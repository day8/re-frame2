# Frames: isolated worlds

Every example so far ran under one `frame-root`. A [frame](glossary.md#frame) is one
running copy of your app, with its own app-db, event queue, and subscription cache,
isolated from every other copy. Most apps create one at boot and never name it again.

You need to know more when you want two copies of your app on one page, or when a
`setTimeout` callback raises `:rf.error/no-frame-context`. This page covers both.
The full boot recipe, including `init!`, hot reload, and the entry namespace, is in
[Boot and mount an app](how-to/boot-and-mount-an-app.md).

## Two todo lists

One set of registrations, mounted in two frames:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :todo/initialise
  (fn [_ _] {:db {:todos {} :showing :all}}))

(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))

(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))

(rf/reg-sub :todo/todos (fn [db _] (:todos db)))
(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _] (vec (sort-by :id (vals todos)))))

(rf/reg-view todo-list []
  [:div
   [:button {:on-click #(dispatch [:todo/add "Another one"])} "Add"]
   [:ul
    (for [{:keys [id title done?]} @(subscribe [:todo/all])]
      ^{:key id}
      [:li {:on-click #(dispatch [:todo/toggle id])
            :style    {:text-decoration (when done? "line-through")}}
       title])]])

;; the new idea: the SAME code in two isolated frames
[:div {:style {:display "flex" :gap "2em"}}
 [rf/frame-root {:id             :todos/work
                 :initial-events [[:todo/initialise] [:todo/add "Write report"]]}
  [todo-list]]
 [rf/frame-root {:id             :todos/home
                 :initial-events [[:todo/initialise] [:todo/add "Buy milk"]]}
  [todo-list]]]
```

Add or toggle a todo in one list. The other doesn't change. Nothing in `todo-list`
names a frame: its injected `dispatch` and `subscribe` use whichever frame it renders
inside, so the same view runs against two independent app-dbs.

Each `frame-root` creates its frame the first time it mounts, runs its
`:initial-events` in order, and makes that frame current for everything inside it.
Both lists run the same `:todo/initialise` from [app-db](app-db.md#initial-state-is-an-event),
then add a different first todo.

## What a frame is

A frame is one running instance of your app. It holds that instance's state:

- its **[app-db](glossary.md#app-db)**, the map this instance's events read and
  write;
- its **event queue**, the [dispatches](glossary.md#dispatch) waiting to run;
- its **[subscription](glossary.md#subscription) cache**, the derived values over
  this instance's app-db.

A frame does *not* hold the functions you register with `reg-event`, `reg-sub`, and
`reg-view`. By default every `reg-*` writes to one shared table, the
[registrar](glossary.md#registrar), that all frames use. Both lists above run the
same `:todo/add` handler against different app-dbs. Frames isolate state, not
behaviour, so showing two copies of an app never requires rewriting it.

??? info "Coming from Redux?"

    A frame is a store instance and `frame-root` is `<Provider store={...}>`. A
    second store gives you a second state tree with the same reducers, and frames
    work the same way. The difference: there is no default store. A dispatch that
    can't tell which frame it belongs to throws instead of landing somewhere by
    convention ([below](#the-one-rule-frame-identity-is-carried-not-found)).

## The normal case: one app, one frame

Almost every app has one frame, established at the root of the view tree:

```clojure
(ns todo.core
  (:require [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [todo.views :refer [todo-list]]))

(defonce app-root (reagent-adapter/client-root))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)        ;; install the adapter (creates no frame)
  (reagent-adapter/render! app-root
    [rf/frame-root {:id :app :initial-events [[:todo/initialise]]}
     [todo-list]]
    (js/document.getElementById "app")))
```

`init!` installs the [adapter](glossary.md#adapter), the one-time connection between
re-frame2 and your rendering library (Reagent here). It creates no frame. Then
`frame-root {:id :app …}` creates the `:app` frame on first mount, runs its
`:initial-events`, and makes it current for the whole subtree, so every `dispatch`
and `subscribe` below resolves to `:app` without naming it. That is also why you can
add frames later without touching app code.

??? info "For JavaScript developers"

    This is `ReactDOM.render(<Provider store={store}><App/></Provider>)`: set up the
    store at the root and every component below reads it through context. The
    difference is that `init!` creates no store; the root boundary creates the frame,
    and you name it there.

??? info "From re-frame v1"

    v1's single implicit app-db becomes one explicit frame created at the root. That
    one wrapper is the only change. `:rf/default` is a legal frame id you may choose,
    but it has no special status: the runtime never falls back to it.

### Seeding initial state

A frame's app-db always starts as `{}`. There is no `:db` config key. State arrives
the way all state arrives, through an [event pipeline](glossary.md#event-pipeline),
so seed it with a named event:

```clojure
(rf/reg-event :todo/initialise
  (fn [_ _] {:db {:todos {} :showing :all}}))

[rf/frame-root {:id :app :initial-events [[:todo/initialise]]}
 [todo-list]]
```

For a raw dump with no domain event, the built-in `[:rf/set-db {…}]` works as the
first step.

`:initial-events` is an ordered vector of steps. Each step is an event vector
(`[:todo/initialise]`) or, when it needs dispatch options, a map
(`{:event [:todo/add "Buy milk"] :opts {…}}`). Each step runs to completion,
including any events it dispatches, before the next starts, so setup is done by the
time the frame is created.

## When you want more than one

The cases where you need several frames, roughly in the order you'll meet them:

- **The same app twice on one page**, like the work and home lists above.
- **Story canvases.** "Show this view empty, loading, and loaded, side by side" is one
  set of handlers and three frames, each seeded differently. The
  [Story](glossary.md#story) runner creates them for you.
- **A fresh frame per test**, torn down afterwards, so no test leaks state into the
  next. See [Test a pipeline run](testing/pipeline-runs.md).
- **A frame per server request.** [Server-side rendering](../ssr/concepts.md)
  creates a frame per HTTP request, renders, and destroys it. A hundred concurrent
  requests are a hundred isolated app-dbs.

Frame boundaries nest. Inside the root `:app` frame, each nested `frame-root`
replaces the current frame for its own subtree:

```clojure
[rf/frame-root {:id :app}
 [:div.split
  [rf/frame-root {:id             :todos/work
                  :initial-events [[:todo/initialise] [:todo/add "Write report"]]}
   [todo-list]]
  [rf/frame-root {:id             :todos/home
                  :initial-events [[:todo/initialise] [:todo/add "Buy milk"]]}
   [todo-list]]]]
```

In [Xray](glossary.md#xray), pick one frame and you see only its events and app-db.

To decide a borderline case, ask whether the two things would ever share state. If
yes, they are two views over one frame's [app-db](app-db.md). If no, they are two
frames.

### frame-provider and frame-root

There are two frame-boundary components:

- **`frame-root {:id …}`** creates the frame if it doesn't exist and reuses it if it
  does. Use it at the root of an app and for a view that brings its own frame, such
  as a Story canvas or an embedded widget. Given `:frame` instead of `:id`, it raises
  `:rf.error/frame-root-given-frame`, naming `frame-provider`.
- **`frame-provider {:frame …}`** makes an existing frame current for a subtree. It
  creates and destroys nothing. Use it when the frame already exists, because an
  enclosing `frame-root` or a `make-frame` call ([below](#the-rest-of-the-frame-config))
  created it. It takes a frame id or a frame value. Given `:id`, it raises
  `:rf.error/frame-provider-given-id`, naming `frame-root`. Pointed at a frame that
  was never created or has been destroyed, it raises
  `:rf.error/frame-provider-frame-absent`.

A view can bring its own frame:

```clojure
(rf/reg-view todo-widget []
  [rf/frame-root {:id             :todos/widget
                  :initial-events [[:todo/initialise] [:todo/add "Try the widget"]]}
   [todo-list]])
```

`frame-root` creates the frame in a client `useLayoutEffect`, at commit, not during
render, and renders its children once the frame is live. A render React discards
before commit, such as a Suspense abort, creates nothing.

Remounting `frame-root` doesn't reset anything. After a hot reload or a Story
re-evaluation, the existing frame keeps its app-db and `:initial-events` do not run
again, which is why hot reload doesn't lose your place. Changing a mounted
`frame-root`'s `:id` or options raises `:rf.error/frame-root-reconfigured`. To switch
to a different frame, give the `frame-root` a React `key` that changes with it. To
change the same frame's config, call `rf/make-frame` with the same `:id`, which
updates the config without resetting state.

Neither component destroys the frame on unmount. When a component should own a
frame's whole lifetime, such as a modal with a throwaway frame, call `rf/make-frame`
and `rf/destroy-frame!` ([below](#ending-and-resetting-a-frame)) from its mount and
unmount lifecycle: a `useEffect` and its cleanup in UIx or React, `create-class` in
Reagent.

??? info "For JavaScript developers"

    `frame-provider {:frame …}` is a context `Provider` around a store someone else
    created. `frame-root {:id …}` is closer to a `useRef` that lazily creates a
    resource and keeps it across re-renders, except that it creates it in a
    commit-phase effect and the frame outlives unmount.

## The one rule: frame identity is carried, not found

A dispatch, a subscription, or a callback gets its frame from its context: the
boundary above it, the handler it runs in, or a frame it captured. The runtime never
guesses a frame, and there is no default one
([frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found)).

So a bare `(rf/dispatch [:todo/add "Buy milk"])` works only when something has
established a frame: a `frame-root` above it while a view renders, the event or
effect handler it runs in, or a `with-frame` block in a test or at the REPL
([below](#scoping-a-frame-in-a-test-or-at-the-repl)). Otherwise it throws:

```clojure
{:rf.error/id :rf.error/no-frame-context
 :operation   :dispatch
 :event-id    :todo/add
 :recovery    :supply-frame}
```

A fallback to a default frame would work until a second frame appeared, such as a
Story canvas or an SSR pass, and then send the dispatch to the wrong frame with no
error. Failing where the frame was lost is easier to fix.

### Naming a frame explicitly

Outside any frame scope, as in a test, a tool, or the REPL, pass a `{:frame …}`
options map as the second argument to `dispatch` or `subscribe`. An explicit frame
always wins:

```clojure
(rf/dispatch   [:todo/add "Buy milk"] {:frame :todos/home})
@(rf/subscribe [:todo/all]            {:frame :todos/home})
```

`:rf.error/no-frame-context` means no frame at all. Naming a frame that doesn't exist
(`{:frame :ghost}`, a typo or an already destroyed frame) fails differently:
`dispatch` does nothing, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed`
record goes to the always-on [error stream](glossary.md#error-record). The runtime
can't tell a typo from a teardown race, so it treats both like a
[destroyed frame](#ending-and-resetting-a-frame).

## The async boundary: capture the frame

A frame gets lost when a callback created inside a frame scope runs later, after the
scope has ended: a `setTimeout` tick, a promise continuation, a WebSocket
`onmessage`, a `window` listener, a third-party SDK callback. A `frame-root`'s scope
lasts only while the view renders, and a handler's scope ends when it returns, so a
bare `rf/dispatch` in that callback raises `:rf.error/no-frame-context`. Click
handlers in a `reg-view` are safe, because the injected `dispatch` is already bound
to its frame ([Views](views.md)).

The fix is to capture the frame while it is in scope, with
[`capture-frame`](glossary.md#capture-frame), and close over it:

```clojure
;; cf. examples/patterns/websocket/messages.cljs
(defn open-socket!
  "Call from an effect handler: the runtime makes the event's frame current
   while its effects run. The socket's callbacks fire later, with no frame."
  [url]
  (let [{:keys [dispatch]} (rf/capture-frame)   ;; capture NOW
        socket             (js/WebSocket. url)]
    (set! (.-onmessage socket)
          (fn [e] (dispatch [:todo/remote-changed (.-data e)])))
    socket))
```

`(rf/capture-frame)` returns a **frame api**: a map of operations bound to the
current frame, `{:frame … :dispatch … :dispatch-sync … :subscribe …}`. Open the
socket from the `:todos/work` frame and its messages always land there. Called with
no frame in scope, `(rf/capture-frame)` itself raises `:rf.error/no-frame-context`;
`(rf/capture-frame :todos/work)` binds to a named frame instead.

You don't need this to schedule a dispatch from an event handler. Return
[effect](effects.md) rows, and they carry the frame for you:

```clojure
(rf/reg-event :todo/show-notice
  (fn [{:keys [db]} [_ message]]
    {:db (assoc db :notice message)
     :fx [[:dispatch-later {:ms 3000 :event [:todo/hide-notice]}]]}))
```

`capture-frame` is for callbacks the effect system doesn't schedule, like the
socket's `onmessage`, even when the code that registers them runs in an effect
handler.

??? info "For JavaScript developers"

    This is the familiar stale-closure problem. In JavaScript, a closure over the
    wrong store often works silently against the wrong data. Here a callback that
    didn't capture its frame throws.

## Subscriptions never read across frames

A [subscription](glossary.md#subscription) belongs to one frame. It computes from
that frame's app-db and that frame's other subscriptions. There is no API for reading
frame B from a subscription in frame A, and you must not build one by reading another
frame's app-db inside a subscription.

Story variants stay reproducible, concurrent SSR requests stay independent, and test
frames stay isolated only because nothing outside a frame affects it. The
[epoch](glossary.md#epoch) record, [time-travel](glossary.md#time-travel), and replay
would also misreport frame A once its values depended on frame B. If two things need
shared derived state, they belong in one frame.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/no-frame-context` from a timer, promise, or socket callback | The callback runs after the frame scope ended | Capture with `rf/capture-frame` while in scope, or return `:dispatch` / `:dispatch-later` from a handler |
| `:rf.error/no-frame-context` at the REPL or in a test | No frame is in scope | Wrap in `rf/with-frame`, or pass `{:frame id}` |
| `:rf.error/frame-provider-frame-absent` | `frame-provider` names a frame that was never created, or was destroyed | Use `frame-root`, or call `make-frame` first |
| `:rf.error/frame-root-reconfigured` | A mounted `frame-root`'s `:id` or options changed | Give it a React `key` that changes with the id, or call `rf/make-frame` with the same `:id` to reconfigure |
| Dispatch does nothing; `:rf.error/frame-destroyed` on the error stream | `{:frame …}` names a mistyped or destroyed frame | Fix the id, or stop dispatching after destroying the frame |
| `:rf.error/frame-construction-in-handler` | `make-frame` called from an event handler | Write app-db from the handler and let a view's `frame-root` create the frame |

## What frames are not

- **Component-local state.** A frame carries a full app-db, queue, and subscription
  cache. A dropdown's open flag or an input's draft text goes in the current frame's
  app-db; see [Where should this value live?](where-state-lives.md).
- **Routing.** Navigating changes which part of app-db matters, not which frame is
  running. One frame, many routes.
- **Micro-frontends.** Frames are copies of one app sharing the same handlers. Two
  surfaces with different handler sets can share a page (see [Images](images.md)),
  but two different apps on one page want iframes.

??? note "Going deeper: when two frames resolve the same id differently"

    This page assumed all frames use one shared registrar. The set of registrations
    a frame uses is its [image](glossary.md#image). Occasionally two frames should
    resolve `[:todo/add]` to different handlers, as with two examples on one page or
    an inspection tool beside the app it inspects. Then you give those frames
    different images; see [Images](images.md).

## Advanced

### The rest of the frame config

The frame config is the same map whether you pass it to `frame-root` or to
`make-frame`, the function that creates a frame directly. Besides `:initial-events`
it accepts:

```clojure
(rf/make-frame
  {:id             :todos/work
   :doc            "The work todo list."
   :initial-events [[:todo/initialise]
                    [:todo/add "Write report"]]  ;; ordered setup steps
   :on-destroy     [:todo/cleanup]              ;; dispatched once during teardown
   :fx-overrides   {:todo.storage/save stub-fn} ;; per-frame effect replacements
   :interceptors   [:my-app/logger]             ;; interceptor ids prepended to every event
   :drain-depth    100                          ;; run-to-completion depth limit
   :preset         :test})                      ;; :default, :test or :story
```

Notes:

1. **`:on-destroy`** is dispatched once during `destroy-frame!`, after queued work is
   discarded. Events it dispatches into the same frame also run before the frame is
   removed.
2. **`:fx-overrides`** replaces [effect handlers](glossary.md#effect-handler) by id,
   usually with test doubles, so the frame never touches storage or the network.
3. **`:interceptors`** prepends [interceptor](glossary.md#interceptor) ids to every
   event in the frame; see [Interceptors](interceptors.md).
4. **`:drain-depth`** caps the [run-to-completion](run-to-completion.md) drain.
5. **`:preset`** expands into a bundle of defaults. `:test` stubs `:rf.http/managed`,
   sets `:drain-depth` to 100, and makes coeffect minting strict; `:story` stubs HTTP
   and sets `:drain-depth` to 16. Your own keys win, and `(rf/frame-meta :todos/work)`
   shows the result.

The `:observability` key is covered in
[Observability](observability.md#consuming-production-telemetry-declare-a-sink), and
the full grammar in the [API reference](../api/re-frame.core.md).

Construction throws `:rf.error/bad-frame-classification` before any setup runs if the
config carries `:sensitive` or `:large` (those belong on handler effects; see
[data classification](glossary.md#data-classification)) or a malformed
`:observability` entry. A shape mistake such as `{:initial-events [:todo/initialise]}`,
a bare event instead of a vector of steps, is rejected the same way, with a message
naming the fix (`[[:todo/initialise]]`).

As an app author you call `init!` once and create frames.
`re-frame.substrate.adapter/install-adapter!`, `rf/destroy-adapter!`, and the
adapter-spec map are for people writing an adapter.

### Ending and resetting a frame

Most frames live for the whole program. Tests, tools, and SSR harnesses tear theirs
down explicitly:

```clojure
(rf/destroy-frame! :todos/work)   ;; run teardown and remove the frame

;; Reset to "just created": destroy, then create again with the same config.
(rf/destroy-frame! :todos/work)
(rf/make-frame config)            ;; the same config, carrying :id :todos/work
```

`destroy-frame!` takes a frame id or frame value. It discards the frame's queued
events immediately. An event already running may finish its own code, but nothing it
produced is committed, no effects run, and nothing renders. Then the `:on-destroy`
event runs, if there is one, and finally the subscription cache is disposed, feature
resources are released, and the frame is removed.

After that, a `dispatch` or `subscribe` aimed at the frame does not throw: `dispatch`
does nothing, `subscribe` returns `nil`, and a `:rf.error/frame-destroyed` record goes
to the always-on [error stream](glossary.md#error-record). The runtime can't tell a
harmless teardown race from a real use-after-destroy bug, so it recovers and reports.

A full reset, `destroy-frame!` then `make-frame` with the same config, clears app-db
to `{}`, clears the subscription cache and queue, and runs `:initial-events` again.
Tests and Story "reset" buttons use it. For a frame built from images, pass the same
`:images` again. The two calls are not atomic, so run them outside any handler. To
reset only app-db, use `(rf/replace-frame-state! frame-id {:rf.db/app {}})`.

Creating a frame inside an event handler raises
`:rf.error/frame-construction-in-handler`. Handlers change app-db; views, boot code,
and SSR request code create frames. A handler that wants a new frame writes app-db to
say so, and a view's `frame-root` creates it.

### Scoping a frame in a test or at the REPL

Tests and the REPL run outside any view, so no frame is in scope, and adding
`{:frame …}` to every call is tedious. Two macros make a frame current for a block:

```clojure
;; Make an EXISTING frame current for the block (creates and destroys nothing):
(rf/with-frame :todos/work
  (rf/dispatch-sync [:todo/add "Buy milk"])
  @(rf/subscribe [:todo/all]))

;; CREATE a frame, use it, and destroy it on exit, even if the body throws:
(rf/with-new-frame [f (rf/make-frame {:initial-events [[:todo/initialise]
                                                        [:todo/add "Buy milk"]]})]
  (rf/dispatch-sync [:todo/add "Walk the dog"])
  (is (= 2 (count (:todos (rf/app-db-value f))))))
```

`with-frame` is the block-scoped version of `frame-provider`. `with-new-frame` owns
the frame's lifetime and destroys it when the block exits. Inside either, a plain
`dispatch` or `subscribe` uses the bound frame. `make-frame` returns a live frame
value, and every API that takes a frame accepts that value or its id.

These examples use `dispatch-sync`, which returns only after the event has run to
completion, so the next line can assert on the result
([Run to completion](run-to-completion.md#dispatch-sync)).
[Test a pipeline run](testing/pipeline-runs.md) covers the full test setup.

`with-frame` binds a dynamic var, so its scope ends when control leaves the block.
An async callback created inside the block that fires later has no frame, and
`with-new-frame` has destroyed its frame by then. Capture a frame api with
`capture-frame`, or pass `{:frame …}`, before the async boundary.

### Hold, scope, override

Code gets its frame in one of three ways. Prefer them in this order:

1. **Hold**: carry the frame as a value. This survives every boundary, including
   async callbacks and functions handed to outside libraries. The primitive is
   `capture-frame`, and each context has its own form of it:
    - In a Reagent `reg-view`, the injected `dispatch` and `subscribe` are already
      bound.
    - In UIx, the `(use-frame)` hook returns the same frame api (see
      [Use UIx or reagent-slim](how-to/use-uix-or-slim.md#step-3--why-callbacks-dispatch-off-the-frame-api)).
    - Anywhere else, call `rf/capture-frame` directly.
2. **Scope**: make a frame current for a region. `frame-root` and `frame-provider` do
   it for a React subtree through context; `with-frame` does it for a synchronous
   block through a dynamic var. Scope lasts only while control stays in the region,
   which is why async callbacks need to hold.
3. **Override**: pass `{:frame f}` explicitly, for a tool, a test, or an SSR pass
   working on a frame from outside. Needing it in app code usually means the code
   lost its frame and should hold instead.

### Run to completion is per frame

Each frame has its own queue and its own [drain](effects.md#run-to-completion).
Frame A's drain settles A's queue and B's settles B's; they never merge. A depth-limit
halt or a `destroy-frame!` ends only that frame's drain, and each frame's
[epochs](glossary.md#epoch) and [time-travel](glossary.md#time-travel) history are
independent.

### Cross-frame `dispatch-sync` during a drain

Calling `dispatch-sync` for the current frame from inside that frame's running
handler raises `:rf.error/dispatch-sync-in-handler`. A `dispatch-sync` aimed at a
**different** frame is allowed: the target frame's drain runs to completion, then the
caller's frame continues.

That is rarely what you meant, so the runtime emits
`:rf.warning/cross-frame-dispatch-sync-during-drain` and proceeds. To send an event
to another frame, prefer `(rf/dispatch event {:frame other})`, which queues it on the
target to run after your drain settles. Keep the synchronous form for tests and tools
that need the other frame settled before the next line.
