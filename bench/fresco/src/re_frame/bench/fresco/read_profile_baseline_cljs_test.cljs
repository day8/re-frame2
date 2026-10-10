(ns re-frame.bench.fresco.read-profile-baseline-cljs-test
  "PHASE B'S RESIDUE BASELINE IS THE STATE THE RUNTIME SETTLES TO —
  pinned.

  `read_profile_app`'s phase B gates every sample on residue EQUALITY
  against a baseline read once, at setup. The gate is right and the
  equality is right: these are counts of live references and a tolerance
  on them would only make room for the fault. What matters is WHERE
  the baseline is read.

  Phase B's setup harvests one unclaimed read-set entry per commit frame
  — minted by a render, `refs` still zero, reaper armed. `arm1/runtime`
  arms that reaper at [[rf.bench.fresco.arm1.runtime/quiesced!]]'s horizon, 4 ms rather
  than 0, so an entry survives long enough for `hydrateRoot`'s
  passive subscribe to claim it. A baseline read one bare macrotask later
  would therefore count every one of those entries, and by the first
  sampled arm they are gone: six against five, and no phase-B number at
  all.

  ## Why this file

  A gate that never fires looks exactly like a gate that passes, so a
  move of the reap horizon could land under a faithful instrument without
  anything going red.

  The rows below drive `read-profile-app/residue-settle!` itself rather
  than the runtime primitive underneath it, and that is deliberate: a
  witness that called [[rf.bench.fresco.arm1.runtime/quiesced!]] directly would stay green however
  the instrument settled, which is precisely the vacuum this file exists
  to fill. Put the instrument on a bare macrotask and both rows go
  red; move the horizon and they stay green, because the settle
  point is derived from the runtime's own number instead of copying it.

  ## Shape

  Four frames — see [[commit-frames]] for why four is right here and why
  it is NOT phase B's count — one body run each through the same
  [[rf.bench.fresco.arm1.runtime/render-body]] door phase B's setup uses, and the same
  [[rf.bench.fresco.arm1.runtime/commit-boundary!]] seam its `commit` arm rides. Nothing here is
  timed and no number is published — the claim is about reachability,
  not cost.

  Row 2 races a bare macrotask against the reapers, and on Node's real
  clock that race is decided by wall-clock time and by the order of
  Node's per-duration timer lists, not by the horizon. So it runs on a
  clock that stands still: it records the timers its renders and settles
  arm instead of arming them, and fires them by delay, ties in arm order.
  A horizon of 0 turns it red and 32 leaves it green."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.dogfood :as rf.bench.fresco.front.dogfood]
            [re-frame.bench.fresco.lane :as rf.bench.fresco.lane]
            [re-frame.bench.fresco.read-profile-app :as rf.bench.fresco.read-profile-app]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; The map shape, because a reap horizon is not observable inside
     ;; one synchronous test body — every row here is `async`.
     :async?  true
     :init-fn (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(def ^:private commit-frames
  "Phase B's identically-seeded commit frames, in miniature — FOUR here
  against the instrument's 32.

  Four is right for THIS file and the shortfall costs it nothing: the
  claim below is that an unclaimed entry is still reachable at the
  baseline, which is a property of one entry's own reap horizon. It does
  not sharpen with more of them."
  [::b1 ::b2 ::b3 ::b4])

(defn- seeded! []
  (rf.bench.fresco.arm1.runtime/reset-runtime!)
  (doseq [f commit-frames] (rf.bench.fresco.front.dogfood/make-frame! f 3))
  nil)

(defn- render-one!
  "One body run through the runtime's own door, and the read-set entry
  that run minted read back off [[rf.bench.fresco.arm1.runtime/last-reads]]. The entry comes back
  UNCLAIMED — `refs` zero, reaper armed **from this instant** — which is
  the state the real setup leaves behind and the state the baseline is
  taken in."
  [f]
  (rf.bench.fresco.arm1.runtime/render-body f
                  (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))
                           (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/remaining]))])
                  {})
  (rf.bench.fresco.arm1.runtime/last-reads))

(defn- harvest!
  "Phase B's setup: [[render-one!]] per frame."
  []
  (mapv render-one! commit-frames))

(defn- commit-arm!
  "Phase B's `commit` arm and its teardown: the shipping commit half
  through the seam React occupies, for every harvested entry, released
  again outside any window."
  [entries]
  (let [stops (mapv (fn [e] (rf.bench.fresco.arm1.runtime/commit-boundary! e (fn [] nil))) entries)]
    (doseq [stop stops] (stop))
    nil))

