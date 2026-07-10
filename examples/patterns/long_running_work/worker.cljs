(ns long-running-work.worker
  "The two machines that do the actual work: a child and the parent
   that orchestrates a swarm of them.

   Here's the `:spawn-all` shape in one breath. The parent spawns N
   children. Each child works its own shard, politely handing control
   back to the browser between chunks via `:after`, and firing
   `:progress` at the parent so the UI can keep up. When a child
   finishes it dispatches the parent's `:on-child-done` keyword; once
   all N have checked in, the runtime fires the parent's
   `:on-all-complete`. And if the user bails out mid-flight with
   `:cancel`, the parent leaves `:working` and the exit cascade fires
   `:rf.machine/destroy` at every child still going — pending `:after`
   timers and all. See the `:spawn` glossary entry
   (docs/machines/glossary.md#spawn).

   In other words, this is how you say:

     - 'Run N tasks in parallel and wait for all of them.'
     - 'Show me how it's going.'
     - 'And whatever happens — even if the user wanders off
        mid-job — cancellation has to be airtight.'

   Why a parent plus N children instead of one machine grinding through
   chunks? Because the shards are independent, so they may as well run
   at once — and splitting it this way lets the demo show off both
   halves of the pattern: the join (`:on-all-complete`) and the
   teardown cascade (`:work/flow`'s `:cancel` leaves `:working`, and
   that exit destroys every child)."
  (:require [re-frame.core :as rf]
            ;; Requiring re-frame.machines stands up the runtime, so the
            ;; rf/reg-machine calls below (they run at ns-load) and the
            ;; `:rf/machine` / `:rf.machine/has-tag?` subs all resolve.
            [re-frame.machines]
            [long-running-work.schema :as schema]))

;; ============================================================================
;; CONSTANTS
;; ============================================================================

(def items-per-shard
  "How many make-believe items each child grinds through. Three shards
   in parallel at 50ms an item works out to a ~5-second job — long
   enough that you can actually watch the bars move, short enough that
   the headless test doesn't drag."
  100)

(def default-tick-ms
  "How long a child pauses between chunks in the live UI. The child's
   `:yielding` state declares `:after {tick-ms :processing}`, handing
   the browser a render tick before it grabs the next item. It's a
   named const so headless tests can dial it down (or to zero) via the
   child's `:data :tick-ms`, which the parent sets per child on spawn."
  50)

(def parent-id
  "Where a child sends its messages home: the parent's registered id.
   Children just name it directly, with no per-child plumbing to thread
   an address through — and they can, because in a static `:spawn-all`
   the parent's id is a literal sitting right there in its own source."
  :work/flow)

;; ============================================================================
;; THE CHILD MACHINE — :work/processor
;; ============================================================================
;;
;; A child works its shard one bite at a time. Each `:processing` entry
;; handles a single item; then it yields for one browser tick via
;; `:yielding` + `:after`, and reports `:progress` to the parent on the
;; way so the bar keeps moving. Rinse and repeat until the shard's done.
;;
;; Here's the loop it walks:
;;
;;     :idle           — where it starts. On spawn the runtime sends
;;                       [:rf.machine.spawn/spawned], which nudges it
;;                       into :processing.
;;     :processing     — :entry does one item, bumps :processed, and
;;                       reports :progress. The :always cascade then
;;                       carries it to :checking-done with no event.
;;     :checking-done  — a quick fork, no event needed: finished? →
;;                       :done; more to do? → :yielding.
;;     :yielding       — the pause. :after {tick-ms :processing} lines
;;                       up the next chunk one browser tick later.
;;     :done           — the end. :entry tells the parent :on-child-done.
;;                       The parent's exit cascade clears this child away
;;                       once :on-all-complete fires.
;;     :cancelled      — a child never actually walks in here:
;;                       cancellation comes from the parent as a
;;                       :rf.machine/destroy, not an event routed inward.
;;                       It's here for completeness, and so tools can see
;;                       the full shape.

(rf/defmachine processor-machine
  {:doc "Works one shard, a chunk at a time. Yields to the browser
         between chunks via `:after`, reports `:progress` to the parent
         as it goes, and on reaching `:done` tells the parent
         :on-child-done (`:work/child-done`)."

   :initial :idle

   ;; Check the child's initial `:data` at spawn, before the snapshot
   ;; even installs. This is the trick for validating a spawned child:
   ;; the runtime hands each one its own id (`:work/processor#0`, …),
   ;; so no fixed app-db path could ever name it — the machine's
   ;; `[:schemas :data]` slot validates the spawn whatever the id turns
   ;; out to be. (See schema.cljs.)
   :schemas {:data schema/ProcessorData}

   ;; The parent fills this in per child on spawn, from its invoke-spec
   ;; :data (see :work/flow below). :shard is the id the parent handed
   ;; out (`:s1`/`:s2`/`:s3`); :total is the shard size; :tick-ms is the
   ;; pause between chunks. The values here are just placeholders.
   :data    {:shard     nil
             :total     0
             :processed 0
             :tick-ms   default-tick-ms}

   :guards
   {:done?
    ;; Worked through every item in the shard?
    (fn guard-done? [{:keys [data]}]
      (>= (:processed data) (:total data)))

    :more-work?
    ;; Still something left to do?
    (fn guard-more-work? [{:keys [data]}]
      (< (:processed data) (:total data)))}

   :actions
   {:process-one
    ;; Do one item's worth of work. In a real app this would be the
    ;; actual per-item job — encode a frame, parse a row, index a
    ;; document. Here we just bump a counter, because the point of the
    ;; example is the *shape* (yield, report, cancel), not the labour.
    ;;
    ;; It also fires :progress at the parent so the bar moves between
    ;; chunks. The parent treats :progress as a self-transition that
    ;; folds the number into :data :progress.
    (fn action-process-one [{:keys [data]}]
      (let [shard         (:shard data)
            new-processed (inc (:processed data))]
        {:data (assoc data :processed new-processed)
         :fx   [[:dispatch [parent-id [:progress shard new-processed (:total data)]]]]}))

    :dispatch-done
    ;; The child's last words: tell the parent :on-child-done. The
    ;; runtime catches this at the parent's boundary and updates the
    ;; join. We pass the shard id (e.g. :s1) so the parent knows which
    ;; child just crossed the line.
    (fn action-dispatch-done [{:keys [data]}]
      (let [shard (:shard data)]
        {:fx [[:dispatch [parent-id [:work/child-done shard]]]]}))}

   :states
   {:idle
    ;; The runtime conjures [:rf.machine.spawn/spawned] when there's no
    ;; explicit :start to send. Listening for it here is what lets the
    ;; child spring into :processing the instant it's spawned.
    {:tags #{:work/idle}
     :on   {:rf.machine.spawn/spawned :processing}}

    :processing
    ;; Do one item, then take stock again. That second step is the
    ;; :always cascade — an EVENTLESS transition the runtime follows on
    ;; entry, no event required. So once :process-one runs, the child
    ;; slides straight on to :checking-done.
    {:tags  #{:work/running :work/cancellable}
     :entry :process-one
     :always [{:target :checking-done}]}

    :checking-done
    ;; The fork in the road, again eventless: done → :done, otherwise
    ;; → :yielding. :always takes the first matching branch, but :done?
    ;; and :more-work? can't both be true, so the order here is just for
    ;; the reader.
    {:always [{:guard :done?      :target :done}
              {:guard :more-work? :target :yielding}]}

    :yielding
    ;; The breather — the one spot where the child steps aside for the
    ;; browser. :after waits on the runtime clock, and if the child gets
    ;; destroyed mid-wait, the cascade cancels that pending timer too:
    ;; a worker killed here will NOT wake up and carry on once the delay
    ;; runs out.
    ;;
    ;; :after is a (fn [ctx] ms) so it can read the child's own :tick-ms
    ;; out of :data — handy for tests that want to stagger the delays or
    ;; zero them out entirely.
    {:tags  #{:work/running :work/cancellable :work/yielding}
     :after {(fn after-tick-ms [{snap :snapshot}]
               (or (-> snap :data :tick-ms) default-tick-ms))
             :processing}}

    :done
    ;; Journey's end. The child's parting act is to tell the parent
    ;; :on-child-done; the exit cascade then sweeps the child away once
    ;; the parent's :on-all-complete fires.
    {:meta  {:terminal? true}
     :tags  #{:work/done}
     :entry :dispatch-done}

    :cancelled
    ;; Defined, but this example never routes a child into it. The
    ;; parent cancels a child by destroying it with :rf.machine/destroy,
    ;; not by sending a cancel event inward. We keep the state around for
    ;; two reasons: so the machine's tags advertise #{:work/cancelled}
    ;; to tools, and so an app that *does* prefer in-FSM cancellation
    ;; has a place to land.
    {:meta {:terminal? true}
     :tags #{:work/cancelled}}}})

(rf/reg-machine :work/processor processor-machine)

;; ============================================================================
;; THE PARENT COORDINATOR — :work/flow
;; ============================================================================
;;
;; One parent, three children, a single `:spawn-all` declaration. The
;; parent keeps almost nothing of its own — just the aggregated
;; :progress map, and only because the user likes to watch it (the
;; runtime never reads it). The real ledger — who's done, who failed,
;; who's resolved — is the join-state map, and that belongs to the
;; runtime, tucked away in runtime-db.

(def shards
  "The shards this demo runs in parallel. Three is the sweet spot:
   enough to show off the parallelism and the join, few enough to keep
   the UI legible. Here a shard is just a label — in a real app it'd be
   an actual slice of work (a chunk of a dataset, a region of an image)
   that the worker knows how to chew on."
  [:s1 :s2 :s3])

(rf/defmachine flow-machine
  {:doc "The coordinator. Spawns one :work/processor per shard with
         :spawn-all, joins on :all, and cancels mid-flight whenever
         :working is left — whether by an explicit :cancel or by the
         user hiding the bench (its `r/with-let` cleanup dispatches
         :cancel for them)."

   :initial :idle

   :data    {:total    items-per-shard
             :shards   shards
             :progress (zipmap shards (repeat 0))
             :outcome  nil}

   ;; Guards the snapshot's :data. The snapshot lives in runtime-db, so
   ;; [:schemas :data] — not an app-schema — is what does the checking.
   :schemas {:data schema/FlowData}

   :actions
   {:reset-progress
    (fn action-reset-progress [{:keys [data]}]
      {:data (assoc data
                    :progress (zipmap (:shards data) (repeat 0))
                    :outcome  nil)})

    :record-progress
    ;; Filing a child's progress report. A child dispatched, say,
    ;;   [:work/flow [:progress :s1 5 100]]
    ;; and we drop that count into the right slot, which nudges the
    ;; view's aggregate-progress sub to recompute.
    (fn action-record-progress [{data :data [_ shard-id processed _total] :event}]
      {:data (assoc-in data [:progress shard-id] processed)})

    :stamp-outcome
    ;; Pin down how it all ended, on the way into a terminal state, so
    ;; the UI can say "Complete" / "Cancelled" / "Failed" without a sub
    ;; of its own. The event that brought us here names the ending
    ;; (`:work/all-done` / `:cancel` / `:work/any-failed`) — we just
    ;; translate it and record it.
    (fn action-stamp-outcome [{data :data [event-kw & _] :event}]
      {:data (assoc data :outcome
                    (case event-kw
                      :work/all-done    :complete
                      :cancel           :cancelled
                      :work/any-failed  :error
                      ;; anything else: leave the outcome as it was
                      (:outcome data)))})}

   :states
   {:idle
    ;; The resting state. :start sets the work going; :reset is how a
    ;; terminal state finds its way back here.
    {:tags #{:flow/idle}
     :on   {:start {:target :working :action :reset-progress}
            :reset {:target :idle    :action :reset-progress}}}

    :working
    ;; The heart of it — the state that carries :spawn-all. Entering it,
    ;; the runtime seeds the join-state map and spawns a child for each
    ;; entry below. Leaving it — by ANY route, :cancel or :work/all-done
    ;; alike — runs the exit cascade, which (because :spawn-all is sugar
    ;; over N :spawns, per spec 005) fires a :rf.machine/destroy per
    ;; surviving child and clears every one still standing.
    ;;
    ;; The :progress event is just a child checking in. It's an internal
    ;; self-transition that updates :data :progress — and because
    ;; :progress isn't the parent's :on-child-done / :on-child-error
    ;; keyword, the join machinery leaves it alone and the runtime feeds
    ;; it straight to the parent's :on table.
    {:tags #{:flow/working}
     :spawn-all
     {:children [{:id :s1 :machine-id :work/processor
                  :data {:shard :s1 :total items-per-shard :processed 0 :tick-ms default-tick-ms}}
                 {:id :s2 :machine-id :work/processor
                  :data {:shard :s2 :total items-per-shard :processed 0 :tick-ms default-tick-ms}}
                 {:id :s3 :machine-id :work/processor
                  :data {:shard :s3 :total items-per-shard :processed 0 :tick-ms default-tick-ms}}]
      :join             :all
      ;; The keywords a child uses to phone home. The runtime watches
      ;; for events whose inner id matches these and updates the join.
      :on-child-done    :work/child-done
      :on-child-error   :work/child-error
      ;; And the events the runtime sends *back* into the parent once
      ;; the join resolves. The :on table below picks each one up.
      :on-all-complete  [:work/all-done]
      :on-any-failed    [:work/any-failed]}
     :on    {;; A progress report. Note there's no :target — that makes
             ;; it an internal self-transition: the action runs, but
             ;; :exit / :entry stay quiet, so the :spawn-all entry
             ;; cascade won't re-spawn the children and the exit cascade
             ;; won't tear them down. (Dropping :target is the whole
             ;; trick.)
             :progress        {:action :record-progress}
             ;; The join landing here. The runtime dispatches these the
             ;; moment :on-all-complete / :on-any-failed resolve, and
             ;; the parent treats them as plain old transitions.
             :work/all-done   {:target :complete  :action :stamp-outcome}
             :work/any-failed {:target :error     :action :stamp-outcome}
             ;; The user pulling the plug. Leaving :working is the part
             ;; that matters: that exit fires a :rf.machine/destroy per
             ;; surviving child, taking every one of them with it.
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
;; SUBSCRIPTIONS — read the snapshot, then total it up
;; ============================================================================
;;
;; A little layered chain: the first sub grabs the parent's snapshot,
;; the next few carve slices out of it, and the last couple do the
;; arithmetic the progress bar wants. Each one stays small and reads
;; only from the layer above — the usual subscription-graph shape.

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
  {:doc "How far along, all up — a number from 0.0 to 1.0. The progress
         bar multiplies by 100 to turn it into a width."}
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

;; ============================================================================
;; INITIALISATION
;; ============================================================================

(rf/reg-event :work/initialise
  {:doc "Bring the :work/flow machine to life in its :idle start state, via
         the `:rf.machine/start` creation marker — the same eager kick the
         boot and websocket examples use. :idle is the initial state and has
         no entry work, so start materialises it cleanly, with an :explicit
         start-cause. Booting via an ordinary event (e.g. [:reset]) instead
         lazily first-touches the machine, which flags the :lazy start-cause
         the Xray [START] badge surfaces — an ordering smell, and out of step
         with the sibling machine examples."}
  (fn handler-work-initialise [_ _]
    {:fx [[:dispatch [:work/flow [:rf.machine/start]]]]}))
