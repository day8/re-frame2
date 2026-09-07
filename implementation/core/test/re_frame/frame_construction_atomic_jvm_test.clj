(ns re-frame.frame-construction-atomic-jvm-test
  "rf2-vxgfnd.198 / rf2-vxgfnd.292 — partial frame-container allocation must be
  FAILURE-ATOMIC.

  THE DEFECT. `new-frame-record` acquires three opaque adapter values in
  sequence before the `frames` registry owns them: the physical frame-state
  container (`make-state-container`), then the app-db partition projection, then
  the runtime-db partition projection (both `make-derived-value`). If the SECOND
  or THIRD call throws, no complete frame record exists — yet the successfully
  returned earlier values were never unwound. A conforming adapter that tracks a
  state container and a first projection (installing a real host resource — a
  watch), then throws while building the second projection, was left owning that
  first projection's watch even though frame installation failed. Retried, the
  allocation counts grew with no corresponding live frame.

  THE CONTRACT (Spec 006, no new adapter fn):
    - A returned physical state container is GC-owned — no per-container
      disposal verb; `make-state-container` either throws before returning or
      returns a disposal-free value. \"Throws before returning\" is about
      RESIDUE, not ordering (rf2-vxgfnd.292): a constructor that acquires a host
      or registry resource and only then fails must release it before the throw
      escapes, because the core never receives the container and has no verb
      that could reach it.
    - Each `make-derived-value` is internally failure-atomic: a throw before
      it returns has removed any watches/host resources it installed.
    - Every successfully returned projection is disposable through the existing
      `rf.interop/dispose!` seam.
    - `new-frame-record` constructs into locals under a failure boundary and
      disposes successfully returned projections in REVERSE acquisition order if
      a later construction step throws.

  These fixtures install a CUSTOM conforming adapter (the frozen ten-fn surface,
  no eleventh function) whose `make-state-container` / first / second
  `make-derived-value` can be armed to throw, and that TRACKS the host resource
  (a watch on the source container) each returned projection owns. Each case
  asserts zero residual projection watches, no frame row, no trace-policy
  residue, and a clean retry. No sleeps, no global adapter dispose, no
  whole-process reset is used as the per-frame rollback.

  Pre-fix, the second-projection case leaves `:proj-1`'s watch owned (the
  rollback never disposed it) — `residual-watches` is non-empty and the test
  FAILS."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]))

;; ---------------------------------------------------------------------------
;; The custom tracking adapter — a conforming, ten-fn adapter that models the
;; externally-owned host resource a real substrate's derived value installs.
;;
;; State (all atoms, closed over):
;;   :throw-at        — nil | :state | :first-derived | :second-derived. Which
;;                      construction position throws. An ATOM so a test can
;;                      DISARM it (reset! … nil) and retry through the SAME
;;                      installed adapter — the retry is not a fresh process.
;;   :derived-count   — number of `make-derived-value` CALLS so far.
;;   :watched         — set of projection ids whose WATCH is currently installed
;;                      on the source container (the leak surface). A returned
;;                      projection adds its id here and removes it on dispose; a
;;                      throwing projection unwinds its OWN entry before throwing.
;;   :disposed-order  — vector of projection ids passed to a RETURNED
;;                      projection's `dispose!`, in dispose order.
;;   :containers      — vector of every state container handed out (so a test can
;;                      read the real watch set off the JVM atom).
;;   :pinned          — set of containers currently PINNED in the adapter's own
;;                      ownership registry (rf2-vxgfnd.292). The state
;;                      constructor acquires this pin BEFORE its fault point, so
;;                      an armed throw has something real to unwind. This is the
;;                      state-container leak surface: the core never receives an
;;                      un-returned container, so it holds no reference to drop
;;                      and no verb to call — only the constructor itself can
;;                      release the pin.
;;   :leak-state-pin? — when true the state constructor throws WITHOUT releasing
;;                      its pin: a deliberately NON-conformant adapter, used to
;;                      prove the zero-residue assertion is falsifiable rather
;;                      than vacuously green.
;; ---------------------------------------------------------------------------

