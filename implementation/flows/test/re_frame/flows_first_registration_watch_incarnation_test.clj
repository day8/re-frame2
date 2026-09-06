(ns re-frame.flows-first-registration-watch-incarnation-test
  "rf2-ytpeqf — exact-incarnation fence for FIRST-TIME flow registration
  evidence (`:rf.flow/registered`).

  rf2-vxgfnd.155 fenced the callback-bearing REPLACEMENT / clear tails (the
  output-mark refresh + path vacation + the bare-id registry / dirty-check /
  commit-epoch mutations), but the first-time `:rf.flow/registered` trace was
  emitted OUTSIDE the exact-owner continuation, gated only on
  `(nil? prior-frame-flow)`. A first-time flow that declares output marks reaches
  a callback-bearing runtime-db write (`write-flow-output-marks!`); a
  synchronous container watch can destroy incarnation A there and publish a
  same-id B. The merged .155 fixtures only exercise the REPLACEMENT branch
  (prior non-nil), so they never reach the first-registration emit.

  Before the fix the exact mark write correctly reported ownership loss, yet the
  outer first-registration trace still fired after the terminal fence — a stale
  A `:rf.flow/registered` delivery with no incarnation discriminator, consulting
  B's current trace policy/capture and synchronously notifying tooling after A
  was gone. After the fix the first-registration evidence lives INSIDE the same
  exact-owner postcheck, so the loss suppresses it: zero A deliveries, B's
  registration trace (emitted by B's own reg-flow) intact, B's stores
  byte-identical, and B's trace policy never consulted for A.

  The watch is driven SYNCHRONOUSLY on the single JVM test thread (it destroys A
  and publishes B reentrantly), so the cross-incarnation ordering is fully
  deterministic without threads. A one-shot `armed?` CAS makes the watch fire
  exactly once, during the first-time mark write under test."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as rf.elision]
            [re-frame.flows :as rf.flows]
            [re-frame.flows.registry :as rf.flows.registry]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace :as rf.trace]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- install-watching-adapter!
  "Install a plain-atom-backed adapter whose `replace-container!` runs `on-write`
  (a 0-arg thunk) exactly once, the first time a container write happens while
  `armed?` holds true. The physical write always lands FIRST so A's write that
  linearized before the loss stands."
  [armed? on-write]
  (let [base-replace (:replace-container! rf.substrate.plain-atom/adapter)]
    (rf.substrate.adapter/dispose-adapter!)
    (reset! rf.frame/frames {})
    (rf.substrate.adapter/install-adapter!
      (assoc rf.substrate.plain-atom/adapter
             :kind :custom
             :replace-container!
             (fn [container value]
               (base-replace container value)
               (when (compare-and-set! armed? true false)
                 (on-write)))))))

(defn- restore-plain-adapter! []
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter))

