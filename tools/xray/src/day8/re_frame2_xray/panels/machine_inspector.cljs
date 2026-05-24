(ns day8.re-frame2-xray.panels.machine-inspector
  "Machine Inspector panel — collapsed Dynamic surface (rf2-y9xmf).

  Per Mike's 2026-05-19 redesign, the Dynamic Machines panel is
  **event-driven only**:

    - **BLANK** when the currently focused event is not machine-related
      (per rf2-g3ghh silent-by-default).
    - **When the focused event triggered a machine transition** the
      panel renders one section per machine: topology chart with
      FROM/TO highlighting, the transition edge, guards / actions
      results, the cancellation cascade (when present), `:after`
      countdown rings overlay (when armed timers exist).
    - **prev/next** affordance walks the spine's epoch-history to the
      prior / next event for THE FOCUSED MACHINE (not the full spine).

  ## What was collapsed (rf2-y9xmf)

  The pre-collapse panel (1362 LoC) carried five orthogonal
  exploration surfaces piled into one Dynamic tab: a Machine picker, a
  sub-strip (Topology / Sim / Instances / Cascade), Mode A/B/C
  instance-tab + cluster views, the Sim ribbon UI, a Browse-all entry
  point, an arc overlay + mini-scrubber. None of those belong in a
  Dynamic panel whose only job is to be the lens on the focused event.
  The collapse drops every ribbon. Sim's engine + the browse-all index
  remain in the codebase (sibling bead rf2-r4nao re-hosts them under
  the future Static surface); only the UI ribbons go away.

  ## What stays

    - Topology renderer (ELK + layered fallback; SVG primitive in
      `chart/{layout,svg}`).
    - Transition highlighting (from-state → to-state — dashed-origin /
      bold-landing visual grammar).
    - Per-transition guards + actions lists.
    - Cancellation cascade inline (when the transition triggered one).
    - `:after` countdown rings overlay (when armed timers exist).
    - prev/next nav (per-machine epoch walking).

  ## Pure hiccup

  Same contract as every other Xray panel — the view is pure hiccup,
  no Reagent / UIx / Helix references. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [day8.re-frame2-machines-viz.chart.layout :as chart-layout]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cancellation-cascade]
            [day8.re-frame2-xray.panels.machine-canvas :as machine-canvas]
            [day8.re-frame2-xray.panels.machine-inspector-helpers :as h]
            [day8.re-frame2-xray.panels.machines.trace-state :as trace-state]
            [day8.re-frame2-xray.panels.machine-after-rings :as after-rings]
            [day8.re-frame2-xray.share :as share]
            [day8.re-frame2-xray.theme.tokens
             :as t
             :refer [tokens mono-stack sans-stack display-stack]]))

;; ---- guards + actions lists --------------------------------------------

(defn- safe-name
  "Render `x` to a string suitable for `data-testid` suffixes. Belt-and-
  braces over the projection layer's `ref-display-id` (which normalises
  guard/action refs into keywords). If a future trace shape pipes a fn
  through unprojected the view still won't blow up — `cljs.core/name`
  on a fn throws `Doesn't support name: function ...` (rf2-ujra6)."
  [x]
  (cond
    (nil? x)                          ""
    (or (keyword? x) (symbol? x))     (name x)
    (string? x)                       x
    :else                             (str x)))

(defn- guards-list
  "Render the per-transition Guards section. Empty list = no surface
  (silent-by-default per rf2-g3ghh)."
  [guards]
  (when (seq guards)
    [:div {:data-testid "rf-xray-machine-focused-event-guards"
           :style {:padding "8px 12px"
                   :background (:bg-1 tokens)
                   :border-top (str "1px solid " (:border-subtle tokens))}}
     [:div {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"
                    :margin-bottom "4px"}}
      "Guards"]
     (into [:ul {:style {:list-style "none"
                         :margin 0
                         :padding 0
                         :font-family mono-stack
                         :font-size "11px"
                         :color (:text-primary tokens)}}]
           (for [{:keys [guard-id input outcome]} guards]
             ^{:key (str guard-id)}
             [:li {:data-testid (str "rf-xray-machine-focused-event-guard-"
                                     (safe-name guard-id))
                   :style {:display "flex"
                           :align-items "center"
                           :gap "8px"
                           :padding "2px 0"}}
              [:span {:style {:color (case outcome
                                       :pass (:green tokens)
                                       :fail (:red tokens)
                                       (:text-tertiary tokens))
                              :font-weight 600}}
               (case outcome :pass "✓" :fail "✗" "?")]
              [:span (str guard-id)]
              (when input
                [:span {:style {:color (:text-tertiary tokens)}}
                 (pr-str input)])]))]))

