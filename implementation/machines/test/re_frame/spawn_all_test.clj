(ns re-frame.spawn-all-test
  "Per rf2-6vmw and Spec 005 §Spawn-and-join via :spawn-all. Verifies
  the first-class `:spawn-all` spawn-and-join surface that gives
  parallel-region state-machines a single declarative shape.

  The test scenarios cover the four join-condition shapes
  (:all / :any / {:n N} / {:fn pred}) plus cancel-on-decision
  semantics and registration-time validation.

  Children dispatch their completion via [<parent-id> [:on-child-done
  <child-id> & extra]] (or :on-child-error). The runtime intercepts
  these at the parent's create-machine-handler boundary and updates
  the join-state at [:rf/spawned <parent> <invoke-id>] in app-db.
  When the join condition resolves, the runtime fires the parent
  join event (:on-all-complete / :on-some-complete / :on-any-failed)
  and (by default) cancels surviving siblings via :rf.machine/destroy.

  All tests run on the JVM through the plain-atom substrate."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

(defn- frame-db []
  (rf/get-frame-db :rf/default))

(defn- snapshot
  [machine-id]
  (get-in (frame-db) [:rf/machines machine-id]))

;; ---- common child machine -------------------------------------------------
;;
;; Trivial child that, on receiving :go, transitions to :done (which
;; dispatches :on-child-done back to the parent). On :fail, transitions
;; to :failed (which dispatches :on-child-error back). The parent-id
;; the child dispatches into is supplied via :data — we pre-populate
;; it via :start [:set-id <id>] from the parent's :spawn-all entry.

(defn- mk-child
  "Return a child spec that dispatches `done-event-kw` /
  `error-event-kw` back to `parent-id` carrying its own :id from :data."
  [parent-id done-event-kw error-event-kw]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done
             (fn [data _]
               {:fx [[:dispatch [parent-id [done-event-kw (:id data)]]]]})
             :dispatch-error
             (fn [data _]
               {:fx [[:dispatch [parent-id [error-event-kw (:id data)]]]]})
             :record-id
             (fn [data ev]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done   :action :dispatch-done}
                   :fail   {:target :failed :action :dispatch-error}}}
    :done   {}
    :failed {}}})

;; ---- :join :all — all children complete fires :on-all-complete ----------

(deftest join-all-fires-on-all-complete
  (testing "all N children completing fires :on-all-complete"
    (let [child  (mk-child :sup/all :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/a :start [:set-id :a]}
                                        {:id :b :machine-id :child/b :start [:set-id :b]}
                                        {:id :c :machine-id :child/c :start [:set-id :c]}]
                     :join             :all
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready  {}
                   :error  {}}}]
      (rf/reg-machine :child/a child)
      (rf/reg-machine :child/b child)
      (rf/reg-machine :child/c child)
      (rf/reg-machine :sup/all parent)
      (rf/dispatch-sync [:sup/all [:start]])
      (let [db     (frame-db)
            jstate (get-in db [:rf/spawned :sup/all [:hydrating]])]
        (is (map? jstate) "join-state seeded under [:rf/spawned :sup/all [:hydrating]]")
        (is (= #{:a :b :c} (set (keys (:children jstate)))))
        (is (= #{} (:done jstate)))
        (is (= #{} (:failed jstate)))
        (is (false? (:resolved? jstate)))
        (is (some? (get-in db [:rf/machines (get-in jstate [:children :a])])))
        (is (some? (get-in db [:rf/machines (get-in jstate [:children :b])])))
        (is (some? (get-in db [:rf/machines (get-in jstate [:children :c])]))))
      (let [jstate (get-in (frame-db) [:rf/spawned :sup/all [:hydrating]])
            ids    (:children jstate)]
        (rf/dispatch-sync [(:a ids) [:go]])
        (rf/dispatch-sync [(:b ids) [:go]])
        (rf/dispatch-sync [(:c ids) [:go]]))
      (is (= :ready (:state (snapshot :sup/all)))
          "parent transitioned to :ready via :on-all-complete"))))

;; ---- :join :all with one child failing fires :on-any-failed --------------

(deftest join-all-with-one-failure-fires-on-any-failed-and-cancels
  (testing "one child failing fires :on-any-failed and cancels surviving siblings"
    (let [child  (mk-child :sup/fail-test :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/fa :start [:set-id :a]}
                                        {:id :b :machine-id :child/fb :start [:set-id :b]}
                                        {:id :c :machine-id :child/fc :start [:set-id :c]}]
                     :join             :all
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready  {}
                   :error  {}}}]
      (rf/reg-machine :child/fa child)
      (rf/reg-machine :child/fb child)
      (rf/reg-machine :child/fc child)
      (rf/reg-machine :sup/fail-test parent)
      (rf/dispatch-sync [:sup/fail-test [:start]])
      (let [jstate (get-in (frame-db) [:rf/spawned :sup/fail-test [:hydrating]])]
        (is (= 3 (count (:children jstate)))))
      (let [jstate (get-in (frame-db) [:rf/spawned :sup/fail-test [:hydrating]])
            ids    (:children jstate)
            a-id   (:a ids)
            b-id   (:b ids)
            c-id   (:c ids)]
        (rf/dispatch-sync [a-id [:fail]])
        (is (= :error (:state (snapshot :sup/fail-test))))
        (is (nil? (get-in (frame-db) [:rf/machines a-id])))
        (is (nil? (get-in (frame-db) [:rf/machines b-id])))
        (is (nil? (get-in (frame-db) [:rf/machines c-id])))
        (is (nil? (get-in (frame-db) [:rf/spawned :sup/fail-test [:hydrating]])))))))

