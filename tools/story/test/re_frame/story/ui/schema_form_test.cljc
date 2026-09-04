(ns re-frame.story.ui.schema-form-test
  "JVM-portable regression net for the schema-generated input-form pure
  projection (rf2-xon7j, spec/019 §5.1 + §4 control empty states).

  Covers the host-free surface — no host, no Reagent, no validator:

  - `scalar-widget`     — flat scalar → widget descriptor; non-flat → nil.
  - `field-shape`       — schema → renderable scalar / flat map / EDN
                          escape-hatch descriptor (the flat-shapes-first
                          boundary + the always-present escape hatch).
  - `widget-default` / `default-form-value` — initial form values.
  - `coerce-input`      — typed coercion of raw input back out.
  - `override-snippet`  — the copy-paste `:sub-overrides` scaffold (same
                          artifact kind, source never written directly)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [re-frame.story.ui.schema-form :as rf.story.ui.schema-form]))

;; ---------------------------------------------------------------------------
;; scalar-widget — the flat scalar vocabulary; non-flat → nil
;; ---------------------------------------------------------------------------

(deftest scalar-widget-maps-the-flat-shapes
  (testing "each flat scalar maps to its widget tag"
    (is (= {:widget :text}                (rf.story.ui.schema-form/scalar-widget :string)))
    (is (= {:widget :number :integer? true} (rf.story.ui.schema-form/scalar-widget :int)))
    (is (= {:widget :number}              (rf.story.ui.schema-form/scalar-widget :double)))
    (is (= {:widget :number}              (rf.story.ui.schema-form/scalar-widget :number)))
    (is (= {:widget :boolean}             (rf.story.ui.schema-form/scalar-widget :boolean)))
    (is (= {:widget :text :coerce :keyword} (rf.story.ui.schema-form/scalar-widget :keyword))))
  (testing "a keyword-enum becomes a select over its options"
    (is (= {:widget :select :options [:loading :ready :error]}
           (rf.story.ui.schema-form/scalar-widget [:enum :loading :ready :error])))))

(deftest scalar-widget-rejects-non-flat-shapes
  (testing "collections / nested maps / opaque predicates are NOT flat scalars"
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:map [:k :string]])))
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:vector :int])))
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:set :keyword])))
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:tuple :int :int])))
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:fn 'pos?]))))
  (testing "a mixed-type / non-keyword enum is NOT a flat select (ambiguous encoding)"
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:enum "a" "b"])))
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:enum 1 2 3])))
    (is (nil? (rf.story.ui.schema-form/scalar-widget [:enum]))))
  (testing "a registry-name keyword is opaque to a data walk"
    (is (nil? (rf.story.ui.schema-form/scalar-widget :my/user)))))

;; ---------------------------------------------------------------------------
;; field-shape — renderable scalar / flat map / EDN escape hatch
;; ---------------------------------------------------------------------------

(deftest field-shape-single-scalar-is-renderable
  (testing "a top-level flat scalar renders as one widget"
    (is (= {:renderable? true :kind :scalar :widget {:widget :text}}
           (rf.story.ui.schema-form/field-shape :string)))
    (is (= :select (get-in (rf.story.ui.schema-form/field-shape [:enum :a :b]) [:widget :widget])))))

