# Frames: isolated worlds

Every page so far ran its example under one `frame-root`. A
[frame](glossary.md#frame) is one complete, running copy of your app — its own
app-db, event queue, and subscription cache — isolated from every other copy. Most
apps create exactly one at boot and never name it again.

You need to know more when you want two copies of your app (a split pane, a
[Story](glossary.md#story) canvas with three states, one per SSR request), or when a
`setTimeout` callback raises `:rf.error/no-frame-context`. This page covers both.
The full boot recipe — `init!`, hot reload, packaging an entry namespace — is in
[Boot and mount an app](how-to/boot-and-mount-an-app.md).

## The counter, twice

One set of registrations — the same events, sub, and view — mounted into **two** frames:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :initialise
  (fn [_cofx [_ start]] {:db {:value start}}))
(rf/reg-event :inc (fn [{:keys [db]} _] {:db (update db :value inc)}))
(rf/reg-sub :value (fn [db _] (:value db)))

(rf/reg-view counter []
  [:div
   [:span @(subscribe [:value]) " "]
   [:button {:on-click #(dispatch [:inc])} "+"]])

;; the SAME code, two isolated worlds —
;; each frame-root ENSURES its frame (creates it at first mount, runs
;; :initial-events) and scopes it for the subtree
[:div {:style {:display "flex" :gap "2em"}}
 [rf/frame-root {:id :left  :initial-events [[:initialise 0]]}   [counter]]
 [rf/frame-root {:id :right :initial-events [[:initialise 100]]} [counter]]]
```

Click one. The other doesn't move. Nothing in `counter` names a frame: its injected `dispatch` and `subscribe` resolve against whichever frame it renders inside, so the same view runs against two independent app-dbs.

## What a frame is

A frame is one running instance of your app. It owns that instance's runtime state:

- its **[app-db](glossary.md#app-db)** — the single map this instance's events read and write,
- its **event queue** — the [dispatches](glossary.md#dispatch) waiting to run against this instance,
- its **[subscription](glossary.md#subscription) cache** — the memoised graph of derived values over this instance's state.

A frame does *not* own the functions you register with `reg-event` / `reg-sub` / `reg-view`. By default every `reg-*` writes into one common table, the [**registrar**](glossary.md#registrar), that all frames draw from, so two frames both handling `[:inc]` run the same handler function against different app-dbs. A frame isolates state, not behaviour, which is why showing two copies side by side never forces a rewrite.

(The set of registrations a frame resolves against is called its [**image**](glossary.md#image). It matters only when two frames should run *different* handlers; see the end of this page.)

??? info "Coming from Redux?"

    A frame is a store instance and `frame-root` is `<Provider store={...}>`. Creating a second store gives you a second state tree but the same reducers; frames work the same way. The difference: there is no default store. A dispatch that can't tell which frame it belongs to fails loud instead of landing somewhere conventional (more below).

## The normal case: one app, one frame

Almost every app has one frame. You establish it at the root of your view tree and never name it again. Inside a `reg-view`, the injected `dispatch` and `subscribe` target whichever frame the view renders under.

```clojure
(ns my-app.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]))

(rf/reg-event :app/initialise
  (fn [_cofx _event]
    {:db {:screen :home}}))

(rf/reg-sub :screen (fn [db _] (:screen db)))

(rf/reg-view main-view []
  [:h1 "Screen: " (name @(subscribe [:screen]))])

(defonce app-root (reagent-adapter/client-root))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)        ;; install the adapter (creates no frame)
  (reagent-adapter/render! app-root
    [rf/frame-root {:id :app :initial-events [[:app/initialise]]}
     [main-view]]
    (js/document.getElementById "app")))
```

Two forms do the work. `init!` installs the [substrate](glossary.md#substrate) [adapter](glossary.md#adapter) — the one-time hookup between re-frame2 and your rendering library (Reagent here) — and creates no frame. Then `frame-root {:id :app …}` **ensures** the `:app` frame: it creates the frame the first time it mounts (running its `:initial-events`) and scopes it for everything underneath, so inside that subtree every `dispatch` and `subscribe` resolves to `:app` without naming it. That is also what lets you go multi-frame later without touching app code.

??? info "For JavaScript developers"

    This is `ReactDOM.render(<Provider store={store}><App/></Provider>)`: establish the store at the root, and every component below reads it through context. The difference is that `init!` creates no store; the root boundary creates the frame, and you name it there.

??? info "From re-frame v1"

    v1's single implicit app-db becomes one explicit frame your root boundary ensures — one wrapper at the root, and nothing else changes. `:rf/default` is a perfectly legal frame id you may *choose*, but it carries no privilege: the runtime never falls back to it.

### Seeding initial state

!!! note "A frame's app-db always starts as `{}` — there is no `:db` config key"

    State arrives the way all state arrives: through an
    [event pipeline](glossary.md#event-pipeline). Prefer a **named seed event**,
    the same pattern the earlier pages used:

    ```clojure
    (rf/reg-event :cart/initialise
      (fn [_ _] {:db {:items []}}))

    [rf/frame-root {:id :cart :initial-events [[:cart/initialise]]}
     [cart-view]]
    ```

    For a raw dump with no domain event, the built-in `[:rf/set-db {…}]` is fine as
    the first step. Either way, initialisation goes through the same pipeline as
    every later change.

`:initial-events` is an ordered vector of setup steps. Each step is a bare event vector (`[:cart/restore-session]`) or, when it needs dispatch opts, a map (`{:event [:cart/add "milk"] :opts {…}}`). Each step is dispatched synchronously and run to completion, including any events it dispatches, before the next one starts. By the time construction returns, setup is done.

## When you want more than one

The genuine multi-frame cases, roughly in the order you'll meet them:

- **The same widget twice on one page.** A split pane comparing today against last week. Two panes, two frames, zero shared state.
- **Story canvases.** "Show this view empty, loading, and loaded, side by side" is one set of handlers and three frames, each seeded differently. The Story runner allocates them; you mostly don't see it.
- **A fresh frame per test.** Each test gets its own frame, torn down after, so no test can leak state into the next — see [Test a pipeline run](testing/pipeline-runs.md).
- **A frame per server request.** [Server-side rendering](../ssr/concepts.md) creates a frame per HTTP request, runs the app in it, serialises, destroys it. A hundred concurrent requests are a hundred isolated app-dbs.

In each case it is one app mounted N times, each mount fully isolated. Here's the split pane, end to end:

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
(reagent-adapter/render! app-root
  [rf/frame-root {:id :app}
   [split-screen]]
  (js/document.getElementById "app"))
```

No pane id is threaded through the view, and no handler takes a "which counter am I" argument. Boundaries nest: each pane's `frame-root` overrides the root scope for its own subtree.

Click `+` on the left and only the left number moves. In [Xray](glossary.md#xray), pick the left frame and you see only that frame's events and app-db.

For a borderline case, ask: *would these two things ever share a piece of state?* If yes, they are two views over slices of one frame's [app-db](app-db.md). If no — they are genuinely two separate runs of the app — they are two frames.

### frame-provider and frame-root

There are two frame-boundary components: `frame-root` **ensures** a frame, `frame-provider` **scopes** one that already exists. The split pane used **`frame-root {:id …}`** three times. It creates the frame the first time it mounts (running `:initial-events`); on every later mount under the same id it reuses the live frame without re-seeding. It does **not** destroy the frame on unmount. You give it the frame's config inline:

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

**`frame-provider {:frame …}`** scopes an already-created frame into a React subtree; it creates and destroys nothing. Use it when the frame already exists (an enclosing `frame-root` ensured it, or code created it with `make-frame`, [below](#the-rest-of-the-frame-config)). Pass it a frame id or frame value. Scoping a frame that was never created, or has been destroyed, fails loud with `:rf.error/frame-provider-frame-absent`.

So the choice is whether the boundary should create the frame or you already have it:

- **`frame-root {:id …}` (ensure)** — creates the frame if absent and reuses it if present, keyed by `:id`. Use it at the root of an app and for a view that brings its own frame: a Story canvas, an embedded widget, a comparison pane. Given a `:frame` it raises `:rf.error/frame-root-given-frame`, naming `frame-provider`.
- **`frame-provider {:frame …}` (scope)** — the frame exists already. Given an `:id` it raises `:rf.error/frame-provider-given-id`, naming `frame-root`.

`frame-root` creates the frame in a client `useLayoutEffect` (at commit), not during render; its children render only once the frame is live. A render React discards before commit (a Suspense abort, say) therefore creates nothing.

??? info "For JavaScript developers"

    `frame-provider {:frame …}` is a context `Provider` wrapping a store someone else created. `frame-root {:id …}` is closer to a `useRef` that lazily initialises a resource and keeps it stable across re-renders, except that it initialises in a commit-phase effect and the frame survives unmount; tearing it down is an explicit `destroy-frame!`.

??? note "True ownership is explicit"

    Neither component destroys the frame on unmount. When a component should own a frame's whole lifetime (a modal that wants a throwaway frame torn down on close), call `rf/make-frame` and `rf/destroy-frame!` ([below](#ending-and-resetting-a-frame)) from the component's mount and unmount lifecycle, e.g. inside a `create-class`.

Re-mounting `frame-root` is idempotent. If the view re-mounts — a hot reload, a Story re-evaluation — the existing frame keeps its state: `app-db` is not reset and `:initial-events` do not run again, which is why hot reload doesn't lose your place. Changing a *mounted* `frame-root`'s `:id` or opts raises `:rf.error/frame-root-reconfigured`. To switch to a different frame, give the `frame-root` a React `key` that changes with it; to change the same frame's config, call `rf/make-frame` with the same `:id`, which updates the config without resetting state. For a genuinely fresh start, destroy and re-create the frame ([below](#ending-and-resetting-a-frame)).

## The one rule: frame identity is carried, not found

[Frame identity is a value that travels with the work](glossary.md#frame-identity-is-carried-not-found). A dispatch, a subscription, or a callback gets its frame from the context it was given: the boundary above it, the handler it runs in, or a frame it captured. The runtime never guesses a frame, and there is no default one.

So a bare `(rf/dispatch [:inc])` works only when something established a frame around it: a `frame-root` above it while a view renders, the event or effect handler it runs in, or a `with-frame` block in a test or at the REPL ([below](#scoping-a-frame-in-a-test-or-at-the-repl)). With no scope and no carried frame, it fails loud:

```clojure
{:rf.error/id :rf.error/no-frame-context
 :operation   :dispatch
 :event-id    :ws/message-received
 :recovery    :supply-frame}
```

Why not fall back to a default frame? Because the app would work until a second frame appeared (a Story canvas, an inspection tool, an SSR pass), and then the dispatch would land in the wrong frame with no error. Failing at the call site that lost its frame is easier to fix.

??? info "Coming from Redux?"

    Redux gives you "the store" through context, and a frameless `store.dispatch` always works. re-frame2 treats an operation that has lost track of its frame as a bug and reports it at the call site.

### Naming a frame explicitly

From outside any scope — a test, a tool, the REPL — name the frame with a `{:frame …}` opts map as the second argument to `dispatch` / `subscribe`. An explicit target always wins:

```clojure
(rf/dispatch   [::inc] {:frame :pane/left})    ;; explicit target
@(rf/subscribe [:n]    {:frame :pane/left})    ;; same, for a read
```

Server-side rendering, headless JVM tests, and tools all address frames this way.

`:rf.error/no-frame-context` means **no frame at all**. A frame you *named* that doesn't exist (`{:frame :ghost}` — a typo, or a frame already destroyed) is a different failure: `dispatch` does nothing, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed` record goes to the always-on [error stream](glossary.md#error-record). That is the same recovery as a [destroyed frame](#ending-and-resetting-a-frame), because the runtime can't tell a typo from a teardown race.

## The async boundary: capture the frame

A frame gets lost in one situation: a callback created while a frame was in scope runs later, after the scope is gone — a `setTimeout` tick, a promise continuation, a WebSocket `onmessage`, a `window` listener, a third-party SDK calling back. A `frame-root`'s scope lasts only while the view renders, and a handler's scope ends when the handler returns, so a bare `rf/dispatch` in that callback raises `:rf.error/no-frame-context`. (Click handlers in a `reg-view` are safe because the injected `dispatch` is already captured; see [Views](views.md).)

The fix: **capture the frame as a value while it's still in scope, and close over it**, with [`capture-frame`](glossary.md#capture-frame):

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

`(rf/capture-frame)` reads the frame in scope when it is called and returns a **frame api**, a map of operations locked to that frame: `{:frame … :dispatch … :dispatch-sync … :subscribe …}`. The captured `dispatch` routes to its frame whenever the socket fires: open the socket from the left pane and its messages land in the left frame. Called outside any scope, `(rf/capture-frame)` itself raises `:rf.error/no-frame-context`; `(rf/capture-frame :pane/left)` locks a frame api to a named frame instead.

??? info "For JavaScript developers"

    This is the familiar "capture the closure variable" problem. In JS, a stale closure over the wrong store often works silently against the wrong data; here a callback that didn't capture its frame throws.

You don't need any of this to schedule a dispatch from an event handler. Return [effect](effects.md) data and the effects carry the frame for you:

```clojure
(rf/reg-event :toast/show
  (fn [{:keys [db]} [_ message]]
    {:db (assoc db :toast message)
     :fx [[:dispatch-later {:ms 3000 :event [:toast/clear]}]]}))
```

`:dispatch` and `:dispatch-later` rows are stamped with the running frame before any timer fires. If the deferred work is just a dispatch, use them.

`capture-frame` is still needed for callbacks the effect system doesn't schedule — the socket's `onmessage` above, SDK callbacks, `window` listeners — even when the code that registers them runs inside an effect handler.

## The hard rule: subscriptions never reach across frames

A [subscription](glossary.md#subscription) belongs to one frame. It computes from that frame's app-db and from other subscriptions in that frame. There is no API for reading frame B from a sub in frame A, and you must not build one by reading another frame's app-db inside a sub's computation function.

A cross-frame read breaks the per-frame guarantees: Story variants stay reproducible, concurrent SSR requests stay independent, and test frames stay hermetic only because nothing outside a frame influences it. Per-frame tools — the [epoch](glossary.md#epoch) record, [time-travel](glossary.md#time-travel), replay — would also misreport frame A once its values depend on frame B. If two things need to share derived state, they belong in one frame.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/no-frame-context` from a timer, promise, or socket callback | The callback runs after the scope that knew the frame has gone | Capture with `rf/capture-frame` while in scope, or return `:dispatch-later` / `:dispatch` from a handler |
| `:rf.error/no-frame-context` at the REPL or in a test | No frame is in scope | Wrap in `rf/with-frame`, or pass `{:frame id}` |
| `:rf.error/frame-provider-frame-absent` | `frame-provider` names a frame that was never created, or was destroyed | Use `frame-root` to create it, or `make-frame` first |
| `:rf.error/frame-root-reconfigured` | A mounted `frame-root`'s `:id` or opts changed | To scope a different frame, give the `frame-root` a React `key` that changes with the id; to reconfigure the same frame, call `rf/make-frame` with the same `:id` |
| Dispatch does nothing; `:rf.error/frame-destroyed` on the error stream | `{:frame …}` names a mistyped or destroyed frame | Fix the id, or stop dispatching to a frame after destroying it |
| `:rf.error/frame-construction-in-handler` | `make-frame` called from an event handler | Write app-db from the handler and let a view's `frame-root` create the frame |

## What frames are not

- **Not component-local state.** A frame carries a full app-db, queue, and sub cache. A dropdown's open flag or a form's draft text goes in the current frame's app-db — see [Where should this value live?](where-state-lives.md).
- **Not routing.** Navigating changes *which slice of app-db matters*, not which frame is running. One frame, many routes.
- **Not micro-frontends.** Frames are N instances of *one* app, each running the same shared handlers. Two surfaces with genuinely *different* handler sets can share a page (that's the [Images](images.md) story), but two genuinely different *apps* on one page want iframes.

??? note "Going deeper — when two frames resolve the same id differently"

    Everything on this page assumed the default: all frames draw their handlers from one shared registrar. The set of registrations a frame resolves against is its [**image**](glossary.md#image). Occasionally you want two frames to resolve `[:inc]` to *different* handlers — two examples on one page, or an inspection tool beside the app it inspects. Then you give those frames different images; see [Images](images.md).

## Advanced

## The rest of the frame config

Day to day, `:initial-events` is the key you use. The frame config — the same map whether you hand it to `frame-root` inline or to the programmatic constructor `make-frame` — accepts a few more:

```clojure
(rf/make-frame
  {:id             :cart
   :doc            "The shopping-cart frame."
   :initial-events [[:rf/set-db {:items []}]       ;; ordered setup steps, dispatched synchronously
                    [:cart/restore-session]]
   :on-destroy     [:cart/cleanup]                 ;; event dispatched once while the frame is torn down
   :fx-overrides   {:my-app/http http-stub-fn}     ;; per-frame fx replacements (test doubles)
   :interceptors   [:my-app/recorder]              ;; interceptor REFS prepended to every event in this frame
   :drain-depth    100                             ;; run-to-completion drain depth limit
   :preset         :test})                         ;; capability bundle — :default / :test / :story
```

Notes:

1. **`:on-destroy`** is an event dispatched once during `destroy-frame!`, after ordinary queued work has been discarded. Events it dispatches into the same frame run too, before the frame is removed.
2. **`:fx-overrides`** swaps registered [effect handlers](glossary.md#effect-handler) by id — the test-double mechanism (stub `:my-app/http` so a frame never hits the network).
3. **`:interceptors`** prepends [interceptor](glossary.md#interceptor) references (registered ids) to every event in the frame. [Interceptors](interceptors.md) has the details.
4. **`:drain-depth`** caps the run-to-completion drain.
5. **`:preset`** expands into a named bundle of frame-config defaults: `:test` stubs `:rf.http/managed`, sets `:drain-depth` 100, and makes coeffect minting strict; `:story` stubs HTTP and sets `:drain-depth` 16. Your own keys win on conflict, and `(rf/frame-meta :cart)` shows the result.

The `:observability` sink policy — the production-telemetry key not shown above — is covered in [Observability](observability.md#consuming-production-telemetry-declare-a-sink); the full frame-config grammar is in the [API reference](../api/re-frame.core.md).

Hand the frame config a `:sensitive` or `:large` key (those belong on handler effects, not frame config — see [data classification](glossary.md#data-classification)) or a malformed `:observability` entry, and construction throws `:rf.error/bad-frame-classification` before any setup event runs. A shape mistake such as `{:initial-events [:cart/init]}` — a bare event instead of a vector of steps — is rejected the same way, with a message naming the fix (`[[:cart/init]]`).

As an app author you call `init!` once and create frames; `re-frame.substrate.adapter/install-adapter!`, `rf/destroy-adapter!`, and the adapter-spec map are for people writing a substrate adapter.

## Ending and resetting a frame

Most frames live for the whole program. Tests, tools, and SSR harnesses tear theirs down explicitly:

```clojure
(rf/destroy-frame! :pane/left)   ;; remove it from the registry; run teardown

;; Reset to "just created" — re-runs :initial-events. Not a dedicated verb:
;; destroy, then re-create with the SAME config you built the frame with.
(rf/destroy-frame! :pane/left)
(rf/make-frame config)           ;; the SAME config (it carries :id :pane/left)
```

**`destroy-frame!`** takes a frame id or frame value. It immediately discards the frame's queued events; an event already running may finish its own code, but nothing it produced is committed, no effects run, and nothing renders. Then your `:on-destroy` event (if any) runs, and finally the sub-cache is disposed, feature resources are released, and the frame is removed from the registry.

After destruction, a `dispatch` or `subscribe` still aimed at the frame does not throw: `dispatch` does nothing, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed` record goes to the always-on [error stream](glossary.md#error-record). The runtime can't tell a harmless teardown or hot-reload race from a real use-after-destroy bug, so it recovers and reports.

**A full reset** is `destroy-frame!` followed by `make-frame` with the same config: `app-db` resets to `{}`, the sub-cache and queue clear, and `:initial-events` run again. Tests and Story "reset" buttons use it. For an image-loaded frame, pass the same `:images` again. The two calls are not atomic together, so run them outside any handler. (For an `app-db`-only reset that keeps the rest of the frame's runtime state, use `(rf/replace-frame-state! frame-id {:rf.db/app {}})`.)

Constructing a frame inside an event handler raises `:rf.error/frame-construction-in-handler`. Handlers change app-db; views, boot code, and SSR request code create frames. A handler that wants a child frame writes app-db to say so, and the view tree creates the frame with `frame-root`.

### Scoping a frame in a test or at the REPL

A test or REPL session runs outside any view, so no frame is in scope, and threading `{:frame …}` onto every line is tedious. Two macros scope a frame for a block:

```clojure
;; Using the counter from the top of the page.
;; Pin to an EXISTING frame for the block (creates / destroys nothing):
(rf/with-frame :left
  (rf/dispatch-sync [:inc])
  @(rf/subscribe [:value]))

;; CREATE a frame, use it, and destroy it on exit (success or throw):
(rf/with-new-frame [f (rf/make-frame {:initial-events [[:initialise 0]]})]
  (rf/dispatch-sync [:inc])
  (is (= 1 (:value (rf/app-db-value f)))))
```

`with-frame` scopes an existing frame, like `frame-provider`; `with-new-frame` owns the frame's lifetime and destroys it when the block exits. Inside either, plain `dispatch` / `subscribe` resolve to the bound frame. `make-frame` is the one frame constructor (`frame-root` uses it too). It returns a live frame value, and every API that takes a frame accepts either that value or its id, so `(rf/app-db-value f)` works directly.

The examples use `dispatch-sync`, which runs the event to completion before returning — what a test wants to assert against ([Run to completion](run-to-completion.md#dispatch-sync)). [Test a pipeline run](testing/pipeline-runs.md) covers the full test-fixture idiom.

`with-frame` binds a dynamic var, so its scope ends when control leaves the block. An async callback created inside the body that fires after the body returns has no frame, and `with-new-frame` has already destroyed its frame by then. Capture a frame api with `capture-frame` (or pass `{:frame …}`) before the async boundary, as with the WebSocket above.

### Hold first, scope second, override last

Code learns its frame in one of three ways. Prefer them in this order:

1. **Hold** — carry the frame as a value. This survives every boundary: an async callback, a component that dispatches, a function handed to an outside library. The primitive is `capture-frame`, and each context has its own spelling of it:
    - In a Reagent `reg-view`, the injected `dispatch` / `subscribe` are already captured.
    - In UIx, `(use-frame)` returns the same frame api as a hook (see [Use UIx or reagent-slim](how-to/use-uix-or-slim.md#step-3--why-callbacks-dispatch-off-the-frame-api)).
    - Anywhere else — an async setup fn, a tool, a test, a callback registered with the outside world — call `rf/capture-frame` directly.

2. **Scope** — establish a frame for a region so code inside doesn't name one. `frame-root` / `frame-provider` scope a React subtree through context; `with-frame` scopes a synchronous block through a dynamic var. Scope lasts only while control stays in the region, which is why an async callback needs hold.

3. **Override** — pass `{:frame f}` explicitly, for a tool, a test, or an SSR pass addressing a frame from outside any scope. Needing it inside app code usually means the code lost its frame and should hold instead.

### Run-to-completion is per-frame

[Run to completion](effects.md#run-to-completion) is scoped to one frame. Each frame has its own queue and its own drain; frame A's drain settles A's queue, frame B's settles B's, and the two never merge. A depth-limit halt or a `destroy-frame!` ends only that frame's drain. So each frame's [epochs](glossary.md#epoch) and [time-travel](glossary.md#time-travel) history are independent of every other frame.

### Cross-frame `dispatch-sync` during a drain

Calling `dispatch-sync` against the current frame from inside that frame's running handler raises `:rf.error/dispatch-sync-in-handler`. A `dispatch-sync` aimed at a **different** frame is allowed: the target frame's drain runs to completion, then the caller's frame continues.

It is rarely what you meant, so the runtime emits `:rf.warning/cross-frame-dispatch-sync-during-drain` and proceeds. To send an event to another frame, prefer `(rf/dispatch event {:frame other})`, which queues it on the target and runs it after your own drain settles. Keep the synchronous form for test and tooling setups that need the other frame settled before the next line runs.
