(ns re-frame.story.ui.controls-nested-cljs-test
  "Tests for the Story Controls panel's nested Malli walker (rf2-agshe).

  The walker recurses into `:map` / `:vector` / `:tuple` / `:set`
  schemas — these tests pin the pure-data widget-spec emission, the
  default-element-value seeding for collection adds, and the path-
  aware cell-override writes against shell-state.

  Splits into two tiers:

  - **JVM + CLJS** (`state.cljc` is `.cljc`) — the path-aware
    `set-cell-override` fn (plus its `-scalar` wrapper). Pure data →
    data.
  - **CLJS-only** (`controls.cljs` is CLJS-only — it depends on
    Reagent / DOM) — `infer-widget` widget-spec emission on every
    collection operator, plus `resolve-argtypes` integration with the
    Story registrar.

  Runs on the JVM under `clojure -M:test` and on CLJS under shadow's
  `:node-test` / `:browser-test` targets (ns suffix `-cljs-test` is
  picked up by both `cljs-test$` and `-cljs-test$` regexes)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            #?(:cljs [re-frame.core :as rf])
            #?(:cljs [re-frame.story :as story])
            #?(:cljs [re-frame.story.ui.controls :as controls])
            [re-frame.story.ui.state :as state]))

;; ---- fixtures ------------------------------------------------------------

#?(:cljs
   (defn reset-fixture [test-fn]
     (story/clear-all!)
     (state/reset-shell-state!)
     (story/install-canonical-vocabulary!)
     (test-fn))
   :clj
   (defn reset-fixture [test-fn]
     (state/reset-shell-state!)
     (test-fn)))

(use-fixtures :each reset-fixture)

;; ---- CLJS: infer-widget on collection forms -----------------------------

#?(:cljs
   (deftest infer-widget-map-emits-group
     (testing ":map schema → :group widget with one entry per key"
       (let [w (controls/infer-widget
                 [:map [:label :string] [:disabled? :boolean]])]
         (is (= :group (:widget w)))
         (is (= :map   (:kind w)))
         (is (= 2      (count (:entries w))))
         (is (= [:label :disabled?] (mapv :key (:entries w))))
         (is (= :text    (-> w :entries (nth 0) :widget :widget)))
         (is (= :boolean (-> w :entries (nth 1) :widget :widget)))))))

#?(:cljs
   (deftest infer-widget-map-with-properties-skips-property-map
     (testing ":map with an optional properties map at index 1 is handled"
       (let [w (controls/infer-widget
                 [:map {:closed true} [:k :string]])]
         (is (= :group (:widget w)))
         (is (= [:k] (mapv :key (:entries w))))))))

#?(:cljs
   (deftest infer-widget-vector-emits-repeater
     (testing ":vector schema → :repeater widget with element schema"
       (let [w (controls/infer-widget [:vector :string])]
         (is (= :repeater (:widget w)))
         (is (= :vector   (:kind w)))
         (is (= :text     (-> w :element :widget)))))))

#?(:cljs
   (deftest infer-widget-set-emits-repeater
     (testing ":set schema → :repeater widget kind :set"
       (let [w (controls/infer-widget [:set :int])]
         (is (= :repeater (:widget w)))
         (is (= :set      (:kind w)))
         (is (= :number   (-> w :element :widget)))))))

#?(:cljs
   (deftest infer-widget-tuple-emits-positions
     (testing ":tuple schema → :tuple widget with one position per element"
       (let [w (controls/infer-widget [:tuple :string :int :boolean])]
         (is (= :tuple (:widget w)))
         (is (= 3 (count (:positions w))))
         (is (= [:text :number :boolean]
                (mapv :widget (:positions w))))))))

