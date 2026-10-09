(ns re-frame.schemas-failure-paths-test
  "The app-db failure trace locates the failing leaf: `:path` is the
  registered path plus the explainer's `:in` (the value's navigation path),
  `:registered-path` is the registration root, and `:value` is the failing
  datum. Sensitivity is decided at that leaf, so a sensitive sibling does not
  redact a non-sensitive leaf's narrowed `:value`. With no extractable `:in`
  the trace falls back to the registered root and whole-schema sensitivity."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- capture-trace
  "The schema-validation-failure traces `body-fn` emits."
  [body-fn]
  (with-trace-recorder! [traces]
    (body-fn)
    (filterv #(= :rf.error/schema-validation-failure (:operation %)) @traces)))

(defn- locator
  "The locating slots of a failure trace."
  [v]
  (select-keys (:tags v) [:path :registered-path :value]))

(deftest path-is-leaf-when-explainer-reports-in
  (rf/reg-app-schema [:user] [:map [:id :int] [:email :string]])
  (is (= [{:path [:user :id] :registered-path [:user] :value "not-an-int"
           :failing-id :user/set-bad :where :app-db}]
         (map #(select-keys (:tags %) [:path :registered-path :value :failing-id :where])
              (capture-trace #(rf.schemas/validate-app-schema!
                                {:user {:id "not-an-int" :email "alice@example.com"}}
                                :user/set-bad))))))

(deftest both-sensitive-and-clean-failures-handled-independently
  ;; Each registration's failure is classified on its own, and a failure
  ;; does not stop the remaining registrations from being validated.
  (rf/reg-app-schema [:auth] [:map [:token {:sensitive? true} :string]])
  (rf/reg-app-schema [:count] [:int])
  (is (= #{[[:auth] true :rf/redacted] [[:count] nil "not-an-int"]}
         (set (map (juxt (comp :registered-path :tags) :sensitive? (comp :value :tags))
                   (capture-trace #(rf.schemas/validate-app-schema!
                                     {:auth {:token 42} :count "not-an-int"}
                                     :bulk/bad)))))))

(deftest fallback-uses-the-registered-root-when-no-leaf-is-extractable
  ;; An explainer with no `:in` (here, none at all) leaves the leaf unknown:
  ;; :path is the root and sensitivity is the whole schema's.
  (rf.schemas/set-schema-fns! {:validate (fn [_ _] false) :explain (fn [_ _] nil)})
  (are [schema value expected]
       (= expected
          (let [[v :as all] (do (rf.schemas/clear-schemas-by-frame!)
                                (rf/reg-app-schema [:slot] schema)
                                (capture-trace #(rf.schemas/validate-app-schema! {:slot value} :s/bad)))]
            [(count all) (:sensitive? v) (locator v)]))
    [:map [:password {:sensitive? true} :string]] {:password "pw"}
    [1 true {:path [:slot] :registered-path [:slot] :value :rf/redacted}]

    [:int] "x"
    [1 nil {:path [:slot] :registered-path [:slot] :value "x"}]))

(deftest schema-sensitive-at?-flags-the-leaf-its-ancestors-and-descendants
  (testing "a failing slot is sensitive when it, an ancestor or a descendant it
            carries is flagged, never because of a sibling; a nil path is the
            whole schema"
    (let [sens       [:map [:p {:sensitive? true} :string]]
          not-sens   [:map [:p :string]]
          ancestor   [:map {:sensitive? true} [:token :string] [:expiry :int]]
          descendant [:map [:user [:map [:name :string] [:password {:sensitive? true} :string]]]]]
      (are [expected schema path] (= expected (rf.schemas/schema-sensitive-at? schema path))
        true  sens       nil
        false not-sens   nil
        true  ancestor   [:token]
        true  descendant [:user]
        true  descendant [:user :password]
        false descendant [:user :name]))))

(deftest multi-error-path-narrows-to-common-ancestor
  ;; Two diverging failures under one registration narrow to the parent slot
  ;; that holds both, whose value is the failing datum.
  (rf/reg-app-schema [:root] [:map [:user [:map [:id :int] [:age :int]]]])
  (is (= [{:path [:root :user] :registered-path [:root] :value {:id "bad" :age "also-bad"}}]
         (map locator (capture-trace #(rf.schemas/validate-app-schema!
                                        {:root {:user {:id "bad" :age "also-bad"}}}
                                        :root/bad))))))

;; Malli reports a :map-of key failure and a value failure under the same
;; `:in [k]`, so reading the value at `:in` would blame the entry's valid
;; value. The dev trace and the always-on `:errors` record both describe the
;; failing key.
(deftest map-of-key-failure-reports-the-failing-key
  (rf/reg-app-schema [:scores] [:map-of :keyword :int])
  (let [errors (atom [])
        traces (do (rf.error-emit/register-error-listener! ::rec (fn [r] (swap! errors conj r)))
                   (try
                     (capture-trace #(rf.schemas/validate-app-schema! {:scores {"alice" 10}}
                                                                       :scores/bad))
                     (finally
                       (rf.error-emit/unregister-error-listener! ::rec))))
        record (first (filter #(= :app-db (:where %)) @errors))]
    (is (= [{:path [:scores "alice"] :registered-path [:scores] :value "alice"}]
           (map locator traces)))
    (is (str/includes? (:reason record) "(got string)") (:reason record)))
  (testing "control: a :map-of value failure still reports the value"
    (is (= [{:path [:scores :alice] :registered-path [:scores] :value "ten"}]
           (map locator (capture-trace #(rf.schemas/validate-app-schema!
                                          {:scores {:alice "ten"}} :scores/bad)))))))
