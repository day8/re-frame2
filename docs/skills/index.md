# Skills

A **skill** is a package of instructions, reference notes and optional scripts that an AI agent loads when your request matches it. re-frame2 ships nine skills for Claude Code (or any agent that reads Anthropic-format skills). With one loaded, a request like *"add an event that removes an item from the cart"* gets re-frame2's actual API and conventions instead of a guess.

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

If a request spans more than one skill, start with the one that matches best; each skill hands off to the others. The routing rules are kept in one place, [`skills/README.md` §Skill routing](https://github.com/day8/re-frame2/blob/main/skills/README.md#skill-routing--single-source) — edit routing there first.

## Install

In Claude Code, a skill lives in a project's `.claude/skills/<name>/` or globally in `~/.claude/skills/<name>/`. From a re-frame2 clone, the installer links every skill into `~/.claude/skills/` in one step:

```bash
scripts/install-skills.sh                                              # macOS / Linux
powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1    # Windows
```

It links rather than copies — symlinks on macOS/Linux, directory junctions on Windows, no admin needed — because a copy goes stale as the repo changes and Claude Code keeps loading the old version. Running it again is safe. It will not overwrite a non-link copy unless you pass `--force` (`-Force`); `--check` (`-Check`) exits 0 when every skill is linked and current; `--target DIR` (`-Target DIR`) links somewhere other than `~/.claude/skills/`.

The only other route is `npx skills add` against the public repo, which installs one skill directory. Nothing is published to npm and there is no Claude Code plugin marketplace entry; the `package.json` and `.claude-plugin/plugin.json` beside each skill are packaging metadata, not install routes. The full setup is in [`skills/README.md`](https://github.com/day8/re-frame2/blob/main/skills/README.md#installing-link-never-copy).

A few skills need more than the install:

- **re-frame2-pair** needs its MCP server built from a clone, and a small runtime namespace added to the app's dev build — see [its one-time setup](re-frame2-pair.md#one-time-setup).
- **re-frame-migration** and **re-frame2-implementor** read the migration rules or the spec from a local re-frame2 checkout, pinned to a commit or tag you supply, and check that pin before reading anything.
- **re-frame2-setup** needs Java 21+ and the Clojure CLI.
- **reagent-migration** runs its reporter, and **re-frame-migration** its M-73 codemod, through the Clojure CLI.

## Run

Claude Code watches its skill directories, so a running session usually picks up newly linked skills. If they do not appear — for example because `~/.claude/skills/` did not exist when the session started — run `/reload-skills` or start a new session. From then on you usually don't invoke a skill at all: ask in your own words and the matching skill loads itself. To load one explicitly, type its name as a slash command (`/re-frame2-pair`) or name it in the prompt (*"Using re-frame2-pair, trace `[:cart/add 42]`"*).

Each skill's own `SKILL.md`, under [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) in this repo, is the authority on what it does; the pages in this section are short entry points to it.
