# Part 5: test it, ship it

Your Conduit slice works. You watched it in the browser through [Parts 1–4](index.md): the feed loads, login guards the editor, favoriting [invalidates and refetches](04-mutations-and-invalidation.md). Now prove it. You'll write tests that run on the JVM in milliseconds, with no browser anywhere. Then you'll cut the production bundle and see exactly what ships — and, just as importantly, what *doesn't*.

> **Coming from Vitest, React Testing Library, and MSW?** That stack exists because in most React apps the logic is fused to rendering and the network. To test a decision you must render a component, so you stand up JSDOM. To test a fetch you interpose a mock service worker. To see the result you flush with `act()`. None of that has a counterpart here — not because re-frame2 ships better mocks, but because there is nothing to mock. Your handlers — the functions that decide what happens when an event fires — are pure. What they need from the world arrives as **values** (coeffects, the facts a handler reads in). What they do to the world leaves as **data** (effects, the changes a handler asks for). So a test supplies values and asserts on values. That's the whole game.

Here's the one sentence to carry out of this page:

**Supply data, don't swap mechanisms.**

You never patch `js/Date`. You never intercept `fetch`. You never replace a module. You hand the runtime the exact facts a handler declared it needs, then read the data it produced. Everything below is that sentence applied three times — to a handler, to a cascade, and to the release build.

## 1. Set up the JVM test runner

re-frame2's core is `.cljc`, which means the same artefact your browser build uses also loads on the JVM — where tests run in milliseconds with no DOM. Your registration namespaces from Parts 1–4 (events, subs, the auth machine, resources, mutations) contain no browser code, so they're portable too. If you created them as `.cljs`, rename them to `.cljc`. Views — the components that render app-db, your app's single state map, into UI — stay in `.cljs`, because they require React, and nothing on this page needs them.

Add a `:test` alias to your `deps.edn`. Your re-frame2 deps are already there from [the setup](index.md); the only addition is a runner:

```clojure
;; deps.edn
{:aliases
 {:test {:extra-paths ["test"]
         :extra-deps  {io.github.cognitect-labs/test-runner
                       {:git/tag "v0.5.1" :git/sha "dfb30dd"}}
         :main-opts   ["-m" "cognitect.test-runner"]}}}
```

Then create the test namespace. One fixture resets the whole runtime — registrar, frames, adapter — around every test, so nothing bleeds between them:

```clojure
;; test/conduit/auth_test.clj
(ns conduit.auth-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed]                        ;; the production HTTP fx
            [re-frame.http.test-support]                   ;; canned stubs — test-only
            [re-frame.substrate.plain-atom :as plain-atom] ;; the headless JVM substrate
            [re-frame.test-support :as ts]
            [conduit.auth]))                               ;; Part 3's registrations load here

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter       plain-atom/adapter
     :ambient-frame nil}))   ;; nil: our tests create their own frames
```

> **Keep the test stubs out of production.** Require `re-frame.http.test-support` from your test namespaces only — never from production or SSR code. That single rule is what guarantees the canned stubs can't ship to users: on the JVM they're absent from the production classpath, and under `:advanced` CLJS they're dead-code-eliminated from the module graph. The boundary is enforced by absence, not by a flag you have to remember to flip.

## 2. Test a handler: it's a function, so call it

Start with the smallest possible test. An event handler is a pure function from the coeffects (the facts handed in — `:db`, the current state, is one) and the event (a vector naming what happened) to an effects map. Give the simplest one a coeffects map and the event, and it returns `{:db next-state}`. Pull it out of the registry with `handler-meta` and call it:

```clojure
(deftest edit-field-updates-the-draft
  (let [handler (:handler-fn (rf/handler-meta :event :auth.login-form/edit-field))
        result  (handler {:db {:auth {:login-form {:draft {:email "" :password ""}}}}}
                         [:auth.login-form/edit-field :email "ada@example.com"])]
    (is (= "ada@example.com" (get-in result [:db :auth :login-form :draft :email])))))
```

No frame, no dispatch, no runtime. A coeffects map went in — `:db` holds the current state. You assert on the effects map that came out: its `:db` is the next state. That's a unit test in the truest sense — a function, an input, an output — and you didn't have to build a single piece of scaffolding to write it.

Now the interesting case: a handler that needs something from the world. Recall the boot handler from [Part 3](03-auth-and-forms.md). The saved JWT is a fact from outside the event, so it's registered as a **provided recordable coeffect** — a coeffect being one of those facts-from-the-world a handler reads in. The boot site reads localStorage once and stamps the value onto the dispatch. The handler **declares** it:

```clojure
;; src/conduit/auth.cljc — from Part 3 (abridged)
(rf/reg-cofx :auth.session/token
  {:recordable? true
   :provided?   true
   :doc "The saved JWT (or nil). Read once at the boot boundary and stamped
         onto the boot dispatch — never read ambiently by a handler."})

(rf/reg-event :auth/initialise
  {:rf.cofx/requires [:auth.session/token]}
  (fn [{:keys [db auth.session/token]} _]
    {:db (assoc db :auth {:user nil :token token})
     :fx [[:dispatch [:auth/flow [:auth/restore token]]]]}))
```

