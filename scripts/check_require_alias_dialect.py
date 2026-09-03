#!/usr/bin/env python3
"""Conventions §Require-alias dialect — the repo-wide require-alias ratchet (rf2-ydpr).

`spec/Conventions.md` §Require-alias dialect fixes ONE canonical alias per
framework namespace:

    re-frame.core          ->  rf          (the root keeps the bare root alias)
    re-frame.<tail>        ->  rf.<tail>   (full dotted tail, no abbreviation)

and reserves the BARE leaf form (`:as routing`, `:as machines`) for application
namespaces, so an app's own `myapp.routing` never has to be disambiguated
against the framework's.

Three artefact sweeps migrated the tree to that dialect — core and routing in
PR #9103, machines in PR #9114, resources in PR #9129 — and each of their beads
asked for a CI ratchet that neither of them built, because `scripts/` and
`.github/workflows/` are hot-zone and the ratchet has to be ONE repo-wide
instrument. PER-ARTEFACT IS THE DEFECT, not a smaller version of the fix:
rf2-6r9j.161 fenced core and rf2-6r9j.167 fenced routing, and machines went
unowned between them precisely because nothing looked at the whole tree. So
this checker reads EVERY git-tracked `.clj` / `.cljs` / `.cljc` in the
repository and requires every surface it finds to be NAMED in the baseline —
a new top-level tree cannot go unowned the way machines did (see
`_assert_every_surface_is_owned`).


WHY SYNTAX-AWARE AND NOT GREP

Five textual traps, four of them measured on the machines sweep and one on the
core sweep, each of which a line-oriented regex ratchet gets wrong:

  (1) A LITERAL SINGLE-COLON KEYWORD IS NOT AN ALIAS REFERENCE, AND A
      DOUBLE-COLON ONE IS.  machines carries 179 sites of the shape
      `:machines/rearm-after-hydration!`, `:router/dispatch!`,
      `:frame/destroyed` — late-bind keys, trace channels and frame ids whose
      first segment collides with a live alias — against 7 auto-resolved
      `::result/...` sites, which DO move with the alias.  A first pass on that
      sweep treated the colon as a word boundary and rewrote 42 late-bind keys
      into new keyword values; nothing failed, because a renamed late-bind key
      is only read back by another renamed site in the same file.

      This checker sidesteps the whole family by construction: it reads the
      LIBSPEC, never the use site.  An `::alias/key` moves with its libspec and
      a `:literal/key` is not an alias reference at all, so neither is this
      gate's subject.  The negative fixture pins that.

  (2) FULLY-QUALIFIED, QUOTED AND VAR-QUOTED FORMS EACH NEED DIFFERENT
      HANDLING at a use site — `re-frame.machines.paths/snapshot-path` must not
      move, `'re-frame.fx/dispatch-later-timers` must not move, but
      `#'parallel/reduce-regions` must.  Again: not this gate's subject, and
      pinned green by the negative fixture.

  (3) REPO-RELATIVE DOC PATHS SHARE A FIRST SEGMENT WITH LIVE ALIASES —
      `spec/conformance/fixtures/`, `docs/machines/...`, `../../spec/...`.
      They live in strings, which the reader-level mask below blanks before a
      single bracket is scanned.

  (4) MULTI-LINE REQUIRE VECTORS DEFEAT ANY SINGLE-LINE REGEX, AND THE MISS IS
      SILENT.  `parallel.cljc` opened `[re-frame.machines.result :as result`
      and closed the vector on the NEXT line; a regex anchored on the closing
      bracket skipped that libspec and, with it, 116 use sites — while
      reporting a clean run.  Core carries 39 such libspecs, all already
      canonical, which is exactly why a line-oriented census would have
      reported a clean run there too.  So libspec vectors are found by
      BALANCED-BRACKET scan over reader-masked text, never by line.

  (5) A RUNTIME `(require ...)` IS A REQUIRE EDGE AND clj-kondo DOES NOT SEE
      IT.  The core sweep's residue after 312 files was ONE edge, and it
      survived because it is not an `(ns ... (:require ...))` edge at all: it
      is a top-level `(require '[re-frame.late-bind.directory :as directory])`.
      clj-kondo's namespace-usage analysis does not report runtime require
      forms, which is why that bead's clj-kondo census read 1616 where a
      textual scan of the same tree read 1617 — THE ONE-EDGE DELTA IS THAT
      EDGE.  A ratchet built on the ns form alone reproduces the exact miss it
      exists to prevent, so `(require ...)` / `(require-macros ...)` forms are
      in the scan surface, they carry their own positive fixture, and the
      self-test proves that fixture goes GREEN when the runtime context is
      withheld (`_selftest_runtime_context_is_load_bearing`).


THE EXEMPTION IS DERIVED, NEVER LISTED

A libspec is exempt when the SAME alias is bound to two or more DISTINCT
namespaces within the same file.  That is exactly the host-conditional
reader-arm shape —

    #?(:clj  [re-frame.substrate.plain-atom :as substrate]
       :cljs [re-frame.adapter.reagent      :as substrate])

— where the shared name IS the point: per-arm dotted tails would give the
single use site two names.  Measured across the tree: machines 1 file, routing
8, resources 12 files / 24 edges (all one alias `substrate`), core ZERO.  A
path allowlist would go stale on the first rename and would have carried a
wrong entry from the day it was written (rf2-j5or's notes say "core one"; the
core sweep refuted it).  This predicate cannot rot.


DEDUP: ONE EDGE PER (file, namespace, alias)

A `.cljc` that binds the same alias to the same namespace in both reader arms
is ONE edge, not two.  That is the dedup the artefact beads' own clj-kondo
censuses applied, and the reason a naive count read 841 on machines where the
bead read 835.


SHAPE: A PER-SURFACE FLOOR, NOT A ZERO GATE

`scripts/require-alias-baseline.edn` maps each surface to the number of
violating edges it is allowed to carry.  Migrated surfaces sit at 0 and can
never regress; unmigrated ones ratchet DOWN as beads land (a surface below its
floor is reported so the floor can be lowered, and `--write-baseline`
regenerates the file).  Same shape as `scripts/check-ai-tracking-ratchet.sh`,
which is the in-repo precedent.


IT CANNOT REPORT A CONFIDENT ZERO OVER A CORPUS IT CANNOT SEE

An instrument that answers "nothing here" gives the same answer when it is
misused, and a misused one raises no error — this repo has measured exactly
that (a surface classifier handed revisions where it expects file paths
reported every surface as unaffected, which is indistinguishable from a
genuinely unaffected change).  So RECOGNISING NOTHING IS A DIFFERENT OUTCOME
FROM FINDING NOTHING here, and it exits 2 rather than 0.  `assert_usable`
enforces, in order:

  * the roster is non-empty (git-tracked `.clj`/`.cljs`/`.cljc` files exist);
  * at least one file was actually read;
  * the total re-frame require edges observed meets the baseline's
    `:min-edges` floor — a scanner that has gone blind reads far below it;
  * every surface named in the baseline still exists and still contributes
    files (a stale baseline entry is an error, not a free pass);
  * every surface that contributes files is NAMED in the baseline — the
    anti-unowned rule, at tree granularity;
  * every re-frame-headed libspec vector parsed into a recognised shape; an
    unrecognised one (a prefix list, a reader conditional in the alias slot)
    is REPORTED, never silently skipped.

MODES

    python scripts/check_require_alias_dialect.py               gate (default)
    python scripts/check_require_alias_dialect.py --report      per-surface census
    python scripts/check_require_alias_dialect.py --self-test   fixture self-tests
    python scripts/check_require_alias_dialect.py --write-baseline
    python scripts/check_require_alias_dialect.py --scan-path <p>

Exit 0 clean, 1 findings, 2 unusable corpus / malformed baseline / unparseable
libspec.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path
from typing import Iterable, NamedTuple

# --------------------------------------------------------------------------
# Corpus
# --------------------------------------------------------------------------

CLOJURE_SUFFIXES = (".clj", ".cljs", ".cljc")

# `scripts/_test_fixtures/` is DELIBERATELY non-idiomatic: it is where every
# checker in this directory plants the defect it exists to catch, including
# this one's own positive fixtures.  Scanning it would make each gate's fixture
# corpus another gate's finding.  Excluded by prefix, and named here rather
# than left implicit so the exclusion is visible in the same file as the
# anti-unowned rule it is an exception to.
EXCLUDED_PREFIXES = ("scripts/_test_fixtures/",)

# The framework namespace root this dialect governs.  A namespace is in scope
# when it IS `re-frame` or begins `re-frame.` — `re-frame2-pair.runtime` and
# `reagent2.core` are not, and the dot is what tells them apart.
_ROOT = "re-frame"

# The one namespace exempt by construction: it IS the root, so it takes the
# bare root alias, matching the bare `:rf/*` keyword root.
_ROOT_NS = "re-frame.core"
_ROOT_ALIAS = "rf"

# Libspec option keywords whose VALUE is a collection we must step over.  A
# `:refer [dispatch]` vector nested in a libspec is not a prefix list, and
# telling the two apart is what keeps `[re-frame.core :as rf :refer [...]]`
# from being reported as an unrecognised shape.
_OPTION_KEYWORDS_WITH_VALUES = frozenset({
    ":as", ":as-alias", ":refer", ":refer-macros", ":only", ":exclude",
    ":rename", ":include-macros", ":default",
})

# The two contexts in which a libspec vector is a REQUIRE EDGE.  Both are
# load-bearing: see trap (5) in the module docstring for why dropping
# "require" reproduces the core sweep's one-edge miss.
CONTEXT_NS = "ns"
CONTEXT_REQUIRE = "require"
ALL_CONTEXTS = (CONTEXT_NS, CONTEXT_REQUIRE)

_NS_FORM_RE = re.compile(r"\(ns(?=[\s(\[{])")
_REQUIRE_FORM_RE = re.compile(r"\((?:clojure\.core/)?require(?:-macros)?(?=[\s'(\[{)])")

_OPEN_TO_CLOSE = {"(": ")", "[": "]", "{": "}"}


# --------------------------------------------------------------------------
# Reader-level masking
# --------------------------------------------------------------------------
#
# Blank out everything the Clojure reader does not treat as code, PRESERVING
# offsets and newlines so a finding's line number is still the file's.  Three
# things have to be handled together, and getting any one wrong is silent:
#
#   * `"..."` strings and `#"..."` regex literals, with backslash escapes —
#     `docs/machines/...` and this repo's own quoted `(require '[...])` prose
#     live in them, and `check_keyword_catalogue_drift.py` masks for the same
#     reason;
#   * `;` line comments — `;;   (require '[re-frame.core :as rf]` appears
#     verbatim in the playground's docstring;
#   * CHARACTER LITERALS.  `\;` is a semicolon, not a comment, and `\"` is a
#     quote, not a string opener.  A masker that misses this desynchronises
#     from the reader at the first `\"` and blanks the rest of the file — the
#     "recognises nothing" failure, arriving as a clean run.

def mask_source(text: str) -> str:
    """Blank strings, regex literals, comments and char literals, keeping offsets."""
    out = list(text)
    n = len(text)

    def blank(start: int, stop: int) -> None:
        for k in range(start, stop):
            if out[k] != "\n":
                out[k] = " "

    i = 0
    while i < n:
        c = text[i]
        if c == "\\":
            # A character literal: `\a`, `\;`, `\"`, `\\`, `\newline`, `\u0041`.
            j = min(i + 2, n)
            while j < n and (text[j].isalnum() or text[j] == "-"):
                j += 1
            blank(i, j)
            i = j
            continue
        if c == ";":
            j = text.find("\n", i)
            j = n if j == -1 else j
            blank(i, j)
            i = j
            continue
        if c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            # Blank the CONTENTS but keep both delimiters. A string is still an
            # atom of the form it sits in, and dropping the quotes would delete
            # the token entirely — which is how the baseline reader below lost
            # its surface KEYS and started pairing floors with floors.
            blank(i + 1, max(i + 1, min(j, n) - 1))
            i = min(j, n)
            continue
        i += 1
    return "".join(out)


# --------------------------------------------------------------------------
# Balanced-bracket scanning (trap 4)
# --------------------------------------------------------------------------

def balanced_end(masked: str, start: int) -> int:
    """Index just past the collection opening at `start`, or -1 if unbalanced."""
    if masked[start] not in _OPEN_TO_CLOSE:
        return -1
    stack: list[str] = []
    for i in range(start, len(masked)):
        c = masked[i]
        if c in _OPEN_TO_CLOSE:
            stack.append(_OPEN_TO_CLOSE[c])
        elif c in ")]}":
            if not stack or stack[-1] != c:
                return -1
            stack.pop()
            if not stack:
                return i + 1
    return -1


class Token(NamedTuple):
    text: str
    offset: int          # relative to the string the tokens were read from

    @property
    def is_collection(self) -> bool:
        return bool(self.text) and self.text[0] in _OPEN_TO_CLOSE


def top_level_tokens(masked_body: str) -> list[Token] | None:
    """Depth-0 tokens of a masked collection BODY; None if it does not balance.

    A nested collection comes back as ONE token, which is what lets a libspec's
    `:refer [...]` value be stepped over as an option value while a prefix
    list's sub-vector is reported as an unrecognised shape.
    """
    tokens: list[Token] = []
    i, n = 0, len(masked_body)
    while i < n:
        c = masked_body[i]
        if c.isspace() or c == ",":
            i += 1
            continue
        if c in _OPEN_TO_CLOSE:
            end = balanced_end(masked_body, i)
            if end == -1:
                return None
            tokens.append(Token(masked_body[i:end], i))
            i = end
            continue
        if c in ")]}":
            return None
        if c == '"':
            # A masked string keeps its delimiters and blanks its contents, so
            # it has to be consumed to the CLOSING quote rather than to the
            # first space.
            close = masked_body.find('"', i + 1)
            if close == -1:
                return None
            tokens.append(Token(masked_body[i:close + 1], i))
            i = close + 1
            continue
        j = i
        while j < n and not masked_body[j].isspace() and masked_body[j] not in "()[]{},":
            j += 1
        tokens.append(Token(masked_body[i:j], i))
        i = j
    return tokens


def _spans_of(masked: str, pattern: re.Pattern[str]) -> list[tuple[int, int]]:
    """Balanced spans of every form whose opening `pattern` matches."""
    spans: list[tuple[int, int]] = []
    for m in pattern.finditer(masked):
        end = balanced_end(masked, m.start())
        if end != -1:
            spans.append((m.start(), end))
    return spans


# --------------------------------------------------------------------------
# Libspec model
# --------------------------------------------------------------------------

class Edge(NamedTuple):
    """One require edge: a namespace bound to an alias in one file."""
    path: str            # repo-relative, forward slashes
    line: int
    namespace: str
    alias: str
    context: str         # CONTEXT_NS | CONTEXT_REQUIRE

    @property
    def canonical(self) -> str:
        return canonical_alias(self.namespace)

    @property
    def is_canonical(self) -> bool:
        return self.alias == self.canonical

    @property
    def surface(self) -> str:
        return surface_of(self.path)


class Unparseable(NamedTuple):
    """A re-frame-headed libspec whose shape this checker does not recognise.

    Never silently skipped: an unrecognised shape is the one thing that could
    let a bare alias through while the run still reported clean, so it exits 2.
    """
    path: str
    line: int
    text: str
    reason: str


def canonical_alias(namespace: str) -> str:
    """The one alias `spec/Conventions.md` §Require-alias dialect allows."""
    if namespace in (_ROOT, _ROOT_NS):
        return _ROOT_ALIAS
    return "rf." + namespace[len(_ROOT) + 1:]


def _is_framework_namespace(token: str) -> bool:
    return token == _ROOT or token.startswith(_ROOT + ".")


def _line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def _parse_libspec(
    rel_path: str, text: str, masked: str, start: int, end: int, context: str,
) -> tuple[Edge | None, Unparseable | None]:
    """Read one `[...]` vector.  Returns (edge, unparseable); both may be None.

    (None, None) means "not a re-frame libspec" — a `:refer` vector, an
    `(:import [java.util Date])` clause, an `#?@` splice wrapper, or a
    framework libspec that binds no alias at all (`[re-frame.core :refer [...]]`
    names nothing, so there is no alias to be wrong).
    """
    body = masked[start + 1:end - 1]
    tokens = top_level_tokens(body)
    if tokens is None or not tokens:
        return None, None
    head = tokens[0]
    if head.is_collection:
        # An `#?@(:clj [[re-frame.x :as y]])` splice wrapper, or a `:refer`
        # vector's contents.  The inner vectors are visited on their own.
        return None, None
    if not _is_framework_namespace(head.text):
        return None, None

    line = _line_of(text, start)
    raw = text[start:end]
    alias: str | None = None
    i = 1
    while i < len(tokens):
        tok = tokens[i]
        if tok.text in (":as", ":as-alias"):
            if i + 1 >= len(tokens) or tokens[i + 1].is_collection:
                return None, Unparseable(
                    rel_path, line, raw, f"{tok.text} with no alias symbol",
                )
            alias = tokens[i + 1].text
            i += 2
            continue
        if tok.text in _OPTION_KEYWORDS_WITH_VALUES:
            i += 2
            continue
        if tok.is_collection:
            # A sub-collection that is not an option value: the prefix-list
            # form `[re-frame.machines [result :as result]]`, whose canonical
            # alias is not derivable from this vector alone.
            return None, Unparseable(
                rel_path, line, raw,
                "prefix-list / unrecognised sub-form in a re-frame libspec",
            )
        if tok.text in ("#?", "#?@"):
            # A reader conditional SPLICING options into the libspec itself —
            # `[re-frame.trace :as rf.trace #?@(:cljs [:include-macros true])]`
            # is the live shape, 26 sites in implementation/core alone. The
            # spliced arms carry option pairs, so they are stepped over — but
            # only once they are shown to bind no alias. A conditional `:as`
            # would give one namespace two aliases by host, which is a shape
            # this checker will not guess at, so it is REPORTED rather than
            # skipped. Fails closed: the skip is earned, never assumed.
            if i + 1 >= len(tokens) or not tokens[i + 1].is_collection:
                return None, Unparseable(
                    rel_path, line, raw, "reader conditional with no form",
                )
            arms = tokens[i + 1].text
            if re.search(r":as(?:-alias)?(?=[\s)\]}])", arms):
                return None, Unparseable(
                    rel_path, line, raw,
                    "an alias bound inside a reader conditional in the libspec",
                )
            i += 2
            continue
        if tok.text.startswith(":"):
            # A flag-shaped option with no value (`:reload`, `:verbose`).
            i += 1
            continue
        return None, Unparseable(
            rel_path, line, raw, f"unrecognised libspec element {tok.text!r}",
        )

    if alias is None:
        return None, None
    return Edge(rel_path, line, head.text, alias, context), None


def read_file(
    rel_path: str, text: str, contexts: Iterable[str] = ALL_CONTEXTS,
) -> tuple[list[Edge], list[Unparseable]]:
    """Every re-frame require edge in one file's text, deduplicated.

    `contexts` exists for the self-test: withholding CONTEXT_REQUIRE must make
    the runtime-require fixture go green, which is what proves that half of the
    scan surface is load-bearing rather than decorative (trap 5).
    """
    masked = mask_source(text)
    spans: list[tuple[int, int, str]] = []
    if CONTEXT_NS in contexts:
        spans += [(a, b, CONTEXT_NS) for a, b in _spans_of(masked, _NS_FORM_RE)]
    if CONTEXT_REQUIRE in contexts:
        spans += [
            (a, b, CONTEXT_REQUIRE) for a, b in _spans_of(masked, _REQUIRE_FORM_RE)
        ]

    seen: dict[tuple[str, str, str], Edge] = {}
    unparseable: list[Unparseable] = []
    for span_start, span_end, context in spans:
        i = span_start
        while i < span_end:
            if masked[i] != "[":
                i += 1
                continue
            vec_end = balanced_end(masked, i)
            if vec_end == -1 or vec_end > span_end:
                i += 1
                continue
            edge, bad = _parse_libspec(
                rel_path, text, masked, i, vec_end, context,
            )
            if bad is not None:
                unparseable.append(bad)
            if edge is not None:
                # Dedup per (file, namespace, alias): a `.cljc` binding the
                # same alias in both reader arms is ONE edge.  Keep the first
                # occurrence so the reported line is the earliest.
                seen.setdefault((edge.path, edge.namespace, edge.alias), edge)
            i += 1
    return list(seen.values()), unparseable


def exempt_aliases(edges: Iterable[Edge]) -> set[str]:
    """Aliases bound to 2+ distinct namespaces in ONE file — host-conditional.

    Derived, never listed (see the module docstring).  `edges` is one file's.
    """
    by_alias: dict[str, set[str]] = defaultdict(set)
    for e in edges:
        by_alias[e.alias].add(e.namespace)
    return {alias for alias, namespaces in by_alias.items() if len(namespaces) > 1}


def violations_of(edges: list[Edge]) -> list[Edge]:
    """The non-canonical, non-exempt edges of ONE file."""
    exempt = exempt_aliases(edges)
    return [e for e in edges if not e.is_canonical and e.alias not in exempt]


# --------------------------------------------------------------------------
# Surfaces
# --------------------------------------------------------------------------

def surface_of(rel_path: str) -> str:
    """The baseline key for a repo-relative path.

    `implementation/` is split one level deeper because that is where the
    artefact boundary lives — and because a single `implementation` bucket is
    exactly the granularity at which machines hid between core and routing.
    """
    parts = rel_path.split("/")
    if parts[0] == "implementation" and len(parts) > 2:
        return "implementation/" + parts[1]
    return parts[0]


# --------------------------------------------------------------------------
# Baseline
# --------------------------------------------------------------------------

BASELINE_REL = "scripts/require-alias-baseline.edn"


class Baseline(NamedTuple):
    min_edges: int
    surfaces: dict[str, int]


class BaselineError(Exception):
    pass


def parse_baseline(text: str) -> Baseline:
    """Read the baseline EDN.

    Deliberately strict: anything this reader does not consume is an ERROR, not
    an ignored key.  A baseline half-read is a floor half-applied, and that
    fails in the direction that admits a regression.
    """
    masked = mask_source(text)
    start = masked.find("{")
    if start == -1:
        raise BaselineError("no map in the baseline file")
    end = balanced_end(masked, start)
    if end == -1:
        raise BaselineError("the baseline map does not balance")
    tokens = top_level_tokens(masked[start + 1:end - 1])
    if tokens is None or len(tokens) % 2 != 0:
        raise BaselineError("the baseline map is not a sequence of key/value pairs")

    min_edges: int | None = None
    surfaces: dict[str, int] | None = None
    body_offset = start + 1
    for k, v in zip(tokens[0::2], tokens[1::2]):
        if k.text == ":min-edges":
            if not v.text.isdigit():
                raise BaselineError(f":min-edges must be a non-negative integer, got {v.text!r}")
            min_edges = int(v.text)
        elif k.text == ":surfaces":
            if not v.text.startswith("{"):
                raise BaselineError(":surfaces must be a map")
            inner = top_level_tokens(v.text[1:-1])
            if inner is None or len(inner) % 2 != 0:
                raise BaselineError(":surfaces is not a sequence of key/value pairs")
            surfaces = {}
            for sk, sv in zip(inner[0::2], inner[1::2]):
                # The KEY comes from the unmasked source: masking blanks string
                # contents, so the name has to be read back from `text`.
                abs_off = body_offset + v.offset + 1 + sk.offset
                raw_key = text[abs_off:abs_off + len(sk.text)]
                if not (raw_key.startswith('"') and raw_key.endswith('"')):
                    raise BaselineError(f"surface keys must be strings, got {raw_key!r}")
                if not sv.text.isdigit():
                    raise BaselineError(
                        f"surface {raw_key} floor must be a non-negative integer, "
                        f"got {sv.text!r}"
                    )
                surfaces[raw_key[1:-1]] = int(sv.text)
        else:
            raise BaselineError(f"unrecognised baseline key {k.text!r}")

    if min_edges is None:
        raise BaselineError("the baseline names no :min-edges floor")
    if surfaces is None:
        raise BaselineError("the baseline names no :surfaces map")
    return Baseline(min_edges, surfaces)


def render_baseline(min_edges: int, surfaces: dict[str, int]) -> str:
    width = max((len(s) for s in surfaces), default=0) + 2
    lines = [
        ";; Require-alias dialect baseline (rf2-ydpr) — regenerate with",
        ";;     python scripts/check_require_alias_dialect.py --write-baseline",
        ";;",
        ";; `:surfaces` maps each surface to the number of NON-CANONICAL,",
        ";; non-exempt re-frame require edges it is allowed to carry. A surface",
        ";; at 0 can never regress; a surface above 0 ratchets DOWN as its",
        ";; migration bead lands, and the gate reports any surface now BELOW its",
        ";; floor so the number can be lowered. Every surface that contributes a",
        ";; .clj/.cljs/.cljc file must appear here even at 0 — an unnamed surface",
        ";; is an error, which is the anti-unowned rule that per-artefact",
        ";; ratchets did not have.",
        ";;",
        ";; `:min-edges` is a floor on the TOTAL re-frame require edges the scan",
        ";; observes. It is not a style rule: it is what stops the checker",
        ";; reporting a confident zero over a corpus it has stopped being able",
        ";; to read.",
        "{:min-edges " + str(min_edges),
        "",
        " :surfaces",
        " {",
    ]
    for name in sorted(surfaces):
        quoted = '"' + name + '"'
        lines.append("  " + quoted.ljust(width) + " " + str(surfaces[name]))
    lines.append(" }}")
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------------------
# Corpus roster
# --------------------------------------------------------------------------

def git_tracked_clojure_files(repo_root: Path) -> list[str]:
    """Every git-tracked `.clj`/`.cljs`/`.cljc` path, repo-relative."""
    proc = subprocess.run(
        ["git", "-C", str(repo_root), "ls-files", "-z", "*.clj", "*.cljs", "*.cljc"],
        capture_output=True, text=True, check=False,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"git ls-files failed: {proc.stderr.strip()}")
    return [
        p for p in proc.stdout.split("\0")
        if p and not p.startswith(EXCLUDED_PREFIXES)
    ]


class ScanResult(NamedTuple):
    files_read: int
    edges: list[Edge]
    violations: list[Edge]
    unparseable: list[Unparseable]

    @property
    def per_surface(self) -> dict[str, int]:
        counts: dict[str, int] = defaultdict(int)
        for v in self.violations:
            counts[v.surface] += 1
        return dict(counts)

    @property
    def surfaces_with_files(self) -> set[str]:
        return {surface_of(e.path) for e in self.edges}


def scan_paths(
    repo_root: Path, rel_paths: Iterable[str], contexts: Iterable[str] = ALL_CONTEXTS,
) -> ScanResult:
    edges: list[Edge] = []
    violations: list[Edge] = []
    unparseable: list[Unparseable] = []
    files_read = 0
    for rel in rel_paths:
        path = repo_root / rel
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        files_read += 1
        file_edges, file_bad = read_file(rel, text, contexts)
        edges.extend(file_edges)
        violations.extend(violations_of(file_edges))
        unparseable.extend(file_bad)
    return ScanResult(files_read, edges, violations, unparseable)


# --------------------------------------------------------------------------
# Usability floor — "recognises nothing" must not read as "found nothing"
# --------------------------------------------------------------------------

def assert_usable(
    result: ScanResult, roster: list[str], baseline: Baseline,
    surfaces_with_files: set[str],
) -> list[str]:
    """Reasons the run cannot be believed.  Non-empty means exit 2, never 0."""
    problems: list[str] = []
    if not roster:
        problems.append(
            "the git-tracked .clj/.cljs/.cljc roster is EMPTY — this is not a "
            "clean tree, it is a scanner that cannot see the corpus."
        )
    if result.files_read == 0:
        problems.append("no file was read; the scan covered nothing.")
    total = len(result.edges)
    if total < baseline.min_edges:
        problems.append(
            f"only {total} re-frame require edge(s) observed, below the "
            f"baseline floor of {baseline.min_edges}. A clean tree does not "
            "lose require edges; a blind scanner does. Investigate the "
            "scanner before lowering this number."
        )
    missing = sorted(set(baseline.surfaces) - surfaces_with_files)
    if missing:
        problems.append(
            "baseline surface(s) with no scanned re-frame require edge — the "
            "baseline is stale, or the roster is truncated: "
            + ", ".join(missing)
        )
    unowned = sorted(surfaces_with_files - set(baseline.surfaces))
    if unowned:
        problems.append(
            "surface(s) carrying re-frame require edges that the baseline does "
            "NOT name: " + ", ".join(unowned)
            + ". Add each to " + BASELINE_REL + " (--write-baseline). An "
            "unnamed surface is exactly how machines went unowned between the "
            "core and routing sweeps."
        )
    if result.unparseable:
        problems.append(
            f"{len(result.unparseable)} re-frame libspec(s) in a shape this "
            "checker does not recognise — reported rather than skipped, "
            "because a skipped libspec is a bare alias the gate waves through."
        )
    return problems


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINT = (
    "spec/Conventions.md §Require-alias dialect: `re-frame.core` takes the bare "
    "root alias `rf`; every other `re-frame.<tail>` takes the full dotted "
    "`rf.<tail>`. The bare leaf form is reserved for APPLICATION namespaces. "
    "Rename the alias AND every qualified use site that moves with it — "
    "including auto-resolved `::alias/key` keywords, which denote the aliased "
    "namespace — but NOT literal `:alias/key` keywords, which are values whose "
    "first segment merely collides with the alias."
)


def report_violations(result: ScanResult, baseline: Baseline) -> None:
    counts = result.per_surface
    over = {
        s: (n, baseline.surfaces.get(s, 0))
        for s, n in counts.items()
        if n > baseline.surfaces.get(s, 0)
    }
    sys.stderr.write(
        f"\n{sum(n for n, _ in over.values())} non-canonical require alias(es) "
        f"above the per-surface floor in {len(over)} surface(s) "
        "(spec/Conventions.md §Require-alias dialect):\n\n"
    )
    for surface in sorted(over):
        found, allowed = over[surface]
        sys.stderr.write(f"  {surface}: {found} violation(s), floor {allowed}\n")
        for e in sorted(
            (v for v in result.violations if v.surface == surface),
            key=lambda v: (v.path, v.line),
        ):
            sys.stderr.write(
                f"      {e.path}:{e.line}  [{e.namespace} :as {e.alias}]"
                f"  ->  :as {e.canonical}"
                + ("  (runtime require)" if e.context == CONTEXT_REQUIRE else "")
                + "\n"
            )
    sys.stderr.write(f"\nFix:\n  * {_FIX_HINT}\n")


def report_unusable(problems: list[str], result: ScanResult) -> None:
    sys.stderr.write(
        "\nerror: this run CANNOT be believed — recognising nothing is not the "
        "same outcome as finding nothing:\n\n"
    )
    for p in problems:
        sys.stderr.write(f"  * {p}\n")
    for bad in result.unparseable[:20]:
        sys.stderr.write(
            f"      {bad.path}:{bad.line}: {bad.reason}\n"
            f"        {' '.join(bad.text.split())[:160]}\n"
        )
    sys.stderr.write("\n")


def report_census(result: ScanResult, baseline: Baseline) -> None:
    counts = result.per_surface
    surfaces = sorted(set(counts) | set(baseline.surfaces))
    width = max((len(s) for s in surfaces), default=0)
    sys.stdout.write(
        f"{result.files_read} file(s), {len(result.edges)} re-frame require "
        f"edge(s), {len(result.violations)} non-canonical.\n\n"
    )
    sys.stdout.write(f"  {'surface'.ljust(width)}  found  floor\n")
    for s in surfaces:
        found = counts.get(s, 0)
        floor = baseline.surfaces.get(s, 0)
        mark = "  OVER" if found > floor else ("  under" if found < floor else "")
        sys.stdout.write(f"  {s.ljust(width)}  {found:5d}  {floor:5d}{mark}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def _resolve_repo_root(arg: str | None) -> Path:
    return Path(arg).resolve() if arg else Path(__file__).resolve().parent.parent


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "spec/Conventions.md §Require-alias dialect: fail when a surface "
            "carries more non-canonical re-frame require aliases than its "
            "baseline floor allows."
        ),
    )
    parser.add_argument("--repo-root", default=None)
    parser.add_argument(
        "--scan-path", default=None,
        help="Scan one repo-relative file or directory instead of the corpus "
             "(census only; the per-surface floor is not applied).",
    )
    parser.add_argument("--report", action="store_true", help="Per-surface census.")
    parser.add_argument(
        "--write-baseline", action="store_true",
        help=f"Regenerate {BASELINE_REL} from the current tree.",
    )
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_tests(_resolve_repo_root(args.repo_root), verbose=args.verbose)

    repo_root = _resolve_repo_root(args.repo_root)
    if not (repo_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"error: {repo_root} does not look like the re-frame2 repo root "
            "(no mkdocs.yml). Pass --repo-root explicitly.\n"
        )
        return 2

    try:
        roster = git_tracked_clojure_files(repo_root)
    except RuntimeError as exc:
        sys.stderr.write(f"error: {exc}\n")
        return 2

    if args.scan_path:
        target = repo_root / args.scan_path
        if not target.exists():
            sys.stderr.write(f"error: scan path {target} does not exist.\n")
            return 2
        if target.is_file():
            selected = [args.scan_path.replace("\\", "/")]
        else:
            prefix = args.scan_path.replace("\\", "/").rstrip("/") + "/"
            selected = [p for p in roster if p.startswith(prefix)]
            if not selected:
                sys.stderr.write(
                    f"error: no tracked Clojure file under {args.scan_path} — "
                    "an empty selection is not a clean scan.\n"
                )
                return 2
        result = scan_paths(repo_root, selected)
        for e in sorted(result.violations, key=lambda v: (v.path, v.line)):
            sys.stdout.write(
                f"{e.path}:{e.line}  [{e.namespace} :as {e.alias}] -> "
                f":as {e.canonical}\n"
            )
        for bad in result.unparseable:
            sys.stdout.write(f"{bad.path}:{bad.line}  UNPARSEABLE: {bad.reason}\n")
        sys.stdout.write(
            f"{result.files_read} file(s), {len(result.edges)} edge(s), "
            f"{len(result.violations)} non-canonical, "
            f"{len(result.unparseable)} unparseable.\n"
        )
        return 2 if result.unparseable else (1 if result.violations else 0)

    result = scan_paths(repo_root, roster)
    surfaces_with_files = {surface_of(p) for p in roster}

    if args.write_baseline:
        counts = result.per_surface
        surfaces = {s: counts.get(s, 0) for s in sorted(surfaces_with_files)}
        # Round the observed total DOWN to a stable floor: the point is to
        # catch a scanner that has gone blind, not to red the gate the next
        # time a namespace is deleted.
        floor = int(len(result.edges) * 0.9)
        (repo_root / BASELINE_REL).write_text(
            render_baseline(floor, surfaces), encoding="utf-8", newline="\n",
        )
        sys.stdout.write(
            f"wrote {BASELINE_REL}: {len(surfaces)} surface(s), "
            f":min-edges {floor} (from {len(result.edges)} observed edges).\n"
        )
        return 0

    try:
        baseline = parse_baseline(
            (repo_root / BASELINE_REL).read_text(encoding="utf-8")
        )
    except (OSError, BaselineError) as exc:
        sys.stderr.write(f"error: {BASELINE_REL}: {exc}\n")
        return 2

    problems = assert_usable(result, roster, baseline, surfaces_with_files)
    if problems:
        report_unusable(problems, result)
        return 2

    if args.report:
        report_census(result, baseline)
        return 0

    counts = result.per_surface
    if any(n > baseline.surfaces.get(s, 0) for s, n in counts.items()):
        report_violations(result, baseline)
        return 1

    below = {
        s: (counts.get(s, 0), f) for s, f in baseline.surfaces.items()
        if counts.get(s, 0) < f
    }
    if below:
        sys.stderr.write(
            f"\n{len(below)} surface(s) now BELOW their baseline floor — lower "
            f"the floor in {BASELINE_REL} (--write-baseline) so the ratchet "
            "holds the ground that was won:\n"
        )
        for s in sorted(below):
            found, floor = below[s]
            sys.stderr.write(f"  {s}: {found} violation(s), floor {floor}\n")
        return 1

    if args.verbose:
        sys.stderr.write(
            f"require-alias dialect clean: {result.files_read} file(s), "
            f"{len(result.edges)} re-frame require edge(s), "
            f"{len(result.violations)} non-canonical, every surface at or "
            "under its floor.\n"
        )
    return 0


# --------------------------------------------------------------------------
# Self-tests
# --------------------------------------------------------------------------
#
# A CHECKER WHOSE SUBJECT IS ABSENCE IS GREEN WHEN IT STOPS FIRING.  That is
# the defect class this repo keeps finding in its own instruments, and the
# reason lint.yml already carries a fixture-witness step for hicasso's kondo
# export.  So every rule here is exercised in BOTH directions against real
# files on disk under `scripts/_test_fixtures/check_require_alias_dialect/`:
# each `positive/` fixture must be NAMED, each `negative/` fixture must be
# silent, and the two controls below prove the two halves of the scan surface
# that a plausible-looking implementation would have omitted.

FIXTURE_REL = "scripts/_test_fixtures/check_require_alias_dialect"

# fixture -> (expected violating aliases, in file order)
POSITIVE_FIXTURES: dict[str, tuple[str, ...]] = {
    "ns_form_bare_alias.cljc": ("machines", "routing"),
    "multiline_libspec.cljc": ("result",),
    "runtime_require.cljc": ("directory",),
    "reader_conditional_splice.cljc": ("flows",),
    "as_alias.cljc": ("schemas",),
    "wrong_dotted_alias.cljc": ("rf.core", "rf.mach"),
}

NEGATIVE_FIXTURES: tuple[str, ...] = (
    "canonical_aliases.cljc",
    "host_conditional_exemption.cljc",
    "use_site_lookalikes.cljc",
    "masked_require_shapes.cljc",
)


def _fixture_text(repo_root: Path, kind: str, name: str) -> tuple[str, str]:
    rel = f"{FIXTURE_REL}/{kind}/{name}"
    return rel, (repo_root / rel).read_text(encoding="utf-8")


def run_self_tests(repo_root: Path, verbose: bool = False) -> int:
    failures: list[str] = []
    passes = 0

    def expect(label: str, ok: bool, detail: str = "") -> None:
        nonlocal passes
        if ok:
            passes += 1
            if verbose:
                sys.stderr.write(f"self-test PASS: {label}\n")
        else:
            failures.append(f"{label}{(' — ' + detail) if detail else ''}")
            sys.stderr.write(f"self-test FAIL: {label} {detail}\n")

    # ---- positive fixtures: the gate must NAME each planted bare alias -----
    for name, expected in POSITIVE_FIXTURES.items():
        try:
            rel, text = _fixture_text(repo_root, "positive", name)
        except OSError:
            expect(f"positive/{name} present", False, "fixture missing")
            continue
        edges, bad = read_file(rel, text)
        got = tuple(v.alias for v in sorted(violations_of(edges), key=lambda e: e.line))
        expect(f"positive/{name} FIRES on {expected}", got == expected, f"got {got}")
        expect(f"positive/{name} parses cleanly", not bad, f"{bad}")

    # ---- negative fixtures: the gate must stay silent ----------------------
    for name in NEGATIVE_FIXTURES:
        try:
            rel, text = _fixture_text(repo_root, "negative", name)
        except OSError:
            expect(f"negative/{name} present", False, "fixture missing")
            continue
        edges, bad = read_file(rel, text)
        vs = violations_of(edges)
        expect(f"negative/{name} is GREEN", not vs,
               f"{[(v.path, v.line, v.alias) for v in vs]}")
        expect(f"negative/{name} parses cleanly", not bad, f"{bad}")

    # ---- control 1: the RUNTIME REQUIRE context is load-bearing ------------
    # The core sweep's residue after 312 files was a top-level
    # `(require '[re-frame.late-bind.directory :as directory])` that clj-kondo
    # cannot report.  A ratchet built on the ns form alone reads this fixture
    # GREEN, which is the miss this control exists to make visible.
    rel, text = _fixture_text(repo_root, "positive", "runtime_require.cljc")
    ns_only_edges, _ = read_file(rel, text, contexts=(CONTEXT_NS,))
    expect(
        "control: runtime_require.cljc goes GREEN when the runtime context is "
        "withheld (so scanning ns forms alone would reproduce the core miss)",
        not violations_of(ns_only_edges),
        f"{[v.alias for v in violations_of(ns_only_edges)]}",
    )
    both_edges, _ = read_file(rel, text)
    expect(
        "control: and RED with it",
        [v.alias for v in violations_of(both_edges)] == ["directory"],
    )

    # ---- control 2: the exemption predicate is derived, both ways ----------
    rel, text = _fixture_text(repo_root, "negative", "host_conditional_exemption.cljc")
    edges, _ = read_file(rel, text)
    expect("control: the host-conditional alias IS exempt",
           exempt_aliases(edges) == {"substrate"}, f"{exempt_aliases(edges)}")
    # Collapse the two reader arms onto ONE namespace and the same file must
    # FIRE: the exemption is the two-namespace binding, not the file.
    collapsed = text.replace(
        "re-frame.adapter.reagent :as substrate",
        "re-frame.substrate.plain-atom :as substrate",
    )
    expect("control: and the SAME file fires once the two arms name one namespace",
           [v.alias for v in violations_of(read_file(rel, collapsed)[0])] == ["substrate"])

    # ---- control 3: masking, in both directions ---------------------------
    live = "(require '[re-frame.machines :as machines])"
    expect("control: a live runtime require fires",
           [v.alias for v in violations_of(read_file("x.cljc", live)[0])] == ["machines"])
    expect("control: the same text inside a string is invisible",
           not violations_of(read_file("x.cljc", '(def s "%s")' % live)[0]))
    expect("control: the same text inside a comment is invisible",
           not violations_of(read_file("x.cljc", ";; " + live)[0]))
    expect("control: a `\\;` char literal does not open a comment",
           [v.alias for v in violations_of(
               read_file("x.cljc", "(def c \\;)\n" + live)[0])] == ["machines"])
    expect("control: a `\\\"` char literal does not open a string",
           [v.alias for v in violations_of(
               read_file("x.cljc", "(def q \\\")\n" + live)[0])] == ["machines"])

    # ---- control 4: unrecognised shapes are REPORTED, never skipped -------
    prefix_list = "(ns x (:require [re-frame.machines [result :as result]]))"
    _, bad = read_file("x.cljc", prefix_list)
    expect("control: a prefix-list libspec is reported unparseable", len(bad) == 1,
           f"{bad}")

    # ---- control 5: the baseline reader refuses what it cannot read -------
    ok = parse_baseline('{:min-edges 10 :surfaces {"tools" 3 "bench" 0}}')
    expect("control: a well-formed baseline reads back",
           ok == Baseline(10, {"tools": 3, "bench": 0}), f"{ok}")
    for bad_edn, why in (
        ('{:surfaces {"tools" 3}}', "no :min-edges"),
        ('{:min-edges 10}', "no :surfaces"),
        ('{:min-edges 10 :surfaces {"tools" 3} :extra 1}', "unrecognised key"),
        ('{:min-edges 10 :surfaces {tools 3}}', "non-string surface key"),
        ('{:min-edges "ten" :surfaces {"tools" 3}}', "non-integer floor"),
    ):
        try:
            parse_baseline(bad_edn)
            expect(f"control: baseline REFUSES {why}", False, "accepted it")
        except BaselineError:
            expect(f"control: baseline REFUSES {why}", True)

    # ---- control 6: the usability floor fires on a blind scan -------------
    empty = ScanResult(0, [], [], [])
    problems = assert_usable(empty, [], Baseline(100, {"tools": 0}), set())
    expect("control: an empty roster is UNUSABLE, not clean", len(problems) >= 3,
           f"{problems}")
    live_edge = Edge("tools/x.cljc", 1, "re-frame.core", "rf", CONTEXT_NS)
    unowned = ScanResult(1, [live_edge], [], [])
    expect("control: a surface the baseline does not name is UNUSABLE",
           any("does NOT name" in p for p in
               assert_usable(unowned, ["tools/x.cljc"], Baseline(0, {}), {"tools"})))
    expect("control: a fully-owned scan over the floor is usable",
           assert_usable(unowned, ["tools/x.cljc"], Baseline(1, {"tools": 0}),
                         {"tools"}) == [])

    # ---- control 7: surface_of, and the granularity that matters ----------
    expect("control: implementation/ splits one level deeper",
           surface_of("implementation/machines/src/re_frame/machines.cljc")
           == "implementation/machines"
           and surface_of("tools/xray/src/a.cljs") == "tools")

    sys.stderr.write(
        f"\nself-test: {passes} passed, {len(failures)} failed.\n"
    )
    if failures:
        for f in failures:
            sys.stderr.write(f"  FAIL {f}\n")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
