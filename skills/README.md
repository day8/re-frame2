# skills/

This directory holds Claude Code / agent-shaped skills for re-frame2. Each
subdirectory is a self-contained skill with its own `SKILL.md`, scripts,
and packaging metadata.

These skills were consolidated here from formerly-standalone repositories
during the `v0.0.1.alpha` prep — keeping the skill source colocated with the
re-frame2 surfaces it consumes, so the spec, implementation, and tooling
travel together.

The docs-site landing page mirrors this index at
[`docs/skills/index.md`](../docs/skills/index.md) — same eight skills, same
picking-the-right-one decision flow, hosted on the mkdocs site.

## Current skills

re-frame2 ships **eight** skills, grouped by the situation they cover:

### Authoring on the CLJS reference

- **[`re-frame2/`](./re-frame2/)** — author re-frame2 ClojureScript
  application code. Events, subscriptions, effects, frames, state machines,
  schemas, stories, routing, and the canonical patterns (RemoteData, Forms,
  Boot, WebSocket, NineStates, ManagedHTTP, AsyncEffect, LongRunningWork,
  StaleDetection). Scaffolding, leaf content under `reference/`,
  `patterns/`, and `decision-trees/`, and the integration pass have all
  landed — the skill is alpha-ready.

- **[`re-frame2-setup/`](./re-frame2-setup/)** — scaffold a fresh
  re-frame2 ClojureScript project by hand. Walks the author from an empty
  directory to a working `shadow-cljs watch` counter via the canonical
  seven-step path. Complementary to the generator template under
  [`tools/template/`](../tools/template/): use the template
  (`clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app`)
  when you want a one-shot scaffold; reach for this skill when you're
  adding re-frame2 to an existing CLJS project, or when you want to
  understand each step the template performs.

- **[`re-frame-migration/`](./re-frame-migration/)** — migrate an existing
  re-frame v1.x ClojureScript codebase to re-frame2. Drives the
  six-phase migration workflow from [`migration/from-re-frame-v1/README.md`](../migration/from-re-frame-v1/README.md):
  applies Type A (mechanical) M-rules without asking, flags Type B
  (judgment-call) rewrites for the author. The migration corpus
  is the authoritative breaking-change list; the skill routes and
  sequences but never duplicates it.

