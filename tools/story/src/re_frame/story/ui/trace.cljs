(ns re-frame.story.ui.trace
  "Six-domino trace panel. Per Stage 4 (rf2-ekai) IMPL-SPEC §9.2.

  Each variant frame gets a trace listener registered at the moment the
  panel mounts. The listener filters events to the frame, classifies
  them into the six-domino bucket (event → handler → fx → effect →
  subscription → render), and appends to a per-variant ring buffer.

  The panel re-renders each time the buffer changes. The buffer is held
  in a Reagent ratom keyed by variant id, with a fixed retention cap so
  long-running variants don't leak memory.

  ## What 'six-domino' means here

  The classic re-frame six-domino cascade is:

    1. event dispatched
    2. event handler runs (the interceptor chain)
    3. effects emitted (fx map computed)
    4. effects executed (each fx handler runs)
    5. subscriptions recomputed (any signal that depended on the changed
       app-db)
    6. views re-render

  The trace bus emits structured events for each step (spec/009). This
  panel groups by `:dispatch-id` (Spec 009 §Dispatch correlation) so
  each cascade renders as one row with six sub-cells. Clicks on any
  handler row jump to the source location stamped by the registrar
  (spec/009 §Source-coord stamping).

  ## Listener lifecycle

  - `register-listener!` — install a trace cb under a stable id.
  - `remove-listener!`   — tear down the cb.

  Both are idempotent. The shell wires them on mount/unmount of the
  trace panel, so trace capture only runs while the panel is visible.

  ## Cross-reference with the scrubber (rf2-sxwvf)

  Per `spec/012-Trace-Scrubber-Cross-Ref.md` the trace panel
  cross-references the scrubber's current scrub. When the scrubber's
  selection is non-nil for the focused variant:

    - the buffer is filtered to events with `:id` ≤ the maximum trace
      event id stamped on the selected epoch (cascades whose traces all
      land at or before the epoch boundary stay; cascades emitted
      after the epoch settled fall away);
    - the cascade whose `:dispatch-id` matches the selected epoch's
      cascade-id is visually marked.

  The cross-reference is implemented as a pair of pure-data helpers
  (`filter-cascades-up-to` / `cascade-row-style`) so the same predicate
  runs under the JVM unit tests AND inside the CLJS render path."
  (:require [reagent.core :as r]
            ;; rf2-qwm0a — listener API lives in
            ;; `re-frame.trace.tooling` (production-DCE split).
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.trace.projection :as projection]
            [re-frame.story.config :as config]
            [re-frame.story.ui.scrubber :as scrubber]
            [re-frame.story.ui.scrubber-xref :as xref]))

;; ---- the per-variant trace buffer ----------------------------------------

(def ^:private max-events
  "How many trace events to retain per variant frame. Per Spec 009
  §Retain-N trace ring buffer the framework defaults to 200; the panel
  retains the same cap so the UI cost stays bounded."
  200)

(defonce buffers
  ;; {variant-id → r/atom holding a vector of trace events, newest-last}
  (atom {}))

(defn ensure-buffer!
  "Return the per-variant trace-buffer ratom, creating it on first
  access.  Public so sibling panels (e.g. the Actions panel per
  rf2-5yriz) can subscribe to the SAME ratom the six-domino trace
  panel reads from — both panels share the buffer."
  [variant-id]
  (or (get @buffers variant-id)
      (let [a (r/atom [])]
        (swap! buffers assoc variant-id a)
        a)))

(defn clear-buffer!
  "Drop the buffer for `variant-id` (or all buffers when no arg).
  Per rf2-bclgj: also clears the matching per-variant suppressed-
  events counter so the `[● REDACTED]` hint hides when the user
  resets the panel."
  ([]
   (doseq [a (vals @buffers)] (reset! a []))
   (config/reset-suppressed-count!)
   nil)
  ([variant-id]
   (when-let [a (get @buffers variant-id)]
     (reset! a []))
   (config/reset-suppressed-count! variant-id)
   nil))

