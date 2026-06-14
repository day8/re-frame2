(ns re-frame.resources.timers
  "The resource STALE / GC timer side-table substrate (rf2-nbjewi, EP-0003
  slice 6). Per Spec 016 §Stale and GC scheduling.

  `:stale-after-ms` and `:gc-after-ms` are v1 features, so their scheduling
  is v1. The scheduling discipline (Spec 016 §Stale and GC scheduling, MUST):

  - **freshness is computed from DURABLE timestamps** (`:loaded-at` /
    `:stale-at`), NOT from trusting a timer fired exactly on time — a timer
    is only an advisory nudge; the freshness fact lives on the entry
    (`re-frame.resources.subs/stale?` reads `:invalidated-at` / `:stale-at`
    against the live clock);
  - **a stale timer may enqueue a resource event, but the handler MUST
    re-check the current entry before writing** — the timer thunk dispatches
    a re-checking internal event (`:rf.resource.internal/stale-fired` /
    `:rf.resource.internal/gc-fired`); the handler verifies the entry /
    owners / generation against the LIVE durable facts and writes nothing
    when they have moved on (a superseded reschedule, a re-load, an owner
    re-attach);
  - **inactive GC re-checks owner sets + entry generation after wake** — a
    GC timer removes the entry ONLY if it is still owner-free and not in
    flight (the `gc-fired-handler` re-check);
  - **timers + host handles live in side tables, NOT in frame-state** — this
    module-level `timer-table` mirrors the work-ledger `handle-table` and the
    host-side generation allocator (`re-frame.resources.state/generation-cache`):
    a transient host cache an epoch restore cannot rewind, never on the
    SSR / hydration / epoch wire, cleared per-frame on frame destroy;
  - **frame destroy cancels all resource timers for that frame** — wired
    into the SINGLE `:resources/on-frame-destroyed!` teardown hook the façade
    publishes (composed with the work-ledger + generation host-cache release;
    one hook, no second teardown path — rf2-afpdkn established it);
  - **a hidden tab can delay timers without corrupting correctness** — the
    timer is advisory; the re-check against durable timestamps makes a late /
    coalesced / never-fired timer harmless. The focus/reconnect active-stale
    scan (rf2-vtblcq, `re-frame.resources.revalidate-listeners` +
    `re-frame.resources.events/window-focused-handler` /
    `network-reconnected-handler`) is the public-beta belt-and-braces — a
    separate slice composed off the SAME `:resources/on-frame-destroyed!`
    teardown hook this timer side table uses.

  ## Two halves (mirrors the work-ledger split)

  - **Durable freshness facts** ride the entry (`:loaded-at` / `:stale-at` /
    `:invalidated-at` / `:active-owners` / `:generation`) in runtime-db —
    serializable, on the SSR / hydration / epoch wire.
  - **Host timer handles** live HERE, keyed by `[frame-id resource-key kind]`
    (`kind` ∈ `#{:stale :gc}`) → an opaque host handle from
    `re-frame.interop/schedule-after!`. NOT runtime-db, NOT serialized.

  ## Scheduling is host-side, so it rides an fx (mirrors commit-generation)

  The runtime emits `:rf.resource/schedule-timers` from the success reply
  handler once an entry settles `:loaded` with a `:loaded-at` (so the timers
  arm against the durable load timestamp). It carries a `:server?` flag read
  from the cascade frame's platform; on the SERVER the fx is a no-op (SSR
  uses the blocking-drain wait point + lazy client-side revalidation, never
  wall-clock background timers — mirrors the machine `:after`
  `:skipped-on-server` posture). The skip is by the FRAME's platform, not a
  `:platforms` registration gate, because the host-wide platform default is
  `:server` and a JVM client-mode unit test must still arm timers. A re-load
  reschedules (cancel-then-arm) so a single live timer per `[key kind]`
  survives."
  (:require [re-frame.identity :as identity]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- timer kinds ----------------------------------------------------------

(def stale-kind
  "The `:stale` timer kind — arms `:stale-after-ms` after `:loaded-at`; on
  fire it nudges a re-checking `:rf.resource.internal/stale-fired` event
  whose handler re-derives staleness from durable timestamps (the timer is
  advisory). Per Spec 016 §Stale and GC scheduling."
  :stale)

(def gc-kind
  "The `:gc` timer kind — arms `:gc-after-ms` after `:loaded-at`; on fire it
  nudges a re-checking `:rf.resource.internal/gc-fired` event whose handler
  removes the entry ONLY if it is still owner-free + not in flight. Per Spec
  016 §Stale and GC scheduling."
  :gc)

;; ---- reserved internal re-check event ids ---------------------------------

(def stale-fired-event
  "The internal re-check event a fired stale timer dispatches. The handler
  re-checks the live durable entry before writing (the timer is advisory).
  User code MUST NOT dispatch it. Per Spec 016 §Stale and GC scheduling."
  :rf.resource.internal/stale-fired)

(def gc-fired-event
  "The internal re-check event a fired GC timer dispatches. The handler
  re-checks owner sets + generation before removing. User code MUST NOT
  dispatch it. Per Spec 016 §Stale and GC scheduling."
  :rf.resource.internal/gc-fired)

;; ---- host-side timer side table (Spec 016 §Stale and GC scheduling) -------
;;
;; Module-level transient host cache keyed by `[frame-id resource-key kind]`
;; → an opaque host timer handle. NOT runtime-db, NOT serialized, off the
;; epoch / SSR egress wire. Mirrors the work-ledger `handle-table` and the
;; host-side generation allocator. Cleared per-frame on frame destroy.

(defonce
  ^{:doc "Host-side side table of NON-serializable stale / GC timer handles,
   keyed by `[frame-id resource-key kind]` (`kind` ∈ `#{:stale :gc}`) → an
   opaque host handle from `re-frame.interop/schedule-after!`. Transient host
   state (NOT runtime-db), so an epoch restore cannot rewind / recycle it,
   and it never rides the SSR / hydration / epoch wire — freshness is
   re-derived from the durable entry timestamps, and a timer is only an
   advisory nudge whose handler re-checks the live facts. Cleared per-frame
   on frame destroy (`release-frame!`). Per Spec 016 §Stale and GC
   scheduling / [Runtime-Subsystems] clause 5."}
  timer-table
  (atom {}))

(defn- timer-key
  "The side-table key for `[frame-id resource-key kind]`. rf2-9e0tyq: the
  `resource-key` is reduced to its CEDN-1 byte identity (`canonical-bytes`)
  so a list- and a vector-params resource never share a timer slot (the
  Clojure-`=` map-key collapse the cache-key re-keying closes). The dispatched
  recheck event still carries the kind-preserving scoped-key VECTOR; only the
  host side-table key is byte-reduced."
  [frame-id resource-key kind]
  [frame-id (identity/canonical-bytes resource-key) kind])

(defn cancel!
  "Cancel + drop the host timer for `[frame-id resource-key kind]` (a no-op
  when none is armed). Idempotent. Returns nil. The durable freshness facts
  are untouched (a cancelled timer never means an entry became fresh — only
  that the advisory nudge will not fire). Per Spec 016 §Stale and GC
  scheduling."
  [frame-id resource-key kind]
  (let [k (timer-key frame-id resource-key kind)]
    (when-let [handle (get @timer-table k)]
      (interop/cancel-scheduled! handle))
    (swap! timer-table dissoc k))
  nil)

(defn cancel-for-key!
  "Cancel BOTH the stale + GC timers for a resource `[frame-id resource-key]`
  (used on entry removal — remove / clear-scope / GC). Idempotent. Returns
  nil."
  [frame-id resource-key]
  (cancel! frame-id resource-key stale-kind)
  (cancel! frame-id resource-key gc-kind)
  nil)

(defn- dispatch-recheck!
  "Schedule the re-checking internal event for a fired timer of `kind`. The
  thunk fires through the published `:router/dispatch!` late-bind hook (the
  resources artefact never statically `:require`s the router) stamping
  `:source :after-timer` so Xray's timeline + the Epoch panel label the
  timer-fired cascade, and `:frame frame-id` so it lands in the right frame.
  The event handler does the DURABLE re-check — the timer never writes
  itself. No-op when no dispatcher is bound (a stripped runtime)."
  [frame-id resource-key kind]
  (when-let [dispatch! (late-bind/get-fn :router/dispatch!)]
    (let [event-id (if (= kind stale-kind) stale-fired-event gc-fired-event)]
      (dispatch! [event-id {:resource/key resource-key}]
                 {:frame frame-id :source :after-timer}))))

(defn schedule!
  "Arm a host timer of `kind` for `[frame-id resource-key]` to fire in
  `delay-ms`. Cancel-then-arm: any prior timer for the same `[key kind]` is
  cancelled first, so a re-load reschedules rather than accumulating zombie
  timers (the machine `:after` cancel-and-reschedule discipline). On fire the
  timer dispatches the re-checking internal event (`dispatch-recheck!`) — the
  HANDLER re-checks the durable entry before writing (the timer is advisory).
  No-op for a non-positive `delay-ms` (a resource declaring no policy never
  arms that kind). Returns the host handle (or nil when not armed). Per Spec
  016 §Stale and GC scheduling."
  [frame-id resource-key kind delay-ms]
  ;; cancel any prior timer for this [key kind] (reschedule, not accumulate)
  (cancel! frame-id resource-key kind)
  (when (and (number? delay-ms) (pos? delay-ms))
    (let [handle (interop/schedule-after!
                   (fn [] (dispatch-recheck! frame-id resource-key kind))
                   delay-ms)]
      (swap! timer-table assoc (timer-key frame-id resource-key kind) handle)
      handle)))

(defn release-frame!
  "Cancel + drop EVERY stale / GC timer for a destroyed `frame-id` from the
  side table. Invoked from the single `:resources/on-frame-destroyed!`
  teardown hook (composed in the façade with the work-ledger host-handle +
  generation host-cache release — one hook, no second teardown path). The
  durable entries ride the frame value and are released atomically when the
  frame is dropped; this touches ONLY the host timer side table. Idempotent.
  Per Spec 016 §Stale and GC scheduling (frame destroy cancels all resource
  timers) / [Runtime-Subsystems] clause 5. Returns nil."
  [frame-id]
  (doseq [[[fid rkey kind] handle] @timer-table]
    (when (= fid frame-id)
      (interop/cancel-scheduled! handle)
      (swap! timer-table dissoc [fid rkey kind])))
  nil)

(defn reset-cache!
  "Cancel + drop EVERY frame's stale / GC timers (test isolation). Published
  via the resources test-support reset hook so the shared CLJS
  `make-reset-runtime-fixture` clears it per test (host-side transient state,
  NOT cleared by the runtime / frames reset). Returns nil."
  []
  (doseq [[_ handle] @timer-table]
    (interop/cancel-scheduled! handle))
  (reset! timer-table {})
  nil)

;; ---- the host-side scheduling fx (mirrors :rf.resource/commit-generation) -
;;
;; The timer side table is host-side transient state, so its WRITES ride an
;; fx (not a runtime-db effect) — exactly as the host-side generation
;; high-water bump rides `:rf.resource/commit-generation` and the work-handle
;; side-table writes ride `:rf.resource/record-work-handle`. The success
;; reply handler emits `:rf.resource/schedule-timers` once an entry settles
;; `:loaded`, arming the stale + GC timers from the entry's DURABLE
;; `:loaded-at` + the resource's `:stale-after-ms` / `:gc-after-ms` policy.

(def schedule-timers-meta
  "Metadata for the `:rf.resource/schedule-timers` fx registration. The
  WRITE half of the host-side stale / GC timer side table — arms the
  advisory timers for `[frame-id resource-key]` from the durable load
  timestamp + policy. SKIPPED under SSR via the carried `:server?` flag
  (mirrors the machine `:after` skip-on-server), so the timer table never
  arms a wall-clock timer on a server render — NOT via a `:platforms` gate,
  because a JVM CLIENT-mode runtime (the resources unit tests) must still arm
  timers, and the host-wide platform default is `:server`. fx handlers are
  binary `(fn [ctx args] …)` (Spec 002)."
  {:doc "Arm the host-side stale / GC timers for a settled resource entry,
keyed by `[frame-id resource-key kind]`. Args:
`{:frame-id … :resource/key … :stale-delay-ms … :gc-delay-ms … :server? …}`.
Emitted by the success reply handler once an entry settles `:loaded` (the
delays are derived from the durable `:loaded-at` + the resource's
`:stale-after-ms` / `:gc-after-ms`; `:server?` is read from the cascade
frame's platform). Cancel-then-arm — a re-load reschedules. No-op under
`:server? true` (SSR uses the blocking-drain wait point + lazy client
revalidation, never wall-clock background timers). The fired timer dispatches
a re-checking internal event; the handler re-checks the durable entry before
writing (the timer is advisory). Per Spec 016 §Stale and GC scheduling."})

(defn schedule-timers-handler
  "`:rf.resource/schedule-timers` fx handler. Arms the host-side stale + GC
  timers for `[frame-id resource-key]` from the supplied durable delays. A
  non-positive / nil delay leaves that kind disarmed (a resource declaring no
  `:stale-after-ms` / `:gc-after-ms` arms only the other / neither). No-op
  under SSR (`:server? true`) — a server render never arms a wall-clock
  background timer. Emits a `:rf.resource/gc-scheduled` / `:rf.resource/stale-
  scheduled` trace (gc-class — the Xray lifecycle timeline pairs them with
  `:rf.resource/gc-fired` / `gc-skipped` / `stale-fired`) when a timer arms.
  Per Spec 016 §Stale and GC scheduling."
  [_ctx {:keys [frame-id stale-delay-ms gc-delay-ms server?] resource-key :resource/key}]
  (when-not server?
    (let [stale-h (schedule! frame-id resource-key stale-kind stale-delay-ms)
          gc-h    (schedule! frame-id resource-key gc-kind gc-delay-ms)]
      (when gc-h
        (trace/emit! :rf.event :rf.resource/gc-scheduled
                     {:rf.frame/id frame-id :resource/key resource-key
                      :delay-ms gc-delay-ms}))
      (when stale-h
        (trace/emit! :rf.event :rf.resource/stale-scheduled
                     {:rf.frame/id frame-id :resource/key resource-key
                      :delay-ms stale-delay-ms}))))
  nil)

;; ---- the cancel fx (entry removed — remove / clear-scope / GC) ------------

(def cancel-timers-meta
  "Metadata for the `:rf.resource/cancel-timers` fx registration. Cancels
  the host-side stale + GC timers for one or more removed resource entries
  (their durable facts are gone, so an advisory nudge would no-op anyway —
  but the host handles must be released so they don't leak)."
  {:doc "Cancel the host-side stale / GC timers for removed resource entries,
keyed by `[frame-id resource-key]`. Args:
`{:frame-id … :resource/keys [<scoped-key> …]}`. Emitted when entries are
removed (`:rf.resource/remove` / `:rf.resource/clear-scope` / a fired GC) so
their advisory timer handles are released promptly rather than waiting for
frame destroy. Runs on every platform (a server render with no armed timers
no-ops harmlessly; cancellation must never be platform-gated). Per Spec 016
§Stale and GC scheduling."})

(defn cancel-timers-handler
  "`:rf.resource/cancel-timers` fx handler. Cancels BOTH timers for every
  supplied `[frame-id resource-key]`. Args: `{:frame-id … :resource/keys
  […]}`."
  [_ctx {:keys [frame-id] resource-keys :resource/keys}]
  (doseq [rkey resource-keys]
    (cancel-for-key! frame-id rkey))
  nil)

;; ---- frame teardown (Spec 016 [Runtime-Subsystems] clause 5) --------------

(defn on-frame-destroyed!
  "The stale / GC timer half of the `:resources/on-frame-destroyed!`
  teardown body. `destroy-frame!` invokes the single composed hook by key
  with the destroyed `frame-id`; the façade composes THIS with the
  work-ledger host-handle release + the generation host-cache release (one
  hook, no second teardown path). Cancels every stale / GC timer for the
  frame (`release-frame!`). Per Spec 016 §Stale and GC scheduling / [Runtime-
  Subsystems] clause 5. Returns nil."
  [frame-id]
  (release-frame! frame-id)
  nil)
