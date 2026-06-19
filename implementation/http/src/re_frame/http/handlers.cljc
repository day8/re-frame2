(ns re-frame.http.handlers
  "The `:rf.http/managed` + `:rf.http/managed-abort` fx handler bodies.

  Extracted from `re-frame.http.managed` per rf2-0eyp2 — the façade now
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
  (:require [clojure.string]
            [re-frame.error          :as error]
            [re-frame.frame          :as frame]
            [re-frame.http.encoding  :as encoding]
            [re-frame.http.middleware :as middleware]
            [re-frame.http.privacy   :as privacy]
            [re-frame.http.registry  :as registry]
            [re-frame.http.reply     :as http-reply]
            [re-frame.http.transport :as transport]
            [re-frame.http.transport-jvm :as transport-jvm]))

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
  `:retry :on` value, when present and non-nil, MUST be a SET drawn
  exclusively from `retryable-categories`. Two failure shapes both throw
  `:rf.error/http-bad-retry-on` at fx-call time (before `run-attempt!`),
  so the misuse surfaces at the dispatch site rather than downstream:

  - SHAPE (rf2-4zldh): a non-set `:on` (keyword, vector, list, string,
    map, …). Spec 014:372 types `:on` as a *set* of category keywords
    and the transport loop's membership gate is `(contains? on-set kind)`.
    `contains?` on a vector/list/string tests INDEX/range membership, not
    value membership, so a non-set `:on` would silently DISABLE retry for
    every category — exactly the useless-policy-rides-for-the-lifetime
    misuse the dispatch-time guard exists to prevent. A bare keyword `:on`
    was even worse: it threw a raw `IllegalArgumentException` from the
    `(remove …)` ISeq coercion instead of the canonical error. We reject
    every non-set non-nil `:on` here, before any of that can happen.

  - MEMBERSHIP: a set `:on` carrying a non-retryable `:rf.http/*` category
    (`:rf.http/aborted`, `:rf.http/decode-failure`,
    `:rf.http/accept-failure`) or any keyword outside the `:rf.http/*`
    retryable subset.

  Both throw `:rf.error/http-bad-retry-on` per Spec 009 §Error event
  catalogue, distinguished by ex-data (`:bad-shape` vs `:bad-members`).

  Intentional no-retry shapes that pass untouched: absent `:retry`,
  absent `:on`, explicit `:on nil`, and the empty set `#{}`. A caller
  who supplies `:retry` with one of these already disables retry (the
  transport loop's `(contains? on-set kind)` gate is false for every
  kind), so we do not force `:on` to be non-empty here."
  [args-map]
  ;; `contains?` separates an explicit `:on nil` (intentional no-retry —
  ;; pass) from an absent key, so we read `:on` only when the `:retry`
  ;; map actually carries it and the value is non-nil.
  (let [retry (:retry args-map)]
    (when (and (map? retry) (contains? retry :on))
      (let [on (:on retry)]
        (when (some? on)
          ;; SHAPE: `:on` must be a set when present and non-nil.
          (when-not (set? on)
            (throw (error/thrown-ex-info
                     :rf.error/http-bad-retry-on :rf.http/managed
                     "`:retry :on` must be a SET of retryable-category keywords per Spec 014 §Closed-set `:retry :on` validation; a non-set value (keyword, vector, list, string, …) is rejected because the transport membership gate `(contains? on-set kind)` tests index membership over a sequential collection and would silently disable retry. Use `#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}`, `#{}` for no-retry, or omit `:on`"
                     {:extra {:bad-shape     on
                              :bad-type      (type on)
                              :retryable-set retryable-categories}})))
          ;; MEMBERSHIP: every member must be a retryable category.
          (let [bad-members (into #{} (remove retryable-categories) on)]
            (when (seq bad-members)
              (throw (error/thrown-ex-info
                       :rf.error/http-bad-retry-on :rf.http/managed
                       "`:retry :on` must be drawn exclusively from the closed retryable set #{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}; `:rf.http/aborted`, `:rf.http/decode-failure`, and `:rf.http/accept-failure` are non-retryable by construction"
                       {:extra {:bad-members   bad-members
                                :retryable-set retryable-categories}})))))))))

(defn validate-url!
  "Per Spec 014 §Request envelope `:url` is the only REQUIRED key in the
  request envelope; per Spec 009 §Error catalogue a missing/blank `:url`
  surfaces as `:rf.error/http-bad-request` (rf2-93bck).

  Public (rf2-azrcs) so the canned-stub override target
  (`http-test-support`'s route-map stub) validates
  the FINAL post-`:before` url with the same canonical error the real
  `:rf.http/managed` handler emits — a `:before` that blanks the url must
  raise `:rf.error/http-bad-request`, not receive a synthetic stubbed reply.

  Validated AFTER `run-interceptor-chain!` produces the final `:request`,
  because a `:before` interceptor may legitimately SET the url (e.g. a
  base-URL-prefix interceptor). Throwing here — at the dispatch site —
  rather than letting a nil url fall through to the transport gives a
  clear at-source error: without this guard a nil url surfaces as an
  opaque `:rf.http/transport` failure (JVM `(URI/create nil)` NPE / CLJS
  `(js/fetch nil)` vendor error), inconsistent with the rest of the
  surface, which DOES validate shape at dispatch (`validate-retry!` →
  `:rf.error/http-bad-retry-on`; `build-reply-event` →
  `:rf.error/http-bad-reply-target`). The required field had weaker
  guarding than the optional ones.

  A non-blank string passes; anything else (nil, non-string, or a
  blank/whitespace-only string) throws."
  [request]
  (let [url (:url request)]
    (when-not (and (string? url) (not (clojure.string/blank? url)))
      (throw (error/thrown-ex-info
               :rf.error/http-bad-request :rf.http/managed
               "`:request :url` is required and must be a non-blank string per Spec 014 §Request envelope; a missing / nil / blank url cannot be dispatched"
               {:extra {:url url}})))))

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
  fire on an explicit `nil` value). Both `nil` and `0` thread through
  unchanged; the downstream JVM/CLJS transport collapses them to the
  no-timeout opt-out via a `(pos? timeout-ms)` guard (NOT a bare
  truthiness check — `0` is truthy in Clojure). The three-way contract
  is thus preserved end-to-end without any reshaping here."
  [{:keys [request decode accept retry timeout-ms
           on-success on-failure request-id abort-signal]
    :or   {timeout-ms 30000}
    :as   args-map}
   frame-ctx]
  (let [origin-event (:event frame-ctx)
        ;; EP-0002 carried invariant — `:rf.http/managed` runs inside an
        ;; event cascade, so the fx context ALWAYS carries the envelope
        ;; frame as `:frame` (the HELD stamp used for reply-to-origin
        ;; addressing). A nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame        (frame/require-frame-stamp!
                       (:frame frame-ctx) :rf.http/managed
                       {:where 'rf.http/managed :event-id (first origin-event)})
        ;; rf2-wvkn — when the originating event-id is a spawned actor's
        ;; address, capture it so the in-flight registry can index by
        ;; actor-id alongside :request-id. The destroy cascade then has
        ;; a key to walk on actor-destroy. Ownership is MACHINES-OWNED
        ;; (rf2-ma0wvq): `compute-actor-id` asks the machines artefact via
        ;; the `:machines/owning-actor-id` late-bind hook (per Spec 005
        ;; §Declarative :spawn) rather than reading the spawn registry
        ;; itself; ordinary event handlers' dispatches — and every request
        ;; when the machines artefact is absent — yield nil and are not
        ;; tracked.
        actor-id     (registry/compute-actor-id frame origin-event)
        ;; rf2-bma05 — compute the effective :sensitive? flag once and
        ;; thread it through the attempt-and-retry loop. Two sources
        ;; (OR-reduced): per-call args and per-request (handler-meta
        ;; :sensitive? was removed per rf2-hjs2d). The flag rides every
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
  ;; rf2-q8vbna — `:abort-signal` and `:request-id` are NOT mutually
  ;; exclusive. Both attach cancellation sources to the one managed
  ;; request; the CLJS transport forwards an external `:abort-signal`
  ;; into the SAME framework-owned internal controller the `:request-id`
  ;; supersede/managed-abort path drives, and the once-only `:finalised?`
  ;; CAS (rf2-on7sj) guarantees exactly ONE terminal reply regardless of
  ;; which cancellation source finalises first. The removed
  ;; `validate-abort-config!` guard rejected a fully-defined, working
  ;; configuration on a false premise. Per Spec 014 §`:abort-signal`
  ;; (external).
  (let [;; EP-0002 carried invariant — the fx context carries the cascade
        ;; envelope frame as `:frame`; a nil stamp is an invariant failure
        ;; (`:rf.error/no-frame-context`), never a synthesised `:rf/default`.
        frame-id     (frame/require-frame-stamp!
                       (:frame frame-ctx) :rf.http/managed
                       {:where 'rf.http/managed
                        :event-id (first (:event frame-ctx))})
        ;; rf2-622e3 — resolve once, thread the result through
        ;; frame-ctx's :event slot so normalise-args reads it
        ;; directly instead of re-running the OR-chain.
        origin-event (encoding/resolve-origin-event frame-ctx args-map)
        frame-ctx'   (assoc frame-ctx :event origin-event)
        ;; rf2-1jcpm — resolve :sensitive? at handler entry so the
        ;; middleware-failure trace path (URL leak via
        ;; `:rf.error/http-interceptor-failed`) can redact through the
        ;; privacy composer for a request that arrived already sensitive.
        ;; rf2-rznrz — this PRE-chain reading is the floor; the EFFECTIVE
        ;; sensitivity is recomputed below from the post-`:before` request
        ;; (a `:before` may MARK the request sensitive) before the
        ;; CLJS-only-key warning and `normalise-args` run. The chain
        ;; itself recomputes per-interceptor for its own failure-path
        ;; trace (see `run-chain*` / `run-interceptor-chain!`).
        sensitive?   (privacy/request-sensitive? args-map origin-event)
        ctx0         {:request    (:request args-map)
                      :args       args-map
                      :frame      frame-id
                      :event      origin-event
                      :sensitive? sensitive?}
        ctx          (middleware/run-interceptor-chain! frame-id ctx0)
        ;; rf2-93bck — validate the required `:url` AFTER the `:before`
        ;; chain produces the final `:request` (a `:before` may legitimately
        ;; SET the url). Throws `:rf.error/http-bad-request` on a missing /
        ;; nil / blank url so the misuse surfaces here, at the dispatch
        ;; site, rather than as an opaque `:rf.http/transport` failure
        ;; downstream. Mirrors `validate-retry!`'s dispatch-time guard.
        _            (validate-url! (:request ctx))
        args-map'    (assoc args-map :request (:request ctx))
        ;; rf2-rznrz — recompute the EFFECTIVE :sensitive? from the
        ;; POST-`:before` request, then run the CLJS-only-key check
        ;; against that same post-chain request. Both were previously
        ;; evaluated on the ORIGINAL args BEFORE the chain ran, so:
        ;;   - a `:before` that MARKED the request sensitive (set
        ;;     `[:request :sensitive?] true`) did not raise the flag the
        ;;     CLJS-only warning / `normalise-args` saw — a downstream
        ;;     trace could leak a now-sensitive URL's query values; and
        ;;   - a `:before` that ADDED a JVM-degraded CLJS-only key
        ;;     (`:credentials` / `:mode` / `:cache` / …) into the request
        ;;     escaped the degraded-key warning entirely (the request
        ;;     proceeded on JVM silently dropping the key).
        ;; Recomputing here closes both: the warning fires on the request
        ;; the transport will actually issue, redacted by the effective
        ;; sensitivity.
        sensitive?'  (privacy/request-sensitive? args-map' origin-event)
        ;; rf2-hp772l — `check-cljs-only-keys!` is JVM per-row degradation
        ;; tracing (a no-op on CLJS), owned by the JVM platform adapter.
        ;; rf2-ppkh3v — thread the frame so its warning-path URL redaction
        ;; consults the frame's frame-local HTTP carriers (EP-0015 §3).
        _            (transport-jvm/check-cljs-only-keys! args-map' sensitive?' frame-id)
        ;; rf2-uheqq — carry the post-:before middleware-ctx forward so
        ;; the response-side `:after` chain sees the EXACT same ctx its
        ;; sibling `:before`s ended with. Per Spec 014 §Middleware: a
        ;; `:before` that records a wall-clock mark / parses request
        ;; headers / stamps a correlation-id can read its own work back
        ;; in the `:after`, which is what makes request-correlated
        ;; response handling (response-time telemetry, header-driven
        ;; auth refresh, …) expressible in a single interceptor.
        normalised0  (assoc (normalise-args args-map' frame-ctx')
                            :middleware-ctx ctx)
        request-id   (:request-id normalised0)
        ;; rf2-azcmd3 — allocate this request's monotonic per-request-id
        ;; ISSUANCE number BEFORE superseding the prior in-flight request, so
        ;; the new attempt's `:work/id` `[:rf.work/http logical-id issuance
        ;; attempt]` is `=`-distinct from the superseded one's (both reset
        ;; their retry `:attempt` to 1; the issuance discriminates them). One
        ;; attempt has one work id (EP-0007 / Managed-Effects §Work-id
        ;; correlation §184). An anonymous request (nil request-id) never
        ;; supersedes and stays at issuance 1.
        issuance     (registry/next-issuance! request-id)
        normalised   (assoc normalised0 :issuance issuance)]
    ;; rf2-azcmd3 — supersession emits the SUPERSEDED attempt's canonical
    ;; `:status :stale` / `:work/status :suppressed` reply-envelope trace
    ;; (Managed-Effects §Stale suppression) carrying carried/current work-id
    ;; correlation, with NO app dispatch. `supersede!` returns the old handle
    ;; (carrying its identity facts); we hand it plus the NEW attempt's
    ;; work-id (the live `:work/id` now taking over the request-id) to the
    ;; stale-trace emitter. The old handle's own abort path still fires
    ;; (`:reason :request-id-superseded`) — that suppresses its app reply; this
    ;; ADDS the canonical stale reply-envelope row the old `:rf.http/aborted`
    ;; trace alone did not record.
    (when request-id
      (when-let [superseded (registry/supersede! request-id)]
        (transport/emit-superseded-stale-trace!
          superseded
          (http-reply/work-id {:request-id   request-id
                               :origin-event origin-event
                               :issuance     issuance
                               :attempt      1}))))
    (transport/run-attempt! normalised)
    nil))

(defn managed-abort-handler
  "Public `:rf.http/managed-abort` fx. Args is the request-id (any value).

  Per rf2-plngk the in-flight cleanup is owned by `finalise-failure!`
  (the abort-fn closure calls into it). The earlier shape pre-cleared
  the registry here AND inside `finalise-failure!`, doubling the
  `swap!` traffic per abort. Now the single source of truth lives at
  the failure-finalise site; this handler only fires the abort-fn.

  rf2-rak684 — routes through the shared `registry/abort-in-flight!`
  abort-by-request-id seam (the SAME seam the resources out-of-cascade
  teardown reaches through the `:http/abort-in-flight!` late-bind hook),
  so the fx and the teardown hook fire identical abort semantics."
  [_frame-ctx request-id]
  (registry/abort-in-flight! request-id :user)
  nil)
