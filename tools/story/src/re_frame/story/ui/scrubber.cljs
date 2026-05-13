(ns re-frame.story.ui.scrubber
  "Time-travel scrubber. Per Stage 4 (rf2-ekai) IMPL-SPEC §9.1.

  Reads the variant frame's `epoch-history` (implementation/epoch/) and
  exposes a slider that scrubs forward/back through epochs. Releasing
  the slider triggers `restore-epoch` against the frame.

  Pinned snapshots: clicking 'capture' emits a labelled marker into the
  shell state's `:pinned-snapshots` slot — the markers are rendered as
  chips alongside the scrubber and clicking a chip restores to its
  epoch.

  ## Listener wiring

  The scrubber registers an epoch listener via
  `re-frame.epoch/register-epoch-cb!` so the slider knows to redraw when
  new epochs commit. The listener is keyed by variant id and torn down
  when the shell unmounts.

  ## Cross-reference to the trace panel (rf2-sxwvf)

  Per `spec/012-Trace-Scrubber-Cross-Ref.md` (rf2-sxwvf), the scrubber's
  current selection is exported as a shared per-variant ratom
  (`selections`) keyed by variant-id. The trace panel derefs this slot
  to filter / highlight the cascade view. The selection is held as a
  stable `:epoch-id` (NOT the slider's index) so a history-shift that
  evicts old epochs doesn't silently re-point the selection at the
  wrong record. Nil selection (the default) means 'no scrub in flight'
  — the trace panel shows the full buffer.

  ## Elision

  Epoch history itself sits inside `interop/debug-enabled?` (spec/009);
  the scrubber's mount call is additionally gated by `enabled?`. In a
  production CLJS build both flags are false, and the scrubber's `panel`
  fn is unreachable from any call site (shell entry is gated)."
  (:require [reagent.core :as r]
            [re-frame.epoch :as epoch]
            [re-frame.story.config :as config]
            [re-frame.story.ui.scrubber-xref :as xref]
            [re-frame.story.ui.state :as state]))

;; ---- per-variant state ---------------------------------------------------
;;
;; Two pieces of per-variant UI state live in one registry:
;;
;;   :history    — r/atom holding the latest epoch-history vector,
;;                 refreshed by the epoch-cb (every settled epoch).
;;
;;   :selection  — r/atom holding the current selected :epoch-id (or
;;                 nil). Per rf2-sxwvf the trace panel derefs this to
;;                 filter / highlight the cascade view. We hold the
;;                 stable :epoch-id (NOT the slider's slot index) so a
;;                 history-shift evicting older epochs doesn't silently
;;                 re-point the selection. nil ⇒ no scrub in flight,
;;                 trace panel renders the full buffer.
;;
;; Both slots are keyed by variant-id and torn down together on shell
;; unmount — one registry, one teardown.

(defonce per-variant-state
  ;; {variant-id → {:history <r/atom epoch-history-vec>
  ;;                :selection <r/atom :epoch-id-or-nil>}}
  (atom {}))

(defn- ensure-slot!
  "Return the per-variant ratom for `slot-key` (`:history` or
  `:selection`), creating it on first access via `init-fn` (a 0-arity
  fn returning the initial value)."
  [variant-id slot-key init-fn]
  (or (get-in @per-variant-state [variant-id slot-key])
      (let [a (r/atom (init-fn))]
        (swap! per-variant-state assoc-in [variant-id slot-key] a)
        a)))

(defn- ensure-history-atom!
  [variant-id]
  (ensure-slot! variant-id :history #(epoch/epoch-history variant-id)))

(defn- refresh-history!
  "Re-read the framework's epoch history into the local ratom."
  [variant-id]
  (let [a (ensure-history-atom! variant-id)]
    (reset! a (epoch/epoch-history variant-id))))

(defn drop-history!
  "Remove the per-variant history ratom. Called from shell unmount."
  [variant-id]
  (swap! per-variant-state update variant-id dissoc :history)
  nil)

(defn ensure-selection-atom!
  "Return the per-variant `selection` ratom, creating it on first
  access. Public so the trace panel can deref the same instance the
  scrubber commits to (a fresh `r/atom` on each render would break
  Reagent reactivity)."
  [variant-id]
  (ensure-slot! variant-id :selection (constantly nil)))

(defn selected-epoch-id
  "Return the currently-scrubbed `:epoch-id` for `variant-id`, or nil.
  Public read surface for the trace panel's cross-reference path."
  [variant-id]
  (some-> (get-in @per-variant-state [variant-id :selection]) deref))

(defn select-epoch!
  "Set the scrubber's selection for `variant-id`. Pass nil to clear."
  [variant-id epoch-id]
  (reset! (ensure-selection-atom! variant-id) epoch-id)
  nil)

(defn drop-selection!
  "Remove the per-variant selection ratom. Called from shell unmount."
  [variant-id]
  (swap! per-variant-state update variant-id dissoc :selection)
  nil)

(defn cascade-id-for-epoch
  "Return the cascade `:dispatch-id` that produced `epoch-id` in
  `variant-id`'s history, or nil if the epoch is gone or carried no
  dispatch-id-bearing events (e.g. synthetic epochs from
  `reset-frame-db!`). Thin wrapper around the pure-data
  `xref/cascade-id-for-epoch` so the resolution logic is shared with
  the JVM unit tests."
  [variant-id epoch-id]
  (xref/cascade-id-for-epoch (epoch/epoch-history variant-id) epoch-id))

(defn max-trace-event-id-for-epoch
  "Return the maximum trace-event `:id` recorded for `epoch-id` —
  the pivot the trace panel uses to filter out cascades emitted AFTER
  the selected epoch settled. Thin wrapper around the pure-data
  `xref/max-trace-event-id-for-epoch`."
  [variant-id epoch-id]
  (xref/max-trace-event-id-for-epoch (epoch/epoch-history variant-id) epoch-id))

;; ---- the epoch callback --------------------------------------------------

(defn- cb-id [variant-id]
  (keyword "re-frame.story.ui.scrubber"
           (str "epoch-cb-" (when variant-id (str variant-id)))))

(defn register-listener!
  "Install an epoch-cb that refreshes the local ratom on every settled
  epoch for the variant. Idempotent."
  [variant-id]
  (when config/enabled?
    (epoch/register-epoch-cb! (cb-id variant-id)
      (fn [record]
        (when (= variant-id (:frame record))
          (refresh-history! variant-id))))))

(defn remove-listener!
  "Tear down the epoch listener for `variant-id`. Idempotent."
  [variant-id]
  (when config/enabled?
    (epoch/remove-epoch-cb! (cb-id variant-id))
    nil))

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:panel       {:padding "8px"
                 :font-family "monospace"
                 :font-size "11px"
                 :border-top "1px solid #444"
                 :background "#252526"
                 :color "#ddd"}
   :title       {:font-weight "bold"
                 :margin-bottom "6px"
                 :color "#9cdcfe"}
   :slider-row  {:display "flex"
                 :align-items "center"
                 :gap "8px"}
   :slider      {:flex "1"}
   :label       {:min-width "80px"
                 :color "#b0b0b0"}
   :chip-row    {:display "flex"
                 :flex-wrap "wrap"
                 :gap "4px"
                 :margin-top "6px"}
   :chip        {:padding "2px 6px"
                 :background "#37373d"
                 :color "#9cdcfe"
                 :border-radius "10px"
                 :cursor "pointer"
                 :font-size "10px"}
   :button      {:padding "2px 8px"
                 :background "#0e639c"
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"}
   :release     {:padding "2px 8px"
                 :background "#5a5a5a"
                 :color "white"
                 :border "none"
                 :border-radius "3px"
                 :cursor "pointer"
                 :font-size "10px"
                 :margin-left "6px"}
   :empty       {:color "#9a9a9a" :font-style "italic"}})

;; ---- public component ----------------------------------------------------

(defn- snapshot-label
  "Build a default label for a pinned snapshot — uses the epoch's
  trigger event when available."
  [history idx]
  (let [record (get history idx)
        ev     (:trigger-event record)]
    (str "epoch-" idx (when ev (str " " (pr-str (first ev)))))))

(defn panel
  "The scrubber component. Renders a slider over the variant frame's
  epoch history, a 'capture' button that pins the current epoch, and a
  chip row of pinned snapshots.

  On commit (slider release or pinned-chip click) the selected
  `:epoch-id` is published to the per-variant `selections` ratom so the
  trace panel can filter+highlight against it (rf2-sxwvf). Clicking
  'release' clears the selection (trace panel re-shows the full
  buffer)."
  [variant-id]
  (let [history-atom   (ensure-history-atom! variant-id)
        selection-atom (ensure-selection-atom! variant-id)
        ;; Local UI state for the slider position — separate from the
        ;; framework's epoch history so the user can scrub without
        ;; committing until they release.
        slider-pos     (r/atom nil)]
    (fn [variant-id]
      (let [history   (or @history-atom [])
            n         (count history)
            shell     @state/shell-state-atom
            pinned    (get-in shell [:pinned-snapshots variant-id] [])
            selection @selection-atom
            pos       (or @slider-pos (max 0 (dec n)))]
        [:div {:style (:panel styles)
               :data-test "story-scrubber"}
         [:div {:style (:title styles)}
          "Time travel " (when variant-id (str (pr-str variant-id)))
          " — " n " epochs"]
         (if (zero? n)
           [:div {:style (:empty styles)}
            "no epochs recorded yet — dispatch an event to capture an epoch"]
           [:div
            [:div {:style (:slider-row styles)}
             [:span {:style (:label styles)} (str "epoch " pos "/" (dec n))]
             [:input {:type      "range"
                      :min       0
                      :max       (max 0 (dec n))
                      :value     pos
                      :style     (:slider styles)
                      :data-test "story-scrubber-slider"
                      :on-change (fn [e]
                                   (reset! slider-pos
                                           (js/parseInt
                                             (.. e -target -value))))
                      :on-mouse-up
                                 (fn [e]
                                   (let [idx (js/parseInt
                                               (.. e -target -value))]
                                     (when-let [record (get history idx)]
                                       (epoch/restore-epoch
                                         variant-id
                                         (:epoch-id record))
                                       ;; rf2-sxwvf: publish the selection
                                       ;; so the trace panel filters /
                                       ;; highlights against it.
                                       (reset! selection-atom
                                               (:epoch-id record)))))}]
             [:button {:style    (:button styles)
                       :on-click (fn [_]
                                   (let [record (get history pos)
                                         label  (snapshot-label history pos)]
                                     (when record
                                       (state/swap-state!
                                         state/pin-snapshot
                                         variant-id label
                                         (:epoch-id record)))))}
              "capture"]
             ;; rf2-sxwvf: 'release' clears the scrub-selection so the
             ;; trace panel reverts to showing the full buffer. Only
             ;; rendered when there IS a selection to clear, so the
             ;; panel's visual weight is unchanged in the default
             ;; (no-scrub) state.
             (when selection
               [:button {:style    (:release styles)
                         :data-test "story-scrubber-release"
                         :on-click (fn [_]
                                     (reset! selection-atom nil))}
                "release"])]
            (when (seq pinned)
              [:div {:style (:chip-row styles)}
               (for [{:keys [label epoch-id]} pinned]
                 ^{:key (str label "-" epoch-id)}
                 [:span {:style    (:chip styles)
                         :on-click (fn [_]
                                     (epoch/restore-epoch variant-id epoch-id)
                                     ;; rf2-sxwvf: pinned-chip restore
                                     ;; also publishes selection so
                                     ;; trace cross-references.
                                     (reset! selection-atom epoch-id))}
                  label])])])]))))
