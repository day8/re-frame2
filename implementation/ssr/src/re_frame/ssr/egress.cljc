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
  map reaches EITHER error axis, so the always-on production record and the
  dev trace carry the same already-scrubbed value and cannot disagree about
  the same rejection."
  ([tags] (redact-url-tag tags :location))
  ([tags slot]
   (if (contains? tags slot)
     (update tags slot redact-url-carriers)
     tags)))
