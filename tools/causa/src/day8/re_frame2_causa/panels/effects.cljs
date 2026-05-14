(ns day8.re-frame2-causa.panels.effects
  "Effects panel — Phase 5 (rf2-ts41u, parent rf2-5aw5v).

  Surfaces re-frame2's registered fxs + their per-fx invocations,
  outcome status, and stub indicator. Consumer of:

    - Spec 002 (`reg-fx`)          — the registered-fx surface;
                                     `(rf/handlers :fx)` is the
                                     `{fx-id metadata}` projection
                                     the composite sub reads.
    - Spec 009 (Instrumentation)   — the `:rf.fx/*` trace event
                                     vocabulary (`:rf.fx/handled`,
                                     `:rf.fx/override-applied`,
                                     `:rf.fx/skipped-on-platform`)
                                     plus the fx-layer error events
                                     (`:rf.error/fx-handler-exception`,
                                     `:rf.error/no-such-fx`). The
                                     panel filters the Causa trace
                                     buffer to the fx-related slice
                                     and derives a per-fx outcome
                                     from the latest event.

  ## What this panel shows

  One row per registered fx, with — left to right:

    - status badge (`:error` / `:overridden` / `:skipped` / `:ok` /
      `:never-invoked`),
    - the fx-id (mono column),
    - the `:platforms` chip (`any` / `client` / `server`),
    - the most-recent operation in a small caption,
    - the invocation count for this buffer,
    - a `STUB` indicator when an `:fx-overrides` is active for that id
      (per Spec 002 §`:fx-overrides`).

  Clicking a row selects that fx and dispatches the panel pivot to
  `:event-detail` filtered to the fx's recent invocations — the
  bead's per-row click-through. The selection alone lands in v1; the
  cross-panel filter wiring rides the `:rf.causa/select-fx-id`
  selection.

  Empty state: \"No fx registered.\"

  ## Pure hiccup

  Same contract as every other Causa panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`.
  Every `subscribe` / `dispatch` here resolves to the `:rf/causa`
  frame.

  ## Helpers

  All pure-data logic — `project-rows`, `compute-outcome`,
  `latest-override-per-fx`, ... — lives in `effects_helpers.cljc` so
  the algebra runs under the JVM unit-test target."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.panels.effects-helpers :as h]
            [day8.re-frame2-causa.panels.overflow-indicator :as overflow]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- pure helpers -------------------------------------------------------

(defn- outcome-colour
  "Resolve an outcome's colour token to its hex via the panel's
  `tokens` map. Single point of indirection so the test suite can
  assert against the token without touching the hex."
  [outcome]
  (let [tok (get h/outcome->token outcome :text-tertiary)]
    (get tokens tok (:text-tertiary tokens))))

;; ---- status badge -------------------------------------------------------

(defn- outcome-badge
  "Colour + shape + tooltip-label, 14px on cosy density. Sits at the
  left margin of every fx row. `outcome` is one of the canonical
  five — `#{:error :overridden :skipped :ok :never-invoked}`."
  [outcome]
  (let [glyph   (get h/outcome->glyph outcome "?")
        colour  (outcome-colour outcome)
        tooltip (get h/outcome->tooltip outcome "Unknown")]
    [:span {:data-testid     (str "rf-causa-fx-badge-" (name outcome))
            :title           tooltip
            :aria-label      tooltip
            :style           {:display        "inline-block"
                              :width          "14px"
                              :height         "14px"
                              :line-height    "14px"
                              :text-align     "center"
                              :color          colour
                              :font-family    mono-stack
                              :font-size      "14px"
                              :font-weight    700
                              :margin-right   "8px"
                              :flex-shrink    0}}
     glyph]))

;; ---- fx row -------------------------------------------------------------

(defn- platforms-pill
  "Compact platforms indicator — `any`, `client`, `server`. Per
  Spec 002 §reg-fx fxs default to both platforms; the chip surfaces
  the non-default cases so a programmer scanning the list spots an
  SSR-restricted handler at a glance."
  [platforms]
  [:span {:style {:display       "inline-block"
                  :padding       "1px 6px"
                  :margin-right  "8px"
                  :border-radius "3px"
                  :background    (:bg-3 tokens)
                  :color         (:text-secondary tokens)
                  :font-family   mono-stack
                  :font-size     "10px"}}
   (h/format-platforms platforms)])

(defn- stub-indicator
  "Render the `STUB` indicator for an fx with an active override. Per
  Spec 002 §`:fx-overrides` an override replaces the registered
  handler at the call site; the indicator gives the programmer
  immediate visibility that the fx's real handler is being bypassed."
  [fx-id]
  [:span {:data-testid (str "rf-causa-fx-row-stub-" (h/format-fx-id fx-id))
          :title       "An :fx-overrides redirect is active for this fx — the registered handler is being replaced at the call site."
          :style       {:color         (:accent-violet tokens)
                        :background    "rgba(124, 92, 255, 0.15)"
                        :border        (str "1px solid " (:accent-violet tokens))
                        :border-radius "3px"
                        :padding       "0px 6px"
                        :margin-left   "8px"
                        :font-family   mono-stack
                        :font-size     "10px"
                        :font-weight   700
                        :letter-spacing "0.5px"}}
   "STUB"])

