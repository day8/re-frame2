(ns re-frame.http.url
  "URL handling for the http artefact — query-string redaction surface
  (Spec 014 §Privacy, rf2-2p8wr) plus the query-param denylist.

  This namespace owns URL *redaction / scrubbing* for the http artefact
  (split, walk, redact, denylist). URL *building* — `url-encode`,
  `params->query`, `merge-params` — lives in `re-frame.http.encoding`
  (the rf2-5ijhk encoding split). The two halves stay separate: redaction
  is a privacy-leaf surface, building is an encoding concern.

  ## Query-param denylist (rf2-2p8wr)

  `default-query-param-denylist` covers the canonical query-string-auth
  surface (api_key, apikey, access_token, auth, token, key, secret,
  password, session, signature, sig). These built-in names are an
  **immutable framework default** — no frame can remove one.

  ## App-specific carriers are frame policy (EP-0015 §3, rf2-ppkh3v)

  An app extends the denylist with its own sensitive query-param names
  (e.g. `shop_token`, webhook `signature` variants) on the FRAME, not
  through a process-global mutation:

      (rf/reg-frame :app/main
        {:sensitive {:http {:query-params [\"shop_token\"]}}})

  The frame slice retains the names on the frame's `:config`;
  `re-frame.frame-classification/http-carriers` lowers them to a
  lower-cased extension set, which the redactors UNION onto the immutable
  built-in defaults for the emitting frame. The old process-global
  `declare-sensitive-query-param!` / `clear-sensitive-query-params!`
  surface is removed — frame policy owns app-specific carriers.

  ## Redaction shape

  URLs carrying denylisted params have the *value* redacted in every
  `:rf.http/*` trace event regardless of the originating handler's
  `:sensitive?` flag — the param name itself is the signal. When the
  request is sensitive (per the resolver in `re-frame.http.privacy`),
  **all** query params are redacted (the broader rule). Fragments are
  preserved verbatim past the redaction step.

  ## Production elision

  Like the rest of the privacy machinery, redact / stamp helpers gate
  on `interop/debug-enabled?` at their call sites (same gate as
  `trace/emit!`); no walker runs without the trace surface."
  (:require [clojure.string :as str]
            [re-frame.privacy :as privacy])
  #?(:clj (:import [java.net URLDecoder])))

;; ---- redaction sentinel ---------------------------------------------------
;;
;; The framework-reserved redaction sentinel per Spec 009 §Privacy sits in
;; the `:rf/` reserved-keyword namespace so apps cannot legitimately produce
;; it as a payload value. Consumers wanting "was this redacted?" check
;; `(= :rf/redacted v)`.
;;
;; rf2-qe237 — the single source of truth is now `re-frame.privacy/
;; redacted-sentinel` in core (already consumed by elision / marks). Core is
;; on the http classpath, so this artefact refers to the canonical def
;; rather than re-declaring the keyword literal. `http-url` remains the
;; http-side re-export anchor — it is the privacy leaf with no intra-privacy
;; dependencies, so `http-privacy` and `http-privacy-headers` can both refer
;; to it without creating a leaf→parent cycle (rf2-ee38b.7 intra-http shape).

(def redacted-sentinel
  "The framework-reserved redaction sentinel keyword per Spec 009 §Privacy.
  Re-exported from the canonical `re-frame.privacy/redacted-sentinel` in
  core (rf2-qe237). This is the http-side public anchor — `http-privacy`
  and `http-privacy-headers` both refer to it privately (rf2-m00e7p), so
  the keyword cannot drift across the http privacy cluster."
  privacy/redacted-sentinel)

;; ---- query-param denylist (rf2-2p8wr) -------------------------------------

(def default-query-param-denylist
  "Canonical always-sensitive HTTP query-string parameter names, lower-
  cased. URLs carrying these params have the *values* redacted in
  `:rf.http/*` trace events regardless of the handler's `:sensitive?`
  flag — the param names themselves are the signal (mirrors the header
  denylist contract).

  Drawn from common API-key / bearer-token idioms in older REST APIs,
  webhook receivers, and signed-URL schemes. An **immutable framework
  default** — no frame can remove one. Apps extend with their own carrier
  names on the FRAME via `:sensitive {:http {:query-params [\"shop_token\"]}}`
  (EP-0015 §3); the frame extension set UNIONS onto these defaults."
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

(defn sensitive-query-param?
  "Predicate: is `param-name` sensitive under the emitting frame's effective
  query-param policy? Case-insensitive.

  `frame-policy` is the frame-local query-param carrier policy resolved from
  the emitting frame's `:sensitive {:http {:query-params ..}}` config
  (EP-0015 §3), or `nil` when the frame declares none. Two shapes are
  accepted (rf2-4wqxq8):

   - a **set** of lower-cased names — the legacy include-only extension set
     (`:query-params [\"shop_token\"]`). The built-in defaults always apply;
     the set EXTENDS them.
   - a **policy map** `{:include #{..} :except #{..}}` — `:include` extends
     the defaults (as above); `:except` SUBTRACTS from the immutable
     built-in defaults for THIS frame's own dev trace. The effective policy
     is `(defaults − except) ∪ include`. A name in BOTH wins as sensitive
     (`:include` takes precedence over `:except`) — declaring a name
     sensitive is never overridden by also excepting it.

  The `:except` path is a frame-local DEV-TRACE subtraction only (all
  redaction is debug-gated trace surface, elided entirely in production);
  it lets a frame stop redacting a harmless routing/pagination key/token in
  its OWN local trace. The immutable header denylist and the on-by-default
  query defaults are unchanged — `:except` is opt-in per name.

  Per rf2-ydp66 — short-circuits via `contains?` lookups on the source sets
  rather than building a fresh union per call."
  ([param-name] (sensitive-query-param? param-name nil))
  ([param-name frame-policy]
   (boolean
     (when (string? param-name)
       (let [lowered (str/lower-case param-name)
             ;; Normalise both accepted shapes to [include except].
             [include except] (if (map? frame-policy)
                                [(:include frame-policy) (:except frame-policy)]
                                [frame-policy nil])
             included? (and include (contains? include lowered))]
         ;; :include wins over :except (declaring sensitive is never undone).
         (or included?
             (and (contains? default-query-param-denylist lowered)
                  (not (and except (contains? except lowered))))))))))

(defn- percent-decode-name
  "rf2-065xo — percent-decode a query-param NAME for denylist comparison
  ONLY. Returns the decoded form, or `nil` when there is nothing to
  decode (no `%`) or when the escape sequence is malformed.

  NEVER throws: a malformed escape (`%`-not-followed-by-two-hex,
  truncated tail, invalid byte sequence) yields `nil` so the caller
  falls back to the raw spelling. This keeps redaction total — a
  hand-crafted or attacker-supplied URL with a broken escape can never
  crash the trace/redaction path (Spec 014 §Privacy: redaction must not
  throw).

  Decoded for COMPARISON only — the rebuilt URL always preserves the
  original raw param spelling (`redact-query-param` only ever swaps the
  *value*, never re-encodes the name)."
  [name-str]
  (when (and (string? name-str)
             (str/includes? name-str "%"))
    (try
      #?(:clj  (URLDecoder/decode name-str "UTF-8")
         :cljs (js/decodeURIComponent name-str))
      (catch #?(:clj Throwable :cljs :default) _
        ;; Malformed percent-escape → fall back to raw (caller uses the
        ;; raw-name match path). Total-by-construction.
        nil))))

(defn sensitive-query-param-name?
  "rf2-065xo — denylist match against a query-param name comparing BOTH
  the RAW spelling AND the percent-DECODED spelling (case-insensitively
  via `sensitive-query-param?`).

  Closes the gap where a percent-encoded denylisted name —
  `api%5Fkey` (= `api_key`), `%61ccess_token` (= `access_token`), a
  frame-declared `shop%5Ftoken` — read as a non-sensitive raw string and
  leaked its value into trace events unless the whole request was marked
  sensitive. The redactor consults the decoded form so an encoded auth
  token is denied just like its plain spelling.

  `frame-policy` is the emitting frame's frame-local query-param carrier
  policy (EP-0015 §3) — a legacy include-only set OR a
  `{:include #{..} :except #{..}}` map (rf2-4wqxq8) — or `nil` for
  defaults-only. Threaded verbatim to `sensitive-query-param?`.

  Decode is comparison-only and total: a malformed escape decodes to
  `nil`, so matching falls back to the raw name and never throws."
  ([param-name] (sensitive-query-param-name? param-name nil))
  ([param-name frame-policy]
   (boolean
     (or (sensitive-query-param? param-name frame-policy)
         (when-let [decoded (percent-decode-name param-name)]
           (sensitive-query-param? decoded frame-policy))))))

;; ---- URL query-string redaction (rf2-2p8wr) -------------------------------

(def ^:private redacted-url-token
  "Inline string form of the `:rf/redacted` sentinel suitable for splicing
  into a URL string. Trace consumers parsing the URL still see a structural
  token they can detect (`:rf%2Fredacted` once URL-encoded into the wire
  string we serialise). The unencoded form is `:rf/redacted` — identical
  text to the keyword's `pr-str` — chosen so a human inspecting a trace
  event reads the same sentinel they see in any other redacted slot.

  rf2-ee38b.7 — derived from the canonical `redacted-sentinel` keyword so
  the two forms can never drift."
  (str redacted-sentinel))

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
  `=`, empty value).

  rf2-065xo — the denylist check is `sensitive-query-param-name?`, which
  compares both the RAW and percent-DECODED name, so an encoded
  denylisted name (`api%5Fkey`, `%61ccess_token`, frame-declared
  `shop%5Ftoken`) is redacted. The original raw `pname` is preserved
  verbatim in the rebuilt pair — only the value is replaced (decoding is
  comparison-only).

  `frame-policy` is the emitting frame's frame-local query-param carrier
  policy (EP-0015 §3) — a legacy include-only set OR a
  `{:include #{..} :except #{..}}` map (rf2-4wqxq8) — or `nil` for
  defaults-only."
  [pair force-all? frame-policy]
  (let [eq-idx (str/index-of pair "=")
        [pname _pvalue] (if eq-idx
                          [(subs pair 0 eq-idx) (subs pair (inc eq-idx))]
                          [pair nil])]
    (if (or force-all? (sensitive-query-param-name? pname frame-policy))
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

  `frame-policy` is the emitting frame's frame-local query-param carrier
  policy (EP-0015 §3), or `nil` for defaults-only. Accepts a legacy
  include-only set (UNIONed onto the immutable built-in denylist) OR a
  `{:include #{..} :except #{..}}` map (rf2-4wqxq8) whose `:except` set
  SUBTRACTS from the built-in defaults for this frame's own dev trace —
  effective policy `(defaults − except) ∪ include`. Applies to the
  always-on (`sensitive?` false) redaction; a `sensitive?`-true request
  still redacts EVERY param regardless of policy.

  Returns `[redacted-url any-redacted?]` where `any-redacted?` is true
  iff at least one param value was redacted (the caller uses this to
  decide whether to stamp `:sensitive?` per the denylist-is-signal
  rule). Nil / non-string / no-query inputs return `[url-str false]`."
  ([url-str sensitive?] (redact-url-query-string url-str sensitive? nil))
  ([url-str sensitive? frame-policy]
   (cond
     (not (string? url-str))
     [url-str false]

     :else
     (let [[base query-and-fragment] (split-url-on-query url-str)
           [query fragment]          (split-query-on-fragment query-and-fragment)]
       (if (str/blank? query)
         [url-str false]
         ;; rf2-dqchf — single pass: thread a volatile changed? flag
         ;; through the mapv so the redactor and the change-detection
         ;; share one walk over the pairs. Previously the change
         ;; detection ran a second `(map not= pairs redacted)` walk and
         ;; allocated a lazy seq of N booleans for the `some true?` test
         ;; despite the rf2-02vzz claim of fusion.
         (let [force-all? (true? sensitive?)
               changed?   (volatile! false)
               pairs      (str/split query #"&")
               redacted   (mapv (fn [p]
                                  (let [out (redact-query-param p force-all? frame-policy)]
                                    (when-not (identical? out p)
                                      (vreset! changed? true))
                                    out))
                                pairs)
               rebuilt    (str base "?" (str/join "&" redacted) (or fragment ""))]
           [rebuilt @changed?]))))))

(defn redact-url
  "Convenience wrapper around `redact-url-query-string` that returns only
  the redacted URL string. Use when the caller does not need the
  any-redacted? flag (e.g. inside a generic tag-walker).

  rf2-ee38b.7 — public for direct test assertion only; production reaches
  URL redaction via `re-frame.http.privacy`'s `prepare-emit-*` composers
  (which use `redact-url-query-string` directly for the flag). No
  production caller invokes this single-value wrapper."
  ([url-str sensitive?] (first (redact-url-query-string url-str sensitive? nil)))
  ([url-str sensitive? frame-policy]
   (first (redact-url-query-string url-str sensitive? frame-policy))))
