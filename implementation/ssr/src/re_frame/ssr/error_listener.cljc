(ns re-frame.ssr.error-listener
  "Trace-listener + per-frame error-trace buffer + projection drain +
  `get-response`. Per Spec 011 §Server error projection — the
  runtime-side glue that ties trace events to the active projector and
  stamps the public-error's `:status` onto the response accumulator.

  Buffered (rather than mutating `:rf/response` inline from the trace
  listener) because the firing handler's `{:db ...}` return CLOBBERS
  app-db (replace-container!) AFTER the trace fired — an inline write
  would be silently overwritten. Buffering + applying at the drain's
  settle-point (or via `get-response` on demand) sidesteps that race.

  Per the rf2-gxgo7 split of re-frame.ssr."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.ssr.error-projector :as error-projector]
            [re-frame.ssr.response :as response]
            [re-frame.trace :as trace]))

;; ---- always-on error-emit helper (EP-0008 rf2-hhutya) ---------------------
;;
;; The promoted SSR error categories ride the always-on error-emit axis
;; (surface #4) ALONGSIDE the existing dev-gated `trace/emit-error!`. The
;; always-on emit reaches `error-emit/dispatch-error-record!` through the
;; published `:error-emit/dispatch-error-record` late-bind hook (the SSR
;; artefact ships above core's require graph; the hook keeps the axis
;; addressable without a static require). UNGATED — it fires under
;; `interop/debug-enabled? = false` so off-box shippers (Sentry / Datadog)
;; on a `-Dre-frame.debug=false` JVM SSR host see the structured record the
;; dev trace surface would have elided. The corresponding
;; `error-emit-projection-listener` is registered with id `::error-projection`
;; (re-frame.ssr façade), so this record drives status projection for the
;; projection-eligible categories and is skipped (observability-only) for
;; the recoverable-degradation members — same `non-projection-eligible-error?`
;; gate that governs the wire (promotion changes what SHIPPERS see, never
;; what the WIRE does).
;;
;; The `emit-always-on-error!` helper itself is SINGLE-SOURCED in
;; `re-frame.ssr.error-projector` (rf2-50e82k — this ns already :requires
;; it for the projector); call sites here go through
;; `error-projector/emit-always-on-error!`.

(defn- candidate-frame-for-error
  "Select the frame to project against for a trace-event: the frame named
  in `[:tags :frame]` when it names a registered server frame, else nil.

  Returns nil when the trace carries no routable server `:frame` — an
  explicit no-op rather than a guess. Per rf2-7d30s the former
  single-active-server-frame fallback was REMOVED: under concurrent SSR
  load many server frames are live simultaneously (the canonical shape —
  see ssr-ring's concurrency stress test), so guessing the single frame
  silently mis-attributed (or, with >1 frame live, dropped) the
  projection — shipping a 200 for a request that should have been a
  4xx/5xx. Every error-emit site reachable inside a server-frame drain
  now stamps `[:tags :frame]` from the drain's known frame (audit under
  rf2-7d30s: core/spec.cljc boundary validation, core/subs reactive
  sub-exception + sub-override validation, routing navigate/url-change/
  nav-token; the schemas-artefact validate-*! fns and router miss paths
  already stamped it), so a trace that arrives here without `:frame` is
  genuinely unroutable and correctly no-ops — no projector runs, no
  stray response accumulator is written, and a client-platform error
  never bleeds into a server response."
  [trace-event]
  (let [tag-frame (get-in trace-event [:tags :frame])]
    (when (and tag-frame (error-projector/server-frame? tag-frame))
      tag-frame)))

;; Categories that are RECOVERABLE DEGRADATIONS, not request failures.
;; They fire `:op-type :error` for observability (per Spec 011 §1070 the
;; spec is the artefact: the always-on error-emit substrate carries the
;; trace to user observability stacks) yet MUST NOT project a non-200
;; status — the request continues with a degraded fragment.
;;
;; `:rf.error/ssr-head-resolution-failed` (rf2-bof8i Option B) is the
;; canonical member: `resolve-head` degrades a throwing `:head` fn to an
;; empty fragment so a buggy head fn "can't take down the request"
;; (Spec 011 §1070 + lifecycle/resolve-head docstring). This is the
;; deliberate, spec-pinned counterpoint to the view/sub fail-closed
;; posture (rf2-vvwmi / rf2-7d30s, Spec 011 §744/§748-751): a view or
;; reactive sub that throws mid-render projects a 5xx because the page is
;; unusable; a head fn that throws degrades to a 200 because only the
;; `<head>` metadata is missing — the body still renders and hydrates.
;;
;; rf2-lia3i — ENFORCING the contract at the buffering chokepoint (both
;; the dev-only `error-projection-listener` and the always-on
;; `error-emit-projection-listener` route through here) makes the
;; degraded-200 outcome a PROPERTY OF THE PROJECTOR, not an incidental
;; consequence of `ssr-handler` reading `get-response` (ring.clj:343)
;; BEFORE `build-full-response` fires the head trace (pipeline.clj:286).
;; Without this skip the immunity was timing-only: a future reorder (head
;; resolution before `get-response`, a second flush after the render, a
;; re-read of `get-response` on the same frame) would let a buffered head
;; trace project the default `:rf.error/*` → status and silently flip a
;; degraded 200 to a 4xx/5xx. The skip closes that hole by construction.
;;
;; rf2-sccp5 — `:rf.error/ssr-ring-error-view-failed` (ssr-ring's
;; `resolve-error-body`, pipeline.clj:228) is the SECOND member of the
;; same fragility class. It fires `:op-type :error` for observability
;; when a caller's `:error-view` itself throws, and the host falls back
;; to the locked default error template — "a buggy error-view must not
;; bypass the error boundary". It is a RECOVERABLE DEGRADATION by intent,
;; semantically the same bucket as the head category. CRUCIALLY it is
;; frame-stamped (`:frame frame-id`) and fires inside `build-full-
;; response`'s render-time catch AFTER `project-render-exception!` has
;; ALREADY stamped the projected status and cleared the buffer
;; (consume-pending-traces!) — so without this skip it is re-buffered
;; and left in `pending-error-traces` until frame-destroy. Safe TODAY
;; only because nothing re-reads `get-response`/`flush-response!` on the
;; frame after that point (the c0bq1 re-flush, pipeline.clj:331, lives on
;; the HAPPY path inside `build-full-response*`, which the error-view-
;; failed catch never reaches), and the default projector maps it to the
;; same 500 the render-time path already stamped. But a CUSTOM projector
;; mapping the render exception to a 4xx, plus a future post-error
;; re-flush, would let the buffered trace re-project → its generic 5xx
;; and silently flip 4xx→5xx. Skipping it here closes that hole by
;; construction — symmetric with the head category, enforced at the same
;; chokepoint across both buffering listeners.
;; EP-0008 (rf2-hhutya) extends the skip set with the two further NON-
;; PROJECTING SSR categories promoted to the always-on axis (the RULED
;; "keep categories 2/4/5/6 in the non-projection-eligible skip set"):
;;
;;   `:rf.error/ssr-streaming-writer-failed` — POST-HEAD-COMMIT (the chunked
;;   200 is already on the wire on the daemon writer thread; the status can
;;   no longer change). Promoting it to the always-on axis would otherwise
;;   let `error-emit-projection-listener` buffer + project a 500 onto a
;;   response that has already committed — flipping the wire. It is pure
;;   off-box telemetry; skip it so promotion ships the record WITHOUT
;;   touching the (already-committed) status.
;;
;;   `:rf.error/sanitised-on-projection` — the projector-fallback path. It
;;   was always guarded explicitly here (the re-entry guard); folding it
;;   into the set makes the classification uniform and keeps the one-shot,
;;   never-re-enter-projection contract enforced at the same chokepoint.
(def ^:private non-projection-eligible-errors
  #{:rf.error/ssr-head-resolution-failed
    :rf.error/ssr-ring-error-view-failed
    :rf.error/ssr-streaming-writer-failed
    :rf.error/sanitised-on-projection})

