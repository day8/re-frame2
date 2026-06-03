# 18 - Frames

You want two independent copies of your app on one screen — a Story canvas showing the same widget in three states, a split-pane editor with live and preview sides, a server render handling a hundred concurrent requests — and you want them to stop leaking state into each other. This chapter is **frames**, the isolated context that makes that not a nightmare. The good news up front: if your app is one app on one page, you already have exactly one frame, you've been using it the whole time, and you never had to know it existed.

## The request that breaks everything you've built

Let me set the scene with the kind of thing a product manager says on a Tuesday, because the motivating problem is the whole reason frames exist and it's worth feeling the pain before reaching for the cure.

You've got an analytics widget. It's a clean re-frame2 feature: a date-range picker, some events that load data for the selected range, a subscription that derives the chart series, a view that paints it. All the things this guide has been teaching you to build. It works. You're proud of it.

Then:

> *"On the analytics page, can we show today's numbers and last week's numbers side-by-side? Same widget — same buttons, same filters — just running against different data."*

Easy, you think. The view is just a function. Put two of them on the page:

```clojure
[:div.split
 [analytics-panel]      ;; today
 [analytics-panel]]     ;; last week — but how?
```

And then it isn't easy at all, and you feel the floor tilt. The handlers exist once. The subscription exists once. The view exists once. *What there isn't two of is state.* Both panels read the same `app-db`, both write the same `app-db`, so the moment you move the date picker in the left panel the data changes in **both**. You wanted two independent instances. You got one widget rendered twice, sharing a single brain.

Here's the part I want you to notice, because it's the trap. The React-shaped reflex at this exact moment is to reach for local state. Give each panel its own atom. Prop-drill it down into every leaf. Rewire every handler to take the atom as an argument instead of reading `app-db`. And it *works* — for about a day — and then look at what you've done: your handlers are no longer pure functions of `app-db`, your subs are no longer expressible as `reg-sub`, and the entire architecture that made the rest of your app tractable just evaporated the instant you needed two of one thing. You traded the framework for a closure full of atoms. That's not a refactor; that's a defection.

re-frame2's answer is to give each panel its own **frame** — and to do it without touching a single line of the handlers, the sub, or the view.

## What a frame actually is

Here's the one-sentence version, and it's the load-bearing idea the rest of the chapter is consequences of:

**A frame is one running instance of your re-frame2 app.**

That's it. Single-frame apps have one instance. Multi-frame apps have several. The framework treats them identically — nothing in the dispatch pipeline, the interceptor chain, or the subscription graph cares whether the frame it's running against is the default one or the fourteenth one you hand-rolled for a Story.

Mechanically, a frame is an **isolated runtime boundary** identified by a keyword (`:left`, `:test/auth-flow`, `:ssr.req/abc123`). It owns exactly three pieces of runtime state:

- An **`app-db`** — the one immutable map this frame's events read and write.
- A **router queue** — the events waiting to be drained for *this* frame.
- A **subscription cache** — the memoised values of every active `reg-sub` against *this* frame's `app-db`.

And — this is the part that surprises people — here is what a frame emphatically does **not** own:

- **The handler registry.** Every `reg-event-db`, `reg-event-fx`, `reg-sub`, `reg-view`, `reg-fx`, `reg-cofx` you've ever written populates a *single global registry* shared across every frame in the program. Two frames running `:counter/inc` are running the **same handler** against **different `app-db`s**. Say that one back to yourself, because it's the whole trick: **frames isolate state, not behaviour.** You register the behaviour once; you get as many isolated copies of the state as you have frames.

That distinction is what saves the analytics widget. The view, the sub, the handlers don't get duplicated, don't get parameterised, don't learn that there are now two of them. They stay exactly as written. What changes is only *which `app-db` they resolve against* — and the frame is the thing that answers that question.

## When you actually need more than one

Before you go frame-happy, the honest framing: the mental model "one app, one `app-db`, one queue, one sub-cache" is correct for the overwhelming majority of apps. You will write whole real applications and never type the word `frame`. This chapter is for the minority case, and for the one scrap of vocabulary even the majority case has to be able to recognise (`:rf/default`, coming up).

The cases that genuinely want more than one frame, roughly in order of how often you'll meet them:

