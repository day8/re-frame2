(ns re-frame.privacy.url
  "The ONE Spec 015 URL-carrier egress scrub: the redactor every diagnostic
  that carries a raw URL on a CUSTOM tag slot routes that slot through.

  ## Why a URL slot needs its own redactor

  Trace / tool / log / epoch / error records are egress surfaces and EP-0015
  requires their off-box defaults to FAIL CLOSED. The framework's marks
  chokepoint (`re-frame.classification/project-trace-event` via
  `re-frame.trace/build-event`) already projects the KNOWN slots —
  `:rf.fx/args` against fx marks, the dispatched-event vector against event
  marks — and `re-frame.projection/project-egress` projects tree slots against
  the FRAME'S classification registry. Neither reaches a bare URL string on a
  diagnostic's own custom tag slot.

  And `project-egress` is the wrong instrument on this path rather than the
  costlier one. The diagnostics that carry a raw URL are exactly the ones with
  NO matched route and therefore no `:params` / `:query` schema to
  path-target — a route miss, a malformed URL, a blocked navigation, a
  rejected redirect. Under a live frame no declared path covers the slot, so
  `project-egress` would ride it through untouched; under a frameless one it
  would blanket-redact the structured path those diagnostics deliberately
  keep. What the path needs is a blanket CARRIER policy, applied at the emit
  site by the caller, naming its own slot.

  ## Why it lives in core

  Two artefacts need this policy on egress boundaries that must fail closed,
  and they are siblings rather than dependents: `re-frame.routing` (the
  route-miss `:url` and the blocked-navigation `:requested-url`, rf2-ov56u)
  and `re-frame.ssr` (the rejected `:rf.server/safe-redirect` `:location`,
  rf2-6jqa8). Neither may reach the other:

    - `implementation/ssr` depends on `core` ALONE (Spec 006 §Adapter shipping
      convention). A production `:require` on routing would drag the whole
      route grammar onto the classpath of every SSR app that registers no
      routes.
    - A LATE-BIND hook to a routing-owned copy would FAIL OPEN in exactly the
      common case — a routing-free SSR host — and a fail-open scrub on a
      fail-closed egress boundary is the wrong failure mode by construction.
      No routing artefact, no scrub, secrets on the wire.

  Core is the one artefact both already depend on, so a shared implementation
  needs neither a new dependency edge nor a late-bind. That is the whole point
  of this home: the scrub is UNCONDITIONAL, not contingent on which optional
  artefacts an app happens to load. (rf2-6l2nc consolidated the two copies
  that stood here before.)

  ## What this policy is NOT

  It is a carrier DENY-list — redact the query values and the fragment, keep
  everything else — and that is right over the app's OWN URL space, where the
  path is a route the app authored, the host is the app's own, and a query KEY
  names the shape rather than the secret.

  It is NOT a fail-closed projection of an ARBITRARY FOREIGN URL. It does
  string surgery only after the first `?` or `#`, so it never reaches userinfo
  (`https://alice:pw@host/…`), a path-borne reset token, or an attacker-chosen
  value-less query key. A record that ships an attacker-authored URL off-box
  needs an ALLOW-list over parsed structural components instead — see
  `re-frame.ssr.egress/safe-redirect-record-slots`, which is built FROM a
  closed slot set rather than filtered down to one.

  Internal namespace; nothing here is published from the `re-frame.core`
  facade."
  (:require [clojure.string :as str]
            [re-frame.privacy :as privacy]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private sentinel-str
  "The `:rf/redacted` sentinel as a plain string, for substitution INSIDE a
  URL string (a keyword sentinel cannot live inside a URL)."
  (subs (str privacy/redacted-sentinel) 1))   ;; ":rf/redacted" → "rf/redacted"

(defn redact-url-carriers
  "Project a raw URL string for off-box / log / tool egress: keep the
  structured path and redact the query-string and `#fragment` carrier VALUES.

  - Query KEYS are PRESERVED (they name the shape, not the secret — a security
    dashboard still sees `token` / `code` were present) and each VALUE is
    replaced with the `rf/redacted` sentinel; a value-less flag key is left
    as-is.
  - The whole `#fragment` is redacted (it is opaque — could be a path, an
    OAuth implicit-grant token, anything).
  - A URL with NEITHER query nor fragment rides back verbatim. A bare
    `/admin/users/42` path is not a carrier this policy targets — the path
    rarely carries a secret and apps key error UIs off it. The scheme and host
    are kept for the same reason, and on the rejected-redirect path they ARE
    the security signal (`javascript:`, `evil.example.com`); neither can carry
    a query-string carrier.

  Pure / host-symmetric — string surgery only, no percent-decode (a malformed
  URL must not throw here). A non-string input rides back unchanged (nil-safe),
  which is what makes it total over a slot like SSR's `:location`, reached for
  every rejection arm including the parse-failure one."
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
        (let [path   (if has-query? (subs url-no-frag 0 q-idx) url-no-frag)
              query  (when has-query? (subs url-no-frag (inc q-idx)))
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
  "Redact the carrier VALUES of the URL under `slot` in a diagnostic `tags`
  map, via [[redact-url-carriers]]. A no-op when the slot is absent.

  `slot` is REQUIRED, deliberately. The callers spell it differently — `:url`
  on the route-miss / malformed-URL telemetry, `:requested-url` on the blocked-
  navigation and entry-denied traces, `:location` on the rejected safe-redirect
  — so any default this fn could pick would be silently wrong for someone, and
  the failure mode of a wrong slot is a no-op that ships the URL. Naming the
  slot at the call site is one word, and it is the word that makes the scrub
  visible where the tag map is built."
  [tags slot]
  (if (contains? tags slot)
    (update tags slot redact-url-carriers)
    tags))
