(ns re-frame.resources.timers
  "The host-side side table of advisory stale / GC / poll timers, keyed by
  `[frame-id resource-key kind]`. A timer only nudges: it dispatches an
  internal re-check event whose handler re-derives freshness from the
  entry's durable timestamps and writes nothing when the facts have moved
  on, so a late, coalesced or never-fired timer is harmless. The table is
  transient host state (never runtime-db, never serialized), cleared per
  frame through the single `:resources/on-frame-destroyed!` hook.

  Timer writes ride the `:rf.resource/schedule-timers` fx, which is a no-op
  on a `:server?` frame: SSR never arms wall-clock timers. The skip reads the
  frame's platform rather than a `:platforms` gate because the host-wide
  default platform is `:server` and a JVM client-mode test must still arm.
  Spec 016 §Stale and GC scheduling and Spec 016 §Polling."
  (:require [re-frame.frame :as rf.frame]
            [re-frame.identity :as rf.identity]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.managed-timer :as rf.managed-timer]
            [re-frame.resources.state :as rf.resources.state]
            [re-frame.trace :as rf.trace]))

#?(:cljs (declare document-hidden?))

#?(:clj (set! *warn-on-reflection* true))

;; ---- timer kinds ----------------------------------------------------------

(def stale-kind
  "The `:stale` timer kind: arms `:stale-after-ms` after `:loaded-at`.
  Spec 016 §Stale and GC scheduling."
  :stale)

(def gc-kind
  "The `:gc` timer kind: arms `:gc-after-ms` when a load settles and again
  when the last owner releases. Each arm stamps the entry's `:gc-window`, and
  a re-check from an older window never collects.
  Spec 016 §Stale and GC scheduling."
  :gc)

(def poll-kind
  "The `:poll` timer kind: arms `:poll-interval-ms` after each settle while
  the entry is actively owned. The interval is the cadence, so a tick does
  not first check `:stale?`. Spec 016 §Polling."
  :poll)

;; ---- reserved internal re-check event ids ---------------------------------

(def stale-fired-event
  "The internal re-check event a fired stale timer dispatches. User code
  MUST NOT dispatch it. Spec 016 §Stale and GC scheduling."
  :rf.resource.internal/stale-fired)

(def gc-fired-event
  "The internal re-check event a fired GC timer dispatches; its handler
  removes the entry only if it is still owner-free and not in flight. User
  code MUST NOT dispatch it. Spec 016 §Stale and GC scheduling."
  :rf.resource.internal/gc-fired)

(def poll-fired-event
  "The internal re-check event a fired poll timer dispatches. User code MUST
  NOT dispatch it. Spec 016 §Polling."
  :rf.resource.internal/poll-fired)

;; ---- host-side timer side table -------------------------------------------

