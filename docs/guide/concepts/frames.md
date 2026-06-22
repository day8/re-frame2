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
  ;; only way state ever arrives: via an event. :initial-events run
  ;; synchronously; by the time reg-frame returns, the cascade has settled.
  (rf/reg-frame :app {:initial-events [[:app/initialise]]})
  (rdc/render react-root
    [rf/frame-provider-existing {:frame :app}
     [main-view]]))
```

`frame-provider-existing` scopes the already-registered `:app` frame for everything underneath it, so inside that subtree every `dispatch` and `subscribe` resolves to `:app` without ever naming it. (`dispatch` sends an event into the queue; `subscribe` reads a derived value.) This covers the ones injected into [registered views](views.md) and the ones your event handlers cause. The frame is invisible inside its own scope — and that's a design rule, not an accident. Going multi-frame later must not change a line of your app code, which means single-frame code is forbidden from ever depending on which frame it's in.

Notice that `init!` created no frame. Nothing is implicit about which frame your root uses; you say so, once, at the root.

### What `reg-frame` accepts

`reg-frame` is the front-porch named path: it creates a frame record, registers it under the keyword, runs the setup events synchronously, and returns the keyword (the family-wide `reg-*` return convention). It mirrors the other registrations — a keyword plus a metadata map:

```clojure
(rf/reg-frame :todo
  {:doc            "The todo app frame."          ;; like all reg-*
   :initial-events [[:rf/set-db {:items []}]       ;; ordered setup steps, dispatched synchronously after creation
                    [:todo/restore-session]]
   :on-destroy     [:todo/cleanup]                 ;; single event dispatched before teardown
   :fx-overrides   {:my-app/http http-stub-fn}     ;; per-frame fx replacements (test doubles)
   :interceptors   [:my-app/recorder]              ;; interceptor REFS prepended to every event in this frame
   :drain-depth    100                             ;; run-to-completion drain depth limit
   :preset         :test})                         ;; capability bundle — :default / :test / :story / :devtool
```

Two keys carry the weight day to day. **`:initial-events`** is an *ordered vector of setup steps* — each step a bare event vector (`[:todo/restore-session]`) or, when it needs dispatch opts, a map (`{:event [:todo/add "milk"] :opts {…}}`). Each step is dispatched synchronously and drained to fixed point before the next, so by the time `reg-frame` returns, the whole setup cascade has settled. **`:on-destroy`** is a *single* event fired once, just before teardown.

> **There is no `:db` key — and that's deliberate.** A frame's `app-db` always starts as `{}`. State arrives the only way state ever arrives: through an event. To seed initial data, make `[:rf/set-db {…}]` the first `:initial-events` step (`:rf/set-db` is the framework's "replace app-db with this map" event). Keeping initialisation on the dispatch path means the same pipeline that handles every later state change also builds the first one — one mechanism, no special "initial state" channel that drifts from the rest of your app.

A few keys are worth knowing exist even if you reach for them rarely: `:fx-overrides` swaps registered effect handlers by id (the test-double mechanism — stub `:my-app/http` so a frame never hits the network); `:interceptors` prepends interceptor *refs* (registered ids, never inline interceptor values) to every event in the frame — "global within this frame"; `:drain-depth` caps the run-to-completion drain; `:preset` expands into a named bundle (`:test`, `:story`, `:devtool`) so a frame's *intent* is visible at the call site and machine-readable from `(rf/frame-meta :todo)`. The full grammar — including the production-observability `:observability` sink policy — is [the `reg-frame` reference in 002](../../../spec/002-Frames.md).

> **Malformed config fails loud, at registration, before anything mutates.** Hand `reg-frame` a key it doesn't recognise, a `:sensitive` / `:large` key (those moved to handler effects under EP-0025 — see [data classification](../../../spec/015-Data-Classification.md)), or a malformed `:observability` entry, and registration throws `:rf.error/bad-frame-classification` — before any setup event runs, so you never get a half-registered frame. The same goes for a top-level shape mistake in `:initial-events`: `{:initial-events [:todo/init]}` (a bare event, not a *vector of* steps) is rejected with a diagnostic that names the fix — wrap it as `[[:todo/init]]`.

> **Three lanes meet at startup — keep them apart.** The two lines above are the *whole* app-author boot lane: **install the substrate with `init!`, then create your frame(s) explicitly.** Two other lanes sit nearby but are not your concern as an app author. **Frame startup** is what each frame does as it comes alive — the `:initial-events`, which seed app-db or kick a boot sequence ([Pattern — Boot](../../../spec/Pattern-Boot.md)). **Adapter-author internals** — `install-adapter!`, `destroy-adapter!`, `current-adapter`, and the adapter-spec map — sit one layer *below* `init!`; you reach for them only when writing a substrate adapter, never for ordinary boot. The full three-lane breakdown is the [Lifecycle API chapter](../../api/13-lifecycle.md).

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
(rf/reg-frame :pane/left  {:initial-events [[::init]]})
(rf/reg-frame :pane/right {:initial-events [[::init]]})

(rdc/render react-root
  [rf/frame-provider-existing {:frame :app}
   [split-screen]])
```

