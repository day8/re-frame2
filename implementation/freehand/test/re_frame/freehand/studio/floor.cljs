;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd. Deleted before the PR.
;;
;; The REACT FLOOR — the same two DOM trees W1 and W2 produce, built by hand
;; with `react/createElement` and no Freehand at all: no boundary, no
;; ViewCell, no shell, no props map, no attribute walk, no conversion table.
;;
;; It is not an arm anybody could ship — it has no state, no events and no
;; identity — and that is the point. It is the irreducible cost of asking
;; React to build this page, so `interpreted - floor` and `compiled - floor`
;; are the substrate's own overheads rather than two numbers dominated by a
;; reconciliation both arms pay identically.
;;
;; The markup is transcribed from `witnesses-interpreted` by hand, in React's
;; canonical prop spelling — which is exactly what the interpreted walk
;; derives at runtime and the compiler derives at build time. The probe gates
;; the floor's `innerHTML` against the interpreted arm's, so a transcription
;; that drifted would fail rather than flatter the floor.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.floor
  (:require ["react" :as react]))

(def ^:private avatar
  "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==")

(def ^:private row-style #js {:paddingLeft "4px" :color "rebeccapurple"})

(defn- w1-row
  "A plain React function component per row — same component-per-row shape
  the Freehand arms have, so React's fiber count matches."
  [^js props]
  (let [i (.-i props)]
    (react/createElement
      "li" #js {:className "w1row cell wide" :style row-style
                :data-index i :title "row"}
      (react/createElement "img" #js {:className "avatar" :src avatar :alt ""})
      (react/createElement "span" #js {:className "label"} "row ")
      (react/createElement "em" #js {:className "badge"} (str i)))))

(defn w1
  [rows]
  (react/createElement
    "section" #js {:className "w1" :aria-label "large template"}
    (react/createElement "h1" #js {:className "title"} "Large")
    (react/createElement "hr" #js {:className "rule"})
    (.apply react/createElement nil
            (.concat #js ["ul" #js {:className "rows" :id "w1list" :role "list"}]
                     (let [a (array)]
                       (dotimes [i rows]
                         (.push a (react/createElement w1-row #js {:key i :i i})))
                       a)))))

(defn- w2-leaf
  [^js props]
  (let [i (.-i props)]
    (react/createElement "span" #js {:className "w2leaf" :data-i i} "leaf")))

(defn w2
  [free]
  (.apply react/createElement nil
          (.concat #js ["ul" #js {:className "free"}]
                   (let [a (array)]
                     (dotimes [i free]
                       (.push a (react/createElement w2-leaf #js {:key i :i i})))
                     a))))
