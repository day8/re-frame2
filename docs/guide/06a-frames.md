# 06a — Frames

## TL;DR

You want multiple instances of the same app's state — a split-screen widget, a story variant, a per-test fixture, a per-request server-side render — without them sharing `app-db`, event queue, or sub cache. This page explains the **frame** boundary that gives you that, and why most apps never have to name one.

The mental model "one app, one `app-db`, one queue, one sub-cache" is right for 90% of apps. This chapter is for the other 10%, and for the small piece of vocabulary even the 90% case has to recognise — `:rf/default`.

## The motivating need

A real example, lifted from the kind of dashboard most production apps end up with. The product manager has just said:

> *"On the analytics page, can we show today's numbers and last week's numbers side-by-side? Same widget — same buttons, same filters — just running against different data."*

You write the view as a registered view called `[analytics-panel]`. You drop two of them onto the page:

```clojure
[:div.split
 [analytics-panel]      ;; today
 [analytics-panel]]     ;; last week — but how?
```

The handlers exist once. The subs exist once. The view exists once. *What it doesn't have is two independent slots of state.* If both panels write to the same `app-db`, the date-range picker in one panel will move the data in both. The whole point of the request was that they shouldn't.

The Reagent way out is `useState`-shaped: each panel maintains its own local atom, prop-drill it into every leaf, wire every handler to take it as an argument. The handlers stop being pure functions of `app-db`; the subs stop being expressible as `reg-sub`; the architecture that made the rest of the app tractable evaporates the moment you have two of anything.

The re-frame2 way out is to give each panel its own **frame**.

## What a frame actually is

A frame is an **isolated runtime boundary**, identified by a keyword. It owns three pieces of runtime state:

- An **`app-db`** — the single immutable map this frame's events read and write.
- A **router queue** — the events waiting to be drained for this frame.
- A **subscription cache** — the memoised values of every active `reg-sub` against this frame's `app-db`.

What a frame does *not* own:

- **The handler registry.** `reg-event-db`, `reg-event-fx`, `reg-sub`, `reg-view`, `reg-fx`, `reg-cofx` all populate a single global registry shared across every frame. Two frames running the same `:counter/inc` event are running *the same handler against different `app-db`s*. Frames isolate state, not behaviour.
- **A reference handle.** User code holds keywords (`:left`, `:right`, `:test/auth-flow`). The framework holds the underlying frame records. You can't accidentally use a stale frame value or compare two frames for identity — keyword equality is the only equality there is.

The lens that makes the rest of the chapter click: **a frame is one running instance of your re-frame2 app.** Single-frame apps have one. Multi-frame apps have several. The framework treats them identically; nothing about the dispatch pipeline cares whether the frame is the default or a hand-rolled one.

## When you need more than one

The canonical multi-frame use cases, roughly in order of how often you'll meet them:

- **Multiple live instances of the same widget.** The motivating example above — two panels, two date ranges, one widget definition. Devcards on a documentation page, embedded white-label widgets on a host page, multi-window or split-screen UIs.
- **Stories.** [Story](../story/index.md) gives every variant its own frame. *"Show this view in the loaded state, the loading state, and the error state, side by side"* — three frames, one set of registered handlers, three different `app-db` values. The story tool owns the frame allocation; you don't see it directly.
- **Per-test fixtures.** [Chapter 13 — Testing](13-testing.md) creates a fresh frame for each test and tears it down at the end, so no test leaks state into the next. `with-frame` and `make-frame` are the test-side primitives.
- **Per-request server-side render.** [Chapter 11 — The server side](11-server-side.md) creates a new frame per HTTP request, runs the SSR cascade against it, serialises the resulting `app-db`, and destroys the frame. Concurrent requests can't pollute each other because they each have their own `app-db`.

The cases that look like multi-frame and aren't:

- **Different routes in one app.** Routing changes which slice of `app-db` matters at any moment; it doesn't change which frame is in play. One frame, many routes — [chapter 17](17-routing.md) walks through it.
- **Different components on one page.** You don't isolate by component. The whole point of `app-db` is that components compose by sharing slices of it through subs.
- **Different *apps* on one page.** That's micro-frontends, explicitly out of scope. Use iframes; the host-page boundary is already what you need.

The discriminator: *would these two instances ever sensibly share a piece of state?* If yes, they're slices of the same frame. If no — if they're genuinely two runs of the same app — they're separate frames.

## Creating a frame

Two API shapes, picking one mostly by whether you'll dispatch into the frame by name.

```clojure
;; Named, registered up-front. You'll address this frame from
;; elsewhere using its keyword.
(rf/reg-frame :left
  {:doc       "Left-hand counter."
   :on-create [:counter/initialise]})

;; Anonymous, gensym'd id. You hold the returned keyword and
;; tear it down explicitly.
(let [f (rf/make-frame {:on-create [:counter/initialise]})]
  ;; ... use f for as long as the surrounding code needs it ...
  (rf/destroy-frame! f))
```