Notice what isn't there: no pane id threaded through the view, no atom per pane, no "which counter am I" argument on the handler. Click `+` on the left and only the left number moves. Run it and open Xray: pick the left frame and you see only that frame's events and app-db. The right frame's ledger never heard about the click. Frames are how every inspection tool partitions the world.

Borderline case? This is where people hesitate, so here's the single question to ask: would these two things ever sensibly share a piece of state? If yes, they are two views over slices of *one* frame's app-db — components on a page compose by sharing app-db, and that's the point of [having one place](app-db.md). If no — if they're genuinely two separate runs of the app — they're two frames. The two panes never want to share a counter, and that "no" is the signal.

### Two providers: who owns the frame's life?

The split pane above used `frame-provider-existing` — and the name is precise. It **scopes** an *already-registered* frame into a React subtree; it creates nothing and destroys nothing. You registered `:pane/left` with `reg-frame` at boot, and the provider just establishes its scope for descendants. Pass it a `:frame` keyword and nothing else — a lifecycle option (`:images`, `:initial-events`) fails loud, because this provider doesn't own a lifecycle.

Its sibling, `frame-provider`, **owns** one. It creates the frame when the component mounts and destroys it when the component unmounts — the React tree's lifecycle *is* the frame's lifecycle. You give it the frame's recipe inline rather than a pre-registered id:

```clojure
;; A component that owns its frame's lifetime. Mount creates the frame
;; (and runs :initial-events); unmount destroys it. No boot-time reg-frame,
;; no manual teardown — the React tree does both.
(rf/reg-view counter-widget [label]
  [rf/frame-provider {:images         [counter-image]
                      :initial-events [[:rf/set-db {:n 0}]]}
   [counter-panel label]])
```

So the choice is just "who controls when this frame lives and dies?"

- **`frame-provider-existing`** — *you* control the lifetime (you `reg-frame` it, you `destroy-frame!` it, or it lives for the whole program). The provider only scopes. This is the split-pane shape above and the normal root-of-app shape.
- **`frame-provider`** — *the component* controls the lifetime. Mount creates, unmount destroys. Reach for it when a frame should exist exactly as long as a piece of UI is on screen — a modal that wants its own throwaway world, a dynamically-added widget, a devcard.

> **Re-mounting `frame-provider` under the same id is idempotent.** If a `frame-provider` carries an `:id` and the component re-mounts (a hot reload, a Story re-evaluation, a key change), the existing frame's durable state is *preserved*, not blown away — re-mount updates config and refreshes the image without resetting `app-db`. That's what makes hot reload not blink. If you genuinely want a fresh start, that's `reset-frame!` (below), not a re-mount.

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

> **Why a default frame would be a trap.** Say a frameless dispatch fell through to "the" frame. Your app would work perfectly — right up until a second frame appears (a Story canvas, an inspection tool, an SSR pass). At that point the dispatch lands *somewhere*, with no error, in the wrong world. The carried rule converts that silent cross-frame leak into an immediate, attributed failure at the exact call site that lost its frame. The error is the feature.

