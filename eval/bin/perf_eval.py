#!/usr/bin/env python3
"""Eval harness for the performance analysis agent (perf-analysis-agent/CLAUDE.md).

    python3 eval/bin/perf_eval.py record  [case ...]              record fixtures from the example apps
    python3 eval/bin/perf_eval.py run     [-n N] [--jobs J] [--model M] [--keep] [case ...]
    python3 eval/bin/perf_eval.py score   <case> <workdir>        score one finished run, print JSON
    python3 eval/bin/perf_eval.py summary <results-dir>           table over all runs of one eval
    python3 eval/bin/perf_eval.py --self-test

Cases are eval/cases/<id>.json, fixtures eval/fixtures/<id>/, results eval/results/<run-id>/.
Each run happens in an isolated directory outside the repository, so the agent sees only its
CLAUDE.md, comment-free sources and the fixtures - never the answers in this repository.
"""
import argparse
import concurrent.futures
import datetime
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sanitize_java import sanitize  # noqa: E402

EVAL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_DIR = os.path.dirname(EVAL_DIR)
CASES_DIR = os.path.join(EVAL_DIR, 'cases')
FIXTURES_DIR = os.path.join(EVAL_DIR, 'fixtures')
RESULTS_DIR = os.path.join(EVAL_DIR, 'results')
AGENT_INSTRUCTIONS = os.path.join(REPO_DIR, 'perf-analysis-agent', 'CLAUDE.md')
MAT_WRAPPER = os.path.join(REPO_DIR, 'ci', 'mat.sh')

SEVERITIES = ('Critical', 'High', 'Medium', 'Low')
SERIOUS = ('Critical', 'High')

PROMPT_SUFFIX = """

The diagnostic artifacts are already collected in perf-artifacts/. The application is no longer
running: do not start it and do not attach to any JVM. The source code is in src/.
Write the report as described in CLAUDE.md."""

ALLOWED_TOOLS = ['Read', 'Grep', 'Glob', 'Write', 'Edit',
                 'Bash(jfr:*)', 'Bash(ci/mat.sh:*)', 'Bash(grep:*)', 'Bash(awk:*)', 'Bash(sort:*)',
                 'Bash(uniq:*)', 'Bash(head:*)', 'Bash(tail:*)', 'Bash(wc:*)', 'Bash(cut:*)',
                 'Bash(cat:*)', 'Bash(ls:*)', 'Bash(cd:*)', 'Bash(sed:*)', 'Bash(tr:*)', 'Bash(mkdir:*)']


# ---------------------------------------------------------------- cases and sources

def load_case(case_id, cases_dir=CASES_DIR):
    path = os.path.join(cases_dir, case_id + '.json')
    if not os.path.isfile(path):
        raise SystemExit(f'Unknown case {case_id!r}: {path} does not exist')
    with open(path, encoding='utf-8') as f:
        case = json.load(f)
    case['id'] = case_id
    return case


def all_case_ids(cases_dir=CASES_DIR):
    return sorted(os.path.splitext(f)[0] for f in os.listdir(cases_dir) if f.endswith('.json'))


def prepare_sources(case, dest_root, repo_dir=None):
    """Writes the sanitized sources of a case; returns their paths relative to dest_root."""
    repo_dir = repo_dir or REPO_DIR
    written = []
    for source in case['sources']:
        with open(os.path.join(repo_dir, source['from']), encoding='utf-8') as f:
            text = sanitize(f.read())
        for old, new in source.get('replace', []):
            if old not in text:
                raise ValueError(f"{case['id']}: replacement text {old!r} not found in {source['from']}")
            text = text.replace(old, new)
        rel = source.get('to', source['from'])
        dest = os.path.join(dest_root, rel)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, 'w', encoding='utf-8', newline='\n') as f:
            f.write(text)
        written.append(rel)
    return written


# ---------------------------------------------------------------- record

def jdk_tool(name):
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        for candidate in (name, name + '.exe'):
            path = os.path.join(java_home, 'bin', candidate)
            if os.path.isfile(path):
                return path
    path = shutil.which(name)
    if not path:
        raise SystemExit(f'{name} not found: set JAVA_HOME or put the JDK bin directory on PATH')
    return path


