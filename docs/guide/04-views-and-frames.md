# 04 — Views and frames

Two questions matter for the screen:

1. **What goes on it?** That's the view layer.
2. **Whose state is it showing?** That's the frame layer.

In the simplest case (one app, one page) these collapse together: there's *the* view tree showing *the* state. As soon as you have a story tool, a server-side render, two windows, or anything that involves rendering "the same component but with different state," the two questions diverge. re-frame2 keeps them separate from the start so that when you need the more complex case, the architecture is already there.

## Views

A **view** is a function. It takes some inputs (props, mostly) and returns a description of what should be on the screen. In re-frame2, that description is **hiccup**: nested Clojure vectors that look like the DOM tree they describe.

```clojure
[:div.greeting
 [:h1 "Hello"]
 [:p "Welcome back, " [:strong "Mike"] "."]]
```

Hiccup is *just data*. You can `pprint` it. You can build it programmatically. You can ship it across the wire. You can write a hiccup → HTML emitter that runs on the JVM (which is exactly what server-side rendering uses).

The fact that hiccup is data, rather than JSX-flavoured pseudo-HTML or a templating-language string, is one of the deeper architectural choices. JSX is *almost* data, but the language extensions and component objects make it not-quite. Templating languages are strings, which means computing them involves string concatenation, which is where SQL injection lives. Hiccup is data the whole way down.

### Plain views vs registered views

The simplest view is a plain Clojure function:

```clojure
(defn greeting [name]
  [:div.greeting
   [:h1 "Hello"]
   [:p "Welcome back, " [:strong name] "."]])
```

You can use it anywhere by referencing the var: `[greeting "Mike"]` inside another piece of hiccup. Reagent (the underlying view substrate in the CLJS reference) calls the function and substitutes its return value into the tree.

For most apps, this works fine. There's no ceremony, no registration, nothing to register or look up.

But there's a sharper version: **registered views**.

```clojure
(rf/reg-view greeting [name]
  [:div.greeting
   [:h1 "Hello"]
   [:p "Welcome back, " [:strong name] "."]])
```

Two things change:

1. The view is registered under an auto-derived keyword, `(keyword *ns* "greeting")`. It's now in the registry alongside events, subs, fx. Tooling can list it. AIs can introspect it. You can query its `:doc`/`:spec` metadata.

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
(rf/reg-view greeting [name]
  (let [greeted-at (js/Date.now)]
    (fn render [name]
      [:div "Hello, " name " (at " greeted-at ")"])))
```

The macro errors at compile time if you hand it a Form-3 (`reagent.core/create-class`) or a non-literal-fn body — the message points you at the underlying fn `reg-view*` for those rare cases. The compile-time check is the load-bearing piece: it stops the auto-inject from silently doing the wrong thing when the body shape isn't what the macro can rewrite.

### How frames propagate through the view tree

The architecture above assumes a view inside a `frame-provider` subtree picks up the surrounding frame. The CLJS reference uses **React context** to carry this — when you wrap a subtree with `[rf/frame-provider {:frame :left} ...]`, every registered view rendered underneath receives the frame id through context, and the auto-injected `dispatch`/`subscribe` resolves against it.

In practice, the propagation is part of `reg-view`'s contract: a registered view inside `frame-provider {:frame :left}` will dispatch to `:left`. **Caveat:** as of today the CLJS implementation has a partial gap here — the resolution chain spec/002 §3 documents (dynamic-var → React context → `:rf/default`) is not fully wired in the current CLJS reference. Subscribe consults the dynamic-var tier and falls back to `:rf/default`; the React-context tier is documented but not yet connected in the read path. The split-counter example below works in the canonical case (one frame-per-mount, set up via `frame-provider` plus dispatch-sync seed events) because the dynamic-var tier carries the frame for each render, but more elaborate cases — sibling views under different frame-providers within a single render pass that BOTH need to read state — should be tested against your specific shape.

The behaviour is tracked at [rf2-d4sf](#); the contract in this guide describes what works today, not what spec/002 §3 promises long-term. When the gap closes, the resolution is automatic — your code does not change. The chapter's example (and the runnable [`examples/reagent/counter/core.cljs`](../../examples/reagent/counter/core.cljs)) exercises only the part that works today.

### The Var-reference idiom

`reg-view` is defn-shape: it auto-defs the symbol you supply. Reference that var like any other Reagent component:

```clojure
(rf/reg-view greeting [name] ...)

;; ... elsewhere
[:div
 [greeting "Mike"]
 [greeting "Sandra"]]
```

This reads exactly like Reagent. There's nothing to learn. It just happens to work for the multi-frame case because of the registration that's also happening.

An alternative form exists — `(rf/view :my-ns/greeting)` for runtime-id'd dispatch and late-binding (cross-module reference, hot-reload-sensitive call sites) — but the Var idiom is what you'll see most. `view` is the escape hatch for those specific cases.

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
(rf/reg-frame :test/auth-flow      {:preset :test})
(rf/reg-frame :story.cart/loading  {:preset :story})
(rf/reg-frame :ssr.req/abc123      {:preset :ssr-server})
(rf/reg-frame :app/main            {:preset :default})  ;; same as omitting :preset
```

