(ns re-frame.bench.p0-reagent
  "EP-0038 P0 — the **denominator**: Reagent reading re-frame2
  subscriptions.

  The ship bar (HD-012) is stated as a ratio to Reagent, so this arm is
  what every P0 figure is divided by. It has to be the Reagent an
  application would actually write, on the same sub graph as the UIx arm,
  or the bar is set against a straw man and the red-zones derived from it
  are worthless.

  ## Why every subscribing boundary is a `reg-view`

  A plain Reagent fn does not carry the `:contextType` static-field wiring
  Reagent needs to read the frame context, so its render-time `subscribe`
  resolves no frame and raises `:rf.error/no-frame-context` (Spec 002
  §Reading the frame from React context). `reg-view` is the paved Reagent
  spelling for a frame-scoped view and it injects a lexical `subscribe`
  bound to the surrounding frame — the exact counterpart of the UIx arm's
  `use-subscribe`. Neither arm is doing the other's work, and neither is
  hand-optimised.

  ## Why NOT a `reagent.core/atom` or a `cursor`

  The predecessor's cross-substrate row put Freehand on re-frame `app-db`
  against Reagent on a bare `r/atom`, and said in its own docstring that
  this was not the same amount of framework. It is exactly the asymmetry
  P0 exists to remove: a bare ratom skips the re-frame write, the event
  pipeline and the signal graph, so it is a LOWER BOUND on Reagent and not
  a fair denominator. Everything here goes through `re-frame.core`.

  Owner: the operator-owned governance set that superseded rf2-2rtt6.1 on
  2026-08-10, enumerated once in `docs/design/hicasso/studio/README.md`;
  this arm rf2-2rtt6.4."
  (:require [re-frame.bench.p0-fixture :as fx]
            [re-frame.core :as rf])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ---------------------------------------------------------------------------
;; W1 — the large template
;; ---------------------------------------------------------------------------

(reg-view w1-row [i]
  (let [text @(subscribe [:p0/row i])]
    [:li.row.cell.wide {:style      {:padding-left "4px" :color "rebeccapurple"}
                        :data-index i}
     [:img.avatar {:src "/avatar.png" :alt ""}]
     [:span.label "row "]
     [:em.n text]]))

(reg-view w1 [rows]
  [:section.panel {:aria-label "bench rows"}
   [:h1.title "Rows"]
   [:ul.rows {:role "list"}
    (for [i (range rows)]
      ^{:key i} [w1-row i])]])

;; ---------------------------------------------------------------------------
;; W3 — the ordinary form
;; ---------------------------------------------------------------------------

(reg-view w3-field [i]
  (let [{:keys [value error]} @(subscribe [:p0/field i])]
    [:div.field
     [:label.lbl {:for (str "f" i)} (str "Field " i)]
     [:input.inp {:id        (str "f" i)
                  :name      (str "f" i)
                  :type      "text"
                  :value     value
                  :read-only true}]
     [:p.err error]]))

(reg-view w3 [fields]
  [:form.w3form
   [:fieldset.fields
    (for [i (range fields)]
      ^{:key i} [w3-field i])]
   [:button.submit {:type "submit" :disabled true} "Submit"]])

;; ---------------------------------------------------------------------------
;; U — the bulk witness
;; ---------------------------------------------------------------------------

(reg-view u-cell [i]
  (let [v @(subscribe [:p0/cell i])]
    [:span.cell {:data-i i} (str v)]))

(reg-view u-grid [n]
  [:div.ugrid
   (for [i (range n)]
     ^{:key i} [u-cell i])])

;; ---------------------------------------------------------------------------
;; FAN — the fan-out family (rf2-5prok)
;; ---------------------------------------------------------------------------
;;
;; The same `.cell` boundary as `u-cell`, with two things lifted into
;; parameters: how many subscriptions a boundary READS (`reads`), and how
;; many UNIQUE query keys the whole page holds (Q, set on the fixture
;; before the mount). Boundary count, element count and text are held
;; fixed across every rung — all cells are `0`, and a two-read cell shows
;; their SUM, which is also `0` — so a rung differs from its neighbours in
;; cache cardinality and in nothing a floor subtraction could confuse for
;; it.
;;
;; THREE CELLS RATHER THAN ONE WITH A LOOP. A boundary's read count has to
;; be fixed per component: a `for` over `reads` inside one cell would be a
;; different question on Reagent and a hook-linter error on the UIx
;; counterpart, and the two arms have to be written the same way.
;;
;; The `_n` arg on the zero-read cell is deliberate: every rung's cell
;; takes the SAME two arguments, so the argv a fiber retains is the same
;; shape at R=0, R=1 and R=2 and the shell rung is not measuring one fewer
;; prop slot than the rungs it anchors.

(reg-view fan-cell-0 [j _n]
  [:span.cell {:data-i j} "0"])

(reg-view fan-cell-1 [j n]
  (let [a @(subscribe [:p0/fan (fx/fan-key n 1 0)])]
    [:span.cell {:data-i j} (str a)]))

(reg-view fan-cell-2 [j n]
  (let [a @(subscribe [:p0/fan (fx/fan-key n 2 0)])
        b @(subscribe [:p0/fan (fx/fan-key n 2 1)])]
    [:span.cell {:data-i j} (str (+ a b))]))

(reg-view fan-grid [offset n-cells reads]
  [:div.ugrid
   (case (long reads)
     0 (for [j (range n-cells)] ^{:key j} [fan-cell-0 j (+ offset j)])
     1 (for [j (range n-cells)] ^{:key j} [fan-cell-1 j (+ offset j)])
     2 (for [j (range n-cells)] ^{:key j} [fan-cell-2 j (+ offset j)]))])

;; ---------------------------------------------------------------------------
;; LAD — the reads-per-boundary ladder (rf2-2rtt6.34)
;; ---------------------------------------------------------------------------
;;
;; The same `.cell` boundary again, at 1/3/7/20 reads, every read a
;; DISTINCT query (Q = E). It is the fan family's `reads` axis carried out
;; to the rungs HD-002 mandates, on the instrument the per-read gates are
;; stated on — the fan family stops at two reads because two is all a
;; per-edge contrast needs.
;;
;; A LOOP rather than an unrolled body, and the asymmetry with the UIx arm
;; below is in the substrates rather than in the measurement: Reagent has
;; no hook-order rule, so a form-1 component may deref a computed number of
;; reactions and Reagent's `deref`-capture learns all of them. That is how
;; an application would write it. `reads-ladder`'s Reagent arm makes the
;; same choice for the same reason.

(reg-view lad-cell [j n r]
  (let [v (loop [k 0 acc 0]
            (if (< k r)
              (recur (inc k) (+ acc @(subscribe [:p0/fan (fx/fan-key n r k)])))
              acc))]
    [:span.cell {:data-i j} (str v)]))

(reg-view lad-grid [offset n-cells reads]
  [:div.ugrid
   (for [j (range n-cells)]
     ^{:key j} [lad-cell j (+ offset j) reads])])

;; ---------------------------------------------------------------------------
;; Roots
;; ---------------------------------------------------------------------------

(defn lad-root
  "One root of the ladder arm. `offset` is this root's base in the GLOBAL
  boundary numbering, exactly as [[fan-root]]'s is — at Q = E it is what
  keeps the roots' key spaces disjoint."
  [frame-id offset n-cells reads]
  [rf/frame-provider {:frame frame-id} [lad-grid offset n-cells reads]])

(defn fan-root
  "One root of the fan-out arm. `offset` is this root's base in the GLOBAL
  boundary numbering, which is what lets four roots of one frame either
  share every key or share none."
  [frame-id offset n-cells reads]
  [rf/frame-provider {:frame frame-id} [fan-grid offset n-cells reads]])

(defn w1-root [frame-id rows]
  [rf/frame-provider {:frame frame-id} [w1 rows]])

(defn w3-root [frame-id fields]
  [rf/frame-provider {:frame frame-id} [w3 fields]])

(defn u-root [frame-id]
  [rf/frame-provider {:frame frame-id} [u-grid fx/cells-n]])
