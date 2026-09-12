(ns re-frame.story.ui.test-mode.state
  "CLJS-side local state for the `:test` mode pane (spec/009).

  The pane keeps its own ratom — one map keyed by variant-id.
  Each entry carries:

      {:result          <unified-run-result-map>
       :ran-at-ms       <epoch-ms>
       :running?        <bool>
       :expanded        #{<row-key>}      ; expanded assertion rows
       :expanded-checks #{<check-id>}     ; expanded check groups
       :failed-only?    <bool>            ; failed-only filter
       :play-events     <vector>          ; dispatch events of the compiled program, run opts threaded
       :epoch-ids       <vector>          ; trailing epoch-id slice
       :selected-step   <int|nil>}

  `:result` is the ONE unified run-result the runtime returns
  (`re-frame.story.result/run-result` merged with the lifecycle slots):
  a top-level `:status`, `:checks`, `:schema-violations`,
  `:cannot-run` refusals, `:runner` / `:required-runner`, plus the
  `:assertions` records each stamped with their own `:status`. The pane
  reads it through `re-frame.story.ui.test-mode.pure`'s projection helpers.

  Re-run flips `:running?` on, calls `rf.story.runtime/reset-variant`, swaps the
  result in on resolve.

  `:play-events`, `:epoch-ids` + `:selected-step` are the step-through
  scrubber slots. `:selected-step` is the slider position
  (a slot index into `:epoch-ids`); `nil` means 'no scrub in flight'
  — the canvas shows the post-play app-db, the same value the user
  sees on a fresh run. A non-nil selection has called `restore-epoch`
  against the variant frame, so the canvas re-renders against the
  app-db value at that step.

  Companion namespaces:

  - `re-frame.story.ui.test-mode.pure`  — JVM-testable pure helpers.
  - `re-frame.story.ui.test-mode.view`  — styles, section renderers,
    and the top-level `test-view` component (the sole consumer of the
    helpers below)."
  (:require [reagent.core                 :as r]
            [re-frame.core                :as rf]
            [re-frame.interop             :as rf.interop]
            [re-frame.story.async         :as rf.story.async]
            [re-frame.story.play          :as rf.story.play]
            [re-frame.story.runtime       :as rf.story.runtime]
            [re-frame.story.ui.state      :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.pure :as rf.story.ui.test-mode.pure]))

;; ---- ratom ---------------------------------------------------------------
;;
;; The view derefs this directly inside its Reagent render closures so
;; any slot change re-renders the pane. Exposed (non-private) for that
;; reason — the view is the only consumer outside this ns.

(defonce results-atom (r/atom {}))

(defn run-opts
  "The `run-variant` opts the `:test` pane runs `variant-id` with, read from
  shell state: the chrome-wide `:active-modes` and `:substrate`, and the
  variant's OWN `:cell-overrides` entry — the slots the canvas's `run-key`
  reads, so the pane runs against the effective args the user has been
  editing in the controls panel.

  Re-run, the scrubber and the step-debugger all take their opts here, so
  all three compile ONE program (rf2-ad25). A script `[:arg]` fed by a mode
  or an override would otherwise be scrubbed or stepped as a different
  program from the one Re-run executed."
  [variant-id]
  (let [shell @rf.story.ui.state/shell-state-atom]
    {:active-modes   (:active-modes shell)
     :cell-overrides (get-in shell [:cell-overrides variant-id])
     :substrate      (:substrate shell)}))

(defn- begin-run!
  "Mark the variant's slot as running. Returns nothing. Stamps the
  shell-state `[:tests :runs]` slot too so the chrome-level test widget
  and the sidebar's per-variant dot read `:running` while the run
  is in flight."
  [variant-id]
  (swap! results-atom assoc-in [variant-id :running?] true)
  (rf.story.ui.state/swap-state! rf.story.ui.state/mark-test-running variant-id))

