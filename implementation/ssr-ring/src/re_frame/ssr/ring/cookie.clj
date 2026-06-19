(ns re-frame.ssr.ring.cookie
  "Cookie serialisation (RFC 6265) for the Ring host adapter.

  Per Spec 011 §Cookie shape, re-frame.ssr stores cookies as structured
  maps and lets the host adapter materialise the Set-Cookie wire string.
  This intentionally keeps the per-attribute quoting / encoding details
  out of user code.

  Public surface:
    (cookie->set-cookie-header cookie-map) → string

  Re-exposed from the public façade `re-frame.ssr.ring` so tests, alt
  host adapters (Pedestal, HttpKit), and user code that needs a one-off
  serialisation can reach it without depending on the internal ns.

  Attribute-injection safety (rf2-rpedl, security audit 2026-05-14 §P1.2)
  — every attribute string that flows into the wire shape is validated
  for CR / LF / NUL before concatenation. A multi-tenant app that
  derives `:domain` (or any attribute) from tenant-controlled input
  would otherwise allow header-splitting via `\\r\\n` injection. Cookie
  `:name` carries a separate RFC 6265 §4.1.1 token-grammar gate (no
  CTLs, whitespace, or `( ) < > @ , ; : \\ \" / [ ] ? = { }`). Folds in
  the §P2.4 cookie-name grammar finding."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.ssr.http-validation :as http-validation])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.time Instant ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]))

;; Audit rf2-asmj1 P6 / cluster rf2-sljs1 — reflection-warning gate.
;; The JVM-side `Instant/ofEpochMilli` in `cookie->set-cookie-header`
;; has a primitive-long contract; surfacing reflection at compile time
;; flags accidentally-Long-boxed args before they NPE in production.
(set! *warn-on-reflection* true)

(def ^:private ^DateTimeFormatter rfc1123-formatter
  ;; Set-Cookie's :Expires uses RFC 7231 IMF-fixdate (a fixed-format
  ;; subset of RFC 1123): "Sun, 06 Nov 1994 08:49:37 GMT".
  (.withZone DateTimeFormatter/RFC_1123_DATE_TIME ZoneOffset/UTC))

(defn- url-encode
  "URL-encode a cookie value. java.net.URLEncoder emits `+` for space
  (form-encoded shape, RFC 1866), but RFC 6265 cookie values follow
  RFC 3986 percent-encoding — space is `%20`. Post-process the
  form-encoded output to convert `+` back to `%20` so the wire shape is
  RFC-3986-clean."
  [s]
  (-> (URLEncoder/encode (str s) (.name StandardCharsets/UTF_8))
      (str/replace "+" "%20")))

