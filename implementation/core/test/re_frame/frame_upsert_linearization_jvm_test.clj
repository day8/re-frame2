(ns re-frame.frame-upsert-linearization-jvm-test
  "rf2-vxgfnd.76 — linearize frame-id creation through the `frames` registry
  atom itself.

  THE WINDOW (JVM-only; CLJS is single-threaded so the guarded CAS is
  uncontended there). Before the A-prime fix `upsert-frame!` read `(get @frames
  id)` and THEN wrote (`swap! frames assoc id …` on create / `swap! frames update
  id assoc …` on re-register) as two separate steps. Two actors racing in the
  read→write window both read the id absent and both `assoc` — a LAST-WRITER
  CLOBBER that orphaned the loser's container / drain-lock / durable state; and a
  re-registration racing a concurrent `destroy-frame!` did a bare `(update m id
  assoc …)` on a dissoc'd id, RESURRECTING a partial `{:config … :generation …}`
  zombie with no state container and no `:drain-lock`.

  The fix makes decide+install a GUARDED-CAS RETRY loop through the `frames`
  atom (the `set-generation!` discipline): a create installs via `swap-vals!`
  with a contains-guard and, on CAS loss, recurs (now a re-registration); a
  re-registration is contains-guarded too and, if the id vanished under a
  concurrent destroy, recurs as a create. Plus an internal create-exclusive mode
  (`:rf.frame/must-create?`) that throws typed `:rf.error/frame-id-taken` on a
  taken id — the primitive `ui.test` rests its fresh-isolated-frame contract on.

  These fixtures open the read→CAS window DETERMINISTICALLY via the
  `frame/*upsert-decide-probe*` JVM linearization seam (a `nil`-in-production
  dynamic hook fired once after the decide read and before the guarded CAS),
  conveyed into the racing thread by `future` binding-conveyance — NO sleeps.
  Each FAILS against the pre-fix read-then-write shape (clobber / zombie / no
  such error id)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; Loads the trace-tooling artefact so its
            ;; `:trace.tooling/set-frame-events-retained!` late-bind is published
            ;; before these tests run (the retention override is otherwise a
            ;; silent no-op) and so the per-frame retention store is observable.
            [re-frame.trace.tooling :as trace-tooling])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier TimeUnit]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  ;; Clear the process-global trace-policy stores so a frame-scoped no-emit /
  ;; retention override written by one test never leaks into the next
  ;; (rf2-umsyo9 — these stores are SEPARATE from `frames`, which the reset
  ;; above does unwind).
  (trace/clear-frame-no-emit!)
  (trace-tooling/clear-trace-rings!)
  (rf/init! plain-atom/adapter)
  (test-fn))

