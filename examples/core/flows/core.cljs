(ns flows.core
  "Worked example for flows. Guide: docs/core/flows.md
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
   3. A discount that is just data — the discount rate is an ordinary app-db
      value that two events set and remove. `:cart/total` reads it like any
      other input, so one click is one event, and the total that depends on
      the rate lands in that event's own commit.

   Both flows are top-level registrations, like the events and subs below,
   and the frame runs them because its `:flows` config names them (MOUNT,
   further down). The guide has the full registration map, the topological
   sort, and the failure semantics."
  (:require [re-frame.core :as rf]
            ;; Flows live in their own artefact, so you opt in. Requiring
            ;; re-frame.flows once wires up the flow API; leave it out and a
            ;; frame naming flows in its `:flows` config raises
            ;; :rf.error/flows-artefact-missing when it is made.
            [re-frame.flows]
            ;; Turns on Malli validation, so the `:schema` keys the two
            ;; quantity events declare below actually run. Leave it out and the
            ;; router has no `:schemas/validate-event!` hook to call, so those
            ;; declarations are inert — the events validate nothing.
            [re-frame.schemas]
            [re-frame.adapter.reagent :as rf.adapter.reagent]))

;; ============================================================================
;; FLOWS
;; ============================================================================
;;
;; A flow registration is just data. `:inputs` lists the paths to watch (a
;; bare path reads app-db; a path led by `:rf.db/runtime` reads runtime-db).
;; `:derive` gets the values at those paths, in order, and returns the value
;; written to `:output-path`. That's the whole contract; the full map is in
;; docs/core/flows.md.
;;
;; `reg-flow` registers a definition, the way `reg-event` does: it names no
;; frame and needs none, so these are plain top-level forms. A frame runs a
;; flow only when its `:flows` config names it — see MOUNT, below.

(defn- line-total [{:keys [price qty]}]
  (* price qty))

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
;; The discount rate is plain state: absent until "Apply 10% discount" sets
;; it, so this `:derive` reads nil as 0% off.
(rf/reg-flow :cart/total
  {:doc    "Subtotal less the active discount. Reads :cart/subtotal's output
            and the discount rate the two discount events set and remove."
   :inputs [[:cart :subtotal] [:cart :discount-rate]]
   :output-path [:cart :total]}
  (fn [subtotal discount-rate]
    (let [rate (or discount-rate 0)]
      (Math/round (* subtotal (- 1 rate))))))

;; ============================================================================
;; EVENTS
;; ============================================================================

(def ^:private seed-items
  [{:sku "RF2-MUG"   :name "re-frame2 mug"      :price 1800 :qty 1}
   {:sku "RF2-TEE"   :name "Six-domino t-shirt" :price 3200 :qty 2}
   {:sku "RF2-STKR"  :name "Sticker pack"       :price 500  :qty 3}])

(rf/reg-event :cart/initialise
  {:doc "Seed the cart. The frame's `:initial-events` run it once, when the
         frame is made, and the frame already runs :cart/subtotal and
         :cart/total by then — so their outputs materialise in this handler's
         own write, and the totals exist before the first render."}
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

;; The discount. Engaging it writes a rate into app-db and removing it takes
;; the rate out; `:cart/total` has `[:cart :discount-rate]` among its inputs,
;; so it recomputes in the same event, before the commit. One click, one
;; event, one write. Guide: docs/core/flows.md#gating-a-derivation-on-state.
(rf/reg-event :cart/apply-discount
  {:doc "Engage the 10%-off discount. :cart/total recomputes in this same
         event."}
  (fn handler-cart-apply-discount [{:keys [db]} _]
    {:db (assoc-in db [:cart :discount-rate] 0.10)}))

(rf/reg-event :cart/remove-discount
  {:doc "Disengage the discount. The rate leaves app-db, so :cart/total
         climbs back to full price in this same event."}
  (fn handler-cart-remove-discount [{:keys [db]} _]
    {:db (update db :cart dissoc :discount-rate)}))

;; And here's the payoff — a handler reading a flow's output. This one reads
;; [:cart :total] as plain app-db data, alongside the items it summarises.
;; A handler can also read a subscription with `rf/subscribe-once`; a flow
;; earns its place here by keeping the total in the durable cart snapshot.
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
  {:doc "True while a discount rate is in app-db."}
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
(defonce app-root (rf.adapter.reagent/client-root))

;; The frame's config. `:flows` names the flows this frame runs: a flow
;; registered but not named here never runs in it. `:initial-events` seed the
;; cart once, when the frame is made — and because the frame runs both flows
;; from its first event, the seed's own write already carries the subtotal
;; and total.
(def cart-frame
  {:id             :rf/default
   :doc            "Cart-with-flows demo frame."
   :flows          [:cart/subtotal :cart/total]
   :initial-events [[:cart/initialise]]})

;; `mount!` is browser setup: create the root lazily, then render the view tree
;; inside the frame-root, which makes the frame on first mount and reuses it
;; afterwards. `^:dev/after-load` is shadow's cue to re-run it on each reload.
;; That is all a hot reload needs here: the reloaded namespace re-registers
;; every flow, event and sub at the top level, and the live frame computes
;; with an edited `:derive` from its next event — cart state intact, no
;; re-seed. It's the same mount shape the counter and todomvc examples spell
;; (see `docs/core/how-to/boot-and-mount-an-app.md`).
(defn ^:dev/after-load mount! []
  (when-let [el (and (exists? js/document)
                     (js/document.getElementById "app"))]
    (rf.adapter.reagent/render! app-root
      [rf/frame-root cart-frame
       [cart-app]]
      el)))

(defn run []
  ;; `init!` tells re-frame2 which reactive substrate to render through —
  ;; here, Reagent. It has to come before any frame is made: a frame's state
  ;; container is substrate-specific.
  (rf/init! rf.adapter.reagent/adapter)
  (mount!))
