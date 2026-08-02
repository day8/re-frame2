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

  rf2-gncxk was filed on a proof that the guard is guaranteed to MISS on
  the flush path: `flush!` runs only if the value was marked dirty, which
  happens only if the source's `notify` fired, which requires movement by
  `rf=` — and `rf=` moved implies `=` differs.

  **THAT PROOF IS FALSE, and the correction is the whole point of this
  namespace** (rf2-gncxk.1's design pass; merged-PR audit #7233). It
  establishes \"the source moved since the source's PREVIOUS NOTIFY\". The
  guard asks a DIFFERENT question — \"did the input move since THE WRAPPER
  LAST RAN\" — and two reachable interleavings separate them, in both of
  which the guard correctly HITS on the flush path even for `:db`:

    * **(a) DEREF-BETWEEN.** The source moves P → N and marks the sub
      dirty. Anything derefs the sub before the drain reaches its queued
      flush; `deref-derived` is PULL-based, so that read recomputes against
      N and leaves `last-db` = N. The queued `flush!` then arrives with N
      against N.

    * **(b) RETURN-TO-EQUAL.** Two writes inside one drain take app-db
      A → B → A′ with A′ `=` A but a fresh object. The sub's `mark-dirty!`
      dedups, so ONE flush arrives with A′ against `last-db` = A: the
      source moved twice by `rf=`, `=` says equal.

  So no fast path may key on \"am I on the flush path?\". The landed fix
  (rf2-gncxk.1) keys on an exact fact about the SOURCE instead — the
  `re-frame.movement/IMovementWitness` departure value — and this namespace
  is where its consumer-side obligation is pinned: **C1, VERDICT-
  PRESERVING**. The witness may only skip a comparison whose answer it
  already determines, so every deftest below asserts a `{hit, miss}`
  verdict that must be bit-identical with and without it.

  There IS still an asymmetry between the single-source kinds, and the
  first two tests pin it. All three share this one wrapper
  (`subs/single-source-input-kinds`); what differs is what sits between the
  sub and the physical frame-state atom:

    * `:db` / `:runtime-db` read a PROJECTION — a `make-derived-value` whose
      `notify` is gated on `rf=`. A commit that installs a value-equal
      app-db does not propagate, so with NO interleaving the sub is never
      marked dirty and `flush!` never runs: the guard is not reached at all.
      (Not \"reached and misses\" — never consulted. The interleavings above
      are how it IS reached, and there it hits.)

    * `:frame-state` reads the RAW physical container. A raw atom source is
      wired through the spine's per-source fan-out coordinator, whose
      `mark-dirty!` fan-out is NOT movement-gated — it fires on every
      `reset!`. And `commit-frame-transition!` short-circuits only when both
      partitions are `identical?`, rebuilding a FRESH partition map
      otherwise. So a commit whose new app-db is `=`-but-not-`identical?`
      installs a fresh frame-state, marks every `:frame-state` sub dirty,
      and reaches the wrapper on the FLUSH path with a value-equal input —
      where the guard HITS and suppresses the body, with no interleaving
      needed at all.

  Between them these four tests mean a change that teaches the flush path
  to skip the comparison cannot silently start re-running sub bodies on
  value-equal input — which would break Spec 006 §Invalidation algorithm
  (\"invalidated ONLY when an input changes value by `rf=`\") and turn a
  spec'd `:rf.sub/skip` into a `:rf.sub/run` (Spec 009 §`:rf.sub/skip`).

  The instrument is the `:rf.sub/skip` trace counted DURING `dispatch-sync`:
  the commit and its epoch drain both complete inside that call. In the two
  no-interleaving tests nothing else runs there, so a skip observed is a
  `flush!` skip and nothing else. The two interleaving tests interpose
  DELIBERATELY, from a watcher on the app-db projection — which runs inside
  that projection's `notify` fan-out, after the witness is armed and always
  before the drain reaches the sub's queued flush thunk. That is the seam
  production reaches whenever anything reads or writes app-db from inside
  the fan-out, and it is the only seam from which either interleaving can
  be driven: `dispatch-sync` opens AND closes the epoch inside one call.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
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
;; :db, NO INTERLEAVING — a value-equal commit never reaches the guard at all
;;
;; Narrowed by merged-PR audit #7233. This test proves exactly one scenario:
;; a lone value-equal commit with nothing interleaved. It does NOT prove the
;; general claim its old name (`db-sub-guard-never-hits-on-the-flush-path`)
;; made — see the two interleaving tests below, where the guard DOES hit on
;; the flush path for a `:db` sub.
;; ---------------------------------------------------------------------------

(deftest db-sub-value-equal-commit-never-reaches-the-guard
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
          ;; A genuine move DOES reach the sub, and with nothing interleaved
          ;; the guard MISSES there — the comparison is paid and fails. That
          ;; is the case rf2-gncxk was filed on; it is the wrapper's FIRST
          ;; invocation after a movement, and it is exactly the one the
          ;; landed movement witness proves in advance (`-moved-from` is
          ;; still `identical?` to `last-db` here, so the `=` walk is
          ;; skipped and the verdict is the same miss).
          (rf/dispatch-sync [:bump])
          (is (= 43 @r))
          (is (= 2 @runs) "a real value move re-runs the body"))))))

