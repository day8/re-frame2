(ns day8.re-frame2-causa.panels.managed-fx-template
  "Managed-fx wire-boundary diff template (rf2-uyp86, parent rf2-5aw5v).

  The headline cross-cutting Causa feature from
  [`tools/causa/spec/019-Cross-Cutting-Insight.md`](../../../../tools/causa/spec/019-Cross-Cutting-Insight.md)
  §2.4 / F-C2. Renders one panel per managed-fx invocation inside the
  focused cascade's six-domino window, surfacing the eight-property
  contract from [`spec/Managed-Effects.md`](../../../../spec/Managed-Effects.md)
  as a uniform UI:

  ```
  ┌─ MANAGED FX [HTTP] · :user/load-profile · 250ms ──────────────┐
  │ STATUS: ✓ 200 OK · correlation: c-abc12 · phase: completed     │
  │                                                                │
  │ ▼ REQUEST                                                      │
  │ ▼ WIRE TIMING                                                  │
  │ ▼ RESPONSE                                                     │
  │ ▼ HANDLER DISPATCHED                                           │
  │ ▼ APP-DB SLICE TOUCHED                                         │
  └────────────────────────────────────────────────────────────────┘
  ```

  ## Five surfaces, one template

  The same renderer handles HTTP, WebSocket, machine-`:invoke`, SSR
  `:rf.server/*`, and `:rf.flow/*` records. Per-surface variation lives
  in the helpers ns (`panels/managed_fx_helpers`); this ns is purely a
  hiccup folder over the record shape.

  ## Pure hiccup (rf2-tijr)

  Same contract as every other Causa panel — pure hiccup, no Reagent /
  UIx / Helix references. Frame isolation is provided by the enclosing
  `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`. Every
  `subscribe` / `dispatch` here resolves to the `:rf/causa` frame.

  ## Cross-link

  The HANDLER DISPATCHED row uses `:rf.causa/focus-event` to pivot the
  spine to the child cascade — clicking '→ jump to handler' moves
  focus to wherever the response landed, so the user can follow the
  cascade chain hop-by-hop. Cross-link wiring lives in
  `panels/managed_fx_subs/install!` so the panel view stays thin."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.managed-fx-helpers :as h]
            [day8.re-frame2-causa.chart.timing-waterfall :as waterfall]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]
            [day8.re-frame2-causa.theme.section :as section]
            [day8.re-frame2-causa.theme.data-inspector :as inspector]))

;; Section rhythm hoisted to `theme/section.cljc` per rf2-pie8q —
;; identical visual contract is shared with `panels/event_detail`.
;; This panel passes `:container-padding "8px 0"` so the section sits
;; flush against the record-panel's surrounding `padding "8px 12px"`
;; outer wrapper (added below in `record-panel`).

;; ---- panel header ------------------------------------------------------

(defn- status-pill
  "Coloured status pill anchoring the panel header. HTTP-status-band
  colour wins when an HTTP status is present (green for 2xx, etc.);
  otherwise we fall back to the generic status colour."
  [{:keys [status http-status]}]
  (let [band-tok    (when (number? http-status)
                      (h/format-http-status-band http-status))
        tok         (or band-tok (get h/status->colour-token status :text-tertiary))
        colour      (get tokens tok (:text-tertiary tokens))
        glyph       (get h/status->glyph status "—")
        label       (h/format-status-label status)]
    [:span {:data-testid (str "rf-causa-managed-fx-status-" (name status))
            :style       {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "6px"
                          :padding       "1px 8px"
                          :border-radius "3px"
                          :background    "rgba(255,255,255,0.04)"
                          :color         colour
                          :font-family   mono-stack
                          :font-size     "11px"
                          :font-weight   700
                          :letter-spacing "0.4px"}}
     [:span glyph]
     (str label
          (when (number? http-status)
            (str " " http-status)))]))

