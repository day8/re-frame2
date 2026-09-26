# Test a pipeline run

Some behaviour only shows up across several events. A handler writes state and fires an HTTP request; the reply arrives as another event, whose handler writes more state and may dispatch still more. Each dispatch is one [**pipeline run**](../glossary.md#run), one pass through the [event pipeline](../glossary.md#event-pipeline): handler → [effect map](../glossary.md#effect-map) → effects → [subscriptions](../glossary.md#subscription) → [view](../glossary.md#view). Follow-up dispatches are their own runs inside the same [drain](../glossary.md#drain--run-to-completion).

This page tests that chain end to end: an event goes in, the queue drains to a fixed point, and you assert on the committed [`app-db`](../glossary.md#app-db). The tests run on the JVM with no browser and no mock library.

Nothing is mocked. The handler under test runs unmodified and returns the same [effect](../glossary.md#effect) data it returns in production; the test only changes what answers that effect. There is no service worker, no patched `fetch` and no module mocking.

??? info "Coming from MSW?"

    Mock Service Worker intercepts at the network boundary and answers with canned responses, so the code under test runs unmodified. re-frame2 intercepts earlier: the request is an effect, data the handler returns, so the test redirects that effect id to a different handler and never reaches the network stack.

## The shape of every pipeline-run test

Every pipeline-run test has the same three steps: create a fresh [frame](../glossary.md#frame) (an isolated running instance of your app, with its own `app-db` and event queue), call [`dispatch-sync`](../glossary.md#dispatch-sync), and assert against the frame's `app-db`.

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))

(deftest counter-walk
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:count (rf/app-db-value f))))))
```

`with-new-frame` gives the test its own frame and destroys it on exit, like `with-open`, so nothing leaks between tests. `dispatch-sync` returns only when the drain has settled: the event runs, every follow-up `:dispatch` its handlers queue runs, and, as shown below, every stubbed HTTP reply runs too. This is [run-to-completion](../glossary.md#drain--run-to-completion), so the assertion on the next line reads committed state. There is no `act()`, no fake timer to advance and nothing to `await`.

To test one handler as a pure function instead, see [Test an event handler](event-handlers.md).

??? info "From re-frame v1"

    `dispatch-sync` drains follow-up dispatches too, so the v1 test library's `wait-for` and `run-test-sync` have no counterpart. Call `dispatch-sync` directly under a `make-reset-runtime-fixture` and the test body reads the same.

!!! note "`with-new-frame` vs `with-frame` — the argument shape tells you which"

    `with-new-frame [f expr]` takes a **vector**: it evaluates `expr` (typically `(rf/make-frame {})`), binds the result, runs the body, and destroys the frame on exit. `with-frame :some-id` takes a **keyword**: it makes an existing frame current and neither creates nor destroys it. The wrong argument shape fails at compile time with `:rf.error/with-new-frame-keyword-form` or `:rf.error/with-frame-vector-form`. Use `with-new-frame` per test, and `with-frame` for a frame shared across several `deftest`s.

## A real pipeline run: the code under test

Now a login flow that records state, fires a request, and folds the reply back in. The handlers live in a `.cljc` file, which compiles for both the browser and the JVM, so the same code runs in the app and under a JVM test runner.

```clojure
;; src/my_app/session.cljc
(ns my-app.session
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]))   ;; registers :rf.http/managed

(rf/reg-event :session/login
  {:doc "Submit credentials; record when we tried."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [email password]}]]
    {:db (assoc db :session/status       :pending
                   :session/attempted-at time-ms)
     :fx [[:rf.http/managed
           {:request    {:method :post
                         :url    "/api/users/login"
                         :body   {:user {:email email :password password}}}
            :on-success [:session/login-ok]
            :on-failure [:session/login-failed]}]]}))

(rf/reg-event :session/login-ok
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :session/status :authed
                   :session/user   (:user value))}))

(rf/reg-event :session/login-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc db :session/status :error
                   :session/error  (:kind error))}))
