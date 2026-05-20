(ns re-frame.test-support
  "Test fixture helpers shared between JVM and CLJS test suites.

  ## See also — `re-frame.test-helpers` (rf2-v7kjq)

  Sibling namespace covering the **view-tree assertion axis** — hiccup
  walkers (`find-by-testid`, `text-content`, `extract-handler`), handler
  invocation (`invoke-handler`), the single-frame e2e fixture trio
  (`with-app-fixture` / `expect-text` / `wait-until`), and the `testid`
  authoring helper.

  This namespace owns the **runtime-state assertion axis**: registrar,
  frames, `app-db`, drain, in-flight requests, fixture machinery.

  A test that exercises events / subs / machines reaches here. A test
  that asserts on rendered view content reaches `re-frame.test-helpers`.
  A test doing both `:require`s both. See [Spec 008 §Audience-split]
  (../../../../../spec/008-Testing.md#audience-split--re-frametest-support-vs-re-frametest-helpers-rf2-v7kjq)
  for the axis rationale.

  ## Why this namespace exists (rf2-am9d, follow-up to rf2-coks / rf2-p8g8)

  Tests need per-test isolation of *user-test-registered* handlers, subs,
  views, machines, fx, etc. — without wiping *framework-shipped*
  registrations that landed at namespace-load time and (under CLJS)
  cannot be re-loaded at runtime.

  Earlier fixtures in the CLJS test suite reached for `registrar/clear-all!`,
  which is fundamentally hostile to CLJS isolation:

    - `re-frame.routing` registers `:rf.route/transitioned`, `:rf.route/navigate`,
      `:rf.nav/scroll`, the `:rf/route` and `:rf.route/{id,params,query,
      transition,error}` reg-subs (and friends) at ns-load.
    - `re-frame.machines` registers the `:rf/machine` sub at ns-load.
    - Example apps (e.g. `nine-states.core`) register their handlers /
      subs / views / machines at ns-load.

  CLJS has no `(require ... :reload)` analogue, so once those slots are
  wiped they cannot be reinstated for downstream tests in the same run.
  rf2-coks documented the resulting cross-test pollution.

  The right pattern is **snapshot/restore**: capture
  `@registrar/kind->id->metadata` before the test, allow the test to
  register additional ids, then reset the registrar to the captured map
  on the way out. Framework-shipped registrations survive (they're in
  the snapshot); user-test registrations are rolled back; the next test
  starts from the same baseline as this one.

  The same shape works on the JVM. JVM tests can additionally rely on
  `:reload` to resurrect registrations after `clear-all!`, but that's
  the expensive route; snapshot/restore is faster and substrate-agnostic.

  ## Public API

  ### Fixture machinery
  - [[snapshot-registrar]] — capture the current registrar state.
  - [[restore-registrar!]] — restore the registrar to a captured snapshot.
  - [[with-fresh-registrar]] — bracket a thunk with snapshot + restore.
  - [[make-reset-runtime-fixture]] — `clojure.test`/`cljs.test` `:each`
    fixture that snapshot/restores the registrar AND resets the
    per-process state held by frames / flows (when the flows artefact
    is loaded, rf2-tfw3) / adapter / machine counters / trace
    listeners.

  ### Test-flavoured helpers (rf2-0l3s / rf2-hkr5 / rf2-8j9m6)
  - [[dispatch-sequence]] — fire a vector of events synchronously,
    optionally observing intermediate state via `:after-each`.
  - [[assert-path-equals]] — assert `(= expected (get-in app-db path))`
    against the resolved frame; failure reports via `clojure.test/is`.
    Mirrors the `:rf.assert/path-equals` event (Story `:play` blocks);
    same name root so a reader who knows one surface navigates the other.
  - [[assert-db-equals]] — assert `(= expected-db app-db)` against the
    resolved frame; failure reports via `clojure.test/is`. Companion
    full-db form (no event analog).

  ### Deterministic-wait helpers (rf2-ka3n6 / rf2-fun38)
  - [[poll-until]] — bounded-deadline poll for `(pred)` to return
    truthy. JVM returns the truthy value synchronously (throws on
    timeout); CLJS returns a `js/Promise` that resolves with the
    truthy value (rejects on timeout). Replaces incidental fixed
    `Thread/sleep N` / `js/setTimeout` for waits that are observable
    in state (router drain, event-cascade settle, sub re-fire,
    in-flight registry entries appearing/clearing). NOT for
    timer-semantics tests — those should keep their sleep and annotate
    that intent locally (the sleep IS the contract under test)."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            ;; The flows / schemas / machines / routing / http-managed /
            ;; epoch artefacts ship in separate Maven coordinates and are
            ;; reached only through late-bind hooks — see the
            ;; `reset-hook-table` var docstring below for the per-artefact
            ;; rationale (rf2-tfw3 / rf2-p7va / rf2-xbtj / rf2-k682 /
            ;; rf2-5kpd / rf2-lt4e). This ns must not statically require
            ;; any of them.
            [re-frame.late-bind :as late-bind]
            ;; Per rf2-qwm0a: the public-tooling listener + buffer
            ;; surface lives in `re-frame.trace.tooling` (split off
            ;; from `re-frame.trace` for production CLJS bundle DCE).
            ;; Test fixtures need `clear-trace-listeners!` between scenarios;
            ;; we reach it through the tooling sibling directly.
            [re-frame.trace.tooling :as trace-tooling]
            ;; Clear the always-on event-emit listener registry on each
            ;; reset so a forwarder registered in one test doesn't see
            ;; events fired by a sibling test.
            [re-frame.event-emit :as event-emit]
            [re-frame.router :as router]
            [re-frame.substrate.adapter :as adapter]
            #?(:clj  [clojure.test :as ctest]
               :cljs [cljs.test :as ctest :include-macros true])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- registrar snapshot/restore -------------------------------------------

(defn snapshot-registrar
  "Capture the current registrar contents.

  Returns the value of `@registrar/kind->id->metadata` — a plain map of
  `kind → id → metadata` — at the moment of the call. Pair with
  [[restore-registrar!]] to roll the registrar back to this point.

  The returned value is the persistent map the registrar atom holds; it
  is safe to keep across mutations (subsequent `register!` calls produce
  new persistent maps and don't alter the captured one)."
  []
  @registrar/kind->id->metadata)

(defn restore-registrar!
  "Reset the registrar to a previously captured snapshot.

  Any registrations made since the snapshot are dropped; any registrations
  removed since the snapshot are restored. Use this in test fixtures to
  undo per-test pollution while preserving framework / example
  registrations that landed at ns-load time."
  [snapshot]
  (reset! registrar/kind->id->metadata snapshot)
  nil)

(defn with-fresh-registrar
  "Run `body-fn` with a snapshot/restore bracket around the registrar.

  Captures the registrar before `body-fn`, runs `body-fn`, then restores
  the registrar to the captured state — even if `body-fn` throws.
  Returns whatever `body-fn` returned.

  Intended for use in `clojure.test` / `cljs.test` `:each` fixtures:

      (defn fixture [test-fn]
        (with-fresh-registrar test-fn))

      (use-fixtures :each fixture)

  Tests can `reg-event-db` / `reg-sub` / `reg-view` / etc. freely; the
  registrar is rolled back on the way out so those user registrations
  don't leak into the next test, while ns-load-time framework
  registrations (which are part of the captured snapshot) survive."
  [body-fn]
  (let [snap (snapshot-registrar)]
    (try
      (body-fn)
      (finally
        (restore-registrar! snap)))))

;; ---- full per-test runtime reset ------------------------------------------

(def ^:private reset-hook-table
  "Late-bind hook keys fired by `make-reset-runtime-fixture` to drop per-process
  test state — one row per optional artefact. Each entry pairs the hook key
  with a `:phase` (when it fires relative to `adapter/dispose-adapter!`) and
  the design bead that introduced the artefact. The driver
  `run-reset-hooks!` walks the table in registration order and no-ops a row
  when its hook is unregistered (artefact absent from the classpath).

  Order is load-bearing — non-late-bind steps interleave with the hooks:
    1. `(reset! frame/frames {})`
    2. `:pre-dispose` hooks (flows resets, schemas clear)
    3. `(adapter/dispose-adapter!)`
    4. `:post-dispose` hooks (machines, routing, http, epoch, adapter-warn)
  Splitting the table by `:phase` lets the driver fire each contiguous run
  in one pass while keeping the cross-cutting prose in one place.

  Per-row rationale:

    :flows/reset-flows!              — drop the per-frame flow registry.
    :flows/reset-last-inputs!        — drop the dirty-check last-inputs
                                       map paired with the flow registry.
    :schemas/clear-by-frame!         — clear per-frame schema registrations.
                                       Paired with `:schemas/snapshot-by-frame`
                                       + `:schemas/restore-by-frame!` for
                                       snapshot/restore around the test body.
    :machines/reset-timers!          — cancel in-flight `:after` wall-clock
                                       timers so a stale timer from a
                                       sibling test can't survive.
    :machines/reset-spawn-order!     — drop the per-frame spawn-order
                                       channel (rf2-vsigt) so a stale
                                       entry from a sibling test can't
                                       contaminate a frame-destroy walk.
    :routing/reset-counters!         — reset the route-registration counter
                                       so reg-index is deterministic across
                                       fixture runs.
    :http/clear-all-in-flight!       — drop the in-flight managed-request
                                       registry.
    :epoch/clear-history!            — drop the per-frame epoch ring buffer.
    :epoch/clear-epoch-listeners!          — drop the epoch-settled callback
                                       registry.
    :adapter/clear-warn-once-caches! — clear per-adapter
                                       `warned-non-dom-roots` warn-once
                                       caches. Chained — re-frame.views,
                                       and the helix / uix adapters each
                                       register a clear-step.

  Adding a new artefact's reset becomes a one-row addition here."
  [{:hook :flows/reset-flows!              :phase :pre-dispose}
   {:hook :flows/reset-last-inputs!        :phase :pre-dispose}
   {:hook :schemas/clear-by-frame!         :phase :pre-dispose}
   {:hook :machines/reset-timers!          :phase :post-dispose}
   {:hook :machines/reset-spawn-order!     :phase :post-dispose}
   {:hook :routing/reset-counters!         :phase :post-dispose}
   {:hook :http/clear-all-in-flight!       :phase :post-dispose}
   {:hook :epoch/clear-history!            :phase :post-dispose}
   {:hook :epoch/clear-epoch-listeners!          :phase :post-dispose}
   {:hook :adapter/clear-warn-once-caches! :phase :post-dispose}
   ;; Per Spec 015 — clear the per-(kind, id) marks table and the
   ;; per-frame sub-output propagation table so each test starts
   ;; from a clean classification slate. No-op when the marks
   ;; artefact is absent.
   {:hook :marks/clear-marks!              :phase :post-dispose}
   {:hook :marks/clear-sub-output-marks!   :phase :post-dispose}])

(defn- run-reset-hooks!
  "Driver: fire every `reset-hook-table` row whose `:phase` matches and
  whose producer has registered a fn. Rows with unregistered hooks no-op
  (artefact absent from the classpath)."
  [phase]
  (run! (fn [{:keys [hook]}]
          (when-let [f (late-bind/get-fn hook)]
            (f)))
        (filter #(= phase (:phase %)) reset-hook-table)))

(defn make-reset-runtime-fixture
  "Build a `clojure.test` / `cljs.test` `:each` fixture that resets the
  per-process re-frame runtime around each test.

  Per call (i.e. per test), the fixture:

    1. Captures the current registrar (so user-test registrations can be
       rolled back without losing ns-load-time framework / example
       registrations).
    2. Resets `frame/frames` to `{}`, plus `flows/flows` and
       `schemas/schemas-by-frame` (when those artefacts are loaded —
       reset is late-bound so JVM tests that don't pull them in are
       unaffected).
    3. Disposes the currently-installed substrate adapter.
    4. Cancels the machines' in-flight `:after` wall-clock timers.
    5. Clears trace listeners and adapter warn-once caches
       (`warned-non-dom-roots` across re-frame.views and the helix /
       uix adapters).
    6. If an `:adapter` was supplied, installs it and ensures the
       `:rf/default` frame. Otherwise leaves adapter installation to
       the test (or to a separate fixture).
    7. If an `:init-fn` was supplied, invokes it (zero-arg). Use this
       hook for per-suite setup that needs the registrar / adapter
       live — e.g. seeding test data into the just-installed adapter's
       app-db.
    8. Runs the test.
    9. Restores the registrar to the captured snapshot.
   10. Resets `frame/frames` back to `{}` for symmetry, and (when their
       artefacts are loaded) `flows/flows` and `schemas/schemas-by-frame`.

  Steps 9–10 run in a `finally` block so they fire even on test
  exceptions.

  Options (all optional):
    :adapter      — substrate adapter to install. If omitted, no adapter
                    is installed by the fixture.
    :init-fn      — zero-arg fn run after adapter install, before the test.
    :clear-kinds  — collection of registry kinds (e.g. `[:app-schema]`) to
                    `clear-kind!` AFTER the snapshot capture and BEFORE
                    the test body runs. Use this when example apps
                    loaded by ns-load time register entries under a
                    kind your test wants a clean slate for. The
                    snapshot still includes those entries, so they're
                    restored on the way out — they only disappear for
                    the duration of the test.

  Returns a fixture fn suitable for `(use-fixtures :each ...)`.

  Example (CLJS):

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter reagent-adapter/adapter}))

  Example with example-app collision avoidance — schemas tests want a
  clean :app-schema slate without losing nine-states.core's other
  registrations:

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter     reagent-adapter/adapter
           :clear-kinds [:app-schema]}))

  Example (JVM, default plain-atom adapter):

      (use-fixtures :each
        (test-support/make-reset-runtime-fixture
          {:adapter plain-atom/adapter}))"
  ([] (make-reset-runtime-fixture {}))
  ([{:keys [adapter init-fn clear-kinds]}]
   (fn [test-fn]
     ;; Late-bind: when the schemas artefact is loaded, snap and restore
     ;; the per-frame schema registry around the test body. The clear
     ;; step in the mid-body run fires from `reset-hook-table` —
     ;; `clear-fn` is captured here only for the `:clear-kinds
     ;; [:app-schema]` branch (the schemas artefact owns its own per-
     ;; frame side-table, not a registrar kind).
     (let [snap          (snapshot-registrar)
           snapshot-fn   (late-bind/get-fn :schemas/snapshot-by-frame)
           clear-fn      (late-bind/get-fn :schemas/clear-by-frame!)
           restore-fn    (late-bind/get-fn :schemas/restore-by-frame!)
           schemas-snap  (when snapshot-fn (snapshot-fn))]
       (try
         (reset! frame/frames {})
         (run-reset-hooks! :pre-dispose)
         (adapter/dispose-adapter!)
         (run-reset-hooks! :post-dispose)
         (trace-tooling/clear-trace-listeners!)
         (event-emit/clear-event-emit-listeners!)
         (when adapter
           (adapter/install-adapter! adapter)
           (frame/ensure-default-frame!))
         (doseq [k clear-kinds]
           (registrar/clear-kind! k)
           ;; The `:clear-kinds [:app-schema]` opt is preserved as the
           ;; test-support convention for "give me a clean app-schema
           ;; slate"; the registrar `clear-kind!` above is a no-op for
           ;; this kind (`:app-schema` is the schemas artefact's per-
           ;; frame side-table, not a registrar kind), and this branch
           ;; dispatches the actual reset through the schemas clear-fn.
           (when (and (= k :app-schema) clear-fn)
             (clear-fn)))
         (when init-fn (init-fn))
         (test-fn)
         (finally
           (restore-registrar! snap)
           (when restore-fn (restore-fn schemas-snap))
           (reset! frame/frames {})
           (when-let [reset-flows! (late-bind/get-fn :flows/reset-flows!)]
             (reset-flows!))))))))

