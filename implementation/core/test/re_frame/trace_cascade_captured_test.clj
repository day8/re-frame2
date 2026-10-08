(ns re-frame.trace-cascade-captured-test
  "The substrate's reactive trace ops: `:rf.sub/skip` on a memo hit, the
  `:rf.sub/run` value-change / cascade / first-run / cause-event-id attribution
  tags, and the `:rf.cascade/captured` aggregator (fires only under a focus
  predicate; bounded at 50 subs / 100 views per Spec 009).

  Posture split: every trace read sits inside `(when rf.interop/debug-enabled? …)`,
  and each deftest also asserts, always-on, the production fact the trace
  reports (a body-run count, a recomputed value), so the production-gate lane
  runs real assertions. Negatives (an absent tag, an empty capture list) stay
  inside the guard because an elided trace passes them for free."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            ;; Publishes the `:epoch/run-cause` hook and the settle seam that
            ;; calls the cascade aggregator.
            [re-frame.epoch]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.cascade :as rf.trace.cascade]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- run-traced
  "Run `f` with a listener capturing every trace event; return `[(f) events]`."
  [f]
  (let [captured (atom [])]
    (rf.trace.tooling/register-listener! ::collect #(swap! captured conj %))
    (try [(f) @captured]
         (finally (rf.trace.tooling/unregister-listener! ::collect)))))

(defn- op-of
  "The first `operation` event in `events` for sub `sub-id`."
  [events operation sub-id]
  (first (filter #(and (= operation (:operation %))
                       (= sub-id (get-in % [:tags :rf.sub/id])))
                 events)))

