(ns re-frame.ssr.egress
  "SSR-owned URL-carrier egress scrub (Spec 015) for the ONE diagnostic slot
  the marks chokepoint cannot reach: `:rf.server/safe-redirect`'s
  `:location`.

  Error records are egress surfaces and EP-0015 requires their off-box
  defaults to FAIL CLOSED. The framework's marks chokepoint
  (`re-frame.classification/project-trace-event`) projects the KNOWN slots —
  `:rf.fx/args` against fx marks, the dispatched-event vector against event
  marks — and `project-egress` projects tree slots against the FRAME'S
  classification registry. Neither reaches a bare `:location` string on a
  custom tag slot.

  And `:location` is the slot that most needs reaching. It is BY
  CONSTRUCTION caller-untrusted — that is the entire reason
  `:rf.server/safe-redirect` exists as the sibling of the caller-trusted
  `:rf.server/redirect` — so a rejected target routinely looks like
  `?next=https://evil.example.com/cb?token=…#access_token=…`. rf2-6jqa8
  promoted those rejections onto the always-on error axis so a security team
  can SEE open-redirect probing in production; that promotion is only
  defensible with the scrub applied first.

  ## Why this duplicates `re-frame.routing.egress`

  `redact-url-carriers` here is the same Spec 015 blanket carrier policy
  `re-frame.routing.egress/redact-url-carriers` applies to the route-miss
  `:url` slot (rf2-ov56u), deliberately spelled the same way so the eventual
  consolidation is a move rather than a rewrite. It is duplicated rather
  than shared because the alternatives are worse, not because nobody looked:

    - `implementation/ssr` depends on `core` ALONE (Spec 006 §Adapter
      shipping convention). Routing is a TEST-only dep here; a production
      `:require` on it would drag the whole route grammar onto the classpath
      of every SSR app that registers no routes.
    - A late-bind hook to routing's copy would FAIL OPEN in exactly the
      common case — a routing-free SSR host — and a fail-open scrub on a
      fail-closed egress boundary is the wrong failure mode by construction.

  The right home is `core`, next to `re-frame.privacy`. rf2-6l2nc carries
  that consolidation; until it lands, two artefacts own one policy and this
  docstring is the pointer between them.

  Internal namespace; the public facade is `re-frame.ssr`."
  (:require [clojure.string :as str]
            [re-frame.privacy :as privacy]))

(def ^:private sentinel-str
  "The `:rf/redacted` sentinel as a plain string, for substitution INSIDE a
  URL string (a keyword sentinel cannot live inside a URL)."
  (subs (str privacy/redacted-sentinel) 1))   ;; ":rf/redacted" → "rf/redacted"

(defn redact-url-carriers
  "Project a raw URL string for off-box egress on the no-schema
  rejected-redirect diagnostic path: keep the structured path and redact the
  query-string and `#fragment` carrier VALUES.

  A rejected redirect target has no matched route and no schema to
  path-target, yet it is the URL class most likely to carry secret material.
  Apply the blanket carrier policy: redact query / hash VALUES by default.

  - Query KEYS are PRESERVED (they name the shape, not the secret — a
    security dashboard still sees `token` / `code` were present) and each
    VALUE is replaced with the `rf/redacted` sentinel; a value-less flag key
    is left as-is.
  - The whole `#fragment` is redacted (it is opaque — could be a path, an
    OAuth implicit-grant token, anything).
  - A URL with NEITHER query nor fragment rides back verbatim. The scheme
    and host are deliberately kept: on this path they ARE the security
    signal (`javascript:`, `evil.example.com`), and neither can carry a
    query-string carrier.

  Pure / host-symmetric — string surgery only, no percent-decode (a
  malformed URL must not throw here). A non-string input rides back
  unchanged (nil-safe), which is what makes it total over the `:location`
  slot: `safe-redirect-fx` reaches this scrub for non-string and nil
  locations too, via the parse-failure arm."
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
  map (default `:location`), via [[redact-url-carriers]]. A no-op when the
  slot is absent.

  Applied by `re-frame.ssr.response`'s `safe-redirect-tags` BEFORE the tag
  map reaches EITHER error axis, so both the DEV trace and (historically) the
  always-on record carried the same already-scrubbed value and could not
  disagree about the same rejection.

  Since rf2-6jqa8's egress tightening the always-on record carries no URL at
  all — see [[project-safe-redirect-record]] — so this scrub's remaining
  consumer on THIS path is the dev trace. It stays because the dev trace is
  still an egress surface in the weaker sense (a local operator's screen, a
  captured test fixture), and because a scrubbed diagnostic is the honest
  input to a projection."
  ([tags] (redact-url-tag tags :location))
  ([tags slot]
   (if (contains? tags slot)
     (update tags slot redact-url-carriers)
     tags)))

