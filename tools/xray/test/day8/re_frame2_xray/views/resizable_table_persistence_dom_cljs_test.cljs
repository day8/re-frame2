(ns day8.re-frame2-xray.views.resizable-table-persistence-dom-cljs-test
  "The column-widths persistence rows that need a real
  `window.localStorage`: per-instance storage-key isolation, the
  tick-does-not-persist / commit-does-persist split, reset's persist, and
  the hydrate lift. The storage-free rows live in
  `resizable-table-persistence-cljs-test`, among them
  `resize-pair-tick-clamps-sub-floor-width`.

  Only a namespace ending `-dom-cljs-test` is loaded by `:browser-test`,
  which is why these rows live here. `:node-test`'s `cljs-test$` regexp
  loads it too, so each row is guarded by `ls/available?` and asserts its
  skip rather than holding zero assertions."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

(use-fixtures :each
  ;; The browser lane runs every namespace on one page, so each row starts
  ;; from an empty default-key slot.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (rt/clear!)
                   (rt/set-storage-key! nil))}))

(defn- xray-setup!
  "Register Xray handlers + the :rf/xray frame, then hydrate so any stored
  widths lift into the slot."
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

(deftest custom-storage-key-isolates-per-instance
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing "story testbeds set distinct keys so two Xray instances
              do not stomp on each other's column-widths state"
      ;; `rt/clear!` reaches only the current key, so the fixture cannot
      ;; clear B; this row clears both keys itself, before and after.
      (let [key-a "story.testbed.a.column-widths"
            key-b "story.testbed.b.column-widths"
            clear-both! (fn []
                          (rt/set-storage-key! key-a)
                          (rt/clear!)
                          (rt/set-storage-key! key-b)
                          (rt/clear!)
                          (rt/set-storage-key! nil))]
        (try
          (clear-both!)
          (rt/set-storage-key! key-a)
          (rt/save! {:t1 {:a 100}})
          (rt/set-storage-key! key-b)
          (is (= {} (rt/load))
              "instance B's slot is independent of instance A")
          (rt/save! {:t2 {:b 200}})
          (rt/set-storage-key! key-a)
          (is (= {:t1 {:a 100}} (rt/load))
              "instance A's write persisted and survived instance B's write")
          (finally
            (clear-both!)))))))

(deftest resize-pair-commit-persists-current-slot
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing "a pointermove tick writes app-db only; the pointerup commit
              writes the settled widths to localStorage"
      (xray-setup!)
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :rf.xray.epoch/subscriptions
                       :sub 250 :inputs 170])
      (is (= {} (rt/load))
          "the tick leaves localStorage untouched")
      (frame-dispatch [:rf.xray.column-widths/resize-pair-commit])
      (is (= {:rf.xray.epoch/subscriptions {:sub 250 :inputs 170}}
             (rt/load))
          "the commit writes the settled widths"))))

(deftest reset-clears-table-and-persists
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (xray-setup!)
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :t1 :a 100 :b 200])
      (frame-dispatch [:rf.xray.column-widths/resize-pair-tick
                       :t2 :a 50 :b 70])
      (frame-dispatch [:rf.xray.column-widths/reset :t1])
      (is (nil? (frame-sub [:rf.xray.column-widths/for-table :t1]))
          "t1's overrides cleared")
      (is (= {:a 50 :b 70}
             (frame-sub [:rf.xray.column-widths/for-table :t2]))
          "t2's overrides untouched")
      (is (= {:t2 {:a 50 :b 70}} (rt/load))
          "localStorage reflects the reset"))))

(deftest hydrate-lifts-persisted-widths
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      ;; Seed localStorage BEFORE setup so hydrate sees it.
      (rt/save! {:rf.xray.epoch/views {:view 180 :subs 220}})
      (xray-setup!)
      (is (= {:view 180 :subs 220}
             (frame-sub [:rf.xray.column-widths/for-table
                         :rf.xray.epoch/views]))
          "hydrate! ran in xray-setup! and lifted the slot into app-db"))))
