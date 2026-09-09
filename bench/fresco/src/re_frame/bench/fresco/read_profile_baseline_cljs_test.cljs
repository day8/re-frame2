(ns re-frame.bench.hicasso.read-profile-baseline-cljs-test
  "PHASE B'S RESIDUE BASELINE IS THE STATE THE RUNTIME SETTLES TO —
  pinned (rf2-981nt).

  `read_profile_app`'s phase B gates every sample on residue EQUALITY
  against a baseline read once, at setup. The gate is right and the
  equality is right: these are counts of live references and a tolerance
  on them would only make room for the fault. What was wrong was WHERE
  the baseline was read.

  Phase B's setup harvests one unclaimed read-set entry per commit frame
  — minted by a render, `refs` still zero, reaper armed. `arm1/runtime`
  arms that reaper at [[rf.bench.hicasso.arm1.runtime/quiesced!]]'s horizon, which rf2-2rtt6.84 moved
  from 0 ms to 4 ms so an entry survives long enough for `hydrateRoot`'s
  passive subscribe to claim it. A baseline read one bare macrotask later
  therefore counts every one of those entries, and by the first sampled
  arm they are gone. Six, then five, byte-identical on both attempts of
  the granted quiet-box run, and no phase-B number at all.

  ## Why this file, and why it would have caught the move

  Both published phase-B runs predate the horizon change, so the studio
  page records a residue gate that had never once fired. A gate that
  never fires looks exactly like a gate that passes — which is the whole
  reason the 0 -> 4 move could land under a faithful instrument without
  anything going red.

  The rows below drive `read-profile-app/residue-settle!` itself rather
  than the runtime primitive underneath it, and that is deliberate: a
  witness that called [[rf.bench.hicasso.arm1.runtime/quiesced!]] directly would stay green however
  the instrument settled, which is precisely the vacuum this file exists
  to fill. Put the instrument back on a bare macrotask and both rows go
  red; move the horizon again and they stay green, because the settle
  point is derived from the runtime's own number instead of copying it.

  ## Shape

  Four frames — see [[commit-frames]] for why four is right here and why
  it is NOT phase B's count — one body run each through the same
  [[rf.bench.hicasso.arm1.runtime/render-body]] door phase B's setup uses, and the same
  [[rf.bench.hicasso.arm1.runtime/commit-boundary!]] seam its `commit` arm rides. Nothing here is
  timed and no number is published — the claim is about reachability,
  not cost.

  Which is exactly why row 2 arms its macrotask at the FIRST mint rather
  than after the harvest: a reap horizon is a duration from one entry's
  own minting, so a settle armed at the end of the setup is racing the
  setup's wall-clock and nothing else. Timing the harvest is not this
  file's business, and this is how it declines to."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.front.dogfood :as rf.bench.hicasso.front.dogfood]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.read-profile-app :as rf.bench.hicasso.read-profile-app]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; The map shape, because a reap horizon is not observable inside
     ;; one synchronous test body — every row here is `async`.
     :async?  true
     :init-fn (fn [] (rf.bench.hicasso.arm1.runtime/reset-runtime!))}))

(def ^:private commit-frames
  "Phase B's identically-seeded commit frames, in miniature — FOUR here
  against the instrument's 32 (rf2-3l6hf raised that count, and this
  docstring went on attributing four to phase B itself).

  Four is right for THIS file and the shortfall costs it nothing: the
  claim below is that an unclaimed entry is still reachable at the
  baseline, which is a property of one entry's own reap horizon. It does
  not sharpen with more of them."
  [::b1 ::b2 ::b3 ::b4])

(defn- seeded! []
  (rf.bench.hicasso.arm1.runtime/reset-runtime!)
  (doseq [f commit-frames] (rf.bench.hicasso.front.dogfood/make-frame! f 3))
  nil)

(defn- render-one!
  "One body run through the runtime's own door, and the read-set entry
  that run minted read back off [[rf.bench.hicasso.arm1.runtime/last-reads]]. The entry comes back
  UNCLAIMED — `refs` zero, reaper armed **from this instant** — which is
  the state the real setup leaves behind and the state the baseline is
  taken in."
  [f]
  (rf.bench.hicasso.arm1.runtime/render-body f
                  (fn [_] [:li (str (rf.bench.hicasso.arm1.runtime/sub [:dogfood/todo 0]))
                           (str (rf.bench.hicasso.arm1.runtime/sub [:dogfood/remaining]))])
                  {})
  (rf.bench.hicasso.arm1.runtime/last-reads))

(defn- harvest!
  "Phase B's setup: [[render-one!]] per frame."
  []
  (mapv render-one! commit-frames))

(defn- commit-arm!
  "Phase B's `commit` arm and its teardown: the shipping commit half
  through the seam React occupies, for every harvested entry, released
  again outside any window."
  [entries]
  (let [stops (mapv (fn [e] (rf.bench.hicasso.arm1.runtime/commit-boundary! e (fn [] nil))) entries)]
    (doseq [stop stops] (stop))
    nil))

