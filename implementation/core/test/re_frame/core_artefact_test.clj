(ns re-frame.core-artefact-test
  "Direct unit coverage for the `re-frame.core-artefact/defwrapper`
  absent-policy branches.

  Per the rf2-o7ayf audit (rf2-byut1 round-2 core review): the
  optional-artefact factory underpins ~ 30 wrapper fns across
  `core_<artefact>.cljc` (flows, routing, schemas, machines, ssr, epoch,
  http). Higher-level tests caught downstream symptoms (a wrapper used
  in an integration test would surface when the producing artefact was
  missing), but the absent-policy branches at the factory level had no
  direct coverage. This file locks each policy branch (`:throw`,
  `:nil`, `:false`, `:empty-vec`, `:empty-map`, literal value) against
  the late-bind registry.

  Coverage:
    - `:throw`     — `late-bind/require-fn!` raises the structured
                     :rf.error/<artefact>-artefact-missing ex-info with
                     the documented slots (:where, :reason, :recovery).
    - `:nil`       — returns nil when the hook is unregistered;
                     delegates to the hook when registered.
    - `:false`     — returns false when absent.
    - `:empty-vec` — returns [] when absent.
    - `:empty-map` — returns {} when absent.
    - literal      — returns the literal value when absent.
    - `:ex-data`   — symbol values resolve in the arity's locals and
                     ride the throw's ex-data."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core-artefact :refer [defwrapper]]
            [re-frame.late-bind :as late-bind]))

;; ---- fixture --------------------------------------------------------------

;; Each test installs its own hook(s); reset between tests so a stale
;; registration from one test doesn't leak into the next.

(defn- reset-late-bind [test-fn]
  ;; The `late-bind/hooks` registry is a defonce atom; snapshot and
  ;; restore around the test so each test installs its own hooks
  ;; without leaking into siblings or wiping framework registrations.
  (let [snap @late-bind/hooks
        test-keys [:test/throw-hook :test/nil-hook :test/false-hook
                   :test/empty-vec-hook :test/empty-map-hook
                   :test/literal-hook :test/ex-data-hook]]
    (doseq [k test-keys] (swap! late-bind/hooks dissoc k))
    (try (test-fn)
         (finally (reset! late-bind/hooks snap)))))

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
        (is (= 'rf/throw-wrapper (:where data))
            ":where stamps the user-facing fn name (default rf/<name>)")
        (is (= :no-recovery (:recovery data))
            ":recovery is :no-recovery for the missing-artefact branch")
        (is (re-find #"test/artefact" (:reason data))
            ":reason mentions the Maven artefact coordinates")
        (is (re-find #"re-frame.test-fake-artefact" (:reason data))
            ":reason mentions the producing ns name")
        (is (= ":rf.error/test-artefact-missing"
               (.getMessage ^Throwable e))
            "the exception message is the :error-keyword printed as a string")))))

(deftest throw-policy-delegates-when-hook-registered
  (testing ":throw delegates to the hook fn when registered"
    (late-bind/set-fn! :test/throw-hook (fn ([] :zero) ([x] [:one x])))
    (is (= :zero (throw-wrapper)))
    (is (= [:one 42] (throw-wrapper 42)))))

;; ---- :nil policy ----------------------------------------------------------

(defwrapper nil-wrapper
  "Test wrapper — :on-absent :nil."
  {:hook :test/nil-hook :artefact test-artefact :on-absent :nil}
  ([] :delegate))

(deftest nil-policy-returns-nil-when-absent
  (testing ":on-absent :nil returns nil when the hook is unregistered"
    (is (nil? (nil-wrapper)))))

(deftest nil-policy-delegates-when-present
  (testing ":on-absent :nil delegates when the hook is registered"
    (late-bind/set-fn! :test/nil-hook (fn [] :present))
    (is (= :present (nil-wrapper)))))

;; ---- :false policy --------------------------------------------------------

(defwrapper false-wrapper
  "Test wrapper — :on-absent :false."
  {:hook :test/false-hook :artefact test-artefact :on-absent :false}
  ([] :delegate))

(deftest false-policy-returns-false-when-absent
  (is (false? (false-wrapper))))

(deftest false-policy-delegates-when-present
  (late-bind/set-fn! :test/false-hook (fn [] :really))
  (is (= :really (false-wrapper))))

;; ---- :empty-vec policy ---------------------------------------------------

(defwrapper empty-vec-wrapper
  "Test wrapper — :on-absent :empty-vec."
  {:hook :test/empty-vec-hook :artefact test-artefact :on-absent :empty-vec}
  ([] :delegate))

(deftest empty-vec-policy-returns-empty-vec
  (is (= [] (empty-vec-wrapper))))

(deftest empty-vec-policy-delegates-when-present
  (late-bind/set-fn! :test/empty-vec-hook (fn [] [:a :b]))
  (is (= [:a :b] (empty-vec-wrapper))))

;; ---- :empty-map policy ---------------------------------------------------

(defwrapper empty-map-wrapper
  "Test wrapper — :on-absent :empty-map."
  {:hook :test/empty-map-hook :artefact test-artefact :on-absent :empty-map}
  ([] :delegate))

(deftest empty-map-policy-returns-empty-map
  (is (= {} (empty-map-wrapper))))

(deftest empty-map-policy-delegates-when-present
  (late-bind/set-fn! :test/empty-map-hook (fn [] {:k :v}))
  (is (= {:k :v} (empty-map-wrapper))))

;; ---- literal-value policy ------------------------------------------------

(defwrapper literal-wrapper
  "Test wrapper — :on-absent literal value (a sentinel keyword)."
  {:hook :test/literal-hook :artefact test-artefact :on-absent :rf/sentinel}
  ([] :delegate))

(deftest literal-policy-returns-the-literal-when-absent
  (is (= :rf/sentinel (literal-wrapper))))

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
