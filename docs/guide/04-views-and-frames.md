# 04 — Views and frames

Two questions matter for the screen:

1. **What goes on it?** That's the view layer.
2. **Whose state is it showing?** That's the frame layer.

In the simplest case (one app, one page) these collapse together: there's *the* view tree showing *the* state. As soon as you have a story tool, a server-side render, two windows, or anything that involves rendering "the same component but with different state," the two questions diverge. re-frame2 keeps them separate from the start so that when you need the more complex case, the architecture is already there.

## Views

A **view** is a function. It takes some inputs (props, mostly) and returns a description of what should be on the screen. In re-frame2, that description is **hiccup**: nested Clojure vectors that look like the DOM tree they describe.

```clojure
[:div.counter
 [:h1 "Apples"]
 [:p "Count: " [:strong "5"]]]
```

Hiccup is *just data*. You can `pprint` it. You can build it programmatically. You can ship it across the wire. You can write a hiccup → HTML emitter that runs on the JVM (which is exactly what server-side rendering uses).

The fact that hiccup is data, rather than JSX-flavoured pseudo-HTML or a templating-language string, is one of the deeper architectural choices. JSX is *almost* data, but the language extensions and component objects make it not-quite. Templating languages are strings, which means computing them involves string concatenation, which is where SQL injection lives. Hiccup is data the whole way down.

### Plain views vs registered views

The chapter-02 counter view was the simplest case — no arguments, all state read through a sub. A small step up is a view that takes an argument. Suppose we want the counter to display a label alongside its number:

```clojure
(defn labelled-counter [label]
  [:div.counter
   [:h1 label]
   [:span @(rf/subscribe [:count])]])
```

You can use it anywhere by referencing the var: `[labelled-counter "Apples"]` inside another piece of hiccup. Reagent (the underlying view substrate in the CLJS reference) calls the function and substitutes its return value into the tree.

For most apps, this works fine. There's no ceremony, no registration, nothing to register or look up.

But there's a sharper version: **registered views**.

```clojure
(rf/reg-view labelled-counter [label]
  [:div.counter
   [:h1 label]
   [:span @(subscribe [:count])]])
```

Two things change:

1. The view is registered under an auto-derived keyword, `(keyword *ns* "labelled-counter")`. It's now in the registry alongside events, subs, fx. Tooling can list it. AIs can introspect it. You can query its `:doc`/`:spec` metadata.

2. **Inside the view's body, `dispatch` and `subscribe` are bound to the surrounding frame.** This is the load-bearing reason to register views. Without it, plain Reagent functions read state from a hard-coded default frame; you can't put two instances of the same view next to each other showing different state.

For a single-frame app, plain views are fine. For anything else — and "anything else" includes story tools, SSR, devcards, and multi-window apps — register your views. The cost is a few extra characters; the win is that the view-side architecture supports the multi-frame case as soon as you need it.

### `dispatch` and `subscribe` are auto-injected

Inside a `reg-view` body, two names are available that you didn't import: `dispatch` and `subscribe`. They look like the global functions but are **lexically bound**, frame-aware versions that the macro injects.

```clojure
(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/inc])} "+"]
   [:span @(subscribe [:count])]])
;; ↑                  ↑
;; injected           injected
```

What that buys you: the view's body never says "this frame" out loud. The injected `dispatch`/`subscribe` capture the surrounding frame from the React tree (when wrapped by `frame-provider`) or fall back to the dynamic-var tier or `:rf/default`. The view fn doesn't know which frame it's been instantiated under; the framework routes correctly.

Form-1 views — render fns whose body is a literal hiccup expression — get the auto-inject via the macro's expansion. Form-2 views — render fns that return another fn (the "outer fn closes over args, inner fn does the work" Reagent idiom) — also work, and are the canonical shape when the view needs to capture per-instance state in the closure or maintain a Reagent-local atom for transient UI state. Both shapes are documented in [Spec 004](../../spec/004-Views.md).

```clojure
;; Form-1 — direct render
(rf/reg-view counter []
  [:div @(subscribe [:count])])

;; Form-2 — captures args; useful for setup-once-then-render
(rf/reg-view labelled-counter [label]
  (let [mounted-at (js/Date.now)]
    (fn render [label]
      [:div label ": " @(subscribe [:count]) " (mounted " mounted-at ")"])))
```

The macro errors at compile time if you hand it a Form-3 (`reagent.core/create-class`) or a non-literal-fn body — the message points you at the underlying fn `reg-view*` for those rare cases. The compile-time check is the load-bearing piece: it stops the auto-inject from silently doing the wrong thing when the body shape isn't what the macro can rewrite.

