;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd's ELISION ABLATION. Deleted before the PR; see
;; docs/design/freehand/studio/compiled-tier-browser-worth-it.md §1a.
;;
;; The two leaves and their same-tier list, COMPILED — `witnesses-interpreted`
;; PROMOTED. Every declaration below is its twin with `{:compiled true}` added
;; and nothing else changed.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.witnesses-compiled
  (:require [re-frame.freehand :as v]))

(v/defview leaf-free
  "A sub-free leaf. Nothing in this body reads state, so the compiled tier
  can PROVE it needs no ViewCell; the interpreted tier cannot prove
  anything and keeps its shell."
  {:compiled true}
  [{:keys [i]}]
  [:span.leaf {:data-i i} "leaf"])

(v/defview leaf-read
  "A leaf that DOES read state — the same element, the same attribute, one
  text child, plus one subscription and the `str` around it. Reactive in
  both modes, so its shell is kept in both."
  {:compiled true}
  [{:keys [i]}]
  [:span.leaf {:data-i i} (str (v/sub [:studio/tick]))])

(v/defview list-free
  "N sub-free boundaries under one root."
  {:compiled true}
  [{:keys [n]}]
  [:ul.leaves
   (for [i (range n)]
     [leaf-free {:key i :i i}])])

(v/defview list-read
  "N reactive boundaries under one root — the SAME n."
  {:compiled true}
  [{:keys [n]}]
  [:ul.leaves
   (for [i (range n)]
     [leaf-read {:key i :i i}])])
