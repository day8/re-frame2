(ns re-frame2-pair-mcp.tools.subscribe
  "Tool: subscribe — streaming trace + epoch channel.

  The MCP `tools/call` request runs until either:
    (a) the client aborts (cancellation arrives via the MCP `extra.signal`
        AbortSignal), or
    (b) an `unsubscribe` op clears the sub-id from the runtime.

  While running, each batch of newly-queued runtime events is shipped to
  the client as a `notifications/progress` notification correlated to the
  original tools/call via `extra._meta.progressToken`. The final
  `tools/call` result is a summary `{:ok? true :sub-id :delivered N
  :overflow N :reason <terminated-reason>}`.

  The runtime queue is bounded by a byte+event budget:
  default 500 events OR ~5 MB of pr-str bytes, whichever trips first.
  On overflow the OLDEST queued events are evicted (drop-oldest FIFO).
  The drain payload carries `:dropped-events`, `:dropped-bytes`, and
  `:overflow-reason` (`:max-buffered-events` / `:max-buffered-bytes`)
  so the AI client knows which budget to tune.

  ## Internal shape

  All rolling per-stream accounting lives in a single `state` atom
  holding a map `{:tick :delivered :dropped-events :dropped-bytes
  :overflow-reason :dropped-sensitive :elided-large}`. The poll loop
  applies one `swap!` per drain (merging the drain's contributions
  into the accumulators) rather than 5-7 separate ones. Termination,
  poll-step, and per-tick emission are factored into `make-stream-
  controller`, which closes over the atom and returns the `terminate`
  + `poll` fns — the streaming-loop body reads top-down.

  ## Per-tick + final-summary emit

  `progress-payload`, `emit-progress-tick!`, and `final-summary` live
  in this same namespace — single-consumer helpers coupled to this
  loop's state shape, with no independent tests and no reuse surface.
  Keeping them here lets the streaming-loop body read top-down without
  bouncing between two files when the state shape changes."
  (:require [applied-science.js-interop :as j]
            [re-frame.mcp-base.elision :as base-elision]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.tools.cap :as cap]
            [re-frame2-pair-mcp.tools.probe :as probe]
            [re-frame2-pair-mcp.tools.args :as args]
            [re-frame2-pair-mcp.tools.dedup :as dedup]
            [re-frame2-pair-mcp.tools.elision :as elision]
            [re-frame2-pair-mcp.tools.epoch-egress :as egress]
            [re-frame2-pair-mcp.tools.sensitive :as sensitive]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.resource-controls :as resource]))

(def ^:private default-poll-ms 100)
;; `:max-buffered-events` / `:max-buffered-bytes` are NOT mirrored here:
;; the runtime applies its own defaults when the slot is nil, and
;; mirroring would force a two-file sync on every runtime budget tweak.
;; Pass nil → runtime defaults.

(def ^:private initial-state
  "The rolling per-stream accounting map. Held inside one
  atom for the lifetime of a `subscribe-tool` call; merged once per
  drain. Indicator slots (`:dropped-sensitive`, `:elided-large`) feed
  `wire/with-indicators` at terminal-summary emit time.

  `:rate-dropped` is the count of poll cycles the per-session
  rate-limit (resource-controls token bucket) DEFERRED to keep the wire
  under the operator-configured events/sec cap. A deferred cycle does
  NOT drain the runtime queue, so its events ride a later cycle once a
  token refills — `:rate-dropped` is a \"cap-tripped\" signal (raise
  `--max-events-per-sec`, or look at why a consumer is sending so much),
  NOT a lost-event count. The count surfaces on the final summary so the
  operator sees whether the cap bit during the stream."
  ;; :elided-large counts upstream-pre-elided markers per
  ;; Spec 009 §Indicator field — cumulative across drains.
  {:tick              0
   :delivered         0
   :dropped-events    0
   :dropped-bytes     0
   :overflow-reason   nil
   :dropped-sensitive 0
   :elided-large      0
   :rate-dropped      0})

(defn drain-produced-output?
  "Did this drain produce a tick the client should see? True iff the
  drain delivered kept events OR reported queue-overflow drops — the
  two shapes that surface as a `notifications/progress` tick.

  Lifted to a named predicate (rather than inlining the OR at the
  two call sites — `merge-drain`'s `tick?` gate and the poll loop's
  emit gate) so the contract is single-sourced: any future change
  to what counts as a tick (e.g. surfacing tick-elided-only drains)
  lands here once, not at two sites that could drift."
  [{:keys [n ev-dropped]}]
  (or (pos? (or n 0)) (pos? (or ev-dropped 0))))

