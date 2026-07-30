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

  Owner: rf2-2rtt6.1 (standard); this arm rf2-2rtt6.4."
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
;; Roots
;; ---------------------------------------------------------------------------

(defn w1-root [frame-id rows]
  [rf/frame-provider {:frame frame-id} [w1 rows]])

(defn w3-root [frame-id fields]
  [rf/frame-provider {:frame frame-id} [w3 fields]])

(defn u-root [frame-id]
  [rf/frame-provider {:frame frame-id} [u-grid fx/cells-n]])
