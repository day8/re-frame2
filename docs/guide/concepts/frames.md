# Frames: isolated worlds

You want two independent copies of your app on one screen. A split pane showing the same widget against different data. A Story canvas rendering one view in three states side by side. A server handling a hundred render requests at once. Or you've just hit `:rf.error/no-frame-context` from a `setTimeout` callback and want to know what it means. Both roads lead here. This page explains the **frame** — re-frame2's isolation boundary — and the one rule everything else falls out of.

> **Coming from Redux?** A frame is a store instance and `frame-provider` is `<Provider store={...}>` — creating a second store gives you a second state tree but the same reducers, and frames work exactly that way: handlers are registered once, state is per-frame. The divergence: there is no default store. A dispatch that can't trace which frame it belongs to fails loudly instead of landing somewhere conventional.

## What a frame is

A frame is one running instance of your app. It owns the runtime state of that instance:

- its **app-db** — the single map this instance's events read and write; it's the whole of this instance's state,
- its **event queue** — the dispatches (requests to run an event) waiting to run against this instance,
- its **subscription cache** — the memoised graph of derived values computed over this instance's state.

A frame deliberately does *not* own the handlers — the functions you register to handle events and subscriptions. The registrations live in an **image**: the set of `reg-event` / `reg-sub` / `reg-view` entries a frame resolves against, lifted into a value. A frame carries a reference to one resolved image; resolving `[:counter/inc]` means looking it up in *that frame's image*. By default every `reg-*` in your program projects into one shared image, so two frames running `[:counter/inc]` run the *same handler function* against *different app-dbs*. That's the whole trick. (When you need two frames to resolve the *same* id to *different* handlers — two examples on one page, a tool beside its target — you give them different images; [Images](images.md) is that story.)

A frame isolates state, not behaviour. You write the app once, and the frame decides which copy of the state it runs against — and which image supplies the behaviour.

That division is why "show two of them side by side" never forces a rewrite. You mount the same app twice, each mount in its own frame, and isolation is total. One app, one frame — until the day you need two, and then nothing leaks.

## The normal case: one app, one frame

Almost every app is a one-frame app, and stays one. You register a frame at boot, establish it at the root of your view tree, and never name it again:

```clojure
(ns my-app.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/reg-event :app/initialise
  (fn [_cofx _event]
    {:db {:screen :home}}))

(rf/reg-sub :screen (fn [db _] (:screen db)))

(rf/reg-view main-view []
  [:h1 "Screen: " (name @(subscribe [:screen]))])

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)   ;; installs the adapter — creates no frame
  ;; Frames come after init! — the adapter must be installed before any
  ;; frame exists. A new frame's app-db is always {} — state arrives the
  ;; only way state ever arrives: via an event. :on-create runs
  ;; synchronously; by the time reg-frame returns, the cascade has settled.
  (rf/reg-frame :app {:on-create [:app/initialise]})
  (rdc/render react-root
    [rf/frame-provider-existing {:frame :app}
     [main-view]]))
```

`frame-provider-existing` scopes the already-registered `:app` frame for everything underneath it, so inside that subtree every `dispatch` and `subscribe` resolves to `:app` without ever naming it. (`dispatch` sends an event into the queue; `subscribe` reads a derived value.) This covers the ones injected into [registered views](views.md) and the ones your event handlers cause. The frame is invisible inside its own scope — and that's a design rule, not an accident. Going multi-frame later must not change a line of your app code, which means single-frame code is forbidden from ever depending on which frame it's in.

Notice that `init!` created no frame. Nothing is implicit about which frame your root uses; you say so, once, at the root.

!!! note "Three lanes meet at startup — keep them apart"

    The two lines above are the *whole* app-author boot lane: **install the substrate with `init!`, then create your frame(s) explicitly.** Two other lanes sit nearby but are not your concern as an app author. **Frame startup** is what each frame does as it comes alive — the `:on-create` event, which seeds app-db or kicks a boot sequence ([Pattern — Boot](../../../spec/Pattern-Boot.md)). **Adapter-author internals** — `install-adapter!`, `destroy-adapter!`, `current-adapter`, and the adapter-spec map — sit one layer *below* `init!`; you reach for them only when writing a substrate adapter, never for ordinary boot. The full three-lane breakdown is the [Lifecycle API chapter](../../api/13-lifecycle.md).

## When you want more than one

The genuine multi-frame cases, roughly in the order you'll meet them:

