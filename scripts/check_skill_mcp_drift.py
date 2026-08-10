#!/usr/bin/env python3
"""Drift smoke-test: skill `allowed-tools` vs MCP server tool catalogue (rf2-flzdp + rf2-yiccf + rf2-4kyg6).

Four axes of cross-check.

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

**Title-safety axis** (rf2-4kyg6 finding 1): the all-current-consumers
backstop for the shared gh-issue shell-safety contract. `TITLE_SAFETY_RULES`
lists every gh-issue-writing consumer named in
`skills/shared/issue-filing.md` §Scope. Each must EITHER link
`../shared/issue-filing.md` (single-source — inherits both the body- and
the title-safety clauses) OR carry the local body+title clauses itself. The
`retro_protocol_test.clj` suite only pinned the re-frame2-pair-retro path;
this axis closes the gap for the other consumers so a deliberately-local
recipe cannot drift from the title half of the contract (no `--title-file`,
restricted safe alphabet, never-paste-evidence-into-`--title`).

  - MISSING-TITLE-SAFETY — a consumer neither links the shared leaf nor
    carries the local clauses, and is not under a tracked per-rule
    `allowance_bead` follow-up allowance.

**Doc-coverage axis** (rf2-l2y4n): the MCP axis proves a tool is
allow-listed, not that anyone can find out what it DOES. For each rule in
`DOC_COVERAGE_RULES`, the descriptor manifest's tool-name set must equal the
set of names appearing as a first-column code span in a markdown table row of
the named reference — the skill's transport index, whose `Semantics home`
column routes onward to the per-tool prose.

  - MISSING-DOC-ROW — the server exposes a tool with no row (shipped,
    counted, allow-listed, undocumented — the rf2-l2y4n defect).
  - STALE-DOC-ROW — a row names a tool the server no longer exposes.

Xray-MCP is currently spec-only (no `src/`); the script skips its entry
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
    python scripts/check_skill_mcp_drift.py --self-test      # title-safety-axis
                                                             #   self-test (rf2-4kyg6)
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
        # Post rf2-47g8l the catalogue data lives in a dedicated
        # `descriptors_data.cljs` leaf — the sibling `descriptors.cljs` is
        # now a slim splicer/façade with no `{:name "..."}` literals. Point
        # the gate at the data file.
        server_src=(REPO_ROOT / "tools" / "re-frame2-pair-mcp" / "src" / "re_frame2_pair_mcp" / "tools" / "descriptors_data.cljs",),
        host_prefix="re-frame2-pair",
        skill_md=REPO_ROOT / "skills" / "re-frame2-pair" / "SKILL.md",
        # rf2-230ekq: the two write-authority tools (rf2-ee38b.18),
        # `restore-epoch` + `replace-app-db`, ARE now allow-listed — they
        # are the canonical, audited path for named state rewrites. The
        # server's default-OFF `--allow-writes` launch gate (not the skill
        # allow-list) is the write-authority boundary: against a gate-OFF
        # server both refuse with `:rf.error/writes-disabled` without
        # touching the runtime, so allow-listing them is safe. The set is
        # therefore empty — every server tool must be allow-listed (a new
        # write tool should be allow-listed + gated at the server, not
        # excluded here).
        intentional_server_only=frozenset(),
    ),
    # story-mcp consumers (rf2-1v7tu HYBRID): both skills consume the
    # 17-tool surface, split along the authoring vs live-runtime axis.
    # - re-frame2 (authoring) owns: get-story-instructions, list-*, get-*,
    #   variant->edn, preview-variant, register-variant, unregister-variant.
    # - re-frame2-pair (live-session) owns: run-variant, read-failures,
    #   snapshot-identity, read-a11y-violations, record-as-variant.
    # Each mapping marks the OTHER skill's tools as `intentional_server_only`
    # so the gate only fires when the canonical owner forgets a tool.
    # The host prefix is `re-frame2-story-mcp` per both skills' allowed-tools
    # entries (the MCP server's advertised name).
    Mapping(
        # rf2-ia7qf finding 3: re-frame2-pair-retro is transcript-first and
        # grants exactly ONE re-frame2-pair MCP tool — the read-only
        # `discover-app` — use-gated to its opt-in live-runtime probe
        # (SKILL.md §Guard rails + references/known-frictions.md
        # §Tool-catalogue). Every OTHER pair-mcp tool is intentionally NOT
        # consumed by this skill (deeper live work routes to the
        # re-frame2-pair skill instead). Marking them server-only here turns
        # the MCP axis into the structural check finding 3 asks for: the
        # MISSING-IN-SERVER direction fails if the skill ever allow-lists a
        # phantom (e.g. names an opt-in tool the server dropped), and the
        # MISSING-IN-SKILL direction is silenced ONLY for the deliberately
        # un-consumed tools — so adding a new opt-in tool to the prose
        # without allow-listing it surfaces as drift the moment it is also
        # dropped from this set.
        name="re-frame2-pair-mcp <-> re-frame2-pair-retro",
        server_src=(REPO_ROOT / "tools" / "re-frame2-pair-mcp" / "src" / "re_frame2_pair_mcp" / "tools" / "descriptors_data.cljs",),
        host_prefix="re-frame2-pair",
        skill_md=REPO_ROOT / "skills" / "re-frame2-pair-retro" / "SKILL.md",
        intentional_server_only=frozenset({
            # Everything except the single opt-in read-only probe
            # `discover-app`. This skill operates on a transcript / recap;
            # all live-operation tools belong to the re-frame2-pair skill.
            "orient",
            "describe-image",
            "eval-cljs",
            "dispatch",
            "dispatch-dry-run",
            "restore-epoch",
            "replace-app-db",
            "trace-window",
            "watch-epochs",
            "tail-build",
            "snapshot",
            "get-path",
            "read-sub",
            "read-dom",
            "read-ui",
            # The Hicasso evidence door — live view work routes to the
            # re-frame2-pair skill, same as read-sub/read-dom/read-ui above.
            "read-mounted-boundaries",
            "read-read-attribution",
            "explain-render",
            "record",
            "read-recording",
            "watch-until",
            "subscribe",
            "unsubscribe",
            "list-subscriptions",
            "list-streams",
            "get-stream-controls",
            "handler-meta",
            "list-handlers",
            "set-operating-frame",
            "reset-operating-frame",
            "get-operating-frame",
            "get-re-frame2-pair-instructions",
        }),
    ),
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
            "read-a11y-violations",
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
_BASELINE: set[tuple[str, str, str]] = set()


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

    `skill_md`            — SKILL.md whose `allowed-tools:` frontmatter
                            supplies the allow-list the rule checks.
    `body_pattern`        — regex matched against the body (post-frontmatter)
                            of the scan target (`body_md` if set, else
                            `skill_md`). If it fires anywhere, the allow-list
                            on `skill_md` must satisfy `required_allow`.
    `required_allow`      — required allow-list entry shape; `*` is a
                            wildcard. The check passes if any actual
                            `Bash(...)` entry on `skill_md` matches.
    `description`         — human-readable description of what the rule
                            catches, printed in the drift message.
    `body_md`             — optional separate doc to scan for `body_pattern`.
                            Defaults to `skill_md`. Use when a skill states
                            the gated command in a reference leaf rather than
                            in SKILL.md itself (e.g. re-frame2-implementor's
                            spec-pin `git -C` provenance check lives in
                            references/cardinal-rules.md, while the allow-list
                            it requires lives in SKILL.md's frontmatter). The
                            allow-list is ALWAYS read from `skill_md`.
    """
    skill_md: Path
    body_pattern: re.Pattern[str]
    required_allow: str
    description: str
    body_md: Path | None = None


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
    # rf2-pd4is8: the migration-corpus pin check (cardinal rule 5 +
    # references/setup.md §Pin the migration corpus) is a load-bearing,
    # read-only provenance step the skill runs ITSELF. SKILL.md cardinal
    # rule 5 spells out the two `git -C <path> rev-parse` / `remote
    # get-url` commands; the allow-list must permit them, or the skill
    # presents a provenance check it cannot run (the defect this rule
    # catches). Scoped narrowly to the two read-only sub-surfaces — the
    # build/test/install boundary (cardinal rule 5) stays the author's.
    BashRule(
        skill_md=REPO_ROOT / "skills" / "re-frame-migration" / "SKILL.md",
        body_pattern=re.compile(r"\bgit\s+-C\b[^\n`]*\brev-parse\b", re.IGNORECASE),
        required_allow="git -C * rev-parse *",
        description="re-frame-migration body runs the corpus-pin `git -C … rev-parse` provenance check; allow-list must permit Bash(git -C * rev-parse *)",
    ),
    BashRule(
        skill_md=REPO_ROOT / "skills" / "re-frame-migration" / "SKILL.md",
        body_pattern=re.compile(r"\bgit\s+-C\b[^\n`]*\bremote\s+get-url\b", re.IGNORECASE),
        required_allow="git -C * remote get-url *",
        description="re-frame-migration body runs the corpus-pin `git -C … remote get-url` provenance check; allow-list must permit Bash(git -C * remote get-url *)",
    ),
    # rf2-fvvzxi: re-frame2-implementor cardinal rules 8–9 instruct the agent
    # to file GitHub issues against day8/re-frame2 for spec gaps; SKILL.md
    # body spells out `gh issue create` (and `gh issue list` for the
    # search-before-filing dedupe), so the allow-list must permit the issue
    # surface — the same shape as the migration rule above. The body pattern
    # lives in SKILL.md itself here, so no `body_md` redirect is needed.
    BashRule(
        skill_md=REPO_ROOT / "skills" / "re-frame2-implementor" / "SKILL.md",
        body_pattern=re.compile(
            r"\bgh\s+issue\s+(?:create|list)\b|\bfile\s+(?:a\s+)?github\s+issue\b",
            re.IGNORECASE,
        ),
        required_allow="gh issue *",
        description="re-frame2-implementor body instructs the agent to file/search GitHub issues; allow-list must permit Bash(gh issue *)",
    ),
    # rf2-fvvzxi: re-frame2-implementor cardinal rule 1 (Phase 1 spec-pin
    # preamble) instructs the agent to verify the pinned `day8/re-frame2`
    # checkout with `git -C <path-to-re-frame2> rev-parse HEAD` /
    # `remote get-url origin` BEFORE reading the spec — a load-bearing,
    # read-only provenance step the skill runs ITSELF (the same shape the
    # migration rules above guard). Unlike migration, the implementor states
    # the commands in references/cardinal-rules.md (§1) rather than SKILL.md,
    # so `body_md` points the body scan at that leaf while the allow-list is
    # still read from SKILL.md's frontmatter. Scoped narrowly to the two
    # read-only sub-surfaces; commits + the port's own build/test runner stay
    # engineer-owned (per references/output-format.md §Discipline — they vary
    # per host and run under the engineer's session permissions, not the
    # skill's baseline allow-list).
    # `rev-parse` is spelled literally in cardinal-rules.md §1; `remote
    # get-url` is spelled literally in the phase-1-decisions.md §Spec-pin
    # preamble (cardinal-rules.md §1 describes the origin check in prose but
    # only spells the `rev-parse` command). Each rule's `body_md` points at
    # the leaf that carries its literal command; both require the same
    # SKILL.md frontmatter allow-list.
    BashRule(
        skill_md=REPO_ROOT / "skills" / "re-frame2-implementor" / "SKILL.md",
        body_md=REPO_ROOT / "skills" / "re-frame2-implementor" / "references" / "cardinal-rules.md",
        body_pattern=re.compile(r"\bgit\s+-C\b[^\n`]*\brev-parse\b", re.IGNORECASE),
        required_allow="git -C * rev-parse *",
        description="re-frame2-implementor cardinal-rules.md runs the spec-pin `git -C … rev-parse` provenance check; SKILL.md allow-list must permit Bash(git -C * rev-parse *)",
    ),
    BashRule(
        skill_md=REPO_ROOT / "skills" / "re-frame2-implementor" / "SKILL.md",
        body_md=REPO_ROOT / "skills" / "re-frame2-implementor" / "references" / "phase-1-decisions.md",
        body_pattern=re.compile(r"\bgit\s+-C\b[^\n`]*\bremote\s+get-url\b", re.IGNORECASE),
        required_allow="git -C * remote get-url *",
        description="re-frame2-implementor phase-1-decisions.md runs the spec-pin `git -C … remote get-url` provenance check; SKILL.md allow-list must permit Bash(git -C * remote get-url *)",
    ),
]


