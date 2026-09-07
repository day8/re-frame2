(ns re-frame.flows-rollback-dirty-check-test
  "An app-db schema REJECTION (rf2-uhk9ko — the candidate transition is
  validated BEFORE install) rolls the flow dirty-check (`last-inputs`)
  bookkeeping back in lock-step with the discarded candidate.

  The flow transform runs as the router's OUTERMOST `:after` interceptor: the
  moment a flow recomputes it advances THIS frame's `last-inputs` row and
  folds the output into the chain's PENDING `:db` effect. Whether that pending
  `:db` becomes DURABLE, however, is decided AFTER the chain returns: a
  candidate schema / machine-data validation failure REJECTS the transition —
  nothing installs, app-db keeps its pre-handler value. `run-flows-on-db`'s
  OWN throw-path snapshot/restore does not cover that — the rejection lands
  OUTSIDE it. If the advanced `last-inputs` row survived such a rejection, the
  dirty-check would believe the flow up-to-date even though its output never
  reached app-db: the next clean drain would see `=`-equal inputs, SKIP
  the flow, and the output would never re-materialise.

  So `flows-after-interceptor` snapshots the draining frame's `last-inputs`
  rows BEFORE the flow transform advances them (via the
  `:flows/snapshot-last-inputs` hook) and stashes the snapshot on the ctx.
  `commit-and-flow!`'s rejection arm restores it (via
  `:flows/restore-last-inputs!`) — the ONLY state a rejection restores (the
  container was never written), the exact mirror of the throw-path restore.
  Frame-scoped: a sibling frame's container is a different atom and is
  untouched.

  The validator seam is the pluggable predicate seam the rest of Spec 010
  uses (`set-schema-fns!`) so the test exercises the exact production
  rejection path without pulling Malli onto the classpath."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

;; ---- per-test reset / schema-validator seam ------------------------------
;;
;; The standard runtime reset (registrar baseline + frames + flows/schemas +
;; plain-atom adapter + ambient `:rf/default` scope) is owned by
;; `make-reset-runtime-fixture`. Layered AROUND it, a concern-specific
;; fixture resets the pluggable schema-validator seam (which the standard
;; reset does not touch) before and after each test so the validator this
;; suite installs cannot leak into a sibling suite.

(defn- with-schema-validator-reset
  [test-fn]
  (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns)
  (try
    (test-fn)
    (finally
      (rf.schemas/set-schema-fns! rf.schemas/default-schema-fns))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  with-schema-validator-reset)

;; A pluggable predicate validator: a registered "schema" here is a 1-arg
;; predicate fn applied to the slice at its registered path. This exercises
;; the `:schemas/validate-with-registered-fn` seam without Malli (Spec 010
;; §Non-Malli validators).
(defn- install-predicate-validator! []
  (rf.schemas/set-schema-fns!
    {:validate (fn [schema value] (boolean (schema value)))
     :explain  (fn [schema value] (when-not (schema value) {:failed value}))}))

;; ---------------------------------------------------------------------------
;; Finding 1 — app-db rollback restores the flow's last-inputs, and a later
;; clean drain re-materialises the flow output WITHOUT an input change.
;; ---------------------------------------------------------------------------

(deftest app-db-rejection-restores-flow-last-inputs-and-recomputes-on-next-clean-drain
  (testing "an app-db schema rejection rolls back the flow dirty-check, so the next clean drain re-materialises the output without an input change"
    (install-predicate-validator!)
    ;; `:reject-out?` flips the validator's verdict on the flow's output
    ;; slice. First flow-augmented candidate is rejected; after we
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
                         {:frame :rf/default} (fn [out]
                                                (or (nil? out) (not @reject-out?))))

      ;; Seed :n = 1 DURABLY (no flow registered yet, so :out is absent and
      ;; the schema passes). This is the pre-handler db the rollback below
      ;; restores to.
      (rf/dispatch-sync [:seed])
      (is (= {:n 1} (rf/app-db-value :rf/default))
          "precondition: :n = 1 committed durably, :out absent")

      ;; Register the flow and arm the rejection. The flow computes on the
      ;; NEXT drain (registration does not drain).
      (rf/reg-flow :double {:inputs [[:n]] :output-path [:out]} (fn [n] (* 2 (or n 0))))
      (reset! reject-out? true)

      ;; A drain whose handler does NOT touch :n. The flow transform computes
      ;; :out = 2 into the pending db and advances :double's last-inputs to
      ;; [1], but the candidate validator rejects the :out slice → the
      ;; whole candidate is discarded pre-install; app-db keeps {:n 1}.
      ;; (No :out, no :other land.)
      (rf/dispatch-sync [:touch-other])

      ;; --- Post-rejection state (the bug's trigger) ---------------------
      (is (= {:n 1} (rf/app-db-value :rf/default))
          "app-db keeps {:n 1} — the rejected :out write was discarded")
      (is (not (contains? (rf/app-db-value :rf/default) :out))
          ":out absent after the rejection")
      ;; THE LOAD-BEARING ASSERT: the flow's dirty-check row was rolled back
      ;; in lock-step. A surviving advanced row would leave the flow looking
      ;; up-to-date despite its output never reaching app-db.
      (is (not (contains? (rf.flows/last-inputs-snapshot) :double))
          (str "flow :double's last-inputs row was rolled back with the "
               "rejected candidate (pre-fix it stayed advanced to "
               "{:double {:rf/default [1]}}, permanently suppressing the "
               "flow). Got "
               (pr-str (rf.flows/last-inputs-snapshot))))

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
      (is (= [1] (get-in (rf.flows/last-inputs-snapshot) [:double :rf/default]))
          "after the successful recompute the dirty-check row is advanced to [1]"))))

