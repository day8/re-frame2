(ns re-frame.story.ui.watch
  "Watch-mode detector compute + hot-path cache (rf2-z1h0f / rf2-zrswb).

  The chrome-level test widget's eye toggle enables watch mode; while on,
  the shell polls `compute-testable-content-hashes` every 500ms, diffs it
  against the recorded `[:tests :content-hashes]` slot, and re-runs the
  variants whose snapshot-identity drifted.

  This leaf hosts ONLY the pure-ish compute + its cache — it requires
  neither `shell` nor `sidebar`, so BOTH can consume it without a load
  cycle. The detector orchestration (`detect-watch-drift!`, which calls
  `sidebar/watch-rerun!`) stays in `re-frame.story.ui.shell`; the toggle
  seed (`sidebar/set-watch-mode!`, which must compute the baseline BEFORE
  flipping the flag — rf2-asp2op) lives in `re-frame.story.ui.sidebar`.
  Both call `compute-testable-content-hashes` here.

  ## Hot path (rf2-zrswb)

  `compute-testable-content-hashes` runs every 500ms while watch mode is
  on. The naïve walk hashed every testable variant on every tick — the
  canonical tuple serialised per variant, O(V × variant-body-size), to
  produce the same hash 99.9% of the time.

  The cache below keys on the inputs that can perturb a snapshot-identity
  hash across two polls:

  1. `registrar-mutation-tick` — a Story registrar write invalidates
     every variant entry.
  2. `active-modes` / `substrate` — shell-state render context.
  3. `cell-overrides` — per-cell control edits land here and ARE threaded
     through `args/resolve-args` into `snapshot-tuple`'s `:effective-args`
     slot (identity.cljc + args.cljc), so they DO perturb the hash
     (rf2-mclvi — a dropped `:cell-overrides` key served stale answers
     after every control edit and the detector missed the re-run).

  When the key matches the previous tick's key we return the cached map —
  zero hashing work. When it drifts we recompute the lot once and re-seed."
  (:require [re-frame.story.identity  :as identity]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.ui.state  :as state]))

(defonce ^:private testable-hash-cache
  (atom {:key nil :hashes nil}))

(defn compute-testable-content-hashes
  "Walk the registered testable variants and return a `{variant-id →
  hex-hash}` map of snapshot-identity content hashes. The hash captures
  the variant's `:play-script` / `:events` / `:loaders` / `:decorators` /
  `:component` / `:sub-overrides` / `:db-seed` / `:network` / `:tags`
  slots plus the parent story's slice, the view's registered schema-
  digest, AND the variant's resolved effective args (which fold in the
  user's live `:cell-overrides`). See `re-frame.story.identity` §What's in
  the hash + /spec/007-Stories.md §Variant snapshot identity.

  HOT PATH (rf2-zrswb): the registrar-driven cache short-circuits when
  none of the perturbing inputs (registrar tick, `:active-modes`,
  `:substrate`, `:cell-overrides`) have drifted since the last tick — see
  the ns docstring."
  []
  (let [shell      (state/get-state)
        modes      (:active-modes shell)
        subs       (:substrate shell)
        overrides  (:cell-overrides shell)
        tick       (registrar/current-mutation-tick)
        key        [tick modes subs overrides]
        cached     @testable-hash-cache]
    (if (= key (:key cached))
      (:hashes cached)
      (let [testable (state/testable-variant-ids
                       (:variants (state/registry-snapshot)))
            hashes   (into {}
                           (map (fn [vid]
                                  (let [opts {:active-modes   modes
                                              :substrate      subs
                                              :cell-overrides (get overrides vid)}]
                                    [vid (:content-hash
                                           (identity/snapshot-identity vid opts))])))
                           testable)]
        (reset! testable-hash-cache {:key key :hashes hashes})
        hashes))))
