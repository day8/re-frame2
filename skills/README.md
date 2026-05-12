# skills/

This directory holds Claude Code / agent-shaped skills for re-frame2. Each
subdirectory is a self-contained skill with its own `SKILL.md`, scripts,
and packaging metadata.

These skills were consolidated here from formerly-standalone repositories
during the `v0.0.1.alpha` prep — keeping the skill source colocated with the
re-frame2 surfaces it consumes, so the spec, implementation, and tooling
travel together.

The docs-site landing page mirrors this index at
[`docs/skills/index.md`](../docs/skills/index.md) — same six skills, same
picking-the-right-one decision flow, hosted on the mkdocs site.

## Current skills

re-frame2 ships **six** skills, grouped by the situation they cover:

### Authoring on the CLJS reference

- **[`re-frame2/`](./re-frame2/)** — author re-frame2 ClojureScript
  application code. Events, subscriptions, effects, frames, state machines,
  schemas, stories, routing, and the canonical patterns (RemoteData, Forms,
  Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork,
  StaleDetection). Scaffolding, leaf content under `reference/`,
  `patterns/`, and `decision-trees/`, and the integration pass all landed
  via rf2-qumf and its follow-on beads — the skill is alpha-ready.

- **[`re-frame2-setup/`](./re-frame2-setup/)** — scaffold a fresh
  re-frame2 ClojureScript project by hand. Walks the author from an empty
  directory to a working `shadow-cljs watch` counter via the canonical
  seven-step path. Complementary to the generator template under
  [`tools/template/`](../tools/template/): use the template
  (`clojure -X:project/new :template re-frame2 :name acme/my-app`) when
  you want a one-shot scaffold; reach for this skill when you're adding
  re-frame2 to an existing CLJS project, or when you want to understand
  each step the template performs.

- **[`re-frame-migration/`](./re-frame-migration/)** — migrate an existing
  re-frame v1.x ClojureScript codebase to re-frame2. Drives the
  six-phase migration workflow from [`spec/MIGRATION.md`](../spec/MIGRATION.md):
  applies Type A (mechanical) M-rules without asking, flags Type B
  (judgment-call) rewrites for the author. The MIGRATION.md rule corpus
  is the authoritative breaking-change list; the skill routes and
  sequences but never duplicates it.

### Implementing the framework

- **[`re-frame2-implementor/`](./re-frame2-implementor/)** — guide an
  engineer **building a new re-frame2 implementation** — a port to a
  different host language or substrate, not an application built on the
  CLJS reference. Two-phase workflow: Phase 1 locks the host-language,
  substrate, scope, and primitive decisions; Phase 2 walks the EP corpus
  in dependency order with `spec/conformance/` as the acceptance test.

### Live-runtime pair programming

- **[`re-frame-pair2/`](./re-frame-pair2/)** — pair-program with a live
  re-frame2 application. Attach to a running shadow-cljs build via nREPL,
  inspect `app-db`, dispatch events, hot-swap handlers, trace the six
  dominoes, walk the per-frame epoch history, time-travel via
  `restore-epoch`. Consumes only re-frame2's own Tool-Pair surfaces —
  no re-frame-10x dependency. The runtime helper namespace ships into
  consumer apps via shadow-cljs `:devtools :preloads` (per rf2-7dvg);
  there is no per-session cljs-eval inject step.

- **[`re-frame-pair-improver2/`](./re-frame-pair-improver2/)** — meta-skill
  for `re-frame-pair2`. Reviews a pair-programming session, identifies
  friction and wasted effort, and proposes improvements to `re-frame-pair2`
  itself (or routes upstream beads to re-frame2 when the friction is
  framework-shaped rather than tool-shaped).

## Picking the right one

- **Starting from nothing?** → `re-frame2-setup` (or the
  [`tools/template/`](../tools/template/) generator if you want one
  command). When the counter mounts, switch to `re-frame2`.
- **Existing v1 codebase?** → `re-frame-migration`. When the migration
  report is signed off, switch to `re-frame2`.
- **Writing new code in an existing v2 project?** → `re-frame2`.
- **Building a NEW re-frame2 implementation in a different host language
  or substrate?** → `re-frame2-implementor`.
- **Debugging or pairing with a running v2 app?** → `re-frame-pair2`.
- **Just finished a pairing session and noticed friction?** →
  `re-frame-pair-improver2`.

## Layout convention

Each skill subdir contains, at minimum:

- `SKILL.md` — the skill description Claude loads on invocation.
- `README.md` — human-facing overview (with a breadcrumb back here).
- `.claude-plugin/plugin.json` — Claude Code Plugin packaging metadata.
- `package.json` — npm packaging metadata (skill is also distributable as
  an Agent Skill via `npx skills add`).

Skills do not run independently of re-frame2's CI; their workflows have
been removed in favour of release coordination through re-frame2's own
release pipeline. See each skill's `RELEASING.md` (where present) for
historical npm publish mechanics.
