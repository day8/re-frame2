(ns re-frame.machine-hydration-reconcile-incarnation-fence-cljs-test
  "The SSR hydration reconcile is bound to ONE frame incarnation across both
  phases and every iteration.

  Every callback-bearing trace the reconcile emits — phase 1's `/cancelled`,
  each arm's leading `:on-supersede`, and each arm's own `/scheduled` — can
  reach a listener that destroys frame A and publishes a same-id successor B.
  The declarations being reconciled were enumerated from A's runtime-db, so
  none of their host work may land in B. Per-step owner capture fails here
  silently: each step is right about the owner it captured, and B, a live frame
  under the right id, accepts the work. Each tooth publishes B from the first
  callback-bearing trace of its phase and reads B; the last test is the
  live-owner control. Deterministic on both hosts: B is published on the
  trace's own stack."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            ;; Loading `re-frame.machines` installs the artefact's late-bind
            ;; hooks + reserved fxs; under a single-ns run nothing else does.
            [re-frame.machines]
            [re-frame.machines.hydrate :as rf.machines.hydrate]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.machines.timer :as rf.machines.timer]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-counter (atom 0))

(defn- fresh-frame!
  "A frame of `platform` under an unused id. `make-frame` opts are FLAT: a
  nested `{:config {…}}` would leave the platform at the `:client` default."
  [platform]
  (let [fid (keyword "rf.hydfence" (str (name platform) (swap! frame-counter inc)))]
    (rf/make-frame {:id fid :platform platform})
    fid))

(defn- inner
  "`frame-id`'s `:after` timer table, or `{}`."
  [frame-id]
  (get @rf.machines.timer/after-timers frame-id {}))

(defn- server-runtime-db
  "Run every `[machine-id events]` pair on ONE real SERVER frame, assert it
  armed no host timer, and return that frame's runtime-db."
  [runs]
  (let [sfid (fresh-frame! :server)]
    (doseq [[machine-id events] runs
            e                   events]
      (rf/dispatch-sync [machine-id e] {:frame sfid}))
    (is (empty? (inner sfid)) "precondition: the SERVER armed no `:after` host timer")
    (rf.frame/frame-runtime-db-value sfid)))

(defn- install!
  "Replace `frame-id`'s runtime-db and run the machines hydration seam."
  [frame-id runtime-db]
  (rf.frame/replace-runtime-db! frame-id runtime-db)
  (rf.machines.hydrate/rearm-after-timers! frame-id))

(def ^:private no-host-work {:subscribes 0 :unsubscribes 0 :arms 0})

(defn- with-host-stubs!
  "Run `f` with subscriptions and the host clock stubbed, tallying every
  subscribe, unsubscribe and host arm into `counts`."
  [counts f]
  (let [reaction (atom 2500)
        tally!   (fn [k] (swap! counts update k inc))]
    (with-redefs [rf.subs/subscribe            (fn ([_q] (tally! :subscribes) reaction)
                                                 ([_q _o] (tally! :subscribes) reaction))
                  rf.subs/unsubscribe          (fn ([_q] (tally! :unsubscribes) nil)
                                                 ([_f _q] (tally! :unsubscribes) nil))
                  rf.interop/schedule-after!   (fn [_thunk _ms] (tally! :arms) ::handle)
                  rf.interop/cancel-scheduled! (fn [_h] nil)]
      (f))))

(defn- install-republishing!
  "`install!` from a clean trace capture, with a listener that on the FIRST
  `operation` trace destroys `frame-id`, publishes a same-id successor B on the
  trace's own stack, and runs `after-swap!`."
  [frame-id runtime-db operation after-swap!]
  (let [fired? (atom false)]
    (rf.trace.tooling/register-listener!
      ::republish
      (fn [ev]
        (when (and (= operation (:operation ev))
                   (compare-and-set! fired? false true))
          (rf.frame/destroy-frame! frame-id)
          (rf/make-frame {:id frame-id :platform :client})
          (after-swap!))))
    (rf.machines.test-support/reset-captured!)
    (try
      (install! frame-id runtime-db)
      (finally (rf.trace.tooling/unregister-listener! ::republish)))))

(defn- assert-successor-took-no-a-work!
  "B holds no timer, carries no `/scheduled` row, and saw no subscription or
  host-clock work after the swap. Without the swap A would keep its timers, so
  these readings also prove the tooth bit."
  [frame-id counts]
  (is (= {:timers {} :scheduled [] :counts no-host-work}
         {:timers    (inner frame-id)
          :scheduled (rf.machines.test-support/events-of :rf.machine.timer/scheduled)
          :counts    @counts})))

(def ^:private dyn-machine
  "A single SUBSCRIPTION-vector `:after`, so an arm takes a reaction, watcher
  and subscription ref-count with it."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {[:hydfence/dyn] {:target :timeout}}}
             :timeout {}}})

(def ^:private literal-machine
  "The actor a replacement DROPS, giving phase 1 one cancellation to fire from."
  {:initial :idle
   :data    {}
   :states  {:idle    {:on {:go :waiting}}
             :waiting {:after {5000 {:target :timeout}}}
             :timeout {}}})

