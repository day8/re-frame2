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
            [re-frame.ssr.error-projector :as error-projector]
            [re-frame.ssr.response :as response]
            [re-frame.trace :as trace]))

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
  [frame-id trace-event]
  (swap! pending-error-traces update frame-id (fnil conj []) trace-event))

(defn- consume-pending-traces!
  "Atomically pull and clear the pending error traces for frame-id."
  [frame-id]
  (let [snap @pending-error-traces
        traces (get snap frame-id [])]
    (when (seq traces)
      (swap! pending-error-traces dissoc frame-id))
    traces))

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
      ;; Apply directly with the synthesised trace event — this is
      ;; the projection that stamps :status on the response and
      ;; returns the public-error map the caller uses to render the
      ;; wire body.
      (let [public-error (apply-error-projection! frame-id trace-event)]
        ;; Clear any duplicate buffer entry the listener appended above
        ;; (apply-error-projection! 2-arity does not drain). Without
        ;; this a later peek/flush would re-project the same event.
        (consume-pending-traces! frame-id)
        public-error))))

(defn error-projection-listener
  "Dev-only trace-cb listener — captures error trace events bound to a
  server frame in the per-frame pending-error-traces buffer. Buffering
  avoids the race where an in-flight handler's `{:db ...}` would clobber
  an inline :rf/response write. Registered in the `re-frame.ssr` façade
  under `::error-projection`.

  This listener covers ONLY the `:rf.error/*` categories that fire
  exclusively through `trace/emit-error!` (no always-on emission path):
  `:rf.error/no-such-handler`, `:rf.error/no-such-route`,
  `:rf.error/schema-validation-failure`, `:rf.error/sub-exception`,
  `:rf.error/drain-depth-exceeded`. They elide under
  `interop/debug-enabled? = false`. The production-survivable channel for
  the 500-class errors is `error-emit-projection-listener` (below). In
  dev both fire for those — last-write-wins + idempotent projection makes
  the duplicate benign (rf2-fb598)."
  [event]
  (when (= :error (:op-type event))
    (let [op (:operation event)]
      ;; Skip our own sanitisation traces to avoid recursion.
      (when-not (= :rf.error/sanitised-on-projection op)
        (when-let [frame-id (candidate-frame-for-error event)]
          (buffer-error-trace! frame-id event))))))

(defn error-emit-projection-listener
  "Always-on error-emit-substrate listener (per rf2-fb598 / audit Finding
  #3) — captures `:rf.error/*` records delivered via
  `register-error-listener!` and buffers them onto the per-frame
  pending-error-traces buffer in the same trace-event shape the
  projector consumes.

  The error-emit record arrives as the tight flat shape
  `{:error :event :event-id :frame :time :exception :elapsed-ms
    :source-coord}` (per `re-frame.error-emit/dispatch-on-error!`'s
  contract). We synthesise the `{:operation :op-type :tags}` envelope
  the existing projector pipeline expects — symmetric with the trace-cb
  delivery — so the projector body is substrate-agnostic.

  Registered in the `re-frame.ssr` façade under `::error-projection`.
  Survives `interop/debug-enabled? = false` — Spec 011 §Server error
  projection holds under production hardening (rf2-vnjfg)."
  [record]
  (let [op (:error record)]
    ;; Symmetric with the trace-cb guard above — refuse our own
    ;; sanitisation records to avoid recursion. (As of rf2-fb598
    ;; `:rf.error/sanitised-on-projection` is not delivered through the
    ;; error-emit substrate, but keep the guard so a future routing
    ;; change can't reintroduce a re-entrant projection.)
    (when-not (= :rf.error/sanitised-on-projection op)
      (let [;; Frame is normally on the flat error-emit record directly
            ;; (`:frame`). When absent we consult `candidate-frame-for-
            ;; error` on the synthesised event — which, per rf2-7d30s, now
            ;; only honours an explicit `[:tags :frame]` and no longer
            ;; guesses the single active server frame. A record with no
            ;; routable server frame correctly no-ops (see
            ;; `candidate-frame-for-error`).
            direct-frame-id (:frame record)
            ;; Synthesise a trace-event-shaped envelope. The projector
            ;; reads `(:operation event)`; we copy the relevant flat
            ;; slots onto `:tags` so custom projectors using the tag
            ;; shape (e.g. `(get-in event [:tags :exception])`) see the
            ;; same keys they would on the trace path.
            event {:operation op
                   :op-type   :error
                   :tags      {:frame             (:frame      record)
                               :event-id          (:event-id   record)
                               :event             (:event      record)
                               :exception         (:exception  record)
                               :elapsed-ms        (:elapsed-ms record)
                               :time              (:time       record)
                               :source-coord      (:source-coord record)
                               :recovery          :no-recovery}}]
        (when-let [frame-id (if (and direct-frame-id
                                     (error-projector/server-frame? direct-frame-id))
                              direct-frame-id
                              (candidate-frame-for-error event))]
          (buffer-error-trace! frame-id event))))))

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
  Called from `re-frame.ssr.request/on-frame-destroyed!`."
  [frame-id]
  (swap! pending-error-traces dissoc frame-id))
