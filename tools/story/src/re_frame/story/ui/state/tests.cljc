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
  - `variant-body-has-tests?`   — the ONE 'has tests' predicate (a play
                                  surface, a declarative `:assertions` /
                                  `:checks`, a `:script` received through a
                                  composed fragment, or `:checks` received
                                  through `:extends` / `:compose`).
  - `testable-variant-ids`      — derive the seq of `:test`-tagged
                                  variants that have tests.
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
  keep working."
  (:require [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.verdict   :as rf.story.verdict]))

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
        buckets    (frequencies (map rf.story.verdict/record-status active))
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
                 (contains? rf.story.verdict/statuses status)
                 ;; This slot carries the FIVE dot-facing run statuses
                 ;; (`test-run-statuses` — `:cannot-run` is the distinct
                 ;; third). A run-level `:error` verdict lowers to `:fail`
                 ;; HERE, at write time — the one place the four-verdict
                 ;; vocabulary (spec/017, `rf.story.verdict/statuses`) narrows; both
                 ;; demand attention and wear the danger tint. The
                 ;; per-assertion `:error` detail stays distinct in the
                 ;; test-mode pane's result rows (test-mode.pure), per
                 ;; spec/018 §12.6.
                 (if (= :error status) :fail status)
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

(defn- non-empty-vector? [x]
  (and (vector? x) (seq x)))

(defn- non-empty-script?
  "A non-empty `:script` slot value, in either `PlaySpec` form: the bare
  vector, or the map carrying its steps under `:script`."
  [script]
  (cond
    (map? script)    (seq (:script script))
    (vector? script) (seq script)
    :else            false))

(defn- own-tests?
  "The body's OWN slots: a non-empty `:script` (map or bare-vector form) or
  `:plays`, or a non-empty `:assertions` / `:checks` vector."
  [body]
  (or (non-empty-script? (:script body))
      (non-empty-vector? (:plays body))
      (non-empty-vector? (:assertions body))
      (non-empty-vector? (:checks body))))

(defn- composed-script?
  "True iff a `:compose` id names a registered fragment whose own `:script`
  is non-empty. The plan compiler prepends that script onto the primary
  play, or synthesizes one (spec/017 §Total merge order, rf2-k23efg), so it
  runs like the variant's own. One lookup per compose id; `:compose` is
  child-only, so no ancestor is walked."
  [body]
  (some #(non-empty-script? (:script (rf.story.registrar/handler-meta :fragment %)))
        (:compose body)))

(defn- received-checks?
  "True iff `body` receives `:checks` it does not declare, by the two routes
  the plan compiler merges into `[:expect :checks]` (spec/017 §Parent
  chain): a `:compose` id naming a registered check, or an `:extends`
  ancestor whose OWN `:checks` is non-empty. `:compose` is child-only, so an
  ancestor's composed checks do not reach the child. One lookup per compose
  id and per ancestor; an unknown parent or a cycle ends the walk (the
  compiler refuses both with its own error)."
  [body id->body]
  (or (some #(rf.story.registrar/registered? :check %) (:compose body))
      (loop [pid (:extends body) seen #{}]
        (when (and pid (not (contains? seen pid)))
          (let [parent (get id->body pid)]
            (or (non-empty-vector? (:checks parent))
                (recur (:extends parent) (conj seen pid))))))))

(defn variant-body-has-tests?
  "True iff a variant `body` has something a run judges:

  - a play surface — a non-empty `:script` or `:plays`; OR
  - a declarative expectation of its own — a non-empty `:assertions` or
    `:checks` vector (rf2-uiihg); OR
  - a non-empty `:script` it receives through a `:compose` of a fragment,
    which the compiler folds into the primary play (rf2-dt9xf); OR
  - `:checks` it receives from an `:extends` ancestor or through a
    `:compose` of a check id (rf2-ckpm4).

  spec/017 lowers `:assertions` / `:checks` into `[:expect …]`, merging the
  inherited and composed check ids in, and `run-variant` evaluates them
  against the final settled state whether or not a script ran, so each is
  a real test. This is the ONE 'has tests' predicate:
  `testable-variant-ids` (chrome widget, sidebar dots, Run all, watch
  mode) and `test-mode.pure/variant-has-tests?` (the Tests pane) both call
  it, so they cannot disagree.

  Never compiles a plan, so the sidebar hot path (rf2-dtj61) stays a
  predicate: the own slots first, then lookups per `:compose` id and per
  `:extends` ancestor. `id->body` resolves the chain; the 1-arity reads the
  registered variants."
  ([body]
   (variant-body-has-tests? body (rf.story.registrar/registrations :variant)))
  ([body id->body]
   (boolean (or (own-tests? body)
                (composed-script? body)
                (received-checks? body id->body)))))

(defn testable-variant-ids
  "Return the seq of variant-ids tagged `:test`, in stable (alphabetical)
  order. The chrome widget + sidebar dots key off this seq.

  Variants are testable iff (a) their `:tags` contains `:test`, AND
  (b) `variant-body-has-tests?` holds for the body. The second filter
  prunes variants tagged `:test` but with nothing to run — those
  contribute neither to the headline counts nor to the 'Run all'
  iteration. JVM-testable. `id->body` is the
  `{variant-id → body}` map from `(registrar/registrations :variant)` (the
  sidebar passes `registry-snapshot`'s tag-resolved `:variants`); the
  `:extends` chain resolves within it."
  [id->body]
  (->> id->body
       (filter (fn [[_ body]]
                 (and (contains? (or (:tags body) #{}) :test)
                      (variant-body-has-tests? body id->body))))
       (map first)
       sort
       vec))

;; ---- watch mode ----------------------------------------------------------
;;
;; Storybook 9 ships a Vitest-addon watch-mode toggle (eye icon) that
;; re-runs the changed stories on file save. Story's parity surface is
;; this: an opt-in toggle on the chrome-level test widget that
;; subscribes to per-variant drift and re-fires `run-variant` for the
;; variants that changed. The detection signal is the variant's watch hash
;; (re-frame.story.ui.watch — its snapshot-identity content-hash plus the
;; slots that decide what a run judges); a delta against the recorded
;; [:tests :content-hashes] slot triggers the re-run.

(defn set-test-watch-mode
  "Toggle/set the chrome-level watch-mode flag. When `on?` is true the
  shell auto-re-runs testable variants whose watch hash drifts;
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
