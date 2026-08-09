#!/usr/bin/env python3
"""THE FREEZE GATE for implementation/hicasso/ (rf2-hic-001).

Three properties, all of which stop being true silently:

  FROZEN  Every donor file the package was copied from still hashes to the
          digest recorded in `frozen-sources.edn`, and still has the line
          count pinned beside it. A bench-tree edit that the package did
          not receive is a divergence, and a divergence nobody noticed is
          the failure mode this gate exists for.

  MOVED   Every package file IS its donor, moved. The gate RECONSTRUCTS
          the package file — donor text with `:renames` applied at symbol
          boundaries — and requires the file on disk to equal it. This is
          the property that makes rf2-hic-001's one sentence, *the package
          is the prototype moved*, a check rather than a claim.

  SEALED  No file under `implementation/hicasso/` IMPORTS a benchmark-tree
          namespace. Prose may name one — a docstring citing the witness
          that pins a behaviour is worth keeping — but a `:require`,
          `:require-macros` or runtime `require` would make the shipped
          package depend on a test tree.

MOVED is the one that was missing, and its absence was a FALSE GREEN worth
naming: until rf2-hic-001's reopen the gate hashed each DONOR and then
merely checked that the package path EXISTED. A package body could differ
from its prototype arbitrarily — audit #7735's reproduction turned
`(def answer 1)` into `(def answer :silently-different)` — and the gate
still reported zero failures, while `TESTING.md` and `test.yml` presented
it as proof that the two trees agreed. A hollow gate advertised as proof is
worse than no gate: it converts an unchecked claim into a checked-looking
one. MOVED is what makes the advertisement true.

## Exactly what the move is allowed to have changed

Two things, and the gate licenses no third:

  1. THE NAMESPACE RENAME in the manifest's `:renames`, applied at SYMBOL
     BOUNDARIES. The boundary is not a detail. `front/slot` renames to
     `impl/slot`, but the docstring reference `…front.slot-cljs-test` names
     a bench SUITE the package does not contain, and the manifest's header
     says such references were deliberately left verbatim. A substring
     rename would rewrite it and the gate would then demand a package that
     points at a file nobody moved.

  2. INSERTED COMMENT LINES. The copy added a four-line
     `rf2:builder-bypass-ok` marker above the shared `fail!` helper in six
     files (the manifest header says why). Rather than pin that block as
     data — which would need a manifest field per marker, maintained by
     hand, and re-maintained by every worker who later splits a module —
     the rule is structural and general: the package may INSERT lines the
     donor lacks, and every inserted line must be a COMMENT.

     The boundary that buys is worth stating plainly, because a gate should
     never be vaguer than what it checks: added COMMENTARY passes; a
     changed, deleted, reordered or inserted line of CODE — including
     every docstring, since a docstring is a string and a string is code —
     fails. Divergence of the BODY, which is what the audit asked to red,
     cannot get through. An added comment can, and that is the whole of it.

## The macro-only row

`arm1/lang.clj`'s three macros went into the public door `re-frame.hicasso`,
wrapped in `#?(:clj …)` and re-indented, so no reconstruction can be
byte-exact and the row carries `:whole-file? false`. That row is checked by
SHAPE instead: every top-level definition the DONOR makes must appear in the
package with an identical READER FORM once renames are applied — same
tokens, same strings, same docstrings, whitespace and comments ignored. The
roster comes from the donor rather than from a hand-written list in the
manifest, so a fourth macro is covered the day it lands and the row cannot
be silently under-pinned.

    python implementation/hicasso/scripts/check_freeze.py
    python implementation/hicasso/scripts/check_freeze.py --self-test
    python implementation/hicasso/scripts/check_freeze.py --update

`--update` re-pins every drifted donor digest and line count from the donor
tree as it stands. Note what it can no longer do: re-pinning records that
the BENCH tree moved, and MOVED then reds until somebody ports the change
into the package. `--update` alone cannot manufacture a green, which is the
point — it is the correct response to a deliberate, ported bench-tree change
and the WRONG response to a surprise. Read `frozen-sources.edn`'s header
before reaching for it.

SUNSET. This gate is scaffolding for the extraction, not a permanent law.
rf2-hic-009 carves the runtime into owned modules, and from the first such
split the package is deliberately ahead of the bench tree — at that point
the divergent rows are retired rather than re-pinned, and when the last row
goes, so does this file.
"""
import argparse
import difflib
import hashlib
import os
import re
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PACKAGE_ROOT = os.path.dirname(HERE)
REPO_ROOT = os.path.dirname(os.path.dirname(PACKAGE_ROOT))
MANIFEST = os.path.join(PACKAGE_ROOT, "frozen-sources.edn")
SOURCE_SUFFIXES = (".clj", ".cljs", ".cljc")

