# 21 — Stories

> **Optional deep-dive.** This chapter assumes you've absorbed the [events](04-events-state-cycle.md), [views](06-views-and-frames.md), [testing](13-testing.md), and [devtools](15-devtools-and-pair-tools.md) chapters. If you haven't yet, come back later — Stories is a layered surface on top of those, not the next link in the linear sequence.

You've seen the runtime in the [events chapter](04-events-state-cycle.md), the views in the [views chapter](06-views-and-frames.md), the test machinery in the [testing chapter](13-testing.md), and the tooling pitch in the [devtools chapter](15-devtools-and-pair-tools.md). This chapter is where they converge.

[`re-frame2-story`](https://github.com/day8/re-frame2/tree/main/tools/story) is a **frame-aware component playground** — Storybook-flavoured, but built on re-frame2's primitives the whole way down. Each variant of a component runs in its own dedicated frame (chapter 06). Each variant body is plain data, not a function (no `<Counter.story.tsx>` with inline JSX). Args resolve through a three-layer chain. Assertions ride the same `dispatch` pipeline as production events. Time-travel scrubs through `restore-epoch` (chapter 15). When you want to scaffold a new component, you're not reaching for a separate `.stories.tsx` file — you're declaring `reg-story` and `reg-variant` against the same component you're shipping.

You'll know how to:

- Author your first story + variant.
- Resolve args through the three-layer chain (global / story / variant).
- Wire a custom decorator and the built-in `force-fx-stub`.
- Compose variants into a workspace.
- Record-don't-throw assertions through `:rf.assert/*` events.
- Mount the Story shell at a URL and tear it down cleanly.
- Find the agent-facing MCP surface when you want one.

The worked example throughout is [`examples/reagent/counter_with_stories/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter_with_stories) — the same counter we've been pivoting around since chapter 03, with the seven Story authoring macros wired up end-to-end.

## What Story is — and when you'd reach for it

Story is the surface you reach for when **a component has more than one state and you want them all in one place**. Forms have an empty / loading / error / submitted state. A dropdown has a closed / open / disabled / read-only state. A header has a logged-in / logged-out / impersonating state. In production these states are reached by dispatching events in sequence; you can't see them all at once. Story gives you the catalogue.

Three of Story's hard rules are worth knowing up front:

1. **Each variant runs in its own frame.** Per spec/002, a variant is allocated a fresh frame with a fresh `app-db`; no state leaks between scenarios. What you see is what production would render against the same fixture.
2. **Variant bodies are data — never functions.** A variant body is a map with `:events`, `:args`, `:decorators`, `:play`, etc. — every slot is plain EDN, round-trippable across the network. This is the lock that lets MCP, visual-regression services, and agent input pipelines all consume the same shape.
3. **Assertions record, don't throw.** A failing `:rf.assert/path-equals` doesn't blow up the variant — it appends an entry to the variant frame's assertion accumulator. The play sequence runs to completion either way; the test runner asks "did every entry pass?" at the end.

If you've used Storybook, this will be familiar. The places re-frame2-story differs:

- **Schema-derived controls.** Spec 010's Malli schemas auto-generate the right control type for each arg — strings get text inputs, enums get dropdowns, ranges get sliders. You don't author `argTypes` separately.
- **Frame-aware time-travel.** The scrubber doesn't replay events — it walks the per-frame epoch buffer (chapter 15) and restores via `restore-epoch`. The same machinery the runtime exposes everywhere.
- **No CSF Factories.** Variant bodies are data; there's no `import { Story } from '@storybook/react'`. The artefact you ship is a clojure map.

## Authoring a first story

Three lines:

```clojure
(ns counter-with-stories.stories
  (:require [re-frame.story :as story]
            [counter-with-stories.views]))   ; loads the view registry

(story/reg-story :story.counter
  {:doc       "The counter — every state, all in one place."
   :component :counter-with-stories.views/counter-card
   :args      {:label "Count"}
   :tags      #{:dev :docs}
   :substrates #{:reagent}})

(story/reg-variant :story.counter/empty
  {:doc    "Fresh counter at zero."
   :events [[:counter/initialise 0]]
   :play   [[:rf.assert/path-equals [:count] 0]]
   :tags   #{:dev :docs :test}})
```

That's enough to register one variant against the parent story. Things to notice:

- The id grammar is **locked**. Stories live under `:story.<dotted-path>`; variants under `:story.<path>/<variant-name>`. The story tool's sidebar tree is built from the dotted path — no separate `:title` field.
- `:component` is a **view-id keyword**, not a function ref. The variant body stays serialisable. The view registry resolves the keyword at render time; the closure (the actual hiccup-producing fn) is on the view, not on the story.
- `:events` is a sequence of regular event vectors. They dispatch in source order through the same router that the live app uses. By the time the canvas renders, the variant frame's `app-db` is exactly what those events left behind.
- `:play` is the assertion sequence (see the §Play sequences section below).
- `:tags #{:dev :docs}` makes the variant visible in the dev-mode sidebar and the documentation export. The seven canonical tags (`:dev :docs :test :screenshot :experimental :internal :agent`) register at Story load; project-specific tags need a `reg-tag` first.

The worked example registers four variants (`empty`, `loaded`, `clicked-three-times`, `save-stubbed`) — read [`examples/reagent/counter_with_stories/stories.cljs`](https://github.com/day8/re-frame2/blob/main/examples/reagent/counter_with_stories/stories.cljs) for the whole shape.

## Three-level args + auto-derived controls

Args resolve through a three-layer chain (per IMPL-SPEC §5.2):

```
global-args   ← set once via (story/configure! {:global-args {...}})
     ↓
story-args    ← the :args slot on reg-story (parent default)
     ↓
variant-args  ← the :args slot on reg-variant (per-scenario override)
     ↓
cell-overrides ← live edits from the controls panel
```

Deep-merge, left-to-right. Variants that don't say anything inherit; variants that override win. Modes (next section) sit between global and story for a fourth point of leverage when needed.

The **controls panel** in the right-side pane auto-derives editors. If the parent story carries `:argtypes {:label {:control :text}}`, the panel renders a text input wired to dispatch a cell-override. If you've gone further and tagged the schema in `re-frame.schemas` (spec/010), the panel reads the schema directly — a `:keyword` schema becomes a select, an `:int` schema with `:max`/`:min` becomes a number-input or slider, an `:enum` becomes a radio group. No `argTypes` plumbing; the schema is the source of truth.

## Decorators — three kinds

Decorators are how you **wrap the canvas with re-usable concerns**: layout, theming, mock data, fx mocking. Per IMPL-SPEC §3.1 there are three kinds:

### `:hiccup` decorators — wrap the rendered tree

The closure lives at the decorator's *registration site*:

```clojure
(story/reg-decorator :app/centered-layout
  {:kind :hiccup
   :wrap (fn [body _ref-args]
           [:div {:style {:display "flex" :justify-content "center"}}
            body])})

(story/reg-variant :story.counter/loaded
  {:decorators [[:app/centered-layout]]
   :events     [[:counter/initialise 7]]})
```

The variant body references the decorator by id; the closure lives on the decorator. This is what keeps variant bodies serialisable.

The worked example uses this shape for `:counter-with-stories/log-decorator`, which paints a labelled dashed outline around the rendered variant.

### `:frame-setup` decorators — patch the frame at allocation

```clojure
(story/reg-decorator :app/with-current-user
  {:kind         :frame-setup
   :init         [[:auth/initialise]
                  [:auth/login-as-alice]]
   :app-db-patch {:locale :en-AU}})
```

Used when several variants share an "assume the user is logged in" setup. The events dispatch before the variant's own `:events` slot; the patch merges into the variant frame's `app-db`. Pure data, no closures.

### `:fx-override` decorators — stub a network call

The marquee shape is the MSW-flavoured **`force-fx-stub`** decorator, which Story ships built-in:

```clojure
(story/reg-variant :story.counter/save-stubbed
  {:events     [[:counter/initialise 5]
                [:counter/save]]
   :decorators [[story/force-fx-stub-id
                 :counter/sync-to-server
                 {:ok? true}]]
   :play       [[:rf.assert/effect-emitted :counter/sync-to-server]]})
```

`:counter/save` walks an `:fx` slot that dispatches `[:counter/sync-to-server ...]`. The stub decorator intercepts the fx-id and records its calls into a per-frame log; the play sequence asserts the fx was emitted. The real network call never fires.

This is the same shape you'd reach for in tests (the assertion records-don't-throw, so it's also the same shape your test code uses).

## Workspaces

A workspace is a **layout that arranges variants on a single page**. Two layouts are common; v1 ships five:

- `:grid` — explicit list of variant ids, in order, in a grid. The variant grid you reach for when a story has, say, four named states and you want a screenshot showing all of them.
- `:variants-grid` — auto-enumerated from a parent story id. New variants land here without touching the workspace.

```clojure
(story/reg-workspace :Workspace.counter/all-states
  {:layout   :grid
   :variants [:story.counter/empty
              :story.counter/loaded
              :story.counter/clicked-three-times
              :story.counter/save-stubbed]
   :columns  2})

(story/reg-workspace :Workspace.counter/auto-grid
  {:layout  :variants-grid
   :for     :story.counter
   :columns 2})
```

Workspaces are **transit-shareable**. The "share this layout" button serialises the workspace + active mode + cell-overrides into a URL; whoever opens the URL sees the same grid in the same state. Per IMPL-SPEC §2.5 this is the v1 sharing primitive.

## Modes — saved tuples

A **Mode** is a Chromatic-style saved tuple of args that any variant can render against:

```clojure
(story/reg-mode :Mode.app/dark
  {:args {:theme       :dark
          :background  "#1e1e1e"
          :foreground  "#e0e0e0"}})

(story/reg-mode :Mode.app/light
  {:args {:theme       :light
          :background  "#ffffff"
          :foreground  "#1a1a1a"}})
```

When a variant renders against `:Mode.app/dark`, the mode's `:args` deep-merge into the variant's effective args between the global layer and the story layer. Each `(variant × mode)` cell has its own snapshot-identity — visual-regression services key off it. Dark mode and light mode become two screenshots from one variant body.

## Play sequences + assertions

The `:play` slot on a variant is a sequence of regular event-vectors. They dispatch through the same router as `:events` — but the seven canonical assertion events from spec/007 §304 don't throw on failure; they append a record to `[:rf.story/assertions]` in the variant frame's `app-db`.

The seven:

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals`     | `[path expected]` | `(= (get-in @app-db path) expected)` |
| `:rf.assert/path-matches`    | `[path malli-schema]` | Malli validates the value at `path` |
| `:rf.assert/sub-equals`      | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)` |
| `:rf.assert/dispatched?`     | `[event-or-pred]` | Did this event dispatch during play? |
| `:rf.assert/state-is`        | `[machine-id state]` | Is the machine in `state`? |
| `:rf.assert/no-warnings`     | `[]` | No `:warning` trace events since play start? |
| `:rf.assert/effect-emitted`  | `[fx-id]` (or `[fx-id pred]`) | Was this fx-id emitted? |

The record-don't-throw shape (per IMPL-SPEC §2.3) is the design call. A play sequence with eight assertions where three fail still runs all eight; you get the full picture, not the first failure. Tests then read the accumulator:

```clojure
(deftest counter-loaded
  (cljs.test/async done
    (-> (story/run-variant :story.counter/loaded)
        (story.async/then
          (fn [result]
            (is (story/assertions-passing? result))
            (story/destroy-variant! :story.counter/loaded)
            (done))))))
```

`run-variant` returns a promise (CLJS) or future (JVM) of `{:frame :app-db :assertions :rendered-hiccup :elapsed-ms :snapshot :lifecycle}`. The same result map the MCP surface returns when an agent asks for a preview. Same shape; same vocabulary.

The worked example's [`stories_cljs_test.cljs`](https://github.com/day8/re-frame2/blob/main/examples/reagent/counter_with_stories/stories_cljs_test.cljs) is the full integration-test pattern.

## Mounting the Story shell

In your app's entry namespace:

```clojure
(:require [re-frame.story :as story]
          [my-app.stories])    ; loads the registrations

(defn run []
  (rf/init! reagent-adapter/adapter)
  (story/install-canonical-vocabulary!)
  ;; ... normal app boot ...
  (when (= "#/stories" js/window.location.hash)
    (story/mount-shell! (js/document.getElementById "app"))))
```

`install-canonical-vocabulary!` registers the seven canonical tags, the lifecycle machine, the seven `:rf.assert/*` handlers, the built-in `force-fx-stub` decorator, the layout-debug decorator trio, and the v1.0 panel set. Idempotent. Production builds — where `re-frame.story.config/enabled?` is `false` via `:closure-defines` — short-circuit at registration time, and `mount-shell!` short-circuits before any DOM call.

The shell is a three-pane Reagent component: a left sidebar (stories tree + tag filter + workspaces), the main pane (selected variant's canvas or selected workspace), and a right panel (controls, time-travel scrubber, six-domino trace, and any project-custom `reg-story-panel` placements).

The worked example hash-routes — `#/` renders the live counter app; `#/stories` mounts the shell. Read [`core.cljs`](https://github.com/day8/re-frame2/blob/main/examples/reagent/counter_with_stories/core.cljs) for the mount / unmount discipline.

## The MCP surface — brief

Story ships an **agent-facing MCP server** as a separate jar at [`tools/story-mcp/`](https://github.com/day8/re-frame2/tree/main/tools/story-mcp) (`day8/re-frame2-story-mcp`). It's a stdio JSON-RPC server that exposes Story's read (and gated write) surface as Model Context Protocol tools — `list-stories`, `get-variant`, `run-variant`, `snapshot-identity`, `read-failures`, etc.

You'd wire it into Claude Code or Cursor when you want an agent to drive the playground — write a new variant, run it, read the assertion failures, fix the component, run again. The agent's transcripts feed back into the design loop. Per IMPL-SPEC §2.1 the MCP server is **a separate artefact** so the Story core jar stays out of the production classpath: a project that ships Story in dev mode doesn't have to ship a JSON-RPC server along with it.

Run the server (the agent host usually launches it for you):

```bash
cd tools/story-mcp
clojure -M -m re-frame.story-mcp.server
```

The write surface (`register-variant`, `unregister-variant`) is gated behind `--allow-writes`. The full tool list and protocol shape are in [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md).

## Bundle isolation — what happens under `:advanced`

The two ends of the contract:

- **Dev builds.** `re-frame.story.config/enabled?` defaults to `true`. Every `reg-*` macro registers; `mount-shell!` mounts; the playground is live.
- **Production builds.** Set `:closure-defines {re-frame.story.config/enabled? false}` (typically alongside `goog.DEBUG false`). Every `reg-*` macro expands to `(when re-frame.story.config/enabled? ...)`; with the constant `false`, Closure DCEs the body. `mount-shell!` short-circuits before any DOM call. The Story query API (`handlers`, `registered?`, `variants-of`) survives but reads from an empty side-table.

A separate CI gate at [`implementation/scripts/check-bundle-isolation.cjs`](../../implementation/scripts/check-bundle-isolation.cjs) greps the plain `examples/counter` bundle for Story-internal sentinel strings (`rf.error/unknown-tag`, `rf.error/decorator-*`). Any hit means a `:require` accidentally dragged a `re-frame.story.*` namespace into the production path; CI fails the PR.

This is the whole story (no pun intended) about how a tool with this much surface can ship without weighing on production bundles: physical separation under `tools/`, compile-time elision via the config flag, and a CI gate that asserts the elision held.

## Where to go next

- **The worked example** — [`examples/reagent/counter_with_stories/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/counter_with_stories). Four variants, two workspaces, every `reg-*` form, and a passing integration test.
- **The implementation contract** — [`tools/story/spec/`](../../tools/story/spec/). What the runtime actually does, decision-by-decision. The [`005-SOTA-Features.md`](../../tools/story/spec/005-SOTA-Features.md) §Production elision section + [`002-Runtime.md`](../../tools/story/spec/002-Runtime.md) §Args resolution precedence are the most-referenced parts.
- **The normative spec** — [`spec/007-Stories.md`](../../spec/007-Stories.md). The id grammar lock, the variant-as-data lock, the seven canonical assertions. When the IMPL-SPEC and Spec 007 disagree, Spec 007 wins.
- **The agent surface** — [`tools/story-mcp/README.md`](https://github.com/day8/re-frame2/blob/main/tools/story-mcp/README.md). The MCP server, the sixteen tools, the protocol shape, the write-gate.

Story is the most direct expression of the third-pillar pitch from [chapter 15](15-devtools-and-pair-tools.md): the runtime is the substrate, the tools are downstream observers, and a project's stories live in the same repo as the components they exercise. Open `#/stories` against your own app and you'll see what it feels like to have the catalogue right there.
