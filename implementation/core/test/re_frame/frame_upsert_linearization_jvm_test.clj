(ns re-frame.frame-upsert-linearization-jvm-test
  "rf2-vxgfnd.76 — linearize frame-id creation through the `frames` registry
  atom itself.

  THE WINDOW (JVM-only; CLJS is single-threaded). Before the A-prime fix
  `upsert-frame!` read `(get @frames
  id)` and THEN wrote (`swap! frames assoc id …` on create / `swap! frames update
  id assoc …` on re-register) as two separate steps. Two actors racing in the
  read→write window both read the id absent and both `assoc` — a LAST-WRITER
  CLOBBER that orphaned the loser's container / drain-lock / durable state; and a
  re-registration racing a concurrent `destroy-frame!` did a bare `(update m id
  assoc …)` on a dissoc'd id, RESURRECTING a partial `{:config … :generation …}`
  zombie with no state container and no `:drain-lock`.

  The fix serializes the cold create absence-check/allocation/install path so a
  losing creator allocates nothing; re-registration is contains-guarded and, if
  the id vanished under a concurrent destroy, recurs as a create. Plus an
  internal create-exclusive mode
  (`:rf.frame/must-create?`) that throws typed `:rf.error/frame-id-taken` on a
  taken id — the primitive `ui.test` rests its fresh-isolated-frame contract on.

  These fixtures open the decision window DETERMINISTICALLY via the
  `frame/*upsert-decide-probe*` JVM linearization seam (a `nil`-in-production
  dynamic hook fired once before the authoritative registry path),
  conveyed into the racing thread by `future` binding-conveyance — NO sleeps.
  Each FAILS against the pre-fix read-then-write shape (clobber / zombie / no
  such error id)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as substrate]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; Loads the trace-tooling artefact so its
            ;; The trace-tooling retention-policy late-bind is published
            ;; before these tests run (the retention override is otherwise a
            ;; silent no-op) and so the per-frame retention store is observable.
            [re-frame.trace.tooling :as trace-tooling])
  (:import [java.util.concurrent CountDownLatch CyclicBarrier
            LinkedBlockingQueue TimeUnit]))

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
;; white-box read of the process-global store config publication must linearize).
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
;; Create/create: the loser of the serialized create decision re-registers — no
;; last-writer clobber of the winner's durable record.
;; ===========================================================================

(deftest serialized-create-loser-reregisters-surgically-never-clobbers
  ;; A reads the id absent, pauses in the decide→install window; B installs its
  ;; frame and seeds durable app-db; A resumes and its create decision finds the id
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
          "B's incarnation SURVIVES — A lost the create decision and re-registered
           surgically rather than clobbering B's record (the pre-fix hole)")
      (is (= {:who :b} (frame/frame-app-db-value :race/x))
          "B's durable app-db is preserved through A's surgical re-registration —
           no runtime-state loss")
      (is (some? (frame/frame-state-container :race/x))
          "the record is FULL (a real state container), never a partial zombie"))))

(deftest concurrent-creates-linearize-to-one-full-live-frame
  ;; A barrier releases two ordinary creates together; the serialized create path
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
        "with a real state container — serialization never left a clobbered/partial record")))

