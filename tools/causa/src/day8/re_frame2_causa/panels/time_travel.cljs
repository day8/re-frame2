(ns day8.re-frame2-causa.panels.time-travel
  "Time Travel scrubber panel (Phase 3, rf2-t53ze).

  Per `tools/causa/spec/002-Time-Travel.md` the panel is the scrubber-
  shaped device for walking through epoch history without disturbing
  the live runtime. The Phase 3 deliverable covers the timeline +
  cursor + pin store + the two confirmed-rewind paths
  (`reset-to-epoch` + `reset-to-pinned`).

  ## Three layers

    1. **The track** — a horizontal range slider over the target
       frame's epoch history (oldest at the left). Dragging the
       cursor fires `:rf.causa/select-epoch` (passive — per spec
       §The passive-scrubbing rule; the runtime does not rebase).
       `[` / `]` keyboard nav (arrow-key nudge) steps one slot
       back / forward.

    2. **The pins** — chips alongside the track. Each chip is a
       4-tuple eagerly captured at pin time. Detached chips (epoch
       aged out of the ring buffer) render with a dimmed marker
       (per spec §Pins on the scrubber — 'the detached marker is
       the visible signal that the pin out-lives the ring buffer').

    3. **The buttons** — `Pin` captures the current selection with
       a label; `Reset to current` calls `restore-epoch` against
       the selected epoch; `Reset to pinned` calls
       `reset-frame-db!` against the pin's `:frame-db` value
       (per spec §Why reset-frame-db! not restore-epoch).

  ## Pure hiccup

  Same contract as the event-detail panel. The view never references
  Reagent / UIx / Helix directly. The substrate adapter installed via
  `rf/init!` handles the render. Frame isolation comes from the
  enclosing `[rf/frame-provider {:frame :rf/causa}]` in `shell.cljs`
  — every `subscribe` / `dispatch` resolves to `:rf/causa`.

  ## Helpers

  All pure-data logic lives in `time_travel_helpers.cljc` for JVM
  testability. This ns is the thin view-only wrapper."
  (:require [re-frame.core :as rf]
            [day8.re-frame2-causa.defaults :as defaults]
            [day8.re-frame2-causa.panels.time-travel-helpers :as h]
            [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack sans-stack]]))

;; ---- input state ---------------------------------------------------------
;;
;; The pin-label input box's text is per-session UI state. Pre-rf2-qvz85
;; this was a `defonce` plain atom deref'd inside the view; since plain
;; atoms are NOT a reactive primitive in any substrate, every keystroke
;; updated the atom but the panel did not re-render. The input now lives
;; at `:label-input` in `:rf/causa`'s db; the view subscribes via
;; `:rf.causa/time-travel-label-input` so a keystroke → event →
;; db-write → sub re-fire → re-render.

;; ---- sub-views -----------------------------------------------------------

(defn- empty-state
  "Rendered when the target frame has no epoch history yet — usually
  because no dispatch has run since the last reload, or because the
  epoch artefact is not on the classpath. Per spec §Production
  elision the panel still mounts in prod but renders this empty
  state."
  [target-frame]
  [:div {:data-testid "rf-causa-time-travel-empty"
         :style       {:padding "16px"
                       :color   (:text-tertiary tokens)
                       :font-family sans-stack
                       :font-size "13px"}}
   "No epoch history for "
   [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
    (str target-frame)]
   " yet. Trigger a dispatch in the host app and the scrubber will populate."])