(defn- non-projection-eligible-error?
  "True when `operation` names a recoverable-degradation error category
  that must NOT be buffered for status projection (it ships its trace for
  observability but the request continues with a degraded fragment).
  Members: `:rf.error/ssr-head-resolution-failed` (rf2-lia3i) and
  `:rf.error/ssr-ring-error-view-failed` (rf2-sccp5)."
  [operation]
  (contains? non-projection-eligible-errors operation))

;; Per-frame buffer of captured error trace events. The trace listener
;; appends here synchronously when the error fires; apply-pending-
;; error-projection! drains the buffer and stamps the projected status
;; onto :rf/response.
;;
;; NOT marked `^:private`: the `re-frame.ssr` façade re-exports as
;; `^:private` so external consumers see the framework-private surface.
;; Spec 011 §Per-request frame teardown pins it as framework-private;
;; the visibility split lives at the namespace boundary.
(defonce pending-error-traces (atom {}))

(defn- buffer-error-trace!
  ;; Keyed by the (realm, frame) ADDRESS (rf2-bzw8gd) so two server frames
  ;; sharing an id in different realms buffer independent error traces —
  ;; `frame-address` collapses to the bare `frame-id` for the default realm.
  [frame-id trace-event]
  (swap! pending-error-traces update (frame/frame-address frame-id)
         (fnil conj []) trace-event))

