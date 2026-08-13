#!/usr/bin/env python3
"""THE BUDGET-LEDGER GATE for Hicasso (rf2-hic-089).

`docs/design/hicasso/product/budgets.md` §9 is the RECONCILIATION LEDGER:
one row per registered budget line, each stating its own verdict, its
population, the instrument that read it, who owns it and where it was
dispositioned. Eight sections of that page state budgets; the ledger is
the one place they are all answered, and a summary nothing checks drifts
from the thing it summarises within a release.

WHAT THIS GATE IS NOT
---------------------
It is **not** a budget gate. It decides no budget, reads no instrument
and reddens on no breach. Two thirds of the ledger's rows are
distributional, and budgets.md §1 has already ruled that a hosted runner
may never source a distributional figure; §7 adds that such a row is
never converted into a flaky pull-request threshold. Turning a budget
into a blocking gate is `rf2-hic-071`'s, over a population that does not
exist yet. What is enforceable TODAY is the honesty of the record, and
that is exactly what this gate holds.

THE TWO THINGS IT EXISTS TO PREVENT, stated plainly:

  a BAND CROSSING read as a PASS. The operator froze the shell line at
  `1,024 B` on 2026-08-12 and adopted, with it, the rule that a
  confidence band crossing that line is UNRESOLVED rather than a pass.
  A two-valued ledger cannot hold that ruling: it must round every
  crossing to green or red, and whichever it picks is a fabrication.
  L1 gives the ledger a four-valued status of which exactly one is a
  pass, and L2 recomputes the verdict from the numbers so `MET` cannot
  be written over a crossing band by hand.

  a BREACH going SILENT. `rf2-hic-018` refused substrate remediation of
  the R=0 shell on the evidence and carried the row red on purpose. A
  gate that failed the build on that would demand a fix the programme
  has ruled against; a gate that greened it would be the normalisation
  budgets.md §5 forbids. So a `BREACH` row does not redden this gate —
  an UNRECORDED breach does. L3 is the whole of that: every not-green
  row names an owner and a disposition record, and that record has to
  exist and name the row.

THE RULES
---------
  L0  THE LEDGER IS READABLE AT ALL.
                     The marker comments that delimit the table are
                     present and every row has its eight cells. Deleting
                     a marker is the loudest way to empty the table, and
                     an empty table satisfies five of the six rules
                     below vacuously — so this one is reported in the
                     gate's own voice rather than as a traceback.
  L1  THE VOCABULARY IS CLOSED, AND ONE VALUE IS A PASS.
                     Status is `MET`, `BREACH`, `UNRESOLVED` or
                     `UNPINNED`. Only `MET` passes; this gate's own
                     report never folds the other three into it, and
                     prints each by name.
  L2  THE BAND DECIDES, AND A CEILING MAY NOT DUCK IT.
                     A byte ceiling in the Registered-line cell REQUIRES
                     a byte band in the Current-value cell — omitting
                     the band would otherwise be a way out from under
                     this rule. The verdict is then recomputed: band
                     wholly at or under the ceiling is `MET`, wholly
                     over is `BREACH`, a band containing it is
                     `UNRESOLVED`. A stated status that disagrees reds.
  L3  NO SILENT BREACH.
                     Every row names an authority (`rf2-…`). Every row
                     that is not `MET` also names a disposition link
                     whose target file exists, whose anchor resolves to
                     a heading, and whose section NAMES the row. A
                     pointer at a section that never mentions the row is
                     worse than no pointer: it reads as a disposition
                     and is not one.
  L4  COMPLETE, AND NOTHING INVENTED.
                     Every `D`/`S`/`U`/`C` id registered in budgets.md
                     OUTSIDE the ledger has exactly one ledger row, and
                     every ledger id is either registered there or in
                     EXTRA_ROWS below — which carries its own resolving
                     provenance anchor. Dropping a row is the cheapest
                     way to make a ledger green, so both directions are
                     checked.
  L5  THE LANDED ADJUDICATION IS PINNED.
                     Population per row and, where the operator froze
                     one, the registered line per row. `rf2-fe0l` made
                     S1–S5 package figures and left S6, S7 and U1–U4
                     where they were; a cell rewrite cannot promote a
                     bench-tree figure to a package one. The `1.25x`
                     cold-mount ceiling is a PROPOSAL pending the
                     operator's sitting, so it is refused in S6's and
                     C2's line cells by name, and any line cell written
                     as a proposal is refused generally.
  L6  THE LANE IS LEGAL.
                     Each instrument cell ends in its lane: `PR gate`,
                     `P-DEV-1 evidence run` or `none`. A DETERMINISTIC
                     row must name a witness FILE THAT EXISTS, in the
                     `PR gate` lane. A DISTRIBUTIONAL row may never name
                     the `PR gate` lane at all — that is budgets.md §1,
                     §2 and §7 mechanised, and it is what stops the 5%
                     heap comparison being wired to a hosted runner by a
                     later worker acting in good faith.

WHAT L6 DELIBERATELY DOES NOT CHECK is that a distributional row's
instrument is absent from the repository. The P0 heap ladder lives in
this repo and always has; what makes it illegal as a PR gate is its
LANE, not its address. Checking the address instead would red the honest
case and miss the dishonest one.

THE STATUS OF `UNPINNED`, since it is the value most easily mistaken for
a bookkeeping hole: it means no instrument for the row exists on the
governed population, so the evidence never reached the line. It is not a
softer `UNRESOLVED` — `UNRESOLVED` means evidence was taken and did not
decide. Ten rows are `UNPINNED` today and every one of them names, in
§9.2, the instrument or ruling that would move it.

Exit code:
    0  the ledger is internally honest (which is NOT "the budgets are met")
    1  at least one rule failed; each is printed with its L-number
"""

