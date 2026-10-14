(ns re-frame.bench.fresco.arm1.hydrate-cljs-test
  "THE HYDRATION DOOR'S HEADLESS HALF.

  Three of the door's four moving parts are answerable without React, a
  root or a DOM, and they are answered here so the browser file is left
  with only the claims a browser can make:

  1. **The entry reap horizon is past a bare `setTimeout 0`** — the
     property `hydrateRoot`'s passive subscribe needs, expressed as the
     race it has to win rather than as the integer it is implemented
     with. `read_profile_baseline_cljs_test`'s
     `one-bare-macrotask-lands-in-front-of-the-reap-horizon` runs that
     race on a clock that stands still; this file holds the other half,
     that an entry a commit claimed is never reaped at any horizon.
  2. **A runtime reset shuts the adoption window** — so a fixture that
     throws mid-hydration cannot leave the page permanently adopting.
  3. **`body-runs` counts real body runs**, is monotone, and survives
     `reset-runtime!` — HD-028's rider stated where it can be stated
     exactly."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.dogfood :as rf.bench.fresco.front.dogfood]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     ;; The map shape, because the claimed-entry row is `async` — it
     ;; waits the real horizon out.
     :async?  true
     :init-fn (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!) (rf.bench.fresco.arm1.runtime/reset-body-runs!))}))

(def ^:private frame-id ::arm1-hydrate)

(defn- seeded! []
  (rf.bench.fresco.arm1.runtime/reset-runtime!)
  (rf.bench.fresco.arm1.runtime/reset-body-runs!)
  (rf.bench.fresco.front.dogfood/make-frame! frame-id 3)
  frame-id)

(defn- one-body-run!
  "One boundary body through the shell's own fence, minus React."
  []
  (rf.bench.fresco.arm1.runtime/render-body frame-id (fn [_] [:li (str (rf.bench.fresco.arm1.runtime/sub [:dogfood/todo 0]))]) {})
  (rf.bench.fresco.arm1.runtime/last-reads))

;; ---------------------------------------------------------------------------
;; 1 — the reap horizon evicts the cache and never a claimed entry
;; ---------------------------------------------------------------------------

(deftest a-claimed-entry-is-never-reaped-at-any-horizon
  (async done
    (seeded!)
    (testing "the horizon is a cache-eviction schedule and nothing else — an
             entry a commit claimed is held by its `refs`, so no delay can
             drop it"
      (let [stop (rf.bench.fresco.arm1.runtime/commit-boundary! (one-body-run!) (fn [] nil))]
        (js/setTimeout
          (fn []
            (is (= 1 (:entries (rf.bench.fresco.arm1.runtime/stats))) "claimed, so past the horizon it stands")
            (stop)
            (done))
          8)))))

;; ---------------------------------------------------------------------------
;; 2 — the adoption window
;; ---------------------------------------------------------------------------

(deftest a-runtime-reset-shuts-an-open-adoption-window
  (seeded!)
  (testing "a fixture that throws between `hydrateRoot` and the closer's
           effect cannot leave the page adopting for every row after it"
    (rf.bench.fresco.arm1.runtime/open-adoption-window!)
    (is (true? (rf.bench.fresco.arm1.runtime/adopting?)) "opened by the door")
    (rf.bench.fresco.arm1.runtime/reset-runtime!)
    (is (false? (rf.bench.fresco.arm1.runtime/adopting?)) "and shut by a runtime reset, whatever threw")))

;; ---------------------------------------------------------------------------
;; 3 — the body-run counter (HD-028's rider)
;; ---------------------------------------------------------------------------

(deftest body-runs-counts-bodies-that-ran-and-is-not-cleared-by-a-reset
  (seeded!)
  (testing "**The instrument the X-witnesses read**. Always on, so the
           `:advanced` / `goog.DEBUG false` builds this lane drives can see
           it; bumped inside `run-once`, so a `React.memo` bail-out reads as
           an increment that did not happen"
    (dotimes [_ 3] (one-body-run!))
    (rf.bench.fresco.arm1.runtime/reset-runtime!)
    (is (= 3 (rf.bench.fresco.arm1.runtime/body-runs))
        "one per body, counted — and a teardown does NOT zero it — an instrument a teardown door
         resets is one a reading taken on the wrong side of the reset can
         pass with")))

(deftest a-fenced-re-run-is-two-body-runs-because-two-bodies-ran
  (seeded!)
  (testing "the generation fence re-runs a body that straddled a commit,
           and the counter says TWO — which is why it is bumped in
           `run-once` rather than once per `render-body`"
    (let [runs (volatile! 0)]
      (rf.bench.fresco.arm1.runtime/render-body frame-id
                                                (fn [_]
                                                  (when (= 1 (vswap! runs inc))
                                                    (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0]))
                                                  [:li])
                                                {})
      (is (= 2 (rf.bench.fresco.arm1.runtime/body-runs))))))