(defn merge-drain
  "Pure state update — fold one drain's contributions into the rolling
  accumulators. The drain reports `:ev-dropped`/`:by-dropped` for
  queue-overflow eviction, `:dropped` for sensitive-strip,
  and `:tick-elided` for the count of `:rf.size/large-elided` markers
  on this batch. `:n` is the kept-event count after sensitive-strip.

  A tick is counted whenever `drain-produced-output?` returns true —
  the predicate single-sources the gate.

  Public (not `defn-`) so unit tests in `subscribe_test.cljs` can
  pin the state-merge contract directly; the runtime contract surface
  is otherwise nrepl-only."
  [s {:keys [n ev-dropped by-dropped ov-reason dropped tick-elided] :as drain}]
  (cond-> s
    (drain-produced-output? drain)
                       (-> (update :tick      inc)
                           (update :delivered + n))
    (pos? ev-dropped)  (update :dropped-events    + ev-dropped)
    (pos? by-dropped)  (update :dropped-bytes     + by-dropped)
    ov-reason          (assoc  :overflow-reason   ov-reason)
    (pos? dropped)     (update :dropped-sensitive + dropped)
    (pos? tick-elided) (update :elided-large      + tick-elided)))

;; ---------------------------------------------------------------------------
;; Per-tick progress payload + final-summary emit.
;; ---------------------------------------------------------------------------

(defn progress-payload
  "Build the JSON params payload for one `notifications/progress` tick.
  `events` is the EDN-printed string of the batch (kept as a string so
  the agent host sees the same shape as `tools/call` results). The
  `_meta.data` carries the structured drop counts and the
  `:overflow-reason` keyword so AI clients can
  pattern-match on which budget tripped without re-parsing the EDN
  message. The official MCP SDK strips unknown top-level progress
  params, but preserves `_meta`."
  [progress-token tick events dropped-events dropped-bytes overflow-reason]
  #js {:progressToken progress-token
       :progress      tick
       ;; `message` is the human-readable slot. We stash an EDN form
       ;; here so an MCP client that surfaces progress messages to
       ;; the agent shows the events directly. A capable client can
       ;; additionally inspect `_meta.data` for the structured
       ;; counts.
       :message       events
       :_meta         #js {:data #js {:dropped-events  dropped-events
                                      :dropped-bytes   dropped-bytes
                                      ;; `overflow-reason` is an EDN keyword on
                                      ;; the runtime side — stringify here so it
                                      ;; rides JSON-RPC cleanly. The runtime
                                      ;; sentinels are `:max-buffered-events` /
                                      ;; `:max-buffered-bytes`.
                                      :overflow-reason (when overflow-reason
                                                         (pr-str overflow-reason))}}})

(defn final-summary
  "The terminal `ok-text` result emitted when the subscription ends —
  client cancel, unsubscribe, max-events / max-ms hit, sub-gone, or
  abuse-detected.

  `state` is the deref'd rolling accumulators map (see
  `initial-state`); `wire/with-indicators` splices the
  `:dropped-sensitive` / `:elided-large` counters onto the envelope
  per the cross-MCP indicator-field convention. `:rate-dropped`
  surfaces only when non-zero — same suppress-when-zero discipline
  as the indicator-field MUSTs. `:rate-dropped` counts
  poll cycles DEFERRED by the rate cap (events stayed queued for a
  later cycle), not lost events."
  [{:keys [sub-id topic state reason]}]
  (let [{:keys [tick delivered dropped-events dropped-bytes
                overflow-reason dropped-sensitive elided-large
                rate-dropped]} state]
    (wire/ok-text
      (wire/with-indicators
        (cond-> {:ok?            true
                 :sub-id         sub-id
                 :topic          topic
                 :delivered      delivered
                 :dropped-events dropped-events
                 :dropped-bytes  dropped-bytes
                 :ticks          tick
                 :reason         reason}
          overflow-reason
          (assoc :overflow-reason overflow-reason)
          (pos? (or rate-dropped 0))
          (assoc :rate-dropped rate-dropped))
        {:dropped dropped-sensitive :elided elided-large}))))

(defn emit-progress-tick!
  "Build the per-tick progress payload and ship it via the MCP
  `sendNotification`. Failures are swallowed — a flaky client must not
  collapse the stream.

  The `:message`-slot EDN map carries the delivered payload
  under one of two slots per the sub's topic:
    - `:cascades` (vector of event bundles) on event-bundle topics
      (`:trace`/`:fx`/`:error`);
    - `:events` (flat vector) on `:epoch` and `:frameless`.
  The split keeps the wire shape congruent with `(rf/trace-buffer
  frame-id)` for event-bundle topics — one tick = one dispatched event's
  traces as a unit. The `:cascade?` flag on `tick-state` carries the
  topic-shape signal.

  The serialised `:message` EDN runs through
  `cap/cap-message` BEFORE it crosses the notification wire, applying the
  SAME per-notification token budget (and `:rf.mcp/overflow` overflow
  marker) the `tools/call` result path enforces. Without this, a single
  busy trace/epoch drain could ship a multi-megabyte progress message
  even though ordinary results are capped — busting the per-notification
  budget the spec pins (`Principles.md` §Streaming over batch /
  §Subscribe streaming). The drop counts on `_meta.data` are tiny scalars
  and ride uncapped; only the payload-bearing `:message` is gated. `cap`
  is the resolved per-call `max-tokens` budget (`nil` ⇒ caller disabled
  the cap via `max-tokens 0`)."
  [{:keys [send-note progress-tk sub-id cap]} dedup? tick-state]
  (let [{:keys [tick cascade? dedup-events ev-dropped by-dropped
                ov-reason dropped tick-elided]} tick-state
        payload-slot (if cascade? :cascades :events)
        message      (cap/cap-message
                       (pr-str (wire/with-indicators
                                 (cond-> {:sub-id         sub-id
                                          payload-slot    dedup-events
                                          :dedup          dedup?
                                          :dropped-events ev-dropped
                                          :dropped-bytes  by-dropped}
                                   ov-reason
                                   (assoc :overflow-reason ov-reason))
                                 {:dropped dropped :elided tick-elided}))
                       {:tool "subscribe" :cap cap})]
    (try
      (send-note
        #js {:method "notifications/progress"
             :params (progress-payload
                       progress-tk
                       tick
                       message
                       ev-dropped
                       by-dropped
                       ov-reason)})
      (catch :default _ nil))))

