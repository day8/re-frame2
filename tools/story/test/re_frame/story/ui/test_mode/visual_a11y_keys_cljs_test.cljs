(ns re-frame.story.ui.test-mode.visual-a11y-keys-cljs-test
  "Every sibling the visual + a11y results section emits from a `for`
  reaches React carrying a key.

  A lost key does not fail: it degrades silently into index-based
  reconciliation, which paints identically and corrupts card identity only
  once the row seq changes shape. Key metadata on the section's `(if …)`
  CALL FORM is discarded when the form evaluates, and Fresco's codec reads
  `:key` only from the attribute map.

  `(meta …)` would be a hollow gate (nil on either spelling), so the key is
  read through `r/as-element`, which runs Reagent's own resolution —
  metadata first, then props — and grades what the RENDERER receives. The
  walk is RAW: a `mapv`-style rebuild mints fresh vectors and strips reader
  metadata, so this gate would read a false nil."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [reagent.core :as r]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]
            [re-frame.story.ui.test-mode.visual-a11y-view :as rf.story.ui.test-mode.visual-a11y-view]))

;; ---- fixtures ------------------------------------------------------------

(defn- reset-results! [] (reset! rf.story.ui.test-mode.state/results-atom {}))

(use-fixtures :each {:before reset-results! :after reset-results!})

(def ^:private variant-id :rf2-32ib.story/visual-a11y)

(def ^:private run-result
  "THREE browser-tier records, because a one-element sequence cannot
  distinguish a real key from a missing one and two cannot show a stable
  stamp. Both branches of the section's `if` are exercised: the `:visual`
  record takes the true branch, the two a11y records the false one — the
  branch being where call-form metadata would be discarded.

  The a11y-structural record carries THREE findings for the same reason,
  so `findings-list`'s own `for` is graded on more than a singleton."
  {:assertions
   [{:assertion :rf.assert/visual-snapshot
     :status    :pass
     :reason    "snapshot matched baseline"
     :actual    "sha256:0ab1"
     :expected  "sha256:0ab1"}
    {:assertion :rf.assert/a11y-structural
     :status    :fail
     :reason    "3 structural violations"
     :count     3
     :actual    [{:rule :img-missing-alt :tag :img
                  :detail "img element has no :alt attribute"}
                 {:rule :control-missing-name :tag :button
                  :detail "button control has no accessible name"}
                 {:rule :some-future-rule :tag :div
                  :detail "a rule this projection does not know by name"}]}
    {:assertion :rf.assert/a11y
     :status    :fail
     :reason    "1 axe violation"
     :count     1
     :actual    [{:id "color-contrast" :impact "serious"
                  :help "Elements must have sufficient colour contrast"}]}]})

(defn- seed! []
  (swap! rf.story.ui.test-mode.state/results-atom
         assoc variant-id {:result run-result}))

;; ---- raw helpers ---------------------------------------------------------

(defn- raw-seq-child
  "The single lazy seq among `node`'s children — i.e. exactly what a `for`
  emitted, RAW. Never rebuilt (see the ns docstring on why a rebuild would
  make this gate lie)."
  [node]
  (vec (first (filter seq? (drop 2 node)))))

(defn- raw-child-by-tag
  [node tag]
  (first (filter #(and (vector? %) (= tag (first %))) (drop 2 node))))

(defn- react-key
  "The key REACT actually receives for one node. `r/as-element` runs
  Reagent's own key resolution — metadata first, then the props map — so
  this reads the rendered element rather than the authoring shape."
  [node]
  (.-key (r/as-element node)))

(defn- section-rows
  "The per-row nodes `visual-a11y-section`'s `for` emits."
  []
  (raw-seq-child (rf.story.ui.test-mode.visual-a11y-view/visual-a11y-section variant-id)))

(defn- component-calls
  "Every `[component-fn & args]` node at or under `node`, RAW.

  Deliberately shape-TOLERANT about the wrapper: whether the section emits
  each card bare or inside a keyed fragment is `section-rows-reach-react-
  with-distinct-keys`'s subject, not this one's. Coupling the two would let
  the findings row go red for the section's reason, which is a worse gate
  than no gate — it reports the same defect twice and hides its own."
  [node]
  (cond
    (and (vector? node) (fn? (first node))) [node]
    (vector? node) (mapcat component-calls
                           (drop (if (map? (second node)) 2 1) node))
    (seq? node)    (mapcat component-calls node)
    :else          nil))

(defn- finding-rows
  "The `<li>` nodes `findings-list` emits, reached by INVOKING the card
  components the section embeds. `a11y-card` and `findings-list` are both
  private, so the rendered tree is the only honest route to them — and
  going through it is what makes this a gate on what the section really
  renders rather than on a hand-built call.

  Every card is tried and the first one carrying a findings `<ul>` wins:
  the `:visual` card renders a snapshot box and no list, so selecting by
  position would silently grade the wrong card."
  []
  (some (fn [call]
          (let [card (apply (first call) (rest call))]
            (some-> (raw-child-by-tag card :ul) raw-seq-child seq vec)))
        (mapcat component-calls (section-rows))))

;; ---- the section's cards ---------------------------------------------------

;; The exact keys imply the rest: three rows rendered (no vacuous pass),
;; every one keyed, siblings distinct.
(deftest section-rows-reach-react-with-distinct-keys
  (seed!)
  (is (= [":visual#0" ":a11y-structural#1" ":a11y#2"]
         (mapv react-key (section-rows)))))

;; ---- the findings list in the same file -----------------------------------

(deftest findings-list-rows-reach-react-with-distinct-keys
  (seed!)
  (is (= [":img-missing-alt#0" ":control-missing-name#1" ":some-future-rule#2"]
         (mapv react-key (finding-rows)))))
