(ns day8.re-frame2-causa.spine-focus-cljs-test
  "CLJS coverage for the focus-set wiring on the spine (rf2-a1z3b).

  The pure helpers (`fh/infer-dimension`, `fh/build-focus-predicate`,
  `fh/step-in-focus-id`) are covered by the JVM `focus_helpers_test.cljc`
  suite; this file exercises the spine-side reducers + the sub
  registration so the wired surface is asserted end-to-end:

  1. `set-focus-reducer` — slot write + toggle-off contract.
  2. `clear-focus-reducer` — drops slot.
  3. `focus-step-reducer` — skips out-of-focus cascades when a
     focus-set is active.
  4. The registered sub `:rf.causa/focus-set` returns the slot.

  Same fixture discipline as `spine_cljs_test.cljs` so any future
  spine-side wiring follows a single test pattern."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.spine :as spine]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (preload/reset-for-test!)
  (registry/reset-for-test!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- cascade fixture ----------------------------------------------------

(defn- cascade
  [{:keys [id event-id machine-id]
    :or   {id 1}}]
  (cond-> {:dispatch-id id
           :frame       :rf/default
           :event       (when event-id [event-id {}])
           :handler     nil :fx nil :effects [] :subs [] :renders []
           :other       []}
    machine-id (update :other conj {:tags {:machine-id machine-id}
                                    :operation :rf.machine/transition})))

(def ^:private cs
  [(cascade {:id :c1 :event-id :foo})
   (cascade {:id :c2 :event-id :bar})
   (cascade {:id :c3 :event-id :foo})
   (cascade {:id :c4 :event-id :bar})
   (cascade {:id :c5 :event-id :foo})])

;; ---- (1) set-focus-reducer ----------------------------------------------

(deftest set-focus-writes-slot
  (let [db' (spine/set-focus-reducer {} :event-id :foo :c1)]
    (is (= {:dimension :event-id :value :foo :pivot-id :c1}
           (:focus-set db')))))

(deftest set-focus-rebuilds-around-new-pivot
  (let [db  {:focus-set {:dimension :event-id :value :foo :pivot-id :c1}}
        db' (spine/set-focus-reducer db :event-id :foo :c3)]
    (is (= {:dimension :event-id :value :foo :pivot-id :c3}
           (:focus-set db'))
        "same value, different pivot → re-pivot, NOT toggle off")))

(deftest set-focus-on-same-pivot-toggles-off
  (let [db  {:focus-set {:dimension :event-id :value :foo :pivot-id :c1}}
        db' (spine/set-focus-reducer db :event-id :foo :c1)]
    (is (nil? (:focus-set db'))
        "identical descriptor → clear the slot (toggle-off contract)")))

(deftest set-focus-changing-dimension-replaces
  (let [db  {:focus-set {:dimension :event-id :value :foo :pivot-id :c1}}
        db' (spine/set-focus-reducer db :machine-id :checkout :c1)]
    (is (= {:dimension :machine-id :value :checkout :pivot-id :c1}
           (:focus-set db'))
        "different dimension → replace, even if pivot id is identical")))

;; ---- (2) clear-focus-reducer --------------------------------------------

(deftest clear-focus-drops-slot
  (let [db  {:focus-set {:dimension :event-id :value :foo :pivot-id :c1}}]
    (is (nil? (:focus-set (spine/clear-focus-reducer db)))))
  (testing "no-op when slot is already absent"
    (is (nil? (:focus-set (spine/clear-focus-reducer {}))))))

;; ---- (3) focus-step-reducer with focus-set ------------------------------

(deftest step-skips-out-of-focus-rows
  (let [db {:focus     {:dispatch-id :c1 :mode :retro}
            :focus-set {:dimension :event-id :value :foo :pivot-id :c1}}
        db' (spine/focus-step-reducer db cs [] +1)]
    (is (= :c3 (get-in db' [:focus :dispatch-id]))
        ":c1 +1 (focus-set on :foo) → skip :c2 (bar), land on :c3")))

(deftest step-prev-skips-out-of-focus-rows
  (let [db {:focus     {:dispatch-id :c5 :mode :retro}
            :focus-set {:dimension :event-id :value :foo :pivot-id :c1}}
        db' (spine/focus-step-reducer db cs [] -1)]
    (is (= :c3 (get-in db' [:focus :dispatch-id]))
        ":c5 -1 (focus-set on :foo) → skip :c4 (bar), land on :c3")))

(deftest step-boundary-no-op-with-focus-set
  (let [db {:focus     {:dispatch-id :c5 :mode :retro}
            :focus-set {:dimension :event-id :value :foo :pivot-id :c5}}
        db' (spine/focus-step-reducer db cs [] +1)]
    (is (= db db')
        "boundary: stepping past last in-focus is a no-op (db unchanged)")))

(deftest step-without-focus-set-walks-every-cascade
  (let [db {:focus {:dispatch-id :c1 :mode :retro}}
        db' (spine/focus-step-reducer db cs [] +1)]
    (is (= :c2 (get-in db' [:focus :dispatch-id]))
        "no focus-set → standard step-dispatch-id walks every cascade incl :c2")))

(deftest step-with-machine-dimension
  (let [cs-m [(cascade {:id :c1 :event-id :foo :machine-id :checkout})
              (cascade {:id :c2 :event-id :bar})
              (cascade {:id :c3 :event-id :foo :machine-id :checkout})
              (cascade {:id :c4 :event-id :bar})
              (cascade {:id :c5 :event-id :foo :machine-id :checkout})]
        db   {:focus     {:dispatch-id :c1 :mode :retro}
              :focus-set {:dimension :machine-id :value :checkout :pivot-id :c1}}
        db'  (spine/focus-step-reducer db cs-m [] +1)]
    (is (= :c3 (get-in db' [:focus :dispatch-id]))
        ":machine-id focus skips non-machine cascades")))

;; ---- (4) registered sub -------------------------------------------------

(deftest focus-set-sub-returns-slot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (testing "Initially nil"
      (is (nil? @(rf/subscribe [:rf.causa/focus-set]))))
    (testing "After :rf.causa/set-focus dispatch"
      (rf/dispatch-sync [:rf.causa/set-focus :event-id :foo :c1])
      (is (= {:dimension :event-id :value :foo :pivot-id :c1}
             @(rf/subscribe [:rf.causa/focus-set]))))
    (testing "After :rf.causa/clear-focus dispatch"
      (rf/dispatch-sync [:rf.causa/clear-focus])
      (is (nil? @(rf/subscribe [:rf.causa/focus-set]))))))
