(ns day8.re-frame2-causa.sensitive-trace-loop-cljs-test
  "Loop-proof tests for the `:sensitive?` trace-callback path
  (rf2-nk01x → rf2-qsjda).

  ## Why this file exists

  Causa registers `trace-bus/collect-trace!` as the
  `:rf.causa/trace-collector` callback at preload time. Whenever a
  `:sensitive?` trace event arrives, `collect-trace!` calls
  `config/note-suppressed!`, which itself dispatches
  `:rf.causa/note-sensitive-suppressed` into the `:rf/causa` frame so
  the reactive `[● REDACTED N]` indicator updates on the standard
  app-db-write path (rf2-0vxdn).

  Before rf2-nk01x's fix, that dispatch was the root of an infinite
  loop:

    1. The dispatch is queued via `dispatch!`, which synchronously
       calls `emit-dispatched-trace!`.
    2. `emit-dispatched-trace!` lands inside `emit!` while the framework's
       `*current-sensitive?*` Var (re-frame.trace, rf2-isdwf) is still
       bound TRUE (the outer sensitive handler's scope hasn't unwound
       yet — the callback iterates listeners during the outer emit).
    3. `emit!` hoists `:sensitive? true` onto the `:event/dispatched`
       trace event for `:rf.causa/note-sensitive-suppressed` itself.
    4. `collect-trace!` sees that event, calls `note-suppressed!` again,
       dispatches again, … until the JavaScript stack overflows or the
       framework's drain-depth limit truncates the cascade.

  The fix evolved:

    - rf2-nk01x landed a Causa-side `self-emitted?` predicate that
      short-circuited `collect-trace!` for bookkeeping self-emits.
    - rf2-qsjda promoted the opt-out to the framework: the bookkeeping
      handlers carry `:rf.trace/no-emit? true` in their registration
      metadata, and `re-frame.trace/emit!` / `emit-error!` /
      `emit-dispatched-trace!` short-circuit on the flag (Spec 009
      §Trace-emission opt-out). The collector never sees self-emits
      because the framework never emits them.

  This CLJS file drives the END-TO-END loop scenario through real
  `dispatch-sync` calls against the registered trace callback. The
  framework-level gate's pure-data tests live in
  `re-frame.trace-test`.

  ## What the tests assert

    - A single sensitive `dispatch-sync` completes cleanly (no
      stack overflow, no `:rf.error/drain-depth-exceeded` trace).
    - 200 sensitive dispatches complete cleanly and the
      suppressed-counter advances in lockstep — i.e. the per-dispatch
      fan-out under sensitive scope is stable (no run-away
      multiplication from re-entrant counter bumps).
    - The non-sensitive-mirror loop is also closed —
      `:rf.causa/note-trace-event` does not re-enter the collector via
      its own `:event/dispatched` trace.
    - Tests run WITHOUT a per-fixture `trace/clear-trace-cbs!`
      workaround. The Causa callback is registered exactly as the
      production preload installs it."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.preload :as preload]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------
;;
;; `reset-runtime-fixture` snapshots/restores the registrar around each
;; test, clears trace listeners, and disposes the substrate adapter. The
;; init-fn flips Causa's idempotency sentinels, re-registers the trace
;; collector (so each test runs against the SAME wiring the production
;; preload installs), and clears the per-process buffer + counter +
;; flag so each test starts from the baseline.

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (registry/register-causa-handlers!)
  ;; Allocate the :rf/causa frame so `note-suppressed!`'s dispatch
  ;; guard passes. Without the frame, `note-suppressed!` skips the
  ;; dispatch entirely — which would mask the loop scenario.
  (frame/reg-frame :rf/causa {})
  ;; Re-install the trace collector. The fixture above cleared it.
  (preload/register-trace-collector!)
  (trace-bus/clear-buffer!)
  (config/reset-suppressed-count!)
  (config/set-show-sensitive! false))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

;; ---- helpers ------------------------------------------------------------

(defn- register-sensitive-event! []
  (rf/reg-event-db :test/sensitive-bump
    ;; Registration metadata map — `:sensitive? true` flows through
    ;; the registrar's meta-merge and the runtime hoists it onto every
    ;; trace event emitted inside this handler's scope (rf2-isdwf).
    {:sensitive? true}
    (fn [db [_ n]]
      (assoc db :test/last-bump n))))

(defn- register-non-sensitive-event! []
  (rf/reg-event-db :test/plain-bump
    (fn [db [_ n]]
      (assoc db :test/last-bump n))))

