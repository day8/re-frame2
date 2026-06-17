# Spec 014 — HTTP requests

> Status: Drafting. **v1-optional capability.** Implementations MAY ship `:rf.http/managed`; when they do, the contract below is fixed. The CLJS reference implementation ships it (see [§Implementation status](#implementation-status)). Builds on the registration grammar in [001-Registration](001-Registration.md), the dispatch envelope and frame routing in [002-Frames §Routing](002-Frames.md#routing-the-dispatch-envelope), the trace-stream contract in [009-Instrumentation](009-Instrumentation.md), schema integration in [010-Schemas](010-Schemas.md), and the reserved-namespace policy in [Conventions](Conventions.md).
>
> **The minimum claim:** *if* an implementation ships HTTP-request infrastructure, it ships `:rf.http/managed` per this spec — a first-class HTTP request fx that bakes in decoding, success/failure normalisation, retry-with-backoff, abort, schema-driven decode, and reply-to-origin dispatch. The fx is rich enough that apps overwhelmingly want it; the contract being uniform is what lets pair tools, `:fx-overrides`, retry policy, error projection, and frame-aware reply addressing compose across implementations without per-app reinvention.
>
> **Code samples are in ClojureScript** (the CLJS reference). The contract is host-agnostic; the spec calls out per-host divergences (CLJS Fetch / JVM `java.net.http.HttpClient`) explicitly per row.
>
> `:rf.http/managed` is a **managed external effect** — per [Managed-Effects](Managed-Effects.md), the surface MUST satisfy the nine properties (effect-as-data, framework-owned lifecycle, structured failure taxonomy under `:rf.http/*`, trace-bus observability, `:sensitive?` / `:large?` composition, built-in retry / abort / teardown, in-flight registry, per-frame interceptor scoping, and — property 9 — the [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) for async completions, onto which managed HTTP [lowers its replies](#lowering-onto-the-uniform-reply-envelope)).

## Abstract

`:rf.http/managed` is the canonical HTTP fx for re-frame2 implementations that ship one. Take an args map, get a request issued; the fx handles transport, decoding, retry-with-backoff, and dispatching the reply back into the runtime. The recommended shape is **two explicit handlers** — `:on-success [:article/loaded]` / `:on-failure [:article/load-error]` — one event each for the request, the success, and the failure. Each handler stays small and single-purpose, and the failure path is named rather than folded into a branch. When the request and reply genuinely belong together, the fx also supports a **co-located** form: omit the handlers and one event handler branches on `(:rf/reply msg)` to serve both the initial dispatch and the async result.

The fx specialises [Pattern-AsyncEffect](Pattern-AsyncEffect.md)'s generic six-step shape (register → return `:fx` → post work → reply → dispatch → commit), pins the lifecycle slice from [Pattern-RemoteData](Pattern-RemoteData.md), and inherits the epoch carry from [Pattern-StaleDetection](Pattern-StaleDetection.md). It complements but does not replace the lower-level `:http` fx — apps that need wire-level control (custom transport, raw bytes, idiosyncratic protocols) keep using `:http`; apps that want the common case ergonomic use `:rf.http/managed`.

Streaming and bidirectional communication are deliberately out of scope here — Spec 014 covers the single-request / single-reply shape. WebSocket / SSE / chunked-streaming are handled by sibling specs (Pattern-WebSocket; future `:rf.http/streaming`).

## Implementation status

Spec 014 is an **optional capability** in the [000-Vision §Capability matrix](000-Vision.md) sense. Implementations MAY:

- **Ship `:rf.http/managed` per this spec.** Then the contract below applies — args map shape, failure categories, reply addressing, retry semantics, abort surface, schema-reflection metadata, and trace events all locked. Pair tools and conformance fixtures key off the canonical surface.
- **Omit it.** Applications that need HTTP roll their own fx (or use a third-party library) per [Pattern-AsyncEffect](Pattern-AsyncEffect.md)'s generic shape. The omission is a conformance-set difference, not a defect.

The **CLJS reference implementation ships `:rf.http/managed`**, backed by Fetch on the browser and `java.net.http.HttpClient` on the JVM. Other in-scope JS-cross-compile-language ports (TypeScript, Fable (F#), Scala.js, PureScript, Kotlin/JS, Melange / ReScript / Reason, Squint) decide independently — each typically wraps the host's binding to `fetch` (browser) and the host's runtime HTTP client on Node. A port that omits `:rf.http/managed` MUST NOT register the `:rf.http/*` namespace for any other purpose (it's reserved for this Spec; see [Conventions](Conventions.md)).

If an implementation ships ONLY a subset (e.g., no JVM transport), it claims the relevant capability rows and the conformance corpus exercises only those.

**Artefact (CLJS reference).** As a per-feature artefact split, the CLJS reference's managed-HTTP surface ships in the separate Maven artefact `day8/re-frame2-http` — `re-frame.http-managed` namespace, the production `:rf.http/managed` / `:rf.http/managed-abort` / `:rf.fx/reg-http-interceptor` / `:rf.fx/clear-http-interceptor` fxs registered at ns-load time, the in-flight request registry, the Fetch / HttpClient transport adapters, the encode / decode pipeline, the retry-with-backoff machinery, the eight-category `:rf.http/*` failure taxonomy, AND a sibling `re-frame.http-test-support` namespace (test-only) which carries the canned-stub fxs (`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`) and the `with-managed-request-stubs` family of macros / fns (the single discoverable home for HTTP test surfaces). The core artefact (`day8/re-frame2`) no longer carries any of this; apps that don't issue managed-HTTP requests build an `:advanced` bundle clean of every `:rf.http/*` symbol and trace string. See [MIGRATION §M-31](../migration/from-re-frame-v1/README.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http) for the deps swap.

## Role

`:rf.http/managed`, when an implementation ships it, is **framework-provided** — the implementation registers the fx; applications use it the way they'd use `:dispatch` or `:db`. This is what makes it a Spec rather than a convention: the public contract is locked, `:fx-overrides` target the same id across applications, pair tools introspect the same envelope, and the same schema language Spec 010 standardises ([Malli on the CLJS reference](010-Schemas.md#default-validator-and-the-validator-fn-extension-point)) is consumed by the `:decode` pipeline universally.

## The shape

Single fx-id, single args map. The recommended shape names two explicit reply targets — `:on-success` and `:on-failure` — so each of the three concerns (issue, succeed, fail) is its own small handler and the failure path is impossible to overlook. Omitting them opts into the co-located form below.

```clojure
;; Issue the request — name where the success and failure replies land.
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    {:db (-> db
             (assoc-in [:article :status] :loading)
             (assoc-in [:article :error]  nil))
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    (str "/articles/" slug)}
            :decode     ArticleResponse
            :accept     (fn [decoded]
                          (if-let [article (:article decoded)]
                            {:ok article}
                            {:failure {:reason  :missing-article
                                       :message "Response missing :article"}}))
            :retry      {:on           #{:rf.http/transport :rf.http/http-5xx}
                         :max-attempts 4
                         :backoff      {:base-ms 250
                                        :factor  2
                                        :max-ms  5000
                                        :jitter  true}}
            :on-success [:article/loaded]
            :on-failure [:article/load-error]}]]}))

;; Success reply — the payload rides as the last event arg.
(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :status] :loaded)
             (assoc-in [:article :data]   value)
             (assoc-in [:article :error]  nil))}))

;; Failure reply — named, not a branch.
(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
             (assoc-in [:article :status] :error)
             (assoc-in [:article :error]  failure))}))
```

When the request resolves, the runtime dispatches `[:article/loaded {:kind :success :value article}]` (or `[:article/load-error {:kind :failure :failure ...}]`) — the reply payload is appended as the last argument to the named event.

### Co-located form (request and reply in one handler)

When the request and its reply genuinely belong together, omit `:on-success` / `:on-failure` and the reply routes back to the originating event id with the payload merged under `:rf/reply`. One handler then branches on the `(:rf/reply msg)` sentinel to serve both roles:

```clojure
(rf/reg-event :article/load
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

When the request resolves, the runtime dispatches `[:article/load (assoc msg :rf/reply {:kind :success :value article})]` (or `:failure` shape) back to the same event id. The handler's `(if-let [reply ...] ...)` branch handles the result. The co-located form keeps one mental model per feature; the two-handler form keeps each handler single-purpose. Prefer two handlers unless the reply logic is trivial and tightly coupled to the request.

> Future consideration (out of scope here): a `defmanaged-event-fx`-style macro could collapse the three-handler boilerplate into a single declaration. Weighing it is deferred to post-v1 — it is not part of this spec.

## The args map

The `:rf.http/managed` fx accepts a single args map. The reference card below lists every public slot, its default, and — for slots that can cause a request to fail — the failure category the runtime classifies into. Each row anchors to its Spec 014 detail section.

| Slot | Default | Meaning | Recovery on failure |
|---|---|---|---|
| `:request` | required | The wire envelope — `:method` / `:url` / `:headers` / `:params` / `:body` / `:request-content-type` / `:credentials` / `:mode` / `:redirect` / `:cache` / `:referrer` / `:integrity` / `:sensitive?`. See [§Request envelope](#request-envelope). | A bad envelope surfaces as `:rf.http/transport`, `:rf.http/cors`, `:rf.http/http-4xx`, or `:rf.http/http-5xx` depending on how the host rejects it. |
| `:decode` | `:auto` | Response-body decoder: a Malli schema, a fn `(response-text headers → decoded)`, or one of `:json` / `:text` / `:blob` / `:array-buffer` / `:form-data` / `:auto`. Runs only on 2xx. See [§Decoding](#decoding). | `:rf.http/decode-failure` (schema reject, JSON parse, custom-fn throw). |
| `:accept` | `{:ok decoded}` | Post-decode normaliser `(decoded → {:ok v} | {:failure m})` — lets a structurally-valid 200 surface as a domain failure. Runs only after a successful 2xx decode (non-2xx classifies by status before decode, so `:accept` never sees it). See [§`:accept` — domain-failure normalisation](#accept--domain-failure-normalisation). | `:rf.http/accept-failure` (the user map rides at `:detail`). |
| `:retry` | no retry | Retry policy `{:on #{categories} :max-attempts N :backoff {:base-ms :factor :max-ms :jitter}}`. `:on` is a closed subset of `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`. See [§Retry and backoff](#retry-and-backoff). | Invalid `:retry :on` member → `:rf.error/http-bad-retry-on` at registration / dispatch time. Retries exhaust → the final failure category. |
| `:timeout-ms` | `30000` | Per-attempt wall-clock timeout in ms. `nil` or `0` opts out (no timeout). See [§`:timeout-ms` security defaults](#timeout-ms-security-defaults). | `:rf.http/timeout` when the budget elapses. |
| `:on-success` | originating event id with `:rf/reply` merged | Where to dispatch the success reply. See [§Reply addressing](#reply-addressing). | — (`:on-success` does not itself fail.) |
| `:on-failure` | originating event id with `:rf/reply` merged | Where to dispatch the failure reply. `nil` swallows silently. See [§Reply addressing](#reply-addressing). | — (`:on-failure` does not itself fail; it routes the reply.) |
| `:request-id` | none | Stable `=`-comparable id for abort + correlation. Keywords / strings / vectors / uuids all work. See [§`:request-id` (internal)](#request-id-internal). | Superseded by a later request with the same id → in-flight request aborts with `:rf.http/aborted :reason :request-id-superseded` on the trace stream. |
| `:abort-signal` | none | External `AbortController.signal` handle. Mutually exclusive with `:request-id`-driven internal abort. CLJS-only; JVM ignores. See [§`:abort-signal` (external)](#abort-signal-external). | `:rf.http/aborted :reason :user` when the host fires the signal. |
| `:sensitive?` | `false` | Marks the request body / headers / params / decoded value as sensitive for the trace stream. Honours [Spec 009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces). May also be set under `:request`; the top-level slot is sugar. See [§Privacy](#privacy). | — (privacy flag; does not affect classification.) |
| `:rf.http/max-decoded-keys` | `10000` | Per-request cap on the number of unique JSON object keys the decoder will intern. Second line of defence after `:decode :text` for untrusted-origin payloads. See [§Keyword-interning cap](#keyword-interning-cap). | `:rf.http/decode-failure :reason :too-many-keys` on overflow. |

Stub-mode slots (`:rf.http/canned-success` / `:rf.http/canned-failure` and the `with-managed-request-stubs` family) live in the sibling `re-frame.http-test-support` namespace and are documented in [§Testing](#testing); they are not part of the production args-map surface.

## Request envelope

The `:request` map carries the wire shape. Keys are minimal and chosen to be host-portable:

| Key | Required? | Type | Notes |
|---|---|---|---|
| `:method` | no | `:get` / `:head` / `:post` / `:put` / `:patch` / `:delete` / `:options` | Default: `:get`. |
| `:url` | yes | string | May contain `:params`-derived query string (see below). |
| `:headers` | no | map of string → string (or string → vector of strings for multi-valued) | Headers to send. Names are case-insensitive. An **invalid** header (an empty / control-char name, or a value carrying `\r`/`\n` — the response-splitting guard) is rejected by the host's header builder (JVM `java.net.http` `.header`, CLJS Fetch `Headers.append`). The runtime catches that rejection **per header**, emits one redacted `:rf.warning/http-header-invalid` trace naming the offending header (value omitted — values may carry secrets; URL privacy-composed), omits the bad pair, and proceeds with the remaining valid headers. The throw is handled **inside** the managed path on both hosts — it never escapes as a generic `:rf.error/fx-handler-exception`. |
| `:params` | no | map | Query-string params. Encoded URL-safely; merged onto `:url`. Per Spec 012 §URL-encoding rules. |
| `:body` | no | clj coll / string / `FormData` / `Blob` / `ArrayBuffer` / **thunk `(fn body)`** | The request body. See [§Body encoding](#body-encoding). A thunk is invoked at request-send time (after backoff delays elapse), so very-large payloads aren't held in memory between dispatch and send and retries can re-invoke for a fresh handle. |
| `:request-content-type` | no | `:json` / `:form` / `:text` / explicit MIME / `nil` | Sugar for setting `Content-Type` + serialising `:body`. `:json` runs `pr-str → JSON.stringify` (CLJS) / Cheshire (JVM). `:form` URL-encodes a clj map. |
| `:credentials` | no | `:omit` / `:same-origin` / `:include` | Default: `:same-origin`. CLJS-only; JVM ignores (see [§JVM transport](#jvm-transport--degraded-behaviour-for-cljs-only-options)). |
| `:mode` | no | `:cors` / `:no-cors` / `:same-origin` / `:navigate` | CLJS-only; Fetch passthrough. JVM ignores. |
| `:redirect` | no | `:follow` / `:error` / `:manual` | Default: `:follow`. |
| `:cache` | no | Fetch cache directive | CLJS-only passthrough. |
| `:referrer` | no | string | CLJS-only passthrough. |
| `:integrity` | no | string (subresource-integrity hash) | CLJS-only passthrough. |

`:url` is the only **required** key. It is validated at dispatch time — **after** the `:before` interceptor chain runs, so a `:before` that sets the url (e.g. a base-URL-prefix interceptor) is honoured. A request whose final `:url` is missing / nil / a non-string / a blank string is rejected at the dispatch site with a thrown `:rf.error/http-bad-request` ([Spec 009 §Error catalogue](009-Instrumentation.md#error-event-catalogue)) rather than being allowed to fall through to the transport (where a nil url surfaces as an opaque `:rf.http/transport` failure). This matches the dispatch-time guarding the optional keys already carry (`:retry :on` → `:rf.error/http-bad-retry-on`; `:on-success` / `:on-failure` → `:rf.error/http-bad-reply-target`).

#### JVM transport — degraded behaviour for CLJS-only options

Six keys on the args map / request envelope are **CLJS-only** — semantically meaningful against the browser Fetch API and ignored by the JVM's `java.net.http.HttpClient`-backed transport. A request that carries any of them on the JVM proceeds normally; the option is **silently no-op** and the runtime emits one `:rf.http/cljs-only-key-ignored-on-jvm` warning trace per occurrence so consumers (Xray, Story, off-box monitors) can spot the degraded code path:

| Key | Where | JVM behaviour |
|---|---|---|
| `:abort-signal` | args map (top-level) | Ignored. Internal `:request-id`-driven abort still works on the JVM; the external Fetch `AbortController.signal` handle has no `HttpClient` analogue and is dropped. |
| `:mode` | `:request` map | Ignored. CORS policy is browser-only. |
| `:cache` | `:request` map | Ignored. The Fetch cache directive has no `HttpClient` equivalent. |
| `:referrer` | `:request` map | Ignored. Browser-only request-context. |
| `:integrity` | `:request` map | Ignored. Subresource-integrity is a browser-only verification path. |
| `:credentials` | `:request` map | Ignored. The browser same-origin/`:include` cookie model has no faithful `HttpClient` analogue — the shared client configures no `CookieHandler`, so cookies are neither sent nor stored regardless of the value. (Unlike `:redirect`, which IS honoured on the JVM via the redirect-policy client.) |

The trace event's `:tags` carry the key name, the request URL, and `:sensitive?` from the request envelope per [§Privacy](#privacy); a request whose URL falls under the query-param denylist or whose handler is marked `:sensitive?` has the URL redacted on the way to the trace surface. The trace is informational only — there is no `:rf.error/*` for this path, and the request is not classified as a failure. Cross-host portable code SHOULD avoid these six keys when JVM support matters, or feature-flag them at the call site. See also [§`:rf.http/cors` is CLJS-only](#rfhttpcors-is-cljs-only) for the symmetric failure-category asymmetry.

A related but distinct JVM degradation is **shape**, not silent no-op: a binary `:decode` is **honoured** on the JVM but yields a different host shape.

| `:decode` value | Where | JVM behaviour |
|---|---|---|
| `:blob` / `:array-buffer` / `:form-data` | args map (top-level) | **Honoured, shape-degraded.** `jvm-fetch` reads `ofByteArray` and rides the raw bytes, so the decode is NOT ignored — but the returned value is a `byte[]`, not the native browser `Blob` / `ArrayBuffer` / `FormData` object the CLJS Fetch path yields. An EXPLICIT binary `:decode` emits one `:rf.http/binary-decode-degraded-on-jvm` warning trace per occurrence (`:auto` is not flagged — it resolves to `:blob` from the response Content-Type, unknown at dispatch time). Per [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue). |

`:elapsed-ms` on the `:rf.http/timeout` failure category (see [§Failure categories](#failure-categories-closed-set)) is populated on BOTH hosts with the same semantics: a **measured wall-clock delta** captured across the attempt. The JVM uses a monotonic `System/nanoTime` start mark (`run-attempt!`); CLJS uses a `performance.now()` (or `Date.now()` fallback) delta stamped on the synthetic timeout rejection (`cljs-fetch`). On both hosts `:elapsed-ms` is therefore `>= :limit-ms` by the scheduling margin, so a consumer may compute overshoot (`(- :elapsed-ms :limit-ms)`) portably. (Prior to this, CLJS reported a synthetic constant always `== :limit-ms` — shape-parity, not value-parity; the divergence is now closed.)

### Body encoding

If `:body` is a thunk `(fn body)`, the fx invokes it just before sending (after `:retry :backoff` delays elapse). Each retry re-invokes the thunk to obtain a fresh handle — useful when `:body` is a single-shot stream that can't be replayed. Whatever the thunk returns is then encoded per the rules below.

If `:body` is a Clojure collection AND `:request-content-type` is unset, the fx inspects:

- If `:request-content-type :json` (or detected JSON acceptance via headers) → `JSON.stringify` after `clj->js` with `:keywordize-keys`-aware shape preservation.
- If `:request-content-type :form` → URL-encoded form body, sets `Content-Type: application/x-www-form-urlencoded`.
- If `:request-content-type :text` → coerce to string.
- Otherwise: pass through (the user is supplying a `Blob`/`FormData`/`ArrayBuffer`).

Multipart upload: pass `(js/FormData.)` directly as `:body` (or as the return value of a thunk) and let the runtime not set `Content-Type` (the platform sets the boundary).

**Body realization + encoding is a managed preparation phase.** Invoking the `:body` thunk and encoding the body run *inside* the managed request, after the request is registered but before the transport issues it. A thunk that throws, or a body the encoder rejects (e.g. a non-serialisable value under `:request-content-type :json`), is **not** an uncaught error — it is delivered as a `:rf.http/transport` failure (with a `:stage :request-prep` discriminator and the usual `:message` / `:cause` tags; see [§Failure categories](#failure-categories-closed-set)) routed through the normal failure path. So a prep failure dispatches the caller's `:on-failure`, honours the `:retry` policy (`:rf.http/transport` is retryable — a retry re-invokes the thunk for a fresh handle), respects abort precedence, and carries the same trace + sensitivity treatment as a network-transport failure. It never surfaces as a generic `:rf.error/fx-handler-exception`.

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
2. **JSON-parses the body.** The schema path is **JSON-only**: the runtime wires Malli's `json-transformer` for the decode step, so a schema rides a JSON body by construction. JSON eligibility follows the [RFC 6839](https://www.rfc-editor.org/rfc/rfc6839) / IANA structured-syntax-suffix rule — a Content-Type whose subtype is `json` **or** carries the `+json` suffix is JSON (so mainstream vendor types `application/vnd.api+json` (JSON:API), `application/ld+json` (JSON-LD), `application/vnd.github+json` are accepted, parameters such as `;charset=utf-8` stripped). A response that *declares* a non-JSON Content-Type (e.g. `application/edn`, `text/plain`) under a schema `:decode` is a contract mismatch and classifies as `:rf.http/decode-failure` with a `:rf.error/http-schema-non-json-content-type` discriminator — the diagnostic names the MIME mismatch rather than masking it as a schema-validation failure. A **missing/absent** Content-Type is JSON-eligible (many JSON APIs omit the header); only a *present non-JSON* MIME is rejected. (Non-JSON wire formats under a schema are out of v1 scope — decode the body yourself with a `:decode` fn and validate downstream if you need EDN/other MIMEs.)
3. Validates / coerces with Malli's `decode` against the schema (using the `json-transformer`).
4. Hands the decoded value to `:accept`.

If a 2xx response's body fails to decode (transport-OK, status-OK, but malformed payload), the fx classifies it as `:rf.http/decode-failure` and routes through the failure path. Decode never runs on a 4xx/5xx — see [§Classification order](#classification-order).

**Empty/whitespace-only 2xx body.** An empty (`""`) or whitespace-only 2xx JSON body is a *normal* HTTP outcome — the common `200`/`204`-shaped PUT/DELETE/POST-with-no-content reply — **not** a decode failure. Both the explicit `:decode :json` path and the schema path treat it as a parsed value of `nil`, **identically on every host** (the bare `util-json/json-parse` helper's per-host quirks for an empty string are normalised at the managed-cascade decode altitude). For `:decode :json` the reply is `{:kind :success :value nil}`; for a schema `:decode` the schema decides whether `nil` is acceptable (`[:maybe …]` passes; a required `:map` rejects as a normal schema-validation failure). This is the cross-host parity contract — an empty 2xx body MUST classify the same way on the JVM and CLJS reference hosts.

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
- subtype `json` or a `+json` structured-syntax suffix ([RFC 6839](https://www.rfc-editor.org/rfc/rfc6839)) → `:json` (e.g. `application/json`, `application/vnd.api+json`, `application/ld+json`, parameters stripped).
- `text/*` → `:text`.
- otherwise → `:blob`.

Handles 90% of cases without ceremony. Falling through to `:auto` (i.e., not supplying `:decode`) is normal, supported, and stable usage — no trace fires for it. Supply an explicit `:decode` only when you want to override the content-type sniff or run a schema.

### Schema reflection (optional, ergonomic)

Pair tools, generators, and AI-assisted tooling want to know which schemas a handler expects from the wire — without invoking the handler. The user can declare them at registration time via the `:rf.http/decode-schemas` metadata key:

```clojure
(rf/reg-event :article/load
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

### Keyword-interning cap

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

### JSON decoder hardening
The CLJS reference depends on a **hardened third-party JSON parser** (Cheshire on the JVM; the host's native `JSON.parse` on the browser) rather than a hand-rolled reader. Per : the project's hand-rolled JSON fallback was deleted in favour of the hardened dep; the framework does not own a parser it would have to keep hardened against malformed-input classes.

Ports that ship a **hand-rolled** JSON / EDN reader (rather than depending on a hardened third-party parser) own the input-bounds contract directly. the reader MUST bounds-check unicode-escape sequences (`\uXXXX`) and surface structured `:rf.error/malformed-json` with `:reason` slots (e.g., `:reason :truncated-unicode-escape`, `:reason :invalid-hex-digit`) rather than letting truncated or invalid escapes become opaque host errors.

These contracts compose with the keyword-interning cap above: hardened parser → bounds-checks → cap on cardinality → caller-controllable per-request override. Per [Security.md §Input validation / boundary parsing](Security.md#input-validation--boundary-parsing).

### `:timeout-ms` security defaults

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
            {:failure {:reason :missing-article :message "Response missing :article"}}))
```

Returns either:
- `{:ok value}` — success; `value` is the payload of the success reply.
- `{:failure failure-map}` — domain failure; `failure-map` is the payload of the failure reply.

The default `:accept` returns `{:ok decoded}`. It runs only after a successful 2xx decode (step 4 of [§Classification order](#classification-order)): a non-2xx response classifies by status **before** the body is decoded, so the default `:accept` never sees a non-2xx status and never produces a status-derived failure. The non-2xx outcome is `:rf.http/http-4xx` / `:rf.http/http-5xx` (the raw body at `:body`) — there is no `:kind :http-status`; that kind is not a member of the closed `:rf.http/*` [failure set](#failure-categories-closed-set).

**The accept phase is isolated from decode, and its return is shape-validated.** A recognised return is a map carrying *exactly one* of `:ok` / `:failure`. Two misuse paths classify as `:rf.http/accept-failure` (never `:rf.http/decode-failure` — the accept phase is step 4, distinct from the step-3 decode phase per [§Classification order](#classification-order)) and **always dispatch a reply**:

- **An `:accept` fn that throws.** The throw is the application's accept bug; classifying it as a decode failure would point telemetry at the wrong phase. The pre-`:accept` decoded value rides at `:decoded`.
- **A malformed `:accept` return** — `nil`, a non-map, a map carrying neither key, or a map carrying *both* `:ok` and `:failure`. A malformed return MUST NOT strand the caller: the request has already completed, so failing to emit either a success or a failure reply would leave `:on-success` / `:on-failure` un-dispatched forever. The runtime classifies it as `:rf.http/accept-failure` and dispatches the failure reply.

Both carry a framework-supplied `:detail` describing the bad-accept condition alongside the `:decoded` value for context.

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
| `:on` | set of retryable-category keywords | Which failure categories trigger a retry. **Closed set** — must be drawn exclusively from the *retryable* subset of [§Failure categories](#failure-categories-closed-set): `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`. Common defaults: `#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}`. See [§Closed-set `:retry :on` validation](#closed-set-retry-on-validation) below. |
| `:max-attempts` | int | Total attempts including the first. `1` = no retry. Default: 1. |
| `:backoff` | map | Exponential backoff config. |
| `:backoff.:base-ms` | int | Initial delay (ms). |
| `:backoff.:factor` | num | Multiplier per attempt. |
| `:backoff.:max-ms` | int | Cap on delay. |
| `:backoff.:jitter` | bool | Add random ±25% jitter to each delay. |

#### Closed-set `:retry :on` validation

`:retry :on` is restricted to the **retryable subset** of the failure-category vocabulary:

```clojure
#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}
```

A category is in the retryable subset when re-issuing the *same* request can plausibly yield a different outcome — the only thing transport retry can change is whether the transport itself succeeds. The first three are the obvious transient cases; 4xx and CORS are admitted because a meaningful slice of them is transient too, even though most instances are permanent:

| Category | Why it is admitted as retryable |
|---|---|
| `:rf.http/transport` | Network / DNS / connection-reset errors are the canonical transient failure — a retry over a recovered link succeeds. |
| `:rf.http/timeout` | A slow upstream that blew the per-attempt budget may answer within budget on a later attempt. |
| `:rf.http/http-5xx` | 5xx is a server-side fault (overload, a crashed-and-restarted node, a transient dependency outage) — the canonical "back off and try again" case. |
| `:rf.http/http-4xx` | Admitted because the transient 4xx slice is real — `408 Request Timeout`, `425 Too Early`, and especially `429 Too Many Requests` resolve on a backed-off retry. Most 4xx are permanent client errors and should NOT be blanket-retried; this category is opt-in (`:on` is caller-chosen), and a caller that adds it SHOULD pair it with a narrow `:max-attempts`. (Body-conditional 4xx retry — "retry only when the body says rate-limited" — is **semantic** retry and belongs to a state machine, per [§Boundary — transport vs semantic retry](#boundary--transport-vs-semantic-retry).) |
| `:rf.http/cors` | Admitted because a CORS rejection can be transient: a preflight that failed against a momentarily-misconfigured or just-deploying edge may succeed on a later attempt. Like 4xx it is frequently a permanent configuration error, so it is opt-in and should carry a narrow `:max-attempts`; the heuristic emission caveat in [§CORS classification](#cors-classification--heuristic-emission) applies. |

The other `:rf.http/*` categories from [§Failure categories](#failure-categories-closed-set) are **non-retryable by construction** and rejected when they appear in `:retry :on`:

| Category | Why excluded |
|---|---|
| `:rf.http/aborted` | A cancelled request MUST NOT issue a fresh attempt under any retry policy — re-issuing it would violate Spec 014 §Abort precedence. Abort always wins. |
| `:rf.http/decode-failure` | The next attempt would deterministically reproduce the same schema-validation / parser failure for the same response shape — retrying buys nothing and burns attempts. |
| `:rf.http/accept-failure` | A `{:failure user-map}` projection from `:accept` is the caller's own classification of a successful transport + decode; retrying the transport will not change the response body. Domain-level retry of this shape belongs to a state machine (see [§Boundary — transport vs semantic retry](#boundary--transport-vs-semantic-retry)), not to `:retry`. |

Implementations **MUST validate `:retry :on` at fx-call time** (when the `:rf.http/managed` fx body is invoked, before any attempt is issued). The validation has two parts:

- **Shape.** When present and non-nil, `:on` MUST be a `set`. A non-set value (a bare keyword, a vector, a list, a string, …) is rejected with `:rf.error/http-bad-retry-on` (ex-data `:bad-shape`). This is a hard rejection, not a coercion: the transport membership gate is `(contains? on-set kind)`, and `contains?` over a *sequential* collection tests index/range membership rather than value membership — so a vector `:on` would silently DISABLE retry for every category. An explicit `:on nil` and the empty set `#{}` are intentional no-retry shapes and pass untouched.
- **Membership.** A non-empty intersection between a set `:on` and the rejected set throws an `:rf.error/http-bad-retry-on` ex-info (ex-data `:bad-members`), per [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue). Membership outside the full `:rf.http/*` namespace is also rejected — the set is closed.

Both parts catch the misuse at the dispatch site rather than silently letting a useless retry policy ride for the request's lifetime (or, for `:rf.http/aborted`, deferring the rejection to retry-attempt time inside the transport loop).

Per the spec tighten: implementations MUST NOT accept the old open `:rf.http/*` set. The rejection is hard, not advisory.

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

1. **Transport / timeout / abort.** A network error, per-attempt timeout, or abort short-circuits the rest. Classified as `:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, or `:rf.http/aborted`. The body never enters the picture. Per [§Abort precedence (abort always wins)](#abort-precedence-abort-always-wins) abort dominates the rest of this list — a request marked aborted always classifies as `:rf.http/aborted` regardless of any later-arriving decode / status / transport observation for the same request.
2. **HTTP status.** Once a response lands, status is checked **before** the body is touched.
   - `2xx` → success-eligible; proceed to decode.
   - `4xx` → `:rf.http/http-4xx`; the raw response text is surfaced at `:body`. Decode is skipped.
   - `5xx` → `:rf.http/http-5xx`; same shape as `:http-4xx`. Decode is skipped.
   - Anything else (a 1xx/3xx the runtime didn't follow) → `:rf.http/http-4xx`-shaped.
3. **Decode** (only on 2xx). The configured `:decode` runs against the body. A throw / Malli rejection / parser error here classifies as `:rf.http/decode-failure`.
4. **Accept** (only on a successful decode). The configured `:accept` (or the default) projects the decoded value to `{:ok v}` or `{:failure m}`. A `{:failure m}` here classifies as `:rf.http/accept-failure`.

The order is **status-before-decode by design**: a JSON-API endpoint that returns an HTML 404 from a load balancer (or a CORS pre-flight 4xx with a generic HTML body, or a 503 with a Cloudfront error page) classifies as `:rf.http/http-4xx` / `:rf.http/http-5xx` with the raw body at `:body`, not as `:rf.http/decode-failure`. The HTTP failure category is the load-bearing piece of information for the caller; surfacing decode-failure on a 4xx would hide the real error.

If a caller wants to see the structured error body that an API returns alongside a non-2xx (e.g., `{"error": "..."}` JSON on a 4xx), the caller decodes the raw `:body` themselves in the failure-handling branch — the framework hands you the bytes and the status, and you decide what to do with them.

### Abort precedence (abort always wins)

**Abort always wins.** Once a request is marked aborted (via [`:rf.http/managed-abort`](#request-id-internal), an external [`:abort-signal`](#abort-signal-external), or the [actor-destroy hook](#abort-on-actor-destroy)), classification short-circuits to `:rf.http/aborted` / `:reason :actor-destroyed` regardless of any subsequent decode, transport, status, or accept-projection state observed for the same request — including outcomes whose underlying transport completion arrived in the same scheduler tick as the abort. Implementations MUST NOT surface `:rf.http/decode-failure`, `:rf.http/transport`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, or `:rf.http/accept-failure` on a request that has been aborted; the abort observation wins by classification, not by race ordering.

This pins the universal cancellation convention (Fetch `AbortController` rejects with `AbortError`; Node HTTP's `req.destroy()`; JVM `HttpClient.cancel`; gRPC's `CANCELLED` status). User code that issued an abort sees an `:rf.http/aborted` reply — period — and never has to disambiguate "did my abort actually happen, or did the response just barely beat it?".

The implementation seam is two-layered: an `:aborted?` cell on the in-flight handle is flipped by the abort path BEFORE racing the once-only `:finalised?` CAS; the natural-completion paths (`finalise-success!` / `finalise-failure!`) sample the cell AFTER winning the CAS and reclassify the would-be reply before dispatch. `maybe-retry!` additionally refuses to schedule a retry once the cell is flipped — a request the caller has cancelled MUST NOT issue a fresh attempt under any retry policy.

**The backoff window is itself cancellable.** A request that has failed a retry-eligible attempt and is *sleeping between attempts* (waiting out a `:retry :backoff` delay) is still in-flight for cancellation purposes: implementations MUST keep it discoverable in the in-flight registry — under BOTH its `:request-id` and (when applicable) its `actor-id` — for the entire backoff window, with an abort handle that cancels the pending retry timer rather than aborting a (non-existent) network call. An abort, an actor-destroy cascade, or a same-`:request-id` supersede that arrives DURING the backoff MUST cancel the scheduled retry — no further attempt fires, the registry entry is cleared, and the request finalises as `:rf.http/aborted` (`:reason :user` / `:actor-destroyed`, or supersede-suppressed) exactly as it would have mid-fetch. The sleeping request's in-flight handle MUST carry the **same work identity** a mid-fetch handle carries — its `:work/id` ingredients (origin event, issuance, retry attempt) — so a supersede during the backoff records its [`:rf.http/stale-suppressed`](#request-id-internal) trace with the **carried** work-id of the sleeping attempt (the retry attempt the request was about to make), not a default first-attempt id. Clearing the registry entry before arming the retry timer (leaving the sleeping request invisible to every cancellation path until the timer fires), or arming it without the sleeping attempt's work identity, is non-conformant.

## Failure categories (closed set)

Every failure carries a `:kind` keyword (under the framework-reserved `:rf.http/*` namespace) plus category-specific tags. `:kind` is **framework-owned**; user payloads (from `:accept`) sit at `:detail`, never at `:kind`.

| `:kind` | When | Tags |
|---|---|---|
| `:rf.http/transport` | Network / DNS / connection-refused / connection-reset error before the HTTP transaction completed — **also** a request-preparation failure (a throwing `:body` thunk or a body the encoder rejects; see [§Body encoding](#body-encoding)), which carries an extra `:stage :request-prep` tag | `:message`, `:cause`, (`:stage` on a prep failure) |
| `:rf.http/cors` | CORS preflight rejected or response blocked by browser CORS policy. Distinct from `:transport` because CORS is a configuration error, not a network error. CLJS-only; JVM never emits this. | `:message`, `:url` |
| `:rf.http/timeout` | Per-attempt timeout fired | `:elapsed-ms` (measured wall-clock delta, `>= :limit-ms`, same semantics on both hosts — see [§JVM transport](#jvm-transport--degraded-behaviour-for-cljs-only-options)), `:limit-ms` (the configured per-attempt budget) |
| `:rf.http/http-4xx` | Non-2xx 4xx response | `:status`, `:status-text`, `:body` (the raw response text — decode is skipped on non-2xx; see [§Classification order](#classification-order)), `:headers` |
| `:rf.http/http-5xx` | Non-2xx 5xx response | same as `:http-4xx` |
| `:rf.http/decode-failure` | A success-eligible (2xx) response whose body the decode pipeline rejected (schema validation error, JSON syntax error, custom decoder threw). Non-2xx responses never produce `:rf.http/decode-failure` — they classify by status. | `:body-text`, `:cause`, `:schema-validation-failure?` |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}` — OR `:accept` threw / returned a malformed shape (see [§`:accept`](#accept--domain-failure-normalisation)). For the `{:failure user-map}` case the user's failure map sits at `:detail`; for the throw / malformed-return cases `:detail` is a framework descriptor of the bad-accept condition. `:decoded` carries the pre-`:accept` decoded value for context. | `:detail` (user's verbatim failure map, or framework bad-accept descriptor), `:decoded` |
| `:rf.http/aborted` | The request was aborted via `:request-id` or `:abort-signal` | `:request-id` (if any), `:reason` (`:user` on the reply; `:request-id-superseded` is trace-only — see [§`:request-id` (internal)](#request-id-internal)) |

The category vocabulary is **closed for v1** — additions require a Spec change. The `:rf.http/*` namespace makes these unambiguous wherever they leak: trace events, error projector, `:retry :on` sets, epoch records.

#### CORS classification — heuristic emission

`:rf.http/cors` was specced as a distinct category from `:rf.http/transport` but went un-emitted for a release cycle: browsers opaque CORS rejections and the runtime classified every browser-side rejection as `:rf.http/transport`. Per (Option a — heuristic emission), the CLJS reference now emits `:rf.http/cors` when the rejection shape is a `TypeError` against a cross-origin URL (the strongest signal the browser surfaces without dropping to the network panel). The classifier ships with conformance tests that pin the heuristic + the `:rf.http/cors` `:retry :on` membership. JVM never emits this category — host CORS belongs to the browser fetch stack. Per [Security.md §Input validation / boundary parsing](Security.md#input-validation--boundary-parsing) (CORS classification row in the catalogue references).

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

### Lowering onto the uniform reply envelope

The `{:kind …}` payload above is the **public** shape, and it is unchanged. Internally, every managed-HTTP completion lowers onto the framework-wide [uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope) (Managed-Effects property 9; [EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) §Managed HTTP Lowering is the rationale record). One HTTP attempt produces one **canonical reply map** with a single closed `:status`:

```clojure
{:status       :ok            ;; one of :ok | :error | :cancelled
 :value        <decoded-and-accepted-payload>   ;; on :ok
 :error        {:kind <one of :rf.http/*> …}     ;; on :error / :cancelled
 :work/id      [:rf.work/http logical-id issuance attempt] ;; the attempt identity
 :work/kind    :http
 :work/status  :completed | :failed | :timed-out | :cancelled
 :attempt      1
 :rf.frame/id  :app/main
 :completed-at 1781078400456   ;; read ONCE from the host completion
 :correlation  {:request-id <the :request-id>}}  ;; correlation metadata
```

Status mapping (per [Managed-Effects §Status taxonomy](Managed-Effects.md#status-taxonomy)):

- a successful, decoded-and-`:accept`-projected response → `:status :ok` (`:work/status :completed`);
- any `:rf.http/*` failure → `:status :error`, the classified failure map riding verbatim as `:error`;
- a **timeout** → `:status :error` with `:work/status :timed-out` — **timeout is not a top-level status**, it is an error kind (`:rf.http/timeout`) plus a work status;
- an **abort** → `:status :cancelled` with `:cancelled? true`, `:cancel/reason`, and the `:rf.http/aborted` shape under `:error`.

The HTTP `:request-id` is **correlation metadata** under `:correlation` — it is **not** a second stale-suppression key ([Managed-Effects §Work-id correlation](Managed-Effects.md#work-id-correlation); [EP-0007](../docs/EP/EP-0007-one-name-per-fact.md): one attempt, one `:work/id`). The single `:work/id` head is `[:rf.work/http logical-id issuance attempt]` (the `logical-id` is the caller's `:request-id` when supplied, else the originating event-id; `issuance` is the monotonic per-`request-id` re-issuance counter so a superseded request and the request that supersedes it carry **distinct** work ids even though both reset their retry `attempt` to 1; `attempt` discriminates transport retries within one issuance — retries of the same logical request are distinct work ids). The frame-qualified transport request-id `[:rf.req frame-id work-id]` (landed in [Spec 016](016-Resources.md#ledger-row-retention-and-identity)) is the sanctioned second identity for process-global transport correlation; intra-frame stale suppression still keys on `:work/id`.

`:completed-at` is causal completion metadata ([EP-0010](../docs/EP/EP-0010-causal-world-inputs.md)) read **once** from the host completion at finalisation and carried on the reply — a reply handler derives a durable timestamp from `(:completed-at reply)`, never from a fresh ambient clock read. Completion trace rows (`:rf.http/replied`) are emitted from these canonical reply facts, routing every wire-bearing slot through the shared [`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker) walker (property 5).

**Public compatibility sugar.** `:on-success` / `:on-failure` and the co-located `(:rf/reply msg)` merge are public compatibility sugar that lower to one internal `:rf.http/compat-reply` target; the compat-reply handler reshapes the canonical reply back into the `{:kind :success :value v}` / `{:kind :failure :failure f}` payload above, so the public event shapes promised here are preserved exactly. A `:status :stale` (suppressed) reply is never delivered to the app target.

## Reply addressing

`:on-success` / `:on-failure` name where the success and failure replies are dispatched. The recommended form supplies them explicitly (separate handlers); omitting them falls back to the co-located default — "the originating event id with `:rf/reply` merged into the original message".

### Explicit target — separate handler (recommended)

```clojure
{:fx [[:rf.http/managed {... :on-success [:article/loaded] :on-failure [:article/load-error]}]]}
```

The `:rf/reply` payload is appended as the last argument to the dispatched event vector:

```clojure
[:article/loaded {:kind :success :value v}]
[:article/load-error {:kind :failure :failure failure-map}]
```

Each reply lands on its own single-purpose handler — the failure path is named rather than a branch inside the request handler. This is the shape to reach for by default.

### Default (omitted) — co-located handler

```clojure
{:fx [[:rf.http/managed {:request {...} :decode ...}]]}
```

The fx captures the originating event-id (from the dispatch envelope's cofx). On reply, dispatches:

```clojure
[<originating-event-id> (assoc original-msg :rf/reply {:kind :success :value v})]
```

The handler's body is `(if-let [reply (:rf/reply msg)] ...handle... ...request...)`. One handler, two roles, distinguishable by the `:rf/reply` sentinel. Use it when the reply logic is trivial and tightly coupled to the request.

Both addressing modes carry the same shape so handlers can correlate by inspecting either `(:rf/reply msg)` (in the merged form) or the appended last-arg (in the explicit form).

### Silenced

```clojure
:on-success nil
:on-failure nil
```

Fire-and-forget. Useful for telemetry beacons.

A silenced `:on-failure` drops the failure reply with no handler. To keep this honest against the no-silent-swallow principle, the runtime emits a **one-shot `:rf.warning/failure-swallowed`** trace (per runtime, dev-only) the first time a NON-aborted failure (`:rf.http/transport` / `:rf.http/http-5xx` / `:rf.http/timeout` / `:rf.http/decode-failure` / `:rf.http/accept-failure` / …) is dropped by `:on-failure nil` — the silence is observable rather than invisible. Aborted requests (`:rf.http/aborted`, any reason) are excluded: a cancelled request that no longer wants its reply is correct-by-design silence, not a swallowed error. The warning is informational; there is no `:rf.error/*` for the path.

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

When a fresh request supersedes a prior one with the same `:request-id`, the prior request's `:on-failure` reply is **not dispatched** — semantically the new request *replaces* the old one (the debounce-search mental model). The supersession records the superseded attempt the [uniform reply-envelope way](Managed-Effects.md#stale-suppression): a canonical `:rf.http/stale-suppressed` trace row carrying `:rf.reply/status :stale` / `:rf.reply/work-status :suppressed` (`:stale/reason :rf.http/request-id-superseded`) and the **carried** (the superseded attempt's `:work/id`) and **current** (the superseding attempt's `:work/id`) correlation — the two being `=`-distinct because the per-`request-id` issuance counter bumped. The legacy `:rf.http/aborted` trace (with `:reason :request-id-superseded`) still emits for abort-telemetry consumers; consumers wanting either subscribe via `register-listener!`. No app target runs for the superseded attempt. A manual `:rf.http/managed-abort` aborts whichever request currently holds the id and DOES dispatch `:on-failure` with `:reason :user`.

### `:abort-signal` (external)

Pass an `AbortController.signal` directly:

```clojure
:abort-signal (.-signal my-controller)
```

The fx threads the signal through to the underlying transport. User owns the controller's lifecycle. CLJS-only (Fetch supports it; XHR fallback ignores).

The two are mutually exclusive — pick one. Supplying BOTH `:abort-signal` AND `:request-id` against one request would wire two independent abort mechanisms (the external Fetch signal forwarded into the internal controller AND the `:request-id` supersede/managed-abort path) whose simultaneous-abort interaction would be ambiguous. Rather than tolerate the ambiguity, the spec resolves it by rejecting the combination outright: per the pre-alpha reject-misuse posture the `:rf.http/managed` fx body rejects it at the dispatch site (before `run-attempt!`) with a thrown `:rf.error/http-bad-abort-config` ex-info — see [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue).

### Abort on actor destroy

Per [Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts), `:rf.http/managed` requests issued from inside a spawned state-machine actor are aborted automatically when the actor is destroyed.

#### The contract

When a `:rf.http/managed` fx is processed, the runtime captures the **originating event vector** from the dispatch envelope (the `:event` value on the fx ctx, per [Spec 002 §Routing the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope)). The first element of that vector is the event-id that dispatched the request — for events fired into a spawned actor's handler, that first element is the spawned actor's address (e.g. `:http/post#1`).

The fx records the (`request-id`, `actor-id`) pair in its in-flight registry alongside the abort handle. When the spawned actor is later destroyed (any of the destroy triggers per [Spec 005 §The contract](005-StateMachines.md#the-contract)), the runtime invokes the late-bind hook `:http/abort-on-actor-destroy` with the destroyed actor's address. The hook walks the in-flight registry, identifies every request whose actor-id matches, and aborts each — synthesising a standard `:rf.http/aborted` failure with `:reason :actor-destroyed`.

#### Failure shape — meaningful target

When the request's reply target is **still meaningful** — an ordinary registered event (a separate recorder handler), not the destroyed actor itself — the aborted reply is the same shape as a manual-abort failure and is delivered as a live `:status :cancelled` outcome:

```clojure
{:rf/reply {:kind    :failure
            :failure {:kind       :rf.http/aborted
                      :request-id <id-or-nil>
                      :reason     :actor-destroyed
                      :actor-id   <destroyed-spawned-actor-id>}}}
```

The discriminator from a user-issued abort is `:reason` — `:user` (manual `:rf.http/managed-abort`) or `:actor-destroyed` (this contract). Callers that branch on `:reason` recover that distinction; callers that don't see one uniform "aborted" outcome. (The third reason value, `:request-id-superseded`, never lands on a reply dispatch — per [§`:request-id` (internal)](#request-id-internal) supersede suppresses the prior request's reply and emits only to the trace bus.)

#### Obsolete target — stale suppression

When the request's reply target is **obsolete** — its event-id names the destroyed actor itself (the [machine-shape wrapper](#machine-shape-wrapper)'s `[self-id [:rf.http/failed]]` default, or any request whose reply addresses its own actor) — dispatching it would address a now-dead actor. Per [Managed-Effects §Cancellation](Managed-Effects.md#cancellation) (`:status :cancelled` when the actor-bound target is still meaningful, `:status :stale`/`:suppressed` when teardown made the target obsolete before delivery), the abort lowers to the canonical uniform-reply-envelope **stale-suppression** outcome instead of a live `:cancelled`/failure reply:

1. the app reply target **MUST NOT run** (no dispatch to the dead actor);
2. the reply outcome is `:status :stale` / `:work/status :suppressed`, carrying `:stale/reason :rf.http/actor-destroyed-target-obsolete`;
3. a `:rf.http/stale-suppressed` reply-envelope trace records the carried correlation joined to `:work/id` (`:recovery :actor-destroyed-target-obsolete`) — the same canonical stale row a [supersession](#request-id-internal) emits;
4. the `:rf.http/aborted` trace still fires (the abort is observable); only the app **reply delivery** is suppressed.

This replaces the earlier accidental no-op (a dispatch to an unregistered handler that silently dropped): the suppression is now an explicit, data-shaped `:status :stale` outcome that tooling and conformance pin, exactly as supersession already does. Explicit `:user` aborts always stay live `:cancelled`.

#### Multiple in-flight requests per actor

A spawned actor may issue multiple `:rf.http/managed` requests in its lifetime. The actor-destroy hook walks **every** in-flight request whose actor-id matches and aborts each. There is no fairness or ordering guarantee between the aborts; the trace stream sees one `:rf.http/aborted-on-actor-destroy` per cancelled request.

#### Sibling actors are not affected

When actor A is destroyed, only A's in-flight requests are aborted. Actor B's in-flight requests — including under the same `:spawn-all` if `:cancel-on-decision? false` and B has not yet been told to stop — are unaffected.

`:spawn-all`'s cancel-on-decision (per [Spec 005 §Cancel-on-decision](005-StateMachines.md#cancel-on-decision-default-true)) emits one `:rf.machine/destroy` per surviving sibling, so each sibling's HTTP cascade independently fires the `:http/abort-on-actor-destroy` hook against its own actor-id.

#### Direct dispatches from event handlers — NOT covered

Per the spec 005 cross-feature contract, `:rf.http/managed` requests dispatched directly from ordinary `reg-event` handlers — i.e. NOT from inside a spawned actor's event handler — are NOT subject to actor-destroy cancellation. The originating event vector's first element is an ordinary registered event-id, not a spawned-actor address; there is no actor-id to correlate against.

This is **deliberate.** Cancellation tied to actor lifetime is the right scope: the child actor exists to run until the parent says "we no longer care"; the parent destroying the actor kills its outstanding work. Ordinary event handlers have no analogous lifecycle peg — their work is launched as a side effect and outlives the handler.

Apps that want HTTP requests tied to the lifetime of a state-machine state should issue them from inside a spawned child machine, using `:spawn` or `:spawn-all` to bind the child's lifetime to the state's. The `:rf.http/managed-abort` fx and the user-supplied `:request-id` remain available for app-level cancellation of direct-dispatched requests (per [§`:request-id` (internal)](#request-id-internal)).

#### Trace event

`:rf.http/aborted-on-actor-destroy` (per [Spec 009 §Trace events](009-Instrumentation.md)) fires once per cancelled request. `:tags` carry `:request-id`, `:actor-id`, and `:url`.

When the request's reply target was **obsolete** (it addressed the destroyed actor — see [§Obsolete target — stale suppression](#obsolete-target--stale-suppression)), a `:rf.http/stale-suppressed` reply-envelope row ALSO fires (`:stale/reason :rf.http/actor-destroyed-target-obsolete`, `:recovery :actor-destroyed-target-obsolete`), carrying the canonical `:status :stale` / `:work/status :suppressed` facts joined to `:work/id` — the same canonical stale row supersession emits.

#### Cross-references

- [Spec 005 §Cancellation cascade — in-flight `:rf.http/managed` aborts](005-StateMachines.md#cancellation-cascade--in-flight-rfhttpmanaged-aborts) — the machine side of the contract.
- [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) — `:rf.http/aborted-on-actor-destroy` taxonomy entry.
- [`:request-id` (internal)](#request-id-internal) — the orthogonal app-level abort surface.
- Boot-as-state-machine §M2 — the original gap analysis motivating this contract.

## Frame awareness

The reply dispatch lands in the **same frame** the request was issued from. The fx reads the issuing frame's stamp from the dispatch **envelope's** `:frame` key — one of the **sanctioned** sites the bare `:frame` spelling survives at (the binary fx-handler ctx and the dispatch envelope; there is no bare `:frame` *coeffect* — the event-context spelling is `:rf.frame/id`, per [002 §One carrier, one name — the frame stamp](002-Frames.md#one-carrier-one-name--the-frame-stamp)) — and threads it through to the reply dispatch's `{:frame ...}` opt. Multi-frame apps work without extra ceremony.

## Middleware

Apps repeatedly want to apply a transform to every `:rf.http/managed` request or response: attach a Bearer token, stamp a correlation-id, rewrite a base URL in dev (request side); parse rate-limit headers, compute response-time deltas, flag a 401 for auth refresh (response side). v1 ships a **per-frame interceptor chain** with both phases — a `:before` request-side transform that sits between the user's args and the transport, and an `:after` response-side transform that sits between the transport's reply and the `:on-success` / `:on-failure` dispatch. The shape mirrors the event-interceptor `{:id :before :after}` onion (Spec 002): symmetric on both sides, `:before` in registration order, `:after` in reverse.

### Shape

The public surface is `(reg-http-interceptor id interceptor-map)` — positional `id` keyword and a single interceptor-map carrying at least one of `:before` / `:after`, plus optional `:frame` (the EP-0002 *override*; absent, the carried scope it registers under resolves it — registering under no scope raises `:rf.error/no-frame-context`, never `:rf/default`) and any `:rf/registration-metadata` (per [Spec-Schemas §`:rf/http-interceptor-meta`](Spec-Schemas.md#rfhttp-interceptor-meta)): `:doc` / `:tags` / `:schema` / `:sensitive?`. Source-coords (`:ns` / `:line` / `:column` / `:file`) are auto-captured at the call site per [Spec 001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference). The shape mirrors the event-interceptor `{:id :before :after}` mental model (Spec 002) — one `{:before :after}` map per registration, fully symmetric on the request and response sides.

```clojure
(rf/reg-http-interceptor
  :auth-header
  {:doc    "Stamp Bearer <token> on every outgoing request."
   :before (fn [ctx]
             (let [token (-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Bearer " token)))))})
```

### `:before` — request-side ctx contract

Each `:before` receives a context map with these keys:

| Key | Type | Notes |
|---|---|---|
| `:request` | map | The `:request` envelope per [§Request envelope](#request-envelope). `:before` returns a ctx whose `:request` is the modified envelope. |
| `:args` | map | The full `:rf.http/managed` args map (`:request` plus `:decode` / `:accept` / `:retry` / `:on-success` / ...). Read-only by convention; the only field the runtime threads onto the transport is `:request`. |
| `:frame` | keyword | The resolved frame id. |
| `:event` | vector | The originating event vector (or `[:rf.http/managed]` when not threaded). |

The fn returns the (possibly-modified) ctx. The runtime threads its `:request` onto the next interceptor (or onto the transport when the chain is exhausted).

### `:after` — response-side ctx + response contract

Each `:after` receives `(fn [ctx response] response')`:

| Slot | Type | Notes |
|---|---|---|
| `ctx` | map | The SAME ctx the `:before` chain produced for THIS request — `{:request :args :frame :event}` plus any keys `:before`s added. Carrying the request ctx forward is what makes request-correlated handling expressible: a `:before` that stamps `::started-at (System/nanoTime)` lets the same interceptor's `:after` read the start mark and compute a wall-clock delta without app-level state. Likewise per-request header parsing, correlation-id matching, and auth-refresh keyed off the originating event become single-interceptor concerns. |
| `response` | map | `{:kind :success :value <decoded>}` or `{:kind :failure :failure <failure-map>}`. The shape matches the reply-payload `build-reply-event` appends to the user's `:on-success` / `:on-failure` event vector. |

Returns the (possibly-transformed) response map. The runtime threads each `:after`'s return value through the next `:after`, then substitutes the final response into the reply-payload before `:on-success` / `:on-failure` fire.

#### Motivating use cases

1. **Rate-limit header parsing.** Inspect `X-RateLimit-Remaining` once on the response, attach a structured `:rate-limit` slot to the reply; downstream handlers consume the structured form without re-parsing per-call.
2. **Response-time telemetry.** `:before` stamps a wall-clock mark on ctx; `:after` reads it and computes the elapsed delta. The ctx-carried-from-before contract is what makes this expressible in a single interceptor.
3. **Cache-Control inspection.** Parse `Cache-Control` directives once; attach a structured `:cache` slot; downstream handlers consume `:max-age` / `:public?` / etc. without re-parsing the header.
4. **401 auth-token refresh.** Inspect the failure shape on `:rf.http/http-4xx` + `:status 401`; tag the reply with `:auth-refresh-required true` so a downstream handler can mint a refresh dispatch without every call site reimplementing the check.

### Chain order and frame scope

- **`:before` chain — registration order.** The `:before` chain runs in the order `reg-http-interceptor` calls were made on that frame. Re-registering an existing id replaces the slot **in place** — the position is preserved.
- **`:after` chain — REVERSE registration order.** The `:after` chain runs in the **reverse** of registration order: interceptor A registered before B means request flows `A.before → B.before → transport` and response flows `transport → B.after → A.after → reply dispatch`. This mirrors the event-interceptor onion (Spec 002): the outermost registration wraps the innermost on the request side and again on the response side.
- **Skip-when-absent.** Interceptors with no `:before` are transparent on the request side; interceptors with no `:after` are transparent on the response side. A `:before`-only interceptor and an `:after`-only interceptor compose cleanly. At least one of `:before` / `:after` MUST be supplied — a no-op interceptor is rejected at registration with `:rf.error/http-bad-interceptor`.
- **Clear-then-re-register.** `clear-http-interceptor` removes the slot entirely; a subsequent `reg-http-interceptor` of the same id is a fresh registration and **appends to the end** of the chain. The position is *not* preserved across a clear — the slot's prior index is forgotten on clear. Tools that want to mutate-in-place (e.g. hot-reload) should call `reg-http-interceptor` directly without clearing first; tools that want a fresh end-of-chain slot use clear-then-reg explicitly.
- **Per-frame.** An interceptor registered against frame A does NOT fire for a request dispatched from frame B. Multi-frame apps register independent chains per frame; the auth interceptor on the user-app frame doesn't leak into a hypothetical admin-app frame.

### Failure mode

A throw inside any `:before` or `:after` classifies as `:rf.error/http-interceptor-failed`. The runtime:

1. Emits a `:rf.error/http-interceptor-failed` trace event with `:frame`, `:interceptor-id`, `:url`, `:phase` (`:before` is implicit when absent; `:after` is stamped on the response-side path), and `:cause` tags (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)).
2. Re-throws the wrapped ex-info. On the request side, the `re-frame.fx` outer catch converts the throw to `:rf.error/fx-handler-exception` (so `:rf.fx/handled` does NOT fire). On the response side, the throw propagates into the transport's reply-dispatch path; the request is not dispatched to `:on-success` / `:on-failure`.
3. Request-side: does NOT dispatch the request — the transport never sees it.

Pair tools and 10x panels see exactly two traces per request-side interceptor failure: the per-interceptor `:rf.error/http-interceptor-failed` (which carries `:interceptor-id`) and the cascade-level `:rf.error/fx-handler-exception` (which carries `:fx-id :rf.http/managed`). Apps that want to recover gracefully wrap the throwing logic inside the `:before` / `:after` itself — the chain has no recovery cofx.

### Clearing

`(rf/clear-http-interceptor id)` removes the slot on `:rf/default`; `(rf/clear-http-interceptor frame-id id)` targets a specific frame. The single-arity is the common case (single-frame apps); the two-arity is unambiguous for multi-frame.

Hot-reload tools that re-evaluate registration call sites get the right behaviour automatically: re-`reg-http-interceptor` of an existing id replaces the slot in place.

### Trace events

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded. Tags: `:frame`, `:id`. |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing slot (no trace fires for a clear-of-unknown-id). Tags: `:frame`, `:id`. |
| `:rf.error/http-interceptor-failed` | `:error` | A `:before` or `:after` threw; see [§Failure mode](#failure-mode). Tags: `:frame`, `:interceptor-id`, `:url`, `:cause`, `:phase` (`:after` for response-side, absent for `:before`). |

### Example — Bearer auth with a single registration

```clojure
(rf/reg-http-interceptor
  :app/bearer-auth
  {:before (fn [ctx]
             (let [token (-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Bearer " token)))))})

;; All subsequent `:rf.http/managed` requests on `:rf/default` carry the
;; header automatically — no per-call-site threading. The interceptor
;; reads the auth slice on every request, so token rotation is picked
;; up without re-registration.

(rf/reg-event :articles/list
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request {:url "/articles"}                ;; no auth threading
            :decode  ArticleListResponse}]]}))
```

### Public surface

| API | Kind | Signature |
|---|---|---|
| `reg-http-interceptor` | Fn | `(rf/reg-http-interceptor id interceptor-map)` — positional `id`, single `interceptor-map`. `interceptor-map` carries at least one of `:before` / `:after`, optional `:frame`, optional `:rf/registration-metadata`. |
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
(rf/reg-event :ping
  (fn [{:keys [db]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :pong (:value reply))}      ;; co-located reply payload: {:kind :success :value …}
      {:fx [[:rf.http/managed {:request {:url "/ping"}}]]})))   ;; :method defaults to :get
```

No decode (default `:auto`), no accept (default success-on-2xx), no retry, default reply addressing. Two-line fx.

### B — Schema-driven with retry

```clojure
(rf/reg-event :articles/list
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
(rf/reg-event :auth/login
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
(rf/reg-event :search/query
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
(rf/reg-event :ping
  (fn [{:keys [db]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :pong (:value reply))}                  ;; co-located reply payload: {:kind :success :value …}
      {:fx [(rf.http/get "/ping")]})))                       ;; ← 1 line vs 1 envelope

;; B — schema-driven GET with retry:
(rf/reg-event :articles/list
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
(rf/reg-event :auth/login
  (fn [{:keys [db]} [_ creds]]
    {:fx [(rf.http/post "/auth/login"
                        {:request    {:body creds :request-content-type :json}
                         :decode     AuthResponse
                         :on-success [:auth/login-success]
                         :on-failure [:auth/login-error]})]}))

;; E — aborting a stale search:
(rf/reg-event :search/query
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

#### `:after-ms` — deferring a canned reply

Both canned-stub fxs accept an optional `:after-ms` arg. It is a **parameter of the same effect**, not a separate `-later` fx:

| `:after-ms` | Reply timing |
|---|---|
| absent / `0` / non-positive | immediate — the reply dispatches inside the same drain (the default, unchanged) |
| positive `N` | deferred — the reply lands after an `N`-ms `:dispatch-later` tick |

```clojure
;; Immediate (default): reply lands in the same dispatch-sync drain.
{:fx [[:rf.http/managed-canned-success {:value {:user {...}}}]]}

;; Deferred: reply lands 50 ms later, so a `:loading` / `:submitting`
;; UI state is observable before it resolves.
{:fx [[:rf.http/managed-canned-success {:value {:user {...}} :after-ms 50}]]}
```

The delay rides the framework-native `:dispatch-later` timer — it is **observable in the tape** (the deferred dispatch self-tags `:source :fx-dispatch-later` with `:source-detail {:ms N}`) and time-travel-safe (Tool-Pair time-travel and the documented `:dispatch-later` nil-override seam both apply). It is **not** raw `js/setTimeout` / `interop/set-timeout!`. Reply addressing is identical to the immediate path — the originating event (or the explicit `:on-success` / `:on-failure`) still receives the canonical `{:kind ... }` envelope after the delay. `:after-ms` is the single mechanism a demo stub uses to simulate latency; it replaces the former per-app three-hop boilerplate (stub-fx → schedule-reply → `:dispatch-later` → deliver-reply → canned reply) with one arg on the canned effect.

**Interaction with `:fx-overrides`.** When `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` redirects the production fx to a canned stub, the override target receives the **same args-map** the handler built for `:rf.http/managed` — so an `:after-ms` placed there flows straight through to the canned stub. A handler can therefore stay backend-agnostic (it never mentions `:after-ms`) while a test or demo dials in latency purely through the override, OR a demo stub that wraps the canned fx (the worked examples' pattern) can stamp `:after-ms` on the args-map before delegating. The override only redirects `:rf.http/managed`; the deferred reply the canned fx synthesises internally re-fires the canned fx by its own id (not `:rf.http/managed`), so it does not depend on the override staying installed across the delay, and reply addressing is identical to the immediate path.

### Test-support require — the HTTP test surface gate

The canned-stub fxs above AND the `with-managed-request-stubs` family of macros / fns are **test-only**; production / SSR code paths must not be able to reach them. The framework gates registration behind an explicit require:

```clojure
(ns my-app.tests
  (:require [re-frame.http-managed]        ;; production fx surface
            [re-frame.http-test-support])) ;; canned-stub fxs + stub macros
```

Loading `re-frame.http-test-support` registers `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure`, defines the stub-routing helpers (`with-managed-request-stubs`, `with-managed-request-stubs*`, `install-managed-request-stubs!`, `uninstall-managed-request-stubs!`), and publishes the matching late-bind hooks (`:http/install-managed-request-stubs!`, `:http/uninstall-managed-request-stubs!`, `:http/with-managed-request-stubs*`) that the `re-frame.core` re-exports resolve through. Without the require:

- on JVM / SSR the canned-stub fx ids are unregistered (classpath absence through the normal artefact require boundary), so any handler that tries `:fx-overrides {:rf.http/managed :rf.http/managed-canned-success}` will surface the framework's no-such-fx error; the stub-family `rf/install-managed-request-stubs!` / `rf/uninstall-managed-request-stubs!` / `rf/with-managed-request-stubs*` call sites raise `:rf.error/http-artefact-missing` through `re-frame.core-http`'s defwrapper surface (the hooks are nil);
- on CLJS `:advanced + goog.DEBUG=false` the test-support module is unreferenced from any production module, so the compiler trims it wholesale (the canned-stub fx-id keyword string fragments do not appear in the production bundle — pinned by `scripts/check-elision.cjs`).

Earlier the gate was `(when interop/debug-enabled? ...)` inside `re-frame.http-managed` itself; on the JVM that gate folded to `true`, leaving the canned-stub fx ids reachable as production-default API. Per's audit and the remediation, the gate moved to the require boundary so the absence is enforced on every host. Per (audit-of-audits #15) the stub macros consolidated into `re-frame.http-test-support` alongside the canned-stub fxs — one namespace, one require, the namespace name finally matches its content.

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
| `clear-all-in-flight!` | `(clear-all-in-flight!)` → nil | Drops both the request-id-keyed and actor-id-keyed in-flight maps. Consumed by `re-frame.test-support/make-reset-runtime-fixture` to restore a clean registry between tests; the `:http/clear-all-in-flight!` hook is published via the late-bind table so `test-support` can call it without statically requiring the http artefact. |
| `in-flight-snapshot` | `(in-flight-snapshot)` → map | Reads the current value of the request-id-keyed in-flight map. For tests that need to assert "this request-id is in flight" without poking the atom directly. |
| `actor-in-flight-snapshot` | `(actor-in-flight-snapshot)` → map | Reads the current value of the actor-id-keyed in-flight map (per [§Abort on actor destroy](#abort-on-actor-destroy) and). For tests that need to assert the actor → request-id reverse index. |
| `seed-in-flight-for-test!` | `(seed-in-flight-for-test! handle)` / `(seed-in-flight-for-test! request-id actor-id handle)` → handle | Registers a fabricated in-flight handle through the SAME `record-in-flight!` path production uses, so BOTH indexes stay consistent. For fixtures that need an in-flight slot present without issuing a real request. The 1-arity reads `:request-id` / `:actor-id` off the handle. The raw in-flight atoms are NOT exported — a fixture must seed through this helper rather than `swap!`-ing an atom directly. |

These are **test-only** surfaces — not part of the user-facing API for production code paths. Application code SHOULD route through `:rf.http/managed` and the dispatch-shape replies; the helpers exist so test fixtures can observe, seed, and reset registry state without reaching into the namespace's atoms. The underlying `in-flight` / `actor-in-flight` storage atoms are **not** re-exported from `re-frame.http-managed` — exposing mutable internal storage as API would let callers bypass `record-in-flight!` / `clear-in-flight!` and the actor-index cleanup invariants. The per-frame HTTP-interceptor chain has the symmetric pair: `interceptors-snapshot` (`(interceptors-snapshot)` → `frame-id → [slot …]`, or `(interceptors-snapshot frame-id)` → `[slot …]`) reads the chain for assertions; registration / clearing route through `reg-http-interceptor` / `clear-http-interceptor`, never a raw atom `swap!`.

## Machine-shape wrapper

Per — `:rf.http/managed` is **also** registered as a child-invokable state machine, so a parent machine ca `:spawn` it without writing any glue. The wrapper is **additive** on top of the fx surface: `:fx [[:rf.http/managed args]]` continues to work unchanged ([§The shape](#the-shape) is the canonical user-facing surface); the machine wrapper is a second affordance for callers who are already inside a state-machine envelope and want a child machine they can compose with `:spawn`, `:after`, and the cancellation cascade.

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

While in `:authenticating`, a child wrapper actor of `:rf.http/managed` is alive at `[:rf.runtime/machines :snapshots :rf.http/managed#N]` (in runtime-db). It issues the request on entry; on the reply it transitions to its `:succeeded` / `:failed` terminal state and dispatches `[<parent-id> [:succeeded value]]` (or `[<parent-id> [:failed failure]]`) back to the parent — which the parent's `:on` map handles as ordinary FSM events.

### Wrapper spec

Internally the wrapper machine has:

| key | value |
|---|---|
| `:initial` | `:requesting` |
| `:states` | three leaves — `:requesting`, `:succeeded`, `:failed` |

`:requesting` listens for three events:

- `:rf.machine.spawn/spawned` — the synthetic event the runtime dispatches to spawns without a `:start` (per [Spec 005 §Spawning](005-StateMachines.md#spawning--dynamic-actors)). The wrapper's `:fire-request` action runs, emitting the underlying `:rf.http/managed` fx with `:on-success` / `:on-failure` pointing back at the wrapper actor's own id (so the reply lands at the wrapper, not at the user's handler).
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

The framework-reserved `:rf/*` keys the wrapper itself uses (`:rf/self-id`, `:rf/parent-id`, `:rf/invoke-id`, `:rf/result`) are stripped before the underlying fx call, so they never leak into the request envelope.

`:on-success` / `:on-failure` are **not** passed through — the wrapper overrides them to route the reply back to itself. Apps that want explicit reply addressing should keep using the fx form directly; the machine wrapper is for the `:spawn`-orchestrated case.

### Cancellation cascade

Per [§Abort on actor destroy](#abort-on-actor-destroy), the wrapper actor's in-flight request is automatically aborted when the wrapper is destroyed. The wrapper is destroyed:

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

HTTP is the canonical privacy surface in any application: passwords ride request bodies, auth tokens ride request headers, user PII rides response bodies. Without honouring [Spec 009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces)'s `:sensitive?` contract on the `:rf.http/*` trace events, the HTTP cascade is the biggest leakage vector the framework ships.

Spec 014 specifies HTTP-side honouring on top of the Spec 009 contract: every `:rf.http/*` trace event MUST stamp `:sensitive?` when the originating handler is sensitive, MUST redact known-sensitive request headers regardless of handler sensitivity, and MUST redact request / response bodies when the request is sensitive. The contract layers as three cooperating pieces.

### Privacy at a glance — declaration surfaces and owning namespaces

The privacy surface has four declaration surfaces, none of them a process-global mutation (EP-0015 §3):

| Surface | Purpose | Where declared |
|---|---|---|
| Built-in header denylist | A closed, **immutable** set of always-sensitive header names ([§1](#1-header-denylist-always-on)). No frame can remove one. | framework default (`re-frame.http-privacy-headers`) |
| Built-in query-param denylist | A closed, **immutable** set of always-sensitive query-param names ([§2](#2-query-param-denylist-always-on)). | framework default (`re-frame.http-url`) |
| Frame-local carriers | App-specific sensitive header / query-param names, declared on the **frame** via `:sensitive {:http {:headers [..] :query-params [..]}}` ([§Frame-local carriers](#frame-local-carriers-ep-0015-3)). The frame extension set **unions** onto the built-in defaults for the emitting frame. `:query-params` also accepts a `{:include [..] :except [..]}` policy map whose `:except` set **subtracts** a built-in default for that frame's own dev trace (rf2-4wqxq8); `:headers` has no `:except` form. | `reg-frame` metadata (`re-frame.frame-classification`) |
| Per-request `:sensitive?` | The coarse per-call / per-request flag ([§3](#3-per-request--per-call-sensitive)) that redacts a single request's body / params / all URL params wholesale. | the `:rf.http/managed` args map |

Response **bodies** are classified separately, per-slot, via the request's `:decode` schema ([§Response-body classification](#response-body-classification-ep-0015-5)).

The composers that orchestrate per-emit redaction + stamping — `request-sensitive?`, `prepare-emit-tags`, `prepare-emit-failure` — live in `re-frame.http-privacy` as the privacy orchestrator. They consult the two built-in denylists, the emitting frame's frame-local carrier extension sets (resolved once per emit via `re-frame.frame-classification/http-carriers`), and the per-request / per-call `:sensitive?` flag, and produce the redacted slot values + stamped tags map that `trace/emit!` / `trace/emit-error!` sees.

> **No process-global carrier mutation (EP-0015 §3).** Earlier drafts exposed `declare-sensitive-header!` / `declare-sensitive-query-param!` (and `clear-*!` siblings) as a process-global denylist mutation on the `re-frame.http` façade. Those are **removed**: re-frame2 is multi-frame, so app-specific carrier policy is frame-owned durable config, not a process global. The immutable built-in defaults remain.

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

The built-in denylist is **immutable** — no frame can remove a name. Apps extend it for app-specific tokens (e.g. `X-Honeycomb-Team`, `X-Stripe-Signature`) on the **frame** (EP-0015 §3 — see [§Frame-local carriers](#frame-local-carriers-ep-0015-3)):

```clojure
(rf/reg-frame :app/main
  {:sensitive {:http {:headers ["X-Honeycomb-Team"]}}})
```

The frame extension set is lower-cased and **unions** onto the immutable built-in defaults for traces emitted from that frame; matching is case-insensitive.

### 2. Query-param denylist (always-on)

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

The built-in denylist is **immutable** — no frame can remove a name. Apps extend it for app-specific tokens (e.g. `shop_token` for Shopify, `signature` variants in webhook receivers) on the **frame** (EP-0015 §3 — see [§Frame-local carriers](#frame-local-carriers-ep-0015-3)):

```clojure
(rf/reg-frame :app/main
  {:sensitive {:http {:query-params ["shop_token"]}}})
```

The frame extension set is lower-cased and **unions** onto the immutable built-in defaults for traces emitted from that frame; matching is case-insensitive.

**Frame-local subtraction — `{:include :except}` (rf2-4wqxq8).** `:query-params` additionally accepts a policy map so a frame can stop redacting a *harmless* routing/pagination name (e.g. a `token` that is a CSRF/page token, not an auth secret) in its **own** dev trace, where the broad bare-name default produces friction:

```clojure
(rf/reg-frame :app/main
  {:sensitive {:http {:query-params {:include ["shop_token"]   ; extend defaults
                                     :except  ["token"]}}}})    ; subtract a default
```

The effective policy is **`(defaults − except) ∪ include`** — `:include` extends the built-in defaults (identical to the bare vector form), `:except` removes the named defaults for that frame. A name in **both** `:include` and `:except` stays sensitive (`:include` wins — declaring a name sensitive is never undone by also excepting it). The subtraction is **frame-local and dev-trace-only**: all redaction is debug-gated trace surface and elides entirely in production, so `:except` only relaxes dev-trace friction — it never affects a production bundle. The header denylist (§1) has **no** `:except` form (a default-off header would be a real leak), and the query defaults stay **on-by-default**, subtractable only per explicitly-named param. Malformed shapes (unknown key inside the policy map, non-string name, non-vector sub-value) **fail loudly at frame registration**.

Matching is **percent-decoding-aware**: a query-param name is compared against the denylist in both its raw spelling **and** its percent-decoded form, so an encoded denylisted name (`?api%5Fkey=…` for `api_key`, `?%61ccess_token=…` for `access_token`, an app-declared `?shop%5Ftoken=…`) is redacted just like its plain spelling. The decode is comparison-only — the rebuilt URL preserves the original raw name verbatim and replaces only the value. A malformed percent-escape decodes to nothing and falls back to the raw-name match; redaction is total and never throws.

A query-param denylist hit **alone** (no per-request / per-call `:sensitive?`) **stamps `:sensitive? true`** on the resulting trace event — the presence of a denylisted parameter name is itself a signal that the request carries an auth secret, and downstream privacy-honouring consumers should treat the event accordingly. This is the analogue of the header denylist contract: the name is the signal.

### 3. Per-request / per-call `:sensitive?`

Two OR-reduced sources contribute the request-side `:sensitive?` flag for a given `:rf.http/managed` invocation:

1. **Per-request** — `:sensitive? true` under the `:request` map of the `:rf.http/managed` args. The conventional site for opting a single request in (e.g. a generic POST handler that becomes sensitive only when posting to `/auth/login`). Composes with `:request-content-type`, `:body`, etc. unchanged.

2. **Per-call** — `:sensitive? true` at the top level of the `:rf.http/managed` args map. Pragmatic sugar for callers that prefer the flag alongside `:on-success` / `:on-failure` rather than nested under `:request`. Semantically identical to per-request.

Either source set to `true` makes the request sensitive; both sources defaulting to `false`/absent means not sensitive. The runtime resolves the effective flag once at fx-invocation time and threads it through the attempt-and-retry loop so every `:rf.http/*` trace event the cascade emits sees the same flag (no per-emit re-resolution).

Handler-meta `:sensitive?` is **not** a source — the handler-level `:rf/registration-metadata` annotation has been removed (per [Spec 009 §The `:sensitive?` registration metadata key](009-Instrumentation.md#the-sensitive-registration-metadata-key)). Sensitivity is now declared per-request / per-call (the request-side opt-ins here) and, on the trace surface, schema-derived (Spec 009 §Schema-installed redaction). A query-param denylist hit ([§2](#2-query-param-denylist-always-on)) is a third, automatic stamping signal independent of these opt-ins.

```clojure
;; Per-request — opt a single request in:
(rf/reg-event :api/proxy
  (fn [_ [_ {:keys [target body]}]]
    {:fx [[:rf.http/managed
           {:request    {:method :post :url target :body body
                         :sensitive? (= target "/auth/login")}}]]}))

;; Per-call — same effect, top-level:
(rf/reg-event :api/login
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request    {:method :post :url "/auth/login" :body creds}
            :sensitive? true}]]}))
```

### 4. Trace-event redaction + stamping rules

For every `:rf.http/*` trace event the runtime emits (`:rf.http/retry-attempt`, `:rf.http/aborted-on-actor-destroy`, the eight `:rf.http/*` failure categories from [§Failure categories](#failure-categories-closed-set), `:rf.warning/failure-swallowed`), implementations MUST:

1. **Redact denylisted headers** in `:headers` slots regardless of the effective `:sensitive?` flag.
2. **Redact denylisted query-string parameter values** in `:url` slots regardless of the effective `:sensitive?` flag. Param-name + position preserved; the value is replaced inline with the `:rf/redacted` text token.
3. **Redact body / body-text / decoded / detail** slots when the effective `:sensitive?` is true. Specifically: `:body` (request and response), `:body-text` (decode-failure raw text), `:decoded` (the pre-`:accept` decoded value carried by `:rf.http/accept-failure`), and `:detail` (the user-supplied failure map carried by `:rf.http/accept-failure`). All slot values become `:rf/redacted`. This is the **on-box** redaction contract — the per-call `:sensitive?` flag is the coarse escape hatch for the local dev trace. **Off-box** egress fails closed *independently* of this flag: a raw error body (`:body` / `:body-text`) and an unschematized decoded body are omitted off-box regardless of `:sensitive?` — see [§Response-body classification Rules 3–4](#response-body-classification-ep-0015-5).
4. **Redact `:params`** (the structured query-string params map on the request side) when the effective `:sensitive?` is true. The whole `:params` map value becomes `:rf/redacted`.
5. **Redact ALL `:url` query-string param values** when the effective `:sensitive?` is true (broader rule than the always-on denylist) — when the request is sensitive, anything that rides the wire is. Non-denylisted params (e.g. `user_id=42`) are scrubbed alongside denylisted ones.
6. **Stamp `:sensitive?`** on the trace event per [Spec 009 §Trace-event field](009-Instrumentation.md#trace-event-field-sensitive-at-the-top-level). The canonical contract is that the flag rides at the top level of the trace envelope (consumers consult `(:sensitive? ev)` for a one-keyword read). The HTTP layer stamps `:sensitive? true` on the tags map passed to `trace/emit!` / `trace/emit-error!`. A **query-param denylist hit alone** (no per-request / per-call `:sensitive?`) also stamps `:sensitive? true` — the denylisted name is itself the signal. If the core trace surface implements the hoist (Spec 009 §Privacy core-stamping), the flag is moved from tags to top-level by the emit walker; if core does not yet hoist, the flag stays under `:tags`. Once core lands the hoist universally, the tags-slot becomes redundant but harmless. Absent (NOT `false`) when not sensitive — per Spec 009 line 1176 "Consumers treat absent as false."

The `:sensitive?` flag a `:rf.http/*` trace event carries is the one resolved for **that specific request** from its per-request / per-call opt-ins (plus any automatic query-param-denylist stamp). Sensitivity does not transitively propagate across a dispatch cascade — a request fired from a non-sensitive call stays non-sensitive even when an ancestor handler in the cascade fired a sensitive request, and vice versa. The OR-reduce-by-cascade rollup, if a consumer wants one, is the consumer's responsibility (group by `:dispatch-id`).

The header and query-param denylist redaction (rules 1–2) consults the **built-in defaults unioned with the emitting frame's frame-local carriers** — see [§Frame-local carriers](#frame-local-carriers-ep-0015-3) below.

### Frame-local carriers (EP-0015 §3)

App-specific sensitive header and query-param names are declared on the **frame**, not through a process-global mutation. re-frame2 is multi-frame: durable frame-wide facts (which carrier names this frame's HTTP traffic treats as secret) belong to frame config (EP-0015 §3 / [Spec 015 §Frame-owned durable classification](015-Data-Classification.md#frame-owned-durable-classification)), so different frames can route to different carrier policies.

```clojure
(rf/reg-frame :app/main
  {:sensitive {:http {:headers      ["X-Honeycomb-Team"]
                      :query-params ["shop_token"]}}})
```

Semantics:

- The carrier names are **frame-local extensions** to the immutable built-in denylists ([§1](#1-header-denylist-always-on) / [§2](#2-query-param-denylist-always-on)). They **union** onto the defaults; they never replace or remove a built-in name.
- The names are validated at `reg-frame` time (non-string carrier names fail loudly — [Spec 015 §Frame-owned durable classification](015-Data-Classification.md#frame-owned-durable-classification)); they ride the frame's durable config. The HTTP privacy redactor resolves the emitting frame's extension sets (lower-cased) once per emit via `re-frame.frame-classification/http-carriers` and unions them onto the built-in defaults.
- The **emitting frame** is the one the request was issued from (the `:rf.http/managed` fx carries the cascade-envelope frame, propagated onto the in-flight handle so even an actor-destroy abort trace fired from the registry resolves the right frame's carriers). A completion that fires with no resolvable frame applies the built-in defaults only.
- A frame-local query-param carrier hit **stamps `:sensitive? true`** on the trace event exactly like a built-in denylist hit — the carrier name is the signal.

This replaces the removed process-global `declare-sensitive-header!` / `declare-sensitive-query-param!` mutators (EP-0015 §3).

### Response-body classification (EP-0015 §5)

Header and query-param policy covers the request carriers; the **response body** (login / refresh / partner-API / upload-URL / opaque-token responses) is classified separately. Per [EP-0015 issue 5 (ruled)](../docs/EP/EP-0015-frame-owned-egress-policy.md#open-issues) and [Spec 015 §HTTP response bodies](015-Data-Classification.md#http-response-bodies), a response body is a **registration-owned transient payload classified per-slot via `:sensitive?` / `:large?` props on the request's `:decode` SCHEMA** — the [Spec 010](010-Schemas.md) / EP-0005 schema-prop mechanism reused (the `:decode` schema is the owner's natural declaration surface for the body shape, so per-slot props are the *one* route — not a second route to classify a frame-owned app-db path; [Spec 015 §Schemas describe shape](015-Data-Classification.md#schemas-describe-shape-not-durable-app-db-egress-policy)).

```clojure
;; The :decode schema is the owner's declaration of the body shape AND its
;; per-slot sensitivity. [:token] is sensitive; [:user-id] is not.
(rf/reg-event :auth/login
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request {:method :post :url "/auth/login" :body creds}
            :decode  [:map
                      [:token {:sensitive? true} :string]
                      [:user-id :int]]
            :on-success [:auth/logged-in]}]]}))
```

Rules:

1. **Per-slot.** A `:sensitive?` prop on a `:decode`-schema slot redacts that slot of the decoded body (to the `:rf/redacted` sentinel), and a `:large?` prop elides that slot (to the `:rf.size/large-elided` marker), **before** the body rides a `:rf.http/*` trace event — and they fire **independently of** the per-call / per-request `:sensitive?` flag ([§3](#3-per-request--per-call-sensitive)). The owner's schema declaration is the signal. A non-marked sibling slot rides verbatim.
2. **Whole-body root prop.** A root-level `:sensitive?` prop on the `:decode` schema (e.g. `[:string {:sensitive? true}]` — an opaque-token response whose entire body is the secret) redacts the whole body; a root-level `:large?` prop elides the whole body to the size marker.
3. **Unschematized is whole-sensitive (fail-closed).** Only a Malli-**schema** `:decode` carries per-slot marks. The keyword decode modes (`:auto` / `:json` / `:text` / `:blob` / `:array-buffer` / `:form-data`) and a custom decoder fn are not schemas. An **unschematized** body has an unknown shape: for an **off-box** egress (production capture / off-box trace) it is treated as whole-sensitive and **omitted entirely** unless a classified projection is explicitly requested; on the in-process **dev** trace stream it rides governed by the per-call `:sensitive?` flag as before (the local operator inspects their own process). The disposition is `re-frame.http-privacy-body/off-box-body-disposition` (`:omit` for an unschematized body, `:classify` for a schema body); the HTTP trace-emit site **stamps it forward** on the `:rf.http/replied` / `:rf.http/accept-failure` trace event under `:rf.http/off-box-body`, and the off-box trace-events egress projector (`re-frame.epoch.tool-pair`) **enforces** it — omitting the body slot of an `:omit` event (the request's `:decode` is request-private and never on the trace event, so the disposition must travel forward on it).
   - **Opaque schema refs fail closed off-box (`:classify` is INTROSPECTABLE-only).** "Schema body → `:classify`" applies only to an **introspectable** schema — the raw EDN **vector** form (`[op props? …]`, the shape `(rf/reg-app-schema …)` users write) — the only form the shared per-slot schema walker ([Spec 010](010-Schemas.md), [Spec-Schemas §discoverability caveat](Spec-Schemas.md)) can read marks from. A **keyword registry ref** (`:my-app/token-schema`) and a **compiled `m/schema` object** are opaque leaves: the walker (the `:schema` value is opaque to re-frame — [Spec 010 §Default validator and the validator-fn extension point](010-Schemas.md#default-validator-and-the-validator-fn-extension-point) — so the framework MUST NOT resolve the registry / introspect the validator) returns **no** `:sensitive?` / `:large?` paths for them, so a `:classify` disposition would ride the body **unchanged** off-box — the [EP-0015 issue 5](../docs/EP/EP-0015-frame-owned-egress-policy.md#open-issues) fail-OPEN where classification is **unknown**. Off-box therefore fails **closed** to `:omit` for any opaque (non-vector) schema `:decode`; the disposition gates on `re-frame.http-privacy-body/introspectable-schema-decode?` (`schema-decode?` ∧ `vector?`), not the broader `schema-decode?`. On the **dev** trace the on-box per-slot classification still keys on `schema-decode?` — a harmless no-op on an opaque leaf (the local operator sees their own raw process). The supported route for marks to apply off-box is **registering / supplying the vector form** the walker can introspect.
4. **Raw error-response bodies are unconditionally `:omit` off-box (fail-closed).** The **failure-category** trace events carry a *raw* (never-decoded) response body: `:rf.http/http-4xx` / `:rf.http/http-5xx` carry it at `:body`, `:rf.http/decode-failure` carries the raw text at `:body-text`, and `:rf.http/retry-attempt` nests an intermediate failure (which may carry one of those raw bodies) under `:failure`. Such a body is **unschematized by construction** — status classification (4xx/5xx) runs **before** decode, and a `:rf.http/decode-failure` is the decode step itself failing — so the request's `:decode` schema was never (and could never be) applied to it. The off-box disposition is therefore **unconditionally `:omit`**, irrespective of the per-call / per-request `:sensitive?` flag (error bodies frequently echo request context or tokens). The emit site stamps `:rf.http/off-box-body :omit` on these events; the off-box trace-events egress projector omits the raw body slot, lifted only by the trusted-local `:include-sensitive?` opt-in (the `local-raw` boundary). On-box, the raw body rides verbatim for the local operator (the omission is the off-box boundary). This closes the disposition-5 fail-OPEN where a raw error body was previously redacted off-box *only* when the call carried a per-call `:sensitive?` flag.
5. **Composition.** The per-call `:sensitive?` flag remains the **coarse** escape hatch (it redacts the whole body wholesale, regardless of schema marks); response-body classification is the **fine-grained** schema-driven layer that fires irrespective of that flag, and the off-box `:omit` of an unschematized / raw error body fires irrespective of it too. Sensitive wins over large (the shared `rf/elide-wire-value` ordering). The decoded body rides at `:value` on the `:rf.http/replied` success trace and at `:decoded` on an `:rf.http/accept-failure` trace; both apply the `:decode`-schema `:sensitive?` **and** `:large?` marks.

### Composition

| Surface | Behaviour |
|---|---|
| × `:large?` ([Spec 009 §Size elision](009-Instrumentation.md#size-elision-in-traces)) | A `:decode`-schema slot marked `:large?` (only) elides to the `:rf.size/large-elided` marker on the body trace, the parallel axis to `:sensitive?`. A slot marked BOTH sensitive AND large redacts to `:rf/redacted` (sensitive wins per Spec 009's unified `rf/elide-wire-value` walker — the size marker, which would carry `:path` / `:bytes` / `:digest`, is not emitted for a sensitive slot). |
| × Registration-owned `:sensitive` event-payload classification ([009 §Schema-installed redaction](009-Instrumentation.md#schema-installed-redaction) + [015](015-Data-Classification.md)) | Registration-owned `:sensitive` classifies event-vector payload paths (scrubbed by the router's internal redaction plumbing); the HTTP redactor operates on `:rf.http/*` trace-event slots. Both compose additively — a frame whose handler classifies its event payload AND issues a managed request gets event-vector redaction AND HTTP trace redaction. (The positional public `redact-interceptor` was removed from the façade per EP-0015 §7; the underlying fn is internal router plumbing only.) |
| × Spec 014 §Middleware | Request-side interceptors run **before** the privacy machinery reads `:sensitive?` (the interceptor chain may itself attach an `Authorization` header). Headers added by interceptors are subject to the same denylist. |
| × Spec 014 §Failure categories | Every category that carries body-side payload (`:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`) gets body redaction. **On-box**, the per-call `:sensitive?` flag redacts the body slot wholesale (the coarse escape hatch). **Off-box**, a raw error body (`:body` on 4xx/5xx, `:body-text` on decode-failure, and the same nested under a `:rf.http/retry-attempt` `:failure`) is **unconditionally omitted** — it is unschematized by construction (status classification runs before decode), so it fails closed irrespective of the per-call flag ([Rule 4](#response-body-classification-ep-0015-5)). An `:rf.http/accept-failure`'s `:decoded` body rides the schema disposition (`:classify` when the request carried a `:decode` schema, else `:omit`). `:rf.http/aborted` carries no body so no body redaction; headers (the denylist) still apply. |
| × Spec 005 actor-destroy abort | The in-flight handle propagates the effective `:sensitive?` flag, so the `:rf.http/aborted-on-actor-destroy` emit (issued from the registry namespace, distant from the originating fx ctx) still stamps correctly. |
| × WebSockets (app/library-built) | re-frame2 does not ship a managed WebSocket, but an app or library that builds one per [Pattern-WebSocket](Pattern-WebSocket.md) can reuse the same denylist + per-request / per-call `:sensitive?` machinery; the per-message frame-stamping rule is its own affair, but the request-side concerns are shared. |

### Production elision

The HTTP privacy machinery rides the trace surface and elides with it:

- The redact / stamp helpers all gate on `interop/debug-enabled?` at their call sites (the same gate as `trace/emit!` and `trace/emit-error!`). In `:advanced` + `goog.DEBUG=false` builds Closure DCE removes the trace emits AND the redaction step that prepares them.
- The immutable built-in denylists ship in production as plain data; a frame's frame-local carrier extension sets ride its durable config. The walkers (header / query-param / response-body) only run when a trace emit fires, so production builds that elide the trace surface incur no runtime cost.
- Handler-meta `:sensitive?` is no longer consulted (the annotation has been removed). Per-call `:sensitive?` on the `:rf.http/managed` args map is the supported per-request sensitivity opt-in.

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

## Open questions

> **SA-4 classification.** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md): both items are **post-v1, untracked notes** — additive surfaces that do not block v1 and have no tracking bead filed yet (so neither qualifies as `:post-v1 tracked`, which requires a `rf2-<id>`). A tracking bead is filed for each only when its "deferred until …" condition is met.

### Streaming responses (`:rf.http/streaming`) (post-v1)

Per [§What Spec 014 does NOT cover](#what-spec-014-does-not-cover) streaming responses (chunked HTTP, server-sent events) ship in a sibling spec. The per-chunk event model is a different shape from the single-reply `:rf.http/managed` contract and needs its own envelope; the contract here remains the request → single-reply shape. Deferred to a sibling spec post-v1 (untracked note — no bead filed yet).

### Pluggable backoff strategy (post-v1)

Per [§Retry and backoff](#retry-and-backoff) v1 ships a fixed exponential-with-jitter backoff. Pluggable backoff (per-call strategy fn, registered named strategies, host-customisable defaults) is an additive surface — deferred until apps surface a real need that the default doesn't cover.

## Resolved decisions

### `:rf.http/managed` is the canonical framework-provided fx

Per [§Implementation status](#implementation-status) `:rf.http/managed` is the locked v1 surface: args-map shape, failure categories, reply addressing, retry semantics, abort surface, schema-reflection metadata, and trace events are all locked across implementations. This was chosen over a "convention" (every app rolls its own HTTP fx) so `:fx-overrides` target the same id across applications, pair tools introspect the same envelope, Spec 010 schemas plug into the same decode pipeline, and conformance fixtures key off the canonical surface.

### Failure categories are a closed set

Per [§Failure categories (closed set)](#failure-categories-closed-set) the failure taxonomy under `:rf.http/*` (`:transport`, `:cors`, `:timeout`, `:http-4xx`, `:http-5xx`, `:decode-failure`, `:accept-failure`, `:aborted`) is closed for v1. Additions require a Spec change. Apps that want domain-level discrimination layer `:accept` (per [§`:accept` — domain-failure normalisation](#accept--domain-failure-normalisation)) — they don't extend the framework's failure taxonomy. This keeps the `:rf.http/*` trace vocabulary decidable for tools and the [Spec 009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) finite.

### `:rf.http/cors` is CLJS-only

Per [§Failure categories (closed set)](#failure-categories-closed-set) the `:rf.http/cors` row is CLJS-only — JVM transports never emit it. CORS is a browser-policy concern; the JVM has no cross-origin policy to enforce. The asymmetry is documented so tools that consume the trace stream don't assume the row exists on every host.

### Per-frame interceptor chain with both request- and response-side phases

Per [§Middleware](#middleware) the v1 middleware contract is a per-frame interceptor chain carrying both phases: a `:before` request-side transform between the user's args and the transport, and an `:after` response-side transform between the transport's reply and the `:on-success` / `:on-failure` dispatch. The request-side cases (Bearer token, correlation-id, base-URL rewrite) and the response-side cases (rate-limit header parsing, response-time telemetry, 401 auth-refresh tagging) were chosen as the high-frequency patterns. The two phases compose via one `{:id :before :after}` registration that mirrors the event-interceptor onion (Spec 002): `:before` runs in registration order, `:after` in reverse, and the `:after` sees the same ctx the `:before` chain produced for that request — so request-correlated response handling (e.g. a start-mark stamped by `:before`, read by `:after`) is a single-interceptor concern. Response-side composition with `:accept` is settled: `:accept` runs first inside the transport (domain-failure normalisation produces the `{:kind :success|:failure}` response), then the `:after` chain transforms that response before the reply dispatch.

### Frame-aware reply dispatch

Per [§Frame awareness](#frame-awareness) every `:rf.http/managed` reply dispatch inherits the originating frame; replies route to the right frame even when the request was issued from a non-default frame (story variant, per-test fixture, SSR per-request). The frame-capture discipline matches [Pattern-AsyncEffect](Pattern-AsyncEffect.md) and is universal across the async-effect surface.

### Actor-destroy aborts in-flight requests

Per [§Aborts](#aborts) `:rf.http/managed` requests issued from inside a spawned state-machine actor are aborted automatically when the actor is destroyed. The actor-id-keyed in-flight map (per [§Abort on actor destroy](#abort-on-actor-destroy)) is the reverse index; `actor-in-flight-snapshot` is the test-only inspection helper. This was chosen over "orphan the request and ignore the reply" because orphaned requests waste transport quota and the reply path's frame-target may no longer exist — both costs grow under retries.

### Privacy honoured via `:sensitive?` on HTTP trace events

Per [§Privacy](#privacy) the `:rf.http/*` trace events honour the [Spec 009 §`:sensitive?`](009-Instrumentation.md#privacy--sensitive-data-in-traces) contract: per-call and per-request `:sensitive?` flags OR-reduce (handler-meta `:sensitive?` is no longer a source); the framework redacts request/response bodies and a 12-name header denylist (`authorization`, `cookie`, `set-cookie`, etc.). Headers were chosen as the always-on default surface because they carry the highest-value secrets (auth tokens) across the largest fraction of apps. Apps register their own sensitive carrier names on the **frame** via `:sensitive {:http {...}}` (EP-0015 §3 — [§Frame-local carriers](#frame-local-carriers-ep-0015-3)), and response bodies classify per-slot via the request's `:decode` schema ([§Response-body classification](#response-body-classification-ep-0015-5)).

### Query-string denylist is always-on

Per [§2. Query-param denylist (always-on)](#2-query-param-denylist-always-on) the framework redacts denylisted query-string parameter values in `:url` slots **regardless** of the effective `:sensitive?` flag. Param-name and position are preserved; the value is replaced inline with the `:rf/redacted` text token. Always-on was chosen over flag-gated because query-string-auth patterns (older REST APIs, webhooks) leak through `:rf.http/retry-attempt` and similar URL-carrying traces even when the dispatching event isn't `:sensitive?` — the redaction must run unconditionally for the URL slot to be safe.

### Stale-suppression piggy-backs on the epoch carry

Per [Pattern-StaleDetection](Pattern-StaleDetection.md) and [§Reply addressing](#reply-addressing) managed requests inherit the dispatching event's epoch carry; replies that arrive after a newer navigation / actor restart are suppressed at the dispatch site. The same epoch idiom is used by `:after` timers (per [Spec 005 §Epoch-based stale detection](005-StateMachines.md#epoch-based-stale-detection)) and route nav-tokens (per [Spec 012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression)); the recurring pattern is documented in [Pattern-StaleDetection](Pattern-StaleDetection.md).

## Cross-references

- [Pattern-AsyncEffect](Pattern-AsyncEffect.md) — generic six-step async shape; Spec 014 specialises it.
- [Pattern-RemoteData](Pattern-RemoteData.md) — the 5-key request-lifecycle slice; `:rf.http/managed` writes through this slice.
- [Pattern-StaleDetection](Pattern-StaleDetection.md) — epoch carry; managed requests inherit it.
- [Spec 002 §Routing](002-Frames.md#routing-the-dispatch-envelope) — frame-aware fx contract; reply dispatches inherit `:frame`.
- [Spec 005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions) — the substrate semantic retry rides on; the machine fires a transition on the failure reply, optionally delays via `:after`, and re-issues the request from the next state's `:spawn`.
- [Spec 005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) — multi-request semantic retry (refresh-then-retry, fan-out-with-conditional-retry) lives here.
- [Spec 009 §Trace event envelope](009-Instrumentation.md) — trace envelope shape; `:rf.http/retry-attempt`, `:rf.warning/failure-swallowed`, and the `:rf.http/*` failure-category traces follow it.
- [Spec 010 §Schemas](010-Schemas.md) — the schema language `:decode <schema>` consumes. Spec 010 standardises the schema-attachment surface (`:schema` metadata, `reg-app-schema`, `app-schemas-digest`) and the pluggable validator seam (Malli is the CLJS reference's default); the `:rf.http/managed` decode step parses the response body and applies the registered schema language's decode-or-validate primitive (on CLJS reference: `malli.core/decode` + `malli.transform/json-transformer`). There is no separate "Spec 010 decode pipeline" — the decode contract belongs to this Spec; Spec 010 provides the schema language.
- [Pattern-Boot §Worked example — auth-machine and the retry-ownership boundary](Pattern-Boot.md#worked-example--auth-machine-and-the-retry-ownership-boundary) — the canonical end-to-end illustration of [§Boundary — transport vs semantic retry](#boundary--transport-vs-semantic-retry).
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — the `:rf.http/*` namespace is reserved for this Spec.
- [`re-frame-fetch-fx`](https://github.com/superstructor/re-frame-fetch-fx) — the inspiration; Spec 014 adds retry, accept-step, default-reply-addressing, schema-driven decode, JVM coverage, and stale-suppression on top.
