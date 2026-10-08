(ns re-frame.flows-example-cljs-test
  "Integration test: drives the canonical runnable flows example
   (`examples/core/flows/`) through its HOT-RELOAD seam.

   The example's two named flows (`:cart/subtotal`, `:cart/total`) are
   boot-installed: they live inside `flows.core/install-flows!` because
   `reg-flow` needs a live frame, so — unlike every top-level `reg-event` /
   `reg-sub` form in the namespace — a Shadow watch rebuild does NOT
   re-register them by merely reloading the namespace. The example's
   `^:dev/after-load` seam (`flows.core/reload!`) exists to close that gap: it
   re-invokes the CURRENT `install-flows!` against the live `:rf/default`
   frame (same-id `reg-flow` is a surgical replace, docs/core/flows.md /
   Spec 013), then re-renders.

   This suite pins that seam AGAINST THE EXAMPLE'S OWN reload path, not by
   calling `rf/reg-flow` directly: it substitutes a distinguishable updated
   `:cart/total` derive via `with-redefs` on `install-flows!` — standing in
   for the edited-and-rebuilt namespace, exactly what a watch rebuild
   produces — and invokes `reload!`. Non-vacuity: the substitution alone must
   change NOTHING (that is the stale-derive hazard this seam exists for);
   only the seam invocation swaps the derive in. A mount-only after-load
   never calls `install-flows!`, so under it the post-reload assertions
   here would go red.

   The fixture fns live HERE (the adapter test tree), not under
   examples/core/flows/ — the example source stays test-free by policy.
   The ns requires the example's production source
   (`flows.core`) so its events / subs / views register at ns-load, then
   exercises `install-flows!` / `reload!` against the fixture's `:rf/default`
   frame. `mount!` inside `reload!` no-ops under node (no `js/document`),
   which is precisely the browser/DOM split the seam is built around."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]
            ;; the flows artefact (the example requires it too; explicit here
            ;; so the fixture's late-bound flows-registry reset is armed even
            ;; if the example's require chain changes).
            [re-frame.flows]
            ;; the example's production source — registers its cart events,
            ;; subs, and views at ns-load; `install-flows!` / `reload!` are
            ;; the surfaces under test.
            [flows.core :as example])
  (:require-macros [re-frame.core :refer [with-frame]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- cart [] (:cart (rf/app-db-value :rf/default)))

(defn- qty [sku]
  (some #(when (= sku (:sku %)) (:qty %)) (:items (cart))))

(defn- boot-example!
  "The example's boot order from `run`, minus the two pieces the fixture and
   the node environment own (`rf/init!` — the fixture installs the adapter and
   ensures `:rf/default` — and the DOM mount): install the flows FIRST, then
   seed the cart, so the seed dispatch's own `:db` write is the one the flow
   walk sees."
  []
  (example/install-flows!)
  (rf/dispatch-sync [:cart/initialise] {:frame :rf/default}))

;; Seed arithmetic (flows.core/seed-items):
;;   RF2-MUG  1800 × 1 = 1800
;;   RF2-TEE  3200 × 2 = 6400
;;   RF2-STKR  500 × 3 = 1500
;;                       ----
;;   subtotal            9700, total (no discount) 9700.

(deftest boot-installs-flows-before-the-seed
  (testing "examples/core/flows — control: `run`'s boot order installs both
            flows BEFORE :cart/initialise, so the first committed seed already
            carries correct subtotal and total (they materialise in the seed's
            own write, not one event later)"
    (boot-example!)
    (is (= [9700 9700] [(:subtotal (cart)) (:total (cart))])
        ":cart/subtotal materialised in the seed's own committed write, and :cart/total cascaded off it in the same walk")))

(deftest watch-reload-swaps-the-current-derives-into-the-live-frame
  (testing "examples/core/flows — the ^:dev/after-load seam (reload!)
            re-registers the CURRENT flow definitions into the live frame:
            after substituting an updated :cart/total derive and invoking the
            seam, the next ordinary cart event computes the updated result
            while the pre-reload cart items and quantities survive"
    (boot-example!)
    ;; Take the cart away from its seed state so state preservation is
    ;; observable (the reproduction's step 2).
    (rf/dispatch-sync [:cart/inc-qty "RF2-MUG"] {:frame :rf/default})
    (is (= [2 11500 11500] [(qty "RF2-MUG") (:subtotal (cart)) (:total (cart))])
        "the user's pre-reload change is in, and subtotal and total follow it (old build)")
    (let [new-total-runs (atom 0)
          ;; The `install-flows!` a watch rebuild would produce: same two
          ;; flows, same shapes, with a DISTINGUISHABLE edited :cart/total
          ;; derive (+41 marker) that also counts its invocations.
          rebuilt-install-flows!
          (fn []
            (with-frame :rf/default
              (rf/reg-flow :cart/subtotal
                {:inputs [[:cart :items]]
                 :output-path [:cart :subtotal]}
                (fn [items]
                  (reduce + 0 (map (fn [{:keys [price qty]}] (* price qty)) items))))
              (rf/reg-flow :cart/total
                {:inputs [[:cart :subtotal] [:cart :discount-rate]]
                 :output-path [:cart :total]}
                (fn [subtotal discount-rate]
                  (swap! new-total-runs inc)
                  (+ 41 (Math/round (* subtotal (- 1 (or discount-rate 0)))))))))]
      ;; A local do-nothing event, purely to drive one flow walk without
      ;; disturbing the cart. The example itself carries no such event:
      ;; flow-lifecycle effects settle on their own dispatch (Spec 013
      ;; §Sequencing), so an app never needs one. This test does, because
      ;; it is probing the hot-reload seam rather than a lifecycle effect —
      ;; there is no `:rf.fx/reg-flow` here to settle.
      (rf/reg-event ::walk (fn [{:keys [db]} _] {:db db}))
      (with-redefs [example/install-flows! rebuilt-install-flows!]
        ;; NON-VACUITY, half one: the rebuild REPLACING install-flows! is not
        ;; enough — this is the hazard. Nothing has called the new function,
        ;; so a walk still computes with the previous build's derive.
        (rf/dispatch-sync [::walk] {:frame :rf/default})
        (is (= [11500 0] [(:total (cart)) @new-total-runs])
            "before the seam runs, the live frame still computes with the
             PREVIOUS build's derive and the updated one has never run —
             replacing the function registers nothing")
        ;; THE SEAM. This is what Shadow invokes after a successful rebuild.
        ;; Under a mount-only after-load, install-flows! would never be
        ;; called here and every assertion below would go red.
        (example/reload!))
      ;; An ordinary cart event drives the next flow pass (the reproduction's
      ;; step 4) — no manual reg-flow, no refresh.
      (rf/dispatch-sync [:cart/inc-qty "RF2-TEE"] {:frame :rf/default})
      ;; MUG 1800×2 + TEE 3200×3 + STKR 500×3 = 14700; edited total adds 41.
      (is (= [14700 14741 1] [(:subtotal (cart)) (:total (cart)) @new-total-runs])
          "the re-registered :cart/subtotal still computes, and the next ordinary
           cart event ran the UPDATED :cart/total derive exactly once — the
           reload swapped it into the live frame (surgical replace, no duplicate)")
      ;; Controls: reload preserved the live frame and its state.
      (is (= [2 3 3 3] [(qty "RF2-MUG") (qty "RF2-TEE") (qty "RF2-STKR") (count (:items (cart)))])
          "the pre-reload user change SURVIVED the reload (no :cart/initialise
           replay, no frame teardown), the post-reload event landed on the same
           cart, and its three line items are otherwise untouched"))))