(defn- chip-view
  "Render one pin chip. Attached chips (epoch still in history) get the
  accent-violet marker; detached chips get a dimmed dashed marker
  (per spec §Pins on the scrubber — the detached marker is the
  visible signal that the pin out-lives the ring buffer).

  Click → passive rebase via `:rf.causa/select-epoch`.
  Reset button (right-click affordance lives behind the chip's action
  menu in v1.1; Phase 3 ships the inline Reset button so the
  end-to-end pin → reset path is testable now)."
  [{:keys [pin attached] :as _chip-state}]
  (let [{:keys [epoch-id label]} pin]
    [:span {:data-testid (str "rf-causa-pin-chip-" (str epoch-id))
            :style       {:display       "inline-flex"
                          :align-items   "center"
                          :gap           "6px"
                          :padding       "2px 8px"
                          :margin-right  "6px"
                          :background    (:bg-3 tokens)
                          :color         (if attached
                                           (:text-primary tokens)
                                           (:text-tertiary tokens))
                          :border        (str "1px solid "
                                              (if attached
                                                (:border-default tokens)
                                                (:border-subtle tokens)))
                          :border-style  (if attached "solid" "dashed")
                          :border-radius "10px"
                          :font-family   sans-stack
                          :font-size     "11px"}}
     [:span {:style    {:color    (if attached
                                    (:accent-violet tokens)
                                    (:text-tertiary tokens))
                        :cursor   "pointer"}
             :on-click #(rf/dispatch [:rf.causa/select-epoch epoch-id] {:frame :rf/causa})}
      (str (if attached "●" "○") " " label)]
     [:button {:data-testid (str "rf-causa-pin-reset-" (str epoch-id))
               :on-click    #(rf/dispatch [:rf.causa/reset-to-pinned epoch-id] {:frame :rf/causa})
               :style       {:background    "transparent"
                             :border        "none"
                             :color         (:cyan tokens)
                             :cursor        "pointer"
                             :padding       "0 2px"
                             :font-family   mono-stack
                             :font-size     "10px"}
               :title       "Reset frame-db to this pin"}
      "↺"]
     [:button {:data-testid (str "rf-causa-pin-remove-" (str epoch-id))
               :on-click    #(rf/dispatch [:rf.causa/unpin epoch-id] {:frame :rf/causa})
               :style       {:background    "transparent"
                             :border        "none"
                             :color         (:text-tertiary tokens)
                             :cursor        "pointer"
                             :padding       "0 2px"
                             :font-family   mono-stack
                             :font-size     "10px"}
               :title       "Remove pin"}
      "✕"]]))

(defn- chip-row
  "Pin chips row. Hidden when no pins exist for the target frame."
  [chip-states]
  (when (seq chip-states)
    (into [:div {:data-testid "rf-causa-time-travel-chips"
                 :style       {:padding "8px 12px"
                               :display "flex"
                               :flex-wrap "wrap"
                               :gap "4px"
                               :border-bottom (str "1px solid " (:border-subtle tokens))}}]
          (for [cs chip-states]
            ^{:key (str (:epoch-id (:pin cs)))}
            (chip-view cs)))))

(defn- track-row
  "The timeline + slider. The slider's position is bound to the
  `:selected-index` of the composite — when the user drags, the
  on-change fires `:rf.causa/select-epoch` against the stable
  `:epoch-id` at the new slot (per Story's same rationale —
  selection holds the id, not the index).

  `[` / `]` keyboard nav routes through `on-key-down` on the slider —
  per spec §The passive-scrubbing rule the keyboard nav is passive."
  [{:keys [history selected-epoch-id selected-index] :as _data}]
  (let [n        (count history)
        max-idx  (max 0 (dec n))
        cur-idx  (or selected-index max-idx)
        on-step  (fn [delta]
                   (when-let [new-id (h/step-epoch history selected-epoch-id delta)]
                     (rf/dispatch [:rf.causa/select-epoch new-id] {:frame :rf/causa})))]
    [:div {:data-testid "rf-causa-time-travel-track"
           :style       {:padding "12px"
                         :display "flex"
                         :align-items "center"
                         :gap "10px"
                         :border-bottom (str "1px solid " (:border-subtle tokens))}}
     [:button {:data-testid "rf-causa-time-travel-jump-oldest"
               :on-click    #(when (seq history)
                               (rf/dispatch
                                 [:rf.causa/select-epoch
                                  (:epoch-id (first history))] {:frame :rf/causa}))
               :title       "Jump to oldest"
               :style       {:background  "transparent"
                             :border      (str "1px solid " (:border-default tokens))
                             :color       (:text-secondary tokens)
                             :padding     "2px 6px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family mono-stack
                             :font-size   "11px"}}
      "◀◀"]
     [:input {:data-testid "rf-causa-time-travel-slider"
              :type        "range"
              :min         0
              :max         max-idx
              :value       cur-idx
              :style       {:flex 1}
              :on-change   (fn [e]
                             (let [idx (js/parseInt (.. e -target -value))]
                               (when-let [eid (h/epoch-id-at-index history idx)]
                                 (rf/dispatch [:rf.causa/select-epoch eid] {:frame :rf/causa}))))
              :on-key-down (fn [e]
                             (case (.-key e)
                               "[" (do (.preventDefault e) (on-step -1))
                               "]" (do (.preventDefault e) (on-step  1))
                               "ArrowLeft"  (do (.preventDefault e) (on-step -1))
                               "ArrowRight" (do (.preventDefault e) (on-step  1))
                               nil))}]
     [:button {:data-testid "rf-causa-time-travel-jump-newest"
               :on-click    #(when (seq history)
                               (rf/dispatch
                                 [:rf.causa/select-epoch
                                  (:epoch-id (peek history))] {:frame :rf/causa}))
               :title       "Jump to newest"
               :style       {:background  "transparent"
                             :border      (str "1px solid " (:border-default tokens))
                             :color       (:text-secondary tokens)
                             :padding     "2px 6px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family mono-stack
                             :font-size   "11px"}}
      "▶▶"]
     [:span {:style {:color (:text-tertiary tokens)
                     :font-family mono-stack
                     :font-size "11px"
                     :min-width "76px"
                     :text-align "right"}}
      (str (inc cur-idx) "/" n " epochs")]]))

