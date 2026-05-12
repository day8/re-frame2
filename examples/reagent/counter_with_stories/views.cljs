(ns counter-with-stories.views
  "Counter views. Three forms of the same component, one
  Form-2 button row, and a parity badge. The stories namespace
  references these by view-id keyword — Story renders by id, not
  by symbol, so the views are namespaced via `reg-view`.

  Per spec/004 §reg-view, the macro auto-injects `dispatch` and
  `subscribe` as lexical bindings; both resolve at render time to
  the surrounding frame. In the live app that's `:rf/default`; in
  the Story playground each variant runs in its own dedicated
  frame allocated by `run-variant` (spec/002), so the same view
  code is automatically frame-scoped — no view-level changes
  needed to make it Story-friendly.

  This is the design payoff: the views written for the live app
  are exactly the views the Story playground exercises. No
  parallel `<Counter.story.tsx>` file; no two sources of truth."
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.views])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

;; ---- Form-1: pure render -------------------------------------------------
;;
;; A label that renders whatever `:label` arg comes in. The Story
;; playground's three-level args resolution feeds the variant body's
;; `:args` here; you can scrub the label from the controls panel.

(reg-view counter-label [{:keys [label]}]
  [:div {:style {:font-size "14px" :color "#666"}}
   (or label "Count")])

;; ---- Form-2: local state ------------------------------------------------
;;
;; A button group with a small piece of local state — whether the
;; hover-hint is visible. Form-2 components return a render-fn that
;; closes over a Reagent ratom; the surrounding frame is still resolved
;; at the render-fn site. Story handles this shape transparently —
;; local component state lives outside `app-db`, so assertion vocab
;; against the app-db doesn't see it, which is the right separation.
;;
;; The hint text deliberately announces what it's demonstrating so
;; readers inspecting the playground see the pedagogy.

(reg-view counter-buttons []
  (let [hint? (r/atom false)]
    (fn []
      [:div
       ;; aria-label gives the "-" glyph an accessible name (rf2-4763); axe-core's
       ;; `button-name` rule passes on visible text, but the glyph alone reads
       ;; literally as "minus" to a screen reader — `aria-label` provides intent.
       [:button {:on-mouse-enter #(reset! hint? true)
                 :on-mouse-leave #(reset! hint? false)
                 :on-click       #(dispatch [:counter/dec])
                 :aria-label     "Decrement count"
                 :data-test      "dec"}
        "-"]
       [:span {:style {:margin "0 1em" :min-width "2em" :display "inline-block" :text-align "center"}
               :data-test "count"}
        @(subscribe [:count])]
       [:button {:on-click   #(dispatch [:counter/inc])
                 :aria-label "Increment count"
                 :data-test  "inc"}
        "+"]
       ;; Hint span always renders; toggling visibility (not unmount) reserves
       ;; layout space so the card width stays stable on hover (rf2-bnn7).
       ;; Colour darkened from #888 (3.54:1) to #595959 (7.0:1) to clear WCAG AA
       ;; contrast for normal text — rf2-4763.
       [:span {:style {:margin-left "1em" :font-size "11px" :color "#595959"
                       :visibility (if @hint? "visible" "hidden")}}
        "(demo: hint state is component-local, not in app-db)"]])))

;; ---- Form-3: with-let parity badge --------------------------------------
;;
;; A derived parity badge — reads the `:count-parity` sub, paints
;; green for `:even`, amber for `:odd`. Form-3 (`r/with-let`) is the
;; idiomatic shape when a component needs mount / unmount side
;; effects but the bulk of the render is data-driven.

(reg-view parity-badge []
  (r/with-let [_ nil]
    (let [p @(subscribe [:count-parity])]
      [:span {:style {:padding         "2px 8px"
                      :border-radius   "10px"
                      :font-size       "11px"
                      :background      (case p :even "#dff6dd" :odd "#fff4ce")
                      :color           (case p :even "#107c10" :odd "#7e5b00")
                      :margin-left     "1em"}
              :data-test "parity"}
       (case p :even "even" :odd "odd")])))

;; ---- Composed counter card ----------------------------------------------
;;
;; The whole-app shape — labels + buttons + parity. The live app
;; renders this; the `:story.counter/composed` variant renders this
;; exact view in its own frame.

(reg-view counter-card [{:keys [label]}]
  [:div {:style {:padding         "1em 1.5em"
                 :border          "1px solid #ddd"
                 :border-radius   "6px"
                 :background      "#fff"
                 :display         "inline-flex"
                 :flex-direction  "column"
                 :gap             "0.5em"
                 :font-family     "system-ui, sans-serif"}}
   [counter-label {:label label}]
   [:div {:style {:display "flex" :align-items "center"}}
    [counter-buttons]
    [parity-badge]]])
