# Frames: isolated worlds

Sooner or later you want two independent copies of your app on one screen. A split pane showing the same widget against different data. A Story canvas rendering one view in three states side by side. A server handling a hundred render requests at once. Or you've hit `:rf.error/no-frame-context` from a `setTimeout` callback and want to know what it means. All those roads lead to one idea: the **[frame](../glossary.md#frame)** — re-frame2's isolation boundary.

The good news is you can ignore frames almost entirely. Most apps establish exactly one frame at boot and never name it again. So we'll start there — the simplest working thing — and add a second frame only once the first one is solid. By the end you'll know the one rule everything else falls out of: *frame identity is carried, not found.*

## What a frame is

A frame is one running instance of your app. It owns the runtime state of that instance:

- its **[app-db](../glossary.md#app-db)** — the single map this instance's events read and write; the whole of this instance's state,
- its **event queue** — the [dispatches](../glossary.md#dispatch) waiting to run against this instance,
- its **[subscription](../glossary.md#subscription) cache** — the memoised graph of derived values computed over this instance's state.

A frame deliberately does *not* own the handlers — the functions you register with `reg-event` / `reg-sub` / `reg-view`. Those are shared. By default every `reg-*` in your program writes into one common table — the [**registrar**](../glossary.md#registrar) — that all frames draw from, so two frames both running `[:counter/inc]` run the *same handler function* against *different app-dbs*. That's the whole trick: shared code, separate state.

A frame isolates **state, not behaviour**. You write the app once; the frame decides which copy of the state it runs against. That division is why "show two of them side by side" never forces a rewrite: you mount the same app twice, each mount in its own frame, and isolation is total.

(The selected set of registrations a frame resolves against has a name — the [**image**](../glossary.md#image) — but you can park that word for now. It only earns its keep in the rare case where you want two frames to run *different* handlers, which we get to at the very end. Until then, "the handlers are shared" is the whole story.)

??? info "For JavaScript developers"

    A frame is the *instance* of your app's state; the handlers are the *code* that runs against it, and they're shared across every instance. Nothing in React or Redux forces you to keep those two things apart — re-frame2 does, and that separation is what lets you spin up a second copy for free.

??? info "Coming from Redux?"

    A frame is a store instance and the frame provider is `<Provider store={...}>`. Creating a second store gives you a second state tree but the same reducers; frames work exactly that way — handlers are registered once, state is per-frame. The divergence: there is no default store. A dispatch that can't trace which frame it belongs to fails loud instead of landing somewhere conventional. (More on that below — it's the whole design.)

## The normal case: one app, one frame

Almost every app is a one-frame app, and stays one. You register a frame at boot, establish it at the root of your view tree, and never name it again.

Two pieces of syntax show up in the view below, so here they are up front: inside a `reg-view`, `dispatch` and `subscribe` arrive as injected functions — `dispatch` sends an [event](../glossary.md#event) into the queue, `subscribe` reads a derived value (both detailed on the [views](views.md) page). And `@` is Clojure's deref — `@(subscribe [:screen])` reads the *current value* out of the reactive subscription. Both quietly target whichever frame this view is rendering under; you'll see how in a moment.

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
  (rf/init! reagent-adapter/adapter)        ;; install the adapter (creates no frame)
  (rf/reg-frame :app {:initial-events [[:app/initialise]]})
  (rdc/render react-root
    [rf/frame-provider {:frame :app}
     [main-view]]))
```

Three lines do the work. `init!` installs the [substrate](../glossary.md#substrate) [adapter](../glossary.md#adapter) — and creates *no* frame. `reg-frame` then creates the `:app` frame explicitly and runs its `:initial-events`. Finally `frame-provider {:frame :app}` scopes that already-registered frame for everything underneath it, so inside that subtree every `dispatch` and `subscribe` resolves to `:app` without ever naming it.

That last point is the payoff: the frame is **invisible inside its own scope**. Your views and handlers never mention `:app` — that's why the injected `dispatch` and `subscribe` in `main-view` above just *worked* without naming a frame, and it's exactly what lets you go multi-frame later without touching a line of app code.

??? info "For JavaScript developers"

    This is `ReactDOM.render(<Provider store={store}><App/></Provider>)` — establish the store at the root, and every component below reads it through context. The difference: `init!` doesn't secretly create a store for you. Nothing is implicit about which frame your root uses; you say so, once, at the root.

??? info "From re-frame v1"

    v1's single implicit app-db becomes one explicit frame you register and establish at the root — one extra line at boot, and nothing else changes. `:rf/default` is a perfectly legal frame id you may *choose*, but it carries no privilege: the runtime never falls back to it.

### Seeding initial state

Notice there's no place to hand `reg-frame` an initial app-db. That's on purpose.

!!! note "A frame's app-db always starts as `{}` — there is no `:db` config key"

    State arrives the only way state ever arrives: through an [event pipeline](../glossary.md#event-pipeline). To seed initial data, make `[:rf/set-db {…}]` the first `:initial-events` step (`:rf/set-db` is the framework's "replace app-db with this map" event):

    ```clojure
    (rf/reg-frame :cart {:initial-events [[:rf/set-db {:items []}]]})
    ```

    Keeping initialisation on the dispatch path means the same pipeline that handles every later state change also builds the first one — one mechanism, no special "initial state" channel that drifts from the rest of your app.

`:initial-events` is an *ordered vector of setup steps*. Each step is a bare event vector (`[:cart/restore-session]`) or, when it needs dispatch opts, a map (`{:event [:cart/add "milk"] :opts {…}}`). Each step is dispatched synchronously and run to completion before the next one starts — "to completion" meaning that if a setup event dispatches further events, those all finish too. So by the time `reg-frame` returns, the entire setup drain is done and the frame is fully booted.

### The rest of `reg-frame`'s config

Day to day, `:initial-events` is the key you reach for. But `reg-frame` mirrors the other registrations — a keyword plus a metadata map — and a few more keys are worth knowing exist:

```clojure
(rf/reg-frame :cart
  {:doc            "The shopping-cart frame."      ;; like all reg-*
   :initial-events [[:rf/set-db {:items []}]       ;; ordered setup steps, dispatched synchronously
                    [:cart/restore-session]]
   :on-destroy     [:cart/cleanup]                 ;; single event dispatched before teardown
   :fx-overrides   {:my-app/http http-stub-fn}     ;; per-frame fx replacements (test doubles)
   :interceptors   [:my-app/recorder]              ;; interceptor REFS prepended to every event in this frame
   :drain-depth    100                             ;; run-to-completion drain depth limit
   :preset         :test})                         ;; capability bundle — :default / :test / :story / :ssr-server
```

- **`:on-destroy`** is a *single* event fired once, just before teardown.
- **`:fx-overrides`** swaps registered [effect handlers](../glossary.md#effect-handler) by id — the test-double mechanism (stub `:my-app/http` so a frame never hits the network).
- **`:interceptors`** prepends [interceptor](../glossary.md#interceptor) *refs* (registered ids, never inline interceptor values) to every event in the frame — "global within this frame."
- **`:drain-depth`** caps the run-to-completion drain.
- **`:preset`** expands into a named bundle (`:test`, `:story`, `:ssr-server`) so a frame's *intent* is visible at the call site and machine-readable from `(rf/frame-meta :cart)`.

The `:observability` sink policy — the production-telemetry key not shown above — is covered in [Observability](observability.md#consuming-production-telemetry-declare-a-sink); the full `reg-frame` grammar is in the [API reference](../../api/re-frame.core.md).

!!! warning "Gotcha"

    Hand `reg-frame` a `:sensitive` or `:large` key (those moved to handler effects — see [data classification](../glossary.md#data-classification)) or a malformed `:observability` entry, and registration throws `:rf.error/bad-frame-classification` *before* any setup event runs, so you never get a half-registered frame. A top-level shape mistake is caught the same way: `{:initial-events [:cart/init]}` — a bare event, not a *vector of* steps — is rejected with a diagnostic that names the fix (wrap it as `[[:cart/init]]`).

??? note "Going deeper — three lanes meet at startup; keep them apart"

    The two lines you write are the *whole* app-author boot lane: **install the substrate with `init!`, then create your frame(s) explicitly.** Two other lanes sit nearby but are not your concern as an app author. **Frame startup** is what each frame does as it comes alive — the `:initial-events`, which seed app-db or kick a boot sequence. **Adapter-author internals** — `install-adapter!`, `destroy-adapter!`, and the adapter-spec map — sit one layer *below* `init!`; you reach for them only when writing a substrate adapter, never for ordinary boot. The full three-lane breakdown is the [Lifecycle API chapter](../../api/re-frame.core.md).

## When you want more than one

Now the reason frames exist. The genuine multi-frame cases, roughly in the order you'll meet them:

- **The same widget twice on one page.** A split pane comparing today against last week. Two panes, two frames, zero shared state.
- **Story canvases.** "Show this view empty, loading, and loaded, side by side" is one set of handlers and three frames, each seeded differently. The Story runner allocates them; you mostly don't see it.
- **A fresh frame per test.** Each test gets its own frame, torn down after, so no test can leak state into the next — see [Test a pipeline run](../testing/pipeline-runs.md).
- **A frame per server request.** [Server-side rendering](../../ssr/concepts.md) creates a frame per HTTP request, runs the app in it, serialises, destroys it. A hundred concurrent requests are a hundred isolated app-dbs.

The shape is the same every time: **one app, mounted N times, each mount fully isolated.** Multi-frame is never "N half-apps stitched together" — each frame is a complete, self-sufficient world.

Here's the split pane, end to end. Watch how little changes from the one-frame case:

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
   [rf/frame-provider {:frame :pane/left}  [counter-panel "Left"]]
   [rf/frame-provider {:frame :pane/right} [counter-panel "Right"]]])

;; At boot — after (rf/init! ...) — register the pane frames, then render.
(rf/reg-frame :pane/left  {:initial-events [[::init]]})
(rf/reg-frame :pane/right {:initial-events [[::init]]})

(rdc/render react-root
  [rf/frame-provider {:frame :app}
   [split-screen]])
```

Notice what *isn't* there: no pane id threaded through the view, no atom per pane, no "which counter am I" argument on the handler. The view is identical to one you'd write for a single counter. Providers simply nest — each pane's provider overrides the root scope for its own subtree.

Click `+` on the left and only the left number moves. Open [Xray](../glossary.md#xray), pick the left frame, and you see only that frame's events and app-db; the right frame's ledger never heard about the click. Frames are how every inspection tool partitions the world.

!!! note "Borderline case? Ask one question"

    This is where people hesitate. The discriminator: *would these two things ever sensibly share a piece of state?* If yes, they are two views over slices of *one* frame's app-db — views on a page compose by sharing app-db, and that's the point of [having one place](app-db.md). If no — if they're genuinely two separate runs of the app — they're two frames. The two panes never want to share a counter, and that "no" is the signal.

### Two config shapes: scope an existing frame, or ensure a named one?

`frame-provider` is one component with two config shapes, chosen by the prop map you hand it. The split pane used the **scope** shape, `{:frame …}` — and it's precise. It **scopes** an *already-registered* frame into a React subtree; it creates nothing and destroys nothing. You registered `:pane/left` with `reg-frame` at boot, and the provider just establishes its scope for descendants. Pass it a `:frame` keyword; scoping a frame that was never created (or has been destroyed) fails loud with `:rf.error/frame-provider-frame-absent`, because a silent mis-scope is a debugging nightmare.

Its other shape, `{:id …}`, **ensures** a named frame. It creates the frame the first time the view mounts (running `:initial-events`), and on every later mount/remount under the same id it *reuses* the live frame without re-seeding — there is **no destroy-on-unmount**. You give it the frame's recipe inline rather than a pre-registered id:

```clojure
;; A view that ensures its frame. The first mount creates the frame
;; (and runs :initial-events); a remount under the same :id reuses it
;; without re-seeding. No boot-time reg-frame needed — the provider ensures it.
(rf/reg-view counter-widget [label]
  [rf/frame-provider {:id             :counter/widget
                      :images         [counter-image]
                      :initial-events [[:rf/set-db {:n 0}]]}
   [counter-panel label]])
```

So the choice is "do I already have this frame, or do I want the provider to ensure it?"

- **`{:frame …}` (scope)** — the frame exists already (you `reg-frame` it at boot, or an enclosing provider ensured it). The provider only scopes. This is the split-pane shape above and the normal root-of-app shape.
- **`{:id …}` (ensure)** — the provider creates the frame if absent and reuses it if present, keyed by `:id`. Reach for it when a view should bring its own frame into being — a Story canvas, an embedded widget, a comparison pane.

??? info "For JavaScript developers"

    The `{:frame …}` shape is the React pattern you already know: *providing* a store someone else created — a context `Provider` wrapping a store made at the app root. The `{:id …}` shape is closer to a `useState`/`useRef` that lazily initialises a resource on first render and keeps it stable across re-renders — except the resource (the frame) deliberately *survives* unmount; tearing it down is an explicit `destroy-frame!`, not a cleanup effect.

!!! note "True ownership is explicit"

    Neither shape destroys the frame on unmount. When a component should own a frame's whole lifetime (a modal that wants a throwaway world torn down on close), make that explicit: `rf/make-frame` + `rf/destroy-frame!` inside a `create-class`, where the component declares it owns the birth *and* the death.

!!! warning "Gotcha — re-mounting the `{:id …}` shape is idempotent"

    If the view re-mounts (a hot reload, a Story re-evaluation, a key change), the existing frame's durable state is *preserved*, not blown away — re-mount updates config and refreshes the image without resetting `app-db` or replaying `:initial-events`. That's what makes hot reload not blink. If you genuinely want a fresh start, that's `reset-frame!` (below), not a re-mount.

## The one rule: frame identity is carried, not found

Everything above rests on a single invariant. It's worth stating plainly, because it's the rule that makes isolation *trustworthy*:

!!! note

    **[Frame identity is a value that travels with the work](../glossary.md#frame-identity-is-carried-not-found).** A dispatch, a subscription, a captured callback — each reads its frame from the context it was *given*: the provider above it, the handler it's running in, the frame api that carried it. An operation never goes looking for a frame in the ambient world, and the runtime never invents one from absence.

So a bare `(rf/dispatch [:counter/inc])` works when — and only when — something above it established a frame: the root provider, the event handler it's firing from, a `with-frame` block in a test or at the REPL. With no established scope and no carried frame, the operation fails loud:

```clojure
{:rf.error/id :rf.error/no-frame-context
 :operation   :dispatch
 :event-id    :ws/message-received
 :recovery    :supply-frame}
```

Why an error instead of a sensible default? Because a default would make distant code change meaning *silently* — the kind of bug that costs you a weekend.

!!! note "Why a default frame would be a trap"

    Say a frameless dispatch fell through to "the" frame. Your app would work perfectly — right up until a second frame appears (a Story canvas, an inspection tool, an SSR pass). At that point the dispatch lands *somewhere*, with no error, in the wrong world. The carried rule converts that silent cross-frame leak into an immediate, attributed failure at the exact call site that lost its frame. The error is the feature.

??? info "Coming from Redux?"

    This is the one place re-frame2 refuses the Redux convenience. Redux gives you "the store" through context and a frameless `store.dispatch` always works. re-frame2 trades that convenience for a guarantee: an operation that has lost track of which world it belongs to is a *bug*, and you find out at the call site, not three frames later in production.

### Naming a frame explicitly

From outside any scope — a test, a tool, the REPL — you name the frame explicitly, and the explicit target always wins. The explicit form is the *same* `dispatch` / `subscribe` you already use, with a `{:frame …}` opts map as the second argument:

```clojure
(rf/dispatch   [::inc] {:frame :pane/left})    ;; explicit target
@(rf/subscribe [:n]    {:frame :pane/left})    ;; same, for a read
```

There is no `dispatch-to` / `subscribe-to` sugar — the two-argument opts form is the one mechanism, and `{:frame …}` always beats whatever scope (or absence of scope) surrounds the call. It's also the right shape from non-Reagent contexts: server-side rendering, headless JVM tests, and tooling agents all address frames this way.

!!! warning "Gotcha"

    `:rf.error/no-frame-context` is reserved for **absence** — you carried no frame at all. The moment you *do* carry one (`{:frame :ghost}`), you've supplied an explicit target, so a target that names no registered frame — a typo, or a frame already torn down — is the registry-lookup case instead: `dispatch` quietly no-ops, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed` record lands on the always-on [error stream](../glossary.md#error-record) (the same recovering behaviour as a [destroyed frame](#ending-and-resetting-a-frame), because the runtime can't tell a typo from a teardown race). Branch on the category, not the absence: a missing scope and a bad target are two distinct failures.

## The async boundary: capture the frame

There is exactly one place a frame gets lost: a callback built while a frame was in scope, fired later when the scope is gone. A `setTimeout` tick. A promise continuation. A WebSocket `onmessage`. A `window` listener. A third-party SDK calling you back.

The reason follows straight from the carried rule. A provider's scope is render-time knowledge, and a handler's scope ends when the handler returns. So when the callback finally runs, it's on a fresh stack with no frame anywhere — and a bare `dispatch` inside it raises `:rf.error/no-frame-context`.

The fix is always the same move: **capture the frame as a value while it's still in scope, and close over it.** The capture tool is [`capture-frame`](../glossary.md#capture-frame):

```clojure
;; Adapted from examples/patterns/websocket/messages.cljs
(defn open-socket!
  "Call from inside an effect handler — opening a socket is an effect,
   not a view's job, and the runtime establishes the frame scope around
   every running handler and its effects. The socket's callbacks fire
   much later, on frameless stacks."
  [url]
  (let [{:keys [dispatch]} (rf/capture-frame)   ;; capture NOW
        socket             (js/WebSocket. url)]
    (set! (.-onmessage socket)
          (fn [e] (dispatch [:ws/message-received (.-data e)])))
    socket))
```

`(rf/capture-frame)` reads the frame in scope *at creation time* and returns a frame api — a bundle of operations locked to it — `{:frame ... :dispatch ... :dispatch-sync ... :subscribe ...}`. The captured `dispatch` carries its frame inside the closure, so it routes correctly whenever and wherever the socket fires. Trigger the opening effect from the left pane and the socket's messages land in the left frame; trigger it from the right pane and they land in the right one. Same code.

`capture-frame` is the one public carry primitive — reach for it (or an explicit `{:frame …}` opt) for every async / callback / tooling boundary.

??? info "For JavaScript developers"

    This is the classic "capture `this` / capture the closure variable" problem, but the runtime makes the failure mode *loud* instead of silent. In JS, a stale closure over the wrong store often just works against the wrong data and you never notice. Here, a callback that didn't capture its frame throws — so you're forced to capture at the right moment.

And there's one case where you need none of this: scheduling from inside an event handler. A handler that wants a later dispatch returns [effect](../glossary.md#effect) data — a description of work for the runtime to perform — and the effects carry the frame for you:

```clojure
(rf/reg-event :toast/show
  (fn [{:keys [db]} [_ message]]
    {:db (assoc db :toast message)
     :fx [[:dispatch-later {:ms 3000 :event [:toast/clear]}]]}))
```

`:dispatch` and `:dispatch-later` effects are stamped with the in-flight frame before any timer or microtask boundary — zero ceremony. If the deferred work is just a dispatch, this is the shape.

!!! warning "Gotcha — when `capture-frame` is still needed inside an effect handler"

    Reach for `capture-frame` only for callbacks the effect system doesn't mediate — the socket's `onmessage` above, SDK callbacks, `window` listeners — even when the function that wires them up runs inside an effect handler. The effect system carries the frame for the dispatches *it* schedules, not for callbacks you register with the outside world.

This page is the canonical home of the capture pattern. When the [views](views.md) and [subscriptions](subscriptions.md) pages warn "don't dispatch bare from async callbacks," this is the full story they're pointing at.

## The hard rule: subscriptions never reach across frames

A [subscription](../glossary.md#subscription) — a derived, cached read over app-db — belongs to one frame. It computes from that frame's app-db and from other subscriptions *in that frame*, never from another frame's state. There is no "read frame B from a sub in frame A" affordance, and you must not build one by sneaking a cross-frame read into a sub's computation function. That's the anti-pattern, full stop.

!!! note

    **Why this matters — one cross-frame subscription breaks every per-frame guarantee.** The reasoning is the same as the carried rule's: isolation is only worth having if it's total. Story variants are reproducible because nothing outside a frame can influence them. SSR requests can run concurrently because no request can observe another. A test frame is hermetic because *nothing* reaches in. One cross-frame sub quietly breaks all three — frame A's derived values now change when frame B does, and every tool that reasons per-frame (the [epoch](../glossary.md#epoch) ledger, [time-travel](../glossary.md#time-travel), replay) is lying to you about A.

If you feel the need for one, you've answered the discriminator question wrongly: two things that need to share derived state are one frame. Restructure — don't reach across.

## Ending and resetting a frame

Most frames live for the whole program and you never tear them down — `frame-provider` handles unmount for the UI-owned ones, and SSR/test harnesses tear theirs down for you. But two verbs cover the lifetime explicitly, and you'll meet them in tests and tools:

```clojure
(rf/destroy-frame! :pane/left)   ;; remove it from the registry; run teardown
(rf/reset-frame!   :pane/left)   ;; reset to "just created" — re-runs :initial-events
```

**`destroy-frame!`** drops the frame from the registry, disposes its sub-cache (so nothing leaks reactive listeners), stops its router, and — if you declared one — fires its `:on-destroy` event first. It accepts the frame id or the frame value. After destruction, a dispatch or subscribe still aimed at that frame doesn't throw — it **recovers**: `dispatch` quietly no-ops, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed` record is emitted on the always-on [error stream](../glossary.md#error-record). Recovery rather than a throw is deliberate: the runtime can't tell a benign teardown/hot-reload *race* from a real use-after-destroy bug, so it stays race-safe while still surfacing the diagnostic where your error monitor will see it.

**`reset-frame!`** is "I want this back to how it started." It's equivalent to a destroy followed by a fresh `reg-frame` with the same config: `app-db` resets to `{}`, the sub-cache and router queue clear, and the recorded `:initial-events` re-run synchronously. Tests use it between cases; Story "reset" buttons use it. (For an `app-db`-only reset that *keeps* live runtime state, there's a lighter `reset-app-db!` — but `reset-frame!` is the whole-world one.)

!!! warning "Gotcha"

    Constructing a frame inside an event handler fails loud with `:rf.error/frame-construction-in-handler`. The division is the same one this whole page rests on: a *handler* changes app-db; a *view* (or boot, or an SSR-per-request top level) materialises frames. A handler that wants a child frame to exist writes app-db to say so, and the view tree creates the frame in response (via `frame-provider`). There is no mid-run frame-creation path.

### Scoping a frame in a test or at the REPL

A test or REPL session is *outside* any provider, so there's no ambient scope — but you don't want to thread `{:frame …}` onto every line. Two macros establish a scope for a block, mirroring the two providers:

```clojure
;; Pin to an EXISTING frame for the block (creates / destroys nothing):
(rf/with-frame :cart
  (rf/dispatch-sync [:cart/add "milk"])
  @(rf/subscribe [:items]))

;; CREATE a frame, use it, and destroy it on exit (success or throw):
(rf/with-new-frame [f (rf/make-frame {:images [cart-image]})]
  (rf/dispatch-sync [:cart/add "milk"])
  (is (= 1 (count (:items (rf/app-db-value f))))))
```

`with-frame` is the lexical counterpart of the scope-only `frame-provider {:frame …}`; `with-new-frame` is the lexical form that *owns* a frame's lifetime — guaranteed teardown on block exit. Inside either, plain `dispatch` / `subscribe` resolve to the bound frame. `make-frame` is the one frame constructor; it hands back a live frame *value*, and the read surfaces take either that value or a frame id — `(rf/app-db-value f)` works as-is (`rf/frame-value->id` exists when an API genuinely wants the id).

Note `dispatch-sync` rather than `dispatch`: from outside a running drain it runs the event to completion *before returning*, which is what a test wants to assert against. (Calling `dispatch-sync` from *inside* a handler is an error — `:rf.error/dispatch-sync-in-handler` — because the drain is already running synchronously; the in-handler shape is `:fx [[:dispatch …]]`.) The test-fixture idiom in full — seeding, stubs, and the two macros' argument-shape guards — is [Test a pipeline run](../testing/pipeline-runs.md)'s territory.

!!! warning "Gotcha"

    `with-frame` establishes the frame via a dynamic var, which evaporates the instant control crosses an async boundary. An async callback created inside a `with-frame` body that fires *after* the body returns is back in frameless territory — and `with-new-frame` has already destroyed its frame by then. That's the same async cliff as the WebSocket above; the same fix applies — capture a `capture-frame` (or pass an explicit `{:frame …}`) before the boundary.

## Advanced

Two corners you can ignore until a multi-frame app makes you reach for them. Both follow from "frames are independent state machines" — the same root as everything above.

### Run-to-completion is per-frame

[Run-to-completion](../glossary.md#drain--run-to-completion) — the runtime drains the whole event queue to a fixed point before anything renders — is scoped to *one frame*. Each frame has its own queue and its own drain loop; a drain in frame A carries A's queue to settled, and a drain in frame B carries B's, and the two never merge into one drain. That's the dispatch-level expression of isolation: a settled, between-event state of *one* frame is the snapshot boundary [time-travel](../glossary.md#time-travel) restores, and no other frame's drain can leave that value inconsistent with its handlers.

So "one event, one run, one [epoch](../glossary.md#epoch)" is a per-frame statement. N frames mid-flight are N independent run-to-completion loops, each rewindable on its own.

### Cross-frame `dispatch-sync` during a drain

You met one `dispatch-sync` rule already: calling it against the *current* frame from inside that frame's running handler is `:rf.error/dispatch-sync-in-handler` (the drain is already synchronous). The *cross-frame* variant is the deliberate exception. A `dispatch-sync` aimed at a **different** frame while the caller's frame is mid-drain is **not** rejected — it interleaves the two drains: the target frame runs to settled, then the caller's frame continues. Frames are independent state machines, so this is well-defined, not a deadlock.

It's almost never what you meant, though, so the runtime emits `:rf.warning/cross-frame-dispatch-sync-during-drain` and proceeds. If you want one frame to poke another, prefer the async form — `(rf/dispatch [event] {:frame other})` — which queues on the target's router and drains on a later cycle, after your own drain settles. Reserve the synchronous cross-frame call for the rare case where you genuinely need the other frame settled *before the next line runs* (some test and tooling setups), and treat the warning as the signal to double-check that intent.

## What frames are not

- **Not component-local state.** A frame carries a full app-db, queue, and sub cache; it is heavyweight by design. A dropdown's open flag or a form's draft text goes in the current frame's app-db like always — see [Where should this value live?](../where-state-lives.md).
- **Not routing.** Navigating changes *which slice of app-db matters*, not which frame is running. One frame, many routes.
- **Not micro-frontends.** Frames are N instances of *one* app, each running the same shared handlers. Two surfaces with genuinely *different* handler sets can share a page (that's the [Images](images.md) story), but two genuinely different *apps* on one page want iframes — a wall, not a scalpel.

??? note "Going deeper — when two frames resolve the same id differently"

    Everything on this page assumed the default: all frames draw their handlers from one shared registrar, so every frame runs the same handlers against different app-dbs. The selected slice a frame resolves against has a name — the [**image**](../glossary.md#image) — and 99% of the time you never need to think about it. The 1% is when you want two frames to resolve `[:counter/inc]` to *different* handlers: two examples on one page, or an inspection tool sitting beside the app it inspects. Then you give those frames *different* images, and which image a frame points at is what decides its behaviour. That's the [Images](images.md) story; ignore it until you hit a case that needs it, which most apps never do.