(defn- consume-pending-traces!
  "Atomically pull and clear the pending error traces for frame-id.

  rf2-hzttr finding 4 — the prior shape DEREF'd the atom, read the
  frame's traces, then `swap! dissoc`'d the frame in a SEPARATE
  transition. A `buffer-error-trace!` append for the same frame landing
  between the deref and the dissoc (concurrent SSR / streaming error
  paths run many server frames simultaneously — see ssr-ring's
  concurrency stress test) was silently dropped: the deref missed it,
  the dissoc then deleted the whole frame key including the just-appended
  trace. A dropped error trace can lose a fail-closed status upgrade
  (200 shipped where a 5xx was due) or incomplete diagnostics — exactly
  the operational path this listener is meant to harden.

  `swap-vals!` performs the read and the clear in a SINGLE CAS-retried
  state transition: it returns the `[old new]` pair where `old` is the
  exact pre-clear value the `dissoc` was applied to, so an append that
  races the drain either lands before the CAS (and rides in `old`,
  returned to this caller) or after it (and survives in the atom for the
  next drain). No trace is dropped either way. `swap-vals!` is available
  on both the JVM (Clojure ≥1.9) and ClojureScript."
  [frame-id]
  (let [addr       (frame/frame-address frame-id)
        [old _new] (swap-vals! pending-error-traces dissoc addr)]
    (get old addr [])))

(defn apply-error-projection!
  "Project an error trace event via the active projector for frame-id
  and stamp the public-error's :status onto the response accumulator.
  Returns the public-error map on success, nil on no-op (frame missing
  / not server / no pending trace / redirect set on the response).

  Two arities:

    (apply-error-projection! frame-id)
      Drain frame-id's error-trace buffer and project the LAST trace
      (last-write-wins, mirroring the multi-status policy). Hosts that
      drive their own SSR loop call this after drain settles so the
      response carries the projector's status. The runtime also calls
      it automatically from get-response so a host reading the resolved
      response always sees up-to-date projection.

    (apply-error-projection! frame-id trace-event)
      Project the given trace-event directly. Host adapters that catch
      errors outside the trace stream call this to drive projection
      explicitly.

  Per Spec 011 §Redirect precedence — when the response carries a
  `:redirect`, the redirect's :status is locked through and this fn
  does not overwrite it."
  ([frame-id]
   (when-let [last-trace (when (and frame-id (error-projector/server-frame? frame-id))
                           (last (consume-pending-traces! frame-id)))]
     (apply-error-projection! frame-id last-trace)))
  ([frame-id trace-event]
   (when (and frame-id trace-event (error-projector/server-frame? frame-id))
     (let [public-error (error-projector/project-error frame-id trace-event)
           existing     (response/response-of frame-id)
           redirect?    (:redirect existing)]
       (when-not redirect?
         (response/swap-response! frame-id
                                  (fn [r] (assoc r :status (:status public-error)))))
       public-error))))