(defn- fx-row
  "One row in the fx list. Clicking the row fires
  `:rf.causa/select-fx-id` so the cross-panel affordance can drill
  into the event-detail panel filtered to this fx's recent
  invocations."
  [{:keys [fx-id platforms outcome stubbed? invocation-count last-operation]
    :as _row}
   selected?]
  [:li {:data-testid (str "rf-causa-fx-row-" (h/format-fx-id fx-id))
        :on-click   #(rf/dispatch [:rf.causa/select-fx-id fx-id] {:frame :rf/causa})
        :style      {:display       "flex"
                     :align-items   "center"
                     :padding       "6px 12px"
                     :background    (if selected?
                                      (:bg-active tokens)
                                      "transparent")
                     :border-bottom (str "1px solid " (:border-subtle tokens))
                     :cursor        "pointer"
                     :font-family   mono-stack
                     :font-size     "13px"
                     :color         (:text-primary tokens)}}
   (outcome-badge outcome)
   (platforms-pill platforms)
   [:span {:style {:flex 1
                   :min-width 0
                   :overflow "hidden"
                   :text-overflow "ellipsis"
                   :white-space "nowrap"}}
    [:span {:data-testid (str "rf-causa-fx-id-" (h/format-fx-id fx-id))
            :style {:color (:text-primary tokens) :margin-right "8px"}}
     (h/format-fx-id fx-id)]
    (when last-operation
      [:span {:style {:color (:text-tertiary tokens)
                      :font-size "11px"
                      :font-family sans-stack}}
       (case last-operation
         :rf.fx/handled                  "handled"
         :rf.fx/override-applied         "override"
         :rf.fx/skipped-on-platform      "skipped"
         :rf.error/fx-handler-exception  "exception"
         :rf.error/no-such-fx            "no-handler"
         (str last-operation))])]
   (when (pos? invocation-count)
     [:span {:data-testid (str "rf-causa-fx-row-invocations-"
                               (h/format-fx-id fx-id))
             :style {:color (:text-tertiary tokens)
                     :font-size "10px"
                     :margin-left "8px"
                     :font-family mono-stack}}
      (str invocation-count "×")])
   (when stubbed?
     (stub-indicator fx-id))])

;; ---- fx list ------------------------------------------------------------

(defn- fx-list
  "The default list view. Renders one row per registered fx in the
  canonical sort order (errors first, then overridden, then skipped,
  then ok, then never-invoked)."
  [rows selected-fx-id]
  (overflow/capped-list
    rows
    {:panel-id "effects"
     :ul-attrs {:data-testid "rf-causa-fx-list"
                :style {:list-style "none"
                        :margin     0
                        :padding    0
                        :background (:bg-2 tokens)}}
     :row-fn   (fn [row]
                 ^{:key (h/format-fx-id (:fx-id row))}
                 (fx-row row (= (:fx-id row) selected-fx-id)))}))

;; ---- summary header -----------------------------------------------------

(defn- summary-header
  "Per-outcome tally + total count, mirroring the Flows panel's
  summary header. The five-outcome taxonomy is small enough that v1
  sorts rather than filters — the most actionable rows surface at
  the top via the canonical outcome order."
  [outcome-counts total]
  [:div {:data-testid "rf-causa-fx-summary"
         :style       {:padding "8px 12px"
                       :display "flex"
                       :align-items "center"
                       :gap "8px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :color (:text-tertiary tokens)
                       :font-family mono-stack
                       :font-size "11px"}}
   (for [o h/outcomes
         :let [n (get outcome-counts o 0)]
         :when (pos? n)]
     ^{:key o}
     [:span {:data-testid (str "rf-causa-fx-summary-" (name o))
             :style {:color (outcome-colour o)
                     :margin-right "6px"}}
      (str (get h/outcome->glyph o) " " n " " (name o))])
   [:span {:style {:flex 1}}]
   [:span (str total " fx" (if (= 1 total) "" "s"))]])

;; ---- empty state --------------------------------------------------------

(defn- empty-state
  "Rendered when no fxs are registered. The empty surface matches the
  bead's minimum-viable contract — exactly the copy 'No fx
  registered.' and a one-line orienting caption."
  []
  [:div {:data-testid "rf-causa-fx-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"
                :color  (:text-secondary tokens)}}
    "No fx registered."]
   [:p {:style {:margin 0
                :font-size "12px"
                :color (:text-tertiary tokens)}}
    "Register an fx via "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     "rf/reg-fx"]
    " — the panel populates as fxs register. See "
    [:code {:style {:font-family mono-stack
                    :color       (:accent-violet tokens)}}
     "spec/002-Frames.md"]
    " §reg-fx."]])

;; ---- public view --------------------------------------------------------

