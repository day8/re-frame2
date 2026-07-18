#!/usr/bin/env python3
"""Authority guard: the re-frame.ui synthesis plan's S0-coverage disposition.

`ai/findings/new-substrate-synthesis/12-implementation-plan.md` is the
authoritative, handoff-ready program plan for the re-frame.ui substrate. Its
§4 handoff checklist carries a ONE-TIME, durable disposition — the "S0 COVERAGE
PASS (2026-07-12) — SHIP verdict". That disposition does not drift: it records a
review that ran on a fixed date with a fixed verdict, plus the named
implementer-question -> bead mappings the coverage pass produced.

The durable record is the anchor checklist item ITSELF (honest provenance: the
pass ran 2026-07-12 during PR #6090's post-fold review; the program epic
`rf2-vxgfnd` records the same-dated pass in its description). A fresh worker
resolves every unresolved item from that single item — it does not chase a
Beads NOTES entry, and this guard makes no live Beads-API call.

This guard is CAUSAL — it proves the authority relationship, not merely that the
tokens co-occur — and DURABLE — a change that should trip it does. It pins,
inside the one named anchor item:

  1. the checked box, the "S0 COVERAGE PASS (2026-07-12) — SHIP" name+verdict;
  2. a BARE epic authority attribution (`rf2-vxgfnd` NOT written as a child id
     `rf2-vxgfnd.<n>`) — deleting the attribution while keeping the child-id
     mappings fails independently;
  3. the exact label -> owning-bead ASSOCIATIONS, each bound within its own
     "<item> -> <bead>" clause — swapping two owners, or dropping one of a
     clause's beads (e.g. the `.12` half of `rf2-vxgfnd.8 / .12`), fails.

Every id above is required as an EXACT tracker-id token (see `_exact_token_re`):
a near miss that merely contains the id — `rf2-vxgfndx`, `rf2-vxgfnd.9x`,
`rf2-vxgfnd.9.1`, `xrf2-vxgfnd` — names a different (nonexistent) bead and
therefore fails the check it looks like it satisfies.

It also forbids ONE drift-prone sentence shape anywhere in the plan's ACTIVE
text: an unqualified live-stage progress claim. Live stage/progress state is
owned by the epic and its beads, never by this plan. The rule has an honest,
deliberately small contract — it is NOT a general Markdown/prose parser:

  * KNOWN active-status syntax only — the closed set {"not (yet) started",
    "underway" / "under way", "in progress" / "in-progress"} co-occurring with a
    live-stage token S1..S7 on a line. S0 (the paper stage) is excluded: its
    coverage disposition is the durable anchor above.
  * HISTORICAL EXEMPTION requires a GENUINE dated snapshot — a snapshot/
    historical/"as of" qualifier AND an ISO-8601 date (YYYY-MM-DD) on the line.
    A bare "snapshot:" with no date does not exempt.
  * INACTIVE text does not create a false failure — HTML comment regions
    (`<!-- ... -->`) are blanked before the scan, so guidance prose inside a
    comment cannot trip the rule.

Exit code:
    0  the plan's S0 authority is intact and no unqualified live-stage claim
    1  the anchor is missing/unchecked/incomplete, or a live-stage claim drifted
    2  invocation / setup error (repo root or plan file not found)

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

# The authoritative plan, relative to the repo root.
PLAN_REL = "ai/findings/new-substrate-synthesis/12-implementation-plan.md"

# --- Durable S0 anchor tokens -------------------------------------------------
# The disposition's stable name + date. Appears on the checked list-item line.
ANCHOR_TOKEN = "S0 COVERAGE PASS (2026-07-12)"
# The verdict word, also on the anchor line.
ANCHOR_VERDICT = "SHIP"
# The durable authority: the epic that owns the S1-S7 program. Its BARE mention
# in the anchor item is the authority attribution (see _BARE_EPIC_RE); the child
# ids `rf2-vxgfnd.<n>` below are the per-item owners, a distinct thing.
ANCHOR_AUTHORITY = "rf2-vxgfnd"

# The arrow that binds each named item to its owning bead in the anchor prose.
_ARROW = "→"  # "→"

# A Markdown checklist item line. `box` captures the checkbox state (` ` or `x`).
_CHECKLIST_ITEM_RE = re.compile(r"^\s*-\s*\[(?P<box>[ xX])\]")

# --- Exact tracker-id tokens --------------------------------------------------
# The tracker's id alphabet: alphanumerics plus the `-` word separator (`_` is
# included for safety — it never splits an identifier in prose). A `.<digits>`
# suffix names a child of the id before it.
_ID_CHAR = r"[0-9A-Za-z_-]"


def _exact_token_re(token: str) -> re.Pattern[str]:
    """Compile `token` to match only as a COMPLETE tracker-id token.

    ONE structural rule, applied at every site that looks for a known id: the
    token matches only where it cannot be extended into a LONGER identifier.

      * RIGHT — not followed by an id character, and not followed by a `.<digit>`
        child suffix. So `rf2-vxgfndx`, `rf2-vxgfnd.9x` and `rf2-vxgfnd.90` are
        rejected (alphanumeric extension), as are `rf2-vxgfnd.9` for the bare
        epic and `rf2-vxgfnd.9.1` for the child `rf2-vxgfnd.9` (deeper-child
        extension). That second clause GENERALIZES the old bare-epic-only
        lookahead: exactness is now the guard's uniform rule, not a per-site
        special case.
      * LEFT — not preceded by an id character, so `xrf2-vxgfnd` is rejected.
        A token that itself begins with the `.` child separator carries its own
        left delimiter (it is the suffix half of the `<parent>.<n> / .<m>`
        shorthand), so no lookbehind applies to it — which is also what lets
        `.12` match a fully written `rf2-vxgfnd.12`, the same bead.

    A trailing sentence period is NOT an extension (`.` not followed by a digit),
    so prose like "... → rf2-vxgfnd.8." still resolves its owner.
    """
    left = "" if token.startswith(".") else rf"(?<!{_ID_CHAR})"
    return re.compile(left + re.escape(token) + rf"(?!{_ID_CHAR})(?!\.\d)")


# The authority attribution must be a BARE epic reference — the exact token
# `rf2-vxgfnd`, NOT a child id `rf2-vxgfnd.<n>`. This is what makes the
# attribution check CAUSAL: the child-id mappings (`rf2-vxgfnd.9`, ...) all
# contain the substring "rf2-vxgfnd", so a bare `in` test would pass even after
# the actual attribution clause is deleted. Exact-token matching rejects those
# — and equally rejects a renamed authority such as `rf2-vxgfndx`.
_BARE_EPIC_RE = _exact_token_re(ANCHOR_AUTHORITY)

# The named implementer-question -> owning-bead mappings the coverage pass
# produced. Each entry is (label, (bead, ...)): the label must occur on the
# LEFT of an arrow clause whose bead side carries ALL the listed bead ids, so a
# fresh worker can resolve every unresolved item from the plan alone. Validated
# as an ASSOCIATION (label bound to its own owner), not by independent
# co-occurrence — swapping owners between two labels trips it. The
# `static-override-lease Tier-3` fixture legitimately owns TWO beads
# (`rf2-vxgfnd.8 / .12`); both are required, so dropping `.12` trips it. The
# `:activity-hidden` mapping is the one this guard's source bead (rf2-vs60jg)
# de-fuzzed from the non-identifying phrase "the S2 evidence/Xray slice" to its
# exact owner rf2-vxgfnd.8 (S2b ViewCell, per 03 §4).
REQUIRED_MAPPINGS: list[tuple[str, tuple[str, ...]]] = [
    ("Q49", ("rf2-vxgfnd.9",)),
    ("Q51", ("rf2-vxgfnd.10",)),
    ("[S2-CONFIRM]", ("rf2-vxgfnd.7",)),
    ("static-override-lease Tier-3", ("rf2-vxgfnd.8", ".12")),
    (":activity-hidden", ("rf2-vxgfnd.8",)),
]

# --- Forbidden live-stage drift ----------------------------------------------
# A live implementation stage token (S1..S7). S0 is the paper stage and its
# "complete"/coverage disposition is durable, so S0 is deliberately excluded.
_LIVE_STAGE_TOKEN_RE = re.compile(r"\bS[1-7]\b")
# The closed set of KNOWN active-status syntax the rule recognizes causally.
# Kept deliberately small and literal — not a general progress-language parser.
_ACTIVE_STATUS_RE = re.compile(
    r"not\s+(?:yet\s+)?started"  # "not started" / "not yet started"
    r"|under\s?way"              # "underway" / "under way"
    r"|in[\s-]progress",         # "in progress" / "in-progress"
    re.IGNORECASE,
)
# A dated-snapshot exemption needs BOTH a snapshot/historical/"as of" qualifier
# AND a genuine ISO-8601 date on the line — the word alone does not exempt.
_SNAPSHOT_QUALIFIER_RE = re.compile(r"as[\s-]of|snapshot|historical", re.IGNORECASE)
_ISO_DATE_RE = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")
# HTML/Markdown comment span (possibly multi-line); blanked before the scan so
# inactive guidance prose cannot trip the live-stage rule.
_HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)


def _plan_path(repo_root: Path) -> Path:
    return repo_root / PLAN_REL


def _find_anchor_block(lines: list[str]) -> tuple[str | None, str, int]:
    """Locate the S0 coverage-pass checklist item.

    Returns (box_char, block_text, line_no):
      * box_char  — the checkbox state of the anchor item ('x'/'X'/' '),
                    or None if no anchor item is present at all.
      * block_text — the anchor item joined with its wrapped continuation
                    lines (up to the next checklist item / section / EOF).
      * line_no   — 1-based line number of the anchor item (0 if absent).

    The anchor item is the checklist line that carries both ANCHOR_TOKEN and
    ANCHOR_VERDICT — a single, self-contained line by construction, so no
    Markdown parsing is required.
    """
    anchor_idx = None
    box_char: str | None = None
    for idx, raw in enumerate(lines):
        m = _CHECKLIST_ITEM_RE.match(raw)
        if not m:
            continue
        if ANCHOR_TOKEN in raw and ANCHOR_VERDICT in raw:
            anchor_idx = idx
            box_char = m.group("box").lower()
            break

    if anchor_idx is None:
        return None, "", 0

    # Extend to the wrapped continuation of this list item: subsequent lines
    # until the next checklist item, a new section heading, or EOF.
    block = [lines[anchor_idx]]
    for raw in lines[anchor_idx + 1:]:
        if _CHECKLIST_ITEM_RE.match(raw) or raw.startswith("## "):
            break
        block.append(raw)
    return box_char, "\n".join(block), anchor_idx + 1


def _mapping_clauses(block: str) -> list[tuple[str, str]]:
    """Split the anchor block into (label-side, bead-side) arrow clauses.

    The anchor records ownership as a ';'-separated list of
    "<text naming the item> -> <owning bead id(s)>" clauses. Whitespace is
    normalized (so a line-wrapped clause reads as one), the block is split on
    ';', and each clause carrying an arrow is split ONCE at the first arrow.
    Binding each owner to the text before its own arrow is what makes the
    mapping check causal: a swapped owner lands in a different clause.
    """
    flat = re.sub(r"\s+", " ", block)
    clauses: list[tuple[str, str]] = []
    for clause in flat.split(";"):
        if _ARROW in clause:
            left, right = clause.split(_ARROW, 1)
            clauses.append((left, right))
    return clauses


def _bead_present(text: str, bead: str) -> bool:
    """True if `bead` occurs in `text` as an EXACT tracker-id token.

    An owner that is merely a fragment of some longer, nonexistent id does not
    resolve the item: `rf2-vxgfnd.90`, `rf2-vxgfnd.9x` and `rf2-vxgfnd.9.1` all
    fail to satisfy the owner `rf2-vxgfnd.9`. See `_exact_token_re`.
    """
    return _exact_token_re(bead).search(text) is not None


def check(repo_root: Path, verbose: bool = False) -> int:
    """Validate the plan's S0 authority. Returns the defect count (0 == OK)."""
    plan = _plan_path(repo_root)
    if not plan.is_file():
        sys.stderr.write(f"error: no synthesis plan at {plan}\n")
        return 1

    text = plan.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines()

    if verbose:
        sys.stderr.write(
            f"checking S0 authority in {plan.relative_to(repo_root)} "
            f"({len(lines)} lines)\n"
        )

    defects: list[str] = []

    # (1) The durable S0 coverage-pass anchor must exist and be CHECKED.
    box_char, block, line_no = _find_anchor_block(lines)
    if box_char is None:
        defects.append(
            f"  MISSING S0 ANCHOR: no checked list item carrying "
            f'"{ANCHOR_TOKEN}" + "{ANCHOR_VERDICT}" was found. The one-time S0 '
            "coverage-pass disposition must remain a checked, named anchor."
        )
        block = ""  # nothing to check the mappings against
    elif box_char != "x":
        defects.append(
            f"  UNCHECKED S0 ANCHOR: line {line_no} carries the S0 "
            f'coverage-pass disposition but its checkbox is "[{box_char}]", '
            'not "[x]". The SHIP disposition is durable and must stay checked.'
        )

    # (2) The durable authority attribution must be intact — a BARE `rf2-vxgfnd`
    #     reference, distinct from the `rf2-vxgfnd.<n>` child-id owners below.
    if block and not _BARE_EPIC_RE.search(block):
        defects.append(
            f"  MISSING AUTHORITY: the S0 anchor no longer carries a bare "
            f'"{ANCHOR_AUTHORITY}" authority attribution (only child-id owners '
            f'"{ANCHOR_AUTHORITY}.<n>" remain). The item must name the epic that '
            "owns the S1-S7 program as its durable authority."
        )

    # (3) Every named implementer-question -> bead ASSOCIATION must hold: the
    #     label must own an arrow clause carrying ALL its beads.
    if block:
        clauses = _mapping_clauses(block)
        for label, beads in REQUIRED_MAPPINGS:
            owner_sides = [right for (left, right) in clauses if label in left]
            if not owner_sides:
                defects.append(
                    f"  MISSING MAPPING: no \"{label} {_ARROW} <bead>\" clause "
                    "is present in the S0 anchor item; the unresolved item is "
                    "not resolvable from the plan alone."
                )
                continue
            missing = [
                b for b in beads
                if not any(_bead_present(side, b) for side in owner_sides)
            ]
            if missing:
                defects.append(
                    f'  WRONG MAPPING: "{label}" is not bound to '
                    f"{' + '.join(beads)} — its clause is missing "
                    f"{' + '.join(missing)}. Each named item must own its exact "
                    "bead(s) inside its own arrow clause."
                )

    # (4) No unqualified live-stage progress claim anywhere in the plan's ACTIVE
    #     text (HTML comments blanked so inactive prose is not a false failure).
    scan_text = _HTML_COMMENT_RE.sub(
        lambda m: re.sub(r"[^\n]", " ", m.group(0)), text
    )
    for idx, raw in enumerate(scan_text.splitlines(), start=1):
        if not (_ACTIVE_STATUS_RE.search(raw) and _LIVE_STAGE_TOKEN_RE.search(raw)):
            continue
        # Exempt only a GENUINE dated snapshot: qualifier word AND an ISO date.
        if _SNAPSHOT_QUALIFIER_RE.search(raw) and _ISO_DATE_RE.search(raw):
            continue
        defects.append(
            f"  LIVE-STAGE DRIFT: line {idx} states an unqualified live-stage "
            f"progress claim: {raw.strip()!r}. Live stage/progress state is "
            "owned by the epic and its beads; remove it, source it from that "
            'durable authority, or mark it as a dated snapshot ("as of '
            '<YYYY-MM-DD>").'
        )

    if defects:
        sys.stderr.write(
            f"\n{len(defects)} synthesis-plan authority defect(s) in "
            f"{plan.relative_to(repo_root)}:\n\n"
        )
        for line in defects:
            sys.stderr.write(line + "\n")
        sys.stderr.write(
            "\nFix: restore the checked "
            f'"{ANCHOR_TOKEN} — {ANCHOR_VERDICT}" anchor with a bare {ANCHOR_AUTHORITY} '
            "authority attribution and the named item -> bead mappings bound "
            "in their own arrow clauses, and keep volatile live-stage status "
            "out of the plan's active text (the epic + its children own it). "
            "See the DURABLE ANCHOR comment in the plan.\n"
        )
    elif verbose:
        sys.stderr.write("S0 coverage-pass authority is intact.\n")

    return len(defects)