Each preset expands at registration time into a fixed bundle — `:test` redirects `:rf.http/managed` to the canned-success stub (so HTTP fx don't reach the network in tests) and stamps `:drain-depth 100`; `:story` does the same redirect and tightens `:drain-depth` to 16 so runaway dispatch cascades fail fast under a story; `:ssr-server` sets `:platform :server` and wires the server-projection error path. User-supplied keys override the expansion (so you can opt out of any one default), but the preset's name stays in the metadata and is queryable. The expansions are locked — every `:test` frame is configured the same way, every `:story` frame is configured the same way. Adding a fifth preset would be a Spec-change. The full grammar lives in [Spec 002 §Frame presets](../../spec/002-Frames.md#frame-presets--capability-bundles-for-common-configurations).

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

A small but load-bearing detail: every `reg-view`-rendered DOM element receives a `data-rf2-source-coord` attribute pointing back to the registration that produced it.

```html
<button data-rf2-source-coord="counter.core:counter:48:5" ...>+</button>
```

The four colon-separated segments are `<ns>:<sym>:<line>:<col>` — the registration's namespace, the registered symbol, and the source line/column captured at macro-expansion time. (To recover the file path too, look it up via `(rf/handler-meta :view <id>)`; the registration metadata carries `:rf/source-coord-meta` with `:ns`/`:line`/`:column`/`:file`.)

The annotation is **mandatory** in the CLJS reference per [Spec 006](../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1) — every adapter whose host has a DOM-attribute concept injects it. It exists so pair-tools and devtools can take a clicked DOM node and resolve it back to the source line that produced the view; [chapter 11](11-devtools-and-pair-tools.md) walks through what tools do with it.

Two production-elision details matter:

- The annotation is **dev-only** — gated on the universal `re-frame.interop/debug-enabled?` flag (the CLJS mirror of `goog.DEBUG`). `:advanced` builds with `goog.DEBUG=false` strip the attribute via Closure DCE; the rendered HTML in production carries no `data-rf2-source-coord` bytes.
- Components whose outermost return is a React Fragment, a `:>`-prefixed host component, or another non-DOM root are exempt — the runtime can't attach an attribute to a fragment. Pair-tools fall back to the registration's metadata for those nodes.

You don't need to do anything to get the annotation. `reg-view` does it. The mention here is so you recognise the attribute when you see it in the inspector.

## What lives in app-db

The single most common question new re-frame users ask: *what shape should I put my app-db in?*

The honest answer is: whatever shape suits your app. There's no required schema. `app-db` is "your app's state, in one map." How you organise that map is your business.

That said, conventions help:

```clojure
{:auth     {:user nil :loading? false :error nil}
 :cart     {:items [] :checkout-state :idle}
 :articles {:status :loaded :data [...] :loaded-at 1747...}
 :route    {:id :route/home :params {}}
 :ui       {:sidebar-open? true :modal nil}}
```

A top-level key per *feature*. Each feature owns its own slice. No feature reaches into another feature's slice directly — instead, it goes through the other feature's subs (to read) and dispatches the other feature's events (to write).

This **id-prefix-as-namespace** convention extends to the registry: events for the cart feature live under `:cart/...` and `:cart.item/...`; subs that read the cart's slice live under `:cart/items`, `:cart/total`; views live under `:cart/summary`. The whole feature is identifiable by its prefix. Adding or removing a feature is a `git mv` away from being a single coherent unit.

For complex schemas, [Spec 010](../../spec/010-Schemas.md) lets you attach Malli schemas to `app-db` paths so validation happens automatically in dev. We'll see this in [chapter 07](07-server-side.md) when SSR enters the picture.

### Computed values as state — the flow escape hatch

Most derived values you reach for in re-frame2 are subscriptions: the rectangle's area derived from `:width` and `:height`, the cart's total derived from its items, the form's submittable-state derived from validity flags. Subs live in the per-frame sub-cache and are consumed by views.

Sometimes, though, the derived value isn't *just* for views. You want it **in `app-db`**, where:
- another event handler can read it as plain data,
- it survives SSR/hydration round-trips,
- it shows up in the `app-db` inspector,
- a registered schema can cover it.

That's what **flows** are for. A flow is a registered rule: "when these `app-db` paths change, run this pure function and write the result to that `app-db` path." Same compute-on-input-change semantics as subs; different storage location.

```clojure
(rf/reg-flow
  {:id     :rectangle/area
   :inputs [[:width] [:height]]
   :output (fn [w h] (* w h))
   :path   [:area]})
```

Flows are deliberately a **niche convenience**, not a sub replacement. Most derived values are still subs — flows pay an `app-db` write per recomputation, and they're only worth that cost when the derived value is genuinely part of the application's state. A typical re-frame2 app has dozens of subs and one or two flows. Full contract: [Spec 013](../../spec/013-Flows.md).

### Routing as state

A specific case of "feature-as-slice" worth flagging: **routing**. The current route lives in `app-db` at `:route` — a small map of `{:id :params :query :fragment :transition :nav-token}`. Navigation is an event (`:rf.route/navigate`); URL changes are events (`:rf.route/handle-url-change`); the active route is a sub. There's no separate routing runtime, no route-aware components, no "route context" — it's just data that happens to be reflected in the address bar.

The substrate gives you a few things you'd otherwise hand-roll: a deterministic ranking algorithm (so `/users/me` and `/users/:id` always agree on which one wins), per-navigation tokens that ride through async work and let stale results suppress themselves cleanly, fragments as a first-class part of the route slice, and `:can-leave` guards that pause navigation through `:rf/pending-navigation` so the user can confirm or cancel an "unsaved changes?" prompt. The full contract is in [Spec 012](../../spec/012-Routing.md). For this chapter, the load-bearing fact is just: routing is one more slice of `app-db`, not a parallel system.

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
- `app-db` is one map. Conventions: namespace by feature, ids reflect feature/area.

## Next

- [05 — State machines](05-state-machines.md) — when an event handler's logic is a flow, model it as a state machine.