#?(:cljs
   (deftest infer-widget-nested-map-of-maps
     (testing ":map nested inside :map → depth-2 :group"
       (let [w (controls/infer-widget
                 [:map
                  [:outer
                   [:map
                    [:inner :string]
                    [:flag  :boolean]]]])]
         (is (= :group (:widget w)))
         (is (= :outer (-> w :entries first :key)))
         (let [inner-w (-> w :entries first :widget)]
           (is (= :group (:widget inner-w)))
           (is (= 2 (count (:entries inner-w))))
           (is (= [:inner :flag] (mapv :key (:entries inner-w))))
           (is (= :text    (-> inner-w :entries (nth 0) :widget :widget)))
           (is (= :boolean (-> inner-w :entries (nth 1) :widget :widget))))))))

#?(:cljs
   (deftest infer-widget-vector-of-maps
     (testing ":vector [:map ...] → :repeater whose element is a :group"
       (let [w (controls/infer-widget
                 [:vector [:map [:k :string] [:n :int]]])]
         (is (= :repeater (:widget w)))
         (is (= :group    (-> w :element :widget)))
         (is (= [:k :n]   (mapv :key (-> w :element :entries))))))))

#?(:cljs
   (deftest infer-widget-enum-still-scalar
     (testing "scalar :enum path survives the new vector dispatch"
       (let [w (controls/infer-widget [:enum :a :b :c])]
         (is (= :select (:widget w)))
         (is (= [:a :b :c] (:options w)))))))