(defn- actions-row
  "The pin + reset buttons. Pin uses a label input bound to
  `:rf.causa/time-travel-label-input` (rf2-1barg — pre-fix this was a
  plain `defonce` atom which is not a reactive substrate primitive, so
  keystrokes did not re-render the row). Submitting fires
  `:rf.causa/pin-current` with the resolved selected-epoch-id + label.
  Reset to current uses restore-epoch via the
  `:rf.causa.fx/restore-epoch` effect."
  [{:keys [selected-epoch-id history cap-reached? label-input] :as _data}]
  (let [target-eid (or selected-epoch-id (h/newest-epoch-id history))
        history-empty? (empty? history)
        label       (or label-input "")]
    [:div {:data-testid "rf-causa-time-travel-actions"
           :style       {:padding "8px 12px"
                         :display "flex"
                         :align-items "center"
                         :gap "8px"
                         :border-bottom (str "1px solid " (:border-subtle tokens))
                         :flex-wrap "wrap"}}
     [:input {:data-testid "rf-causa-pin-label-input"
              :type        "text"
              :placeholder "pin label (optional)"
              :value       label
              :on-change   #(rf/dispatch [:rf.causa/time-travel-set-label-input
                                          (.. % -target -value)]
                                         {:frame :rf/causa})
              :style       {:flex "1 1 160px"
                            :min-width "120px"
                            :background  (:bg-3 tokens)
                            :color       (:text-primary tokens)
                            :border      (str "1px solid " (:border-default tokens))
                            :border-radius "4px"
                            :padding     "4px 8px"
                            :font-family sans-stack
                            :font-size   "12px"}}]
     [:button {:data-testid "rf-causa-pin-current"
               :disabled    (or history-empty? cap-reached?)
               :on-click    #(when target-eid
                               (rf/dispatch
                                 [:rf.causa/pin-current target-eid label]
                                 {:frame :rf/causa})
                               (rf/dispatch
                                 [:rf.causa/time-travel-set-label-input ""]
                                 {:frame :rf/causa}))
               :style       {:background  (if cap-reached?
                                            (:bg-3 tokens)
                                            (:accent-violet tokens))
                             :color       (if cap-reached?
                                            (:text-tertiary tokens)
                                            "#fff")
                             :border      "none"
                             :padding     "4px 10px"
                             :border-radius "4px"
                             :cursor      (if cap-reached? "not-allowed" "pointer")
                             :font-family sans-stack
                             :font-size   "12px"
                             :font-weight 600}}
      "Pin"]
     [:button {:data-testid "rf-causa-reset-to-epoch"
               :disabled    (or history-empty? (nil? target-eid))
               :on-click    #(rf/dispatch [:rf.causa/reset-to-epoch target-eid] {:frame :rf/causa})
               :style       {:background  "transparent"
                             :color       (:text-secondary tokens)
                             :border      (str "1px solid " (:border-default tokens))
                             :padding     "4px 10px"
                             :border-radius "4px"
                             :cursor      "pointer"
                             :font-family sans-stack
                             :font-size   "12px"}}
      "Reset to current"]
     (when cap-reached?
       [:span {:data-testid "rf-causa-pin-cap-warning"
               :style       {:color (:yellow tokens)
                             :font-family sans-stack
                             :font-size "11px"}}
        (str "Pin cap reached (" h/default-pin-cap "). Remove a pin to capture more.")])]))

