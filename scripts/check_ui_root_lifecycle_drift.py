#!/usr/bin/env python3
"""Narrow code/spec/doc drift gate for re-frame.ui Root settlement (rf2-vxgfnd.291)
and root-attempt evidence (rf2-vxgfnd.249).

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
    # Row-scoped `:tearing-down? true` evidence teeth (rf2-vizyct). The row-ID
    # teeth above pin only that the diagnostic EXISTS; they leave the conditional
    # `:tearing-down? true` ex-data evidence — the exact settlement contract
    # .277/.291 required this gate to protect — unpinned, so an edit deleting the
    # `:tearing-down? true` shape from any/all three rows stayed green. Each
    # anchor is a row-UNIQUE literal that CONTAINS the evidence, so removing it
    # from one row (or all rows) turns exactly that row's tooth red and cannot
    # slip past a surviving generic anchor elsewhere. These are the CONTRACT
    # side; the IMPLEMENTATION side is pinned by the CLJS test
    # `tearing-down-root-diagnostics-carry-complete-ownership-evidence`
    # (re-frame.ui.root-teardown-wiring-cljs-test), which asserts each
    # diagnostic emits `:existing {… :tearing-down? true}`. Strip the evidence
    # from either the spec rows or the runtime emitters and a gate turns red.
    Tooth(
        "spec-009-duplicate-tearing-evidence",
        "spec/009-Instrumentation.md",
        "optional `:tearing-down? true`) + `:arriving` (client)",
    ),
    Tooth(
        "spec-009-container-tearing-evidence",
        "spec/009-Instrumentation.md",
        "`:owner-root-id`, optional `:existing {:tearing-down? true}`",
    ),
    # NOTE (rf2-mb8yp): this anchor carried a trailing `` , `:recovery` `` until
    # the corpus-wide strike removed that slot from all 134 `:tags` cells (it is
    # envelope-level — `build-event` hoists it — so no cell rosters it now). The
    # suffix was only ever the row-UNIQUENESS half of the anchor, never the
    # evidence half; the shortened literal still occurs exactly once in 009
    # (`:rf.error/root-container-in-use` reads `` `:root-id`, `:owner-root-id`,
    # optional … ``, so the comma breaks the match) and still CONTAINS the
    # `:tearing-down? true` evidence this tooth exists to pin.
    Tooth(
        "spec-009-not-live-tearing-evidence",
        "spec/009-Instrumentation.md",
        "`:root-id`, optional `:existing {:tearing-down? true}`",
    ),
    Tooth(
        "api-settlement-lifecycle",
        "docs/api/re-frame.ui.md",
        "`:live → :tearing-down → :released`",
    ),
    # rf2-sddbc / rf2-fjti6 — consumed-container fail-close teeth. A throwing host
    # `.unmount` may have QUEUED late DOM work before it threw, so an adapter reclaim
    # clearing the node is a SNAPSHOT, never proof the surface settled: the EXACT node
    # is recorded fail-closed and terminally denied while only the id/prefix free for a
    # fresh-container re-mount. These pin (1) the runtime mark-consumed-BEFORE-release
    # ordering, (2) the `:rf.error/root-container-consumed` fresh-node recovery contract,
    # and (3) the causally-late browser fixture that forces the queued mutation to run
    # AFTER the successor commits (the earlier FIFO-microtask proof could not). Each
    # anchor is a site-unique literal; its mutation self-test turns exactly its tooth red.
    Tooth(
        "runtime-consumed-mark-before-release",
        "implementation/ui/src/re_frame/ui/client.cljs",
        "(mark-container-consumed! (.-container root))\n  (release-root! root-id root)",
    ),
    Tooth(
        "spec-009-consumed-fresh-node-recovery",
        "spec/009-Instrumentation.md",
        "the same `:root-id` re-mounts onto a fresh node once the poisoned claim is reclaimed",
    ),
    Tooth(
        "consumed-causal-late-fixture",
        "implementation/ui/test/re_frame/ui/adapter_public_root_disposal_dom_cljs_test.cljs",
        "the queued predecessor mutation ran AFTER the successor committed",
    ),
    # rf2-vxgfnd.249 — root-ATTEMPT evidence teeth. The settlement teeth above pin
    # the Root claim lifecycle; they leave the frame-plan ATTEMPT vocabulary
    # (`:committed` / `:mount-incomplete` / `:preflight-attempt-failed`, the stripped
    # internal `:rev`, and the lifecycle-loss `:kind` roster) unpinned — which is
    # exactly how Spec 004C came to call the conflict payload the install record
    # "verbatim" while the runtime stripped `:rev`, how Spec 009 published a
    # TWO-kind roster against a shipped THREE-kind runtime, and how the runtime's
    # `:scope-config-less-or-own-the-lifetime` recovery reached the catalogue in
    # neither arm. The error-catalogue conformance parser reads catalogue columns
    # 1-3 only, so no existing gate covers the Recovery or Data cells at all.
    # Each anchor below is a file-unique literal on one layer of the closed
    # vocabulary, so drift in ONE layer turns exactly that layer's tooth red.
    Tooth(
        "runtime-abort-provenance-mapping",
        "implementation/ui/src/re_frame/ui/frames.cljc",
        ":fresh (assoc-in m [frame-id :mount-incomplete] true)",
    ),
    Tooth(
        "runtime-projection-strips-rev",
        "implementation/ui/src/re_frame/ui/frames.cljc",
        "(some-> (installed-record frame-id) (dissoc :rev))",
    ),
    Tooth(
        "runtime-boot-authority-recovery",
        "implementation/ui/src/re_frame/ui/frames.cljc",
        ":recovery :scope-config-less-or-own-the-lifetime",
    ),
    Tooth(
        "runtime-found-live-authority-lost",
        "implementation/ui/src/re_frame/ui/frames.cljc",
        "root-id frame-id :found-live-authority-lost",
    ),
    Tooth(
        "spec-004c-attempt-evidence-section",
        "spec/004C-Roots-and-Mount.md",
        "### 7.1 Root-attempt evidence — authority, committed scope, and settlement",
    ),
    Tooth(
        "spec-004c-installed-is-projection",
        "spec/004C-Roots-and-Mount.md",
        "the **external projection** of the recorded install/adopt record",
    ),
    Tooth(
        "spec-004c-published-vs-internal",
        "spec/004C-Roots-and-Mount.md",
        "`:committed`, `:mount-incomplete`, `:preflight-attempt-failed`. Internal, never",
    ),
    Tooth(
        "spec-009-installed-is-projection",
        "spec/009-Instrumentation.md",
        "the EXTERNAL PROJECTION of the install/adopt record, never the record itself",
    ),
    Tooth(
        "spec-009-boot-authority-recovery",
        "spec/009-Instrumentation.md",
        ":scope-config-less-or-own-the-lifetime`",
    ),
    Tooth(
        "spec-009-lifecycle-loss-three-kinds",
        "spec/009-Instrumentation.md",
        ":ensured-frame-lost` / `:refresh-target-replaced` / `:found-live-authority-lost`",
    ),
    Tooth(
        "spec-009-every-rejection-fail-closed",
        "spec/009-Instrumentation.md",
        "EVERY publication rejection is fail-closed under this id",
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
