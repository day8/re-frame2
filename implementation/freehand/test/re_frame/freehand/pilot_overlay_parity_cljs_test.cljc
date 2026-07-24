(ns re-frame.freehand.pilot-overlay-parity-cljs-test
  "CASE B, promotion parity — and the exact line the pilot's composition
  could not be promoted past.

  Two things are proven here, and the second is the more useful one.

  **The overlay MARKUP promotes cleanly.** The anchor, the promoted panel,
  the desired-state property and both event sites render to the same
  structural node interpreted and compiled. So the top-layer half of the
  pilot really is compiler-recognised common semantics.

  **The BEHAVIOR boundary does not promote at all.** `[v/behavior {…}
  node]` is classified a foreign component boundary by
  `:re-frame.freehand/v1` and refused, so the measure-then-place half of a
  dropdown cannot be compiled. The row that pins it names the diagnostic
  and its recovery, because a refusal a pilot met is worth more as a
  reproduction than as prose.

  Promotion reaches a BROWSER too — the promoted panel in a real top
  layer, building the interpreted twin's document, and its anchor
  dispatching a live click — but that row needs a live `document` and a
  real commit, so it lives in `pilot-overlay-dom-cljs-test`. Every row
  here is structural, which is what these rows are for."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.pilot-overlay-compiled :as compiled]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.tree :as tree]))

(def ^:private args
  {:open? true :control [:acme.ui/dropdown [:toolbar :doc-1 :format]]})

(v/defview interpreted-panel
  "The identical declaration WITHOUT `{:compiled true}` — the one-line
  difference promotion is, and nothing else changed."
  {:props [:map
           [:open? :boolean]
           [:control :any]]}
  [{:keys [open? control]}]
  [:div {:data-component "acme/dropdown"
         :data-part      "root"
         :style          {:display "inline-block" :position "relative"}}
   [:button {:data-part     "anchor"
             :type          "button"
             :aria-expanded (if open? "true" "false")
             :on-click      [:acme.ui.dropdown/anchor-clicked control]}
    "Export as…"]
   [:div {:data-part          "panel"
          :role               "listbox"
          :popover            :auto
          :re-frame.freehand.web/popover-open? open?
          :on-toggle          [:acme.ui.dropdown/toggle-reported control]
          :style              {:position "fixed"
                               :inset    "auto"
                               :top      "var(--acme-anchor-y)"
                               :left     "var(--acme-anchor-x)"
                               :margin   0}}
    "PDF"]])

(defn- as-compiled-ids
  "Rewrite this namespace's own view ids onto the compiled twin's. A view
  id names where a declaration lives, and living in another file is the
  only difference these two have."
  [x]
  (walk/postwalk
    (fn [v]
      (if (and (keyword? v)
               (= "re-frame.freehand.pilot-overlay-parity-cljs-test" (namespace v)))
        (keyword "re-frame.freehand.pilot-overlay-compiled" "panel")
        v))
    x))

(deftest the-promoted-overlay-markup-denotes-the-same-node
  (testing "The compiled twin's structural tree is the interpreted one's,
            whole: the same reserved `:rf.ui/top-layer` fact on the same
            element, the same event vectors at the same sites, and the same
            attributes around them. Promotion changes when a mistake
            surfaces, not what a declaration means."
    (let [a (tree/render [interpreted-panel args])
          b (tree/render [compiled/panel args])]
      (is (= (as-compiled-ids a) b)
          "the whole tree, not a sampled fact"))))

(deftest the-promoted-overlay-carries-the-top-layer-fact-explicitly
  (testing "Stated separately from the whole-tree equality, so a change
            that made BOTH sides wrong in the same way could not pass
            quietly."
    (doseq [[label tree] [["interpreted" (tree/render [interpreted-panel args])]
                          ["compiled"    (tree/render [compiled/panel args])]]]
      (let [promoted (t/find-all tree #(and (map? %) (contains? % :rf.ui/top-layer)))]
        (is (= 1 (count promoted)) (str label ": exactly one promoted element"))
        (is (= {:popover-open? true} (:rf.ui/top-layer (first promoted)))
            (str label ": and its desired state"))
        (is (= "auto" (:popover (t/attrs (first promoted))))
            (str label ": on a valid popover mode"))))))

(deftest a-compiled-declaration-reports-its-analysis
  (testing "A promoted view's manifest is what makes it statically
            knowable, and here it is the evidence that the overlay's event
            sites really are finite and lexical. The interpreted twin
            reports nil, which is the honest answer for a mode with no
            analysis step rather than an omission."
    (let [m (v/manifest compiled/panel)]
      (is (some? m) "the compiled declaration carries a manifest")
      (is (nil? (v/manifest interpreted-panel))
          "and the interpreted one reports none")
      (is (= 2 (count (:events m)))
          "two lexical event sites — the anchor's click and the panel's toggle")
      (is (= 2 (count (into #{} (map :sid) (:events m))))
          "each with its own lexical site id")
      (is (every? (comp some? :source-coord) (:events m))
          "and every site traceable to the source that made it")
      (is (= [[0] [1]] (mapv :path (:events m)))
          "at the anchor's position and the panel's, in document order"))))

;; ===========================================================================
;; THE FINDING — the measure-then-place half cannot be promoted
;; ===========================================================================

#?(:clj
   (deftest a-behavior-boundary-is-refused-by-the-compiled-grammar
     (testing "A compiled body that attaches a behavior is REFUSED:
               `[v/behavior {…} node]` classifies as a foreign component
               boundary, which `:re-frame.freehand/v1` does not admit.

               That is the pilot's most consequential parity result.
               `v/defbehavior` is the ONE sanctioned imperative boundary
               and `:timing :layout` is the only declared moment before
               paint, so measure-then-place work has exactly one home — and
               a view that goes there can never take the compiled tier. A
               dropdown that measures its anchor is interpreted forever,
               and the recovery the diagnostic names (extract a declared
               child, keep it interpreted) does not change that: it moves
               the interpreted boundary, it does not remove it.

               The refusal is macro-expansion-time, so this row expands the
               declaration itself rather than describing it."
       (let [form '(re-frame.freehand/defview promoted-with-a-behavior
                     {:compiled true}
                     [{:keys [open?]}]
                     [re-frame.freehand/behavior
                      {:use    :re-frame.freehand.pilot-overlay/anchor-box
                       :target [:toolbar :doc-1]
                       :config {:open? open?}}
                      [:div {:data-part "root"} "x"]])
             ;; `macroexpand-1` wraps a macro's own refusal in a
             ;; CompilerException, so the ex-data is one cause down.
             ;; `macroexpand-1` wraps a macro's own refusal in a
             ;; CompilerException whose own ex-data is compiler bookkeeping,
             ;; so the grammar's refusal is found by walking the causes.
             data (try (macroexpand-1 form) nil
                       (catch Throwable e
                         (->> (iterate ex-cause e)
                              (take-while some?)
                              (keep ex-data)
                              (filter :rf.ui.compile/error)
                              first)))]
         (is (some? data) "the declaration really is refused")
         (is (= :rf.ui.compile/unsupported-form (:rf.ui.compile/error data))
             "with the grammar's own refusal id")
         (is (= :foreign (:op data))
             "classified FOREIGN — the compiled tier sees a component it cannot lower")
         (is (= :re-frame.freehand/v1 (:re-frame.freehand/grammar data))
             "against the versioned grammar the marker selects")
         (is (some #{:keep-interpreted} (:recovery data))
             "and the only recovery on offer is to stay interpreted")))))

#?(:clj
   (deftest the-refusal-is-the-behavior-and-not-the-overlay
     (testing "Non-vacuous: the SAME body without the behavior compiles.
               So the boundary the compiled grammar refuses is precisely
               the imperative one, not the overlay markup around it."
       (let [ok (try (macroexpand-1
                       '(re-frame.freehand/defview promoted-without-a-behavior
                          {:compiled true}
                          [{:keys [open?]}]
                          [:div {:data-part "root"}
                           [:div {:popover :auto
                                  :re-frame.freehand.web/popover-open? open?
                                  :on-toggle [:acme.ui.dropdown/toggle-reported :x]}
                            "PDF"]]))
                     ::compiled
                     (catch Throwable e (.getMessage e)))]
         (is (= ::compiled ok)
             (str "the overlay markup alone compiles: " (when (string? ok) (str/join " " (take 30 (str/split ok #"\s+"))))))))))
