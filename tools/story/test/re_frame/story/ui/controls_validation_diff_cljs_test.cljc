(ns re-frame.story.ui.controls-validation-diff-cljs-test
  "Tests for the Story Controls panel's rf2-ba86n.5 surfaces:

  - **inline schema validation** — `violations-by-key` indexing + the
    `validation-banner` / inline-error rendering in `args-editor`;
  - **diff-from-saved + per-arg reset** — `arg-changed?` + the
    changed-dot / per-arg `reset` affordance;
  - **summarise-before-expand** — `summarize-value` + the disclosure
    header that collapses nested controls by default and lazily renders
    children only when expanded.

  ## Tiers

  - **CLJS-only pure** — `violations-by-key`, `arg-changed?`,
    `summarize-value` live in the CLJS-only `controls` ns. All pure
    data → data.
  - **CLJS-only render** — `args-editor` (Form-2) returns hiccup; the
    collection / row / banner pieces are plain hiccup-returning fns so
    the whole tree is inline and walkable.

  The inline-error test branches on the live validator's observed
  behaviour (`validator-fns` is 'either nil or callable' per the schema-
  validation panel's own test): with a validator present it asserts the
  banner + inline error; without, it asserts the documented soft-pass.
  The pure walk that PRODUCES violations is covered in
  `schema-validation-cljs-test`.

  The file is a `.cljc` for symmetry with sibling controls tests; every
  body is CLJS-only (`#?(:cljs ...)`). The ns suffix `-cljs-test` is
  picked up by both `cljs-test$` and `-cljs-test$` regexes."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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

;; ---- helpers -------------------------------------------------------------
;;
;; Hiccup walking: `for`-produced child seqs are NOT vectors, so a naive
;; `(tree-seq vector? rest tree)` never descends into them. We walk
;; explicitly — recursing into vector children AND seq children — mirroring
;; the sibling `controls-scalar-widgets-cljs-test/walk-find` idiom.

#?(:cljs
   (defn- attrs
     "Return the attribute map of a hiccup node (second element when it's
     a map, else nil)."
     [node]
     (let [a (when (vector? node) (second node))]
       (when (map? a) a))))

#?(:cljs
   (defn- all-nodes
     "Depth-first vector of every hiccup VECTOR node in `tree` (descending
     through both vector children and `for`-produced seq children)."
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
     "First hiccup node satisfying `pred` (pred receives the node)."
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
     ((controls/args-editor variant-id) variant-id)))

;; ---- pure: violations-by-key --------------------------------------------

#?(:cljs
   (deftest violations-by-key-indexes-by-arg-key
     (testing "violations-by-key turns the args-violations vector into a
               {arg-key → violation} map"
       (let [viols [{:key :name :value 42 :schema :string :explain nil}
                    {:key :age  :value "x" :schema :int    :explain nil}]
             by-k  (controls/violations-by-key viols)]
         (is (= 2 (count by-k)))
         (is (= 42 (get-in by-k [:name :value])))
         (is (= "x" (get-in by-k [:age :value])))))))

#?(:cljs
   (deftest violations-by-key-remaps-root-key
     (testing "the schema-validation ::root sentinel (whole-args non-:map
               failure) remaps to :rf.story.controls/root so it never
               collides with a real arg-key but still counts"
       (let [viols [{:key :re-frame.story.ui.schema-validation/root
                     :value {:a 1} :schema :string :explain nil}]
             by-k  (controls/violations-by-key viols)]
         (is (contains? by-k :rf.story.controls/root))
         (is (= {:a 1} (get-in by-k [:rf.story.controls/root :value])))))))

#?(:cljs
   (deftest violations-by-key-empty
     (testing "an empty violations vector indexes to an empty map"
       (is (= {} (controls/violations-by-key []))))))

;; ---- pure: arg-changed? -------------------------------------------------

#?(:cljs
   (deftest arg-changed?-true-on-diff
     (testing "an arg whose effective value differs from its saved value
               is flagged changed"
       (is (controls/arg-changed? {:a 2} {:a 1} :a)))))

#?(:cljs
   (deftest arg-changed?-false-on-equal
     (testing "an arg whose effective value equals its saved value is not
               flagged changed — even with an override present (same-value
               override)"
       (is (not (controls/arg-changed? {:a 1} {:a 1} :a))))))

#?(:cljs
   (deftest arg-changed?-handles-missing-keys
     (testing "a key absent from saved but present in effective is changed;
               a key absent from both is not"
       (is (controls/arg-changed? {:a 1} {} :a))
       (is (not (controls/arg-changed? {} {} :a))))))

;; ---- pure: summarize-value ----------------------------------------------