### How frames propagate through the view tree

The architecture above assumes a view inside a `frame-provider` subtree picks up the surrounding frame. The CLJS reference uses **React context** to carry this — when you wrap a subtree with `[rf/frame-provider {:frame :left} ...]`, every registered view rendered underneath receives the frame id through context, and the auto-injected `dispatch`/`subscribe` resolves against it.

The propagation is part of `reg-view`'s contract: a registered view inside `frame-provider {:frame :left}` dispatches to `:left`. The full resolution chain (dynamic-var → React context → `:rf/default`) is specified in [Spec 002 §3](../../spec/002-Frames.md). The split-counter example below exercises the resolution; the view fn doesn't know which frame it's been instantiated under, and the framework routes correctly.

### The Var-reference idiom

`reg-view` is defn-shape: it auto-defs the symbol you supply. Reference that var like any other Reagent component:

```clojure
(rf/reg-view labelled-counter [label] ...)

;; ... elsewhere
[:div
 [labelled-counter "Apples"]
 [labelled-counter "Oranges"]]
```

This reads exactly like Reagent. There's nothing to learn. It just happens to work for the multi-frame case because of the registration that's also happening.

An alternative form exists — `(rf/view :my-ns/labelled-counter)` for runtime-id'd dispatch and late-binding (cross-module reference, hot-reload-sensitive call sites) — but the Var idiom is what you'll see most. `view` is the escape hatch for those specific cases.

## Frames

A **frame** is the boundary that holds an app's runtime state. Each frame has:

- An `app-db` (a single immutable map).
- A queue of events to process.
- A subscription cache.
- An id (a keyword).

In a single-frame app, all of these are implicit. The default frame, `:rf/default`, gets registered automatically. Every dispatch goes to it. Every subscribe reads from it. You don't think about frames at all.

In multi-frame apps, you care.

### When you need multiple frames

You need multiple frames when you want **multiple instances of the same app's handlers** with different state. The headline cases:

- **Story tools.** "Show me this view in the loaded state, the loading state, and the error state, side by side." Each story is a frame. They share the same registered events / subs / views; their `app-db` values differ.
- **Server-side rendering.** Each HTTP request creates a frame, runs setup events, renders, ships state, destroys the frame. Concurrent requests don't pollute each other.
- **Devcards / Storybook-style isolated rendering.** Same reason as story tools: render the component, in isolation, in a known state.
- **Multi-window or split-screen UIs.** If you've got two panels showing related data with shared events, give each panel its own frame.
- **Per-test fixtures in unit tests.** Each test runs in a fresh frame, gets its own `app-db`, doesn't leak state to the next test.

What you don't need multiple frames for:

- **Different *apps* on one page.** That's a different problem (micro-frontends), explicitly out of scope. Use iframes.
- **State for different routes.** Routing puts different data in `app-db` over time, but it's all the same app — one frame is correct.
- **State for different components.** You don't isolate by component. You compose subs/events to read and write the slices each component cares about.

### Creating a frame

Two ways:

```clojure
;; Named, registered up-front.
(rf/reg-frame :counter
  {:doc "An isolated counter frame."
   :on-create [:counter/initialise]})

;; Anonymous, gensym'd id.
(let [f (rf/make-frame {:on-create [:counter/initialise]})]
  ...)
```

`reg-frame` is for frames you'll dispatch into by name (story variants, well-known runtime instances). `make-frame` is for frames whose lifecycle is controlled by surrounding code — typically tests and per-mount story instances.

Both end with the same shape: a frame is in the global frame registry under its keyword id, with its `app-db` initialised by the `:on-create` event.

### Frame presets

Most frames you'll register fall into one of four shapes: a normal client app, a per-test fixture, a story variant, a per-request SSR frame. Writing the metadata for each by hand each time would be repetitive *and* would make the intent of the call site invisible. So re-frame2 ships a closed set of four canonical **presets**:

```clojure
(rf/reg-frame :test/counter-flow      {:preset :test})
(rf/reg-frame :story.counter/loading  {:preset :story})
(rf/reg-frame :ssr.req/abc123         {:preset :ssr-server})
(rf/reg-frame :app/main               {:preset :default})  ;; same as omitting :preset
```

