(ns re-frame.freehand.crossings-cljs-test
  "THE CROSSINGS — a codebase may be mixed, and mixing it costs nothing at
  the call site.

  Promotion is per declaration and not transitive, so the interesting
  question is never 'does a compiled view work' but 'what happens where
  the two modes meet'. Three claims are made here, on BOTH hosts:

  1. **The crossing is invisible in the output.** Four declarations, one
     body, every pairing of parent mode and child mode — `FH-CALL-004`
     pins the ONE tree all four produce.
  2. **Markup already in hand crosses at a declared child.** `v/markup`
     is not a grammar valve; it is an ordinary interpreted view, and
     `FH-CALL-005` pins what that buys — a real boundary node, its own
     recorded props, its own expansion.
  3. **The manifest marks where the compiled tier stops.** A compiled
     declaration says which of its child boundaries cross back into the
     interpreted mode, so 'compiled' in an inventory cannot quietly mean
     'compiled except for most of the work'.

  ## What is proven structurally, and what is not yet

  Every claim below is asserted against the **structural tree** — the
  host-neutral render both hosts run. The compiled React lowering is not
  landed (it waits on the ViewCell slice), so nothing here is a claim
  about React elements, and the mount COUNT in
  [[one-interp-slot-mounts-once-per-row]] is read off the structural tree
  rather than off an occurrence evidence record, which does not exist
  yet. That is the honest extent of the proof today: the manifest half of
  the `:interp-slots` contract is complete, and the evidence half is
  counted from the tree that the evidence record will later count from."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.freehand :as v]
            [re-frame.freehand.compiled-views :as compiled]
            [re-frame.freehand.conformance :as conf]
            [re-frame.freehand.crossing-views :as cross]
            [re-frame.freehand.tree :as tree]
            [re-frame.freehand.tree-views :as views]))

(def call-004 (conf/fixture :FH-CALL-004))
(def call-005 (conf/fixture :FH-CALL-005))

(defn- view-id [view]
  (:view-id (v/describe view)))

