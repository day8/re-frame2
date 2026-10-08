(ns re-frame.ssr.hash-parity-fixtures
  "Shared fixtures for the JVM and CLJS render-tree-hash parity tests. Spec 011
  §Hydration-mismatch detection promises a byte-identical hash on both hosts:
  the server hashes the render tree and the client re-hashes it, so any drift
  is a spurious `:rf.ssr/hydration-mismatch`. Both hosts pin the same literal
  for each input; one row per path through the canonicaliser and the UTF-8 byte
  stream.")

(defn fn-head-component
  "A raw-fn hiccup head, the deref'd `defn` value Reagent / UIx SSR writes."
  [props]
  [:span (:label props)])

(def all-fixtures
  [{:label "scalar-int" :input 42 :expected "87e38583"}
   ;; false is a value, not pruned the way nil is
   {:label "scalar-false" :input false :expected "0b069958"}
   {:label "minimal-hiccup" :input [:p "hi"] :expected "3d16d3e4"}
   {:label "div-class" :input [:div {:class "x"} [:p "hi"]] :expected "9d7457ef"}
   ;; keys emit sorted by their canonical form, not in insertion order
   {:label    "namespaced-kw-attr"
    :input    [:input {:type "text"
                       :data-rf2-source-coord "rf.app:view:42:7"
                       :on-change :rf.parity-test/handler}]
    :expected "a97ed733"}
   {:label    "nested-deep"
    :input    [:section {:class "wrap"}
               [:header {:role "banner"}
                [:h1 "Title"]
                [:nav
                 [:a {:href "/" :class "active"} "Home"]
                 [:a {:href "/about"} "About"]]]
               [:main
                [:article {:class "post"}
                 [:h2 "Subtitle"]
                 [:p "Body 1"]
                 [:p "Body 2"]]]
               [:footer [:p "(C) 2026"]]]
    :expected "02eed3c5"}
   ;; UTF-8 bytes, not UTF-16 code units: 2-byte, a 4-byte surrogate pair, 3-byte
   {:label "unicode-cafe" :input [:p "café"] :expected "2379e33d"}
   {:label "unicode-emoji" :input [:span "👋 world"] :expected "b0c50846"}
   {:label "unicode-mixed" :input [:div {:title "Привет"} "你好" :hello] :expected "66e28ab6"}
   {:label "set-with-strings" :input #{"b" "a" "c"} :expected "978929dc"}
   {:label "list-children" :input [:ul (list :li1 :li2 :li3)] :expected "81d3674a"}
   ;; a raw fn head collapses to #fn[] on both hosts; its props still hash
   {:label "fn-head-child" :input [:div [fn-head-component {:label "hi"}]] :expected "c105e684"}
   ;; the JVM's 0.0 / 1.0 print as CLJS's 0 / 1
   {:label "whole-double" :input [:progress {:value 0.0 :max 1.0}] :expected "5ef66c2e"}])

(def nil-prune-pairs
  "Nil attribute values and nil children are pruned: both inputs hash to the
  no-nil literal."
  [{:label             "nil-prune-attr"
    :input-with-nil    [:div {:class nil :id "x"} [:p "hi"]]
    :input-without-nil [:div {:id "x"} [:p "hi"]]
    :expected          "11d3589e"}
   {:label             "nil-prune-child"
    :input-with-nil    [:div [:p "a"] nil [:p "b"]]
    :input-without-nil [:div [:p "a"] [:p "b"]]
    :expected          "e4cf2667"}])

(def equality-pairs
  "Inputs that must hash alike. A keyword `:a` and a string `\":a\"` both `str`
  to `\":a\"`, so a sort on `str` would fall back to insertion order; sorting
  on the canonical form of the key is total."
  [{:label   "str-colliding-keys-order-independent"
    :input-a (array-map :a 1 ":a" 2)
    :input-b (array-map ":a" 2 :a 1)}])
