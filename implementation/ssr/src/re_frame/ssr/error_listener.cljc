(ns re-frame.ssr.error-listener
  "Trace-listener + per-frame error-trace buffer + projection drain +
  `get-response`. Per Spec 011 §Server error projection — the
  runtime-side glue that ties trace events to the active projector and
  stamps the public-error's `:status` onto the response accumulator.

  Listeners buffer candidate errors instead of projecting inside the callback.
  The settle-point drain then chooses the final error, respects redirect
  precedence, and updates the per-frame response accumulator atomically."
  (:require [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.ssr.error-projector :as error-projector]
            [re-frame.ssr.response :as response]
            [re-frame.trace :as trace]))

;; ---- always-on error emission ---------------------------------------------
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
;; The `emit-always-on-error!` helper lives in `re-frame.ssr.error-projector`;
;; call sites here go through
;; `error-projector/emit-always-on-error!`.

(defn- candidate-frame-for-error
  "Select the frame to project against for a trace-event: the frame named
  in `[:tags :frame]` when it names a registered server frame, else nil.

  Returns nil when the trace carries no routable server `:frame`; it never
  guesses from the set of live frames. Concurrent requests may have many
  server frames, so fallback attribution could project one request's error
  onto another response. Error sites inside a drain must stamp the known
  frame."
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
;; `:rf.error/ssr-head-resolution-failed` is the
;; canonical member: `resolve-head` degrades a throwing `:head` fn to an
;; empty fragment so a buggy head fn "can't take down the request"
;; (Spec 011 §1070 + lifecycle/resolve-head docstring). This is the
;; deliberate counterpoint to the view/sub fail-closed posture: a view or
;; reactive sub that throws mid-render projects a 5xx because the page is
;; unusable; a head fn that throws degrades to a 200 because only the
;; `<head>` metadata is missing — the body still renders and hydrates.
;;
;; Enforcing the contract at the buffering choke point (both
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
;; `:rf.error/ssr-ring-error-view-failed` (ssr-ring's
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
;; Two further non-projecting categories also use the always-on axis:
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
;;
;; rf2-6jqa8 — THE THREE `:rf.error/safe-redirect-*` CATEGORIES, and they are
;; the sharpest case in the set. `:rf.server/safe-redirect`'s five-step gate
;; is deliberately EMIT-AND-NO-OP rather than throw (`response.cljc`
;; §safe-redirect): "the cascade continues, the response's `:redirect` stays
;; unchanged". The request is not degraded at all — the page renders exactly
;; as it would have, minus a redirect the framework refused to perform. That
;; refusal IS the mitigation working.
;;
;; Promoting these onto the always-on axis without this skip would have made
;; `error-emit-projection-listener` buffer them and the default projector's
;; `:else` arm stamp the locked generic 500 — handing an attacker a trivial
;; denial of service: `?next=javascript:alert(1)` would turn a healthy page
;; into a 500. That is the inverse of the bead's intent. The governing rule
;; is the one stated at the head of this block and it is absolute here:
;; PROMOTION CHANGES WHAT SHIPPERS SEE, NEVER WHAT THE WIRE DOES.
;;
;; The skip is at this chokepoint, so it is symmetric across BOTH buffering
;; listeners — which also closes the pre-existing dev-side asymmetry: on the
;; trace-cb path these categories were projection-eligible, so a rejected
;; redirect could stamp a 500 in a dev build while a production build (where
;; nothing buffered at all) answered 200. Same wire in both postures now.
(def ^:private non-projection-eligible-errors
  #{:rf.error/ssr-head-resolution-failed
    :rf.error/ssr-ring-error-view-failed
    :rf.error/ssr-streaming-writer-failed
    :rf.error/sanitised-on-projection
    :rf.error/safe-redirect-invalid-url
    :rf.error/safe-redirect-scheme-rejected
    :rf.error/safe-redirect-host-disallowed
    ;; rf2-gblft — the Ring materialiser's fail-closed `:status` rewrite. It
    ;; fires at MATERIALISATION time, strictly AFTER the response is resolved
    ;; and flushed, so a projection could only fight the 500 it is already
    ;; reporting. Today its record is deliberately FRAMELESS (`:frame nil` —
    ;; the materialiser is a pure map→map fn), so nothing routes here anyway;
    ;; the entry is the same forward-looking symmetry the members above carry,
    ;; and it is what makes a future change that gives the materialiser a frame
    ;; safe rather than silently status-flipping every request it reports on.
    :rf.error/ssr-ring-response-status-invalid})

(defn- non-projection-eligible-error?
  "True when `operation` names a recoverable-degradation error category
  that must NOT be buffered for status projection (it ships its trace for
  observability but the request continues with a degraded fragment).
  Members include head-resolution, error-view, post-commit writer, and
  projector-sanitisation failures."
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
  ;; All SSR side channels use the process-local frame address.
  [frame-id trace-event]
  (swap! pending-error-traces update (frame/frame-address frame-id)
         (fnil conj []) trace-event))

(defn- consume-pending-traces!
  "Atomically pull and clear the pending error traces for frame-id.

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
  / not server / no pending trace). When a redirect is already set on the
  response the projected map is STILL returned — only the :status stamp is
  suppressed (per Spec 011 §Redirect precedence, below).

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
  server frame in the per-frame pending-error-traces buffer. Projection waits
  until the settle point so the host observes one last-write-wins result and
  redirect precedence is applied once. Registered in the `re-frame.ssr`
  façade under `::error-projection`.

  This listener covers every `:rf.error/*` category that fires through
  `trace/emit-error!` — which is a SUPERSET of the always-on axis, not a
  disjoint set. ONE projection-eligible category rides the dev bus ALONE
  and therefore elides under `interop/debug-enabled? = false`:
  `:rf.error/no-such-route` (the `route-url` caller-misuse throw, Spec 009
  catalogues it diagnostic).

  The rest arrive on BOTH buses, and the always-on
  `error-emit-projection-listener` (below) is their production status
  source of truth: `:rf.error/sub-exception` (a sub throwing
  mid-`render-to-string` under production hardening must project a
  fail-closed 5xx, not recover to a silent 200),
  `:rf.error/no-such-handler` (both the `:kind :event` dispatch miss and —
  since rf2-ov56u — the `:kind :route` URL miss the default projector maps
  to 404), `:rf.error/drain-depth-exceeded` (rf2-fcbrjo), and — since
  rf2-mwv4e — `:rf.error/schema-validation-failure` from the
  `:rf.schema/at-boundary` interceptor, which the default projector's
  `:where`-gated arm maps to 400 (RFC 9110 §15.5.1: a refused request
  payload is a client fault, not a server one). In dev both listeners fire
  for those — last-write-wins + idempotent projection makes the duplicate
  benign.

  THE BOUNDARY ENTRY USED TO SIT IN THE DEV-ONLY LIST, and both halves of
  its stated reason were wrong even before rf2-mwv4e promoted it. Boundary
  validation is ungated per Spec 010 §Production builds (one of several
  load-bearing checks that are — C-000.35 settles what may be elided by
  WHAT THE CHECK IS FOR, not by who declared the schema it reads; this is
  the one an application author installs): the CHECK was never elided, only
  its `trace/emit-error!` — the
  same overclaim rf2-mnmzh and rf2-bx4bf corrected elsewhere — so a
  production reject always existed; what did not exist was a record to
  project it from. rf2-mwv4e supplied that record (structural-only,
  `:source :boundary`), it is absent from `non-projection-eligible-errors`
  below, and the generic tag-lift in `error-emit-projection-listener` puts
  its `:where :event` where `error_projector/default-error-projector-fn`
  looks. `re-frame.ssr-boundary-rejection-400-production-test` is the
  witness, and it runs under the REAL gate."
  [event]
  (when (= :error (:op-type event))
    (let [op (:operation event)]
      ;; Skip our own sanitisation traces to avoid recursion, and skip
      ;; recoverable-degradation categories (for example,
      ;; `:rf.error/ssr-head-resolution-failed`,
      ;; `:rf.error/ssr-ring-error-view-failed`) that must ship their
      ;; trace for observability without projecting a non-200 status.
      (when-not (or (= :rf.error/sanitised-on-projection op)
                    (non-projection-eligible-error? op))
        (when-let [frame-id (candidate-frame-for-error event)]
          (buffer-error-trace! frame-id event))))))

(defn error-emit-projection-listener
  "Always-on error-emission listener. Captures `:rf.error/*` records delivered via
  `register-error-listener!` and buffers them onto the per-frame
  pending-error-traces buffer in the same trace-event shape the
  projector consumes.

  The error-emit record arrives in the UNION shape `{:error <kw> :frame
  <id-or-nil> :time <ms> + flat category-specific keys}`. The event-centric
  `dispatch-on-error!` path carries `:event` / `:event-id` / `:elapsed-ms`
  / `:source-coord`; other SSR records carry `:exception` /
  `:phase` / `:reason` / `:projector-id` / … instead. We synthesise the
  `{:operation :op-type :tags}` envelope the existing projector pipeline
  expects — symmetric with the trace-cb delivery — by lifting EVERY
  non-`:error` slot onto `:tags` generically, so a custom
  projector reading `(get-in event [:tags :exception])` sees the same keys
  on this always-on path as on the trace-cb path regardless of which
  category was promoted. `:operation` is the record's `:error`; `:recovery`
  defaults to `:no-recovery` when the record didn't carry one.

  Registered in the `re-frame.ssr` façade under `::error-projection`.
  Survives `interop/debug-enabled? = false` — Spec 011 §Server error
  projection holds when development tracing is disabled."
  [record]
  (let [op (:error record)]
    ;; Symmetric with the trace-cb guard above — refuse our own
    ;; sanitisation records to avoid recursion. The record is not currently
    ;; delivered through this substrate, but keep the guard so a future routing
    ;; change can't reintroduce a re-entrant projection.) Also refuse
    ;; recoverable-degradation categories such as
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
            ;; slot directly. Every error site reachable inside a server-frame
            ;; drain stamps that slot from the
            ;; drain's known frame; a record with no routable server frame
            ;; correctly no-ops (no projector runs, no stray status).
            direct-frame-id (:frame record)
            ;; Synthesise a trace-event-shaped envelope GENERICALLY
            ;; The projector reads `(:operation event)` and
            ;; tag-shaped custom projectors read `(get-in event [:tags …])`.
            ;; Lift EVERY non-`:error` slot of the union record onto `:tags`
            ;; — both the event-centric `dispatch-on-error!` slots
            ;; (`:event` / `:event-id` / `:elapsed-ms` / `:source-coord`)
            ;; and flat category-specific slots (`:exception` / `:phase` / `:reason` /
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

(defn flush-response-result!
  "Drain any pending error projection for `frame-id` ONCE and return BOTH
  the resolved response accumulator AND the projected `:rf/public-error`
  map (`nil` when no projection fired):

      {:response     <resolved-response-map>
       :public-error <projected-public-error-or-nil>}

  Host adapters branch on `:public-error` to classify the drain-time
  outcome — a projected 4xx (client-fault / renderable app) keeps the app
  root body + hydration payload, a projected 5xx (server fault, before the
  body commits) diverts to the error page — WITHOUT re-inferring projection
  from `(:status response)`. Status alone is not proof that an error was
  projected: an app that manually `:rf.server/set-status`-es a 500 with no
  error projected returns `:public-error nil` and stays on the app arm.

  Side-effecting — this is the SAME single drain `flush-response!` /
  `get-response` perform, so calling it consumes the pending trace: a
  SECOND call returns `{:response … :public-error nil}` for the
  already-consumed projection. `flush-response!` and `get-response`
  delegate to `(:response (flush-response-result! …))`, so response-only
  reads are unchanged. Per Spec 011 §Server error projection §Drain-time
  error classification (rf2-oytx7j)."
  [frame-id]
  (let [public-error (apply-error-projection! frame-id)]
    {:response     (peek-response frame-id)
     :public-error public-error}))

(defn flush-response!
  "Drain any pending error projection for `frame-id` and return the
  resolved response. Side-effecting — every call clears the projector
  buffer; the first call after an error trace wins (last-write-wins,
  mirroring `:rf.server/set-status`).

  This is the explicit-side-effect spelling. `get-response` is the
  canonical host-adapter alias for the same drain-then-read sequence;
  `peek-response` is the pure-read counterpart for callers that want
  to opt out of the drain side effect. `flush-response-result!` is the
  variant that ALSO returns the projected `:public-error` for classification."
  [frame-id]
  (:response (flush-response-result! frame-id)))

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

(defn pending-error-trace?
  "PURE predicate — true when `frame-id` has at least one buffered error
  trace still awaiting projection. Does NOT consume, drain, or project.

  Host adapters use this to close the error-view containment hole
  (rf2-oytx7j): the `resolve-error-body` `try/catch` catches a THROWING
  error view, but a reactive sub INSIDE the error view that RECOVERS to nil
  under production hardening does not throw — it silently buffers a
  fail-closed projection here. On entry to the error arm the buffer is
  empty (the drain / render-throw / post-render flush already consumed it),
  so a pending trace after the error-view render can only be that
  recovered-to-nil error-view sub — the signal to fall back once to the
  locked template."
  [frame-id]
  (boolean (seq (get @pending-error-traces (frame/frame-address frame-id)))))

(defn clear-pending-error-traces!
  "Drop frame-id's entry from the pending-error-traces buffer.
  Called from `re-frame.ssr.request/on-frame-destroyed!`. Keyed by the frame
  address."
  [frame-id]
  (swap! pending-error-traces dissoc (frame/frame-address frame-id)))
