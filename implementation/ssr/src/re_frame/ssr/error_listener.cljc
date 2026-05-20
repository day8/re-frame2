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
  "Select the frame to project against for a trace-event. Prefer the
  frame named in :tags :frame; otherwise pick a single registered
  server frame if exactly one exists. Returns nil when no server
  frame applies (so client-platform errors don't write to a stray
  response accumulator)."
  [trace-event]
  (let [tag-frame (get-in trace-event [:tags :frame])]
    (cond
      (and tag-frame (error-projector/server-frame? tag-frame))
      tag-frame

      ;; Routing's :rf.error/no-such-handler may not have carried :frame
      ;; in older code paths. Fall back to the single active server
      ;; frame if there is exactly one — the canonical SSR-request
      ;; shape.
      (nil? tag-frame)
      (let [server-fids (filter error-projector/server-frame? (frame/frame-ids))]
        (when (= 1 (count server-fids))
          (first server-fids)))

      :else nil)))

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
     (let [public    (error-projector/project-error frame-id trace-event)
           existing  (response/response-of frame-id)
           redirect? (:redirect existing)]
       (when-not redirect?
         (response/swap-response! frame-id
                                  (fn [r] (assoc r :status (:status public)))))
       public))))

(defn project-render-exception!
  "Route a render-time `Throwable` through the SSR error projector for
  `frame-id`. Synthesises a `:rf.error/ssr-render-failed` trace event
  carrying the exception and drives the projector via
  `apply-error-projection!` so the response accumulator's `:status`
  is stamped with the projector's output. Also emits the trace on the
  trace bus so monitoring listeners (`register-trace-cb!`) see the
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
  Option B)."
  [frame-id ^Throwable t]
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
      (let [public (apply-error-projection! frame-id trace-event)]
        ;; Clear any duplicate buffer entry the listener appended above
        ;; (apply-error-projection! 2-arity does not drain). Without
        ;; this a later peek/flush would re-project the same event.
        (consume-pending-traces! frame-id)
        public))))

(defn error-projection-listener
  "Trace-event listener — captures error trace events bound to a server
  frame in the per-frame pending-error-traces buffer. Buffering avoids
  the race where an in-flight handler's `{:db ...}` would clobber an
  inline :rf/response write. Registered in the `re-frame.ssr` façade
  under `::error-projection`.

  NOTE (rf2-fb598): the production-survivable install site is
  `error-emit-projection-listener` (below) which rides the always-on
  `register-error-emit-listener!` substrate. This trace-cb listener
  is preserved for the dev-only `:rf.error/*` categories that fire
  only through `trace/emit-error!` — `:rf.error/no-such-handler`,
  `:rf.error/no-such-route`, `:rf.error/schema-validation-failure`,
  `:rf.error/sub-exception`, `:rf.error/drain-depth-exceeded`. These
  do not have an always-on emission path; they elide under
  `interop/debug-enabled? = false` along with the trace surface they
  ride. The 500-class errors (`:rf.error/handler-exception`,
  `:rf.error/fx-handler-exception` family, `:rf.error/flow-eval-
  exception`) DO have an always-on emission path and reach the
  projector via `error-emit-projection-listener` regardless of the
  dev/prod gate. In dev both listeners fire and buffer the same logical
  error twice — apply-error-projection! 1-arity is last-write-wins and
  the projector is idempotent, so the duplicate is benign."
  [event]
  (when (= :error (:op-type event))
    (let [op (:operation event)]
      ;; Skip our own sanitisation traces to avoid recursion.
      (when-not (= :rf.error/sanitised-on-projection op)
        (when-let [fid (candidate-frame-for-error event)]
          (buffer-error-trace! fid event))))))

(defn error-emit-projection-listener
  "Always-on error-emit-substrate listener (per rf2-fb598 / audit Finding
  #3) — captures `:rf.error/*` records delivered via
  `register-error-emit-listener!` and buffers them onto the per-frame
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
      (let [;; Frame may be on the flat record directly (`:frame`); fall
            ;; back to the candidate-frame lookup used on the trace-cb
            ;; path so a record missing `:frame` still routes to the
            ;; single active server frame when one exists.
            direct-fid (:frame record)
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
        (when-let [fid (if (and direct-fid (error-projector/server-frame? direct-fid))
                         direct-fid
                         (candidate-frame-for-error event))]
          (buffer-error-trace! fid event))))))

(defn peek-response
  "PURE read of the resolved response accumulator for a frame — does
  NOT drain pending error projections. The internal
  `:rf.server/_status-writes` / `:rf.server/_redirect-writes`
  bookkeeping keys are stripped.

  Use this from debug paths or midpoint inspections where draining the
  projector buffer (the side-effect baked into `get-response`) would
  consume a trace the host had not yet observed (audit rf2-asmj1 P5 /
  cluster rf2-sljs1)."
  [frame-id]
  (-> (response/response-of frame-id)
      (dissoc response/status-writes-key response/redirect-writes-key)))

(defn flush-response!
  "Drain any pending error projection for `frame-id` and return the
  resolved response. Side-effecting — every call clears the projector
  buffer; the first call after an error trace wins (last-write-wins,
  mirroring `:rf.server/set-status`).

  This is the read host adapters call once at drain settle-point.
  `get-response` is preserved as the back-compat alias (audit
  rf2-asmj1 P5 / cluster rf2-sljs1)."
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

  Note (audit rf2-asmj1 P5 / cluster rf2-sljs1): `get-response` is the
  drain-then-read entry point — equivalent to `flush-response!`. The
  pure read (no drain) is `peek-response`. Both new names exist so a
  caller can opt into the side-effect explicitly; `get-response` is
  preserved as the canonical host-adapter call."
  [frame-id]
  (flush-response! frame-id))

(defn clear-pending-error-traces!
  "Drop frame-id's entry from the pending-error-traces buffer.
  Called from `re-frame.ssr.request/on-frame-destroyed!`."
  [frame-id]
  (swap! pending-error-traces dissoc frame-id))