(defn- blind
  "`tree` with the ids in `substitutions` replaced by their placeholders.

  The ONLY rewriting a crossing assertion is allowed. A view id names the
  declaration a view IS, and four mode pairings need four declarations —
  so the parent's id and the child's id differ between cases for a reason
  that has nothing to do with mode. Everything else in the tree is
  compared literally."
  [substitutions tree]
  (walk/postwalk #(get substitutions % %) tree))

(def ^:private child-descriptors
  "The fixture's child view ids -> the declarations they name. The fixture
  is EDN and names a view; this is where the name becomes the value, and
  it is also what stops a case from asserting a lowering the fixture
  merely claims."
  {:re-frame.freehand.tree-views/row     views/row
   :re-frame.freehand.compiled-views/row compiled/row})

(defn- boundaries
  "Every view-boundary node in `tree` whose view id is `id`."
  [id tree]
  (filterv #(= id (:view-id %)) (tree-seq map? :children tree)))

;; ---------------------------------------------------------------------------
;; FH-CALL-004 — the crossing is invisible in the output
;; ---------------------------------------------------------------------------

(deftest every-mode-pairing-yields-one-tree
  (testing "Per FH-CALL-004: four declarations of one body, over every
            pairing of parent mode and child mode, render the same
            structural tree. A caller cannot tell which mode a mounted
            declaration selected, and that is the property the whole
            two-mode design rests on."
    (let [{:keys [args cases tree]} call-004]
      (is (= 4 (count cases)) "every pairing of the 2 x 2 is present")
      (doseq [{:keys [note view child-view-id parent-lowering child-lowering]} cases]
        (let [parent (get cross/by-name view)
              child  (get child-descriptors child-view-id)]
          (is (some? parent) (str note " — the fixture names a declared parent"))
          (is (some? child) (str note " — the fixture names a declared child"))
          ;; The pairing is asserted before its output is trusted: a matrix
          ;; whose four cells are secretly the same mode proves nothing.
          (is (= parent-lowering (:lowering (v/describe parent)))
              (str note " — the parent is declared " parent-lowering))
          (is (= child-lowering (:lowering (v/describe child)))
              (str note " — the child is declared " child-lowering))
          (is (= tree (blind {(view-id parent) :fh/parent
                              child-view-id    :fh/child}
                             (tree/render [parent args])))
              note))))))

(deftest an-interpreted-parent-mounts-a-compiled-descriptor
  (testing "The first direction, stated on its own rather than inferred
            from the matrix: an interpreted body reaches a vector head,
            mounts it, and the fact that the child resolved its structure
            at build time changes nothing it can observe. The comparison
            is against the ALL-INTERPRETED equivalent, directly."
    (let [args     (:args call-004)
          all-interp (tree/render [cross/interpreted-parent-interpreted-child args])
          crossing   (tree/render [cross/interpreted-parent-compiled-child args])]
      (is (= (blind {(view-id cross/interpreted-parent-interpreted-child) :fh/parent
                     (view-id views/row)                                 :fh/child}
                    all-interp)
             (blind {(view-id cross/interpreted-parent-compiled-child) :fh/parent
                     (view-id compiled/row)                            :fh/child}
                    crossing))
          "mounting the compiled child yields the all-interpreted output")
      (is (= 2 (count (boundaries (view-id compiled/row) crossing)))
          "and the compiled child really is the one that rendered"))))

(deftest a-compiled-parent-mounts-a-statically-named-interpreted-child
  (testing "The other direction — D010's escape. 'Extract a declared
            child, it may stay interpreted' is the recovery every
            rejection ladder offers, and a recovery that did not work
            would be advice to nowhere."
    (let [args (:args call-004)
          t    (tree/render [cross/compiled-parent-interpreted-child args])
          rows (boundaries (view-id views/row) t)]
      (is (= 2 (count rows)) "both interpreted children mounted")
      (is (= (dissoc (tree/render [views/row {:label 1}]) :rf.ui/tree-version)
             (dissoc (first rows) :key))
          "and each produced exactly what it produces when mounted alone"))))

;; ---------------------------------------------------------------------------
;; FH-CALL-005 — v/markup, the declared boundary a markup VALUE crosses at
;; ---------------------------------------------------------------------------

(deftest markup-is-an-ordinary-declared-interpreted-view
  (testing "The recovery is deliberately not a mechanism. If `v/markup`
            were special — a compiled intrinsic, a second declaration
            form, a head the analyzer knows by name — the grammar would
            have grown a valve wearing a view's clothes."
    (let [d (v/describe v/markup)]
      (is (true? (v/view? v/markup)) "it is a declared view")
      (is (= (:view-id call-005) (:view-id d)) "declared in the public door's namespace")
      (is (= (:lowering call-005) (:lowering d)) "and it is interpreted")
      (is (= (:children-policy call-005) (:children-policy d))
          "it accepts no children — the value is the content")
      (is (nil? (v/manifest v/markup))
          "an interpreted declaration has no analysis to report"))))

(deftest markup-renders-the-value
  (testing "Per FH-CALL-005: every shape a view body may return crosses —
            a Hiccup vector, a seq of them, text and numbers, and
            nothing. Each is rendered through a COMPILED parent, which is
            the only situation in which the boundary earns its keep."
    (is (seq (:cases call-005)) "the fixture's case table loaded")
    (doseq [{:keys [note value tree]} (:cases call-005)]
      (is (= tree (blind {(view-id cross/markup-host) :fh/parent}
                         (tree/render [cross/markup-host {:value value}])))
          note))))

(deftest the-markup-child-owns-its-own-boundary
  (testing "A boundary, not an inlining. The child is a real node with
            its own view id, its own recorded props and its own
            expansion beneath it — which is what 'the child owns its own
            occurrence' means in the structural render, and what the
            ViewCell will own once the reactive shell lands."
    (let [value  [:p.hint "type a name"]
          t      (tree/render [cross/markup-host {:value value}])
          host   (first (:children t))
          child  (first (boundaries (:view-id call-005) t))]
      (is (= :section (:tag host)) "the compiled parent owns its own element")
      (is (some? child) "the crossing is a node in the tree, addressable by view id")
      (is (= {:value value} (:props child))
          "the child records the props IT was called with, not the parent's")
      (is (= [{:tag :p :attrs {:class "hint"} :children ["type a name"]}]
             (:children child))
          "and the walked value hangs beneath the child, not beside it")
      (is (= [child] (:children host))
          "the parent's element holds the boundary, never the expansion directly"))))

;; ---------------------------------------------------------------------------
;; The manifest marks the crossing
;; ---------------------------------------------------------------------------

(deftest the-manifest-marks-each-crossing-with-the-mode-it-crosses-into
  (testing "A compiled declaration says where it stops being compiled.
            Two crossings in one body, into two different modes: a
            manifest that marked by position, or by the parent's own
            lowering, or not at all, would pass a single-child body and
            fails this one."
    (let [m (v/manifest cross/mixed-crossings)]
      (is (= (:view-id (v/describe cross/mixed-crossings)) (:view-id m)))
      (is (= :re-frame.freehand/v1 (:grammar m))
          "the manifest names the grammar that produced it")
      (is (= [{:view-id (view-id compiled/row) :lowering :compiled}
              {:view-id (:view-id call-005)    :lowering :interpreted}]
             (mapv #(dissoc % :path) (:crossings m)))
          "both crossings, in source order, each marked with the mode it enters")
      (is (= 2 (count (distinct (map :path (:crossings m)))))
          "and the two sites are separately addressable"))))

(deftest an-interpreted-declaration-reports-no-manifest
  (testing "Nil is the honest answer, not an omission. The interpreted
            mode has no finite grammar and no analysis step, so an empty
            manifest would be a claim it has not earned."
    (doseq [[view-name interpreted] views/by-name]
      (is (nil? (v/manifest interpreted)) (str view-name " reports no manifest")))
    (doseq [[view-name promoted] compiled/by-name]
      (is (some? (v/manifest promoted)) (str view-name " carries one")))))

(deftest one-interp-slot-mounts-once-per-row
  (testing "Sites and mounts are different quantities. The manifest names
            SITES — one lexical crossing, however many rows it renders —
            and the evidence column counts MOUNTS. Conflating them is how
            a view with one visible escape gets reported as one interpreted
            render.

            The mount count here is read off the structural tree. The
            occurrence evidence record that will carry it under
            `:interp-slots` lands with the ViewCell slice; this asserts
            the quantity it will count, from the render that produces it."
    (let [{:keys [rows interp-slots mounts]} (:mount-count call-005)
          m     (v/manifest cross/markup-rows)
          slots (filterv #(= :interpreted (:lowering %)) (:crossings m))
          t     (tree/render [cross/markup-rows {:rows rows}])]
      (is (= interp-slots (count slots))
          "one interpreted crossing site in the manifest")
      (is (= mounts (count (boundaries (:view-id call-005) t)))
          "one mount per row in the render")
      (is (not= interp-slots mounts)
          "the fixture is not a case where the two quantities coincide"))))