import argparse
import os
import re
import sys


# Cells quoted back in a failure message carry the ledger's em-dashes and
# `<=` glyphs, and a Windows console is cp1252.  Replace rather than raise:
# a gate that dies encoding its own error message reports nothing at all.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(errors="replace")


SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
PACKAGE_ROOT = os.path.dirname(SCRIPTS_DIR)                       # implementation/hicasso
REPO_ROOT = os.path.dirname(os.path.dirname(PACKAGE_ROOT))        # repo root

PRODUCT_DIR = os.path.join(REPO_ROOT, "docs", "design", "hicasso", "product")
BUDGETS = os.path.join(PRODUCT_DIR, "budgets.md")

OPEN_MARKER = "<!-- rf2-hic-089: ledger -->"
CLOSE_MARKER = "<!-- rf2-hic-089: end-ledger -->"

# L1.  Exactly one of these is a pass, and the report below never says
# "all budgets met" — it says how many rows are in each.
STATUSES = ("MET", "BREACH", "UNRESOLVED", "UNPINNED")
PASSING_STATUS = "MET"

# L6.  The lane a reading was taken in, which is what decides whether it may
# gate a pull request.  `none` belongs to a row nothing has measured.
LANES = ("PR gate", "P-DEV-1 evidence run", "none")

# L6.  Family by id.  budgets.md §2 draws this line and §7 routes on it: a
# body count is a delta on a monotone integer counter and contention cannot
# move it, so it belongs in an ordinary blocking gate; bytes and milliseconds
# need a quiet box and belong in pinned evidence runs.  U5 and U6 are §6
# user-visible budgets that happen to be PINNED deterministically (as D1–D4
# and D9), which is why they sit on the deterministic side here.
#
# This is a REGISTRY, not a rule: it transcribes which ids §3 registers, so it
# grows when §3 does.  `rf2-hic-033` added D10–D13, the direct-return delta,
# and `rf2-hic-034` added D14–D16, the island band's structural half — element
# type identity, props-object slots and the unwrapping hop those two price.
# §3 states the test this list applies in terms — *they are counts, not
# clocks, so they are deterministic and carry no hardware profile; that is
# what lets them sit in this section rather than §4*.  Note which way the
# entry cuts: a row NAMED here must run in the `PR gate` lane and must name a
# witness file that exists, while a row omitted from it is merely forbidden
# that lane and has its witness checked not at all.  Adding an id tightens
# this gate; leaving a deterministic id out is the loophole.
DETERMINISTIC_IDS = frozenset(
    ["D%d" % n for n in range(1, 17)] + ["U5", "U6", "I9"])

