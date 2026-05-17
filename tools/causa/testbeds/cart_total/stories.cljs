(ns cart-total.stories
  "Story coverage for the Causa cart-total hero testbed.

  This is intentionally based on the existing cart-total app. It gives
  Story a realistic Causa substrate: a visible wrong total, source coord,
  app-db drift between :cart/items and :checkout/items, and a derived
  subscription graph with the wrong edge."
  (:require [re-frame.story :as story]
            [cart-total.fixtures :as fixtures]))

(defn register-all!
  "Register the cart-total Story surface. Idempotent enough for tests:
  callers can clear Story's registrar and replay this function."
  []
  (story/install-canonical-vocabulary!)

  (story/reg-tag :feature/causa-cart-total
    {:axis :feature
     :doc  "Causa cart-total tutorial app: wrong-slot subscription bug."})

  (story/reg-story :story.causa.cart-total
    {:doc        "Existing Causa cart-total testbed rendered through Story.
                 The variants pin the states a developer should inspect in
                 Causa: seeded symptom, checkout snapshot, post-checkout
                 drift, and discount-through-wrong-snapshot."
     :component  :cart-total.core/cart-story-app
     :tags       #{:dev :test :feature/causa-cart-total}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/empty
    {:doc        "Fresh empty cart. Baseline for the Causa panel gallery."
     :events     fixtures/initialise-events
     :play       [[:rf.assert/path-equals [:cart/items] []]
                  [:rf.assert/path-equals [:checkout/items] []]
                  [:rf.assert/sub-equals [:cart/total] 0]]
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/seeded-wrong-total
    {:doc        "The tutorial's visible symptom: Apple x2 and Bread x1
                 are in the cart, but the total is $0.00 because the
                 display sub reads the empty checkout snapshot."
     :events     fixtures/tutorial-seed-events
     :play       [[:rf.assert/path-equals [:cart/items 0 :qty] 2]
                  [:rf.assert/path-equals [:cart/items 1 :qty] 1]
                  [:rf.assert/path-equals [:checkout/items] []]
                  [:rf.assert/sub-equals [:cart/total] 0]]
     :tags       #{:dev :test :docs}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/checkout-snapshot
    {:doc        "Checkout has copied the cart into :checkout/items and
                 cleared the live basket. The wrong total now matches
                 the stale checkout snapshot."
     :events     fixtures/checkout-snapshot-events
     :play       [[:rf.assert/path-equals [:cart/items] []]
                  [:rf.assert/path-equals [:checkout/items 0 :qty] 2]
                  [:rf.assert/path-equals [:checkout/items 1 :qty] 1]
                  [:rf.assert/sub-equals [:cart/checkout-active?] true]
                  [:rf.assert/sub-equals [:cart/total] 650]]
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/live-basket-drift
    {:doc        "The core bug state after checkout: the live basket has
                 Apple x1, but the displayed total is still the $6.50
                 checkout snapshot."
     :events     fixtures/live-basket-drift-events
     :play       [[:rf.assert/path-equals [:cart/items 0 :id] :apple]
                  [:rf.assert/path-equals [:cart/items 0 :qty] 1]
                  [:rf.assert/path-equals [:checkout/items 0 :qty] 2]
                  [:rf.assert/path-equals [:checkout/items 1 :qty] 1]
                  [:rf.assert/sub-equals [:cart/total] 650]]
     :tags       #{:dev :test :docs}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/discounted-snapshot
    {:doc        "Discount path through the wrong snapshot: STUDENT
                 discount is applied to the stale checkout total."
     :events     fixtures/discounted-snapshot-events
     :play       [[:rf.assert/path-equals [:cart/discount :code] "STUDENT"]
                  [:rf.assert/path-equals [:cart/discount :percent] 10]
                  [:rf.assert/sub-equals [:cart/total] 585]]
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Boundary variants (rf2-5kad2) — extra states a developer should be
  ;; able to inspect in Causa beyond the tutorial arc. Each one pins a
  ;; specific edge of the cart slice shape or sub graph.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.causa.cart-total/empty-after-clear
    {:doc        "Empty cart distinct from the initial-empty state:
                 user added the seeded basket then removed every line.
                 The event log carries the add/remove traffic while the
                 slice is empty — useful for inspecting Causa's
                 event-history pane alongside an empty App-DB Diff."
     :events     fixtures/empty-after-clear-events
     :play       [[:rf.assert/path-equals [:cart/items] []]
                  [:rf.assert/path-equals [:checkout/items] []]
                  [:rf.assert/sub-equals [:cart/total] 0]]
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/stacked-same-item
    {:doc        "Five Apples stacked into a single line item.
                 Boundary for the qty-stacking branch of
                 :cart/add-item — Causa's diff should show qty
                 incrementing on one line, not five line additions."
     :events     fixtures/stacked-same-item-events
     :play       [[:rf.assert/path-equals [:cart/items 0 :id] :apple]
                  [:rf.assert/path-equals [:cart/items 0 :qty] 5]
                  [:rf.assert/sub-equals [:cart/total] 0]]
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/all-three-items
    {:doc        "All three catalogue items present, one each.
                 Exercises the three-line render path and the
                 catalogue/cart symmetry — useful for inspecting the
                 sub graph with a fully-populated :cart/line-totals."
     :events     fixtures/all-three-items-events
     :play       [[:rf.assert/path-equals [:cart/items 0 :id] :apple]
                  [:rf.assert/path-equals [:cart/items 1 :id] :bread]
                  [:rf.assert/path-equals [:cart/items 2 :id] :coffee]
                  [:rf.assert/sub-equals [:cart/total] 0]]
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/friend-discount-applied
    {:doc        "FRIEND (20%) discount applied to the seeded basket.
                 Sister to :discounted-snapshot which used STUDENT (10%)
                 — pins the second discount-table branch and the
                 deeper discount-percent arithmetic."
     :events     fixtures/friend-discount-events
     :play       [[:rf.assert/path-equals [:cart/discount :code] "FRIEND"]
                  [:rf.assert/path-equals [:cart/discount :percent] 20]
                  [:rf.assert/path-equals [:cart/items 0 :qty] 2]
                  [:rf.assert/sub-equals [:cart/total] 0]]
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/discount-then-cleared
    {:doc        "Discount applied to the seeded basket, then cleared.
                 The cart still carries lines but :cart/discount is
                 back to nil — exercises the discount-row 'cleared'
                 branch and the cleared-discount sub recompute."
     :events     fixtures/discount-then-cleared-events
     :play       [[:rf.assert/path-equals [:cart/discount] nil]
                  [:rf.assert/path-equals [:cart/items 0 :qty] 2]
                  [:rf.assert/sub-equals [:cart/total] 0]]
     :tags       #{:dev :test}
     :substrates #{:reagent}})

  ;; -------------------------------------------------------------------------
  ;; Boundary variants (rf2-4ip3r) — two cascades the pure-data variants
  ;; couldn't express: mid-flight HTTP 5xx during finalize-checkout, and
  ;; populated PII slots the trace / MCP wire redact.
  ;; -------------------------------------------------------------------------

  (story/reg-variant :story.causa.cart-total/checkout-http-error
    {:doc        "Mid-flight HTTP 5xx during finalize-checkout. After
                 the seed + :checkout/start the fixture dispatches
                 :checkout/finalize, which issues
                 :rf.http/managed-canned-failure with kind
                 :rf.http/http-5xx. Default reply-addressing routes
                 the failure-map back to :checkout/finalize, which
                 lands it at :checkout/error and flips
                 :checkout/status to :error. Open Causa's Effects /
                 Trace / Issues panels — the same canonical 5xx
                 cascade a live failure would produce."
     :events     fixtures/checkout-http-error-events
     :play       [[:rf.assert/path-equals [:checkout/status] :error]
                  [:rf.assert/path-equals [:checkout/error :status] 503]
                  [:rf.assert/path-equals [:checkout/error :kind] :rf.http/http-5xx]
                  ;; The wrong-slot bug is still live — the snapshot
                  ;; survives even though the finalize failed.
                  [:rf.assert/sub-equals [:cart/total] 650]]
     :tags       #{:dev :test :docs}
     :substrates #{:reagent}})

  (story/reg-variant :story.causa.cart-total/customer-pii-set
    {:doc        "Schema-:sensitive? PII populated. The seeded basket
                 plus :customer/email and :payment/token landed in the
                 :customer slice. The handler :cart/set-customer-pii
                 carries :sensitive? true registration meta + a
                 (rf/path :customer) interceptor, so trace / MCP
                 wire surfaces see :rf/redacted in place of the
                 payload (the App-DB Diff renders the redaction
                 marker; the sensitive-trace count badge advances).
                 The handler body still receives the raw values, so
                 the subs and the in-page DOM mirror read them back."
     :events     fixtures/customer-pii-events
     :play       [[:rf.assert/path-equals [:customer :customer/email] "ada@example.com"]
                  [:rf.assert/path-equals [:customer :payment/token]  "tok_pii_visa_4242"]
                  [:rf.assert/sub-equals  [:customer/pii-set?]        true]]
     :tags       #{:dev :test :docs}
     :substrates #{:reagent}})

  (story/reg-workspace :Workspace.causa.cart-total/debugging-states
    {:doc      "All cart-total Causa debugging states side-by-side."
     :layout   :grid
     :variants [:story.causa.cart-total/empty
                :story.causa.cart-total/seeded-wrong-total
                :story.causa.cart-total/checkout-snapshot
                :story.causa.cart-total/live-basket-drift
                :story.causa.cart-total/discounted-snapshot
                :story.causa.cart-total/empty-after-clear
                :story.causa.cart-total/stacked-same-item
                :story.causa.cart-total/all-three-items
                :story.causa.cart-total/friend-discount-applied
                :story.causa.cart-total/discount-then-cleared
                ;; rf2-4ip3r boundary variants
                :story.causa.cart-total/checkout-http-error
                :story.causa.cart-total/customer-pii-set]
     :columns  2
     :tags     #{:docs}}))

(register-all!)