(defonce
  ^{:doc "`[frame-id resource-key kind]` → `{:token <int> :handle <host-handle-or-nil>}`.
   `:handle nil` is a two-phase ARMING sentinel. Transient host state, so an
   epoch restore cannot rewind it and it never rides the wire.
   Runtime-Subsystems §5. Teardown."}
  timer-table
  (atom {}))

;; Two-phase arming (reserve a sentinel, then publish the handle) plus
;; token-scoped cancellation make arm / cancel / frame-destroy linearizable
;; against a reused `[frame rkey kind]` key.

(defonce ^:private timer-attempt-counter
  ;; Per-arm ownership token, so a trailing cancel of an OLD attempt never
  ;; claims a re-armed successor in the same slot, and a late-returning arm can
  ;; tell a cleanup already claimed its slot.
  (atom 0))

(defn- next-attempt-token []
  (swap! timer-attempt-counter inc))

(defn- timer-key
  "The side-table key. `resource-key` is reduced to its canonical bytes so a
  list- and a vector-params resource never share a slot, which Clojure `=`
  map keys would collapse."
  [frame-id resource-key kind]
  [frame-id (rf.identity/canonical-bytes resource-key) kind])

(defn cancel!
  "Cancels and drops the timer for `[frame-id resource-key kind]`; idempotent,
  returns nil. Claims the slot only while the observed `:token` is still
  current, so it never erases a successor it did not cancel. An arming
  sentinel is dropped without a host cancel: the arming thread's publish
  phase cancels the handle it gets back. The host cancel runs off the
  CAS-derived old snapshot, never inside the retriable swap."
  [frame-id resource-key kind]
  (let [k (timer-key frame-id resource-key kind)]
    (when-let [slot (get @timer-table k)]
      (let [token      (:token slot)
            [old _new] (swap-vals! timer-table
                                   (fn [m] (if (= token (:token (get m k)))
                                             (dissoc m k)
                                             m)))
            claimed    (get old k)]
        (when (and (= token (:token claimed)) (:handle claimed))
          (rf.interop/cancel-scheduled! (:handle claimed))))))
  nil)

(defn cancel-for-key!
  "Cancels the stale, GC and poll timers for `[frame-id resource-key]` on
  entry removal; idempotent, returns nil. Last-owner release does not use
  it: the entry stays alive, so that path cancels only the poll and re-arms
  GC."
  [frame-id resource-key]
  (cancel! frame-id resource-key stale-kind)
  (cancel! frame-id resource-key gc-kind)
  (cancel! frame-id resource-key poll-kind)
  nil)

;; ---- host document visibility ---------------------------------------------
;;
;; A poll pauses while the tab is hidden by default. Visibility is read only
;; here at the host boundary and stamped onto the re-check event, so
;; `poll-fired-handler` decides from its recordable payload rather than an
;; ambient host read. EP-0010 §Browser And Storage Facts.

#?(:cljs
   (defn document-hidden?
     "True when a host `document` exists and its `visibilityState` is not
     `\"visible\"`. No DOM reads as not hidden."
     []
     (boolean
       (when (exists? js/document)
         (let [vs (.-visibilityState js/document)]
           (and (some? vs) (not= "visible" vs)))))))

