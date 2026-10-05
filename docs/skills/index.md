# Skills

A **skill** is a package of instructions, reference notes and optional scripts that an AI agent loads when your request matches it. re-frame2 ships nine skills for Claude Code (or any agent that reads Anthropic-format skills). Choose a skill by the work you want it to do. Each skill supplies re-frame2-specific recipes and a workflow for its task.

Skills are for handing work to an agent. To learn re-frame2 yourself, read [the guide](../core/introduction.md).

## Which skill do I want?

| If you are… | Use |
|---|---|
| Starting a new project from nothing | [re-frame2-setup](re-frame2-setup.md), then switch to `re-frame2` once the counter mounts |
| Writing or editing re-frame2 code, or adding re-frame2 to an existing app | [re-frame2](re-frame2.md) |
| Asking for a review of existing re-frame2 code | [re-frame2-improver](re-frame2-improver.md) |
| Moving a re-frame v1 codebase to re-frame2 | [re-frame-migration](re-frame-migration.md), then switch to `re-frame2` once the migration report is signed off |
| On re-frame2 already and wanting Fresco for your Reagent views | [reagent-fresco-migration](reagent-fresco-migration.md) — optional; staying on the Reagent adapter is a complete, supported setup |
| Debugging or pairing with a running re-frame2 app | [re-frame2-pair](re-frame2-pair.md) |
| Just finished a pairing session and hit friction | [re-frame2-pair-retro](re-frame2-pair-retro.md) |
| Looking for the right tab or launch mode in the Xray devtools panel | [re-frame2-xray](re-frame2-xray.md) |
| Building a new re-frame2 implementation — TypeScript, F# (Fable) or another host that compiles to JavaScript and renders through React | [re-frame2-implementor](re-frame2-implementor.md) |

If a request spans more than one skill, start with the one that matches best; each skill hands off to the others. The full routing rules, including the cases this table leaves out, are in [`skills/README.md` §Skill routing](https://github.com/day8/re-frame2/blob/main/skills/README.md#skill-routing--single-source).

## Install

[Install the skills](installation.md#install-from-a-checkout) from a checkout, then check that your agent can find them. The installation page also covers project-local installs and runtime tools.

## Run

[Name the skill and the task](installation.md#run-a-skill) in your request. Each guide above gives a kickoff example and explains what that skill needs to finish the work.

Each skill's own `SKILL.md`, under [`skills/`](https://github.com/day8/re-frame2/tree/main/skills) in this repo, is the authority on what it does; these guides are short entry points to it.