(defn- tagged
  "`ev`'s `:tags` read at the keys of `want` (an absent key reads nil)."
  [ev want]
  (zipmap (keys want) (map #(get-in ev [:tags %]) (keys want))))

;; ---- :rf.sub/run on slot creation, :rf.sub/skip on a memo hit -------------

(deftest sub-first-run-then-memo-hit-skip
  ;; Outside any dispatch: the first deref of each sub allocates its cache slot
  ;; (`:rf.sub/run`, first-run), the second is a memo hit (`:rf.sub/skip`).
  (let [body-runs (atom {})
        count!    #(swap! body-runs update % (fnil inc 0))]
    (rf/reg-event :seed (fn [_ _] {:db {:n 3}}))
    (rf/reg-sub :n (fn [db _] (count! :n) (:n db)))
    (rf/reg-sub :doubled {:inputs [[:n]]} (fn [[n] _] (count! :doubled) (* 2 n)))
    (rf/dispatch-sync [:seed])
    (let [[seen events] (run-traced #(let [r-n (rf/subscribe [:n])
                                           r-d (rf/subscribe [:doubled])]
                                       [@r-n @r-n @r-d @r-d]))
          n-run         (op-of events :rf.sub/run :n)]
      ;; ALWAYS-ON: the memo the skip reports — two derefs, one body run each.
      (is (= [[3 3 6 6] {:n 1 :doubled 1}] [seen @body-runs]))
      (when rf.interop/debug-enabled?
        (doseq [[ev want]
                [[n-run {:rf.sub/first-run? true :rf.sub/value-changed? true
                         :rf.sub/prev-value nil}]
                 [(op-of events :rf.sub/run :doubled) {:rf.sub/first-run? true :rf.sub/value 6}]
                 [(op-of events :rf.sub/skip :n) {:rf.sub/query-v [:n]
                                                  :rf.sub/reason :input-value-equal
                                                  :rf.sub/input-paths-unchanged []}]
                 [(op-of events :rf.sub/skip :doubled) {:rf.sub/input-paths-unchanged [[:n]]}]]]
          (is (= want (tagged ev want))))
        (is (= :rf.sub (:op-type (op-of events :rf.sub/skip :n))))
        (is (not (contains? (:tags n-run) :rf.sub/cause-event-id))
            "outside a dispatch the key is absent, not nil")))))

;; ---- :rf.sub/run attribution inside a dispatch ---------------------------

(deftest sub-run-cause-event-id-layer-2-cascade
  ;; One write recomputes a layer-1, a single-input and a multi-input layer-2
  ;; sub inside the drain (an fx derefs them while the run is in flight).
  (rf/reg-event :seed (fn [_ _] {:db {:a 1 :b 10}}))
  (rf/reg-sub :a (fn [db _] (:a db)))
  (rf/reg-sub :b (fn [db _] (:b db)))
  (rf/reg-sub :b2 {:inputs [[:b]]} (fn [[b] _] (* 2 b)))
  (rf/reg-sub :sum {:inputs [[:a] [:b]]} (fn [[a b] _] (+ a b)))
  (rf/dispatch-sync [:seed])
  (let [rs       (mapv #(rf/subscribe [%]) [:a :b2 :sum])
        _        (mapv deref rs)
        in-drain (atom nil)]
    (rf/reg-fx :deref-fx (fn [_ctx _args] (reset! in-drain (mapv deref rs))))
    (rf/reg-event :inc-b
      (fn [{:keys [db]} _] {:db (update db :b inc) :fx [[:deref-fx true]]}))
    (let [[_ events] (run-traced #(rf/dispatch-sync [:inc-b]))
          run-of     #(op-of events :rf.sub/run %)]
      ;; ALWAYS-ON: inside the drain the fx sees :a stable and both layer-2 subs
      ;; following :b.
      (is (= [1 22 12] @in-drain))
      (when rf.interop/debug-enabled?
        (doseq [[ev want]
                [[(run-of :a)   {:rf.sub/value-changed? false :rf.sub/prev-value 1
                                 :rf.sub/value 1}]
                 [(run-of :b)   {:rf.sub/value-changed? true :rf.sub/prev-value 10
                                 :rf.sub/value 11 :rf.sub/cascade? false
                                 :rf.sub/cause-sub nil :rf.sub/first-run? false
                                 :rf.sub/query-v [:b] :frame :rf/default
                                 :rf.sub/cause-event-id :inc-b}]
                 [(run-of :b2)  {:rf.sub/value-changed? true :rf.sub/prev-value 20
                                 :rf.sub/value 22 :rf.sub/cascade? true
                                 :rf.sub/cause-sub [:b] :rf.sub/cause-event-id :inc-b}]
                 ;; Multi-input: names the input that changed, not the stable :a.
                 [(run-of :sum) {:rf.sub/cascade? true :rf.sub/cause-sub [:b]
                                 :rf.sub/cause-event-id :inc-b}]]]
          (is (= want (tagged ev want))))
        (is (= :rf.sub (:op-type (run-of :b))))))))

;; ---- :rf.cascade/captured --------------------------------------------------

(deftest cascade-captured-fires-only-under-focus
  (rf/reg-event :inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
  (let [captures  #(filterv (comp #{:rf.cascade/captured} :operation)
                            (second (run-traced (fn [] (rf/dispatch-sync [:inc])))))
        unfocused (captures)
        focused   (try (rf.trace.cascade/set-focus-predicate! (fn [_ _ _] true))
                       (captures)
                       (finally (rf.trace.cascade/clear-focus-predicate!)))
        cap       (first focused)]
    ;; ALWAYS-ON: both cascades ran; installing a predicate does not perturb them.
    (is (= 2 (:n (rf/app-db-value :rf/default))))
    (when rf.interop/debug-enabled?
      (is (empty? unfocused) "the default focus predicate suppresses the aggregator")
      (is (= :rf.cascade (:op-type cap)))
      (is (contains? (:tags cap) :rf.epoch/id))
      (let [want {:frame :rf/default :subs-recomputed [] :subs-skipped []
                  :flows-computed [] :flows-skipped [] :views-rendered []
                  :sub-cap-truncated? false :view-cap-truncated? false}]
        (is (= want (tagged cap want)))))))

(deftest aggregate-cascade-honours-bounds
  ;; Spec 009 caps a capture at 50 subs and 100 views; only an entry past the
  ;; cap sets the truncation flag. `:subs-recomputed` and `:subs-skipped` are
  ;; each capped at 50 on their own.
  (doseq [[op n k kept sub-truncated? view-truncated?]
          [[:rf.sub/run     50  :subs-recomputed 50  false false]
           [:rf.sub/run     51  :subs-recomputed 50  true  false]
           [:rf.sub/skip    50  :subs-skipped    50  false false]
           [:rf.sub/skip    51  :subs-skipped    50  true  false]
           [:rf.view/render 100 :views-rendered  100 false false]
           [:rf.view/render 101 :views-rendered  100 false true]]]
    (let [dag (rf.trace.cascade/aggregate-cascade (repeat n {:operation op}))]
      (is (= [kept sub-truncated? view-truncated?]
             [(count (get dag k)) (:sub-cap-truncated? dag) (:view-cap-truncated? dag)])
          (str n " x " op))))
  (let [dag (rf.trace.cascade/aggregate-cascade
              (concat (repeat 50 {:operation :rf.sub/run})
                      (repeat 50 {:operation :rf.sub/skip})))]
    (is (= [50 50 false]
           [(count (:subs-recomputed dag)) (count (:subs-skipped dag))
            (:sub-cap-truncated? dag)])
        "50 recomputed + 50 skipped fit: the two vectors do not share the cap")))

(deftest aggregate-cascade-shape-pin
  ;; `:subs-recomputed` records also carry nil-padded attribution slots; only
  ;; the identity keys are pinned so an additive slot is not a break.
  (is (= {:subs-recomputed     [{:sub-id :a :query-v [:a]}]
          :subs-skipped        [{:sub-id :b :query-v [:b]
                                 :reason :input-value-equal
                                 :input-paths-unchanged [[:a]]}]
          :flows-computed      [{:flow-id :f :path [:p]}]
          :flows-skipped       [{:flow-id :g :input-paths-unchanged [[:x]]}]
          :views-rendered      [{:render-key [:v :k] :triggered-by :db-change}]
          :sub-cap-truncated?  false
          :view-cap-truncated? false}
         (-> (rf.trace.cascade/aggregate-cascade
               [{:operation :rf.sub/run :tags {:rf.sub/id :a :rf.sub/query-v [:a]}}
                {:operation :rf.sub/skip
                 :tags {:rf.sub/id :b :rf.sub/query-v [:b]
                        :rf.sub/reason :input-value-equal
                        :rf.sub/input-paths-unchanged [[:a]]}}
                {:operation :rf.flow/computed :tags {:flow-id :f :path [:p]}}
                {:operation :rf.flow/skip :tags {:flow-id :g :input-paths-unchanged [[:x]]}}
                {:operation :rf.view/render
                 :tags {:rf.view/render-key [:v :k] :triggered-by :db-change}}])
             (update :subs-recomputed (partial mapv #(select-keys % [:sub-id :query-v])))))))
