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
  (:require [clojure.string]
            [re-frame.http-encoding  :as encoding]
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
            (throw (ex-info ":rf.error/http-bad-retry-on"
                            {:rf.error/id   :rf.error/http-bad-retry-on
                             :where         :rf.http/managed
                             :recovery      :no-recovery
                             :bad-shape     on
                             :bad-type      (type on)
                             :retryable-set retryable-categories
                             :reason        "`:retry :on` must be a SET of retryable-category keywords per Spec 014 §Closed-set `:retry :on` validation; a non-set value (keyword, vector, list, string, …) is rejected because the transport membership gate `(contains? on-set kind)` tests index membership over a sequential collection and would silently disable retry. Use `#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}`, `#{}` for no-retry, or omit `:on`"})))
          ;; MEMBERSHIP: every member must be a retryable category.
          (let [bad-members (into #{} (remove retryable-categories) on)]
            (when (seq bad-members)
              (throw (ex-info ":rf.error/http-bad-retry-on"
                              {:rf.error/id   :rf.error/http-bad-retry-on
                               :where         :rf.http/managed
                               :recovery      :no-recovery
                               :bad-members   bad-members
                               :retryable-set retryable-categories
                               :reason        "`:retry :on` must be drawn exclusively from the closed retryable set #{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}; `:rf.http/aborted`, `:rf.http/decode-failure`, and `:rf.http/accept-failure` are non-retryable by construction"})))))))))

(defn- validate-url!
  "Per Spec 014 §Request envelope `:url` is the only REQUIRED key in the
  request envelope; per Spec 009 §Error catalogue a missing/blank `:url`
  surfaces as `:rf.error/http-bad-request` (rf2-93bck).

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
      (throw (ex-info ":rf.error/http-bad-request"
                      {:rf.error/id :rf.error/http-bad-request
                       :where       :rf.http/managed
                       :recovery    :no-recovery
                       :url         url
                       :reason      "`:request :url` is required and must be a non-blank string per Spec 014 §Request envelope; a missing / nil / blank url cannot be dispatched"})))))

(defn- validate-abort-config!
  "Per Spec 014 §`:abort-signal` (external): `:abort-signal` and
  `:request-id` are mutually exclusive — \"pick one\" (rf2-culoe).

  A request supplying BOTH gets two independent abort mechanisms wired
  against the one in-flight request — the caller's external Fetch signal
  forwarded into the internal controller AND the `:request-id`
  supersede/managed-abort path — which can race, with behaviour under
  simultaneous abort undefined-by-spec. Per the pre-alpha reject-misuse
  posture this is a dispatch-time configuration error, not a tolerated
  combination: throw `:rf.error/http-bad-abort-config` at the fx-call
  site (before `run-attempt!`), matching the at-source guarding the rest
  of the surface already carries (`validate-retry!` →
  `:rf.error/http-bad-retry-on`, `validate-url!` →
  `:rf.error/http-bad-request`, `build-reply-event` →
  `:rf.error/http-bad-reply-target`). This closes the one stated abort
  constraint that previously had no guard.

  Presence is what's checked, not truthiness: an explicit `:request-id
  nil` opts out of internal abort (the registry never indexes a nil id),
  so only a present, non-nil `:request-id` alongside a present, non-nil
  `:abort-signal` is the conflicting shape. `contains?` + `some?` express
  that precisely."
  [{:keys [request-id abort-signal] :as args-map}]
  (when (and (contains? args-map :request-id)   (some? request-id)
             (contains? args-map :abort-signal) (some? abort-signal))
    (throw (ex-info ":rf.error/http-bad-abort-config"
                    {:rf.error/id :rf.error/http-bad-abort-config
                     :where       :rf.http/managed
                     :recovery    :no-recovery
                     :request-id  request-id
                     :reason      "`:abort-signal` and `:request-id` are mutually exclusive per Spec 014 §`:abort-signal` (external) — pick one. Supplying both wires two independent abort mechanisms (the external Fetch signal forwarded into the internal controller AND the `:request-id` supersede/managed-abort path) against a single request; their simultaneous-abort behaviour is undefined-by-spec"}))))

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
        frame        (or (:frame frame-ctx) :rf/default)
        ;; rf2-wvkn — when the originating event-id is a spawned actor's
        ;; address, capture it so the in-flight registry can index by
        ;; actor-id alongside :request-id. The destroy cascade then has
        ;; a key to walk on actor-destroy. Detection is structural —
        ;; we look up the id in the frame's [:rf/runtime :machines :spawned ...] runtime
        ;; registry (per Spec 005 §Declarative :spawn); ordinary event
        ;; handlers' dispatches yield nil and are not tracked.
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
  ;; rf2-culoe — `:abort-signal` / `:request-id` mutual-exclusivity.
  ;; Like `validate-retry!`, fires at the dispatch site (before the
  ;; middleware chain and `run-attempt!`) so the misuse is rejected at
  ;; source rather than wiring two racing abort mechanisms against one
  ;; request. Per Spec 014 §`:abort-signal` (external).
  (validate-abort-config! args-map)
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
        ;; rf2-93bck — validate the required `:url` AFTER the `:before`
        ;; chain produces the final `:request` (a `:before` may legitimately
        ;; SET the url). Throws `:rf.error/http-bad-request` on a missing /
        ;; nil / blank url so the misuse surfaces here, at the dispatch
        ;; site, rather than as an opaque `:rf.http/transport` failure
        ;; downstream. Mirrors `validate-retry!`'s dispatch-time guard.
        _            (validate-url! (:request ctx))
        args-map'    (assoc args-map :request (:request ctx))
        ;; rf2-uheqq — carry the post-:before middleware-ctx forward so
        ;; the response-side `:after` chain sees the EXACT same ctx its
        ;; sibling `:before`s ended with. Per Spec 014 §Middleware: a
        ;; `:before` that records a wall-clock mark / parses request
        ;; headers / stamps a correlation-id can read its own work back
        ;; in the `:after`, which is what makes request-correlated
        ;; response handling (response-time telemetry, header-driven
        ;; auth refresh, …) expressible in a single interceptor.
        normalised   (assoc (normalise-args args-map' frame-ctx')
                            :middleware-ctx ctx)
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
