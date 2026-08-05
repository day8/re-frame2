#!/usr/bin/env python3
"""Retired composition-vocabulary guardrail — the `image -> frame -> event stream` rule.

The normative rule this gate enforces is recorded in
spec/Conventions.md §"Retired composition vocabulary — the hard rule"
(rf2-6sefiu.1). In one line: the EP-0013 app/realm/module construction-and-install
model is RETIRED — removed from the public facade by EP-0023 (image-loaded
frames) and DELETED IN FULL by EP-0024 (unified frame identity) — and the ONLY
place its vocabulary may appear is HISTORICAL discussion under `docs/EP/**`. The
live public model is `image -> frame -> event stream`. This gate fails if a
retired composition spelling reappears as LIVE, copy-pasteable public API on the
teaching / reference surface anywhere outside that historical carve-out.

THE POLICY (EP-0023 §Naming, docs/EP/EP-0023-image-loaded-frames.md:201)

    "Avoid using `app`, `application`, `realm`, or `module` as new conceptual
     nouns in this model."

and the supersession table (EP-0023:1670) maps the retired construction +
install + inspection family — `rf/install!`, `rf/reinstall!`, `rf/realm`,
`rf/dispose-realm!`, `rf/installed-app`, `rf/realm-ids`, `rf/frame-realm`,
`rf/app-registrations`, `rf/app-owns`, `rf/app-requires`, `rf/module`,
`rf/app` — onto the public `rf/image` + `rf/make-frame` (re-construction folds
image hot-reload in, rf2-lxwpob) + `rf/destroy-frame!` model. EP-0024 then DELETED the substrate that carried
the realm machinery, so there is no retained-internal realm surface to read.

So a retired composition spelling is legitimate ONLY where the SUBJECT is the
retirement itself — the `EP-*` design/supersession docs. It is DRIFT anywhere it
reads as live, recommended, copy-pasteable re-frame2 API on the public teaching
surface (the spec, guide, API reference, skills, migration guidance, examples,
and repo support files are all held to the clean bar).

WHY THE SHAPE IS SCOPED TO FENCED CODE (the "where shapes allow" caveat)

The words `app`, `application`, `module`, and `realm` are named CONSTANTLY in
legitimate prose: "the application", "app-db" (a sanctioned existing term per
EP-0023:204), "the module manifest", "discussing the realm container". A bare
word grep fires on hundreds of correct sites. The retired SYMBOLS are the
load-bearing signal, and *position* disambiguates:

  * Inside a fenced code block (```...```), a retired symbol reads as live
    copy-pasteable API — `(rf/install! realm app)`, `(rf/app-owns app [:cart])`,
    `(rf/realm ...)`. THIS is the drift the gate fires on.
  * In prose (outside any code fence), the symbol is a NAME being discussed —
    "`rf/install!` was retired", "the old `rf/realm` surface". The gate never
    looks at prose, so a removed-context mention can never fire.
  * In an INLINE `code span` (single backticks) the symbol is also a name being
    named, not a snippet to copy. Inline spans are part of prose and not scanned.

A `;`-to-EOL Clojure comment INSIDE a code fence is masked (length-preserving)
the same way: a fenced block whose only mention is a `;; rf/install! is retired`
comment is removed-context prose that happens to sit in a code block.

THE MINIMAL ALLOWLIST (historical surface ONLY — `docs/EP/**`)

A retired symbol inside a code fence is correct ONLY on the surfaces whose
SUBJECT IS the retired vocabulary — the `EP-*` design/supersession docs, which
carry worked examples of the old construction as the thing-being-retired. After
the EP-0024 atomic substrate deletion (#4811) there is NO retained-internal
realm machinery, so the migration/implementor/tooling skills NO LONGER carry the
retired surface as live examples — they were rewritten onto the image/frame
model and now scan clean WITHOUT an allowlist entry. The allowlist was therefore
TIGHTENED to the rule (rf2-6sefiu.1): it is now the `docs/EP/` prefix (plus the
`EP-*.md` basename glob for EP docs under spec/) and nothing else.

The allowlist is a FILE allowlist, not a pattern allowlist: it does not widen
what counts as the retired vocabulary, it only exempts the EP historical record.
Every other doc — the whole teaching surface, the public API reference, the spec
meta-docs (including spec/Conventions.md, which now HOLDS the hard rule), the
skills, the migration guidance — must be clean. A future stale example anywhere
outside `docs/EP/**` FAILS without the allowlist being touched. WIDENING the
allowlist to relegitimise a non-EP surface is a rule violation, not a fix —
rewrite the surface onto `image -> frame -> event stream` instead.

NOTE — this is a DOC-SURFACE gate: it keeps the public TEACHING surface clean —
a retired spelling reappearing as live, copy-pasteable API inside markdown
fenced code. (The earlier manifest-data facade-hygiene gate was retired with the
atomic re-frame.realm/migration removal, rf2-udl74a — its disposition source
(re-frame.migration/migration-map) and the superseded EP-0013 facade names it
tracked no longer exist.) Keep this guard deliberately small and modular.

SCAN SURFACE

`docs/core`, `spec`, and `skills` markdown (`.md`). Generated
mirrors (`docs/spec/` — CI does `rm -rf docs/spec && cp -r spec docs/spec`)
and vendor/build dirs are excluded; the tracked `spec/` source is the
authoritative copy. `docs/EP` rides under `docs/` but is allowlisted as a whole
(the EP docs ARE the retirement record).

ROOT-SUPPORT COVERAGE (rf2-2c8zq6)

The dir-tree scan above reaches docs/spec/skills markdown only. The root
support surfaces a reader sees FIRST — README.md, TESTING.md, CHANGELOG.md,
SKILL-REDIRECT.md, AGENTS.md, CLAUDE.md — plus the JVM gate scripts whose
comments explain what each tier protects (scripts/test-*.sh) are scanned too
(`_ROOT_SUPPORT_PROSE_FILES`). On those files the SYMBOL families still fire on
any fenced Clojure, and a third PROSE-architecture family
(`retired-architecture-prose`) fires on two distinctive multi-word phrases —
`event[- ]program` and `realm[- ]routing` — wherever they teach the previous
model rather than discuss its removal. A removed-context marker on the same
line (`retired`, `removed`, `no longer`, `no realm-routing`, …) suppresses the
hit, mirroring the symbol rules' prose/comment tolerance. The phrase set is
kept TINY on purpose: bare `program` / `app` / `realm` words are far too noisy.

Exit code:
    0  no live retired composition vocabulary on the teaching surface
    1  at least one live retired symbol (results printed file:line)
    2  invocation / setup error

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Iterable, NamedTuple

# --------------------------------------------------------------------------
# Scan surface
# --------------------------------------------------------------------------

# The human-facing + AI-facing doc surface EP-0023 governs: the API reference,
# the guide, the spec, and the skills docs. (docs/EP rides under docs/ via the
# `docs/core`-sibling tree, but every EP doc is allowlisted below.)
DEFAULT_SCAN_DIRS = ("docs/core", "docs/api", "docs/EP", "spec", "skills")

# rf2-lq99wc — the per-tool spec trees (`tools/<tool>/spec/`) are a teaching
# surface too: the Xray / Pair-MCP / Story tool specs document the host model a
# tool reads, and the rf2-6sefiu.5 audit found a deleted-substrate-as-live class
# there is exactly what the expanded guard now gates. The tool set varies, so
# the dirs are DISCOVERED at runtime by globbing `tools/*/spec` under the repo
# root (see `_tool_spec_scan_dirs`) and unioned into the default scan. (Tool
# specs are NOT mirrored into the staged docs site, so they are not double-
# counted by the docs/spec exclusion.)
_TOOL_SPEC_GLOB = "tools/*/spec"

# A repo-relative path under any `tools/<tool>/spec/` tree — the panel-convention
# surface where bare `install!` / `reinstall!` is exempted (rf2-7gmtz5).
_TOOL_SPEC_REL_RE = re.compile(r"^tools/[^/]+/spec/")

_DOC_SUFFIXES = (".md",)

# rf2-2c8zq6 — root-support surfaces the dir-tree scan above DOES NOT reach.
# These are the first support files a reader/maintainer sees (the root README,
# the testing matrix, the changelog, the skill redirect, the AI-agent
# instruction files) plus the JVM gate scripts whose comments explain what each
# tier protects. The dir scan covers docs/spec/skills markdown only, so without
# this list a retired ARCHITECTURE phrase ("the event program", "preserved
# realm-routing") can drift back onto root support without a CI failure.
#
# The prose-architecture family (`_PROSE_PATTERN`) runs over these files; the
# symbol families ALSO run over the markdown ones (they carry fenced Clojure).
# Listed as repo-relative forward-slash paths. KEEP HIGH-SIGNAL: each file is a
# root-level support surface that teaches the public model or documents a gate.
_ROOT_SUPPORT_MARKDOWN = (
    "README.md",
    "TESTING.md",
    "CHANGELOG.md",
    "SKILL-REDIRECT.md",
    "AGENTS.md",
    "CLAUDE.md",
)

# Root support SCRIPTS whose comments explain the gates. Non-markdown, so only
# the prose-architecture family runs (line-by-line; a shell comment is the
# "prose" surface). The JVM gate scripts are where 2f1x2x found the stale
# "preserved realm-routing" claim.
_ROOT_SUPPORT_SCRIPTS = (
    "scripts/test-jvm-implementation.sh",
    "scripts/test-jvm-tools.sh",
    "scripts/test-fast-pr.sh",
    "scripts/test-rigorous-local.sh",
)

# Everything the prose-architecture family scans (markdown + scripts).
_ROOT_SUPPORT_PROSE_FILES = frozenset(
    _ROOT_SUPPORT_MARKDOWN + _ROOT_SUPPORT_SCRIPTS
)

# Directory names whose contents are never an authoritative teaching surface.
# `docs/spec/` is the generated CI mirror of `spec/` (rm -rf + cp -r), so it
# would double-count every spec hit; the tracked `spec/` tree is the source.
_EXCLUDE_DIR_NAMES = frozenset({
    "node_modules",
    "target",
    ".shadow-cljs",
    ".git",
    ".beads",
    ".cpcache",
    "site",   # mkdocs build output (gitignored)
})

# Generated mirror of spec/ that CI materialises under docs/. Excluded so a
# spec hit is reported once (against the tracked spec/ source), never twice.
_EXCLUDE_REL_PREFIXES = ("docs/spec/",)


# --------------------------------------------------------------------------
# Narrow allowlist — historical surface ONLY (file-scoped)
# --------------------------------------------------------------------------
#
# THE HARD RULE (rf2-6sefiu.1, recorded normatively in
# spec/Conventions.md §Retired composition vocabulary — the hard rule):
# the ONLY place the retired realm/app/module composition vocabulary may appear
# is HISTORICAL DISCUSSION, and the only historical surface is `docs/EP/**`
# (plus EP-* docs that live under spec/, handled by the basename glob below).
#
# Post-EP-0024 (the atomic substrate deletion, #4811) there is NO retained-
# internal realm machinery left to read, so the migration skill, the
# implementor skill, and the Pair/Xray tooling skills NO LONGER carry the
# retired surface as live fenced examples — they were rewritten onto the
# image/frame model, and the guard now confirms they scan clean WITHOUT an
# allowlist entry. The earlier per-skill allowlist entries were therefore
# de-listed under this bead (rf2-6sefiu.1): they were stale exemptions for a
# substrate that no longer exists. spec/Conventions.md was de-listed too — it
# is now the home of the hard rule itself and is held to the clean public-surface
# bar like any other teaching doc (its retired mentions are prose / inline
# spans, which the gate never scans).
#
# KEEP THIS MINIMAL — the allowlist is the `docs/EP/` prefix and nothing else.
# Widening it to relegitimise a non-EP surface is a RULE VIOLATION, not a fix:
# rewrite the surface onto image -> frame -> event stream instead.
_ALLOWLIST: tuple[tuple[str, str], ...] = (
    # The EP design + supersession docs ARE the retirement record. EP-0013 is
    # the design doc that introduced the app/realm/module construction; EP-0023
    # supersedes it (the §1670 mapping table); EP-0024 deletes it in full.
    # Worked examples of the old surface inside code fences are the docs' subject.
    ("docs/EP/",
     "EP design/supersession docs — the retirement record; worked examples of "
     "the retired surface are the docs' subject (EP-0013 design, EP-0023 §1670 "
     "supersession map, EP-0024 deletion). The ONLY historical carve-out."),
)

# EP docs also appear under spec/ as `EP-*.md` (e.g. spec/EP-0007.md); allow
# those by basename glob in addition to the docs/EP/ prefix above.
_ALLOWLIST_BASENAME_RE = re.compile(r"(?:^|/)EP-\d+[^/]*\.md$")


def _is_allowlisted(rel_posix: str) -> bool:
    if any(rel_posix.startswith(p) for p, _ in _ALLOWLIST):
        return True
    if _ALLOWLIST_BASENAME_RE.search(rel_posix):
        return True
    return False


# --------------------------------------------------------------------------
# Retired-symbol patterns (matched only inside fenced code, see below)
# --------------------------------------------------------------------------
#
# Each pattern targets a RETIRED composition SYMBOL — the load-bearing signal,
# not a bare English word. Two families:
#
#   (a) The strong, unambiguous construction/install/inspection symbols. These
#       dash-cased identifiers are not English words and not used by any
#       sanctioned surface, so a bare-symbol match (optionally namespace-
#       qualified) is safe. The negative-lookarounds keep `install!` from
#       matching a longer symbol and forbid `realm-ids-foo` etc.
#
#   (b) The conceptual-noun symbols `app` / `module` / `realm` ONLY in the
#       dangerous NAMESPACED-CALL shape `(rf/app ...)`, `(rf/module ...)`,
#       `(rf/realm ...)`. A bare `realm`/`module`/`app` word is far too noisy
#       (app-db, the application, the module manifest) — EP-0023:204 explicitly
#       sanctions `app-db` and ordinary English `application` — so we scope to
#       the `rf/`-prefixed facade-call form, which is exactly the retired API
#       reintroduction the inventory targets.
#
# A symbol char class for Clojure identifiers (dash-cased + the usual punct).
_SYM = r"\w.+!?<>=-"

# (a) Strong unambiguous retired symbols. The drift shape is the BARE or
#     `rf/`-FACADE-qualified spelling — the public reintroduction the inventory
#     targets. A symbol qualified by ANY OTHER namespace names a different
#     symbol that merely shares the bare name (the internal `re-frame.realm/`
#     substrate read; an app's own `counter/install!` setup hook) and is NOT
#     drift — see `_strong_hits` for the qualifier filter. The trailing
#     negative-lookahead forbids matching a longer symbol (so `realm-ids` does
#     not match `realm-ids-extra`); `install!` / `reinstall!` / `dispose-realm!`
#     end in `!` which already bounds them.
_STRONG_SYMBOLS = (
    "reinstall!",          # listed before `install!` so the alternation
    "install!",            #   prefers the longer match
    "dispose-realm!",
    "realm-ids",
    "installed-app",
    "app-registrations",
    "app-owns",
    "app-requires",
    "frame-realm",
)

# rf2-lq99wc / rf2-7gmtz5 — `install!` / `reinstall!` are the only two strong
# symbols whose BARE spelling collides with a LIVE, sanctioned convention: the
# Xray / Story tools name each panel's registration entry point `install!`
# (`(defn install! [] (subs/install!) (events/install!) nil)` — dozens of live
# source files; the tool specs document the convention). NONE of those are the
# retired EP-0013 `rf/install!` facade constructor (verified: zero co-occur with
# realm/app-value). So on the TOOL-SPEC surface (`tools/*/spec`) a bare
# install!/reinstall! is the panel convention, not drift, and is exempted there.
# The REALM-SPECIFIC retired symbols below still fire EVERYWHERE (incl. tool
# specs) — they have no live-convention collision. (On the framework doc surface
# bare install!/reinstall! still fires: there it reads as the retired verb.)
_TOOL_CONVENTION_SYMBOLS = frozenset({"install!", "reinstall!"})

# Build one alternation. The optional namespace qualifier is CAPTURED so the
# matcher (`_strong_hits` below) can keep ONLY the facade forms (bare or `rf/`)
# and reject any other qualifier (`re-frame.realm/realm-ids` internal substrate,
# `counter/install!` app hook). The leading negative-lookbehind keeps it from
# matching mid-identifier; the trailing `(?![\w.+!?<>=-])` keeps `realm-ids`
# from matching `realm-idsx` and `app-owns` from `app-ownsx`. `install!` won't
# match inside `reinstall!`/`installed-app` because the lookbehind forbids a
# preceding identifier char.
_STRONG_ALT = "|".join(re.escape(s) for s in _STRONG_SYMBOLS)
_STRONG_RE = re.compile(
    rf"(?<![{_SYM}])(?P<ns>[{_SYM}]+/)?(?P<sym>{_STRONG_ALT})(?![{_SYM}])"
)

# The retired SYMBOLS are `re-frame.core` (`rf/`) FACADE exports. The drift is
# therefore exactly the `rf/`-qualified or BARE-unqualified spelling. Any OTHER
# namespace qualifier names a DIFFERENT symbol that merely shares the bare name:
#
#   * `counter/install!`, `my.app/install!` — an APP's own setup hook (a normal
#     name for an example app's registration entry point; see
#     spec/008-Testing.md §Pattern 5 `with-app-fixture {:install counter/install!}`).
#     `install!` is not a reserved word; an app may name its own fn that. This is
#     the live-and-legitimate case the qualifier filter protects.
#
# (The realm machinery itself — the `re-frame.realm/` / `re-frame.frame/`
# substrate — was DELETED IN FULL by EP-0024 (#4811), so there is no longer an
# internal `re-frame.realm/realm-ids` read to exempt; only an app's own
# off-facade hook survives as a non-`rf/` qualified match.)
#
# So a strong-symbol match is drift ONLY when its captured `ns` qualifier is
# empty (bare) or exactly `rf/` (the facade). A bare `install!` is still caught
# because the dangerous reintroduction `[(rf/install! ...)]` is the common shape
# AND a bare `(install! ...)` on the public teaching surface reads as the
# retired verb; an app's setup hook is always namespace-qualified (it lives in
# the app's ns, called as `counter/install!`), so the bare form is unambiguous
# drift on the teaching surface.
_FACADE_NS_QUALIFIERS = frozenset({"", "rf/"})


def _strong_hits(line: str, tool_spec_surface: bool = False) -> str | None:
    """The retired strong symbol `line` carries (bare or `rf/`), else None.

    Returns the SYMBOL rather than a bare True so a finding can say which of the
    nine roster entries produced it, and the self-test can hold each entry to
    owning a fixture (rf2-57vnc). Still truthy/falsy, so every caller that only
    asks "did it hit?" reads the same answer it always did.

    A match qualified by any namespace OTHER than `rf/` (e.g. the internal
    `re-frame.realm/`, or an app's own `counter/`) names a different symbol
    that merely shares the bare name, and is NOT drift.

    On the TOOL-SPEC surface (`tool_spec_surface=True`) the panel-convention
    symbols `install!` / `reinstall!` (`_TOOL_CONVENTION_SYMBOLS`) are exempted —
    there they are the Xray/Story per-panel registration entry point, not the
    retired `rf/install!` facade constructor (rf2-7gmtz5). The realm-specific
    retired symbols still fire everywhere.
    """
    for m in _STRONG_RE.finditer(line):
        ns = m.group("ns") or ""
        if ns not in _FACADE_NS_QUALIFIERS:
            continue
        if tool_spec_surface and m.group("sym") in _TOOL_CONVENTION_SYMBOLS:
            continue
        return m.group("sym")
    return None

# (b) Retired conceptual-noun facade CALLS: `(rf/app ...)`, `(rf/module ...)`,
#     `(rf/realm ...)`. Scoped to the open-paren + `rf/`-qualified call form so
#     `app-db`, "the application", the `:rf.realm/...` keyword, and
#     `re-frame.realm/` internal namespace reads never match. The trailing
#     `(?![\w.+!?<>=-])` forbids `rf/app-db`, `rf/realm-thing`, etc. — those
#     are different symbols (and `rf/app-db` is not even a real export). We
#     deliberately require the `(` so a bare `rf/realm` mentioned as a value
#     does not fire; the retired API reintroduction is always a call.
#     The three nouns are a NAMED roster so a finding says which one fired and
#     the self-test can hold each to owning a fixture (rf2-57vnc) — `rf/app` had
#     none, and deleting it from the alternation changed nothing.
_RETIRED_FACADE_NOUNS: tuple[str, ...] = ("app", "module", "realm")
_RETIRED_FACADE_CALL_RE = re.compile(
    r"\(\s*rf/(?P<noun>" + "|".join(_RETIRED_FACADE_NOUNS)
    + r")(?![\w.+!?<>=-])"
)


def _facade_noun_hits(line: str) -> str | None:
    """The retired facade noun `line` calls (`rf/app` / `rf/module` /
    `rf/realm`), else None."""
    m = _RETIRED_FACADE_CALL_RE.search(line)
    return f"rf/{m.group('noun')}" if m else None

# (c) Retired ARCHITECTURE-TEACHING prose phrases (rf2-2c8zq6). The root-support
#     surfaces (README, TESTING, the JVM gate comments) drifted not by planting
#     a retired SYMBOL in a code fence but by teaching the previous model in
#     PROSE: an app's event stream described as "the event program", a JVM gate
#     comment claiming "realm-routing" is a preserved contract. The canonical
#     public model is image -> frame -> event stream, so these two distinctive
#     multi-word phrases read as live architecture vocabulary wherever they are
#     NOT discussing the retirement itself.
#
#     Scope is DELIBERATELY two phrases only — `event[- ]program` and
#     `realm[- ]routing`. They are multi-word and architecture-specific, so the
#     false-positive surface is tiny (unlike a bare `program`, `app`, or `realm`
#     word). A removed-context marker on the same line (`retired`, `removed`,
#     `no longer`, `collapsed`, `superseded`, `deprecated`, `no <phrase>`, …)
#     SUPPRESSES the hit: a sentence that says "this tier carries no
#     realm-routing section" or "the event-program field was renamed" is
#     discussing the removal, not teaching the model — exactly the same
#     removed-context exemption the symbol rules grant prose + masked comments.
_RETIRED_PROSE_RE = re.compile(
    r"\b(?:event[- ]program|realm[- ]routing)\b",
    re.IGNORECASE,
)

# Phrases that mark a line as DISCUSSING the retirement (removed-context), so a
# retired prose phrase on that line is a name being named, not live teaching.
# Mirrors the symbol rules' removed-context tolerance (prose + masked comments
# never fire there either).
#
# The base set covers the architecture-prose family (event-program / realm-
# routing). The `gone` / `deleted` / `there is no` / bare `no` markers are added
# (rf2-lq99wc) so the deleted-substrate families below — which fire on a CLAIM
# that the deleted realm/app-value/migration substrate is retained/live — stay
# GREEN wherever the sentence is DISCUSSING the deletion ("the realm machinery
# was deleted in full", "there is no `re-frame.realm` namespace", "no installed-
# app value, no `re-frame.realm/installed-app` seam", "is **gone** with the
# substrate it read"). A bare `\bno\b` is a strong enough removed-context signal
# for these families because the deleted-substrate signal they fire on is itself
# very specific (a `re-frame.realm/`-class read or a "retained realm substrate"
# claim) — "no realm coordinate", "no re-frame.realm namespace", "no installed-
# app value" are all the removal being stated.
_REMOVED_CONTEXT_RE = re.compile(
    r"\b(?:retired|removed?|removal|"
    r"deleted?|delete|gone|absent|eliminated?|dropped?|"
    r"no longer|there\s+is\s+no|there\s+are\s+no|is\s+no\b|are\s+no\b|"
    r"\bno\b|"
    r"collapsed?|superseded?|supersedes|deprecated?|"
    r"renamed|was\s+the|formerly|legacy|"
    r"not?\s+(?:a\s+)?(?:realm[- ]routing|event[- ]program))\b",
    re.IGNORECASE,
)


def _prose_hits(line: str, context: str = "") -> str | None:
    """The retired architecture phrase `line` teaches as LIVE model prose, else
    None. Named, like the other families, so a finding says which of the two
    phrases fired rather than merely that one did.

    A retired phrase (`event[- ]program` / `realm[- ]routing`) fires unless a
    removed-context marker (`retired`, `removed`, `no longer`, `no
    realm-routing`, …) appears in `context` — the line itself PLUS its immediate
    neighbours. Prose wraps across lines, so a sentence like "…carries no\\n
    realm-routing section." carries its removed-context marker on the PREVIOUS
    line; the small window keeps the gate from firing on wrapped removed-context
    prose while staying narrow enough not to swallow an unrelated nearby
    sentence.
    """
    m = _RETIRED_PROSE_RE.search(line)
    if not m:
        return None
    haystack = context if context else line
    if _REMOVED_CONTEXT_RE.search(haystack):
        return None
    # Normalise the spelling variants (`event program` / `event-program`) onto
    # one roster name so the witness is the RULE, not the prose that tripped it.
    return m.group(0).lower().replace(" ", "-")


# (d) DELETED-SUBSTRATE-AS-LIVE prose families (rf2-lq99wc). The
#     architecture-prose family (c) only fires on two phrases (`event-program`
#     / `realm-routing`) and only over the root-support surfaces, so it MISSED a
#     whole residue class the rf2-6sefiu.5 audit surfaced on the teaching
#     surface (spec/API.md, the tool specs, the api-manifest): prose that
#     RE-LEGITIMIZES the deleted EP-0013 realm/app-value/migration substrate as
#     still-present internal machinery. EP-0024 DELETED that substrate in full
#     (#4811) — there is no `re-frame.realm` namespace, no installed-app value,
#     no migration-map, no realm-scoped reader. Two distinctive shapes:
#
#       (d1) "retained-internal" / "retained as internal substrate" / "realm
#            machinery … retained" / "realm-scoped readers … remain" — a CLAIM
#            that the deleted realm/app-value/module substrate survives as a
#            live internal seam. The phrase must co-occur with the SUBSTRATE
#            noun (`realm` / `app-value` / `app value` / `module` / installed-
#            app) so the EP-0018 `reg-event-ctx` "the … mechanism is retained
#            internally" note (a DIFFERENT subject — no realm/app-value
#            adjacency) does NOT fire.
#
#       (d2) a DELETED-NAMESPACE read named as a CURRENT seam:
#            `re-frame.realm/<sym>`, `re-frame.app-value/<sym>`, or
#            `re-frame.migration/migration-map`. These namespaces no longer
#            exist, so naming one as a live read/import is drift. (The LIVE
#            internal namespaces a tool legitimately reads — `re-frame.registrar`
#            / `re-frame.frame` / `re-frame.live-frame` / `re-frame.image` —
#            are NOT in this set and never fire.)
#
#     BOTH are suppressed by the (extended) removed-context window: a sentence
#     that says the substrate "was deleted in full", "there is no
#     `re-frame.realm` namespace", "no `re-frame.realm/installed-app` seam", or
#     "is **gone** with the substrate it read" is DISCUSSING the deletion, not
#     teaching a live seam — exactly the same removed-context tolerance the
#     symbol + architecture-prose families grant. Unlike the architecture-prose
#     family these run over the FULL doc surface (not just root-support),
#     because the residue the audit found lives in spec/ + the tool specs.
#
#     NOTE (d2 does NOT mask inline `code spans`): a deleted namespace named in
#     an inline span AS A LIVE SEAM ("the realm-scoped readers
#     (`re-frame.realm/realm-registrations`) remain for tooling") is precisely
#     the residue, so d2 scans the RAW line and relies on removed-context
#     suppression — the deleted-namespace signal is specific enough that a span
#     mention without a removal marker IS drift.

# (d1) The retained-internal-substrate CLAIM. `retained[- ]internal`, "retained
#      … internal substrate", "realm machinery … retained", or "… readers …
#      remain" — required to co-occur with a realm/app-value/module substrate
#      noun on the same line so the EP-0018 reg-event-ctx "retained internally"
#      note (no realm adjacency) stays green.
#      Each alternative is NAMED so a finding says which claim shape fired and
#      the self-test can hold each to owning a fixture (rf2-57vnc): the single
#      positive matched only the first two, leaving three shapes unexercised.
_RETAINED_CLAIM_FORMS: tuple[tuple[str, str], ...] = (
    ("retained-internal",
     r"retained[- ]internal"),
    ("retained-as-internal-substrate",
     r"retained\s+(?:as\s+)?(?:an?\s+)?internal\s+substrate"),
    ("substrate-noun-retained",
     r"(?:realm|app[- ]value|module)\s+(?:machinery|substrate|readers?|"
     r"surface|reader)\b[^.\n]*?\bretain"),
    ("retained-substrate-noun",
     r"retained?\b[^.\n]*?\b(?:realm|app[- ]value)\s+(?:machinery|substrate)"),
    ("realm-readers-remain",
     r"(?:realm[- ]scoped|realm)\s+readers?\b[^.\n]*?\bremain"),
)
_RETAINED_CLAIM_RE = re.compile(
    "|".join(pattern for _name, pattern in _RETAINED_CLAIM_FORMS),
    re.IGNORECASE,
)
_RETAINED_CLAIM_RES = tuple(
    (name, re.compile(pattern, re.IGNORECASE))
    for name, pattern in _RETAINED_CLAIM_FORMS
)

# A realm/app-value/module SUBSTRATE noun the (d1) claim must touch — guards the
# narrow co-occurrence requirement so a generic "retained internally" note
# (EP-0018 reg-event-ctx) does not fire.
_SUBSTRATE_NOUN_RE = re.compile(
    r"\b(?:realm|app[- ]value|installed[- ]app|module)\b",
    re.IGNORECASE,
)

# (d2) A DELETED-NAMESPACE read. These namespaces were removed in full by
#      EP-0024; naming one as a live seam is drift. `re-frame.migration/` is
#      scoped to `migration-map` specifically (the disposition source the
#      retired manifest-hygiene gate tracked) to keep the family tight.
#      Named per namespace, same reason as the rosters above (rf2-57vnc): only
#      `re-frame.realm/` was ever planted, so the other two could be deleted
#      from the alternation without a single fixture noticing.
_DELETED_NS_READS: tuple[tuple[str, str], ...] = (
    ("re-frame.realm/",     r"re-frame\.realm/[\w.+!?<>=-]+"),
    ("re-frame.app-value/", r"re-frame\.app-value/[\w.+!?<>=-]+"),
    ("re-frame.migration/migration-map",
     r"re-frame\.migration/migration-map\b"),
)
_DELETED_NS_READ_RE = re.compile(
    "|".join(pattern for _name, pattern in _DELETED_NS_READS)
)
_DELETED_NS_READ_RES = tuple(
    (name, re.compile(pattern)) for name, pattern in _DELETED_NS_READS
)


def _retained_substrate_hits(line: str, context: str = "") -> str | None:
    """The retained-substrate claim shape(s) `line` carries, else None.

    Fires on a `retained-internal` / "realm machinery … retained" / "readers …
    remain" claim that ALSO names a realm/app-value/module substrate noun (so
    the EP-0018 reg-event-ctx "retained internally" note stays green), UNLESS a
    removed-context marker appears in `context` (line + immediate neighbours).

    The return names EVERY `_RETAINED_CLAIM_FORMS` entry that matched, `+`-
    joined — several of the five overlap ("retained internal substrate" is both
    the first and the second), and reporting only the first would credit a
    fixture with proving an entry it never reached.
    """
    matched = [n for n, rx in _RETAINED_CLAIM_RES if rx.search(line)]
    if not matched:
        return None
    if not _SUBSTRATE_NOUN_RE.search(line):
        return None
    haystack = context if context else line
    if _REMOVED_CONTEXT_RE.search(haystack):
        return None
    return "+".join(matched)


def _deleted_ns_read_hits(line: str, context: str = "") -> str | None:
    """The DELETED namespace(s) `line` names as a live read seam, else None.

    Fires on a `re-frame.realm/` / `re-frame.app-value/` /
    `re-frame.migration/migration-map` mention (raw line — inline spans are NOT
    masked, since a span naming a deleted ns as a live seam IS the residue),
    UNLESS a removed-context marker appears in `context`.
    """
    matched = [n for n, rx in _DELETED_NS_READ_RES if rx.search(line)]
    if not matched:
        return None
    haystack = context if context else line
    if _REMOVED_CONTEXT_RE.search(haystack):
        return None
    return "+".join(matched)


# Each family is a (kind, predicate) pair where the predicate maps a
# (masked-line, tool_spec_surface) pair to the ROSTER ENTRY it matched, or None.
# It used to answer a bare True, which is why four of the nine strong symbols and
# `rf/app` could sit in their rosters with no fixture at all: a finding could
# only ever say that SOMETHING in the family fired (rf2-57vnc). The strong family
# uses `_strong_hits` (capture + internal-namespace filter + the tool-spec
# install!-convention exemption); the facade-noun family is a plain regex search
# (surface-independent). The prose family (`_prose_hits`) runs over PROSE lines
# (outside fenced code) on the root-support surfaces, not the fenced-code lines
# the symbol families consume.
_RETIRED_PATTERNS = (
    ("retired-construction-symbol",
     lambda line, tool_spec: _strong_hits(line, tool_spec_surface=tool_spec)),
    ("retired-facade-noun-call",
     lambda line, _tool_spec: _facade_noun_hits(line)),
)

# The prose family is scanned separately (over non-fenced lines), so it is its
# own (kind, predicate) entry rather than part of `_RETIRED_PATTERNS`.
# `_prose_hits` is ROOT-SUPPORT-only (event-program / realm-routing teaching);
# the deleted-substrate families below run over the FULL doc surface.
_PROSE_PATTERN = ("retired-architecture-prose", _prose_hits)

# The deleted-substrate families (rf2-lq99wc) — each a (kind, context-predicate)
# pair. They take the (line, context) signature so the removed-context window
# can read the immediate neighbours (prose wraps across lines). They run over
# the PROSE of EVERY scanned file (not just root-support), because the residue
# the rf2-6sefiu.5 audit found lives on the spec + tool-spec teaching surface.
# Note: these consume the RAW prose line (inline spans NOT masked) — a deleted
# namespace named as a live seam inside an inline span IS the residue.
_DELETED_SUBSTRATE_PATTERNS = (
    ("retired-substrate-retained-claim", _retained_substrate_hits),
    ("retired-deleted-namespace-read", _deleted_ns_read_hits),
)


class Finding(NamedTuple):
    path: Path
    line: int
    kind: str
    snippet: str
    # WHICH roster entry within the family fired. Four of the nine strong
    # symbols, `rf/app`, two of the three deleted namespaces and three of the
    # five retained-claim shapes had no fixture, and nothing could have said so:
    # every finding in a family carried the same `kind` (rf2-57vnc). `_report`
    # is unchanged — this exists for the self-test's roster-coverage assertion.
    detail: str = ""


# --------------------------------------------------------------------------
# Markdown fenced-code extraction + Clojure-comment masking
# --------------------------------------------------------------------------
#
# We scan ONLY inside fenced code blocks (``` or ~~~), and within those, mask
# `;`-to-EOL Clojure line comments so a removed-context comment in a code block
# does not fire. Fence handling follows CommonMark closely enough for our docs:
# a fence opens on a line beginning (after optional indent) with >= 3 backticks
# or tildes, and closes on a later line with a matching fence of the same char
# and at least as many marks. Masking is length-preserving so 1-based line
# numbers and reported snippets line up with the raw source.
#
# (Shape copied from scripts/check_inject_cofx_residue.py — the sibling EP-0017
# residue gate — so the two doc-surface vocabulary gates stay consistent.)

_FENCE_RE = re.compile(r"^(\s*)(`{3,}|~{3,})")
_LINE_COMMENT_RE = re.compile(r";.*$")


def _mask_clj_comment(line: str) -> str:
    """Blank a `;`-to-EOL Clojure line comment, length-preserving.

    Conservative: a `;` anywhere blanks to EOL. Inside a code fence a `;`
    almost always starts a comment in the Clojure-shaped snippets these docs
    carry. The masking only ever REMOVES potential matches, so it cannot cause
    a false PASS for live code outside a comment.
    """
    m = _LINE_COMMENT_RE.search(line)
    if not m:
        return line
    start = m.start()
    return line[:start] + (" " * (len(line) - start))


def _code_fence_lines(text: str) -> list[tuple[int, str]]:
    """Return (1-based-line-no, masked-content) for lines INSIDE fenced code.

    Lines outside any fence (prose, inline code spans, headings) are omitted
    entirely — the gate never inspects prose. Comment lines inside a fence are
    yielded MASKED (blanked) so a removed-context comment cannot fire.
    """
    out: list[tuple[int, str]] = []
    in_fence = False
    fence_char = ""
    fence_len = 0
    for line_no, raw in enumerate(text.splitlines(), start=1):
        m = _FENCE_RE.match(raw)
        if m:
            marks = m.group(2)
            char = marks[0]
            length = len(marks)
            if not in_fence:
                in_fence = True
                fence_char = char
                fence_len = length
                continue
            # Already in a fence: a same-char fence of >= the opening length
            # closes it. Otherwise it is content (a nested-looking fence).
            if char == fence_char and length >= fence_len:
                in_fence = False
                fence_char = ""
                fence_len = 0
                continue
        if in_fence:
            out.append((line_no, _mask_clj_comment(raw)))
    return out


# Inline `code span` masking (single/double backtick runs). Length-preserving.
# A retired phrase inside an inline span is a NAME being named (`event-program`
# the schema field), not live model prose — masked out before the prose rule
# runs, mirroring the symbol rules' inline-span tolerance.
_INLINE_CODE_SPAN_RE = re.compile(r"(`+)(?:.+?)\1")


def _mask_inline_code(line: str) -> str:
    """Blank inline `code spans`, length-preserving, so a span name cannot fire."""
    return _INLINE_CODE_SPAN_RE.sub(lambda m: " " * len(m.group(0)), line)


def _prose_lines(text: str, mask_inline: bool = True) -> list[tuple[int, str]]:
    """Return (1-based-line-no, content) for PROSE lines (outside fences).

    The complement of `_code_fence_lines`: lines INSIDE a fenced code block are
    omitted (the symbol families own those). With `mask_inline=True` (the
    default — used by the architecture-prose family) inline code spans are
    masked (a `code-span` mention is a name, not live model prose). With
    `mask_inline=False` (used by the deleted-namespace family) the RAW prose
    line is returned, since a deleted namespace named as a live seam INSIDE a
    span is the residue.
    """
    out: list[tuple[int, str]] = []
    in_fence = False
    fence_char = ""
    fence_len = 0
    for line_no, raw in enumerate(text.splitlines(), start=1):
        m = _FENCE_RE.match(raw)
        if m:
            marks = m.group(2)
            char = marks[0]
            length = len(marks)
            if not in_fence:
                in_fence = True
                fence_char = char
                fence_len = length
                continue
            if char == fence_char and length >= fence_len:
                in_fence = False
                fence_char = ""
                fence_len = 0
                continue
            # A content line inside a fence: not prose.
            continue
        if not in_fence:
            out.append((line_no, _mask_inline_code(raw) if mask_inline else raw))
    return out


# --------------------------------------------------------------------------
# File iteration
# --------------------------------------------------------------------------


def _tool_spec_scan_dirs(repo_root: Path) -> list[str]:
    """Discover the per-tool spec dirs (`tools/*/spec`) present in the repo.

    Returns repo-relative forward-slash dir strings for every existing
    `tools/<tool>/spec/` directory, sorted. Empty if `tools/` is absent (a
    consumer repo need not carry the dev tools). Unioned into the default scan
    so tool-spec drift is gated (rf2-lq99wc).
    """
    if not (repo_root / "tools").is_dir():
        return []
    out: list[str] = []
    for spec_dir in sorted(repo_root.glob(_TOOL_SPEC_GLOB)):
        if spec_dir.is_dir():
            out.append(spec_dir.relative_to(repo_root).as_posix())
    return out


def _iter_doc_files(scan_root: Path, repo_root: Path) -> Iterable[Path]:
    """Yield markdown files under scan_root, excluding generated/vendor dirs."""
    if scan_root.is_file():
        # Direct-file mode (used by --self-test fixtures pointing at one file).
        if scan_root.suffix in _DOC_SUFFIXES:
            yield scan_root
        return
    for path in sorted(scan_root.rglob("*")):
        if path.suffix not in _DOC_SUFFIXES:
            continue
        parts = set(path.relative_to(scan_root).parts)
        if parts & _EXCLUDE_DIR_NAMES:
            continue
        try:
            rel_posix = path.relative_to(repo_root).as_posix()
        except ValueError:
            rel_posix = path.as_posix()
        if any(rel_posix.startswith(pre) for pre in _EXCLUDE_REL_PREFIXES):
            continue
        yield path


# --------------------------------------------------------------------------
# Per-file scan
# --------------------------------------------------------------------------


def _scan_text(path: Path, text: str, rel_posix: str) -> list[Finding]:
    """Return live retired-vocabulary findings in `text` (already attributed).

    Allowlisted files (the historical / internal / migration surface) are
    skipped entirely — a worked example of the retired surface is correct
    there. For every other file the SYMBOL families scan the masked fenced-code
    lines; on the root-support surfaces the PROSE-architecture family also
    scans the prose lines (`_prose_lines`) for a retired architecture phrase.
    """
    if _is_allowlisted(rel_posix):
        return []
    findings: list[Finding] = []
    raw = text.splitlines()
    # The tool-spec surface (`tools/*/spec/...`) exempts the bare install!/
    # reinstall! panel convention (rf2-7gmtz5); the realm-specific symbols still
    # fire there. `_TOOL_SPEC_REL_RE` matches the discovered scan dirs.
    tool_spec_surface = bool(_TOOL_SPEC_REL_RE.match(rel_posix))
    for line_no, masked in _code_fence_lines(text):
        for kind, predicate in _RETIRED_PATTERNS:
            entry = predicate(masked, tool_spec_surface)
            if entry:
                snippet = raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""
                findings.append(
                    Finding(path, line_no, f"live-retired:{kind}", snippet,
                            detail=entry)
                )
    # The deleted-substrate prose families (rf2-lq99wc) run over the prose of
    # EVERY scanned file — markdown OR a non-markdown root-support script (whose
    # whole body is the prose surface). The residue the audit found lives on the
    # spec + tool-spec teaching surface, not just root-support.
    findings.extend(_scan_deleted_substrate_prose(path, text))
    if rel_posix in _ROOT_SUPPORT_PROSE_FILES:
        findings.extend(_scan_root_support_prose(path, text, rel_posix))
    return findings


def _scan_deleted_substrate_prose(path: Path, text: str) -> list[Finding]:
    """Scan a file's prose for deleted-realm/app-value-substrate-as-live drift.

    Runs the `_DELETED_SUBSTRATE_PATTERNS` families over the RAW prose lines
    (inline spans NOT masked — a deleted namespace named as a live seam in a
    span is the residue). For a markdown file the fenced-code blocks are
    excluded (`_prose_lines(..., mask_inline=False)`); a non-markdown script has
    no markdown fences, so every line is prose. Each family suppresses on a
    removed-context marker in the line + its immediate neighbours.
    """
    raw = text.splitlines()
    is_markdown = path.suffix in _DOC_SUFFIXES
    if is_markdown:
        lines = _prose_lines(text, mask_inline=False)
    else:
        lines = list(enumerate(raw, start=1))
    by_no = {ln: s for ln, s in lines}
    findings: list[Finding] = []
    for line_no, line in lines:
        context = " ".join(
            by_no.get(n, "") for n in (line_no - 1, line_no, line_no + 1)
        )
        for kind, predicate in _DELETED_SUBSTRATE_PATTERNS:
            entry = predicate(line, context)
            if entry:
                snippet = raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""
                findings.append(
                    Finding(path, line_no, f"live-retired:{kind}", snippet,
                            detail=entry)
                )
    return findings


def _scan_root_support_prose(path: Path, text: str, rel_posix: str) -> list[Finding]:
    """Scan a root-support file's prose for retired architecture phrases.

    Markdown root-support files are scanned over their PROSE lines (outside
    fenced code, inline spans masked). Non-markdown scripts have no markdown
    fences, so every line is the prose surface — a shell comment is exactly
    where the gate-explanation prose lives.
    """
    kind, _predicate = _PROSE_PATTERN
    raw = text.splitlines()
    is_markdown = path.suffix in _DOC_SUFFIXES
    if is_markdown:
        lines = _prose_lines(text)
    else:
        # Scripts: scan every line, inline `spans` masked the same way so a
        # `event-program` field name in a comment is a name, not live prose.
        lines = [(i, _mask_inline_code(s)) for i, s in enumerate(raw, start=1)]
    # Index masked content by line-no so the removed-context window can read the
    # immediate neighbours (prose wraps across lines).
    masked_by_no = {ln: m for ln, m in lines}
    findings: list[Finding] = []
    for line_no, masked in lines:
        context = " ".join(
            masked_by_no.get(n, "")
            for n in (line_no - 1, line_no, line_no + 1)
        )
        entry = _prose_hits(masked, context)
        if entry:
            snippet = raw[line_no - 1].strip() if 0 <= line_no - 1 < len(raw) else ""
            findings.append(
                Finding(path, line_no, f"live-retired:{kind}", snippet,
                        detail=entry)
            )
    return findings


def scan(scan_root: Path, repo_root: Path | None = None) -> list[Finding]:
    """Scan doc markdown under scan_root for live retired composition vocab."""
    if repo_root is None:
        repo_root = scan_root if scan_root.is_dir() else scan_root.parent
    findings: list[Finding] = []
    for path in _iter_doc_files(scan_root, repo_root):
        try:
            rel_posix = path.relative_to(repo_root).as_posix()
        except ValueError:
            rel_posix = path.as_posix()
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text, rel_posix))
    return findings


def scan_root_support(repo_root: Path) -> list[Finding]:
    """Scan the root-support surfaces (`_ROOT_SUPPORT_PROSE_FILES`) for drift.

    These individual files live at the repo root / under scripts/ and are not
    reached by the dir-tree scan. Each is scanned via `_scan_text` (the symbol
    families fire on any fenced Clojure; the prose-architecture family fires on
    the prose surface). Missing files are skipped (a repo may not carry every
    one).
    """
    findings: list[Finding] = []
    for rel in sorted(_ROOT_SUPPORT_PROSE_FILES):
        path = repo_root / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        findings.extend(_scan_text(path, text, rel))
    return findings


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

_FIX_HINTS = {
    "retired-construction-symbol": (
        "The EP-0013 construction/install/inspection symbols (`install!`, "
        "`reinstall!`, `dispose-realm!`, `realm-ids`, `installed-app`, "
        "`app-registrations`, `app-owns`, `app-requires`, `frame-realm`) were "
        "RETIRED from the public facade by EP-0023 (image-loaded frames) and the "
        "realm machinery DELETED IN FULL by EP-0024 (unified frame identity). The "
        "public composition model is `rf/image` -> `rf/make-frame` / "
        "`rf/make-frame` -> `rf/reload-images!` / `rf/destroy-frame!`. Rewrite "
        "the example onto that model. The retired vocabulary is allowed ONLY in "
        "the `docs/EP/**` historical record — nowhere else (see "
        "spec/Conventions.md §Retired composition vocabulary — the hard rule)."
    ),
    "retired-facade-noun-call": (
        "`(rf/app ...)`, `(rf/module ...)`, and `(rf/realm ...)` are the "
        "retired EP-0013 facade constructors. EP-0023 §Naming "
        "(docs/EP/EP-0023-image-loaded-frames.md:201) retires `app` / `realm` "
        "/ `module` as composition nouns in favour of `image` -> `frame` -> "
        "`event stream`. Build composition from `rf/image` + `rf/make-frame` "
        "instead. (`app-db` is a sanctioned EXISTING term — EP-0023:204 — and "
        "ordinary English `application` is fine; only the `rf/`-facade noun "
        "CALL is the drift.)"
    ),
    "retired-architecture-prose": (
        "The phrases \"event program\" / \"event-program\" and \"realm "
        "routing\" / \"realm-routing\" teach the RETIRED model in prose. The "
        "canonical public model is image -> frame -> event stream: an image "
        "loads behavior into a frame, and a frame processes its events as an "
        "EVENT STREAM (not a 'program'); routing is FRAME-scoped (the EP-0013 "
        "multi-realm substrate collapsed under EP-0023/EP-0024, so there is no "
        "'realm routing'). Rewrite to 'event stream' (or, for a Story replay "
        "artifact, the localized field name in an inline `code span`). If the "
        "sentence is DISCUSSING the removal, say so on the same line "
        "('retired', 'removed', 'no longer', 'no realm-routing', …) and the "
        "gate will treat it as removed-context."
    ),
    "retired-substrate-retained-claim": (
        "A prose CLAIM that the deleted EP-0013 realm / app-value / module "
        "substrate is 'retained internal substrate' / 'retained internally' / "
        "that the realm machinery or realm-scoped readers 'remain' as a live "
        "internal seam. EP-0024 DELETED that substrate IN FULL (#4811): there "
        "is no `re-frame.realm` namespace, no installed-app value, no realm-"
        "scoped reader. Frame resolution routes through the process registrar "
        "(`re-frame.registrar` / `re-frame.frame` / `re-frame.image`), not a "
        "retained realm seam. Rewrite the sentence to the current model. If it "
        "is DISCUSSING the deletion ('deleted in full', 'there is no "
        "re-frame.realm namespace', 'gone with the substrate it read'), say so "
        "on the line and the gate treats it as removed-context."
    ),
    "retired-deleted-namespace-read": (
        "A DELETED namespace named as a live read seam: `re-frame.realm/...`, "
        "`re-frame.app-value/...`, or `re-frame.migration/migration-map`. These "
        "namespaces were removed in full by EP-0024 — they no longer exist, so "
        "naming one as a current read/import is drift. A tool reads the host "
        "registrations through the LIVE process registrar (`re-frame.registrar` "
        "/ `re-frame.frame` / `re-frame.image`), not a realm coordinate. If the "
        "sentence is DISCUSSING the removal ('there is no `re-frame.realm` "
        "namespace', 'no `re-frame.realm/installed-app` seam', '... was "
        "removed'), say so on the line and the gate treats it as "
        "removed-context."
    ),
}


def _report(findings: list[Finding], repo_root: Path) -> None:
    sys.stderr.write(
        f"\n{len(findings)} live retired composition-vocabulary hit(s) found on "
        "the teaching surface (spec/Conventions.md §Retired composition "
        "vocabulary — the hard rule):\n\n"
    )
    for f in findings:
        try:
            rel = f.path.relative_to(repo_root)
        except ValueError:
            rel = f.path
        sys.stderr.write(f"  {f.kind}: {rel}:{f.line}\n      {f.snippet}\n")
    families = {k.split(":", 1)[1] for k in (f.kind for f in findings)}
    sys.stderr.write("\nFix:\n")
    for fam in sorted(families):
        sys.stderr.write(f"  * {_FIX_HINTS[fam]}\n")
    sys.stderr.write(
        "\nThe ONLY surface that may carry the retired vocabulary is the "
        "`docs/EP/**` historical record (already allowlisted). Do NOT widen the "
        "allowlist to relegitimise a non-EP surface — that is a rule violation. "
        "Rewrite the example/prose onto `image -> frame -> event stream`. The "
        "normative rule (banned terms, preferred replacements, ordinary-English "
        "allowances, the historical carve-out) is "
        "spec/Conventions.md §Retired composition vocabulary — the hard rule.\n"
    )


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "EP-0023 §retired-composition-vocabulary: fail on a live retired "
            "app/realm/module composition symbol on the public teaching "
            "surface (fenced code outside the narrow historical allowlist)."
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
        help=(
            "Directory (relative to repo-root) to scan. Repeatable. Defaults "
            f"to {list(DEFAULT_SCAN_DIRS)}."
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
            "scripts/_test_fixtures/check_retired_composition_vocab/ and exit."
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

    # The default scan = the doc-surface dirs UNION the discovered per-tool spec
    # trees (rf2-lq99wc). An explicit --scan-dir is a targeted run and uses
    # exactly what was passed (tool specs are not auto-added there).
    if args.scan_dir:
        scan_dirs = list(args.scan_dir)
    else:
        scan_dirs = list(DEFAULT_SCAN_DIRS) + _tool_spec_scan_dirs(repo_root)
    all_findings: list[Finding] = []
    for d in scan_dirs:
        scan_root = repo_root / d
        if not scan_root.exists():
            sys.stderr.write(f"error: scan dir {scan_root} does not exist.\n")
            return 2
        if args.verbose:
            n = sum(1 for _ in _iter_doc_files(scan_root, repo_root))
            sys.stderr.write(f"scanning {n} markdown file(s) under {d}...\n")
        all_findings.extend(scan(scan_root, repo_root=repo_root))

    # rf2-2c8zq6 — the root-support surfaces (README/TESTING/CHANGELOG/the JVM
    # gate scripts, …) are individual files outside any scan dir. Cover them on
    # the DEFAULT invocation (an explicit --scan-dir is a targeted run and skips
    # them so the override stays predictable).
    if not args.scan_dir:
        if args.verbose:
            present = [
                r for r in sorted(_ROOT_SUPPORT_PROSE_FILES)
                if (repo_root / r).is_file()
            ]
            sys.stderr.write(
                f"scanning {len(present)} root-support file(s) for retired "
                "architecture prose...\n"
            )
        all_findings.extend(scan_root_support(repo_root))

    if all_findings:
        _report(all_findings, repo_root)
        return 1
    if args.verbose:
        sys.stderr.write(
            "no live retired composition vocabulary on the teaching surface.\n"
        )
    return 0


# --------------------------------------------------------------------------
# Self-tests (fixture-driven) — prove the gate FIRES on each live shape
# and stays GREEN on every removed-context / sanctioned counterpart.
# --------------------------------------------------------------------------

_SELF_TEST_FIXTURE_ROOT = (
    Path(__file__).resolve().parent
    / "_test_fixtures"
    / "check_retired_composition_vocab"
)


def _witnesses(findings: list[Finding]) -> frozenset[str]:
    """`<family>:<roster entry>` for each finding — what it PROVED, not how
    many there were."""
    return frozenset(
        f"{f.kind.removeprefix('live-retired:')}:{f.detail}" for f in findings
    )


def _run_self_tests(verbose: bool = False) -> int:
    """Scan each fixture file and assert the EXACT set of witnesses it yields.

    A witness is `<family>:<roster entry>` — which of the family's alternatives
    fired, not merely that the family did. Positive fixtures plant a LIVE
    retired symbol inside a code fence on a non-allowlisted-shaped page;
    negative fixtures exercise the counterparts that MUST stay green:
    removed-context prose, an inline code span, a masked `;` comment in a fence,
    the sanctioned `app-db` term, the rewritten image/frame teaching, and a
    non-facade namespace-qualified symbol (an app's own `counter/install!` setup
    hook — the qualifier filter keeps a non-`rf/` qualifier from firing). A
    dedicated allowlist case scans a positive fixture AS IF it were an EP doc
    and asserts it does NOT fire.

    A second block (rf2-2c8zq6) exercises the root-support PROSE-architecture
    family: it scans the prose fixtures AS IF they were a root-support file
    (rel_posix=README.md) so the prose family activates, asserting the live
    "event program" / "realm-routing" teaching FIRES and every removed-context
    / inline-span / in-fence counterpart stays GREEN.

    The counts were already exact, and every fixture single-token — the hole was
    one level down (rf2-57vnc): nothing obliged a ROSTER ENTRY to own a fixture,
    so `realm-ids`, `app-registrations`, `app-requires`, `frame-realm`,
    `rf/app`, two of the three deleted namespaces and three of the five
    retained-claim shapes could each be deleted from their alternation without a
    single case noticing. The coverage assertions at the end close that.
    """
    failures = 0
    covered: set[str] = set()

    def run_case(fixture: str, rel_posix: str, expected: frozenset[str],
                 label: str = "") -> None:
        """Scan one fixture at one surface and assert its exact witness set."""
        nonlocal failures
        tag = f"{label} " if label else ""
        path = _SELF_TEST_FIXTURE_ROOT / fixture
        if not path.is_file():
            sys.stderr.write(
                f"self-test FAIL: {tag}fixture {fixture!r} missing at {path}\n"
            )
            failures += 1
            return
        text = path.read_text(encoding="utf-8", errors="replace")
        findings = _scan_text(path, text, rel_posix=rel_posix)
        actual = _witnesses(findings)
        covered.update(actual)
        if actual != expected:
            failures += 1
            sys.stderr.write(f"self-test FAIL: {tag}{fixture}\n")
            missing = sorted(expected - actual)
            extra = sorted(actual - expected)
            if missing:
                sys.stderr.write(
                    "      DETECTOR DEAD — this fixture plants "
                    f"{', '.join(missing)} and the gate did not see it\n"
                )
            if extra:
                sys.stderr.write(f"      UNEXPECTED: {', '.join(extra)}\n")
            return
        # A set hides duplicates, and a duplicate witness can die unseen.
        if len(findings) != len(actual):
            failures += 1
            sys.stderr.write(
                f"self-test FAIL: {tag}{fixture} has {len(findings)} findings "
                f"for {len(actual)} distinct witness(es) — a duplicate can die "
                "without changing the set. Plant each witness once.\n"
            )
        elif verbose:
            sys.stderr.write(
                f"self-test PASS: {tag}{fixture} "
                f"({', '.join(sorted(actual)) or 'green'})\n"
            )

    _SYMBOL = "retired-construction-symbol:"
    _FACADE = "retired-facade-noun-call:"
    cases: list[tuple[str, frozenset[str]]] = [
        # (fixture relative to fixture-root, exact witness set)
        # --- positives: a LIVE retired symbol in a code fence must FIRE ---
        ("positive/live_install_realm_app.md",
         frozenset({_SYMBOL + "install!"})),
        ("positive/live_reinstall.md",
         frozenset({_SYMBOL + "reinstall!"})),
        ("positive/live_app_owns_inspector.md",
         frozenset({_SYMBOL + "app-owns"})),
        ("positive/live_installed_app_read.md",
         frozenset({_SYMBOL + "installed-app"})),
        # The four strong symbols that had no fixture at all, one per fenced
        # line, each attributed to itself.
        ("positive/live_remaining_strong_symbols.md", frozenset({
            _SYMBOL + "realm-ids",
            _SYMBOL + "app-registrations",
            _SYMBOL + "app-requires",
            _SYMBOL + "frame-realm",
        })),
        ("positive/live_rf_realm_call.md",
         frozenset({_FACADE + "rf/realm"})),
        ("positive/live_rf_module_call.md",
         frozenset({_FACADE + "rf/module"})),
        # `rf/app` was in the alternation and in no fixture.
        ("positive/live_rf_app_call.md",
         frozenset({_FACADE + "rf/app"})),
        # --- negatives: removed-context / sanctioned forms must stay GREEN ---
        ("negative/removed_context_prose.md",          frozenset()),
        ("negative/inline_code_span_mention.md",       frozenset()),
        ("negative/masked_clj_comment_in_fence.md",    frozenset()),
        ("negative/app_db_sanctioned.md",              frozenset()),
        ("negative/rewritten_image_frame_teaching.md", frozenset()),
        # Non-facade namespace-qualified symbols: the internal substrate read
        # (`re-frame.realm/realm-ids`, `re-frame.realm/installed-app`) AND an
        # app's own `counter/install!` setup hook — all share a bare name with
        # a retired facade symbol but are different symbols. Must stay GREEN.
        ("negative/internal_substrate_ns_reads.md",    frozenset()),
    ]
    for fixture, expected in cases:
        # Direct-file scan; the fixture's own posix path is non-allowlisted
        # (it does not start with an allowlist prefix), so positives fire.
        run_case(fixture, fixture, expected)

    # Dedicated allowlist case: the SAME live-residue fixture, scanned as if it
    # were an EP doc, must NOT fire (the docs/EP/ allowlist exempts it).
    allow_fixture = "positive/live_install_realm_app.md"
    allow_path = _SELF_TEST_FIXTURE_ROOT / allow_fixture
    if allow_path.is_file():
        text = allow_path.read_text(encoding="utf-8", errors="replace")
        got = len(_scan_text(
            allow_path, text,
            rel_posix="docs/EP/EP-0013-app-values-and-runtime-realms.md",
        ))
        if got == 0:
            if verbose:
                sys.stderr.write(
                    "self-test PASS: allowlist exempts EP-doc retired example\n"
                )
        else:
            sys.stderr.write(
                "self-test FAIL: allowlist did NOT exempt EP-doc retired "
                f"example (got {got} findings)\n"
            )
            failures += 1
    else:
        sys.stderr.write(
            f"self-test FAIL: allowlist fixture {allow_fixture!r} missing\n"
        )
        failures += 1

    # rf2-2c8zq6 — the root-support PROSE-architecture family. Scanned AS IF the
    # fixture were a root-support file (rel_posix=README.md) so the prose family
    # activates (it is gated to `_ROOT_SUPPORT_PROSE_FILES`).
    _PROSE = "retired-architecture-prose:"
    prose_cases: list[tuple[str, frozenset[str]]] = [
        # --- positives: live retired architecture prose must FIRE ---
        ("positive/live_event_program_prose.md",
         frozenset({_PROSE + "event-program"})),
        ("positive/live_realm_routing_prose.md",
         frozenset({_PROSE + "realm-routing"})),
        # --- negatives: removed-context / inline-span / in-fence stay GREEN ---
        ("negative/removed_context_prose_phrases.md",   frozenset()),
        ("negative/inline_span_field_name.md",          frozenset()),
        ("negative/phrase_in_fence_not_prose.md",       frozenset()),
    ]
    for fixture, expected in prose_cases:
        # rel_posix=README.md makes _scan_text run the prose-architecture family.
        run_case(fixture, "README.md", expected, label="prose")

    # rf2-lq99wc — the DELETED-SUBSTRATE families (retained-internal claim +
    # deleted-namespace read). These run over the prose of EVERY scanned file in
    # `_scan_text`, so a plain non-allowlisted rel_posix activates them. The
    # positives plant the audit-found residue class (a "retained internal
    # substrate" claim; a `re-frame.realm/...` live read); the negatives prove
    # the removed-context window keeps the legitimate deletion-discussion shapes
    # (spec/Conventions.md / the Xray specs / the api-manifest) GREEN, and that
    # the EP-0018 `reg-event-ctx` "retained internally" note (different subject,
    # no realm adjacency) does NOT fire.
    _CLAIM = "retired-substrate-retained-claim:"
    _NSREAD = "retired-deleted-namespace-read:"
    deleted_substrate_cases: list[tuple[str, frozenset[str]]] = [
        # --- positives: live deleted-substrate-as-live drift must FIRE ---
        # One line, three overlapping claim shapes — the witness records all
        # three rather than crediting the fixture with only the first.
        ("positive/live_retained_internal_substrate.md",
         frozenset({_CLAIM + "retained-internal+retained-as-internal-substrate"
                    "+substrate-noun-retained"})),
        # The two claim shapes that had no fixture: verb-first, and survival
        # phrased as continuity.
        ("positive/live_retained_substrate_noun_claim.md",
         frozenset({_CLAIM + "retained-substrate-noun"})),
        ("positive/live_realm_readers_remain_claim.md",
         frozenset({_CLAIM + "realm-readers-remain"})),
        ("positive/live_deleted_namespace_read.md",
         frozenset({_NSREAD + "re-frame.realm/"})),
        # The two deleted namespaces that had no fixture.
        ("positive/live_deleted_app_value_ns_read.md",
         frozenset({_NSREAD + "re-frame.app-value/"})),
        ("positive/live_deleted_migration_map_read.md",
         frozenset({_NSREAD + "re-frame.migration/migration-map"})),
        # --- negatives: removed-context / different-subject stay GREEN ---
        ("negative/removed_context_deleted_substrate.md", frozenset()),
        ("negative/reg_event_ctx_retained_internally.md", frozenset()),
        # the pre-existing internal-substrate fixture reads the LIVE namespaces
        # (`re-frame.registrar/` / `re-frame.frame/`), NOT a deleted one.
        ("negative/internal_substrate_ns_reads.md",       frozenset()),
    ]
    for fixture, expected in deleted_substrate_cases:
        # A plain non-allowlisted rel_posix runs the deleted-substrate families.
        run_case(fixture, fixture, expected, label="deleted-substrate")

    # rf2-7gmtz5 — the TOOL-SPEC install!-convention exemption. Scanned AS IF the
    # fixture lived under `tools/xray/spec/` so `_scan_text` lights the
    # tool_spec_surface flag: a bare `install!` / `reinstall!` panel convention
    # stays GREEN, but a realm-specific retired symbol still FIRES there.
    tool_spec_cases: list[tuple[str, frozenset[str]]] = [
        ("negative/tool_panel_install_convention.md",   frozenset()),
        ("positive/tool_spec_realm_symbol_fires.md",
         frozenset({_SYMBOL + "dispose-realm!"})),
    ]
    for fixture, expected in tool_spec_cases:
        # A tools/<tool>/spec/ rel_posix lights the tool_spec_surface flag.
        run_case(fixture, f"tools/xray/spec/{Path(fixture).name}", expected,
                 label="tool-spec")

    # ----------------------------------------------------------------------
    # ROSTER COVERAGE — a roster entry without a fixture is a hard failure.
    # ----------------------------------------------------------------------
    #
    # This is the assertion the four families were missing (rf2-57vnc). Every
    # case above already asserted an exact count over a single-token fixture;
    # what nothing asserted was that each entry in each alternation had a case
    # AT ALL. Deleting `realm-ids`, `frame-realm`, `rf/app` or two thirds of the
    # deleted-namespace alternation left the whole self-test green.
    #
    # `covered` is accumulated from the witnesses the fixtures actually
    # produced, and the joined multi-match details are split so a line matching
    # three claim shapes credits all three.
    covered_entries: set[str] = set()
    for witness in covered:
        family, _, detail = witness.partition(":")
        for entry in detail.split("+"):
            covered_entries.add(f"{family}:{entry}")

    rosters: tuple[tuple[str, str, tuple[str, ...]], ...] = (
        ("_STRONG_SYMBOLS", "retired-construction-symbol", _STRONG_SYMBOLS),
        ("_RETIRED_FACADE_NOUNS", "retired-facade-noun-call",
         tuple(f"rf/{n}" for n in _RETIRED_FACADE_NOUNS)),
        ("_DELETED_NS_READS", "retired-deleted-namespace-read",
         tuple(n for n, _p in _DELETED_NS_READS)),
        ("_RETAINED_CLAIM_FORMS", "retired-substrate-retained-claim",
         tuple(n for n, _p in _RETAINED_CLAIM_FORMS)),
    )
    for roster_name, family, entries in rosters:
        uncovered = [e for e in entries
                     if f"{family}:{e}" not in covered_entries]
        if uncovered:
            failures += 1
            sys.stderr.write(
                f"self-test FAIL: {roster_name} entr(y/ies) with no fixture of "
                f"their own: {', '.join(uncovered)}\n"
                "      Plant each one in a positive fixture and declare its "
                "witness. An alternation entry no case reaches can be deleted, "
                "or typo'd, and stay green forever.\n"
            )

    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    total = (len(cases) + 1 + len(prose_cases) + len(deleted_substrate_cases)
             + len(tool_spec_cases))
    if verbose:
        sys.stderr.write(
            f"all {total} self-tests passed; every entry in "
            f"{', '.join(r for r, _f, _e in rosters)} owns a fixture.\n"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
