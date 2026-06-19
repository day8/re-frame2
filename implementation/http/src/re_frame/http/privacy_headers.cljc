(ns re-frame.http.privacy-headers
  "HTTP header denylist for Spec 014 §Privacy (rf2-bma05).

  HTTP request / response headers are the canonical bearer-token surface
  (Authorization, Cookie, Set-Cookie, X-API-Key, …). The header denylist
  defined here is unconditional: matching headers are redacted in every
  `:rf.http/*` trace event regardless of the handler / request
  `:sensitive?` flag, because the header names themselves declare them
  sensitive (an `Authorization` header leaking would be a leak even if
  the surrounding handler was not declared sensitive).

  `default-header-denylist` covers the canonical surface. These built-in
  names are an **immutable framework default** — no frame can remove one.

  ## App-specific carriers are frame policy (EP-0015 §3, rf2-ppkh3v)

  An app extends the denylist with its own sensitive header names (e.g.
  `X-Honeycomb-Team`, `X-Stripe-Signature`) on the FRAME, not through a
  process-global mutation:

      (rf/reg-frame :app/main
        {:sensitive {:http {:headers [\"X-Honeycomb-Team\"]}}})

  The frame slice validates + retains the carrier names on the frame's
  `:config`; `re-frame.frame-classification/http-carriers` (reached via the
  `:frame-classification/http-carriers` late-bind hook) lowers them to a
  lower-cased extension set, which `redact-headers` / `sensitive-header?`
  UNION onto the immutable built-in defaults for the emitting frame. The
  old process-global `declare-sensitive-header!` / `clear-sensitive-headers!`
  surface is removed — frame policy owns app-specific carriers (EP-0015 §3).

  ## Production elision

  Like the rest of the privacy machinery, the redactor here gates on
  `interop/debug-enabled?` at its call sites (same gate as
  `trace/emit!`). In production builds the trace surface elides
  entirely; this namespace's walker never runs."
  (:require [clojure.string :as str]
            [re-frame.http.url :as url]))

;; rf2-ee38b.7 — the framework-reserved redaction sentinel per Spec 009
;; §Privacy now has a single canonical home in the sibling leaf
;; `re-frame.http.url` (which carries no privacy deps, so there is no
;; leaf→parent cycle). Previously this namespace held its own private
;; copy AND `http-url` held the string form AND `http-privacy` held the
;; keyword — three coordinated edits to change one value. Refer to the
;; canonical def instead.
(def ^:private redacted-sentinel url/redacted-sentinel)

(def default-header-denylist
  "Canonical always-sensitive HTTP header names, lower-cased. These are
  redacted in `:rf.http/*` trace events regardless of the handler's
  `:sensitive?` flag — the header names themselves are the signal.

  Drawn from the OWASP Authentication Cheatsheet plus common bearer-
  scheme headers used by SaaS APIs. An **immutable framework default** —
  no frame can remove one. Apps extend with their own carrier names on
  the FRAME via `:sensitive {:http {:headers [\"X-Honeycomb-Team\"]}}`
  (EP-0015 §3); the frame extension set UNIONS onto these defaults."
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
  the emitting frame's `frame-extras`)? Case-insensitive.

  `frame-extras` is the frame-local carrier extension set — a set of
  lower-cased header names resolved from the emitting frame's
  `:sensitive {:http {:headers [..]}}` policy (EP-0015 §3), or `nil` when
  the frame declares none. The built-in defaults always apply; the frame
  set EXTENDS them.

  Per rf2-ydp66 — the predicate short-circuits via `contains?` lookups on
  the source sets rather than building a fresh union per call. `redact-headers`
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
  the emitting frame's `frame-extras`) with `:rf/redacted`. Returns a new
  map; nil/empty input returns the input unchanged. Case-insensitive on
  header names.

  `frame-extras` is the emitting frame's frame-local header carrier
  extension set (EP-0015 §3), or `nil` for defaults-only."
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
