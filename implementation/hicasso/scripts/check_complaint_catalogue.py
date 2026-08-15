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

THE EIGHT RULES
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
  R9  THE INDEX IS THE ROUTE IN.  The guide's troubleshooting complaint
                     index carries exactly ONE explicit anchor per `live`
                     id, indexes nothing that is not live, and shows for
                     each id only recovery keywords that id's Spec 009
                     row actually carries. See R9 IS A ROUND TRIP below
                     for which directions that is, and which it is not.

THE DIRECTION R8 DELIBERATELY DOES NOT CHECK is the reverse one: an id
whose `Taught in` cell is `—` is NOT required to be absent from the
guide. The guide is a live draft under active rewrite, and a gate that
reddened whenever a chapter gained a citation would put this register in
the rewrite's path for no correctness gain — the register's subject is
the id lifecycle, and the anchor is a convenience on top of it. If the
guide ever stops moving, that direction is worth taking.

R9 IS A ROUND TRIP, AND HERE IS EXACTLY WHICH ONE
-------------------------------------------------
"Round trip" is worth spelling out, because a gate that checks one
direction and reads like it checks two is the failure mode that never
announces itself — it passes.

R9 RUNS BOTH WAYS between the index and the RAISE SET:

  forward   every id the runtime raises is indexed.  R9 compares the
            index against `live`, and `live` is pinned to the raise set
            from both sides already — R1 forbids an emitted id with no
            `live` row and R2 forbids a `live` row with no emitter, so
            `live` IS the raise set and this is that comparison.
  backward  every id the index lists is actually raised.  Same identity,
            read the other way: an indexed id that is not `live` is one
            nothing raises — a reservation the page promoted early, a
            tombstone the page never buried, or a typo.

It also runs one direction on the RECOVERY keyword: every keyword the
index shows for an id must appear in that id's Spec 009 recovery cell.
Subset, not equality, and deliberately — two corpus-owned rows quote
option and payload names in their recovery prose (`:url-strategy`,
`:map-props`, `:frame`), which are not recoveries, and `ui-tree-malformed`
carries fourteen arms the page summarises as `:no-recovery`. Requiring
equality would red on those and teach the next author to weaken the rule.

