(ns re-frame.story.generate
  "Generated / property-style Story runs that emit run artifacts carrying
  their SEED and SHRINK data — and promote only curated failures
  (NewTestStory rf2-5x1wt.31, `tools/story/spec/017-Testing-Story.md`
  §Generated runs and artifacts).

  ## Generated runs emit artifacts FIRST

  A property-style Story run generates an event PROGRAM from a seed, replays
  it (`re-frame.story.artifact/replay-run-artifact`), and judges the
  resulting run-result. Most generated programs PASS and are throwaway; a
  failing one is the interesting case. This ns wires generated runs to the
  EXISTING run-artifact surface: every generated run produces a
  `:rf.test/run-artifact` carrying the `:seed` it was generated from (and,
  for a shrunk failure, the `:shrink-path`), so a generated failure is
  replayable off-CI and promotable into a curated regression variant
  (§Promotion) — artifacts FIRST, promotion only when curated. The `:seed` /
  `:shrink-path` artifact slots already exist (§Artifacts — Run artifact,
  rf2-5x1wt.7); this ns is the producer that fills them.

  ## Generator-agnostic — a `gen-fn` of the seed

  This ns does NOT bundle a generator library. A generated run is driven by a
  caller-supplied `gen-fn` — a pure `(fn [seed] event-program)` that, given a
  numeric seed, returns a deterministic event program (a vector of tagged
  steps, or bare event vectors the artifact coerces). The seed sequence is a
  pure, seedable splitmix64 PRNG (`seed-seq`), so a property run over N seeds
  is fully reproducible: the same root seed replays the same N programs. This
  keeps the substrate self-contained (no new dependency) while leaving the
  CHOICE of generator to the caller — a `clojure.test.check` generator, a
  hand-rolled state-machine walk, or a recorded-interaction fuzzer all fit by
  wrapping their draw in a `gen-fn`.

  ## Shrinking — deterministic program-prefix delta-debug

  When a generated program FAILS, `shrink-program` searches for a SMALLER
  failing program by deterministic delta-debugging over the event program:
  it tries to drop steps (largest chunks first, then finer), keeping any
  reduction that STILL fails the same way, until no single-step removal keeps
  the failure. The `:shrink-path` records the ordered sequence of candidate
  programs that were KEPT (each a smaller failing program than the last), so a
  consumer can see how the minimal failing case was reached. Shrinking reuses
  the SAME replay path as the original run — no separate execution model — so
  a shrunk artifact replays identically.

  ## Pure / impure split

  The seed PRNG (`seed-seq`), the failure predicate (`failing?`), and the
  shrink CANDIDATE enumeration (`drop-candidates`) are pure data → data and
  JVM-testable with no host. The run + shrink DRIVERS (`run-seed!`,
  `shrink-program!`, `check-property!`) are the impure seam — they call
  `replay-run-artifact` into fresh `:rf.test.replay/*` frames (torn down by
  the replay path), so the JVM `clojure -M:test` gate exercises the full
  generated-run → artifact flow against a live frame synchronously."
  (:require [re-frame.story.artifact :as artifact]))

;; ===========================================================================
;; SEEDABLE PRNG  (pure — splitmix64)
;; ===========================================================================
;;
;; A pure, reproducible 64-bit splitmix64 step. Given a seed it returns the
;; NEXT seed; `seed-seq` unfolds a deterministic seed sequence from a root
;; seed. No host RNG, no `rand` — WITHIN a host the same root seed always
;; yields the same sequence, which is what makes a property run reproducible
;; from its recorded `:seed` on that host. The bit-pattern differs JVM↔CLJS
;; (`next-seed` below), so seed-reproducibility is within-host; cross-host
;; replay rides the artifact's recorded `:event-program` (spec/017
;; §Generator-agnostic).

;; The splitmix64 constants, written as SIGNED two's-complement longs (the
;; JVM reader rejects a hex literal whose value exceeds Long/MAX_VALUE, so the
;; unsigned 0x9E37…/0xBF58…/0x94D0… constants are spelled as their signed
;; equivalents). CLJS computes the same mix in `js/BigInt`.
#?(:clj
   (do
     (def ^:private ^:const splitmix-gamma  -7046029254386353131)  ; 0x9E3779B97F4A7C15
     (def ^:private ^:const splitmix-mul-1  -4658895280553007687)  ; 0xBF58476D1CE4E5B9
     (def ^:private ^:const splitmix-mul-2  -7723592293110705685))) ; 0x94D049BB133111EB

