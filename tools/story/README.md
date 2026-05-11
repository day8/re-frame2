# re-frame2-story

> `day8/re-frame2-story` — a Storybook-class component playground for re-frame2.
> Implements [`spec/007-Stories.md`](../../spec/007-Stories.md).
> Implementation contract: [`IMPL-SPEC.md`](./IMPL-SPEC.md).

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
- The story tool **embeds re-frame-10x's epoch panel** as a registered story
  panel — time-travel via `restore-epoch` is a UI affordance, not a
  reimplementation.

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
See [IMPL-SPEC §6](./IMPL-SPEC.md#section-6).

## Status

- **Spec.** [`spec/007-Stories.md`](../../spec/007-Stories.md) — normative.
- **Implementation contract.** [`IMPL-SPEC.md`](./IMPL-SPEC.md).
- **Research lineage.**
  - Phase 1 — `findings/re-frame-2-story-feature-set.md` (rf2-m6tu).
  - Phase 2 — `findings/re-frame-2-story-sota-refinement.md` (rf2-94b0).
  - Architectural decisions resolved 2026-05-11; see IMPL-SPEC §2.

## See also

- [Spec 002 — Frames](../../spec/002-Frames.md) — the primitive Story leans on.
- [Spec 008 — Testing](../../spec/008-Testing.md) — the test-runner surface
  Story integrates with via `run-variant`.
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) — the trace
  bus Story consumes for its trace panel.
- [Spec 010 — Schemas](../../spec/010-Schemas.md) — the auto-derivation source
  for controls.
- [Spec 011 — SSR](../../spec/011-SSR.md) — the schema-digest source for
  snapshot identities; dual-pane SSR support.
- [`tools/README.md`](../README.md) — the per-tool layout and bundle-isolation
  contract.
- [Guide chapter 14 — Stories](../../docs/guide/14-stories.md) — the narrative
  walkthrough; the friendly entry-point for human readers.
- [`examples/reagent/counter_with_stories/`](../../examples/reagent/counter_with_stories/) —
  the worked example wiring the seven `reg-*` macros against the canonical
  counter app.
