(ns flows.core
  "Worked example for [Spec 013 — Flows](../../../spec/013-Flows.md).

   A flow is a *registered, runtime-toggleable computed-state declaration
   that materialises its output into `app-db`*. It says: \"when these
   `app-db` paths change, run this pure function and write the result to
   that `app-db` path.\" Flows evaluate automatically right after the event
   handler — as the outermost `:after`, transforming the pending `:db`
   effect before it installs into `app-db` — in topological order over
   their static input/output graph.

   WHY A FLOW AND NOT A SUB?  This is the load-bearing question Spec 013
   §When (and when not) to use a flow answers, and the whole point of this
   example. Most derived values should be SUBSCRIPTIONS — they are lighter,
   live in the per-frame sub-cache, and pay no `app-db` write. Reach for a
   flow ONLY when the derived value is part of the application's *state*:

     - other event handlers read it as plain `app-db` data (here:
       `:checkout/place-order` reads `[:cart :total]` directly — no
       subscribe ceremony inside a handler);
     - it should survive SSR hydration / time-travel revert / app-db
       serialisation (sub-cache contents do not survive the wire);
     - the derivation is stable enough to be worth registering.

   This cart demonstrates three things Spec 013 calls out:

   1. MATERIALISED COMPUTED STATE — `:cart/subtotal` derives the line-item
      sum from `[:cart :items]` and writes it to `[:cart :subtotal]`.
   2. A TOPOLOGICAL CASCADE — `:cart/total` reads `[:cart :subtotal]`
      (another flow's output) plus `[:cart :discount-rate]`. The runtime
      sorts the two flows so `:cart/subtotal` always runs first; both
      settle in one walk — right after the handler, before the db install.
   3. RUNTIME-TOGGLEABLE DERIVATION — the discount is a feature gate. The
      `:rf.fx/reg-flow` / `:rf.fx/clear-flow` fx register and clear the
      `:cart/discount-rate` flow from a handler, mid-event. v1's
      `on-changes` interceptor cannot do this (it is wired into specific
      events at registration time); flows are runtime-registered and
      runtime-clearable.

   Demonstrates:
   - `rf/reg-flow`                                  (Spec 013 §Registration shape)
   - flow-reads-flow topological cascade            (Spec 013 §Topological sort)
   - `:rf.fx/reg-flow` / `:rf.fx/clear-flow`        (Spec 013 §Dynamic toggle via fx)
   - reading a flow's output inside an event handler (Spec 013 §Sub integration (a))
   - reading a flow's output via a plain sub over its `:path`  (Spec 013 §Sub integration (b))"
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            ;; Flows ship in day8/re-frame2-flows. Requiring re-frame.flows
            ;; here is what publishes the artefact's late-bind hooks
            ;; (`:flows/reg-flow` etc.) so the `rf/reg-flow` calls below
            ;; resolve; without it they raise
            ;; :rf.error/flows-artefact-missing. (Same require-to-register
            ;; convention the schemas / machines / routing examples use.)
            [re-frame.flows]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view with-frame]]))

;; ============================================================================
;; FLOWS  (Spec 013 §Registration shape)
;; ============================================================================
;;
;; `:inputs` is a vector of app-db paths; `:output` receives the values at
;; those paths positionally and returns the value written to `:path`.

(defn- line-total [{:keys [price qty]}]
  (* price qty))

;; EP-0002 (rf2-5q7um6): reg-flow is context-required frame-local; a bare
;; ns-load call raises :rf.error/no-frame-context. This example runs in the
;; :rf/default frame, so name it explicitly here. (The runtime-toggleable
;; `:cart/discount-rate` flow is registered later via :rf.fx/reg-flow, which
;; inherits the cascade frame — see the discount button handler.)
(with-frame :rf/default

(rf/reg-flow
  {:id     :cart/subtotal
   :doc    "Sum of every line item's price × qty. Materialised into app-db
            so the checkout handler can read it as plain data."
   :inputs [[:cart :items]]
   :output (fn [items] (reduce + 0 (map line-total items)))
   :path   [:cart :subtotal]})

;; `:cart/total` reads ANOTHER flow's output (`[:cart :subtotal]`) plus the
;; (optionally-registered) discount rate. The runtime derives the
;; dependency edge from the path overlap and topologically sorts so
;; `:cart/subtotal` always runs before `:cart/total` — both settle in a
;; single walk, run right after the handler before the db install
;; (Spec 013 §Topological sort and cycle detection).
;;
;; `:cart/discount-rate` is NOT registered at boot. When absent, its path
;; is nil and `:cart/total`'s `:output` treats it as 0% off. The "Apply
;; 10% discount" button registers it via `:rf.fx/reg-flow`; "Remove
;; discount" clears it via `:rf.fx/clear-flow` (which `dissoc-in`s the
;; path back to nil). While registered it derives a flat 10% off the live
;; subtotal — a constant rate today, but reading `[:cart :subtotal]` keeps
;; it honest: a tiered "5% over $50" rule would slot straight in, and the
;; rate stays fresh as the cart changes without re-registration.
(rf/reg-flow
  {:id     :cart/total
   :doc    "Subtotal less the active discount. Reads :cart/subtotal's
            output and the runtime-toggleable discount rate."
   :inputs [[:cart :subtotal] [:cart :discount-rate]]
   :output (fn [subtotal discount-rate]
             (let [rate (or discount-rate 0)]
               (Math/round (* subtotal (- 1 rate)))))
   :path   [:cart :total]}))

