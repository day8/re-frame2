#!/usr/bin/env python3
"""Every hicasso verb a guide sample names resolves to a public def.

`docs/core/hicasso/` is the front door, and a guide whose samples name verbs
the door no longer carries teaches whatever rots first.  This reads every
fenced Clojure block in the guide, learns the guide's own aliases from its
`[re-frame.hicasso… :as alias]` requires, and checks each `alias/verb` in
code position against the `def*` heads of that namespace's source file.  A
verb the source does not define, or a namespace with no source, reds naming
the chapter, the block and the verb.

It pins nothing: a prose edit, or a sample edit that names no new verb, is a
one-file change with no roster to regenerate.  It never evaluates a line of
Clojure; strings, `;` comments and character literals are blanked before a
verb is read, `::alias/name` is a marker keyword rather than a var use, and a
`#_` discard is read as code.

    python hicasso/scripts/check_guide_samples.py --self-test
    python hicasso/scripts/check_guide_samples.py [--list]
"""

from __future__ import annotations

import argparse
import os
import re
import sys

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
PACKAGE_ROOT = os.path.dirname(SCRIPTS_DIR)                 # implementation/hicasso
REPO_ROOT = os.path.dirname(os.path.dirname(PACKAGE_ROOT))  # repo root

GUIDE_DIR = os.path.join(REPO_ROOT, "docs", "core", "hicasso")

# Where a `re-frame.hicasso*` namespace's source may live.  `test_kit/src` is
# on the list because the testing chapter is written against the `ht/` and
# `hm/` kits, which ship from there.
SOURCE_ROOTS = [
    os.path.join(PACKAGE_ROOT, "src"),
    os.path.join(PACKAGE_ROOT, "test_kit", "src"),
]
SOURCE_EXTS = (".cljc", ".cljs", ".clj")
CLOJURE_LANGS = frozenset({"clojure", "clj", "cljs", "cljc", "edn"})

# `[re-frame.hicasso.x :as alias]` — the guide's own alias bindings.
_REQUIRE_RE = re.compile(r"\[(re-frame\.hicasso[a-z.-]*)\s+:as\s+([a-zA-Z][\w.-]*)\]")
# `alias/verb` in code position.  The lookbehind keeps `::h/value` and
# `:h/thing` out: keywords are not var uses.
_QUALIFIED_RE = re.compile(
    r"(?<![:\w/.*+!?<>='-])([a-zA-Z][\w.-]*)/([a-zA-Z*+!?<>=_-][\w*+!?<>=_.'-]*)"
)
# `(def… name)`, `(defn… name)`, and the namespace-qualified defining macros a
# module uses on itself — `(h/defview buffered-field …)` mints a public name.
_DEF_RE = re.compile(
    r"\((?:[a-zA-Z0-9.*+!?<>=_-]+/)?(def[a-z-]*)\s+"
    r"((?:\^\{[^{}]*\}\s*|\^:[\w-]+\s*|\^[\w.]+\s*)*)"
    r"([a-zA-Z*+!?<>=_-][\w*+!?<>=_.'-]*)"
)


def mask(text: str) -> str:
    """Blank strings, `;` comments and character literals, keeping newlines."""
    out = list(text)
    n = len(text)

    def blank(a: int, b: int) -> None:
        for k in range(a, min(b, n)):
            if out[k] != "\n":
                out[k] = " "

    i = 0
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            blank(i, j + 1)
            i = j + 1
        elif c == ";":
            j = text.find("\n", i)
            j = n if j < 0 else j
            blank(i, j)
            i = j
        elif c == "\\":
            j = i + 2
            while j < n and text[j].isalnum():
                j += 1
            blank(i, j)
            i = j
        else:
            i += 1
    return "".join(out)


def clojure_blocks(text: str) -> list[tuple[int, str]]:
    """`[(ordinal, masked code)]` for each Clojure fence, numbered among all fences."""
    lines = text.replace("\r\n", "\n").split("\n")
    out: list[tuple[int, str]] = []
    i = n = 0
    while i < len(lines):
        if not lines[i].startswith("```"):
            i += 1
            continue
        lang = lines[i][3:].strip() or "none"
        j = i + 1
        while j < len(lines) and not lines[j].startswith("```"):
            j += 1
        n += 1
        if lang in CLOJURE_LANGS:
            out.append((n, mask("\n".join(lines[i + 1:j]))))
        i = j + 1
    return out


def corpus(guide_dir: str = GUIDE_DIR) -> dict[str, list[tuple[int, str]]]:
    out = {}
    for entry in sorted(os.listdir(guide_dir)):
        if entry.endswith(".md"):
            with open(os.path.join(guide_dir, entry), encoding="utf-8") as fh:
                out[entry] = clojure_blocks(fh.read())
    return out


def aliases(corp: dict) -> dict[str, str]:
    """`{alias: namespace}` for every hicasso require in the guide's samples."""
    return {alias: ns for blks in corp.values() for _n, code in blks
            for ns, alias in _REQUIRE_RE.findall(code)}


def uses(corp: dict, alias_map: dict[str, str]) -> dict[tuple[str, str], list[str]]:
    """`{(namespace, verb): [site, …]}` for every hicasso verb a sample names."""
    out: dict[tuple[str, str], list[str]] = {}
    for page, blks in corp.items():
        for n, code in blks:
            for alias, verb in _QUALIFIED_RE.findall(code):
                ns = alias_map.get(alias)
                if ns is not None:
                    out.setdefault((ns, verb), []).append(f"{page} block {n}")
    return out