BACKSLASH = chr(92)

# The characters that may CONTINUE a Clojure symbol. A rename matches only
# where the next character is not one of these, which is what keeps
# `…front.slot` from rewriting the middle of `…front.slot-cljs-test`.
SYMBOL_TAIL = r"[\w.$*+!?<>=-]"


# ---------------------------------------------------------------------------
# A small EDN reader — the manifest's subset, deliberately not more
# ---------------------------------------------------------------------------
#
# The manifest is maps, vectors, strings, keywords, symbols, integers,
# booleans and `;` comments. Nothing tagged, no sets, no metadata. Reading
# that subset honestly is ~50 lines; shelling out to Clojure to read one data
# file would cost a JVM start on every run of a gate meant to be cheap.

_TOKEN = re.compile(
    r'"(?:[^"\\]|\\.)*"'      # string
    r"|[\[\]{}]"              # delimiters
    r"|;[^\n]*"               # comment
    r"|[^\s\[\]{}\",]+"       # atom
)


def _unescape(literal):
    out, i = [], 1
    while i < len(literal) - 1:
        c = literal[i]
        if c == BACKSLASH:
            i += 1
            out.append({"n": "\n", "t": "\t", "r": "\r"}.get(literal[i], literal[i]))
        else:
            out.append(c)
        i += 1
    return "".join(out)


def read_edn(text):
    tokens = [t for t in _TOKEN.findall(text) if not t.startswith(";")]
    value, rest = _read_form(tokens, 0)
    if rest != len(tokens):
        raise ValueError("trailing data in manifest at token %d" % rest)
    return value


def _read_form(tokens, i):
    tok = tokens[i]
    if tok == "{":
        out, i = {}, i + 1
        while tokens[i] != "}":
            key, i = _read_form(tokens, i)
            val, i = _read_form(tokens, i)
            out[key] = val
        return out, i + 1
    if tok == "[":
        out, i = [], i + 1
        while tokens[i] != "]":
            val, i = _read_form(tokens, i)
            out.append(val)
        return out, i + 1
    if tok.startswith('"'):
        return _unescape(tok), i + 1
    if tok == "true":
        return True, i + 1
    if tok == "false":
        return False, i + 1
    if tok == "nil":
        return None, i + 1
    if re.fullmatch(r"-?\d+", tok):
        return int(tok), i + 1
    return tok, i + 1          # keyword or symbol, kept as its literal text


# ---------------------------------------------------------------------------
# Reading Clojure without a Clojure reader
# ---------------------------------------------------------------------------
#
# Everything below needs one thing: to know which characters are CODE and
# which are inside a string or a comment. One scanner answers that, and the
# three projections that follow are the only consumers. Two scanners in one
# file would be two places for the same off-by-one.

