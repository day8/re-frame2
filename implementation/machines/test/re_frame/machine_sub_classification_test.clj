(ns re-frame.machine-sub-classification-test
  "The framework `[:rf/machine <id>]` sub's value is the actor's snapshot, so the
  machine's `:sensitive` / `:large` declaration redacts it at every egress — the
  `:rf.sub/run` trace and an off-box direct read — while the in-process read
  stays raw."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            ;; Registers `:rf/machine` and the hook `rf/reg-machine` resolves through.
            [re-frame.machines]
            [re-frame.elision :as rf.elision]
            [re-frame.interop :as rf.interop]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.privacy :as rf.privacy]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private mid :rf.machine-sub-classification/auth)
(def ^:private token-1 "secret-sub-token-one")
(def ^:private token-2 "secret-sub-token-two")

(defn- reg-auth-machine!
  "`:data :token` sensitive, `:data :blob` large. `:login` and `:refresh` both
  store the event's token, so a held sub's recompute has a secret prior value."
  []
  (rf/reg-machine mid
    {:sensitive [[:data :token]]
     :large     [[:data :blob]]
     :initial   :anon
     :data      {:token nil :blob nil :retries 0}
     :actions   {:store (fn [{:keys [data event]}]
                          {:data (assoc data
                                        :token   (second event)
                                        :blob    (apply str (repeat 64 "b"))
                                        :retries (inc (:retries data)))})}
     :states    {:anon   {:on {:login {:target :authed :action :store}}}
                 :authed {:on {:refresh {:action :store}}}}}))

(defn- egress-view
  "An auth snapshot's `:state` and `:data`, its `:blob` reduced to whether it
  is the size marker."
  [snap]
  (when snap
    {:state (:state snap) :data (update (:data snap) :blob rf.elision/marker?)}))

(defn- authed [retries]
  {:state :authed :data {:token rf.privacy/redacted-sentinel :blob true :retries retries}})

(defn- capture-traces
  "Run `f` and return every trace event emitted meanwhile."
  [f]
  (let [captured (atom [])]
    (rf/register-listener! :trace ::capture (fn [ev] (swap! captured conj ev)))
    (try (f)
         @captured
         (finally (rf/unregister-listener! :trace ::capture)))))

(defn- runs-for
  "The captured `:rf.sub/run` events for `[:rf/machine id]`."
  [id events]
  (filterv #(and (= :rf.sub/run (:operation %))
                 (= [:rf/machine id] (get-in % [:tags :rf.sub/query-v])))
           events))

(defn- run-values
  "Each run's `[prev-value value]`, through `view`."
  [view runs]
  (mapv (fn [{t :tags}] (mapv view [(:rf.sub/prev-value t) (:rf.sub/value t)])) runs))