WHAT R9 CANNOT SEE — the honest limit, and it is a real one:

  * IT NEVER READS THE RUNTIME'S OWN `:recovery` ARGUMENT.  It reconciles
    two DOCUMENTS.  If an emitter and its Spec 009 row drift together
    away from the API they name, both agree, the page agrees with both,
    and R9 is green while all three are wrong.  That is not a theoretical
    hole: it is rf2-15bqc exactly — four `:recovery` keywords went on
    spelling the callback form `h-fn` for as long as they did BECAUSE
    runtime and catalogue agreed with each other, and the register says
    so itself ("a recovery keyword that disagreed with what the runtime
    raises would be silent untracked drift rather than a red build").
    Closing it means parsing the `:recovery` ARGUMENT out of each `fail!`
    call and each hand-built refusal map, including the sites that
    compute it conditionally.  That is a different rule with its own
    blind spots; it is not folded in here to look thorough.  Filed.
  * It does not check that the index's PROSE is true, only its keywords.
  * It reads ONE page.  A complaint documented somewhere else in the
    guide, or twice, is outside its subject.

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
# The guide R8 resolves its chapter anchors against.  It shipped from
# `docs/design/hicasso/draft-guide/`; rf2-0yp7w promoted that corpus into the
# published tree, where the chapter FILENAMES (`NN-*.md`) are unchanged, so
# `guide_chapter` needed no other edit.
GUIDE_DIR = os.path.join(REPO_ROOT, "docs", "core", "hicasso")

# The reader's route into both catalogues (rf2-hic-068).  R9's subject.
TROUBLESHOOTING = os.path.join(GUIDE_DIR, "troubleshooting.md")

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

# An index entry: an explicit anchor immediately above the heading that names
# the id.  Explicit because the anchor is the deep-link target every other page
# and every error message points at, and a heading's GENERATED slug is a
# rendering detail that changes when the heading's wording does.
_ENTRY_RE = re.compile(
    r'^<a id="([^"]+)"></a>\r?\n#### `(:rf\.error/[^`]+)`\s*$', re.MULTILINE)
# A recovery keyword as either document writes it: a backticked plain keyword.
_RECOVERY_TOKEN_RE = re.compile(r"`(:[a-z][a-z0-9-]*)`")
# Markdown table cells.  An escaped pipe is content, not a cell boundary — no
# live row uses one today, and splitting on it would silently mis-shape a row
# that later does, shifting the recovery cell one place left.
_CELL_SPLIT_RE = re.compile(r"(?<!\\)\|")


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


def read_spec_recoveries(path):
    """`{id: [recovery keyword, …]}` off Spec 009's recovery cell.

    The recovery is the SECOND-TO-LAST cell of a catalogue row (`| id |
    severity | channel | meaning | recovery | payload |`), read
    positionally from the right so a meaning cell's own length and
    punctuation cannot move it.
    """
    recoveries = {}
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            match = _ROW_ID_RE.match(line)
            if not match:
                continue
            cells = [cell.strip()
                     for cell in _CELL_SPLIT_RE.split(line.strip().strip("|"))]
            if len(cells) >= 2:
                recoveries[match.group(1)] = _RECOVERY_TOKEN_RE.findall(cells[-2])
    return recoveries


def read_index(path):
    """`(entries, recoveries)` off the troubleshooting complaint index.

    `entries` is `{id: anchor-count}` — a COUNT rather than a set, because
    "exactly one" is the rule and a second copy of an entry is a page
    where a reader's two routes can disagree.  `recoveries` is `{id:
    [keyword, …]}`, read from the `Recovery:` block inside that id's own
    section: the line that opens it plus the lines that continue it, up
    to the first blank one.

    A mis-slugged anchor is reported as `entries` disagreeing with the
    heading rather than silently repaired, and shows up in `mismatched`.
    """
    if not os.path.isfile(path):
        return {}, {}, []
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    entries, recoveries, mismatched = {}, {}, []
    matches = list(_ENTRY_RE.finditer(text))
    for position, match in enumerate(matches):
        anchor, error_id = match.group(1), match.group(2)
        entries[error_id] = entries.get(error_id, 0) + 1
        if anchor != error_id.split("/", 1)[-1]:
            mismatched.append((error_id, anchor))
        end = matches[position + 1].start() if position + 1 < len(matches) else len(text)
        found = []
        lines = text[match.end():end].splitlines()
        for number, line in enumerate(lines):
            if line.startswith("Recovery:"):
                block = [line]
                for following in lines[number + 1:]:
                    if not following.strip():
                        break
                    block.append(following)
                found.extend(_RECOVERY_TOKEN_RE.findall(" ".join(block)))
        recoveries.setdefault(error_id, []).extend(found)
    return entries, recoveries, mismatched


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

def check(register, emitted, package_ids, spec_active, spec_retired, chapter_text,
          index_entries=None, index_recoveries=None, index_mismatched=None,
          spec_recoveries=None):
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
    index_entries = {} if index_entries is None else index_entries
    index_recoveries = {} if index_recoveries is None else index_recoveries
    index_mismatched = [] if index_mismatched is None else index_mismatched
    spec_recoveries = {} if spec_recoveries is None else spec_recoveries

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

    # R9 — the index is the route in.  Both directions against `live`, which
    # R1 and R2 have already pinned to the raise set, so this IS the index /
    # raise-set round trip.  See the header for what it cannot see.
    for error_id in sorted(live):
        count = index_entries.get(error_id, 0)
        if count == 0:
            failures.append(
                "R9 %s is raised by the package and has no anchor in the "
                "troubleshooting index. A complaint a reader cannot look up is "
                "a stable id with nowhere to spend it" % error_id)
        elif count > 1:
            failures.append(
                "R9 %s is anchored %d times in the troubleshooting index. Two "
                "entries are two answers that can drift apart; keep one"
                % (error_id, count))
    for error_id in sorted(index_entries):
        if error_id not in live:
            where = [status for status in EXCLUSIVE_STATUSES
                     if error_id in register[status]]
            failures.append(
                "R9 %s is anchored in the troubleshooting index and is not "
                "`live` (%s). The index documents what the runtime raises "
                "TODAY" % (error_id, " and ".join(where) or "in no register row"))
    for error_id, anchor in sorted(index_mismatched):
        failures.append(
            "R9 %s is indexed under the anchor %r. The anchor is the deep-link "
            "target; it is the id's local part or inbound links rot"
            % (error_id, anchor))
    for error_id in sorted(index_entries):
        if error_id not in live:
            continue                      # already reported, one failure each
        shown = index_recoveries.get(error_id) or []
        if not shown:
            failures.append(
                "R9 %s is indexed with no recovery keyword. The keyword is what "
                "the reader matches against what they caught" % error_id)
            continue
        rowed = set(spec_recoveries.get(error_id, []))
        for keyword in sorted(set(shown) - rowed):
            failures.append(
                "R9 %s is indexed with the recovery %s, which its Spec 009 row "
                "does not carry. `:recovery` is advice about a LIVE api and "
                "moves when that api is renamed — move it in one pass across "
                "the runtime, Spec 009 and this page (rf2-15bqc)"
                % (error_id, keyword))
    return failures


def report(failures):
    """Print `failures` in the gate's voice, and return the exit code 1.

    Both entry points red through here, so the rule that broke is the first
    thing on screen whichever one you ran.
    """
    print("FAIL: Hicasso complaint catalogue\n")
    for failure in failures:
        print("  " + failure)
    print("\nThe register is docs/design/hicasso/product/complaints.md; the "
          "rule each R-number names is in this script's header.")
    return 1


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
    index_entries, index_recoveries, index_mismatched = read_index(TROUBLESHOOTING)
    return dict(register=register, emitted=emitted, package_ids=package_ids,
                spec_active=spec_active, spec_retired=spec_retired,
                chapter_text=chapter_text, index_entries=index_entries,
                index_recoveries=index_recoveries,
                index_mismatched=index_mismatched,
                spec_recoveries=read_spec_recoveries(SPEC_009))


# ---------------------------------------------------------------------------
# --self-test: prove the gate classifies, rather than merely runs
# ---------------------------------------------------------------------------

def self_test():
    # THIS SELF-TEST'S OWN CLAIMS MUST NOT BE DELETABLE (rf2-uyhh). `python -O`
    # — and `PYTHONOPTIMIZE` in the environment, which needs no flag at the
    # call site — strips every `assert` below, leaving a function that runs to
    # its success line having verified nothing. That is a control failing
    # GREEN, the one direction that never announces itself. The check is
    # empirical rather than a reading of `__debug__`, so it also catches a
    # `.pyc` compiled under `-O` and run without it.
    try:
        assert False
    except AssertionError:
        pass
    else:
        raise SystemExit(
            "assertions are disabled (python -O / PYTHONOPTIMIZE), so this "
            "self-test would prove nothing. Re-run without -O."
        )

    assert mask('(fail! :rf.error/a "text :rf.error/in-a-string")') \
        .count(":rf.error/") == 1, "a string literal must not emit an id"
    assert ":rf.error/" not in mask("; :rf.error/in-a-comment\n"), \
        "a comment must not emit an id"
    assert mask('(= \\" :rf.error/after-a-quote-char)').count(":rf.error/") == 1, \
        "a quote character literal must not open a string"

    # R9's readers are proved on written-down fixtures rather than only
    # through `check`, because a reader that silently found NOTHING would make
    # every R9 arm below vacuous in the one direction that passes.
    assert read_spec_recoveries.__doc__, "the recovery reader is documented"
    assert _ENTRY_RE.search('<a id="hicasso-x"></a>\n#### `:rf.error/hicasso-x`\n'), \
        "the index entry shape must match with a bare newline"
    assert _ENTRY_RE.search('<a id="hicasso-x"></a>\r\n#### `:rf.error/hicasso-x`\r\n'), \
        "and with CRLF, which is what these files actually carry"
    assert _CELL_SPLIT_RE.split(r"a \| b | c") == [r"a \| b ", " c"], \
        "an escaped pipe is content, not a cell boundary"

    inputs = read_all()
    # Every red below is driven by doctoring ONE input of a green tree, so a
    # tree that is already red cannot be tested against.  That is a finding
    # about the register rather than about this self-test, and it is by far
    # the likeliest way this entry point fails on a PR — so it is reported
    # exactly as a plain run reports it.  An `assert` here would bury the rule
    # that broke behind an AssertionError.
    failures = check(**inputs)
    if failures:
        code = report(failures)
        print("\n(--self-test drives its reds by doctoring a GREEN tree's "
              "inputs, so it stops here.)")
        return code

    register = inputs["register"]
    emitted = inputs["emitted"]
    package_ids = inputs["package_ids"]
    spec_active = inputs["spec_active"]
    chapters = inputs["chapter_text"]
    index_entries = inputs["index_entries"]
    index_recoveries = inputs["index_recoveries"]

    def red(prefix, **overrides):
        args = dict(inputs)
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

    # R9 — one control per way the index and the raise set can part company.
    # The first is not hypothetical: it is the state this rule was written to
    # end.  `:rf.error/hicasso-overlay-anchor-missing` was promoted from
    # `reserved` to `live` by rf2-1ppe0 and the index was not extended, and
    # every rule R1–R8 stayed green over it — the register agreed with the
    # runtime, the runtime agreed with Spec 009, and the page nobody checked
    # was the only document that was wrong.
    without = dict(index_entries)
    without.pop(a_live)
    red("R9", index_entries=without)                     # a live id, unindexed
    red("R9", index_entries=dict(index_entries, **{a_live: 2}))   # indexed twice
    red("R9", index_entries=dict(index_entries,
                                 **{a_reserved: 1}))     # a reservation, indexed
    red("R9", index_entries=dict(index_entries,
                                 **{":rf.error/hicasso-not-a-complaint": 1}))
    red("R9", index_mismatched=[(a_live, "an-anchor-nothing-points-at")])
    red("R9", index_recoveries=dict(index_recoveries, **{a_live: []}))
    red("R9", index_recoveries=dict(index_recoveries,
                                    **{a_live: [":a-recovery-spec-009-never-rowed"]}))
    # And the drift that started this: the page keeping a spelling the runtime
    # and Spec 009 have moved on from (rf2-15bqc's four `h-fn` keywords).
    red("R9", index_recoveries=dict(
        index_recoveries,
        **{":rf.error/hicasso-intent-needs-the-event":
           [":write-an-h-fn-at-a-value-first-position"]}))

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

    inputs = read_all()
    failures = check(**inputs)
    if failures:
        return report(failures)
    register = inputs["register"]
    print("OK: %d live, %d reserved, %d pending retirement, %d retired; every "
          "live row is emitted and rowed in Spec 009, every reservation is "
          "unbuilt, every anchor resolves, and the troubleshooting index "
          "carries all %d live ids exactly once with Spec 009's recoveries."
          % (len(register["live"]), len(register["reserved"]),
             len(register["pending-retirement"]), len(register["retired"]),
             len(register["live"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
