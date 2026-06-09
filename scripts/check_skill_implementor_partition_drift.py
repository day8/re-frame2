#!/usr/bin/env python3
"""Partition-aware + HTTP-fx + API-name drift guard for the re-frame2-implementor
skill (rf2-whsb0c + rf2-6c59ob).

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
    summaries implied all seven marking sites accept
    `{:sensitive [paths] :large [paths]}` — contradicting the `reg-machine`
    schema-first exception (no top-level `:sensitive` / `:large` keys).

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


def run(*, verbose: bool, ci: bool) -> int:
    if not SKILL_DIR.is_dir():
        sys.stderr.write(
            f"error: re-frame2-implementor skill not found at {SKILL_DIR}\n"
        )
        return 2

    problems, lines_checked = find_drift(SCANNED_FILES)

    if verbose:
        print(
            f"implementor partition/HTTP/API-name guard: scanned "
            f"{len(SCANNED_FILES)} files ({lines_checked} lines)."
        )

    if not problems:
        if verbose:
            print(
                "partition-drift: no deregister-listener!, bare-:http-managed, "
                "public-dispose-adapter!, or reg-machine-marks drift found."
            )
        return 0

    err_prefix = "::error::" if ci else ""
    for p in problems:
        print(f"{err_prefix}{p}")
    print(
        f"\npartition-drift: {len(problems)} drift issue(s) — the implementor "
        "skill is a control surface; align it to shipped EP-0001 partition / "
        "Spec 014 managed-HTTP / public-API reality."
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
