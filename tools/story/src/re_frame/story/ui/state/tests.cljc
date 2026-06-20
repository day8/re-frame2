(ns re-frame.story.ui.state.tests
  "Pure test-run aggregation + watch-mode helpers for the shell state
  map. Split from `re-frame.story.ui.state` to honor the leaf-size
  ceiling.

  ## What lives here

  - `test-run-statuses`         — canonical run-state ids.
  - `mark-test-running`         — pure transition.
  - `aggregate-summary`         — fold a per-variant assertion vector
                                  into pass/fail/skipped counts.
  - `record-test-run`           — write the aggregate into
                                  `[:tests :runs <variant-id>]`.
  - `clear-test-run`            — drop a run record.
  - `variant-test-status`       — read the per-variant status keyword.
  - `test-summary`              — aggregate across an id-seq.
  - `testable-variant-ids`      — derive the seq of `:test`-tagged
                                  variants with a non-empty `:play-script`.
  - `set-test-watch-mode`       — toggle the chrome watch-mode flag.
  - `test-watch-mode?`          — read the flag.
  - `record-test-content-hashes` — stamp per-variant snapshot hashes.
  - `watch-mode-drift`          — pure differ over prev/current hash maps.

  ## Why a separate leaf

  Both surfaces (test-runs + watch-mode) read/write under the same
  `:tests` root in the shell-state map. They share no code with the
  selection / filter / cell-override surfaces in
  `re-frame.story.ui.state` proper — splitting honors the leaf-size
  ceiling without losing locality. The parent ns re-exports the
  public defs so existing consumer requires (`re-frame.story.ui.state`)
  keep working.")

;; ---- test-runs -----------------------------------------------------------
;;
;; Cross-variant aggregation surface: each variant's last `run-variant`
;; outcome is folded into `[:tests :runs]`. The chrome-level test
;; widget reads it as a summary; the sidebar's per-variant rows read
;; individual entries as a status dot. Both surfaces are pure
;; derivations of this one slot.
;;
;; The test-mode pane's local `results-atom` (in
;; `re-frame.story.ui.test-mode.state`) keeps the full result-map
;; (assertion records + expanded-row UI state); this shell-state slot
;; carries only the aggregate counts the chrome widget + sidebar dots
;; need. Two stores, two read paths, no contention — the pane's local
;; atom drives the detail view, the shell-state slot drives the global
;; surfaces.

(def test-run-statuses
  "Canonical run-state ids, in render order. `:cannot-run` is the
  unified result's distinct THIRD status (spec/017 §`:cannot-run`).

  - `:pass`        last run: every assertion passed (and at least one assertion).
  - `:fail`        last run: ≥1 assertion failed.
  - `:cannot-run`  last run: the only unmet expectations were ones the
                   runner could not even attempt (a refusal, not a pass).
  - `:running`     run currently in flight.
  - `:pending`     no run recorded yet (or run produced zero assertions)."
  [:pass :fail :cannot-run :running :pending])

(defn mark-test-running
  "Stamp `variant-id` as :running. Idempotent."
  [state variant-id]
  (assoc-in state [:tests :runs variant-id] {:status :running}))

