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
    5. resize-pair event-fx writes the slot AND fires the persist fx
       (post-dispatch, the localStorage slot reflects the new value).
    6. reset event-fx clears one table's overrides AND fires persist.
    7. hydrate! lifts the persisted slot back into :rf/xray's app-db
       so the for-table sub re-reads the restored value."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

;; ---- fixture ------------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (rt/clear!)
  (rt/set-storage-key! nil))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup!
  "Register Xray handlers + the :rf/xray frame, then re-run the
  column-widths hydration so any localStorage value lifts into the
  slot. Mirrors `filters/persistence_cljs_test`'s `xray-setup!`."
  []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
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

(deftest save-and-load-round-trip
  (when (and (exists? js/window) (.-localStorage js/window))
    (let [widths {:rf.xray.epoch/subscriptions {:sub 300 :inputs 150 :value 200}}]
      (rt/save! widths)
      (is (= widths (rt/load))))))

(deftest load-when-slot-is-empty-returns-empty-map
  (rt/clear!)
  (is (= {} (rt/load))))

;; ---- (3) Storage-key override (per-instance isolation) ------------------

(deftest custom-storage-key-isolates-per-instance
  (when (and (exists? js/window) (.-localStorage js/window))
    (testing "story testbeds set distinct keys so two Xray instances
              do not stomp on each other's column-widths state"
      (rt/set-storage-key! "story.testbed.a.column-widths")
      (rt/save! {:t1 {:a 100}})
      (rt/set-storage-key! "story.testbed.b.column-widths")
      (is (= {} (rt/load))
          "instance B's slot is independent of instance A")
      (rt/save! {:t2 {:b 200}})
      (rt/set-storage-key! "story.testbed.a.column-widths")
      (is (= {:t1 {:a 100}} (rt/load))
          "instance A's slot survived instance B's write"))))

;; ---- (4) resize-pair persists via fx ------------------------------------

(deftest resize-pair-writes-slot-and-persists
  (when (and (exists? js/window) (.-localStorage js/window))
    (xray-setup!)
    (frame-dispatch [:rf.xray.column-widths/resize-pair
                     :rf.xray.epoch/subscriptions
                     :sub 250 :inputs 170])
    (testing "app-db slot reflects the resize"
      (is (= {:sub 250 :inputs 170}
             (frame-sub [:rf.xray.column-widths/for-table
                         :rf.xray.epoch/subscriptions]))))
    (testing "localStorage carries the same payload"
      (is (= {:rf.xray.epoch/subscriptions {:sub 250 :inputs 170}}
             (rt/load))))))

(deftest resize-pair-clamps-sub-floor-width
  (when (and (exists? js/window) (.-localStorage js/window))
    (xray-setup!)
    (frame-dispatch [:rf.xray.column-widths/resize-pair
                     :rf.xray.epoch/subscriptions
                     :sub 5 :inputs 300])
    (is (= {:sub 24 :inputs 300}
           (frame-sub [:rf.xray.column-widths/for-table
                       :rf.xray.epoch/subscriptions]))
        "sub clamped to the 24px floor; inputs verbatim")))

;; ---- (5) reset clears one table AND persists ----------------------------

(deftest reset-clears-table-and-persists
  (when (and (exists? js/window) (.-localStorage js/window))
    (xray-setup!)
    (frame-dispatch [:rf.xray.column-widths/resize-pair
                     :t1 :a 100 :b 200])
    (frame-dispatch [:rf.xray.column-widths/resize-pair
                     :t2 :a 50 :b 70])
    (frame-dispatch [:rf.xray.column-widths/reset :t1])
    (is (nil? (frame-sub [:rf.xray.column-widths/for-table :t1]))
        "t1's overrides cleared")
    (is (= {:a 50 :b 70}
           (frame-sub [:rf.xray.column-widths/for-table :t2]))
        "t2's overrides untouched")
    (is (= {:t2 {:a 50 :b 70}} (rt/load))
        "localStorage reflects the reset")))

;; ---- (6) hydrate! lifts persisted slot into app-db ----------------------

(deftest hydrate-lifts-persisted-widths
  (when (and (exists? js/window) (.-localStorage js/window))
    ;; Seed localStorage BEFORE setup so hydrate sees it.
    (rt/save! {:rf.xray.epoch/views {:view 180 :subs 220}})
    (xray-setup!)
    (is (= {:view 180 :subs 220}
           (frame-sub [:rf.xray.column-widths/for-table
                       :rf.xray.epoch/views]))
        "hydrate! ran in xray-setup! and lifted the slot into app-db")))

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
