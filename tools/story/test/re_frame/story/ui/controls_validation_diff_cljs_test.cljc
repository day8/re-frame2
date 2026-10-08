(ns re-frame.story.ui.controls-validation-diff-cljs-test
  "Tests for the Story Controls panel's validation, diff and summary
  surfaces:

  - **inline schema validation** — `violations-by-key` indexing + the
    `validation-banner` / inline-error rendering in `args-editor`;
  - **diff-from-saved + per-arg reset** — the changed-dot / per-arg
    `reset` affordance;
  - **summarise-before-expand** — `summarize-value` + the disclosure
    header that collapses nested controls by default and lazily renders
    children only when expanded.

  The inline-error test branches on whether a live validator is
  registered (`validator-fns` is either nil or callable): with a validator
  present it asserts the banner + inline error; without, it asserts the
  documented soft-pass. The pure walk that PRODUCES violations is covered
  in `schema-validation-cljs-test`.

  The file is a `.cljc` for symmetry with sibling controls tests; every
  body is CLJS-only (`#?(:cljs ...)`). The ns suffix `-cljs-test` puts
  it in shadow's `:node-test` target (the `cljs-test$` regex)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            #?(:cljs [re-frame.core :as rf])
            #?(:cljs [re-frame.story :as rf.story])
            #?(:cljs [re-frame.story.ui.controls :as rf.story.ui.controls])
            #?(:cljs [re-frame.story.ui.schema-validation :as rf.story.ui.schema-validation])
            [re-frame.story.ui.state :as rf.story.ui.state]))

;; ---- fixtures ------------------------------------------------------------

#?(:cljs
   (defn reset-fixture [test-fn]
     (rf.story/clear-all!)
     (rf.story.ui.state/reset-shell-state!)
     (rf.story/install-canonical-vocabulary!)
     (test-fn))
   :clj
   (defn reset-fixture [test-fn]
     (rf.story.ui.state/reset-shell-state!)
     (test-fn)))

(use-fixtures :each reset-fixture)

;; ---- helpers -------------------------------------------------------------
;;
;; `for`-produced child seqs are NOT vectors, so a naive
;; `(tree-seq vector? rest tree)` never descends into them; the walk
;; recurses into vector AND seq children.

#?(:cljs
   (defn- attrs
     "The attribute map of a hiccup node, or nil."
     [node]
     (let [a (when (vector? node) (second node))]
       (when (map? a) a))))

#?(:cljs
   (defn- all-nodes
     "Depth-first vector of every hiccup VECTOR node in `tree`."
     [tree]
     (let [acc (atom [])]
       (letfn [(walk [node]
                 (cond
                   (vector? node) (do (swap! acc conj node)
                                      (doseq [c (rest node)] (walk c)))
                   (seq? node)    (doseq [c node] (walk c))))]
         (walk tree))
       @acc)))

#?(:cljs
   (defn- find-node
     "First hiccup node satisfying `pred`."
     [tree pred]
     (some (fn [n] (when (pred n) n)) (all-nodes tree))))

#?(:cljs
   (defn- node-with-attr
     "First hiccup node whose attribute `k` equals `v` (or, when `v` is
     omitted, whose attribute `k` is present)."
     ([tree k] (find-node tree (fn [n] (contains? (attrs n) k))))
     ([tree k v] (find-node tree (fn [n] (= v (get (attrs n) k)))))))

#?(:cljs
   (defn- button-with-action
     "First `:button` node whose `:data-controls-action` is `action`."
     [tree action]
     (find-node tree
                (fn [n] (and (= :button (first n))
                             (= action (:data-controls-action (attrs n))))))))

#?(:cljs
   (defn- render
     "Render the Form-2 args-editor for `variant-id` to hiccup."
     [variant-id]
     ((rf.story.ui.controls/args-editor variant-id) variant-id)))

;; ---- pure: violations-by-key --------------------------------------------

#?(:cljs
   (deftest violations-by-key-indexes-by-arg-key
     (testing "a {arg-key → violation} map; the schema-validation ::root
               sentinel (whole-args non-:map failure) remaps to
               :rf.story.controls/root so it never collides with a real arg"
       (let [name-v {:key :name :value 42 :schema :string :explain nil}
             root-v {:key :re-frame.story.ui.schema-validation/root
                     :value {:a 1} :schema :string :explain nil}]
         (is (= {:name name-v :rf.story.controls/root root-v}
                (rf.story.ui.controls/violations-by-key [name-v root-v])))))))

;; ---- pure: summarize-value ----------------------------------------------

