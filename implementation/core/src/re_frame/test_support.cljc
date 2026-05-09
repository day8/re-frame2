(ns re-frame.test-support
  "Test fixture helpers shared between JVM and CLJS test suites.

  ## Why this namespace exists (rf2-am9d, follow-up to rf2-coks / rf2-p8g8)

  Tests need per-test isolation of *user-test-registered* handlers, subs,
  views, machines, fx, etc. — without wiping *framework-shipped*
  registrations that landed at namespace-load time and (under CLJS)
  cannot be re-loaded at runtime.

  Earlier fixtures in the CLJS test suite reached for `registrar/clear-all!`,
  which is fundamentally hostile to CLJS isolation:

    - `re-frame.routing` registers `:rf/url-changed`, `:rf.route/navigate`,
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
  - [[reset-runtime-fixture]] — `clojure.test`/`cljs.test` `:each`
    fixture that snapshot/restores the registrar AND resets the
    per-process state held by frames / flows (when the flows artefact
    is loaded, rf2-tfw3) / adapter / machine counters / trace
    listeners.

  ### Test-flavoured helpers (rf2-0l3s / rf2-hkr5)
  - [[dispatch-sequence]] — fire a vector of events synchronously,
    optionally observing intermediate state via `:after-each`.
  - [[assert-state]] — assert against the current frame's app-db (full
    db or `path` form); failure is reported via `clojure.test/is`.
  - [[run-test-sync]] — v1 compatibility shim. v2's `dispatch-sync`
    drain is already synchronous so the macro is largely a body
    wrapper; included for mechanical migration of v1 test code."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            ;; re-frame.flows (Spec 013) ships as a separate artefact
            ;; (day8/re-frame-2-flows, rf2-tfw3). The reset fixture
            ;; touches the per-frame flows registry and the dirty-check
            ;; `last-inputs` map through the late-bind hook table —
            ;; when the flows artefact is not on the classpath the
            ;; lookups return nil and the flow reset steps no-op
            ;; (correct: there is no flow state to preserve).
            ;; re-frame.machines (Spec 005) ships as a separate artefact
            ;; (day8/re-frame-2-machines, rf2-xbtj) — the reset fixture
            ;; touches the machines spawn-counter through the late-bind
            ;; hook table when the artefact is loaded, no-ops when not.
            ;; re-frame.schemas (Spec 010) ships as a separate artefact
            ;; (day8/re-frame-2-schemas, rf2-p7va). The reset fixture
            ;; touches the per-frame schema registry through the
            ;; late-bind hook table — when the schemas artefact is not
            ;; on the classpath the lookups return nil and the schema
            ;; steps no-op (correct: there is no schema state to
            ;; preserve).
            ;; re-frame.http-managed (Spec 014) ships as a separate
            ;; artefact (day8/re-frame-2-http, rf2-5kpd). The reset
            ;; fixture clears the in-flight request registry through
            ;; the late-bind hook table — when the http artefact is
            ;; not on the classpath the lookup returns nil and the
            ;; clear step no-ops (correct: there is no request state
            ;; to clear).
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]
            [re-frame.router :as router]
            [re-frame.substrate.adapter :as adapter]
            #?(:clj  [clojure.test :as ctest]
               :cljs [cljs.test :as ctest :include-macros true])))

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