;; ---------------------------------------------------------------------------
;; :frame-state, NO INTERLEAVING — the guard hits on the flush path anyway
;;
;; The one kind that needs no interleaving to get there, which is why it was
;; the original counterexample. It is ALSO the kind the landed witness cannot
;; touch: a raw physical container does not implement `IMovementWitness`, so
;; `subs.memo`'s `witness-src` is nil here and the guard expression is
;; byte-for-byte the one that shipped (pinned in
;; `movement_witness_cljs_test.cljs`).
;; ---------------------------------------------------------------------------

(deftest frame-state-sub-guard-does-hit-on-the-flush-path
  (testing "a `:frame-state` sub reads the RAW container, whose fan-out is not
            movement-gated, so a value-equal commit DOES reach the wrapper on
            the flush path — with nothing interleaved — and the memo guard is
            what suppresses the body"
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

;; ---------------------------------------------------------------------------
;; :db WITH INTERLEAVING — the guard hits on the flush path here too
;;
;; Added by merged-PR audit #7233. These are the two cases rf2-gncxk.1's
;; design pass named when it falsified the bead's own premise, and neither
;; had a consumer-level pin: `movement_witness_cljs_test.cljs` pins the
;; SOURCE's W1/W2, not the wrapper's C1 verdict along these paths.
;;
;; Both interpose from a watcher on the app-db projection. That watcher runs
;; inside the projection's own `notify` fan-out — so the witness is already
;; armed (`notify` `vreset!`s it BEFORE fanning out) and the sub's queued
;; flush thunk has not run yet, whichever order the fan-out visits the two
;; watchers in. It is the only seam these interleavings can be driven from:
;; `dispatch-sync` opens the epoch and drains it inside one call.
;; ---------------------------------------------------------------------------

(deftest db-sub-guard-hits-on-the-flush-path-when-a-deref-interleaves
  (testing "INTERLEAVING (a), DEREF-BETWEEN. The source moves P -> N and marks
            the sub dirty; something derefs the sub before the drain reaches
            its queued flush. `deref-derived` is PULL-based, so that read
            recomputes against N and leaves `last-db` = N — and the queued
            `flush!` then arrives with N against N, where the guard HITS.

            C1: the witness must NOT short-circuit that. After the interleaved
            read `last-db` holds N while `-moved-from` still holds P, so the
            `identical?` proof term fails and the `=` walk is performed — and
            returns the hit. This is the structural reason the optimisation
            cannot fire twice running on one movement."
    (rf/with-frame :rf/default
      (let [runs (atom 0)]
        (rf/reg-event :seed (fn [_ _] {:db {:n 42 :other :a}}))
        (rf/reg-event :bump (fn [{:keys [db]} _] {:db (assoc db :n 43)}))
        (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
        (rf/dispatch-sync [:seed])
        (let [r     (rf/subscribe [:n])
              proj  (frame/app-db-container :rf/default)
              reads (atom 0)]
          (is (= 42 @r))
          (is (= 1 @runs) "first deref runs the body")
          (add-watch proj ::deref-between
                     (fn [_ _ _ _] (swap! reads inc) @r))
          (let [flush-skips (try
                              (skips-for :n #(rf/dispatch-sync [:bump]))
                              (finally (remove-watch proj ::deref-between)))]
            (is (= 1 @reads)
                "the interposition genuinely fired — without this the two
                 assertions below would be about an ordinary flush")
            (is (= 2 @runs)
                "the interleaved deref ran the body ONCE, against the moved db")
            (is (pos? flush-skips)
                "and the queued `flush!` then found its input `=` to what that
                 deref had already left in `last-db`: the guard HIT on the
                 FLUSH path, for a `:db` sub. The premise rf2-gncxk was filed
                 on says this cannot happen."))
          (is (= 43 @r) "the sub projects the moved value")
          (is (= 2 @runs)
              "and the flush-path hit was load-bearing: ONE body run for one
               movement, not two (Spec 009 §`:rf.sub/skip`)"))))))

(deftest db-sub-guard-hits-on-the-flush-path-on-a-return-to-equal
  (testing "INTERLEAVING (b), RETURN-TO-EQUAL. Two writes inside one drain take
            app-db A -> B -> A' with A' `=` A but a FRESH object. The sub's
            `mark-dirty!` dedups, so its flush arrives with A' against
            `last-db` = A: the source moved twice by `rf=`, `=` says equal, and
            the guard HITS.

            C1: W1 is what keeps the witness honest here. The second write's
            `mark-dirty!` on the projection RETRACTS the witness — the
            container's live value may now run ahead of its last completed
            movement — so `-moved-from` answers `no-witness`, the proof term
            fails, and the wrapper performs the walk instead of trusting a
            stale departure value. Delete W1's `vreset!` and this test is what
            goes red."
    (rf/with-frame :rf/default
      (let [runs (atom 0)]
        (rf/reg-event :seed (fn [_ _] {:db {:n 42 :other :a}}))
        (rf/reg-event :bump (fn [{:keys [db]} _] {:db (assoc db :n 43)}))
        (rf/reg-sub :n (fn [db _] (swap! runs inc) (:n db)))
        (rf/dispatch-sync [:seed])
        (let [r       (rf/subscribe [:n])
              proj    (frame/app-db-container :rf/default)
              returns (atom 0)]
          (is (= 42 @r))
          (is (= 1 @runs) "first deref runs the body")
          ;; The SECOND write, from inside the projection's fan-out: the move
          ;; to B is established (witness armed, sub marked dirty, flush
          ;; queued) and app-db is returned to a fresh A' before the drain
          ;; reaches that thunk. `replace-app-db!` is `commit-frame-
          ;; transition!` — the same write boundary the router commit uses —
          ;; and its re-entrant `with-epoch` cannot drain (the outer
          ;; `drain-scheduler!` holds `flushing?`), so the two writes coalesce
          ;; into one flush exactly as the design pass described.
          (add-watch proj ::return-to-equal
                     (fn [_ _ _ _]
                       (when (zero? @returns)
                         (swap! returns inc)
                         (frame/replace-app-db! :rf/default {:n 42 :other :a}))))
          (let [flush-skips (try
                              (skips-for :n #(rf/dispatch-sync [:bump]))
                              (finally (remove-watch proj ::return-to-equal)))]
            (is (= 1 @returns) "the interposed second write genuinely fired")
            (is (pos? flush-skips)
                "the sub's `flush!` found A' `=` to the A still sitting in
                 `last-db`: the guard HIT on the FLUSH path, for a `:db` sub,
                 on an input that moved twice by `rf=`")
            (is (= 1 @runs)
                "and the hit is load-bearing: the body did NOT re-run anywhere
                 across A -> B -> A'"))
          (is (= 42 @r) "the sub still projects the returned-to value")
          (is (= 1 @runs)))))))
