#!/usr/bin/env python3
r"""Present-tense campaign tool: count history-shaped lines, and prove an edit touched only prose.

    python scripts/present_tense.py census [--hits] <path>...
    python scripts/present_tense.py verify <base-rev> <path>...
    python scripts/present_tense.py self-test

A campaign tool, not a CI gate: it has no baseline and nothing runs it
automatically.

census
    For each path, and in total: the in-scope tracked files under it, their
    lines, the lines matching the bead-id pattern and the lines matching the
    history-word pattern.  Both patterns are candidate filters, never verdicts.
    `--hits` also lists every matching line, tagged with where each match sits
    (comment, string, regex, code or prose), and closes with a count per tag,
    so an identifier or an emitted string is told apart from prose.

    In scope: tracked files with a code extension (CODE_EXTS) and markdown
    under `spec/`.  Skipped, and counted as skipped: every other file, anything
    under a `findings/` directory, `.beads/` or `ai/`, and a file whose first
    lines say GENERATED.  A path matching no tracked file exits 1.

verify
    For every file under the given paths that differs between <base-rev> and
    the working tree (committed, staged, unstaged and untracked alike), proves
    that everything except comments and string contents is identical.  Prints
    each file's verdict with its line-ending counts, and names the file and
    the first differing token of every file that fails.

    Per file type:
      Clojure (.clj .cljs .cljc .edn .bb)  `;` comments are dropped and string
          contents blanked.  Regex literals and char literals (`\"` and `\;`
          among them) are code, compared verbatim.
      JS/TS (.js .cjs .mjs .ts)            `//` and `/* */` comments are dropped,
          string and template-literal text blanked.  `${...}` and regex
          literals are code.
      Python (.py)                         `tokenize`: comments are dropped and
          string contents blanked.
      Shell, PowerShell, YAML              `#` comments are dropped (and `<# #>`
          in PowerShell); what remains is compared line by line.
      Markdown                             prose is exempt, but heading lines
          must be unchanged, because a heading's slug is its anchor.
      Anything else                        compared byte for byte.

    Where it is conservative:
      * a changed string is not told apart from a changed docstring, so an
        edited error message passes; tests and review guard emitted strings;
      * adding or deleting a whole string, a whole Python docstring or a whole
        file fails; rewrite the text instead;
      * a quote the line lexer misreads (shell, PowerShell, YAML) turns a
        comment edit into a failure, never a code edit into a pass;
      * prose inside a `(comment ...)` or `#_` form is compared as code unless
        it sits in a `;` comment or a string.
    It also fails a file with MIXED line endings (CRLF beside a bare LF, or a
    lone CR), the damage an editing tool leaves in a CRLF checkout.  A file
    rewritten wholly to LF is not mixed and passes; `git status` shows it as
    modified with an empty `git diff`.

    Exit 0: every changed file passed.  1: at least one failed.  2: usage
    error or unknown revision.  3: no file differs, so nothing was checked,
    which is what a wrong base or a wrong path looks like.

self-test
    Builds a throwaway repository and shows `verify` RED on planted one-token
    code changes and GREEN on comment-only and docstring-only changes for every
    lexer, restores each plant and checks the restore by blob hash, and checks
    both census patterns on known lines.
"""

import argparse
import bisect
import io
import os
import re
import shutil
import subprocess
import sys
import tempfile
import tokenize

BEAD_ID = re.compile(r'(?<![A-Za-z0-9-])rf2-[a-z0-9]+(?:-[0-9]+)?(?:\.[0-9]+)*')
HISTORY = re.compile(r'\b(previously|used to|no longer|formerly|was renamed|were renamed'
                     r'|historically|originally|retired|superseded|replaced by)\b', re.I)

CLOJURE = {'.clj', '.cljs', '.cljc', '.edn', '.bb'}
JS = {'.js', '.cjs', '.mjs', '.ts'}
HASH = {'.sh', '.ps1', '.yml', '.yaml'}
CODE_EXTS = CLOJURE | JS | HASH | {'.py'}


def ext_of(path):
    return os.path.splitext(path)[1].lower()


