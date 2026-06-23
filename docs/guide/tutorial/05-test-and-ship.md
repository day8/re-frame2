# Part 5: test it, ship it

Your Conduit slice works. You watched it in the browser through [Parts 1–4](index.md): the feed loads, login guards the editor, favoriting [invalidates and refetches](04-mutations-and-invalidation.md). Now prove it. You'll write tests that run on the JVM in milliseconds, with no browser anywhere. Then you'll cut the production bundle and see exactly what ships — and, just as importantly, what *doesn't*.

There's one sentence to carry out of this whole page:

**Supply data, don't swap mechanisms.**

You never patch `js/Date`. You never intercept `fetch`. You never replace a module. You hand the runtime the exact facts a handler declared it needs, then read the data it produced. Everything below is that one sentence applied across the slice — to a handler, a subscription, a view, a whole cascade, and finally the release build.

> **For JavaScript developers.** If your instinct is "where do the mocks go?", the answer is: there's nothing to mock. In most React apps the logic is fused to rendering and the network — to test a decision you render a component (so you stand up JSDOM), to test a fetch you interpose a mock service worker (MSW), to see the result you flush with `act()`. None of that has a counterpart here. Your handlers — the functions that decide what happens when an event fires — are pure. What they need from the world arrives as **values** (coeffects, the facts a handler reads in). What they do to the world leaves as **data** (effects, the changes a handler asks for). So a test supplies values and asserts on values. That's the whole game — not because re-frame2 ships better mocks, but because there is nothing to mock.

## 1. Set up the JVM test runner

A quick fact about Clojure file extensions, because the whole testing strategy rests on it. A `.cljs` file is ClojureScript — it compiles to JavaScript and runs in a browser. A `.clj` file is Clojure — it runs on the JVM. A `.cljc` file is *portable*: the same source compiles to both. re-frame2's core is `.cljc`, so the very artefact your browser build uses also loads on the JVM — where tests run in milliseconds with no browser and no DOM in sight.

That portability extends to your own code. Your registration namespaces from Parts 1–4 (events, subs, the auth machine, resources, mutations) contain no browser code, so they're portable too — if you created them as `.cljs`, rename them to `.cljc` so the JVM can load them. Only your views need to stay `.cljs`: views are the components that render app-db (your app's single state map) into UI, and rendering pulls in React, which is browser-only. Nothing on this page touches a view through React, so that's fine — none of these tests need a browser.

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

> **Gotcha. Keep the test stubs out of production.** Require `re-frame.http.test-support` from your test namespaces only — never from production or SSR code. That single rule is what guarantees the canned stubs can't ship to users: on the JVM they're absent from the production classpath, and under `:advanced` CLJS they're dead-code-eliminated from the module graph. The boundary is enforced by absence, not by a flag you have to remember to flip.

## 2. Test a handler: it's a function, so call it

Start with the smallest possible test. An event handler is a pure function. It takes two arguments — the **coeffects** (the facts handed in; `:db`, the current state, is the one you'll always see) and the **event** (a vector naming what happened, like `[:auth.login-form/edit-field :email "ada@…"]`) — and it returns an **effects map** describing what should change (most simply, `{:db next-state}`). Nothing else: no globals read, no side effects performed. So to test the simplest handler, hand it a coeffects map and an event and check what comes back. Pull the handler out of the registry with `handler-meta` and call it directly:

```clojure
(deftest edit-field-updates-the-draft
  (let [handler (:handler-fn (rf/handler-meta :event :auth.login-form/edit-field))
        result  (handler {:db {:auth {:login-form {:draft {:email "" :password ""}}}}}
                         [:auth.login-form/edit-field :email "ada@example.com"])]
    (is (= "ada@example.com" (get-in result [:db :auth :login-form :draft :email])))))
```

No frame, no dispatch, no runtime. A coeffects map went in — `:db` holds the current state. You assert on the effects map that came out: its `:db` is the next state. That's a unit test in the truest sense — a function, an input, an output — and you didn't have to build a single piece of scaffolding to write it.

Now the interesting case: a handler that needs something from the world. Recall the boot handler from [Part 3](03-auth-and-forms.md). The saved JWT (a token from a previous login, kept in `localStorage`) is a fact from outside the event — so it's a coeffect too, just one that doesn't arrive for free the way `:db` does. It's registered as a **provided recordable coeffect**: *provided* means the app supplies it explicitly at the boundary, *recordable* means it's durable causal data that survives into the trace and the release build. The boot site reads `localStorage` once and stamps the value onto the dispatch; the handler simply **declares** that it needs it:

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

A handler that declares coeffects tests *exactly* the same way — it's the same `reg-event`, just with more facts in the input map. Delivery is flat and declared-only, so you know exactly what the input map contains: `:db`, `:event`, plus precisely the facts in `:rf.cofx/requires`. So the fixture is a literal:

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

> **Gotcha. Freezing the clock.** Time is a declared fact like any other — there is no implicit clock. A handler that stamps a timestamp declares `:rf.cofx/requires [:rf/time-ms]` and reads it flat. A test supplies `{:rf/time-ms 1781078400123}` in the literal map, and the answer is identical at 14:00 and at 23:59:59. There's no `js/Date` to monkey-patch because the handler never reads one. (Every `reg-event` can declare requires — there's no second-class form that needing the world forces you to convert away from.)

