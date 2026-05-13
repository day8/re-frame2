(ns re-frame.story.ui.actions
  "Actions panel — a dedicated, chronological view of user-action
  dispatches and dispatch-shaped fx invocations against the variant's
  trace buffer. Per Story SOTA audit F3 / rf2-5yriz.

  ## What it is

  Storybook's `action('clicked')` API surfaces handler-call events in
  a dedicated 'Actions' panel so designers can answer 'what did the
  user just do?' without reading source.  Story's trace panel already
  captures the full six-domino cascade — far more than Storybook's
  Actions panel sees — but the cascade view conflates user-driven
  dispatches with the internal cascade (effects, sub re-runs, view
  renders) and asks the reader to mentally filter.

  This panel is the cheap filter.  It reads the same per-variant
  trace buffer the trace panel reads (`re-frame.story.ui.trace/
  buffers`, populated by the listener wired at variant-selection in
  `re-frame.story.ui.shell`) and projects the buffer down to two
  classes of events:

    1. **dispatches** — every `:event/dispatched` trace event,
       regardless of whether it was a root dispatch or a descendant
       fired from inside a handler.
    2. **dispatch-shaped fx invocations** — every `:rf.fx/handled`
       trace event whose `:tags :fx-id` is one of `:dispatch`,
       `:dispatch-later`, or `:dispatch-sync`.

  Together those two channels cover both 'a user clicked, an event
  fired' (1) and 'a handler returned `:fx [[:dispatch x]]` so a
  follow-on dispatch is queued' (2).  Either signal is meaningful
  for 'what's actually happening'.

  ## Differentiator vs Storybook

  Storybook's panel only captures whatever the author manually wired
  via `action('name')` per handler.  Forget to wire one and the panel
  goes silent.  Story's panel sees EVERYTHING on the trace bus
  automatically — every `dispatch`, every `dispatch-later`, every
  fx-handled emit — without any manual instrumentation.  The header
  copy says so.

  ## UX

      ┌──────────────────────────────────────────────────────┐
      │  Actions — <variant-id> — N events  [pause] [clear]   │
      ├──────────────────────────────────────────────────────┤
      │  hh:mm:ss.mmm  :counter/inc          []              │
      │  hh:mm:ss.mmm  :rf.fx/handled  :dispatch  [:foo]     │
      │  hh:mm:ss.mmm  :counter/dec          []              │
      │  …                                                   │
      └──────────────────────────────────────────────────────┘

  - The list scrolls; on every render the panel re-anchors at the
    bottom (newest entry visible) unless the user has scrolled up.
  - **Pause** flips a per-variant boolean.  While paused, the
    projection is computed against a SNAPSHOT of the trace buffer
    taken at the moment of pause; new trace events continue to
    accumulate in the underlying buffer (we don't pause capture, we
    pause rendering) so unpausing 'rewinds' you to live.
  - **Clear** zeros the underlying trace buffer for the variant via
    `trace/clear-buffer!`.  That is the same clear the trace panel
    would do — both panels share the buffer.  We deliberately wire
    to the same primitive so the panels stay coherent.

  ## Source-coord hook

  Each row carries the action's `:rf.trace/trigger-handler` (per
  Spec 009 §Source-coord stamping) under the row's `:source` slot.
  When rf2-evgf5 lands and Story ships an 'Open in editor' click
  affordance, the row's source-coord is already in hand — the
  click-handler wraps the existing slot.  No retrofit needed.

  ## Pure / impure split

  This namespace is `.cljc` so the pure projection (`action-event?`,
  `project-rows`, `format-timestamp`) can be exercised by the JVM
  test corpus alongside the cljs-test node runner.  The Reagent
  rendering, the listener wiring, and the pause/clear ratoms are
  CLJS-only.

  ## Elision

  The pure helpers below run on the JVM and in dev CLJS only — the
  panel's mount call sits behind `re-frame.story.config/enabled?` in
  the shell, so production builds short-circuit before any panel fn
  is reached.  Closure DCEs the lot.

  ## Panel registry slot

  Per rf2-5yriz the shell wires this panel as a top-level slot under
  the `:actions` key in `:panel-visibility` — the same shape used by
  `:trace`, `:scrubber`, `:controls`.  Stage 6's `reg-story-panel`
  contract handles registered panels; the built-in Story chrome
  panels (trace, scrubber, controls, actions) are wired by the shell
  directly because they are always present and have no late-bind
  contract."
  #?(:cljs
     (:require [reagent.core            :as r]
               [re-frame.story.config   :as config]
               [re-frame.story.ui.trace :as trace])))

