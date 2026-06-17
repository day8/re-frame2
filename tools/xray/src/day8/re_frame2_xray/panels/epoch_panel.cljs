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
            [day8.re-frame2-xray.host-registry :as host-registry]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.panels.epoch.projection :as proj]
            [day8.re-frame2-xray.panels.epoch.view :as view]
            [day8.re-frame2-xray.panels.shared.focus-resolver :as focus]))

;; The registry resolvers below run INSIDE the
;; `:rf.xray/epoch-pipeline` sub COMPUTATION (threaded into
;; `proj/project-numbered`), and Xray seats in its OWN image-loaded `:rf/xray`
;; frame, so the sub build binds the registrar to Xray's image generation. They
;; therefore read the HOST app's registrar via `host-registry/handler-meta` (the
;; generation-bypassing default-realm form), NOT a bare `(rf/handler-meta …)`: a
;; bare read would resolve the focused event / its interceptors / its cofx
;; declarations through Xray's OWN image (which carries none of the host's), and
;; the INTERCEPTORS + RECORDABLE COEFFECTS steps would silently vanish. See
;; `day8.re-frame2-xray.host-registry`.

;; ---- authored-interceptor resolver (rf2-se9a9t / EP-0022 §11) -------------
;;
;; The pure projection cannot read the registry; it threads this resolver in
;; so the INTERCEPTORS step surfaces an event's AUTHORED interceptor chain
;; (the clean, non-throwing case the exception-only step never showed).
;; Reads `(rf/handler-meta :event event-id)` for the authored `:interceptors`
;; refs and supplies a `resolve-meta-fn` reading `(rf/handler-meta
;; :interceptor id)` for each ref's registered descriptor. Fail-soft: any
;; lookup that throws (unregistered event, runtime that can't answer)
;; degrades to no step rather than crashing the panel render.

(defn resolve-event-interceptors
  "Return `{:entries <authored :interceptors vector> :resolve-meta-fn <fn>}`
  for `event-id`, or nil when the event is not registered / has no chain.
  The projection's `authored-interceptors-step` filters the framework
  auto-wrapper (`:rf/default?`) out of `:entries`, so a handler with no
  authored interceptors yields no step."
  [event-id]
  (when (some? event-id)
    (let [meta    (host-registry/handler-meta :event event-id)
          entries (:interceptors meta)]
      (when (seq entries)
        {:entries         entries
         :resolve-meta-fn (fn [icpt-id]
                            (host-registry/handler-meta :interceptor icpt-id))}))))

;; ---- declared-recordable resolver (rf2-n9v5ga / EP-0017 §9) ---------------
;;
;; The RECORDABLE COEFFECTS lens shows the focused handler's DECLARED
;; RECORDABLE LEAVES (EP-0017 §9; docs/EP-0017 §661-666; spec/009 §155) —
;; the handler's declared inputs (`:rf.cofx/requires`), restricted to the
;; recordable grade — NOT every arbitrary leaf that rode the raw dispatch
;; token (EP-0017 does NOT deliver an undeclared leaf to the handler). The
;; pure projection cannot read the registry, so this resolver lives in the
;; sub (like `resolve-event-interceptors`) and threads the declared id SET
;; into `project-numbered`.
;;
;; The declared recordable set = the bare cofx ids of the event's
;; `:rf.cofx/requires` whose cofx registration is `:recordable?` (an
;; ambient-grade fact runs a supplier and is never recorded, so it is NOT a
;; recordable leaf). `:rf/time-ms` is framework-stamped + always recordable,
;; handled verbatim by the projection independent of this set. A
;; parameterized `[id arg]` requirement delivers under the bare `id`, so we
;; key on the leaf id. Fail-soft: any registry lookup that throws degrades
;; to nil → the projection's show-all fallback rather than crashing.

(defn- requires-leaf-id
  "The bare cofx leaf id of one `:rf.cofx/requires` entry — a keyword
  verbatim, or the head of a parameterized `[id arg]` pair (the fact
  delivers under the bare id, EP-0017 §4)."
  [entry]
  (cond
    (keyword? entry) entry
    (vector? entry)  (first entry)
    :else            nil))

(defn- recordable-cofx-id?
  "True iff `cofx-id` is registered as a RECORDABLE-grade coeffect
  (`:recordable? true` on its `reg-cofx` metadata). `:rf/time-ms` is the
  framework's always-recordable provided fact. Fail-soft on lookup error."
  [cofx-id]
  (or (= cofx-id :rf/time-ms)
      (boolean
        (:recordable? (host-registry/handler-meta :cofx cofx-id)))))

(defn resolve-event-recordables
  "Return the focused event's DECLARED RECORDABLE leaf id SET (EP-0017 §9,
  rf2-n9v5ga): the bare cofx ids of `:rf.cofx/requires` whose cofx
  registration is recordable-grade. nil when the event is unregistered or
  declares no recordable requirements — the projection then falls back to
  its documented show-all behaviour. The RECORDABLE COEFFECTS lens uses this
  set to filter the raw token's `:rf.cofx` leaves down to what the handler
  declared + received, so an undeclared leaf that merely rode the token is
  not falsely presented as a recordable input."
  [event-id]
  (when (some? event-id)
    (let [meta     (host-registry/handler-meta :event event-id)
          requires (:rf.cofx/requires meta)]
      (when (seq requires)
        (let [recordables (into #{}
                                (comp (keep requires-leaf-id)
                                      (filter recordable-cofx-id?))
                                requires)]
          (when (seq recordables)
            recordables))))))

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
            ;; rf2-se9a9t — thread the registry-reading resolver so the
            ;; INTERCEPTORS step surfaces the dispatched event's AUTHORED
            ;; interceptor chain (EP-0022 §11). The projection stays pure;
            ;; the runtime read lives here, in the sub.
            steps          (when record
                             (proj/project-numbered
                               record
                               {:resolve-event-interceptors
                                resolve-event-interceptors
                                ;; rf2-n9v5ga — the declared-recordable
                                ;; resolver: the RECORDABLE COEFFECTS lens
                                ;; filters the raw token's leaves to the
                                ;; handler's declared recordable inputs.
                                :resolve-event-recordables
                                resolve-event-recordables}))]
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
         ;; rf2-x25e0 — the DISPATCH step's `:fx-dispatch` parent-epoch
         ;; link resolves `:parent-dispatch-id → parent epoch-id` off a
         ;; `{dispatch-id → epoch-id}` index the view builds once per
         ;; render from this slice. Pinning `:epoch-history` on the
         ;; composite sub keeps the view side a pure render — no secondary
         ;; sub against `:rf.xray/epoch-history` in the per-row hot path.
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

  (rf/reg-event :rf.xray.epoch/toggle-row-expand
    (fn [{:keys [db]} [_ step-kw row-id]]
      {:db (let [k (vector step-kw row-id)
            current (get db :epoch-panel-expanded-rows #{})]
        (assoc db :epoch-panel-expanded-rows
               (if (contains? current k)
                 (disj current k)
                 (conj current k))))}))

  (rf/reg-event :rf.xray.epoch/clear-row-expand
    (fn [{:keys [db]} _event]
      {:db (dissoc db :epoch-panel-expanded-rows)}))

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

  (rf/reg-event :rf.xray.epoch/set-subs-filter-mode
    (fn [{:keys [db]} [_ mode]]
      {:db (assoc db :epoch-panel-subs-filter-mode mode)}))

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
  ;; The Epoch tab is the master inverse of the other Dynamic tabs:
  ;; the six tabs (Epoch · App-db · Views · Trace · Machines ·
  ;; Routing) read in cascade order, and Epoch renders every step
  ;; the other tabs detail as one timeline. (History: Epoch was
  ;; added alongside the then-existing tabs; rf2-5gl5r since retired
  ;; the Handler tab and rf2-gbz39 removed the Issues tab — issues
  ;; surface inline in the Epoch panel + the L2 event-row pink-wash
  ;; + the ribbon.) Epoch sits leftmost at :order -1.

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