```

A test can substitute values at two points without editing the handler. The clock is a declared [coeffect](../glossary.md#coeffect) (`:rf.cofx/requires [:rf/time-ms]`), so a test can supply an exact value. The HTTP request is an effect in the returned map, so a test can answer it without a network. [Effects and coeffects](../coeffects.md) covers the model.

!!! note "Where do `:value` and `:error` come from?"

    Managed HTTP appends a reply map to the `:on-success` or `:on-failure` event vector: `{:status :ok :value <decoded-body> …}` or `{:status :error :error <failure-map> …}`. So `:session/login-ok` destructures `:value`, and `:session/login-failed` destructures `:error`, whose `:kind` is one of eight `:rf.http/*` categories. [Managed HTTP](../../async/http.md) lists the full reply map and the categories.

## The test

```clojure
;; test/my_app/session_test.clj
(ns my-app.session-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.http.test-support :as http-test-support]   ;; canned-reply stubs — test-only, never in production requires
            [re-frame.test-support :as ts]
            [my-app.session]))             ;; loads the registrations

(deftest login-happy-path
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:post "/api/users/login"]
       {:reply {:ok {:user {:email "alice@example.com" :token "jwt.abc"}}}}}
      (fn []
        (rf/dispatch-sync [:session/login {:email    "alice@example.com"
                                           :password "hunter2"}]
                          {:rf.cofx {:rf/time-ms 1781078400000}})
        (let [db (rf/app-db-value f)]
          (is (= :authed (:session/status db)))
          (is (= 1781078400000 (:session/attempted-at db)))
          (is (= "alice@example.com" (get-in db [:session/user :email]))))))))

(deftest login-bad-credentials
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:post "/api/users/login"]
       {:reply {:failure {:kind :rf.http/http-4xx :status 401}}}}
      (fn []
        (rf/dispatch-sync [:session/login {:email    "alice@example.com"
                                           :password "wrong"}])
        (is (= :error            (:session/status (rf/app-db-value f))))
        (is (= :rf.http/http-4xx (:session/error  (rf/app-db-value f))))))))
```

Run it with your project's JVM test runner (`clojure -M:test`). Both tests cover the full chain: request out, reply in, reply handler updates state.

### Supply the facts: `{:rf.cofx {...}}`

The router stamps `:rf/time-ms` on every dispatch, which is why the second test runs without mentioning it. Left alone, though, it is the live clock, and an assertion on `:session/attempted-at` would be flaky, so the first test pins it. Facts supplied under `:rf.cofx` in the dispatch opts win: the runtime fills only what is missing.

A handler receives exactly the facts its `:rf.cofx/requires` vector names, flat in the coeffects map, so that vector is the test's checklist; read it with `(rf/handler-meta {:source :store :kind :event :id :session/login})`. A declared fact the runtime can't satisfy raises an error — `:rf.error/missing-required-cofx`, or `:rf.error/unregistered-cofx` when nothing registers it — rather than delivering `nil`.

!!! note "Why generated facts are stricter than the clock"

    The router stamps `:rf/time-ms` on every dispatch, so it is always present. Other [recordable facts](../glossary.md#recordable-vs-ambient-coeffects) backed by a generator — a `reg-cofx` that produces a fresh id or a random choice — are not. What happens when a dispatch doesn't supply one depends on the frame's mint policy. A plain `(rf/make-frame {})` uses the `:live` default and runs the generator. A `{:preset :test}` frame uses `:strict` and raises `:rf.error/missing-required-cofx` instead, so a test can't pass on a value production would never produce. Supply the fact in `:rf.cofx`, or pass `{:rf.cofx/mint-policy :explicit-live}` as a dispatch opt when a fresh value per run is what you want.

### Answer the HTTP: canned replies by method + URL

`with-request-stubs` (in `re-frame.http.test-support`, not `re-frame.core`) takes a route map of `[method url]` → reply, and a zero-argument function. While that function runs, every `:rf.http/managed` request that matches a route is answered from the table. `{:reply {:ok value}}` produces a success reply and `{:reply {:failure {:kind … :status …}}}` a failure. The reply has the same shape as a live one and is dispatched the same way, so the reply handler can't tell the difference, and it lands inside the same `dispatch-sync` drain.

One table can hold several routes. Each request is matched on its `:request :method` and `:request :url` (the URL after any per-frame HTTP interceptors have run):

```clojure
(http-test-support/with-request-stubs
  {[:get    "/api/profiles/alice"] {:reply {:ok {:profile {:username "alice"}}}}
   [:post   "/api/articles"]       {:reply {:ok {:article {:slug "hello"}}}}
   [:delete "/api/articles/old"]   {:reply {:failure {:kind :rf.http/http-4xx :status 403}}}}
  (fn []
    ;; ... dispatch the events whose handlers fire those three requests ...))
```

A request that matches no route is answered with a failure of kind `:rf.http/transport`, tagged `"no stub matched"` with the method and URL, through the normal `:on-failure` path. The miss shows up in your failure handler's state, where the next assertion catches it, so the table must name every request the path under test fires.

#### Observing the `:pending` state before the reply lands

The tests above assert the settled state, so they never see `:session/status :pending`. To test the in-flight state (a spinner, a disabled submit button), delay the reply. The route table has no delay option, but the canned stubs read an `:after-ms` key from their args map. Redirect `:rf.http/managed` to a wrapper that adds `:after-ms` (and the reply `:value`) and calls the registered `:rf.http/managed-canned-success` handler. The reply then arrives on a later `:dispatch-later` tick, so `:pending` is observable in between:

```clojure
(deftest login-shows-pending-then-authed
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [canned (:handler-fn (rf/handler-meta {:source :store :kind :fx :id :rf.http/managed-canned-success}))]
      (rf/dispatch-sync [:session/login {:email "alice@example.com" :password "x"}]
                        {:rf.cofx      {:rf/time-ms 1781078400000}
                         :fx-overrides {:rf.http/managed
                                        (fn [frame-ctx args]
                                          (canned frame-ctx
                                                  (assoc args
                                                         :after-ms 20
                                                         :value    {:user {:email "alice@example.com"}})))}})
      ;; The request fired but the reply hasn't landed yet — :pending is observable.
      (is (= :pending (:session/status (rf/app-db-value f))))
      ;; Wait for the deferred reply to settle, then assert the final state.
      (ts/poll-until #(= :authed (:session/status (rf/app-db-value f))))
      (is (= :authed (:session/status (rf/app-db-value f)))))))
```

`ts/poll-until` (from `re-frame.test-support`) polls a predicate until it returns truthy or a deadline passes (defaults `:timeout-ms 2000`, `:interval-ms 5`), so a stuck drain shows up as a timeout rather than a hang. On the JVM it is synchronous: it returns the truthy value, or throws an `ex-info` carrying `:rf.error/poll-until-timeout`. On CLJS it returns a `js/Promise` to compose under `cljs.test/async`, resolving with the value or rejecting on timeout. The optional `:label` goes into the timeout message. Use it whenever work settles after `dispatch-sync` returns: a delayed reply, a machine `:after` transition, a `:dispatch-later`.

Don't use `poll-until` to wait out a timer window such as a grace period or a debounce. There the duration is what you are testing, so use a `Thread/sleep` and say so in a comment. `poll-until` waits for a change to appear; a fixed sleep checks what does or doesn't happen within a window.

### Redirect anything: `:fx-overrides`

The stub table is built on a more general mechanism. A per-dispatch `:fx-overrides` map redirects any effect id for that one dispatch, to a function or to another registered effect:

```clojure
(deftest login-sends-the-right-request
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [sent (atom nil)]
      (rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                        {:rf.cofx       {:rf/time-ms 0}
                         :fx-overrides {:rf.http/managed
                                        (fn [_frame-ctx args] (reset! sent args))}})
      (is (= :post              (get-in @sent [:request :method])))
      (is (= "/api/users/login" (get-in @sent [:request :url]))))))
```

The override receives the exact args map the handler built, so you assert on the request without performing it. The same option silences a logger, captures your own effects, or redirects to `:rf.http/managed-canned-success` by keyword, the success stub shipped in `re-frame.http.test-support`:

```clojure
;; Redirect to the framework-shipped canned-success stub by keyword.
(rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                  {:rf.cofx       {:rf/time-ms 0}
                   :fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
;; The success stub synthesises a canonical {:status :ok :value {:stubbed true} …} reply
;; (override the value by supplying :value in the args map the handler builds).
```

!!! note "The override fn takes two args"

    An `:fx-overrides` value is either another registered fx-id (a keyword) or a function `(fn [frame-ctx args] ...)`. The first argument is the frame context; the second is the effect's args map, which for `:rf.http/managed` is `{:request {...} :on-success [...] :on-failure [...]}`. The return value is ignored, as it is for an ordinary `reg-fx` handler.

!!! note "Testing your own `reg-fx`"

    Because an fx handler has the same two-argument shape, you can call one you wrote directly, with a stub frame context and a literal args map, when its body has logic worth testing. Keep those bodies thin: the more of an effect's behaviour is decided by the args your handlers build, the more the capture test above already covers.

!!! warning "Gotcha — frames isolate `app-db`, not registrations"

    A fresh frame gets its own state and queue, but handlers live in the process-global [registrar](../glossary.md#registrar), so registering `:session/login` registers it for every test. If your tests call `rf/reg-event` themselves rather than requiring app namespaces, add `(use-fixtures :each (ts/make-reset-runtime-fixture))` once per file so one test's registrations can't leak into the next. Registrations made by requiring `my-app.session`, as the tests above do, stay stable for the whole run.

### The `:test` preset — deterministic defaults in one key

Most test frames want both behaviours above: HTTP redirected to a stub, and generated facts strict. `{:preset :test}` on `rf/make-frame` sets them up:

```clojure
;; Never reaches the network; an unsupplied generated cofx raises an error.
(rf/with-new-frame [f (rf/make-frame {:preset :test})]
  (rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                    {:rf.cofx {:rf/time-ms 0}})
  (is (= :authed (:session/status (rf/app-db-value f)))))
```

The preset expands to three entries: `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}`, so every managed request gets a canned success reply; `:drain-depth 100`, the framework's default cap on how many events one drain processes, stated explicitly so tools can read it off the frame; and `:rf.cofx/mint-policy :strict`. The canned stub is registered by `re-frame.http.test-support`, which this page's test namespace already requires; without it the redirect target doesn't exist. Your own keys win over the expansion, so you can still pin a different HTTP stub, or opt into `:explicit-live` per dispatch. Use `with-request-stubs` when a test needs different replies per route.

!!! warning "Gotcha — a runaway drain halts at `:drain-depth`, it doesn't hang"

    If a handler re-dispatches itself, or a stubbed reply re-fires the request that caused it, the drain stops when it exceeds `:drain-depth` and emits `:rf.error/drain-depth-exceeded` (tags `:depth`, `:queue-size`, `:last-event`). Each event is atomic, not the whole drain: events that already settled keep their committed `:db`, the remaining queued events are discarded, and nothing is rolled back. A test that hits the cap therefore reads partly advanced state. If a `dispatch-sync` seems to loop, check your error listener for that error.

## Asserting on what would dispatch

Sometimes the thing under test is which event a handler decided to dispatch next. `:dispatch` is itself a reserved fx that can be overridden, so a per-call `:fx-overrides {:dispatch ...}` captures the event vector instead of queueing it, and the drain stops one step in:

```clojure
(rf/reg-event :session/logout
  (fn [_ _]
    {:fx [[:dispatch [:nav/goto :login]]]}))

(deftest logout-fires-redirect
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [dispatched (atom [])]
      (rf/dispatch-sync [:session/logout]
                        {:fx-overrides {:dispatch (fn [_ ev] (swap! dispatched conj ev))}})
      ;; The handler tried to dispatch; we captured it instead of running it.
      (is (= [[:nav/goto :login]] @dispatched)))))
```

A per-call override applies to the run it starts, and the `:dispatch` / `:dispatch-later` children of that dispatch inherit it. It does not reach an HTTP reply, which is a new dispatch (tagged `:source :http`), so a per-call capture on the request's dispatch never sees what the reply handler dispatches. To cover the reply too, wrap the body in `rf/with-fx-overrides`: every dispatch made while the body runs carries the override, including stubbed replies, which arrive inside the same drain. `with-request-stubs` installs its routing the same way. Precedence is per-call opt, then `with-fx-overrides`, then the frame's `:fx-overrides`.

Suppose the success handler also navigates, returning `{:db … :fx [[:dispatch [:nav/goto :home]]]}`. This test checks that dispatch without running it:

```clojure
(deftest login-ok-navigates-home
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [dispatched (atom [])]
      (rf/with-fx-overrides {:dispatch (fn [_ ev] (swap! dispatched conj ev))}
        (http-test-support/with-request-stubs
          {[:post "/api/users/login"] {:reply {:ok {:user {:email "a@b.c"}}}}}
          (fn []
            (rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                              {:rf.cofx {:rf/time-ms 0}}))))
      (is (= :authed (:session/status (rf/app-db-value f))))
      (is (= [[:nav/goto :home]] @dispatched)))))
```

!!! warning "Gotcha — scope a `:dispatch` override per-call, never per-frame"

    In a frame's config, a `:dispatch` override applies to every dispatch routed to that frame for its whole lifetime, including framework-internal traffic such as machine actor messages and HTTP reply handling. That re-routes events the test never meant to touch. Scope it to one dispatch, or to a `with-fx-overrides` body.

!!! note "State-installing fxs can't be overridden"

    `:dispatch` and `:dispatch-later` can be overridden, but the reserved fxs that install framework state — `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow` and the router's `:rf.route/with-nav-token` — cannot. An override targeting one is ignored: the runtime emits `:rf.error/reserved-fx-override` and runs the real handler. Stubbing them would leave the frame's [runtime-db](../glossary.md#runtime-db) inconsistent (a spawned actor whose snapshot was never installed makes every later dispatch to it fail). To test those operations, let the real fx run and read the resulting runtime-db state.

    Redirecting one of your own effects *to* one of these ids is allowed: `{:my/custom-fx :rf.machine/spawn}` runs the real registered handler, which your app could emit directly anyway.

## Silencing noise — `:interceptor-overrides`

A logging or analytics [interceptor](../glossary.md#interceptor) that runs on every event can flood the test output. Remove it for the test frame with `:interceptor-overrides`, keyed by the interceptor's registered reference, with `nil` as the value:

```clojure
(rf/make-frame
  {:id                    :test/quiet
   :interceptor-overrides {:my-app/request-logger nil}})   ;; nil removes the interceptor
```

A bare keyword key matches that registered interceptor; a parameterized reference such as `[:rf.interceptor/path [:cart]]` is matched by the whole vector, not the bare id. The value is `nil` (remove) or another registered reference (replace). `:interceptor-overrides` in the `dispatch-sync` opts win over the frame's on a key conflict, the same precedence as `:rf.cofx` and `:fx-overrides`.

## Replay a bug as a regression test

A user reports: "I favorited an article, then unfavorited it, and the count stuck." That report describes a list of events: favorite, then unfavorite. `app-db` is the result of applying each event in turn to the initial state, so replaying the same list rebuilds the same state, bug included.

If [Xray](../glossary.md#xray), the dev inspector, was open when the bug happened, copy the exact events (and each one's `:rf.cofx` facts) from its recorded [epoch](../glossary.md#epoch) history instead of reconstructing them. The test replays that sequence and asserts the correct result:

```clojure
(ns my-app.regression-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.articles]))

(deftest issue-217-unfavorite-leaves-count-stale
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:app/init]]})]
    (doseq [ev [[:article/loaded {:slug "ten-tips" :favorites-count 0}]
                [:article/favorite "ten-tips"]
                [:article/unfavorite "ten-tips"]]]
      (rf/dispatch-sync ev))
    ;; assert what the fold SHOULD produce
    (is (= 0 (get-in (rf/app-db-value f)
                     [:articles "ten-tips" :favorites-count])))))
```

A `doseq` over `dispatch-sync` runs the events in order, each draining before the next. Here the sequence belongs in the test body rather than in `:initial-events`, because the sequence is what is under test; app boot is setup, so `[:app/init]` stays in the frame's construction. When a replayed event's handler declares facts, give that event its own `dispatch-sync` carrying the recorded `:rf.cofx`, so the replay uses the recorded inputs. The test fails until the handler is fixed and then stays as a regression test, with every input pinned.

!!! note "Capturing intermediate state on the way through"

    When the bug is "the count was briefly wrong *between* two events", read state after each event's drain settles:

    ```clojure
    (let [seen (atom [])]
      (doseq [ev [[:article/favorite "ten-tips"]
                  [:article/unfavorite "ten-tips"]]]
        (rf/dispatch-sync ev)
        (swap! seen conj
               [(first ev)
                (get-in (rf/app-db-value f) [:articles "ten-tips" :favorites-count])]))
      @seen)   ;; => [[:article/favorite 1] [:article/unfavorite 0]]
    ```

`re-frame.test-support` also has `assert-path-equals`, a `clojure.test`-aware check of one path, whose failure message names the frame and path. It looks the frame up by id, so give the test frame an `:id` and pass it:

```clojure
;; with (rf/make-frame {:id :test/regression :initial-events [[:app/init]]})
(ts/assert-path-equals [:articles "ten-tips" :favorites-count] 0 {:frame :test/regression})
```

Without `{:frame …}` it reads the reset fixture's ambient frame (`:rf/default` when the fixture installs an adapter). Its name matches the `:rf.assert/path-equals` event used in Story scripts. For a whole-map check compare `app-db-value` directly, `(is (= expected-db (rf/app-db-value f)))`; for one path, the plain `(is (= … (get-in …)))` form is just as clear.

## Asserting on a derived value, not raw `app-db`

Sometimes the value worth checking is derived — a filtered list, a count, a formatted label — rather than the raw state it is computed from. `compute-sub` computes a [subscription](../glossary.md#subscription) against the frame's `app-db`, still on the JVM, so the assertion covers the sub's logic too:

```clojure
(rf/reg-sub :articles/favorited
  (fn [db _] (filterv :favorited? (vals (:articles db)))))

(deftest favorited-derives-correctly
  (rf/with-new-frame [f (rf/make-frame
                          {:initial-events [[:article/loaded {:slug "a" :favorited? true}]
                                            [:article/loaded {:slug "b" :favorited? false}]]})]
    ;; assert the SUB's output, not the raw :articles map
    (is (= 1 (count (rf/compute-sub [:articles/favorited]
                                    (rf/app-db-value f)))))))
```

`(rf/compute-sub query-v db)` runs the subscription against an `app-db` value and returns the result, with no reactive cache and no adapter. `query-v` is the vector you would pass to `subscribe`, arguments included, and subs built on other subs compute their inputs first. Build `db` with real events, as above, so the sub is tested against state your handlers actually produce; [Test a subscription](subscriptions.md) covers the alternatives.

!!! warning "Gotcha"

    `compute-sub` returns `nil` in two failure cases. A sub body that throws emits `:rf.error/sub-exception`, which an error listener sees. An input naming an unregistered sub computes to `nil` with no error record (`:rf.error/no-such-sub` comes from the reactive `subscribe` path). So an unexpected `nil` can mean a thrown body or a misspelled input id: check your error listener and re-read the input ids.

!!! note "`compute-sub` is JVM-pure; it is not the live reactive value"

    It recomputes from the `db` you pass on every call, with nothing carried between calls. To read what a running frame's cache holds right now, use `subscribe-once` instead: same query vector, no db argument; it subscribes, reads the value and releases the subscription.

## One reply handler: `:reply-to`

The login handler names two reply targets, `:on-success` and `:on-failure`. The alternative is `:reply-to`, a single event vector that receives the reply for both outcomes; the handler branches on the reply's `:status`:

```clojure
(rf/reg-event :profile/load
  (fn [{:keys [db]} [_ username]]
    {:db (assoc-in db [:profiles username :loading?] true)
     :fx [[:rf.http/managed {:request  {:method :get :url (str "/api/profiles/" username)}
                             :reply-to [:profile/loaded username]}]]}))

(rf/reg-event :profile/loaded
  (fn [{:keys [db]} [_ username {:keys [status value error]}]]
    {:db (assoc-in db [:profiles username]
                   (if (= :ok status) value {:error (:kind error)}))}))
```

The test has the same shape as before: stub the route, dispatch, assert on the settled state.

```clojure
(deftest profile-load-single-reply-target
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:get "/api/profiles/alice"] {:reply {:ok {:bio "hello"}}}}
      (fn []
        (rf/dispatch-sync [:profile/load "alice"])
        (is (= {:bio "hello"} (get-in (rf/app-db-value f) [:profiles "alice"])))))))
```

A request must address its reply: one with no `:reply-to`, `:on-success` or `:on-failure` raises `:rf.error/http-no-reply-target` when the effect runs. An explicit `:on-failure nil` silences failures on purpose (fire-and-forget, such as a telemetry beacon). When a live request's failure is dropped that way, the runtime emits a one-shot dev-only `:rf.warning/failure-swallowed`; aborted requests are excluded.

## What the dispatch opts can change

Every opt this page used changes something around the handler, never the handler function itself:

| Opt | What it changes |
|---|---|
| `:frame` | which frame the event runs in |
| `:rf.cofx` | the facts the handler receives |
| `:rf.cofx/mint-policy` | whether an unsupplied generated fact is produced (`:explicit-live`) or raises an error (`:strict`) |
| `:fx-overrides` | what performs the effects the handler returns |
| `:interceptor-overrides` | which interceptors are removed or replaced for this dispatch |

Because the handler itself can't be replaced, a passing test says that the production handler, given those inputs, returns those effects.

??? note "Going deeper — why replay reproduces production"

    An event handler is a pure step function `(db, event, coeffects) → (db', effects)`, and `app-db` is the result of folding that step over the event history, starting from the initial db. The opts above change the fold's inputs (`:rf.cofx`) and how its outputs are carried out (`:fx-overrides`), not the step function. Replaying recorded events with their recorded inputs therefore computes the same fold production computed.

Frames, dispatch opts and drain behaviour are covered in [Frames](../frames.md) and [Run to completion](../run-to-completion.md); the reply map and stubs in [Managed HTTP](../../async/http.md); and `assert-path-equals`, `poll-until` and `make-reset-runtime-fixture` in the [test-support API reference](../../api/re-frame.test-support.md). Keep `re-frame.http.test-support` out of production requires.