(defn- correlation-pill
  "Render the correlation-id pill. Right-click fires
  `:rf.causa/filter-by-http-correlation` to drop a typed
  `:http-correlation` IN pill in the ribbon (rf2-piye4) — narrows
  the L2 event list to cascades that touched this exchange (issuing
  effect, retries, response, downstream handler)."
  [correlation-id]
  (when correlation-id
    [:span {:data-testid "rf-causa-managed-fx-correlation"
            :on-context-menu (fn [^js e]
                               (.preventDefault e)
                               (rf/dispatch
                                 [:rf.causa/filter-by-http-correlation correlation-id]
                                 {:frame :rf/causa}))
            :title "Right-click to filter the event list to this HTTP exchange"
            :style {:padding       "1px 6px"
                    :margin-left   "8px"
                    :border-radius "3px"
                    :background    (:bg-3 tokens)
                    :color         (:text-secondary tokens)
                    :font-family   mono-stack
                    :font-size     "10px"
                    :cursor        "context-menu"}}
     (str "correlation: " correlation-id)]))

(defn- phase-pill
  [phase]
  (when phase
    [:span {:data-testid (str "rf-causa-managed-fx-phase-" (name phase))
            :style {:padding       "1px 6px"
                    :margin-left   "8px"
                    :border-radius "3px"
                    :background    (:bg-3 tokens)
                    :color         (:text-secondary tokens)
                    :font-family   mono-stack
                    :font-size     "10px"}}
     (str "phase: " (name phase))]))

(defn- cancel-pill
  [cancel-cause]
  (when cancel-cause
    [:span {:data-testid "rf-causa-managed-fx-cancel"
            :style {:padding       "1px 6px"
                    :margin-left   "8px"
                    :border-radius "3px"
                    :background    "rgba(232, 121, 249, 0.12)"
                    :color         (:magenta tokens)
                    :font-family   mono-stack
                    :font-size     "10px"
                    :font-weight   600}}
     (str "cancel: " cancel-cause)]))

(defn- stub-pill
  [stubbed?]
  (when stubbed?
    [:span {:data-testid "rf-causa-managed-fx-stub"
            :title "Stubbed — this effect is redirected by an :fx-overrides entry instead of running for real."
            :style {:padding       "1px 6px"
                    :margin-left   "8px"
                    :border-radius "3px"
                    :background    "rgba(124, 92, 255, 0.15)"
                    :border        (str "1px solid " (:accent-violet tokens))
                    :color         (:accent-violet tokens)
                    :font-family   mono-stack
                    :font-size     "10px"
                    :font-weight   700
                    :letter-spacing "0.5px"}}
     "STUB"]))

(defn- panel-header
  [{:keys [surface fx-id duration-ms status http-status correlation-id phase cancel-cause stubbed?]
    :as   record}]
  [:header {:data-testid (str "rf-causa-managed-fx-header-" (name surface))
            :style       {:display       "flex"
                          :align-items   "center"
                          :flex-wrap     "wrap"
                          :gap           "6px"
                          :padding       "8px 12px"
                          :background    (:bg-3 tokens)
                          :border-bottom (str "1px solid " (:border-subtle tokens))
                          :font-family   sans-stack
                          :font-size     "12px"
                          :color         (:text-primary tokens)}}
   [:span {:data-testid (str "rf-causa-managed-fx-surface-" (name surface))
           :style {:display       "inline-flex"
                   :align-items   "center"
                   :gap           "4px"
                   :padding       "1px 8px"
                   :border-radius "3px"
                   :background    "rgba(67, 195, 208, 0.12)"
                   :color         (:cyan tokens)
                   :font-family   mono-stack
                   :font-size     "11px"
                   :font-weight   700
                   :letter-spacing "0.5px"}}
    [:span (get h/surface->glyph surface "•")]
    (str "MANAGED FX [" (get h/surface->label surface (str surface)) "]")]
   [:span {:data-testid "rf-causa-managed-fx-fx-id"
           :on-context-menu (fn [^js e]
                              (when fx-id
                                (.preventDefault e)
                                (rf/dispatch
                                  [:rf.causa/filter-by-fx fx-id]
                                  {:frame :rf/causa})))
           :title "Right-click to filter the event list to events triggering this fx"
           :style {:color (:accent-violet tokens)
                   :font-family mono-stack
                   :font-size "12px"
                   :font-weight 600
                   :cursor "context-menu"}}
    (h/format-fx-id fx-id)]
   [:span {:style {:color (:text-tertiary tokens)
                   :font-family mono-stack
                   :font-size "11px"}}
    (h/format-duration-ms duration-ms)]
   [:span {:style {:flex 1}}]
   (status-pill record)
   (correlation-pill correlation-id)
   (phase-pill phase)
   (cancel-pill cancel-cause)
   (stub-pill stubbed?)])

