# 23a — Privacy: keeping secrets out of traces

## TL;DR

You're shipping events and errors to a production observability service ([chapter 22](22-trace-to-datadog.md)), and you need to make sure credentials, tokens, and PII never go with them. This page shows how to declare which `app-db` slices are **sensitive** and how the wire-boundary walker honours the declaration.

One trace surface feeds every tool. That's the killer feature. It's also the killer threat: every event a user dispatches, every `:tags :event` payload, every `app-db` snapshot, every `:rf.http/*` request and response rides the same stream. If the stream goes off-box without privacy-honouring, the first sign-in form on the app leaks `password "shhh"` to every dev who attaches a listener, every Datadog dashboard, every Sentry queue, every pair-programming MCP server. The trace surface is built like a firehose because firehoses make great debuggers; firehoses make terrible auth-token loggers.

This chapter is the **writer-side** companion to [chapter 22](22-trace-to-datadog.md) for the privacy half. Chapter 22 shows you how to *consume* the privacy flags from a listener; this one shows you how to *declare* them from your app so the consumers have something to honour. The companion chapter [23b — Large blobs](23b-large-blobs.md) covers the matching size half. The two halves close the loop.

You'll know:

- What `:sensitive?` means and how it composes with `:large?` on the same slot.
- The **one** primary declaration site (Malli schema-slot meta) and the **one** escape hatch (handler-meta `:sensitive?`).
- What the `rf/elide-wire-value` walker does with sensitive values.
- How HTTP coverage layers on top (header denylist, body redaction, URL query-string).
- How schema-validation errors interact (sensitive paths in `:explain` traces).

## Why the framework cares

Observability is the third pillar — but observability without privacy is *the leak channel built into the runtime*. The Causa-MCP server (re-frame2-pair-mcp; the off-box AI surface) reads `app-db`. The Datadog forwarder you saw in [ch.22](22-trace-to-datadog.md) reads `:tags :event`. The Sentry bridge in [ch.14](14-errors.md) ships `:rf.error/*` events whose `:tags` include the event vector that triggered the throw. Every one of those consumers is downstream of the same stream — and every one of them, if it ships your password-bearing sign-in event unmodified, has a security incident.

The framework's answer is *not* "filter at the consumer". Consumers are written by humans (you, the app writer) and AI agents and ops engineers — humans who forget, agents who don't know which slot is sensitive without being told. The framework's answer is **the registration declares the truth, the walker enforces it, the consumer reads the result**. Three pieces; one is yours.

## One obvious way

The earlier life of this chapter enumerated three declaration sites for the privacy flag — plus a "belt + braces" recommendation that nudged you to use *both* a handler-meta flag *and* an interceptor on the same handler. That was a confession that no single site was sufficient. Three sites for one decision is three chances to disagree with yourself.

Pre-alpha lets us be strict. The Zen-of-Python rule — *there should be one — and preferably only one — obvious way to do it* — wins here. So this chapter has **one obvious way**: declare on the Malli schema. There is **one escape hatch** — handler-meta `:sensitive?` — for the genuinely cross-cutting case where the question "is this sensitive?" lives at the handler scope, not at any one slot. That's it.

The asymmetry is intentional, and it tracks the underlying semantic asymmetry:

- **Data-shape questions live on the schema.** "Is this value sensitive?" is a question about the *kind* of thing stored at a path. It's stable across every handler that ever touches the path — every write, every read, every cascade. The schema is where stable data-shape facts go.
- **Handler-scoped behavioural questions live on the handler.** "Does *this handler* read or write multiple slots whose individual schemas vary, in a way that makes the *handler* the right unit of sensitivity?" is a question about a specific code path, not a specific slot. That's what handler-meta `:sensitive?` is for, and it's rare — most handlers touch one slot kind and inherit its schema's verdict automatically.

## The one primary site — schema-slot meta

Both elision flags live on the same surface: a Malli slot's per-slot props map. One keyword on one map, and every consumer of the trace stream honours it.

```clojure
(rf/reg-app-schema
  [:user]
  [:map
   [:profile      [:map [:name :string] [:email :string]]]
   [:credit-card  {:sensitive? true}                              :string]    ;; redacted in traces
   [:audit-log    {:large?     true :hint "Audit log entries"}    [:vector :map]]]) ;; elided in traces (see ch.23b)
```

That's the declaration. Nothing else.

Boot-time, the runtime walks every registered schema and writes the verdict into the reserved `[:rf/elision :declarations]` slot of `app-db`. Every wire-boundary emit consults that slot. Every off-box consumer — re-frame2-pair-mcp, Datadog shipper, Causa-MCP — sees the redacted shape; the value never leaves the trust boundary.

What `:sensitive? true` does:

