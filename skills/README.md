# skills/

This directory holds Claude Code / agent-shaped skills for re-frame2. Each
subdirectory is a self-contained skill with its own `SKILL.md`, scripts,
and packaging metadata.

These skills were consolidated here from formerly-standalone repositories
during the `v0.0.1.alpha` prep — keeping the skill source colocated with the
re-frame2 surfaces it consumes, so the spec, implementation, and tooling
travel together.

## Installing (link, never copy)

Claude Code loads skills from `~/.claude/skills/<name>/`. **Link the repo
directory in; never `cp -r` it.** A copy snapshots the skill and then drifts
as the repo is maintained — Claude Code keeps loading the stale copy. The
cross-platform installer links *every* skill below into `~/.claude/skills/`
so the active skill is the repo source by construction:

```bash
scripts/install-skills.sh                                              # macOS / Linux (symlinks)
powershell -ExecutionPolicy Bypass -File scripts/install-skills.ps1    # Windows (junctions, no admin)
```

It is idempotent, refuses to clobber a non-link copy without `--force`
(`-Force`), and supports `--check` (`-Check`) to verify the links. See
[`CONTRIBUTING.md`](../CONTRIBUTING.md#skills--link-dont-copy) for the full
contributor setup.

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
  StaleDetection, ReusableComponents, StatefulComponents, FormAction,
  SSR-Loaders). Scaffolding, leaf content under `reference/`,
  `patterns/`, and `decision-trees/`, and the integration pass have all
  landed — the skill is alpha-ready.

- **[`re-frame2-setup/`](./re-frame2-setup/)** — scaffold a fresh
  re-frame2 ClojureScript project by hand. Walks the author from an empty
  directory to a working `shadow-cljs watch` counter via the canonical
  seven-step path. Complementary to the generator template under
  [`tools/template/`](../tools/template/): use the template when you want a
  one-shot scaffold. **Pre-split caveat:** the dedicated
  `github.com/day8/re-frame2-template` repo isn't published yet (the template
  still lives in-monorepo under `tools/template/`), so the published
  `clojure -Tnew create :template io.github.day8/re-frame2-template :name acme/my-app`
  form is post-split/future-only and can't auto-resolve today — pre-release,
  scaffold via the working `:local/root` route against a checkout of this repo
  (`clojure -Sdeps '{:deps {day8/re-frame2-template {:local/root "tools/template"}}}' -Tnew create :template day8/re-frame2-template :name acme/my-app`).
  See [`tools/template/README.md`](../tools/template/README.md) for both routes.
  Reach for this skill when you're
  bootstrapping greenfield — a brand-new app, or an **empty** CLJS project
  (shadow-cljs / Clojure present but **zero re-frame2 wiring**) — or when
  you want to understand each step the template performs. Adding re-frame2
  to a **non-trivial** existing app is an authoring task — route to
  [`re-frame2/`](./re-frame2/) (see the disqualifier below).

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