;; ---------------------------------------------------------------------------
;; Companion — a DURABLE commit (no rejection) leaves the dirty-check advanced
;; as normal. Pins that the fix only rolls back the bookkeeping on an actual
;; rejection (no over-restore on the happy path).
;; ---------------------------------------------------------------------------

(deftest durable-commit-leaves-dirty-check-advanced
  (testing "a flow whose output passes candidate validation commits durably and advances last-inputs"
    (install-predicate-validator!)
    (rf/reg-event :seed (fn [{:keys [db]} _] {:db {:n 3}}))
    (rf/reg-flow :double {:inputs [[:n]] :output-path [:out]} (fn [n] (* 2 (or n 0))))
    ;; Schema always accepts.
    (rf/reg-app-schema [:out] {:frame :rf/default} (fn [_] true))
    (rf/dispatch-sync [:seed])
    (is (= 6 (:out (rf/app-db-value :rf/default)))
        "the flow output committed durably")
    (is (= [3] (get-in (rf.flows/last-inputs-snapshot) [:double :rf/default]))
        "last-inputs advanced normally on a durable commit (no over-restore)")))

;; ===========================================================================
;; rf2-1b8yxb — a CHAIN error (`:error` outcome) rolls back the flow dirty-
;; check + in-drain vacation state exactly like the post-commit rollback
;; above. `execute-chain` runs the FULL `:after` pass for teardown even after
;; a `:before` / handler / cofx / user-`:after` throw, so the framework-
;; outermost flows `:after` would (pre-fix) run the flow transform against a
;; DOOMED pending db — advancing `last-inputs` + draining vacations that
;; `commit-and-flow!`'s `:error` short-circuit then discards WITHOUT restoring.
;; Fix (1) guards the flows `:after` on `:rf/interceptor-error`; fix (2)
;; restores the ctx-stashed snapshots on the in-band legacy-root / class-defect
;; aborts (which run the `:after` CLEANLY, then abort at the final-effects
;; boundary). Manifestations a–d + the fix-(2) abort arm are covered below.
;; ===========================================================================

;; ---- (a) output-loss: a fresh flow first-firing during a handler-throw ----