- **The same widget twice on one page.** A split pane comparing today against last week. Two panes, two frames, zero shared state.
- **Story canvases.** "Show this view empty, loading, and loaded, side by side" is one set of handlers and three frames, each seeded differently. The Story runner allocates them; you mostly don't see it.
- **A fresh frame per test.** Each test gets its own frame, torn down after, so no test can leak state into the next — see [Test a full cascade](../how-to/test-a-cascade.md).
- **A frame per server request.** [Server-side rendering](ssr.md) creates a frame per HTTP request, runs the app in it, serialises, destroys it. A hundred concurrent requests are a hundred isolated app-dbs.

The shape is the same every time: one app, mounted N times, each mount fully isolated. Multi-frame is never "N half-apps stitched together." Each frame is a complete, self-sufficient world.

Here's the split pane, end to end:

```clojure
;; Adapted from testbeds/multi_frame/core.cljs
(rf/reg-event ::init (fn [_cofx _ev] {:db {:n 0}}))
(rf/reg-event ::inc  (fn [{:keys [db]} _ev] {:db (update db :n inc)}))
(rf/reg-sub :n (fn [db _] (:n db)))

;; Registered once. The injected `dispatch` / `subscribe` resolve against
;; whichever frame this view renders under.
(rf/reg-view counter-panel [label]
  [:div
   [:h3 label]
   [:p "n = " @(subscribe [:n])]
   [:button {:on-click #(dispatch [::inc])} "+"]])

(rf/reg-view split-screen []
  [:div.split
   [rf/frame-provider-existing {:frame :pane/left}  [counter-panel "Left"]]
   [rf/frame-provider-existing {:frame :pane/right} [counter-panel "Right"]]])

;; At boot — after (rf/init! ...) — register the pane frames, then render
;; under the app's root provider (:app, registered in the boot snippet
;; above), like every other view. Providers nest: each pane's provider
;; overrides the root scope for its own subtree.
(rf/reg-frame :pane/left  {:on-create [::init]})
(rf/reg-frame :pane/right {:on-create [::init]})

(rdc/render react-root
  [rf/frame-provider-existing {:frame :app}
   [split-screen]])
```

Notice what isn't there: no pane id threaded through the view, no atom per pane, no "which counter am I" argument on the handler. Click `+` on the left and only the left number moves. Run it and open Xray: pick the left frame and you see only that frame's events and app-db. The right frame's ledger never heard about the click. Frames are how every inspection tool partitions the world.

Borderline case? This is where people hesitate, so here's the single question to ask: would these two things ever sensibly share a piece of state? If yes, they are two views over slices of *one* frame's app-db — components on a page compose by sharing app-db, and that's the point of [having one place](app-db.md). If no — if they're genuinely two separate runs of the app — they're two frames. The two panes never want to share a counter, and that "no" is the signal.

## Frame identity is carried, not found

Now the rule underneath all of this, the one that makes isolation trustworthy:

> **Frame identity is a value that travels with the work.** A dispatch, a subscription, a captured callback — each reads its frame from the context it was *given*: the provider above it, the handler it's running in, the handle that captured it. An operation never goes looking for a frame in the ambient world, and the runtime never invents one from absence.

So a bare `(rf/dispatch [:counter/inc])` works when — and only when — something above it established a frame: the root `frame-provider`, the event handler it's firing from, a `with-frame` block in a test or at the REPL. With no established scope and no carried frame, the operation fails loudly:

```clojure
{:rf.error/id :rf.error/no-frame-context
 :operation   :dispatch
 :event-id    :ws/message-received
 :recovery    :supply-frame}
```

Why an error instead of a sensible default? Because a default would make distant code change meaning silently, which is the kind of bug that costs you a weekend.

!!! warning "A default frame would hide cross-frame leaks"

    Say a frameless dispatch fell through to "the" frame. Your app would work perfectly — right up until a second frame appears (a Story canvas, an inspection tool, an SSR pass). At that point the dispatch lands *somewhere*, with no error, in the wrong world. The carried rule converts that silent cross-frame leak into an immediate, attributed failure at the exact call site that lost its frame. The error is the feature.

From outside any scope — a test, a tool, the REPL — you name the frame explicitly, and the explicit target always wins:

```clojure
(rf/dispatch [::inc] {:frame :pane/left})   ;; explicit target
(rf/subscribe :pane/left [:n])              ;; frame-id-first arity
```

The complete resolution contract — every rule, the full error payload, the frame-metadata grammar — is [the frames spec](../../../spec/002-Frames.md).

> **Coming from re-frame v1?** v1's single implicit app-db becomes one explicit frame you register and establish at the root — one extra line at boot, and nothing else changes. `:rf/default` is a perfectly legal frame id you may *choose*, but it carries no privilege: the runtime never falls back to it.

## The async boundary: capture the frame

