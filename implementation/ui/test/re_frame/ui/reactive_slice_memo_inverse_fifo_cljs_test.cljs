(ns re-frame.ui.reactive-slice-memo-inverse-fifo-cljs-test
  "rf2-2g7pxq — the CLJS slice-memo lifetime contract under an INVERSE microtask
  FIFO ordering.

  The CLJS slice memo is a module holder (`re-frame.ui.reactive/slice-memo*`)
  shared across a synchronous render pass and released at the host microtask
  checkpoint (`queueMicrotask`, aligned with the observation port's own table
  clear). `queueMicrotask` is FIFO, so a callback ENQUEUED BEFORE the first
  probe drains BEFORE the clear that probe queues — an inverse ordering. If that
  callback performs a genuinely-later render, its probes find the holder STILL
  INSTALLED and reuse it. The existing lifetime fixtures never exercise this:
  `reactive-slice-memo-render-boundary-jvm-test` pins the JVM thread-local scope
  (no microtask at all), and `reactive-slice-memo-incarnation-cljs-test` queues
  its later read AFTER the first probe, so the clear is already ahead.

  What the checkpoint honestly guarantees is the LAW at its HOST-DEFINED
  boundary: the whole host-microtask window IS one CLJS slice, so within-window
  reuse (including an interposed later render or a caught-render retry) is a
  BOUNDED, SAFE economy — never a leak — and no holder or table survives PAST the
  checkpoint into the next window. Safety rides the exact
  `(frame, frame-epoch, registry-epoch)` + incarnation tag: an interposed later
  render at a MOVED epoch mints a fresh table rather than serving a stale value
  (Spec 006 §The slice-scoped probe memo; rf2-vxgfnd.160).

  These fixtures FORCE the inverse ordering deterministically — the later render
  is a microtask enqueued before the first synchronous probe — and witness the
  contract by the cold parent's recompute count and the served value, the same
  count-based method as the render-boundary fixtures:

    - within-window reuse (same epoch): the interposed render REUSES the holder,
      so the cold parent does NOT recompute (the economy), and a render AFTER the
      checkpoint recomputes (nothing survived past it);
    - moved epoch: the interposed render's tag mismatch mints a FRESH table, so
      it serves the NEW value, never the stale memoized one (AC4);
    - caught-render retry: synchronous, before the checkpoint, so on CLJS it is
      NOT semantically distinct — it collapses to ordinary within-window sharing.

  CLJS-only by nature (the JVM has no microtask queue and never installs the
  module holder). The JVM lifetime is pinned per-host by
  `reactive-slice-memo-render-boundary-jvm-test`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures async]]
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.ui.reactive          :as reactive]))

