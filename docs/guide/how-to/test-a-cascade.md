# Test a full cascade

You have an event handler — the function that decides how state changes in response to something happening — and it does real work. It stamps state, fires an HTTP request, gets the reply back as another event, and folds the result in along with follow-up dispatches. What you want to test is the whole journey: an event goes in, and settled `app-db` (your app's single state map) comes out the other side. This recipe tests that journey on the JVM. No browser, no mock library, milliseconds per test.

The trick that makes it fast is one idea: **you don't mock, you redirect.** The handler under test runs unmodified and produces the same effect data it produces in production — an effect is just a description of work, returned as plain data. The test changes only *who answers* that effect. No service worker, no patched `fetch`, no module-mock hoisting.

> **For JavaScript developers.** Your closest anchor here is **MSW**. With MSW you don't mock your own modules; you intercept at the network boundary and answer with canned responses, so the code under test runs unmodified. re-frame2 keeps that idea and moves the boundary earlier. An effect is data the handler returns — the request is a *description* before it's ever a connection — so the test never touches network traffic at all. It redirects the effect's id to a different answerer for one dispatch, and that's it.

## The shape of every cascade test

Start with the simplest possible version. Every cascade test is the same three moves: a fresh frame, a `dispatch-sync`, an assertion against the frame's `app-db`. (A frame is one isolated instance of your running app — its own `app-db`, its own event queue. A `dispatch` is how you send an event into it.)

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))

(deftest counter-walk
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:count (rf/app-db-value f))))))
```

`with-new-frame` gives the test its own isolated frame and destroys it on exit, so nothing leaks between tests. `dispatch-sync` **drains to fixed point**: the event settles, every follow-up `:dispatch` its handlers queue settles, and (as you'll see below) every stubbed HTTP reply settles too — all before the call returns. So the assertion on the next line reads committed state. No `act()`, no fake timer to advance, nothing to `await`.

If instead you want to test one handler as the pure function it is, see [Test an event handler](test-an-event-handler.md). This page is for when the interesting behaviour is the chain itself.

> **From re-frame v1.** `dispatch-sync` drains the follow-up dispatches too, so the `wait-for` choreography from the v1 test library is gone. And there is no `run-test-sync` shim — `dispatch-sync` is already settle-by-default, so the v1 macro was pure migration tax. Inline your `dispatch-sync` calls under a `make-reset-runtime-fixture` and the body reads the same.

> **`with-new-frame` vs `with-frame`.** These are siblings, and the macro name telegraphs the intent. `with-new-frame [f expr]` takes a **vector** — it evaluates `expr` (typically `(rf/make-frame {})`), binds the result, runs the body, and *destroys* the frame on exit (modelled on `with-open`). `with-frame :some-id` takes a **keyword** — it pins to a frame that already exists (registered via `reg-frame`, or created earlier) and does *not* create or destroy it. Pass the wrong argument shape and the macro rejects it at compile time (`:rf.error/with-new-frame-keyword-form` / `:rf.error/with-frame-vector-form`) — so you can't accidentally leak a frame by pinning when you meant to bracket. Reach for `with-new-frame` for per-test fixtures; reach for `with-frame` for a shared fixture across several `deftest`s.

## A real cascade: the code under test

The counter was a warm-up. Now a real chain — a RealWorld-style login that stamps state, fires a request, and folds the reply back in. The handlers live in a `.cljc` file so the JVM test can load them — same code, both platforms.

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
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (assoc db :session/status :error
                   :session/error  (:kind failure))}))
```

Both seams the test will use are already visible in that code. First, the handler **declares** the clock with `:rf.cofx/requires [:rf/time-ms]` and reads it as a delivered fact — a coeffect, an input the world hands the handler — instead of calling the host directly. That's what lets a test hand it an exact value. Second, the HTTP request is an **effect description** in the returned map, which is what lets a test answer it without a network. The model behind both is [Effects and coeffects](../concepts/effects-and-coeffects.md).