#?(:cljs
   (deftest summarize-value-collections
     (testing "summarize-value gives a non-recursive one-line summary"
       (is (= "empty"   (controls/summarize-value nil)))
       (is (= "1 key"   (controls/summarize-value {:a 1})))
       (is (= "2 keys"  (controls/summarize-value {:a 1 :b 2})))
       (is (= "1 item"  (controls/summarize-value [:x])))
       (is (= "3 items" (controls/summarize-value [:x :y :z])))
       (is (= "2 items" (controls/summarize-value #{:x :y})))
       ;; a scalar landing under a collection widget pr-strs
       (is (= "\"x\""   (controls/summarize-value "x"))))))

#?(:cljs
   (deftest summarize-value-does-not-recurse
     (testing "deep contents are NOT walked — only the top-level count
               appears (the point of summarising before expanding)"
       (is (= "1 key"
              (controls/summarize-value
                {:deep {:and {:nested {:tree :here}}}}))))))

;; ---- CLJS render: summarise-before-expand -------------------------------

#?(:cljs
   (deftest group-collapsed-by-default-renders-summary-not-children
     (testing "a :group widget is collapsed by default — the disclosure
               header is present + collapsed, and the nested child rows
               are NOT in the tree (summarise-before-expand, spec/019 §4)"
       (story/reg-variant :story.ba86n/grp
         {:schema [:map [:meta [:map [:author :string] [:rating :int]]]]
          :args   {:meta {:author "ada" :rating 5}}
          :events []})
       (let [tree   (render :story.ba86n/grp)
             toggle (button-with-action tree "toggle-expand")]
         (is (some? toggle) "disclosure toggle present")
         (is (= "false" (:data-controls-expanded (attrs toggle)))
             "collapsed by default")
         ;; The nested :author key row is NOT rendered while collapsed.
         ;; (Keys stringify WITH the leading colon — `(str :author)`.)
         (is (nil? (node-with-attr tree :data-controls-key ":author")))))))

#?(:cljs
   (deftest disclosure-toggle-expands-and-reveals-children
     (testing "clicking the disclosure toggle flips the (component-local)
               expand state and a re-render reveals the nested child rows"
       (story/reg-variant :story.ba86n/grp2
         {:schema [:map [:meta [:map [:author :string]]]]
          :args   {:meta {:author "ada"}}
          :events []})
       ;; ONE editor instance — the expand ratom lives on its closure, so
       ;; the toggle + re-render must go through the same instance.
       (let [editor (controls/args-editor :story.ba86n/grp2)
             tree-1 (editor :story.ba86n/grp2)
             toggle (button-with-action tree-1 "toggle-expand")
             on-click (:on-click (attrs toggle))]
         (is (fn? on-click))
         ;; Collapsed first: no nested :author row.
         (is (nil? (node-with-attr tree-1 :data-controls-key ":author")))
         (on-click nil)
         (let [tree-2 (editor :story.ba86n/grp2)]
           ;; Expanded now: the nested :author row IS present.
           (is (some? (node-with-attr tree-2 :data-controls-key ":author"))))))))

;; ---- CLJS render: inline schema error + banner --------------------------

#?(:cljs
   (deftest args-editor-marks-rows-with-validity-attribute
     (testing "every arg row carries a data-controls-invalid attribute so
               downstream tooling / the browser smoke can detect blocked
               renders"
       (story/reg-variant :story.ba86n/val
         {:args {:title "hi"} :events []})
       (let [tree (render :story.ba86n/val)
             row  (node-with-attr tree :data-controls-arg ":title")]
         (is (some? row))
         (is (contains? (attrs row) :data-controls-invalid))))))

#?(:cljs
   (deftest inline-error-and-banner-track-the-live-validator
     (testing "a committed value that violates the variant's :schema
               surfaces an inline error + a panel banner when a live
               validator is present, and soft-passes otherwise"
       (story/reg-variant :story.ba86n/bad
         {:schema [:map [:age :int]]
          ;; :age is a string but the schema says :int.
          :args   {:age "not-a-number"}
          :events []})
       (let [tree    (render :story.ba86n/bad)
             banner  (node-with-attr tree :data-controls-validation "invalid")
             err-row (node-with-attr tree :data-controls-error)
             age-row (node-with-attr tree :data-controls-arg ":age")]
         (if banner
           ;; Live validator flagged the violation — the BEFORE-render
           ;; claims contract: banner + inline error + row marked invalid.
           (do
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
       (story/reg-variant :story.ba86n/diff
         {:args {:title "saved" :other "keep"} :events []})
       (state/swap-state! state/set-cell-override-scalar
                          :story.ba86n/diff :title "edited")
       (state/swap-state! state/set-cell-override-scalar
                          :story.ba86n/diff :other "changed-too")
       (let [tree      (render :story.ba86n/diff)
             title-row (node-with-attr tree :data-controls-arg ":title")]
         (is (= "true" (:data-controls-changed (attrs title-row))))
         (let [reset-btn (button-with-action title-row "reset-arg")]
           (is (some? reset-btn))
           ((:on-click (attrs reset-btn)) nil)
           ;; :title override gone, :other override survives.
           (is (nil? (get-in (state/get-state)
                             [:cell-overrides :story.ba86n/diff :title])))
           (is (= "changed-too"
                  (get-in (state/get-state)
                          [:cell-overrides :story.ba86n/diff :other]))))))))

#?(:cljs
   (deftest unchanged-arg-has-no-changed-dot
     (testing "an arg at its saved value carries data-controls-changed=
               \"false\" — no diff dot"
       (story/reg-variant :story.ba86n/same
         {:args {:title "saved"} :events []})
       (let [tree (render :story.ba86n/same)
             row  (node-with-attr tree :data-controls-arg ":title")]
         (is (= "false" (:data-controls-changed (attrs row))))))))

#?(:cljs
   (deftest reset-overrides-button-present-when-overrides-exist
     (testing "the panel-level 'reset overrides' button appears only when
               at least one override exists for the focused variant"
       (story/reg-variant :story.ba86n/resetall
         {:args {:a 1} :events []})
       ;; No overrides yet → no reset-all button.
       (is (nil? (button-with-action (render :story.ba86n/resetall) "reset-all")))
       ;; Add an override → reset-all appears.
       (state/swap-state! state/set-cell-override-scalar
                          :story.ba86n/resetall :a 2)
       (is (some? (button-with-action (render :story.ba86n/resetall) "reset-all"))))))
