(ns re-frame.trace-cascade-captured-test
  "Per rf2-931pm — pins the three new substrate-level trace ops:

    1. `:rf.sub/skip` emitted by the memo wrappers on a memo-hit.
    2. `:rf.flow/skip` carries `:rf.sub/input-paths-unchanged` (additive tag).
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
        (is (= :rf.sub (:op-type skip)))
        (is (= :n (get-in skip [:tags :rf.sub/id])))
        (is (= [:n] (get-in skip [:tags :rf.sub/query-v])))
        (is (= :input-value-equal (get-in skip [:tags :rf.sub/reason])))
        (is (= [] (get-in skip [:tags :rf.sub/input-paths-unchanged]))
            "layer-1 has no upstream subs so :rf.sub/input-paths-unchanged is empty")))))

(deftest layer-2-memo-hit-emits-sub-skip-with-upstream
  (testing "layer-2 sub on memo-hit names its upstream input(s) in :rf.sub/input-paths-unchanged"
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
                               (= :doubled (get-in % [:tags :rf.sub/id])))
                         events)]
      (is (seq skips)
          "expected at least one :rf.sub/skip emit for the layer-2 sub")
      (let [skip (first skips)]
        (is (= [[:n]] (get-in skip [:tags :rf.sub/input-paths-unchanged]))
            "input-paths-unchanged names the upstream sub vector")))))

;; ---- :rf.flow/skip carries :rf.sub/input-paths-unchanged -------------------------

(deftest flow-skip-emits-input-paths-unchanged
  (testing ":rf.flow/skip carries :rf.sub/input-paths-unchanged naming the flow's input paths"
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
        (is (contains? (:tags cap) :rf.epoch/id))
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
                   {:operation :rf.sub/run
                    :op-type   :rf.sub/run
                    :tags      {:rf.sub/id (keyword (str "s" i))
                                :rf.sub/query-v [(keyword (str "s" i))]}})
          dag    (cascade/aggregate-cascade events)]
      (is (= 50 (count (:subs-recomputed dag))))
      (is (true? (:sub-cap-truncated? dag)))
      (is (false? (:view-cap-truncated? dag)))))

  (testing "aggregate-cascade caps views at 100 and stamps :view-cap-truncated?"
    (let [events (for [i (range 120)]
                   {:operation :rf.view/render
                    :op-type   :rf.view
                    :tags      {:rf.view/render-key   [:v (str "k" i)]
                                :triggered-by :db-change}})
          dag    (cascade/aggregate-cascade events)]
      (is (= 100 (count (:views-rendered dag))))
      (is (true? (:view-cap-truncated? dag)))
      (is (false? (:sub-cap-truncated? dag))))))

;; ---- :rf.sub/run value-change + cascade attribution (rf2-l1jz8) --------------
;;
;; The reactive recompute path (subs.memo/validate-and-trace) enriches the
;; `:rf.sub/run` tag with value-change + cascade attribution so Xray's
;; Reactive panel can populate "SUBS WHOSE VALUE CHANGED" / "SUBS THAT
;; CASCADED". These tests pin the emitted tags directly off the trace
;; stream (the structured projection threading is covered by the epoch +
;; aggregate-cascade pins).

(defn- sub-runs
  "Filter a captured trace stream to `:rf.sub/run` events for `sub-id`."
  [events sub-id]
  (filter #(and (= :rf.sub/run (:operation %))
                (= sub-id (get-in % [:tags :rf.sub/id])))
          events))

(deftest sub-run-value-changed-attribution
  (testing "a layer-1 recompute whose value CHANGED stamps :rf.sub/value-changed? true + :prev/:value, :rf.sub/cascade? false, :rf.sub/cause-sub nil"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed (fn [_ _] {:n 1}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          _      @r ;; force first recompute (value 1)
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc]) ;; n 1 -> 2, layer-1 recompute
                     @r))
          runs   (sub-runs events :n)]
      (is (seq runs) "expected a :rf.sub/run for :n on the value-changing recompute")
      (let [t (:tags (first runs))]
        (is (true? (:rf.sub/value-changed? t)) "value changed 1 -> 2")
        (is (= 1 (:rf.sub/prev-value t)) ":rf.sub/prev-value is the prior computed value")
        (is (= 2 (:rf.sub/value t)) ":value is the freshly computed value")
        (is (false? (:rf.sub/cascade? t)) "layer-1 sub is app-db-driven, not a cascade")
        (is (nil? (:rf.sub/cause-sub t)) "layer-1 has no upstream sub to attribute")))))

(deftest sub-run-value-unchanged-attribution
  (testing "a recompute whose value did NOT change stamps :rf.sub/value-changed? false"
    ;; Prove the false case genuinely exists: a layer-1 sub that projects
    ;; the SAME value out of a CHANGED db. The memo wrapper compares db
    ;; identity (layer-1 reads app-db directly), so a db write to an
    ;; unrelated key forces the body to re-run, but the body returns a
    ;; `=`-equal value for this sub.
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed   (fn [_ _] {:n 5 :other 0}))
    (rf/reg-event-db :bump-other (fn [db _] (update db :other inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          _      @r ;; first recompute, value 5
          events (collect-trace
                   (fn []
                     ;; db changes (whole-map identity changes), :n body
                     ;; re-runs, but :n's value stays 5.
                     (rf/dispatch-sync [:bump-other])
                     @r))
          runs   (sub-runs events :n)]
      (is (seq runs) "expected a :rf.sub/run for :n on the re-run (db identity changed)")
      (let [t (:tags (first runs))]
        (is (false? (:rf.sub/value-changed? t)) ":n re-ran but its value stayed 5")
        (is (= 5 (:rf.sub/prev-value t)))
        (is (= 5 (:rf.sub/value t)))))))