;; ---- :join {:n N} fires :on-some-complete and cancels extras ------------

(deftest join-n-of-fires-some-complete-and-cancels-extras
  (testing ":join {:n 3} fires :on-some-complete after 3rd :go; remaining 2 cancelled"
    (let [child  (mk-child :sup/n-test :done :failed)
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/na :start [:set-id :a]}
                                        {:id :b :machine-id :child/nb :start [:set-id :b]}
                                        {:id :c :machine-id :child/nc :start [:set-id :c]}
                                        {:id :d :machine-id :child/nd :start [:set-id :d]}
                                        {:id :e :machine-id :child/ne :start [:set-id :e]}]
                     :join             {:n 3}
                     :on-child-done    :done
                     :on-child-error   :failed
                     :on-some-complete [:phase/three-done]}
                    :on    {:phase/three-done :ready}}
                   :ready  {}}}]
      (doseq [k [:child/na :child/nb :child/nc :child/nd :child/ne]]
        (rf/reg-machine k child))
      (rf/reg-machine :sup/n-test parent)
      (rf/dispatch-sync [:sup/n-test [:start]])
      (let [jstate (get-in (frame-db) [:rf/spawned :sup/n-test [:working]])
            ids    (:children jstate)]
        (is (= 5 (count ids)))
        (rf/dispatch-sync [(:a ids) [:go]])
        (rf/dispatch-sync [(:b ids) [:go]])
        (let [j (get-in (frame-db) [:rf/spawned :sup/n-test [:working]])]
          (is (= 2 (count (:done j))))
          (is (false? (:resolved? j))))
        (rf/dispatch-sync [(:c ids) [:go]])
        (is (= :ready (:state (snapshot :sup/n-test))))
        (is (nil? (get-in (frame-db) [:rf/machines (:d ids)])))
        (is (nil? (get-in (frame-db) [:rf/machines (:e ids)])))
        (is (nil? (get-in (frame-db) [:rf/machines (:a ids)])))
        (is (nil? (get-in (frame-db) [:rf/machines (:b ids)])))
        (is (nil? (get-in (frame-db) [:rf/machines (:c ids)])))))))

;; ---- :join :any fires :on-some-complete on first child ------------------