(def ^:private two-delay-machine
  "MULTI-LIVE: one active node bearing TWO `:after` declarations, so the arm
  phase has a second iteration."
  {:initial :idle
   :data    {}
   :states  {:idle      {:on {:go :waiting}}
             :waiting   {:after {[:hydfence/dyn-a] {:target :timeout}
                                 [:hydfence/dyn-b] {:target :elsewhere}}}
             :timeout   {}
             :elsewhere {}}})

(deftest a-cancel-phase-callback-that-republishes-the-frame-arms-nothing-into-b
  ;; CANCEL → ARM: the dropped actor's phase-1 cancellation publishes B, and the
  ;; retained declaration, enumerated from A, must not be armed into B.
  (rf/reg-machine :hydfence/kept dyn-machine)
  (rf/reg-machine :hydfence/gone literal-machine)
  (let [counts (atom no-host-work)
        cfid   (fresh-frame! :client)]
    (with-host-stubs! counts
      (fn []
        (let [rt (server-runtime-db [[:hydfence/kept [[:go]]] [:hydfence/gone [[:go]]]])]
          (install! cfid rt)
          (install-republishing! cfid (update-in rt [:rf.runtime/machines :snapshots]
                                                 dissoc :hydfence/gone)
                                 :rf.machine.timer/cancelled #(reset! counts no-host-work)))))
    (assert-successor-took-no-a-work! cfid counts)))

(deftest an-arm-phase-supersede-that-republishes-the-frame-stops-the-later-arms
  ;; ARM → ARM: an identical re-hydration cancels nothing in phase 1, so arm 1's
  ;; own `:on-supersede` publishes B; the arm after it must not resume against B.
  (rf/reg-machine :hydfence/multi two-delay-machine)
  (let [counts (atom no-host-work)
        cfid   (fresh-frame! :client)]
    (with-host-stubs! counts
      (fn []
        (let [rt (server-runtime-db [[:hydfence/multi [[:go]]]])]
          (install! cfid rt)
          (install-republishing! cfid rt :rf.machine.timer/cancelled #(reset! counts no-host-work)))))
    (assert-successor-took-no-a-work! cfid counts)))

(deftest a-scheduled-trace-callback-that-republishes-the-frame-arms-nothing-into-b
  ;; WITHIN ONE ARM: on a fresh frame nothing is cancelled, so the arm's own
  ;; `/scheduled` — emitted after the slot is reserved and before the host arm —
  ;; is the reconcile's first callback.
  (rf/reg-machine :hydfence/sched dyn-machine)
  (let [counts (atom no-host-work)
        cfid   (fresh-frame! :client)]
    (with-host-stubs! counts
      (fn []
        (install-republishing! cfid (server-runtime-db [[:hydfence/sched [[:go]]]])
                               :rf.machine.timer/scheduled #(reset! counts no-host-work))))
    (is (= {:timers {} :counts no-host-work} {:timers (inner cfid) :counts @counts})
        (str "B holds no A-derived slot and no host clock was armed in its name; nor was A's "
             "`(frame, query-v)` hold released by bare id, which now denotes B"))
    ;; Spec 005 §Trace event catalogue pairs `/scheduled` with `/fired` or
    ;; `/cancelled` by (actor-id, state, epoch); an announced attempt the abort
    ;; left open would stand for a wall-clock window that never opened.
    (let [pair-keys                 [:actor-id :state :delay :epoch :frame]
          [scheduled cancelled :as rows]
          (filterv (comp #{:rf.machine.timer/scheduled :rf.machine.timer/cancelled} :operation)
                   (rf.machines.test-support/captured-events))]
      (is (= [:rf.machine.timer/scheduled :rf.machine.timer/cancelled] (mapv :operation rows))
          "the aborted arm's `/scheduled` row is closed by exactly one `/cancelled`, in that order")
      (is (= (assoc (select-keys (:tags scheduled) pair-keys) :reason :on-frame-destroy)
             (select-keys (:tags cancelled) (conj pair-keys :reason)))
          "and the two rows pair, the closure carrying an existing `:reason`"))))

(deftest a-live-owner-reconcile-arms-every-live-declaration
  ;; The control the fence must not break: with no successor, an identical
  ;; re-hydration supersedes every live declaration in place — the arm loop is
  ;; not truncated and the cancel phase sweeps nothing live.
  (rf/reg-machine :hydfence/live two-delay-machine)
  (let [cfid (fresh-frame! :client)]
    (with-host-stubs! (atom no-host-work)
      (fn []
        (let [rt (server-runtime-db [[:hydfence/live [[:go]]]])]
          (install! cfid rt)
          (rf.machines.test-support/reset-captured!)
          (install! cfid rt))))
    (is (= {:timers 2 :scheduled 2 :cancelled [:on-supersede :on-supersede]}
           {:timers    (count (inner cfid))
            :scheduled (count (rf.machines.test-support/events-of :rf.machine.timer/scheduled))
            :cancelled (mapv (comp :reason :tags)
                             (rf.machines.test-support/events-of :rf.machine.timer/cancelled))}))))