# --------------------------------------------------------------------------
# Self-tests — hermetic, generated into a temp dir (mirrors the style of
# scripts/check_ep_status_sync.py). Each fixture is a mini-repo: an mkdocs.yml
# at the root (so a repo-root guard would accept it) plus the plan file under
# ai/findings/new-substrate-synthesis/. Each mutation isolates exactly one
# failure and asserts an EXACT defect count, so "fails independently" is proven.
# --------------------------------------------------------------------------

# A minimal but faithful S0 anchor block — the shape the real plan carries. The
# authority attribution is a BARE `rf2-vxgfnd` (the epic), separate from the
# `rf2-vxgfnd.<n>` child-id owners in the mapping clauses.
_GOOD_ANCHOR = (
    "- [x] **S0 COVERAGE PASS (2026-07-12) — SHIP verdict** — durable "
    "disposition recorded here (the coverage pass ran 2026-07-12 in PR #6090's "
    "post-fold review); authority: the epic `rf2-vxgfnd` owns the S1–S7 "
    "program and records the same pass. Named mappings: Q49 → rf2-vxgfnd.9; "
    "Q51 → rf2-vxgfnd.10; the four [S2-CONFIRM] items → rf2-vxgfnd.7; the "
    "static-override-lease Tier-3 fixture → rf2-vxgfnd.8 / .12; the "
    "`:activity-hidden` retroactive-annotation evidence schema → rf2-vxgfnd.8 "
    "(S2b ViewCell, per 03 §4).\n"
)

