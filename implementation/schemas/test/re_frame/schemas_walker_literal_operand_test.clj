(ns re-frame.schemas-walker-literal-operand-test
  "JVM tests for the operator-aware schema-opacity walk (rf2-3fc89f.12).

  Bug: `re-frame.schemas.walker/schema-has-opaque-child?` recursed into EVERY
  vector tail as if it were a child schema. For literal-bearing Malli operators
  the tail is DATA, not a nested schema, so any non-keyword/non-fn/non-symbol
  literal reached the opaque catch-all and was mis-classified as an opaque
  compiled child. `[:= 42]`, `[:enum 1 2]`, `[:> 10]`, `[:re \"x\"]`,
  `[:cat [:= :id] [:= 42]]` all reported `schema-has-opaque-child? => true`,
  which stamped ordinary literal/enum validation failures `:sensitive?`,
  redacted their inspectable values, and emitted a spurious
  `:rf.warning/schema-walker-opaque` at registration.

  Fix (DESIGN): an operator-aware child-schema projection recurses only real
  child-schema positions; known literal/config operands are treated as data
  (not recursed); a genuinely opaque value in a true schema-bearing position
  and an unknown operator shape still fail closed. Classification is structural
  (no Malli/validator introspection) and identical on CLJ + CLJS — the shared
  corpus lives in `re-frame.schemas.walker-literal-operand-fixtures` and is
  asserted on both runtimes (`...-cljs-test` is the CLJS half).

  Every assertion below fails on the pre-fix walker and passes on the fixed
  one; the last section pins the fail-closed guarantees the fix must preserve."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.schemas.test-fixture :as rf.schemas.test-fixture]
            [re-frame.schemas.walker-literal-operand-fixtures :as rf.schemas.walker-literal-operand-fixtures]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.schemas.test-fixture/reset-runtime)

(defn- warnings-of [recorded operation]
  (filterv (fn [ev] (and (= :warning (:op-type ev))
                         (= operation (:operation ev))))
           @recorded))

;; ---- pure walker: shared cross-host corpus --------------------------------

(deftest literal-operands-are-data-not-opaque
  (testing "rf2-3fc89f.12 — literal/config operands (`:=` value, `:enum`
            members, comparator bounds, `:re` pattern) and their enclosing
            structural forms are fully walkable, so schema-has-opaque-child?
            is FALSE (pre-fix: every one returned true)"
    (doseq [s rf.schemas.walker-literal-operand-fixtures/not-opaque-forms]
      (is (false? (rf.schemas/schema-has-opaque-child? s))
          (str "literal/walkable form must NOT be opaque: " (pr-str s))))))

(deftest nested-compiled-values-still-fail-closed
  (testing "rf2-3fc89f.12 — a genuinely opaque (compiled m/schema) value in a
            REAL child-schema position (root, :map slot, container element,
            :cat/:tuple element, :multi/:orn branch, :map-of key/value,
            combinator child) still fails closed (true)"
    (doseq [s rf.schemas.walker-literal-operand-fixtures/opaque-forms]
      (is (true? (rf.schemas/schema-has-opaque-child? s))
          (str "nested compiled value must fail closed: " (pr-str s))))))

(deftest unknown-operator-shapes-fail-closed
  (testing "rf2-3fc89f.12 — an unclassified operator shape cannot be proven
            walkable, so it fails closed (true)"
    (doseq [s rf.schemas.walker-literal-operand-fixtures/unknown-op-forms]
      (is (true? (rf.schemas/schema-has-opaque-child? s))
          (str "unknown operator shape must fail closed: " (pr-str s))))))

;; ---- registration warning (ACCEPTANCE #2) ---------------------------------

(deftest literal-vector-forms-do-not-warn-walker-opaque
  (testing "rf2-3fc89f.12 — registering ordinary literal-bearing vector-form
            schemas (`[:= 42]`, `[:enum ...]`, `[:cat [:= id] [:= v]]`) emits
            NO :rf.warning/schema-walker-opaque (pre-fix: each warned once)"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:answer]  [:= 42])
      (rf/reg-app-schema [:code]    [:enum 200 404])
      (rf/reg-app-schema [:label]   [:enum "a" "b"])
      (rf/reg-app-schema [:bound]   [:> 0])
      (rf/reg-app-schema [:pattern] [:re "a.*z"])
      (is (empty? (warnings-of recorded :rf.warning/schema-walker-opaque))
          "literal-bearing vector forms are fully walkable — no opaque nudge"))))

(deftest nested-compiled-child-still-warns-walker-opaque
  (testing "rf2-3fc89f.12 — the once-per-process opaque-walker warning is
            RETAINED for a vector-form schema that nests a real compiled child"
    (with-trace-recorder! [recorded]
      (rf/reg-app-schema [:token]
                         [:map [:secret (m/schema [:string {:sensitive? true}])]])
      (is (= 1 (count (warnings-of recorded :rf.warning/schema-walker-opaque)))
          "a nested compiled child still triggers the walker-opaque warning"))))

