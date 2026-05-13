(ns re-frame.http-privacy
  "Spec 014 §Privacy — sensitive-data honouring for `:rf.http/managed` (rf2-bma05).

  HTTP is the canonical privacy surface: passwords ride request bodies,
  auth tokens ride request headers, user PII rides response bodies. The
  trace events emitted by the managed-HTTP fx must not surface those
  values verbatim — either by stamping the top-level `:sensitive?` flag
  on the trace event (so consumers filter per Spec 009 §Privacy) or by
  redacting known-sensitive slots before the value reaches the trace
  surface.

  Three cooperating mechanisms, mirroring Spec 009's `:sensitive?` /
  `with-redacted` split:

  1. **Header denylist** (this namespace) — a canonical set of always-
     sensitive header names. Redacted in trace events regardless of
     the handler / request `:sensitive?` flag, because the headers'
     names themselves declare them sensitive (an `Authorization` header
     leaking would be a leak even if the surrounding handler was not
     declared sensitive).

  2. **Query-param denylist** (this namespace, rf2-2p8wr) — a canonical
     set of always-sensitive query-string parameter names (e.g.
     `api_key`, `access_token`, `auth`). URLs carrying these params
     are redacted **inline** in every `:rf.http/*` trace event that
     carries a `:url` slot, regardless of the handler / request
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

  ## Defaults and extensibility

  `default-header-denylist` covers the canonical surface (Authorization,
  Cookie, Set-Cookie, X-API-Key, X-Auth-Token, Proxy-Authorization,
  Authentication, WWW-Authenticate, X-CSRF-Token, X-XSRF-Token,
  X-Session-Token, Proxy-Authenticate). Apps extend via
  `(declare-sensitive-header! \"X-My-Auth\")` — names are
  case-insensitive; the registry stores lower-cased.

  `default-query-param-denylist` covers the canonical query-string-auth
  surface (api_key, apikey, access_token, auth, token, key, secret,
  password, session, signature, sig). Apps extend via
  `(declare-sensitive-query-param! \"my_token\")` — names are
  case-insensitive; the registry stores lower-cased.

  ## Production elision

  The redact / stamp helpers all gate on `interop/debug-enabled?` at
  their call sites (the same gate as `trace/emit!`). In production
  builds the trace surface elides entirely; the privacy machinery is
  moot. The denylist atoms themselves ship in production (they're
  app-readable state — `declare-sensitive-*!` writes through), but no
  walker runs against them without the trace surface."
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

;; ---- query-param denylist (rf2-2p8wr) -------------------------------------

(def default-query-param-denylist
  "Canonical always-sensitive HTTP query-string parameter names, lower-
  cased. URLs carrying these params have the *values* redacted in
  `:rf.http/*` trace events regardless of the handler's `:sensitive?`
  flag — the param names themselves are the signal (mirrors the header
  denylist contract).

  Drawn from common API-key / bearer-token idioms in older REST APIs,
  webhook receivers, and signed-URL schemes. Apps extend at boot via
  `declare-sensitive-query-param!` for app-specific param names (e.g.
  `\"shop_token\"`, `\"hmac\"`)."
  #{"api_key"
    "apikey"
    "api-key"
    "access_token"
    "accesstoken"
    "auth"
    "auth_token"
    "authtoken"
    "key"
    "token"
    "secret"
    "password"
    "passwd"
    "session"
    "session_id"
    "sessionid"
    "signature"
    "sig"
    "hmac"})