A handler that declares coeffects tests *exactly* the same way — it's the same `reg-event`, just with more facts in the input map. It's a function from a **coeffects map** (the facts coming in) to an effects map (the changes going out, where an effect is a piece of data describing something the runtime should do). Delivery is flat and declared-only, so you know exactly what the input map contains: `:db`, `:event`, plus precisely the facts in `:rf.cofx/requires`. So the fixture is a literal:

```clojure
(deftest initialise-seeds-the-session-and-kicks-restore
  (let [handler (:handler-fn (rf/handler-meta :event :auth/initialise))
        result  (handler {:db                 {}
                          :auth.session/token "jwt-fixture"}  ;; the literal coeffects map
                         [:auth/initialise])]
    (is (= "jwt-fixture" (get-in result [:db :auth :token])))
    (is (= [[:dispatch [:auth/flow [:auth/restore "jwt-fixture"]]]]
           (:fx result)))))
```

Look at the second assertion. The handler *did not* dispatch anything — dispatch being the act of sending an event into the runtime. It returned a *description* of what should happen, and you asserted on the description. The HTTP request behind login tests the same way: the handler returns data naming the request. No network is mocked because no network was involved.

The declaration also serves as the **fixture checklist**. Writing a test for an unfamiliar handler? Ask the registry what it must be fed:

```clojure
(:rf.cofx/requires (rf/handler-meta :event :auth/initialise))
;; => [:auth.session/token]
```

Whatever appears there is what your literal map (or your dispatch, below) supplies. Nothing else is delivered, so nothing else can secretly matter. The handler can't quietly reach for a global you forgot about, because there are no globals to reach for.

> **Freezing the clock.** Time is a declared fact like any other. There is no implicit clock. A handler that stamps a timestamp declares `:rf.cofx/requires [:rf/time-ms]` and reads it flat. A test supplies `{:rf/time-ms 1781078400123}` in the literal map, and the answer is identical at 14:00 and at 23:59:59. There's no `js/Date` to monkey-patch because the handler never reads one. (Every `reg-event` can declare requires — there's no second-class form that needing the world forces you to convert away from.)

> **Coming from re-frame v1?** The interceptor-based coeffect injection is gone: declaration moved into registration metadata, and tests supply values on the dispatch instead of stubbing handlers. The full delta is in [From re-frame v1](../25-from-re-frame-v1.md).

## 3. Test the cascade: one dispatch, end to end

Pure handler tests catch most bugs. But Part 3's login is a *flow*: an event hits the machine, the machine fires a managed HTTP request, the reply re-enters as another event, the session lands in `app-db` — your app's single state map. You want to test that as one piece, the way it actually runs. Drive a real dispatch through a real frame — an isolated, self-contained runtime instance — and bend only the edges:

```clojure
(deftest cold-boot-with-saved-token-lands-authed
  (rf/with-new-frame [f (re-frame.frame/make-frame {:preset :test})]  ;; :preset is record-config — rides re-frame.frame/make-frame, not rf/make-frame
    (rf/with-managed-request-stubs
      {[:get "https://api.realworld.io/api/user"]          ;; the URL Part 3's restore requests
       {:reply {:ok {:user {:username "ada"
                            :email    "ada@example.com"
                            :token    "jwt-fixture"}}}}}
      (rf/dispatch-sync [:auth/initialise]
                        {:rf.cofx {:auth.session/token "jwt-fixture"}}))
    (is (= :authed (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (= "ada"   (get-in (rf/app-db-value f) [:auth :user :username])))))
```

Four things do the work. Each is an **edge**, not a mechanism swap:

- **`with-new-frame`** gives the test its own isolated frame — created for the body, destroyed on the way out, success or exception. `{:preset :test}` declares intent. It expands to a frame-level `:fx-overrides` entry that redirects `:rf.http/managed` to its canned-success stub, so a request you forgot to stub can never escape to the wire. The same `:fx-overrides` map can ride a single dispatch — `(rf/dispatch-sync event {:fx-overrides {...}})` — when one test needs to redirect a different effect. Per-call wins over per-frame.
- **`{:rf.cofx {...}}` on the dispatch** supplies the declared fact. This is the same surface the boot site uses in production. The test isn't faking the coeffect machinery — it's *being* the boundary that stamps the value. Supplied values always win; the runtime fills only what's missing. Forget a declared provided fact and you get a loud `:rf.error/missing-required-cofx`, never a silent `nil`.
- **`with-managed-request-stubs`** routes `:rf.http/managed` by method + URL for the body's extent and synthesizes a real reply envelope. It's a *redirect*, not a mock. The exact request data your machine's action produced arrives at the stub, and the reply re-enters through the same `:on-success` path a live response would.
- **`dispatch-sync` drains to fixed point.** The entire cascade settles before the call returns — the machine transition, the stubbed request, the reply event, the session write. The assertions on the next lines read fully-committed state. No `act()`, no awaiting, no sleeps, no flake.