# ---------------------------------------------------------------------------
# Lexers.  Each returns spans (kind, start, end) over LF-normalised text; kind
# is 'code', 'str', 'regex' or 'comment'.  The token lexers (Clojure, JS,
# Python) cover every non-blank character; the line lexer (#) emits comments
# only.

def lex_clojure(s):
    spans, i, n = [], 0, len(s)
    while i < n:
        c = s[i]
        if c in ' \t\r\n,\f':
            i += 1
        elif c == ';':
            j = s.find('\n', i)
            j = n if j < 0 else j
            spans.append(('comment', i, j))
            i = j
        elif c == '"' or s.startswith('#"', i):
            start, i = i, i + (1 if c == '"' else 2)
            while i < n and s[i] != '"':
                i += 2 if s[i] == '\\' else 1
            i = min(i + 1, n)
            spans.append(('str' if c == '"' else 'regex', start, i))
        elif c == '\\':
            # A char literal: the backslash and one character, whatever it is
            # (so `\"` opens no string and `\;` no comment), or a named char.
            j = i + 2
            if i + 1 < n and s[i + 1].isalnum():
                while j < n and s[j].isalnum():
                    j += 1
            j = min(j, n)
            spans.append(('code', i, j))
            i = j
        elif c in '()[]{}':
            spans.append(('code', i, i + 1))
            i += 1
        else:
            j = i + 1
            while j < n and s[j] not in ' \t\r\n,\f()[]{}";':
                j += 1
            spans.append(('code', i, j))
            i = j
    return spans


JS_REGEX_AFTER = set('(,=:[!&|?{};+-*%<>~^') | {
    'return', 'typeof', 'instanceof', 'in', 'of', 'new', 'delete', 'void',
    'throw', 'case', 'do', 'else', 'yield', 'await'}


def lex_js(s):
    spans = []
    _lex_js(s, 0, spans, False)
    return spans


def _lex_js(s, i, spans, in_template_expr):
    """Lex JS from i; inside `${...}`, stop after the brace that closes it."""
    n, depth, prev = len(s), 0, None
    while i < n:
        c = s[i]
        if c in ' \t\r\n\f\v':
            i += 1
        elif s.startswith('//', i):
            j = s.find('\n', i)
            j = n if j < 0 else j
            spans.append(('comment', i, j))
            i = j
        elif s.startswith('/*', i):
            j = s.find('*/', i + 2)
            j = n if j < 0 else j + 2
            spans.append(('comment', i, j))
            i = j
        elif c in '\'"':
            j = i + 1
            while j < n and s[j] != c and s[j] != '\n':
                j += 2 if s[j] == '\\' else 1
            j = min(j + 1, n)
            spans.append(('str', i, j))
            i, prev = j, 'str'
        elif c == '`':
            i, prev = _lex_template(s, i, spans), 'str'
        elif c == '/' and (prev is None or prev in JS_REGEX_AFTER):
            j, in_class = i + 1, False
            while j < n and s[j] != '\n':
                if s[j] == '\\':
                    j += 2
                    continue
                if s[j] == '[':
                    in_class = True
                elif s[j] == ']':
                    in_class = False
                elif s[j] == '/' and not in_class:
                    break
                j += 1
            j += 1
            while j < n and s[j].isalnum():
                j += 1
            j = min(j, n)
            spans.append(('regex', i, j))
            i, prev = j, 'regex'
        elif c.isalnum() or c in '_$':
            j = i
            while j < n and (s[j].isalnum() or s[j] in '_$'):
                j += 1
            spans.append(('code', i, j))
            i, prev = j, s[i:j]
        else:
            if in_template_expr and c == '}':
                if depth == 0:
                    return i + 1
                depth -= 1
            elif in_template_expr and c == '{':
                depth += 1
            spans.append(('code', i, i + 1))
            i, prev = i + 1, c
    return i


def _lex_template(s, i, spans):
    n, start, j = len(s), i, i + 1
    while j < n:
        if s[j] == '\\':
            j += 2
        elif s[j] == '`':
            spans.append(('str', start, j + 1))
            return j + 1
        elif s.startswith('${', j):
            spans.append(('str', start, j + 2))
            j = _lex_js(s, j + 2, spans, True)
            start = j - 1  # the closing brace opens the next text chunk
        else:
            j += 1
    spans.append(('str', start, n))
    return n