- **[`re-frame2-xray/`](./re-frame2-xray/)** — read-only tour of
  **Xray**, the re-frame2 in-app devtools panel. Answers how to *launch*
  Xray (true-inline panel, pop-out, programmatic `init!`, wired hotkeys,
  the Dynamic ↔ Static mode toggle) and *which tab shows X* — across the
  9 Dynamic event-spine tabs (Epoch (hero) / app-db / Views / Trace /
  Machine / Routes / Resources / Graph / Modules — Graph being Xray's UI over the
  EP-0014 derivation/process graph, and Modules the EP-0013 module/realm/app-value
  lens (L4-only)) and the 5 Static registry-browse
  tabs (Machines / Routes / Schemas / Flows / Interceptors). There is no
  Issues tab — issues surface inline. Xray owns
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
  `re-frame2-pair` itself (or routes a GitHub issue upstream to
  `day8/re-frame2` when the friction is framework-shaped rather than
  tool-shaped). Activates
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
- **Touring the Xray devtools panel — how to launch it, or which tab /
  mode shows X?** → `re-frame2-xray`.
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
| Write new application code on a working re-frame2 project | events, subs, fx, cofx, frames, state machines, schemas, stories, routing, canonical patterns; `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, `reg-view`, `reg-route`, `reg-story`, `reg-app-schema`, `reg-interceptor`, `dispatch`, `subscribe`, `app-db` | [`re-frame2/`](./re-frame2/) |
| Migrate an existing re-frame v1.x ClojureScript codebase to re-frame2 | "migrate to re-frame2", "upgrade re-frame", "v1 to v2", "what breaks under re-frame2", or any v1 surface (`re-frame.db`, `dispatch-with`, `reg-global-interceptor`, `reg-sub-raw`, `^:flush-dom`, `re-frame.alpha`, `re-frame-test`, old top-level `:dispatch` / `:dispatch-n` effect-map keys) | [`re-frame-migration/`](./re-frame-migration/) |
| Tour the **Xray** in-app devtools panel — how to launch it (true-inline, pop-out, programmatic `init!`, hotkeys, the Dynamic ↔ Static mode toggle) or **which tab / mode surfaces X** | "open Xray", "where is X in Xray", "which Xray panel/tab shows…", "Xray Static mode", "browse registered machines/routes/schemas in Xray", "Ctrl+Shift+C", "Xray hotkey", "Xray popout", "Xray machine inspector", "Xray epoch cascade", "where do Xray issues show up" — the user wants to *read* the panel, not drive a runtime | [`re-frame2-xray/`](./re-frame2-xray/) |
| Pair-program against a **running** re-frame2 application — attach to a live shadow-cljs nREPL, inspect a frame's `app-db`, dispatch events, hot-swap handlers, walk traces / epochs, time-travel with `restore-epoch` | live runtime is involved; user is operating on (or wants to operate on) a running local app | [`re-frame2-pair/`](./re-frame2-pair/) |
| Retrospect on a `re-frame2-pair` session and turn it into prioritised improvement ideas for the pair-tool skill, scripts, MCP surface, or upstream `re-frame2` Tool-Pair contract | concrete `re-frame2-pair` session in the conversation **or** a user-supplied recap of one; user explicitly asks for a retro ("retro on this pair session", "review my re-frame2-pair session", "draft an issue about that"), OR a post-error post-mortem trigger fires within a live re-frame2-pair session | [`re-frame2-pair-retro/`](./re-frame2-pair-retro/) |
| Build a **new re-frame2 implementation** in one of the eight in-scope JS-cross-compile-to-React+VDOM host languages (TypeScript, F# / Fable, Kotlin/JS, Squint, Scala.js, PureScript, Melange / ReScript / Reason — plus ClojureScript, the reference) — porting the pattern, not building an app on the CLJS reference | "port re-frame2", "implement re-frame2 in &lt;language&gt;", "second re-frame2 implementation", "implementor checklist", "conformance corpus", or any prompt about building re-frame2 itself | [`re-frame2-implementor/`](./re-frame2-implementor/) |
| Critique **existing** re-frame2 ClojureScript code on explicit pull — review a body of source files (or a user-supplied snippet) against the re-frame2 anti-pattern catalogue, surface findings cross-linked to canonical idioms, and optionally propose inline fixes | "review my re-frame2 code for anti-patterns", "audit this against re-frame2 best practices", "any improvements?", "is there a better re-frame2 pattern here", "spot any anti-patterns in `cart/handlers.cljs`" — **and** a body of re-frame2 source is in scope: read or edited in the conversation, supplied as a snippet, **or** named as a concrete, resolvable `.cljs` / `.cljc` file or directory path the skill can read (the skill reads the named path before critiquing). A path that doesn't resolve does not establish scope — the skill says so and asks for a snippet rather than fabricate. | [`re-frame2-improver/`](./re-frame2-improver/) |
| Read re-frame2's full API reference, EP design rationale, principles, conventions, or spec corpus | spec / architecture / design discussion without a running app or active authoring task | [`SKILL-REDIRECT.md`](../SKILL-REDIRECT.md) |

### Disqualifiers (vocabulary alone is not enough)

- Vocabulary matches without context don't justify activation. *"retro"*, *"what went wrong"*, *"improve workflow"*, *"any improvements?"* don't unlock `re-frame2-pair-retro` unless a real `re-frame2-pair` session has occurred in the conversation (or the user supplies a recap).
- Spec-reading, architecture questions, design discussion belong to [`SKILL-REDIRECT.md`](../SKILL-REDIRECT.md) — not to `re-frame2-pair` (no runtime) and not to `re-frame2` (not authoring).
- Generic debugging retrospectives, post-mortems on shell sessions, IDE workflows, or test-suite runs are out of scope for `re-frame2-pair-retro` — there is no pair-tool surface to improve.
- Mid-session pair work stays in `re-frame2-pair`; switch to `re-frame2-pair-retro` only when the user explicitly asks for a retro, or for a post-error post-mortem within the re-frame2-pair session — not as a default mode during routine pair work.
- "Adding re-frame2 to an existing app with other state management or non-trivial code" is an authoring task — route to `re-frame2/`, not `re-frame2-setup/`. Setup is greenfield-only and exits once the counter mounts.
- **Xray vs re-frame2-pair: read vs drive.** `re-frame2-xray` is a *read-only tour of the panel* — how to launch it and which tab/mode shows X. The moment the user wants to *operate* on a running runtime (dispatch an event, mutate `app-db`, hot-swap a handler, time-travel), that is `re-frame2-pair`, even if the word "Xray" appears in the prompt.
- **`re-frame2-implementor` is scoped to the eight in-scope hosts.** Per [`spec/000-Vision.md`](../spec/000-Vision.md) §scope footnote, the only in-scope implementation targets are the eight JS-cross-compile-to-React+VDOM host languages (ClojureScript, TypeScript, F# / Fable, Kotlin/JS, Squint, Scala.js, PureScript, Melange / ReScript / Reason). A prompt asking to implement re-frame2 against a **non-React substrate** (Vue, Solid, Svelte, vanilla DOM, native UI, a terminal UI) or a **non-cross-compile-to-JS host** (Python, Ruby, native Rust, Go, server-side Kotlin / Java / Swift) is **out of scope** — a deliberate scope choice, not an oversight. There is no implementation track to sequence: surface the scope footnote and stop, or route the architecture question to [`SKILL-REDIRECT.md`](../SKILL-REDIRECT.md) — do not start Phase 1 / Phase 2 implementation work.

### Routing for friction found mid-pair retro

`re-frame2-pair-retro` is a **published** skill: it files **GitHub issues
against `day8/re-frame2`**, never `bd` beads (`bd` is the monorepo's internal
tracker and has no place in a skill shipped to consumer projects — see
[§Published-skill `allowed-tools` baseline](#published-skill-allowed-tools-baseline-security-policy)).
Both kinds of friction target the same repo and carry the tool-vs-framework
distinction in the **title + body**:

- **Pair-tool friction** — SKILL.md wording, scripts, recipes, structured-results shapes, attach/discovery, cross-platform behavior.
- **Framework / Tool-Pair contract friction** — missing trace events, gaps in `epoch-history` / `restore-epoch` failure modes, missing registrar query surfaces, source-coord annotation gaps, schema-reflection shortcomings. Name the specific Tool-Pair surface from [`../shared/tool-pair-surfaces.md`](./shared/tool-pair-surfaces.md).

**Labels are optional taxonomy, not a filing precondition.** A `--label` (e.g. `pair-mcp`) is added only after confirming the target repo defines it (detect with `gh label list`); on a repo/fork without that label, `gh issue create --label` fails the whole command, so filing falls back to a no-label `gh issue create` and lands regardless. The operational label/filing rules live in [`re-frame2-pair-retro/SKILL.md` §Filing improvements](./re-frame2-pair-retro/SKILL.md#filing-improvements) — this index points there rather than restating them.

## Verification posture — follows role, by design

Each skill's verification posture (what the agent runs, what gates "done")
follows from the **role cell** the skill occupies, not from a uniform
family rule. The postures differ deliberately; uniform wording would buy
incoherence of principle. The single source for the spread:

| Skill | Role | Who executes | What gates "done" | Why |
|---|---|---|---|---|
| `re-frame2-pair` | drive a live runtime | the agent, against the live app | a grounding live read after every change (Pillar 4) | the role *is* driving a runtime — executing against it is the point |
| `re-frame2-implementor` | implementation driver (build the runtime) | the agent, narrowly | a per-EP slice gate from the **port's own** scripts, before calling an EP landed | acceptance criterion *is* spec-conformance; the slice gate operationalises it |
| `re-frame-migration` | migrate v1 code on an existing reference | the **author** in their own env | the author's own build / test / smoke | hard trust boundary — the skill bars the agent from running build/test/smoke in the author's app env |
| `re-frame2` (authoring) | emit authoring recipes | the **human** who pastes the recipe | the human's project gates | Pillar-4 / Q14 lock: no runtime the agent drives, no conformance corpus |
| `re-frame2-setup` | scaffold greenfield | the **author**, following steps | the counter mounts under `shadow-cljs watch` | greenfield bootstrap; no agent-driven runtime |
| `re-frame2-improver` | critique existing code | nobody runs; static critique | findings cross-linked to canonical idioms | review-only; proposes `Edit`s, runs no suite |
| `re-frame2-xray` | read-only tour of the devtools panel | nobody runs; read-only | n/a (read-only tour) | owns the *seeing*, not the *driving* |
| `re-frame2-pair-retro` | retro on a pair session | nobody runs the app; files issues | a filed GitHub issue (tool- vs framework-shaped) | meta-skill over `re-frame2-pair`; no runtime of its own |

The takeaway: **only `re-frame2-implementor` carries a per-EP slice gate**,
and that scoping is by design — it is the only implementation driver whose
acceptance is spec-conformance. The other skills' postures are correct
under "posture follows role", not inconsistent.

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
`re-frame2-pair/`, `shared/`, and `re-frame2-setup/` are wired into
`.github/workflows/test.yml` only when those skill paths change;
behavioural replay fixtures remain manual/diagnostic and are not required
PR coverage.

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
  composes a `gh issue create` body from text drawn out of the
  conversation — transcripts, error traces, user-supplied recaps —
  never interpolate that text inline (where `$`, `` ` ``, or `\` would
  expand). Write the body to a file with the **`Write` tool**, then
  pass it with `gh`'s native `--body-file` flag. Canonical shape:

  1. `Write` the body to a **fresh, per-filing temp file in the host
     OS's temp directory** — never a fixed, shared, predictable name. A
     hard-coded `/tmp/issue-body.md` fails on hosts without a POSIX
     `/tmp` (Windows consumer installs), and its predictable name lets
     two concurrent filings overwrite each other's redacted body. Pick
     the path for the OS and add a per-filing nonce — e.g.
     `${TMPDIR:-/tmp}/re-frame2-issue-$$-$RANDOM.md` on POSIX or
     `$env:TEMP\re-frame2-issue-$([guid]::NewGuid()).md` on Windows
     (PowerShell) — then carry that exact path into `--body-file`. The
     body is transcript-derived markdown — no shell escaping needed;
     nothing expands it.
  2. File it with one `gh issue create` command (the `--body-file` value
     is the exact per-filing path you wrote in step 1, never a re-typed
     fixed name):

     ```bash
     gh issue create --title "<short title>" --body-file "<the per-filing temp path you wrote in step 1>"
     ```

  `--body-file` reads the body verbatim from disk, so no shell
  expansion ever touches the transcript-derived text, and the only
  `Bash` call is a bare `gh issue create` — runnable under the
  restricted `Bash(gh issue *)` permission these skills declare (a
  `cat > file` here-doc or a `--body "$(cat …)"` subshell is **not**,
  since neither is a bare `gh issue` invocation). The skill files
  affected by this pattern are the retro / improvement-filing skills
  (`re-frame2-pair-retro`, `re-frame2-implementor`).

  **The title is a shell argument too — it has no `--body-file`
  equivalent.** `gh issue create` reads the title only from the inline
  `--title "<…>"` argv (there is no `--title-file` flag, and `--editor`
  is interactive and banned), so the file trick that protects the body
  cannot protect the title. The title is therefore safe **only because
  the agent authors it**, not because the shell is bypassed. Two rules,
  both load-bearing:
  - **Never copy transcript-/evidence-derived text into `--title`.** A
    suggested title, a quoted failure string, or a recap line can carry
    `$(…)`, backticks, `"`, `\`, or a newline that the shell expands
    *before* `gh` sees argv — user approval to file an issue is not
    approval to execute session-carried shell syntax. This is the same
    untrusted-evidence boundary as the body, projected onto `--title`.
  - **Author the title from a restricted safe alphabet:** letters,
    digits, spaces, and `- . , / ( ) :` only. Fill the
    `references/issue-template.md` title patterns
    (`Improve <workflow> when <condition>`, …) with summarised,
    agent-written text — no `$`, no backtick, no `"`/`'`, no `\`, no
    newline, and no other shell metacharacter (`;`, `|`, `&`, `<`, `>`,
    `*`, `?`, `[`, `]`, `{`, `}`, `` ` ``, `!`, `~`). Re-read the
    assembled `--title` in the same pre-emission reviewer pass that
    scans the body; if any metacharacter survived, rewrite the title
    before running the command.

  Same threat model covers any other user-influenced argv (`--repo`,
  `--label`): keep them agent-authored / from a fixed set, never
  interpolated from evidence.

