# 23 — Privacy and size elision

> **If you're skipping this chapter, the upshot:** observability is the killer feature, but if the trace stream leaks credentials and PII it's malware. Two orthogonal flags — `:sensitive?` (drop) and `:large?` (elide-with-fetch) — declared on registrations, schemas, or fx, and consulted by one wire-boundary walker (`rf/elide-wire-value`). [Chapter 22](22-trace-to-datadog.md) is the *consumer* side; this chapter is the *writer* side — what you declare in your app so the walker has something to honour.

The third pillar of re-frame2 — [chapter 15](15-devtools-and-pair-tools.md) — is that one trace surface feeds every tool. That's the killer feature. It's also the killer threat: every event a user dispatches, every `:tags :event` payload, every `app-db` snapshot, every `:rf.http/*` request and response rides the same stream. If the stream goes off-box without privacy-honouring, the first sign-in form on the app leaks `password "shhh"` to every dev who attaches a listener, every Datadog dashboard, every Sentry queue, every pair-programming MCP server. The trace surface is built like a firehose because firehoses make great debuggers; firehoses make terrible auth-token loggers.

This chapter is the **writer-side** companion to [chapter 22](22-trace-to-datadog.md). Chapter 22 shows you how to *consume* the privacy flags from a listener; this one shows you how to *declare* them from your app so the consumers have something to honour. The two halves close the loop.

You'll know:

- The 2-axis design — sensitive (drop) vs large (elide-with-fetch) — and the composition rule.
- The three declaration sites for `:sensitive?` and the three for `:large?`.
- What the unified `rf/elide-wire-value` walker does and when it fires.
- How HTTP coverage layers on top (header denylist, body redaction, URL query-string).
- How schema-validation errors interact (sensitive paths in `:explain` traces).
- How consumer-side flags compose with your writer-side declarations.

The `:sensitive?` + `:large?` composition you'll meet below is **wire-elision over managed external effects** — property five of the eight-property contract in [`spec/Managed-Effects.md`](../../spec/Managed-Effects.md). Every framework-owned async surface (HTTP, WebSocket, state-machine `:invoke`, SSR per-request fxs, managed flows) routes its wire-bearing trace slots through the single shared `rf/elide-wire-value` walker. Surface-specific elision is prohibited; the walker is the one point of truth. This chapter is what you declare so that one walker has something to honour for every surface at once.

## Why the framework cares

Observability is the third pillar — but observability without privacy is *the leak channel built into the runtime*. The Causa-MCP server (pair2-mcp; the off-box AI surface) reads `app-db`. The Datadog forwarder you saw in [ch.22](22-trace-to-datadog.md) reads `:tags :event`. The Sentry bridge in [ch.14](14-errors.md) ships `:rf.error/*` events whose `:tags` include the event vector that triggered the throw. Every one of those consumers is downstream of the same stream — and every one of them, if it ships your password-bearing sign-in event unmodified, has a security incident.

The framework's answer is *not* "filter at the consumer". Consumers are written by humans (you, the app writer) and AI agents and ops engineers — humans who forget, agents who don't know which slot is sensitive without being told. The framework's answer is **the registration declares the truth, the walker enforces it, the consumer reads the result**. Three pieces; one is yours.

## The 2-axis design

Two predicates. Same walker.

| Axis | Question | Default placeholder | Wire effect |
|---|---|---|---|
| `:sensitive?` | "Does this value carry user input that must not leave the trust boundary?" | `:rf/redacted` keyword (or whole event dropped) | Filter-out for off-box ship; in-place scrub for in-app emit |
| `:large?` | "Is this value too big to ride a 5K-token wire?" | `:rf.size/large-elided` marker with `:handle` for opt-in fetch | Marker substitutes for the value; consumer re-fetches via `get-path` |