> **Where do `:value` and `:failure` come from?** A managed-HTTP reply is appended to the named `:on-success` / `:on-failure` event vector as a single map — `{:kind :success :value <decoded-body>}` on the success path, `{:kind :failure :failure <failure-map>}` on the failure path. That's why `:session/login-ok` destructures `:value` and `:session/login-failed` destructures `:failure`. The `:failure` map carries `:kind` (one of the eight `:rf.http/*` categories) plus kind-specific tags — `:status`, `:status-text`, `:body`, `:headers` for an HTTP 4xx; `:message` / `:cause` for a transport error; `:elapsed-ms` / `:limit-ms` for a timeout. See [014-HTTPRequests](../../../spec/014-HTTPRequests.md) for the full envelope and the closed failure-category set.

## The test

```clojure
;; test/my_app/session_test.clj
(ns my-app.session-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.http.test-support]   ;; canned-reply stubs — test-only, never in production requires
            [my-app.session]))             ;; loads the registrations

(deftest login-happy-path
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/with-managed-request-stubs
      {[:post "/api/users/login"]
       {:reply {:ok {:user {:email "alice@example.com" :token "jwt.abc"}}}}}
      (rf/dispatch-sync [:session/login {:email    "alice@example.com"
                                         :password "hunter2"}]
                        {:rf.cofx {:rf/time-ms 1781078400000}})
      (let [db (rf/app-db-value f)]
        (is (= :authed (:session/status db)))
        (is (= 1781078400000 (:session/attempted-at db)))
        (is (= "alice@example.com" (get-in db [:session/user :email])))))))

(deftest login-bad-credentials
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/with-managed-request-stubs
      {[:post "/api/users/login"]
       {:reply {:failure {:kind :rf.http/http-4xx :status 401}}}}
      (rf/dispatch-sync [:session/login {:email    "alice@example.com"
                                         :password "wrong"}])
      (is (= :error            (:session/status (rf/app-db-value f))))
      (is (= :rf.http/http-4xx (:session/error  (rf/app-db-value f)))))))
```

Run it with your project's JVM test runner (`clojure -M:test`). Both tests cover the full chain: request out, reply in, reply handler folds the result. Each runs in about a millisecond. Three pieces carry the recipe, so let's take them one at a time.

### Supply the facts: `{:rf.cofx {...}}`

`:rf/time-ms` is stamped onto every dispatch automatically, which is why the second test runs fine without ever mentioning it. But left alone its value would be the live clock, and an assertion on `:session/attempted-at` would flake. So the first test pins it. Facts supplied under `:rf.cofx` in the dispatch opts **win**: the runtime fills only what's missing and never overwrites. With the clock supplied there's no ambient time left to read, which means the test gives the same answer at 14:00 and at 23:59:59.

Delivery is declared-only — the clock included. A handler receives exactly the facts its `:rf.cofx/requires` names, flat in the coeffects map, and nothing else. So that `requires` vector is effectively the test's **fixture checklist**; you can read it off `(rf/handler-meta :event :session/login)`. This part trips people up, so it's worth saying plainly: a declared fact the runtime can't satisfy fails loudly with `:rf.error/missing-required-cofx`, never a silent `nil`. And note that this is the same `reg-event` as a pure state handler — declaring a world fact is metadata, not a different registration form.

> **The `:rf/time-ms` exception, and why generated facts are stricter.** `:rf/time-ms` is special: the router stamps it on every dispatch, so it's always satisfied even when you don't supply it. *Generated* recordable facts — a `reg-cofx` that mints a fresh id, a seeded random source, a read of browser location — are not stamped. Under a test frame's default **strict mint policy** (below), a handler that declares such a fact but for which the token carries no supplied value is **`:rf.error/missing-required-cofx`** — the generator does *not* fire a fresh per-run value. That's deliberate: a silently-minted id would make a green test that minted a *different* value than production will. Either supply the fact in `:rf.cofx`, or opt back into live generation with `{:rf.cofx/mint-policy :explicit-live}` (a per-call dispatch opt) when a fresh value per run is genuinely what you want.

### Answer the HTTP: canned replies by method + URL

