(ns re-frame.routing-check-seam-test
  "A named address that fails its route's `:params` schema is checked through
  the `:schemas/check!` seam (Spec 010 §The validation seam): the navigate
  door's trace carries `:failing-id`, `:frame` and, in development,
  `:explain-humanized`, `route-url`'s thrown ex-data is redacted against the
  schema, and a validator that throws reports `:rf.error/malformed-schema`.
  The traces are development-only, so under the production-gate lane
  (`-Dre-frame.debug=false`) the trace assertions read none, while the
  thrown ex-data holds in both postures."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.privacy :as rf.privacy]
            [re-frame.routing :as rf.routing]
            [re-frame.routing-test-support :as rf.routing-test-support]
            [re-frame.schemas :as rf.schemas]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.routing-test-support/reset-runtime)

(defn- navigate-traces
  "The `op` traces a navigate to `address` emits."
  [op address]
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [:rf.route/navigate address])
    (filterv #(= op (:operation %)) @traces)))

(def ^:private seam-slots [:where :route-id :failing-id :frame])

(deftest a-params-failure-carries-the-seam-tags
  (rf/reg-route :seam/article {:params [:map [:id :int]]} "/articles/:id")
  (let [[ev :as evs] (navigate-traces :rf.error/schema-validation-failure
                                      {:to :seam/article :params {:id "x"}})]
    (if rf.interop/debug-enabled?
      (do (is (= 1 (count evs)))
          (is (= {:where :event :route-id :seam/article :failing-id :seam/article :frame :rf/default}
                 (select-keys (:tags ev) seam-slots)))
          (is (= {:id ["should be an integer"]} (get-in ev [:tags :explain-humanized]))))
      (is (empty? evs) "a production build emits no trace"))))

(deftest route-url-redacts-a-sensitive-param-in-its-thrown-error
  (rf/reg-route :seam/doc {:params [:map [:id {:sensitive? true} :int]]} "/docs/:id")
  (let [data (try (rf.routing/route-url {:to :seam/doc :params {:id "SECRET-DOC-ID"}})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= [:rf.error/route-url-validation :params rf.privacy/redacted-sentinel true]
           ((juxt :rf.error/id :slot :value :sensitive?) data)))
    (is (not (str/includes? (pr-str data) "SECRET-DOC-ID")))))

(deftest a-validator-throw-is-a-malformed-schema
  (rf/reg-route :seam/article {:params [:map [:id :int]]} "/articles/:id")
  (let [installed (rf.schemas/schema-fns)
        [evs data] (try
                     (rf.schemas/set-schema-fns! {:validate (fn [_ _] (throw (ex-info "boom" {})))})
                     [(navigate-traces :rf.error/malformed-schema {:to :seam/article :params {:id 1}})
                      (try (rf.routing/route-url {:to :seam/article :params {:id 1}})
                           nil
                           (catch clojure.lang.ExceptionInfo e (ex-data e)))]
                     (finally (rf.schemas/set-schema-fns! installed)))]
    (is (= (if rf.interop/debug-enabled?
             [{:where :event :route-id :seam/article :failing-id :seam/article :frame :rf/default}]
             [])
           (mapv #(select-keys (:tags %) seam-slots) evs)))
    (is (= [:rf.error/route-url-validation :malformed-schema rf.privacy/redacted-sentinel true]
           ((juxt :rf.error/id :reason :value :sensitive?) data))
        "the throw withholds a value the validator decided nothing about")))