def lex_python(s):
    starts = [0]
    for line in s.split('\n'):
        starts.append(starts[-1] + len(line) + 1)
    spans = []
    for tok in tokenize.generate_tokens(io.StringIO(s).readline):
        if tok.type in (tokenize.NL, tokenize.ENDMARKER):
            continue
        a = starts[tok.start[0] - 1] + tok.start[1]
        b = starts[tok.end[0] - 1] + tok.end[1]
        name = tokenize.tok_name[tok.type]
        if tok.type == tokenize.COMMENT:
            kind = 'comment'
        elif tok.type == tokenize.STRING or name.endswith('STRING_MIDDLE'):
            kind = 'str'
        else:
            kind = 'code'
        spans.append((kind, a, b))
    return spans


def _hash_comment_start(line, j, ext):
    """Index of the first comment opener at or after j outside quotes, else -1."""
    quote, n = None, len(line)
    escape = '`' if ext == '.ps1' else '\\'
    while j < n:
        c = line[j]
        if quote:
            if c == escape and quote == '"':
                j += 2
                continue
            if c == quote:
                quote = None
        elif c in '\'"':
            # A YAML apostrophe inside a word (don't) opens no quote.
            if not (ext in ('.yml', '.yaml') and j > 0 and line[j - 1].isalnum()):
                quote = c
        elif ext == '.ps1' and line.startswith('<#', j):
            return j
        elif c == '#' and (j == 0 or line[j - 1] in ' \t'):
            return j
        j += 1
    return -1


def lex_hash(s, ext):
    spans, pos, in_block = [], 0, False
    for line in s.split('\n'):
        j = 0
        while j < len(line):
            if in_block:
                k = line.find('#>', j)
                end = len(line) if k < 0 else k + 2
                spans.append(('comment', pos + j, pos + end))
                in_block, j = k < 0, end
                continue
            k = _hash_comment_start(line, j, ext)
            if k < 0:
                break
            if ext == '.ps1' and line.startswith('<#', k):
                spans.append(('comment', pos + k, pos + k + 2))
                in_block, j = True, k + 2
                continue
            spans.append(('comment', pos + k, pos + len(line)))
            break
        pos += len(line) + 1
    return spans


def lex(text, ext):
    if ext in CLOJURE:
        return lex_clojure(text)
    if ext in JS:
        return lex_js(text)
    if ext == '.py':
        return lex_python(text)
    if ext in HASH:
        return lex_hash(text, ext)
    return None


# ---------------------------------------------------------------------------
# Comparison tokens for verify.

def line_starts(text):
    starts = [0]
    for k, ch in enumerate(text):
        if ch == '\n':
            starts.append(k + 1)
    return starts


def code_tokens(text, ext):
    """[(token, line)] for everything verify compares."""
    starts = line_starts(text)
    line_of = lambda off: bisect.bisect_right(starts, off)
    if ext in HASH:
        # Drop comment characters but keep their newlines, then compare lines.
        keep, prev = [], 0
        for _, a, b in lex_hash(text, ext):
            keep.append(text[prev:a])
            keep.append('\n' * text.count('\n', a, b))
            prev = b
        keep.append(text[prev:])
        return [(line.rstrip(), no)
                for no, line in enumerate(''.join(keep).split('\n'), 1) if line.strip()]
    if ext == '.md':
        return [(line.rstrip(), no) for no, line in markdown_headings(text)]
    spans = lex(text, ext)
    if spans is None:
        return [(line, no) for no, line in enumerate(text.split('\n'), 1)]
    return [('<string>' if kind == 'str' else text[a:b], line_of(a))
            for kind, a, b in spans if kind != 'comment']


def markdown_headings(text):
    fence = None
    for no, line in enumerate(text.split('\n'), 1):
        stripped = line.lstrip(' ')
        marker = stripped[:3]
        if marker in ('```', '~~~'):
            fence = None if fence == marker else (fence or marker)
        elif fence is None and len(line) - len(stripped) <= 3 and re.match(r'#{1,6}(\s|$)', stripped):
            yield no, line


def eol_counts(raw):
    cr, crlf, lf = raw.count(b'\r'), raw.count(b'\r\n'), raw.count(b'\n')
    return cr, crlf, lf - crlf