#?(:cljs
   (deftest summarize-value-collections
     (testing "summarize-value gives a non-recursive one-line summary"
       (is (= "empty"   (rf.story.ui.controls/summarize-value nil)))
       (is (= "1 key"   (rf.story.ui.controls/summarize-value {:a 1})))
       (is (= "2 keys"  (rf.story.ui.controls/summarize-value {:a 1 :b 2})))
       (is (= "1 item"  (rf.story.ui.controls/summarize-value [:x])))
       (is (= "3 items" (rf.story.ui.controls/summarize-value [:x :y :z])))
       (is (= "2 items" (rf.story.ui.controls/summarize-value #{:x :y})))
       ;; a scalar landing under a collection widget pr-strs
       (is (= "\"x\""   (rf.story.ui.controls/summarize-value "x"))))))

;; ---- CLJS render: summarise-before-expand -------------------------------

#?(:cljs
   (deftest disclosure-toggle-expands-and-reveals-children
     (testing "a :group widget is collapsed by default with its nested rows
               NOT in the tree (summarise-before-expand, spec/019 §4); the
               toggle flips the component-local expand state and a re-render
               reveals them"
       (rf/reg-view* :view.ba86n/grp2
         {:rf/props [:map [:meta [:map [:author :string]]]]}
         (fn [_] [:div]))
       (rf.story/reg-variant :story.ba86n/grp2
         {:component :view.ba86n/grp2
          :args      {:meta {:author "ada"}}
          :setup    []})
       ;; ONE editor instance — the expand ratom lives on its closure.
       ;; Keys stringify WITH the leading colon — `(str :author)`.
       (let [editor (rf.story.ui.controls/args-editor :story.ba86n/grp2)
             tree-1 (editor :story.ba86n/grp2)
             toggle (button-with-action tree-1 "toggle-expand")]
         (is (= "false" (:data-controls-expanded (attrs toggle))))
         (is (nil? (node-with-attr tree-1 :data-controls-key ":author")))
         ((:on-click (attrs toggle)) nil)
         (is (some? (node-with-attr (editor :story.ba86n/grp2)
                                    :data-controls-key ":author")))))))

;; ---- CLJS render: inline schema error + banner --------------------------

#?(:cljs
   (deftest inline-error-and-banner-track-the-live-validator
     (testing "a committed value that violates the component view's props
               schema surfaces an inline error + a panel banner when a live
               validator is present, and soft-passes otherwise"
       (rf/reg-view* :view.ba86n/bad
         {:rf/props [:map [:age :int]]}
         (fn [_] [:div]))
       (rf.story/reg-variant :story.ba86n/bad
         {:component :view.ba86n/bad
          ;; :age is a string but the schema says :int.
          :args      {:age "not-a-number"}
          :setup    []})
       (let [tree    (render :story.ba86n/bad)
             banner  (node-with-attr tree :data-controls-validation "invalid")
             err-row (node-with-attr tree :data-controls-error)
             age-row (node-with-attr tree :data-controls-arg ":age")]
         (if (:validate (rf.story.ui.schema-validation/validator-fns))
           ;; Live validator present: banner + inline error + row marked invalid.
           (do
             (is (some? banner) "the panel banner renders")
             (is (pos? (js/parseInt
                         (:data-controls-violation-count (attrs banner)))))
             (is (some? err-row) "inline error renders for the violating arg")
             (is (= "true" (:data-controls-invalid (attrs age-row)))))
           ;; Soft-pass — no validator on the classpath (per Spec 010).
           (do
             (is (nil? err-row))
             (is (= "false" (:data-controls-invalid (attrs age-row))))))))))

;; ---- CLJS render: diff-from-saved dot + per-arg reset -------------------

#?(:cljs
   (deftest changed-arg-shows-dot-and-per-arg-reset
     (testing "an arg overridden to a value differing from saved shows the
               changed dot (data-controls-changed=\"true\") and a per-arg
               reset button; clicking reset clears only that arg's override"
       (rf.story/reg-variant :story.ba86n/diff
         {:args {:title "saved" :other "keep"} :setup []})
       (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override-scalar
                          :story.ba86n/diff :title "edited")
       (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override-scalar
                          :story.ba86n/diff :other "changed-too")
       (let [tree      (render :story.ba86n/diff)
             title-row (node-with-attr tree :data-controls-arg ":title")]
         (is (= "true" (:data-controls-changed (attrs title-row))))
         ((:on-click (attrs (button-with-action title-row "reset-arg"))) nil)
         (is (= {:other "changed-too"}
                (get-in (rf.story.ui.state/get-state)
                        [:cell-overrides :story.ba86n/diff])))))))

#?(:cljs
   (deftest unchanged-arg-has-no-changed-dot
     (testing "an arg at its saved value carries data-controls-changed=
               \"false\" — no diff dot"
       (rf.story/reg-variant :story.ba86n/same
         {:args {:title "saved"} :setup []})
       (let [tree (render :story.ba86n/same)
             row  (node-with-attr tree :data-controls-arg ":title")]
         (is (= "false" (:data-controls-changed (attrs row))))))))

#?(:cljs
   (deftest reset-overrides-button-present-when-overrides-exist
     (testing "the panel-level 'reset overrides' button appears only when
               at least one override exists for the focused variant"
       (rf.story/reg-variant :story.ba86n/resetall
         {:args {:a 1} :setup []})
       (is (nil? (button-with-action (render :story.ba86n/resetall) "reset-all")))
       (rf.story.ui.state/swap-state! rf.story.ui.state/set-cell-override-scalar
                          :story.ba86n/resetall :a 2)
       (is (some? (button-with-action (render :story.ba86n/resetall) "reset-all"))))))
