(ns panel-gallery.gallery-views
  "Story coverage for the **Reactive tab** of the Causa chrome
  (rf2-wyvf2 · spec/021 §3 · renamed from Views per §11.5).

  The Reactive tab body is the `reactive-panel/Panel` view. It reads
  `:rf.causa/reactive-data`, a composite over the spine's
  `:rf.causa/focus` + the focused cascade's `:trace-events`. Each
  variant seeds the variant frame's `:epoch-history` directly with a
  vector of synthetic epoch records carrying `:trace-events` shaped
  per the substrate trace ops landed in PRs #1728 + #1729."
  (:require [re-frame.story :as story]
            [panel-gallery.panel-views :as panel-views]))

(defn register-gallery-view! []
  (panel-views/register!))

;; ---- pure fixture builders ---------------------------------------------

(defn- trace-event
  [op payload]
  {:operation op
   :payload   payload})

(defn- empty-buffer [] [])

(defn- sparse-cascade-buffer []
  [{:epoch-id     :ep-1
    :dispatch-id  :dispatch-1
    :event        [:checkout/submit {:id 42}]
    :trace-events [(trace-event :rf.sub/computed {:sub-id :cart/state})
                   (trace-event :rf.view/rendered
                                {:view-id :cart.banner/StateBanner
                                 :file    "views/cart/banner.cljs"
                                 :line    14
                                 :caused-by-sub :cart/state})]}])

(defn- dense-cascade-buffer []
  [{:epoch-id     :ep-1
    :dispatch-id  :dispatch-1
    :event        [:checkout/submit {:id 42}]
    :trace-events
    (vec
      (concat
        [(trace-event :rf.sub/computed {:sub-id :cart/state})
         (trace-event :rf.sub/computed {:sub-id :cart/can-submit?})
         (trace-event :rf.sub/computed {:sub-id :cart/items})
         (trace-event :rf.sub/computed {:sub-id :cart/total})
         (trace-event :rf.sub/skipped {:sub-id :user/name
                                       :reason :input-unchanged})
         (trace-event :rf.sub/skipped {:sub-id :cart/eligibility
                                       :reason :input-unchanged})]
        [(trace-event :rf.view/rendered
                      {:view-id :checkout/CheckoutButton
                       :file    "views/checkout.cljs"
                       :line    88
                       :caused-by-sub :cart/can-submit?
                       :caused-by-paths [[:cart :state]]})
         (trace-event :rf.view/rendered
                      {:view-id :cart.banner/StateBanner
                       :file    "views/cart/banner.cljs"
                       :line    14
                       :caused-by-sub :cart/state
                       :caused-by-paths [[:cart :state]]})
         (trace-event :rf.view/rendered
                      {:view-id :cart.totals/TotalsRow
                       :file    "views/cart/totals.cljs"
                       :line    22
                       :caused-by-sub :cart/total
                       :caused-by-paths [[:cart :total]]})]
        [(trace-event :rf.cascade/captured
                      {:subs-ran        4
                       :subs-skipped    2
                       :views-rendered  3
                       :flows-recomputed 0})]))}])

(defn- silent-cascade-buffer []
  [{:epoch-id     :ep-1
    :dispatch-id  :dispatch-1
    :event        [:ping/tick]
    :trace-events [(trace-event :rf.cascade/captured
                                {:subs-ran        0
                                 :subs-skipped    0
                                 :views-rendered  0
                                 :flows-recomputed 0})]}])

(defn register-all!
  "Register the Reactive tab Story surface. Idempotent."
  []
  (story/install-canonical-vocabulary!)
  (register-gallery-view!)

  (story/reg-tag :feature/causa-reactive
    {:axis :feature
     :doc  "Causa Reactive tab — sub-cascade + view-re-render
            visualisation per spec/021 §3 (rf2-wyvf2)."})

  (story/reg-story :story.causa.reactive
    {:doc        "Visual gallery of the Causa Reactive tab under
                 varying cascade magnitude. Each variant seeds the
                 variant frame's :epoch-history directly with
                 synthetic epoch records carrying :trace-events."
     :component  :panel-gallery.reactive/Panel
     :tags       #{:dev :feature/causa-reactive}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.reactive/empty
    {:doc        "No epochs in history. Panel renders the
                 no-cascade empty-state."
     :events     [[:rf.causa/sync-epoch-history (empty-buffer)]]
     :tags       #{:dev :state/empty}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.reactive/sparse
    {:doc        "Single sub recomputed + single view re-rendered.
                 Pins the resting-density render."
     :events     [[:rf.causa/sync-epoch-history (sparse-cascade-buffer)]]
     :tags       #{:dev :state/small}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.reactive/dense
    {:doc        "Mid-load: 4 subs ran · 2 skipped · 3 views
                 re-rendered + a :rf.cascade/captured aggregate.
                 The dense-but-typical cascade."
     :events     [[:rf.causa/sync-epoch-history (dense-cascade-buffer)]]
     :tags       #{:dev :state/medium}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.reactive/silent
    {:doc        "Sparse case from spec/021 §3.2 — the epoch's db
                 change touched no subscribed paths. Panel shows
                 the 'no reactive cascade' empty body."
     :events     [[:rf.causa/sync-epoch-history (silent-cascade-buffer)]]
     :tags       #{:dev :state/special}
     :substrates #{:reagent}})

  (story/reg-workspace :Workspace.causa.reactive/all
    {:doc      "All four Reactive tab variants in one auto-grid."
     :layout   :variants-grid
     :story    :story.causa.reactive
     :columns  2
     :tags     #{:dev}}))

(register-all!)