_PLAN_HEAD = "# 12 — Implementation plan\n\n## 4. Handoff checklist\n\n"


def _write_fixture(root: Path, plan_body: str) -> None:
    plan = root / PLAN_REL
    plan.parent.mkdir(parents=True, exist_ok=True)
    (root / "mkdocs.yml").write_text("site_name: fixture\n", encoding="utf-8")
    plan.write_text(plan_body, encoding="utf-8")


def _build_self_test_fixtures(base: Path) -> None:
    # good: checked anchor, bare authority, exact mappings, no drift -> passes.
    _write_fixture(base / "good", _PLAN_HEAD + _GOOD_ANCHOR)

    # unchecked: the durable disposition's box flipped to [ ] -> 1 defect.
    _write_fixture(
        base / "unchecked",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("- [x]", "- [ ]", 1),
    )

    # missing_anchor: the whole S0 disposition deleted -> 1 defect.
    _write_fixture(
        base / "missing_anchor",
        _PLAN_HEAD + "- [x] Some other unrelated checklist item.\n",
    )

    # dropped_authority: the bare epic attribution removed, ALL child-id
    # mappings left intact -> exactly 1 authority defect (fails independently).
    _write_fixture(
        base / "dropped_authority",
        _PLAN_HEAD
        + _GOOD_ANCHOR.replace(
            "authority: the epic `rf2-vxgfnd` owns the S1–S7 "
            "program and records the same pass. ",
            "",
        ),
    )

    # swapped_owners: Q49 and Q51 owners exchanged -> both associations break
    # -> exactly 2 defects (the co-occurrence tokens are all still present).
    _write_fixture(
        base / "swapped_owners",
        _PLAN_HEAD
        + _GOOD_ANCHOR.replace(
            "Q49 → rf2-vxgfnd.9; Q51 → rf2-vxgfnd.10",
            "Q49 → rf2-vxgfnd.10; Q51 → rf2-vxgfnd.9",
        ),
    )

    # dropped_second_bead: the `.12` half of the static-override-lease owner
    # deleted -> exactly 1 mapping defect.
    _write_fixture(
        base / "dropped_second_bead",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("rf2-vxgfnd.8 / .12", "rf2-vxgfnd.8"),
    )

    # --- Exact-token mutations (rf2-eipo0) --------------------------------
    # Each renames a required id into a DIFFERENT, nonexistent one while leaving
    # the id's text visibly "there". Every one must fail exactly its own check.

    # authority_suffix_extended: `rf2-vxgfnd` -> `rf2-vxgfndx`; the child-id
    # mappings are untouched -> exactly 1 authority defect.
    _write_fixture(
        base / "authority_suffix_extended",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("`rf2-vxgfnd`", "`rf2-vxgfndx`"),
    )

    # authority_prefix_extended: `rf2-vxgfnd` -> `xrf2-vxgfnd` (extended on the
    # LEFT) -> exactly 1 authority defect.
    _write_fixture(
        base / "authority_prefix_extended",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("`rf2-vxgfnd`", "`xrf2-vxgfnd`"),
    )

    # owner_suffix_extended: Q49's owner `rf2-vxgfnd.9` -> `rf2-vxgfnd.9x`
    # -> exactly 1 mapping defect (only Q49's association breaks).
    _write_fixture(
        base / "owner_suffix_extended",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("rf2-vxgfnd.9;", "rf2-vxgfnd.9x;"),
    )

    # owner_child_extended: Q49's owner -> the deeper child `rf2-vxgfnd.9.1`,
    # a different bead -> exactly 1 mapping defect.
    _write_fixture(
        base / "owner_child_extended",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("rf2-vxgfnd.9;", "rf2-vxgfnd.9.1;"),
    )

    # second_bead_suffix_extended: the abbreviated `.12` owner -> `.12x`
    # -> exactly 1 mapping defect.
    _write_fixture(
        base / "second_bead_suffix_extended",
        _PLAN_HEAD + _GOOD_ANCHOR.replace("rf2-vxgfnd.8 / .12", "rf2-vxgfnd.8 / .12x"),
    )

    # --- Positive controls: genuine id spellings must stay green ----------

    # full_second_bead: the `.12` shorthand written out as the full child id
    # names the SAME bead -> still passes (the rule is not over-tightened).
    _write_fixture(
        base / "full_second_bead",
        _PLAN_HEAD
        + _GOOD_ANCHOR.replace("rf2-vxgfnd.8 / .12", "rf2-vxgfnd.8 / rf2-vxgfnd.12"),
    )

    # sentence_final_owner: an owner ending a sentence is followed by a period,
    # which is not a child suffix -> still passes.
    _write_fixture(
        base / "sentence_final_owner",
        _PLAN_HEAD
        + _GOOD_ANCHOR.replace("rf2-vxgfnd.8 (S2b ViewCell, per 03 §4).", "rf2-vxgfnd.8."),
    )

    # missing_mapping: the :activity-hidden owner mapping stripped -> 1 defect.
    _write_fixture(
        base / "missing_mapping",
        _PLAN_HEAD
        + _GOOD_ANCHOR.replace(
            "the `:activity-hidden` retroactive-annotation evidence schema "
            "→ rf2-vxgfnd.8 (S2b ViewCell, per 03 §4).",
            "the evidence schema landed with S2.",
        ),
    )

    # not_started_drift: an unqualified "S3-S7 not started" claim -> 1 defect.
    _write_fixture(
        base / "not_started_drift",
        _PLAN_HEAD
        + _GOOD_ANCHOR
        + "\nS1 is complete and S2 core is verified S3-ready. S3–S7 not started.\n",
    )

    # underway_drift: the "underway" active-status syntax (previously missed by
    # a not-started-only rule) -> 1 defect.
    _write_fixture(
        base / "underway_drift",
        _PLAN_HEAD + _GOOD_ANCHOR + "\nS3 is underway now.\n",
    )

    # in_progress_drift: the "in progress" active-status syntax -> 1 defect.
    _write_fixture(
        base / "in_progress_drift",
        _PLAN_HEAD + _GOOD_ANCHOR + "\nS4 remains in progress.\n",
    )

    # undated_snapshot: a snapshot word but NO date does not exempt -> 1 defect.
    _write_fixture(
        base / "undated_snapshot",
        _PLAN_HEAD + _GOOD_ANCHOR + "\nSnapshot (historical): S3–S7 not started.\n",
    )

    # comment_drift: a drift claim inside an HTML comment is inactive -> passes.
    _write_fixture(
        base / "comment_drift",
        _PLAN_HEAD
        + _GOOD_ANCHOR
        + "\n<!-- guidance: do NOT restate that S3–S7 are not started here. -->\n",
    )

    # dated_snapshot: the drift shape but a GENUINE dated snapshot -> passes.
    _write_fixture(
        base / "dated_snapshot",
        _PLAN_HEAD
        + _GOOD_ANCHOR
        + "\nSnapshot as of 2026-07-12 (historical, not current): S3–S7 not "
        "started.\n",
    )


