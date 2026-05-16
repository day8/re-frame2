(ns re-frame.schemas-validator-unavailable-warning-test
  "JVM tests for `:rf.warning/schema-validator-unavailable` — the
  one-time process-lifecycle warning that fires from `reg-app-schema`
  / `reg-app-schemas` when the Malli adapter is unloaded AND the
  framework-default validator is still installed (rf2-fq7d2).

  Background — Spec 010 §Recommended soft-pass: the schemas artefact
  ships with a Malli-delegating default validator that returns true
  ('pass') when the `:schemas/malli-validate` late-bind hook is
  unbound — i.e. when `re-frame.schemas.malli` hasn't been required
  at app boot. This is intentional (apps that swap in a non-Malli
  validator must work), but it has a footgun: a `reg-app-schema`
  call with no validator wired up validates nothing. The warning
  surfaces this misconfiguration once per process.

  The warning is suppressed when:
    - The Malli adapter is loaded (`:schemas/malli-validate` is
      bound). The validation hot path will run; no need to warn.
    - The app explicitly registered a non-default validator
      (a Zod port, clojure.spec bridge, etc.). The app opted out
      of Malli — the warning would be noise."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.trace :as trace]))

(use-fixtures :each tf/reset-runtime)

(defn- record-traces!
  "Attach a recording listener and return its atom."
  [listener-id]
  (let [a (atom [])]
    (rf/register-trace-cb! listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warnings-of
  "Filter the recorded events to the given operation keyword."
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
           @recorded))

(defn- with-unbound-malli-validate
  "Temporarily unbind `:schemas/malli-validate` so the warning's
  gating condition fires. The test-fixture's `reset-runtime` does
  NOT itself unload the Malli adapter ns (loading order is a
  process-wide concern), so we simulate the unloaded state by
  invalidating the hook around the test body."
  [f]
  (let [prior (late-bind/get-fn :schemas/malli-validate)]
    (try
      (swap! late-bind/hooks dissoc :schemas/malli-validate)
      (late-bind/invalidate-cache! :schemas/malli-validate)
      (f)
      (finally
        (when prior
          (late-bind/set-fn! :schemas/malli-validate prior))))))

;; ---- positive paths -------------------------------------------------------

(deftest warning-fires-when-hook-unbound-and-default-validator
  (testing "reg-app-schema with no Malli adapter loaded and the default
            validator still installed emits the warning exactly once"
    (let [recorded (record-traces! ::unbound+default)]
      (with-unbound-malli-validate
        (fn []
          (rf/reg-app-schema [:user] [:map [:id :int]])))
      (let [warns (warnings-of recorded
                               :rf.warning/schema-validator-unavailable)]
        (is (= 1 (count warns))
            "exactly one warning fires on the first reg-app-schema call")))))

(deftest warning-fires-once-across-multiple-reg-app-schema-calls
  (testing "subsequent reg-app-schema calls within the same process do NOT
            re-emit the warning (process-lifecycle one-shot)"
    (let [recorded (record-traces! ::dedup-rereg)]
      (with-unbound-malli-validate
        (fn []
          (rf/reg-app-schema [:user]    [:map [:id :int]])
          (rf/reg-app-schema [:cart]    [:vector :any])
          (rf/reg-app-schema [:session] [:map [:tok :string]])))
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-validator-unavailable)))
          "three registrations -> exactly one warning"))))

(deftest warning-fires-once-from-reg-app-schemas-bulk
  (testing "bulk reg-app-schemas registers many entries; the warning still
            fires once across all entries (each delegates to reg-app-schema
            but the warn-once cache dedupes)"
    (let [recorded (record-traces! ::bulk)]
      (with-unbound-malli-validate
        (fn []
          (rf/reg-app-schemas {[:user]    [:map [:id :int]]
                               [:cart]    [:vector :any]
                               [:session] [:map [:tok :string]]})))
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-validator-unavailable)))))))

(deftest warning-carries-actionable-reason
  (testing ":tags includes a :reason string that names the two fixes
            (require the Malli adapter ns OR install a custom validator)"
    (let [recorded (record-traces! ::reason)]
      (with-unbound-malli-validate
        (fn [] (rf/reg-app-schema [:user] [:map])))
      (let [warns (warnings-of recorded
                               :rf.warning/schema-validator-unavailable)
            tags  (-> warns first :tags)]
        (is (string? (:reason tags)))
        (is (re-find #"re-frame\.schemas\.malli" (:reason tags))
            ":reason names the Malli adapter ns")
        (is (re-find #"set-schema-validator!" (:reason tags))
            ":reason names the explicit-opt-out escape hatch")))))

;; ---- suppression — explicit non-default validator -------------------------

(deftest warning-suppressed-when-explicit-validator-installed
  (testing "an app that called set-schema-validator! with a non-default fn
            has explicitly opted out of Malli — no warning fires"
    (let [recorded (record-traces! ::opt-out)]
      (with-unbound-malli-validate
        (fn []
          (rf/set-schema-validator! (fn [_schema _value] true))
          (rf/reg-app-schema [:user] [:map [:id :int]])))
      (is (empty? (warnings-of recorded
                               :rf.warning/schema-validator-unavailable))
          "explicit non-default validator suppresses the warning"))))

(deftest warning-suppressed-when-set-schema-validator-map-arity-installs-validate
  (testing "set-schema-validator! with a {:validate ...} map also counts as
            'explicit opt-out' — non-default validator-fn after the swap"
    (let [recorded (record-traces! ::opt-out-map)]
      (with-unbound-malli-validate
        (fn []
          (rf/set-schema-validator! {:validate (fn [_ _] true)})
          (rf/reg-app-schema [:user] [:map])))
      (is (empty? (warnings-of recorded
                               :rf.warning/schema-validator-unavailable))))))

;; ---- suppression — Malli adapter loaded -----------------------------------

(deftest warning-suppressed-when-malli-validate-hook-bound
  (testing "with the Malli adapter loaded (`:schemas/malli-validate`
            is bound) the validation hot path runs — no warning"
    (let [recorded (record-traces! ::malli-loaded)]
      (late-bind/set-fn! :schemas/malli-validate (fn [_ _] true))
      (try
        (rf/reg-app-schema [:user] [:map])
        (finally
          ;; Restore the slot for sibling tests (the fixture restores the
          ;; validator-fn but not the late-bind hook table).
          nil))
      (is (empty? (warnings-of recorded
                               :rf.warning/schema-validator-unavailable))
          ":schemas/malli-validate bound -> warning suppressed"))))

;; ---- cache-clear semantics ------------------------------------------------

(deftest cache-clear-allows-warning-to-fire-again
  (testing "clear-validator-unavailable-warned! resets the one-shot so a
            subsequent reg-app-schema fires the warning anew (test-fixture
            isolation)"
    (let [recorded (record-traces! ::clear-cache)]
      (with-unbound-malli-validate
        (fn []
          (rf/reg-app-schema [:first] [:map])
          (is (= 1 (count (warnings-of recorded
                                       :rf.warning/schema-validator-unavailable))))
          (schemas/clear-validator-unavailable-warned!)
          (rf/reg-app-schema [:second] [:map])
          (is (= 2 (count (warnings-of recorded
                                       :rf.warning/schema-validator-unavailable)))
              "after cache clear the warning fires again"))))))
