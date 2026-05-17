(ns panel-gallery.fixtures-views
  "Pure fixture builders for the Causa Views tab gallery
  (rf2-sszlr — rebuild for new 6-tab Causa shape).

  The Views panel (rf2-21ob3; spec/012-Views.md) reads its data from
  `:rf.causa/views-data`, a composite over
  `:rf.causa/views-focused-cascade-pair` (per
  `views_subs.cljs`), which in turn reads the spine's `:rf.causa/focus`
  + the current frame's `:epoch-history`.

  Each variant seeds the variant frame's `:epoch-history` via
  `:rf.causa/sync-epoch-history` — the same canonical seed event the
  App-db panel uses. The panel walks `:renders` (and the prior
  cascade's `:renders` for unmount derivation) + `:sub-runs` off
  each epoch record. No additional events required — the spine's
  default `:live` mode auto-focuses on the head cascade.

  ## Epoch-record `:renders` shape (per Spec-Schemas
  §`:rf/epoch-record`)

      {:render-key   [<view-id> <instance-token>]
       :triggered-by <sub-id-or-nil>
       :elapsed-ms   <ms>}

  ## Epoch-record `:sub-runs` shape

      {:sub-id       <kw>
       :recomputed?  <bool>
       :elapsed-ms   <ms>}

  Mount/unmount derivation is structural (per
  `views_helpers.cljc` §Mount/unmount derivation under v1
  instrumentation): instance-token diff against the prior cascade's
  renders.")

;; ---- per-render builders ------------------------------------------------

(defn- render-row
  "Build one `:renders` entry."
  ([view-id instance-token]
   (render-row view-id instance-token nil 1))
  ([view-id instance-token triggered-by elapsed-ms]
   (cond-> {:render-key [view-id instance-token]
            :elapsed-ms elapsed-ms}
     triggered-by (assoc :triggered-by triggered-by))))

(defn- sub-row
  "Build one `:sub-runs` entry."
  ([sub-id recomputed?]
   (sub-row sub-id recomputed? 1))
  ([sub-id recomputed? elapsed-ms]
   {:sub-id      sub-id
    :recomputed? recomputed?
    :elapsed-ms  elapsed-ms}))

(defn- epoch
  "Build a minimal `:rf/epoch-record` carrying just the slots the Views
  panel reads — `:renders`, `:sub-runs`, `:event-id`, `:dispatch-id`,
  `:trigger-event`, `:frame`."
  [{:keys [epoch-id event-id dispatch-id event renders sub-runs frame]}]
  (cond-> {:epoch-id      (or epoch-id dispatch-id)
           :dispatch-id   dispatch-id
           :event-id      event-id
           :trigger-event event
           :renders       (vec (or renders []))
           :sub-runs      (vec (or sub-runs []))
           :committed-at  (* 1000 (or epoch-id dispatch-id 0))
           :db-before     {}
           :db-after      {}
           :trace-events  []}
    frame (assoc :frame frame)))

;; ---- buffer builders ---------------------------------------------------

(defn empty-buffer
  "No epochs — panel renders the 'no cascades' / empty-state copy."
  []
  [])

(defn sparse-subs-buffer
  "Two epochs with two views each + minimal sub-runs. Pins the panel's
  sparse-resting render shape."
  []
  [(epoch
     {:epoch-id 100 :dispatch-id 100 :event-id :counter/inc
      :event [:counter/inc]
      :renders  [(render-row :counter/badge :tok-1 :counter/value 1)
                 (render-row :app/root :tok-root nil 2)]
      :sub-runs [(sub-row :counter/value true 1)]})
   (epoch
     {:epoch-id 101 :dispatch-id 101 :event-id :counter/dec
      :event [:counter/dec]
      :renders  [(render-row :counter/badge :tok-1 :counter/value 1)
                 (render-row :app/root :tok-root nil 2)]
      :sub-runs [(sub-row :counter/value true 1)]})])

(defn dense-subs-buffer
  "One epoch with ~30 views + ~15 sub-runs. Pins the panel's render
  with comfortable mid-load density."
  []
  (let [renders  (->> (for [i (range 30)]
                        (render-row (keyword "ui" (str "row-" i))
                                    (keyword (str "tok-" i))
                                    (keyword "data" (str "item-" (mod i 10)))
                                    (mod (inc i) 8)))
                      vec)
        sub-runs (->> (for [i (range 15)]
                        (sub-row (keyword "data" (str "item-" i))
                                 (odd? i)
                                 (mod (inc i) 4)))
                      vec)
        prior    (->> (for [i (range 30)]
                        (render-row (keyword "ui" (str "row-" i))
                                    (keyword (str "tok-" i))
                                    (keyword "data" (str "item-" (mod i 10)))
                                    (mod (inc i) 8)))
                      vec)]
    [(epoch {:epoch-id 200 :dispatch-id 200 :event-id :ui/scroll
             :event [:ui/scroll {:y 100}]
             :renders prior
             :sub-runs sub-runs})
     (epoch {:epoch-id 201 :dispatch-id 201 :event-id :ui/scroll
             :event [:ui/scroll {:y 200}]
             :renders renders
             :sub-runs sub-runs})]))

(defn grid-explosion-buffer
  "One epoch whose render list contains ~80 grid cells sharing the
  same `(view-id, triggered-by)` tuple — pushes past the default
  cluster-threshold (50) so the panel collapses them into one
  aggregate row with the `× N` summary."
  []
  (let [grid-renders (->> (for [i (range 80)]
                            (render-row :grid/cell
                                        (keyword (str "cell-tok-" i))
                                        :grid/data
                                        (mod (inc i) 5)))
                          vec)]
    [(epoch {:epoch-id 300 :dispatch-id 300 :event-id :grid/data-loaded
             :event [:grid/data-loaded]
             :renders  grid-renders
             :sub-runs [(sub-row :grid/data true 14)]})]))

(defn heatmap-mode-buffer
  "Two epochs with renders spanning multiple view-ids carrying a wide
  spread of :elapsed-ms — exercises the heatmap mode's segment bar
  (sorted by total-ms, components under 1% folded into `<rest>`)."
  []
  (let [hot     (for [i (range 6)]
                  (render-row :report/heavy-chart
                              (keyword (str "chart-tok-" i))
                              :report/data
                              (* (inc i) 15)))
        warm    (for [i (range 4)]
                  (render-row :report/table-row
                              (keyword (str "row-tok-" i))
                              :report/data
                              (* (inc i) 4)))
        cold    (for [i (range 30)]
                  (render-row (keyword "tiny" (str "leaf-" i))
                              (keyword (str "leaf-tok-" i))
                              :report/data
                              1))]
    [(epoch {:epoch-id 400 :dispatch-id 400 :event-id :report/initial
             :event [:report/initial]
             :renders  (vec (concat hot warm cold))
             :sub-runs [(sub-row :report/data true 6)]})
     (epoch {:epoch-id 401 :dispatch-id 401 :event-id :report/refresh
             :event [:report/refresh]
             :renders  (vec (concat hot warm cold))
             :sub-runs [(sub-row :report/data true 6)]})]))

(defn three-group-buffer
  "Two epochs designed to surface the panel's three groups (Mounted /
  Re-rendered / Unmounted) all populated simultaneously:

    - Prior cascade had `:cart/header` + `:cart/list` + `:cart/footer`.
    - Current cascade has `:cart/header` (re-rendered) + `:cart/list`
      (re-rendered) — `:cart/footer` is gone (unmounted) — plus
      `:cart/promo` (newly mounted)."
  []
  (let [prior-renders   [(render-row :cart/header :hdr-tok :auth/user 2)
                         (render-row :cart/list   :list-tok :cart/items 5)
                         (render-row :cart/footer :ftr-tok :cart/total 1)]
        current-renders [(render-row :cart/header :hdr-tok :auth/user 2)
                         (render-row :cart/list   :list-tok :cart/items 8)
                         (render-row :cart/promo  :promo-tok nil 1)]]
    [(epoch {:epoch-id 500 :dispatch-id 500 :event-id :cart/initial
             :event [:cart/initial]
             :renders prior-renders
             :sub-runs [(sub-row :auth/user true 1)
                        (sub-row :cart/items true 2)
                        (sub-row :cart/total true 1)]})
     (epoch {:epoch-id 501 :dispatch-id 501 :event-id :cart/promo-enabled
             :event [:cart/promo-enabled]
             :renders current-renders
             :sub-runs [(sub-row :auth/user false 0)
                        (sub-row :cart/items true 2)
                        (sub-row :cart/total false 0)]})]))

(defn filter-applied-buffer
  "Two epochs with renders across multiple view-ids; pairs with a
  component-filter seed that pins the panel rendering only the
  `:cart/list` slices."
  []
  (let [renders [(render-row :cart/header :hdr-tok :auth/user 1)
                 (render-row :cart/list   :list-tok :cart/items 8)
                 (render-row :cart/list   :list-tok-2 :cart/items 6)
                 (render-row :app/nav     :nav-tok :route/active 2)
                 (render-row :app/footer  :footer-tok :app/version 1)]]
    [(epoch {:epoch-id 600 :dispatch-id 600 :event-id :cart/add
             :event [:cart/add :apple]
             :renders renders
             :sub-runs [(sub-row :cart/items true 2)
                        (sub-row :auth/user false 0)]})
     (epoch {:epoch-id 601 :dispatch-id 601 :event-id :cart/remove
             :event [:cart/remove :apple]
             :renders renders
             :sub-runs [(sub-row :cart/items true 1)]})]))
