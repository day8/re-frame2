(ns day8.re-frame2-causa.filters.edit-popup
  "Edit popup for IN/OUT filter pills (rf2-ak4ms).

  Per `tools/causa/spec/018-Event-Spine.md` §7 'Click-pill → edit
  popup' the popup is a modal overlay with:

      ┌─ Edit filter ──────────────────────┐
      │ Mode    ◉ IN   ○ OUT               │
      │                                    │
      │ Pattern                            │
      │   :auth/*                          │
      │   (keyword · glob · namespace)     │
      │                                    │
      │ Match scope                        │
      │   ☑ event-id                       │
      │   ☐ event-args                     │
      │   ☐ source-coord                   │
      │   ☐ tags                           │
      │                                    │
      │ ──────────────────────────────────  │
      │ [Delete]      [Cancel]    [Apply]  │
      └────────────────────────────────────┘

  ## Pre-alpha scope

  Pre-alpha the matcher (`filters/matcher.cljc`) operates on event-id
  only — event-args / source-coord / tags scopes are surfaced in the
  popup so the visual contract ships, but the checkboxes are stored
  as data and consumed later when the matcher widens. The 'event-id'
  scope is the default and always-on; widening the scope is a future
  rev.

  ## Trigger sources (spec/018 §7)

  Three paths open the popup:

  1. Click an existing pill → `{:source :pill :mode … :idx … :pill …}`
     — popup pre-populated; `[Delete]` button visible.
  2. Click the trailing `[ + ]` add-pill → `{:source :add :mode :in}`
     — popup empty + IN default; no `[Delete]`.
  3. Right-click an event row → `{:source :context :mode :out
     :pill {:pattern <event-id>}}` — popup pre-populated with the
     event-id + OUT default; no `[Delete]`.

  The view reads the trigger payload from `:rf.causa/edit-popup-pill`
  and the draft from `:rf.causa/edit-popup-draft` (a working copy the
  user mutates without affecting the live filter slot)."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens type-scale sans-stack mono-stack]]))

;; ---- styles --------------------------------------------------------------
;;
;; Backdrop honours `:rf.causa/modal-positioning` (rf2-om6fa). `:fixed`
;; (production default) keeps the full-viewport overlay with max-int
;; z-index (one above the palette so an edit popup opened over an
;; open palette wins focus — palette is z 2147483646 per rf2-wm7z4).
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
                    primary? (:accent-violet tokens)
                    :else    "transparent")
   :border        (str "1px solid "
                       (cond
                         danger?  (:red tokens)
                         primary? (:accent-violet tokens)
                         :else    (:border-default tokens)))
   :color         (cond
                    primary? "#0E0F12"           ; bg-0 — readable on violet
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
  registry slot expects. Pre-alpha the canonical shape is `{:pattern
  <str-or-kw>}` — the matcher only consults `:pattern`. A non-default
  `:scope` is attached when present so the future widening pass has
  the data; the default `#{:event-id}` scope (always-on per spec/018
  §7) is NOT serialised because every pill carries it implicitly.

  String patterns starting with `:` are normalised to keywords so the
  stored shape matches what spec/018 §7 catalogues (`:auth/*`,
  `:order/cart/*`). Whitespace-only / blank patterns survive as nil —
  the registry's save handler drops the pill rather than persisting
  an unmatchable one."
  [{:keys [pattern scope]}]
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
                  :else                      nil)
        ;; Only attach :scope when the user widened past the default
        ;; #{:event-id} — pre-alpha there is no widening surface but
        ;; the data plumbing carries any non-default set through.
        non-default-scope?
        (and (set? scope)
             (not= scope #{:event-id})
             (seq scope))]
    (cond-> {:pattern p}
      non-default-scope? (assoc :scope scope))))

(defn pill->draft
  "Hydrate a stored pill into the user-editable draft shape. nil pill
  → empty draft."
  [{:keys [pattern scope] :as _pill}]
  {:pattern (cond
              (nil? pattern)         ""
              (keyword? pattern)     (str pattern)
              :else                  (str pattern))
   :scope   (or scope #{:event-id})})

;; ---- view ----------------------------------------------------------------

(defn- mode-radio
  [{:keys [mode current-mode]}]
  (let [tone   (case mode :in (:green tokens) :out (:magenta tokens))
        on?    (= mode current-mode)]
    [:label {:data-testid (str "rf-causa-edit-popup-mode-" (name mode))
             :style {:display       "inline-flex"
                     :align-items   "center"
                     :gap           "6px"
                     :cursor        "pointer"
                     :color         (if on? tone (:text-secondary tokens))
                     :font-family   sans-stack
                     :font-size     (:body type-scale)}}
     [:input {:type      "radio"
              :name      "rf-causa-edit-popup-mode"
              :value     (name mode)
              :checked   on?
              :on-change #(rf/dispatch
                            [:rf.causa/edit-popup-set-mode mode]
                            {:frame :rf/causa})}]
     (case mode :in "IN (show only)" :out "OUT (hide)")]))

(defn- scope-checkbox
  [{:keys [scope-key label current-scope]}]
  (let [checked? (boolean (and current-scope (contains? current-scope scope-key)))
        ;; Pre-alpha the matcher only honours :event-id; the other
        ;; scopes ship as data-only so the popup's visual contract is
        ;; complete. event-id stays disabled — it's always on so a
        ;; pattern always has somewhere to match.
        always-on? (= scope-key :event-id)]
    [:label {:data-testid (str "rf-causa-edit-popup-scope-" (name scope-key))
             :style {:display     "flex"
                     :align-items "center"
                     :gap         "8px"
                     :padding     "2px 0"
                     :cursor      (if always-on? "default" "pointer")
                     :color       (if always-on?
                                    (:text-primary tokens)
                                    (:text-secondary tokens))
                     :font-family sans-stack
                     :font-size   (:body type-scale)}}
     [:input {:type      "checkbox"
              :checked   (or always-on? checked?)
              :disabled  always-on?
              :on-change #(when-not always-on?
                            (rf/dispatch
                              [:rf.causa/edit-popup-toggle-scope scope-key]
                              {:frame :rf/causa}))}]
     label
     (when (not always-on?)
       [:span {:style {:margin-left "6px"
                       :color (:text-tertiary tokens)
                       :font-size (:caption type-scale)}}
        "(pre-alpha: stored, not yet matched)"])]))

(defn popup-view
  "The popup body. Caller (`filters/Modal`) gates the mount on
  `:rf.causa/edit-popup-open?`."
  []
  (let [trigger     @(rf/subscribe [:rf.causa/edit-popup-trigger])
        draft       @(rf/subscribe [:rf.causa/edit-popup-draft])
        positioning @(rf/subscribe [:rf.causa/modal-positioning])
        mode        (or (:mode draft) :in)
        scope       (or (:scope draft) #{:event-id})
        editing?    (= :pill (:source trigger))
        title       (case (:source trigger)
                      :pill    "Edit filter"
                      :context "Add filter for this event"
                      "Add filter")
        ;; Apply is enabled when the pattern is non-blank.
        can-apply?  (let [p (:pattern draft)]
                      (and (string? p) (seq (clojure.string/trim p))))]
    [:div {:data-testid "rf-causa-edit-popup-backdrop"
           :data-rf-causa-modal-positioning (name (or positioning :fixed))
           :on-click    #(rf/dispatch [:rf.causa/close-edit-popup] {:frame :rf/causa})
           :style       (backdrop-style positioning)}
     [:div {:data-testid "rf-causa-edit-popup-dialog"
            :on-click    #(.stopPropagation %)
            :style       (dialog-style)}
      [:div {:style (header-style)}
       [:span title]
       [:button {:data-testid "rf-causa-edit-popup-close"
                 :on-click    #(rf/dispatch
                                 [:rf.causa/close-edit-popup]
                                 {:frame :rf/causa})
                 :title       "Close (Esc)"
                 :style {:background  "transparent"
                         :border      "none"
                         :color       (:text-secondary tokens)
                         :cursor      "pointer"
                         :font-size   "16px"}}
        "✕"]]

      [:div {:style (section-style)}
       [:label {:style (label-style)} "Mode"]
       [:div {:style (radio-row-style)}
        [mode-radio {:mode :in :current-mode mode}]
        [mode-radio {:mode :out :current-mode mode}]]]

      [:div {:style (section-style)}
       [:label {:style (label-style) :for "rf-causa-edit-popup-pattern"}
        "Pattern"]
       [:input {:data-testid  "rf-causa-edit-popup-pattern"
                :id           "rf-causa-edit-popup-pattern"
                :type         "text"
                :auto-focus   true
                :value        (or (:pattern draft) "")
                :placeholder  ":auth/* or :mouse-move or /login"
                :on-change    #(rf/dispatch
                                 [:rf.causa/edit-popup-set-pattern
                                  (.. % -target -value)]
                                 {:frame :rf/causa})
                :on-key-down  (fn [^js e]
                                (case (.-key e)
                                  "Enter"  (when can-apply?
                                             (.preventDefault e)
                                             (rf/dispatch
                                               [:rf.causa/save-edit-popup]
                                               {:frame :rf/causa}))
                                  "Escape" (do (.preventDefault e)
                                               (rf/dispatch
                                                 [:rf.causa/close-edit-popup]
                                                 {:frame :rf/causa}))
                                  nil))
                :style        (input-style)}]
       [:div {:style {:margin-top "4px"
                      :color (:text-tertiary tokens)
                      :font-family sans-stack
                      :font-size (:caption type-scale)}}
        "keyword · glob (:foo/*) · namespace (:foo) · substring"]]

      [:div {:style (section-style)}
       [:label {:style (label-style)} "Match scope"]
       [scope-checkbox {:scope-key :event-id
                        :label "event-id"
                        :current-scope scope}]
       [scope-checkbox {:scope-key :event-args
                        :label "event-args"
                        :current-scope scope}]
       [scope-checkbox {:scope-key :source-coord
                        :label "source-coord"
                        :current-scope scope}]
       [scope-checkbox {:scope-key :tags
                        :label "tags"
                        :current-scope scope}]]

      [:div {:style (footer-style)}
       (if editing?
         [:button {:data-testid "rf-causa-edit-popup-delete"
                   :on-click    #(rf/dispatch
                                   [:rf.causa/delete-edit-popup]
                                   {:frame :rf/causa})
                   :style       (btn-style {:danger? true})}
          "Delete"]
         [:span])
       [:div {:style {:display "flex" :gap "8px"}}
        [:button {:data-testid "rf-causa-edit-popup-cancel"
                  :on-click    #(rf/dispatch
                                  [:rf.causa/close-edit-popup]
                                  {:frame :rf/causa})
                  :style       (btn-style {})}
         "Cancel"]
        [:button {:data-testid "rf-causa-edit-popup-save"
                  :disabled    (not can-apply?)
                  :on-click    #(when can-apply?
                                  (rf/dispatch
                                    [:rf.causa/save-edit-popup]
                                    {:frame :rf/causa}))
                  :style       (merge (btn-style {:primary? true})
                                      (when-not can-apply?
                                        {:opacity 0.4 :cursor "default"}))}
         (if editing? "Apply" "Add")]]]]]))