;; ---- public view --------------------------------------------------------

(rf/reg-view time-travel-view
  "The Time Travel panel's root view. Subscribes to
  `:rf.causa/time-travel` and renders the empty-state / track + chips
  / actions stack."
  []
  (let [{:keys [target-frame history] :as data}
        @(rf/subscribe [:rf.causa/time-travel])]
    [:section {:data-testid "rf-causa-time-travel"
               :style       {:height         "100%"
                             :display        "flex"
                             :flex-direction "column"
                             :background     (:bg-2 tokens)
                             :color          (:text-primary tokens)
                             :font-family    sans-stack
                             :font-size      "14px"}}
     [:header {:style {:padding "16px 16px 8px 16px"}}
      [:h1 {:style {:font-size   "16px"
                    :font-weight 600
                    :margin      0
                    :color       (:text-primary tokens)}}
       "Time Travel"]
      [:p {:style {:font-size "12px"
                   :color     (:text-tertiary tokens)
                   :margin    "4px 0 0 0"}}
       "Scrub epoch history (passive). Rewind via "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        "Reset to current"]
       " or "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        "Reset to pinned"]
       ". Frame: "
       [:code {:style {:color (:accent-violet tokens) :font-family mono-stack}}
        (str target-frame)]]]
     [:div {:style {:flex 1 :overflow "auto"}}
      (if (empty? history)
        (empty-state target-frame)
        [:div
         (track-row data)
         (actions-row data)
         (chip-row (:chip-states data))])]]))

;; ---- registration entry --------------------------------------------------

(defonce restore-epoch-last-result
  ;; Captures the most-recent `:rf.causa.fx/restore-epoch` outcome —
  ;; `{:ok? <bool> :frame-id <kw> :epoch-id <opaque>}` or `nil` before
  ;; any restore has been attempted. Lives outside `install!` so a
  ;; shadow-cljs `:after-load` reload doesn't clobber an in-flight
  ;; failure record (and so the eviction-test corpus can `reset!` it
  ;; deterministically per case). See the long comment on the
  ;; `:rf.causa/last-restore-failure` sub in `install!` for why this
  ;; lives at module-scope and not inside app-db. rf2-o94sp /
  ;; audit 4b.
  (atom nil))

