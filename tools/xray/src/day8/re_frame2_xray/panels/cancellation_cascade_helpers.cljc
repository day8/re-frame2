(ns day8.re-frame2-xray.panels.cancellation-cascade-helpers
  "Pure-data helpers for Xray's Cancellation-cascade visualiser
  (rf2-59e7k, parent rf2-5aw5v).

  ## What this is

  Per `tools/xray/spec/019-Cross-Cutting-Insight.md` §M.3 — when a
  parent machine destroys a child (`:spawn` exit, `:after` fire,
  `:spawn-all` join resolution, explicit `[:rf.machine/destroy <id>]`,
  or parent-frame teardown), every in-flight `:rf.http/managed` request
  the child held aborts. Each abort emits a
  `:rf.http/aborted-on-actor-destroy` trace event (per Spec 014 §Abort
  on actor destroy / rf2-wvkn). In today's Trace tab these scatter
  through the firehose alongside the `:rf.machine.lifecycle/destroyed`
  emit and the `:rf.machine/destroyed` enrichment — devs cannot
  reconstruct which abort came from which destroy.

  This namespace folds those scattered trace events into ONE record:

      {:parent-decision  {:event-vec <vec> :t <ms> :machine-id <id>
                          :dispatch-id <id>}
       :child-teardowns  [{:child-id <id> :t <ms>
                           :reason   <keyword> :inflight-count <int>}
                          ...]
       :effect-aborts    [{:fx <:http | :ws | :after | :machine-invoke>
                           :req {<...>}  ;; per-fx detail
                           :t <ms>
                           :cancel-cause <keyword>  ;; :actor-destroyed etc
                           :request-id <id-or-nil>
                           :url <str-or-nil>
                           :actor-id <id-or-nil>
                           :correlation-id <id-or-nil>
                           :trace-id <id>           ;; the trace event :id
                           :dispatch-id <id-or-nil>}
                          ...]
       :total-elapsed-ms <int-or-nil>
       :empty-kind       <nil | :no-trigger | :no-aborts>}

  `:total-elapsed-ms` is the wall-clock span from the parent decision
  to the last abort (nil if either endpoint is missing). `:empty-kind`
  is `:no-trigger` when no parent decision could be located,
  `:no-aborts` when a decision exists but no abort traces ride with
  it.

  ## Why a separate .cljc

  Same dual-target pattern every other panel helper uses (the JVM
  test target drives the algebra without a CLJS runtime). The view in
  `cancellation_cascade.cljs` is a thin renderer over this record.

  ## Detection strategy

  The cascade pivots on a single anchor: a destroy trace event whose
  (channel, reason) tuple is BOTH emittable per the disjoint
  channel/reason matrix (`destroy-channel-reasons` below) AND a
  cancellation — `:rf.machine/destroyed` + `:explicit` (the fx-substrate
  teardown of a not-yet-final actor: imperative destroy, parent
  state-exit cascade, `:spawn-all` cancel-on-decision) or
  `:rf.machine.lifecycle/destroyed` + `:parent-frame-destroyed` (the
  registrar-substrate frame-exit reap). The anchor's
  `:dispatch-id` (when present) is the cascade boundary; aborts that
  share the same `:dispatch-id` (or land within a small wall-clock
  window of the anchor for the actor-destroy case where the abort
  emits run outside the originating drain) are gathered into the
  visualiser.

  The decision row (\"parent decision\") is the most-recent
  `:rf.event/dispatched` trace event within the cascade that the anchor
  belongs to — typically `[:auth/logout]`, `[:checkout/cancel]`, etc.

  Best-effort heuristic when the trace events lack the runtime tags
  we'd ideally read: fall back to a small wall-clock window
  (`+default-actor-destroy-window-ms+`) around the anchor; group every
  `:rf.http/aborted-on-actor-destroy` trace inside the window into the
  cascade. Divergence note: today's traces don't all carry
  `:cancel-cause`; we lift it off `:reason` / `:tags :reason` when
  available and default to `:actor-destroyed` for
  `:rf.http/aborted-on-actor-destroy` events (the canonical case).

  ## Frame scoping (rf2-y8doi.15)

  Xray's trace buffer is every host frame's ring MERGED, and a
  `:rf.trace/dispatch-id` is unique only WITHIN a frame (Spec 002 §Frame
  isolation). So both correlation paths above — the dispatch-id match and the
  wall-clock window — could reach into a foreign frame, and on a multi-frame
  host frame B's aborts folded into frame A's cascade. `extract-cascade` now
  takes an optional `:frame` on its focus map and scopes anchor, aborts,
  teardowns and the decision row to it; see `in-frame?` for the two escapes
  (no frame named, and an event carrying no frame tag at all). This is the
  frame-strict keying rf2-bz7flo gave the managed-fx and routing panels.

  ## What this does NOT do

    - Rendering — the view ns does the SVG/hiccup work.
    - Cross-frame causality — single-cascade-anchor scope only, and now
      enforced rather than merely documented when the focus names a frame.
    - Multi-anchor merging — each call returns ONE cascade. The subs
      pick which anchor to focus (focused-machine or focused-event)."
  (:require [clojure.string :as str]
            [day8.re-frame2-xray.panels.common-helpers :as common]))

;; ---- canonical operation sets -------------------------------------------

(def destroy-channel-reasons
  "The canonical channel/reason matrix — per Spec 009 §op-type
  vocabulary (`spec/009-Instrumentation.md`, the `:reason` enum table).

  The two destroy channels are **DISJOINT, not symmetric**. Each reason
  belongs to exactly ONE channel, so a (channel, reason) tuple absent
  from this map is one the runtime CANNOT emit:

    - `:rf.machine.lifecycle/destroyed` — the REGISTRAR-substrate
      channel. Fires only from the frame-exit cascade
      (`lifecycle_fx/frame_destroy.cljc`, or `frame.cljc` when the
      machines artefact is absent), always with the channel's sole
      reason `:parent-frame-destroyed`.
    - `:rf.machine/destroyed` — the FX-substrate channel
      (`lifecycle_fx/finalize.cljc` + `lifecycle_fx/destroy.cljc`).
      Carries every NON-frame-exit reason. Never fires for frame-exit
      reaping.

  A consumer that wants the complete \"an actor went away\" record must
  therefore read BOTH channels — neither alone is complete.

  `:actor-destroyed` is deliberately ABSENT: it is an HTTP/WS abort
  `:cancel-cause`, never a machine-destroy `:reason` — see `cancel-cause`
  below."
  {:rf.machine.lifecycle/destroyed #{:parent-frame-destroyed}
   :rf.machine/destroyed           #{:rf.machine/finished
                                     :explicit}})