;; ---- validation egress: dev validate path (ACCEPTANCE #3) -----------------

(defn- event-failure-trace
  "Validate `event` against `schema` via the dev validate-event! path and
  return the single :rf.error/schema-validation-failure trace."
  [schema event]
  (with-trace-recorder! [traces]
    (rf.schemas/validate-event! :demo/e event {:schema schema})
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

(deftest non-sensitive-literal-event-failure-rides-verbatim
  (testing "rf2-3fc89f.12 — a failing NON-sensitive literal event schema
            (`[:cat [:= :demo/e] [:= 42]]`) preserves the failing value and is
            NOT stamped :sensitive? (pre-fix: value redacted + :sensitive? true
            because the walker false-flagged [:= 42] opaque)"
    (let [v (event-failure-trace [:cat [:= :demo/e] [:= 42]] [:demo/e 99])]
      (is (some? v) "a validation-failure trace fired")
      (is (not (contains? v :sensitive?))
          "no top-level :sensitive? stamp — nothing in the schema is sensitive")
      (is (= [:demo/e 99] (-> v :tags :value))
          ":value rides verbatim — the literal operand is data, not opaque")
      (is (= [:demo/e 99] (-> v :tags :received))
          ":received rides verbatim too")
      (is (not= :rf/redacted (-> v :tags :explain))
          ":explain is not spuriously redacted"))))

(deftest non-sensitive-enum-event-failure-rides-verbatim
  (testing "rf2-3fc89f.12 — a failing NON-sensitive :enum event schema
            preserves the value and is not stamped sensitive"
    (let [v (event-failure-trace
              [:cat [:= :demo/e] [:enum "a" "b"]] [:demo/e "z"])]
      (is (some? v))
      (is (not (contains? v :sensitive?)))
      (is (= [:demo/e "z"] (-> v :tags :value))))))

(deftest sensitive-slot-still-redacts-through-event-path
  (testing "rf2-3fc89f.12 — a genuinely :sensitive? payload slot is still
            redacted + stamped through the event path (fix preserves it)"
    (let [v (event-failure-trace
              [:cat [:= :demo/e] [:map [:pw {:sensitive? true} :string]]]
              [:demo/e {:pw 99}])]
      (is (some? v))
      (is (true? (:sensitive? v)) ":sensitive? stamped for the marked slot")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted"))))

;; ---- validation egress: always-on redact-validation-tags (ACCEPTANCE #3) --
;; The boundary / off-namespace emit sites reach the walker through the pure
;; `redact-validation-tags` seam. It is host-agnostic, so the CLJS half asserts
;; the identical cases (host parity for the always-on path).

(deftest redact-validation-tags-non-sensitive-literal-rides-verbatim
  (testing "rf2-3fc89f.12 — the always-on boundary redactor leaves a
            non-sensitive literal/enum schema's tags verbatim and adds no
            :sensitive? stamp (pre-fix: every slot scrubbed + stamped)"
    (let [tags {:value [:demo/e 99] :received [:demo/e 99] :explain :exp}]
      (doseq [schema [[:cat [:= :demo/e] [:= 42]]
                      [:cat [:= :demo/e] [:enum 1 2]]
                      [:= 42]
                      [:enum "a" "b"]]]
        (let [out (rf.schemas/redact-validation-tags schema tags)]
          (is (= tags out)
              (str "non-sensitive literal schema rides verbatim: " (pr-str schema)))
          (is (not (contains? out :sensitive?))
              (str "no :sensitive? stamp for: " (pr-str schema))))))))

(deftest redact-validation-tags-sensitive-and-opaque-still-redact
  (testing "rf2-3fc89f.12 — the boundary redactor still fails closed for a
            genuinely :sensitive? slot AND for a nested compiled/opaque child"
    (let [tags {:value [:demo/e 99] :received [:demo/e 99] :explain :exp}]
      (doseq [schema [[:cat [:= :demo/e] [:map [:pw {:sensitive? true} :string]]]
                      [:map [:tok (m/schema [:string {:sensitive? true}])]]
                      (m/schema [:string {:sensitive? true}])]]
        (let [out (rf.schemas/redact-validation-tags schema tags)]
          (is (true? (:sensitive? out))
              (str "sensitive/opaque schema is stamped: " (pr-str schema)))
          (is (= :rf/redacted (:value out))
              (str "sensitive/opaque schema value redacted: " (pr-str schema)))
          (is (not (str/includes? (pr-str out) "99"))
              (str "no raw value survives redaction for: " (pr-str schema))))))))
