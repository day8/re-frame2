(ns re-frame.sub-memo-flush-path-cljs-test
  "rf2-gncxk — WHICH callers reach the layer-1 memo guard, and on which of
  them can it actually HIT?

  `subs.memo/make-layer-1-memoised-body` guards the user's sub body with
  `(= @last-db db)`. The wrapper is handed to the substrate as a
  `compute-fn`, and on the React-hook spine exactly TWO callers reach it
  (`substrate/spine.cljs`, `make-derived-value-fn`):

    * `deref-derived` — the READ path. Pull-based: it recomputes on EVERY
      `-deref`, so this guard is the only thing stopping a view render from
      re-running every sub body. It hits constantly and it is cheap here,
      because a repeat read yields the *identical* app-db object and CLJS
      `=` short-circuits on `identical?`.

    * `flush!` — the WRITE path, run once per dirty entry at epoch drain.

  rf2-gncxk records a proof that the guard is guaranteed to MISS on the
  flush path: `flush!` runs only if the value was marked dirty, which
  happens only if the source's `notify` fired, which requires movement by
  `rf=` — and `rf=` moved implies `=` differs.

  **That proof is sound for `:db` and `:runtime-db`, and UNSOUND for
  `:frame-state` — all three of which share this one wrapper**
  (`subs/single-source-input-kinds`). The difference is what sits between
  the sub and the physical frame-state atom:

    * `:db` / `:runtime-db` read a PROJECTION — a `make-derived-value` whose
      `notify` is gated on `rf=`. A commit that installs a value-equal
      app-db does not propagate, so the sub is never marked dirty and
      `flush!` never runs. The guard genuinely cannot hit on this path.

    * `:frame-state` reads the RAW physical container. A raw atom source is
      wired through the spine's per-source fan-out coordinator, whose
      `mark-dirty!` fan-out is NOT movement-gated — it fires on every
      `reset!`. And `commit-frame-transition!` short-circuits only when both
      partitions are `identical?`, rebuilding a FRESH partition map
      otherwise. So a commit whose new app-db is `=`-but-not-`identical?`
      installs a fresh frame-state, marks every `:frame-state` sub dirty,
      and reaches the wrapper on the FLUSH path with a value-equal input —
      where the guard HITS and suppresses the body.

  These two tests pin exactly that asymmetry, so a future change that
  teaches the flush path to skip the comparison cannot silently start
  re-running `:frame-state` sub bodies on value-equal commits — which would
  break Spec 006 §Invalidation algorithm (\"invalidated ONLY when an input
  changes value by `rf=`\") and turn a spec'd `:rf.sub/skip` into a
  `:rf.sub/run` (Spec 009 §`:rf.sub/skip`).

  The instrument is the `:rf.sub/skip` trace counted DURING `dispatch-sync`
  with no interleaved deref: the commit and its epoch drain both complete
  inside that call, so a skip observed there was emitted by `flush!` and by
  nothing else.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.subs :as subs]
            [re-frame.substrate.spine :as spine]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

;; A test-local React-hook spine with inert hook stubs — the same shape the
;; write-attribution harness builds. It needs no React and mounts nothing; we
;; only ever drive it through `dispatch-sync` / `subscribe`, which is enough to
;; exercise the real epoch scheduler and the real `flush!` path. (Core cannot
;; require an adapter artefact, so the spine is built here rather than borrowed
;; from `re-frame.adapter.uix`.)
(def ^:private spine-adapter
  (spine/make-react-adapter
    (spine/make-react-spine
      {:substrate-name        "gncxk-flush-path"
       :gensym-prefix-sub     "gncxk-sub-"
       :gensym-prefix-derived "gncxk-derived-"
       :gensym-prefix-use-sub "gncxk-use-sub-"
       :use-memo              (fn [t _] (t))
       :use-callback          (fn [t _] t)
       :use-context           (fn [_] nil)})
    {:kind :rf.adapter/gncxk-flush-path :frame-provider nil}))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter spine-adapter}))

(defn- skips-for
  "Count `:rf.sub/skip` trace events naming `sub-id` that are emitted while
  `body-fn` runs. Callers pass a `body-fn` that performs a `dispatch-sync`
  and NOTHING else, so every skip counted was emitted from `flush!`."
  [sub-id body-fn]
  (let [n (atom 0)
        k ::skip-probe]
    (trace-tooling/register-listener!
      k
      (fn [ev]
        (when (and (= :rf.sub/skip (:operation ev))
                   (= sub-id (get-in ev [:tags :rf.sub/id])))
          (swap! n inc))))
    (try (body-fn)
         (finally (trace-tooling/unregister-listener! k)))
    @n))