- **Multiple live instances of the same widget.** The analytics panels. Embedded white-label widgets dropped onto a host page. A split-screen or multi-window UI where each pane is a full running copy.
- **Stories.** [Story](../story/index.md) gives every variant its own frame — *"show this view loaded, loading, and errored, side by side"* is three frames, one set of registered handlers, three different `app-db` values. The Story runner owns the frame allocation; you mostly don't see it.
- **Per-test fixtures.** [Chapter 13 — Testing](13-testing.md) spins up a fresh frame for each test and tears it down after, so no test can leak state into the next. That isolation is *why* re-frame2 tests don't need a browser or a reset-the-world dance between cases.
- **Per-request server-side render.** [Chapter 20 — The server side](20-server-side.md) creates a brand-new frame per HTTP request, runs the SSR cascade against it, serialises the resulting `app-db`, and destroys the frame. A hundred concurrent requests are a hundred isolated `app-db`s; none of them can see another.

And the cases that *look* like multi-frame and absolutely are not — getting this wrong is the most common frames mistake:

- **Different routes in one app.** Routing changes *which slice of `app-db` matters right now*; it does not change which frame is in play. One frame, many routes. ([Chapter 19](19-routing.md) is the whole story.)
- **Different components on one page.** You do not isolate by component. The entire point of `app-db` is that components compose by sharing slices of it through subs. Two components on a page are not two frames; they're two views over one frame.
- **Different *apps* on one page.** That's micro-frontends, and it's explicitly out of scope. Use iframes — the host-page boundary is already the isolation you want, and trying to do it with frames is using a scalpel where you wanted a wall.

If you want a single discriminator to settle any borderline case, it's this question: **would these two instances ever sensibly share a piece of state?** If yes — they're slices of the *same* frame. If no, if they're genuinely two separate runs of the same app — they're separate frames. The analytics panels never want to share their date range; that "no" is the signal that they're two frames.

## `:rf/default` — the frame you've had all along

Every example before this chapter has been running inside a frame. The counter in chapter 03, the cascade in chapter 04, the subscription graph in chapter 05 — all of it. You never saw the frame because the framework pre-registers one for you at load time, named `:rf/default`, and every `dispatch` / `subscribe` that doesn't name a frame quietly resolves against it.

```clojure
;; What you've been writing all this time:
(rf/dispatch [:counter/inc])

;; What the framework actually routes:
(rf/dispatch [:counter/inc] {:frame :rf/default})
```

`:rf/default` is not a special case bolted onto the side. It's a completely ordinary frame sitting in the registry, listable by your tooling, addressable by keyword like any other. The *only* special thing about it is that the framework registers it on your behalf, so single-frame apps get to pretend frames don't exist.

And that's the real payoff of the design: the mental shift from "this is *the* `app-db`" to "this is *one frame's* `app-db`" costs you **nothing** in single-frame code — `:rf/default` is invisible scaffolding — and it's *exactly* the shift you'll already have made the day you grow a second frame. You don't refactor your way into multi-frame. You just stop letting the default be implicit.

## Creating a frame

Two shapes, and you pick between them on one question: will you dispatch into this frame by name from somewhere else?

```clojure
;; Named, registered up front. You'll address this frame
;; from elsewhere using its keyword.
(rf/reg-frame :left
  {:on-create [:counter/initialise]})

;; Anonymous, gensym'd id. You hold the returned keyword
;; and tear it down explicitly.
(let [f (rf/make-frame {:on-create [:counter/initialise]})]
  ;; ... use f for as long as the surrounding code needs it ...
  (rf/destroy-frame! f))
```

`reg-frame` is for frames whose identity is fixed at app-load — the two analytics panels, named Story variants, the SSR frame you'll dispatch into from request-handler code. `make-frame` is for frames whose whole lifecycle is owned by the surrounding code — tests, per-mount devcards, a modal stack — where there's no name worth choosing and the gensym (a `:rf.frame/`-namespaced keyword) is exactly right. Both end in the same place: a frame in the registry, addressable by keyword, its `app-db` seeded by `:on-create`.

### `:on-create` — how a frame's state gets seeded

A freshly-created frame's `app-db` is **always `{}`**. There is no `:db` config slot, no "initial state" parameter, no escape hatch. This is deliberate and it's consistent with everything else in the framework: state arrives the one and only way state ever arrives in re-frame2 — *via an event*. `:on-create` is the single event vector the framework `dispatch-sync`s into the new frame the instant it's created.

```clojure
(rf/reg-event-db :counter/initialise
  (fn [_ _] {:count 0 :history [0]}))

(rf/reg-frame :left {:on-create [:counter/initialise]})
;; By the time reg-frame returns, :left's app-db is {:count 0 :history [0]}.
```