;; ---------------------------------------------------------------------------
;; The always-on record projection (rf2-6jqa8 AUDIT-REOPEN)
;; ---------------------------------------------------------------------------
;;
;; WHY A PROJECTION AND NOT A BIGGER SCRUB.  [[redact-url-carriers]] is the
;; Spec 015 blanket CARRIER policy: it redacts query / fragment values and
;; keeps everything else, which is exactly right over the app's OWN URL space
;; (rf2-ov56u's route-miss `:url`, where the path is a route the app authored
;; and the host is the app's own).  A rejected safe-redirect target is not
;; that.  It is an ARBITRARY FOREIGN URL chosen by whoever is probing, so
;; every component is attacker-authored and "keep everything except the
;; carriers" inverts the burden of proof.  Concretely, the carrier policy does
;; string surgery only after the first `?` or `#`, so it never reached:
;;
;;   - the USERINFO component — `https://alice:pw@host/…` shipped credentials
;;     whole (RFC 3986 §3.2.1; OpenTelemetry's URL conventions say user /
;;     password MUST NOT be recorded),
;;   - the PATH — a password-reset token in a path segment is the canonical
;;     opaque path-borne secret, and the policy keeps the path DELIBERATELY,
;;   - a VALUE-LESS query key — preserved by design, because a key names the
;;     shape rather than the secret; true of the app's own URL space, false
;;     when the attacker picks the key.
;;
;; Widening the scrub to cover those would leave nothing but the scheme and
;; host anyway, and would still be a DENY-list — one more URL component away
;; from a leak.  So the record is built the other way round, as an ALLOW-list
;; over parsed structural components.  That is fail-closed by construction: a
;; slot added to any emit arm's tag map does not reach a shipper unless
;; someone edits [[safe-redirect-record-slots]] and reddens the test that pins
;; it.
;;
;; This is the EP-0015 diagnostics/egress relationship in its ordinary form —
;; the dev trace keeps the rich (scrubbed) map, and the production record is a
;; strict projection of it, not a copy.

(def safe-redirect-record-slots
  "The CLOSED set of tag slots the ALWAYS-ON `:rf.error/safe-redirect-*`
  record may carry off-box, over and above the `:error` / `:time` the
  union-record helper assoc's.

  Every member is either framework-owned or a PARSED URL COMPONENT — never a
  URL, never caller-supplied policy data:

    `:frame`     the frame id — attribution, so a multi-tenant host knows
                 which app was probed.
    `:recovery`  fixed `:no-recovery`.
    `:reason`    a closed framework keyword naming which gate arm fired.
    `:scheme`    the parsed, lower-cased scheme (`\"javascript\"`) — the
                 aggregatable probe class.
    `:host`      the parsed, lower-cased host (`\"evil.example.com\"`) — the
                 target, which an operator can block or recognise as their
                 own misconfiguration.

  Deliberately ABSENT: `:location` (the attacker's URL, in any form, scrubbed
  or not) and `:allowlist` (the application's own security configuration —
  unbounded policy data whose contents hand a reader the exact boundary being
  probed, and which `:reason :not-in-allowlist` already discriminates without
  disclosing)."
  #{:frame :recovery :reason :scheme :host})

(def ^:private normalised-slots
  "The projected slots that are PARSED URL COMPONENTS, and so must be
  case-normalised to aggregate. `:frame` / `:recovery` / `:reason` are
  framework-owned values that already have one spelling each."
  #{:scheme :host})

(defn- normalise-component
  "Lower-case a parsed URL component so it AGGREGATES. Schemes are
  case-insensitive (RFC 3986 §3.1) and DNS labels likewise (RFC 1035 §2.3.3),
  so without this a prober alternating case fragments one spike across many
  dashboard buckets — an evasion that costs the attacker nothing and costs
  the operator the signal. Non-strings ride back unchanged."
  [v]
  (if (string? v) (str/lower-case v) v))

(defn safe-redirect-record-tags
  "Project the safe-redirect DIAGNOSTIC tag map down to the closed structural
  map the always-on error axis may ship off-box: [[safe-redirect-record-slots]]
  only, with `:scheme` / `:host` normalised for aggregation.

  Applied by `re-frame.ssr.response`'s `dispatch-safe-redirect-record!` — the
  one function that reaches the `:error-emit/dispatch-error-record` hook — so
  the projection is inseparable from the egress rather than being a discipline
  each of the eight rejection arms has to remember.

  Note the direction: the result is BUILT FROM the closed slot set rather than
  filtered down to it, so an unrecognised tag has no path into the record even
  in principle. A slot whose component did not parse is omitted rather than
  carried as nil, which is why the per-arm key sets differ — each arm names
  exactly the discriminators it actually had.

  The result is intentionally SMALL. Losing the exact URL in production is a
  good trade: an operator keeps an aggregatable probe class, the target host,
  the arm that fired and the frame, without accepting credentials, opaque path
  material, arbitrary attacker-chosen query keys or unbounded policy data. If
  richer local diagnosis is wanted, it is already on the dev trace beside
  this — derive a further view deliberately rather than widening what a
  `-Dre-frame.debug=false` build sends to Sentry."
  [tags]
  (into {}
        (keep (fn [slot]
                (when-some [v (get tags slot)]
                  [slot (if (normalised-slots slot)
                          (normalise-component v)
                          v)])))
        safe-redirect-record-slots))