(deftest losing-creator-does-not-allocate-an-unowned-state-container
  ;; A pauses before the authoritative create path. B creates and owns the sole
  ;; container; A resumes, observes B, and re-registers without calling the
  ;; adapter allocator. The former speculative shape allocated once in A above
  ;; the race and once in B, abandoning A's opaque adapter value.
  (let [reached       (CountDownLatch. 1)
        release       (CountDownLatch. 1)
        allocations   (atom 0)
        original-make substrate/make-state-container]
    (with-redefs [substrate/make-state-container
                  (fn [initial]
                    (swap! allocations inc)
                    (original-make initial))]
      (let [a (binding [frame/*upsert-decide-probe*
                        (window-probe :cc/owned reached release)]
                (future (frame/upsert-frame! :cc/owned {:tags #{:a}})))]
        (is (.await reached 10 TimeUnit/SECONDS)
            "A reached the pre-create barrier without allocating")
        (is (= :cc/owned (frame/upsert-frame! :cc/owned {:tags #{:b}}))
            "B installs the frame")
        (.countDown release)
        (is (= :cc/owned @a) "A falls through to surgical re-registration")
        (is (= 1 @allocations)
            "exactly the installed frame's state container was allocated")))))

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
;; rf2-umsyo9 — the exclusive create loser must not overwrite the WINNER's
;; frame-scoped trace policy.
;;
;; `upsert-frame!`'s two frame-scoped TRACE POLICY writes — the `set-frame-
;; no-emit!` suppression flag (always written) and the `:rf.trace/events-
;; retained` retention override — live in process-global stores SEPARATE from
;; the `frames` registry, so the registry commit does not linearize them. PR #5776
;; ran them ABOVE the CAS, so a must-create LOSER (or a create/create loser)
;; already mutated the WINNER's trace policy for the id by the time its CAS
;; failed — an exclusive collision that claims to write NOTHING corrupted the
;; live winner's tracing (self-noise from an inspector frame, a rewritten ring
;; cap). The `*upsert-decide-probe*` seam now fires BEFORE those writes, opening
;; the actual pre-side-effect window; the fix makes them ride the winning
;; branch. Fails against PR #5776's ordering (the loser's writes clobber the
;; winner's policy); passes once the writes are linearized onto the winner.
;; ===========================================================================

(deftest exclusive-create-loser-must-not-overwrite-winner-trace-policy
  ;; LOSER A: exclusive (must-create) construction with DISTINCT trace policy —
  ;; no-emit? FALSE, retention 10. A reads the id absent and pauses in the
  ;; PRE-side-effect window (after the decide snapshot, before its policy writes
  ;; / CAS). WINNER B: ordinary construction, no-emit? TRUE, retention 99 —
  ;; installs the id and writes its policy fully on the main thread. A resumes;
  ;; its create decision finds the id present → typed `:rf.error/frame-id-taken`. The
  ;; winner's suppression flag AND retention override must be UNCHANGED. Pre-fix:
  ;; A's policy writes ran on CAS loss and overwrote B's (no-emit? → false,
  ;; retention → 10) → both post-collision assertions fail.
  (let [stage        (LinkedBlockingQueue.)
        release      (CountDownLatch. 1)
        original-set trace/set-frame-no-emit!
        probe        (fn [id]
                       (when (= :tp/race id)
                         (.put stage :decide-probe)
                         (.await release 10 TimeUnit/SECONDS)))]
    ;; The auxiliary-writer barrier makes this fixture independent of where a
    ;; historical implementation placed *upsert-decide-probe*. Correct/current
    ;; ordering reaches :decide-probe first. PR #5776's pre-CAS policy ordering
    ;; reaches :policy-write first. In either case B then wins before A resumes.
    (with-redefs [trace/set-frame-no-emit!
                  (fn [frame-id no-emit? & guard]
                    (when (and (= :tp/race frame-id) (false? no-emit?))
                      (.put stage :policy-write)
                      (.await release 10 TimeUnit/SECONDS))
                    (apply original-set frame-id no-emit? guard))]
      (let [a (binding [frame/*upsert-decide-probe* probe]
                (future (err-id #(frame/upsert-frame! :tp/race
                                   {:rf.frame/must-create?     true
                                    :rf.trace/frame-no-emit?   false
                                    :rf.trace/events-retained 10}))))
            first-stage (.poll stage 10 TimeUnit/SECONDS)]
        (is (contains? #{:decide-probe :policy-write} first-stage)
            "A reached either the decision barrier (fixed ordering) or the
             policy barrier (PR #5776 ordering) before B runs")
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
            "winner B's trace SUPPRESSION survives. Under PR #5776, A resumes
             its already-entered pre-CAS writer and clears B here → fails")
        (is (= 99 (retained-cap :tp/race))
            "winner B's RETENTION override survives. Under PR #5776, A writes
             its pre-CAS cap 10 after B's cap 99 here → fails")))))

(deftest older-successful-reregistration-cannot-publish-policy-after-newer-winner
  ;; Both A and B successfully commit a surgical re-registration. A commits
  ;; config A first, then pauses immediately before its first auxiliary policy
  ;; write. B commits config + policy B and returns. When A resumes, its stale
  ;; policy publication must be rejected: the authoritative record and BOTH
  ;; auxiliary stores stay at B. This is the successful-winner race that merely
  ;; moving policy writes below the registry CAS does not close.
  (frame/upsert-frame! :tp/successful
                       {:tags #{:initial}
                        :rf.trace/frame-no-emit? true
                        :rf.trace/events-retained 5})
  (let [reached (CountDownLatch. 1)
        release (CountDownLatch. 1)
        a       (binding [frame/*upsert-policy-probe*
                          (window-probe :tp/successful reached release)]
                  (future
                    (frame/upsert-frame! :tp/successful
                                         {:tags #{:a}
                                          :rf.trace/frame-no-emit? false
                                          :rf.trace/events-retained 10})))]
    (is (.await reached 10 TimeUnit/SECONDS)
        "A committed config A and reached the pre-policy barrier")
    (is (= :tp/successful
           (frame/upsert-frame! :tp/successful
                                {:tags #{:b}
                                 :rf.trace/frame-no-emit? true
                                 :rf.trace/events-retained 99}))
        "newer actor B committed config + policy and returned")
    (.countDown release)
    (is (= :tp/successful @a) "older actor A also returns successfully")
    (is (= #{:b} (get-in (frame/frame :tp/successful) [:config :tags]))
        "the authoritative frame record remains B")
    (is (true? (trace/frame-trace-disabled? :tp/successful))
        "the no-emit auxiliary store remains B — stale A was rejected")
    (is (= 99 (retained-cap :tp/successful))
        "the retention auxiliary store remains B — stale A was rejected")))

(deftest destroyed-frame-rejects-delayed-successful-policy-publication
  ;; A successfully commits config, then pauses before publishing its auxiliary
  ;; policy. B destroys that SAME incarnation and pauses after BOTH policy stores
  ;; have been cleared (ring release precedes no-emit clear) but before the frame
  ;; record is dissociated. A must not treat the destroyed raw record's matching
  ;; token as live authority and repopulate either store.
  (frame/upsert-frame! :tp/destroy-race
                       {:rf.trace/frame-no-emit? false
                        :rf.trace/events-retained 5})
  (let [policy-reached   (CountDownLatch. 1)
        release-policy   (CountDownLatch. 1)
        teardown-cleared (CountDownLatch. 1)
        release-teardown (CountDownLatch. 1)
        original-clear   trace/clear-frame-no-emit-for!]
    (with-redefs [trace/clear-frame-no-emit-for!
                  (fn [frame-id]
                    (original-clear frame-id)
                    (when (= :tp/destroy-race frame-id)
                      (.countDown teardown-cleared)
                      (.await release-teardown 10 TimeUnit/SECONDS)))]
      (let [a (binding [frame/*upsert-policy-probe*
                        (window-probe :tp/destroy-race
                                      policy-reached release-policy)]
                (future
                  (frame/upsert-frame! :tp/destroy-race
                                       {:rf.trace/frame-no-emit? true
                                        :rf.trace/events-retained 77})))]
        (is (.await policy-reached 10 TimeUnit/SECONDS)
            "A committed config and paused before policy publication")
        (let [b (future (frame/destroy-frame! :tp/destroy-race))]
          (is (.await teardown-cleared 10 TimeUnit/SECONDS)
              "B cleared the ring and no-emit store, then paused before dissoc")
          (is (nil? (frame/frame :tp/destroy-race))
              "the matching raw record is already lifecycle-dead")
          (.countDown release-policy)
          (.countDown release-teardown)
          (is (= :tp/destroy-race @a) "A's earlier config commit still returns")
          (is (nil? @b) "B completes teardown")
          (is (nil? (frame/frame :tp/destroy-race)) "the frame stays absent")
          (is (false? (trace/frame-trace-disabled? :tp/destroy-race))
              "delayed A cannot repopulate no-emit after B cleared it")
          (is (nil? (retained-cap :tp/destroy-race))
              "delayed A cannot recreate a ring override after B released it"))))))

(deftest omitting-retention-on-reregistration-clears-frame-override
  (rf/configure! {:trace-buffer {:events-retained 7}})
  (frame/upsert-frame! :tp/inherit {:rf.trace/events-retained 99})
  (is (= 99 (retained-cap :tp/inherit))
      "precondition: the first config installed an explicit frame override")
  (frame/upsert-frame! :tp/inherit {:tags #{:override-removed}})
  (is (= 7 (retained-cap :tp/inherit))
      "omission restores the current process default instead of retaining 99")
  (is (false? (get-in @@#'re-frame.trace.tooling/trace-rings
                       [:tp/inherit :override?]))
      "the ring is marked inherited, so later process-default changes follow")
  (rf/configure! {:trace-buffer {:events-retained 3}})
  (is (= 3 (retained-cap :tp/inherit))
      "a later process-default change reaches the now-inherited frame"))
