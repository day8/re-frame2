(ns re-frame.hicasso.view-annotation-slot-cljs-test
  "SPEC 006'S TWO ANNOTATIONS AND THE AUTHOR'S OWN VALUE, OWNED AT THE
  CANONICAL SLOT RATHER THAN AT THE KEYWORD (rf2-c5w1).

  `collector/annotate-root` merges the framework's two attributes UNDER
  the body's own attrs, and Spec 006 §Cross-host records the consequence
  as a guarantee: a body that wrote either attribute itself keeps the
  value it wrote.

  `merge` resolves a collision only between keys that are `=`, and this
  codec accepts FIVE spellings of one attribute — keyword, namespaced
  keyword, symbol, namespaced symbol and string — every one of which
  `codec/canonical-slot` folds onto the SAME React prop name. Two
  surviving keys therefore reach `convert-props`, which writes both into
  one slot, and the map's ITERATION ORDER picks the winner.

  ## Why these rows and not the DOM witness beside them

  The DOM rows in `view-annotation-dom-cljs-test` assert the guarantee
  through a body that writes the keyword — the one spelling `merge`
  collapses — so they pass whether ownership is held at the key or at the
  slot. A small array map iterates in insertion order and happens to pass
  too, which is why a bigger map is what exposed this.

  So the property is pinned TWICE here. Once STRUCTURALLY: after the merge
  exactly one key in the map canonicalises to each annotation slot. That
  claim cannot depend on iteration order at all — there is no second entry
  for an order to choose between — which is what makes it a witness rather
  than a lucky run. And once through `convert-props`, the emission step
  the DOM actually shows. Both are taken over an array map AND over a
  PersistentHashMap, and one row asserts those two are genuinely different
  implementations so the second is not silently the first."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [goog.object :as gobj]
            [re-frame.hicasso.impl.codec :as codec]
            [re-frame.hicasso.impl.collector :as collector]))

(def ^:private annotations
  "The framework's pre-built attrs map, ASKED of the collector rather than
  spelled here, so a rename of either key reddens these rows instead of
  passing beside them. The name was never declared through the macros, so
  `error/source-of` answers nil and the coordinate degrades to `?` — which
  is irrelevant to every claim below, all of which are about the KEY."
  (collector/view-annotations "re-frame.hicasso.view-annotation-slot-cljs-test/probe"))

(def ^:private author-value "author-wrote-this")

(defn- spellings
  "Every prop-key spelling of `k`'s name — the four `codec/canonical-slot`
  reads through `name`, plus the string the slot rule takes verbatim."
  [k]
  (let [n (name k)]
    [(keyword n)
     (keyword "probe" n)
     (symbol n)
     (symbol "probe" n)
     n]))

(defn- small-attrs
  "The author's attrs as a two-entry map: a PersistentArrayMap, which
  iterates in insertion order."
  [k]
  {k author-value :class "small"})

(defn- hashed-attrs
  "The author's attrs as a PersistentHashMap carrying eleven ordinary
  filler attributes beside the authored one — the shape the audit of
  PR #9191 reproduced against, and one whose iteration order is not
  insertion order. `apply hash-map` rather than a map literal so the
  implementation is chosen by construction and not by counting entries."
  [k]
  (apply hash-map
         (into [k author-value]
               (mapcat (fn [i] [(keyword (str "data-filler-" i)) (str i)]))
               (range 11))))

(defn- claimants
  "The keys of `attrs` whose canonical React slot is `slot`."
  [attrs slot]
  (into #{} (filter #(= slot (codec/canonical-slot %))) (keys attrs)))

(defn- emitted
  "The value `convert-props` writes into `slot` — the emission step, on a
  plain `:p` so the tag shorthand folds nothing."
  [attrs slot]
  (gobj/get (codec/convert-props attrs (codec/cached-parse :p)) slot))

(defn- annotated
  "The attrs map `annotate-root` leaves on the root it is handed."
  [authored]
  (nth (collector/annotate-root [:p authored] annotations) 1))

;; ---------------------------------------------------------------------------
;; The premise, established at source
;; ---------------------------------------------------------------------------

(deftest every-spelling-of-an-annotation-names-one-slot
  (testing "the accepted spelling set is what `codec/canonical-slot` folds
            together, and for both annotations it is a single slot — the
            fact that makes a key-keyed merge the wrong instrument"
    (doseq [[k _] annotations]
      (is (= #{(codec/canonical-slot k)}
             (into #{} (map codec/canonical-slot) (spellings k)))
          (str (pr-str k) ": every spelling emits into one React prop name")))))

(deftest the-two-map-shapes-are-genuinely-different-implementations
  (let [k (first (keys annotations))]
    (is (not= (type (small-attrs k)) (type (hashed-attrs k)))
        "the array-map row and the hash-map row below exercise two different
         map implementations — without this the second row would be the
         first wearing a different name, and the order-dependence would go
         unmeasured")))

;; ---------------------------------------------------------------------------
;; The guarantee, at the slot
;; ---------------------------------------------------------------------------

(deftest the-author-owns-the-slot-however-the-annotation-is-spelled
  (doseq [[k framework-value] annotations
           :let               [slot  (codec/canonical-slot k)
                               other (first (remove #(= k %) (keys annotations)))]
          spelling            (spellings k)
          [shape authored]    [["array map" (small-attrs spelling)]
                               ["hash map"  (hashed-attrs spelling)]]]
    (testing (str (pr-str k) " written as " (pr-str spelling) " in an " shape)
      (let [merged (annotated authored)]
        (is (= #{spelling} (claimants merged slot))
            "exactly ONE key survives the merge into that slot — the author's.
             A second one leaves the winner to the map's iteration order,
             which is the defect this row exists to catch")
        (is (= author-value (emitted merged slot))
            "and the value the codec emits into the slot is the author's")
        (is (not= framework-value (emitted merged slot))
            "never the framework's")
        (is (= (get annotations other) (emitted merged (codec/canonical-slot other)))
            "while the annotation the author did NOT write is still stamped —
             ownership is per slot, not a blanket refusal to annotate")))))

(deftest an-untouched-root-still-carries-both-annotations
  (testing "the ordinary body — no collision in any spelling — is unchanged
            by slot-keyed ownership"
    (let [merged (annotated {:class "plain" :id "keep-me"})]
      (doseq [[k v] annotations]
        (is (= v (emitted merged (codec/canonical-slot k)))
            (str (pr-str k) " is stamped")))
      (is (= "keep-me" (emitted merged "id")) "and the author's attrs stand"))))