The value never appears in any trace, off-box ship, dev-panel render, or schema-validation error. At every wire emit, the slot's value is substituted with the `:rf/redacted` sentinel. The trace event also gets `:sensitive? true` stamped at the top level so off-box listeners can drop the whole event. **You do not need a separate interceptor**; the framework auto-installs the scrub for any handler that reads or writes a `:sensitive?` slot. The schema is the truth; the wiring is automatic.

This is the AI-discoverable form. Schemas are the AI-first surface for app shape (per [ch.04a — Schemas](04a-schemas.md)); an agent reading the schema sees the privacy claim alongside the type, on the same line, in the same vocabulary. There is no separate handler-side declaration to cross-reference, no interceptor to grep for, no runtime registration to chase down.

## The one escape hatch — handler-meta `:sensitive?`

Schemas are the truth for data-shape questions. But occasionally a handler is itself the unit of sensitivity:

> A `:billing/import-statement` handler reads three different slots — a user's profile (not sensitive), a fetched HTTP response (whose schema doesn't itself declare `:sensitive?` because the same endpoint is reused in non-sensitive contexts), and the imported statement payload. The handler is the place where these three composing pieces become a *sensitive* operation; no individual slot's schema can carry that verdict.

For that case — and only for that case — handler-meta `:sensitive?` is the escape hatch:

```clojure
(rf/reg-event-fx :billing/import-statement
  {:doc        "Reconcile an imported bank statement against the user's ledger."
   :sensitive? true}                                ;; ← the WHOLE handler scope is sensitive
  (fn [{:keys [db]} [_ statement]]
    {:db (assoc-in db [:billing :pending-import] statement)
     :fx [[:rf.http/managed {:request {:method :post :url "/billing/reconcile" :body statement}}]]}))
```

What the runtime does with the flag:

- Hoists `:sensitive? true` to the **top level** of every trace event emitted while this handler is in scope (alongside `:source` / `:recovery`; not nested under `:tags`). Off-box listeners route on this exact slot.
- Propagates the flag through dispatched cascades — every `:rf.http/*` trace event the handler triggers inherits `:sensitive? true`.
- Drops the whole trace event from off-box shippers' default policy. The on-box dev panels render an opaque `:sensitive?` indicator and require an opt-in click to reveal.

This is the *only* declaration site outside the schema. Use it when the sensitivity claim genuinely belongs to the handler — when no single slot's schema can carry the truth. Otherwise: put it on the schema.

## Worked examples

### A schema with a sensitive slot

```clojure
(rf/reg-app-schema
  [:user/account]
  [:map
   [:username     :string]
   [:credit-card  {:sensitive? true}                                :string]])
```

That's the entire privacy declaration surface for the `:user/account` slice. `:credit-card` will never appear in any trace. Every handler that touches the slot inherits the verdict; no further bookkeeping required.

### A handler that touches the slot — automatic scrub, no interceptor

```clojure
(rf/reg-event-db :user.account/update-card
  (fn [db [_ new-card-number]]
    (assoc-in db [:user/account :credit-card] new-card-number)))
```

That's all you write. The handler body sees `new-card-number` verbatim — handlers need the real value to do their work. But the `:event/dispatched` trace event for this handler, and the `:event/db-changed` trace event that follows, both ship with `:credit-card` substituted by `:rf/redacted` — *automatically*, because the schema for the slot declared `:sensitive? true`. You did not write a `with-redacted` interceptor. You did not stamp the handler. The schema is the single source of truth; the framework installs the scrub.

### The cross-cutting case — handler-meta `:sensitive?`

```clojure
(rf/reg-event-fx :user/export-account-bundle
  {:doc        "Bundle the user's profile, audit log, and settings for a GDPR export."
   :sensitive? true}                                ;; ← the bundle is a sensitive operation
  (fn [{:keys [db]} [_ destination]]
    {:fx [[:rf.http/managed
           {:request {:method :post
                      :url    destination
                      :body   (gdpr-bundle db)}}]]}))
```

Here no individual slot in `app-db` carries `:sensitive? true` — the user's username, the audit log, the settings are all individually non-sensitive (or only `:large?`). But the *bundle*, sent to a third-party export destination, is a sensitive operation: the dispatch carries the destination URL, the body assembles three slots into a single payload, and an off-box trace consumer should not see the whole bundle even though no one slot in it is "sensitive" in isolation. Handler-meta is the right tool because the sensitivity is a property of *this handler's behaviour*, not of any slot it touches.

## What you DON'T do anymore

A short list of things that used to exist and don't. Each line answers "what do I do instead?"

