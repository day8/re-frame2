(ns re-frame.story.ui.test-mode.state
  "CLJS-side local state for the `:test` mode pane (rf2-qmjo + spec/009).

  Split out of the legacy `re-frame.story.ui.test-mode` monolith per
  rf2-8n2fz. The pane keeps its own ratom — one map keyed by variant-id.
  Each entry carries:

      {:result        <run-variant-result-map>
       :ran-at-ms     <epoch-ms>
       :running?      <bool>
       :expanded      #{<row-index>}
       :play-events   <vector>     ; flat event-vec list derived from :play-script
       :epoch-ids     <vector>     ; trailing epoch-id slice
       :selected-step <int|nil>}

  Re-run flips `:running?` on, calls `runtime/reset-variant`, swaps the
  result in on resolve.

  `:play-events`, `:epoch-ids` + `:selected-step` are the step-through
  scrubber slots (rf2-lc36w). `:selected-step` is the slider position
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
            [re-frame.epoch               :as epoch]
            [re-frame.interop             :as interop]
            [re-frame.story.async         :as async]
            [re-frame.story.play          :as play]
            [re-frame.story.runtime       :as runtime]
            [re-frame.story.ui.state      :as state]
            [re-frame.story.ui.test-mode.pure :as pure]))

;; ---- ratom ---------------------------------------------------------------
;;
;; The view derefs this directly inside its Reagent render closures so
;; any slot change re-renders the pane. Exposed (non-private) for that
;; reason — the view is the only consumer outside this ns.

(defonce results-atom (r/atom {}))

(defn- begin-run!
  "Mark the variant's slot as running. Returns nothing. Stamps the
  shell-state `[:tests :runs]` slot too so the chrome-level test widget
  and the sidebar's per-variant dot read `:running` while the run
  is in flight (rf2-q0irb)."
  [variant-id]
  (swap! results-atom assoc-in [variant-id :running?] true)
  (state/swap-state! state/mark-test-running variant-id))

(defn- store-result!
  "Swap a fresh `result` (from `run-variant`) into the variant's
  slot, clear `:running?`, stamp `:ran-at-ms` with the local clock,
  and reset the per-row expanded set so a fresh failure detail
  starts collapsed.

  Captures the play-events vector + the trailing epoch-id slice
  against the same atom so the step-through scrubber (rf2-lc36w)
  has a stable read-surface that doesn't drift on a later
  unrelated dispatch.

  Folds the run's aggregate into the shell-state `[:tests :runs]` slot
  too — the chrome-level test widget + sidebar dots read off that
  slot (rf2-q0irb)."
  [variant-id result]
  (let [now          (interop/now-ms)
        ;; Per rf2-0wrud `:play-script` is the canonical AND ONLY
        ;; phase-4 slot. `play/variant-play-events` extracts a flat
        ;; event-vec list (one per `:dispatch`/`:dispatch-sync` step)
        ;; — the same shape the legacy `:play` slot carried, so the
        ;; scrubber's slot-shape stays stable.
        play-events  (play/variant-play-events variant-id)
        history      (epoch/epoch-history variant-id)
        epoch-ids    (pure/epoch-id-slice history (count play-events))]
    (swap! results-atom assoc variant-id
           {:result        result
            :ran-at-ms     now
            :running?      false
            :expanded      #{}
            :play-events   (vec play-events)
            :epoch-ids     epoch-ids
            :selected-step nil})
    (let [summary (-> (state/aggregate-summary (:assertions result))
                      (assoc :ran-at-ms  now
                             :elapsed-ms (:elapsed-ms result)))]
      (state/swap-state! state/record-test-run variant-id summary))))

(defn select-step!
  "Set the step-through scrubber's `:selected-step` for `variant-id`
  and call `restore-epoch` against the variant frame so the canvas
  re-renders against the app-db at that step.

  `idx` is a 0-based index into the variant's `:epoch-ids` vector.
  Pass nil to release — the canvas reverts to the post-play app-db
  (we restore against the last epoch-id in the slice, which is the
  play-sequence's terminal state).

  No-ops while the slot's `:running?` is true (rf2-tistm). A
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
        (epoch/restore-epoch variant-id target-id)))))

(defn toggle-expanded!
  "Toggle the expand state of an assertion row, keyed by stable
  identity (`row-key`, derived from `assertion-row :label`) rather
  than positional index (rf2-tistm). A re-run that reorders or
  inserts assertions would otherwise open the wrong row."
  [variant-id row-key]
  (swap! results-atom update-in [variant-id :expanded]
         (fn [s] (let [s (or s #{})]
                   (if (contains? s row-key)
                     (disj s row-key)
                     (conj s row-key))))))

(defn run-variant-pane!
  "Drive a fresh `reset-variant` against the variant's frame and
  swap the result into local state when it resolves. No-ops if
  the slot already carries `:running?`.

  Per rf2-zq6sn the variant's run threads its OWN cell-overrides
  entry from shell state — same lookup the canvas / sidebar /
  share-url paths perform — so the test pane re-runs against the
  same effective-args the user has been editing in the controls
  panel."
  [variant-id]
  (let [shell @state/shell-state-atom
        opts  {:active-modes   (:active-modes shell)
               :cell-overrides (get-in shell [:cell-overrides variant-id])
               :substrate      (:substrate shell)}]
    (begin-run! variant-id)
    (-> (runtime/reset-variant variant-id opts)
        (async/then  (fn [r] (store-result! variant-id r) nil))
        (async/catch* (fn [_]
                        ;; Even a rejection clears :running? so the
                        ;; UI button comes back to "Re-run". Drop
                        ;; the shell-state running stamp too — the
                        ;; widget/dot should not stay yellow on a
                        ;; rejection (rf2-q0irb).
                        (swap! results-atom assoc-in
                               [variant-id :running?] false)
                        (state/swap-state! state/clear-test-run variant-id)
                        nil)))))
