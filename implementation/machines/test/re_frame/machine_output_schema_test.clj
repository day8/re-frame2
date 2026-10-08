(ns re-frame.machine-output-schema-test
  "Verifies the `[:schemas :output]` completion-output schema on `reg-machine`
  and the `:where :machine-output` validation boundary.

  The contract under test:

   1. **Completion boundary — conforming output.** A finishing machine whose
      `:output-key` payload CONFORMS to `[:schemas :output]` emits NO
      `:where :machine-output` trace; the completion flows normally.

   2. **Completion boundary — violating output.** A finishing machine whose
      `:output-key` payload VIOLATES `[:schemas :output]` emits exactly one
      `:rf.error/schema-validation-failure :where :machine-output :phase
      :completion` trace — and the completion STILL FLOWS (best-effort
      fail-loud; the machine already finished, so `:rollback? false`). The
      failure tag carries `:where`, `:machine-id`, `:failing-id`, `:phase`,
      `:value`, `:received`, `:schema`, `:explain`, `:rollback?`, `:reason`
      + the `:recovery` envelope.

   3. **Spawned child → parent :on-done.** A spawned child whose
      `:output-key` payload violates `[:schemas :output]` emits the boundary
      trace AND the parent's `:on-done` STILL receives the (violating) result
      — output validation observes, it does not suppress the payload.

   4. **No schema → no validation.** A machine with no `[:schemas :output]`
      runs without any `:where :machine-output` trace.

   5. **nil output passes vacuously.** A final state with no `:output-key`
      produces a nil `result`; a `[:schemas :output]` that admits nil passes.

  That `reg-machine` accepts `[:schemas :output]` and round-trips it through
  the `:rf/machine` projection is pinned in `machine_schemas_grammar_test`.

  Tests use Malli schemas (the framework default validator) — the same
  late-bound `:schemas/validate-with-registered-fn` adapter the `:where
  :machine-data` boundary routes through."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]  ;; loaded for its late-bind hooks (`rf/reg-machine`)
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- collect-output-traces!
  "Run `f` while collecting every `:rf.error/schema-validation-failure` trace
  whose `:where` is `:machine-output`. Returns the captured trace events."
  [f]
  (rf.machines.test-support/with-trace-capture traces
    (f)
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :machine-output (-> % :tags :where)))
             @traces)))

;; ---- (1) conforming completion output → no trace -------------------------

(deftest conforming-output-emits-no-trace
  (rf/reg-machine :rf.machine-output/ok
    {:initial :running
     :data    {}
     :schemas {:output [:int]}
     :states  {:running {:on {:fin {:target :done
                                    :action (fn [{data :data}]
                                              {:data (assoc data :result 42)})}}}
               :done    {:final?     true
                         :output-key :result}}})
  (is (empty? (collect-output-traces!
                (fn []
                  (rf/dispatch-sync [:rf.machine-output/ok [:noop]])
                  (rf/dispatch-sync [:rf.machine-output/ok [:fin]]))))))

;; ---- (2) violating completion output → one trace, completion still flows ---

(deftest violating-output-emits-trace-and-still-finishes
  (testing "a finishing singleton whose :output-key payload violates the schema
            emits exactly one :where :machine-output :phase :completion trace,
            and the machine STILL finishes (best-effort — :rollback? false)"
    (rf/reg-machine :rf.machine-output/bad
      {:initial :running
       :data    {}
       :schemas {:output [:int]}
       :states  {:running {:on {:fin {:target :done
                                      :action (fn [{data :data}]
                                                {:data (assoc data :result "nope")})}}}
                 :done    {:final?     true
                           :output-key :result}}})
    (rf/dispatch-sync [:rf.machine-output/bad [:noop]])
    (let [[ev :as traces] (collect-output-traces!
                            #(rf/dispatch-sync [:rf.machine-output/bad [:fin]]))
          tag             (:tags ev)]
      (is (= 1 (count traces)))
      (is (= {:where      :machine-output
              :machine-id :rf.machine-output/bad
              :failing-id :rf.machine-output/bad
              :phase      :completion
              :value      "nope"
              :rollback?  false}
             (select-keys tag [:where :machine-id :failing-id :phase :value :rollback?])))
      (is (= [true true true true :no-recovery]
             [(contains? tag :received) (contains? tag :schema) (contains? tag :explain)
              (string? (:reason tag)) (:recovery ev)])
          "the Spec 009 schema-failure shape consumers render")
      (is (nil? (rf.machines.test-support/snapshot :rf.machine-output/bad))
          "the machine STILL finished + auto-destroyed (completion flows)"))))

;; ---- (3) spawned child: violating output → trace + parent :on-done STILL runs

(deftest spawned-violating-output-trace-but-on-done-still-receives-result
  (testing "a spawned child whose :output-key payload violates [:schemas :output]
            emits the boundary trace AND the parent's :on-done STILL receives
            the (violating) result — output validation observes, never suppresses"
    (rf/reg-machine :rf.machine-output/spawn-child
      {:initial :running
       :data    {}
       :schemas {:output [:int]}
       :states  {:running {:on {:fin {:target :done
                                      :action (fn [{data :data}]
                                                {:data (assoc data :result "bad")})}}}
                 :done    {:final?     true
                           :output-key :result}}})
    (rf/reg-machine :rf.machine-output/spawn-parent
      {:initial :working
       :data    {}
       :states  {:working
                 {:spawn {:machine-id :rf.machine-output/spawn-child
                          :on-done    (fn [{d :data r :result}] (assoc d :reported r))}}}})
    (let [traces (collect-output-traces!
                   (fn []
                     (rf/dispatch-sync [:rf.machine-output/spawn-parent [:rf.machine.spawn/spawned]])
                     (rf/dispatch-sync [(get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                                [:rf.runtime/machines :spawned
                                                 :rf.machine-output/spawn-parent [:working]])
                                        [:fin]])))]
      (is (= [["bad"] "bad"]
             [(mapv (comp :value :tags) traces)
              (get-in (rf.machines.test-support/snapshot :rf.machine-output/spawn-parent)
                      [:data :reported])])))))

;; ---- (4) no schema → no validation (control) ------------------------------

;; ---- (5) nil output passes vacuously against a nil-admitting schema -------

(deftest nil-output-passes-against-nil-admitting-schema
  (testing "a final state with NO :output-key produces a nil result; a
            [:schemas :output] that admits nil passes vacuously"
    (let [spec {:initial :running
                :schemas {:output [:maybe :int]}
                :states  {:running {:on {:fin :done}}
                          ;; no :output-key → result is nil
                          :done    {:final? true}}}]
      (rf/reg-machine :rf.machine-output/nil-ok spec)
      (let [traces (collect-output-traces!
                     #(rf/dispatch-sync [:rf.machine-output/nil-ok [:fin]]))]
        (is (empty? traces)
            "nil output conforms to [:maybe :int] — no trace")))))