(defn- actions-list
  "Render the per-transition Actions section. Empty list = no surface."
  [actions]
  (when (seq actions)
    [:div {:data-testid "rf-xray-machine-focused-event-actions"
           :style {:padding "8px 12px"
                   :background (:bg-1 tokens)
                   :border-top (str "1px solid " (:border-subtle tokens))}}
     [:div {:style {:color (:text-tertiary tokens)
                    :font-family sans-stack
                    :font-size "10px"
                    :text-transform "uppercase"
                    :letter-spacing "0.5px"
                    :margin-bottom "4px"}}
      "Actions"]
     (into [:ul {:style {:list-style "none"
                         :margin 0
                         :padding 0
                         :font-family mono-stack
                         :font-size "11px"
                         :color (:text-primary tokens)}}]
           (for [{:keys [action-id input outcome]} actions]
             ^{:key (str action-id)}
             [:li {:data-testid (str "rf-xray-machine-focused-event-action-"
                                     (safe-name action-id))
                   :style {:display "flex"
                           :align-items "center"
                           :gap "8px"
                           :padding "2px 0"}}
              [:span {:style {:color (case outcome
                                       :ok   (:green tokens)
                                       :fail (:red tokens)
                                       (:text-tertiary tokens))
                              :font-weight 600}}
               (case outcome :ok "✓" :fail "✗" "•")]
              [:span (str action-id)]
              (when input
                [:span {:style {:color (:text-tertiary tokens)}}
                 (pr-str input)])]))]))

;; ---- per-machine focused-event section ---------------------------------

