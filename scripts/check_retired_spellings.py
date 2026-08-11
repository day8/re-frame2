#!/usr/bin/env python3
"""EP-0007 (One Name Per Fact) §Enforcement — retired-spelling CI gate.

EP-0007 rule 2 ("no stable accepted synonyms") gets the no-floor-lint
treatment "where shapes allow": a *retired* spelling reappearing in
repo source is a CI failure, not a doc note. Four renames have merged,
so four retired spellings are now lintable:

  (a) The bare `:frame` event-context COEFFECT (sweep item 1, rf2-1m6rf1).
      EP-0002 R3 "one carrier, one name" retired the duplicate `:frame`
      coeffect: the frame stamp travels in the event context under
      `:rf.frame/id` ONLY. The framework once injected `:frame` beside it
      and ~10 internal sites read it back.

  (b) The redirect-target keys `:url` / `:to` on an SSR redirect effect
      (sweep item 4, rf2-vngir). The `:rf.server/redirect` /
      `:rf.server/safe-redirect` fx writes an HTTP `Location` response
      header, so it uses header vocabulary — the canonical (and only)
      target key is `:location`. `:url` / `:to` are retired and now throw
      `:rf.error/redirect-retired-target-key`.

  (c) Route metadata `:query-retain` (EP-0037 R5, rf2-jlmgt). A destination
      address is taken LITERALLY: the router no longer folds ambient
      current-route query state into a route the caller authored. The key is
      retired with NO alias — `reg-route` rejects it as an unknown bare key —
      and it no longer widens the query-key promotion vocabulary. Carrying
      query state across routes is application policy spelled as a pure
      function over the destination address.

  (d) A hicasso BENCH-TREE coordinate — `front.<ns>/<name>` or
      `arm1.<ns>/<name>` (rf2-hic-007, rf2-r4jy). The shipped
      `implementation/hicasso` package was measured as the prototype
      `re-frame.bench.hicasso.{front,arm1}.*` and carries that tree's
      provenance all through its prose, but the prototype is NOT in this
      repo and no consumer can follow a coordinate into it. rf2-hic-007
      moved 42 `:where` coordinates onto the package's own
      `re-frame.hicasso.impl.*` namespaces; a coordinate naming `front.*`
      or `arm1.*` is retired.

WHY THE SHAPES ARE SCOPED PRECISELY (the "where shapes allow" caveat)

`:frame` and `:url` are both heavily SANCTIONED elsewhere — a bare grep for
either keyword fires on 100+ legitimate sites. EP-0007 rule 3 records the
cross-layer vocabulary rules that make those uses correct:

  * `:frame` is the public dispatch/subscribe opt, the dispatch envelope
    key, the binary fx-handler ctx key (Spec 002 §The binary fx-handler
    signature) + the HTTP-interceptor ctx key (Spec 014), and the
    trace / error-record tag. ALL sanctioned. Only the *coeffect read* was
    retired.

  * `:url` is the canonical CLIENT-navigation key (routing / `navigate`
    surfaces) per the HTTP-response-vs-navigation vocabulary rule in
    spec/Conventions.md §The naming rules. Only its use as an SSR
    redirect-TARGET key was retired.

So this gate does NOT grep for the bare keyword. It scopes to the exact
retired SHAPES:

  (a) the coeffect-READ forms the rf2-1m6rf1 rename removed —
        - (get-coeffect <ctx> :frame ...)        ;; the canonical accessor
        - (:frame (:coeffects <x>))              ;; keyword-fn off :coeffects
        - (get-in <ctx> [:coeffects :frame] ...) ;; get-in path via :coeffects
      None of these can match the public opt, the trace tag, the dispatch
      envelope, or the fx-handler `{:keys [frame]}` destructuring (which is
      an fx-CONTEXT read, sanctioned). The framework reads coeffects through
      these three forms and nothing else (verified against the rename diff).

  (b) a `:url` / `:to` KEY appearing in a map that is — in the same form —
      tagged as an SSR redirect: a literal `{... :url ...}` (or `:to`) whose
      enclosing form also names `:rf.server/redirect` or
      `:rf.server/safe-redirect`. This catches the
      `(dispatch [... [:rf.server/redirect {:url "/x"}]])` /
      `{:fx [[:rf.server/redirect {:to "/x"}]]}` reintroduction without
      firing on `(navigate {:url "/x"})` client-nav, where no
      `:rf.server/*redirect` tag is present.

  (c) `:query-retain` as a Clojure KEYWORD TOKEN — the keyword delimited on
      both sides (start-of-line or one of `(`/`[`/`{`/whitespace/`,` before;
      a closing delimiter, whitespace or end-of-line after). Unlike (a) and
      (b) the retired key has NO sanctioned code use whatsoever, so the three
      USE shapes EP-0037 row 9 enumerates all reduce to that one token shape:

        - a `reg-route` metadata map    `{:query-retain #{:theme :locale}}`
        - the accepted-key roster       `#{... :query-defaults :query-retain}`
        - the promotion vocabulary      `[:query :query-defaults :query-retain]`

      What the token boundary buys is prose safety. Every legitimate in-tree
      mention NAMES the key as retired, and every one of them spells it as
      backticked prose — `` `:query-retain` `` — so the opening backtick
      denies the token-start boundary and the rule cannot fire on a retirement
      note even where comment/string masking does not reach. The mentions:
      `implementation/routing/src/re_frame/routing/{classification,navigate,
      registry}.cljc` (comments + one docstring, masked as well as
      backticked), `skills/re-frame2/references/tooling/routing.md`,
      `spec/Spec-Schemas.md`, and
      `spec/conformance/fixtures/routing-query-keyword-discipline.edn`. The
      last three carry a suffix this gate never opens (`.md`, `.edn`) — the
      `skills/` one sits inside a scanned TREE, so it is the suffix filter and
      not the roster that keeps it out. The boundary is what keeps the rule
      correct if the suffix filter is ever widened, and the self-test pins all
      five verbatim.

  (d) TWO shapes, and the second one is the whole reason the rule exists:

        - the SYMBOL   `'front.codec/root-element`, `arm1.mount/render!`
        - the STRING   `"front.codec/"`

      A `:where` is a symbol, so a symbol-shaped check is the obvious rule and
      it is the one that misses. rf2-hic-007 moved the 42 coordinates, and CI
      went red on `test_kit_runtime_parity_cljs_test`, whose row asserted
      `(str/starts-with? (str (:where …)) "front.codec/")` — a GREEN test
      holding a shipped refusal to a benchmark coordinate no consumer could
      follow. It was a string; every `'front.` scan was blind to it, including
      the two the sweep's own verification used. It surfaced only in CI, from
      an assertion, in a file the sweep's author had already edited.

      SYMBOL: the namespace segment carries a MANDATORY dot and the coordinate
      a mandatory `/` (member optional, so the bare-prefix string `front.codec/`
      is covered as a symbol too). Both are load-bearing prose defences:

        * The dot rejects `arm1/host_hatch_dom_cljs_test` — the pervasive
          in-tree shorthand for a FILE in the prototype's `arm1` directory, not
          a namespace coordinate. ~20 comments spell it that way.
        * The `/` rejects the bare namespace mentions (`front.sub-index`,
          `front.dogfood`, `front.intent`), which name a retired MODULE in
          provenance prose and resolve to nothing executable.
        * Token start denies a preceding `.`, so the honest fully-qualified
          `re-frame.bench.hicasso.front.slot-cljs-test` stays green: that
          spelling names the prototype tree truthfully and is not a coordinate
          anyone could mistake for a shipped one.
        * Token start denies a preceding BACKTICK **in Markdown only**, so
          `` `front.codec/realize-deep` `` in prose cannot fire on a surface
          where masking does not reach. In Clojure the backtick is not a prose
          device at all — it is the SYNTAX-QUOTE reader macro, and
          ``(def x `front.codec/realize-deep)`` is live code. Denying it there
          bought nothing (a backticked mention in `.clj*` is inside a `;`
          comment or a string, both already masked before this pattern runs)
          and cost a hole exactly the size of a syntax-quoted coordinate, which
          is why the boundary is SOURCE-KIND AWARE: `_COORD_SYMBOL_START_CLJ`
          admits the backtick, `_COORD_SYMBOL_START_MD` refuses it.

      STRING: a double-quoted literal whose ENTIRE content is such a
      coordinate — the opening quote is immediately followed by `front.`/
      `arm1.`, and the literal closes with no whitespace in between. That is
      exactly the `str/starts-with?` / `=` comparison shape, and it is what
      keeps the rule off the two shipped refusal MESSAGES that name the
      prototype in backticked prose (`impl/collector.cljs:1738` and `:1790`) —
      those literals are sentences, so they carry spaces and cannot match.

      Both delimiters must be REAL — a `"` preceded by a backslash is an
      escaped quote INSIDE a larger literal, not a boundary of one. Without
      that constraint the pattern read `\"front.codec/\"` in
      `(def msg "the old assertion used \"front.codec/\" here")` as a whole
      literal and reported quoted PROSE about the retirement as a
      reintroduction of it — the mirror image of the miss above, and the more
      annoying failure of the two, since it reds a correct edit.

WHERE A SHAPE IS TOO AMBIGUOUS TO LINT WITHOUT NOISE (documented, not shipped)

EP-0007 §Enforcement says "where shapes allow"; two shapes are deliberately
NOT linted because they cannot be distinguished statically from sanctioned
code without unacceptable false-positive noise:

  * A redirect args map bound to a NAME far from the fx id, e.g.
        (let [r {:url "/x"}] (dispatch [... [:rf.server/redirect r]]))
    The `:url` key and the `:rf.server/redirect` tag are in different forms;
    binding them requires dataflow analysis a regex gate can't do. The
    RUNTIME guard (`reject-retired-redirect-keys!` in
    re-frame.ssr.response) is the real backstop here — it throws on the
    retired key regardless of how the map was constructed. This lint is a
    fast static defence-in-depth for the common inline-literal shape, not a
    replacement for the runtime throw.

  * The bare `:frame` keyword as a coeffect read via an unusual accessor
    (e.g. a user `(get (:coeffects ctx) :frame)`). The three canonical
    forms above are what the framework uses; an exotic accessor is rare,
    framework-author-only, and would need whole-form dataflow to bind the
    map to `:coeffects`. Not worth the false-positive surface; the
    `event_context_coeffect_keys_test` conformance test pins the exact
    framework-injected coeffect key set so a `:frame` coeffect cannot ride
    back IN even if a read of it slipped past this gate.

  * A retired bench coordinate inside a `;` COMMENT. Rule (d) masks comments,
    which is the same treatment rules (a)-(c) get and is decided on evidence
    rather than convention. rf2-r4jy proposed allowlisting the one line the
    audit had found — `impl/state.cljc:104`, the section header
    `;; Errors — the lane's shape (front.presence/fail!)`, prose provenance
    kept verbatim by the freeze manifest and carried by no refusal. Scanning
    the real surface with comments UNMASKED finds THREE such lines, not one:
    that header, plus `impl/presence_react.cljs:64` and `:121`, both
    `[[front.presence/step]]` / `[[front.presence/settle]]` wiki-links whose
    `[` grants token start exactly as the header's `(` does. So the allowlist
    was never one line; it was three, and it would grow with every future
    provenance comment — an allowlist that a correct edit keeps having to
    extend is a maintenance tax that teaches people to extend it.

    The cost is real and worth stating: masking blinds rule (d) to a comment
    that LIES about where a refusal is raised. That is a documentation defect,
    and this gate is not the thing that catches documentation defects — the
    failure it exists to close was an EXECUTABLE assertion holding a shipped
    refusal to a dead coordinate, which is a different and worse animal. A
    stale comment misleads a reader; a stale assertion turns green and reds
    someone else's PR. Note the asymmetry inside rule (d): comments are masked
    but STRING LITERALS ARE NOT, because the string shape is the whole point.

SCAN SURFACE

Every Clojure source tree in the repo — `.clj` / `.cljc` / `.cljs` under
`implementation/`, `examples/`, `tools/`, `skills/`, `testbeds/`, `migration/`
and `docs/tools/`. See `DEFAULT_SCAN_DIRS` for the roster, the two trees
deliberately left off it, and why `implementation/` alone was not enough
(rf2-kqxe6.25). The default excludes `test/` trees: tests legitimately ASSERT
the retired spelling is gone (the `event_context_coeffect_keys_test` checks
`(not (contains? cofx :frame))`, and the SSR end-to-end test feeds `:url` /
`:to` to assert the throw). A test fixture deliberately exercising a retired
spelling is correct, not drift. Pass `--include-tests` to scan them too
(used by the self-test fixtures, which live under a `test`-named dir).

Rule (d) has its OWN surface (`COORD_SCAN_PATHS`) and its own suffix filter,
and both differences are load-bearing:

  * It scans `implementation/hicasso/test/**` — with no `--include-tests` opt,
    unconditionally. The demonstrated regression WAS a test assertion, so a
    coordinate rule that skipped test trees would be a rule that skips the only
    place the failure has ever occurred. The other rules' reason for excluding
    tests does not transfer: a hicasso test has no reason to assert that a
    refusal still names the prototype, which is precisely what rf2-hic-007
    found one doing.
  * It adds `.md`, for the single file `spec/009-Instrumentation.md` — the spec
    that owns the `:where` coordinate contract, where a worked example naming a
    prototype coordinate would teach the retired spelling. Markdown gets no
    comment/string masking (it has neither), so on `.md` the backtick token
    boundary is the entire prose defence, which is why rule (d)'s symbol
    pattern denies a preceding backtick everywhere rather than only there.
  * It does NOT scan the whole repo. `docs/design/hicasso/**` is a working
    design record of the prototype and names `front.*` / `arm1.*` throughout
    on purpose; so does `implementation/hicasso`'s own freeze manifest. The
    subject here is a coordinate a SHIPPED refusal can carry, and that is the
    package's source, its tests, its test-kit and its spec.

Exit code:
    0  no retired spelling in any scanned source tree
    1  at least one retired spelling (results printed file:line)
    2  invocation / setup error

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from typing import Iterable, NamedTuple

# --------------------------------------------------------------------------
# Scan surface
# --------------------------------------------------------------------------

# EVERY Clojure source tree in the repo, minus the two documented exclusions
# below. rf2-kqxe6.25: the surface used to be `implementation/` alone, on the
# reasoning that `tools/` is bundle-isolated dev tooling and `examples/` is
# consumer-shaped, so only framework source needed the ratchet. That reasoning
# does not survive EP-0037 R5, which retired `:query-retain` with NO alias and
# then had rf2-kqxe6.12 / .13 migrate `examples/` and `skills/` off it. A
# reintroduction in the trees a reader COPIES FROM is the likeliest
# reintroduction there is, and it was the one the ratchet could not see: the
# rule fired correctly on a planted `examples/` occurrence the moment it was
# pointed at it, so only the invocation was scoped.
#
# The roster covers every top-level directory that carries a tracked
# `.clj`/`.cljc`/`.cljs` file today, so no tree is unratcheted. Verify with:
#
#   git ls-files | grep -E '\.clj[cs]?$' | sed -E 's#/.*##' | sort -u
#
# Two trees on that list are deliberately absent, and both would break the gate
# rather than merely widen it:
#
#   * `scripts/` — `scripts/_test_fixtures/` is where this gate's own POSITIVE
#     self-test fixtures live. They plant the retired spellings on purpose; a
#     live scan over them would be red by construction, forever.
#   * `docs/` — mkdocs stages `spec/` and `migration/` into `docs/spec/` and
#     `docs/migration/` at build time (see `.gitignore`), so a checkout that has
#     run `mkdocs build` carries GENERATED copies of trees scanned here already.
#     Scanning `docs/` would double-report a real finding and could report a
#     stale one from a copy predating the fix. `docs/tools` — the playground SCI
#     source, the only tracked Clojure under `docs/` — is rostered directly, so
#     nothing is lost.
#
# `spec/` carries no Clojure source at all (prose + EDN fixtures), and this
# gate's suffix filter is source-only, so it is not a candidate: the retirement
# NOTES there are held by the self-test's verbatim prose phase instead.
DEFAULT_SCAN_DIRS = (
    "implementation",
    "examples",
    "tools",
    "skills",
    "testbeds",
    "migration",
    "docs/tools",
)

_SOURCE_SUFFIXES = (".clj", ".cljc", ".cljs")

# Rule (d)'s surface — the four paths a shipped hicasso refusal's `:where` can
# reach: the package source, its tests (where rf2-hic-007's regression lived),
# its test-kit, and the spec that owns the coordinate contract. Deliberately NOT
# the whole repo; see SCAN SURFACE in the module docstring. Rostered paths are
# required to exist for the same reason `DEFAULT_SCAN_DIRS` are: a skipped tree
# reports success for a surface it never opened.
COORD_SCAN_PATHS = (
    "implementation/hicasso/src",
    "implementation/hicasso/test",
    "implementation/hicasso/test_kit",
    "spec/009-Instrumentation.md",
)

_COORD_SUFFIXES = _SOURCE_SUFFIXES + (".md",)

# Directory names whose contents are never scannable source for this gate.
_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules",
    "target",
    ".shadow-cljs",
    ".git",
    ".beads",
    ".cpcache",
})

# `test` / `tests` dirs are excluded by default (see module docstring): a test
# that ASSERTS a retired spelling is gone, or feeds one to assert the runtime
# throw, legitimately names it. `--include-tests` lifts the exclusion (the
# self-test fixtures rely on this so their `*_test`-named files are scanned).
_TEST_DIR_NAMES = frozenset({"test", "tests"})


# --------------------------------------------------------------------------
# Retired-shape patterns
# --------------------------------------------------------------------------

# (a) The retired `:frame` event-context COEFFECT read. Three forms, each of
#     which the rf2-1m6rf1 rename removed. `\b` after `:frame` forbids
#     matching `:frame/id` or `:frame-foo`; a `:rf.frame/id` read never
#     matches because the keyword there is `:rf.frame/id`, not `:frame`.

# 1. (get-coeffect <ctx> :frame)  — optionally namespace-qualified accessor
#    (interceptor/get-coeffect, rf/get-coeffect, bare get-coeffect).
_COEFFECT_GET_RE = re.compile(
    r"\(\s*(?:[\w.+!*?<>=/-]+/)?get-coeffect\s+[^()]+?:frame\b(?!/)"
)

# 2. (:frame (:coeffects <x>))    — keyword-fn read off the :coeffects map.
#    Whitespace-tolerant; the inner (:coeffects ...) is the load-bearing
#    signal that this is a coeffect read, not a public-opt / trace-tag use.
_COEFFECT_KWFN_RE = re.compile(
    r"\(\s*:frame\b(?!/)\s*\(\s*:coeffects\b"
)

# 3. (get-in <ctx> [:coeffects :frame] ...) — get-in path through :coeffects.
_COEFFECT_GETIN_RE = re.compile(
    r"\[\s*:coeffects\s+:frame\b(?!/)\s*\]"
)

_COEFFECT_PATTERNS = (
    ("get-coeffect-frame", _COEFFECT_GET_RE),
    (":frame-of-:coeffects", _COEFFECT_KWFN_RE),
    ("get-in-:coeffects-:frame", _COEFFECT_GETIN_RE),
)

# (b) The retired `:url` / `:to` redirect-TARGET key. Scoped to a form that
#     also names an SSR redirect fx id, so client-navigation `:url` (no
#     `:rf.server/*redirect` tag nearby) never matches. We detect, within a
#     window that mentions `:rf.server/redirect` or `:rf.server/safe-redirect`,
#     a `:url` or `:to` MAP KEY (a keyword immediately followed by whitespace
#     and a value — the map-entry shape, distinguishing a key from a bare
#     keyword used as a value).
_SSR_REDIRECT_FX_RE = re.compile(r":rf\.server/(?:safe-)?redirect\b")

# A retired redirect-target key in map-entry position: `:url <something>` or
# `:to <something>` where the keyword opens a map entry. The trailing
# `(?=\s)` + `\b` ensure `:url` not `:urls`/`:url/x`, and `:to` not
# `:token`/`:to/x`. Matched only inside an SSR-redirect window (see below).
_RETIRED_REDIRECT_KEY_RE = re.compile(r":(?:url|to)\b(?!/)\s")

# (c) The retired route-metadata key `:query-retain` (EP-0037 R5). Matched as a
#     delimited Clojure KEYWORD TOKEN, which is what every USE shape has in
#     common — a `reg-route` metadata-map key, a member of the accepted-key
#     roster, a member of the promotion vocabulary — and what no retirement
#     NOTE has, since those spell it as backticked prose. The trailing
#     lookahead also rejects `:query-retain/x` and `:query-retains`.
_RETIRED_QUERY_RETAIN_RE = re.compile(
    r"(?:^|(?<=[\s(\[{,]))"      # keyword-token start (a backtick denies it)
    r":query-retain"
    r"(?=[\s)\]},]|$)"           # keyword-token end
)


# (d) A retired hicasso bench-tree coordinate, in its TWO shapes.
#
# SYMBOL. `front.<ns>/<name>` or `arm1.<ns>/<name>` as a delimited Clojure
# symbol token. The namespace dot and the `/` are both mandatory (the member
# after `/` is not, so the bare prefix `front.codec/` matches). Each constraint
# keeps a specific class of in-tree provenance prose green — see (d) under
# "WHY THE SHAPES ARE SCOPED PRECISELY".
_COORD_SYMBOL_BODY = (
    r"(?:front|arm1)\.[\w.+!*?<>=-]+"      # namespace segment; dot MANDATORY
    r"/[\w.+!*?<>=-]*"                     # `/` mandatory, member optional
)

# Token start, and it is SOURCE-KIND AWARE in exactly one character. Both kinds
# deny a preceding `.` (the honest fully-qualified `re-frame.bench.hicasso.*`
# spelling stays green) and admit the reader macros that can legitimately
# precede a coordinate.
#
#   * Clojure ADMITS the backtick: there it is the syntax-quote reader macro,
#     so ``(def x `front.codec/realize-deep)`` is live code and must fire.
#     Backticked PROSE in `.clj*` lives in a `;` comment or a string literal,
#     both masked before this pattern runs, so admitting it costs nothing.
#   * Markdown DENIES it: on a surface with no masking at all, the backtick is
#     the entire prose defence — `` `front.codec/realize-deep` `` is the
#     spelling the fix hint tells authors to use.
_COORD_SYMBOL_START_CLJ = r"(?:^|(?<=[\s(\[{,'~@^`]))"
_COORD_SYMBOL_START_MD = r"(?:^|(?<=[\s(\[{,'~@^]))"

_RETIRED_COORD_SYMBOL_CLJ_RE = re.compile(
    _COORD_SYMBOL_START_CLJ + _COORD_SYMBOL_BODY
)
_RETIRED_COORD_SYMBOL_MD_RE = re.compile(
    _COORD_SYMBOL_START_MD + _COORD_SYMBOL_BODY
)

# STRING. A double-quoted literal whose ENTIRE content is such a coordinate:
# the opening quote is immediately followed by `front.`/`arm1.` (so a leading
# backtick denies it), and the literal closes with no whitespace anywhere in
# between (so a prose sentence naming the coordinate can never match). This is
# the `(str/starts-with? (str (:where …)) "front.codec/")` shape that went red
# in CI — the one a symbol-shaped check cannot see.
#
# BOTH delimiters must be REAL. `(?<!\\)` refuses a backslash-escaped quote,
# which is a character inside a larger literal and not a boundary of one, and
# the content class refuses a backslash for the same reason: a Clojure symbol
# contains neither. Together they keep the rule off quoted PROSE about the
# retirement — `(def msg "the old assertion used \"front.codec/\" here")` names
# the retired spelling in order to say it is retired.
_RETIRED_COORD_STRING_RE = re.compile(
    r'(?<!\\)"(?:front|arm1)\.[^"\s\\]*/[^"\s\\]*(?<!\\)"'
)


class Finding(NamedTuple):
    path: Path
    line: int
    kind: str
    snippet: str


# --------------------------------------------------------------------------
# Clojure-comment masking
# --------------------------------------------------------------------------
#
# The retired spellings appear extensively in PROSE: docstrings, `;;` line
# comments, and the rationale comments around the runtime guards (e.g. the
# `retired-redirect-target-keys` def + its docstring in
# re-frame.ssr.response). Those are documentation, not reintroduced code —
# the gate must not fire on them. We mask:
#
#   * `;`-to-end-of-line comments (length-preserving so column offsets and
#     the keyword `\b` boundaries are unchanged), AND
#   * the contents of "..." string literals (docstrings + prose strings),
#     again length-preserving.
#
# `#"..."` regex literals and `\"` escapes inside strings are handled by the
# simple state machine below. The `retired-redirect-target-keys` DEF — the
# vector literal `[:url :to]` that names the retired keys — is intentionally
# NOT a redirect-fx form (it carries no `:rf.server/*redirect` tag in the same
# window), so it does not match (b) regardless. And `[:url :to]` as bare
# keywords are not in map-entry position (no value follows), so the
# map-entry shape would not match them anyway.

_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_strings(line: str, in_string: bool) -> tuple[str, bool]:
    """Replace "..."-string-literal contents with spaces (length-preserving).

    Returns (masked-line, still-in-string?). Tracks multi-line strings via the
    carried `in_string` flag. Backslash-escaped quotes inside a string do not
    close it.
    """
    out = []
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                out.append("  ")
                i += 2
                continue
            if c == '"':
                in_string = False
                out.append('"')
                i += 1
                continue
            out.append(" ")
            i += 1
            continue
        if c == '"':
            in_string = True
            out.append('"')
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out), in_string


def _mask_comment(line: str) -> str:
    """Blank a `;`-to-EOL line comment, length-preserving.

    Applied AFTER string masking so a `;` inside a (now-blanked) string is not
    treated as a comment, and a `;` inside a real string is already gone.
    """
    m = _LINE_COMMENT_RE.search(line)
    if not m:
        return line
    start = m.start()
    return line[:start] + (" " * (len(line) - start))


def _masked_lines(text: str) -> list[str]:
    """Return per-line content with string-literals + `;` comments blanked.

    Length-preserving so 1-based line numbers and reported snippets line up
    with the source. Carries the in-string flag across newlines for multi-line
    docstrings.
    """
    masked: list[str] = []
    in_string = False
    for raw in text.splitlines():
        line, in_string = _mask_strings(raw, in_string)
        line = _mask_comment(line)
        masked.append(line)
    return masked


def _comment_masked_lines(text: str) -> list[str]:
    """Return per-line content with `;` comments blanked, STRINGS INTACT.

    Rule (d)'s string shape LIVES inside a string literal, so `_masked_lines`
    would blank the very thing it looks for. Comments still have to go: a
    retirement note may quote the retired string verbatim, and a note is not a
    reintroduction.

    String masking is still used, but only to LOCATE the comment — so a `;`
    inside a string literal does not open one — while the blanking is applied
    to the RAW line. Length-preserving, like its sibling.
    """
    out: list[str] = []
    in_string = False
    for raw in text.splitlines():
        probe, in_string = _mask_strings(raw, in_string)
        m = _LINE_COMMENT_RE.search(probe)
        out.append(
            raw[: m.start()] + (" " * (len(raw) - m.start())) if m else raw
        )
    return out


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def _iter_source_files(scan_root: Path, include_tests: bool) -> Iterable[Path]:
    """Yield scannable source files under scan_root.

    Excludes generated/vendor dirs always, and `test`/`tests` dirs unless
    `include_tests` is set.

    PRUNED, not filtered-after (rf2-76c76; method proven by rf2-e1xx0 in
    `check_retired_image_keys.py`). `_EXCLUDE_DIR_NAMES` is dropped from
    `os.walk`'s dirnames IN PLACE, so a built checkout never descends into
    `implementation/.shadow-cljs` (34.8k entries), `node_modules` (3.4k) or
    `target`. `rglob("*")` enumerated all 51.6k entries under
    `implementation/` and discarded them one at a time — 4.9s of this gate's
    8.1s wall clock on a built tree.

    The surviving sequence is IDENTICAL, set and order:
      * pruning drops only what the `_EXCLUDE_DIR_NAMES` test below already
        dropped — nothing under an excluded directory could survive either
        path, so the sets match;
      * the collected matches go through ONE GLOBAL `sorted()`, reproducing
        `sorted(rglob("*"))`'s whole-subtree ordering rather than os.walk's
        directory-grouped order.

    Note `_EXCLUDE_DIR_NAMES` here deliberately does NOT carry `out` (unlike
    the sibling gates) — adding it would narrow this gate's scope, which is a
    scope decision and not this change's business.
    """
    if scan_root.is_file():
        # Direct-file mode (used by --self-test fixtures pointing at one file).
        if scan_root.suffix in _SOURCE_SUFFIXES:
            yield scan_root
        return
    scan_prefix_len = len(scan_root.as_posix()) + 1
    matches: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(scan_root):
        dirnames[:] = [d for d in dirnames if d not in _EXCLUDE_DIR_NAMES]
        for name in filenames:
            if os.path.splitext(name)[1] in _SOURCE_SUFFIXES:
                matches.append(Path(dirpath) / name)
    for path in sorted(matches):
        # Kept as the belt to the pruning's braces, and now on string ops:
        # `Path.relative_to` was pure pathlib object churn for a prefix strip.
        parts = set(path.as_posix()[scan_prefix_len:].split("/"))
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        if not include_tests and (parts & _TEST_DIR_NAMES):
            continue
        yield path


def _iter_coordinate_files(scan_root: Path) -> Iterable[Path]:
    """Yield rule (d)'s scannable files under scan_root.

    Two deliberate differences from `_iter_source_files`, both argued in the
    module docstring's SCAN SURFACE section: `.md` is scannable (for
    `spec/009-Instrumentation.md`), and there is NO test-dir exclusion and no
    opt to reinstate one — the regression this rule exists to close was a test
    assertion.
    """
    if scan_root.is_file():
        if scan_root.suffix in _COORD_SUFFIXES:
            yield scan_root
        return
    matches: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(scan_root):
        dirnames[:] = [d for d in dirnames if d not in _EXCLUDE_DIR_NAMES]
        for name in filenames:
            if os.path.splitext(name)[1] in _COORD_SUFFIXES:
                matches.append(Path(dirpath) / name)
    yield from sorted(matches)


# --------------------------------------------------------------------------
# Per-file scan
# --------------------------------------------------------------------------


def _scan_text(path: Path, text: str) -> list[Finding]:
    """Return retired-spelling findings in `text` (already file-attributed).

    Pattern-matching runs over the MASKED lines (string-literals + `;`
    comments blanked) so prose never fires the gate, but the reported snippet
    is the RAW source line so the diagnostic shows the actual offending text.
    """
    findings: list[Finding] = []
    masked = _masked_lines(text)
    raw = text.splitlines()

    def raw_snippet(line_no: int) -> str:
        # line_no is 1-based; raw may be shorter than masked only if the file
        # ends without a trailing newline edge-case, so guard the index.
        return raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""

    # (a) Coeffect-read forms — line-local; none of the three spans lines.
    for line_no, line in enumerate(masked, start=1):
        for kind, pat in _COEFFECT_PATTERNS:
            if pat.search(line):
                findings.append(
                    Finding(path, line_no, f"retired-frame-coeffect:{kind}",
                            raw_snippet(line_no))
                )

    # (b) SSR-redirect retired target key. We work over a small sliding
    #     window of masked lines: a retired `:url` / `:to` map-entry key counts
    #     only when a `:rf.server/(safe-)redirect` tag appears within the same
    #     window. The window is the enclosing top-level-ish form approximated
    #     as +/- a few lines, which covers the realistic inline shapes
    #     (`[:rf.server/redirect {:url ...}]`, possibly wrapped across 2-3
    #     lines) without binding distant `let`-bound maps (documented
    #     ambiguity limit).
    _WINDOW = 3
    for line_no, line in enumerate(masked, start=1):
        if not _RETIRED_REDIRECT_KEY_RE.search(line):
            continue
        lo = max(0, line_no - 1 - _WINDOW)
        hi = min(len(masked), line_no + _WINDOW)
        window_text = "\n".join(masked[lo:hi])
        if _SSR_REDIRECT_FX_RE.search(window_text):
            findings.append(
                Finding(path, line_no, "retired-redirect-target-key",
                        raw_snippet(line_no))
            )

    # (c) The retired `:query-retain` route-metadata key — line-local, and no
    #     enclosing-form window is needed: the key has no sanctioned code use,
    #     so a delimited keyword token IS the violation wherever it appears.
    for line_no, line in enumerate(masked, start=1):
        if _RETIRED_QUERY_RETAIN_RE.search(line):
            findings.append(
                Finding(path, line_no, "retired-query-retain-key",
                        raw_snippet(line_no))
            )
    return findings


def scan(scan_root: Path, include_tests: bool = False) -> list[Finding]:
    """Scan source under scan_root for retired spellings."""
    findings: list[Finding] = []
    for path in _iter_source_files(scan_root, include_tests):
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text))
    return findings


def _scan_coordinates(path: Path, text: str) -> list[Finding]:
    """Return rule (d) findings — retired hicasso bench coordinates.

    Rule (d) is the one rule in this gate that reads INSIDE string literals, so
    it cannot share `_scan_text`'s single masked view. It takes two:

      * the SYMBOL shape runs over the fully masked lines (strings + comments
        blanked), the standard treatment — a coordinate spelled in prose is
        provenance, not a reintroduction;
      * the STRING shape runs over comment-masked lines with string literals
        INTACT, because the literal is the subject.

    Markdown gets neither: it has no Clojure comments and no Clojure strings,
    and applying the state machine to prose would let a stray `"` blank a real
    finding. On `.md` both patterns run raw, which is safe because the symbol
    pattern's token boundary already denies a preceding backtick and the string
    pattern already requires a whitespace-free whole literal.

    That backtick denial is also the one place the two surfaces need DIFFERENT
    patterns, and the difference follows from the masking above rather than
    from taste: in Clojure a backtick is the syntax-quote reader macro and
    prose is masked, so the coordinate must fire; in Markdown a backtick is
    prose and nothing is masked, so it must not.
    """
    findings: list[Finding] = []
    raw = text.splitlines()
    prose = path.suffix == ".md"
    symbol_lines = raw if prose else _masked_lines(text)
    string_lines = raw if prose else _comment_masked_lines(text)
    symbol_re = (
        _RETIRED_COORD_SYMBOL_MD_RE if prose else _RETIRED_COORD_SYMBOL_CLJ_RE
    )

    def raw_snippet(line_no: int) -> str:
        return raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""

    for line_no, line in enumerate(symbol_lines, start=1):
        if symbol_re.search(line):
            findings.append(
                Finding(path, line_no, "retired-bench-coordinate:symbol",
                        raw_snippet(line_no))
            )
    for line_no, line in enumerate(string_lines, start=1):
        if _RETIRED_COORD_STRING_RE.search(line):
            findings.append(
                Finding(path, line_no, "retired-bench-coordinate:string",
                        raw_snippet(line_no))
            )
    return findings


def scan_coordinates(scan_root: Path) -> list[Finding]:
    """Scan rule (d)'s surface under scan_root for retired bench coordinates."""
    findings: list[Finding] = []
    for path in _iter_coordinate_files(scan_root):
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_coordinates(path, text))
    return findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINTS = {
    "retired-frame-coeffect": (
        "The bare `:frame` event-context coeffect was retired by EP-0002 R3 / "
        "rf2-1m6rf1 (one carrier, one name). Read the frame stamp from the "
        "`:rf.frame/id` coeffect instead — e.g. "
        "`(interceptor/get-coeffect ctx :rf.frame/id)`. (The public `:frame` "
        "dispatch/subscribe opt, the dispatch envelope key, the binary "
        "fx-handler ctx `:frame`, and the trace `:frame` tag are all "
        "sanctioned and unaffected.)"
    ),
    "retired-redirect-target-key": (
        "The redirect-target keys `:url` / `:to` were retired by rf2-vngir / "
        "EP-0007. The SSR redirect fx writes an HTTP `Location` header, so it "
        "uses header vocabulary: the canonical (and only) redirect-target key "
        "is `:location`. Rewrite the `:rf.server/redirect` / "
        "`:rf.server/safe-redirect` arg as `{:location \"...\"}`. (`:url` is "
        "still the canonical key on CLIENT navigation surfaces — different "
        "concept, different word, per spec/Conventions.md §The naming rules.)"
    ),
    "retired-query-retain-key": (
        "Route metadata `:query-retain` was retired by EP-0037 R5 with no "
        "alias — `reg-route` rejects it as an unknown bare key, it is not in "
        "the accepted-key roster, and it no longer widens the query-key "
        "promotion vocabulary. A destination address is taken LITERALLY. "
        "Declare the keys the route owns in `:query` / `:query-defaults`; "
        "carry query state across routes with an ordinary pure function over "
        "the destination address at the call site (Spec 012 §Carrying query "
        "state across routes). To edit the CURRENT route's query, use the "
        "in-place `:query` / `:query-merge` request."
    ),
    "retired-bench-coordinate": (
        "A hicasso BENCH-TREE coordinate — `front.*` / `arm1.*` — was retired "
        "by rf2-hic-007. The shipped package was measured as the prototype "
        "`re-frame.bench.hicasso.{front,arm1}.*`, which is NOT in this repo: a "
        "refusal whose `:where` names it points a consumer at nothing. Raise "
        "from the package's own namespace — e.g. "
        "`:where 're-frame.hicasso.impl.collector/frame-prop-shell` — and "
        "assert against the PACKAGE prefix `\"re-frame.hicasso.impl.\"` rather "
        "than one file's, so the next move of a guard between `impl` "
        "namespaces does not red the row. If you are writing PROSE about the "
        "prototype: in Markdown, backtick it (`` `front.codec/realize-deep` "
        "``) — a backticked mention there is provenance and this rule never "
        "fires on one. In Clojure a backtick is the SYNTAX-QUOTE reader macro, "
        "not a prose device, so it does not exempt anything; put the mention "
        "in a `;` comment or a string, both of which the symbol rule masks. "
        "Naming the coordinate inside a larger sentence is always safe — the "
        "string rule fires only on a whole literal that IS the coordinate."
    ),
}


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} retired spelling(s) found in repo source "
        "(EP-0007 §Enforcement):\n\n"
    )
    for f in findings:
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        sys.stderr.write(f"  {f.kind}: {rel}:{f.line}\n      {f.snippet}\n")
    # Group fix hints by family so the message stays terse.
    families = {k.split(":", 1)[0] for k in (f.kind for f in findings)}
    sys.stderr.write("\nFix:\n")
    for fam in sorted(families):
        sys.stderr.write(f"  * {_FIX_HINTS[fam]}\n")


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "EP-0007 §Enforcement: fail on a retired spelling reappearing in "
            "repo source (the bare :frame coeffect; redirect :url/:to; "
            "route :query-retain; a hicasso front.*/arm1.* bench coordinate, "
            "as a symbol OR as a string)."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--scan-dir",
        action="append",
        default=None,
        dest="scan_dirs",
        help=(
            "Directory (relative to repo-root) to scan. Repeatable. Defaults to "
            "every Clojure source tree in the repo: "
            f"{', '.join(DEFAULT_SCAN_DIRS)}. Scopes rules (a)-(c) only; the "
            "bench-coordinate rule (d) has a fixed roster and always runs."
        ),
    )
    parser.add_argument(
        "--include-tests",
        action="store_true",
        help=(
            "Scan test/ trees too. Off by default — tests legitimately name "
            "the retired spelling to assert it is gone / rejected. Does not "
            "apply to rule (d), which scans hicasso tests unconditionally."
        ),
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help=(
            "Run the bundled fixture-based self-tests in "
            "scripts/_test_fixtures/check_retired_spellings/ and exit."
        ),
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests(verbose=args.verbose)

    if args.repo_root:
        repo_root = Path(args.repo_root).resolve()
    else:
        repo_root = Path(__file__).resolve().parent.parent

    if not (repo_root / "mkdocs.yml").is_file():
        sys.stderr.write(
            f"error: {repo_root} does not look like the re-frame2 repo root "
            "(no mkdocs.yml). Pass --repo-root explicitly.\n"
        )
        return 2

    scan_dirs = args.scan_dirs or list(DEFAULT_SCAN_DIRS)
    scan_roots = [repo_root / d for d in scan_dirs]
    coord_roots = [repo_root / p for p in COORD_SCAN_PATHS]

    # A rostered tree that has been renamed or deleted is NOT skipped. Skipping
    # is how a widened gate quietly narrows again — it would report success for
    # a tree it never opened, which is the whole defect class rf2-kqxe6.25 is
    # about. Same posture as the test-lane bijection gate's phantom-path rule.
    # Rule (d)'s roster gets the identical treatment, and needs it more: three
    # of its four entries are hicasso subtrees, and a package reorganisation
    # that renamed one would otherwise silently unratchet the rule.
    missing = [r for r in scan_roots + coord_roots if not r.exists()]
    if missing:
        for root in missing:
            sys.stderr.write(f"error: scan dir {root} does not exist.\n")
        return 2

    if args.verbose:
        n = sum(
            1
            for root in scan_roots
            for _ in _iter_source_files(root, args.include_tests)
        )
        sys.stderr.write(
            f"scanning {n} source file(s) under "
            f"{', '.join(str(r.relative_to(repo_root)) for r in scan_roots)} "
            f"(tests {'included' if args.include_tests else 'excluded'})...\n"
        )
        c = sum(1 for root in coord_roots for _ in _iter_coordinate_files(root))
        sys.stderr.write(
            f"scanning {c} file(s) under {', '.join(COORD_SCAN_PATHS)} for "
            "retired bench coordinates (tests always included)...\n"
        )

    findings: list[Finding] = []
    for root in scan_roots:
        findings.extend(scan(root, include_tests=args.include_tests))
    for root in coord_roots:
        findings.extend(scan_coordinates(root))
    if findings:
        _report(findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write("no retired spellings in any scanned source tree.\n")
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FIRES on each retired shape
# and stays GREEN on every sanctioned counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent / "_test_fixtures" / "check_retired_spellings"
)

# Sanctioned `:query-retain` mentions that live OUTSIDE this gate's scan surface
# (a skill reference, a spec schema comment, a conformance fixture). Pinned
# VERBATIM and scanned as raw single lines rather than as `.cljc` fixtures,
# because that is the point: a Markdown bullet gets no comment/string masking,
# so only the keyword-token boundary keeps the rule off it. If the surface ever
# widens to these trees, this phase is what says it stayed correct.
_SANCTIONED_PROSE_MENTIONS: tuple[tuple[str, str], ...] = (
    ("skills/re-frame2/references/tooling/routing.md",
     "and never gains query keys from whichever route was current. There is no "
     "`:query-retain` (retired, no alias; declaring it is rejected as an "
     "unknown bare key), no query middleware, no per-route carry policy."),
    ("spec/Spec-Schemas.md",
     "   [:query-defaults  {:optional true} [:map-of :keyword :any]]  "
     ";; Destination-LOCAL. EP-0037 R5 retired `:query-retain`; cross-route "
     "carry is the application's pure fold over the destination address."),
    ("spec/conformance/fixtures/routing-query-keyword-discipline.edn",
     ";;      EP-0037 R5 retired the third source, `:query-retain`: a key that "
     "was keyword-promoted solely by its `:query-retain #{:theme}` declaration "
     "must now be declared in `:query` / `:query-defaults`."),
)

# Rule (d) fixtures, scanned through `scan_coordinates` rather than `scan` —
# different surface, different masking, so a separate roster. Each POSITIVE
# plants exactly one shape and expects exactly one finding, which is itself an
# assertion: a coordinate must not be double-reported by both patterns.
_COORD_SELF_TEST_CASES: tuple[tuple[str, int], ...] = (
    # --- positives: BOTH shapes must fire, in source AND in Markdown ---
    ("coord/positive/where_symbol_front.cljc",     1),
    ("coord/positive/where_symbol_arm1.cljc",      1),
    # The two that a symbol-shaped check cannot see — the rf2-hic-007 blind
    # spot, which is the entire reason rule (d) exists.
    ("coord/positive/assertion_string_front.cljc", 1),
    ("coord/positive/assertion_string_arm1.cljc",  1),
    ("coord/positive/spec_prose.md",               1),
    ("coord/positive/spec_code_block.md",          1),
    # The third executable shape, and the one the PR #7867 audit found escaping
    # rule (d): a syntax-quoted coordinate is live Clojure, not prose.
    ("coord/positive/syntax_quoted_symbol.cljc",   1),
    # --- negatives: the corpus's real provenance prose must stay GREEN ---
    ("coord/negative/bare_comment_provenance.cljc", 0),
    ("coord/negative/refusal_message_prose.cljc",   0),
    ("coord/negative/shipped_coordinates.cljc",     0),
    ("coord/negative/spec_boundary_cases.md",       0),
    # The false positive that shipped alongside the miss above: a retired
    # coordinate quoted, with escaped quotes, inside a larger prose literal.
    ("coord/negative/escaped_quote_in_prose_string.cljc", 0),
)


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture file and assert the expected finding count.

    Each fixture is a single `.cljc` file. Positive fixtures plant ONE retired
    shape (expected=1); negative fixtures exercise the sanctioned counterparts
    that MUST stay green (expected=0). Fixtures live under a `negative`/`positive`
    dir; we scan with --include-tests semantics (direct-file mode) so the
    test-dir exclusion does not hide them.

    A second phase scans `_SANCTIONED_PROSE_MENTIONS` — verbatim retirement
    notes from trees this gate does not scan, where no masking applies — as raw
    lines, so the shape scoping is proven independently of the masking.

    A third phase runs `_COORD_SELF_TEST_CASES` through `scan_coordinates`.
    Rule (d) needs its own phase because it needs its own scanner: it reads
    INSIDE string literals and it accepts `.md`. Its negatives are reproduced
    verbatim from the shipped hicasso corpus, so a future widening of the rule
    that would red real files fails here first, in this repo, rather than in
    someone else's PR.
    """
    cases: list[tuple[str, int]] = [
        # (fixture-file relative to fixture-root, expected finding count)
        # --- positives: each retired shape must FIRE ---
        ("positive/get_coeffect_frame.cljc",        1),
        ("positive/frame_of_coeffects.cljc",        1),
        ("positive/get_in_coeffects_frame.cljc",    1),
        ("positive/redirect_url_key.cljc",          1),
        ("positive/safe_redirect_to_key.cljc",      1),
        ("positive/redirect_url_multiline.cljc",    1),
        # EP-0037 R5 `:query-retain` — the three USE shapes row 9 enumerates.
        ("positive/query_retain_reg_route_meta.cljc",     1),
        ("positive/query_retain_accepted_key_roster.cljc", 1),
        ("positive/query_retain_promotion_vocabulary.cljc", 1),
        # --- negatives: every sanctioned counterpart must stay GREEN ---
        ("negative/public_frame_opt.cljc",          0),
        ("negative/frame_trace_tag.cljc",           0),
        ("negative/fx_handler_ctx_frame.cljc",      0),
        ("negative/rf_frame_id_coeffect.cljc",      0),
        ("negative/client_navigate_url.cljc",       0),
        ("negative/redirect_location_key.cljc",     0),
        ("negative/frame_in_comment.cljc",          0),
        ("negative/frame_in_docstring.cljc",        0),
        ("negative/retired_keys_def.cljc",          0),
        # EP-0037 R5 `:query-retain` — every sanctioned in-tree mention names
        # the key as RETIRED; none of them may fire the rule.
        ("negative/query_retain_sanctioned_mentions.cljc", 0),
        ("negative/query_retain_lookalikes.cljc",   0),
    ]

    failures = 0
    for fixture, expected in cases:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing at {path}\n"
            )
            failures += 1
            continue
        # Direct-file scan (the fixture IS the surface).
        got = len(scan(path, include_tests=True))
        if got == expected:
            if verbose:
                sys.stderr.write(f"self-test PASS: {fixture} (findings={got})\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected findings={expected}, "
                f"got {got}\n"
            )
            failures += 1

    # Phase 2: the out-of-surface sanctioned prose mentions, scanned raw.
    for origin, line in _SANCTIONED_PROSE_MENTIONS:
        got = len(_scan_text(Path(origin), line))
        if got == 0:
            if verbose:
                sys.stderr.write(f"self-test PASS: prose mention in {origin}\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: sanctioned prose mention in {origin} fired "
                f"{got} finding(s):\n      {line}\n"
            )
            failures += 1

    # Phase 3: rule (d), through its own scanner. Same direct-file mode, but
    # `scan_coordinates` — rule (d) reads inside string literals and accepts
    # `.md`, neither of which `scan` does.
    for fixture, expected in _COORD_SELF_TEST_CASES:
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(
                f"self-test FAIL: fixture {fixture!r} missing at {path}\n"
            )
            failures += 1
            continue
        got = len(scan_coordinates(path))
        if got == expected:
            if verbose:
                sys.stderr.write(f"self-test PASS: {fixture} (findings={got})\n")
        else:
            sys.stderr.write(
                f"self-test FAIL: {fixture} expected findings={expected}, "
                f"got {got}\n"
            )
            failures += 1

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        total = (len(cases) + len(_SANCTIONED_PROSE_MENTIONS)
                 + len(_COORD_SELF_TEST_CASES))
        sys.stderr.write(f"all {total} self-tests passed.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
