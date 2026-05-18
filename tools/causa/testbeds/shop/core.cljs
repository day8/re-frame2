(ns shop.core
  "Shop testbed (rf2-yhxk3) — THE canonical Causa demo.

  One rich multi-frame demo exercising every Causa lens in a single
  page. Replaces and absorbs both `cart_total/` (the 3pm tutorial
  hero) and `multi_frame_causa/` (the three-frame scaffolding from
  #1468). Pre-alpha posture: no overlap, no shims, deletes the
  predecessors in the same PR.

  ## The 6 layers

  1. **Multi-frame** — three named frames, each with its own events,
     subs, and `app-db` slot, mounted under their own `frame-provider`:
       :cart-frame      — fetches the product catalogue on mount,
                          add/remove items, send-to-checkout.
       :checkout-frame  — runs the `:checkout/flow` state machine,
                          fires charge-card! / fire-confirmation! actions.
       :admin-frame     — audit log + refund button (with deliberate
                          throw mode) + 'submit broken item' (schema
                          violation source).
  2. **HTTP** — `:shop/load-catalogue` issues a managed-HTTP request
     against `/api/products`. Dev panel toggles the next request between
     success / HTTP-500 / timeout. Successful reply lands in
     `[:cart :catalogue]`; failures land in `[:cart :catalogue-error]`
     and surface in the Issues ribbon via the canonical
     `:rf.http/<kind>` error trace.
  3. **Machine** — `:checkout/flow` machine:
       cart-empty → ready → paying → confirmed | failed
     Guard `cart-non-empty?` gates ready→paying. Entry action
     `charge-card!` issues a (stubbed) charge on entry to `paying`;
     entry action `fire-confirmation!` fires on entry to `confirmed`.
     Machine traces (`:rf.machine/guard-evaluated`,
     `:rf.machine/action-ran` from #1469) light up Causa's Machines
     lens automatically.
  4. **Flow** — `:total-due` derived view recomputes from
     `[:cart :items]`, `[:checkout :tax-rate]`, `[:checkout :shipping]`
     — all on :cart-frame's app-db (subs are per-frame; see the
     LAYER 5 note below). PRESERVES the deliberately-wrong sub from
     `cart_total/` (the 3pm tutorial beat): `:cart/subtotal-WRONG`
     reads `[:checkout :snapshot]` instead of `[:cart :items]`, so the
     displayed total locks to whatever was snapshotted at
     send-to-checkout. Causa's Views lens reveals the bug when you
     click the dependency edge.
  5. **Non-trivial app-db** — nested per-frame shapes:
       cart-frame:
         {:cart     {:items [...] :catalogue [...] :catalogue-error nil
                     :discount nil :next-http-outcome :success}
          :checkout {:snapshot [] :tax-rate 0.1 :shipping 500}}
       checkout-frame:
         {:checkout {:card {:last4 \"4242\"}}}
         + [:rf/machines :checkout/flow] for the machine snapshot.
       admin-frame:
         {:admin {:audit-log [] :refund-throw? false
                  :flags {:beta? true :strict-validation? false}}}
     Rich enough that App-db Diff has multiple cluster sections on
     each cascade.

     Why cart-frame carries the [:checkout :snapshot] + tax + shipping
     slots: subs are per-frame (Spec 006), so the `:total-due` →
     `:cart/subtotal-WRONG` → snapshot chain MUST resolve under a
     single frame's app-db. Cross-frame state lives on the frame whose
     subs read it; the cross-frame *machine* hop carries items via
     `:seed-from-cart` action into the machine's :data slot, which the
     checkout-frame's view reads locally.
  6. **Issues source** —
       - Schema violation: `CartItem` schema declares
         `[:map [:id :keyword] [:name :string] [:price pos-int?]
                [:qty pos-int?]]`; the admin 'submit broken item'
         button fans a cross-frame write of a line with `:qty -1`
         into `:cart-frame`'s `[:cart :items]` slot, which the
         registered app-schema rejects with post-handler rollback.
       - Handler throw: `:admin/refund` throws when
         `[:admin :refund-throw?]` is true. Deterministic toggle —
         the testbed always reproduces.

  ## Cross-frame coordination

  Clicking *Send to checkout* on `:cart-frame` dispatches
  `:cart/send-to-checkout`; the handler snapshots items into
  `:checkout/snapshot`, clears the live cart, then fans out
  `[:checkout/flow [:checkout.flow/start]]` to `:checkout-frame` via
  the `::dispatch-to-frame` bridge fx (the reserved `:dispatch` is
  intra-frame by contract — Spec 002 §Dispatches issued from inside
  a handler body).

  ## What Causa users observe

  - Event lens: every interaction produces a cascade.
  - App-db lens: nested diff with multiple cluster sections per click.
  - Views lens: `:total-due` and `:cart/subtotal-WRONG` recompute on
    every relevant event; the wrong-slot bug is one click away.
  - Trace lens: HTTP request/reply rows, machine guard/action rows,
    schema-violation rows.
  - Machines lens: live state of `:checkout/flow` with transition
    history.
  - Issues ribbon: schema violations + handler throws + HTTP failures.

  Each layer's wiring reads as a small section with a clear comment
  header so a reader can navigate by skim."
  (:require [clojure.string :as str]
            [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [re-frame.views]
            ;; Loading these namespaces installs their late-bind
            ;; hooks; without them rf/reg-machine, rf/reg-app-schema,
            ;; and the :rf.http/* fxs would fail at registration time.
            [re-frame.machines]
            [re-frame.schemas]
            ;; The Malli adapter — browser builds soft-pass schema
            ;; checks by default, so without this require the
            ;; testbed's deliberate violation would never surface
            ;; as :rf.error/schema-validation-failure.
            [re-frame.schemas.malli]
            [re-frame.http :as rf.http]
            [re-frame.http-managed]
            ;; Testbeds are test affordances — requiring the test-
            ;; support ns to reach :rf.http/managed-canned-failure is
            ;; correct here (mirrors testbeds/http_toggle and the
            ;; cart_total testbed this one supersedes).
            [re-frame.http-test-support]
            [re-frame.adapter.reagent :as reagent-adapter])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; FRAME IDs
;; ============================================================================

(def frame-cart     :cart-frame)
(def frame-checkout :checkout-frame)
(def frame-admin    :admin-frame)

;; ============================================================================
;; FALLBACK CATALOGUE — used until the HTTP fetch lands
;; ============================================================================

(def fallback-catalogue
  [{:id :apple   :name "Apple"   :price 150}
   {:id :bread   :name "Bread"   :price 350}
   {:id :coffee  :name "Coffee"  :price 450}])

(defn- format-money
  "Cents → display string. `150` → `\"$1.50\"`."
  [cents]
  (let [cents   (long (or cents 0))
        sign    (if (neg? cents) "-" "")
        abs     (if (neg? cents) (- cents) cents)
        dollars (quot abs 100)
        change  (mod abs 100)]
    (str sign "$" dollars "." (if (< change 10) (str "0" change) change))))

;; ============================================================================
;; LAYER 5 — Non-trivial nested app-db (per-frame initial shapes)
;; ============================================================================
;;
;; Each frame seeds its own slice; nothing shared at the root. The
;; nested shape gives Causa's App-db Diff multiple cluster sections to
;; render on every cascade — see Spec 004-App-DB-Diff §Slice-centric.

(rf/reg-event-db :cart/init
  (fn [_db _ev]
    ;; The cart-frame owns the entire shopping-projection slice: the
    ;; live cart, the catalogue, AND the snapshot the checkout-start
    ;; handler freezes. Keeping the snapshot here is what makes the
    ;; 3pm-tutorial wrong-slot sub a live bug — subs are per-frame,
    ;; so the deliberately-wrong projection MUST read off the same
    ;; frame's app-db it's mounted under (see LAYER 4).
    {:cart      {:items             []
                 :catalogue         fallback-catalogue
                 :catalogue-error   nil
                 :discount          nil
                 :next-http-outcome :success}     ;; dev toggle
     :checkout  {:snapshot []
                 :tax-rate 0.10                   ;; 10% sales tax
                 :shipping 500}}))                ;; flat $5.00

(rf/reg-event-db :checkout/init
  (fn [_db _ev]
    ;; The checkout-frame owns the flow state machine (snapshot lives
    ;; at [:rf/machines :checkout/flow] after first dispatch) plus the
    ;; payment-card metadata. Catalogue / cart / total data lives on
    ;; cart-frame so the views' subs resolve against one frame's
    ;; app-db.
    {:checkout {:card {:last4 "4242"}}}))

(rf/reg-event-db :admin/init
  (fn [_db _ev]
    {:admin {:audit-log      []
             :refund-throw?  false
             :flags          {:beta?              true
                              :strict-validation? false}}}))

;; ============================================================================
;; LAYER 6a — Malli schema for cart items (issues source)
;; ============================================================================
;;
;; A tight schema for the line-item shape. Registered against
;; :cart-frame's `[:cart :items]` slot in `run` (below) via the
;; :frame opt on reg-app-schema. The admin's 'submit broken item'
;; button fans a cross-frame write of a `:qty -1` line into that
;; slot; the post-handler app-db validation step rejects it (Spec
;; 010 §Validation order step 4). The trace lands as
;; :rf.error/schema-validation-failure and surfaces in Causa's
;; Issues ribbon; the :db effect rolls back.

(def CartItem
  [:map
   [:id    :keyword]
   [:name  :string]
   [:price pos-int?]
   [:qty   pos-int?]])

(def CartItems
  [:vector CartItem])

;; ============================================================================
;; LAYER 1 — :cart-frame events + subs + LAYER 2 HTTP catalogue fetch
;; ============================================================================
;;
;; `:cart/load-catalogue` issues a managed-HTTP GET; the testbed routes
;; the next call via `[:cart :next-http-outcome]` so dev buttons can
;; toggle between success / HTTP-500 / timeout. Default reply
;; addressing routes the response back to `:cart/load-catalogue`
;; (Spec 014); the reply branch pins the catalogue or the failure-map.

(def catalogue-request-id ::catalogue-in-flight)

(rf/reg-event-fx :cart/load-catalogue
  (fn [{:keys [db]} [_ msg]]
    (cond
      ;; Reply branch — success: pin the catalogue. The JSON-decoded
      ;; payload carries string :id values; coerce to keywords so the
      ;; catalogue shape matches `fallback-catalogue` and the add-item
      ;; handler can match by id uniformly.
      (some-> msg :rf/reply :kind (= :success))
      (let [raw    (get-in msg [:rf/reply :value :products])
            items  (if (seq raw)
                     (mapv (fn [{:keys [id name price]}]
                             {:id    (if (keyword? id) id (keyword id))
                              :name  name
                              :price price})
                           raw)
                     fallback-catalogue)]
        {:db (-> db
                 (assoc-in [:cart :catalogue]       items)
                 (assoc-in [:cart :catalogue-error] nil))})

      ;; Reply branch — failure: pin the error map. The :rf.http/<kind>
      ;; error-trace fired already; Causa's Issues ribbon picks it up.
      (some-> msg :rf/reply :kind (= :failure))
      {:db (assoc-in db [:cart :catalogue-error]
                     (:failure (:rf/reply msg)))}

      ;; Initial branch — fan out by the dev-panel outcome. The
      ;; toggle lives on :cart-frame's own [:cart :next-http-outcome]
      ;; slot so the fetch stays single-frame-scoped.
      :else
      (let [outcome (or (get-in db [:cart :next-http-outcome])
                        :success)]
        {:db (assoc-in db [:cart :catalogue-error] nil)
         :fx [(case outcome
                ;; HOT PATH — live Fetch against the staged JSON asset.
                :success
                (rf.http/get "api/products.json"
                             {:decode     :json
                              :request-id catalogue-request-id})

                ;; HTTP-500 — canned-failure synth; same reply envelope
                ;; as a live 500.
                :rf.http/http-5xx
                [:rf.http/managed-canned-failure
                 {:request    {:method :get :url "api/products"}
                  :request-id catalogue-request-id
                  :kind       :rf.http/http-5xx
                  :tags       {:status      503
                               :status-text "Service Unavailable"
                               :body        "{\"error\":\"products endpoint down\"}"
                               :headers     {}}}]

                :rf.http/timeout
                [:rf.http/managed-canned-failure
                 {:request    {:method :get :url "api/products"}
                  :request-id catalogue-request-id
                  :kind       :rf.http/timeout
                  :tags       {:elapsed-ms 5000 :limit-ms 5000}}])]}))))

(rf/reg-event-db :cart/add-item
  (fn [db [_ item-id]]
    (let [catalogue   (get-in db [:cart :catalogue])
          item        (some #(when (= item-id (:id %)) %) catalogue)
          items       (get-in db [:cart :items])
          existing-ix (->> items
                           (keep-indexed (fn [ix line]
                                           (when (= item-id (:id line)) ix)))
                           first)]
      (if existing-ix
        (update-in db [:cart :items existing-ix :qty] inc)
        (update-in db [:cart :items] (fnil conj [])
                   (assoc item :qty 1))))))

(rf/reg-event-db :cart/remove-item
  (fn [db [_ item-id]]
    (update-in db [:cart :items]
               (fn [lines] (vec (remove #(= item-id (:id %)) lines))))))

(rf/reg-event-db :cart/clear
  (fn [db _ev]
    (assoc-in db [:cart :items] [])))

;; Subs — root slots, derived projections, and the deliberately-wrong
;; pair this testbed inherits from cart_total (see LAYER 4 §The bug).

(rf/reg-sub :cart/items     (fn [db _] (get-in db [:cart :items])))
(rf/reg-sub :cart/catalogue (fn [db _] (get-in db [:cart :catalogue])))
(rf/reg-sub :cart/catalogue-error
  (fn [db _] (get-in db [:cart :catalogue-error])))

(rf/reg-sub :cart/line-totals
  :<- [:cart/items]
  (fn [items _]
    (mapv (fn [{:keys [id name price qty]}]
            {:id id :name name :qty qty
             :subtotal (* (or price 0) (or qty 0))})
          items)))

;; ============================================================================
;; LAYER 4 — :total-due derived view, with the deliberately-wrong sub
;; ============================================================================
;;
;; THE 3pm TUTORIAL BEAT (preserved from cart_total). Two sibling subs:
;;
;;   :cart/subtotal-CORRECT  — reads [:cart :items]   (the live basket)
;;   :cart/subtotal-WRONG    — reads [:checkout :snapshot] (the frozen
;;                             snapshot the checkout-start handler took)
;;
;; The display sub `:total-due` chains through :cart/subtotal-WRONG +
;; tax + shipping; after a *Send to checkout* click, the live cart
;; re-populates on the next +Add but the displayed total stays locked
;; to the snapshot's total. Open Causa's Views lens, find :total-due,
;; click the dependency edge — the wrong upstream slot is one click
;; away. The one-token fix swaps WRONG → CORRECT.
;;
;; The :total-due "flow" runs as a derived sub for testbed simplicity.
;; (Spec 013 reg-flow is the same shape — pure derivation; subs are
;; the right primitive for this teaching beat because Causa's Views
;; lens is where the bug is supposed to surface.)

;; Local-to-cart-frame subs reading the snapshot + tax + shipping. The
;; whole projection now lives on cart-frame's app-db so the
;; `:total-due` chain resolves under one frame. The checkout-frame
;; reads the snapshot off the machine's :data slot instead (set by
;; the :seed-from-cart entry action).
(rf/reg-sub :cart/snapshot
  (fn [db _] (get-in db [:checkout :snapshot])))

(rf/reg-sub :cart/tax-rate
  (fn [db _] (get-in db [:checkout :tax-rate])))

(rf/reg-sub :cart/shipping
  (fn [db _] (get-in db [:checkout :shipping])))

(rf/reg-sub :cart/subtotal-CORRECT
  :<- [:cart/line-totals]
  (fn [lines _]
    (reduce + 0 (map :subtotal lines))))

;; THE BUG: reads :cart/snapshot ([:checkout :snapshot] on cart-frame),
;; not :cart/items. After *Send to checkout* the snapshot drifts from
;; the live cart.
(rf/reg-sub :cart/subtotal-WRONG
  :<- [:cart/snapshot]
  (fn [items _]
    (reduce + 0 (map (fn [{:keys [price qty]}]
                       (* (or price 0) (or qty 0)))
                     items))))

;; The display sub — WIRED TO THE WRONG ONE. Swap to
;; :cart/subtotal-CORRECT to fix.
(rf/reg-sub :total-due
  :<- [:cart/subtotal-WRONG]     ;; ← THE BUG (the tutorial's 3pm beat)
  :<- [:cart/tax-rate]
  :<- [:cart/shipping]
  (fn [[subtotal tax-rate shipping] _]
    (let [tax (long (* (or subtotal 0) (or tax-rate 0)))]
      (+ (or subtotal 0) tax (or shipping 0)))))

;; ============================================================================
;; LAYER 3 — :checkout/flow state machine
;; ============================================================================
;;
;; cart-empty → ready → paying → confirmed | failed
;;
;; Guard `cart-non-empty?` gates `:ready` → `:paying` (event
;; `:checkout.flow/pay`). Entry actions `charge-card!` (entering
;; `:paying`) and `fire-confirmation!` (entering `:confirmed`) emit
;; the canonical machine traces (`:rf.machine/guard-evaluated`,
;; `:rf.machine/action-ran` — shipped in #1469 / rf2-2nwfd). Causa's
;; Machines lens shows the state chart + transition history
;; automatically.
;;
;; The flow's `:data` slot is the snapshot-aware bridge to the cart
;; — `:checkout.flow/start` carries the items snapshot into :data so
;; the action handlers can charge against the right amount without
;; re-reading app-db.

(def checkout-flow
  {:initial :cart-empty
   :data    {:items [] :amount 0 :last-error nil}

   :guards
   {:cart-non-empty?
    (fn cart-non-empty?-guard [data _event]
      (boolean (seq (:items data))))}

   :actions
   {:charge-card!
    ;; Entry action for :paying. Stub: a real implementation would
    ;; issue managed-HTTP against a charge endpoint. Returns no fx
    ;; — the testbed completes the flow synchronously via the
    ;; explicit :checkout.flow/charge-success event the
    ;; checkout-pay handler dispatches alongside :pay.
    (fn charge-card!-action [_data _event]
      {:data {:last-error nil}})

    :fire-confirmation!
    ;; Entry action for :confirmed. Stub: a real implementation would
    ;; persist the confirmed order and fire an email fx.
    (fn fire-confirmation!-action [_data _event]
      {})

    :record-failure
    (fn record-failure-action [data [_ {:keys [reason]}]]
      {:data (assoc data :last-error (or reason "charge failed"))})

    :seed-from-cart
    (fn seed-from-cart-action [_data [_ items]]
      {:data {:items  (vec items)
              :amount (reduce + 0
                              (map (fn [{:keys [price qty]}]
                                     (* (or price 0) (or qty 0)))
                                   items))
              :last-error nil}})}

   :states
   {:cart-empty
    {:on {:checkout.flow/start {:target :ready
                                :action :seed-from-cart}}}

    :ready
    {:on {:checkout.flow/pay [{:target :paying
                               :guard  :cart-non-empty?}]
          :checkout.flow/reset {:target :cart-empty}}}

    :paying
    {:entry :charge-card!
     :on    {:checkout.flow/charge-success {:target :confirmed}
             :checkout.flow/charge-failure {:target :failed
                                            :action :record-failure}}}

    :confirmed
    {:entry :fire-confirmation!
     :on    {:checkout.flow/reset {:target :cart-empty}}}

    :failed
    {:on {:checkout.flow/pay   [{:target :paying
                                 :guard  :cart-non-empty?}]
          :checkout.flow/reset {:target :cart-empty}}}}})

(rf/reg-machine :checkout/flow checkout-flow)

;; Machine-projection subs. The snapshot lives at
;; [:rf/machines :checkout/flow] (Spec 005).
(rf/reg-sub :checkout/state
  (fn [db _] (get-in db [:rf/machines :checkout/flow :state])))

(rf/reg-sub :checkout/last-error
  (fn [db _] (get-in db [:rf/machines :checkout/flow :data :last-error])))

;; The machine's :data slot carries the snapshot copied in by the
;; :seed-from-cart entry action — checkout-frame's view reads it
;; from here (the cart-frame's snapshot lives on a different frame's
;; app-db, and subs are per-frame).
(rf/reg-sub :checkout/machine-items
  (fn [db _] (get-in db [:rf/machines :checkout/flow :data :items])))

(rf/reg-sub :checkout/machine-amount
  (fn [db _] (get-in db [:rf/machines :checkout/flow :data :amount])))

;; ============================================================================
;; CROSS-FRAME BRIDGE — :cart-frame → :checkout-frame
;; ============================================================================
;;
;; Reserved `:dispatch` is intra-frame; cross-frame fan-out uses the
;; public `rf/dispatch` with `{:frame ...}` opts (Spec 002). Same
;; bridge-fx pattern as the prior `multi_frame_causa/` scaffold.

(rf/reg-fx ::dispatch-to-frame
  (fn [_ctx {:keys [event frame]}]
    (rf/dispatch event {:frame frame})))

(rf/reg-event-fx :cart/send-to-checkout
  (fn [{:keys [db]} _ev]
    (let [items (get-in db [:cart :items])]
      ;; Snapshot the cart into the LOCAL :checkout :snapshot slot so
      ;; the deliberately-wrong sub (LAYER 4) has something to lock
      ;; onto. Clear the live cart. Then drive the checkout-frame's
      ;; machine via the cross-frame envelope.
      {:db (-> db
               (assoc-in [:checkout :snapshot] (vec items))
               (assoc-in [:cart :items]        []))
       :fx [[::dispatch-to-frame
             {:event [:checkout/flow [:checkout.flow/start items]]
              :frame frame-checkout}]]})))

;; Cross-frame reset: when the checkout-frame's :reset fires, it also
;; clears the cart-frame's snapshot so the next add-to-cart resumes
;; from a clean slate.
(rf/reg-event-db :cart/clear-snapshot
  (fn [db _ev]
    (assoc-in db [:checkout :snapshot] [])))

;; ============================================================================
;; LAYER 3 (cont) — checkout-pay convenience handler
;; ============================================================================
;;
;; `:checkout/pay` dispatches the machine's :pay event and then,
;; synchronously, the charge-success event (stub — a real charge
;; would be async via managed-HTTP). The two-event sequence lands
;; the machine in :confirmed.

(rf/reg-event-fx :checkout/pay
  (fn [_ctx _ev]
    {:fx [[:dispatch [:checkout/flow [:checkout.flow/pay]]]
          [:dispatch [:checkout/flow [:checkout.flow/charge-success]]]]}))

(rf/reg-event-fx :checkout/reset
  (fn [_ctx _ev]
    {:fx [;; Drive the machine through its :reset transition (intra-frame).
          [:dispatch [:checkout/flow [:checkout.flow/reset]]]
          ;; Cross-frame: clear the cart-frame's snapshot too so the
          ;; deliberately-wrong sub stops locking the displayed total.
          [::dispatch-to-frame {:event [:cart/clear-snapshot]
                                :frame frame-cart}]]}))

(rf/reg-event-db :cart/set-http-outcome
  (fn [db [_ outcome]]
    (assoc-in db [:cart :next-http-outcome] outcome)))

(rf/reg-sub :cart/next-http-outcome
  (fn [db _] (get-in db [:cart :next-http-outcome])))

;; ============================================================================
;; LAYER 6 — :admin-frame events: audit, refund (with throw toggle),
;;           and the schema-violation source ('submit broken item')
;; ============================================================================

(rf/reg-event-db :admin/audit
  (fn [db _ev]
    (update-in db [:admin :audit-log] (fnil conj [])
               {:kind :audit :at (count (get-in db [:admin :audit-log]))})))

;; Handler throw — deterministic via the [:admin :refund-throw?] flag.
;; The runtime's per-handler try/catch lands the exception on the
;; Issues ribbon as :rf.error/handler-threw with the recovery
;; classification (per Spec 009).
(rf/reg-event-db :admin/refund
  (fn [db [_ amount]]
    (when (get-in db [:admin :refund-throw?])
      (throw (ex-info "refund failed — deterministic testbed throw"
                      {:amount amount
                       :rf/recovery :no-recovery})))
    (update-in db [:admin :audit-log] (fnil conj [])
               {:kind :refund :amount amount
                :at (count (get-in db [:admin :audit-log]))})))

(rf/reg-event-db :admin/toggle-refund-throw
  (fn [db _ev]
    (update-in db [:admin :refund-throw?] not)))

;; Submit-broken-item — fan a write of a :qty -1 line into
;; :cart-frame's [:cart :items] slot via the bridge fx. The CartItems
;; app-schema registered against that path on :cart-frame rejects the
;; resulting db (post-handler validation, per Spec 010 §Validation
;; order step 4): the :db effect rolls back, and the violation lands
;; in Causa's Issues ribbon.
(rf/reg-event-fx :admin/submit-broken-item
  (fn [_ctx _ev]
    {:fx [[::dispatch-to-frame
           {:event [:cart/_force-broken-item]
            :frame frame-cart}]]}))

(rf/reg-event-db :cart/_force-broken-item
  (fn [db _ev]
    (update-in db [:cart :items] (fnil conj [])
               {:id :gremlin :name "Gremlin" :price 100 :qty -1})))

(rf/reg-sub :admin/audit-log     (fn [db _] (get-in db [:admin :audit-log])))
(rf/reg-sub :admin/refund-throw? (fn [db _] (get-in db [:admin :refund-throw?])))
(rf/reg-sub :admin/flags         (fn [db _] (get-in db [:admin :flags])))

;; ============================================================================
;; VIEWS — one panel per frame, plus dev toggles
;; ============================================================================

(reg-view cart-panel []
  (let [catalogue   @(subscribe [:cart/catalogue])
        cat-error   @(subscribe [:cart/catalogue-error])
        line-totals @(subscribe [:cart/line-totals])
        total-due   @(subscribe [:total-due])
        outcome     @(subscribe [:cart/next-http-outcome])]
    [:section {:data-testid "cart-frame-panel"
               :style {:border "1px solid #2b7" :border-radius "6px"
                       :padding "1em 1.5em" :background "#f7fff9"
                       :min-width "280px" :flex 1}}
     [:h3 {:style {:margin-top 0 :color "#1a5"}}
      "Cart " [:small "(:cart-frame)"]]
     [:div {:style {:margin-bottom "0.5em" :display "flex" :gap "6px" :flex-wrap "wrap"}}
      [:button {:data-testid "cart-load-catalogue"
                :on-click    #(dispatch [:cart/load-catalogue])}
       "Reload catalogue (HTTP)"]
      [:button {:data-testid "cart-clear"
                :on-click    #(dispatch [:cart/clear])
                :style       {:margin-left "8px"}}
       "Clear cart"]]
     ;; Dev panel — toggle the next :cart/load-catalogue outcome.
     [:div {:style {:margin "0.25em 0 0.5em 0" :font-size "11px" :color "#444"}}
      "Next HTTP outcome: "
      (for [[opt label] [[:success           "200"]
                         [:rf.http/http-5xx  "500"]
                         [:rf.http/timeout   "timeout"]]]
        ^{:key opt}
        [:button {:data-testid (str "outcome-" (clojure.core/name opt))
                  :on-click    #(dispatch [:cart/set-http-outcome opt])
                  :style       {:font-size  "11px"
                                :background (if (= outcome opt) "#1a5" "#eef")
                                :color      (if (= outcome opt) "#fff" "#1a5")
                                :border     "1px solid #1a5"
                                :padding    "1px 6px"
                                :margin-left "4px"
                                :border-radius "3px"}}
         label])]
     (when cat-error
       [:p {:data-testid "cart-catalogue-error"
            :style {:color "#a40" :font-size "12px" :margin "0.5em 0"}}
        "Catalogue load failed: "
        [:code (str (:kind cat-error))]
        " (status=" (str (:status cat-error)) ")"])
     [:div {:style {:margin "0.5em 0" :font-size "12px" :color "#444"}}
      "Catalogue (" (count catalogue) " items): "
      (str/join ", " (map :name catalogue))]
     [:div {:style {:margin "0.5em 0" :display "flex" :gap "6px" :flex-wrap "wrap"}}
      (for [{:keys [id name]} catalogue]
        ^{:key id}
        [:button {:data-testid (str "cart-add-" (clojure.core/name id))
                  :on-click    #(dispatch [:cart/add-item id])}
         (str "+ " name)])]
     (if (seq line-totals)
       [:table {:style {:border-collapse "collapse" :width "100%"
                        :font-size "13px" :margin-bottom "0.5em"}}
        [:tbody
         (for [{:keys [id name qty subtotal]} line-totals]
           ^{:key id}
           [:tr {:data-testid (str "cart-line-" (clojure.core/name id))}
            [:td {:style {:padding "2px 8px"}} name]
            [:td {:style {:padding "2px 8px"}} (str "× " qty)]
            [:td {:style {:padding "2px 8px" :text-align "right"}}
             (format-money subtotal)]
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
      [:span "Total due"]
      [:span {:data-testid "cart-total-due"} (format-money total-due)]]
     [:p {:style {:font-size "11px" :color "#666" :margin "0.25em 0 0.5em 0"}}
      "Total includes tax + shipping. "
      [:em "Note: total reads off the checkout snapshot — see "]
      [:code ":cart/subtotal-WRONG"]
      [:em " in core.cljs."]]
     [:button {:data-testid "cart-send-to-checkout"
               :on-click    #(dispatch [:cart/send-to-checkout])
               :disabled    (empty? line-totals)
               :style       {:margin-top "0.5em"
                             :padding "6px 12px"
                             :background (if (seq line-totals) "#1a5" "#aaa")
                             :color "#fff" :border "none"
                             :border-radius "4px"
                             :cursor (if (seq line-totals) "pointer" "default")}}
      "Send to checkout (cross-frame → :checkout-frame)"]]))

(reg-view checkout-panel []
  (let [items      @(subscribe [:checkout/machine-items])
        amount     @(subscribe [:checkout/machine-amount])
        state      @(subscribe [:checkout/state])
        last-error @(subscribe [:checkout/last-error])]
    [:section {:data-testid "checkout-frame-panel"
               :style {:border "1px solid #36c" :border-radius "6px"
                       :padding "1em 1.5em" :background "#f5f8ff"
                       :min-width "280px" :flex 1}}
     [:h3 {:style {:margin-top 0 :color "#249"}}
      "Checkout " [:small "(:checkout-frame)"]]
     [:div {:style {:margin "0.25em 0 0.75em 0" :font-size "12px"}}
      "Machine state: "
      [:span {:data-testid "checkout-machine-state"
              :style {:font-weight "bold"
                      :color (case state
                               :confirmed "#1a5"
                               :failed    "#a40"
                               :paying    "#249"
                               "#444")}}
       (str state)]
      (when last-error
        [:span {:data-testid "checkout-last-error"
                :style {:margin-left "8px" :color "#a40"}}
         " · last-error=" (pr-str last-error)])]
     [:div {:style {:margin-bottom "0.75em" :display "flex" :gap "6px" :flex-wrap "wrap"}}
      [:button {:data-testid "checkout-pay"
                :on-click    #(dispatch [:checkout/pay])
                :disabled    (not (#{:ready :failed} state))}
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
             (format-money (* (or price 0) (or qty 0)))]])
         [:tr
          [:td {:style {:padding "4px 8px" :font-weight "bold"
                        :border-top "1px solid #cce"}}
           "Charge amount"]
          [:td {:style {:padding "4px 8px"}}]
          [:td {:style {:padding "4px 8px" :text-align "right" :font-weight "bold"
                        :border-top "1px solid #cce"}
                :data-testid "checkout-charge-amount"}
           (format-money amount)]]]]
       [:p {:style {:color "#666" :font-style "italic" :margin "0.5em 0"}}
        "Machine has not started yet. Click "
        [:em "Send to checkout"] " on the cart."])]))

(reg-view admin-panel []
  (let [audit-log     @(subscribe [:admin/audit-log])
        refund-throw? @(subscribe [:admin/refund-throw?])
        flags         @(subscribe [:admin/flags])]
    [:section {:data-testid "admin-frame-panel"
               :style {:border "1px solid #c63" :border-radius "6px"
                       :padding "1em 1.5em" :background "#fff8f3"
                       :min-width "280px" :flex 1}}
     [:h3 {:style {:margin-top 0 :color "#a40"}}
      "Admin " [:small "(:admin-frame)"]]
     [:div {:style {:margin-bottom "0.5em" :display "flex" :gap "6px" :flex-wrap "wrap"}}
      [:button {:data-testid "admin-audit"
                :on-click    #(dispatch [:admin/audit])}
       "Run audit"]
      [:button {:data-testid "admin-refund"
                :on-click    #(dispatch [:admin/refund 250])}
       "Issue refund ($2.50)"]
      [:button {:data-testid "admin-toggle-refund-throw"
                :on-click    #(dispatch [:admin/toggle-refund-throw])
                :style       {:background (if refund-throw? "#a40" "#eef")
                              :color      (if refund-throw? "#fff" "#444")
                              :border     "1px solid #a40"
                              :padding    "2px 10px"
                              :font-size  "11px"
                              :border-radius "3px"}}
       (if refund-throw?
         "refund-throw: ON (next refund throws)"
         "refund-throw: off")]]
     [:div {:style {:margin "0.5em 0" :font-size "12px" :color "#666"}}
      "Issues source: "
      [:button {:data-testid "admin-submit-broken-item"
                :on-click    #(dispatch [:admin/submit-broken-item])
                :style       {:font-size "11px"}}
       "Submit broken item (schema violation)"]
      [:span {:style {:margin-left "8px" :font-style "italic"}}
       "qty=-1 fails CartItem schema; app-db rollback."]]
     [:p {:style {:margin "0.5em 0" :font-size "11px" :color "#888"}}
      "flags: "
      (pr-str flags)]
     (if (seq audit-log)
       [:ul {:data-testid "admin-audit-list"
             :style {:max-height "8em" :overflow :auto
                     :font-size "12px" :font-family "monospace"
                     :background "#fff" :border "1px solid #f2dcc4"
                     :border-radius "4px" :padding "4px 12px"
                     :margin "0.5em 0"}}
        (for [[idx entry] (map-indexed vector audit-log)]
          ^{:key idx}
          [:li {:data-testid (str "admin-audit-" idx)} (pr-str entry)])]
       [:p {:style {:color "#666" :font-style "italic" :margin "0.5em 0"}}
        "No admin entries yet."])]))

(reg-view root []
  [:div {:data-testid "shop-root"
         :style {:font-family "system-ui, sans-serif" :padding "1em"
                 :max-width "1100px" :margin "0 auto"}}
   [:h2 {:style {:margin-top 0}} "Shop testbed"]
   [:p {:style {:color "#444" :margin "0.25em 0 1em 0"}}
    "One demo, six Causa lenses. Three frames coexist on this page: "
    [:code ":cart-frame"] ", "
    [:code ":checkout-frame"] ", "
    [:code ":admin-frame"] ". "
    "The cart fetches a catalogue over managed HTTP; the checkout runs "
    "a "
    [:code ":checkout/flow"]
    " state machine; the admin panel sources both schema violations "
    "and deterministic handler throws. The displayed total deliberately "
    "reads off the checkout snapshot (the cart-total tutorial beat) — "
    "the bug is one click away in Causa's Views lens."]
   [:p {:style {:color "#666" :font-size "12px" :margin "0 0 1em 0"}}
    "Open Causa (Ctrl+Shift+C). Walk the lenses in order: "
    "Event → App-db → Views → Trace → Machines → Issues."]
   [:div {:style {:display "flex" :gap "1em" :flex-wrap "wrap"
                  :align-items "flex-start"}}
    [rf/frame-provider {:frame frame-cart}     [cart-panel]]
    [rf/frame-provider {:frame frame-checkout} [checkout-panel]]
    [rf/frame-provider {:frame frame-admin}    [admin-panel]]]])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; Register the three frames; :on-create seeds each frame's
  ;; app-db synchronously per Spec 002 §Frame creation.
  (rf/reg-frame frame-cart     {:on-create [:cart/init]})
  (rf/reg-frame frame-checkout {:on-create [:checkout/init]})
  (rf/reg-frame frame-admin    {:on-create [:admin/init]})
  ;; Register the cart-item schema against :cart-frame so the post-
  ;; handler validation step catches the admin's deliberately-broken
  ;; line item (LAYER 6). reg-app-schema is frame-scoped via the
  ;; optional :frame opt (Spec 010 §Per-frame schemas).
  (rf/reg-app-schema [:cart :items] CartItems {:frame frame-cart})
  ;; Kick off the catalogue fetch on first paint so the testbed
  ;; opens with a live HTTP cascade visible in Causa's Trace lens.
  (rf/dispatch [:cart/load-catalogue] {:frame frame-cart})
  (rdc/render react-root [root]))
