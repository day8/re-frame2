(ns day8.re-frame2-causa.filters.persistence-cljs-test
  "localStorage round-trip tests for the filter persistence layer
  (rf2-ak4ms).

  These tests run in the node-runtime CLJS suite. localStorage exists
  in `npm run test:cljs`'s shadow-cljs node-target via the
  `dom-storage` polyfill the test-support harness installs; absence
  is also exercised via the storage-available? guard."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.filters :as filters]
            [day8.re-frame2-causa.filters.persistence :as persistence]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (persistence/clear!)
  (config/set-filters-storage-key! nil)
  (config/set-filter-seed! nil))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- causa-setup!
  "Register Causa handlers + the :rf/causa frame, then re-run the
  filter hydration so the seed / localStorage value lifts into the
  slot. Production runs hydrate in `mount.cljs/ensure-causa-frame!`
  after the same reg-frame; tests skip mount so we call hydrate
  directly."
  []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (filters/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/causa
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/causa
    (rf/dispatch-sync ev)))

;; -------------------------------------------------------------------------
;; (1) ->edn / <-edn round-trip
;; -------------------------------------------------------------------------

(deftest edn-round-trip-preserves-shape
  (let [filters {:in  [{:pattern :auth/*}]
                 :out [{:pattern :mouse-move}
                       {:pattern "/login"}]}]
    (is (= filters
           (persistence/<-edn (persistence/->edn filters))))))

(deftest edn-round-trip-handles-empty
  (let [filters {:in [] :out []}]
    (is (= filters
           (persistence/<-edn (persistence/->edn filters))))))

(deftest from-edn-malformed-falls-back-to-empty
  (is (= {:in [] :out []}
         (persistence/<-edn "this is not edn")))
  (is (= {:in [] :out []}
         (persistence/<-edn "[1 2 3]"))
      "non-map parsed value collapses to default"))

;; -------------------------------------------------------------------------
;; (2) save! / load round-trip (depends on localStorage being available)
;; -------------------------------------------------------------------------

(deftest save-and-load-round-trip
  (when (and (exists? js/window) (.-localStorage js/window))
    (let [filters {:in  [{:pattern :auth/*}]
                   :out [{:pattern :mouse-move}]}]
      (persistence/save! filters)
      (is (= filters (persistence/load))))))

(deftest load-when-slot-is-empty-returns-defaults
  (persistence/clear!)
  (is (= {:in [] :out []} (persistence/load))))

;; -------------------------------------------------------------------------
;; (3) Storage-key override via config (per-instance isolation)
;; -------------------------------------------------------------------------

(deftest custom-storage-key-isolates-per-instance
  (when (and (exists? js/window) (.-localStorage js/window))
    (testing "story testbeds set distinct keys so two Causa instances
              do not stomp on each other's pill state"
      ;; Instance A
      (config/set-filters-storage-key! "story.testbed.a.filters")
      (persistence/save! {:in [{:pattern :a}] :out []})
      ;; Switch to instance B and confirm an empty load
      (config/set-filters-storage-key! "story.testbed.b.filters")
      (is (= {:in [] :out []} (persistence/load))
          "instance B's slot starts empty even though A wrote")
      (persistence/save! {:in [] :out [{:pattern :b}]})
      ;; Switch back to A and confirm A's value is intact
      (config/set-filters-storage-key! "story.testbed.a.filters")
      (is (= {:in [{:pattern :a}] :out []}
             (persistence/load))
          "instance A's slot survived the B writes")
      ;; Cleanup
      (config/set-filters-storage-key! "story.testbed.a.filters")
      (persistence/clear!)
      (config/set-filters-storage-key! "story.testbed.b.filters")
      (persistence/clear!)
      (config/set-filters-storage-key! nil))))

;; -------------------------------------------------------------------------
;; (4) configure! plumbs :filters and :filters/storage-key
;; -------------------------------------------------------------------------

(deftest configure-bang-passes-filters-through
  (config/configure! {:filters {:in  [{:pattern :auth/*}]
                                :out [{:pattern :mouse-move}]}})
  (is (= {:in  [{:pattern :auth/*}]
          :out [{:pattern :mouse-move}]}
         (config/get-filter-seed))))

(deftest configure-bang-passes-storage-key-through
  (config/configure! {:filters/storage-key "myhost.filters"})
  (is (= "myhost.filters" (config/get-filters-storage-key)))
  (is (= "myhost.filters" (persistence/get-storage-key))))

(deftest configure-bang-without-filters-keys-leaves-them-alone
  (config/set-filter-seed! {:in [{:pattern :seeded}] :out []})
  (config/set-filters-storage-key! "myhost.filters")
  (config/configure! {:editor :cursor})
  (is (= {:in [{:pattern :seeded}] :out []}
         (config/get-filter-seed))
      "absent :filters key leaves seed untouched")
  (is (= "myhost.filters" (config/get-filters-storage-key))
      "absent :filters/storage-key leaves key untouched"))

;; -------------------------------------------------------------------------
;; (5) Hydration on install — localStorage wins, seed fills the gap
;; -------------------------------------------------------------------------

(deftest hydration-prefers-localstorage-over-seed
  (when (and (exists? js/window) (.-localStorage js/window))
    (persistence/save! {:in  [{:pattern :persisted}]
                        :out []})
    (config/set-filter-seed! {:in [{:pattern :seed}] :out []})
    ;; A fresh registry install rehydrates.
    (registry/reset-for-test!)
    (causa-setup!)
    (is (= [{:pattern :persisted}]
           (:in (frame-sub [:rf.causa/active-filters])))
        "localStorage value wins")
    (persistence/clear!)))

(deftest hydration-falls-back-to-seed-when-localstorage-empty
  (persistence/clear!)
  (config/set-filter-seed! {:in  []
                            :out [{:pattern :mouse-move}]})
  (registry/reset-for-test!)
  (causa-setup!)
  (is (= [{:pattern :mouse-move}]
         (:out (frame-sub [:rf.causa/active-filters])))
      "seed fills the empty-slot gap"))

(deftest hydration-falls-back-to-empty-when-no-source
  (persistence/clear!)
  (config/set-filter-seed! nil)
  (registry/reset-for-test!)
  (causa-setup!)
  (is (= {:in [] :out []}
         (frame-sub [:rf.causa/active-filters]))
      "no localStorage + no seed → empty (first-session honesty)"))

;; -------------------------------------------------------------------------
;; (6) add-filter / remove-filter persist (round-trip via the fx)
;; -------------------------------------------------------------------------

(deftest add-filter-persists-to-localstorage
  (when (and (exists? js/window) (.-localStorage js/window))
    (causa-setup!)
    (frame-dispatch [:rf.causa/add-filter :in {:pattern :auth/*}])
    (is (= {:in [{:pattern :auth/*}] :out []}
           (persistence/load))
        "add-filter writes through to localStorage")))

(deftest remove-filter-persists-to-localstorage
  (when (and (exists? js/window) (.-localStorage js/window))
    (causa-setup!)
    (frame-dispatch [:rf.causa/add-filter :out {:pattern :a}])
    (frame-dispatch [:rf.causa/add-filter :out {:pattern :b}])
    (frame-dispatch [:rf.causa/remove-filter :out 0])
    (is (= {:in [] :out [{:pattern :b}]}
           (persistence/load))
        "remove-filter writes through to localStorage")))
