(ns re-frame.flows-direct-clear-settle-trace-incarnation-cljs-test
  "Exact-incarnation fence for the traces the direct-clear settle emits, on
  both hosts.

  The settle runs inside the cold serialized region, which defers listener
  delivery past the release, so its pass carries a continuation predicate
  bound to A's pinned incarnation: a listener that destroys A and publishes a
  same-id B while the dependent's `:rf.flow/computed` fans out stands, and
  every later listener is suppressed. With A live, a later listener still
  receives the event. Listeners fan out in insertion order."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- seed-frame!
  "[:input] --:producer--> [:source] --:dependent--> [:derived] on a fresh
  frame, seeded through one event."
  [frame-id]
  (rf/make-frame {:id frame-id})
  (rf/reg-event ::seed (fn [_ _] {:db {:input 5}}))
  (rf/reg-flow :producer {:frame frame-id :inputs [[:input]] :output-path [:source]} identity)
  (rf/reg-flow :dependent {:frame frame-id :inputs [[:source]] :output-path [:derived]}
    (fn [source] (or source :absent)))
  (rf/dispatch-sync [::seed] {:frame frame-id}))

(defn- dependent-computed? [ev]
  (and (= :rf.flow/computed (:operation ev))
       (= :dependent (get-in ev [:tags :flow-id]))))

(defn- listen! [id f]
  (rf.trace.tooling/register-listener! id (fn [ev] (when (dependent-computed? ev) (f ev)))))

(defn- unlisten! []
  (rf.trace.tooling/unregister-listener! ::first)
  (rf.trace.tooling/unregister-listener! ::observer))

(deftest direct-clear-settle-trace-listener-loss-fences-subsequent-listeners
  (let [frame-id :flow.settle.fence/subject
        hits     (atom 0)
        observed (atom [])]
    (seed-frame! frame-id)
    (listen! ::first (fn [_]
                       (when (= 1 (swap! hits inc))
                         (rf.frame/destroy-frame! frame-id)
                         (rf/make-frame {:id frame-id}))))
    (listen! ::observer #(swap! observed conj %))
    (try
      (is (= :producer (rf/clear :flow :producer {:frame frame-id})))
      (is (= 1 @hits) "the already-entered delivery stands")
      (is (= [] @observed) "no later listener receives A's stale computed event")
      (is (= {} (rf/app-db-value frame-id)) "A's stale settle installed nothing into B")
      (finally
        (unlisten!)))))

(deftest direct-clear-settle-trace-with-live-owner-emits-once
  ;; Over-fence tooth: with A live, the fence changes neither delivery nor
  ;; what the settle installs.
  (let [frame-id :flow.settle.fence/live
        observed (atom [])]
    (seed-frame! frame-id)
    (listen! ::first (fn [_]))
    (listen! ::observer #(swap! observed conj (get-in % [:tags :result])))
    (try
      (rf/clear :flow :producer {:frame frame-id})
      (is (= [:absent] @observed))
      (is (= {:input 5 :derived :absent} (rf/app-db-value frame-id)))
      (finally
        (unlisten!)))))
