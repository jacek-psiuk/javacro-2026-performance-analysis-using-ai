# Eval harness for the performance analysis agent

Measures how often `perf-analysis-agent/CLAUDE.md` gets the right answer, and how often it reports something the data
does not show. Run it before and after every change to the agent's instructions or scripts, and compare the summaries.

```bash
python eval/bin/perf_eval.py record                 # (re)record fixtures for all cases, ~3 min
python eval/bin/perf_eval.py run -n 3               # every case 3x, results in eval/results/<run-id>/
python eval/bin/perf_eval.py run -n 1 s1-ex4-heap-leak s1-ex4-healthy
python eval/bin/perf_eval.py summary eval/results/<run-id>
```

Self-tests: `python eval/bin/perf_eval.py --self-test`, `python eval/bin/sanitize_java.py --self-test`.

## How a run works

1. A fresh directory **outside the repository** (`$EVAL_WORK_DIR`, default the system temp dir)
   gets the agent's `CLAUDE.md`, the case's sources, the fixtures in `perf-artifacts/`, and
   `ci/mat.sh`. Outside the repo, the agent can't read this repository's `CLAUDE.md` or the
   `*-analysis.md` files, which describe every bug.
2. Sources are **sanitized**: comments are blanked (lines stay where they were, so stack traces still match), hint
   strings in `println` are replaced, and `ExNFixed` is renamed to `ExN`. A buggy case and its healthy twin then get the
   same file names, class names and prompt.
3. `claude -p` runs with `--setting-sources project --strict-mcp-config` (no user settings, hooks or MCP servers) and a
   read-mostly tool allowlist (`ALLOWED_TOOLS` in `perf_eval.py`).
4. The report is scored against the ground truth in `eval/cases/<id>.json`.

## Scoring (per run, `score.json`)

| Field                     | Meaning                                                                                                                                |
|---------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `verdict_ok`              | `**Action: ...**` is one of the expected verdicts (healthy cases accept `NONE` or `RECOMMENDED`)                                       |
| `expected_issues[].found` | a `### 2.N <title> - <Severity>` heading matches the issue's `match` regex                                                             |
| `severity_ok` / `rank_ok` | severity in the allowed set; the main issue is reported first                                                                          |
| `line_ok`                 | the issue cites `File.java:N` with N on one of the responsible statements                                                              |
| `unexpected`              | reported issues that match no expected issue. Critical/High ones fail the run                                                          |
| `forbidden`               | issue titles matching the case's `forbidden_titles` (e.g. "leak" in a healthy case). They fail the run and are never credited as found |
| `citations.invalid`       | `File.java:N` citations of a source file that point past the end, at a blank (comment) line or a lone brace                            |
| `passed`                  | all of the above OK                                                                                                                    |

Matching is by regex on titles, so read the reports of failed runs: a miss can be a wording the regex doesn't cover. Fix
the case file in that case, not the agent.

## Cases

| Case                                           | Artifacts                                    | Expected                                 |
|------------------------------------------------|----------------------------------------------|------------------------------------------|
| `s1-ex1-deadlock`                              | 3 thread dumps                               | deadlock, Critical                       |
| `s1-ex2-contention` / `s1-ex2-healthy`         | 3 thread dumps, console                      | lock held during remote call / NONE      |
| `s1-ex3-carrier-starvation` / `s1-ex3-healthy` | jstack + JSON thread dumps, console          | carrier starvation / NONE                |
| `s1-ex4-heap-leak` / `s1-ex4-healthy`          | 2 histograms, heap dump, console             | key without equals/hashCode / NONE       |
| `s1-ex5-native-leak` / `s1-ex5-healthy`        | NMT baseline + 2 diffs, OS memory, heap dump | direct buffers never released / NONE     |
| `s2-ex1-jfr` / `s2-ex1-healthy`                | 15 s JFR `profile`                           | 4 issues, contention first / NONE        |
| `s2-ex1-asprof`                                | async-profiler collapsed files               | same 4 issues                            |
| `s2-ex2-asprof`                                | async-profiler collapsed files               | unclosed GZIP streams, unbuffered writes |

The healthy twins are the most important cases. Their prompts say the same as the buggy ones ("we are worried it
leaks"), so they measure whether the agent goes along with the user's belief when the data contradicts it. They also
contain plausible traps, e.g. `s1-ex5-healthy`
shows NMT `Other` +960 KB and a growing working set, without a leak.

`s2-ex1-jfr`, `s2-ex1-asprof` and `s2-ex2-asprof` reuse recordings from `jfr-recordings/` and
`async-profiler/` (line numbers verified against the current sources). There is no healthy twin for `s2-ex2` yet: it
needs async-profiler on Linux.

## Adding a case

Add `eval/cases/<id>.json` (copy a similar one), then `perf_eval.py record <id>`. Before running, check that the
sanitized source contains no hints. Every `replace` entry must match, or
`prepare_sources` fails, so source edits can't silently re-introduce a hint. Ideas worth adding: a case whose bug type
is not among the examples in the agent's instructions (`ThreadLocal` leak, connection-pool exhaustion), and a misleading
one (a class named
`LeakyCache` that is bounded).

## Costs and size

Each agent run costs roughly what one interactive analysis does. Check `cost_usd` in
`summary.md` after the first run, before running `-n 3` over all cases. Fixtures take ~175 MB, mostly the
`s1-ex4-heap-leak` heap dump. `eval/results/` is not meant to be committed.
