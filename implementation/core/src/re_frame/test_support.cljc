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
      `:rf.nav/scroll` (and friends) at ns-load.
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

  - [[snapshot-registrar]] — capture the current registrar state.
  - [[restore-registrar!]] — restore the registrar to a captured snapshot.
  - [[with-fresh-registrar]] — bracket a thunk with snapshot + restore.
  - [[reset-runtime-fixture]] — `clojure.test`/`cljs.test` `:each`
    fixture that snapshot/restores the registrar AND resets the
    per-process state held by frames / flows / adapter / machine
    counters / trace listeners."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.machines :as machines]
            [re-frame.schemas :as schemas]
            [re-frame.trace :as trace]
            [re-frame.substrate.adapter :as adapter]))

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
    2. Resets `frame/frames`, `flows/flows`, and `schemas/schemas-by-frame`
       to `{}`.
    3. Disposes the currently-installed substrate adapter.
    4. Resets the machines spawn-counter.
    5. Clears trace listeners.
    6. If an `:adapter` was supplied, installs it and ensures the
       `:rf/default` frame. Otherwise leaves adapter installation to
       the test (or to a separate fixture).
    7. If an `:init-fn` was supplied, invokes it (zero-arg). Use this
       hook for per-suite setup that needs the registrar / adapter live
       — e.g. resetting routing counters via
       `(routing/reset-counters!)`.
    8. Runs the test.
    9. Restores the registrar to the captured snapshot.
   10. Resets `frame/frames`, `flows/flows`, and
       `schemas/schemas-by-frame` back to `{}` for symmetry.

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
           schemas-snap  @schemas/schemas-by-frame]
       (try
         (reset! frame/frames {})
         (reset! flows/flows {})
         (reset! schemas/schemas-by-frame {})
         (adapter/dispose-adapter!)
         (machines/reset-counters!)
         (trace/clear-trace-cbs!)
         (when adapter
           (adapter/install-adapter! adapter)
           (frame/ensure-default-frame!))
         (doseq [k clear-kinds]
           (registrar/clear-kind! k)
           ;; Per-frame schema map is the authoritative store; clear it
           ;; in lockstep when the caller asks for an :app-schema reset.
           (when (= k :app-schema)
             (reset! schemas/schemas-by-frame {})))
         (when init-fn (init-fn))
         (test-fn)
         (finally
           (restore-registrar! snap)
           (reset! schemas/schemas-by-frame schemas-snap)
           (reset! frame/frames {})
           (reset! flows/flows {})))))))