(defn- registered-events
  "The captured `:rf.flow/registered` events, in emission order."
  [captured]
  (filterv #(= :rf.flow/registered (:operation %)) @captured))

;; ---------------------------------------------------------------------------
;; FIRST-TIME reg-flow — the output-mark write's watch loses A. The stale outer
;; first-registration trace must NOT deliver A's :rf.flow/registered against the
;; same-id successor B.
;; ---------------------------------------------------------------------------

(deftest first-registration-mark-watch-loss-withholds-registered-trace-from-successor
  ;; rf2-ytpeqf (red before fix). Registering A's FIRST flow installs its output
  ;; marks through a container write; that write's synchronous watch destroys A
  ;; and publishes same-id B (with B's own flow row + dirty-check cache +
  ;; output-mark declaration, and B's own :rf.flow/registered). Before the fix A's
  ;; first-time :rf.flow/registered still fired outside the exact-owner
  ;; continuation, delivering a stale A registration to tooling after A was gone.
  (let [id            :flow.incarnation/first-loss
        armed?        (atom false)
        captured      (atom [])
        b-flow-row    (atom ::unset)
        b-dirty       (atom ::unset)
        b-commit      (atom ::unset)
        b-sensitive   (atom ::unset)
        b-token       (atom nil)]
    (install-watching-adapter!
      armed?
      (fn []
        ;; The mark-write watch: destroy A, publish same-id B with its own
        ;; first-time flow state (B's reg-flow emits B's own :rf.flow/registered),
        ;; and snapshot B's stores for the byte-identical assertions.
        (rf.frame/destroy-frame! id)
        (rf/make-frame {:id id})
        (rf/reg-flow :flow.incarnation/f
          {:frame id :inputs [[:bn]] :output-path [:bout] :sensitive [[:bout]]}
          (fn [n] (or n 0)))
        (rf.flows.registry/set-frame-flow-last-inputs! id :flow.incarnation/f [::b-input])
        (reset! b-token (rf.frame/frame-incarnation-token id))
        (reset! b-flow-row (get-in (rf.flows.registry/flows-snapshot)
                                   [id :flow.incarnation/f]))
        (reset! b-dirty (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/f))
        (reset! b-commit (rf.frame/frame-commit-epoch id))
        (reset! b-sensitive (rf.elision/sensitive-declarations id))))
    (try
      (rf/make-frame {:id id})
      (rf.trace/register-listener!
        ::first-registration-recorder
        (fn [ev]
          (when (= :flow (:op-type ev))
            (swap! captured conj ev))))
      (reset! armed? true)
      ;; A's FIRST-TIME registration: it declares an output classification, so
      ;; installing its marks produces the elision-registry (runtime-db) container
      ;; write — the watch seam — that destroys A and publishes B.
      (is (= :flow.incarnation/f
             (rf/reg-flow :flow.incarnation/f
               {:frame id :inputs [[:an]] :output-path [:aout] :sensitive [[:aout]]}
               (fn [n] (or n 0))))
          "reg-flow returns its flow-id even though A's owner was lost mid-write")
      (let [token-b @b-token
            regs    (registered-events captured)]
        (is (some? token-b) "the mark-write watch published a same-id B")
        (is (identical? token-b (rf.frame/frame-incarnation-token id))
            "B remains the live incarnation")
        ;; The core assertion: zero A :rf.flow/registered deliveries after loss.
        (is (= 1 (count regs))
            "exactly one :rf.flow/registered — B's own first registration; A's
             stale first-registration trace is withheld once its owner is lost")
        (is (empty? (filter #(= [:aout] (get-in % [:tags :path])) regs))
            "no :rf.flow/registered carries A's :aout output — A's registration
             evidence is never delivered to tooling against successor B")
        (is (= [:bout] (get-in (first regs) [:tags :path]))
            "the sole delivered registration is B's (:bout), emitted by B's own
             reg-flow inside the watch")
        ;; B's evidence stays byte-identical — A's stale tail touches nothing.
        (is (= @b-flow-row (get-in (rf.flows.registry/flows-snapshot) [id :flow.incarnation/f]))
            "A's stale first-registration tail never rewrites B's flow row")
        (is (= @b-dirty
               (rf.flows.registry/get-frame-flow-last-inputs id :flow.incarnation/f))
            "A's stale tail never drops B's dirty-check cache")
        (is (= @b-commit (rf.frame/frame-commit-epoch id))
            "A's stale mark write never bumps B's commit epoch")
        (is (= @b-sensitive (rf.elision/sensitive-declarations id))
            "A's stale tail never rewrites B's output-mark declaration"))
      (finally
        (rf.trace/unregister-listener! ::first-registration-recorder)
        (restore-plain-adapter!)))))

;; ---------------------------------------------------------------------------
;; Green control — when A retains ownership through a NON-destroying watch, the
;; ordinary first-time registration still emits :rf.flow/registered exactly once.
;; A wrongly-fencing exact path would silently swallow the trace.
;; ---------------------------------------------------------------------------

(deftest first-registration-with-live-owner-emits-registered-once
  ;; rf2-ytpeqf mutation tooth. The exact-incarnation fence must NOT suppress the
  ;; normal first-registration trace when A stays live: :rf.flow/registered fires
  ;; exactly once, carrying A's own payload.
  (let [id          :flow.incarnation/first-live
        armed?      (atom false)
        captured    (atom [])
        watch-runs  (atom 0)]
    (install-watching-adapter!
      armed?
      (fn [] (swap! watch-runs inc)))          ; observe only — A stays live
    (try
      (rf/make-frame {:id id})
      (rf.trace/register-listener!
        ::first-registration-live-recorder
        (fn [ev]
          (when (= :flow (:op-type ev))
            (swap! captured conj ev))))
      (reset! armed? true)
      (rf/reg-flow :flow.incarnation/h
        {:frame id :inputs [[:n]] :output-path [:out] :sensitive [[:out]]}
        (fn [n] (or n 0)))
      (let [regs (registered-events captured)]
        (is (= 1 @watch-runs) "the container watch fired on the mark write")
        (is (= 1 (count regs))
            "the live-owner first registration emits :rf.flow/registered once")
        (let [tags (:tags (first regs))]
          (is (= :flow.incarnation/h (:flow-id tags)) "A's own :flow-id")
          (is (= [:out] (:path tags))                 "A's own :output-path")
          (is (= id (:frame tags))                    "A's own frame")))
      (finally
        (rf.trace/unregister-listener! ::first-registration-live-recorder)
        (restore-plain-adapter!)))))