Each preset expands at registration time into a fixed bundle — `:test` redirects `:rf.http/managed` to the canned-success stub (so HTTP fx don't reach the network in tests) and stamps `:drain-depth 100`; `:story` does the same redirect and tightens `:drain-depth` to 16 so runaway dispatch cascades fail fast under a story; `:ssr-server` sets `:platform :server` and wires the server-projection error path. User-supplied keys override the expansion (so you can opt out of any one default), but the preset's name stays in the metadata and is queryable. The expansions are locked — every `:test` frame is configured the same way, every `:story` frame is configured the same way. Adding a fifth preset would be a Spec-change. The full grammar lives in [Spec 002 §Frame presets](../../spec/002-Frames.md#frame-presets-capability-bundles-for-common-configurations).

This matters because the call site now declares its intent — "this is a test frame" — and tooling (test runners, story runners, SSR adapters) reads that declaration without inferring it from the surrounding code.

### Targeting a specific frame

Every dispatch and every subscribe takes a frame argument when targeting a specific frame from outside a registered view:

```clojure
(rf/dispatch [:counter/inc] {:frame :left})   ;; dispatch — opts map
(rf/subscribe :right [:count])                 ;; subscribe — frame-id positional
```

The shapes differ for historical reasons: `dispatch` carries opts beyond `:frame` (e.g. `:fx-overrides` for tests), while `subscribe`'s only out-of-view callers are tooling-shaped, where a positional frame-id is enough.

This is the **explicit-frame addressing** form. It's the canonical way to target a frame from outside a registered view — REPL sessions, test code, event-handler effects (fx).

Inside a registered view's body, you don't write the `:frame` argument explicitly — `dispatch` and `subscribe` are already bound to the surrounding frame. That's what the registration buys you: the view's body is frame-blind, and the framework routes correctly.

### `frame-provider` — scoping a frame to a subtree

In CLJS, the way to put a registered view inside a particular frame's subtree is `frame-provider`:

```clojure
[:div.app
 [rf/frame-provider {:frame :left}
  [counter-view]]                     ;; reads/writes :left
 [rf/frame-provider {:frame :right}
  [counter-view]]]                    ;; reads/writes :right, same view fn
```

Two instances of the same registered view, different frames. Each subtree's `dispatch`/`subscribe` resolves to its own frame. The view fn doesn't know it's been instantiated twice with different state.

`frame-provider` is a Reagent-specific (React context-driven) construct. The pattern itself doesn't require it — what the *pattern* requires is that every dispatch/subscribe targets a specific frame, by whatever mechanism the host language provides. In TypeScript, you might use a hooks-flavoured `useFrame()`. In Python, you might pass the frame as an argument. In Clojure, React context happens to be ergonomic. The contract — every view targets a specific frame — is the part that survives across hosts.

## Source coordinates on rendered DOM

One small detail to recognise when you see it in the inspector: every `reg-view`-rendered DOM element carries a `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` attribute pointing back to the registration that produced it. It's how pair tools and devtools resolve a clicked DOM node back to the source line. The annotation is dev-only — production builds elide it via DCE.

You don't need to do anything to get it. `reg-view` does it. The full story — format, recovery to file path, exemptions, machine-spec equivalents — is in [chapter 11 §Click-to-source](11-devtools-and-pair-tools.md#click-to-source-the-source-coord-story). The contract is specified in [Spec 006 §source-coord-annotation](../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7-rf2-z9n1).

## Computed values as state — the flow escape hatch

Most derived values are subscriptions — they live in the per-frame sub-cache and are consumed by views. Sometimes, though, the derived value isn't *just* for views: you want it **in `app-db`** so another event handler can read it as plain data, it survives SSR/hydration, it shows up in the inspector, a registered schema can cover it.

That's what **flows** are for. A flow is a registered rule: "when these `app-db` paths change, run this pure function and write the result to that `app-db` path." Same compute-on-input-change semantics as subs; different storage location.

```clojure
(rf/reg-flow
  {:id     :rectangle/area
   :inputs [[:width] [:height]]
   :output (fn [w h] (* w h))
   :path   [:area]})
```

Flows are deliberately a **niche convenience**, not a sub replacement — a typical re-frame2 app has dozens of subs and one or two flows. Full contract: [Spec 013](../../spec/013-Flows.md).

## Routing as state

Routing is just another slice of `app-db`. The current route lives at `:route` (a `{:id :params :query :fragment :transition :nav-token}` map); navigation is an event; the active route is a sub. There's no separate routing runtime — it's data that happens to be reflected in the address bar. The full story (deterministic ranking, navigation tokens, `:can-leave` guards, multi-frame routing) is in [chapter 12](12-routing.md) and [Spec 012](../../spec/012-Routing.md). For this chapter, the load-bearing fact is that routing doesn't break the one-app-db model.

## Making the common UI states explicit — Pattern-NineStates

Once your view is reading state from `app-db`, a temptation appears: branch the render on a few obvious cases (`loading`, `loaded`, `error`) and call it done. The trouble is that most pages have more than three meaningful states, and the missing ones — the empty result, the single-item case, the too-many-results case, the validation-failure case, the terminal "this is archived now" case — end up visually undefined unless you treat them as first-class.

**Pattern-NineStates** names the canonical checklist for a page-level render contract:

| # | State | Typical source |
|---|---|---|
| 1 | **Nothing** — user hasn't asked for a fetch yet | `:data` region at `:nothing` |
| 2 | **Loading** — first fetch in flight | `:data` region at `:loading` |
| 3 | **Empty** — loaded, zero results | `:data` region at `:empty` |
| 4 | **One** — loaded, exactly one result | `:data` region at `:one` |
| 5 | **Some** — loaded, manageable list | `:data` region at `:some` |
| 6 | **Too Many** — loaded, count above a domain threshold | `:data` region at `:too-many` |
| 7 | **Incorrect** — user input invalid, user can fix it | `:form` region at `:incorrect` |
| 8 | **Correct** — visible success acknowledgement | `:form` region at `:correct` |
| 9 | **Done / Frozen** — domain reached terminal / read-only | `:mode` region at `:done` |

The pattern is a **design checklist** plus a **rendering convention**, not a universal ontology — most pages don't need all nine, but most pages do need *more than three*. The shape is one `:type :parallel` state machine with three regions (`:data` / `:form` / `:mode`); every state declares `:tags` from a small canonical vocabulary (`:data/loading`, `:form/invalid`, `:mode/done`, …); a single render-priority table + selector sub collapses the tag union into one render-model keyword, and the root view's `case` over that keyword is the only branch site. "We forgot what happens when the result is empty" stops being a class of bug because the region's `:empty` state is enumerable from the machine declaration.

Full convention plus a worked example: [`spec/Pattern-NineStates.md`](../../spec/Pattern-NineStates.md) and [`examples/reagent/nine_states/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/nine_states).

## A small example: split counter

Two counters, side by side, isolated:

```clojure
;; Registered events/subs/views (same for both counters)
(rf/reg-event-db :counter/initialise (fn [_ _] {:count 0}))
(rf/reg-event-db :counter/inc        (fn [db _] (update db :count inc)))
(rf/reg-sub      :count              (fn [db _] (:count db)))

(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/inc])} "+"]
   [:span @(subscribe [:count])]])

