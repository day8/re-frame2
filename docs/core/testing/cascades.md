# Test a full cascade

You have an [event handler](../glossary.md#event-handler) that does real work. It writes some state, fires an HTTP request, gets the reply back (which arrives as *another* event), and that reply handler writes more state and maybe kicks off still more events. That whole journey is one [**event cascade**](../glossary.md#event-cascade) — the fixed, ordered run a single [dispatch](../glossary.md#dispatch) sets off: handler → [effect map](../glossary.md#effect-map) → effects → [subscriptions](../glossary.md#subscription) → [view](../glossary.md#view). And because effects can dispatch follow-up events, those run their own cascades inside the same drain.

What you want to test is that journey end to end: an event goes in, the queue [drains to a fixed point](../glossary.md#drain--run-to-completion), and the final committed [`app-db`](../glossary.md#app-db) comes out the other side. This recipe tests exactly that, on the JVM. No browser, no mock library, milliseconds per test.

The trick that makes it fast is one idea: **you don't mock, you redirect.** The handler under test runs unmodified and produces the same [effect](../glossary.md#effect) data it produces in production — an effect is just a *description* of work, returned as data. The test changes only *who answers* that effect. No service worker, no patched `fetch`, no module-mock hoisting.

??? info "Coming from MSW?"

    Your closest anchor is **Mock Service Worker**. With MSW you don't mock your own modules; you intercept at the network boundary and answer with canned responses, so the code under test runs unmodified. re-frame2 keeps that idea and moves the boundary *earlier*. An effect is data the handler returns — the request is a description before it's ever a connection — so the test never touches network traffic at all. It redirects the effect's id to a different answerer for one dispatch, and that's it.

## The shape of every cascade test

Start with the simplest possible version. Every cascade test is the same three moves: a fresh [frame](../glossary.md#frame), a [`dispatch-sync`](../glossary.md#dispatch-sync), an assertion against the frame's `app-db`. (A frame is one isolated, running instance of your app — its own `app-db`, its own event queue.)

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _] {:db (update db :count (fnil inc 0))}))

(deftest counter-walk
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:counter/inc])
    (rf/dispatch-sync [:counter/inc])
    (is (= 2 (:count (rf/app-db-value f))))))
