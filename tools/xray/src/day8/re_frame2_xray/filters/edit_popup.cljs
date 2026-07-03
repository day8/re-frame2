(ns day8.re-frame2-xray.filters.edit-popup
  "Edit popup for IN/OUT filter pills.

  Per `tools/xray/spec/018-Event-Spine.md` §7 'Click-pill → edit
  popup' the popup is a modal overlay with (Add-filter copy shown;
  the title flips to 'Edit filter' on the :pill source and 'Add
  filter for this event' on the :context source):

      ┌─ Filter events ─────────────────────────┐
      │ Action                                  │
      │   (•) Show only matching events         │
      │   ( ) Hide matching events              │
      │                                         │
      │ Match events containing                 │
      │   [ :auth/*, :mouse-move, /login ]      │
      │   Matches keywords, namespaces, globs,  │
      │   or text.                              │
      │   Examples: :auth/*, :auth, :mouse-move,│
      │   /login                                │
      │                                         │
      │ ───────────────────────────────────────  │
      │              [Cancel]    [Add filter]   │
      └─────────────────────────────────────────┘

  ## Scope

  The matcher (`filters/matcher.cljc`) operates on event-id only —
  event-id is the implicit, only scope. The dialog creates exactly
  one pill shape: `{:pattern <kw-or-str>}` keyed off the event-bundle's
  event-id. (Typed-predicate kinds — machine / http-correlation / fx
  — arrive via right-click affordances on other panels, not this
  dialog; see `filters/typed_predicates.cljc`.)

  ## Trigger sources (spec/018 §7)

  Three paths open the popup:

  1. Click an existing pill → `{:source :pill :mode … :idx … :pill …}`
     — popup pre-populated; `[Delete]` button visible.
  2. Click the trailing `[ + ]` add-pill → `{:source :add :mode :in}`
     — popup empty + IN default; no `[Delete]`.
  3. Right-click an event row → `{:source :context :mode :out
     :pill {:pattern <event-id>}}` — popup pre-populated with the
     event-id + OUT default; no `[Delete]`.

  The view reads the trigger payload from `:rf.xray/edit-popup-pill`
  and the draft from `:rf.xray/edit-popup-draft` (a working copy the
  user mutates without affecting the live filter slot)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.theme.modal-chrome :as modal-chrome]
            [day8.re-frame2-xray.theme.tokens
             :refer [tokens type-scale sans-stack mono-stack]]))

;; ---- styles --------------------------------------------------------------
;;
;; Backdrop honours `:rf.xray/modal-positioning`. `:fixed`
;; (production default) keeps the full-viewport overlay with max-int
;; z-index (one above the palette so an edit popup opened over an
;; open palette wins focus — palette is z 2147483646).
;; `:absolute` (Story testbeds) confines the backdrop to the shell
;; cell and drops the z-index to a sane in-cell layer.

(defn- backdrop-style [positioning]
  (let [absolute? (= positioning :absolute)]
    {:position         (if absolute? "absolute" "fixed")
     :top              0
     :left             0
     :right            0
     :bottom           0
     :background       "rgba(0,0,0,0.55)"
     :backdrop-filter  "blur(2px)"
     :display          "flex"
     :align-items      "flex-start"
     :justify-content  "center"
     :padding-top      (if absolute? "8%" "12vh")
     :z-index          (if absolute? 101 2147483647)}))

(defn- dialog-style []
  {:width            "420px"
   :max-width        "92vw"
   :display          "flex"
   :flex-direction   "column"
   :background       (:bg-1 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "8px"
   :box-shadow       "rgba(0,0,0,0.6) 0 24px 64px"
   :overflow         "hidden"
   :font-family      sans-stack
   :color            (:text-primary tokens)})

(defn- header-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "10px 16px"
   :border-bottom    (str "1px solid " (:border-subtle tokens))
   :background       (:bg-2 tokens)
   :font-weight      600})

(defn- section-style []
  {:padding          "12px 16px"
   :border-bottom    (str "1px solid " (:border-subtle tokens))})

(defn- label-style []
  {:display          "block"
   :margin-bottom    "6px"
   :color            (:text-secondary tokens)
   :font-size        (:caption type-scale)
   :font-weight      600
   :letter-spacing   "0.04em"
   :text-transform   "uppercase"})

(defn- input-style []
  {:width            "100%"
   :background       (:bg-2 tokens)
   :border           (str "1px solid " (:border-default tokens))
   :border-radius    "4px"
   :color            (:text-primary tokens)
   :padding          "6px 8px"
   :font-family      mono-stack
   :font-size        (:body type-scale)})

(defn- radio-row-style []
  {:display          "flex"
   :align-items      "center"
   :gap              "16px"})

(defn- footer-style []
  {:display          "flex"
   :align-items      "center"
   :justify-content  "space-between"
   :padding          "10px 16px"
   :background       (:bg-2 tokens)})

(defn- btn-style [{:keys [primary? danger?]}]
  {:background    (cond
                    primary? (:accent tokens)
                    :else    "transparent")
   :border        (str "1px solid "
                       (cond
                         danger?  (:red tokens)
                         primary? (:accent tokens)
                         :else    (:border-default tokens)))
   :color         (cond
                    primary? (:bg-0 tokens)      ; readable on the accent
                    danger?  (:red tokens)
                    :else    (:text-primary tokens))
   :padding       "6px 14px"
   :border-radius "4px"
   :cursor        "pointer"
   :font-family   sans-stack
   :font-size     (:body type-scale)
   :font-weight   (if primary? 600 400)})

;; ---- helpers (pure) ------------------------------------------------------

(defn draft->pill
  "Project the user-edited `draft` into the canonical pill record the
  registry slot expects. The canonical shape is `{:pattern <str-or-kw>}`
  — the matcher consults `:pattern` only; event-id is the implicit,
  only scope.

  String patterns starting with `:` are normalised to keywords so the
  stored shape matches what spec/018 §7 catalogues (`:auth/*`,
  `:order/cart/*`). Whitespace-only / blank patterns survive as nil —
  the registry's save handler drops the pill rather than persisting
  an unmatchable one."
  [{:keys [pattern]}]
  (let [trimmed (when (string? pattern)
                  (clojure.string/trim pattern))
        p       (cond
                  (keyword? pattern)         pattern
                  (and trimmed (seq trimmed))
                  (if (clojure.string/starts-with? trimmed ":")
                    (try
                      (keyword (subs trimmed 1))
                      (catch :default _ trimmed))
                    trimmed)
                  :else                      nil)]
    {:pattern p}))

(defn pill->draft
  "Hydrate a stored pill into the user-editable draft shape. nil pill
  → empty draft."
  [{:keys [pattern] :as _pill}]
  {:pattern (cond
              (nil? pattern)         ""
              (keyword? pattern)     (str pattern)
              :else                  (str pattern))})

;; ---- view ----------------------------------------------------------------

(defn- mode-radio
  [dispatch {:keys [mode current-mode]}]
  (let [tone   (case mode :in (:green tokens) :out (:magenta tokens))
        on?    (= mode current-mode)]
    [:label {:data-testid (str "rf-xray-edit-popup-mode-" (name mode))
             :style {:display       "inline-flex"
                     :align-items   "center"
                     :gap           "6px"
                     :cursor        "pointer"
                     :color         (if on? tone (:text-secondary tokens))
                     :font-family   sans-stack
                     :font-size     (:body type-scale)}}
     [:input {:type      "radio"
              :name      "rf-xray-edit-popup-mode"
              :value     (name mode)
              :checked   on?
              :on-change #(dispatch
                            [:rf.xray/edit-popup-set-mode mode])}]
     (case mode :in "Show only matching events" :out "Hide matching events")]))

(defn popup-view
  "The popup body. Caller (`filters/Modal`) gates the mount on
  `:rf.xray/edit-popup-open?`.

  `dispatch` is the frame-aware dispatcher injected by the
  `filters/Modal` `reg-view` body — every deferred handler routes
  through it so the edit lands on the surrounding instance frame, not
  a `{:frame :rf/xray}` literal."
  [dispatch]
  (let [trigger     @(rf/subscribe [:rf.xray/edit-popup-trigger])
        draft       @(rf/subscribe [:rf.xray/edit-popup-draft])
        positioning @(rf/subscribe [:rf.xray/modal-positioning])
        mode        (or (:mode draft) :in)
        editing?    (= :pill (:source trigger))
        title       (case (:source trigger)
                      :pill    "Edit filter"
                      :context "Add filter for this event"
                      "Filter events")
        ;; Apply is enabled when the pattern is non-blank.
        can-apply?  (let [p (:pattern draft)]
                      (and (string? p) (seq (clojure.string/trim p))))]
    ;; Shared backdrop + dialog scaffold. Keeps this popup's
    ;; own `backdrop-style` / `dialog-style` and its `tab-index "-1"`
    ;; dialog root. It carries NO backdrop/dialog keydown handler — Esc
    ;; is handled inside the pattern input's own `:on-key-down` (the
    ;; `:auto-focus`ed field owns key handling), so `modal-chrome` only
    ;; wires the positioning attribute, the click-outside dismiss,
    ;; `a11y/dialog-attrs` + the `a11y/dialog-ref` focus trap (which
    ;; respects the input's pre-existing focus on mount — no double
    ;; focus).
    (modal-chrome/modal-chrome
      {:positioning      positioning
       :backdrop-style   (backdrop-style positioning)
       :dialog-style     (dialog-style)
       :on-dismiss       #(dispatch [:rf.xray/close-edit-popup])
       :labelled-by      "rf-xray-edit-popup-title"
       :backdrop-testid  "rf-xray-edit-popup-backdrop"
       :dialog-testid    "rf-xray-edit-popup-dialog"
       :dialog-tab-index "-1"}
      [:div {:style (header-style)}
       [:span {:id "rf-xray-edit-popup-title"} title]
       [:button {:data-testid "rf-xray-edit-popup-close"
                 :aria-label  "Close filter editor"
                 :on-click    #(dispatch
                                 [:rf.xray/close-edit-popup])
                 :title       "Close (Esc)"
                 :style {:background  "transparent"
                         :border      "none"
                         :color       (:text-secondary tokens)
                         :cursor      "pointer"
                         :font-size   "16px"}}
        "✕"]]

      [:div {:style (section-style)}
       [:label {:style (label-style)} "Action"]
       [:div {:style (radio-row-style)}
        [mode-radio dispatch {:mode :in :current-mode mode}]
        [mode-radio dispatch {:mode :out :current-mode mode}]]]

      [:div {:style (section-style)}
       [:label {:style (label-style) :for "rf-xray-edit-popup-pattern"}
        "Match events containing"]
       [:input {:data-testid  "rf-xray-edit-popup-pattern"
                :id           "rf-xray-edit-popup-pattern"
                :type         "text"
                :auto-focus   true
                :value        (or (:pattern draft) "")
                :placeholder  ":auth/*, :mouse-move, /login"
                :on-change    #(dispatch
                                 [:rf.xray/edit-popup-set-pattern
                                  (.. % -target -value)])
                :on-key-down  (fn [^js e]
                                (case (.-key e)
                                  "Enter"  (when can-apply?
                                             (.preventDefault e)
                                             (dispatch
                                               [:rf.xray/save-edit-popup]))
                                  "Escape" (do (.preventDefault e)
                                               (dispatch
                                                 [:rf.xray/close-edit-popup]))
                                  nil))
                :style        (input-style)}]
       [:div {:style {:margin-top "4px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size (:caption type-scale)
                      :line-height 1.5}}
        [:div "Matches keywords, namespaces, globs, or text."]
        [:div "Examples: :auth/*, :auth, :mouse-move, /login"]]]

      [:div {:style (footer-style)}
       (if editing?
         [:button {:data-testid "rf-xray-edit-popup-delete"
                   :on-click    #(dispatch
                                   [:rf.xray/delete-edit-popup])
                   :style       (btn-style {:danger? true})}
          "Delete"]
         [:span])
       [:div {:style {:display "flex" :gap "8px"}}
        [:button {:data-testid "rf-xray-edit-popup-cancel"
                  :on-click    #(dispatch
                                  [:rf.xray/close-edit-popup])
                  :style       (btn-style {})}
         "Cancel"]
        [:button {:data-testid "rf-xray-edit-popup-save"
                  :disabled    (not can-apply?)
                  :on-click    #(when can-apply?
                                  (dispatch
                                    [:rf.xray/save-edit-popup]))
                  :style       (merge (btn-style {:primary? true})
                                      (when-not can-apply?
                                        {:opacity 0.4 :cursor "default"}))}
         (if editing? "Apply" "Add filter")]]])))