# ---------------------------------------------------------------------------
# Title-safety axis — all-current-consumers backstop for the shared
# gh-issue shell-safety contract (rf2-4kyg6 finding 1).
#
# `skills/shared/issue-filing.md` is the single home for the gh-issue
# shell-safety core. Its §Scope claims that EVERY gh-issue-writing
# consumer either LINKS that leaf (and so inherits the body- AND
# title-safety clauses for free) or carries the same clauses locally.
# The retro_protocol_test.clj suite only pins that contract for the
# re-frame2-pair-retro path; an implementor-style consumer with a
# deliberately-local recipe can pass every existing gate while its local
# copy drifts from the *title* half of the contract (no `--title-file`,
# restricted safe alphabet, never-paste-evidence-into-`--title`, reviewer
# pass for the title arg) — `gh issue create` has no `--title-file` flag,
# so the body-file trick cannot protect the title and the title is the
# one argv the shell still expands before `gh` sees it.
#
# This axis enforces the §Scope claim structurally: for every consumer
# listed below, require EITHER a link to `../shared/issue-filing.md`
# (single-source — inherits both halves) OR local body+title clauses.
# The check fires `missing-title-safety` drift when a consumer neither
# links the leaf nor carries the local clauses.
#
# The per-rule `allowance_bead` field is the escape hatch for a consumer
# that is being updated under a tracked follow-up bead but cannot be
# edited in the same change (e.g. a concurrent worker holds the file). An
# allowance keeps the gate green now and is removed by the named
# follow-up, which tightens the gate to enforce the clauses. KEEP IT
# NARROW — every allowance must name a bead.
# ---------------------------------------------------------------------------

