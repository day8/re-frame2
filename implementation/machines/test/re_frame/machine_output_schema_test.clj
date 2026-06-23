(ns re-frame.machine-output-schema-test
  "Verifies the `[:schemas :output]` completion-output schema on `reg-machine`
  and the `:where :machine-output` validation boundary (EP-0029 A8,
  rf2-kgr3kk).

  The contract under test:

   1. **Acceptance.** `reg-machine` accepts a machine-level `[:schemas
      :output]` schema; registration completes and the schema round-trips
      through `(rf/machine-meta id)`.

   2. **Completion boundary — conforming output.** A finishing machine whose
      `:output-key` payload CONFORMS to `[:schemas :output]` emits NO
      `:where :machine-output` trace; the completion flows normally.

   3. **Completion boundary — violating output.** A finishing machine whose
      `:output-key` payload VIOLATES `[:schemas :output]` emits exactly one
      `:rf.error/schema-validation-failure :where :machine-output :phase
      :completion` trace — and the completion STILL FLOWS (best-effort
      fail-loud; the machine already finished, so `:rollback? false`).

   4. **Spawned child → parent :on-done.** A spawned child whose
      `:output-key` payload violates `[:schemas :output]` emits the boundary
      trace AND the parent's `:on-done` STILL receives the (violating) result
      — output validation observes, it does not suppress the payload.

   5. **No schema → no validation.** A machine with no `[:schemas :output]`
      runs without any `:where :machine-output` trace.

   6. **nil output passes vacuously.** A final state with no `:output-key`
      produces a nil `result`; a `[:schemas :output]` that admits nil passes.

   7. **Tag payload.** The failure tag carries `:where`, `:machine-id`,
      `:failing-id`, `:phase`, `:value`, `:received`, `:schema`, `:explain`,
      `:rollback?`, `:reason` + the `:recovery` envelope.

  Tests use Malli schemas (the framework default validator) — the same
  late-bound `:schemas/validate-with-registered-fn` adapter the `:where
  :machine-data` boundary routes through."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.schemas]
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- collect-output-traces!
  "Run `f` while collecting every `:rf.error/schema-validation-failure` trace
  whose `:where` is `:machine-output`. Returns the captured trace events."
  [f]
  (mtest/with-trace-capture traces
    (f)
    (filterv #(and (= :rf.error/schema-validation-failure (:operation %))
                   (= :machine-output (-> % :tags :where)))
             @traces)))

;; ---- (1) acceptance: `[:schemas :output]` round-trips through machine-meta -

(deftest reg-machine-accepts-output-schema-key
  (testing "reg-machine completes registration when the spec carries [:schemas :output]"
    (let [OutputSchema [:int]
          spec         {:initial :running
                        :schemas {:output OutputSchema}
                        :states  {:running {:on {:fin :done}}
                                  :done    {:final?     true
                                            :output-key :result}}}]
      (rf/reg-machine :rf.machine-output/accepted spec)
      (let [meta (machines/machine-meta :rf.machine-output/accepted)]
        (is (some? meta) "machine-meta returns the registered spec")
        (is (= OutputSchema (get-in meta [:schemas :output]))
            "the [:schemas :output] schema round-trips through machine-meta")))))

;; ---- (2) conforming completion output → no trace -------------------------

(deftest conforming-output-emits-no-trace
  (testing "a finishing machine whose :output-key payload conforms emits no
            :where :machine-output trace"
    (let [spec {:initial :running
                :data    {}
                :schemas {:output [:int]}
                :states  {:running {:on {:fin {:target :done
                                               :action (fn [{data :data}]
                                                         {:data (assoc data :result 42)})}}}
                          :done    {:final?     true
                                    :output-key :result}}}]
      (rf/reg-machine :rf.machine-output/ok spec)
      (let [traces (collect-output-traces!
                     (fn []
                       (rf/dispatch-sync [:rf.machine-output/ok [:noop]])
                       (rf/dispatch-sync [:rf.machine-output/ok [:fin]])))]
        (is (empty? traces)
            "no :where :machine-output trace for a conforming output")
        (is (nil? (mtest/snapshot :rf.machine-output/ok))
            "the machine finished + auto-destroyed normally")))))

;; ---- (3) violating completion output → one trace, completion still flows ---