;; ---- section bodies ----------------------------------------------------

(defn- request-section
  [{:keys [req surface fx-id]}]
  (cond
    (and (nil? req) (= surface :flow))
    [:span {:style {:color (:text-tertiary tokens)}} "(flow input — see registration)"]

    (nil? req)
    [:span {:style {:color (:text-tertiary tokens)}} "(no request payload)"]

    :else
    [inspector/inspect req (str "managed-fx/" (h/format-fx-id fx-id) "/req")]))

(defn- wire-section
  "Wire timing section. When the surface emits per-phase wire data we
  render the waterfall; when only synthesised round-trip is available
  we render the single bar; when nothing is available we render the
  documented `n/a` placeholder per the divergence allowance."
  [{:keys [wire]}]
  (cond
    (and wire (seq (:phases wire)))
    (waterfall/render wire)

    :else
    [:div {:data-testid "rf-causa-managed-fx-wire-na"
           :style {:color (:text-tertiary tokens) :font-style "italic"}}
     "n/a — this surface does not emit per-phase wire timing today."]))

(defn- response-section
  [{:keys [res surface fx-id failure]}]
  (cond
    failure
    [:div
     [:div {:style {:color (:red tokens)
                    :font-weight 600
                    :margin-bottom "4px"}}
      (str "✗ " (or (some-> failure :kind name) "FAILURE"))]
     [inspector/inspect (or (:tags failure) failure)
      (str "managed-fx/" (h/format-fx-id fx-id) "/failure")]]

    (and (nil? res) (= surface :flow))
    [:span {:style {:color (:text-tertiary tokens)}}
     "(in flight / no output yet)"]

    (nil? res)
    [:span {:style {:color (:text-tertiary tokens)}}
     "(no response payload yet)"]

    :else
    [inspector/inspect res (str "managed-fx/" (h/format-fx-id fx-id) "/res")]))

(defn- handler-section
  "Renders the dispatched handler event vector + a click-to-focus
  affordance that pivots the spine to that child cascade. Anchors the
  F.3 'failed response handler' diagnostic."
  [{:keys [handler frame dispatch-id]}]
  (if (and (vector? handler) (seq handler))
    [:div {:style {:display "flex"
                   :align-items "center"
                   :gap "12px"
                   :flex-wrap "wrap"}}
     [:div {:style {:flex 1 :min-width 0}}
      [inspector/inspect handler "managed-fx/handler"]]
     [:button {:data-testid "rf-causa-managed-fx-focus-handler"
               :on-click    #(rf/dispatch [:rf.causa/focus-event dispatch-id frame]
                                          {:frame :rf/causa})
               :style       {:background  "transparent"
                             :border      (str "1px solid " (:border-default tokens))
                             :color       (:accent-violet tokens)
                             :font-family mono-stack
                             :font-size   "10px"
                             :padding     "2px 8px"
                             :border-radius "3px"
                             :cursor      "pointer"
                             :flex-shrink 0}}
      "→ focus event ↗"]]
    [:span {:style {:color (:text-tertiary tokens)}}
     "(no handler dispatched — this fx had no :on-success / :on-failure / :on-done)"]))

