(ns re-frame.freehand.virtual-collection-parity-cljs-test
  "FH-CTRL-021, applicability `common` — the COMPILED half.

  The laws in [[re-frame.freehand.virtual-collection-cljs-test]] bind both
  execution modes, and most of the control could not differ between them:
  `window` and `reveal-offset` are ordinary functions no lowering touches.
  What promotion COULD move is the tree — the keys, the
  positions, the stated sizes, the event sites and the window boundaries —
  so this suite renders the promoted vertical
  ([[re-frame.freehand.virtual-collection-compiled]]) beside the
  interpreted one and compares what they actually produce, at every offset
  the fixture names.

  ## Three things it establishes, in order of strength

  **The shipped control is inside the grammar.** `v/check` reads the
  PRODUCTION source file — the one an application requires — and reports
  both declarations compile-eligible with no findings. That is the claim
  an author cares about, and it is checked against the file rather than
  against the twin, so a production body drifting out of the grammar reds
  here even if the twin does not.

  **The whole vertical promotes.** The engine, the listbox row, the
  listbox and the caller all carry `{:compiled true}`, so the compiled arm
  is compiled through every boundary the control owns rather than an
  interpreted caller driving one compiled leaf. The manifest is the
  evidence: a real analysis exists for each, with the event and slot sites
  the body writes — and where those sites LAND is itself the machine-checked
  form of the engine/listbox split.

  **The control reads nothing.** The manifest's `:subscriptions` roster is
  the machine-checked form of the narrow-read design — the list and its
  row have no reactive site at all, so every read in a virtual collection
  belongs to the caller or to a row's own boundary. That is a statement
  the interpreted arm can only make by inspection.

  ## Equality is asserted over the whole tree, not a sample

  A sampled comparison — the row count here, a key there — is exactly the
  shape that stays green while a lowering moves an attribute nobody
  sampled. So the two trees are compared WHOLE, after rewriting the one
  thing that is legitimately different: a view id carries the namespace
  its declaration lives in, and the twin lives in another namespace by
  necessity."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.test :as t]
            [re-frame.freehand.virtual-collection-cljs-test :as interpreted]
            [re-frame.freehand.virtual-collection-compiled :as compiled]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ctrl-021 (conf/fixture :FH-CTRL-021))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(defn- interpreted-tree [] (interpreted/render-tree))

(defn- compiled-tree []
  (t/with-render (t/render [compiled/inbox interpreted/caller-props])))

(def ^:private twin-ns "re-frame.freehand.virtual-collection-compiled")
(def ^:private home-ns "re-frame.freehand.collection")
(def ^:private caller-ns "re-frame.freehand.virtual-collection-cljs-test")

(defn- normalize
  "The compiled tree with the ONE difference a lowering does not cause
  rewritten away: a view id carries the namespace its declaration lives
  in, and the twin lives in a namespace of its own because two
  declarations of one name cannot share one.

  Only view-id keywords are touched, and only their namespace component —
  so a divergence in a tag, an attribute, a key, a position, a stated size
  or an event vector survives the rewrite and fails the comparison."
  [tree]
  (walk/postwalk
    (fn [x]
      (if (and (keyword? x) (= twin-ns (namespace x)))
        (keyword (if (= :inbox (keyword (name x))) caller-ns home-ns) (name x))
        x))
    tree))

;; ===========================================================================
;; FH-CTRL-021 — the shipped declarations are inside the compiled grammar
;; ===========================================================================

#?(:clj
   (deftest fh-ctrl-021-the-production-control-is-compile-eligible-as-it-stands
     (testing "Per FH-CTRL-021, applicability `common`: the claim an author
               cares about is about the SHIPPED file, so the checker reads
               that file rather than the twin. Both declarations are
               eligible with no findings, which is what makes `{:compiled
               true}` a keyword rather than a rewrite — and what makes the
               twin below a promotion rather than a reimplementation."
       (let [reports (v/check "src/re_frame/freehand/collection.cljc")
             by-id   (into {} (map (juxt :view-id identity)) reports)]
         (is (= #{:re-frame.freehand.collection/virtual-collection
                  :re-frame.freehand.collection/virtual-list
                  :re-frame.freehand.collection/virtual-row}
                (set (keys by-id)))
             "the checker found all three declarations the control ships —
              the engine, the listbox row and the listbox")
         (doseq [[view-id report] by-id]
           (is (:compile-eligible? report)
               (str view-id " is inside the compiled grammar as it stands"))
           (is (empty? (:findings report))
               (str view-id " raises no finding: " (pr-str (:findings report)))))))))