# L4.  Rows registered somewhere other than budgets.md, with the anchor that
# registers them.  Held here rather than in a ledger column because it is a
# one-row exception and a column would invite a second.
EXTRA_ROWS = {
    "I9": "substrate-decision.md#4-the-two-hook-ceiling-frozen-with-its-measurement",
}

# L5.  The landed adjudication, pinned so a cell rewrite cannot move it.
# `rf2-fe0l` (PRs #7939, #7941) re-pinned S1–S5 on `implementation/hicasso`
# and left S6, S7 and U1–U4 exactly where they were.  Changing one of these
# is a deliberate act — a new measurement window and an edit here — which is
# the whole point.  D10–D13 and D14–D16 join the D rows' `package` pin on the
# same terms: `rf2-hic-033` and `rf2-hic-034` took them on
# `implementation/hicasso` and §3's heading is what they landed under.  An
# unpinned row is the hole this constant exists to close, so a new row is
# pinned as it lands rather than later.
POPULATION_PIN = dict(
    [("D%d" % n, "package") for n in range(1, 17)]
    + [("S%d" % n, "package") for n in range(1, 6)]
    + [("S6", "bench-tree"), ("S7", "bench-tree")]
    + [("U%d" % n, "—") for n in range(1, 5)]
    + [("U5", "package"), ("U6", "package")]
    + [("C1", "—"), ("C2", "bench-tree"), ("C3", "—"), ("C4", "—"),
       ("C5", "package"), ("C6", "package"), ("C7", "—"), ("C8", "—")]
    + [("I9", "package")])

POPULATIONS = ("package", "bench-tree", "—")

# L5.  Lines the operator has frozen: the cell must contain the frozen
# spelling.  `1,024 B` is the 2026-08-12 ruling, and the ambiguous `1 KB`
# spelling retired with it.
LINE_PIN = {
    "S1": "1,024 B",
    "S2": "1,024 B",
    "C5": "1,024 B",
    "S6": "1.10x",
    "C2": "1.10x",
}

# L5.  budgets.md §4's standing prohibition, held by name: the `1.25x`
# cold-mount ceiling is a proposal pending the operator's sitting, and no
# evidence row may use it to mark K1 green.  Scoped to the two rows it is
# about — C3's `1.25x` is the broad-update rule, which IS registered.
LINE_FORBIDDEN = {
    "S6": ("1.25x",),
    "C2": ("1.25x",),
}

# L5, the general direction: a line written as a proposal is not a
# registered line, whatever number it carries.
PROPOSAL_MARKERS = ("subject to ratification", "pending ratification",
                    "proposal", "not yet ratified", "unratified")

_ROW_ID_RE = re.compile(r"^\|\s*([DSUCI]\d+)\s*\|")
# L2 reads BYTE ceilings only.  `2 bodies` and `100 ms p95` are not byte
# ceilings, and a lower-case `b` is not a byte here: the unit is written `B`.
_CEILING_RE = re.compile(r"([\d,]+)\s*B\b")
_BAND_RE = re.compile(r"\[\s*([\d,]+)\s*[-–—]\s*([\d,]+)\s*\]")
_LANE_RE = re.compile(r"\(([^()]+)\)\s*$")
_LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
_AUTHORITY_RE = re.compile(r"^rf2-[a-z0-9-]+$")

_RE_TAGS = re.compile(r"</?[^>]*>")
_RE_INVALID_SLUG_CHAR = re.compile(r"[^\w\- ]", re.UNICODE)


