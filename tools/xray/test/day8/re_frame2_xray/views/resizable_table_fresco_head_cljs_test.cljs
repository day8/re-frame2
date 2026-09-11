(ns day8.re-frame2-xray.views.resizable-table-fresco-head-cljs-test
  "rf2-fcy5 slice 1b — the shared resizable-table's FRESCO SIBLING.

  `views/resizable_table.cljs` now ships two heads over one renderer:
  `resizable-table`, the Reagent `reg-view`, and `resizable-table-view`,
  the `rf.fresco/defview` boundary. Same props, same output; they differ
  only in how they resolve the column-widths read and the frame-bound
  dispatcher the drag flow runs on.

  ## Why the codec is the instrument, not a pattern match

  `head-kind` is the renderer's OWN answer to 'what kind of head is
  this?', and it is one call. A census that pattern-matches the authoring
  shape gets this wrong in both directions: a `reg-view` head is a `def`
  and LOOKS like a component while grading `:invalid`, and a `defview`
  product is also a `def` and grades `:boundary`. `heads-are-what-the-
  codec-says-they-are` asks the codec instead, in both directions and
  against a `:tag` control.

  ## The kit runs the BODY, which is the half a `def` cannot pin

  `re-frame.fresco.test/tree` runs a boundary body on the runtime's own
  body-run path — the real ambient-read extent, the real read-set
  accounting — and answers a Spec 004B tree. No React, no DOM, so it runs
  in `:node-test` beside everything else.

  That matters here for one specific reason: `rf.fresco/sub` REFUSES
  outside a render extent, so a boundary whose read is misspelled cannot
  be caught by looking at the var. The kit's `:subs` roster is what turns
  the read into a gate — a body that reads a key no fixture answers is
  refused by name (`:rf.error/fresco-test-missing-read-fixture`) rather
  than resolving to nil, so the fixture below IS the assertion that the
  boundary reads `[:rf.xray.column-widths/for-table <table-id>]` and
  nothing else.

  ## THE FIXTURE IS THE CORE ONE, WITH `:ambient-frame nil`, AND THAT IS
  LOAD-BEARING

  The suite's usual `make-xray-runtime-fixture` cannot be used here, and
  the failure is loud rather than subtle, so it is worth naming for
  whoever writes the next boundary test.

  `make-reset-runtime-fixture` binds `re-frame.frame/*current-frame*` to
  `:rf/default` by default, and the Xray wrapper deliberately does not
  thread the option that turns it off. `rf.fresco.test/tree` runs the body under a
  probe frame of its own, and `(rf/capture-frame)` inside a boundary body
  refuses when it finds a CARRIED stamp naming a different frame from the
  extent's — `:rf.error/ambient-frame-refused`, on the reasoning that two
  frames in one body is exactly what frame isolation forbids. The refusal
  is correct; an ambient `:rf/default` left standing by a test fixture is
  the artefact. So this namespace opts out, as
  `make-xray-runtime-fixture`'s own docstring says to
  (\"reach for the core fixture directly if you do\"), and drives its own
  frames explicitly.

  Production is unaffected: a boundary renders inside a React tree with
  no dynamic frame scope in force, and an Xray shell that does scope one
  scopes the SAME frame the boundary renders under, which is a match
  rather than a conflict.

  ## WHO ADOPTS `resizable-table-view` (rf2-k97c.3)

  This section used to read \"Nothing adopts `resizable-table-view`\" —
  the two consumer panels migrated on their own beads, and until their
  own mounts were boundaries they had to keep mounting the Reagent head.
  BOTH HAVE SINCE MIGRATED, so all five production call sites now head
  the boundary: `panels/epoch/view.cljs` ×3 and `panels/trace.cljs` ×2.
  The Reagent head has no production call site left.

  That does NOT make the rows below vestigial, and they are the reason
  this namespace is still the right home for them. A Fresco boundary in
  a Reagent head position is the mirror of the failure
  `reagent-head-is-invalid-to-the-codec` pins, and that refusal is what
  makes a revert of either panel LOUD rather than silent — so the row
  grades a live contract, not a historical one. `both-heads-resolve-the-
  same-widths` likewise still has two heads to compare: the Reagent one
  survives as the tree's only public pure-render door into the private
  `render-table` (see its own docstring for the four namespaces that
  depend on it)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.test :as rf.fresco.test]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.substrate.plain-atom/adapter
     ;; See the ns docstring — an ambient `:rf/default` collides with the
     ;; kit's probe frame inside a boundary body.
     :ambient-frame nil
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      (rt/clear!)
                      (rt/set-storage-key! nil))}))

