(ns re-frame.http-managed-machine-cljs-test
  "CLJS-side smoke for Spec 014 §Machine-shape wrapper (rf2-ijm7).

  The JVM test (re-frame.http-managed-machine-test) exercises the full
  end-to-end shape against the in-process JDK HTTP server. This file
  confirms that on CLJS:

  - The wrapper machine registration succeeds when `re-frame.machines`
    is on the classpath at http-managed load time, and its `:succeeded` /
    `:failed` terminals are `:final?` leaves (rf2-6gxs).
  - A parent machine `:spawn`ing `:rf.http/managed` with the canned
    success stub fires the wrapper, transitions to `:succeeded`, and
    dispatches `[<parent-id> [:succeeded value]]` back to the parent.
  - Wrapper children under `:spawn-all` resolve the join (Spec 014
    §Multiple wrappers per parent), hand a join parent the same value a
    `:spawn` parent receives, and never message a join parent directly
    (rf2-6gxs).

  The Fetch transport itself is covered by the broader CLJS test
  suite; this smoke is scoped to the wrapper's machine-shape envelope
  plus the back-channel to the parent."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            ;; re-frame.machines and re-frame.http.managed cross-publish their
            ;; registration hooks through `re-frame.late-bind` (machines
            ;; publishes `:machines/reg-machine`; http-managed publishes
            ;; `:http/register-managed-machine!`). Either ns can load first;
            ;; whichever loads second triggers the wrapper registration
            ;; (rf2-ijm7). Listing both here makes the dependency closure
            ;; explicit so the bundle includes both producers.
            [re-frame.machines :as rf.machines]
            [re-frame.http.managed :as rf.http.managed]
            ;; rf2-lwmgw — the stub macros / install fn live in
            ;; `re-frame.http.test-support` (alongside the canned-stub fx
            ;; registrations). This test calls `install-managed-request-stubs!`
            ;; directly, so it requires that ns.
            [re-frame.http.test-support :as rf.http.test-support]
            [re-frame.registrar :as rf.registrar]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :init-fn (fn []
                (rf.machines/reset-timers!)
                (rf.http.managed/clear-all-in-flight!))}))

(defn- snapshot [machine-id]
  (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/machines :snapshots machine-id]))

;; ---- (1) wrapper registration succeeds on classpath ---------------------

(deftest wrapper-is-registered-when-machines-is-present
  (testing "loading both re-frame.machines and re-frame.http.managed registers `:rf.http/managed` as a machine"
    (let [meta (rf.registrar/lookup :event :rf.http/managed)]
      (is (some? meta) ":rf.http/managed is registered as an :event")
      (is (true? (:rf/machine? meta))
          ":rf/machine? metadata flag is set — it's an actual machine, not a vanilla event")
      (let [spec (:rf/machine meta)]
        (is (= :requesting (:initial spec)))
        (is (contains? (:states spec) :requesting))
        (is (contains? (:states spec) :succeeded))
        (is (contains? (:states spec) :failed))
        ;; rf2-6gxs — this used to assert `:meta {:terminal? true}`, which pinned
        ;; the defect: `:terminal?` is not `:final?` (Spec 005 §Child completion
        ;; protocol), so a `:spawn-all` join over these children never resolved.
        (is (= {:final? true :output-key :rf/result}
               (select-keys (get-in spec [:states :succeeded]) [:final? :error? :output-key]))
            ":succeeded is a :final? leaf whose result is :rf/result")
        (is (= {:final? true :error? true :output-key :rf/result}
               (select-keys (get-in spec [:states :failed]) [:final? :error? :output-key]))
            ":failed is an :error? :final? leaf whose result is :rf/result")))))

;; ---- (2) parent :spawn spawns wrapper, registry/snapshot wiring -------