def slugify(text):
    """The heading slug mkdocs mints, `pymdownx.slugs.slugify(case=lower)`.

    Reimplemented rather than imported so this gate needs no third-party
    package: it runs chained into `npm run test:hicasso-invariants`, whose
    other members are pure standard library.  `scripts/check_doc_slugs.py`
    validates the same anchors against the real implementation on every
    docs change, so a divergence here cannot ship silently — that gate
    would red on the link this one resolved.
    """
    text = _RE_TAGS.sub("", text.lower())
    text = _RE_INVALID_SLUG_CHAR.sub("", text)
    return text.strip().replace(" ", "-")


# ---------------------------------------------------------------------------
# Reading the ledger
# ---------------------------------------------------------------------------

def _cells(line):
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def _bare(cell):
    """`cell` with markdown backticks stripped, for a cell holding one token."""
    return cell.replace("`", "").strip()


def read_ledger(path):
    """`(rows, registered)` from budgets.md.

    `rows` is the ledger's own rows, in document order; `registered` is
    every `D`/`S`/`U`/`C` id the page registers in a table OUTSIDE the
    ledger, which is what L4 round-trips against.  The two are separated
    structurally by the marker comments rather than by heading text, so
    renaming §9 cannot silently empty either side.
    """
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()

    rows, registered = [], set()
    inside = False
    seen_open = False
    for line in lines:
        if line.strip() == OPEN_MARKER:
            inside, seen_open = True, True
            continue
        if line.strip() == CLOSE_MARKER:
            inside = False
            continue
        match = _ROW_ID_RE.match(line)
        if not match:
            continue
        if not inside:
            if not match.group(1).startswith("I"):
                registered.add(match.group(1))
            continue
        cells = _cells(line)
        if len(cells) != 8:
            raise ValueError(
                "ledger row %s has %d cells, expected 8 (%s)"
                % (match.group(1), len(cells), line.strip()))
        rows.append({
            "id": cells[0],
            "line": cells[1],
            "current": cells[2],
            "population": cells[3],
            "status": _bare(cells[4]),
            "instrument": cells[5],
            "authority": _bare(cells[6]),
            "disposition": cells[7],
        })
    if not seen_open:
        raise ValueError("%s carries no %s marker" % (path, OPEN_MARKER))
    return rows, registered


def read_sections(path):
    """`{slug: section body}` for `path`, a section running to the next
    heading of the same or a higher level.  Fenced blocks are skipped so a
    `#` inside one cannot mint a phantom heading."""
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    heads = []
    fenced = False
    for index, line in enumerate(lines):
        if line.lstrip().startswith("```"):
            fenced = not fenced
            continue
        if fenced:
            continue
        match = re.match(r"^(#{1,6})\s+(.*?)\s*$", line)
        if match:
            heads.append((index, len(match.group(1)), slugify(match.group(2))))
    sections = {}
    for position, (index, level, slug) in enumerate(heads):
        end = len(lines)
        for later_index, later_level, _ in heads[position + 1:]:
            if later_level <= level:
                end = later_index
                break
        sections.setdefault(slug, "\n".join(lines[index:end]))
    return sections


def resolve(target, cache):
    """The section body a `file.md#anchor` target names, or None.

    None covers both halves of a broken pointer — a file that is not there
    and an anchor that resolves to nothing — because L3's message is the
    same either way and the caller has the target to print.
    """
    if "#" not in target:
        return None
    rel, anchor = target.split("#", 1)
    path = os.path.normpath(os.path.join(PRODUCT_DIR, rel))
    if not os.path.isfile(path):
        return None
    if path not in cache:
        cache[path] = read_sections(path)
    return cache[path].get(anchor)


# ---------------------------------------------------------------------------
# The rules
# ---------------------------------------------------------------------------

def _numbers(text):
    return int(text.replace(",", ""))


