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

- **[`re-frame-pair-retro2/`](./re-frame-pair-retro2/)** — meta-skill
  for `re-frame-pair2`. Retrospects on a pair-programming session,
  identifies friction and wasted effort, and proposes improvements to
  `re-frame-pair2` itself (or routes upstream beads to re-frame2 when
  the friction is framework-shaped rather than tool-shaped). Activates
  on explicit pull ("retro on this pair session", "review my pair
  session") or on a post-error within a live pair2 session.

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
- **Just finished a pairing session and noticed friction (or hit an
  error mid-session and want a post-mortem)?** → `re-frame-pair-retro2`.

## Skill routing — single source

Each per-skill `SKILL.md` formerly carried its own "When NOT to use this
skill" table mapping the other five skills' triggers to a route. Those
30+ cross-referenced cells drift in lockstep. **This section is the
single source of truth**; per-skill `SKILL.md` files point here instead
of duplicating.

### Trigger → skill

| Author / engineer intent | Trigger phrasing / surface | Route to |
|---|---|---|
| Bootstrap a brand-new re-frame2 ClojureScript project from nothing (or an empty CLJS project with shadow-cljs/Clojure but zero re-frame2 wiring) | "start a re-frame2 project", "scaffold re-frame2", "hello-world re-frame2 app", "new re-frame2 app", build failure on a freshly-scaffolded project tracing to missing `re-frame.core` / `re-frame.adapter.reagent` wiring | [`re-frame2-setup/`](./re-frame2-setup/) |
| Write new application code on a working re-frame2 project | events, subs, fx, cofx, frames, state machines, schemas, stories, routing, canonical patterns; `reg-event-*`, `reg-sub`, `reg-fx`, `reg-machine`, `reg-view`, `reg-route`, `reg-story`, `reg-app-schema`, `dispatch`, `subscribe`, `app-db` | [`re-frame2/`](./re-frame2/) |
| Migrate an existing re-frame v1.x ClojureScript codebase to re-frame2 | "migrate to re-frame2", "upgrade re-frame", "v1 to v2", "what breaks under re-frame2", or any v1 surface (`re-frame.db`, `dispatch-with`, `reg-global-interceptor`, `reg-sub-raw`, `^:flush-dom`, `re-frame.alpha`, `re-frame-test`, old top-level `:dispatch` / `:dispatch-n` effect-map keys) | [`re-frame-migration/`](./re-frame-migration/) |
| Pair-program against a **running** re-frame2 application — attach to a live shadow-cljs nREPL, inspect a frame's `app-db`, dispatch events, hot-swap handlers, walk traces / epochs, time-travel with `restore-epoch` | live runtime is involved; user is operating on (or wants to operate on) a running local app | [`re-frame-pair2/`](./re-frame-pair2/) |
| Retrospect on a `re-frame-pair2` session and turn it into prioritised improvement ideas for the pair-tool skill, scripts, MCP surface, or upstream `re-frame2` Tool-Pair contract | concrete `re-frame-pair2` session in the conversation **or** a user-supplied recap of one; user explicitly asks for a retro ("retro on this pair session", "review my re-frame-pair2 session", "draft a bead about that"), OR a post-error post-mortem trigger fires within a live pair2 session | [`re-frame-pair-retro2/`](./re-frame-pair-retro2/) |
| Build a **new re-frame2 implementation** in a different host language or substrate (TypeScript, F# / Fable, Kotlin/JS, Squint, Scala.js, PureScript, ReScript, Python, Rust, native UI, terminal, …) — porting the pattern, not building an app on the CLJS reference | "port re-frame2", "implement re-frame2 in &lt;language&gt;", "second re-frame2 implementation", "implementor checklist", "conformance corpus", or any prompt about building re-frame2 itself | [`re-frame2-implementor/`](./re-frame2-implementor/) |
| Critique **existing** re-frame2 ClojureScript code on explicit pull — review a body of source files (or a user-supplied snippet) against the re-frame2 anti-pattern catalogue, surface findings cross-linked to canonical idioms, and optionally propose inline fixes | "review my re-frame2 code for anti-patterns", "audit this against re-frame2 best practices", "any improvements?", "is there a better re-frame2 pattern here", "spot any anti-patterns" — **and** a body of re-frame2 source is in scope (read, edited, or supplied as a snippet) | [`re-frame2-improver/`](./re-frame2-improver/) |
| Read re-frame2's full API reference, EP design rationale, principles, conventions, or spec corpus | spec / architecture / design discussion without a running app or active authoring task | [`SKILL-REDIRECT.md`](../SKILL-REDIRECT.md) |

### Disqualifiers (vocabulary alone is not enough)

- Vocabulary matches without context don't justify activation. *"retro"*, *"what went wrong"*, *"improve workflow"*, *"any improvements?"* don't unlock `re-frame-pair-retro2` unless a real `re-frame-pair2` session has occurred in the conversation (or the user supplies a recap).
- Spec-reading, architecture questions, design discussion belong to [`SKILL-REDIRECT.md`](../SKILL-REDIRECT.md) — not to `re-frame-pair2` (no runtime) and not to `re-frame2` (not authoring).
- Generic debugging retrospectives, post-mortems on shell sessions, IDE workflows, or test-suite runs are out of scope for `re-frame-pair-retro2` — there is no pair-tool surface to improve.
- Mid-session pair work stays in `re-frame-pair2`; switch to `re-frame-pair-retro2` only when the user explicitly asks for a retro, or for a post-error post-mortem within the pair2 session — not as a default mode during routine pair work.
- "Adding re-frame2 to an existing app with other state management or non-trivial code" is an authoring task — route to `re-frame2/`, not `re-frame2-setup/`. Setup is greenfield-only and exits once the counter mounts.

### Routing for friction found mid-pair retro

`re-frame-pair-retro2` proposals route as follows:

- **Pair-tool friction** (SKILL.md wording, scripts, recipes, structured-results shapes, attach/discovery, cross-platform behavior) → bead against `re-frame-pair2`.
- **Framework / Tool-Pair contract friction** (missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coord annotation gaps, schema-reflection shortcomings) → bead against `re-frame2` (upstream).

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

### Leaf size discipline

Single source of truth for the per-leaf size ceiling — per-skill
`spec/authoring-prompt.md` files defer here instead of restating.

- Each leaf file SHOULD be **≤250 lines AND ≤16 KB** (target ~150 lines /
  ~10 KB). The byte ceiling catches leaves whose long unwrapped prose
  lines fit under the line count but still bloat the per-session token
  load.
- `SKILL.md` orchestrators SHOULD be ≤500 lines (target ~300–400).
- No SKILL → A → B chains; routing is one level deep.
- Catalogue-shaped leaves (e.g. `re-frame-pair2/references/recipes.md`)
  may exceed the ceiling if splitting would multiply file-handle overhead
  without reducing tokens-per-session. Test: would splitting reduce total
  tokens loaded per session?

Corpus stats supporting these numbers: `ai/findings/skill-leaf-size-audit-20260513.md`
(local-only; max 203 L, p95 148 L, median 88 L).

### Published-skill `allowed-tools` baseline (security policy)

Per the closed decision under rf2-hpkkx — a pragmatic least-privilege
stance, not a paranoid one. The skills here are dev productivity tools;
trust the explicit invoker, gate accidents rather than theoretical
attacks.

- **Wildcards on routine commands are fine.** `Bash(npm *)`,
  `Bash(npx *)`, `Bash(clojure *)`, `Bash(shadow-cljs *)`,
  `Bash(rg *)`, `Bash(gh issue *)`, `Bash(gh pr *)`, `Bash(git *)`
  are all acceptable in published-skill frontmatter.
- **No `Bash(bd *)` in published skill frontmatter.** `bd` (beads) is
  the re-frame2 monorepo's internal tracker — it has no place in
  skills shipped to consumer projects. Cross-repo side effects from
  skills file against **the target repo's GitHub issues** via
  `gh issue create` (see the shell-safety pattern below).
- **Avoid wildcards on truly dangerous tools.** Never grant
  `Bash(*)`, `Bash(sudo *)`, `Bash(rm -rf *)`, or equivalent. If a
  skill needs a destructive shell action, name the exact command.
- **Install from tags, not from SHAs.** Skill installation guidance
  may point at `main` or a release tag — no SHA-pin requirement.
  Latest-stable is the default for remote inputs; the explicit
  invoker can override.
- **nREPL is dev-only and binds to localhost.** Any skill that walks
  the author through enabling nREPL (currently `re-frame2-setup`)
  carries a one-line reminder that nREPL is a remote-evaluation
  surface and must stay bound to `localhost` in dev.
- **Shell-safety pattern for transcript-derived text.** When a skill
  composes a shell command from text drawn out of the conversation —
  transcripts, error traces, user-supplied recaps — pass the body via
  stdin or a here-doc, never via direct interpolation. Canonical
  shape:

  ```bash
  cat > /tmp/issue-body.md <<'EOF'
  …transcript-derived body…
  EOF
  gh issue create --title "<short title>" --body "$(cat /tmp/issue-body.md)"
  ```

  Single-quoted here-doc delimiter (`<<'EOF'`) so the shell doesn't
  expand `$`, `` ` ``, or `\` inside the body. The skill files
  affected by this pattern are the retro / improvement-filing skills
  (`re-frame-pair-retro2`, `re-frame2-implementor`).

### Test-fixture discipline — only `re-frame-pair2/` ships tests

Of the skills in this corpus, **only [`re-frame-pair2/`](./re-frame-pair2/)
ships a `tests/` directory** (see [`re-frame-pair2/tests/`](./re-frame-pair2/tests/)
— `e2e/`, `fixture/`, `prompts/`, `runtime/`, `shim/`). The asymmetry is
intentional, not an oversight. Future skill-authors: do not add a `tests/`
dir to a pure-doc skill on cargo-cult grounds.

**Why pair2 is the exception.** `re-frame-pair2` is the only skill that
drives a **live runtime** — it attaches to a running shadow-cljs nREPL,
mutates `app-db`, dispatches events, hot-swaps handlers, and reads from
the epoch buffer. That behaviour is testable in the conventional sense:
spin up a fixture app, run the tool surface, assert observable effects.
Regressions in the runtime helper namespace or the Tool-Pair consumer
contract show up as test failures; the fixtures exist to catch them.

**Why the other skills don't ship tests.** Every other skill in this
corpus is **pure documentation** — orchestrator `SKILL.md` plus reference
leaves under `reference/`, `patterns/`, `decision-trees/`, etc. There is
no runtime surface to assert against. The quality gate for pure-doc
skills is the authoring conventions catalogued elsewhere in this README
(leaf size discipline, single-source routing, no SKILL → A → B chains)
plus orchestrator review against the bead corpus. Adding a `tests/`
directory to a pure-doc skill would test prose, not behaviour.

**Rule of thumb.** A skill warrants a `tests/` dir iff it ships an
executable surface (scripts, MCP server, runtime helpers, structured
tool-call shapes). If the skill is leaves-plus-orchestrator, the
authoring conventions are the test.