(deftest sub-run-cascade-attribution-layer-2
  (testing "a layer-2 sub recomputed by an upstream sub change stamps :rf.sub/cascade? true + :rf.sub/cause-sub naming the upstream"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed (fn [_ _] {:n 2}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/reg-sub :doubled
      :<- [:n]
      (fn [n _] (* 2 n)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:doubled])
          _      @r ;; first recompute, value 4
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc]) ;; :n 2->3, :doubled cascades 4->6
                     @r))
          runs   (sub-runs events :doubled)]
      (is (seq runs) "expected a :rf.sub/run for :doubled on the cascade")
      (let [t (:tags (first runs))]
        (is (true? (:rf.sub/value-changed? t)) ":doubled changed 4 -> 6")
        (is (= 4 (:rf.sub/prev-value t)))
        (is (= 6 (:rf.sub/value t)))
        (is (true? (:rf.sub/cascade? t)) "layer-2 recompute is a cascade")
        (is (= [:n] (:rf.sub/cause-sub t))
            ":rf.sub/cause-sub names the upstream sub query-vector that changed")))))

(deftest sub-run-cascade-attribution-layer-2-multi-input
  (testing "a multi-input layer-2 sub names the SPECIFIC upstream that changed"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed (fn [_ _] {:a 1 :b 10}))
    (rf/reg-event-db :inc-b (fn [db _] (update db :b inc)))
    (rf/reg-sub :a (fn [db _] (:a db)))
    (rf/reg-sub :b (fn [db _] (:b db)))
    (rf/reg-sub :sum
      :<- [:a]
      :<- [:b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:sum])
          _      @r ;; first recompute, value 11
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc-b]) ;; :b 10->11, :a stable
                     @r))
          runs   (sub-runs events :sum)]
      (is (seq runs))
      (let [t (:tags (first runs))]
        (is (true? (:rf.sub/cascade? t)))
        (is (= [:b] (:rf.sub/cause-sub t))
            ":rf.sub/cause-sub names :b (the changed input), not :a (stable)")))))

(deftest sub-run-layer-1-no-cause-sub
  (testing "a layer-1 sub never carries a :rf.sub/cause-sub (app-db-driven, not a cascade)"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed (fn [_ _] {:n 0}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          _      @r
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc])
                     @r))
          runs   (sub-runs events :n)]
      (is (seq runs))
      (let [t (:tags (first runs))]
        (is (false? (:rf.sub/cascade? t)))
        (is (nil? (:rf.sub/cause-sub t)))))))

(deftest sub-run-base-shape-still-emitted
  (testing "the :rf.sub/run op-type vocabulary is unchanged — the base tags still ride"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :seed (fn [_ _] {:n 1}))
    (rf/reg-event-db :inc  (fn [db _] (update db :n inc)))
    (rf/reg-sub :n (fn [db _] (:n db)))
    (rf/dispatch-sync [:seed])
    (let [r      (rf/subscribe [:n])
          _      @r
          events (collect-trace
                   (fn []
                     (rf/dispatch-sync [:inc])
                     @r))
          runs   (sub-runs events :n)]
      (is (seq runs))
      (let [ev (first runs)]
        (is (= :rf.sub (:op-type ev)))
        (is (= :n (get-in ev [:tags :rf.sub/id])))
        (is (= [:n] (get-in ev [:tags :rf.sub/query-v])))
        (is (contains? (:tags ev) :frame))))))

(deftest aggregate-cascade-shape-pin
  (testing "aggregate-cascade splits subs by :rf.sub/run vs :rf.sub/skip"
    (let [events [{:operation :rf.sub/run :tags {:rf.sub/id :a :rf.sub/query-v [:a]}}
                  {:operation :rf.sub/skip
                   :tags {:rf.sub/id :b :rf.sub/query-v [:b]
                          :rf.sub/reason :input-value-equal
                          :rf.sub/input-paths-unchanged [[:a]]}}
                  {:operation :rf.flow/computed
                   :tags {:flow-id :f :path [:p]}}
                  {:operation :rf.flow/skip
                   :tags {:flow-id :g :input-paths-unchanged [[:x]]}}
                  {:operation :rf.view/render
                   :tags {:rf.view/render-key [:v :k] :triggered-by :db-change}}]
          dag    (cascade/aggregate-cascade events)]
      ;; Per rf2-l1jz8 the `:subs-recomputed` projection threads value-
      ;; change + cascade attribution; this fixture event carries no
      ;; attribution tags so the slots are nil. The projection RECORD keys
      ;; stay bare (nested record-map carve-out — Spec 009 §`:tags`).
      (is (= [{:sub-id :a :query-v [:a]
               :value-changed? nil :prev-value nil :value nil
               :cascade? nil :cause-sub nil}]
             (:subs-recomputed dag)))
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
