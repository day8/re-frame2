# Writing re-frame2 tests

Load when the task is **authoring a `deftest` / `cljs.test` test** against re-frame2 application code: an event-fx handler, a sub graph, a machine snapshot, a tag query, a view that reads from a frame. Teaches only the **re-frame2-specific binding** — `clojure.test` / `cljs.test` themselves are assumed. The skill stops at writing the test; the author runs the suite, so name the nearest relevant gate concretely (see [§Discovering a project's gates](#discovering-a-projects-gates)) for the hand-off.

## The single import

```clojure
(:require [re-frame.core         :as rf]
          [re-frame.test-support :as ts]
          #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
             :cljs [cljs.test    :refer-macros [deftest is testing use-fixtures]]))
```

Everything you need — fixtures, helpers — lives under `re-frame.test-support`. Do not reach into `re-frame.registrar` or `re-frame.frame` directly.

## The per-test fixture (always use it)

```clojure
(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter reagent-adapter/adapter}))
```

`make-reset-runtime-fixture` snapshot/restores the registrar around each test, resets every frame's `app-db` to `{}`, disposes any installed substrate adapter and reinstalls the one in `:adapter`, and ensures `:rf/default` is present. Per-test `reg-event` / `reg-sub` / `reg-machine` calls land cleanly inside the test and are rolled back on the way out — **without** wiping framework registrations (e.g. `:rf/route`, `:rf/machine` subs) that landed at namespace-load time.

JVM tests pass the plain-atom adapter:

```clojure
(:require [re-frame.substrate.plain-atom :as plain-atom])

(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))
```

Optional opts: `:init-fn` (zero-arg fn run after adapter install, before the test), `:clear-kinds` (a collection of registrar kinds to clear after the snapshot capture), `:clear-app-schemas?` (boolean — when true, clears the schemas artefact's per-frame side-table to start each test with an empty schema slate while preserving the snapshot on exit; app-db schemas are NOT a registrar kind so they have their own opt), `:async?` (boolean — return the `cljs.test` map-form fixture for suites with async tests; see below).

Do **not** call `(registrar/clear-all!)` from a fixture — under CLJS, framework registrations cannot be reloaded and will be gone for the rest of the run.

### Async (`cljs.test`) suites — `:async? true`

A suite with `(async done …)` tests cannot use the fn-form fixture: `cljs.test` HARD-ERRORS with *"Async tests require fixtures to be specified as maps"*. The reason is structural — the fn-form establishes the body's ambient frame scope with a dynamic `binding`, and that binding unwinds the instant the fixture fn returns, which for an async test is *before* the body resumes on a later tick. Pass `:async? true` to get the `{:before :after}` map-form instead:

```clojure
(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter :async? true}))

(deftest drains-across-the-async-boundary
  (async done
    (js/setTimeout
      (fn []
        ;; a BARE dispatch-sync — no explicit {:frame …} — still drains and lands,
        ;; because :before set! the ambient scope persistently (not a binding).
        (rf/dispatch-sync [:counter/inc])
        (is (= 1 (:n (rf/app-db-value :rf/default))))
        (done))
      0)))
```

`:before` runs the same reset as the fn-form AND establishes the ambient scope with a **persistent `set!`** of `*current-frame*` (not a dynamic binding) so it survives the async boundary; `:after` tears it down after the test's `done`. Everything else (`:adapter`, `:init-fn`, `:clear-kinds`, `:clear-app-schemas?`, `:ambient-frame`) behaves identically. Two things to verify when you adopt it:

- **Does a bare test-body `dispatch-sync` actually land?** This is the whole point of the map-form. A naive hand-rolled map fixture that only `set!`s `*current-frame* :rf/default` *without re-ensuring the `:rf/default` frame* silences the no-frame-context throw yet **silently does not drain** — `dispatch-sync` resolves `:rf/default`, finds no frame record, and no-ops. Assert a value actually lands; don't assume it.
- **The fn/map mixing hazard.** `cljs.test` runs every ns in one shared JS runtime, and a *sync* ns's fn-form-fixture teardown resets `frames` to `{}` — destroying `:rf/default` for whatever ns runs next. The `:async? true` fixture is robust because its `:before` re-ensures `:rf/default` every test; a hand-rolled fixture that trusts a sibling-left frame is not. Don't mix fixture *types* within one `use-fixtures` vector — `cljs.test` rejects it ("Fixtures may not be of mixed types").

> **State isolation is the default; behaviour isolation needs a different image, not a registrar mutation — see the section below.** `make-reset-runtime-fixture` snapshots and restores the *shared* default registrar around each test, the right tool for the common single-frame case; reach for an `rf/image` + frame only when a test must isolate its *registration set*, not just its state.

### Behaviour isolation in tests — image, not a global install

A test that needs a *different instruction set* (a fake HTTP fx, a swapped coeffect supplier, a narrower route table) wants a different **image**, not a process-global registrar mutation. Those are image changes — they produce another image generation rather than mutating shared state under the running frame. The shape (the one EP-0024 `make-frame` constructor over `:images` — returns the live frame value; see [`fundamentals/images.md`](../fundamentals/images.md)):

```clojure
(deftest cart-add-isolated
  (let [frame (rf/make-frame
                {:images     [(rf/image {:select-ns {:include ["shop.cart.**"
                                                               "shop.test-doubles.**"]}})]
                 :initial-events [[:rf/set-db {:cart/items []}]]})]
    (rf/dispatch-sync [:cart/add "SKU-1"] {:frame frame})
    (is (= ["SKU-1"] @(rf/subscribe [:cart/items] {:frame frame})))))
```

- **The frame is a local frame value** (no `:id`) — born in the test, discarded with it, never claiming a public frame id. `make-frame` returns the frame value (the lifecycle token); a test passes it directly (or its id) to `dispatch-sync` / `subscribe` — both accept either form, no accessor needed. That is the direct-frame-value test pattern (EP-0024).
- **Override behaviour through a later image**, not a global install: compose a small overrides image *after* the app image (its `:registrations` shadow the earlier ones — image order decides, the later image wins), then read the `:rf.gen/shadows` report on `rf/frame-generation` to assert exactly what it overrode. A swap is data the test states rather than last-writer-wins on a shared table. There is no `:replace` / `:replace-standard` declared-winner key — image order is the only mechanism.
- For an ordinary single-frame test, keep it on `make-reset-runtime-fixture` + `with-new-frame`. A frame created with no `:images` resolves against the shared registrar; pass `:images [...]` to isolate behaviour. **Isolate *behaviour* with a later overrides image; isolate *state* with a fresh frame** — there is no realm / app / module install surface (see [`fundamentals/images.md` §Frame isolation is the whole isolation story](../fundamentals/images.md#frame-isolation-is-the-whole-isolation-story)).

## Driving events: `dispatch-sync` and `dispatch-sequence`

`rf/dispatch-sync` drains to fixed point synchronously — by the time it returns, the handler has run, fx have fired, and the queue is empty. Use it instead of `rf/dispatch` in tests; `dispatch` is async and the test would assert before the handler ran.

```clojure
(rf/dispatch-sync [:counter/inc])
(is (= 1 (:n (rf/app-db-value :rf/default))))
```

`ts/dispatch-sequence` fires a vector of events in order, each drained before the next:

```clojure
(ts/dispatch-sequence [[:counter/init] [:counter/inc] [:counter/inc]])
;; => the final app-db value
```

Capture intermediate states with `:after-each`:

```clojure
(let [seen (atom [])]
  (ts/dispatch-sequence
    [[:counter/inc] [:counter/inc]]
    {:after-each (fn [db ev] (swap! seen conj [(:n db) ev]))})
  @seen)
;; => [[1 [:counter/inc]] [2 [:counter/inc]]]
```

Target a non-default frame with `{:frame :feature/frame-id}` in the trailing opts map.

## Pinning recordable coeffects: `:rf.cofx` in dispatch opts

A handler that writes a durable timestamp / generated id **declares** the fact in `:rf.cofx/requires` and reads it flat (EP-0017) — `:rf/time-ms` for the durable clock, the event payload or a recordable cofx for an id — not an ambient `js/Date` / `random-uuid` call. That makes the handler a pure function of its inputs, and a test pins those inputs by passing `:rf.cofx` in the dispatch opts. The runtime preserves a supplied map verbatim (filling only `:rf/time-ms` when absent), so the durable write becomes deterministic:

```clojure
;; Handler under test declares the clock and reads it flat:
;; (rf/reg-event :todo/create
;;   {:rf.cofx/requires [:rf/time-ms]}
;;   (fn [{:keys [db rf/time-ms]} [_ text]]
;;     {:db (assoc-in db [:todos :t1] {:text text :created-at time-ms})}))

(rf/dispatch-sync [:todo/create "write spec"]
  {:rf.cofx {:rf/time-ms 1700000000000}})             ;; pin the durable clock
(ts/assert-path-equals [:todos :t1 :created-at] 1700000000000)

;; Pin a generated id the same way (a recordable fact in the flat :rf.cofx map):
(rf/dispatch-sync [:todo/create "write spec"]
  {:rf.cofx {:rf/time-ms 1700000000000
             :todo/id    #uuid "00000000-0000-0000-0000-000000000001"}})
```

Pin at the boundary that owns it: a caller-pinned id is simplest on the **event payload** (`[:todo/create {:id … :text …}]`); a fold-internal fact rides `:rf.cofx`. Pinning works through `rf/dispatch-sync` (and `rf/dispatch`) opts — the router preserves a caller-supplied `:rf.cofx` verbatim, filling only `:rf/time-ms` when absent (omit it and the runtime stamps the wall clock at dispatch — fine for a non-durable assertion, but pin it whenever the test asserts a durable timestamp/id). `ts/dispatch-sequence` does **not** forward `:rf.cofx` (it threads only `:frame` / `:source` / `:origin`), so a multi-event scenario needing a pinned clock dispatches each step through `rf/dispatch-sync` with the opt. (A durable host fact behind a *recordable* cofx is pinned by stubbing that cofx — see [§HTTP and other side-effecting fx](#http-and-other-side-effecting-fx); a plain ambient cofx for a diagnostic read needs no pinning.)

## Pinning a frame: `with-frame`

`with-frame` binds the active frame inside the body. Two shapes:

```clojure
(rf/with-frame :stories
  (rf/dispatch-sync [:counter/inc])
  (ts/assert-path-equals [:n] 1))

(rf/with-new-frame [f (rf/reg-frame :stories {})]  ;; new frame, symbol AND dynamic var
  (is (= :stories f))                              ;; reg-frame returns the id
  (is (= :stories (rf/current-frame-id))))
```

On CLJS reach the macro via `rf/with-frame` after `(:require [re-frame.core :as rf])`, or `:require-macros [re-frame.core :refer [with-frame]]`. On JVM use the `(rf/with-frame frame-id (fn [] ...))` function form.

## Asserting state: `assert-path-equals` / `assert-db-equals` and `app-db-value`

Two fns — one per shape — sharing a name root with the `:rf.assert/*` Story event-family:

```clojure
(ts/assert-db-equals   {:n 0})                          ;; full-db match against current frame
(ts/assert-path-equals [:n] 7)                          ;; path match — equivalent to (= 7 (get-in db [:n]))
(ts/assert-path-equals [:n] 7 {:frame :stories})        ;; trailing opts pins the frame
(ts/assert-db-equals   {:n 0} {:frame :stories})        ;; same for the full-db form
```

`assert-path-equals` mirrors the `:rf.assert/path-equals` event used inside Story `:script` blocks; the shared name root means a reader navigating between the two surfaces needs no translation table. `assert-db-equals` is the companion full-db form (no `:rf.assert/*` event analog — the event-family is path-keyed).

Failure reports through `clojure.test/is` with both expected and actual, so the diagnostic is one line. For ad-hoc reads outside an assertion:

```clojure
(rf/app-db-value :rf/default)                       ;; whole app-db, any frame
(get-in (rf/app-db-value :rf/default) [:cart :items])  ;; path-scoped read
(get-in (rf/app-db-value :stories) [:cart :items])
```

These are value-form accessors — there is no `deref`. They work identically on JVM and CLJS.

## Asserting subscriptions: `compute-sub` (preferred) and `subscribe-once`

For a sub graph under test, **prefer `compute-sub`** — it runs the registered sub against a supplied db with no reactive cache involvement, so the test does not depend on prior subscribe state:

```clojure
(rf/reg-sub :items    (fn [db _] (:items db)))
(rf/reg-sub :item-sum :<- [:items] (fn [items _] (reduce + items)))

(is (= 60 (rf/compute-sub [:item-sum] {:items [10 20 30]})))
```

`compute-sub` supports the `:<-` chain shape exactly like `subscribe` does and validates the return value against any output `:schema` metadata on the sub.

When the test is exercising the live cache (e.g. layer-2 sub on top of a real dispatch), use `subscribe-once`:

```clojure
(rf/dispatch-sync [:seed])
(is (= 60 (rf/subscribe-once [:item-sum])))
```

`subscribe-once` materialises the reaction, reads `@`, and unsubscribes — one line, no leaked subscription. Prefer it over `@(rf/subscribe ...)` in tests.

## Asserting the view: `re-frame.test-helpers` (the view-tree axis)

`re-frame.test-support` covers the **runtime-state** axis (events / fx / subs / machines). The sibling namespace `re-frame.test-helpers` covers the **view-tree** axis — call the view-fn, walk the returned hiccup by `:data-testid`, assert on rendered content. Reach for it when "does the screen show the right thing?" or "does the button dispatch the right event?" is the question. A test doing both `:require`s both.

```clojure
(:require [re-frame.core         :as rf]
          [re-frame.test-helpers :as h])
```

### The single-frame e2e trio: `with-app-fixture` / `expect-text` / `wait-until`

This is the dominant shape for an app-developer e2e view test — it compresses the `make-frame` / `with-frame` / `destroy-frame!` bracket to one macro. `with-app-fixture` creates a fresh single frame, runs an `:install` thunk inside its dynamic extent, stashes the root view so `expect-text` / `wait-until` find it without a tree argument, and tears the frame down in a `finally`.

```clojure
(deftest counter-e2e
  (h/with-app-fixture
    {:install   (fn []
                  (rf/reg-event :counter/inc (fn [{:keys [db]} _] {:db (update db :n inc)}))
                  (rf/reg-sub   :counter/n   (fn [db _] (:n db)))
                  (rf/reg-view  :counter/view
                    (fn [] [:span (h/testid "n") @(rf/subscribe [:counter/n])])))
     :root-view (fn [] [:counter/view])}
    (rf/dispatch-sync [:counter/inc])
    (h/expect-text :n "1")))          ;; uses the stashed root view
```

`with-app-fixture` opts (all optional): `:install` (zero-arg fn run inside the bound frame — register events/subs/views here; pair with a `make-reset-runtime-fixture` `:each` fixture to roll the registrations back), `:root-view` (hiccup-returning view fn stashed for `expect-text`/`wait-until`), `:root-view-args` (args vector applied to `:root-view`, default `[]` — use when the view takes a props map), `:frame-config` (map merged into `make-frame`/`reg-frame` — `:initial-events`, `:fx-overrides`, `:interceptors`, …). Two call shapes: `(with-app-fixture opts body+)` gets an anonymous gensym'd frame id; `(with-app-fixture opts frame-id body+)` names it.

`expect-text` asserts the `:data-testid` node's text equals `expected`, reporting via `clojure.test/is`:

```clojure
(h/expect-text :n "1")              ;; uses the fixture-stashed root view
(h/expect-text tree :n "1")         ;; 3-arity — walk an explicit tree, no fixture
```

`testid` may be a string (`"n"`) or keyword (`:n`). Returns a boolean, but the `is` report has already fired.

`wait-until` is the bounded-deadline poll for async runs (HTTP, scheduled events, machine `:after` transitions) whose post-condition is observable in the view — the view-test counterpart to `test-support`'s `poll-until`. Two call shapes plus opts:

```clojure
(h/wait-until #(= "done" (-> (some-tree) (h/find-by-testid "status") h/text-content)))
(h/wait-until :status "done")                          ;; testid form — polls the stashed root view
(h/wait-until :status "done" {:timeout-ms 5000 :interval-ms 10 :label "status ready"})
```

`opts`: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label` (timeout-message tag). **Per-platform shape** (matching `poll-until`): **JVM** synchronous — returns the truthy value, throws `ex-info` (`:rf.error/id :rf.error/wait-until-timeout`) on timeout; **CLJS** returns a `js/Promise` — resolves with the truthy value, rejects on timeout, compose with `cljs.test/async`. For sync runs, `expect-text` after `dispatch-sync` suffices — reach for `wait-until` only when the run is genuinely async. Not a substitute for timer-semantics sleeps (grace-period elapse, throttle/debounce window).

### Lower-level walk helpers — the hiccup-walk pattern

When a fixture didn't stash the tree, or you need the `:on-click`-fires-the-right-event assertion rather than a text check, walk the hiccup directly. The view-fn returns hiccup; that's just data. Dispatch via `dispatch-sync` into the test frame, call the view-fn, then walk the returned tree by `:data-testid`:

```clojure
(deftest counter-view-shows-and-fires
  (rf/with-new-frame [f (rf/make-frame {:initial-events [[:counter/init]]})]
    (let [tree (counter-view {:n 0})
          btn  (h/find-by-testid tree "counter-inc")]
      (h/invoke-handler btn :on-click nil)              ;; fire the handler as the DOM would
      (is (= 1 (:n (rf/app-db-value f)))))))
```

- `find-by-testid` / `find-all-by-testid` — locate node(s) by `:data-testid`.
- `text-content` — the rendered text under a node.
- `invoke-handler` — call an attr handler (`:on-click`, `:on-change`, …) with an event arg, as the DOM would.
- `testid` — the **authoring** helper that standardises the attrs fragment at view call sites; use it whenever you write a new view that wants a test handle:

```clojure
[:button (h/testid "counter-inc" {:on-click #(rf/dispatch [:counter/inc])}) "+"]
```

**Why walk the view, not just assert state?** State-only assertions (`(is (= 2 (:n db)))`) catch handler bugs but miss two classes the hiccup-walk catches — *state-correct, view-broken* (handler updated db, view reads the wrong path / forgets a branch) and *wrong-frame dispatch* (`:on-click` dispatches into the wrong frame; host-frame state never changes). Both surface on JVM and Node-CLJS with no browser.

**Single-frame discipline.** Application view tests use ONE frame — the host frame. Views, events, subs, and asserts all reference the same frame. A multi-frame harness — a test where an **observer / tool** frame watches another frame's app-db or trace stream (as a dev tool would) — is the only shape that legitimately spans frames, and it is never a regular application view. Full walkthrough at [`docs/core/testing/pipeline-runs.md`](../../../../docs/core/testing/pipeline-runs.md).

## Machine snapshots and tag queries

A machine's snapshot lives in the **runtime-db** partition at `(get-in runtime-db [:rf.runtime/machines :snapshots machine-id])` — a map of `{:state ... :data ... :tags ...}` (`:tags` is absent when the active state-configuration's tag union is empty). Read runtime-db with `(:rf.db/runtime (rf/frame-state-value frame-id))` (not `app-db-value`, which returns the app-db partition only) — or, preferably, read the snapshot through the `[:rf/machine machine-id]` subscription vector.

```clojure
(rf/reg-machine :loader
  {:initial :idle
   :data    {}
   :states  {:idle    {:tags #{:empty}     :on {:fetch :loading}}
             :loading {:tags #{:transient} :on {:done :ready}}
             :ready   {:tags #{:terminal}}}})

(rf/dispatch-sync [:loader [:fetch]])

;; Direct snapshot access — full-shape assertions (runtime-db partition)
(let [s (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/machines :snapshots :loader])]
  (is (= :loading      (:state s)))
  (is (= #{:transient} (:tags s))))

;; Same assertion through the framework sub (preferred for reaction-driven tests)
(is (= :loading      (:state @(rf/subscribe [:rf/machine :loader]))))
(is (= true          @(rf/subscribe [:rf/machine-has-tag? :loader :transient])))
(is (= false         @(rf/subscribe [:rf/machine-has-tag? :loader :terminal])))
```

For compound machines, `:state` is a path vector (`[:auth :dashboard]`) and `:tags` is the union along the path. `:rf/machine-has-tag?` is null-tolerant: a missing or uninitialised machine returns `false` rather than throwing.

The pure transition fn — `(re-frame.machines/machine-transition machine snapshot event)` — returns `[new-snapshot fx]` with no frame and no dispatch loop. Use it when the test wants to assert transition tables in isolation. (`machine-transition` lives on the owning `re-frame.machines` namespace — it is not on the `re-frame.core` façade.)

## HTTP and other side-effecting fx

For `:rf.http/managed`, install per-call stubs around the body:

```clojure
(rf/with-managed-request-stubs
  ;; :reply is a MAP — {:ok <value>} for success, {:failure {:kind ...}} for
  ;; failure. The runtime branches on (contains? reply :ok) / (:failure);
  ;; a bare keyword like :reply :ok matches nothing and falls through to the
  ;; "no stub matched" transport failure.
  {[:get "/api/items"] {:reply {:ok {:items [...]}}}
   [:post "/api/cart"] {:reply {:failure {:kind :rf.http/http-5xx :status 500}}}}
  (rf/dispatch-sync [:cart/fetch])
  (ts/assert-path-equals [:cart :status] :ready))
```

For arbitrary fx, override the registered handler from inside the test — the fixture rolls the registration back on the way out:

```clojure
(let [calls (atom [])]
  (rf/reg-fx :app/persist (fn [_ v] (swap! calls conj v)))
  (rf/dispatch-sync [:cart/save])
  (is (= [{:items [...]}] @calls)))
```

### `:fx-overrides` per-call function value

The dispatch-opts `:fx-overrides` map also accepts **function values** — a one-shot lambda that runs in place of the registered fx-handler for this dispatch only. No registry mutation, nothing to roll back. The signature matches `reg-fx`'s binary contract: `(fn [m args] ...)`, where `m` carries `:frame` (and `:event` when the fx ran from an event handler) and `args` is the fx's arg payload.

```clojure
(let [calls (atom [])]
  (rf/dispatch-sync [:cart/save]
    {:fx-overrides {:app/persist (fn [_m args] (swap! calls conj args))}})
  (is (= [{:items [...]}] @calls)))
```

For HTTP stubs, the fn form lets the test return a canned response shape without registering a parallel `:rf.http/managed-canned-success` fx:

```clojure
(rf/dispatch-sync [:user/login {:email "user@example.com"}]
  {:fx-overrides {:rf.http/managed
                  (fn [m args]
                    (when-let [on-success (:on-success args)]
                      (rf/dispatch (conj on-success {:status 200 :body {:user "u1"}})
                                   {:frame (:frame m)})))}})
```

Per-frame `:fx-overrides` in `reg-frame` accepts the same fn-value form, so a test frame can install a stub once for every dispatch routed to it. The id-keyword form (`{:rf.http/managed :rf.http/managed-canned-success}`) is the portable pattern-level form — use it when the stub is shared across many tests or when SSR / serialisation is in play; reach for the fn form when one test wants a bespoke response.

## Discovering a project's gates

Find the project's *actual* gate commands — don't guess names — and name the narrowest gate that exercises the path you touched, not the full matrix. Check, in order:

- **`deps.edn` `:aliases`** — the `:test` alias is the per-artefact runner (`clojure -M:test` from that artefact's dir). A consumer monorepo often has one `deps.edn` per artefact; name the alias for the dir whose source you changed.
- **`shadow-cljs.edn`** — `:builds` keys and any `:test` build id reveal the CLJS compile/test targets (`npx shadow-cljs compile <id>`, or the project's test build).
- **`package.json` `scripts`** — the `test:*` family (e.g. `test:cljs`, `test:browser`) is the canonical entry-point set for shadow-cljs projects; prefer the one scoped to your change.
- **The nearest `README.md`** — examples and feature dirs often note their gate commands inline ("run `npm run test:foo`"). An example app's README is the authority for that example's gate.

Pick the tightest match and name it for the author. A green slice on the changed path is the signal; reach for a wider gate only when the change genuinely spans artefacts. If no gate exists for the surface you touched, say so and describe the gate the author should add.

## Checklist before declaring a test done

- The ns uses `re-frame.test-support` and `re-frame.core` only — no reach into internal namespaces.
- A `:each` `make-reset-runtime-fixture` is installed with the right `:adapter`.
- Event drive is `dispatch-sync` (not `dispatch`) or `ts/dispatch-sequence`.
- Sub assertions go through `compute-sub` (preferred) or `subscribe-once`; no bare `@(rf/subscribe ...)` left subscribed at test exit.
- Machine assertions use `(subscribe [:rf/machine id])` / `machine-has-tag?` or `(get-in (:rf.db/runtime (rf/frame-state-value frame-id)) [:rf.runtime/machines :snapshots id])` — runtime-db partition, not internal machine namespaces, and not `db`/app-db.
- Schema-validation, fx-stubs, and frame-scoping each use the public surface above. No fixture lifts `registrar/clear-all!`.
- **Gate named for the author** — the nearest relevant gate (the new test's artefact `:test` alias / `npm run test:*` / a focused namespace run) is named concretely so the author can run it; the skill writes the test, the author runs the suite.

---

*Derived from `implementation/core/src/re_frame/test_support.cljc` (public test-support surface), `implementation/core/src/re_frame/test_helpers.cljc` (view-tree assertion surface — `with-app-fixture` / `expect-text` / `wait-until`), and `implementation/core/src/re_frame/substrate/plain_atom.cljc` (JVM adapter) @ main `89bd9c3`. Re-verify after test-support / test-helpers surface changes.*
