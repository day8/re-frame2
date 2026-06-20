(ns re-frame.flows-rollback-dirty-check-test
  "A POST-commit app-db schema rollback rolls the flow dirty-check
  (`last-inputs`) bookkeeping back in lock-step with app-db.

  The flow transform runs as the router's OUTERMOST `:after` interceptor: the
  moment a flow recomputes it advances THIS frame's `last-inputs` row and
  folds the output into the chain's PENDING `:db` effect. Whether that pending
  `:db` becomes DURABLE, however, is decided AFTER the chain returns, in
  `commit-db-effect!`: a post-commit schema / machine-data validation failure
  rolls app-db back to the pre-handler value. `run-flows-on-db`'s OWN
  throw-path snapshot/restore does not cover that — the rollback lands OUTSIDE
  it. If the advanced `last-inputs` row survived such a rollback, the
  dirty-check would believe the flow up-to-date even though app-db was restored
  (the flow output gone): the next clean drain would see `=`-equal inputs, SKIP
  the flow, and the output would never re-materialise.

  So `flows-after-interceptor` snapshots the draining frame's `last-inputs`
  rows BEFORE the flow transform advances them (via the
  `:flows/snapshot-last-inputs` hook) and stashes the snapshot on the ctx.
  `commit-db-effect!`'s rollback arm restores it (via
  `:flows/restore-last-inputs!`) the instant it rolls app-db back — the exact
  mirror of the throw-path rollback, at the post-commit boundary the flows
  artefact cannot reach. Frame-scoped: a sibling frame's container is a
  different atom and is untouched.

  The validator seam is the pluggable predicate seam the rest of Spec 010
  uses (`set-schema-fns!`) so the test exercises the exact production
  rollback path without pulling Malli onto the classpath."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- per-test reset -------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (schemas/reset-schema-validator!)
  (flows/reset-last-inputs!)
  (error-emit/clear-error-listeners!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; EP-0002: reg-flow / reg-app-schema are context-required frame-local — an
  ;; ambient call under no scope raises :rf.error/no-frame-context. Pin
  ;; :rf/default (an ordinary frame) as the established scope for the body.
  (frame/ensure-default-frame!)
  (try
    (binding [frame/*current-frame* :rf/default]
      (test-fn))
    (finally
      (schemas/reset-schema-validator!))))

(use-fixtures :each reset-runtime)

;; A pluggable predicate validator: a registered "schema" here is a 1-arg
;; predicate fn applied to the slice at its registered path. This exercises
;; the `:schemas/validate-with-registered-fn` seam without Malli (Spec 010
;; §Non-Malli validators).
(defn- install-predicate-validator! []
  (schemas/set-schema-fns!
    {:validate (fn [schema value] (boolean (schema value)))
     :explain  (fn [schema value] (when-not (schema value) {:failed value}))}))

;; ---------------------------------------------------------------------------
;; Finding 1 — app-db rollback restores the flow's last-inputs, and a later
;; clean drain re-materialises the flow output WITHOUT an input change.
;; ---------------------------------------------------------------------------

(deftest app-db-rollback-restores-flow-last-inputs-and-recomputes-on-next-clean-drain
  (testing "a post-commit app-db schema rollback rolls back the flow dirty-check, so the next clean drain re-materialises the output without an input change"
    (install-predicate-validator!)
    ;; `:reject-out?` flips the validator's verdict on the flow's output
    ;; slice. First flow-augmented commit is rejected (rollback); after we
    ;; flip it, the slice passes.
    (let [reject-out? (atom false)]
      ;; The flow doubles :n into :out.
      (rf/reg-event :seed       (fn [{:keys [db]} _] {:db {:n 1}}))
      (rf/reg-event :touch-other (fn [{:keys [db]} _] {:db (assoc db :other true)}))
      ;; App-db schema on the flow's OUTPUT slot: reject while the flag is
      ;; set, otherwise accept. A nil slice (output absent) always passes —
      ;; so the seeding dispatch (before :out exists / before the flow is
      ;; registered) is never rejected.
      (rf/reg-app-schema [:out]
                         {:schema (fn [out]
                                    (or (nil? out) (not @reject-out?)))
                          :frame :rf/default})

      ;; Seed :n = 1 DURABLY (no flow registered yet, so :out is absent and
      ;; the schema passes). This is the pre-handler db the rollback below
      ;; restores to.
      (rf/dispatch-sync [:seed])
      (is (= {:n 1} (rf/app-db-value :rf/default))
          "precondition: :n = 1 committed durably, :out absent")

      ;; Register the flow and arm the rejection. The flow computes on the
      ;; NEXT drain (registration does not drain).
      (rf/reg-flow {:id     :double
                    :inputs [[:n]]
                    :derive (fn [n] (* 2 (or n 0)))
                    :output-path   [:out]})
      (reset! reject-out? true)

      ;; A drain whose handler does NOT touch :n. The flow transform computes
      ;; :out = 2 into the pending db and advances :double's last-inputs to
      ;; [1], but the post-commit validator rejects the :out slice → the
      ;; whole commit rolls back to the pre-handler db {:n 1}. (No :out, no
      ;; :other land.)
      (rf/dispatch-sync [:touch-other])

      ;; --- Post-rollback state (the bug's trigger) ----------------------
      (is (= {:n 1} (rf/app-db-value :rf/default))
          "app-db rolled back to {:n 1} — the rejected :out write was discarded")
      (is (not (contains? (rf/app-db-value :rf/default) :out))
          ":out absent after the rollback")
      ;; THE LOAD-BEARING ASSERT: the flow's dirty-check row was rolled back
      ;; in lock-step. A surviving advanced row would leave the flow looking
      ;; up-to-date despite its output never reaching app-db.
      (is (not (contains? (flows/last-inputs-snapshot) :double))
          (str "flow :double's last-inputs row was rolled back with app-db "
               "(pre-fix it stayed advanced to {:double {:rf/default [1]}}, "
               "permanently suppressing the flow). Got "
               (pr-str (flows/last-inputs-snapshot))))

      ;; Flip the validator to accept, then drive a CLEAN drain whose event
      ;; does NOT change :n (the flow's only input). The rolled-back
      ;; dirty-check forces a recompute; a surviving advanced row would skip
      ;; the flow on =-equal inputs and :out would never appear.
      (reset! reject-out? false)
      (rf/dispatch-sync [:touch-other])

      ;; --- Acceptance: the flow re-materialised on a no-input-change drain
      (is (= 2 (:out (rf/app-db-value :rf/default)))
          (str "the next clean drain re-materialised :out = 2 × :n = 2 "
               "WITHOUT an input change. Pre-fix the stale dirty-check row "
               "skipped the flow and :out stayed absent. Got "
               (pr-str (rf/app-db-value :rf/default))))
      (is (:other (rf/app-db-value :rf/default))
          "the no-op event's own write also landed")
      (is (= [1] (get-in (flows/last-inputs-snapshot) [:double :rf/default]))
          "after the successful recompute the dirty-check row is advanced to [1]"))))

;; ---------------------------------------------------------------------------
;; Companion — a DURABLE commit (no rollback) leaves the dirty-check advanced
;; as normal. Pins that the fix only rolls back the bookkeeping on an actual
;; rollback (no over-restore on the happy path).
;; ---------------------------------------------------------------------------

(deftest durable-commit-leaves-dirty-check-advanced
  (testing "a flow whose output passes post-commit validation commits durably and advances last-inputs"
    (install-predicate-validator!)
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 3}}))
    (rf/reg-flow {:id     :double
                  :inputs [[:n]]
                  :derive (fn [n] (* 2 (or n 0)))
                  :output-path   [:out]})
    ;; Schema always accepts.
    (rf/reg-app-schema [:out] {:schema (fn [_] true) :frame :rf/default})
    (rf/dispatch-sync [:seed])
    (is (= 6 (:out (rf/app-db-value :rf/default)))
        "the flow output committed durably")
    (is (= [3] (get-in (flows/last-inputs-snapshot) [:double :rf/default]))
        "last-inputs advanced normally on a durable commit (no over-restore)")))