def namespace_source(ns: str) -> str | None:
    rel = ns.replace("-", "_").replace(".", os.sep)
    for root in SOURCE_ROOTS:
        for ext in SOURCE_EXTS:
            candidate = os.path.join(root, rel + ext)
            if os.path.isfile(candidate):
                return candidate
    return None


def public_names(source: str) -> set[str]:
    """Every name a `def*` head in `source` mints, private ones excluded."""
    return {name for head, meta, name in _DEF_RE.findall(mask(source))
            if not head.endswith("-") and ":private" not in meta}


def unresolved(use_map: dict, exports: dict[str, set[str] | None]) -> list[str]:
    problems = []
    for (ns, verb), sites in sorted(use_map.items()):
        names = exports.get(ns)
        where = ", ".join(sites)
        if names is None:
            problems.append(f"{ns}/{verb}: no source for {ns} ({where})")
        elif verb not in names:
            problems.append(f"{ns}/{verb}: named at {where}, defined nowhere in {ns}")
    return problems


def run_check(verbose: bool = False) -> int:
    corp = corpus()
    alias_map = aliases(corp)
    use_map = uses(corp, alias_map)
    exports: dict[str, set[str] | None] = {}
    for ns in set(alias_map.values()):
        src = namespace_source(ns)
        if src is None:
            exports[ns] = None
        else:
            with open(src, encoding="utf-8") as fh:
                exports[ns] = public_names(fh.read())
    problems = unresolved(use_map, exports)
    if problems:
        print("FAIL: the Hicasso guide names a verb the package does not define", file=sys.stderr)
        for line in problems:
            print(f"  {line}", file=sys.stderr)
        return 1
    sites = sum(len(s) for s in use_map.values())
    print(f"Hicasso guide samples: {len(use_map)} distinct hicasso verbs at {sites} "
          f"site(s) across {len(corp)} pages resolve to a public def.")
    if verbose:
        for (ns, verb), s in sorted(use_map.items()):
            print(f"  {ns}/{verb:24s} {len(s):3d} site(s)")
    return 0


def self_test() -> int:
    """Drive the rule red and green against synthetic input."""
    failures: list[str] = []

    def check(label: str, ok: bool) -> None:
        if not ok:
            failures.append(label)

    page = ('```clojure\n(ns a (:require [re-frame.hicasso :as h]))\n'
            '(h/sub [:x]) ::h/value :h/thing "h/ghost" ; h/ghost2\n'
            '(str \\; (h/defview v [] 1)) (js/setTimeout f 0)\n```\n'
            '```css\n(h/absent)\n```\n')
    corp = {"p.md": clojure_blocks(page)}
    alias_map = aliases(corp)
    seen = uses(corp, alias_map)
    exports = {"re-frame.hicasso": {"sub", "defview"}}
    check("the alias is read from the sample's own require", alias_map == {"h": "re-frame.hicasso"})
    check("a var use is seen", ("re-frame.hicasso", "sub") in seen)
    check("a marker keyword is not a var use", ("re-frame.hicasso", "value") not in seen)
    check("a plain keyword is not a var use", ("re-frame.hicasso", "thing") not in seen)
    check("a string is inert", ("re-frame.hicasso", "ghost") not in seen)
    check("a comment is inert", ("re-frame.hicasso", "ghost2") not in seen)
    check("a `\\;` character literal opens no comment", ("re-frame.hicasso", "defview") in seen)
    check("an unrequired alias is out of scope", all(ns != "js" for ns, _ in seen))
    check("a non-Clojure fence carries no verb", ("re-frame.hicasso", "absent") not in seen)
    check("silence when every verb resolves", unresolved(seen, exports) == [])

    phantom = {"p.md": clojure_blocks("```clojure\n[re-frame.hicasso :as h]\n(h/does-not-exist)\n```\n")}
    problems = unresolved(uses(phantom, aliases(phantom)), exports)
    check("a phantom verb reds naming the page, the block and the verb",
          any("p.md block 1" in p and "does-not-exist" in p for p in problems))
    ghost = {"p.md": clojure_blocks("```clojure\n[re-frame.hicasso.ghost :as g]\n(g/boo)\n```\n")}
    check("a namespace with no source reds",
          any("no source" in p for p in unresolved(uses(ghost, aliases(ghost)),
                                                   {"re-frame.hicasso.ghost": None})))

    names = public_names('(def ^{:doc "x"}\n  sub impl/sub)\n(h/defview buffered-field [p] 1)\n'
                         '(defn- hidden [] 1)\n(def ^:private secret 1)\n(def "(def fake 1)" 2)')
    check("a metadata-carrying def mints a name", "sub" in names)
    check("a namespace-qualified defining macro mints a name", "buffered-field" in names)
    check("a private def is not public", not names & {"hidden", "secret", "fake"})

    if failures:
        print("SELF-TEST FAILED:", file=sys.stderr)
        for line in failures:
            print(f"  {line}", file=sys.stderr)
        return 1
    print("check_guide_samples self-test: the rule fires.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--self-test", action="store_true",
                        help="drive the rule red and green against synthetic input")
    parser.add_argument("--list", "--verbose", dest="verbose", action="store_true",
                        help="list every resolved verb and its use-site count")
    args = parser.parse_args()
    return self_test() if args.self_test else run_check(verbose=args.verbose)


if __name__ == "__main__":
    sys.exit(main())