- **[`re-frame2-improver/`](./re-frame2-improver/)** — critique-mode for
  **existing** re-frame2 ClojureScript code. Reviews a body of source
  files (or a user-supplied snippet) against a small catalogue of
  re-frame2 anti-patterns, surfaces concrete findings cross-linked to
  canonical idioms under `skills/re-frame2/patterns/`, and may propose
  inline fixes via `Edit`. Activates only on explicit pull ("review my
  re-frame2 code", "any anti-patterns?", "audit against best
  practices"); a body of re-frame2 source must be in scope.

### Implementing the framework

- **[`re-frame2-implementor/`](./re-frame2-implementor/)** — guide an
  engineer **building a new re-frame2 implementation** — a port to a
  different host language or substrate, not an application built on the
  CLJS reference. Two-phase workflow: Phase 1 locks the host-language,
  substrate, scope, and primitive decisions; Phase 2 walks the EP corpus
  in dependency order with `spec/conformance/` as the acceptance test.

### Live-runtime devtools & pair programming

- **[`re-frame2-causa/`](./re-frame2-causa/)** — read-only tour of
  **Causa**, the re-frame2 in-app devtools panel. Answers how to *launch*
  Causa (true-inline panel, pop-out, programmatic `init!`, wired hotkeys,
  the Dynamic ↔ Static mode toggle) and *which tab shows X* — across the
  8 Dynamic event-spine tabs (Event / App DB / View / Trace / Machines /
  Machines Canvas / Routing / Issues) and the 5 Static registry-browse
  tabs (Machines / Routes / Schemas / Flows / Interceptors). Causa owns
  the *seeing*; `re-frame2-pair` owns the *driving*.

- **[`re-frame2-pair/`](./re-frame2-pair/)** — pair-program with a live
  re-frame2 application. Attach to a running shadow-cljs build via nREPL,
  inspect `app-db`, dispatch events, hot-swap handlers, trace the six
  dominoes, walk the per-frame epoch history, time-travel via
  `restore-epoch`. Consumes only re-frame2's own Tool-Pair surfaces —
  no re-frame-10x dependency. The runtime helper namespace ships into
  consumer apps via shadow-cljs `:devtools :preloads`; there is no
  per-session cljs-eval inject step.

- **[`re-frame2-pair-retro/`](./re-frame2-pair-retro/)** — meta-skill
  for `re-frame2-pair`. Retrospects on a pair-programming session,
  identifies friction and wasted effort, and proposes improvements to
  `re-frame2-pair` itself (or routes upstream beads to re-frame2 when
  the friction is framework-shaped rather than tool-shaped). Activates
  on explicit pull ("retro on this pair session", "review my pair
  session") or on a post-error within a live re-frame2-pair session.

## Picking the right one

- **Starting from nothing?** → `re-frame2-setup` (or the
  [`tools/template/`](../tools/template/) generator if you want one
  command). When the counter mounts, switch to `re-frame2`.
- **Existing v1 codebase?** → `re-frame-migration`. When the migration
  report is signed off, switch to `re-frame2`.
- **Writing new code in an existing v2 project?** → `re-frame2`.
- **Critiquing existing v2 code on explicit pull (anti-pattern audit,
  "any improvements?")?** → `re-frame2-improver`.
- **Building a NEW re-frame2 implementation in a different host language
  or substrate?** → `re-frame2-implementor`.
- **Touring the Causa devtools panel — how to launch it, or which tab /
  mode shows X?** → `re-frame2-causa`.
- **Debugging or pairing with a running v2 app?** → `re-frame2-pair`.
- **Just finished a pairing session and noticed friction (or hit an
  error mid-session and want a post-mortem)?** → `re-frame2-pair-retro`.

## Skill routing — single source

Each per-skill `SKILL.md` formerly carried its own "When NOT to use this
skill" table mapping the other skills' triggers to a route. Those
cross-referenced cells drift in lockstep. **This section is the
single source of truth**; per-skill `SKILL.md` files point here instead
of duplicating.

### Trigger → skill

| Author / engineer intent | Trigger phrasing / surface | Route to |
|---|---|---|
| Bootstrap a brand-new re-frame2 ClojureScript project from nothing (or an empty CLJS project with shadow-cljs/Clojure but zero re-frame2 wiring) | "start a re-frame2 project", "scaffold re-frame2", "hello-world re-frame2 app", "new re-frame2 app", build failure on a freshly-scaffolded project tracing to missing `re-frame.core` / `re-frame.adapter.reagent` wiring | [`re-frame2-setup/`](./re-frame2-setup/) |
| Write new application code on a working re-frame2 project | events, subs, fx, cofx, frames, state machines, schemas, stories, routing, canonical patterns; `reg-event-*`, `reg-sub`, `reg-fx`, `reg-machine`, `reg-view`, `reg-route`, `reg-story`, `reg-app-schema`, `dispatch`, `subscribe`, `app-db` | [`re-frame2/`](./re-frame2/) |
| Migrate an existing re-frame v1.x ClojureScript codebase to re-frame2 | "migrate to re-frame2", "upgrade re-frame", "v1 to v2", "what breaks under re-frame2", or any v1 surface (`re-frame.db`, `dispatch-with`, `reg-global-interceptor`, `reg-sub-raw`, `^:flush-dom`, `re-frame.alpha`, `re-frame-test`, old top-level `:dispatch` / `:dispatch-n` effect-map keys) | [`re-frame-migration/`](./re-frame-migration/) |
| Tour the **Causa** in-app devtools panel — how to launch it (true-inline, pop-out, programmatic `init!`, hotkeys, the Dynamic ↔ Static mode toggle) or **which tab / mode surfaces X** | "open Causa", "where is X in Causa", "which Causa panel/tab shows…", "Causa Static mode", "browse registered machines/routes/schemas in Causa", "Ctrl+Shift+C", "Causa hotkey", "Causa popout", "Causa machine inspector / issues feed" — the user wants to *read* the panel, not drive a runtime | [`re-frame2-causa/`](./re-frame2-causa/) |
| Pair-program against a **running** re-frame2 application — attach to a live shadow-cljs nREPL, inspect a frame's `app-db`, dispatch events, hot-swap handlers, walk traces / epochs, time-travel with `restore-epoch` | live runtime is involved; user is operating on (or wants to operate on) a running local app | [`re-frame2-pair/`](./re-frame2-pair/) |
| Retrospect on a `re-frame2-pair` session and turn it into prioritised improvement ideas for the pair-tool skill, scripts, MCP surface, or upstream `re-frame2` Tool-Pair contract | concrete `re-frame2-pair` session in the conversation **or** a user-supplied recap of one; user explicitly asks for a retro ("retro on this pair session", "review my re-frame2-pair session", "draft a bead about that"), OR a post-error post-mortem trigger fires within a live re-frame2-pair session | [`re-frame2-pair-retro/`](./re-frame2-pair-retro/) |
| Build a **new re-frame2 implementation** in a different host language or substrate (TypeScript, F# / Fable, Kotlin/JS, Squint, Scala.js, PureScript, ReScript, Python, Rust, native UI, terminal, …) — porting the pattern, not building an app on the CLJS reference | "port re-frame2", "implement re-frame2 in &lt;language&gt;", "second re-frame2 implementation", "implementor checklist", "conformance corpus", or any prompt about building re-frame2 itself | [`re-frame2-implementor/`](./re-frame2-implementor/) |
| Critique **existing** re-frame2 ClojureScript code on explicit pull — review a body of source files (or a user-supplied snippet) against the re-frame2 anti-pattern catalogue, surface findings cross-linked to canonical idioms, and optionally propose inline fixes | "review my re-frame2 code for anti-patterns", "audit this against re-frame2 best practices", "any improvements?", "is there a better re-frame2 pattern here", "spot any anti-patterns" — **and** a body of re-frame2 source is in scope (read, edited, or supplied as a snippet) | [`re-frame2-improver/`](./re-frame2-improver/) |
| Read re-frame2's full API reference, EP design rationale, principles, conventions, or spec corpus | spec / architecture / design discussion without a running app or active authoring task | [`SKILL-REDIRECT.md`](../SKILL-REDIRECT.md) |

### Disqualifiers (vocabulary alone is not enough)

- Vocabulary matches without context don't justify activation. *"retro"*, *"what went wrong"*, *"improve workflow"*, *"any improvements?"* don't unlock `re-frame2-pair-retro` unless a real `re-frame2-pair` session has occurred in the conversation (or the user supplies a recap).
- Spec-reading, architecture questions, design discussion belong to [`SKILL-REDIRECT.md`](../SKILL-REDIRECT.md) — not to `re-frame2-pair` (no runtime) and not to `re-frame2` (not authoring).
- Generic debugging retrospectives, post-mortems on shell sessions, IDE workflows, or test-suite runs are out of scope for `re-frame2-pair-retro` — there is no pair-tool surface to improve.
- Mid-session pair work stays in `re-frame2-pair`; switch to `re-frame2-pair-retro` only when the user explicitly asks for a retro, or for a post-error post-mortem within the re-frame2-pair session — not as a default mode during routine pair work.
- "Adding re-frame2 to an existing app with other state management or non-trivial code" is an authoring task — route to `re-frame2/`, not `re-frame2-setup/`. Setup is greenfield-only and exits once the counter mounts.
- **Causa vs re-frame2-pair: read vs drive.** `re-frame2-causa` is a *read-only tour of the panel* — how to launch it and which tab/mode shows X. The moment the user wants to *operate* on a running runtime (dispatch an event, mutate `app-db`, hot-swap a handler, time-travel), that is `re-frame2-pair`, even if the word "Causa" appears in the prompt.

### Routing for friction found mid-pair retro

`re-frame2-pair-retro` proposals route as follows:

- **Pair-tool friction** (SKILL.md wording, scripts, recipes, structured-results shapes, attach/discovery, cross-platform behavior) → bead against `re-frame2-pair`.
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
historical npm publish mechanics. Deterministic structural tests for
`re-frame2-pair/` and `shared/` are wired into `.github/workflows/test.yml`
only when those skill paths change; behavioural replay fixtures remain
manual/diagnostic and are not required PR coverage.

### Leaf size discipline

Single source of truth for the per-leaf size ceiling — per-skill
`spec/authoring-prompt.md` files defer here instead of restating.

- Each leaf file SHOULD be **≤250 lines AND ≤16 KB** (target ~150 lines /
  ~10 KB). The byte ceiling catches leaves whose long unwrapped prose
  lines fit under the line count but still bloat the per-session token
  load.
- `SKILL.md` orchestrators SHOULD be ≤500 lines (target ~300–400).
- No SKILL → A → B chains; routing is one level deep.
- Catalogue-shaped leaves (e.g. `re-frame2-pair/references/recipes.md`)
  may exceed the ceiling if splitting would multiply file-handle overhead
  without reducing tokens-per-session. Test: would splitting reduce total
  tokens loaded per session?

Corpus stats supporting these numbers: `ai/findings/skill-leaf-size-audit-20260513.md`
(local-only; max 203 L, p95 148 L, median 88 L).

### Published-skill `allowed-tools` baseline (security policy)

A pragmatic least-privilege stance, not a paranoid one. The skills here
are dev productivity tools; trust the explicit invoker, gate accidents
rather than theoretical attacks.

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
  (`re-frame2-pair-retro`, `re-frame2-implementor`).

### Test-fixture discipline — only `re-frame2-pair/` and `shared/` ship tests

Of the skills in this corpus, **only [`re-frame2-pair/`](./re-frame2-pair/)
and [`shared/`](./shared/) ship a `tests/` directory** (see
[`re-frame2-pair/tests/`](./re-frame2-pair/tests/) —
`e2e/`, `fixture/`, `prompts/`, `runtime/`, `shim/` —
and [`shared/tests/`](./shared/tests/) —
`retro_protocol_test.clj` + `fixtures/`). The asymmetry is intentional,
not an oversight. Future skill-authors: do not add a `tests/` dir to a
pure-doc skill on cargo-cult grounds.

**Why re-frame2-pair is the exception.** `re-frame2-pair` is the only skill that
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

**Why `shared/` is the second exception.** `shared/retro-protocol.md`
is a **security boundary**, not just a doc leaf. A prior audit found
four issues there; the prose-only fixes landed, and a regression suite
backstops them so a future silent weakening of the prose doesn't
re-open the boundary. The structural test
([`shared/tests/retro_protocol_test.clj`](./shared/tests/retro_protocol_test.clj))
pins load-bearing phrasings; the document-runnable fixtures
([`shared/tests/fixtures/`](./shared/tests/fixtures/)) cover the
behavioural axis.

**Rule of thumb.** A skill warrants a `tests/` dir iff (a) it ships an
executable surface (scripts, MCP server, runtime helpers, structured
tool-call shapes), or (b) it is a **security boundary** whose prose
locks justify a regression backstop. If the skill is
leaves-plus-orchestrator on a non-security surface, the authoring
conventions are the test.
