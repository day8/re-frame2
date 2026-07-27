(ns re-frame.make-frame-generation-pool-window-jvm-test
  "rf2-rt4jz — a frame's GENERATION and the POOL that generation was resolved
  against must never be observable OUT OF STEP.

  THE WINDOW. `re-frame.live-frame/make-frame` does two things in order:

      (frame/upsert-frame! runnable-id record-config token-box) ;; INSTALL the generation
      (record-frame-generation-pool! runnable-id descriptors)   ;; RECORD the pool it came from

  Between them the record already carries the NEW generation while the
  provenance row (rf2-rf3zgt — `frame-generation-pool`, the per-frame row naming
  WHICH descriptor pool a generation was resolved against) still names the pool
  of some PREVIOUS incarnation, or names nothing at all. Anything that reprojects
  the frame inside that window re-resolves its composition against the WRONG pool
  and `frame/set-generation!`s the result over the generation the constructor
  had just installed. The constructor returns having silently lost its own swap.

  AND SOMETHING DOES REPROJECT INSIDE IT — synchronously, on the constructing
  thread, on BOTH hosts. `upsert-frame!` runs the `:initial-events` setup steps
  (EP-0027) INSIDE the call, after the record is published. Those steps dispatch,
  so they go through `call-with-frame-resolution`, whose read-time consult
  flushes any PENDING reprojection (rf2-h1vqa4 / rf2-9c2jf). That flush is the
  reprojection, and it runs while the provenance row is still stale.

  So the reproduction below needs NO threads and NO barrier — unlike its sibling
  `make-frame-generation-seal-race-jvm-test` (rf2-djkr0), whose window is a
  genuine two-thread race. This one is a plain ordering wart, reachable by a
  single synchronous call:

    1. `:pool-window/target` is created against explicit pool V1 and destroyed —
       the provenance row survives the destroy (documented residue: there is no
       destroy-time cleanup hook, and a REUSED id's row is supposed to be
       overwritten by the next `make-frame`).
    2. A `reg-*` arms `pending-reprojection?` (an image-loaded decoy frame is
       standing, so the hook does not take its no-image-loaded-frame skip; on the
       JVM `mark-dirty-and-schedule!` schedules no tick, so the flag simply
       waits for the next resolution).
    3. `:pool-window/target` is re-created against explicit pool V2, carrying
       `:initial-events`. The setup cascade flushes → the sweep reaches the
       just-published frame → reads the row, which still says V1 → re-resolves
       against V1 and swaps THAT over the V2 generation.

  PRE-FIX the constructor returns a frame running the V1 descriptors it was
  never asked for. POST-FIX the row is written BEFORE the engine commit (and
  rolled back exactly on failure, preserving rf2-ktmto9's no-residue contract
  EXPLICITLY rather than by ordering), so the cascade's flush re-resolves the
  frame against the pool it was actually sealed from — a byte-for-byte identical
  generation, hence no swap at all.

  WHY THE `_jvm_test` SUFFIX WHEN THE DEFECT IS COMMON. The defect is NOT
  JVM-only — the read-time flush in `call-with-frame-resolution` is synchronous
  on both hosts, so CLJS reaches the same clobber inside the same synchronous
  `make-frame` call (this is the rf2-djkr0 relationship INVERTED: that one was a
  JVM-only race with a common-code repair; this one is a common-code defect).
  The CLJS side is covered where the wart was first observed —
  `live-frame-reload-cljs-test/reload-swaps-generation-preserving-frame-memory`,
  which rides `npm run test:cljs` and reddens under an unconditional
  construction-path dirty mark. This namespace is the DETERMINISTIC, defect-
  specific reproduction, and it is JVM-scoped so it can arm the dirty flag
  without racing a scheduled `next-tick` flush (CLJS schedules one; the JVM does
  not — `mark-dirty-and-schedule!`).

  See also `make-frame-generation-seal-race-jvm-test` §4, which pins that
  rf2-djkr0's construction-path dirty mark is CONDITIONAL. That conditionality
  is what keeps this wart out of the ordinary quiet-construction path; it is not
  what closes the wart, and it stays exactly as it is."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.events :as events]
            [re-frame.image :as image]
            [re-frame.image-assembly :as asm]
            [re-frame.live-frame :as lf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; White-box handles. The whole defect lives in the relationship between two
;; PRIVATE pieces of process-local bookkeeping — the coalescing dirty flag and
;; the generation-provenance table — so the reproduction observes both directly,
;; exactly as `make-frame-generation-seal-race-jvm-test` does.
;; ---------------------------------------------------------------------------

(def ^:private dirty-flag #'lf/pending-reprojection?)
(def ^:private provenance #'lf/frame-generation-pool)

(defn- pool-row [id] (get (deref @provenance) id))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [t]
    ;; A flag left dirty by an earlier case would have this case's FIRST
    ;; resolution flush — repairing (or provoking) the staleness for the wrong
    ;; reason and reporting a result that proves nothing. Clear either side.
    (reset! @dirty-flag false)
    ;; The provenance table is process-local `defonce` bookkeeping that no
    ;; fixture resets (a destroyed id's row deliberately survives). Clear this
    ;; namespace's own ids so a re-run inside one JVM starts from the documented
    ;; "no row" state.
    (swap! @provenance dissoc :pool-window/target :pool-window/decoy :pool-window/rollback)
    (t)
    (reset! @dirty-flag false)))

;; ---------------------------------------------------------------------------
;; Two explicit descriptor pools selected by the SAME image — the reload shape
;; (`live-frame-reload-cljs-test`'s pools, renamed for this namespace). Both
;; carry the frame's namespace, so a reprojection against the WRONG one resolves
;; happily rather than zero-matching: the clobber is SILENT, which is precisely
;; what makes it worth a regression test.
;; ---------------------------------------------------------------------------

(defn- reg-desc [provenance-ns kind id impl]
  {:rf.provenance/ns provenance-ns
   :kind             kind
   :id               id
   :handler-fn       impl})

(def ^:private pool-v1
  [(reg-desc "pool.window.core" :event :pool-window/inc ::inc-v1)])

(def ^:private pool-v2
  [(reg-desc "pool.window.core" :event :pool-window/inc   ::inc-v2)
   (reg-desc "pool.window.core" :event :pool-window/reset ::reset)])

(def ^:private img
  (image/image {:id :pool-window/img :select-ns {:include ["pool.window.core"]}}))

(defn- inc-impl
  "The `:pool-window/inc` implementation the frame's CURRENT generation resolves
  — `::inc-v1` when the frame is running pool V1, `::inc-v2` for pool V2. The
  one-line discriminator between the pool the constructor asked for and the pool
  a stale provenance row reprojected it onto."
  [id]
  (:handler-fn (asm/resolve-descriptor (lf/frame-generation id) :event :pool-window/inc)))

;; ---------------------------------------------------------------------------
;; 1. THE REPRODUCTION.
;; ---------------------------------------------------------------------------

(deftest initial-events-cascade-does-not-reproject-onto-the-previous-pool
  (testing "a make-frame whose :initial-events cascade flushes a pending
            reprojection returns the generation it sealed — the flush sees the
            pool the constructor is installing, not the row left by a previous
            incarnation of the same id"
    ;; `:initial-events` dispatches `:rf/set-db`, which must resolve through the
    ;; frame's OWN sealed generation (the framework-standard registry is unioned
    ;; into every assembly). Idempotent.
    (events/register-set-db-standard!)

    ;; A standing image-loaded frame, so the registration hook below does NOT
    ;; take `mark-dirty-and-schedule!`'s no-image-loaded-frame skip (rf2-h4q6cy).
    (rf/make-frame {:id :pool-window/decoy})

    ;; The id's FIRST incarnation, against pool V1. Destroying it leaves the
    ;; provenance row behind — the documented residue the next `make-frame`
    ;; against this id is supposed to overwrite.
    (lf/make-frame {:id :pool-window/target :images [img]} pool-v1)
    (is (= ::inc-v1 (inc-impl :pool-window/target))
        "control: the first incarnation resolved against pool V1")
    (rf/destroy-frame! :pool-window/target)
    (is (= pool-v1 (pool-row :pool-window/target))
        "control: the destroyed id's provenance row still names pool V1")

    ;; ARM the coalesced reprojection. On the JVM this schedules no tick — the
    ;; flag waits for the next resolution, which will be the setup cascade's.
    (reset! @dirty-flag false)
    (rf/reg-event :pool-window/armer (fn [{:keys [db]} _] {:db db}))
    (is (true? @@dirty-flag)
        "control: a reprojection is pending as the constructor is entered")

    ;; THE CONSTRUCTION UNDER TEST. Its `:initial-events` step dispatches inside
    ;; `upsert-frame!`, whose `call-with-frame-resolution` consult flushes the
    ;; pending reprojection while the frame is published but its provenance row
    ;; is (pre-fix) still the destroyed incarnation's.
    (lf/make-frame {:id             :pool-window/target
                    :images         [img]
                    :initial-events [[:rf/set-db {:seeded true}]]}
                   pool-v2)

    (is (= ::inc-v2 (inc-impl :pool-window/target))
        (str "the constructor's own generation survived its :initial-events "
             "cascade — pre-fix the cascade's flush reprojected the frame "
             "against the PREVIOUS incarnation's pool (V1) and swapped that "
             "over the V2 generation the constructor had just installed"))
    (is (some? (asm/resolve-descriptor (lf/frame-generation :pool-window/target)
                                       :event :pool-window/reset))
        "the V2-only registration is resolvable — the frame is running V2")
    (is (= pool-v2 (pool-row :pool-window/target))
        "the provenance row names the pool the frame is actually running")
    (is (= {:seeded true} (rf/app-db-value :pool-window/target))
        "the setup cascade itself still ran (the flush is not being skipped)")))

;; ---------------------------------------------------------------------------
;; 2. THE SAME WINDOW WITHOUT :initial-events. The clobber needs a resolution
;;    inside `upsert-frame!` to trigger it, and `:initial-events` is the only
;;    one on the construction path — so a construction with no setup steps must
;;    come through unharmed both before AND after the fix. This case exists so
;;    the reproduction above cannot be mistaken for "re-construction is broken":
;;    it pins the trigger precisely.
;; ---------------------------------------------------------------------------

(deftest construction-without-setup-steps-was-never-in-the-window
  (testing "with no :initial-events there is no in-upsert resolution to flush
            the pending reprojection, so the constructor's generation stands —
            the trigger is the setup cascade, not re-construction itself"
    (rf/make-frame {:id :pool-window/decoy})
    (lf/make-frame {:id :pool-window/target :images [img]} pool-v1)
    (rf/destroy-frame! :pool-window/target)
    (reset! @dirty-flag false)
    (rf/reg-event :pool-window/armer-2 (fn [{:keys [db]} _] {:db db}))
    (is (true? @@dirty-flag) "control: a reprojection is pending")
    (lf/make-frame {:id :pool-window/target :images [img]} pool-v2)
    (is (= ::inc-v2 (inc-impl :pool-window/target)))))

;; ---------------------------------------------------------------------------
;; 3. THE ROLLBACK. Moving the provenance write AHEAD of the engine commit is
;;    only safe because the write is UNDONE exactly when the commit fails —
;;    rf2-ktmto9's contract (a failed creation records nothing; a failed
;;    re-construction preserves the OLD row) now holds by explicit rollback
;;    rather than by ordering. `live-frame-reload-cljs-test` pins the contract's
;;    OBSERVABLE consequence (reprojection still resolves against the original
;;    pool); these two pin the row itself, which is the thing the fix moved.
;; ---------------------------------------------------------------------------

(deftest failed-re-construction-restores-the-previous-provenance-row
  (testing "a re-make-frame that fails in the engine leaves the provenance row
            exactly as the successful creation left it — the early write is
            rolled back, not merely overwritten later"
    (lf/make-frame {:id :pool-window/rollback :images [img]} pool-v1)
    (is (= pool-v1 (pool-row :pool-window/rollback)) "control: V1 recorded")
    (is (thrown? clojure.lang.ExceptionInfo
                 ;; `:on-create` is retired and fails loud INSIDE the engine —
                 ;; i.e. after the early provenance write, which is the branch
                 ;; the rollback exists for.
                 (lf/make-frame {:id :pool-window/rollback :on-create [:boom]} pool-v2)))
    (is (= pool-v1 (pool-row :pool-window/rollback))
        "the failed re-construction preserved the ORIGINAL provenance row")))

(deftest failed-first-construction-leaves-no-provenance-row
  (testing "a FIRST make-frame that fails in the engine leaves NO row behind —
            the early write is removed, not left as residue for an id that
            never became a frame"
    (is (not (contains? (deref @provenance) :pool-window/never))
        "control: the id has no row")
    (is (thrown? clojure.lang.ExceptionInfo
                 (lf/make-frame {:id :pool-window/never :on-create [:boom]} pool-v1)))
    (is (not (contains? (deref @provenance) :pool-window/never))
        "the failed creation recorded nothing")))