;; ---- test-flavoured helpers (rf2-0l3s / rf2-hkr5) -------------------------
;;
;; Two thin wrappers over `dispatch-sync!` and `frame-app-db-value` for
;; ergonomic test code. The fixture machinery above carries the heavy
;; lifting; these helpers are composition sugar.
;;
;; Per Spec 008 §Built-in test-runner namespace, both live under
;; re-frame.test-support so users `(:require [re-frame.test-support :as t])`
;; once and reach the full testing surface — including dispatch-sequence
;; and the assert-*-equals fn-family — without an additional require.

(defn- resolve-frame
  "Frame-resolution chain shared by the helpers below:
     1. `:frame` key in opts when supplied;
     2. `(frame/current-frame)` — picks up `with-frame` bindings,
        defaults to `:rf/default`."
  [opts]
  (or (:frame opts) (frame/current-frame)))

(defn dispatch-sequence
  "Fire each event in `events` synchronously, in order, against the
  resolved frame. Returns the final app-db value.

  Each event is delivered via `dispatch-sync!`, which runs the handler
  and drains the queue to fixed point before returning. The drain
  settles between events, so observable state between calls reflects
  committed effects.

  Options (all optional):
    :after-each — fn of `(db, event)` invoked after each event's drain
                  settles. Useful for capturing intermediate states or
                  per-step assertions.
    :frame      — frame id. Defaults to `(current-frame)`, which is
                  `:rf/default` outside a `with-frame` binding.

  Per Spec 008 §Normative surface and the rf2-hkr5 / rf2-0l3s decision.

  Example — assert final state:

      (dispatch-sequence [[:counter/inc] [:counter/inc] [:counter/dec]])
      ;; => {:counter/value 1}

  Example — capture intermediate states:

      (let [seen (atom [])]
        (dispatch-sequence [[:counter/inc] [:counter/inc]]
                           {:after-each (fn [db ev] (swap! seen conj [ev db]))})
        @seen)"
  ([events] (dispatch-sequence events {}))
  ([events {:keys [after-each] :as opts}]
   (let [frame-id (resolve-frame opts)
         ;; Per rf2-t1lxr: test-fixture-driven dispatches default to
         ;; :rf/dispatch-origin :test-harness so Causa's L2 timeline +
         ;; per-origin filters can discriminate test-driven cascades
         ;; from user-origin events. Caller may override.
         dispatch-opts (cond-> {:frame frame-id :rf/dispatch-origin :test-harness}
                         (contains? opts :origin) (assoc :origin (:origin opts))
                         (contains? opts :rf/dispatch-origin)
                         (assoc :rf/dispatch-origin (:rf/dispatch-origin opts)))]
     (doseq [ev events]
       (router/dispatch-sync! ev dispatch-opts)
       (when after-each
         (after-each (frame/frame-app-db-value frame-id) ev)))
     (frame/frame-app-db-value frame-id))))