(defn next-seed
  "The splitmix64 successor of `seed` — a pure 64-bit mix. Deterministic
  WITHIN a host: the same `seed` always returns the same successor on the
  same host. Pure data → data.

  CLJS computes in `js/BigInt` (doubles cannot hold 64 exact bits) and
  returns a JS number truncated into the safe-integer range for ergonomic
  use as a seed; the JVM uses native wrapping `long` arithmetic. The exact
  bit-pattern therefore DIFFERS across hosts (CLJS truncates to
  `MAX_SAFE_INTEGER`; the JVM keeps the full signed 64-bit mix), so the same
  root seed unfolds different program seeds JVM↔CLJS. A recorded `:seed` is
  thus reproducible only WITHIN the host that produced it; cross-host replay
  (author on CLJS, CI gate on JVM) rides the recorded CONCRETE
  `:event-program` instead — which IS host-portable (tagged-step data). This
  within-host scope is by design: the artifact's `:event-program` carries the
  cross-host reproducer, so the seed never needs to (spec/017
  §Generator-agnostic)."
  [seed]
  #?(:clj
     (let [z (unchecked-add (unchecked-long seed) splitmix-gamma)
           z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 30))
                                 splitmix-mul-1)
           z (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 27))
                                 splitmix-mul-2)]
       (bit-xor z (unsigned-bit-shift-right z 31)))
     :cljs
     (let [m   (js/BigInt "0xFFFFFFFFFFFFFFFF")
           z   (bit-and (+ (js/BigInt seed) (js/BigInt "0x9E3779B97F4A7C15")) m)
           z   (bit-and (* (bit-xor z (bit-shift-right z (js/BigInt 30)))
                           (js/BigInt "0xBF58476D1CE4E5B9")) m)
           z   (bit-and (* (bit-xor z (bit-shift-right z (js/BigInt 27)))
                           (js/BigInt "0x94D049BB133111EB")) m)
           z   (bit-xor z (bit-shift-right z (js/BigInt 31)))]
       ;; Truncate into JS safe-integer range for an ergonomic numeric seed.
       (js/Number (bit-and z (js/BigInt js/Number.MAX_SAFE_INTEGER))))))

(defn seed-seq
  "A deterministic sequence of `n` seeds unfolded from `root-seed` via
  `next-seed`. Pure data → data — WITHIN a host the same `root-seed` always
  yields the same `n` seeds, so a property run over the sequence is reproducible
  on that host. Because `next-seed`'s bit-pattern differs JVM↔CLJS, the seed
  sequence diverges across hosts; cross-host replay rides the recorded
  `:event-program` (see `next-seed` / spec/017 §Generator-agnostic). The first
  element is the splitmix successor of `root-seed` (so the root seed itself is
  never reused as a program seed)."
  [root-seed n]
  (->> root-seed
       (iterate next-seed)
       rest
       (take (max 0 n))
       vec))

;; ===========================================================================
;; THE RUN ARTIFACT FOR A GENERATED RUN  (pure construction)
;; ===========================================================================

(defn generated-artifact
  "Build the `:rf.test/run-artifact` for a generated run from its `seed`,
  generated `event-program`, and optional `:fx-decisions` / `:shrink-path` /
  `:source`. Pure data → data — `re-frame.story.artifact/make-run-artifact`
  coerces the program + stamps `:artifact/kind`; this fills the `:seed`
  provenance slot (and `:shrink-path` for a shrunk failure) the generated
  flow is about. The default `:source` records that the artifact came from
  the property runner, so a promoted variant explains it was generated."
  [{:keys [seed event-program fx-decisions shrink-path source] :as _parts}]
  (artifact/make-run-artifact
    (cond-> {:seed          seed
             :event-program (vec event-program)
             :fx-decisions  (or fx-decisions {})
             :source        (or source {:tool :rf.story/property-run :seed seed})}
      (some? shrink-path) (assoc :shrink-path shrink-path))))

;; ===========================================================================
;; FAILURE PREDICATE  (pure)
;; ===========================================================================