;; Two frames, one for each side.
(rf/reg-frame :left  {:on-create [:counter/initialise]})
(rf/reg-frame :right {:on-create [:counter/initialise]})

;; Mount both, scoped.
(defn ^:export run []
  (rdc/render root
    [:div.split
     [rf/frame-provider {:frame :left}  [counter]]
     [rf/frame-provider {:frame :right} [counter]]]))
```

That's the whole thing. Same registered handlers. Same registered view. Two frames. Each click increments only its own side. The handlers don't know there are two of them.

What this would look like in idiomatic React would be three or four `useState`s, plus prop-drilling to keep them separate, plus a careful audit to ensure the click handler isn't accidentally closed over the wrong state. In re-frame2, the architecture handles isolation; the developer doesn't.

## Frames are not "components with state"

There's a temptation, when you first see frames, to use them like React components: each component gets its own frame. **Don't do this.** Frames are heavyweight — they have their own `app-db`, queue, and sub-cache. They exist for cases where the *whole app* runs in isolation (story variants, SSR requests, multi-window). For "component-level state," you do what re-frame has always done: put the data in `app-db`, write events to update it, write subs to read it.

A useful test: if two instances might want to share state under any circumstance, they're not separate frames. They're separate slices of the same frame's `app-db`.

## What we covered

- The view layer is plain functions (or registered views) returning hiccup.
- Registered views are the canonical form because they bring frame-routing and registry introspection.
- Frames are runtime boundaries: each has its own state, queue, sub-cache.
- Most apps are single-frame; multi-frame is for story tools, SSR, devcards, multi-window.
- The pattern requires every view to target a specific frame; the CLJS reference uses React context (`frame-provider`) to make that ergonomic.
- Computed values (flows) and routing both live in `app-db` — multi-frame doesn't change that.

## Next

- [05 — State machines](05-state-machines.md) — when an event handler's logic is a flow, model it as a state machine.
