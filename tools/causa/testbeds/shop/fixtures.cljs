(ns shop.fixtures
  "Per-frame deterministic event vectors for the shop testbed (rf2-yhxk3).

  Each fixture is a sequence of `[event-vec opts-map]` pairs where
  `opts-map` carries `{:frame ...}` so the dispatch lands on the
  intended frame. These are NOT auto-replayed at boot — the testbed
  app starts in its seeded-empty state for every frame so a Causa
  user steps through clicks directly. The fixtures exist for the
  Playwright smoke (`spec.cjs`) and future Story variants to seed
  deterministic shapes.

  Six scenarios named for the cascade they pin:

    cart-seed-events           — Apple ×2 + Bread ×1 in :cart-frame.
    send-to-checkout-events    — seed + cross-frame snapshot hop.
    happy-path-pay-events      — seed + send + pay → :confirmed.
    http-500-cascade-events    — flip outcome → reload → 500 reply.
    schema-violation-events    — admin 'submit broken item' → rollback.
    refund-throw-events        — toggle throw → refund → handler throw."
  (:require [re-frame.core :as rf]))

(def cart-frame     :cart-frame)
(def checkout-frame :checkout-frame)
(def admin-frame    :admin-frame)

(def cart-seed-events
  "Apple ×2 + Bread ×1 in :cart-frame."
  [[[:cart/add-item :apple] {:frame cart-frame}]
   [[:cart/add-item :apple] {:frame cart-frame}]
   [[:cart/add-item :bread] {:frame cart-frame}]])

(def send-to-checkout-events
  "Seed the cart, then click Send-to-checkout. After this the
  cart-frame's [:checkout :snapshot] carries the snapshot (the
  deliberately-wrong sub locks onto it), the cart-frame's
  [:cart :items] is empty, and the cross-frame :checkout/flow
  machine envelope drives the checkout-frame's machine from
  cart-empty → ready (entry action :seed-from-cart fires; the
  machine's :data slot carries the items)."
  (conj cart-seed-events
        [[:cart/send-to-checkout] {:frame cart-frame}]))

(def happy-path-pay-events
  "Full happy path: seed → send → pay. After replay, the checkout
  machine sits in :confirmed, the snapshot is populated, the cart
  is empty. Causa's Machines lens shows the full transition history."
  (conj send-to-checkout-events
        [[:checkout/pay] {:frame checkout-frame}]))

(def http-500-cascade-events
  "Flip the next-HTTP-outcome to HTTP-500, then reload the catalogue.
  After this the cart-frame's :catalogue-error slot carries the
  failure-map and Causa's Issues ribbon shows the :rf.http/http-5xx
  error trace."
  [[[:cart/set-http-outcome :rf.http/http-5xx] {:frame cart-frame}]
   [[:cart/load-catalogue] {:frame cart-frame}]])

(def schema-violation-events
  "Admin clicks 'Submit broken item' — the bridge fans to :cart-frame,
  the deliberately-broken {:qty -1} write violates the CartItems
  schema, the post-handler validation step rolls back the :db.
  Issues ribbon shows :rf.error/schema-validation-failure."
  [[[:admin/submit-broken-item] {:frame admin-frame}]])

(def refund-throw-events
  "Toggle :refund-throw? on, then issue a refund. The handler throws
  deterministically; the runtime traps the exception and surfaces it
  in the Issues ribbon as :rf.error/handler-exception."
  [[[:admin/toggle-refund-throw] {:frame admin-frame}]
   [[:admin/refund 250]          {:frame admin-frame}]])

(defn dispatch-sync-events!
  "Replay a fixture vector through `dispatch-sync` so the resulting
  cascades land synchronously (suitable for tests that need a fully-
  resolved app-db before asserting)."
  [events]
  (doseq [[event opts] events]
    (rf/dispatch-sync event opts))
  nil)
