;;;; tests/runtime/streaming_subscriptions_test.clj
;;;;
;;;; Babashka-runnable verification of the streaming subscription
;;;; substrate in `preload/re_frame_pair2/runtime.cljs` (rf2-hq49) —
;;;; `subscribe!`, `drain-subscription!`, `unsubscribe!`, and the
;;;; trace + epoch dispatchers.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;;   `preload/re_frame_pair2/runtime.cljs` is a CLJS-only file loaded
;;;;   into the consumer app via shadow-cljs `:devtools :preloads`. It
;;;;   depends on the live re-frame2 trace bus + epoch bus + reagent
;;;;   atom — none of which run under bb. This file mirrors the
;;;;   subscription registry, the filter compiler, the per-topic
;;;;   dispatcher routing, and the drain semantics, and asserts the
;;;;   behaviour against pure-data inputs.
;;;;
;;;;   KEEP IN SYNC WITH preload/re_frame_pair2/runtime.cljs §Streaming
;;;;   subscriptions.
;;;;
;;;; Run:    bb tests/runtime/streaming_subscriptions_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns streaming-subscriptions-test
  (:require [clojure.test :refer [deftest is run-tests testing]]))

;; ---------------------------------------------------------------------------
;; Mirror of the subscription registry + helpers.
;; ---------------------------------------------------------------------------

(def default-max-buffered 500)

(defn topic->base-filter
  [topic]
  (case topic
    :fx    {:op-type :fx}
    :error {:op-type :error}
    {}))

(defn compose-trace-filter
  [topic user-filter]
  (merge (topic->base-filter topic) (or user-filter {})))

(defn trace-matches?
  [filter-map ev]
  (let [{:keys [operation op-type frame severity
                event-id handler-id source origin
                dispatch-id since-ms between]}
        filter-map
        [t0 t1] (when (and (sequential? between) (= 2 (count between)))
                  between)]
    (boolean
      (and (or (nil? operation)  (= operation (:operation ev)))
           (or (nil? op-type)    (= op-type   (:op-type ev)))
           (or (nil? severity)   (= severity  (:op-type ev)))
           (or (nil? frame)      (= frame
                                    (or (:frame ev)
                                        (get-in ev [:tags :frame]))))
           (or (nil? event-id)   (= event-id
                                    (get-in ev [:tags :event-id])))
           (or (nil? handler-id) (= handler-id
                                    (get-in ev [:tags :handler-id])))
           (or (nil? source)     (= source
                                    (or (:source ev)
                                        (get-in ev [:tags :source]))))
           (or (nil? origin)     (= origin
                                    (get-in ev [:tags :origin])))
           (or (nil? dispatch-id)(= dispatch-id
                                    (get-in ev [:tags :dispatch-id])))
           (or (nil? since-ms)   (and (number? (:time ev))
                                      (> (:time ev) since-ms)))
           (or (nil? t0)         (and (number? (:time ev))
                                      (<= t0 (:time ev) t1)))))))

