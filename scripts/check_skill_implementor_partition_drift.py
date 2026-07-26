#!/usr/bin/env python3
"""Partition-aware + HTTP-fx + API-name + reply-contract drift guard for the
re-frame2-implementor skill (rf2-whsb0c + rf2-6c59ob + rf2-3fc89f.34).

The implementor skill is a control surface: API names and contract shapes copied
from it become a port author's tests, docs, and public surface. Two senior
reviews caught the skill drifting from shipped reality:

  * **rf2-whsb0c** — partition-aware teaching drift after EP-0001 landed the
    two-partition frame (app-db `:rf.db/app` / runtime-db `:rf.db/runtime`):
    stale `register-listener!` / `deregister-listener!` naming, and the
    public-lifecycle verb `destroy-adapter!` blurred with the adapter-spec map's
    internal `:dispose-adapter!` slot.
  * **rf2-6c59ob** — HTTP-fx + data-classification summary drift: the reference
    tour described the Spec 014 managed HTTP lifecycle as "the `:http` fx"
    (the canonical surface is `:rf.http/managed`), and the SKILL.md / tour
    summaries implied that *every* classification site accepts a
    `{:sensitive [paths] :large [paths]}` metadata map — contradicting the
    `reg-machine` schema-first exception (no top-level `:sensitive` / `:large`
    keys). EP-0015 then made the model owner-owned (one declaration surface per
    owner): durable app-db classification was then frame-owned (the frame
    config's `:sensitive`/`:large {:app-db ...}` keys — since moved to the
    EP-0025 commit-plane effects); machine `:data`, resource data/params,
    and HTTP bodies classify via per-slot `:sensitive?` / `:large?` schema props
    (the schema-first route — still NO top-level `reg-machine` keys); transient
    payloads classify via `:sensitive`/`:large` registration metadata. Rule 4
    below polices exactly the surviving invariant: a `reg-machine` line must
    never imply top-level `:sensitive` / `:large` keys.

This guard makes each of those re-introductions a build failure. It scans the
user-facing implementor docs and asserts:

  1. **No `deregister-listener!`** — the public trace-listener removal verb is
     `unregister-listener!` (spec/API.md, re_frame/trace.cljc).
  2. **No bare `:http` described as the Spec 014 managed/framework HTTP fx** —
     the managed surface is `:rf.http/managed`; bare `:http` is app/user/
     implementation-specific. (A line may mention bare `:http` only when it
     also marks it user/app/implementation-specific OR explicitly denies it is
     the managed surface.)
  3. **No public `dispose-adapter!`** — the public teardown verb is
     `destroy-adapter!`; `:dispose-adapter!` is the adapter-spec map's internal
     lifecycle slot. A line may use `:dispose-adapter!` / `dispose-adapter!`
     only when it marks it as the adapter-spec map key / internal slot (i.e.
     it is paired with `destroy-adapter!` or the words "slot" / "map key" /
     "adapter-spec" / "internal").
  4. **No `reg-machine` line implying top-level `:sensitive` / `:large`** — the
     machine `:data` exception is schema-first (`:data-schema` props), with NO
     metadata-bearing `reg-machine` arity.
  5. **No `rf2-XXXX` bead ids in user-facing leaves** — SKILL.md, README.md, and
     every `references/*.md` leaf carry NO internal `rf2-` tracker ids (L7 in the
     skill's `spec/design.md`). Bead ids are monorepo-internal workflow noise; an
     external port author has no `bd` and no bead corpus, so a leaked id is
     unexplained context that invites treating bead history as normative evidence.
     Use public, stable evidence instead — spec section links, fixture names, API
     entries, or a fully-qualified public PR link where it helps an external
     reader. The skill's own `spec/` meta-docs MAY mention bead ids (authoring /
     internal context) and are out of this scan's scope.
  6. **Unified reply-address / envelope contract** — after the unified reply-
     addressing migration, the app-facing authoring key is `:reply-to`;
     `:rf/reply-to` is the internal / normalized descriptor it lowers to. HTTP
     `:on-success` / `:on-failure` are SPLIT ROUTING sugar that receive the
     IDENTICAL canonical reply map (which event routes on success vs failure) —
     they do NOT reshape the reply into a second, narrower payload dialect. The
     transient reply map spells the work identity `:rf.reply/work-id`; bare
     `:work/id` is the durable ledger / verification / abstract-attempt identity.
     A user-facing leaf that reintroduces the retired teaching (`:rf/reply-to`
     as the public key / HTTP sugar as a reshaped envelope / bare `:work/id` on
     the reply map) can turn a conforming new port non-conforming. This rule is
     context-sensitive: legitimate INTERNAL `:rf/reply-to` descriptor mentions
     still pass.
  7. **Frame-root lifecycle — two realizations kept present and distinct**
     (rf2-vxgfnd.278). The merged frame-root/frame-provider split corrected the
     synthesis but left the implementor guide universalizing the *legacy*
     adapters' commit-owned two-pass `useLayoutEffect` ENSURE as THE frame-root
     lifecycle. The compiled `re-frame.ui` substrate runs ENSURE at **host
     preflight** — before React/JVM render (#5711) — a DIFFERENT lifecycle:
     scope-only emitted component, evidence-only commit reporting,
     `:mount-incomplete` on an aborted host attempt, a surgical `:refresh` (not
     the legacy reconfigured error) on a same-owner config change. This rule is a
     positive-PRESENCE scan (not the per-line denylist above): it fails if either
     realization disappears or collapses into the other, if a Spec-002 frame-root
     / frame-provider heading link goes missing OR no longer resolves to a real
     Spec-002 heading, if either frame boundary drops out of the OWNING core
     artifact inventory row in `spec/Conventions.md`, or if the synthesis §8
     reverts preflight to future work. It also holds a CAUSAL, source-backed
     assertion over the compiled client's THREE production render paths —
     existing-root refresh, fresh mount, and the split `render!*` — that EACH host
     render (`(.render …)`) is preceded by its OWN frame preflight
     (`(run-preflight! …)` → `execute-frame-plans!`), and that the one-shot fresh
     mount additionally preflights before it creates the React root
     (`(run-preflight! …)` → `(rdc/createRoot …)` → `(.render …)`). The two honest
     host paths are stated, not universalized: one-shot `mount*` preflights before
     `createRoot` AND the first render, while the intentional split API's
     `create-root*` allocates/registers the React root FIRST by design (no plans
     yet) and `render!*` later preflights before every render — so the split path
     is never falsely claimed to preflight before `create-root*`. The check is a
     bounded, PER-FUNCTION token-order scan (rf2-8cncz): the client is partitioned
     into its top-level `(defn ...)` bodies and preflight credit is scoped to the
     SAME body as the render it covers, so one function's preflight can never
     credit another's render (a preflight migrated into `create-root*` cannot
     silence `render!*`'s uncredited render), and reader-discarded (`#_`) /
     dead-conditional (`(when false ...)`) preflights are neutralized before the
     scan and never count. It stays a bounded lexical bracket-matcher; no general
     Markdown or full Clojure parser is added. (Adapter *status* — which
     adapters live on and which are removed — is not scanned here: it is owned
     by `scripts/check_adapter_disposition.py`, per rf2-vxgfnd.290. The
     `[REACT-ADAPTERS]` token below is a lifecycle-PARTITION marker, not a
     status label.)

     Rule 7's arms are individually skippable — each reads a source file, and a
     missing file used to leave its arm silently unevaluated behind a SETUP
     error whose own text invited the edit that retires it ("update the *_FILE
     constants"). A skipped assertion is not a passing one, so the arms are
     declared in `LIFECYCLE_ARM_SOURCES` and the guard FAILS when any declared
     arm did not run. Deleting a `*_FILE` constant therefore no longer buys
     silence — it trades one loud failure for another. Retiring an arm is a
     deliberate, self-documenting act: delete its row from
     `LIFECYCLE_ARM_SOURCES` and record that the contract it held is unguarded.

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_implementor_partition_drift.py
    python scripts/check_skill_implementor_partition_drift.py --verbose
    python scripts/check_skill_implementor_partition_drift.py --ci
    python scripts/check_skill_implementor_partition_drift.py --self-test

rf2-whsb0c + rf2-6c59ob.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import unicodedata
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Force UTF-8 on output streams — the corpus carries em-dash / arrows etc. and
# the default Windows console codec (cp1252) would crash on them (rf2 is
# maintained on Windows; the gate also runs on Linux CI).
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover - non-TextIO stream
        pass

SKILL_DIR = REPO_ROOT / "skills" / "re-frame2-implementor"

SCANNED_FILES = [
    SKILL_DIR / "SKILL.md",
    SKILL_DIR / "references" / "phase-2-impl-order.md",
    SKILL_DIR / "references" / "reference-impl-tour.md",
    SKILL_DIR / "references" / "decision-record.md",
    SKILL_DIR / "references" / "phase-1-decisions.md",
    SKILL_DIR / "references" / "cardinal-rules.md",
]


def _beadid_scanned_files() -> list[Path]:
    """User-facing leaves for the no-bead-ids scan (Rule 5): SKILL.md,
    README.md, and every `references/*.md` leaf. Globbed (not hand-listed) so a
    newly-added reference leaf is covered automatically. The skill's `spec/`
    meta-docs are deliberately NOT included — L7 permits bead ids there."""
    files = [SKILL_DIR / "SKILL.md", SKILL_DIR / "README.md"]
    files.extend(sorted((SKILL_DIR / "references").glob("*.md")))
    return files

# --- Rule 1: deregister-listener! is never the verb (it is unregister-listener!).
DEREGISTER_RE = re.compile(r"deregister-listener!")

# --- Rule 2: bare `:http` as the managed / Spec-014 framework surface.
# Fires when a line names bare `:http` as an fx/effect, UNLESS the same line
# marks it user/app/implementation-specific OR denies it is the managed surface.
BARE_HTTP_FX_RE = re.compile(r"`:http`\s*(?:fx|effect)|the\s*`:http`\s*fx", re.IGNORECASE)
HTTP_ALLOW_RE = re.compile(
    r"user-registered|user/app|app/user|app-level|application-level"
    r"|implementation-specific|impl-specific|app/user/implementation"
    r"|NOT a reserved|no reserved bare|not the (?:spec 014|managed)"
    r"|don't describe the managed",
    re.IGNORECASE,
)

# --- Rule 3: public dispose-adapter! (the public verb is destroy-adapter!).
# Fires when a line uses dispose-adapter! / :dispose-adapter! UNLESS the line
# marks it as the adapter-spec map key / internal lifecycle slot.
DISPOSE_ADAPTER_RE = re.compile(r":?dispose-adapter!")
DISPOSE_ALLOW_RE = re.compile(
    r"map key|map's|adapter-spec|adapter spec|internal|slot", re.IGNORECASE
)

# --- Rule 4: reg-machine line implying top-level :sensitive / :large keys.
REG_MACHINE_RE = re.compile(r"reg-machine")
# Phrasing that asserts reg-machine takes the metadata map. We only flag when a
# reg-machine line ALSO carries a {:sensitive ... :large ...}-style claim AND
# does NOT carry the schema-first / no-metadata-key disclaimer.
SENS_LARGE_MAP_RE = re.compile(r":sensitive\b|:large\b")
MACHINE_EXCEPTION_RE = re.compile(
    r"schema-first|data-schema|NO\s*`?:sensitive|no\s+`?:sensitive"
    r"|two-arity|no metadata|not.*reg-machine|never.*reg-machine"
    r"|no top-level|accepts no",
    re.IGNORECASE,
)

# --- Rule 5: no `rf2-XXXX` internal bead ids in the user-facing leaves.
# The id shape is `rf2-` + alphanumerics (some carry a `.N` sub-task suffix,
# e.g. `rf2-d3fb7.1`). Word-boundary the prefix so it doesn't match inside a
# longer token. Scanned over a SEPARATE file set (BEADID_SCANNED_FILES below):
# SKILL.md + README.md + every references/*.md. The skill's own spec/ meta-docs
# are deliberately excluded (L7 permits bead ids there).
BEADID_RE = re.compile(r"\brf2-[a-z0-9]+(?:\.[0-9]+)?\b")

# --- Rule 6: unified reply-address / envelope contract drift (rf2-3fc89f.34).
# Three context-sensitive sub-checks. Each is deliberately narrow so legitimate
# INTERNAL `:rf/reply-to` descriptor mentions (the normalized target) still pass
# — the rules only fire on the retired teaching, not on the token itself.

# 6a — `:rf/reply-to` claimed as the PUBLIC / app-facing / authoring target key.
# The public authoring key is `:reply-to`; `:rf/reply-to` is the internal /
# normalized descriptor it lowers to. Fires on "<public-cue> [reply][target] key
# … `:rf/reply-to`" where NO backtick separates the key-claim from `:rf/reply-to`
# — so a correct "authoring key is `:reply-to` … internal `:rf/reply-to`
# descriptor" line (whose first backtick after "key" is `:reply-to`) does NOT
# match, while "public target key is `:rf/reply-to`" does.
REPLY_PUBLIC_KEY_RE = re.compile(
    r"(?:canonical\s+public|public|app-facing|app-authored|authoring|call-site)"
    r"\s+(?:reply[\s-]*)?(?:target\s+)?key[^`]*`:rf/reply-to`",
    re.IGNORECASE,
)

# 6b — HTTP `:on-success` / `:on-failure` described as RESHAPING the reply into a
# second, narrower payload dialect. They are split ROUTING sugar that receive the
# IDENTICAL canonical reply map. Fires on `reshap*` in a reply / HTTP-sugar
# context UNLESS the line DENIES it (not / never / no reshape) or asserts the
# IDENTICAL / same reply envelope.
RESHAPE_RE = re.compile(r"reshap\w*", re.IGNORECASE)
RESHAPE_CTX_RE = re.compile(
    r":on-success|:on-failure|reply map|reply payload|http payload|envelope",
    re.IGNORECASE,
)
RESHAPE_ALLOW_RE = re.compile(
    r"\bnot\b|n't|\bnever\b|\bno\s+reshap|identical|same\s+(?:canonical\s+)?reply",
    re.IGNORECASE,
)

# 6c — bare `:work/id` placed on the TRANSIENT reply map / payload. Its spelling
# there is `:rf.reply/work-id`; bare `:work/id` is the durable ledger /
# verification / abstract-attempt identity. Fires on a reply-map/payload context
# carrying bare `:work/id` UNLESS the line also spells `:rf.reply/work-id` or
# marks the id as the durable ledger / verification / abstract identity.
REPLYMAP_CTX_RE = re.compile(
    r"reply map|reply payload|http payload|reply envelope", re.IGNORECASE
)
BARE_WORKID_RE = re.compile(r"`:work/id`")
WORKID_ALLOW_RE = re.compile(
    r":rf\.reply/work-id|ledger|durable|verification|abstract", re.IGNORECASE
)


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def line_problems(line: str) -> list[str]:
    """Return drift labels for a single line (empty when clean)."""
    problems: list[str] = []

    if DEREGISTER_RE.search(line):
        problems.append(
            "DEREGISTER-LISTENER: use `unregister-listener!` — `deregister-"
            "listener!` is not the public trace-listener removal verb "
            "(spec/API.md, re_frame/trace.cljc)."
        )

    if BARE_HTTP_FX_RE.search(line) and not HTTP_ALLOW_RE.search(line):
        problems.append(
            "BARE-HTTP-MANAGED: the Spec 014 managed HTTP surface is "
            "`:rf.http/managed` — bare `:http` is app/user/implementation-"
            "specific. Don't describe the managed lifecycle as 'the `:http` "
            "fx' (mark bare `:http` user/app-level if you mention it)."
        )

    if DISPOSE_ADAPTER_RE.search(line) and not DISPOSE_ALLOW_RE.search(line):
        problems.append(
            "PUBLIC-DISPOSE-ADAPTER: the public teardown verb is "
            "`destroy-adapter!`; `:dispose-adapter!` is the adapter-spec map's "
            "internal lifecycle slot. Mark it as the map key / internal slot, "
            "or use `destroy-adapter!` for the public verb."
        )

    if (
        REG_MACHINE_RE.search(line)
        and SENS_LARGE_MAP_RE.search(line)
        and not MACHINE_EXCEPTION_RE.search(line)
    ):
        problems.append(
            "REG-MACHINE-MARKS: `reg-machine` is two-arity and accepts NO "
            "top-level `:sensitive` / `:large` keys — machine `:data` is the "
            "schema-first exception (`:data-schema` props / runtime path-list). "
            "Don't imply `reg-machine` takes the marking metadata map."
        )

    if REPLY_PUBLIC_KEY_RE.search(line):
        problems.append(
            "REPLY-PUBLIC-KEY: the app-facing reply-target authoring key is "
            "`:reply-to` (the unified spelling shared by HTTP / resources / "
            "mutations); `:rf/reply-to` is the internal / normalized descriptor "
            "it lowers to, NOT a public spelling. Don't teach `:rf/reply-to` as "
            "the public / app-facing target key (spec/Managed-Effects.md "
            "§The reply target)."
        )

    if (
        RESHAPE_RE.search(line)
        and RESHAPE_CTX_RE.search(line)
        and not RESHAPE_ALLOW_RE.search(line)
    ):
        problems.append(
            "REPLY-HTTP-RESHAPE: HTTP `:on-success` / `:on-failure` are SPLIT "
            "ROUTING sugar that receive the IDENTICAL canonical reply map (which "
            "event routes on success vs failure) — they do NOT reshape the reply "
            "into a second, narrower payload dialect. Don't describe HTTP reply "
            "sugar as reshaping the envelope (spec/Managed-Effects.md "
            "§The reply target / Spec 014)."
        )

    if (
        REPLYMAP_CTX_RE.search(line)
        and BARE_WORKID_RE.search(line)
        and not WORKID_ALLOW_RE.search(line)
    ):
        problems.append(
            "REPLY-WORKID-SPELLING: the TRANSIENT reply map spells the work "
            "identity `:rf.reply/work-id`; bare `:work/id` is the durable ledger "
            "/ verification / abstract-attempt identity. Don't put bare "
            "`:work/id` on the reply map (one fact, two spellings across the "
            "record↔reply boundary)."
        )

    return problems


def find_drift(files: list[Path]) -> tuple[list[str], int]:
    problems: list[str] = []
    lines_checked = 0
    for path in files:
        if not path.is_file():
            problems.append(
                f"SETUP: expected implementor-skill file missing: "
                f"{path.relative_to(REPO_ROOT)} — the guard's file list drifted "
                "from the skill layout; update SCANNED_FILES."
            )
            continue
        for lineno, line in enumerate(_slurp(path).splitlines(), start=1):
            lines_checked += 1
            for label in line_problems(line):
                rel = path.relative_to(REPO_ROOT)
                problems.append(f"{rel}:{lineno}: {label}\n    {line.strip()}")
    return problems, lines_checked


def find_beadid_drift(files: list[Path]) -> tuple[list[str], int]:
    """Rule 5 scan: no `rf2-XXXX` bead id in any user-facing leaf (L7).

    Returns (drift messages, lines scanned). A missing file is a SETUP error
    — except README.md / a references leaf glob can never go missing silently,
    so the SKILL.md anchor missing is the only hard SETUP signal here."""
    problems: list[str] = []
    lines_checked = 0
    if not (SKILL_DIR / "SKILL.md").is_file():
        problems.append(
            "SETUP: re-frame2-implementor/SKILL.md missing — the no-bead-ids "
            "scan anchor drifted from the skill layout."
        )
    for path in files:
        if not path.is_file():
            continue
        for lineno, line in enumerate(_slurp(path).splitlines(), start=1):
            lines_checked += 1
            for m in BEADID_RE.finditer(line):
                rel = path.relative_to(REPO_ROOT)
                problems.append(
                    f"{rel}:{lineno}: BEAD-ID-LEAK: `{m.group(0)}` is an "
                    "internal tracker id — user-facing leaves (SKILL.md / "
                    "README.md / references/*.md) carry NO `rf2-` ids (L7). "
                    "Replace with public, stable evidence: a spec section link, "
                    "a fixture name, an API entry, or a fully-qualified public "
                    "PR link. (bead ids stay only in the skill's spec/ "
                    f"meta-docs.)\n    {line.strip()}"
                )
    return problems, lines_checked


# ---------------------------------------------------------------------------
# Rule 7 — frame-root lifecycle realization guard (rf2-vxgfnd.278). A
# positive-PRESENCE scan (distinct from the per-line denylist above): the two
# frame-root lifecycles must stay present and distinct, their Spec-002 heading
# links resolvable, and a small source-backed assertion holds that the compiled
# client preflights BEFORE it renders the host root.
# ---------------------------------------------------------------------------

PHASE2_FILE = SKILL_DIR / "references" / "phase-2-impl-order.md"
UI_CLIENT_FILE = (
    REPO_ROOT / "implementation" / "ui" / "src" / "re_frame" / "ui" / "client.cljs"
)
UI_FRAMES_FILE = (
    REPO_ROOT / "implementation" / "ui" / "src" / "re_frame" / "ui" / "frames.cljc"
)
CONVENTIONS_FILE = REPO_ROOT / "spec" / "Conventions.md"
SPEC_002_FILE = REPO_ROOT / "spec" / "002-Frames.md"

FRAME_ROOT_ANCHOR = "#frame-root--the-ensure-component-cljs-reference"
FRAME_PROVIDER_ANCHOR = "#frame-provider--the-scope-only-component-cljs-reference"

# --- Rule 7 arm coverage (rf2-0dbvy) ----------------------------------------
# Every Rule-7 assertion arm, and the source texts it needs in order to RUN.
# An arm whose sources are all present is evaluated; an arm missing any source
# is SKIPPED — and a skip is not a pass, so `lifecycle_realization_problems`
# fails on any declared arm that did not run.
#
# This map is the requirement declaration, held SEPARATELY from the `*_FILE`
# constants it depends on. That separation is the whole point: the old SETUP
# error told the reader to "update the *_FILE constants", and deleting the two
# `ui/` constants cleared the error AND retired the L5 causal assertion in one
# stroke, with nothing left to notice (found by the F6e deletion probe).
# Deleting a constant now trades the SETUP failure for an ARM-NOT-RUN failure.
# Retiring an arm is a deliberate second edit — delete its row here, and record
# that the contract it held is henceforth unguarded.
LIFECYCLE_ARM_SOURCES: dict[str, tuple[str, ...]] = {
    "L1-compiled-realization": ("phase2",),
    "L2-react-adapters": ("phase2",),
    "L4-spec002-links-present": ("phase2",),
    "L4-spec002-links-resolve": ("phase2", "spec002"),
    "L5-client-preflight-causality": ("client",),
    "L5-frames-executor": ("frames",),
    "L6-inventory-row": ("conventions",),
}


def lifecycle_arms_run(texts: dict[str, str | None]) -> set[str]:
    """The declared arms whose every source text is present — i.e. the arms
    that actually get evaluated for this input."""
    return {
        arm
        for arm, sources in LIFECYCLE_ARM_SOURCES.items()
        if all(texts.get(src) is not None for src in sources)
    }


# The compiled client's frame-lifecycle CALL forms. Keyed on the leading paren so
# docstring / backtick mentions (`(run-preflight! …)`, `` `.render` ``,
# `` `createRoot` ``) never register as calls.
PREFLIGHT_CALL = "(run-preflight!"
CREATE_ROOT_CALL = "(rdc/createRoot"
HOST_RENDER_CALL = "(.render "


def _client_call_order(client_text: str) -> list[str]:
    """The ordered kinds of a text chunk's frame-lifecycle CALL forms: `'pre'`
    (`(run-preflight!`), `'create'` (`(rdc/createRoot`), `'render'` (`(.render `)
    — sorted by source position. A bounded, lexical token stream (no Clojure
    parser). Called PER top-level function body (see `_top_level_form_regions`), so
    the returned order is that ONE function's calls — never a file-wide pool.

    Over the whole compiled client the three production render paths and the two
    root allocations show up, in document order, as:

        pre, render,  pre, create, render,  create,  pre, render
        └ same-root ┘ └──── fresh mount ───┘ └split┘ └─ render!* ─┘

    but `_preflight_causal_problems` scopes credit to each owning function, so
    `create-root*`'s lone `create` can never donate to `render!*`'s `render`."""
    events: list[tuple[int, str]] = []
    for kind, needle in (
        ("pre", PREFLIGHT_CALL),
        ("create", CREATE_ROOT_CALL),
        ("render", HOST_RENDER_CALL),
    ):
        start = 0
        while True:
            i = client_text.find(needle, start)
            if i == -1:
                break
            events.append((i, kind))
            start = i + len(needle)
    events.sort()
    return [kind for _, kind in events]


# --- Per-function partition + dead-form neutralization (rf2-8cncz) -----------
# The causal preflight assertion is scoped to each top-level function body and
# ignores statically-dead preflights (reader-discarded `#_…` / dead-conditional
# `(when false …)`). Both are bounded lexical passes — a bracket-matcher that
# honours strings / char literals / `;` comments — NOT a general Clojure parser.

_TOP_FORM_OPEN_RE = re.compile(r"^\(", re.MULTILINE)
_WHEN_FALSE_RE = re.compile(r"\(when\s+false(?![\w!?*+./<>=-])")


def _skip_form(text: str, i: int) -> int:
    """Index just past the balanced bracketed form OPENING at text[i] (`([{`).
    Honours `"..."` strings (with `\\"` escapes), `\\x` char literals, and `;` line
    comments — enough to balance the small dead regions this guard neutralizes.
    Returns len(text) on an unbalanced tail. Not a Clojure parser."""
    close = {"(": ")", "[": "]", "{": "}"}
    stack = [close[text[i]]]
    j, n = i + 1, len(text)
    while j < n and stack:
        c = text[j]
        if c == "\\":                       # char literal \x — skip the escaped char
            j += 2
            continue
        if c == '"':                        # string — skip to its close
            j += 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            continue
        if c == ";":                        # line comment — skip to EOL
            k = text.find("\n", j)
            j = n if k == -1 else k
            continue
        if c in close:
            stack.append(close[c])
        elif c == stack[-1]:
            stack.pop()
        j += 1
    return j


def _skip_datum(text: str, j: int) -> int:
    """Index just past ONE datum starting at/after text[j] (leading whitespace
    skipped): a bracket form, a string, a char literal, or a bare atom/symbol/
    keyword. Used to bound the form a `#_` reader-discard drops."""
    n = len(text)
    while j < n and text[j].isspace():
        j += 1
    if j >= n:
        return j
    c = text[j]
    if c in "([{":
        return _skip_form(text, j)
    if c == '"':
        k = j + 1
        while k < n:
            if text[k] == "\\":
                k += 2
                continue
            if text[k] == '"':
                return k + 1
            k += 1
        return n
    if c == "\\":                           # char literal \x
        return min(j + 2, n)
    while j < n and not text[j].isspace() and text[j] not in '()[]{};"':
        j += 1
    return j


def _neutralize_dead_forms(text: str) -> str:
    """Blank out statically-dead preflight forms so they cannot earn credit:
    reader-discarded `#_<form>` and dead-conditional `(when false …)` blocks. A
    two-pass bounded lexical rewrite that copies strings / char literals / `;`
    comments verbatim (so a literal `#_` or `(when false` inside a docstring is
    never mistaken for code). Live forms are returned unchanged."""

    def _copy_or(src: str, drop) -> str:
        out: list[str] = []
        i, n = 0, len(src)
        while i < n:
            c = src[i]
            if c == '"':                    # copy a string datum verbatim
                end = _skip_datum(src, i)
                out.append(src[i:end])
                i = end
                continue
            if c == ";":                    # copy a line comment verbatim
                k = src.find("\n", i)
                k = n if k == -1 else k
                out.append(src[i:k])
                i = k
                continue
            if c == "\\":                   # copy a char literal verbatim
                out.append(src[i:i + 2])
                i += 2
                continue
            handled, i2 = drop(src, i)
            if handled:
                i = i2
                continue
            out.append(c)
            i += 1
        return "".join(out)

    def _drop_discard(src: str, i: int):
        if src[i] == "#" and i + 1 < len(src) and src[i + 1] == "_":
            return True, _skip_datum(src, i + 2)   # drop `#_` + the discarded datum
        return False, i

    def _drop_when_false(src: str, i: int):
        if src[i] == "(" and _WHEN_FALSE_RE.match(src, i):
            return True, _skip_form(src, i)        # drop the whole dead block
        return False, i

    return _copy_or(_copy_or(text, _drop_discard), _drop_when_false)


def _form_label(region_text: str) -> str:
    """Best-effort name of a top-level form for diagnostics — the symbol after
    `defn` / `defn-` / `def…`, skipping `^meta`. Falls back to a short prefix."""
    m = re.match(r"\((?:defn-?|def\w*)\s+(?:\^\S+\s+)*([^\s()\[\]{}]+)", region_text)
    return m.group(1) if m else region_text.split("\n", 1)[0][:40].strip()


def _top_level_form_regions(text: str) -> list[tuple[str, str]]:
    """Partition the client into its top-level forms — one `(name, body)` per
    column-0 `(` opener (well-formatted Clojure indents every nested form, so a
    line-initial `(` reliably marks a NEW top-level form). Each needle-bearing
    body is thus scoped to its owning function; credit never crosses the boundary.
    A bounded lexical split, not a reader."""
    opens = [m.start() for m in _TOP_FORM_OPEN_RE.finditer(text)]
    regions: list[tuple[str, str]] = []
    for idx, s in enumerate(opens):
        e = opens[idx + 1] if idx + 1 < len(opens) else len(text)
        body = text[s:e]
        regions.append((_form_label(body), body))
    return regions


def preflight_scan(client_text: str) -> dict[str, object]:
    """The per-function credit walk over the compiled client, returned as a
    POPULATION rather than a verdict: `forms` top-level forms partitioned,
    `renders` host renders found, `caused` of them preceded by their own live
    preflight, the `uncredited` function names, and whether the one-shot
    `pre → create → render` triple is present. Reported by `--verbose` so a
    scan that covered nothing is visible rather than merely exit-0."""
    total_renders = 0
    total_caused = 0
    one_shot = False
    uncredited: list[str] = []
    regions = _top_level_form_regions(client_text)

    for name, body in regions:
        seq = _client_call_order(_neutralize_dead_forms(body))
        armed = 0
        caused = 0
        renders = 0
        for kind in seq:
            if kind == "pre":
                armed += 1
            elif kind == "render":
                renders += 1
                if armed > 0:
                    caused += 1
                    armed = 0  # a render consumes its function's pending preflight(s)
        total_renders += renders
        total_caused += caused
        if caused != renders:
            uncredited.append(name)
        if any(
            seq[i] == "pre" and seq[i + 1] == "create" and seq[i + 2] == "render"
            for i in range(len(seq) - 2)
        ):
            one_shot = True

    return {
        "forms": len(regions),
        "renders": total_renders,
        "caused": total_caused,
        "uncredited": uncredited,
        "one_shot": one_shot,
    }


def _preflight_causal_problems(client_text: str) -> list[str]:
    """CAUSAL source assertions over the compiled client's render paths, scoped
    PER top-level function body (rf2-8cncz — the old whole-file substring pool let
    a preflight in one function credit another's render, and a reader-discarded /
    dead-conditional preflight still counted as live).

    A — render causality (per function): within EACH function body, every host
        render is preceded by its OWN LIVE frame preflight. Dead forms (`#_…`,
        `(when false …)`) are neutralized first, then a per-function credit walk
        arms one credit per preflight; each render spends a credit (and consumes
        any surplus). Fires if any function has a render its own body does not
        preflight — a removed / reordered / reader-discarded / dead-conditional
        preflight on the existing-root, fresh-mount, or `render!*` path, OR a
        preflight migrated into `create-root*` (which cannot credit `render!*`).
    B — one-shot allocation ordering: the fresh one-shot mount preflights BEFORE
        it creates the React root — a contiguous `pre → create → render` triple
        WITHIN one function body (`mount*`). The split `create-root*` allocation is
        a render-less lone `create`, so it never forms this triple and is never
        falsely claimed to preflight before `create-root*`. Fires if the fresh
        mount's preflight moves after its `createRoot` (or disappears)."""
    problems: list[str] = []
    scan = preflight_scan(client_text)
    total_renders = scan["renders"]
    total_caused = scan["caused"]
    uncredited = scan["uncredited"]
    one_shot = scan["one_shot"]

    if total_renders == 0:
        problems.append(
            "LIFECYCLE-SOURCE-ORDER: implementation/ui/src/re_frame/ui/client.cljs "
            "has no host render call (`(.render …)`) at all — the compiled "
            "preflight-before-render source assertion cannot be proved."
        )
    elif total_caused != total_renders:
        problems.append(
            "LIFECYCLE-SOURCE-ORDER: implementation/ui/src/re_frame/ui/"
            "client.cljs has a host render (`(.render …)`) NOT preceded by its "
            "own LIVE frame preflight (`(run-preflight! …)` → `execute-frame-plans!`) "
            "in the SAME function body — each production path (existing-root "
            "refresh, fresh mount, split `render!*`) must preflight before ITS "
            "render, and a reader-discarded / dead-conditional / cross-function "
            "preflight does not count (the compiled ENSURE-at-preflight contract, "
            f"#5711). Uncredited function(s): {', '.join(uncredited)}. "
            f"{total_caused} of {total_renders} renders are preflight-caused."
        )

    if not one_shot:
        problems.append(
            "LIFECYCLE-ONE-SHOT-ALLOC: implementation/ui/src/re_frame/ui/client.cljs "
            "no longer shows the one-shot fresh mount preflighting BEFORE it "
            "creates the React root (`(run-preflight! …)` → `(rdc/createRoot …)` → "
            "`(.render …)`, contiguous within one function body). `mount*` must "
            "preflight before `createRoot`; the split `create-root*` allocates "
            "first by design and is exempt."
        )

    return problems