> **From re-frame v1.** The interceptor-based coeffect injection is gone. Declaration moved into registration metadata (`:rf.cofx/requires`), and tests supply values on the dispatch instead of stubbing handlers or registering test cofx-injectors. The full delta is in [From re-frame v1](../25-from-re-frame-v1.md).

> **Going deeper.** Notice that the input map (coeffects) and the output map (effects) are both *plain data*, and the handler is a pure function between them. That makes a handler a morphism in the most boring, most powerful sense: testing it is exactly testing a function `f(input) = output` with no observation of effects on the side. The runtime's job is to *interpret* the returned effect data — your test simply skips the interpreter and inspects the description. This is the same separation a free monad buys you (build a description of a program as data, run it later), arrived at without any of the type machinery: the effect map *is* the description, and `dispatch` *is* the interpreter.

## 3. Test the cascade: one dispatch, end to end

Pure handler tests catch most bugs. But Part 3's login is a *flow*: an event hits the machine, the machine fires a managed HTTP request, the reply re-enters as another event, the session lands in `app-db`. You want to test that as one piece, the way it actually runs.

To do that you'll drive a real dispatch through a real **frame** — an isolated, self-contained runtime instance, the thing your app mounts into — and redirect only the handful of points where it touches the outside world. (Call those points the **edges**; you'll meet all four in a moment.) Here's the happy path: a cold boot that finds a saved token and lands the user authenticated.

```clojure
(deftest cold-boot-with-saved-token-lands-authed
  ;; make-frame here takes a config map, so we call re-frame.frame/make-frame
  ;; (the config-taking form) rather than the plain rf/make-frame used elsewhere.
  ;; :preset :test is one of its config keys — see the bullets below.
  (rf/with-new-frame [f (re-frame.frame/make-frame {:preset :test})]
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

Four things do the work — those four edges. Each one redirects a value at a boundary, not a mechanism the test swaps out:

- **`with-new-frame`** gives the test its own isolated frame — created for the body, destroyed on the way out, success or exception. `{:preset :test}` declares intent: it redirects `:rf.http/managed` to its canned-success stub, so a request you forgot to stub can never escape to the wire.
- **`{:rf.cofx {...}}` on the dispatch** supplies the declared fact. This is the same surface the boot site uses in production — the test isn't faking the coeffect machinery, it's *being* the boundary that stamps the value.
- **`with-managed-request-stubs`** routes `:rf.http/managed` by method + URL for the body's extent and synthesizes a real reply envelope. The exact request data your machine's action produced arrives at the stub, and the reply re-enters through the same `:on-success` path a live response would.
- **`dispatch-sync` drains to fixed point.** The entire cascade settles before the call returns — the machine transition, the stubbed request, the reply event, the session write. The assertions on the next lines read fully-committed state. No `act()`, no awaiting, no sleeps, no flake.

The unhappy path — the one your users will actually hit — is the same shape with a failure reply:

```clojure
(deftest wrong-password-shows-the-error
  (rf/with-new-frame [f (re-frame.frame/make-frame {:preset :test})]  ;; same config-taking make-frame as above
    (rf/with-managed-request-stubs
      {[:post "https://api.realworld.io/api/users/login"]
       {:reply {:failure {:kind :rf.http/http-4xx :status 422}}}}
      (rf/dispatch-sync [:auth/flow [:auth/login {:email    "ada@example.com"
                                                  :password "wrong"}]]))
    (is (= :error (rf/compute-sub [:auth/state] (rf/frame-state-value f))))
    (is (some?    (rf/compute-sub [:auth/error] (rf/frame-state-value f))))))