def os_memory(pid):
    if sys.platform.startswith('linux'):
        with open(f'/proc/{pid}/status') as f:
            return ' '.join(line.split(':')[0] + '=' + line.split(':')[1].strip().replace(' ', '')
                            for line in f if line.startswith(('VmRSS', 'VmSwap')))
    if os.name == 'nt':
        out = subprocess.run(['powershell', '-NoProfile', '-Command',
                              f'$p = Get-Process -Id {pid}; "PrivateMemorySize64=$($p.PrivateMemorySize64) '
                              f'WorkingSet64=$($p.WorkingSet64)"'],
                             capture_output=True, text=True, check=True).stdout
        return out.strip()
    return 'unsupported OS'


class Recorder:
    """Runs the recording steps of a case against a live JVM and writes the artifacts."""

    def __init__(self, out_dir, app, pid, ts):
        self.out_dir, self.app, self.pid, self.ts = out_dir, app, pid, ts
        self.started = time.monotonic()
        self.log = []

    def name(self, tool, suffix):
        return os.path.join(self.out_dir, f'{tool}-{self.app}-{self.pid}-{self.ts}{suffix}')

    def tool(self, args, out_path=None):
        elapsed = time.monotonic() - self.started
        shown = ' '.join(os.path.basename(a) if os.path.isabs(a) else a for a in args)
        self.log.append(f'+{elapsed:6.1f}s  {shown}' + (f' > {os.path.basename(out_path)}' if out_path else ''))
        result = subprocess.run(args, capture_output=True, text=True, timeout=300)
        if result.returncode != 0:
            raise RuntimeError(f'{shown} failed: {result.stderr or result.stdout}')
        if out_path:
            with open(out_path, 'w', encoding='utf-8', newline='\n') as f:
                f.write(result.stdout)
        return result.stdout

    def jcmd(self, *args, out_path=None):
        return self.tool([jdk_tool('jcmd'), str(self.pid), *args], out_path)

    def step(self, kind, arg=None):
        if kind == 'sleep':
            time.sleep(arg)
        elif kind == 'context':
            parts = []
            for command in ('VM.version', 'VM.uptime', 'VM.flags', 'VM.command_line'):
                parts.append(f'$ jcmd {self.pid} {command}\n{self.jcmd(command)}')
            parts.append(f'CPU count: {os.cpu_count()}\n')
            with open(self.name('context', '.txt'), 'w', encoding='utf-8', newline='\n') as f:
                f.write('\n'.join(parts))
        elif kind == 'jstack':
            self.tool([jdk_tool('jstack'), '-l', str(self.pid)], self.name('thread', f'-{arg}.txt'))
        elif kind == 'threads_json':
            self.jcmd('Thread.dump_to_file', '-format=json', self.name('threads', f'-{arg}.json'))
        elif kind == 'histogram':
            self.jcmd('GC.class_histogram', out_path=self.name('histo', f'-{arg}.txt'))
        elif kind == 'heap_dump':
            self.jcmd('GC.heap_dump', self.name('heap', '.hprof'))
        elif kind == 'nmt_baseline':
            self.jcmd('VM.native_memory', 'baseline')
        elif kind == 'nmt_summary':
            self.jcmd('VM.native_memory', 'summary', out_path=self.name('nmt', f'-{arg}-summary.txt'))
        elif kind == 'nmt_diff':
            self.jcmd('VM.native_memory', 'summary.diff', out_path=self.name('nmt', f'-{arg}-diff.txt'))
        elif kind == 'os_memory':
            elapsed = time.monotonic() - self.started
            self.log.append(f'+{elapsed:6.1f}s  OS memory of {self.pid}')
            with open(self.name('os-memory', '.txt'), 'a', encoding='utf-8', newline='\n') as f:
                f.write(f'+{elapsed:.0f}s {os_memory(self.pid)}\n')
        elif kind == 'jfr':
            path = self.name('jfr', '.jfr')
            self.jcmd('JFR.start', 'name=eval', 'settings=profile', f'duration={arg}s', f'filename={path}')
            time.sleep(arg + 3)
            for _ in range(30):
                if os.path.isfile(path) and 'eval' not in self.jcmd('JFR.check'):
                    break
                time.sleep(1)
        else:
            raise ValueError(f'Unknown recording step {kind!r}')


