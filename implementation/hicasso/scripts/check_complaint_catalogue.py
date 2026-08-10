#!/usr/bin/env python3
"""THE COMPLAINT-CATALOGUE GATE for implementation/hicasso/ (rf2-hic-021).

`docs/design/hicasso/product/complaints.md` is the REGISTER of Hicasso's
diagnostic ids: every complaint the substrate can raise, every spelling
claimed for a refusal whose surface is not built yet, and every spelling
retired and therefore dead forever. That register is a document of
PROMISES, and a promise nothing checks is a promise that rots. This gate
is what makes it a contract.

WHAT THIS GATE IS NOT
---------------------
It is not a second copy of `scripts/check_keyword_catalogue_drift.py`.
That gate defends the repo-wide Spec 009 catalogue in both directions —
every emitted `:rf.error/*` has a row (CHECK A), every active row has an
emitter (CHECK C) — and it already covers every Hicasso id that EXISTS.
Nothing here restates that.

What it adds is the axis Spec 009 structurally cannot hold: **status**.
A row in Spec 009 means "the runtime raises this today"; CHECK C enforces
exactly that, so an id whose surface has not been built CANNOT have a
Spec 009 row, and an id that has been retired must not have one either.
Those two populations — the RESERVED and the RETIRED — are precisely
the ones a stability contract is about, and until this register they
lived nowhere. A refusal the guide teaches by mechanism with no id at all
satisfies both directions of a raise-set/catalogue-set round trip
VACUOUSLY: it is raised by nothing and appears in no catalogue, so the
two enumerations agree and the coverage is still missing. The register's
reserved section is the third input that closes that hole, and R3 below
is what keeps it honest.

THE SEVEN RULES
---------------
  R1  NO GAP.        Every `:rf.error/*` id emitted by the package's
                     shipped source or its test kit has a `live` row.
  R2  NO DEAD ENTRY. Every `live` row is emitted by one of those.
  R3  RESERVATIONS ARE HONEST.  A `reserved` id is emitted nowhere in
                     the package and has no Spec 009 row. A reservation
                     that quietly became real is a register that has
                     stopped describing the runtime — promoting it is a
                     deliberate act (write the emitter, write the Spec
                     009 row, move the register row to `live`), never a
                     drift.
  R4  TOMBSTONES STAY DEAD.  A `retired` id is emitted nowhere and has
                     no ACTIVE Spec 009 row.
  R5  NO REUSE.      No id appears under two of the three EXCLUSIVE
                     statuses (`live`, `reserved`, `retired`). This is
                     the stability rule mechanised: a retired spelling
                     cannot come back as a reservation, and a live one
                     cannot be quietly re-minted for a different
                     meaning.
  R6  THE TWO CATALOGUES AGREE.  Every `live` row has a Spec 009 row.
                     The register indexes; Spec 009 states the meaning,
                     the payload and the recovery. One owner per fact,
                     bound by id.
  R7  A PENDING RETIREMENT IS STILL LIVE.  An id recorded as retiring
                     later must be live TODAY. Without this the section
                     becomes a list of predictions nobody re-reads: an
                     id could be deleted outright and its pending row
                     would sit there describing a runtime that no longer
                     has it.
  R8  THE ANCHOR RESOLVES.  Every guide chapter a row cites exists, and
                     names that id. A pointer to a page that does not
                     mention the complaint is worse than no pointer.

THE DIRECTION R8 DELIBERATELY DOES NOT CHECK is the reverse one: an id
whose `Taught in` cell is `—` is NOT required to be absent from the
guide. The guide is a live draft under active rewrite, and a gate that
reddened whenever a chapter gained a citation would put this register in
the rewrite's path for no correctness gain — the register's subject is
the id lifecycle, and the anchor is a convenience on top of it. If the
guide ever stops moving, that direction is worth taking.

WHAT COUNTS AS AN EMIT
----------------------
A `:rf.error/…` keyword LITERAL in code, after `;` comments and string
literals are masked out. The masking is not optional: this package's
docstrings cite ids they do not raise — `impl/collector` names
`:rf.error/frame-destroyed` and `:rf.error/no-such-sub` while explaining
what core does on a cold read, and `impl/error`'s own docstring names
three ids to explain why their payload column reads `(none)`. An
unmasked scan would demand `live` rows for all of them and the register
would claim Hicasso raises complaints it merely describes.

USAGE
    python hicasso/scripts/check_complaint_catalogue.py --self-test
    python hicasso/scripts/check_complaint_catalogue.py
    python hicasso/scripts/check_complaint_catalogue.py --list
"""

import argparse
import os
import re
import sys

SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
PACKAGE_ROOT = os.path.dirname(SCRIPTS_DIR)                       # implementation/hicasso
REPO_ROOT = os.path.dirname(os.path.dirname(PACKAGE_ROOT))        # repo root

REGISTER = os.path.join(REPO_ROOT, "docs", "design", "hicasso", "product", "complaints.md")
SPEC_009 = os.path.join(REPO_ROOT, "spec", "009-Instrumentation.md")
GUIDE_DIR = os.path.join(REPO_ROOT, "docs", "design", "hicasso", "draft-guide")

# The surfaces a consumer can actually reach: the published package and the
# supported test kit.  `test/` and `testbed/` ASSERT ids rather than owning
# them, so they are not an emit surface — but they are part of the package
# for the purposes of R3/R4, where the question is "does this spelling occur
# at all".
EMIT_ROOTS = [
    os.path.join(PACKAGE_ROOT, "src"),
    os.path.join(PACKAGE_ROOT, "test_kit", "src"),
]

SOURCE_EXTS = (".clj", ".cljc", ".cljs")

STATUSES = ("live", "reserved", "pending-retirement", "retired")

# The three an id can hold ONE of.  `pending-retirement` is deliberately not
# among them: it annotates an id that is still live (R7), so treating it as a
# fourth exclusive state would make the register contradict itself.
EXCLUSIVE_STATUSES = ("live", "reserved", "retired")

# `:rf.error/id` is the ex-data KEY every complaint is stamped with, not a
# complaint. It reads as one to a scan of `:rf.error/…` literals, and it is
# the only member of that family, so it is named here rather than filtered by
# a pattern that would also swallow a real id somebody spelled badly.
NOT_A_COMPLAINT = frozenset({":rf.error/id"})

_MARKER_RE = re.compile(r"<!--\s*rf2-hic-021:\s*status=([a-z-]+)\s*-->")
_ERROR_ID_RE = re.compile(r":rf\.error/[a-zA-Z0-9*+!_'?<>=-]+")
_ROW_ID_RE = re.compile(r"^\|\s*~{0,2}`(:rf\.error/[^`]+)`~{0,2}\s*\|")
_CHAPTER_RE = re.compile(r"\bch(\d{2})\b")


# ---------------------------------------------------------------------------
# Reading source
# ---------------------------------------------------------------------------

def mask(text):
    """`text` with `;` comments and string literals replaced by spaces.

    Character literals (`\\;`, `\\"`) are consumed as two characters so a
    quote or semicolon written as a value cannot open a phantom string or
    comment.  Newlines survive so a caller can still report line numbers.
    """
    out = []
    i, n = 0, len(text)
    in_string = False
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\" and i + 1 < n:
                out.append("  ")
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            if c == '"':
                in_string = False
            i += 1
            continue
        if c == "\\" and i + 1 < n:
            # A character literal.  Two characters, neither of them syntax.
            out.append("  ")
            i += 2
            continue
        if c == '"':
            in_string = True
            out.append(" ")
            i += 1
            continue
        if c == ";":
            while i < n and text[i] != "\n":
                out.append(" ")
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _source_files(roots):
    for root in roots:
        for dirpath, _dirnames, filenames in os.walk(root):
            for name in sorted(filenames):
                if name.endswith(SOURCE_EXTS):
                    yield os.path.join(dirpath, name)


def read_ids(roots):
    """`{id: [relative path, …]}` for every masked `:rf.error/…` literal."""
    found = {}
    for path in _source_files(roots):
        with open(path, encoding="utf-8") as fh:
            code = mask(fh.read())
        for match in sorted(set(_ERROR_ID_RE.findall(code)) - NOT_A_COMPLAINT):
            rel = os.path.relpath(path, REPO_ROOT).replace(os.sep, "/")
            found.setdefault(match, []).append(rel)
    return found


# ---------------------------------------------------------------------------
# Reading the register
# ---------------------------------------------------------------------------

def read_register(path):
    """`{status: {id: [cited chapter numbers]}}`, read off the markers.

    Status is STRUCTURAL — a `<!-- rf2-hic-021: status=… -->` marker opens a
    section and the next marker closes it — rather than read off a heading's
    wording or a list kept beside the tables.  Same posture as Spec 009's
    retire-in-place convention: the row says what it is.
    """
    with open(path, encoding="utf-8") as fh:
        lines = fh.read().splitlines()
    register = {status: {} for status in STATUSES}
    current = None
    for line in lines:
        marker = _MARKER_RE.search(line)
        if marker:
            current = marker.group(1)
            if current not in STATUSES:
                raise ValueError("unknown status marker %r in %s" % (current, path))
            continue
        if current is None:
            continue
        row = _ROW_ID_RE.match(line)
        if row:
            cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
            chapters = _CHAPTER_RE.findall(cells[-1]) if len(cells) > 1 else []
            register[current][row.group(1)] = chapters
    return register