(defn- store-result!
  "Swap a fresh `result` (from `run-variant`) into the variant's
  slot, clear `:running?`, stamp `:ran-at-ms` with the local clock,
  and reset the per-row expanded set so a fresh failure detail
  starts collapsed.

  Captures the play-events vector + the trailing epoch-id slice
  against the same atom so the step-through scrubber has a stable
  read-surface that doesn't drift on a later unrelated dispatch.

  Folds the run's aggregate into the shell-state `[:tests :runs]` slot
  too — the chrome-level test widget + sidebar dots read off that
  slot.

  `opts` is the `run-variant` opts map the run received (`run-opts`)."
  [variant-id opts result]
  (let [now          (rf.interop/now-ms)
        ;; `rf.story.play/variant-play-events` extracts the compiled
        ;; program's flat event-vec list (one per `:dispatch` /
        ;; `:dispatch-sync` step), the shape the scrubber's slot expects.
        ;; Compiled against the run's OWN opts: without them an `[:arg]` a
        ;; mode or cell override supplied differs from the event the run
        ;; dispatched, and matches nothing on the tape below (rf2-ad25).
        play-events  (rf.story.play/variant-play-events variant-id opts)
        ;; rf2-4e545l finding 4: match against THIS run's own scoped
        ;; `:epoch-tape` (the result's raw `:rf/epoch-record` vector,
        ;; each carrying its `:trigger-event`) by trigger-event identity
        ;; rather than a positional trailing-N slice of the frame's WHOLE
        ;; epoch-history — a mixed :click+:dispatch script where the
        ;; :click ALSO commits an epoch (its DOM handler dispatches)
        ;; would otherwise misalign a positional slice. See
        ;; `rf.story.ui.test-mode.pure/epoch-id-slice`'s docstring.
        epoch-ids    (rf.story.ui.test-mode.pure/epoch-id-slice (:epoch-tape result) play-events)]
    (swap! results-atom assoc variant-id
           {:result        result
            :ran-at-ms     now
            :running?      false
            :expanded      #{}
            :play-events   (vec play-events)
            :epoch-ids     epoch-ids
            :selected-step nil})
    (let [summary (-> (rf.story.ui.state/aggregate-summary (:assertions result))
                      (assoc :ran-at-ms  now
                             :elapsed-ms (:elapsed-ms result)
                             ;; thread the unified run-level `:status` so
                             ;; the sidebar dot reflects a tape-floor
                             ;; `:fail` / `:cannot-run` refusal the
                             ;; assertion counts alone might miss.
                             :status     (:status result)))]
      (rf.story.ui.state/swap-state! rf.story.ui.state/record-test-run variant-id summary))))

(defn select-step!
  "Set the step-through scrubber's `:selected-step` for `variant-id`
  and call `restore-epoch` against the variant frame so the canvas
  re-renders against the app-db at that step.

  `idx` is a 0-based index into the variant's `:epoch-ids` vector.
  Pass nil to release — the canvas reverts to the post-play app-db
  (we restore against the last epoch-id in the slice, which is the
  play-sequence's terminal state).

  No-ops while the slot's `:running?` is true. A
  scrubber-tick during an in-flight `reset-variant` would race
  `store-result!`: the restore would land against the frame being
  reset, the new `:epoch-ids` would overwrite the slice, and
  `:selected-step` would silently index a different epoch (or no
  epoch at all). Better to drop the click than to corrupt the
  scrubber state — the slot's epoch-ids may change shape under
  it on resolve."
  [variant-id idx]
  (let [s          (get @results-atom variant-id)
        epoch-ids  (or (:epoch-ids s) [])
        target-id  (cond
                     (and (integer? idx)
                          (<= 0 idx)
                          (< idx (count epoch-ids)))
                     (nth epoch-ids idx)

                     ;; release → restore terminal state
                     (and (nil? idx) (seq epoch-ids))
                     (peek epoch-ids)

                     :else nil)]
    (when-not (:running? s)
      (swap! results-atom assoc-in [variant-id :selected-step] idx)
      (when target-id
        (rf/restore-epoch! variant-id target-id)))))

(defn toggle-expanded!
  "Toggle the expand state of an assertion row, keyed by stable
  identity (`row-key`, derived from `assertion-row :label`) rather
  than positional index. A re-run that reorders or inserts assertions
  would otherwise open the wrong row."
  [variant-id row-key]
  (swap! results-atom update-in [variant-id :expanded]
         (fn [s] (let [s (or s #{})]
                   (if (contains? s row-key)
                     (disj s row-key)
                     (conj s row-key))))))

(defn toggle-check!
  "Toggle the expand state of a check group, keyed by check id.
  Mirrors `toggle-expanded!` for the per-test rows."
  [variant-id check-id]
  (swap! results-atom update-in [variant-id :expanded-checks]
         (fn [s] (let [s (or s #{})]
                   (if (contains? s check-id)
                     (disj s check-id)
                     (conj s check-id))))))

(defn set-failed-only!
  "Set the failed-only filter flag for `variant-id`'s slot
  (spec/021 §1 — failed-only filtering). `on?` truthy hides passing
  rows; falsey shows every row."
  [variant-id on?]
  (swap! results-atom assoc-in [variant-id :failed-only?] (boolean on?)))

(defn run-variant-pane!
  "Drive a fresh `reset-variant` against the variant's frame and
  swap the result into local state when it resolves. No-ops if
  the slot already carries `:running?`.

  The run's opts come from `run-opts` — the variant's OWN cell-overrides
  entry plus the active modes and substrate — so the test pane re-runs
  against the same effective-args the user has been editing in the
  controls panel, and the scrubber compiles its events against those
  same opts."
  [variant-id]
  (let [opts (run-opts variant-id)]
    (begin-run! variant-id)
    (-> (rf.story.runtime/reset-variant variant-id opts)
        (rf.story.async/then  (fn [r] (store-result! variant-id opts r) nil))
        (rf.story.async/catch* (fn [_]
                        ;; Even a rejection clears :running? so the
                        ;; UI button comes back to "Re-run". Drop
                        ;; the shell-state running stamp too — the
                        ;; widget/dot should not stay yellow on a
                        ;; rejection.
                        (swap! results-atom assoc-in
                               [variant-id :running?] false)
                        (rf.story.ui.state/swap-state! rf.story.ui.state/clear-test-run variant-id)
                        nil)))))
