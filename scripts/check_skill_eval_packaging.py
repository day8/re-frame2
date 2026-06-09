#!/usr/bin/env python3
"""Eval-packaging-consistency gate: docs stance vs package.json `files` (rf2-ngw0wl).

Follow-up from rf2-myge8z (PR #3653), which reconciled
`skills/re-frame2-pair-retro`'s eval-fixture docs to match its package
(`evals/` excluded) and asked for a lightweight gate so that drift cannot
recur unnoticed.

WHY *AGREEMENT*, NOT "documented evals must ship"
-------------------------------------------------
There is NO single repo-wide ship/no-ship policy for `evals/`. Across the
published skills two deliberate stances coexist, BOTH valid:

  - EXCLUDE — `evals/` is a repo-maintenance artifact, deliberately omitted
    from the npm `files` allow-list (a packaged consumer runs the skill, they
    do not re-run its gate suite). Most skills take this stance.
  - INCLUDE — `evals/` is carried in `files` so a vendored copy can re-run its
    own gate from the package. A minority take this stance.

So a naive "documented evals must be in the package" gate would be WRONG — it
would fail every deliberate-EXCLUDE skill. The correct generic invariant is
PACKAGE/DOCS AGREEMENT: for each packaged skill that has an `evals/` dir on
disk, the skill's prose (its `evals/README.md`, or the directory-contents block
in its top-level `README.md`) and its `package.json` `files` array must not
CONTRADICT each other on whether `evals/` ships. This catches the rf2-myge8z
drift class generically without forcing one repo-wide policy.

WHAT THIS GATE CHECKS
---------------------
Per packaged skill (a `skills/<name>/` dir carrying a `package.json`) that has
an `evals/` dir on disk, it derives two facts:

  - PACKAGE FACT — does the `files` array INCLUDE `evals/` (an `evals/` or
    `evals` entry)?
  - DOC STANCE — does the skill's prose declare an EXCLUDE stance
    ('not shipped' / 'excluded from the … files array' / 'omits `evals/`'), an
    INCLUDE stance ('carried in this skill's `package.json` `files`' /
    'shipped slice file'), or stay SILENT (no own-stance prose)?

Then it fails on a CONTRADICTION only:

  (a) `files` INCLUDES `evals/` but the docs say 'not shipped' / 'excluded'.
  (b) `files` OMITS `evals/` but the docs call it a 'shipped slice file' /
      say it is carried in `files`.

SILENCE IS NOT A CONTRADICTION. A skill whose prose says nothing about the
packaging stance passes either way — the gate catches contradictions, not the
absence of a documented stance (a stance-documentation gate is a separate
concern this bead did not ask for).

Doc-stance detection is scoped to the skill's OWN stance. A skill that compares
itself to siblings ("the re-frame2-improver / re-frame2-xray siblings make the
opposite … choice — they carry `evals/` in `files`") must not have that
comparative sentence read as its own INCLUDE stance; the INCLUDE markers are
anchored on first-person / own-package phrasing so the sibling mention never
matches.

The gate is pure-Python-stdlib (no PyYAML / Node), mirroring the sibling
`scripts/check_skill_*.py` gates, so it stays fast and CI-portable. It reads
`package.json` `files` with `json.loads` (the array is plain JSON) and the doc
stance with small, well-commented regexes.

Exit code:
    0  no contradiction
    1  contradiction detected (printed; `::error::` under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_eval_packaging.py
    python scripts/check_skill_eval_packaging.py --verbose
    python scripts/check_skill_eval_packaging.py --ci          # CI-shaped
    python scripts/check_skill_eval_packaging.py --self-test    # built-in fixtures

rf2-ngw0wl.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent
SKILLS_DIR = REPO_ROOT / "skills"

# Force UTF-8 on output streams — skill prose carries → / em-dash etc. and the
# default Windows console codec (cp1252) would crash on them. Mirrors the
# sibling check_skill_eval_docs.py.
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    except (AttributeError, ValueError):  # pragma: no cover
        pass


# ---------------------------------------------------------------------------
# Package-fact extraction.
# ---------------------------------------------------------------------------


def package_includes_evals(package_json_text: str) -> bool:
    """True iff the `files` array carries an `evals/` (or `evals`) entry.

    `files` is plain JSON, so we parse it directly rather than lexing. A
    missing `files` array means "ship everything" by npm semantics — but in
    this repo every skill `package.json` carries an explicit allow-list, so a
    missing array is treated as NOT-including `evals/` (the gate then only
    fires the EXCLUDE-contradiction direction, which is the safe reading; a
    skill that documents an INCLUDE stance must spell `evals/` out in `files`).
    """
    data = json.loads(package_json_text)
    files = data.get("files")
    if not isinstance(files, list):
        return False
    return any(
        isinstance(entry, str) and entry.strip().rstrip("/") == "evals"
        for entry in files
    )


# ---------------------------------------------------------------------------
# Doc-stance detection.
#
# The skill's stance lives in one of two doc surfaces, in priority order:
#   1. `evals/README.md`        — the eval-harness README, when present.
#   2. the skill's `README.md`  — its directory-contents / status block.
# We concatenate both (when present) and scan the union; the markers are
# specific enough that a stance stated in either surface is detected, and the
# union keeps the gate robust to which surface a given skill chose.
#
# EXCLUDE markers — the skill's own prose says `evals/` does NOT ship.
# INCLUDE markers — the skill's own prose says `evals/` DOES ship. Anchored on
#   first-person / own-package phrasing ("carried in this skill's …",
#   "shipped slice file") so a COMPARATIVE sentence about a sibling ("they
#   carry `evals/` in `files`") is not misread as this skill's stance.
# ---------------------------------------------------------------------------

# Each tuple is an OR over acceptable phrasings; ANY alternative present marks
# the stance. Phrasings are matched case-insensitively against the doc text
# with backticks/markdown stripped so `` `evals/` `` and `evals/` both hit.
_EXCLUDE_MARKERS: tuple[str, ...] = (
    "not shipped",
    "not part of the distributable",
    "omits evals",                       # "files allow-list omits `evals/`"
    "excluded from the npm",             # "excluded from the npm files array"
    "deliberately excluded",
    "deliberately not in the published package",
    "intentionally absent from the published package",
    "it is intentionally absent from the published",
)

_INCLUDE_MARKERS: tuple[str, ...] = (
    # re-frame2-improver's own-stance sentence: "It is carried in this skill's
    # `package.json` `files` allow-list so a vendored copy can re-run its own
    # gate". Anchored on "this skill's … files" so the pair-retro comparative
    # "they carry `evals/` in `files`" never matches.
    "carried in this skill's package.json files",
    "carried in this skill's files",
    "shipped slice file",
    "shipped in the package so a vendored copy",
)


def _normalise(text: str) -> str:
    """Lower-case and strip markdown emphasis/code punctuation so prose
    markers match regardless of backticks (`` `evals/` ``) or bold (`**not**`).
    Collapses runs of whitespace so a marker spanning a soft line-wrap still
    matches.
    """
    # Drop backticks and asterisks (code spans + bold/italic emphasis).
    stripped = re.sub(r"[`*]", "", text)
    # Collapse all whitespace (including newlines) to single spaces.
    collapsed = re.sub(r"\s+", " ", stripped)
    return collapsed.lower()


def detect_doc_stance(doc_texts: Iterable[str]) -> str:
    """Return 'exclude' | 'include' | 'silent' for the union of doc surfaces.

    If BOTH an exclude and an include marker are present we treat the stance
    as EXCLUDE: in practice the only doc that mentions both is a skill stating
    its own EXCLUDE stance and then noting siblings make the opposite choice
    (re-frame2-pair-retro). The own-anchored INCLUDE markers should not match
    that sibling sentence, but preferring EXCLUDE when both somehow fire keeps
    the resolution deterministic and matches the observed corpus. (A genuine
    self-contradicting doc — own prose claiming BOTH — is itself a drift the
    skill author should fix; resolving to EXCLUDE here would surface it as a
    contradiction against an INCLUDE `files`, which is the desired signal.)
    """
    blob = _normalise("\n".join(doc_texts))
    has_exclude = any(_normalise(m) in blob for m in _EXCLUDE_MARKERS)
    has_include = any(_normalise(m) in blob for m in _INCLUDE_MARKERS)
    if has_exclude:
        return "exclude"
    if has_include:
        return "include"
    return "silent"


# ---------------------------------------------------------------------------
# Per-skill check.
# ---------------------------------------------------------------------------


@dataclass
class SkillResult:
    skill: str
    files_includes: bool
    stance: str               # 'exclude' | 'include' | 'silent'
    doc_surfaces: tuple[str, ...]  # rel paths of the doc surfaces scanned
    contradiction: str | None  # None == OK; else a human-readable message


def _doc_surfaces(skill_dir: Path) -> list[Path]:
    """Doc surfaces to scan for the stance, in priority order. Both are
    optional; the union of whichever exist is scanned."""
    surfaces: list[Path] = []
    evals_readme = skill_dir / "evals" / "README.md"
    if evals_readme.is_file():
        surfaces.append(evals_readme)
    skill_readme = skill_dir / "README.md"
    if skill_readme.is_file():
        surfaces.append(skill_readme)
    return surfaces


def _rel(p: Path) -> str:
    """Repo-relative POSIX path for display, tolerant of paths outside the
    repo (e.g. the self-test's tempdir fixtures) — falls back to the name."""
    try:
        return str(p.relative_to(REPO_ROOT)).replace("\\", "/")
    except ValueError:
        return p.name


def check_skill(skill_dir: Path) -> SkillResult | None:
    """Check one skill. Returns None for a skill that is out of scope
    (no package.json, or no `evals/` dir on disk).
    """
    package_json = skill_dir / "package.json"
    evals_dir = skill_dir / "evals"
    if not package_json.is_file() or not evals_dir.is_dir():
        return None

    files_includes = package_includes_evals(
        package_json.read_text(encoding="utf-8")
    )

    surfaces = _doc_surfaces(skill_dir)
    stance = detect_doc_stance(p.read_text(encoding="utf-8") for p in surfaces)

    contradiction: str | None = None
    if files_includes and stance == "exclude":
        contradiction = (
            "package.json `files` INCLUDES `evals/` but the skill's docs "
            "declare an EXCLUDE stance ('not shipped' / 'excluded'). Either "
            "drop `evals/` from `files`, or update the docs to state the "
            "INCLUDE stance."
        )
    elif (not files_includes) and stance == "include":
        contradiction = (
            "package.json `files` OMITS `evals/` but the skill's docs call it "
            "a 'shipped slice file' / say it is carried in `files`. Either add "
            "`evals/` to `files`, or update the docs to state the EXCLUDE "
            "stance."
        )

    return SkillResult(
        skill=skill_dir.name,
        files_includes=files_includes,
        stance=stance,
        doc_surfaces=tuple(_rel(p) for p in surfaces),
        contradiction=contradiction,
    )


def check_all(skills_dir: Path) -> list[SkillResult]:
    """Check every packaged skill with an `evals/` dir, sorted by name."""
    results: list[SkillResult] = []
    for skill_dir in sorted(p for p in skills_dir.iterdir() if p.is_dir()):
        r = check_skill(skill_dir)
        if r is not None:
            results.append(r)
    return results


# ---------------------------------------------------------------------------
# Self-test — synthetic fixtures exercising both agree cases and both
# contradiction directions, plus the sibling-mention guard.
# ---------------------------------------------------------------------------


def _run_self_test() -> int:
    import tempfile

    failures: list[str] = []

    def make_skill(
        td: Path,
        files_includes: bool,
        evals_readme: str | None,
        skill_readme: str | None = None,
    ) -> Path:
        """Build a synthetic skill dir with an `evals/` dir + a package.json."""
        sd = td
        (sd / "evals").mkdir(parents=True, exist_ok=True)
        # Always give it an evals.json so the dir is non-empty (mirrors reality).
        (sd / "evals" / "evals.json").write_text("{}", encoding="utf-8")
        files = ["SKILL.md", "README.md", "LICENSE", "references/"]
        if files_includes:
            files.append("evals/")
        files.append(".claude-plugin/")
        (sd / "package.json").write_text(
            json.dumps({"name": "@day8/fixture", "files": files}),
            encoding="utf-8",
        )
        if evals_readme is not None:
            (sd / "evals" / "README.md").write_text(evals_readme, encoding="utf-8")
        if skill_readme is not None:
            (sd / "README.md").write_text(skill_readme, encoding="utf-8")
        return sd

    # Marker prose fragments mirroring the real corpus.
    EXCLUDE_PROSE = (
        "## Repo-maintenance artifact, not shipped\n\n"
        "`evals/` is a **repo-maintenance artifact** — it is deliberately "
        "**not** part of the distributable skill package. The `files` "
        "allow-list omits `evals/` on purpose.\n"
    )
    INCLUDE_PROSE = (
        "## Repo-maintenance artifact\n\n"
        "`evals/` is a **repo-maintenance artifact**. It is carried in this "
        "skill's `package.json` `files` allow-list so a vendored copy can "
        "re-run its own gate.\n"
    )
    # Pair-retro shape: own EXCLUDE stance + a COMPARATIVE sentence about
    # siblings that carry evals. Must resolve to EXCLUDE, not INCLUDE.
    EXCLUDE_WITH_SIBLING_MENTION = (
        EXCLUDE_PROSE
        + "\n(The re-frame2-improver and re-frame2-xray siblings make the "
        "opposite, equally valid, choice — they carry `evals/` in `files` so "
        "a vendored copy can re-run its own gate; either stance is fine as "
        "long as the skill's docs and its `files` array agree.)\n"
    )
    SILENT_PROSE = (
        "## Coverage\n\nThis directory holds the eval fixtures. Eight evals, "
        "covering the three dimensions.\n"
    )

    cases: list[tuple[str, bool, str | None, str | None, bool]] = [
        # (label, files_includes, evals_readme, skill_readme, want_ok)
        ("agree-exclude", False, EXCLUDE_PROSE, None, True),
        ("agree-include", True, INCLUDE_PROSE, None, True),
        # Contradiction each way.
        ("contradiction-files-include-docs-exclude", True, EXCLUDE_PROSE, None, False),
        ("contradiction-files-omit-docs-include", False, INCLUDE_PROSE, None, False),
        # Silence is not a contradiction — passes either way.
        ("silent-files-omit", False, SILENT_PROSE, None, True),
        ("silent-files-include", True, SILENT_PROSE, None, True),
        # No doc surface at all (no evals/README, skill README silent) — silent.
        ("no-doc-surface-files-omit", False, None, "# Skill\n\nA skill.\n", True),
        # Sibling-mention guard: own EXCLUDE + sibling INCLUDE mention, files
        # OMIT — must resolve EXCLUDE (agree), NOT fire the include-direction.
        ("sibling-mention-resolves-exclude", False, EXCLUDE_WITH_SIBLING_MENTION, None, True),
        # And the same prose with files INCLUDING evals MUST fire (the own
        # EXCLUDE stance contradicts an include `files`).
        ("sibling-mention-exclude-vs-include-files", True, EXCLUDE_WITH_SIBLING_MENTION, None, False),
        # Stance stated in the SKILL README rather than evals/README (mirrors
        # re-frame2-setup: "excluded from the npm `files` array by design").
        ("stance-in-skill-readme-exclude", False, None,
         "## Layout\n\n`evals/` holds the trigger-accuracy fixture; it is "
         "excluded from the npm `files` array by design.\n", True),
        ("stance-in-skill-readme-exclude-vs-include-files", True, None,
         "## Layout\n\n`evals/` holds the trigger-accuracy fixture; it is "
         "excluded from the npm `files` array by design.\n", False),
    ]

    for label, fi, er, sr, want_ok in cases:
        with tempfile.TemporaryDirectory() as td:
            sd = make_skill(Path(td) / "skill", fi, er, sr)
            r = check_skill(sd)
            if r is None:
                failures.append(
                    f"{label}: check_skill returned None (fixture not in scope)"
                )
                continue
            is_ok = r.contradiction is None
            if is_ok != want_ok:
                failures.append(
                    f"{label}: expected {'OK' if want_ok else 'CONTRADICTION'}, "
                    f"got {'OK' if is_ok else r.contradiction!r} "
                    f"(files_includes={r.files_includes}, stance={r.stance})"
                )

    # Out-of-scope guard: a skill with NO evals/ dir is skipped (returns None).
    with tempfile.TemporaryDirectory() as td:
        sd = Path(td) / "no-evals"
        sd.mkdir()
        (sd / "package.json").write_text(
            json.dumps({"name": "@day8/no-evals", "files": ["SKILL.md"]}),
            encoding="utf-8",
        )
        if check_skill(sd) is not None:
            failures.append(
                "out-of-scope: a skill with no evals/ dir should be skipped "
                "(check_skill must return None) but it was checked."
            )

    if failures:
        for f in failures:
            print(f"SELF-TEST FAIL: {f}", file=sys.stderr)
        print(f"\n{len(failures)} self-test failure(s).", file=sys.stderr)
        return 1
    print(
        "self-test: all fixtures pass "
        "(agree-include + agree-exclude stay green; both contradiction "
        "directions fire; silence + no-doc-surface stay green; the "
        "sibling-mention guard resolves to the skill's own EXCLUDE stance; "
        "stance-in-skill-README is detected; out-of-scope skills are skipped)."
    )
    return 0


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------


def _is_ci() -> bool:
    return os.environ.get("GITHUB_ACTIONS") == "true"


def _emit_error(msg: str, ci: bool) -> None:
    if ci:
        print(f"::error::{msg}", file=sys.stderr)
    else:
        print(f"ERROR: {msg}", file=sys.stderr)


def main(argv: Iterable[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Verify each packaged skill's eval-packaging stance (its "
            "evals/README.md or README.md prose) agrees with its package.json "
            "`files` array on whether evals/ ships (rf2-ngw0wl)."
        ),
    )
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="Print the per-skill stance summary even when clean.")
    parser.add_argument("--ci", action="store_true",
                        help="Emit GitHub-Actions ::error:: lines (auto-on under GITHUB_ACTIONS).")
    parser.add_argument("--self-test", action="store_true",
                        help="Run built-in fixtures (both agree cases + both "
                             "contradiction directions + the sibling-mention guard) and exit.")
    args = parser.parse_args(list(argv))

    if args.self_test:
        return _run_self_test()

    ci = args.ci or _is_ci()

    if not SKILLS_DIR.is_dir():
        _emit_error(f"skills dir not found: {SKILLS_DIR}", ci)
        return 2

    try:
        results = check_all(SKILLS_DIR)
    except (ValueError, json.JSONDecodeError, OSError) as e:
        _emit_error(f"failed to read skill packaging data: {e}", ci)
        return 2

    if args.verbose:
        for r in results:
            pkg = "files:INCLUDE" if r.files_includes else "files:OMIT"
            surfaces = ", ".join(r.doc_surfaces) if r.doc_surfaces else "(none)"
            print(
                f"{r.skill}: {pkg}, docs:{r.stance.upper()} "
                f"[{surfaces}] -- {'DRIFT' if r.contradiction else 'OK'}"
            )

    contradictions = [r for r in results if r.contradiction]
    if contradictions:
        print(
            f"\nEval-packaging drift detected "
            f"({len(contradictions)} "
            f"finding{'' if len(contradictions) == 1 else 's'}):",
            file=sys.stderr,
        )
        for r in contradictions:
            _emit_error(f"skills/{r.skill}: {r.contradiction}", ci)
        print(
            "\nThe invariant is PACKAGE/DOCS AGREEMENT — both INCLUDE and "
            "EXCLUDE are valid as long as the skill's docs (evals/README.md or "
            "the README directory-contents block) and its package.json `files` "
            "array agree. (rf2-ngw0wl)",
            file=sys.stderr,
        )
        return 1

    if args.verbose:
        print(
            f"\nNo eval-packaging drift detected across {len(results)} "
            f"packaged skill(s) with an evals/ dir."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
