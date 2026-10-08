(ns re-frame.story.ui.controls-nested-cljs-test
  "Tests for the Story Controls panel's nested Malli walker and the
  path-aware cell-override writes behind it.

  - **JVM + CLJS** — `set-cell-override` / `clear-cell-override` in
    `state.cljc`.
  - **CLJS-only** — `infer-widget` widget-spec emission and
    `resolve-argtypes` against the Story registrar (`controls.cljs`
    depends on Reagent / DOM).

  Runs on the JVM under `clojure -M:test` and on CLJS under shadow's
  `:node-test` target (its `cljs-test$` regex picks up the `-cljs-test`
  suffix)."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            #?(:cljs [re-frame.core :as rf])
            [re-frame.story :as rf.story]
            [re-frame.story.args :as rf.story.args]
            #?(:cljs [re-frame.story.ui.controls :as rf.story.ui.controls])
            [re-frame.story.ui.state :as rf.story.ui.state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-fixture [test-fn]
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  #?(:cljs (rf.story/install-canonical-vocabulary!))
  (test-fn))

(use-fixtures :each reset-fixture)

;; ---- CLJS: infer-widget on collection forms -----------------------------

#?(:cljs
   (deftest infer-widget-collection-shapes
     (are [schema widget] (= widget (rf.story.ui.controls/infer-widget schema))
       ;; the optional properties map at index 1 is skipped, not read as an entry
       [:map {:closed true} [:label :string] [:disabled? :boolean]]
       {:widget :group :kind :map
        :entries [{:key :label :widget {:widget :text}}
                  {:key :disabled? :widget {:widget :boolean}}]}

       [:set :int]
       {:widget :repeater :kind :set :element {:widget :number}}

       [:tuple :string :int :boolean]
       {:widget :tuple :kind :tuple
        :positions [{:widget :text} {:widget :number} {:widget :boolean}]}

       [:enum :a :b :c]
       {:widget :select :options [:a :b :c]}

       ;; an unrecognised vector schema-op degrades to :text
       [:fn 'pos?]
       {:widget :text})))

;; ---- CLJS: resolve-argtypes from the component view's props schema -------
;;
;; The schema is the compiled plan's `[:world :view-args-schema]`, resolved
;; off the `:component` view's `:rf/props`. A resolver reading `:schema` off
;; the bare variant body would miss both.

#?(:cljs
   (deftest resolve-argtypes-picks-up-component-props-schema
     (rf/reg-view* :view.nest/widget
       {:rf/props [:map
                   [:title :string]
                   [:items [:vector :string]]
                   [:meta  [:map [:author :string] [:rating :int]]]]}
       (fn [_] [:div]))
     (rf.story/reg-variant :story.nest/v
       {:component :view.nest/widget
        :args      {:title "Hello"
                    :items ["a" "b"]
                    :meta  {:author "ada" :rating 5}}
        :setup    []})
     (is (= {:title {:widget :text}
             :items {:widget :repeater :kind :vector :element {:widget :text}}
             :meta  {:widget :group :kind :map
                     :entries [{:key :author :widget {:widget :text}}
                               {:key :rating :widget {:widget :number}}]}}
            (select-keys (rf.story.ui.controls/resolve-argtypes :story.nest/v)
                         [:title :items :meta])))))

#?(:cljs
   (deftest resolve-argtypes-author-argtypes-win
     (testing "an explicit :argtypes entry trumps the component-schema-
               derived widget"
       (rf/reg-view* :view.nest/labelled
         {:rf/props [:map [:label :string]]}
         (fn [_] [:div]))
       (rf.story/reg-variant :story.nest/v2
         {:component :view.nest/labelled
          :argtypes  {:label {:widget :select :options ["a" "b"]}}
          :args      {:label "a"}
          :setup    []})
       (is (= {:widget :select :options ["a" "b"]}
              (:label (rf.story.ui.controls/resolve-argtypes :story.nest/v2)))))))

