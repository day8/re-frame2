(ns re-frame.trace-listener-reentrant-dispatch-sync-deferral-test
  "rf2-6t6qk — PR #6365's post-drain deferral must also cover the MOST important
  reentrant path: a trace listener that calls `dispatch-sync`.

  ## The defect this pins (rf2-6t6qk)

  `re-frame.trace.tooling/deliver-to-tooling!` tested the reentrant `*fanout-ctx*`
  fast path BEFORE the post-drain deferral scope. During an outer listener
  fan-out `*fanout-ctx*` is bound; if that listener calls `dispatch-sync` into a
  frame F, `re-frame.router/drain-block!` opens the deferral scope AND acquires
  F's `:drain-lock`, but each nested drain trace still took the earlier
  `*fanout-ctx*` branch — appending to the outer schedule and driving it INLINE.
  Arbitrary listener code therefore ran while the framework held F's drain lock,
  the exact negation of the rf2-wxy1c charter (\"arbitrary listener code is never
  invoked nor awaited while the framework owns any target frame drain lock\").

  ## The probe

  This manifests SAME-THREAD — no second thread is needed. A `::trigger` listener
  reacts to a clean, frameless `emit!` (so its outer fan-out is in flight and
  `*fanout-ctx*` is bound) by `dispatch-sync`-ing into `:rf/default`, which the
  same thread then drains synchronously. A `::probe` listener records, for every
  drain-owned emit of that nested drain, whether `:rf/default`'s `:drain-lock` is
  held at the instant its callback runs.

  Pre-fix the probe observed the lock HELD on the nested `:rf.event/run-start`
  (and the run-end / trailer emits): the reentrant fast path delivered them under
  the lock. Post-fix every drain-owned emit is deferred and delivered at the
  post-drain boundary — integrated back into the still-active outer schedule so
  outer-before-inner ordering and synchronous completion survive — so the probe
  sees the lock FREE on every one.

  This asserts an OBSERVABLE OUTCOME (was the lock held?), never an exception:
  the reentrant bypass does not throw, it silently runs listener code under the
  lock. JVM-only (`.clj`): the drain-lock read is only meaningful where a real
  lock cell is contended; the platform-uniform settled-state contract has its own
  cross-host suite (`trace-listener-reentrant-settled-state-cljs-test`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Load-bearing require (mirrors the rf2-jl75r / rf2-wxy1c suites):
            ;; with the epoch artefact on the classpath the per-event settle also
            ;; emits the cascade trailers on the drainer thread while the
            ;; drain-lock is held, so the deferral seam is exercised for the
            ;; trailer emits too and not only the in-run emits.
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Loop the deterministic proof so a green run is determinism, not a lucky
;; interleaving. Env-overridable for a heavier local soak.
(def ^:private iters
  (or (some-> (System/getenv "RF2_6T6QK_ITERS") Long/parseLong)
      25))

(defn- drain-lock-held?
  "True iff `frame-id`'s `:drain-lock` is currently taken — the direct read of
  the single-drainer cell the router CAS-acquires for a drain pass. Read from
  inside a listener callback this answers the rf2-6t6qk / rf2-wxy1c acceptance
  question literally: is arbitrary listener code running while the framework owns
  this frame's drain lock?"
  [frame-id]
  (boolean (some-> (rf.frame/frame frame-id) :drain-lock deref)))

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `rf.trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug listener-initiated-dispatch-sync-never-runs-listeners-under-drain-lock
  (testing (str "a listener that dispatch-syncs into F from inside an outer "
                "fan-out never has F's drain-owned traces fanned out while F's "
                ":drain-lock is held (" iters " iterations)")
    (dotimes [iter iters]
      (rf/reg-event :6t6qk/settle
        (fn [{:keys [db]} _] {:db (assoc db :6t6qk/settled? true)}))

      (let [;; Every drain-owned emit of :rf/default seen by the probe, paired
            ;; with whether the frame's drain-lock was held at callback time.
            observed  (atom [])
            fired?    (atom false)]
        ;; The probe: pure observer. Records the ownership state for each
        ;; drain-owned run emit of the nested :rf/default drain.
        (rf.trace/register-listener! ::probe
          (fn [ev]
            (when (and (= :rf/default (rf.trace/frame-of ev))
                       (contains? #{:rf.event/run-start :rf.event/run-end}
                                  (:operation ev)))
              (swap! observed conj
                     [(:operation ev) (drain-lock-held? :rf/default)]))))
        ;; The trigger: reacts to the clean, frameless trigger emit (so the
        ;; outer fan-out is in flight and *fanout-ctx* is bound) by
        ;; dispatch-syncing into :rf/default, which this thread drains inline.
        (rf.trace/register-listener! ::trigger
          (fn [ev]
            (when (and (= :6t6qk/trigger (:operation ev))
                       (compare-and-set! fired? false true))
              (rf/dispatch-sync [:6t6qk/settle] {:frame :rf/default}))))
        (try
          (rf.trace/emit! :info :6t6qk/trigger {})
          ;; The nested dispatch-sync must have actually settled — proves the
          ;; reentrant path was exercised (assertion count moves either way).
          (is (true? (:6t6qk/settled? (rf/app-db-value :rf/default)))
              (str "iter " iter ": the listener-initiated dispatch-sync did not "
                   "settle :rf/default"))
          (is (seq @observed)
              (str "iter " iter ": the probe never saw a nested drain-owned "
                   "emit — the reentrant path was not exercised"))
          (is (every? (comp false? second) @observed)
              (str "iter " iter ": a trace listener was invoked while "
                   ":rf/default's :drain-lock was held — the reentrant "
                   "dispatch-sync path bypassed post-drain deferral. Observed "
                   "[op lock-held?]: " (pr-str @observed)))
          (finally
            (rf.trace/unregister-listener! ::probe)
            (rf.trace/unregister-listener! ::trigger)))))))