```

`compute-sub` runs a subscription's derivation — a subscription being a read-only, derived view of app-db — as a plain function against a state value. No reactive machinery, so it works headlessly on the JVM, including the machine-backed subs. These two tests are the pattern for *every* flow in your slice: stub the edges, drive one dispatch, assert on settled state.

> **Going deeper. Edges, not mocks.** Every one of those four moves redirects a *value at a boundary* rather than substituting a *mechanism*. `with-managed-request-stubs` is a `:fx-override` — it swaps where one effect-id resolves, leaving the request data and reply path identical. The same `:fx-overrides` map can ride a single dispatch — `(rf/dispatch-sync event {:fx-overrides {...}})` — when one test needs to redirect a different effect; per-call wins over per-frame. And `{:rf.cofx {...}}` doesn't fake the cofx system, it *is* the production stamping surface. Supplied values always win; the runtime fills only what's missing. Forget a declared provided fact and you get a loud `:rf.error/missing-required-cofx`, never a silent `nil`. The whole testing surface — every helper, fixture shape, and the exact JVM/CLJS boundary — is catalogued in [Spec 008 — Testing](../../../spec/008-Testing.md).

> **Gotcha. Client-only effects skip on the server.** Effects gated to the browser — like Part 3's localStorage persist fx, declared `:platforms #{:client}` — simply skip on the server platform, leaving a trace note behind. That's by design, not a gap in your test. Your assertion targets the durable outcome (the session in app-db), not the host write, so the same test is meaningful on the JVM where there's no `localStorage` to write to.

## 4. Test a subscription: compute it against a db

Your views read app-db through subscriptions — Part 3's `:auth.login-form/can-submit?`, the field-error visibility rule, the form's `dirty?` flag. A subscription is a pure derivation: `app-db` value in, derived value out. So you test it the same way you tested a handler — give it a value, read the value back — except a sub doesn't take its `db` as an argument the way a handler does. `compute-sub` supplies it:

```clojure
(deftest can-submit-flips-once-the-draft-is-filled
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:auth.login-form/initialise])      ;; seed the empty form
    (rf/dispatch-sync [:auth.login-form/edit-field :email    "ada@example.com"])
    (rf/dispatch-sync [:auth.login-form/edit-field :password "hunter2"])
    (is (= true (rf/compute-sub [:auth.login-form/can-submit?] (rf/app-db-value f))))))
```

`compute-sub` runs the sub's body against the supplied `db` and returns the result — no reactive cache, no Reagent, no JS runtime, fully JVM-runnable. Notice the shape: rather than hand-building an `app-db` map, you *dispatch the real events* that build the state, then read the resulting `app-db-value` and compute the sub against it. That's deliberate. The test exercises the sub against state produced by the same code paths the app uses, so when you reshape where the form lives, the events move, the sub moves, and this test keeps passing unmodified.

> **Gotcha. Two ways to supply the db, one of them rots.** For a trivial reader where the dispatch adds nothing, you *can* pass a literal map — `(compute-sub [:auth.login-form/can-submit?] {:auth {:login-form {:errors {} :status :idle}}})`. Reach for that escape hatch sparingly: a hand-rolled db shape silently rots when the real schema moves underneath it, whereas the dispatch-the-real-events form tracks the schema for free.

There's a partition wrinkle the cascade tests above already leaned on. They read `:auth/state` with `(rf/frame-state-value f)`, not `(rf/app-db-value f)`:

> **Gotcha. App-db subs vs machine-backed subs.** A frame holds state in *two* partitions: **app-db**, your application's data, and **runtime-db**, framework-managed state like machine snapshots and in-flight mutation status. Most subs read app-db, so `app-db-value` feeds them. But `:auth/state` is **machine-backed** — its value lives in runtime-db, so feeding it a bare `app-db-value` would find nothing. The rule is simple: app-db subs take `app-db-value`; subs that touch machine snapshots (or any runtime-db state, like the mutation status in section 5) take `frame-state-value`. `frame-state-value` returns *both* partitions as one projection — `{:rf.db/app … :rf.db/runtime …}` — and `compute-sub` reads whichever partition each sub belongs to. Since it always works, when in doubt reach for `frame-state-value`.

> **Going deeper. Layered subs come along for free.** That `:auth.login-form/can-submit?` sub is **layered** — it's defined `:<- [:auth.login-form/slice]`, so it reads through another sub. `compute-sub` resolves the chain transitively: it computes the input sub against `db` first, depth-first, then runs the outer body against that value — all without spinning up the cache. Same for a parametric `input-fn` sub. You test the top sub and the whole derivation underneath it comes along, exactly as the running app composes it. The cache the live app uses is an *optimisation* layered over this pure composition, not part of its meaning — which is precisely why you can drop it on the JVM and still get the right answer.