Need to fire several init events? The single `:on-create` handler does it through its effect map — `:fx [[:dispatch [:counter/restore]] [:dispatch [:prefs/load]]]` — and run-to-completion guarantees those cascades fully settle before `reg-frame` returns. There's a symmetric `:on-destroy` slot for teardown effects, and `reg-frame` accepts a broader metadata grammar (`:interceptors`, `:on-error`, `:platform`, and the presets below) that downstream chapters introduce as each surface needs it.

### Re-registering, resetting, destroying

The three lifecycle verbs round out the surface:

- **`reg-frame` on a name that already exists** is a *surgical update*: the config is replaced, but the live runtime state (`app-db`, queue, sub-cache) is **preserved** and the `:on-create` event does **not** re-fire. This is the hot-reload-friendly shape — re-saving a file that re-runs your `reg-frame` calls won't blow away the state you were looking at, exactly the way `app-db` doesn't reset when you save a file today.
- **`reset-frame!`** is the explicit "I actually do want the init cascade again" verb — equivalent to a `destroy-frame!` followed by a fresh `reg-frame` with the current config, so `:on-create` re-fires against a `{}` `app-db`.
- **`destroy-frame!`** tears the frame down: it fires `:on-destroy`, then removes the keyword from the registry. A subsequent `dispatch` / `subscribe` against that keyword throws (`:reason :frame-destroyed`) — a destroyed frame is gone, not silently re-defaulted.

## Targeting a frame — and why you mostly won't have to

From *outside* a view — a REPL session, a test, framework-level code — you name the frame explicitly with the `{:frame ...}` opt:

```clojure
(rf/dispatch  [:counter/inc] {:frame :left})   ;; dispatch — opts map
(rf/subscribe :right [:count])                 ;; subscribe — frame-id positional
```

(The shapes differ for historical reasons — `dispatch` carries other opts in that map; `subscribe`'s out-of-view callers are tooling-shaped. Don't read meaning into the asymmetry.) The `{:frame ...}` opt is the **explicit override** — first-class routing for tools, tests, SSR boot code, and fx handlers. Everything else in this chapter is a more ergonomic way of *not* having to write it at every call site.

But here's the thing you'll actually do day-to-day: *inside* a registered view, you **never write `{:frame ...}`**. Look back at the analytics panels — the whole point was that the view function doesn't know it's been instantiated twice. So how does its `dispatch` know which frame to hit?

The answer is `frame-provider`, and it's the mechanism the view-side of the chapter is built around. It wraps a subtree, carries a frame keyword down through React context, and the `dispatch` / `subscribe` that `reg-view` auto-injects into the view's body resolve against it:

```clojure
[:div.analytics
 [rf/frame-provider {:frame :left}
  [analytics-panel]]                   ;; this subtree reads/writes :left
 [rf/frame-provider {:frame :right}
  [analytics-panel]]]                  ;; this subtree reads/writes :right
```

Two instances of the *same* registered view; two frames; each subtree's `dispatch` / `subscribe` silently routed to its own `app-db`. The view function is frame-blind — it just calls `(dispatch [...])` and `(subscribe [...])` — and the framework routes correctly based on which provider it finds itself under. That frame-blindness is the load-bearing reason to register views with `reg-view` rather than writing bare `defn` functions: a registered view's body can be instantiated under any frame and Just Works.