def spans(src):
    """Classify `src` into consecutive ("code" | "string" | "comment") spans.

    Character literals matter here even though they look like noise: `\\"` is
    a double-quote CHARACTER, not the start of a string, and a scanner that
    misreads one treats the rest of the file as quoted.
    """
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            j = i + 1
            while j < n:
                if src[j] == BACKSLASH:
                    j += 2
                    continue
                if src[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(("string", i, min(j, n)))
            i = min(j, n)
        elif c == ";":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(("comment", i, j))
            i = j
        elif c == BACKSLASH:
            j = min(i + 2, n)
            while j < n and re.match(r"[\w-]", src[j]):
                j += 1
            out.append(("code", i, j))
            i = j
        else:
            j = i
            while j < n and src[j] not in ('"', ";", BACKSLASH):
                j += 1
            out.append(("code", i, j))
            i = j
    return out


def _blank(text):
    return re.sub(r"[^\n]", " ", text)


def blank_strings(src):
    """String CONTENT replaced by spaces, offsets and comments preserved.

    This is what lets a docstring name `re-frame.bench.hicasso.arm1.
    hook-ledger-dom-cljs-test` — the witness that pins the hook budget —
    without the gate calling it an import.
    """
    out = []
    for kind, a, b in spans(src):
        chunk = src[a:b]
        if kind == "string":
            out.append('"' + _blank(chunk[1:-1]) + '"' if len(chunk) >= 2 else _blank(chunk))
        else:
            out.append(chunk)
    return "".join(out)


def code_only(src):
    """Strings and comments blanked, offsets preserved. For finding `(ns …)`."""
    return "".join(src[a:b] if kind == "code" else _blank(src[a:b])
                   for kind, a, b in spans(src))


def reader_form(src):
    """`src` as the reader would see it: comments dropped, whitespace
    collapsed, string literals kept to the character.

    Two forms with the same reader_form differ only in layout. That is what
    lets the macro row see through `#?(:clj …)`'s three-space re-indentation
    while still failing on a single changed token or docstring word.
    """
    out = []
    for kind, a, b in spans(src):
        if kind == "comment":
            out.append(" ")
        elif kind == "string":
            out.append(src[a:b])
        else:
            out.append(re.sub(r"\s+", " ", src[a:b]))
    return re.sub(r" +", " ", "".join(out)).strip()


_NS_FORM = re.compile(r"\(ns\s+(?:\^\S+\s+)?([^\s()\[\]{},;\"]+)")


def declared_namespace(src):
    """The symbol in the file's `(ns …)` form, or None."""
    match = _NS_FORM.search(code_only(src))
    return match.group(1) if match else None


def definitions(src):
    """Every top-level `(defsomething NAME …)` in `src`, in order.

    Depth is counted over code spans only, so a `(def x)` written inside a
    docstring or commented out is not a definition.
    """
    code = [(a, b) for kind, a, b in spans(src) if kind == "code"]
    flat = "".join(src[a:b] if (a, b) in code else _blank(src[a:b])
                   for kind, a, b in spans(src))
    found, depth, i, n = [], 0, 0, len(flat)
    while i < n:
        c = flat[i]
        if c == "(":
            if depth == 0:
                match = re.match(r"\((def[\w.*+!?<>=-]*)\s+([\w.*+!?<>=-]+)", flat[i:])
                if match:
                    found.append(match.group(2))
            depth += 1
        elif c == ")":
            depth -= 1
        i += 1
    return found


def definition_form(src, name):
    """The balanced text of the definition of `name`, or None.

    Not anchored at depth 0: the package wraps each macro in `#?(:clj …)`,
    so the form sits one level in.
    """
    head = re.compile(r"\((def[\w.*+!?<>=-]*)\s+" + re.escape(name) + r"(?!" + SYMBOL_TAIL + r")")
    flat = code_only(src)
    match = head.search(flat)
    if not match:
        return None
    start, depth = match.start(), 0
    for i in range(start, len(flat)):
        if flat[i] == "(":
            depth += 1
        elif flat[i] == ")":
            depth -= 1
            if depth == 0:
                return src[start:i + 1]
    return None


_REQUIRE_HEAD = re.compile(r"\((?::require-macros|:require|:use|require|use)\b")
_SYMBOL = re.compile(r"[A-Za-z][\w.$*+!?<>=-]*")


def imported_namespaces(src):
    """Every namespace symbol reachable from a require/use form in `src`."""
    text = blank_strings(src)
    found = set()
    for match in _REQUIRE_HEAD.finditer(text):
        depth, i = 0, match.start()
        while i < len(text):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        found.update(_SYMBOL.findall(text[match.start():i + 1]))
    return found


# ---------------------------------------------------------------------------
# The move, applied
# ---------------------------------------------------------------------------

def rename(text, renames):
    """`renames` applied at symbol boundaries, longest key first.

    Longest-first matters as much as the boundary: `…arm1.presence` and
    `…arm1.presence-react` would otherwise race, and alternation in `re`
    takes the first branch that matches rather than the longest.
    """
    if not renames:
        return text
    keys = sorted(renames, key=len, reverse=True)
    pattern = re.compile("(?:%s)(?!%s)" % ("|".join(re.escape(k) for k in keys), SYMBOL_TAIL))
    return pattern.sub(lambda m: renames[m.group(0)], text)


def read_text(path):
    """The file's text with line endings normalised, for the same reason the
    digest normalises them: a commit checks out CRLF on Windows and LF
    elsewhere, and neither is a change."""
    with open(path, "rb") as fh:
        return fh.read().decode("utf-8").replace("\r\n", "\n")


def digest(path):
    """SHA-256 over the file's LINE-ENDING-NORMALISED bytes.

    Mike develops on Windows and the maintainers on Mac and Linux, so one
    commit checks out CRLF in one tree and LF in another. A raw-byte digest
    would make this gate report a platform rather than a change.
    """
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read().replace(b"\r\n", b"\n")).hexdigest()


