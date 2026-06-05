#!/usr/bin/env python3
"""Re-authoring/spec drift gate for skills/re-frame2-pair (rf2-pok18).

The `skills/re-frame2-pair/spec/*` meta-docs and `docs/initial-spec.md` are
the material a future session uses to *re-author* the skill (per
`spec/authoring-prompt.md`). They are NOT loaded during normal skill
operation, so the sibling gates (`check_skill_mcp_drift.py`, the doc-slug
gate) don't read them — which is exactly how they silently fell behind the
shipped contract: a retired `inject-runtime` tool, a "two transport" /
bash-shim-as-fallback model, and a "six restore failure modes" count where
the live Tool-Pair §Time-travel table lists seven. A re-author from stale
material would reintroduce removed tools / stale allow-list guidance into
the AI-facing skill, undoing the MCP-only / default-gated posture.

This gate scans the re-authoring materials for **retired names in a
live-contract framing** and fails when one reappears. It is deliberately
narrow: each rule pairs a forbidden pattern with an `allow` predicate so a
*neutralizing* mention (a "retired" / "gone" / "MUST NOT reintroduce" note,
or an explicit `--allow-writes` gated-tool framing) does NOT trip it. The
point is to catch a re-author that *reintroduces* the stale shape, not to
ban the word.

Scoped files (the re-authoring source tree only):

  - skills/re-frame2-pair/spec/authoring-prompt.md
  - skills/re-frame2-pair/spec/design.md
  - skills/re-frame2-pair/spec/inputs.md
  - skills/re-frame2-pair/docs/initial-spec.md

Rules:

  R1  inject-runtime tool        — `inject-runtime` must only appear in a
                                    retired/gone/MUST-NOT framing; never as a
                                    live MCP tool in an `allowed-tools` /
                                    tool-catalogue context.
  R2  six restore failure modes  — "six … (restore|documented|failure) modes"
                                    is stale; the Tool-Pair §Time-travel table
                                    lists SEVEN. (The "six fire under :rf.epoch/*"
                                    breakdown is explicitly allowed.)
  R3  bash-shim transport        — bash shims framed as a skill-facing /
                                    fallback / back-compat *transport*. The
                                    shims are retired from the skill surface
                                    (harness-only); only that framing is allowed.

Exit code:
    0  no drift
    1  drift detected (printed; `::error::` under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_pair_authoring_drift.py
    python scripts/check_skill_pair_authoring_drift.py --verbose
    python scripts/check_skill_pair_authoring_drift.py --ci          # CI-shaped
    python scripts/check_skill_pair_authoring_drift.py --self-test    # fixtures

rf2-pok18.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent

SCOPED_FILES = (
    REPO_ROOT / "skills" / "re-frame2-pair" / "spec" / "authoring-prompt.md",
    REPO_ROOT / "skills" / "re-frame2-pair" / "spec" / "design.md",
    REPO_ROOT / "skills" / "re-frame2-pair" / "spec" / "inputs.md",
    REPO_ROOT / "skills" / "re-frame2-pair" / "docs" / "initial-spec.md",
)


@dataclass(frozen=True)
class Rule:
    rule_id: str
    # Pattern matched per-line (case-insensitive). A match is a *candidate*
    # finding unless `allow` clears it.
    pattern: re.Pattern[str]
    # Predicate over the matched line (lower-cased): return True to ALLOW the
    # line (a neutralizing / correct-contract framing). Candidate is a finding
    # only when allow() is False.
    allow: Callable[[str], bool]
    message: str


# A retired-framing line is allowed when it explicitly marks the name as gone /
# retired / forbidden, or frames it as the gated-tool / harness-only contract.
_RETIRED_MARKERS = (
    "retired", "gone", "removed", "must not", "no `inject-runtime`",
    "no inject-runtime", "there is no", "is **gone**", "predate the mcp",
    "predates the mcp", "harness only", "harness-only", "for the e2e harness",
    "not a skill-facing", "not reachable", "no shell tool",
)


def _has_marker(line: str) -> bool:
    return any(m in line for m in _RETIRED_MARKERS)


RULES: tuple[Rule, ...] = (
    Rule(
        rule_id="R1-inject-runtime",
        pattern=re.compile(r"inject-runtime", re.IGNORECASE),
        # Allowed only in a retired/gone/MUST-NOT framing.
        allow=_has_marker,
        message=(
            "`inject-runtime` named without a retired/gone framing — there is "
            "no `inject-runtime` MCP tool; the runtime ships via shadow-cljs "
            "`:devtools :preloads`. A re-author MUST NOT reintroduce it."
        ),
    ),
    Rule(
        rule_id="R2-six-failure-modes",
        pattern=re.compile(
            r"\bsix\b[^.\n]*\b(restore|documented|failure)[^.\n]*\bmode",
            re.IGNORECASE,
        ),
        # Allowed: the "six fire under :rf.epoch/* (plus Unknown frame)"
        # breakdown of the SEVEN-row table is legitimate.
        allow=lambda ln: "rf.epoch" in ln or "seven" in ln,
        message=(
            "stale restore-failure-mode count — the Tool-Pair §Time-travel "
            "table lists SEVEN documented failure modes, not six."
        ),
    ),
    Rule(
        rule_id="R3-bash-shim-transport",
        # bash shims framed as a transport / fallback / back-compat surface.
        pattern=re.compile(
            r"(bash[- ]shim|bash shims|two transport|two-transport)",
            re.IGNORECASE,
        ),
        allow=lambda ln: (
            _has_marker(ln)
            # Allowed contract framings:
            or "only transport" in ln          # "MCP is the only transport"
            or "mcp-only" in ln
            or "mcp is the only" in ln
            or "single persistent" in ln
        ),
        message=(
            "bash shims framed as a skill-facing / fallback / back-compat "
            "transport — the MCP server is the ONLY skill transport; the "
            "shims are retired (harness-only)."
        ),
    ),
)


@dataclass
class Finding:
    rule_id: str
    path: str
    lineno: int
    line: str
    message: str

    def render(self) -> str:
        return (
            f"{self.path}:{self.lineno} [{self.rule_id}] {self.message}\n"
            f"    > {self.line.strip()}"
        )


def scan_text(rel_path: str, text: str) -> list[Finding]:
    findings: list[Finding] = []
    for i, raw in enumerate(text.splitlines(), start=1):
        low = raw.lower()
        for rule in RULES:
            if not rule.pattern.search(raw):
                continue
            if rule.allow(low):
                continue
            findings.append(Finding(rule.rule_id, rel_path, i, raw, rule.message))
    return findings


def scan_files(files: Iterable[Path]) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    missing: list[str] = []
    for path in files:
        if not path.exists():
            missing.append(str(path))
            continue
        rel = path.relative_to(REPO_ROOT).as_posix()
        findings.extend(scan_text(rel, path.read_text(encoding="utf-8")))
    return findings, missing


# ---------------------------------------------------------------------------
# Self-test fixtures.
# ---------------------------------------------------------------------------

_SELFTEST_BAD = {
    "inject (R1)": (
        "Frontmatter `allowed-tools` lists `eval-cljs`, `inject-runtime`, `dispatch`.",
        "R1-inject-runtime",
    ),
    "six modes (R2)": (
        "restore-epoch — first-class time-travel with six documented failure modes.",
        "R2-six-failure-modes",
    ),
    "two transports (R3)": (
        "### L3 — Two transports, MCP preferred",
        "R3-bash-shim-transport",
    ),
    "bash-shim fallback (R3)": (
        "The bash-shim transport is a first-class fallback, even though deprecated.",
        "R3-bash-shim-transport",
    ),
}

_SELFTEST_GOOD = (
    # R1: retired framing.
    "(`inject-runtime` is gone — the runtime ships via shadow-cljs `:preloads`.)",
    "There is no `inject-runtime` tool; a re-author MUST NOT reintroduce it.",
    # R2: the seven-row breakdown.
    "Seven in total: six fire under the reserved `:rf.epoch/*` namespace, plus Unknown frame.",
    "first-class time-travel with seven documented failure modes (Tool-Pair §Time-travel).",
    # R3: contract framings.
    "### L3 — MCP is the only skill-facing transport",
    "The bash shims under `scripts/` are retired from the skill's tool surface.",
    "Bash shims — retired from the skill surface; on disk for the e2e harness only.",
)


def run_self_test() -> int:
    ok = True
    for label, (line, want_rule) in _SELFTEST_BAD.items():
        fs = scan_text("fixture.md", line)
        ids = {f.rule_id for f in fs}
        if want_rule not in ids:
            print(f"SELF-TEST FAIL (expected {want_rule}): {label!r}\n    {line}")
            ok = False
    for line in _SELFTEST_GOOD:
        fs = scan_text("fixture.md", line)
        if fs:
            print(
                "SELF-TEST FAIL (expected clean): "
                f"{line!r} -> {[f.rule_id for f in fs]}"
            )
            ok = False
    if ok:
        print("self-test: all fixtures pass.")
        return 0
    return 1


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------


def _is_ci() -> bool:
    return os.environ.get("GITHUB_ACTIONS") == "true"


def main(argv: Iterable[str]) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Fail on retired tool/transport names reappearing in the "
            "re-frame2-pair re-authoring/spec docs (rf2-pok18)."
        ),
    )
    parser.add_argument("--verbose", action="store_true",
                        help="Print the scanned-file summary even when clean.")
    parser.add_argument("--ci", action="store_true",
                        help="Emit GitHub-Actions ::error:: lines (auto-on under GITHUB_ACTIONS).")
    parser.add_argument("--self-test", action="store_true",
                        help="Run built-in fixtures and exit.")
    args = parser.parse_args(list(argv))

    if args.self_test:
        return run_self_test()

    ci = args.ci or _is_ci()
    findings, missing = scan_files(SCOPED_FILES)

    if missing:
        for m in missing:
            msg = f"scoped re-authoring file not found: {m}"
            print(f"::error::{msg}" if ci else f"ERROR: {msg}", file=sys.stderr)
        return 2

    if args.verbose:
        print(f"Scanned {len(SCOPED_FILES)} re-authoring file(s).")

    if findings:
        print(
            f"Re-authoring drift detected ({len(findings)} "
            f"finding{'' if len(findings) == 1 else 's'}):",
            file=sys.stderr,
        )
        for f in findings:
            line = f.render()
            print(f"::error::{line}" if ci else f"ERROR: {line}", file=sys.stderr)
        return 1

    if args.verbose:
        print("No re-authoring drift detected.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