(deftest violating-output-emits-trace-and-still-finishes
  (testing "a finishing singleton whose :output-key payload violates the schema
            emits exactly one :where :machine-output :phase :completion trace,
            and the machine STILL finishes (best-effort — :rollback? false)"
    (let [spec {:initial :running
                :data    {}
                :schemas {:output [:int]}
                :states  {:running {:on {:fin {:target :done
                                               :action (fn [{data :data}]
                                                         ;; "nope" violates [:int]
                                                         {:data (assoc data :result "nope")})}}}
                          :done    {:final?     true
                                    :output-key :result}}}]
      (rf/reg-machine :rf.machine-output/bad spec)
      (rf/dispatch-sync [:rf.machine-output/bad [:noop]])
      (let [traces   (collect-output-traces!
                       #(rf/dispatch-sync [:rf.machine-output/bad [:fin]]))
            trace-ev (first traces)
            tag      (:tags trace-ev)]
        (is (= 1 (count traces))
            "exactly one :where :machine-output trace fired on the violating completion")
        (is (= :machine-output (:where tag))
            "trace's :where tag pins the completion-output boundary")
        (is (= :completion (:phase tag))
            "tag's :phase is :completion (the finalize-time output check)")
        (is (= "nope" (:value tag))
            "tag carries the offending output payload")
        (is (false? (:rollback? tag))
            "completion is best-effort — :rollback? false (machine already finished)")
        ;; The completion STILL flows: the machine auto-destroyed despite the
        ;; output schema violation (best-effort fail-loud, not suppression).
        (is (nil? (mtest/snapshot :rf.machine-output/bad))
            "the machine STILL finished + auto-destroyed (completion flows)")))))

;; ---- (4) spawned child: violating output → trace + parent :on-done STILL runs

(deftest spawned-violating-output-trace-but-on-done-still-receives-result
  (testing "a spawned child whose :output-key payload violates [:schemas :output]
            emits the boundary trace AND the parent's :on-done STILL receives
            the (violating) result — output validation observes, never suppresses"
    (let [seen-result (atom :unset)]
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
                            :on-done    (fn [{d :data r :result}]
                                          (reset! seen-result r)
                                          (assoc d :reported r))}}}})
      (let [traces
            (collect-output-traces!
              (fn []
                (rf/dispatch-sync [:rf.machine-output/spawn-parent [:rf.machine.spawn/spawned]])
                (let [spawned-id (get-in (rf/runtime-db-value :rf/default)
                                         [:rf.runtime/machines :spawned
                                          :rf.machine-output/spawn-parent [:working]])]
                  (rf/dispatch-sync [spawned-id [:fin]]))))]
        (is (= 1 (count traces))
            "exactly one :where :machine-output trace fired for the spawned child")
        (is (= "bad" (-> traces first :tags :value))
            "the trace carries the child's violating output payload")
        (is (= "bad" @seen-result)
            "the parent's :on-done STILL received the result — validation observes, not suppresses")
        (is (= "bad" (get-in (mtest/snapshot :rf.machine-output/spawn-parent) [:data :reported]))
            "the parent's :data mutation from :on-done still landed")))))

;; ---- (5) no schema → no validation (control) ------------------------------

(deftest no-output-schema-no-validation
  (testing "a machine without [:schemas :output] finishes without any
            :where :machine-output trace"
    (let [spec {:initial :running
                :data    {}
                :states  {:running {:on {:fin {:target :done
                                               :action (fn [{data :data}]
                                                         {:data (assoc data :result "anything")})}}}
                          :done    {:final?     true
                                    :output-key :result}}}]
      (rf/reg-machine :rf.machine-output/no-schema spec)
      (let [traces (collect-output-traces!
                     (fn []
                       (rf/dispatch-sync [:rf.machine-output/no-schema [:noop]])
                       (rf/dispatch-sync [:rf.machine-output/no-schema [:fin]])))]
        (is (empty? traces)
            "no :where :machine-output trace for a no-output-schema machine")))))

;; ---- (6) nil output passes vacuously against a nil-admitting schema -------

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

;; ---- (7) tag payload completeness -----------------------------------------

(deftest output-tag-payload-carries-downstream-required-keys
  (testing "the :where :machine-output failure tag carries :where, :machine-id,
            :failing-id, :phase, :value, :received, :schema, :explain,
            :rollback?, :reason + the :recovery envelope"
    (let [spec {:initial :running
                :data    {}
                :schemas {:output [:int]}
                :states  {:running {:on {:fin {:target :done
                                               :action (fn [{data :data}]
                                                         {:data (assoc data :result :not-an-int)})}}}
                          :done    {:final?     true
                                    :output-key :result}}}]
      (rf/reg-machine :rf.machine-output/payload spec)
      (rf/dispatch-sync [:rf.machine-output/payload [:noop]])
      (let [traces   (collect-output-traces!
                       #(rf/dispatch-sync [:rf.machine-output/payload [:fin]]))
            trace-ev (first traces)
            tag      (:tags trace-ev)]
        (is (= :machine-output (:where tag)))
        (is (= :rf.machine-output/payload (:machine-id tag)))
        (is (= :rf.machine-output/payload (:failing-id tag)))
        (is (= :completion (:phase tag)))
        (is (= :not-an-int (:value tag))    ":value carries the offending output payload")
        (is (contains? tag :received)       ":received present (parallels :value)")
        (is (contains? tag :schema)         ":schema present (consumers render it inline)")
        (is (contains? tag :explain)        ":explain present (Malli explanation)")
        (is (false? (:rollback? tag))       ":rollback? false (machine already finished)")
        (is (string? (:reason tag))         ":reason is a human-readable string")
        ;; `:recovery` rides the trace envelope, not :tags — mirrors :where :machine-data.
        (is (= :no-recovery (:recovery trace-ev))
            ":recovery :no-recovery on the trace envelope")))))