def read_spec_ids(path):
    """`(active, retired)` id sets, read off Spec 009's catalogue rows."""
    active, retired = set(), set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            match = _ROW_ID_RE.match(line)
            if match:
                first = line.strip().strip("|").split("|")[0].strip()
                (retired if first.startswith("~~") else active).add(match.group(1))
    return active, retired


def guide_chapter(number):
    """The guide file whose name starts `<number>-`, or None."""
    if not os.path.isdir(GUIDE_DIR):
        return None
    for name in sorted(os.listdir(GUIDE_DIR)):
        if name.startswith(number + "-") and name.endswith(".md"):
            return os.path.join(GUIDE_DIR, name)
    return None


# ---------------------------------------------------------------------------
# The rules
# ---------------------------------------------------------------------------

def check(register, emitted, package_ids, spec_active, spec_retired, chapter_text):
    """Every rule, against already-read inputs.

    Pure so `--self-test` can drive each red by doctoring one input rather
    than by writing a fixture tree: the reds this gate must produce are
    statements about the RELATION between four documents, and a fixture
    would only re-create that relation less honestly.
    """
    failures = []
    live = register["live"]
    reserved = register["reserved"]
    pending = register["pending-retirement"]
    retired = register["retired"]

    # R1 — no gap.
    for error_id in sorted(emitted):
        if error_id not in live:
            failures.append(
                "R1 %s is emitted by %s and has no `live` row in the register"
                % (error_id, ", ".join(emitted[error_id])))

    # R2 — no dead entry.
    for error_id in sorted(live):
        if error_id not in emitted:
            failures.append(
                "R2 %s has a `live` row and is emitted by nothing. A documented "
                "complaint with no emitter is a promise the substrate cannot keep"
                % error_id)

    # R3 — reservations are honest.
    for error_id in sorted(reserved):
        if error_id in package_ids:
            failures.append(
                "R3 %s is `reserved` but occurs in %s. Promote it: write the Spec "
                "009 row and move the register row to `live`"
                % (error_id, ", ".join(package_ids[error_id])))
        if error_id in spec_active or error_id in spec_retired:
            failures.append(
                "R3 %s is `reserved` but has a Spec 009 row. A reservation names a "
                "refusal nothing raises yet, and Spec 009 rows are for refusals that "
                "exist" % error_id)

    # R4 — tombstones stay dead.
    for error_id in sorted(retired):
        if error_id in package_ids:
            failures.append(
                "R4 %s is `retired` and still occurs in %s"
                % (error_id, ", ".join(package_ids[error_id])))
        if error_id in spec_active:
            failures.append(
                "R4 %s is `retired` and still has an ACTIVE Spec 009 row (retire it "
                "in place with a struck first cell)" % error_id)

    # R5 — no reuse.  Over the three EXCLUSIVE statuses only: `pending-
    # retirement` is an annotation on a live id rather than a fourth state,
    # and R7 is what requires it to co-occur with `live`.
    seen = {}
    for status in EXCLUSIVE_STATUSES:
        for error_id in register[status]:
            seen.setdefault(error_id, []).append(status)
    for error_id, statuses in sorted(seen.items()):
        if len(statuses) > 1:
            failures.append(
                "R5 %s is registered under %s at once. An id has one status; a "
                "retired spelling is never re-minted"
                % (error_id, " and ".join(sorted(statuses))))

    # R6 — the two catalogues agree.
    for error_id in sorted(live):
        if error_id not in spec_active:
            failures.append(
                "R6 %s has a `live` row and no active Spec 009 catalogue row"
                % error_id)

    # R7 — a pending retirement is still live.
    for error_id in sorted(pending):
        if error_id not in live:
            failures.append(
                "R7 %s is recorded as retiring later but has no `live` row. Either "
                "it is still raised (register it `live`) or the retirement already "
                "happened (move it to `retired`)" % error_id)

    # R8 — the anchor resolves.
    for status in STATUSES:
        for error_id, chapters in sorted(register[status].items()):
            for number in chapters:
                text = chapter_text.get(number)
                if text is None:
                    failures.append(
                        "R8 %s cites guide chapter ch%s, which does not exist"
                        % (error_id, number))
                elif error_id not in text:
                    failures.append(
                        "R8 %s cites guide chapter ch%s, which never names it"
                        % (error_id, number))
    return failures


