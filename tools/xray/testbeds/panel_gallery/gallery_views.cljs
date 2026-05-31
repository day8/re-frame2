(ns panel-gallery.gallery-views
  "Story coverage for the **Reactive tab** of the Xray chrome
  (rf2-wyvf2 · spec/021 §3 · renamed from Views per §11.5; render-cause
  refresh rf2-bhi3t).

  The Reactive tab body is the `reactive-panel/Panel` view. It reads
  `:rf.xray/reactive-data`, a composite over the spine's
  `:rf.xray/focus` + the focused epoch record's `:sub-runs` /
  `:renders` structured projections + its raw `:trace-events`. Each
  variant seeds the variant frame's `:epoch-history` directly with a
  vector of synthetic epoch records.

  ## Record shape the panel reads (per spec/018 + reactive_panel_subs)

  `project-record` reads:

    - `:sub-runs`     — `[{:sub-id :query-v :recomputed? :value-changed?
                           :value :prev-value} ...]`; drives the L1/L2
                        sub tables + the `changed-set` that classifies
                        each view's render reason.
    - `:trace-events` — the view-side capture ops the flow graph reads:
                        `:rf.view/rendered` (with `:rf.view/id` /
                        `:rf.view/render-key` / `:rf.view/mount?` /
                        `:rf.view/deref-subs` / `:rf.view/triggered-by`
                        / `:rf.view/elapsed-ms`) + `:rf.view/unmounted`
                        + `:rf.sub/dispose`.
    - `:event`        — the trigger event (the cascade's seed).

  ## Render-cause chips (rf2-bhi3t)

  A view re-renders for exactly one of two reasons — a SUBSCRIPTION it
  derefs changed value, or its PROPS changed (the orthogonal
  `:rf/props` channel). The view-node's cause sub-label attributes
  which: `← :sub-id` when the render carries a `:rf.view/triggered-by`
  cause sub, `← props` on a re-render whose own derefed subs all held
  value (so the cause is the props channel). A mount carries no cause
  (the `(mounted)` label conveys the first render). The
  `render-cause` variant below pins all three states side-by-side."
  (:require [re-frame.story :as story]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

;; ---- pure fixture builders ----------------------------------------------
;;
;; The fixtures build `:rf/epoch-record`-shaped maps. The panel reads
;; the structured `:sub-runs` projection (for the L1/L2 sub tables +
;; the changed-set) and the raw `:trace-events` (for the flow-graph
;; view nodes + their render-cause chips). Each builder mirrors the
;; substrate emit-site shape `reactive_panel_subs` reads.

(defn- sub-run
  "One `:sub-runs` entry. `recomputed?` splits genuine recomputes
  (subs-ran) from memo-hit skips (subs-skipped); `value-changed?`
  feeds the per-view render-reason `changed-set`."
  [sub-id recomputed? value-changed? prev-value value]
  {:sub-id         sub-id
   :query-v        [sub-id]
   :recomputed?    recomputed?
   :value-changed? value-changed?
   :prev-value     prev-value
   :value          value})

(defn- rendered-ev
  "A `:rf.view/rendered` trace event in the shape `view-rows` reads.
  `:rf.view/mount?` true → a mount (no cause); false → a re-render.
  `triggered-by` (a sub-id) drives the `← :sub-id` cause chip; omit it
  on a re-render to drive the `← props` chip (the props channel)."
  ([view-id render-key mount? deref-subs elapsed-ms]
   (rendered-ev view-id render-key mount? deref-subs elapsed-ms nil))
  ([view-id render-key mount? deref-subs elapsed-ms triggered-by]
   {:operation :rf.view/rendered
    :tags      (cond-> {:rf.view/id         view-id
                        :rf.view/render-key render-key
                        :rf.view/mount?     mount?
                        :rf.view/deref-subs deref-subs
                        :rf.view/elapsed-ms elapsed-ms}
                 (some? triggered-by)
                 (assoc :rf.view/triggered-by triggered-by))}))

(defn- unmounted-ev
  "A `:rf.view/unmounted` teardown trace event — drives a view-row's
  `:unmount` action + the UNMOUNTED VIEWS section."
  [view-id render-key]
  {:operation :rf.view/unmounted
   :tags      {:rf.view/id view-id :rf.view/render-key render-key}})

(defn- dispose-ev
  "A `:rf.sub/dispose` teardown trace event — drives the DESTROYED
  SUBSCRIPTIONS section."
  [sub-id]
  {:operation :rf.sub/dispose
   :tags      {:rf.sub/id sub-id :rf.sub/query-v [sub-id]}})

(defn- record
  "Wrap a one-record `:epoch-history` (the focus-resolver head-fallback
  resolves to `(peek epoch-history)`)."
  [{:keys [event sub-runs trace-events]}]
  [{:epoch-id     1
    :dispatch-id  1
    :event        event
    :sub-runs     (vec sub-runs)
    :trace-events (vec trace-events)}])

(defn- empty-buffer [] [])

(defn- sparse-cascade-buffer
  "Single sub recomputed (changed) + single view re-rendered, caused by
  that sub — the resting-density render. The view node reads
  `← :cart/state`."
  []
  (record
    {:event    [:checkout/submit {:id 42}]
     :sub-runs [(sub-run :cart/state true true {:phase :idle} {:phase :submitting})]
     :trace-events
     [(rendered-ev :cart.banner/StateBanner [:StateBanner 0] false
                   [[:cart/state]] 0.6 :cart/state)]}))

(defn- dense-cascade-buffer
  "Mid-load: 4 subs ran (2 changed) · 2 memo-hit skips · 3 views
  re-rendered. The view nodes exercise the full render-cause taxonomy
  (rf2-bhi3t): two `← :sub-id` causes + one `← props` re-render."
  []
  (record
    {:event [:checkout/submit {:id 42}]
     :sub-runs
     [(sub-run :cart/state       true  true  {:phase :idle} {:phase :submitting})
      (sub-run :cart/can-submit? true  true  false true)
      (sub-run :cart/items       true  false [{:id :apple}] [{:id :apple}])
      (sub-run :cart/total       true  false 195 195)
      (sub-run :user/name        false false "Ada" "Ada")
      (sub-run :cart/eligibility false false true true)]
     :trace-events
     [(rendered-ev :checkout/CheckoutButton [:CheckoutButton 0] false
                   [[:cart/can-submit?]] 0.9 :cart/can-submit?)
      (rendered-ev :cart.banner/StateBanner [:StateBanner 0] false
                   [[:cart/state]] 0.7 :cart/state)
      ;; A re-render whose own derefed subs ALL held value — the cause
      ;; is the props channel, so the node reads `← props` (rf2-bhi3t).
      (rendered-ev :cart.totals/TotalsRow [:TotalsRow 0] false
                   [[:cart/total]] 0.5)]}))

(defn- render-cause-buffer
  "Pins all three render-cause states side-by-side (rf2-bhi3t):
   - a MOUNT (no cause; the `(mounted)` label conveys the first render)
   - a SUBSCRIPTION-driven re-render (`← :route/current`)
   - a PROPS-driven re-render (`← props` — its derefed sub held value)."
  []
  (record
    {:event [:route/change :dashboard]
     :sub-runs
     [(sub-run :route/current true true :checkout :dashboard)
      (sub-run :user/prefs    true false {:theme :dark} {:theme :dark})]
     :trace-events
     [;; mount — first render of the new route's page; no cause chip.
      (rendered-ev :app.dashboard/Page [:Page 0] true
                   [[:route/current]] 1.1)
      ;; subscription-driven re-render — its deref of :route/current
      ;; changed value → `← :route/current`.
      (rendered-ev :app.nav/Breadcrumb [:Breadcrumb 0] false
                   [[:route/current]] 0.4 :route/current)
      ;; props-driven re-render — :user/prefs held value, so none of the
      ;; view's own subs changed → `← props`.
      (rendered-ev :app.dashboard/Toolbar [:Toolbar 0] false
                   [[:user/prefs]] 0.3)]}))

(defn- teardown-cascade-buffer
  "A route change tears down two views + disposes two subs while one
  new view mounts. Exercises the UNMOUNTED VIEWS + DESTROYED
  SUBSCRIPTIONS sections alongside a mount."
  []
  (record
    {:event [:route/change :reports]
     :sub-runs
     [(sub-run :route/current true true :checkout :reports)]
     :trace-events
     [(rendered-ev :app.reports/Page [:Page 0] true [[:route/current]] 1.0)
      (unmounted-ev :app.checkout/Modal       [:Modal 0])
      (unmounted-ev :app.sidebar/CheckoutItem [:CheckoutItem 0])
      (dispose-ev :checkout/total)
      (dispose-ev :checkout/line-items)]}))

(defn- silent-cascade-buffer
  "Sparse case from spec/021 §3.2 — the epoch's db change touched no
  subscribed paths, so no sub recomputed and no view re-rendered."
  []
  (record
    {:event        [:ping/tick]
     :sub-runs     []
     :trace-events []}))

(defn register-all!
  "Register the Reactive tab Story surface. Idempotent."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/xray-reactive
    {:axis :feature
     :doc  "Xray Reactive tab — sub-cascade + view-re-render
            visualisation per spec/021 §3 (rf2-wyvf2); per-view
            render-cause attribution (rf2-bhi3t)."})

  (story/reg-story :story.xray.reactive
    {:doc        "Visual gallery of the Xray Reactive tab under
                 varying cascade magnitude + render-cause shape. Each
                 variant seeds the variant frame's :epoch-history
                 directly with a synthetic epoch record carrying
                 :sub-runs + :trace-events; the flow graph paints one
                 node per sub / view with the per-view render-cause
                 chip (← :sub-id / ← props)."
     :component  :panel-gallery.reactive/Panel
     :tags       #{:dev :feature/xray-reactive}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.reactive/empty
    {:doc        "No epochs in history. Panel renders the
                 no-cascade empty-state."
     :events     [[:rf.xray/sync-epoch-history (empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.reactive/sparse
    {:doc        "Single sub recomputed (changed) + single view
                 re-rendered caused by that sub. Pins the
                 resting-density render — the view node reads
                 `← :cart/state`."
     :events     [[:rf.xray/sync-epoch-history (sparse-cascade-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.reactive/dense
    {:doc        "Mid-load: 4 subs ran (2 changed) · 2 memo-hit skips ·
                 3 views re-rendered. The view nodes exercise the full
                 render-cause taxonomy (rf2-bhi3t) — two `← :sub-id`
                 causes + one `← props` re-render."
     :events     [[:rf.xray/sync-epoch-history (dense-cascade-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  ;; ----- render-cause chips (rf2-bhi3t) ------------------------------
  (story/reg-variant :story.xray.reactive/render-cause
    {:doc        "All three render-cause states side-by-side (rf2-bhi3t):
                 a MOUNT (no cause — the `(mounted)` label), a
                 SUBSCRIPTION-driven re-render (`← :route/current`), and
                 a PROPS-driven re-render (`← props`, its derefed sub
                 held value so the cause is the orthogonal props
                 channel)."
     :events     [[:rf.xray/sync-epoch-history (render-cause-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  ;; ----- teardown sections -------------------------------------------
  (story/reg-variant :story.xray.reactive/teardown
    {:doc        "Route change: one new view mounts while two views
                 unmount + two subs dispose. Exercises the UNMOUNTED
                 VIEWS + DESTROYED SUBSCRIPTIONS sections (spec/021
                 §3.2) beneath the live cascade graph."
     :events     [[:rf.xray/sync-epoch-history (teardown-cascade-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-variant :story.xray.reactive/silent
    {:doc        "Sparse case from spec/021 §3.2 — the epoch's db
                 change touched no subscribed paths. Panel shows
                 the 'no reactive cascade' empty body."
     :events     [[:rf.xray/sync-epoch-history (silent-cascade-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-workspace :Workspace.xray.reactive/all
    {:doc      "All six Reactive tab variants in one auto-grid — empty /
                sparse / dense / render-cause / teardown / silent. The
                render-cause + dense variants pin the rf2-bhi3t per-view
                cause chips (← :sub-id / ← props)."
     :layout   :variants-grid
     :story    :story.xray.reactive
     :columns  2
     :tags     #{:dev}}))

(register-all!)
