(ns re-frame.flows-reg-flow-destroy-linearization-test
  "JVM regression coverage for the `reg-flow` vs concurrent `destroy-frame!`
  LINEARIZATION race (rf2-3fc89f.6).

  THE BUG. `reg-flow`'s pre-serializer liveness check was a check-then-act: it
  proved `(frame/frame frame-id)` live, THEN entered
  `frame/call-serialized-with-drain!` and unconditionally installed the flow
  row. `call-serialized-with-drain!` deliberately runs its thunk in-line for an
  ABSENT frame (so `clear-flow` stays idempotent and mid-drain effects run
  reentrantly), and `destroy-frame!` did not serialize its teardown with that
  helper — so a `destroy-frame!` completing in the window between the liveness
  check and the winning registry mutation left a GHOST flow row keyed by the
  destroyed frame-id. A later `make-frame` reusing that id could inherit and
  evaluate the stale flow.

  THE FIX (ONE frame-owned lifecycle gate — the frame's `:drain-lock`).

    - `reg-flow` PINS the incarnation it selected (`frame/frame-incarnation-
      token`, the frame record's `:drain-lock` atom — fresh per first-time
      `make-frame`, stable across in-place record swaps) and REVALIDATES that
      token INSIDE the serialized mutation, under the frame's `:drain-lock`.

    - `destroy-frame!` flips liveness (`mark-frame-destroyed!`) under the SAME
      `:drain-lock` (`frame/call-serialized-with-drain!`, keyed on the
      captured record so it still serializes after the record is dissoc'd).

  Registration and destruction therefore linearize on one reentrant gate:

    * destroy wins  -> the revalidation observes a nil / DIFFERENT incarnation
                       token and REFUSES with `:rf.error/flow-frame-not-live`;
                       no ghost row.
    * reg-flow wins -> it publishes its row, which the destroy's
                       `:flows/teardown-on-frame-destroy!` hook (which always
                       runs AFTER the liveness flip and BEFORE `dissoc-frame!`)
                       then removes.

  Because the flows teardown always precedes `dissoc-frame!`, and a fresh
  same-id incarnation can only exist AFTER that dissoc, the teardown can never
  delete a NEWER registration; and a stale `reg-flow` pinned to the OLD
  incarnation is rejected by the token-identity check rather than clobbering the
  new incarnation's slot.

  TEST STRATEGY. The dangerous interleaving is driven DETERMINISTICALLY by
  pausing `reg-flow` right after its pin (via a `with-redefs` of the PUBLIC
  `frame/call-serialized-with-drain!` — the reg-flow enters it AFTER the pin,
  BEFORE the mutation) while `destroy-frame!` runs to completion on the main
  thread. A high-contention test then hammers both orderings on real threads
  and asserts the invariant holds with no ghost, deadlock, or flake.

  CLJS is single-threaded; this race is JVM-only by construction."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.elision :as elision]
            [re-frame.flows :as flows]
            [re-frame.flows.registry :as registry]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---- per-test reset -------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- await! [^CountDownLatch latch where]
  (is (.await latch 30 TimeUnit/SECONDS)
      (str "latch '" where "' did not trip within 30s — a deadlock or a "
           "racing thread that never reached the rendezvous")))

(defn- reg-flow-error-id
  "Run `thunk`; return the `:rf.error/id` of a thrown ExceptionInfo, or
  `::no-throw` when it returned normally."
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo e
      (:rf.error/id (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Criterion 1 + 2 — the exact bead interleaving: destroy completes in the
;; window AFTER reg-flow's liveness decision and BEFORE its registry mutation.
;; The registration must REFUSE and leave NO ghost row on ANY surface.
;; ---------------------------------------------------------------------------

(deftest reg-flow-losing-to-destroy-in-the-mutation-window-refuses-no-ghost
  (testing "destroy between reg-flow's pin and its serialized mutation -> refuse, no ghost row"
    (let [orig-token frame/frame-incarnation-token
          calls      (atom 0)
          entered    (CountDownLatch. 1) ;; reg-flow captured its pin, is parked before the mutation
          release    (CountDownLatch. 1)] ;; let reg-flow resume into the mutation
      (rf/make-frame {:id :fc/scratch :doc "scratch frame destroyed mid-registration"})
      ;; Park reg-flow right AFTER its outer pin (the FIRST frame-incarnation-token
      ;; read, which captures the still-live token) but BEFORE it enters the
      ;; serialized mutation — exactly the bead's "after the liveness decision,
      ;; before the registry mutation" window. reg-flow holds NO lock while
      ;; parked, so destroy on the main thread proceeds freely. NB: `destroy-frame!`
      ;; itself now routes its liveness flip through `call-serialized-with-drain!`,
      ;; so the pause seam MUST NOT be that helper (it would park destroy too);
      ;; `frame-incarnation-token` is called only by reg-flow here.
      (with-redefs [frame/frame-incarnation-token
                    (fn [id]
                      (let [tok (orig-token id)]
                        (when (= 1 (swap! calls inc)) ;; #1 = reg-flow's outer pin
                          (.countDown entered)
                          (await! release "reg-flow release"))
                        tok))]
        (let [reg (future
                    (reg-flow-error-id
                      #(rf/reg-flow :ghost
                                    {:frame       :fc/scratch
                                     :inputs      [[:n]]
                                     :output-path [:out]
                                     ;; Declare an output classification so the
                                     ;; elision-declaration surface is exercised
                                     ;; too (criterion 2).
                                     :sensitive   [[:out]]}
                                    (fn [n] (* 2 (or n 0))))))]
          (await! entered "reg-flow parked past its pin")
          ;; Destroy completes FULLY while reg-flow is parked past its pin.
          (frame/destroy-frame! :fc/scratch)
          (is (nil? (frame/frame :fc/scratch))
              "precondition: destroy fully torn the frame down before reg-flow resumes")
          (.countDown release)
          (is (= :rf.error/flow-frame-not-live (deref reg 30000 ::timeout))
              "reg-flow that lost the race to destroy refuses with the stable discriminator")))

      ;; --- The load-bearing post-conditions (no ghost on ANY surface) ------
      (is (not (contains? (flows/flows-snapshot) :fc/scratch))
          "flows-snapshot: no ghost flow row survives the destroyed frame")
      (is (not (contains? (flows/last-inputs-snapshot) :ghost))
          "last-inputs-snapshot: no dirty-check row for the ghost flow")
      (is (empty? (registry/abandoned-output-paths-snapshot :fc/scratch))
          "no pending abandoned output paths for the destroyed frame")
      (is (empty? (elision/sensitive-declarations :fc/scratch))
          "no flow-sourced :sensitive declaration survives")
      (is (nil? (registrar/lookup :flow :ghost))
          "the :flow registrar slot is RESERVED-but-empty throughout (rf2-en00bk single-store)"))))

;; ---------------------------------------------------------------------------
;; Criterion 3 — a stale reg-flow pinned to the OLD incarnation must NOT write
;; into a NEW same-id incarnation's slot; the re-registered frame inherits
;; nothing.
;; ---------------------------------------------------------------------------

(deftest stale-reg-flow-does-not-clobber-a-re-registered-new-incarnation
  (testing "reg-flow pinned to incarnation X refuses when X is destroyed and the id is re-registered to X+1"
    (let [orig-token frame/frame-incarnation-token
          calls      (atom 0)
          entered    (CountDownLatch. 1)
          release    (CountDownLatch. 1)]
      (rf/make-frame {:id :fc/reused :doc "incarnation X"})
      (rf/reg-event :fc/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
      ;; Park reg-flow right after its outer pin (captures incarnation X's token)
      ;; via `frame-incarnation-token` — NOT `call-serialized-with-drain!`, which
      ;; `destroy-frame!` now also uses.
      (with-redefs [frame/frame-incarnation-token
                    (fn [id]
                      (let [tok (orig-token id)]
                        (when (= 1 (swap! calls inc)) ;; #1 = reg-flow's outer pin
                          (.countDown entered)
                          (await! release "stale reg-flow release"))
                        tok))]
        (let [;; This reg-flow pins incarnation X, then parks past its pin.
              reg (future
                    (reg-flow-error-id
                      #(rf/reg-flow :stale
                                    {:frame :fc/reused :inputs [[:n]] :output-path [:out]}
                                    (fn [n] (* 999 (or n 0))))))]
          (await! entered "stale reg-flow parked past its pin")
          ;; X is destroyed, then the SAME id is re-registered as a FRESH
          ;; incarnation X+1 (new :drain-lock -> new incarnation token).
          (frame/destroy-frame! :fc/reused)
          (rf/make-frame {:id :fc/reused :doc "incarnation X+1"})
          (is (some? (frame/frame :fc/reused))
              "precondition: a NEW incarnation is live under the reused id")
          (.countDown release)
          ;; The stale reg-flow's revalidation sees a DIFFERENT incarnation
          ;; token (X vs X+1) and refuses — the token identity, not a bare
          ;; nil-check, is what rejects the clobber (X+1 is live, so a bare
          ;; nil-check would WRONGLY admit the write).
          (is (= :rf.error/flow-frame-not-live (deref reg 30000 ::timeout))
              "the stale reg-flow refuses rather than clobbering incarnation X+1")))

      ;; The fresh incarnation inherited NO flow; a drive proves nothing runs.
      (is (not (contains? (flows/flows-snapshot) :fc/reused))
          "the re-registered incarnation has no inherited flow-registry slot")
      (rf/dispatch-sync [:fc/set-n 5] {:frame :fc/reused})
      (is (nil? (get (frame/frame-app-db-value :fc/reused) :out))
          "the stale flow did not run — no [:out] write in the new incarnation's app-db"))))

;; ---------------------------------------------------------------------------
;; Criterion 4 (reg wins) — when reg-flow holds the gate, destroy WAITS; after
;; reg-flow commits its row, the destroy's teardown removes it. reg-flow itself
;; succeeds (does not throw).
;; ---------------------------------------------------------------------------

(deftest reg-flow-holding-the-gate-blocks-destroy-then-teardown-removes-the-row
  (testing "reg-flow committing under the lock succeeds; a racing destroy blocks, then tears the row down"
    (let [orig-token frame/frame-incarnation-token
          in-thunk   (CountDownLatch. 1) ;; reg-flow is inside its serialized thunk, holding :drain-lock
          release    (CountDownLatch. 1) ;; let reg-flow finish the thunk
          calls      (atom 0)]
      (rf/make-frame {:id :fc/winner :doc "reg-flow wins the gate"})
      ;; Pause reg-flow INSIDE its serialized thunk (holding the drain-lock) at
      ;; the revalidation read. The FIRST token read is reg-flow's outer pin
      ;; (before the lock); the SECOND is the revalidation (under the lock) —
      ;; park on the second so destroy blocks on the lock reg-flow holds.
      (with-redefs [frame/frame-incarnation-token
                    (fn [id]
                      (let [n (swap! calls inc)]
                        (when (= n 2)
                          (.countDown in-thunk)
                          (await! release "reg-flow winner release"))
                        (orig-token id)))]
        (let [reg (future
                    (reg-flow-error-id
                      #(rf/reg-flow :won
                                    {:frame :fc/winner :inputs [[:n]] :output-path [:out]}
                                    (fn [n] (* 2 (or n 0))))))]
          (await! in-thunk "reg-flow inside thunk holding the lock")
          ;; Destroy on this thread must BLOCK on the drain-lock reg-flow holds
          ;; (its mark-frame-destroyed! now takes the same lock). Prove it can't
          ;; complete while reg-flow is parked.
          (let [destroyer (future (frame/destroy-frame! :fc/winner))]
            (is (= ::still-blocked (deref destroyer 1000 ::still-blocked))
                "destroy is blocked on the :drain-lock reg-flow holds")
            (.countDown release)
            (is (= ::no-throw (deref reg 30000 ::timeout))
                "reg-flow that won the gate committed successfully (no throw)")
            (is (not= ::timeout (deref destroyer 30000 ::timeout))
                "destroy completed once reg-flow released the lock"))))

      ;; reg-flow's row was published, then the destroy's teardown removed it.
      (is (nil? (frame/frame :fc/winner))
          "the frame is destroyed")
      (is (not (contains? (flows/flows-snapshot) :fc/winner))
          "no leftover flow row — the destroy's flows-teardown removed the committed row"))))

;; ---------------------------------------------------------------------------
;; Criterion 4 (contention) — hammer both orderings on real threads. The
;; invariant after every (reg-flow || destroy) pair: no ghost row, no deadlock,
;; no flake.
;; ---------------------------------------------------------------------------

(deftest reg-flow-vs-destroy-high-contention-never-leaves-a-ghost
  (testing "many concurrent (reg-flow || destroy) races leave no ghost row and never deadlock"
    (rf/reg-event :fc/set-n (fn [{:keys [db]} [_ v]] {:db (assoc db :n v)}))
    (dotimes [i 250]
      (let [fid (keyword "fc" (str "race-" i))]
        (rf/make-frame {:id fid :doc "contention frame"})
        (let [start   (CountDownLatch. 1)
              reg     (future
                        (.await start 5 TimeUnit/SECONDS)
                        (reg-flow-error-id
                          #(rf/reg-flow :contended
                                        {:frame fid :inputs [[:n]] :output-path [:out]}
                                        (fn [n] (* 2 (or n 0))))))
              destroy (future
                        (.await start 5 TimeUnit/SECONDS)
                        (frame/destroy-frame! fid))]
          (.countDown start)
          (let [reg-result     (deref reg 30000 ::timeout)
                destroy-result (deref destroy 30000 ::timeout)]
            (is (not= ::timeout reg-result)
                (str "reg-flow completed (no deadlock) on iteration " i))
            (is (not= ::timeout destroy-result)
                (str "destroy completed (no deadlock) on iteration " i))
            (is (contains? #{::no-throw :rf.error/flow-frame-not-live} reg-result)
                (str "reg-flow either registered or cleanly refused on iteration "
                     i " (got " reg-result ")"))))
        ;; The load-bearing invariant: whoever won, the destroyed frame carries
        ;; NO flow row (registration refused, or registered-then-torn-down).
        (is (not (contains? (flows/flows-snapshot) fid))
            (str "no ghost flow row for the destroyed frame on iteration " i))))))

;; ---------------------------------------------------------------------------
;; Criterion 5 — same-frame IN-DRAIN reg-flow reentrancy is preserved: a
;; reg-flow issued from inside an event handler (reentrantly, mid-drain) still
;; registers and materialises. The added revalidation passes reentrantly (the
;; drainer thread sees the SAME live incarnation token) and the serializer runs
;; the thunk directly rather than self-deadlocking on the lock.
;; ---------------------------------------------------------------------------

(deftest in-drain-reg-flow-reentrancy-preserved
  (testing "a mid-drain reg-flow still registers and materialises (no deadlock, revalidation passes)"
    (rf/make-frame {:id :fc/live :doc "in-drain reg-flow frame"})
    (rf/reg-event :fc/seed (fn [_ _] {:db {:n 5}}))
    ;; A handler that, mid-drain, registers a NEW flow reentrantly.
    (rf/reg-event :fc/install-flow
                  (fn [{:keys [db]} _]
                    (rf/reg-flow :mid
                                 {:frame :fc/live :inputs [[:n]] :output-path [:out]}
                                 (fn [n] (* 3 (or n 0))))
                    {:db db}))
    (rf/dispatch-sync [:fc/seed] {:frame :fc/live})
    (rf/dispatch-sync [:fc/install-flow] {:frame :fc/live})
    (is (contains? (get (flows/flows-snapshot) :fc/live) :mid)
        "the mid-drain reg-flow registered its row")
    (is (= 15 (:out (frame/frame-app-db-value :fc/live)))
        "the mid-drain flow materialised its output (3 × 5 = 15) on the same drain")))

;; ---------------------------------------------------------------------------
;; clear-flow stays IDEMPOTENT for an absent frame (an explicit gate acceptance
;; point) — the fix must not turn a no-op clear into a throw.
;; ---------------------------------------------------------------------------

(deftest clear-flow-idempotent-for-absent-frame
  (testing "clear-flow against a destroyed / never-registered frame is a silent no-op"
    (rf/make-frame {:id :fc/gone :doc "frame to destroy"})
    (frame/destroy-frame! :fc/gone)
    (is (nil? (flows/clear-flow :whatever {:frame :fc/gone}))
        "clear-flow on a DESTROYED frame returns nil (idempotent no-op)")
    (is (nil? (flows/clear-flow :whatever {:frame :fc/never-existed}))
        "clear-flow on a NEVER-registered frame returns nil (idempotent no-op)")))

;; ---------------------------------------------------------------------------
;; Reentrancy: `destroy-frame!` invoked from INSIDE a cold
;; `call-serialized-with-drain!` critical section on the SAME thread must run
;; the (now-serialized) liveness flip DIRECTLY, not spin-CAS forever on the
;; drain-lock its own thread already holds. This is the epoch-suite deadlock
;; regression: `perform-restore!` runs a Tool-Pair write under
;; `call-serialized-with-drain!` (holds the drain-lock, sets NO `:in-drain?`),
;; and its write body can call `destroy-frame!` on the same frame (the
;; post-liveness-teardown window). The `:serialized-holder` thread marker makes
;; that nested destroy reentrant; without it the JVM epoch suite hung.
;; ---------------------------------------------------------------------------

(deftest destroy-from-within-a-cold-serialized-write-does-not-deadlock
  (testing "destroy-frame! nested inside a same-thread call-serialized-with-drain! runs reentrantly (no self-deadlock)"
    (rf/make-frame {:id :fc/nested :doc "destroyed from within a serialized write"})
    (rf/reg-flow :held {:frame :fc/nested :inputs [[:n]] :output-path [:out]} (fn [n] (* 2 (or n 0))))
    (let [;; A cold serialized critical section (no active drain) whose body
          ;; calls destroy-frame! on the SAME frame — the Tool-Pair write shape.
          done (future
                 (frame/call-serialized-with-drain! :fc/nested
                   (fn []
                     (frame/destroy-frame! :fc/nested)
                     :ok)))]
      (is (= :ok (deref done 30000 ::timeout))
          "the nested destroy ran reentrantly and the serialized write returned (pre-fix: deadlock)"))
    (is (nil? (frame/frame :fc/nested))
        "the frame was destroyed")
    (is (not (contains? (flows/flows-snapshot) :fc/nested))
        "its flow row was torn down by destroy — no leak")))
