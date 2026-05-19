#!/usr/bin/env python3
"""Drift smoke-test: skill `allowed-tools` vs MCP server tool catalogue (rf2-flzdp + rf2-yiccf).

Two axes of cross-check.

**MCP axis** (rf2-flzdp): every (mcp-server, consumer-skill) pair declared
in `MAPPINGS` below. For each pair, builds two sets:

  - SERVER tool names — extracted from the MCP server's source (the Clojure
    `tool-descriptors` / `tool-registry` literal where each tool is a
    `:name "..."` map entry).
  - SKILL tool names — extracted from the consumer skill's YAML front-matter
    `allowed-tools:` block, filtered to `mcp__<prefix>__*` entries.

Then reports two drift directions:

  - MISSING-IN-SKILL — server exposes the tool but skill doesn't allow-list it.
    Always a failure unless the tool is in `:intentional_server_only` for that
    mapping. The agent host's permission gate blocks the tool either way; this
    drift means the skill silently can't reach it.

  - MISSING-IN-SERVER — skill allow-lists a tool the server doesn't expose
    (any more, or yet). Always a failure — the skill is referencing a phantom.

**Bash axis** (rf2-yiccf): every rule in `BASH_RULES` is a small contract
between a SKILL.md body pattern and a required `Bash(...)` allow-list
entry. When the body matches the pattern, the allow-list must carry an
entry matching the required shape (with `*` as a wildcard). Catches the
class of silent-breakage where a skill body instructs the agent to run a
command the allow-list doesn't permit (e.g. rf2-scpaa: re-frame-migration
cardinal rule 7 instructs the agent to file GitHub issues but lacks
`Bash(gh issue *)`).

  - MISSING-BASH-ALLOW — body fires the rule's pattern but no allow-list
    entry matches the required shape.

Causa-MCP is currently spec-only (no `src/`); the script skips its entry
gracefully rather than failing on missing files.

Exit code:
    0  no drift detected
    1  drift detected (printed line-by-line, GitHub-Actions ::error:: style
       when run under CI)
    2  invocation / setup error

Usage:
    python scripts/check_skill_mcp_drift.py
    python scripts/check_skill_mcp_drift.py --verbose
    python scripts/check_skill_mcp_drift.py --ci             # tighter output
                                                             #   (auto on under
                                                             #   GITHUB_ACTIONS)
    python scripts/check_skill_mcp_drift.py --show-baseline  # print current
                                                             #   shipped baseline
    python scripts/check_skill_mcp_drift.py --no-baseline    # fail on every
                                                             #   drift, current
                                                             #   or new

The `_BASELINE` set lets us land this gate before rf2-aks1t completes -- the
script remembers the current drift and only fails on new drift introduced on
top of it. As beads close pre-existing drift, trim `_BASELINE` accordingly.

rf2-flzdp.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parent.parent

# ---------------------------------------------------------------------------
# Configuration.
#
# `mappings` declares the (mcp-server source file, MCP-host prefix, consumer
# skill path) triples that we cross-check. The MCP-host prefix is the
# `mcp__<prefix>__<tool-name>` string the agent host generates when the
# server is named in the host's mcp.json — it is conventionally the server's
# advertised name minus a `-mcp` suffix (re-frame2-pair-mcp → re-frame2-pair).
#
# `intentional_server_only` is the escape hatch — names listed there are
# exposed by the server but intentionally not consumed by the skill (e.g. a
# write-surface tool gated for non-skill callers). The script ignores the
# MISSING-IN-SKILL direction for those names; the MISSING-IN-SERVER direction
# still fires.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Mapping:
    name: str
    # Tuple of source paths to lex for `{:name "..."}` descriptor
    # literals. Multiple paths are concatenated set-wise — this lets a
    # mapping target an MCP server whose tool catalogue is split across
    # per-category leaves (e.g. story-mcp post rf2-3ukix). All paths
    # must exist (unless `optional=True`).
    server_src: tuple[Path, ...]
    host_prefix: str
    skill_md: Path
    intentional_server_only: frozenset[str] = field(default_factory=frozenset)
    optional: bool = False  # True ⇒ missing server_src is a skip, not an error.


# Story-mcp tool catalogue lives across five per-category leaves post
# rf2-3ukix — dev/docs/testing/write each carry a `descriptors` vector,
# recorder carries a singleton `descriptor` map (split out for
# leaf-size reasons, rf2-zkca8). The parent `tools.cljc` was removed in
# the same refactor; assembly happens in `tools/registry.cljc` which
# only re-exports symbols (no literals to lex). Enumerate the leaves
# directly.
_STORY_MCP_LEAVES = tuple(
    REPO_ROOT / "tools" / "story-mcp" / "src" / "re_frame" / "story_mcp" / "tools" / f"{leaf}.cljc"
    for leaf in ("dev", "docs", "testing", "write", "recorder")
)


MAPPINGS: list[Mapping] = [
    Mapping(
        name="re-frame2-pair-mcp <-> re-frame2-pair",
        # Post rf2-47g8l the eleven-tool catalogue data lives in a dedicated
        # `descriptors_data.cljs` leaf — the sibling `descriptors.cljs` is
        # now a slim splicer/façade with no `{:name "..."}` literals. Point
        # the gate at the data file.
        server_src=(REPO_ROOT / "tools" / "re-frame2-pair-mcp" / "src" / "re_frame2_pair_mcp" / "tools" / "descriptors_data.cljs",),
        host_prefix="re-frame2-pair",
        skill_md=REPO_ROOT / "skills" / "re-frame2-pair" / "SKILL.md",
    ),
    # story-mcp consumers (rf2-1v7tu HYBRID): both skills consume the
    # 17-tool surface, split along the authoring vs live-runtime axis.
    # - re-frame2 (authoring) owns: get-story-instructions, list-*, get-*,
    #   variant->edn, preview-variant, register-variant, unregister-variant.
    # - re-frame2-pair (live-session) owns: run-variant, read-failures,
    #   snapshot-identity, run-a11y, record-as-variant.
    # Each mapping marks the OTHER skill's tools as `intentional_server_only`
    # so the gate only fires when the canonical owner forgets a tool.
    # The host prefix is `re-frame2-story-mcp` per both skills' allowed-tools
    # entries (the MCP server's advertised name).
    Mapping(
        name="story-mcp <-> re-frame2",
        server_src=_STORY_MCP_LEAVES,
        host_prefix="re-frame2-story-mcp",
        skill_md=REPO_ROOT / "skills" / "re-frame2" / "SKILL.md",
        intentional_server_only=frozenset({
            # Live-session tools — owned by re-frame2-pair.
            "run-variant",
            "read-failures",
            "snapshot-identity",
            "run-a11y",
            "record-as-variant",
        }),
    ),
    Mapping(
        name="story-mcp <-> re-frame2-pair",
        server_src=_STORY_MCP_LEAVES,
        host_prefix="re-frame2-story-mcp",
        skill_md=REPO_ROOT / "skills" / "re-frame2-pair" / "SKILL.md",
        intentional_server_only=frozenset({
            # Authoring tools — owned by re-frame2.
            "get-story-instructions",
            "list-stories",
            "get-story",
            "get-variant",
            "variant->edn",
            "list-tags",
            "list-modes",
            "list-assertions",
            "list-substrates",
            "preview-variant",
            "register-variant",
            "unregister-variant",
        }),
    ),
]

# Pre-existing drift the gate accepts as the shipped baseline. Entries are
# `(mapping-name, direction, tool-name)`. `direction` is one of
# "missing-in-skill" / "missing-in-server". As follow-up beads fix each
# entry, drop it from this list — the gate then catches any regression.
#
# Entries are keyed by mapping-name (not host_prefix) so renames in MAPPINGS
# above stay self-consistent.
_BASELINE: set[tuple[str, str, str]] = {
    # rf2-scpaa — landing as part of the cluster PR that introduces this
    # gate (rf2-yiccf). Baselined so commit 2 of the cluster passes; commit
    # 6 trims this entry as it adds the missing allow-list line. After
    # commit 6 lands the gate will fail if anyone re-removes the grant.
    (
        "bash:skills/re-frame-migration/SKILL.md",
        "missing-bash-allow",
        "gh issue *",
    ),
}


# ---------------------------------------------------------------------------
# Server-side extraction.
#
# Tool descriptors in both re-frame2-pair-mcp (.cljs) and story-mcp (.cljc) sit
# inside a top-level `def` of a vector-of-maps literal. Each tool's name
# appears as `{:name "<dash-separated>"`. We don't try to evaluate Clojure
# — we lex out the `:name "..."` pairs from the file. False positives are
# rare because `:name` is a strong convention and the tools.cljs/cljc files
# don't use `:name` for anything else.
#
# An alternative approach is to spawn `clojure -X` and have it print the
# tool-descriptor names. We avoid that because:
#   - it would force the CI step to install + warm a Clojure CLI cache for
#     a 200ms parse,
#   - the regex shape is robust against the way these files are actually
#     written, AND
#   - the per-tool descriptor blocks are very disciplined (Conventions.md
#     mandates the `{:name <string> :description ... :inputSchema ...}` map
#     shape for every MCP tool surface).
# ---------------------------------------------------------------------------

# Tool-name character class: dash-cased identifiers, plus `>` and `*` and
# `?` and `!` so we cover the Clojure-flavoured shapes (`variant->edn`,
# `epochs-since!`, `state-is?`). The MCP names are technically free-form
# strings, but in practice every tool in the re-frame2 triplet uses these
# chars. A stricter parser would EDN-read the file -- we keep the regex
# tight enough that an accidental `:name "foo bar"` (with a space) would
# fail to match, surfacing as missing-in-skill if the skill listed it.
_TOOL_NAME_CHARS = r"a-zA-Z0-9_./>!?*-"
# Matches `{:name "..."` to be safer against an unrelated `:name "..."`
# appearing in a docstring elsewhere. The actual descriptors always open
# with `{` immediately before `:name`. The `<` char is deliberately not in
# the class so the docstring example `{:name "<dash-separated-name>"` --
# present in tools/story-mcp/src/.../tools.cljc -- does not match.
STRICT_NAME_RE = re.compile(rf"\{{[^}}]*?:name\s+\"([{_TOOL_NAME_CHARS}]+)\"")


def extract_server_tools(paths: tuple[Path, ...]) -> set[str]:
    """Lex one-or-more MCP server source files for tool names.

    Returns the set of names declared inside `{:name "..." ...}` map
    literals across every path. Raises FileNotFoundError if any source
    is missing (caller handles `optional=True` skip).

    Multi-path support exists for servers whose tool catalogue is
    split across per-category leaves (e.g. story-mcp post rf2-3ukix —
    dev/docs/testing/write each own a `descriptors` def, with the
    recorder bridge living in a fifth leaf for leaf-size reasons).
    """
    tools: set[str] = set()
    for path in paths:
        text = path.read_text(encoding="utf-8")
        # Drop comment regions inside descriptor strings (Clojure ;; line
        # comments) so we don't pick up `;; :name "..."` examples in docstrings.
        # Pragmatic strip — full reader-aware parsing is overkill for this gate.
        stripped_lines: list[str] = []
        for line in text.splitlines():
            # Strip everything from the first ;; to EOL. Inside-string ;;'s are
            # extremely rare in this codebase and would only cause a false
            # positive (extra name spotted), which the cross-check would
            # surface as a missing-in-skill — caller debugs it.
            comment = line.find(";;")
            if comment >= 0:
                stripped_lines.append(line[:comment])
            else:
                stripped_lines.append(line)
        clean = "\n".join(stripped_lines)
        tools.update(STRICT_NAME_RE.findall(clean))
    return tools


# ---------------------------------------------------------------------------
# Skill-side extraction.
#
# `allowed-tools:` is a YAML list under the SKILL.md front-matter. The
# front-matter is delimited by `---` lines at the top of the file. We do
# not depend on PyYAML — the format is constrained and we can lex it with
# a small state machine. The list items can be either bare strings
# (`- mcp__re-frame2-pair__discover-app`) or `Bash(bd *)`-style wrapped
# entries. We only care about the `mcp__<prefix>__*` entries for the
# given prefix.
# ---------------------------------------------------------------------------

FRONTMATTER_DELIM = "---"


def extract_skill_tools(path: Path, host_prefix: str) -> set[str]:
    """Pull the `mcp__<host_prefix>__*` tool names from a SKILL.md.

    Returns the set of tool names (the trailing chunk after the second `__`).
    """
    text = path.read_text(encoding="utf-8")
    fm = _read_frontmatter(text)
    if fm is None:
        # No front-matter at all — treat as empty allow-list; the cross-check
        # will surface every server tool as missing-in-skill. That's the right
        # answer.
        return set()
    return _parse_allowed_tools(fm, host_prefix)


def extract_skill_bash_entries(path: Path) -> list[str]:
    """Pull every `Bash(...)` entry from a SKILL.md's `allowed-tools:` block.

    Returns the raw list of inner-string arguments (e.g.
    `gh issue create *`, `rg *`, `npm install`). Order preserved for
    diagnostics; duplicates left in.
    """
    text = path.read_text(encoding="utf-8")
    fm = _read_frontmatter(text)
    if fm is None:
        return []
    return _parse_allowed_bash(fm)


_BASH_ENTRY = re.compile(r"Bash\(\s*([^)]+?)\s*\)")


def _parse_allowed_bash(frontmatter: str) -> list[str]:
    """State-machine scan of the `allowed-tools:` block for `Bash(...)` items."""
    entries: list[str] = []
    in_block = False
    for line in frontmatter.splitlines():
        if _ALLOWED_TOOLS_KEY.match(line):
            in_block = True
            continue
        if not in_block:
            continue
        stripped = line.lstrip()
        if not stripped:
            continue
        if stripped.startswith("#"):
            continue
        m = _LIST_ITEM.match(line)
        if not m:
            break
        item = m.group(1).strip()
        bm = _BASH_ENTRY.match(item)
        if bm:
            entries.append(bm.group(1).strip())
    return entries


def _read_frontmatter(text: str) -> str | None:
    lines = text.splitlines()
    if not lines or lines[0].strip() != FRONTMATTER_DELIM:
        return None
    body = []
    for line in lines[1:]:
        if line.strip() == FRONTMATTER_DELIM:
            return "\n".join(body)
        body.append(line)
    return None


_ALLOWED_TOOLS_KEY = re.compile(r"^allowed-tools\s*:\s*$")
_LIST_ITEM = re.compile(r"^\s*-\s+(.*?)\s*$")


def _parse_allowed_tools(frontmatter: str, host_prefix: str) -> set[str]:
    """Tiny state machine: find `allowed-tools:`, then collect `- foo`
    items until indentation drops back to column zero (i.e. the next top
    level key).  Comment lines (`# ...`) inside the list are tolerated.
    """
    tools: set[str] = set()
    in_block = False
    prefix_marker = f"mcp__{host_prefix}__"
    for line in frontmatter.splitlines():
        if _ALLOWED_TOOLS_KEY.match(line):
            in_block = True
            continue
        if not in_block:
            continue
        # Block end: a non-indented, non-list line means we've fallen out
        # of the allowed-tools block.
        stripped = line.lstrip()
        if not stripped:
            # Blank line — stay in the block (YAML allows blank lines
            # between list items).
            continue
        if stripped.startswith("#"):
            continue
        m = _LIST_ITEM.match(line)
        if not m:
            # First non-list, non-comment, non-blank line ends the block.
            break
        item = m.group(1).strip()
        if item.startswith(prefix_marker):
            tools.add(item[len(prefix_marker):])
    return tools


# ---------------------------------------------------------------------------
# Bash-allow-list drift detection (rf2-yiccf).
#
# Companion to the MCP-axis cross-check above: the same skill files also
# declare `Bash(...)` allow-list entries that must agree with what the
# SKILL.md body actually instructs the agent to do. Bash-prefix drift is
# invisible to the MCP-axis gate but causes silent breakage at the agent
# host's permission boundary (e.g. rf2-scpaa: re-frame-migration cardinal
# rule 7 says "file a GitHub issue" but the allow-list lacks `Bash(gh
# issue ...)`).
#
# v0 shape per the bead: a hand-curated `BASH_RULES` table of
# (body-pattern, required-allow-list-pattern) per skill. The script lexes
# the SKILL.md body, and when a body pattern fires, checks the skill's
# `Bash(...)` allow-list for any entry that matches the required pattern.
# Wildcard semantics: `*` in the required-pattern matches any whitespace
# or characters (greedy) in the allow-list entry.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class BashRule:
    """One cross-check for the Bash axis.

    `skill_md`            — SKILL.md to inspect.
    `body_pattern`        — regex matched against the body (post-frontmatter).
                            If it fires anywhere, the allow-list must satisfy
                            `required_allow`.
    `required_allow`      — required allow-list entry shape; `*` is a
                            wildcard. The check passes if any actual
                            `Bash(...)` entry matches.
    `description`         — human-readable description of what the rule
                            catches, printed in the drift message.
    """
    skill_md: Path
    body_pattern: re.Pattern[str]
    required_allow: str
    description: str


def _allow_pattern(required: str) -> re.Pattern[str]:
    """Compile a `Bash(...)` required-entry shape with `*` wildcards into a regex."""
    escaped = re.escape(required).replace(r"\*", r".*")
    return re.compile(rf"^{escaped}$")


BASH_RULES: list[BashRule] = [
    # rf2-scpaa: re-frame-migration cardinal rule 7 instructs the agent to
    # file GitHub issues against day8/re-frame2; allow-list must permit
    # the create surface. `gh issue list` and `gh issue view` are
    # adjacent read-only surfaces the skill body also leans on; we gate on
    # the destructive `create` because that's the rule-7 surface.
    BashRule(
        skill_md=REPO_ROOT / "skills" / "re-frame-migration" / "SKILL.md",
        # Matches both the literal command and the natural-language
        # instruction shape (cardinal rules typically read "File a GitHub
        # issue against …" rather than spelling out `gh issue create`).
        body_pattern=re.compile(
            r"\bgh\s+issue\s+create\b|\bfile\s+(?:a\s+)?github\s+issue\b",
            re.IGNORECASE,
        ),
        required_allow="gh issue *",
        description="re-frame-migration body instructs the agent to file GitHub issues; allow-list must permit Bash(gh issue *)",
    ),
]


# ---------------------------------------------------------------------------
# Drift detection.
# ---------------------------------------------------------------------------


@dataclass
class Drift:
    mapping_name: str
    direction: str  # "missing-in-skill" | "missing-in-server"
    tool: str

    @property
    def key(self) -> tuple[str, str, str]:
        return (self.mapping_name, self.direction, self.tool)

    def message(self, mapping: Mapping | None) -> str:
        if self.direction == "missing-bash-allow":
            return (
                f"{self.mapping_name}: SKILL.md body references a Bash "
                f"command but `allowed-tools:` lacks Bash({self.tool}). "
                f"Add `Bash({self.tool})` to the skill's allow-list, or "
                f"remove the command reference from the body."
            )
        assert mapping is not None
        if self.direction == "missing-in-skill":
            return (
                f"{mapping.name}: MCP server exposes tool '{self.tool}' "
                f"but skill '{mapping.skill_md.relative_to(REPO_ROOT)}' "
                f"does not allow-list mcp__{mapping.host_prefix}__{self.tool}."
            )
        return (
            f"{mapping.name}: skill '{mapping.skill_md.relative_to(REPO_ROOT)}' "
            f"allow-lists mcp__{mapping.host_prefix}__{self.tool} "
            f"but the MCP server does not expose a tool by that name."
        )


def _read_body(text: str) -> str:
    """Return everything after the closing `---` frontmatter delimiter.

    If there's no frontmatter, the whole file is body. Used by Bash-rule
    body scans so frontmatter `allowed-tools:` entries (which may quote
    the same command shapes) don't double-trigger the body pattern.
    """
    lines = text.splitlines()
    if not lines or lines[0].strip() != FRONTMATTER_DELIM:
        return text
    for i, line in enumerate(lines[1:], start=1):
        if line.strip() == FRONTMATTER_DELIM:
            return "\n".join(lines[i + 1:])
    return ""


def check_bash_rules(rules: Iterable[BashRule]) -> tuple[list[Drift], list[str]]:
    """Run the Bash-axis cross-checks. Returns (drift, info-messages).

    Each rule fires drift when the SKILL.md body matches `body_pattern`
    and no `Bash(...)` entry in `allowed-tools:` matches `required_allow`.
    Drift entries piggyback on the existing `Drift` shape with mapping
    name set to `bash:<skill-rel-path>` and direction `missing-bash-allow`.
    """
    info: list[str] = []
    drift: list[Drift] = []
    for rule in rules:
        if not rule.skill_md.exists():
            info.append(
                f"bash-rule: skill md '{rule.skill_md}' missing -- skipping."
            )
            continue
        text = rule.skill_md.read_text(encoding="utf-8")
        body = _read_body(text)
        if not rule.body_pattern.search(body):
            continue  # Body doesn't reference the gated command; no rule fires.
        entries = extract_skill_bash_entries(rule.skill_md)
        allow_re = _allow_pattern(rule.required_allow)
        if any(allow_re.match(e) for e in entries):
            continue  # Allow-list satisfies the rule.
        rel = rule.skill_md.relative_to(REPO_ROOT)
        drift.append(Drift(
            mapping_name=f"bash:{rel.as_posix()}",
            direction="missing-bash-allow",
            tool=rule.required_allow,
        ))
        info.append(f"bash-rule: {rule.description} -- DRIFT.")
    return drift, info


def check_mapping(mapping: Mapping) -> tuple[list[Drift], list[str]]:
    """Return (drift, info-messages) for one mapping. Empty drift list = OK."""
    info: list[str] = []
    drift: list[Drift] = []

    missing_srcs = [p for p in mapping.server_src if not p.exists()]
    if missing_srcs:
        if mapping.optional:
            rels = ", ".join(str(p.relative_to(REPO_ROOT)) for p in missing_srcs)
            info.append(
                f"{mapping.name}: server src '{rels}' "
                "missing -- skipping (optional)."
            )
            return drift, info
        # Hard error -- the mapping declares a server that should be there.
        rels = ", ".join(str(p) for p in missing_srcs)
        raise FileNotFoundError(
            f"{mapping.name}: server src not found at {rels}"
        )

    if not mapping.skill_md.exists():
        if mapping.optional:
            info.append(
                f"{mapping.name}: skill md '{mapping.skill_md.relative_to(REPO_ROOT)}' "
                "missing -- skipping (optional)."
            )
            return drift, info
        raise FileNotFoundError(
            f"{mapping.name}: skill md not found at {mapping.skill_md}"
        )

    server_tools = extract_server_tools(mapping.server_src)
    skill_tools = extract_skill_tools(mapping.skill_md, mapping.host_prefix)

    info.append(
        f"{mapping.name}: server={len(server_tools)} tools, "
        f"skill={len(skill_tools)} allow-listed."
    )

    # MISSING-IN-SKILL: server tool not in skill, and not annotated as
    # intentionally server-only.
    for t in sorted(server_tools - skill_tools):
        if t in mapping.intentional_server_only:
            continue
        drift.append(Drift(mapping.name, "missing-in-skill", t))

    # MISSING-IN-SERVER: skill allow-list refers to a tool the server does
    # not expose. Always a failure — `intentional_server_only` cannot relax
    # this direction (a phantom reference is always wrong).
    for t in sorted(skill_tools - server_tools):
        drift.append(Drift(mapping.name, "missing-in-server", t))

    return drift, info


# ---------------------------------------------------------------------------
# CLI.
# ---------------------------------------------------------------------------


def _is_ci() -> bool:
    return os.environ.get("GITHUB_ACTIONS") == "true"


def _emit_error(msg: str, ci: bool) -> None:
    if ci:
        # `::error::` lines render as PR-attached annotations.
        print(f"::error::{msg}", file=sys.stderr)
    else:
        print(f"ERROR: {msg}", file=sys.stderr)


def main(argv: Iterable[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Diff MCP server tool catalogues against skill allowed-tools.",
    )
    parser.add_argument("--verbose", action="store_true",
                        help="Print per-mapping summary even when no drift.")
    parser.add_argument("--ci", action="store_true",
                        help="Emit GitHub-Actions ::error:: lines (auto-on under GITHUB_ACTIONS).")
    parser.add_argument("--no-baseline", action="store_true",
                        help="Ignore the shipped baseline — fail on every drift, current or new.")
    parser.add_argument("--show-baseline", action="store_true",
                        help="Print the current accepted baseline and exit.")
    args = parser.parse_args(list(argv))

    ci = args.ci or _is_ci()

    if args.show_baseline:
        if not _BASELINE:
            print("Baseline is empty — every drift is a failure.")
            return 0
        print("Accepted-baseline drift entries (each will silence one finding):")
        for k in sorted(_BASELINE):
            print(f"  {k[0]} | {k[1]} | {k[2]}")
        return 0

    all_drift: list[tuple[Mapping | None, Drift]] = []
    all_info: list[str] = []
    saw_setup_error = False

    for mapping in MAPPINGS:
        try:
            drift, info = check_mapping(mapping)
        except FileNotFoundError as e:
            _emit_error(str(e), ci)
            saw_setup_error = True
            continue
        all_info.extend(info)
        for d in drift:
            all_drift.append((mapping, d))

    # Bash-axis cross-checks (rf2-yiccf). These piggyback on the same drift
    # accumulator but carry mapping=None — Drift.message handles the
    # missing-bash-allow direction without a Mapping object.
    bash_drift, bash_info = check_bash_rules(BASH_RULES)
    all_info.extend(bash_info)
    for d in bash_drift:
        all_drift.append((None, d))

    if args.verbose:
        for line in all_info:
            print(line)

    # Partition drift against the baseline.
    new_drift = []
    silenced = []
    for m, d in all_drift:
        if (not args.no_baseline) and d.key in _BASELINE:
            silenced.append((m, d))
        else:
            new_drift.append((m, d))

    if args.verbose and silenced:
        print()
        print(f"Silenced by baseline ({len(silenced)}):")
        for m, d in silenced:
            print(f"  - {d.message(m)}")

    if new_drift:
        print()
        print(f"Drift detected ({len(new_drift)} finding{'' if len(new_drift) == 1 else 's'}):", file=sys.stderr)
        for m, d in new_drift:
            _emit_error(d.message(m), ci)
        # The baseline can be stale: entries in _BASELINE that no longer
        # reflect actual drift (because the underlying gap was fixed) are
        # surfaced as a warning so the maintainer trims them. Stale baseline
        # entries don't fail the build.
        _warn_stale_baseline(all_drift, args.no_baseline, ci)
        return 1

    if saw_setup_error:
        return 2

    # Silent-on-success (rf2-try1x): emit the success line only under
    # --verbose. Green CI runs and local invocations otherwise produce
    # no stdout — the exit code is the success signal.
    if args.verbose:
        print("No skill <-> MCP drift detected.")
    _warn_stale_baseline(all_drift, args.no_baseline, ci)
    return 0


def _warn_stale_baseline(all_drift, no_baseline: bool, ci: bool) -> None:
    if no_baseline:
        return
    actual_keys = {d.key for _m, d in all_drift}
    stale = sorted(_BASELINE - actual_keys)
    if not stale:
        return
    msg_header = "Baseline contains entries that no longer reflect actual drift -- trim them from _BASELINE in scripts/check_skill_mcp_drift.py:"
    if ci:
        print(f"::warning::{msg_header}", file=sys.stderr)
    else:
        print(msg_header, file=sys.stderr)
    for k in stale:
        line = f"  stale: {k[0]} | {k[1]} | {k[2]}"
        print(line, file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