;; ---- the dispatch-shaped fx-ids -----------------------------------------

(def dispatch-shaped-fx-ids
  "Set of `:fx-id` values whose `:rf.fx/handled` trace event represents
  a (re-)dispatch of an event vector.  Per re-frame.fx/handle-one-fx
  the framework emits `:rf.fx/handled` with one of these ids whenever
  an event handler returned `:fx [[:dispatch ...] [:dispatch-later
  ...] [:dispatch-sync ...]]`.  We surface those alongside the
  primary `:event/dispatched` channel so the panel shows both the
  enqueue (the fx) AND the drain (the eventual `:event/dispatched`)."
  #{:dispatch :dispatch-later :dispatch-sync})

;; ---- pure: classify a trace event -------------------------------------

(defn action-event?
  "True iff `ev` (a raw trace event from the buffer) is one of the two
  classes the Actions panel surfaces:

    - `:op-type :event` + `:operation :event/dispatched` — a dispatch
      landing on the router.
    - `:op-type :fx` + `:operation :rf.fx/handled` + `:tags :fx-id`
      ∈ `dispatch-shaped-fx-ids` — a dispatch-shaped fx invocation.

  Pure data → boolean; JVM-testable."
  [{:keys [op-type operation tags] :as _ev}]
  (boolean
    (or (and (= op-type :event)
             (= operation :event/dispatched))
        (and (= op-type :fx)
             (= operation :rf.fx/handled)
             (contains? dispatch-shaped-fx-ids (:fx-id tags))))))

;; ---- pure: project a trace event into a row ---------------------------

(defn row-class
  "Classify a trace event into one of two row classes so the renderer
  can colour-code dispatch (`:dispatch`) vs fx-emit (`:fx-dispatch`).
  Returns nil for events that don't classify (caller should have
  filtered via `action-event?` first; this is the secondary classifier
  used by the row projection)."
  [{:keys [op-type operation] :as _ev}]
  (cond
    (and (= op-type :event) (= operation :event/dispatched)) :dispatch
    (and (= op-type :fx)    (= operation :rf.fx/handled))    :fx-dispatch
    :else                                                    nil))

(defn- base-row
  "Build the slots every projected row shares — `:id`, `:class`,
  `:time`, `:dispatch-id`, `:source`, and `:raw`. Per-class
  projections in `project-row` then fill in the slots that differ
  (event-id / event / args / origin / fx-id)."
  [class {:keys [tags id time] :as ev} trig-src]
  {:id          id
   :class       class
   :time        time
   :event-id    nil
   :event       nil
   :args        nil
   :dispatch-id (:dispatch-id tags)
   :origin      nil
   :fx-id       nil
   :source      trig-src
   :raw         ev})

(defn project-row
  "Project one raw trace event into the row shape the panel renders.
  Pure data → data; JVM-testable.

  Output shape:

      {:id          <int>                 ;; the trace event's :id (stable per-process key)
       :class       :dispatch|:fx-dispatch
       :time        <ms-since-epoch>      ;; from the trace event's :time
       :event-id    <kw|nil>              ;; the event-id for both classes
       :event       <vector|nil>          ;; the full event vector when present
       :args        <any|nil>             ;; for :fx-dispatch rows, the dispatched event's args
       :dispatch-id <id-or-nil>           ;; cascade correlation
       :origin      <kw|nil>              ;; per Spec 009 §Origin tagging
       :fx-id       <kw|nil>              ;; nil for :dispatch rows
       :source      <{:file :line :column}|nil>  ;; trigger-handler coord, rf2-evgf5 hook
       :raw         <trace-event>}"
  [{:keys [tags] :as ev}]
  (let [class      (row-class ev)
        rf-trigger (:rf.trace/trigger-handler ev)
        trig-src   (:source-coord rf-trigger)]
    (case class
      :dispatch
      (assoc (base-row :dispatch ev trig-src)
             :event-id (or (:event-id tags) (some-> tags :event first))
             :event    (:event tags)
             :origin   (:origin tags))

      :fx-dispatch
      (let [fx-args (:fx-args tags)
            evt     (cond
                      (vector? fx-args) fx-args              ;; :dispatch       → [event-id args...]
                      (map?    fx-args) (:event fx-args)     ;; :dispatch-later → {:event ... :ms ...}
                      :else             nil)]
        (assoc (base-row :fx-dispatch ev trig-src)
               :event-id (some-> evt first)
               :event    (when (vector? evt) evt)
               :args     (when (and (vector? evt) (> (count evt) 1))
                           (vec (rest evt)))
               :origin   (:origin tags)
               :fx-id    (:fx-id tags)))

      ;; nil class — shouldn't happen if action-event? was checked, but
      ;; return a minimal row so the renderer can still surface it.
      (base-row :unknown ev trig-src))))