;; ---- retroactive scrub on set-show-sensitive! false (rf2-lqmje) ---------
;;
;; Per Spec 009 §Privacy §Retroactive-scrub on `set-show-sensitive!`
;; false: toggling the flag from true → false clears every per-variant
;; trace buffer. Each Story trace listener only gates at ingest via
;; `config/suppress-sensitive?`, so without this hook a sensitive
;; cascade emitted while the flag was true would remain visible in
;; every variant's trace + actions panels after the user expected
;; privacy to be restored.
;;
;; The clear cascades through `clear-buffer!` (zero-arg form):
;;   - resets every per-variant ratom to `[]` (panels re-render empty),
;;   - calls `config/reset-suppressed-count!` so each variant's
;;     `[● REDACTED]` hint drops in lockstep with the buffer.
;;
;; The hook registers at load time, gated on `config/enabled?` —
;; production builds (`enabled?` false via `:closure-defines`) elide
;; Story entirely, including the registration form.

(when config/enabled?
  (config/register-toggle-off-callback! ::clear-on-toggle-off clear-buffer!))

(defn drop-buffer!
  "Remove the buffer entry entirely. Called from shell unmount.
  Per rf2-bclgj: also clears the per-variant suppressed-events
  counter so it doesn't leak across variant teardowns."
  [variant-id]
  (swap! buffers dissoc variant-id)
  (config/reset-suppressed-count! variant-id)
  nil)

