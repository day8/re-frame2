# Skills

A **skill** is a package of instructions, reference notes and optional scripts that an AI agent loads when your request matches it. re-frame2 ships nine skills for Claude Code (or any agent that reads Anthropic-format skills). This page helps you choose a skill and install it. Each skill supplies re-frame2-specific recipes and a workflow for its task.

Skills are for handing work to an agent. To learn re-frame2 yourself, read [the guide](../core/introduction.md).

## Which skill do I want?

| If you are… | Use |
|---|---|
| Starting a new project from nothing | [re-frame2-setup](re-frame2-setup.md), then switch to `re-frame2` once the counter mounts |
| Writing or editing code in a re-frame2 project | [re-frame2](re-frame2.md) |
| Asking for a review of existing re-frame2 code | [re-frame2-improver](re-frame2-improver.md) |
| Moving a re-frame v1 codebase to re-frame2 | [re-frame-migration](re-frame-migration.md), then switch to `re-frame2` once the migration report is signed off |
| On re-frame2 already and wanting Fresco for your Reagent views | [reagent-migration](reagent-migration.md) — optional; staying on the Reagent adapter is a complete, supported setup |
| Debugging or pairing with a running re-frame2 app | [re-frame2-pair](re-frame2-pair.md) |
| Just finished a pairing session and hit friction | [re-frame2-pair-retro](re-frame2-pair-retro.md) |
| Looking for the right tab or launch mode in the Xray devtools panel | [re-frame2-xray](re-frame2-xray.md) |
| Building a new re-frame2 implementation — TypeScript, F# (Fable) or another host that compiles to JavaScript and renders through React | [re-frame2-implementor](re-frame2-implementor.md) |

If a request spans more than one skill, start with the one that matches best; each skill hands off to the others. The full routing rules, including the cases this table leaves out, are in [`skills/README.md` §Skill routing](https://github.com/day8/re-frame2/blob/main/skills/README.md#skill-routing--single-source).

## Install

In Claude Code, a skill lives in a project's `.claude/skills/<name>/` or globally in `~/.claude/skills/<name>/`. From a re-frame2 clone, the installer links every skill into `~/.claude/skills/` in one step:

```bash
scripts/install-skills.sh                                              # macOS / Linux
powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1    # Windows
```

The links point at this checkout, so keep it on disk. Updating that checkout updates the installed instructions; installing from another checkout repoints existing links there. The installer uses symlinks on macOS/Linux and directory junctions on Windows, without requiring admin rights. Its options, with the PowerShell spelling in brackets:

- `--check` (`-Check`) verifies without changing the installation: exit 0 means every skill links to this checkout; exit 1 means at least one is missing, copied, or linked elsewhere.
- `--target DIR` (`-Target DIR`) chooses a different skill directory, such as your project's `.claude/skills/`. The checkout's own `skills/` directory is refused as a destination, including through an alias.
- `--force` (`-Force`) deletes non-link copies and replaces them with links. Keep any local edits first. Without force, the installer leaves those copies alone and exits 1; other skills may already have been linked.

The only other route is `npx skills add` against the public repo, which installs one skill directory. Nothing is published to npm and there is no Claude Code plugin marketplace entry; the `package.json` and `.claude-plugin/plugin.json` beside each skill are packaging metadata, not install routes. The full setup is in [`skills/README.md`](https://github.com/day8/re-frame2/blob/main/skills/README.md#installing-link-never-copy).

Installing a skill supplies instructions. Runtime tools are configured separately; for example, live pairing also needs its [MCP server and app preload](re-frame2-pair.md#one-time-setup).

## Run

In a project open in your agent, name the skill and the work you want:

> Using re-frame2, add a `:cart/remove` event that removes an item by id and test it.

Claude Code also exposes skills as slash commands, such as `/re-frame2`. Automatic selection uses the `description` in each `SKILL.md`; other hosts decide discovery and invocation in their own way. If a skill is missing, run the installer's check mode against the same target, then start a new agent session. Installing an individual directory through `npx skills add` does not install its sibling skills: install a handoff target too when you need it.

Each skill's own `SKILL.md`, under [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) in this repo, is the authority on what it does; the pages in this section are short entry points to it.