(defn restore-epoch-fx-fn
  "Body of the `:rf.causa.fx/restore-epoch` reg-fx, exposed as a
  top-level fn so the failure-surfacing contract (rf2-o94sp; audit 4b)
  is unit-testable in isolation. The reg-fx call inside `install!`
  delegates here.

  Failure surfacing: `rf/restore-epoch` returns `false` on any of its
  six failure modes (aged-out epoch, schema mismatch, drain-in-flight,
  etc.) and emits its own structured `:rf.epoch/restore-*` trace event.
  This wrapper writes the boolean + identifiers to
  `restore-epoch-last-result`, dispatches the bump-tick so the
  reactive `:rf.causa/last-restore-failure` sub re-fires, and on
  failure clears the now-stuck `:selected-epoch-id` so the panel
  doesn't render a confirmed-rewind that didn't actually land. The
  structured trace event has already flowed through the trace bus
  and surfaced on the Issues ribbon."
  [_ctx {:keys [frame-id epoch-id]}]
  (let [ok? (rf/restore-epoch frame-id epoch-id)]
    (reset! restore-epoch-last-result
            {:ok? ok? :frame-id frame-id :epoch-id epoch-id})
    (rf/dispatch [:rf.causa/bump-restore-epoch-tick] {:frame :rf/causa})
    (when-not ok?
      (rf/dispatch [:rf.causa/clear-selected-epoch] {:frame :rf/causa}))))

