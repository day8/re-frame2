(ns re-frame.flows-example-cljs-test
  "Integration test: drives the canonical runnable flows example
   (`examples/core/flows/`) through its own frame config.

   The example's two flows (`:cart/subtotal`, `:cart/total`) are top-level
   registrations, and its frame runs them because `flows.core/cart-frame`
   names them in `:flows`. So the suite makes the frame from that config, the
   way the example's `frame-root` does on first mount, and checks three
   things: the seed's own write carries both totals, the discount is one
   event, and a re-registered flow — what a watch rebuild's reloaded
   namespace does — reaches the live frame on its next event with the cart
   state intact.

   The fixture fns live HERE (the adapter test tree), not under
   examples/core/flows/ — the example source stays test-free by policy. The
   ns requires the example's production source (`flows.core`) so its flows,
   events, subs and views register at ns-load."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            ;; the flows artefact (the example requires it too; explicit here
            ;; so the fixture's late-bound flows-registry reset is armed even
            ;; if the example's require chain changes).
            [re-frame.flows]
            ;; the example's production source — registers its flows, cart
            ;; events, subs and views at ns-load; `cart-frame` is the frame
            ;; config under test.
            [flows.core :as example]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- cart [] (:cart (rf/app-db-value :rf/default)))

(defn- qty [sku]
  (some #(when (= sku (:sku %)) (:qty %)) (:items (cart))))

(defn- boot-example!
  "Make the example's frame from its own config, as its `frame-root` does on
   first mount. The fixture has already made a bare `:rf/default`, and
   re-making an existing frame does not replay `:initial-events`, so the
   fixture's frame goes first."
  []
  (rf/destroy-frame! :rf/default)
  (rf/make-frame example/cart-frame))

;; Seed arithmetic (flows.core/seed-items):
;;   RF2-MUG  1800 × 1 = 1800
;;   RF2-TEE  3200 × 2 = 6400
;;   RF2-STKR  500 × 3 = 1500
;;                       ----
;;   subtotal            9700, total (no discount) 9700.

(deftest the-seed-write-carries-both-totals
  (testing "examples/core/flows — the frame runs both flows from its first
            event, so the :initial-events seed's own write carries the
            subtotal and the total cascaded off it"
    (boot-example!)
    (is (= [[:cart/subtotal :cart/total] 9700 9700]
           [(:flows (rf/frame-meta :rf/default)) (:subtotal (cart)) (:total (cart))]))))

(deftest the-discount-is-one-event
  (testing "examples/core/flows — applying and removing the discount are
            each one event, and the total that reads the rate lands in that
            event's own write"
    (boot-example!)
    (rf/dispatch-sync [:cart/apply-discount] {:frame :rf/default})
    (is (= [0.1 8730] [(:discount-rate (cart)) (:total (cart))]))
    (rf/dispatch-sync [:cart/remove-discount] {:frame :rf/default})
    (is (= [false 9700] [(contains? (cart) :discount-rate) (:total (cart))]))))

(deftest a-rebuilt-flow-reaches-the-live-frame-on-its-next-event
  (testing "examples/core/flows — re-registering :cart/total, as the
            reloaded namespace of a watch rebuild does, changes nothing until
            the frame's next event, which computes with the edited derive
            while the cart's items and quantities survive"
    (boot-example!)
    (rf/dispatch-sync [:cart/inc-qty "RF2-MUG"] {:frame :rf/default})
    (let [new-total-runs (atom 0)]
      ;; The reloaded namespace's `:cart/total` form, with a distinguishable
      ;; edited derive (+41) that counts its invocations. It carries
      ;; `flows.core`'s provenance because a rebuild reloads that namespace;
      ;; registered as this test namespace it would be a second owner of the
      ;; id, which a frame resolving the live store refuses as ambiguous.
      (rf/reg-flow :cart/total
        {:inputs [[:cart :subtotal] [:cart :discount-rate]]
         :output-path [:cart :total]
         :rf.provenance/ns "flows.core"}
        (fn [subtotal discount-rate]
          (swap! new-total-runs inc)
          (+ 41 (Math/round (* subtotal (- 1 (or discount-rate 0)))))))
      (is (= [11500 0] [(:total (cart)) @new-total-runs])
          "registering writes nothing: the frame still holds the previous derive's output")
      (rf/dispatch-sync [:cart/inc-qty "RF2-TEE"] {:frame :rf/default})
      ;; MUG 1800×2 + TEE 3200×3 + STKR 500×3 = 14700; edited total adds 41.
      (is (= [14700 14741 1] [(:subtotal (cart)) (:total (cart)) @new-total-runs])
          "the next ordinary cart event ran the edited :cart/total derive exactly once")
      (is (= [2 3 3 3] [(qty "RF2-MUG") (qty "RF2-TEE") (qty "RF2-STKR") (count (:items (cart)))])
          "the frame kept its state: no re-seed, and the change before the
           re-registration survived"))))
