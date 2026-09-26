# Effects: the way out

So far, handlers have returned `{:db …}`. Real apps also need storage, HTTP,
timers, and follow-up events, and an [event handler](glossary.md#event-handler)
still has to stay pure: same inputs, same output. Testing, replay, and time-travel
all depend on that.

The answer is the pattern you have used since [events](events.md). A handler never
*does* anything. It returns a description of what should happen, as plain data, and
the runtime performs it.

## Saving todos

The todo list forgets everything on reload. The obvious fix is to write to
`localStorage` from the handler:

```clojure
;; Don't do this
(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    (let [todos (update-in (:todos db) [id :done?] not)]
      (.setItem js/localStorage "todos" (pr-str todos))   ;; I/O inside a handler
      {:db (assoc db :todos todos)})))
```

That write costs you three things:

- **The handler isn't pure any more.** Testing it now means faking `localStorage`.
- **Replay repeats the write.** Re-running the event history, as time-travel does,
  writes to storage again, and the event record never shows that a write happened.
- **It breaks off the browser.** During [server-side rendering](../ssr/concepts.md)
  there is no `localStorage`, so the handler throws.

Instead, the handler describes the write, and one registered
[effect handler](glossary.md#effect-handler) performs it:

```clojure
(require '[re-frame.core :as rf])

;; the new idea: the one place in the app that writes to localStorage
(rf/reg-fx :todo.storage/save
  {:doc       "Write the todos to localStorage."
   :platforms #{:client}}
  (fn [_ctx todos]
    (.setItem js/localStorage "todos" (pr-str todos))))

(defn next-id [todos]
  (inc (apply max 0 (keys todos))))

(rf/reg-event :todo/initialise
  (fn [_ _] {:db {:todos {} :showing :all}}))

(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id    (next-id (:todos db))
          todos (assoc (:todos db) id {:id id :title title :done? false})]
      {:db (assoc db :todos todos)
       :fx [[:todo.storage/save todos]]})))

(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    (let [todos (update-in (:todos db) [id :done?] not)]
      {:db (assoc db :todos todos)
       :fx [[:todo.storage/save todos]]})))

(rf/reg-sub :todo/todos (fn [db _] (:todos db)))
(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _] (vec (sort-by :id (vals todos)))))

(rf/reg-view todo-list []
  [:div
   [:button {:on-click #(dispatch [:todo/add (rand-nth ["Buy milk" "Walk the dog" "Call mum"])])}
    "Add a todo"]
   [:ul
    (for [{:keys [id title done?]} @(subscribe [:todo/all])]
      ^{:key id}
      [:li {:on-click #(dispatch [:todo/toggle id])
            :style    {:text-decoration (when done? "line-through")}}
       title])]])

[rf/frame-root {:id :app :initial-events [[:todo/initialise]]}
 [todo-list]]
```

Mount it in your app, add a todo, then open your browser's storage inspector: the
`todos` key changes on every add and toggle.

The handlers never touch storage. Each returns an extra key, `:fx`, holding a row
`[:todo.storage/save todos]` that *describes* the write. The runtime commits `:db`,
then looks up `:todo.storage/save` and calls its handler with the row's argument.
That row is an [effect](glossary.md#effect).

`reg-fx` takes an id, a metadata map, and a function of two arguments. The second is
the row's argument, here the todos map. The first is a small context map, which this
handler ignores ([Advanced](#the-effect-handlers-two-arguments) covers it).

`:platforms #{:client}` says where the effect may run. During server-side rendering
the runtime skips a `:client`-only effect and emits a `:rf.fx/skipped-on-platform`
trace event, so handlers never branch on platform. List more than one platform to
run on each; omit the key and the effect runs everywhere.

What you gain:

- The handlers are pure again. A test calls one and asserts on the returned map,
  with no storage involved.
- The write shows up in the [trace stream](glossary.md#trace-stream) and in
  [Xray](glossary.md#xray), and a test can redirect it by id
  ([Stubbing effects in tests](#stubbing-effects-in-tests)).
- `reg-fx` is the only code that touches `js/localStorage`. Keep effect handlers
  this small: they are the hardest functions in the app to test, so put the logic
  in the pure handler that builds the arguments.

Reading the saved todos back in at startup is the other direction, and it is the
subject of [Coeffects](coeffects.md).

## The effect map

A handler returns an [**effect map**](glossary.md#effect-map) with two top-level
keys:

| Key | Meaning |
|---|---|
| `:db` | Replace `app-db` with this value. |
| `:fx` | A vector of `[fx-id arg]` rows. Each names a registered effect and passes it one argument. Every other effect goes here: a storage write, an HTTP request, a follow-up dispatch, a navigation. |

([App-db](app-db.md) also taught the privacy classification keys, `:sensitive`,
`:large` and their `clear-` counterparts, which may sit beside these two.)

Because `:fx` is a vector, one handler can ask for several things:

```clojure
(rf/reg-event :todo/clear-done
  (fn [{:keys [db]} _]
    (let [todos (into {} (remove (comp :done? val)) (:todos db))]
      {:db (assoc db :todos todos)
       :fx [[:todo.storage/save todos]
            [:dispatch [:todo/set-showing :all]]]})))
```

`:dispatch` is a built-in effect that queues a follow-up event. Never call
`dispatch` from inside a handler body; return a `:dispatch` row and the runtime
queues it. For a delayed dispatch, return
`[:dispatch-later {:ms 3000 :event [:todo/hide-notice]}]` instead of calling
`js/setTimeout`.

### Ordering and atomicity — what you can rely on

When a handler returns `{:db new-db :fx [[a 1] [b 2] [c 3]]}`:

1. **`:db` commits first, in one step**, before any `:fx` row runs. No subscription
   or other reader ever sees a half-written app-db.
2. **`:fx` rows run in order.** `[a 1]`, then `[b 2]`, then `[c 3]`.
3. **Each row finishes before the next starts.** Async work a row starts, such as a
   request or a `:dispatch-later` timer, is not awaited. "Finishes" means its effect
   handler returned.
4. **Effects see the new state.** A `[:dispatch [:next-step]]` row queues an event
   whose handler reads the app-db this handler just committed. That is how you chain
   steps: write state, then dispatch the event that builds on it.

!!! warning "Gotcha — one effect throwing does not stop the others, and nothing rolls back"

    If the handler for `[a 1]` throws, `[b 2]` and `[c 3]` still run, and each
    failure is reported separately as `:rf.error/fx-handler-exception`. The `:db`
    [commit](glossary.md#commit) already happened and is kept. App-db is never rolled
    back and effects that already ran are not undone; most real effects, like a sent
    request, cannot be undone anyway. If one step depends on another succeeding, have
    the first report its outcome as an event (as `:rf.http/managed` does with
    `:on-success`) and run the second step in that event's handler.

!!! warning "Gotcha — `:db` and `:fx` are the whole top level"

    Any other top-level key, apart from the classification keys, is a malformed
    effect map. The runtime emits `:rf.error/effect-map-shape`, naming the key, and
    [refuses the whole event](glossary.md#fail-loud-not-silent): nothing is applied,
    not even `:db`. Committing the state while a requested effect silently vanished
    would look like success and hide the bug. This catches a typo (`:dn` for `:db`)
    and the re-frame v1 habit of returning a top-level `:dispatch`.

## Run to completion

`:todo/clear-done` changed app-db and queued a follow-up event. When does the
screen update: after the first event, or after both?

After both. When the runtime starts processing events, it
[**drains the queue to completion**](glossary.md#drain--run-to-completion) before any
view re-renders. The dequeued event runs its handler and
[commits](glossary.md#commit) its app-db write, then any events it dispatched run
theirs, and so on until the queue is empty. Only then, at the host's next
checkpoint, do views render, once. Every dispatch behaves this way, and there is no
opt-out.

One click here dispatches `:todo/add-samples`, which queues three `:todo/add`
events: four pipeline runs, one render. Click into the cell, press **`Ctrl-Enter`**
(**`Cmd-Enter`** on macOS) to evaluate, then click the button:

```cljs-rf2
(require '[re-frame.core :as rf])

(rf/reg-event :todo/initialise
  (fn [_ _] {:db {:todos {} :showing :all}}))

(rf/reg-event :todo/add
  (fn [{:keys [db]} [_ title]]
    (let [id (inc (apply max 0 (keys (:todos db))))]
      {:db (assoc-in db [:todos id] {:id id :title title :done? false})})))

;; one event that fans out into three more
(rf/reg-event :todo/add-samples
  (fn [_ _]
    {:fx [[:dispatch [:todo/add "Buy milk"]]
          [:dispatch [:todo/add "Walk the dog"]]
          [:dispatch [:todo/add "Call mum"]]]}))

(rf/reg-sub :todo/todos (fn [db _] (:todos db)))
(rf/reg-sub :todo/all {:inputs [[:todo/todos]]}
  (fn [[todos] _] (vec (sort-by :id (vals todos)))))

(rf/reg-view sample-list []
  [:div
   [:button {:on-click #(dispatch [:todo/add-samples])} "Add samples"]
   [:ol
    (for [{:keys [id title]} @(subscribe [:todo/all])]
      ^{:key id} [:li title])]])

[rf/frame-root {:id :todos :initial-events [[:todo/initialise]]}
 [sample-list]]
```

All three todos appear together. The view never renders a list with only
"Buy milk" in it.

Three details:

1. **Each dequeued event is its own [epoch](glossary.md#epoch).** The parent and its
   three children are four rows in the event record, even though they rendered
   together.
2. **Async effects are not drained.** An HTTP request started during the drain does
   not delay the render. Its reply arrives later as a new event, in a new drain.
3. **The drain is per [frame](glossary.md#frame).** With one frame, the normal case,
   that means per app.

Drain-depth limits, `dispatch-sync`, and what `destroy-frame!` does to a running
drain are on [Run to completion](run-to-completion.md). You don't need them to use
`:fx`.

??? info "For JavaScript developers"

    React batches the state updates inside one event handler and paints once at the
    end. Run to completion takes that further: the smallest unit that renders is an
    entire settled drain, not one handler. As in React, the batch closes at the
    host's next checkpoint, so two drains in the same stack can render together. You
    never need `flushSync` for app work, and you never see the UI between
    synchronous follow-ups.

## HTTP

A request is an effect like any other. The handler describes it, and the
`:rf.http/managed` effect performs it:

```clojure
(rf/reg-event :todo/fetch
  (fn [{:keys [db]} _]
    {:db (assoc db :loading? true)
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/todos"}
            :decode     :json
            :on-success [:todo/fetched]
            :on-failure [:todo/fetch-failed]}]]}))

;; On success, the reply's :value is the decoded body: here a vector of todos.
(rf/reg-event :todo/fetched
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :loading? false
                   :todos    (into {} (map (juxt :id identity)) value))}))

;; On failure, the reply's :error is a map whose :kind names what went wrong.
(rf/reg-event :todo/fetch-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc db :loading? false :fetch-error error)}))
```

The runtime performs the request. When the reply arrives, it dispatches
`:on-success` or `:on-failure` as a new event, with the reply map appended as the
last argument. That map is [the uniform reply](glossary.md#the-uniform-reply):
success carries `:value`, failure carries `:error`, and every managed async
effect answers the same way.

All three handlers are pure. Each tests as a plain function, and the request tests
as data: assert on the `:fx` row, with no network.

`:rf.http/managed` ships in the HTTP artefact, `day8/re-frame2-http`. Require
`re-frame.http.managed` once at boot; without it, the first `:rf.http/managed` row
fails with `:rf.error/http-artefact-missing`.

Don't call `js/fetch` inside a handler. Besides the problems the storage write had,
the `.then` callback runs after the handler has returned, so it has no way to
produce new state.

Retries, reply categories, and aborts are covered in
[Managed HTTP](../async/http.md). For caching and staleness, use
[resources](../resources/concepts.md), which build on this effect. Runnable
examples: [`examples/core/managed_http_counter`](../../examples/core/managed_http_counter)
and [RealWorld HTTP](../../examples/real-apps/realworld_http).

??? info "Coming from Redux?"

    The `:fx` vector is where thunks, sagas, and middleware used to live, except the
    handler stays a pure function returning data and the runtime interprets the
    effects. The async reply doesn't resolve a promise a reducer is awaiting; it
    arrives as a new action on the same queue.

??? info "Coming from TanStack Query?"

    A bare `:rf.http/managed` row is the low-level form: one request and its two
    reply events, wired by hand. For caching, staleness, and deduplication, use
    [resources](../resources/concepts.md), which play the role of `useQuery`.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/effect-map-shape`; nothing applied, not even `:db` | A top-level key other than `:db` / `:fx` (often a typo like `:dn`) | Put every effect in an `:fx` row |
| `:rf.error/no-such-fx`; that row fails, the others run | The `:fx` row names an unregistered id | Register it with `reg-fx`, or fix the typo |
| `:rf.error/fx-handler-exception`; later rows still run | An effect handler threw; `:db` is already committed | Chain dependent steps through reply events |
| Handler calls `dispatch` or does I/O directly | The handler is no longer pure, and the event record misses the work | Return an `:fx` row (`[:dispatch …]`, or your own `reg-fx` id) |
| `:rf.error/http-artefact-missing` | `:rf.http/managed` used without the HTTP artefact | Add `day8/re-frame2-http` and require `re-frame.http.managed` |
| `:rf.error/no-frame-context` from an async callback | A bare `dispatch` in a callback that runs later | Capture `(:frame ctx)` in the effect handler ([below](#the-effect-handlers-two-arguments)) |

## Advanced

### The effect handler's two arguments

The first argument to an effect handler is a context map carrying `:frame`, the
frame the originating event ran in, and `:event`, the originating event vector. It is
not the coeffects map and has no `:db`. An effect that needs state gets it in its
argument, or reads it at run time with `(rf/app-db-value frame)`.

You need `:frame` when an effect dispatches back later. A page can run several
[frames](frames.md), and a callback that fires after the effect handler has returned
no longer knows which one it belongs to. Capture the frame on entry and pass it to
`dispatch`:

```clojure
(rf/reg-fx :todo.api/save
  {:doc       "POST the todos, then dispatch the outcome into the originating frame."
   :platforms #{:client}}
  (fn [ctx {:keys [todos on-success on-failure]}]
    (let [frame (:frame ctx)]                        ;; read once, on entry
      (-> (js/fetch "/api/todos" #js {:method "POST" :body (pr-str todos)})
          (.then  #(rf/dispatch on-success {:frame frame}))
          (.catch #(rf/dispatch on-failure {:frame frame}))))))
```

`dispatch` takes an optional options map as its second argument, and `{:frame frame}`
sends the event to that frame. A bare `(rf/dispatch …)` in the `.then` raises
`:rf.error/no-frame-context`
([frame identity is carried, not found](glossary.md#frame-identity-is-carried-not-found)).
In practice you would use `:rf.http/managed`, `:dispatch`, or `:dispatch-later`,
which carry the frame for you; this example only shows the rule. Outside an effect
handler, `rf/capture-frame` does the same job ([Frames](frames.md#the-async-boundary-capture-the-frame)),
and the `dispatch` a view receives from [`reg-view`](views.md) is already captured.

### Registration details

- An `:fx` row naming an unregistered id [fails loud](glossary.md#fail-loud-not-silent)
  with `:rf.error/no-such-fx`, reported through the always-on error listener.
- Registration order across files doesn't matter. The effect is looked up when the
  row runs, not when the event handler is registered.
- Treat an effect's argument as an API you are designing, and prefer explicit keys.

### Stubbing effects in tests

A registered effect is addressable by id, so a test can replace it without touching
the handler under test: pass `:fx-overrides` in the dispatch options, or set them on
the frame. See [Testing event handlers](testing/event-handlers.md).
