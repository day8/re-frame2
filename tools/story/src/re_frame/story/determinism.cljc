(ns re-frame.story.determinism
  "The determinism gate — `assert-deterministic` (NewTestStory rf2-5x1wt.8,
  spec/017-Testing-Story.md §Determinism gate). It answers ONE question:
  does this plan / artifact produce the SAME run every time?

  ## What determinism means here

  A Story run is deterministic when replaying its event program into a
  FRESH frame N times yields the SAME canonical run-result every time —
  same final app-db, same effects, same assertion verdicts, same epoch
  causal spine — after the per-RUN bookkeeping is normalized away. The
  normalization is `re-frame.story.fingerprint/canonicalize` (rf2-5x1wt.3,
  EXTENDED by this bead): a fresh frame restarts the process-global epoch /
  dispatch / trace-id counters and allocates a new `:rf.test.replay/*`
  frame id, so two semantically-equal runs stamp DIFFERENT epoch ids,
  dispatch ids, trace ids, frame ids, and wall-clock times. The `.7`
  replay worker deliberately left stripping those per-frame stamps in the
  tape to THIS gate; `canonicalize` now strips them (reserved keys
  recursively, the common `:id` / `:time` / `:frame` stamps structurally on
  their trace-event / epoch-record carriers), so two semantically-equal
  runs canonicalize `=` and `run-hash` equal.

  ## The strip list (canonicalization, normalized before comparison)

  Per spec §Determinism gate `canonicalize` strips / normalizes, before two
  runs are compared:

  - wall-clock timestamps — `:committed-at` (epoch record), `:time` (trace
    event), `:elapsed-ms` (run / assertion record);
  - elapsed durations — `:elapsed-ms`;
  - generated dispatch ids — `:dispatch-id`;
  - generated frame ids — `:frame` (a fresh replay's `:rf.test.replay/*`);
  - runtime object identities — `:epoch-id` / `:trace-id` (process-global
    counters), `:schema-digest` (per-frame digest);
  - intentionally-unspecified source order — maps are key-sorted, sets
    element-sorted (effects / epochs keep producer order, which IS
    semantic).

  ## The bare-`[:wait ms]` opt-out → `:cannot-run`

  Determinism guarantees apply ONLY to plans free of wall-clock steps
  (spec §Unit and integration testing adjustments / §Script step grammar):
  `[:wait-until pred]` (queue/state-based) is deterministic; bare
  `[:wait ms]` is the explicit opt-out. A plan / artifact whose event
  program contains a bare `[:wait ms]` cannot be given a stable verdict, so
  `assert-deterministic` REFUSES it with `:cannot-run` (the spec's third
  result state) rather than running it flakily. This is the same refusal
  vocabulary the settled-boundary uses (rf2-5x1wt.2).

  ## Pure / JVM-testable

  This ns splits the PURE verdict logic (`wait-steps`, `has-wall-clock-wait?`,
  `cannot-run-wait-refusal`, `compare-runs`) from the impure REPLAY driver
  (`assert-deterministic`, which calls `.7`'s `replay-run-artifact` N times
  into fresh frames). The pure half runs under `clojure -M:test` with no
  runtime; the replay half settles synchronously to a fixed point via the
  headless flush-hooks, so the JVM gate exercises the full gate."
  (:require [re-frame.story.artifact    :as artifact]
            [re-frame.story.fingerprint :as fingerprint]
            [re-frame.story.play.runner :as runner]))

;; ===========================================================================
;; PLAN / ARTIFACT → REPLAYABLE ARTIFACT  (pure)
;; ===========================================================================

