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
  serialisation can reach it without depending on the internal ns."
  (:require [clojure.string :as str])
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
  user code that needs a one-off serialisation can call it directly."
  [{:keys [name value max-age secure http-only same-site path domain expires]
    :as cookie}]
  (when (nil? name)
    (throw (ex-info ":rf.error/cookie-missing-name"
                    {:reason "cookie map must carry :name"
                     :cookie cookie})))
  ;; Per audit rf2-asmj1 R7 / cluster rf2-sljs1: `Instant/ofEpochMilli`
  ;; takes a primitive long; passing anything else (a string-shaped
  ;; epoch from a misconfigured projector, a `java.util.Date`, …) would
  ;; NPE deep inside the format path. Catch the type-mismatch up front
  ;; with a clear `:rf.error/cookie-invalid-expires` so the misuse
  ;; surfaces with the cookie's actual shape attached.
  (when (and (some? expires) (not (integer? expires)))
    (throw (ex-info ":rf.error/cookie-invalid-expires"
                    {:reason   (str ":expires must be an epoch-millis long; got " (.getName (class expires)))
                     :expires  expires
                     :cookie   cookie
                     :recovery :no-recovery})))
  (let [parts (cond-> [(str (clojure.core/name name)
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
    (str/join "; " parts)))
