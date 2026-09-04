(ns re-frame.bench.hicasso.arm1.hydrate-cljs-test
  "THE HYDRATION DOOR'S HEADLESS HALF (rf2-2rtt6.84).

  Three of the door's four moving parts are answerable without React, a
  root or a DOM, and they are answered here so the browser file is left
  with only the claims a browser can make:

  1. **The entry reap horizon is past a bare `setTimeout 0`** — the
     property `hydrateRoot`'s passive subscribe needs, expressed as the
     race it has to win rather than as the integer it is implemented
     with.
  2. **The adoption window opens, closes, and is closed by a runtime
     reset** — so a fixture that throws mid-hydration cannot leave the
     page permanently adopting.
  3. **`body-runs` counts real body runs**, is monotone, and survives
     `reset-runtime!` — HD-028's rider stated where it can be stated
     exactly.

  ## Why row 1 is not a contract test in disguise

  `arm1.runtime/entry-reap-horizon-ms` is a MARGIN and its own docstring
  says no caller may rely on it. What is asserted below is not \"4 ms\":
  it is that an entry a render minted is **still in the cache one bare
  macrotask later**, which is the defect a `setTimeout 0` horizon had and
  the only thing about the horizon that is a design property rather than
  a measurement. Setting the horizon back to 0 turns this row red;
  raising it to 32 does not."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.front.dogfood :as rf.bench.hicasso.front.dogfood]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; The horizon rows are `async` — a reap horizon is not observable
     ;; inside one synchronous test body.
     :async?  true
     :init-fn (fn [] (rf.bench.hicasso.arm1.runtime/reset-runtime!) (rf.bench.hicasso.arm1.runtime/reset-body-runs!))}))

(def ^:private frame-id ::arm1-hydrate)

(defn- seeded! []
  (rf.bench.hicasso.arm1.runtime/reset-runtime!)
  (rf.bench.hicasso.arm1.runtime/reset-body-runs!)
  (rf.bench.hicasso.front.dogfood/make-frame! frame-id 3)
  frame-id)

(defn- one-body-run!
  "One boundary body through the shell's own fence, minus React."
  []
  (rf.bench.hicasso.arm1.runtime/render-body frame-id (fn [_] [:li (str (rf.bench.hicasso.arm1.runtime/sub [:dogfood/todo 0]))]) {})
  (rf.bench.hicasso.arm1.runtime/last-reads))

;; ---------------------------------------------------------------------------
;; 1 — the reap horizon is past a bare `setTimeout 0`
;; ---------------------------------------------------------------------------

