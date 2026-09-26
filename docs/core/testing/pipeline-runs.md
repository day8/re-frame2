# Test a pipeline run

Some behaviour only shows up across several events. A handler writes state and fires an HTTP request; the reply arrives as another event, whose handler writes more state and may dispatch more. Each dispatch is one [**pipeline run**](../glossary.md#run), one pass through the [event pipeline](../glossary.md#event-pipeline), and follow-up dispatches are their own runs inside the same [drain](../glossary.md#drain--run-to-completion).

This page tests that chain end to end: an event goes in, the queue drains, and you assert on the committed [`app-db`](../glossary.md#app-db). The handlers run unmodified and return the same [effect](../glossary.md#effect) data as in production; the test only changes what answers those effects. The tests run on the JVM with no browser, no patched `fetch` and no mock library.

??? info "Coming from MSW?"

    Mock Service Worker intercepts at the network boundary and answers with canned responses. re-frame2 intercepts earlier: the request is an effect, data the handler returns, so the test redirects that effect to a different handler and never reaches the network stack.

## The shape of every pipeline-run test

Create a fresh [frame](../glossary.md#frame), call [`dispatch-sync`](../glossary.md#dispatch-sync), and assert against the frame's `app-db`. The handlers are the todo app's, from [Test an event handler](event-handlers.md):

```clojure
(deftest toggle-a-new-todo
  (rf/with-new-frame [f (rf/make-frame
                          {:fx-overrides   {:todo.storage/save (fn [_ _] nil)}
                           :initial-events [[:todo/add "Buy milk"]]})]
    (rf/dispatch-sync [:todo/toggle 1])
    (is (true? (get-in (rf/app-db-value f) [:todos 1 :done?])))))
```

`with-new-frame` gives the test its own frame and destroys it on exit, like `with-open`. `dispatch-sync` returns only when the drain has settled: the event runs, every follow-up `:dispatch` runs, and, as shown below, every stubbed HTTP reply runs too. This is [run-to-completion](../glossary.md#drain--run-to-completion), so the next line reads committed state. There is no `act()`, no fake timer to advance and nothing to `await`.

??? info "From re-frame v1"

    `dispatch-sync` drains follow-up dispatches too, so the v1 test library's `wait-for` and `run-test-sync` have no counterpart. Call `dispatch-sync` directly.

## The code under test

[Effects](../effects.md#http) loaded the todo list from a server with `:todo/fetch` and its two reply events. Here the same three events also record when the request went out and a sync status, which gives the tests more to check. The file is `.cljc`, so the same code runs in the app and under a JVM test runner.

```clojure
;; src/my_app/sync.cljc
(ns my-app.sync
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]))   ;; registers :rf.http/managed

(rf/reg-event :todo/fetch
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _]
    {:db (assoc db :sync-status :loading :requested-at time-ms)
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/todos"}
            :on-success [:todo/fetched]
            :on-failure [:todo/fetch-failed]}]]}))

(rf/reg-event :todo/fetched
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :sync-status :loaded
                   :todos (into {} (map (juxt :id identity)) value))}))

(rf/reg-event :todo/fetch-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc db :sync-status :error :sync-error (:kind error))}))
```

The test can substitute values at two points without touching the handlers. The clock is a declared [coeffect](../glossary.md#coeffect), so the test can supply an exact value. The request is an effect in the returned map, so the test can answer it without a network.

Managed HTTP appends a reply map to the `:on-success` or `:on-failure` event: `{:status :ok :value <decoded-body> …}` or `{:status :error :error <failure-map> …}`. That is why `:todo/fetched` destructures `:value` (here the decoded vector of todos) and `:todo/fetch-failed` destructures `:error`, whose `:kind` is one of eight `:rf.http/*` categories. [Managed HTTP](../../async/http.md) lists the full reply map.

## The test

```clojure
;; test/my_app/sync_test.clj
(ns my-app.sync-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.test-support :as http-test-support]   ;; test-only; never in production requires
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [my-app.todos]    ;; :todo/add, :todo/toggle, :todo/set-showing, …
            [my-app.sync]))

;; Installs the headless adapter every frame needs and resets the runtime per test.
(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest fetch-happy-path
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:get "/api/todos"]
       {:reply {:ok [{:id 1 :title "Buy milk" :done? false}]}}}
      (fn []
        (rf/dispatch-sync [:todo/fetch] {:rf.cofx {:rf/time-ms 1781078400000}})
        (let [db (rf/app-db-value f)]
          (is (= :loaded (:sync-status db)))
          (is (= 1781078400000 (:requested-at db)))
          (is (= "Buy milk" (get-in db [:todos 1 :title]))))))))

(deftest fetch-server-error
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:get "/api/todos"]
       {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}}
      (fn []
        (rf/dispatch-sync [:todo/fetch])
        (is (= :error            (:sync-status (rf/app-db-value f))))
        (is (= :rf.http/http-5xx (:sync-error  (rf/app-db-value f))))))))
```

Run it with your project's JVM test runner (`clojure -M:test`). Both tests cover the full chain: request out, reply in, reply handler updates state. The reset fixture is the one [Test an event handler](event-handlers.md#4-the-trap-frames-dont-isolate-registrations) explains; without its `:adapter`, `make-frame` throws `:rf.error/no-adapter-installed`.

### Supply the facts: `{:rf.cofx {...}}`

The router stamps `:rf/time-ms` on every dispatch, which is why the second test runs without mentioning it. Left alone it is the live clock, and an assertion on `:requested-at` would be flaky, so the first test pins it. Facts supplied under `:rf.cofx` win, and the runtime fills in only what is missing.

A handler receives exactly the facts its `:rf.cofx/requires` names, so that vector is the test's checklist; read it with `(rf/handler-meta {:source :store :kind :event :id :todo/fetch})`. A declared fact the runtime can't satisfy raises `:rf.error/missing-required-cofx`, or `:rf.error/unregistered-cofx` when nothing registers it, instead of delivering `nil`.

### Answer the HTTP: canned replies by method and URL

`with-request-stubs` (in `re-frame.http.test-support`) takes a route map of `[method url]` → reply, and a zero-argument function. While that function runs, every `:rf.http/managed` request that matches a route is answered from the table: `{:reply {:ok value}}` produces a success reply and `{:reply {:failure {:kind … :status …}}}` a failure. The reply has the same shape as a live one and lands inside the same `dispatch-sync` drain, so the reply handler can't tell the difference.

One table can hold several routes. A request is matched on its `:request :method` and `:request :url`:

```clojure
(http-test-support/with-request-stubs
  {[:get    "/api/todos"]   {:reply {:ok []}}
   [:post   "/api/todos"]   {:reply {:ok {:todo {:id 3 :title "Call mum"}}}}
   [:delete "/api/todos/2"] {:reply {:failure {:kind :rf.http/http-4xx :status 403}}}}
  (fn []
    ;; ... dispatch the events whose handlers fire those three requests ...
    ))
```

The URL is matched as the request map spells it, before `:params` are appended, so key a request carrying `:params {:page 2}` by its base URL, `"/api/todos"`.

A request that matches no route is answered with a `:rf.http/transport` failure tagged `"no stub matched"`, through the normal `:on-failure` path. The miss shows up in your failure handler's state, where the next assertion catches it, so the table must name every request the path under test fires.

#### Observing the `:loading` state before the reply lands

The tests above assert the settled state, so they never see `:sync-status :loading`. To test the in-flight state (a spinner, a disabled button), delay the reply. The route table has no delay option, but the canned stub reads an `:after-ms` key from its args. Redirect `:rf.http/managed` to a wrapper that adds `:after-ms` (and the reply `:value`) and calls the registered `:rf.http/managed-canned-success` handler. The reply then arrives on a later tick:

```clojure
(deftest fetch-shows-loading-then-loaded
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [canned (:handler-fn (rf/handler-meta {:source :store :kind :fx
                                                :id     :rf.http/managed-canned-success}))]
      (rf/dispatch-sync [:todo/fetch]
                        {:fx-overrides {:rf.http/managed
                                        (fn [frame-ctx args]
                                          (canned frame-ctx
                                                  (assoc args
                                                         :after-ms 20
                                                         :value    [])))}})
      ;; The request fired but the reply hasn't landed yet.
      (is (= :loading (:sync-status (rf/app-db-value f))))
      ;; Wait for the deferred reply, then assert the final state.
      (ts/poll-until #(= :loaded (:sync-status (rf/app-db-value f))))
      (is (= :loaded (:sync-status (rf/app-db-value f)))))))
```

`ts/poll-until` polls a predicate until it returns truthy or a deadline passes (defaults `:timeout-ms 2000`, `:interval-ms 5`), so a stuck drain shows up as a timeout rather than a hang. On the JVM it returns the truthy value or throws an `ex-info` carrying `:rf.error/poll-until-timeout`. On CLJS it returns a `js/Promise` to compose under `cljs.test/async`. The optional `:label` goes into the timeout message. Use it whenever work settles after `dispatch-sync` returns: a delayed reply, a machine `:after` transition, a `:dispatch-later`.

Don't use `poll-until` to wait out a timer window such as a debounce. There the duration is what you are testing, so use a `Thread/sleep` and say so in a comment.

### Redirect any effect: `:fx-overrides`

The stub table is built on a more general mechanism. A per-dispatch `:fx-overrides` map redirects any effect id for that one dispatch, to a function or to another registered effect:

```clojure
(deftest fetch-sends-the-right-request
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [sent (atom nil)]
      (rf/dispatch-sync [:todo/fetch]
                        {:fx-overrides {:rf.http/managed
                                        (fn [_frame-ctx args] (reset! sent args))}})
      (is (= :get         (get-in @sent [:request :method])))
      (is (= "/api/todos" (get-in @sent [:request :url]))))))
```

An override value is either another registered fx id (a keyword) or a function `(fn [frame-ctx args] …)`. The function receives the exact args map the handler built, so you assert on the request without performing it, and its return value is ignored. The same option captures your own effects, such as `:todo.storage/save`, or redirects by keyword to the shipped success stub:

```clojure
(rf/dispatch-sync [:todo/fetch]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
;; The stub replies {:status :ok :value {:stubbed true} …}; supply :value in
;; the args map to change it.
```

A keyword override must name a registered effect. `:rf.http/managed-canned-success` is registered by `re-frame.http.test-support`; without that require, the runtime emits `:rf.error/override-fallthrough` and runs the real `:rf.http/managed`, so the test fails with a transport error instead of the stubbed reply.

!!! warning "Gotcha: frames isolate `app-db`, not registrations"

    Handlers live in the process-global [registrar](../glossary.md#registrar). The reset fixture in the test namespace above restores it after each test, so keep it whenever your tests call `rf/reg-event` themselves; without it, one test's registrations leak into the next. The fixture's baseline is what was registered before `use-fixtures` ran, so a test file's own `reg-event`, like `:todo/clear-done` below, goes inside the `deftest` body or above the fixture form; a top-level one below it is invisible to the frames the test makes. See [Test an event handler](event-handlers.md#4-the-trap-frames-dont-isolate-registrations).

### The `:test` preset

Most test frames want HTTP redirected to a stub and generated facts strict. `{:preset :test}` on `rf/make-frame` does both:

```clojure
;; Never reaches the network; an unsupplied generated cofx raises an error.
(rf/with-new-frame [f (rf/make-frame {:preset :test})]
  (rf/dispatch-sync [:todo/fetch])
  (is (= :loaded (:sync-status (rf/app-db-value f)))))
```

The preset expands to `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}`, `:rf.cofx/mint-policy :strict` and `:drain-depth 100`, the framework default. The canned stub is registered by `re-frame.http.test-support`, which the test namespace above already requires. Your own keys win over the expansion, key by key rather than deep: a frame that passes its own `:fx-overrides` replaces the preset's, HTTP redirect included. To keep both, add the redirect to your map, `{:preset :test :fx-overrides {:todo.storage/save (fn [_ _] nil) :rf.http/managed :rf.http/managed-canned-success}}`. Use `with-request-stubs` when a test needs different replies per route.

## Asserting on what would dispatch

Sometimes the thing under test is which event a handler dispatches next. `:dispatch` is itself an effect that can be overridden, so a per-call `:fx-overrides {:dispatch …}` captures the event vector instead of queueing it:

```clojure
(rf/reg-event :todo/clear-done
  (fn [{:keys [db]} _]
    {:db (update db :todos #(into {} (remove (comp :done? val)) %))
     :fx [[:dispatch [:todo/set-showing :all]]]}))

(deftest clear-done-resets-the-filter
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [dispatched (atom [])]
      (rf/dispatch-sync [:todo/clear-done]
                        {:fx-overrides {:dispatch (fn [_ ev] (swap! dispatched conj ev))}})
      (is (= [[:todo/set-showing :all]] @dispatched)))))
```

A per-call override applies to the run it starts, and to that run's `:dispatch` and `:dispatch-later` children. It does not reach an HTTP reply, which is a new dispatch. To cover the reply too, wrap the body in `rf/with-fx-overrides`: every dispatch made while the body runs carries the override, including stubbed replies. Precedence is per-call opt, then `with-fx-overrides`, then the frame's `:fx-overrides`.

Suppose `:todo/fetched` also returns `:fx [[:dispatch [:todo/set-showing :all]]]`. This test checks that dispatch without running it:

```clojure
(deftest fetched-resets-the-filter
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [dispatched (atom [])]
      (rf/with-fx-overrides {:dispatch (fn [_ ev] (swap! dispatched conj ev))}
        (http-test-support/with-request-stubs
          {[:get "/api/todos"] {:reply {:ok []}}}
          (fn [] (rf/dispatch-sync [:todo/fetch]))))
      (is (= :loaded (:sync-status (rf/app-db-value f))))
      (is (= [[:todo/set-showing :all]] @dispatched)))))
```

!!! warning "Gotcha: scope a `:dispatch` override to a call, never to a frame"

    In a frame's config, a `:dispatch` override applies to every dispatch routed to that frame, including framework traffic such as machine messages and HTTP reply handling. Scope it to one dispatch, or to a `with-fx-overrides` body.

A few framework effects that install state can't be overridden: `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow` and the router's `:rf.route/with-nav-token`. An override targeting one is ignored, and the runtime emits `:rf.error/reserved-fx-override` and runs the real handler, because stubbing them would leave the frame's [runtime-db](../glossary.md#runtime-db) inconsistent. To test those operations, let the real effect run and read the resulting state.

## Replay a bug as a regression test

A user reports: "I ticked a todo, unticked it, cleared done todos, and it disappeared anyway." That report is a list of events. `app-db` is the result of applying each event in turn to the initial state, so replaying the same list rebuilds the same state, bug included.

If [Xray](../glossary.md#xray) was open when the bug happened, copy the exact events (and each one's `:rf.cofx` facts) from its recorded [epoch](../glossary.md#epoch) history. The test replays the sequence and asserts the correct result:

```clojure
(deftest issue-217-clear-done-removes-unticked-todo
  (rf/with-new-frame [f (rf/make-frame
                          {:initial-events [[:rf/set-db {:todos {1 {:id 1 :title "Buy milk" :done? false}}
                                                         :showing :all}]]})]
    (doseq [ev [[:todo/toggle 1]
                [:todo/toggle 1]
                [:todo/clear-done]]]
      (rf/dispatch-sync ev))
    (is (= "Buy milk" (get-in (rf/app-db-value f) [:todos 1 :title])))))
```

A `doseq` over `dispatch-sync` runs the events in order, each draining before the next. The sequence belongs in the test body because it is what is under test; the starting state is setup, so it stays in the frame's construction. When a replayed event's handler declares facts, give that event its own `dispatch-sync` carrying the recorded `:rf.cofx`. The test fails until the handler is fixed and then stays as a regression test.

When the bug is "the state was briefly wrong between two events", read state after each event:

```clojure
(let [seen (atom [])]
  (doseq [ev [[:todo/toggle 1] [:todo/toggle 1] [:todo/clear-done]]]
    (rf/dispatch-sync ev)
    (swap! seen conj [(first ev) (get-in (rf/app-db-value f) [:todos 1 :done?])]))
  @seen)
;; => [[:todo/toggle true] [:todo/toggle false] [:todo/clear-done false]]
```

For one path, `ts/assert-path-equals` gives a `clojure.test` failure message naming the frame and path, and inside `with-new-frame` it reads that frame without a `:frame` option (see [Test an event handler](event-handlers.md#checking-one-path)).

## Asserting on a derived value

Sometimes the value worth checking is derived, such as a count or a filtered list. `compute-sub` computes a [subscription](../glossary.md#subscription), here `:todo/remaining-count` from [Subscriptions](../subscriptions.md), against the frame's `app-db`, so the assertion covers the sub's logic too:

```clojure
(deftest remaining-after-clear
  (rf/with-new-frame [f (rf/make-frame
                          {:initial-events [[:rf/set-db {:todos {1 {:id 1 :title "Buy milk"     :done? true}
                                                                 2 {:id 2 :title "Walk the dog" :done? false}}}]]})]
    (rf/dispatch-sync [:todo/clear-done])
    (is (= 1 (rf/compute-sub [:todo/remaining-count] (rf/app-db-value f))))))
```

`(rf/compute-sub query-v db)` runs the subscription against an `app-db` value with no reactive cache and no adapter, computing any input subs first. It returns `nil` if the sub body throws (`:rf.error/sub-exception`). An input naming an unregistered sub computes to `nil` without an error record, and the body runs on that `nil`. To read what a running frame's cache holds instead, use `subscribe-once`; [Test a subscription](subscriptions.md) covers `compute-sub`.

## What the dispatch opts can change

Every opt on this page changes something around the handler, never the handler function itself:

| Opt | What it changes |
|---|---|
| `:frame` | which frame the event runs in |
| `:rf.cofx` | the facts the handler receives |
| `:rf.cofx/mint-policy` | whether an unsupplied generated fact is produced (`:explicit-live`) or raises an error (`:strict`) |
| `:fx-overrides` | what performs the effects the handler returns |
| `:interceptor-overrides` | which interceptors are removed or replaced for this dispatch |

A key the runtime does not know is ignored. A dev build emits `:rf.warning/unknown-dispatch-opt` naming it, so a misspelt `:rf/cofx` shows up in the trace rather than as a test running on the live clock.

Because the handler itself can't be replaced, a passing test says that the production handler, given those inputs, returns those effects. Replaying recorded events with their recorded inputs therefore computes the same state production computed.

## Advanced

### One reply handler: `:reply-to`

Instead of `:on-success` and `:on-failure`, a request can name one event, `:reply-to`, that receives the reply for both outcomes; the handler branches on the reply's `:status`:

```clojure
(rf/reg-event :todo/fetch-one
  (fn [_ [_ id]]
    {:fx [[:rf.http/managed {:request  {:method :get :url (str "/api/todos/" id)}
                             :reply-to [:todo/fetched-one id]}]]}))

(rf/reg-event :todo/fetched-one
  (fn [{:keys [db]} [_ id {:keys [status value error]}]]
    {:db (assoc-in db [:todos id]
                   (if (= :ok status) value {:id id :error (:kind error)}))}))

(deftest fetch-single-reply-target
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:get "/api/todos/2"] {:reply {:ok {:id 2 :title "Walk the dog" :done? false}}}}
      (fn []
        (rf/dispatch-sync [:todo/fetch-one 2])
        (is (= "Walk the dog" (get-in (rf/app-db-value f) [:todos 2 :title])))))))
```

A request must address its reply: one with no `:reply-to`, `:on-success` or `:on-failure` raises `:rf.error/http-no-reply-target`. An explicit `:on-failure nil` drops failures on purpose (a telemetry beacon, say), and the runtime emits a one-shot dev-only `:rf.warning/failure-swallowed` when a live request's failure is dropped that way.

### Removing noisy interceptors: `:interceptor-overrides`

A logging or analytics [interceptor](../glossary.md#interceptor) that runs on every event can flood the test output. Remove it for the test frame with `:interceptor-overrides`, keyed by the interceptor's registered reference:

```clojure
(rf/make-frame
  {:id                    :test/quiet
   :interceptor-overrides {:my-app/request-logger nil}})   ;; nil removes it
```

A parameterized reference such as `[:rf.interceptor/path [:todos]]` is matched by the whole vector. The value is `nil` (remove) or another registered reference (replace). The same key in the `dispatch-sync` opts wins over the frame's.

### A runaway drain halts at `:drain-depth`

If a handler re-dispatches itself, or a stubbed reply re-fires the request that caused it, the drain stops when it exceeds `:drain-depth` and emits `:rf.error/drain-depth-exceeded` (tags `:depth`, `:queue-size`, `:last-event`). Events that already ran keep their committed `:db`, the remaining queued events are discarded, and nothing is rolled back, so a test that hits the cap reads partly advanced state. If a `dispatch-sync` seems to loop, check your error listener for that error.

### `with-new-frame` or `with-frame`

`with-new-frame [f expr]` takes a vector: it evaluates `expr`, binds the result, runs the body, and destroys the frame on exit. `with-frame :some-id` takes a keyword: it makes an existing frame current and neither creates nor destroys it. The wrong shape fails at compile time with `:rf.error/with-new-frame-keyword-form` or `:rf.error/with-frame-vector-form`. Use `with-new-frame` per test, and `with-frame` for a frame shared across several `deftest`s.

Frames, dispatch opts and drain behaviour are covered in [Frames](../frames.md) and [Run to completion](../run-to-completion.md), the reply map and stubs in [Managed HTTP](../../async/http.md), and the test helpers in the [test-support API reference](../../api/re-frame.test-support.md).