The walker — `rf/elide-wire-value` (per [API.md §Size-elision wire-boundary walker](../../spec/API.md#elide-wire-value-the-wire-boundary-walker)) — consults both flags at every node it visits and substitutes the appropriate placeholder. One pass, two flags.

**The composition rule is `sensitive drop wins`.** When a value matches both predicates (a 5 MB base64-encoded ID-card image stored under `[:auth :scanned-id]`), the value is dropped, not marker-substituted — because the marker itself carries `:path` and `:bytes`, which is structural information about the redacted slot. One value, two flags, deterministic precedence:

```clojure
(cond
  (and sensitive? large?)  ::drop                  ; no marker; emit :sensitive? true
  sensitive?               ::redact-or-drop        ; :rf/redacted sentinel
  large?                   ::elide-with-marker     ; :rf.size/large-elided
  :else                    ::pass-through)
```

Same composition rule binds the schema-validation emit-site (per [§Schema-validation errors](#schema-validation-errors) below) and every tool that calls the walker downstream.

## Sensitive — three declaration sites

Three places you tell the framework "this is secret". Use the one that matches the granularity of the truth.

### 1. Registration metadata (`^{:sensitive? true}`)

The most common form. A boolean key on the `reg-event-*` / `reg-sub` metadata map. Every trace event emitted while this handler is in scope gets `:sensitive? true` stamped at the top level:

```clojure
(rf/reg-event-fx :auth/sign-in
  {:doc        "Verify credentials and start a session."
   :sensitive? true                                 ;; the whole handler scope is sensitive
   :spec       [:cat [:= :auth/sign-in] [:map [:username :string] [:password :string]]]}
  (fn [{:keys [db]} [_ {:keys [username password]}]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed {:url "/auth" :method :post :body {:u username :p password}}]]}))
```

What the runtime does with the flag:

- Copies it onto the registry slot's stored meta. Tools read it via `(rf/handler-meta :event :auth/sign-in)`.
- At every trace emit within the handler's execution scope, hoists `:sensitive? true` to the **top level** of the trace event (alongside `:source` / `:recovery`; not nested under `:tags`). Single keyword read, no nested lookup. The Datadog shipper in [ch.22](22-trace-to-datadog.md) routes on this exact slot.
- Propagates the flag through dispatched cascades — every `:rf.http/*` trace event the handler triggers inherits `:sensitive? true` (per [spec/014 §Privacy](../../spec/014-HTTPRequests.md#privacy)).

`:sensitive?` is **declarative, not redactive** — setting it doesn't by itself overwrite anything. The flag is a signal for downstream consumers to drop, redact, or filter. The framework-published listener integrations (Sentry / Honeybadger forwarders, pair2-mcp, Causa-MCP) all default-drop `:sensitive? true` events. Your own listeners should follow suit; the canonical guard line is `(when-not (:sensitive? trace-event) (ship! ...))`.

### 2. `with-redacted` interceptor

When you want the trace to *still emit* (so the dashboard still sees the cascade, the error class, the timing) but with the secret payloads scrubbed in place, reach for `with-redacted`:

```clojure
(rf/reg-event-fx :auth/sign-in
  {:doc "Verify credentials and start a session." :sensitive? true}
  [(rf/with-redacted [[:password] [:totp-code]])]      ;; positional interceptor chain
  (fn [{:keys [db]} [_ {:keys [username password totp-code] :as payload}]]
    ;; The HANDLER BODY sees the unredacted payload — handlers need
    ;; the real value to do their work. The redaction is for the trace
    ;; surface only.
    ...))

;; A dispatch like (rf/dispatch [:auth/sign-in {:username "ada"
;;                                               :password "shhh"
;;                                               :totp-code "123456"}])
;; produces an :event/dispatched trace event whose :tags :event is:
;;
;;   [:auth/sign-in {:username "ada" :password :rf/redacted :totp-code :rf/redacted}]
```

`paths` is a vector of `get-in`-style paths into the event payload's map. The interceptor's `:before` stage overwrites each named slot with the `:rf/redacted` keyword *in the form the trace surface sees*. The handler body itself gets the unredacted payload via the regular `:event` cofx slot — handlers need the real value to do their work. The redaction is for the wire, not for the handler.

The recommended pattern for new sensitive handlers is **both flags together**: `:sensitive? true` on the metadata covers the case where a listener bypasses redaction and ships the event anyway; `with-redacted` covers the case where a listener (or a future code path) ignores `:sensitive?` and emits the payload to a dev panel verbatim. Belt + braces, one line each.

Registering `:sensitive? true` *without* `with-redacted` triggers the runtime warning `:rf.warning/sensitive-without-redaction` once per `(kind, id)` pair — the framework noticing you've declared the leak surface without scrubbing it. The opt-out is `:no-redaction-needed? true` on the metadata (for cases where the event vector legitimately carries no secrets — the sensitivity is in the *handler's app-db touches*, not the payload).

### 3. Schema slot meta `:sensitive?` (forward-ref — rf2-c1l4d in flight)

When the truth lives in the data shape rather than in any particular handler, declare `:sensitive?` on the Malli schema slot:

```clojure
(rf/reg-app-schema
  [:map
   [:auth [:map
           [:username :string]
           [:token {:sensitive? true} :string]      ;; per-slot meta
           [:totp-code {:sensitive? true} :string]]]])
```

The schema-validation emit-site (`:rf.error/schema-validation-failure`; see [§Schema-validation errors](#schema-validation-errors) below) walks the failing path's schema and substitutes `:rf/redacted` for the slot's value before emitting the trace. The same flag propagates through `rf/elide-wire-value` when tools walk `app-db` snapshots.

Per-slot meta is the most AI-discoverable form — an agent reading the schema sees the privacy claim alongside the type. Use it for slots whose *value-kind* is always sensitive, regardless of which handler touches them. (Slot-meta lands with [spec/010 §Per-slot metadata vocabulary](../../spec/010-Schemas.md#per-slot-metadata-vocabulary); the walker side is rf2-c1l4d, still in flight as of this writing — declare today and the runtime picks it up when the walker lands.)

## Large — three declaration sites

The size axis is structurally parallel — three nomination paths, one walker. The framework keeps a per-frame registry under the reserved `[:rf/elision :declarations]` slot in `app-db` (per [Conventions §Reserved app-db keys](../../spec/Conventions.md)); each declaration site writes into the same slot with a `:source` provenance tag.

### 1. Schema slot meta (`:large?`) — canonical AI-discoverable

The first-class form. A boolean on a Malli slot's per-slot props map:

```clojure
(rf/reg-app-schema
  [:map
   [:user [:map
           [:profile :string]
           [:uploaded-pdf {:large? true :hint "Upload preview blob"} :string]]]])

;; Boot-time, the runtime walks the registered schema and writes:
;;   [:rf/elision :declarations [:user :uploaded-pdf]]
;;     {:large? true :source :schema :hint "Upload preview blob"}
```

`:hint` is a free-form short string that rides the elision marker, so an agent or a dev-tool tooltip can see what the elided slot *is* without fetching its value. Pair it with `:large?` whenever the slot's purpose isn't obvious from the path.

This is the AI-discoverable path: schemas are the AI-first surface for app shape (per [ch.04a — Schemas](04a-schemas.md)), so an agent reading the schema sees the size claim alongside the type. Prefer this form when the size is a property of the *slot* (an upload preview, a base64 image cache, a generated PDF blob).

### 2. Runtime auto-detect (`:rf.size/threshold-bytes`, default 16384)

For slots whose size is unpredictable — caches, paginated lists, debug snapshots — the runtime auto-flags. At every wire-boundary emit, the walker `pr-str`-measures each top-two-level subtree it visits; values over the threshold (default `16384` bytes) get a slot written into `[:rf/elision :runtime-flagged]` and elided. Subsequent emits on the same path short-circuit (no re-walk; the slot is the cached decision).

The threshold is per-call configurable through the elision-policy opts map (per [API.md §Size-elision wire-boundary walker](../../spec/API.md#elide-wire-value-the-wire-boundary-walker)):

```clojure
(rf/elide-wire-value trace-event
                     {:rf.size/threshold-bytes 8192})     ;; tighter than default
```

A dedicated warning fires once per `(path, frame)` pair when the heuristic first flags a path: `:rf.warning/runtime-large-elision`. The framework's nudge — "you're shipping a value over the threshold; promote it to a declared `:large?` to suppress the warning, or override with a `{:large? false}` declared entry if it really is small enough to ride the wire."

### 3. fx-driven (`:rf.size/declare-large`)

When the size claim is *runtime knowledge* — your `app/init` handler runs and *now* the framework should know that the slot under `[:user :photo-cache]` is going to be big — declare from an fx:

```clojure
(rf/reg-event-fx :app/init
  (fn [{:keys [db]} _]
    {:db db
     :fx [[:rf.size/declare-large
            {:path [:user :photo-cache]
             :hint "User photo thumbnail cache"}]]}))
```

The convenience REPL form is `(rf/declare-large-path! [:user :photo-cache] "User photo thumbnail cache")` (per [API.md §Size-elision wire-boundary walker](../../spec/API.md#elide-wire-value-the-wire-boundary-walker)) — same effect, suitable for boot-time wiring or interactive REPL debugging. The matching `:rf.size/clear` fx (and `rf/clear-large-path!`) removes the slot.

### Resolution order

The three sites overlap. When the same path has conflicting declarations, **declared wins, then schema, then runtime-flagged**. App knowledge beats schema declaration beats heuristic. The walker consults `[:rf/elision :declarations <path>]` first; absent, falls back to `[:rf/elision :runtime-flagged <path>]`.

## The unified walker — `rf/elide-wire-value`

One function. Every tool that emits wire data calls it. The single normative emission site for the `:rf/redacted` sentinel and the `:rf.size/large-elided` marker — per-tool reimplementation is prohibited.

```clojure
(rf/elide-wire-value v
                     {:rf.size/include-sensitive? false    ;; default false — sensitive drops
                      :rf.size/include-large?     false    ;; default false — large elides
                      :rf.size/include-digests?   false    ;; default false — no sha256 in marker
                      :rf.size/threshold-bytes    16384
                      :frame                      :rf/default})
;; → v unchanged, OR
;; → nil (sensitive event dropped entirely), OR
;; → v with :rf/redacted at sensitive paths, OR
;; → v with :rf.size/large-elided markers at large paths
```

The marker shape (per [Spec-Schemas §`:rf/elision-marker`](../../spec/Spec-Schemas.md)):

```clojure
{:rf.size/large-elided
  {:path   [:user :uploaded-pdf]               ;; absolute path inside the slice's root
   :bytes  5242880                             ;; pr-str byte count, exact when known
   :type   :string                             ;; :map :vector :set :scalar :string
   :reason :declared                           ;; :declared :schema :runtime-flagged
   :hint   "Upload preview blob"               ;; the schema slot's :hint copied verbatim
   :handle [:rf.elision/at [:user :uploaded-pdf]]}}  ;; EDN, passable to get-path
```

The `:handle` is **a normal EDN vector**, not a tagged literal — agents pattern-match on the leading `:rf.elision/at` keyword and pass the handle straight to the pair2-mcp `get-path` tool to fetch the elided value (subject to that tool's own cap check; an over-cap fetch fails with `:rf.mcp/overflow`). One round-trip per elided value the consumer actually needs.

## HTTP coverage

HTTP is the canonical privacy surface — passwords ride bodies, auth tokens ride headers, user PII rides response payloads. The framework's HTTP cascade ([ch.10](10-doing-http-requests.md), [spec/014 §Privacy](../../spec/014-HTTPRequests.md#privacy)) layers three cooperating pieces on top of the generic `:sensitive?` machinery.

**Header denylist (always-on).** A canonical set of header names is *always sensitive* — the name itself declares the value secret regardless of the surrounding handler's flag. The v1 closed list is twelve names: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-Session-Token`, `X-CSRF-Token`, `X-XSRF-Token`, `Authentication`, `WWW-Authenticate`, `Proxy-Authenticate`. Their values become `:rf/redacted` in every `:rf.http/*` trace event that carries a `:headers` slot. Apps extend with `(rf.http/declare-sensitive-header! "X-Honeycomb-Team")`.

**URL query-string denylist (always-on, rf2-2p8wr — in flight).** Parallel-axis: a closed set of query parameter names whose values redact inline regardless of handler `:sensitive?`. `?api_key=SECRET&page=2` becomes `?api_key=:rf/redacted&page=2` — the name and position survive (so you can see *which* endpoint was called), the secret value doesn't. A denylist hit also *stamps* `:sensitive? true` on the trace event — the presence of a denylisted param is itself a signal the request carries an auth secret. Extend with `(rf.http/declare-sensitive-query-param! "shop_token")`.

**Body / params redaction (effective-sensitive).** When the request is sensitive (handler-level, per-request, or per-call), the body redaction kicks in: `:body`, `:body-text`, `:decoded`, `:detail`, `:params`, and **all** `:url` query-string param values become `:rf/redacted`. Three OR-reduced sources contribute the effective flag — handler `:sensitive?`, `:request` map `:sensitive?`, or top-level `:sensitive?` on the `:rf.http/managed` args. Any one true ⇒ sensitive.

```clojure
;; Per-request form — non-sensitive handler with one sensitive call:
(rf/reg-event-fx :api/post-to-endpoint
  (fn [{:keys [db]} [_ target body]]
    {:fx [[:rf.http/managed
           {:request {:method  :post
                      :url     target
                      :body    body
                      :sensitive? (= target "/auth/login")}}]]}))
```

## Schema-validation errors

When `app-db` fails validation, the runtime emits `:rf.error/schema-validation-failure` with the failing value in `:tags :value` (and the surrounding `:explain` map). For sensitive slots, that emit is the back-door — the value the schema rejected is exactly the value you didn't want in the trace stream.

Per [spec/010 §`:sensitive?` — privacy in schema-validation error traces (rf2-kj51z)](../../spec/010-Schemas.md), the validation emit-site walks the failing path's schema; if the slot declares `:sensitive? true`, the `:value` and `:received` slots in the trace are substituted with `:rf/redacted` before emit, and the trace event is stamped `:sensitive? true` at the top level (so off-box listeners filter it like any other sensitive emit).

The slot-meta form (same vocabulary as `:large?`):

```clojure
(rf/reg-app-schema
  [:map [:token {:sensitive? true} :string]])

;; A validation failure on [:token] now emits:
;;   {:operation :rf.error/schema-validation-failure
;;    :tags      {:path  [:token]
;;                :value :rf/redacted             ;; ← scrubbed
;;                :explain {...}}
;;    :sensitive? true                            ;; ← consumers route on this
;;    ...}
```

Composition with `:large?` on the same slot mirrors the unified walker's rule — sensitive wins; no `:rf.size/large-elided` marker is emitted because the marker would leak `:path` / `:bytes`.

## Consumer-side flags

Writer-side is half the picture. The other half is the *consumer*'s elision policy — the per-call opts map every tool passes when it invokes `rf/elide-wire-value`. Five consumers ship with the framework, all defaulting to maximum elision:

| Consumer | `:include-sensitive?` default | `:include-large?` default | Off-box? |
|---|---|---|---|
| pair2-mcp (AI surface) | `false` | `false` | Yes |
| story-mcp (story playgrounds) | `false` | `false` | Yes |
| Causa-MCP (cascade graph) | `false` | `false` | Yes |
| Story panel (on-box dev UI) | `false` | `false` | No |
| Causa panel (on-box dev UI) | `false` | `false` | No |

[Chapter 22](22-trace-to-datadog.md)'s Datadog shipper is the sixth consumer — and it follows the same rule: **off-box shippers MUST default both `include-*` flags to `false`**. Off-box means "the data is leaving your trust boundary"; Datadog's trust boundary is not yours. The conservative default is the framework's safety net for app authors who opt into a published integration without reading its source.

On-box dev UIs (the Causa panel, the Story panel) show a `[● ELIDED N]`-style indicator when the marker is in the rendered view, and the user clicks to opt in for a single fetch. Production-trust on-box consumers MAY default to `true`, but the rationale must be documented per-consumer.

## Worked example — login + PDF upload

Pulling the strings together. A login form with sensitive credentials and a PDF preview upload (large blob) — both axes, both predicates, one app:

```clojure
;; 1. Declare the schema slots — :sensitive? on the password slot,
;;    :large? on the PDF blob slot.
(rf/reg-app-schema
  [:map
   [:auth [:map
           [:username :string]
           [:password {:sensitive? true} :string]
           [:pdf-preview {:large? true :hint "Resume PDF preview blob"} :string]]]])

;; 2. Sign-in handler — :sensitive? on the registration meta,
;;    with-redacted for in-place scrub. Belt + braces.
(rf/reg-event-fx :auth/sign-in
  {:doc        "Verify credentials and start a session."
   :sensitive? true}
  [(rf/with-redacted [[:password]])]
  (fn [{:keys [db]} [_ {:keys [username password]}]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed
           {:request {:method :post
                      :url    "/auth/login"
                      :body   {:u username :p password}}}]]}))
;; The :rf.http/managed cascade inherits :sensitive? from the handler;
;; body redaction kicks in; the Authorization header (if the response
;; sets one) is denylisted automatically.

;; 3. PDF upload — no privacy concern, but the blob is huge.
(rf/reg-event-db :auth/load-pdf-preview
  (fn [db [_ pdf-base64]]
    (assoc-in db [:auth :pdf-preview] pdf-base64)))

;; 4. Trace stream when the user signs in then uploads a 5MB PDF:

;;    Event A — :event/dispatched
;;    {:operation :event/dispatched
;;     :op-type   :event
;;     :tags      {:event [:auth/sign-in {:username "ada" :password :rf/redacted}]}
;;     :sensitive? true}
;;    ;; with-redacted scrubbed :password; :sensitive? stamps the top level.

;;    Event B — :rf.http/request-started
;;    {:operation :rf.http/request-started
;;     :tags      {:request {:url "/auth/login" :body :rf/redacted}}
;;     :sensitive? true}
;;    ;; HTTP cascade inherited :sensitive?; body redaction fired.

;;    Event C — :event/db-changed (the PDF assoc)
;;    {:operation :event/db-changed
;;     :tags      {:app-db-after
;;                 {:auth {:pdf-preview
;;                         {:rf.size/large-elided
;;                          {:path   [:auth :pdf-preview]
;;                           :bytes  5242880
;;                           :type   :string
;;                           :reason :schema
;;                           :hint   "Resume PDF preview blob"
;;                           :handle [:rf.elision/at [:auth :pdf-preview]]}}}}}}
;;    ;; The walker swapped the 5MB blob for a 150-byte marker;
;;    ;; the rest of app-db rides verbatim.

;;    Event D — the Datadog shipper from ch.22
;;    Event A drops (sensitive); B drops (sensitive); C ships
;;    (large-but-not-sensitive — the marker rides the wire and the
;;    Datadog dashboard sees a `large-elided` indicator instead of
;;    the 5MB string).
```

Two axes, one walker, one consumer rendering. The Datadog dashboard sees the cascade shape, the timing, the error class — it just doesn't see the password or the PDF. Privacy and size, declared once at the source, enforced once at the wire boundary.

## Next

- [10 — Doing HTTP requests](10-doing-http-requests.md) — the `:rf.http/managed` cascade the privacy section of [spec/014 §Privacy](../../spec/014-HTTPRequests.md#privacy) extends.
- [14 — Errors and how to handle them](14-errors.md) — the `:rf.error/*` taxonomy this chapter's schema-validation section bottoms out on.
- [15 — Tooling](15-devtools-and-pair-tools.md) — the third-pillar pitch: one trace bus, every tool consumes it. The reason privacy and size matter is that the bus has five+ consumers.
- [22 — Trace forwarding to Datadog](22-trace-to-datadog.md) — the consumer-side companion. Read it after this chapter to see the writer's declarations land on the wire.
- [spec/009 §Privacy / sensitive data in traces](../../spec/009-Instrumentation.md#privacy--sensitive-data-in-traces) and [§Size elision in traces](../../spec/009-Instrumentation.md#size-elision-in-traces) — the normative specification.
- [API §Size-elision wire-boundary walker](../../spec/API.md#elide-wire-value-the-wire-boundary-walker) — the `rf/elide-wire-value` reference.