The unhappy path — the one your users will actually hit — is the same shape with a failure reply:

```clojure
(deftest wrong-password-shows-the-error
  (rf/with-new-frame [f (re-frame.frame/make-frame {:preset :test})]  ;; :preset is record-config — rides re-frame.frame/make-frame
    (rf/with-managed-request-stubs
      {[:post "https://api.realworld.io/api/users/login"]
       {:reply {:failure {:kind :rf.http/http-4xx :status 422}}}}
      (rf/dispatch-sync [:auth/flow [:auth/login {:email    "ada@example.com"
                                                  :password "wrong"}]]))
    (is (= :error (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (some?    (rf/compute-sub [:auth/error] (rf/frame-state-value f))))))
```

`compute-sub` runs a subscription's derivation — a subscription being a read-only, derived view of app-db — as a plain function against a state value. No reactive machinery, so it works headlessly on the JVM, including the machine-backed subs. These two tests are the pattern for *every* flow in your slice: stub the edges, drive one dispatch, assert on settled state. The complete test surface — every helper, fixture shape, and the exact JVM/CLJS boundary — is catalogued in [Spec 008 — Testing](../../../spec/008-Testing.md).

> **Client-only effects skip on the server.** Effects gated to the browser — like Part 3's localStorage persist fx, declared `:platforms #{:client}` — simply skip on the server platform, leaving a trace note behind. That's by design, not a gap in your test. Your assertion targets the durable outcome (the session in app-db), not the host write, so the same test is meaningful on the JVM where there's no `localStorage` to write to.

Run the suite:

```bash
clojure -M:test
# Ran 4 tests containing 6 assertions.
# 0 failures, 0 errors.
```

The whole run takes well under a second. **Try it:** break `:auth/initialise` — store the token under the wrong key — and run again. The pure test fails pointing at the exact map entry. There's no stack of rendering internals to dig through, because there was no rendering. Fix it, and notice the loop is fast enough to leave running on every save.

## 4. Ship it: the release build

Now cut the production bundle — same build id you've been running with `watch`:

```bash
npx shadow-cljs release app
```

`release` compiles with `:advanced` optimizations and `goog.DEBUG` set to `false`. That flag is the hinge of re-frame2's production story. Everything diagnostic is gated behind it, so the Closure compiler constant-folds the gate and dead-code-eliminates what's behind it. **What's gone from the file you just built:**

- **Every schema check.** The validations that screamed at you in dev compile out entirely. Zero hot-path cost — which is precisely *why* you could afford to write them everywhere. You don't ration safety to pay for speed; you get both.
- **The entire trace channel.** The epoch ledger you scrolled in Xray, the per-frame trace ring, every emit site. Not switched off — *absent*. Open Xray against the release build and there's nothing for it to attach to. That silence is your verification that the elision is real.

**What survives — because it isn't diagnostics:**

- **The causal channel.** Events, effect maps, and the `:rf.cofx` facts stamped on every dispatch. Recordable coeffects are durable causal data — part of how the app computes its state — so they ship unconditionally. Deleting them would delete the app.
- **The always-on error axis.** One tight, structured record per production-reachable failure. A handler exception reaches your error service with its frame and event-id attached, instead of surfacing as a bare `window.onerror`. It deliberately survives elision. It is the production observability surface.

That second survivor needs one piece of wiring before you deploy. Declare a sink on your frame's metadata (the `reg-frame` from Part 1) and register its function:

```clojure
;; add to the frame's metadata:
{:observability {:errors [{:sink              :conduit.sinks/error-reporter
                           :rf.egress/profile :rf.egress/off-box-observability}]}}

(rf/register-observability-sink! :conduit.sinks/error-reporter
  (fn [record]
    ;; the record arrives already projected — secrets show up as :rf/redacted
    (ship-to-your-error-service! record)))
```

The runtime projects each record through the frame's classification *before* your sink sees it, so the sink does no redaction of its own — it can't accidentally leak what the framework already scrubbed. The full recipe — choosing a backend, what the records contain — is [Report errors in production](../how-to/report-errors-in-production.md), and the dev/prod build knobs are [Configure dev and production builds](../how-to/configure-dev-and-prod.md).

> **Pre-alpha: no back-compat covenant yet.** re-frame2 is pre-alpha. The *shape* of what you ship is stable in the ways this page describes, but there is no back-compat covenant yet. Expect to track changes between releases.

---

That's the tutorial. You built a real app — pages, server data, auth, writes — and you just tested and shipped it.

**You can now:**

- test any handler by calling it with a literal coeffects map, reading `:rf.cofx/requires` off `handler-meta` as the fixture checklist
- drive a full cascade deterministically — facts supplied via `{:rf.cofx ...}`, HTTP answered by canned stubs, everything settled when `dispatch-sync` returns
- run the suite on the JVM in milliseconds, with per-test frame isolation and no DOM emulation
- cut a release bundle and state precisely what elided (schemas, the trace channel) and what survived (the causal channel, the always-on error axis)
- wire a production error sink that receives already-projected records
