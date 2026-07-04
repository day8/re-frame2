(ns flows.core
  "Worked example for flows. Guide: docs/core/concepts/flows.md
   (glossary: docs/core/glossary.md#flow).

   A flow is a standing rule: \"when these app-db paths change, run this pure
   function and write its result to that app-db path.\" Each flow fires right
   after the event handler and before the db commit, so the handler's change
   and the fresh flow output land together in a single write.

   So when do you reach for a flow instead of a subscription? Almost never,
   honestly — most derived values want to be subs. Subs are lighter, live in
   the per-frame sub-cache, and cost no app-db write. A flow earns its keep
   only when the derived value is genuinely part of the application's *state*:

     - other event handlers read it as plain app-db data (here
       `:checkout/place-order` reads `[:cart :total]` straight off the db);
     - it has to survive SSR hydration, time-travel restore, and app-db
       serialisation (the sub-cache doesn't make it across the wire);
     - the derivation is stable enough to be worth naming and registering.

   This cart is a little three-act play for flows:

   1. Computed state, materialised — `:cart/subtotal` sums the line items at
      `[:cart :items]` and writes the result back to `[:cart :subtotal]`.
   2. A cascade, sorted for you — `:cart/total` reads `[:cart :subtotal]`
      (another flow's output) and `[:cart :discount-rate]`. The runtime sees
      the shared path, sorts the two so `:cart/subtotal` always runs first,
      and both settle in one walk.
   3. A derivation you can toggle at runtime — the discount is a feature
      gate. The `:rf.fx/reg-flow` / `:rf.fx/clear-flow` effects register and
      clear the `:cart/discount-rate` flow from a handler, mid-flight, while
      the app is running.

   The guide has the full registration map, the topological sort, the
   one-event lag, and the failure semantics."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Flows live in their own artefact, so you opt in. Requiring
            ;; re-frame.flows once wires up the flow API; leave it out and the
            ;; reg-flow calls below raise :rf.error/flows-artefact-missing.
            [re-frame.flows]
            [re-frame.adapter.reagent :as reagent-adapter]))

;; ============================================================================
;; FLOWS
;; ============================================================================
;;
;; A flow registration is just data. `:inputs` lists the paths to watch (a
;; bare path reads app-db; a path led by `:rf.db/runtime` reads runtime-db).
;; `:derive` gets the values at those paths, in order, and returns the value
;; written to `:output-path`. That's the whole contract; the full map is in
;; docs/core/concepts/flows.md.

(defn- line-total [{:keys [price qty]}]
  (* price qty))

;; Every flow belongs to a frame, so `reg-flow` needs a frame in scope; call
;; it bare and you'll get :rf.error/no-frame-context for your trouble. This
;; example lives in the :rf/default frame, so we name it once here. (The
;; toggleable `:cart/discount-rate` flow comes later, registered via
;; :rf.fx/reg-flow from inside a handler — that route carries the dispatching
;; frame along for you, no naming required.)
;; See docs/core/glossary.md#frame-identity-is-carried-not-found.
(rf/with-frame :rf/default

(rf/reg-flow :cart/subtotal
  {:doc    "Every line item's price × qty, summed. Materialised into app-db
            so the checkout handler can read it as ordinary data."
   :inputs [[:cart :items]]
   :output-path [:cart :subtotal]}
  (fn [items] (reduce + 0 (map line-total items))))

;; Here's the cascade. `:cart/total` reads another flow's output
;; (`[:cart :subtotal]`) alongside the discount rate. The runtime spots that
;; shared path, works out that `:cart/subtotal` feeds `:cart/total`, and runs
;; them in that order — both settle in a single walk. You never wire the
;; ordering by hand; the dependency graph falls out of the paths.
;;
;; The discount starts switched off. `:cart/discount-rate` has no flow yet,
;; so its path reads nil and this `:derive` treats that as 0% off. "Apply 10%
;; discount" registers the flow; "Remove discount" clears it. While it's
;; live, it derives a flat 10% off the current subtotal. And because it reads
;; `[:cart :subtotal]`, the rate stays fresh as the cart changes — a tiered
;; "5% over $50" rule would drop straight in.
(rf/reg-flow :cart/total
  {:doc    "Subtotal less the active discount. Reads :cart/subtotal's output
            and the discount rate you can toggle at runtime."
   :inputs [[:cart :subtotal] [:cart :discount-rate]]
   :output-path [:cart :total]}
  (fn [subtotal discount-rate]
    (let [rate (or discount-rate 0)]
      (Math/round (* subtotal (- 1 rate)))))))

;; ============================================================================
;; EVENTS
;; ============================================================================

(def ^:private seed-items
  [{:sku "RF2-MUG"   :name "re-frame2 mug"      :price 1800 :qty 1}
   {:sku "RF2-TEE"   :name "Six-domino t-shirt" :price 3200 :qty 2}
   {:sku "RF2-STKR"  :name "Sticker pack"       :price 500  :qty 3}])

(rf/reg-event :cart/initialise
  {:doc "Seed the cart. The :cart/subtotal and :cart/total flows fire on the
         heels of this handler and materialise their outputs in the same
         write — so the totals exist before the first render."}
  (fn handler-cart-initialise [{:keys [db]} _]
    {:db (assoc db :cart {:items seed-items})}))

(rf/reg-event :cart/inc-qty
  {:doc    "Bump a line item's quantity. Touching [:cart :items] re-fires
            :cart/subtotal (its input just changed), which re-fires
            :cart/total in turn — the whole cascade settles in one walk."
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

;; Now the toggleable derivation. The discount flow gets registered and
;; cleared while the app runs, through the two reserved effects — and both
;; carry the dispatching frame along for you.
;;
;; There's one wrinkle worth knowing: the one-event lag. A flow registered
;; via `:rf.fx/reg-flow` doesn't run during the event that registers it; it
;; first runs on the *next* event. Its opening output is always one event
;; behind. So right after registering the discount-rate flow, we dispatch a
;; do-nothing `:cart/touch`. By the time it runs the new flow is in the
;; registry, so that walk finally materialises `[:cart :discount-rate]` (and
;; the `[:cart :total]` that depends on it) at the discounted figure.
;; `:cart/touch` writes nothing — the walk it triggers does all the work.
;; Guide: docs/core/concepts/flows.md#toggling-a-derivation-at-runtime.
(rf/reg-event :cart/apply-discount
  {:doc "Engage the 10%-off feature gate: register a flow that writes the
         discount rate, then nudge a re-walk so :cart/total recomputes."}
  (fn handler-cart-apply-discount [_ _]
    {:fx [[:rf.fx/reg-flow
           [:cart/discount-rate
            {:doc    "The active discount rate — feature-gated, so it only
                      exists while the discount is engaged. It reads the live
                      subtotal so the rule has room to grow; for now it's a
                      flat 10% off."
             :inputs [[:cart :subtotal]]
             :output-path [:cart :discount-rate]}
            (fn [_subtotal] 0.10)]]
          [:dispatch [:cart/touch]]]}))

(rf/reg-event :cart/remove-discount
  {:doc "Disengage the feature gate. `:rf.fx/clear-flow` removes the flow and
         `dissoc-in`s its [:cart :discount-rate] output path (per spec 013 —
         the key is removed, not set to nil), so :cart/total climbs back to
         full price on the next walk."}
  (fn handler-cart-remove-discount [_ _]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]
          [:dispatch [:cart/touch]]]}))

;; The humble no-op. It hands the db straight back, unchanged; its entire
;; purpose in life is to make one more walk happen on this frame. On that
;; walk the flows re-run with the just-(de)registered discount flow now in
;; the registry, and the one-event-lagged output finally surfaces. The real
;; work is the `:rf.fx/reg-flow` / `:rf.fx/clear-flow`; this event is just
;; the walk that makes it show up.
(rf/reg-event :cart/touch
  {:doc "No-op. Exists only to trigger the walk that surfaces the
         one-event-lagged discount flow output."}
  (fn handler-cart-touch [{:keys [db]} _] {:db db}))

;; And here's the payoff — a handler reading a flow's output. This one reads
;; [:cart :total] as plain app-db data, and that is the whole reason the
;; total is a flow and not a sub. A sub's value sits in the view-facing
;; sub-cache, where a handler simply can't reach it. A flow puts the value in
;; app-db, where everyone can.
(rf/reg-event :checkout/place-order
  {:doc "Place the order. Reads the materialised :cart/total straight off
         app-db — the flow's output is just ordinary application state."}
  (fn handler-checkout-place-order [{:keys [db]} _]
    (let [total (get-in db [:cart :total])]
      {:fx [[:flows.demo/order-placed total]]})))

(rf/reg-fx :flows.demo/order-placed
  {:doc       "Demo-only confirmation popup. A real app would POST the order
               somewhere; we just say hello."
   :platforms #{:client}}
  (fn fx-order-placed [_ total]
    (js/alert (str "Order placed — total $" (.toFixed (/ total 100) 2)))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; Flows don't hand you any subs of their own. A flow's output is just
;; application state at a known path, so you read it through whatever sub you
;; fancy pointed at the `:output-path`. The view below pulls the materialised
;; :subtotal / :total through plain, unremarkable subs.

(rf/reg-sub :cart/items
  (fn [db _] (get-in db [:cart :items])))

(rf/reg-sub :cart/subtotal
  {:doc "Reads the :cart/subtotal flow's output at its :output-path. Nothing
         special — a plain app-db read, like any other sub."}
  (fn [db _] (get-in db [:cart :subtotal])))

(rf/reg-sub :cart/total
  (fn [db _] (get-in db [:cart :total])))

(rf/reg-sub :cart/discount-active?
  {:doc "True while the discount feature gate is engaged. When the flow is
         cleared its output path reads nil, so a present value means it's on."}
  (fn [db _] (some? (get-in db [:cart :discount-rate]))))

;; ============================================================================
;; VIEW
;; ============================================================================

(defn- money [cents]
  (str "$" (.toFixed (/ cents 100) 2)))

(rf/reg-view cart-line [{:keys [sku name price qty]}]
  [:tr {:data-testid (str "cart-line-" sku)}
   [:td name]
   [:td (money price)]
   [:td
    [:button {:on-click #(dispatch [:cart/dec-qty sku])} "−"]
    [:span {:style {:margin "0 0.6em"} :data-testid (str "qty-" sku)} qty]
    [:button {:on-click #(dispatch [:cart/inc-qty sku])} "+"]]
   [:td (money (* price qty))]])

(rf/reg-view cart-app []
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

;; We stash the React root in an atom and create it lazily inside `run`,
;; never at ns-load. Loading a namespace should touch no DOM, so that two
;; co-required example namespaces don't both race to slap `create-root` onto
;; the shared `#app`.
(defonce react-root (atom nil))

;; `frame-provider` stands the frame up at the render root. On first mount it
;; creates the frame and runs its `:initial-events` once to seed it (here
;; `[:cart/initialise]`); on hot reload it reuses the frame as-is and skips
;; the seed, so your live state survives a save. Wrapping the render is also
;; what lets the `reg-view`-injected `dispatch`/`subscribe` find this frame —
;; render without a provider and they raise :rf.error/no-frame-context. The
;; id is `:rf/default`, an ordinary frame id, the very same one the
;; `with-frame` flow registrations use up top.
(def app-frame :rf/default)

(defn run []
  ;; Tell the runtime to render through Reagent. This picks the substrate; it
  ;; doesn't create a frame — that's frame-provider's job below.
  (rf/init! reagent-adapter/adapter)
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider {:id app-frame
                                    :initial-events [[:cart/initialise]]}
                 [cart-app]])))
