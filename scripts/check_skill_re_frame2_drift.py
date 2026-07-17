#!/usr/bin/env python3
"""No-bead-id + verification-posture + launcher-canonical + machine-handler-
recipe + managed-http-recipe drift guard for the re-frame2 (authoring) skill.

`spec/design.md` is the single normative source for the skill's locked
decisions (L1–L11), file structure, cardinal rules, and verification posture;
`spec/inputs.md` owns the canonical inputs and update procedure. The
`spec/authoring-prompt.md` launcher orchestrates a reauthoring pass by
*pointing at* those two files — it must not carry a second, drift-prone copy of
the tree, the rules, or the locks. This guard protects four regressions the
existing automated guards did not catch (the no-bead-id guard was scoped to
`skills/re-frame2-implementor` only):

  1. **Bead-id leaks in user-facing leaves.** Two leaves carried
     `EP-0008 (rf2-hhutya)` — an internal `bd` tracker id. The skill's own
     design locks this out (`spec/design.md` L10 — the normative source; the
     `spec/authoring-prompt.md` launcher references it but no longer restates
     it): `SKILL.md` + `references/` + `patterns/` + `decision-trees/`
     carry NO `rf2-XXXX` ids. Bead ids are monorepo-internal workflow noise; a
     consumer app author has no `bd` and no bead corpus, so a leaked id is
     unexplained context. Use public, stable evidence instead — an EP number, a
     spec section link, a fixture name, an API entry, or a fully-qualified
     public PR link. The skill's own `spec/` meta-docs MAY mention bead ids
     (authoring / internal context) and are out of scan scope; `evals/` is also
     out of scope (eval harness, not user-facing teaching).

  2. **Verification-posture drift.** `spec/design.md` (Q14 / L3) is the
     normative lock on the posture (the `spec/authoring-prompt.md` launcher and
     `skills/README.md` both reference it, they do not re-hold it): this is
     an authoring-only skill — the AUTHOR runs the tests, the compiler, the app;
     the skill stops at writing the code. Running gates is general software
     practice (Pillar 4), not a re-frame2 binding the skill teaches. The skill
     had drifted to instruct the AGENT to run gates: a `Bash(clojure -M:test)`
     allow-list, a "Verify what you changed" SKILL.md section, and a
     testing-leaf "verifying is in scope" clause. This guard makes a
     re-introduction of any of those shapes a build failure, so the shipped
     contract and its rationale can't silently diverge again. (If Q14 is ever
     unlocked by Mike, the design/index rationale flips first — and this guard's
     posture rules are retired in the same change.)

  3. **Launcher regrowth.** `spec/authoring-prompt.md` is a launcher, not a
     second normative source. It MUST point at both canonical files
     (`design.md` AND `inputs.md`) so a reauthoring session reads the design
     before writing, and it MUST NOT regrow the copied file-structure tree or
     the copied locks/cardinal-rules block — the exact duplication that drifted
     (the launcher had over-generalised the runtime `*` suffixes and still
     named a default frame, both contradicting current design). This guard
     fails if the launcher stops citing a canonical file or grows a box-drawing
     tree / a "Locks to preserve verbatim" (or "Cardinal rules to bake in")
     block back.

  4. **The machine-registration footgun.** The state-machine leaf and the API
     cheatsheet taught the bare `(reg-event id meta (make-machine-handler spec))`
     route as if it were a normal way to author a machine. That direct path does
     NOT stamp the `:rf/machine?` / `:rf/machine` registration metadata or the
     per-element source coordinates that machine introspection, `(machine-meta
     id)`, visualisers, and Xray resolve through, and a `[:schemas :data]`-
     bearing spec throws `:rf.error/machine-schema-requires-reg-machine` on it.
     `reg-machine` is the sole normal application-authoring recipe — it already
     registers the machine AS an event handler and stamps that metadata. This
     guard rejects a POSITIVE fenced recipe that co-locates `reg-event` and
     `make-machine-handler`, while ALLOWING an inline (non-fenced) implementation
     warning that names the shape — so the retained advanced-note mention on
     `reg-machine.md` / `api-cheatsheet.md` stays legal, but a copy-pasteable
     footgun recipe cannot reappear in any user-facing leaf.

  5. **The retired Managed-HTTP reply contract.** The runtime retired the
     co-located reply default pre-alpha (`rf2-et4c1s` / PR #5449): every
     `:rf.http/managed` fx-form request must now address its reply with at
     least one of `:reply-to` / `:on-success` / `:on-failure`, and omitting all
     three throws `:rf.error/http-no-reply-target` at fx-call time — a
     targetless canonical recipe cannot even start a request. The skill's
     primary HTTP example had drifted to teach exactly that removed shape
     (`rf2-j538f7.35` / PR #5603 repaired the leaf prose). This guard makes the
     retired teaching a build failure so it cannot re-enter: (5a) a fenced
     fx-form `[:rf.http/managed …]` recipe that carries a `:request` but no
     reply target; (5b) reading the removed co-located key `(:rf/reply …)`; and
     (5c) bare `:work/id` asserted as a TRANSIENT reply field (whose spelling is
     `:rf.reply/work-id` — bare `:work/id` is the durable ledger / machine-
     `:data` / correlation identity). The machine-form `:spawn {:machine-id
     :rf.http/managed …}` (which dispatches back to its parent and needs no
     `:reply-to`), the `[:rf.http/managed-abort …]` dispatch, and legitimate
     durable-ledger `:work/id` prose are deliberately allowed.

  6. **UIx/Helix hooks stateful-component guidance (rf2-adm10).** The hooks
     adapters (UIx / Helix) bridge a stateful component with an ordinary
     `defui` / `defnc` plus the `use-subscribe` (read subs) and `use-frame`
     (carry the frame) hooks — NOT the Reagent `reg-view` / render-time
     `capture-frame` / `:contextType` idiom. `use-frame` is `capture-frame` in
     hook position, reading the surrounding frame-provider / frame-root through
     React context; a bare no-arg `capture-frame` in a plain hooks component
     reads only the dynamic-var tier and raises under a context-only frame, and
     `reg-view*` on these adapters is optional registry addressing, never the
     source of a Reagent `:contextType`. Two narrow guards (no general prose
     parser): (6a) the two authoritative hooks-guidance leaves (`patterns/
     stateful-components.md`, `references/fundamentals/frames.md`) MUST each name
     `use-frame` AND `use-subscribe`; (6b) no scanned-leaf line may attribute a
     `:contextType` to a hooks adapter (`:contextType` co-located with UIx /
     Helix on a line carrying no negation / Reagent-only cue).

Scanned leaves (globbed, so a new leaf is covered automatically):
    SKILL.md, README.md, references/**/*.md, patterns/**/*.md,
    decision-trees/**/*.md.
Out of scope of the line-by-line scan (deliberately): spec/ (L10 permits bead
    ids; `design.md` carries the normative lock *statements*; `authoring-
    prompt.md` is the launcher, checked separately by Rule 3), evals/ (harness,
    not teaching). examples-map.md is in scope (it is a user-facing leaf).

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_re_frame2_drift.py
    python scripts/check_skill_re_frame2_drift.py --verbose
    python scripts/check_skill_re_frame2_drift.py --ci
    python scripts/check_skill_re_frame2_drift.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import sys
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

SKILL_DIR = REPO_ROOT / "skills" / "re-frame2"
# The launcher: a reauthoring wrapper that must POINT at the canonical files
# (design.md + inputs.md), never re-hold their tree / rules / locks (Rule 3).
AUTHORING_PROMPT = SKILL_DIR / "spec" / "authoring-prompt.md"


def scanned_files() -> list[Path]:
    """User-facing leaves: SKILL.md, README.md, examples-map.md, and every
    leaf under references/ / patterns/ / decision-trees/. Globbed (not
    hand-listed) so a newly-added leaf is covered automatically. The skill's
    spec/ meta-docs and evals/ harness are deliberately NOT included."""
    files: list[Path] = []
    for name in ("SKILL.md", "README.md", "examples-map.md"):
        p = SKILL_DIR / name
        if p.is_file():
            files.append(p)
    for sub in ("references", "patterns", "decision-trees"):
        files.extend(sorted((SKILL_DIR / sub).rglob("*.md")))
    return files


# --- Rule 1: no `rf2-XXXX` internal bead ids in the user-facing leaves (L10).
# The id shape is `rf2-` + alphanumerics (some carry a `.N` sub-task suffix,
# e.g. `rf2-d3fb7.1`). Word-boundary the prefix so it doesn't match inside a
# longer token.
BEADID_RE = re.compile(r"\brf2-[a-z0-9]+(?:\.[0-9]+)?\b")

# --- Rule 2: verification-posture drift (Q14 / L3).
# 2a — a Bash test/compile/lint allow-list entry (the agent-runs-gates surface).
#      The skill's allowed-tools front-matter must not carry a Bash() entry that
#      runs a test / compile / lint command. (Read/Edit/Write/Grep/Glob + the
#      story-mcp authoring tools are fine; this rule only targets the
#      gate-running Bash shapes that the Q14 lock forbids.)
BASH_GATE_RE = re.compile(
    r"^\s*-\s*Bash\(\s*(?:.*\b(?:clojure|shadow-cljs|clj-kondo)\b.*"
    r"|npm\s+(?:run\s+)?test.*)\)",
    re.IGNORECASE,
)
# 2b — body prose that instructs the AGENT to run a gate before declaring done.
#      Fires on the "Verify what you changed" heading and the
#      "run the nearest relevant gate before declaring done" instruction shape.
VERIFY_PROSE_RE = re.compile(
    r"^\s*#+\s*Verify what you changed\b"
    r"|run the nearest relevant gate before declaring done"
    r"|verifying\b.{0,40}\bis in scope",
    re.IGNORECASE,
)

# --- Rule 4: no positive bare `reg-event` + `make-machine-handler` recipe in a
#     fenced code block (the machine-registration footgun). `reg-machine` is the
#     sole normal application-authoring recipe; the bare direct path skips the
#     `:rf/machine?` / source-coord stamp and trips
#     `:rf.error/machine-schema-requires-reg-machine` on a `[:schemas :data]`
#     spec. Scanned per-FENCED-BLOCK (the two tokens land on different lines of
#     one block), so an inline single-backtick warning that names the shape in
#     prose — the retained advanced-note mention — is allowed; only a
#     copy-pasteable positive recipe is rejected.
FENCE_RE = re.compile(r"^\s*```")
REG_EVENT_TOKEN_RE = re.compile(r"\breg-event\b")
MAKE_MACHINE_HANDLER_TOKEN_RE = re.compile(r"\bmake-machine-handler\b")
MACHINE_HANDLER_MSG = (
    "MACHINE-HANDLER-FOOTGUN: this fenced recipe registers a machine via the "
    "bare `reg-event` + `make-machine-handler` route. That path does NOT stamp "
    "the :rf/machine? / :rf/machine registration metadata or the per-element "
    "source coordinates that machine introspection, (machine-meta id), "
    "visualisers, and Xray resolve through, and a [:schemas :data]-bearing spec "
    "throws :rf.error/machine-schema-requires-reg-machine on it. Author normal "
    "machines with `rf/reg-machine` — the sole normal application-authoring "
    "recipe; it already registers the machine AS an event handler. Demote "
    "make-machine-handler to an inline advanced-note mention, never a positive "
    "fenced recipe."
)


def machine_handler_recipe_problems(text: str) -> list[tuple[int, str]]:
    """Rule 4 — a fenced code block that co-locates `reg-event` and
    `make-machine-handler` is the machine-registration footgun (a positive
    recipe for the metadata-skipping direct path). Returns
    (opening-fence-lineno, message) tuples. Takes the whole file body so the
    self-test can pass a fenced fixture directly; an inline single-backtick
    mention in prose never enters a fenced block, so it is allowed."""
    problems: list[tuple[int, str]] = []
    in_block = False
    block_start = 0
    block_lines: list[str] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        if FENCE_RE.match(line):
            if not in_block:
                in_block, block_start, block_lines = True, lineno, []
            else:
                body = "\n".join(block_lines)
                if (REG_EVENT_TOKEN_RE.search(body)
                        and MAKE_MACHINE_HANDLER_TOKEN_RE.search(body)):
                    problems.append((block_start, MACHINE_HANDLER_MSG))
                in_block, block_lines = False, []
        elif in_block:
            block_lines.append(line)
    return problems


# --- Rule 5: no retired Managed-HTTP reply-contract teaching (rf2-723lgn,
#     follow-up to rf2-j538f7.35 / PR #5603).
#
# 5a — a targetless fx-form `:rf.http/managed` REQUEST recipe. The fx form
#      `:fx [[:rf.http/managed {:request …}]]` MUST address its reply with at
#      least one of :reply-to / :on-success / :on-failure; the runtime throws
#      :rf.error/http-no-reply-target at fx-call time otherwise, so a targetless
#      canonical recipe cannot even start a request (there is no co-located
#      default that routes the reply back to the originating event). Scanned
#      per-FENCED-BLOCK (the tokens span lines). Only the `[:rf.http/managed`
#      fx invocation head is matched — never `:machine-id :rf.http/managed` (the
#      machine-form spawn dispatches back to its parent and needs no reply key)
#      and never `[:rf.http/managed-abort …]` (a dispatch with no :request).
FX_MANAGED_RE = re.compile(r"\[:rf\.http/managed(?!-)\b")
HTTP_REQUEST_RE = re.compile(r":request\b")
HTTP_REPLY_TARGET_RE = re.compile(r":reply-to\b|:on-success\b|:on-failure\b")
MANAGED_HTTP_TARGETLESS_MSG = (
    "HTTP-TARGETLESS-RECIPE: this fenced fx-form `:rf.http/managed` recipe "
    "supplies a `:request` but names NO reply target. The current contract "
    "REQUIRES at least one of `:reply-to` / `:on-success` / `:on-failure`; "
    "omitting all three throws :rf.error/http-no-reply-target at fx-call time, "
    "so the recipe cannot start a request — there is no co-located default that "
    "routes the reply back to the originating event (retired pre-alpha, "
    "rf2-et4c1s / PR #5449). Add an explicit reply target. (The machine-form "
    "`:spawn {:machine-id :rf.http/managed …}` dispatches back to its parent "
    "and is the allowed exception — it is not matched here.)"
)

# 5b — reading the RETIRED co-located reply key `(:rf/reply …)`. The current
#      contract has no implicit reply routed back to the originating event, so a
#      handler that reads `:rf/reply` teaches the removed recipe. The negative
#      lookahead excludes the internal descriptor `:rf/reply-to`; the transient
#      `:rf.reply/work-id` is a different token and never matches.
RF_REPLY_READ_RE = re.compile(r":rf/reply(?![-\w])")

# 5c — bare `:work/id` asserted as a TRANSIENT reply field. The reply map /
#      payload / envelope spells the attempt identity `:rf.reply/work-id`; bare
#      `:work/id` is the durable ledger / machine-`:data` / correlation identity.
#      Fires only in a reply-map context carrying bare `:work/id` UNLESS the line
#      also spells `:rf.reply/work-id` or marks the id as the durable / ledger /
#      machine-`:data` / correlation / verification / abstract identity.
REPLYMAP_CTX_RE = re.compile(
    r"reply map|reply payload|reply envelope|transient reply", re.IGNORECASE
)
BARE_WORKID_RE = re.compile(r"`:work/id`")
WORKID_ALLOW_RE = re.compile(
    r":rf\.reply/work-id|ledger|durab|verification|abstract|:data|correlat",
    re.IGNORECASE,
)


def reply_contract_problems(line: str) -> list[str]:
    """Rule 5b/5c — retired Managed-HTTP reply-contract teaching on a single
    line: reading the removed `:rf/reply` co-located key, or spelling bare
    `:work/id` on a transient reply map. (5a is a cross-line fenced-block shape,
    scanned separately in `managed_http_recipe_problems`.)"""
    problems: list[str] = []
    if RF_REPLY_READ_RE.search(line):
        problems.append(
            "HTTP-REPLY-CO-LOCATED: `:rf/reply` is the RETIRED co-located reply "
            "key. The current Managed HTTP contract routes replies to an EXPLICIT "
            "target (`:reply-to` / `:on-success` / `:on-failure`), not back to "
            "the originating event, and omitting a target throws "
            ":rf.error/http-no-reply-target. Don't read `:rf/reply` — branch on "
            "`(:status reply)` in the reply handler (spec/Managed-Effects.md, "
            "patterns/managed-http.md)."
        )
    if (
        REPLYMAP_CTX_RE.search(line)
        and BARE_WORKID_RE.search(line)
        and not WORKID_ALLOW_RE.search(line)
    ):
        problems.append(
            "HTTP-REPLY-WORKID-SPELLING: the TRANSIENT reply map spells the "
            "attempt identity `:rf.reply/work-id`; bare `:work/id` is the durable "
            "ledger / machine-`:data` / correlation identity. Don't put bare "
            "`:work/id` on the reply map (one fact, two spellings across the "
            "record↔reply boundary — spec/Managed-Effects.md)."
        )
    return problems


def managed_http_recipe_problems(text: str) -> list[tuple[int, str]]:
    """Rule 5a — a fenced code block whose fx-form `[:rf.http/managed …]`
    invocation carries a `:request` args map but names no reply target is the
    retired targetless canonical recipe. Returns (opening-fence-lineno, message)
    tuples. Scanned per-FENCED-BLOCK (the tokens span lines of one block); the
    machine-form `:machine-id :rf.http/managed` spawn and the
    `[:rf.http/managed-abort …]` dispatch are deliberately not matched."""
    problems: list[tuple[int, str]] = []
    in_block = False
    block_start = 0
    block_lines: list[str] = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        if FENCE_RE.match(line):
            if not in_block:
                in_block, block_start, block_lines = True, lineno, []
            else:
                body = "\n".join(block_lines)
                if (
                    FX_MANAGED_RE.search(body)
                    and HTTP_REQUEST_RE.search(body)
                    and not HTTP_REPLY_TARGET_RE.search(body)
                ):
                    problems.append((block_start, MANAGED_HTTP_TARGETLESS_MSG))
                in_block, block_lines = False, []
        elif in_block:
            block_lines.append(line)
    return problems


# --- Rule 6: UIx/Helix hooks stateful-component guidance (rf2-adm10 repair).
#     The hooks adapters (UIx / Helix) bridge a stateful component with an
#     ordinary `defui` / `defnc` plus the `use-subscribe` (read subs) and
#     `use-frame` (carry the frame) hooks — NOT the Reagent `reg-view` /
#     render-time `capture-frame` / `:contextType` idiom. `use-frame` is
#     `capture-frame` in hook position; it reads the surrounding frame-provider /
#     frame-root through React context, which a bare no-arg `capture-frame` in a
#     plain hooks component cannot (it reads only the dynamic-var tier and raises
#     under a context-only frame). `reg-view*` is optional on these adapters —
#     registry addressing only, never the source of a Reagent `:contextType`.
#     Two narrow guards lock the fix (no general prose parser):
#
#     6a — the two authoritative hooks-guidance leaves (patterns/
#          stateful-components.md and references/fundamentals/frames.md) MUST
#          each name `use-frame` AND `use-subscribe`. Dropping either token means
#          the corrected hooks frame-carry / sub-read spelling regressed back to
#          a Reagent-only idiom. Positive-presence, per-file.
#     6b — a scanned-leaf line MUST NOT attribute a Reagent `:contextType` to a
#          hooks adapter. Fires on a line co-locating `:contextType` with `UIx`
#          or `Helix`, UNLESS the line carries a negation / Reagent-only cue (the
#          corrected prose says the hooks adapters have *no* `:contextType`).
#          Line-scoped and cue-gated — a Reagent-only `:contextType` line (no
#          UIx/Helix on it) never fires.
HOOKS_LEAF_REQUIRED = (
    ("patterns", "stateful-components.md"),
    ("references", "fundamentals", "frames.md"),
)
HOOKS_REQUIRED_TOKENS = ("use-frame", "use-subscribe")
CONTEXTTYPE_RE = re.compile(r":contextType\b")
HOOKS_ADAPTER_RE = re.compile(r"\b(?:UIx|Helix)\b")
# Negation / scoping cues that mark a legitimate "the hooks adapters have NO
# :contextType" / "unlike Reagent" line. Word-boundaried so `non-reg-view` does
# not read as a `no` cue.
CONTEXTTYPE_ALLOW_RE = re.compile(
    r"\bno\b|\bnot\b|\bnever\b|\bwithout\b|\bunlike\b|Reagent-only",
    re.IGNORECASE,
)
HOOKS_CONTEXTTYPE_MSG = (
    "HOOKS-CONTEXTTYPE: this line attributes a Reagent `:contextType` to a hooks "
    "adapter (UIx / Helix). `:contextType` is Reagent's class-component "
    "mechanism; the hooks adapters read the surrounding frame-provider / "
    "frame-root through the `use-subscribe` / `use-frame` hooks (React context "
    "in hook position) and have NO `:contextType`. `reg-view*` on these adapters "
    "is optional registry addressing, never a source of a `:contextType`. State "
    "the hooks spelling (or say the hooks adapters have *no* `:contextType`)."
)


def contextype_problems(line: str) -> list[str]:
    """Rule 6b — a single line that co-locates `:contextType` with a hooks
    adapter (UIx / Helix) and carries no negation / Reagent-only cue is the
    retired 'reg-view* gives UIx/Helix a :contextType' teaching."""
    if (
        CONTEXTTYPE_RE.search(line)
        and HOOKS_ADAPTER_RE.search(line)
        and not CONTEXTTYPE_ALLOW_RE.search(line)
    ):
        return [HOOKS_CONTEXTTYPE_MSG]
    return []


def hooks_leaf_missing_tokens(body: str) -> list[str]:
    """Rule 6a — tokens a hooks-guidance leaf must name (`use-frame`,
    `use-subscribe`) that are absent from its body."""
    return [t for t in HOOKS_REQUIRED_TOKENS if t not in body]


# --- Rule 3: launcher points at BOTH canonical files, without regrowing the
#     tree / locks sections. Operates on the whole authoring-prompt.md body
#     (not the line-by-line leaf scan). `design.md` / `inputs.md` are the
#     normative source; the launcher must cite each so a reauthoring session
#     reads them first, and must not paste a second copy of the material they
#     own.
DESIGN_REF_RE = re.compile(r"\bdesign\.md\b")
INPUTS_REF_RE = re.compile(r"\binputs\.md\b")
# A regrown file-structure tree: box-drawing branch glyphs (├── / └── / │) —
# the only reason they appear in this prose is a copied directory listing.
TREE_REGROWTH_RE = re.compile(r"[├└]──")
# A regrown normative block: the copied "Locks to preserve verbatim" or
# "Cardinal rules to bake in" heading/label the launcher used to carry.
LOCKS_REGROWTH_RE = re.compile(
    r"(?im)^\s*[>*#\s-]*\**\s*(?:locks to preserve verbatim"
    r"|cardinal rules to bake in)\b"
)


def launcher_problems(text: str) -> list[str]:
    """Rule 3 — the authoring-prompt.md launcher must point at both canonical
    files and must not regrow the tree / locks sections. Takes the raw body so
    the self-test can pass fixtures directly."""
    problems: list[str] = []
    if not DESIGN_REF_RE.search(text):
        problems.append(
            "LAUNCHER-CANONICAL: spec/authoring-prompt.md no longer points at "
            "spec/design.md — the normative source for the locked decisions "
            "(L1–L11), the file structure, and the cardinal rules. A "
            "reauthoring session must be told to read design.md first."
        )
    if not INPUTS_REF_RE.search(text):
        problems.append(
            "LAUNCHER-CANONICAL: spec/authoring-prompt.md no longer points at "
            "spec/inputs.md — the normative source for the canonical inputs and "
            "the §6 update procedure. Cite it in the ordered reads."
        )
    if TREE_REGROWTH_RE.search(text):
        problems.append(
            "LAUNCHER-REGROWTH: spec/authoring-prompt.md regrew the file-"
            "structure tree (box-drawing ├── / └── glyphs). The locked layout "
            "lives ONLY in design.md §5 — link it, do not paste a second copy "
            "(a duplicate is exactly what drifts)."
        )
    if LOCKS_REGROWTH_RE.search(text):
        problems.append(
            "LAUNCHER-REGROWTH: spec/authoring-prompt.md regrew a normative "
            "block (a 'Locks to preserve verbatim' / 'Cardinal rules to bake "
            "in' section). The L1–L11 locks and the cardinal rules live ONLY in "
            "design.md §3 / SKILL.md §Cardinal rules — reference them, don't "
            "restate a divergent copy."
        )
    return problems


def beadid_problems(line: str) -> list[str]:
    problems: list[str] = []
    for m in BEADID_RE.finditer(line):
        problems.append(
            f"BEAD-ID-LEAK: `{m.group(0)}` is an internal tracker id — "
            "user-facing leaves (SKILL.md / README.md / examples-map.md / "
            "references|patterns|decision-trees/**) carry NO `rf2-` ids "
            "(spec/design.md L10). Replace with public, stable evidence: an EP "
            "number, a spec section link, a fixture name, an API entry, or a "
            "fully-qualified public PR link. (bead ids stay only in the skill's "
            "spec/ meta-docs.)"
        )
    return problems


def posture_problems(line: str) -> list[str]:
    problems: list[str] = []
    if BASH_GATE_RE.search(line):
        problems.append(
            "VERIFY-POSTURE-BASH: this is an authoring-only skill (Q14 / L3 — "
            "spec/design.md). The author runs the tests / compiler / app; the "
            "skill stops at writing the code. Remove the gate-running Bash "
            "allow-list entry. (If Q14 is unlocked, flip the design/index "
            "rationale first, then retire this rule.)"
        )
    if VERIFY_PROSE_RE.search(line):
        problems.append(
            "VERIFY-POSTURE-PROSE: this is an authoring-only skill (Q14 / L3 — "
            "spec/design.md). Don't instruct the agent to run a gate before "
            "declaring done; name the gate for the AUTHOR to run instead. "
            "(If Q14 is unlocked, flip the design/index rationale first, then "
            "retire this rule.)"
        )
    return problems


def find_drift(files: list[Path]) -> tuple[list[str], int]:
    problems: list[str] = []
    lines_checked = 0
    if not (SKILL_DIR / "SKILL.md").is_file():
        problems.append(
            "SETUP: skills/re-frame2/SKILL.md missing — the guard's scan anchor "
            "drifted from the skill layout."
        )
    for path in files:
        if not path.is_file():
            continue
        rel = path.relative_to(REPO_ROOT)
        body = _slurp(path)
        for lineno, line in enumerate(body.splitlines(), start=1):
            lines_checked += 1
            for label in (beadid_problems(line) + posture_problems(line)
                          + reply_contract_problems(line)
                          + contextype_problems(line)):
                problems.append(f"{rel}:{lineno}: {label}\n    {line.strip()}")
        # Rules 4 & 5a — the machine-registration footgun and the targetless
        # Managed-HTTP recipe are cross-line shapes (their tokens land on
        # different lines of one fenced block), so they are scanned per-block
        # rather than per-line.
        for start_lineno, label in machine_handler_recipe_problems(body):
            problems.append(f"{rel}:{start_lineno}: {label}")
        for start_lineno, label in managed_http_recipe_problems(body):
            problems.append(f"{rel}:{start_lineno}: {label}")

    # Rule 6a — the two authoritative hooks-guidance leaves must name both hook
    # idioms (`use-frame` carries the frame, `use-subscribe` reads subs) so the
    # corrected UIx/Helix stateful-component spelling can't silently regress to a
    # Reagent-only idiom. Positive-presence, per required leaf.
    for parts in HOOKS_LEAF_REQUIRED:
        leaf = SKILL_DIR.joinpath(*parts)
        rel = leaf.relative_to(REPO_ROOT)
        if not leaf.is_file():
            problems.append(
                f"{rel}: SETUP: authoritative UIx/Helix hooks-guidance leaf "
                "missing — the Rule 6a anchor drifted from the skill layout."
            )
            continue
        for token in hooks_leaf_missing_tokens(_slurp(leaf)):
            problems.append(
                f"{rel}: HOOKS-IDIOM-MISSING: this authoritative UIx/Helix "
                f"stateful-component leaf no longer names `{token}`. The hooks "
                "adapters read subs with `use-subscribe` and carry the frame "
                "with the `use-frame` hook (the hook-position spelling of "
                "capture-frame) — not the Reagent reg-view / render-time "
                "capture-frame idiom. Restore the `{token}` spelling."
                .replace("{token}", token)
            )

    # Rule 3 — the launcher (authoring-prompt.md) is checked as a whole body,
    # not line-by-line, and lives under spec/ (out of the leaf scan above).
    if not AUTHORING_PROMPT.is_file():
        problems.append(
            "SETUP: skills/re-frame2/spec/authoring-prompt.md missing — the "
            "launcher-canonical check (Rule 3) has no anchor."
        )
    else:
        rel = AUTHORING_PROMPT.relative_to(REPO_ROOT)
        for label in launcher_problems(_slurp(AUTHORING_PROMPT)):
            problems.append(f"{rel}: {label}")
    return problems, lines_checked


def _slurp(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_DIR.is_dir():
        sys.stderr.write(f"error: re-frame2 skill not found at {SKILL_DIR}\n")
        return 2

    files = scanned_files()
    problems, lines_checked = find_drift(files)

    if verbose:
        print(
            "re-frame2 no-bead-id + verify-posture + launcher-canonical + "
            "machine-handler-recipe + managed-http-recipe + uix-helix-hooks "
            "guard: scanned "
            f"{len(files)} user-facing leaves ({lines_checked} lines) plus the "
            "spec/authoring-prompt.md launcher."
        )

    if not problems:
        if verbose:
            print(
                "re-frame2-drift: no bead-id leaks, no agent-run verification-"
                "posture drift, no bare reg-event + make-machine-handler recipe, "
                "no retired Managed-HTTP reply-contract teaching, the UIx/Helix "
                "hooks leaves name use-frame + use-subscribe with no "
                ":contextType misattribution, and the "
                "launcher points at design.md + inputs.md without regrowing the "
                "tree / locks."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\nre-frame2-drift: {len(problems)} drift issue(s) — keep internal "
        "bead ids out of the user-facing leaves (L10), keep the "
        "authoring-only verification posture (Q14 / L3: the author runs the "
        "gates, the skill names them), author machines with reg-machine (not a "
        "bare reg-event + make-machine-handler recipe), address every Managed-"
        "HTTP reply with an explicit :reply-to/:on-success/:on-failure (no "
        "retired co-located `:rf/reply` default), keep the UIx/Helix hooks "
        "stateful-component leaves naming use-frame + use-subscribe (never a "
        "reg-view / :contextType idiom), and keep the launcher "
        "pointing at the canonical design.md + inputs.md instead of re-holding "
        "the tree / locks."
    )
    return 1


# ---------------------------------------------------------------------------
# Self-test — exercises the line classifiers against in-memory fixtures so the
# guard itself can't silently rot. Mirrors the --self-test convention in the
# sibling check_skill_*.py guards.
# ---------------------------------------------------------------------------

def _self_test() -> int:
    failures = 0

    def expect(fn, line: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(fn(line))
        if got != dirty:
            print(
                f"SELF-TEST FAIL ({label}): expected dirty={dirty}, got "
                f"{got} for: {line!r}"
            )
            failures += 1

    # --- Rule 1: bead-id leak. LEAK fixtures (the exact finding-1 shapes).
    expect(
        beadid_problems,
        "EP-0008 (rf2-hhutya) promoted the production-reachable SSR error categories.",
        dirty=True, label="A1 plain bead id beside an EP number",
    )
    expect(
        beadid_problems,
        "the dual-partition recompute trigger is a SILENT regression (rf2-d3fb7.1).",
        dirty=True, label="A2 bead id with .N sub-task suffix",
    )
    # CLEAN fixtures — the corrected public-evidence wording must NOT flag.
    expect(
        beadid_problems,
        "EP-0008 promoted the production-reachable SSR error categories.",
        dirty=False, label="B1 EP number alone, no bead id",
    )
    expect(
        beadid_problems,
        "the runtime records history per `spec/005-StateMachines.md` §History.",
        dirty=False, label="B2 spec section link, no bead id",
    )
    expect(
        beadid_problems,
        "a fully-qualified public PR link day8/re-frame2#2863 is fine.",
        dirty=False, label="B3 public PR ref is not a bead id",
    )

    # --- Rule 2a: Bash gate allow-list entry. DRIFT fixtures.
    expect(
        posture_problems,
        "  - Bash(clojure -M:test)",
        dirty=True, label="C1 clojure test allow-list",
    )
    expect(
        posture_problems,
        "  - Bash(npm run test:*)",
        dirty=True, label="C2 npm test allow-list",
    )
    expect(
        posture_problems,
        "  - Bash(shadow-cljs compile *)",
        dirty=True, label="C3 shadow-cljs compile allow-list",
    )
    expect(
        posture_problems,
        "  - Bash(clj-kondo --lint *)",
        dirty=True, label="C4 clj-kondo lint allow-list",
    )
    # CLEAN — non-gate allow-list entries must NOT flag.
    expect(
        posture_problems,
        "  - Read",
        dirty=False, label="D1 Read tool entry",
    )
    expect(
        posture_problems,
        "  - mcp__re-frame2-story-mcp__register-variant",
        dirty=False, label="D2 story-mcp authoring tool entry",
    )

    # --- Rule 2b: agent-run verification prose. DRIFT fixtures.
    expect(
        posture_problems,
        "## Verify what you changed",
        dirty=True, label="E1 verify-section heading",
    )
    expect(
        posture_problems,
        "run the nearest relevant gate before declaring done — do not hand off unverified changes.",
        dirty=True, label="E2 run-gate-before-done instruction",
    )
    expect(
        posture_problems,
        "It is not a tutorial. Verifying what you wrote is in scope: run the gate.",
        dirty=True, label="E3 verifying-is-in-scope clause",
    )
    # CLEAN — the reconciled author-runs-it wording must NOT flag.
    expect(
        posture_problems,
        "Name the nearest relevant gate concretely so the author can run it.",
        dirty=False, label="F1 author-runs-it hand-off wording",
    )
    expect(
        posture_problems,
        "The skill writes the test; the author runs the suite.",
        dirty=False, label="F2 author-runs-suite wording",
    )

    # --- Rule 3: launcher points at both canonical files without regrowing the
    # tree / locks. `launcher_problems` takes the WHOLE body, so these fixtures
    # are multi-line prose, not single lines.
    clean_launcher = (
        "Read spec/design.md (the normative locks + file structure) then "
        "spec/inputs.md (the canonical inputs). Write the skill to the layout "
        "locked in design.md §5; honour the L1-L11 locks in design.md §3."
    )
    expect(
        launcher_problems, clean_launcher,
        dirty=False, label="G1 launcher cites both canonical files, no regrowth",
    )
    expect(
        launcher_problems,
        "Read spec/design.md then write the skill.",  # no inputs.md ref
        dirty=True, label="G2 launcher drops the inputs.md pointer",
    )
    expect(
        launcher_problems,
        "Read spec/inputs.md then write the skill.",  # no design.md ref
        dirty=True, label="G3 launcher drops the design.md pointer",
    )
    expect(
        launcher_problems,
        clean_launcher + "\n```\nskills/re-frame2/\n├── SKILL.md\n```",
        dirty=True, label="G4 launcher regrew the file-structure tree",
    )
    expect(
        launcher_problems,
        clean_launcher + "\n\n> *Locks to preserve verbatim:*\n> *- L3 …*",
        dirty=True, label="G5 launcher regrew the locks block",
    )
    expect(
        launcher_problems,
        clean_launcher + "\n\n*Cardinal rules to bake in (these go in SKILL.md):*",
        dirty=True, label="G6 launcher regrew the cardinal-rules block",
    )

    # --- Rule 4: machine-registration footgun. `machine_handler_recipe_problems`
    # takes the WHOLE body (the two tokens span lines of one fenced block), so
    # these fixtures are multi-line prose with fences.
    dirty_recipe = (
        "Author the boot flow:\n\n"
        "```clojure\n"
        "(rf/reg-event :app/boot\n"
        "  (re-frame.machines/make-machine-handler\n"
        "    {:initial :configuring :states {}}))\n"
        "```\n"
    )
    expect(
        machine_handler_recipe_problems, dirty_recipe,
        dirty=True, label="H1 fenced reg-event wrapping make-machine-handler",
    )
    dirty_recipe_alias = (
        "```clojure\n"
        "(rf/reg-event :ws/connection\n"
        "  (machines/make-machine-handler\n"
        "    {:initial :disconnected}))\n"
        "```\n"
    )
    expect(
        machine_handler_recipe_problems, dirty_recipe_alias,
        dirty=True, label="H2 aliased machines/make-machine-handler recipe",
    )
    # CLEAN — the corrected reg-machine recipe.
    clean_recipe = (
        "Author it with reg-machine:\n\n"
        "```clojure\n"
        "(rf/reg-machine :app/boot\n"
        "  {:initial :configuring :states {}})\n"
        "(rf/dispatch [:app/boot [:rf.machine/start]])\n"
        "```\n"
    )
    expect(
        machine_handler_recipe_problems, clean_recipe,
        dirty=False, label="I1 fenced reg-machine recipe (no footgun)",
    )
    # CLEAN — inline (non-fenced) advanced-note warning that names the shape.
    clean_inline_warning = (
        "> **Advanced — make-machine-handler.** Registering it by hand, "
        "`(rf/reg-event id meta (make-machine-handler spec))`, does not stamp "
        "the :rf/machine? metadata, and a [:schemas :data] spec throws "
        ":rf.error/machine-schema-requires-reg-machine. Use reg-machine."
    )
    expect(
        machine_handler_recipe_problems, clean_inline_warning,
        dirty=False, label="I2 inline advanced-note warning prose is allowed",
    )
    # CLEAN — a fenced block with a plain reg-event and no machine handler.
    clean_simple = (
        "```clojure\n"
        "(rf/reg-event :app/init (fn [_ _] {:fx [[:dispatch [:config/load]]]}))\n"
        "```\n"
    )
    expect(
        machine_handler_recipe_problems, clean_simple,
        dirty=False, label="I3 fenced plain reg-event (no machine handler)",
    )

    # --- Rule 5a: targetless fx-form :rf.http/managed recipe (rf2-723lgn).
    #     `managed_http_recipe_problems` takes the WHOLE body (the tokens span
    #     lines of one fenced block), so these fixtures are multi-line prose
    #     with fences.
    dirty_targetless_http = (
        "Issue the request:\n\n"
        "```clojure\n"
        "(rf/reg-event :article/load\n"
        "  (fn [_ _]\n"
        "    {:fx [[:rf.http/managed {:request {:url \"/x\"} :decode S}]]}))\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, dirty_targetless_http,
        dirty=True, label="J1 fx-form :rf.http/managed request with no reply target",
    )
    # CLEAN — the corrected fx recipe with an explicit reply target.
    clean_http_reply_to = (
        "```clojure\n"
        "{:fx [[:rf.http/managed {:request {:url \"/x\"} :reply-to [:article/replied]}]]}\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, clean_http_reply_to,
        dirty=False, label="K1 fx-form request WITH :reply-to (explicit address)",
    )
    clean_http_split_sugar = (
        "```clojure\n"
        "{:fx [[:rf.http/managed {:request {:url \"/x\"}\n"
        "                         :on-success [:ok] :on-failure [:err]}]]}\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, clean_http_split_sugar,
        dirty=False, label="K2 fx-form request WITH :on-success/:on-failure split sugar",
    )
    # CLEAN — the machine-form spawn dispatches back to its parent; no :reply-to.
    clean_http_machine_form = (
        "```clojure\n"
        "{:authenticating\n"
        " {:spawn {:machine-id :rf.http/managed :data {:request {:url \"/login\"}}}}}\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, clean_http_machine_form,
        dirty=False, label="K3 machine-form :spawn of :rf.http/managed (no reply key needed)",
    )
    # CLEAN — the abort dispatch is not a request recipe.
    clean_http_abort = (
        "```clojure\n"
        "(rf/dispatch [:rf.http/managed-abort req-id])\n"
        "```\n"
    )
    expect(
        managed_http_recipe_problems, clean_http_abort,
        dirty=False, label="K4 :rf.http/managed-abort dispatch (no request)",
    )

    # --- Rule 5b: reading the retired co-located `:rf/reply` key. DRIFT fixture.
    expect(
        reply_contract_problems,
        "(rf/reg-event :article/load (fn [_ [_ msg]] (when-let [r (:rf/reply msg)] r)))",
        dirty=True, label="L1 reads the retired co-located :rf/reply key",
    )
    # CLEAN — the current explicit-target contract and the INTERNAL descriptor.
    expect(
        reply_contract_problems,
        "The request names `:reply-to [:article/replied]`; the handler branches on `(:status reply)`.",
        dirty=False, label="M1 explicit :reply-to target, no :rf/reply read",
    )
    expect(
        reply_contract_problems,
        "The public `:reply-to` normalizes to the internal `:rf/reply-to` descriptor.",
        dirty=False, label="M2 internal :rf/reply-to descriptor is not the retired :rf/reply",
    )

    # --- Rule 5c: bare :work/id on a transient reply map. DRIFT fixture.
    expect(
        reply_contract_problems,
        "The reply map exposes `:status`, `:value`, and `:work/id`.",
        dirty=True, label="N1 bare :work/id asserted on the transient reply map",
    )
    # CLEAN — the transient spelling, and legitimate durable-ledger prose.
    expect(
        reply_contract_problems,
        "The durable ledger row keeps bare `:work/id`; the transient reply map spells `:rf.reply/work-id`.",
        dirty=False, label="O1 reply map spells :rf.reply/work-id; ledger keeps bare :work/id",
    )
    expect(
        reply_contract_problems,
        "The reply envelope correlates a late completion by the child's `:work/id` for stale suppression.",
        dirty=False, label="O2 :work/id as the correlation identity is allowed",
    )
    expect(
        reply_contract_problems,
        "The durable work-ledger row is keyed by bare `:work/id`.",
        dirty=False, label="O3 durable-ledger :work/id prose (no reply-map context)",
    )

    # --- Rule 6b: :contextType attributed to a hooks adapter (rf2-adm10).
    #     DRIFT fixture — the retired "reg-view* gives UIx/Helix a :contextType".
    expect(
        contextype_problems,
        "A plain UIx / Helix fn registers via `reg-view*`, which gives it a live scope (a `:contextType`).",
        dirty=True, label="P1 reg-view* :contextType attributed to UIx/Helix",
    )
    # CLEAN — the corrected prose says the hooks adapters have NO :contextType.
    expect(
        contextype_problems,
        "The hooks adapters (UIx / Helix) read the frame via `use-subscribe` / `use-frame` and need no `:contextType`.",
        dirty=False, label="Q1 hooks adapters explicitly have no :contextType",
    )
    # CLEAN — a Reagent-only :contextType line (no UIx/Helix on it) never fires.
    expect(
        contextype_problems,
        "A `reg-view`-wrapped Reagent component participates via `:contextType`.",
        dirty=False, label="Q2 Reagent-only :contextType line (no hooks adapter named)",
    )

    # --- Rule 6a: a hooks-guidance leaf must name use-frame AND use-subscribe.
    #     hooks_leaf_missing_tokens returns [] (clean) / a non-empty list (drift).
    expect(
        hooks_leaf_missing_tokens,
        "UIx bridges with an ordinary defui plus use-subscribe; carry the frame with the use-frame hook.",
        dirty=False, label="R1 leaf names both use-frame and use-subscribe",
    )
    expect(
        hooks_leaf_missing_tokens,
        "UIx bridges with an ordinary defui plus use-subscribe.",  # no use-frame
        dirty=True, label="R2 leaf drops the use-frame idiom",
    )

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