def record(case):
    spec = case['record']
    out_dir = os.path.join(FIXTURES_DIR, case['id'])
    shutil.rmtree(out_dir, ignore_errors=True)
    os.makedirs(out_dir)
    if 'copy' in spec:
        for rel in spec['copy']:
            shutil.copy2(os.path.join(REPO_DIR, rel), out_dir)
        print(f"{case['id']}: copied {len(spec['copy'])} files")
        return

    with tempfile.TemporaryDirectory(prefix='perf-eval-build-') as build:
        sources = prepare_sources(case, os.path.join(build, 'src'))
        classes = os.path.join(build, 'classes')
        subprocess.run([jdk_tool('javac'), '-d', classes, *(os.path.join(build, 'src', s) for s in sources)],
                       check=True)
        console = os.path.join(build, 'console.txt')
        with open(console, 'w', encoding='utf-8') as out:
            app_proc = subprocess.Popen([jdk_tool('java'), *spec.get('jvm', []), '-cp', classes, case['main']],
                                        stdout=out, stderr=subprocess.STDOUT)
        try:
            pid = app_proc.pid
            app = spec.get('app', case['main'].rsplit('.', 1)[-1].lower())
            ts = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
            recorder = Recorder(out_dir, app, pid, ts)
            deadline = time.monotonic() + 30
            while 'PID:' not in open(console, encoding='utf-8').read():
                if app_proc.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError(f"{case['id']}: the application did not start:\n"
                                       + open(console, encoding='utf-8').read())
                time.sleep(0.2)
            for step in spec['steps']:
                recorder.step(*step)
        finally:
            app_proc.kill()
            app_proc.wait()
        shutil.copy(console, recorder.name('console', '.txt'))
        with open(recorder.name('collection-log', '.txt'), 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(recorder.log) + '\n')
    print(f"{case['id']}: recorded {len(os.listdir(out_dir))} files into {out_dir}")


# ---------------------------------------------------------------- run

def prepare_workdir(case, workdir):
    fixtures = os.path.join(FIXTURES_DIR, case['id'])
    if not os.path.isdir(fixtures) or not os.listdir(fixtures):
        raise SystemExit(f"No fixtures for {case['id']}: run `perf_eval.py record {case['id']}` first")
    os.makedirs(workdir)
    shutil.copy(AGENT_INSTRUCTIONS, os.path.join(workdir, 'CLAUDE.md'))
    prepare_sources(case, workdir, REPO_DIR)
    shutil.copytree(fixtures, os.path.join(workdir, 'perf-artifacts'))
    os.makedirs(os.path.join(workdir, 'ci'))
    shutil.copy(MAT_WRAPPER, os.path.join(workdir, 'ci', 'mat.sh'))


def claude_command(case, claude_cmd, model=None, max_turns=60):
    command = [*claude_cmd, '-p', case['prompt'] + PROMPT_SUFFIX,
               '--output-format', 'json', '--max-turns', str(max_turns),
               '--setting-sources', 'project', '--strict-mcp-config', '--no-session-persistence',
               '--allowedTools', *ALLOWED_TOOLS]
    if model:
        command += ['--model', model]
    return command


def agent_env(workdir):
    env = dict(os.environ, MAT_WORK_DIR=os.path.join(workdir, 'mat-work'))
    jdks = os.path.join(os.path.expanduser('~'), '.jdks')
    if 'MAT_HOME' not in env and os.path.isdir(os.path.join(jdks, 'mat')):
        env['MAT_HOME'] = os.path.join(jdks, 'mat')
    if 'MAT_JAVA' not in env and os.name == 'nt' and shutil.which('javaw'):
        env['MAT_JAVA'] = shutil.which('javaw')
    return env


def find_report(workdir):
    reports = glob.glob(os.path.join(workdir, 'perf-artifacts', 'performance-analysis-*.md'))
    return max(reports, key=os.path.getmtime) if reports else None


