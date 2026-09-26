# Test an event handler

An [**event handler**](../glossary.md#event-handler) is the pure function that runs when an [event](../glossary.md#event) is dispatched and computes the next state. Most of an app's logic lives in handlers, so most of your tests belong here. They are also the cheapest tests you will write: pull the handler out of the [registrar](../glossary.md#registrar), call it with literal values, and assert on what it returns. No browser, no DOM, no fake network or clock.

The examples test the todo app, whose app-db looks like `{:todos {1 {:id 1 :title "Buy milk" :done? false}} :showing :all}`.

## 1. Pluck the handler and call it

Every event handler takes two arguments: the [**coeffects**](../glossary.md#coeffect) map (the facts the framework hands it, always including `:db`, the current [app-db](../glossary.md#app-db)) and the event vector. It returns an [**effect map**](../glossary.md#effect-map) whose `:db` key is the next state.

```clojure
;; src/my_app/todos.cljc
(rf/reg-event :todo/toggle
  (fn [{:keys [db]} [_ id]]
    {:db (update-in db [:todos id :done?] not)}))
```

`rf/handler-meta` reads a registration back. Given `{:source :store :kind :event :id <id>}` it returns the registration's metadata, whose `:handler-fn` is your function as you wrote it:

```clojure
(ns my-app.todos-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as ts]
            [my-app.todos]))   ;; loading the ns registers the handlers

(deftest toggle-flips-done
  (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :event :id :todo/toggle}))
        db      {:todos {1 {:id 1 :title "Buy milk" :done? false}}}]
    (is (true? (get-in (handler {:db db} [:todo/toggle 1])
                       [:db :todos 1 :done?])))))
```

That is a function call and an assertion, with no [frame](../glossary.md#frame) and no runtime. It runs on the JVM, where most re-frame2 suites live. The require of `my-app.todos` is what runs the `reg-event` calls; without it the registrar has nothing to hand back. (`ts` is used by the fixtures later on this page. Setting up the runner, the `deps.edn` `:test` alias and the `.cljc` files that let registrations load on the JVM, is covered in [the tutorial's Part 6: test it, ship it](../../resources/tutorial/06-test-and-ship.md).)

`handler-meta` returns `nil` for an unregistered id. If you misspell the id or forget the require, `(:handler-fn nil)` is `nil` and the next line fails with "nil is not a function". When that happens, check the id and the require list.

A handler that only performs side effects, say one that dispatches a follow-up, may return `nil` or an effect map with no `:db` (see [Effects](../effects.md)). Assert on `:fx` for those.

??? info "Coming from Redux?"

    This is the reducer test: call the function, check the return. Where a Jest or Vitest suite would reach for `vi.mock` or fake timers, a handler test doesn't need to, because a handler's inputs arrive as declared data and its side effects leave as data.

## 2. A handler that needs the world

Some handlers need facts from outside: the current time, a random number, a value from local storage. The handler **declares** each one as a [coeffect](../glossary.md#coeffect), and the value arrives as ordinary data in the coeffects map. The test hands it in.

This `:todo/add` stamps the creation time and asks for the list to be saved:

```clojure
;; src/my_app/todos.cljc
(rf/reg-event :todo/add
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ title]]
    (let [id    (inc (apply max 0 (keys (:todos db))))
          todos (assoc (:todos db) id {:id id :title title :done? false
                                       :created-at time-ms})]
      {:db (assoc db :todos todos)
       :fx [[:todo.storage/save todos]]})))
```

`:rf.cofx/requires` lists the coeffects the handler reads, here only the clock. Each arrives flat in the coeffects map beside `:db`. The list is also the test's fixture checklist, and you can read it off the registrar:

```clojure
(:rf.cofx/requires (rf/handler-meta {:source :store :kind :event :id :todo/add}))
;; => [:rf/time-ms]
```

The test supplies exactly those facts:

```clojure
(deftest add-stamps-and-saves
  (let [handler (:handler-fn (rf/handler-meta {:source :store :kind :event :id :todo/add}))
        result  (handler {:db {:todos {}} :rf/time-ms 1781078400123}
                         [:todo/add "Buy milk"])
        todo    {:id 1 :title "Buy milk" :done? false :created-at 1781078400123}]
    ;; the state change it computed
    (is (= todo (get-in result [:db :todos 1])))
    ;; the save it asked for, as data
    (is (= [[:todo.storage/save {1 todo}]] (:fx result)))))
```

The handler did not write to storage. It returned a description of the write, an [effect](../glossary.md#effect), and the runtime, absent from this test, is what would perform it. So the test asserts on the description. [Effects and coeffects](../coeffects.md) explains why the outside world only appears at this boundary.

!!! note "What the coeffects map contains"

    At runtime a handler receives the base keys (`:db`, `:event`, the frame id and the recorded `:rf.cofx` map) plus exactly the facts named in its `:rf.cofx/requires`, each under its own id. An undeclared fact is not delivered. In a pure-call test you only build the keys the handler reads.

## 3. When you want the runtime: a fresh frame per test

The pure call tests the handler's logic but skips the runtime: it never checks that dispatching `[:todo/add "Buy milk"]` finds that handler, or that the returned `:db` lands in app-db. That wiring is the framework's job, so you rarely need to test it. When you do, drive a real [dispatch](../glossary.md#dispatch) and read the committed state.

That needs a [**frame**](../glossary.md#frame), an isolated runtime with its own app-db (see [Frames](../frames.md)). `with-new-frame` creates one, makes it current for the body, and destroys it on the way out, even if the body throws.

```clojure
(deftest add-through-the-runtime
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync [:todo/add "Buy milk"]
                      {:rf.cofx      {:rf/time-ms 1781078400123}
                       :fx-overrides {:todo.storage/save (fn [_ctx _todos] nil)}})
    (is (= "Buy milk" (get-in (rf/app-db-value f) [:todos 1 :title])))))
```

[`dispatch-sync`](../glossary.md#dispatch-sync) [drains](../glossary.md#drain--run-to-completion) the queue before it returns, so the next line reads committed state. The two options replace the literal coeffects map from section 2:

- **`:rf.cofx`** supplies coeffects on the dispatch. Supplied values win, and the runtime fills in only what is missing. Without it the clock would be the real time the event was queued.
- **`:fx-overrides`** redirects an effect for this one dispatch. Here it discards the storage write. [Test a pipeline run](pipeline-runs.md) shows how to capture the args instead, or answer an HTTP request with a canned reply.

!!! warning "Gotcha"

    Call `dispatch-sync` from a test, at boot or at the REPL, never from inside a running handler. A handler that calls `(rf/dispatch-sync [:other])` raises `:rf.error/dispatch-sync-in-handler`. Return `{:fx [[:dispatch [:other]]]}` instead, and the runtime runs it in the same drain.

Inside a `with-new-frame` body you don't pass `:frame`, because the macro makes `f` current. If you keep a frame in a `let` and destroy it yourself, pass `{:frame f}` in the dispatch opts. There is no default frame to fall back on (see [frame identity is carried, not found](../glossary.md#frame-identity-is-carried-not-found)).

### Seeding state: the frame boots it, the body tests it

When a test needs state before the action under test, hand the setup to `make-frame` as `:initial-events`, the same ordered event list a production [frame](../frames.md#seeding-initial-state) boots with. Each step dispatches and drains, in order, while the frame is built. The body then holds one dispatch, the one under test:

```clojure
(deftest toggle-a-seeded-todo
  (rf/with-new-frame [f (rf/make-frame
                          {:fx-overrides   {:todo.storage/save (fn [_ _] nil)}
                           :initial-events [[:todo/add "Buy milk"]
                                            [:todo/add "Walk the dog"]]})]
    (rf/dispatch-sync [:todo/toggle 2])
    (is (true? (get-in (rf/app-db-value f) [:todos 2 :done?])))))
```

`:fx-overrides` on the frame applies to every dispatch in it, setup included. A setup step that needs its own opts takes the map form, where `:opts` is an ordinary `dispatch-sync` opts map:

```clojure
{:initial-events [{:event [:todo/add "Buy milk"]
                   :opts  {:rf.cofx {:rf/time-ms 1781078400123}}}]}
```

If a setup step fails (a handler throws, a required coeffect is missing), `make-frame` destroys the partial frame and throws `:rf.error/initial-events-step-failed` naming the step, so a broken seed fails at the `make-frame` line.

### Checking one path

`re-frame.test-support` has a `clojure.test`-aware path assertion, `(ts/assert-path-equals path expected opts?)`. It reads a frame's app-db, compares the value at `path`, and reports a pass or fail through `clojure.test`. Like `dispatch-sync`, it works on the current frame, so inside `with-new-frame` it needs no `:frame`. A failure message names the frame by its id, so an `:id` makes that message readable:

```clojure
(deftest toggle-committed
  (rf/with-new-frame [_ (rf/make-frame {:id             :test/todos
                                        :fx-overrides   {:todo.storage/save (fn [_ _] nil)}
                                        :initial-events [[:todo/add "Buy milk"]]})]
    (rf/dispatch-sync [:todo/toggle 1])
    (ts/assert-path-equals [:todos 1 :done?] true)))
```

To check a different frame, pass `{:frame x}`, where `x` is the frame's id or the frame value `make-frame` returned. Outside `with-new-frame` and `with-frame`, the helper reads the reset fixture's current frame (`:rf/default` when the fixture installs an adapter). For a whole-map check, compare directly: `(is (= expected-db (rf/app-db-value f)))`.

## 4. The trap: frames don't isolate registrations

`with-new-frame` gives each test its own app-db, but not its own registrar. `reg-event` and its siblings write to one process-global [registrar](../glossary.md#registrar) shared by the whole test run.

If two test namespaces register different handlers under the same id, the later load wins silently. Every test passes alone, the suite fails together, and the failure moves when test order changes.

If your tests, or helpers they load, register anything themselves, add the reset fixture:

```clojure
(use-fixtures :each (ts/make-reset-runtime-fixture {}))
```

`make-reset-runtime-fixture` returns the fixture function you hand to `use-fixtures`. It snapshots the registrar before each test and restores it afterwards, keeping the registrations your namespaces made at load. It also resets the rest of the per-process runtime: frames, flows, machine timers, in-flight HTTP, resource caches, epoch history and trace listeners. Resets for artefacts you haven't loaded do nothing, so use it as the default for any real suite.

For a single ad-hoc block, call the primitives `ts/snapshot-registrar` and `ts/restore-registrar!` yourself:

```clojure
(let [snap (ts/snapshot-registrar)]
  (try
    (rf/reg-event :scratch/probe (fn [_ _] nil))
    ;; ... assertions that need the scratch registration ...
    (finally (ts/restore-registrar! snap))))
```

## Advanced

### Generated coeffects and the `:test` preset

Some coeffects are backed by a **generator**: an app-registered, recordable `reg-cofx` whose supplier produces a fresh value each time, such as a new id. What happens when a dispatch needs one but doesn't supply it depends on the frame's mint policy. A plain `(rf/make-frame {})` uses `:live` and runs the generator. A `{:preset :test}` frame uses `:strict`, and the dispatch raises `:rf.error/missing-required-cofx` instead of inventing a value your test didn't choose. (`:rf/time-ms` never fails this way: the router stamps every event with it.)

```clojure
(rf/reg-cofx :app/new-id
  {:recordable? true}
  (fn [] (str (random-uuid))))

(rf/reg-event :todo/import
  {:rf.cofx/requires [:app/new-id]}
  (fn [{:keys [db app/new-id]} [_ title]]
    {:db (assoc-in db [:todos new-id] {:id new-id :title title :done? false})}))

(rf/with-new-frame [f (rf/make-frame {:preset :test})]
  (rf/dispatch-sync [:todo/import "Buy milk"]
                    {:rf.cofx {:app/new-id "id-123"}})
  (get-in (rf/app-db-value f) [:todos "id-123" :title]))
;; => "Buy milk"
```

Supply the fact in `:rf.cofx`, which is almost always what you want. When a fresh value per run is intended, pass `{:rf.cofx/mint-policy :explicit-live}` as a dispatch opt.

`{:preset :test}` expands to three entries: it redirects `:rf.http/managed` to a canned-success stub (registered by `re-frame.http.test-support`, so require that namespace), sets `:rf.cofx/mint-policy :strict`, and sets `:drain-depth 100`, the framework default. Your own keys win over the expansion.

### Why no mocks are needed

Declaring its inputs makes the handler a function from an environment (the coeffects map) to a value (the effect map). The framework builds the environment and performs the effects, and the handler in between is pure. The test calls that pure function with a literal environment, and the `:rf.cofx/requires` vector tells you what the environment must contain.