(defn project-rows
  "Filter `events` (a seq of raw trace events from the buffer) to the
  action-emit subset and project each one into a row.  Returns a
  vector in chronological order — same order the trace buffer
  delivers (oldest first), so the renderer can `take-last N` for the
  most-recent slice or render the whole vector with auto-scroll-to-
  bottom.

  Pure data → data; JVM-testable."
  [events]
  (into []
        (comp (filter action-event?)
              (map project-row))
        events))

;; ---- pure: formatting --------------------------------------------------

(defn format-timestamp
  "Render a wall-clock `ms` (UNIX epoch milliseconds, as captured by
  `re-frame.interop/now-ms`) as `\"HH:MM:SS.mmm\"`.  Pure; JVM-testable.

  `nil` / non-number inputs return the empty string so the renderer
  can interpolate it safely.

  Uses the platform's local time.  Both branches handle the
  fractional-millis padding so 1ms-resolution timestamps don't
  collapse to two digits."
  [ms]
  (cond
    (nil? ms)          ""
    (not (number? ms)) ""
    (neg? ms)          ""
    :else
    (let [millis (mod (long ms) 1000)
          ;; The CLJS branch uses Date for local-time HH:MM:SS; the
          ;; JVM branch uses a SimpleDateFormat-equivalent via formatting.
          pad   (fn [n w]
                  (let [s (str n)]
                    (if (< (count s) w)
                      (str (apply str (repeat (- w (count s)) "0")) s)
                      s)))]
      #?(:clj
         (let [d  (java.util.Date. (long ms))
               c  (doto (java.util.Calendar/getInstance)
                    (.setTime d))]
           (str (pad (.get c java.util.Calendar/HOUR_OF_DAY) 2) ":"
                (pad (.get c java.util.Calendar/MINUTE)      2) ":"
                (pad (.get c java.util.Calendar/SECOND)      2) "."
                (pad millis                                  3)))
         :cljs
         (let [d (js/Date. ms)]
           (str (pad (.getHours d)   2) ":"
                (pad (.getMinutes d) 2) ":"
                (pad (.getSeconds d) 2) "."
                (pad millis          3)))))))

(defn pretty-args
  "Render the optional args vector for a row (everything after the
  event-id in the dispatched event vector) as a short pr-str.  Empty
  / nil → the empty string.  Pure; JVM-testable."
  [args]
  (cond
    (nil? args)        ""
    (and (coll? args)
         (zero? (count args))) ""
    :else              (pr-str args)))

;; ---- per-variant pause state -------------------------------------------
;;
;; Pause is per-variant so the user can pause /story.a/x's actions
;; while keeping /story.b/y live.  On pause we ALSO capture the
;; current buffer snapshot so the panel renders the moment-of-pause
;; even as new trace events continue to flow into the underlying
;; buffer.  Unpausing drops the snapshot and renders against the
;; live buffer again.
;;
;; `paused-state` carries `{variant-id → {:paused? bool :snapshot
;; [trace-event ...]}}`.  CLJS-only because the runtime uses
;; `reagent.core/atom` so toggles trigger re-renders.  On the JVM
;; the per-variant pause state is irrelevant (no shell mounted)
;; and the helpers below are unused.

#?(:cljs
   (defonce paused-state
     ;; Reagent ratom so the panel re-renders when pause / unpause
     ;; toggles.  The buffer snapshot lives inside the ratom so
     ;; updates fire one re-render, not two.
     (r/atom {})))

#?(:cljs
   (defn paused?
     "True iff actions are currently paused for `variant-id`."
     [variant-id]
     (boolean (get-in @paused-state [variant-id :paused?]))))

#?(:cljs
   (defn pause!
     "Pause action-rendering for `variant-id`.  Captures the current
     trace buffer as the snapshot the panel renders against until
     unpause.  Idempotent."
     [variant-id]
     (let [buf (some-> (get @trace/buffers variant-id) deref)]
       (swap! paused-state assoc variant-id
              {:paused?  true
               :snapshot (vec (or buf []))}))
     nil))

