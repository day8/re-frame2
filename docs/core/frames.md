# Frames: isolated worlds

Every pure-pipeline page used one [frame](glossary.md#frame) — one app-db, one queue —
under `frame-root`, and never said much about it. That is the normal case. Until
now that root was ambient magic. **This page owns isolation:** what a world is,
ensure vs scope, and *frame identity is carried, not found.*

The **boot recipe** — `init!`, hot reload, host listeners, packaging a real entry
namespace — is not re-taught here. Use
[Boot and mount an app](how-to/boot-and-mount-an-app.md).

Sooner or later you want two of your app: a split pane, a [Story](glossary.md#story)
canvas with three states, many SSR requests, or a `setTimeout` that just raised
`:rf.error/no-frame-context`. A frame is re-frame2's isolation boundary — a
**world**: one complete, running copy of your app, sealed off from every other
copy. Most apps establish exactly one world at boot and never say its name again.
We start there, then add a second world.

**On this page — two speeds.** Day one: one `frame-root`, seed with
`:initial-events`, two frames side by side, the carried rule. Going further: the
full frame config, `capture-frame` from callbacks, ending or resetting a frame.
Ship on one frame until you need a second.

## The counter, twice

Before any theory, the whole point of frames, running. One set of registrations — the same events, the same sub, the same view — mounted into **two** frames:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [_world [_id start]] {:db {:value start}}))
(rf/reg-event :inc (fn [{:keys [db]} _] {:db (update db :value inc)}))
(rf/reg-sub :value (fn [db _] (:value db)))