;; ---------------------------------------------------------------------------
;; :db — the guard CANNOT hit on the flush path (rf2-gncxk's proof, pinned)
;; ---------------------------------------------------------------------------

(deftest db-sub-guard-never-hits-on-the-flush-path
  (testing "a value-equal commit never marks a `:db` sub dirty, so `flush!`
            never runs and the guard is never consulted there — the app-db
            projection's `rf=` gate absorbed the write one layer up"
    (rf/with-frame :rf/default
      (let [runs (atom 0)]
        (rf/reg-event :seed   (fn [_ _] {:db {:n 42 :other :a}}))
        ;; a FRESH map that is `=` to the seeded one
        (rf/reg-event :reseed (fn [_ _] {:db {:n 42 :other :a}}))
        (rf/reg-event :bump   (fn [{:keys [db]} _] {:db (assoc db :n 43)}))
        (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
        (rf/dispatch-sync [:seed])
        (let [r (rf/subscribe [:n])]
          (is (= 42 @r))
          (is (= 1 @runs) "first deref runs the body")
          ;; The value-equal commit. No deref inside, so any skip counted
          ;; would have come from `flush!`.
          (let [flush-skips (skips-for :n #(rf/dispatch-sync [:reseed]))]
            (is (zero? flush-skips)
                "the `:db` sub is never marked dirty by a value-equal commit,
                 so the memo guard is not reached on the flush path at all"))
          (is (= 42 @r))
          (is (= 1 @runs)
              "body still has not re-run — the projection's rf= gate stopped
               propagation, and the deref-path guard absorbed the fresh object")
          ;; A genuine move DOES reach the sub, and the guard MISSES there —
          ;; which is exactly rf2-gncxk's point: on this path the comparison
          ;; is paid and is known in advance to fail.
          (rf/dispatch-sync [:bump])
          (is (= 43 @r))
          (is (= 2 @runs) "a real value move re-runs the body"))))))

;; ---------------------------------------------------------------------------
;; :frame-state — the guard DOES hit on the flush path (the counterexample)
;; ---------------------------------------------------------------------------

(deftest frame-state-sub-guard-does-hit-on-the-flush-path
  (testing "a `:frame-state` sub reads the RAW container, whose fan-out is not
            movement-gated, so a value-equal commit DOES reach the wrapper on
            the flush path — and the memo guard is what suppresses the body"
    (rf/with-frame :rf/default
      (let [runs (atom 0)]
        (subs/reg-frame-state-sub
          :whole-state
          (fn [frame-state _] (swap! runs inc) (:n (:rf.db/app frame-state))))
        (rf/reg-event :seed   (fn [_ _] {:db {:n 42 :other :a}}))
        (rf/reg-event :reseed (fn [_ _] {:db {:n 42 :other :a}}))
        (rf/reg-event :bump   (fn [{:keys [db]} _] {:db (assoc db :n 43)}))
        (rf/dispatch-sync [:seed])
        (let [r (rf/subscribe [:whole-state])]
          (is (= 42 @r))
          (let [before      @runs
                ;; The commit installs a FRESH frame-state map (the
                ;; `identical?` short-circuit in `commit-frame-transition!`
                ;; does not fire, because the new app-db is a different
                ;; object), the raw container's coordinator marks this sub
                ;; dirty unconditionally, and `flush!` invokes the wrapper.
                flush-skips (skips-for :whole-state #(rf/dispatch-sync [:reseed]))]
            (is (pos? flush-skips)
                "THE COUNTEREXAMPLE: the memo guard HIT on the flush path — so
                 the guard is not universally futile there and a flush-path
                 fast-path that skips the comparison would re-run this body")
            (is (= before @runs)
                "and the hit is load-bearing: the body did NOT re-run on a
                 value-equal commit (Spec 006 §Invalidation algorithm)"))
          (is (= 42 @r))
          ;; A genuine move still recomputes, so the sub is demonstrably live
          ;; and wired — the assertions above are not passing vacuously.
          (rf/dispatch-sync [:bump])
          (is (= 43 @r))
          (is (= 2 @runs) "a real value move re-runs the body"))))))