There is exactly one place a frame gets lost: a callback built while a frame was in scope, fired later when the scope is gone. A `setTimeout` tick. A promise continuation. A WebSocket `onmessage`. A `window` listener. A third-party SDK calling you back. The provider's scope is render-time knowledge, and a handler's scope ends when the handler returns. So when the callback finally runs, it's on a fresh stack with no frame anywhere — and a bare `dispatch` inside it raises `:rf.error/no-frame-context`.

The fix is always the same move: capture the frame as a value while it's still in scope, and close over it. The capture tool is `frame-handle`:

```clojure
;; Adapted from examples/reagent/websocket/messages.cljs
(defn open-socket!
  "Call from inside an effect handler — opening a socket is an effect,
   not a view's job, and the runtime establishes the frame scope around
   every running handler and its effects. The socket's callbacks fire
   much later, on frameless stacks."
  [url]
  (let [{:keys [dispatch]} (rf/frame-handle)   ;; capture NOW
        socket             (js/WebSocket. url)]
    (set! (.-onmessage socket)
          (fn [e] (dispatch [:ws/message-received (.-data e)])))
    socket))
```

`(rf/frame-handle)` reads the frame in scope *at creation time* and returns a bundle of operations locked to it — `{:frame ... :dispatch ... :dispatch-sync ... :subscribe ...}`. The captured `dispatch` carries its frame inside the closure, so it routes correctly whenever and wherever the socket fires. Trigger the opening effect from the left pane and the socket's messages land in the left frame; trigger it from the right pane and they land in the right one. Same code.

`frame-handle` is the one public carry primitive — reach for it (or an explicit `{:frame …}` opt) for every async / callback / tooling boundary.

And there's one important case where you need none of this: scheduling from inside an event handler. A handler that wants a later dispatch returns effect data — a description of work for the runtime to perform — and the effects carry the frame for you:

```clojure
(rf/reg-event :toast/show
  (fn [{:keys [db]} [_ message]]
    {:db (assoc db :toast message)
     :fx [[:dispatch-later {:ms 3000 :event [:toast/clear]}]]}))
```

`:dispatch` and `:dispatch-later` effects are stamped with the in-flight frame before any timer or microtask boundary — zero ceremony. If the deferred work is just a dispatch, this is the shape.

!!! note "When `frame-handle` is still needed inside an effect handler"

    Reach for `frame-handle` only for callbacks the effect system doesn't mediate — the socket's `onmessage` above, SDK callbacks, `window` listeners — even when the function that wires them up runs inside an effect handler. The effect system carries the frame for the dispatches *it* schedules, not for callbacks you register with the outside world.

This page is the canonical home of the capture pattern. When the [views](views.md) and [subscriptions](subscriptions.md) pages warn "don't dispatch bare from async callbacks," this is the full story they're pointing at.

## The hard rule: subscriptions never reach across frames

A subscription — a derived, cached read over app-db — belongs to one frame. It computes from that frame's app-db and from other subscriptions *in that frame*, never from another frame's state. There is no "read frame B from a sub in frame A" affordance, and you must not build one by sneaking a cross-frame read into a sub's computation function. That's the anti-pattern, full stop.

!!! warning "One cross-frame subscription breaks every per-frame guarantee"

    The reasoning is the same as the carried rule's: isolation is only worth having if it's total. Story variants are reproducible because nothing outside a frame can influence them. SSR requests can run concurrently because no request can observe another. A test frame is hermetic because *nothing* reaches in. One cross-frame sub quietly breaks all three — frame A's derived values now change when frame B does, and every tool that reasons per-frame (the epoch ledger, time-travel, replay) is lying to you about A.

If you feel the need for one, you've answered the discriminator question wrongly: two things that need to share derived state are one frame. Restructure — don't reach across.

## What frames are not

- **Not component-local state.** A frame carries a full app-db, queue, and sub cache; it is heavyweight by design. A dropdown's open flag or a form's draft text goes in the current frame's app-db like always — see [Where should this value live?](../where-state-lives.md).
- **Not routing.** Navigating changes *which slice of app-db matters*, not which frame is running. One frame, many routes.
- **Not micro-frontends.** Frames are N instances of *one* app, each resolving against its image. Two surfaces with disjoint images can share a page ([Images](images.md)), but genuinely different *apps* on one page want iframes — a wall, not a scalpel.

---

**You can now:**

- say what a frame owns (app-db, event queue, sub cache) and what it doesn't (the registrations — those live in the frame's [image](images.md), one shared image by default),
- register one frame and establish it at your root, and explain why `init!` doesn't do it for you,
- mount one app N times — split panes, Story canvases, SSR requests, test fixtures — with `reg-frame` + `frame-provider`, nothing shared,
- read `:rf.error/no-frame-context` as "this callback lost its frame" and fix it with `frame-handle` — or with `:fx` when the work starts in a handler,
- state the hard rule: subscriptions never reach across frames; things that share state are one frame.