(rf/reg-view counter []
  [:div
   [:span @(subscribe [:value]) " "]
   [:button {:on-click #(dispatch [:inc])} "+"]])

;; the new idea: the SAME code, mounted in two isolated worlds —
;; each frame-root ENSURES its frame (creates it at first mount, runs
;; :initial-events) and scopes it for the subtree
[:div {:style {:display "flex" :gap "2em"}}
 [rf/frame-root {:id :left  :initial-events [[:initialise 0]]}   [counter]]
 [rf/frame-root {:id :right :initial-events [[:initialise 100]]} [counter]]]
```

Click one. The other doesn't move. Nothing in `counter` names a frame — its injected `dispatch` and `subscribe` resolve against *whichever frame it's rendered inside* — so the same view runs against two independent app-dbs, unchanged. That's the payoff this page builds toward, starting from the one-frame app you already have.

## What a frame is

One running instance of your app. That's the definition; the rest is inventory. A frame owns the runtime state of its instance:

- its **[app-db](glossary.md#app-db)** — the single map this instance's events read and write; the whole of this instance's state,
- its **event queue** — the [dispatches](glossary.md#dispatch) waiting to run against this instance,
- its **[subscription](glossary.md#subscription) cache** — the memoised graph of derived values computed over this instance's state.

Now notice what's missing from that list: the handlers. A frame deliberately does *not* own the functions you register with `reg-event` / `reg-sub` / `reg-view`. Those are shared. By default every `reg-*` in your program writes into one common table — the [**registrar**](glossary.md#registrar) — that all frames draw from, so two frames both running `[:counter/inc]` run the *same handler function* against *different app-dbs*.

That's the whole trick. **Shared code, separate state.** A frame isolates state, not behaviour — which is why "show two of them side by side" never forces a rewrite. You mount the same app twice, each mount in its own world, and isolation is total.

(The selected set of registrations a frame resolves against has a name — the [**image**](glossary.md#image) — but you can park that word for now. It only earns its keep in the rare case where you want two frames to run *different* handlers, which we get to at the very end. Until then, "the handlers are shared" is the whole story.)


??? info "Coming from Redux?"

    A frame is a store instance and the frame provider is `<Provider store={...}>`. Creating a second store gives you a second state tree but the same reducers; frames work exactly that way — handlers are registered once, state is per-frame. The divergence: there is no default store. A dispatch that can't trace which frame it belongs to fails loud instead of landing somewhere conventional. (More on that below — it's the whole design.)

## The normal case: one app, one frame

Almost every app is a one-world app, and stays one. You register a frame at boot, establish it at the root of your view tree, and never name it again.

One piece of syntax recurs on this page, so let's settle it once. Inside a `reg-view`, `dispatch` and `subscribe` arrive as injected functions — `dispatch` sends an [event](glossary.md#event) into the queue, `subscribe` reads a derived value (both detailed on the [views](views.md) page). And `@` is Clojure's deref — `@(subscribe [:screen])` reads the *current value* out of the reactive subscription. Both quietly target whichever frame this view is rendering under; you'll see how in a moment.

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
  (rdc/render react-root
    [rf/frame-root {:id :app :initial-events [[:app/initialise]]}
     [main-view]]))
```

Two forms do the work. `init!` installs the [substrate](glossary.md#substrate) [adapter](glossary.md#adapter) — the one-time hookup between re-frame2 and your rendering library (Reagent here) — and creates *no* frame. Then `frame-root {:id :app …}` **ensures** the `:app` frame: it creates the frame the first time the view mounts (running its `:initial-events`) and establishes it for everything underneath, so inside that subtree every `dispatch` and `subscribe` resolves to `:app` without ever naming it.

That last point is the payoff: the frame is **invisible inside its own scope**. It's why the injected `subscribe` in `main-view` above just *worked*, and it's exactly what lets you go multi-frame later without touching a line of app code.

??? info "For JavaScript developers"

    This is `ReactDOM.render(<Provider store={store}><App/></Provider>)` — establish the store at the root, and every component below reads it through context. The difference: `init!` doesn't secretly create a store for you — the root boundary itself brings the frame into being. Nothing is implicit about which frame your root uses; you say so, once, at the root.

??? info "From re-frame v1"

    v1's single implicit app-db becomes one explicit frame your root boundary ensures — one wrapper at the root, and nothing else changes. `:rf/default` is a perfectly legal frame id you may *choose*, but it carries no privilege: the runtime never falls back to it.

### Seeding initial state

Notice there's no place to hand the frame config an initial app-db. That's on purpose.

!!! note "A frame's app-db always starts as `{}` — there is no `:db` config key"

    State arrives the only way state ever arrives: through an
    [event pipeline](glossary.md#event-pipeline). Prefer a **named seed event** —
    the same pattern the pure-pipeline pages used:

    ```clojure
    (rf/reg-event :cart/initialise
      (fn [_ _] {:db {:items []}}))

    [rf/frame-root {:id :cart :initial-events [[:cart/initialise]]}
     [cart-view]]
    ```

    For a raw dump with no domain event, the built-in `[:rf/set-db {…}]` is fine as
    the first step. Either way, initialisation rides the same pipeline as every later
    change — no special "initial state" channel that drifts from the rest of the app.

`:initial-events` is an *ordered vector of setup steps*. Each step is a bare event vector (`[:cart/restore-session]`) or, when it needs dispatch opts, a map (`{:event [:cart/add "milk"] :opts {…}}`). Each step is dispatched synchronously and run to completion before the next one starts — and "to completion" means all the way down: if a setup event dispatches further events, those finish too. By the time construction returns, the entire setup drain is done and the world is fully booted.

## When you want more than one

Now the reason frames exist. The genuine multi-frame cases, roughly in the order you'll meet them:

- **The same widget twice on one page.** A split pane comparing today against last week. Two panes, two frames, zero shared state.
- **Story canvases.** "Show this view empty, loading, and loaded, side by side" is one set of handlers and three frames, each seeded differently. The Story runner allocates them; you mostly don't see it.
- **A fresh frame per test.** Each test gets its own frame, torn down after, so no test can leak state into the next — see [Test a pipeline run](testing/pipeline-runs.md).
- **A frame per server request.** [Server-side rendering](../ssr/concepts.md) creates a frame per HTTP request, runs the app in it, serialises, destroys it. A hundred concurrent requests are a hundred isolated app-dbs.

The shape is the same every time: **one app, mounted N times, each mount fully isolated.** Multi-frame is never "N half-apps stitched together" — each frame is a complete, self-sufficient world. Two panes: two worlds. Three Story variants: three. A hundred SSR requests: a hundred worlds, each with its own state, its own queue, its own private history, blinking in and out of existence as requests come and go — you're not running an app anymore, you're administering a multiverse. Too much? Fine: N hash maps and N event queues, rigorously fenced. But that fence is the entire point of this page.

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
   [rf/frame-root {:id :pane/left  :initial-events [[::init]]} [counter-panel "Left"]]
   [rf/frame-root {:id :pane/right :initial-events [[::init]]} [counter-panel "Right"]]])

;; At boot — after (rf/init! ...) — the root boundary ensures the app frame.
(rdc/render react-root
  [rf/frame-root {:id :app}
   [split-screen]])
```

Notice what *isn't* there: no pane id threaded through the view, no atom per pane, no "which counter am I" argument on the handler. The view is identical to one you'd write for a single counter. Boundaries simply nest — each pane's `frame-root` overrides the root scope for its own subtree.

Click `+` on the left and only the left number moves. Open [Xray](glossary.md#xray), pick the left frame, and you see only that frame's events and app-db; the right frame's ledger never heard about the click. Frames are how every inspection tool partitions the world.

Borderline case? This is where people hesitate, so here's the one question that settles it: *would these two things ever sensibly share a piece of state?* If yes, they are two views over slices of *one* frame's app-db — views on a page compose by sharing app-db, and that's the point of [having one place](app-db.md). If no — if they're genuinely two separate runs of the app — they're two frames. The two panes never want to share a counter. That "no" is the signal.

### frame-provider and frame-root

There are two frame-boundary components, one verb each — **roots ensure; providers scope**. The split pane used **`frame-root {:id …}`** three times: it **ensures** a named frame — creates the frame the first time the view mounts (running `:initial-events`), and on every later mount/remount under the same id it *reuses* the live frame without re-seeding — there is **no destroy-on-unmount**. You give it the frame's recipe inline:

```clojure
;; A view that ensures its frame. The first mount creates the frame
;; (and runs :initial-events); a remount under the same :id reuses it
;; without re-seeding. No boot-time constructor call needed — frame-root
;; ensures it.
(rf/reg-view counter-widget [label]
  [rf/frame-root {:id             :counter/widget
                  :initial-events [[:rf/set-db {:n 0}]]}
   [counter-panel label]])
```

Its sibling, **`frame-provider {:frame …}`**, **scopes** an *already-created* frame into a React subtree — creates nothing, destroys nothing. Reach for it when the frame already exists (an enclosing `frame-root` ensured it, or code created it programmatically with `make-frame`) and a subtree just needs to run inside it. Pass it a `:frame` keyword (or frame value). Scoping a frame that was never created (or has been destroyed) fails loud with `:rf.error/frame-provider-frame-absent`, because a silent mis-scope is a debugging nightmare.

So the choice is "do I want the boundary to ensure this frame, or do I already have it?" — pick the **component**, not a prop-map key:

- **`frame-root {:id …}` (ensure)** — creates the frame if absent and reuses it if present, keyed by `:id`. The day-1 root-of-app shape, and the shape for a view that brings its own frame into being — a Story canvas, an embedded widget, a comparison pane. Given a `:frame` it fails loud naming `frame-provider`.
- **`frame-provider {:frame …}` (scope)** — the frame exists already. It only scopes. Given an `:id` it fails loud naming `frame-root`.

`frame-root` is a **commit-owned boundary**: it creates the frame in a client `useLayoutEffect` (at commit), not during render — its first render emits no descendant subtree, and children render only once the frame is live. A render React discards before commit (a Suspense abort, a concurrent tear-off) therefore creates and seeds **nothing**. No ghost frame.

??? info "For JavaScript developers"

    `frame-provider {:frame …}` is the React pattern you already know: *providing* a store someone else created — a context `Provider` wrapping a store made at the app root. `frame-root {:id …}` is closer to a `useState`/`useRef` that lazily initialises a resource and keeps it stable across re-renders — except it initialises the resource in a commit-phase effect (not during render), and the resource (the frame) deliberately *survives* unmount; tearing it down is an explicit `destroy-frame!`, not a cleanup effect.

??? note "True ownership is explicit"

    Neither component destroys the frame on unmount. When a component should own a frame's whole lifetime (a modal that wants a throwaway world torn down on close), make that explicit: `rf/make-frame` + `rf/destroy-frame!` (both [below](#ending-and-resetting-a-frame)), tied to the component's mount/unmount lifecycle — e.g. inside a `create-class`, where the component declares it owns the birth *and* the death.

One behaviour to internalise before it surprises you: re-mounting `frame-root` is idempotent. If the view re-mounts — a hot reload, a Story re-evaluation, a key change — the existing frame's durable state is *preserved*, not blown away. Re-mount updates the frame's config without resetting `app-db` or replaying `:initial-events`; that's what makes hot reload not blink. (Changing a mounted `frame-root`'s `:id` or opts is a different thing — that fails loud with `:rf.error/frame-root-reconfigured`; give it a React `key` that changes to scope a different frame.) If you genuinely want a fresh start, that's the destroy + re-register composition [below](#ending-and-resetting-a-frame), not a re-mount.

## The one rule: frame identity is carried, not found

Everything above rests on a single invariant — the rule that makes isolation *trustworthy*:

> **[Frame identity is a value that travels with the work](glossary.md#frame-identity-is-carried-not-found).** A dispatch, a subscription, a captured callback — each reads its frame from the context it was *given*: the provider above it, the handler it's running in, the frame api that carried it (the captured operations bundle you'll meet below). An operation never goes looking for a frame in the ambient world, and the runtime never invents one from absence.

Stop on that sentence for a second. Everything below — the loud errors, the capture tool, the test macros — is that sentence, enforced.

So a bare `(rf/dispatch [:counter/inc])` works when — and only when — something above it established a frame: the root provider, the event handler it's firing from, a `with-frame` block in a test or at the REPL. With no established scope and no carried frame, the operation fails loud:

```clojure
{:rf.error/id :rf.error/no-frame-context
 :operation   :dispatch
 :event-id    :ws/message-received
 :recovery    :supply-frame}
```

Why an error instead of a sensible default? Because a default would make distant code change meaning *silently* — the kind of bug that costs you a weekend.

??? note "Why a default frame would be a trap"

    Say a frameless dispatch fell through to "the" frame. Your app would work perfectly — right up until a second frame appears (a Story canvas, an inspection tool, an SSR pass). At that point the dispatch lands *somewhere*, with no error, in the wrong world. The carried rule converts that silent cross-frame leak into an immediate, attributed failure at the exact call site that lost its frame. The error is the feature.

??? info "Coming from Redux?"

    This is the one place re-frame2 refuses the Redux convenience. Redux gives you "the store" through context and a frameless `store.dispatch` always works. re-frame2 trades that convenience for a guarantee: an operation that has lost track of which world it belongs to is a *bug*, and you find out at the call site, not three frames later in production.

### Naming a frame explicitly

From outside any scope — a test, a tool, the REPL — you name the frame explicitly, and the explicit target always wins. The explicit form is the *same* `dispatch` / `subscribe` you already use, with a `{:frame …}` opts map as the second argument:

```clojure
(rf/dispatch   [::inc] {:frame :pane/left})    ;; explicit target
@(rf/subscribe [:n]    {:frame :pane/left})    ;; same, for a read
```

There is no `dispatch-to` / `subscribe-to` sugar — the two-argument opts form is the one mechanism. It's also the right shape from non-Reagent contexts: server-side rendering, headless JVM tests, and tooling agents all address frames this way.

One distinction will save you a confused half-hour, so draw it now. `:rf.error/no-frame-context` is reserved for **absence** — you carried no frame at all. The moment you *do* carry one (`{:frame :ghost}`), you've supplied an explicit target, and a bad target — a typo, or a frame already torn down — is the registry-lookup case instead: `dispatch` quietly no-ops, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed` record lands on the always-on [error stream](glossary.md#error-record). (Same recovery as a [destroyed frame](#ending-and-resetting-a-frame) — the runtime can't tell a typo from a teardown race.) A missing scope and a bad target are two distinct failures. Branch on the category, not the absence.

## Day-one checklist

You can wrap your app in one `frame-root {:id …}`, seed it with `:initial-events`,
and mount the *same* registrations in as many frames as you like — each fully
isolated. Inside a frame, bare `dispatch` / `subscribe` resolve to it; from outside
(a test, a tool) you name it with a `{:frame …}` opt. And you know the one rule that
keeps it honest: **frame identity is carried, not found.** Ship on one frame until a
second world actually appears.

## The hard rule: subscriptions never reach across frames

A [subscription](glossary.md#subscription) — a derived, cached read over app-db — belongs to one frame. It computes from that frame's app-db and from other subscriptions *in that frame*, never from another frame's state. There is no window between worlds: no "read frame B from a sub in frame A" affordance exists, and you must not build one by sneaking a cross-frame read into a sub's computation function. That's the anti-pattern, full stop.

Why so hard a line? Because one cross-frame subscription breaks every per-frame guarantee at once. The reasoning is the same as the carried rule's: isolation is only worth having if it's total. Story variants are reproducible because nothing outside a frame can influence them. SSR requests can run concurrently because no request can observe another. A test frame is hermetic because *nothing* reaches in. One cross-frame sub quietly breaks all three — frame A's derived values now change when frame B does, and every tool that reasons per-frame (the [epoch](glossary.md#epoch) ledger, [time-travel](glossary.md#time-travel), replay) is lying to you about A.

If you feel the need for one, you've answered the discriminator question wrongly: two things that need to share derived state are one frame. Restructure — don't reach across.

## What frames are not

- **Not component-local state.** A frame carries a full app-db, queue, and sub cache; it is heavyweight by design. A dropdown's open flag or a form's draft text goes in the current frame's app-db like always — see [Where should this value live?](where-state-lives.md).
- **Not routing.** Navigating changes *which slice of app-db matters*, not which frame is running. One frame, many routes.
- **Not micro-frontends.** Frames are N instances of *one* app, each running the same shared handlers. Two surfaces with genuinely *different* handler sets can share a page (that's the [Images](images.md) story), but two genuinely different *apps* on one page want iframes — a wall, not a scalpel.

??? note "Going deeper — when two frames resolve the same id differently"

    Everything on this page assumed the default: all frames draw their handlers from one shared registrar, so every frame runs the same handlers against different app-dbs. The selected slice a frame resolves against has a name — the [**image**](glossary.md#image) — and 99% of the time you never need to think about it. The 1% is when you want two frames to resolve `[:counter/inc]` to *different* handlers: two examples on one page, or an inspection tool sitting beside the app it inspects. Then you give those frames *different* images, and which image a frame points at is what decides its behaviour. That's the [Images](images.md) story; ignore it until you hit a case that needs it.

## Going further

The rest of the page is here for when a second world makes you reach for it: the
full frame config, capturing the frame across async boundaries, ending or resetting
a frame, and the two advanced corners.

## The rest of the frame config

Day to day, `:initial-events` is the key you reach for. But the frame config — the same map whether you hand it to `frame-root` inline or to the programmatic constructor `make-frame` — carries a few more keys worth knowing exist:

```clojure
(rf/make-frame
  {:id             :cart
   :doc            "The shopping-cart frame."
   :initial-events [[:rf/set-db {:items []}]       ;; ordered setup steps, dispatched synchronously
                    [:cart/restore-session]]
   :on-destroy     [:cart/cleanup]                 ;; one private cleanup seed after destruction claims the frame
   :fx-overrides   {:my-app/http http-stub-fn}     ;; per-frame fx replacements (test doubles)
   :interceptors   [:my-app/recorder]              ;; interceptor REFS prepended to every event in this frame
   :drain-depth    100                             ;; run-to-completion drain depth limit
   :preset         :test})                         ;; capability bundle — :default / :test / :story / :ssr-server
```

Notes:

1. **`:on-destroy`** is one cleanup seed fired once after destruction claims the exact frame incarnation and cuts ordinary queued work. Its same-frame descendants run on the same private cleanup cascade before lifecycle-dead is published.
2. **`:fx-overrides`** swaps registered [effect handlers](glossary.md#effect-handler) by id — the test-double mechanism (stub `:my-app/http` so a frame never hits the network).
3. **`:interceptors`** prepends [interceptor](glossary.md#interceptor) *refs* (registered ids, never inline interceptor values) to every event in the frame — "global within this frame." (Interceptors get [their own page](interceptors.md) later.)
4. **`:drain-depth`** caps the run-to-completion drain.
5. **`:preset`** expands into a named bundle of frame-config defaults (`:test`, `:story`, `:ssr-server` — what each sets is in the [API reference](../api/re-frame.core.md)) so a frame's *intent* is visible at the call site and machine-readable from `(rf/frame-meta :cart)`.

The `:observability` sink policy — the production-telemetry key not shown above — is covered in [Observability](observability.md#consuming-production-telemetry-declare-a-sink); the full frame-config grammar is in the [API reference](../api/re-frame.core.md).

One class of mistake is caught before it can hurt you, so know what the catch looks like. Hand the frame config a `:sensitive` or `:large` key (those belong on handler effects, not frame config — see [data classification](glossary.md#data-classification)) or a malformed `:observability` entry, and registration throws `:rf.error/bad-frame-classification` *before* any setup event runs — you never get a half-registered frame. A top-level shape mistake is caught the same way: `{:initial-events [:cart/init]}` — a bare event, not a *vector of* steps — is rejected with a diagnostic that names the fix (wrap it as `[[:cart/init]]`).

??? note "Going deeper — three lanes meet at startup; keep them apart"

    The two lines you write are the *whole* app-author boot lane: **install the substrate with `init!`, then create your frame(s) explicitly.** Two other lanes sit nearby but are not your concern as an app author. **Frame startup** is what each frame does as it comes alive — the `:initial-events`, which seed app-db or kick a boot sequence. **Adapter-author internals** — `install-adapter!`, `destroy-adapter!`, and the adapter-spec map — sit one layer *below* `init!`; you reach for them only when writing a substrate adapter, never for ordinary boot. The full three-lane breakdown is the [Lifecycle API chapter](../api/re-frame.core.md).

## The async boundary: capture the frame

There is exactly one place a frame gets lost: a callback built while a frame was in scope, fired later when the scope is gone. A `setTimeout` tick. A promise continuation. A WebSocket `onmessage`. A `window` listener. A third-party SDK calling you back.

The reason follows straight from the carried rule. A provider's scope is render-time knowledge, and a handler's scope ends when the handler returns. So when the callback finally runs, it's on a fresh stack with no frame anywhere — and a bare `dispatch` inside it raises `:rf.error/no-frame-context`.

The fix is always the same move: **capture the frame as a value while it's still in scope, and close over it.** The capture tool is [`capture-frame`](glossary.md#capture-frame):

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

And there's one case where you need none of this: scheduling from inside an event handler. A handler that wants a later dispatch returns [effect](glossary.md#effect) data — a description of work for the runtime to perform — and the effects carry the frame for you:

```clojure
(rf/reg-event :toast/show
  (fn [{:keys [db]} [_ message]]
    {:db (assoc db :toast message)
     :fx [[:dispatch-later {:ms 3000 :event [:toast/clear]}]]}))
```

`:dispatch` and `:dispatch-later` effects are stamped with the in-flight frame before any timer or microtask boundary — zero ceremony. If the deferred work is just a dispatch, this is the shape.

Don't over-learn that, though. `capture-frame` is still needed for callbacks the effect system doesn't mediate — the socket's `onmessage` above, SDK callbacks, `window` listeners — even when the function that wires them up runs inside an effect handler. The effect system carries the frame for the dispatches *it* schedules, not for callbacks you register with the outside world.

This page is the canonical home of the capture pattern. When the [views](views.md) and [subscriptions](subscriptions.md) pages warn "don't dispatch bare from async callbacks," this is the full story they're pointing at.

### Hold first, scope second, override last

You've now met every way a piece of code learns which frame it belongs to. They fall into three jobs, and reaching for them in this order keeps a multi-frame app honest:

1. **Hold** — capture the frame *as a value* and carry it. This is the primary move, the one that survives every boundary: an async callback, a component that dispatches, a fn handed to an outside library. There is **one** hold primitive, `capture-frame`, wearing a different face per context:
    - `reg-view` injection — Reagent's face. A `reg-view` body's `dispatch` / `subscribe` are the captured ops, handed to you lexically.
    - `use-frame` — the UIx face. `(use-frame)` returns the same ops map in hook position (see [Use UIx or reagent-slim](how-to/use-uix-or-slim.md#step-3--why-callbacks-dispatch-off-the-frame-api)).
    - `capture-frame` itself — the bare form, for anything that leaves the render stack: an async setup fn, a tool, a test, a callback registered with the outside world.

    *One primitive, three faces.* Whichever face you use, you're holding the frame — the thing that actually travels.

2. **Scope** — establish a frame for a *region* so the code inside it doesn't have to name one. `frame-root` / `frame-provider` scope a frame down a React subtree; `with-frame` scopes one across a synchronous, non-React block (a test, a REPL session, a setup fn). Scope is a convenience over hold: it works only while control stays inside the region, which is exactly why an async callback that outlives the region falls back to hold.

3. **Override** — pass `{:frame f}` explicitly. The rare escape: a tool, a test, or an SSR pass addressing a specific frame from outside any scope. First-class, but you reach for it last, because a call that *needs* an explicit `:frame` is a call that lost its ambient frame — usually a sign to hold instead.

Two taglines pin the two scope mechanisms, because they are easy to confuse:

> **`with-frame` = dynamic binding. `frame-provider` = React context.**

`with-frame` binds a dynamic var, so it scopes a *synchronous* block and evaporates the instant control crosses an async or React-render boundary. `frame-provider` scopes a *React subtree* through context, so it reaches every component rendered beneath it — but, being render-time knowledge, it too is gone by the time a click handler fires. Both are scope; neither survives async; that's what hold is for.

## Ending and resetting a frame

Most frames live for the whole program and you never tear them down — a UI-owned frame simply outlives its mounts, and SSR/test harnesses tear theirs down deliberately. One verb covers destruction; a full reset is that verb composed with re-registration — you'll meet both in tests and tools:

```clojure
(rf/destroy-frame! :pane/left)   ;; remove it from the registry; run teardown

;; Reset to "just created" — re-runs :initial-events. Not a dedicated verb:
;; destroy, then re-create with the SAME config you built the frame with.
(rf/destroy-frame! :pane/left)
(rf/make-frame config)           ;; the SAME config (it carries :id :pane/left)
```

**`destroy-frame!`** first claims the exact installed frame incarnation and atomically cuts its ordinary event queue. An authored callback already on the stack may return and already-entered authored interceptor `:after` callbacks may unwind, but the returned context/output is inert: no framework-owned commit, flow, effect, child dispatch, ordinary diagnostic/trailer, normal epoch settlement, or render follows. If you declared `:on-destroy`, its seed and same-frame descendants then run under the sole private exact-token cleanup exception; ordinary dispatches cannot join that cascade. Only after cleanup does teardown publish lifecycle-dead, dispose the sub-cache, release feature resources, and remove the frame from the registry. It accepts the frame id or the frame value. An external ordinary dispatch in the claim-to-dead window may enter the claimed incarnation's real queue, but its next exact-incarnation drain check drops it before invocation and includes it in the interruption count. After destruction, dispatch and subscribe still aimed at the frame **recover** rather than throw: `dispatch` quietly no-ops, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed` record is emitted on the always-on [error stream](glossary.md#error-record). Recovery is deliberate: the runtime can't tell a benign teardown/hot-reload *race* from a real use-after-destroy bug, so it stays race-safe while still surfacing the diagnostic where your error monitor will see it.

**A full reset** is "I want this back to how it started." Compose `destroy-frame!` with a fresh `make-frame` using the SAME config: `app-db` resets to `{}`, the sub-cache and router queue clear, and the recorded `:initial-events` re-run synchronously. Tests use this between cases; Story "reset" buttons use it. For an image-loaded frame, re-supply the same `:images` too, or the recreated frame loses its resolved composition. (For an `app-db`-only reset that *keeps* live runtime state, there's a lighter `(rf/replace-frame-state! frame-id {:rf.db/app {}})` — but the destroy+make-frame composition is the whole-world one.) Unlike a single dedicated verb, this composition has no joint atomicity across the two calls — run it outside any handler, same as construction.

And one construction rule, stated flat: constructing a frame inside an event handler fails loud with `:rf.error/frame-construction-in-handler`. The division is the same one this whole page rests on — a *handler* changes app-db; a *view* (or boot, or an SSR-per-request top level) materialises frames. A handler that wants a child frame to exist writes app-db to say so, and the view tree creates the frame in response (via `frame-root`). There is no mid-run frame-creation path.

### Scoping a frame in a test or at the REPL

A test or REPL session is *outside* any provider, so there's no ambient scope — but you don't want to thread `{:frame …}` onto every line. Two macros establish a scope for a block, mirroring the two providers:

```clojure
;; Pin to an EXISTING frame for the block (creates / destroys nothing):
(rf/with-frame :cart
  (rf/dispatch-sync [:cart/add "milk"])
  @(rf/subscribe [:items]))

;; CREATE a frame, use it, and destroy it on exit (success or throw):
(rf/with-new-frame [f (rf/make-frame {})]
  (rf/dispatch-sync [:cart/add "milk"])
  (is (= 1 (count (:items (rf/app-db-value f))))))
```

`with-frame` is the lexical counterpart of the scope-only `frame-provider {:frame …}`; `with-new-frame` is the lexical form that *owns* a frame's lifetime — guaranteed teardown on block exit. Inside either, plain `dispatch` / `subscribe` resolve to the bound frame. `make-frame` is the one frame constructor — `frame-root` creates its frames through it. It hands back a live frame *value*, and every read/write surface takes either that value or a frame id interchangeably: `(rf/app-db-value f)` works as-is, no separate accessor needed.

Note `dispatch-sync` rather than `dispatch`: from outside a running drain it runs the event to completion *before returning*, which is what a test wants to assert against. (Calling `dispatch-sync` from *inside* a handler is an error — `:rf.error/dispatch-sync-in-handler` — because the drain is already running synchronously; the in-handler shape is `:fx [[:dispatch …]]`.) The test-fixture idiom in full — seeding, stubs, and the two macros' argument-shape guards — is [Test a pipeline run](testing/pipeline-runs.md)'s territory.

One cliff edge, before you walk off it: `with-frame` establishes the frame via a dynamic var, which evaporates the instant control crosses an async boundary. An async callback created inside a `with-frame` body that fires *after* the body returns is back in frameless territory — and `with-new-frame` has already destroyed its frame by then. It's the same async cliff as the WebSocket above, and the same fix applies: capture a frame api with `capture-frame` (or pass an explicit `{:frame …}`) before the boundary.

## Advanced

Two corners you can ignore until a multi-frame app makes you reach for them. Both follow from "frames are independent state machines" — the same root as everything above.

### Run-to-completion is per-frame

[Run-to-completion](glossary.md#drain--run-to-completion) — the runtime normally drains the whole event queue to a fixed point before anything renders — is scoped to *one frame*. Each frame has its own queue and its own drain loop; frame A's drain carries A's queue to settled, frame B's carries B's, and the two never merge. A depth-limit halt or a successful exact-incarnation destruction claim is terminal. At a destroy claim an authored callback already on the stack may return and entered authored `:after` callbacks may unwind, but its returned framework tail is inert; later ordinary work does not begin, and no render is inserted. That's the dispatch-level expression of isolation: a settled, between-event state of *one* frame is the snapshot boundary [time-travel](glossary.md#time-travel) restores, and no other frame's drain can leave that value inconsistent with its handlers.

So "one event, one run, one [epoch](glossary.md#epoch)" is a per-frame statement. N frames mid-flight are N independent run-to-completion loops, each rewindable on its own.

### Cross-frame `dispatch-sync` during a drain

You met one `dispatch-sync` rule already: calling it against the *current* frame from inside that frame's running handler is `:rf.error/dispatch-sync-in-handler` (the drain is already synchronous). The *cross-frame* variant is the deliberate exception. A `dispatch-sync` aimed at a **different** frame while the caller's frame is mid-drain is **not** rejected — it interleaves the two drains: the target frame runs to settled, then the caller's frame continues. Frames are independent state machines, so this is well-defined, not a deadlock.

It's almost never what you meant, though, so the runtime emits `:rf.warning/cross-frame-dispatch-sync-during-drain` and proceeds. If you want one frame to poke another, prefer the async form — `(rf/dispatch [event] {:frame other})` — which queues on the target's router and drains on a later cycle, after your own drain settles. Reserve the synchronous cross-frame call for the rare case where you genuinely need the other frame settled *before the next line runs* (some test and tooling setups), and treat the warning as the signal to double-check that intent.

One app. N worlds. And one rule keeping them honest: frame identity is carried, not found.