def _spec_heading_slug(heading_text: str) -> str:
    """GitHub-style heading slug (pymdownx.slugs.slugify, case=lower,
    unicode=False) for a Spec-002 heading's text — enough to check the two
    canonical fragments RESOLVE to a real heading rather than merely appearing as
    literal strings in the guide. Bounded + lexical: ASCII-fold, drop chars that
    are not word/whitespace/hyphen (an em-dash between spaces is dropped, leaving
    the DOUBLE hyphen the published anchors carry), then whitespace → hyphen with
    NO run-collapsing. Not a Markdown parser."""
    text = unicodedata.normalize("NFKD", heading_text).encode("ascii", "ignore").decode("ascii")
    text = re.sub(r"[^\w\s-]", "", text).strip().lower()
    return re.sub(r"\s", "-", text)


def _spec_heading_slugs(spec_text: str) -> set[str]:
    """The set of GitHub-style slugs of every ATX heading (`#`..`######`) in a
    spec document — a lexical heading-line scan, not a parser."""
    slugs: set[str] = set()
    for line in spec_text.splitlines():
        m = re.match(r"\s{0,3}#{1,6}\s+(.*\S)\s*$", line)
        if m:
            slugs.add(_spec_heading_slug(m.group(1)))
    return slugs


def _core_artifact_inventory_row(conventions_text: str) -> str | None:
    """The `day8/re-frame2` CORE artifact inventory row from the Adapter-shipping
    table — the OWNING row that inventories `frame-root` / `frame-provider` as core
    surface. Matched by its exact first table cell so the `-reagent` / `-uix` /
    `-helix` rows (which also mention the boundaries) can never stand in for it,
    and so deleting a boundary from THIS row is caught even while unrelated
    mentions survive elsewhere in the document. None if the row is gone."""
    for line in conventions_text.splitlines():
        cells = [c.strip() for c in line.split("|")]
        # cells[0] is the empty pre-leading-pipe field; cells[1] is the first cell.
        if len(cells) >= 2 and cells[1] == "`day8/re-frame2`":
            return line
    return None