;; ===========================================================================
;; 1 — the baseline is the state the runtime settles to
;; ===========================================================================

(deftest the-phase-b-baseline-is-the-state-the-runtime-settles-to
  (async done
    (seeded!)
    (harvest!)
    (testing "the reading `residue-settle!` puts the baseline behind is the
             reading the runtime settles to on its own. Read it a bare
             macrotask earlier and it counts four cached entries the
             reapers are about to drop — a baseline the run can never
             return to, and the six-against-five the gate threw on"
      (let [!baseline (volatile! nil)]
        (-> (rf.bench.hicasso.read-profile-app/residue-settle!)
            (.then (fn [_] (vreset! !baseline (rf.bench.hicasso.arm1.runtime/residue)) (rf.bench.hicasso.arm1.runtime/quiesced!)))
            (.then (fn [_]
                     (is (= @!baseline (rf.bench.hicasso.arm1.runtime/residue))
                         "the baseline is a fixed point of the runtime's own
                          settling, so every later sample can reach it")
                     (is (zero? (:entries @!baseline))
                         "and it is the post-reap state: four unclaimed
                          entries, none of them cached by the time the
                          baseline is taken")
                     (rf.bench.hicasso.arm1.runtime/reset-runtime!)
                     (done))))))))

;; ===========================================================================
;; 2 — the mechanism, stated positively
;; ===========================================================================

(deftest one-bare-macrotask-lands-in-front-of-the-reap-horizon
  (async done
    (seeded!)
    ;; ARMED AT THE FIRST MINT, with the other three renders behind it in
    ;; the same tick. Every reaper's horizon starts when ITS OWN entry is
    ;; minted, so a macrotask armed after the whole harvest is not
    ;; measured against the horizon at all — it is measured against what
    ;; the harvest left of it. Node drains one duration's timer list per
    ;; pass, so the row turns on a single comparison: the settle's expiry
    ;; against the FIRST reaper's, which is `settle-armed - first-mint`
    ;; against 3 ms. Armed at the end, that interval is three whole
    ;; renders — ~1.3 ms on a quiet box, past 3 ms on a loaded CI runner
    ;; inside the consolidated node-test bundle, where this read 2 of 4
    ;; on one and 1 of 4 on another. That is the SETUP'S COST arriving as
    ;; a residue reading. Armed here it is the tail of one render, and
    ;; 4 ms goes back to being React's commit margin rather than a budget
    ;; for a test's own setup.
    (render-one! (first commit-frames))
    (let [settled (rf.bench.hicasso.lane/settle!)]
      (run! render-one! (rest commit-frames))
      (testing "why row 1 is not free: one `rf.bench.hicasso.lane/settle!` after the render
               every unclaimed entry is STILL cached — that survival is the
               hydration margin rf2-2rtt6.84 bought, and it is what makes a
               residue reading taken there disagree with one taken after the
               runtime has quiesced"
        (-> settled
            (.then (fn [_]
                     (is (= (count commit-frames) (:entries (rf.bench.hicasso.arm1.runtime/residue)))
                         "a bare macrotask is inside the horizon: every
                          harvested entry is still in the cache")
                     (rf.bench.hicasso.read-profile-app/residue-settle!)))
            (.then (fn [_]
                     (is (zero? (:entries (rf.bench.hicasso.arm1.runtime/residue)))
                         "past it they are gone — two readings, two answers,
                          and only the second is a baseline")
                     (rf.bench.hicasso.arm1.runtime/reset-runtime!)
                     (done))))))))

;; ===========================================================================
;; 3 — and the gate itself holds across the `commit` arm
;; ===========================================================================

(deftest the-residue-gate-holds-across-the-commit-arm
  (async done
    (seeded!)
    (let [entries   (harvest!)
          !baseline (volatile! nil)]
      (testing "the assertion `rounds-async!` runs between samples, end to
               end: baseline, one `commit` arm with its teardown, and the
               reading a row's worth of time later. The real run takes
               hundreds of samples over eight arms, so only its very first
               reading can fall inside the horizon at all — `rf.bench.hicasso.arm1.runtime/quiesced!`
               behind the instrument's own settle is the shortest honest
               stand-in for that, and it is what makes this row decide the
               gate rather than race it"
        (-> (rf.bench.hicasso.read-profile-app/residue-settle!)
            (.then (fn [_]
                     (vreset! !baseline (rf.bench.hicasso.arm1.runtime/residue))
                     (commit-arm! entries)
                     (rf.bench.hicasso.read-profile-app/residue-settle!)))
            (.then (fn [_] (rf.bench.hicasso.arm1.runtime/quiesced!)))
            (.then (fn [_]
                     (is (= @!baseline (rf.bench.hicasso.arm1.runtime/residue))
                         "the commit half acquired 4 x 2 cells and gave
                          every one of them back, and the entry cache is
                          where the baseline left it")
                     (rf.bench.hicasso.arm1.runtime/reset-runtime!)
                     (done))))))))