(deftest chain-error-handler-throw-does-not-poison-fresh-flow-first-firing
  (testing "a handler-throw event runs the flows :after; pre-fix it advances the fresh flow's last-inputs against the doomed db and discards the output, so a later same-input drain skips forever. The guard leaves the row unadvanced so the next clean drain materialises the output."
    (rf/reg-event :seed (fn [_ _] {:db {:n 1}}))
    (rf/dispatch-sync [:seed])
    (rf/reg-flow :derived {:inputs [[:n]] :output-path [:out]} (fn [n] (* n 10)))
    (rf/reg-event :boom (fn [_ _] (throw (ex-info "handler boom" {:src :test}))))

    ;; The aborting event. Its flows :after must NOT advance :derived's row.
    (rf/dispatch-sync [:boom])
    (is (not (contains? (rf.flows/last-inputs-snapshot) :derived))
        (str "the errored event did NOT advance :derived's dirty-check row "
             "(pre-fix it stayed advanced to [1], suppressing the flow). Got "
             (pr-str (rf.flows/last-inputs-snapshot))))
    (is (nil? (:out (rf/app-db-value :rf/default)))
        ":out was not committed by the aborted event")

    ;; A later CLEAN drain with the SAME :n must recompute — pre-fix the stale
    ;; row skipped the flow on =-equal inputs and :out stayed nil forever.
    (rf/dispatch-sync [:seed])
    (is (= 10 (:out (rf/app-db-value :rf/default)))
        (str "the fresh flow finally materialised :out = 10 × :n on the next "
             "clean drain WITHOUT an input change. Got "
             (pr-str (rf/app-db-value :rf/default))))))

;; ---- (b) user-:after throw: handler returned :db, an :after interceptor ----
;;         throws. Same `:rf/interceptor-error` guard covers it.