(defn- app-db-slice-section
  "Per spec/019 §2.4 F.4 — 'app-db wasn't updated' lights up when the
  status is OK but the slice paths-touched list is empty. The renderer
  highlights the empty-paths case with an amber hint so the bug class
  is immediately legible."
  [{:keys [paths-touched status]}]
  (cond
    (and (= status :ok) (empty? paths-touched))
    [:div
     [:div {:style {:color (:yellow tokens)
                    :font-weight 600
                    :font-family mono-stack
                    :font-size "11px"
                    :margin-bottom "4px"}}
      "⚠ STATUS :ok but no app-db paths changed — app-db wasn't updated"]
     [:div {:style {:color (:text-tertiary tokens) :font-style "italic"}}
      "The handler dispatched but did not write a slice. Likely a "
      "missing :db assoc or a guard that no-op'd."]]

    (empty? paths-touched)
    [:span {:style {:color (:text-tertiary tokens)}}
     "(no app-db changes in this cascade)"]

    :else
    [:ul {:style {:list-style "none"
                  :margin     0
                  :padding    0}}
     (for [[i path] (map-indexed vector paths-touched)]
       ^{:key i}
       [:li {:style {:padding "2px 0"
                     :font-family mono-stack
                     :font-size "12px"
                     :color (:text-primary tokens)}}
        [:span {:style {:color (:accent-violet tokens)}}
         (pr-str path)]])]))

;; ---- one record's panel ------------------------------------------------

(defn record-panel
  "Render one managed-fx record as a hiccup panel. Pure function over
  the record; CLJS-only (consumes Reagent re-frame subs via
  `inspector/inspect`).

  Section default-expanded state per the bead's contract:

    - STATUS + WIRE + APP-DB SLICE TOUCHED expanded by default
    - REQUEST + RESPONSE + HANDLER DISPATCHED collapsed by default
      (one click reveals the payload — keeps the panel scannable on
      first paint)."
  [record]
  [:section {:data-testid (str "rf-causa-managed-fx-record-"
                               (name (:surface record))
                               "-" (or (:origin-event-id record) "x"))
             :data-fx-id  (h/format-fx-id (:fx-id record))
             :data-status (name (:status record))
             :style       {:margin "8px 12px"
                           :border (str "1px solid " (:border-subtle tokens))
                           :border-radius "4px"
                           :background    (:bg-2 tokens)}}
   (panel-header record)
   [:div {:style {:padding "8px 12px"}}
    (section/section-row
      {:label "REQUEST" :expanded? false
       :testid "rf-causa-managed-fx-section-request"
       :container-padding "8px 0"}
      (request-section record))
    (section/section-row
      {:label "WIRE TIMING" :expanded? true
       :testid "rf-causa-managed-fx-section-wire"
       :container-padding "8px 0"}
      (wire-section record))
    (section/section-row
      {:label "RESPONSE" :expanded? false
       :testid "rf-causa-managed-fx-section-response"
       :container-padding "8px 0"}
      (response-section record))
    (section/section-row
      {:label "HANDLER DISPATCHED" :expanded? false
       :testid "rf-causa-managed-fx-section-handler"
       :container-padding "8px 0"}
      (handler-section record))
    (section/section-row
      {:label "APP-DB SLICE TOUCHED" :expanded? true
       :testid "rf-causa-managed-fx-section-app-db"
       :container-padding "8px 0"}
      (app-db-slice-section record))]])

;; ---- list panel --------------------------------------------------------

(defn records-list
  "Render a vector of managed-fx records as a stack of panels. Pure fn
  over the records vector; used by `event_detail.cljs` to mount the
  list under the six-domino cascade view, and by the tests to render
  a deterministic stack against canned records."
  [records]
  (when (seq records)
    [:div {:data-testid "rf-causa-managed-fx-list"
           :style {:padding "8px 0"
                   :border-top (str "1px solid " (:border-subtle tokens))}}
     [:div {:style {:padding "8px 12px 0 12px"
                    :font-family sans-stack
                    :font-size "12px"
                    :color (:text-tertiary tokens)}}
      [:span (str (count records) " managed-fx record"
                  (if (= 1 (count records)) "" "s")
                  " in this cascade")]]
     (for [rec records]
       ^{:key (str (:surface rec) "-" (:origin-event-id rec) "-" (:fx-id rec))}
       (record-panel rec))]))