`reg-frame` is the right shape for frames whose identity is fixed at app-load (the two analytics panels, named story variants, the SSR cascade you'll dispatch into from request-handler code). `make-frame` is the right shape for frames whose lifecycle is controlled by surrounding code — tests, per-mount devcards, modal stacks — where there's no pre-chosen name and you want the gensym.

Both end with the same shape: a frame in the registry, addressable by keyword, with its `app-db` initialised by the configured `:on-create` event.

### `:on-create` — how a frame's `app-db` gets seeded

A freshly-created frame's `app-db` is **always `{}`**. There's no `:db` config slot, no "initial state" parameter. State arrives the only way state ever arrives in re-frame2: via an event. `:on-create` is the single event vector the framework dispatch-syncs into the new frame the moment it's created.

```clojure
(rf/reg-event-db :counter/initialise
  (fn [_ _] {:count 0 :history [0]}))

(rf/reg-frame :left {:on-create [:counter/initialise]})
;; By the time reg-frame returns, :left's app-db is {:count 0 :history [0]}.
```

If you need to fire multiple init events, the single `:on-create` handler does so via its effect map — `:fx [[:dispatch [:counter/restore]] [:dispatch [:counter/preferences-load]]]`. Run-to-completion guarantees those cascades settle before `reg-frame` returns.

There's a symmetric `:on-destroy` slot for teardown effects. `reg-frame` also accepts a broader metadata grammar — `:fx-overrides`, `:interceptors`, `:drain-depth`, `:on-error`, `:platform` — that the rest of this chapter introduces as each surface comes up.

## Targeting a specific frame

Every dispatch and every subscribe takes a frame argument when targeting a specific frame from outside a registered view:

```clojure
(rf/dispatch  [:counter/inc] {:frame :left})   ;; dispatch — opts map
(rf/subscribe :right [:count])                  ;; subscribe — frame-id positional
```

The shapes differ for historical reasons (`dispatch` carries other opts like `:fx-overrides`; `subscribe`'s out-of-view callers are tooling-shaped). This is the **explicit-frame addressing** form. It's the canonical way to target a frame from REPL sessions, test code, or framework-level callers that don't sit inside a view.

Inside a registered view's body, you *don't* write `{:frame ...}` explicitly — `dispatch` and `subscribe` are auto-bound to the surrounding frame by the `reg-view` macro. That's the load-bearing reason to register views: the view's body is frame-blind, and the framework routes correctly regardless of which frame the view has been instantiated under.

## `frame-provider` — scoping a frame to a subtree

The mechanism that makes a registered view *know* it's running inside `:left` (and not `:right`) is `frame-provider`. It wraps a subtree, carries the frame keyword through React context, and the `reg-view`-injected `dispatch`/`subscribe` resolves against it:

```clojure
[:div.analytics
 [rf/frame-provider {:frame :left}
  [analytics-panel]]                   ;; reads/writes :left
 [rf/frame-provider {:frame :right}
  [analytics-panel]]]                  ;; reads/writes :right
```

Two instances of the same registered view; two frames; each subtree's `dispatch`/`subscribe` resolves to its own frame. The view function doesn't know it's been instantiated twice with different state — which is exactly what we wanted from the motivating example.

`frame-provider` is a Reagent-specific (React-context-driven) construct. The *pattern* doesn't require React context — what it requires is that every dispatch/subscribe targets a specific frame, by whatever mechanism the host language provides. That's the part that survives across hosts.

## The split-counter — putting it together

A pedagogical worked example. Two counters, side by side, fully isolated:

```clojure
;; Registered events, sub, view — same for both counters.
(rf/reg-event-db :counter/initialise (fn [_ _] {:count 0}))
(rf/reg-event-db :counter/inc        (fn [db _] (update db :count inc)))
(rf/reg-sub      :count              (fn [db _] (:count db)))

(rf/reg-view counter []
  [:div
   [:button {:on-click #(dispatch [:counter/inc])} "+"]
   [:span @(subscribe [:count])]])

;; Two frames, one per side.
(rf/reg-frame :left  {:on-create [:counter/initialise]})
(rf/reg-frame :right {:on-create [:counter/initialise]})

;; Mount both, scoped.
(defn ^:export run []
  (rdc/render root
    [:div.split
     [rf/frame-provider {:frame :left}  [counter]]
     [rf/frame-provider {:frame :right} [counter]]]))
```

That's the whole thing. Same registered handlers. Same registered sub. Same registered view. Two frames. Each click increments only its own side. The handlers, the sub, and the view don't know there are two of them — *the architecture handles the isolation*. The frame-provider tells each subtree which `app-db` it's reading from and writing to; the registered view's auto-injected `dispatch`/`subscribe` picks that up; routing does the rest.

## `:rf/default` — the frame you've been using all along

Every example before this chapter — the counter in chapter 03, the events-and-effects walkthrough in chapter 04, the schema-bound counter in 04a, the views in 06 — has been running inside a frame. You just didn't see it. The framework pre-registers a frame called `:rf/default` at load time, and every `dispatch` / `subscribe` that doesn't specify a `:frame` resolves against it.

```clojure
;; What you've been writing:
(rf/dispatch [:counter/inc])

;; What the framework actually routes:
(rf/dispatch [:counter/inc] {:frame :rf/default})
```

This isn't a special case. `:rf/default` is a regular frame in the registry, listable in tooling, addressable by keyword. The single special thing about it is that the framework registers it for you so single-frame apps never have to think about frames at all. The `:rf/get-frame-db :rf/default` call you saw in the chapter-02 tests is reading the default frame's `app-db` by name — exactly the same call shape you'd use against any other frame.

The mental shift from "this is the app-db" to "this is one frame's app-db" costs nothing in single-frame code (`:rf/default` is invisible scaffolding) and is exactly the shift you'll need the moment you grow a second frame.

## Frames are not "components with state"

There's a temptation, when frames first click, to use them like React components — give every reusable widget its own frame, scope all per-instance state inside it. **Don't.**

Frames are heavyweight runtime objects. Each one has its own `app-db`, its own event queue, its own subscription cache, its own router context. They exist for cases where the *whole app* genuinely runs in isolation — story variants, SSR requests, multi-window apps, the analytics-panel side-by-side. They don't exist to be a `useState` replacement.

For "component-level state" — a tooltip's hover bit, a dropdown's open/closed flag, a form's draft text — you do what re-frame has always done: put the data in `app-db` (the single frame's `app-db`, whichever frame you're in), write events to update it, write subs to read it. Composition of subs and events is how shared state gets *parts* of itself made available to specific UI surfaces. The frame is the wrong granularity for that.

A useful test: *if two instances might want to share state under any circumstance, they're not separate frames.* They're separate slices of the same frame's `app-db`.

## Multi-frame in tests, stories, and SSR — pointers

The chapters that exercise the multi-frame story in anger are downstream:

- **[Chapter 13 — Testing](13-testing.md)** — `with-frame` and `make-frame` as the per-test fixture. The pattern is "create a fresh frame, dispatch a sequence of events, assert against the frame's `app-db`, tear down." Tests that span more than one event use `with-frame`'s lexical-binding form so you don't write `{:frame f}` on every dispatch.

- **[Story](../story/index.md)** — every story variant is a frame, allocated by the story runner. The variant's `:events` slot is a sequence of regular event vectors that dispatch into that frame; the canvas renders against it. The frame disappears when the variant is unmounted.

- **[Chapter 11 — The server side](11-server-side.md)** — the per-request frame. The SSR adapter calls `(rf/make-frame {:preset :ssr-server :on-create [:rf/server-init request]})`, runs the cascade, reads the resulting `app-db` for hydration shipping, and destroys the frame. Concurrent requests don't see each other.

Each chapter walks its own surface; this chapter is the substrate they all stand on.

## Frame presets — a brief mention

Most frames you'll register fall into one of four shapes: a normal client app, a per-test fixture, a story variant, a per-request SSR frame. Writing the metadata for each by hand each time would be repetitive *and* would make the intent of the call site invisible. So re-frame2 ships a closed set of four canonical presets — `:default`, `:test`, `:story`, `:ssr-server` — that expand at registration time into a fixed bundle of metadata keys:

```clojure
(rf/reg-frame :test/auth-flow      {:preset :test})
(rf/reg-frame :story.counter/empty {:preset :story})
(rf/reg-frame :ssr.req/abc123      {:preset :ssr-server})
```

Each preset's intent is **visible at the call site** — a reader of the source can tell at a glance that this is a test frame, that one is a story variant. The expansion is locked, which keeps the four canonical for AI scaffolding. Chapter 11, chapter 13, and the [Story tutorial](../story/index.md) introduce each preset in the context that needs it.

## What we covered

- A frame is an isolated runtime boundary: its own `app-db`, queue, sub-cache, identified by a keyword.
- Single-frame apps run inside the pre-registered `:rf/default`. You never see it; it's invisible scaffolding.
- Multi-frame apps are about *multiple instances of the same handlers* — split widgets, stories, tests, SSR. Not about isolating components.
- `reg-frame` is for named frames; `make-frame` is for per-mount anonymous frames.
- `:on-create` is the event that seeds the frame's `app-db`. Frames always start with `{}`.
- Inside a registered view, `dispatch`/`subscribe` resolve to the surrounding frame via `frame-provider`. Outside a view, pass `{:frame :id}` explicitly.
- Frames are not "components with state" — they're heavyweight, app-shaped runtime objects. Use `app-db` slices for per-instance widget state.

## Cross-references

- [chapter 06 — Views and frames](06-views-and-frames.md) — `reg-view`, `frame-provider`, and the split-counter in context.
- [chapter 13 — Testing](13-testing.md) — `with-frame`/`make-frame` as the per-test fixture; the `:test` preset.
- [Story](../story/index.md) — frame-per-variant; the `:story` preset.
- [chapter 11 — The server side](11-server-side.md) — per-request frame; the `:ssr-server` preset.

## Next

- [07 — Interceptors](07-interceptors.md) — the sandwich wrapping every event handler's invocation; the place where per-frame `:interceptors` get prepended.