def run_one(case, run_no, run_dir, work_root, claude_cmd, model, max_turns, timeout, keep):
    label = f"{case['id']}-{run_no}"
    workdir = os.path.join(work_root, label)
    out_dir = os.path.join(run_dir, label)
    os.makedirs(out_dir)
    prepare_workdir(case, workdir)
    started = time.monotonic()
    try:
        proc = subprocess.run(claude_command(case, claude_cmd, model, max_turns), cwd=workdir,
                              env=agent_env(workdir), capture_output=True, text=True, encoding='utf-8',
                              timeout=timeout)
        stdout, stderr = proc.stdout, proc.stderr
    except subprocess.TimeoutExpired as e:
        stdout, stderr = '', f'Timed out after {timeout}s\n{e.stderr or ""}'
    with open(os.path.join(out_dir, 'claude-result.json'), 'w', encoding='utf-8') as f:
        f.write(stdout)
    if stderr:
        with open(os.path.join(out_dir, 'claude-stderr.txt'), 'w', encoding='utf-8') as f:
            f.write(stderr)
    try:
        claude_result = json.loads(stdout)
    except json.JSONDecodeError:
        claude_result = {}

    report_path = find_report(workdir)
    if report_path:
        shutil.copy(report_path, os.path.join(out_dir, 'report.md'))
        with open(report_path, encoding='utf-8') as f:
            report = f.read()
    else:
        report = claude_result.get('result', '')
    fixtures = set(os.listdir(os.path.join(FIXTURES_DIR, case['id'])))
    for path in glob.glob(os.path.join(workdir, 'perf-artifacts', '*')):
        if os.path.basename(path) not in fixtures and os.path.isfile(path) and os.path.getsize(path) < 5_000_000:
            os.makedirs(os.path.join(out_dir, 'agent-artifacts'), exist_ok=True)
            shutil.copy(path, os.path.join(out_dir, 'agent-artifacts'))

    result = score(case, report, workdir)
    result.update(run=run_no, report_file=bool(report_path), wall_s=round(time.monotonic() - started),
                  cost_usd=claude_result.get('total_cost_usd'), turns=claude_result.get('num_turns'),
                  is_error=claude_result.get('is_error'),
                  permission_denials=len(claude_result.get('permission_denials') or []))
    with open(os.path.join(out_dir, 'score.json'), 'w', encoding='utf-8') as f:
        json.dump(result, f, indent=2)
    if not keep:
        shutil.rmtree(workdir, ignore_errors=True)
    print(f"{label}: {'PASS' if result['passed'] else 'FAIL'} verdict={result['verdict']} "
          f"recall={result['recall']} unexpected={len(result['unexpected'])} "
          f"invalid_citations={len(result['citations']['invalid'])} cost={result['cost_usd']}", flush=True)
    return result


def run(case_ids, runs, jobs, model, max_turns, timeout, keep, claude_cmd):
    cases = [load_case(c) for c in case_ids]
    run_id = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    run_dir = os.path.join(RESULTS_DIR, run_id)
    base = os.path.realpath(os.environ.get('EVAL_WORK_DIR') or tempfile.gettempdir())
    work_root = os.path.join(base, 'perf-agent-eval', run_id)
    os.makedirs(run_dir)
    with open(os.path.join(run_dir, 'eval.json'), 'w', encoding='utf-8') as f:
        json.dump({'cases': case_ids, 'runs': runs, 'model': model, 'max_turns': max_turns}, f, indent=2)
    print(f'Results: {run_dir}\nWork dirs: {work_root}', flush=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=jobs) as pool:
        futures = [pool.submit(run_one, case, i, run_dir, work_root, claude_cmd, model, max_turns, timeout, keep)
                   for case in cases for i in range(1, runs + 1)]
        for future in futures:
            future.result()
    print(summary(run_dir))


# ---------------------------------------------------------------- score

ISSUE_HEADING = re.compile(r'^###\s+2\.(\d+)\s+(.+?)\s+[-–—]+\s+\**(Critical|High|Medium|Low)\**\s*$',
                           re.MULTILINE | re.IGNORECASE)
CITATION = re.compile(r'([A-Za-z_$][\w$]*\.java):(\d+)')


def parse_report(text):
    verdict = re.search(r'Action:\s*\**\s*(REQUIRED|RECOMMENDED|NONE)\b', text)
    status = re.search(r'Analysis status:\s*\**\s*(complete|partial|failed)', text, re.IGNORECASE)
    headings = list(ISSUE_HEADING.finditer(text))
    issues = []
    for i, m in enumerate(headings):
        end = headings[i + 1].start() if i + 1 < len(headings) else len(text)
        next_section = re.search(r'^##\s', text[m.end():end], re.MULTILINE)
        body_end = m.end() + next_section.start() if next_section else end
        issues.append({'title': m.group(2).strip(), 'severity': m.group(3).capitalize(),
                       'body': text[m.end():body_end]})
    return {'verdict': verdict.group(1) if verdict else None,
            'status': status.group(1).lower() if status else None,
            'issues': issues}