;; rf2-rpedl — every cookie attribute value (after :value, which is
;; URL-encoded above) is concatenated verbatim into the Set-Cookie
;; header. A CR / LF / NUL in :domain / :path / :max-age / :same-site /
;; :expires would split the header and let an attacker who controls one
;; of those fields forge a second header — RFC 7230 §3.2.4 explicitly
;; bans CR/LF/NUL inside header values. We reject up front so the
;; behaviour is portable across hosts (Jetty 11+ rejects, but earlier
;; Jetty / HttpKit / Pedestal don't all defend the same way).
;;
;; The injection-char grammar is single-sourced in
;; `re-frame.ssr.http-validation/contains-injection-char?` — the SAME
;; predicate the SSR fx boundary (`re-frame.ssr.response`) enforces, so
;; the materialiser and the fx-boundary gate can't drift on what counts
;; as a header-splitting char.

(defn- validate-attribute-string!
  [attr-key v]
  (let [s (str v)]
    (when (http-validation/contains-injection-char? s)
      (error/throw-error!
        :rf.error/cookie-invalid-attribute
        'rf.ssr/cookie->set-cookie-header
        (str "cookie attribute " attr-key
             " contains CR/LF/NUL — forbidden by"
             " RFC 7230 §3.2.4 (header-splitting"
             " injection). Strip CR/LF/NUL from the attribute value.")
        {:recovery :remove-injection-chars-from-cookie-attr
         :extra    {:attribute attr-key
                    :value     v}}))
    s))

;; rf2-rpedl / §P2.4 — RFC 6265 §4.1.1 cookie-name = token. The token
;; grammar (RFC 7230 §3.2.6) is `1*tchar` where `tchar` is the set of
;; visible US-ASCII chars MINUS the separators
;; `( ) < > @ , ; : \ " / [ ] ? = { }` and whitespace. Single-sourced in
;; `re-frame.ssr.http-validation/valid-token-name?` — the SAME predicate
;; the SSR fx boundary (`re-frame.ssr.response`) enforces on cookie +
;; header names, so the materialiser and the fx-boundary gate can't drift
;; on the token grammar.

(defn- validate-cookie-name!
  [n]
  ;; rf2-d95o1y — type-guard BEFORE `(clojure.core/name n)`. A cookie
  ;; `:name` is only a string or a `Named` (keyword / symbol); anything
  ;; else (a Long `42`, a vector `[]`, a map `{}`, …) would make
  ;; `clojure.core/name` throw a raw `ClassCastException` with nil
  ;; ex-data, bypassing the documented `:rf.error/cookie-invalid-name`
  ;; contract and reaching `:on-error` / adapter code as a generic host
  ;; exception. Reject the unsupported type loudly through the same
  ;; structured error id so the validation contract holds for every
  ;; name shape, not just bad strings.
  (when-not (or (string? n) (instance? clojure.lang.Named n))
    (error/throw-error!
      :rf.error/cookie-invalid-name
      'rf.ssr/cookie->set-cookie-header
      (str "cookie :name must be a string or a keyword/symbol; got a "
           (.getName (class n)) " (" (pr-str n) "). Use a string or"
           " keyword token-grammar cookie name.")
      {:recovery :use-a-token-grammar-cookie-name
       :extra    {:name n
                  :type (.getName (class n))}}))
  (let [s (clojure.core/name n)]
    (if (http-validation/valid-token-name? s)
      s
      (error/throw-error!
        :rf.error/cookie-invalid-name
        'rf.ssr/cookie->set-cookie-header
        (str "cookie :name violates RFC 6265 §4.1.1"
             " token grammar (no CTLs, whitespace,"
             " or separators ()<>@,;:\\\"/[]?={}); got "
             (pr-str s) ". Use a token-grammar cookie name.")
        {:recovery :use-a-token-grammar-cookie-name
         :extra    {:name n}}))))

(defn- same-site-token [v]
  (case v
    :strict "Strict"
    :lax    "Lax"
    :none   "None"
    ;; tolerant of string-shaped values
    (cond
      (string? v) v
      (keyword? v) (str/capitalize (name v))
      :else (str v))))

(defn cookie->set-cookie-header
  "Serialise one re-frame.ssr cookie map to a Set-Cookie header value
  per RFC 6265 §4.1. Per Spec 011 §Cookie shape — the cookie's :name /
  :value are required; everything else is an attribute appended after
  semicolons. The :value is URL-encoded.

  Public surface so tests, alt host adapters (Pedestal, HttpKit), and
  user code that needs a one-off serialisation can call it directly.

  Validation (rf2-rpedl, security audit 2026-05-14 §P1.2 + §P2.4):
    - `:name` is checked against the RFC 6265 §4.1.1 token grammar — no
      CTLs / whitespace / separators. Throws `:rf.error/cookie-invalid-name`.
    - `:domain` / `:path` / `:max-age` / `:same-site` are checked for
      CR / LF / NUL before concatenation. Throws
      `:rf.error/cookie-invalid-attribute`. `:value` is URL-encoded
      upstream so any CR/LF/NUL in it ends up as `%0D` / `%0A` / `%00` —
      no injection path through `:value`.
    - `:expires` was already type-checked as `integer?`; no string
      content reaches the wire."
  [{:keys [name value max-age secure http-only same-site path domain expires]
    :as cookie}]
  (when (nil? name)
    (error/throw-error!
      :rf.error/cookie-missing-name
      'rf.ssr/cookie->set-cookie-header
      "cookie map must carry :name; add a :name key to the cookie map."
      {:recovery :supply-a-cookie-name
       :extra    {:cookie cookie}}))
  ;; rf2-rpedl §P2.4 — RFC 6265 §4.1.1 cookie-name token grammar.
  ;; rf2-d95o1y — `validate-cookie-name!` returns the type-guarded,
  ;; coerced name string; reuse it for the wire form so `(name …)` is
  ;; not re-derived (and the type guard cannot be skipped on the wire
  ;; path).
  (let [name-str (validate-cookie-name! name)]
    ;; `Instant/ofEpochMilli` takes a primitive long; passing anything
    ;; else (a string-shaped epoch from a misconfigured projector, a
    ;; `java.util.Date`, …) would NPE deep inside the format path. Catch
    ;; the type-mismatch up front with a clear
    ;; `:rf.error/cookie-invalid-expires` so the misuse surfaces with the
    ;; cookie's actual shape attached.
    (when (and (some? expires) (not (integer? expires)))
      (error/throw-error!
        :rf.error/cookie-invalid-expires
        'rf.ssr/cookie->set-cookie-header
        (str ":expires must be an epoch-millis long; got "
             (.getName (class expires))
             ". Pass :expires as a long count of milliseconds since the epoch.")
        {:recovery :supply-epoch-millis-long
         :extra    {:expires expires
                    :cookie  cookie}}))
    ;; rf2-rpedl §P1.2 — gate every string-shaped attribute that gets
    ;; concatenated into the Set-Cookie wire form. `:value` is URL-encoded
    ;; (CR/LF/NUL come out as %0D/%0A/%00 — no injection path); `:expires`
    ;; flows through `rfc1123-formatter` which only emits ASCII letters /
    ;; digits / `, : SP`; `:secure` / `:http-only` are booleans. The
    ;; remaining string-typed slots — `:domain`, `:path`, `:max-age`
    ;; (callers sometimes pass strings), `:same-site` (string form
    ;; tolerated by same-site-token) — are validated here.
    (when (some? domain)    (validate-attribute-string! :domain    domain))
    (when (some? path)      (validate-attribute-string! :path      path))
    (when (some? max-age)   (validate-attribute-string! :max-age   max-age))
    (when (some? same-site) (validate-attribute-string! :same-site same-site))
    (let [parts (cond-> [(str name-str
                              "="
                              (url-encode (or value "")))]
                  ;; Order doesn't matter to the RFC, but the canonical
                  ;; serving order in most libraries is:
                  ;;   Max-Age, Domain, Path, Expires, Secure, HttpOnly, SameSite
                  (some? max-age)  (conj (str "Max-Age=" max-age))
                  (some? domain)   (conj (str "Domain=" domain))
                  (some? path)     (conj (str "Path=" path))
                  (some? expires)
                  (conj (str "Expires="
                             (.format rfc1123-formatter
                                      (ZonedDateTime/ofInstant
                                        ;; `expires` was type-checked above
                                        ;; (`integer?`); coerce to a
                                        ;; primitive long so the static-
                                        ;; method dispatch picks the long
                                        ;; arity without reflection.
                                        (Instant/ofEpochMilli (long expires))
                                        ZoneOffset/UTC))))
                  (true? secure)    (conj "Secure")
                  (true? http-only) (conj "HttpOnly")
                  (some? same-site) (conj (str "SameSite=" (same-site-token same-site))))]
      (str/join "; " parts))))