(deftest an-unclaimed-entry-survives-a-bare-macrotask-and-not-the-horizon
  (async done
    (seeded!)
    (testing "**The race the hydration door has to win** (rf2-2rtt6.84 (2)).
             An entry is minted in the RENDER and claimed in the COMMIT,
             and `hydrateRoot` puts a scheduler turn between the two. A
             reaper armed at `setTimeout 0` inside the render therefore
             evicts the entry before React ever calls its `subscribe` —
             the boundary ends up subscribed to a detached entry, and its
             next render misses the cache, mints a second one, and hands
             `useSyncExternalStore` a different `subscribe` to tear down
             and rebuild. Same class as rf2-2rtt6.71 in the spine.

             The horizon is asserted as the RACE, not as its integer: a
             timer armed AFTER the reaper's, for zero, must still find the
             entry cached"
      (let [entry (one-body-run!)]
        (is (some? entry) "the render minted an entry")
        (is (zero? (.-refs entry)) "unclaimed — no commit has run")
        (is (= 1 (:entries (rf.bench.hicasso.arm1.runtime/stats))) "and it is in the cache")
        (js/setTimeout
          (fn []
            (is (= 1 (:entries (rf.bench.hicasso.arm1.runtime/stats)))
                "one bare macrotask later it is STILL cached — a commit
                 arriving here would find the entry its render minted")
            (js/setTimeout
              (fn []
                (is (zero? (:entries (rf.bench.hicasso.arm1.runtime/stats)))
                    "and the horizon is bounded, not disabled: an entry
                     nothing claimed is still evicted")
                (done))
              8))
          0)))))

(deftest a-claimed-entry-is-never-reaped-at-any-horizon
  (async done
    (seeded!)
    (testing "the horizon is a cache-eviction schedule and nothing else —
             an entry a commit claimed is held by its `refs`, so no delay
             can drop it and correctness never depended on the race"
      (let [entry (one-body-run!)
            stop  (rf.bench.hicasso.arm1.runtime/commit-boundary! entry (fn [] nil))]
        (is (= 1 (.-refs entry)))
        (js/setTimeout
          (fn []
            (is (= 1 (:entries (rf.bench.hicasso.arm1.runtime/stats))) "claimed, so past the horizon it stands")
            (stop)
            (js/setTimeout
              (fn []
                (is (zero? (:entries (rf.bench.hicasso.arm1.runtime/stats)))
                    "and released, it is evicted on the ordinary edge")
                (done))
              8))
          8)))))

;; ---------------------------------------------------------------------------
;; 2 — the adoption window
;; ---------------------------------------------------------------------------

(deftest the-adoption-window-is-shut-by-default-and-shut-again-by-a-reset
  (seeded!)
  (testing "**Adoption is a window, not a mode** (rf2-2rtt6.84 (3)). It is
           false for every ordinary mount — which is what keeps the
           charter's one-mode law intact — and `reset-runtime!` shuts it,
           so a fixture that throws between `hydrateRoot` and the closer's
           effect cannot leave the page adopting for every row after it"
    (is (false? (rf.bench.hicasso.arm1.runtime/adopting?)) "shut by default")
    (rf.bench.hicasso.arm1.runtime/open-adoption-window!)
    (is (true? (rf.bench.hicasso.arm1.runtime/adopting?)) "opened by the door")
    (rf.bench.hicasso.arm1.runtime/close-adoption-window!)
    (is (false? (rf.bench.hicasso.arm1.runtime/adopting?)) "shut by the closer")
    (rf.bench.hicasso.arm1.runtime/open-adoption-window!)
    (rf.bench.hicasso.arm1.runtime/reset-runtime!)
    (is (false? (rf.bench.hicasso.arm1.runtime/adopting?)) "and shut by a runtime reset, whatever threw")))

;; ---------------------------------------------------------------------------
;; 3 — the body-run counter (HD-028's rider)
;; ---------------------------------------------------------------------------

(deftest body-runs-counts-bodies-that-ran-and-is-not-cleared-by-a-reset
  (seeded!)
  (testing "**The instrument the X-witnesses read** (rf2-2rtt6.84 (6)).
           Always on, so the `:advanced` / `goog.DEBUG false` builds this
           lane actually drives can see it; bumped inside `run-once`, so
           what it counts is a body that RAN rather than a render React
           was asked for — which is the whole of HD-028's rider, because a
           `React.memo` bail-out has to read as an increment that did not
           happen"
    (is (zero? (rf.bench.hicasso.arm1.runtime/body-runs)) "the fixture zeroed it")
    (one-body-run!)
    (is (= 1 (rf.bench.hicasso.arm1.runtime/body-runs)))
    (one-body-run!)
    (one-body-run!)
    (is (= 3 (rf.bench.hicasso.arm1.runtime/body-runs)) "one per body, counted")
    (rf.bench.hicasso.arm1.runtime/reset-runtime!)
    (is (= 3 (rf.bench.hicasso.arm1.runtime/body-runs))
        "and a teardown does NOT zero it — an instrument a teardown door
         resets is one a reading taken on the wrong side of the reset can
         pass with")
    (rf.bench.hicasso.arm1.runtime/reset-body-runs!)
    (is (zero? (rf.bench.hicasso.arm1.runtime/body-runs)) "only the explicit door zeroes it")))

(deftest a-fenced-re-run-is-two-body-runs-because-two-bodies-ran
  (seeded!)
  (testing "the generation fence re-runs a body that straddled a commit,
           and the counter says TWO — which is the reason it is bumped in
           `run-once` rather than once per `render-body`. A count of
           renders React asked for would say one here, and one is not what
           happened"
    ;; A mid-body write is only observable when the key is already held —
    ;; a key nothing holds has no watch to fire (`runtime_cljs_test`'s
    ;; own fence row states it).
    (rf.bench.hicasso.arm1.runtime/render-body frame-id (fn [_] [:li (str (rf.bench.hicasso.arm1.runtime/sub [:dogfood/done? 0]))]) {})
    (let [stop (rf.bench.hicasso.arm1.runtime/commit-boundary! (rf.bench.hicasso.arm1.runtime/last-reads) (fn [] nil))
          runs (volatile! 0)]
      (rf.bench.hicasso.arm1.runtime/reset-body-runs!)
      (rf.bench.hicasso.arm1.runtime/render-body frame-id
                      (fn [_]
                        (vswap! runs inc)
                        (rf.bench.hicasso.arm1.runtime/sub [:dogfood/done? 0])
                        (when (= 1 @runs)
                          (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0]))
                        [:li (str (rf.bench.hicasso.arm1.runtime/sub [:dogfood/done? 0]))])
                      {})
      (is (= 2 @runs) "the fence re-ran the body")
      (is (= 2 (rf.bench.hicasso.arm1.runtime/body-runs))
          "and the counter reads two, because two bodies ran")
      (stop))))