;; ============================================================================
;; EVENTS
;; ============================================================================

(def ^:private seed-items
  [{:sku "RF2-MUG"   :name "re-frame2 mug"      :price 1800 :qty 1}
   {:sku "RF2-TEE"   :name "Six-domino t-shirt" :price 3200 :qty 2}
   {:sku "RF2-STKR"  :name "Sticker pack"       :price 500  :qty 3}])

(rf/reg-event :cart/initialise
  {:doc "Seed the cart. The :cart/subtotal and :cart/total flows fire right
         after THIS event's handler (before the db install) and materialise
         their outputs — a newly-registered flow's first walk always
         recomputes."}
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

;; Runtime-toggleable derivation (Spec 013 §Dynamic toggle via fx). The
;; discount flow is registered / cleared mid-event via the two reserved
;; fx-ids. Both route to the dispatching frame automatically.
;;
;; SEQUENCING NOTE (Spec 013 §Sequencing — the one-event lag): a flow
;; registered via `:rf.fx/reg-flow` first runs on the NEXT drain — its
;; initial output appears one event after registration. So after
;; registering the discount-rate flow we dispatch a no-op `:cart/touch`
;; whose ONLY job is to drain: by the time it runs, the new flow is in the
;; registry, so the flow transform on that drain materialises
;; `[:cart :discount-rate]` (and the dependent `[:cart :total]`) at the
;; discounted figure. The re-walk is driven by the dispatched event
;; draining — NOT by `:cart/touch` writing anything (it writes nothing).
;; This is the spec's own `:wizard/settle` pattern (§Sequencing).
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
            :output (fn [_subtotal] 0.10)
            :path   [:cart :discount-rate]}]
          [:dispatch [:cart/touch]]]}))

(rf/reg-event :cart/remove-discount
  {:doc "Disengage the feature gate. `:rf.fx/clear-flow` removes the flow
         AND dissoc-in's its [:cart :discount-rate] output back to nil, so
         :cart/total recomputes to the full price on the next walk."}
  (fn handler-cart-remove-discount [_ _]
    {:fx [[:rf.fx/clear-flow :cart/discount-rate]
          [:dispatch [:cart/touch]]]}))

;; No-op drain — Spec 013 §Sequencing's `:wizard/settle` verbatim:
;; `(fn [db _] db)`, no schema, dispatched with no args. It writes
;; NOTHING. Its sole purpose is to make a drain happen on this frame so
;; the flow transform re-walks with the just-(de)registered discount flow
;; now visible in the registry — materialising the one-drain-lagged
;; output. The toggle is the `:rf.fx/reg-flow` / `:rf.fx/clear-flow`
;; registration itself; this event is the drain that surfaces its effect.
(rf/reg-event :cart/touch
  {:doc "No-op drain. Exists only to trigger the re-walk that materialises
         the one-event-lagged discount flow output (Spec 013 §Sequencing)."}
  (fn handler-cart-touch [{:keys [db]} _] {:db db}))

;; Reading a flow's output inside an event handler — Spec 013 §Sub
;; integration (a). The handler reads [:cart :total] as plain `app-db`
;; data; no subscribe, no special flow accessor. This is the central
;; reason the total is a flow and not a sub: a sub's value lives in the
;; view-facing sub-cache and is awkward to read from a handler.
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
;; Flows publish ZERO framework subs (Spec 013 §Sub integration). A flow's
;; output is "ordinary application state with a known producer" — consumers
;; read it through whatever sub they prefer over the flow's `:path`. The
;; view below reads the materialised :subtotal / :total via plain subs.

(rf/reg-sub :cart/items
  (fn [db _] (get-in db [:cart :items])))

(rf/reg-sub :cart/subtotal
  {:doc "Reads the :cart/subtotal flow's output at its :path — pattern (b)
         from Spec 013 §Sub integration. No special flow sub-id; just an
         app-db read."}
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

;; The React root is held in an atom and materialised lazily inside `run`
;; (not at ns-load) per examples/TESTING.md §Example mount-isolation
;; convention: ns-load must produce no DOM side effects so co-required
;; example namespaces don't race `create-root` onto the shared `#app`.
(defonce react-root (atom nil))

;; EP-0002 (rf2-9o48ih): the runtime never synthesises a frame from absence —
;; register the app frame explicitly, seed under `with-frame`, and wrap the
;; render in a `frame-provider` so the `reg-view`-injected `dispatch`/`subscribe`
;; resolve to it (a no-provider render reads the no-provider sentinel and raises
;; :rf.error/no-frame-context). The frame id is `:rf/default` — the same id the
;; ns-load `reg-app-schema` is scoped to above.
(def app-frame :rf/default)

(defn run []
  ;; Pass the adapter spec map directly — no registry.
  (rf/init! reagent-adapter/adapter)
  (rf/reg-frame app-frame {})
  (rf/with-frame app-frame
    (rf/dispatch-sync [:cart/initialise]))
  (when (exists? js/document)
    (when-not @react-root
      (reset! react-root (rdc/create-root (js/document.getElementById "app"))))
    (rdc/render @react-root
                [rf/frame-provider-existing {:frame app-frame}
                 [cart-app]])))
