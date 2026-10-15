(ns re-frame.bench.fresco.read-profile-baseline-cljs-test
  "The instrument waits past the runtime reap horizon before taking and comparing residue baselines."
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
  "One entry is armed before the settle timer and one after it."
  [::b1 ::b2])

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

;; 1 — the mechanism, stated positively

(deftest one-bare-macrotask-lands-in-front-of-the-reap-horizon
  (async done
    (seeded!)
    (testing "why the settle point is not free: one `rf.bench.fresco.lane/settle!` after the render
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

;; 2 — and the gate itself holds across the `commit` arm

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
                         "the commit half acquired its cells and gave
                          every one of them back, and the entry cache is
                          where the baseline left it")
                     (rf.bench.fresco.arm1.runtime/reset-runtime!)
                     (done))))))))
