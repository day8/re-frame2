(ns re-frame.flows-first-registration-trace-incarnation-test
  "Exact-incarnation fence for the FIRST-registration flow trace
  THROUGH the synchronous trace-emit callback pipeline (classification
  projection → epoch capture → ordered tooling listeners).

  The first-time `:rf.flow/registered` evidence sits behind ONE exact-owner
  postcheck, so a loss during the PRECEDING mark write withholds the trace;
  the replacement shares that postcheck, and
  `re-frame.flows-clear-reg-watch-incarnation-test` pins it. But that
  postcheck only proves A is live at the instant emission
  STARTS. `trace/emit!` is itself a callback-bearing pipeline whose stages
  recheck ownership ONLY while a continuation predicate is installed
  (`trace/continuation-live?` reads the always-true default otherwise). The
  event router installs an exact-owner predicate for the reserved-effect
  `:rf.fx/reg-flow` route, but a DIRECT cold `reg-flow` does not. So with the
  default always-true continuation, A's first-registration emit could START
  while A is live, then an ordered trace LISTENER (or the epoch-capture
  callback) could destroy A and publish same-id B, and every SUBSEQUENT
  listener would still receive A's incarnation-less `:rf.flow/registered`
  after B owns the bare id (and later policy/capture could observe B).

  `reg-flow` therefore wraps that emit in `trace/call-with-continuation-predicate`
  bound to A's pinned incarnation, so the trace pipeline is fenced to A: the
  already-entered delivery (the listener that destroys A) stands once, and
  every LATER listener / capture / policy stage is suppressed the instant A's
  exact ownership is lost.

  The seam here is DELIBERATELY the trace-internal listener boundary, not the
  mark write: A's flow declares NO output marks, so `reg-flow` reaches
  `trace/emit!` with A fully live and the ONLY callback seam is the ordered
  listener fan-out inside emission — the boundary the mark-write watch tests
  (which lose A during a preceding mark write, before emission starts, with
  a passive recorder) cannot reach. Removing the `call-with-continuation-
  predicate` wrapper (leaving the always-true default) makes the subsequent
  listener receive A's stale event and the focused assertion fail.

  The whole scenario runs SYNCHRONOUSLY on the single JVM test thread: the
  destroyer listener destroys A and publishes B reentrantly inside emission, so
  the cross-incarnation ordering is deterministic without threads. Listener
  fan-out order is insertion order (the array-map the listener registry holds),
  which is exactly the delivery order the fence's between-listener recheck is
  defined against — the destroyer is registered FIRST so it is the
  already-entered delivery and the observer is the SUBSEQUENT one."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ---------------------------------------------------------------------------
;; The trace-internal boundary: A's first-registration emit reaches ordered
;; tooling listeners with A live; the FIRST listener destroys A and publishes
;; same-id B; the SUBSEQUENT listener must NOT receive A's stale
;; :rf.flow/registered after B owns the bare id.
;; ---------------------------------------------------------------------------

(deftest first-registration-trace-listener-loss-fences-subsequent-listeners
  ;; Registering A's first flow (NO output marks)
  ;; reaches `trace/emit!` with A live. The destroyer listener — the
  ;; already-entered delivery — destroys A and publishes same-id B mid-fan-out.
  ;; Were the emit to run under the always-true continuation, the
  ;; observer (the subsequent listener) would receive A's incarnation-less
  ;; :rf.flow/registered after B owned the id; the pinned-A
  ;; continuation predicate suppresses every listener past the loss.
  (let [id               :flow.trace.fence/subject
        a-flow-id        :flow.trace.fence/a
        b-flow-id        :flow.trace.fence/b
        destroyer-hits   (atom 0)
        observer-a-regs  (atom [])       ;; A's :rf.flow/registered the SUBSEQUENT listener saw
        observer-regs    (atom [])       ;; every :rf.flow/registered the observer saw
        armed?           (atom true)
        b-token          (atom nil)
        b-flow-registry  (atom ::unset)
        b-commit         (atom ::unset)
        b-sensitive      (atom ::unset)
        b-trace-disabled (atom ::unset)]
    (rf/make-frame {:id id})
    ;; Listener 1 (registered FIRST → fans out FIRST): the DESTROYER. On A's
    ;; own :rf.flow/registered — the already-entered delivery — it destroys A
    ;; and publishes same-id B, exactly once (one-shot CAS), then snapshots B's
    ;; stores for the byte-identical assertions. This is the trace-internal loss
    ;; the mark-write seam cannot reach.
    (rf.trace.tooling/register-listener!
      ::destroyer
      (fn [ev]
        (when (and (= :rf.flow/registered (:operation ev))
                   (= id (get-in ev [:tags :frame]))
                   (compare-and-set! armed? true false))
          (swap! destroyer-hits inc)
          (rf.frame/destroy-frame! id)
          (rf/make-frame {:id id})
          (reset! b-token (rf.frame/frame-incarnation-token id))
          (reset! b-flow-registry (get (rf.flows.registry/flows-snapshot) id ::none))
          (reset! b-commit (rf.frame/frame-commit-epoch id))
          (reset! b-sensitive (rf.elision/sensitive-declarations id))
          (reset! b-trace-disabled (rf.trace/frame-trace-disabled? id)))))
    ;; Listener 2 (registered SECOND → fans out AFTER the destroyer): the
    ;; SUBSEQUENT observer. Absent the fence it receives A's stale
    ;; :rf.flow/registered after B owns the bare id; the fence suppresses it.
    (rf.trace.tooling/register-listener!
      ::observer
      (fn [ev]
        (when (= :rf.flow/registered (:operation ev))
          (swap! observer-regs conj ev)
          (when (= a-flow-id (get-in ev [:tags :flow-id]))
            (swap! observer-a-regs conj ev)))))
    (try
      ;; A's FIRST-TIME registration — NO output marks, so `reg-flow` reaches
      ;; `trace/emit!` with A fully live; the only callback seam is the ordered
      ;; listener fan-out inside emission.
      (is (= a-flow-id
             (rf/reg-flow a-flow-id
               {:frame id :inputs [[:an]] :output-path [:aout]}
               (fn [n] (or n 0))))
          "reg-flow returns its flow-id even though A's owner was lost mid-emit")
      ;; The destroyer received A's registered event exactly once — the
      ;; already-entered delivery that may stand.
      (is (= 1 @destroyer-hits)
          "the destroyer listener received A's :rf.flow/registered once — the
           already-entered delivery stands")
      (is (some? @b-token) "the destroyer published a same-id B")
      (is (identical? @b-token (rf.frame/frame-incarnation-token id))
          "B remains the live incarnation")
      ;; THE TOOTH: the subsequent listener never receives A's stale event.
      (is (empty? @observer-a-regs)
          "the SUBSEQUENT listener received ZERO A :rf.flow/registered events —
           the fence suppresses every trace stage after A's exact ownership is
           lost (removing the call-with-continuation-predicate wrapper makes
           this fail)")
      ;; B is never observed by A's tail: B's stores are byte-identical to the
      ;; clean slate the destroy handed it; no A work consulted or mutated B.
      (is (= ::none @b-flow-registry) "B started with an empty flow registry")
      (is (= ::none (get (rf.flows.registry/flows-snapshot) id ::none))
          "A's stale first-registration tail never wrote a flow row onto B")
      (is (= @b-commit (rf.frame/frame-commit-epoch id))
          "A's stale tail never bumped B's commit epoch")
      (is (= @b-sensitive (rf.elision/sensitive-declarations id))
          "A's stale tail never wrote B's output-mark declaration")
      (is (= @b-trace-disabled (rf.trace/frame-trace-disabled? id))
          "A's stale tail never changed B's trace policy")
      ;; The fence does not POISON the successor: a later independent
      ;; registration owned by B (outside A's continuation scope) emits
      ;; :rf.flow/registered normally, exactly once, observed by the observer.
      (reset! observer-regs [])
      (is (= b-flow-id
             (rf/reg-flow b-flow-id
               {:frame id :inputs [[:bn]] :output-path [:bout]}
               (fn [n] (or n 0)))))
      (is (= 1 (count @observer-regs))
          "B's own later registration emits :rf.flow/registered exactly once —
           the fence did not poison the successor")
      (is (= b-flow-id (get-in (first @observer-regs) [:tags :flow-id]))
          "the sole post-loss registration observed is B's own")
      (finally
        (rf.trace.tooling/unregister-listener! ::destroyer)
        (rf.trace.tooling/unregister-listener! ::observer)))))

;; ---------------------------------------------------------------------------
;; The reserved-effect `:rf.fx/reg-flow` route runs the first-
;; registration emit UNDER the router's own exact-owner continuation predicate
;; (`re-frame.router` binds `#(frame/event-continuation-live? frame owner-token)`
;; around the event pipeline). The direct wrapper AND-composes with any parent
;; predicate (see `trace/call-with-continuation-predicate`), so a first
;; registration reached through the reserved-effect route inherits the router
;; fence and gains this one — the DIRECT cold `reg-flow` covered above is the
;; only path with no predicate of its own.
;; ---------------------------------------------------------------------------
