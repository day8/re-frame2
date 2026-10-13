(ns re-frame.machine-output-schema-test
  "The `[:schemas :output]` completion-output schema on `reg-machine` and the
  `:where :machine-output` validation boundary (Malli, the default validator):

   1. A violating `:output-key` payload emits exactly one
      `:rf.error/schema-validation-failure :where :machine-output :phase
      :completion` trace, and the completion STILL flows (`:rollback? false`).
   2. A spawned child's violating result still reaches the parent's `:on-done`
      — output validation observes, it never suppresses the payload.
   3. A final state with no `:output-key` produces a nil result, which passes a
      nil-admitting schema.

  That `reg-machine` accepts `[:schemas :output]` and round-trips it through
  the `:rf/machine` projection is pinned in `machine_schemas_grammar_test`."
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

;; ---- (1) violating completion output → one trace, completion still flows ---

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

;; ---- (2) spawned child: violating output → trace + parent :on-done STILL runs

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

;; ---- (3) nil output passes vacuously against a nil-admitting schema -------

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