def check_citations(text, src_root):
    sources = {}
    for path in glob.glob(os.path.join(src_root, 'src', '**', '*.java'), recursive=True):
        with open(path, encoding='utf-8') as f:
            sources[os.path.basename(path)] = f.read().split('\n')
    checked, invalid = 0, []
    for file, line in sorted(set(CITATION.findall(text))):
        if file not in sources:
            continue
        checked += 1
        n = int(line)
        lines = sources[file]
        if n < 1 or n > len(lines):
            invalid.append(f'{file}:{n} (file has {len(lines)} lines)')
        elif not lines[n - 1].strip() or lines[n - 1].strip() in ('{', '}'):
            invalid.append(f'{file}:{n} (blank line or lone brace)')
    return {'checked': checked, 'invalid': invalid}


def cites_expected_line(text, expected_lines):
    return any(int(line) in expected_lines.get(file, [])
               for file, line in CITATION.findall(text))


def score(case, report_text, workdir):
    expected = case['expected']
    report = parse_report(report_text or '')
    issues = report['issues']
    forbidden_re = expected.get('forbidden_titles')
    assigned = [None] * len(issues)
    for idx, issue in enumerate(issues):
        if forbidden_re and re.search(forbidden_re, issue['title'], re.IGNORECASE):
            continue
        for exp in expected.get('issues', []):
            if re.search(exp['match'], issue['title'], re.IGNORECASE):
                assigned[idx] = exp['id']
                break

    found = []
    for exp in expected.get('issues', []):
        hits = [i for i, a in enumerate(assigned) if a == exp['id']]
        entry = {'id': exp['id'], 'found': bool(hits)}
        if hits:
            first = issues[hits[0]]
            entry['severity'] = first['severity']
            entry['severity_ok'] = first['severity'] in exp.get('severities', SEVERITIES)
            entry['line_ok'] = any(cites_expected_line(issues[i]['title'] + issues[i]['body'], exp['lines'])
                                   for i in hits)
            if 'rank' in exp:
                entry['rank_ok'] = hits[0] + 1 == exp['rank']
            if len(hits) > 1:
                entry['duplicates'] = len(hits) - 1
        found.append(entry)

    unexpected = [{'title': i['title'], 'severity': i['severity']}
                  for i, a in zip(issues, assigned) if a is None]
    forbidden = [i['title'] for i in issues if forbidden_re and re.search(forbidden_re, i['title'], re.IGNORECASE)]
    citations = check_citations(report_text or '', workdir)
    verdict_ok = report['verdict'] in expected['verdicts']
    recall = round(sum(e['found'] for e in found) / len(found), 2) if found else None

    passed = (verdict_ok
              and all(e['found'] and e['severity_ok'] and e['line_ok'] and e.get('rank_ok', True) for e in found)
              and not forbidden
              and not any(u['severity'] in SERIOUS for u in unexpected)
              and not citations['invalid'])
    return {'case': case['id'], 'passed': passed,
            'verdict': report['verdict'], 'verdict_expected': expected['verdicts'], 'verdict_ok': verdict_ok,
            'status': report['status'], 'issues_reported': len(issues), 'expected_issues': found,
            'recall': recall, 'unexpected': unexpected, 'forbidden': forbidden, 'citations': citations}


# ---------------------------------------------------------------- summary

def summary(run_dir):
    scores = []
    for path in sorted(glob.glob(os.path.join(run_dir, '*', 'score.json'))):
        with open(path, encoding='utf-8') as f:
            scores.append(json.load(f))
    if not scores:
        return f'No score.json files in {run_dir}'

    def avg(values):
        values = [v for v in values if v is not None]
        return f'{sum(values) / len(values):.2f}' if values else '-'

    rows = ['| Case | Passed | Verdict OK | Recall | Lines OK | Unexpected (serious) | Forbidden | '
            'Bad citations | Cost $ | Turns |',
            '|---|---|---|---|---|---|---|---|---|---|']
    by_case = {}
    for s in scores:
        by_case.setdefault(s['case'], []).append(s)
    for case_id, runs in by_case.items():
        n = len(runs)
        lines = [e['line_ok'] for r in runs for e in r['expected_issues'] if e['found']]
        rows.append(' | '.join([
            f'| {case_id}', f"{sum(r['passed'] for r in runs)}/{n}", f"{sum(r['verdict_ok'] for r in runs)}/{n}",
            avg(r['recall'] for r in runs), f'{sum(lines)}/{len(lines)}' if lines else '-',
            f"{sum(len(r['unexpected']) for r in runs)} "
            f"({sum(u['severity'] in SERIOUS for r in runs for u in r['unexpected'])})",
            str(sum(len(r['forbidden']) for r in runs)),
            str(sum(len(r['citations']['invalid']) for r in runs)),
            avg(r.get('cost_usd') for r in runs), avg(r.get('turns') for r in runs)]) + ' |')
    total = len(scores)
    rows.append('')
    rows.append(f"Passed {sum(s['passed'] for s in scores)}/{total} runs, "
                f"verdict correct {sum(s['verdict_ok'] for s in scores)}/{total}.")
    text = '\n'.join(rows)
    with open(os.path.join(run_dir, 'summary.md'), 'w', encoding='utf-8') as f:
        f.write(text + '\n')
    return text