;; `async` tests require MAP-form fixtures (a fn-form fixture unwinds its ambient
;; scope the instant it returns, before an async body resumes on a later tick), so
;; both `:each` fixtures are maps: `:async? true` gives the runtime reset its
;; persistent-`set!` map form, and the scheduler reset is a plain before/after map.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :async?  true})
  {:before #(reactive/reset-scheduler!)
   :after  #(reactive/reset-scheduler!)})

;; A cold derived parent whose compute bumps a shared counter, so a recompute is
;; observable — two sibling children share it, exactly like the render-boundary
;; fixtures. A recompute-versus-reuse count is a full witness of the lifetime:
;; reuse means the holder+table survived from the first probe into a later probe;
;; a recompute means a fresh table (a fresh slice).
(def ^:private parent-runs (atom 0))

(defn- reg-slice-subs! []
  (reset! parent-runs 0)
  (rf/reg-sub :slice/parent (fn [db _] (swap! parent-runs inc) (:n db)))
  (rf/reg-sub :slice/a :<- [:slice/parent] (fn [n _] (str "a" n)))
  (rf/reg-sub :slice/b :<- [:slice/parent] (fn [n _] (str "b" n))))

;; ---------------------------------------------------------------------------
;; 1 — within-window reuse is bounded (same epoch), and the checkpoint bounds it
;; ---------------------------------------------------------------------------

(deftest inverse-fifo-within-window-reuse-is-bounded-and-safe
  (async done
    (reg-slice-subs!)
    (let [fid     :slice/fifo-reuse
          _       (live-frame/make-frame {:id fid})
          _       (frame/replace-app-db! fid {:n 5})
          results (atom {})]
      ;; A — enqueued BEFORE the first synchronous probe. Under FIFO it drains
      ;; before the clear that probe queues, so it is a genuinely-later render
      ;; that finds the holder still installed (the inverse-FIFO counterexample).
      (js/queueMicrotask
        (fn []
          (let [runs-before @parent-runs
                v           (rf/with-frame fid (reactive/sub-read [:slice/a]))]
            (swap! results assoc
                   :A-value       v
                   :A-recomputed? (> @parent-runs runs-before)))
          ;; Z — enqueued from WITHIN A, so it drains AFTER the holder-clear the
          ;; first probe queued (which is ahead of Z): a render in the NEXT window.
          (js/queueMicrotask
            (fn []
              (let [runs-before @parent-runs
                    v           (rf/with-frame fid (reactive/sub-read [:slice/a]))]
                (swap! results assoc
                       :Z-value       v
                       :Z-recomputed? (> @parent-runs runs-before)))
              (is (= "a5" (:A-value @results))
                  "the interposed later render served the live value")
              (is (false? (:A-recomputed? @results))
                  "the interposed later render (within the host-microtask window,
                   inverse FIFO) REUSED the still-installed holder+table — the cold
                   parent did NOT recompute; the documented bounded economy")
              (is (= "a5" (:Z-value @results))
                  "the post-checkpoint render served the live value too")
              (is (true? (:Z-recomputed? @results))
                  "a render AFTER the microtask checkpoint minted a FRESH handle —
                   the parent recomputed, so nothing survived PAST the checkpoint
                   into the next window (the lifetime law)")
              (frame/destroy-frame! fid)
              (done)))))
      ;; The first synchronous probe: installs the holder and queues its clear.
      (is (= "a5" (rf/with-frame fid (reactive/sub-read [:slice/a])))
          "the first probe seeds the slice"))))

;; ---------------------------------------------------------------------------
;; 2 — the exact tag prevents a stale value even under the inverse-FIFO reuse
;; ---------------------------------------------------------------------------

(deftest inverse-fifo-moved-epoch-never-serves-a-stale-value
  (async done
    (reg-slice-subs!)
    (let [fid     :slice/fifo-moved
          _       (live-frame/make-frame {:id fid})
          _       (frame/replace-app-db! fid {:n 5})
          results (atom {})]
      ;; A performs a GENUINELY later render on a MOVED app-db (a new commit
      ;; epoch). The holder is still installed (inverse FIFO), but the reused
      ;; handle's table tag no longer matches, so it must recompute against :n 99.
      (js/queueMicrotask
        (fn []
          (frame/replace-app-db! fid {:n 99})
          (let [runs-before @parent-runs
                v           (rf/with-frame fid (reactive/sub-read [:slice/a]))]
            (swap! results assoc
                   :A-value       v
                   :A-recomputed? (> @parent-runs runs-before)))
          (js/queueMicrotask
            (fn []
              (is (= "a99" (:A-value @results))
                  "the interposed later render at a MOVED epoch saw the NEW value,
                   never the stale memoized a5 — the exact
                   (frame, frame-epoch, registry-epoch) + incarnation tag
                   re-validated the reused holder's table")
              (is (true? (:A-recomputed? @results))
                  "the moved-epoch tag mismatch minted a fresh table (recompute),
                   proving the within-window reuse is TAG-GUARDED, not blind")
              (frame/destroy-frame! fid)
              (done)))))
      (is (= "a5" (rf/with-frame fid (reactive/sub-read [:slice/a])))
          "the first probe seeds the slice at epoch(:n 5)"))))

;; ---------------------------------------------------------------------------
;; 3 — the caught-render/retry variant. On CLJS a synchronous retry runs BEFORE
;; the microtask checkpoint, so it is within-window sharing — NOT semantically
;; distinct from the interposed-microtask case. Proven here for completeness.
;; ---------------------------------------------------------------------------

(deftest caught-render-retry-reuses-within-window-without-corruption
  (reg-slice-subs!)
  (let [fid :slice/caught-retry]
    (live-frame/make-frame {:id fid})
    (frame/replace-app-db! fid {:n 5})
    ;; First render: probe the shared cold parent (installs the holder + memoizes
    ;; the parent), then the render body THROWS mid-render and the host catches it
    ;; — an aborted render whose holder is still installed (its clear is a queued
    ;; microtask that has not drained; we are still synchronous).
    (is (thrown? js/Error
          (rf/with-frame fid
            (let [v (reactive/sub-read [:slice/a])]
              (is (= "a5" v) "the probe read the live value before the render threw")
              (throw (ex-info "render blew up after the probe" {})))))
        "the aborted render's throw propagates and is caught here")
    ;; Synchronous retry within the SAME host-microtask window: the holder is
    ;; still installed, so the retry and its sibling REUSE the aborted render's
    ;; memoized table rather than opening a new slice.
    (let [retry (rf/with-frame fid (reactive/sub-read [:slice/a]))
          sib   (rf/with-frame fid (reactive/sub-read [:slice/b]))]
      (is (= "a5" retry)
          "the synchronous retry served the live value from the reused table")
      (is (= "b5" sib)
          "the sibling read the SAME window's value")
      (is (= 1 @parent-runs)
          "the cold parent computed ONCE across the caught render, the synchronous
           retry, and the sibling — all within one host-microtask window; the
           caught throw left the memoized table intact and opened no new slice"))
    (frame/destroy-frame! fid)))