#?(:cljs
   (defn unpause!
     "Resume live action-rendering for `variant-id`.  Drops the
     captured snapshot.  Idempotent."
     [variant-id]
     (swap! paused-state assoc variant-id
            {:paused?  false
             :snapshot nil})
     nil))

#?(:cljs
   (defn toggle-pause!
     "Toggle `paused?` for `variant-id`."
     [variant-id]
     (if (paused? variant-id)
       (unpause! variant-id)
       (pause! variant-id))))

#?(:cljs
   (defn current-events
     "Return the trace events the panel should render.  When paused,
     this is the snapshot captured at pause-time.  When live, this is
     the underlying per-variant trace buffer.  The return value is a
     vector ready for `project-rows`."
     [variant-id]
     (if (paused? variant-id)
       (or (get-in @paused-state [variant-id :snapshot]) [])
       (let [a (get @trace/buffers variant-id)]
         (if a @a [])))))

#?(:cljs
   (defn clear!
     "Clear the underlying trace buffer for `variant-id`.  Shared with
     the trace panel — both panels read the same buffer — so this is
     a destructive operation.  Unpauses on clear (otherwise the panel
     would still render the now-stale snapshot).

     Per rf2-bclgj: also clears the per-variant suppressed-events
     counter so the redaction hint hides when the user resets the
     panel."
     [variant-id]
     (trace/clear-buffer! variant-id)
     (config/reset-suppressed-count! variant-id)
     (unpause! variant-id)
     nil))

;; ---- styling ---------------------------------------------------------

#?(:cljs
   (def ^:private styles
     {:panel          {:padding "8px"
                       :font-family "monospace"
                       :font-size "11px"
                       :border-top "1px solid #444"
                       :background "#1e1e1e"
                       :color "#ddd"
                       :overflow "auto"
                       :max-height "240px"}
      :title          {:font-weight "bold"
                       :margin-bottom "6px"
                       :color "#9cdcfe"
                       :display "flex"
                       :justify-content "space-between"
                       :align-items "center"
                       :gap "8px"}
      :title-text     {:flex "1 1 auto"
                       :overflow "hidden"
                       :text-overflow "ellipsis"
                       :white-space "nowrap"}
      :btn            {:padding "2px 8px"
                       :background "#2d2d30"
                       :color "#d0d0d0"
                       :border "1px solid #444"
                       :border-radius "3px"
                       :font-family "monospace"
                       :font-size "10px"
                       :cursor "pointer"}
      :btn-active     {:background "#264f78"
                       :color "white"
                       :border-color "#264f78"}
      :hint           {:color "#9a9a9a"
                       :font-style "italic"
                       :font-size "10px"
                       :margin-bottom "6px"}
      ;; rf2-bclgj: the redaction hint mirrors the trace panel's
      ;; `[● REDACTED]` indicator. Muted red so the privacy-gate state
      ;; reads as advisory, not as a hard error.
      :redact-note    {:color "#d16969"
                       :font-style "italic"
                       :font-size "10px"
                       :margin-bottom "6px"}
      :rows-host      {:max-height "200px"
                       :overflow-y "auto"
                       :border-top "1px dotted #333"
                       :padding-top "4px"}
      :row            {:display "grid"
                       :grid-template-columns "92px auto 1fr 1fr"
                       :gap "6px"
                       :padding "2px 0"
                       :border-bottom "1px dotted #2a2a2a"
                       :align-items "baseline"}
      :cell           {:overflow "hidden"
                       :text-overflow "ellipsis"
                       :white-space "nowrap"}
      :cell-time      {:color "#9a9a9a"}
      :cell-eid       {:color "#dcdcaa"
                       :font-weight "bold"}
      :cell-args      {:color "#b5cea8"}
      :cell-tag       {:color "#9cdcfe"
                       :font-style "italic"}
      :empty          {:color "#9a9a9a"
                       :font-style "italic"}}))

;; ---- view ------------------------------------------------------------