# ---------------------------------------------------------------------------
# git plumbing.

def git(args, cwd, check=True):
    r = subprocess.run(['git'] + args, cwd=cwd, capture_output=True)
    if check and r.returncode != 0:
        raise UsageError('git %s failed: %s' % (' '.join(args), r.stderr.decode('utf-8', 'replace').strip()))
    return r


class UsageError(Exception):
    pass


def toplevel(cwd):
    return git(['rev-parse', '--show-toplevel'], cwd).stdout.decode('utf-8').strip()


def z_list(raw):
    return [p for p in raw.decode('utf-8').split('\0') if p]


def decode(raw):
    return raw.decode('utf-8', 'replace').replace('\r\n', '\n')


# ---------------------------------------------------------------------------
# census

def in_scope(path):
    parts = path.split('/')
    if 'findings' in parts[:-1] or parts[0] in ('.beads', 'ai'):
        return False
    ext = ext_of(path)
    return ext in CODE_EXTS or (ext == '.md' and parts[0] == 'spec')


def is_generated(text):
    return any('GENERATED' in line for line in text.split('\n', 3)[:3])


def census(paths, hits=False, cwd='.', emit=print):
    root = toplevel(cwd)
    totals, skipped_total, status = [0, 0, 0, 0], 0, 0
    where = {}
    rows = []
    for p in paths:
        files = z_list(git(['ls-files', '-z', '--full-name', '--', p], cwd).stdout)
        if not files:
            emit('census: %s matches no tracked file' % p)
            status = 1
            continue
        row, skipped = [0, 0, 0, 0], 0
        for rel in files:
            if not in_scope(rel):
                skipped += 1
                continue
            with open(os.path.join(root, rel), 'rb') as fh:
                text = decode(fh.read())
            if is_generated(text):
                skipped += 1
                continue
            lines = text.split('\n')
            if lines[-1] == '':
                lines.pop()
            row[0] += 1
            row[1] += len(lines)
            spans, starts = None, None
            for no, line in enumerate(lines, 1):
                found = [('bead-id', m) for m in BEAD_ID.finditer(line)] + \
                        [('history', m) for m in HISTORY.finditer(line)]
                if any(k == 'bead-id' for k, _ in found):
                    row[2] += 1
                if any(k == 'history' for k, _ in found):
                    row[3] += 1
                if hits and found:
                    if starts is None:
                        ext = ext_of(rel)
                        spans = [] if ext == '.md' else (lex(text, ext) or [])
                        starts = line_starts(text)
                    tags = sorted({(k, kind_at(spans, starts[no - 1] + m.start(), ext_of(rel)))
                                   for k, m in found})
                    for tag in tags:
                        where[tag] = where.get(tag, 0) + 1
                    emit('%s:%d: [%s] %s' % (rel, no, ' '.join('%s:%s' % t for t in tags), line.strip()))
        rows.append((row, p))
        skipped_total += skipped
        totals = [a + b for a, b in zip(totals, row)]
    if hits and where:
        emit('')
        for (k, kind), count in sorted(where.items()):
            emit('%s lines by where: %-8s %d' % (k, kind, count))
        emit('')
    emit('%7s %8s %8s %8s  %s' % ('files', 'lines', 'bead-id', 'history', 'path'))
    for row, p in rows + [(totals, 'total')]:
        emit('%7d %8d %8d %8d  %s' % (row[0], row[1], row[2], row[3], p))
    if skipped_total:
        emit('(%d tracked file(s) skipped as out of scope or generated)' % skipped_total)
    return status


def kind_at(spans, off, ext):
    if ext == '.md':
        return 'prose'
    k = bisect.bisect_right([a for _, a, _ in spans], off) - 1
    if k >= 0 and spans[k][1] <= off < spans[k][2]:
        return spans[k][0]
    return 'code'


# ---------------------------------------------------------------------------
# verify

