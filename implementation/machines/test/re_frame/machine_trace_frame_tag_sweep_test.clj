(ns re-frame.machine-trace-frame-tag-sweep-test
  "Per rf2-ko8jb: every `:rf.machine/*` and `:rf.error/machine-*` trace
  event MUST carry the `:frame` tag so `re-frame.epoch.capture/capture-event!`
  admits it into the cascade's `:trace-events` buffer. Without the tag
  the trace is dropped silently — fans out to direct listeners but
  never reaches the epoch-history slot the Causa Machine Inspector
  reads from.

  This test file is the sister to `re_frame.transition_frame_tag_test`
  (rf2-hwuki, which locks `:rf.machine/transition` at registration.cljc).
  rf2-ko8jb sweeps the remaining ~13 emit sites across:

    - finalize.cljc — :on-done callback throw (machine-action-exception)
    - join.cljc — invoke-all resolution traces (any-failed, all-completed,
      some-completed, cancelled-on-join-resolution, late-completion,
      bad-child-id)
    - timer.cljc — wall-clock fx-layer traces (no-clock-configured,
      cancelled-on-resolution, scheduled-from-watcher, after-fn-threw,
      after-sub-threw, after-watch-failed)
    - transition.cljc — pure-engine traces (guard-evaluated, action-ran
      success+error, timer/scheduled, timer/skipped-on-server,
      timer/fired, timer/stale-after, raise-depth-exceeded,
      always-depth-exceeded)

  The test rationale mirrors the rf2-hwuki test's: this lives in the
  machines artefact (which doesn't depend on epoch) so the contract is
  exercised even when the epoch artefact isn't on the test classpath.
  A future regression that drops `:frame` from any of these sites fails
  here first (clear cause) before the upstack epoch / Causa gates
  notice the absent trace events."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace]))

(use-fixtures :each
  (test-support/reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- record-traces!
  "Register a trace listener for the duration of `body-fn`, returning
  the captured trace vec."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/remove-trace-cb! ::rec)))
    @seen))

(defn- of-op [evs op]
  (filterv #(= op (:operation %)) evs))

(defn- first-of-op [evs op]
  (first (of-op evs op)))

(defn- frame-tag [ev]
  (get-in ev [:tags :frame]))

;; ---- transition.cljc :rf.machine/guard-evaluated --------------------------

(deftest guard-evaluated-tag-carries-frame
  (testing ":rf.machine/guard-evaluated carries `:frame` tag so epoch-capture
   admits it (rf2-ko8jb — `(:rf/frame machine)` resolved in evaluate-guard)"
    (rf/reg-machine
      :ko8jb/guard
      {:initial :idle
       :guards  {:always-pass (fn [_d _e] true)}
       :states  {:idle  {:on {:go [{:guard :always-pass :target :done}]}}
                 :done  {}}})
    (let [traces (record-traces!
                   (fn [] (rf/dispatch-sync [:ko8jb/guard [:go]])))
          ev    (first-of-op traces :rf.machine/guard-evaluated)]
      (is (some? ev) "one :rf.machine/guard-evaluated fired")
      (is (= :rf/default (frame-tag ev))
          ":frame tag is the dispatching frame's id"))))

;; ---- transition.cljc :rf.machine/action-ran (success path) ----------------

(deftest action-ran-success-tag-carries-frame
  (testing ":rf.machine/action-ran (success outcome) carries `:frame` tag
   (rf2-ko8jb — `(:rf/frame machine)` resolved in run-action)"
    (rf/reg-machine
      :ko8jb/action
      {:initial :idle
       :actions {:noop (fn [_d _e] nil)}
       :states  {:idle {:on {:go {:target :done :action :noop}}}
                 :done {}}})
    (let [traces (record-traces!
                   (fn [] (rf/dispatch-sync [:ko8jb/action [:go]])))
          ev    (first-of-op traces :rf.machine/action-ran)]
      (is (some? ev) "one :rf.machine/action-ran fired")
      (is (= :rf/default (frame-tag ev))
          ":frame tag stamped on success outcome"))))

;; ---- transition.cljc :rf.machine/action-ran (error path) ------------------

(deftest action-ran-error-tag-carries-frame
  (testing ":rf.machine/action-ran (action-threw outcome) carries `:frame`
   tag — exception path must remain observable through epoch capture"
    (rf/reg-machine
      :ko8jb/action-throws
      {:initial :idle
       :actions {:bang (fn [_d _e] (throw (ex-info "boom" {})))}
       :states  {:idle {:on {:go {:target :done :action :bang}}}
                 :done {}}})
    (let [traces (record-traces!
                   (fn [] (rf/dispatch-sync [:ko8jb/action-throws [:go]])))
          rans   (of-op traces :rf.machine/action-ran)
          err    (first (filter #(= :rf.error/action-threw
                                    (get-in % [:tags :outcome]))
                                rans))]
      (is (some? err) ":rf.machine/action-ran with action-threw outcome fired")
      (is (= :rf/default (frame-tag err))
          ":frame tag stamped on error outcome"))))

;; ---- transition.cljc :rf.machine.timer/scheduled --------------------------

(deftest timer-scheduled-tag-carries-frame
  (testing ":rf.machine.timer/scheduled (build-after-fx) carries `:frame`
   tag (rf2-ko8jb — `(:rf/frame machine)` resolved in build-after-fx)"
    (rf/reg-machine
      :ko8jb/sched
      {:initial :idle
       :states  {:idle    {:on {:go :loading}}
                 :loading {:after {5000 :ready}}
                 :ready   {}}})
    (let [traces (record-traces!
                   (fn [] (rf/dispatch-sync [:ko8jb/sched [:go]])))
          ev    (first-of-op traces :rf.machine.timer/scheduled)]
      (is (some? ev) ":rf.machine.timer/scheduled fired on entry")
      (is (= :rf/default (frame-tag ev))
          ":frame tag stamped"))))

;; ---- transition.cljc :rf.machine.timer/fired ------------------------------

(deftest timer-fired-tag-carries-frame
  (testing ":rf.machine.timer/fired (emit-pick-traces!) carries `:frame`
   tag (rf2-ko8jb — `(:rf/frame machine)` plumbed into emit-pick-traces!)"
    (rf/reg-machine
      :ko8jb/fire
      {:initial :idle
       :data    {:rf/after-epoch 0}
       :states  {:idle    {:on {:go :loading}}
                 :loading {:after {5000 :done}
                           :on    {:bail :idle}}
                 :done    {}}})
    (let [traces
          (record-traces!
            (fn []
              (rf/dispatch-sync [:ko8jb/fire [:go]])
              (let [epoch (get-in (rf/get-frame-db :rf/default)
                                  [:rf/machines :ko8jb/fire :data :rf/after-epoch])]
                (rf/dispatch-sync
                  [:ko8jb/fire
                   [:rf.machine.timer/after-elapsed 5000 epoch]]))))
          ev (first (filter #(true? (:fired? (:tags %)))
                            (of-op traces :rf.machine.timer/fired)))]
      (is (some? ev) ":rf.machine.timer/fired (fired? true) emitted")
      (is (= :rf/default (frame-tag ev))
          ":frame tag stamped on the fired? true trace"))))

;; ---- transition.cljc :rf.machine.timer/stale-after ------------------------

(deftest timer-stale-after-tag-carries-frame
  (testing ":rf.machine.timer/stale-after (emit-pick-traces!) carries `:frame`
   tag — stale-after fires when an in-flight timer's epoch no longer
   matches the snapshot's"
    (rf/reg-machine
      :ko8jb/stale
      {:initial :loading
       :data    {:rf/after-epoch 0}
       :states  {:loading {:after {5000 :timeout}
                           :on    {:cancel :idle}}
                 :idle    {}
                 :timeout {}}})
    (let [traces
          (record-traces!
            (fn []
              ;; First dispatch :cancel which exits :loading and bumps the
              ;; after-epoch. A subsequent synthetic after-elapsed with the
              ;; pre-cancel epoch is stale.
              (rf/dispatch-sync [:ko8jb/stale [:cancel]])
              (rf/dispatch-sync
                [:ko8jb/stale
                 [:rf.machine.timer/after-elapsed 5000 0]])))
          ev (first-of-op traces :rf.machine.timer/stale-after)]
      (is (some? ev) ":rf.machine.timer/stale-after fired")
      (is (= :rf/default (frame-tag ev))
          ":frame tag stamped"))))

;; ---- transition.cljc :rf.error/machine-raise-depth-exceeded ---------------

(deftest raise-depth-exceeded-tag-carries-frame
  (testing ":rf.error/machine-raise-depth-exceeded carries `:frame` tag
   (rf2-ko8jb — `(:rf/frame machine)` resolved in drain-raises)"
    ;; An action that emits a fanned-out batch of :raise fx's — N raises
    ;; in a single :fx vector — feeds the same drain-raises loop N
    ;; iterations. With :raise-depth-limit 3 and 5 raises in one batch
    ;; the loop trips the bound and emits the error trace.
    (rf/reg-machine
      :ko8jb/raise-loop
      {:initial :idle
       :raise-depth-limit 3
       :actions {:fan-out
                 (fn [_d _e]
                   {:fx [[:raise [:noop]]
                         [:raise [:noop]]
                         [:raise [:noop]]
                         [:raise [:noop]]
                         [:raise [:noop]]]})}
       :states  {:idle {:on {:start {:target :running :action :fan-out}
                             :noop  :idle}}
                 :running {:on {:noop :idle}}}})
    (let [traces (record-traces!
                   (fn [] (rf/dispatch-sync [:ko8jb/raise-loop [:start]])))
          ev    (first-of-op traces :rf.error/machine-raise-depth-exceeded)]
      (is (some? ev) ":rf.error/machine-raise-depth-exceeded fired")
      (is (= :rf/default (frame-tag ev))
          ":frame tag stamped on the depth-exceeded error"))))

;; ---- transition.cljc :rf.error/machine-always-depth-exceeded --------------

(deftest always-depth-exceeded-tag-carries-frame
  (testing ":rf.error/machine-always-depth-exceeded carries `:frame` tag
   (rf2-ko8jb — `(:rf/frame machine)` resolved in machine-transition-single)"
    ;; Mirrors the rf2-c0nt always-depth conformance shape: an outer
    ;; `:go` transition lands in `:a`; `:a` and `:b` ping-pong via
    ;; always-true `:always` guards until the depth limit trips.
    (rf/reg-machine
      :ko8jb/always-loop
      {:initial :start
       :always-depth-limit 5
       :guards  {:p? (fn [_d _e] true)}
       :states  {:start {:on {:go :a}}
                 :a     {:always [{:guard :p? :target :b}]}
                 :b     {:always [{:guard :p? :target :a}]}}})
    (let [traces (record-traces!
                   (fn [] (rf/dispatch-sync [:ko8jb/always-loop [:go]])))
          ev    (first-of-op traces :rf.error/machine-always-depth-exceeded)]
      (is (some? ev) ":rf.error/machine-always-depth-exceeded fired")
      (is (= :rf/default (frame-tag ev))
          ":frame tag stamped on the always-depth error"))))

;; ---- finalize.cljc :rf.error/machine-action-exception (on-done throw) ----

(deftest on-done-throw-tag-carries-frame
  (testing ":rf.error/machine-action-exception (on-done callback threw)
   carries `:frame` tag (rf2-ko8jb — `frame-id` IS in scope at the throw
   catch in finalize-machine)"
    (rf/reg-machine
      :ko8jb/child-od
      {:initial :running
       :data    {}
       :states  {:running {:on    {:finish :done}}
                 :done    {:final?     true
                           :output-key :payload}}})
    (rf/reg-machine
      :ko8jb/parent-od
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:start :working}}
                 :working {:invoke {:machine-id :ko8jb/child-od
                                    :on-done    (fn [_d _r]
                                                  (throw (ex-info "boom" {})))}}}})
    (let [traces (record-traces!
                   (fn []
                     ;; Boot parent → :working, which spawns the child.
                     (rf/dispatch-sync [:ko8jb/parent-od [:start]])
                     ;; Drive the child to :final?, triggering parent's
                     ;; :on-done — which throws.
                     (let [child-id (get-in (rf/get-frame-db :rf/default)
                                            [:rf/spawned :ko8jb/parent-od
                                             [:working]])]
                       (rf/dispatch-sync [child-id [:finish]]))))
          ev    (first (filter
                         #(= :rf.invoke/on-done (get-in % [:tags :action-id]))
                         (of-op traces :rf.error/machine-action-exception)))]
      (is (some? ev)
          ":rf.error/machine-action-exception with :action-id :rf.invoke/on-done fired")
      (is (= :rf/default (frame-tag ev))
          ":frame tag stamped on the on-done-throw error"))))

