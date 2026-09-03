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

  Attribute-injection safety: every attribute string that flows into the
  wire shape is validated
  for CR / LF / NUL before concatenation. A multi-tenant app that
  derives `:domain` (or any attribute) from tenant-controlled input
  would otherwise allow header-splitting via `\\r\\n` injection. Cookie
  `:name` carries a separate RFC 6265 §4.1.1 token-grammar gate."
  (:require [clojure.string :as str]
            [re-frame.error :as error]
            [re-frame.ssr.http-validation :as http-validation])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.time Instant ZoneOffset ZonedDateTime]
           [java.time.format DateTimeFormatter]))

(set! *warn-on-reflection* true)

(def ^:private ^DateTimeFormatter rfc1123-formatter
  ;; Set-Cookie's :Expires uses RFC 7231 IMF-fixdate (a fixed-format
  ;; subset of RFC 1123): "Sun, 06 Nov 1994 08:49:37 GMT".
  (.withZone DateTimeFormatter/RFC_1123_DATE_TIME ZoneOffset/UTC))

(defn- encode-cookie-value
  "URL-encode a cookie value. java.net.URLEncoder emits `+` for space
  (form-encoded shape, RFC 1866), but RFC 6265 cookie values follow
  RFC 3986 percent-encoding — space is `%20`. Post-process the
  form-encoded output to convert `+` back to `%20` so the wire shape is
  RFC-3986-clean."
  [cookie-value]
  (-> (URLEncoder/encode (str cookie-value) (.name StandardCharsets/UTF_8))
      (str/replace "+" "%20")))

;; Cookie attributes (:domain / :path / :max-age / :same-site) are
;; concatenated VERBATIM after `; ` separators, so reject both the CR/LF/NUL
;; header-splitting chars AND the raw `;` cookie-attribute delimiter — a `;`
;; inside a value escapes its assigned attribute and fabricates extra
;; attributes (SameSite=None, Secure, …). Cookie :value is exempt: it is
;; percent-encoded upstream, so a `;` there stays data (`%3B`). The shared
;; predicate keeps the effect boundary and this last wire boundary on one
;; grammar.

(defn- validate-cookie-attribute!
  [attribute-key attribute-value]
  (let [wire-value (str attribute-value)]
    (when (http-validation/contains-cookie-attr-injection-char? wire-value)
      (error/throw-error!
        :rf.error/cookie-invalid-attribute
        'rf.ssr/cookie->set-cookie-header
        (str "cookie attribute " attribute-key
             " contains CR/LF/NUL or a `;` — forbidden by"
             " RFC 7230 §3.2.4 (header-splitting injection) and"
             " RFC 6265 §4.1.1 (the `;` is the cookie-attribute delimiter,"
             " so it would fabricate extra Set-Cookie attributes). Strip"
             " CR/LF/NUL and `;` from the attribute value.")
        {:recovery :remove-injection-chars-from-cookie-attr
         :extra    {:attribute attribute-key
                    :value     attribute-value}}))
    wire-value))

;; RFC 6265 cookie names use the shared RFC 7230 token grammar.

(defn- validate-cookie-name!
  [cookie-name]
  ;; Guard the type before `name` so every invalid shape uses the structured
  ;; cookie-name error instead of leaking a ClassCastException.
  (when-not (or (string? cookie-name)
                (instance? clojure.lang.Named cookie-name))
    (error/throw-error!
      :rf.error/cookie-invalid-name
      'rf.ssr/cookie->set-cookie-header
      (str "cookie :name must be a string or a keyword/symbol; got a "
           (.getName (class cookie-name)) " (" (pr-str cookie-name)
           "). Use a string or"
           " keyword token-grammar cookie name.")
      {:recovery :use-a-token-grammar-cookie-name
       :extra    {:name cookie-name
                  :type (.getName (class cookie-name))}}))
  (let [wire-name (clojure.core/name cookie-name)]
    (if (http-validation/valid-token-name? wire-name)
      wire-name
      (error/throw-error!
        :rf.error/cookie-invalid-name
        'rf.ssr/cookie->set-cookie-header
        (str "cookie :name violates RFC 6265 §4.1.1"
             " token grammar (no CTLs, whitespace,"
             " or separators ()<>@,;:\\\"/[]?={}); got "
             (pr-str wire-name) ". Use a token-grammar cookie name.")
        {:recovery :use-a-token-grammar-cookie-name
         :extra    {:name cookie-name}}))))

(defn- same-site-token [same-site]
  (case same-site
    :strict "Strict"
    :lax    "Lax"
    :none   "None"
    ;; tolerant of string-shaped values
    (cond
      (string? same-site) same-site
      (keyword? same-site) (str/capitalize (name same-site))
      :else (str same-site))))

(defn cookie->set-cookie-header
  "Serialise one re-frame.ssr cookie map to a Set-Cookie header value
  per RFC 6265 §4.1. Per Spec 011 §Cookie shape — the cookie's :name is
  required; :value is optional (a missing :value serialises as an empty
  string). Everything else is an attribute appended after semicolons. The
  :value is URL-encoded.

  Public surface so tests, alt host adapters (Pedestal, HttpKit), and
  user code that needs a one-off serialisation can call it directly.

  Validation:
    - `:name` is checked against the RFC 6265 §4.1.1 token grammar — no
      CTLs / whitespace / separators. Throws `:rf.error/cookie-invalid-name`.
    - `:domain` / `:path` / `:max-age` / `:same-site` are concatenated
      verbatim after `; ` separators, so they are checked for CR / LF / NUL
      AND the raw `;` cookie-attribute delimiter before concatenation — a
      `;` inside a value would escape its attribute and fabricate extra
      attributes (`SameSite=None`, `Secure`, …). Throws
      `:rf.error/cookie-invalid-attribute`. `:value` is URL-encoded
      upstream so any CR/LF/NUL/`;` in it ends up as `%0D` / `%0A` / `%00`
      / `%3B` — no injection path through `:value`.
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
  ;; Reuse the validated name string so the wire path cannot skip the guard.
  (let [wire-name (validate-cookie-name! name)]
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
    ;; Validate the attributes that reach the wire as caller-shaped strings.
    ;; Values are URL-encoded, expiry is formatter-owned, and flags are booleans.
    (when (some? domain)    (validate-cookie-attribute! :domain    domain))
    (when (some? path)      (validate-cookie-attribute! :path      path))
    (when (some? max-age)   (validate-cookie-attribute! :max-age   max-age))
    (when (some? same-site) (validate-cookie-attribute! :same-site same-site))
    (let [header-parts (cond-> [(str wire-name
                              "="
                              (encode-cookie-value (or value "")))]
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
      (str/join "; " header-parts))))
