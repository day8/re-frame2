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

;; ---- rf2-apwkm — closed-set `:retry :on` validation ----------------------
;;
;; Per Spec 014 §Closed-set `:retry :on` validation: `:retry :on` is
;; restricted to the *retryable* subset of the failure-category vocabulary.
;; The other `:rf.http/*` categories (`:rf.http/aborted`,
;; `:rf.http/decode-failure`, `:rf.http/accept-failure`) are
;; non-retryable by construction and rejected at fx-call time — the
;; runtime previously rejected only `:rf.http/aborted` and only at
;; retry-attempt time, letting useless members ride for the request's
;; lifetime. The closed-set tighten catches misuse at the dispatch site.
(def retryable-categories
  "The closed set of `:rf.http/*` failure categories permitted in
  `:retry :on`. Per Spec 014 §Closed-set `:retry :on` validation
  (rf2-apwkm)."
  #{:rf.http/transport
    :rf.http/cors
    :rf.http/timeout
    :rf.http/http-4xx
    :rf.http/http-5xx})

(defn- validate-retry!
  "Per Spec 014 §Closed-set `:retry :on` validation (rf2-apwkm): a
  `:retry :on` set may only contain members of `retryable-categories`.
  Anything outside (a non-retryable `:rf.http/*` category like
  `:rf.http/aborted`, `:rf.http/decode-failure`, `:rf.http/accept-failure`
  — or any keyword outside the `:rf.http/*` namespace) throws an
  `:rf.error/http-bad-retry-on` ex-info per Spec 009 §Error event
  catalogue. The throw fires at fx-call time, before `run-attempt!`,
  so the misuse surfaces at the dispatch site rather than being
  silently swallowed inside the transport retry loop.

  Absent `:retry`, absent `:on`, or an empty `:on` set: no-op. A
  caller who supplied `:retry` without `:on` already disables retry
  (the transport loop's `(contains? on-set kind)` gate is false for
  every kind), so we don't force `:on` to be non-empty here."
  [args-map]
  (when-let [on (some-> args-map :retry :on)]
    (when (seq on)
      (let [bad-members (into #{} (remove retryable-categories) on)]
        (when (seq bad-members)
          (throw (ex-info ":rf.error/http-bad-retry-on"
                          {:where         :rf.http/managed
                           :recovery      :no-recovery
                           :bad-members   bad-members
                           :retryable-set retryable-categories
                           :reason        "`:retry :on` must be drawn exclusively from the closed retryable set #{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}; `:rf.http/aborted`, `:rf.http/decode-failure`, and `:rf.http/accept-failure` are non-retryable by construction"})))))))

(defn- normalise-args
  "Validate + normalise the args map. Returns a context ready for the
  per-host attempt loop.

  `frame-ctx` carries the resolved `:event` (the originating event
  vector) — `managed-handler` runs `encoding/resolve-origin-event`
  once before calling here and stashes the result back into
  `frame-ctx` as `:event`, so the resolution shape lives in exactly
  one place per rf2-622e3.

  Per Spec 014 §`:timeout-ms` security defaults (rf2-it1cd):

  - key absent      → 30000 ms (the security default)
  - any int         → that value
  - `nil` or `0`    → opt out (no per-attempt timeout)

  The `:or {timeout-ms 30000}` clause below substitutes only when the
  key is ABSENT (Clojure destructuring semantics — `:or` does not
  fire on an explicit `nil` value). The downstream JVM/CLJS transport
  treats nil/0 as opt-out, so the three-way contract is preserved
  end-to-end without any reshaping here."
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
  not dispatched.

  Per rf2-1jcpm — the `:sensitive?` flag is resolved BEFORE the
  middleware runs and BEFORE `check-cljs-only-keys!` fires, so every
  warning-/error-path trace that carries a request URL can redact
  through the privacy composer rather than leaking secrets. The
  same flag is then re-stamped onto the normalised ctx so the
  attempt loop in `http-transport` sees a single resolved value."
  [frame-ctx args-map]
  ;; rf2-apwkm — closed-set `:retry :on` validation. Fires BEFORE the
  ;; middleware chain runs so misuse surfaces at the dispatch site
  ;; rather than being deferred to retry-attempt time inside the
  ;; transport loop (or silently dropped when the bad member never
  ;; fires). Per Spec 014 §Closed-set `:retry :on` validation.
  (validate-retry! args-map)
  (let [frame-id     (or (:frame frame-ctx) :rf/default)
        ;; rf2-622e3 — resolve once, thread the result through
        ;; frame-ctx's :event slot so normalise-args reads it
        ;; directly instead of re-running the OR-chain.
        origin-event (encoding/resolve-origin-event frame-ctx args-map)
        frame-ctx'   (assoc frame-ctx :event origin-event)
        ;; rf2-1jcpm — resolve :sensitive? once at handler entry so
        ;; the middleware-failure trace path (URL leak via
        ;; `:rf.error/http-interceptor-failed`) and the JVM CLJS-only
        ;; warning path (`:rf.http/cljs-only-key-ignored-on-jvm`) both
        ;; redact through the privacy composer. `normalise-args` then
        ;; re-derives the same flag from `args-map` — the values agree
        ;; by construction.
        sensitive?   (privacy/request-sensitive? args-map origin-event)
        _            (transport/check-cljs-only-keys! args-map sensitive?)
        ctx0         {:request    (:request args-map)
                      :args       args-map
                      :frame      frame-id
                      :event      origin-event
                      :sensitive? sensitive?}
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
