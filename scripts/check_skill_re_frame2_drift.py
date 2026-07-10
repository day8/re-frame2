#!/usr/bin/env python3
"""No-bead-id + verification-posture + launcher-canonical drift guard for the
re-frame2 (authoring) skill.

`spec/design.md` is the single normative source for the skill's locked
decisions (L1–L11), file structure, cardinal rules, and verification posture;
`spec/inputs.md` owns the canonical inputs and update procedure. The
`spec/authoring-prompt.md` launcher orchestrates a reauthoring pass by
*pointing at* those two files — it must not carry a second, drift-prone copy of
the tree, the rules, or the locks. This guard protects three regressions the
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
        for lineno, line in enumerate(_slurp(path).splitlines(), start=1):
            lines_checked += 1
            rel = path.relative_to(REPO_ROOT)
            for label in beadid_problems(line) + posture_problems(line):
                problems.append(f"{rel}:{lineno}: {label}\n    {line.strip()}")

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
            f"re-frame2 no-bead-id + verify-posture + launcher-canonical guard: "
            f"scanned {len(files)} user-facing leaves ({lines_checked} lines) "
            "plus the spec/authoring-prompt.md launcher."
        )

    if not problems:
        if verbose:
            print(
                "re-frame2-drift: no bead-id leaks, no agent-run verification-"
                "posture drift, and the launcher points at design.md + inputs.md "
                "without regrowing the tree / locks."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\nre-frame2-drift: {len(problems)} drift issue(s) — keep internal "
        "bead ids out of the user-facing leaves (L10), keep the "
        "authoring-only verification posture (Q14 / L3: the author runs the "
        "gates, the skill names them), and keep the launcher pointing at the "
        "canonical design.md + inputs.md instead of re-holding the tree / locks."
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