(defonce
  ^{:doc "App-extended query-param denylist. Augments
  `default-query-param-denylist`. Names stored lower-cased. Cleared by
  `clear-sensitive-query-params!` for test ergonomics."}
  extra-query-params
  (atom #{}))

(defn declare-sensitive-query-param!
  "Declare a query-string parameter name as sensitive. Stored lower-
  cased; matching is case-insensitive when the redactor consults the
  merged set. Returns the new merged set (defaults ∪ extras)."
  [param-name]
  (when (string? param-name)
    (swap! extra-query-params conj (str/lower-case param-name)))
  (set/union default-query-param-denylist @extra-query-params))

(defn clear-sensitive-query-params!
  "Reset the app-extended query-param denylist to empty (default
  denylist remains). Test-only — invoked by reset fixtures alongside
  `clear-sensitive-headers!`."
  []
  (reset! extra-query-params #{})
  nil)

(defn sensitive-query-param?
  "Predicate: is `param-name` in the merged query-param denylist?
  Case-insensitive."
  [param-name]
  (boolean
    (when (string? param-name)
      (contains? (set/union default-query-param-denylist @extra-query-params)
                 (str/lower-case param-name)))))

;; ---- redaction sentinel ---------------------------------------------------

(def redacted-sentinel
  "The framework-reserved redaction sentinel per Spec 009 §Privacy. Sits
  in the `:rf/` reserved-keyword namespace so apps cannot legitimately
  produce it as a payload value. Consumers wanting \"was this redacted?\"
  check `(= :rf/redacted v)`."
  :rf/redacted)

(def ^:private redacted-url-token
  "Inline string form of the `:rf/redacted` sentinel suitable for splicing
  into a URL string. Trace consumers parsing the URL still see a structural
  token they can detect (`:rf%2Fredacted` once URL-encoded into the wire
  string we serialise). The unencoded form is `:rf/redacted` — identical
  text to the keyword's `pr-str` — chosen so a human inspecting a trace
  event reads the same sentinel they see in any other redacted slot."
  ":rf/redacted")

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

;; ---- URL query-string redaction (rf2-2p8wr) -------------------------------

(defn- split-url-on-query
  "Split `url-str` into `[base query-string]`. `query-string` is nil
  when the URL has no `?`. Preserves URL fragments (`#…`) on the
  query-string side so the redactor can put them back verbatim."
  [url-str]
  (let [qidx (str/index-of url-str "?")]
    (if (nil? qidx)
      [url-str nil]
      [(subs url-str 0 qidx)
       (subs url-str (inc qidx))])))

(defn- split-query-on-fragment
  "Split `query-and-fragment` on the first `#` so the fragment can be
  preserved verbatim past the redaction step. Returns `[query fragment]`
  where `fragment` includes the leading `#` or is nil."
  [query-and-fragment]
  (if (nil? query-and-fragment)
    [nil nil]
    (let [fidx (str/index-of query-and-fragment "#")]
      (if (nil? fidx)
        [query-and-fragment nil]
        [(subs query-and-fragment 0 fidx)
         (subs query-and-fragment fidx)]))))

(defn- redact-query-param
  "Given a single `name=value` pair (string), return the redacted form:
  `name=:rf/redacted` if the param is denylisted OR `force-all?` is
  true; the pair unchanged otherwise. Tolerates malformed pairs (no
  `=`, empty value)."
  [pair force-all?]
  (let [eq-idx (str/index-of pair "=")
        [pname _pvalue] (if eq-idx
                          [(subs pair 0 eq-idx) (subs pair (inc eq-idx))]
                          [pair nil])]
    (if (or force-all? (sensitive-query-param? pname))
      (str pname "=" redacted-url-token)
      pair)))

(defn redact-url-query-string
  "Redact denylisted query-string parameter values in `url-str`.

  When `sensitive?` is true, redacts **every** param value (the broader
  rule per Spec 014 §Privacy — a sensitive request leaks nothing).
  When `sensitive?` is false, redacts only values whose param name is
  in the merged query-param denylist.

  The redaction shape preserves param name + position; only the value
  is replaced with the framework-reserved `:rf/redacted` text token
  (e.g. `?api_key=SECRET&page=2` → `?api_key=:rf/redacted&page=2`).
  Fragment portion (`#…`) preserved verbatim.

  Returns `[redacted-url any-redacted?]` where `any-redacted?` is true
  iff at least one param value was redacted (the caller uses this to
  decide whether to stamp `:sensitive?` per the denylist-is-signal
  rule). Nil / non-string / no-query inputs return `[url-str false]`."
  [url-str sensitive?]
  (cond
    (not (string? url-str))
    [url-str false]

    :else
    (let [[base query-and-fragment] (split-url-on-query url-str)
          [query fragment]          (split-query-on-fragment query-and-fragment)]
      (if (str/blank? query)
        [url-str false]
        (let [pairs    (str/split query #"&")
              redacted (mapv (fn [p] (redact-query-param p (true? sensitive?)))
                             pairs)
              changed? (boolean (some true? (map not= pairs redacted)))
              rebuilt  (str base "?" (str/join "&" redacted) (or fragment ""))]
          [rebuilt changed?])))))

(defn redact-url
  "Convenience wrapper around `redact-url-query-string` that returns only
  the redacted URL string. Use when the caller does not need the
  any-redacted? flag (e.g. inside a generic tag-walker)."
  [url-str sensitive?]
  (first (redact-url-query-string url-str sensitive?)))

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
  is unconditional. URL query-string values whose param name is in the
  query-param denylist (rf2-2p8wr) are always redacted regardless of
  `sensitive?` — denylisted param names are themselves the signal.

  Idempotent and total: missing slots are left alone."
  [tags sensitive?]
  (cond-> tags
    ;; Always-on header redaction (denylist).
    (map? (:headers tags))
    (update :headers redact-headers)

    ;; URL query-string redaction — always-on for denylisted params
    ;; (rf2-2p8wr); when sensitive? true ALL params are scrubbed.
    (string? (:url tags))
    (update :url redact-url sensitive?)

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
      (update :headers redact-headers)

      ;; URL query-string redaction — always-on for denylisted params
      ;; (rf2-2p8wr); when sensitive? true ALL params are scrubbed.
      (string? (:url failure))
      (update :url redact-url sensitive?)

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

(defn- query-denylist-hit?
  "Return true iff `url-str` carries a query-string param whose name is
  on the merged query-param denylist. The denylist-alone hit is itself
  a signal that the request carries an auth secret (rf2-2p8wr) — the
  prepare-emit-* composers use this to stamp `:sensitive?` even when
  the originating handler was not declared sensitive."
  [url-str]
  (and (string? url-str)
       (let [[_ q-and-f] (split-url-on-query url-str)
             [q _]       (split-query-on-fragment q-and-f)]
         (and (not (str/blank? q))
              (boolean
                (some (fn [pair]
                        (let [eq-idx (str/index-of pair "=")
                              pname  (if eq-idx (subs pair 0 eq-idx) pair)]
                          (sensitive-query-param? pname)))
                      (str/split q #"&")))))))

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
  (let [denylist-hit? (or (query-denylist-hit? (:url tags))
                          (query-denylist-hit? (get-in tags [:failure :url])))
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
  (let [denylist-hit? (query-denylist-hit? (:url failure))
        stamp?        (or (true? sensitive?) denylist-hit?)]
    (-> (redact-failure failure (true? sensitive?))
        (stamp-sensitive stamp?))))
