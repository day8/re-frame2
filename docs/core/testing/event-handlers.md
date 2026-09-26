# Test an event handler

An [**event handler**](../glossary.md#event-handler) is the pure function that runs when an [event](../glossary.md#event) is dispatched and computes how your app's state should change. Most of an app's logic lives in handlers, so most of your tests belong here, and they are the cheapest tests you will write: no browser, no DOM, no test double for the network or the clock. Pull the handler out of the [registrar](../glossary.md#registrar), call it with literal values, and assert on what it returns.

## 1. Pluck the handler and call it

Every event handler is a two-argument function. The first argument is the [**coeffects**](../glossary.md#coeffect) map, the facts the framework hands the handler; `:db`, the current [app-db](../glossary.md#app-db), is always one of them. The second is the event vector that was dispatched. It returns an [**effect map**](../glossary.md#effect-map) whose `:db` key is the next state.

```clojure
;; my-app/articles.cljs
(rf/reg-event :articles/page-changed
  (fn [{:keys [db]} [_ page]]
    {:db (assoc-in db [:articles :page] page)}))
```

`reg-event` stores that function in the registrar, the process-global table every `reg-*` form writes to. `rf/handler-meta` reads a registration back: give it `{:source :store :kind :event :id <id>}` and it returns the registration's metadata, whose `:handler-fn` is your function exactly as you wrote it.

```clojure
(ns my-app.articles-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.articles]))   ;; loading the ns registers the handlers

(deftest page-changed-sets-page
  (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :event :id :articles/page-changed}))
        result  (handler {:db {:articles {:page 1}}} [:articles/page-changed 3])]
    (is (= 3 (get-in result [:db :articles :page])))))
```

No [frame](../glossary.md#frame), no [dispatch](../glossary.md#dispatch), no runtime: a function call and an assertion. Nothing touches a browser, so this runs on the JVM, where most re-frame2 suites live. The require of `my-app.articles` is what runs the `reg-event` calls; without it the registrar has nothing to hand back. (`ts` is used by the fixtures later on this page. Setting up the runner itself — the `deps.edn` `:test` alias, and the `.cljc` discipline that lets registration namespaces load on the JVM — is covered in [the tutorial's Part 5: test it, ship it](../../resources/tutorial/05-test-and-ship.md).)

??? info "Coming from Redux?"

    This is the reducer test: call the function, check the return. The difference shows up where a Vitest or Jest suite would reach for `vi.mock` or fake timers. A handler's inputs arrive as declared data and its side effects leave as data, so the plain function call covers that ground too. There is no mock module, no spy, and no `beforeEach` wiring up a fake clock.

`handler-meta` returns `nil` for an unregistered id. Misspell the id, or forget the app-namespace require, and `(:handler-fn nil)` is `nil`, so the next line fails with "nil is not a function" rather than "no such handler". If a handler you know you registered comes back `nil`, check the id and the require list.

A handler that only performs side effects — say it dispatches a follow-up and changes no state — returns `nil` or an effect map with no `:db`, and that is valid (see [Effects](../effects.md)). Assert on `:fx` for those.

## 2. A handler that needs the world

Some handlers need facts from outside: the current time, a random number, a value from local storage. A handler that consumes one **declares** it as a [coeffect](../glossary.md#coeffect), and the value arrives as ordinary data in the coeffects map. The test hands it in by hand.

This handler records when a refresh was requested, then issues an HTTP request:

```clojure
;; my-app/articles.cljs — cf. examples/real-apps/realworld_http/articles.cljs
(rf/reg-event :articles/refresh
  {:doc "User asked for a fresh feed: stamp when, issue the request."
   :rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} _event]
    {:db (assoc-in db [:articles :refreshing-since] time-ms)
     :fx [[:rf.http/managed {:request    {:method :get :url "/articles"}
                             :on-success [:articles/loaded]
                             :on-failure [:articles/load-failed]}]]}))
```

`:rf.cofx/requires` lists the coeffects the handler consumes, here just the clock. Each arrives flat in the coeffects map beside `:db`. It is the same `reg-event` as section 1: declaring a coeffect is one line of metadata.

The declaration doubles as the test's fixture checklist, and you can read it off the registrar:

```clojure
(:rf.cofx/requires (rf/handler-meta {:source :store :kind :event :id :articles/refresh}))
;; => [:rf/time-ms]
```

The test supplies exactly those facts as literal entries in the coeffects map:

```clojure
(deftest refresh-stamps-and-asks
  (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :event :id :articles/refresh}))
        result  (handler {:db {} :rf/time-ms 1781078400123}
                         [:articles/refresh])]
    ;; the state change it computed
    (is (= 1781078400123 (get-in result [:db :articles :refreshing-since])))
    ;; the request it asked for, as data
    (is (= [:rf.http/managed {:request    {:method :get :url "/articles"}
                              :on-success [:articles/loaded]
                              :on-failure [:articles/load-failed]}]
           (first (:fx result))))))
```

The handler did not fire an HTTP request. It returned a description of one — an [effect](../glossary.md#effect) — and the runtime, absent from this test, is what would perform it. So the test asserts on the description: no fetch was mocked because none happened, and the clock is an entry in a map you wrote. [Effects and coeffects](../coeffects.md) explains why the outside world only appears at this boundary.

!!! note "What the coeffects map contains"

    At runtime a handler receives the framework's base keys — `:db`, `:event` (the dispatched vector), the frame id and the recorded `:rf.cofx` map — plus exactly the facts it named in `:rf.cofx/requires`, each flat under its own id. A fact the handler did not declare is not delivered as a flat key. In a pure-call test you only need to build the keys the handler reads, so if it reaches for a key you didn't supply, read its `:rf.cofx/requires` vector off the registrar and supply each entry.

??? note "Going deeper"

    Declaring its inputs makes the handler a function from an environment (the coeffects map) to a value (the effect map). The framework builds the environment and performs the effects; the handler in between is pure. That's why the test needs no mocks: it calls a pure function with a literal environment. The `:rf.cofx/requires` vector describes that environment, and tools can read it from the registrar.

## 3. When you want the runtime: a fresh frame per test

The pure call tests the handler's logic but skips the runtime. It never checks that dispatching `[:articles/refresh]` finds that handler, or that the returned `:db` lands in app-db. The wiring is the framework's job, so you rarely need to. When you do, drive a real [dispatch](../glossary.md#dispatch) and read the committed state.

That needs a [**frame**](../glossary.md#frame): an isolated runtime context with its own app-db, so tests can't leak state into each other (see [Frames](../frames.md)). `with-new-frame` creates one, makes it current for the body, and destroys it on the way out, whether the body returns or throws.

```clojure
(deftest refresh-stamps-through-the-runtime
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:articles/refresh]
                      {:rf.cofx      {:rf/time-ms 1781078400123}
                       :fx-overrides {:rf.http/managed (fn [_frame-ctx _args] nil)}})
    (is (= 1781078400123
           (get-in (rf/app-db-value f) [:articles :refreshing-since])))))
```

[`dispatch-sync`](../glossary.md#dispatch-sync) [drains](../glossary.md#drain--run-to-completion) the queue to a fixed point before it returns, so the next line reads committed state with nothing to flush or await.

Two dispatch options stand in for the literal coeffects map from section 2:

- **`:rf.cofx`** supplies coeffects on the dispatch, and the runtime passes them to the handler. Supplied values win; the runtime fills in only what is missing. Without it the clock would be the real enqueue time the runtime stamps on the event, and the assertion would chase a moving target.
- **`:fx-overrides`** redirects an effect for this one dispatch. Here it discards the HTTP request, because this test only checks the timestamp. Answering the request with a canned reply and asserting the whole chain is covered in [Test a pipeline run](pipeline-runs.md).

!!! warning "Gotcha"

    Call `dispatch-sync` from a test, at boot, or at the REPL, never from inside a running handler. A handler that calls `(rf/dispatch-sync [:other] …)` in its body raises `:rf.error/dispatch-sync-in-handler`. A handler describes a follow-up dispatch as data instead: return `{:fx [[:dispatch [:other]]]}` and the runtime runs it in the same drain.

??? note "Going deeper — naming the frame yourself"

    A third option, `{:frame f}`, says which frame to dispatch into. Inside a `with-new-frame` body you can leave it out, because the macro makes `f` the current frame. Outside one (say you keep a frame in a `let` and destroy it yourself) you pass `:frame` explicitly. There is no ambient default frame: [a frame's identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found), so the target is either established by scope or named on the opts map.

### When a required coeffect is missing: a loud failure, by design

Some coeffects are backed by a **generator**: an app-registered, recordable `reg-cofx` whose supplier produces a fresh value each time, such as a new id. What happens when a dispatch declares one but doesn't supply it depends on the frame's mint policy. A plain `(rf/make-frame {})` uses the default `:live` policy and runs the generator. A `{:preset :test}` frame uses `:strict`, and the same dispatch raises `:rf.error/missing-required-cofx` instead of minting a value your test didn't choose.

`:rf/time-ms` never fails this way: the router stamps every event with its enqueue time, so the clock is never missing.

```clojure
(rf/reg-cofx :app/new-id
  {:recordable? true}                   ;; a generator-backed recordable fact
  (fn [] (str (random-uuid))))

(rf/reg-event :comment/create
  {:rf.cofx/requires [:app/new-id]}
  (fn [{:keys [db app/new-id]} [_ body]]
    {:db (assoc-in db [:comments new-id] {:body body})}))

;; A {:preset :test} frame is strict. Supply the id, or the dispatch fails
;; with :rf.error/missing-required-cofx instead of minting a random one.
(rf/with-new-frame [f (rf/make-frame {:preset :test})]
  (rf/dispatch-sync [:comment/create "Great article!"]
                    {:rf.cofx {:app/new-id "id-123"}})
  (get-in (rf/app-db-value f) [:comments "id-123" :body]))
;; => "Great article!"
```

A silently minted random value would let the test pass against state production never produces. Either supply the fact in `:rf.cofx`, which is almost always what you want, or, when a fresh value per run is genuinely intended, opt back into generation with `{:rf.cofx/mint-policy :explicit-live}` as a dispatch opt.

!!! note "The `:test` frame preset"

    `{:preset :test}` on `rf/make-frame` expands to three entries. It redirects `:rf.http/managed` to a canned-success stub, so a test frame never reaches the network; the stub is registered by `re-frame.http.test-support`, so add that namespace to the test's requires. It sets `:rf.cofx/mint-policy :strict`, the behaviour above. And it sets `:drain-depth 100`, the framework default, so tools can read the bound off the frame's metadata. Your own keys win over the expansion.

### Seeding state: the frame boots it, the body tests it

When a test needs state built up before the dispatch it is about, hand the setup to `make-frame` as `:initial-events`, the same ordered event list a production [frame](../frames.md#seeding-initial-state) boots with. Each step dispatches synchronously and drains, in order, while the frame is constructed. The body then holds one dispatch, the one under test:

```clojure
(deftest page-change-from-a-seeded-feed
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:articles/init]
                                                         [:articles/page-changed 2]]})]
    (rf/dispatch-sync [:articles/page-changed 3])
    (is (= 3 (get-in (rf/app-db-value f) [:articles :page])))))
```

A setup step that needs pinned facts takes the map form. `:opts` is the ordinary `dispatch-sync` opts map, so a step can carry `:rf.cofx` and `:fx-overrides` like the action can:

```clojure
(rf/make-frame
  {:initial-events [[:articles/init]
                    {:event [:articles/refresh]
                     :opts  {:rf.cofx      {:rf/time-ms 1781078400123}
                             :fx-overrides {:rf.http/managed (fn [_ _] nil)}}}]})
```

If a setup step fails — a handler throws, a required cofx is missing — `make-frame` destroys the partial frame and throws `:rf.error/initial-events-step-failed` naming the step. A broken seed fails at the `make-frame` line.

### Ergonomic state assertions

`re-frame.test-support` has a `clojure.test`-aware helper for path assertions: `(ts/assert-path-equals path expected opts?)` reads a frame's app-db, compares the value at `path`, and reports a `:pass` or `:fail` through `clojure.test`. It looks the frame up by id, so inside a `with-new-frame` body give the frame an `:id` and pass that id as `{:frame …}`, not the frame value `f`:

```clojure
(deftest page-committed
  (rf/with-new-frame [_ (rf/make-frame {:id :test/articles
                                        :initial-events [[:articles/init]]})]
    (rf/dispatch-sync [:articles/page-changed 3])
    (ts/assert-path-equals [:articles :page] 3 {:frame :test/articles})))
```

Without `:frame`, the helper reads the frame established by the reset fixture's ambient scope (`:rf/default` when the fixture installs an adapter). For a whole-map check, compare directly: `(is (= expected-db (rf/app-db-value f)))`.

??? info "For JavaScript developers"

    `assert-path-equals` mirrors the `:rf.assert/path-equals` event used inside a Story play script, so the name is the same in unit tests and Stories. If you've used Testing Library's `expect(…).toHaveTextContent(…)`, this is the app-db equivalent: a focused assertion on one value.

## 4. The trap: frames don't isolate registrations

`with-new-frame` gives each test its own app-db, but not its own registrar. `reg-event` and its siblings write to one process-global [registrar](../glossary.md#registrar) shared across the whole test run: a frame isolates state, not registrations (see [Frames](../frames.md)).

If two test namespaces register different handlers under the same id, the later load wins, silently. Every test passes alone, the suite fails together, and the failure moves as test order changes.

If your tests, or helpers they load, register anything themselves, add the reset fixture:

```clojure
(use-fixtures :each (ts/make-reset-runtime-fixture {}))
```

`make-reset-runtime-fixture` is a factory: calling it returns the fixture function you hand to `use-fixtures`. The fixture snapshots the registrar before each test and restores it afterwards, keeping the registrations your namespaces made at load. It also resets the rest of the per-process runtime — frames, flows, schemas, machine timers, routing counters, in-flight HTTP, resource caches, epoch history and trace listeners. Resets for artefacts you haven't loaded are no-ops, so a plain JVM handler suite pays nothing for them. Use it as the default for any real suite.

For a single ad-hoc block, the raw primitives are `ts/snapshot-registrar` and `ts/restore-registrar!`, which you call directly:

```clojure
(let [snap (ts/snapshot-registrar)]
  (try
    (rf/reg-event :scratch/probe (fn [_ _] nil))
    ;; ... assertions that need the scratch registration ...
    (finally (ts/restore-registrar! snap))))
```

??? info "For JavaScript developers"

    The process-global registrar is the same hazard as one test file mutating a module-level singleton that another file also imports. Jest offers `jest.resetModules()` or `--isolateModules`; here the reset is an explicit `use-fixtures` fixture, and the shared table is a named thing you can snapshot and restore.