(defn- append!
  "Append `ev` to `variant-id`'s buffer, evicting oldest entries to
  honour `max-events`."
  [variant-id ev]
  (let [a (ensure-buffer! variant-id)]
    (swap! a (fn [v]
               (let [v' (conj v ev)
                     n  (count v')]
                 (if (> n max-events)
                   (subvec v' (- n max-events))
                   v'))))))

;; ---- the trace listener --------------------------------------------------

(defn- listener-id [variant-id]
  (keyword "re-frame.story.ui.trace"
           (str "listener-" (when variant-id (str variant-id)))))

(defn- variant-event?
  "Filter predicate: true iff `ev` targets `variant-id`'s frame. The
  trace events emit `:frame` under `:tags`; events that have no frame
  scope (registry / global) match nothing. Per Spec 009 §Per-frame
  routing — `:frame` is the canonical tag key (rf2-shaa1 dropped the
  `:frame-id` alias from impl emit sites)."
  [variant-id ev]
  (= variant-id (get-in ev [:tags :frame])))

(defn register-listener!
  "Install a trace listener that appends every `variant-id`-scoped event
  into the per-variant buffer. Idempotent (re-registering replaces).
  Returns the listener id.

  Per Spec 009 §Privacy + rf2-bclgj: events whose `:sensitive?` flag
  is true are dropped from the buffer when the global
  `:trace/show-sensitive?` flag is false (the default). The
  suppressed-events counter bumps for the variant so the panel's
  `[● REDACTED]` hint can advertise that the buffer is shorter than
  the runtime's actual emit count. The actions panel reads from the
  same buffer (per rf2-5yriz), so the suppression cascades to it for
  free."
  [variant-id]
  (when config/enabled?
    (let [id (listener-id variant-id)]
      (trace-tooling/register-trace-cb! id
        (fn [ev]
          (when (variant-event? variant-id ev)
            (if (config/suppress-sensitive? ev)
              (config/note-suppressed! variant-id)
              (append! variant-id ev)))))
      id)))

(defn remove-listener!
  "Tear down the trace listener for `variant-id`. Idempotent."
  [variant-id]
  (when config/enabled?
    (trace-tooling/remove-trace-cb! (listener-id variant-id))
    nil))

;; ---- six-domino projection -----------------------------------------------
;;
;; Per rf2-wvzgd the bucketing + group-by logic lives in
;; `re-frame.trace.projection` so Story, Causa, and pair2 all consume
;; the same pure-data projection. Story previously carried its own
;; copy here with two latent bugs (an invented `:event/run` operation
;; and op-type/operation confusion on the fx, sub, view emits); the
;; framework version tracks Spec 009's actual op-type vocabulary and
;; surfaces non-six-domino events under `:other`. Consumers require
;; `re-frame.trace.projection` directly.

;; ---- cross-reference with the scrubber (rf2-sxwvf) -----------------------
;;
;; The pure-data helpers live in `re-frame.story.ui.scrubber-xref` so the
;; cross-reference predicates run under the JVM unit-test target
;; (`clojure -M:test`) — per `feedback_jvm_interop_must_work.md`. Story's
;; render path consults the scrubber's selection ratom (CLJS-only),
;; pipes the result of `epoch/epoch-history` + `group-cascades` into
;; the helpers, and lifts the visible subset into the row renderer.

(def filter-cascades-up-to            xref/filter-cascades-up-to)
(def cascade-matches-selected-epoch?  xref/cascade-matches-selected-epoch?)

;; ---- styling -------------------------------------------------------------

(def ^:private styles
  {:panel        {:padding "8px"
                  :font-family "monospace"
                  :font-size "11px"
                  :border-top "1px solid #444"
                  :background "#1e1e1e"
                  :color "#ddd"
                  :overflow "auto"
                  :max-height "240px"}
   :title        {:font-weight "bold"
                  :margin-bottom "6px"
                  :color "#9cdcfe"}
   :scrub-note   {:font-size "10px"
                  :color "#dcdcaa"
                  :font-style "italic"
                  :margin-bottom "4px"}
   ;; rf2-bclgj: the redaction hint surfaces when sensitive events have
   ;; been suppressed by the per-listener privacy gate. A muted red dot
   ;; + count + the literal `[● REDACTED]` token from the spec so the
   ;; user sees why the buffer is shorter than the cascade's actual
   ;; emit count.
   :redact-note  {:font-size "10px"
                  :color "#d16969"
                  :font-style "italic"
                  :margin-bottom "4px"}
   ;; rf2-fq1yg: longhand padding sides on every row. The `:row-selected`
   ;; variant below adds a `:padding-left`; mixing shorthand `:padding`
   ;; with longhand `:padding-left` on the same React element across
   ;; renders triggers React's reconcile warning ("Updating a style
   ;; property during rerender (paddingLeft) when a conflicting property
   ;; is set (padding) can lead to styling bugs"). Keep every side
   ;; longhand so React reconciles a stable set of keys.
   :row          {:display "grid"
                  :grid-template-columns "auto 1fr 1fr 1fr 1fr 1fr"
                  :gap "4px"
                  :padding-top "2px"
                  :padding-right "0"
                  :padding-bottom "2px"
                  :padding-left "0"
                  :border-bottom "1px dotted #333"}
   ;; rf2-sxwvf: the highlight ring marks the cascade whose post-effects
   ;; produced the currently-scrubbed epoch. A solid amber outline plus
   ;; a left border so the row pops without changing the column layout.
   :row-selected {:background "#2d2d1a"
                  :outline "1px solid #dcdcaa"
                  :border-left "3px solid #dcdcaa"
                  :padding-left "4px"}
   :cell         {:padding "2px 4px"
                  :overflow "hidden"
                  :text-overflow "ellipsis"
                  :white-space "nowrap"}
   :header       {:font-weight "bold" :color "#b0b0b0"}
   :event-cell   {:color "#dcdcaa"}
   :handler-cell {:color "#4ec9b0"}
   :fx-cell      {:color "#ce9178"}
   :effect-cell  {:color "#ce9178"}
   :sub-cell     {:color "#b5cea8"}
   :render-cell  {:color "#c586c0"}
   :empty        {:color "#9a9a9a" :font-style "italic"}})

;; ---- view ----------------------------------------------------------------

(defn cascade-row
  "Render one cascade as a six-column row. Per rf2-sxwvf, when
  `selected?` is true the row carries the highlight ring + a
  `data-selected=\"true\"` attribute so the browser test can assert the
  cross-reference fired."
  ([cascade] (cascade-row cascade false))
  ([{:keys [event handler fx effects subs renders] :as _cascade} selected?]
   [:div {:style (cond-> (:row styles)
                   selected? (merge (:row-selected styles)))
          :data-test "story-trace-cascade-row"
          :data-selected (if selected? "true" "false")
          :title (when-let [src (:source handler)]
                   (str (:file src) ":" (:line src)))}
    [:span {:style (merge (:cell styles) (:event-cell styles))}
     (pr-str (first event))]
    [:span {:style (merge (:cell styles) (:handler-cell styles))}
     (if handler
       (str "ran in " (or (get-in handler [:tags :duration-ms]) "?") "ms")
       "—")]
    [:span {:style (merge (:cell styles) (:fx-cell styles))}
     (if fx (str (count (get-in fx [:tags :fx] {})) " fx") "—")]
    [:span {:style (merge (:cell styles) (:effect-cell styles))}
     (str (count effects) " effects")]
    [:span {:style (merge (:cell styles) (:sub-cell styles))}
     (str (count subs) " subs")]
    [:span {:style (merge (:cell styles) (:render-cell styles))}
     (str (count renders) " renders")]]))

(defn panel
  "The trace panel component. Subscribes to the variant's trace buffer
  and renders each cascade as a row.

  Mounts a listener via `register-listener!` on first render; tears down
  via `remove-listener!` when the shell unmounts (handled by
  `re-frame.story.ui.shell`).

  Per rf2-sxwvf, on each render the panel derefs the scrubber's
  `selection` ratom for the same variant. When non-nil, the buffer is
  filtered to events at-or-before the selected epoch and the cascade
  whose post-effects produced that epoch is visually marked."
  [variant-id]
  (let [buffer         (ensure-buffer! variant-id)
        selection-atom (scrubber/ensure-selection-atom! variant-id)]
    (fn [variant-id]
      (let [events             @buffer
            selected-epoch     @selection-atom
            cap                (when selected-epoch
                                 (scrubber/max-trace-event-id-for-epoch
                                   variant-id selected-epoch))
            selected-cascade   (when selected-epoch
                                 (scrubber/cascade-id-for-epoch
                                   variant-id selected-epoch))
            all-cascades       (projection/group-cascades events)
            visible-cascades   (filter-cascades-up-to all-cascades cap)
            hidden-by-scrub    (- (count all-cascades)
                                  (count visible-cascades))
            ;; rf2-bclgj: read the per-variant suppressed-events counter
            ;; on every render. The atom is plain (not a Reagent ratom),
            ;; so the dereference doesn't drive reactivity; the buffer's
            ;; reactive deref above is what re-runs the render — every
            ;; suppressed event also runs the per-listener body so the
            ;; counter is up to date the moment we read it.
            redacted-count     (config/suppressed-count variant-id)]
        ;; Per rf2-xc65: the panel wrap is scrollable (`overflow auto`
        ;; + `max-height 240px`). `tab-index "0"` + an aria-label make
        ;; it keyboard-focusable and named so axe-core's
        ;; `scrollable-region-focusable` rule passes.
        [:div {:style      (:panel styles)
               :role       "region"
               :aria-label "Trace cascades"
               :tab-index  "0"
               :data-test  "story-trace-panel"
               :data-scrubbed-epoch (when selected-epoch (str selected-epoch))
               :data-redacted-count (str redacted-count)}
         [:div {:style (:title styles)}
          "Trace " (when variant-id (str (pr-str variant-id))) " — "
          (count events) " events, " (count visible-cascades) " cascades"]
         ;; rf2-sxwvf: when a scrub is active, surface the cross-reference
         ;; state so the user sees why some cascades dropped out.
         (when selected-epoch
           [:div {:style (:scrub-note styles)
                  :data-test "story-trace-scrub-note"}
            "scrubbed to epoch " (str selected-epoch)
            (when (pos? hidden-by-scrub)
              (str " — " hidden-by-scrub " later cascade"
                   (when (> hidden-by-scrub 1) "s") " hidden"))])
         ;; rf2-bclgj: when sensitive events have been suppressed, render
         ;; the `[● REDACTED]` indicator so the user knows the buffer is
         ;; shorter than the runtime's actual emit count. The number
         ;; comes from the per-listener suppression counter.
         (when (pos? redacted-count)
           [:div {:style (:redact-note styles)
                  :data-test "story-trace-redact-note"}
            "[● REDACTED] " redacted-count " sensitive event"
            (when (> redacted-count 1) "s")
            " suppressed — set "
            [:code {:style {:color "#9cdcfe"}} ":trace/show-sensitive? true"]
            " to surface"])
         [:div {:style (merge (:row styles) (:header styles))}
          [:span {:style (:cell styles)} "event"]
          [:span {:style (:cell styles)} "handler"]
          [:span {:style (:cell styles)} "fx"]
          [:span {:style (:cell styles)} "effects"]
          [:span {:style (:cell styles)} "subs"]
          [:span {:style (:cell styles)} "renders"]]
         (if (empty? visible-cascades)
           [:div {:style (:empty styles)}
            (if selected-epoch
              "no cascades at or before the selected epoch"
              "no cascades captured yet — dispatch an event to see the six dominos")]
           (for [c visible-cascades]
             ^{:key (:dispatch-id c)}
             [cascade-row c (cascade-matches-selected-epoch? c selected-cascade)]))]))))