(defn assert-path-equals
  "Assert `(= expected-val (get-in app-db path))` against the resolved
  frame's `app-db`. Mismatch is reported via `clojure.test/is` — the
  failure carries the actual value so the diagnostic is one line.

  Call shapes:

    (assert-path-equals path expected-val)
    (assert-path-equals path expected-val {:frame :test/foo})

  Frame-resolution chain matches `dispatch-sequence`: `:frame` opt →
  `(current-frame)` → `:rf/default`.

  Returns `true` when the assertion passes, `false` otherwise — the
  `clojure.test` failure has already been reported in either case, so
  callers rarely care about the boolean.

  Mirrors the `:rf.assert/path-equals` event used inside Story `:play`
  blocks (per Spec 007 §Play functions). The fn-side and the event-side
  share the same name root so a reader navigating between the two
  surfaces does not need a translation table.

  Per Spec 008 §Normative surface and the rf2-hkr5 / rf2-0l3s / rf2-8j9m6
  decisions."
  ([path expected-val]
   (assert-path-equals path expected-val nil))
  ([path expected-val opts]
   (let [opts     (or opts {})
         frame-id (resolve-frame opts)
         actual   (get-in (frame/frame-app-db-value frame-id) path)
         pass?    (= expected-val actual)]
     (ctest/do-report
       {:type     (if pass? :pass :fail)
        :message  (str "assert-path-equals mismatch at path " (pr-str path)
                       " on frame " frame-id)
        :expected expected-val
        :actual   actual})
     pass?)))