(defn project-render-exception!
  "Route a render-time `Throwable` through the SSR error projector for
  `frame-id`. Synthesises a `:rf.error/ssr-render-failed` trace event
  carrying the exception and drives the projector via
  `apply-error-projection!` so the response accumulator's `:status`
  is stamped with the projector's output. Also emits the trace on the
  trace bus so monitoring listeners (`register-listener!`) see the
  rich internal detail (Spec 011 §Internal trace events are not
  leaked).

  Returns the public-error map produced by the projector (the
  caller's contract for rendering the wire error body), or `nil`
  when projection is not applicable (frame missing / not a server
  frame).

  Per Spec 011 §Server error projection — unifies render-time
  failures (tag-name validator, view-fn throw, hiccup-walk error)
  with drain-time failures (fx-handler, sub-handler exceptions)
  under the same projector pipeline (rf2-zwgsv / rf2-i9f0g
  Option B).

  Dev escape-hatch (Spec 011 §View-time exceptions, rf2-ee38b.10):
  when the frame declares `:ssr {:on-view-exception :throw}`, the
  exception is RE-THROWN unchanged instead of projected — hosts that
  prefer eager exceptions during dev (to surface bugs early) opt in
  per-frame. Production should always project (the default)."
  [frame-id ^Throwable t]
  (when (and frame-id (error-projector/server-frame? frame-id)
             (= :throw (get-in (frame/frame-meta frame-id)
                               [:ssr :on-view-exception])))
    ;; Dev opt-in — surface the original throwable to the host's outer
    ;; handler rather than projecting it to a sanitised public-error.
    (throw t))
  (when (and frame-id (error-projector/server-frame? frame-id))
    (let [tags {:frame             frame-id
                :exception         t
                :exception-message #?(:clj  (.getMessage t)
                                      :cljs (.-message t))
                :ex-class          #?(:clj  (.getName (class t))
                                      :cljs (str (type t)))
                :recovery          :projected-to-public-error}
          ;; Build the event in the same envelope shape the trace bus
          ;; produces, so projector implementations that case on
          ;; :operation see the same key whether the event arrived via
          ;; the listener-buffer drain (drain-time errors) or our
          ;; synthesised render-time path (Spec 011 §Server error
          ;; projection §Pipeline step 1: "an exception occurs
          ;; (handler, fx, sub, render-time view)").
          trace-event {:op-type   :error
                       :operation :rf.error/ssr-render-failed
                       :tags      tags}]
      ;; Drain the listener buffer first so an earlier in-drain trace
      ;; (e.g. an :rf.error/fx-handler-exception that fired during
      ;; on-create) is not silently dropped if the render-time throw
      ;; reaches us after a drain that buffered a trace. The 1-arity
      ;; call is a no-op when the buffer is empty.
      (apply-error-projection! frame-id)
      ;; Emit on the trace bus so monitoring listeners see the rich
      ;; internal trace event for the render-time failure. The
      ;; listener will buffer the trace under :ssr.error/render-failed;
      ;; we drain it again via the 1-arity call below so the buffer
      ;; clears.
      (trace/emit-error! :rf.error/ssr-render-failed tags)
      ;; EP-0008 (rf2-hhutya): ALSO ride the always-on error-emit axis so
      ;; an off-box shipper on a `-Dre-frame.debug=false` JVM SSR host sees
      ;; the structured render-failure record (the dev trace above is
      ;; elided there). `:rf.error/ssr-render-failed` is PROJECTION-ELIGIBLE
      ;; — the always-on `error-emit-projection-listener` (id
      ;; `::error-projection`) buffers it onto the SAME pending-error-traces
      ;; atom as the dev listener; the `consume-pending-traces!` clear below
      ;; drops BOTH buffered duplicates, so the wire status is driven solely
      ;; by the DIRECT `apply-error-projection!` call (no double-stamp, no
      ;; re-project on a later flush). Union record shape.
      (error-projector/emit-always-on-error!
        {:error :rf.error/ssr-render-failed
         :frame frame-id
         :time  (interop/now-ms)
         :exception         t
         :exception-message #?(:clj  (.getMessage t)
                               :cljs (.-message t))
         :ex-class          #?(:clj  (.getName (class t))
                               :cljs (str (type t)))
         :recovery          :projected-to-public-error})
      ;; Apply directly with the synthesised trace event — this is
      ;; the projection that stamps :status on the response and
      ;; returns the public-error map the caller uses to render the
      ;; wire body.
      (let [public-error (apply-error-projection! frame-id trace-event)]
        ;; Clear any duplicate buffer entry the listeners appended above
        ;; (apply-error-projection! 2-arity does not drain). Without
        ;; this a later peek/flush would re-project the same event.
        ;; Clears BOTH the dev-trace AND the always-on buffered duplicates.
        (consume-pending-traces! frame-id)
        public-error))))