(defn- drain-depth-exceeded?
  "True iff Causa's trace buffer contains a `:rf.error/drain-depth-
  exceeded` event. The framework's drain-depth limit terminates a
  runaway cascade; this is the signal that the loop wasn't contained."
  []
  (boolean
    (some (fn [ev] (= :rf.error/drain-depth-exceeded (:operation ev)))
          (trace-bus/buffer))))

;; ---- (1) single dispatch — no loop --------------------------------------

(deftest single-sensitive-dispatch-does-not-loop
  (testing "one :sensitive? dispatch lands cleanly under the registered
            trace-cb — no stack overflow, no drain-depth-exceeded, and
            the suppressed-counter advances deterministically (NOT
            zero, NOT capped at the framework's drain-depth limit)"
    (register-sensitive-event!)
    (rf/dispatch-sync [:test/sensitive-bump 0])
    (is (not (drain-depth-exceeded?))
        "no drain-depth-exceeded — the cb-dispatch loop is closed")
    (is (pos? (config/suppressed-count))
        "the counter advanced — sensitive trace events DID fire and
         DID hit the privacy gate")))

;; ---- (2) 200 dispatches — count is proportional, not capped ------------

(deftest two-hundred-sensitive-dispatches-scale-linearly
  (testing "firing 200 :sensitive? dispatches advances the counter
            proportionally — pre-fix the loop would terminate every
            cascade at the framework's drain-depth limit (~100
            dispatches) and inflate the counter inconsistently;
            post-fix the per-dispatch fan-out is stable"
    (register-sensitive-event!)
    ;; Baseline: count the per-dispatch fan-out under sensitive scope.
    (rf/dispatch-sync [:test/sensitive-bump :baseline])
    (let [per-dispatch (config/suppressed-count)]
      (is (pos? per-dispatch)
          "sensitive cascade emits at least one suppressible trace event")
      ;; Reset and fire 200 more. The expected total is
      ;; `per-dispatch * 200`. The actual fan-out is stable (the
      ;; cascade shape doesn't change between dispatches — same handler,
      ;; same fx, same db-changed sequence).
      (config/reset-suppressed-count!)
      (dotimes [n 200]
        (rf/dispatch-sync [:test/sensitive-bump n]))
      (is (not (drain-depth-exceeded?))
          "no drain-depth-exceeded across 200 dispatches — the loop is
           closed for every cascade, not just the first")
      (is (= (* 200 per-dispatch) (config/suppressed-count))
          "counter = 200 × per-dispatch fan-out — EXACT, no inflation
           from re-entrant counter bumps and no truncation from
           drain-depth-exceeded"))))

;; ---- (3) non-sensitive mirror loop is also closed ----------------------

(deftest two-hundred-non-sensitive-dispatches-do-not-loop
  (testing "non-sensitive trace events flow into the buffer cleanly.
            Per rf2-e9s81 `collect-trace!` only swaps the buffer-state
            atom (no follow-on dispatch), so there is no
            `:rf.causa/note-trace-event` self-emit loop to close in
            the first place — the buffer fills purely from the
            host's own dispatches."
    (register-non-sensitive-event!)
    (dotimes [n 200]
      (rf/dispatch-sync [:test/plain-bump n]))
    (is (not (drain-depth-exceeded?))
        "no drain-depth-exceeded across 200 dispatches")
    ;; The buffer contains many trace events per dispatch (event/
    ;; dispatched, event/handled, event/db-changed, event/do-fx, ...)
    ;; — we don't assert an exact count, just that the runtime
    ;; survived all 200 dispatches.
    (is (pos? (count (trace-bus/buffer)))
        "buffer received the trace events from 200 plain dispatches")))

;; ---- (4) opted-in pass-through stays loop-proof ------------------------

(deftest opted-in-sensitive-dispatches-also-loop-proof
  (testing "with `:trace/show-sensitive? true` sensitive events flow
            into the buffer instead of bumping the counter — the
            cascade stays bounded because the collector only swaps
            the buffer-state atom (rf2-e9s81 — no follow-on
            dispatch to re-enter through)"
    (config/configure! {:trace/show-sensitive? true})
    (register-sensitive-event!)
    (dotimes [n 50]
      (rf/dispatch-sync [:test/sensitive-bump n]))
    (is (not (drain-depth-exceeded?))
        "opted-in path is also loop-proof")
    (is (zero? (config/suppressed-count))
        "the counter does NOT advance when show-sensitive? is true")
    (is (pos? (count (trace-bus/buffer)))
        "sensitive trace events DID land in the buffer (pass-through
         mode)")))