def verify(base, paths, cwd='.', emit=print):
    if git(['rev-parse', '--verify', '--quiet', base + '^{commit}'], cwd, check=False).returncode != 0:
        emit('verify: unknown revision %r' % base)
        return 2
    root = toplevel(cwd)
    changed = z_list(git(['diff', '--name-only', '--no-renames', '-z', base, '--'] + paths, cwd).stdout)
    untracked = z_list(git(['ls-files', '--others', '--exclude-standard', '--full-name', '-z', '--']
                           + paths, cwd).stdout)
    files = sorted(set(changed) | set(untracked))
    if not files:
        emit('verify: no file under %s differs from %s, so nothing was checked' % (' '.join(paths), base))
        return 3
    failed = 0
    for rel in files:
        shown = git(['show', '%s:%s' % (base, rel)], root, check=False)
        before = shown.stdout if shown.returncode == 0 else None
        path = os.path.join(root, rel)
        after = open(path, 'rb').read() if os.path.isfile(path) else None
        problem, eol = check_file(rel, before, after)
        if problem:
            failed += 1
            emit('  FAIL  %s%s: %s' % (rel, eol, problem))
        else:
            emit('  ok    %s%s' % (rel, eol))
    emit('verify: %d of %d changed file(s) failed against %s' % (failed, len(files), base))
    return 1 if failed else 0


def check_file(rel, before, after):
    """(problem or None, eol note) for one changed file."""
    if before is None:
        return 'added; the campaign edits prose in existing files only', ''
    if after is None:
        return 'deleted; the campaign edits prose in existing files only', ''
    cr, crlf, bare_lf = eol_counts(after)
    eol = '  (eol cr=%d crlf=%d bare_lf=%d)' % (cr, crlf, bare_lf)
    if cr != crlf or (crlf and bare_lf):
        return 'MIXED line endings; restore them before anything else', eol
    ext = ext_of(rel)
    if ext != '.md' and lex('', ext) is None:
        return (None if before == after else 'no lexer for %s, so any change counts as code' % (ext or 'this type')), eol
    try:
        a, b = code_tokens(decode(before), ext), code_tokens(decode(after), ext)
    except (tokenize.TokenError, SyntaxError) as e:
        return 'does not tokenize: %s' % e, eol
    for k in range(max(len(a), len(b))):
        if k >= len(a) or k >= len(b) or a[k][0] != b[k][0]:
            what = 'heading' if ext == '.md' else 'token'
            return 'first differing %s: base %s, working tree %s' % (what, show(a, k), show(b, k)), eol
    return None, eol


def show(tokens, k):
    if k >= len(tokens):
        return '<end of file>'
    tok, line = tokens[k]
    return 'line %d `%s`' % (line, tok if len(tok) <= 60 else tok[:57] + '...')


# ---------------------------------------------------------------------------
# self-test

FIXTURES = {
    'src/demo.cljc': (
        '(ns demo.core\r\n'
        '  "Namespace docstring (rf2-abc12).")\r\n'
        '\r\n'
        ';; A comment that was previously longer.\r\n'
        '(defn f\r\n'
        '  "Docstring."\r\n'
        '  [c]\r\n'
        '  (cond\r\n'
        '    (= c \\") :quote\r\n'
        '    (= c \\;) :semi\r\n'
        '    (re-find #"a\\"b;c" (str c)) :re\r\n'
        '    :else (+ 1 2)))\r\n'),
    'src/demo.cjs': (
        '// A header comment (rf2-abc12).\n'
        'const re = /[/*]+ not a comment/g;\n'
        'function f(x) {\n'
        '  /* block comment */\n'
        "  const s = 'it\\'s a string';\n"
        '  return `total: ${x + 1} units` + re.source + s;\n'
        '}\n'
        'module.exports = { f };\n'),
    'src/demo.py': (
        '"""Module docstring (rf2-abc12)."""\n'
        'import os  # trailing comment\n'
        '\n'
        '\n'
        'def f(x):\n'
        '    """Function docstring."""\n'
        '    # previously a comment\n'
        '    return os.sep + f"{x} items" + str(x + 1)\n'),
    'src/demo.sh': (
        '#!/bin/sh\n'
        '# A comment that was originally longer.\n'
        'echo "count: ${#1}"  # trailing comment\n'
        'exit 0\n'),
    'src/demo.yml': (
        '# Workflow header (rf2-abc12).\n'
        'name: demo  # trailing\n'
        "note: don't # a comment after an apostrophe\n"
        'on: push\n'
        'run: echo "a # not a comment"\n'),
    'src/demo.ps1': (
        '<# Block comment\n'
        '   spanning lines (rf2-abc12). #>\n'
        '$x = 1  # trailing\n'
        'Write-Output "value # $x"\n'),
    'spec/demo.md': (
        '# Title\n'
        '\n'
        'Prose that was previously different.\n'
        '\n'
        '## Section (rf2-abc12)\n'
        '\n'
        '```clojure\n'
        '# not a heading\n'
        '```\n'),
    'src/data.edn': (
        '{:attr "data-rf2-source-coord"  ; not a bead id\n'
        ' :note "see rf2-abc12.3"}       ; a bead id, and one history word: superseded\n'),
    'src/notes.txt': 'plain text (rf2-abc12)\n',
    'src/gen.edn': ';; GENERATED by a generator -- do NOT hand-edit. rf2-abc12\n{:a 1}\n',
    'README.md': 'Out of scope (rf2-abc12), previously.\n',
}

