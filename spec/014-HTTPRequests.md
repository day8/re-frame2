# Spec 014 — HTTP requests

> Status: Drafting. **v1-optional capability.** Implementations MAY ship `:rf.http/managed`; when they do, the contract below is fixed. The CLJS reference implementation ships it (see [§Implementation status](#implementation-status)). Builds on the registration grammar in [001-Registration](001-Registration.md), the dispatch envelope and frame routing in [002-Frames §Routing](002-Frames.md#routing-the-dispatch-envelope), the trace-stream contract in [009-Instrumentation](009-Instrumentation.md), schema integration in [010-Schemas](010-Schemas.md), and the reserved-namespace policy in [Conventions](Conventions.md).
>
> **The minimum claim:** *if* an implementation ships HTTP-request infrastructure, it ships `:rf.http/managed` per this spec — a first-class HTTP request fx that bakes in decoding, success/failure normalisation, retry-with-backoff, abort, schema-driven decode, and reply-to-origin dispatch. The fx is rich enough that apps overwhelmingly want it; the contract being uniform is what lets pair tools, `:fx-overrides`, retry policy, error projection, and frame-aware reply addressing compose across implementations without per-app reinvention.
>
> **Code samples are in ClojureScript** (the CLJS reference). The contract is host-agnostic; the spec calls out per-host divergences (CLJS Fetch / JVM `java.net.http.HttpClient`) explicitly per row.
>
> `:rf.http/managed` is a **managed external effect** — per [Managed-Effects](Managed-Effects.md), the surface MUST satisfy the eight properties (effect-as-data, framework-owned lifecycle, structured failure taxonomy under `:rf.http/*`, trace-bus observability, `:sensitive?` / `:large?` composition, built-in retry / abort / teardown, in-flight registry, per-frame interceptor scoping).

## Abstract

`:rf.http/managed` is the canonical HTTP fx for re-frame2 implementations that ship one. Take an args map, get a request issued; the fx handles transport, decoding, retry-with-backoff, and dispatching the reply back into the runtime. Co-located request and reply handling is the default — one event handler can branch on `(:rf/reply msg)` to handle both initial dispatch and async result — but explicit `:on-success` / `:on-failure` targets switch to the separate-handler shape when that fits better.

The fx specialises [Pattern-AsyncEffect](Pattern-AsyncEffect.md)'s generic six-step shape (register → return `:fx` → post work → reply → dispatch → commit), pins the lifecycle slice from [Pattern-RemoteData](Pattern-RemoteData.md), and inherits the epoch carry from [Pattern-StaleDetection](Pattern-StaleDetection.md). It complements but does not replace the lower-level `:http` fx — apps that need wire-level control (custom transport, raw bytes, idiosyncratic protocols) keep using `:http`; apps that want the common case ergonomic use `:rf.http/managed`.

Streaming and bidirectional communication are deliberately out of scope here — Spec 014 covers the single-request / single-reply shape. WebSocket / SSE / chunked-streaming are handled by sibling specs (Pattern-WebSocket; future `:rf.http/streaming`).

## Implementation status

Spec 014 is an **optional capability** in the [000-Vision §Capability matrix](000-Vision.md) sense. Implementations MAY:

- **Ship `:rf.http/managed` per this spec.** Then the contract below applies — args map shape, failure categories, reply addressing, retry semantics, abort surface, schema-reflection metadata, and trace events all locked. Pair tools and conformance fixtures key off the canonical surface.
- **Omit it.** Applications that need HTTP roll their own fx (or use a third-party library) per [Pattern-AsyncEffect](Pattern-AsyncEffect.md)'s generic shape. The omission is a conformance-set difference, not a defect.

The **CLJS reference implementation ships `:rf.http/managed`**, backed by Fetch on the browser and `java.net.http.HttpClient` on the JVM. Other in-scope JS-cross-compile-language ports (TypeScript, Fable (F#), Scala.js, PureScript, Kotlin/JS, Melange / ReScript / Reason, Squint) decide independently — each typically wraps the host's binding to `fetch` (browser) and the host's runtime HTTP client on Node. A port that omits `:rf.http/managed` MUST NOT register the `:rf.http/*` namespace for any other purpose (it's reserved for this Spec; see [Conventions](Conventions.md)).

If an implementation ships ONLY a subset (e.g., no JVM transport), it claims the relevant capability rows and the conformance corpus exercises only those.

**Artefact (CLJS reference).** Per [rf2-5kpd](#) (the fifth per-feature artefact split per [rf2-5vjj](#) Strategy B), the CLJS reference's managed-HTTP surface ships in the separate Maven artefact `day8/re-frame2-http` — `re-frame.http-managed` namespace, the four `:rf.http/*` fxs registered at ns-load time, the in-flight request registry, the Fetch / HttpClient transport adapters, the encode / decode pipeline, the retry-with-backoff machinery, the eight-category `:rf.http/*` failure taxonomy, and the `with-managed-request-stubs` test helper. The core artefact (`day8/re-frame2`) no longer carries any of this; apps that don't issue managed-HTTP requests build an `:advanced` bundle clean of every `:rf.http/*` symbol and trace string. See [MIGRATION §M-31](../migration/from-re-frame-v1/README.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http) for the deps swap.

## Role

`:rf.http/managed`, when an implementation ships it, is **framework-provided** — the implementation registers the fx; applications use it the way they'd use `:dispatch` or `:db`. This is what makes it a Spec rather than a convention: the public contract is locked, `:fx-overrides` target the same id across applications, pair tools introspect the same envelope, and the same schema language Spec 010 standardises ([Malli on the CLJS reference](010-Schemas.md#default-validator-and-the-validator-fn-extension-point)) is consumed by the `:decode` pipeline universally.

## The shape

Single fx-id, single args map. Co-located request and reply handling is the default; the user can opt out by providing explicit `:on-success` / `:on-failure` targets.

```clojure
(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply path — same handler, different branch.
      (case (:kind reply)
        :success
        {:db (-> db
                 (assoc-in [:article :status] :loaded)
                 (assoc-in [:article :data]   (:value reply))
                 (assoc-in [:article :error]  nil))}

        :failure
        {:db (-> db
                 (assoc-in [:article :status] :error)
                 (assoc-in [:article :error]  (:failure reply)))})

      ;; Initial dispatch — issue the managed request.
      {:db (-> db
               (assoc-in [:article :status] :loading)
               (assoc-in [:article :error]  nil))
       :fx [[:rf.http/managed
             {:request {:method :get
                        :url    (str "/articles/" slug)}
              :decode  ArticleResponse
              :accept  (fn [decoded]
                         (if-let [article (:article decoded)]
                           {:ok article}
                           {:failure {:reason :missing-article
                                      :message "Response missing :article"}}))
              :retry   {:on           #{:rf.http/transport :rf.http/http-5xx}
                        :max-attempts 4
                        :backoff      {:base-ms 250
                                       :factor  2
                                       :max-ms  5000
                                       :jitter  true}}}]]})))
```

When the request resolves, the runtime dispatches `[:article/load (assoc msg :rf/reply {:kind :success :value article})]` (or `:failure` shape) back to the same event id. The handler's `(if-let [reply ...] ...)` branch handles the result.

## The args map

| Key | Required? | Type | Purpose |
|---|---|---|---|
| `:request` | yes | map | The request envelope (see [§Request envelope](#request-envelope)). |
| `:decode` | no | spec / fn / `:json` / `:text` / `:blob` / `:array-buffer` / `:form-data` / `:auto` | How to parse the response body (see [§Decoding](#decoding)). Default: `:auto` (content-type sniffing). |
| `:accept` | no | fn `(decoded → {:ok v} | {:failure m})` | Post-decode normalisation; lets a handler treat a structurally-valid 200 as a domain failure. Default: `(fn [v] {:ok v})` for 2xx, structural failure otherwise. |
| `:retry` | no | map | Retry policy (see [§Retry and backoff](#retry-and-backoff)). Default: no retry. |
| `:timeout-ms` | no | int / `nil` / `0` | Wall-clock timeout per attempt. **Default: 30000** when the key is absent. Per [§`:timeout-ms` security defaults](#timeout-ms-security-defaults-rf2-it1cd) — `:timeout-ms nil` and `:timeout-ms 0` are explicit opt-outs (no per-attempt timeout). Apps facing untrusted upstreams SHOULD leave the default in place. |
| `:on-success` | no | event vector | Where to dispatch on success. Default: back to originating event id with `:rf/reply` merged. |
| `:on-failure` | no | event vector or `nil` | Where to dispatch on failure. Default: back to originating event id with `:rf/reply` merged. `nil` means swallow silently. |
| `:request-id` | no | any `=`-comparable value | Stable id for abort + correlation (see [§Aborts](#aborts)). Keywords (`:search`), strings (`"req-42"`), vectors (`[:articles :load 7]`), uuids — anything the runtime can `=`-compare. The fx stores in-flight requests in a `{request-id → request-handle}` map; identity is structural. |
| `:abort-signal` | no | external `AbortController.signal` | External abort handle. Mutually exclusive with `:request-id`-driven internal abort. |

## Request envelope

The `:request` map carries the wire shape. Keys are minimal and chosen to be host-portable:

| Key | Required? | Type | Notes |
|---|---|---|---|
| `:method` | no | `:get` / `:head` / `:post` / `:put` / `:patch` / `:delete` / `:options` | Default: `:get`. |
| `:url` | yes | string | May contain `:params`-derived query string (see below). |
| `:headers` | no | map of string → string (or string → vector of strings for multi-valued) | Headers to send. Names are case-insensitive. |
| `:params` | no | map | Query-string params. Encoded URL-safely; merged onto `:url`. Per Spec 012 §URL-encoding rules. |
| `:body` | no | clj coll / string / `FormData` / `Blob` / `ArrayBuffer` / **thunk `(fn [] body)`** | The request body. See [§Body encoding](#body-encoding). A thunk is invoked at request-send time (after backoff delays elapse), so very-large payloads aren't held in memory between dispatch and send and retries can re-invoke for a fresh handle. |
| `:request-content-type` | no | `:json` / `:form` / `:text` / explicit MIME / `nil` | Sugar for setting `Content-Type` + serialising `:body`. `:json` runs `pr-str → JSON.stringify` (CLJS) / Cheshire (JVM). `:form` URL-encodes a clj map. |
| `:credentials` | no | `:omit` / `:same-origin` / `:include` | Default: `:same-origin`. |
| `:mode` | no | `:cors` / `:no-cors` / `:same-origin` / `:navigate` | CLJS-only; Fetch passthrough. JVM ignores. |
| `:redirect` | no | `:follow` / `:error` / `:manual` | Default: `:follow`. |
| `:cache` | no | Fetch cache directive | CLJS-only passthrough. |
| `:referrer` | no | string | CLJS-only passthrough. |
| `:integrity` | no | string (subresource-integrity hash) | CLJS-only passthrough. |

### Body encoding

If `:body` is a thunk `(fn [] body)`, the fx invokes it just before sending (after `:retry :backoff` delays elapse). Each retry re-invokes the thunk to obtain a fresh handle — useful when `:body` is a single-shot stream that can't be replayed. Whatever the thunk returns is then encoded per the rules below.

If `:body` is a Clojure collection AND `:request-content-type` is unset, the fx inspects:

- If `:request-content-type :json` (or detected JSON acceptance via headers) → `JSON.stringify` after `clj->js` with `:keywordize-keys`-aware shape preservation.
- If `:request-content-type :form` → URL-encoded form body, sets `Content-Type: application/x-www-form-urlencoded`.
- If `:request-content-type :text` → coerce to string.
- Otherwise: pass through (the user is supplying a `Blob`/`FormData`/`ArrayBuffer`).

Multipart upload: pass `(js/FormData.)` directly as `:body` (or as the return value of a thunk) and let the runtime not set `Content-Type` (the platform sets the boundary).

## Decoding

The `:decode` key controls how the response body is parsed.

**Decode runs only on success-eligible (2xx) responses.** Status classification happens *before* decode — see [§Classification order](#classification-order). On a 4xx or 5xx the body is surfaced raw (as the response text) under the `:body` tag of `:rf.http/http-4xx` / `:rf.http/http-5xx`; the `:decode` pipeline is skipped entirely. This means a JSON endpoint that returns an HTML 404 from the load balancer surfaces as `:rf.http/http-4xx` with the HTML at `:body`, NOT as `:rf.http/decode-failure`.

### Schema-driven (the canonical form)

Pass a Malli schema as `:decode`:

```clojure
:decode ArticleResponse
```

The fx:
1. Reads the response body as text.
2. Parses by content-type (JSON if `application/json`; declared MIME otherwise).
3. Validates / coerces with Malli's `decode` against the schema.
4. Hands the decoded value to `:accept`.

If a 2xx response's body fails to decode (transport-OK, status-OK, but malformed payload), the fx classifies it as `:rf.http/decode-failure` and routes through the failure path. Decode never runs on a 4xx/5xx — see [§Classification order](#classification-order).

### Explicit content type

```clojure
:decode :json     ;; force JSON parsing
:decode :text     ;; raw string
:decode :blob     ;; binary blob
:decode :array-buffer
:decode :form-data
```

No Malli step. The user gets the raw decoded value in `:accept`.

### Custom function

```clojure
:decode (fn [response-text headers] decoded)
```

Full control. Throwing inside this fn (on a 2xx response — it doesn't run on non-2xx) classifies as `:rf.http/decode-failure`.

### `:auto` (default)

Sniff the response Content-Type header:
- `application/json*` → `:json`.
- `text/*` → `:text`.
- otherwise → `:blob`.

Handles 90% of cases without ceremony. Whenever the runtime falls through to `:auto` (i.e., the user didn't supply `:decode`), it emits a single `:rf.warning/decode-defaulted` trace per request so the choice is visible in tooling and logs:

```clojure
{:operation :rf.warning/decode-defaulted
 :op-type   :warning
 :tags      {:request-id  <id-or-nil>
             :url         <url>
             :content-type <header-value>
             :resolved-decoder <:json | :text | :blob>}}
```

The warning is informational, not an error — auto-decode is supported and stable. The trace just lets pair tools and 10x panels surface "this handler is relying on the default" so users can choose to be explicit when they want.

### Schema reflection (optional, ergonomic)

Pair tools, generators, and AI-assisted tooling want to know which schemas a handler expects from the wire — without invoking the handler. The user can declare them at registration time via the `:rf.http/decode-schemas` metadata key:

```clojure
(rf/reg-event-fx :article/load
  {:doc                    "Load an article."
   :rf.http/decode-schemas [ArticleResponse]}     ;; declared up-front for tooling
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      ...
      {:fx [[:rf.http/managed
             {:request {:url (str "/articles/" slug)}
              :decode  ArticleResponse}]]})))     ;; same schema at the call site
```

Then `(rf/handler-meta :event :article/load)` returns a map carrying `:rf.http/decode-schemas [ArticleResponse]`, which pair tools / `(rf/registrations :event)` enumeration / generators can introspect.

**Optional, never enforced.** The runtime does NOT cross-check that the call-site `:decode` matches the declared schemas — the metadata is reflective sugar for tooling, not a runtime contract. A handler that declares one schema and uses another still works. (If you want runtime enforcement, you're really asking for a `defmanaged-event-fx` macro that DRY's the declaration and the call-site reference; out of v1 scope.)

For handlers that issue multiple `:rf.http/managed` requests with different schemas, list all of them: `:rf.http/decode-schemas [ArticleResponse CommentList Profile]`.

### Keyword-interning cap (rf2-wu1n5)

JSON object keys are decoded as Clojure keywords. On the JVM, keywords are interned and never garbage-collected — a compromised upstream returning N unique-key JSON per response would permanently burn N keyword slots per response. Long-running JVMs (SSR, webhook receivers, agent-controlled fetches) are the worst case.

The decoder enforces a per-request cap on the number of unique object keys decoded. Overflow throws `:rf.http/decode-failure` with `:reason :too-many-keys` and the configured `:limit`.

| Args-map key | Default | Notes |
|---|---|---|
| `:rf.http/max-decoded-keys` | 10000 | Per-request cap on unique JSON object keys. Throws `:rf.http/decode-failure :reason :too-many-keys` on overflow. |

The cap applies to both the Cheshire path and the pure-Clojure fallback reader. 10000 is generous — legitimate APIs typically expose tens to low-hundreds of distinct key names per response. Apps that knowingly consume larger-cardinality payloads can raise the cap per request:

```clojure
{:fx [[:rf.http/managed
       {:request                  {:url "/api/bigmap"}
        :decode                   :json
        :rf.http/max-decoded-keys 50000}]]}
```

The cap is the SECOND line of defence; the FIRST line is `:request-content-type` / `:decode :text` for endpoints that don't need keywordization (`(get response "key")` works fine over string-keyed maps). For untrusted-origin JSON, prefer `:decode :text` + explicit parsing into a string-keyed map.

> Cross-reference: see [Security.md §Input validation / boundary parsing](Security.md#input-validation--boundary-parsing) and [Security.md §DoS by input](Security.md#dos-by-input) for the framework-wide posture this section grounds.

### JSON decoder hardening (rf2-263km, rf2-dgsu1)

The CLJS reference depends on a **hardened third-party JSON parser** (Cheshire on the JVM; the host's native `JSON.parse` on the browser) rather than a hand-rolled reader. Per rf2-dgsu1: the project's hand-rolled JSON fallback was deleted in favour of the hardened dep; the framework does not own a parser it would have to keep hardened against malformed-input classes.

Ports that ship a **hand-rolled** JSON / EDN reader (rather than depending on a hardened third-party parser) own the input-bounds contract directly. Per rf2-263km, the reader MUST bounds-check unicode-escape sequences (`\uXXXX`) and surface structured `:rf.error/malformed-json` with `:reason` slots (e.g., `:reason :truncated-unicode-escape`, `:reason :invalid-hex-digit`) rather than letting truncated or invalid escapes become opaque host errors.

These contracts compose with the keyword-interning cap above: hardened parser → bounds-checks → cap on cardinality → caller-controllable per-request override. Per rf2-263km / rf2-dgsu1 and [Security.md §Input validation / boundary parsing](Security.md#input-validation--boundary-parsing).

### `:timeout-ms` security defaults (rf2-it1cd)

The fx applies a 30000 ms per-attempt wall-clock timeout when `:timeout-ms` is absent from the args map. This is a security default: a slow-loris upstream (compromised partner, hostile webhook recipient, stalled CDN edge) that never completes the response body would otherwise pin a `CompletableFuture` (JVM) / Fetch promise (CLJS) indefinitely; in a long-running JVM the in-flight registry fills with hung requests until the connection pool is exhausted. With the default in place, every attempt has a finite deadline.

Two explicit opt-outs are honoured for callers who genuinely need unbounded reads:

| Value | Semantic |
|---|---|
| key absent | apply default (30000 ms) |
| `:timeout-ms 30000` (or any int) | apply the supplied value |
| `:timeout-ms nil` | **opt out** — no per-attempt timeout |
| `:timeout-ms 0` | **opt out** — no per-attempt timeout |

Both opt-outs are deliberate (passing `nil` or `0` is not idiomatic; the call-site author has signalled intent). Apps facing **any** untrusted upstream — partner JSON APIs, webhook receivers, agent-controlled fetches, third-party SaaS integrations — SHOULD leave the default in place. Apps that need a stricter bound can lower it per-request (`:timeout-ms 5000`).

The 10-second **connect** timeout is configured separately at the shared JDK HttpClient build site and is not user-overridable per request (connect timeout governs the TCP handshake, not body read).

## `:accept` — domain-failure normalisation

After decoding, the user's `:accept` fn classifies the decoded value:

```clojure
:accept (fn [decoded]
          (if-let [article (:article decoded)]
            {:ok article}
            {:failure {:kind :payload :message "Response missing :article"}}))
```

Returns either:
- `{:ok value}` — success; `value` is the payload of the success reply.
- `{:failure failure-map}` — domain failure; `failure-map` is the payload of the failure reply.

The default `:accept` returns `{:ok decoded}` for 2xx responses, `{:failure {:kind :http-status :status N :body decoded}}` otherwise.

## Retry and backoff

`:rf.http/managed`'s `:retry` slot owns **transport-level retry only** — retries whose decision is a pure function of the failure category and the attempt count. Network errors, 5xx, per-attempt timeouts, CORS rejection: each is a `:rf.http/*` category the runtime classifies before decode, and the policy is "given attempt N and a category from `:on`, wait `backoff(N)` ms, then re-issue the same request." The failure category, the attempt count, and the configured backoff are the only inputs; the response body, the application state, and the outcome of any other request never enter the picture.

This is deliberate. Retry decisions that depend on more than category + attempt are **semantic retry** — the response body says "rate-limited, try again with the new token", or the application is in a state that gates whether to re-issue, or another in-flight request's outcome decides whether this one should retry. Semantic retry is owned by **state machines** (Spec 005), not by `:rf.http/managed`. See [§Boundary — transport vs semantic retry](#boundary--transport-vs-semantic-retry) below.

```clojure
:retry {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
        :max-attempts 4
        :backoff      {:base-ms 250
                       :factor  2
                       :max-ms  5000
                       :jitter  true}}
```

| Key | Type | Purpose |
|---|---|---|
| `:on` | set of retryable-category keywords | Which failure categories trigger a retry. **Closed set** — must be drawn exclusively from the *retryable* subset of [§Failure categories](#failure-categories-closed-set): `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`. Common defaults: `#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}`. See [§Closed-set `:retry :on` validation](#closed-set-retry-on-validation--rf2-apwkm) below. |
| `:max-attempts` | int | Total attempts including the first. `1` = no retry. Default: 1. |
| `:backoff` | map | Exponential backoff config. |
| `:backoff.:base-ms` | int | Initial delay (ms). |
| `:backoff.:factor` | num | Multiplier per attempt. |
| `:backoff.:max-ms` | int | Cap on delay. |
| `:backoff.:jitter` | bool | Add random ±25% jitter to each delay. |

#### Closed-set `:retry :on` validation — rf2-apwkm

`:retry :on` is restricted to the **retryable subset** of the failure-category vocabulary:

```clojure
#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}
```

The other `:rf.http/*` categories from [§Failure categories](#failure-categories-closed-set) are **non-retryable by construction** and rejected when they appear in `:retry :on`:

| Category | Why excluded |
|---|---|
| `:rf.http/aborted` | A cancelled request MUST NOT issue a fresh attempt under any retry policy — re-issuing it would violate Spec 014 §Abort precedence. Abort always wins. |
| `:rf.http/decode-failure` | The next attempt would deterministically reproduce the same schema-validation / parser failure for the same response shape — retrying buys nothing and burns attempts. |
| `:rf.http/accept-failure` | A `{:failure user-map}` projection from `:accept` is the caller's own classification of a successful transport + decode; retrying the transport will not change the response body. Domain-level retry of this shape belongs to a state machine (see [§Boundary — transport vs semantic retry](#boundary--transport-vs-semantic-retry)), not to `:retry`. |

Implementations **MUST validate `:retry :on` at fx-call time** (when the `:rf.http/managed` fx body is invoked, before any attempt is issued). A non-empty intersection between `:on` and the rejected set throws an `:rf.error/http-bad-retry-on` ex-info, per [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue). This catches the misuse at the dispatch site rather than silently letting a useless retry policy ride for the request's lifetime (or, for `:rf.http/aborted`, deferring the rejection to retry-attempt time inside the transport loop). Membership outside the full `:rf.http/*` namespace is also rejected — the set is closed.

Per the rf2-apwkm spec tighten: implementations MUST NOT accept the old open `:rf.http/*` set. The rejection is hard, not advisory.

Each retry advances the carried epoch (per Pattern-StaleDetection); a stale request (e.g. one whose target route changed mid-retry) is suppressed without dispatching the reply.

### Boundary — transport vs semantic retry

The retry-ownership rule, stated as a single test: **does the retry decision depend on anything other than the failure category and the attempt count?** If no, `:rf.http/managed` `:retry` is the right home. If yes, lift the retry into a state machine.

| Decision shape | Owner | How |
|---|---|---|
| "After a 503, wait `backoff(N)` and try again — up to N times." | `:rf.http/managed` `:retry` | Function of attempt count + category. Transport. |
| "After a network timeout, retry with exponential backoff." | `:rf.http/managed` `:retry` | Function of attempt count + category. Transport. |
| "After a 401, refresh the token, **then** retry the original request." | State machine | Response-conditional; another request must succeed first. Semantic. |
| "If the response body says `{:error \"rate-limited\" :retry-after 5}`, wait the body's hinted delay." | State machine | Body-conditional. Semantic. |
| "Retry the failed write only if the user is still on the page that issued it." | State machine | App-state-conditional. Semantic. |
| "Retry every load-asset call that failed during boot, but not if the user navigated away." | State machine | App-state-conditional, joined across multiple requests. Semantic. |

**Why the split.** Transport retry is mechanical — every category's retry policy is the same shape, and the runtime can express it as a config map at the call site. Semantic retry is a **state transition with side-effecting prerequisites** — refreshing a token, checking app state, joining outcomes across requests. Encoding that into `:retry` would either bloat the slot's vocabulary (predicates over response body, dispatched-effect callbacks per attempt, nested conditions) or hide the control flow inside an opaque blob that doesn't show up in traces. Spec 005's machines already give the substrate for "transition on outcome with guards and entry actions"; semantic retry is just a state-machine transition, and the trace stream sees every decision.

**The escape hatch.** When you reach for "retry-on body matches X", "retry-after refresh", or "retry-when app-state says go" — stop, lift the call site into a state machine state, give that state a `:spawn` of `:rf.http/managed` (per [Pattern-AsyncEffect](Pattern-AsyncEffect.md)), and write the semantic retry as a transition on the failure reply. The machine handles the conditional logic; `:rf.http/managed` keeps doing transport retry inside each attempt the machine launches. Both halves compose — a state machine that drives `:rf.http/managed` requests can still configure transport-level `:retry` on each of those requests; the machine's semantic retry sits *outside* the per-request retry loop.

See [Pattern-Boot §Worked example — auth-machine and the retry-ownership boundary](Pattern-Boot.md#worked-example--auth-machine-and-the-retry-ownership-boundary) for a concrete demonstration of both halves working together (the auth flow that motivated the boundary in the first place — 5xx-retry-with-backoff at the transport layer, 401-vs-refresh at the semantic layer).

### Retry × `:on-failure` semantics

**Only the final exhausted-retries failure dispatches `:on-failure`.** Intermediate attempts that match `:retry :on` do NOT dispatch the failure handler — the user sees the success reply if any attempt succeeds, and exactly one failure reply (with `:max-attempts` reached) if every attempt fails.

For debugging visibility, **each intermediate attempt emits a `:rf.http/retry-attempt` trace event** with the attempt number, the failure category, and the planned backoff delay before the next attempt:

```clojure
{:operation :rf.http/retry-attempt
 :op-type   :info
 :tags      {:request-id   <id-or-nil>
             :url          <url>
             :attempt      <n>           ;; 1-based; the failing attempt
             :max-attempts <max>
             :failure      {:kind <:rf.http/*> ...kind-tags...}
             :next-backoff-ms <ms>}}     ;; nil on final exhaustion
```

Pair tools and 10x panels surface the per-attempt trace; user code only sees the final outcome through `:on-failure` (or the default reply-to-origin path).

## Classification order

When a response arrives, the runtime classifies the outcome in this fixed order:

1. **Transport / timeout / abort.** A network error, per-attempt timeout, or abort short-circuits the rest. Classified as `:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, or `:rf.http/aborted`. The body never enters the picture. Per [§Abort precedence (abort always wins)](#abort-precedence-abort-always-wins--rf2-wez75) abort dominates the rest of this list — a request marked aborted always classifies as `:rf.http/aborted` regardless of any later-arriving decode / status / transport observation for the same request.
2. **HTTP status.** Once a response lands, status is checked **before** the body is touched.
   - `2xx` → success-eligible; proceed to decode.
   - `4xx` → `:rf.http/http-4xx`; the raw response text is surfaced at `:body`. Decode is skipped.
   - `5xx` → `:rf.http/http-5xx`; same shape as `:http-4xx`. Decode is skipped.
   - Anything else (a 1xx/3xx the runtime didn't follow) → `:rf.http/http-4xx`-shaped.
3. **Decode** (only on 2xx). The configured `:decode` runs against the body. A throw / Malli rejection / parser error here classifies as `:rf.http/decode-failure`.
4. **Accept** (only on a successful decode). The configured `:accept` (or the default) projects the decoded value to `{:ok v}` or `{:failure m}`. A `{:failure m}` here classifies as `:rf.http/accept-failure`.

The order is **status-before-decode by design**: a JSON-API endpoint that returns an HTML 404 from a load balancer (or a CORS pre-flight 4xx with a generic HTML body, or a 503 with a Cloudfront error page) classifies as `:rf.http/http-4xx` / `:rf.http/http-5xx` with the raw body at `:body`, not as `:rf.http/decode-failure`. The HTTP failure category is the load-bearing piece of information for the caller; surfacing decode-failure on a 4xx would hide the real error.

If a caller wants to see the structured error body that an API returns alongside a non-2xx (e.g., `{"error": "..."}` JSON on a 4xx), the caller decodes the raw `:body` themselves in the failure-handling branch — the framework hands you the bytes and the status, and you decide what to do with them.

### Abort precedence (abort always wins) — rf2-wez75

**Abort always wins.** Once a request is marked aborted (via [`:rf.http/managed-abort`](#request-id-internal), an external [`:abort-signal`](#abort-signal-external), or the [actor-destroy hook](#abort-on-actor-destroy)), classification short-circuits to `:rf.http/aborted` / `:reason :actor-destroyed` regardless of any subsequent decode, transport, status, or accept-projection state observed for the same request — including outcomes whose underlying transport completion arrived in the same scheduler tick as the abort. Implementations MUST NOT surface `:rf.http/decode-failure`, `:rf.http/transport`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, or `:rf.http/accept-failure` on a request that has been aborted; the abort observation wins by classification, not by race ordering.

This pins the universal cancellation convention (Fetch `AbortController` rejects with `AbortError`; Node HTTP's `req.destroy()`; JVM `HttpClient.cancel`; gRPC's `CANCELLED` status). User code that issued an abort sees an `:rf.http/aborted` reply — period — and never has to disambiguate "did my abort actually happen, or did the response just barely beat it?".

The implementation seam is two-layered: an `:aborted?` cell on the in-flight handle is flipped by the abort path BEFORE racing the once-only `:finalised?` CAS; the natural-completion paths (`finalise-success!` / `finalise-failure!`) sample the cell AFTER winning the CAS and reclassify the would-be reply before dispatch. `maybe-retry!` additionally refuses to schedule a retry once the cell is flipped — a request the caller has cancelled MUST NOT issue a fresh attempt under any retry policy.

## Failure categories (closed set)

Every failure carries a `:kind` keyword (under the framework-reserved `:rf.http/*` namespace) plus category-specific tags. `:kind` is **framework-owned**; user payloads (from `:accept`) sit at `:detail`, never at `:kind`.

| `:kind` | When | Tags |
|---|---|---|
| `:rf.http/transport` | Network / DNS / connection-refused / connection-reset error before the HTTP transaction completed | `:message`, `:cause` |
| `:rf.http/cors` | CORS preflight rejected or response blocked by browser CORS policy. Distinct from `:transport` because CORS is a configuration error, not a network error. CLJS-only; JVM never emits this. | `:message`, `:url` |
| `:rf.http/timeout` | Per-attempt timeout fired | `:elapsed-ms`, `:limit-ms` |
| `:rf.http/http-4xx` | Non-2xx 4xx response | `:status`, `:status-text`, `:body` (the raw response text — decode is skipped on non-2xx; see [§Classification order](#classification-order)), `:headers` |
| `:rf.http/http-5xx` | Non-2xx 5xx response | same as `:http-4xx` |
| `:rf.http/decode-failure` | A success-eligible (2xx) response whose body the decode pipeline rejected (schema validation error, JSON syntax error, custom decoder threw). Non-2xx responses never produce `:rf.http/decode-failure` — they classify by status. | `:body-text`, `:cause`, `:schema-validation-failure?` |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}`. The user's failure map sits at `:detail`; `:decoded` carries the pre-`:accept` decoded value for context. | `:detail` (user's verbatim failure map), `:decoded` |
| `:rf.http/aborted` | The request was aborted via `:request-id` or `:abort-signal` | `:request-id` (if any), `:reason` (`:user` on the reply; `:request-id-superseded` is trace-only — see [§`:request-id` (internal)](#request-id-internal)) |

The category vocabulary is **closed for v1** — additions require a Spec change. The `:rf.http/*` namespace makes these unambiguous wherever they leak: trace events, error projector, `:retry :on` sets, epoch records.

#### CORS classification — heuristic emission (rf2-r40km)

`:rf.http/cors` was specced as a distinct category from `:rf.http/transport` but went un-emitted for a release cycle: browsers opaque CORS rejections and the runtime classified every browser-side rejection as `:rf.http/transport`. Per rf2-r40km (Option a — heuristic emission), the CLJS reference now emits `:rf.http/cors` when the rejection shape is a `TypeError` against a cross-origin URL (the strongest signal the browser surfaces without dropping to the network panel). The classifier ships with conformance tests that pin the heuristic + the `:rf.http/cors` `:retry :on` membership. JVM never emits this category — host CORS belongs to the browser fetch stack. Per rf2-r40km and [Security.md §Input validation / boundary parsing](Security.md#input-validation--boundary-parsing) (CORS classification row in the catalogue references).

> Cross-reference: see [Security.md §What is explicitly out of scope](Security.md#what-is-explicitly-out-of-scope) — CORS itself is a host-platform concern; the framework classifies the rejection but does not configure CORS.

### Reply payload shape

A failure reply lands as:

```clojure
{:rf/reply {:kind    :failure
            :failure {:kind <one of :rf.http/*>
                      ;; ...kind-specific tags above...
                      :detail <user-payload-if-:accept-failure>}}}
```

A success reply lands as:

```clojure
{:rf/reply {:kind  :success
            :value <decoded-and-accepted-payload>}}
```

The two outer-`:kind` values (`:success` / `:failure`) discriminate the reply branch; the inner `:kind` (under `:failure`) names the failure category. Both `:kind`s are framework-owned and unqualified — they live inside `:rf/reply`, where the framework is the sole writer.

## Reply addressing

The `:on-success` / `:on-failure` keys default to "the originating event id with `:rf/reply` merged into the original message".

### Default (omitted) — co-located handler

```clojure
{:fx [[:rf.http/managed {:request {...} :decode ...}]]}
```

The fx captures the originating event-id (from the dispatch envelope's cofx). On reply, dispatches:

```clojure
[<originating-event-id> (assoc original-msg :rf/reply {:kind :success :value v})]
```

The handler's body is `(if-let [reply (:rf/reply msg)] ...handle... ...request...)`. One handler, two roles, distinguishable by the `:rf/reply` sentinel.

### Explicit target — separate handler

```clojure
{:fx [[:rf.http/managed {... :on-success [:article/loaded] :on-failure [:article/load-error]}]]}
```

The `:rf/reply` payload is appended as the last argument to the dispatched event vector:

```clojure
[:article/loaded {:kind :success :value v}]
```

Both addressing modes carry the same shape so handlers can correlate by inspecting either `(:rf/reply msg)` (in the merged form) or the appended last-arg (in the explicit form).

### Silenced

```clojure
:on-success nil
:on-failure nil
```

Fire-and-forget. Useful for telemetry beacons.

## Aborts

Two mechanisms:

### `:request-id` (internal)

Pass any `=`-comparable value as a stable id — keyword, string, vector, uuid:

```clojure
:request-id :article/load                    ;; keyword
:request-id "req-42"                          ;; string
:request-id [:articles :load slug]            ;; structural
:request-id (random-uuid)                     ;; uuid
```

A subsequent `:rf.http/managed-abort` fx with the same id (compared by `=`) cancels the in-flight request, dispatching `:on-failure` with `:kind :rf.http/aborted`:

```clojure
{:fx [[:rf.http/managed-abort :article/load]]}
{:fx [[:rf.http/managed-abort [:articles :load "hello"]]]}
```

When a fresh request supersedes a prior one with the same `:request-id`, the prior request's `:on-failure` reply is **not dispatched** — semantically the new request *replaces* the old one (the debounce-search mental model). The supersede event still emits to the trace bus (`:rf.http/aborted` with `:reason :request-id-superseded`); consumers wanting abort telemetry subscribe via `register-trace-listener!` at `:warning` or `:error` severity. A manual `:rf.http/managed-abort` aborts whichever request currently holds the id and DOES dispatch `:on-failure` with `:reason :user`.

### `:abort-signal` (external)

Pass an `AbortController.signal` directly:

```clojure
:abort-signal (.-signal my-controller)
```

The fx threads the signal through to the underlying transport. User owns the controller's lifecycle. CLJS-only (Fetch supports it; XHR fallback ignores).

The two are mutually exclusive — pick one.

### Abort on actor destroy

Per [Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) (rf2-wvkn), `:rf.http/managed` requests issued from inside a spawned state-machine actor are aborted automatically when the actor is destroyed.

#### The contract

When a `:rf.http/managed` fx is processed, the runtime captures the **originating event vector** from the dispatch envelope (the `:event` value on the fx ctx, per [Spec 002 §Routing the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope)). The first element of that vector is the event-id that dispatched the request — for events fired into a spawned actor's handler, that first element is the spawned actor's address (e.g. `:http/post#1`).

The fx records the (`request-id`, `actor-id`) pair in its in-flight registry alongside the abort handle. When the spawned actor is later destroyed (any of the destroy triggers per [Spec 005 §The contract](005-StateMachines.md#the-contract)), the runtime invokes the late-bind hook `:http/abort-on-actor-destroy` with the destroyed actor's address. The hook walks the in-flight registry, identifies every request whose actor-id matches, and aborts each — synthesising a standard `:rf.http/aborted` failure with `:reason :actor-destroyed`.

#### Failure shape

The aborted reply is the same shape as a manual-abort failure:

```clojure
{:rf/reply {:kind    :failure
            :failure {:kind       :rf.http/aborted
                      :request-id <id-or-nil>
                      :reason     :actor-destroyed
                      :actor-id   <destroyed-spawned-actor-id>}}}
```

The discriminator from a user-issued abort is `:reason` — `:user` (manual `:rf.http/managed-abort`) or `:actor-destroyed` (this contract). Callers that branch on `:reason` recover that distinction; callers that don't see one uniform "aborted" outcome. (The third reason value, `:request-id-superseded`, never lands on a reply dispatch — per [§`:request-id` (internal)](#request-id-internal) supersede suppresses the prior request's reply and emits only to the trace bus.)

The reply lands at the originating handler exactly as any other reply does (per [§Reply addressing](#reply-addressing)). For requests issued by a spawned actor whose handler the destroy already unregistered, the dispatch is a no-op — the actor's snapshot is gone and there is no event handler to receive the reply. The trace event still fires; the abort is still observable through instrumentation.

#### Multiple in-flight requests per actor

A spawned actor may issue multiple `:rf.http/managed` requests in its lifetime. The actor-destroy hook walks **every** in-flight request whose actor-id matches and aborts each. There is no fairness or ordering guarantee between the aborts; the trace stream sees one `:rf.http/aborted-on-actor-destroy` per cancelled request.

#### Sibling actors are not affected

When actor A is destroyed, only A's in-flight requests are aborted. Actor B's in-flight requests — including under the same `:spawn-all` if `:cancel-on-decision? false` and B has not yet been told to stop — are unaffected.

`:spawn-all`'s cancel-on-decision (per [Spec 005 §Cancel-on-decision](005-StateMachines.md#cancel-on-decision-default-true)) emits one `:rf.machine/destroy` per surviving sibling, so each sibling's HTTP cascade independently fires the `:http/abort-on-actor-destroy` hook against its own actor-id.

#### Direct dispatches from event handlers — NOT covered

Per the spec 005 cross-feature contract, `:rf.http/managed` requests dispatched directly from ordinary `reg-event-fx` handlers — i.e. NOT from inside a spawned actor's event handler — are NOT subject to actor-destroy cancellation. The originating event vector's first element is an ordinary registered event-id, not a spawned-actor address; there is no actor-id to correlate against.

This is **deliberate.** Cancellation tied to actor lifetime is the right scope: the child actor exists to run until the parent says "we no longer care"; the parent destroying the actor kills its outstanding work. Ordinary event handlers have no analogous lifecycle peg — their work is launched as a side effect and outlives the handler.

Apps that want HTTP requests tied to the lifetime of a state-machine state should issue them from inside a spawned child machine, using `:spawn` or `:spawn-all` to bind the child's lifetime to the state's. The `:rf.http/managed-abort` fx and the user-supplied `:request-id` remain available for app-level cancellation of direct-dispatched requests (per [§`:request-id` (internal)](#request-id-internal)).

#### Trace event

`:rf.http/aborted-on-actor-destroy` (per [Spec 009 §Trace events](009-Instrumentation.md)) fires once per cancelled request. `:tags` carry `:request-id`, `:actor-id`, and `:url`.

#### Cross-references

- [Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) — the machine side of the contract.
- [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — `:rf.http/aborted-on-actor-destroy` taxonomy entry.
- [`:request-id` (internal)](#request-id-internal) — the orthogonal app-level abort surface.
- Boot-as-state-machine §M2 (rf2-wvkn) — the original gap analysis motivating this contract.

## Frame awareness

The reply dispatch lands in the **same frame** the request was issued from. The fx captures `:frame` from the dispatch envelope's cofx (per Spec 002 §Routing) and threads it through to the reply dispatch's `{:frame ...}` opt. Multi-frame apps work without extra ceremony.

## Middleware

Per [rf2-6y3q](#) — apps repeatedly want to apply a transform to every outgoing `:rf.http/managed` request: attach a Bearer token, stamp a correlation-id, rewrite a base URL in dev. v1 ships a **per-frame request-side interceptor chain** that sits between the user's args and the transport.

### Shape

Per rf2-eyjbn the public surface is `(reg-http-interceptor id opts? before)` — positional `id` keyword, positional `before` fn `(fn [ctx] ctx')`, and an optional `opts` map carrying `:frame` (default `:rf/default`) plus `:rf/registration-metadata` (per [Spec-Schemas §`:rf/http-interceptor-meta`](Spec-Schemas.md#rfhttp-interceptor-meta)): `:doc` / `:tags` / `:schema` / `:sensitive?`. Source-coords (`:ns` / `:line` / `:column` / `:file`) are auto-captured at the call site per [Spec 001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference). The shape aligns with the rest of the `reg-*` family — matching `reg-flow`'s precedent.

```clojure
(rf/reg-http-interceptor
  :auth-header
  {:doc "Stamp Bearer <token> on every outgoing request."}
  (fn [ctx]
    (let [token (-> (rf/get-frame-db (:frame ctx)) :auth :token)]
      (cond-> ctx
        token (assoc-in [:request :headers "Authorization"]
                        (str "Bearer " token))))))
```

### `ctx` contract

Each `:before` receives a context map with these keys:

| Key | Type | Notes |
|---|---|---|
| `:request` | map | The `:request` envelope per [§Request envelope](#request-envelope). `:before` returns a ctx whose `:request` is the modified envelope. |
| `:args` | map | The full `:rf.http/managed` args map (`:request` plus `:decode` / `:accept` / `:retry` / `:on-success` / ...). Read-only by convention; the only field the runtime threads onto the transport is `:request`. |
| `:frame` | keyword | The resolved frame id. |
| `:event` | vector | The originating event vector (or `[:rf.http/managed]` when not threaded). |

The fn returns the (possibly-modified) ctx. The runtime threads its `:request` onto the next interceptor (or onto the transport when the chain is exhausted).

### Chain order and frame scope

- **Registration order.** The chain runs in the order `reg-http-interceptor` calls were made on that frame. Re-registering an existing id replaces the slot **in place** — the position is preserved.
- **Clear-then-re-register.** `clear-http-interceptor` removes the slot entirely; a subsequent `reg-http-interceptor` of the same id is a fresh registration and **appends to the end** of the chain. The position is *not* preserved across a clear — the slot's prior index is forgotten on clear. Tools that want to mutate-in-place (e.g. hot-reload) should call `reg-http-interceptor` directly without clearing first; tools that want a fresh end-of-chain slot use clear-then-reg explicitly.
- **Per-frame.** An interceptor registered against frame A does NOT fire for a request dispatched from frame B. Multi-frame apps register independent chains per frame; the auth interceptor on the user-app frame doesn't leak into a hypothetical admin-app frame.
- **`:before`-only in v1.** Response-side transforms (the moral equivalent of an `:after`) are out of scope for v1 — sticking with the request-side keeps the contract small. The `:after` slot is reserved for future extension; an interceptor map carrying `:after` registers cleanly today (the runtime ignores the key) and will compose with v2's response-side hook when it lands.

### Failure mode

A throw inside any `:before` classifies as `:rf.error/http-interceptor-failed`. The runtime:

1. Emits a `:rf.error/http-interceptor-failed` trace event with `:frame`, `:interceptor-id`, `:url`, and `:cause` tags (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)).
2. Re-throws the wrapped ex-info, which the `re-frame.fx` outer catch converts to `:rf.error/fx-handler-exception` (so `:rf.fx/handled` does NOT fire).
3. Does NOT dispatch the request — the transport never sees it.

Pair tools and 10x panels see exactly two traces per interceptor failure: the per-interceptor `:rf.error/http-interceptor-failed` (which carries `:interceptor-id`) and the cascade-level `:rf.error/fx-handler-exception` (which carries `:fx-id :rf.http/managed`). Apps that want to recover gracefully wrap the throwing logic inside the `:before` itself — the chain has no recovery cofx.

### Clearing

`(rf/clear-http-interceptor id)` removes the slot on `:rf/default`; `(rf/clear-http-interceptor frame-id id)` targets a specific frame. The single-arity is the common case (single-frame apps); the two-arity is unambiguous for multi-frame.

Hot-reload tools that re-evaluate registration call sites get the right behaviour automatically: re-`reg-http-interceptor` of an existing id replaces the slot in place.

### Trace events

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded. Tags: `:frame`, `:id`. |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing slot (no trace fires for a clear-of-unknown-id). Tags: `:frame`, `:id`. |
| `:rf.error/http-interceptor-failed` | `:error` | A `:before` threw; see [§Failure mode](#failure-mode). Tags: `:frame`, `:interceptor-id`, `:url`, `:cause`. |

### Example — Bearer auth with a single registration

```clojure
(rf/reg-http-interceptor
  :app/bearer-auth
  (fn [ctx]
    (let [token (-> (rf/get-frame-db (:frame ctx)) :auth :token)]
      (cond-> ctx
        token (assoc-in [:request :headers "Authorization"]
                        (str "Bearer " token))))))

;; All subsequent `:rf.http/managed` requests on `:rf/default` carry the
;; header automatically — no per-call-site threading. The interceptor
;; reads the auth slice on every request, so token rotation is picked
;; up without re-registration.

(rf/reg-event-fx :articles/list
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request {:url "/articles"}                ;; no auth threading
            :decode  ArticleListResponse}]]}))
```

### Public surface

| API | Kind | Signature |
|---|---|---|
| `reg-http-interceptor` | Fn | `(rf/reg-http-interceptor id before)` / `(rf/reg-http-interceptor id opts before)` — per rf2-eyjbn (positional id + opts kwarg + positional handler) |
| `clear-http-interceptor` | Fn | `(rf/clear-http-interceptor id)` / `(rf/clear-http-interceptor frame id)` |

Both are re-exported from `re-frame.core`. Both ship in `day8/re-frame2-http`; an app that omits the artefact gets `:rf.error/http-artefact-missing` from the core re-exports per the standard pattern.

## Call-site helpers

The canonical `[:rf.http/managed args-map]` envelope is correct and complete, but the args map carries 12+ keys and every call site repeats `{:request {:method <verb> :url <url>} ...}` boilerplate. The `re-frame.http` namespace ships pure synthesis fns — one per HTTP verb — that build the canonical fx vector from a URL + an optional args map. Result:

```clojure
;; Without helpers — the canonical form, always supported:
{:fx [[:rf.http/managed {:request {:method :get :url "/api/items"}
                         :on-success [:items/loaded]}]]}

;; With helpers — same envelope, fewer keys at the call site:
{:fx [(rf.http/get "/api/items" {:on-success [:items/loaded]})]}
```

### Surface

```clojure
(:require [re-frame.http :as rf.http])

(rf.http/get     url)  (rf.http/get     url args)
(rf.http/post    url)  (rf.http/post    url args)
(rf.http/put     url)  (rf.http/put     url args)
(rf.http/delete  url)  (rf.http/delete  url args)
(rf.http/patch   url)  (rf.http/patch   url args)
(rf.http/head    url)  (rf.http/head    url args)
(rf.http/options url)  (rf.http/options url args)
```

Each helper returns a `[:rf.http/managed args-map]` vector ready to drop into `:fx`. The helper pins `(:method (:request args-map))` to its verb's keyword and `(:url (:request args-map))` to the URL argument; caller-supplied `:method` / `:url` under `:request` are overwritten so the call-site contract reads cleanly.

| Helper | `(:request (:method ...))` pinned to |
|---|---|
| `rf.http/get`     | `:get`     |
| `rf.http/post`    | `:post`    |
| `rf.http/put`     | `:put`     |
| `rf.http/delete`  | `:delete`  |
| `rf.http/patch`   | `:patch`   |
| `rf.http/head`    | `:head`    |
| `rf.http/options` | `:options` |

### Args-map merging

Top-level keys (`:decode`, `:accept`, `:retry`, `:timeout-ms`, `:on-success`, `:on-failure`, `:request-id`, `:abort-signal`, etc.) pass through to the args map unchanged. The `:request` map is itself merged with the helper's `{:method <verb> :url url}` pair (helper wins on `:method` and `:url`; caller supplies `:headers`, `:body`, `:params`, `:credentials`, etc.).

```clojure
(rf.http/post "/api/items"
              {:request    {:body new-item :request-content-type :json}
               :on-success [:items/created]
               :on-failure [:items/create-failed]})
;; ↓ expands to ↓
[:rf.http/managed
 {:request    {:method :post :url "/api/items"
               :body   new-item :request-content-type :json}
  :on-success [:items/created]
  :on-failure [:items/create-failed]}]
```

### Naming

`get` collides with `clojure.core/get`; the namespace does `(:refer-clojure :exclude [get])` internally. Users alias the namespace (`[re-frame.http :as rf.http]`) and write `(rf.http/get ...)` — the alias is what makes the bare verb names readable. The other verbs (`post`, `put`, `delete`, `patch`, `head`, `options`) don't collide with `clojure.core`.

### Artefact

Ships in `day8/re-frame2-http` alongside the `:rf.http/managed` fx the helpers reference. Loading the helpers and the fx are a single dep decision; an app that omits the http artefact can't call the helpers in the first place (compile-time `ns` failure) rather than discovering at dispatch time that `:rf.http/managed` isn't registered.

The helpers are NOT re-exported from `re-frame.core` — users explicitly `(:require [re-frame.http :as rf.http])`. Re-exporting under the `rf/` segment would lose the `rf.http/` namespace prefix that makes the call site read as "an HTTP GET" rather than "some framework get".

## Examples

### A — Simplest possible (sugar all the way down)

```clojure
(rf/reg-event-fx :ping
  (fn [{:keys [db]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :pinged-at (:elapsed-at reply))}
      {:fx [[:rf.http/managed {:request {:url "/ping"}}]]})))   ;; :method defaults to :get
```

No decode (default `:auto`), no accept (default success-on-2xx), no retry, default reply addressing. Two-line fx.

### B — Schema-driven with retry

```clojure
(rf/reg-event-fx :articles/list
  (fn [{:keys [db]} [_ {:keys [page] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      (case (:kind reply)
        :success {:db (assoc-in db [:articles :data] (:value reply))}
        :failure {:db (assoc-in db [:articles :error] (:failure reply))})
      {:fx [[:rf.http/managed
             {:request {:method :get
                        :url    "/articles"
                        :params {:page page :page-size 20}}
              :decode  ArticleListResponse
              :retry   {:on           #{:rf.http/transport :rf.http/http-5xx}
                        :max-attempts 3
                        :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}}}]]})))
```

### C — POST with form body and explicit error handler

```clojure
(rf/reg-event-fx :auth/login
  (fn [{:keys [db]} [_ creds]]
    {:fx [[:rf.http/managed
           {:request {:method :post
                      :url    "/auth/login"
                      :body   creds
                      :request-content-type :json}
            :decode  AuthResponse
            :on-success [:auth/login-success]
            :on-failure [:auth/login-error]}]]}))
```

The auth flow has separate success/error handlers (often a state machine), so the co-located shape doesn't fit.

### D — Multipart upload, no retry, custom decode

```clojure
{:fx [[:rf.http/managed
       {:request {:method :post
                  :url    "/upload"
                  :body   form-data}
        :decode  (fn [text headers]
                   {:upload-id (re-find #"id=([0-9a-f]+)" text)})
        :timeout-ms 60000}]]}
```

### E — Aborting a stale search

```clojure
(rf/reg-event-fx :search/query
  (fn [{:keys [db]} [_ {:keys [q] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; ...handle results...
      {:fx [[:rf.http/managed-abort :search]                 ;; cancel previous
            [:rf.http/managed
             {:request    {:method :get :url "/search" :params {:q q}}
              :request-id :search
              :decode     SearchResponse}]]})))
```

The `:rf.http/managed-abort` fx cancels any in-flight `:request-id :search`, then a fresh request fires.

### F — Same flows, with the call-site helpers

```clojure
(:require [re-frame.http :as rf.http])

;; A — minimal GET, default reply addressing:
(rf/reg-event-fx :ping
  (fn [{:keys [db]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :pinged-at (:elapsed-at reply))}
      {:fx [(rf.http/get "/ping")]})))                       ;; ← 1 line vs 1 envelope

;; B — schema-driven GET with retry:
(rf/reg-event-fx :articles/list
  (fn [{:keys [db]} [_ {:keys [page] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      ...
      {:fx [(rf.http/get "/articles"
                         {:request {:params {:page page :page-size 20}}
                          :decode  ArticleListResponse
                          :retry   {:on           #{:rf.http/transport :rf.http/http-5xx}
                                    :max-attempts 3
                                    :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}}})]})))

;; C — POST with body and explicit reply targets:
(rf/reg-event-fx :auth/login
  (fn [{:keys [db]} [_ creds]]
    {:fx [(rf.http/post "/auth/login"
                        {:request    {:body creds :request-content-type :json}
                         :decode     AuthResponse
                         :on-success [:auth/login-success]
                         :on-failure [:auth/login-error]})]}))

;; E — aborting a stale search:
(rf/reg-event-fx :search/query
  (fn [{:keys [db]} [_ {:keys [q] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      ...
      {:fx [[:rf.http/managed-abort :search]
            (rf.http/get "/search"
                         {:request    {:params {:q q}}
                          :request-id :search
                          :decode     SearchResponse})]})))
```

The fx vectors the helpers synthesise are exactly the same shape as the hand-written versions in §A–§E above; `:fx-overrides`, `with-managed-request-stubs`, the trace stream, and pair tools see no difference. The helpers are call-site sugar over the same canonical envelope.

## Testing

`:fx-overrides` redirects `:rf.http/managed` to a stub for tests. The framework ships **two canonical stub fxs** so tests don't have to roll their own:

```clojure
;; Success stub — dispatches the configured success reply.
(rf/dispatch-sync [:article/load {:slug "hello"}]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}})

;; Failure stub — dispatches the configured failure reply.
(rf/dispatch-sync [:article/load {:slug "missing"}]
                  {:fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}})
```

| Stub fx-id | Behaviour |
|---|---|
| `:rf.http/managed-canned-success` | Synthesises a success reply. Args take `:value` (the payload to put under `:rf/reply :value`); defaults to a literal `{:stubbed true}`. |
| `:rf.http/managed-canned-failure` | Synthesises a failure reply. Args take `:kind` (one of `:rf.http/*`; default `:rf.http/transport`) and `:tags` (the kind-specific tags map; defaults documented per row of [§Failure categories](#failure-categories-closed-set)). |

The stubs reuse the same dispatch shape the real fx produces so the test handler's reply branch sees the canonical envelope. Same pattern as the existing http-stub idiom (see `examples_test.clj` and `ssr_end_to_end_test.clj` for prior art).

### Test-support require — the canned-stub gate (rf2-cdmle)

The two canned-stub fxs above are **test-only**; production / SSR code paths must not be able to reach them via `:fx-overrides`. The framework gates registration behind an explicit require:

```clojure
(ns my-app.tests
  (:require [re-frame.http-managed]        ;; production fx surface
            [re-frame.http-test-support])) ;; canned-stub fx registrations
```

Loading `re-frame.http-test-support` registers `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure` against the same handler bodies the higher-level `with-managed-request-stubs` helper uses. Without the require:

- on JVM / SSR the fx ids are unregistered (classpath absence through the normal artefact require boundary), so any handler that tries `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` will surface the framework's no-such-fx error;
- on CLJS `:advanced + goog.DEBUG=false` the test-support module is unreferenced from any production module, so the compiler trims it wholesale (the canned-stub fx-id keyword string fragments do not appear in the production bundle — pinned by `scripts/check-elision.cjs`).

Earlier the gate was `(when interop/debug-enabled? ...)` inside `re-frame.http-managed` itself; on the JVM that gate folded to `true`, leaving the canned-stub fx ids reachable as production-default API. Per [rf2-zk08x](#)'s audit and the [rf2-cdmle](#) remediation, the gate moved to the require boundary so the absence is enforced on every host.

Code that uses `with-managed-request-stubs` / `install-managed-request-stubs!` does **not** need the test-support require — those helpers register their own `:rf.http/managed-test-stub` fx at user invocation time, independent of the canned-stub fx ids.

For test suites that exercise many requests, a higher-level helper ships:

```clojure
(rf/with-managed-request-stubs
  {[:get "/articles/hello"] {:reply {:ok hello-article}}
   [:get "/articles/missing"] {:reply {:failure {:kind :rf.http/http-4xx :status 404}}}}
  ...)
```

The helper inspects each `:rf.http/managed` invocation's `:request :method` + `:request :url` and routes through the configured reply.

### In-flight registry test helpers

For test suites that need to inspect or reset the in-flight request registry directly (e.g. fixtures that share state across `dispatch-sync` calls, or property-based tests that need a clean slate between iterations), three test-time helpers ship in `re-frame.http-managed`:

| Helper | Signature | Purpose |
|---|---|---|
| `clear-all-in-flight!` | `(clear-all-in-flight!)` → nil | Drops both the request-id-keyed and actor-id-keyed in-flight maps. Consumed by `re-frame.test-support/reset-runtime-fixture-factory` to restore a clean registry between tests; the `:http/clear-all-in-flight!` hook is published via the late-bind table so `test-support` can call it without statically requiring the http artefact. |
| `in-flight-snapshot` | `(in-flight-snapshot)` → map | Reads the current value of the request-id-keyed in-flight map. For tests that need to assert "this request-id is in flight" without poking the atom directly. |
| `actor-in-flight-snapshot` | `(actor-in-flight-snapshot)` → map | Reads the current value of the actor-id-keyed in-flight map (per [§Abort on actor destroy](#abort-on-actor-destroy) and rf2-wvkn). For tests that need to assert the actor → request-id reverse index. |

These are **test-only** surfaces — not part of the user-facing API for production code paths. Application code SHOULD route through `:rf.http/managed` and the dispatch-shape replies; the helpers exist so test fixtures can observe and reset registry state without reaching into the namespace's atoms.

## Machine-shape wrapper

Per [rf2-ijm7](#) — `:rf.http/managed` is **also** registered as a child-invokable state machine, so a parent machine ca `:spawn` it without writing any glue. The wrapper is **additive** on top of the fx surface: `:fx [[:rf.http/managed args]]` continues to work unchanged ([§The shape](#the-shape) is the canonical user-facing surface); the machine wrapper is a second affordance for callers who are already inside a state-machine envelope and want a child machine they can compose with `:spawn`, `:after`, and the cancellation cascade.

### The pattern

```clojure
(rf/reg-machine :app/auth
  {:initial :idle
   :states
   {:idle           {:on {:login :authenticating}}

    :authenticating
    {:spawn {:machine-id :rf.http/managed
              :data       {:request {:method :get :url "/api/me"}
                           :decode  :json}}
     :after  {30000 :timed-out}                ;; wall-clock guard
     :on     {:succeeded :authenticated
              :failed    :login-failed}}

    :authenticated  {}
    :login-failed   {}
    :timed-out      {}}})
```

While in `:authenticating`, a child wrapper actor of `:rf.http/managed` is alive at `[:rf/machines :rf.http/managed#N]`. It issues the request on entry; on the reply it transitions to its `:succeeded` / `:failed` terminal state and dispatches `[<parent-id> [:succeeded value]]` (or `[<parent-id> [:failed failure]]`) back to the parent — which the parent's `:on` map handles as ordinary FSM events.

### Wrapper spec

Internally the wrapper machine has:

| key | value |
|---|---|
| `:initial` | `:requesting` |
| `:states` | three leaves — `:requesting`, `:succeeded`, `:failed` |

`:requesting` listens for three events:

- `:rf.machine/spawned` — the synthetic event the runtime dispatches to spawns without a `:start` (per [Spec 005 §Spawning](005-StateMachines.md#spawning--dynamic-actors)). The wrapper's `:fire-request` action runs, emitting the underlying `:rf.http/managed` fx with `:on-success` / `:on-failure` pointing back at the wrapper actor's own id (so the reply lands at the wrapper, not at the user's handler).
- `:rf.http/succeeded` — fired when the underlying fx succeeds; records the reply payload at `:data :rf/result` and transitions to `:succeeded`.
- `:rf.http/failed` — fired when the underlying fx fails (any of the eight `:rf.http/*` failure categories, per [§Failure categories](#failure-categories-closed-set)); records the reply payload and transitions to `:failed`.

The terminal states' `:entry` dispatches `[<parent-id> [:succeeded value]]` or `[<parent-id> [:failed failure]]` — where `value` is the decoded-and-accepted payload (the same `(:value (:rf/reply msg))` an ordinary fx reply carries) and `failure` is the standard failure map (the same `(:failure (:rf/reply msg))` shape per [§Reply payload shape](#reply-payload-shape)). The parent's id comes from `:rf/parent-id` in the wrapper actor's initial `:data` — stamped by `spawn-fx` per [Spec 005 §Spawning](005-StateMachines.md#spawning--dynamic-actors); the wrapper need not be told its parent at spec-write time.

### Args carrier

Every key the [§The args map](#the-args-map) surface accepts may be passed through the parent's `:spawn :data`:

```clojure
{:spawn {:machine-id :rf.http/managed
          :data {:request    {:method :post :url "/api/sessions" :body {...}}
                 :request-content-type :json
                 :decode     SessionResponse
                 :accept     (fn [v] (if (:session v) {:ok (:session v)}
                                                       {:failure {:reason :no-session}}))
                 :retry      {:on #{:rf.http/transport :rf.http/http-5xx}
                              :max-attempts 4
                              :backoff {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}
                 :timeout-ms 30000}}
 :on     {:succeeded :authenticated
          :failed    :login-failed}}
```

The framework-reserved `:rf/*` keys the wrapper itself uses (`:rf/self-id`, `:rf/parent-id`, `:rf/spawn-id`, `:rf/result`) are stripped before the underlying fx call, so they never leak into the request envelope.

`:on-success` / `:on-failure` are **not** passed through — the wrapper overrides them to route the reply back to itself. Apps that want explicit reply addressing should keep using the fx form directly; the machine wrapper is for the `:spawn`-orchestrated case.

### Cancellation cascade

Per [§Abort on actor destroy](#abort-on-actor-destroy) (rf2-wvkn), the wrapper actor's in-flight request is automatically aborted when the wrapper is destroyed. The wrapper is destroyed:

- On any transition out of the parent's `:spawn`-bearing state (per [Spec 005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn)) — including the parent's `:after` firing (per [Spec 005 §Wall-clock timeouts on `:spawn` — use parent state's `:after`](005-StateMachines.md#wall-clock-timeouts-on-spawn--use-parent-states-after)).
- On parent-frame destroy.
- On imperative `:rf.machine/destroy` against the wrapper's actor id.

In every case, the standard `:http/abort-on-actor-destroy` late-bind hook fires, the in-flight HTTP aborts with `:reason :actor-destroyed`, and the `:rf.http/aborted-on-actor-destroy` trace event lands. The wrapper actor's failure dispatch back to the parent is suppressed because the wrapper's handler is unregistered before the abort's failure reply lands — the parent has already moved on by then, so no notification is needed.

### Multiple wrappers per parent

A parent that needs two parallel HTTP requests uses [Spec 005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) with `:rf.http/managed` named as the `:machine-id` for each child:

```clojure
{:hydrating
 {:spawn-all
  {:children       [{:id :user  :machine-id :rf.http/managed
                     :data {:request {:url "/api/me"}}}
                    {:id :prefs :machine-id :rf.http/managed
                     :data {:request {:url "/api/prefs"}}}]
   :join             :all
   :on-child-done    :asset/loaded
   :on-child-error   :asset/failed
   :on-all-complete  [:hydrate/done]
   :on-any-failed    [:hydrate/aborted]}}}
```

Each child gets its own wrapper actor; cancel-on-decision (default `true`) tears down survivors when the join resolves; per-sibling cancellation cascades fire the `:http/abort-on-actor-destroy` hook independently per [§Sibling actors are not affected](#sibling-actors-are-not-affected).

### When to use the fx form vs the machine form

| use case | use |
|---|---|
| Event handler issues a one-off request; reply lands at the handler or a sibling | **fx form**: `:fx [[:rf.http/managed args]]` |
| Parent state-machine wants the request tied to a specific state's lifetime, with abort-on-state-exit and `:after` timeout composition | **machine form**: `:spawn {:machine-id :rf.http/managed :data {...}}` |
| Parent state-machine wants multiple concurrent requests with a join condition | **machine form** under `:spawn-all` (per above) |

Apps may mix both freely. The two registrations coexist under `:rf.http/managed` in the registrar (`:fx` kind for the fx, `:event` kind for the machine).

## Privacy

Per [rf2-bma05](#) (motivated by the [rf2-ok47g §Completeness matrix G3](#) — the sensitive-elision audit). HTTP is the canonical privacy surface in any application: passwords ride request bodies, auth tokens ride request headers, user PII rides response bodies. Without honouring [Spec 009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces)'s `:sensitive?` contract on the `:rf.http/*` trace events, the HTTP cascade is the biggest leakage vector the framework ships.

Spec 014 specifies HTTP-side honouring on top of the Spec 009 contract: every `:rf.http/*` trace event MUST stamp `:sensitive?` when the originating handler is sensitive, MUST redact known-sensitive request headers regardless of handler sensitivity, and MUST redact request / response bodies when the request is sensitive. The contract layers as three cooperating pieces.

### 1. Header denylist (always-on)

A canonical set of HTTP header names is **always sensitive** — the names themselves declare the value secret regardless of the surrounding handler's `:sensitive?` flag. Implementations MUST redact (substitute the framework-reserved `:rf/redacted` sentinel per [Spec 009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces)) the values of these headers in every `:rf.http/*` trace event that carries a `:headers` slot. Header-name matching is **case-insensitive**.

The v1 closed denylist:

| Header name | Why |
|---|---|
| `Authorization` | Bearer tokens, Basic auth credentials |
| `Proxy-Authorization` | Proxy credentials |
| `Cookie` | Session identifiers |
| `Set-Cookie` | Session identifiers (response side) |
| `X-API-Key` | API key in the bearer-key idiom |
| `X-Auth-Token` | Bearer-token variant |
| `X-Session-Token` | Session-token variant |
| `X-CSRF-Token` | CSRF anti-forgery token |
| `X-XSRF-Token` | CSRF anti-forgery token (XSRF spelling) |
| `Authentication` | Some SaaS APIs use the non-standard spelling |
| `WWW-Authenticate` | Challenge response carries scheme + realm details |
| `Proxy-Authenticate` | Same as WWW-Authenticate at the proxy layer |

Apps extend the denylist for app-specific tokens (e.g. `X-Honeycomb-Team`, `X-Stripe-Signature`) via:

```clojure
(rf.http/declare-sensitive-header! "X-Honeycomb-Team")
```

Names stored lower-cased; matching is case-insensitive. The default denylist is fixed at boot; the app-extended set is mutable and clearable for test ergonomics via `(rf.http/clear-sensitive-headers!)`.

### 2. Query-param denylist (always-on) (rf2-2p8wr)

A parallel-axis canonical set of HTTP query-string **parameter names** is **always sensitive** — the names themselves declare the value secret regardless of the surrounding handler's `:sensitive?` flag. URLs in `:rf.http/*` trace events that carry a denylisted query-string parameter have the **value** redacted inline: `?api_key=SECRET&page=2` → `?api_key=:rf/redacted&page=2`. The parameter name and position are preserved so the operator can still see which endpoint was called and which parameters were present, but the secret value is replaced with the framework-reserved sentinel text. Parameter-name matching is **case-insensitive**.

The v1 closed denylist:

| Param name | Why |
|---|---|
| `api_key` / `apikey` / `api-key` | API key in URL query — common legacy idiom |
| `access_token` / `accesstoken` | Bearer-token idiom carried on the URL |
| `auth` / `auth_token` / `authtoken` | Generic auth-token names |
| `token` | Generic bearer-token name |
| `key` | Generic key name (covers `?key=...` API-key idioms) |
| `secret` | Generic secret-name |
| `password` / `passwd` | Password in URL — rare but seen on legacy POST-as-GET endpoints |
| `session` / `session_id` / `sessionid` | Session identifier carried on the URL |
| `signature` / `sig` / `hmac` | Signed-URL HMAC / signature value |

Apps extend the denylist for app-specific tokens (e.g. `shop_token` for Shopify, `signature` variants in webhook receivers) via:

```clojure
(rf.http/declare-sensitive-query-param! "shop_token")
```

Names stored lower-cased; matching is case-insensitive. The default denylist is fixed at boot; the app-extended set is mutable and clearable for test ergonomics via `(rf.http/clear-sensitive-query-params!)`.

A query-param denylist hit **alone** (no per-handler / per-call `:sensitive?`) **stamps `:sensitive? true`** on the resulting trace event — the presence of a denylisted parameter name is itself a signal that the request carries an auth secret, and downstream privacy-honouring consumers should treat the event accordingly. This is the analogue of the header denylist contract: the name is the signal.

### 3. Per-call / per-request / per-handler `:sensitive?`

Three OR-reduced sources contribute the request-side `:sensitive?` flag for a given `:rf.http/managed` invocation:

1. **Handler-level** — `:sensitive? true` on the originating event handler's `:rf/registration-metadata` map (per [Spec 009 §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key)). The conventional site: the event handler that owns the request. Every `:rf.http/managed` dispatched from within a `:sensitive?`-marked handler inherits the flag.

2. **Per-request** — `:sensitive? true` under the `:request` map of the `:rf.http/managed` args. For requests where the handler itself is not sensitive but **this specific call** is (e.g. a generic POST handler that becomes sensitive only when posting to `/auth/login`). Composes with `:request-content-type`, `:body`, etc. unchanged.

3. **Per-call** — `:sensitive? true` at the top level of the `:rf.http/managed` args map. Pragmatic sugar for callers that prefer the flag alongside `:on-success` / `:on-failure` rather than nested under `:request`. Semantically identical to per-request.

Any source set to `true` makes the request sensitive; all sources defaulting to `false`/absent means not sensitive. The runtime resolves the effective flag once at fx-invocation time and threads it through the attempt-and-retry loop so every `:rf.http/*` trace event the cascade emits sees the same flag (no per-emit re-resolution).

```clojure
;; Handler-level (Spec 009 §Privacy — the inherited form):
(rf/reg-event-fx :auth/sign-in
  {:doc        "Verify credentials and start a session."
   :sensitive? true}
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request {:method :post :url "/auth" :body creds}}]]}))

;; Per-request — a non-sensitive handler with one sensitive call:
(rf/reg-event-fx :api/proxy
  (fn [_ [_ {:keys [target body]}]]
    {:fx [[:rf.http/managed
           {:request    {:method :post :url target :body body
                         :sensitive? (= target "/auth/login")}}]]}))

;; Per-call — same effect, top-level:
(rf/reg-event-fx :api/login
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request    {:method :post :url "/auth/login" :body creds}
            :sensitive? true}]]}))
```

### 4. Trace-event redaction + stamping rules

For every `:rf.http/*` trace event the runtime emits (`:rf.http/retry-attempt`, `:rf.http/aborted-on-actor-destroy`, the eight `:rf.http/*` failure categories from [§Failure categories](#failure-categories-closed-set), `:rf.warning/decode-defaulted`), implementations MUST:

1. **Redact denylisted headers** in `:headers` slots regardless of the effective `:sensitive?` flag.
2. **Redact denylisted query-string parameter values** in `:url` slots regardless of the effective `:sensitive?` flag (rf2-2p8wr). Param-name + position preserved; the value is replaced inline with the `:rf/redacted` text token.
3. **Redact body / body-text / decoded / detail** slots when the effective `:sensitive?` is true. Specifically: `:body` (request and response), `:body-text` (decode-failure raw text), `:decoded` (the pre-`:accept` decoded value carried by `:rf.http/accept-failure`), and `:detail` (the user-supplied failure map carried by `:rf.http/accept-failure`). All slot values become `:rf/redacted`.
4. **Redact `:params`** (the structured query-string params map on the request side) when the effective `:sensitive?` is true. The whole `:params` map value becomes `:rf/redacted`.
5. **Redact ALL `:url` query-string param values** when the effective `:sensitive?` is true (broader rule than the always-on denylist) — when the request is sensitive, anything that rides the wire is. Non-denylisted params (e.g. `user_id=42`) are scrubbed alongside denylisted ones.
6. **Stamp `:sensitive?`** on the trace event per [Spec 009 §Trace-event field](009-Instrumentation.md#trace-event-field-sensitive-at-the-top-level). The canonical contract is that the flag rides at the top level of the trace envelope (consumers consult `(:sensitive? ev)` for a one-keyword read). The HTTP layer stamps `:sensitive? true` on the tags map passed to `trace/emit!` / `trace/emit-error!`. A **query-param denylist hit alone** (no per-handler / per-call `:sensitive?`) also stamps `:sensitive? true` — the denylisted name is itself the signal. If the core trace surface implements the [rf2-isdwf](#) hoist (Spec 009 §Privacy core-stamping), the flag is moved from tags to top-level by the emit walker; if core does not yet hoist, the flag stays under `:tags`. Once core lands the hoist universally, the tags-slot becomes redundant but harmless. Absent (NOT `false`) when not sensitive — per Spec 009 line 1176 "Consumers treat absent as false."

The cascade-wide stamping uses the **innermost in-scope handler** rule from [Spec 009 §Privacy](009-Instrumentation.md#the-sensitive-registration-metadata-key): each handler in a cascade contributes its own `:sensitive?` reading. A sensitive handler dispatching a non-sensitive child event does NOT transitively widen the flag — the HTTP fx fired inside the child handler's scope reflects the child's flag. The OR-reduce-by-cascade rollup is the consumer's responsibility (group by `:dispatch-id`).

### Composition

| Surface | Behaviour |
|---|---|
| × `:large?` ([Spec 009 §Size elision](009-Instrumentation.md#size-elision-in-traces)) | A trace-event slot that is BOTH sensitive AND large drops (no `:rf.size/large-elided` marker — the marker would leak `:path` / `:bytes` / `:digest`). Sensitive wins per Spec 009's unified `rf/elide-wire-value` walker. |
| × `redact-interceptor` (Spec 009 §Privacy) | `redact-interceptor` operates on event-vector slots; the HTTP redactor operates on `:rf.http/*` trace-event slots. Both compose additively — a handler that uses both gets event-vector redaction AND HTTP trace redaction. |
| × Spec 014 §Middleware | Request-side interceptors run **before** the privacy machinery reads `:sensitive?` (the interceptor chain may itself attach an `Authorization` header). Headers added by interceptors are subject to the same denylist. |
| × Spec 014 §Failure categories | Every category that carries body-side payload (`:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`) gets the redaction treatment when sensitive. `:rf.http/aborted` carries no body so no body redaction; headers (the denylist) still apply. |
| × Spec 005 actor-destroy abort | The in-flight handle propagates the effective `:sensitive?` flag, so the `:rf.http/aborted-on-actor-destroy` emit (issued from the registry namespace, distant from the originating fx ctx) still stamps correctly. |
| × WebSockets (future) | When `:rf.ws/*` (per [Pattern-WebSocket](Pattern-WebSocket.md)) lands, it inherits the same denylist + per-handler / per-call `:sensitive?` machinery; the per-message frame-stamping rule is its own affair, but the request-side concerns are shared. |

### Production elision

The HTTP privacy machinery rides the trace surface and elides with it:

- The redact / stamp helpers all gate on `interop/debug-enabled?` at their call sites (the same gate as `trace/emit!` and `trace/emit-error!`). In `:advanced` + `goog.DEBUG=false` builds Closure DCE removes the trace emits AND the redaction step that prepares them.
- The header denylist atom itself ships in production (it's read by `declare-sensitive-header!`). The walker only runs against it when a trace emit fires, so production builds that elide the trace surface incur no runtime cost.
- Handler-meta `:sensitive?` is no longer consulted (the annotation has been removed per rf2-hjs2d). Per-call `:sensitive?` on the `:rf.http/managed` args map is the supported per-request sensitivity opt-in.

### Cross-references

- [Spec 009 §Privacy / sensitive data in traces](009-Instrumentation.md#privacy--sensitive-data-in-traces) — the canonical `:sensitive?` contract this section extends into HTTP.
- [Spec 009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) — the parallel-axis `:large?` predicate; both share the unified `rf/elide-wire-value` walker.
- [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — every `:rf.http/*` failure-category row; the redaction rules above apply to each row's `:tags`.
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — the `:rf/redacted` sentinel keyword lives in the framework-reserved `:rf/` namespace.

## What Spec 014 does NOT cover

Adjacent surfaces that are first-class re-frame2 commitments but live in their own specs:

- **Streaming responses** (chunked HTTP, server-sent events). Different shape — per-chunk events, not single reply. Future `:rf.http/streaming` fx; sibling spec.
- **WebSocket** — bidirectional. Lives in [Pattern-WebSocket](Pattern-WebSocket.md); state-machine-shaped.
- **GraphQL-specific batching / persisted queries.** Layer on top — `:rf.http/managed` hands you the decoded response, your application wraps for batching.
- **HTTP/2 server push.** Not a re-frame2 concern; the platform handles it transparently.
- **Response-side interceptors (`:after`).** v1's middleware contract is request-side only ([§Middleware](#middleware), rf2-6y3q). Apps that want to project / log / retry on response paths use `:accept` (domain-failure normalisation) and the trace stream; a future `:after` slot composes additively when it lands.

## Open questions

> **SA-4 classification (rf2-p6xyh).** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): all four items classify as **`:post-v1 tracked`** — additive surfaces that do not block v1.

### Response-side middleware composition (post-v1, rf2-ean6m)

Per [§Middleware](#middleware) (rf2-6y3q) v1 ships request-side middleware only. A response-side `:after` slot — composing additively with `:accept` and `:before` — would let apps project / log / retry on response paths without per-event boilerplate. Deferred to rf2-ean6m until the request-side surface settles in practice and the composition order with `:accept` is decided.

### App-extensible query-param denylist (post-v1, rf2-j3nfp)

Per [§2. Query-param denylist (always-on)](#2-query-param-denylist-always-on-rf2-2p8wr) (rf2-2p8wr) the always-on query-string denylist is a fixed framework-owned set. An extensible registration surface (`rf.http/declare-sensitive-query-param!` parallel to the header denylist) is a natural addition — deferred to rf2-j3nfp until a real app surfaces a query-param-auth pattern outside the default set.

### Streaming responses (`:rf.http/streaming`) (post-v1, rf2-jg6by)

Per [§What Spec 014 does NOT cover](#what-spec-014-does-not-cover) streaming responses (chunked HTTP, server-sent events) ship in a sibling spec. The per-chunk event model is a different shape from the single-reply `:rf.http/managed` contract and needs its own envelope; the contract here remains the request → single-reply shape. Deferred to rf2-jg6by.

### Pluggable backoff strategy (post-v1, rf2-iul15)

Per [§Retry and backoff](#retry-and-backoff) v1 ships a fixed exponential-with-jitter backoff. Pluggable backoff (per-call strategy fn, registered named strategies, host-customisable defaults) is an additive surface — deferred to rf2-iul15 until apps surface a real need that the default doesn't cover.

## Resolved decisions

### `:rf.http/managed` is the canonical framework-provided fx

Per [§Implementation status](#implementation-status) `:rf.http/managed` is the locked v1 surface: args-map shape, failure categories, reply addressing, retry semantics, abort surface, schema-reflection metadata, and trace events are all locked across implementations. This was chosen over a "convention" (every app rolls its own HTTP fx) so `:fx-overrides` target the same id across applications, pair tools introspect the same envelope, Spec 010 schemas plug into the same decode pipeline, and conformance fixtures key off the canonical surface.

### Failure categories are a closed set

Per [§Failure categories (closed set)](#failure-categories-closed-set) the failure taxonomy under `:rf.http/*` (`:transport`, `:cors`, `:timeout`, `:http-4xx`, `:http-5xx`, `:decode-failure`, `:accept-failure`, `:aborted`) is closed for v1. Additions require a Spec change. Apps that want domain-level discrimination layer `:accept` (per [§`:accept` — domain-failure normalisation](#accept--domain-failure-normalisation)) — they don't extend the framework's failure taxonomy. This keeps the `:rf.http/*` trace vocabulary decidable for tools and the [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) finite.

### `:rf.http/cors` is CLJS-only

Per [§Failure categories (closed set)](#failure-categories-closed-set) the `:rf.http/cors` row is CLJS-only — JVM transports never emit it. CORS is a browser-policy concern; the JVM has no cross-origin policy to enforce. The asymmetry is documented so tools that consume the trace stream don't assume the row exists on every host.

### Request-side middleware only in v1 (rf2-6y3q)

Per [§Middleware](#middleware) (rf2-6y3q) the v1 middleware contract is per-frame request-side only — the interceptor chain sits between the user's args and the transport, not between the transport and the reply. The request-side cases (Bearer token, correlation-id, base-URL rewrite) all surfaced as the high-frequency pattern; response-side composition is deferred to [§Open questions](#open-questions). The request-side surface ships first because its shape is settled.

### Frame-aware reply dispatch (rf2-wvkn)

Per [§Frame awareness](#frame-awareness) every `:rf.http/managed` reply dispatch inherits the originating frame; replies route to the right frame even when the request was issued from a non-default frame (story variant, per-test fixture, SSR per-request). The frame-capture discipline matches [Pattern-AsyncEffect](Pattern-AsyncEffect.md) and is universal across the async-effect surface.

### Actor-destroy aborts in-flight requests (rf2-wvkn)

Per [§Aborts](#aborts) (rf2-wvkn) `:rf.http/managed` requests issued from inside a spawned state-machine actor are aborted automatically when the actor is destroyed. The actor-id-keyed in-flight map (per [§Abort on actor destroy](#abort-on-actor-destroy)) is the reverse index; `actor-in-flight-snapshot` is the test-only inspection helper. This was chosen over "orphan the request and ignore the reply" because orphaned requests waste transport quota and the reply path's frame-target may no longer exist — both costs grow under retries.

### Privacy honoured via `:sensitive?` on HTTP trace events (rf2-bma05)

Per [§Privacy](#privacy) (rf2-bma05) the `:rf.http/*` trace events honour the [Spec 009 §`:sensitive?`](009-Instrumentation.md#privacy--sensitive-data-in-traces) contract: per-call, per-request, and per-handler `:sensitive?` flags OR-reduce; the framework redacts request/response bodies and a 12-name header denylist (`authorization`, `cookie`, `set-cookie`, etc.). Headers were chosen as the always-on default surface because they carry the highest-value secrets (auth tokens) across the largest fraction of apps. Apps register their own sensitive headers via `rf.http/declare-sensitive-header!`.

### Query-string denylist is always-on (rf2-2p8wr)

Per [§2. Query-param denylist (always-on)](#2-query-param-denylist-always-on-rf2-2p8wr) (rf2-2p8wr) the framework redacts denylisted query-string parameter values in `:url` slots **regardless** of the effective `:sensitive?` flag. Param-name and position are preserved; the value is replaced inline with the `:rf/redacted` text token. Always-on was chosen over flag-gated because query-string-auth patterns (older REST APIs, webhooks) leak through `:rf.warning/decode-defaulted` and similar URL-carrying traces even when the dispatching event isn't `:sensitive?` — the redaction must run unconditionally for the URL slot to be safe.

### Stale-suppression piggy-backs on the epoch carry

Per [Pattern-StaleDetection](Pattern-StaleDetection.md) and [§Reply addressing](#reply-addressing) managed requests inherit the dispatching event's epoch carry; replies that arrive after a newer navigation / actor restart are suppressed at the dispatch site. The same epoch idiom is used by `:after` timers (per [Spec 005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection)) and route nav-tokens (per [Spec 012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)); the recurring pattern is documented in [Pattern-StaleDetection](Pattern-StaleDetection.md).

## Cross-references

- [Pattern-AsyncEffect](Pattern-AsyncEffect.md) — generic six-step async shape; Spec 014 specialises it.
- [Pattern-RemoteData](Pattern-RemoteData.md) — the 5-key request-lifecycle slice; `:rf.http/managed` writes through this slice.
- [Pattern-StaleDetection](Pattern-StaleDetection.md) — epoch carry; managed requests inherit it.
- [Spec 002 §Routing](002-Frames.md#routing-the-dispatch-envelope) — frame-aware fx contract; reply dispatches inherit `:frame`.
- [Spec 005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions) — the substrate semantic retry rides on; the machine fires a transition on the failure reply, optionally delays via `:after`, and re-issues the request from the next state's `:spawn`.
- [Spec 005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) — multi-request semantic retry (refresh-then-retry, fan-out-with-conditional-retry) lives here.
- [Spec 009 §Trace event envelope](009-Instrumentation.md) — trace envelope shape; `:rf.http/retry-attempt`, `:rf.warning/decode-defaulted`, and the `:rf.http/*` failure-category traces follow it.
- [Spec 010 §Schemas](010-Schemas.md) — the schema language `:decode <schema>` consumes. Spec 010 standardises the schema-attachment surface (`:spec` metadata, `reg-app-schema`, `app-schemas-digest`) and the pluggable validator seam (Malli is the CLJS reference's default); the `:rf.http/managed` decode step parses the response body and applies the registered schema language's decode-or-validate primitive (on CLJS reference: `malli.core/decode` + `malli.transform/json-transformer`). There is no separate "Spec 010 decode pipeline" — the decode contract belongs to this Spec; Spec 010 provides the schema language.
- [Pattern-Boot §Worked example — auth-machine and the retry-ownership boundary](Pattern-Boot.md#worked-example--auth-machine-and-the-retry-ownership-boundary) — the canonical end-to-end illustration of [§Boundary — transport vs semantic retry](#boundary--transport-vs-semantic-retry).
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — the `:rf.http/*` namespace is reserved for this Spec.
- [`re-frame-fetch-fx`](https://github.com/superstructor/re-frame-fetch-fx) — the inspiration; Spec 014 adds retry, accept-step, default-reply-addressing, schema-driven decode, JVM coverage, and stale-suppression on top.