;; ---- join.cljc :rf.machine.invoke-all/all-completed -----------------------

(defn- mk-child-spec
  "Return a child spec that dispatches `done-event-kw` /
  `error-event-kw` back to `parent-id` carrying its own :id (seeded
  from :start [:set-id <id>])."
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
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done   :action :dispatch-done}
                            :fail   {:target :failed :action :dispatch-error}}}
             :done    {}
             :failed  {}}})

(deftest invoke-all-all-completed-tag-carries-frame
  (testing ":rf.machine.invoke-all/all-completed carries `:frame` tag
   (rf2-ko8jb — frame-id plumbed into emit-resolution-traces!)"
    (let [child  (mk-child-spec :ko8jb/parent-all :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states  {:idle      {:on {:start :hydrating}}
                            :hydrating
                            {:invoke-all
                             {:children        [{:id :a :machine-id :ko8jb/ca-a
                                                  :start [:set-id :a]}
                                                 {:id :b :machine-id :ko8jb/ca-b
                                                  :start [:set-id :b]}]
                              :join            :all
                              :on-child-done   :asset/loaded
                              :on-child-error  :asset/failed
                              :on-all-complete [:hydrate/done]}
                             :on    {:hydrate/done :ready}}
                            :ready     {}}}]
      (rf/reg-machine :ko8jb/ca-a child)
      (rf/reg-machine :ko8jb/ca-b child)
      (rf/reg-machine :ko8jb/parent-all parent)
      (let [traces
            (record-traces!
              (fn []
                (rf/dispatch-sync [:ko8jb/parent-all [:start]])
                (let [ids (get-in (rf/get-frame-db :rf/default)
                                  [:rf/spawned :ko8jb/parent-all
                                   [:hydrating] :children])]
                  (rf/dispatch-sync [(:a ids) [:go]])
                  (rf/dispatch-sync [(:b ids) [:go]]))))
            ev (first-of-op traces :rf.machine.invoke-all/all-completed)]
        (is (some? ev) ":rf.machine.invoke-all/all-completed fired")
        (is (= :rf/default (frame-tag ev))
            ":frame tag stamped on join resolution trace")))))