def _run_self_tests(verbose: bool = False) -> int:
    # (fixture, expected_defect_count). Exact counts prove each mutation
    # isolates exactly its own failure.
    cases: list[tuple[str, int]] = [
        ("good", 0),
        ("unchecked", 1),
        ("missing_anchor", 1),
        ("dropped_authority", 1),
        ("swapped_owners", 2),
        ("dropped_second_bead", 1),
        ("authority_suffix_extended", 1),
        ("authority_prefix_extended", 1),
        ("owner_suffix_extended", 1),
        ("owner_child_extended", 1),
        ("second_bead_suffix_extended", 1),
        ("full_second_bead", 0),
        ("sentence_final_owner", 0),
        ("missing_mapping", 1),
        ("not_started_drift", 1),
        ("underway_drift", 1),
        ("in_progress_drift", 1),
        ("undated_snapshot", 1),
        ("comment_drift", 0),
        ("dated_snapshot", 0),
    ]
    failures = 0
    with tempfile.TemporaryDirectory(prefix="synthesis_plan_authority_selftest_") as tmp:
        base = Path(tmp)
        _build_self_test_fixtures(base)
        for fixture, expected in cases:
            root = base / fixture
            saved_stderr = sys.stderr
            sys.stderr = _DevNull()
            try:
                defects = check(root, verbose=False)
            finally:
                sys.stderr = saved_stderr
            if defects == expected:
                if verbose:
                    sys.stderr.write(
                        f"self-test PASS: {fixture} (defects={defects})\n"
                    )
            else:
                sys.stderr.write(
                    f"self-test FAIL: {fixture} expected defects={expected}, "
                    f"got defects={defects}\n"
                )
                failures += 1
    if failures:
        sys.stderr.write(f"\n{failures} self-test failure(s).\n")
        return 1
    if verbose:
        sys.stderr.write(f"all {len(cases)} self-tests passed.\n")
    return 0


class _DevNull:
    def write(self, *_args, **_kwargs) -> int:
        return 0

    def flush(self) -> None:  # pragma: no cover
        return None


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Guard the re-frame.ui synthesis plan's S0 coverage-pass authority "
            "against status drift."
        ),
    )
    parser.add_argument(
        "--repo-root",
        default=None,
        help="Path to the repo root. Defaults to the script's grandparent.",
    )
    parser.add_argument(
        "--verbose", "-v", action="store_true", help="Print progress to stderr."
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run the bundled fixture-based self-tests and exit.",
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

    defects = check(repo_root, verbose=args.verbose)
    return 0 if defects == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
