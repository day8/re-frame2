(ns re-frame.http-managed-machine-cljs-test
  "Spec 014 §Machine-shape wrapper on CLJS, through canned stubs: a `:spawn`
  parent and a `:spawn-all` join parent receive the same value from a wrapper
  reply, and a join child never messages its parent directly (Spec 014
  §Multiple wrappers per parent). The JVM round trips are
  `re-frame.http-managed-machine-test`."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.frame :as rf.frame]
            ;; Whichever of these two loads second registers the wrapper.
            [re-frame.machines :as rf.machines]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.test-support :as rf.http.test-support]
            [re-frame.test-support :as rf.test-support]))

;; `:async? true` — the :spawn-all rows are `async`, and cljs.test aborts a
;; namespace holding async tests unless its fixtures are the map form.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter
     :async?  true
     :init-fn (fn []
                (rf.machines/reset-timers!)
                (rf.http.managed/clear-all-in-flight!))}))

;; A join resolves only when each child reaches a `:final?` state (Spec 005
;; §Child completion protocol), so the wrapper's terminals declare `:final?`.

(defn- stubbed-frame!
  "An anon frame whose per-frame `:fx-overrides` route `:rf.http/managed` to
  `stub-fx-id`: the children fire on later ticks, which a per-call override
  does not reach. Create it after the stub fx and machines are registered,
  because a frame seals its image generation at construction."
  [stub-fx-id]
  (rf.frame/make-anon-frame-record!
    {:doc          ":rf.http/managed wrapper children"
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

(deftest spawn-and-spawn-all-parents-receive-the-same-value
  ;; So `:output-key` and the single-:spawn dispatch cannot drift apart.
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
                                        {:timeout-ms 2000 :label "four parents settle"})
            (.then (fn [_]
                     (let [[s-ok v-ok]        (ev :cljs/spawn-ok)
                           [s-bad v-bad]      (ev :cljs/spawn-bad)
                           [j-ok _ jv-ok]     (ev :cljs/join-ok)
                           [j-bad _ jv-bad]   (ev :cljs/join-bad)]
                       (is (= [[:succeeded {:id 42}] [:some-done {:id 42}]] [[s-ok v-ok] [j-ok jv-ok]])
                           "success: the join result is the value the :spawn parent receives")
                       (is (= [:failed :any-failed :rf.http/http-5xx v-bad] [s-bad j-bad (:kind v-bad) jv-bad])
                           "failure: the classified error reaches both, :on-any-failed for the join"))))
            (.catch (fn [e] (is false (str "unexpected — "
                                           (pr-str (into {} (map (fn [[pid f]] [pid (:state (snap-in f pid))])) frames))
                                           " — " (.-message e)))
                      nil))
            (.then (fn [_] (rf.http.test-support/uninstall-managed-request-stubs!) (done)))))))

(deftest join-child-sends-no-succeeded-event-to-its-parent
  ;; The parent also carries the single-:spawn habit `:on {:succeeded … :failed …}`.
  ;; A :final? state's :entry still runs, so without the :rf/join-child gate the
  ;; first child to finish would send [:succeeded value], the parent would leave
  ;; :hydrating on it, and its own join would be torn down.
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
                                        {:timeout-ms 2000 :label "habit parent settles"})
            (.then (fn [_]
                     (is (= :all-done (first (resolved-event f :cljs/habit)))
                         (str "the parent recorded " (pr-str (resolved-event f :cljs/habit))))))
            (.catch (fn [e] (is false (str "unexpected — " (where-is f :cljs/habit) " — " (.-message e))) nil))
            (.then (fn [_] (rf.http.test-support/uninstall-managed-request-stubs!) (done)))))))