_COMMENT_LINE = re.compile(r"^\s*;")


def compare_moved(expected, actual):
    """Lines of `actual` against the reconstructed `expected`.

    Returns (failures, inserted_line_count). Insertions of COMMENT lines are
    the move's one licensed addition; a replacement, a deletion, or an
    insertion carrying code is divergence and is reported with the lines
    themselves, because a gate that says only "differs" makes the reader do
    the diff it just did.
    """
    problems, inserted = [], 0
    matcher = difflib.SequenceMatcher(None, expected, actual, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            continue
        if tag == "insert" and all(_COMMENT_LINE.match(l) for l in actual[j1:j2]):
            inserted += j2 - j1
            continue
        said = {"replace": "%d donor line(s) rewritten as %d" % (i2 - i1, j2 - j1),
                "delete": "%d donor line(s) missing" % (i2 - i1),
                "insert": "%d line(s) the donor does not have" % (j2 - j1)}[tag]
        detail = ["at package line %d, %s:" % (j1 + 1, said)]
        for line in expected[i1:i2][:4]:
            detail.append("    donor    %s" % line.rstrip())
        for line in actual[j1:j2][:4]:
            detail.append("    package  %s" % line.rstrip())
        problems.append("\n          ".join(detail))
    return problems, inserted


# ---------------------------------------------------------------------------
# The run
# ---------------------------------------------------------------------------

def check(manifest_path, package_root, repo_root, update=False):
    with open(manifest_path, encoding="utf-8") as fh:
        manifest_text = fh.read()
    manifest = read_edn(manifest_text)

    donor_root = os.path.join(repo_root, manifest[":donor-root"])
    renames = manifest.get(":renames", {})
    forbidden = tuple(manifest[":forbidden-import-prefixes"])
    rows = manifest[":files"]
    failures, repins = [], []

    for row in rows:
        bench_rel, package_rel = row[":bench"], row[":package"]
        bench = os.path.join(donor_root, bench_rel)
        package = os.path.join(package_root, package_rel)

        # A row that pins less than the contract cannot be checked against it,
        # and an unpinnable row is how a gate goes quietly hollow.
        missing = [k for k in (":ns", ":lines", ":sha256") if k not in row]
        if missing:
            failures.append("FROZEN  %s pins no %s; every row pins all three"
                            % (bench_rel, ", ".join(missing)))
            continue

        if not os.path.exists(bench):
            failures.append("FROZEN  donor file is gone: %s" % bench_rel)
            continue

        donor_text = read_text(bench)
        donor_lines = donor_text.splitlines()

        actual = digest(bench)
        if actual != row[":sha256"]:
            if update:
                repins.append((bench_rel, row[":sha256"], actual, len(donor_lines)))
            else:
                failures.append(
                    "FROZEN  %s changed in the bench tree\n"
                    "          pinned %s\n"
                    "          actual %s" % (bench_rel, row[":sha256"], actual))
        if len(donor_lines) != row[":lines"] and not update:
            failures.append(
                "FROZEN  %s is %d line(s); the manifest pins :lines %d"
                % (bench_rel, len(donor_lines), row[":lines"]))

        if not os.path.exists(package):
            failures.append("FROZEN  package file is gone: %s" % package_rel)
            continue

        package_text = read_text(package)

        # `:ns` and `:renames`, checked against each other and against the
        # files. Substituting the text is not enough — the package has to
        # DECLARE the namespace the manifest says it declares, and that name
        # has to be the rename table's image of the donor's own.
        donor_ns = declared_namespace(donor_text)
        package_ns = declared_namespace(package_text)
        if package_ns != row[":ns"]:
            failures.append("MOVED   %s declares %s; the row pins :ns %s"
                            % (package_rel, package_ns, row[":ns"]))
        if donor_ns is None:
            failures.append("MOVED   %s declares no namespace" % bench_rel)
        elif donor_ns not in renames:
            failures.append("MOVED   :renames has no entry for %s, declared by %s"
                            % (donor_ns, bench_rel))
        elif renames[donor_ns] != row[":ns"]:
            failures.append("MOVED   :renames sends %s to %s; the row pins :ns %s"
                            % (donor_ns, renames[donor_ns], row[":ns"]))

        if row.get(":whole-file?", True):
            expected = rename(donor_text, renames).splitlines()
            problems, _ = compare_moved(expected, package_text.splitlines())
            for problem in problems:
                failures.append("MOVED   %s is not %s moved\n          %s"
                                % (package_rel, bench_rel, problem))
        else:
            # Not a rename-only image, so the row is held to its SHAPES: every
            # definition the donor makes, present in the package with the same
            # reader form. The roster is the donor's, so it cannot be short.
            for name in definitions(donor_text):
                donor_form = definition_form(donor_text, name)
                package_form = definition_form(package_text, name)
                if package_form is None:
                    failures.append("MOVED   %s does not define %s, which %s does"
                                    % (package_rel, name, bench_rel))
                    continue
                if reader_form(rename(donor_form, renames)) != reader_form(package_form):
                    failures.append(
                        "MOVED   %s's %s is not %s's, moved\n"
                        "          the two forms differ once layout and comments are "
                        "set aside" % (package_rel, name, bench_rel))

    for path in source_files(package_root):
        offenders = sorted(
            ns for ns in imported_namespaces(read_text(path))
            if ns.startswith(forbidden))
        for ns in offenders:
            failures.append(
                "SEALED  %s imports %s"
                % (os.path.relpath(path, repo_root).replace(os.sep, "/"), ns))

    if update and repins:
        for bench_rel, old, new, lines in repins:
            manifest_text = repin_row(manifest_text, bench_rel, old, new, lines)
        with open(manifest_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(manifest_text)
        print("re-pinned %d row(s) in %s" % (len(repins), manifest_path))

    return failures, len(rows)


def source_files(root):
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in (".shadow-cljs", "out", "node_modules")]
        for name in sorted(names):
            if name.endswith(SOURCE_SUFFIXES):
                yield os.path.join(base, name)


def repin_row(text, bench_rel, old_sha, new_sha, new_lines):
    """Rewrite one row's `:sha256` and `:lines` in the manifest SOURCE.

    Both are donor-derived, so re-pinning one without the other leaves a
    manifest that reds on the next run for a reason nobody caused. The edit
    is scoped to the row's own braces rather than done globally, because
    `:lines 214` is not a unique string in this file.
    """
    anchor = text.index('"%s"' % bench_rel)
    start = text.rindex("{", 0, anchor)
    depth, end = 0, None
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break
    row = text[start:end]
    row = row.replace(old_sha, new_sha)
    row = re.sub(r"(:lines\s+)\d+", lambda m: m.group(1) + str(new_lines), row, count=1)
    return text[:start] + row + text[end:]


# ---------------------------------------------------------------------------
# --self-test: prove the gate classifies, rather than merely runs
# ---------------------------------------------------------------------------
#
# The mutation cases below are the reason this section exists. A gate whose
# only evidence is a green run has not shown it can go red, and this gate
# spent two merged PRs green on a package it was not reading.

MANIFEST_TEMPLATE = """{:donor-root "donor"
 :renames {donor.a %(package_ns)s
           donor.b pkg.b}
 :forbidden-import-prefixes ["re-frame.bench."]
 :files [{:bench "a.cljs" :package "src/a.cljs" :ns %(row_ns)s
          :lines %(lines)d :sha256 "%(sha)s"}]}
"""

SHAPE_MANIFEST = """{:donor-root "donor"
 :renames {donor.b pkg.b}
 :forbidden-import-prefixes ["re-frame.bench."]
 :files [{:bench "b.clj" :package "src/b.cljc" :ns pkg.b :whole-file? false
          :lines %(lines)d :sha256 "%(sha)s"}]}
"""


def _write(path, text):
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def self_test():
    """Every assertion here is a case the live run must be able to fail on."""
    assert read_edn('{:a "x" :b [1 2] :c true}') == {":a": "x", ":b": [1, 2], ":c": True}
    assert read_edn(";; lead comment\n{:a 1}") == {":a": 1}
    assert read_edn('{:s re-frame.hicasso.impl.codec}')[":s"] == "re-frame.hicasso.impl.codec"

    # A docstring naming a bench namespace is prose, not an import.
    prose = '(ns x\n  "see re-frame.bench.hicasso.arm1.hook-ledger-dom-cljs-test"\n  (:require [re-frame.core :as rf]))'
    assert "re-frame.bench.hicasso.arm1.hook-ledger-dom-cljs-test" not in imported_namespaces(prose)
    assert "re-frame.core" in imported_namespaces(prose)

    # A require of one is an import, in either spelling.
    for form in ('(ns x (:require [re-frame.bench.hicasso.arm1.runtime :as rt]))',
                 '(ns x (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))',
                 "(require '[re-frame.bench.hicasso.front.codec :as codec])"):
        assert any(ns.startswith("re-frame.bench.") for ns in imported_namespaces(form)), form

    # CRLF and LF forms of one file digest equally.
    with tempfile.TemporaryDirectory() as tmp:
        lf, crlf = os.path.join(tmp, "lf"), os.path.join(tmp, "crlf")
        open(lf, "wb").write(b"(ns a)\n(def b 1)\n")
        open(crlf, "wb").write(b"(ns a)\r\n(def b 1)\r\n")
        assert digest(lf) == digest(crlf)
        open(crlf, "wb").write(b"(ns a)\r\n(def b 2)\r\n")
        assert digest(lf) != digest(crlf)

    # The rename respects symbol boundaries in BOTH directions: it moves the
    # namespace and it leaves the bench suite whose name merely starts with it.
    table = {"a.front.slot": "p.impl.slot"}
    assert rename("(ns a.front.slot)", table) == "(ns p.impl.slot)"
    assert rename("see a.front.slot-cljs-test", table) == "see a.front.slot-cljs-test"
    assert rename("x.presence y.presence-react",
                  {"x.presence": "P", "y.presence-react": "R"}) == "P R"

    # Reader forms see through layout and comments, never through a token.
    assert reader_form("(def  a\n  1)") == reader_form(";; c\n(def a 1)")
    assert reader_form('(def a "one")') != reader_form('(def a "two")')
    assert definitions('(defmacro m [x] x)\n(def v 1)\n"(def not-a-def 1)"') == ["m", "v"]

    # --- the mutation cases -------------------------------------------------
    with tempfile.TemporaryDirectory() as tmp:
        donor = os.path.join(tmp, "donor")
        pkg = os.path.join(tmp, "implementation", "hicasso")
        os.makedirs(donor)
        os.makedirs(os.path.join(pkg, "src"))
        path = os.path.join(pkg, "frozen-sources.edn")
        donor_a = os.path.join(donor, "a.cljs")
        package_a = os.path.join(pkg, "src", "a.cljs")

        body = ('(ns donor.a\n'
                '  "prose naming donor.b-cljs-test, which no row renames")\n'
                '(def answer 1)\n'
                '(defn use-it [] (donor.b/f answer))\n')
        _write(donor_a, body)
        # The package IS that donor, renamed. `donor.b-cljs-test` in the
        # docstring must survive; `donor.b` in the call must not.
        moved = body.replace("(ns donor.a", "(ns pkg.a").replace("(donor.b/f", "(pkg.b/f")
        _write(package_a, moved)

        def manifest(package_ns="pkg.a", row_ns="pkg.a", lines=None, sha=None):
            text = read_text(donor_a)
            _write(path, MANIFEST_TEMPLATE % {
                "package_ns": package_ns, "row_ns": row_ns,
                "lines": len(text.splitlines()) if lines is None else lines,
                "sha": digest(donor_a) if sha is None else sha})

        def failures(kind=None):
            found = check(path, pkg, tmp)[0]
            return [f for f in found if kind is None or f.startswith(kind)]

        # GREEN: a legitimate rename, and only a rename.
        manifest()
        assert failures() == [], failures()
        assert "donor.b-cljs-test" in read_text(package_a), "boundary case must be live"

        # RED: audit #7735's exact reproduction — a package body that differs.
        _write(package_a, moved.replace("(def answer 1)", "(def answer :silently-different)"))
        assert failures("MOVED"), "a changed package body must red"
        assert failures("FROZEN") == [], "and the donor is untouched, so FROZEN stays green"

        # RED: a package body that differs by DELETION rather than by change.
        _write(package_a, moved.replace("(def answer 1)\n", ""))
        assert failures("MOVED"), "a deleted package line must red"

        # RED: an INSERTED line carrying code.
        _write(package_a, moved.replace("(def answer 1)", "(def answer 1)\n(def extra 2)"))
        assert failures("MOVED"), "an inserted line of code must red"

        # GREEN: an inserted COMMENT line — the move's one licensed addition,
        # and the shape of the six `rf2:builder-bypass-ok` blocks.
        _write(package_a, moved.replace("(def answer 1)",
                                        "  ;; rf2:builder-bypass-ok - licensed\n(def answer 1)"))
        assert failures() == [], failures()

        # RED: the rename NOT applied — the package still names the bench ns.
        _write(package_a, body.replace("(ns donor.a", "(ns pkg.a"))
        assert failures("MOVED"), "an unapplied rename must red"

        _write(package_a, moved)

        # RED: the package declares a namespace the row does not pin.
        manifest(row_ns="pkg.somewhere-else")
        assert failures("MOVED"), "a package ns that is not the row's must red"

        # RED: `:renames` disagrees with the row's `:ns`.
        manifest(package_ns="pkg.elsewhere", row_ns="pkg.a")
        assert failures("MOVED"), ":renames must agree with :ns"

        # RED: `:lines` that does not match the donor.
        manifest(lines=999)
        assert failures("FROZEN"), "a wrong :lines must red"

        # RED: a drifted donor digest.
        manifest(sha="0" * 64)
        assert failures("FROZEN"), "a drifted donor digest must red"

        # And `--update` re-pins BOTH the digest and the line count, leaving a
        # manifest that is green for the right reason rather than by omission.
        check(path, pkg, tmp, update=True)
        assert failures() == [], failures()
        assert read_edn(read_text(path))[":files"][0][":lines"] == 4

        # RED: a real import of the bench tree.
        _write(package_a, moved.replace(
            "(ns pkg.a", "(ns pkg.a (:require [re-frame.bench.hicasso.arm1.runtime :as rt])"))
        assert failures("SEALED"), "an import must red"

    # --- the macro-only row, held to its shapes -----------------------------
    with tempfile.TemporaryDirectory() as tmp:
        donor = os.path.join(tmp, "donor")
        pkg = os.path.join(tmp, "implementation", "hicasso")
        os.makedirs(donor)
        os.makedirs(os.path.join(pkg, "src"))
        path = os.path.join(pkg, "frozen-sources.edn")
        donor_b = os.path.join(donor, "b.clj")
        package_b = os.path.join(pkg, "src", "b.cljc")

        _write(donor_b, '(ns donor.b)\n\n(defmacro m\n  "doc"\n  [x]\n  `(donor.b/f ~x))\n')
        _write(path, SHAPE_MANIFEST % {"lines": 6, "sha": digest(donor_b)})

        def shape_failures():
            return check(path, pkg, tmp)[0]

        # GREEN: same macro, re-wrapped, re-indented, renamed — the real row's
        # shape, where `#?(:clj …)` moves every line and changes no token.
        _write(package_b, '(ns pkg.b\n  "a different docstring entirely")\n\n'
                          ';; a comment the donor never had\n'
                          '#?(:clj\n   (defmacro m\n     "doc"\n     [x]\n     `(pkg.b/f ~x)))\n')
        assert shape_failures() == [], shape_failures()

        # RED: the macro's BODY changed.
        _write(package_b, '(ns pkg.b)\n#?(:clj\n   (defmacro m\n     "doc"\n     [x]\n'
                          '     `(pkg.b/g ~x)))\n')
        assert any(f.startswith("MOVED") for f in shape_failures()), "a changed macro must red"

        # RED: the macro's DOCSTRING changed — a docstring is a string, and a
        # string is code.
        _write(package_b, '(ns pkg.b)\n#?(:clj\n   (defmacro m\n     "other"\n     [x]\n'
                          '     `(pkg.b/f ~x)))\n')
        assert any(f.startswith("MOVED") for f in shape_failures()), "a changed docstring must red"

        # RED: the macro is simply absent.
        _write(package_b, '(ns pkg.b)\n')
        assert any("does not define m" in f for f in shape_failures()), "a dropped macro must red"

    print("OK: check_freeze self-test passed")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove the gate's red/green classification, then exit")
    parser.add_argument("--update", action="store_true",
                        help="re-pin drifted donor digests and line counts (read the header first)")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    failures, count = check(MANIFEST, PACKAGE_ROOT, REPO_ROOT, update=args.update)
    if failures:
        print("FAIL: implementation/hicasso freeze gate\n")
        for f in failures:
            print("  " + f)
        print("\n%d row(s) pinned. See implementation/hicasso/frozen-sources.edn "
              "for what a red row means and which of the three responses is right."
              % count)
        return 1
    print("OK: %d frozen row(s) match the bench tree, and each package file is "
          "its donor moved; no package file imports it." % count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
