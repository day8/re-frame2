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
