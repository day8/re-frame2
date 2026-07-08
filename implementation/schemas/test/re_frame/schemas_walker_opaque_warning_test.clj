(ns re-frame.schemas-walker-opaque-warning-test
  "JVM tests for `:rf.warning/schema-walker-opaque` — the one-time
  process-lifecycle warning that fires from `reg-app-schema` /
  `reg-app-schemas` when the registered schema is NOT a Malli vector
  form (rf2-jsokn / rf2-ycqtv finding #12).

  Background — Spec 010 §The `:schema` value is opaque to re-frame: the
  schemas-walker (`re-frame.schemas.walker`) is pure data and handles
  only vector-form Malli EDN. Compiled `m/schema` values are treated as
  opaque leaves; per-slot `:sensitive?` / `:large?` flags inside an
  opaque value are silently skipped. This warning is the discoverability
  nudge that surfaces this misconfiguration once per process.

  Per rf2-ee38b.6 (correctness P2): keyword schemas do NOT warn. A bare
  keyword is non-vector but is a valid Malli schema (primitive `:int` /
  `:string` OR registry ref `:my/user-schema`); a keyword cannot carry
  per-slot props, so the walker provably skips nothing on a primitive —
  warning on every keyword was a frequent false positive on the common
  case. The predicate cannot cheaply distinguish primitive from
  registry-ref keywords without a registry consult (forbidden by Spec
  010 §opaque), so the keyword case is suppressed entirely.

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
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- warnings-of
  "Filter the recorded events to the given operation keyword."
  [recorded operation]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= operation (:operation ev))))
         @recorded))

;; ---- positive paths -------------------------------------------------------
;;
;; Only genuinely opaque NON-keyword values (compiled m/schema-like
;; maps) warn. Keyword schemas — primitive AND registry-ref — are
;; suppressed (rf2-ee38b.6 correctness P2); see the negative-path
;; section below.

(deftest warning-fires-when-schema-is-compiled-map-object
  (testing "reg-app-schema with a compiled m/schema-like map value
            (opaque to the walker) emits the warning exactly once"
    (let [recorded (record-traces! ::compiled-map)]
      (rf/reg-app-schema [:cart] {:schema {:malli/schema :some-compiled-form}})
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "exactly one warning fires on the first reg-app-schema call")
        (is (= :compiled-schema-object (-> warns first :tags :schema-kind)))
        (is (= [:cart] (-> warns first :tags :path)))))))

(deftest warning-fires-once-across-multiple-opaque-calls
  (testing "subsequent reg-app-schema calls with opaque (compiled-map)
            schemas within the same process do NOT re-emit the warning
            (process-lifecycle one-shot)"
    (let [recorded (record-traces! ::dedup-rereg)]
      (rf/reg-app-schema [:a] {:schema {:malli/schema :a}})
      (rf/reg-app-schema [:b] {:schema {:malli/schema :b}})
      (rf/reg-app-schema [:c] {:schema {:malli/schema :c}})
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))
          "three registrations -> exactly one warning"))))

(deftest warning-fires-once-from-reg-app-schemas-bulk
  (testing "bulk reg-app-schemas with opaque schemas fires the warning
            once across all entries"
    (let [recorded (record-traces! ::bulk)]
      (rf/reg-app-schemas {[:user]    {:malli/schema :user}
                           [:cart]    {:malli/schema :cart}
                           [:session] {:malli/schema :session}})
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))))))