(defn error-projection-listener
  "Dev-only trace-cb listener — captures error trace events bound to a
  server frame in the per-frame pending-error-traces buffer. Buffering
  avoids the race where an in-flight handler's `{:db ...}` would clobber
  an inline :rf/response write. Registered in the `re-frame.ssr` façade
  under `::error-projection`.

  This listener covers the `:rf.error/*` categories that fire through
  `trace/emit-error!`: `:rf.error/no-such-handler`,
  `:rf.error/no-such-route`, `:rf.error/schema-validation-failure`,
  `:rf.error/drain-depth-exceeded`. They elide under
  `interop/debug-enabled? = false`. The production-survivable channel for
  the 500-class errors is `error-emit-projection-listener` (below).

  `:rf.error/sub-exception` ALSO arrives here (it still emits on the dev
  trace surface), but as of rf2-vvwmi it ALSO rides the always-on
  substrate below — that is its production status source of truth (a sub
  throwing mid-`render-to-string` under production hardening must project
  a fail-closed 5xx, not recover to a silent 200). In dev both listeners
  fire for sub-exception — last-write-wins + idempotent projection makes
  the duplicate benign (rf2-fb598)."
  [event]
  (when (= :error (:op-type event))
    (let [op (:operation event)]
      ;; Skip our own sanitisation traces to avoid recursion, and skip
      ;; recoverable-degradation categories (rf2-lia3i / rf2-sccp5 — e.g.
      ;; `:rf.error/ssr-head-resolution-failed`,
      ;; `:rf.error/ssr-ring-error-view-failed`) that must ship their
      ;; trace for observability without projecting a non-200 status.
      (when-not (or (= :rf.error/sanitised-on-projection op)
                    (non-projection-eligible-error? op))
        (when-let [frame-id (candidate-frame-for-error event)]
          (buffer-error-trace! frame-id event))))))