(deftest fh-ctrl-021-the-whole-vertical-promotes-and-reads-nothing
  (testing "Per FH-CTRL-021: the engine, the listbox row, the listbox and
            the caller all carry `{:compiled true}`, so a real analysis
            exists for each — an interpreted declaration has none, which is
            what makes this row a mode witness rather than a label.

            And the three the CONTROL owns have an EMPTY subscription
            roster. That is the narrow-read design, machine-checked: a
            virtual collection's reads belong to the caller and to each
            row's own boundary, and there is no reactive site inside the
            control for an item edit to reach."
    (doseq [view [compiled/virtual-collection compiled/virtual-list
                  compiled/virtual-row compiled/inbox]]
      (is (some? (v/manifest view))
          "a compiled declaration carries its analysis"))

    (doseq [view [compiled/virtual-collection compiled/virtual-list
                  compiled/virtual-row]]
      (is (= [] (:subscriptions (v/manifest view)))
          (str (:view-id (v/manifest view))
               " subscribes to nothing — every read in a virtual collection
                is the caller's or a row's own")))

    (testing "non-vacuous: the CALLER does read, so an empty roster is a
              fact about the control rather than about the roster"
      (is (= #{[:inbox/ids] [:inbox/scroll] [:inbox/active]}
             (set (map :query (:subscriptions (v/manifest compiled/inbox)))))
          "the caller's three reads are finite lexical sites"))

    (testing "and the event sites land on the layer that owns the element
              they are written on — which is how the split is visible to a
              machine rather than only to a reader"
      (is (= [:on-scroll :spread-safe]
             (mapv :prop (:events (v/manifest compiled/virtual-collection))))
          "the ENGINE owns the scroll — it owns the scroll host — plus the
           forwarding seam every decorating attribute arrives through,
           `:on-key-down` among them")
      (is (= [] (mapv :prop (:events (v/manifest compiled/virtual-list))))
          "the LISTBOX writes no element at all: it is a props map handed to
           the engine, which is the machine-checked form of `it contains no
           second scroll host`")
      (is (= [:on-click]
             (mapv :prop (:events (v/manifest compiled/virtual-row))))
          "and the row owns the activation — one committed slot per row,
           which is why the row is a declared child rather than markup in
           the loop"))))

;; ===========================================================================
;; FH-CTRL-021 — the two lowerings build one tree
;; ===========================================================================

(deftest fh-ctrl-021-both-lowerings-render-one-tree-at-every-offset
  (testing "Per FH-CTRL-021, applicability `common`: the WHOLE tree is
            compared, at every offset the fixture names, so a divergence in
            a tag, an attribute, a stated size, a position, an event vector
            or a key fails here rather than surviving a sample. The only
            rewrite is the view id's namespace, which the twin cannot share
            by construction."
    (interpreted/register!)
    (interpreted/seed!)
    (doseq [{:keys [note scroll-offset] w-count :count} (:windows ctrl-021)]
      (interpreted/scroll-to! scroll-offset)
      (let [a (interpreted-tree)
            b (normalize (compiled-tree))]
        (is (= a b) (str "one tree at " note))
        (is (= w-count (count (interpreted/rows a)))
            (str "non-vacuous at " note ": the trees compared really do hold
                  the window rather than being two empty renders"))))

    (testing "and the comparison has teeth — an unnormalized compiled tree
              differs, so equality above is a real fact about two renders
              rather than a rewrite that erased the difference"
      (interpreted/scroll-to! 0)
      (is (not= (interpreted-tree) (compiled-tree))
          "the twin's view ids carry its own namespace before normalization"))))

(deftest fh-ctrl-021-both-lowerings-key-the-same-rows-at-the-same-indices
  (testing "Per FH-CTRL-021: the identity claim, under promotion. The two
            lowerings must agree not merely on how many rows there are but
            on WHICH item each row is and where the document addresses it,
            because a lowering that renumbered either would remount the
            whole window on the first scroll of a compiled build."
    (interpreted/register!)
    (interpreted/seed!)
    (let [{:keys [scroll-offset]} (:first-key-at ctrl-021)]
      (interpreted/scroll-to! scroll-offset)
      (let [a-cells (interpreted/cells (interpreted-tree))
            b-cells (interpreted/cells (compiled-tree))
            a-ids   (mapv #(:id (t/attrs %)) (interpreted/rows (interpreted-tree)))
            b-ids   (mapv #(:id (t/attrs %)) (interpreted/rows (compiled-tree)))]
        (is (seq a-cells) "non-vacuous: the interpreted arm rendered rows")
        (is (= (mapv :props a-cells) (mapv :props b-cells))
            "both lowerings hand the same items to the same row contents")
        (is (= a-ids b-ids)
            "and address them identically in the document")
        (is (= (:dom-id (:first-key-at ctrl-021)) (first b-ids))
            "at the absolute index the fixture names")))))
