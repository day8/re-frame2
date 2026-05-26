(ns day8.re-frame2-xray.panels.epoch-panel
  "Epoch panel orchestrator (rf2-sc3r1) — registers the composite sub
  + the per-row toggle event + the L4 tab entry. The view layer lives
  in `panels.epoch.view`; the pure-data projection lives in
  `panels.epoch.projection`.

  ## What this panel answers

  > **\"What happened in this epoch?\"**

  A single epoch's complete computational timeline as a numbered
  vertical cascade — dispatch → coeffects → handler → flow → fx →
  subscriptions → views. The pipeline is a faithful projection of
  the focused epoch's `:trace-events`; each step is conditional, so
  the view renders only the steps that actually fired.

  ## Tab placement

  Registered against `:dynamic` mode at order 5 (between Machines (4)
  and Routing (6)). Co-exists with the existing Event lens (the
  `:event` tab) initially — both surface the focused epoch, but
  through different lenses:

  - **Event** (`:event` tab) — the Figma-locked operational-order
    handling pipeline (rf2-ynnre B+); reads more like a
    spec-shaped attribution document.
  - **Epoch** (`:epoch` tab — this panel) — the full timeline as a
    delightful numbered cascade including the reactive trailing
    edge (SUBSCRIPTIONS + VIEWS) that the Event lens routes to its
    own Reactive tab.

  Per the bead body's pre-alpha posture, this co-existence is the
  initial landing; the older Event/Reactive split deprecates as a
  follow-on bead once the new Epoch panel is exercised in
  production.

  ## Frame integration

  The composite sub joins `:rf.xray/focus` + `:rf.xray/epoch-history`
  via the shared `panels.shared.focus-resolver` so the panel pivots on
  the same focus axis every other L4 panel honours. Per spec/018 §6
  the spine's `:rf.xray/focus` carries `:epoch-id`; the focus-resolver
  classifies the status (`:no-focus / :focused / :epoch-evicted`) and
  resolves the matching record."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.epoch.view :as view]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]))

;; ---- public Panel surface ------------------------------------------------

;; Re-export the view-side `Panel` so the spine + panel-registry
;; resolve a single name. `mount-fns` (panels.cljs) reach through this
;; ns when added to the per-panel mount inventory.
(def Panel view/Panel)

;; ---- registration --------------------------------------------------------

(defn install!
  "Idempotent install for the Epoch panel:

    - `:rf.xray/epoch-pipeline` composite sub (focus + history →
      projection rows + focus-status)
    - `:rf.xray.epoch/expanded-rows` sub
    - `:rf.xray.epoch/toggle-row-expand` event
    - `:rf.xray.epoch/clear-row-expand` event
    - L4 tab registration (`:epoch`, mnem `e`, order 5)

  The composite sub joins the spine's `:rf.xray/focus` with the
  framework's `:rf.xray/epoch-history` through the shared focus-
  resolver, then runs the pure projection from
  `panels.epoch.projection/project-numbered` to produce the ordered
  + numbered step rows. The view subscribes only to this composite;
  the projection is fully testable in isolation."
  []
  ;; ---- composite sub -----------------------------------------------------
  ;;
  ;; Shape:
  ;;
  ;;     {:status   :no-focus | :focused | :epoch-evicted
  ;;      :epoch-id <int-or-nil>      ; for view chrome
  ;;      :record   <:rf/epoch-record map or nil>
  ;;      :steps    [<step-row> ...]} ; the numbered pipeline
  ;;
  ;; The view branches on `:status` for the empty-state lines and
  ;; renders `:steps` when present.
  (rf/reg-sub :rf.xray/epoch-pipeline
    :<- [:rf.xray/focus]
    :<- [:rf.xray/epoch-history]
    (fn [[focus epoch-history] _query]
      (let [focus-epoch-id (:epoch-id focus)
            status         (focus/resolve-focus-status focus-epoch-id
                                                       epoch-history)
            record         (focus/find-epoch-record focus-epoch-id
                                                    epoch-history)
            steps          (when record (proj/project-numbered record))]
        {:status         status
         :epoch-id       (or focus-epoch-id (:epoch-id record))
         :record         record
         ;; rf2-yx1ae — the CHILD-DISPATCHES section's view resolves
         ;; child epoch-ids via `find-child-epoch` against this cascade's
         ;; `:dispatch-id` + the epoch-history. Pinning them on the
         ;; composite sub keeps the view side a pure render — no
         ;; secondary sub against `:rf.xray/epoch-history` in the
         ;; per-row hot path.
         :dispatch-id    (:dispatch-id record)
         :epoch-history  epoch-history
         :steps          (vec (or steps []))})))

  ;; ---- per-row expand state ---------------------------------------------
  ;;
  ;; The view supports per-row drill-down by toggling a `[step-kw row-id]`
  ;; pair in the expanded-set. State lives on the Xray app-db so toggles
  ;; survive sub-recomputes + integrate naturally with time-travel.
  ;; The current view doesn't render expansion UI for every row (the
  ;; design prefers always-visible content for the cascade's punch),
  ;; but the surface is in place so follow-on rich expansions
  ;; (full-handler source code, full-fx EDN, etc.) compose without
  ;; reshaping the panel.

  (rf/reg-sub :rf.xray.epoch/expanded-rows
    (fn [db _query]
      (get db :epoch-panel-expanded-rows #{})))

  (rf/reg-event-db :rf.xray.epoch/toggle-row-expand
    (fn [db [_ step-kw row-id]]
      (let [k (vector step-kw row-id)
            current (get db :epoch-panel-expanded-rows #{})]
        (assoc db :epoch-panel-expanded-rows
               (if (contains? current k)
                 (disj current k)
                 (conj current k))))))

  (rf/reg-event-db :rf.xray.epoch/clear-row-expand
    (fn [db _event]
      (dissoc db :epoch-panel-expanded-rows)))

  ;; ---- SUBSCRIPTIONS show-unchanged toggle (rf2-kfh1v) -----------------
  ;;
  ;; Per the bead body the SUBSCRIPTIONS step hides unchanged-input rows
  ;; by default — N rows of mostly-unchanged subs is noisy. The operator
  ;; opts in to see all rows via this flag (mirrors Chrome devtools'
  ;; network panel's filter toggle). Slot lives on the Xray app-db so the
  ;; preference survives focus shifts.

  (rf/reg-sub :rf.xray.epoch/subs-show-unchanged?
    (fn [db _query]
      (boolean (get db :epoch-panel-subs-show-unchanged?))))

  (rf/reg-event-db :rf.xray.epoch/toggle-subs-show-unchanged
    (fn [db _event]
      (update db :epoch-panel-subs-show-unchanged? not)))

  ;; ---- HANDLER :db view-mode toggle (pair-debug 2026-05-26) -------------
  ;;
  ;; The HANDLER step's `:db` sub-section carries a `[diff][all]` button
  ;; bar. `:diff` (default) renders only the path-changes the handler
  ;; produced; `:all` renders the full post-cascade app-db via the
  ;; edn-inspector. Mode is persisted so the operator's preference
  ;; survives focus shifts.

  (rf/reg-sub :rf.xray.epoch/db-view-mode
    (fn [db _query]
      (get db :epoch-panel-db-view-mode :diff)))

  (rf/reg-event-db :rf.xray.epoch/set-db-view-mode
    (fn [db [_ mode]]
      (assoc db :epoch-panel-db-view-mode mode)))

  ;; ---- L4 tab registration ----------------------------------------------
  ;;
  ;; The Epoch tab lands between Machines (order 4) and Routing
  ;; (order 6). The previous gap at order 5 was reserved for exactly
  ;; this surface (the "what happened in this epoch" canonical view);
  ;; the existing seven tabs (Handler 0 · App-DB 1 · Reactive 2 ·
  ;; Trace 3 · Machines 4 · Routing 6 · Issues 7) read in cascade
  ;; order, and the new Epoch tab is the master inverse — every
  ;; step the other tabs detail, rendered as one timeline.
  ;;
  ;; Per the bead body's worker-decision: co-exist initially. A
  ;; follow-on bead retires the older tabs as the new Epoch panel
  ;; matures.

  (panel-registry/reg-l4-tab!
    {:id    :epoch
     :label "Epoch"
     :mnem  "e"
     :modes #{:dynamic}
     ;; -1 places Epoch leftmost (before Handler's :order 0). Mike's
     ;; pair-debug call 2026-05-26: the cascade-pipeline view is the
     ;; primary "what just happened" surface; it belongs first.
     :order -1
     :panel Panel}))
