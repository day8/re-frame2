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
  arms that reaper at [[rt/quiesced!]]'s horizon, which rf2-2rtt6.84 moved
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
  witness that called [[rt/quiesced!]] directly would stay green however
  the instrument settled, which is precisely the vacuum this file exists
  to fill. Put the instrument back on a bare macrotask and both rows go
  red; move the horizon again and they stay green, because the settle
  point is derived from the runtime's own number instead of copying it.

  ## Shape

  Four frames, one body run each through the same [[rt/render-body]] door
  phase B's setup uses, and the same [[rt/commit-boundary!]] seam its
  `commit` arm rides. Nothing here is timed and no number is published —
  the claim is about reachability, not cost."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.read-profile-app :as app]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; The map shape, because a reap horizon is not observable inside
     ;; one synchronous test body — every row here is `async`.
     :async?  true
     :init-fn (fn [] (rt/reset-runtime!))}))

(def ^:private commit-frames
  "Phase B's four identically-seeded frames, in miniature."
  [::b1 ::b2 ::b3 ::b4])

(defn- seeded! []
  (rt/reset-runtime!)
  (doseq [f commit-frames] (dogfood/make-frame! f 3))
  nil)

(defn- harvest!
  "Phase B's setup: one body run per frame through the runtime's own
  door, and the read-set entry that run minted read back off
  [[rt/last-reads]]. Every entry comes back UNCLAIMED — `refs` zero,
  reaper armed — which is the state the real setup leaves behind and the
  state the baseline is taken in."
  []
  (mapv (fn [f]
          (rt/render-body f
                          (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))
                                   (str (rt/sub [:dogfood/remaining]))])
                          {})
          (rt/last-reads))
        commit-frames))

(defn- commit-arm!
  "Phase B's `commit` arm and its teardown: the shipping commit half
  through the seam React occupies, for every harvested entry, released
  again outside any window."
  [entries]
  (let [stops (mapv (fn [e] (rt/commit-boundary! e (fn [] nil))) entries)]
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
        (-> (app/residue-settle!)
            (.then (fn [_] (vreset! !baseline (rt/residue)) (rt/quiesced!)))
            (.then (fn [_]
                     (is (= @!baseline (rt/residue))
                         "the baseline is a fixed point of the runtime's own
                          settling, so every later sample can reach it")
                     (is (zero? (:entries @!baseline))
                         "and it is the post-reap state: four unclaimed
                          entries, none of them cached by the time the
                          baseline is taken")
                     (rt/reset-runtime!)
                     (done))))))))

;; ===========================================================================
;; 2 — the mechanism, stated positively
;; ===========================================================================

(deftest one-bare-macrotask-lands-in-front-of-the-reap-horizon
  (async done
    (seeded!)
    (harvest!)
    (testing "why row 1 is not free: one `lane/settle!` after the render
             every unclaimed entry is STILL cached — that survival is the
             hydration margin rf2-2rtt6.84 bought, and it is what makes a
             residue reading taken there disagree with one taken after the
             runtime has quiesced"
      (-> (lane/settle!)
          (.then (fn [_]
                   (is (= (count commit-frames) (:entries (rt/residue)))
                       "a bare macrotask is inside the horizon: every
                        harvested entry is still in the cache")
                   (app/residue-settle!)))
          (.then (fn [_]
                   (is (zero? (:entries (rt/residue)))
                       "past it they are gone — two readings, two answers,
                        and only the second is a baseline")
                   (rt/reset-runtime!)
                   (done)))))))

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
               reading can fall inside the horizon at all — `rt/quiesced!`
               behind the instrument's own settle is the shortest honest
               stand-in for that, and it is what makes this row decide the
               gate rather than race it"
        (-> (app/residue-settle!)
            (.then (fn [_]
                     (vreset! !baseline (rt/residue))
                     (commit-arm! entries)
                     (app/residue-settle!)))
            (.then (fn [_] (rt/quiesced!)))
            (.then (fn [_]
                     (is (= @!baseline (rt/residue))
                         "the commit half acquired 4 x 2 cells and gave
                          every one of them back, and the entry cache is
                          where the baseline left it")
                     (rt/reset-runtime!)
                     (done))))))))
