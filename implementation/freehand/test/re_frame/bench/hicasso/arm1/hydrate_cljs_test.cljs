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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     ;; The horizon rows are `async` — a reap horizon is not observable
     ;; inside one synchronous test body.
     :async?  true
     :init-fn (fn [] (rt/reset-runtime!) (rt/reset-body-runs!))}))

(def ^:private frame-id ::arm1-hydrate)

(defn- seeded! []
  (rt/reset-runtime!)
  (rt/reset-body-runs!)
  (dogfood/make-frame! frame-id 3)
  frame-id)

(defn- one-body-run!
  "One boundary body through the shell's own fence, minus React."
  []
  (rt/render-body frame-id (fn [_] [:li (str (rt/sub [:dogfood/todo 0]))]) {})
  (rt/last-reads))

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
        (is (= 1 (:entries (rt/stats))) "and it is in the cache")
        (js/setTimeout
          (fn []
            (is (= 1 (:entries (rt/stats)))
                "one bare macrotask later it is STILL cached — a commit
                 arriving here would find the entry its render minted")
            (js/setTimeout
              (fn []
                (is (zero? (:entries (rt/stats)))
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
            stop  (rt/commit-boundary! entry (fn [] nil))]
        (is (= 1 (.-refs entry)))
        (js/setTimeout
          (fn []
            (is (= 1 (:entries (rt/stats))) "claimed, so past the horizon it stands")
            (stop)
            (js/setTimeout
              (fn []
                (is (zero? (:entries (rt/stats)))
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
    (is (false? (rt/adopting?)) "shut by default")
    (rt/open-adoption-window!)
    (is (true? (rt/adopting?)) "opened by the door")
    (rt/close-adoption-window!)
    (is (false? (rt/adopting?)) "shut by the closer")
    (rt/open-adoption-window!)
    (rt/reset-runtime!)
    (is (false? (rt/adopting?)) "and shut by a runtime reset, whatever threw")))

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
    (is (zero? (rt/body-runs)) "the fixture zeroed it")
    (one-body-run!)
    (is (= 1 (rt/body-runs)))
    (one-body-run!)
    (one-body-run!)
    (is (= 3 (rt/body-runs)) "one per body, counted")
    (rt/reset-runtime!)
    (is (= 3 (rt/body-runs))
        "and a teardown does NOT zero it — an instrument a teardown door
         resets is one a reading taken on the wrong side of the reset can
         pass with")
    (rt/reset-body-runs!)
    (is (zero? (rt/body-runs)) "only the explicit door zeroes it")))

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
    (rt/render-body frame-id (fn [_] [:li (str (rt/sub [:dogfood/done? 0]))]) {})
    (let [stop (rt/commit-boundary! (rt/last-reads) (fn [] nil))
          runs (volatile! 0)]
      (rt/reset-body-runs!)
      (rt/render-body frame-id
                      (fn [_]
                        (vswap! runs inc)
                        (rt/sub [:dogfood/done? 0])
                        (when (= 1 @runs)
                          (rt/dispatch! frame-id [:dogfood/toggle 0]))
                        [:li (str (rt/sub [:dogfood/done? 0]))])
                      {})
      (is (= 2 @runs) "the fence re-ran the body")
      (is (= 2 (rt/body-runs))
          "and the counter reads two, because two bodies ran")
      (stop))))