(deftest join-any-fires-on-first-child
  (testing ":join :any fires :on-some-complete after the first :go"
    (let [child  (mk-child :sup/any-test :done :failed)
          parent {:initial :idle
                  :states
                  {:idle   {:on {:start :racing}}
                   :racing
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/aa :start [:set-id :a]}
                                        {:id :b :machine-id :child/ab :start [:set-id :b]}
                                        {:id :c :machine-id :child/ac :start [:set-id :c]}]
                     :join             :any
                     :on-child-done    :done
                     :on-child-error   :failed
                     :on-some-complete [:race/won]}
                    :on    {:race/won :winner}}
                   :winner {}}}]
      (doseq [k [:child/aa :child/ab :child/ac]]
        (rf/reg-machine k child))
      (rf/reg-machine :sup/any-test parent)
      (rf/dispatch-sync [:sup/any-test [:start]])
      (let [jstate (get-in (frame-db) [:rf/spawned :sup/any-test [:racing]])
            ids    (:children jstate)]
        (rf/dispatch-sync [(:b ids) [:go]])
        (is (= :winner (:state (snapshot :sup/any-test))))
        (is (nil? (get-in (frame-db) [:rf/machines (:a ids)])))
        (is (nil? (get-in (frame-db) [:rf/machines (:c ids)])))))))

;; ---- :join {:fn pred} fires when predicate returns truthy ----------------

(deftest join-fn-pred-fires-when-truthy
  (testing ":join {:fn (fn [{:keys [done failed]}] (>= (count done) 2))} fires when 2+ done"
    (let [child  (mk-child :sup/fn-test :done :failed)
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working
                   {:spawn-all
                    {:children         [{:id :a :machine-id :child/fna :start [:set-id :a]}
                                        {:id :b :machine-id :child/fnb :start [:set-id :b]}
                                        {:id :c :machine-id :child/fnc :start [:set-id :c]}]
                     :join             {:fn (fn [{:keys [done]}] (>= (count done) 2))}
                     :on-child-done    :done
                     :on-child-error   :failed
                     :on-some-complete [:phase/two-done]}
                    :on    {:phase/two-done :ready}}
                   :ready {}}}]
      (doseq [k [:child/fna :child/fnb :child/fnc]]
        (rf/reg-machine k child))
      (rf/reg-machine :sup/fn-test parent)
      (rf/dispatch-sync [:sup/fn-test [:start]])
      (let [ids (:children (get-in (frame-db) [:rf/spawned :sup/fn-test [:working]]))]
        (rf/dispatch-sync [(:a ids) [:go]])
        ;; After 1 done, predicate returns false — no resolution.
        (is (= :working (:state (snapshot :sup/fn-test))))
        (rf/dispatch-sync [(:b ids) [:go]])
        ;; After 2 done, predicate returns true — fires :on-some-complete.
        (is (= :ready (:state (snapshot :sup/fn-test))))
        ;; :c was cancelled.
        (is (nil? (get-in (frame-db) [:rf/machines (:c ids)])))))))

;; ---- registration-time validation ----------------------------------------

(deftest registration-time-rejects-bad-shape
  (testing ":spawn-all with no :id on a child — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/no-id
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children        [{:machine-id :foo}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":spawn-all with duplicate child ids — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-duplicate-id"
          (rf/reg-machine :bad/dup-id
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children        [{:id :x :machine-id :foo}
                                                                {:id :x :machine-id :bar}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":spawn + :spawn-all on same state — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-with-spawn"
          (rf/reg-machine :bad/both
                          {:initial :s
                           :states
                           {:s {:spawn     {:machine-id :foo}
                                :spawn-all {:children        [{:id :x :machine-id :bar}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":spawn-all with :join :all but no :on-all-complete — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/no-on-all
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children        [{:id :x :machine-id :foo}]
                                             :on-child-done   :done
                                             :on-child-error  :failed}}}}))))
  (testing ":spawn-all with :join {:n 3} but no :on-some-complete — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-spawn-all-bad-shape"
          (rf/reg-machine :bad/no-on-some
                          {:initial :s
                           :states
                           {:s {:spawn-all {:children       [{:id :x :machine-id :foo}]
                                             :join           {:n 3}
                                             :on-child-done  :done
                                             :on-child-error :failed}}}})))))

