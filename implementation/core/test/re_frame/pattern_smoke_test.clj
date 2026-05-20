(ns re-frame.pattern-smoke-test
  "Per-pattern smoke tests. Each pattern doc proposes a canonical shape —
  slice keys, status enum, lifecycle events, state-machine states. The
  examples and the conformance corpus exercise the substrate; this suite
  pins each pattern's named shape so drift between the pattern doc and any
  conforming implementation is caught directly.

  One deftest per pattern. The docstring on each test cites the relevant
  Pattern-*.md section. Each test stays under ~50 lines — pin the shape,
  not every edge case."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- Pattern-Forms --------------------------------------------------------

(deftest pattern-forms-shape
  "Pattern-Forms §The form slice / §Standard events. Pins the seven slice
  keys (:draft :submitted :submit-attempted? :status :errors :touched
  :submit-error) and the seven-event lifecycle (initialise, edit-field,
  blur-field, submit, submit-success, submit-error, reset)."
  (testing "form slice transitions through documented states"
    (let [defaults {:email "" :password ""}
          slice    [:auth :login]]
      (rf/reg-event-db :form.login/initialise
        (fn [db _]
          (assoc-in db slice {:draft defaults :submitted nil
                              :submit-attempted? false :status :idle
                              :errors {} :touched #{} :submit-error nil})))
      (rf/reg-event-db :form.login/edit-field
        (fn [db [_ field value]]
          (-> db (assoc-in (conj slice :draft field) value)
                 (update-in (conj slice :touched) conj field))))
      (rf/reg-event-db :form.login/blur-field
        (fn [db [_ field]] (update-in db (conj slice :touched) conj field)))
      (rf/reg-event-db :form.login/submit
        (fn [db _] (-> db (assoc-in (conj slice :submit-attempted?) true)
                          (assoc-in (conj slice :status) :submitting))))
      (rf/reg-event-db :form.login/submit-success
        (fn [db _] (-> db (assoc-in (conj slice :status) :submitted)
                          (assoc-in (conj slice :submitted)
                                    (get-in db (conj slice :draft))))))
      (rf/reg-event-db :form.login/submit-error
        (fn [db [_ err]] (-> db (assoc-in (conj slice :status) :error)
                                (assoc-in (conj slice :submit-error) err))))
      (rf/reg-event-db :form.login/reset
        (fn [db _] (assoc-in db slice {:draft defaults :submitted nil
                                       :submit-attempted? false :status :idle
                                       :errors {} :touched #{}
                                       :submit-error nil})))
      ;; Lifecycle: init → :idle, edit/blur build up :touched, submit flips
      ;; :submit-attempted? and :status, success snapshots :draft → :submitted,
      ;; error sets :status :error + :submit-error, reset returns to :idle.
      (rf/dispatch-sync [:form.login/initialise])
      (let [s0 (get-in (rf/get-frame-db :rf/default) slice)]
        (is (= #{:draft :submitted :submit-attempted? :status :errors
                 :touched :submit-error}
               (set (keys s0))) "all seven canonical slice keys present")
        (is (= :idle (:status s0)))
        (is (false? (:submit-attempted? s0))))
      (rf/dispatch-sync [:form.login/edit-field :email "a@b.c"])
      (rf/dispatch-sync [:form.login/blur-field :password])
      (let [s1 (get-in (rf/get-frame-db :rf/default) slice)]
        (is (= "a@b.c" (get-in s1 [:draft :email])))
        (is (= #{:email :password} (:touched s1))))
      (rf/dispatch-sync [:form.login/submit])
      (is (= :submitting (get-in (rf/get-frame-db :rf/default)
                                 (conj slice :status))))
      (is (true? (get-in (rf/get-frame-db :rf/default)
                         (conj slice :submit-attempted?))))
      (rf/dispatch-sync [:form.login/submit-success])
      (let [s2 (get-in (rf/get-frame-db :rf/default) slice)]
        (is (= :submitted (:status s2)))
        (is (= "a@b.c" (get-in s2 [:submitted :email])) ":draft snapshotted to :submitted"))
      (rf/dispatch-sync [:form.login/submit-error "network down"])
      (let [s3 (get-in (rf/get-frame-db :rf/default) slice)]
        (is (= :error (:status s3)))
        (is (= "network down" (:submit-error s3))))
      (rf/dispatch-sync [:form.login/reset])
      (is (= :idle (get-in (rf/get-frame-db :rf/default) (conj slice :status)))))))

;; ---- Pattern-Boot ---------------------------------------------------------

(deftest pattern-boot-shape
  "Pattern-Boot §The simple form — chained events / §The state-machine
  canonical form / §Standard boot states. Verifies both the chained-event
  variant for trivial boots and the canonical machine variant exercising
  :configuring → :loading → :ready driven by :on-create."
  (testing "chained-events boot — :on-create dispatch threads through to :ready"
    (rf/reg-event-fx :app/init
      (fn [_ _] {:fx [[:dispatch [:config/load]]]}))
    (rf/reg-event-fx :config/load
      (fn [{:keys [db]} _] {:db (assoc db :config {:loaded? true})
                            :fx [[:dispatch [:app/ready]]]}))
    (rf/reg-event-db :app/ready
      (fn [db _] (assoc db :app/booted? true)))
    (let [f (rf/make-frame {:on-create [:app/init]})
          db (rf/get-frame-db f)]
      (is (true? (:app/booted? db)) "chained-events boot reached :app/ready")
      (is (= {:loaded? true} (:config db)))))
  (testing "machine boot — :configuring → :loading → :ready"
    (rf/reg-event-fx :app/boot
      (rf/make-machine-handler
        {:initial :configuring
         :data    {:config nil}
         :actions {:record-config (fn [data [_ c]]
                                    {:data (assoc data :config c)})}
         :states
         {:configuring {:on {:configured {:target :loading
                                          :action :record-config}}}
          :loading     {:on {:loaded     {:target :ready}}}
          :ready       {}}}))
    ;; :on-create kicks the boot machine; subsequent dispatched lifecycle
    ;; events drive the documented progression to :ready.
    (let [f (rf/make-frame {:on-create [:app/boot [:configured {:url "/api"}]]})]
      (is (= :loading (get-in (rf/get-frame-db f)
                              [:rf/machines :app/boot :state]))
          ":on-create transitioned :configuring → :loading")
      (rf/dispatch-sync [:app/boot [:loaded]] {:frame f})
      (is (= :ready (get-in (rf/get-frame-db f)
                            [:rf/machines :app/boot :state]))
          "lifecycle events drove machine to :ready")
      (is (= {:url "/api"} (get-in (rf/get-frame-db f)
                                   [:rf/machines :app/boot :data :config]))))))

;; ---- Pattern-RemoteData ---------------------------------------------------

(deftest pattern-remote-data-shape
  "Pattern-RemoteData §The lifecycle slice / §The four standard events /
  §`:loading` vs `:fetching`. Pins the 5-key slice (:status :data :error
  :loaded-at :attempt), the status enum {:idle :loading :fetching :loaded
  :error}, and the load / loaded / load-failed / reset event lifecycle."
  (let [path [:articles]]
    (rf/reg-event-db :articles/initialise
      (fn [db _] (assoc-in db path {:status :idle :data nil :error nil
                                    :loaded-at nil :attempt 0})))
    (rf/reg-event-db :articles/load
      (fn [db _]
        (let [has-data? (some? (get-in db (conj path :data)))]
          (-> db
              (assoc-in (conj path :status)  (if has-data? :fetching :loading))
              (assoc-in (conj path :error)   nil)
              (update-in (conj path :attempt) inc)))))
    (rf/reg-event-db :articles/loaded
      (fn [db [_ data]]
        (-> db (assoc-in (conj path :status) :loaded)
               (assoc-in (conj path :data) data)
               (assoc-in (conj path :loaded-at) 1234)
               (assoc-in (conj path :error) nil))))
    (rf/reg-event-db :articles/load-failed
      (fn [db [_ err]]
        (-> db (assoc-in (conj path :status) :error)
               (assoc-in (conj path :error) err))))
    (rf/reg-event-db :articles/reset
      (fn [db _] (assoc-in db path {:status :idle :data nil :error nil
                                    :loaded-at nil :attempt 0})))
    (rf/dispatch-sync [:articles/initialise])
    (let [s0 (get-in (rf/get-frame-db :rf/default) path)]
      (is (= #{:status :data :error :loaded-at :attempt} (set (keys s0)))
          "all five canonical slice keys present")
      (is (= :idle (:status s0)))
      (is (zero? (:attempt s0)) ":attempt 0 means never fetched"))
    ;; Initial load: no prior :data → :loading; :attempt bumps to 1.
    (rf/dispatch-sync [:articles/load])
    (let [s1 (get-in (rf/get-frame-db :rf/default) path)]
      (is (= :loading (:status s1)) "initial load with no :data → :loading")
      (is (= 1 (:attempt s1))))
    (rf/dispatch-sync [:articles/loaded [{:id "a"}]])
    (let [s2 (get-in (rf/get-frame-db :rf/default) path)]
      (is (= :loaded (:status s2)))
      (is (= [{:id "a"}] (:data s2)))
      (is (= 1234 (:loaded-at s2))))
    ;; Revalidate over existing :data → :fetching (NOT :loading).
    (rf/dispatch-sync [:articles/load])
    (is (= :fetching (get-in (rf/get-frame-db :rf/default)
                             (conj path :status)))
        "revalidate with existing :data → :fetching")
    (is (= 2 (get-in (rf/get-frame-db :rf/default) (conj path :attempt))))
    (rf/dispatch-sync [:articles/load-failed "boom"])
    (let [s3 (get-in (rf/get-frame-db :rf/default) path)]
      (is (= :error (:status s3)))
      (is (= "boom" (:error s3)))
      (is (= [{:id "a"}] (:data s3)) "prior :data preserved across :error"))
    (rf/dispatch-sync [:articles/reset])
    (is (= :idle (get-in (rf/get-frame-db :rf/default)
                         (conj path :status))))
    (is (nil? (get-in (rf/get-frame-db :rf/default) (conj path :data))))))

;; ---- Pattern-WebSocket ----------------------------------------------------

(deftest pattern-websocket-shape
  "Pattern-WebSocket §The connection state machine / §Standard transitions.
  Drives the connection state machine through :disconnected → :connecting
  → :authenticating → :connected → :reconnecting → :failed via dispatched
  lifecycle events. The actual WebSocket actor is stubbed (no :spawn);
  this pins the documented six-state lifecycle."
  (let [machine
        {:initial :disconnected
         :data    {:retries 0 :max-retries 2}
         :guards  {:max-retries-exceeded?
                   (fn [data _]
                     (>= (:retries data) (:max-retries data)))}
         :actions {:bump-retry  (fn [data _]
                                  {:data (update data :retries inc)})
                   :reset-retry (fn [data _]
                                  {:data (assoc data :retries 0)})}
         :states
         {:disconnected   {:on {:ws/connect {:target :connecting}}}
          :connecting     {:on {:ws/opened {:target :authenticating}
                                :ws/error  {:target :reconnecting
                                            :action :bump-retry}}}
          :authenticating {:on {:ws/auth-ok     {:target :connected
                                                 :action :reset-retry}
                                :ws/auth-failed {:target :failed}}}
          :connected      {:on {:ws/closed {:target :reconnecting
                                            :action :bump-retry}}}
          ;; Reconnect from :reconnecting does NOT reset-retry — the retry
          ;; counter accumulates across the backoff cycle so :always can
          ;; trip → :failed after max-retries.
          :reconnecting   {:always [{:guard :max-retries-exceeded?
                                     :target :failed}]
                           :on {:ws/connect {:target :connecting}}}
          :failed         {:on {:ws/connect {:target :connecting
                                             :action :reset-retry}}}}}
        step (fn [snap event]
               (::result/snap (machines/machine-transition machine snap event)))]
    ;; Happy path: disconnected → connecting → authenticating → connected.
    (let [s1 (step {:state :disconnected :data {:retries 0 :max-retries 2}}
                   [:ws/connect])
          s2 (step s1 [:ws/opened])
          s3 (step s2 [:ws/auth-ok])]
      (is (= :connecting     (:state s1)))
      (is (= :authenticating (:state s2)))
      (is (= :connected      (:state s3)) "happy path lands in :connected")
      ;; Connection-loss cycle: drop, retry, drop again until max-retries
      ;; trips :always → :failed.
      (let [s4 (step s3 [:ws/closed])]                 ;; retries 1
        (is (= :reconnecting (:state s4)))
        (let [s5 (step s4 [:ws/connect])               ;; back to :connecting
              s6 (step s5 [:ws/error])]                ;; retries 2 → :reconnecting → :always → :failed
          (is (= :connecting (:state s5)))
          (is (= :failed (:state s6))
              "max retries exceeded → :always tripped → :failed")
          (let [s7 (step s6 [:ws/connect])]
            (is (= :connecting (:state s7))
                ":failed accepts external [:ws/connect] to retry")))))))

;; ---- Pattern-StaleDetection -----------------------------------------------

(deftest pattern-stale-detection-shape
  "Pattern-StaleDetection §The pattern, stated formally / §Trace event
  naming convention. Captures an epoch (the machine's :rf/after-epoch),
  dispatches async work that carries it, verifies carried-epoch matches
  → commit; mismatch → suppress and emit :rf.machine.timer/stale-after.

  Scenario: a single `:loading` state with `:after`. Leaving and re-entering
  it advances the epoch; an in-flight timer captured at the prior entry now
  carries a stale epoch that won't match the live one."
  (let [machine {:initial :idle
                 :data    {:rf/after-epoch 0}
                 :states  {:idle    {:on    {:fetch :loading
                                             :reset :idle}}
                           :loading {:after {5000 :timeout}
                                     :on    {:loaded :idle
                                             :cancel :idle}}
                           :timeout {}}}]
    (testing "match → commit (timer fires with the carried epoch)"
      (let [s0 {:state :idle :data {:rf/after-epoch 0}}
            s1 (::result/snap (machines/machine-transition machine s0 [:fetch]))
            captured-epoch (get-in s1 [:data :rf/after-epoch])]
        (is (= :loading (:state s1)))
        (is (= 1 captured-epoch) "epoch advances on entry to :after-bearing state")
        (let [s2 (::result/snap (machines/machine-transition
                          machine s1
                          [:rf.machine.timer/after-elapsed 5000 captured-epoch]))]
          (is (= :timeout (:state s2)) "matching epoch → transition commits"))))
    (testing "mismatch → suppress + :rf.machine.timer/stale-after trace"
      ;; Enter :loading (epoch 1, captured by the in-flight timer); leave
      ;; via :cancel (epoch advances to 2); re-enter via :fetch (epoch 3).
      ;; The original timer fires carrying epoch 1 against current epoch 3.
      (let [traces (atom [])
            s0 {:state :idle :data {:rf/after-epoch 0}}
            s1 (::result/snap (machines/machine-transition machine s0 [:fetch]))      ;; epoch 1
            captured 1
            s2 (::result/snap (machines/machine-transition machine s1 [:cancel]))     ;; epoch 2
            s3 (::result/snap (machines/machine-transition machine s2 [:fetch]))]     ;; epoch 3
        (is (= :loading (:state s3)))
        (is (= 3 (get-in s3 [:data :rf/after-epoch])))
        (rf/register-trace-listener! ::stale (fn [ev] (swap! traces conj ev)))
        (let [s4 (::result/snap (machines/machine-transition
                          machine s3
                          [:rf.machine.timer/after-elapsed 5000 captured]))]
          (rf/unregister-trace-listener! ::stale)
          (is (= s3 s4) "stale timer firing leaves snapshot unchanged")
          (is (some (fn [ev]
                      (and (= :rf.machine.timer/stale-after (:operation ev))
                           (= captured (:scheduled-epoch (:tags ev)))
                           (= 3        (:current-epoch (:tags ev)))))
                    @traces)
              "expected :rf.machine.timer/stale-after trace with both epochs"))))))

;; ---- Pattern-LongRunningWork ----------------------------------------------

(deftest pattern-long-running-work-shape
  "Pattern-LongRunningWork §The chunked state-machine pattern / §Canonical
  states. Drives a small batched workload through :idle → :processing →
  :yielding → :complete; verifies progress in :data; confirms :after 0 is
  the yield mechanism between chunks."
  (let [machine
        {:initial :idle
         :data    {:total 0 :processed 0 :chunk-size 2 :input nil :result []}
         :guards  {:done?      (fn [data _]
                                 (>= (:processed data) (:total data)))
                   :more-work? (fn [data _]
                                 (< (:processed data) (:total data)))}
         :actions {:start-job
                   (fn [_ [_ input]]
                     {:data {:total (count input) :input input
                             :chunk-size 2 :processed 0 :result []}})
                   :process-chunk
                   (fn [data _]
                     (let [{:keys [input chunk-size processed result]} data
                           chunk (subvec input processed
                                         (min (+ processed chunk-size)
                                              (count input)))
                           outs  (mapv #(* % %) chunk)]
                       {:data (-> data
                                  (update :processed + (count chunk))
                                  (assoc  :result (into result outs)))}))}
         :states  {:idle          {:on {:start {:target :processing
                                                :action :start-job}}}
                   :processing    {:entry  :process-chunk
                                   :always [{:target :checking-done}]}
                   :checking-done {:always [{:guard :done?      :target :complete}
                                            {:guard :more-work? :target :yielding}]}
                   :yielding      {:after {0 :processing}}
                   :complete      {}}}
        step (fn [snap event]
               (::result/snap (machines/machine-transition machine snap event)))]
    (let [s0 {:state :idle :data (:data machine)}
          ;; :start runs :start-job, enters :processing (chunk 1: items
          ;; 0..1 squared), :always → :checking-done → :yielding.
          s1 (step s0 [:start [1 2 3 4 5]])]
      (is (= :yielding (:state s1)) "after first chunk, parked in :yielding")
      (is (= 2 (get-in s1 [:data :processed])) "progress reflects chunk 1")
      (is (= [1 4] (get-in s1 [:data :result])))
      ;; :after 0 is the yield mechanism — driven by the synthetic
      ;; timer-elapsed event re-entering :processing.
      (let [epoch (get-in s1 [:data :rf/after-epoch])
            s2    (step s1 [:rf.machine.timer/after-elapsed 0 epoch])]
        (is (= :yielding (:state s2)) "second yield-tick lands back in :yielding")
        (is (= 4 (get-in s2 [:data :processed])))
        (is (= [1 4 9 16] (get-in s2 [:data :result])))
        (let [epoch2 (get-in s2 [:data :rf/after-epoch])
              ;; Final chunk processes the last item; :done? guard wins;
              ;; :checking-done → :complete (terminal).
              s3 (step s2 [:rf.machine.timer/after-elapsed 0 epoch2])]
          (is (= :complete (:state s3)) "terminal state reached after final chunk")
          (is (= 5 (get-in s3 [:data :processed])))
          (is (= [1 4 9 16 25] (get-in s3 [:data :result]))))))))
