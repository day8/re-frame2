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
  and Routing (6)). This panel is the **canonical** \"what happened
  in this epoch?\" surface — it presents the full timeline as a
  delightful numbered cascade including the reactive trailing edge
  (SUBSCRIPTIONS + VIEWS). The Reactive tab covers a complementary
  axis (per-sub recomputation detail across cascades) and remains
  side-by-side with this panel.

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
         ;; rf2-ahhgn — the TOOL-SIDE outcome (`:ok` / `:error`) derived
         ;; from the projected steps (any step carrying an exception or
         ;; schema violation reads `:error`). Surfaced so the panel
         ;; header signals a failed cascade — the framework epoch-record
         ;; `:outcome` slot stays `:ok` for a recovered handler exception
         ;; by spec, and is NOT the panel's outcome (see
         ;; `projection/epoch-outcome`).
         :outcome        (proj/epoch-outcome steps)
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

  ;; ---- SUBSCRIPTIONS [all][changed][unchanged] filter (rf2-tzmmf) ------
  ;;
  ;; Per Mike pair-debug 2026-05-26 (rf2-tzmmf) the SUBSCRIPTIONS step's
  ;; chrome to the right of the badge is a 3-button bar
  ;; `[all][changed][unchanged]` — directly mirrors the HANDLER step's
  ;; `[diff][all]` toggle bar above (one familiar shape, no second
  ;; design vocabulary to learn). SUPERSEDES the prior rf2-kfh1v
  ;; `Show unchanged` boolean toggle + the badge-adjacent text — both
  ;; are deleted (pre-alpha masterpiece posture, no back-compat shims).
  ;;
  ;; Modes:
  ;;   :changed   (default) — show only recompute rows whose value changed
  ;;   :all                 — show every recompute row
  ;;   :unchanged           — show only recompute rows whose value didn't change
  ;;
  ;; Preference lives on the Xray app-db so it survives focus shifts;
  ;; default is `:changed` because most subs recompute on a cascade
  ;; but report no value change (rf2-kfh1v hide-unchanged-by-default
  ;; rationale is preserved as the default mode).

  (rf/reg-sub :rf.xray.epoch/subs-filter-mode
    (fn [db _query]
      (get db :epoch-panel-subs-filter-mode :changed)))

  (rf/reg-event-db :rf.xray.epoch/set-subs-filter-mode
    (fn [db [_ mode]]
      (assoc db :epoch-panel-subs-filter-mode mode)))

  ;; ---- HANDLER :db + SUBSCRIPTIONS value rendering ---------------------
  ;;
  ;; rf2-vv3m6 (2026-05-29) retired the `[diff][full][full+diff]` mode
  ;; toggle entirely. FULL+DIFF is the single rendering — auto-collapse
  ;; of unchanged subtrees (rf2-fqcdd) + the leaf-scalar `← was X`
  ;; annotation (rf2-fyd8u) + correct add/remove colouring (rf2-9d4j8)
  ;; together gave FULL+DIFF the density that `:diff` used to provide
  ;; and the comparison-context that `:full` lacked, so the three-mode
  ;; toggle (and its sub/event/slot pair per surface) retired.
  ;;
  ;; The two prior installs here —
  ;;   (diff-mode/reg-mode-sub+event! :rf.xray.epoch/db)
  ;;   (diff-mode/reg-mode-sub+event! :rf.xray.epoch/subs-value)
  ;; — are gone; the view-layer renders FULL+DIFF unconditionally.

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