`with-managed-request-stubs` (re-exported on `re-frame.core` from `re-frame.http.test-support`) takes a route map of `[method url]` → reply. For the duration of its body it answers every `:rf.http/managed` description that matches a route. `{:reply {:ok value}}` synthesises the canonical success envelope; `{:reply {:failure {:kind ... :status ...}}}` synthesises the canonical failure. The point is that the synthesised reply is the same canonical envelope a live request produces, and it rides the same dispatch path — so your reply handler can't tell the difference, which is precisely why the test proves something real. The reply lands inside the same `dispatch-sync` drain, so the assertion on the next line sees it.

Several routes coexist in one table — list each `[method url]` the cascade fires, success or failure, and each `:rf.http/managed` invocation is matched against its `:request :method` + `:request :url`:

```clojure
(rf/with-managed-request-stubs
  {[:get  "/api/profiles/alice"] {:reply {:ok {:profile {:username "alice"}}}}
   [:post "/api/articles"]       {:reply {:ok {:article {:slug "hello"}}}}
   [:del  "/api/articles/old"]   {:reply {:failure {:kind :rf.http/http-4xx :status 403}}}}
  ;; ... dispatch the events whose handlers fire those three requests ...
  )
```

A request that matches no route in the table is unanswered — the cascade hangs at that effect rather than silently passing, which is the loud failure you want. So the table is also a coverage check: it must name every request the path under test fires.

> **Coming from MSW?** The route map is your request-handler table — minus the service worker, because the request is intercepted as data before anything touches a network stack.

#### Observing the `:pending` state before the reply lands

Both tests above assert the *settled* state — the reply has already folded in by the time the assertion runs, so they never see `:session/status :pending`. If the in-flight state is itself worth a test (a spinner, a disabled submit button), defer the canned reply with `:after-ms`. The reply then rides a framework-native `:dispatch-later` tick instead of landing in the same drain, so the `:pending` state is observable in between:

```clojure
(deftest login-shows-pending-then-authed
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/with-managed-request-stubs
      {[:post "/api/users/login"]
       {:reply {:ok {:user {:email "alice@example.com"}}} :after-ms 20}}
      (rf/dispatch-sync [:session/login {:email "alice@example.com" :password "x"}]
                        {:rf.cofx {:rf/time-ms 1781078400000}})
      ;; The request fired but the reply hasn't landed yet — :pending is observable.
      (is (= :pending (:session/status (rf/app-db-value f))))
      ;; Wait for the deferred reply to settle, then assert the final state.
      (ts/poll-until #(= :authed (:session/status (rf/app-db-value f))))
      (is (= :authed (:session/status (rf/app-db-value f)))))))
```

`ts/poll-until` (from `re-frame.test-support`) is the **settle** primitive — it polls a predicate against a bounded deadline (defaults: `:timeout-ms 2000`, `:interval-ms 5`) and fails fast if the condition never holds, so a genuinely stuck cascade surfaces as a timeout rather than a hang. On the JVM it's synchronous and returns the truthy value; on CLJS it returns a `js/Promise` you compose under `cljs.test/async`. Reach for it whenever a reply or a scheduled event drains *past* `dispatch-sync` (a deferred reply, a machine `:after` transition, a `:dispatch-later`). Do **not** reach for it to wait out a timer window — a grace period, a debounce — that's a `Thread/sleep` whose duration *is* the contract; annotate it `;; Timer-semantics sleep: ...` so audits leave it alone.

### Redirect anything: `:fx-overrides`

The stub table is sugar over a more general seam. A per-dispatch `:fx-overrides` map redirects any effect id for that one dispatch — you can point it at a function, or at another registered effect:

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

This is redirect-not-mock in a single frame. The override receives the **exact args map the handler built** — the same data production would interpret — so you assert on the request without ever performing it. Nothing about the handler was faked; only the answerer changed. The same seam silences a logger, captures your own custom effects, or swaps in `:rf.http/managed-canned-success` by keyword (the framework-shipped success stub, the value form `with-managed-request-stubs` ultimately routes to):