(defn assert-db-equals
  "Assert `(= expected-db (frame-app-db-value frame))` against the
  resolved frame's `app-db`. Mismatch is reported via `clojure.test/is`
  — the failure carries the actual value so the diagnostic is one line.

  Call shapes:

    (assert-db-equals expected-db)
    (assert-db-equals expected-db {:frame :test/foo})

  Frame-resolution chain matches `dispatch-sequence`: `:frame` opt →
  `(current-frame)` → `:rf/default`.

  Returns `true` when the assertion passes, `false` otherwise — the
  `clojure.test` failure has already been reported in either case, so
  callers rarely care about the boolean.

  No `:rf.assert/*` event analog exists for the full-db form (the event
  family is path-keyed); `assert-db-equals` is the companion fn-only
  shape carried alongside `assert-path-equals`.

  Per Spec 008 §Normative surface and the rf2-hkr5 / rf2-0l3s / rf2-8j9m6
  decisions."
  ([expected-db]
   (assert-db-equals expected-db nil))
  ([expected-db opts]
   (let [opts     (or opts {})
         frame-id (resolve-frame opts)
         actual   (frame/frame-app-db-value frame-id)
         pass?    (= expected-db actual)]
     (ctest/do-report
       {:type     (if pass? :pass :fail)
        :message  (str "assert-db-equals full-db mismatch on frame " frame-id)
        :expected expected-db
        :actual   actual})
     pass?)))