(defn- dispatch-recheck!
  "Dispatches the re-check event for a fired timer of `kind` into `frame-id`
  through the `:router/dispatch!` late-bind hook (resources never requires
  the router), with `:source :after-timer`. A `:poll` fire stamps `:hidden?`
  and a `:gc` fire stamps the `:gc-window` it armed in, so each handler
  decides from its payload alone. No-op when no dispatcher is bound."
  [frame-id resource-key kind gc-window]
  (when-let [dispatch! (rf.late-bind/get-fn :router/dispatch!)]
    (let [event-id (condp = kind
                     stale-kind stale-fired-event
                     gc-kind    gc-fired-event
                     poll-kind  poll-fired-event)
          payload  (cond-> {:resource/key resource-key}
                     (= kind gc-kind)
                     (assoc :gc-window gc-window)
                     (= kind poll-kind)
                     (assoc :hidden? #?(:cljs (document-hidden?) :clj false)))]
      (dispatch! [event-id payload]
                 {:frame frame-id :source :after-timer}))))

(defn- entry-gc-window
  "The live entry's GC window, 0 when none has armed. Read when a GC timer
  arms, after the arming event's commit, so it sees the window that event
  opened."
  [frame-id resource-key]
  (:gc-window (get-in (rf.frame/frame-runtime-db-value frame-id)
                      (rf.resources.state/entry-path resource-key))
              0))

(def ^:private scheduled-trace-id
  "The per-kind `…-scheduled` trace op. `schedule!` owns the emit so a kind
  never mis-attributes a sibling's schedule."
  {stale-kind :rf.resource/stale-scheduled
   gc-kind    :rf.resource/gc-scheduled
   poll-kind  :rf.resource/poll-scheduled})

(defn schedule!
  "Reconciles the `kind` timer for `[frame-id resource-key]` to `delay-ms`,
  cancel-then-arm. A positive delay arms (emitting the kind's
  `…-scheduled` trace) and returns the host handle; a nil or non-positive
  delay is an explicit disarm and returns nil.
  Spec 016 §Stale and GC scheduling."
  [frame-id resource-key kind delay-ms]
  (cancel! frame-id resource-key kind)
  ;; Reserve the slot BEFORE arming the host clock and publish the handle only
  ;; if this attempt still owns it, so a cleanup racing the arm is never
  ;; outrun by a late-returning handle.
  (when (rf.managed-timer/positive-delay? delay-ms)
    (let [k      (timer-key frame-id resource-key kind)
          token  (next-attempt-token)
          ;; Cancel-then-arm supersedes a timer only until it fires; its
          ;; re-check then queues behind events already queued, so a release
          ;; or settle can open a newer window before the re-check lands.
          window (when (= kind gc-kind) (entry-gc-window frame-id resource-key))]
      (swap! timer-table assoc k {:token token :handle nil})
      (let [handle
            (rf.managed-timer/arm!
              (fn []
                ;; Claim this fire's slot atomically: dispatch authority and
                ;; self-reap in one swap. A cleanup or re-arm that claimed it
                ;; first (even a synchronous host callback firing before
                ;; `arm!` returns) suppresses the dead-on-arrival dispatch.
                (let [[old _new] (swap-vals! timer-table
                                             (fn [m] (if (= token (:token (get m k)))
                                                       (dissoc m k)
                                                       m)))]
                  (when (= token (:token (get old k)))
                    (dispatch-recheck! frame-id resource-key kind window))))
              delay-ms)
            [old _new] (swap-vals! timer-table
                                   (fn [m] (if (= token (:token (get m k)))
                                             (assoc m k {:token token :handle handle})
                                             m)))]
        (if (= token (:token (get old k)))
          (do (rf.trace/emit! :rf.event (scheduled-trace-id kind)
                           {:rf.frame/id frame-id :resource/key resource-key
                            :delay-ms delay-ms})
              handle)
          ;; A cleanup or synchronous self-fire claimed the sentinel while we
          ;; armed: cancel the orphan handle and publish nothing.
          (do (rf.interop/cancel-scheduled! handle)
              nil))))))

(defn release-frame!
  "Cancels and drops every timer of a destroyed `frame-id`; idempotent,
  returns nil. Touches only the host side table: the durable entries go with
  the frame value. Runtime-Subsystems §5. Teardown."
  [frame-id]
  ;; One atomic swap, host cancels off the old snapshot. An arming sentinel is
  ;; never cancelled here: its publish phase cancels the real handle, so the
  ;; timer is cancelled exactly once.
  (let [[old _new] (swap-vals! timer-table
                               (fn [m] (into {} (remove (fn [[[fid _ _] _]] (= fid frame-id))) m)))]
    (doseq [[[fid _rkey _kind] slot] old
            :when (and (= fid frame-id) (:handle slot))]
      (rf.interop/cancel-scheduled! (:handle slot))))
  nil)

(defn reset-cache!
  "Cancels and drops every frame's timers, for test isolation; returns nil.
  The runtime / frames reset does not clear this host state, so the
  test-support reset hook calls it."
  []
  (let [[old _new] (reset-vals! timer-table {})]
    (doseq [[_ slot] old
            :when (:handle slot)]
      (rf.interop/cancel-scheduled! (:handle slot))))
  nil)

;; ---- the host-side scheduling fx ------------------------------------------
;;
;; The side table is host state, so its writes ride an fx rather than a
;; runtime-db effect, as the generation high-water mark and work handles do.

(def schedule-timers-meta
  "Registration metadata for the `:rf.resource/schedule-timers` fx."
  {:doc "Reconcile the host-side stale / GC / poll timers for a resource entry,
keyed by `[frame-id resource-key kind]`. Args:
`{:frame-id … :resource/key … :timers {<kind> <delay-ms> …} :server? …}`
where `<kind>` ∈ `#{:stale :gc :poll}`.
The `:timers` map's KEYS are the kinds this emission reconciles: a key present
with a POSITIVE delay ARMS that kind (cancel-then-arm), a key present with a
nil / non-positive delay CANCELS it (an explicit disarm), and a kind ABSENT
from the map is PRESERVED (left as-is). A FULL settlement (successful load /
mutation settle) names ALL declared kinds — the stale / GC delays derived from
the durable `:loaded-at` + `:stale-after-ms` / `:gc-after-ms`, the poll delay
the `:poll-interval-ms` supplied only for an actively-owned entry (EP-0020) —
so it also cancels a policy removed by hot reload. A PARTIAL re-arm (a poll
tick / a GC skip / a last-owner release) names ONLY its own kind, preserving
its siblings.
`:server?` is read from the cascade frame's platform. No-op
under `:server? true` (SSR uses the blocking-drain wait point + lazy client
revalidation, never wall-clock background timers). The fired timer dispatches a
re-checking internal event; the handler re-checks the durable entry before
writing (the timer is advisory). Per Spec 016 §Stale and GC scheduling /
§Polling."})

(defn schedule-timers-handler
  "`:rf.resource/schedule-timers` fx handler: reconciles each kind present in
  `:timers` through `schedule!` and leaves an absent kind as armed. Absence
  means preserve and a named nil means cancel, so a full settlement can drop
  a policy removed by hot reload while a partial re-arm touches only its own
  kind. No-op under `:server? true`. Spec 016 §Stale and GC scheduling."
  [_ctx {:keys [frame-id server? timers] resource-key :resource/key}]
  (when-not server?
    (doseq [[kind delay-ms] timers]
      (schedule! frame-id resource-key kind delay-ms)))
  nil)

;; ---- the cancel fx (entry removed) ----------------------------------------

(def cancel-timers-meta
  "Registration metadata for the `:rf.resource/cancel-timers` fx."
  {:doc "Cancel the host-side stale / GC / poll timers for removed resource entries,
keyed by `[frame-id resource-key]`. Args:
`{:frame-id … :resource/keys [<scoped-key> …]}`. Emitted when entries are
removed (`:rf.resource/remove` / `:rf.resource/clear-scope` / a fired GC) so
their advisory timer handles are released promptly rather than waiting for
frame destroy. Runs on every platform (a server render with no armed timers
no-ops harmlessly; cancellation must never be platform-gated). Per Spec 016
§Stale and GC scheduling."})

(defn cancel-timers-handler
  "`:rf.resource/cancel-timers` fx handler: cancels every timer kind for each
  of `:resource/keys` in `:frame-id`."
  [_ctx {:keys [frame-id] resource-keys :resource/keys}]
  (doseq [rkey resource-keys]
    (cancel-for-key! frame-id rkey))
  nil)

;; ---- the poll-only cancel fx (last owner released) ------------------------
;;
;; Owner release leaves the entry alive with its GC reaper, so only `:poll` is
;; cancelled. The poll-fired re-check would refuse an owner-free entry anyway;
;; this cancel makes the stop prompt.

(def cancel-poll-timers-meta
  "Registration metadata for the `:rf.resource/cancel-poll-timers` fx."
  {:doc "Cancel the host-side `:poll` timer (ONLY) for entries that became
owner-free, keyed by `[frame-id resource-key]`. Args:
`{:frame-id … :resource/keys [<scoped-key> …]}`. Emitted by
`:rf.resource/release-owner` for each entry whose `:active-owners` went empty
(EP-0020 — polling stops the instant the last owner releases; a poll never
pins an owner-free entry). Runs on every platform (no-ops harmlessly when no
poll timer is armed). Per Spec 016 §Polling."})

(defn cancel-poll-timers-handler
  "`:rf.resource/cancel-poll-timers` fx handler: cancels only the `:poll`
  timer for each of `:resource/keys`, leaving stale and GC armed."
  [_ctx {:keys [frame-id] resource-keys :resource/keys}]
  (doseq [rkey resource-keys]
    (cancel! frame-id rkey poll-kind))
  nil)

;; ---- frame teardown -------------------------------------------------------

(defn on-frame-destroyed!
  "The timer half of the `:resources/on-frame-destroyed!` teardown hook, which
  the façade composes with the work-ledger and revalidation-listener releases.
  Returns nil. Runtime-Subsystems §5. Teardown."
  [frame-id]
  (release-frame! frame-id)
  nil)
