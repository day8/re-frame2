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
            [re-frame.ssr.response :as response]))

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

(defn apply-pending-error-projection!
  "Drain frame-id's error-trace buffer, project each trace via the
  active projector, and stamp the LAST projection's :status onto the
  response accumulator (last-write-wins, mirroring the multi-status
  policy). Returns the last public-error projected, or nil when the
  buffer was empty / frame is not :server.

  Hosts that drive their own SSR loop call this after drain settles
  so the response carries the projector's status. The runtime also
  calls it automatically from get-response so a host reading the
  resolved response always sees up-to-date projection."
  [frame-id]
  (when (and frame-id (error-projector/server-frame? frame-id))
    (let [traces (consume-pending-traces! frame-id)]
      (when (seq traces)
        (let [;; Don't overwrite a redirect's :status — Spec 011
              ;; §Redirect precedence locks the redirect's status
              ;; through to the response.
              existing  (response/response-of frame-id)
              redirect? (:redirect existing)
              last-trace (last traces)
              public     (error-projector/project-error frame-id last-trace)]
          (when-not redirect?
            (response/swap-response! frame-id
                                     (fn [r] (assoc r :status (:status public)))))
          public)))))

(defn apply-error-projection!
  "Project trace-event via the active projector for frame-id and stamp
  the public-error's :status onto the response accumulator. Returns
  the public-error map on success, nil on no-op (frame missing / not
  server / redirect set).

  Public so host adapters that catch errors outside the trace stream
  can drive projection explicitly. Most callers want
  apply-pending-error-projection! instead — that one drains the
  trace-listener-buffered events and applies them in one shot."
  [frame-id trace-event]
  (when (and frame-id (error-projector/server-frame? frame-id))
    (let [public    (error-projector/project-error frame-id trace-event)
          existing  (response/response-of frame-id)
          redirect? (:redirect existing)]
      (when-not redirect?
        (response/swap-response! frame-id
                                 (fn [r] (assoc r :status (:status public)))))
      public)))

(defn error-projection-listener
  "Trace-event listener — captures error trace events bound to a server
  frame in the per-frame pending-error-traces buffer. Buffering avoids
  the race where an in-flight handler's `{:db ...}` would clobber an
  inline :rf/response write. Registered in the `re-frame.ssr` façade
  under `::error-projection`."
  [event]
  (when (= :error (:op-type event))
    (let [op (:operation event)]
      ;; Skip our own sanitisation traces to avoid recursion.
      (when-not (= :rf.error/sanitised-on-projection op)
        (when-let [fid (candidate-frame-for-error event)]
          (buffer-error-trace! fid event))))))

(defn get-response
  "Read the resolved response accumulator for a frame. Public surface
  for host adapters that consume the accumulator after drain to build
  the wire response. The internal `:rf.server/_status-writes` /
  `:rf.server/_redirect-writes` bookkeeping keys are stripped.

  Flushes any pending error projections before reading so the
  response's `:status` reflects the active projector's output. Per
  Spec 011 §Server error projection — \"runtime sets `:rf.server/set-
  status` to the public-error's :status\"."
  [frame-id]
  (apply-pending-error-projection! frame-id)
  (-> (response/response-of frame-id)
      (dissoc response/status-writes-key response/redirect-writes-key)))

(defn clear-pending-error-traces!
  "Drop frame-id's entry from the pending-error-traces buffer.
  Called from `re-frame.ssr.request/on-frame-destroyed!`."
  [frame-id]
  (swap! pending-error-traces dissoc frame-id))