(deftest warning-carries-actionable-reason
  (testing "rf2-k0ew8n — :tags includes a :reason string that names the
            ONE supported shape (register the vector form) and does NOT
            recommend the REMOVED registration-meta `:sensitive?` fallback"
    (let [recorded (record-traces! ::reason)]
      (rf/reg-app-schema [:user] {:schema {:malli/schema :user}})
      (let [warns  (warnings-of recorded :rf.warning/schema-walker-opaque)
            tags   (-> warns first :tags)
            reason (:reason tags)]
        (is (string? reason))
        (is (re-find #"vector form" reason)
            ":reason names the vector-form fix (the supported shape)")
        ;; The stale guidance MUST be gone: the reason must not steer
        ;; users to USE handler/cofx/sub registration-meta `:sensitive?`
        ;; as a workaround — that annotation is removed and the redactor
        ;; deliberately ignores it (sensitivity is path-targeted). It is
        ;; fine (and intended) for the reason to NAME the fallback only to
        ;; say it has been removed.
        (is (re-find #"(?i)removed" reason)
            ":reason states the registration-meta fallback was removed")
        (is (not (re-find #"(?i)(use|via) .{0,40}registration[- ]?(level|meta)"
                          reason))
            "no positive recommendation to USE the registration-meta fallback")))))

(deftest warning-fires-when-vector-form-schema-nests-an-opaque-child
  (testing "rf2-hi0tf8 — a VECTOR-FORM schema (introspectable at its root)
            that embeds a compiled m/schema value as a NESTED child (a
            :map slot's tail) also emits the warning; the root-only
            `schema-opaque?` check used to miss this and stay silent"
    (let [recorded (record-traces! ::nested-opaque-child)]
      (rf/reg-app-schema [:token]
                         {:schema [:map [:secret {} {:malli/schema :compiled}]]})
      (let [warns (warnings-of recorded :rf.warning/schema-walker-opaque)]
        (is (= 1 (count warns))
            "a nested opaque child triggers the warning exactly once")
        (is (= [:token] (-> warns first :tags :path)))))))

;; ---- negative paths (no warning) ------------------------------------------

(deftest warning-suppressed-when-schema-is-vector-form
  (testing "reg-app-schema with a vector-form Malli schema (introspectable)
            does NOT emit the warning"
    (let [recorded (record-traces! ::vector-form)]
      (rf/reg-app-schema [:user] {:schema [:map [:id :int] [:name :string]]})
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "vector-form schema -> no warning"))))

(deftest warning-suppressed-on-primitive-keyword-schemas
  (testing "rf2-ee38b.6 — primitive keyword schemas (`:int` / `:string`
            / `:boolean` / `:any`) are valid Malli schemas that cannot
            carry per-slot props; the walker provably skips nothing, so
            registering one does NOT emit the false-positive warning"
    (let [recorded (record-traces! ::primitive-kw)]
      (rf/reg-app-schema [:age]    {:schema :int})
      (rf/reg-app-schema [:name]   {:schema :string})
      (rf/reg-app-schema [:active] {:schema :boolean})
      (rf/reg-app-schema [:misc]   {:schema :any})
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "primitive keyword schemas -> no spurious 'per-slot flags
           skipped' nudge"))))

(deftest warning-suppressed-on-registry-ref-keyword-schemas
  (testing "rf2-ee38b.6 — registry-ref keyword schemas (`:my/user-schema`)
            also do NOT warn: they are indistinguishable from primitive
            keywords without a forbidden registry consult, so the
            keyword case is suppressed entirely. The advanced registry-
            ref-hides-per-slot-flags shape is covered by the walker
            docstring's discoverability caveat (rf2-yaioz)"
    (let [recorded (record-traces! ::registry-ref)]
      (rf/reg-app-schema [:user] {:schema :my/user-schema})
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "registry-ref keyword schema -> no warning"))))

;; ---- cache-clear semantics ------------------------------------------------

(deftest cache-clear-allows-warning-to-fire-again
  (testing "clear-walker-opaque-warned! resets the one-shot so a
            subsequent reg-app-schema fires the warning anew (test-fixture
            isolation)"
    (let [recorded (record-traces! ::clear-cache)]
      (rf/reg-app-schema [:first] {:schema {:malli/schema :first}})
      (is (= 1 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque))))
      (schemas/clear-walker-opaque-warned!)
      (rf/reg-app-schema [:second] {:schema {:malli/schema :second}})
      (is (= 2 (count (warnings-of recorded
                                   :rf.warning/schema-walker-opaque)))
          "after cache clear the warning fires again"))))
