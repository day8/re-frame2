(ns re-frame.make-frame-generation-seal-race-jvm-test
  "rf2-djkr0 — a PARTIALLY-CONSTRUCTED frame must never be observable as LIVE.

  THE WINDOW (JVM-only). `re-frame.live-frame/make-frame` does two things in
  order, with real work between them:

      generation  (asm/assemble-default …)   ;; SEAL   — the pool, frozen
      …
      (frame/upsert-frame! runnable-id …)    ;; PUBLISH — the record appears

  Between them the frame EXISTS as a sealed generation but is absent from
  `frames`, so `live-frame/live-frame-ids` does not see it. A `reg-*` issued on
  another thread in that gap fires the registration hook →
  `reproject-on-registration-change!` → `mark-dirty-and-schedule!`, which SKIPS
  when `(seq (live-frame-ids))` is empty — the rf2-h4q6cy hot-path
  optimisation, and a correct one: reprojection only ever touches image-loaded
  frames, so with none there is genuinely nothing to mark. The in-flight frame
  does not count, so NO dirty mark is set. `upsert-frame!` then publishes a
  record whose generation PREDATES that registration, and nothing is left to
  flush it. The frame is permanently stale: `dispatch` reports
  `:rf.error/no-such-handler` for a handler `registrar/lookup` is holding at
  that very moment.

  THE INVARIANT, stated as this namespace tests it: *a registration that lands
  while a frame is mid-construction is never lost — the constructor itself
  performs the dirty-mark the registration hook could not make on its behalf,
  because at hook time the frame was not yet in `frames` to be seen.*

  SCOPE — and why it needed reproducing rather than reasoning about. This is
  the residual half of rf2-9c2jf (`ensure-reprojection-installed!` publishing
  its once-flag before its side effects), deliberately not folded into it, and
  it is NARROWER: it bites only when NO image-loaded frame exists at hook time,
  i.e. the FIRST `make-frame` in a process racing a `reg-*`. With any other
  frame already live the hook DOES mark dirty, and by the time the read-time
  flush in `call-with-frame-resolution` runs, the new frame IS in `frames`, so
  the resilient sweep picks it up. `frame-not-first-in-the-process-is-swept-by-
  the-ordinary-mark` below pins that half, so the fix cannot be mistaken for
  the thing that was already working.

  THE HARNESS is the rf2-9c2jf one's sibling: a deterministic barrier, no
  sleeps deciding anything. It parks the constructor inside the window using
  `frame/*upsert-decide-probe*` — the `nil`-in-production JVM linearization
  seam `frame_upsert_linearization_jvm_test.clj` already uses, fired ONCE after
  the per-id construction transaction is acquired and BEFORE the authoritative
  registry decision. Parked there the generation is sealed and the record is
  provably not yet in `frames`, which is exactly the window's interior. The
  racing `reg-*` then runs on the test thread while the constructor is held.

  CLJS IS UNAFFECTED and has no counterpart here: it is single-threaded, so no
  `reg-*` can interleave between the seal and the publish — both run inside one
  synchronous `make-frame` call. The fix is nonetheless in `.cljc` common code
  (an unconditional mark on the construction path), where on CLJS it is simply
  a mark that the very next `reg-*` would have made anyway."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

;; ---------------------------------------------------------------------------
;; White-box handle on the coalescing dirty flag.
;;
;; A deliberate private-var read: the whole defect is that this flag is NOT set
;; when it must be, so observing it is how the narrow-case control below proves
;; the hot-path skip is still intact after the fix.
;; ---------------------------------------------------------------------------

(def ^:private dirty-flag #'live-frame/pending-reprojection?)

(defn- reset-runtime [test-fn]
  (try
    (registrar/clear-all!)
    (reset! frame/frames {})
    (flows/reset-flows!)
    (schemas/clear-schemas-by-frame!)
    (trace/clear-listeners!)
    (rf/init! plain-atom/adapter)
    ;; Framework registrations live at namespace-load time; `clear-all!` wiped
    ;; them. Re-eval so the rest of the suite is not left short.
    (require 're-frame.routing :reload)
    (require 're-frame.ssr :reload)
    ;; LOAD-BEARING, and it must come LAST. `registrar/clear-all!` is itself a
    ;; source-store change and marks the projection dirty (via the removal
    ;; late-bind) whenever a live frame is still standing from an earlier test.
    ;; A test that starts with the flag already true would have its first
    ;; resolution flush — repairing the staleness for the wrong reason and
    ;; reporting a green that proves nothing.
    (reset! @dirty-flag false)
    (test-fn)
    (finally
      (registrar/clear-all!)
      (reset! frame/frames {})
      (reset! @dirty-flag false))))

(use-fixtures :each reset-runtime)

;; A latch we expect to FIRE waits this long; it only bounds a hang.
(def ^:private ^:const settle-ms 10000)

(defn- await! [^CountDownLatch latch ^long ms]
  (.await latch ms TimeUnit/MILLISECONDS))

;; Park the constructor of `target` INSIDE the window: the transaction is held,
;; the generation is sealed, the record is not yet in `frames`.
(defn- window-probe [target ^CountDownLatch reached ^CountDownLatch release]
  (fn [id]
    (when (= id target)
      (.countDown reached)
      (.await release settle-ms TimeUnit/MILLISECONDS))))

;; ---------------------------------------------------------------------------
;; 1. THE REPRODUCTION. The first frame in the process, racing a `reg-*`.
;; ---------------------------------------------------------------------------

(deftest registration-racing-the-generation-seal-is-not-lost
  (testing "a reg-* issued while the FIRST make-frame is between its sealed
            generation and its published record is not lost — the frame is not
            left permanently stale"
    (let [reached (CountDownLatch. 1)
          release (CountDownLatch. 1)
          runs    (atom 0)
          errors  (atom [])]
      (rf/register-listener! :errors ::recorder
                             (fn [record] (swap! errors conj record)))
      (let [a (binding [frame/*upsert-decide-probe*
                        (window-probe :seal-race/a reached release)]
                (future (rf/make-frame {:id  :seal-race/a
                                        :doc "the constructor parked mid-window"})))]
        (is (await! reached settle-ms)
            "the constructor parked inside the window")

        ;; THE PRECONDITION that makes this defect the narrow one it is. The
        ;; in-flight frame is NOT in `frames`, so the registration hook about
        ;; to fire sees nothing image-loaded and takes the hot-path skip.
        (is (nil? (frame/frame :seal-race/a))
            "the parked constructor's record is not published yet")
        (is (empty? (live-frame/live-frame-ids))
            "NO image-loaded frame exists — the hook's skip will fire")

        ;; THE RACING REGISTRATION. Its hook fires now, against an empty
        ;; live-frame set, and marks nothing.
        (rf/reg-event :seal-race/late
          (fn [{:keys [db]} _]
            (swap! runs inc)
            {:db (assoc db :late :ran)}))

        (.countDown release)
        (is (some? (deref a settle-ms ::timeout)) "the constructor completed")
        (is (some? (frame/frame :seal-race/a)) "the frame is published")

        ;; THE END STATE. Without the fix the frame resolves through a
        ;; generation sealed BEFORE `:seal-race/late` was registered, so this
        ;; dispatch is a no-op reported as `:rf.error/no-such-handler` — for a
        ;; handler `registrar/lookup` is holding at this very moment.
        (rf/dispatch-sync [:seal-race/late] {:frame :seal-race/a})
        (rf/unregister-listener! :errors ::recorder)

        (is (empty? (filter #(= :rf.error/no-such-handler (:error %)) @errors))
            (str "the frame resolved :seal-race/late through a generation "
                 "sealed before it was registered — the registration issued "
                 "during construction was LOST"))
        (is (= 1 @runs)
            "the handler registered during construction ran exactly once")
        (is (= :ran (:late (rf/app-db-value :seal-race/a)))
            "the frame is not permanently stale")))))

;; ---------------------------------------------------------------------------
;; 2. THE SCOPE CONTROL. The same race with a frame ALREADY live takes the
;;    ordinary hook path — this half was never broken, and must stay that way.
;; ---------------------------------------------------------------------------

(deftest frame-not-first-in-the-process-is-swept-by-the-ordinary-mark
  (testing "with an image-loaded frame already standing, the racing reg-*'s
            hook marks dirty as usual and the in-flight frame is swept by the
            read-time flush — the half rf2-djkr0 was NOT about"
    (let [reached (CountDownLatch. 1)
          release (CountDownLatch. 1)
          runs    (atom 0)]
      (rf/make-frame {:id :seal-race/incumbent})
      (reset! @dirty-flag false)
      (is (seq (live-frame/live-frame-ids))
          "an image-loaded frame is standing before the race")
      (let [a (binding [frame/*upsert-decide-probe*
                        (window-probe :seal-race/b reached release)]
                (future (rf/make-frame {:id :seal-race/b})))]
        (is (await! reached settle-ms) "the constructor parked inside the window")
        (rf/reg-event :seal-race/late-b
          (fn [{:keys [db]} _]
            (swap! runs inc)
            {:db (assoc db :late :ran)}))
        (is (true? @@dirty-flag)
            "the hook DID mark dirty — an image-loaded frame was visible to it")
        (.countDown release)
        (is (some? (deref a settle-ms ::timeout)) "the constructor completed")
        (rf/dispatch-sync [:seal-race/late-b] {:frame :seal-race/b})
        (is (= 1 @runs) "the handler ran")
        (is (= :ran (:late (rf/app-db-value :seal-race/b))))))))

;; ---------------------------------------------------------------------------
;; 3. THE HOT-PATH SKIP the fix must not spend. `mark-dirty-and-schedule!`'s
;;    no-image-loaded-frame skip (rf2-h4q6cy) is a real win — every
;;    `reg-event` / `reg-sub` / `reg-fx` fires that hook, and the overwhelming
;;    majority run at app boot or in handler-only tests with no frame standing.
;;    The rf2-djkr0 repair belongs on the CONSTRUCTION path, not in the hook.
;; ---------------------------------------------------------------------------

(deftest registration-with-no-live-frame-still-marks-nothing
  (testing "a reg-* with no image-loaded frame standing sets no dirty flag and
            schedules no flush — the rf2-h4q6cy hot-path skip survives the
            rf2-djkr0 fix"
    (is (empty? (live-frame/live-frame-ids)) "no frame is standing")
    (is (false? @@dirty-flag) "the flag starts clear")
    (rf/reg-event :seal-race/boot-time (fn [{:keys [db]} _] {:db db}))
    (rf/reg-sub :seal-race/boot-sub (fn [db _] db))
    (is (false? @@dirty-flag)
        "the registration hook took the no-image-loaded-frame skip")))

;; ---------------------------------------------------------------------------
;; 4. THE MARK IS CONDITIONAL, and that is load-bearing rather than an
;;    optimisation. An UNCONDITIONAL mark on the construction path leaves the
;;    projection dirty after every `make-frame`, and a flag set here is flushed
;;    by some later, unrelated resolution — which is observable: it reprojects a
;;    frame mid-`upsert-frame!`, before `record-frame-generation-pool!` has
;;    updated the provenance row, so a re-`make-frame` reload resolves against
;;    the PREVIOUS pool and its freshly installed generation is clobbered.
;;    (`live-frame-reload-cljs-test/reload-swaps-generation-preserving-frame-
;;    memory` is where that surfaces.) An ordinary construction must therefore
;;    leave global projection state exactly as it found it.
;; ---------------------------------------------------------------------------

(deftest construction-with-no-racing-registration-leaves-the-projection-clean
  (testing "a make-frame that nothing raced marks nothing — the pool did not
            move across its seal→publish window"
    (is (false? @@dirty-flag) "the flag starts clear")
    (rf/reg-event :seal-race/quiet (fn [{:keys [db]} _] {:db db}))
    (rf/make-frame {:id :seal-race/quiet-frame})
    (is (false? @@dirty-flag)
        "an unraced construction left the projection clean")
    ;; And a SECOND construction with a frame already standing likewise — this
    ;; is the shape that leaks a flag into an unrelated test when the mark is
    ;; unconditional.
    (rf/make-frame {:id :seal-race/quiet-frame-2})
    (is (false? @@dirty-flag)
        "a second unraced construction left the projection clean too")))
