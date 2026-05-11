# skills/

This directory holds Claude Code / agent-shaped skills for re-frame2. Each
subdirectory is a self-contained skill with its own `SKILL.md`, scripts,
and packaging metadata.

These skills were consolidated here from formerly-standalone repositories
during the `v0.0.1.alpha` prep — keeping the skill source colocated with the
re-frame2 surfaces it consumes, so the spec, implementation, and tooling
travel together.

## Current skills

- **[`re-frame-pair2/`](./re-frame-pair2/)** — pair-program with a live
  re-frame2 application. Attach to a running shadow-cljs build via nREPL,
  inspect `app-db`, dispatch events, hot-swap handlers, trace the six
  dominoes, and consume re-frame2's Tool-Pair surfaces directly. No
  re-frame-10x dependency.

- **[`re-frame-pair-improver2/`](./re-frame-pair-improver2/)** — meta-skill
  for `re-frame-pair2`. Reviews a pair-programming session, identifies
  friction and wasted effort, and proposes improvements to `re-frame-pair2`
  itself (or routes upstream beads to re-frame2 when the friction is
  framework-shaped rather than tool-shaped).

## Layout convention

Each skill subdir contains, at minimum:

- `SKILL.md` — the skill description Claude loads on invocation.
- `README.md` — human-facing overview.
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata.
- `package.json` — npm packaging metadata (skill is also distributable as
  an Agent Skill via `npx skills add`).

Skills do not run independently of re-frame2's CI; their workflows have
been removed in favour of release coordination through re-frame2's own
release pipeline. See each skill's `RELEASING.md` (where present) for
historical npm publish mechanics.