(defn- focused-event-section
  "Render one section per transitioned machine. Header → chart →
  guards → actions → cancellation cascade (inline) → after-rings
  overlay (on the chart)."
  [{:keys [machine-id from-state to-state on-event event microstep?
           definition guards actions fired-edge-ids]}]
  ;; rf2-gpzb4 (2026-05-21 xyflow migration) — the host-side ELK
  ;; layout dance (layout-or-fallback / ensure-elk! / compute-layout!)
  ;; is GONE. xyflow + elkjs now own positioning end-to-end inside
  ;; `mv-chart/MachineChart`; the panel only computes the from/to
  ;; node-ids for the focused-event lens highlight.
  (let [from-id    (when from-state (chart-layout/highlight-id from-state))
        to-id      (when to-state   (chart-layout/highlight-id to-state))
        engine     "xyflow+elkjs"]
    [:section
     {:data-testid (str "rf-xray-machine-focused-event-section-"
                        (when machine-id
                          (subs (str machine-id) 1)))
      :data-machine-id (str machine-id)
      :data-from-state (str from-state)
      :data-to-state (str to-state)
      :data-on-event (str on-event)
      :data-microstep (str (boolean microstep?))
      ;; rf2-zdfbm — the topology is the panel's centrepiece, so the
      ;; section grows to fill the focused-event host's available
      ;; height. A flex column lets the canvas chart (`flex 1` below)
      ;; expand into the panel instead of sitting in a fixed 320px box.
      :style {:margin "12px"
              :border (str "1px solid " (:border-default tokens))
              :border-radius "4px"
              :background (:bg-2 tokens)
              :flex "1 1 0"
              :min-height 0
              :display "flex"
              :flex-direction "column"}}
     ;; Right-click on the per-machine section header fires
     ;; `:rf.xray/filter-by-machine` with this section's machine-id
     ;; (rf2-piye4) — drops a typed `:machine` IN pill into the ribbon
     ;; so the L2 event list narrows to cascades involving this machine.
     [:header {:data-testid "rf-xray-machine-focused-event-header"
               :on-context-menu (fn [^js e]
                                  (when machine-id
                                    (.preventDefault e)
                                    (rf/dispatch
                                      [:rf.xray/filter-by-machine machine-id]
                                      {:frame :rf/xray})))
               :title "Right-click to filter the event list to this machine"
               :style {:padding "10px 12px"
                       :display "flex"
                       :align-items "center"
                       :gap "10px"
                       :border-bottom (str "1px solid " (:border-subtle tokens))
                       :background (:bg-3 tokens)
                       :font-family mono-stack
                       :font-size "12px"
                       :color (:text-primary tokens)}}
      (when microstep?
        [:span {:style {:color (:text-tertiary tokens) :font-size "10px"}}
         "↳"])
      [:strong {:style {:color (:accent tokens)}}
       (h/format-machine-id machine-id)]
      [:span {:style {:color (:text-secondary tokens)}}
       (h/format-state from-state)]
      [:span {:style {:color (:accent tokens)}} "→"]
      [:span {:style {:color (:text-primary tokens) :font-weight 600}}
       (h/format-state to-state)]
      (when event
        [:span {:style {:color (:text-tertiary tokens)
                        :font-size "11px"
                        :margin-left "auto"}}
         (h/format-event event)])]
     (cond
       (nil? definition)
       [:div {:data-testid "rf-xray-machine-focused-event-no-definition"
              :style {:padding "12px"
                      :font-family sans-stack
                      :font-size "11px"
                      :color (:text-tertiary tokens)}}
        "No introspectable definition — chart cannot render."]

       :else
       (let [view-mode @(rf/subscribe
                          [:rf.xray.machine-canvas/view-mode-for machine-id])]
         (case view-mode
           :list
           ;; List view — chrome-thin pseudo-section just rendering a
           ;; tiny banner; the guards/actions/cascade panes that come
           ;; AFTER this block carry the real list payload. The
           ;; view-mode toggle still has to appear in this mode so the
           ;; user can flip back to Canvas — it's tucked into the
           ;; section header with a 'List view' chip.
           [:div {:data-testid "rf-xray-machine-focused-event-list"
                  :data-layout-engine engine
                  :data-machine-id (str machine-id)
                  :data-view-mode "list"
                  :style {:padding "8px 12px"
                          :background (:bg-1 tokens)
                          :border-bottom (str "1px solid " (:border-subtle tokens))
                          :display "flex"
                          :align-items "center"
                          :gap "10px"}}
            (machine-canvas/view-mode-toggle
              {:machine-id machine-id :mode view-mode})
            [:span {:style {:color (:text-tertiary tokens)
                            :font-family sans-stack
                            :font-size "11px"}}
             "Chart hidden in List view — flip to Canvas to inspect the topology."]]

           ;; default — :canvas
           [:div {:data-testid "rf-xray-machine-focused-event-chart"
                  :data-layout-engine engine
                  :data-machine-id (str machine-id)
                  :data-from-highlight-id (or from-id "")
                  :data-to-highlight-id (or to-id "")
                  ;; rf2-qeemm (G3) — surface the focused epoch's fired
                  ;; edge-ids on the canvas wrapper (sorted, space-joined)
                  ;; so the JVM/hiccup suite + hosts pin the wiring without
                  ;; reaching into the xyflow canvas. "" when none fired.
                  :data-fired-edge-ids (str/join
                                         " " (sort (set fired-edge-ids)))
                  :data-view-mode "canvas"
                  ;; rf2-zdfbm — fill the section's available height so the
                  ;; topology chart (`machine-canvas/Chart` is `height
                  ;; 100%`) expands into the panel rather than collapsing
                  ;; to its 260px min. `flex 1` + `min-height 0` lets the
                  ;; chart grow inside the flex-column section; the
                  ;; min-height floor keeps xyflow's non-zero-parent-
                  ;; height requirement satisfied when the panel is short.
                  :style {:padding "12px"
                          :background (:bg-1 tokens)
                          :overflow "hidden"
                          :flex "1 1 0"
                          :min-height "320px"
                          :display "flex"
                          :flex-direction "column"
                          ;; position-relative so the after-rings overlay
                          ;; can absolute-position itself over the chart SVG.
                          :position "relative"}}
            ;; rf2-y3l8z — the chart is now wrapped in an interactive
            ;; viewport adapter (zoom/pan/fit + view-mode toggle +
            ;; controls toolbar). The adapter owns the after-rings
            ;; overlay so they stay co-located with the canvas.
            [machine-canvas/Chart
             {:definition         definition
              :machine-id         machine-id
              :from-highlight     from-state
              :to-highlight       to-state
              ;; rf2-qeemm (G3) — the focused epoch's traversed edges paint
              ;; the FIRED treatment on the live chart (canonical ids from
              ;; `extract-fired-edge-ids`, attached to the section record).
              :fired-edge-ids     fired-edge-ids
              :on-state-click     (fn [path]
                                    (rf/dispatch
                                      [:rf.xray/machine-state-clicked
                                       {:machine-id machine-id
                                        :path       path}]
                                      {:frame :rf/xray}))
              :show-after-rings?  true}]])))
     (guards-list guards)
     (actions-list actions)
     ;; rf2-59e7k — Cancellation cascade inline (per machine). The
     ;; SidePanel reg-view short-circuits to nil when the focused
     ;; machine has no cancellation in the trace window, so the mount
     ;; is dormant in the common case.
     [cancellation-cascade/SidePanel]]))