(defn- record-status
  "The unified verdict for ONE assertion record: the record's own
  `:status` when it is one of the four verdicts (the unified shape),
  else derived from the outcome fields. Pure data →
  data — a local mirror so this leaf needs no require into
  `re-frame.story.result` (which would loop through the runtime via
  `result` → `runtime`).

  This MUST track `re-frame.story.result/record-status` verdict-for-
  verdict — it cannot delegate (the cycle above) so the rule is copied
  by hand. The canonical ordering (and the one reproduced here) is:

  - an explicit `:status` that is one of the four verdicts wins;
  - `:cannot-run?` / `:skipped?` truthy → `:cannot-run`;
  - `:exception` / `:error` truthy → `:error` (a thrown handler / fx /
    step — a derived record carrying `:exception`/`:error` but no
    explicit `:status` must count as `:error`, not a false-green `:pass`);
  - `:passed?` true → `:pass`; `:passed?` false → `:fail`;
  - otherwise `:pass` (a non-assertion / vacuous record does not fail
    the run — the /spec/007-Stories.md §Story-as-test duality).

  A parity test (`record-status-mirrors-canonical`) runs a table of
  record shapes through BOTH this mirror and the canonical, asserting
  identical verdicts, so the two can never silently drift again."
  [{:keys [status passed? skipped? cannot-run? exception error] :as _record}]
  (cond
    (contains? #{:pass :fail :cannot-run :error} status) status
    cannot-run?          :cannot-run
    skipped?             :cannot-run
    (or exception error) :error
    (true? passed?)      :pass
    (false? passed?)     :fail
    :else                :pass))

(defn aggregate-summary
  "Walk `assertions` (the vector pulled off a `run-variant` result map)
  and produce the aggregated pass/fail/cannot-run counts:

      {:total       <n>
       :passed      <n>
       :failed      <n>
       :cannot-run  <n>
       :skipped     <n>
       :all-passed? <bool>}

  Buckets by each record's unified `:status` (spec/017 §Run result), so
  a `:cannot-run` assertion (a runner refusal — the distinct THIRD
  status) is counted distinctly and NOT folded into
  `:failed`. `:skipped` is the alias count kept for the legacy
  `:rf.assert/skipped` id (re-frame2's runtime doesn't emit it, but the
  slot stays open). `:all-passed?` is true iff `:total > 0 AND :failed = 0
  AND :cannot-run = 0 AND :skipped = 0` — a refusal is NOT all-green.

  Lives here (not `test-mode.pure`) so both the test-mode pane AND the
  sidebar / chrome-level test widget can call one canonical fold
  without a require cycle (sidebar can't require test-mode, which
  would loop back through shell-state). Pure data → data; JVM-testable."
  [assertions]
  (let [items      (or assertions [])
        legacy-skip? (fn [r] (= :rf.assert/skipped (:assertion r)))
        skipped    (count (filter legacy-skip? items))
        active     (remove legacy-skip? items)
        buckets    (frequencies (map record-status active))
        passed     (get buckets :pass 0)
        cannot-run (get buckets :cannot-run 0)
        ;; :fail + :error both count as failures for the headline tally.
        failed     (+ (get buckets :fail 0) (get buckets :error 0))
        total      (count items)]
    {:total       total
     :passed      passed
     :failed      failed
     :cannot-run  cannot-run
     :skipped     skipped
     :all-passed? (and (pos? total) (zero? failed) (zero? cannot-run) (zero? skipped))}))

(defn record-test-run
  "Write the aggregate of a `run-variant` result into `[:tests :runs]`.

  `summary` is the map returned by `aggregate-summary` —
  `{:total :passed :failed :cannot-run :skipped :all-passed?}` — extended
  with optional `:ran-at-ms` / `:elapsed-ms` and the run's unified
  `:status` (so the sidebar dot reflects the run-level verdict,
  including a tape-floor `:fail` or a `:cannot-run` refusal that the
  assertion counts alone might miss).

  Status precedence: an explicit run `:status` wins; otherwise it is
  derived from the counts — zero assertions → `:pending` (grey — ran but
  no signal); all-passed → `:pass`; any `:cannot-run` (and no fail) →
  `:cannot-run`; else → `:fail`."
  [state variant-id summary]
  (let [{:keys [total passed failed cannot-run skipped all-passed?
                ran-at-ms elapsed-ms status]} (or summary {})
        status (cond
                 (contains? #{:pass :fail :cannot-run :error} status)
                 (if (= :error status) :fail status)   ; :error reads as :fail on the dot
                 (zero? (or total 0)) :pending
                 all-passed?          :pass
                 (pos? (or cannot-run 0)) :cannot-run
                 :else                :fail)]
    (assoc-in state [:tests :runs variant-id]
              {:status     status
               :total      (or total 0)
               :passed     (or passed 0)
               :failed     (or failed 0)
               :cannot-run (or cannot-run 0)
               :skipped    (or skipped 0)
               :ran-at-ms  ran-at-ms
               :elapsed-ms elapsed-ms})))

(defn clear-test-run
  "Drop the run record for `variant-id`."
  [state variant-id]
  (update-in state [:tests :runs] dissoc variant-id))

(defn variant-test-status
  "Return the canonical status keyword for `variant-id` (one of
  `test-run-statuses`). Variants with no recorded run read `:pending`.
  Pure data → data; JVM-testable."
  [state variant-id]
  (or (get-in state [:tests :runs variant-id :status])
      :pending))

(defn test-summary
  "Aggregate the chrome-level test widget's headline counts across the
  given seq of variant-ids — the variants tagged `:test` registered at
  the time of call. Returns:

      {:total      <count of variant-ids>
       :passed     <count whose last run was :pass>
       :failed     <count whose last run was :fail>
       :cannot-run <count whose last run was :cannot-run>
       :running    <count currently in flight>
       :pending    <count with no recorded run>
       :all-green? <bool — total > 0 AND failed = 0 AND cannot-run = 0
                          AND running = 0 AND pending = 0>}

  `:cannot-run` (the unified distinct THIRD status) is counted
  distinctly; a refusal is NOT green. Pure data → data; the JVM
  corpus exercises it against a fixture map without booting Reagent.
  `all-green?` mirrors `aggregate-summary`'s `:all-passed?` — true only
  when every variant has a recorded green run; a sea of `:pending` reads
  as 'not green yet', not 'all green'."
  [state variant-ids]
  (let [runs    (get-in state [:tests :runs])
        ;; Single O(N) frequencies pass — read each variant's status
        ;; once and bucket by keyword. Missing entries default to :pending.
        buckets (frequencies
                  (map (fn [vid] (or (get-in runs [vid :status]) :pending))
                       variant-ids))
        total      (count variant-ids)
        passed     (get buckets :pass       0)
        failed     (get buckets :fail       0)
        cannot-run (get buckets :cannot-run 0)
        running    (get buckets :running    0)
        pending    (get buckets :pending    0)]
    {:total      total
     :passed     passed
     :failed     failed
     :cannot-run cannot-run
     :running    running
     :pending    pending
     :all-green? (and (pos? total)
                      (zero? failed)
                      (zero? cannot-run)
                      (zero? running)
                      (zero? pending))}))

(defn- play-surface-has-steps?
  "True iff `body` declares a non-empty play surface — EITHER a
  `:play-script` (map `:script` or bare-vector form) OR a non-empty
  `:plays` vector (multi-play). A `:plays`-only variant counts as
  testable in the chrome widget + sidebar dots, matching
  `ci-runner/has-any-play?` and `test-mode.pure/variant-has-tests?`."
  [body]
  (let [script (:play-script body)
        plays  (:plays body)]
    (boolean
      (or
        (cond
          (map? script)    (seq (:script script))
          (vector? script) (seq script)
          :else            false)
        (and (vector? plays) (seq plays))))))

(defn testable-variant-ids
  "Return the seq of variant-ids tagged `:test`, in stable (alphabetical)
  order. The chrome widget + sidebar dots key off this seq.

  Variants are testable iff (a) their `:tags` contains `:test`, AND
  (b) they declare a non-empty play surface (`:play-script` OR `:plays`).
  The second filter prunes variants tagged `:test` but
  without any assertions to run — those contribute neither to the
  headline counts nor to the 'Run all' iteration. Pure data → data;
  JVM-testable. `id->body` is the `{variant-id → body}` map from
  `(registrar/registrations :variant)`."
  [id->body]
  (->> id->body
       (filter (fn [[_ body]]
                 (and (contains? (or (:tags body) #{}) :test)
                      (play-surface-has-steps? body))))
       (map first)
       sort
       vec))

;; ---- watch mode ----------------------------------------------------------
;;
;; Storybook 9 ships a Vitest-addon watch-mode toggle (eye icon) that
;; re-runs the changed stories on file save. Story's parity surface is
;; this: an opt-in toggle on the chrome-level test widget that
;; subscribes to per-variant snapshot-identity drift and re-fires
;; `run-variant` for the variants whose identity changed. The detection
;; signal is the variant's snapshot-identity content-hash
;; (re-frame.story.identity/snapshot-identity); a delta against the
;; recorded [:tests :content-hashes] slot triggers the re-run.

(defn set-test-watch-mode
  "Toggle/set the chrome-level watch-mode flag. When `on?` is true the
  shell auto-re-runs testable variants whose snapshot identity drifts;
  when false the toggle is off and the recorded hashes are cleared (the
  next toggle-on seeds them fresh from the current registry). Pure data
  → data; JVM-testable."
  [state on?]
  (if on?
    (assoc-in state [:tests :watch-mode?] true)
    (update state :tests assoc
            :watch-mode?    false
            :content-hashes {})))

(defn test-watch-mode?
  "Return `true` iff watch mode is currently on. Pure."
  [state]
  (boolean (get-in state [:tests :watch-mode?])))

(defn record-test-content-hashes
  "Stamp the current snapshot-identity content hashes for every testable
  variant. `id->hash` is `{variant-id → hex-string}`. The detector
  reads this slot on the next tick to decide which variants drifted."
  [state id->hash]
  (assoc-in state [:tests :content-hashes] (or id->hash {})))

(defn watch-mode-drift
  "Pure data → data: given the previous `[:tests :content-hashes]` map and a
  freshly-computed `current` `{variant-id → hex}` map, return the
  ordered vector of variant-ids whose hash differs from `prev` (i.e.
  the variants the watch-mode detector should re-run on this tick).

  Variants present in `current` but absent from `prev` are treated as
  drifted — the seed call to `record-test-content-hashes` happens on
  toggle-on so a missing prev entry signals a fresh registration that
  the user wants exercised. Variants present in `prev` but absent from
  `current` (deregistered) are silently dropped — there's nothing to
  re-run. JVM-testable."
  [prev current]
  (let [prev    (or prev {})
        current (or current {})]
    (->> current
         (filter (fn [[vid hex]] (not= hex (get prev vid))))
         (map first)
         sort
         vec)))
