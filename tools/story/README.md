# re-frame2-story

> `day8/re-frame2-story` — a Storybook-class component playground for re-frame2.
> Implements [`spec/007-Stories.md`](../../spec/007-Stories.md).
> Implementation contract: [`spec/`](./spec/).

re-frame2-story is the component-development surface for re-frame2 apps. It
takes the same primitives you already use — frames, events, subscriptions,
effects, schemas, traces, epochs — and surfaces them as an interactive
playground:

- Each variant runs in its **own frame** (`spec/002`) — no state leaks between
  scenarios; what you see is what you'd get in production.
- Each variant is **EDN-shaped data**, not a function. Variants round-trip
  through MCP, visual-regression services, and the agent input pipeline.
- Each variant ships with **schema-derived controls** (`spec/010`),
  **assertion-vocabulary play sequences** (`:rf.assert/*`), and a
  **content-hashed snapshot identity** for visual-regression keying.
- **Mock anything, not just the network.** Storybook needs a separate addon
  for each fake (MSW for HTTP, custom decorators for analytics, shim libs for
  storage, …). Story has it in **one primitive**: `force-fx-stub` stubs any
  effect handler you registered with `reg-fx` in three lines of variant body.
  See [`spec/005-SOTA-Features.md`](./spec/005-SOTA-Features.md)
  §`force-fx-stub`.
- The story tool **embeds re-frame-10x's epoch panel** as a registered story
  panel — time-travel via `restore-epoch` is a UI affordance, not a
  reimplementation.