# ---------------------------------------------------------------- CLI

def main(argv):
    if argv[1:] == ['--self-test']:
        result = unittest.main(argv=[argv[0]], exit=False, verbosity=1).result
        return 0 if result.wasSuccessful() else 1
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('record')
    p.add_argument('cases', nargs='*')
    p = sub.add_parser('run')
    p.add_argument('cases', nargs='*')
    p.add_argument('-n', '--runs', type=int, default=3)
    p.add_argument('--jobs', type=int, default=2)
    p.add_argument('--model')
    p.add_argument('--max-turns', type=int, default=60)
    p.add_argument('--timeout', type=int, default=1800, help='seconds per agent run')
    p.add_argument('--keep', action='store_true', help='keep the agent work directories')
    p.add_argument('--claude', default='claude', help='claude executable (a test stub in the self-test)')
    p = sub.add_parser('score')
    p.add_argument('case')
    p.add_argument('workdir')
    p = sub.add_parser('summary')
    p.add_argument('results_dir')
    args = parser.parse_args(argv[1:])

    if args.command == 'record':
        for case_id in args.cases or all_case_ids():
            record(load_case(case_id))
    elif args.command == 'run':
        claude = shutil.which(args.claude) or args.claude
        claude_cmd = [sys.executable, claude] if claude.endswith('.py') else [claude]
        run(args.cases or all_case_ids(), args.runs, args.jobs, args.model, args.max_turns, args.timeout,
            args.keep, claude_cmd)
    elif args.command == 'score':
        case = load_case(args.case)
        report_path = find_report(args.workdir)
        if not report_path:
            print(f'No perf-artifacts/performance-analysis-*.md in {args.workdir}', file=sys.stderr)
            return 1
        with open(report_path, encoding='utf-8') as f:
            print(json.dumps(score(case, f.read(), args.workdir), indent=2))
    elif args.command == 'summary':
        print(summary(args.results_dir))
    return 0


# ---------------------------------------------------------------- self-test

TEST_CASE = {
    'id': 'demo',
    'sources': [{'from': 'src/Demo.java', 'replace': [['DemoFixed', 'Demo']]}],
    'prompt': 'The demo is slow.',
    'expected': {
        'verdicts': ['REQUIRED'],
        'forbidden_titles': 'deadlock',
        'issues': [
            {'id': 'contention', 'match': 'contention|lock', 'severities': ['Critical', 'High'],
             'lines': {'Demo.java': [4, 5]}, 'rank': 1},
            {'id': 'regex', 'match': 'regex|pattern', 'lines': {'Demo.java': [7]}},
        ],
    },
}

TEST_SOURCE = ('/// The lock is held during I/O - this is the bug\n'
               'class DemoFixed {\n'
               '    static final Object LOCK = new Object();\n'
               '    static void audit() {\n'
               '        synchronized (LOCK) { sleep(2); }\n'
               '    }\n'
               '    static boolean valid(String s) { return s.matches("[A-Z]+"); }\n'
               '}\n')

GOOD_REPORT = """# Performance analysis: demo (artifacts, 2026-10-11)

## 1. Summary

**Action: REQUIRED**

Analysis status: complete

| # | Issue | Severity | Impact |
|---|---|---|---|
| 1 | Lock contention in audit | High | 95% thread time |

## 2. Detected issues

### 2.1 Lock contention in audit() - High

- **Evidence:** 4451 jdk.JavaMonitorEnter events.
- **Root cause:** `Demo.java:5` sleeps while holding the lock.

### 2.2 Regex compiled per call - Medium

- **Root cause:** `Demo.java:7` calls String.matches.

## 3. Related observations

GC is healthy, see Thread.java:1583.
"""


