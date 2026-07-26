;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd's ELISION ABLATION. Deleted before the PR; see
;; docs/design/freehand/studio/compiled-tier-browser-worth-it.md §1a.
;;
;; The two leaves and their same-tier list, INTERPRETED. Its twin
;; `re-frame.freehand.studio.witnesses-compiled` is this file with
;; `{:compiled true}` on each declaration and the `-interpreted`/`-compiled`
;; namespace segment renamed, and nothing else changed.
;;
;; The two leaves differ in ONE way and are otherwise the same element with
;; the same attribute and one text child:
;;
;;   leaf-free  reads no state       -> the compiled tier PROVES it elidable
;;   leaf-read  reads one sub        -> neither tier may elide it
;;
;; The COUNT is one parameter shared by both lists, so the two witnesses are
;; never compared across different boundary counts. That is the confound this
;; ablation exists to remove: the first pass measured 300 free boundaries
;; against 100 reactive ones.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.witnesses-interpreted
  (:require [re-frame.freehand :as v]))

(v/defview leaf-free
  "A sub-free leaf. Nothing in this body reads state, so the compiled tier
  can PROVE it needs no ViewCell; the interpreted tier cannot prove
  anything and keeps its shell."
  [{:keys [i]}]
  [:span.leaf {:data-i i} "leaf"])

(v/defview leaf-read
  "A leaf that DOES read state — the same element, the same attribute, one
  text child, plus one subscription and the `str` around it. Reactive in
  both modes, so its shell is kept in both."
  [{:keys [i]}]
  [:span.leaf {:data-i i} (str (v/sub [:studio/tick]))])

(v/defview list-free
  "N sub-free boundaries under one root."
  [{:keys [n]}]
  [:ul.leaves
   (for [i (range n)]
     [leaf-free {:key i :i i}])])

(v/defview list-read
  "N reactive boundaries under one root — the SAME n."
  [{:keys [n]}]
  [:ul.leaves
   (for [i (range n)]
     [leaf-read {:key i :i i}])])
