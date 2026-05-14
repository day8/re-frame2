(ns re-frame.http-handlers
  "The `:rf.http/managed` + `:rf.http/managed-abort` fx handler bodies.

  Extracted from `re-frame.http-managed` per rf2-0eyp2 — the façade now
  re-exports these and wires them into `fx/reg-fx` at load time, but
  the handler logic lives here alongside the other per-concern siblings
  (`http-encoding`, `http-registry`, `http-middleware`, `http-transport`,
  `http-machine-wrapper`, `http-privacy`).

  Two public fns:

  - `managed-handler`       — `:rf.http/managed` body. Threads the
                              request through the per-frame interceptor
                              chain, normalises args, supersedes any
                              prior in-flight request with the same
                              `:request-id`, then dispatches to the
                              shared attempt-and-retry loop in
                              `http-transport`.
  - `managed-abort-handler` — `:rf.http/managed-abort` body. Resolves
                              the abort-fn through the in-flight
                              registry and fires it; cleanup belongs
                              to the abort-fn → `finalise-failure!`
                              cascade per rf2-plngk."
  (:require [re-frame.http-encoding  :as encoding]
            [re-frame.http-middleware :as middleware]
            [re-frame.http-privacy   :as privacy]
            [re-frame.http-registry  :as registry]
            [re-frame.http-transport :as transport]))

(defn- normalise-args
  "Validate + normalise the args map. Returns a context ready for the
  per-host attempt loop.

  `frame-ctx` carries the resolved `:event` (the originating event
  vector) — `managed-handler` runs `encoding/resolve-origin-event`
  once before calling here and stashes the result back into
  `frame-ctx` as `:event`, so the resolution shape lives in exactly
  one place per rf2-622e3."
  [{:keys [request decode accept retry timeout-ms
           on-success on-failure request-id abort-signal]
    :or   {timeout-ms 30000}
    :as   args-map}
   frame-ctx]
  (let [origin-event (:event frame-ctx)
        frame        (or (:frame frame-ctx) :rf/default)
        ;; rf2-wvkn — when the originating event-id is a spawned actor's
        ;; address, capture it so the in-flight registry can index by
        ;; actor-id alongside :request-id. The destroy cascade then has
        ;; a key to walk on actor-destroy. Detection is structural —
        ;; we look up the id in the frame's [:rf/spawned ...] runtime
        ;; registry (per Spec 005 §Declarative :invoke); ordinary event
        ;; handlers' dispatches yield nil and are not tracked.
        actor-id     (registry/compute-actor-id frame origin-event)
        ;; rf2-bma05 — compute the effective :sensitive? flag once and
        ;; thread it through the attempt-and-retry loop. Three sources
        ;; (OR-reduced): per-call args, per-request, and the originating
        ;; handler's registration metadata. The flag rides every
        ;; :rf.http/* trace event emitted within the cascade so
        ;; consumers honour the privacy contract per Spec 009 §Privacy.
        sensitive?   (privacy/request-sensitive? args-map origin-event)
        ;; rf2-wu1n5 — keyword-interning DoS guard. The reserved
        ;; `:rf.http/max-decoded-keys` arg overrides the JSON reader's
        ;; default cap on unique decoded object keys. Absent → reader
        ;; default (`util-json/default-max-decoded-keys`, 10000). Per
        ;; Spec 014 §Decoding.
        max-keys     (:rf.http/max-decoded-keys args-map)]
    {:request           request
     :decode            decode
     :decode-supplied?  (some? decode)
     :accept            accept
     :retry             retry
     :timeout-ms        timeout-ms
     :max-decoded-keys  max-keys
     :origin-event      origin-event
     :explicit-on-success
     {:supplied? (contains? args-map :on-success)
      :value     on-success}
     :explicit-on-failure
     {:supplied? (contains? args-map :on-failure)
      :value     on-failure}
     :request-id        request-id
     :actor-id          actor-id
     :abort-signal      abort-signal
     :frame             frame
     :attempt           1
     :sensitive?        sensitive?}))

(defn managed-handler
  "The public `:rf.http/managed` fx body. `frame-ctx` carries `:frame`
  and (when threaded by the runtime, per the do-fx 5-arity) `:event` —
  the originating event vector used for default reply addressing per
  Spec 014 §Reply addressing.

  Per Spec 014 §Middleware (rf2-6y3q): before normalising args, the
  per-frame interceptor chain is walked. Each `:before` transforms a
  ctx `{:request :args :frame :event}`; the runtime threads its return
  value through the rest of the chain. A throw inside any `:before`
  classifies as `:rf.error/http-interceptor-failed`; the request is
  not dispatched."
  [frame-ctx args-map]
  (transport/check-cljs-only-keys! args-map)
  (let [frame-id     (or (:frame frame-ctx) :rf/default)
        ;; rf2-622e3 — resolve once, thread the result through
        ;; frame-ctx's :event slot so normalise-args reads it
        ;; directly instead of re-running the OR-chain.
        origin-event (encoding/resolve-origin-event frame-ctx args-map)
        frame-ctx'   (assoc frame-ctx :event origin-event)
        ctx0         {:request (:request args-map)
                      :args    args-map
                      :frame   frame-id
                      :event   origin-event}
        ctx          (middleware/run-interceptor-chain! frame-id ctx0)
        args-map'    (assoc args-map :request (:request ctx))
        normalised   (normalise-args args-map' frame-ctx')
        request-id   (:request-id normalised)]
    (when request-id (registry/supersede! request-id))
    (transport/run-attempt! normalised)
    nil))

(defn managed-abort-handler
  "Public `:rf.http/managed-abort` fx. Args is the request-id (any value).

  Per rf2-plngk the in-flight cleanup is owned by `finalise-failure!`
  (the abort-fn closure calls into it). The earlier shape pre-cleared
  the registry here AND inside `finalise-failure!`, doubling the
  `swap!` traffic per abort. Now the single source of truth lives at
  the failure-finalise site; this handler only fires the abort-fn."
  [_frame-ctx request-id]
  (when-let [handle (registry/lookup-in-flight request-id)]
    (try ((:abort-fn handle) :user)
         (catch #?(:clj Throwable :cljs :default) _ nil)))
  nil)