;; ---- fixtures -----------------------------------------------------------

(def ^:private table-id :rf.xray.test/fresco)

(def ^:private columns
  [{:id :a :label "a" :default-flex "1fr"}
   {:id :b :label "b" :default-flex "1fr"}
   {:id :c :label "c" :default-flex "1fr"}])

(def ^:private overrides
  "A width override per column pair, so `build-template` has something to
  say that the default flex tracks do not — otherwise a read that never
  happened would produce the same template as one that did."
  {:a 120 :b 90})

(defn- opts []
  {:table-id  table-id
   :columns   columns
   :rows      [{:v 1} {:v 2}]
   :row-key   (fn [_row i] (str "row-" i))
   :row-attrs (fn [_row i] {:data-testid (str "r-" i)})
   :row-cells (fn [row _i]
                (for [col columns]
                  [:div {:data-col (name (:id col))} (str (:v row))]))})

(def ^:private widths-query
  [:rf.xray.column-widths/for-table table-id])

;; ---- (1) the codec's own answer ----------------------------------------

(deftest heads-are-what-the-codec-says-they-are
  (testing "rf2-fcy5 slice 1b — `head-kind` is the renderer's own answer,
            asked in both directions. Both heads are `def`s and neither
            spelling can be told from the other by looking; only the codec
            knows."
    (is (= :boundary (rf.fresco.impl.codec/head-kind rt/resizable-table-view))
        "the Fresco sibling is a minted boundary")
    (is (true? (rf.fresco.impl.codec/boundary-head? rt/resizable-table-view))
        "…and carries the boundary marker the codec reads")
    (is (= :invalid (rf.fresco.impl.codec/head-kind rt/resizable-table))
        "the Reagent `reg-view` head is NOT a boundary — a plain fn to the
         codec, which is the whole reason this sibling exists")
    (is (= :tag (rf.fresco.impl.codec/head-kind :div))
        "control — the instrument distinguishes, so `:invalid` above is an
         answer rather than a default")))

(deftest reagent-head-is-invalid-to-the-codec
  (testing "rf2-fcy5 slice 1b — the refusal a consumer panel would meet if
            it kept `[rt/resizable-table …]` after its own mount became a
            boundary. It is LOUD, which is why both heads can ship at once
            and why the panels can migrate one at a time."
    (let [thrown (try
                   (rf.fresco.impl.codec/as-element [rt/resizable-table (opts)])
                   nil
                   (catch :default e e))]
      (is (some? thrown) "a `reg-view` head in a Fresco position throws")
      (is (= :rf.error/fresco-bad-head (:rf.error/id (ex-data thrown)))
          "…under the codec's own bad-head id"))
    (is (some? (rf.fresco.impl.codec/as-element
                 [rt/resizable-table-view (opts)]))
        "…while the boundary is accepted in the same position")))

;; ---- (2) the body actually runs, and reads the slot it claims to -------

(defn- fresco-tree []
  (rf.fresco.test/tree [rt/resizable-table-view (opts)]
           {:subs {widths-query overrides}}))

(defn- style-of [node]
  (:style (rf.fresco.test/attrs node)))

(defn- testid-of [node]
  (:data-testid (rf.fresco.test/attrs node)))

(deftest fresco-head-runs-and-reads-the-column-widths-slot
  (testing "rf2-fcy5 slice 1b — the boundary body runs on the runtime's own
            body-run path under exactly ONE read fixture. A body reading a
            key no fixture answers is refused by name, so a green run here
            IS the assertion that the read is
            `[:rf.xray.column-widths/for-table <table-id>]`."
    (let [tree (fresco-tree)]
      (is (= :div (:tag tree)) "the container is a div")
      (is (= "fresco" (:data-rf-xray-resizable-table (rf.fresco.test/attrs tree)))
          "…and it is this widget's container")
      (is (= (rt/build-template columns overrides)
             (:grid-template-columns (style-of (rf.fresco.test/find tree #(= "grid" (:display (style-of %)))))))
          "THE READ ROW — the header's grid template is built from the
           fixture's overrides, so the value the boundary read reached
           `build-template`. Compared against the widget's own pure
           builder rather than a hand-typed string, so the row grades the
           read and not the track syntax."))))

(deftest fresco-head-emits-the-gutters-as-native-nodes
  (testing "rf2-fcy5 slice 1b — the gutters are the half that USED to be
            impossible under a boundary: `header-gutter` held a Form-2
            Reagent Ratom for its hover flag, so it sat in head position
            as a plain `defn` and the codec refused it. With the hover
            moved to CSS it is a pure fn the weaver CALLS, so what the
            Fresco tree carries here is N-1 ordinary divs."
    (let [tree    (fresco-tree)
          gutters (rf.fresco.test/find-all tree #(some-> (testid-of %)
                                             (.startsWith "rf-xray-resizable-gutter-")))]
      (is (= 2 (count gutters)) "N-1 gutters for N columns")
      (is (= ["rf-xray-resizable-gutter-fresco-a"
              "rf-xray-resizable-gutter-fresco-b"]
             (mapv testid-of gutters))
          "the stable testids the CSS hover rule selects on")
      (is (= ["g-a" "g-b"] (mapv :key gutters))
          "keyed in the attrs map, which is where the codec reads a key")
      (is (every? #(= "transparent" (:background (style-of %))) gutters)
          "no hover state to render — the accent is the global CSS rule's")
      (is (every? #(contains? (:events %) :on-pointer-down) gutters)
          "the drag affordance survives the migration"))))

(deftest fresco-head-emits-every-row-with-the-consumers-key
  (testing "rf2-fcy5 slice 1b — the row weaver's output under the boundary.
            Slice 1 moved these keys out of metadata and into the attrs
            map precisely so this would hold; this is that fix observed
            through a real Fresco body run rather than through the codec's
            element door alone."
    (let [tree (fresco-tree)
          rows (rf.fresco.test/find-all tree #(some-> (testid-of %) (.startsWith "r-")))]
      (is (= 2 (count rows)) "one node per row")
      (is (= ["row-0" "row-1"] (mapv :key rows))
          "the consumer's `:row-key`, reaching the substrate"))))

;; ---- (3) the two heads agree ------------------------------------------

(deftest both-heads-resolve-the-same-widths
  (testing "rf2-fcy5 slice 1b — one renderer, two reads. The Reagent head
            resolves the slot through its `reg-view`-injected `subscribe`
            and the boundary through `rf.fresco/sub`; with the SAME
            overrides in play both must produce the same grid template.
            This is the row that would go red if a future edit taught one
            head a different query vector or a different renderer."
    (registry/register-xray-handlers!)
    (rf/make-frame {:id :rf/xray})
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray.column-widths/hydrate {table-id overrides}]))
    (let [reagent-tree (rf/with-frame :rf/xray (rt/resizable-table (opts)))
          reagent-header (nth reagent-tree 2)
          reagent-template (get-in reagent-header [1 :style :grid-template-columns])
          fresco-template (:grid-template-columns
                            (style-of (rf.fresco.test/find (fresco-tree)
                                               #(= "grid" (:display (style-of %))))))]
      (is (= (rt/build-template columns overrides) reagent-template)
          "the Reagent head read the hydrated slot")
      (is (= reagent-template fresco-template)
          "…and the boundary resolved the same widths through
           `rf.fresco/sub`"))))
