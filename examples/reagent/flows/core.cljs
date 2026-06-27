(ns flows.core
  "Worked example for flows. Guide: docs/guide/concepts/flows.md
   (glossary: docs/guide/glossary.md#flow).

   A flow is a registered rule: \"when these app-db paths change, run this
   pure function and write the result to that app-db path.\" Each flow runs
   right after the event handler and before the db commit, so the handler's
   change and the fresh flow output land together in one write.

   WHY A FLOW AND NOT A SUB?  Most derived values should be SUBSCRIPTIONS.
   Subs are lighter, live in the per-frame sub-cache, and pay no app-db
   write. Reach for a flow only when the derived value is part of the
   application's STATE:

     - other event handlers read it as plain app-db data (here:
       `:checkout/place-order` reads `[:cart :total]` straight off the db);
     - it should survive SSR hydration, time-travel restore, and app-db
       serialisation (sub-cache contents do not survive the wire);
     - the derivation is stable enough to be worth registering.

   This cart shows three things:

   1. MATERIALISED COMPUTED STATE — `:cart/subtotal` sums the line items
      from `[:cart :items]` and writes the result to `[:cart :subtotal]`.
   2. A TOPOLOGICAL CASCADE — `:cart/total` reads `[:cart :subtotal]`
      (another flow's output) plus `[:cart :discount-rate]`. The runtime
      sorts the two flows so `:cart/subtotal` always runs first; both
      settle in one walk.
   3. RUNTIME-TOGGLEABLE DERIVATION — the discount is a feature gate. The
      `:rf.fx/reg-flow` / `:rf.fx/clear-flow` effects register and clear the
      `:cart/discount-rate` flow from a handler, while the app runs.

   See the guide for the full registration shape, the topological sort, the
   one-event lag, and the failure semantics."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Flows ship in their own artefact. Require re-frame.flows once
            ;; to register the flow API; without it the reg-flow calls below
            ;; raise :rf.error/flows-artefact-missing.
            [re-frame.flows]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; FLOWS
;; ============================================================================
;;
;; `:inputs` is a vector of paths to watch (a bare path reads app-db; a path
;; led by `:rf.db/runtime` reads runtime-db). `:derive` receives the values
;; at those paths positionally and returns the value written to the app-db
;; `:output-path`. Full registration map: docs/guide/concepts/flows.md.

(defn- line-total [{:keys [price qty]}]
  (* price qty))

;; A flow belongs to a frame. `reg-flow` must run inside a frame scope; a
;; bare call raises :rf.error/no-frame-context. This example runs in the
;; :rf/default frame, so name it here. (The runtime-toggleable
;; `:cart/discount-rate` flow is registered later via :rf.fx/reg-flow from a
;; handler, which carries the dispatching frame automatically.)
;; See docs/guide/glossary.md#frame-identity-is-carried-not-found.
(with-frame :rf/default

(rf/reg-flow
  {:id     :cart/subtotal
   :doc    "Sum of every line item's price × qty. Materialised into app-db
            so the checkout handler can read it as plain data."
   :inputs [[:cart :items]]
   :derive (fn [items] (reduce + 0 (map line-total items)))
   :output-path [:cart :subtotal]})

;; `:cart/total` reads ANOTHER flow's output (`[:cart :subtotal]`) plus the
;; discount rate. The runtime derives the dependency edge from the shared
;; path and sorts the flows so `:cart/subtotal` always runs before
;; `:cart/total`; both settle in a single walk.
;;
;; The discount starts off: `:cart/discount-rate` has no flow yet, so its
;; path is nil and this `:derive` treats it as 0% off. The "Apply 10%
;; discount" button registers the flow; "Remove discount" clears it. While
;; registered it derives a flat 10% off the live subtotal. It reads
;; `[:cart :subtotal]`, so the rate stays fresh as the cart changes and a
;; tiered "5% over $50" rule would slot straight in.
(rf/reg-flow
  {:id     :cart/total
   :doc    "Subtotal less the active discount. Reads :cart/subtotal's
            output and the runtime-toggleable discount rate."
   :inputs [[:cart :subtotal] [:cart :discount-rate]]
   :derive (fn [subtotal discount-rate]
             (let [rate (or discount-rate 0)]
               (Math/round (* subtotal (- 1 rate)))))
   :output-path [:cart :total]}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(def ^:private seed-items
  [{:sku "RF2-MUG"   :name "re-frame2 mug"      :price 1800 :qty 1}
   {:sku "RF2-TEE"   :name "Six-domino t-shirt" :price 3200 :qty 2}
   {:sku "RF2-STKR"  :name "Sticker pack"       :price 500  :qty 3}])

(rf/reg-event :cart/initialise
  {:doc "Seed the cart. The :cart/subtotal and :cart/total flows fire right
         after this handler and materialise their outputs in the same write."}
  (fn handler-cart-initialise [{:keys [db]} _]
    {:db (assoc db :cart {:items seed-items})}))

(rf/reg-event :cart/inc-qty
  {:doc    "Bump a line item's quantity. Touching [:cart :items] re-fires
            :cart/subtotal (its input changed), which in turn re-fires
            :cart/total — the cascade settles in one walk."
   :schema [:cat [:= :cart/inc-qty] :string]}
  (fn handler-cart-inc-qty [{:keys [db]} [_ sku]]
    {:db (update-in db [:cart :items]
               (fn [items]
                 (mapv (fn [item]
                         (cond-> item
                           (= sku (:sku item)) (update :qty inc)))
                       items)))}))

(rf/reg-event :cart/dec-qty
  {:doc    "Drop a line item's quantity (min 1)."
   :schema [:cat [:= :cart/dec-qty] :string]}
  (fn handler-cart-dec-qty [{:keys [db]} [_ sku]]
    {:db (update-in db [:cart :items]
               (fn [items]
                 (mapv (fn [item]
                         (cond-> item
                           (= sku (:sku item)) (update :qty #(max 1 (dec %)))))
                       items)))}))

;; Runtime-toggleable derivation. The discount flow is registered / cleared
;; while the app runs via the two reserved effects. Both carry the
;; dispatching frame automatically.
;;
;; THE ONE-EVENT LAG: a flow registered via `:rf.fx/reg-flow` first runs on
;; the NEXT event — its initial output appears one event after registration.
;; So after registering the discount-rate flow we dispatch a no-op
;; `:cart/touch` whose only job is to trigger another walk: by the time it
;; runs, the new flow is in the registry, so that walk materialises
;; `[:cart :discount-rate]` (and the dependent `[:cart :total]`) at the
;; discounted figure. `:cart/touch` writes nothing; the walk itself drives
;; the recompute.
;; Guide: docs/guide/concepts/flows.md#toggling-a-derivation-at-runtime.
(rf/reg-event :cart/apply-discount
  {:doc "Engage the 10%-off feature gate by registering a flow that writes
         the discount rate, then nudge a re-walk so :cart/total recomputes."}
  (fn handler-cart-apply-discount [_ _]
    {:fx [[:rf.fx/reg-flow
           {:id     :cart/discount-rate
            :doc    "The active discount rate (feature-gated; only present
                     while the discount is engaged). Reads the live subtotal
                     so the rule has somewhere to grow; today it is a flat
                     10% off."
            :inputs [[:cart :subtotal]]
            :derive (fn [_subtotal] 0.10)
            :output-path [:cart :discount-rate]}]
          [:dispatch [:cart/touch]]]}))

(rf/reg-event :cart/remove-discount
  {:doc "Disengage the feature gate. `:rf.fx/clear-flow` removes the flow and
         clears its [:cart :discount-rate] output back to nil, so :cart/total
         recomputes to the full price on the next walk."}
  (fn handler-cart-remove-discount [_ _]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]
          [:dispatch [:cart/touch]]]}))

;; No-op event. It returns the db unchanged; its only job is to make one
;; more walk happen on this frame. On that walk the flows re-run with the
;; just-(de)registered discount flow now in the registry, materialising the
;; one-event-lagged output. The toggle is the `:rf.fx/reg-flow` /
;; `:rf.fx/clear-flow` itself; this event is the walk that surfaces it.
(rf/reg-event :cart/touch
  {:doc "No-op. Exists only to trigger the walk that materialises the
         one-event-lagged discount flow output."}
  (fn handler-cart-touch [{:keys [db]} _] {:db db}))

;; Reading a flow's output inside an event handler. This handler reads
;; [:cart :total] as plain app-db data. This is the central reason the total
;; is a flow and not a sub: a sub's value lives in the view-facing sub-cache,
;; which a handler can't reach.
(rf/reg-event :checkout/place-order
  {:doc "Place the order. Reads the materialised :cart/total straight off
         app-db — the flow output IS ordinary application state."}
  (fn handler-checkout-place-order [{:keys [db]} _]
    (let [total (get-in db [:cart :total])]
      {:fx [[:flows.demo/order-placed total]]})))

(rf/reg-fx :flows.demo/order-placed
  {:doc       "Demo-only confirmation. A real app would POST the order."
   :platforms #{:client}}
  (fn fx-order-placed [_ total]
    (js/alert (str "Order placed — total $" (.toFixed (/ total 100) 2)))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; Flows publish no framework subs. A flow's output is ordinary application
;; state at a known path — consumers read it through whatever sub they like
;; over the flow's `:output-path`. The view below reads the materialised
;; :subtotal / :total via plain subs.

(rf/reg-sub :cart/items
  (fn [db _] (get-in db [:cart :items])))

(rf/reg-sub :cart/subtotal
  {:doc "Reads the :cart/subtotal flow's output at its :output-path. A plain
         app-db read, like any sub."}
  (fn [db _] (get-in db [:cart :subtotal])))

(rf/reg-sub :cart/total
  (fn [db _] (get-in db [:cart :total])))

(rf/reg-sub :cart/discount-active?
  {:doc "True while the discount feature gate is engaged. The rate flow's
         output path is nil when the flow is cleared."}
  (fn [db _] (some? (get-in db [:cart :discount-rate]))))

;; ============================================================================
;; VIEW
;; ============================================================================

(defn- money [cents]
  (str "$" (.toFixed (/ cents 100) 2)))

(reg-view cart-line [{:keys [sku name price qty]}]
  [:tr {:data-testid (str "cart-line-" sku)}
   [:td name]
   [:td (money price)]
   [:td
    [:button {:on-click #(dispatch [:cart/dec-qty sku])} "−"]
    [:span {:style {:margin "0 0.6em"} :data-testid (str "qty-" sku)} qty]
    [:button {:on-click #(dispatch [:cart/inc-qty sku])} "+"]]
   [:td (money (* price qty))]])

(reg-view cart-app []
  (let [items            @(subscribe [:cart/items])
        subtotal         @(subscribe [:cart/subtotal])
        total            @(subscribe [:cart/total])
        discount-active? @(subscribe [:cart/discount-active?])]
    [:div.cart-app
     [:h1 "Cart — flows materialise the totals"]
     [:table
      [:thead [:tr [:th "Item"] [:th "Price"] [:th "Qty"] [:th "Line total"]]]
      (into [:tbody]
            (for [item items]
              ^{:key (:sku item)} [cart-line item]))]
     [:dl.cart-totals
      [:dt "Subtotal"] [:dd {:data-testid "cart-subtotal"} (money subtotal)]
      (when discount-active?
        [:<> [:dt "Discount"] [:dd "−10%"]])
      [:dt "Total"] [:dd {:data-testid "cart-total"} (money total)]]
     [:div.cart-actions
      (if discount-active?
        [:button {:data-testid "remove-discount"
                  :on-click #(dispatch [:cart/remove-discount])}
         "Remove discount"]
        [:button {:data-testid "apply-discount"
                  :on-click #(dispatch [:cart/apply-discount])}
         "Apply 10% discount"])
      [:button {:data-testid "place-order"
                :on-click #(dispatch [:checkout/place-order])}
       "Place order"]]]))

;; ============================================================================
;; MOUNT
;; ============================================================================

;; The React root is held in an atom and created lazily inside `run`, not at
;; ns-load. ns-load must produce no DOM side effects, so co-required example
;; namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

;; `frame-provider` sets up the frame at the render root. On first mount it
;; creates the frame and runs its `:initial-events` once to seed it (here
;; `[:cart/initialise]`); on hot reload it reuses the frame and skips the
;; seed. Wrapping the render also makes the `reg-view`-injected
;; `dispatch`/`subscribe` resolve to this frame; a render with no provider
;; raises :rf.error/no-frame-context. The id is `:rf/default` — an ordinary
;; frame id, the same one the `with-frame` flow registrations use above.
(def app-frame :rf/default)

(defn run []
  ;; Install the Reagent adapter so frames render through React.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:cart/initialise]]}
                 [cart-app]])))
