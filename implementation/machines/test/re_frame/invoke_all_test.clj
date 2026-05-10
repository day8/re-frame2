(ns re-frame.invoke-all-test
  "Per rf2-6vmw and Spec 005 §Spawn-and-join via :invoke-all. Verifies
  the first-class `:invoke-all` spawn-and-join surface that gives
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
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.machines :as machines]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init!)
  (require 're-frame.machines :reload)
  (machines/reset-counters!)
  (test-fn))

(use-fixtures :each reset-runtime)

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
;; it via :start [:set-id <id>] from the parent's :invoke-all entry.

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
                   {:invoke-all
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
                   {:invoke-all
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
                   {:invoke-all
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
                   {:invoke-all
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
                   {:invoke-all
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
  (testing ":invoke-all with no :id on a child — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-invoke-all-bad-shape"
          (rf/reg-machine :bad/no-id
                          {:initial :s
                           :states
                           {:s {:invoke-all {:children        [{:machine-id :foo}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":invoke-all with duplicate child ids — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-invoke-all-duplicate-id"
          (rf/reg-machine :bad/dup-id
                          {:initial :s
                           :states
                           {:s {:invoke-all {:children        [{:id :x :machine-id :foo}
                                                                {:id :x :machine-id :bar}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":invoke + :invoke-all on same state — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-invoke-all-with-invoke"
          (rf/reg-machine :bad/both
                          {:initial :s
                           :states
                           {:s {:invoke     {:machine-id :foo}
                                :invoke-all {:children        [{:id :x :machine-id :bar}]
                                             :on-child-done   :done
                                             :on-child-error  :failed
                                             :on-all-complete [:done]}}}}))))
  (testing ":invoke-all with :join :all but no :on-all-complete — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-invoke-all-bad-shape"
          (rf/reg-machine :bad/no-on-all
                          {:initial :s
                           :states
                           {:s {:invoke-all {:children        [{:id :x :machine-id :foo}]
                                             :on-child-done   :done
                                             :on-child-error  :failed}}}}))))
  (testing ":invoke-all with :join {:n 3} but no :on-some-complete — rejected"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"machine-invoke-all-bad-shape"
          (rf/reg-machine :bad/no-on-some
                          {:initial :s
                           :states
                           {:s {:invoke-all {:children       [{:id :x :machine-id :foo}]
                                             :join           {:n 3}
                                             :on-child-done  :done
                                             :on-child-error :failed}}}})))))