```clojure
;; Redirect to the framework-shipped canned-success stub by keyword.
(rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                  {:rf.cofx       {:rf/time-ms 0}
                   :fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
;; The success stub synthesises a {:kind :success :value {:stubbed true}} reply
;; (override the value by supplying :value in the args map the handler builds).
```

> **The override fn takes two args.** An `:fx-overrides` value is either another registered fx-id (a keyword) or a function `(fn [frame-ctx args] ...)`. The first arg is the frame context; the second is the **effect's args map** — for `:rf.http/managed` that's `{:request {...} :on-success [...] :on-failure [...]}`. Returning a value is fine but ignored; the override is run for its capture or its (stubbed) effect, exactly like an ordinary `reg-fx` handler. The `_` prefixes above are just "I'm not reading this arg."

> **Frames isolate `app-db`, not registrations.** A fresh frame gets its own state and its own queue, but handlers live in a *process-global* registry — registering `:session/login` registers it for everyone. If your tests `(rf/reg-event ...)` in their bodies rather than requiring app namespaces, add `(use-fixtures :each (ts/make-reset-runtime-fixture))` — from `re-frame.test-support` — once per file, so one test's registrations can't leak into the next. Requiring `my-app.session` (as the tests above do) sidesteps the issue entirely: those registrations are stable for the whole run.

### The `:test` preset — deterministic defaults in one key

The two seams above — redirect HTTP to a stub, make generated facts strict — are exactly what most test frames want by default. Rather than spell them out per test, declare the intent with `{:preset :test}` and the frame mints with the deterministic defaults:

```clojure
;; A test frame that never reaches the network and fails loud on an
;; unsupplied generated cofx — :preset is a record-config key, so it rides
;; the advanced record-config constructor, not the bare object form.
(rf/with-new-frame [f (re-frame.frame/make-frame
                        {:preset :test
                         :initial-events [[:session/init]]})]
  (rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                    {:rf.cofx {:rf/time-ms 0}})
  (is (= :authed (:session/status (rf/app-db-value f)))))
```

The preset expands to three fixed entries: `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` (every `:rf.http/managed` is redirected to its canned-success stub, so a test frame can never accidentally reach the network), `:drain-depth 100` (the framework default, surfaced so tooling reads "this is a test frame"), and `:rf.cofx/mint-policy :strict` (the strict-mint behaviour described above). Your own keys win over the preset expansion, so you can still pin a different HTTP stub or opt into `:explicit-live` per dispatch. Use the preset when you want the defaults everywhere; reach for the explicit `with-managed-request-stubs` table when a test needs route-by-route control over the replies.

## Asserting on what would dispatch — without running the cascade

Sometimes the property under test is not the settled state but *which event the handler decided to fire next*. `:dispatch` is itself a reserved fx, and it lives in the **overridable** tier — so a per-call `:fx-overrides {:dispatch ...}` captures the queued event vector instead of running it, halting the cascade exactly one step in:

```clojure
(deftest login-success-fires-redirect
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [dispatched (atom [])]
      (rf/with-managed-request-stubs
        {[:post "/api/users/login"] {:reply {:ok {:user {:email "a@b.c"}}}}}
        (rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                          {:rf.cofx       {:rf/time-ms 0}
                           :fx-overrides {:dispatch (fn [_ ev] (swap! dispatched conj ev))}})
        ;; The reply handler tried to (re-)dispatch; we captured it instead of running it.
        (is (some #(= :nav/goto (first %)) @dispatched))))))
```

> **Scope a `:dispatch` override per-call, never per-frame.** Placed in a frame's config, a `:dispatch` override is re-merged into the envelope on *every* dispatch routed to that frame for its whole lifetime — including framework-internal traffic (machine actor messages, router internals, HTTP reply settles). That silently re-routes events the test never meant to touch. The per-call form above scopes the capture to the single event you're asserting on.

> **State-installing fxs can't be stubbed — and that's the point.** `:dispatch` and `:dispatch-later` are overridable, but the **state-installing** reserved fxs — `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow` — are **hard-rejected**. An override targeting one is ignored: the runtime emits `:rf.error/reserved-fx-override` and runs the real body. Stubbing them would leave the frame's runtime-db inconsistent and break behaviour far from the override site (a spawned actor whose snapshot was never installed → every later actor dispatch is a no-such-handler error). To assert on *those* operations, drive the real fx and read the resulting runtime-db state directly.