;; ---- decisive-child payload forwarding (rf2-4aop8) -----------------------

(defn- mk-child-with-payload
  "Like mk-child but the terminal-state dispatch carries an extra payload
  argument so we can assert it forwards through the join resolution."
  [parent-id done-event-kw error-event-kw payload]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done
             (fn [data _]
               {:fx [[:dispatch [parent-id [done-event-kw (:id data) payload]]]]})
             :dispatch-error
             (fn [data _]
               {:fx [[:dispatch [parent-id [error-event-kw (:id data) payload]]]]})
             :record-id
             (fn [data ev]
               {:data (assoc data :id (second ev))})
             :record-join-event
             (fn [data ev]
               {:data (assoc data :join-event ev)})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done   :action :dispatch-done}
                   :fail   {:target :failed :action :dispatch-error}}}
    :done   {}
    :failed {}}})

(deftest decisive-child-payload-forwards-into-join-event
  (testing "the decisive child's & extra is appended onto the resolution event"
    (let [child  (mk-child-with-payload :sup/pay :asset/loaded :asset/failed
                                        {:bytes 4242 :status :ok})
          parent {:initial :idle
                  :actions {:record-payload
                            (fn [data ev]
                              {:data (assoc data :join-event ev)})}
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children        [{:id :a :machine-id :child/pa :start [:set-id :a]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]}
                    :on    {:hydrate/done {:target :ready :action :record-payload}}}
                   :ready {}}}]
      (rf/reg-machine :child/pa child)
      (rf/reg-machine :sup/pay parent)
      (rf/dispatch-sync [:sup/pay [:start]])
      (let [ids (get-in (frame-db) [:rf/spawned :sup/pay [:hydrating] :children])]
        (rf/dispatch-sync [(:a ids) [:go]]))
      (let [snap (snapshot :sup/pay)]
        (is (= :ready (:state snap)) "parent reached :ready")
        ;; The decisive-child payload (:a + {:bytes 4242 :status :ok})
        ;; appears as trailing args on the dispatched join event:
        ;;   [:hydrate/done :a {:bytes 4242 :status :ok}]
        (is (= [:hydrate/done :a {:bytes 4242 :status :ok}]
               (:join-event (:data snap)))
            "resolution event carries decisive child-id and forwarded payload")))))

;; ---- late-completion safety net (rf2-1tt9q) -----------------------------
;;
;; intercept-invoke-all-event has a post-resolution branch (join.cljc:134-141)
;; that fires when a child-done event arrives AFTER the join already
;; resolved. The contract surface is the :rf.machine.spawn-all/late-completion
;; trace — the return-value is a no-op {:db db :fx []}.
;;
;; To exercise the branch we use :cancel-on-decision? false so siblings
;; survive past resolution and can fire their terminal-state dispatches.

