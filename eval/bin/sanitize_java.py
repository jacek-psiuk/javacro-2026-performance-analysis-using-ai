#!/usr/bin/env python3
"""Blank out comments in Java sources, keeping every line where it was.

The example sources explain their own bugs in comments ("this is the bug"). An agent that reads
them diagnoses nothing - it copies. Blanking the comments (instead of deleting them) keeps line
numbers stable, so stack traces in recorded artifacts still point at the right statements.

    python3 sanitize_java.py <in.java> <out.java>
    python3 sanitize_java.py --self-test
"""
import sys
import unittest


def sanitize(source: str) -> str:
    out = []
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            while end != -1 and source[end - 1] == '\\':
                end = source.find('"""', end + 1)
            end = n if end == -1 else end + 3
            out.append(source[i:end])
            i = end
        elif c in '"\'':
            j = i + 1
            while j < n and source[j] != c and source[j] != '\n':
                j += 2 if source[j] == '\\' else 1
            out.append(source[i:j + 1])
            i = j + 1
        elif source.startswith('//', i):
            end = source.find('\n', i)
            i = n if end == -1 else end
        elif source.startswith('/*', i):
            end = source.find('*/', i + 2)
            end = n if end == -1 else end + 2
            out.append('\n' * source.count('\n', i, end))
            i = end
        else:
            out.append(c)
            i += 1
    return '\n'.join(line.rstrip() for line in ''.join(out).split('\n'))


def main(argv):
    if argv[1:] == ['--self-test']:
        result = unittest.main(argv=[argv[0]], exit=False, verbosity=1).result
        return 0 if result.wasSuccessful() else 1
    if len(argv) != 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    with open(argv[1], encoding='utf-8') as f:
        source = f.read()
    with open(argv[2], 'w', encoding='utf-8', newline='\n') as f:
        f.write(sanitize(source))
    return 0


class SanitizeTest(unittest.TestCase):

    def test_blanks_markdown_javadoc_and_line_comments_keeping_line_numbers(self):
        # given
        source = ('/// Deadlock caused by lock ordering.\n'
                  'public class Ex1 {\n'
                  '    // Give the other thread time - this is the bug\n'
                  '    int x = 1; // trailing\n'
                  '}\n')

        # when
        result = sanitize(source)

        # then
        self.assertEqual(result, '\npublic class Ex1 {\n\n    int x = 1;\n}\n')

    def test_block_comment_keeps_its_newlines(self):
        # given
        source = 'a();\n/*\nMy app is stuck.\n */\nb();\n'

        # when
        result = sanitize(source)

        # then
        self.assertEqual(result.split('\n').index('b();'), source.split('\n').index('b();'))
        self.assertNotIn('stuck', result)

    def test_comment_markers_inside_literals_are_kept(self):
        # given
        source = ('String url = "http://host/*x*/"; // c\n'
                  'char c = \'/\'; String q = "a\\"//b";\n'
                  'String t = """\n  // not a comment\n  """;\n')

        # when
        result = sanitize(source)

        # then
        self.assertEqual(result, 'String url = "http://host/*x*/";\n'
                                 'char c = \'/\'; String q = "a\\"//b";\n'
                                 'String t = """\n  // not a comment\n  """;\n')

    def test_end_to_end_writes_sanitized_file(self):
        # given
        import os
        import subprocess
        import tempfile
        with tempfile.TemporaryDirectory() as tmp:
            src, dst = os.path.join(tmp, 'A.java'), os.path.join(tmp, 'out', 'A.java')
            os.makedirs(os.path.dirname(dst))
            with open(src, 'w', encoding='utf-8') as f:
                f.write('/// doc\nclass A {} // bug\n')

            # when
            proc = subprocess.run([sys.executable, __file__, src, dst], capture_output=True, text=True)

            # then
            self.assertEqual(proc.returncode, 0, proc.stderr)
            with open(dst, encoding='utf-8') as f:
                self.assertEqual(f.read(), '\nclass A {}\n')

    def test_wrong_arguments_print_usage_and_fail(self):
        # given
        import subprocess

        # when
        proc = subprocess.run([sys.executable, __file__, 'only-one'], capture_output=True, text=True)

        # then
        self.assertEqual(proc.returncode, 2)
        self.assertIn('sanitize_java.py <in.java> <out.java>', proc.stderr)


if __name__ == '__main__':
    sys.exit(main(sys.argv))