;; ---- prev/next nav (per-machine epoch walking) -------------------------

(defn- prev-next-nav
  "Inline prev/next buttons for the currently-focused machine. Walks
  the epoch history to the prior / next epoch that ALSO touched the
  focused machine. Disabled when no machine is in scope."
  [machine-id]
  (when machine-id
    [:div {:data-testid "rf-xray-machine-inspector-prev-next-nav"
           :data-machine-id (str machine-id)
           :style {:display "flex"
                   :align-items "center"
                   :gap "6px"
                   :margin-left "auto"}}
     [:button
      {:data-testid "rf-xray-machine-inspector-prev"
       :on-click    (fn [_]
                      (rf/dispatch [:rf.xray/machine-focus-prev]
                                   {:frame :rf/xray}))
       :title       (str "Previous event touching " (h/format-machine-id machine-id))
       :style       {:background "transparent"
                     :border (str "1px solid " (:border-default tokens))
                     :color (:accent tokens)
                     :font-family sans-stack
                     :font-size "11px"
                     :padding "3px 10px"
                     :border-radius "10px"
                     :cursor "pointer"}}
      "◀ Prev"]
     [:button
      {:data-testid "rf-xray-machine-inspector-next"
       :on-click    (fn [_]
                      (rf/dispatch [:rf.xray/machine-focus-next]
                                   {:frame :rf/xray}))
       :title       (str "Next event touching " (h/format-machine-id machine-id))
       :style       {:background "transparent"
                     :border (str "1px solid " (:border-default tokens))
                     :color (:accent tokens)
                     :font-family sans-stack
                     :font-size "11px"
                     :padding "3px 10px"
                     :border-radius "10px"
                     :cursor "pointer"}}
      "Next ▶"]]))

;; ---- focused-event view + blank state ----------------------------------

