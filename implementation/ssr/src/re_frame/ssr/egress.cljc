(ns re-frame.ssr.egress
  "SSR-owned egress projection (Spec 015) for the ONE always-on record whose
  payload is caller-untrusted by construction: the rejected
  `:rf.server/safe-redirect`.

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
  defensible with this projection in front of it.

  ## Two instruments, and which one goes where

  The DEV diagnostic map's `:location` is scrubbed by the shared carrier
  policy `re-frame.privacy.url/redact-url-tag`, which lives in CORE precisely
  so this artefact can reach it: `implementation/ssr` depends on core alone
  (Spec 006 §Adapter shipping convention), and the only other home for that
  policy — routing, which needs it for its own route-miss `:url` — would have
  meant either dragging the whole route grammar onto every SSR app's classpath
  or late-binding to it and FAILING OPEN on a routing-free SSR host. A
  fail-open scrub on a fail-closed egress boundary is the wrong failure mode
  by construction (rf2-6l2nc).

  The ALWAYS-ON record does not use that scrub at all. It is built from the
  closed allow-list below, for the reasons argued at
  [[safe-redirect-record-slots]].

  Internal namespace; the public facade is `re-frame.ssr`."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; The always-on record projection (rf2-6jqa8 AUDIT-REOPEN)
;; ---------------------------------------------------------------------------
;;
;; WHY A PROJECTION AND NOT A BIGGER SCRUB.
;; `re-frame.privacy.url/redact-url-carriers` is the
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
;;
;; AND A CLOSED SET OF KEYS IS NOT YET A CLOSED SET OF VALUES.
;; The first tightening carried the PARSED `:scheme` and `:host` as strings,
;; on the reasoning that a parsed URL component is structural.  It is not.
;; Parsing says where a substring sat in the grammar; it says nothing about
;; who wrote it, and on this path the answer is always "whoever is probing":
;;
;;   - `s3cr3t-probe-token:payload` takes the non-http(s) arm with the scheme
;;     `"s3cr3t-probe-token"` — a scheme is any `ALPHA *( ALPHA / DIGIT / "+"
;;     / "-" / "." )` (RFC 3986 §3.1), so its content is the attacker's to
;;     choose and its length is unbounded,
;;   - `https://s3cr3t-reset-token.evil.example/` takes the allowlist arm with
;;     that whole host — a DNS name is up to 253 octets of attacker-chosen
;;     labels (RFC 1035 §2.3.4), and it is by construction a name the app did
;;     NOT authorise.
;;
;; Two costs, both borne by the operator rather than the prober.  CONTENT: a
;; sentinel rides out under a key everyone reads as structural.  CARDINALITY:
;; one probe run with a fresh host per request writes unbounded distinct
;; values into a metrics dimension, which is how an observability bill and a
;; dashboard get DoS'd by the very records meant to reveal the DoS.
;;
;; So the always-on record now carries CLASSES, not components.  `:scheme`
;; becomes [[safe-redirect-scheme-class]] — a lookup into the framework's own
;; closed scheme vocabulary, so the prober selects a bucket and never names
;; one — and `:host` is dropped entirely, for the reasons at
;; [[safe-redirect-record-slots]].  Every value the record can carry is now a
;; framework-owned keyword or the frame's own id: bounded in size, bounded in
;; cardinality, and incapable of transporting a byte the caller chose.

(def scheme-classes
  "The framework's own closed scheme vocabulary, as a lookup from the
  lower-cased scheme string to the CLASS KEYWORD the always-on record may
  carry. Exactly the union of `re-frame.ssr.response`'s two closed sets — the
  three schemes the gate rejects outright (`rejected-schemes`) and the two a
  redirect `Location` may legitimately carry (`allowed-schemes`) — pinned to
  that union by a parity test, since a scheme the gate names but this map does
  not would silently degrade to `:other` and cost the operator the very
  distinction the gate drew.

  A LOOKUP, deliberately, and never `(keyword attacker-string)`: constructing
  a keyword from caller input interns the attacker's chosen name into the
  runtime, which is the leak with a longer half-life than the record."
  {"javascript" :javascript
   "data"       :data
   "vbscript"   :vbscript
   "http"       :http
   "https"      :https})

(def ^:private longest-known-scheme-length
  "Length of the longest scheme in [[scheme-classes]]. A scheme longer than
  this cannot be one of them, so [[safe-redirect-scheme-class]] can answer
  `:other` without lower-casing — an oversized scheme is then never copied,
  only measured."
  (apply max (map count (keys scheme-classes))))

(defn safe-redirect-scheme-class
  "Classify a parsed URL scheme into [[scheme-classes]], or `:other`. Returns
  nil when there is no scheme to classify (a relative reference, or an arm
  whose input never parsed), so the slot is omitted rather than carried as
  nil.

  This is what makes the always-on record's discriminator SAFE BY
  CONSTRUCTION rather than merely well-named. The scheme grammar (RFC 3986
  §3.1) admits any `ALPHA *( ALPHA / DIGIT / \"+\" / \"-\" / \".\" )`, so a
  probe of `s3cr3t-probe-token:payload` reaches the non-http(s) arm carrying
  that whole string as its \"parsed component\". Parsing located it in the
  grammar; it did not make it structural. Mapping it into a five-member
  vocabulary plus `:other` means the caller picks a bucket and never spells
  one: the value's content, its length and its cardinality all become the
  framework's, and the operator keeps the distinction that was ever worth
  aggregating — WHICH class of scheme is being probed.

  Case is folded first, because schemes are case-insensitive (RFC 3986 §3.1)
  and a prober alternating case would otherwise fragment one spike across
  buckets — an evasion that costs the attacker nothing and costs the operator
  the signal."
  [scheme]
  (when (string? scheme)
    (if (<= (count scheme) longest-known-scheme-length)
      (get scheme-classes (str/lower-case scheme) :other)
      :other)))

(def safe-redirect-record-slots
  "The CLOSED set of tag slots the ALWAYS-ON `:rf.error/safe-redirect-*`
  record may carry off-box, over and above the `:error` / `:time` the
  union-record helper assoc's.

  Every member is framework-owned — a value this runtime chose, not one the
  caller supplied and not one parsed out of what the caller supplied:

    `:frame`         the frame id — attribution, so a multi-tenant host knows
                     which app was probed.
    `:recovery`      fixed `:no-recovery`.
    `:reason`        a closed framework keyword naming which gate arm fired.
    `:scheme-class`  [[safe-redirect-scheme-class]] — the probe class, one of
                     `:javascript` / `:data` / `:vbscript` / `:http` /
                     `:https` / `:other`.

  Deliberately ABSENT, each for its own reason:

    `:location`   the caller's URL, in any form, scrubbed or not.
    `:allowlist`  the application's own security configuration — unbounded
                  policy data whose contents hand a reader the exact boundary
                  being probed, and which `:reason :not-in-allowlist` already
                  discriminates without disclosing.
    `:scheme`     the raw parsed scheme, superseded by `:scheme-class`.
    `:host`       the raw parsed host, dropped outright.

  `:host` is the one worth arguing, because it reads like the most useful
  thing here — \"which target?\". Three facts settle it. It is a name the app
  did NOT authorise, on EVERY arm that carries it (that is what
  `:relative-only-violation` and `:not-in-allowlist` mean), so there is no arm
  on which it is trusted. The framework already refused the redirect, so no
  defensive action depends on reading it back — there is nothing left to
  block. And it is the last unbounded caller-authored string in the record:
  up to 253 octets of attacker-chosen labels (RFC 1035 §2.3.4) that can carry
  a sentinel outright and can be varied per request to write unbounded
  distinct values into a metrics dimension. Weighed against a signal the
  `:reason` already carries, EP-0015's fail-closed default decides it. The
  dev trace keeps `:host` and the scrubbed `:location` for the operator
  standing at their own process."
  #{:frame :recovery :reason :scheme-class})

(def ^:private record-slot-derivations
  "For each slot in [[safe-redirect-record-slots]], the DIAGNOSTIC tag it
  derives from and the fn that derives it. Two maps rather than one because
  the record's vocabulary is deliberately NOT the diagnostics' — `:scheme-class`
  is a classification of the diagnostics' `:scheme`, not a copy of it, and a
  name that differs is the honest way to say so to whoever reads the sink.

  A slot with no entry here yields nothing, so the fail-closed direction holds
  even against a half-finished edit."
  {:frame        [:frame    identity]
   :recovery     [:recovery identity]
   :reason       [:reason   identity]
   :scheme-class [:scheme   safe-redirect-scheme-class]})

(defn safe-redirect-record-tags
  "Project the safe-redirect DIAGNOSTIC tag map down to the closed structural
  map the always-on error axis may ship off-box: [[safe-redirect-record-slots]]
  only, each slot derived through [[record-slot-derivations]].

  Applied by `re-frame.ssr.response`'s `dispatch-safe-redirect-record!` — the
  one function that reaches the `:error-emit/dispatch-error-record` hook — so
  the projection is inseparable from the egress rather than being a discipline
  each of the eight rejection arms has to remember.

  Note the direction: the result is BUILT FROM the closed slot set rather than
  filtered down to it, so an unrecognised tag has no path into the record even
  in principle. A slot whose derivation yields nil is omitted rather than
  carried as nil, which is why the per-arm key sets differ — each arm names
  exactly the discriminators it actually had.

  The result is intentionally SMALL, and every value in it is a framework-owned
  keyword or the frame's own id. That is the property to preserve when editing:
  not merely that the KEYS are enumerated, but that no VALUE is a byte the
  caller chose. An operator keeps the probe class, the arm that fired, the
  frame and — by counting the records — the rate, which is what turns one bad
  target into visible probing. If richer local diagnosis is wanted it is
  already on the dev trace beside this; derive a further view deliberately
  rather than widening what a `-Dre-frame.debug=false` build sends to Sentry."
  [tags]
  (into {}
        (keep (fn [record-slot]
                (let [[source-slot derive-value]
                      (record-slot-derivations record-slot)]
                  (when-some [slot-value (some-> (get tags source-slot)
                                                 derive-value)]
                    [record-slot slot-value]))))
        safe-redirect-record-slots))