(deftest sub-run-trace-redacts-the-machine-declaration
  (reg-auth-machine!)
  (rf/dispatch-sync [mid [:login token-1]])
  (let [r      (rf/subscribe [:rf/machine mid])
        reads  (atom [])
        events (capture-traces
                 (fn []
                   (swap! reads conj @r)
                   (rf/dispatch-sync [mid [:refresh token-2]])
                   (swap! reads conj @r)))
        runs   (runs-for mid events)]
    (is (= [token-1 token-2] (map #(get-in % [:data :token]) @reads))
        "the in-process read stays raw")
    (when rf.interop/debug-enabled?
      (is (= [[nil (authed 1)] [(authed 1) (authed 2)]] (run-values egress-view runs))
          "a first run and a recompute, both redacted path-precisely")
      (is (not-any? #(.contains (pr-str runs) %) [token-1 token-2])))))

(deftest off-box-direct-read-redacts-the-machine-declaration
  ;; A direct read names the sub through `:query-v`. The declaration is keyed by
  ;; machine id, so a sibling machine that declares nothing rides raw.
  (reg-auth-machine!)
  (rf/reg-machine :rf.machine-sub-classification/plain
    {:initial :idle
     :data    {:token "not-a-secret"}
     :states  {:idle {}}})
  (rf/dispatch-sync [mid [:login token-1]])
  (rf/dispatch-sync [:rf.machine-sub-classification/plain [:rf.machine/noop]])
  (let [wire (fn [id] (rf.elision/elide-wire-value (rf.machines.test-support/snapshot id)
                                                   {:query-v [:rf/machine id] :frame :rf/default}))
        auth (wire mid)]
    (is (= [(authed 1) "not-a-secret"]
           [(egress-view auth) (get-in (wire :rf.machine-sub-classification/plain) [:data :token])]))
    (is (not (.contains (pr-str auth) token-1)))))

;; ---------------------------------------------------------------------------
;; Teardown drops a destroyed actor's claims from the frame's elision registry
;; and the held sub recomputes to nil. Its trace still carries the live
;; snapshot as `:rf.sub/prev-value`, classified by the claims that governed it.
;; ---------------------------------------------------------------------------

(def ^:private teardown-token "secret-sub-token-teardown")
(def ^:private probe-type :rf.machine-sub-classification/probe)
(def ^:private probe-actor :rf.machine-sub-classification/probe-actor)

(def ^:private probe-spec
  "`:data :token` sensitive, `:data :note` unclassified. `:finish` reaches a
  `:final?` state, whose auto-destroy is the second teardown route."
  {:sensitive [[:data :token]]
   :initial   :idle
   :data      {:token teardown-token :note "visible"}
   :states    {:idle {:on {:finish :done}}
               :done {:final? true}}})

(defn- claims-for
  "The frame's `:sensitive` declarations rooted at `id`'s snapshot."
  [id]
  (let [prefix (conj rf.elision/machine-snapshot-prefix id)]
    (filterv #(= prefix (subvec (vec %) 0 (min (count %) (count prefix))))
             (keys (rf.elision/sensitive-declarations :rf/default)))))

(defn- held-sub-across
  "Hold and deref `[:rf/machine id]`, run `teardown!`, then deref it again."
  [id teardown!]
  (let [r           (rf/subscribe [:rf/machine id])
        live        (atom nil)
        dead        (atom ::unread)
        claims-live (atom nil)
        events      (capture-traces
                      (fn []
                        (reset! live @r)
                        (reset! claims-live (claims-for id))
                        (teardown!)
                        (reset! dead @r)))]
    {:live        @live
     :dead        @dead
     :events      events
     :runs        (runs-for id events)
     :claims-live @claims-live
     :claims-dead (claims-for id)}))

(defn- assert-teardown-trace-classified
  [{:keys [live dead events runs claims-live claims-dead]}]
  (is (= [teardown-token nil] [(get-in live [:data :token]) dead])
      "the in-process reads stay raw, and the torn-down read is nil")
  (is (= [1 0] [(count claims-live) (count claims-dead)])
      "the actor's claim is removed with it")
  (when rf.interop/debug-enabled?
    (let [classified {:token rf.privacy/redacted-sentinel :note "visible"}]
      (is (= [[nil classified] [classified nil]]
             (run-values #(some-> % :data (select-keys [:token :note])) runs))
          "the torn-down run's prior snapshot keeps its classification"))
    (is (not (.contains (pr-str events) teardown-token))
        "no secret appears on any trace the teardown emits")))

(defn- reg-probe-actor!
  []
  (rf/reg-machine probe-type probe-spec)
  (rf/reg-event ::spawn-probe
    (fn [_ _] {:fx [[:rf.machine/spawn {:machine-id probe-type :fixed-actor-id probe-actor}]]}))
  (rf/dispatch-sync [::spawn-probe]))

(deftest explicit-destroy-keeps-a-spawned-actors-prev-value-classified
  (reg-probe-actor!)
  (rf/reg-event ::destroy-probe
    (fn [_ _] {:fx [[:rf.machine/destroy probe-actor]]}))
  (assert-teardown-trace-classified
    (held-sub-across probe-actor #(rf/dispatch-sync [::destroy-probe]))))

(deftest final-state-keeps-a-spawned-actors-prev-value-classified
  (reg-probe-actor!)
  (assert-teardown-trace-classified
    (held-sub-across probe-actor #(rf/dispatch-sync [probe-actor [:finish]]))))
