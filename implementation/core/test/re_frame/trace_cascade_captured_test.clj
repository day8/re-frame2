(ns re-frame.trace-cascade-captured-test
  "Per rf2-931pm — pins the three new substrate-level trace ops:

    1. `:rf.sub/skip` emitted by the memo wrappers on a memo-hit.
    2. `:rf.flow/skip` carries `:input-paths-unchanged` (additive tag).
    3. `:rf.cascade/captured` aggregator fires end-of-epoch ONLY when
       the focus predicate matches; bounded at 50 subs / 100 views.

  Pure JVM coverage — the sub memo + flow trace emits + cascade
  aggregation all run identically on JVM and CLJS (the production
  elision gate is shared; bundle-isolation lives in its own gate)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; The cascade-captured aggregator hooks into `re-frame.epoch/
            ;; settle!` via the late-bind seam — without an epoch
            ;; producer on the classpath there is no settle-time emit
            ;; site and `:rf.cascade/captured` never fires (per
            ;; `core_epoch.cljc` the optional artefact's hooks are
            ;; absent-degrading). The require is here for explicitness.
            [re-frame.epoch]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace.cascade :as cascade]
            [re-frame.trace.tooling :as trace-tooling]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (cascade/clear-focus-predicate!)
  (try (test-fn)
       (finally
         (cascade/clear-focus-predicate!)
         (subs-cache/configure! {:grace-period-ms 50}))))

(use-fixtures :each reset-runtime)

(defn- collect-trace
  "Register a listener that captures every trace event into the
  returned atom while `body-fn` runs. Returns the captured vector."
  [body-fn]
  (let [captured (atom [])
        k        ::collect]
    (trace-tooling/register-listener!
      k
      (fn [ev] (swap! captured conj ev)))
    (try (body-fn)
         (finally
           (trace-tooling/unregister-listener! k)))
    @captured))

;; ---- :rf.sub/skip ---------------------------------------------------------

(deftest layer-1-memo-hit-emits-sub-skip
  (testing "a layer-1 sub deref against an unchanged db emits :rf.sub/skip"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed (fn [_ _] {:n 7}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [events (collect-trace
                   (fn []
                     (let [r (rf/subscribe [:n])]
                       ;; Two derefs against the unchanged db. The
                       ;; first call (post-construction) is a memo hit
                       ;; because the reaction's body fires on initial
                       ;; deref but Reagent's reaction may invoke the
                       ;; wrapper additional times on identical input.
                       @r
                       @r)))
          skips  (filter #(= :rf.sub/skip (:operation %)) events)]
      ;; At least one memo-hit emit must fire on the second deref.
      (is (seq skips)
          "expected at least one :rf.sub/skip emit on memo-hit")
      (let [skip (first skips)]
        (is (= :sub/skip (:op-type skip)))
        (is (= :n (get-in skip [:tags :sub-id])))
        (is (= [:n] (get-in skip [:tags :query-v])))
        (is (= :input-value-equal (get-in skip [:tags :reason])))
        (is (= [] (get-in skip [:tags :input-paths-unchanged]))
            "layer-1 has no upstream subs so :input-paths-unchanged is empty")))))

(deftest layer-2-memo-hit-emits-sub-skip-with-upstream
  (testing "layer-2 sub on memo-hit names its upstream input(s) in :input-paths-unchanged"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed (fn [_ _] {:n 3}))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :doubled
      :<- [:n]
      (fn [n _] (* 2 n)))
    (rf/dispatch-sync [:seed])
    (let [events (collect-trace
                   (fn []
                     (let [r (rf/subscribe [:doubled])]
                       @r @r)))
          skips  (filter #(and (= :rf.sub/skip (:operation %))
                               (= :doubled (get-in % [:tags :sub-id])))
                         events)]
      (is (seq skips)
          "expected at least one :rf.sub/skip emit for the layer-2 sub")
      (let [skip (first skips)]
        (is (= [[:n]] (get-in skip [:tags :input-paths-unchanged]))
            "input-paths-unchanged names the upstream sub vector")))))

;; ---- :rf.flow/skip carries :input-paths-unchanged -------------------------

(deftest flow-skip-emits-input-paths-unchanged
  (testing ":rf.flow/skip carries :input-paths-unchanged naming the flow's input paths"
    (rf/reg-event-db :seed   (fn [_ _]      {:x 0 :y 0}))
    (rf/reg-event-db :bump-z (fn [db _]     (assoc db :z (inc (or (:z db) 0)))))
    (rf/reg-flow {:id     :sum
                  :inputs [[:x] [:y]]
                  :output (fn [x y] (+ x y))
                  :path   [:derived :sum]})
    (rf/dispatch-sync [:seed])
    ;; First non-seed dispatch — flow recomputes. Second — inputs stable,
    ;; flow emits :rf.flow/skip.
    (let [events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:bump-z])
                     (rf/dispatch-sync [:bump-z])))
          skips  (filter #(= :rf.flow/skip (:operation %)) events)]
      (is (seq skips) "expected at least one :rf.flow/skip emit")
      (let [skip (first skips)]
        (is (= [[:x] [:y]] (get-in skip [:tags :input-paths-unchanged])))
        (is (= :inputs-value-equal (get-in skip [:tags :reason])))))))

