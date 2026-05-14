(ns re-frame.http-privacy-headers
  "HTTP header denylist for Spec 014 §Privacy (rf2-bma05).

  HTTP request / response headers are the canonical bearer-token surface
  (Authorization, Cookie, Set-Cookie, X-API-Key, …). The header denylist
  defined here is unconditional: matching headers are redacted in every
  `:rf.http/*` trace event regardless of the handler / request
  `:sensitive?` flag, because the header names themselves declare them
  sensitive (an `Authorization` header leaking would be a leak even if
  the surrounding handler was not declared sensitive).

  `default-header-denylist` covers the canonical surface. Apps extend
  via `(declare-sensitive-header! \"X-My-Auth\")` — names are
  case-insensitive; the registry stores lower-cased.

  ## Production elision

  Like the rest of the privacy machinery, the redactor here gates on
  `interop/debug-enabled?` at its call sites (same gate as
  `trace/emit!`). In production builds the trace surface elides
  entirely; this namespace's walker never runs. The denylist atom
  itself ships in production (it's app-readable state —
  `declare-sensitive-header!` writes through)."
  (:require [clojure.set :as set]
            [clojure.string :as str]))

;; The framework-reserved redaction sentinel per Spec 009 §Privacy. Sits
;; in the `:rf/` reserved-keyword namespace so apps cannot legitimately
;; produce it as a payload value. Held here (not just in
;; `re-frame.http-privacy`) so this leaf namespace doesn't depend on its
;; orchestrating parent.
(def ^:private redacted-sentinel :rf/redacted)

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
