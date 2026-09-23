(ns re-frame.epoch-sub-return-backfill-test
  "rf2-3x7nj.17.4 — a subscription's schema failure rides the SAME epoch as
  its sibling `:rf.sub/run`, whether the recompute ran inside the cascade or
  after it settled.

  Subscriptions deref lazily at React render time, which Reagent batches
  AFTER the causing cascade settled. Such a post-settle recompute emits two
  traces with identical routing tags (`:frame` present, no
  `:rf.trace/dispatch-id`): its `:rf.sub/run`, which `capture-event!`
  back-fills into the frame's last-settled epoch, and — when the sub's
  `:schema` rejects the value — a `:rf.error/schema-validation-failure`
  `:where :sub-return`, which used to fall through to the orphan-drop arm.
  The record then kept the run and lost the failure: Xray showed the
  replaced `nil` as a clean SUBSCRIPTIONS row with outcome `:ok`.

  The recompute here is REAL (a `:schema`-bearing `reg-sub` derefed on the
  plain-atom substrate after `dispatch-sync` returned), so both traces come
  from the runtime's own emit sites rather than hand-built envelopes. The
  `:sub-override` sibling fires only under a Story render context, so it is
  driven at its capture seam instead, the technique
  `re-frame.epoch-attribution-test` documents."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch]
            [re-frame.epoch.capture :as rf.epoch.capture]
            ;; The schemas facade publishes the registered validator the
            ;; sub-return check runs through; without it no violation fires.
            [re-frame.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

;; ---- helpers ---------------------------------------------------------------

(defn- sub-violation?
  [where sub-id trace-event]
  (and (= :rf.error/schema-validation-failure (:operation trace-event))
       (= where  (get-in trace-event [:tags :where]))
       (= sub-id (get-in trace-event [:tags :rf.sub/id]))))

(defn- sub-run?
  [sub-id trace-event]
  (and (= :rf.sub/run (:operation trace-event))
       (= sub-id (get-in trace-event [:tags :rf.sub/id]))))

(defn- count-in
  [pred record]
  (count (filter pred (:trace-events record))))

(defn- epoch-named
  "Re-read the ring (back-fill rewrites records in place) and return the
  record with `epoch-id`."
  [frame-id epoch-id]
  (some #(when (= epoch-id (:epoch-id %)) %) (rf/epoch-history frame-id)))

(defn- register-cart!
  "A `:schema :int` sub over a value the seed event writes as a STRING, so
  every recompute fails its schema and is replaced with nil."
  []
  (rf/make-frame {:id :test/main})
  (rf/reg-sub :cart/total {:schema :int} (fn [db _] (get-in db [:cart :total])))
  (rf/reg-event :cart/seed  (fn [{:keys [db]} _] {:db (assoc-in db [:cart :total] "12.50")}))
  (rf/reg-event :cart/other (fn [{:keys [db]} _] {:db (assoc db :other true)})))

;; ---- post-settle :sub-return ----------------------------------------------

(deftest post-settle-sub-return-failure-rides-its-sub-run-epoch
  (testing "a recompute after the cascade settled lands its :sub-return
            failure in the same (last-settled) epoch as its :rf.sub/run,
            exactly once, and re-fans the corrected record to listeners"
    (register-cart!)
    (let [raw      (atom [])
          notified (atom [])]
      (rf/register-listener! :trace ::raw (fn [ev] (swap! raw conj ev)))
      (try
        (rf/dispatch-sync [:cart/seed] {:frame :test/main})
        (let [seed-id (:epoch-id (last (rf/epoch-history :test/main)))]
          (is (not (rf.epoch.capture/in-flight-cascade? :test/main))
              "precondition: nothing is in flight, so the deref below is post-settle")
          (rf/register-listener! :epoch ::watch (fn [r] (swap! notified conj r)))
          (reset! raw [])
          (is (nil? @(rf/subscribe [:cart/total] {:frame :test/main}))
              "precondition: the failing value is replaced with nil")

          (let [violations (filter (partial sub-violation? :sub-return :cart/total) @raw)
                runs       (filter (partial sub-run? :cart/total) @raw)]
            (is (= 1 (count violations))
                "precondition: the recompute emitted one :sub-return failure")
            (is (= 1 (count runs))
                "precondition: and one sibling :rf.sub/run")
            (is (= [[:test/main nil] [:test/main nil]]
                   (mapv (juxt #(get-in % [:tags :frame])
                               #(get-in % [:tags :rf.trace/dispatch-id]))
                         (concat violations runs)))
                "precondition: both carry the frame and no dispatch-id — identical routing tags"))

          (let [seed (epoch-named :test/main seed-id)]
            (is (contains? seed :trace-events)
                "precondition: the record retained :trace-events, so a zero below is not elision")
            (is (= 1 (count-in (partial sub-run? :cart/total) seed))
                "control: the sibling :rf.sub/run is back-filled into the last-settled epoch")
            (is (= 1 (count-in (partial sub-violation? :sub-return :cart/total) seed))
                "the :sub-return failure is back-filled into the SAME epoch, once"))

          (is (= [seed-id]
                 (->> @notified
                      (filter #(pos? (count-in (partial sub-violation? :sub-return :cart/total) %)))
                      (map :epoch-id)
                      distinct
                      vec))
              "listeners are re-notified with the record carrying the failure")

          (rf/dispatch-sync [:cart/other] {:frame :test/main})
          (let [other (last (rf/epoch-history :test/main))]
            (is (= :cart/other (:event-id other)))
            (is (zero? (count-in (partial sub-violation? :sub-return :cart/total) other))
                "the failure does not leak into the next cascade")))
        (finally
          (rf/unregister-listener! :trace ::raw)
          (rf/unregister-listener! :epoch ::watch))))))

;; ---- in-flight :sub-return ------------------------------------------------

(deftest in-flight-sub-return-failure-rides-its-own-cascade-once
  (testing "a handler that derefs the failing sub records the failure in ITS
            cascade exactly once — buffered, not back-filled, not doubled"
    (register-cart!)
    (rf/reg-event :cart/read
      (fn [_ _]
        @(rf/subscribe [:cart/total] {:frame :test/main})
        {}))
    (rf/dispatch-sync [:cart/seed] {:frame :test/main})
    (let [seed-id (:epoch-id (last (rf/epoch-history :test/main)))]
      (rf/dispatch-sync [:cart/read] {:frame :test/main})
      (let [read (last (rf/epoch-history :test/main))
            seed (epoch-named :test/main seed-id)]
        (is (= :cart/read (:event-id read)))
        (is (= 1 (count-in (partial sub-violation? :sub-return :cart/total) read))
            "the in-flight failure rides its own cascade, once")
        (is (some? (->> (:trace-events read)
                        (filter (partial sub-violation? :sub-return :cart/total))
                        first :tags :rf.trace/dispatch-id))
            "it carries the cascade's dispatch-id")
        (is (zero? (count-in (partial sub-violation? :sub-return :cart/total) seed))
            "nothing was back-filled into the previously settled epoch")))))

;; ---- post-settle :sub-override --------------------------------------------

(deftest post-settle-sub-override-failure-is-back-filled
  (testing "the render-phase :sub-override failure, emitted with the same
            routing tags as :sub-return, lands in the last-settled epoch"
    (register-cart!)
    (rf/dispatch-sync [:cart/other] {:frame :test/main})
    (let [settled-id (:epoch-id (last (rf/epoch-history :test/main)))]
      (rf.trace/emit-error! :rf.error/schema-validation-failure
                            {:where     :sub-override
                             :rf.sub/id :cart/total
                             :recovery  :replaced-with-default
                             :frame     :test/main})
      (is (= 1 (count-in (partial sub-violation? :sub-override :cart/total)
                         (epoch-named :test/main settled-id)))
          "the :sub-override failure is back-filled into the last-settled epoch"))))