```

`with-new-frame` gives the test its own isolated frame and destroys it on exit (it's modelled on `with-open`), so nothing leaks between tests. `dispatch-sync` does the heavy lifting: it doesn't return until the cascade has fully settled. The event runs, every follow-up `:dispatch` its handlers queue runs, and (as you'll see below) every stubbed HTTP reply runs too — all before the call returns. That's [**run-to-completion**](../glossary.md#drain--run-to-completion): the runtime drains the whole queue to a fixed point before subscriptions recompute and views render, and `dispatch-sync` is run-to-completion you can wait on. So the assertion on the next line reads committed state. No `act()`, no fake timer to advance, nothing to `await`.

If instead you want to test one handler as the pure function it is, see [Test an event handler](event-handlers.md). This page is for when the interesting behaviour is the chain itself.

??? info "From re-frame v1"

    `dispatch-sync` drains the follow-up dispatches too, so the `wait-for` choreography from the v1 test library is gone — and there's no `run-test-sync` shim, because `dispatch-sync` is already settle-by-default, so the v1 macro was pure migration tax. Inline your `dispatch-sync` calls under a `make-reset-runtime-fixture` and the body reads the same.

!!! note "`with-new-frame` vs `with-frame` — the argument shape tells you which"

    These are siblings, and the macro name telegraphs the intent. `with-new-frame [f expr]` takes a **vector** — it evaluates `expr` (typically `(rf/make-frame {})`), binds the result, runs the body, and *destroys* the frame on exit. `with-frame :some-id` takes a **keyword** — it pins to a frame that already exists (registered via `reg-frame`, or created earlier) and does *not* create or destroy it. Pass the wrong argument shape and the macro rejects it at compile time (`:rf.error/with-new-frame-keyword-form` / `:rf.error/with-frame-vector-form`), so you can't accidentally leak a frame by pinning when you meant to bracket. Reach for `with-new-frame` for per-test fixtures; reach for `with-frame` for a fixture shared across several `deftest`s.

## A real cascade: the code under test

The counter was a warm-up. Now a real chain — a RealWorld-style login that stamps state, fires a request, and folds the reply back in. The handlers live in a `.cljc` file. (Clojure has three source extensions: `.clj` compiles for the JVM, `.cljs` for the browser, and `.cljc` for *both*. Putting the handlers in `.cljc` means this one file loads in the browser at runtime **and** on the JVM under test — same code, no duplication.)

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

Two *seams* are already visible in that code — a "seam" being a spot where the test can step in and substitute a value without editing the handler. First, the handler **declares** the clock with `:rf.cofx/requires [:rf/time-ms]` and reads it as a delivered fact — a [coeffect](../glossary.md#coeffect), a declared input the framework injects — instead of calling the host clock directly. That's the seam that lets a test hand it an exact value. Second, the HTTP request is an **effect** in the returned effect map — a described side-effect, data, not a live connection — which is the seam that lets a test answer it without a network. Both seams are the same idea: the world arrives as data (coeffects) and leaves as data (effects), the model owned by [Effects and coeffects](../concepts/effects-and-coeffects.md).

!!! note "Where do `:value` and `:failure` come from?"

    A managed-HTTP reply is the [uniform reply](../../async/http.md) every async surface uses — appended to the `:on-success` / `:on-failure` event vector as a single map: `{:kind :success :value <decoded-body>}` or `{:kind :failure :failure <failure-map>}`. That's why `:session/login-ok` destructures `:value` and `:session/login-failed` destructures `:failure`, whose `:kind` is one of the eight closed `:rf.http/*` categories. The full envelope and that closed category set live in [Managed HTTP](../../async/http.md).

## The test

```clojure
;; test/my_app/session_test.clj
(ns my-app.session-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.http.test-support]   ;; canned-reply stubs — test-only, never in production requires
            [re-frame.test-support :as ts]
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

`:rf/time-ms` is stamped onto every dispatch automatically, which is why the second test runs fine without ever mentioning it. But left alone its value is the live clock, and an assertion on `:session/attempted-at` would flake. So the first test pins it. Facts supplied under `:rf.cofx` in the dispatch opts **win**: the runtime fills only what's missing and never overwrites. With the clock supplied there's no ambient time left to read, so the test gives the same answer at 14:00 and at 23:59:59.

A handler only ever receives the facts it asked for — exactly the ones its `:rf.cofx/requires` vector names, handed over flat in the coeffects map, and nothing else, the clock included. So that `requires` vector is effectively the test's **fixture checklist**; you can read it off `(rf/handler-meta :event :session/login)`. This part trips people up, so it's worth saying plainly: a declared fact the runtime can't satisfy fails loudly with `:rf.error/missing-required-cofx`, never a silent `nil`. And note this is the *same* `reg-event` as a pure state handler — declaring a coeffect is metadata, not a different registration form.

!!! note "Why generated facts are stricter than the clock"

    `:rf/time-ms` is special: the router stamps it on every dispatch, so it's always satisfied even when you don't supply it. *Generated* [recordable facts](../glossary.md#recordable-vs-ambient-coeffects) — a `reg-cofx` that mints a fresh id, a seeded random source, a read of browser location — are not stamped. A `:preset :test` frame defaults to a **strict mint policy** ("mint" = a generator producing a fresh value; "strict" = it refuses to do so silently); a plain `(rf/make-frame {})` rides the router's `:live` default, which quietly mints. Under `:strict`, a handler that declares such a fact for which the dispatch carried no supplied value fails with **`:rf.error/missing-required-cofx`** — the generator does *not* fire a fresh per-run value. That's deliberate: a silently-minted id would make a green test that minted a *different* value than production will. Either supply the fact in `:rf.cofx`, or opt back into live generation with `{:rf.cofx/mint-policy :explicit-live}` (a per-call dispatch opt) when a fresh value per run is genuinely what you want.

### Answer the HTTP: canned replies by method + URL

`with-managed-request-stubs` (re-exported on `re-frame.core` from `re-frame.http.test-support`) takes a route map of `[method url]` → reply. For the duration of its body it answers every `:rf.http/managed` description that matches a route. `{:reply {:ok value}}` synthesises the canonical success envelope; `{:reply {:failure {:kind ... :status ...}}}` synthesises the canonical failure. The point: the synthesised reply is the *same* canonical envelope a live request produces, and it rides the *same* dispatch path — so your reply handler can't tell the difference, which is precisely why the test proves something real. The reply lands inside the same `dispatch-sync` drain, so the assertion on the next line sees it.

Several routes coexist in one table — list each `[method url]` the cascade fires, success or failure, and each `:rf.http/managed` invocation is matched against its `:request :method` + `:request :url`:

```clojure
(rf/with-managed-request-stubs
  {[:get    "/api/profiles/alice"] {:reply {:ok {:profile {:username "alice"}}}}
   [:post   "/api/articles"]       {:reply {:ok {:article {:slug "hello"}}}}
   [:delete "/api/articles/old"]   {:reply {:failure {:kind :rf.http/http-4xx :status 403}}}}
  ;; ... dispatch the events whose handlers fire those three requests ...
  )
```

A request that matches no route in the table is answered with a *synthesized failure* — kind `:rf.http/transport`, tagged `"no stub matched"` along with the offending method and URL — riding the normal `:on-failure` path. Nothing hangs and nothing silently passes: the miss folds into your failure handler's state, where the next assertion catches it. So the table is also a coverage check: it must name every request the path under test fires.

??? info "Coming from MSW?"

    The route map is your request-handler table — minus the service worker, because the request is intercepted as data before anything touches a network stack.

#### Observing the `:pending` state before the reply lands

Both tests above assert the *settled* state — the reply has already folded in by the time the assertion runs, so they never see `:session/status :pending`. If the in-flight state is itself worth a test (a spinner, a disabled submit button), defer the canned reply with `:after-ms` — a key the framework-shipped canned stubs read off their *args map*. The stub table has no delay knob, so this is the one case that reaches under it: redirect `:rf.http/managed` to a small wrapper that adds `:after-ms` (and the reply `:value`), then delegates to the registered canned-success handler. The deferred reply rides a framework-native `:dispatch-later` tick instead of landing in the same drain, so the `:pending` state is observable in between:

```clojure
(deftest login-shows-pending-then-authed
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [canned (:handler-fn (rf/handler-meta :fx :rf.http/managed-canned-success))]
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

That last test reaches for `ts/poll-until` — `ts` is the conventional alias for `re-frame.test-support`, the test-only helper namespace already in this page's require block. `poll-until` is the **settle** primitive: it polls a predicate against a bounded deadline (defaults `:timeout-ms 2000`, `:interval-ms 5`) and fails fast if the condition never holds, so a genuinely stuck cascade surfaces as a timeout rather than a hang. On the JVM it's synchronous and returns the truthy value (and on timeout throws an `ex-info` carrying `:rf.error/poll-until-timeout`, so you can branch on the discriminator); on CLJS it returns a `js/Promise` you compose under `cljs.test/async` — resolving with the value, rejecting on timeout. The optional `:label` rides into the timeout message, so a failing poll names *what* it was waiting for instead of a bare deadline. Reach for it whenever a reply or a scheduled event drains *past* `dispatch-sync` — a deferred reply, a machine `:after` transition, a `:dispatch-later`.

!!! warning "Gotcha — `poll-until` is for *settles*, not *windows*"

    Don't reach for it to wait out a timer window (a grace period, a debounce). That's a `Thread/sleep` whose duration *is* the contract — annotate it `;; Timer-semantics sleep: ...` so audits leave it alone. `poll-until` waits for a state change to *appear*; a timer sleep proves something does or doesn't happen *within* a fixed window. Different jobs.

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

This is redirect-not-mock in a single frame. The override receives the **exact args map the handler built** — the same data production would interpret — so you assert on the request without ever performing it. Nothing about the handler was faked; only the answerer changed. The same seam silences a logger, captures your own custom effects, or swaps in `:rf.http/managed-canned-success` by keyword (the framework-shipped success stub — it shares the canned-reply machinery `with-managed-request-stubs` runs under its route table):

```clojure
;; Redirect to the framework-shipped canned-success stub by keyword.
(rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                  {:rf.cofx       {:rf/time-ms 0}
                   :fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})
;; The success stub synthesises a {:kind :success :value {:stubbed true}} reply
;; (override the value by supplying :value in the args map the handler builds).
```

!!! note "The override fn takes two args"

    An `:fx-overrides` value is either another registered fx-id (a keyword) or a function `(fn [frame-ctx args] ...)`. The first arg is the frame context; the second is the **effect's args map** — for `:rf.http/managed` that's `{:request {...} :on-success [...] :on-failure [...]}`. Returning a value is fine but ignored; the override runs for its capture or its (stubbed) effect, exactly like an ordinary `reg-fx` handler. The `_` prefixes above are just "I'm not reading this arg."

!!! note "Testing your own `reg-fx`"

    The same two-arg shape means an effect handler you wrote is directly callable — hand it a stub frame context and a literal args map when its body has logic worth pinning. But keep that body thin on purpose: the more of an effect's behaviour lives in the *args your handlers build*, the more of it the capture test above already covers, and the less lives in the one function only a real host can prove.

!!! warning "Gotcha — frames isolate `app-db`, not registrations"

    A fresh frame gets its own state and its own queue, but handlers live in a *process-global* [registrar](../glossary.md#registrar) — registering `:session/login` registers it for everyone. If your tests `(rf/reg-event ...)` in their bodies rather than requiring app namespaces, add `(use-fixtures :each (ts/make-reset-runtime-fixture))` — from `re-frame.test-support` — once per file, so one test's registrations can't leak into the next. Requiring `my-app.session` (as the tests above do) sidesteps the issue entirely: those registrations are stable for the whole run.

### The `:test` preset — deterministic defaults in one key

The two seams above — redirect HTTP to a stub, make generated facts strict — are exactly what most test frames want by default. Rather than spell them out per test, declare the intent with `{:preset :test}` and the frame is born with the deterministic defaults already in place. It's the same `rf/make-frame` you've used all along; you just hand its config map one extra key:

```clojure
;; A test frame that never reaches the network and fails loud on an
;; unsupplied generated cofx — :preset bundles both behaviours.
(rf/with-new-frame [f (rf/make-frame
                        {:preset :test
                         :initial-events [[:session/init]]})]
  (rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                    {:rf.cofx {:rf/time-ms 0}})
  (is (= :authed (:session/status (rf/app-db-value f)))))
```

The preset expands to three fixed entries: `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` (every `:rf.http/managed` is redirected to its canned-success stub, so a test frame can never accidentally reach the network), `:drain-depth 100` (the cap on how many cascade steps a single dispatch will drain before it bails — set to the framework default here, surfaced explicitly so tooling can read "this is a test frame"), and `:rf.cofx/mint-policy :strict` (the strict-mint behaviour described above). One prerequisite: the canned stubs register only when `re-frame.http.test-support` is in your require block — it already is on this page; without it the preset's redirect target doesn't resolve. Your own keys win over the preset expansion, so you can still pin a different HTTP stub or opt into `:explicit-live` per dispatch. Use the preset when you want the defaults everywhere; reach for the explicit `with-managed-request-stubs` table when a test needs route-by-route control over the replies.

!!! warning "Gotcha — a runaway cascade halts at `:drain-depth`, it doesn't hang"

    If a handler re-dispatches itself (or a stubbed reply re-fires the same request that triggers it), the cascade would loop forever. It doesn't: the drain stops the moment it exceeds `:drain-depth` and emits a structured `:rf.error/drain-depth-exceeded` (tags `:depth`, `:queue-size`, `:last-event`). Crucially the unit of atomicity is the *event, not the drain* — every event that already settled keeps its committed `:db` and its own epoch; only the remaining queued events are discarded, and nothing is rolled back. So a test that trips the cap reads partly-advanced state, never a frozen one. If a `dispatch-sync` in a test ever seems to "never return", suspect an accidental dispatch loop and check your error listener for that category — the cap (`100`) means the loop surfaces within one drain.

## Asserting on what would dispatch — without running the cascade

Sometimes the property under test is not the settled state but *which event the handler decided to fire next*. `:dispatch` is itself a reserved fx, and it lives in the **overridable** tier — so a per-call `:fx-overrides {:dispatch ...}` captures the queued event vector instead of running it, halting the cascade exactly one step in:

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

One boundary to know before you lean on this: a per-call override rides the cascade it starts — the `:dispatch` / `:dispatch-later` children of that dispatch inherit it — but it does **not** survive an asynchronous hop. An HTTP reply is a *fresh* dispatch (tagged `:source :http`), so a per-call capture on the request's dispatch never sees the reply cascade's follow-ups. To capture across the hop, use the lexical seam: `rf/with-fx-overrides` wraps a body so every dispatch in its dynamic extent — replies included — carries the override. (That's the same seam `with-managed-request-stubs` uses to install its own routing.)

!!! warning "Gotcha — scope a `:dispatch` override per-call, never per-frame"

    Placed in a frame's config, a `:dispatch` override is re-merged into the envelope on *every* dispatch routed to that frame for its whole lifetime — including framework-internal traffic (machine actor messages, router internals, HTTP reply settles). That silently re-routes events the test never meant to touch. The per-call form above scopes the capture to the single event you're asserting on.

!!! note "State-installing fxs can't be stubbed — and that's the point"

    `:dispatch` and `:dispatch-later` are overridable, but the **state-installing** reserved fxs — `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`, `:rf.fx/clear-flow`, and the router's `:rf.route/with-nav-token` — are **hard-rejected**. An override targeting one is ignored: the runtime emits `:rf.error/reserved-fx-override` and runs the real body. Stubbing them would leave the frame's [runtime-db](../glossary.md#runtime-db) inconsistent and break behaviour far from the override site (a spawned actor whose snapshot was never installed → every later actor dispatch is a no-such-handler error). To assert on *those* operations, drive the real fx and read the resulting runtime-db state directly.

## Silencing noise — `:interceptor-overrides`

A logging or analytics [interceptor](../glossary.md#interceptor) that fires on every event will flood the test output. Remove it for the test frame with `:interceptor-overrides`, keyed by the interceptor's registered **reference** with a value of `nil` to drop it:

```clojure
(rf/reg-frame :test/quiet
  {:initial-events        [[:session/init]]
   :interceptor-overrides {:my-app/request-logger nil}})   ;; nil removes the interceptor
```

Keys are interceptor *references* — a bare keyword matches that registered interceptor; a parameterized `[id arg]` 2-vector matches the exact factory-built reference (so `[:rf.interceptor/path [:cart]]` is matched by its full reference, not by the bare id). The value is either `nil` (remove) or another registered reference (replace). Per-call overrides in the `dispatch-sync` opts win over per-frame on a key conflict — the same precedence rule as `:rf.cofx` and `:fx-overrides`.

## Replay a bug as a regression test

A user reports: "I favorited an article, then unfavorited it, and the count stuck." Here's the thing — that description is really just a *list of events in order*: favorite, then unfavorite. And in re-frame2 the current `app-db` is nothing more than what you get by starting from the initial state and applying each event in turn — so replaying that exact list rebuilds the exact state, bug and all. A bug report **is a replayable event sequence in disguise.**

If [Xray](../glossary.md#xray) — the dev inspector — was open when the bug happened, don't even reconstruct the sequence from prose: read the exact event rows (and each one's `:rf.cofx` facts) straight off its recorded [epoch](../glossary.md#epoch) ledger and paste them in. The test is just that sequence replayed with the right answer pinned:

```clojure
(ns my-app.regression-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.articles]))

(deftest issue-217-unfavorite-leaves-count-stale
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:app/init]]})]
    (ts/dispatch-sequence [[:article/loaded {:slug "ten-tips" :favorites-count 0}]
                           [:article/favorite "ten-tips"]
                           [:article/unfavorite "ten-tips"]])
    ;; assert what the fold SHOULD produce
    (is (= 0 (get-in (rf/app-db-value f)
                     [:articles "ten-tips" :favorites-count])))))
```

`ts/dispatch-sequence` runs the rows through `dispatch-sync` in order and returns the final `app-db`. This is the one place a multi-event run belongs in the test *body* rather than in `:initial-events`: the sequence itself is the subject — it *is* the bug — not setup for something else. (App boot, by contrast, is setup, which is why `[:app/init]` rides the frame's construction above.) Note the rows are bare event vectors: when a replayed event's handler declares facts, give that row its own `dispatch-sync` carrying the recorded `:rf.cofx` — replay means recorded inputs, never fresh ones. Today the replay reproduces the bug (red); fix the handler and the same replay proves the fix (green). The report has become a permanent regression guard that can't flake, because every input is pinned.

!!! note "Capturing intermediate state on the way through"

    When the bug is "the count was briefly wrong *between* two events", pass `:after-each` to capture state at each step. It's a `(fn [db ev] ...)` run after every event's drain settles:

    ```clojure
    (let [seen (atom [])]
      (ts/dispatch-sequence [[:article/favorite "ten-tips"]
                             [:article/unfavorite "ten-tips"]]
                            {:after-each (fn [db ev]
                                           (swap! seen conj
                                                  [(first ev)
                                                   (get-in db [:articles "ten-tips" :favorites-count])]))})
      @seen)   ;; => [[:article/favorite 1] [:article/unfavorite 0]]
    ```

If you'd rather the assertion read like the ledger does, `re-frame.test-support` ships `assert-path-equals` — `clojure.test`-aware sugar for the path/value shape, reporting through `do-report` so the failure names the frame and path:

```clojure
(ts/assert-path-equals [:articles "ten-tips" :favorites-count] 0)
;; with a non-default frame:
(ts/assert-path-equals [:articles "ten-tips" :favorites-count] 0 {:frame f})
```

It shares its name root with the `:rf.assert/path-equals` event you'd use inside a Story `:script` block, so navigating between a `deftest` and a story variant needs no translation table. The companion `assert-db-equals` takes a whole expected `app-db` — handy in small fixtures where "the whole thing should equal this" is the natural shape. For a single inline check, the plain `(is (= ... (get-in ...)))` form reads fine; reach for the `assert-*` family when you're checking many path/value pairs in sequence.

## Asserting on a derived value, not raw `app-db`

Every assertion so far has read a path straight out of `app-db`. But the cascade doesn't stop at the commit — the chain this page opened with runs on through [subscriptions](../glossary.md#subscription) to the [view](../glossary.md#view), and sometimes the value worth pinning is a *derived* one (a filtered list, a rolled-up count, a formatted label) rather than the raw state it's computed from. You could assert the inputs and trust the sub — but if the bug lives in the sub's own logic, that misses it. `compute-sub` lets you assert the derived value directly, still headless on the JVM:

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

`(rf/compute-sub query-v db)` runs the subscription's body against an `app-db` *value* and returns what it computes — no reactive cache, no Reagent, no adapter. `query-v` is the exact vector you'd hand `subscribe` (`[:sub-id arg1 arg2]`), so a parameterized sub passes its args the same way. Layered subs resolve transitively: a sub built on other subs computes its inputs first, depth-first, then itself — the whole derivation graph, evaluated as a pure function. The recommended shape is the one above: **build `db` with real events — `:initial-events` at construction, or a `dispatch-sync` in the body when the dispatch is itself under test — then `compute-sub` against the result**, so the sub is tested against state the real code paths produced. (You *can* pass a hand-rolled literal map as `db` for a trivial reader, but that decouples the test from how the state is actually built and rots silently when the shape moves — keep it for the rare case where the dispatch path adds nothing.)

!!! warning "Gotcha"

    `compute-sub` fails soft in two different ways. A sub body that throws emits `:rf.error/sub-exception` and the call returns `nil` — that one is listener-observable. But an input naming an *unregistered* sub silently computes to `nil` with no error record at all (`:rf.error/no-such-sub` belongs to the reactive `subscribe` path, which `compute-sub` never touches). So a green-looking `(is (nil? ...))` can be masking a thrown body *or* a typo'd input id — when a `compute-sub` assertion surprises you, check your error listener for `:rf.error/sub-exception` and re-read the input ids before trusting the `nil`.

!!! note "`compute-sub` is JVM-pure; it is not the live reactive value"

    It recomputes from the `db` you hand it every call, with no memoisation carried between calls — perfect for a deterministic assertion, but it is *not* a substitute for the running frame's cache. When you want "what the mounted frame would show *right now*" (cache-aware, after a live dispatch) rather than "what this sub computes over this `app-db` value", that's `subscribe-once` — same query vector, no db argument; it reads through the frame's cache, returns the value, and disposes its ref-count without leaving a live subscription behind.

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

!!! note "The silenced failure is observable, not invisible"

    A third form — `:on-failure nil` — silences the failure reply entirely (fire-and-forget, useful for telemetry beacons). To keep that honest, the runtime emits a one-shot dev-only `:rf.warning/failure-swallowed` trace the first time a *non-aborted* failure is dropped by `:on-failure nil`. So a test that silences a failure it didn't mean to silence still leaves a breadcrumb. (Aborted requests are excluded — a cancelled request that no longer wants its reply is correct-by-design silence.)

## Opts bend the edges, never the middle

The dispatch opts this page used aren't a grab-bag. Picture a handler as having an **edge** — the inputs flowing in and the effects flowing out — and a **middle** — the logic that turns one into the other. Every opt on the list touches only the edge; none can reach the middle. That's the whole pattern, and here's the complete set:

| Opt | Which edge it bends |
|---|---|
| `:frame` | **where** the event runs |
| `:rf.cofx` | **inputs** — what the world said |
| `:rf.cofx/mint-policy` | **inputs** — whether unsupplied generated facts mint live (`:explicit-live`) or fail loud (`:strict`) |
| `:fx-overrides` | **outputs** — who answers the effects |
| `:interceptor-overrides` | **chain** — which interceptors are removed or replaced for this dispatch |

That middle — the handler and its interceptor chain's *logic* — is the program under test, and no opt can bend it. That's exactly why the test is worth anything: because the logic can't be faked, a green test means the production step function, fed those inputs and asked for those outputs, really behaves that way.

??? note "Going deeper — the algebra under replay"

    The edges/middle split is the reason replay is trustworthy, and it's worth seeing once. An event handler is a pure step function `(db, event, coeffects) → (db', effects)`; `app-db` is the left fold of that step over the event ledger, seeded by the initial db. The dispatch opts only ever re-bind the *boundary* of the fold — its inputs (`:rf.cofx`) and the interpretation of its outputs (`:fx-overrides`) — never the step function itself. So "same program plus recorded inputs" is *definitionally* the same fold, and your cascade test is simply that fold's definition run on demand. A green replay isn't *evidence* the behaviour matches production; under this structure it *is* the production behaviour, evaluated with pinned inputs.

!!! note "Where these surfaces live"

    The dispatch opts, frame lifecycle, and drain semantics are covered in [Frames](../concepts/frames.md) and [Events and the cascade](../concepts/events-and-the-cascade.md); the reply envelope and canned-reply stubs in [Managed HTTP](../../async/http.md); the fixture helpers (`dispatch-sequence`, `assert-path-equals`, `poll-until`, `make-reset-runtime-fixture`) in the [test-support API reference](../../api/re-frame.test-support.md). `with-managed-request-stubs` and the `:rf.http/managed-canned-*` stubs ship in `re-frame.http.test-support` and must never appear in a production require.