(defn error-emit-projection-listener
  "Always-on error-emit-substrate listener (per rf2-fb598 / audit Finding
  #3) — captures `:rf.error/*` records delivered via
  `register-error-listener!` and buffers them onto the per-frame
  pending-error-traces buffer in the same trace-event shape the
  projector consumes.

  The error-emit record arrives in the UNION shape `{:error <kw> :frame
  <id-or-nil> :time <ms> + flat category-specific keys}` (rf2-hhutya /
  rf2-ini4wr — the shared non-event always-on shape). The event-centric
  `dispatch-on-error!` path carries `:event` / `:event-id` / `:elapsed-ms`
  / `:source-coord`; the EP-0008 SSR promotions carry `:exception` /
  `:phase` / `:reason` / `:projector-id` / … instead. We synthesise the
  `{:operation :op-type :tags}` envelope the existing projector pipeline
  expects — symmetric with the trace-cb delivery — by lifting EVERY
  non-`:error` slot onto `:tags` GENERICALLY (rf2-hhutya), so a custom
  projector reading `(get-in event [:tags :exception])` sees the same keys
  on this always-on path as on the trace-cb path regardless of which
  category was promoted. `:operation` is the record's `:error`; `:recovery`
  defaults to `:no-recovery` when the record didn't carry one.

  Registered in the `re-frame.ssr` façade under `::error-projection`.
  Survives `interop/debug-enabled? = false` — Spec 011 §Server error
  projection holds under production hardening (rf2-vnjfg)."
  [record]
  (let [op (:error record)]
    ;; Symmetric with the trace-cb guard above — refuse our own
    ;; sanitisation records to avoid recursion. (As of rf2-fb598
    ;; `:rf.error/sanitised-on-projection` is not delivered through the
    ;; error-emit substrate, but keep the guard so a future routing
    ;; change can't reintroduce a re-entrant projection.) Also refuse
    ;; recoverable-degradation categories (rf2-lia3i / rf2-sccp5 —
    ;; `:rf.error/ssr-head-resolution-failed`,
    ;; `:rf.error/ssr-ring-error-view-failed`): if a future host-adapter
    ;; change routes a recoverable-degradation trace through the always-on
    ;; substrate (they ride only the dev trace bus today, but non-Ring
    ;; adapters MUST emit the same categories per Spec 011 §1070), it must
    ;; STILL not project a non-200 — the skip is symmetric across both
    ;; buffering paths so the degraded-200 contract holds regardless of
    ;; which substrate carries the trace.
    (when-not (or (= :rf.error/sanitised-on-projection op)
                  (non-projection-eligible-error? op))
      (let [;; The error-emit record carries the frame on its flat `:frame`
            ;; slot directly. Per rf2-7d30s every error-emit site reachable
            ;; inside a server-frame drain stamps that slot from the
            ;; drain's known frame; a record with no routable server frame
            ;; correctly no-ops (no projector runs, no stray status). Per
            ;; rf2-mn4rd the former `candidate-frame-for-error` fallback was
            ;; dead: it read `[:tags :frame]` on the synthesised event,
            ;; which IS `(:frame record)` re-applied through the same
            ;; `server-frame?` predicate — so it could never yield a frame
            ;; the direct check below had not already accepted.
            direct-frame-id (:frame record)
            ;; Synthesise a trace-event-shaped envelope GENERICALLY
            ;; (rf2-hhutya): the projector reads `(:operation event)` and
            ;; tag-shaped custom projectors read `(get-in event [:tags …])`.
            ;; Lift EVERY non-`:error` slot of the union record onto `:tags`
            ;; — both the event-centric `dispatch-on-error!` slots
            ;; (`:event` / `:event-id` / `:elapsed-ms` / `:source-coord`)
            ;; AND the flat category-specific slots the EP-0008 SSR
            ;; promotions carry (`:exception` / `:phase` / `:reason` /
            ;; `:projector-id` / …). `:recovery` defaults to `:no-recovery`
            ;; when the record didn't supply one. Generic-lift means a newly
            ;; promoted category's tags ride through without re-listing each
            ;; slot here.
            event {:operation op
                   :op-type   :error
                   :tags      (-> (dissoc record :error)
                                  (update :recovery #(or % :no-recovery)))}]
        (when (and direct-frame-id
                   (error-projector/server-frame? direct-frame-id))
          (buffer-error-trace! direct-frame-id event))))))

(defn peek-response
  "PURE read of the resolved response accumulator for a frame — does
  NOT drain pending error projections. The internal
  `:rf.server/_status-writes` / `:rf.server/_redirect-writes`
  bookkeeping keys are stripped.

  Use this from debug paths or midpoint inspections where draining the
  projector buffer (the side-effect baked into `get-response`) would
  consume a trace the host had not yet observed."
  [frame-id]
  (-> (response/response-of frame-id)
      (dissoc response/status-writes-key response/redirect-writes-key)))

(defn flush-response!
  "Drain any pending error projection for `frame-id` and return the
  resolved response. Side-effecting — every call clears the projector
  buffer; the first call after an error trace wins (last-write-wins,
  mirroring `:rf.server/set-status`).

  This is the explicit-side-effect spelling. `get-response` is the
  canonical host-adapter alias for the same drain-then-read sequence;
  `peek-response` is the pure-read counterpart for callers that want
  to opt out of the drain side effect."
  [frame-id]
  (apply-error-projection! frame-id)
  (peek-response frame-id))

(defn get-response
  "Read the resolved response accumulator for a frame. Public surface
  for host adapters that consume the accumulator after drain to build
  the wire response. The internal `:rf.server/_status-writes` /
  `:rf.server/_redirect-writes` bookkeeping keys are stripped.

  Flushes any pending error projections before reading so the
  response's `:status` reflects the active projector's output. Per
  Spec 011 §Server error projection — \"runtime sets `:rf.server/set-
  status` to the public-error's :status\".

  `get-response` is the canonical host-adapter alias for the drain-
  then-read sequence. `flush-response!` is the explicit-side-effect
  spelling; `peek-response` is the pure read. All three exist so
  callers can opt into the side-effect explicitly."
  [frame-id]
  (flush-response! frame-id))

(defn clear-pending-error-traces!
  "Drop frame-id's entry from the pending-error-traces buffer.
  Called from `re-frame.ssr.request/on-frame-destroyed!`. Keyed by the
  (realm, frame) ADDRESS (rf2-bzw8gd) so clearing one realm's frame leaves
  another realm's same-id buffered traces intact."
  [frame-id]
  (swap! pending-error-traces dissoc (frame/frame-address frame-id)))