(deftest chain-error-user-after-throw-does-not-poison-flow
  (testing "handler returns :db but a user :after interceptor throws → the flows :after must not advance last-inputs against the doomed pending db; a later clean drain materialises the output"
    (rf/reg-event :seed (fn [_ _] {:db {:n 2}}))
    (rf/dispatch-sync [:seed])
    (rf/reg-flow :derived {:inputs [[:n]] :output-path [:out]} (fn [n] (* n 10)))
    ;; Interceptor chains are reference-only (EP-0022): register + reference.
    (rf/reg-interceptor :test/after-boom
                        {:after (fn [_ctx]
                                  (throw (ex-info "after boom" {:src :test})))})
    (rf/reg-event :with-bad-after
                  {:interceptors [:test/after-boom]}
                  (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
    (rf/dispatch-sync [:with-bad-after])

    (is (not (contains? (rf.flows/last-inputs-snapshot) :derived))
        ":derived's dirty-check row was NOT advanced by the user-:after-throw abort")
    (is (nil? (:out (rf/app-db-value :rf/default)))
        ":out not committed (the whole event aborted)")
    (is (not (:touched (rf/app-db-value :rf/default)))
        "the doomed handler write did not land either (no partial commit)")

    (rf/dispatch-sync [:seed])
    (is (= 20 (:out (rf/app-db-value :rf/default)))
        "the flow materialised on the next clean drain (row was not poisoned)")))

;; ---- (c) in-drain vacation LOST: a vacation queued by an :fx in event N ----
;;         is drained-and-cleared by errored event N+1 and lost forever.

(deftest chain-error-preserves-in-drain-queued-vacation
  (testing "an in-drain output-path vacation queued for the next drain is NOT consumed by an errored event; it re-attempts on the following clean drain (a drained-but-discarded vacation would be lost forever)"
    ;; Seed a stale derived value at [:stale] and a live sibling flow so the
    ;; frame carries a flow-map. Queue [:stale] for vacation exactly as
    ;; `clear-flow`'s in-drain branch does (`record-abandoned-output-path!`) —
    ;; a vacation queued by a prior drain, pending for the NEXT one.
    (rf/reg-event :seed (fn [_ _] {:db {:n 5 :stale 99}}))
    (rf/reg-flow :keep {:inputs [[:n]] :output-path [:out-keep]} (fn [n] (* 7 (or n 0))))
    (rf/dispatch-sync [:seed])
    (is (= 99 (:stale    (rf/app-db-value :rf/default))) "precondition: :stale present")
    (is (= 35 (:out-keep (rf/app-db-value :rf/default))) "precondition: :out-keep = 35")

    (rf.flows.registry/record-abandoned-output-path! :rf/default [:stale])
    (is (= #{[:stale]} (rf.flows.registry/abandoned-output-paths-snapshot :rf/default))
        "[:stale] is queued for vacation on the next drain")

    ;; An errored event. Pre-fix its flows :after drains-and-clears the queued
    ;; [:stale], applies it to the pending db, then the event aborts and the
    ;; pending db is discarded — the vacation is LOST (queue emptied, :stale
    ;; stranded). The guard leaves the queue intact for the next drain.
    (rf/reg-event :boom (fn [_ _] (throw (ex-info "boom" {:src :test}))))
    (rf/dispatch-sync [:boom])
    (is (= #{[:stale]} (rf.flows.registry/abandoned-output-paths-snapshot :rf/default))
        (str "the queued [:stale] vacation was NOT consumed by the aborted "
             "event (pre-fix it was drained-and-lost). Got "
             (pr-str (rf.flows.registry/abandoned-output-paths-snapshot :rf/default))))
    (is (= 99 (:stale (rf/app-db-value :rf/default)))
        ":stale not yet vacated (the aborted event committed nothing)")

    ;; A clean drain: run-flows-on-db drains the still-queued vacation.
    (rf/reg-event :touch (fn [{:keys [db]} _] {:db (assoc db :touched true)}))
    (rf/dispatch-sync [:touch])
    (is (not (contains? (rf/app-db-value :rf/default) :stale))
        ":stale finally vacated on the next clean drain (the move re-attempted)")
    (is (empty? (rf.flows.registry/abandoned-output-paths-snapshot :rf/default))
        "the queue is drained after the successful vacation")
    (is (= 35 (:out-keep (rf/app-db-value :rf/default)))
        ":out-keep (the live sibling flow) is untouched")))

;; ---- (d) no spurious flow traces on a handler-throw abort -----------------

(deftest chain-error-emits-no-spurious-flow-traces
  (testing "a handler-throw abort emits NO :rf.flow/computed and NO t2 (:rf.event/db-pending-post-flow) — the flow transform never ran"
    (let [events (atom [])]
      (rf/register-listener! :trace ::spurious (fn [ev] (swap! events conj ev)))
      (try
        (rf/reg-event :seed (fn [_ _] {:db {:n 1}}))
        (rf/dispatch-sync [:seed])
        (rf/reg-flow :derived {:inputs [[:n]] :output-path [:out]} (fn [n] (* n 10)))
        (reset! events [])
        (rf/reg-event :boom (fn [_ _] (throw (ex-info "boom" {:src :test}))))
        (rf/dispatch-sync [:boom])
        (is (empty? (filterv #(= :rf.flow/computed (:operation %)) @events))
            "no :rf.flow/computed on the aborted event (flow transform skipped)")
        (is (empty? (filterv #(= :rf.event/db-pending-post-flow (:operation %)) @events))
            "no t2 (:rf.event/db-pending-post-flow) on the aborted event")
        (finally
          (rf/unregister-listener! :trace ::spurious))))))

;; ---- fix (2): the in-band legacy-runtime-root abort (clean chain) ----------
;;      restores the dirty-check the flows :after advanced before the abort.

(deftest legacy-root-abort-restores-flow-dirty-check
  (testing "a FINAL-effects legacy-runtime-root abort (no interceptor error, so the flows :after ran cleanly and advanced the row) rolls the flow dirty-check back, so the next clean drain re-materialises the output"
    (rf/reg-event :seed (fn [_ _] {:db {:n 4}}))
    (rf/dispatch-sync [:seed])
    (rf/reg-flow :derived {:inputs [[:n]] :output-path [:out]} (fn [n] (* n 10)))
    ;; A user :after that inserts the retired `:rf/runtime` app-db root into the
    ;; pending :db AFTER the in-chain guard ran — the flows :after (outermost)
    ;; then runs cleanly over it and advances :derived's row; commit-and-flow!
    ;; detects the legacy root at the final-effects boundary and aborts :error.
    (rf/reg-interceptor :test/insert-legacy-root
                        {:after (fn [ctx]
                                  (update-in ctx [:effects :db] assoc :rf/runtime {}))})
    (rf/reg-event :legacy
                  {:interceptors [:test/insert-legacy-root]}
                  (fn [{:keys [db]} _] {:db db}))
    (rf/dispatch-sync [:legacy])

    (is (not (contains? (rf.flows/last-inputs-snapshot) :derived))
        (str "the in-band legacy-root abort restored :derived's dirty-check "
             "row (pre-fix#2 it stayed advanced to [4]). Got "
             (pr-str (rf.flows/last-inputs-snapshot))))
    (is (nil? (:out (rf/app-db-value :rf/default)))
        ":out not committed (the whole event aborted, no partial commit)")

    (rf/dispatch-sync [:seed])
    (is (= 40 (:out (rf/app-db-value :rf/default)))
        "the flow re-materialised :out = 40 on the next clean drain (row was restored)")))