(deftest late-completion-after-resolution-emits-trace-and-is-no-op
  (testing "child-done events arriving after join resolution emit late-completion trace"
    (let [traces (atom [])
          child  (mk-child :sup/late :asset/loaded :asset/failed)
          ;; Parent stays in :hydrating across resolution by NOT
          ;; declaring an :on for the resolution event. The runtime
          ;; still dispatches it, but no transition fires — the join
          ;; slot survives so late children flow through the
          ;; :resolved? branch.
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children            [{:id :a :machine-id :child/la :start [:set-id :a]}
                                           {:id :b :machine-id :child/lb :start [:set-id :b]}
                                           {:id :c :machine-id :child/lc :start [:set-id :c]}]
                     :join                :any
                     :cancel-on-decision? false
                     :on-child-done       :asset/loaded
                     :on-child-error      :asset/failed
                     :on-some-complete    [:race/won]}}}}]
      (rf/reg-machine :child/la child)
      (rf/reg-machine :child/lb child)
      (rf/reg-machine :child/lc child)
      (rf/reg-machine :sup/late parent)
      (rf/register-trace-listener! ::late-cb
                             (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:sup/late [:start]])
        (let [ids (get-in (frame-db) [:rf/spawned :sup/late [:hydrating] :children])]
          ;; First child resolves the :any join.
          (rf/dispatch-sync [(:a ids) [:go]])
          (let [j (get-in (frame-db) [:rf/spawned :sup/late [:hydrating]])]
            (is (true? (:resolved? j)) ":any resolved on first :go")
            (is (= #{:a} (:done j))))
          ;; Sibling b completes AFTER resolution — late-completion path.
          (rf/dispatch-sync [(:b ids) [:go]])
          (let [j (get-in (frame-db) [:rf/spawned :sup/late [:hydrating]])]
            (is (= #{:a} (:done j))
                "late-completion does NOT mutate :done")
            (is (true? (:resolved? j)) ":resolved? stays true")))
        (let [late-traces (->> @traces
                               (filter #(= :rf.machine.spawn-all/late-completion
                                           (:operation %))))]
          (is (= 1 (count late-traces))
              "exactly one late-completion trace fired")
          (is (= :b (:child-id (:tags (first late-traces))))
              "trace carries the late child's id")
          (is (= :done (:kind (:tags (first late-traces))))
              "trace carries the resolution kind"))
        (finally
          (rf/unregister-trace-listener! ::late-cb))))))

;; ---- forged child-id rejection (rf2-ns8ut) ------------------------------
;;
;; intercept-invoke-all-event must verify the inbound `child-id` is one
;; of the parent's spawned children before mutating the join state.
;; Otherwise a hand-crafted dispatch (typo, copy-paste from a sibling
;; :spawn-all, cascaded event from another parent) silently folds an
;; unknown id into `:done` / `:failed` and can collapse the join early
;; (silent state-machine corruption). The runtime gates this with
;; `:rf.error/machine-spawn-all-bad-child-id` and a no-op fx — the
;; join state is NOT mutated and the join does not resolve on the
;; forged event.
;;
;; Pragmatic security stance: trust the explicit invoker but gate
;; accidents. See ai/findings/machines-security-audit-2026-05-15.md F1.

(defn- mk-inert-child
  "A child that records its parent-id but never auto-dispatches back —
  letting us hand-craft forged `[parent [:on-child-done <bogus>]]`
  events into the parent without race with real child completion."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id
             (fn [data ev]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}}}}})

(defn- collect-traces
  "Run `body-fn` with a trace listener attached; return the collected
  vector of trace envelopes."
  [body-fn]
  (let [traces (atom [])
        cb-key (gensym ::forged-cb)]
    (rf/register-trace-listener! cb-key (fn [ev] (swap! traces conj ev)))
    (try
      (body-fn)
      (finally
        (rf/unregister-trace-listener! cb-key)))
    @traces))

(defn- bad-child-id-error-traces
  [traces]
  (->> traces
       (filter #(= :rf.error/machine-spawn-all-bad-child-id
                   (:operation %)))))

