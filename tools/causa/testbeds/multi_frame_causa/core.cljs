(ns multi-frame-causa.core
  "Multi-frame Causa testbed (rf2-2vgog) — three richly-domain'd frames
  living in one page, each carrying its own events / subs / app-db slot,
  with one deliberate cross-frame coordination scenario.

  Unlike the shared framework-behavior `testbeds/multi_frame/` surface
  (top-level; rf2-kzcim) — which is three minimal counters proving
  per-frame partitioning of `app-db` / sub-cache / epoch ring — this
  Causa-owned testbed is domain-rich: a cart, a checkout, an admin
  panel, each with its own button cluster and its own slice of state.
  The point is to exercise *Causa's* frame picker, frame-scoped panels,
  and cross-frame causality popover against three distinct, named
  frames a Causa user would actually want to inspect.

  ## The three frames

      :cart-frame      — add-item / remove-item / send-to-checkout
                         buttons; subs :cart/items, :cart/total.
      :checkout-frame  — start / pay buttons; subs :checkout/items,
                         :checkout/total, :checkout/status.
      :admin-frame     — refund / audit buttons; sub :admin/transactions.

  Each frame's `app-db` carries only its own slot — no shared keys, no
  app-global root. The framework keeps the three `app-db`s
  partitioned per [spec/002 §What lives in a frame].

  ## The cross-frame coordination scenario

  Clicking *Send to checkout* on `:cart-frame` dispatches
  `[:checkout/start <items>]` against `:checkout-frame` via a tiny
  bridge fx (`::dispatch-to-frame`) — same pattern as the shared
  multi-frame testbed. The reserved `:dispatch` fx is intra-frame by
  contract, so cross-frame fan-out goes through `rf/dispatch` with
  `{:frame ...}` opts.

  In Causa: the click produces two `:event/dispatched` traces in one
  drain — one on `:cart-frame` (the originating `:cart/send-to-checkout`)
  and one on `:checkout-frame` (the resulting `:checkout/start`). The
  frame picker shows both frames; selecting `:cart-frame` scopes the
  L2 list and downstream panels to the cart's cascade only. Selecting
  `:checkout-frame` scopes to the checkout's cascade.

  ## What Causa users observe

  - **Frame picker** (`ribbon-frame-picker` in shell.cljs) enumerates
    the three frames + `:rf/default` (and `:rf/causa` under the dev
    toggle). After one cross-frame click, all three frames carry
    at least one event in their ring buffer.
  - **L2 event list** scopes per the selected frame.
  - **Cascade isolation** — events in `:cart-frame`'s cascade don't
    appear in `:admin-frame`'s L2 list.
  - **Cross-frame causality** — the *Send to checkout* click produces
    an envelope-rooted sequence: cart-frame's `:cart/send-to-checkout`
    is the source, the cross-frame `:dispatch-to-frame` fx fires, and
    checkout-frame's `:checkout/start` lands in the same drain."
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FRAME IDs — referenced from handler bodies + provider props
;; ============================================================================

(def frame-cart     :cart-frame)
(def frame-checkout :checkout-frame)
(def frame-admin    :admin-frame)

;; ============================================================================
;; Catalogue — tiny three-row table referenced by :cart/add-item.
;; Same shape as the shared cart-total testbed but distinct prices so
;; they don't pun across testbeds (prices in cents — exact arithmetic).
;; ============================================================================

(def catalogue
  {:apple   {:id :apple   :name "Apple"   :price 100}
   :bread   {:id :bread   :name "Bread"   :price 300}
   :coffee  {:id :coffee  :name "Coffee"  :price 500}})

;; ============================================================================
;; PER-FRAME app-db INITIALISERS
;; ============================================================================
;;
;; Each frame registers a distinct init handler so the `:on-create`
;; entry on `reg-frame` runs the right seed for the frame's shape. The
;; handler ids carry their frame prefix as documentation.

(rf/reg-event-db :cart/init
  (fn [_db _ev]
    {:cart/items []}))

(rf/reg-event-db :checkout/init
  (fn [_db _ev]
    {:checkout/items  []
     :checkout/status :idle}))                ;; :idle | :paid

(rf/reg-event-db :admin/init
  (fn [_db _ev]
    {:admin/transactions []}))

;; ============================================================================
;; :cart-frame EVENTS + SUBS
;; ============================================================================

(rf/reg-event-db :cart/add-item
  (fn [db [_ item-id]]
    (let [item        (get catalogue item-id)
          existing-ix (->> (:cart/items db)
                           (keep-indexed (fn [ix line]
                                           (when (= item-id (:id line)) ix)))
                           first)]
      (if existing-ix
        (update-in db [:cart/items existing-ix :qty] inc)
        (update db :cart/items (fnil conj []) (assoc item :qty 1))))))

(rf/reg-event-db :cart/remove-item
  (fn [db [_ item-id]]
    (update db :cart/items
            (fn [lines]
              (vec (remove #(= item-id (:id %)) lines))))))

(rf/reg-event-db :cart/clear
  (fn [db _ev]
    (assoc db :cart/items [])))

(rf/reg-sub :cart/items (fn [db _] (:cart/items db)))

(rf/reg-sub :cart/total
  :<- [:cart/items]
  (fn [items _]
    (reduce + 0 (map (fn [{:keys [price qty]}] (* price qty)) items))))

;; ============================================================================
;; :checkout-frame EVENTS + SUBS
;; ============================================================================
;;
;; :checkout/start ACCEPTS a payload (the items snapshot from the
;; originating cart-frame cascade) so the cross-frame hop carries
;; data across — proving the framework passes the dispatch payload
;; through to the target frame's handler intact.

(rf/reg-event-db :checkout/start
  (fn [db [_ items]]
    (-> db
        (assoc :checkout/items  (vec items))
        (assoc :checkout/status :idle))))

(rf/reg-event-db :checkout/pay
  (fn [db _ev]
    (assoc db :checkout/status :paid)))

(rf/reg-event-db :checkout/reset
  (fn [db _ev]
    (-> db
        (assoc :checkout/items  [])
        (assoc :checkout/status :idle))))

(rf/reg-sub :checkout/items  (fn [db _] (:checkout/items db)))
(rf/reg-sub :checkout/status (fn [db _] (:checkout/status db)))

(rf/reg-sub :checkout/total
  :<- [:checkout/items]
  (fn [items _]
    (reduce + 0 (map (fn [{:keys [price qty]}] (* price qty)) items))))

;; ============================================================================
;; :admin-frame EVENTS + SUBS
;; ============================================================================
;;
;; Admin is a passive log frame — refund + audit each append a row to
;; :admin/transactions. No cross-frame coordination originates here.
;; Its purpose in the Causa walkthrough: a frame whose L2 list stays
;; empty when the cart/checkout buttons fire, demonstrating cascade
;; isolation. Clicking the admin-only buttons populates ONLY this
;; frame's ring buffer.

(rf/reg-event-db :admin/refund
  (fn [db [_ amount]]
    (update db :admin/transactions (fnil conj [])
            {:kind   :refund
             :amount amount
             :at     (count (:admin/transactions db))})))

(rf/reg-event-db :admin/audit
  (fn [db _ev]
    (update db :admin/transactions (fnil conj [])
            {:kind :audit
             :at   (count (:admin/transactions db))})))

(rf/reg-sub :admin/transactions (fn [db _] (:admin/transactions db)))

(rf/reg-sub :admin/refund-count
  :<- [:admin/transactions]
  (fn [txs _]
    (count (filter #(= :refund (:kind %)) txs))))

(rf/reg-sub :admin/audit-count
  :<- [:admin/transactions]
  (fn [txs _]
    (count (filter #(= :audit (:kind %)) txs))))

;; ============================================================================
;; CROSS-FRAME BRIDGE — :cart-frame → :checkout-frame
;; ============================================================================
;;
;; The reserved `:dispatch` fx is intra-frame by contract: a handler
;; running on :cart-frame returning `[:dispatch [:checkout/start ...]]`
;; would queue against :cart-frame and fail at handler-resolve time
;; (no :checkout/start handler is registered on :cart-frame's local
;; lookup path — handlers are global, but the dispatch envelope's
;; :frame still gates which app-db is passed in).
;;
;; The bridge fx (`::dispatch-to-frame`) calls public `rf/dispatch`
;; with explicit `{:frame ...}` opts so the target frame's router
;; queue receives the envelope. Same pattern as the shared
;; multi-frame testbed.

(rf/reg-fx ::dispatch-to-frame
  (fn [_ctx {:keys [event frame]}]
    (rf/dispatch event {:frame frame})))

(rf/reg-event-fx :cart/send-to-checkout
  (fn [{:keys [db]} _ev]
    ;; Snapshot the cart's items into the cross-frame dispatch payload,
    ;; then clear the cart locally — same shape a real checkout-flow
    ;; would use.
    (let [items (:cart/items db)]
      {:db (assoc db :cart/items [])
       :fx [[::dispatch-to-frame {:event [:checkout/start items]
                                  :frame frame-checkout}]]})))

;; ============================================================================
;; VIEWS — three panels, one per frame
;; ============================================================================
;;
;; Each panel mounts under its frame-provider; the reg-view-injected
;; `dispatch` / `subscribe` resolve to the surrounding frame. The
;; *Send to checkout* button sits in :cart-frame because the
;; originating event is `:cart/send-to-checkout` (which then fans out
;; to :checkout-frame via the bridge fx).

(defn- format-money [cents]
  (let [dollars (quot cents 100)
        change  (mod cents 100)]
    (str "$" dollars "." (if (< change 10) (str "0" change) change))))

(reg-view cart-panel []
  (let [items @(subscribe [:cart/items])
        total @(subscribe [:cart/total])]
    [:section {:data-testid "cart-frame-panel"
               :style {:border "1px solid #2b7" :border-radius "6px"
                       :padding "1em 1.5em" :background "#f7fff9"
                       :min-width "260px" :flex 1}}
     [:h3 {:style {:margin-top 0 :color "#1a5"}} "Cart " [:small "(:cart-frame)"]]
     [:div {:style {:margin-bottom "0.75em" :display "flex" :gap "6px" :flex-wrap "wrap"}}
      [:button {:data-testid "cart-add-apple"
                :on-click    #(dispatch [:cart/add-item :apple])}
       "+ Apple"]
      [:button {:data-testid "cart-add-bread"
                :on-click    #(dispatch [:cart/add-item :bread])}
       "+ Bread"]
      [:button {:data-testid "cart-add-coffee"
                :on-click    #(dispatch [:cart/add-item :coffee])}
       "+ Coffee"]
      [:button {:data-testid "cart-clear"
                :on-click    #(dispatch [:cart/clear])
                :style       {:margin-left "8px"}}
       "Clear cart"]]
     (if (seq items)
       [:table {:style {:border-collapse "collapse" :width "100%"
                        :font-size "13px" :margin-bottom "0.5em"}}
        [:tbody
         (for [{:keys [id name qty price]} items]
           ^{:key id}
           [:tr {:data-testid (str "cart-line-" (clojure.core/name id))}
            [:td {:style {:padding "2px 8px"}} name]
            [:td {:style {:padding "2px 8px"}} (str "× " qty)]
            [:td {:style {:padding "2px 8px" :text-align "right"}}
             (format-money (* price qty))]
            [:td {:style {:padding "2px 8px"}}
             [:button {:data-testid (str "cart-remove-" (clojure.core/name id))
                       :on-click    #(dispatch [:cart/remove-item id])
                       :style       {:font-size "11px"}}
              "remove"]]])]]
       [:p {:style {:color "#666" :font-style "italic" :margin "0.5em 0"}}
        "Cart is empty."])
     [:div {:style {:border-top "1px solid #cee"
                    :padding-top "6px" :margin-top "6px"
                    :display "flex" :justify-content "space-between"
                    :font-weight "bold"}}
      [:span "Cart total"]
      [:span {:data-testid "cart-total"} (format-money total)]]
     [:button {:data-testid "cart-send-to-checkout"
               :on-click    #(dispatch [:cart/send-to-checkout])
               :disabled    (empty? items)
               :style       {:margin-top "0.75em"
                             :padding "6px 12px"
                             :background (if (seq items) "#1a5" "#aaa")
                             :color "#fff" :border "none"
                             :border-radius "4px"
                             :cursor (if (seq items) "pointer" "default")}}
      "Send to checkout (cross-frame → :checkout-frame)"]]))

(reg-view checkout-panel []
  (let [items  @(subscribe [:checkout/items])
        total  @(subscribe [:checkout/total])
        status @(subscribe [:checkout/status])]
    [:section {:data-testid "checkout-frame-panel"
               :style {:border "1px solid #36c" :border-radius "6px"
                       :padding "1em 1.5em" :background "#f5f8ff"
                       :min-width "260px" :flex 1}}
     [:h3 {:style {:margin-top 0 :color "#249"}} "Checkout " [:small "(:checkout-frame)"]]
     [:div {:style {:margin-bottom "0.75em" :display "flex" :gap "6px" :flex-wrap "wrap"}}
      [:button {:data-testid "checkout-pay"
                :on-click    #(dispatch [:checkout/pay])
                :disabled    (or (empty? items) (= :paid status))}
       "Pay"]
      [:button {:data-testid "checkout-reset"
                :on-click    #(dispatch [:checkout/reset])
                :style       {:margin-left "8px"}}
       "Reset"]]
     (if (seq items)
       [:table {:style {:border-collapse "collapse" :width "100%"
                        :font-size "13px" :margin-bottom "0.5em"}}
        [:tbody
         (for [{:keys [id name qty price]} items]
           ^{:key id}
           [:tr {:data-testid (str "checkout-line-" (clojure.core/name id))}
            [:td {:style {:padding "2px 8px"}} name]
            [:td {:style {:padding "2px 8px"}} (str "× " qty)]
            [:td {:style {:padding "2px 8px" :text-align "right"}}
             (format-money (* price qty))]])]]
       [:p {:style {:color "#666" :font-style "italic" :margin "0.5em 0"}}
        "No items in checkout yet. Click "
        [:em "Send to checkout"] " on the cart."])
     [:div {:style {:border-top "1px solid #cce"
                    :padding-top "6px" :margin-top "6px"
                    :display "flex" :justify-content "space-between"
                    :font-weight "bold"}}
      [:span "Checkout total"]
      [:span {:data-testid "checkout-total"} (format-money total)]]
     [:div {:style {:margin-top "0.5em" :font-size "12px" :color "#444"}}
      "status: "
      [:span {:data-testid "checkout-status"
              :style {:font-weight "bold"
                      :color (case status :paid "#1a5" "#666")}}
       (str status)]]]))

(reg-view admin-panel []
  (let [txs           @(subscribe [:admin/transactions])
        refund-count  @(subscribe [:admin/refund-count])
        audit-count   @(subscribe [:admin/audit-count])]
    [:section {:data-testid "admin-frame-panel"
               :style {:border "1px solid #c63" :border-radius "6px"
                       :padding "1em 1.5em" :background "#fff8f3"
                       :min-width "260px" :flex 1}}
     [:h3 {:style {:margin-top 0 :color "#a40"}} "Admin " [:small "(:admin-frame)"]]
     [:div {:style {:margin-bottom "0.75em" :display "flex" :gap "6px" :flex-wrap "wrap"}}
      [:button {:data-testid "admin-refund"
                :on-click    #(dispatch [:admin/refund 250])}
       "Issue refund ($2.50)"]
      [:button {:data-testid "admin-audit"
                :on-click    #(dispatch [:admin/audit])}
       "Run audit"]]
     [:p {:style {:margin "0.25em 0" :font-size "13px"}}
      "refunds=" [:span {:data-testid "admin-refund-count"} refund-count]
      " · audits=" [:span {:data-testid "admin-audit-count"} audit-count]
      " · total=" [:span {:data-testid "admin-tx-count"} (count txs)]]
     (if (seq txs)
       [:ul {:data-testid "admin-tx-list"
             :style {:max-height "10em" :overflow :auto
                     :font-size "12px" :font-family "monospace"
                     :background "#fff" :border "1px solid #f2dcc4"
                     :border-radius "4px" :padding "4px 12px"}}
        (for [[idx tx] (map-indexed vector txs)]
          ^{:key idx}
          [:li {:data-testid (str "admin-tx-" idx)} (pr-str tx)])]
       [:p {:style {:color "#666" :font-style "italic" :margin "0.5em 0"}}
        "No admin transactions yet."])]))

(reg-view root []
  [:div {:data-testid "multi-frame-causa-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "1100px" :margin "0 auto"}}
   [:h2 {:style {:margin-top 0}} "Multi-frame Causa testbed"]
   [:p {:style {:color "#444" :margin "0.25em 0 1em 0"}}
    "Three frames coexist on this page: "
    [:code ":cart-frame"] ", "
    [:code ":checkout-frame"] ", "
    [:code ":admin-frame"] ". "
    "Each carries its own events, subs, and "
    [:code "app-db"] " slot. "
    "Most clicks stay scoped to one frame; "
    [:em "Send to checkout"] " is the cross-frame coordination scenario — "
    "the cart-frame handler fans out to the checkout-frame via a bridge fx."]
   [:p {:style {:color "#666" :font-size "12px" :margin "0 0 1em 0"}}
    "Open Causa (Ctrl+Shift+C). The frame picker should enumerate all three "
    "frames. Selecting a frame scopes the L2 event list + downstream panels "
    "to that frame's cascade."]
   [:div {:style {:display "flex" :gap "1em" :flex-wrap "wrap"
                  :align-items "flex-start"}}
    [rf/frame-provider {:frame frame-cart}
     [cart-panel]]
    [rf/frame-provider {:frame frame-checkout}
     [checkout-panel]]
    [rf/frame-provider {:frame frame-admin}
     [admin-panel]]]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; Register the three frames before any dispatch. The `:on-create`
  ;; entries seed each frame's `app-db` synchronously.
  (rf/reg-frame frame-cart     {:on-create [:cart/init]})
  (rf/reg-frame frame-checkout {:on-create [:checkout/init]})
  (rf/reg-frame frame-admin    {:on-create [:admin/init]})
  (rdc/render react-root [root]))
