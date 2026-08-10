(ns re-frame.hicasso.checkpoint-support
  "THE MICROTASK-CHECKPOINT INSTRUMENT, and the sabotage that proves it
  can go red (rf2-2l17).

  Three namespaces read this file, because all three wait on the same
  deferral — `re-frame.hicasso.impl.collector/invalidate-cell!`'s second
  phase — and waiting on it three different ways is how three answers
  drift apart:

  | reader | the route it reaches the deferral by |
  |---|---|
  | `reincarnation-cells-cljs-test` | frame destruction, at the commit seam |
  | `reincarnation-paint-dom-cljs-test` | frame destruction, in a real browser, where the paint is |
  | `hmr-registry-cljs-test` | a `:sub` re-registration — a developer's save |

  ## What is being waited on, and why a duration cannot express it

  `re-frame.hicasso.impl.collector/invalidate-cell!` drops a retired
  reaction synchronously and rebuilds the durable attachment on a
  **microtask**. Design law React 3 is what makes that the scheduling
  rather than a preference: a render/commit tear must be corrected
  *before visible paint*, and the HTML event loop grants exactly one
  ordering promise of that shape — the microtask checkpoint drains, in
  full, before the *update the rendering* step that may follow the same
  task.

  So the claim under test is not \"the correction arrives quickly\". It is
  **\"the correction arrives without control returning to the event
  loop\"**, and a timer cannot say that: a 30 ms wait is green for a
  correction scheduled a microtask, a macrotask or twenty-nine
  milliseconds away, and those are three different guarantees.

  Reading it the other way round is what makes it a witness rather than a
  wait. A row asserts the tear SYNCHRONOUSLY — the committed value is
  still the predecessor's — and then asserts the correction inside the
  checkpoint. Neither half is interesting alone; together they say the
  runtime had something to fix and fixed it before the event loop could
  paint.

  ## The instrument

  [[drain-checkpoint]] chains promise turns. Every `.then` callback it
  queues is queued *while the microtask queue is still draining*, so the
  whole chain runs inside ONE checkpoint: control never reaches the event
  loop, no task is dequeued, and no rendering opportunity occurs. React's
  own sync-lane flush for a `useSyncExternalStore` notification is
  reachable there for the same reason — React schedules it with
  `queueMicrotask`, and a microtask queued during the drain joins the
  drain.

  A correction scheduled with `setTimeout` is unreachable there at **any**
  budget, because a task cannot run during a checkpoint. The budget is
  therefore a bound on the chain's LENGTH and never on elapsed time, and
  exhausting it is a categorical answer rather than a slow one.

  ## The sabotage

  [[with-macrotask-deferral]] restores the pre-rf2-2l17 scheduling for
  the width of one synchronous form, and restores it for real: the
  collector is unmodified and unaware. It is Evidence law 3's sabotage
  control, and the reason the instrument's green means something."
  (:require [cljs.test :refer-macros [is]]))

;; ---------------------------------------------------------------------------
;; The checkpoint drain
;; ---------------------------------------------------------------------------

(def checkpoint-turn-budget
  "How many promise turns [[drain-checkpoint]] will chain before it gives
  up and answers `nil`.

  **A length, not a duration.** The corrections these suites wait on need
  two or three turns — the collector's own microtask, then React's
  sync-lane flush, then the assimilation turns a resolved-with-a-promise
  chain costs — and nothing legitimate needs sixty-four. The number is
  large enough that a green never depends on counting React's internal
  turns correctly, and finite so that a correction which is not coming
  fails instead of hanging."
  64)

(defn drain-checkpoint
  "Chain promise turns until `ready?` holds. Answers a promise of the
  number of turns it took, or of `nil` when [[checkpoint-turn-budget]] is
  exhausted.

  The turn count is reported for diagnosis, and **is not a vacuity
  check** — a mistake worth recording, because it was made here first and
  red four rows. The drain necessarily spends one turn before its first
  probe, and the collector queues its microtask *before* that, so `0`
  turns is the ordinary healthy answer rather than evidence of a row that
  watched nothing. The vacuity check has to be taken SYNCHRONOUSLY,
  before the checkpoint the row is about; [[at-the-checkpoint]] takes it."
  [ready?]
  (let [!turns (volatile! 0)]
    (letfn [(step [_]
              (cond
                (ready?)                                    @!turns
                (>= (vswap! !turns inc) checkpoint-turn-budget) nil
                :else (.then (js/Promise.resolve) step)))]
      (.then (js/Promise.resolve) step))))

(defn at-the-checkpoint
  "Run `f` once `ready?` holds, without ever letting the task end, then
  finish the `cljs.test/async` block. The commit-seam form of
  [[drain-checkpoint]], and what the three suites' rows actually call.

  **It replaces a 30 ms timer, and the replacement is the point.** A
  duration cannot tell a microtask-deferred repair from a
  macrotask-deferred one — 30 ms was green for both — and after rf2-2l17
  that difference IS the invariant.

  Two failure modes, both loud, and they bracket the checkpoint from
  either side. `ready?` holding **synchronously** means the row watched
  nothing happen, which is the shape a witness silently degrades into.
  The budget running out means the repair is not coming before the
  rendering opportunity, which is the fault the bead exists for."
  [ready? label done f]
  (is (not (ready?))
      (str label " — the condition already held synchronously, before the "
           "checkpoint this row is about; nothing was measured"))
  (-> (drain-checkpoint ready?)
      (.then (fn [turns]
               (is (some? turns)
                   (str label " — not repaired within the microtask "
                        "checkpoint (" checkpoint-turn-budget
                        " promise turns). A correction deferred to a later "
                        "task cannot be reached from inside one, which is "
                        "the ordering React 3 forbids."))
               (f turns)))
      (.catch (fn [e] (is false (str label " — " (.-message e)))))
      (.then (fn [_] (done)))))

;; ---------------------------------------------------------------------------
;; The sabotage
;; ---------------------------------------------------------------------------

(defn with-macrotask-deferral
  "Run `f` with `js/queueMicrotask` routed through `setTimeout 0` — the
  scheduling `invalidate-cell!` had before rf2-2l17 — and restore the
  global afterwards, whatever `f` does.

  **It has no collateral, and that is checkable rather than hoped for.**
  React 19 binds `scheduleMicrotask = queueMicrotask` *by value* when
  `react-dom` evaluates (`react-dom-client.development.js`, the
  `scheduleMicrotask =` initialiser), so React keeps the original
  function however this rebinds the global, and its sync-lane flush stays
  a microtask. Inside the window the only other caller is the collector,
  because the window is one synchronous form. What the sabotage moves is
  therefore exactly the deferral under test and nothing else."
  [f]
  (let [original (.-queueMicrotask js/globalThis)]
    (set! (.-queueMicrotask js/globalThis)
          (fn [cb] (js/setTimeout cb 0) js/undefined))
    (try (f)
         (finally (set! (.-queueMicrotask js/globalThis) original)))))

;; ---------------------------------------------------------------------------
;; Lane
;; ---------------------------------------------------------------------------

(defn leave-act-environment!
  "React's `act` queue is not the browser's scheduler, and a paint-order
  reading taken inside it would be a reading of `act`'s ordering. Set
  outright rather than imported from the bench tree, which the freeze
  gate forbids this package to `:require`; `roots-frames-support` carries
  the same line for the same reason."
  []
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  nil)