- **No `with-redacted` interceptor to add by hand.** *Instead:* declare `:sensitive?` on the schema slot. The framework auto-installs the scrub for every handler that reads or writes the slot. You write zero interceptor lines.
- **No "belt + braces" pattern.** The earlier recommendation to write `:sensitive? true` on the handler-meta *and* `with-redacted` *and* the schema-slot meta was a confession that no one site was sufficient. Under the current contract each declaration is sufficient by itself — schema-slot meta for the data-shape case, handler-meta for the rare cross-cutting case. Pick the one that matches the truth; never both.

## Composition with `:large?` — sensitive wins

When a slot carries both flags (a 5 MB base64-encoded ID-card image stored under `[:auth :scanned-id]` — both sensitive *and* large), the value is dropped, not marker-substituted — because the marker itself carries `:path` and `:bytes`, which is structural information about the redacted slot. The composition rule is **`sensitive drop wins`**, deterministic:

```clojure
(cond
  (and sensitive? large?)  ::drop                  ; no marker; emit :sensitive? true
  sensitive?               ::redact-or-drop        ; :rf/redacted sentinel
  large?                   ::elide-with-marker     ; :rf.size/large-elided (see ch.23b)
  :else                    ::pass-through)
```

The same composition rule binds the schema-validation emit-site (per [§Schema-validation errors](#schema-validation-errors) below) and every tool that calls the walker downstream. The `:large?` side of the story lives in [chapter 23b](23b-large-blobs.md).

## How elision uses what you declared

The schema is the input; the elision pipeline is the output. The framework does the wiring between the two — you don't see it from the app-writer side, but the one paragraph is worth knowing:

At boot, the runtime walks every registered schema and extracts the per-slot `:sensitive?` / `:large?` claims into the reserved `[:rf/elision :declarations]` slot in `app-db`. At every wire-boundary emit, the `rf/elide-wire-value` walker consults that slot once per visited path. Tools like Causa, re-frame2-pair-mcp, story-mcp, and the Datadog shipper from [ch.22](22-trace-to-datadog.md) consume the walker's output, not your schema directly; they don't need to know how the declarations got into the registry, just that they're there.

**One declaration; every consumer honours it.** If you declare `:sensitive? true` on `[:user :credit-card]`, every off-box ship, every on-box dev-panel render, every `:rf.http/*` request body, every schema-validation error trace substitutes `:rf/redacted` for the slot's value. The platform handles the rest.

## The unified walker — `rf/elide-wire-value`

One function. Every tool that emits wire data calls it. The single normative emission site for the `:rf/redacted` sentinel and the `:rf.size/large-elided` marker — per-tool reimplementation is prohibited.

```clojure
(rf/elide-wire-value v
                     {:rf.size/include-sensitive? false    ;; default false — sensitive drops
                      :rf.size/include-large?     false    ;; default false — large elides (see ch.23b)
                      :rf.size/include-digests?   false    ;; default false — no sha256 in marker
                      :rf.size/threshold-bytes    16384
                      :frame                      :rf/default})
;; → v unchanged, OR
;; → nil (sensitive event dropped entirely), OR
;; → v with :rf/redacted at sensitive paths, OR
;; → v with :rf.size/large-elided markers at large paths (see ch.23b)
```

## HTTP coverage

HTTP is the canonical privacy surface — passwords ride bodies, auth tokens ride headers, user PII rides response payloads. The framework's HTTP cascade (see [ch.10](10-doing-http-requests.md)) layers three cooperating pieces on top of the generic `:sensitive?` machinery — none of them are app-writer declarations, but all of them honour your schema's `:sensitive?` verdict.

**Header denylist (always-on).** A canonical set of header names is *always sensitive* — the name itself declares the value secret regardless of the surrounding handler's flag. The v1 closed list is twelve names: `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-Session-Token`, `X-CSRF-Token`, `X-XSRF-Token`, `Authentication`, `WWW-Authenticate`, `Proxy-Authenticate`. Their values become `:rf/redacted` in every `:rf.http/*` trace event that carries a `:headers` slot. Apps extend with `(rf.http/declare-sensitive-header! "X-Honeycomb-Team")`.

**URL query-string denylist (always-on, rf2-2p8wr — in flight).** Parallel-axis: a closed set of query parameter names whose values redact inline regardless of handler `:sensitive?`. `?api_key=SECRET&page=2` becomes `?api_key=:rf/redacted&page=2` — the name and position survive (so you can see *which* endpoint was called), the secret value doesn't. A denylist hit also *stamps* `:sensitive? true` on the trace event — the presence of a denylisted param is itself a signal the request carries an auth secret. Extend with `(rf.http/declare-sensitive-query-param! "shop_token")`.

**Body / params redaction (effective-sensitive).** When the request is sensitive — either because the handler-meta carries `:sensitive? true`, or because a schema slot the request body assembles from is `:sensitive?` — the body redaction kicks in: `:body`, `:body-text`, `:decoded`, `:detail`, `:params`, and **all** `:url` query-string param values become `:rf/redacted`. Three OR-reduced sources contribute the effective flag — handler `:sensitive?`, `:request` map `:sensitive?`, or top-level `:sensitive?` on the `:rf.http/managed` args. Any one true ⇒ sensitive.

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

The validation emit-site walks the failing path's schema; if the slot declares `:sensitive? true`, the `:value` and `:received` slots in the trace are substituted with `:rf/redacted` before emit, and the trace event is stamped `:sensitive? true` at the top level (so off-box listeners filter it like any other sensitive emit).

This is the same `:sensitive?` declaration you already wrote on the schema slot. There is no second site to also tell the validation emit-site:

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
| re-frame2-pair-mcp (AI surface) | `false` | `false` | Yes |
| story-mcp (story playgrounds) | `false` | `false` | Yes |
| Causa-MCP (cascade graph) | `false` | `false` | Yes |
| Story panel (on-box dev UI) | `false` | `false` | No |
| Causa panel (on-box dev UI) | `false` | `false` | No |

[Chapter 22](22-trace-to-datadog.md)'s Datadog shipper is the sixth consumer — and it follows the same rule: **off-box shippers MUST default both `include-*` flags to `false`**. Off-box means "the data is leaving your trust boundary"; Datadog's trust boundary is not yours. The conservative default is the framework's safety net for app authors who opt into a published integration without reading its source.

On-box dev UIs (the Causa panel, the Story panel) show a `[● ELIDED N]`-style indicator when the marker is in the rendered view, and the user clicks to opt in for a single fetch. Production-trust on-box consumers MAY default to `true`, but the rationale must be documented per-consumer.

## Worked example — login with a sensitive credential

A login form with a sensitive password — `:sensitive?` declared once on the schema, enforced once at the wire boundary:

```clojure
;; 1. Declare the schema slot — :sensitive? on the password.
;;    That's the whole privacy surface.
(rf/reg-app-schema
  [:auth]
  [:map
   [:username    :string]
   [:password    {:sensitive? true}     :string]])

;; 2. Sign-in handler — no metadata, no interceptor.
;;    The schema's :sensitive? on :password drives the trace scrub
;;    AND the HTTP body redaction automatically.
(rf/reg-event-fx :auth/sign-in
  {:doc "Verify credentials and start a session."}
  (fn [{:keys [db]} [_ {:keys [username password]}]]
    {:db (assoc db :auth/pending? true)
     :fx [[:rf.http/managed
           {:request {:method :post
                      :url    "/auth/login"
                      :body   {:u username :p password}}}]]}))
;; The :rf.http/managed cascade sees the :password slot is :sensitive?
;; (by schema); body redaction kicks in; the Authorization header (if
;; the response sets one) is denylisted automatically.

;; 3. Trace stream when the user signs in:

;;    Event A — :event/dispatched
;;    {:operation :event/dispatched
;;     :op-type   :event
;;     :tags      {:event [:auth/sign-in {:username "ada" :password :rf/redacted}]}
;;     :sensitive? true}
;;    ;; Schema-driven scrub on :password; :sensitive? stamps the top level.

;;    Event B — :rf.http/request-started
;;    {:operation :rf.http/request-started
;;     :tags      {:request {:url "/auth/login" :body :rf/redacted}}
;;     :sensitive? true}
;;    ;; HTTP cascade saw :password was :sensitive?; body redaction fired.

;;    Event C — the Datadog shipper from ch.22
;;    Events A and B drop (sensitive). The Datadog dashboard sees the
;;    cascade shape, the timing, the error class — it just doesn't see
;;    the password.
```

One declaration site, one walker, every consumer honours it.

## Next

- [23b — Large blobs: keeping the wire small](23b-large-blobs.md) — the matching size half. The `:large?` flag, the `:rf.size/large-elided` marker, opt-in fetch via `:handle`.
- [04a — Schemas](04a-schemas.md) — the per-slot props map this chapter writes to, and the rest of the schema vocabulary.
- [10 — Doing HTTP requests](10-doing-http-requests.md) — the `:rf.http/managed` cascade that extends this chapter's privacy machinery.
- [14 — Errors and how to handle them](14-errors.md) — the `:rf.error/*` taxonomy this chapter's schema-validation section bottoms out on.
- [Causa](../causa/index.md) — the third-pillar pitch: one trace bus, every tool consumes it. The reason privacy and size matter is that the bus has five+ consumers.
- [22 — Production observability](22-trace-to-datadog.md) — the consumer-side companion. Read it after this chapter to see the writer's declarations land on the wire.
