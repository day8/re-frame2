(ns re-frame.flows-user-fx-lifecycle-settle-cljs-test
  "Spec 013 §Sequencing — flow lifecycle calls and frame-state writes made
  from a USER-registered fx settle before the dispatch returns.

  The `:fx` walk runs AFTER the event's flow pass. Two things can happen
  during it that the pass never saw:

    1. rf2-3x7nj.18.3 — a user fx calls `(rf/clear :flow id)`, or a
       `reg-flow` that moves an existing flow's `:output-path`. Both run
       in-drain, so both QUEUE their output-path vacation for a pending flow
       pass — but the pass has already run. Only the reserved
       `:rf.fx/reg-flow` / `:rf.fx/clear-flow` bodies used to request a settle,
       so the cleared flow's row was gone while its own leaf, and every
       dependent derived from it, stayed in app-db until some unrelated drain.
       The lifecycle op now requests the settle itself; outside a walk the
       request is a no-op, so a clear from a handler body still settles via
       that event's own pending pass.

    2. rf2-3x7nj.9.7 — a user fx writes frame state directly. The walk now
       requests a settle whenever it leaves the frame's state container
       changed and the frame holds a flow. (The machine lifecycle writers are
       pinned in core, whose test classpath carries machines.)

  The two triggers are distinct and neither covers the other: a user-fx clear
  mutates only side tables (the registry row and the vacation queue), so on a
  frame with no CLASSIFIED flow it leaves the container `identical?` and the
  write trigger never fires. Every flow below is unclassified for that reason.

  Named `*_cljs_test.cljc` so both the JVM runner and the shadow-cljs
  `:node-test` build discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.flows :as rf.flows]
   [re-frame.frame :as rf.frame]
   [re-frame.test-support :as rf.test-support]
   #?(:clj  [re-frame.substrate.plain-atom :as substrate]
      :cljs [re-frame.adapter.reagent :as substrate])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter substrate/adapter}))

(defn- db [] (rf/app-db-value :rf/default))

(defn- reg-chain-and-seed!
  "`:p3/a` [:x] -> [:a], `:p3/b` [:a] -> [:b], seeded to {:x 2 :a 2 :b 2}."
  []
  (rf/reg-flow :p3/a {:inputs [[:x]] :output-path [:a]} identity)
  (rf/reg-flow :p3/b {:inputs [[:a]] :output-path [:b]} identity)
  (rf/reg-event :seed (fn [_ _] {:db {:x 2}}))
  (rf/reg-event :noop (fn [_ _] {}))
  (rf/dispatch-sync [:seed]))

;; ---------------------------------------------------------------------------
;; rf2-3x7nj.18.3 — lifecycle calls from a user fx
;; ---------------------------------------------------------------------------

(deftest a-clear-from-a-user-fx-settles-in-one-dispatch
  (testing "`(rf/clear :flow id)` from a user fx vacates the cleared flow's leaf
            and recomputes its dependents before the dispatch returns"
    (reg-chain-and-seed!)
    (is (= {:x 2 :a 2 :b 2} (db)) "precondition")
    (rf/reg-fx :p3/clear-a (fn [_ _] (rf/clear :flow :p3/a)))
    (rf/reg-event :go (fn [_ _] {:fx [[:p3/clear-a nil]]}))

    (rf/dispatch-sync [:go])
    (is (not (contains? (get (rf.flows/flows-snapshot) :rf/default) :p3/a))
        "the registry row is gone")
    ;; Red before the fix: {:x 2 :a 2 :b 2} — the row gone, its value and
    ;; the value derived from it still present.
    (is (= {:x 2 :b nil} (db))
        "the leaf is vacated and the dependent derived from its absence")
    (let [settled (db)]
      (rf/dispatch-sync [:noop])
      (is (= settled (db)) "an unrelated drain finds nothing left to repair"))))

(deftest a-path-moving-reg-flow-from-a-user-fx-settles-in-one-dispatch
  (testing "a `reg-flow` from a user fx that MOVES an existing flow's
            :output-path vacates the old leaf before the dispatch returns"
    (reg-chain-and-seed!)
    (rf/reg-fx :p3/move-a
      (fn [{:keys [frame]} _]
        (rf/reg-flow :p3/a {:frame frame :inputs [[:x]] :output-path [:a2]} identity)))
    (rf/reg-event :go (fn [_ _] {:fx [[:p3/move-a nil]]}))

    (rf/dispatch-sync [:go])
    ;; Red before the fix: the old [:a] leaf, and :b derived from it, linger.
    (is (= {:x 2 :a2 2 :b nil} (db))
        "the old leaf is vacated, the moved flow materialised, the dependent settled")))

(deftest a-clear-from-a-handler-body-is-unchanged
  (testing "CONTROL — a clear from the handler BODY still settles through that
            event's own pending flow pass; the request is a no-op there"
    (reg-chain-and-seed!)
    (rf/reg-event :go (fn [{:keys [db]} _]
                        (rf/clear :flow :p3/a)
                        {:db db}))
    (rf/dispatch-sync [:go])
    (is (= {:x 2 :b nil} (db)))))

;; ---------------------------------------------------------------------------
;; rf2-3x7nj.9.7 — the generic write trigger, independent of machines
;; ---------------------------------------------------------------------------

(deftest a-user-fx-writing-frame-state-settles-in-one-dispatch
  (testing "a user fx that writes runtime-db directly refreshes a flow reading it"
    (rf/reg-flow :p/rt {:inputs [[:rf.db/runtime :probe/n]] :output-path [:rt-n]} identity)
    (rf/reg-fx :p/bump-runtime
      (fn [{:keys [frame]} _] (rf.frame/swap-runtime-db! frame update :probe/n (fnil inc 0))))
    (rf/reg-event :go (fn [_ _] {:fx [[:p/bump-runtime nil]]}))
    (rf/dispatch-sync [:go])
    ;; Red before the fix: nil — the pass ran before the write.
    (is (= 1 (:rt-n (db))) "fresh after the ONE dispatch"))

  (testing "a user fx that writes app-db directly refreshes a flow reading it"
    (rf/reg-flow :p/app {:inputs [[:y]] :output-path [:y2]} (fn [y] (some-> y (* 2))))
    (rf/reg-fx :p/set-y
      (fn [{:keys [frame]} v] (rf.frame/swap-frame-db! frame assoc :y v)))
    (rf/reg-event :set-y (fn [_ [_ v]] {:fx [[:p/set-y v]]}))
    (rf/dispatch-sync [:set-y 21])
    (is (= 42 (:y2 (db))) "fresh after the ONE dispatch")))