# (file, old, new, expected verify exit).  Each `old` must occur exactly once.
PLANTS = [
    ('src/demo.cljc', 'previously longer', 'shorter', 0),
    ('src/demo.cljc', '"Docstring."', '"Docstring, rewritten."', 0),
    ('src/demo.cljc', '(rf2-abc12).")', '(present tense).")', 0),
    ('src/demo.cljc', '(+ 1 2)', '(+ 1 3)', 1),
    ('src/demo.cljc', ':quote', ':dquote', 1),        # red only if `\"` opens no string
    ('src/demo.cljc', ':semi', ':semicolon', 1),      # red only if `\;` opens no comment
    ('src/demo.cljc', 'b;c"', 'b;d"', 1),             # regex compared verbatim
    ('src/demo.cljc', '[c]', '[c d]', 1),
    ('src/demo.cljc', 'longer.\r\n', 'longer.\n', 1),  # mixed line endings
    ('src/demo.cjs', 'A header comment', 'A comment', 0),
    ('src/demo.cjs', 'block comment', 'a block comment', 0),
    ('src/demo.cjs', "a string'", "a text'", 0),
    ('src/demo.cjs', 'total: ', 'sum: ', 0),
    ('src/demo.cjs', '${x + 1}', '${x + 2}', 1),
    ('src/demo.cjs', 'not a comment/g', 'now a comment/g', 1),  # red only if `/*` in a regex opens no comment
    ('src/demo.cjs', '+ s;', '+ x;', 1),
    ('src/demo.py', 'Module docstring (rf2-abc12).', 'Module docstring.', 0),
    ('src/demo.py', 'Function docstring.', 'What f returns.', 0),
    ('src/demo.py', '# previously a comment', '# a comment', 0),
    ('src/demo.py', ' items"', ' things"', 0),
    ('src/demo.py', 'x + 1', 'x + 2', 1),
    ('src/demo.py', 'import os', 'import sys', 1),
    ('src/demo.sh', 'originally longer', 'shorter', 0),
    ('src/demo.sh', 'exit 0', 'exit 1', 1),
    ('src/demo.sh', '${#1}', '${#2}', 1),
    ('src/demo.yml', 'Workflow header (rf2-abc12).', 'Workflow header.', 0),
    ('src/demo.yml', 'after an apostrophe', 'after a word', 0),
    ('src/demo.yml', 'on: push', 'on: pull_request', 1),
    ('src/demo.yml', '# not a comment"', '# not a remark"', 1),
    ('src/demo.ps1', 'spanning lines (rf2-abc12).', 'spanning lines.', 0),
    ('src/demo.ps1', '$x = 1', '$x = 2', 1),
    ('src/demo.ps1', 'value # $x', 'value = $x', 1),
    ('spec/demo.md', 'was previously different', 'is different', 0),
    ('spec/demo.md', '# not a heading', '# still not a heading', 0),
    ('spec/demo.md', '## Section (rf2-abc12)', '## Section', 1),
    ('src/notes.txt', 'plain text', 'plain prose', 1),
]

# census over the fixture tree: files, lines, bead-id lines, history lines.
# In scope: the six demo code files, data.edn and spec/demo.md.  Skipped:
# notes.txt (no code extension), gen.edn (GENERATED) and README.md (markdown
# outside spec/).  data-rf2-source-coord is not a bead id.
CENSUS_EXPECTED = (8, 52, 7, 5)