;; ---- deterministic wait helper (rf2-ka3n6 / rf2-fun38) -------------------
;;
;; Replaces incidental fixed `Thread/sleep N` / `js/setTimeout` waits that
;; exist to let an *observable* event (router drain, cascade settle, sub
;; re-fire, in-flight registry entries appearing/clearing) complete.
;;
;; NOT for timer-semantics tests — those should keep their sleep and
;; annotate that intent locally (the sleep IS the contract under test:
;; grace-period elapse, throttle/debounce window, host-clock advancement,
;; "prove a thing did NOT happen within window N").
;;
;; Per-platform shape (rf2-fun38):
;;   JVM:  synchronous — returns the truthy value, throws on timeout.
;;   CLJS: async       — returns a `js/Promise`. Resolves with the truthy
;;                       value on success, rejects with an `ex-info`-style
;;                       error on timeout. Designed to compose with
;;                       `cljs.test/async`:
;;
;;                         (deftest something
;;                           (async done
;;                             (-> (test-support/poll-until
;;                                   #(some? (rf/get-frame-db :rf/default)))
;;                                 (.then (fn [db] (is (...)) (done)))
;;                                 (.catch (fn [e] (is false (.-message e))
;;                                                 (done))))))
;;
;; Single name across platforms — read sites are mechanical conversions.
;; The opts map is identical (`:timeout-ms` / `:interval-ms` / `:label`).
;; A central helper means CI flake budgets land in one place.

(defn- poll-timeout-error
  "Shared timeout-error constructor — same shape JVM / CLJS so test code
  that pattern-matches on `:rf.test/poll-timeout` works on either runtime."
  [label elapsed-ms]
  (ex-info (str "poll-until timed out"
                (when label (str " — " label)))
           {:rf.test/poll-timeout true
            :elapsed-ms elapsed-ms
            :label label}))

#?(:clj
   (defn poll-until
     "Bounded-deadline poll for `(pred)` to return truthy. Returns the
     truthy value on success; throws `ex-info` on timeout with
     `:rf.test/poll-timeout` `true`, the elapsed ms, and the supplied
     `:label` (when given) to identify the assertion site.

     `opts` (all optional):
       :timeout-ms   default 2000 — overall deadline.
       :interval-ms  default 5    — sleep between probes.
       :label        string/keyword used in the timeout message.

     Use this in JVM tests that previously called `(Thread/sleep N)` to
     wait for the async router to drain, an event cascade to settle, or
     a sub to re-fire. The deadline is generous; tests fail fast on a
     truly stuck condition, not on CI scheduler jitter."
     ([pred] (poll-until pred nil))
     ([pred opts]
      (let [{:keys [timeout-ms interval-ms label]
             :or   {timeout-ms 2000 interval-ms 5}} opts
            start    (System/currentTimeMillis)
            deadline (+ start timeout-ms)]
        (loop []
          (let [v (pred)]
            (cond
              v v
              (>= (System/currentTimeMillis) deadline)
              (throw (poll-timeout-error
                       label (- (System/currentTimeMillis) start)))
              :else (do (Thread/sleep ^long interval-ms) (recur)))))))))