(def ^:private destroy-operations
  "The destroy trace channels — the key set of the matrix above."
  (set (keys destroy-channel-reasons)))

(def ^:private abort-operations
  "Trace operations that signal an in-flight effect being cancelled.

    - `:rf.http/aborted-on-actor-destroy` (Spec 014 §Abort on actor destroy)
    - `:rf.http/aborted` (failure category). Every actor-destroy abort
      emits one of these too: the registry emits the row above, then calls
      the request's abort-fn with `:actor-destroyed`, and the transport's
      abort choke emits `:rf.http/aborted` `:reason :actor-destroyed` for
      the SAME request (rf2-3x7nj.23.4). `gather-related-aborts` folds that
      echo into its registry row, so one request counts once.
    - `:rf.ws/aborted-on-actor-destroy` (Pattern-WebSocket; defensive —
      not all installs ship this)
    - `:rf.machine.timer/cancelled` (per Spec 005 §after timer
      lifecycle; rf2-82a0u unified every cancellation path under this
      single event id with a closed `:reason` set covering exit /
      destroy / resolution / supersede / frame-destroy — the
      cascade-cancellation case the visualiser anchors on is
      `:reason :on-destroy`)
    - `:rf.machine.spawn/cancelled-on-join-resolution` (per Spec 005
      §Cancel-on-decision)"
  #{:rf.http/aborted-on-actor-destroy
    :rf.http/aborted
    :rf.ws/aborted-on-actor-destroy
    :rf.machine.timer/cancelled
    :rf.machine.spawn/cancelled-on-join-resolution})