(defn- fresh-state []
  {:throw-at        (atom nil)
   :derived-count   (atom 0)
   :watched         (atom #{})
   :disposed-order  (atom [])
   :containers      (atom [])
   :pinned          (atom #{})
   :leak-state-pin? (atom false)})

(defn- tracking-adapter [{:keys [throw-at derived-count watched disposed-order
                                 containers pinned leak-state-pin?]}]
  {:kind :custom
   :make-state-container
   (fn [initial]
     ;; Allocate and PIN first — a conforming adapter is free to acquire host
     ;; resources before it validates, and the contract is about residue, not
     ;; ordering (Spec 006 §make-state-container).
     (let [c (atom initial)]
       (swap! containers conj c)
       (swap! pinned conj c)
       (if (= :state @throw-at)
         (do
           ;; An un-returned throw must release what it acquired. Skipping this
           ;; release is exactly the contract violation `:leak-state-pin?` arms.
           (when-not @leak-state-pin?
             (swap! pinned disj c))
           (throw (ex-info "make-state-container armed to throw" {:pos :state})))
         ;; A RETURNED container is disposal-free and GC-owned, so construction
         ;; drops its pin here too — a conformant adapter must not hold the
         ;; returned container behind a reference the core cannot reach.
         (do (swap! pinned disj c)
             c))))
   :read-container     (fn [c] @c)
   :replace-container! (fn [c v] (reset! c v))
   :make-derived-value
   (fn [source-containers compute-fn]
     (let [n      (swap! derived-count inc)
           pid    (keyword "proj" (str "p" n))
           pos    (if (= n 1) :first-derived :second-derived)
           source (first source-containers)]
       ;; Install the host resource FIRST (a real watch on the source
       ;; container) and record ownership — this is exactly the watch a
       ;; spine/React derived value installs, and the thing that leaks if a
       ;; later construction step throws and no one unwinds it.
       (add-watch source pid (fn [_ _ _ _] nil))
       (swap! watched conj pid)
       (if (= pos @throw-at)
         ;; INTERNAL failure-atomicity: this projection is NOT being returned,
         ;; so it must unwind its OWN partial work (the watch + tracking)
         ;; before throwing. (Spec 006 §make-derived-value.)
         (do
           (remove-watch source pid)
           (swap! watched disj pid)
           (throw (ex-info "make-derived-value armed to throw" {:pos pos})))
         ;; A disposable derived value: `rf.interop/dispose!` fires the registered
         ;; on-dispose callback, which releases the watch + tracking.
         (let [dv (reify clojure.lang.IDeref
                    (deref [_] (apply compute-fn (map deref source-containers))))]
           (rf.interop/add-on-dispose! dv
             (fn []
               (remove-watch source pid)
               (swap! watched disj pid)
               (swap! disposed-order conj pid)))
           dv))))
   :render           (fn [_ _ _] nil)
   :render-to-string (fn [_ _] nil)
   :dispose-adapter! (fn [] nil)})

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace/clear-listeners!)
  (rf.trace/clear-frame-no-emit!)
  ;; Cold the process-owned adapter slot so each test installs its OWN tracking
  ;; adapter (NOT a global dispose used as rollback — this is fixture setup;
  ;; `rf/init!` is idempotent, so without the cold reset the previous test's
  ;; adapter would linger and each test would silently reuse it).
  (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
  (try
    (test-fn)
    (finally
      ;; Restore the shared plain-atom baseline every OTHER test namespace
      ;; expects. `rf/init!` is idempotent for the adapter it seated (rf2-kuky.1
      ;; — a DIFFERENT adapter raises `:rf.error/adapter-already-installed`), so
      ;; a custom tracking adapter left installed here would make the next
      ;; namespace's plain-atom boot throw. Cold + reinstall
      ;; plain-atom leaves the process exactly as neighbouring fixtures assume.
      (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
      (rf/init! rf.substrate.plain-atom/adapter))))

(use-fixtures :each reset-runtime)

(defn- err [thunk]
  (try (thunk) ::no-throw
       (catch Throwable e (or (:pos (ex-data e)) e))))

;; The residual watches ACTUALLY installed on the state containers, read
;; straight off the JVM atoms — the white-box ground truth the fixture's
;; `:watched` set mirrors. Zero == failure-atomic.
(defn- residual-watches [{:keys [containers]}]
  (reduce (fn [m c] (merge m (.getWatches ^clojure.lang.ARef c))) {} @containers))

;; ===========================================================================
;; make-state-container throws — nothing else was allocated.
;; ===========================================================================

(deftest state-container-throw-leaves-no-residue
  (let [state (fresh-state)]
    (rf/init! (tracking-adapter state))
    (reset! (:throw-at state) :state)
    (is (= :state (err #(rf.frame/upsert-frame! :atomic/state-throw
                                             {:rf.trace/frame-no-emit? true})))
        "construction surfaces the make-state-container throw")
    (is (nil? (rf.frame/frame :atomic/state-throw)) "no frame row installed")
    (is (nil? (rf.frame/frame-state-container :atomic/state-throw)))
    (is (empty? @(:watched state)) "no projection watch was installed")
    (is (empty? (residual-watches state)) "no residual watch on any container")
    (is (empty? @(:pinned state))
        "the registry pin the constructor acquired BEFORE its fault was released
         before the throw escaped — nothing else could have released it, since
         the core never received the container (rf2-vxgfnd.292)")
    (is (zero? @(:derived-count state)) "make-derived-value was never reached")
    (is (false? (rf.trace/frame-trace-disabled? :atomic/state-throw))
        "no trace-policy residue — the no-emit flag the failed config requested
         was never published (construction threw before policy publication)")
    (testing "clean retry through the SAME adapter"
      (reset! (:throw-at state) nil)
      (is (= :atomic/state-throw (rf.frame/upsert-frame! :atomic/state-throw {})))
      (is (some? (rf.frame/frame-state-container :atomic/state-throw))
          "the retry installs a full, live record"))))

;; ===========================================================================
;; The zero-pin assertion above is only worth anything if it can go RED. Arm a
;; deliberately NON-conformant state constructor — one that throws without
;; releasing the pin it took — and prove the fixture sees the residue.
;; ===========================================================================

(deftest state-container-pin-leak-is-detected-not-vacuously-green
  (let [state (fresh-state)]
    (rf/init! (tracking-adapter state))
    (reset! (:throw-at state) :state)
    (reset! (:leak-state-pin? state) true)
    (is (= :state (err #(rf.frame/upsert-frame! :atomic/state-leak {})))
        "construction still surfaces the throw")
    (is (nil? (rf.frame/frame :atomic/state-leak)) "still no frame row installed")
    (is (= 1 (count @(:pinned state)))
        "a non-conformant constructor strands its pin, and the fixture's residue
         check SEES it — so the conformant case's `empty?` assertion is a real
         test, not a vacuous one. Nothing in core can clean this up: the pin
         outlives the process's interest in the container")
    (testing "and each retry strands another — the leak accumulates"
      (is (= :state (err #(rf.frame/upsert-frame! :atomic/state-leak-2 {}))))
      (is (= 2 (count @(:pinned state)))))))

;; ===========================================================================
;; The FIRST projection throws — it unwinds its own partial work; nothing was
;; returned, so `new-frame-record` has nothing to dispose.
;; ===========================================================================

(deftest first-projection-throw-unwinds-own-partial-work
  (let [state (fresh-state)]
    (rf/init! (tracking-adapter state))
    (reset! (:throw-at state) :first-derived)
    (is (= :first-derived
           (err #(rf.frame/upsert-frame! :atomic/first-throw
                                      {:rf.trace/frame-no-emit? true})))
        "construction surfaces the first make-derived-value throw")
    (is (nil? (rf.frame/frame :atomic/first-throw)) "no frame row installed")
    (is (= 1 @(:derived-count state)) "only the first projection constructor ran")
    (is (empty? @(:watched state))
        "the throwing make-derived-value unwound its OWN unreturned partial work
         — its watch is gone (Spec 006 internal failure-atomicity)")
    (is (empty? (residual-watches state)) "no residual watch on any container")
    (is (empty? @(:disposed-order state))
        "new-frame-record disposed nothing — no projection had been RETURNED yet")
    (is (false? (rf.trace/frame-trace-disabled? :atomic/first-throw))
        "no trace-policy residue")
    (testing "clean retry"
      (reset! (:throw-at state) nil)
      (reset! (:derived-count state) 0)
      (is (= :atomic/first-throw (rf.frame/upsert-frame! :atomic/first-throw {})))
      (is (some? (rf.frame/frame-state-container :atomic/first-throw))))))

;; ===========================================================================
;; The SECOND projection throws — the FIRST (already returned) projection must
;; be disposed exactly once, in reverse acquisition order. THE headline case:
;; pre-fix the first projection's watch is stranded.
;; ===========================================================================

(deftest second-projection-throw-disposes-first-in-reverse-order
  (let [state (fresh-state)]
    (rf/init! (tracking-adapter state))
    (reset! (:throw-at state) :second-derived)
    (is (= :second-derived
           (err #(rf.frame/upsert-frame! :atomic/second-throw
                                      {:rf.trace/frame-no-emit? true})))
        "construction surfaces the second make-derived-value throw")
    (is (nil? (rf.frame/frame :atomic/second-throw)) "no frame row installed")
    (is (nil? (rf.frame/frame-state-container :atomic/second-throw)))
    (is (= 2 @(:derived-count state)) "both projection constructors ran")
    (is (= [:proj/p1] @(:disposed-order state))
        "the ONE successfully-returned projection (:proj/p1) was disposed exactly
         once, in reverse acquisition order (proj/p2 threw and never returned)")
    (is (empty? @(:watched state))
        "zero returned-projection watches survive — proj/p1 was disposed by the
         reverse-order rollback and proj/p2 unwound itself. PRE-FIX proj/p1's
         watch is stranded and this set is #{:proj/p1}")
    (is (empty? (residual-watches state))
        "no residual watch on any container — the leak surface is clean")
    (is (false? (rf.trace/frame-trace-disabled? :atomic/second-throw))
        "no trace-policy residue")
    (testing "the partial allocation did not grow live state — a clean retry
              installs exactly one full frame"
      (reset! (:throw-at state) nil)
      (reset! (:derived-count state) 0)
      (reset! (:disposed-order state) [])
      (is (= :atomic/second-throw (rf.frame/upsert-frame! :atomic/second-throw {})))
      (is (some? (rf.frame/frame-state-container :atomic/second-throw)))
      (is (= #{:proj/p1 :proj/p2} @(:watched state))
          "the live frame owns exactly its two partition-projection watches"))))

;; ===========================================================================
;; Happy path — the failure boundary does not disturb a successful build.
;; ===========================================================================

(deftest successful-construction-installs-both-projections
  (let [state (fresh-state)]
    (rf/init! (tracking-adapter state))
    (is (= :atomic/ok (rf.frame/upsert-frame! :atomic/ok {})))
    (is (some? (rf.frame/frame-state-container :atomic/ok)) "a full record installed")
    (is (= 2 @(:derived-count state)))
    (is (= #{:proj/p1 :proj/p2} @(:watched state))
        "both partition projections are live and own their watches")
    (is (empty? @(:pinned state))
        "the returned container carries no construction-time pin — it is
         disposal-free and GC-owned")
    (is (empty? @(:disposed-order state)) "no rollback ran on the happy path")))