def write_test_repo(root):
    os.makedirs(os.path.join(root, 'src'))
    with open(os.path.join(root, 'src', 'Demo.java'), 'w', encoding='utf-8') as f:
        f.write(TEST_SOURCE)


class PrepareSourcesTest(unittest.TestCase):

    def test_sources_are_sanitized_and_renamed_keeping_lines(self):
        # given
        with tempfile.TemporaryDirectory() as repo, tempfile.TemporaryDirectory() as dest:
            write_test_repo(repo)

            # when
            written = prepare_sources(TEST_CASE, dest, repo)

            # then
            with open(os.path.join(dest, written[0]), encoding='utf-8') as f:
                lines = f.read().split('\n')
            self.assertEqual(lines[0], '')
            self.assertEqual(lines[1], 'class Demo {')
            self.assertEqual(lines[4], '        synchronized (LOCK) { sleep(2); }')

    def test_missing_replacement_text_fails_instead_of_leaking_the_answer(self):
        # given
        case = dict(TEST_CASE, sources=[{'from': 'src/Demo.java', 'replace': [['Ex9Fixed', 'Ex9']]}])
        with tempfile.TemporaryDirectory() as repo, tempfile.TemporaryDirectory() as dest:
            write_test_repo(repo)

            # when / then
            with self.assertRaisesRegex(ValueError, 'Ex9Fixed'):
                prepare_sources(case, dest, repo)


class ScoreTest(unittest.TestCase):

    def score_report(self, report, case=TEST_CASE):
        with tempfile.TemporaryDirectory() as repo, tempfile.TemporaryDirectory() as workdir:
            write_test_repo(repo)
            prepare_sources(case, workdir, repo)
            return score(case, report, workdir)

    def test_parse_report_reads_verdict_status_and_issues(self):
        # when
        report = parse_report(GOOD_REPORT)

        # then
        self.assertEqual(report['verdict'], 'REQUIRED')
        self.assertEqual(report['status'], 'complete')
        self.assertEqual([(i['title'], i['severity']) for i in report['issues']],
                         [('Lock contention in audit()', 'High'), ('Regex compiled per call', 'Medium')])
        self.assertNotIn('GC is healthy', report['issues'][1]['body'])

    def test_good_report_passes(self):
        # when
        result = self.score_report(GOOD_REPORT)

        # then
        self.assertTrue(result['passed'], result)
        self.assertEqual(result['recall'], 1.0)
        self.assertEqual(result['citations'], {'checked': 2, 'invalid': []})

    def test_wrong_verdict_fails(self):
        # when
        result = self.score_report(GOOD_REPORT.replace('Action: REQUIRED', 'Action: NONE'))

        # then
        self.assertFalse(result['passed'])
        self.assertFalse(result['verdict_ok'])

    def test_line_outside_the_expected_statements_fails(self):
        # when
        result = self.score_report(GOOD_REPORT.replace('Demo.java:5', 'Demo.java:3'))

        # then
        self.assertFalse(result['passed'])
        self.assertFalse(result['expected_issues'][0]['line_ok'])

    def test_citation_of_a_blank_line_is_invalid(self):
        # when
        result = self.score_report(GOOD_REPORT.replace('see Thread.java:1583', 'see Demo.java:1'))

        # then
        self.assertFalse(result['passed'])
        self.assertEqual(result['citations']['invalid'], ['Demo.java:1 (blank line or lone brace)'])

    def test_wrong_ranking_fails(self):
        # given
        report = ('**Action: REQUIRED**\n\n## 2. Detected issues\n\n'
                  '### 2.1 Regex compiled per call - Medium\n\nDemo.java:7\n\n'
                  '### 2.2 Lock contention in audit() - High\n\nDemo.java:5\n\n'
                  '## 3. Related observations\n')

        # when
        result = self.score_report(report)

        # then
        self.assertFalse(result['expected_issues'][0]['rank_ok'])
        self.assertFalse(result['passed'])

    def test_invented_serious_issue_and_forbidden_title_fail(self):
        # given
        report = GOOD_REPORT.replace('## 3. Related observations',
                                     '### 2.3 Deadlock between workers - Critical\n\nDemo.java:5\n\n'
                                     '## 3. Related observations')

        # when
        result = self.score_report(report)

        # then
        self.assertFalse(result['passed'])
        self.assertEqual(result['unexpected'], [{'title': 'Deadlock between workers', 'severity': 'Critical'}])
        self.assertEqual(result['forbidden'], ['Deadlock between workers'])

    def test_healthy_case_tolerates_low_findings_only(self):
        # given
        healthy = dict(TEST_CASE, expected={'verdicts': ['NONE', 'RECOMMENDED'], 'issues': []})
        report = ('**Action: NONE**\n\nAnalysis status: complete\n\n## 2. Detected issues\n\n'
                  '### 2.1 Regex compiled per call - Low\n\nDemo.java:7\n\n## 3. Related observations\n')

        # when
        ok = self.score_report(report, healthy)
        bad = self.score_report(report.replace('NONE', 'REQUIRED').replace('- Low', '- High'), healthy)

        # then
        self.assertTrue(ok['passed'], ok)
        self.assertIsNone(ok['recall'])
        self.assertFalse(bad['passed'])

    def test_missing_report_fails(self):
        # when
        result = self.score_report('')

        # then
        self.assertFalse(result['passed'])
        self.assertIsNone(result['verdict'])
        self.assertEqual(result['recall'], 0.0)