(def ^:private cancellation-reasons
  "The subset of the matrix's reasons that classify a destroy as a
  CANCELLATION — the actor was torn down before reaching a `:final?`
  leaf. Spans both channels: `:parent-frame-destroyed` rides the
  registrar channel, `:explicit` the fx channel.

  Excluded (emittable, but NOT cancellations):

    - `:rf.machine/finished`   — natural termination; the actor closed
                                 its attempt through `:rf.machine/done`.
                                 This covers a `:spawn-all` join child
                                 too: completion is finality, so a folded
                                 child tore itself down there, and only
                                 SURVIVORS are cancelled at resolution.

  Membership here is necessary but NOT sufficient for an anchor — the
  (channel, reason) tuple must also be emittable. See
  `cancellation-anchor?`."
  #{:parent-frame-destroyed
    :explicit})

(def ^:const default-actor-destroy-window-ms
  "Best-effort wall-clock window (ms) around the anchor's `:time` for
  associating abort traces that lack a `:dispatch-id` link. Per the
  bead's divergence allowance: until the substrate stamps
  `:cancel-cause` + a back-link on every abort event, proximity is
  the structural fallback."
  100)

;; ---- predicates ---------------------------------------------------------

(defn destroy-event?
  "True iff `ev` names one of the two machine-destroy trace channels.
  Channel membership only — says nothing about whether the event's
  `:reason` is one that channel can carry. See `emittable-destroy?`."
  [ev]
  (and (map? ev)
       (contains? destroy-operations (:operation ev))))

(defn- destroy-reason
  "Lift the destroy `:reason` off a destroy trace event. Both emitters
  stamp it under `:tags :reason` (per Spec 009 §op-type vocabulary).
  nil when absent."
  [ev]
  (get-in ev [:tags :reason]))

