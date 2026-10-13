# Releasing re-frame2-pair

> **Skill-internal meta-doc.** This skill's source of truth is
> `re-frame2/skills/re-frame2-pair/`. Releases ride re-frame2's own release
> pipeline; this directory carries no release workflow of its own.

## Release rules

- **Version lockstep.** `package.json` `version` is the single source of
  truth; `.claude-plugin/plugin.json` `"version"` MUST match it. Bump both
  together.
- **Tag format.** Semver, pre-1.0 everything is a pre-release:
  `v0.1.0-alpha.N` / `v0.1.0-beta.N` / `v0.1.0-rc.N` / `v1.0.0`.
- **The MCP server package** (`@day8/re-frame2-pair-mcp`) is released from
  `tools/re-frame2-pair-mcp/`, not from this skill directory.

For the actual release mechanics, follow re-frame2's release pipeline.