(`frame-provider` is React-context-driven, so it's substrate-specific in *mechanism* — every adapter, Reagent / UIx / Helix, reads and writes the same context object. The *pattern* — every dispatch and subscribe targets a specific frame, by whatever means the host language offers — is what survives across substrates and ports.)

## The split-counter, end to end

Let's put it all together with the smallest example that exercises every piece. Two counters, side by side, fully isolated, zero shared state:

```clojure
;; Registered once — same handlers, sub, and view for BOTH counters.
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

;; Mount both, each scoped to its frame.
(defn ^:export run []
  (rdc/render root
    [:div.split
     [rf/frame-provider {:frame :left}  [counter]]
     [rf/frame-provider {:frame :right} [counter]]]))
```

That's the whole thing, and I want you to notice what isn't in it. There's no parameter threaded through the view. There's no atom. There's no "which counter am I" argument on the handler. The handlers, the sub, and the view *do not know there are two of them* — they were written for one app and they're running in two, unchanged. Click `+` on the left and only the left number moves; the right frame's `app-db` never hears about it. The architecture did the isolation. You just told each subtree which `app-db` it lives in.

Go back and reread the analytics problem from the top of the chapter. The "but how?" is now a `frame-provider` and a `reg-frame`, and not one line of the feature changed.

## The async-boundary problem — why a bare `dispatch` can leak

The split-counter works because of a quiet bit of magic in the last section, and it's the magic that the rest of this chapter is about. Inside a `reg-view` body the injected `dispatch` knows it's under `:left` because `reg-view` reads the frame from React context **at render time** and bakes it into the `dispatch` closure. The `:on-click` lambda closes over that closure, so when the click fires — long after render unwound — it still dispatches into `:left`. The frame rode along inside the closure.

Now break that. Render time is not the only moment a callback gets created, and React context is **render-only knowledge** — it's gone the instant render returns. The moment your callback is built somewhere *other* than directly in a `reg-view` body — or fires across an async boundary — the ambient "I'm under `:left`" knowledge has evaporated, and a bare `dispatch` falls through to `:rf/default`. That's a state leak: the left panel's WebSocket message lands in the wrong `app-db`.

The cases where this bites:

- A `setTimeout` / `setInterval` callback.
- A `Promise.then` / `js/await` continuation.
- A WebSocket / `EventSource` `onmessage` handler.
- An `IntersectionObserver` / `MutationObserver` callback, a `requestAnimationFrame` tick.
- A raw `window.addEventListener` handler — a drag flow that registers `pointermove` / `pointerup` on `window` so the move/up fire *outside* the React tree after render unwound.
- A callback handed to a third-party SDK that calls you back later.

The unifying property: the callback is **constructed in one synchronous moment when the frame is still resolvable, but invoked later, on a fresh stack, after the binding is gone.** The fix is always the same shape — capture the frame at the synchronous moment, carry it into the callback as a closure value. The frame-affordance surface is exactly the set of tools for doing that capture cleanly.

## `frame-handle` — the keystone affordance

`frame-handle` is the answer to "I need to dispatch (or subscribe) from a callback that fires later." It captures the frame **at creation time** and hands you back an **operation bundle** — a map of frame-locked ops:

```clojure
(rf/frame-handle)            ;; capture the ambient frame (current-frame-id)
(rf/frame-handle :rf/xray)   ;; bundle locked to an explicit frame-id
;; =>
{:frame         <id>
 :dispatch      (fn ([event] [event opts]))
 :dispatch-sync (fn ([event] [event opts]))
 :subscribe     (fn [query-v])}
```

Build the handle while the frame is still ambient — inside a `reg-view` body, inside an event handler, under a `with-frame` — and from then on every op on the bundle targets the captured frame, no matter when or where it fires. The capture is **locked**: a per-call `:frame` opt cannot override it, so you can't accidentally re-route a handle. (One nuance worth holding: the handle is an *operation bundle*, not a container — to read a frame's `app-db` value you call `(rf/app-db-value (:frame handle))`, not the handle itself.)

The WebSocket case, end to end:

```clojure
(rf/reg-view stream-view [_]
  (let [{:keys [dispatch]} (rf/frame-handle)]      ;; captures the render frame
    (ws/subscribe! (fn [msg] (dispatch [:ws/incoming msg])))
    [:div "streaming…"]))
```

`ws/subscribe!`'s callback fires minutes later, on a socket turn with no React context and no dynamic binding — and it still dispatches into the right frame, because the frame was baked into `dispatch` when the handle was built during render. A left-panel stream lands in `:left`; a right-panel stream lands in `:right`; you wrote the view once.

The same handle covers the raw-`addEventListener` drag flow: capture `(rf/frame-handle)` in the `:on-pointer-down`, close over its `dispatch` in the `pointermove` / `pointerup` listeners you attach to `window`, and the move/up dispatches route correctly even though they fire entirely outside the React tree.

## `frame-bound-fn` / `frame-bound-fn*` — when the value is an arbitrary fn

`frame-handle` is the right tool when the thing you're carrying across the boundary is a *dispatch* or *subscribe*. Sometimes it isn't — sometimes the value you must carry is an arbitrary function whose *body* needs to re-establish the frame (a fn that itself calls `current-frame-id`, or makes several dispatches and subscribes, or threads through a helper that does). For that, you want the frame re-bound around the whole fn body, and that's what `frame-bound-fn` and its `*`-twin do:

```clojure
;; Macro form — `(fn ...)` syntax + frame-capture in one step.
(rf/frame-bound-fn [msg]
  (rf/dispatch [:ws/incoming msg]))          ;; closure carries the captured frame

;; *-twin fn form — wrap an existing fn value (HoF / programmatic wrap).
(rf/frame-bound-fn*
  (fn [msg] (rf/dispatch [:ws/incoming msg])))

;; *-twin with an EXPLICIT frame-id — no surrounding with-frame / provider
;; needed at wrap time. The shape for module-level install! routines.
(rf/frame-bound-fn* :rf/xray
  (fn [_e mode] (rf/dispatch [:set-mode mode])))
```

Both produce a fn that runs its body inside a `binding` that re-establishes the captured frame — so plain `dispatch` / `subscribe` *inside* the wrapped body pick up the right frame, however deep the call chain goes and whenever the call fires.

The rule of thumb: **reach for `frame-handle` for the common dispatch/subscribe case; reach for `frame-bound-fn` / `frame-bound-fn*` when you're wrapping an arbitrary fn whose body re-establishes the frame.** Use the macro (`frame-bound-fn`) when you want `(fn ...)` syntax inline; use the `*`-twin (`frame-bound-fn*`) when you already hold a fn value or need to name the frame explicitly at wrap time.

### A look-alike that is *not* this bug

There's an adjacent failure mode worth naming so you don't reach for the wrong tool. "My view doesn't update when I click" sometimes looks like a lost-frame bug but is actually a Reagent reactive-tracking failure — a lazy `(for ...)` in a view body whose elements deref subscriptions, where the deref happens outside the render's tracking scope. The fix for *that* is to realise the seq inside render (`doall` / `mapv` / `into`), not a frame affordance. Reach for `frame-handle` / `frame-bound-fn` only when you have a genuine async boundary (timer, promise, socket, out-of-tree listener); reach for `doall` when a lazy seq is swallowing the deref. The two are not interchangeable.

### The handler-side shortcut: `:fx`

One important case where you *don't* reach for any of the above: dispatching from inside an **event handler**. The router binds the in-flight frame for the duration of a handler, so a synchronous `(rf/dispatch [:child])` from a handler body routes to the handler's own frame automatically. And when a handler wants to schedule a *later* dispatch — an HTTP reply, a timer — it returns effect data rather than calling host async directly:

```clojure
;; canonical: the fx walker threads the frame through for you
{:fx [[:dispatch [:next-step]]
      [:dispatch-later {:ms 500 :event [:tick]}]]}
```

The `:dispatch` and `:dispatch-later` effects capture the in-flight frame in their closure before the timer or microtask boundary, so the deferred dispatch carries the right frame with zero ceremony. This is *the* multi-frame pattern for handler-emitted work — prefer it over a manual `js/setTimeout` whenever the dispatch originates from a handler. `frame-handle` is for the cases `:fx` can't reach: a callback handed to a non-fx async library, or one set up directly in a view body.

## Scoping a frame from code: `with-frame` and `with-new-frame`

`frame-provider` scopes a frame to a *React subtree*. Outside the view layer — in tests, at the REPL, in framework-level code — you scope a frame **lexically** instead, with two macros that bind the ambient frame for the duration of a body:

```clojure
;; Pin to a frame that already exists. Not created, not destroyed.
(rf/with-frame :scratch
  (rf/dispatch-sync [:counter/inc])      ;; routes to :scratch via the ambient binding
  @(rf/subscribe [:count]))              ;; also :scratch

;; Create + own + destroy in one block — the common test shape.
(rf/with-new-frame [f (rf/make-frame {:on-create [:counter/init]})]
  (rf/dispatch-sync [:counter/inc])      ;; routes to f
  ;; ... assertions ...
  )                                      ;; f is destroyed on exit, success or throw
```

`with-frame` pins the ambient frame to an existing frame-id; the frame outlives the block. `with-new-frame` is the eval-bind-run-destroy form — it evaluates an expression that *creates* a frame (`make-frame`, or a `reg-frame` that returns its keyword), binds the result, runs the body with that frame ambient, and destroys it on the way out regardless of how the body exits. That tear-down-on-exit is exactly the isolation [chapter 13](13-testing.md) leans on — a fresh frame per test, gone before the next one starts, no try/finally.

