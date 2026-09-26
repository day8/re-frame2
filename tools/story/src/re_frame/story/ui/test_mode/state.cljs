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
       :epoch-ids       <vector>          ; epoch-id per play event (`epoch-id-slice`)
       :run-opts        <map>             ; the `run-opts` the run received, for promotion capture
       :selected-step   <int|nil>}

  `:result` is the ONE unified run-result the runtime returns
  (`re-frame.story.result/run-result` merged with the lifecycle slots):
  a top-level `:status`, `:checks`, `:schema-violations`,
  `:cannot-run` refusals, `:runner` / `:required-runner`, plus the
  `:assertions` records each stamped with their own `:status`. The pane
  reads it through `re-frame.story.ui.test-mode.pure`'s projection helpers.

  The pane runs nothing of its own. The canvas it renders owns the
  variant's run, and every run reaches the slot through the one run owner:
  `follow-run!` listens (`rf.story.runtime/listen-runs!`) and stores each
  settled run of the variant the pane shows — the canvas's own, a Re-run, a
  play-chip Re-run. Re-run (`run-variant-pane!`) re-prepares the frame IN
  PLACE through that owner and resumes it, so the canvas keeps its frame and
  its view while the script runs against it.

  `:play-events`, `:epoch-ids` + `:selected-step` are the step-through
  scrubber slots. `:selected-step` is the slider position
  (a slot index into `:epoch-ids`); `nil` means 'no scrub in flight'
  — the canvas shows the post-play app-db, the same value the user
  sees on a fresh run. A non-nil selection has called `restore-epoch`
  against the variant frame, so the canvas re-renders against the
  app-db value at that step.

  Companion namespaces:

  - `re-frame.story.ui.test-mode.pure`  — JVM-testable pure helpers.
  - `re-frame.story.ui.test-mode.view`  — section renderers,
    and the top-level `test-view` component (the sole consumer of the
    helpers below)."
  (:require [reagent.core                 :as r]
            [re-frame.core                :as rf]
            [re-frame.interop             :as rf.interop]
            [re-frame.story.async         :as rf.story.async]
            [re-frame.story.config        :as rf.story.config]
            [re-frame.story.play          :as rf.story.play]
            [re-frame.story.runtime       :as rf.story.runtime]
            [re-frame.story.ui.canvas     :as rf.story.ui.canvas]
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

  The scrubber and the step-debugger take their opts here, and Re-run's
  `rf.story.ui.canvas/run-opts` carries the same three slots, so all three
  compile ONE program. A script `[:arg]` fed by a mode
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

  Captures the play-events vector + the per-step epoch-id slice
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
        ;; dispatched, and matches nothing on the tape below.
        play-events  (rf.story.play/variant-play-events variant-id opts)
        ;; Match against THIS run's own scoped
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
            ;; Promotion captures THIS run, so it compiles the source with the
            ;; opts the run received, not whatever the controls hold when the
            ;; promote button is pressed.
            :run-opts      opts
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
  scrubber-tick during an in-flight run would race
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

;; ---- following the one run owner -----------------------------------------

;; The variant the pane shows, or last showed. `test-view` writes it
;; (`show-variant!`).
(defonce ^:private pane-variant (atom nil))

(defn show-variant!
  "Record `variant-id` as the variant the pane shows. `follow-run!` stores
  the runs of this variant.

  The pane records it on every change of variant and never clears it on
  unmount. Reagent may dispose a mounted component's `with-let` state and
  build it again on the next render, so a clear on dispose would leave a
  mounted pane following nothing until it re-rendered, and a run settling
  in that gap would be lost. Once the pane unmounts, the variant it last
  showed stays followed, so its slot holds its latest run when the tab
  opens again."
  [variant-id]
  (reset! pane-variant variant-id)
  nil)

(defn- clear-running!
  "Drop `variant-id`'s running stamps, in the pane and in shell state."
  [variant-id]
  (swap! results-atom assoc-in [variant-id :running?] false)
  (rf.story.ui.state/swap-state! rf.story.ui.state/clear-test-run variant-id))

(defn- follow-run!
  "The pane's `rf.story.runtime/listen-runs!` listener. A run of the
  variant the pane shows marks the slot running when its generation is
  prepared; a settled run is stored when it is still the variant's
  current generation (a superseded one is dropped — its successor
  settles), for the variant the pane shows or for a slot a Re-run marked
  running. So the slot follows every run of the variant, whoever started
  it, while runs of variants nobody is looking at leave it alone."
  [variant-id {:keys [generation] :as run}]
  (cond
    (not (contains? run :result))
    (when (= variant-id @pane-variant)
      (begin-run! variant-id))

    (and (= generation (rf.story.runtime/current-generation variant-id))
         (or (= variant-id @pane-variant)
             (get-in @results-atom [variant-id :running?])))
    (store-result! variant-id (run-opts variant-id) (:result run))))

(when rf.story.config/enabled?
  (rf.story.runtime/listen-runs! ::pane follow-run!))

(defn run-variant-pane!
  "Re-run `variant-id` through the one run owner: re-prepare its frame IN
  PLACE to the declared start and resume the auto-plays, with the canvas's
  own opts (`rf.story.ui.canvas/run-opts` — the active modes, the variant's
  cell overrides and the substrate the controls panel has set, the
  canvas's run-key, and `:runner :auto`). The frame is never destroyed, so
  the canvas keeps rendering it and a DOM step runs against its view.
  `follow-run!` stores the settled result into the slot before the
  returned promise resolves.

  Returns the resume's promise of the unified result, or a resolved nil
  when there was nothing to run."
  [variant-id]
  (begin-run! variant-id)
  (let [shell @rf.story.ui.state/shell-state-atom]
    (rf.story.runtime/prepare-run!
      variant-id
      (rf.story.ui.canvas/run-opts (rf.story.ui.canvas/run-key shell variant-id))))
  (if-let [p (rf.story.runtime/resume-run! variant-id)]
    (rf.story.async/catch* p (fn [_]
                               ;; Even a rejection clears :running? so the
                               ;; button comes back to "Re-run" and the
                               ;; widget/dot does not stay yellow.
                               (clear-running! variant-id)
                               nil))
    (do (clear-running! variant-id)
        (rf.story.async/resolved nil))))
