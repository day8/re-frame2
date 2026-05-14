(ns re-frame.http-privacy
  "Spec 014 §Privacy — sensitive-data honouring for `:rf.http/managed` (rf2-bma05).

  HTTP is the canonical privacy surface: passwords ride request bodies,
  auth tokens ride request headers, user PII rides response bodies. The
  trace events emitted by the managed-HTTP fx must not surface those
  values verbatim — either by stamping the top-level `:sensitive?` flag
  on the trace event (so consumers filter per Spec 009 §Privacy) or by
  redacting known-sensitive slots before the value reaches the trace
  surface.

  This namespace is the orchestrating seam — the sensitivity resolver
  (`request-sensitive?` / `handler-sensitive?`) and the trace-event
  composers (`prepare-emit-tags` / `prepare-emit-failure` and the
  redact-* / stamp-sensitive primitives they compose). The two leaf
  surfaces it draws on live in sibling namespaces:

  - `re-frame.http-privacy-headers` — header denylist + `redact-headers`.
  - `re-frame.http-url` — query-param denylist + `redact-url` /
    `redact-url-query-string` / `query-denylist-hit?` (and future home
    of `url-encode` / `params->query` / `merge-params` per rf2-5ijhk).

  Three cooperating mechanisms, mirroring Spec 009's `:sensitive?` /
  `with-redacted` split:

  1. **Header denylist** (`re-frame.http-privacy-headers`) — a canonical
     set of always-sensitive header names. Redacted in trace events
     regardless of the handler / request `:sensitive?` flag, because
     the headers' names themselves declare them sensitive.

  2. **Query-param denylist** (`re-frame.http-url`, rf2-2p8wr) — a
     canonical set of always-sensitive query-string parameter names
     (e.g. `api_key`, `access_token`, `auth`). URLs carrying these
     params are redacted **inline** in every `:rf.http/*` trace event
     that carries a `:url` slot, regardless of the handler / request
     `:sensitive?` flag. Same rationale as the header denylist: the
     param name itself is the signal.

  3. **Per-call / per-handler `:sensitive?`** — the originating event
     handler's `:sensitive?` registration metadata propagates to every
     `:rf.http/*` trace event emitted within the cascade. A per-call
     `:sensitive?` arg on the `:rf.http/managed` args map opts in for
     a specific request when the handler itself is not sensitive (e.g.
     a generic POST handler that is sensitive only when posting to
     `/auth/login`). When the request is sensitive, **all** query
     params are redacted (broader rule than the denylist).

  The runtime stamps `:sensitive? true` on the emitted trace event's
  `:tags` (and at the top level when the core runtime stamp lands per
  rf2 G1) so consumers consult one keyword to decide ship / drop /
  redact. When ANY query param is redacted by the denylist alone (no
  per-call sensitivity), the trace event still stamps `:sensitive? true`
  — the presence of a denylisted query param is itself a signal that
  the request carries an auth secret.

  ## Production elision

  The redact / stamp helpers all gate on `interop/debug-enabled?` at
  their call sites (the same gate as `trace/emit!`). In production
  builds the trace surface elides entirely; the privacy machinery is
  moot. The denylist atoms themselves ship in production (they're
  app-readable state — `declare-sensitive-*!` writes through), but no
  walker runs against them without the trace surface."
  (:require [re-frame.http-privacy-headers :as headers]
            [re-frame.http-url :as url]
            [re-frame.registrar :as registrar]))

(def redacted-sentinel
  "The framework-reserved redaction sentinel per Spec 009 §Privacy. Sits
  in the `:rf/` reserved-keyword namespace so apps cannot legitimately
  produce it as a payload value. Consumers wanting \"was this redacted?\"
  check `(= :rf/redacted v)`."
  :rf/redacted)

;; ---- trace-event redaction helpers ----------------------------------------

(defn redact-request-tags
  "Given a tags map about to ride a `:rf.http/*` trace event, redact
  the request-side payload when `sensitive?` is true. Always redacts
  denylisted headers regardless of `sensitive?` — the header denylist
  is unconditional. URL query-string values whose param name is in the
  query-param denylist (rf2-2p8wr) are always redacted regardless of
  `sensitive?` — denylisted param names are themselves the signal.

  Idempotent and total: missing slots are left alone."
  [tags sensitive?]
  (cond-> tags
    ;; Always-on header redaction (denylist).
    (map? (:headers tags))
    (update :headers headers/redact-headers)

    ;; URL query-string redaction — always-on for denylisted params
    ;; (rf2-2p8wr); when sensitive? true ALL params are scrubbed.
    (string? (:url tags))
    (update :url url/redact-url sensitive?)

    ;; Sensitive-request redaction — body becomes the sentinel.
    (and sensitive? (contains? tags :body))
    (assoc :body redacted-sentinel)

    ;; Sensitive-request redaction — params (URL query string) too.
    (and sensitive? (contains? tags :params))
    (assoc :params redacted-sentinel)))