(rf/reg-view effects-view
  "The Effects panel's root view. Subscribes to
  `:rf.causa/effects-data` and renders the summary header + fx list
  (or the empty state when no fxs are registered)."
  []
  (let [{:keys [rows outcome-counts total selected-fx-id]}
        @(rf/subscribe [:rf.causa/effects-data])]
    [:section {:data-testid "rf-causa-fx"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "Effects"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Registered fxs + recent invocations. The "
       [:em "STUB"]
       " indicator surfaces when an "
       [:code {:style {:font-family mono-stack
                       :color       (:accent-violet tokens)}}
        ":fx-overrides"]
       " redirect is active."]]
     (when (pos? total)
       (summary-header outcome-counts total))
     [:div {:style {:flex 1 :overflow "auto"}}
      (if (zero? total)
        (empty-state)
        (fx-list rows selected-fx-id))]]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Effects panel's Causa-side registrations
  (Phase 5, rf2-ts41u)."
  []
  ;; ---- Phase 5 (rf2-ts41u) — Effects panel ---------------------------
  ;;
  ;; Surfaces re-frame2's registered fxs + their per-fx invocations,
  ;; outcome status, and stub indicator. Consumer of Spec 002 §reg-fx
  ;; (the registered-fx surface) + Spec 009 (the `:rf.fx/*` trace event
  ;; vocabulary).
  ;;
  ;; The panel reads two surfaces — the framework's registered-fx map
  ;; (`(rf/handlers :fx)`) and the Causa trace-buffer's fx-related
  ;; slice (`:op-type :fx` plus the fx-layer error categories) — and
  ;; folds them via `effects-helpers/project-rows` into one row per
  ;; registered fx.
  ;;
  ;; Tests stub the registered-fx surface by writing
  ;; `:registered-fxs-override` to Causa's app-db (via
  ;; `:rf.causa/set-registered-fxs-override-for-test`) so the suite
  ;; can assert against a deterministic fx set without booting a host
  ;; runtime that registers fxs.
  ;;
  ;; Shape of `:rf.causa/effects-data`:
  ;;
  ;;     {:rows           [<row> ...]
  ;;      :outcome-counts {outcome count}
  ;;      :total          <int>
  ;;      :selected-fx-id <fx-id-or-nil>}

  ;; Read the registered-fx map. Reads `(rf/handlers :fx)` — per
  ;; Spec 001 §The public registrar query API the registrar is
  ;; process-global so this surfaces every registered fx across
  ;; every frame. The v1 wiring threads it through the override
  ;; slot so the JVM test target can drive the projection without
  ;; booting a substrate that registers fxs.
  (rf/reg-sub :rf.causa/registered-fxs
    (fn [db _query]
      (let [ov (get db :registered-fxs-override)]
        (or ov (rf/handlers :fx)))))

  ;; Test-only override hook for the registered-fxs surface.
  ;; Production code paths never dispatch this — the slot exists
  ;; only so JVM + node-test suites can drive the projection
  ;; without booting the fx registrar.
  (rf/reg-event-db :rf.causa/set-registered-fxs-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :registered-fxs-override)
        (assoc db :registered-fxs-override ov))))

  ;; The Causa trace-buffer's fx-related slice. Pure-data filter —
  ;; the helper's predicate is JVM-runnable so tests can drive it
  ;; without a CLJS runtime.
  ;;
  ;; Note the single-signal `:<-` arity: re-frame2's `reg-sub`
  ;; passes the upstream value DIRECTLY (not vector-wrapped) when
  ;; there's exactly one `:<-` chain entry — per Spec 002 §The
  ;; reg-sub forms + subs.cljc parse-reg-sub-args.
  (rf/reg-sub :rf.causa/fx-trace-events
    :<- [:rf.causa/trace-buffer]
    (fn [buffer _query]
      (h/filter-fx-events buffer)))

  ;; The user's per-panel fx selection. Drives a cross-panel
  ;; affordance (click fx → event-detail filtered to that fx's
  ;; recent invocations); v1 wiring carries the selection only —
  ;; the cross-panel jump lands when the cross-panel filter API
  ;; stabilises.
  (rf/reg-sub :rf.causa/selected-fx-id
    (fn [db _query]
      (get db :selected-fx-id)))

  ;; The composite the panel consumes. One read produces every slot
  ;; the view needs (matches the per-panel composite pattern every
  ;; other Causa panel uses).
  (rf/reg-sub :rf.causa/effects-data
    :<- [:rf.causa/registered-fxs]
    :<- [:rf.causa/fx-trace-events]
    :<- [:rf.causa/selected-fx-id]
    (fn [[fxs-map fx-events selected-fx-id] _query]
      (let [rows   (h/project-rows fxs-map fx-events)
            counts (h/outcome-counts rows)]
        {:rows           rows
         :outcome-counts counts
         :total          (count rows)
         :selected-fx-id selected-fx-id})))

  ;; ---- Phase 5 (rf2-ts41u) — Effects panel events ------------------

  (rf/reg-event-db :rf.causa/select-fx-id
    (fn [db [_ fx-id]]
      (assoc db :selected-fx-id fx-id)))

  (rf/reg-event-db :rf.causa/clear-fx-selection
    (fn [db _event]
      (dissoc db :selected-fx-id))))