(defn emittable-destroy?
  "True iff `ev` is a destroy trace event whose (channel, reason) tuple
  is one the runtime can ACTUALLY emit, per `destroy-channel-reasons`.

  This is the guard against reading a cross-product the disjoint
  channels forbid — lifecycle+`:explicit`, fx+`:parent-frame-destroyed`,
  or either channel carrying the HTTP-only `:actor-destroyed`. A destroy
  event failing this predicate is malformed (a hand-built fixture, a
  replay from a divergent runtime, or a genuine emitter bug); the
  cascade visualiser declines to anchor on it rather than draw a
  cascade the substrate could not have produced."
  [ev]
  (and (destroy-event? ev)
       (contains? (get destroy-channel-reasons (:operation ev) #{})
                  (destroy-reason ev))))

(defn abort-event?
  "True iff `ev` is an effect-abort trace event."
  [ev]
  (and (map? ev)
       (contains? abort-operations (:operation ev))))

(defn dispatched-event?
  "True iff `ev` is an `:rf.event/dispatched` trace event."
  [ev]
  (and (map? ev)
       (= :rf.event/dispatched (:operation ev))))

(defn cancellation-anchor?
  "True iff `ev` is an EMITTABLE destroy whose `:reason` marks it as a
  cancellation — i.e. both halves must hold:

    1. the (channel, reason) tuple is one the runtime emits, and
    2. that reason is a cancellation (not `:rf.machine/finished`).

  Checking the tuple rather than channel-membership and
  reason-membership independently is what keeps the impossible
  cross-products out of the visualiser."
  [ev]
  (and (emittable-destroy? ev)
       (contains? cancellation-reasons (destroy-reason ev))))

;; ---- fx-id classification -----------------------------------------------

(defn classify-fx
  "Classify an abort trace event into one of the abort-row :fx tags
  the visualiser displays. Pure fn of the event."
  [ev]
  (case (:operation ev)
    :rf.http/aborted-on-actor-destroy              :http
    :rf.http/aborted                               :http
    :rf.ws/aborted-on-actor-destroy                :ws
    :rf.machine.timer/cancelled                    :after
    :rf.machine.spawn/cancelled-on-join-resolution :machine-invoke
    :unknown))

;; ---- cancel-cause projection --------------------------------------------

(defn cancel-cause
  "Project the `:cancel-cause` for an abort trace event. Per the
  divergence allowance: the substrate may or may not stamp this slot
  today. We fall back to a structural default derived from the abort's
  identity:

    - `:rf.http/aborted-on-actor-destroy` → `:actor-destroyed`
    - `:rf.ws/aborted-on-actor-destroy`   → `:actor-destroyed`
    - `:rf.machine.timer/cancelled-*`     → `:join-resolved`
    - `:rf.machine.spawn/cancelled-*`    → `:join-resolved`
    - any other abort with `:reason` tag  → that reason
    - otherwise                           → `:unknown`"
  [ev]
  (or (get-in ev [:tags :cancel-cause])
      (get-in ev [:tags :reason])
      (case (:operation ev)
        :rf.http/aborted-on-actor-destroy              :actor-destroyed
        :rf.ws/aborted-on-actor-destroy                :actor-destroyed
        :rf.machine.timer/cancelled                    :join-resolved
        :rf.machine.spawn/cancelled-on-join-resolution :join-resolved
        :unknown)))

;; ---- row projection -----------------------------------------------------

(defn- abort-row
  "Build one abort-row from an abort trace event."
  [ev]
  (let [tags (:tags ev)]
    {:fx             (classify-fx ev)
     :req            (select-keys tags [:request-id :url :method
                                        :timer-id :child-id :spawned-id
                                        :invoke-id])
     :t              (:time ev)
     :cancel-cause   (cancel-cause ev)
     :request-id     (:request-id tags)
     :url            (:url tags)
     :actor-id       (or (:actor-id tags) (:machine-id tags) (:spawned-id tags))
     :correlation-id (or (:request-id tags) (:correlation-id tags))
     :trace-id       (:id ev)
     :dispatch-id    (:rf.trace/dispatch-id tags)}))

(defn- teardown-row
  "Build one child-teardown row from a destroy trace event."
  [ev]
  (let [tags (:tags ev)]
    {:child-id       (or (:actor-id tags) (:machine-id tags) (:spawned-id tags))
     :spawned-id     (:spawned-id tags)
     :parent-id      (:parent-id tags)
     :invoke-id      (:invoke-id tags)
     :t              (:time ev)
     :reason         (destroy-reason ev)
     :last-state     (:last-state tags)
     :inflight-count nil  ;; populated downstream from the gathered aborts
     :trace-id       (:id ev)
     :dispatch-id    (:rf.trace/dispatch-id tags)}))

(defn- decision-row
  "Build the parent-decision row from a `:rf.event/dispatched` trace
  event. nil when the input isn't a dispatched event."
  [ev]
  (when (dispatched-event? ev)
    (let [tags (:tags ev)]
      {:event-vec   (:rf.event/v tags)
       :t           (:time ev)
       :machine-id  (or (:machine-id tags) (:handler-id tags))
       :dispatch-id (:rf.trace/dispatch-id tags)
       :trace-id    (:id ev)})))

;; ---- cascade extraction -------------------------------------------------

(defn in-frame?
  "Is `ev` in scope for a cascade being extracted under `frame` (rf2-y8doi.15)?

  Dispatch ids are unique only WITHIN a frame (Spec 002 §Frame isolation; the
  trace projection groups event-bundles by `[frame dispatch-id]` and emits two
  records for a cross-frame id collision), and Xray's trace buffer is every
  host frame's ring MERGED. So on a multi-frame host the dispatch-id match and
  the 100 ms wall-clock window below both reached into a FOREIGN frame: frame
  B's aborts folded into frame A's cascade, or the popover anchored on the
  wrong frame's destroy. This is the frame-strict keying rf2-bz7flo applied to
  the managed-fx and routing panels, which the cascade never got.

  Two deliberate escapes, both nil:

    - a nil `frame` means the CALLER named none (no focus, a machine-id
      focus, a pre-frame-set focus) — every event is in scope, i.e. exactly
      the behaviour before this gate;
    - an event carrying no `[:tags :frame]` is UNATTRIBUTABLE, not foreign.
      `[:tags :frame]` is the single canonical raw-event frame path and the
      key the framework's own ring routing uses, but a frameless emit reaches
      Xray through the listener path and never carried one. Dropping those
      would delete rows that belong to no frame at all — and the actor-destroy
      abort the wall-clock fallback exists FOR is precisely the emit that
      fires outside the originating drain."
  [frame ev]
  (or (nil? frame)
      (let [f (get-in ev [:tags :frame])]
        (or (nil? f) (= frame f)))))

(defn- find-anchor
  "Locate the cascade anchor in `trace-buffer`. The anchor is either:

    1. The destroy event matching `focus-id` (when `focus-kind`
       = `:machine-id`).
    2. The most-recent destroy event in the cascade identified by
       `focus-id` (when `focus-kind` = `:dispatch-id`).
    3. The most-recent cancellation-anchor in the buffer (when
       `focus-kind` = nil — the 'just show me the latest cascade' path).

  Candidates are restricted to `frame` when the focus names one — see
  `in-frame?`. Returns the trace event map, or nil when no anchor can be
  found."
  [trace-buffer focus-kind focus-id frame]
  (let [evs (filter #(in-frame? frame %) (or trace-buffer []))]
    (case focus-kind
      :machine-id
      (->> evs
           (filter #(and (cancellation-anchor? %)
                         (let [tags (:tags %)]
                           (or (= focus-id (:actor-id tags))
                               (= focus-id (:machine-id tags))
                               (= focus-id (:spawned-id tags))
                               (= focus-id (:parent-id tags))))))
           (sort-by :time)
           last)

      :dispatch-id
      (->> evs
           (filter #(and (cancellation-anchor? %)
                         (= focus-id (get-in % [:tags :rf.trace/dispatch-id]))))
           (sort-by :time)
           last)

      ;; default: latest cancellation-anchor in the buffer
      (->> evs
           (filter cancellation-anchor?)
           (sort-by :time)
           last))))

(defn- actor-destroy-echo?
  "True iff `ev` is the transport-side `:rf.http/aborted` echo of an
  `:rf.http/aborted-on-actor-destroy` row in `registry-rows` — the SAME
  request aborted once, traced twice (rf2-3x7nj.23.4). The echo carries
  `:reason :actor-destroyed` and the registry row's `:actor-id`; the
  `:request-id` refines the match when both rows carry one."
  [registry-rows ev]
  (and (= :rf.http/aborted (:operation ev))
       (= :actor-destroyed (get-in ev [:tags :reason]))
       (let [{:keys [actor-id request-id]} (:tags ev)]
         (some (fn [reg]
                 (let [reg-tags (:tags reg)]
                   (and (= actor-id (:actor-id reg-tags))
                        (or (nil? request-id)
                            (nil? (:request-id reg-tags))
                            (= request-id (:request-id reg-tags))))))
               registry-rows))))

(defn- gather-related-aborts
  "Pull every abort trace event that should be grouped under the
  anchor. Two paths in order of preference:

    1. Same `:dispatch-id` as the anchor (the strict structural link).
    2. Wall-clock window around the anchor's `:time`
       (`+default-actor-destroy-window-ms+`) — the best-effort fallback
       for when the actor-destroy abort fires outside the originating
       drain and so carries no `:dispatch-id` link.

  Restricted to `frame` when the focus names one (`in-frame?`).

  An `:rf.http/aborted` `:reason :actor-destroyed` row is dropped when the
  gathered set also holds the `:rf.http/aborted-on-actor-destroy` row it
  echoes (`actor-destroy-echo?`), so one aborted request is one abort row
  and counts once toward its teardown's `:inflight-count`.

  Sorted oldest-first by `:time`."
  [trace-buffer anchor frame]
  (let [evs           (filter #(in-frame? frame %) (or trace-buffer []))
        anchor-t      (:time anchor)
        anchor-disp   (get-in anchor [:tags :rf.trace/dispatch-id])
        anchor-actor  (or (get-in anchor [:tags :actor-id])
                          (get-in anchor [:tags :machine-id])
                          (get-in anchor [:tags :spawned-id]))
        window        default-actor-destroy-window-ms
        by-dispatch   (when anchor-disp
                        (filter #(and (abort-event? %)
                                      (= anchor-disp
                                         (get-in % [:tags :rf.trace/dispatch-id])))
                                evs))
        ;; Wall-clock fallback — include if the trace lacks the
        ;; dispatch-id link but lands inside the window AND either
        ;; mentions the actor or is an actor-destroy-shaped abort.
        by-window     (filter
                        (fn [ev]
                          (and (abort-event? ev)
                               (number? (:time ev))
                               (number? anchor-t)
                               (<= 0 (- (:time ev) anchor-t) window)
                               (let [ev-disp (get-in ev [:tags :rf.trace/dispatch-id])
                                     ev-actor (or (get-in ev [:tags :actor-id])
                                                  (get-in ev [:tags :machine-id]))]
                                 (and (or (nil? ev-disp)
                                          (not= ev-disp anchor-disp))
                                      (or (nil? anchor-actor)
                                          (nil? ev-actor)
                                          (= anchor-actor ev-actor)
                                          ;; actor-destroy-shaped abort with
                                          ;; no actor link — fold it in
                                          (= :rf.http/aborted-on-actor-destroy
                                             (:operation ev)))))))
                        evs)
        all           (concat by-dispatch by-window)
        ;; Dedup by trace-id; preserve order; sort oldest-first.
        unique        (->> all
                           (reduce (fn [{:keys [seen acc]} ev]
                                     (let [k (:id ev)]
                                       (if (and k (contains? seen k))
                                         {:seen seen :acc acc}
                                         {:seen (if k (conj seen k) seen)
                                          :acc  (conj acc ev)})))
                                   {:seen #{} :acc []})
                           :acc)
        registry-rows (filter #(= :rf.http/aborted-on-actor-destroy (:operation %))
                              unique)]
    (->> unique
         (remove #(actor-destroy-echo? registry-rows %))
         (sort-by (fn [ev] [(or (:time ev) 0) (or (:id ev) 0)])))))

(defn- gather-related-teardowns
  "Pull every destroy trace event that rides with the anchor (same
  cascade or wall-clock window). The anchor itself is included. Each
  carries `:inflight-count` derived from the aborts that target the
  same actor.

  Restricted to `frame` when the focus names one (`in-frame?`).

  Sorted oldest-first by `:time`."
  [trace-buffer anchor aborts frame]
  (let [evs           (filter #(in-frame? frame %) (or trace-buffer []))
        anchor-t      (:time anchor)
        anchor-disp   (get-in anchor [:tags :rf.trace/dispatch-id])
        window        default-actor-destroy-window-ms
        by-dispatch   (when anchor-disp
                        (filter #(and (cancellation-anchor? %)
                                      (= anchor-disp
                                         (get-in % [:tags :rf.trace/dispatch-id])))
                                evs))
        by-window     (filter
                        (fn [ev]
                          (and (cancellation-anchor? ev)
                               (number? (:time ev))
                               (number? anchor-t)
                               (<= 0
                                   (Math/abs (- (:time ev) anchor-t))
                                   window)
                               (let [ev-disp (get-in ev [:tags :rf.trace/dispatch-id])]
                                 (or (nil? ev-disp)
                                     (nil? anchor-disp)
                                     (not= ev-disp anchor-disp)))))
                        evs)
        all           (concat by-dispatch by-window [anchor])
        seen          (volatile! #{})
        unique        (vec
                        (keep (fn [ev]
                                (let [k [(:operation ev) (:id ev)]]
                                  (when (and (not (contains? @seen k))
                                             (vswap! seen conj k))
                                    ev)))
                              all))
        ;; Build a count of aborts per actor id.
        counts-by-actor
        (reduce (fn [acc abort-ev]
                  (let [tags  (:tags abort-ev)
                        actor (or (:actor-id tags)
                                  (:machine-id tags)
                                  (:spawned-id tags))]
                    (if actor
                      (update acc actor (fnil inc 0))
                      acc)))
                {}
                (or aborts []))]
    (->> unique
         (sort-by (fn [ev] [(or (:time ev) 0) (or (:id ev) 0)]))
         (mapv (fn [ev]
                 (let [row    (teardown-row ev)
                       actor  (or (:child-id row) (:spawned-id row))
                       found  (when actor (get counts-by-actor actor))]
                   (assoc row :inflight-count (or found 0))))))))

(defn- find-decision
  "Locate the parent decision — the most-recent `:rf.event/dispatched`
  trace event within the cascade defined by the anchor's
  `:rf.trace/dispatch-id`. Falls back to the most-recent dispatched event
  before the anchor's `:time` when the anchor has no `:rf.trace/dispatch-id`.
  Restricted to `frame` when the focus names one (`in-frame?`)."
  [trace-buffer anchor frame]
  (let [evs         (filter #(in-frame? frame %) (or trace-buffer []))
        anchor-t    (:time anchor)
        anchor-disp (get-in anchor [:tags :rf.trace/dispatch-id])
        by-dispatch (when anchor-disp
                      (->> evs
                           (filter #(and (dispatched-event? %)
                                         (= anchor-disp
                                            (get-in % [:tags :rf.trace/dispatch-id]))))
                           (sort-by :time)
                           first))]
    (or by-dispatch
        (when (number? anchor-t)
          (->> evs
               (filter #(and (dispatched-event? %)
                             (number? (:time %))
                             (<= (:time %) anchor-t)))
               (sort-by :time)
               last)))))

(defn extract-cascade
  "Project a cancellation-cascade record from the trace buffer. Pure
  data → data.

  Inputs:
    `trace-buffer` — vector of trace events (Xray's mirror slot or
      a fixture). nil-safe.
    `focus`        — `{:kind  <:machine-id | :dispatch-id | nil>
                       :id    <value-or-nil>
                       :frame <frame-id-or-nil>}` or nil.

      `:kind :machine-id`  → anchor is the latest cancellation-destroy
                             for that machine-id.
      `:kind :dispatch-id` → anchor is the destroy with that
                             dispatch-id.
      `:kind nil` (or focus nil) → most-recent cancellation-destroy
                                   in the buffer.

      `:frame` (rf2-y8doi.15) scopes the WHOLE extraction — anchor,
      aborts, teardowns and the decision row — to one host frame. Xray's
      buffer is every frame's ring merged and a dispatch-id is unique only
      within a frame, so without it frame B's aborts folded into frame A's
      cascade. Omit it and nothing is scoped, which is the behaviour every
      caller had before. See `in-frame?` for the two nil escapes.

  Returns the cascade record described in the ns docstring, or a
  shaped empty-state record when no anchor / aborts are present."
  ([trace-buffer]
   (extract-cascade trace-buffer nil))
  ([trace-buffer focus]
   (let [{:keys [kind id frame]} (or focus {})
         anchor   (find-anchor trace-buffer kind id frame)]
     (if (nil? anchor)
       {:parent-decision  nil
        :child-teardowns  []
        :effect-aborts    []
        :total-elapsed-ms nil
        :empty-kind       :no-trigger}
       (let [aborts        (gather-related-aborts trace-buffer anchor frame)
             teardowns     (gather-related-teardowns trace-buffer anchor aborts frame)
             decision-ev   (find-decision trace-buffer anchor frame)
             decision      (decision-row decision-ev)
             abort-rows    (mapv abort-row aborts)
             ;; Total elapsed: earliest event-time (decision when
             ;; present, else first teardown) → latest abort/teardown.
             start-t       (or (:t decision)
                               (some-> teardowns first :t)
                               (:time anchor))
             end-t         (or (some->> abort-rows
                                        (keep :t)
                                        seq
                                        (apply max))
                               (some->> teardowns
                                        (keep :t)
                                        seq
                                        (apply max))
                               (:time anchor))
             elapsed       (when (and (number? start-t) (number? end-t))
                             (max 0 (- end-t start-t)))]
         {:parent-decision  decision
          :child-teardowns  teardowns
          :effect-aborts    abort-rows
          :total-elapsed-ms elapsed
          :empty-kind       (if (empty? abort-rows) :no-aborts nil)})))))

;; ---- cascade summarisers ------------------------------------------------

(defn cascade-summary
  "One-line summary line for the visualiser footer / collapsed header.
  Pure data → string."
  [cascade]
  (let [teardowns (count (:child-teardowns cascade))
        aborts    (count (:effect-aborts cascade))
        elapsed   (:total-elapsed-ms cascade)
        elapsed-s (when elapsed (str elapsed "ms"))]
    (cond
      (= :no-trigger (:empty-kind cascade))
      "No cancellation cascade in the trace window."

      (= :no-aborts (:empty-kind cascade))
      (str teardowns " " (common/pluralize teardowns "child destroyed")
           " · 0 effects aborted")

      :else
      (str/join " · "
                (cond-> [(str teardowns " " (common/pluralize teardowns "child" "ren")
                              " destroyed")
                         (str aborts " " (common/pluralize aborts "effect")
                              " aborted")]
                  elapsed-s (conj (str elapsed-s " elapsed")))))))

(def ^:const default-collapse-threshold
  "Per the bead's contract — collapse aborts by default when there are
  more than N. The view exposes a 'Show all N' expander."
  10)

(defn should-collapse?
  "True when the abort list should be collapsed by default. Pure fn
  for unit testability."
  ([cascade] (should-collapse? cascade default-collapse-threshold))
  ([cascade threshold]
   (> (count (:effect-aborts cascade)) threshold)))

;; ---- formatters (view-side, kept in .cljc for test reuse) --------------

(defn format-time-ms
  "Render a wall-clock `:time` value as a short label. Best-effort —
  if `t` is nil returns `\"—\"`."
  [t]
  (if (number? t)
    (str t "ms")
    "—"))

(defn format-event-vec
  "Pretty-print an event vector for display in the parent-decision row."
  [event-vec]
  (cond
    (nil? event-vec) "—"
    (vector? event-vec)
    (try (pr-str event-vec) (catch #?(:clj Throwable :cljs :default) _ (str event-vec)))
    :else (str event-vec)))

(defn format-fx-label
  "Human label for an abort row's `:fx` tag. Includes the request
  method/URL when present."
  [{:keys [fx req url]}]
  (let [method (some-> (:method req) name str/upper-case)
        u      (or url (:url req))]
    (case fx
      :http           (str "HTTP " (or method "") (when u " ") (or u ""))
      :ws             (str "WS send" (when (:event req)
                                       (str " " (pr-str (:event req)))))
      :after          (str ":after timer fire"
                           (when (:timer-id req)
                             (str " " (pr-str (:timer-id req)))))
      :machine-invoke (str "machine-invoke"
                           (when (:child-id req)
                             (str " " (pr-str (:child-id req)))))
      (str (name (or fx :unknown))))))
