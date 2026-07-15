#!/usr/bin/env python3
"""Narrow code/spec/doc drift gate for re-frame.ui Root settlement (rf2-vxgfnd.291).

This is deliberately not a Markdown or Clojure parser. It protects a small set
of literal contract teeth whose accidental removal previously left runtime,
Specs 004C/006/009, API docs, and the active guide telling different lifecycle
stories. Each tooth has a mutation self-test: delete/rename that exact anchor
and the gate must turn red.

Usage:
    python scripts/check_ui_root_lifecycle_drift.py
    python scripts/check_ui_root_lifecycle_drift.py --self-test
    python scripts/check_ui_root_lifecycle_drift.py --ci
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Tooth:
    label: str
    path: str
    anchor: str


TEETH = (
    Tooth(
        "runtime-mark-before-cleanup",
        "implementation/ui/src/re_frame/ui/client.cljs",
        "(mark-tearing-down! root-id root)\n  (try\n    (reactive/teardown-root!",
    ),
    Tooth(
        "runtime-fifo-release",
        "implementation/ui/src/re_frame/ui/client.cljs",
        "(queue-microtask! #(release-root! root-id root))",
    ),
    Tooth(
        "runtime-secondary-cleanup-evidence",
        "implementation/ui/src/re_frame/ui/client.cljs",
        'primary "rfUiRollbackCleanupError"',
    ),
    Tooth(
        "runtime-platform-capability",
        "implementation/ui/src/re_frame/ui/reactive.cljc",
        ":rf.error/ui-platform-incompatible where",
    ),
    Tooth(
        "runtime-capability-evidence",
        "implementation/ui/src/re_frame/ui/reactive.cljc",
        ":platform :javascript\n                             :capability :js/WeakRef",
    ),
    Tooth(
        "spec-004c-three-states",
        "spec/004C-Roots-and-Mount.md",
        "`:live → :tearing-down → :released`",
    ),
    Tooth(
        "spec-004c-rollback-secondary",
        "spec/004C-Roots-and-Mount.md",
        "`rfUiRollbackCleanupError`",
    ),
    Tooth(
        "spec-006-failed-first-transaction",
        "spec/006-ReactiveSubstrate.md",
        "#### Failed-first-mount rollback is one teardown transaction",
    ),
    Tooth(
        "spec-006-tearing-evidence",
        "spec/006-ReactiveSubstrate.md",
        "`:existing {:tearing-down? true}`",
    ),
    Tooth(
        "spec-006-platform-boundary",
        "spec/006-ReactiveSubstrate.md",
        "### JavaScript host capability boundary",
    ),
    Tooth(
        "spec-009-platform-id",
        "spec/009-Instrumentation.md",
        "| `:rf.error/ui-platform-incompatible` | `:error` | diagnostic |",
    ),
    Tooth(
        "spec-009-duplicate-id",
        "spec/009-Instrumentation.md",
        "| `:rf.error/duplicate-root-id` | `:error` | diagnostic |",
    ),
    Tooth(
        "spec-009-container-id",
        "spec/009-Instrumentation.md",
        "| `:rf.error/root-container-in-use` | `:error` | diagnostic |",
    ),
    Tooth(
        "spec-009-not-live-id",
        "spec/009-Instrumentation.md",
        "| `:rf.error/root-not-live` | `:error` | diagnostic |",
    ),
    Tooth(
        "api-settlement-lifecycle",
        "docs/api/re-frame.ui.md",
        "`:live → :tearing-down → :released`",
    ),
    Tooth(
        "guide-required-weakref",
        "ai/findings/new-substrate-synthesis/guide/01-getting-started.md",
        "standard JavaScript `WeakRef` constructor",
    ),
)


def load_texts(root: Path) -> dict[str, str]:
    texts: dict[str, str] = {}
    for path in sorted({tooth.path for tooth in TEETH}):
        target = root / path
        if not target.is_file():
            raise FileNotFoundError(f"required lifecycle surface missing: {path}")
        texts[path] = target.read_text(encoding="utf-8")
    return texts


def missing_teeth(texts: dict[str, str]) -> list[Tooth]:
    return [tooth for tooth in TEETH if tooth.anchor not in texts[tooth.path]]


def report(problems: list[Tooth], ci: bool) -> None:
    for tooth in problems:
        message = (
            f"UI Root lifecycle drift: {tooth.label} anchor is absent from "
            f"{tooth.path}"
        )
        if ci:
            print(f"::error file={tooth.path}::{message}", file=sys.stderr)
        else:
            print(message, file=sys.stderr)


def self_test(texts: dict[str, str]) -> bool:
    baseline = missing_teeth(texts)
    if baseline:
        report(baseline, False)
        print("self-test setup failed: live baseline already drifted", file=sys.stderr)
        return False

    failures: list[str] = []
    for tooth in TEETH:
        mutated = dict(texts)
        mutated[tooth.path] = mutated[tooth.path].replace(
            tooth.anchor, f"[mutated:{tooth.label}]"
        )
        labels = {problem.label for problem in missing_teeth(mutated)}
        if tooth.label not in labels:
            failures.append(tooth.label)

    if failures:
        for label in failures:
            print(f"mutation stayed green: {label}", file=sys.stderr)
        return False

    print(
        f"UI Root lifecycle drift self-test PASS ({len(TEETH)} mutation teeth)",
        file=sys.stderr,
    )
    return True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--ci", action="store_true")
    args = parser.parse_args()

    try:
        texts = load_texts(REPO_ROOT)
    except (OSError, UnicodeError) as error:
        print(f"UI Root lifecycle drift setup error: {error}", file=sys.stderr)
        return 2

    if args.self_test:
        return 0 if self_test(texts) else 1

    problems = missing_teeth(texts)
    if problems:
        report(problems, args.ci)
        return 1

    print(f"UI Root lifecycle contract clean ({len(TEETH)} anchors).", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