# The canonical leaf every consumer may link to satisfy the single-source
# branch. Matched as a substring against the consumer's SKILL.md AND its
# referenced recipe leaves (paths listed per rule).
SHARED_ISSUE_FILING_LINK = "shared/issue-filing.md"


@dataclass(frozen=True)
class TitleSafetyRule:
    """One all-consumers title-safety backstop check (rf2-4kyg6).

    `consumer`         — short human label for the gh-issue-writing skill.
    `docs`             — tuple of doc paths that together carry the
                         consumer's filing recipe (its SKILL.md plus any
                         local recipe leaf). The check concatenates their
                         text. Link-presence OR clause-presence is
                         evaluated over the union.
    `allowance_bead`   — if set, the consumer is a known follow-up: drift
                         is silenced and reported as an info line naming
                         the bead. `None` = enforced.
    `require_search`   — if True, a DELIBERATELY-LOCAL consumer (one that
                         carries the recipe instead of linking the shared
                         leaf) must ALSO carry the search-before-filing
                         clause locally (`gh issue list --search` + the
                         author-the-keywords-never-paste shell-safety note).
                         The shared leaf's §"Search before filing" hardening
                         is inherited for free by link-branch consumers; a
                         local copy can silently omit it, so this flag pins
                         the dedupe clause for the local-recipe consumer
                         (rf2-ij6ulc). Ignored on the link branch.
    `local_recipe`     — if True, this consumer carries its filing recipe
                         LOCALLY and is NOT permitted to satisfy the contract
                         via the loose shared-leaf-link substring (rf2-wpwckr
                         finding 2). The link branch's `SHARED_ISSUE_FILING_LINK
                         in text` test is a loose substring match — a
                         deliberately-local consumer that only *mentions* the
                         shared leaf's path in prose (e.g. "kept in sync with
                         skills/shared/issue-filing.md") would otherwise
                         short-circuit on the link branch and skip its local
                         body+title clause enforcement entirely, letting the
                         local title/body safety silently drift. With this flag
                         set the link branch is skipped and the consumer MUST be
                         proven by its local body+title clauses (plus the search
                         clause when `require_search`). A consumer that TRULY
                         loads/links the shared leaf as its canonical recipe
                         (pair-retro, migration) leaves this False and is
                         satisfied by the link.
    """
    consumer: str
    docs: tuple[Path, ...]
    allowance_bead: str | None = None
    require_search: bool = False
    local_recipe: bool = False


# Body-safety clause markers: the `--body-file` recipe and the
# never-interpolate-inline prohibition. Either alternative per pair.
_BODY_SAFE_MARKERS: tuple[tuple[str, ...], ...] = (
    ("--body-file",),
    ("never interpolate", "Never interpolate", "never inline"),
)

# Title-safety clause markers: no `--title-file`, the restricted safe
# alphabet, and the never-paste-evidence-into-`--title` prohibition.
# Each tuple is an OR over acceptable phrasings; ALL tuples must hit.
_TITLE_SAFE_MARKERS: tuple[tuple[str, ...], ...] = (
    ("no `--title-file`", "no --title-file", "has no `--title-file`",
     "has no --title-file"),
    ("safe alphabet", "safe-alphabet"),
    ("into `--title`", "into --title"),
)

# Search-before-filing clause markers (rf2-ij6ulc). A deliberately-local
# consumer (`require_search=True`) must carry BOTH: the dedupe command
# (`gh issue list` with `--search`) and the author-the-keywords-never-paste
# shell-safety note (`--search` is an inline argument, no `--search-file`).
# Each tuple is an OR over acceptable phrasings; ALL tuples must hit.
_SEARCH_SAFE_MARKERS: tuple[tuple[str, ...], ...] = (
    ("gh issue list",),
    ("--search",),
    ("no `--search-file`", "no --search-file", "there is no `--search-file`",
     "there is no --search-file"),
)


def _all_markers_present(text: str, marker_groups: tuple[tuple[str, ...], ...]) -> bool:
    """True iff every group has at least one alternative present in `text`."""
    return all(any(alt in text for alt in group) for group in marker_groups)