(defn- focused-event-view
  "Top-level focused-event lens. Reads the
  `:rf.xray/machine-transitions-for-focused-event` composite sub.
  Returns nil when no machine transitioned in the focused event's
  cascade — the panel renders the blank state in that case."
  []
  (let [records @(rf/subscribe
                   [:rf.xray/machine-transitions-for-focused-event])]
    (when (seq records)
      (into [:div {:data-testid "rf-xray-machine-focused-event"
                   :data-section-count (count records)
                   ;; rf2-zdfbm — fill the focused-event host so each
                   ;; per-machine section (`flex 1`) can grow its topology
                   ;; chart into the panel's available height.
                   :style {:display "flex"
                           :flex-direction "column"
                           :flex 1
                           :min-height 0}}]
            (for [rec records]
              (with-meta (focused-event-section rec)
                {:key (str (:machine-id rec) "-"
                           (:id rec) "-"
                           (:from-state rec) "-"
                           (:to-state rec))}))))))

(defn- blank-state
  "Rendered when the focused event has no machine activity in its
  cascade. The Dynamic Machines panel is **event-driven only** (Mike's
  2026-05-19 redesign, panel docstring §4-15): it is silent-by-default
  (rf2-g3ghh) when the focused event triggered no machine transition.

  This is a TRULY BLANK affordance — not a topology render. The earlier
  rf2-t5wp9 (#1757) variant rendered one per-machine topology section
  here, which contradicted the event-driven-only contract and produced
  the inverted visibility bug (rf2-zdfbm): content surfaced on
  non-machine events, drowning the panel's lens job. The most-recent-
  known-state topology view belongs to the future Static Machines
  surface (rf2-r4nao), not the Dynamic lens."
  []
  [:div {:data-testid "rf-xray-machine-inspector-blank"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "13px"
                 :flex 1
                 :display "flex"
                 :flex-direction "column"
                 :align-items "center"
                 :justify-content "center"
                 :text-align "center"}}
   [:p {:style {:margin "0 0 6px 0" :font-size "14px"}}
    "No machine activity in the focused event."]
   [:p {:style {:margin 0 :font-size "12px" :color (:text-tertiary tokens)}}
    "Select an event that triggered a transition to inspect machines."]])

;; ---- empty state (no machines registered at all) -----------------------

(defn- empty-state
  "Rendered when `(rf/machines)` returns nothing — either the host
  app has not yet called `reg-machine`, or `day8/re-frame2-machines`
  is not on the classpath."
  []
  [:div {:data-testid "rf-xray-machine-inspector-empty"
         :style {:padding "16px"
                 :color (:text-tertiary tokens)
                 :font-family sans-stack
                 :font-size "13px"}}
   [:p {:style {:margin "0 0 8px 0"}}
    "No machines registered."]
   [:p {:style {:margin 0 :font-size "12px"}}
    "Register a machine with "
    [:code {:style {:font-family mono-stack :color (:accent tokens)}}
     "rf/reg-machine"]
    " to populate this panel."]])

;; ---- share button -------------------------------------------------------

(defn- share-button
  "Top-right Share button in the panel toolbar."
  []
  [:button
   {:data-testid "rf-xray-machine-inspector-share-button"
    :on-click    (fn [_]
                   (rf/dispatch [:rf.xray/share-modal-open] {:frame :rf/xray}))
    :title       "Share this view (URL with focus + mode + scrubber)"
    :style       {:background "transparent"
                  :border (str "1px solid " (:border-default tokens))
                  :color (:accent tokens)
                  :font-family sans-stack
                  :font-size "11px"
                  :font-weight 600
                  :padding "4px 12px"
                  :border-radius "10px"
                  :cursor "pointer"
                  :white-space "nowrap"}}
   "⤴ Share"])

;; ---- public view --------------------------------------------------------

