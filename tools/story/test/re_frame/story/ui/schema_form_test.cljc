(ns re-frame.story.ui.schema-form-test
  "JVM-portable regression net for the schema-generated input-form pure
  projection (spec/019 §5.1 + §4 control empty states).

  Covers the host-free surface — no host, no Reagent, no validator:

  - `scalar-widget`     — flat scalar → widget descriptor; non-flat → nil.
  - `field-shape`       — schema → renderable scalar / flat map / EDN
                          escape-hatch descriptor (the flat-shapes-first
                          boundary + the always-present escape hatch).
  - `default-form-value` — initial form values.
  - `coerce-input`      — typed coercion of raw input back out.
  - `override-snippet`  — the copy-paste `:sub-overrides` scaffold. That it
                          pastes and registers is pinned in
                          `dialog-output-paste-test`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.ui.schema-form :as rf.story.ui.schema-form]))

;; ---------------------------------------------------------------------------
;; scalar-widget — the flat scalar vocabulary; non-flat → nil
;; ---------------------------------------------------------------------------

;; `:string`, `:int` and a keyword `[:enum …]` are pinned through
;; `field-shape-flat-map-is-renderable`.
(deftest scalar-widget-maps-the-flat-shapes
  (is (= {:widget :number}                (rf.story.ui.schema-form/scalar-widget :double)))
  (is (= {:widget :number}                (rf.story.ui.schema-form/scalar-widget :number)))
  (is (= {:widget :boolean}               (rf.story.ui.schema-form/scalar-widget :boolean)))
  (is (= {:widget :text :coerce :keyword} (rf.story.ui.schema-form/scalar-widget :keyword))))

(deftest scalar-widget-rejects-non-flat-shapes
  (testing "a non-keyword enum is NOT a flat select (ambiguous encoding)"
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:enum "a" "b"])))))

;; ---------------------------------------------------------------------------
;; field-shape — renderable scalar / flat map / EDN escape hatch
;; ---------------------------------------------------------------------------

(deftest field-shape-single-scalar-is-renderable
  (is (= {:renderable? true :kind :scalar :widget {:widget :text}}
         (rf.story.ui.schema-form/field-shape :string))))

(deftest field-shape-flat-map-is-renderable
  (testing "a flat map of flat scalars renders one field per declared key"
    (is (= {:renderable? true
            :kind        :map
            :fields      [{:key :state :widget {:widget :select :options [:loading :ready :error]}}
                          {:key :msg   :widget {:widget :text}}
                          {:key :count :widget {:widget :number :integer? true}}]}
           (rf.story.ui.schema-form/field-shape [:map
                                                 [:state [:enum :loading :ready :error]]
                                                 [:msg   :string]
                                                 [:count :int]]))))
  (testing "an optional-marked flat entry is still renderable"
    (is (= {:renderable? true :kind :map :fields [{:key :msg :widget {:widget :text}}]}
           (rf.story.ui.schema-form/field-shape [:map [:msg {:optional true} :string]])))))

(deftest field-shape-non-flat-falls-to-edn-escape-hatch
  (testing "no declared schema → EDN escape hatch with an honest reason"
    (let [shape (rf.story.ui.schema-form/field-shape nil)]
      (is (false? (:renderable? shape)))
      (is (= :edn (:kind shape)))
      (is (str/includes? (:reason shape) "no output schema"))))
  (testing "a map with a NESTED field is non-renderable (flat-shapes-first)"
    (let [shape (rf.story.ui.schema-form/field-shape [:map
                                 [:ok :boolean]
                                 [:nested [:map [:deep :string]]]])]
      (is (false? (:renderable? shape)))
      (is (= :edn (:kind shape)))
      (is (str/includes? (:reason shape) ":nested"))))
  (testing "a collection schema is non-renderable"
    (is (false? (:renderable? (rf.story.ui.schema-form/field-shape [:vector :int])))))
  (testing "a registry ref is non-renderable but names itself"
    (let [shape (rf.story.ui.schema-form/field-shape :my/user)]
      (is (false? (:renderable? shape)))
      (is (str/includes? (:reason shape) ":my/user"))))
  (testing "an empty map schema is non-renderable"
    (is (false? (:renderable? (rf.story.ui.schema-form/field-shape [:map]))))))