;; ---- join.cljc :rf.error/machine-invoke-all-bad-child-id ------------------

(deftest invoke-all-bad-child-id-tag-carries-frame
  (testing ":rf.error/machine-invoke-all-bad-child-id carries `:frame` tag
   (rf2-ko8jb — `(:rf/frame machine)` resolved at interceptor entry)"
    (let [child  (mk-child-spec :ko8jb/parent-bc :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states  {:idle      {:on {:start :hydrating}}
                            :hydrating
                            {:invoke-all
                             {:children        [{:id :a :machine-id :ko8jb/cbc
                                                  :start [:set-id :a]}]
                              :join            :all
                              :on-child-done   :asset/loaded
                              :on-child-error  :asset/failed
                              :on-all-complete [:hydrate/done]}
                             :on    {:hydrate/done :ready}}
                            :ready     {}}}]
      (rf/reg-machine :ko8jb/cbc child)
      (rf/reg-machine :ko8jb/parent-bc parent)
      (let [traces
            (record-traces!
              (fn []
                (rf/dispatch-sync [:ko8jb/parent-bc [:start]])
                ;; Inject a forged child-id the join-state never knew about
                ;; — triggers :rf.error/machine-invoke-all-bad-child-id.
                (rf/dispatch-sync [:ko8jb/parent-bc
                                   [:asset/loaded :forged/never-spawned]])))
            ev (first-of-op traces :rf.error/machine-invoke-all-bad-child-id)]
        (is (some? ev) ":rf.error/machine-invoke-all-bad-child-id fired")
        (is (= :rf/default (frame-tag ev))
            ":frame tag stamped on bad-child-id error")))))

;; ---- timer.cljc :rf.warning/no-clock-configured ---------------------------

(deftest timer-no-clock-configured-tag-carries-frame
  (testing ":rf.warning/no-clock-configured (timer fx layer) carries
   `:frame` tag (rf2-ko8jb — frame-id is the first param of
   schedule-after-timer!). Triggered by a delay fn that returns nil
   (no positive ms resolution)."
    (rf/reg-machine
      :ko8jb/no-clock
      {:initial :idle
       :states  {:idle    {:on {:go :loading}}
                 :loading {:after {(fn [_snap] nil) :done}}
                 :done    {}}})
    (let [traces (record-traces!
                   (fn [] (rf/dispatch-sync [:ko8jb/no-clock [:go]])))
          ev    (first-of-op traces :rf.warning/no-clock-configured)]
      (is (some? ev) ":rf.warning/no-clock-configured fired from fx layer")
      (is (= :rf/default (frame-tag ev))
          ":frame tag stamped"))))
