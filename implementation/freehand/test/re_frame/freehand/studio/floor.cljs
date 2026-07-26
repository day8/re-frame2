;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd's ELISION ABLATION. Deleted before the PR.
;;
;; The REACT FLOOR — the same two DOM trees the free and read witnesses
;; produce, built by hand with `react/createElement` and no Freehand at all:
;; no boundary, no ViewCell, no shell, no props map, no attribute walk, no
;; conversion table.
;;
;; It is not an arm anybody could ship — it has no state, no events and no
;; identity — and that is the point. It is the irreducible cost of asking
;; React to build this page, so every arm's cost can be read as a RATIO to the
;; floor measured in the SAME run, which is what makes readings comparable on
;; a box whose absolute milliseconds drift.
;;
;; THE READ FLOOR IS NOT A FLOOR FOR REACTIVITY, and is not offered as one.
;; A view that reads state cannot have a floor that reads state and still be a
;; floor. What it is: the React cost of the SAME DOM the reactive witness
;; produces at `tick` 0 — the literal string "0" where the witness computes it.
;; So `read-arm - read-floor` is that arm's whole substrate overhead INCLUDING
;; the subscription, which is exactly the quantity the ablation needs in order
;; to price the reactive leaf's extra common work.
;;
;; The markup is transcribed from `witnesses-interpreted` by hand, in React's
;; canonical prop spelling. The probe gates the floor's canonical DOM against
;; every other arm of its witness, so a transcription that drifted would fail
;; rather than flatter the floor.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.floor
  (:require ["react" :as react]))

(defn- leaf-free
  [^js props]
  (react/createElement "span" #js {:className "leaf" :data-i (.-i props)} "leaf"))

(defn- leaf-read
  [^js props]
  (react/createElement "span" #js {:className "leaf" :data-i (.-i props)} "0"))

(defn- listing
  [leaf n]
  (.apply react/createElement nil
          (.concat #js ["ul" #js {:className "leaves"}]
                   (let [a (array)]
                     (dotimes [i n]
                       (.push a (react/createElement leaf #js {:key i :i i})))
                     a))))

(defn free [n] (listing leaf-free n))
(defn reads [n] (listing leaf-read n))