(defn redact-failure
  "Given a failure map (per Spec 014 §Failure categories) about to ride
  a `:rf.http/*` trace event, redact response-side payload slots when
  `sensitive?` is true. Always redacts headers via the denylist; always
  redacts URL query-string values whose param name is in the query-
  param denylist (rf2-2p8wr) — denylisted param names are the signal."
  [failure sensitive?]
  (when failure
    (cond-> failure
      (map? (:headers failure))
      (update :headers headers/redact-headers)

      ;; URL query-string redaction — always-on for denylisted params
      ;; (rf2-2p8wr); when sensitive? true ALL params are scrubbed.
      (string? (:url failure))
      (update :url url/redact-url sensitive?)

      (and sensitive? (contains? failure :body))
      (assoc :body redacted-sentinel)

      (and sensitive? (contains? failure :body-text))
      (assoc :body-text redacted-sentinel)

      (and sensitive? (contains? failure :decoded))
      (assoc :decoded redacted-sentinel)

      ;; Accept-failure carries the user's domain failure-map at :detail
      ;; — opaque to us; redact wholesale when sensitive.
      (and sensitive? (contains? failure :detail))
      (assoc :detail redacted-sentinel))))

(defn stamp-sensitive
  "Stamp the `:sensitive?` flag onto a tags map when `sensitive?` is true.

  Per Spec 009 §Privacy the canonical contract is that `:sensitive?`
  rides at the **top level** of the trace event. Both `re-frame.trace/emit!`
  and `re-frame.trace/emit-error!` (per rf2-isdwf) consult a
  caller-supplied `:sensitive?` slot in tags and hoist it to top-level,
  dissoc'ing it from tags — so we stamp into tags here and let core do
  the hoist. Consumers always read `(:sensitive? ev)` at the top level,
  per the spec contract.

  When `sensitive?` is false the slot is OMITTED (not stamped to false)
  per Spec 009 line 1176: \"Consumers treat absent as false.\""
  [tags sensitive?]
  (cond-> tags
    sensitive? (assoc :sensitive? true)))

;; ---- per-request / per-handler :sensitive? --------------------------------

(defn handler-sensitive?
  "Read the originating event handler's `:sensitive?` registration
  metadata via `re-frame.registrar/handler-meta`.

  Returns `true` iff the handler is declared sensitive. Tolerant of
  every failure mode (id not found, metadata absent) — returns `false`
  so the privacy machinery degrades gracefully when the originating
  handler can't be resolved."
  [origin-event]
  (boolean
    (try
      (when (and origin-event (vector? origin-event) (seq origin-event))
        (let [event-id (first origin-event)
              meta     (registrar/handler-meta :event event-id)]
          (true? (:sensitive? meta))))
      (catch #?(:clj Throwable :cljs :default) _ false))))

(defn request-sensitive?
  "Compute the effective `:sensitive?` reading for a `:rf.http/managed`
  request. Three sources, OR-reduced:

  1. Per-call `:sensitive?` on the args map (`true` opts in for this
     specific request, even when the handler is not declared sensitive).
  2. Per-call `:sensitive?` under `:request` (sugar — callers reaching
     for `(rf.http/post ... {:request {:sensitive? true}})` get the
     same effect as the top-level form).
  3. Handler-level `:sensitive?` registration metadata for the
     originating event id.

  Returns `true` if any source declares sensitivity; `false` otherwise."
  [args-map origin-event]
  (or (true? (:sensitive? args-map))
      (true? (get-in args-map [:request :sensitive?]))
      (handler-sensitive? origin-event)))

;; ---- trace-event composers ------------------------------------------------

(defn prepare-emit-tags
  "Compose `redact-request-tags` + `stamp-sensitive` for a request-side
  trace event (`:rf.http/retry-attempt`, `:rf.http/aborted-on-actor-destroy`,
  `:rf.warning/decode-defaulted`, `:rf.warning/cljs-only-key-ignored-on-jvm`).
  Returns a tags map ready for `trace/emit!`.

  Two distinct decisions per rf2-2p8wr:

  - **What to redact in `:url`** uses the `sensitive?` arg (handler /
    per-call). When `sensitive?` true, ALL query-string param values
    are scrubbed; when false, only denylisted param values are scrubbed.
    The denylist-only redaction does NOT promote the URL scrub to ALL
    params — denylisted names are signals, not licenses to scrub
    unrelated params.

  - **Whether to stamp `:sensitive?`** OR-combines `sensitive?` with a
    denylist-hit check on `:url` / `:failure :url`. A denylisted query-
    param name alone is enough to stamp the event — the name is the
    signal that the request carries a secret."
  [tags sensitive?]
  (let [denylist-hit? (or (url/query-denylist-hit? (:url tags))
                          (url/query-denylist-hit? (get-in tags [:failure :url])))
        stamp?        (or (true? sensitive?) denylist-hit?)]
    (-> tags
        (redact-request-tags (true? sensitive?))
        (cond-> (contains? tags :failure)
                (update :failure redact-failure (true? sensitive?)))
        (stamp-sensitive stamp?))))

(defn prepare-emit-failure
  "Compose `redact-failure` + `stamp-sensitive` for an error-side trace
  event (`emit-error!` on a `:rf.http/*` failure kind). The whole
  failure map is the tags map for `emit-error!`.

  Two distinct decisions per rf2-2p8wr — same split as `prepare-emit-
  tags`: URL redaction uses the explicit `sensitive?` arg; `:sensitive?`
  stamping OR-combines that arg with a query-param denylist hit on the
  failure's `:url`."
  [failure sensitive?]
  (let [denylist-hit? (url/query-denylist-hit? (:url failure))
        stamp?        (or (true? sensitive?) denylist-hit?)]
    (-> (redact-failure failure (true? sensitive?))
        (stamp-sensitive stamp?))))