def self_test():
    failures = []

    def expect(label, got, want, log):
        if got != want:
            failures.append('%s: expected %r, got %r\n      %s' % (label, want, got, '\n      '.join(log)))

    tmp = tempfile.mkdtemp(prefix='present-tense-selftest-')
    try:
        git(['init', '-q'], tmp)
        git(['config', 'core.autocrlf', 'false'], tmp)
        git(['config', 'user.name', 'self-test'], tmp)
        git(['config', 'user.email', 'self-test@example.invalid'], tmp)
        for rel, body in FIXTURES.items():
            os.makedirs(os.path.dirname(os.path.join(tmp, rel)) or tmp, exist_ok=True)
            with open(os.path.join(tmp, rel), 'wb') as fh:
                fh.write(body.encode('utf-8'))
        git(['add', '-A'], tmp)
        git(['commit', '-q', '-m', 'fixtures'], tmp)

        log = []
        expect('verify with nothing changed', verify('HEAD', ['.'], tmp, log.append), 3, log)
        log = []
        expect('verify against an unknown revision', verify('no-such-rev', ['.'], tmp, log.append), 2, log)

        for rel, old, new, want in PLANTS:
            path = os.path.join(tmp, rel)
            original = open(path, 'rb').read()
            if original.count(old.encode('utf-8')) != 1:
                failures.append('plant anchor %r occurs %d times in %s' % (old, original.count(old.encode('utf-8')), rel))
                continue
            with open(path, 'wb') as fh:
                fh.write(original.replace(old.encode('utf-8'), new.encode('utf-8')))
            log = []
            got = verify('HEAD', ['.'], tmp, log.append)
            expect('%s: %r -> %r' % (rel, old, new), got, want, log)
            if want == 1 and not any(rel in line and 'FAIL' in line for line in log):
                failures.append('%s: the failure does not name the file\n      %s' % (rel, '\n      '.join(log)))
            with open(path, 'wb') as fh:
                fh.write(original)
            restored = git(['hash-object', rel], tmp).stdout.strip()
            committed = git(['rev-parse', 'HEAD:' + rel], tmp).stdout.strip()
            if restored != committed:
                failures.append('%s: restore does not match the committed blob' % rel)

        added = os.path.join(tmp, 'src', 'added.cljc')
        with open(added, 'wb') as fh:
            fh.write(b'(ns added)\n')
        log = []
        expect('an added file', verify('HEAD', ['.'], tmp, log.append), 1, log)
        os.remove(added)
        log = []
        expect('every plant restored', verify('HEAD', ['.'], tmp, log.append), 3, log)

        log = []
        census(['.'], cwd=tmp, emit=log.append)
        total = [line for line in log if line.endswith('  total')]
        got = tuple(int(x) for x in total[0].split()[:4]) if total else None
        expect('census totals', got, CENSUS_EXPECTED, log)
        log = []
        expect('census over a path matching nothing', census(['no-such-dir'], cwd=tmp, emit=log.append), 1, log)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    for f in failures:
        print('  FAIL  ' + f)
    print('self-test: %d plant(s), %d failure(s)' % (len(PLANTS), len(failures)))
    return 1 if failures else 0


# ---------------------------------------------------------------------------

def main(argv):
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description='Present-tense campaign tool.')
    sub = parser.add_subparsers(dest='command', required=True)
    c = sub.add_parser('census', help='count bead-id and history-word lines')
    c.add_argument('--hits', action='store_true', help='list every matching line, tagged by where it sits')
    c.add_argument('paths', nargs='+')
    v = sub.add_parser('verify', help='prove only comments and strings changed since <base>')
    v.add_argument('base')
    v.add_argument('paths', nargs='+')
    sub.add_parser('self-test', help='show verify red on planted code changes and green on prose ones')
    args = parser.parse_args(argv)
    try:
        if args.command == 'census':
            return census(args.paths, hits=args.hits)
        if args.command == 'verify':
            return verify(args.base, args.paths)
        return self_test()
    except UsageError as e:
        print('present_tense: %s' % e, file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