;; ---- :rf.cascade/captured -------------------------------------------------

(deftest cascade-captured-does-not-fire-when-no-focus
  (testing "default focus-predicate returns false → no :rf.cascade/captured emits"
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [events (collect-trace
                   (fn []
                     (let [r (rf/subscribe [:n])]
                       @r
                       (rf/dispatch-sync [:inc])
                       @r)))
          caps   (filter #(= :rf.cascade/captured (:operation %)) events)]
      (is (empty? caps)
          "no :rf.cascade/captured when focus predicate returns false"))))

(deftest cascade-captured-fires-when-focused
  (testing "installed focus-predicate matching the cascade → :rf.cascade/captured emits"
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (cascade/set-focus-predicate!
      (fn [_frame _epoch _event] true))
    (rf/dispatch-sync [:seed])
    (let [events (collect-trace
                   (fn []
                     (let [r (rf/subscribe [:n])]
                       @r
                       (rf/dispatch-sync [:inc])
                       @r)))
          caps   (filter #(= :rf.cascade/captured (:operation %)) events)]
      (is (seq caps) "expected at least one :rf.cascade/captured emit under focus")
      (let [cap (first caps)]
        (is (= :rf.cascade (:op-type cap)))
        (is (contains? (:tags cap) :frame))
        (is (contains? (:tags cap) :epoch-id))
        (is (vector? (get-in cap [:tags :subs-recomputed])))
        (is (vector? (get-in cap [:tags :subs-skipped])))
        (is (vector? (get-in cap [:tags :flows-computed])))
        (is (vector? (get-in cap [:tags :flows-skipped])))
        (is (vector? (get-in cap [:tags :views-rendered])))
        (is (boolean? (get-in cap [:tags :sub-cap-truncated?])))
        (is (boolean? (get-in cap [:tags :view-cap-truncated?])))))))

;; ---- bounds ---------------------------------------------------------------

(deftest aggregate-cascade-honours-bounds
  (testing "aggregate-cascade caps subs at 50 and stamps :sub-cap-truncated?"
    (let [events (for [i (range 60)]
                   {:operation :sub/run
                    :op-type   :sub/run
                    :tags      {:sub-id (keyword (str "s" i))
                                :query-v [(keyword (str "s" i))]}})
          dag    (cascade/aggregate-cascade events)]
      (is (= 50 (count (:subs-recomputed dag))))
      (is (true? (:sub-cap-truncated? dag)))
      (is (false? (:view-cap-truncated? dag)))))

  (testing "aggregate-cascade caps views at 100 and stamps :view-cap-truncated?"
    (let [events (for [i (range 120)]
                   {:operation :view/render
                    :op-type   :view
                    :tags      {:render-key   [:v (str "k" i)]
                                :triggered-by :db-change}})
          dag    (cascade/aggregate-cascade events)]
      (is (= 100 (count (:views-rendered dag))))
      (is (true? (:view-cap-truncated? dag)))
      (is (false? (:sub-cap-truncated? dag))))))

(deftest aggregate-cascade-shape-pin
  (testing "aggregate-cascade splits subs by :sub/run vs :rf.sub/skip"
    (let [events [{:operation :sub/run :tags {:sub-id :a :query-v [:a]}}
                  {:operation :rf.sub/skip
                   :tags {:sub-id :b :query-v [:b]
                          :reason :input-value-equal
                          :input-paths-unchanged [[:a]]}}
                  {:operation :rf.flow/computed
                   :tags {:flow-id :f :path [:p]}}
                  {:operation :rf.flow/skip
                   :tags {:flow-id :g :input-paths-unchanged [[:x]]}}
                  {:operation :view/render
                   :tags {:render-key [:v :k] :triggered-by :db-change}}]
          dag    (cascade/aggregate-cascade events)]
      (is (= [{:sub-id :a :query-v [:a]}] (:subs-recomputed dag)))
      (is (= [{:sub-id :b :query-v [:b]
               :reason :input-value-equal
               :input-paths-unchanged [[:a]]}]
             (:subs-skipped dag)))
      (is (= [{:flow-id :f :path [:p]}] (:flows-computed dag)))
      (is (= [{:flow-id :g :input-paths-unchanged [[:x]]}]
             (:flows-skipped dag)))
      (is (= [{:render-key [:v :k] :triggered-by :db-change}]
             (:views-rendered dag)))
      (is (false? (:sub-cap-truncated? dag)))
      (is (false? (:view-cap-truncated? dag))))))