def check(rows, registered, sections, existing_files):
    """Every rule, against already-read inputs.

    Pure, so `--self-test` drives each red by doctoring one input of a
    green tree rather than by building a fixture page.  The reds this gate
    must produce are statements about the relation between a ledger, the
    tables it summarises and the records it points at; a fixture would
    re-create that relation less honestly than the real one.

    `sections` maps a `file.md#anchor` target to the section body it names,
    or to None when it names nothing.  `existing_files` is the set of
    repo-relative paths, among those the ledger cites, that exist.
    """
    failures = []
    by_id = {}

    for row in rows:
        rid = row["id"]
        if rid in by_id:
            failures.append(
                "L4 %s has two ledger rows. One line, one verdict" % rid)
            continue
        by_id[rid] = row

        # --- L1: the vocabulary is closed -------------------------------
        if row["status"] not in STATUSES:
            failures.append(
                "L1 %s has status %r, which is not one of %s"
                % (rid, row["status"], ", ".join(STATUSES)))
            continue

        # --- L2: the band decides ---------------------------------------
        ceiling = _CEILING_RE.search(row["line"])
        if ceiling:
            band = _BAND_RE.search(row["current"])
            if not band:
                failures.append(
                    "L2 %s registers a byte ceiling (%s B) and its current "
                    "value carries no band. A ceiling without a band cannot "
                    "be adjudicated, and omitting one is the way out from "
                    "under this rule" % (rid, ceiling.group(1)))
            else:
                limit = _numbers(ceiling.group(1))
                low, high = _numbers(band.group(1)), _numbers(band.group(2))
                if high <= limit:
                    verdict = "MET"
                elif low > limit:
                    verdict = "BREACH"
                else:
                    verdict = "UNRESOLVED"
                if row["status"] != verdict:
                    detail = ("the band [%d-%d] crosses the %d B line, so the "
                              "row is UNRESOLVED, not a pass"
                              if verdict == "UNRESOLVED" else
                              "the band [%d-%d] against the %d B line reads %s")
                    args = ((low, high, limit) if verdict == "UNRESOLVED"
                            else (low, high, limit, verdict))
                    failures.append(
                        "L2 %s is recorded %s but %s"
                        % (rid, row["status"], detail % args))

        # --- L3: no silent breach ---------------------------------------
        if not _AUTHORITY_RE.match(row["authority"]):
            failures.append(
                "L3 %s names authority %r, which is not a bead id. Every row "
                "has an owner" % (rid, row["authority"]))
        if row["status"] != PASSING_STATUS:
            link = _LINK_RE.search(row["disposition"])
            if not link:
                failures.append(
                    "L3 %s is %s and names no disposition record. A breach "
                    "nothing points at is a silent breach"
                    % (rid, row["status"]))
            else:
                body = sections.get(link.group(1))
                if body is None:
                    failures.append(
                        "L3 %s points its disposition at %s, which does not "
                        "resolve" % (rid, link.group(1)))
                elif rid not in body:
                    failures.append(
                        "L3 %s points its disposition at %s, a section that "
                        "never names it. A pointer that reads as a "
                        "disposition and is not one is worse than none"
                        % (rid, link.group(1)))

        # --- L5: the landed adjudication is pinned ----------------------
        if row["population"] not in POPULATIONS:
            failures.append(
                "L5 %s has population %r, which is not one of %s"
                % (rid, row["population"], ", ".join(POPULATIONS)))
        pinned = POPULATION_PIN.get(rid)
        if pinned is not None and row["population"] != pinned:
            failures.append(
                "L5 %s is pinned to the %s population and reads %r. rf2-fe0l "
                "settled which rows are package figures; promoting one is a "
                "measurement window, not a cell edit"
                % (rid, pinned, row["population"]))
        if row["population"] == "—":
            if row["status"] != "UNPINNED":
                failures.append(
                    "L5 %s has no population and is recorded %s. Nothing has "
                    "measured it, so nothing has decided it"
                    % (rid, row["status"]))
            if row["current"] != "—":
                failures.append(
                    "L5 %s has no population and states the value %r. A "
                    "figure with no population is a figure from somewhere "
                    "else" % (rid, row["current"]))
        required = LINE_PIN.get(rid)
        if required is not None and required not in row["line"]:
            failures.append(
                "L5 %s must register the frozen line %r and its line cell "
                "reads %r" % (rid, required, row["line"]))
        for banned in LINE_FORBIDDEN.get(rid, ()):
            if banned in row["line"]:
                failures.append(
                    "L5 %s registers %s, which is a PROPOSAL pending the "
                    "operator's sitting. No evidence row may use it to mark "
                    "K1 green (budgets.md sec. 4)" % (rid, banned))
        lowered = row["line"].lower()
        for marker in PROPOSAL_MARKERS:
            if marker in lowered:
                failures.append(
                    "L5 %s writes its registered line as a proposal (%r). A "
                    "line that is not ratified is not registered"
                    % (rid, marker))
                break

        # --- L6: the lane is legal --------------------------------------
        lane_match = _LANE_RE.search(row["instrument"])
        if not lane_match:
            failures.append(
                "L6 %s names no lane. The instrument cell ends in one of (%s)"
                % (rid, "), (".join(LANES)))
            continue
        lane = lane_match.group(1).strip()
        if lane not in LANES:
            failures.append(
                "L6 %s runs in lane %r, which is not one of %s"
                % (rid, lane, ", ".join(LANES)))
            continue
        deterministic = rid in DETERMINISTIC_IDS
        witness = _bare(row["instrument"][:lane_match.start()])
        if deterministic:
            if lane != "PR gate":
                failures.append(
                    "L6 %s is a deterministic row in the %r lane. A counter "
                    "reads the same on a loaded box, so it belongs in an "
                    "ordinary blocking gate (budgets.md sec. 2)" % (rid, lane))
            elif witness not in existing_files:
                failures.append(
                    "L6 %s names the witness %s, which does not exist"
                    % (rid, witness))
        else:
            if lane == "PR gate":
                failures.append(
                    "L6 %s is a distributional row wired to the PR-gate lane. "
                    "A hosted runner may never source a distributional budget "
                    "(budgets.md sec. 1) and such a row is never converted into a "
                    "flaky PR threshold (sec. 7)" % rid)
            if row["status"] == "UNPINNED" and lane != "none":
                failures.append(
                    "L6 %s is UNPINNED and names the lane %r. Nothing has "
                    "measured it" % (rid, lane))

    # --- L4: complete, and nothing invented -----------------------------
    for rid in sorted(registered - set(by_id)):
        failures.append(
            "L4 %s is registered in budgets.md and has no ledger row. "
            "Dropping a row is the cheapest way to make a ledger green" % rid)
    for rid in sorted(set(by_id) - registered):
        if rid not in EXTRA_ROWS:
            failures.append(
                "L4 %s has a ledger row and is registered nowhere. Register "
                "it, or add it to EXTRA_ROWS with the anchor that does" % rid)
            continue
        body = sections.get(EXTRA_ROWS[rid])
        if body is None:
            failures.append(
                "L4 %s cites the provenance anchor %s, which does not resolve"
                % (rid, EXTRA_ROWS[rid]))
        elif rid not in body:
            failures.append(
                "L4 %s cites the provenance anchor %s, a section that never "
                "names it" % (rid, EXTRA_ROWS[rid]))
    return failures


