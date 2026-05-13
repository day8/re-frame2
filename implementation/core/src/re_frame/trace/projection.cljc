(ns re-frame.trace.projection
  "Six-domino cascade projection over the raw trace event stream.

  Per Spec 009 §Subscription / consumption, the trace stream is
  event-at-a-time. Pair-shaped tools (Story's trace panel, Causa's
  event-detail panel and causality graph, re-frame-pair2's
  `cascade-of`) all want the same higher-level shape — one row per
  cascade with the six-domino slots already populated. This namespace
  ships that projection as a pure data function so each tool can stop
  re-implementing it.

  The projection is **pure data** — no atoms, no interop, no reagent.
  Runs on the JVM, the JVM-CLJS REPL, and in production traces lifted
  out of a session. The function is the only correlation primitive
  consumers need above the raw stream for the common 'render this
  cascade as six dominoes' use case.

  Per rf2-g6ih4 (cascade-wide `:dispatch-id`) the projection is robust
  against events that the framework emits *inside* a drain even though
  they aren't `:event/dispatched` — fx invocations, sub-runs, renders,
  errors. Every such event now carries `:tags :dispatch-id` so the
  group-by below assembles a complete cascade record.

  ## Bucketing (per Spec 009 §`:op-type` vocabulary)

  Six dominoes:

    1. `:event`    — `:op-type :event` + `:operation :event/dispatched`
                     (cascade-root marker; bucket value is the event vector)
    2. `:handler`  — `:op-type :event` + `:operation :event`
                     (the handler ran; tags carry `:phase :run-start` / `:run-end`)
    3. `:fx`       — `:op-type :event` + `:operation :event/do-fx`
                     (effects map computed and about to be walked)
    4. `:effect`   — `:op-type :fx` (any `:operation` — `:rf.fx/handled`,
                     `:rf.fx/override-applied`, `:rf.fx/skipped-on-platform`)
    5. `:sub`      — `:op-type :sub/run` or `:sub/create`
    6. `:render`   — `:op-type :view` + `:operation :view/render`

  Events whose op-type/operation pair doesn't fit any bucket flow
  through `:other` so the cascade-record shape is fully accountable to
  the input — `(reduce + 0 (map #(+ (count (:effects %)) ...) ...))`
  equals the input event count when every event is grouped.

  ## Future hooks

  - Causa (rf2-5aw5v) will consume `group-cascades` in its event-detail
    panel and causality-graph node renderer; the `:ungrouped` slot covers
    free-floating traces (e.g. registry events emitted at app boot).
  - re-frame-pair2's `cascade-of` MCP op currently walks
    `:event/dispatched` traces in a slimmer form; it migrates to this
    projection so 'show me every fx in this cascade' becomes one slice
    of the returned record.")

#?(:clj (set! *warn-on-reflection* true))

;; ---- bucketing ------------------------------------------------------------

(def ^:private effect-op-types
  "Op-types that classify as the fourth domino — fx invocation. The
  framework emits `:op-type :fx` for every fx handler invocation
  (`:rf.fx/handled`), every override (`:rf.fx/override-applied`), and
  every platform-skip (`:rf.fx/skipped-on-platform`)."
  #{:fx})

(def ^:private sub-op-types
  "Op-types that classify as the fifth domino — subscription work.
  `:sub/run` is the recompute path; `:sub/create` is the first-time
  signal-graph build."
  #{:sub/run :sub/create})

(defn domino-bucket
  "Classify a trace event into one of the six domino buckets.

  Returns one of `#{:event :handler :fx :effect :sub :render :other}`.
  `:other` is returned for events that don't fit a domino slot (errors,
  warnings, machine transitions, frame lifecycle, flows — every event
  Spec 009 documents that is **not** part of the six-domino cascade).

  The classification is total: every input maps to exactly one bucket."
  [{:keys [op-type operation]}]
  (cond
    (= op-type :event)
    (case operation
      :event/dispatched :event
      :event            :handler
      :event/do-fx      :fx
      :other)

    (contains? effect-op-types op-type) :effect
    (contains? sub-op-types op-type)    :sub

    (and (= op-type :view) (= operation :view/render)) :render

    :else :other))

;; ---- cascade record -------------------------------------------------------

(def ^:private empty-cascade
  "Slot template for a cascade record. Per-cascade reduction starts here;
  every key the consumer can rely on lives in the template."
  {:dispatch-id nil
   :event       nil
   :handler     nil
   :fx          nil
   :effects     []
   :subs        []
   :renders     []
   :other       []})

(defn- cascade-id
  "Extract the cascade identifier from an event. Per Spec 009 §Dispatch
  correlation, `:dispatch-id` is cascade-wide. For `:event/dispatched`
  events, `:parent-dispatch-id` documents inter-cascade lineage; for
  pair-shaped tools assembling 'the cascade caused by THAT dispatch',
  the event's own `:dispatch-id` is the right key (the
  `:event/dispatched` event rides under its own cascade's id). Events
  outside any drain (registry-time, frame-creation) carry no
  `:dispatch-id` — they land in the `:ungrouped` bucket."
  [ev]
  (or (get-in ev [:tags :dispatch-id])
      :ungrouped))

(defn- absorb
  "Fold one trace event into the per-cascade accumulator."
  [acc ev]
  (case (domino-bucket ev)
    :event   (assoc acc :event (get-in ev [:tags :event]))
    :handler (assoc acc :handler ev)
    :fx      (assoc acc :fx ev)
    :effect  (update acc :effects conj ev)
    :sub     (update acc :subs conj ev)
    :render  (update acc :renders conj ev)
    :other   (update acc :other conj ev)))

(defn- first-id
  "Lowest `:id` among the cascade's events, or `##Inf` when no event
  carries an id. Used for sorting cascades into emission order."
  [{:keys [event handler fx effects subs renders other]}]
  (let [all (concat (when handler [handler])
                    (when fx [fx])
                    effects subs renders other)
        ids (keep :id all)]
    (if (seq ids)
      (apply min ids)
      ;; Sentinel for cascades with no event carrying an id — sort to
      ;; the end. Use a number-shaped value larger than any practical
      ;; per-process trace id; CLJS-portable (no Long/MAX_VALUE).
      #?(:clj  Long/MAX_VALUE
         :cljs js/Number.MAX_SAFE_INTEGER))))

(defn group-cascades
  "Project a sequence of raw trace events into one cascade record per
  `:dispatch-id`. Pure data — JVM and CLJS.

  Returns a vector of maps shaped:

      {:dispatch-id <cascade-id-or-:ungrouped>
       :event       <event-vector or nil>     ;; from :event/dispatched
       :handler     <trace-event or nil>      ;; the :run-end emit (last wins)
       :fx          <trace-event or nil>      ;; :event/do-fx
       :effects     [<trace-event> ...]       ;; :op-type :fx
       :subs        [<trace-event> ...]       ;; :sub/run + :sub/create
       :renders     [<trace-event> ...]       ;; :view/render
       :other       [<trace-event> ...]}      ;; everything else
                                              ;;   (errors, warnings,
                                              ;;   machine, frame,
                                              ;;   flow, etc.)

  Events without a `:dispatch-id` (registry-time emits, frame
  lifecycle outside a drain, REPL evals) collect under
  `:dispatch-id :ungrouped`. The returned vector is sorted by the
  lowest `:id` in each cascade, so cascades render in emission order.

  Stable / additive: future framework op-types that don't fit a
  domino slot will surface under `:other` automatically. Tools that
  want richer projections of `:other` can call `domino-bucket`
  directly on each event."
  [events]
  (let [groups (group-by cascade-id events)]
    (->> groups
         (map (fn [[dispatch-id evs]]
                (reduce absorb
                        (assoc empty-cascade :dispatch-id dispatch-id)
                        evs)))
         (sort-by first-id)
         vec)))