### Test-fixture discipline — which skills ship tests

Of the skills in this corpus, **three ship a `tests/` directory**:
[`re-frame2-pair/`](./re-frame2-pair/), [`shared/`](./shared/), and
[`re-frame2-setup/`](./re-frame2-setup/) (see
[`re-frame2-pair/tests/`](./re-frame2-pair/tests/) —
`e2e/`, `fixture/`, `prompts/`, `runtime/`, `shim/` —
[`shared/tests/`](./shared/tests/) —
`retro_protocol_test.clj` + `fixtures/` — and
[`re-frame2-setup/tests/`](./re-frame2-setup/tests/) —
`setup_drift_test.clj`). Each earns its tests under the Rule of thumb
below; the rest of the corpus does not. The asymmetry is intentional,
not an oversight. Future skill-authors: do not add a `tests/` dir to a
pure-doc skill on cargo-cult grounds.

**Why re-frame2-pair is the exception.** `re-frame2-pair` is the only skill that
drives a **live runtime** — it attaches to a running shadow-cljs nREPL,
mutates `app-db`, dispatches events, hot-swaps handlers, and reads from
the epoch buffer. That behaviour is testable in the conventional sense:
spin up a fixture app, run the tool surface, assert observable effects.
Regressions in the runtime helper namespace or the Tool-Pair consumer
contract show up as test failures; the fixtures exist to catch them.