> **From re-frame v1. Where did `subscribe` go?** You never call `@(rf/subscribe …)` in these tests. `subscribe` needs a live reactive cache and an installed adapter — overhead for an assertion against a value. `compute-sub` is the pure, headless form. (If you genuinely want "what would the running frame see *right now*, cache and all", there's `rf/subscribe-once` — current value, synchronously, no live ratom left dangling — but for unit tests `compute-sub` is the right tool.)

## 5. Test the view, not just the state

Everything so far asserts on *state*. But two bugs live in the gap between correct state and a correct screen: the handler writes the right value, the sub computes it, and the view still reads the wrong path, or wires `:on-click` to dispatch into the wrong frame. State assertions stay green; the user sees a broken page. You catch both on the JVM — no JSDOM, no React, no `act()` — because a view-fn is just a function, and what it returns is just **hiccup**: plain Clojure vectors describing the markup (`[:button {…} "Save"]` is a `<button>`). Data you can walk and assert on, no rendering required.

To address a node from a test, give it a stable handle. The `testid` helper builds an attrs map carrying a `:data-testid` — a one-line change at the view's call site, and it dead-code-eliminates from production. Part 4's `favorite-button` grows one attribute:

```clojure
;; in the view (Part 4's favorite-button), tag the button:
[:button.btn.btn-outline-primary.btn-sm
 (th/testid "favorite-btn" {:type "button" :on-click #(dispatch [:ui/favorite slug favorited])})
 [:i.ion-heart] " " favoritesCount]
```

Now require the view helpers and walk the tree the view returns:

```clojure
;; add to the test ns:
;;   [re-frame.test-helpers :as th]

(deftest favorite-button-shows-the-count-and-clicks-into-this-frame
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:rf/set-db {:auth {:user {:username "ada"}}}])   ;; a user is signed in
    (let [article {:slug "x" :favorited false :favoritesCount 7}
          tree    (favorite-button {:article article})]                ;; call the view-fn directly
      ;; class-1 bug: does the button render the count it was handed?
      (is (= "  7" (th/text-content (th/find-by-testid tree "favorite-btn"))))
      ;; class-2 bug: invoking :on-click must dispatch into THIS frame —
      ;; the favorite mutation's instance comes alive only if it landed here.
      (th/invoke-handler (th/find-by-testid tree "favorite-btn") :on-click)
      (is (some? (rf/compute-sub [:rf.mutation/state {:instance [:favorite "x"]}]
                                 (rf/frame-state-value f)))))))
```

Three helpers from `re-frame.test-helpers` do the work, all pure walkers over hiccup data: `find-by-testid` locates the node carrying that `:data-testid`, `text-content` collects the string leaves under it (the heart glyph contributes nothing, so you read `"  7"` — the two spaces flanking `favoritesCount`), and `invoke-handler` calls a wired handler (`:on-click`, `:on-change`, …). The handler assertion is what catches the wrong-frame-dispatch bug: if the click fired into a sibling frame, *this* frame's mutation instance would never come alive and the second `is` would fail. (The mutation-state sub reads runtime-db, so it's computed against `frame-state-value` — same partition rule as section 4.) `find-by-testid` expands nested function components on the way down, so calling a parent view shows you the leaf hiccup the user actually sees.

> **For JavaScript developers.** `:data-testid` is the same convention React Testing Library leans on, and the helpers mirror its query/fire shape: `find-by-testid` ≈ `getByTestId`, `text-content` ≈ `textContent`, `invoke-handler` ≈ `fireEvent`. The difference is what's underneath — RTL queries a rendered DOM that JSDOM had to build; these helpers walk the hiccup *data* the view returned, so there is no DOM, no `render()`, and no `act()` to flush.

> **Two flavours of view test.** Reach for hiccup-walk (above) when you care about *structure* or *handlers* — which testid is present, which `:on-click` is wired. Reach for `rf/render-to-string` when you care about the rendered *markup* — "is the `<button>` disabled?", "does the `<h1>` carry the right class?". It emits the whole view to an HTML string, also JVM-runnable, also no DOM. Only tests that need real React mounting (a click firing through actual DOM listeners, scroll behaviour) need a CLJS runtime — and that's a small minority. The boundary is catalogued in [Spec 008 — Testing](../../../spec/008-Testing.md).