(rf/reg-view Panel
  "The Machine Inspector panel's root view. Event-driven: BLANK when
  the focused event has no machine activity; one section per machine
  when it does. The header carries the Share button + the per-machine
  prev/next nav (when a machine is in scope)."
  []
  (let [{:keys [empty-kind]} @(rf/subscribe [:rf.xray/machine-inspector-data])
        records @(rf/subscribe [:rf.xray/machine-transitions-for-focused-event])
        ;; The first record's machine-id drives the prev/next nav (a
        ;; cascade may touch multiple machines; the nav's "this machine"
        ;; is the head section's machine — same default-focus pattern
        ;; the cascade SidePanel uses).
        scope-machine-id (some-> records first :machine-id)]
    [:section {:data-testid "rf-xray-machine-inspector"
               :data-view-mode "focused-event"
               :data-has-records (str (boolean (seq records)))
               :style {:height         "100%"
                       :display        "flex"
                       :flex-direction "column"
                       :background     (:bg-2 tokens)
                       :color          (:text-primary tokens)
                       :font-family    sans-stack
                       :font-size      "14px"}}
     [:header {:data-testid "rf-xray-machine-inspector-header"
               :style {:padding "16px 16px 8px 16px"
                       :display "flex"
                       :align-items "center"
                       :justify-content "space-between"
                       :gap "12px"}}
      ;; rf2-6xezz — Mike-direction 2026-05-21: the large h1 "Machine
      ;; inspector" heading is scrubbed; the L4 tab strip is the
      ;; panel-name source-of-truth. The header row keeps the nav +
      ;; share affordances on the right.
      [:div]
      (when (not= :no-machines empty-kind)
        [:div {:style {:display "flex"
                       :align-items "center"
                       :gap "8px"}}
         (prev-next-nav scope-machine-id)
         (share-button)])]
     (cond
       (= :no-machines empty-kind)
       (empty-state)

       (seq records)
       ;; rf2-zdfbm — flex column so the focused-event view fills the
       ;; host and the topology chart grows into the panel height.
       [:div {:data-testid "rf-xray-machine-inspector-focused-event-host"
              :style {:flex 1
                      :overflow "auto"
                      :display "flex"
                      :flex-direction "column"}}
        (focused-event-view)]

       :else
       (blank-state))]))

;; ---- registration entry --------------------------------------------------

