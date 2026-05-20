# O-18. Security + operational logging sweep on the observability interceptor surface

> **Type B** (semantic flag — every hit needs operator judgement). The agent sweeps the codebase for hand-rolled observability interceptors (audit logging, telemetry forwarders, error projectors, post-event recorders) registered against the v1 surfaces ([M-13](README.md#m-13-reg-event-error-handler-is-dropped--error-policy-is-per-frame-on-error) `reg-event-error-handler`, [M-17](README.md#m-17-reg-global-interceptor--clear-global-interceptor-removed--use-frame-level-interceptors) `reg-global-interceptor`, [M-19](README.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in) `add-post-event-callback`, bespoke `reg-event-fx` wrappers that emit telemetry from inside handler bodies, ajax-cljs response-side `:interceptors`), classifies each by **whether the payload it ships off-box may carry sensitive data** and **whether the slot it walks may carry oversize values**, and produces a per-site rewrite proposal that lands the interceptor on the canonical v2 surface ([`register-listener!`](../../spec/009-Instrumentation.md#the-listener-api), `register-epoch-listener!`, or per-frame `:interceptors`) with the framework's sensitive / large defense composed by default.

> **Cross-references.** Required-rule [M-13](README.md#m-13-reg-event-error-handler-is-dropped--error-policy-is-per-frame-on-error) drops `reg-event-error-handler`; this rule covers the broader sweep that catches observers M-13 misses. Required-rule [M-17](README.md#m-17-reg-global-interceptor--clear-global-interceptor-removed--use-frame-level-interceptors) drops `reg-global-interceptor`; this rule sweeps the audit-shaped subset of M-17 hits to the trace surface rather than the per-frame `:interceptors` vector. The [API.md §wire-elision walker](../../spec/API.md#elide-wire-value-the-wire-boundary-walker) is the framework primitive every off-box forwarder this rule produces routes through; [Security.md §Privacy / secret handling](../../spec/Security.md#privacy--secret-handling) is the threat-model context.

---

## Why this is its own rule

[M-13](README.md#m-13-reg-event-error-handler-is-dropped--error-policy-is-per-frame-on-error) and [M-17](README.md#m-17-reg-global-interceptor--clear-global-interceptor-removed--use-frame-level-interceptors) hand the operator a per-call-site decision — "this `reg-event-error-handler` was an observer; convert to `register-listener!`." That's the right shape for the *mechanical* part. What M-13 / M-17 leave on the floor is the **security and operational consequence** of the conversion: an audit-logger that worked in v1 by hooking the dispatch envelope sees the whole event vector, which may carry passwords / tokens / PII; the v2-canonical `register-listener!` listener receives the same event under `:tags :event-v` and ships it to wherever the listener's body forwards (Sentry, an external SIEM, a local log file). Per [Security.md §Privacy / secret handling](../../spec/Security.md#privacy--secret-handling), the framework defends with `:sensitive?` declarations + the wire-elision walker, but the defense is **declarative** — if the v1 site never declared its observability surface, the v2 port silently leaks the same payloads to a wider audience.

This rule is the **dedicated sweep** that turns the post-M-13 / post-M-17 observer set into a v2-canonical set with privacy + oversize defenses composed at every egress. It has four sections:

1. [§Discovery](#1-discovery) — how to find every observability site that needs review.
2. [§Sensitive-key checklist](#2-sensitive-key-checklist) — the closed set of payload-key substrings that signal sensitive content, plus the recursive-walk discipline.
3. [§Size-cap pattern + register-listener! for dropped count](#3-size-cap-pattern--register-trace-listener-for-dropped-count) — how to bound listener egress and surface a dropped-count signal so the operator sees what was filtered.
4. [§Reference mediation interceptor](#4-reference-mediation-interceptor) — the canonical "redact + size-cap + forward" interceptor body that every site is rewritten to.

## 1. Discovery

The agent sweeps the codebase for the following patterns. Each hit is one observability site; classify the site by **what it does with the event** (logs locally / forwards off-box / mutates app-db / something else) and **what payload it walks** (full event vector / specific keys / `app-db` slice / failure response).

```bash
# 1. Direct v1 observer surfaces (M-13 / M-17 / older add-post-event-callback)
rg -n 'reg-event-error-handler|reg-global-interceptor|add-post-event-callback|remove-post-event-callback' .

# 2. Bespoke per-handler telemetry that emits from inside reg-event-fx bodies
rg -n '\(reg-event-fx[^)]*\)' . -A 20 | rg -n '(log|console|track|telemetry|sentry|honeybadger|rollbar|datadog|analytics|posthog|mixpanel|segment)' -B 5

# 3. Manually-constructed interceptors via ->interceptor that fire side-effects in :before/:after
rg -n '->interceptor' . -A 10 | rg -n '(:before|:after).*\b(log|console|track|telemetry|sentry|fetch|XMLHttpRequest|XhrIo|js/fetch)' -B 5

# 4. ajax-cljs / cljs-ajax response-side :interceptors (often telemetry-shaped)
rg -n ':interceptors\b' . -A 15 | rg -n '(:response|:on-response)' -B 5

# 5. Post-handler dispatch from inside fx for the purpose of recording (recorder pattern)
rg -n 'fn \[\{:keys \[.*event.*\]\}.*\] \{:fx.*\[:.*record' .
```

The agent presents every hit to the operator with a one-line classification:

- **observer (off-box egress)** — body forwards a payload over an HTTP / SDK boundary (Sentry, Honeybadger, Rollbar, Datadog, custom telemetry endpoint). **High-risk for sensitive data.** Apply the full pattern: sensitive redaction + size cap + dropped-count signal.
- **observer (local log)** — body writes to console / local log file / dev panel. **Lower-risk** (local trust boundary) but still benefits from the size cap to avoid log bloat; sensitive redaction recommended for dev environments shared with other operators.
- **behaviour-modifying interceptor** — body mutates `app-db` / dispatches an event / changes the effect map. **Not an observability site** — port to per-frame `:interceptors` per [M-17](README.md#m-17-reg-global-interceptor--clear-global-interceptor-removed--use-frame-level-interceptors), not to `register-listener!`. The two patterns are structurally different: observers must not change runtime behaviour; behaviour-modifying interceptors must.
- **misclassified telemetry-from-handler-body** — the v1 author inlined telemetry inside a `reg-event-fx` body because the v1 surface didn't have a cross-cutting trace listener. **Lift to the trace surface.** The handler body returns the domain effect map; a `register-listener!` listener picks up the trace event and forwards from there. Removing the inline telemetry shrinks every handler that has it.

The classification drives the rewrite path. Sites flagged "observer (off-box egress)" or "observer (local log)" are the substantive payload of this rule.

## 2. Sensitive-key checklist

re-frame2's framework defense for sensitive data is the `:sensitive?` declaration (per [009 §Privacy / sensitive data in traces](../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)) — schema-driven via `{:sensitive? true}` on Malli slots and handler-scoped via `:sensitive? true` in registration metadata. The framework redacts at emit-site (always-on substrates) and at off-box egress (wire-elision walker). **But the framework only redacts what's declared.** v1 observability sites usually pre-date the declaration discipline, so the rewrite must inspect each site's payload and either (a) propose new `:sensitive?` declarations against the schemas / handlers the site walks, or (b) compose an explicit drop in the listener body for the keys it must filter even when undeclared.

### Closed set of sensitive-key substring matches

The agent applies this closed set against every payload key the observability site walks. A key whose **lower-cased name contains any of these substrings** is treated as sensitive by default — propose `:sensitive? true` on the schema slot if one exists, and compose an explicit drop in the listener body regardless:

| Substring | Why |
|---|---|
| `password` | Account credential. Variants: `:password`, `:user/password`, `:new-password`, `:current-password`, `:password-confirmation`. |
| `token` | Auth token / session token / refresh token / API token. Variants: `:token`, `:auth-token`, `:access-token`, `:refresh-token`, `:csrf-token`, `:bearer-token`, `:reset-token`. |
| `secret` | API secret / signing secret / webhook secret. Variants: `:secret`, `:client-secret`, `:api-secret`, `:webhook-secret`, `:signing-secret`. |
| `jwt` | JSON Web Token (auth credential carrying claims). Variants: `:jwt`, `:jwt-token`, `:auth.jwt/value`. |
| `sudo` | Elevated-privilege session / sudo-mode credential. Variants: `:sudo`, `:sudo-token`, `:auth.sudo/expires`. |
| `auth-uri` | OAuth redirect URI / auth-flow URI carrying a code / state parameter (the URI itself is sensitive when it contains an `?code=...&state=...` query). Variants: `:auth-uri`, `:auth-flow/uri`, `:oauth/redirect-uri`. |
| `user-id` | User identifier — sensitive in privacy-regulated contexts (GDPR, HIPAA, SOC2) where PII linkage is restricted. Variants: `:user-id`, `:user/id`, `:account-id`. |
| `email` | Personal email address — PII. Variants: `:email`, `:user/email`, `:contact-email`. |
| `phone` | Personal phone number — PII. Variants: `:phone`, `:mobile`, `:phone-number`. |
| `ssn` | Social-security / national-id — PII / sensitive-PII. Variants: `:ssn`, `:national-id`, `:tax-id`. |
| `cc` / `card` | Credit-card number — PCI-regulated. Variants: `:cc-number`, `:card-number`, `:card-cvv`, `:card-exp`. |

The list is **the floor, not the ceiling.** App-specific sensitive keys (HIPAA-regulated medical fields, partner-API secrets, internal session ids) require operator review per codebase. The agent surfaces the floor list and asks: "what app-specific keys also signal sensitive?" — every additional key joins the closed set for this rewrite pass.

The match is **case-insensitive substring** (`(re-find #"(?i)password|token|...|cc|card" (name k))`) — the agent walks every keyword in the observed payload and drops every key whose name matches. Namespace prefix is ignored (`:user/password` and `:auth.sudo/expires` both match).

### Recursive walk discipline

Observability payloads are typically nested: an event vector carries a payload map carrying a `:user` sub-map carrying `:credentials` carrying `:password`. The agent's rewrite MUST walk the payload **recursively** — top-level redaction misses nested credentials. The framework's wire-elision walker ([API.md §wire-elision walker](../../spec/API.md#elide-wire-value-the-wire-boundary-walker)) does this correctly when given `:sensitive?` declarations from the registry; the explicit-drop fallback (for undeclared sensitive keys) MUST also recurse. The canonical body:

```clojure
(defn redact-sensitive
  "Walk v recursively, replacing every map-entry value whose key matches the
   sensitive-key floor with :rf/redacted. Returns the redacted value."
  [v]
  (let [sensitive? (fn [k]
                     (and (or (keyword? k) (string? k))
                          (re-find #"(?i)password|token|secret|jwt|sudo|auth-uri|user-id|email|phone|ssn|cc|card"
                                   (name k))))]
    (clojure.walk/postwalk
      (fn [node]
        (if (map? node)
          (reduce-kv (fn [m k vv] (assoc m k (if (sensitive? k) :rf/redacted vv))) {} node)
          node))
      v)))
```

`postwalk` ensures the walker visits leaves before parents — every nested map is rebuilt with redaction applied at its own level. **`:rf/redacted` is the canonical sensitive-substitution sentinel** (per [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned)); listener bodies that produce a different sentinel ("REDACTED", `nil`, the empty string) defeat downstream consumers that filter on `:rf/redacted` and MUST be normalised in this pass.

The agent SHOULD prefer the framework wire-elision walker over a hand-rolled `redact-sensitive` whenever the payload walks a value that has registered `:sensitive?` schema declarations — the walker reads the registry, applies sensitive drop, AND composes the size-cap from §3 in one pass. Use the hand-rolled `redact-sensitive` only as a fallback for sites whose payload is **entirely undeclared** (transient telemetry that never lands in `app-db`, headers from an external SDK, raw failure responses with no schema). The reference mediation interceptor in §4 shows both paths composed.

### Schema-declaration proposal as a follow-on

For every sensitive key the agent finds in an observability payload that **does** appear in a registered schema, the agent proposes a follow-on `{:sensitive? true}` schema annotation:

```clojure
;; Before
[:map
 [:email :string]
 [:auth/token :string]]

;; After (follow-on, per O-3 modernisation)
[:map
 [:email      {:sensitive? true} :string]
 [:auth/token {:sensitive? true} :string]]
```

The schema-declaration path is **strictly better** than per-listener explicit drops: the declaration covers every consumer (trace listeners, error monitors, MCP servers, hosted dashboards) uniformly, and the framework's always-on error / event substrates honour it before fan-out (per [009 §Privacy / sensitive data in traces](../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces)). The agent flags every schema-eligible key in the report and surfaces "consider adding `{:sensitive? true}` on `<schema-id>` slot `<path>`" as a separate operator decision per slot — the rewrite of the observability site is independent of the schema annotation, but the schema annotation eliminates the need for the explicit drop on every future consumer.

## 3. Size-cap pattern + `register-listener!` for dropped count

Observability payloads can be unboundedly large — a `:db/state-loaded` event carrying the full `app-db` slice, an HTTP failure carrying a 5 MB response body, a `:render/completed` event carrying every rendered view's props. Listener bodies that forward such payloads to off-box destinations (Sentry / log shippers / hosted dashboards) cause memory pressure, network bloat, and rate-limited destinations rejecting batches. The framework defends with the `:large?` schema declaration + the wire-elision walker (per [009 §Size elision in traces](../../spec/009-Instrumentation.md#size-elision-in-traces)); this rule's rewrite composes the defense at every listener body produced.

### The cap pattern

Every listener body that walks a payload bounded by user input or by app-db size MUST apply a size cap. The cap is **per-payload bytes**, applied after sensitive redaction:

```clojure
(defn cap-or-elide
  "Returns [bounded-value dropped-count]. Walks v through the wire-elision walker
   with a size threshold; returns the elided value plus a count of slots the walker
   dropped (sensitive + large combined)."
  [v {:keys [threshold-bytes frame]
      :or   {threshold-bytes 16384
             frame           :rf/default}}]
  (let [opts    {:rf.size/threshold-bytes threshold-bytes
                 :rf.size/include-sensitive? false
                 :rf.size/include-large?     false
                 :frame                      frame}
        elided  (rf/elide-wire-value v opts)
        ;; Count :rf.size/large-elided markers and :rf/redacted sentinels in elided
        dropped (->> (tree-seq coll? seq elided)
                     (filter #(or (= :rf/redacted %)
                                  (and (map? %) (= :rf.size/large-elided (:rf.size/marker %)))))
                     count)]
    [elided dropped]))
```

`16384` bytes is the framework default per [API.md §wire-elision walker](../../spec/API.md#elide-wire-value-the-wire-boundary-walker) configure key; the operator picks a per-listener cap that matches the destination's payload budget (Sentry's 100KB event-payload soft cap suggests ~32-64KB per listener; a self-hosted log file can be larger). The default is the right floor for production telemetry; specialised listeners (a dev-only `console.log` panel reading the trace buffer) can opt for a higher cap.

### Surfacing the dropped count via `register-listener!`

The cap silently elides — but silent elision is the wrong default for operational observability. Operators need to see that the listener filtered SOMETHING — otherwise a misconfigured schema (forgot `{:sensitive? true}` on a new field) leads to "the dashboard shows nothing" with no diagnostic signal. The pattern is to **emit a counter trace event** every time the listener drops slots:

```clojure
(rf/register-listener! :my-app/audit-forwarder
  (fn [trace-event]
    (when (and (#{:event/dispatched :event/handler-completed} (:operation trace-event))
               (not (:sensitive? trace-event)))                      ;; default-drop sensitive cascades
      (let [event-v   (-> trace-event :tags :event-v)
            [bounded
             dropped] (cap-or-elide event-v
                                    {:threshold-bytes 32768
                                     :frame           (:frame trace-event)})]
        (when (pos? dropped)
          ;; Per-batch counter: operator sees how often the cap fires
          (rf/dispatch [:audit/dropped-counter-inc {:dropped dropped
                                                    :operation (:operation trace-event)}]))
        (sentry/capture-message
          {:message "audit event"
           :extra   {:event/operation (:operation trace-event)
                     :event/payload   bounded
                     :event/dropped   dropped}})))))
```

Two diagnostic signals:

1. **The `:event/dropped` slot on every forwarded payload** — the operator opening the destination dashboard sees per-event how many slots were filtered.
2. **The `[:audit/dropped-counter-inc ...]` dispatch into the runtime** — accumulates a counter the operator can query via `(rf/subscribe [:audit/dropped-counter])` for a continuous "how often is the cap firing" view.

The two together let the operator distinguish "the cap is a healthy backstop firing twice a day" from "the cap is firing on every event because a misconfigured schema is leaking the whole `app-db`." Both signals MUST land on the rewrite — silent elision is the failure mode this rule defends against.

### Composition with the framework default

The framework already drops `:sensitive? true` events on the off-box-forwarder default (per [009 §Privacy / sensitive data in traces](../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces) — "Framework-published listener integrations MUST default to suppressing `:sensitive? true` events"). The `(when-not (:sensitive? trace-event) ...)` guard in the listener body composes the default — the listener body never even sees a sensitive-scope cascade. The explicit `cap-or-elide` walk catches the **rest** of the payload — fields whose own schema didn't carry `:sensitive?` but match the floor checklist from §2, plus the size-cap.

## 4. Reference mediation interceptor

The canonical "redact + size-cap + forward + drop-count" body — the agent ports every classified-as-observer hit to one of these two shapes depending on what the v1 site did:

### Shape A — `register-listener!` for cross-frame observers (the M-13 / M-17 cross-frame-observer replacement)

```clojure
(ns my-app.observability
  (:require [re-frame.core :as rf]
            [clojure.walk]
            [sentry.core :as sentry]))

;; --- Local helpers (lift to a shared utility if used across multiple listeners) ---

(def ^:private sensitive-key-re
  #"(?i)password|token|secret|jwt|sudo|auth-uri|user-id|email|phone|ssn|cc|card")

(defn- sensitive-key? [k]
  (and (or (keyword? k) (string? k))
       (re-find sensitive-key-re (name k))))

(defn- redact-sensitive-floor
  "Recursive postwalk redaction for the closed sensitive-key floor.
   Fallback for payloads whose values aren't covered by registered :sensitive? schema."
  [v]
  (clojure.walk/postwalk
    (fn [node]
      (if (map? node)
        (reduce-kv (fn [m k vv] (assoc m k (if (sensitive-key? k) :rf/redacted vv))) {} node)
        node))
    v))

(defn- cap-or-elide
  "Returns [bounded-value dropped-count] — applies schema-aware wire-elision walker
   first, then floor redaction as a fallback for undeclared keys."
  [v {:keys [threshold-bytes frame] :or {threshold-bytes 32768 frame :rf/default}}]
  (let [floor-redacted (redact-sensitive-floor v)
        opts           {:rf.size/threshold-bytes    threshold-bytes
                        :rf.size/include-sensitive? false
                        :rf.size/include-large?     false
                        :frame                      frame}
        elided         (rf/elide-wire-value floor-redacted opts)
        dropped        (->> (tree-seq coll? seq elided)
                            (filter #(or (= :rf/redacted %)
                                         (and (map? %) (= :rf.size/large-elided (:rf.size/marker %)))))
                            count)]
    [elided dropped]))

;; --- The trace listener registration (the M-13 / M-17 replacement) ---

(rf/register-listener! :my-app/audit-forwarder
  (fn audit-forwarder [trace-event]
    (when (and (= :event/dispatched (:operation trace-event))         ;; one event per dispatch
               (not (:sensitive? trace-event)))                       ;; honour framework default-drop
      (let [event-v   (-> trace-event :tags :event-v)
            frame     (:frame trace-event)
            [bounded
             dropped] (cap-or-elide event-v {:threshold-bytes 32768
                                             :frame           frame})]
        (when (pos? dropped)
          (rf/dispatch [:audit/dropped-counter-inc
                        {:operation (:operation trace-event)
                         :dropped   dropped}]))
        (sentry/capture-message
          {:message "audit event"
           :extra   {:event/operation  (:operation trace-event)
                     :event/payload    bounded
                     :event/dispatch-id (:dispatch-id trace-event)
                     :event/dropped     dropped
                     :event/frame       frame}})))))

;; --- The dropped-counter event + sub (operator-visible signal #2) ---

(rf/reg-event-db :audit/dropped-counter-inc
  (fn [db [_ {:keys [operation dropped]}]]
    (-> db
        (update-in [:audit/counters :total-dropped]                 (fnil + 0) dropped)
        (update-in [:audit/counters :per-operation operation]        (fnil + 0) dropped))))

(rf/reg-sub :audit/dropped-counter
  (fn [db _] (:audit/counters db)))
```

This is the **structural rewrite target** for every "observer-shaped `reg-event-error-handler` / `reg-global-interceptor` / `add-post-event-callback` / handler-body telemetry" hit from §1. The framework defaults compose (the `:sensitive?` guard, the off-box-include defaults on the walker); the floor checklist composes (`redact-sensitive-floor`); the size cap composes (`cap-or-elide`); the dropped-count signal composes (the `[:audit/dropped-counter-inc ...]` dispatch). The body is the **minimum baseline** — every observability site lands here or better; the agent surfaces the diff between the v1 site's body and this shape and asks the operator to confirm any deviations (a destination-specific SDK call, a custom batching layer, an alternative redaction policy).

### Shape B — `register-epoch-listener!` for assembled-epoch observers

When the v1 observer assembled a per-cascade summary (an audit-log entry per drain, an error-projection per failed cascade, a post-mortem record per top-level event), the v2-canonical surface is `register-epoch-listener!` rather than `register-listener!` — the framework hands the listener one assembled `:rf/epoch-record` per drain-settle with the structured `:sub-runs` / `:renders` / `:effects` projections (per [009 §`register-epoch-listener!` — assembled-epoch listener](../../spec/009-Instrumentation.md#register-epoch-listener--assembled-epoch-listener)). The mediation body is **structurally similar** to Shape A but operates on the epoch record:

```clojure
(rf/register-epoch-listener! :my-app/post-mortem-shipper
  (fn epoch-shipper [epoch-record]
    (when-not (:rf.epoch/sensitive? epoch-record)                  ;; honour epoch-level rollup
      (let [[bounded dropped] (cap-or-elide epoch-record
                                            {:threshold-bytes 65536
                                             :frame           (:frame epoch-record)})]
        (when (pos? dropped)
          (rf/dispatch [:audit/dropped-counter-inc
                        {:operation :epoch/settled
                         :dropped   dropped}]))
        (post-mortem-svc/forward
          {:trigger-event (:trigger-event bounded)
           :outcome       (:outcome bounded)
           :sub-runs      (:sub-runs bounded)
           :renders       (:renders bounded)
           :effects       (:effects bounded)
           :dropped       dropped})))))
```

Two epoch-specific notes:

- **`(:rf.epoch/sensitive? epoch-record)`** is the framework-computed rollup over the schema-declared sensitive leaves of `:db-before` / `:db-after` / `:trigger-event` / `:trace-events` (per [Security.md §Sensitive rollup at the record level](../../spec/Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)). The shipper MUST default-drop sensitive epochs; the rollup is exactly the signal to gate on.
- **The `(rf/configure :epoch-history {:redact-fn ...})` build-time hook** is a stronger alternative — instead of every off-box forwarder applying its own `cap-or-elide`, the operator installs one redact-fn at boot that erases sensitive material from the record **once per drain**; every downstream consumer (ring buffer, listener fan-out, off-box egress) sees the redacted shape. The agent SHOULD recommend the build-time hook when the codebase has multiple forwarders against the same epoch surface; for single-forwarder codebases the per-listener `cap-or-elide` is sufficient.

### Shape C — per-frame `:interceptors` for behaviour-modifying interceptors

For hits classified as "behaviour-modifying interceptor" (§1's third category), the rewrite is **not** to the trace surface — those interceptors are not observers, and lifting them off the dispatch path would change runtime behaviour. Port them per [M-17](README.md#m-17-reg-global-interceptor--clear-global-interceptor-removed--use-frame-level-interceptors) to the frame-level `:interceptors` vector. This rule does not cover that path — surface the hit and point the operator at M-17.

### Schema-declaration follow-on per site

For every observability site the agent rewrites, the report lists the sensitive keys the site walked and proposes `{:sensitive? true}` schema annotations for each that has a registered schema slot. The operator picks per slot — the schema annotation eliminates the per-listener explicit-drop on every future consumer and is the canonical privacy declaration (per [Security.md §Privacy / secret handling](../../spec/Security.md#privacy--secret-handling)). The follow-on lands as a separate diff per schema, surfaced in the report's "schema-annotation follow-ons" section.

## Reporting

When the agent applies this rule:

- The migration report lists every observability site found, classified per §1 (observer-off-box / observer-local / behaviour-modifying / misclassified-handler-body) with file/line.
- Each rewrite shows: the v1 site, the v2 target shape (A / B / C), the framework defaults composed (sensitive guard, walker defaults), the floor-checklist drops applied, the dropped-count signal added.
- The "schema-annotation follow-ons" section lists every sensitive-key + schema-slot pair the agent found, with a per-slot proposal of `{:sensitive? true}` and the rationale (which observability site walked it).
- The "size-cap configuration" section lists each listener's chosen `threshold-bytes` and the rationale (Sentry budget, log-file size, dashboard payload limit) — operator confirmation per listener.
- Any hit the agent could not classify ("the body does too much / does both observer and behaviour-modifying work") is listed as an escalation, with the recommended path (split the body, port halves to different surfaces).
- The "framework defaults" section reminds the operator that the always-on substrate (`:on-error`, event-emit listener, error-emit listener — per [009 §What IS available in production](../../spec/009-Instrumentation.md#what-is-available-in-production)) survives production builds; observability sites that need a production-survivable path go through those substrates, not through `register-listener!` (which elides on `:advanced + goog.DEBUG=false`).
