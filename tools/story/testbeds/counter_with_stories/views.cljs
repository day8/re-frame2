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
  (:require-macros [re-frame.core :refer [reg-view]]))

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
       ;; aria-label gives the "-" glyph an accessible name; axe-core's
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
       ;; layout space so the card width stays stable on hover.
       ;; Colour darkened from #888 (3.54:1) to #595959 (7.0:1) to clear WCAG AA
       ;; contrast for normal text.
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
    (let [p @(subscribe [:count-parity])
          badge (case p
                  :even {:bg "#dff6dd" :fg "#107c10" :text "even"}
                  :odd  {:bg "#fff4ce" :fg "#7e5b00" :text "odd"}
                  ;; Story chrome can render this panel before the
                  ;; selected variant frame has replayed its seed events.
                  {:bg "#f3f2f1" :fg "#605e5c" :text "pending"})]
      [:span {:style {:padding         "2px 8px"
                      :border-radius   "10px"
                      :font-size       "11px"
                      :background      (:bg badge)
                      :color           (:fg badge)
                      :margin-left     "1em"}
              :data-test "parity"}
       (:text badge)])))

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

(reg-view recorder-redaction-card [{:keys [label]}]
  [:div {:style {:display "inline-flex"
                 :flex-direction "column"
                 :gap "0.75em"}}
   [counter-card {:label label}]
   [:button {:on-click   #(dispatch [:auth/sign-in
                                      {:email "redaction@example.com"
                                       :password "browser-secret"}])
             :aria-label "Dispatch sensitive sign-in event"
             :data-test  "story-recorder-sensitive-action"}
    "Sensitive sign in"]])

(reg-view a11y-known-good-card [_args]
  [:div {:style {:display "inline-flex"
                 :flex-direction "column"
                 :gap "0.5em"}
         :data-test "a11y-known-good-fixture"}
   [:h2 {:style {:margin 0 :font-size "18px"}} "Accessible fixture"]
   [:button {:type "button"
             :aria-label "Accessible fixture action"
             :data-test "a11y-known-good-action"}
    "Run accessible action"]])

(reg-view a11y-known-bad-card [_args]
  [:div {:style {:display "inline-flex"
                 :flex-direction "column"
                 :gap "0.5em"}
         :data-test "a11y-known-bad-fixture"}
   [:h2 {:style {:margin 0 :font-size "18px"}} "Inaccessible fixture"]
   [:img {:src "data:image/gif;base64,R0lGODlhAQABAAAAACw="
          :data-test "a11y-known-bad-image"}]
   [:button {:type "button"
             :data-test "a11y-known-bad-action"}
    "Unlabelled image above"]])

;; ---- counter-with-input — DOM-step play-script fixture (rf2-e0kof) -----
;;
;; A minimal counter variant whose user surface exposes the three DOM
;; affordances the rich-DSL `:click` / `:type` / `:assert-dom` steps
;; reach for:
;;
;;   - [data-test=count-input]   — text input the `:type` step writes into
;;   - [data-test=set-button]    — button the `:click` step dispatches
;;   - [data-test=count-display] — span the `:assert-dom :text` step reads
;;
;; The component owns local state for the input value (Reagent ratom);
;; the button click commits it to app-db via `:counter/set`. Picking a
;; distinct `[data-test=count-display]` selector (rather than
;; `[data-test=count]` from counter-buttons) avoids cross-component
;; selector collisions if a future variant composes both surfaces.
;;
;; The CI runner at examples/scripts/serve-and-run-story-play-scripts.cjs
;; navigates to the matching variant and asserts the auto-run reaches
;; `:pass` (or `:fail` for the expected-fail twin).

(reg-view counter-with-input [{:keys [label]}]
  [:div {:style {:padding         "1em 1.5em"
                 :border          "1px solid #ddd"
                 :border-radius   "6px"
                 :background      "#fff"
                 :display         "inline-flex"
                 :flex-direction  "column"
                 :gap             "0.5em"
                 :font-family     "system-ui, sans-serif"}}
   [:div {:style {:font-size "14px" :color "#666"}}
    (or label "Counter (input)")]
   [:div {:style {:display "flex" :align-items "center" :gap "0.5em"}}
    [:span {:data-test "count-display"
            :style     {:min-width "2em" :text-align "center"
                        :font-weight "bold"}}
     (str @(subscribe [:count]))]
    ;; Uncontrolled input — `defaultValue ""` + no `:value` prop. Reads
    ;; back from the DOM on click. The rich-DSL `:type` step sets
    ;; `.value` directly (re_frame.story.play.dom/type!) and dispatches
    ;; synthetic `input` + `change` events; a controlled input here
    ;; would fight React's value-tracker (the tracker thinks the value
    ;; is unchanged after a raw `.value =` write), so we read the DOM
    ;; directly on click instead. This isolates the DOM-step spec from
    ;; React controlled-input semantics — exactly what we want under
    ;; test, where the step contract is "DOM node mutated; event fired".
    [:input {:type          "text"
             :data-test     "count-input"
             :default-value ""}]
    [:button {:type       "button"
              :data-test  "set-button"
              :aria-label "Set count from input"
              :on-click
              (fn [_]
                (let [node (.querySelector js/document
                             "[data-test=count-input]")
                      raw  (when node (.-value node))
                      n    (try
                             (js/parseInt raw 10)
                             (catch :default _ nil))]
                  (when (and (number? n) (not (js/isNaN n)))
                    (dispatch [:counter/set n]))))}
     "Set"]]])

(reg-view throwing-card [_args]
  [:div {:style {:background "#5a1d1d"
                 :color "#fff"
                 :padding "0.75em"
                 :border "1px solid #f08080"}
         :data-test "story-render-error"}
   [:div "Render threw — Story shell remains interactive."]
   [:div "variant: :story.counter-diagnostics/render-throws"]
   [:div "phase: :phase-3-render"]
   [:div "source: :counter-with-stories.views/throwing-card"]
   [:div "story-load deterministic render failure"]
   [:pre "stack: counter-with-stories.views/throwing-card"]])