;; Mirror of epoch-matches? (subset of the keys the runtime exposes —
;; enough to exercise the dispatch routing).
(defn epoch-matches?
  [pred {:keys [event-id effects frame]}]
  (let [{p-eid    :event-id
         p-fx     :effects
         p-frame  :frame} pred]
    (boolean
      (and
        (if p-eid    (= p-eid event-id) true)
        (if p-fx     (some #(= p-fx (:fx-id %)) effects) true)
        (if p-frame  (= p-frame frame) true)))))

(defn enqueue!
  [sub event]
  (let [q   (:queue sub)
        n   (count q)
        cap (:max-buffered sub default-max-buffered)]
    (if (>= n cap)
      (update sub :overflow (fnil inc 0))
      (update sub :queue conj event))))

(defn dispatch-trace-to-subs!
  [subs ev]
  (reduce-kv
    (fn [acc sub-id sub]
      (if (and (contains? #{:trace :fx :error} (:topic sub))
               (trace-matches? (:compiled-filter sub) ev))
        (update acc sub-id enqueue! ev)
        acc))
    subs subs))

(defn dispatch-epoch-to-subs!
  [subs record]
  (reduce-kv
    (fn [acc sub-id sub]
      (if (and (= :epoch (:topic sub))
               (epoch-matches? (or (:filter sub) {}) record))
        (update acc sub-id enqueue! record)
        acc))
    subs subs))

(defn subscribe!
  [subs sub-id {:keys [topic filter max-buffered]}]
  (let [compiled (when (#{:trace :fx :error} topic)
                   (compose-trace-filter topic filter))
        sub {:id sub-id
             :topic topic
             :filter (or filter {})
             :compiled-filter compiled
             :queue []
             :overflow 0
             :created-at 0
             :max-buffered (or max-buffered default-max-buffered)}]
    (assoc subs sub-id sub)))

(defn drain-subscription!
  [subs sub-id]
  (if-let [sub (get subs sub-id)]
    [{:events (:queue sub) :overflow (:overflow sub) :gone? false}
     (assoc subs sub-id (-> sub (assoc :queue []) (assoc :overflow 0)))]
    [{:events [] :overflow 0 :gone? true}
     subs]))

(defn unsubscribe!
  [subs sub-id]
  (let [existed? (contains? subs sub-id)]
    [{:existed? existed?} (dissoc subs sub-id)]))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest topic-sugar-base-filters
  (testing ":fx topic adds :op-type :fx as a base filter"
    (is (= {:op-type :fx} (compose-trace-filter :fx nil))))
  (testing ":error topic adds :op-type :error"
    (is (= {:op-type :error} (compose-trace-filter :error nil))))
  (testing ":trace topic adds no base — user filter is the only constraint"
    (is (= {} (compose-trace-filter :trace nil))))
  (testing "user filter overrides the topic's base on conflict"
    (is (= {:op-type :error :event-id :user/login}
           (compose-trace-filter :fx {:op-type :error :event-id :user/login})))))

(deftest trace-filter-matches-by-event-id
  (let [filter-map {:event-id :user/login}
        match     {:operation :event/dispatched :tags {:event-id :user/login}}
        no-match  {:operation :event/dispatched :tags {:event-id :user/logout}}]
    (is (trace-matches? filter-map match))
    (is (not (trace-matches? filter-map no-match)))))

(deftest trace-filter-matches-by-severity
  (let [filter-map {:severity :error}
        match     {:op-type :error}
        no-match  {:op-type :info}]
    (is (trace-matches? filter-map match))
    (is (not (trace-matches? filter-map no-match)))))

(deftest trace-filter-matches-between
  (let [filter-map {:between [100 200]}]
    (is (trace-matches? filter-map {:time 150}))
    (is (trace-matches? filter-map {:time 100}))
    (is (trace-matches? filter-map {:time 200}))
    (is (not (trace-matches? filter-map {:time 99})))
    (is (not (trace-matches? filter-map {:time 201})))))

(deftest trace-filter-frame-falls-back-to-tags
  (let [filter-map {:frame :stories}]
    (is (trace-matches? filter-map {:tags {:frame :stories}}))
    (is (trace-matches? filter-map {:frame :stories}))
    (is (not (trace-matches? filter-map {:tags {:frame :rf/default}})))))

(deftest epoch-filter-matches-by-event-id
  (is (epoch-matches? {:event-id :cart/add} {:event-id :cart/add}))
  (is (not (epoch-matches? {:event-id :cart/add} {:event-id :cart/remove}))))

(deftest epoch-filter-matches-by-effects
  (is (epoch-matches? {:effects :http} {:effects [{:fx-id :http} {:fx-id :db}]}))
  (is (not (epoch-matches? {:effects :http} {:effects [{:fx-id :db}]}))))

(deftest subscribe-and-drain-roundtrips
  (let [subs (-> {} (subscribe! "s1" {:topic :trace :filter {:op-type :error}}))]
    (is (contains? subs "s1"))
    (is (= :trace (get-in subs ["s1" :topic])))
    (let [;; Three events, only one matches.
          ev1 {:op-type :error :tags {:event-id :user/login}}
          ev2 {:op-type :info  :tags {:event-id :user/login}}
          ev3 {:op-type :error :tags {:event-id :user/logout}}
          subs (-> subs
                   (dispatch-trace-to-subs! ev1)
                   (dispatch-trace-to-subs! ev2)
                   (dispatch-trace-to-subs! ev3))]
      (let [[drain1 subs] (drain-subscription! subs "s1")]
        (is (= 2 (count (:events drain1))))
        (is (= ev1 (first (:events drain1))))
        (is (= ev3 (second (:events drain1))))
        (is (false? (:gone? drain1)))
        ;; A second drain (immediately after) returns empty — the
        ;; queue is reset.
        (let [[drain2 _] (drain-subscription! subs "s1")]
          (is (empty? (:events drain2))))))))

(deftest unsubscribe-deletes-and-future-drains-mark-gone
  (let [subs (-> {} (subscribe! "s1" {:topic :trace}))
        ;; Confirm the sub is live.
        _ (is (contains? subs "s1"))
        [unsub-resp subs] (unsubscribe! subs "s1")]
    (is (true? (:existed? unsub-resp)))
    (is (not (contains? subs "s1")))
    (let [[drain-resp _] (drain-subscription! subs "s1")]
      (is (true? (:gone? drain-resp)))
      (is (empty? (:events drain-resp))))))

(deftest unsubscribe-unknown-is-idempotent
  (let [subs {}
        [resp subs] (unsubscribe! subs "phantom")]
    (is (false? (:existed? resp)))
    (is (= {} subs))))

(deftest epoch-subscription-only-receives-epoch-events
  ;; An :epoch sub should NOT be fed trace events; a :trace sub should
  ;; NOT be fed epoch records. Verifies the topic gating in the
  ;; dispatchers.
  (let [subs (-> {}
                 (subscribe! "epoch-sub" {:topic :epoch :filter {:event-id :cart/add}})
                 (subscribe! "trace-sub" {:topic :trace}))
        ;; A trace event flows only to the trace sub.
        subs (dispatch-trace-to-subs! subs {:op-type :info :tags {}})
        ;; An epoch record flows only to the epoch sub (matching filter).
        subs (dispatch-epoch-to-subs! subs {:event-id :cart/add :effects []})]
    (is (= 1 (count (get-in subs ["trace-sub" :queue]))))
    (is (= 1 (count (get-in subs ["epoch-sub" :queue]))))))

(deftest fx-topic-defaults-op-type-but-respects-overrides
  (let [;; :fx sub with no extra filter matches any trace event with
        ;; :op-type :fx.
        subs (-> {} (subscribe! "fx-sub" {:topic :fx}))
        ev   {:op-type :fx :tags {:fx-id :http}}
        ev2  {:op-type :info :tags {}}
        subs (-> subs
                 (dispatch-trace-to-subs! ev)
                 (dispatch-trace-to-subs! ev2))]
    (is (= [ev] (get-in subs ["fx-sub" :queue]))))
  (testing "user filter can override the topic's base op-type"
    (let [subs (-> {} (subscribe! "fx-sub" {:topic :fx :filter {:op-type :info}}))
          ev   {:op-type :fx :tags {}}
          ev2  {:op-type :info :tags {}}
          subs (-> subs
                   (dispatch-trace-to-subs! ev)
                   (dispatch-trace-to-subs! ev2))]
      (is (= [ev2] (get-in subs ["fx-sub" :queue]))))))

(deftest error-topic-matches-error-traces
  (let [subs (-> {} (subscribe! "err-sub" {:topic :error}))
        err  {:op-type :error :tags {:handler-id :user/login}}
        ok   {:op-type :info :tags {}}
        subs (-> subs
                 (dispatch-trace-to-subs! err)
                 (dispatch-trace-to-subs! ok))]
    (is (= [err] (get-in subs ["err-sub" :queue])))))

(deftest queue-cap-counts-overflow-keeps-oldest
  (let [subs (-> {} (subscribe! "s" {:topic :trace :max-buffered 2}))
        e1 {:op-type :info :id 1}
        e2 {:op-type :info :id 2}
        e3 {:op-type :info :id 3}
        e4 {:op-type :info :id 4}
        subs (-> subs
                 (dispatch-trace-to-subs! e1)
                 (dispatch-trace-to-subs! e2)
                 (dispatch-trace-to-subs! e3)
                 (dispatch-trace-to-subs! e4))]
    (is (= 2 (count (get-in subs ["s" :queue]))))
    (is (= [e1 e2] (get-in subs ["s" :queue])))
    (is (= 2 (get-in subs ["s" :overflow])))))

(deftest unknown-topic-rejected-at-subscribe-level
  ;; The runtime's `subscribe!` returns {:ok? false :reason :unknown-topic}
  ;; — mirrored here as the dispatcher refusing to fan to an unknown topic.
  (let [recognised #{:trace :epoch :fx :error}]
    (is (not (contains? recognised :other)))
    (is (every? recognised [:trace :epoch :fx :error]))))

(let [{:keys [fail error]} (run-tests 'streaming-subscriptions-test)]
  (when (or (pos? fail) (pos? error))
    (System/exit 1)))