TITLE_SAFETY_RULES: list[TitleSafetyRule] = [
    # re-frame2-pair-retro — links the shared leaf from its SKILL.md
    # (§Filing improvements / Reference files). Satisfied by the
    # link-presence branch; the retro_protocol_test.clj suite separately
    # pins its local title clauses.
    TitleSafetyRule(
        consumer="re-frame2-pair-retro",
        docs=(REPO_ROOT / "skills" / "re-frame2-pair-retro" / "SKILL.md",),
    ),
    # re-frame-migration — cardinal-rule recipe links the shared leaf
    # (SKILL.md §Recipes). Satisfied by link-presence.
    TitleSafetyRule(
        consumer="re-frame-migration",
        docs=(REPO_ROOT / "skills" / "re-frame-migration" / "SKILL.md",),
    ),
    # re-frame2-implementor — carries a DELIBERATELY-LOCAL recipe in
    # references/cardinal-rules.md §8 and does NOT link the shared leaf as its
    # canonical recipe. That local recipe now carries BOTH the body-safety
    # clauses and the title-safety clauses (no `--title-file`, restricted safe
    # alphabet, never-paste-evidence-into-`--title`, reviewer pass for the title
    # arg), added under rf2-57b5t0 — the rf2-4kyg6 finding-1 gap is closed. The
    # follow-up allowance that kept the gate green while rf2-708nm held
    # cardinal-rules.md has been removed, so this consumer is now enforced
    # via the local-clauses branch (no allowance).
    # The local recipe also carries the search-before-filing (dedupe) clause
    # — `gh issue list --search` + the author-the-keywords-never-paste
    # shell-safety note — added under rf2-ij6ulc so the local copy can't drift
    # from the shared leaf's §"Search before filing" hardening. `require_search`
    # pins it: a future edit that drops the dedupe clause from the local recipe
    # fires `missing-search-clause` drift.
    # rf2-wpwckr finding 2: `local_recipe=True` closes the guard hole. The
    # local recipe *mentions* `skills/shared/issue-filing.md` in prose (it says
    # the local copy is kept in sync with the shared leaf), so the loose
    # `SHARED_ISSUE_FILING_LINK in text` substring would otherwise short-circuit
    # this consumer onto the link branch and SKIP its local body+title clause
    # enforcement — a deliberately-local consumer could then drift the title/body
    # safety while still reporting "links the shared leaf -- OK". With
    # `local_recipe=True` the link branch is bypassed and the implementor MUST be
    # proven by its local body+title (+search) clauses.
    TitleSafetyRule(
        consumer="re-frame2-implementor",
        docs=(
            REPO_ROOT / "skills" / "re-frame2-implementor" / "SKILL.md",
            REPO_ROOT / "skills" / "re-frame2-implementor" / "references" / "cardinal-rules.md",
        ),
        require_search=True,
        local_recipe=True,
    ),
]


def check_title_safety_rules(
    rules: Iterable[TitleSafetyRule],
) -> tuple[list[Drift], list[str]]:
    """Run the all-consumers title-safety backstop (rf2-4kyg6 finding 1).

    For each consumer, the union of its doc texts must EITHER link the
    shared issue-filing leaf OR carry both the body-safety and the
    title-safety clauses locally. A consumer that does neither is drift,
    unless it carries an `allowance_bead` (a tracked follow-up), in which
    case it is silenced with an info line naming the bead.
    """
    info: list[str] = []
    drift: list[Drift] = []
    for rule in rules:
        missing = [p for p in rule.docs if not p.exists()]
        if missing:
            rels = ", ".join(str(p) for p in missing)
            raise FileNotFoundError(
                f"title-safety: consumer '{rule.consumer}' doc not found at {rels}"
            )
        text = "\n".join(p.read_text(encoding="utf-8") for p in rule.docs)

        # rf2-ij6ulc: the search-before-filing (dedupe) check runs FIRST and
        # INDEPENDENTLY of the link/local-clause branch dispatch below. The
        # link branch's `SHARED_ISSUE_FILING_LINK in text` test is a loose
        # substring match — a deliberately-local consumer (like
        # re-frame2-implementor, which carries the recipe and only *mentions*
        # the shared leaf's path in prose) would otherwise short-circuit on the
        # link branch and skip its local-clause enforcement entirely. So a
        # require_search consumer must carry the dedupe clause regardless of
        # which branch it lands on; a genuine link-branch consumer inherits the
        # shared leaf's §"Search before filing" for free and never sets
        # require_search.
        if rule.require_search and not _all_markers_present(
            text, _SEARCH_SAFE_MARKERS
        ):
            drift.append(Drift(
                mapping_name=f"title-safety:{rule.consumer}",
                direction="missing-search-clause",
                tool=rule.consumer,
            ))
            info.append(
                f"title-safety: {rule.consumer} is MISSING the search-before-"
                f"filing (dedupe) clause (`gh issue list --search` + the "
                f"author-the-keywords-never-paste note) -- DRIFT."
            )
            continue

        # rf2-wpwckr finding 2: a DELIBERATELY-LOCAL consumer (`local_recipe`)
        # must NOT be satisfied by the loose `SHARED_ISSUE_FILING_LINK in text`
        # substring — it carries the recipe and only *mentions* the shared
        # leaf's path in prose ("kept in sync with skills/shared/issue-filing.md"),
        # which the substring match would mistake for a canonical link and so
        # skip the local body+title clause enforcement, letting the local
        # title/body safety silently drift. The link branch is therefore gated
        # on `not rule.local_recipe`: only a consumer that TRULY loads/links the
        # shared leaf as its canonical recipe (pair-retro, migration) inherits
        # both halves via the link; the local-recipe consumer is forced down the
        # clause branch below and proven by its own body+title (+search) clauses.
        if not rule.local_recipe and SHARED_ISSUE_FILING_LINK in text:
            info.append(
                f"title-safety: {rule.consumer} links the shared "
                f"issue-filing leaf (single-source)"
                + ("; local search clause present" if rule.require_search else "")
                + " -- OK."
            )
            continue

        has_body = _all_markers_present(text, _BODY_SAFE_MARKERS)
        has_title = _all_markers_present(text, _TITLE_SAFE_MARKERS)
        if has_body and has_title:
            info.append(
                f"title-safety: {rule.consumer} carries local body+title"
                + ("+search" if rule.require_search else "")
                + (" shell-safety clauses (local recipe; link-branch bypassed)"
                   if rule.local_recipe
                   else " shell-safety clauses")
                + " -- OK."
            )
            continue

        # Neither linked nor fully clause-covered: this consumer can drift
        # from the title half of the shared shell-safety contract.
        if rule.allowance_bead:
            info.append(
                f"title-safety: {rule.consumer} lacks the title-safety "
                f"clauses and does not link the shared leaf -- ALLOWED "
                f"pending follow-up {rule.allowance_bead} "
                f"(body-clauses={'present' if has_body else 'absent'}, "
                f"title-clauses=absent)."
            )
            continue

        drift.append(Drift(
            mapping_name=f"title-safety:{rule.consumer}",
            direction="missing-title-safety",
            tool=rule.consumer,
        ))
        info.append(
            f"title-safety: {rule.consumer} neither links "
            f"../shared/issue-filing.md nor carries local body+title "
            f"shell-safety clauses "
            f"(body-clauses={'present' if has_body else 'absent'}, "
            f"title-clauses={'present' if has_title else 'absent'}) -- DRIFT."
        )
    return drift, info


