(ns day8.re-frame2-xray.views.resizable-table-persistence-cljs-test
  "localStorage round-trip tests for the shared resizable-table
  column-widths persistence layer (rf2-xzg1y).

  Mirrors the shape of `filters/persistence_cljs_test.cljs`:

    1. ->edn / <-edn round-trip preserves the {table-id {col-id px}}
       shape.
    2. <-edn on malformed input falls back to {} (load path never
       throws into init).
    3. save! → load round-trip via localStorage (when available).
    4. Empty / cleared slot loads as {}.
    5. resize-pair-tick event-db writes the slot (no persist);
       resize-pair-commit event-fx writes the persist fx exactly once
       on pointerup (rf2-xm1jy split — one localStorage write per
       drag, not one per pixel).
    6. reset event-fx clears one table's overrides AND fires persist.
    7. hydrate! lifts the persisted slot back into :rf/xray's app-db
       so the for-table sub re-reads the restored value."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the reset (plain-atom +
  ;; `:all` tier) into one owner; `:post-reset` carries the column-widths
  ;; persistence slate.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (rt/clear!)
                   (rt/set-storage-key! nil))}))

(defn- xray-setup!
  "Register Xray handlers + the :rf/xray frame, then re-run the
  column-widths hydration so any localStorage value lifts into the
  slot. Mirrors `filters/persistence_cljs_test`'s `xray-setup!`."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rt/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

;; ---- (1) ->edn / <-edn round-trip ---------------------------------------

(deftest edn-round-trip-preserves-shape
  (let [widths {:rf.xray.epoch/subscriptions       {:sub 220 :inputs 180 :value 240}
                :rf.xray.epoch/subscriptions-disposed {:disposed 200}}]
    (is (= widths (rt/<-edn (rt/->edn widths))))))

(deftest edn-round-trip-handles-empty
  (is (= {} (rt/<-edn (rt/->edn {}))))
  (is (= {} (rt/<-edn (rt/->edn nil)))
      "nil widths round-trips as the empty map"))

(deftest from-edn-malformed-falls-back-to-empty
  (is (= {} (rt/<-edn "this is not edn")))
  (is (= {} (rt/<-edn "[1 2 3]"))
      "non-map parsed value collapses to default")
  (is (= {} (rt/<-edn ""))
      "empty string collapses to default"))

(deftest from-edn-clamps-degenerate-widths
  (testing "rf2-xzg1y — a corrupted entry below the min-col floor
            (24px) is clamped on read so a stale persisted value
            can't sneak past the resolver"
    (let [parsed (rt/<-edn (pr-str {:t1 {:a 5}}))]
      (is (= {:t1 {:a 24}} parsed)
          "5px clamps to the 24px floor"))))

(deftest from-edn-drops-non-numeric-widths
  (testing "rf2-xzg1y — defence-in-depth: a non-number value in the
            stored map drops out rather than poisoning the slot"
    (is (= {:t1 {:a 100}}
           (rt/<-edn (pr-str {:t1 {:a 100 :b "oops"}})))
        ":b dropped, :a preserved")))

;; ---- (2) save! / load round-trip (depends on localStorage) --------------

;; The real-storage rows that lived here — `save-and-load-round-trip`,
;; `custom-storage-key-isolates-per-instance`,
;; `resize-pair-tick-writes-slot-without-persisting`,
;; `resize-pair-commit-persists-current-slot`,
;; `reset-clears-table-and-persists` and `hydrate-lifts-persisted-widths`
;; — moved to
;; `day8.re-frame2-xray.views.resizable-table-persistence-dom-cljs-test`
;; under rf2-r51p. Each was wrapped in `(when (and (exists? js/window)
;; (.-localStorage js/window)) ...)`, which is FALSE under `:node-test`,
;; while `:browser-test`'s `.*-dom-cljs-test$` `:ns-regexp` never loaded
;; this file at all — so they executed in NEITHER lane. Their new home
;; ends `-dom-cljs-test`, which BOTH builds select, so the rows now run
;; for real in the browser and stay inert on node behind `ls/available?`.
;;
;; `resize-pair-tick-clamps-sub-floor-width` stayed BELOW rather than
;; moving, because the choice is per PROPERTY and not per file: it
;; asserts only over app-db and never reads storage, so its guard was
;; incidental. The guard came off instead, which makes it run on node.

(deftest load-when-slot-is-empty-returns-empty-map
  (rt/clear!)
  (is (= {} (rt/load))))

;; ---- (3) Storage-key override (per-instance isolation) ------------------

;; ---- (4) resize-pair tick + commit (rf2-xm1jy split) --------------------

(deftest resize-pair-tick-clamps-sub-floor-width
  ;; rf2-r51p — the `(when (and (exists? js/window) (.-localStorage
  ;; js/window)) ...)` guard that used to wrap this body was INCIDENTAL:
  ;; the assertion reads app-db through the `for-table` sub and never
  ;; touches storage, so the guard bought nothing and cost the row both
  ;; lanes. Unguarded, it runs on node — for the first time.
  (xray-setup!)
  (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                   :rf.xray.epoch/subscriptions
                   :sub 5 :inputs 300])
  (is (= {:sub 24 :inputs 300}
         (frame-sub [:rf.xray.column-widths/for-table
                     :rf.xray.epoch/subscriptions]))
      "sub clamped to the 24px floor; inputs verbatim"))

;; ---- (5) reset clears one table AND persists ----------------------------

;; ---- (6) hydrate! lifts persisted slot into app-db ----------------------

(deftest hydrate-is-no-op-when-storage-empty
  (rt/clear!)
  (xray-setup!)
  ;; The for-table sub returns nil for an unwritten table (the
  ;; default "no override" shape).
  (is (nil? (frame-sub [:rf.xray.column-widths/for-table
                        :rf.xray.epoch/subscriptions]))
      "no localStorage value → no slot in app-db"))

(deftest hydrate-is-no-op-pre-frame-registration
  (testing "rf2-xzg1y — hydrate! short-circuits when :rf/xray is not
            yet registered (the preload-time call from registry's
            install! fan-out lands here and must not throw)"
    (rt/save! {:t1 {:a 100}})
    ;; Don't call xray-setup! → :rf/xray is NOT registered.
    (is (nil? (rt/hydrate!))
        "hydrate! returns nil rather than dispatching")))