def lifecycle_realization_problems(
    *,
    phase2: str | None = None,
    client: str | None = None,
    frames: str | None = None,
    conventions: str | None = None,
    spec002: str | None = None,
) -> list[str]:
    """Positive-presence assertions for the two frame-root lifecycles. Each arg
    is the file's text, or None when that source could not be read — in which
    case the arms depending on it do not run, and the ARM-NOT-RUN floor at the
    end reports exactly which assertions were skipped. Returns drift labels
    (empty only when every declared arm ran clean).

    Every parameter defaults to None on purpose: deleting a `*_FILE` constant
    (and its entry in `find_lifecycle_drift`'s `required` map) must not crash
    with a TypeError, nor silently retire the arm — it must land on the
    coverage floor with a message naming the assertion that stopped running."""
    problems: list[str] = []

    if phase2 is not None:
        # L1 — compiled `re-frame.ui` realization present + timing distinct.
        for token, label in (
            ("execute-frame-plans!", "the compiled preflight call `execute-frame-plans!`"),
            (
                "host preflight, never render",
                'the compiled ENSURE-timing statement ("host preflight, never render")',
            ),
            (":mount-incomplete", "the compiled aborted-attempt evidence `:mount-incomplete`"),
        ):
            if token not in phase2:
                problems.append(
                    "LIFECYCLE-COMPILED-COLLAPSED: phase-2-impl-order.md is missing "
                    f"{label} — the compiled `re-frame.ui` frame-root realization "
                    "must not disappear or collapse into the legacy one (ENSURE at "
                    "host preflight, scope-only emit, evidence-only commit, "
                    "`:mount-incomplete` on abort, `:refresh` on same-owner reconfig)."
                )
        # L2 — `[REACT-ADAPTERS]` React-adapter realization present + distinct.
        for token, label in (
            ("[REACT-ADAPTERS]", "the `[REACT-ADAPTERS]` React-adapter label"),
            ("useLayoutEffect", "the commit-owned `useLayoutEffect` two-pass timing"),
            (":rf.error/frame-root-reconfigured", "the React-adapter reconfiguration error"),
        ):
            if token not in phase2:
                problems.append(
                    "LIFECYCLE-REACT-ADAPTERS-COLLAPSED: phase-2-impl-order.md is missing "
                    f"{label} — the `[REACT-ADAPTERS]` Reagent / reagent-slim / UIx "
                    "realization must not disappear or collapse into the compiled one "
                    "(commit-owned two-pass layout-effect ENSURE, discarded-render zero "
                    "writes, `:rf.error/frame-root-reconfigured` on mounted reconfig)."
                )
        # L4 — Spec-002 frame-root / frame-provider heading links present in the
        # guide AND resolvable to a REAL Spec-002 heading (not merely a literal
        # published fragment string). The anchor's fragment must slugify-match an
        # ATX heading in spec/002-Frames.md.
        spec_slugs = _spec_heading_slugs(spec002) if spec002 is not None else None
        for anchor in (FRAME_ROOT_ANCHOR, FRAME_PROVIDER_ANCHOR):
            if anchor not in phase2:
                problems.append(
                    "LIFECYCLE-SPEC002-LINK: phase-2-impl-order.md is missing the "
                    f"Spec-002 heading anchor `{anchor}` — the frame-root / frame-"
                    "provider component-contract links must stay present."
                )
            elif spec_slugs is not None and anchor.lstrip("#") not in spec_slugs:
                problems.append(
                    "LIFECYCLE-SPEC002-UNRESOLVED: phase-2-impl-order.md links "
                    f"`{anchor}`, but no heading in spec/002-Frames.md slugifies to "
                    f"`{anchor.lstrip('#')}` — the frame-root / frame-provider "
                    "component-contract links must RESOLVE to a live Spec-002 "
                    "heading, not merely appear as a literal fragment."
                )

    # L5 — CAUSAL source assertion: each production render path preflights before
    # its host render, and the one-shot fresh mount preflights before createRoot.
    if client is not None:
        problems.extend(_preflight_causal_problems(client))
    if frames is not None:
        for token in (
            "execute-frame-plans!",
            "finalize-preflight-attempt!",
            ":mount-incomplete",
        ):
            if token not in frames:
                problems.append(
                    "LIFECYCLE-SOURCE-FRAMES: implementation/ui/src/re_frame/ui/"
                    f"frames.cljc is missing `{token}` — the compiled preflight "
                    "executor + its commit-bound evidence surface must stay present."
                )

    # L6 — the OWNING core artifact inventory row keeps BOTH frame boundaries.
    # Scoped to the `day8/re-frame2` row so deleting a boundary from the row it
    # inventories is caught even while unrelated mentions survive elsewhere.
    if conventions is not None:
        row = _core_artifact_inventory_row(conventions)
        if row is None:
            problems.append(
                "LIFECYCLE-INVENTORY-ROW: spec/Conventions.md no longer has the "
                "`day8/re-frame2` core artifact inventory row (Adapter-shipping "
                "table) — the row that owns the frame-root / frame-provider "
                "boundary inventory is gone."
            )
        else:
            for token in ("frame-root", "frame-provider"):
                if token not in row:
                    problems.append(
                        "LIFECYCLE-INVENTORY: spec/Conventions.md's "
                        f"`day8/re-frame2` core artifact inventory row no longer "
                        f"names `{token}` — neither frame boundary may disappear "
                        "from the row that inventories it (unrelated mentions "
                        "elsewhere do not count)."
                    )

    # --- ARM-NOT-RUN floor (rf2-0dbvy). Every arm above is conditional on a
    # source text; a missing source means the assertion was never evaluated,
    # and an unevaluated assertion guards nothing. Report it as a failure here
    # rather than letting the caller's SETUP line be the only trace — because
    # that SETUP line is exactly what a well-meaning edit deletes.
    missing_arms = sorted(
        set(LIFECYCLE_ARM_SOURCES)
        - lifecycle_arms_run(
            {
                "phase2": phase2,
                "client": client,
                "frames": frames,
                "conventions": conventions,
                "spec002": spec002,
            }
        )
    )
    if missing_arms:
        total = len(LIFECYCLE_ARM_SOURCES)
        problems.append(
            "LIFECYCLE-ARM-NOT-RUN: the frame-root lifecycle guard evaluated "
            f"{total - len(missing_arms)} of {total} declared assertion arms; it "
            f"did NOT evaluate {', '.join(missing_arms)}. A skipped assertion is "
            "not a passing one — with the arm silent, the contract it holds is "
            "unguarded and nothing else reports that. Either RE-POINT the arm's "
            "source constant at the file that now owns the contract, so the "
            "assertion keeps running; or RETIRE the arm deliberately by deleting "
            "its row from LIFECYCLE_ARM_SOURCES and recording the contract as "
            "unguarded."
        )

    return problems