;; ---------------------------------------------------------------------------
;; Drain eval-form — server-side off-box projection (EP-0015 §13).
;; ---------------------------------------------------------------------------
;;
;; `drain-subscription!` returns one of two envelopes per the sub's topic:
;;
;;   - Event-bundle topics (`:trace`/`:fx`/`:error`) →
;;     `{:ok? :sub-id :cascades [<bundle> ...] :dropped-events ... :gone? ...}`
;;     where each bundle has `:dispatch-id :frame :event :dispatched
;;     :handler :fx :effects :subs :renders :other :trace-events
;;     :parent-dispatch-id` (the framework's `(rf/trace-buffer frame-id)`
;;     shape).
;;   - Flat topics (`:epoch`/`:frameless`) →
;;     `{:ok? :sub-id :events [...] :dropped-events ... :gone? ...}`.
;;
;; The delivered slots take DIFFERENT off-box-egress primitives, selected
;; by topic:
;;
;;   - TRACE-event slots — `:cascades` (event-bundle topics) + the
;;     `:frameless` topic's `:events` — are tree-shaped values rooted at
;;     the frame's app-db, so each flows through
;;     `re-frame.core/elide-wire-value` (the size-elision walker reading
;;     the live `[:rf.runtime/elision]` runtime-db registry — it runs
;;     app-side). Gated by the `:elision` toggle:
;;     gate-OFF forces it on; gate-ON + `:elision false` ships raw.
;;
;;   - The `:epoch` topic's `:events` carry whole `:rf/epoch-record`s,
;;     including the structured `:effects` rows whose `:args` slot is
;;     payload-bearing fx input NOT rooted at app-db (so the schema-path
;;     walker cannot prove it safe — `elide-wire-value` over a whole
;;     record would ship `:effects[].args` RAW). Per Security.md §Epoch
;;     privacy posture, off-box epoch egress MUST route through
;;     `re-frame.core/projected-record` — the single normative emission
;;     site, which FAILS CLOSED on `:effects[].args`, default-redacts the
;;     frame-state runtime-db partition, and projects every payload slot
;;     under `:rf.egress/off-box-observability`. Per-tool reimplementation
;;     (the prior whole-record `elide-wire-value` walk) is prohibited. The
;;     `:epoch` projection is gated on the SENSITIVE opt-in axis (`incl?`,
;;     via `--allow-sensitive-reads`), like the pull-mode `watch-epochs` /
;;     `trace-window` tools — independent of the large-slot `:elision`
;;     toggle — so a gate-ON `:elision false` caller still gets projected
;;     (fail-closed) records unless they ALSO pass `:include-sensitive
;;     true`.
;;
;; The projection runs server-side, before any value crosses the nREPL
;; wire, so an operator who didn't pass `--allow-sensitive-reads` can't be
;; talked into shipping raw state through a hostile per-call arg.