(deftest forged-child-id-with-no-live-children-is-rejected
  (testing "an :on-child-done dispatch carrying a child-id NOT in the
  parent's spawned set emits :rf.error/machine-spawn-all-bad-child-id
  and is a no-op (join state untouched, no resolution)"
    (let [parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children        [{:id :a :machine-id :child/forge-a :start [:set-id :a]}
                                       {:id :b :machine-id :child/forge-b :start [:set-id :b]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]
                     :on-any-failed   [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready  {}
                   :error  {}}}]
      (rf/reg-machine :child/forge-a (mk-inert-child))
      (rf/reg-machine :child/forge-b (mk-inert-child))
      (rf/reg-machine :sup/forge-none parent)
      (rf/dispatch-sync [:sup/forge-none [:start]])
      (let [pre-jstate (get-in (frame-db) [:rf/spawned :sup/forge-none [:hydrating]])
            children   (:children pre-jstate)
            _          (is (= #{:a :b} (set (keys children))))
            traces (collect-traces
                    (fn []
                      (rf/dispatch-sync
                        [:sup/forge-none [:asset/loaded :totally-fake-id]])))
            post-jstate (get-in (frame-db) [:rf/spawned :sup/forge-none [:hydrating]])
            errs (bad-child-id-error-traces traces)]
        (is (= 1 (count errs))
            "exactly one :rf.error/machine-spawn-all-bad-child-id trace fired")
        (let [err  (first errs)
              tags (:tags err)]
          (is (= :sup/forge-none (:machine-id tags)))
          (is (= [:hydrating] (:spawn-id tags)))
          (is (= :totally-fake-id (:child-id tags)))
          (is (= #{:a :b} (:children tags))
              ":children carries the legitimate seed set")
          (is (= :done (:kind tags))
              ":kind carries the resolution-side the forged event aimed at")
          (is (= :event-dropped (:recovery err))
              ":recovery is hoisted to top-level on error envelopes"))
        (is (= pre-jstate post-jstate)
            "join state unchanged: forged id NOT added to :done or :failed")
        (is (= :hydrating (:state (snapshot :sup/forge-none)))
            "parent did NOT resolve on the forged event")))))

(deftest repeated-forged-completions-each-emit-error-and-do-not-resolve
  (testing "repeated forged :on-child-done dispatches each emit error
  traces; the join still does not resolve"
    (let [parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children        [{:id :a :machine-id :child/forge-r :start [:set-id :a]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]
                     :on-any-failed   [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready  {}
                   :error  {}}}]
      (rf/reg-machine :child/forge-r (mk-inert-child))
      (rf/reg-machine :sup/forge-repeated parent)
      (rf/dispatch-sync [:sup/forge-repeated [:start]])
      (let [traces (collect-traces
                    (fn []
                      (rf/dispatch-sync [:sup/forge-repeated [:asset/loaded :fake-1]])
                      (rf/dispatch-sync [:sup/forge-repeated [:asset/loaded :fake-2]])
                      (rf/dispatch-sync [:sup/forge-repeated [:asset/failed :fake-3]])))
            errs (bad-child-id-error-traces traces)
            jstate (get-in (frame-db) [:rf/spawned :sup/forge-repeated [:hydrating]])]
        (is (= 3 (count errs))
            "one error trace per forged dispatch")
        (is (= [:fake-1 :fake-2 :fake-3]
               (mapv (comp :child-id :tags) errs))
            "each error trace carries its specific forged child-id")
        (is (= [:done :done :failed]
               (mapv (comp :kind :tags) errs))
            "each error trace carries the resolution-side it aimed at")
        (is (= #{} (:done jstate)))
        (is (= #{} (:failed jstate)))
        (is (false? (:resolved? jstate)))
        (is (= :hydrating (:state (snapshot :sup/forge-repeated))))))))

(deftest sibling-invoke-all-child-id-is-not-counted-into-other-join
  (testing "a child-id legitimate to one parent's :spawn-all is forged
  for ANOTHER parent's :spawn-all and is rejected by the other"
    (let [parent-1 {:initial :idle
                    :states
                    {:idle      {:on {:start :working}}
                     :working
                     {:spawn-all
                      {:children        [{:id :p1-a :machine-id :child/p1a :start [:set-id :p1-a]}]
                       :join            :all
                       :on-child-done   :asset/loaded
                       :on-child-error  :asset/failed
                       :on-all-complete [:done]}}}}
          parent-2 {:initial :idle
                    :states
                    {:idle      {:on {:start :working}}
                     :working
                     {:spawn-all
                      {:children        [{:id :p2-x :machine-id :child/p2x :start [:set-id :p2-x]}]
                       :join            :all
                       :on-child-done   :asset/loaded
                       :on-child-error  :asset/failed
                       :on-all-complete [:done]}}}}]
      (rf/reg-machine :child/p1a (mk-inert-child))
      (rf/reg-machine :child/p2x (mk-inert-child))
      (rf/reg-machine :sup/p1 parent-1)
      (rf/reg-machine :sup/p2 parent-2)
      (rf/dispatch-sync [:sup/p1 [:start]])
      (rf/dispatch-sync [:sup/p2 [:start]])
      ;; Dispatch p2's child-id (:p2-x) into p1's join — it is foreign
      ;; to p1's seeded :children {:p1-a ...} and must be rejected.
      (let [traces (collect-traces
                    (fn []
                      (rf/dispatch-sync [:sup/p1 [:asset/loaded :p2-x]])))
            errs (bad-child-id-error-traces traces)
            p1-j (get-in (frame-db) [:rf/spawned :sup/p1 [:working]])]
        (is (= 1 (count errs))
            "sibling parent's child-id is forged-for-this-parent and rejected")
        (is (= :p2-x (:child-id (:tags (first errs)))))
        (is (= #{:p1-a} (:children (:tags (first errs))))
            ":children reflects p1's seed, NOT p2's")
        (is (= #{} (:done p1-j))
            "p1's join state untouched by the foreign id")
        (is (false? (:resolved? p1-j)))))))

(deftest legitimate-child-id-flow-not-regressed-by-the-gate
  (testing "the legitimate child-id path still resolves and the gate
  does not emit a bad-child-id error for real children"
    (let [child  (mk-child :sup/gate-ok :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children        [{:id :a :machine-id :child/gate-a :start [:set-id :a]}
                                       {:id :b :machine-id :child/gate-b :start [:set-id :b]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]}
                    :on    {:hydrate/done :ready}}
                   :ready {}}}]
      (rf/reg-machine :child/gate-a child)
      (rf/reg-machine :child/gate-b child)
      (rf/reg-machine :sup/gate-ok parent)
      (let [traces (collect-traces
                    (fn []
                      (rf/dispatch-sync [:sup/gate-ok [:start]])
                      (let [ids (:children (get-in (frame-db)
                                                   [:rf/spawned :sup/gate-ok
                                                    [:hydrating]]))]
                        (rf/dispatch-sync [(:a ids) [:go]])
                        (rf/dispatch-sync [(:b ids) [:go]]))))
            errs (bad-child-id-error-traces traces)]
        (is (= [] (vec errs))
            "no :rf.error/machine-spawn-all-bad-child-id for legitimate flow")
        (is (= :ready (:state (snapshot :sup/gate-ok)))
            "legitimate :spawn-all resolution still fires :on-all-complete")))))

(deftest any-failed-trace-carries-reason-payload
  (testing ":rf.machine.spawn-all/any-failed trace carries :reason from decisive failure"
    (let [traces (atom [])
          child  (mk-child-with-payload :sup/fail-payload
                                        :asset/loaded :asset/failed
                                        {:reason :boom :http-status 503})
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children       [{:id :a :machine-id :child/fpa :start [:set-id :a]}
                                      {:id :b :machine-id :child/fpb :start [:set-id :b]}]
                     :join            :all
                     :on-child-done   :asset/loaded
                     :on-child-error  :asset/failed
                     :on-all-complete [:hydrate/done]
                     :on-any-failed   [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready {} :error {}}}]
      (rf/reg-machine :child/fpa child)
      (rf/reg-machine :child/fpb child)
      (rf/reg-machine :sup/fail-payload parent)
      (rf/register-trace-listener! ::any-failed-trace
                             (fn [ev] (swap! traces conj ev)))
      (try
        (rf/dispatch-sync [:sup/fail-payload [:start]])
        (let [ids (get-in (frame-db) [:rf/spawned :sup/fail-payload [:hydrating] :children])]
          (rf/dispatch-sync [(:a ids) [:fail]]))
        (let [any-failed (->> @traces
                              (filter #(= :rf.machine.spawn-all/any-failed
                                          (:operation %)))
                              first)]
          (is (some? any-failed) ":rf.machine.spawn-all/any-failed trace fired")
          (is (= [{:reason :boom :http-status 503}]
                 (:reason (:tags any-failed)))
              ":reason key on trace carries decisive child's forwarded payload"))
        (finally
          (rf/unregister-trace-listener! ::any-failed-trace))))))