# ---------------------------------------------------------------------------
# Doc-coverage axis — every server tool must have a documented semantic home
# (rf2-l2y4n).
#
# The MCP axis above proves a tool is ALLOW-LISTED; it says nothing about
# whether a human or agent can find out what the tool DOES. rf2-l2y4n is the
# defect that gap admits: PR #6184 allow-listed the five S3 view-inspection
# tools and reconciled the prose tool COUNT to 35, so every existing gate
# stayed green while the five S3 view tools (`explain-render` and the four
# `read-view-*` / `read-mounted-views` reads it shipped beside, since replaced
# by the Hicasso evidence door under rf2-n3mb)
# appeared nowhere but the SKILL.md frontmatter — no arg shape, no semantics,
# no workflow.
#
# The check is deliberately ONE structural comparison, not a prose reader:
# the descriptor manifest's tool-name set must equal the set of tool names
# appearing as a FIRST-COLUMN code span in a markdown table row of the named
# doc. That doc's table is the skill's transport index, and its
# `Semantics home` column is what routes onward to the per-tool prose — so a
# name present there is a tool with a findable home, and a name absent is a
# tool with none.
#
# Both directions fail:
#
#   - MISSING-DOC-ROW — the server exposes a tool with no row. This is the
#     rf2-l2y4n defect: shipped, counted, allow-listed, undocumented.
#   - STALE-DOC-ROW  — a row names a tool the server no longer exposes. The
#     row outlived its tool and now documents a phantom.
#
# What it deliberately does NOT do: read prose, count tools, judge a row's
# CONTENT, or generate documentation. A row whose prose is wrong is a review
# problem, not a gate problem.
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class DocCoverageRule:
    """One (descriptor manifest, documenting reference) pair to cross-check.

    `name`        — human label used in drift messages.
    `server_src`  — descriptor source path(s), lexed by `extract_server_tools`
                    (the same extractor the MCP axis uses, so the two axes can
                    never disagree about what the server exposes).
    `doc_md`      — the reference whose table rows must cover every tool.
    """
    name: str
    server_src: tuple[Path, ...]
    doc_md: Path


DOC_COVERAGE_RULES: list[DocCoverageRule] = [
    DocCoverageRule(
        name="re-frame2-pair-mcp <-> references/mcp-transport.md",
        server_src=(REPO_ROOT / "tools" / "re-frame2-pair-mcp" / "src" / "re_frame2_pair_mcp" / "tools" / "descriptors_data.cljs",),
        doc_md=REPO_ROOT / "skills" / "re-frame2-pair" / "references" / "mcp-transport.md",
    ),
]


# A tool row in the transport index: a table row whose FIRST cell is a code
# span holding a tool name. Anchored to the line start so a code span in a
# later column (an arg name, an example) can never be mistaken for a row's
# subject. The leading `[a-z]` keeps the sibling keyword tables out — their
# first column holds `:reason` keywords (`:build-not-running`), which start
# with a colon and so never match.
_DOC_TABLE_TOOL_RE = re.compile(
    rf"^\|\s*`([a-z][{_TOOL_NAME_CHARS}]*)`", re.MULTILINE
)


def extract_documented_tools(path: Path) -> set[str]:
    """The set of tool names documented as table rows in `path`."""
    return set(_DOC_TABLE_TOOL_RE.findall(path.read_text(encoding="utf-8")))