#?(:cljs
   (defn poll-until
     "Bounded-deadline poll for `(pred)` to return truthy. Returns a
     `js/Promise` that resolves with the truthy value on success or
     rejects with an `ex-info`-style error carrying
     `:rf.test/poll-timeout` `true`, `:elapsed-ms`, and `:label` on
     timeout.

     `opts` (all optional):
       :timeout-ms   default 2000 — overall deadline.
       :interval-ms  default 5    — gap (ms) between probes; scheduled
                                    via `js/setTimeout`.
       :label        string/keyword used in the timeout message.

     `pred` is invoked synchronously on each tick. If `pred` itself
     returns a `js/Promise`, the returned promise is awaited and its
     resolved value drives the truthy check — so `pred` can be either
     synchronous (the common case) or `async`/Promise-returning.

     Use this in CLJS tests under `cljs.test/async` that previously
     chained nested `js/setTimeout` calls to wait for a router drain,
     event cascade, or sub re-fire. The Promise composes with `.then` /
     `.catch` and integrates cleanly with `async done`:

         (deftest drains
           (async done
             (-> (test-support/poll-until
                   #(= 3 (:n (rf/get-frame-db :rf/default)))
                   {:label \"counter reached 3\"})
                 (.then (fn [_] (is (= 3 ...)) (done)))
                 (.catch (fn [e] (is false (.-message e)) (done))))))"
     ([pred] (poll-until pred nil))
     ([pred opts]
      (let [{:keys [timeout-ms interval-ms label]
             :or   {timeout-ms 2000 interval-ms 5}} opts
            start    (.now js/Date)
            deadline (+ start timeout-ms)]
        (js/Promise.
          (fn [resolve reject]
            (letfn [(settle [v]
                      (cond
                        v (resolve v)
                        (>= (.now js/Date) deadline)
                        (reject (poll-timeout-error
                                  label (- (.now js/Date) start)))
                        :else (js/setTimeout tick interval-ms)))
                    (tick []
                      (let [raw (try (pred) (catch :default _ false))]
                        (if (instance? js/Promise raw)
                          (-> ^js/Promise raw
                              (.then settle)
                              (.catch (fn [_] (settle false))))
                          (settle raw))))]
              (tick))))))))
