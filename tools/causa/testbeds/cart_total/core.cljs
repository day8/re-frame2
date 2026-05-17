(ns cart-total.core
  "Cart-total testbed (rf2-0sg12) — Causa hero scenario as runnable code.

  The Causa tutorial's index page opens with a 3pm cart-total scenario
  (`docs/causa/index.md:13-30`):

    > A tester drops a screenshot on your desk: the wrong number is
    > showing in the cart total. The HTML they paste back carries
    > `data-rf2-source-coord` on the cart-total span. You read the line.
    > The sub reads `:cart/items`. You click the dependency edge. The
    > upstream sub is reading `:checkout/items`. Wrong slot.

  This testbed is the runnable version of that scenario. It ships the
  cart shape, the wrong sub, and a deliberate `Checkout` button that
  drifts the projection so the cart total reads zero while the cart
  still visibly contains items. Open Causa, click `+ Apple`, click
  *Checkout*, watch the total flip from $1.50 to $0.00, and walk the
  sub-graph back to the wrong slot.

  Reader takeaway: every step on `docs/causa/index.md:21-28` is a click
  away from this page in your browser — coord on the wire, sub-graph
  navigation in the panel, the wrong slot visible in app-db diff.

  Domain shape:

    {:cart/items       [{:id :apple :name \"Apple\" :price 150 :qty 2}
                        {:id :bread :name \"Bread\" :price 350 :qty 1}]
     :cart/discount    nil
     :checkout/items   []                  ;; populated mid-checkout
     :checkout/status  :idle               ;; :idle | :submitting | :error | :done
     :checkout/error   nil                 ;; failure-map after a 5xx
     :customer         {:customer/email nil
                        :payment/token  nil}} ;; both schema-:sensitive?

  Two state slots; one is the *live* basket the user sees in the
  cart, the other is the snapshot the checkout flow took. The bug
  the tutorial describes is the projection sub reading the wrong
  one of those two.

  ## Boundary variants (rf2-4ip3r) — HTTP error + PII redaction

  Two additional cascades the pure-data variants couldn't express:

  * Mid-flight HTTP 5xx during *Finalize checkout* — the Finalize
    button issues a managed-HTTP request via
    `:rf.http/managed-canned-failure` (a deterministic stub from
    `re-frame.http-test-support` per Spec 014 §Testing) so Causa's
    Effects + Trace + Issues panels can be inspected against an
    error cascade without crossing the network. The reply lands
    `:checkout/error` in app-db and flips `:checkout/status` to
    `:error`.

  * PII redaction — `:customer/email` and `:payment/token` are
    declared `:sensitive? true` via `reg-app-schema` on the
    `[:customer]` slice. The handler `:cart/set-customer-pii`
    carries `:sensitive? true` registration meta + `(rf/path
    :customer)` so the schema-derived redaction interceptor
    replaces the event payload with `:rf/redacted` on the trace /
    MCP wire while the live handler still receives the raw values.

  This file is deliberately self-contained as an app, but it also
  exposes `#/stories` so the same Causa bug can be inspected through
  Story variants. Story coverage lives in `stories.cljs`, which reuses
  this app's real handlers, subscriptions, and views. The browser-side spec at `spec.cjs` clicks the
  +Apple / +Bread / Apply discount / Checkout buttons and asserts on
  the rendered cart total. Bundle-isolation: this testbed lives under
  `tools/causa/testbeds/`; nothing under `implementation/` :requires
  it (per tools/README.md)."
  (:require [reagent.core       :as r]
            [reagent.dom.client :as rdc]
            [re-frame.core      :as rf]
            [re-frame.schemas]
            [re-frame.story     :as story]
            [re-frame.adapter.reagent :as reagent-adapter]
            ;; Managed-HTTP ships in day8/re-frame2-http. Requiring at
            ;; app boot triggers its load-time fx registrations
            ;; (`:rf.http/managed` / `:rf.http/managed-abort`); without
            ;; it, dispatching the HTTP-5xx cascade would fail with
            ;; :rf.error/no-such-fx.
            [re-frame.http-managed]
            ;; rf2-4ip3r — the HTTP-5xx variant drives
            ;; `:rf.http/managed-canned-failure` directly via :fx so
            ;; the cascade is deterministic under the Playwright smoke.
            ;; Per the rf2-zk08x gate, the canned-stub fx ids register
            ;; from `re-frame.http-test-support`. A testbed IS a test
            ;; affordance, so requiring it here is correct (mirrors the
            ;; pattern in testbeds/http_toggle/core.cljs).
            [re-frame.http-test-support]
            [cart-total.fixtures :as fixtures]
            [cart-total.stories])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; ============================================================================
;; CATALOGUE — three items the +Add buttons reference by id
;; ============================================================================
;;
;; Prices are in cents to keep the arithmetic exact — `(+ 150 350)` is
;; precise, `(+ 1.50 3.50)` would invite floating-point grief on a
;; tutorial page. The view divides by 100 when it formats for display.

(def catalogue
  {:apple  {:id :apple  :name "Apple"  :price 150}
   :bread  {:id :bread  :name "Bread"  :price 350}
   :coffee {:id :coffee :name "Coffee" :price 450}})

;; ============================================================================
;; EVENTS  (CP-1) — the cart flow
;; ============================================================================

(rf/reg-event-db :cart/initialise
  (fn [db _event]
    ;; Story variant frames carry their own lifecycle/runtime slots in
    ;; app-db. Initialise only the cart's domain slots so the same event
    ;; is safe in both the live app and Story-isolated frames.
    (assoc db
           :cart/items      []
           :cart/discount   nil
           :checkout/items  []
           ;; rf2-4ip3r — checkout-finalize HTTP state. :status flips
           ;; :idle → :submitting → :error|:done; :error carries the
           ;; failure map the 5xx variant pins for Causa inspection.
           :checkout/status :idle
           :checkout/error  nil
           ;; rf2-4ip3r — :customer slice is path-scoped so the
           ;; schema-derived `:sensitive?` redaction interceptor fires
           ;; on every event routed through `(rf/path :customer)`.
           :customer        {:customer/email nil
                             :payment/token  nil})))

(rf/reg-event-db :cart/add-item
  (fn [db [_ item-id]]
    (let [item (get catalogue item-id)
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

;; Discount codes: small fixed table; production app would read this
;; off the server. Two codes — STUDENT (10% off) and FRIEND (20% off).
(def discount-codes
  {"STUDENT" {:code "STUDENT" :percent 10}
   "FRIEND"  {:code "FRIEND"  :percent 20}})

(rf/reg-event-db :cart/apply-discount
  (fn [db [_ code]]
    (assoc db :cart/discount (get discount-codes code))))

(rf/reg-event-db :cart/clear-discount
  (fn [db _event]
    (assoc db :cart/discount nil)))

;; The bug the 3pm scenario exposes — start of checkout snapshots the
;; basket into `:checkout/items` and then *clears* the live basket. A
;; correct projection sub reads `:cart/items`; the WRONG sub (below)
;; reads `:checkout/items` after the user has clicked *Checkout*. Both
;; slots show non-empty in app-db immediately after; the user sees the
;; live `:cart/items` re-populate on the next add but the displayed
;; total stays at $0.00 because the projection is locked to the
;; emptied checkout slot.
(rf/reg-event-db :checkout/start
  (fn [db _event]
    (-> db
        ;; Snapshot the basket — production would freeze the lines here
        ;; before shipping payment to the server.
        (assoc :checkout/items (:cart/items db))
        ;; ... and clear the live basket so a second checkout session
        ;; doesn't double-count. THIS is the moment after which the
        ;; wrong-slot sub starts producing the wrong total.
        (assoc :cart/items []))))

(rf/reg-event-db :checkout/reset
  (fn [db _event]
    (assoc db
           :checkout/items  []
           :checkout/status :idle
           :checkout/error  nil)))

;; ============================================================================
;; rf2-4ip3r — Boundary variant 1: mid-flight HTTP 5xx during finalize
;; ============================================================================
;;
;; The Finalize button issues a managed-HTTP POST against a stub
;; endpoint. The Spec 014 §Testing canned-failure stub
;; (`:rf.http/managed-canned-failure`) synthesises a deterministic 5xx
;; reply via the same late-bind dispatch path the live transport uses,
;; so Causa's Effects / Trace / Issues / Performance panels all see
;; the canonical `:rf.http/http-5xx` cascade without the test crossing
;; the network.
;;
;; Default reply-addressing sends the reply back to the originating
;; event id (`:checkout/finalize`) with `:rf/reply` merged. The reply
;; branch lands the failure-map at `:checkout/error` and flips
;; `:checkout/status` to `:error` for the in-page banner. Open Causa,
;; click Finalize, and walk the resulting cascade.

(rf/reg-event-fx :checkout/finalize
  (fn [{:keys [db]} [_ msg]]
    (cond
      ;; Reply branch — the canned-failure stub fires this with
      ;; {:kind :failure :failure {...}}. Pin the failure-map at
      ;; :checkout/error and flip :checkout/status so the banner
      ;; renders and the spec / Story assertion can read both.
      (some-> msg :rf/reply :kind (= :failure))
      {:db (-> db
               (assoc :checkout/status :error)
               (assoc :checkout/error  (:failure (:rf/reply msg))))}

      ;; Reply branch — success (live path; the stub never fires this).
      (some-> msg :rf/reply :kind (= :success))
      {:db (-> db
               (assoc :checkout/status :done)
               (assoc :checkout/error  nil))}

      ;; Initial branch — issue the request via the canned-failure
      ;; stub. The args-map is the same shape `:rf.http/managed`
      ;; would carry; the canned stub short-circuits the live
      ;; Fetch and synthesises the {:kind :failure ...} reply via
      ;; the default reply-addressing path (back to :checkout/finalize).
      :else
      {:db (-> db
               (assoc :checkout/status :submitting)
               (assoc :checkout/error  nil))
       :fx [[:rf.http/managed-canned-failure
             {:request    {:method :post :url "/api/checkout"}
              :request-id ::checkout-in-flight
              :decode     :json
              :kind       :rf.http/http-5xx
              :tags       {:status      503
                           :status-text "Service Unavailable"
                           :body        "{\"error\":\"checkout temporarily unavailable\"}"
                           :headers     {}}}]]})))

;; ============================================================================
;; rf2-4ip3r — Boundary variant 2: schema-derived PII redaction
;; ============================================================================
;;
;; `:customer/email` and `:payment/token` carry `:sensitive? true` in
;; their Malli slot props. Registering the schema on the `[:customer]`
;; slice + calling `populate-sensitive-from-schemas!` at boot wires
;; the schema-derived redaction interceptor (per Spec 010 §`:sensitive?`)
;; for any handler path-scoped through `(rf/path :customer)`.
;;
;; The handler `:cart/set-customer-pii` carries both `:sensitive? true`
;; registration meta (drops the substrate-level event-emit record per
;; rf2-6hklf) AND the `(rf/path :customer)` interceptor (so the trace /
;; MCP wire sees the payload replaced with `:rf/redacted`). The
;; handler body still receives the raw values; only the wire shapes
;; change. App-DB Diff's `:rf/redacted` display + sensitive-trace
;; count badge can be inspected in Causa as the user types.

(def CustomerSlice
  ;; Both PII slots carry `:sensitive? true` so the schema walker
  ;; produces redaction entries at `[:customer :customer/email]` and
  ;; `[:customer :payment/token]` (Spec 010 §Schema metadata flags).
  [:map
   [:customer/email {:optional true :sensitive? true} [:maybe :string]]
   [:payment/token  {:optional true :sensitive? true} [:maybe :string]]])

(rf/reg-app-schema [:customer] CustomerSlice)

(rf/reg-event-db :cart/set-customer-pii
  {:doc        "Stash the customer's email + payment token in the
                schema-:sensitive? :customer slice. The handler body
                receives the raw payload; the trace, error-emit, and
                MCP wire surfaces see the event payload redacted to
                :rf/redacted (Spec 010 §`:sensitive?` + Spec 009
                §Privacy)."
   :sensitive? true}
  [(rf/path :customer)]
  (fn [customer [_ {:keys [email token]}]]
    (-> (or customer {})
        (assoc :customer/email email)
        (assoc :payment/token  token))))

(rf/reg-event-db :cart/clear-customer-pii
  {:doc "Clear the customer PII slice — wipes both sensitive slots."}
  [(rf/path :customer)]
  (fn [_customer _event]
    {:customer/email nil
     :payment/token  nil}))

;; ============================================================================
;; SUBSCRIPTIONS  (CP-2) — the projection graph, with the deliberate bug
;; ============================================================================
;;
;; Three layers:
;;
;;   :cart/items                            (root — reads app-db directly)
;;   :cart/line-totals → :cart/items        (derived per-line subtotals)
;;   :cart/subtotal    → :cart/line-totals
;;   :cart/total       → :cart/subtotal, :cart/discount   ← THE BUG LIVES HERE
;;
;; The BUG: `:cart/total` reads `:checkout/items` instead of
;; `:cart/items` (via the wrong upstream `:cart/subtotal`). The view's
;; cart-total span subscribes to `:cart/total`. After a *Checkout*
;; click, the live `:cart/items` repopulates on the next +Add, but
;; the total is locked to the emptied snapshot.
;;
;; The tutorial's 3pm scenario walk-through:
;;
;;   1. Tester sends a screenshot. data-rf2-source-coord on the
;;      cart-total span points at this file, line 173, col 4
;;      (`cart-total-line`).
;;
;;   2. Read the function. It subscribes to `:cart/total`.
;;
;;   3. Open Causa, Subscriptions panel, find `:cart/total`. Watch
;;      it recompute as you click +Add / Apply discount / Checkout.
;;
;;   4. Click the dependency edge from `:cart/total` to its upstream.
;;      The upstream is `:cart/subtotal-WRONG` reading
;;      `:checkout/items`. That's the wrong slot.
;;
;;   5. App-db diff panel shows `:cart/items` and `:checkout/items`
;;      are distinct values. The fix is one line.

(rf/reg-sub :cart/items
  (fn [db _query]
    (:cart/items db)))

(rf/reg-sub :checkout/items
  (fn [db _query]
    (:checkout/items db)))

(rf/reg-sub :cart/discount
  (fn [db _query]
    (:cart/discount db)))

(rf/reg-sub :cart/line-totals
  :<- [:cart/items]
  (fn [items _query]
    (mapv (fn [{:keys [id name price qty]}]
            {:id id :name name :qty qty :subtotal (* price qty)})
          items)))

;; The CORRECT subtotal — what the tutorial's fix replaces the buggy
;; one with. Left in the file so the reader can see the contrast and
;; the one-line diff that closes the bug.
(rf/reg-sub :cart/subtotal-CORRECT
  :<- [:cart/line-totals]
  (fn [lines _query]
    (reduce + 0 (map :subtotal lines))))

;; THE BUG: this layer-2 sub reads `:checkout/items` (the snapshot the
;; checkout-start handler froze) instead of `:cart/items` (the live
;; basket). After *Checkout* the snapshot drifts from the live basket,
;; and the cart-total span starts showing the snapshot's total — which
;; is whatever was in the basket the moment Checkout was clicked, not
;; what's there now.
;;
;; Keep this id close to the correct one so the reader can see the
;; one-line diff that fixes the bug — the swap is from `:checkout/items`
;; to `:cart/items`.
(rf/reg-sub :cart/subtotal-WRONG
  :<- [:checkout/items]
  (fn [items _query]
    (reduce + 0 (map (fn [{:keys [price qty]}] (* price qty)) items))))

;; The display sub. WIRED TO THE WRONG ONE — `:cart/subtotal-WRONG`.
;; The tutorial's fix is a one-token edit on this line: change
;; `:cart/subtotal-WRONG` to `:cart/subtotal-CORRECT`. That's the whole
;; fix; the rest of the dataflow stays put.
(rf/reg-sub :cart/total
  :<- [:cart/subtotal-WRONG]                       ;; ← THE BUG
  :<- [:cart/discount]
  (fn [[subtotal discount] _query]
    (let [percent (:percent discount 0)
          deduction (long (/ (* subtotal percent) 100))]
      (- subtotal deduction))))

(rf/reg-sub :cart/checkout-active?
  :<- [:checkout/items]
  (fn [items _query]
    (boolean (seq items))))

;; rf2-4ip3r — checkout-finalize HTTP cascade subs. :checkout/status
;; flips :idle → :submitting → :error|:done; :checkout/error carries
;; the failure-map post-5xx so Causa's App-DB Diff can show the
;; failure shape on its own row.

(rf/reg-sub :checkout/status
  (fn [db _query]
    (:checkout/status db)))

(rf/reg-sub :checkout/error
  (fn [db _query]
    (:checkout/error db)))

;; rf2-4ip3r — :customer slice subs. Both slots are schema-`:sensitive?`,
;; so the trace / MCP wire never sees the raw values when a handler
;; routes through `(rf/path :customer)`. The subs return the LIVE
;; values for the view layer — `:sensitive?` is about the wire shape,
;; not about what the app itself can read (per Spec 010 §`:sensitive?`).

(rf/reg-sub :customer/email
  (fn [db _query]
    (get-in db [:customer :customer/email])))

(rf/reg-sub :payment/token
  (fn [db _query]
    (get-in db [:customer :payment/token])))

(rf/reg-sub :customer/pii-set?
  :<- [:customer/email]
  :<- [:payment/token]
  (fn [[email token] _query]
    (boolean (or (seq email) (seq token)))))

;; ============================================================================
;; VIEWS  (CP-4) — the cart UI
;; ============================================================================

(defn- format-money
  "Cents → display string. `150` → `\"$1.50\"`."
  [cents]
  (let [dollars (quot cents 100)
        change  (mod cents 100)]
    (str "$" dollars "." (if (< change 10) (str "0" change) change))))

(reg-view catalogue-row [{:keys [item-id]}]
  (let [{:keys [name price]} (get catalogue item-id)]
    [:tr
     [:td {:style {:padding "4px 12px"}} name]
     [:td {:style {:padding "4px 12px" :color "#666"}} (format-money price)]
     [:td {:style {:padding "4px 12px"}}
      [:button {:on-click   #(dispatch [:cart/add-item item-id])
                :data-test  (str "add-" (clojure.core/name item-id))
                :aria-label (str "Add " name " to cart")}
       "+ Add"]]]))

(reg-view catalogue-table []
  [:section
   [:h3 {:style {:margin-top 0}} "Catalogue"]
   [:table {:style {:border-collapse "collapse" :margin-bottom "1em"}}
    [:tbody
     [catalogue-row {:item-id :apple}]
     [catalogue-row {:item-id :bread}]
     [catalogue-row {:item-id :coffee}]]]])

(reg-view cart-line [{:keys [line]}]
  (let [{:keys [id name qty subtotal]} line]
    [:tr {:data-test (str "line-" (clojure.core/name id))}
     [:td {:style {:padding "4px 12px"}}     name]
     [:td {:style {:padding "4px 12px"}}     (str "× " qty)]
     [:td {:style {:padding "4px 12px" :text-align "right"}}
      (format-money subtotal)]
     [:td {:style {:padding "4px 12px"}}
      [:button {:on-click   #(dispatch [:cart/remove-item id])
                :data-test  (str "remove-" (clojure.core/name id))
                :aria-label (str "Remove " name " from cart")}
       "✕"]]]))

(reg-view discount-row []
  (let [state (r/atom "")]
    (fn []
      (let [discount @(subscribe [:cart/discount])]
        [:div {:style {:margin "1em 0" :display "flex" :gap "8px" :align-items "center"}}
         [:input {:type        "text"
                  :placeholder "Discount code"
                  :data-test   "discount-code"
                  :on-change   #(reset! state (.. % -target -value))}]
         [:button {:on-click   #(dispatch [:cart/apply-discount @state])
                   :data-test  "apply-discount"
                   :aria-label "Apply discount code"}
          "Apply"]
         (when discount
           [:span {:style    {:font-size "13px" :color "#107c10"}
                   :data-test "discount-applied"}
            (str (:code discount) " — " (:percent discount) "% off")
            " "
            [:button {:on-click  #(dispatch [:cart/clear-discount])
                      :data-test "clear-discount"
                      :style     {:font-size "11px" :margin-left "6px"}}
             "clear"]])]))))

(reg-view cart-total-line []
  ;; The DOM node that carries `data-rf2-source-coord` pointing at this
  ;; reg-view. The 3pm scenario's first step is the tester right-
  ;; clicking THIS span and copying the element; the coord routes the
  ;; reader back here, and the reader walks the `:cart/total` sub from
  ;; this line.
  [:div {:style {:display "flex" :justify-content "space-between"
                 :font-weight "bold" :font-size "16px"
                 :border-top  "1px solid #ddd" :padding-top "8px" :margin-top "8px"}}
   [:span "Total"]
   [:span {:data-test "cart-total"}
    (format-money @(subscribe [:cart/total]))]])

(reg-view checkout-banner []
  ;; Surfaces the bug to a casual visitor — when the snapshot is non-
  ;; empty but the live basket has been re-populated, the cart total
  ;; the user sees is the SNAPSHOT'S total, not the basket's. The
  ;; banner says so out loud.
  (let [active? @(subscribe [:cart/checkout-active?])]
    (when active?
      [:div {:style {:background "#fff4ce" :border "1px solid #d4b106"
                     :padding    "8px 12px" :border-radius "4px"
                     :font-size  "13px" :margin "0 0 1em 0"}
             :data-test "checkout-banner"}
       [:strong "Checkout in progress."]
       " The cart total above reflects the snapshot taken at checkout — "
       "this is the wrong-slot bug from the tutorial. "
       [:button {:on-click  #(dispatch [:checkout/reset])
                 :data-test "checkout-reset"
                 :style     {:margin-left "8px"}}
        "Reset checkout"]])))

(reg-view checkout-http-error-banner []
  ;; rf2-4ip3r — surfaces the HTTP-5xx failure-map from
  ;; `:checkout/error` for the inspector and the Playwright spec.
  ;; Causa's Effects + Trace + Issues panels also carry this same
  ;; cascade end-to-end; this banner is the DOM mirror so a spec
  ;; can assert against the rendered state directly.
  (let [status @(subscribe [:checkout/status])
        error  @(subscribe [:checkout/error])]
    (when (= :error status)
      [:div {:style {:background "#fde7e9" :border "1px solid #c50f1f"
                     :padding    "8px 12px" :border-radius "4px"
                     :font-size  "13px" :margin "0 0 1em 0" :color "#a4262c"}
             :data-test "checkout-http-error"}
       [:strong "Checkout finalize failed."]
       " status="
       [:span {:data-test "checkout-http-error-status"}
        (str (:status error "?"))]
       " kind="
       [:span {:data-test "checkout-http-error-kind"}
        (pr-str (:kind error))]])))

(reg-view customer-pii-form []
  ;; rf2-4ip3r — small inline form for the PII slice. Typing into
  ;; either field dispatches `:cart/set-customer-pii`, which carries
  ;; `:sensitive? true` registration meta + `(rf/path :customer)` so
  ;; the trace / MCP wire sees the event payload replaced with
  ;; `:rf/redacted`. The handler still receives the raw values —
  ;; that's why these subs render the actual text.
  (let [state (r/atom {:email nil :token nil})]
    (fn []
      (let [email     @(subscribe [:customer/email])
            token     @(subscribe [:payment/token])
            pii-set?  @(subscribe [:customer/pii-set?])
            current   (fn [k live] (or (get @state k) live))]
        [:section {:style {:margin-top "1.5em" :border "1px solid #e0e0e0"
                           :border-radius "6px" :padding "0.75em 1em"
                           :background "#fafafa"}
                   :data-test "customer-pii-form"}
         [:h4 {:style {:margin "0 0 0.5em 0"}} "Customer details (PII)"]
         [:p {:style {:font-size "11px" :color "#666" :margin "0 0 0.5em 0"}}
          "Schema-:sensitive? slots. The trace / MCP wire sees "
          [:code ":rf/redacted"]
          " — open Causa's App-DB Diff to inspect."]
         [:div {:style {:display "flex" :gap "8px" :flex-wrap "wrap"
                        :align-items "center"}}
          [:input {:type        "email"
                   :placeholder "you@example.com"
                   :data-test   "customer-email-input"
                   :value       (or (current :email email) "")
                   :on-change   #(swap! state assoc :email (.. % -target -value))}]
          [:input {:type        "text"
                   :placeholder "payment token"
                   :data-test   "payment-token-input"
                   :value       (or (current :token token) "")
                   :on-change   #(swap! state assoc :token (.. % -target -value))}]
          [:button {:on-click  #(dispatch [:cart/set-customer-pii
                                           {:email (or (:email @state) email "")
                                            :token (or (:token @state) token "")}])
                    :data-test "set-customer-pii"}
           "Save"]
          (when pii-set?
            [:button {:on-click  #(do (reset! state {:email nil :token nil})
                                      (dispatch [:cart/clear-customer-pii]))
                      :data-test "clear-customer-pii"
                      :style     {:font-size "11px"}}
             "clear"])]
         (when pii-set?
           [:p {:style {:margin "0.5em 0 0 0" :font-size "12px" :color "#107c10"}
                :data-test "pii-saved"}
            "PII stored at "
            [:code "[:customer]"]
            ". email-len="
            [:span {:data-test "pii-email-length"} (count (str email))]
            " token-len="
            [:span {:data-test "pii-token-length"} (count (str token))]])]))))

(reg-view cart-panel []
  (let [lines           @(subscribe [:cart/line-totals])
        discount        @(subscribe [:cart/discount])
        active?         @(subscribe [:cart/checkout-active?])
        checkout-status @(subscribe [:checkout/status])]
    [:section {:style {:border "1px solid #ddd" :border-radius "6px"
                       :padding "1em 1.5em" :background "#fff"
                       :min-width "320px"}}
     [:h3 {:style {:margin-top 0}} "Cart"]
     [checkout-banner]
     [checkout-http-error-banner]
     (if (seq lines)
       [:table {:style {:border-collapse "collapse" :width "100%"}}
        [:tbody
         (for [line lines]
           ^{:key (:id line)} [cart-line {:line line}])]]
       [:p {:style {:color "#666" :font-style "italic"}}
        "Cart is empty. Add an item from the catalogue."])
     [discount-row]
     (when discount
       [:div {:style {:display "flex" :justify-content "space-between"
                      :color "#107c10" :font-size "13px"}
              :data-test "cart-discount-row"}
        [:span (str "Discount (" (:percent discount) "%)")]
        [:span "—"]])
     [cart-total-line]
     [:div {:style {:margin-top "1em" :display "flex" :justify-content "flex-end"
                    :gap "8px"}}
      [:button {:on-click   #(dispatch [:checkout/start])
                :data-test  "checkout-start"
                :aria-label "Start checkout"
                :style      {:padding "6px 14px"}}
       "Checkout"]
      ;; rf2-4ip3r — Finalize fires the HTTP-5xx cascade. Disabled
      ;; until checkout has started (so the user can see the natural
      ;; cart → snapshot → finalize ordering); not gated on the
      ;; cascade's :submitting state — Causa wants to see the
      ;; click-through, not be blocked at the UI.
      [:button {:on-click   #(dispatch [:checkout/finalize])
                :data-test  "checkout-finalize"
                :aria-label "Finalize checkout"
                :disabled   (not active?)
                :style      {:padding "6px 14px"}}
       (case checkout-status
         :submitting "Finalizing..."
         :error      "Retry finalize"
         "Finalize")]]
     [customer-pii-form]]))

(reg-view tutorial-hint []
  ;; A tiny tutorial-context badge so a curious visitor who lands on
  ;; the testbed without context knows what they're looking at and
  ;; where the bug walk-through lives.
  [:aside {:style {:font-size "12px" :color "#555"
                   :background "#f5f5f5" :border "1px solid #e0e0e0"
                   :padding    "8px 12px" :margin-top "2em" :border-radius "4px"}}
   [:strong "Tutorial testbed."]
   " This page is the runnable version of the 3pm cart-total scenario from"
   " "
   [:a {:href "../docs/causa/"} "the Causa tutorial"]
   ". Click "
   [:em "Checkout"]
   ", then add another item — the total stays locked to the snapshot."
   " Open Causa (Ctrl+Shift+C) and walk the "
   [:code "{:cart/total}"]
   " sub back to the wrong slot."])

(reg-view cart-app []
  [:div {:style {:padding     "2em"
                 :font-family "system-ui, sans-serif"
                 :max-width   "720px"
                 :margin      "0 auto"}}
   [:h2 {:style {:margin-top 0}} "Cart-total testbed"]
   [:div {:style {:display "flex" :gap "2em" :flex-wrap "wrap"
                  :align-items "flex-start"}}
    [catalogue-table]
    [cart-panel]]
   [tutorial-hint]])

(reg-view cart-story-app [_args]
  ;; Story passes resolved args into every :component view. The testbed
  ;; app itself has no args, so this tiny adapter keeps the live view
  ;; unchanged while making it Story-renderable.
  [cart-app])

;; ============================================================================
;; MOUNT
;; ============================================================================

(defonce ^:private app-root
  (atom nil))

(defn- ensure-app-root! []
  (when (nil? @app-root)
    (reset! app-root (rdc/create-root (js/document.getElementById "app"))))
  @app-root)

(defn- tear-down-app-root! []
  (when-let [root @app-root]
    (try (rdc/unmount root) (catch :default _ nil))
    (reset! app-root nil)))

(defn- mount-app! []
  (story/unmount-shell!)
  (ensure-app-root!)
  (fixtures/dispatch-sync-events! fixtures/tutorial-seed-events)
  (rdc/render @app-root [cart-app]))

(defn- mount-stories! []
  (tear-down-app-root!)
  (story/mount-shell! (js/document.getElementById "app")))

(defn- on-hash-change! []
  (let [hash (or (.. js/window -location -hash) "")]
    (if (re-find #"^#/stories" hash)
      (mount-stories!)
      (mount-app!))))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)
  ;; rf2-4ip3r — wire the schema-derived `:sensitive?` redaction
  ;; index. Called after `init!` so the registered app-schemas
  ;; (`[:customer]`) are visible to the walker. Without this call
  ;; the path-D redaction interceptor silently soft-passes and
  ;; the PII variant's wire trace would NOT show `:rf/redacted`.
  (rf/populate-sensitive-from-schemas!)
  (story/install-canonical-vocabulary!)
  (.addEventListener js/window "hashchange" on-hash-change!)
  (on-hash-change!))