def report(failures):
    """Print `failures` in the gate's voice, and return the exit code 1."""
    print("FAIL: Hicasso budget-line reconciliation ledger\n")
    for failure in failures:
        print("  " + failure)
    print("\nThe ledger is docs/design/hicasso/product/budgets.md sec. 9; the rule "
          "each L-number names is in this script's header.")
    print("Note what a green run here does NOT mean: this gate gates the "
          "RECORD, never the budgets.")
    return 1


# ---------------------------------------------------------------------------

def read_all():
    rows, registered = read_ledger(BUDGETS)
    cache = {}
    targets = set(EXTRA_ROWS.values())
    for row in rows:
        link = _LINK_RE.search(row["disposition"])
        if link:
            targets.add(link.group(1))
    sections = {target: resolve(target, cache) for target in sorted(targets)}
    existing = set()
    for row in rows:
        lane = _LANE_RE.search(row["instrument"])
        if not lane:
            continue
        witness = _bare(row["instrument"][:lane.start()])
        if witness and os.path.isfile(os.path.join(REPO_ROOT, witness)):
            existing.add(witness)
    return rows, registered, sections, existing


def tally(rows):
    counts = {status: 0 for status in STATUSES}
    for row in rows:
        if row["status"] in counts:
            counts[row["status"]] += 1
    return counts