(defn install!
  "Idempotent install for the Time Travel scrubber panel's Causa-side
  registrations (Phase 3, rf2-t53ze). Owns the cross-panel scrubber
  state slots — target-frame / epoch-history / selected-epoch-id /
  pin-store — that the App-DB Diff, Causality Graph, Routes, Hydration
  Debugger, Schema-violation Timeline and Machine Inspector panels
  all read transitively.

  Two `reg-fx` handlers (the confirmed-rewind paths) and two event-fxs
  (`:rf.causa/reset-to-epoch` / `:rf.causa/reset-to-pinned`) round out
  the surface."
  []
  ;; Target frame the scrubber inspects. Hard-bound to :rf/default
  ;; until a frame picker lands; the sub abstracts so the picker can
  ;; drop in without rewiring every consumer.
  (rf/reg-sub :rf.causa/target-frame
    (fn [db _query]
      (get db :target-frame defaults/default-target-frame)))

  ;; Set the active target frame. The `core.cljs` facade's
  ;; `set-target-frame!` dispatches this; once a UI frame picker
  ;; lands it will dispatch the same event. `nil` resets to the
  ;; default target frame (`:rf/default`).
  (rf/reg-event-db :rf.causa/set-target-frame
    (fn [db [_ frame-id]]
      (if (nil? frame-id)
        (dissoc db :target-frame)
        (assoc db :target-frame frame-id))))

  ;; Cached snapshot of the target frame's epoch history, pumped
  ;; by `:rf.causa/epoch-recorded` (dispatched from the epoch-cb in
  ;; preload). The cache is necessary because rf/epoch-history is a
  ;; side-effecting read of the epoch artefact's atom — a sub fn
  ;; can call it but the sub graph won't re-fire when the atom
  ;; mutates. Routing history through Causa's app-db makes the sub
  ;; reactive on its own write path.
  (rf/reg-sub :rf.causa/epoch-history
    (fn [db _query]
      (get db :epoch-history [])))

  ;; The view's currently-selected epoch — nil = newest (no scrub
  ;; in flight). Per spec §The passive-scrubbing rule, scrubbing
  ;; rebases panels but does NOT rewind app-db.
  (rf/reg-sub :rf.causa/selected-epoch-id
    (fn [db _query]
      (get db :selected-epoch-id)))

  ;; The most-recent `restore-epoch` failure, captured by the
  ;; `:rf.causa.fx/restore-epoch` fx wrapper when `rf/restore-epoch`
  ;; returns `false` (rf2-o94sp, audit 4b). Reads from the
  ;; `restore-epoch-last-result` atom — keyed on the boolean return
  ;; rather than on app-db state because the fx layer is the only
  ;; point that sees the return value, and writing into the Causa
  ;; frame's app-db from inside an fx that's mid-cascade is not the
  ;; framework's expected shape (fx are leaf-level side-effects, not
  ;; cascade re-entrants).
  ;;
  ;; The structured `:rf.epoch/restore-*` failure trace event is the
  ;; canonical signal; this sub exists so panel views can render an
  ;; inline notice without scanning the trace ribbon. Shape (or nil
  ;; when no failure has been observed):
  ;;   {:frame-id <kw> :epoch-id <opaque>}
  (rf/reg-sub :rf.causa/last-restore-failure
    :<- [:rf.causa/restore-epoch-tick]
    (fn [_tick _query]
      (let [{:keys [ok? frame-id epoch-id]} @restore-epoch-last-result]
        (when (and (some? ok?) (not ok?))
          {:frame-id frame-id :epoch-id epoch-id}))))

  ;; Reactivity-anchor sub for `:rf.causa/last-restore-failure`. The
  ;; fx-wrapper writes to `restore-epoch-last-result` (an atom outside
  ;; the reactive substrate); the wrapper bumps `:restore-epoch-tick`
  ;; in Causa's app-db on every write, and this sub depends on that
  ;; tick so the substrate's reactive surface sees the change.
  (rf/reg-sub :rf.causa/restore-epoch-tick
    (fn [db _query]
      (get db :restore-epoch-tick 0)))

  ;; Internal event the fx wrapper dispatches to bump the tick.
  ;; Carries `:rf.trace/no-emit? true` so the bump itself doesn't
  ;; flood the trace ribbon (per rf2-qsjda).
  (rf/reg-event-db :rf.causa/bump-restore-epoch-tick
    {:rf.trace/no-emit? true}
    (fn [db _]
      (update db :restore-epoch-tick (fnil inc 0))))

  ;; Per-frame pin store, keyed by target-frame. Persisted into
  ;; Causa's app-db only — never localStorage / disk (Lock 4 per
  ;; spec §Session-scoped — pins do not survive reload).
  (rf/reg-sub :rf.causa/pin-store
    (fn [db _query]
      (get db :pin-store {})))

  ;; The pin vector for the current target-frame — a flat sequence
  ;; the view iterates. Decoupled from :rf.causa/pin-store so the
  ;; view doesn't re-render when an unrelated frame's pins mutate.
  (rf/reg-sub :rf.causa/pinned-snapshots
    :<- [:rf.causa/pin-store]
    :<- [:rf.causa/target-frame]
    (fn [[pin-store target-frame] _query]
      (h/epoch-pins-for-frame pin-store target-frame)))

  ;; The pin-label input box's current text (rf2-1barg). Pre-fix this
  ;; was a `defonce` plain atom; plain atoms are not a substrate-
  ;; reactive primitive, so keystrokes updated state without re-
  ;; rendering the actions row. Routing through the app-db slot makes
  ;; the panel's React-context-tier subscribe re-fire on every set-
  ;; label dispatch.
  (rf/reg-sub :rf.causa/time-travel-label-input
    (fn [db _query]
      (get db :label-input "")))

  ;; Composite for the panel — one read produces every slot the
  ;; view needs. Mirrors the Phase-2 `:rf.causa/event-detail`
  ;; composite shape. The :chip-states projection runs chip-state
  ;; over each pin against the current history so detached pins
  ;; carry the visible signal per spec §Pins on the scrubber.
  (rf/reg-sub :rf.causa/time-travel
    :<- [:rf.causa/target-frame]
    :<- [:rf.causa/epoch-history]
    :<- [:rf.causa/selected-epoch-id]
    :<- [:rf.causa/pinned-snapshots]
    :<- [:rf.causa/time-travel-label-input]
    (fn [[target-frame history selected-id pins label-input] _query]
      (let [selected-record (when selected-id
                              (h/find-epoch-in-history
                                history selected-id))]
        {:target-frame    target-frame
         :history         history
         :selected-epoch-id selected-id
         :selected-record selected-record
         :selected-index  (h/epoch-index-in-history
                            history selected-id)
         :pins            pins
         :label-input     label-input
         :chip-states     (h/chip-states history pins)
         :cap-reached?    (>= (count pins) h/default-pin-cap)})))

  ;; ---- Phase 3 (rf2-t53ze) — Time Travel scrubber events ---------

  ;; Pump the latest epoch-history snapshot for the target frame
  ;; into Causa's app-db. Dispatched from the epoch-cb registered
  ;; in preload.cljs on every settled epoch. We don't pass the
  ;; vector across the dispatch boundary — we re-read from the
  ;; framework's `rf/epoch-history` so the snapshot is always
  ;; consistent with the framework's view (the cb fires AFTER the
  ;; record is appended; a stale arg would be off-by-one only on
  ;; the boundary, but threading the live read keeps the contract
  ;; simple).
  ;; Per rf2-1barg: carries `:rf.trace/no-emit? true` (per rf2-qsjda).
  ;; Without the flag every host-side settle fires this handler under
  ;; `:rf/causa` AND its cascade is captured by the epoch artefact —
  ;; which snapshots `:rf/causa`'s app-db at db-before / db-after.
  ;; Causa's app-db contains the trace-buffer + a copy of the host's
  ;; epoch-history; the snapshot pair grows O(N) per write so the
  ;; recorded ring buffer cost is O(N²) and the browser OOMs after
  ;; a few host dispatches. With the flag the trace surface short-
  ;; circuits before allocation, no events are captured for the
  ;; cascade, no record is appended, and `:rf/causa` stays out of
  ;; the epoch ring buffer entirely (the correct shape — Causa is
  ;; the inspector, not the inspected).
  (rf/reg-event-db :rf.causa/epoch-recorded
    {:rf.trace/no-emit? true}
    (fn [db [_ frame-id]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (if (= frame-id target)
          (assoc db :epoch-history (vec (rf/epoch-history target)))
          db))))

  ;; Wholesale overwrite of the `:epoch-history` slot (rf2-1barg).
  ;; Dispatched from `mount.cljs/open!` on first Ctrl+Shift+C to seed
  ;; the slot with the framework's accumulated history for the
  ;; default target frame. Host dispatches that fired before the user
  ;; opened Causa landed in the framework's ring buffer but couldn't
  ;; flow through the epoch-cb (the cb short-circuits pre-mount per
  ;; preload.cljs §Pre-mount guard); this seed lifts those records
  ;; into Causa's reactive slot. Per rf2-qsjda `:rf.trace/no-emit?
  ;; true` opts out of trace emission for the seed dispatch — pure
  ;; bookkeeping, no user signal.
  (rf/reg-event-db :rf.causa/sync-epoch-history
    {:rf.trace/no-emit? true}
    (fn [db [_ history]]
      (assoc db :epoch-history (vec history))))

  ;; Replace the pin-label input text (rf2-1barg). Fires from every
  ;; `<input on-change>` keystroke and on submit-clears the slot.
  ;; Carries `:rf.trace/no-emit?` (per rf2-qsjda) so keystroke-rate
  ;; updates don't flood the trace ribbon.
  (rf/reg-event-db :rf.causa/time-travel-set-label-input
    {:rf.trace/no-emit? true}
    (fn [db [_ text]]
      (assoc db :label-input (or text ""))))

  ;; Set the view's selected-epoch (passive scrub). Per spec §The
  ;; passive-scrubbing rule — this DOES NOT call restore-epoch.
  (rf/reg-event-db :rf.causa/select-epoch
    (fn [db [_ epoch-id]]
      (assoc db :selected-epoch-id epoch-id)))

  ;; Clear the panel's scrub selection — used by explicit UI reset and
  ;; by the fx wrapper on failed restore to drop the now-stuck pointer.
  (rf/reg-event-db :rf.causa/clear-selected-epoch
    (fn [db _event]
      (dissoc db :selected-epoch-id)))

  ;; Pin the epoch at `epoch-id` under the current target-frame
  ;; with `label`. The handler eagerly copies :db-after off the
  ;; live history record (per spec §What a pin captures — eager
  ;; capture). Enforces the 32-pin cap; surfaces `:overflow?` via
  ;; the toast slot the view reads on next render.
  (rf/reg-event-db :rf.causa/pin-current
    (fn [db [_ epoch-id label]]
      (let [target  (get db :target-frame defaults/default-target-frame)
            history (vec (or (get db :epoch-history)
                             (rf/epoch-history target)))
            record  (h/find-epoch-in-history history epoch-id)
            pin     (h/pin-from-epoch record label)]
        (if (some? pin)
          (let [{:keys [store overflow? dropped-pin]}
                (h/pin-snapshot (get db :pin-store {})
                                target pin)]
            (cond-> (assoc db :pin-store store)
              overflow? (assoc :pin-overflow-toast
                               {:dropped-label (:label dropped-pin)
                                :ts            (.getTime (js/Date.))})))
          db))))

  ;; Drop a pin from the current target-frame's pin store.
  (rf/reg-event-db :rf.causa/unpin
    (fn [db [_ epoch-id]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pin-store
                h/unpin-snapshot target epoch-id))))

  ;; Inline-rename a pin's label. The 4-tuple's other slots are
  ;; immutable (spec §Pin actions §Rename pin).
  (rf/reg-event-db :rf.causa/rename-pin
    (fn [db [_ epoch-id new-label]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        (update db :pin-store
                h/rename-pin target epoch-id new-label))))

  ;; Dismiss the cap-reached toast surface.
  (rf/reg-event-db :rf.causa/dismiss-pin-overflow-toast
    (fn [db _] (dissoc db :pin-overflow-toast)))

  ;; ---- write effects (the two confirmed-rewind paths) ----------

  ;; Reset to current epoch — uses restore-epoch (the ring-buffer
  ;; path). Per spec §The passive-scrubbing rule §rewind = explicit:
  ;; this is the confirmed-rewind branch. Per re-frame v2's reg-fx
  ;; contract (Spec API.md §reg-fx) the handler signature is
  ;; (fn [ctx args] ...).
  ;; Failure surfacing (rf2-o94sp; audit 4b) — body lives in
  ;; `restore-epoch-fx-fn` above so the contract is unit-testable
  ;; without going through the framework's effect-handler dispatch
  ;; (a `with-redefs` over `rf/restore-epoch` doesn't reach a
  ;; closure captured at reg-fx time; the top-level fn route does).
  (rf/reg-fx :rf.causa.fx/restore-epoch restore-epoch-fx-fn)

  ;; Reset to pinned — uses reset-frame-db! (the value-direct path).
  ;; Per spec §Why reset-frame-db! not restore-epoch — pins hold the
  ;; value directly, so the rewind works even after the underlying
  ;; epoch ages out of the ring buffer.
  (rf/reg-fx :rf.causa.fx/reset-frame-db!
    (fn [_ctx {:keys [frame-id frame-db]}]
      (rf/reset-frame-db! frame-id frame-db)))

  (rf/reg-event-fx :rf.causa/reset-to-epoch
    (fn [{:keys [db]} [_ epoch-id]]
      (let [target (get db :target-frame defaults/default-target-frame)]
        ;; Per Spec MIGRATION §Effect map shape — re-frame2's canonical
        ;; fx return is `{:db ... :fx [[fx-id args] ...]}`. Top-level
        ;; effect keys other than :db / :fx are not part of the
        ;; contract; the registered fx is invoked via the :fx vector.
        {:fx [[:rf.causa.fx/restore-epoch
               {:frame-id target :epoch-id epoch-id}]]})))

  (rf/reg-event-fx :rf.causa/reset-to-pinned
    (fn [{:keys [db]} [_ epoch-id]]
      (let [target (get db :target-frame defaults/default-target-frame)
            pin    (h/find-pin (get db :pin-store {})
                               target epoch-id)]
        (when pin
          {:fx [[:rf.causa.fx/reset-frame-db!
                 {:frame-id target :frame-db (:frame-db pin)}]]}))))

  nil)
