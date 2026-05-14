(ns re-frame.http-url
  "URL handling for the http artefact — query-string redaction surface
  (Spec 014 §Privacy, rf2-2p8wr) plus the query-param denylist.

  This namespace owns URLs end-to-end for the http artefact. Today it
  hosts the redaction half (split, walk, redact, denylist). When the
  http-encoding split lands (rf2-5ijhk), `url-encode`, `params->query`,
  and `merge-params` move here too — making `http-url` the single
  authority for URL parsing, building, and scrubbing across the artefact.

  ## Query-param denylist (rf2-2p8wr)

  `default-query-param-denylist` covers the canonical query-string-auth
  surface (api_key, apikey, access_token, auth, token, key, secret,
  password, session, signature, sig). Apps extend via
  `(declare-sensitive-query-param! \"my_token\")` — names are
  case-insensitive; the registry stores lower-cased.

  ## Redaction shape

  URLs carrying denylisted params have the *value* redacted in every
  `:rf.http/*` trace event regardless of the originating handler's
  `:sensitive?` flag — the param name itself is the signal. When the
  request is sensitive (per the resolver in `re-frame.http-privacy`),
  **all** query params are redacted (the broader rule). Fragments are
  preserved verbatim past the redaction step.

  ## Production elision

  Like the rest of the privacy machinery, redact / stamp helpers gate
  on `interop/debug-enabled?` at their call sites (same gate as
  `trace/emit!`). The denylist atom itself ships in production
  (app-readable state — `declare-sensitive-query-param!` writes
  through), but no walker runs without the trace surface."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

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

;; ---- URL query-string redaction (rf2-2p8wr) -------------------------------

(def ^:private redacted-url-token
  "Inline string form of the `:rf/redacted` sentinel suitable for splicing
  into a URL string. Trace consumers parsing the URL still see a structural
  token they can detect (`:rf%2Fredacted` once URL-encoded into the wire
  string we serialise). The unencoded form is `:rf/redacted` — identical
  text to the keyword's `pr-str` — chosen so a human inspecting a trace
  event reads the same sentinel they see in any other redacted slot."
  ":rf/redacted")

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