**Why the remaining skills don't ship tests.** The rest of the corpus is
**pure documentation** — orchestrator `SKILL.md` plus reference
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

**Why `re-frame2-setup/` is the third exception.** The setup skill is
prose, but the prose is a **contract boundary**: it teaches load-bearing
generator/build coordinates (the build-discipline lockstep framing,
template-pin parity across substrates, the dev/prod CSP split, the
schema-missing-is-loud contract, the canonical `shadow-cljs.edn` block,
and so on) that must stay in lockstep with the generator template and the
spec. A silent drift in any of those phrasings would teach a setup that
no longer matches the framework. The structural drift guard
([`re-frame2-setup/tests/setup_drift_test.clj`](./re-frame2-setup/tests/setup_drift_test.clj))
pins those coordinates so the drift surfaces as a test failure instead of
shipped misinformation. This is the same Rule-of-thumb clause (b) backstop
as `shared/` — a contract boundary whose prose locks justify a regression
suite — applied to a coordination surface rather than a security one.

**Rule of thumb.** A skill warrants a `tests/` dir iff (a) it ships an
executable surface (scripts, MCP server, runtime helpers, structured
tool-call shapes), or (b) it is a **security or contract boundary** whose
prose locks justify a regression backstop. If the skill is
leaves-plus-orchestrator on a non-boundary surface, the authoring
conventions are the test.