;; ---------------------------------------------------------------------------
;; defaults + coercion
;; ---------------------------------------------------------------------------

(deftest default-form-value-builds-the-initial-form
  (testing "a scalar shape defaults to its widget default"
    (is (= "" (rf.story.ui.schema-form/default-form-value {:kind :scalar :widget {:widget :text}}))))
  (testing "a map shape defaults to a per-key default map — a select's first
            option, an empty text, a blank number, an unchecked box"
    (is (= {:state :loading :msg "" :count nil :ok false}
           (rf.story.ui.schema-form/default-form-value
             {:kind   :map
              :fields [{:key :state :widget {:widget :select :options [:loading :ready]}}
                       {:key :msg   :widget {:widget :text}}
                       {:key :count :widget {:widget :number :integer? true}}
                       {:key :ok    :widget {:widget :boolean}}]}))))
  (testing "an EDN shape has no generated default"
    (is (nil? (rf.story.ui.schema-form/default-form-value {:kind :edn})))))

(deftest coerce-input-types-the-raw-value
  (testing "text passes through; keyword-coercion strips a leading colon"
    (is (= "hi"      (rf.story.ui.schema-form/coerce-input {:widget :text} "hi")))
    (is (= :loading  (rf.story.ui.schema-form/coerce-input {:widget :text :coerce :keyword} "loading")))
    (is (= :loading  (rf.story.ui.schema-form/coerce-input {:widget :text :coerce :keyword} ":loading")))
    (is (nil?        (rf.story.ui.schema-form/coerce-input {:widget :text :coerce :keyword} ""))))
  (testing "number coerces to int / double; blank → nil; a typo stays raw"
    (is (= 42        (rf.story.ui.schema-form/coerce-input {:widget :number :integer? true} "42")))
    (is (= 3.5       (rf.story.ui.schema-form/coerce-input {:widget :number} "3.5")))
    (is (nil?        (rf.story.ui.schema-form/coerce-input {:widget :number} "")))
    (is (= "x"       (rf.story.ui.schema-form/coerce-input {:widget :number} "x"))))
  (testing "boolean coerces a missing value to false"
    (is (false? (rf.story.ui.schema-form/coerce-input {:widget :boolean} nil)))))

;; ---------------------------------------------------------------------------
;; read-edn-value — the raw-EDN escape hatch
;; ---------------------------------------------------------------------------

(deftest read-edn-value-parses-the-escape-hatch
  (testing "a valid EDN form reads ok"
    (is (= [true {:state :ready}]   (rf.story.ui.schema-form/read-edn-value "{:state :ready}"))))
  (testing "blank / nil read as not-ok (fill this value), never committing nil"
    (is (= [false nil] (rf.story.ui.schema-form/read-edn-value "   ")))
    (is (= [false nil] (rf.story.ui.schema-form/read-edn-value nil))))
  (testing "a malformed form reports not-ok with a message, never throws"
    (let [[ok? msg] (rf.story.ui.schema-form/read-edn-value "{:a ")]
      (is (false? ok?))
      (is (string? msg)))))

;; ---------------------------------------------------------------------------
;; override-snippet
;; ---------------------------------------------------------------------------

(deftest override-snippet-pins-a-value-keeping-the-artifact-a-variant
  (testing "the scaffold carries the honest lowest-fidelity reading"
    (is (str/includes? (rf.story.ui.schema-form/override-snippet
                         :story.login/explore [:login/state] :error)
                       "never proof")))
  (testing "a flat-map value prints cleanly"
    (let [snip (rf.story.ui.schema-form/override-snippet :story.s/v [:q] {:state :ready :msg "ok"})]
      (is (str/includes? snip ":state :ready"))
      (is (str/includes? snip ":msg \"ok\"")))))