(defn ->artifact
  "Coerce a determinism-gate `target` into a replayable `:rf.test/run-artifact`.
  Pure data → data.

  `target` is one of:

  - a `:rf.test/run-artifact` map — used verbatim (it already carries the
    `:event-program` + `:fx-decisions`);
  - a normalized variant plan (a map with `:world` / `:script`) — its
    `[:world :setup]` ⧺ `:script` fold into the artifact `:event-program`
    (the same setup-first fold `make-run-artifact` applies) and its
    `[:world :frame :fx-overrides]` become the artifact `:fx-decisions`;
  - any other map carrying `:setup` / `:script` / `:event-program` — folded
    by `make-run-artifact` directly.

  Reading the plan's `[:world :setup]` / `:script` is the only contact this
  ns has with the plan compiler (`re-frame.story.plan`); it WRITES nothing
  there. An already-built artifact short-circuits so a recorder's captured
  program replays verbatim."
  [target]
  (cond
    (artifact/run-artifact? target)
    target

    ;; A normalized variant plan: setup lives under [:world :setup], the
    ;; primary script under :script, fx decisions under [:world :frame
    ;; :fx-overrides]. Fold setup ⧺ script into the event program.
    (and (map? target) (contains? target :world))
    (artifact/make-run-artifact
      {:setup        (get-in target [:world :setup] [])
       :script       (get target :script [])
       :fx-decisions (get-in target [:world :frame :fx-overrides] {})
       :source       {:tool :determinism-gate :variant/id (:variant/id target)}})

    :else
    (artifact/make-run-artifact target)))

;; ===========================================================================
;; THE BARE-[:wait ms] OPT-OUT  (pure)
;; ===========================================================================