#?(:cljs
   (deftest resolve-argtypes-value-shape-fallback-recurses
     (testing "with no schema + no argtypes the value-shape walker recurses"
       (rf.story/reg-variant :story.nest/v3
         {:args   {:nest {:k "v" :n 1}
                   :items ["x" "y"]}
          :setup []})
       (let [t (rf.story.ui.controls/resolve-argtypes :story.nest/v3)]
         (is (= :group (-> t :nest :widget)))
         (is (some? (some #(= :k (:key %)) (-> t :nest :entries))))
         (is (= :repeater (-> t :items :widget)))))))

;; ---- JVM + CLJS: path-aware set-cell-override ---------------------------

(deftest set-cell-override-tolerates-integer-indices
  (testing "with no base seed a missing collection vivifies as a real
            VECTOR, not the int-keyed map plain `assoc-in` would mint"
    (let [s1    (rf.story.ui.state/set-cell-override rf.story.ui.state/default-shell-state
                                                     :story.a/x [:items 0] "x")
          items (get-in s1 [:cell-overrides :story.a/x :items])]
      (is (vector? items))
      (is (= ["x"] items)))))

;; Editing one entry of a not-yet-overridden `:vector`/`:set` arg: args
;; resolution replaces vectors/sets whole but deep-merges maps, so a
;; collection level is seeded from `base` (its siblings survive) while a
;; map level stays minimal.

(deftest set-cell-override-deeply-nested-vivifies-map-levels-with-set-base
  (testing "the nested SET is seeded from base so its sibling entry survives
            and stays a set, while the enclosing MAP stays minimal — its
            unrelated :other key is not written into the override"
    (let [s1 (rf.story.ui.state/set-cell-override rf.story.ui.state/default-shell-state
                                                  :story.a/x [:group :tags 0] "x"
                                                  {:tags #{"a" "b"} :other 1})]
      (is (= {:tags #{"x" "b"}}
             (get-in s1 [:cell-overrides :story.a/x :group]))))))

(deftest set-cell-override-vector-entry-edit-keeps-base-siblings
  (let [s1    (rf.story.ui.state/set-cell-override rf.story.ui.state/default-shell-state
                                                   :story.a/x [:items 1] "X" ["a" "b" "c"])
        items (get-in s1 [:cell-overrides :story.a/x :items])]
    (is (vector? items))
    (is (= ["a" "X" "c"] items))))

(deftest set-cell-override-edits-an-established-set-override
  (testing "a set override stays a set across an indexed edit; the index
            addresses the sorted projection the repeater renders — sort-by
            str on #{x b} is [b x], so index 0 is \"b\""
    (let [s1 (-> rf.story.ui.state/default-shell-state
                 (rf.story.ui.state/set-cell-override-scalar :story.a/x :tags #{"x" "b"})
                 (rf.story.ui.state/set-cell-override :story.a/x [:tags 0] "y"))]
      (is (= #{"y" "x"} (get-in s1 [:cell-overrides :story.a/x :tags])))
      (rf.story/reg-variant :story.a/x {:args {:tags #{"a" "b"}} :setup []})
      (is (= #{"y" "x"} (:tags (rf.story.args/resolve-args
                                  :story.a/x {:cell-overrides (get-in s1 [:cell-overrides :story.a/x])})))
          "the effective args read the override set, which replaces the base set rather than merging into it"))))

;; ---- JVM + CLJS: per-arg clear-cell-override -----------------------------

(deftest clear-cell-override-drops-one-arg-keeps-others
  (testing "clearing one arg keeps the variant's other args and other
            variants; clearing its last arg prunes the variant's entry"
    (let [s1 (-> rf.story.ui.state/default-shell-state
                 (rf.story.ui.state/set-cell-override-scalar :story.a/x :label "hi")
                 (rf.story.ui.state/set-cell-override-scalar :story.a/x :n     42)
                 (rf.story.ui.state/set-cell-override-scalar :story.b/y :label "yo"))
          s2 (rf.story.ui.state/clear-cell-override s1 :story.a/x :label)
          s3 (rf.story.ui.state/clear-cell-override s2 :story.a/x :n)]
      (is (= {:story.a/x {:n 42} :story.b/y {:label "yo"}} (:cell-overrides s2)))
      (is (= {:story.b/y {:label "yo"}} (:cell-overrides s3))))))

(deftest clear-cell-override-drops-matching-repeater-row-ids
  (testing "clearing a collection arg drops only the repeater row ids
            anchored on that arg-key; a sibling collection's survive"
    (let [s1 (-> rf.story.ui.state/default-shell-state
                 (rf.story.ui.state/set-cell-override-scalar :story.a/x :items ["a" "b"])
                 (rf.story.ui.state/ensure-repeater-row-ids :story.a/x [:items] 2)
                 (rf.story.ui.state/set-cell-override-scalar :story.a/x :tags  ["t"])
                 (rf.story.ui.state/ensure-repeater-row-ids :story.a/x [:tags] 1))
          s2 (rf.story.ui.state/clear-cell-override s1 :story.a/x :items)]
      (is (= [] (rf.story.ui.state/repeater-row-ids s2 :story.a/x [:items])))
      (is (= 1 (count (rf.story.ui.state/repeater-row-ids s2 :story.a/x [:tags])))))))