- The `:test` mode pane ships a **play step-debugger** — step / pause /
  rewind / step-back / breakpoint controls over a variant's `:play`
  sequence (see [`spec/009-Test-Mode.md` §Play step-debugger](spec/009-Test-Mode.md#play-step-debugger-rf2-ulw5m)).
  Storybook's Interactions panel ships these for `play()` functions;
  Story's parity surface drives the same controls over the
  `:rf.assert/*`-bearing play vector, with the canvas re-rendering against
  each step's app-db.

The agent-facing MCP surface ships as a separate artefact at
[`tools/story-mcp/`](../story-mcp/) (`day8/re-frame2-story-mcp`) so the story
core jar does not transitively pull stdio / JSON-RPC machinery.

## Authoring example

```clojure
(ns app.stories.login-form
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]
            [app.auth.views :refer [login-form]]))

(story/reg-story :story.auth.login-form
  {:doc        "The login form component."
   :component  login-form
   :decorators [[:centered-layout]
                [:theme :light]]
   :args       {:placeholder  "you@example.com"
                :submit-label "Sign in"}
   :tags       #{:dev :docs}})

(story/reg-variant :story.auth.login-form/empty
  {:doc    "Fresh form, nothing entered."
   :events [[:auth/initialise]]})

(story/reg-variant :story.auth.login-form/validation-error
  {:doc    "Invalid email shown inline after submit."
   :events [[:auth/initialise]
            [:auth/email-changed "not-an-email"]
            [:auth/login-pressed]]
   :play   [[:rf.assert/path-equals [:auth :status] :rejected]
            [:rf.assert/no-warnings]]
   :tags   #{:dev :docs :test}})
```

Every variant body is plain data — no fn-slots, no closures. The variant id
grammar `:story.<path>/<variant>` is locked at the spec level.

## How to install

> v1.0 is in active development under [rf2-u6fb](../../). This README
> documents the surface that lands with v1.0; the artefact is not yet on
> Clojars. Once published:

```clojure
;; deps.edn
{:deps {day8/re-frame2-story {:mvn/version "..."}}}

;; Optional — agent surface
{:deps {day8/re-frame2-story-mcp {:mvn/version "..."}}}
```

re-frame2-story DCEs under `:advanced` builds — `reg-story` / `reg-variant`
macros elide entirely, leaving the production bundle with zero Story bytes.
See [`spec/005-SOTA-Features.md`](./spec/005-SOTA-Features.md) §Production
elision.

## Where the depth lives

The substantive implementation contract is decomposed into
[`spec/`](./spec/), per the per-tool spec-folder convention in
[`tools/README.md`](../README.md).

| File | Covers |
|---|---|
| [`spec/000-Vision.md`](./spec/000-Vision.md) | What Story is, what it isn't, how it relates to `spec/007-Stories.md`. |
| [`spec/001-Authoring.md`](./spec/001-Authoring.md) | The seven `reg-*` macros; EDN-first variant contract; inclusion tags; source-coord stamping. |
| [`spec/002-Runtime.md`](./spec/002-Runtime.md) | Per-variant frame allocation; args precedence; decorator composition; the four-phase loader lifecycle; `run-variant` and friends. |
| [`spec/003-Render-Shell.md`](./spec/003-Render-Shell.md) | The UI shell (sidebar / canvas / controls / workspace / embedded Causa inspector); the five workspace layouts; multi-substrate side-by-side. |
| [`spec/004-Assertions.md`](./spec/004-Assertions.md) | The seven canonical `:rf.assert/*` events; record-don't-throw semantics; play-sequence execution; the assertion-side `force-fx-stub` interaction. |
| [`spec/005-SOTA-Features.md`](./spec/005-SOTA-Features.md) | `force-fx-stub` (mock anything, not just the network); layout-debug trio; a11y; QR share; multi-substrate; 10x embed; v1.1 deferrals; production elision. |
| [`spec/006-MCP-Surface.md`](./spec/006-MCP-Surface.md) | The boundary between Story and `tools/story-mcp/`. |
| [`spec/Principles.md`](./spec/Principles.md) | Design principles (EDN-first, no fn-slots, production-elision strict, etc.). |
| [`spec/API.md`](./spec/API.md) | Consolidated public API surface. |
| [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md) | WHY each major decision was made. |
| [`spec/findings/`](./spec/findings/) | The Phase-1 and Phase-2 research that informed the design (committed audit trail). |

## Browser testbed guardrails

Story browser tests run in developer worktrees and CI jobs that may overlap.
When running the feature-load gate locally, set a unique
`STORY_FEATURE_LOAD_PORT` instead of relying on the default `8031`.

The browser testbeds also depend on a few non-obvious invariants:

- Story panel `:for` scoping is part of frame/testbed isolation. Do not broaden
  panel visibility unless the isolation behavior is tested.
- Hot-reload fingerprint baselines are setup state, not behavior under test.
  Baseline creation must not itself trigger hot-reload reruns or recorder
  output can include fixture events.
- Snapshot identity assertions should include the browser-visible unsigned hex
  string exposed by the shell, not only JVM-side hash values.

## Status

- **Spec.** [`spec/007-Stories.md`](../../spec/007-Stories.md) — normative.
- **Implementation contract.** [`spec/`](./spec/).
- **Research lineage.**
  - Phase 1 — [`spec/findings/re-frame-2-story-feature-set.md`](./spec/findings/re-frame-2-story-feature-set.md) (rf2-m6tu).
  - Phase 2 — [`spec/findings/re-frame-2-story-sota-refinement.md`](./spec/findings/re-frame-2-story-sota-refinement.md) (rf2-94b0).
  - Architectural decisions resolved 2026-05-11; see
    [`spec/DESIGN-RATIONALE.md`](./spec/DESIGN-RATIONALE.md).

## See also

- [Spec 002 — Frames](../../spec/002-Frames.md) — the primitive Story leans on.
- [Spec 008 — Testing](../../spec/008-Testing.md) — the test-runner surface
  Story integrates with via `run-variant`.
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) — the trace
  bus Story's per-variant ring buffer consumes (rf2-sgdd3 — used by
  schema-validation; Causa, embedded in the RHS, also subscribes).
- [Spec 010 — Schemas](../../spec/010-Schemas.md) — the auto-derivation source
  for controls.
- [Spec 011 — SSR](../../spec/011-SSR.md) — the schema-digest source for
  snapshot identities; dual-pane SSR support.
- [`tools/README.md`](../README.md) — the per-tool layout and bundle-isolation
  contract.
- [Story tutorial](../../docs/story/index.md) — the narrative walkthrough;
  the friendly entry-point for human readers.
- [`tools/story/testbeds/counter_with_stories/`](testbeds/counter_with_stories/) —
  the tool-owned testbed wiring the seven `reg-*` macros against the canonical
  counter app (rf2-p8f2s — relocated from `examples/reagent/`).