(defn wait-steps
  "The bare wall-clock `[:wait ms]` steps in an artifact's `:event-program`
  (spec §Script step grammar: `[:wait ms]` is the explicit determinism
  opt-out). Pure data → data; returns them in program order.

  `[:wait-until pred]` is NOT a wait step — it settles on a queue/state
  condition and is deterministic — so it never appears here."
  [artifact]
  (filterv #(= :wait (runner/step-type %)) (:event-program artifact)))

(defn has-wall-clock-wait?
  "True iff the artifact's event program contains a bare `[:wait ms]` step —
  the explicit determinism opt-out the gate must refuse. Pure data → data."
  [artifact]
  (boolean (seq (wait-steps artifact))))

(defn cannot-run-wait-refusal
  "Build the `:cannot-run` refusal for a plan/artifact carrying a bare
  `[:wait ms]` step (spec §Determinism gate: refuse rather than produce a
  flaky verdict). Pure data → data; shape mirrors the settled-boundary
  refusal (a distinct third status, never a silent pass)."
  [artifact]
  {:status :cannot-run
   :reason :determinism-wall-clock-wait
   :wait-steps (wait-steps artifact)
   :detail (str "re-frame2-story: assert-deterministic refuses a plan with a "
                "bare [:wait ms] step — wall-clock waits are the explicit "
                "determinism opt-out (use [:wait-until pred] for a "
                "deterministic, queue/state-based settle).")})

;; ===========================================================================
;; COMPARE N RUNS  (pure)
;; ===========================================================================

(defn compare-runs
  "Compare the canonical forms + `run-hash`es of `results` (the N replay
  run-results). Pure data → data.

  Returns:

      {:deterministic? boolean
       :run-count      N
       :run-hash       <stable hash when deterministic, else nil>
       :hashes         [hash …]              ; one per run, in order
       :divergence     {…}}                  ; present only when NOT deterministic

  A run is deterministic iff every run's canonical RUN-SLICE is `=`. The
  slice is `re-frame.story.fingerprint/run-hash-input-keys` — the
  behavioural surface (`:status`, final `:app-db`, `:epoch-tape`,
  `:assertions` / `:checks`, projected `:effects` / `:schema-violations` /
  `:warnings`, `:sub-overrides` / `:fidelity`) — NOT the whole result: the
  result also carries pure provenance (`:frame` replay id, `:run-artifact`
  back-link, `:replay-steps`) that legitimately differs per replay and is
  excluded from `run-hash` for exactly this reason. The hash is the cheap
  discriminator; equality of the canonical slice is the authority, so a
  hash collision can never report a false GREEN. The `:divergence` slot
  names the FIRST run whose canonical slice differs from run 0, with both
  hashes, for a readable failure."
  [results]
  (let [n        (count results)
        slice    (fn [r] (select-keys r fingerprint/run-hash-input-keys))
        ;; Canonicalize each run-slice ONCE and reuse the result for BOTH the
        ;; hash and the equality. `fingerprint/run-hash` would re-canonicalize
        ;; the same slice internally (`canonical-hash` → `canonicalize`), so
        ;; hashing the canon directly via `content-hash` avoids the second
        ;; pass while staying byte-identical: `run-hash` ≡ `(content-hash
        ;; (canonicalize (slice r)))`, since `content-hash` only orders (it
        ;; does NOT re-strip) and `canonicalize` is already the stripped,
        ;; ordered form. The two-stage contract — the hash is the cheap
        ;; discriminator, canonical `=` is the authority — is preserved exactly.
        canons   (mapv (comp fingerprint/canonicalize slice) results)
        hashes   (mapv fingerprint/content-hash canons)
        base     (first canons)
        diverged (first (keep-indexed
                          (fn [i c] (when (and (pos? i) (not= base c)) i))
                          canons))]
    (cond-> {:deterministic? (nil? diverged)
             :run-count      n
             :hashes         hashes
             :run-hash       (when (nil? diverged) (first hashes))}
      diverged
      (assoc :divergence
             {:run        diverged
              :run-hash-0 (first hashes)
              :run-hash-n (nth hashes diverged)
              :detail     (str "run " diverged " diverged from run 0 — the "
                               "canonical run-results are not =")}))))

;; ===========================================================================
;; THE GATE  (impure — N fresh-frame replays)
;; ===========================================================================

(def ^:const default-runs
  "Default replay count for the determinism gate. Two runs are the minimum
  that can witness non-determinism; callers pin a higher N for flaky-hunt
  confidence via `:runs`."
  2)

(defn assert-deterministic
  "Assert that `plan-or-artifact` produces the SAME run every time — the
  determinism gate (spec §Determinism gate). Replays the event program into
  N FRESH frames via `re-frame.story.artifact/replay-run-artifact` and
  compares the runs through `re-frame.story.fingerprint/canonicalize`.

  `plan-or-artifact` is a `:rf.test/run-artifact`, a normalized variant
  plan, or a `:setup`/`:script`/`:event-program` body — coerced by
  `->artifact`.

  `opts` (all optional):

  - `:runs`  — replay count (default 2). N >= 2.
  - `:hooks` / `:frame-config` — threaded to `replay-run-artifact` (a `:dom`
    adapter passes richer settled-boundary hooks; the fx decisions are
    routed THROUGH that hook's `:dispatch!` — they wrap it, never replace
    it — so the adapter dispatch path still runs on every replay).

  Returns one of three statuses (never a flaky verdict):

  - `{:status :cannot-run :reason :determinism-wall-clock-wait …}` — the
    program contains a bare `[:wait ms]`; the gate REFUSES rather than run
    it flakily. This is computed BEFORE any replay (pure pre-flight).
  - `{:status :deterministic :run-hash <hash> :runs N :hashes [...]}` — every
    replay canonicalized `=`.
  - `{:status :non-deterministic :divergence {…} :runs N :hashes [...]
      :results [run-result …]}` — at least one replay diverged; the
    divergence names the first differing run + both run-hashes, and the
    per-run results are returned for a semantic diff (rf2-5x1wt.9).

  Each replay runs into its OWN fresh `:rf.test.replay/*` frame (torn down
  before return), so the gate observes no cross-run app-db leak — the same
  isolation `replay-run-artifact` already guarantees."
  ([plan-or-artifact] (assert-deterministic plan-or-artifact nil))
  ([plan-or-artifact {:keys [runs hooks frame-config] :as _opts}]
   (let [art (->artifact plan-or-artifact)
         n   (max 2 (or runs default-runs))]
     (if (has-wall-clock-wait? art)
       ;; Pure pre-flight refusal — never replay a wall-clock plan.
       (cannot-run-wait-refusal art)
       (let [replay-opts (cond-> {}
                           hooks        (assoc :hooks hooks)
                           frame-config (assoc :frame-config frame-config))
             results     (mapv (fn [_] (artifact/replay-run-artifact art replay-opts))
                               (range n))
             ;; A replay that itself refused/errored is not a determinism
             ;; question — surface it directly rather than comparing.
             refused     (some (fn [r] (when (= :cannot-run (:status r)) r)) results)
             errored     (some (fn [r] (when (= :error (:status r)) r)) results)
             cmp         (compare-runs results)]
         (cond
           refused (assoc refused :runs n :results results)
           errored (assoc errored :runs n :results results)

           (:deterministic? cmp)
           {:status   :deterministic
            :runs     n
            :run-hash (:run-hash cmp)
            :hashes   (:hashes cmp)
            :artifact art}

           :else
           {:status     :non-deterministic
            :runs       n
            :hashes     (:hashes cmp)
            :divergence (:divergence cmp)
            :results    results
            :artifact   art}))))))