def read_all():
    register = read_register(REGISTER)
    emitted = read_ids(EMIT_ROOTS)
    package_ids = read_ids([PACKAGE_ROOT])
    spec_active, spec_retired = read_spec_ids(SPEC_009)
    cited = set()
    for status in STATUSES:
        for chapters in register[status].values():
            cited.update(chapters)
    chapter_text = {}
    for number in sorted(cited):
        path = guide_chapter(number)
        if path is None:
            continue
        with open(path, encoding="utf-8") as fh:
            chapter_text[number] = fh.read()
    return register, emitted, package_ids, spec_active, spec_retired, chapter_text


# ---------------------------------------------------------------------------
# --self-test: prove the gate classifies, rather than merely runs
# ---------------------------------------------------------------------------

def self_test():
    assert mask('(fail! :rf.error/a "text :rf.error/in-a-string")') \
        .count(":rf.error/") == 1, "a string literal must not emit an id"
    assert ":rf.error/" not in mask("; :rf.error/in-a-comment\n"), \
        "a comment must not emit an id"
    assert mask('(= \\" :rf.error/after-a-quote-char)').count(":rf.error/") == 1, \
        "a quote character literal must not open a string"

    register, emitted, package_ids, spec_active, spec_retired, chapters = read_all()
    assert not check(register, emitted, package_ids, spec_active, spec_retired,
                     chapters), "the tree must be green before the reds are driven"

    def red(prefix, **overrides):
        args = dict(register=register, emitted=emitted, package_ids=package_ids,
                    spec_active=spec_active, spec_retired=spec_retired,
                    chapter_text=chapters)
        args.update(overrides)
        found = check(**args)
        assert any(f.startswith(prefix) for f in found), \
            "%s did not red: %r" % (prefix, found)

    def with_status(status, key, value):
        copy = {name: dict(rows) for name, rows in register.items()}
        copy[status][key] = value
        return copy

    a_live = sorted(register["live"])[0]
    a_reserved = sorted(register["reserved"])[0]

    # R1 — an emitted id the register does not carry.
    red("R1", emitted=dict(emitted, **{":rf.error/hicasso-unregistered":
                                       ["implementation/hicasso/src/x.cljs"]}))
    # R2 — a register row nothing raises.
    red("R2", register=with_status("live", ":rf.error/hicasso-never-raised", []))
    # R3 — a reservation that has quietly become real.
    red("R3", package_ids=dict(package_ids,
                               **{a_reserved: ["implementation/hicasso/src/x.cljs"]}))
    red("R3", spec_active=spec_active | {a_reserved})
    # R4 — a tombstone with an emitter, and one with an active Spec 009 row.
    red("R4", register=with_status("retired", ":rf.error/hicasso-tombstoned", []),
        package_ids=dict(package_ids,
                         **{":rf.error/hicasso-tombstoned":
                            ["implementation/hicasso/src/x.cljs"]}))
    red("R4", register=with_status("retired", a_live, []))   # also reds R5
    # R5 — one id, two statuses.
    red("R5", register=with_status("reserved", a_live, []))
    # R6 — a live row with no Spec 009 row.
    red("R6", spec_active=spec_active - {a_live})
    # R7 — a pending retirement that is no longer live.
    red("R7", register=with_status("pending-retirement",
                                   ":rf.error/hicasso-not-live", []))
    # R8 — an anchor that does not resolve, and one that resolves to a page
    # that never names the id.
    red("R8", register=with_status("live", a_live, ["99"]))
    red("R8", register=with_status("live", a_live, ["02"]),
        chapter_text=dict(chapters, **{"02": "a chapter naming nothing"}))

    print("OK: check_complaint_catalogue self-test passed")
    return 0


# ---------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--self-test", action="store_true",
                        help="prove the gate's red/green classification, then exit")
    parser.add_argument("--list", action="store_true",
                        help="print every id the package emits, with its emit sites")
    args = parser.parse_args(argv)

    if args.self_test:
        return self_test()

    if args.list:
        for error_id, paths in sorted(read_ids(EMIT_ROOTS).items()):
            print("%-58s %s" % (error_id, ", ".join(paths)))
        return 0

    register, emitted, package_ids, spec_active, spec_retired, chapters = read_all()
    failures = check(register, emitted, package_ids, spec_active, spec_retired,
                     chapters)
    if failures:
        print("FAIL: Hicasso complaint catalogue\n")
        for failure in failures:
            print("  " + failure)
        print("\nThe register is docs/design/hicasso/product/complaints.md; the "
              "rule each R-number names is in this script's header.")
        return 1
    print("OK: %d live, %d reserved, %d pending retirement, %d retired; every "
          "live row is emitted and rowed in Spec 009, every reservation is "
          "unbuilt, every anchor resolves."
          % (len(register["live"]), len(register["reserved"]),
             len(register["pending-retirement"]), len(register["retired"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
