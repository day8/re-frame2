(ns re-frame.core-artefact-test
  "Direct unit coverage for the `re-frame.core-artefact/defwrapper`
  absent-policy branches.

  The optional-artefact factory underpins ~ 30 wrapper fns across
  `core_<artefact>.cljc` (flows, routing, schemas, machines, ssr, epoch,
  http). Higher-level tests see only downstream symptoms (a wrapper used
  in an integration test surfaces when the producing artefact is
  missing), so this file locks each absent-policy branch at the factory
  level (`:throw`, `:nil`, `:false`, `:empty-vec`, `:empty-map`, literal
  value) against the late-bind registry.

  Coverage:
    - `:throw`     — `rf.late-bind/require-fn!` raises the structured
                     :rf.error/<artefact>-artefact-missing ex-info with
                     the documented slots (:where, :reason, :recovery).
    - `:nil`       — returns nil when absent.
    - `:false`     — returns false when absent.
    - `:empty-vec` — returns [] when absent.
    - `:empty-map` — returns {} when absent.
    - literal      — returns the literal value when absent.
    - every policy delegates to the hook when it is registered.
    - `:ex-data`   — symbol values resolve in the arity's locals and
                     ride the throw's ex-data."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core-artefact :refer [defwrapper]]
            [re-frame.late-bind :as rf.late-bind]))

;; ---- fixture --------------------------------------------------------------

;; Each test installs its own hook(s); reset between tests so a stale
;; registration from one test doesn't leak into the next.

(defn- reset-late-bind [test-fn]
  ;; The `rf.late-bind/hooks` registry is a defonce atom; snapshot and
  ;; restore around the test so each test installs its own hooks
  ;; without leaking into siblings or wiping framework registrations.
  (let [snap @rf.late-bind/hooks
        test-keys [:test/throw-hook :test/nil-hook :test/false-hook
                   :test/empty-vec-hook :test/empty-map-hook
                   :test/literal-hook :test/ex-data-hook]]
    (doseq [k test-keys] (swap! rf.late-bind/hooks dissoc k))
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
  (testing ":on-absent :throw raises :rf.error/<artefact>-artefact-missing"
    (let [e (try (throw-wrapper) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "the wrapper threw")
      (let [data (ex-data e)]
        (is (= :rf.error/test-artefact-missing (:rf.error/id data))
            ":rf.error/id carries the canonical discriminator (per Spec 009 §The thrown-error shape)")
        (is (= 'rf/throw-wrapper (:where data))
            ":where stamps the user-facing fn name (default rf/<name>)")
        (is (= :no-recovery (:recovery data))
            ":recovery is :no-recovery for the missing-artefact branch")
        (is (re-find #"test/artefact" (:reason data))
            ":reason mentions the Maven artefact coordinates")
        (is (re-find #"re-frame.test-fake-artefact" (:reason data))
            ":reason mentions the producing ns name")
        ;; The message is the human :reason sentence + the
        ;; trailing [:rf.error/<id>] greppability token, NOT the bare
        ;; stringified keyword. Assert the token substring, not equality.
        (is (re-find #"\[:rf\.error/test-artefact-missing\]"
                     (.getMessage ^Throwable e))
            "the exception message carries the [:rf.error/test-artefact-missing] token")))))

(deftest throw-policy-delegates-when-hook-registered
  (testing ":throw delegates to the hook fn when registered"
    (rf.late-bind/set-fn! :test/throw-hook (fn ([] :zero) ([x] [:one x])))
    (is (= :zero (throw-wrapper)))
    (is (= [:one 42] (throw-wrapper 42)))))

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

(deftest each-silent-policy-returns-its-value-when-the-hook-is-absent
  (testing "an unregistered hook returns the policy's value, one row per policy"
    (are [expected wrapper] (= expected (wrapper))
      nil          nil-wrapper
      false        false-wrapper
      []           empty-vec-wrapper
      {}           empty-map-wrapper
      :rf/sentinel literal-wrapper)))

(deftest each-silent-policy-delegates-when-the-hook-is-registered
  (testing "a registered hook is called and its value returned, whatever the
            absent-policy"
    (doseq [[hook wrapper value] [[:test/nil-hook       nil-wrapper       :present]
                                  [:test/false-hook     false-wrapper     :really]
                                  [:test/empty-vec-hook empty-vec-wrapper [:a :b]]
                                  [:test/empty-map-hook empty-map-wrapper {:k :v}]]]
      (rf.late-bind/set-fn! hook (fn [] value))
      (is (= value (wrapper)) (str hook " delegates to the registered hook")))))

;; ---- :ex-data sym scoping -----------------------------------------------

(defwrapper ex-data-wrapper
  "Test wrapper — :ex-data carries a symbol that resolves in the arity locals."
  {:hook :test/ex-data-hook :artefact test-artefact :on-absent :throw
   :ex-data {:item-id id}}
  ([id] :delegate))

(deftest ex-data-symbol-rides-the-throw
  (testing ":ex-data symbol values resolve in the arity's local scope"
    (let [e (try (ex-data-wrapper :my-id) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= :my-id (:item-id (ex-data e)))
          ":item-id rides the throw's ex-data, sourced from the local"))))