FAKE_CLAUDE = r'''
import json, os, sys
os.makedirs('perf-artifacts', exist_ok=True)
with open('perf-artifacts/performance-analysis-demo-20261011-120000.md', 'w') as f:
    f.write(os.environ['FAKE_REPORT'])
with open('seen-args.json', 'w') as f:
    json.dump({'argv': sys.argv[1:], 'claude_md': os.path.exists('CLAUDE.md'),
               'mat_work_dir': os.environ.get('MAT_WORK_DIR')}, f)
print(json.dumps({'result': 'done', 'is_error': False, 'num_turns': 7, 'total_cost_usd': 0.5}))
'''


class EndToEndTest(unittest.TestCase):

    def test_run_scores_a_stubbed_agent_in_an_isolated_workdir(self):
        # given
        from unittest import mock
        with tempfile.TemporaryDirectory() as tmp:
            repo = os.path.join(tmp, 'repo')
            write_test_repo(repo)
            fixtures, run_dir = os.path.join(tmp, 'fixtures'), os.path.join(tmp, 'results', 'r1')
            os.makedirs(os.path.join(fixtures, 'demo'))
            os.makedirs(run_dir)
            with open(os.path.join(fixtures, 'demo', 'thread-demo-1-1.txt'), 'w') as f:
                f.write('"worker-1" BLOCKED')
            fake = os.path.join(tmp, 'fake_claude.py')
            with open(fake, 'w') as f:
                f.write(FAKE_CLAUDE)
            with mock.patch.dict(globals(), FIXTURES_DIR=fixtures, REPO_DIR=repo),                     mock.patch.dict(os.environ, FAKE_REPORT=GOOD_REPORT):
                # when
                result = run_one(TEST_CASE, 1, run_dir, os.path.join(tmp, 'work'),
                                 [sys.executable, fake], None, 60, 60, True)

            # then
            self.assertTrue(result['passed'], result)
            self.assertEqual((result['cost_usd'], result['turns']), (0.5, 7))
            workdir = os.path.join(tmp, 'work', 'demo-1')
            with open(os.path.join(workdir, 'seen-args.json')) as f:
                seen = json.load(f)
            self.assertTrue(seen['claude_md'])
            self.assertIn('--setting-sources', seen['argv'])
            self.assertTrue(seen['argv'][1].startswith('The demo is slow.'))
            self.assertTrue(seen['mat_work_dir'].endswith('mat-work'))
            self.assertTrue(os.path.isfile(os.path.join(workdir, 'perf-artifacts', 'thread-demo-1-1.txt')))
            self.assertTrue(os.path.isfile(os.path.join(workdir, 'src', 'Demo.java')))
            self.assertTrue(os.path.isfile(os.path.join(run_dir, 'demo-1', 'report.md')))
            self.assertIn('| demo | 1/1 |', summary(run_dir))

    def test_score_command_without_report_fails_with_message(self):
        # given
        with tempfile.TemporaryDirectory() as workdir:
            # when
            proc = subprocess.run([sys.executable, __file__, 'score', all_case_ids()[0], workdir],
                                  capture_output=True, text=True)

            # then
            self.assertEqual(proc.returncode, 1)
            self.assertIn('No perf-artifacts/performance-analysis-*.md', proc.stderr)


if __name__ == '__main__':
    sys.exit(main(sys.argv))