> **Going deeper. The `test-support` sugar.** The view-content tests pull `re-frame.test-helpers` (the view-tree axis); the event/sub/cascade tests pull `re-frame.test-support` for its sugar — `dispatch-sequence` to fire a vector of events in order, `assert-path-equals` and `assert-db-equals` for `clojure.test`-aware state assertions, `poll-until` for async settles whose result lands in state after `dispatch-sync` returns. Reach for them when the inline `(is (= …))` form gets repetitive; everything else composes from `dispatch-sync` / `app-db-value` / `compute-sub` directly. The full inventory — every helper, both test namespaces, the JVM/CLJS split — is in [Spec 008 — Testing](../../../spec/008-Testing.md), and there are focused recipes in [Test an event handler](../how-to/test-an-event-handler.md) and [Test a cascade](../how-to/test-a-cascade.md).

## 6. Reset between tests

One detail underwrites every test above: each runs against a clean runtime, with nothing bleeding in from the last. That's the fixture from step 1:

```clojure
(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter :ambient-frame nil}))
```

`make-reset-runtime-fixture` is the default `:each` fixture for re-frame2 suites. It snapshots the registrar and resets the per-process state — frames, flows, schemas, machine timers, in-flight HTTP, epoch history, trace listeners — around every test, then restores. It's a *factory*: it returns the fixture fn, which is why you call it (`(make-reset-runtime-fixture {…})`) rather than passing it bare. The reset machinery no-ops for any subsystem your suite doesn't touch, so a thin JVM suite that never pulls flows or schemas doesn't pay for resetting them — it's the cheap default for thin suites and the complete one for thick.

> **Going deeper. Two scopes of isolation, stacked.** The fixture resets the *process*; `with-new-frame` scopes one *frame's* lifetime to one test (created for the body, destroyed on exit — success or exception). Every cascade test above wraps both, belt-and-braces, so no frame, no registration, and no in-flight request can survive into the next test. If a test only registers a handful of handlers and never mounts an adapter or drives a long-lived frame, you can reach below the fixture to `with-fresh-registrar`, which brackets just the registrar around a single body.

Run the suite:

```bash
clojure -M:test
# Ran 6 tests containing 10 assertions.
# 0 failures, 0 errors.
```

The whole run takes well under a second. **Try it:** break `:auth/initialise` — store the token under the wrong key — and run again. The pure test fails pointing at the exact map entry. There's no stack of rendering internals to dig through, because there was no rendering. Fix it, and notice the loop is fast enough to leave running on every save.

## 7. Ship it: the release build

Now cut the production bundle — same build id you've been running with `watch`:

```bash
npx shadow-cljs release app
```

`release` compiles with `:advanced` optimizations and sets `goog.DEBUG` to `false`. `goog.DEBUG` is a compile-time boolean from the Google Closure compiler (the optimizer under shadow-cljs); in a release build it's a known-false constant, not a runtime variable. That single constant is the hinge of re-frame2's production story. Everything diagnostic sits behind an `if goog.DEBUG` gate, so when the constant is false the compiler folds the gate to "always skip" and then deletes the now-unreachable code entirely — *dead-code elimination*. **What's gone from the file you just built:**

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

> **For JavaScript developers.** This is the inverse of the usual bundler story. You don't add a production logging service and tree-shake the dev tooling by hoping your imports are side-effect-free; the framework gates its *own* diagnostics behind `goog.DEBUG` so `:advanced` constant-folds them away by construction. The error sink is the one diagnostic that survives on purpose — think of it as the structured replacement for the `window.onerror` handler you'd otherwise hand-roll, except it arrives pre-redacted and carries the frame and event-id.

> **Gotcha. Pre-alpha: no back-compat covenant yet.** re-frame2 is pre-alpha. The *shape* of what you ship is stable in the ways this page describes, but there is no back-compat covenant yet. Expect to track changes between releases.

---

That's the tutorial. You built a real app — pages, server data, auth, writes — and you just tested and shipped it.

**You can now:**

- test any handler by calling it with a literal coeffects map, reading `:rf.cofx/requires` off `handler-meta` as the fixture checklist
- compute a subscription headlessly with `compute-sub` — app-db subs against `app-db-value`, machine-backed subs against `frame-state-value`
- assert on the rendered view on the JVM — walk the hiccup with `find-by-testid` / `text-content` / `invoke-handler`, or emit markup with `render-to-string` — catching state-correct/view-broken and wrong-frame-dispatch bugs
- drive a full cascade deterministically — facts supplied via `{:rf.cofx ...}`, HTTP answered by canned stubs, everything settled when `dispatch-sync` returns
- run the suite on the JVM in milliseconds, with per-test frame isolation (`make-reset-runtime-fixture` + `with-new-frame`) and no DOM emulation
- cut a release bundle and state precisely what elided (schemas, the trace channel) and what survived (the causal channel, the always-on error axis)
- wire a production error sink that receives already-projected records