(deftest invoke-spawns-wrapper-and-injects-framework-keys
  (testing "parent :spawn {:machine-id :rf.http/managed ...} spawns the wrapper actor and stamps :rf/parent-id / :rf/self-id / :rf/invoke-id into the wrapper's :data (rf2-ijm7)"
    ;; Install a stub that NEVER replies — gives us a stable
    ;; :requesting snapshot to inspect without racing against Fetch.
    (rf.http.test-support/install-managed-request-stubs! {})
    (try
      (rf/reg-machine :cljs/auth2
        {:initial :idle
         :states
         {:idle {:on {:login :authenticating}}
          :authenticating
          {:spawn {:machine-id :rf.http/managed
                    :data       {:request {:url "/api/me" :method :get}}}
           :on     {:succeeded :authenticated
                    :failed    :idle}}
          :authenticated {}}})
      (rf/dispatch-sync
        [:cljs/auth2 [:login]]
        ;; Route the wrapper actor's outgoing :rf.http/managed fx to the
        ;; stub installed above, so it does NOT issue a real Fetch.
        ;; Per-call fx-overrides apply for the duration of this drain —
        ;; including the wrapper actor's child dispatch that emits the
        ;; underlying fx.
        {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
      (let [db (:rf.db/runtime (rf/frame-state-value :rf/default))]
        (is (= :rf.http/managed#1
               (get-in db [:rf.runtime/machines :spawned :cljs/auth2 [:authenticating]]))
            "the wrapper actor is bound under the parent's spawn-registry slot")
        (let [wrapper-snap (snapshot :rf.http/managed#1)
              wrapper-data (:data wrapper-snap)]
          (is (= :requesting (:state wrapper-snap))
              "the wrapper is in :requesting awaiting the (stubbed-out) reply")
          (is (= :rf.http/managed#1 (:rf/self-id wrapper-data))
              ":rf/self-id is stamped to the wrapper's own id")
          (is (= :cljs/auth2 (:rf/parent-id wrapper-data))
              ":rf/parent-id is stamped to the parent machine's id (rf2-ijm7)")
          (is (= [:authenticating] (:rf/invoke-id wrapper-data))
              ":rf/invoke-id is stamped to the parent's :spawn-bearing state path")
          (is (= {:url "/api/me" :method :get} (:request wrapper-data))
              "the user's :request is preserved verbatim under :data")))
      (finally
        (rf.http.test-support/uninstall-managed-request-stubs!)))))

;; ---- (2b) nested :request-content-type survives the wrapper pass-through -

(deftest invoke-preserves-nested-request-content-type
  (testing "a `:request-content-type` nested under `:request` (the Spec 014 §Args carrier shape) survives the wrapper's :data → fx-args pass-through verbatim (rf2-ycna8w)"
    ;; The transport reads request content-type only from the nested
    ;; request envelope (transport/prepare-request reads
    ;; `(:request-content-type request)`), so the Args-carrier example
    ;; MUST nest it under `:request`, not at the top level of `:data`.
    ;; This proves the corrected example shape threads through.
    (rf.http.test-support/install-managed-request-stubs! {})  ;; no-reply stub: stable :requesting snapshot
    (try
      (rf/reg-machine :cljs/poster
        {:initial :idle
         :states
         {:idle {:on {:post :posting}}
          :posting
          {:spawn {:machine-id :rf.http/managed
                    :data       {:request {:url    "/api/sessions"
                                           :method :post
                                           :body   {:user "ada"}
                                           :request-content-type :json}}}
           :on     {:succeeded :done
                    :failed    :idle}}
          :done {}}})
      (rf/dispatch-sync
        [:cljs/poster [:post]]
        {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
      (let [wrapper-data (:data (snapshot :rf.http/managed#1))]
        (is (= {:url "/api/sessions" :method :post :body {:user "ada"}
                :request-content-type :json}
               (:request wrapper-data))
            ":request-content-type nested under :request is preserved verbatim — the runtime reads it from the nested request envelope")
        (is (not (contains? wrapper-data :request-content-type))
            ":request-content-type is NOT promoted to the top level of :data"))
      (finally
        (rf.http.test-support/uninstall-managed-request-stubs!)))))

;; ---- (3) parent state-exit destroys wrapper child + clears registry ----

(deftest parent-exit-destroys-wrapper-child
  (testing "transition out of the :spawn-bearing state destroys the wrapper actor — registry slot cleared, snapshot gone (rf2-ijm7 + rf2-wvkn destroy cascade)"
    (rf.http.test-support/install-managed-request-stubs! {})  ;; no-match stub: synthesises a failure (which we will not observe)
    (try
      (rf/reg-machine :cljs/cancellable
        {:initial :idle
         :states
         {:idle {:on {:login :authenticating}}
          :authenticating
          {:spawn {:machine-id :rf.http/managed
                    :data       {:request {:url "/never-returns" :method :get}}}
           :on     {:cancel    :idle
                    :succeeded :authenticated
                    :failed    :idle}}
          :authenticated {}}})
      ;; The stub will synthesise a failure (no match), which will be
      ;; dispatched via the router. We cancel BEFORE that dispatch
      ;; drains so the wrapper is in :requesting at the moment of
      ;; cancel. The cancel's destroy cascade tears down the wrapper
      ;; synchronously inside this dispatch-sync.
      (rf/dispatch-sync
        [:cljs/cancellable [:login]]
        {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
      (rf/dispatch-sync [:cljs/cancellable [:cancel]])
      (let [db (:rf.db/runtime (rf/frame-state-value :rf/default))]
        (is (nil? (get-in db [:rf.runtime/machines :spawned :cljs/cancellable [:authenticating]]))
            "spawn-registry slot cleared by the destroy cascade")
        (is (nil? (get-in db [:rf.runtime/machines :snapshots :rf.http/managed#1]))
            "wrapper actor's snapshot is gone after the parent's cancel"))
      (finally
        (rf.http.test-support/uninstall-managed-request-stubs!)))))

;; ---- (4) wrapper children under :spawn-all (rf2-6gxs) -------------------
;;
;; Spec 014 §Multiple wrappers per parent puts `:rf.http/managed` children
;; under `:spawn-all`. A join resolves only when each child reaches a `:final?`
;; state (Spec 005 §Child completion protocol), which the wrapper's terminals
;; did not declare — so before rf2-6gxs the parent stayed in `:hydrating` with
;; `:done #{}` for ever, both requests having answered.

(defn- stubbed-frame!
  "An anon frame whose PER-FRAME `:fx-overrides` route every `:rf.http/managed`
  to `stub-fx-id`. Per-frame, because the wrapper children fire their requests
  on later ticks, and a per-call override on the opening `dispatch-sync` did
  not reach a `:spawn-all` child's request (measured on the JVM under rf2-6gxs:
  the real transport ran). Create it AFTER the stub fx and the machines are
  registered — a frame seals its image generation at construction
  (rf2-bxc8kf)."
  [stub-fx-id]
  (rf.frame/make-anon-frame-record!
    {:doc          "rf2-6gxs — :rf.http/managed wrapper children"
     :fx-overrides {:rf.http/managed stub-fx-id}}))

(defn- snap-in [frame machine-id]
  (get-in (:rf.db/runtime (rf/frame-state-value frame))
          [:rf.runtime/machines :snapshots machine-id]))

(defn- resolved-event
  "The event the parent recorded when it left its waiting state."
  [frame parent-id]
  (get-in (snap-in frame parent-id) [:data :resolved]))

(defn- settled? [frame parent-id]
  #(contains? #{:done :failed :decoy} (:state (snap-in frame parent-id))))

(defn- where-is [frame parent-id]
  (let [join (get-in (:rf.db/runtime (rf/frame-state-value frame))
                     [:rf.runtime/machines :spawned parent-id [:hydrating]])]
    (str parent-id " :state " (pr-str (:state (snap-in frame parent-id)))
         ", join :done " (pr-str (:done join)) " :failed " (pr-str (:failed join)))))

(def ^:private record-event
  {:record (fn [{data :data ev :event}] {:data (assoc data :resolved (vec ev))})})

(defn- wrapper-child [id url]
  {:id id :machine-id :rf.http/managed :data {:request {:url url :method :get}}})

(defn- reg-join-parent!
  "A parent that fans `children` out under `:spawn-all` and records the join
  event it resolves on. `extra-on` merges into the `:hydrating` `:on` map; any
  target it names should be `:decoy`."
  ([parent-id join children] (reg-join-parent! parent-id join children {}))
  ([parent-id join children extra-on]
   (rf/reg-machine parent-id
     {:initial :idle
      :actions record-event
      :states
      (cond-> {:idle      {:on {:go :hydrating}}
               :hydrating {:spawn-all (merge {:children      children
                                              :join          join
                                              :on-any-failed [:any-failed]}
                                             (if (= :any join)
                                               {:on-some-complete [:some-done]}
                                               {:on-all-complete [:all-done]}))
                           :on        (merge {:all-done   {:target :done   :action :record}
                                              :some-done  {:target :done   :action :record}
                                              :any-failed {:target :failed :action :record}}
                                             extra-on)}
               :done      {}
               :failed    {}}
        (seq extra-on) (assoc :decoy {}))})))

(defn- reg-spawn-parent! [parent-id url]
  (rf/reg-machine parent-id
    {:initial :idle
     :actions record-event
     :states
     {:idle       {:on {:go :requesting}}
      :requesting {:spawn {:machine-id :rf.http/managed
                           :data       {:request {:url url :method :get}}}
                   :on    {:succeeded {:target :done   :action :record}
                           :failed    {:target :failed :action :record}}}
      :done       {}
      :failed     {}}}))

(deftest spawn-all-join-all-resolves-over-wrapper-children
  (testing "two :rf.http/managed children under :spawn-all :join :all resolve the join once both reply; the join event carries the decisive child's decoded value and each child tore itself down at finality (rf2-6gxs)"
    (async done
      (rf.http.test-support/install-managed-request-stubs!
        {[:get "/api/me"]    {:reply {:ok {:id 42}}}
         [:get "/api/prefs"] {:reply {:ok {:theme "dark"}}}})
      (reg-join-parent! :cljs/hydrate :all [(wrapper-child :user "/api/me")
                                            (wrapper-child :prefs "/api/prefs")])
      (let [f (stubbed-frame! :rf.http/managed-test-stub)]
        (rf/dispatch-sync [:cljs/hydrate [:go]] {:frame f})
        (-> (rf.test-support/poll-until (settled? f :cljs/hydrate)
                                        {:timeout-ms 2000 :label "rf2-6gxs :join :all resolves"})
            (.then (fn [_]
                     (let [[ev child-id result] (resolved-event f :cljs/hydrate)]
                       (is (= :done (:state (snap-in f :cljs/hydrate))))
                       (is (= :all-done ev) ":on-all-complete fired")
                       (is (contains? #{:user :prefs} child-id) "the join names its decisive child")
                       (is (= (get {:user {:id 42} :prefs {:theme "dark"}} child-id) result)
                           "the join result is the decisive child's (:value reply), not the reply envelope"))
                     (is (nil? (snap-in f :rf.http/managed#1)) "finality tore the first child down")
                     (is (nil? (snap-in f :rf.http/managed#2)) "finality tore the second child down")))
            (.catch (fn [e] (is false (str "rf2-6gxs — " (where-is f :cljs/hydrate) " — " (.-message e))) nil))
            (.then (fn [_] (rf.http.test-support/uninstall-managed-request-stubs!) (done))))))))

(deftest spawn-and-spawn-all-parents-receive-the-same-value
  (testing "one wrapper reply reaches a :spawn parent as [:succeeded value] / [:failed failure] and a :spawn-all parent as the join result — the SAME value on both paths, success and failure, so `:output-key` and the single-:spawn dispatch cannot drift apart; the failure path is also an accepted child error reaching :on-any-failed (rf2-6gxs)"
    (async done
      (rf.http.test-support/install-managed-request-stubs!
        {[:get "/api/ok"]  {:reply {:ok {:id 42}}}
         [:get "/api/bad"] {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}})
      (reg-spawn-parent! :cljs/spawn-ok  "/api/ok")
      (reg-spawn-parent! :cljs/spawn-bad "/api/bad")
      (reg-join-parent!  :cljs/join-ok   :any [(wrapper-child :only "/api/ok")])
      (reg-join-parent!  :cljs/join-bad  :any [(wrapper-child :only "/api/bad")])
      ;; One frame per parent, so the four wrapper children never share an
      ;; actor address.
      (let [frames (into {} (map (fn [pid] [pid (stubbed-frame! :rf.http/managed-test-stub)]))
                         [:cljs/spawn-ok :cljs/spawn-bad :cljs/join-ok :cljs/join-bad])
            ev     (fn [pid] (resolved-event (frames pid) pid))]
        (doseq [[pid f] frames] (rf/dispatch-sync [pid [:go]] {:frame f}))
        (-> (rf.test-support/poll-until #(every? (fn [[pid f]] ((settled? f pid))) frames)
                                        {:timeout-ms 2000 :label "rf2-6gxs four parents settle"})
            (.then (fn [_]
                     (let [[s-ok v-ok]        (ev :cljs/spawn-ok)
                           [s-bad v-bad]      (ev :cljs/spawn-bad)
                           [j-ok _ jv-ok]     (ev :cljs/join-ok)
                           [j-bad _ jv-bad]   (ev :cljs/join-bad)]
                       (is (= [:succeeded {:id 42}] [s-ok v-ok]) "a :spawn parent receives [:succeeded (:value reply)]")
                       (is (= :some-done j-ok))
                       (is (= v-ok jv-ok) "success — the join result is the value the :spawn parent receives")
                       (is (= :failed s-bad) "a :spawn parent receives [:failed …]")
                       (is (= :any-failed j-bad) "an accepted child error reaches :on-any-failed")
                       (is (= :rf.http/http-5xx (:kind v-bad)) "the :spawn parent's failure is the classified (:error reply)")
                       (is (= v-bad jv-bad) "failure — the join result is the failure the :spawn parent receives"))))
            (.catch (fn [e] (is false (str "rf2-6gxs — "
                                           (pr-str (into {} (map (fn [[pid f]] [pid (:state (snap-in f pid))])) frames))
                                           " — " (.-message e)))
                      nil))
            (.then (fn [_] (rf.http.test-support/uninstall-managed-request-stubs!) (done))))))))

(deftest spawn-all-join-any-cancels-the-live-sibling
  (testing "under :join :any the first wrapper to reply resolves the join, and the join cancels the sibling still waiting on its request (rf2-6gxs)"
    (async done
      (let [traces (atom [])
            cb-id  (gensym "rf2-6gxs-any-")]
        ;; Answers /api/fast only — /api/slow stays in flight.
        (rf.fx/reg-fx :cljs/fast-only-stub
          {:doc "rf2-6gxs — replies to /api/fast, never to /api/slow"}
          (fn [frame-ctx args-map]
            (when (= "/api/fast" (get-in args-map [:request :url]))
              (rf.http.test-support/canned-success-handler
                frame-ctx (assoc args-map :value {:fast true})))))
        (rf.trace.tooling/register-listener! cb-id (fn [trace] (swap! traces conj trace)))
        (reg-join-parent! :cljs/race :any [(wrapper-child :fast "/api/fast")
                                           (wrapper-child :slow "/api/slow")])
        (let [f (stubbed-frame! :cljs/fast-only-stub)]
          (rf/dispatch-sync [:cljs/race [:go]] {:frame f})
          (-> (rf.test-support/poll-until (settled? f :cljs/race)
                                          {:timeout-ms 2000 :label "rf2-6gxs :join :any resolves"})
              (.then (fn [_]
                       (is (= [:some-done :fast {:fast true}] (resolved-event f :cljs/race))
                           ":on-some-complete fired on the child that replied")
                       (is (= [:slow]
                              (->> @traces
                                   (filter #(= :rf.machine.spawn/cancelled-on-join-resolution (:operation %)))
                                   (mapv #(get-in % [:tags :child-id]))))
                           "the join cancelled exactly the live sibling")))
              (.catch (fn [e] (is false (str "rf2-6gxs — " (where-is f :cljs/race) " — " (.-message e))) nil))
              (.then (fn [_]
                       (rf.trace.tooling/unregister-listener! cb-id)
                       (rf.registrar/unregister! :fx :cljs/fast-only-stub)
                       (done)))))))))

(deftest join-child-sends-no-succeeded-event-to-its-parent
  (testing "a :spawn-all parent that also carries the single-:spawn habit `:on {:succeeded … :failed …}` resolves through its join: a join child's terminal :entry stays silent, so no spurious [:succeeded value] reaches the parent (rf2-6gxs). A :final? state's :entry still runs, so without the :rf/join-child gate the first child to finish would send it, the parent would leave :hydrating on it, and its own join would be torn down"
    (async done
      (rf.http.test-support/install-managed-request-stubs!
        {[:get "/api/me"]    {:reply {:ok {:id 42}}}
         [:get "/api/prefs"] {:reply {:ok {:theme "dark"}}}})
      (reg-join-parent! :cljs/habit :all
                        [(wrapper-child :user "/api/me") (wrapper-child :prefs "/api/prefs")]
                        {:succeeded {:target :decoy :action :record}
                         :failed    {:target :decoy :action :record}})
      (let [f (stubbed-frame! :rf.http/managed-test-stub)]
        (rf/dispatch-sync [:cljs/habit [:go]] {:frame f})
        (-> (rf.test-support/poll-until (settled? f :cljs/habit)
                                        {:timeout-ms 2000 :label "rf2-6gxs habit parent settles"})
            (.then (fn [_]
                     (is (= :done (:state (snap-in f :cljs/habit)))
                         "the parent resolved through its join, not through a spurious :succeeded")
                     (is (= :all-done (first (resolved-event f :cljs/habit)))
                         (str "the parent recorded " (pr-str (resolved-event f :cljs/habit))))))
            (.catch (fn [e] (is false (str "rf2-6gxs — " (where-is f :cljs/habit) " — " (.-message e))) nil))
            (.then (fn [_] (rf.http.test-support/uninstall-managed-request-stubs!) (done))))))))