(defn reset-runtime-fixture
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
    4. Resets the machines spawn-counter.
    5. Clears trace listeners.
    6. If an `:adapter` was supplied, installs it and ensures the
       `:rf/default` frame. Otherwise leaves adapter installation to
       the test (or to a separate fixture).
    7. If an `:init-fn` was supplied, invokes it (zero-arg). Use this
       hook for per-suite setup that needs the registrar / adapter live
       — e.g. seeding test data into the just-installed adapter's
       app-db. (Routing's reg-counter and the machines spawn-counter
       are reset automatically when their respective artefacts are on
       the classpath; see steps 4 and the late-bind block above.)
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
        (test-support/reset-runtime-fixture
          {:adapter reagent-adapter/adapter}))

  Example with example-app collision avoidance — schemas tests want a
  clean :app-schema slate without losing nine-states.core's other
  registrations:

      (use-fixtures :each
        (test-support/reset-runtime-fixture
          {:adapter     reagent-adapter/adapter
           :clear-kinds [:app-schema]}))

  Example (JVM, default plain-atom adapter):

      (use-fixtures :each
        (test-support/reset-runtime-fixture
          {:adapter plain-atom/adapter}))"
  ([] (reset-runtime-fixture {}))
  ([{:keys [adapter init-fn clear-kinds]}]
   (fn [test-fn]
     (let [snap          (snapshot-registrar)
           ;; Late-bind: when the schemas artefact is loaded, snap and
           ;; restore the per-frame schema registry. When it isn't,
           ;; the hooks return nil and the schema steps no-op. Per
           ;; rf2-p7va — schemas ships in day8/re-frame-2-schemas and
           ;; this namespace must not statically require it.
           snapshot-fn   (late-bind/get-fn :schemas/snapshot-by-frame)
           clear-fn      (late-bind/get-fn :schemas/clear-by-frame!)
           restore-fn    (late-bind/get-fn :schemas/restore-by-frame!)
           schemas-snap  (when snapshot-fn (snapshot-fn))]
       (try
         (reset! frame/frames {})
         ;; Late-bind: when the flows artefact is loaded, clear the
         ;; per-frame flow registry and the dirty-check `last-inputs`
         ;; map. When it isn't, the hooks return nil and the flow
         ;; reset steps no-op (correct: there is no flow state to
         ;; reset). Per rf2-tfw3 — flows ships in
         ;; day8/re-frame-2-flows.
         (when-let [reset-flows! (late-bind/get-fn :flows/reset-flows!)]
           (reset-flows!))
         (when-let [reset-li! (late-bind/get-fn :flows/reset-last-inputs!)]
           (reset-li!))
         (when clear-fn (clear-fn))
         (adapter/dispose-adapter!)
         ;; Late-bind: when the machines artefact is loaded, reset the
         ;; spawn-counter so id allocation is stable across fixture
         ;; runs. When it isn't, the hook returns nil and this is a
         ;; no-op (correct: there is no counter state to reset).
         ;; Per rf2-xbtj — machines ships in day8/re-frame-2-machines.
         (when-let [reset-counters! (late-bind/get-fn :machines/reset-counters!)]
           (reset-counters!))
         ;; Late-bind: when the routing artefact is loaded, reset the
         ;; route registration counter so reg-index is deterministic
         ;; across fixture runs. When it isn't, the hook returns nil
         ;; and this is a no-op (correct: there is no counter state to
         ;; reset). Per rf2-k682 — routing ships in
         ;; day8/re-frame-2-routing.
         (when-let [reset-counters! (late-bind/get-fn :routing/reset-counters!)]
           (reset-counters!))
         ;; Late-bind: when the http artefact is loaded, drop the
         ;; in-flight request registry so a stale handle from a
         ;; sibling test cannot survive into this one. When it isn't,
         ;; the hook returns nil and this is a no-op (correct: there
         ;; is no request state to clear). Per rf2-5kpd — http ships
         ;; in day8/re-frame-2-http.
         (when-let [clear-in-flight! (late-bind/get-fn :http/clear-all-in-flight!)]
           (clear-in-flight!))
         ;; Late-bind: when the epoch artefact is loaded, drop the
         ;; per-frame epoch ring buffer and any registered listeners
         ;; so a previous test's recorded epochs / callbacks cannot
         ;; survive into this one. When it isn't, the hooks return
         ;; nil and these are no-ops (correct: there is no epoch state
         ;; to clear). Per rf2-lt4e — epoch ships in day8/re-frame-2-epoch.
         (when-let [clear-history! (late-bind/get-fn :epoch/clear-history!)]
           (clear-history!))
         (when-let [clear-epoch-cbs! (late-bind/get-fn :epoch/clear-epoch-cbs!)]
           (clear-epoch-cbs!))
         (trace/clear-trace-cbs!)
         (when adapter
           (adapter/install-adapter! adapter)
           (frame/ensure-default-frame!))
         (doseq [k clear-kinds]
           (registrar/clear-kind! k)
           ;; Per-frame schema map is the authoritative store; clear it
           ;; in lockstep when the caller asks for an :app-schema reset.
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
;; Three thin wrappers over `dispatch-sync!` and `frame-app-db-value` for
;; ergonomic test code. The fixture machinery above carries the heavy
;; lifting; these helpers are composition sugar.
;;
;; Per Spec 008 §Built-in test-runner namespace, all three live under
;; re-frame.test-support so users `(:require [re-frame.test-support :as t])`
;; once and reach the full testing surface — including dispatch-sequence,
;; assert-state, run-test-sync — without an additional require.

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
         dispatch-opts (cond-> {:frame frame-id}
                         (contains? opts :origin) (assoc :origin (:origin opts)))]
     (doseq [ev events]
       (router/dispatch-sync! ev dispatch-opts)
       (when after-each
         (after-each (frame/frame-app-db-value frame-id) ev)))
     (frame/frame-app-db-value frame-id))))

(defn assert-state
  "Assert against the resolved frame's `app-db`. Mismatch is reported
  via `clojure.test/is` — the failure carries the actual value so the
  diagnostic is one line.

  Two call shapes:

    (assert-state expected-db)
    ;; full-db form: (= expected-db (frame-app-db-value frame))

    (assert-state path expected-val)
    ;; path form:    (= expected-val (get-in (frame-app-db-value frame) path))

  Both shapes accept a trailing options map:

    (assert-state expected-db {:frame :test/foo})
    (assert-state path expected-val {:frame :test/foo})

  Frame-resolution chain matches `dispatch-sequence`: `:frame` opt →
  `(current-frame)` → `:rf/default`.

  Returns `true` when the assertion passes, `false` otherwise — the
  `clojure.test` failure has already been reported in either case, so
  callers rarely care about the boolean.

  Per Spec 008 §Normative surface and the rf2-hkr5 / rf2-0l3s decision."
  ([expected-db]
   (assert-state expected-db nil))
  ([path-or-expected expected-or-opts]
   ;; Disambiguate by shape of the second arg:
   ;; - if path-or-expected is a vector (path), the second arg is the
   ;;   expected value;
   ;; - otherwise the second arg, if a map, is opts (full-db form).
   (cond
     (and (vector? path-or-expected)
          (not (map? expected-or-opts)))
     (assert-state path-or-expected expected-or-opts nil)

     :else
     (let [opts        (or expected-or-opts {})
           frame-id    (resolve-frame opts)
           actual      (frame/frame-app-db-value frame-id)
           expected-db path-or-expected
           pass?       (= expected-db actual)]
       (ctest/do-report
         {:type     (if pass? :pass :fail)
          :message  (str "assert-state full-db mismatch on frame " frame-id)
          :expected expected-db
          :actual   actual})
       pass?)))
  ([path expected-val opts]
   (let [opts     (or opts {})
         frame-id (resolve-frame opts)
         actual   (get-in (frame/frame-app-db-value frame-id) path)
         pass?    (= expected-val actual)]
     (ctest/do-report
       {:type     (if pass? :pass :fail)
        :message  (str "assert-state mismatch at path " (pr-str path)
                       " on frame " frame-id)
        :expected expected-val
        :actual   actual})
     pass?)))

#?(:clj
   (defmacro run-test-sync
     "v1 compatibility shim for `re-frame-test/run-test-sync`. In v2
     `dispatch-sync` already drains synchronously to fixed point, so this
     macro is largely a body wrapper — it exists so v1 test code that
     called `re-frame.test/run-test-sync` ports to v2 by a mechanical
     namespace rename (per [MIGRATION §M-25](MIGRATION.md), and rf2-8hcb's
     `re-frame.test` → `re-frame.test-support` rename).

     The macro snapshots and restores the registrar around `body` so
     test-local registrations are rolled back even if the body throws.
     This matches v1's `run-test-sync` behaviour of giving each test
     block a fresh registrar baseline.

     Example:

         (deftest legacy-flow
           (run-test-sync
             (rf/reg-event-db :counter/inc (fn [db _] (update db :n inc)))
             (rf/dispatch-sync [:counter/inc])
             (is (= 1 (:n (rf/get-frame-db :rf/default))))))

     Per Spec 008 §`re-frame-test` library compatibility."
     [& body]
     `(with-fresh-registrar (fn [] ~@body))))