(defn failing?
  "True iff a replay `run-result`'s `:status` is a genuine FAILURE — `:fail`
  or `:error`. A `:cannot-run` is NOT a failure (the runner could not attempt
  it; a property over a wrong-runner program is inconclusive, not falsified),
  and `:pass` is not. Pure data → data — the property's falsification signal."
  [run-result]
  (contains? #{:fail :error} (:status run-result)))

;; ===========================================================================
;; SHRINK CANDIDATE ENUMERATION  (pure — deterministic delta-debug)
;; ===========================================================================

(defn drop-candidates
  "The ordered candidate programs to try when shrinking `program` at
  granularity `chunk-size` — each is `program` with one contiguous
  `chunk-size`-step window removed, scanning left to right. Pure data → data;
  deterministic. A `chunk-size` >= the program length yields the empty
  program as the sole candidate; a `chunk-size` <= 0 yields none.

  This is the classic ddmin reduction lattice: shrinking tries the LARGEST
  chunks first (halving on failure to reduce further) so the search reaches a
  small failing case in few replays, deterministically."
  [program chunk-size]
  (let [v (vec program)
        n (count v)]
    (if (or (<= chunk-size 0) (zero? n))
      []
      (->> (range 0 n chunk-size)
           (mapv (fn [start]
                   (let [end (min n (+ start chunk-size))]
                     (into (subvec v 0 start) (subvec v end)))))))))

;; ===========================================================================
;; RUN ONE SEED  (impure — fresh-frame replay)
;; ===========================================================================

(defn run-seed!
  "Generate the event program for `seed` via `gen-fn`, replay it into a FRESH
  frame, and return `{:seed :event-program :result :artifact}`. Impure — it
  replays into a `:rf.test.replay/*` frame (allocated + torn down by
  `replay-run-artifact`).

  `gen-fn` is a pure `(fn [seed] event-program)`; `opts` may carry
  `:fx-decisions` (reapplied on every dispatch of the generated program) and
  the `:hooks` / `:frame-config` `replay-run-artifact` accepts. The returned
  `:artifact` is the run's `:rf.test/run-artifact` carrying the `:seed` (and
  the replay's projected `:result`), so EVERY generated run — pass or fail —
  has a replayable, seed-bearing artifact (artifacts first)."
  [gen-fn seed {:keys [fx-decisions hooks frame-config] :as _opts}]
  (let [program     (vec (gen-fn seed))
        base        (artifact/make-run-artifact
                      {:event-program program
                       :fx-decisions  (or fx-decisions {})})
        replay-opts (cond-> {}
                      hooks        (assoc :hooks hooks)
                      frame-config (assoc :frame-config frame-config))
        result      (artifact/replay-run-artifact base replay-opts)
        artifact    (generated-artifact
                      {:seed          seed
                       :event-program program
                       :fx-decisions  fx-decisions
                       :source        {:tool :rf.story/property-run :seed seed}})]
    {:seed          seed
     :event-program program
     :result        (assoc result :seed seed)
     :artifact      (assoc artifact :result (assoc result :seed seed))}))

;; ===========================================================================
;; SHRINK A FAILING PROGRAM  (impure — fresh-frame replays)
;; ===========================================================================

(defn shrink-program!
  "Delta-debug `failing-program` toward a SMALLER program that still FAILS,
  replaying each candidate into a fresh frame. Returns
  `{:program minimal-failing-program :shrink-path [program …] :result …}` —
  `:program` is the smallest failing program found, `:shrink-path` the ordered
  sequence of KEPT reductions (each smaller + still-failing than the last,
  EXCLUDING the original), and `:result` the minimal program's replay result.

  Impure — each candidate is replayed via `re-frame.story.artifact/replay-run-
  artifact` (fresh `:rf.test.replay/*` frames). The reduction keeps a
  candidate iff its replay still `failing?`; granularity halves from the
  program length down to single-step removals (the ddmin schedule), so the
  search is deterministic and terminates. `opts` threads `:fx-decisions` /
  `:hooks` / `:frame-config` to the replays.

  `max-replays` (default 200) caps the search so a pathological program cannot
  replay unboundedly; on reaching the cap the smallest failing program found
  so far is returned (a partial shrink is still a smaller, valid artifact)."
  ([failing-program opts] (shrink-program! failing-program opts 200))
  ([failing-program {:keys [fx-decisions hooks frame-config] :as _opts} max-replays]
   (let [replay-opts (cond-> {}
                       hooks        (assoc :hooks hooks)
                       frame-config (assoc :frame-config frame-config))
         replay!     (fn [program]
                       (let [a (artifact/make-run-artifact
                                 {:event-program program
                                  :fx-decisions  (or fx-decisions {})})]
                         (artifact/replay-run-artifact a replay-opts)))]
     (loop [current     (vec failing-program)
            chunk-size  (max 1 (quot (count failing-program) 2))
            shrink-path []
            best-result nil
            replays     0]
       (let [candidates (drop-candidates current chunk-size)
             ;; First candidate that still fails — keep it and restart the
             ;; reduction at the same granularity against the smaller program.
             [kept kept-result n-tried]
             (reduce (fn [_ cand]
                       (let [r (replay! cand)]
                         (if (failing? r)
                           (reduced [cand r 1])
                           [nil nil 1])))
                     [nil nil 0]
                     candidates)
             replays' (+ replays (count candidates))]
         (cond
           ;; Cap reached — return the best (smallest failing) so far.
           (>= replays' max-replays)
           {:program     (if kept kept current)
            :shrink-path (cond-> shrink-path kept (conj kept))
            :result      (or kept-result best-result)}

           ;; A smaller failing program — keep it, restart at this granularity.
           (some? kept)
           (recur kept
                  (max 1 (min chunk-size (quot (count kept) 2)))
                  (conj shrink-path kept)
                  kept-result
                  replays')

           ;; No reduction at this granularity — refine, or finish at size 1.
           (> chunk-size 1)
           (recur current (max 1 (quot chunk-size 2)) shrink-path best-result replays')

           :else
           {:program     current
            :shrink-path shrink-path
            :result      best-result}))))))

;; ===========================================================================
;; CHECK A PROPERTY OVER N SEEDS  (impure — the generated-run entry point)
;; ===========================================================================

(defn check-property!
  "Run a property over `n` generated programs and return a result that —
  on falsification — carries the SHRUNK, seed-bearing failing
  `:rf.test/run-artifact` (rf2-5x1wt.31, spec/017 §Generated runs and
  artifacts). The generated-run entry point.

  `gen-fn` is a pure `(fn [seed] event-program)`; `opts`:

  - `:seed`         — the ROOT seed the `n` program seeds unfold from
                      (`seed-seq`). Default 0. Recording it reproduces the whole
                      property run WITHIN the host that produced it; it is
                      provenance, not a cross-host reproducer (the splitmix
                      bit-pattern differs JVM↔CLJS — see `next-seed`). A
                      falsifying run's recorded `:event-program` is what
                      replays the concrete failure cross-host (spec/017
                      §Generator-agnostic).
  - `:num-tests`    — `n`, the number of generated programs (default 100).
  - `:shrink?`      — shrink a failing program toward a minimal failing case
                      (default true). When false the first failing program is
                      reported as-is.
  - `:fx-decisions` — fx overrides reapplied on every generated dispatch (the
                      world the property runs against). Ride the artifact's
                      `:fx-decisions` so a fault-injected property replays
                      against the same stubs.
  - `:hooks` / `:frame-config` — threaded to the replays (a `:dom` adapter
                      supplies richer settled-boundary hooks).

  Returns one of:

  - `{:status :pass :num-tests n :seed root-seed}` — every generated program
    passed (or was `:cannot-run`, which does not falsify).
  - `{:status :fail :seed failing-seed :root-seed root-seed :num-tests tried
      :smallest-program [step …] :shrink-path [program …] :artifact <run-
      artifact> :result <run-result>}` — a program FAILED. The `:artifact` is
    the failing run-artifact carrying its `:seed` + (when shrunk) its
    `:shrink-path`, ready to feed `re-frame.story.promotion/promote-run-
    artifact!` for a CURATED regression variant — artifacts first, promotion
    only when an author decides (§Promotion). The `:shrink-path` is the
    ordered sequence of kept reductions from the original failing program to
    the minimal one.

  Each program runs into its OWN fresh `:rf.test.replay/*` frame (torn down by
  the replay path), so the property observes no cross-program app-db leak."
  [gen-fn {:keys [seed num-tests shrink? fx-decisions hooks frame-config] :as opts}]
  (let [root-seed (or seed 0)
        n         (or num-tests 100)
        shrink?   (if (contains? opts :shrink?) (boolean shrink?) true)
        run-opts  (cond-> {}
                    (some? fx-decisions) (assoc :fx-decisions fx-decisions)
                    (some? hooks)        (assoc :hooks hooks)
                    (some? frame-config) (assoc :frame-config frame-config))
        seeds     (seed-seq root-seed n)]
    (loop [seeds   seeds
           tried   0]
      (if (empty? seeds)
        {:status    :pass
         :num-tests tried
         :seed      root-seed}
        (let [s   (first seeds)
              run (run-seed! gen-fn s run-opts)]
          (if (failing? (:result run))
            ;; Falsified — shrink (if asked), then build the failing artifact
            ;; carrying its seed + shrink-path.
            (let [program  (:event-program run)
                  shrunk   (when shrink?
                             (shrink-program! program run-opts))
                  smallest (if shrunk (:program shrunk) program)
                  spath    (when (and shrunk (seq (:shrink-path shrunk)))
                             (:shrink-path shrunk))
                  result   (or (:result shrunk) (:result run))
                  artifact (generated-artifact
                             {:seed          s
                              :event-program smallest
                              :fx-decisions  fx-decisions
                              :shrink-path   spath
                              :source        {:tool      :rf.story/property-run
                                              :seed      s
                                              :root-seed root-seed
                                              :shrunk?   (boolean spath)}})]
              {:status           :fail
               :seed             s
               :root-seed        root-seed
               :num-tests        (inc tried)
               :original-program program
               :smallest-program smallest
               :shrink-path      (vec spath)
               :result           (assoc result :seed s)
               :artifact         (assoc artifact :result (assoc result :seed s))})
            (recur (rest seeds) (inc tried))))))))

;; ===========================================================================
;; FAULT LATTICE SWEEP  (impure — derived from the existing fx-override world)
;; ===========================================================================
;;
;; A fault lattice sweep replays ONE base program across a small lattice of
;; FAULT cells, where each cell is a different `:fx-decisions` map (the same
;; fx-override world input `replay-run-artifact` already reapplies, and the
;; same surface the `:network` world slot lowers to). It collects one
;; seed-bearing artifact per cell, so an author can see which fault states
;; falsify the program. This is DERIVED entirely from the existing
;; fx-override world input + the run-artifact surface — no new fault-injection
;; contract: a "fault" is just a different override map.

(defn sweep-faults!
  "Replay `base-program` across a `fault-lattice` of `:fx-decisions` cells and
  collect one `:rf.test/run-artifact` per cell (rf2-5x1wt.31, spec/017
  §Fault lattice sweep). Impure — each cell replays into a fresh frame.

  `fault-lattice` is a map `{cell-id fx-decisions}` (or a seq of `[cell-id
  fx-decisions]` pairs) — each `fx-decisions` is the SAME `{fx-id override}`
  shape the artifact + `:fx-overrides` world input carry (a 'fault' is an fx
  override that fails / delays / mis-replies). `opts` threads `:seed` (stamped
  on each cell's artifact for provenance), `:hooks`, `:frame-config`.

  Returns `{:cells [{:cell cell-id :status … :artifact <run-artifact>
  :result <run-result>} …] :failing [cell-id …]}` — one entry per cell in
  lattice order, plus the ids of the cells whose run FAILED (`failing?`). Each
  cell's artifact carries the faulted `:fx-decisions` so it replays against the
  same fault, and is promotable (§Promotion) — artifacts first."
  [base-program fault-lattice {:keys [seed hooks frame-config] :as _opts}]
  ;; `(seq fault-lattice)` handles BOTH accepted shapes uniformly: a map seqs
  ;; to its `[cell-id fx-decisions]` entry pairs, a pair-seq seqs to its pairs
  ;; — and the cell destructure `(fn [[cell-id fx-decisions]] …)` consumes
  ;; either. The map?/seq distinction is genuinely vacuous (no order or
  ;; seqability concern differs between the arms), so there is one branch.
  (let [cells   (seq fault-lattice)
        results (mapv
                  (fn [[cell-id fx-decisions]]
                    (let [a       (artifact/make-run-artifact
                                    {:event-program base-program
                                     :fx-decisions  (or fx-decisions {})})
                          ropts   (cond-> {}
                                    hooks        (assoc :hooks hooks)
                                    frame-config (assoc :frame-config frame-config))
                          result  (artifact/replay-run-artifact a ropts)
                          artifact (generated-artifact
                                     {:seed          seed
                                      :event-program base-program
                                      :fx-decisions  fx-decisions
                                      :source        {:tool  :rf.story/fault-sweep
                                                      :cell  cell-id
                                                      :seed  seed}})]
                      {:cell     cell-id
                       :status   (:status result)
                       :result   result
                       :artifact (assoc artifact :result result)}))
                  cells)]
    {:cells   results
     :failing (into [] (comp (filter #(failing? (:result %))) (map :cell)) results)}))
