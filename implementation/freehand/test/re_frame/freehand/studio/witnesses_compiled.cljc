;; ---------------------------------------------------------------------------
;; SCAFFOLDING for rf2-lnecd. Deleted before the PR; see
;; docs/design/freehand/studio/compiled-tier-browser-worth-it.md.
;;
;; The three witnesses, COMPILED — `witnesses-interpreted` PROMOTED. Every
;; declaration below is its twin with `{:compiled true}` added and nothing
;; else changed.
;; ---------------------------------------------------------------------------

(ns re-frame.freehand.studio.witnesses-compiled
  (:require [re-frame.freehand :as v]))

;; --- W1 — the large template ------------------------------------------------

(v/defview w1-row
  "One row of the large template — the attribute shapes the walk treats
  differently, over four children."
  {:compiled true}
  [{:keys [i]}]
  [:li.w1row {:class      ["cell" "wide"]
              :style      {:padding-left 4 :color "rebeccapurple"}
              :data-index i
              :title      "row"}
   [:img.avatar {:src "/a.png" :alt ""}]
   [:span.label "row "]
   [:em.badge i]])

(v/defview w1
  "The large template: one boundary over a fixed skeleton and a keyed run."
  {:compiled true}
  [{:keys [rows]}]
  [:section.w1 {:aria-label "large template"}
   [:h1.title "Large"]
   [:hr.rule]
   [:ul#w1list.rows {:role "list"}
    (for [i (range rows)]
      [w1-row {:key i :i i}])]])

;; --- W2 — many small boundaries, with cell elision --------------------------

(v/defview w2-free
  "A sub-free leaf. Nothing in this body reads state, so the compiled tier
  can PROVE it needs no ViewCell; the interpreted tier cannot prove
  anything and keeps its shell."
  {:compiled true}
  [{:keys [i]}]
  [:span.w2leaf {:data-i i} "leaf"])

(v/defview w2-read
  "A leaf that DOES read state — the control on the elision arm. Reactive
  in both modes, so its shell is kept in both."
  {:compiled true}
  [{:keys [i]}]
  [:span.w2read {:data-i i} (str (v/sub [:studio/tick]))])

(v/defview w2
  "Many small boundaries under one root."
  {:compiled true}
  [{:keys [free reads]}]
  [:div.w2
   [:ul.free
    (for [i (range free)]
      [w2-free {:key i :i i}])]
   [:ul.reads
    (for [i (range reads)]
      [w2-read {:key i :i i}])]])

;; --- W3 — an ordinary form/control ------------------------------------------

(v/defview w3-field
  "One controlled field with its label and its error line — the shape the
  corpus census says is the most common interactive element after buttons."
  {:compiled true}
  [{:keys [idx]}]
  [:div.w3field
   [:label.w3label {:for (str "f-" idx)} "Field"]
   [:input.w3input {:id       (str "f-" idx)
                    :type     "text"
                    :value    (v/sub [:studio/field idx])
                    :on-input [:studio/type idx :re-frame.freehand/value]}]
   [:span.w3error (str (v/sub [:studio/error idx]))]])

(v/defview w3
  "An ordinary form: N controlled fields and a derived submit gate."
  {:compiled true}
  [{:keys [fields]}]
  [:form.w3 {:on-submit [:studio/submit]}
   [:h2.w3title "Details"]
   (for [i (range fields)]
     [w3-field {:key i :idx i}])
   [:button.w3submit {:type     "submit"
                      :disabled (v/sub [:studio/submit-blocked?])}
    "Save"]])
