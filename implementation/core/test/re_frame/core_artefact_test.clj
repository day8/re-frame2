(ns re-frame.core-artefact-test
  "Unit coverage for `re-frame.core-artefact/defwrapper`: each absent-policy
  branch, delegation when the hook is registered, and `:ex-data` scoping."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.core-artefact :refer [defwrapper]]
            [re-frame.late-bind :as rf.late-bind]))

(defn- reset-late-bind [test-fn]
  (let [snap @rf.late-bind/hooks]
    (try (test-fn)
         (finally (reset! rf.late-bind/hooks snap)))))

(use-fixtures :each reset-late-bind)

;; ---- the artefact descriptor used across the wrappers below --------------

(def ^:private test-artefact
  {:error-keyword :rf.error/test-artefact-missing
   :maven         "test/artefact"
   :require-ns    "re-frame.test-fake-artefact"})

;; ---- :throw policy --------------------------------------------------------

(defwrapper throw-wrapper
  "Test wrapper — :on-absent :throw."
  {:hook :test/throw-hook :artefact test-artefact :on-absent :throw}
  ([] :delegate)
  ([x] :delegate))

(deftest throw-policy-raises-structured-ex-info
  (let [e    (try (throw-wrapper) nil
                  (catch clojure.lang.ExceptionInfo e e))
        data (ex-data e)]
    (is (= {:rf.error/id :rf.error/test-artefact-missing
            :where       'rf/throw-wrapper
            :recovery    :no-recovery}
           (select-keys data [:rf.error/id :where :recovery])))
    (is (re-find #"test/artefact" (:reason data)) ":reason names the Maven artefact")
    (is (re-find #"re-frame.test-fake-artefact" (:reason data)) ":reason names the producing ns")
    (is (re-find #"\[:rf\.error/test-artefact-missing\]" (.getMessage ^Throwable e)))))

;; ---- the silent policies: :nil / :false / :empty-vec / :empty-map / literal -

(defwrapper nil-wrapper
  "Test wrapper — :on-absent :nil."
  {:hook :test/nil-hook :artefact test-artefact :on-absent :nil}
  ([] :delegate))

(defwrapper false-wrapper
  "Test wrapper — :on-absent :false."
  {:hook :test/false-hook :artefact test-artefact :on-absent :false}
  ([] :delegate))

(defwrapper empty-vec-wrapper
  "Test wrapper — :on-absent :empty-vec."
  {:hook :test/empty-vec-hook :artefact test-artefact :on-absent :empty-vec}
  ([] :delegate))

(defwrapper empty-map-wrapper
  "Test wrapper — :on-absent :empty-map."
  {:hook :test/empty-map-hook :artefact test-artefact :on-absent :empty-map}
  ([] :delegate))

(defwrapper literal-wrapper
  "Test wrapper — :on-absent literal value (a sentinel keyword)."
  {:hook :test/literal-hook :artefact test-artefact :on-absent :rf/sentinel}
  ([] :delegate))

(deftest a-registered-hook-is-called-on-both-policy-paths
  (rf.late-bind/set-fn! :test/throw-hook (fn ([] :zero) ([x] [:one x])))
  (rf.late-bind/set-fn! :test/nil-hook (fn [] :present))
  (is (= :zero (throw-wrapper)))
  (is (= [:one 42] (throw-wrapper 42)))
  (is (= :present (nil-wrapper))))

(deftest each-silent-policy-returns-its-value-when-the-hook-is-absent
  (are [expected wrapper] (= expected (wrapper))
    nil          nil-wrapper
    false        false-wrapper
    []           empty-vec-wrapper
    {}           empty-map-wrapper
    :rf/sentinel literal-wrapper))

;; ---- :ex-data sym scoping -----------------------------------------------

(defwrapper ex-data-wrapper
  "Test wrapper — :ex-data carries a symbol that resolves in the arity locals."
  {:hook :test/ex-data-hook :artefact test-artefact :on-absent :throw
   :ex-data {:item-id id}}
  ([id] :delegate))

(deftest ex-data-symbol-rides-the-throw
  (let [e (try (ex-data-wrapper :my-id) nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :my-id (:item-id (ex-data e))))))