(defn install!
  "Idempotent install for the Machine Inspector panel's Xray-side
  registrations. Post-collapse (rf2-y9xmf) the panel registers:

    - the per-machine projection composite (`:rf.xray/machine-inspector-data`)
    - the focused-event lens composite (`:rf.xray/machine-transitions-for-focused-event`)
    - the per-machine prev/next nav events
    - the scrubber-position slot (kept for share-URL compatibility;
      the scrubber UI is gone but the slot round-trips through share)
    - the rings install (`:after` countdown ring overlay)
    - the share-affordance install

  rf2-r4nao moved the Sim engine + UI into
  `static.machines.sim` — installed via
  `static.machines.panel/install!` further down the registry."
  []
  ;; Registered-machine vector (reads `(rf/machines)`).
  (rf/reg-sub :rf.xray/registered-machines
    (fn [db _query]
      (let [ov (get db :registered-machines-override)]
        (or ov
            (try (vec (rf/machines))
                 (catch :default _ []))))))

  (rf/reg-event-db :rf.xray/set-registered-machines-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :registered-machines-override)
        (assoc db :registered-machines-override ov))))

  ;; The live snapshots map for every registered machine.
  (rf/reg-sub :rf.xray/machine-snapshots
    :<- [:rf.xray/target-frame-db]
    (fn [target-frame-db _query]
      (when (map? target-frame-db)
        (get target-frame-db :rf/machines {}))))

  (rf/reg-sub :rf.xray/machine-snapshots-override
    (fn [db _query]
      (get db :machine-snapshots-override)))

  (rf/reg-event-db :rf.xray/set-machine-snapshots-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :machine-snapshots-override)
        (assoc db :machine-snapshots-override ov))))

  ;; The registered-machine-definition map for every machine.
  (rf/reg-sub :rf.xray/machine-definitions-override
    (fn [db _query]
      (get db :machine-definitions-override)))

  (rf/reg-sub :rf.xray/machine-definitions
    :<- [:rf.xray/registered-machines]
    :<- [:rf.xray/machine-definitions-override]
    (fn [[machines override] _query]
      (or override
          (into {}
                (keep (fn [id]
                        (let [m (try (rf/machine-meta id)
                                     (catch :default _ nil))]
                          (when m [id m]))))
                (or machines [])))))

  (rf/reg-event-db :rf.xray/set-machine-definitions-override-for-test
    (fn [db [_ ov]]
      (if (nil? ov)
        (dissoc db :machine-definitions-override)
        (assoc db :machine-definitions-override ov))))

  ;; The user's per-panel machine selection (kept as a slot for the
  ;; Sim engine + share-URL round-trip; the collapsed Dynamic panel
  ;; itself drives focus off the event lens, not the picker slot).
  (rf/reg-sub :rf.xray/selected-machine-id
    (fn [db _query]
      (get db :selected-machine-id)))

  ;; The per-panel composite — one read produces every slot the panel
  ;; consumes. Kept post-collapse so callers (after-rings, share, sim)
  ;; that read `:selected-id` / `:empty-kind` keep working without
  ;; touching their wiring.
  (rf/reg-sub :rf.xray/machine-inspector-data
    :<- [:rf.xray/registered-machines]
    :<- [:rf.xray/machine-snapshots]
    :<- [:rf.xray/machine-snapshots-override]
    :<- [:rf.xray/machine-definitions]
    :<- [:rf.xray/trace-buffer]
    :<- [:rf.xray/selected-machine-id]
    :<- [:rf.xray/target-frame]
    (fn [[machines live-snapshots snapshots-override definitions buffer selected-id target-frame]
         _query]
      (let [snapshots (or snapshots-override live-snapshots {})]
        (h/project-data
          machines snapshots definitions buffer selected-id target-frame))))

  ;; ---- focused-event lens composite (rf2-a9cke) ------------------

  (rf/reg-sub :rf.xray/machine-transitions-for-focused-event
    :<- [:rf.xray/focus]
    :<- [:rf.xray/epoch-history]
    :<- [:rf.xray/machine-definitions]
    (fn [[focus history definitions] _query]
      (let [record (h/focused-epoch-record history focus)
            events (when record (:trace-events record))]
        ;; rf2-qeemm (G3) — attach the focused epoch's fired-edge-ids to
        ;; each per-machine section. `extract-fired-edge-ids` (B7,
        ;; canonical) mints the SAME edge-ids the live chart mints off the
        ;; same definition, so the set lands on real chart edges. The view
        ;; threads it into `MachineChart` so the traversed arms paint the
        ;; FIRED treatment — every microstep / guard-fork candidate the
        ;; from/to lens cannot reach.
        (mapv (fn [{:keys [machine-id definition] :as rec}]
                (assoc rec :fired-edge-ids
                       (trace-state/extract-fired-edge-ids
                         definition events machine-id)))
              (h/project-focused-event-transitions events definitions)))))

  ;; Test-only overrides for the focused-event composite.
  (rf/reg-event-db :rf.xray/set-epoch-history-for-test
    (fn [db [_ history]]
      (if (nil? history)
        (dissoc db :epoch-history)
        (assoc db :epoch-history (vec history)))))

  (rf/reg-event-db :rf.xray/set-focus-epoch-id-for-test
    (fn [db [_ epoch-id]]
      (if (nil? epoch-id)
        (update db :focus dissoc :epoch-id)
        (update db :focus (fnil assoc {}) :epoch-id epoch-id))))

  ;; ---- Machine Inspector panel events -----------------------------

  (rf/reg-event-db :rf.xray/select-machine-id
    (fn [db [_ machine-id]]
      (assoc db :selected-machine-id machine-id)))

  (rf/reg-event-db :rf.xray/clear-machine-selection
    (fn [db _event]
      (dissoc db :selected-machine-id)))

  (rf/reg-event-db :rf.xray/machine-state-clicked
    (fn [db [_ _payload]]
      db))

  (rf/reg-event-db :rf.xray/machine-chart-layout-pulse
    (fn [db _event]
      (update db :machine-inspector/elk-pulse-tick (fnil inc 0))))

  ;; ---- per-machine prev/next nav (rf2-y9xmf) ---------------------

  ;; Walk the epoch-history to the prior / next epoch that ALSO
  ;; touched the focused machine. The focused-event lens picks the
  ;; head section's machine-id as scope; these events filter
  ;; epoch-history for that machine + step the spine's focus.
  (letfn [(epoch-touches-machine? [epoch machine-id]
            (some (fn [ev]
                    (and (h/transition-event? ev)
                         (= machine-id (h/machine-id-of ev))))
                  (or (:trace-events epoch) [])))
          (scope-machine-id [db]
            (let [history (vec (or (get db :epoch-history) []))
                  focus   (get db :focus)
                  record  (h/focused-epoch-record history focus)
                  events  (when record (:trace-events record))
                  records (h/project-focused-event-transitions events nil)]
              (or (some-> records first :machine-id)
                  (get db :selected-machine-id))))
          (step-focus [db direction]
            (let [history (vec (or (get db :epoch-history) []))
                  mid     (scope-machine-id db)
                  current (get-in db [:focus :epoch-id])
                  cur-idx (or (some (fn [[i r]]
                                      (when (= (:epoch-id r) current) i))
                                    (map-indexed vector history))
                              (dec (count history)))
                  step    (case direction :prev dec :next inc)
                  match?  (fn [r] (epoch-touches-machine? r mid))
                  pred    (case direction
                            :prev #(neg? %)
                            :next #(>= % (count history)))]
              (loop [i (step cur-idx)]
                (cond
                  (or (nil? mid) (pred i))
                  db

                  (match? (nth history i))
                  (update db :focus (fnil assoc {}) :epoch-id
                          (:epoch-id (nth history i)))

                  :else (recur (step i))))))]
    (rf/reg-event-db :rf.xray/machine-focus-prev
      (fn [db _event] (step-focus db :prev)))

    (rf/reg-event-db :rf.xray/machine-focus-next
      (fn [db _event] (step-focus db :next))))

  ;; ---- scrubber-position slot (share-URL compatibility) ----------

  ;; The scrubber UI is gone (rf2-y9xmf), but the slot survives because
  ;; share.cljs / share_modal.cljs round-trip the position through the
  ;; share URL. Reads default to `:present`. The companion `set-scrubber-
  ;; position` event keeps the contract bidirectional.
  (rf/reg-sub :rf.xray/machine-scrubber-position
    (fn [db _query]
      (get db :machine-inspector/scrubber-position :present)))

  (rf/reg-event-db :rf.xray/set-scrubber-position
    (fn [db [_ position]]
      (cond
        (= :present position)
        (assoc db :machine-inspector/scrubber-position :present)

        (integer? position)
        (assoc db :machine-inspector/scrubber-position position)

        (nil? position)
        (assoc db :machine-inspector/scrubber-position :present)

        :else db)))

  ;; ---- Sim engine ------------------------------------------------
  ;;
  ;; rf2-r4nao — Sim engine + UI rehosted under
  ;; `static.machines.sim` (event/sub family renamed to
  ;; `:rf.xray.static.machines/sim-*`). The Dynamic Machine Inspector
  ;; no longer installs Sim; the Static Machines panel does. See
  ;; `static.machines.panel/install!`.

  ;; ---- `:after` countdown rings (rf2-7hwwe) ---------------------
  (after-rings/install!)

  ;; ---- Interactive viewport adapter (rf2-y3l8z) -----------------
  (machine-canvas/install!)

  ;; ---- Share affordance (rf2-nqw0v) -----------------------------
  (share/install!)

  ;; rf2-2moh1 — register the Dynamic Machines tab with the internal L4
  ;; tab registry.
  (panel-registry/reg-l4-tab!
    {:id    :machines
     ;; rf2-ad7zx.10 — Figma App labels the Dynamic L4 tab "Machine"
     ;; (singular · the focused-epoch lens is on ONE machine's topology).
     ;; The internal id stays `:machines` (mnemonic + routing unchanged).
     :label "Machine"
     :mnem  "m"
     :modes #{:dynamic}
     :order 4
     :panel Panel}))
