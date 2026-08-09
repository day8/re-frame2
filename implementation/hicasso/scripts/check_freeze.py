#!/usr/bin/env python3
"""THE FREEZE GATE for implementation/hicasso/ (rf2-hic-001).

Two properties, both of which stop being true silently:

  FROZEN  Every donor file the package was copied from still hashes to the
          digest recorded in `frozen-sources.edn`, and every package file
          the copy produced is still there. A bench-tree edit that the
          package did not receive is a divergence, and a divergence nobody
          noticed is the failure mode this gate exists for.

  SEALED  No file under `implementation/hicasso/` IMPORTS a benchmark-tree
          namespace. Prose may name one — a docstring citing the witness
          that pins a behaviour is worth keeping — but a `:require`,
          `:require-macros` or runtime `require` would make the shipped
          package depend on a test tree.

The gate reads only `frozen-sources.edn`; the roster, the rename table and
the forbidden prefixes all live there, so adding a module is a data edit.

    python implementation/hicasso/scripts/check_freeze.py
    python implementation/hicasso/scripts/check_freeze.py --self-test
    python implementation/hicasso/scripts/check_freeze.py --update

`--update` re-pins every digest from the donor tree as it stands. It is the
correct response to a deliberate, ported bench-tree change and the WRONG
response to a surprise: read `frozen-sources.edn`'s header before reaching
for it.

SUNSET. This gate is scaffolding for the extraction, not a permanent law.
rf2-hic-009 carves the runtime into owned modules, and from the first such
split the package is deliberately ahead of the bench tree — at that point
the divergent rows are retired rather than re-pinned, and when the last row
goes, so does this file.
"""
import argparse
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
        if c == "\\":
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
# The two predicates
# ---------------------------------------------------------------------------

def digest(path):
    """SHA-256 over the file's LINE-ENDING-NORMALISED bytes.

    Mike develops on Windows and the maintainers on Mac and Linux, so one
    commit checks out CRLF in one tree and LF in another. A raw-byte digest
    would make this gate report a platform rather than a change.
    """
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read().replace(b"\r\n", b"\n")).hexdigest()


def _blank_strings(src):
    """Replace every string literal's CONTENT with spaces, keeping offsets.

    This is what lets a docstring name `re-frame.bench.hicasso.arm1.
    hook-ledger-dom-cljs-test` — the witness that pins the hook budget —
    without the gate calling it an import.
    """
    out, instr, esc = [], False, False
    for c in src:
        if instr:
            if esc:
                esc = False
                out.append(" ")
            elif c == "\\":
                esc = True
                out.append(" ")
            elif c == '"':
                instr = False
                out.append('"')
            else:
                out.append("\n" if c == "\n" else " ")
        else:
            if c == '"':
                instr = True
            out.append(c)
    return "".join(out)


_REQUIRE_HEAD = re.compile(r"\((?::require-macros|:require|:use|require|use)\b")
_SYMBOL = re.compile(r"[A-Za-z][\w.$*+!?<>=-]*")


def imported_namespaces(src):
    """Every namespace symbol reachable from a require/use form in `src`."""
    text = _blank_strings(src)
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


def source_files(root):
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in (".shadow-cljs", "out", "node_modules")]
        for name in sorted(names):
            if name.endswith(SOURCE_SUFFIXES):
                yield os.path.join(base, name)


# ---------------------------------------------------------------------------
# The run
# ---------------------------------------------------------------------------

def check(manifest_path, package_root, repo_root, update=False):
    with open(manifest_path, encoding="utf-8") as fh:
        manifest = read_edn(fh.read())

    donor_root = os.path.join(repo_root, manifest[":donor-root"])
    forbidden = tuple(manifest[":forbidden-import-prefixes"])
    failures, repins = [], []

    for row in manifest[":files"]:
        bench = os.path.join(donor_root, row[":bench"])
        package = os.path.join(package_root, row[":package"])
        if not os.path.exists(bench):
            failures.append("FROZEN  donor file is gone: %s" % row[":bench"])
            continue
        if not os.path.exists(package):
            failures.append("FROZEN  package file is gone: %s" % row[":package"])
        actual = digest(bench)
        if actual != row[":sha256"]:
            if update:
                repins.append((row[":sha256"], actual))
            else:
                failures.append(
                    "FROZEN  %s changed in the bench tree\n"
                    "          pinned %s\n"
                    "          actual %s" % (row[":bench"], row[":sha256"], actual))

    for path in source_files(package_root):
        offenders = sorted(
            ns for ns in imported_namespaces(open(path, encoding="utf-8").read())
            if ns.startswith(forbidden))
        for ns in offenders:
            failures.append(
                "SEALED  %s imports %s"
                % (os.path.relpath(path, repo_root).replace(os.sep, "/"), ns))

    if update and repins:
        with open(manifest_path, encoding="utf-8") as fh:
            text = fh.read()
        for old, new in repins:
            text = text.replace(old, new)
        with open(manifest_path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
        print("re-pinned %d digest(s) in %s" % (len(repins), manifest_path))

    return failures, len(manifest[":files"])


# ---------------------------------------------------------------------------
# --self-test: prove the gate classifies, rather than merely runs
# ---------------------------------------------------------------------------

def self_test():
    """Every assertion here is a case the live run must be able to fail on.

    A gate whose only evidence is a green run has not shown it can go red.
    """
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

    # And the whole gate goes red on a drifted digest and on a real import.
    with tempfile.TemporaryDirectory() as tmp:
        donor = os.path.join(tmp, "donor")
        pkg = os.path.join(tmp, "implementation", "hicasso")
        os.makedirs(donor)
        os.makedirs(os.path.join(pkg, "src"))
        open(os.path.join(donor, "a.cljs"), "w").write("(ns donor.a)\n")
        open(os.path.join(pkg, "src", "a.cljs"), "w").write("(ns pkg.a)\n")
        good = digest(os.path.join(donor, "a.cljs"))
        path = os.path.join(pkg, "frozen-sources.edn")

        def write_manifest(sha):
            open(path, "w").write(
                '{:donor-root "donor"\n'
                ' :forbidden-import-prefixes ["re-frame.bench."]\n'
                ' :files [{:bench "a.cljs" :package "src/a.cljs" :sha256 "%s"}]}\n' % sha)

        write_manifest(good)
        assert check(path, pkg, tmp)[0] == [], "clean tree must be green"

        write_manifest("0" * 64)
        assert any(f.startswith("FROZEN") for f in check(path, pkg, tmp)[0]), "drift must be red"

        write_manifest(good)
        open(os.path.join(pkg, "src", "a.cljs"), "w").write(
            "(ns pkg.a (:require [re-frame.bench.hicasso.arm1.runtime :as rt]))\n")
        assert any(f.startswith("SEALED") for f in check(path, pkg, tmp)[0]), "import must be red"

    print("OK: check_freeze self-test passed")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove the gate's red/green classification, then exit")
    parser.add_argument("--update", action="store_true",
                        help="re-pin drifted digests from the donor tree (read the header first)")
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
    print("OK: %d frozen row(s) match the bench tree; no package file imports it." % count)
    return 0


if __name__ == "__main__":
    sys.exit(main())
