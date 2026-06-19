(ns re-frame.managed-timer
  "Framework-internal shared kernel for the advisory managed-timer skeleton
  (rf2-7x2lky).

  ## What this is

  Two feature artefacts independently arm host-clock timers over the Spec
  005 §Clock-abstraction primitive (`re-frame.interop/schedule-after!` /
  `cancel-scheduled!`):

  - `re-frame.machines.timer` — the state-machine `:after` timers, keyed by
    `{:parent :spawn :delay}` in a per-frame-nested table whose rich entry
    value carries the host handle PLUS the sub-reaction, watcher key,
    resolved-ms, epoch, state, and delay-source; cancellation emits the
    `:rf.machine.timer/cancelled` reply-envelope trace and tears down the
    dynamic-delay subscription watcher.
  - `re-frame.resources.timers` — the resource STALE / GC / POLL timers,
    keyed by `[frame-id resource-key kind]` in a flat table whose entry
    value is the bare host handle; cancellation is silent.

  Both share the SAME low-level arm step: schedule a thunk after a delay
  via `interop/schedule-after!`, but ONLY when the delay is a positive
  number (a non-positive / nil delay never arms a timer — the resource that
  declared no policy, the machine whose dynamic delay resolved to nothing).
  That guarded-arm step is the genuinely-common, divergence-free kernel;
  it lives here once so the two call sites can never drift on the
  positive-delay rule or the `interop` plumbing.

  ## What deliberately stayed split (rf2-7x2lky)

  The bead asked whether the whole advisory-timer skeleton could collapse
  into one helper. It cannot — and forcing it would lose load-bearing
  behaviour AND break the two test suites that assert the exact table
  shapes. The pieces that stayed in each artefact, with why:

  - **The side-table itself.** The machines table is per-frame-NESTED
    (`{frame-id {inner-key entry}}`) with a RICH entry map; the resources
    table is FLAT (`{[frame-id rkey kind] handle}`) with a BARE handle.
    Both shapes are asserted directly by their artefact's tests
    (`@timer/after-timers` outer-frame-id `contains?` vs
    `@timers/timer-table` flat-key `contains?`). One shared atom would have
    to pick a shape and break the other.

  - **Cancel-then-arm orchestration + cancellation.** Machines cancels a
    whole rich entry (release the sub-reaction watcher, unsubscribe, emit
    the `:rf.machine.timer/cancelled` reply-envelope trace stamped with a
    closed `:reason`) and SWALLOWS host-clock throws; resources cancels a
    bare handle SILENTLY and does NOT swallow. The two cancel disciplines
    are genuinely different observable behaviour — neither may adopt the
    other's.

  - **The advisory re-check / generation-epoch guard.** This is the
    load-bearing correctness boundary and it is per-artefact: machines
    guards on a per-decl-path EPOCH read from the durable machine snapshot
    (with per-region hierarchy resolution) and re-resolves on a
    subscription-delay change; resources guards in the FIRED-EVENT HANDLER
    on the durable entry's timestamps / owner-set / generation. They share
    only the PRINCIPLE (the timer is an advisory nudge; re-check live
    durable facts on fire), not a code path.

  - **Delay resolution.** Machines resolves a literal / subscription-vector
    / fn delay (with subscription bookkeeping + fn-throw tracing); resources
    receives an already-resolved positive-int ms. Only machines has this
    layer.

  So this kernel is intentionally small: the positive-delay-guarded arm,
  plus a best-effort cancel helper for the swallow-throws callers (machines)
  to share.

  ## NOT a public surface

  This is framework-internal plumbing under `re-frame.*`, allowed in core
  and bundle-isolated like the rest of core. It is NOT a `re-frame.core`
  façade export, and it is NOT the DEFERRED public `:rf.timer/after` surface
  (rf2-5wuikc) nor the test-only `re-frame.timer-probe` managed-effect
  conformance instance. Those are distinct things; this just de-duplicates
  the host-clock arm step the two shipped timer side-tables share."
  (:require [re-frame.interop :as interop]))

#?(:clj (set! *warn-on-reflection* true))

(defn positive-delay?
  "True when `delay-ms` is a positive number — the single rule both timer
  artefacts apply before arming a host-clock timer. A nil / non-number /
  non-positive delay never arms (the resource that declared no policy for
  that kind; the machine whose dynamic / fn delay resolved to nothing).
  Pure."
  [delay-ms]
  (and (number? delay-ms) (pos? delay-ms)))

(defn arm!
  "Schedule `thunk` to fire after `delay-ms` via the Spec 005
  §Clock-abstraction primitive (`re-frame.interop/schedule-after!`), but
  ONLY when `delay-ms` is a positive number. Returns the opaque host handle
  when armed, or nil when the delay is non-positive / nil / not a number (no
  timer is created — the caller arms nothing for that kind).

  This is the genuinely-common, divergence-free kernel the machines `:after`
  and the resources stale / GC / poll timers share (rf2-7x2lky). It owns the
  positive-delay rule and the `interop` plumbing once; each caller keeps its
  own side-table bookkeeping, cancel-then-arm orchestration, cancellation
  tracing, and advisory re-check (see the namespace docstring for what
  stayed split and why)."
  [thunk delay-ms]
  (when (positive-delay? delay-ms)
    (interop/schedule-after! thunk delay-ms)))

(defn cancel!
  "Best-effort cancel of a host-clock `handle` via
  `re-frame.interop/cancel-scheduled!`, SWALLOWING any throw. A nil handle is
  a no-op. Returns nil.

  Shared by the callers that release host handles defensively (the machines
  `:after` table, which tolerates a partial-state entry whose handle is
  nil / already cleared). Resources cancels its bare handle directly without
  swallowing — a deliberately different discipline — so it does NOT route
  through here."
  [handle]
  (when handle
    (try (interop/cancel-scheduled! handle)
         (catch #?(:clj Throwable :cljs :default) _ nil)))
  nil)
