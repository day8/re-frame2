(ns re-frame.trace-listener-post-drain-settled-state-cljs-test
  "rf2-uoy6m — the post-drain settled-state contract must hold on EVERY host,
  CLJS included.

  ## The defect this pins (rf2-uoy6m)

  PR #6365 made Spec 009's listener timing a cross-platform contract: an
  internal, drain-owned trace emit is delivered at the post-drain boundary, so a
  listener never observes a partially settled state. The JVM path defers; the
  shipped CLJS path did the opposite — `re-frame.trace/call-with-deferred-listener-
  delivery` was `:cljs (f)`, so `deliver-to-tooling!` fanned a drain-owned emit
  out INLINE while the frame's drain was still active. A CLJS listener therefore
  observed the db BEFORE the in-flight event committed — a different temporal API
  from JVM, contradicting the PR's own cross-platform claim.

  ## The probe (identical on both hosts)

  `:rf.event/run-start` is emitted while the drain owns the frame's lock, BEFORE
  the event handler's `:db` effect commits. A listener reads the frame's app-db
  from inside its run-start callback:

    - post-drain delivery (the contract): the callback runs after the whole drain
      unwinds, so the db is already SETTLED — the `:uoy6m/settled?` marker the
      handler wrote is present.
    - inline delivery (the CLJS defect): the callback runs mid-drain, before the
      commit, so the marker is ABSENT.

  The assertion is an OBSERVABLE OUTCOME — was the settled marker visible? — not
  an exception. On CLJS this class never throws: an inline read simply returns the
  un-settled db. The same deftest runs on JVM (already deferred → true) and CLJS
  (the fix makes it true), so a single cross-host lever pins uniformity.

  Runs on both hosts: `-cljs-test` matches the shadow `:node-test` ns-regexp and
  cognitect-test-runner's `*-test` discovery."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.trace                :as trace]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- Posture: dev-only, declared by `^:requires-debug` (rf2-d2841) ---------
;; Trace machinery end to end: under `-Dre-frame.debug=false` `trace/emit` is a
;; no-op, so there is no semantic residue to run under that posture, and a
;; `(when interop/debug-enabled? ...)` split -- the shape the rest of rf2-d2841
;; used -- would leave EMPTY deftests reporting green (class 2).  Every deftest
;; below is therefore TAGGED, and the production-gate lane skips the tag rather
;; than the file: the namespace is still LOADED there, so a load-time failure
;; under the gate still reddens the job, and an untagged new deftest joins that
;; lane BY DEFAULT.  Mechanism + rationale: `scripts/test-core-prod-gate.sh`.

(deftest ^:requires-debug run-start-listener-observes-settled-db-uniformly
  ;; A drain-owned run-start listener must see the event's SETTLED db on every
  ;; host. Pre-fix CLJS delivered inline and the listener saw the un-settled db.
  (let [seen-at-run-start (atom :not-recorded)]
    (rf/reg-event :uoy6m/settle
      (fn [{:keys [db]} _] {:db (assoc db :uoy6m/settled? true)}))
    (trace/register-listener! ::probe
      (fn [ev]
        (when (and (= :rf.event/run-start (:operation ev))
                   (= :uoy6m/settle (first (-> ev :tags :rf.event/v)))
                   (= :not-recorded @seen-at-run-start))
          (reset! seen-at-run-start
                  (boolean (:uoy6m/settled? (rf/app-db-value :rf/default)))))))
    (try
      (rf/dispatch-sync [:uoy6m/settle] {:frame :rf/default})
      ;; Control: the event's :db effect is visible once dispatch-sync returns,
      ;; on both hosts. Proves the marker is a real settled-state signal (moves
      ;; the assertion count on both hosts regardless of the delivery seam).
      (is (true? (:uoy6m/settled? (rf/app-db-value :rf/default)))
          "control: the event's :db effect committed after dispatch-sync")
      ;; Contract: the run-start emit is drain-owned, so its listener delivery is
      ;; deferred to the post-drain boundary and observes the SETTLED db —
      ;; identically on JVM and CLJS.
      (is (true? @seen-at-run-start)
          (str "the run-start listener observed a PARTIALLY SETTLED db "
               "(:uoy6m/settled? absent) — drain-owned listener delivery ran "
               "inline while the drain was active, not at the post-drain "
               "boundary. Recorded: " (pr-str @seen-at-run-start)))
      (finally
        (trace/unregister-listener! ::probe)))))