From outside any scope — a test, a tool, the REPL — you name the frame explicitly, and the explicit target always wins. The explicit form is the *same* `dispatch` / `subscribe` you already use, with a `{:frame …}` opts map as the second argument:

```clojure
(rf/dispatch   [::inc] {:frame :pane/left})    ;; explicit target
@(rf/subscribe [:n]    {:frame :pane/left})    ;; same, for a read
```

There is no `dispatch-to` / `subscribe-to` sugar — the two-argument opts form is the one mechanism, and `{:frame …}` always beats whatever scope (or absence of scope) surrounds the call. It's also the right shape from non-Reagent contexts: server-side rendering, headless JVM tests, and tooling agents all address frames this way.

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

> **When `frame-handle` is still needed inside an effect handler.** Reach for `frame-handle` only for callbacks the effect system doesn't mediate — the socket's `onmessage` above, SDK callbacks, `window` listeners — even when the function that wires them up runs inside an effect handler. The effect system carries the frame for the dispatches *it* schedules, not for callbacks you register with the outside world.

This page is the canonical home of the capture pattern. When the [views](views.md) and [subscriptions](subscriptions.md) pages warn "don't dispatch bare from async callbacks," this is the full story they're pointing at.

## The hard rule: subscriptions never reach across frames

A subscription — a derived, cached read over app-db — belongs to one frame. It computes from that frame's app-db and from other subscriptions *in that frame*, never from another frame's state. There is no "read frame B from a sub in frame A" affordance, and you must not build one by sneaking a cross-frame read into a sub's computation function. That's the anti-pattern, full stop.

> **One cross-frame subscription breaks every per-frame guarantee.** The reasoning is the same as the carried rule's: isolation is only worth having if it's total. Story variants are reproducible because nothing outside a frame can influence them. SSR requests can run concurrently because no request can observe another. A test frame is hermetic because *nothing* reaches in. One cross-frame sub quietly breaks all three — frame A's derived values now change when frame B does, and every tool that reasons per-frame (the epoch ledger, time-travel, replay) is lying to you about A.

If you feel the need for one, you've answered the discriminator question wrongly: two things that need to share derived state are one frame. Restructure — don't reach across.

## Ending and resetting a frame

Most frames live for the whole program and you never tear them down — `frame-provider` handles unmount for the UI-owned ones, and SSR/test harnesses tear theirs down for you. But three verbs cover the lifetime explicitly, and you'll meet them in tests and tools:

```clojure
(rf/destroy-frame! :pane/left)   ;; remove it from the registry; run teardown
(rf/reset-frame!   :pane/left)   ;; reset to "just created" — re-runs :initial-events
```

**`destroy-frame!`** drops the frame from the registry, disposes its sub-cache (so nothing leaks reactive listeners), stops its router, and — if you declared one — fires its `:on-destroy` event first. It accepts the frame id or the frame value. After destruction, a dispatch or subscribe still aimed at that frame doesn't throw — it **recovers**: `dispatch` quietly no-ops, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed` record is emitted on the always-on error stream. Recovery rather than a throw is deliberate: the runtime can't tell a benign teardown/hot-reload *race* from a real use-after-destroy bug, so it stays race-safe while still surfacing the diagnostic where your error monitor will see it.

**`reset-frame!`** is "I want this back to how it started." It's equivalent to a destroy followed by a fresh `reg-frame` with the same config: `app-db` resets to `{}`, the sub-cache and router queue clear, and the recorded `:initial-events` re-run synchronously. Tests use it between cases; Story "reset" buttons use it. (For an `app-db`-only reset that *keeps* live runtime state, there's a lighter `reset-app-db!` — but `reset-frame!` is the whole-world one.)

