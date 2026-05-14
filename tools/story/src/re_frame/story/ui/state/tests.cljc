(ns re-frame.story.ui.state.tests
  "Pure test-run aggregation + watch-mode helpers for the shell state
  map. Split from `re-frame.story.ui.state` per rf2-gcpon (leaf-size
  ceiling rf2-zkca8 — the parent ns was 718L).

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
                                  variants with a non-empty `:play`.
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

;; ---- test-runs (rf2-q0irb) -----------------------------------------------
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
  "Canonical run-state ids, in render order.

  - `:pass`     last run: every assertion passed (and at least one assertion).
  - `:fail`     last run: ≥1 assertion failed.
  - `:running`  run currently in flight.
  - `:pending`  no run recorded yet (or run produced zero assertions)."
  [:pass :fail :running :pending])

(defn mark-test-running
  "Stamp `variant-id` as :running. Idempotent."
  [state variant-id]
  (assoc-in state [:tests :runs variant-id] {:status :running}))

(defn aggregate-summary
  "Walk `assertions` (the vector pulled off a `run-variant` result map)
  and produce the aggregated pass/fail/skip counts:

      {:total       <n>
       :passed      <n>
       :failed      <n>
       :skipped     <n>
       :all-passed? <bool>}

  `:skipped` counts records carrying `:assertion :rf.assert/skipped` —
  re-frame2's v1 runtime doesn't emit this id, but the slot stays open
  so spec/004 additions flow through without a pane refactor.
  `:all-passed?` is true iff `:total > 0 AND :failed = 0 AND :skipped = 0`.

  Lives here (not `test-mode.pure`) so both the test-mode pane AND the
  sidebar / chrome-level test widget can call one canonical fold
  without a require cycle (sidebar can't require test-mode, which
  would loop back through shell-state). Pure data → data; JVM-testable."
  [assertions]
  (let [items     (or assertions [])
        skipped?  (fn [r] (= :rf.assert/skipped (:assertion r)))
        skipped   (count (filter skipped? items))
        active    (remove skipped? items)
        passed    (count (filter :passed? active))
        failed    (- (count active) passed)
        total     (count items)]
    {:total       total
     :passed      passed
     :failed      failed
     :skipped     skipped
     :all-passed? (and (pos? total) (zero? failed) (zero? skipped))}))

(defn record-test-run
  "Write the aggregate of a `run-variant` result into `[:tests :runs]`.

  `summary` is the map returned by `aggregate-summary` —
  `{:total :passed :failed :skipped :all-passed?}` — extended with
  optional `:ran-at-ms` and `:elapsed-ms`. A run that recorded zero
  assertions lands as `:pending` (rather than `:pass`/`:fail`) so the
  sidebar dot reads grey — the variant ran but produced no signal."
  [state variant-id summary]
  (let [{:keys [total passed failed skipped all-passed?
                ran-at-ms elapsed-ms]} (or summary {})
        status (cond
                 (zero? (or total 0)) :pending
                 all-passed?          :pass
                 :else                :fail)]
    (assoc-in state [:tests :runs variant-id]
              {:status     status
               :total      (or total 0)
               :passed     (or passed 0)
               :failed     (or failed 0)
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
       :running    <count currently in flight>
       :pending    <count with no recorded run>
       :all-green? <bool — total > 0 AND failed = 0 AND running = 0
                          AND pending = 0>}

  Pure data → data; the JVM corpus exercises it against a fixture map
  without booting Reagent. `all-green?` mirrors `aggregate-summary`'s
  `:all-passed?` — true only when every variant has a recorded green
  run; a sea of `:pending` reads as 'not green yet', not 'all green'."
  [state variant-ids]
  (let [runs    (get-in state [:tests :runs])
        ;; Single O(N) frequencies pass — read each variant's status
        ;; once and bucket by keyword. Missing entries default to :pending.
        buckets (frequencies
                  (map (fn [vid] (or (get-in runs [vid :status]) :pending))
                       variant-ids))
        total   (count variant-ids)
        passed  (get buckets :pass    0)
        failed  (get buckets :fail    0)
        running (get buckets :running 0)
        pending (get buckets :pending 0)]
    {:total      total
     :passed     passed
     :failed     failed
     :running    running
     :pending    pending
     :all-green? (and (pos? total)
                      (zero? failed)
                      (zero? running)
                      (zero? pending))}))

(defn testable-variant-ids
  "Return the seq of variant-ids tagged `:test`, in stable (alphabetical)
  order. The chrome widget + sidebar dots key off this seq.

  Variants are testable iff (a) their `:tags` contains `:test`, AND
  (b) they declare a non-empty `:play` slot. The second filter prunes
  variants tagged `:test` but without any assertions to run — those
  contribute neither to the headline counts nor to the 'Run all'
  iteration. Pure data → data; JVM-testable. `id->body` is the
  `{variant-id → body}` map from `(registrar/registrations :variant)`."
  [id->body]
  (->> id->body
       (filter (fn [[_ body]]
                 (and (contains? (or (:tags body) #{}) :test)
                      (seq (or (:play body) [])))))
       (map first)
       sort
       vec))

;; ---- watch mode (rf2-z1h0f) ---------------------------------------------
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