def find_lifecycle_drift() -> tuple[list[str], str]:
    """Read the Rule-7 source-of-truth files and run the presence assertions,
    reporting a missing REQUIRED file as a SETUP error (the spec + ui
    sources are all repo-tracked, so a miss means the guard's paths drifted).

    Returns (problems, coverage summary). The summary states how many declared
    arms actually ran and the L5 scan's population, so a `--verbose` run cannot
    claim an assertion "held" that never executed."""
    problems: list[str] = []
    texts: dict[str, str | None] = {}
    required = {
        "phase2": PHASE2_FILE,
        "client": UI_CLIENT_FILE,
        "frames": UI_FRAMES_FILE,
        "conventions": CONVENTIONS_FILE,
        "spec002": SPEC_002_FILE,
    }
    for key, path in required.items():
        if path.is_file():
            texts[key] = _slurp(path)
        else:
            texts[key] = None
            arms = sorted(
                arm for arm, srcs in LIFECYCLE_ARM_SOURCES.items() if key in srcs
            )
            problems.append(
                "SETUP: expected Rule-7 source file missing: "
                f"{path.relative_to(REPO_ROOT)} — {', '.join(arms)} cannot run "
                "against it. Two honest fixes, and only two: RE-POINT this "
                "*_FILE constant at the source that now owns the contract, so "
                "the assertion keeps running; or RETIRE the arm DELIBERATELY, "
                "which means ALSO deleting its row from LIFECYCLE_ARM_SOURCES "
                "and recording that the contract it held is now unguarded. "
                "Deleting the constant alone does NOT quiet this guard — the "
                "ARM-NOT-RUN floor then reports the assertion as never evaluated."
            )
    problems.extend(lifecycle_realization_problems(**texts))

    arms_run = lifecycle_arms_run(texts)
    summary = f"{len(arms_run)} of {len(LIFECYCLE_ARM_SOURCES)} assertion arms evaluated"
    client_text = texts.get("client")
    if client_text is None:
        summary += "; L5 causal scan DID NOT RUN (0 renders checked)"
    else:
        scan = preflight_scan(client_text)
        summary += (
            f"; L5 causal scan: {scan['forms']} top-level forms, "
            f"{scan['renders']} host renders, {scan['caused']} preflight-caused, "
            f"one-shot pre→create→render triple {'present' if scan['one_shot'] else 'ABSENT'}"
        )
    return problems, summary


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_DIR.is_dir():
        sys.stderr.write(
            f"error: re-frame2-implementor skill not found at {SKILL_DIR}\n"
        )
        return 2

    problems, lines_checked = find_drift(SCANNED_FILES)

    beadid_files = _beadid_scanned_files()
    beadid_problems, beadid_lines = find_beadid_drift(beadid_files)
    problems.extend(beadid_problems)

    lifecycle_problems, lifecycle_coverage = find_lifecycle_drift()
    problems.extend(lifecycle_problems)

    if verbose:
        print(
            f"implementor partition/HTTP/API-name guard: scanned "
            f"{len(SCANNED_FILES)} files ({lines_checked} lines)."
        )
        print(
            f"implementor no-bead-ids guard: scanned {len(beadid_files)} "
            f"user-facing leaves ({beadid_lines} lines)."
        )
        # State the POPULATION, not a claim: an arm that did not run says so
        # here rather than hiding behind "assertion held" (rf2-0dbvy).
        print(f"implementor frame-root lifecycle guard: {lifecycle_coverage}.")

    if not problems:
        if verbose:
            print(
                "partition-drift: no deregister-listener!, bare-:http-managed, "
                "public-dispose-adapter!, reg-machine-marks, or bead-id drift "
                "found."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\npartition-drift: {len(problems)} drift issue(s) — the implementor "
        "skill is a control surface; align it to shipped EP-0001 partition / "
        "Spec 014 managed-HTTP / public-API reality, and keep internal bead ids "
        "out of the user-facing leaves (L7)."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the line classifier against in-memory fixtures so the
# guard itself can't silently rot. Mirrors the --self-test convention in the
# sibling check_skill_*.py guards.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    def expect(line: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(line_problems(line))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got "
                f"{got} for: {line!r}"
            )
            failures += 1

    # FAIL fixtures — the exact rf2-whsb0c / rf2-6c59ob drift shapes.
    expect(
        "The trace stream — `register-listener!` / `deregister-listener!`, the rich emits.",
        dirty=True, label="A1 deregister-listener",
    )
    expect(
        "EP 014 implementation. The `:http` fx wraps a request lifecycle through a state machine.",
        dirty=True, label="A2 bare :http as managed fx",
    )
    expect(
        "boot wiring is the core's `install-adapter!` / `dispose-adapter!`.",
        dirty=True, label="A3 public dispose-adapter (no slot/map-key cue)",
    )
    expect(
        "The seven first-class marking sites accept `{:sensitive [paths] :large [paths]}` "
        "including reg-machine.",
        dirty=True, label="A4 reg-machine implies :sensitive/:large",
    )

    # PASS fixtures — the corrected wording must NOT flag.
    expect(
        "The trace stream — `register-listener!` / `unregister-listener!`, the rich emits.",
        dirty=False, label="B1 unregister-listener (correct)",
    )
    expect(
        "The Spec 014 surface is `:rf.http/managed`; bare `:http` is app/user/implementation-specific.",
        dirty=False, label="B2 :rf.http/managed + bare :http marked app-level",
    )
    expect(
        "Lower-level bare `:http` is NOT a reserved framework fx — it is app/user-specific.",
        dirty=False, label="B3 bare :http explicitly denied as managed",
    )
    expect(
        "`destroy-adapter!` calls the adapter-spec map's internal `:dispose-adapter!` slot.",
        dirty=False, label="B4 dispose-adapter marked as map slot",
    )
    expect(
        "One lifecycle slot: `:dispose-adapter!` — the adapter-spec map key the adapter implements.",
        dirty=False, label="B5 :dispose-adapter as map key",
    )
    expect(
        "`reg-machine` is two-arity and accepts NO `:sensitive` / `:large` metadata keys.",
        dirty=False, label="B6 reg-machine schema-first exception stated",
    )
    expect(
        "The six metadata-bearing sites accept `{:sensitive [paths] :large [paths]}`.",
        dirty=False, label="B7 six sites, no reg-machine on the line",
    )

    # Rule 6 — reply-address / envelope contract. FAIL fixtures reproduce the
    # exact rf2-3fc89f.34 retired teaching; PASS fixtures are the corrected prose
    # and the legitimate INTERNAL `:rf/reply-to` descriptor mentions.
    expect(
        "The canonical public target key is `:rf/reply-to` (short vector form, "
        "or the internal descriptor form).",
        dirty=True, label="E1 :rf/reply-to taught as the public target key",
    )
    expect(
        "For HTTP, the public `:on-success` / `:on-failure` payload is sugar "
        "reshaped from the internal envelope — the canonical reply map is internal.",
        dirty=True, label="E2 HTTP sugar as a reshaped envelope",
    )
    expect(
        "Do not expose `:status` / `:work/id` / `:completed-at` on the public "
        "HTTP reply payload.",
        dirty=True, label="E3 bare :work/id on the reply payload",
    )
    # PASS — corrected prose and legitimate internal-descriptor references.
    expect(
        "The app-facing authoring key is `:reply-to`; it normalizes to the one "
        "internal / normalized `:rf/reply-to` descriptor it lowers to.",
        dirty=False, label="F1 public :reply-to + internal :rf/reply-to descriptor",
    )
    expect(
        "All family sugar normalizes to the internal `:rf/reply-to` descriptor "
        "(a conformance surface, not an everyday app-facing spelling).",
        dirty=False, label="F2 legitimate internal :rf/reply-to mention",
    )
    expect(
        "`:on-success` and `:on-failure` receive the identical canonical reply "
        "map that `:reply-to` would; they do NOT reshape it into a second dialect.",
        dirty=False, label="F3 HTTP sugar denied-reshape + identical envelope",
    )
    expect(
        "The transient reply map spells the work identity `:rf.reply/work-id`; "
        "bare `:work/id` is the durable ledger / verification identity.",
        dirty=False, label="F4 reply map :rf.reply/work-id, ledger :work/id",
    )
    expect(
        "Ledger-backed work correlates by `:work/id` (the family owns the tuple head).",
        dirty=False, label="F5 bare :work/id in a ledger context (no reply-map)",
    )
    expect(
        "The route loader stores the normalized `:rf/reply-to` target in "
        "runtime-db and completes it via the shared substrate.",
        dirty=False, label="F6 internal :rf/reply-to storage, no public-key claim",
    )

    # Rule 5 — bead-id leak. line_problems() does NOT cover Rule 5 (it scans a
    # separate file set), so exercise BEADID_RE directly.
    def expect_beadid(line: str, *, leaked: bool, label: str) -> None:
        nonlocal failures
        got = bool(BEADID_RE.search(line))
        if got != leaked:
            print(
                f"SELF-TEST FAIL ({label}): expected bead-id leaked={leaked}, "
                f"got {got} for: {line!r}"
            )
            failures += 1

    # LEAK fixtures — the exact rf2-ij6ulc finding-2 shapes.
    expect_beadid(
        "a stray `:rf/runtime` root now HARD-ERRORS (shipped EP-0001 bead 9, rf2-tfepxu).",
        leaked=True, label="C1 plain bead id",
    )
    expect_beadid(
        "the post-v1 deferral withdrawn via rf2-mle6e / PR #2863; the runtime records history.",
        leaked=True, label="C2 bead id beside a PR ref",
    )
    expect_beadid(
        "the dual-partition recompute trigger is a SILENT regression (rf2-d3fb7.1).",
        leaked=True, label="C3 bead id with .N sub-task suffix",
    )

    # CLEAN fixtures — the corrected public-evidence wording must NOT flag.
    expect_beadid(
        "a stray `:rf/runtime` root now HARD-ERRORS (`:rf.error/legacy-runtime-root`).",
        leaked=False, label="D1 spec error keyword, no bead id",
    )
    expect_beadid(
        "the runtime records history per `spec/005-StateMachines.md` §History states.",
        leaked=False, label="D2 spec section link, no bead id",
    )
    expect_beadid(
        "backstopped by `effect-map-shape-bad-fx-entry.edn` and `effect-handler-bad-return.edn`.",
        leaked=False, label="D3 fixture names, no bead id",
    )
    expect_beadid(
        "a fully-qualified public PR link day8/re-frame2#2863 is fine for an external reader.",
        leaked=False, label="D4 public PR ref is not a bead id",
    )

    # Rule 7 — frame-root lifecycle realization presence. lifecycle_realization_
    # problems() reads whole-file text (not per-line), so exercise it with in-
    # memory good/bad content variants.
    good_phase2 = (
        "runs them through `re-frame.ui.frames/execute-frame-plans!` before "
        "`createRoot`. ENSURE is host preflight, never render (#5711). An aborted "
        "host attempt may leave `:mount-incomplete`. **`[REACT-ADAPTERS]` Reagent "
        "/ reagent-slim / UIx** — ENSURE runs only from a client `useLayoutEffect`, and a "
        "mounted reconfiguration fails loud with `:rf.error/frame-root-reconfigured`. "
        "See [§frame-root](https://day8.github.io/re-frame2/spec/002-Frames/"
        "#frame-root--the-ensure-component-cljs-reference) and "
        "[§frame-provider](https://day8.github.io/re-frame2/spec/002-Frames/"
        "#frame-provider--the-scope-only-component-cljs-reference)."
    )
    # good_client models all THREE production render paths + the split allocation
    # as REAL top-level `defn` bodies (rf2-8cncz) so the PER-FUNCTION scoping is
    # genuinely exercised: `mount*` carries both render paths (pre, render, pre,
    # create, render), `create-root*` is an allocation-only lone `createRoot`, and
    # `render!*` preflights before its render. Built from labeled fragments so a
    # mutation can target ONE path — or migrate a preflight ACROSS a function
    # boundary — in isolation. (The old whole-file substring scan pooled credit
    # across these bodies and counted reader-discarded / dead-conditional tokens;
    # the J* / K* cases below were GREEN under it and must now be RED.)
    same_root_ok = (
        "  (let [receipt (run-preflight! root-id plans)]  ;; same-root refresh\n"
        "    (.render (.-react-root root) (element-thunk)))\n"
    )
    fresh_ok = (
        "  (let [receipt (run-preflight! root-id plans)]  ;; fresh mount\n"
        "    (rdc/createRoot container opts)\n"
        "    (.render react-root (element-thunk)))\n"
    )
    create_root_ok = (
        "  (rdc/createRoot container opts)  ;; create-root* — no preflight, by design\n"
    )
    render_bang_ok = (
        "  (let [receipt (run-preflight! rid plans)]\n"
        "    (.render (.-react-root root) (element-thunk)))\n"
    )

    def client_of(*, same=same_root_ok, fresh=fresh_ok,
                  create_body=create_root_ok, render_bang=render_bang_ok):
        """Assemble a client from per-function fragments, each a real column-0
        `defn` body so the partition scopes credit per function."""
        return (
            "(defn mount* [info container element-thunk react-opts plans-thunk]\n"
            + same + fresh
            + "  root)\n\n"
            + "(defn create-root* [info container react-opts]\n"
            + create_body
            + "  root)\n\n"
            + "(defn render!* [root element-thunk plans-thunk descriptor-base]\n"
            + render_bang
            + "  root)\n"
        )

    good_client = client_of()
    good_frames = "(defn execute-frame-plans! [root-id plans]\n  ;; finalize-preflight-attempt! ... :mount-incomplete\n  nil)"
    # A markdown Adapter-shipping table: the owning `day8/re-frame2` core row names
    # BOTH boundaries; an unrelated `-uix` row also mentions them (so an
    # inventory-row mutation must survive those unrelated mentions).
    good_conv = (
        "| Artefact | Contents |\n"
        "|---|---|\n"
        "| `day8/re-frame2` | Core: registry, drain, fx, dispatch, subscribe, "
        "frame-root, frame-provider, trace, the substrate-adapter contract. |\n"
        "| `day8/re-frame2-uix` | UIx adapter — the UIx-side frame-root / "
        "frame-provider consuming the shared React context. |\n"
    )
    # A Spec-002 doc whose ATX headings slugify to the two canonical fragments.
    good_spec002 = (
        "### frame-provider — the SCOPE-only component (CLJS reference)\n"
        "some prose\n"
        "### frame-root — the ENSURE component (CLJS reference)\n"
    )

    base = dict(
        phase2=good_phase2,
        client=good_client,
        frames=good_frames,
        conventions=good_conv,
        spec002=good_spec002,
    )

    def expect_lifecycle(overrides: dict, *, dirty: bool, label: str) -> None:
        nonlocal failures
        kwargs = {**base, **overrides}
        got = bool(lifecycle_realization_problems(**kwargs))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected lifecycle dirty={dirty}, "
                f"got {got}."
            )
            failures += 1

    expect_lifecycle({}, dirty=False, label="G0 both realizations + links + source present")
    expect_lifecycle(
        {"phase2": good_phase2.replace("execute-frame-plans!", "some-other-fn")},
        dirty=True, label="G1 compiled preflight call removed",
    )
    expect_lifecycle(
        {"phase2": good_phase2.replace("host preflight, never render", "runs in a layout effect")},
        dirty=True, label="G2 compiled timing collapsed into legacy",
    )
    expect_lifecycle(
        {"phase2": good_phase2.replace(":rf.error/frame-root-reconfigured", "some refresh")},
        dirty=True, label="G3 React-adapter reconfiguration error removed",
    )
    expect_lifecycle(
        {"phase2": good_phase2.replace("[REACT-ADAPTERS]", "current")},
        dirty=True, label="G4 [REACT-ADAPTERS] label removed",
    )
    expect_lifecycle(
        {"phase2": good_phase2.replace("#frame-root--the-ensure-component-cljs-reference", "#gone")},
        dirty=True, label="G5 Spec-002 frame-root anchor missing from guide",
    )
    # G5b — anchor present in the guide but NO longer resolves to a Spec-002
    # heading (the fragment is now a dangling literal). Must fail (unresolved).
    expect_lifecycle(
        {"spec002": good_spec002.replace(
            "### frame-root — the ENSURE component (CLJS reference)",
            "### frame-root moved elsewhere")},
        dirty=True, label="G5b Spec-002 frame-root anchor no longer resolves",
    )
    expect_lifecycle(
        {"frames": "(defn something-else [] nil)"},
        dirty=True, label="G7 compiled executor/evidence surface gone",
    )
    # --- Rule 7 causal source assertions (client) — remove/reorder EACH
    # production preflight independently, and violate the one-shot allocation
    # ordering; every mutation must fail.
    same_no_pre = (
        "  (let []  ;; same-root refresh (preflight removed)\n"
        "    (.render (.-react-root root) (element-thunk)))\n"
    )
    fresh_no_pre = (
        "  (let []  ;; fresh mount (preflight removed)\n"
        "    (rdc/createRoot container opts)\n"
        "    (.render react-root (element-thunk)))\n"
    )
    render_bang_no_pre = (
        "  (.render (.-react-root root) (element-thunk))  ;; render!* (preflight removed)\n"
    )
    same_reordered = (
        "  (.render (.-react-root root) (element-thunk))  ;; render BEFORE preflight\n"
        "  (run-preflight! root-id plans)\n"
    )
    fresh_alloc_before_pre = (
        "  (rdc/createRoot container opts)  ;; createRoot BEFORE preflight\n"
        "  (let [receipt (run-preflight! root-id plans)]\n"
        "    (.render react-root (element-thunk)))\n"
    )
    expect_lifecycle(
        {"client": client_of(same=same_no_pre)},
        dirty=True, label="H1 same-root refresh preflight removed",
    )
    expect_lifecycle(
        {"client": client_of(fresh=fresh_no_pre)},
        dirty=True, label="H2 fresh-mount preflight removed",
    )
    expect_lifecycle(
        {"client": client_of(render_bang=render_bang_no_pre)},
        dirty=True, label="H3 render!* preflight removed",
    )
    expect_lifecycle(
        {"client": client_of(same=same_reordered)},
        dirty=True, label="H4 same-root render reordered before preflight",
    )
    expect_lifecycle(
        {"client": client_of(fresh=fresh_alloc_before_pre)},
        dirty=True, label="H5 one-shot allocation ordering violated (createRoot before preflight)",
    )

    # --- Rule 7 dead-call bypass (rf2-8cncz, class 1) — a preflight that is
    # reader-discarded (`#_`) or wrapped in `(when false ...)` is textually
    # present (the old whole-file substring scan counted it, staying GREEN) but is
    # STATICALLY DEAD, so `render!*`'s render is really uncredited. Neutralization
    # must drop it and trip the guard.
    render_bang_discarded = (
        "  (let [receipt #_(run-preflight! rid plans) nil]\n"
        "    (.render (.-react-root root) (element-thunk)))\n"
    )
    render_bang_when_false = (
        "  (let [receipt (when false (run-preflight! rid plans))]\n"
        "    (.render (.-react-root root) (element-thunk)))\n"
    )
    render_bang_live_plus_discard = (
        "  #_(run-preflight! rid stale-plans)  ;; a discarded UNRELATED datum\n"
        "  (let [receipt (run-preflight! rid plans)]\n"
        "    (.render (.-react-root root) (element-thunk)))\n"
    )
    expect_lifecycle(
        {"client": client_of(render_bang=render_bang_discarded)},
        dirty=True, label="J1 render!* preflight reader-discarded (#_) — now trips",
    )
    expect_lifecycle(
        {"client": client_of(render_bang=render_bang_when_false)},
        dirty=True, label="J2 render!* preflight wrapped in (when false ...) — now trips",
    )
    expect_lifecycle(
        {"client": client_of(render_bang=render_bang_live_plus_discard)},
        dirty=False, label="J3 a discarded UNRELATED datum leaves the LIVE preflight (surgical)",
    )

    # --- Rule 7 cross-function credit migration (rf2-8cncz, class 2) — moving
    # `render!*`'s preflight into allocation-only `create-root*` preserved the
    # whole-file token sequence (old scan GREEN) while `render!*`'s render reached
    # `.render` with no preflight. Per-function scoping means `create-root*` cannot
    # donate its credit to `render!*`.
    create_root_with_migrated_pre = (
        "  (run-preflight! root-id (constantly nil))  ;; MIGRATED from render!*\n"
        "  (rdc/createRoot container opts)\n"
    )
    expect_lifecycle(
        {"client": client_of(create_body=create_root_with_migrated_pre,
                             render_bang=render_bang_no_pre)},
        dirty=True,
        label="K1 preflight migrated into create-root*; render!* render uncredited — now trips",
    )

    # --- Rule 7 inventory-row scoping (conventions) — remove EACH boundary from
    # the OWNING `day8/re-frame2` core row while leaving the unrelated `-uix`
    # mention intact; every mutation must fail.
    expect_lifecycle(
        {"conventions": good_conv.replace(
            "subscribe, frame-root, frame-provider, trace", "subscribe, frame-provider, trace")},
        dirty=True, label="I1 frame-root dropped from core row (uix mention survives)",
    )
    expect_lifecycle(
        {"conventions": good_conv.replace(
            "subscribe, frame-root, frame-provider, trace", "subscribe, frame-root, trace")},
        dirty=True, label="I2 frame-provider dropped from core row (uix mention survives)",
    )
    # I3 — the owning core row itself is gone.
    expect_lifecycle(
        {"conventions": (
            "| Artefact | Contents |\n|---|---|\n"
            "| `day8/re-frame2-uix` | UIx adapter — frame-root / frame-provider. |\n")},
        dirty=True, label="I3 core artifact inventory row removed",
    )

    # --- Rule 7 ARM-NOT-RUN floor (rf2-0dbvy) — the F6e trap. A missing source
    # used to make its arm SKIP silently: `find_lifecycle_drift` reported the
    # file and passed None on, and `lifecycle_realization_problems` simply did
    # not evaluate that arm. Clearing the SETUP error by deleting the *_FILE
    # constant then retired the assertion outright with nothing left to notice.
    # Every one of these was GREEN before the floor and must now be RED, and the
    # message must NAME the arm that stopped running.
    def expect_arm_not_run(overrides: dict, *, arm: str, label: str) -> None:
        nonlocal failures
        got = lifecycle_realization_problems(**{**base, **overrides})
        floor = [p for p in got if p.startswith("LIFECYCLE-ARM-NOT-RUN")]
        if not floor:
            print(f"SELF-TEST FAIL ({label}): expected an ARM-NOT-RUN floor, got none.")
            failures += 1
        elif arm not in floor[0]:
            print(f"SELF-TEST FAIL ({label}): floor does not name `{arm}`: {floor[0]!r}")
            failures += 1

    expect_arm_not_run(
        {"client": None}, arm="L5-client-preflight-causality",
        label="L1 client source gone — the causal preflight assertion stops running",
    )
    expect_arm_not_run(
        {"frames": None}, arm="L5-frames-executor",
        label="L2 frames source gone — the executor presence assertion stops running",
    )
    expect_arm_not_run(
        {"conventions": None}, arm="L6-inventory-row",
        label="L3 conventions gone — the inventory-row assertion stops running",
    )
    expect_arm_not_run(
        {"spec002": None}, arm="L4-spec002-links-resolve",
        label="L4 spec002 gone — anchors still checked PRESENT but never RESOLVED",
    )
    expect_arm_not_run(
        {"phase2": None}, arm="L1-compiled-realization",
        label="L5 phase2 gone — the guide realization arms stop running",
    )
    # Every source present ⇒ every declared arm runs, and no floor fires.
    ran = lifecycle_arms_run(dict(base))
    if ran != set(LIFECYCLE_ARM_SOURCES):
        print(
            f"SELF-TEST FAIL (L6 full coverage): expected all "
            f"{len(LIFECYCLE_ARM_SOURCES)} arms to run, missing "
            f"{sorted(set(LIFECYCLE_ARM_SOURCES) - ran)}."
        )
        failures += 1

    if failures:
        print(f"self-test: {failures} failure(s).")
        return 1
    print("self-test: all cases passed.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--verbose", action="store_true", help="print summary")
    parser.add_argument(
        "--ci",
        action="store_true",
        help="GitHub-Actions ::error:: prefix (auto on under GITHUB_ACTIONS)",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run built-in fixtures instead of scanning the repo",
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()

    ci = args.ci or bool(os.environ.get("GITHUB_ACTIONS"))
    return run(verbose=args.verbose, ci=ci)


if __name__ == "__main__":
    raise SystemExit(main())