(defn drain-form
  "Build the nREPL drain eval form. When `elision?` is true, wraps the
  drain envelope so the delivered slot (`:cascades` or `:events`,
  whichever the runtime produced) is projected for off-box egress
  server-side. `incl?` threads into the projection's sensitive opt-in —
  gate-OFF callers see redacted sensitive slots regardless of any
  per-call opt-in.

  The wrapper handles both delivery shapes (event-bundle topics →
  `:cascades`; flat topics → `:events`).

  ## Per-slot egress primitive — `topic` selects (EP-0015 §13)

  The two delivered slots are NOT the same shape, so they take DIFFERENT
  egress primitives — selected by `topic`, not by slot presence:

  - **Trace-event slots** — `:cascades` (event-bundle topics
    `:trace`/`:fx`/`:error`) and the `:frameless` topic's `:events`
    (raw trace events) — are tree-shaped values rooted at the frame's
    app-db, so the size-elision walker
    `re-frame.core/elide-wire-value` is the correct primitive: it
    redacts the frame's declared-sensitive slots and elides large slots
    (the declarations are frame-owned, EP-0015 §3/§8).

  - **The `:epoch` topic's `:events`** carry whole `:rf/epoch-record`s
    (`:db-before` / `:db-after` / `:trigger-event` / `:trace-events`
    PLUS the structured `:effects` rows whose `:args` slot is
    payload-bearing fx-handler input — an HTTP body, a dispatched event
    vector, a payment map — NOT rooted at app-db, so the schema-path
    walker cannot prove it safe). A bare `elide-wire-value` walk over a
    whole record therefore ships those `:effects[].args` (and the
    `:sub-runs` rows + the frame-state runtime-db partition) RAW off-box.
    Per Security.md §Epoch privacy posture, off-box epoch egress MUST
    route through `re-frame.core/projected-record` — the single
    normative emission site — which FAILS CLOSED on `:effects[].args`
    (`:rf/redacted` unless `:include-fx-args? true`), default-redacts the
    runtime-db partition, and projects every payload slot under
    `:rf.egress/off-box-observability`. Per-tool reimplementation of the
    projection (the prior whole-record `elide-wire-value` walk) is
    prohibited. This mirrors `watch-epochs` / `trace-window`
    (`epoch-egress/project-page-src`) and the snapshot `:epochs` slice:
    one off-box epoch-egress site, one projector.

  The `:epoch` projection is gated on `(not incl?)` (the
  `--allow-sensitive-reads` opt-in axis, like the pull-mode epoch tools)
  rather than on `elision?`: gate-OFF forces `incl?` false ⇒ every record
  is projected; gate-ON + `:include-sensitive true` ⇒ records ship raw
  (the operator's deliberate opt-in). When `elision?` is false (gate-ON +
  `:elision false`) the trace-event slots ship raw — but a gate-ON caller
  who left `:include-sensitive` at its default (`incl?` false) still gets
  PROJECTED epoch records, because epoch projection tracks the sensitive
  axis, not the large-slot toggle.

  The trace-walk arm resolves the elision
  `:frame` PER ELEMENT inside the walk by LAYER: an event-bundle record's own
  top-level `:frame` slot (DERIVED records key frame at top level —
  event bundles are keyed by `[frame dispatch-id]`), else a frameless
  RAW event's frame via the canonical reader
  `re-frame.trace/trace-event-frame` (its `[:tags :frame]` slot). An
  all-frame stream — or a filter frame that differs from the operating
  frame — carries event bundles from several
  frames in one tick, and per EP-0015 sensitive/large declarations are
  per-frame; eliding a foreign-frame event bundle against the operating
  frame's registry mis-redacts (under-redaction leaks across the off-box
  boundary). The operating frame is the genuinely-frameless FALLBACK
  only.

  EP-0002 — the drain form runs on the nREPL eval thread,
  which carries NO ambient `with-frame` scope, so a frameless
  `re-frame.core/elide-wire-value` would FAIL CLOSED and redact every
  slot to `:rf/redacted` (the carried-frame invariant). We resolve the
  operating frame app-side via `(re-frame2-pair.runtime/current-frame)`
  and use it as the per-element fallback — the same idiom
  `elision/elide-sub-value-src` uses for the sub-cache walker and
  `runtime/pair-dispatch!` uses for the dispatch override — so a truly
  frameless element resolves against the operating frame's
  `[:rf.runtime/elision]` runtime-db registry instead of failing closed.
  `projected-record` resolves the frame off each record's own `:frame`
  slot, so the `:epoch` arm needs no `current-frame` thread.

  Public (not `defn-`) so unit tests can pin the form shape directly —
  the form-string is the contract surface between MCP server and the
  app-side runtime."
  [sub-id topic elision? incl?]
  (let [epoch?         (= :epoch topic)
        ;; Epoch records ALWAYS project — independent of the large-slot
        ;; `elision?` toggle AND of the sensitive opt-in (`incl?`).
        ;; `:include-sensitive true` does NOT bypass the epoch
        ;; projection: it threads `{:include-sensitive? true}` INTO
        ;; `projected-record` (app-db sensitive axis only), so the
        ;; orthogonal fx-args / runtime-db / large axes and the app
        ;; `:redact-fn` stay fail-closed. Mirrors watch-epochs /
        ;; trace-window. An epoch record never crosses the wire as a raw
        ;; fx-arg / runtime-db payload.
        project-epoch? epoch?
        ;; The trace-event walker (`:cascades` / `:frameless` `:events`)
        ;; covers tree-shaped slots rooted at the frame's app-db. The
        ;; `:epoch` topic's `:events` are NOT trace events — they take
        ;; `projected-record`, never the walker — so the walker arm is
        ;; suppressed for it.
        ;;
        ;; Fail-CLOSED: the trace walker fires UNLESS the caller opted
        ;; into BOTH raw axes (`:elision false` ⇒ `include-large? true`
        ;; AND `:include-sensitive true` ⇒ `incl?`). Gating on `elision?`
        ;; alone would let a gate-ON `:elision false` caller who left
        ;; `:include-sensitive` at its default ship raw trace-event slots
        ;; — a declared-sensitive frame slot inside an event bundle leaking
        ;; off-box. A bare `:elision false` still walks (large passes via
        ;; `elision-opts-edn`, sensitive redacts to `:rf/redacted`).
        walk-trace?    (and (not epoch?)
                            (elision/walk-required? (not elision?) incl?))
        drain-call     (ef/rt-call 'drain-subscription! sub-id)]
    (cond
      ;; Nothing to project: gate-ON + `:elision false` on a NON-epoch
      ;; topic — the full-raw trace-event opt-in. Bare drain ships raw
      ;; (the operator-opt-in posture). The `:epoch` topic NEVER reaches
      ;; this arm: `project-epoch?` is `epoch?`, always true for it (epoch
      ;; records always project; the sensitive opt-in threads INTO the
      ;; projection, it does not bypass it).
      (not (or walk-trace? project-epoch?))
      (ef/emit drain-call)

      ;; Epoch-only projection (no trace-event walk). Each record ALWAYS
      ;; routes through `re-frame.core/projected-record` — the single
      ;; normative off-box epoch-egress site (Security.md §Epoch privacy
      ;; posture): fails closed on `:effects[].args`, default-redacts the
      ;; frame-state runtime-db partition, projects every payload slot
      ;; under `:rf.egress/off-box-observability`. `projected-record`
      ;; resolves the frame off each record's own `:frame` slot, so no
      ;; `current-frame` thread is needed. Per-tool reimplementation (a
      ;; whole-record `elide-wire-value` walk, which would leave
      ;; `:effects[].args` / `:sub-runs` / runtime-db raw) is prohibited.
      ;; `incl?` threads `{:include-sensitive? true}` INTO the projection
      ;; (app-db sensitive axis only); it does NOT disable it. fx-args /
      ;; runtime-db / large slots / `:redact-fn` stay fail-closed
      ;; regardless of `:include-sensitive`.
      project-epoch?
      (let [opts-edn (egress/egress-opts-edn incl?)]
        (ef/emit
          (ef/rt-let
            ['drain drain-call]
            (ef/rt-raw
              (str "(cond-> drain"
                   " (contains? drain :events)"
                   " (update :events (fn [es] (mapv (fn [e#] (re-frame.core/projected-record e# " opts-edn ")) es))))")))))

      ;; Trace-event walk (event-bundle topics `:trace`/`:fx`/`:error`
      ;; ship `:cascades`; the `:frameless` topic ships `:events`). These
      ;; are tree-shaped trace events rooted at the frame's app-db, so the
      ;; size-elision walker is the correct primitive.
      ;;
      ;; `elision-opts-edn` first arg is walker-aligned `include-large?`
      ;; (subscribe always elides, so emit markers ⇒ pass `false`).
      ;;
      ;; The elision `:frame` is resolved PER ELEMENT inside
      ;; the walk, NOT once for the whole drain. Event bundles are keyed
      ;; by `[frame dispatch-id]` (group-by-event-with-events), so an
      ;; all-frame stream — or a filter frame that differs from the
      ;; operating frame — carries event bundles from several frames in one
      ;; tick. Per EP-0015 sensitive/large declarations are PER FRAME, so
      ;; eliding a frame-B event bundle against frame-A's (the operating
      ;; frame's) registry mis-redacts — the sharp edge is UNDER-redaction
      ;; (frame-A values A marks sensitive but B does not would leak across
      ;; the off-box MCP→LLM boundary). Each element supplies its own
      ;; frame BY LAYER: a DERIVED event-bundle record's top-level
      ;; `:frame` slot, else a frameless RAW event's frame via the
      ;; canonical `re-frame.trace/trace-event-frame` reader ([:tags
      ;; :frame]).
      ;;
      ;; EP-0002: `current-frame` is the genuinely frameless FALLBACK
      ;; only. The nREPL eval thread carries no ambient
      ;; frame scope, so a truly frameless element would otherwise fail
      ;; closed and redact every slot to `:rf/redacted`; resolving the
      ;; operating frame app-side preserves the deliberate frame-thread for
      ;; that case without applying it to frame-qualified elements.
      :else
      (ef/emit
        (ef/rt-let
          ['drain     drain-call
           'cur-frame (ef/rt-raw "(re-frame2-pair.runtime/current-frame)")
           'base-opts (ef/rt-raw (elision/elision-opts-edn false incl?))]
          (ef/rt-raw
            (str "(let [walk (fn [xs]"
                 "             (mapv (fn [x]"
                 "                     (let [frame (or (:frame x)"
                 "                                     (re-frame.trace/trace-event-frame x)"
                 "                                     cur-frame)]"
                 "                       (re-frame.core/elide-wire-value"
                 "                         x (assoc base-opts :frame frame))))"
                 "                   xs))]"
                 " (cond-> drain"
                 " (contains? drain :cascades)"
                 " (update :cascades walk)"
                 " (contains? drain :events)"
                 " (update :events walk)))")))))))

;; ---------------------------------------------------------------------------
;; Streaming controller — termination + poll loop.
;; ---------------------------------------------------------------------------

(defn- parse-mcp-extra
  "Pluck the three MCP-host slots the streaming loop needs out of the
  JS `extra` object: the abort signal, the progress-notification
  emitter, and the progress token correlating ticks to this
  `tools/call`."
  [extra]
  {:signal      (some-> extra (j/get :signal))
   :send-note   (some-> extra (j/get :sendNotification))
   :progress-tk (some-> extra (j/get :_meta) (j/get :progressToken))})

(defn make-stream-controller
  "Build the `terminate` + `poll` fns over a shared `state` atom and
  the per-call context. Returns `{:state :terminate :poll}` so the
  caller's body reads top-down — controller built, then `(poll)`
  invoked.

  Both fns close over the same atom. `terminate` issues the
  runtime-side `unsubscribe!`, releases the resource-controls
  stream slot (must run on EVERY exit path so the
  concurrent-stream counter doesn't leak), then `resolve`s the outer
  `tools/call` Promise with the final-summary envelope. `poll` runs
  the rate-gate → drain → state-merge → progress-emit → reschedule
  cycle until termination triggers (client abort, max-events reached,
  sub-gone, or abuse-detected).

  Public (not `defn-`) so `subscribe_resource_controls_test.cljs` can
  drive a single `poll` invocation with a stubbed
  `nrepl/cljs-eval-value` to pin the rate-gate-before-drain ordering
  (a denied cycle MUST NOT call the destructive drain)."
  [{:keys [conn build-id sub-id topic resolve state
           signal send-note progress-tk poll-ms max-events
           incl? elision? dedup? cap]}]
  (let [drain-src    (drain-form sub-id topic elision? incl?)
        terminated?  (atom false)
        terminate
        (fn terminate [reason]
          ;; Idempotent: a double-fire (e.g. abuse-detected fires the
          ;; same tick the max-events cap was reached) would otherwise
          ;; release the resource slot twice and double-resolve the
          ;; outer Promise. The atom guards the first-wins path.
          (when (compare-and-set! terminated? false true)
            (resource/release-stream!)
            (-> (nrepl/cljs-eval-value
                  conn build-id
                  (ef/emit (ef/rt-call 'unsubscribe! sub-id)))
                (.catch (fn [_] nil))
                (.then
                  (fn [_]
                    (resolve
                      (final-summary
                        {:sub-id sub-id :topic topic
                         :state  @state :reason reason})))))))
        poll
        (fn poll []
          (cond
            (and signal (.-aborted signal))
            (terminate :aborted)

            (and (pos? max-events)
                 (>= (:delivered @state) max-events))
            (terminate :max-events-reached)

            ;; Per-session rate-limit gate, checked BEFORE the destructive
            ;; drain. The token bucket holds at most `max-events-per-sec`
            ;; tokens; one drain-and-emit cycle consumes one token. When
            ;; the bucket is empty THIS poll cycle is deferred: we do NOT
            ;; call `drain-subscription!`, so the runtime-side queue stays
            ;; intact and its events ride the NEXT cycle once a token
            ;; refills — no event loss. Gating the drain itself (rather
            ;; than after it has popped + cleared the queue) is what keeps
            ;; a denied tick from throwing an already-drained batch away.
            ;;
            ;; `:rate-dropped` counts DEFERRED poll cycles, not lost
            ;; ticks — it is a "the cap was tripped, consider raising
            ;; --max-events-per-sec" signal, not a data-loss tally. Under
            ;; normal load (poll cadence ≪ refill rate) the bucket never
            ;; empties from cadence alone, so this only bites under
            ;; genuine sustained event volume — exactly when the runtime
            ;; queue is non-empty and deferral (not loss) is the right
            ;; behaviour.
            (not (resource/check-rate!))
            (do (swap! state update :rate-dropped inc)
                (js/setTimeout poll poll-ms))

            :else
            (-> (nrepl/cljs-eval-value conn build-id drain-src)
                (.then
                  (fn [drain-resp]
                    (if (:gone? drain-resp)
                      (terminate :sub-gone)
                      (let [;; The drain envelope carries the
                            ;; delivered slot per the sub's topic:
                            ;; `:cascades` for event-bundle topics
                            ;; (`:trace`/`:fx`/`:error`), `:events` for
                            ;; flat topics (`:epoch`/`:frameless`).
                            ;; Exactly one is present; `cascade?` keeps
                            ;; the slot name on the progress payload
                            ;; congruent with the topic's wire shape.
                            cascade?       (contains? drain-resp :cascades)
                            raw-items      (if cascade?
                                             (:cascades drain-resp)
                                             (:events   drain-resp))
                            ev-dropped     (:dropped-events  drain-resp 0)
                            by-dropped     (:dropped-bytes   drain-resp 0)
                            ov-reason      (:overflow-reason drain-resp)
                            [evts dropped] (sensitive/strip-sensitive
                                             (vec raw-items) incl?)
                            ;; :elided-large counts upstream-pre-elided markers per
                            ;; Spec 009 §Indicator field — per-tick contribution.
                            tick-elided    (base-elision/count-elided-markers evts)
                            n              (count evts)
                            drain-delta    {:n           n
                                            :ev-dropped  ev-dropped
                                            :by-dropped  by-dropped
                                            :ov-reason   ov-reason
                                            :dropped     dropped
                                            :tick-elided tick-elided}
                            tick?          (drain-produced-output? drain-delta)
                            s'             (swap! state merge-drain drain-delta)]
                        (when (and tick? send-note progress-tk)
                          (emit-progress-tick!
                            {:send-note   send-note
                             :progress-tk progress-tk
                             :sub-id      sub-id
                             :cap         cap}
                            dedup?
                            {:tick         (:tick s')
                             :cascade?     cascade?
                             :dedup-events (dedup/dedup-value evts dedup?)
                             :ev-dropped   ev-dropped
                             :by-dropped   by-dropped
                             :ov-reason    ov-reason
                             :dropped      dropped
                             :tick-elided  tick-elided}))
                        ;; Abuse-detection: any drain that
                        ;; reported a queue overflow contributes to the
                        ;; session's rolling window. Sustained overflow
                        ;; (the consumer can't keep up) terminates the
                        ;; stream rather than churning forever.
                        (if (and ov-reason
                                 (= :abuse-detected (resource/record-overflow!)))
                          (do (js/console.error
                                (str "[re-frame2-pair-mcp] stream abuse detected "
                                     "(sub-id=" sub-id " topic=" topic
                                     ") — sustained overflow exceeded threshold; "
                                     "terminating."))
                              (terminate :rf.error/stream-abuse-detected))
                          (js/setTimeout poll poll-ms))))))
                (.catch
                  (fn [_err]
                    ;; nREPL hiccup — back off and try again rather
                    ;; than collapsing the stream.
                    (js/setTimeout poll (* 2 poll-ms)))))))]
    {:state state :terminate terminate :poll poll}))

(defn- run-acquired
  "Drive the subscription lifecycle once the resource-controls
  stream slot is reserved. Returns a Promise resolving to
  the MCP tool result; on any pre-controller exit path the slot is
  released here — the stream-controller's `terminate` only fires
  AFTER `make-stream-controller` wires up, so failures before that
  point need explicit release."
  [{:keys [conn raw-args topic build-id filter-map max-buf-events
           max-buf-bytes poll-ms max-ms max-events
           incl? elision? dedup? cap signal send-note progress-tk]}]
  (let [subscribe-form
        (ef/emit
          (ef/rt-call 'subscribe!
                      ;; Only inline slots the caller actually
                      ;; supplied — the runtime applies its own
                      ;; defaults for absent budget knobs.
                      (cond-> {:topic topic}
                        max-buf-events (assoc :max-buffered-events max-buf-events)
                        max-buf-bytes  (assoc :max-buffered-bytes  max-buf-bytes)
                        filter-map     (assoc :filter              filter-map))))]
    (-> (probe/ensure-runtime! conn build-id)
        (.then (fn [_] (raw-state/signal-runtime! conn build-id)))
        (.then (fn [_] (nrepl/cljs-eval-value conn build-id subscribe-form)))
        (.then
          (fn [subscribe-resp]
            (if-not (:ok? subscribe-resp)
              (do (resource/release-stream!)
                  (wire/ok-text subscribe-resp))
              (let [sub-id (:sub-id subscribe-resp)]
                (js/Promise.
                  (fn [resolve _reject]
                    (let [{:keys [terminate poll]}
                          (make-stream-controller
                            {:conn        conn        :build-id    build-id
                             :sub-id      sub-id      :topic       topic
                             :resolve     resolve     :state       (atom initial-state)
                             :signal      signal      :send-note   send-note
                             :progress-tk progress-tk :poll-ms     poll-ms
                             :max-events  max-events  :incl?       incl?
                             :elision?    elision?    :dedup?      dedup?
                             :cap         cap})]
                      (when (pos? max-ms)
                        (js/setTimeout #(terminate :max-ms-reached) max-ms))
                      (poll))))))))
        (.catch (fn [err]
                  ;; Probe / signal-runtime / subscribe-eval failure —
                  ;; controller never wired, so terminate's release
                  ;; never fires. Release here.
                  (resource/release-stream!)
                  (probe/err->result :subscribe-failed err))))))

(defn subscribe-tool [conn raw-args extra]
  (let [build-id           (wire/arg-build conn raw-args)
        topic              (wire/arg-keyword raw-args :topic)
        ;; `parse-filter-arg` returns the tagged
        ;; `[:ok m]` / `[:err :invalid-filter-edn]` shape (mirroring
        ;; `read-edn-arg`). A bad filter EDN short-circuits to an honest
        ;; `:ok? false` envelope below rather than riding into the
        ;; runtime `subscribe!` `:filter` slot as a nonsense filter that
        ;; would silently stream the wrong (likely empty) event set.
        [filter-tag filter-map] (args/parse-filter-arg (wire/arg raw-args :filter))
        ;; Validate the five numeric subscribe controls
        ;; BEFORE they reach the runtime queue budget / poll loop /
        ;; termination caps. Buffer caps + poll cadence must be positive
        ;; integers; the termination caps must be non-negative (0 =
        ;; unbounded). A bad value short-circuits to an honest `:ok? false`
        ;; envelope in the `cond` below — un-vetted negatives would
        ;; collapse the queue budget (empty probe), spin the poll loop, or
        ;; silently disable the intended bound. Each is `[:ok n|nil]` /
        ;; `[:err {…}]`; `nil` (absent) falls back to the default.
        mbe-r              (args/parse-positive-int-arg "max-buffered-events" (wire/arg raw-args :max-buffered-events))
        mbb-r              (args/parse-positive-int-arg "max-buffered-bytes"  (wire/arg raw-args :max-buffered-bytes))
        pms-r              (args/parse-positive-int-arg "poll-ms"             (wire/arg raw-args :poll-ms))
        mms-r              (args/parse-non-negative-int-arg "max-ms"     (wire/arg raw-args :max-ms))
        mev-r              (args/parse-non-negative-int-arg "max-events" (wire/arg raw-args :max-events))
        numeric-err        (->> [mbe-r mbb-r pms-r mms-r mev-r]
                                (filter #(= :err (first %)))
                                first)
        max-buf-events     (second mbe-r)
        max-buf-bytes      (second mbb-r)
        poll-ms            (or (second pms-r) default-poll-ms)
        max-ms             (or (second mms-r) 0)
        max-events         (or (second mev-r) 0)
        ;; The `--allow-sensitive-reads` boot gate forces
        ;; `:include-sensitive false` on every streamed event when OFF
        ;; (the default). `sensitive/strip-sensitive` below honours the
        ;; post-gate value, so a caller's `:include-sensitive true` arg is
        ;; dropped before reaching the runtime drain. The single
        ;; intention-naming predicate `raw-state-allowed?` (positive sense
        ;; — true when operator opted in at launch) governs it.
        incl?              (if (raw-state/raw-state-allowed?)
                             (args/parse-bool-arg raw-args :include-sensitive)
                             false)
        ;; The `--allow-sensitive-reads` boot gate forces
        ;; `:elision true` on every streamed event when OFF, mirroring
        ;; the snapshot / get-path gate. Server-side, the drain envelope's
        ;; `:events` flow through `re-frame.core/elide-wire-value` before
        ;; crossing the nREPL wire — declared-large slots elide and
        ;; declared-sensitive slots redact. A caller's `:elision false`
        ;; arg is dropped when the gate is OFF.
        elision?           (if (raw-state/raw-state-allowed?)
                             (args/parse-bool-arg raw-args :elision)
                             true)
        dedup?             (args/parse-bool-arg raw-args :dedup)
        ;; The per-call wire-cap budget for the streamed
        ;; progress notifications. The `invoke` chokepoint (tools.cljs)
        ;; already validates `max-tokens` and short-circuits a NEGATIVE /
        ;; fractional value to an isError BEFORE this tool runs, so the
        ;; arg here is always a valid integer cap, `nil` (caller disabled
        ;; via `max-tokens 0`), or the default. `cap/max-tokens-arg` is
        ;; the same resolver the result path uses, so the progress channel
        ;; honours the SAME per-call budget override as `tools/call`
        ;; results — one `max-tokens` knob governs both surfaces.
        cap                (cap/max-tokens-arg raw-args)
        {:keys [signal send-note progress-tk]} (parse-mcp-extra extra)]
    (cond
      (or (nil? topic)
          (not (#{:trace :epoch :fx :error :frameless} topic)))
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :unknown-topic
                        :given (wire/arg raw-args :topic)
                        :hint  "Recognised topics: trace, epoch, fx, error, frameless."}))

      ;; The filter EDN failed to parse. Surface an honest
      ;; `:ok? false` error (same one-cond-branch shape as
      ;; `:unknown-topic` above) instead of subscribing with a garbage
      ;; filter. No nREPL socket touched.
      (= :err filter-tag)
      (js/Promise.resolve
        (wire/err-text {:ok? false :reason :invalid-filter-edn
                        :given (wire/arg raw-args :filter)
                        :hint  "filter must be an EDN-readable map (or a JSON object), e.g. \"{:op-type :error}\"."}))

      ;; A numeric control arg failed validation (zero /
      ;; negative / fractional / non-numeric). Surface the honest
      ;; `:ok? false` envelope (same one-cond-branch shape as the topic /
      ;; filter checks) BEFORE reserving a stream slot or touching the
      ;; nREPL socket, rather than forwarding a budget-collapsing value.
      numeric-err
      (js/Promise.resolve (wire/err-text (second numeric-err)))

      :else
      ;; Reserve a session-wide stream slot BEFORE any
      ;; runtime allocation. A rejection at this gate returns an
      ;; isError result without touching the nREPL socket — the
      ;; client must close an existing subscription first.
      (let [acquire (resource/acquire-stream!)]
        (if-not (:ok? acquire)
          (js/Promise.resolve (wire/err-text acquire))
          (run-acquired
            {:conn conn :raw-args raw-args :topic topic :build-id build-id
             :filter-map filter-map
             :max-buf-events max-buf-events :max-buf-bytes max-buf-bytes
             :poll-ms poll-ms :max-ms max-ms :max-events max-events
             :incl? incl? :elision? elision? :dedup? dedup? :cap cap
             :signal signal :send-note send-note :progress-tk progress-tk}))))))