# ---------------------------------------------------------------------------
# --self-test: prove the gate classifies, rather than merely runs
# ---------------------------------------------------------------------------

def self_test():
    assert slugify("5.2 The read-free boundary shell — the disposition") == \
        "52-the-read-free-boundary-shell--the-disposition", slugify(
            "5.2 The read-free boundary shell — the disposition")
    assert slugify("9. The budget-line reconciliation ledger") == \
        "9-the-budget-line-reconciliation-ledger"
    assert _CEILING_RE.search("2 bodies per keystroke at 5×5") is None, \
        "a lower-case b is not a byte unit"
    assert _CEILING_RE.search("≤ 100 ms p95") is None, \
        "milliseconds are not a byte ceiling"

    rows, registered, sections, existing = read_all()
    # Every red below is driven by doctoring ONE input of a green tree, so a
    # tree that is already red cannot be tested against.  That is a finding
    # about the ledger rather than about this self-test, and it is by far the
    # likeliest way this entry point fails on a pull request — so it is
    # reported exactly as a plain run reports it.
    failures = check(rows, registered, sections, existing)
    if failures:
        code = report(failures)
        print("\n(--self-test drives its reds by doctoring a GREEN ledger's "
              "inputs, so it stops here.)")
        return code

    def red(prefix, **overrides):
        args = dict(rows=rows, registered=registered, sections=sections,
                    existing_files=existing)
        args.update(overrides)
        found = check(**args)
        assert any(f.startswith(prefix) for f in found), \
            "%s did not red: %r" % (prefix, found)

    def green(**overrides):
        args = dict(rows=rows, registered=registered, sections=sections,
                    existing_files=existing)
        args.update(overrides)
        found = check(**args)
        assert not found, "a legal ledger reddened: %r" % found

    def patched(rid, **changes):
        out = []
        for row in rows:
            out.append(dict(row, **changes) if row["id"] == rid else dict(row))
        return out

    # L1 — a status outside the vocabulary.  `PASS` is the likeliest way to
    # write one, and it is exactly the word this ledger will not have.
    red("L1", rows=patched("S1", status="PASS"))

    # L2 — the two directions that matter.  A crossing band recorded as a
    # pass is the failure the operator's ruling exists to forbid; a ceiling
    # with no band at all is the way out from under the rule.
    red("L2", rows=patched("S1", current="1,100 B [1,010–1,107]", status="MET"))
    red("L2", rows=patched("S1", current="1,100 B"))
    red("L2", rows=patched("S1", current="1,100 B [1,091–1,107]", status="MET"))

    # ...and the eager direction: a legal reading must still pass.  A band
    # wholly under the line is MET, and a band that crosses it is UNRESOLVED
    # — both are recomputed, neither is a failure.
    green(rows=patched("S1", current="994 B [988–1,002]", status="MET"))
    green(rows=patched("S1", current="1,020 B [1,010–1,030]",
                       status="UNRESOLVED"))

    # L3 — a breach with no owner, with no disposition, with one that does
    # not resolve, and with one that resolves to a section never naming it.
    red("L3", rows=patched("S1", authority="the operator"))
    red("L3", rows=patched("S1", disposition="—"))
    red("L3", rows=patched("S1", disposition="[gone](substrate-decision.md#no-such-anchor)"))
    a_breach = next(r for r in rows if r["status"] == "BREACH")
    link = _LINK_RE.search(a_breach["disposition"]).group(1)
    red("L3", sections=dict(sections, **{link: "a section naming nothing"}))

    # ...and the eager direction: a MET row needs no disposition, and an
    # em-dash there must not be read as a broken pointer.
    green(rows=patched("S4", disposition="—"))

    # L4 — a registered row dropped from the ledger, and a ledger row
    # registered nowhere.
    red("L4", registered=registered | {"S9"})
    red("L4", rows=rows + [dict(rows[0], id="D42")])
    # ...and the extra row's own provenance, which is the only thing keeping
    # EXTRA_ROWS from being a hole.
    red("L4", sections=dict(sections,
                            **{EXTRA_ROWS["I9"]: "a section naming nothing"}))

    # L5 — a bench-tree figure promoted to the package by a cell edit; a
    # frozen line rewritten; the unratified cold-mount proposal used as a
    # registered line; and a value stated for a row nothing has measured.
    red("L5", rows=patched("S6", population="package"))
    red("L5", rows=patched("S1", line="1,000 B, R=0 shell, Reagent segment"))
    red("L5", rows=patched("S6", line="1.25x cold mount against direct UIx"))
    red("L5", rows=patched("C3", line="≤ 1.25x, subject to ratification"))
    red("L5", rows=patched("U2", current="42 ms p95"))
    # ...and the eager direction: C3's `1.25x` is the REGISTERED broad-update
    # rule, and must not be caught by the prohibition aimed at the cold-mount
    # proposal.
    green(rows=patched("C3", line="≤ 1.25x the best relevant adapter"))

    # L6 — the rule that refuses this bead's own first deliverable as
    # written: a distributional row wired to a pull-request gate.
    red("L6", rows=patched("C1", instrument="`scripts/five-percent.sh` (PR gate)"))
    red("L6", rows=patched("S1", instrument="P0 heap ladder (PR gate)"))
    # ...a deterministic row whose witness has been deleted or renamed...
    red("L6", rows=patched("D1", instrument="`implementation/hicasso/gone.cljs` (PR gate)"))
    # ...a deterministic row moved out of the blocking lane...
    red("L6", rows=patched("I9", instrument="a bench (P-DEV-1 evidence run)"))
    # ...and a lane nobody registered.
    red("L6", rows=patched("D1", instrument="`x` (some other lane)"))

    counts = tally(rows)
    print("OK: check_budget_ledger self-test passed "
          "(%d rows: %s)"
          % (len(rows),
             ", ".join("%d %s" % (counts[s], s) for s in STATUSES)))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove the gate's red/green classification, then exit")
    parser.add_argument("--list", action="store_true",
                        help="print the ledger, one row per line, with its verdict")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    rows, registered, sections, existing = read_all()

    if args.list:
        for row in rows:
            print("%-4s %-11s %-11s %s"
                  % (row["id"], row["status"], row["population"], row["line"]))
        return 0

    failures = check(rows, registered, sections, existing)
    if failures:
        return report(failures)

    counts = tally(rows)
    print("OK: %d ledger rows -- %d MET, %d BREACH, %d UNRESOLVED, %d UNPINNED."
          % (len(rows), counts["MET"], counts["BREACH"],
             counts["UNRESOLVED"], counts["UNPINNED"]))
    print("Every registered line has a row; every row that is not MET names "
          "a bead id and a disposition that resolves;")
    print("no band crossing its line is recorded as a pass; no distributional "
          "row is wired to a pull-request gate.")
    print("An Authority cell is read for SHAPE, not for life. This gate reads "
          "two markdown files and has no tracker access, so it cannot see "
          "that a named bead has closed:")
    print("a row can name an owner and have none. Whether the named beads are "
          "open is a question for a reader, and it is not certified here.")
    print("This is a verdict about the RECORD. It is not a statement that any "
          "budget is met -- %d rows are not MET."
          % (len(rows) - counts["MET"]))
    return 0


if __name__ == "__main__":
    # A malformed ledger — markers deleted, a row short of a cell — is a
    # ledger failure, not a crash, and it must arrive in the gate's own voice
    # rather than as a traceback.  Deleting the markers is the loudest way to
    # empty the table, so it is the shape most worth reporting well.
    try:
        sys.exit(main())
    except ValueError as malformed:
        sys.exit(report(["L0 %s" % malformed]))
