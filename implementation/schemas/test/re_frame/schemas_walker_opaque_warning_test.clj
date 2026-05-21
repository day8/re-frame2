(ns re-frame.schemas-walker-opaque-warning-test
  "JVM tests for `:rf.warning/schema-walker-opaque` — the one-time
  process-lifecycle warning that fires from `reg-app-schema` /
  `reg-app-schemas` when the registered schema is NOT a Malli vector
  form (rf2-jsokn / rf2-ycqtv finding #12).

  Background — Spec 010 §The `:schema` value is opaque to re-frame: the
  schemas-walker (`re-frame.schemas.walker`) is pure data and handles
  only vector-form Malli EDN. Compiled `m/schema` values and registry
  refs (`:my/user-schema`) are treated as opaque leaves; per-slot
  `:sensitive?` / `:large?` flags inside an opaque value are silently
  skipped. This warning is the discoverability nudge that surfaces
  this misconfiguration once per process.

  Symmetric with `:rf.warning/schema-validator-unavailable` (rf2-fq7d2)
  — same emit-site, same warn-once-per-process pattern, same
  test-fixture cache-clear story."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

(defn- record-traces!
  "Attach a recording listener and return its atom."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warnings-of
  "Filter the recorded events to the given operation keyword."
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
         @recorded))

;; ---- positive paths -------------------------------------------------------

(deftest warning-fires-when-schema-is-registry-ref
  (testing "reg-app-schema with a registry-ref keyword (opaque to the
            walker) emits the warning exactly once"
    (let [recorded (record-traces! ::registry-ref)]
      (rf/reg-app-schema [:user] :my/user-schema)
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "exactly one warning fires on the first reg-app-schema call")
        (is (= :registry-ref (-> warns first :tags :schema-kind)))
        (is (= [:user] (-> warns first :tags :path)))))))

(deftest warning-fires-when-schema-is-compiled-map-object
  (testing "reg-app-schema with a compiled m/schema-like map value
            (opaque to the walker) emits the warning"
    (let [recorded (record-traces! ::compiled-map)]
      (rf/reg-app-schema [:cart] {:malli/schema :some-compiled-form})
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns)))
        (is (= :compiled-schema-object (-> warns first :tags :schema-kind)))))))

(deftest warning-suppressed-when-schema-is-vector-form
  (testing "reg-app-schema with a vector-form Malli schema (introspectable)
            does NOT emit the warning"
    (let [recorded (record-traces! ::vector-form)]
      (rf/reg-app-schema [:user] [:map [:id :int] [:name :string]])
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "vector-form schema -> no warning"))))

(deftest warning-fires-once-across-multiple-non-vector-calls
  (testing "subsequent reg-app-schema calls with opaque schemas within
            the same process do NOT re-emit the warning (process-
            lifecycle one-shot)"
    (let [recorded (record-traces! ::dedup-rereg)]
      (rf/reg-app-schema [:a] :my/schema-a)
      (rf/reg-app-schema [:b] :my/schema-b)
      (rf/reg-app-schema [:c] :my/schema-c)
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))
          "three registrations -> exactly one warning"))))

(deftest warning-fires-once-from-reg-app-schemas-bulk
  (testing "bulk reg-app-schemas with opaque schemas fires the warning
            once across all entries"
    (let [recorded (record-traces! ::bulk)]
      (rf/reg-app-schemas {[:user]    :my/user-schema
                           [:cart]    :my/cart-schema
                           [:session] :my/session-schema})
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))))))

(deftest warning-carries-actionable-reason
  (testing ":tags includes a :reason string that names the two workable
            shapes (vector form OR registration-level :sensitive?
            metadata)"
    (let [recorded (record-traces! ::reason)]
      (rf/reg-app-schema [:user] :my/user-schema)
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)
            tags  (-> warns first :tags)]
        (is (string? (:reason tags)))
        (is (re-find #"vector form" (:reason tags))
            ":reason names the vector-form fix")
        (is (re-find #"sensitive\?" (:reason tags))
            ":reason names the registration-meta fallback")))))

;; ---- cache-clear semantics ------------------------------------------------

(deftest cache-clear-allows-warning-to-fire-again
  (testing "clear-walker-opaque-warned! resets the one-shot so a
            subsequent reg-app-schema fires the warning anew (test-fixture
            isolation)"
    (let [recorded (record-traces! ::clear-cache)]
      (rf/reg-app-schema [:first] :my/schema-1)
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque))))
      (schemas/clear-walker-opaque-warned!)
      (rf/reg-app-schema [:second] :my/schema-2)
      (is (= 2 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))
          "after cache clear the warning fires again"))))