One sharp edge that ties back to the async section: **the lexical binding these macros establish is render-and-call-time only.** An async closure that fires *after* the body returns has missed it — and with `with-new-frame` the frame has already been destroyed by then. If a body sets up a callback that fires later, capture the frame with `frame-handle` / `frame-bound-fn` *inside* the body, before it returns.

## Reading and listing frames

Rounding out the surface, the read / introspection verbs — what tools, tests, and the REPL use to look at a frame from outside:

- **`(rf/current-frame-id)`** — the active frame at the call site, resolved through the chain (dynamic var → React context → `:rf/default`). A keyword.
- **`(rf/app-db-value frame-id)`** — the current `app-db` of a frame as a plain map (the deref'd *value*, no container, no reactivity). `nil` if the frame isn't registered. This is how you assert against a frame's state in a test, and how a handle's owner reads the state behind the ops.
- **`(rf/snapshot-of path)` / `(rf/snapshot-of path {:frame id})`** — convenience over `(get-in (rf/app-db-value frame-id) path)`; resolves the current frame when you don't pass one.
- **`(rf/frame-ids)`** / **`(rf/frame-meta frame-id)`** — list the registered frames and read a frame's effective metadata. The shape your tooling walks.

There's also `app-db-container` — but it returns the underlying substrate-managed *reactive cell* (an atom under stock Reagent, a different cell under other substrates), and it exists for **framework internals, tools, and adapter code** that need to read or replace the container itself. Application code never wants it: handlers receive `db` through coeffects, views read via subscriptions, and tests assert against `app-db-value`. If you find yourself reaching for `app-db-container` in app code, you've almost certainly taken a wrong turn — the value accessor is what you want.

## Frames are not "components with state"

There's a failure mode that shows up the moment frames click for someone, and I want to head it off, because it's seductive and it's wrong. The temptation is: *frames give per-instance isolation — so I'll give every reusable widget its own frame and scope all its local state inside it.* A frame per tooltip. A frame per dropdown. A frame as a fancy `useState`.

**Don't.** This is the single biggest way to misuse the feature.

Frames are **heavyweight runtime objects**. Each one carries a full `app-db`, its own event queue, its own subscription cache, its own router context. They exist for the case where the *whole app* genuinely runs in isolation — Story variants, SSR requests, multi-window apps, the side-by-side analytics panels. They are not, and were never meant to be, a `useState` replacement.

For honest component-level state — a tooltip's hover bit, a dropdown's open flag, a form's draft text — you do what re-frame2 has always done: put it in `app-db` (the *current* frame's `app-db`, whichever frame you're in), write an event to update it, write a sub to read it. Sharing *parts* of state with specific UI surfaces is the job of sub and event composition, not frame allocation. The frame is the wrong granularity for "this dropdown is open" by several orders of magnitude.

The same discriminator from earlier settles it cleanly: *if two instances might ever want to share state, they aren't separate frames — they're separate slices of one frame's `app-db`.* A tooltip and the form it annotates absolutely share state. They're one frame.

## Presets — making intent visible at the call site

Most frames you'll ever register fall into one of four shapes: a normal client app, a per-test fixture, a Story variant, a per-request SSR frame. Hand-writing the metadata bundle for each one every time would be both repetitive *and* — worse — would bury the intent of the call site under boilerplate. So re-frame2 ships a closed set of four canonical presets that expand at registration into a fixed bundle of metadata:

```clojure
(rf/reg-frame :test/auth-flow      {:preset :test})
(rf/reg-frame :story.counter/empty {:preset :story})
(rf/reg-frame :ssr.req/abc123      {:preset :ssr-server})
```

The same `:preset` key works on `make-frame` too, with the same expansion. The win is legibility: a reader skimming the source can tell at a glance that *this* is a test frame and *that* one is a Story variant, without decoding a metadata map. The expansion is locked — four presets, no more — which keeps the set canonical for AI scaffolding and for cross-codebase recognition. [Chapter 20](20-server-side.md), [chapter 13](13-testing.md), and the [Story tutorial](../story/index.md) each introduce the preset they need in the context that needs it.

The chapters that exercise multi-frame in anger are all downstream of this one — [testing](13-testing.md) uses the per-test fixture and `with-new-frame`, [Story](../story/index.md) the frame-per-variant, [the server side](20-server-side.md) the per-request frame. Each of those walks its own surface. This chapter is the substrate they all stand on: a frame is one isolated instance of your app, the framework gives you the first one free, everything you've already learned runs inside it unchanged, and when a callback has to outlive its render you capture the frame with a handle and carry it across.
