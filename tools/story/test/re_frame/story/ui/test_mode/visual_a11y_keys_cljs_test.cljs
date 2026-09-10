(ns re-frame.story.ui.test-mode.visual-a11y-keys-cljs-test
  "rf2-32ib — every sibling the visual + a11y results section emits from a
  `for` reaches React carrying a key.

  ## What was broken, and why no markup assertion could see it

  `visual-a11y-section` spelled its sibling key as `^{:key …}` reader
  metadata on the `(if (= kind :visual) [visual-card row] [a11y-card row])`
  CALL FORM. Clojure metadata on a call form is discarded the moment the
  form evaluates, so the vector the `if` returns carried none of it and
  React received no key — on ANY substrate, not merely at a future Fresco
  boundary. Nothing supplied one by another route either: `a11y-card` and
  `visual-card` both root at a `[:div {:style … :data-test …}]` whose attrs
  map has no `:key`.

  A lost key does not FAIL. It degrades silently into index-based
  reconciliation, which paints identically and corrupts card identity only
  once the row seq changes shape.

  `findings-list`'s key (the second site in that file) rode a vector
  LITERAL, which Reagent does read — that one was LATENT rather than dead,
  dying only at a Fresco boundary, whose codec reads `:key` from the
  attribute map and Clojure metadata nowhere. Both now carry the key in an
  attribute map, which both renderers honour.

  ## `(meta …)` WOULD BE A HOLLOW GATE IN BOTH DIRECTIONS

  It reads nil on the BROKEN spelling, because the call form discarded the
  metadata; and it reads nil on the FIXED spelling too, because the key now
  lives in an attribute map, where it is not metadata at all. A gate built
  on `(meta …)` therefore answers identically either way and proves
  nothing. Hence `r/as-element` below: it runs Reagent's own key resolution
  — metadata first, then props — so these rows grade what the RENDERER
  receives rather than how the key happens to be spelled. A meta-spelled
  key would still pass here, which is deliberate: the subject is whether
  React gets a key, not which syntax delivered it.

  ## The walk is RAW

  Row nodes are taken from the hiccup WITHOUT rebuilding it. A `mapv`-style
  rebuild (what the shared `expand-tree` helper does) mints fresh vectors
  and strips reader metadata, so a meta-spelled key could not be seen at
  all and this gate would read a false nil and fail for the wrong reason."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
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
  stamp. Both branches of the `if` the dead metadata sat on are exercised:
  the `:visual` record takes the true branch, the two a11y records the
  false one — the branch being the very thing that discarded the key.

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

;; ---- rf2-32ib — the live defect -----------------------------------------

(deftest section-rows-reach-react-with-distinct-keys
  (testing "rf2-32ib — every card the `visual-a11y-section` `for` emits
            reaches React carrying a key, and sibling keys are distinct.
            The key used to ride reader metadata on the `(if …)` call form,
            which reaches React on no substrate at all."
    (seed!)
    (let [rows (section-rows)
          ks   (mapv react-key rows)]

      ;; Precondition — a vacuous pass is the failure mode here, so the row
      ;; count is asserted before the keys are.
      (is (= 3 (count rows)) "the fixture's three browser-tier rows each render")

      (is (every? some? ks) "every row reaches React with a key")
      (is (= 3 (count (distinct ks))) "sibling keys are distinct")
      (is (= [":visual#0" ":a11y-structural#1" ":a11y#2"] ks)
          "the key React receives is the `<kind>#<index>` the section
           stamps, unchanged from the pre-repair expression"))))

;; ---- rf2-32ib — the latent site in the same file -------------------------

(deftest findings-list-rows-reach-react-with-distinct-keys
  (testing "rf2-32ib — every `<li>` `findings-list` emits reaches React
            carrying a key. This one was LATENT rather than dead (Reagent
            reads metadata on a vector literal); the key now rides the
            `<li>`'s own attribute map, which Fresco's codec reads too."
    (seed!)
    (let [lis (finding-rows)
          ks  (mapv react-key lis)]

      (is (= 3 (count lis)) "the fixture's three structural findings each render")

      (is (every? some? ks) "every finding row reaches React with a key")
      (is (= 3 (count (distinct ks))) "sibling keys are distinct")
      (is (= [":img-missing-alt#0" ":control-missing-name#1" ":some-future-rule#2"] ks)
          "the key React receives is the `<rule>#<index>` the list stamps,
           unchanged from the pre-repair expression"))))
