(ns re-frame.routing.egress
  "Routing-owned URL-carrier EGRESS scrub (EP-0015 / Spec 015) for the
  diagnostic trace slots the per-registration marks chokepoint cannot reach.

  EP-0015 treats trace/tool/log/epoch records as egress surfaces and requires
  off-box defaults to FAIL CLOSED. The framework's marks chokepoint
  (`re-frame.classification/project-trace-event` via `re-frame.trace/build-event`)
  already projects the KNOWN trace slots — `:rf.fx/args` against fx marks,
  the dispatched-event vector against event marks, etc. — so the routing fx
  (`:rf.nav/scroll`, `:rf.nav/capture-scroll`, `:rf.nav/push-url`,
  `:rf.nav/replace-url`) and the dispatched `:rf.route/navigation-blocked`
  event payload are covered by declaring `:sensitive` marks on their
  registrations (rf2-1wmni6 / rf2-pbbo68 / rf2-jfaucw).

  But routing also emits a few DIAGNOSTIC traces whose carrier rides a
  CUSTOM tag slot the marks chokepoint does not walk:

    - `:rf.warning/malformed-url` / `:rf.error/no-such-handler` carry the
      requested URL under a bare `:url` slot (rf2-n1f4rh). These are the
      route-MISS / malformed-URL diagnostics — there is NO matched route, so
      NO `:params` / `:query` schema to consult, yet an unmatched / malformed
      URL is the class most likely to carry secret material (`?token=…`,
      `?code=…`, `#access_token=…`) and `:rf.error/no-such-handler` is a
      production-survivable / off-box-observable error category EP-0015
      requires to fail closed.
    - `:rf.route/navigation-blocked` (the trace, distinct from the dispatched
      event) carries `:requested-url` under a custom slot (rf2-jfaucw).

  This namespace owns the ONE URL-carrier redactor those emit sites route
  their URL slot through — keeping the structured PATH (the reason an app
  error handler / SSR projection needs to branch on) and redacting the
  query-string and `#fragment` carrier VALUES by default. It is the
  no-schema egress analogue of the schema-driven
  `re-frame.routing.navigate/redact-route-error-tags` (rf2-zsm03).

  Internal namespace; the public facade is `re-frame.routing`. Per the
  rf2-2yabr cohesion split: EGRESS-SCRUB seam."
  (:require [clojure.string :as str]
            [re-frame.privacy :as privacy]))

(def ^:private sentinel-str
  "The `:rf/redacted` sentinel as a plain string, for substitution INSIDE a
  URL string (a keyword sentinel cannot live inside a URL)."
  (subs (str privacy/redacted-sentinel) 1))   ;; ":rf/redacted" → "rf/redacted"

(defn redact-url-carriers
  "Project a raw URL string for off-box / log / tool egress on the
  NO-SCHEMA route-miss / blocked-navigation diagnostic path (rf2-n1f4rh /
  rf2-jfaucw): keep the PATH and redact the query-string and `#fragment`
  carrier VALUES.

  An unmatched / malformed / blocked URL has no matched route → no
  `:params` / `:query` schema to path-target, yet it is the URL class most
  likely to carry the secret material EP-0015 fails closed on. We apply the
  blanket carrier policy: redact query/hash VALUES by default.

  - Query KEYS are PRESERVED (they name the shape, not the secret — a
    security dashboard still sees `token` / `code` were present) and each
    VALUE is replaced with the `rf/redacted` sentinel; a value-less flag key
    is left as-is.
  - The whole `#fragment` is redacted (it is opaque — could be a path, an
    OAuth implicit-grant token, anything).
  - A URL with NEITHER query nor fragment rides back verbatim (a bare
    `/admin/users/42` path is not a carrier this policy targets — the path
    rarely carries a secret and apps key error UIs off it).

  Pure / host-symmetric — string surgery only, no percent-decode (a
  malformed URL must not throw here). A non-string input rides back
  unchanged (nil-safe)."
  [url]
  (if-not (string? url)
    url
    (let [hash-idx    (.indexOf #?(:clj ^String url :cljs ^string url) "#")
          has-frag?   (not (neg? hash-idx))
          url-no-frag (if has-frag? (subs url 0 hash-idx) url)
          q-idx       (.indexOf #?(:clj ^String url-no-frag :cljs ^string url-no-frag) "?")
          has-query?  (not (neg? q-idx))]
      (if-not (or has-query? has-frag?)
        url                                              ;; bare path — nothing to scrub
        (let [path  (if has-query? (subs url-no-frag 0 q-idx) url-no-frag)
              query (when has-query? (subs url-no-frag (inc q-idx)))
              query' (when query
                       (->> (str/split query #"&")
                            (map (fn [pair]
                                   (let [eq (.indexOf #?(:clj ^String pair :cljs ^string pair) "=")]
                                     (if (neg? eq)
                                       pair               ;; value-less flag key
                                       (str (subs pair 0 (inc eq)) sentinel-str)))))
                            (str/join "&")))]
          (cond-> path
            has-query? (str "?" query')
            has-frag?  (str "#" sentinel-str)))))))

(defn redact-url-tag
  "Redact the carrier VALUES of the URL under `slot` in a diagnostic
  `tags` map (default `:url`), via `redact-url-carriers`. A no-op when the
  slot is absent. Used by the route-miss telemetry intents
  (`:rf.warning/malformed-url` / `:rf.error/no-such-handler`, `:url` slot)
  and the `:rf.route/navigation-blocked` trace (`:requested-url` slot)
  before they cross the trace bus / Xray / MCP / log egress boundary."
  ([tags] (redact-url-tag tags :url))
  ([tags slot]
   (if (contains? tags slot)
     (update tags slot redact-url-carriers)
     tags)))
