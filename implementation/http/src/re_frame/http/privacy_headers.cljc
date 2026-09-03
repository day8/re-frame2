(ns re-frame.http.privacy-headers
  "HTTP header denylist for Spec 014 §Privacy.

  HTTP request / response headers are the canonical bearer-token surface
  (Authorization, Cookie, Set-Cookie, X-API-Key, …). The header denylist
  defined here is unconditional: matching headers are redacted in every
  `:rf.http/*` trace event regardless of the request
  `:sensitive?` flag, because the header names themselves declare them
  sensitive (an `Authorization` header leaking would be a leak even if
  the surrounding handler was not declared sensitive).

  `default-header-denylist` covers the canonical surface. These built-in
  names are an **immutable framework default** — no frame can remove one.

  ## App-specific carriers

  An app extends the denylist with its own sensitive header names (e.g.
  `X-Honeycomb-Team`, `X-Stripe-Signature`) on the `:rf.http/managed`
  `reg-fx` registration metadata, not through a mutable global or frame
  annotation:

      (rf/reg-fx :rf.http/managed
        {:carriers {:headers [\"X-Honeycomb-Team\"]}}
        http-managed/managed-handler)

  `re-frame.http.privacy/managed-carriers` reads the registration metadata
  and lowers the `:carriers` names to a lower-cased extension set, which
  `redact-headers` / `sensitive-header?` UNION onto the immutable built-in
  defaults. The managed-HTTP registration owns app-specific carriers.

  ## Production elision

  Like the rest of the privacy machinery, the redactor here gates on
  `interop/debug-enabled?` at its call sites (same gate as
  `trace/emit!`). In production builds the trace surface elides
  entirely; this namespace's walker never runs."
  (:require [clojure.string :as str]
            [re-frame.http.url :as rf.http.url]))

;; Keep the sentinel in the dependency-leaf URL namespace so every HTTP
;; redactor can share it without creating a privacy dependency cycle.
(def ^:private redacted-sentinel rf.http.url/redacted-sentinel)

(def default-header-denylist
  "Canonical always-sensitive HTTP header names, lower-cased. These are
  redacted in `:rf.http/*` trace events regardless of the request's
  `:sensitive?` flag — the header names themselves are the signal.

  Drawn from the OWASP Authentication Cheatsheet plus common bearer-
  scheme headers used by SaaS APIs. An **immutable framework default** —
  no app can remove one. Apps extend with their own carrier names on the
  `:rf.http/managed` `reg-fx` registration via `:carriers {:headers
  [\"X-Honeycomb-Team\"]}` (EP-0025); the carrier extension set UNIONS onto
  these defaults."
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

(defn sensitive-header?
  "Predicate: is `header-name` in the merged denylist (built-in defaults ∪
  the supplied carrier extensions)? Case-insensitive.

  `frame-extras` is the app-declared carrier extension set — a set of
  lower-cased header names resolved from the `:rf.http/managed`
  `reg-fx` registration's `:carriers {:headers [..]}` block, or `nil` when no
  carrier is declared. The parameter name is retained for the leaf API and
  fixture contract; the extension set is not frame state.

  The predicate checks the source sets directly rather than building a fresh
  union per call. `redact-headers`
  calls this once per header (`reduce-kv` over the request's headers map).
  With trace/privacy permanently on under JVM (`interop/debug-enabled?` is
  constant `true` there), a merged-set rebuild would compound across every
  traced HTTP request; the two `contains?` ops on small sets are O(1) and
  allocate nothing."
  ([header-name] (sensitive-header? header-name nil))
  ([header-name frame-extras]
   (boolean
     (when (string? header-name)
       (let [lowered (str/lower-case header-name)]
         (or (contains? default-header-denylist lowered)
             (and frame-extras (contains? frame-extras lowered))))))))

(defn redact-headers
  "Walk `headers-map` (string→string or string→vector-of-strings); replace
  values whose key matches the merged header denylist (built-in defaults ∪
  the app-declared `frame-extras`) with `:rf/redacted`. Returns a new
  map; nil/empty input returns the input unchanged. Case-insensitive on
  header names.

  `frame-extras` is the app-declared header carrier extension set from the
  `:rf.http/managed` `:carriers {:headers [..]}` block, or `nil`
  for defaults-only. (Param name retained for the leaf API contract.)"
  ([headers-map] (redact-headers headers-map nil))
  ([headers-map frame-extras]
   (cond
     (nil? headers-map) headers-map
     (not (map? headers-map)) headers-map
     :else
     (reduce-kv
       (fn [acc k v]
         (assoc acc k
                (if (sensitive-header? (if (keyword? k) (name k) (str k)) frame-extras)
                  redacted-sentinel
                  v)))
       {}
       headers-map))))