#?(:cljs
   (defn action-row
     "Render one row.  Form-1 — pure projection over the row map; no
     deref happens inside.  The wrapping panel handles the reactive
     deref so per-row equality is React-stable.

     Per rf2-5yriz the row carries a `data-test=\"story-actions-row\"`
     attribute so the browser-side Playwright spec can locate rows
     deterministically across renders.  Source-coord lives on the
     row map under `:source` and rides on the `title` tooltip — when
     rf2-evgf5 lands the click-handler binds to the same slot."
     [{:keys [class time event-id event args fx-id source] :as _row}]
     (let [title (when source
                   (str (:file source) ":" (:line source)))
           tag   (case class
                   :dispatch     ""
                   :fx-dispatch  (str ":fx-handled " fx-id)
                   :unknown      "(unknown)")
           tail  (cond
                   (and (= class :dispatch) event)
                   (pretty-args (when (> (count event) 1)
                                  (vec (rest event))))
                   (= class :fx-dispatch) (pretty-args args)
                   :else                  "")]
       [:div {:style                (:row styles)
              :title                title
              :data-test            "story-actions-row"
              :data-row-class       (when class (name class))
              :data-event-id        (when event-id (str event-id))}
        [:span {:style (merge (:cell styles) (:cell-time styles))}
         (format-timestamp time)]
        [:span {:style (merge (:cell styles) (:cell-eid styles))}
         (if event-id (pr-str event-id) "—")]
        [:span {:style (merge (:cell styles) (:cell-args styles))} tail]
        [:span {:style (merge (:cell styles) (:cell-tag styles))} tag]])))

#?(:cljs
   (defn panel
     "The Actions panel.  Per rf2-5yriz form-2 — the inner render fn
     derefs the trace buffer + the pause state so Reagent's reaction
     tracking observes every change.  Mirrors the shape of
     `re-frame.story.ui.trace/panel`.

     Renders a scrollable region tagged with `data-test=\"story-actions-
     panel\"` and `role=\"region\"` + `aria-label` so axe-core's
     scrollable-region-focusable rule passes and the browser-test
     spec can anchor on it."
     [variant-id]
     ;; Form-2: capture the buffer atom on the outer closure so the
     ;; per-render fn observes the same source-of-truth across the
     ;; component's lifecycle.  Mirrors the trace panel.
     (let [_buf (trace/ensure-buffer! variant-id)]
       (fn [variant-id]
         (let [paused (paused? variant-id)
               events (current-events variant-id)
               rows   (project-rows events)
               ;; rf2-bclgj: same per-variant suppressed-events counter
               ;; the trace panel reads. The trace listener bumps it once
               ;; per sensitive event, the actions panel renders the
               ;; redaction hint when the count is non-zero. Read on every
               ;; render — the buffer's reactive deref above is what re-
               ;; runs the render.
               redacted-count (config/suppressed-count variant-id)]
           [:div {:style      (:panel styles)
                  :role       "region"
                  :aria-label "Actions"
                  :tab-index  "0"
                  :data-test  "story-actions-panel"
                  :data-redacted-count (str redacted-count)}
            [:div {:style (:title styles)}
             [:span {:style (:title-text styles)}
              "Actions" (when variant-id (str " " (pr-str variant-id)))
              " — " (count rows) " events"
              (when paused " (paused)")]
             [:button {:style     (merge (:btn styles)
                                         (when paused (:btn-active styles)))
                       :on-click  (fn [_] (toggle-pause! variant-id))
                       :data-test "story-actions-pause"
                       :aria-pressed (str paused)
                       :title     (if paused "resume" "pause")}
              (if paused "resume" "pause")]
             [:button {:style     (:btn styles)
                       :on-click  (fn [_] (clear! variant-id))
                       :data-test "story-actions-clear"
                       :title     "clear the trace buffer"}
              "clear"]]
            [:div {:style (:hint styles)}
             "auto-captures every dispatch + dispatch-shaped fx — "
             "no manual instrumentation needed."]
            ;; rf2-bclgj: surface the same `[● REDACTED]` hint the trace
            ;; panel renders so users on the actions panel see the
            ;; privacy-gate state too.
            (when (pos? redacted-count)
              [:div {:style     (:redact-note styles)
                     :data-test "story-actions-redact-note"}
               "[● REDACTED] " redacted-count " sensitive event"
               (when (> redacted-count 1) "s")
               " suppressed — set "
               [:code {:style {:color "#9cdcfe"}} ":trace/show-sensitive? true"]
               " to surface"])
            (if (zero? (count rows))
              [:div {:style (:empty styles)}
               "no actions yet — interact with the variant to see dispatches stream in"]
              [:div {:style     (:rows-host styles)
                     :data-test "story-actions-rows"}
               (for [row rows]
                 ^{:key (:id row)}
                 [action-row row])])])))))