def check_doc_coverage_rules(
    rules: Iterable[DocCoverageRule],
) -> tuple[list[Drift], list[str]]:
    """Run the doc-coverage axis (rf2-l2y4n). Returns (drift, info-messages)."""
    info: list[str] = []
    drift: list[Drift] = []
    for rule in rules:
        missing = [p for p in (*rule.server_src, rule.doc_md) if not p.exists()]
        if missing:
            rels = ", ".join(str(p) for p in missing)
            raise FileNotFoundError(
                f"doc-coverage: rule '{rule.name}' input not found at {rels}"
            )
        server_tools = extract_server_tools(rule.server_src)
        documented = extract_documented_tools(rule.doc_md)
        info.append(
            f"doc-coverage: {rule.name}: server={len(server_tools)} tools, "
            f"doc={len(documented)} rows."
        )
        for t in sorted(server_tools - documented):
            drift.append(Drift(f"doc-coverage:{rule.name}", "missing-doc-row", t))
        for t in sorted(documented - server_tools):
            drift.append(Drift(f"doc-coverage:{rule.name}", "stale-doc-row", t))
    return drift, info


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
        if self.direction == "missing-title-safety":
            return (
                f"{self.mapping_name}: gh-issue-writing consumer '{self.tool}' "
                f"neither links `../shared/issue-filing.md` nor carries the "
                f"local body+title shell-safety clauses. Per "
                f"skills/shared/issue-filing.md §Scope, every consumer must do "
                f"one or the other so the title half of the shell-safety "
                f"contract (no `--title-file`, restricted safe alphabet, "
                f"never paste evidence into `--title`) is enforced. Link the "
                f"shared leaf, or add the clauses to the consumer's local "
                f"recipe. (rf2-4kyg6)"
            )
        if self.direction == "missing-doc-row":
            return (
                f"{self.mapping_name}: MCP server exposes tool '{self.tool}' "
                f"but the reference has no table row documenting it. A tool "
                f"that is shipped, counted, and allow-listed but undocumented "
                f"leaves callers with no arg shape, semantics, or workflow. "
                f"Add a row (name | arg signature | semantics home) to the "
                f"transport index, and its semantics to the home it points at. "
                f"(rf2-l2y4n)"
            )
        if self.direction == "stale-doc-row":
            return (
                f"{self.mapping_name}: the reference documents a tool "
                f"'{self.tool}' the MCP server does not expose. The row "
                f"outlived its tool — remove it, or restore the tool. "
                f"(rf2-l2y4n)"
            )
        if self.direction == "missing-search-clause":
            return (
                f"{self.mapping_name}: gh-issue-writing consumer '{self.tool}' "
                f"carries a deliberately-local filing recipe but is missing "
                f"the search-before-filing (dedupe) clause. Per "
                f"skills/shared/issue-filing.md §\"Search before filing\", a "
                f"consumer that does not link the shared leaf must carry the "
                f"dedupe step locally: `gh issue list --repo <owner/repo> "
                f"--search \"<keywords>\"` AND the shell-safety note that "
                f"`--search` is an inline argument (no `--search-file`) whose "
                f"keywords are agent-authored, never pasted from evidence. Add "
                f"the clause to the local recipe, or link the shared leaf. "
                f"(rf2-ij6ulc)"
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
        # Scan `body_md` (a reference leaf) for the gated command when set;
        # otherwise scan SKILL.md's own body. The allow-list is always read
        # from `skill_md`'s frontmatter (below) regardless of scan target.
        scan_md = rule.body_md or rule.skill_md
        if not scan_md.exists():
            info.append(
                f"bash-rule: scan md '{scan_md}' missing -- skipping."
            )
            continue
        text = scan_md.read_text(encoding="utf-8")
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


def _run_self_test(ci: bool) -> int:
    """Self-test the title-safety backstop (rf2-4kyg6 finding 1).

    Guards against the axis silently degrading into a no-op. As of
    rf2-57b5t0 the implementor allowance is removed — every shipped
    consumer satisfies the contract on its own (pair-retro / migration via
    the shared-leaf link, implementor via its local body+title clauses), so
    the non-vacuousness proof uses synthetic fixtures rather than toggling a
    live allowance:

      1. With the shipped `TITLE_SAFETY_RULES`, the axis produces NO drift
         (all consumers satisfied; no allowances remain).
      2. A synthetic consumer whose docs carry NEITHER the shared-leaf link
         NOR the local body+title clauses MUST fire `missing-title-safety`
         drift — proving the clause/link detection is real, not vacuously
         satisfied.
      3. A synthetic consumer carrying the local body+title clauses (no
         link) MUST stay green — proving the local-clauses branch is the
         live path the implementor recipe now travels.
      4. The shipped implementor consumer specifically must be GREEN via
         its local clauses with no allowance present — the exact tightening
         rf2-57b5t0 lands.
      5. (rf2-wpwckr finding 2) A synthetic `local_recipe=True` consumer
         whose docs MENTION `skills/shared/issue-filing.md` in prose but lack
         the local body+title clauses MUST fire `missing-title-safety` — the
         loose link substring no longer rescues a deliberately-local
         consumer onto the link branch. The mirror positive: the same
         consumer WITH the local clauses (still only mentioning the path)
         stays green via the clause branch, proving the bypass routes to the
         right branch rather than failing outright.

    Returns 0 on pass, 1 on a broken invariant.
    """
    import tempfile

    failures: list[str] = []

    # (1) Shipped rules: no title-safety drift, and no allowance remains.
    drift, _ = check_title_safety_rules(TITLE_SAFETY_RULES)
    if any(d.direction == "missing-title-safety" for d in drift):
        failures.append(
            "shipped TITLE_SAFETY_RULES produced missing-title-safety drift "
            "(every shipped consumer must satisfy the contract on its own)."
        )
    if any(r.allowance_bead for r in TITLE_SAFETY_RULES):
        leftover = [r.consumer for r in TITLE_SAFETY_RULES if r.allowance_bead]
        failures.append(
            "a TITLE_SAFETY_RULES entry still carries an allowance_bead "
            f"({leftover}); rf2-57b5t0 removes the implementor allowance and "
            "no consumer should rely on one."
        )

    with tempfile.TemporaryDirectory() as td:
        td_path = Path(td)

        # (2) Synthetic NEGATIVE fixture: neither link nor clauses -> MUST drift.
        neg = td_path / "no-safety.md"
        neg.write_text(
            "# fixture\nThis recipe files a gh issue with no shell-safety "
            "guidance at all.\n",
            encoding="utf-8",
        )
        neg_drift, _ = check_title_safety_rules(
            [TitleSafetyRule(consumer="synthetic-negative", docs=(neg,))]
        )
        if not [
            d for d in neg_drift
            if d.direction == "missing-title-safety"
            and d.tool == "synthetic-negative"
        ]:
            failures.append(
                "a synthetic consumer lacking BOTH the shared-leaf link and "
                "the local body+title clauses did NOT fire missing-title-safety "
                "drift -- the title-safety check is a no-op. The clause/link "
                "detection is broken."
            )

        # (3) Synthetic POSITIVE fixture: local body+title clauses, no link ->
        #     MUST stay green (the branch the implementor recipe now travels).
        pos = td_path / "local-clauses.md"
        pos.write_text(
            "# fixture\n"
            "Write the body with the Write tool and pass it via --body-file; "
            "never interpolate transcript text inline.\n"
            "gh issue create has no `--title-file` flag; author the title from "
            "a restricted safe alphabet (letters, digits, spaces, - . , / ( ) :) "
            "and never paste evidence into `--title`; the reviewer pass covers "
            "the title arg.\n",
            encoding="utf-8",
        )
        pos_drift, _ = check_title_safety_rules(
            [TitleSafetyRule(consumer="synthetic-positive", docs=(pos,))]
        )
        if [
            d for d in pos_drift
            if d.direction == "missing-title-safety"
            and d.tool == "synthetic-positive"
        ]:
            failures.append(
                "a synthetic consumer carrying the local body+title clauses "
                "(no shared-leaf link) was flagged as missing-title-safety -- "
                "the local-clauses branch is broken."
            )

        # (3b) Synthetic SEARCH-NEGATIVE (rf2-ij6ulc): body+title clauses but
        #      NO search-before-filing clause, require_search=True -> MUST fire
        #      missing-search-clause (and NOT missing-title-safety).
        search_neg_drift, _ = check_title_safety_rules(
            [TitleSafetyRule(
                consumer="synthetic-search-negative",
                docs=(pos,),
                require_search=True,
            )]
        )
        if not [
            d for d in search_neg_drift
            if d.direction == "missing-search-clause"
            and d.tool == "synthetic-search-negative"
        ]:
            failures.append(
                "a require_search consumer carrying body+title clauses but NO "
                "search-before-filing clause did NOT fire missing-search-clause "
                "drift -- the search-clause check is a no-op (rf2-ij6ulc)."
            )

        # (3c) Synthetic SEARCH-POSITIVE (rf2-ij6ulc): body+title+search clauses,
        #      require_search=True -> MUST stay green.
        search_pos = td_path / "local-clauses-with-search.md"
        search_pos.write_text(
            pos.read_text(encoding="utf-8")
            + "\nSearch first: gh issue list --repo day8/re-frame2 --search "
            "\"<keywords>\"; there is no `--search-file`, so author the "
            "keywords from the safe alphabet and never paste evidence.\n",
            encoding="utf-8",
        )
        search_pos_drift, _ = check_title_safety_rules(
            [TitleSafetyRule(
                consumer="synthetic-search-positive",
                docs=(search_pos,),
                require_search=True,
            )]
        )
        if [
            d for d in search_pos_drift
            if d.tool == "synthetic-search-positive"
        ]:
            failures.append(
                "a require_search consumer carrying body+title+search clauses "
                "(no shared-leaf link) was flagged -- the search-clause branch "
                "is broken (rf2-ij6ulc)."
            )

        # (3d) Synthetic LOCAL-RECIPE-LINK-MENTION-NEGATIVE (rf2-wpwckr finding
        #      2): a `local_recipe=True` consumer whose doc MENTIONS the shared
        #      leaf's path in prose (the loose `SHARED_ISSUE_FILING_LINK`
        #      substring is present) but lacks the local body+title clauses MUST
        #      fire missing-title-safety. Before the fix the loose substring
        #      short-circuited onto the link branch and reported "links the
        #      shared leaf -- OK", masking the missing clauses. This fixture
        #      proves the link branch is now bypassed for local-recipe consumers.
        link_mention_no_clauses = td_path / "local-recipe-mentions-link.md"
        link_mention_no_clauses.write_text(
            "# fixture\n"
            "This recipe files a gh issue. Its shell-safety guidance is kept "
            "in sync with skills/shared/issue-filing.md as a local copy.\n"
            "Search first: gh issue list --repo day8/re-frame2 --search "
            "\"<keywords>\"; there is no `--search-file`, author the keywords "
            "from the safe alphabet and never paste evidence.\n",
            encoding="utf-8",
        )
        link_mention_drift, _ = check_title_safety_rules(
            [TitleSafetyRule(
                consumer="synthetic-local-recipe-link-mention",
                docs=(link_mention_no_clauses,),
                require_search=True,
                local_recipe=True,
            )]
        )
        if not [
            d for d in link_mention_drift
            if d.direction == "missing-title-safety"
            and d.tool == "synthetic-local-recipe-link-mention"
        ]:
            failures.append(
                "a local_recipe consumer that MENTIONS "
                "skills/shared/issue-filing.md in prose but lacks the local "
                "body+title clauses did NOT fire missing-title-safety -- the "
                "loose shared-leaf-link substring still rescues a deliberately-"
                "local consumer onto the link branch, leaving the local title/"
                "body safety free to drift (rf2-wpwckr finding 2 guard hole)."
            )

        # (3e) Synthetic LOCAL-RECIPE-LINK-MENTION-POSITIVE (rf2-wpwckr finding
        #      2): the same local_recipe consumer, still only MENTIONING the
        #      shared path, but now WITH the local body+title (+search) clauses
        #      MUST stay green via the clause branch — proving the link-branch
        #      bypass routes a local-recipe consumer to its clause proof rather
        #      than failing it outright.
        link_mention_with_clauses = td_path / "local-recipe-mentions-link-ok.md"
        link_mention_with_clauses.write_text(
            search_pos.read_text(encoding="utf-8")
            + "\nThis local recipe is kept in sync with "
            "skills/shared/issue-filing.md.\n",
            encoding="utf-8",
        )
        link_mention_ok_drift, _ = check_title_safety_rules(
            [TitleSafetyRule(
                consumer="synthetic-local-recipe-link-mention-ok",
                docs=(link_mention_with_clauses,),
                require_search=True,
                local_recipe=True,
            )]
        )
        if [
            d for d in link_mention_ok_drift
            if d.tool == "synthetic-local-recipe-link-mention-ok"
        ]:
            failures.append(
                "a local_recipe consumer carrying the local body+title+search "
                "clauses (while also mentioning the shared leaf's path) was "
                "flagged -- the link-branch bypass must route a local-recipe "
                "consumer to its CLAUSE proof, not fail it (rf2-wpwckr)."
            )

    # (4) Shipped implementor specifically: GREEN via local clauses, no
    #     allowance. This is the exact state rf2-57b5t0 lands.
    impl_rule = next(
        (r for r in TITLE_SAFETY_RULES if r.consumer == "re-frame2-implementor"),
        None,
    )
    if impl_rule is None:
        failures.append(
            "re-frame2-implementor is no longer in TITLE_SAFETY_RULES -- the "
            "consumer must stay enforced, not dropped."
        )
    else:
        if impl_rule.allowance_bead is not None:
            failures.append(
                "re-frame2-implementor still carries an allowance_bead -- "
                "rf2-57b5t0 removes it so the local clauses enforce the rule."
            )
        # rf2-ij6ulc: the implementor must carry the search-before-filing
        # clause locally — require_search pins it.
        if not impl_rule.require_search:
            failures.append(
                "re-frame2-implementor no longer carries require_search=True -- "
                "its local recipe must keep enforcing the search-before-filing "
                "(dedupe) clause (rf2-ij6ulc)."
            )
        # rf2-wpwckr finding 2: the implementor must be proven via its LOCAL
        # clauses, never the loose shared-leaf-link substring (its recipe
        # mentions the shared path in prose). local_recipe=True bypasses the
        # link branch and forces the clause proof.
        if not impl_rule.local_recipe:
            failures.append(
                "re-frame2-implementor no longer carries local_recipe=True -- "
                "its recipe mentions skills/shared/issue-filing.md in prose, so "
                "without local_recipe the loose link substring would short-"
                "circuit the clause enforcement and let its title/body safety "
                "drift (rf2-wpwckr finding 2)."
            )
        impl_drift, _ = check_title_safety_rules([impl_rule])
        if [
            d for d in impl_drift
            if d.direction == "missing-title-safety"
        ]:
            failures.append(
                "re-frame2-implementor fired missing-title-safety drift with "
                "no allowance -- its local recipe is missing the body or title "
                "clauses (rf2-57b5t0 must add them)."
            )
        if [
            d for d in impl_drift
            if d.direction == "missing-search-clause"
        ]:
            failures.append(
                "re-frame2-implementor fired missing-search-clause drift -- its "
                "local recipe must carry the search-before-filing (dedupe) "
                "clause: `gh issue list --search` + the author-the-keywords-"
                "never-paste shell-safety note (rf2-ij6ulc)."
            )

    # (6) Doc-coverage axis (rf2-l2y4n) — prove it is not vacuous. The shipped
    #     rules must be green; a doc missing one server tool's row MUST fire
    #     missing-doc-row; a doc naming a tool the server dropped MUST fire
    #     stale-doc-row. The fixtures use a synthetic two-tool descriptor in
    #     their own temp dir, so the proof depends on neither the live roster's
    #     size nor the title-safety fixtures above.
    doc_drift, _ = check_doc_coverage_rules(DOC_COVERAGE_RULES)
    if doc_drift:
        failures.append(
            "shipped DOC_COVERAGE_RULES produced drift: "
            + "; ".join(f"{d.direction}:{d.tool}" for d in doc_drift)
            + " -- every server tool needs a transport-index row and every "
            "row needs a live tool."
        )

    with tempfile.TemporaryDirectory() as doc_td:
        doc_td_path = Path(doc_td)

        fake_src = doc_td_path / "descriptors_data.cljs"
        fake_src.write_text(
            '(def descriptors\n'
            '  [{:name "read-widget" :description "..."}\n'
            '   {:name "explain-widget" :description "..."}])\n',
            encoding="utf-8",
        )

        # POSITIVE: every server tool has a row -> green.
        covered = doc_td_path / "covered.md"
        covered.write_text(
            "| MCP tool | Arg signature | Semantics home |\n"
            "|---|---|---|\n"
            "| `read-widget` | `{id}` | ops.md |\n"
            "| `explain-widget` | `{id?}` | ops.md |\n",
            encoding="utf-8",
        )
        covered_drift, _ = check_doc_coverage_rules([DocCoverageRule(
            name="synthetic-covered", server_src=(fake_src,), doc_md=covered,
        )])
        if covered_drift:
            failures.append(
                "a synthetic doc covering every server tool fired drift "
                f"({[d.direction for d in covered_drift]}) -- the doc-coverage "
                "row extraction is broken (rf2-l2y4n)."
            )

        # NEGATIVE: drop one row, leave the prose tool COUNT intact -- exactly
        # the rf2-l2y4n shape (counted + allow-listed + undocumented).
        undocumented = doc_td_path / "undocumented.md"
        undocumented.write_text(
            "The server exposes **2 tools**, all allow-listed.\n\n"
            "| MCP tool | Arg signature | Semantics home |\n"
            "|---|---|---|\n"
            "| `read-widget` | `{id}` | ops.md |\n",
            encoding="utf-8",
        )
        undoc_drift, _ = check_doc_coverage_rules([DocCoverageRule(
            name="synthetic-undocumented", server_src=(fake_src,),
            doc_md=undocumented,
        )])
        if not [
            d for d in undoc_drift
            if d.direction == "missing-doc-row" and d.tool == "explain-widget"
        ]:
            failures.append(
                "a synthetic doc missing one server tool's row did NOT fire "
                "missing-doc-row drift -- the doc-coverage axis is a no-op, "
                "and a shipped+counted+allow-listed tool can stay "
                "undocumented (rf2-l2y4n)."
            )

        # NEGATIVE: a row naming a tool the server does not expose.
        phantom = doc_td_path / "phantom.md"
        phantom.write_text(
            "| MCP tool | Arg signature | Semantics home |\n"
            "|---|---|---|\n"
            "| `read-widget` | `{id}` | ops.md |\n"
            "| `explain-widget` | `{id?}` | ops.md |\n"
            "| `retired-widget` | `{}` | ops.md |\n",
            encoding="utf-8",
        )
        phantom_drift, _ = check_doc_coverage_rules([DocCoverageRule(
            name="synthetic-phantom", server_src=(fake_src,), doc_md=phantom,
        )])
        if not [
            d for d in phantom_drift
            if d.direction == "stale-doc-row" and d.tool == "retired-widget"
        ]:
            failures.append(
                "a synthetic doc row naming a tool the server does not expose "
                "did NOT fire stale-doc-row drift -- a row can outlive its "
                "tool (rf2-l2y4n)."
            )

    if failures:
        for f in failures:
            _emit_error(f"self-test: {f}", ci)
        return 1

    print("self-test: PASS "
          "(title-safety: shipped consumers green with no allowance; synthetic "
          "negative fires; synthetic local-clauses positive stays green; "
          "search-clause negative fires + positive stays green; implementor "
          "enforced via its local body+title+search clauses. doc-coverage: "
          "shipped rules green; synthetic covered stays green; missing row and "
          "phantom row both fire).")
    return 0


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
    parser.add_argument("--self-test", action="store_true",
                        help="Run the title-safety (rf2-4kyg6) + doc-coverage (rf2-l2y4n) "
                             "self-tests and exit. Proves the all-consumers backstop fires "
                             "when an allowance is removed, that link-presence consumers "
                             "stay green, and that the doc-coverage axis fires on both a "
                             "missing row and a phantom row.")
    args = parser.parse_args(list(argv))

    ci = args.ci or _is_ci()

    if args.self_test:
        return _run_self_test(ci)

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

    # Title-safety axis (rf2-4kyg6 finding 1) — all-current-consumers
    # backstop for the shared gh-issue shell-safety contract. Same
    # accumulator, mapping=None; Drift.message handles the
    # missing-title-safety direction.
    try:
        title_drift, title_info = check_title_safety_rules(TITLE_SAFETY_RULES)
    except FileNotFoundError as e:
        _emit_error(str(e), ci)
        saw_setup_error = True
        title_drift, title_info = [], []
    all_info.extend(title_info)
    for d in title_drift:
        all_drift.append((None, d))

    # Doc-coverage axis (rf2-l2y4n) — every server tool must have a table row
    # in the reference that routes to its semantics home. Same accumulator,
    # mapping=None; Drift.message handles both directions.
    try:
        doc_drift, doc_info = check_doc_coverage_rules(DOC_COVERAGE_RULES)
    except FileNotFoundError as e:
        _emit_error(str(e), ci)
        saw_setup_error = True
        doc_drift, doc_info = [], []
    all_info.extend(doc_info)
    for d in doc_drift:
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
