(ns re-frame.http-privacy
  "Spec 014 §Privacy — sensitive-data honouring for `:rf.http/managed` (rf2-bma05).

  HTTP is the canonical privacy surface: passwords ride request bodies,
  auth tokens ride request headers, user PII rides response bodies. The
  trace events emitted by the managed-HTTP fx must not surface those
  values verbatim — either by stamping the top-level `:sensitive?` flag
  on the trace event (so consumers filter per Spec 009 §Privacy) or by
  redacting known-sensitive slots before the value reaches the trace
  surface.

  Two cooperating mechanisms, mirroring Spec 009's `:sensitive?` /
  `with-redacted` split:

  1. **Header denylist** (this namespace) — a canonical set of always-
     sensitive header names. Redacted in trace events regardless of
     the handler / request `:sensitive?` flag, because the headers'
     names themselves declare them sensitive (an `Authorization` header
     leaking would be a leak even if the surrounding handler was not
     declared sensitive).

  2. **Per-call / per-handler `:sensitive?`** — the originating event
     handler's `:sensitive?` registration metadata propagates to every
     `:rf.http/*` trace event emitted within the cascade. A per-call
     `:sensitive?` arg on the `:rf.http/managed` args map opts in for
     a specific request when the handler itself is not sensitive (e.g.
     a generic POST handler that is sensitive only when posting to
     `/auth/login`).

  The runtime stamps `:sensitive? true` on the emitted trace event's
  `:tags` (and at the top level when the core runtime stamp lands per
  rf2 G1) so consumers consult one keyword to decide ship / drop /
  redact.

  ## Defaults and extensibility

  `default-header-denylist` covers the canonical surface (Authorization,
  Cookie, Set-Cookie, X-API-Key, X-Auth-Token, Proxy-Authorization,
  Authentication, WWW-Authenticate, X-CSRF-Token, X-XSRF-Token,
  X-Session-Token, Proxy-Authenticate). Apps extend via
  `(declare-sensitive-header! \"X-My-Auth\")` — names are
  case-insensitive; the registry stores lower-cased.

  ## Production elision

  The redact / stamp helpers all gate on `interop/debug-enabled?` at
  their call sites (the same gate as `trace/emit!`). In production
  builds the trace surface elides entirely; the privacy machinery is
  moot. The header denylist atom itself ships in production (it's
  app-readable state — `declare-sensitive-header!` writes through),
  but no walker runs against it without the trace surface."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [re-frame.registrar :as registrar]))

;; ---- header denylist ------------------------------------------------------

(def default-header-denylist
  "Canonical always-sensitive HTTP header names, lower-cased. These are
  redacted in `:rf.http/*` trace events regardless of the handler's
  `:sensitive?` flag — the header names themselves are the signal.

  Drawn from the OWASP Authentication Cheatsheet plus common bearer-
  scheme headers used by SaaS APIs. Apps extend at boot via
  `declare-sensitive-header!` for app-specific tokens (e.g.
  `X-Honeycomb-Team`, `X-Stripe-Signature`)."
  #{"authorization"
    "proxy-authorization"
    "cookie"
    "set-cookie"
    "x-api-key"
    "x-auth-token"
    "x-session-token"
    "x-csrf-token"
    "x-xsrf-token"
    "authentication"
    "www-authenticate"
    "proxy-authenticate"})

(defonce
  ^{:doc "App-extended denylist. Augments `default-header-denylist`.
  Names stored lower-cased. Cleared by `clear-sensitive-headers!` for
  test ergonomics."}
  extra-headers
  (atom #{}))

(defn declare-sensitive-header!
  "Declare a header name as sensitive. Stored lower-cased; matching is
  case-insensitive when the redactor consults the merged set. Returns
  the new merged set (defaults ∪ extras)."
  [header-name]
  (when (string? header-name)
    (swap! extra-headers conj (str/lower-case header-name)))
  (set/union default-header-denylist @extra-headers))

(defn clear-sensitive-headers!
  "Reset the app-extended header denylist to empty (default denylist
  remains). Test-only — invoked by reset fixtures alongside
  `clear-all-in-flight!` and `clear-all-http-interceptors!`."
  []
  (reset! extra-headers #{})
  nil)

(defn sensitive-header?
  "Predicate: is `header-name` in the merged denylist? Case-insensitive."
  [header-name]
  (boolean
    (when (string? header-name)
      (contains? (set/union default-header-denylist @extra-headers)
                 (str/lower-case header-name)))))

;; ---- redaction sentinel ---------------------------------------------------

(def redacted-sentinel
  "The framework-reserved redaction sentinel per Spec 009 §Privacy. Sits
  in the `:rf/` reserved-keyword namespace so apps cannot legitimately
  produce it as a payload value. Consumers wanting \"was this redacted?\"
  check `(= :rf/redacted v)`."
  :rf/redacted)

(defn redact-headers
  "Walk `headers-map` (string→string or string→vector-of-strings); replace
  values whose key matches the merged header denylist with
  `:rf/redacted`. Returns a new map; nil/empty input returns the input
  unchanged. Case-insensitive on header names."
  [headers-map]
  (cond
    (nil? headers-map) headers-map
    (not (map? headers-map)) headers-map
    :else
    (reduce-kv
      (fn [acc k v]
        (assoc acc k
               (if (sensitive-header? (if (keyword? k) (name k) (str k)))
                 redacted-sentinel
                 v)))
      {}
      headers-map)))

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

;; ---- trace-event redaction helpers ----------------------------------------

(defn redact-request-tags
  "Given a tags map about to ride a `:rf.http/*` trace event, redact
  the request-side payload when `sensitive?` is true. Always redacts
  denylisted headers regardless of `sensitive?` — the header denylist
  is unconditional.

  Idempotent and total: missing slots are left alone."
  [tags sensitive?]
  (cond-> tags
    ;; Always-on header redaction (denylist).
    (map? (:headers tags))
    (update :headers redact-headers)

    ;; Sensitive-request redaction — body becomes the sentinel.
    (and sensitive? (contains? tags :body))
    (assoc :body redacted-sentinel)

    ;; Sensitive-request redaction — params (URL query string) too.
    (and sensitive? (contains? tags :params))
    (assoc :params redacted-sentinel)))

(defn redact-failure
  "Given a failure map (per Spec 014 §Failure categories) about to ride
  a `:rf.http/*` trace event, redact response-side payload slots when
  `sensitive?` is true. Always redacts headers via the denylist."
  [failure sensitive?]
  (when failure
    (cond-> failure
      (map? (:headers failure))
      (update :headers redact-headers)

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

(defn prepare-emit-tags
  "Compose `redact-request-tags` + `stamp-sensitive` for a request-side
  trace event (`:rf.http/retry-attempt`, `:rf.http/aborted-on-actor-destroy`,
  `:rf.warning/decode-defaulted`, `:rf.warning/cljs-only-key-ignored-on-jvm`).
  Returns a tags map ready for `trace/emit!`."
  [tags sensitive?]
  (-> tags
      (redact-request-tags sensitive?)
      (cond-> (contains? tags :failure)
              (update :failure redact-failure sensitive?))
      (stamp-sensitive sensitive?)))

(defn prepare-emit-failure
  "Compose `redact-failure` + `stamp-sensitive` for an error-side trace
  event (`emit-error!` on a `:rf.http/*` failure kind). The whole
  failure map is the tags map for `emit-error!`."
  [failure sensitive?]
  (-> (redact-failure failure sensitive?)
      (stamp-sensitive sensitive?)))
