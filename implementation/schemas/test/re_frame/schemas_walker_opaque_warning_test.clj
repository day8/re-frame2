(ns re-frame.schemas-walker-opaque-warning-test
  "`:rf.warning/schema-walker-opaque`: `reg-app-schema` and `reg-app-schemas`
  warn once per process when a registered schema hides per-slot flags from the
  pure-data walker. Bare keywords never warn: a primitive cannot carry props,
  and a registry reference cannot be told from one."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- opaque-warnings
  "The tags of each walker-opaque warning recorded."
  [recorded]
  (into []
        (comp (filter #(and (= :warning (:op-type %))
                            (= :rf.warning/schema-walker-opaque (:operation %))))
              (map :tags))
        @recorded))

(defn- kinds [warnings]
  (mapv #(select-keys % [:schema-kind :path]) warnings))

(deftest warning-fires-when-schema-is-a-compiled-schema-object
  (with-trace-recorder! [recorded]
    (rf/reg-app-schema [:cart] (m/schema [:map [:a :int]]))
    (let [warnings (opaque-warnings recorded)]
      (is (= [{:schema-kind :compiled-schema-object :path [:cart]}] (kinds warnings)))
      ;; Spec 009's warning row: the reason names the vector-form fix.
      (is (re-find #"vector form" (:reason (first warnings)))))))

(deftest warning-fires-once-from-reg-app-schemas-bulk
  (with-trace-recorder! [recorded]
    (rf/reg-app-schemas {[:user]    {:malli/schema :user}
                         [:cart]    {:malli/schema :cart}
                         [:session] {:malli/schema :session}})
    (is (= 1 (count (opaque-warnings recorded))))))

(deftest warning-fires-when-schema-carries-a-local-registry
  ;; The root is a walkable vector form and its child a bare keyword, so only
  ;; the props tell the walk the referenced shape is out of its reach.
  (with-trace-recorder! [recorded]
    (rf/reg-app-schema [:auth]
                       [:schema {:registry {::user [:map [:pw {:sensitive? true} :string]]}}
                        ::user])
    (is (= [{:schema-kind :local-registry :path [:auth]}]
           (kinds (opaque-warnings recorded))))))

(deftest warning-suppressed-on-introspectable-schemas
  (with-trace-recorder! [recorded]
    (rf/reg-app-schema [:user]    [:map [:id :int] [:name :string]])
    (rf/reg-app-schema [:profile] :my/user-schema)
    (is (empty? (opaque-warnings recorded)))))
