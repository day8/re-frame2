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

  (story/reg-workspace :Workspace.causa.cart-total/debugging-states
    {:doc      "All cart-total Causa debugging states side-by-side."
     :layout   :grid
     :variants [:story.causa.cart-total/empty
                :story.causa.cart-total/seeded-wrong-total
                :story.causa.cart-total/checkout-snapshot
                :story.causa.cart-total/live-basket-drift
                :story.causa.cart-total/discounted-snapshot]
     :columns  2
     :tags     #{:docs}}))

(register-all!)
