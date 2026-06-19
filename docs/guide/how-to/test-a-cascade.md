# Test a full cascade

You have an event handler — the function that decides how state changes in response to something happening — and it does real work. It stamps state, fires an HTTP request, gets the reply back as another event, and folds the result in along with follow-up dispatches. What you want to test is the whole journey: an event goes in, and settled `app-db` (your app's single state map) comes out the other side. This recipe tests that journey on the JVM. No browser, no mock library, milliseconds per test.

Your JavaScript anchor here is **MSW**. With MSW you don't mock your own modules; you intercept at the network boundary and answer with canned responses, so the code under test runs unmodified. re-frame2 keeps that idea and moves the boundary earlier. An effect — a description of work the handler wants done, returned as plain data — is just that: data the handler returns. The request is a description before it's ever a connection. Which means the test never touches network traffic at all. It redirects the effect's id to a different answerer for one dispatch, and that's it. No service worker, no patched `fetch`, no module-mock hoisting.

> **You don't mock, you redirect.** The handler under test runs unmodified and produces the same effect data it produces in production; the test changes only who answers it.

## The shape

Every cascade test is the same three moves: a fresh frame, a `dispatch-sync`, an assertion against the frame's `app-db`. (A frame is one isolated instance of your running app — its own `app-db`, its own event queue. A `dispatch` is how you send an event into it.)

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

> **Coming from re-frame v1?** `dispatch-sync` drains the follow-up dispatches too, so the `wait-for` choreography from the v1 test library is gone.

If instead you want to test one handler as the pure function it is, see [Test an event handler](test-an-event-handler.md). This page is for when the interesting behaviour is the chain itself.

## What's under test

We'll use a RealWorld-style login. The handlers live in a `.cljc` file so the JVM test can load them — same code, both platforms.

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

### Answer the HTTP: canned replies by method + URL

`with-managed-request-stubs` (from `re-frame.http.test-support`) takes a route map of `[method url]` → reply. For the body's extent, it answers every `:rf.http/managed` description that matches. `{:reply {:ok value}}` synthesises the canonical success envelope; `{:reply {:failure {:kind ... :status ...}}}` synthesises the canonical failure. The point is that the synthesised reply is the same canonical envelope a live request produces, and it rides the same dispatch path — so your reply handler can't tell the difference, which is precisely why the test proves something real. The reply lands inside the same `dispatch-sync` drain, so the assertion on the next line sees it.

> **Coming from MSW?** The route map is your request-handler table — minus the service worker, because the request is intercepted as data before anything touches a network stack.

### Redirect anything: `:fx-overrides`

The stub table is sugar over a more general seam. A per-dispatch `:fx-overrides` map redirects any effect id for that one dispatch — you can point it at a function, or at another registered effect:

```clojure
(deftest login-sends-the-right-request
  (rf/with-new-frame [f (rf/make-frame {})]
    (let [sent (atom nil)]
      (rf/dispatch-sync [:session/login {:email "a@b.c" :password "x"}]
                        {:fx-overrides {:rf.http/managed
                                        (fn [_frame-ctx args] (reset! sent args))}})
      (is (= :post              (get-in @sent [:request :method])))
      (is (= "/api/users/login" (get-in @sent [:request :url]))))))
```

This is redirect-not-mock in a single frame. The override receives the **exact args map the handler built** — the same data production would interpret — so you assert on the request without ever performing it. Nothing about the handler was faked; only the answerer changed. The same seam silences a logger, captures your own custom effects, or swaps in `:rf.http/managed-canned-success` by keyword.

!!! note "Frames isolate `app-db`, not registrations"

    Handlers live in a process-global registry. If your tests register handlers in their bodies (rather than requiring app namespaces), add `(use-fixtures :each (ts/make-reset-runtime-fixture))` — from `re-frame.test-support` — once per file, so one test's registrations can't leak into the next.

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
    (rf/dispatch-sync [:app/init])   ;; seed via a setup dispatch, not :on-create
    (ts/dispatch-sequence [[:article/loaded {:slug "ten-tips" :favorites-count 0}]
                           [:article/favorite "ten-tips"]
                           [:article/unfavorite "ten-tips"]])
    ;; assert what the fold SHOULD produce
    (is (= 0 (get-in (rf/app-db-value f)
                     [:articles "ten-tips" :favorites-count])))))
```

`ts/dispatch-sequence` runs the rows through `dispatch-sync` in order and returns the final `app-db`. A row whose handler declares facts gets its own `dispatch-sync` with the recorded `:rf.cofx` supplied — replay means recorded inputs, never fresh ones. Today the replay reproduces the bug (red); fix the handler and the same replay proves the fix (green). The report has become a permanent regression guard that can't flake, because every input is pinned.

## Opts bend the edges, never the middle

The dispatch opts this page used aren't a grab-bag. They're the complete set of legal bends, and they share a boundary:

| Opt | Edge |
|---|---|
| `:frame` | **where** the event runs |
| `:rf.cofx` | **inputs** — what the world said |
| `:fx-overrides` | **outputs** — who answers the effects |

What dispatch opts can never touch is the **middle**: the handler and its interceptor chain. The middle is the program under test. And because the middle can't be bent, a green test means the production step function — fed those inputs, asked for those outputs — really behaves that way. That's also why replay is trustworthy: same program plus recorded inputs is the framework's own definition of state, and your cascade test is simply that definition, run on demand.

---

**You can now:**

- drive a multi-event cascade with `dispatch-sync` and assert settled state on the next line — no browser, no `act()`, no fake timers
- pin a handler's declared world facts with `{:rf.cofx {:rf/time-ms ...}}` so time can never flake a test
- answer `:rf.http/managed` with canned replies routed by method + URL — and redirect any effect with `:fx-overrides`
- turn a bug report into a deterministic replay that becomes its own regression test
- say what dispatch opts may bend (frame, facts, effects — the edges) and what they never touch (the handler — the middle)
