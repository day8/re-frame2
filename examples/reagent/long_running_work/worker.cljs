(ns long-running-work.worker
  "The worker + parent-coordinator machines for the long-running-work
   example.

   Pattern-LongRunningWork's `:spawn-all`-shape: the parent coordinator
   spawns N children via :spawn-all; each child processes its own shard
   cooperatively (yielding to the browser between chunks via `:after`);
   children dispatch `:progress` events back to the parent for UI; on
   completion each child dispatches the parent's `:on-child-done`
   keyword. When all N report done the runtime fires the parent's
   `:on-all-complete`. The user can `:cancel` mid-flight; the parent
   transitions out of `:working` and the standard exit cascade tears
   down every surviving child via `:rf.machine/destroy` fx — including
   any in-flight `:after` timers a worker had pending.

   This is the canonical re-frame2 expression of:

     - 'Run N parallel tasks and join on all done.'
     - 'Show progress.'
     - 'Cancellation must be reliable on EVERY exit path —
        including the user navigating away mid-flight.'

   Why one parent + N children rather than one big chunked machine: each
   shard is independent, parallelisable, and lets the demo show *both*
   join semantics (`:on-all-complete`) AND the cascade-teardown contract
   (`:cancel-on-decision?` defaults to true; `:work/flow` `:cancel`
   transition exits the state and the desugared :exit fires the
   per-child destroy)."
  (:require [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine
            ;; (called below at ns-load) and the `:rf/machine` /
            ;; `:rf/machine-has-tag?` framework subs resolve.
            [re-frame.machines]
            [long-running-work.schema]))

;; ============================================================================
;; CONSTANTS
;; ============================================================================

(def items-per-shard
  "How many mock items each child processes per shard. Three shards
   running in parallel at 50ms/item produces a ~5-second job that's
   long enough to demonstrate progress visibly and short enough to
   keep the Playwright smoke fast."
  100)

(def default-tick-ms
  "Wall-clock delay between chunks for the live UI. The child's
   `:yielding` state declares `:after {tick-ms :processing}` so the
   browser gets a render tick between chunks. Lifted to a named const
   so headless tests can override it via the child's `:data :tick-ms`
   (set from the parent's per-child invoke-spec :data)."
  50)

(def parent-id
  "The parent coordinator machine's registered id. Children hard-code
   this in their dispatch-back so they can address the parent without
   any per-child plumbing. Per Spec 005 §Child completion protocol:
   for static `:spawn-all` declarations the parent-id is a literal
   in the parent's source, so the child simply hard-codes it."
  :work/flow)

;; ============================================================================
;; THE CHILD MACHINE — :work/processor
;; ============================================================================
;;
;; Each child processes its own shard in chunks. One item per
;; `:processing` entry; one browser-tick yield between chunks via
;; `:yielding` + `:after`; per-step progress dispatched back to the
;; parent so the UI's progress bar updates between chunks.
;;
;; The state graph:
;;
;;     :idle           — initial; on spawn the runtime dispatches
;;                       [:rf.machine/spawned], which transitions
;;                       us to :processing.
;;     :processing     — entry processes one item, bumps :processed,
;;                       dispatches :progress to the parent. The :always
;;                       cascade routes to :checking-done.
;;     :checking-done  — eventless decision: done? → :done; else
;;                       → :yielding.
;;     :yielding       — :after {tick-ms :processing} schedules the
;;                       next chunk after one browser tick.
;;     :done           — terminal. :entry dispatches :on-child-done to
;;                       the parent. The parent's exit cascade tears
;;                       this child down once :on-all-complete fires.
;;     :cancelled      — never reached cooperatively: cancellation
;;                       cascades from the parent via :rf.machine/destroy.
;;                       Included for shape-completeness / observability.

(def processor-machine
  {:doc "Process one shard of items in chunks. Cooperative yield via
         `:after` between chunks; progress reported via :progress event
         to the parent; terminal `:done` state dispatches the parent's
         :on-child-done keyword (`:work/child-done`)."

   :initial :idle

   ;; The :data is materialised from the parent's per-child invoke-spec
   ;; :data slot at spawn time (see :work/flow below). :shard is the
   ;; user-supplied id keyword the parent assigned (`:s1`/`:s2`/`:s3`);
   ;; :total is the shard size; :tick-ms is the per-chunk yield delay.
   :data    {:shard     nil
             :total     0
             :processed 0
             :tick-ms   default-tick-ms}

   :guards
   {:done?
    ;; Have we processed every item in this shard?
    (fn guard-done? [{:keys [data]}]
      (>= (:processed data) (:total data)))

    :more-work?
    (fn guard-more-work? [{:keys [data]}]
      (< (:processed data) (:total data)))}

   :actions
   {:process-one
    ;; Process one item. In a real app this is whatever the per-item
    ;; computation is (encoding, parsing, indexing, ...). For this
    ;; demo we just bump the counter — the load-bearing thing the
    ;; example shows is the yield + progress-reporting + cancellation
    ;; shape, not the per-item work.
    ;;
    ;; The action also dispatches a :progress event back to the
    ;; parent so the UI's progress bar updates between chunks. The
    ;; parent's :on map handles :progress as a self-transition with
    ;; an action that updates :data :progress.
    (fn action-process-one [{:keys [data]}]
      (let [shard         (:shard data)
            new-processed (inc (:processed data))]
        {:data (assoc data :processed new-processed)
         :fx   [[:dispatch [parent-id [:progress shard new-processed (:total data)]]]]}))

    :dispatch-done
    ;; Terminal action: dispatch the parent's :on-child-done keyword.
    ;; The runtime intercepts the event at the parent's machine
    ;; boundary and updates the join state at
    ;;   [:rf/spawned :work/flow [:working] :done]
    ;; The second-position arg is the user-supplied shard id (e.g.
    ;; :s1) so the parent can address which child completed.
    (fn action-dispatch-done [{:keys [data]}]
      (let [shard (:shard data)]
        {:fx [[:dispatch [parent-id [:work/child-done shard]]]]}))}

   :states
   {:idle
    ;; The runtime synthesises [:rf.machine/spawned] when no explicit
    ;; :start is supplied; declaring it as an :on entry lets the child
    ;; auto-kick into :processing on spawn.
    {:tags #{:work/idle}
     :on   {:rf.machine/spawned :processing}}

    :processing
    ;; Process one item, then immediately re-evaluate the work via
    ;; the :always cascade.
    {:tags  #{:work/running :work/cancellable}
     :entry :process-one
     :always [{:target :checking-done}]}

    :checking-done
    ;; Eventless decision: done → :done; more work → :yielding.
    ;; :always evaluates first-match-wins; the :done? / :more-work?
    ;; guards are mutually exclusive so the order doesn't matter
    ;; semantically.
    {:always [{:guard :done?      :target :done}
              {:guard :more-work? :target :yielding}]}

    :yielding
    ;; The canonical browser-yield seam. :after carries a runtime
    ;; clock-driven delay; the standard cancellation cascade (the
    ;; parent's `:exit` action) tears down the in-flight timer on
    ;; child-destroy, so a worker cancelled mid-yield does NOT
    ;; resume after the delay elapses.
    ;;
    ;; The :after key is a (fn [{:keys [snapshot]}] ms) form per Spec
    ;; 005 §Value shape (rf2-grw4i / rf2-v0rrr — unified context-map) —
    ;; reads the per-child :tick-ms out of :data so tests / callers can
    ;; stagger or zero-out the delay.
    {:tags  #{:work/running :work/cancellable :work/yielding}
     :after {(fn after-tick-ms [{snap :snapshot}]
               (or (-> snap :data :tick-ms) default-tick-ms))
             :processing}}

    :done
    ;; Terminal. Dispatch the parent's :on-child-done keyword as the
    ;; child's last act. The exit cascade then tears the child down
    ;; once the parent's :on-all-complete fires.
    {:meta  {:terminal? true}
     :tags  #{:work/done}
     :entry :dispatch-done}

    :cancelled
    ;; Never reached by ordinary child transitions — the cancellation
    ;; cascade destroys the child via :rf.machine/destroy fx (per
    ;; Spec 005 §Cancellation cascade) rather than transitioning it
    ;; here. Kept in the spec for tag completeness and for code paths
    ;; that may later route cancellation as an event into the child.
    {:meta {:terminal? true}
     :tags #{:work/cancelled}}}})

(rf/reg-machine :work/processor processor-machine)

;; ============================================================================
;; THE PARENT COORDINATOR — :work/flow
;; ============================================================================
;;
;; Three children, one parent, one declarative :spawn-all entry. The
;; parent has no per-child bookkeeping in :data beyond the aggregated
;; :progress map (the user wants to see it; the runtime doesn't read
;; it). The :children / :done / :failed / :resolved? join-state map
;; lives in app-db under [:rf/spawned :work/flow [:working]] — runtime
;; owned per Spec 005 §Spawn-id tracking.

(def shards
  "The shard ids this demo processes in parallel. Three is enough to
   show the parallelism and the join; small enough to keep the UI
   readable. Each shard is just a label here — in a real app it would
   be a chunk of work (a slice of a dataset, a region of an image,
   ...) the worker knows how to process."
  [:s1 :s2 :s3])

(def flow-machine
  {:doc "Parent coordinator. Spawns one :work/processor per shard via
         :spawn-all; joins on :all; cancels mid-flight on :cancel
         or on the parent's :working state being exited by any means
         (including user-driven unmount → :cancel dispatch from the
         component's :will-unmount cleanup)."

   :initial :idle

   :data    {:total    items-per-shard
             :shards   shards
             :progress (zipmap shards (repeat 0))
             :outcome  nil}

   :actions
   {:reset-progress
    (fn action-reset-progress [{:keys [data]}]
      {:data (assoc data
                    :progress (zipmap (:shards data) (repeat 0))
                    :outcome  nil)})

    :record-progress
    ;; Self-transition action — a child dispatched
    ;;   [:work/flow [:progress :s1 5 100]]
    ;; and the parent records the per-shard progress so the view's
    ;; aggregate-progress sub recomputes.
    (fn action-record-progress [{data :data [_ shard-id processed _total] :event}]
      {:data (assoc-in data [:progress shard-id] processed)})

    :stamp-outcome
    ;; Stamp :outcome on entry to a terminal state so the UI can show
    ;; "Complete" / "Cancelled" / "Failed" without a separate sub. The
    ;; action's event payload's first element is the matching
    ;; transition event keyword (`:work/all-done` / `:cancel`
    ;; / `:work/any-failed`); we just record it as the outcome.
    (fn action-stamp-outcome [{data :data [event-kw & _] :event}]
      {:data (assoc data :outcome
                    (case event-kw
                      :work/all-done    :complete
                      :cancel           :cancelled
                      :work/any-failed  :error
                      ;; default: keep current
                      (:outcome data)))})}

   :states
   {:idle
    ;; Initial state. :start kicks off the work; :reset returns from
    ;; a terminal state back here.
    {:tags #{:flow/idle}
     :on   {:start {:target :working :action :reset-progress}
            :reset {:target :idle    :action :reset-progress}}}

    :working
    ;; The :spawn-all-bearing state. On entry the runtime emits one
    ;; :rf.machine/spawn-all-init fx (seeds the join-state map) plus
    ;; N :rf.machine/spawn fxs (one per child). On exit (by ANY
    ;; transition including :cancel / :work/all-done), the runtime
    ;; emits a single :rf.machine/destroy fx carrying :rf/spawn-all
    ;; true; the destroy fx handler reads the join-state's :children
    ;; map and tears each surviving child down.
    ;;
    ;; The :progress event is the per-step report from a child. It's
    ;; a self-transition (same target :working) with an action that
    ;; updates :data :progress. Because :progress is NOT the parent's
    ;; :on-child-done / :on-child-error keyword, it is NOT intercepted
    ;; by the :spawn-all join machinery — the runtime feeds it
    ;; straight into the parent's :on lookup.
    {:tags #{:flow/working}
     :spawn-all
     {:children [{:id :s1 :machine-id :work/processor
                  :data {:shard :s1 :total items-per-shard :processed 0 :tick-ms default-tick-ms}}
                 {:id :s2 :machine-id :work/processor
                  :data {:shard :s2 :total items-per-shard :processed 0 :tick-ms default-tick-ms}}
                 {:id :s3 :machine-id :work/processor
                  :data {:shard :s3 :total items-per-shard :processed 0 :tick-ms default-tick-ms}}]
      :join             :all
      ;; The keywords children dispatch back. The runtime intercepts
      ;; events whose inner-event-id matches these and updates the
      ;; join-state at [:rf/spawned :work/flow [:working]].
      :on-child-done    :work/child-done
      :on-child-error   :work/child-error
      ;; The events the runtime dispatches into the parent when the
      ;; join resolves. The parent's :on table below handles each.
      :on-all-complete  [:work/all-done]
      :on-any-failed    [:work/any-failed]}
     :on    {;; Progress reports — INTERNAL self-transition (no :target):
             ;; the action runs but :exit / :entry do NOT fire, so the
             ;; :spawn-all entry cascade does not re-spawn children and
             ;; the exit cascade does not tear them down. Per Spec 005
             ;; §Transitions §Internal vs external self-transitions: omit
             ;; :target for internal-self.
             :progress        {:action :record-progress}
             ;; Join-resolution events. The runtime dispatches these
             ;; into the parent when :on-all-complete / :on-any-failed
             ;; resolve; the parent's transition handles them as
             ;; ordinary state transitions.
             :work/all-done   {:target :complete  :action :stamp-outcome}
             :work/any-failed {:target :error     :action :stamp-outcome}
             ;; Cooperative user-driven cancellation. The transition
             ;; exits :working; the desugared :spawn-all exit fires
             ;; one :rf.machine/destroy fx with :rf/spawn-all true;
             ;; the destroy fx handler tears down every surviving
             ;; child.
             :cancel          {:target :cancelled :action :stamp-outcome}}}

    :complete
    {:meta {:terminal? false}
     :tags #{:flow/complete :flow/terminal}
     :on   {:reset {:target :idle :action :reset-progress}}}

    :cancelled
    {:tags #{:flow/cancelled :flow/terminal}
     :on   {:reset {:target :idle :action :reset-progress}
            :start {:target :working :action :reset-progress}}}

    :error
    {:tags #{:flow/error :flow/terminal}
     :on   {:reset {:target :idle :action :reset-progress}
            :start {:target :working :action :reset-progress}}}}})

(rf/reg-machine :work/flow flow-machine)

;; ============================================================================
;; SUBSCRIPTIONS — slice readers + aggregate progress
;; ============================================================================

(rf/reg-sub :work/flow-snapshot
  :<- [:rf/machine :work/flow]
  (fn [snap _] snap))

(rf/reg-sub :work/flow-state
  :<- [:work/flow-snapshot]
  (fn [snap _] (:state snap)))

(rf/reg-sub :work/flow-data
  :<- [:work/flow-snapshot]
  (fn [snap _] (:data snap)))

(rf/reg-sub :work/progress-map
  :<- [:work/flow-data]
  (fn [data _] (:progress data {})))

(rf/reg-sub :work/total-items
  :<- [:work/flow-data]
  (fn [data _]
    (* (count (:shards data)) (:total data 0))))

(rf/reg-sub :work/items-done
  :<- [:work/progress-map]
  (fn [progress _]
    (reduce + 0 (vals progress))))

(rf/reg-sub :work/progress-fraction
  {:doc "Aggregate progress, 0.0 .. 1.0. The view's progress bar
         multiplies by 100 to render."}
  :<- [:work/items-done]
  :<- [:work/total-items]
  (fn [[done total] _]
    (if (pos? total)
      (/ done total)
      0)))

(rf/reg-sub :work/outcome
  :<- [:work/flow-data]
  (fn [data _] (:outcome data)))

(rf/reg-sub :work/running?
  :<- [:work/flow-state]
  (fn [state _] (= state :working)))

(rf/reg-sub :work/spawn-registry-children
  {:doc "Read the runtime-owned :children map under the parent's
         :spawn-all slot. Used by the headless tests to assert
         the spawn cascade fired. Returns nil when no :spawn-all
         is active (i.e. before :start or after the cascade has
         torn it down)."}
  (fn sub-work-spawn-registry-children [db _]
    (get-in db [:rf/spawned :work/flow [:working] :children])))

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event-fx :work/initialise
  {:doc "Boot the parent machine to its :idle state."}
  (fn handler-work-initialise [_ _]
    {:fx [[:dispatch [:work/flow [:reset]]]]}))
