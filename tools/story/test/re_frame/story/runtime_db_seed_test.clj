(ns re-frame.story.runtime-db-seed-test
  "End-to-end run-path tests for the `:db-seed` fidelity rung.

  `:db-seed` is the MIDDLE rung of spec/017's fidelity ladder
  (`#{:real-setup :db-seed :sub-overrides}`): a schema-checked direct
  app-db seed. These tests drive `rf.story/run` to terminal on the JVM and
  assert the runtime BEHAVIOUR the plan compiler can't (the seed is
  applied to a live frame; the seeded app-db is schema-validated):

  - a valid `:db-seed` seeds the variant frame's app-db BEFORE the script,
    and a `:real-setup` event runs ON TOP of the seed (the ladder
    composes);
  - a seed that VIOLATES the frame's registered app-db schema FAILS the
    run with a structured `:rf.error/story-db-seed-invalid` carrying the
    `{:path :value :explain}` violations (spec/017 §Setup — direct seeding
    bypasses event/cofx validation but MUST validate the affected app-db
    schema);
  - with NO schemas artefact / no validator the seed is applied unchecked
    (the host-free floor).

  Validation reuses the schemas late-bind seam — the runtime
  reaches `:schemas/frame-schema-entries` +
  `:schemas/validate-with-registered-fn` / `:schemas/explain-with-registered-fn`
  through `re-frame.late-bind` (the SAME seam the `:sub-return` path + the
  `:sub-override` validation use). The schemas artefact is NOT on
  Story's classpath, so these tests REGISTER those hooks directly (backed
  by Malli, which IS on the classpath) exactly as the artefact would —
  which is itself proof the runtime takes no hard dep on the schemas
  artefact and drives the validation purely through the late-bind seam."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core         :as m]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story     :as rf.story]))

;; ---- a host-free stand-in for the schemas artefact's late-bind seam ------
;;
;; A per-frame `{frame-id {reg-path schema-meta}}` registry + the Malli-
;; backed validator/explainer, published under the SAME hook keys the real
;; schemas artefact uses. This is the contract the runtime's
;; `db-seed-violations` reaches — proving the reuse without a hard dep.

(def ^:private frame-schemas (atom {}))

(defn- reg-app-schema! [frame-id reg-path schema]
  (swap! frame-schemas assoc-in [frame-id reg-path] {:schema schema}))

(defn- install-schema-seam! []
  (rf.late-bind/set-fn! :schemas/frame-schema-entries
                     (fn [frame-id] (get @frame-schemas frame-id {})))
  (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
                     (fn [schema value] (m/validate schema value)))
  (rf.late-bind/set-fn! :schemas/explain-with-registered-fn
                     (fn [schema value] (m/explain schema value))))

