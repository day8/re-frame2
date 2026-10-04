(ns day8.re-frame2-xray.views.resizable-table-persistence-cljs-test
  "localStorage round-trip tests for the shared resizable-table
  column-widths persistence layer. The properties, split between this
  namespace and `resizable-table-persistence-dom-cljs-test` (the rows
  that need a real localStorage):

    1. ->edn / <-edn round-trip preserves the {table-id {col-id px}}
       shape.
    2. <-edn on malformed input falls back to {} (load path never
       throws into init).
    3. save! → load round-trip via localStorage (when available).
    4. Empty / cleared slot loads as {}.
    5. resize-pair-tick event-db writes the slot (no persist);
       resize-pair-commit event-fx writes the persist fx exactly once
       on pointerup (one localStorage write per drag, not one per
       pixel).
    6. reset event-fx clears one table's overrides AND fires persist.
    7. hydrate! lifts the persisted slot back into :rf/xray's app-db
       so the for-table sub re-reads the restored value."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; `make-xray-runtime-fixture` folds the reset (plain-atom +
  ;; `:all` tier) into one owner; `:post-reset` carries the column-widths
  ;; persistence slate.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (rt/clear!)
                   (rt/set-storage-key! nil))}))

(defn- xray-setup!
  "Register Xray handlers + the :rf/xray frame, then re-run the
  column-widths hydration so any localStorage value lifts into the
  slot."
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

(deftest edn-codec-round-trips-and-sanitises
  (let [widths {:rf.xray.epoch/subscriptions          {:sub 220 :inputs 180 :value 240}
                :rf.xray.epoch/subscriptions-disposed {:disposed 200}}]
    (doseq [[label expected encoded]
            [["round-trip preserves the {table-id {col-id px}} shape"
              widths (rt/->edn widths)]
             ["nil widths round-trip as the empty map"
              {} (rt/->edn nil)]
             ["a non-map parsed value collapses to the default"
              {} "[1 2 3]"]
             [(str "a corrupted entry below the min-col floor (24px) is clamped "
                   "on read, so a stale persisted value can't sneak past the "
                   "resolver")
              {:t1 {:a 24}} (pr-str {:t1 {:a 5}})]
             [(str "defence-in-depth: a non-number width drops out rather than "
                   "poisoning the slot")
              {:t1 {:a 100}} (pr-str {:t1 {:a 100 :b "oops"}})]]]
      (is (= expected (rt/<-edn encoded)) label))))

;; ---- (2) save! / load round-trip (depends on localStorage) --------------

;; The real-storage rows — `custom-storage-key-isolates-per-instance`,
;; `resize-pair-commit-persists-current-slot`,
;; `reset-clears-table-and-persists` and `hydrate-lifts-persisted-widths`
;; — live in
;; `day8.re-frame2-xray.views.resizable-table-persistence-dom-cljs-test`.
;; Here, wrapped in `(when (and (exists? js/window)
;; (.-localStorage js/window)) ...)`, which is FALSE under `:node-test`,
;; while `:browser-test`'s `.*-dom-cljs-test$` `:ns-regexp` never loads
;; this file at all, they would execute in NEITHER lane. That namespace
;; ends `-dom-cljs-test`, which BOTH builds select, so the rows run
;; for real in the browser and stay inert on node behind `ls/available?`.
;;
;; `resize-pair-tick-clamps-sub-floor-width` sits BELOW rather than
;; there, because the choice is per PROPERTY and not per file: it
;; asserts only over app-db and never reads storage, so it needs no
;; storage guard, and unguarded it runs on node.

;; ---- (3) Storage-key override (per-instance isolation) ------------------

;; ---- (4) resize-pair tick + commit --------------------------------------

(deftest resize-pair-tick-clamps-sub-floor-width
  ;; No `(when (and (exists? js/window) (.-localStorage js/window)) ...)`
  ;; guard: the assertion reads app-db through the `for-table` sub and
  ;; never touches storage, so a guard would buy nothing and cost the
  ;; row both lanes. Unguarded, it runs on node.
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

(deftest hydrate-is-no-op-pre-frame-registration
  (testing "hydrate! short-circuits when :rf/xray is not
            yet registered (the preload-time call from registry's
            install! fan-out lands here): it dispatches nothing, so no
            error is emitted"
    ;; Node has no localStorage, so `load` answers {} and hydrate! would
    ;; stop at its empty-map check before the frame check was ever
    ;; asked. A stored map puts the frame check in charge. Without it,
    ;; the dispatch into the unregistered frame does not throw — it is
    ;; refused with `:rf.error/frame-destroyed` and dropped — so the
    ;; always-on error channel is what this row reads.
    (let [errors (atom [])]
      (rf.error-emit/register-error-listener!
        ::pre-registration-observer #(swap! errors conj %))
      (try
        (with-redefs [rt/load (constantly {:t1 {:a 100}})]
          ;; Don't call xray-setup! → :rf/xray is NOT registered.
          (is (nil? (rt/hydrate!))
              "hydrate! returns nil"))
        (is (empty? @errors)
            "and emits no error — nothing was dispatched into the
             unregistered frame")
        (finally
          (rf.error-emit/unregister-error-listener! ::pre-registration-observer))))))