#?(:cljs
   (deftest infer-widget-unknown-vector-falls-back-to-text
     (testing "an unrecognised vector schema-op degrades to :text"
       (is (= :text (:widget (controls/infer-widget [:fn 'pos?])))))))

;; ---- CLJS: resolve-argtypes from the component view's props schema -------
;;
;; rf2-din8u / rf2-vnedo — the auto-derivation schema is the COMPILED
;; variant-plan's `[:world :view-args-schema]`, resolved first-match
;; `[:rf/props :schema]` off the variant's `:component` VIEW metadata. These
;; exercise the PRODUCTION path: a REGISTERED view carries the props schema
;; under `:rf/props`; the variant points its `:component` at it; the plan
;; compiles via the DEFAULT side-table lookup (no `:lookup` / `:view-lookup`
;; arg). This is the CI-blind-spot gate — the pre-ruling resolver read
;; `:schema` off the bare variant body, which both missed `:rf/props` AND
;; never saw the compiled `:component`.

#?(:cljs
   (deftest resolve-argtypes-picks-up-component-props-schema
     (testing "the :component view's :rf/props schema drives the inference,
               resolved off the compiled plan"
       (rf/reg-view* :view.nest/widget
         {:rf/props [:map
                     [:title :string]
                     [:items [:vector :string]]
                     [:meta  [:map [:author :string] [:rating :int]]]]}
         (fn [_] [:div]))
       (story/reg-variant :story.nest/v
         {:component :view.nest/widget
          :args      {:title "Hello"
                      :items ["a" "b"]
                      :meta  {:author "ada" :rating 5}}
          :events    []})
       (let [t (controls/resolve-argtypes :story.nest/v)]
         ;; :title scalar
         (is (= :text (-> t :title :widget)))
         ;; :items vector → :repeater
         (is (= :repeater (-> t :items :widget)))
         (is (= :vector   (-> t :items :kind)))
         (is (= :text     (-> t :items :element :widget)))
         ;; :meta nested :map → :group with two entries
         (is (= :group (-> t :meta :widget)))
         (is (= [:author :rating] (mapv :key (-> t :meta :entries))))))))

#?(:cljs
   (deftest resolve-argtypes-author-argtypes-win
     (testing "an explicit :argtypes entry trumps the component-schema-
               derived widget"
       (rf/reg-view* :view.nest/labelled
         {:rf/props [:map [:label :string]]}
         (fn [_] [:div]))
       (story/reg-variant :story.nest/v2
         {:component :view.nest/labelled
          :argtypes  {:label {:widget :select :options ["a" "b"]}}
          :args      {:label "a"}
          :events    []})
       (let [t (controls/resolve-argtypes :story.nest/v2)]
         (is (= :select (-> t :label :widget)))
         (is (= ["a" "b"] (-> t :label :options)))))))

#?(:cljs
   (deftest resolve-argtypes-value-shape-fallback-recurses
     (testing "with no schema + no argtypes the value-shape walker recurses"
       (story/reg-variant :story.nest/v3
         {:args   {:nest {:k "v" :n 1}
                   :items ["x" "y"]}
          :events []})
       (let [t (controls/resolve-argtypes :story.nest/v3)]
         ;; :nest map value → :group
         (is (= :group (-> t :nest :widget)))
         (is (some? (some #(= :k (:key %)) (-> t :nest :entries))))
         ;; :items vector value → :repeater
         (is (= :repeater (-> t :items :widget)))))))

;; ---- rf2-wb4y3: 2-arity threads precomputed eff-args ---------------------

#?(:cljs
   (deftest resolve-argtypes-2-arity-threads-eff-args
     (testing "the 2-arity overload uses the supplied eff-args instead of
               re-resolving — passes a fabricated args map and asserts the
               fallback-inference reflects that shape (not the registered
               variant args)"
       (story/reg-variant :story.nest/v4
         {:args   {:registered "x"}
          :events []})
       ;; Supply a different eff-args map. The variant has no schema and
       ;; no argtypes, so the fallback inference walks the supplied map's
       ;; value shapes — :supplied should appear in the result, :registered
       ;; should NOT (proving the supplied map was used, not the variant's
       ;; own).
       (let [t (controls/resolve-argtypes :story.nest/v4
                                          {:supplied "y" :n 42})]
         (is (contains? t :supplied))
         (is (contains? t :n))
         (is (not (contains? t :registered)))
         (is (= :text   (-> t :supplied :widget)))
         (is (= :number (-> t :n :widget)))))))

#?(:cljs
   (deftest resolve-argtypes-1-arity-still-resolves-internally
     (testing "the 1-arity overload still works for non-render callers
               (tests, docs, etc.) — it calls args/resolve-args itself"
       (story/reg-variant :story.nest/v5
         {:args   {:title "hi"}
          :events []})
       (let [t (controls/resolve-argtypes :story.nest/v5)]
         (is (= :text (-> t :title :widget)))))))

;; ---- JVM + CLJS: path-aware set-cell-override ---------------------------

(deftest set-cell-override-scalar-wrapper
  (testing "set-cell-override-scalar wraps the arg-key into a singleton
            path and writes a top-level override"
    (let [s  state/default-shell-state
          s1 (state/set-cell-override-scalar s :story.a/x :label "hi")]
      (is (= "hi" (get-in s1 [:cell-overrides :story.a/x :label]))))))

(deftest set-cell-override-writes-nested
  (testing "a multi-element path writes at the nested location"
    (let [s  state/default-shell-state
          s1 (state/set-cell-override s :story.a/x [:meta :author] "ada")]
      (is (= "ada" (get-in s1 [:cell-overrides :story.a/x :meta :author]))))))

(deftest set-cell-override-deeply-nested
  (testing "path can address depth ≥ 3"
    (let [s  state/default-shell-state
          s1 (state/set-cell-override s :story.a/x
                                      [:outer :inner :leaf] 42)]
      (is (= 42 (get-in s1 [:cell-overrides :story.a/x
                            :outer :inner :leaf]))))))

(deftest set-cell-override-tolerates-integer-indices
  (testing "vector indices are valid path elements (per assoc-in)"
    (let [s  state/default-shell-state
          s1 (state/set-cell-override s :story.a/x [:items 0] "x")]
      (is (= "x" (get-in s1 [:cell-overrides :story.a/x :items 0]))))))

(deftest set-cell-override-singleton-path-equivalent-to-scalar-wrapper
  (testing "a 1-element path produces the same result as the scalar wrapper"
    (let [s  state/default-shell-state
          via-path   (state/set-cell-override        s :story.a/x [:label] "hi")
          via-scalar (state/set-cell-override-scalar s :story.a/x  :label  "hi")]
      (is (= via-path via-scalar))
      (is (= "hi" (get-in via-path [:cell-overrides :story.a/x :label]))))))

(deftest set-cell-override-empty-path-noop
  (testing "an empty path leaves the state unchanged"
    (let [s  state/default-shell-state
          s1 (state/set-cell-override s :story.a/x [] :ignored)]
      (is (= s s1)))))

(deftest set-cell-override-roundtrip-deep-then-shallow
  (testing "shallow override at the same arg-key shadows deep entries"
    (let [s0 state/default-shell-state
          s1 (state/set-cell-override        s0 :story.a/x [:meta :author] "ada")
          s2 (state/set-cell-override-scalar s1 :story.a/x  :meta {:author "bob"})]
      (is (= {:author "bob"}
             (get-in s2 [:cell-overrides :story.a/x :meta]))))))

;; ---- JVM + CLJS: per-arg clear-cell-override (rf2-ba86n.5) ---------------

(deftest clear-cell-override-drops-one-arg-keeps-others
  (testing "clear-cell-override reverts a single arg-key, leaving the
            variant's other overrides intact"
    (let [s0 state/default-shell-state
          s1 (-> s0
                 (state/set-cell-override-scalar :story.a/x :label "hi")
                 (state/set-cell-override-scalar :story.a/x :n     42))
          s2 (state/clear-cell-override s1 :story.a/x :label)]
      (is (nil? (get-in s2 [:cell-overrides :story.a/x :label])))
      (is (= 42 (get-in s2 [:cell-overrides :story.a/x :n]))))))

(deftest clear-cell-override-prunes-empty-variant-entry
  (testing "clearing the LAST override for a variant prunes the empty
            :cell-overrides entry — callers reading
            (seq (get-in state [:cell-overrides variant])) see 'no
            overrides', matching clear-cell-overrides"
    (let [s0 state/default-shell-state
          s1 (state/set-cell-override-scalar s0 :story.a/x :label "hi")
          s2 (state/clear-cell-override s1 :story.a/x :label)]
      (is (not (contains? (:cell-overrides s2) :story.a/x))))))

(deftest clear-cell-override-leaves-other-variants
  (testing "clearing one variant's arg does not touch another variant's
            overrides"
    (let [s0 state/default-shell-state
          s1 (-> s0
                 (state/set-cell-override-scalar :story.a/x :label "hi")
                 (state/set-cell-override-scalar :story.b/y :label "yo"))
          s2 (state/clear-cell-override s1 :story.a/x :label)]
      (is (not (contains? (:cell-overrides s2) :story.a/x)))
      (is (= "yo" (get-in s2 [:cell-overrides :story.b/y :label]))))))

(deftest clear-cell-override-drops-matching-repeater-row-ids
  (testing "clearing a collection arg drops only the repeater row-id
            bookkeeping anchored on that arg-key; sibling collection
            row-ids survive (rf2-c8kfy lockstep)"
    (let [s0 state/default-shell-state
          ;; Two collection args under one variant, each with row ids.
          s1 (-> s0
                 (state/set-cell-override-scalar :story.a/x :items ["a" "b"])
                 (state/ensure-repeater-row-ids :story.a/x [:items] 2)
                 (state/set-cell-override-scalar :story.a/x :tags  ["t"])
                 (state/ensure-repeater-row-ids :story.a/x [:tags] 1))
          s2 (state/clear-cell-override s1 :story.a/x :items)]
      ;; :items row-ids gone, :tags row-ids intact.
      (is (= [] (state/repeater-row-ids s2 :story.a/x [:items])))
      (is (= 1 (count (state/repeater-row-ids s2 :story.a/x [:tags])))))))

(deftest clear-cell-override-unknown-key-is-noop
  (testing "clearing an arg-key with no override leaves the state
            effectively unchanged (the variant entry is pruned only
            when it becomes empty)"
    (let [s0 state/default-shell-state
          s1 (state/set-cell-override-scalar s0 :story.a/x :label "hi")
          s2 (state/clear-cell-override s1 :story.a/x :missing)]
      (is (= "hi" (get-in s2 [:cell-overrides :story.a/x :label]))))))
