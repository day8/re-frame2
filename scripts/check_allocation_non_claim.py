#!/usr/bin/env python3
"""The warm-allocation NON-CLAIM scan (rf2-hic-072).

`specification.md` §6 registers one sentence about warm allocation and this
gate is that sentence, executable:

>   Warm-allocation evidence is not a release gate: **no allocation claim
>   publishes until a fitted series clears the registered quality floor.**

No fitted series clears it. That is not a gap awaiting a number — it is a
measured result with three independent legs, recorded in the tree:

  * `rf2-2rtt6.140` measured a FIXED per-write cost of F ~= 24.4 KB on this
    rig (24,108 B on `reagent-subs`, 24,730 B on `uix-subs`) which does not
    shrink as the page shrinks. Against the masking bound's 42,857 B per
    write at the six-write averaging floor, F alone is 57% of the budget
    before a single boundary is measured. At six writes there is no page of
    one boundary or more that certifies the 1/3/7/20 ladder.
  * `rf2-2rtt6.139` retired the sizing constant `ALLOC_B_PER_BOUNDARY_WRITE
    = 1655`: it was read off a window the instrument itself REFUSED, so it
    is a lower bound taken from an invalid measurement.
  * `rf2-e9wr` found tau cannot be pinned, because the controls are not a
    valid calibration population for the arms — the controls' work unit has
    no first-write re-allocation and the arms' has one, in 336 of 336
    measured windows.

So the obligation this gate enforces is NEGATIVE, and the failure mode it
exists to catch is somebody reaching for one of those refused figures to
fill a cell that wants a number. That is the exact shape `rf2-2rtt6.139`
was filed to name.


## What counts as a claim

A number bound to a PER-WRITE byte unit. `2,031 B/boundary/write`,
`24.4 KB per write`, `1655 bytes per write`. That unit IS the
warm-allocation estimand and nothing else in this repository uses it:
retained heap is measured per READ or per BOUNDARY and never carries the
`/write` denominator, so the discriminator is exact rather than heuristic.

It is deliberately NOT a keyword scan for the word *allocation*. The word
is unavoidable in prose about an instrument that measures allocation, and a
gate that fired on it would be a gate authors route around by rewording.
The figure is the thing that cannot be reworded, so the figure is the
thing that is checked.


## Where it is a claim, and where it is evidence

The distinction is the corpus, and the reason is not cosmetic. The design
record's job is to say what was measured, including what was measured and
refused; a gate that silenced it would delete the evidence FOR the
non-claim in the name of enforcing the non-claim. So:

  * THE PUBLICATION SURFACE — `README.md`, `CHANGELOG.md`, and every
    tracked `docs/**/*.md` OUTSIDE `docs/design/` — is what a consumer
    reads, and a figure there is a product claim. This is the row that
    must be empty.
  * THE DESIGN RECORD — `docs/design/` — is a working record and its
    figures are evidence. It is the POSITIVE CONTROL below rather than a
    scanned row.

Qualification is honoured rather than assumed away: a publication-surface
figure passes if its own paragraph also carries the non-claim in words.
The rule is *no claim WITHOUT its qualification*, not *no figure*, because
a page that states the number and then says the instrument does not
certify it has published no claim — it has published the refusal, which is
the truthful thing to publish. Today no such paragraph exists and the row
is empty, which is a stronger state and not the one being enforced.


## The positive control, and why an absence check needs one

An absence check over a corpus the scan never really read is a green that
means nothing, and it is the failure this class of gate is most exposed to
(the idiom is `implementation/hicasso/scripts/check_bundle_isolation.cjs`'s,
and so is the reasoning). So the same pattern that must find NOTHING on the
publication surface must find SOMETHING in the design record, where the
instrument's own measurements legitimately live. If it finds nothing there,
the pattern has rotted or the tree has moved, and the scan says so rather
than reporting a green absence for a pattern that no longer matches prose.


## The premise, checked before the rows

The gate enforces a rule whose whole justification is that the floor is
UNMET. If that ever stops being true the rule stops being right, and a
guard that keeps enforcing a retired rule is worse than no guard. So the
two places the tree records the floor's state are read first, and a change
in either FAILS the scan asking for re-authorisation rather than silently
continuing. This gate is designed to be retired by its own premise check.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# A number bound to a per-write byte unit.  `2,031 B/boundary/write`,
# `24.4 KB per write`, `1655 bytes per write`.  The `/write` denominator is
# what makes it warm allocation rather than retained heap.
CLAIM = re.compile(
    r"[0-9][0-9,.]*\s*(?:B|KB|MB|bytes?)\s*(?:/|per\s+)\s*"
    r"(?:boundary\s*(?:/|per\s+)\s*)?write",
    re.IGNORECASE,
)

# Words that say, in the same paragraph, that the figure is not certified.
# Deliberately generous: the gate's job is to catch an UNQUALIFIED claim,
# and a false red on a paragraph that qualifies honestly in wording this
# list did not anticipate would teach authors to fight the gate.
QUALIFIERS = re.compile(
    r"no publishable claim|no allocation claim|not a claim|non-claim"
    r"|no claim|does not certify|cannot certify|uncertifiable"
    r"|quality floor|no fitted series|not a release gate"
    r"|no slope is published|lower bound|refused|unpinned|unmet",
    re.IGNORECASE,
)

# The two rows that record the floor's state.  If either stops reading this
# way the premise has moved and the gate must be re-authorised.
PREMISES = [
    (
        "docs/design/hicasso/product/budgets.md",
        re.compile(r"\|\s*S7\s*\|\s*Warm allocation\s*\|\s*no publishable claim", re.IGNORECASE),
        "budgets.md section 9's S7 row reads `no publishable claim`",
    ),
    (
        "docs/design/hicasso/product/lanes/evidence-baseline.md",
        re.compile(r"\|\s*Warm allocation\s*\|\s*No fitted series clears the registered quality floor", re.IGNORECASE),
        "evidence-baseline.md's Warm allocation row reads `No fitted series clears the registered quality floor`",
    ),
]

DESIGN_PREFIX = "docs/design/"


def tracked_markdown(root: Path) -> list[str]:
    """Every tracked markdown path git knows about, repo-relative, POSIX slashes."""
    out = subprocess.run(
        ["git", "-C", str(root), "ls-files", "*.md"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def publication_corpus(paths: list[str]) -> list[str]:
    """The consumer-facing surface: root README/CHANGELOG plus docs outside the design tree."""
    keep = []
    for p in paths:
        if p in ("README.md", "CHANGELOG.md"):
            keep.append(p)
        elif p.startswith("docs/") and not p.startswith(DESIGN_PREFIX):
            keep.append(p)
    return sorted(keep)


def design_corpus(paths: list[str]) -> list[str]:
    return sorted(p for p in paths if p.startswith(DESIGN_PREFIX))


def paragraphs(text: str) -> list[tuple[int, str]]:
    """(1-based start line, paragraph text) for each blank-line-separated block."""
    blocks: list[tuple[int, str]] = []
    line_no = 1
    for block in re.split(r"\n[ \t]*\n", text):
        if block.strip():
            blocks.append((line_no, block))
        line_no += block.count("\n") + 2
    return blocks


def unqualified_claims(text: str) -> list[tuple[int, str]]:
    """Paragraphs carrying a per-write figure and no qualification of it."""
    found = []
    for start, block in paragraphs(text):
        m = CLAIM.search(block)
        if m and not QUALIFIERS.search(block):
            found.append((start, m.group(0).strip()))
    return found


def scan(root: Path) -> tuple[list[str], list[str]]:
    """Returns (problems, notes). An empty problems list is the green verdict."""
    problems: list[str] = []
    notes: list[str] = []

    paths = tracked_markdown(root)

    # --- the premise, before anything else -------------------------------
    for rel, pattern, description in PREMISES:
        f = root / rel
        if not f.is_file():
            problems.append(
                f"PREMISE GONE: {rel} does not exist. This gate enforces a rule whose\n"
                f"    justification is that the warm-allocation quality floor is unmet, and\n"
                f"    {description}\n"
                f"    is where the tree records that. Re-authorise the gate rather than\n"
                f"    repointing it silently."
            )
            continue
        if not pattern.search(f.read_text(encoding="utf-8")):
            problems.append(
                f"PREMISE MOVED: {rel} no longer records the floor as unmet.\n"
                f"    Expected: {description}.\n"
                f"    If a fitted series has cleared the quality floor then an allocation\n"
                f"    claim is now publishable and THIS GATE SHOULD BE RETIRED, not\n"
                f"    repaired. If it has not, the row has drifted and the row is the bug."
            )

    # --- the positive control --------------------------------------------
    design = design_corpus(paths)
    control_hits = [p for p in design if CLAIM.search((root / p).read_text(encoding="utf-8"))]
    if not control_hits:
        problems.append(
            "POSITIVE CONTROL ABSENT: the claim pattern matches nothing anywhere under\n"
            f"    {DESIGN_PREFIX}, where the allocation instrument's own measurements live.\n"
            "    An absence check whose pattern matches no real prose is a green that means\n"
            "    nothing. Either the pattern has rotted or the design record has moved."
        )
    else:
        notes.append(
            f"positive control: the pattern fires in {len(control_hits)} design-record file(s) "
            f"under {DESIGN_PREFIX}"
        )

    # --- the row that must be empty --------------------------------------
    publication = publication_corpus(paths)
    for rel in publication:
        for line, figure in unqualified_claims((root / rel).read_text(encoding="utf-8")):
            problems.append(
                f"UNQUALIFIED ALLOCATION CLAIM: {rel}:{line} publishes {figure!r}.\n"
                "    No fitted series clears the registered quality floor, so no allocation\n"
                "    claim is publishable (specification.md section 6). State the refusal in\n"
                "    the same paragraph, or take the figure out. Do NOT reach for a number\n"
                "    from a window the instrument refused -- that is rf2-2rtt6.139's finding."
            )

    notes.append(
        f"scanned {len(publication)} publication-surface file(s); "
        f"{len(design)} design-record file(s) excluded as evidence rather than claim"
    )
    return problems, notes


# ---------------------------------------------------------------------------
# self-test
#
# Four fixtures, one per way this gate can fail OPEN.  Each is written into a
# throwaway tree with its own git repository, because the corpus is derived
# from `git ls-files` and a fixture that skipped that would not exercise the
# code path the real run takes.
# ---------------------------------------------------------------------------

_GOOD_BUDGETS = "| S7 | Warm allocation | no publishable claim | allocation instrument | floor |\n"
_GOOD_BASELINE = "| Warm allocation | No fitted series clears the registered quality floor | x | y |\n"


def _make_tree(tmp: Path, *, budgets: str, baseline: str, design: str, publication: str) -> Path:
    root = tmp / "tree"
    (root / "docs" / "design" / "hicasso" / "product" / "lanes").mkdir(parents=True)
    (root / "docs" / "core").mkdir(parents=True)
    (root / "docs" / "design" / "hicasso" / "product" / "budgets.md").write_text(budgets, encoding="utf-8")
    (root / "docs" / "design" / "hicasso" / "product" / "lanes" / "evidence-baseline.md").write_text(
        baseline, encoding="utf-8"
    )
    (root / "docs" / "design" / "hicasso" / "record.md").write_text(design, encoding="utf-8")
    (root / "docs" / "core" / "guide.md").write_text(publication, encoding="utf-8")
    subprocess.run(["git", "-C", str(root), "init", "-q"], check=True)
    subprocess.run(["git", "-C", str(root), "add", "-A"], check=True)
    return root


def _run_self_tests() -> int:
    import tempfile

    failures = 0
    cases = []

    # 1. The shape of the real tree: green.
    cases.append(
        (
            "a qualified design record and a figure-free publication surface is GREEN",
            dict(
                budgets=_GOOD_BUDGETS,
                baseline=_GOOD_BASELINE,
                design="The floor arm reads 24,108 B per write.\n",
                publication="Hicasso keeps high-rate work local to a native host.\n",
            ),
            None,
        )
    )

    # 2. THE SEEDED VIOLATION the bead asks to be demonstrated red.
    cases.append(
        (
            "an unqualified figure on the publication surface is RED",
            dict(
                budgets=_GOOD_BUDGETS,
                baseline=_GOOD_BASELINE,
                design="The floor arm reads 24,108 B per write.\n",
                publication="Each boundary costs about 2,031 B/boundary/write in a warm page.\n",
            ),
            "UNQUALIFIED ALLOCATION CLAIM",
        )
    )

    # 3. The same figure, qualified in its own paragraph: GREEN. The rule is
    #    *no claim without its qualification*, and this is the half that
    #    proves the gate is not simply banning the digits.
    cases.append(
        (
            "the same figure WITH its qualification is GREEN",
            dict(
                budgets=_GOOD_BUDGETS,
                baseline=_GOOD_BASELINE,
                design="The floor arm reads 24,108 B per write.\n",
                publication=(
                    "An early window read about 2,031 B/boundary/write, but no fitted series\n"
                    "clears the quality floor, so this is not a claim.\n"
                ),
            ),
            None,
        )
    )

    # 4. The premise moving is RED, so the gate cannot outlive its own reason.
    cases.append(
        (
            "a cleared quality floor is RED, demanding re-authorisation",
            dict(
                budgets="| S7 | Warm allocation | 1,655 B/boundary/write | instrument | MET |\n",
                baseline=_GOOD_BASELINE,
                design="The floor arm reads 24,108 B per write.\n",
                publication="Nothing to see here.\n",
            ),
            "PREMISE MOVED",
        )
    )

    # 5. A design record with no figure at all trips the positive control,
    #    which is what stops a green from meaning "the scan read nothing".
    cases.append(
        (
            "a design record with no figure trips the POSITIVE CONTROL",
            dict(
                budgets=_GOOD_BUDGETS,
                baseline=_GOOD_BASELINE,
                design="Prose about allocation with no figure in it at all.\n",
                publication="Nothing to see here.\n",
            ),
            "POSITIVE CONTROL ABSENT",
        )
    )

    for name, kwargs, expected in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = _make_tree(Path(tmp), **kwargs)
            problems, _ = scan(root)
            if expected is None:
                if problems:
                    failures += 1
                    print(f"SELF-TEST FAIL: {name}\n  expected green, got:\n    " + "\n    ".join(problems))
                else:
                    print(f"  ok  {name}")
            else:
                if not any(expected in p for p in problems):
                    failures += 1
                    print(f"SELF-TEST FAIL: {name}\n  expected {expected!r}, got: {problems}")
                else:
                    print(f"  ok  {name}")

    if failures:
        print(f"\n{failures} self-test failure(s).")
        return 1
    print(f"\nwarm-allocation non-claim scan: self-test OK ({len(cases)} fixtures).")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Fail if the publication surface carries a warm-allocation claim without its qualification."
    )
    parser.add_argument("--self-test", action="store_true", help="run the seeded fixtures and exit")
    args = parser.parse_args(argv)

    if args.self_test:
        return _run_self_tests()

    problems, notes = scan(REPO_ROOT)
    for n in notes:
        print(f"  {n}")
    if problems:
        print("\nwarm-allocation non-claim scan: FAILED\n")
        for p in problems:
            print(f"  - {p}\n")
        return 1
    print(
        "\nOK: no warm-allocation claim publishes while the quality floor is unmet.\n"
        "This is a verdict about the RECORD. It is not a statement that any allocation\n"
        "figure is known -- none is, and rf2-2rtt6.139, rf2-2rtt6.140 and rf2-e9wr are why."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