## Silencing noise — `:interceptor-overrides`

A logging or analytics interceptor that fires on every event will flood the test output. Remove it for the test frame with `:interceptor-overrides`, keyed by the interceptor's registered **reference** with a value of `nil` to drop it:

```clojure
(rf/reg-frame :test/quiet
  {:initial-events        [[:session/init]]
   :interceptor-overrides {:my-app/request-logger nil}})   ;; nil removes the interceptor
```

Keys are interceptor *references* — a bare keyword matches that registered interceptor; a parameterized `[id arg]` 2-vector matches the exact factory-built reference (so `[:rf.interceptor/path [:cart]]` is matched by its full reference, not by the bare id). The value is either `nil` (remove) or another registered reference (replace). Per-call overrides in the `dispatch-sync` opts win over per-frame on a key conflict — same precedence rule as `:rf.cofx` and `:fx-overrides`.

## Replay a bug as a regression test

A user reports: "I favorited an article, then unfavorited it, and the count stuck." Here's the thing — that description **is an event-ledger excerpt**. Because `app-db` is the fold of the events, a fresh frame fed the same rows lands in the same state. If Xray was open when it happened, don't reconstruct from prose; read the exact event rows (and each one's `:rf.cofx` facts) off the epoch ledger and paste them in. The test is just that excerpt replayed with the right answer pinned:

```clojure
(ns my-app.regression-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.articles]))

(deftest issue-217-unfavorite-leaves-count-stale
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:app/init])   ;; seed via a setup dispatch (or :initial-events on make-frame)
    (ts/dispatch-sequence [[:article/loaded {:slug "ten-tips" :favorites-count 0}]
                           [:article/favorite "ten-tips"]
                           [:article/unfavorite "ten-tips"]])
    ;; assert what the fold SHOULD produce
    (is (= 0 (get-in (rf/app-db-value f)
                     [:articles "ten-tips" :favorites-count])))))
```

`ts/dispatch-sequence` runs the rows through `dispatch-sync` in order and returns the final `app-db`. A row whose handler declares facts gets its own `dispatch-sync` with the recorded `:rf.cofx` supplied — replay means recorded inputs, never fresh ones. Today the replay reproduces the bug (red); fix the handler and the same replay proves the fix (green). The report has become a permanent regression guard that can't flake, because every input is pinned.

> **Capturing intermediate state on the way through.** When the bug is "the count was briefly wrong *between* two events", pass `:after-each` to capture state at each step. It's a `(fn [db ev] ...)` run after every event's drain settles:
>
> ```clojure
> (let [seen (atom [])]
>   (ts/dispatch-sequence [[:article/favorite "ten-tips"]
>                          [:article/unfavorite "ten-tips"]]
>                         {:after-each (fn [db ev]
>                                        (swap! seen conj
>                                               [(first ev)
>                                                (get-in db [:articles "ten-tips" :favorites-count])]))})
>   @seen)   ;; => [[:article/favorite 1] [:article/unfavorite 0]]
> ```

If you'd rather the assertion read like the ledger does, `re-frame.test-support` ships `assert-path-equals` — `clojure.test`-aware sugar for the path/value shape, reporting through `do-report` so the failure names the frame and path:

```clojure
(ts/assert-path-equals [:articles "ten-tips" :favorites-count] 0)
;; with a non-default frame:
(ts/assert-path-equals [:articles "ten-tips" :favorites-count] 0 {:frame f})
```

It shares its name root with the `:rf.assert/path-equals` event you'd use inside a Story `:script` block, so navigating between a `deftest` and a story variant needs no translation table. The companion `assert-db-equals` takes a whole expected `app-db` — handy in small fixtures where "the whole thing should equal this" is the natural shape. For a single inline check, the plain `(is (= ... (get-in ...)))` form reads fine; reach for the `assert-*` family when you're checking many path/value pairs in sequence.

## Co-located replies — when the request and its reply belong together

The login handler above named two separate reply handlers — `:on-success` and `:on-failure`. That's the recommended shape: each of the three concerns (issue, succeed, fail) is its own small handler and the failure path is impossible to overlook. But re-frame2 also supports a **co-located** form where you omit both, and the reply routes back to the *originating* event id with the payload merged under `:rf/reply`. One handler then branches on the sentinel to serve both roles:

```clojure
(rf/reg-event :profile/load
  (fn [{:keys [db]} [_ {:keys [username] :as msg}]]
    (if-let [{:keys [kind value failure]} (:rf/reply msg)]
      ;; reply role — :rf/reply is present
      (case kind
        :success {:db (assoc-in db [:profiles username] value)}
        :failure {:db (assoc-in db [:profiles username] {:error (:kind failure)})})
      ;; request role — no :rf/reply yet
      {:db (assoc-in db [:profiles username :loading?] true)
       :fx [[:rf.http/managed {:request {:method :get :url (str "/api/profiles/" username)}}]]})))
```

The test is unchanged in shape — stub the route, dispatch, assert the settled fold — because the co-located form rides the same canonical reply path:

```clojure
(deftest profile-load-co-located
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/with-managed-request-stubs
      {[:get "/api/profiles/alice"] {:reply {:ok {:bio "hello"}}}}
      (rf/dispatch-sync [:profile/load {:username "alice"}])
      (is (= {:bio "hello"} (get-in (rf/app-db-value f) [:profiles "alice"]))))))
```

> **The silenced failure is observable, not invisible.** A third form — `:on-failure nil` — silences the failure reply entirely (fire-and-forget, useful for telemetry beacons). To keep that honest, the runtime emits a one-shot dev-only `:rf.warning/failure-swallowed` trace the first time a *non-aborted* failure is dropped by `:on-failure nil`. So a test that silences a failure it didn't mean to silence still leaves a breadcrumb. (Aborted requests are excluded — a cancelled request that no longer wants its reply is correct-by-design silence.)

## Opts bend the edges, never the middle

The dispatch opts this page used aren't a grab-bag. They're the complete set of legal bends, and they share a boundary:

| Opt | Edge |
|---|---|
| `:frame` | **where** the event runs |
| `:rf.cofx` | **inputs** — what the world said |
| `:rf.cofx/mint-policy` | **inputs** — whether unsupplied generated facts mint live (`:explicit-live`) or fail loud (`:strict`) |
| `:fx-overrides` | **outputs** — who answers the effects |
| `:interceptor-overrides` | **chain** — which interceptors are removed or replaced for this dispatch |

What dispatch opts can never touch is the **middle**: the handler and its interceptor chain's *logic*. The middle is the program under test. And because the middle can't be bent, a green test means the production step function — fed those inputs, asked for those outputs — really behaves that way.

> **Going deeper.** The edges/middle split is the algebraic reason replay is trustworthy. An event handler is a pure step function `(db, event, coeffects) → (db', effects)`; `app-db` is the left fold of that step over the event ledger, seeded by the initial db. The dispatch opts only ever re-bind the *boundary* of the fold — its inputs (`:rf.cofx`) and the interpretation of its outputs (`:fx-overrides`) — never the step function itself. So "same program plus recorded inputs" is *definitionally* the same fold, and your cascade test is simply that fold's definition run on demand. A green replay isn't evidence the behaviour matches production; under this structure it *is* the production behaviour, evaluated with pinned inputs.

> **Where these surfaces live.** The dispatch opts, frame lifecycle, and drain semantics are owned by [Spec 002 — Frames](../../../spec/002-Frames.md); the canned-reply stubs and the `:reply` route shape by [Spec 014 — HTTP Requests](../../../spec/014-HTTPRequests.md); the fixture helpers (`dispatch-sequence`, `assert-path-equals`, `poll-until`, `make-reset-runtime-fixture`) by [Spec 008 — Testing](../../../spec/008-Testing.md). `with-managed-request-stubs` and the `:rf.http/managed-canned-*` stubs ship in `re-frame.http.test-support` and must never appear in a production require.
