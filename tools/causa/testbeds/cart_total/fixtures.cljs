(ns cart-total.fixtures
  "Deterministic cart-total state builders shared by the live testbed,
  Story variants, and browser tests.

  Keep these as plain event vectors where possible. Story can replay
  them directly into a variant frame, while the live app can dispatch the
  same sequence into :rf/default."
  (:require [re-frame.core :as rf]))

(def initialise-events
  [[:cart/initialise]])

(def tutorial-seed-events
  "Apple x2 + Bread x1. This is the tutorial's obvious-$6.50 basket.
  Because :cart/total deliberately reads :checkout/items, the visible
  total remains $0.00 before checkout starts."
  (into initialise-events
        [[:cart/add-item :apple]
         [:cart/add-item :apple]
         [:cart/add-item :bread]]))

(def checkout-snapshot-events
  "The seeded basket has been snapshotted into :checkout/items and the
  live basket has been cleared."
  (conj tutorial-seed-events [:checkout/start]))

(def live-basket-drift-events
  "The user starts checkout, then adds a new Apple to the live basket.
  The cart visibly contains Apple x1, but :cart/total still reads the
  $6.50 checkout snapshot."
  (conj checkout-snapshot-events [:cart/add-item :apple]))

(def discounted-snapshot-events
  "A discount applied to the wrong checkout snapshot. This pins the
  projection bug through both subscription and discount paths."
  (conj checkout-snapshot-events [:cart/apply-discount "STUDENT"]))

;; ============================================================================
;; Boundary fixtures (rf2-5kad2) — additional Story variants pinning
;; states a developer should be able to inspect in Causa beyond the five
;; tutorial-arc states above. Pure-data extensions; no new handlers.
;; ============================================================================

(def empty-after-clear-events
  "User added a basket, then removed every line. Distinct from the
  initial empty state because the event-log carries the add/remove
  traffic — Causa should show that history while the slice is empty."
  (into tutorial-seed-events
        [[:cart/remove-item :apple]
         [:cart/remove-item :bread]]))

(def stacked-same-item-events
  "Five Apples stacked into a single line item. Boundary for the
  qty-stacking branch of :cart/add-item — should land at qty 5 on a
  single line, not five separate lines."
  (into initialise-events
        (repeat 5 [:cart/add-item :apple])))

(def all-three-items-events
  "All three catalogue items present, one each. Exercises the
  three-line render path and the catalogue/cart symmetry."
  (into initialise-events
        [[:cart/add-item :apple]
         [:cart/add-item :bread]
         [:cart/add-item :coffee]]))

(def friend-discount-events
  "FRIEND (20%) discount applied to the seeded basket. Sister to the
  STUDENT (10%) discount fixture above; pins the second discount-table
  branch."
  (into tutorial-seed-events
        [[:cart/apply-discount "FRIEND"]]))

(def discount-then-cleared-events
  "Discount applied to the seeded basket, then cleared. The cart still
  carries lines but :cart/discount is back to nil — exercises the
  discount-row 'cleared' branch."
  (into friend-discount-events
        [[:cart/clear-discount]]))

;; ============================================================================
;; Boundary fixtures (rf2-4ip3r) — additional Story variants pinning
;; the two app-shape cascades a developer should be able to inspect in
;; Causa beyond the pure-data variants above: HTTP-5xx during finalize,
;; and a populated PII slot the wire surfaces redact.
;; ============================================================================

(def checkout-http-error-events
  "Seed → start checkout → fire `:checkout/finalize` which issues
  `:rf.http/managed-canned-failure` with `:rf.http/http-5xx`. The
  reply lands `:checkout/error` in app-db and flips `:checkout/status`
  to `:error`. Causa's Effects + Trace + Issues panels all see the
  same canonical cascade as a live 5xx (per Spec 014 §Testing).

  The dispatch-sync of `:checkout/finalize` runs the initial branch
  synchronously. The canned-stub's reply path dispatches *back* to
  `:checkout/finalize` (default reply-addressing); under the
  Story replay loop that reply also lands synchronously, so the
  variant's `:ready` snapshot already carries `:checkout/error`."
  (conj checkout-snapshot-events [:checkout/finalize]))

(def customer-pii-events
  "Seed + populate the schema-:sensitive? `:customer/email` and
  `:payment/token` slots. Causa's App-DB Diff displays `:rf/redacted`
  for the payload-bearing event and the sensitive-trace count badge
  updates; the underlying handler still receives the raw values, so
  the live subs (`:customer/email`, `:payment/token`) read them back
  for the in-page DOM mirror."
  (conj tutorial-seed-events
        [:cart/set-customer-pii
         {:email "ada@example.com"
          :token "tok_pii_visa_4242"}]))

(defn dispatch-sync-events!
  "Replay an event fixture into the current frame."
  [events]
  (doseq [event events]
    (rf/dispatch-sync event))
  nil)
