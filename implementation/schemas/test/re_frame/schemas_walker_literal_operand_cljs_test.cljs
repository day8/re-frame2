(ns re-frame.schemas-walker-literal-operand-cljs-test
  "CLJS host-parity half of the operator-aware schema-opacity walk
  (rf2-3fc89f.12). The classification is structural and identical on CLJ + CLJS,
  so both runtimes assert the SAME shared corpus
  (`re-frame.schemas.walker-literal-operand-fixtures`) against the SAME pure
  entry points. The JVM half (`re-frame.schemas-walker-literal-operand-test`)
  additionally covers the registration-warning and dev-validate egress paths;
  this half pins the pure walker (`schema-has-opaque-child?`) and the always-on
  boundary redactor (`redact-validation-tags`) — both host-agnostic pure
  functions — so a CLJS regression that diverged from the JVM classification is
  caught here.

  `malli.core` — the fixtures' compiled-schema dependency — reaches the
  `:node-test` classpath through the fixtures namespace's own require, and
  through the `re-frame.schemas` facade, which `:require`s the Malli
  adapter in its own ns-form."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as str]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.walker-literal-operand-fixtures :as fx]))

;; ---- pure walker: shared cross-host corpus (parity anchor) ----------------

(deftest cljs-literal-operands-are-data-not-opaque
  (testing "rf2-3fc89f.12 — literal/config operands and their structural forms
            are walkable, so schema-has-opaque-child? is FALSE on CLJS too
            (byte-identical classification with the JVM half)"
    (doseq [s fx/not-opaque-forms]
      (is (false? (schemas/schema-has-opaque-child? s))
          (str "literal/walkable form must NOT be opaque: " (pr-str s))))))

(deftest cljs-nested-compiled-values-still-fail-closed
  (testing "rf2-3fc89f.12 — a nested compiled m/schema value in a real
            child-schema position still fails closed (true) on CLJS"
    (doseq [s fx/opaque-forms]
      (is (true? (schemas/schema-has-opaque-child? s))
          (str "nested compiled value must fail closed: " (pr-str s))))))

(deftest cljs-unknown-operator-shapes-fail-closed
  (testing "rf2-3fc89f.12 — an unclassified operator shape fails closed (true)
            on CLJS"
    (doseq [s fx/unknown-op-forms]
      (is (true? (schemas/schema-has-opaque-child? s))
          (str "unknown operator shape must fail closed: " (pr-str s))))))

;; ---- always-on redact-validation-tags (host-agnostic egress parity) -------

(deftest cljs-redact-validation-tags-non-sensitive-literal-rides-verbatim
  (testing "rf2-3fc89f.12 — the always-on boundary redactor leaves a
            non-sensitive literal/enum schema's tags verbatim on CLJS"
    (let [tags {:value [:demo/e 99] :received [:demo/e 99] :explain :exp}]
      (doseq [schema [[:cat [:= :demo/e] [:= 42]]
                      [:cat [:= :demo/e] [:enum 1 2]]
                      [:= 42]
                      [:enum "a" "b"]]]
        (let [out (schemas/redact-validation-tags schema tags)]
          (is (= tags out)
              (str "non-sensitive literal schema rides verbatim: " (pr-str schema)))
          (is (not (contains? out :sensitive?))
              (str "no :sensitive? stamp for: " (pr-str schema))))))))

(deftest cljs-redact-validation-tags-sensitive-and-opaque-still-redact
  (testing "rf2-3fc89f.12 — the boundary redactor still fails closed for a
            :sensitive? slot AND for the nested-compiled corpus on CLJS"
    (let [tags    {:value [:demo/e 99] :received [:demo/e 99] :explain :exp}
          ;; an explicit vector-form sensitive slot + the shared opaque corpus
          ;; (every entry nests a compiled child, so the redactor fails closed)
          schemas [[:cat [:= :demo/e] [:map [:pw {:sensitive? true} :string]]]]]
      (doseq [schema (concat schemas fx/opaque-forms)]
        (let [out (schemas/redact-validation-tags schema tags)]
          (is (true? (:sensitive? out))
              (str "sensitive/opaque schema is stamped: " (pr-str schema)))
          (is (= :rf/redacted (:value out))
              (str "sensitive/opaque schema value redacted: " (pr-str schema)))
          (is (not (str/includes? (pr-str out) "99"))
              (str "no raw value survives redaction for: " (pr-str schema))))))))