(deftest field-shape-flat-map-is-renderable
  (testing "a flat map of flat scalars renders one field per declared key"
    (let [shape (rf.story.ui.schema-form/field-shape [:map
                                 [:state [:enum :loading :ready :error]]
                                 [:msg   :string]
                                 [:count :int]])]
      (is (true? (:renderable? shape)))
      (is (= :map (:kind shape)))
      (is (= [:state :msg :count] (mapv :key (:fields shape))))
      (is (= [:select :text :number] (mapv (comp :widget :widget) (:fields shape))))))
  (testing "an optional-marked flat entry is still renderable"
    (let [shape (rf.story.ui.schema-form/field-shape [:map [:msg {:optional true} :string]])]
      (is (true? (:renderable? shape)))
      (is (= [:msg] (mapv :key (:fields shape)))))))

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
  (testing "a collection / tuple / predicate schema is non-renderable"
    (is (false? (:renderable? (rf.story.ui.schema-form/field-shape [:vector :int]))))
    (is (false? (:renderable? (rf.story.ui.schema-form/field-shape [:tuple :int :int]))))
    (is (false? (:renderable? (rf.story.ui.schema-form/field-shape [:fn 'map?])))))
  (testing "a registry ref is non-renderable but names itself"
    (let [shape (rf.story.ui.schema-form/field-shape :my/user)]
      (is (false? (:renderable? shape)))
      (is (str/includes? (:reason shape) ":my/user"))))
  (testing "an empty map schema is non-renderable"
    (is (false? (:renderable? (rf.story.ui.schema-form/field-shape [:map]))))))

;; ---------------------------------------------------------------------------
;; defaults + coercion
;; ---------------------------------------------------------------------------

(deftest widget-defaults-are-sensible-empties
  (is (= ""    (rf.story.ui.schema-form/widget-default {:widget :text})))
  (is (nil?    (rf.story.ui.schema-form/widget-default {:widget :number})))
  (is (false?  (rf.story.ui.schema-form/widget-default {:widget :boolean})))
  (is (= :a    (rf.story.ui.schema-form/widget-default {:widget :select :options [:a :b]}))))

(deftest default-form-value-builds-the-initial-form
  (testing "a scalar shape defaults to its widget default"
    (is (= "" (rf.story.ui.schema-form/default-form-value {:kind :scalar :widget {:widget :text}}))))
  (testing "a map shape defaults to a per-key default map"
    (is (= {:state :loading :msg "" :count nil}
           (rf.story.ui.schema-form/default-form-value
             {:kind   :map
              :fields [{:key :state :widget {:widget :select :options [:loading :ready]}}
                       {:key :msg   :widget {:widget :text}}
                       {:key :count :widget {:widget :number :integer? true}}]}))))
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
  (testing "boolean + select"
    (is (true?  (rf.story.ui.schema-form/coerce-input {:widget :boolean} true)))
    (is (false? (rf.story.ui.schema-form/coerce-input {:widget :boolean} nil)))
    (is (= :ready (rf.story.ui.schema-form/coerce-input {:widget :select :options [:ready :error]} :ready)))))

;; ---------------------------------------------------------------------------
;; read-edn-value — the raw-EDN escape hatch
;; ---------------------------------------------------------------------------

(deftest read-edn-value-parses-the-escape-hatch
  (testing "a valid EDN form reads ok"
    (is (= [true :error]            (rf.story.ui.schema-form/read-edn-value ":error")))
    (is (= [true {:state :ready}]   (rf.story.ui.schema-form/read-edn-value "{:state :ready}")))
    (is (= [true [1 2 3]]           (rf.story.ui.schema-form/read-edn-value "[1 2 3]"))))
  (testing "blank / nil read as not-ok (fill this value), never committing nil"
    (is (= [false nil] (rf.story.ui.schema-form/read-edn-value "")))
    (is (= [false nil] (rf.story.ui.schema-form/read-edn-value "   ")))
    (is (= [false nil] (rf.story.ui.schema-form/read-edn-value nil))))
  (testing "a malformed form reports not-ok with a message, never throws"
    (let [[ok? msg] (rf.story.ui.schema-form/read-edn-value "{:a ")]
      (is (false? ok?))
      (is (string? msg)))))

;; ---------------------------------------------------------------------------
;; override-snippet — same artifact kind, source never written directly
;; ---------------------------------------------------------------------------

(deftest override-snippet-pins-a-value-keeping-the-artifact-a-variant
  (testing "the scaffold is a reg-variant :extends-ing the source with one pinned entry"
    (let [snip (rf.story.ui.schema-form/override-snippet :story.login/explore [:login/state] :error)]
      (is (str/includes? snip "story/reg-variant")
          "stays a reg-variant — artifact kind unchanged")
      (is (str/includes? snip ":extends :story.login/explore")
          "extends the source so component/decorators/args carry forward")
      (is (str/includes? snip ":sub-overrides"))
      (is (str/includes? snip "[:login/state] :error")
          "the pinned query vector → value")
      (is (str/includes? snip "never proof")
          "the honest lowest-fidelity reading rides on the scaffold")))
  (testing "a flat-map value prints cleanly"
    (let [snip (rf.story.ui.schema-form/override-snippet :story.s/v [:q] {:state :ready :msg "ok"})]
      (is (str/includes? snip ":state :ready"))
      (is (str/includes? snip ":msg \"ok\""))))
  (testing "the from-edn? flag notes the raw-EDN path"
    (let [snip (rf.story.ui.schema-form/override-snippet :story.s/v [:q] {:x 1} true)]
      (is (str/includes? snip "raw EDN")))))
