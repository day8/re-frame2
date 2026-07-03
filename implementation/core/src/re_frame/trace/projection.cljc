(ns re-frame.trace.projection
  "Six-domino cascade projection over the raw trace event stream.

  Per Spec 009 §Subscription / consumption, the trace stream is
  event-at-a-time. Pair-shaped tools (Story's trace panel, Xray's
  event-detail panel and causality graph, re-frame2-pair's
  `cascade-of`) all want the same higher-level shape — one row per
  cascade with the six-domino slots already populated. This namespace
  ships that projection as a pure data function so each tool can stop
  re-implementing it.

  The projection is **pure data** — no atoms, no interop, no reagent.
  Runs on the JVM, the JVM-CLJS REPL, and in production traces lifted
  out of a session. The function is the only correlation primitive
  consumers need above the raw stream for the common 'render this
  cascade as six dominoes' use case.

  The run-wide `:rf.trace/dispatch-id` makes the projection robust
  against events the framework emits *inside* a drain even though they
  aren't `:rf.event/dispatched` — fx invocations, sub-runs, renders,
  errors. Every such event carries `:tags :rf.trace/dispatch-id` so the
  group-by below assembles a complete event bundle.

  ## Bucketing (per Spec 009 §`:op-type` vocabulary)

  Six dominoes:

    1. `:event`    — `:op-type :rf.event` + `:operation :rf.event/dispatched`
                     (cascade-root marker; bucket value is the event vector)
    2. `:handler`  — `:op-type :rf.event` + `:operation :rf.event/run-start`
                     or `:rf.event/run-end` (the cascade run markers; both
                     also carry the redundant `:rf.trace/phase :run-start` /
                     `:run-end` tag — `:operation` is the discriminator)
    3. `:fx`       — `:op-type :rf.fx` + `:operation :rf.fx/do-fx`
                     (effects map computed and about to be walked)
    4. `:effect`   — `:op-type :rf.fx` (any other `:operation` —
                     `:rf.fx/handled`, `:rf.fx/override-applied`,
                     `:rf.fx/skipped-on-platform`)
    5. `:sub`      — `:op-type :rf.sub` (`:operation :rf.sub/run` recompute,
                     `:rf.sub/skip` memo-hit, or `:rf.sub/create` first-time
                     signal-graph build)
    6. `:render`   — `:op-type :rf.view` + `:operation :rf.view/render`

  Events whose op-type/operation pair doesn't fit any bucket flow
  through `:other` so the event-bundle shape is fully accountable to
  the input — `(reduce + 0 (map #(+ (count (:effects %)) ...) ...))`
  equals the input event count when every event is grouped.

  ## Future hooks

  - Xray (rf2-5aw5v) will consume `group-by-event` in its event-detail
    panel and causality-graph node renderer; the `:ungrouped` slot covers
    free-floating traces (e.g. registry events emitted at app boot).
  - re-frame2-pair's `cascade-of` MCP op currently walks
    `:rf.event/dispatched` traces in a slimmer form; it migrates to this
    projection so 'show me every fx in this cascade' becomes one slice
    of the returned record.")

#?(:clj (set! *warn-on-reflection* true))

;; ---- bucketing ------------------------------------------------------------

(def ^:private effect-op-types
  "Op-types that classify as the fourth domino — fx invocation. The
  framework emits `:op-type :rf.fx` for every fx handler invocation
  (`:rf.fx/handled`), every override (`:rf.fx/override-applied`), and
  every platform-skip (`:rf.fx/skipped-on-platform`)."
  #{:rf.fx})

(def ^:private sub-op-types
  "Op-types that classify as the fifth domino — subscription work.
  `:rf.sub/run` is the recompute path; `:rf.sub/create` is the first-time
  signal-graph build."
  #{:rf.sub})

(defn domino-bucket
  "Classify a trace event into one of the six domino buckets.

  Returns one of `#{:event :handler :fx :effect :sub :render :other}`.
  `:other` is returned for events that don't fit a domino slot (errors,
  warnings, machine transitions, frame lifecycle, flows — every event
  Spec 009 documents that is **not** part of the six-domino cascade).

  The classification is total: every input maps to exactly one bucket."
  [{:keys [op-type operation]}]
  (cond
    (= op-type :rf.event)
    (case operation
      :rf.event/dispatched :event
      :rf.event/run-start  :handler
      :rf.event/run-end    :handler
      :other)

    ;; `:rf.fx/do-fx` is the third domino (the effects-resolution pass
    ;; boundary marker, folded into the fx family per Spec 009 §`:op-type`
    ;; vocabulary); every other `:rf.fx` op (`:rf.fx/handled`,
    ;; `:rf.fx/override-applied`, `:rf.fx/skipped-on-platform`) is the
    ;; fourth domino — fx invocation.
    (= operation :rf.fx/do-fx) :fx
    (contains? effect-op-types op-type) :effect
    (contains? sub-op-types op-type)    :sub

    (and (= op-type :rf.view) (= operation :rf.view/render)) :render

    :else :other))

;; ---- event bundle ---------------------------------------------------------

(def ^:no-doc empty-event-bundle
  "Slot template for an event bundle. Per-run reduction starts here;
  every key the consumer can rely on lives in the template.

  `^:no-doc` public so `re-frame.trace.tooling/event-bundle` folds the
  per-frame ring's events with `absorb` over this same template rather
  than re-inlining the slot set + the bucketing cond a second time
  (rf2-ih437c). Both nss are dev-side and bundle-isolated from production
  CLJS; this ns carries no requires, so the new tooling→projection edge
  introduces no cycle and no production-bundle leak.

  `:event` is the dispatched event VECTOR (the convenient
  slim form most consumers need). `:dispatched` is the full
  `:rf.event/dispatched` trace EVENT — preserved so consumers (Xray's
  Event lens) can read top-level hoisted slots like
  `:rf.trace/call-site` (per rf2-twt7m Change 1) without reaching
  back into the raw trace buffer."
  {:dispatch-id        nil
   :parent-dispatch-id nil
   :frame              nil
   :event              nil
   :dispatched         nil
   :handler            nil
   :fx                 nil
   :effects            []
   :subs               []
   :renders            []
   :other              []})

(def ^:private default-frame :rf/default)

(defn- dispatch-id
  "Return an event's concrete dispatch id, or nil when the event was
  emitted outside a dispatch run."
  [ev]
  (get-in ev [:tags :rf.trace/dispatch-id]))

(defn- bundle-id
  "Extract the event-bundle grouping identifier from an event. Per Spec
  009 §Dispatch correlation, `:rf.trace/dispatch-id` is run-wide (shared
  by every trace event of one pipeline run). For `:rf.event/dispatched`
  events, `:rf.trace/parent-dispatch-id` documents inter-run lineage; for
  pair-shaped tools assembling 'the run caused by THAT dispatch', the
  event's own `:rf.trace/dispatch-id` is the right key (the
  `:rf.event/dispatched` event rides under its own run's id). Events
  outside any drain (registry-time, frame-creation) carry no
  `:rf.trace/dispatch-id` — they land in the `:ungrouped` bucket."
  [ev]
  (or (dispatch-id ev) :ungrouped))

(defn- frame-index
  "Map dispatch ids to the frames explicitly seen for that dispatch.
  Frame-less events inside a run can then join the only known frame
  deterministically without merging ambiguous multi-frame dispatch ids."
  [events]
  (reduce (fn [acc ev]
            (if-let [id (dispatch-id ev)]
              (if-let [frame (or (get-in ev [:tags :frame]) (:frame ev))]
                (update acc id (fnil conj #{}) frame)
                acc)
              acc))
          {}
          events))

(defn- bundle-frame
  "Extract the host frame from an event. nil means the event is not
  frame-qualified (registry-time, boot-time, or older traces). Older
  dispatch-scoped traces that predate explicit frame tags are treated as
  default-frame traces so errors/warnings still ride with their run."
  [frame-index ev]
  (or (get-in ev [:tags :frame])
      (:frame ev)
      (when-let [id (dispatch-id ev)]
        (let [frames (get frame-index id)]
          (when (= 1 (count frames))
            (first frames))))
      (when (dispatch-id ev)
        default-frame)))

(defn- bundle-key
  "The stable grouping key for an event bundle. Dispatch ids are only
  unique inside a frame in the portable contract, so frame-qualified
  traces group by `[frame dispatch-id]`. Traces without a dispatch-id are
  not runs and share the historical ungrouped bucket regardless of
  frame lifecycle metadata."
  [frame-index ev]
  (let [id (bundle-id ev)]
    (if (= :ungrouped id)
      [nil :ungrouped]
      [(bundle-frame frame-index ev) id])))

(defn ^:no-doc absorb
  "Fold one trace event into the per-run event-bundle accumulator.

  The `:event` bucket lands the event VECTOR on `:event` (slim,
  consumers' common case) AND the full trace event on `:dispatched`
  (preserves top-level hoisted slots like `:rf.trace/call-site` per
  rf2-twt7m Change 1).

  `^:no-doc` public so `re-frame.trace.tooling/event-bundle` reuses
  this same fold (over `empty-event-bundle`) rather than re-inlining the
  six-domino classification cond a third time (rf2-ih437c). Any extra
  keys the caller seeds the accumulator with (e.g. tooling's
  `:trace-events`) are preserved untouched — `absorb` only writes the
  domino slots."
  [acc ev]
  (case (domino-bucket ev)
    :event   (assoc acc :event              (get-in ev [:tags :rf.event/v])
                       :dispatched         ev
                       ;; Surface the causal-parent link once at the
                       ;; projection (Spec 009 §Dispatch correlation /
                       ;; rf2-ryri7): the `:rf.event/dispatched` trace
                       ;; carries `:rf.trace/parent-dispatch-id` (the
                       ;; in-flight run that emitted this dispatch —
                       ;; an `:fx :dispatch` parent, a machine-internal
                       ;; dispatch, etc.). Under epoch-per-event every
                       ;; child event is its own run; this slot is
                       ;; the only edge linking a run to its spawning
                       ;; run. Consumers (Xray typed pills, the
                       ;; causality breadcrumb) walk it to relate a
                       ;; run to its causal ancestors. nil for a
                       ;; root run (a user / external dispatch).
                       :parent-dispatch-id (get-in ev [:tags :rf.trace/parent-dispatch-id]))
    :handler (assoc acc :handler ev)
    :fx      (assoc acc :fx ev)
    :effect  (update acc :effects conj ev)
    :sub     (update acc :subs conj ev)
    :render  (update acc :renders conj ev)
    :other   (update acc :other conj ev)))

(defn- first-id
  "Lowest `:id` among the event bundle's events, or `##Inf` when no event
  carries an id. Used for sorting event bundles into emission order."
  [{:keys [event handler fx effects subs renders other]}]
  (let [all (concat (when handler [handler])
                    (when fx [fx])
                    effects subs renders other)
        ids (keep :id all)]
    (if (seq ids)
      (apply min ids)
      ;; Sentinel for event bundles with no event carrying an id — sort to
      ;; the end. Use a number-shaped value larger than any practical
      ;; per-process trace id; CLJS-portable (no Long/MAX_VALUE).
      #?(:clj  Long/MAX_VALUE
         :cljs js/Number.MAX_SAFE_INTEGER))))

(defn- grouped-event-bundles
  "The single grouping pass shared by `group-by-event` and
  `group-by-event-with-events`. Groups `events` by the stable
  `[frame dispatch-id]` `bundle-key` (dispatch ids are unique only
  within a frame — see `bundle-key`), reduces each group into an event
  bundle, and returns a vector of `[[frame dispatch-id] evs record]`
  triples sorted into emission order (lowest `:id` per run first).

  Both public projections consume this so the grouping key never drifts
  between them: `group-by-event` keeps the slim record; the
  `-with-events` sibling additionally attaches `evs` as `:trace-events`."
  [events]
  (let [index  (frame-index events)
        groups (group-by #(bundle-key index %) events)]
    (->> groups
         (map (fn [[[frame dispatch-id :as key] evs]]
                [key
                 evs
                 (reduce absorb
                         (assoc empty-event-bundle
                                :dispatch-id dispatch-id
                                :frame frame)
                         evs)]))
         (sort-by (fn [[_key _evs record]] (first-id record)))
         vec)))

(defn group-by-event
  "Project a sequence of raw trace events into one event-bundle record
  per `:rf.trace/dispatch-id` (one dequeued event / pipeline run). Pure
  data — JVM and CLJS.

  Returns a vector of maps shaped:

      {:dispatch-id <dispatch-id-or-:ungrouped>
       :parent-dispatch-id <dispatch-id or nil> ;; causal-parent link from
                                              ;;   :rf.trace/parent-dispatch-id
                                              ;;   (the run that emitted
                                              ;;   this dispatch); nil for a
                                              ;;   root / external dispatch
       :frame       <frame-id-or-nil>
       :event       <event-vector or nil>     ;; from :rf.event/dispatched :tags
       :dispatched  <trace-event or nil>      ;; the full :rf.event/dispatched
                                              ;;   trace event (top-level
                                              ;;   :rf.trace/call-site,
                                              ;;   :source, :origin per
                                              ;;   rf2-twt7m Change 1)
       :handler     <trace-event or nil>      ;; the :run-end emit (last wins)
       :fx          <trace-event or nil>      ;; :rf.fx/do-fx
       :effects     [<trace-event> ...]       ;; :op-type :rf.fx
       :subs        [<trace-event> ...]       ;; :rf.sub/run + :rf.sub/skip
                                              ;;   + :rf.sub/create
       :renders     [<trace-event> ...]       ;; :rf.view/render
       :other       [<trace-event> ...]}      ;; everything else
                                              ;;   (errors, warnings,
                                              ;;   machine, frame,
                                              ;;   flow, etc.)

  Events without a `:rf.trace/dispatch-id` tag (registry-time emits,
  frame lifecycle outside a drain, REPL evals) collect under the
  projection's `:dispatch-id :ungrouped` slot. The returned vector is
  sorted by the lowest `:id` in each run, so runs render in
  emission order.

  Stable / additive: future framework op-types that don't fit a
  domino slot will surface under `:other` automatically. Tools that
  want richer projections of `:other` can call `domino-bucket`
  directly on each event."
  [events]
  (->> (grouped-event-bundles events)
       (mapv (fn [[_key _evs record]] record))))

(defn group-by-event-with-events
  "Like `group-by-event`, but each record additionally carries a
  `:trace-events` slot holding the VECTOR of raw trace events that
  composed that event bundle. The same `[frame dispatch-id]` grouping
  that `group-by-event` uses is reused verbatim — the `:trace-events`
  slot is the exact set of events the record was reduced from, in input
  order.

  This is the correct projection for consumers that need both the
  six-domino record AND the raw events of a pipeline run per the portable
  trace contract — notably re-frame2-pair's streaming event bundles,
  whose wire shape mirrors `(re-frame.trace.tooling/trace-buffer frame)`.
  Such consumers MUST NOT re-derive the grouping with a weaker key
  (`:rf.trace/dispatch-id` alone): dispatch ids are unique only WITHIN a
  frame (see `bundle-key`), so a dispatch-id-only group-by merges two
  same-id runs from different frames and attaches each the UNION of
  both frames' raw events. Keying by `[frame dispatch-id]` here keeps
  every record's `:trace-events` scoped to its own frame.

  `group-by-event` is deliberately left slim — its seven-slot shape is
  pinned and Xray-consumed; this sibling carries the extra `:trace-events`
  slot for the consumers that need it. Returns a vector sorted by
  emission order, identical to `group-by-event`."
  [events]
  (->> (grouped-event-bundles events)
       (mapv (fn [[_key evs record]]
               (assoc record :trace-events (vec evs))))))
