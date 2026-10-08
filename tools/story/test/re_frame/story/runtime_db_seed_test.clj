(ns re-frame.story.runtime-db-seed-test
  "End-to-end run-path tests for the `:db-seed` fidelity rung: the seed is
  applied to a live frame before the script, and a seed that violates the
  frame's registered app-db schema fails the run with a structured
  `:rf.error/story-db-seed-invalid` before the script runs.

  The schemas artefact is not on Story's classpath, so these tests publish
  its late-bind hooks directly (backed by Malli), which also shows the
  runtime reaches validation only through that seam."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core         :as m]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story     :as rf.story]))

;; A per-frame `{frame-id {reg-path schema-meta}}` registry published under
;; the hook keys the real schemas artefact uses.
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
  (uninstall-schema-seam!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :cart/add-item
                   (fn [{:keys [db]} [_ item]]
                     {:db (update-in db [:cart :items] (fnil conj []) item)}))
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-target [target]
  (.get ^java.util.concurrent.CompletableFuture (rf.story/run target)))

(defn- seed-error-record [result]
  (first (filter #(= :rf.error/story-db-seed-invalid (:assertion %))
                 (:assertions result))))

(deftest valid-db-seed-seeds-app-db-before-script
  ;; No schemas seam is installed, so this is also the host-free floor: the
  ;; seed is applied unchecked.
  (rf.story/reg-variant
    :story.cart/seeded
    {:db-seed {:cart {:items [{:sku "A" :qty 1}]}}
     :script  [[:dispatch-sync [:cart/add-item {:sku "B" :qty 2}]]]})
  (let [result (run-target :story.cart/seeded)]
    (is (= :pass (:status result)))
    (is (= [{:sku "A" :qty 1} {:sku "B" :qty 2}]
           (get-in result [:app-db :cart :items])))))

(deftest db-seed-violating-schema-fails-run-structured
  (install-schema-seam!)
  ;; The probe counts the script's dispatch directly: a handler writing into a
  ;; malformed seed throws, so app-db cannot tell "never ran" from "ran and failed".
  (let [script-ran    (atom 0)
        probe-variant (fn [variant-id items]
                        (reg-app-schema! variant-id [:cart]
                                         [:map [:items [:vector [:map [:sku :string] [:qty :int]]]]])
                        (rf.story/reg-variant
                          variant-id
                          {:db-seed {:cart {:items items}}
                           :script  [[:dispatch-sync [:halt/probe]]]}))]
    (rf/reg-event :halt/probe (fn [_ _] (swap! script-ran inc) {}))
    (probe-variant :story.cart/bad [{:sku "A" :qty "two"}])
    (probe-variant :story.cart/go [{:sku "A" :qty 1}])
    (let [result (run-target :story.cart/bad)
          rec    (seed-error-record result)
          viol   (first (:violations rec))]
      (is (= [:error :error false] [(:status result) (:lifecycle result) (:passed? rec)]))
      (is (= {:path [:cart] :value {:items [{:sku "A" :qty "two"}]}}
             (select-keys viol [:path :value])))
      (is (some? (:explain viol)))
      (is (zero? @script-ran) "the script never ran"))
    (testing "control: the same script behind a seed the schema accepts runs"
      (is (= :pass (:status (run-target :story.cart/go))))
      (is (= 1 @script-ran)))))
