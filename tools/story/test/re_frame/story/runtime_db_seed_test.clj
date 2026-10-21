(ns re-frame.story.runtime-db-seed-test
  "End-to-end run-path tests for the `:db-seed` fidelity rung: the seed is
  applied to a live frame before the script, and a seed that violates the
  frame's registered app-db schema fails the run with a structured
  `:rf.error/story-db-seed-invalid` before the script runs.

  The tests publish only the two late-bind hooks the runtime reads: the
  schemas artefact's real `:schemas/check!` seam, and a per-frame stub of
  `:schemas/frame-schema-entries`. The artefact's facade is never loaded, so
  no other hook is published, which also shows the runtime reaches
  validation only through those seams."
  (:require [clojure.string     :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core      :as rf]
            [re-frame.frame     :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.privacy   :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas.validate  :as rf.schemas.validate]
            [re-frame.schemas.validator :as rf.schemas.validator]
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
  (rf.late-bind/set-fn! :schemas/check! rf.schemas.validate/check!))

(defn- uninstall-schema-seam! []
  (swap! rf.late-bind/hooks dissoc
         :schemas/frame-schema-entries
         :schemas/check!))

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
      (is (= {:items [{:qty ["should be an integer"]}]} (:explain-humanized viol))
          "the seam humanizes the explanation in development")
      (is (zero? @script-ran) "the script never ran"))
    (testing "control: the same script behind a seed the schema accepts runs"
      (is (= :pass (:status (run-target :story.cart/go))))
      (is (= 1 @script-ran)))))

(defn- seeded-variant! [variant-id reg-path schema seed]
  (reg-app-schema! variant-id reg-path schema)
  (rf.story/reg-variant
    variant-id
    {:db-seed seed
     :script  [[:dispatch-sync [:cart/add-item {:sku "B" :qty 2}]]]}))

(deftest db-seed-check-skips-an-absent-path-and-checks-a-present-nil
  ;; As at commit (spec/010 §When schemas are checked): a path schema
  ;; constrains a value only where its path exists.
  (install-schema-seam!)
  (seeded-variant! :story.cart/no-coupon [:coupon] :string {:cart {:items []}})
  (seeded-variant! :story.cart/nil-coupon [:coupon] :string {:cart {:items []} :coupon nil})
  (is (= :pass (:status (run-target :story.cart/no-coupon)))
      "a registered path the seeded db lacks is not checked")
  (is (= [{:path [:coupon] :value nil}]
         (mapv #(select-keys % [:path :value])
               (:violations (seed-error-record (run-target :story.cart/nil-coupon)))))
      "a present nil is checked"))

(deftest db-seed-check-delegates-a-nil-schema-token
  ;; A schema token is opaque, so a registered `nil` is a declaration the
  ;; validator interprets, as at commit. This validator refuses every value
  ;; under a `nil` token.
  (install-schema-seam!)
  (let [installed (rf.schemas.validator/schema-fns)]
    (try
      (rf.schemas.validator/set-schema-fns! {:validate (fn [schema _value] (some? schema))
                                             :explain  nil})
      (seeded-variant! :story.cart/nil-token [:cart] nil {:cart {:items []}})
      (is (= [[:cart]]
             (mapv :path (:violations (seed-error-record (run-target :story.cart/nil-token))))))
      (finally (rf.schemas.validator/set-schema-fns! installed)))))

(deftest db-seed-violation-redacts-a-sensitive-slot
  ;; A seeded violation rides the run result and the thrown ex-data, so a
  ;; `:sensitive?` slot in the schema redacts it, as every validation failure
  ;; redacts (spec/010 §`:sensitive?`).
  (install-schema-seam!)
  (seeded-variant! :story.auth/bad-seed [:auth]
                   [:map [:token {:sensitive? true} :string] [:age :int]]
                   {:auth {:token "SEEDED-SECRET-42" :age "not-an-int"}})
  (let [viol (first (:violations (seed-error-record (run-target :story.auth/bad-seed))))]
    (is (= [[:auth] rf.privacy/redacted-sentinel rf.privacy/redacted-sentinel true]
           [(:path viol) (:value viol) (:explain viol) (:sensitive? viol)]))
    (is (not (str/includes? (pr-str viol) "SEEDED-SECRET-42")))))

(deftest db-seed-malformed-schema-is-a-violation-without-the-value
  (install-schema-seam!)
  (seeded-variant! :story.cart/malformed [:cart] [:vector] {:cart {:items ["SEEDED-VALUE"]}})
  (let [viol (first (:violations (seed-error-record (run-target :story.cart/malformed))))]
    (is (= [[:cart] :rf.error/malformed-schema false]
           [(:path viol) (:category viol) (contains? viol :value)]))))
