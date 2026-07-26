;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd's ELISION ABLATION. Deleted before the PR; see
;; docs/design/freehand/studio/compiled-tier-browser-worth-it.md §1a.
;;
;; THE ABLATION ITSELF.
;;
;; The question the first pass could not answer: how much of the compiled
;; tier's advantage on a sub-free witness is the ELIDED VIEWCELL, and how much
;; is compilation of the markup? The first pass compared 300 sub-free
;; boundaries against 100 reactive ones whose leaf also ran a subscription and
;; a `str`, and read the whole difference as elision. Three things varied at
;; once, so none of them was measured.
;;
;; This file varies ONE. `leaf-free-kept` is `witnesses-compiled/leaf-free`'s
;; own descriptor with a single entry changed — the manifest's `:view-cell`
;; verdict flipped from `:elided` to `:present`. Same compiled React body, same
;; props, same 300 boundaries, same DOM (the probe's parity gate proves it).
;; The only difference is the wrapper `react.cljs/compiled-component` selects,
;; which that verdict chooses "and by nothing else". So
;;
;;     kept - elided  =  the cost of 300 ViewCells, and nothing else.
;;
;; Reaching past the public door for this is deliberate and is why the file is
;; scaffolding: an application may not fabricate a descriptor, and there is no
;; supported way to ask for a ViewCell a proof says is unnecessary. That is a
;; measurement, not a feature.
;;
;; The PARENTS below are interpreted, and the interpreted-parent arms share
;; them, so the one boundary above the 300 is a constant that cancels out of
;; every difference. The probe additionally runs the fully-compiled parent+leaf
;; arm — the shape the first pass measured — to price the parent itself.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.ablation
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.descriptor :as descriptor]
            [re-frame.freehand.studio.witnesses-compiled :as wc]))

(def leaf-free-kept
  "`wc/leaf-free` with its ViewCell verdict overridden to `:present`.

  A distinct `:view-id`, so the boundary cache holds this and the elided
  original as two entries and neither evicts the other when the arms
  interleave. Nothing else in the entry is touched: the `:react` body is
  IDENTICAL — the same closure object the elided arm runs."
  (descriptor/->ViewDescriptor
    (-> (.-entry ^re-frame.freehand.descriptor/ViewDescriptor wc/leaf-free)
        (assoc :view-id ::leaf-free-kept)
        (update :manifest assoc :view-cell :present :reactive? true))))

(defn same-body?
  "Published by the probe as a determinism gate: the kept clone must run the
  very same compiled body object as the elided original, or the ablation is
  comparing two programs."
  []
  (identical? (descriptor/react-body wc/leaf-free)
              (descriptor/react-body leaf-free-kept)))

;; --- the shared interpreted parent, one per leaf under test -----------------
;;
;; `witnesses-interpreted/list-free` and `/list-read` are the same parent over
;; the interpreted leaves; these three are it over the compiled ones. Identical
;; markup in all five, so the parent cancels.

(v/defview list-free-c
  "The interpreted parent over the COMPILED sub-free leaf — ViewCell elided."
  [{:keys [n]}]
  [:ul.leaves (for [i (range n)] [wc/leaf-free {:key i :i i}])])

(v/defview list-free-k
  "The interpreted parent over the COMPILED sub-free leaf with its ViewCell
  FORCED KEPT. The ablation arm."
  [{:keys [n]}]
  [:ul.leaves (for [i (range n)] [leaf-free-kept {:key i :i i}])])

(v/defview list-read-c
  "The interpreted parent over the COMPILED reactive leaf."
  [{:keys [n]}]
  [:ul.leaves (for [i (range n)] [wc/leaf-read {:key i :i i}])])
