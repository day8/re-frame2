(ns re-frame.flows-replace-clear-trace-incarnation-cljs-test
  "rf2-rxsldx — exact-incarnation fence for the flow REPLACEMENT and CLEAR
  lifecycle traces THROUGH the synchronous trace-emit callback pipeline
  (classification projection → epoch capture → ordered tooling listeners), plus
  — for replacement — the preceding hot-reload dedup-by-shape decision, which
  (rf2-soyqfn) is now taken from THIS frame's authoritative prior/new stored flow
  values rather than the frame-blind process-global registrar dedup table.

  The sibling of rf2-pwum1g (first registration) and rf2-ytpeqf. Merged PR #5897
  made the flow-registry app-db / runtime-db / epoch writes exact, and pwum1g
  fenced the FIRST-registration `:rf.flow/registered` emit. This bead extends the
  same fence to the two remaining direct lifecycle traces:

    - REPLACEMENT: re-registering a flow id emits `:rf.registry/handler-replaced`
      (gated by a per-frame prior/new shape compare — rf2-soyqfn).
    - CLEAR: `clear-flow` emits `:rf.flow/cleared`.

  Before this fix the replacement dedup+emit ran under the always-true default
  continuation, and the clear emit ran AFTER the serialized drain section had
  released — carrying no pinned token at all. `trace/emit!` is itself a
  synchronous, callback-bearing pipeline whose stages recheck ownership ONLY
  while a continuation predicate is installed (`trace/continuation-live?` reads
  the always-true default otherwise). A DIRECT cold `reg-flow` / `clear-flow`
  (unlike the reserved-effect `:rf.fx/reg-flow` route, which inherits the
  router's exact-owner predicate) installed none. So an ordered trace LISTENER
  (or the epoch-capture callback) could destroy incarnation A and publish a
  same-id B mid-emit, and every SUBSEQUENT listener would still receive A's
  incarnation-less replacement / clear event after B owns the bare id (and later
  policy/capture could observe B).

  The fix wraps each emit in `trace/call-with-continuation-predicate` bound to A's
  pinned incarnation — and, for clear, moves the emit INSIDE the exact-owner
  serialization so `pinned` is authoritative when emission is initiated — so the
  trace pipeline is fenced to A: the already-entered delivery (the listener that
  destroys A) stands once, and every LATER listener / capture / policy stage is
  suppressed the instant A's exact ownership is lost.

  Each seam here is DELIBERATELY the trace-internal listener boundary, not a
  container-write watch: A declares NO output marks, so the lifecycle op reaches
  the emit with A fully live and the ONLY callback seam is the ordered listener
  fan-out inside emission — the boundary the merged vxgfnd.155 / mybhk3 fixtures
  (which lose A during a preceding container write, with a passive recorder that
  records no trace evidence or dedup consultation) cannot reach. Removing the
  `call-with-continuation-predicate` wrapper — or, for clear, moving the emit
  back outside the serialization (which strands `pinned`, forcing the always-true
  default) — makes the subsequent listener receive A's stale event and the
  focused assertion fail.

  The whole scenario runs SYNCHRONOUSLY on the single host thread: the destroyer
  listener destroys A and publishes B reentrantly inside emission, so the
  cross-incarnation ordering is deterministic without threads. Listener fan-out
  order is insertion order, so the destroyer is registered FIRST (the
  already-entered delivery that may stand) and the observer SECOND (the
  subsequent delivery the fence must suppress).

  This file is `*-cljs-test.cljc` so the shadow-cljs `:node-test` build
  (ns-regexp `cljs-test$`) discovers it under CLJS AND the cognitect JVM runner
  runs it — both hosts exercise replacement and clear."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; ===========================================================================
;; REPLACEMENT — `:rf.registry/handler-replaced`
;;
;; A's replacement emit reaches the ordered tooling listeners with A live; the
;; FIRST listener destroys A and publishes same-id B; the SUBSEQUENT listener
;; must NOT receive A's stale `:rf.registry/handler-replaced` after B owns the
;; bare id. The destroyer hit proves the trace/emit pipeline ran for A (not
;; merely app-db/cache state); the per-frame shape dedup (rf2-soyqfn) allows the
;; emit because the replacement carries a different derive.
;; ===========================================================================

(deftest reg-flow-replacement-trace-listener-loss-fences-subsequent-listeners
  ;; rf2-rxsldx (red before fix). Re-registering A's flow (NO output marks) with a
  ;; different derive reaches `trace/emit! :rf.registry/handler-replaced` with A
  ;; live. The destroyer listener — the already-entered delivery — destroys A and
  ;; publishes same-id B mid-fan-out. Before the fix the dedup+emit ran under the
  ;; always-true continuation, so the observer (the subsequent listener) still
  ;; received A's incarnation-less replaced event after B owned the id. After the
  ;; fix the pinned-A continuation predicate suppresses every listener past the
  ;; loss.
  (let [id               :flow.replace.fence/subject
        flow-id          :flow.replace.fence/a
        b-flow-id        :flow.replace.fence/b
        destroyer-hits   (atom 0)
        observer-a-repl  (atom [])          ;; A's replaced events the observer saw
        observer-repl    (atom [])          ;; every replaced event the observer saw
        armed?           (atom true)
        b-token          (atom nil)
        b-flow-registry  (atom ::unset)
        b-commit         (atom ::unset)]
    (rf/make-frame {:id id})
    ;; First registration establishes the prior so the next reg-flow is a
    ;; REPLACEMENT (emits :rf.registry/handler-replaced, not :rf.flow/registered).
    (rf/reg-flow flow-id
      {:frame id :inputs [[:an]] :output-path [:aout]}
      (fn [n] (or n 0)))
    ;; Listener 1 (registered FIRST → fans out FIRST): the DESTROYER. On A's own
    ;; :rf.registry/handler-replaced — the already-entered delivery — it destroys
    ;; A and publishes same-id B exactly once, then snapshots B's stores.
    (rf.trace/register-listener!
      ::destroyer
      (fn [ev]
        (when (and (= :rf.registry/handler-replaced (:operation ev))
                   (= flow-id (get-in ev [:tags :id]))
                   (compare-and-set! armed? true false))
          (swap! destroyer-hits inc)
          (rf.frame/destroy-frame! id)
          (rf/make-frame {:id id})
          (reset! b-token (rf.frame/frame-incarnation-token id))
          (reset! b-flow-registry (get (rf.flows.registry/flows-snapshot) id ::none))
          (reset! b-commit (rf.frame/frame-commit-epoch id)))))
    ;; Listener 2 (registered SECOND → fans out AFTER the destroyer): the
    ;; SUBSEQUENT observer. Absent the fence it receives A's stale replaced event
    ;; after B owns the bare id; the fence suppresses it.
    (rf.trace/register-listener!
      ::observer
      (fn [ev]
        (when (= :rf.registry/handler-replaced (:operation ev))
          (swap! observer-repl conj ev)
          (when (= flow-id (get-in ev [:tags :id]))
            (swap! observer-a-repl conj ev)))))
    (try
      ;; REPLACEMENT: same id, different derive → :rf.registry/handler-replaced.
      (is (= flow-id
             (rf/reg-flow flow-id
               {:frame id :inputs [[:an]] :output-path [:aout]}
               (fn [n] (* 2 (or n 0)))))
          "reg-flow returns its flow-id even though A's owner was lost mid-emit")
      (is (= 1 @destroyer-hits)
          "the destroyer received A's :rf.registry/handler-replaced once — the
           already-entered delivery stands")
      (is (some? @b-token) "the destroyer published a same-id B")
      (is (identical? @b-token (rf.frame/frame-incarnation-token id))
          "B remains the live incarnation")
      ;; THE TOOTH: the subsequent listener never receives A's stale event.
      (is (empty? @observer-a-repl)
          "the SUBSEQUENT listener received ZERO A :rf.registry/handler-replaced
           events — the fence suppresses every trace stage after A's exact
           ownership is lost (removing the call-with-continuation-predicate
           wrapper makes this fail)")
      ;; B is never observed / mutated by A's stale tail.
      (is (= ::none @b-flow-registry) "B started with an empty flow registry")
      (is (= ::none (get (rf.flows.registry/flows-snapshot) id ::none))
          "A's stale replacement tail never wrote a flow row onto B")
      (is (= @b-commit (rf.frame/frame-commit-epoch id))
          "A's stale tail never bumped B's commit epoch")
      ;; The fence does not POISON the successor: B's OWN later replacement emits
      ;; :rf.registry/handler-replaced exactly once, observed.
      (reset! observer-repl [])
      (rf/reg-flow b-flow-id
        {:frame id :inputs [[:bn]] :output-path [:bout]}
        (fn [n] (or n 0)))
      (rf/reg-flow b-flow-id
        {:frame id :inputs [[:bn]] :output-path [:bout]}
        (fn [n] (* 3 (or n 0))))
      (is (= 1 (count @observer-repl))
          "B's own later replacement emits :rf.registry/handler-replaced exactly
           once — the fence did not poison the successor")
      (is (= b-flow-id (get-in (first @observer-repl) [:tags :id]))
          "the sole post-loss replacement observed is B's own")
      (finally
        (rf.trace/unregister-listener! ::destroyer)
        (rf.trace/unregister-listener! ::observer)))))

;; ---------------------------------------------------------------------------
;; Green control / over-fence tooth — when A retains ownership through a
;; NON-destroying listener, the ordinary replacement trace still reaches the
;; subsequent listener exactly once. A wrongly-over-fencing predicate would
;; silently swallow the trace.
;; ---------------------------------------------------------------------------

(deftest reg-flow-replacement-trace-with-live-owner-emits-once
  ;; rf2-rxsldx mutation tooth. The exact-incarnation fence must NOT suppress the
  ;; normal replacement trace when A stays live through the fan-out:
  ;; :rf.registry/handler-replaced reaches BOTH the first and the subsequent
  ;; listener, carrying A's own id.
  (let [id       :flow.replace.fence/live
        flow-id  :flow.replace.fence/h
        touched  (atom 0)
        observed (atom [])]
    (rf/make-frame {:id id})
    (rf/reg-flow flow-id
      {:frame id :inputs [[:n]] :output-path [:out]}
      (fn [n] (or n 0)))
    (rf.trace/register-listener!
      ::live-touch
      (fn [ev]
        (when (= :rf.registry/handler-replaced (:operation ev))
          (swap! touched inc))))          ;; observe only — A stays live
    (rf.trace/register-listener!
      ::live-observer
      (fn [ev]
        (when (= :rf.registry/handler-replaced (:operation ev))
          (swap! observed conj ev))))
    (try
      (rf/reg-flow flow-id
        {:frame id :inputs [[:n]] :output-path [:out]}
        (fn [n] (* 2 (or n 0))))
      (is (= 1 @touched) "the first listener saw A's replaced event")
      (is (= 1 (count @observed))
          "the SUBSEQUENT listener also received it — the live-owner replacement
           is not over-fenced")
      (is (= flow-id (get-in (first @observed) [:tags :id])) "A's own flow id")
      (finally
        (rf.trace/unregister-listener! ::live-touch)
        (rf.trace/unregister-listener! ::live-observer)))))

;; ===========================================================================
;; CLEAR — `:rf.flow/cleared`
;;
;; A's clear emit reaches the ordered tooling listeners with A live; the FIRST
;; listener destroys A and publishes same-id B; the SUBSEQUENT listener must NOT
;; receive A's stale `:rf.flow/cleared` after B owns the bare id. The emit is now
;; initiated INSIDE the exact-owner serialization, so `pinned` is authoritative.
;; ===========================================================================

(deftest clear-flow-trace-listener-loss-fences-subsequent-listeners
  ;; rf2-rxsldx (red before fix). Clearing A's flow reaches
  ;; `trace/emit! :rf.flow/cleared` with A live. The destroyer listener — the
  ;; already-entered delivery — destroys A and publishes same-id B mid-fan-out.
  ;; Before the fix the emit ran AFTER the serialized section released, under the
  ;; always-true continuation, so the observer still received A's incarnation-less
  ;; cleared event after B owned the id. After the fix the emit is inside the
  ;; serialization under the pinned-A continuation predicate, which suppresses
  ;; every listener past the loss.
  (let [id               :flow.cleared.fence/subject
        flow-id          :flow.cleared.fence/a
        b-flow-id        :flow.cleared.fence/b
        destroyer-hits   (atom 0)
        observer-a-clr   (atom [])          ;; A's cleared events the observer saw
        observer-clr     (atom [])          ;; every cleared event the observer saw
        armed?           (atom true)
        b-token          (atom nil)
        b-flow-registry  (atom ::unset)
        b-commit         (atom ::unset)]
    (rf/make-frame {:id id})
    (rf/reg-flow flow-id
      {:frame id :inputs [[:an]] :output-path [:aout]}
      (fn [n] (or n 0)))
    ;; Listener 1 (registered FIRST): the DESTROYER. On A's own :rf.flow/cleared
    ;; it destroys A and publishes same-id B exactly once, then snapshots B.
    (rf.trace/register-listener!
      ::destroyer
      (fn [ev]
        (when (and (= :rf.flow/cleared (:operation ev))
                   (= flow-id (get-in ev [:tags :flow-id]))
                   (= id (get-in ev [:tags :frame]))
                   (compare-and-set! armed? true false))
          (swap! destroyer-hits inc)
          (rf.frame/destroy-frame! id)
          (rf/make-frame {:id id})
          (reset! b-token (rf.frame/frame-incarnation-token id))
          (reset! b-flow-registry (get (rf.flows.registry/flows-snapshot) id ::none))
          (reset! b-commit (rf.frame/frame-commit-epoch id)))))
    ;; Listener 2 (registered SECOND): the SUBSEQUENT observer.
    (rf.trace/register-listener!
      ::observer
      (fn [ev]
        (when (= :rf.flow/cleared (:operation ev))
          (swap! observer-clr conj ev)
          (when (= flow-id (get-in ev [:tags :flow-id]))
            (swap! observer-a-clr conj ev)))))
    (try
      (is (nil? (rf/clear :flow flow-id {:frame id}))
          "clear-flow returns nil even though A's owner was lost mid-emit")
      (is (= 1 @destroyer-hits)
          "the destroyer received A's :rf.flow/cleared once — the already-entered
           delivery stands")
      (is (some? @b-token) "the destroyer published a same-id B")
      (is (identical? @b-token (rf.frame/frame-incarnation-token id))
          "B remains the live incarnation")
      ;; THE TOOTH: the subsequent listener never receives A's stale event.
      (is (empty? @observer-a-clr)
          "the SUBSEQUENT listener received ZERO A :rf.flow/cleared events — the
           fence suppresses every trace stage after A's exact ownership is lost
           (removing the wrapper, or moving the emit back outside the exact-owner
           serialization, makes this fail)")
      ;; B is never observed / mutated by A's stale tail.
      (is (= ::none @b-flow-registry) "B started with an empty flow registry")
      (is (= @b-commit (rf.frame/frame-commit-epoch id))
          "A's stale clear tail never bumped B's commit epoch")
      ;; The fence does not POISON the successor: B's OWN later clear emits
      ;; :rf.flow/cleared exactly once, observed.
      (reset! observer-clr [])
      (rf/reg-flow b-flow-id
        {:frame id :inputs [[:bn]] :output-path [:bout]}
        (fn [n] (or n 0)))
      (is (nil? (rf/clear :flow b-flow-id {:frame id})))
      (is (= 1 (count @observer-clr))
          "B's own later clear emits :rf.flow/cleared exactly once — the fence did
           not poison the successor")
      (is (= b-flow-id (get-in (first @observer-clr) [:tags :flow-id]))
          "the sole post-loss clear observed is B's own")
      (finally
        (rf.trace/unregister-listener! ::destroyer)
        (rf.trace/unregister-listener! ::observer)))))

;; ---------------------------------------------------------------------------
;; Green control / over-fence tooth — when A retains ownership through a
;; NON-destroying listener, the ordinary clear trace still reaches the
;; subsequent listener exactly once, carrying A's own payload.
;; ---------------------------------------------------------------------------

(deftest clear-flow-trace-with-live-owner-emits-once
  ;; rf2-rxsldx mutation tooth. The exact-incarnation fence must NOT suppress the
  ;; normal clear trace when A stays live through the fan-out: :rf.flow/cleared
  ;; reaches BOTH the first and the subsequent listener.
  (let [id       :flow.cleared.fence/live
        flow-id  :flow.cleared.fence/h
        touched  (atom 0)
        observed (atom [])]
    (rf/make-frame {:id id})
    (rf/reg-flow flow-id
      {:frame id :inputs [[:n]] :output-path [:out]}
      (fn [n] (or n 0)))
    (rf.trace/register-listener!
      ::live-touch
      (fn [ev]
        (when (= :rf.flow/cleared (:operation ev))
          (swap! touched inc))))          ;; observe only — A stays live
    (rf.trace/register-listener!
      ::live-observer
      (fn [ev]
        (when (= :rf.flow/cleared (:operation ev))
          (swap! observed conj ev))))
    (try
      (is (nil? (rf/clear :flow flow-id {:frame id})))
      (is (= 1 @touched) "the first listener saw A's cleared event")
      (is (= 1 (count @observed))
          "the SUBSEQUENT listener also received it — the live-owner clear is not
           over-fenced")
      (let [tags (:tags (first @observed))]
        (is (= flow-id (:flow-id tags)) "A's own :flow-id")
        (is (= [:out]  (:path tags))    "A's own :output-path")
        (is (= id      (:frame tags))   "A's own frame"))
      (finally
        (rf.trace/unregister-listener! ::live-touch)
        (rf.trace/unregister-listener! ::live-observer)))))

;; ===========================================================================
;; PER-FRAME REPLACEMENT EVIDENCE (rf2-soyqfn) — CROSS-HOST (CLJ + CLJS)
;;
;; Flow replacement evidence must be scoped to the authoritative frame slot, not
;; a frame-blind process-global registrar dedup key. Two live frames replacing
;; the same flow-id are two independent definitions (Spec 013 §Frame-scoping);
;; each genuine replacement must emit its OWN `:rf.registry/handler-replaced`,
;; carrying `:frame`, and a subsequent identical reload / a same-id frame
;; reincarnation must not inherit a sibling's or a predecessor's recorded shape.
;; Each assertion below is RED on the pre-fix process-global path.
;;
;; These run on BOTH hosts (`*-cljs-test.cljc`), covering the DIRECT `reg-flow`
;; and the reserved-effect `:rf.fx/reg-flow` entry points on CLJ and CLJS.
;; ===========================================================================

(deftest reg-flow-replacement-evidence-is-per-frame-cross-host
  ;; DIRECT reg-flow. Two live frames replace the same flow-id from the SAME
  ;; prior derive to the SAME new derive; each emits once, attributed to its
  ;; frame. Pre-fix the process-global [:flow flow-id] key let the first frame's
  ;; recorded shape suppress the second's genuine replacement (1 emit, no :frame).
  (let [captured (atom [])
        f1       (fn [n] (* 2 (or n 0)))
        f2       (fn [n] (* 3 (or n 0)))]
    (rf.trace/register-listener!
      ::repl-recorder
      (fn [ev]
        (when (= :rf.registry/handler-replaced (:operation ev))
          (swap! captured conj ev))))
    (try
      (rf/make-frame {:id :left})
      (rf/make-frame {:id :right})
      (rf/reg-flow :shared {:frame :left  :inputs [[:n]] :output-path [:out]} f1)
      (rf/reg-flow :shared {:frame :right :inputs [[:n]] :output-path [:out]} f1)
      (is (empty? @captured) "first registrations emit no :rf.registry/handler-replaced")
      (rf/reg-flow :shared {:frame :left  :inputs [[:n]] :output-path [:out]} f2)
      (rf/reg-flow :shared {:frame :right :inputs [[:n]] :output-path [:out]} f2)
      (is (= 2 (count @captured))
          "each frame's genuine replacement emits once — no cross-frame suppression")
      (is (= #{:left :right}
             (set (map #(get-in % [:tags :frame]) @captured)))
          "the two events are attributable to their distinct :frame slots")
      ;; Independent per-frame suppression: an identical reload in each frame
      ;; (same f2 object) is now suppressed within that frame.
      (reset! captured [])
      (rf/reg-flow :shared {:frame :left  :inputs [[:n]] :output-path [:out]} f2)
      (rf/reg-flow :shared {:frame :right :inputs [[:n]] :output-path [:out]} f2)
      (is (empty? @captured)
          "identical reloads suppress independently within each frame")
      (finally
        (rf.trace/unregister-listener! ::repl-recorder)))))

(deftest fx-reg-flow-replacement-evidence-is-per-frame-cross-host
  ;; RESERVED-EFFECT :rf.fx/reg-flow. The dispatching frame threads through as the
  ;; flow's :frame, so dispatching the registering event into :left / :right
  ;; registers/replaces in that frame. Each frame's effect-driven replacement
  ;; emits its own :rf.registry/handler-replaced, attributed to its frame.
  (let [captured (atom [])
        f1       (fn [n] (* 2 (or n 0)))
        f2       (fn [n] (* 3 (or n 0)))]
    (rf.trace/register-listener!
      ::repl-recorder
      (fn [ev]
        (when (= :rf.registry/handler-replaced (:operation ev))
          (swap! captured conj ev))))
    (try
      (rf/make-frame {:id :left})
      (rf/make-frame {:id :right})
      (rf/reg-event :reg-shared
        (fn [_ [_ derive-fn]]
          {:fx [[:rf.fx/reg-flow [:shared {:inputs [[:n]] :output-path [:out]} derive-fn]]]}))
      ;; First registrations, one per frame (dispatch into the target frame).
      (rf/dispatch-sync [:reg-shared f1] {:frame :left})
      (rf/dispatch-sync [:reg-shared f1] {:frame :right})
      (is (empty? @captured) "effect-driven first registrations do not emit handler-replaced")
      ;; Real replacements f1→f2, one per frame, via the reserved effect.
      (rf/dispatch-sync [:reg-shared f2] {:frame :left})
      (rf/dispatch-sync [:reg-shared f2] {:frame :right})
      (is (= 2 (count @captured))
          "each frame's effect-driven replacement emits once — no cross-frame suppression")
      (is (= #{:left :right}
             (set (map #(get-in % [:tags :frame]) @captured)))
          "the reserved-effect evidence is attributable to its frame")
      (finally
        (rf.trace/unregister-listener! ::repl-recorder)))))

(deftest reg-flow-replacement-reincarnation-does-not-inherit-cross-host
  ;; Destroy + recreate a frame under the SAME id; the new incarnation's genuine
  ;; replacement must emit. Pre-fix the process-global table persisted across
  ;; destroy and suppressed the successor's real replacement.
  (let [captured (atom [])
        f1       (fn [n] (* 2 (or n 0)))
        f2       (fn [n] (* 3 (or n 0)))]
    (rf.trace/register-listener!
      ::repl-recorder
      (fn [ev]
        (when (= :rf.registry/handler-replaced (:operation ev))
          (swap! captured conj ev))))
    (try
      (rf/make-frame {:id :host})
      (rf/reg-flow :shared {:frame :host :inputs [[:n]] :output-path [:out]} f1)
      (rf/reg-flow :shared {:frame :host :inputs [[:n]] :output-path [:out]} f2)
      (is (= 1 (count @captured)) "incarnation A's real replacement emitted once")
      (reset! captured [])
      (rf.frame/destroy-frame! :host)
      (rf/make-frame {:id :host})
      (rf/reg-flow :shared {:frame :host :inputs [[:n]] :output-path [:out]} f1)
      (rf/reg-flow :shared {:frame :host :inputs [[:n]] :output-path [:out]} f2)
      (is (= 1 (count @captured))
          "the reincarnated frame's genuine replacement emits — no inherited shape")
      (is (= :host (get-in (first @captured) [:tags :frame]))
          "attributed to the reincarnated :host frame")
      (finally
        (rf.trace/unregister-listener! ::repl-recorder)))))