;; The per-frame retention cap the winning config's `:rf.trace/events-retained`
;; override installed, read straight from the trace-tooling ring store (a
;; white-box read of the process-global store the guarded CAS must linearize).
;; nil when no override ring was written for the frame.
(defn- retained-cap [frame-id]
  ;; Double deref: `#'…/trace-rings` is the VAR, its value is the store ATOM,
  ;; and the atom's value is the rings map.
  (get-in @@#'re-frame.trace.tooling/trace-rings [frame-id :events-retained]))

(use-fixtures :each reset-runtime)

(defn- err-id [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

;; A probe that trips a "reached the window" latch for the target id, then blocks
;; on a "release" latch — the deterministic read→CAS window opener.
(defn- window-probe [target reached release]
  (fn [id]
    (when (= id target)
      (.countDown ^CountDownLatch reached)
      (.await ^CountDownLatch release 10 TimeUnit/SECONDS))))

;; ===========================================================================
;; Create/create: the loser of the guarded CAS re-registers surgically — no
;; last-writer clobber of the winner's durable record.
;; ===========================================================================

(deftest guarded-cas-create-loser-reregisters-surgically-never-clobbers
  ;; A reads the id absent, pauses in the decide→install window; B installs its
  ;; frame and seeds durable app-db; A resumes and its guarded CAS finds the id
  ;; PRESENT, so it re-registers surgically (metadata refresh, runtime state
  ;; preserved) instead of assoc-clobbering B's record. B's incarnation + seeded
  ;; app-db SURVIVE. Pre-fix: A's unconditional `assoc` clobbered B → this fails.
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a (binding [frame/*upsert-decide-probe* (window-probe :race/x reached release)]
            (future (frame/upsert-frame! :race/x {:tags #{:a}})))]
    (.await reached 10 TimeUnit/SECONDS)
    ;; B installs on the main thread (its *upsert-decide-probe* is nil — the
    ;; binding scope above closed once the future was created).
    (frame/upsert-frame! :race/x {:tags #{:b}})
    (frame/replace-app-db! :race/x {:who :b})
    (let [token-b (frame/frame-incarnation-token :race/x)]
      (.countDown release)
      (is (= :race/x @a) "A's upsert returns the id — it re-registered, did not throw")
      (is (identical? token-b (frame/frame-incarnation-token :race/x))
          "B's incarnation SURVIVES — A lost the guarded CAS and re-registered
           surgically rather than clobbering B's record (the pre-fix hole)")
      (is (= {:who :b} (frame/frame-app-db-value :race/x))
          "B's durable app-db is preserved through A's surgical re-registration —
           no runtime-state loss")
      (is (some? (frame/frame-state-container :race/x))
          "the record is FULL (a real state container), never a partial zombie"))))

(deftest concurrent-creates-linearize-to-one-full-live-frame
  ;; A barrier releases two ordinary creates together; the guarded CAS
  ;; linearizes them — exactly one live, FULL record survives (both calls return
  ;; the id; the loser re-registered).
  (let [gate (CyclicBarrier. 2)
        mk   (fn [tag] (future (.await gate 10 TimeUnit/SECONDS)
                               (frame/upsert-frame! :cc/x {:tags #{tag}})))
        r1   (mk :one)
        r2   (mk :two)
        outs [@r1 @r2]]
    (is (= [:cc/x :cc/x] outs) "both calls returned the id (one installed, one re-registered)")
    (is (some? (frame/frame :cc/x)) "one live frame exists")
    (is (some? (frame/frame-state-container :cc/x))
        "with a real state container — the CAS never left a clobbered/partial record")))

;; ===========================================================================
;; Re-register vs destroy: the losing re-registration recurs as a CREATE — no
;; zombie resurrection of a dissoc'd record.
;; ===========================================================================

(deftest reregister-losing-to-a-concurrent-destroy-recurs-as-create-no-zombie
  ;; A re-registers a LIVE frame; it pauses in the window; B destroys the frame;
  ;; A resumes and its guarded update finds the id GONE, so it recurs as a fresh
  ;; CREATE — NOT a bare `(update m id assoc …)` that would resurrect a partial
  ;; `{:config :generation}` zombie (no container, no drain-lock). Pre-fix: the
  ;; bare update resurrected the zombie → this fails.
  (frame/upsert-frame! :zombie/x {:tags #{:orig}})
  (let [token-orig (frame/frame-incarnation-token :zombie/x)
        reached    (CountDownLatch. 1)
        release    (CountDownLatch. 1)
        a (binding [frame/*upsert-decide-probe* (window-probe :zombie/x reached release)]
            (future (frame/upsert-frame! :zombie/x {:tags #{:reregister}})))]
    (.await reached 10 TimeUnit/SECONDS)
    (frame/destroy-frame! :zombie/x)
    (is (nil? (frame/frame :zombie/x)) "B's destroy forgot the record before A resumes")
    (.countDown release)
    (is (= :zombie/x @a) "A completes and returns the id")
    (is (some? (frame/frame :zombie/x)) "A re-created the frame (the guarded update recurred as a create)")
    (is (some? (frame/frame-state-container :zombie/x))
        "the re-created record is FULL — a state container is present, NOT a
         partial {:config :generation} zombie the bare update would resurrect")
    (let [token-new (frame/frame-incarnation-token :zombie/x)]
      (is (and (some? token-new) (not (identical? token-orig token-new)))
          "a DISTINCT fresh incarnation — the id was rebuilt, not zombie-patched
           onto the destroyed record"))))

;; ===========================================================================
;; must-create (create-exclusive) — the ui.test primitive.
;; ===========================================================================

(deftest must-create-throws-typed-collision-on-an-already-live-id
  (frame/upsert-frame! :mc/taken {})
  (is (= :rf.error/frame-id-taken
         (err-id #(frame/upsert-frame! :mc/taken {:rf.frame/must-create? true})))
      "must-create against a LIVE id throws the typed :rf.error/frame-id-taken —
       it never adopts or surgically refreshes the pre-existing frame")
  (is (some? (frame/frame-state-container :mc/taken))
      "the pre-existing frame is untouched by the rejected exclusive construction"))

(deftest must-create-installs-cleanly-on-a-free-id
  (is (= :mc/free (frame/upsert-frame! :mc/free {:rf.frame/must-create? true}))
      "must-create on a free id installs normally and returns the id")
  (is (some? (frame/frame-state-container :mc/free)) "a full record was installed")
  (is (false? (contains? (:config (frame/frame :mc/free)) :rf.frame/must-create?))
      "the construction-only :rf.frame/must-create? key is stripped from stored config"))

(deftest must-create-losing-the-window-race-throws-typed-collision
  ;; A requests must-create, reads the id absent, pauses in the window; B installs
  ;; the id; A resumes and, finding the id now live, throws the typed collision
  ;; (exclusive mode never adopts). B's frame is untouched.
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a (binding [frame/*upsert-decide-probe* (window-probe :mc/race reached release)]
            (future (err-id #(frame/upsert-frame! :mc/race {:rf.frame/must-create? true}))))]
    (.await reached 10 TimeUnit/SECONDS)
    (frame/upsert-frame! :mc/race {:tags #{:b}})
    (let [token-b (frame/frame-incarnation-token :mc/race)]
      (.countDown release)
      (is (= :rf.error/frame-id-taken @a)
          "A's exclusive construction loses the window race → typed collision, not adoption")
      (is (identical? token-b (frame/frame-incarnation-token :mc/race))
          "B's frame is untouched — the losing exclusive construction wrote nothing"))))

;; ===========================================================================
;; rf2-umsyo9 — the exclusive-CAS loser must not overwrite the WINNER's
;; frame-scoped trace policy.
;;
;; `upsert-frame!`'s two frame-scoped TRACE POLICY writes — the `set-frame-
;; no-emit!` suppression flag (always written) and the `:rf.trace/events-
;; retained` retention override — live in process-global stores SEPARATE from
;; the `frames` registry, so the guarded CAS does not linearize them. PR #5776
;; ran them ABOVE the CAS, so a must-create LOSER (or a create/create loser)
;; already mutated the WINNER's trace policy for the id by the time its CAS
;; failed — an exclusive collision that claims to write NOTHING corrupted the
;; live winner's tracing (self-noise from an inspector frame, a rewritten ring
;; cap). The `*upsert-decide-probe*` seam now fires BEFORE those writes, opening
;; the actual pre-side-effect window; the fix makes them ride the winning
;; branch. Fails against PR #5776's ordering (the loser's writes clobber the
;; winner's policy); passes once the writes are linearized onto the winner.
;; ===========================================================================

(deftest exclusive-cas-loser-must-not-overwrite-winner-trace-policy
  ;; LOSER A: exclusive (must-create) construction with DISTINCT trace policy —
  ;; no-emit? FALSE, retention 10. A reads the id absent and pauses in the
  ;; PRE-side-effect window (after the decide snapshot, before its policy writes
  ;; / CAS). WINNER B: ordinary construction, no-emit? TRUE, retention 99 —
  ;; installs the id and writes its policy fully on the main thread. A resumes;
  ;; its guarded CAS finds the id present → typed `:rf.error/frame-id-taken`. The
  ;; winner's suppression flag AND retention override must be UNCHANGED. Pre-fix:
  ;; A's policy writes ran on CAS loss and overwrote B's (no-emit? → false,
  ;; retention → 10) → both post-collision assertions fail.
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a (binding [frame/*upsert-decide-probe* (window-probe :tp/race reached release)]
            (future (err-id #(frame/upsert-frame! :tp/race
                               {:rf.frame/must-create?    true
                                :rf.trace/frame-no-emit?  false
                                :rf.trace/events-retained 10}))))]
    (.await reached 10 TimeUnit/SECONDS)
    (frame/upsert-frame! :tp/race {:rf.trace/frame-no-emit?  true
                                   :rf.trace/events-retained 99})
    (is (true? (trace/frame-trace-disabled? :tp/race))
        "precondition: winner B registered trace-disabled (no-emit? true)")
    (is (= 99 (retained-cap :tp/race))
        "precondition: winner B installed its retention override (99)")
    (.countDown release)
    (is (= :rf.error/frame-id-taken @a)
        "the exclusive loser threw the typed collision — it installed nothing")
    (is (true? (trace/frame-trace-disabled? :tp/race))
        "winner B's trace SUPPRESSION survives the collision — the loser never
         overwrote the winner's frame-scoped no-emit flag (rf2-umsyo9). Pre-fix:
         the loser's `set-frame-no-emit! false` on CAS loss cleared it → fails")
    (is (= 99 (retained-cap :tp/race))
        "winner B's RETENTION override survives — the loser never rewrote the
         winner's per-frame :rf.trace/events-retained store (rf2-umsyo9).
         Pre-fix: the loser's write dropped the cap to 10 → fails")))
