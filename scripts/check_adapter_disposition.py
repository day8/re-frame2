#!/usr/bin/env python3
"""Authority guard: the current re-frame2 ADAPTER DISPOSITION (Mike, 2026-07-17).

The ruling, whose source of record is
`docs/EP/EP-0030-the-compiled-view-substrate-program.md` §Resolved Decisions:

  * `re-frame.ui` is a **new, EXPERIMENTAL** view substrate offered **alongside**
    the existing adapters — not a mandated replacement, not the default, and not
    the only taught view layer.
  * **Stock Reagent, reagent-slim, and UIx live on as first-class, actively-
    supported adapters** — not frozen, not scheduled for removal.
  * **Only Helix is removed**, behind the S7 soak gates.

This supersedes an earlier ("frozen compatibility adapters" / "reagent-slim
removed" / "only taught view layer") shape that was ratified 2026-07-11 and
reframed 2026-07-17. That superseded shape had propagated into planning,
implementor, source-comment, and synthesis authorities; a worker dispatched from
any of them would have removed or downgraded a supported surface. This guard
exists so the superseded statuses cannot silently return. Owner: rf2-vxgfnd.290.

SCOPE — deliberately narrow. This is NOT a general prose parser and NOT a corpus
sweep. It scans a CLOSED roster of files that are ACTIVE dispatchable authorities
(`ACTIVE_AUTHORITIES`), for a CLOSED set of superseded-status sentence shapes
(`SUPERSEDED_PATTERNS`). Prose that merely mentions an adapter, or discusses the
lifecycle/technical realizations, is out of scope by construction.

HONEST EXEMPTIONS — a line that states a superseded status is NOT a defect when it
is visibly RECORDING that the status is superseded rather than asserting it. Two
exemptions, both requiring an explicit marker on the SAME line:

  1. A supersession marker (`SUPERSESSION_MARKERS`) — "superseded", "formerly",
     "no longer", "VOID", "read it as", "predates this ruling", "reconciled 20xx".
     This is what lets EP-0030 say the old shape *is superseded*, and lets a
     reconciled synthesis row say it *formerly* read otherwise.
  2. A genuine dated historical snapshot — a snapshot/historical/"as of"
     qualifier AND an ISO-8601 date (YYYY-MM-DD). A bare "historically" with no
     date does not exempt.

HTML comment regions (`<!-- ... -->`) are blanked before the scan, so guidance
prose inside a comment cannot trip the rule.

POSITIVE ASSERTION — EP-0030, as the source of record, must actually STATE the
current ruling. Deleting the ruling from EP-0030 while leaving every other file
merely silent would otherwise pass; `EP0030_REQUIRED` prevents that.

NOT YET IN THE ROSTER (pending, deliberate):
  * `spec/004-Views.md` — its transition annex (the `[SUPERSEDED]`-marked block
    and the reg-view "freezes"/"v1 (frozen — compat tier)" text ABOVE that
    marker) still carries the superseded shape. It is HOT-ZONE and was held by
    another worker when rf2-vxgfnd.290 ran, so its repair is sequenced
    separately. ADD IT TO `ACTIVE_AUTHORITIES` in the same PR that repairs it.
  * `.github/workflows/release.yml` — one stale comment phrase ("the compiled-
    view substrate that will replace the adapter trio"). CI surfaces are owned by
    rf2-nojiwy; add the file here when that owner corrects the phrase.

Exit code:
    0  no superseded adapter status on an active authority
    1  drift detected (printed line-by-line; GitHub-Actions ::error:: under --ci)
    2  invocation / setup error (repo root or a rostered file not found)

Dependency-light — Python stdlib only.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

# --- The closed roster of ACTIVE dispatchable authorities ---------------------
ACTIVE_AUTHORITIES = (
    "docs/EP/EP-0030-the-compiled-view-substrate-program.md",
    "skills/re-frame2-implementor/references/phase-2-impl-order.md",
    "implementation/README.md",
    "implementation/adapters/reagent/README.md",
    "ai/findings/new-substrate-synthesis/08-delivery.md",
    "ai/findings/new-substrate-synthesis/11-adoption-workstreams.md",
    "ai/findings/new-substrate-synthesis/12-implementation-plan.md",
    "ai/findings/new-substrate-synthesis/prep/w3-docs-disposition.md",
    "ai/findings/new-substrate-synthesis/prep/w9-ci-matrix-disposition.md",
)

EP0030_REL = "docs/EP/EP-0030-the-compiled-view-substrate-program.md"

# --- The closed set of superseded-status sentence shapes ----------------------
# Each entry: (compiled regex, human explanation of what the current ruling says).
SUPERSEDED_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(r"frozen\s+compat(?:ibility)?\s+(?:adapter|tier|Reagent)", re.I),
        'the "frozen compatibility adapter/tier" shape is superseded — Reagent, '
        "reagent-slim, and UIx are first-class and actively supported",
    ),
    (
        re.compile(r"frozen[- ](?:stock[- ])?(?:Reagent|UIx)\b", re.I),
        "Reagent and UIx are not frozen — they live on as first-class adapters",
    ),
    (
        re.compile(r"\b(?:Reagent|UIx|reagent-slim)\s+(?:adapters?\s+)?freezes?\b", re.I),
        "no adapter freezes — only Helix is removed",
    ),
    (
        # "…the reg-view family freezes WITH stock Reagent", "they freeze INTO the
        # stock-Reagent compatibility tier" — the subject is separated from the verb,
        # so anchor on the verb plus its adapter/compat object instead.
        re.compile(
            r"freezes?\s+(?:in)?to\s+(?:the\s+)?(?:stock[- ])?(?:Reagent|compat\w*)"
            r"|freezes?\s+with\s+(?:the\s+)?(?:stock\s+)?Reagent",
            re.I,
        ),
        "the reg-view / Reagent family does not freeze — Reagent stays first-class",
    ),
    (
        re.compile(r"retiring\s+UIx|UIx\s+(?:is\s+|are\s+)?retir(?:es|ing|ed)", re.I),
        "UIx is not retiring — only Helix is removed",
    ),
    (
        re.compile(r"only[- ]taught\s+(?:view\s+)?layer|only\s+taught\s+(?:view\s+)?layer", re.I),
        "`re-frame.ui` is experimental and is NOT the only taught view layer",
    ),
    (
        re.compile(r"(?:slated\s+to\s+replace|replaces?)\s+the\s+adapter\s+trio", re.I),
        "`re-frame.ui` replaces nothing — it is offered alongside the adapters",
    ),
    (
        re.compile(
            r"reagent-slim\s+(?:is\s+|are\s+|remains?\s+)?"
            r"(?:deleted|removed|retired|retires|exits)\b",
            re.I,
        ),
        "reagent-slim is NOT deleted — it lives on as a first-class adapter",
    ),
    (
        re.compile(
            r"(?:Helix\s*\+\s*reagent-slim|reagent-slim\s*\+\s*Helix|Helix\s+and\s+"
            r"reagent-slim|Helix\s*/\s*(?:reagent-)?slim)\s+"
            r"(?:are\s+|is\s+)?(?:deleted|removed|exit|retire)",
            re.I,
        ),
        "only Helix is removed; reagent-slim is retained",
    ),
    (
        re.compile(r"defaults?\s+to\s+`?re-frame\.ui`?", re.I),
        "`re-frame.ui` is experimental and is not the default view layer",
    ),
)

# --- Exemptions ---------------------------------------------------------------
SUPERSESSION_MARKERS = (
    "superseded",
    "supersedes",
    "formerly",
    "no longer",
    "void",
    "read it as",
    "predates this ruling",
    "reconciled 20",
    # Negations — the line states the CURRENT ruling by denying the old shape.
    "not the only",
    "not a mandated",
    "not frozen",
    "none is frozen",
    "not scheduled for removal",
    "not deleted",
    "not removed",
    "not retiring",
)

_HISTORICAL_QUALIFIER = re.compile(r"snapshot|historical|historically|as of\b", re.I)
_ISO_DATE = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")
_HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)

# --- EP-0030 positive assertions ---------------------------------------------
EP0030_REQUIRED: tuple[tuple[re.Pattern[str], str], ...] = (
    (
        re.compile(r"experimental", re.I),
        "EP-0030 must state that `re-frame.ui` is EXPERIMENTAL",
    ),
    (
        re.compile(r"first-class", re.I),
        "EP-0030 must state that the retained adapters are FIRST-CLASS",
    ),
    (
        re.compile(r"only\s+Helix\s+is\s+removed", re.I),
        'EP-0030 must state that "only Helix is removed"',
    ),
    (
        re.compile(r"reagent-slim", re.I),
        "EP-0030 must name reagent-slim among the retained adapters",
    ),
)


def _blank_html_comments(text: str) -> str:
    """Replace HTML comment regions with newline-preserving blanks."""
    return _HTML_COMMENT.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)


_EMPHASIS = re.compile(r"[*_`]+")
_WHITESPACE = re.compile(r"\s+")


def _normalize(text: str) -> str:
    """Strip Markdown emphasis and collapse whitespace before marker matching.

    Authors bold the negation — `**not** the only taught view layer` — which would
    otherwise split the "not the only" marker with asterisks and defeat the
    exemption. Emphasis is presentation, never meaning, so it is removed first.
    """
    return _WHITESPACE.sub(" ", _EMPHASIS.sub("", text)).lower()


def _line_is_exempt(line: str) -> bool:
    normalized = _normalize(line)
    if any(marker in normalized for marker in SUPERSESSION_MARKERS):
        return True
    if _HISTORICAL_QUALIFIER.search(normalized) and _ISO_DATE.search(normalized):
        return True
    return False


def scan_text(text: str, rel: str) -> list[str]:
    """Return one problem string per superseded-status line in `text`.

    Exemption is evaluated over a ±1-line CONTEXT WINDOW, not the matched line
    alone: Markdown prose hard-wraps, so a claim and the marker that neutralizes
    it ("... is superseded", "not the\\nonly taught view layer") routinely land on
    adjacent lines. One line of slack is enough for wrapped prose and is still far
    too tight to let an unqualified assertion hide behind a distant disclaimer.
    """
    problems: list[str] = []
    lines = _blank_html_comments(text).splitlines()
    for index, line in enumerate(lines):
        lineno = index + 1
        window = " ".join(lines[max(0, index - 1): index + 2])
        if _line_is_exempt(window):
            continue
        for pattern, explanation in SUPERSEDED_PATTERNS:
            match = pattern.search(line)
            if match:
                problems.append(
                    f"ADAPTER-DISPOSITION-DRIFT: {rel}:{lineno} asserts a SUPERSEDED "
                    f"adapter status ({match.group(0)!r}) — {explanation}. "
                    "Current ruling: EP-0030 §Resolved Decisions (2026-07-17). "
                    "If this line is RECORDING the superseded shape rather than "
                    "asserting it, mark it (e.g. 'superseded', 'formerly', 'VOID')."
                )
                break
    return problems


def ep0030_problems(text: str | None) -> list[str]:
    """Positive assertions: the source of record must state the current ruling."""
    if text is None:
        return []
    problems: list[str] = []
    for pattern, explanation in EP0030_REQUIRED:
        if not pattern.search(text):
            problems.append(
                f"ADAPTER-DISPOSITION-SOURCE-OF-RECORD: {EP0030_REL} — {explanation}. "
                "The ruling must remain stated at its source of record, not merely "
                "implied by other files' silence."
            )
    return problems


class SetupError(Exception):
    """A rostered authority is missing — an invocation/setup failure, not drift."""


def check(repo_root: Path, *, verbose: bool = False, ci: bool = False) -> int:
    problems: list[str] = []
    for rel in ACTIVE_AUTHORITIES:
        path = repo_root / rel
        if not path.is_file():
            raise SetupError(
                f"rostered authority {rel} not found under {repo_root}. "
                "If the file moved, update ACTIVE_AUTHORITIES."
            )
        text = path.read_text(encoding="utf-8")
        if verbose:
            sys.stderr.write(f"scanning {rel}\n")
        problems.extend(scan_text(text, rel))
        if rel == EP0030_REL:
            problems.extend(ep0030_problems(text))

    for problem in problems:
        prefix = "::error::" if ci else ""
        sys.stderr.write(f"{prefix}{problem}\n")

    if verbose and not problems:
        sys.stderr.write(
            f"adapter disposition intact across {len(ACTIVE_AUTHORITIES)} authorities.\n"
        )
    return len(problems)


# --- Self-tests ---------------------------------------------------------------
def _run_self_tests(*, verbose: bool = False) -> int:
    failures = 0

    def expect(text: str, *, dirty: bool, label: str) -> None:
        nonlocal failures
        got = bool(scan_text(text, "fixture.md"))
        if got != dirty:
            failures += 1
            sys.stderr.write(
                f"self-test FAILED [{label}]: expected dirty={dirty}, got dirty={got}\n"
            )
        elif verbose:
            sys.stderr.write(f"self-test ok [{label}]\n")

    # Superseded statuses must be caught.
    expect(
        "Stock Reagent and UIx are frozen compatibility adapters.",
        dirty=True, label="A1 frozen compatibility adapters",
    )
    expect(
        "`re-frame.ui` is the only taught view layer.",
        dirty=True, label="A2 only taught view layer",
    )
    expect(
        "it is the substrate slated to replace the adapter trio",
        dirty=True, label="A3 replaces the adapter trio",
    )
    expect(
        "| RETIRE-AT:S7 | Slim deleted (W13); strictly behind soak gates.",
        dirty=False, label="A4 bare 'Slim deleted' phrasing is out of the closed set",
    )
    expect(
        "reagent-slim is deleted at the wave.",
        dirty=True, label="A5 reagent-slim is deleted",
    )
    expect(
        "Helix + reagent-slim are removed after the soak gates.",
        dirty=True, label="A6 Helix + reagent-slim removed",
    )
    expect(
        "New UI work defaults to `re-frame.ui`.",
        dirty=True, label="A7 defaults to re-frame.ui",
    )
    expect(
        "the `[TRANSITION]` frozen Reagent / retiring UIx + Helix adapters",
        dirty=True, label="A8 frozen Reagent / retiring UIx",
    )
    expect(
        "stock Reagent + the `reg-view` family freeze into the compatibility tier",
        dirty=True, label="A9 family freezes INTO the compat tier (subject separated)",
    )
    expect(
        "the family freezes with stock Reagent, and moves at the deletion wave",
        dirty=True, label="A10 family freezes WITH stock Reagent",
    )

    # Current-ruling prose must NOT trip.
    expect(
        "Reagent, UIx, and reagent-slim live on as first-class, actively-supported "
        "adapters; only Helix is removed.",
        dirty=False, label="B1 current ruling is clean",
    )
    expect(
        "`re-frame.ui` ships as a new, experimental view substrate offered alongside "
        "the existing adapters.",
        dirty=False, label="B2 experimental framing is clean",
    )
    expect(
        "The commit-owned two-pass realization is the React adapters' standing "
        "contract; the compiled substrate moves the timing, not the laws.",
        dirty=False, label="B3 lifecycle prose is out of scope",
    )

    # Exemptions.
    expect(
        'the original "frozen compatibility adapters" / "reagent-slim removed" shape '
        "is superseded on the adapter-disposition point",
        dirty=False, label="C1 supersession marker exempts",
    )
    expect(
        'This row formerly read "Freeze + deletion wave" and deleted reagent-slim.',
        dirty=False, label="C2 'formerly' exempts",
    )
    expect(
        "Snapshot as of 2026-07-11: stock Reagent and UIx are frozen compatibility "
        "adapters.",
        dirty=False, label="C3 dated historical snapshot exempts",
    )
    expect(
        "Historically, stock Reagent and UIx are frozen compatibility adapters.",
        dirty=True, label="C4 undated 'historically' does NOT exempt",
    )
    expect(
        "<!-- Stock Reagent and UIx are frozen compatibility adapters. -->",
        dirty=False, label="C5 HTML comment is inactive text",
    )
    expect(
        "`re-frame.ui` is an additional option, **not** a mandated replacement and\n"
        "**not** the\nonly taught view layer: Reagent, UIx, and reagent-slim live on.",
        dirty=False, label="C6 bolded negation wrapped across lines exempts",
    )
    expect(
        "The substrate is the only taught view layer.\n\n\n"
        "Separately, and much later: this was superseded.",
        dirty=True, label="C7 distant disclaimer does NOT exempt (window is +/-1)",
    )

    # EP-0030 positive assertions.
    good_ep = (
        "`re-frame.ui` ships as a new, experimental substrate. Reagent, UIx, and "
        "reagent-slim live on as first-class adapters; only Helix is removed."
    )
    if ep0030_problems(good_ep):
        failures += 1
        sys.stderr.write("self-test FAILED [D1]: complete EP-0030 text flagged\n")
    elif verbose:
        sys.stderr.write("self-test ok [D1 complete EP-0030 text]\n")

    for token, label in (
        ("experimental", "D2 experimental removed"),
        ("first-class", "D3 first-class removed"),
        ("only Helix is removed", "D4 only-Helix clause removed"),
        ("reagent-slim", "D5 reagent-slim removed"),
    ):
        if not ep0030_problems(good_ep.replace(token, "REDACTED")):
            failures += 1
            sys.stderr.write(f"self-test FAILED [{label}]: mutation not caught\n")
        elif verbose:
            sys.stderr.write(f"self-test ok [{label}]\n")

    if failures:
        sys.stderr.write(f"self-test: {failures} case(s) failed.\n")
        return 1
    sys.stderr.write("self-test: all cases passed.\n")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Guard the active re-frame2 authorities against reintroducing the "
            "superseded adapter disposition (frozen Reagent/UIx, deleted "
            "reagent-slim, re-frame.ui as sole/default/only-taught layer)."
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
        "--ci", action="store_true", help="Emit GitHub-Actions ::error:: annotations."
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

    try:
        defects = check(repo_root, verbose=args.verbose, ci=args.ci)
    except SetupError as exc:
        sys.stderr.write(f"error: {exc}\n")
        return 2
    return 0 if defects == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
