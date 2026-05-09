# Spec 014 — HTTP requests

> Status: Drafting. **v1-optional capability.** Implementations MAY ship `:rf.http/managed`; when they do, the contract below is fixed. The CLJS reference implementation ships it (see [§Implementation status](#implementation-status)). Builds on the registration grammar in [001-Registration](001-Registration.md), the dispatch envelope and frame routing in [002-Frames §Routing](002-Frames.md#routing-the-dispatch-envelope), the trace-stream contract in [009-Instrumentation](009-Instrumentation.md), schema integration in [010-Schemas](010-Schemas.md), and the reserved-namespace policy in [Conventions](Conventions.md).
>
> **The minimum claim:** *if* an implementation ships HTTP-request infrastructure, it ships `:rf.http/managed` per this spec — a first-class HTTP request fx that bakes in decoding, success/failure normalisation, retry-with-backoff, abort, schema-driven decode, and reply-to-origin dispatch. The fx is rich enough that apps overwhelmingly want it; the contract being uniform is what lets pair tools, `:fx-overrides`, retry policy, error projection, and frame-aware reply addressing compose across implementations without per-app reinvention.
>
> **Code samples are in ClojureScript** (the CLJS reference). The contract is host-agnostic; the spec calls out per-host divergences (CLJS Fetch / JVM `java.net.http.HttpClient`) explicitly per row.

## Abstract

`:rf.http/managed` is the canonical HTTP fx for re-frame2 implementations that ship one. Take an args map, get a request issued; the fx handles transport, decoding, retry-with-backoff, and dispatching the reply back into the runtime. Co-located request and reply handling is the default — one event handler can branch on `(:rf/reply msg)` to handle both initial dispatch and async result — but explicit `:on-success` / `:on-failure` targets switch to the separate-handler shape when that fits better.

The fx specialises [Pattern-AsyncEffect](Pattern-AsyncEffect.md)'s generic six-step shape (register → return `:fx` → post work → reply → dispatch → commit), pins the lifecycle slice from [Pattern-RemoteData](Pattern-RemoteData.md), and inherits the epoch carry from [Pattern-StaleDetection](Pattern-StaleDetection.md). It complements but does not replace the lower-level `:http` fx — apps that need wire-level control (custom transport, raw bytes, idiosyncratic protocols) keep using `:http`; apps that want the common case ergonomic use `:rf.http/managed`.

Streaming and bidirectional communication are deliberately out of scope here — Spec 014 covers the single-request / single-reply shape. WebSocket / SSE / chunked-streaming are handled by sibling specs (Pattern-WebSocket; future `:rf.http/streaming`).

## Implementation status

Spec 014 is an **optional capability** in the [000-Vision §Capability matrix](000-Vision.md) sense. Implementations MAY:

- **Ship `:rf.http/managed` per this spec.** Then the contract below applies — args map shape, failure categories, reply addressing, retry semantics, abort surface, schema-reflection metadata, and trace events all locked. Pair tools and conformance fixtures key off the canonical surface.
- **Omit it.** Applications that need HTTP roll their own fx (or use a third-party library) per [Pattern-AsyncEffect](Pattern-AsyncEffect.md)'s generic shape. The omission is a conformance-set difference, not a defect.

The **CLJS reference implementation ships `:rf.http/managed`**, backed by Fetch on the browser and `java.net.http.HttpClient` on the JVM. Other-language ports (TypeScript, Python, Kotlin) decide independently. A port that omits `:rf.http/managed` MUST NOT register the `:rf.http/*` namespace for any other purpose (it's reserved for this Spec; see [Conventions](Conventions.md)).

If an implementation ships ONLY a subset (e.g., no JVM transport), it claims the relevant capability rows and the conformance corpus exercises only those.

**Artefact (CLJS reference).** Per [rf2-5kpd](#) (the fifth per-feature artefact split per [rf2-5vjj](#) Strategy B), the CLJS reference's managed-HTTP surface ships in the separate Maven artefact `day8/re-frame-2-http` — `re-frame.http-managed` namespace, the four `:rf.http/*` fxs registered at ns-load time, the in-flight request registry, the Fetch / HttpClient transport adapters, the encode / decode pipeline, the retry-with-backoff machinery, the eight-category `:rf.http/*` failure taxonomy, and the `with-managed-request-stubs` test helper. The core artefact (`day8/re-frame-2`) no longer carries any of this; apps that don't issue managed-HTTP requests build an `:advanced` bundle clean of every `:rf.http/*` symbol and trace string. See [MIGRATION §M-31](MIGRATION.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame-2-http) for the deps swap.

## Role

`:rf.http/managed`, when an implementation ships it, is **framework-provided** — the implementation registers the fx; applications use it the way they'd use `:dispatch` or `:db`. This is what makes it a Spec rather than a convention: the public contract is locked, `:fx-overrides` target the same id across applications, pair tools introspect the same envelope, and Spec 010 schemas plug into the same decode pipeline universally.

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
| `:timeout-ms` | no | int | Wall-clock timeout per attempt. Default: 30000. |
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

Then `(rf/handler-meta :event :article/load)` returns a map carrying `:rf.http/decode-schemas [ArticleResponse]`, which pair tools / `(rf/handlers :event)` enumeration / generators can introspect.

**Optional, never enforced.** The runtime does NOT cross-check that the call-site `:decode` matches the declared schemas — the metadata is reflective sugar for tooling, not a runtime contract. A handler that declares one schema and uses another still works. (If you want runtime enforcement, you're really asking for a `defmanaged-event-fx` macro that DRY's the declaration and the call-site reference; out of v1 scope.)

For handlers that issue multiple `:rf.http/managed` requests with different schemas, list all of them: `:rf.http/decode-schemas [ArticleResponse CommentList Profile]`.

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
| `:on` | set of category keywords | Which failure categories trigger a retry. Drawn from the `:rf.http/*` set in [§Failure categories](#failure-categories-closed-set). Common defaults: `#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}`. `:rf.http/aborted` is never retried regardless of `:on`. |
| `:max-attempts` | int | Total attempts including the first. `1` = no retry. Default: 1. |
| `:backoff` | map | Exponential backoff config. |
| `:backoff.:base-ms` | int | Initial delay (ms). |
| `:backoff.:factor` | num | Multiplier per attempt. |
| `:backoff.:max-ms` | int | Cap on delay. |
| `:backoff.:jitter` | bool | Add random ±25% jitter to each delay. |

Each retry advances the carried epoch (per Pattern-StaleDetection); a stale request (e.g. one whose target route changed mid-retry) is suppressed without dispatching the reply.

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

1. **Transport / timeout / abort.** A network error, per-attempt timeout, or abort short-circuits the rest. Classified as `:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, or `:rf.http/aborted`. The body never enters the picture.
2. **HTTP status.** Once a response lands, status is checked **before** the body is touched.
   - `2xx` → success-eligible; proceed to decode.
   - `4xx` → `:rf.http/http-4xx`; the raw response text is surfaced at `:body`. Decode is skipped.
   - `5xx` → `:rf.http/http-5xx`; same shape as `:http-4xx`. Decode is skipped.
   - Anything else (a 1xx/3xx the runtime didn't follow) → `:rf.http/http-4xx`-shaped.
3. **Decode** (only on 2xx). The configured `:decode` runs against the body. A throw / Malli rejection / parser error here classifies as `:rf.http/decode-failure`.
4. **Accept** (only on a successful decode). The configured `:accept` (or the default) projects the decoded value to `{:ok v}` or `{:failure m}`. A `{:failure m}` here classifies as `:rf.http/accept-failure`.

The order is **status-before-decode by design**: a JSON-API endpoint that returns an HTML 404 from a load balancer (or a CORS pre-flight 4xx with a generic HTML body, or a 503 with a Cloudfront error page) classifies as `:rf.http/http-4xx` / `:rf.http/http-5xx` with the raw body at `:body`, not as `:rf.http/decode-failure`. The HTTP failure category is the load-bearing piece of information for the caller; surfacing decode-failure on a 4xx would hide the real error.

If a caller wants to see the structured error body that an API returns alongside a non-2xx (e.g., `{"error": "..."}` JSON on a 4xx), the caller decodes the raw `:body` themselves in the failure-handling branch — the framework hands you the bytes and the status, and you decide what to do with them.

## Failure categories (closed set)

Every failure carries a `:kind` keyword (under the framework-reserved `:rf.http/*` namespace) plus category-specific tags. `:kind` is **framework-owned**; user payloads (from `:accept`) sit at `:detail`, never at `:kind`.

| `:kind` | When | Tags |
|---|---|---|
| `:rf.http/transport` | Network / DNS / connection-refused / connection-reset error before the HTTP transaction completed | `:message`, `:cause` |
| `:rf.http/cors` | CORS preflight rejected or response blocked by browser CORS policy. Distinct from `:transport` because CORS is a configuration error, not a network error. CLJS-only; JVM never emits this. | `:message`, `:url` |
| `:rf.http/timeout` | Per-attempt timeout fired | `:elapsed-ms`, `:limit-ms` |
| `:rf.http/http-4xx` | Non-2xx 4xx response | `:status`, `:status-text`, `:body` (the raw response text — decode is skipped on non-2xx; see [§Classification order](#classification-order)), `:headers` |
| `:rf.http/http-5xx` | Non-2xx 5xx response | same as `:http-4xx` |
| `:rf.http/decode-failure` | A success-eligible (2xx) response whose body the decode pipeline rejected (Malli error, JSON syntax error, custom decoder threw). Non-2xx responses never produce `:rf.http/decode-failure` — they classify by status. | `:body-text`, `:cause`, `:malli-error?` |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}`. The user's failure map sits at `:detail`; `:decoded` carries the pre-`:accept` decoded value for context. | `:detail` (user's verbatim failure map), `:decoded` |
| `:rf.http/aborted` | The request was aborted via `:request-id` or `:abort-signal` | `:request-id` (if any), `:reason` (`:user` / `:request-id-superseded`) |

The category vocabulary is **closed for v1** — additions require a Spec change. The `:rf.http/*` namespace makes these unambiguous wherever they leak: trace events, error projector, `:retry :on` sets, epoch records.

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

When two in-flight requests share an id, issuing a new one with the same id supersedes the old one (`:reason :request-id-superseded` on the aborted reply); a manual `:rf.http/managed-abort` aborts whichever request currently holds the id (`:reason :user`).

### `:abort-signal` (external)

Pass an `AbortController.signal` directly:

```clojure
:abort-signal (.-signal my-controller)
```

The fx threads the signal through to the underlying transport. User owns the controller's lifecycle. CLJS-only (Fetch supports it; XHR fallback ignores).

The two are mutually exclusive — pick one.

## Frame awareness

The reply dispatch lands in the **same frame** the request was issued from. The fx captures `:frame` from the dispatch envelope's cofx (per Spec 002 §Routing) and threads it through to the reply dispatch's `{:frame ...}` opt. Multi-frame apps work without extra ceremony.

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

For test suites that exercise many requests, a higher-level helper ships:

```clojure
(rf/with-managed-request-stubs
  {[:get "/articles/hello"] {:reply {:ok hello-article}}
   [:get "/articles/missing"] {:reply {:failure {:kind :rf.http/http-4xx :status 404}}}}
  ...)
```

The helper inspects each `:rf.http/managed` invocation's `:request :method` + `:request :url` and routes through the configured reply.

## What Spec 014 does NOT cover

Adjacent surfaces that are first-class re-frame2 commitments but live in their own specs:

- **Streaming responses** (chunked HTTP, server-sent events). Different shape — per-chunk events, not single reply. Future `:rf.http/streaming` fx; sibling spec.
- **WebSocket** — bidirectional. Lives in [Pattern-WebSocket](Pattern-WebSocket.md); state-machine-shaped.
- **GraphQL-specific batching / persisted queries.** Layer on top — `:rf.http/managed` hands you the decoded response, your application wraps for batching.
- **HTTP/2 server push.** Not a re-frame2 concern; the platform handles it transparently.

## Cross-references

- [Pattern-AsyncEffect](Pattern-AsyncEffect.md) — generic six-step async shape; Spec 014 specialises it.
- [Pattern-RemoteData](Pattern-RemoteData.md) — the 5-key request-lifecycle slice; `:rf.http/managed` writes through this slice.
- [Pattern-StaleDetection](Pattern-StaleDetection.md) — epoch carry; managed requests inherit it.
- [Spec 002 §Routing](002-Frames.md#routing-the-dispatch-envelope) — frame-aware fx contract; reply dispatches inherit `:frame`.
- [Spec 009 §Trace event envelope](009-Instrumentation.md) — trace envelope shape; `:rf.http/retry-attempt`, `:rf.warning/decode-defaulted`, and the `:rf.http/*` failure-category traces follow it.
- [Spec 010 §Schemas](010-Schemas.md) — Malli decode integration; `:decode <schema>` runs through `010`'s decode pipeline.
- [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) — the `:rf.http/*` namespace is reserved for this Spec.
- [`re-frame-fetch-fx`](https://github.com/superstructor/re-frame-fetch-fx) — the inspiration; Spec 014 adds retry, accept-step, default-reply-addressing, schema-driven decode, JVM coverage, and stale-suppression on top.