(defn- uninstall-schema-seam! []
  (swap! rf.late-bind/hooks dissoc
         :schemas/frame-schema-entries
         :schemas/validate-with-registered-fn
         :schemas/explain-with-registered-fn))

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (reset! frame-schemas {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  ;; Clear any schema seam a prior test left so the no-validator floor case
  ;; is clean; tests that want validation install it explicitly.
  (uninstall-schema-seam!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :cart/add-item
                   (fn [{:keys [db]} [_ item]]
                     {:db (update-in db [:cart :items] (fnil conj []) item)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-target
  ([target] (.get ^java.util.concurrent.CompletableFuture (rf.story/run target)))
  ([target opts] (.get ^java.util.concurrent.CompletableFuture (rf.story/run target opts))))

(defn- seed-error-record [result]
  (first (filter #(= :rf.error/story-db-seed-invalid (:assertion %))
                 (:assertions result))))

;; ===========================================================================
;; schema acceptance — :db-seed is a TYPED slot, never silently ignored
;; ===========================================================================

(deftest malformed-db-seed-is-rejected-by-the-variant-schema
  (testing "a non-map :db-seed is REJECTED at registration (never silently
            accepted) — :rf.error/variant-shape"
    (let [ex (try (rf.story/reg-variant :story.cart/malformed {:db-seed [1 2 3]})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/variant-shape (:rf.error/id (ex-data ex)))
          "a non-map :db-seed throws a shape error"))))

;; ===========================================================================
;; valid seed → app-db seeded before the script
;; ===========================================================================

(deftest valid-db-seed-seeds-app-db-before-script
  (testing "a valid :db-seed merges into the frame's app-db BEFORE the script,
            and a :real-setup event runs ON TOP of the seed (the ladder
            composes). The fixture leaves the schemas seam uninstalled, so
            this is also the host-free floor: no validator, seed applied
            unchecked"
    (rf.story/reg-variant
      :story.cart/seeded
      {:db-seed {:cart {:items [{:sku "A" :qty 1}]}}
       :script  [[:dispatch-sync [:cart/add-item {:sku "B" :qty 2}]]]})
    (let [result (run-target :story.cart/seeded)]
      (is (= :pass (:status result))
          "a valid-seed run with no failing assertion is :pass")
      (testing "the seed established the precondition, then the script appended"
        (is (= [{:sku "A" :qty 1} {:sku "B" :qty 2}]
               (get-in result [:app-db :cart :items]))
            "the :db-seed item is present AND the script's dispatched item appended")))))

;; ===========================================================================
;; invalid seed → structured :rf.error/story-db-seed-invalid
;; ===========================================================================

(deftest db-seed-violating-schema-fails-run-structured
  (testing "a :db-seed that VIOLATES the registered app-db schema FAILS the run
            with a structured :rf.error/story-db-seed-invalid (path/value/explain),
            per spec/017 §Setup"
    (install-schema-seam!)
    (reg-app-schema! :story.cart/bad [:cart]
                     [:map [:items [:vector [:map [:sku :string] [:qty :int]]]]])
    (rf.story/reg-variant
      :story.cart/bad
      ;; :qty is a string — violates [:qty :int].
      {:db-seed {:cart {:items [{:sku "A" :qty "two"}]}}})
    (let [result (run-target :story.cart/bad)]
      (is (= :error (:status result))
          "a schema-violating seed errors the run (never a vacuous pass)")
      (is (= :error (:lifecycle result)))
      (let [rec (seed-error-record result)]
        (is (false? (:passed? rec)) "the structured :rf.error/story-db-seed-invalid assertion landed")
        (testing "the record carries the structured path/value/explain violations"
          (let [viol (first (:violations rec))]
            (is (= [:cart] (:path viol)) "the violation names the registered app-db path")
            (is (= {:items [{:sku "A" :qty "two"}]} (:value viol))
                "the violation carries the rejected seeded slice")
            (is (some? (:explain viol)) "the violation carries the validator's explanation")))))))

(deftest db-seed-violation-stops-before-script
  (testing "a schema-violating seed FAILS before the script runs — a malformed
            precondition is not a thing to assert against"
    (install-schema-seam!)
    ;; The probe counts the script's one dispatch directly, because an
    ;; app-db read cannot witness it: a handler writing into a seed this
    ;; malformed throws, so "never ran" and "ran and failed" leave the same
    ;; app-db.
    (let [script-ran    (atom 0)
          probe-variant (fn [variant-id items]
                          (reg-app-schema! variant-id [:cart]
                                           [:map [:items [:vector :map]]])
                          (rf.story/reg-variant
                            variant-id
                            {:db-seed {:cart {:items items}}
                             :script  [[:dispatch-sync [:halt/probe]]]}))]
      (rf/reg-event :halt/probe (fn [_ _] (swap! script-ran inc) {}))
      (probe-variant :story.cart/halt "not-a-vector")
      (probe-variant :story.cart/go [{:sku "A"}])
      (let [result (run-target :story.cart/halt)]
        (is (= :error (:status result)))
        (is (= [:cart] (-> result seed-error-record :violations first :path))
            "the stop is the structured :rf.error/story-db-seed-invalid record")
        (testing "the script never ran — its one dispatch was never handled"
          (is (zero? @script-ran))))
      (testing "the probe counts the same script behind a seed the schema
                accepts, so its zero above is the seed rejection's"
        (reset! script-ran 0)
        (is (= :pass (:status (run-target :story.cart/go))))
        (is (= 1 @script-ran))))))
