(ns multi-frame-causa.fixtures
  "Per-frame deterministic event vectors for the multi-frame Causa
  testbed. Each fixture is a sequence of `[event-vec opts-map]` pairs
  where `opts-map` carries the `{:frame ...}` opts that route the
  dispatch to its target frame.

  These are NOT auto-replayed at boot — the testbed app starts in the
  empty state for every frame, so a Causa user steps through clicks
  directly. The fixtures exist for the Playwright smoke + future Story
  variants to seed deterministic shapes."
  (:require [re-frame.core :as rf]))

(def cart-frame     :cart-frame)
(def checkout-frame :checkout-frame)
(def admin-frame    :admin-frame)

(def cart-seed-events
  "Apple ×2 + Bread ×1 in :cart-frame."
  [[[:cart/add-item :apple] {:frame cart-frame}]
   [[:cart/add-item :apple] {:frame cart-frame}]
   [[:cart/add-item :bread] {:frame cart-frame}]])

(def cross-frame-handoff-events
  "Seed the cart, then click Send-to-checkout. After this the
  checkout-frame's :checkout/items carries the snapshot and the
  cart-frame's :cart/items is empty."
  (conj cart-seed-events
        [[:cart/send-to-checkout] {:frame cart-frame}]))

(def admin-traffic-events
  "Two admin events firing on :admin-frame only — used by the
  cascade-isolation spec to prove they don't appear in
  :cart-frame or :checkout-frame's L2 list."
  [[[:admin/audit] {:frame admin-frame}]
   [[:admin/refund 250] {:frame admin-frame}]])

(defn dispatch-sync-events!
  "Replay a fixture vector through `dispatch-sync` so the resulting
  cascades land synchronously (suitable for tests that need a fully-
  resolved app-db before asserting)."
  [events]
  (doseq [[event opts] events]
    (rf/dispatch-sync event opts))
  nil)