(defn- armed-by
  "Calls `f` with `setTimeout` recording each timer instead of arming it,
  and answers `[result timers]`: every timer `f` armed, as
  `[delay-ms callback]` in arm order."
  [f]
  (let [real    (.-setTimeout js/globalThis)
        !timers (volatile! [])]
    (set! (.-setTimeout js/globalThis)
          (fn [callback ms] (vswap! !timers conj [(or ms 0) callback]) nil))
    (try
      [(f) @!timers]
      (finally (set! (.-setTimeout js/globalThis) real)))))

(defn- fire-on-a-still-clock!
  "Fires `timers` as a clock that does not move while they are armed
  would: by delay, ties in arm order (`sort-by` is stable). Each fires in
  its own promise job, so the `.then` a timer resolves runs before the
  next timer fires."
  [timers]
  (reduce (fn [p [_ callback]] (.then p (fn [_] (callback) nil)))
          (js/Promise.resolve nil)
          (sort-by first timers)))

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
             return to, and the six-against-five the gate throws on"
      (let [!baseline (volatile! nil)]
        (-> (rf.bench.fresco.read-profile-app/residue-settle!)
            (.then (fn [_] (vreset! !baseline (rf.bench.fresco.arm1.runtime/residue)) (rf.bench.fresco.arm1.runtime/quiesced!)))
            (.then (fn [_]
                     (is (= @!baseline (rf.bench.fresco.arm1.runtime/residue))
                         "the baseline is a fixed point of the runtime's own
                          settling, so every later sample can reach it")
                     (is (zero? (:entries @!baseline))
                         "and it is the post-reap state: four unclaimed
                          entries, none of them cached by the time the
                          baseline is taken")
                     (rf.bench.fresco.arm1.runtime/reset-runtime!)
                     (done))))))))

;; ===========================================================================
;; 2 — the mechanism, stated positively
;; ===========================================================================

(deftest one-bare-macrotask-lands-in-front-of-the-reap-horizon
  (async done
    (seeded!)
    (testing "why row 1 is not free: one `rf.bench.fresco.lane/settle!` after the render
             every unclaimed entry is STILL cached — that survival is the
             hydration margin the 4 ms horizon buys, and it is what makes a
             residue reading taken there disagree with one taken after the
             runtime has quiesced"
      (let [[[settled quiesced] timers]
            (armed-by (fn []
                        ;; After the first mint, so at a horizon of 0 that
                        ;; entry's reaper ties the settle and fires first.
                        (render-one! (first commit-frames))
                        (let [settled (rf.bench.fresco.lane/settle!)]
                          (run! render-one! (rest commit-frames))
                          [settled (rf.bench.fresco.read-profile-app/residue-settle!)])))]
        (.then settled
               (fn [_]
                 (is (= (count commit-frames) (:entries (rf.bench.fresco.arm1.runtime/residue)))
                     "a bare macrotask is inside the horizon: every
                      harvested entry is still in the cache")))
        (.then quiesced
               (fn [_]
                 (is (zero? (:entries (rf.bench.fresco.arm1.runtime/residue)))
                     "past it they are gone — two readings, two answers,
                      and only the second is a baseline")
                 (rf.bench.fresco.arm1.runtime/reset-runtime!)
                 (done)))
        (fire-on-a-still-clock! timers)))))

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
               reading can fall inside the horizon at all — `rf.bench.fresco.arm1.runtime/quiesced!`
               behind the instrument's own settle is the shortest honest
               stand-in for that, and it is what makes this row decide the
               gate rather than race it"
        (-> (rf.bench.fresco.read-profile-app/residue-settle!)
            (.then (fn [_]
                     (vreset! !baseline (rf.bench.fresco.arm1.runtime/residue))
                     (commit-arm! entries)
                     (rf.bench.fresco.read-profile-app/residue-settle!)))
            (.then (fn [_] (rf.bench.fresco.arm1.runtime/quiesced!)))
            (.then (fn [_]
                     (is (= @!baseline (rf.bench.fresco.arm1.runtime/residue))
                         "the commit half acquired 4 x 2 cells and gave
                          every one of them back, and the entry cache is
                          where the baseline left it")
                     (rf.bench.fresco.arm1.runtime/reset-runtime!)
                     (done))))))))