> **Frames are created from views and top level, never from a handler.** Constructing a frame inside an event handler fails loud with `:rf.error/frame-construction-in-handler`. The division is the same one this whole page rests on: a *handler* changes app-db; a *view* (or boot, or an SSR-per-request top level) materialises frames. A handler that wants a child frame to exist writes app-db to say so, and the view tree creates the frame in response (via `frame-provider`). There is no mid-cascade frame-creation path.

### Scoping a frame in a test or at the REPL

A test or REPL session is *outside* any provider, so there's no ambient scope — but you don't want to thread `{:frame …}` onto every line. Two macros establish a scope for a block, mirroring the two providers:

```clojure
;; Pin to an EXISTING frame for the block (creates / destroys nothing):
(rf/with-frame :todo
  (rf/dispatch-sync [:todo/add "milk"])
  @(rf/subscribe [:items]))

;; CREATE a frame, use it, and destroy it on exit (success or throw):
(rf/with-new-frame [f (rf/make-frame {:images [todo-image]})]
  (rf/dispatch-sync [:todo/add "milk"])
  (is (= 1 (count (:items (rf/app-db-value f))))))
```

`with-frame` is the lexical counterpart of `frame-provider-existing` (scope only); `with-new-frame` is the counterpart of `frame-provider` (it *owns* the lifetime — guaranteed teardown on block exit). Inside either, plain `dispatch` / `subscribe` resolve to the bound frame. Note `dispatch-sync` rather than `dispatch`: from outside a running cascade it runs the event to completion *before returning*, which is what a test wants to assert against. (Calling `dispatch-sync` from *inside* a handler is an error — `:rf.error/dispatch-sync-in-handler` — because the cascade is already running synchronously; the in-handler shape is `:fx [[:dispatch …]]`.)

> **The scope macros are synchronous-only — like the dynamic binding underneath them.** `with-frame` establishes the frame via a dynamic var, which evaporates the instant control crosses an async boundary. An async callback created inside a `with-frame` body that fires *after* the body returns is back in frameless territory — and `with-new-frame` has already destroyed its frame by then. That's the same async cliff as everywhere else on this page; the same fix applies — capture a `frame-handle` (or pass an explicit `{:frame …}`) before the boundary.

## What frames are not

- **Not component-local state.** A frame carries a full app-db, queue, and sub cache; it is heavyweight by design. A dropdown's open flag or a form's draft text goes in the current frame's app-db like always — see [Where should this value live?](../where-state-lives.md).
- **Not routing.** Navigating changes *which slice of app-db matters*, not which frame is running. One frame, many routes.
- **Not micro-frontends.** Frames are N instances of *one* app, each resolving against its image. Two surfaces with disjoint images can share a page ([Images](images.md)), but genuinely different *apps* on one page want iframes — a wall, not a scalpel.

---

**You can now:**

- say what a frame owns (app-db, event queue, sub cache) and what it doesn't (the registrations — those live in the frame's [image](images.md), one shared image by default),
- register one frame with `reg-frame` (and reach for `:initial-events`, `:on-destroy`, `:fx-overrides`, `:preset` when you need them — seeding state with `[:rf/set-db {…}]`, never a `:db` key), establish it at your root, and explain why `init!` doesn't do it for you,
- mount one app N times — split panes, Story canvases, SSR requests, test fixtures — nothing shared, choosing `frame-provider-existing` (scope an existing frame) or `frame-provider` (own a frame's lifetime, create-on-mount / destroy-on-unmount),
- target a frame explicitly with the `{:frame …}` opts arg on `dispatch` / `subscribe`, and tear one down or reset it with `destroy-frame!` / `reset-frame!` — knowing a dispatch to a destroyed frame *recovers* and emits `:rf.error/frame-destroyed`,
- scope a frame in a test or at the REPL with `with-frame` (existing) or `with-new-frame` (create-use-destroy), using `dispatch-sync` to drive cascades to completion,
- read `:rf.error/no-frame-context` as "this callback lost its frame" and fix it with `frame-handle` — or with `:fx` when the work starts in a handler,
- state the hard rule: subscriptions never reach across frames; things that share state are one frame.
