(ns re-frame.http-actor-destroy-cancellation-test
  "Destroying a spawned state-machine actor aborts every in-flight
  `:rf.http/managed` request it issued (Spec 005 §Cancellation cascade, Spec
  014 §Abort on actor destroy): each delivers a `:cancelled` reply with
  `:reason :actor-destroyed` and emits `:rf.http/aborted-on-actor-destroy`.

  The host transport is replaced at `jvm-fetch` with never-completing futures,
  so every request stays in flight until the destroy aborts it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [java.util.concurrent CompletableFuture]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- await-condition! [pred]
  (rf.test-support/poll-until pred {:timeout-ms 5000 :interval-ms 10
                                    :label "http-actor-destroy condition"}))

(defn- reg-recorder! [replies]
  (rf/reg-event :reply/recorder
    (fn [_ [_ payload]] (swap! replies conj payload) {})))

(defn- reg-worker!
  "A machine that, on entering :running, issues one managed request per entry
  of `request-ids` (nil issues an anonymous one)."
  [machine-id request-ids]
  (rf/reg-machine machine-id
    {:initial :idle
     :actions {:fire (fn [_]
                       {:fx (mapv (fn [request-id]
                                    [:rf.http/managed
                                     (cond-> {:request    {:url "http://example.invalid/slow"}
                                              :decode     :json
                                              :on-failure [:reply/recorder]}
                                       request-id (assoc :request-id request-id))])
                                  request-ids)})}
     :states  {:idle    {:on {:start :running}}
               :running {:entry :fire}}}))

(defn- reg-supervisor!
  "A parent that spawns `worker` in :working and destroys it by leaving on :cancel."
  [machine-id worker]
  (rf/reg-machine machine-id
    {:initial :idle
     :states  {:idle    {:on {:start :working}}
               :working {:spawn {:machine-id worker :start [:start]}
                         :on    {:cancel :idle}}}}))

(defn- held-fetch [_] (CompletableFuture.))

(def ^:private cancel-summary
  (juxt :status (comp :kind :error) (comp :reason :error)))

(defn- abort-traces [traces]
  (filter #(= :rf.http/aborted-on-actor-destroy (:operation %)) traces))

(deftest multiple-in-flight-from-one-actor-all-abort
  (testing "destroying an actor aborts every request it has in flight, with one
            trace per request"
    (let [replies (atom [])
          traces  (atom [])]
      (reg-recorder! replies)
      (reg-worker! :worker/multi [:a :b :c])
      (reg-supervisor! :sup/multi :worker/multi)
      (with-redefs [rf.http.transport-jvm/jvm-fetch held-fetch]
        (try
          (rf.trace.tooling/register-listener! ::multi #(swap! traces conj %))
          (rf/dispatch-sync [:sup/multi [:start]])
          (await-condition!
            #(= 3 (count (get (rf.http.managed/actor-in-flight-snapshot) :worker/multi#1))))
          (rf/dispatch-sync [:sup/multi [:cancel]])
          (await-condition! #(= 3 (count @replies)))
          (is (= (repeat 3 [:cancelled :rf.http/aborted :actor-destroyed])
                 (map cancel-summary @replies)))
          (is (= 3 (count (abort-traces @traces))))
          (is (empty? (rf.http.managed/actor-in-flight-snapshot)))
          (finally
            (rf.trace.tooling/unregister-listener! ::multi)))))))

(deftest sibling-actors-not-affected-by-destroy
  (testing "destroying actor A does not abort sibling actor B's request"
    (let [replies (atom [])]
      (reg-recorder! replies)
      (reg-worker! :worker/proc-a [:a])
      (reg-worker! :worker/proc-b [:b])
      (reg-supervisor! :sup/a :worker/proc-a)
      (reg-supervisor! :sup/b :worker/proc-b)
      (with-redefs [rf.http.transport-jvm/jvm-fetch held-fetch]
        (rf/dispatch-sync [:sup/a [:start]])
        (rf/dispatch-sync [:sup/b [:start]])
        (await-condition! #(= 2 (count (rf.http.managed/actor-in-flight-snapshot))))
        (rf/dispatch-sync [:sup/a [:cancel]])
        (await-condition! #(seq @replies))
        (is (= [:actor-destroyed] (map (comp :reason :error) @replies)))
        (is (= 1 (count (rf.http.managed/actor-in-flight-snapshot)))
            "B remains in the in-flight registry")))))

(deftest anonymous-child-request-abort-cleans-actor-index
  (testing "an anonymous request issued from a spawned actor is indexed by the
            actor alone; destroying the actor aborts it and empties the index"
    (let [replies (atom [])]
      (reg-recorder! replies)
      (reg-worker! :worker/anon [nil])
      (reg-supervisor! :sup/anon :worker/anon)
      (with-redefs [rf.http.transport-jvm/jvm-fetch held-fetch]
        (rf/dispatch-sync [:sup/anon [:start]])
        (await-condition! #(seq (rf.http.managed/actor-in-flight-snapshot)))
        (rf/dispatch-sync [:sup/anon [:cancel]])
        (await-condition! #(seq @replies))
        (is (= [[:cancelled :rf.http/aborted :actor-destroyed]] (map cancel-summary @replies)))
        (is (empty? (rf.http.managed/actor-in-flight-snapshot)))))))

(deftest imperatively-spawned-actor-request-aborts-on-imperative-destroy
  (testing "an imperatively spawned actor (no :spawned registry slot) owns its
            requests, and `[:rf.machine/destroy …]` aborts them"
    (let [replies (atom [])
          traces  (atom [])]
      (reg-recorder! replies)
      (reg-worker! :worker/imp [[:worker/imp :slow]])
      (rf/reg-event :imp/spawn
        (fn [_ _]
          {:fx [[:rf.machine/spawn {:machine-id :worker/imp
                                    :id-prefix  :worker/imp
                                    :start      [:start]}]]}))
      (rf/reg-event :imp/destroy
        (fn [_ _] {:fx [[:rf.machine/destroy :worker/imp#1]]}))
      (with-redefs [rf.http.transport-jvm/jvm-fetch held-fetch]
        (try
          (rf.trace.tooling/register-listener! ::imp #(swap! traces conj %))
          (rf/dispatch-sync [:imp/spawn])
          (await-condition! #(seq (rf.http.managed/actor-in-flight-snapshot)))
          (rf/dispatch-sync [:imp/destroy])
          (await-condition! #(seq @replies))
          (is (= [[:cancelled :rf.http/aborted :actor-destroyed]] (map cancel-summary @replies)))
          (is (= {:actor-id :worker/imp#1 :request-id [:worker/imp :slow]}
                 (select-keys (:tags (first (abort-traces @traces))) [:actor-id :request-id])))
          (finally
            (rf.trace.tooling/unregister-listener! ::imp)))))))
