# Managed HTTP reference

`:rf.http/managed` is the one [effect](../core/glossary.md#effect) for talking to a server. You describe the request as data, the runtime performs it — decoding, failure classification, retry, cancellation — and the reply arrives as an ordinary [event](../core/glossary.md#event).

This page is the reference: every key, every contract. If you're new, do the [tutorial](tutorial.md) first. For the idea underneath, read [Why no await](continuations-are-data.md). For API-level detail — interceptor fn signatures, test-stub surfaces, trace events — see [re-frame.http](../api/re-frame.http.md).

## Setup

Managed HTTP ships in its own artefact, `day8/re-frame2-http`, so apps that never issue a request build a bundle clean of it. Add the dep and require `re-frame.http.managed` once at app boot — that registers `:rf.http/managed` and family. Call a managed fx without the artefact loaded and the `re-frame.core` re-exports fail loud with a named "HTTP artefact missing" error rather than a silent miss.

## The args map

Every key you can hand `:rf.http/managed`. Only `:request` (with a `:url`) is required; everything else has a sane default, which is why the common case stays short.

| Key | What it does | Default |
|---|---|---|
| `:request` | The wire request as data — `:method` / `:url` / `:headers` / `:params` / `:body` and the [rest of the envelope](#the-request-is-a-map). | **required** |
| `:on-success` | Event vector for success replies. Omit it to route success back to the originating event; set it to `nil` to [silence](#silencing-a-reply) success replies. | omitted |
| `:on-failure` | Event vector for failure replies. Omit it to route failure back to the originating event; set it to `nil` to [silence](#silencing-a-reply) failure replies. | omitted |
| `:decode` | Parse and [validate the 2xx body](#validating-the-body-with-decode) — a [schema](../core/glossary.md#schema), a keyword, or a fn. | `:auto` |
| `:accept` | A [post-decode domain check](#a-valid-200-can-still-be-a-failure-accept) — a 200 can still be a failure. | `{:ok decoded}` |
| `:retry` | A [transport-retry policy](#retry-transport-retry-as-data) — `:on` set, `:max-attempts`, `:backoff`. | none |
| `:request-id` | A stable id; a new request with the same id [supersedes the old](#cancellation-supersession-and-abort). | none |
| `:abort-signal` | An external `AbortController` `.signal` to cancel through. Browser-only. | none |
| `:timeout-ms` | Per-attempt [timeout](#timeouts); `nil` or `0` opts out. | `30000` |
| `:sensitive?` | Redact body, params, and all URL values in traces — see [keeping secrets](http-going-further.md#keeping-secrets-out-of-the-trace). | `false` |
| `:rf.http/max-decoded-keys` | Caps how many unique JSON object keys the decoder may intern. Raise it only for unusually large trusted payloads. | `10000` |

The common case:

```clojure
{:fx [[:rf.http/managed
       {:request    {:url "/api/articles/intro"}
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

## The request is a map

`:request` *is* the wire request, expressed as data, and it grows to cover any real call. A POST with a JSON body, query params, and a header is still just data:

```clojure
{:fx [[:rf.http/managed
       {:request    {:method  :post
                     :url     (str "/api/articles/" slug "/comments")
                     :params  {:notify true}
                     :headers {"X-Client" "web"}
                     :body    {:comment {:body text}}
                     :request-content-type :json}   ;; serialises :body + sets Content-Type
        :decode     CommentResponse
        :on-success [:comment/created]
        :on-failure [:comment/create-error]}]]}
```

The full envelope:

| Key | Default | Notes |
|---|---|---|
| `:method` | `:get` | `:get` / `:head` / `:post` / `:put` / `:patch` / `:delete` / `:options`. |
| `:url` | **required** | A string. Validated at dispatch time *after* the interceptor chain runs, so a base-URL interceptor is honoured. |
| `:headers` | none | Map of string → string (or string → vector for multi-valued). Names are case-insensitive. |
| `:params` | none | Map of query-string params. URL-encoded and merged onto `:url` for you. |
| `:body` | none | A Clojure collection, string, `FormData`, `Blob`, `ArrayBuffer`, or a **thunk** `(fn [] body)` invoked at send-time. |
| `:request-content-type` | none | `:json` / `:form` / `:text` / an explicit MIME. Sugar that both sets `Content-Type` and serialises `:body`. |
| `:credentials` | `:same-origin` | `:omit` / `:same-origin` / `:include`. CLJS-only; the JVM transport ignores it. |
| `:mode` | host default | Fetch passthrough. CLJS-only; ignored on the JVM. |
| `:redirect` | `:follow` | `:follow` / `:error` / `:manual`. Honoured by browser Fetch; on the JVM, `:error` and `:manual` both mean "do not auto-follow". |
| `:cache` | host default | Fetch cache mode. CLJS-only; ignored on the JVM. |
| `:referrer` | host default | Fetch referrer value. CLJS-only; ignored on the JVM. |
| `:integrity` | none | Fetch subresource-integrity value. CLJS-only; ignored on the JVM. |
| `:sensitive?` | `false` | Request-local privacy flag. Same effect as top-level `:sensitive?`: request and response values are redacted from traces. |

Three conveniences worth knowing:

- **`:params` builds the query string for you.** Hand it a map and it URL-encodes each pair and merges them onto `:url` — no hand-built `?a=1&b=2`.
- **`:request-content-type` serialises `:body` and stamps the header in one move.** `:json` runs the `:body` map through JSON serialisation and sets `Content-Type: application/json`; `:form` URL-encodes it as a form body instead. For a file upload, hand a `js/FormData` straight in as `:body` and leave `:request-content-type` off — the platform sets the multipart boundary itself.
- **A bad `:url` fails loud, not silent.** A blank, nil, or non-string `:url` is rejected at dispatch with a named bad-request error rather than falling through to the transport as an opaque failure.

!!! warning "Gotcha — don't thread auth by hand"

    Threading `"Authorization"` into each call site gets old fast and breaks the moment a token rotates. Register one [HTTP interceptor](http-going-further.md#interceptors-stamp-every-request-once) instead and drop the header from your handlers entirely.

!!! warning "Gotcha — the CLJS-only keys are silently no-ops on the JVM"

    The six Fetch-passthrough keys (`:credentials`, `:mode`, `:cache`, `:referrer`, `:integrity`, and the top-level `:abort-signal`) are meaningful against the browser Fetch API and have no `java.net.http.HttpClient` analogue. On the JVM the request still goes out — the option is just dropped — and one `:rf.http/cljs-only-key-ignored-on-jvm` warning trace fires per occurrence so the degraded path is visible. (`:redirect` is the exception: it *is* honoured on the JVM.) If a request runs on both hosts — SSR, a shared loader — keep cross-host code off these keys or feature-flag them at the call site. The same asymmetry hits `:rf.http/cors`, which only the browser ever emits.

!!! warning "Gotcha — a malformed header is dropped, not fatal"

    A header with an empty or control-character name, or a value carrying a raw `\r`/`\n` (the response-splitting guard), is rejected by the platform's header builder. Rather than failing the whole request, the runtime drops just that one pair, emits a redacted `:rf.warning/http-header-invalid` trace naming the offending header (the *value* is omitted — it may carry a secret), and sends the request with the remaining valid headers. So a stray newline in one interpolated header value quietly loses that header instead of taking down the call — watch the warning trace if a header you expected isn't arriving.

## Handling the reply

Every reply is the one **canonical reply envelope** — a plain map with a closed `:status`:

| `:status` | Shape | Notes |
|---|---|---|
| `:ok` | `{:status :ok :value value …}` | `value` is the decoded 2xx body. If you supplied `:accept`, this is the value inside `{:ok value}`. |
| `:error` | `{:status :error :error failure-map …}` | `failure-map` is one of the [category maps below](#failures-are-a-closed-set). Branch on `(-> reply :error :kind)`. |
| `:cancelled` | `{:status :cancelled :error {:kind :rf.http/aborted …} …}` | An aborted request — the `:rf.http/aborted` map rides under `:error`, with `:cancel/reason` alongside. |

So the *reply's* `:status` tells you which path (`:ok` / `:error` / `:cancelled`), and a failure's inner `:error` map carries its **own** `:kind` — the category. This is the one reply contract every async surface shares — the full envelope (`:work/id`, `:completed-at`, …) lives in [Why no await](continuations-are-data.md#one-envelope-under-every-async-surface); everyday requests read only `:status` / `:value` / `:error`.

There are two **equal** ways to receive the reply — pick by fit, not correctness.

### Separate handlers

Name `:on-success` and `:on-failure` and each outcome lands in its own handler, with the canonical reply appended as the last event argument — `[:article/loaded {:status :ok :value <decoded> …}]`. `:on-success` / `:on-failure` are pure routing sugar; both receive the identical envelope, they just route it to two named handlers:

```clojure
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed {:request    {:url (str "/api/articles/" slug)}
                             :on-success [:article/loaded]
                             :on-failure [:article/load-error]}]]}))

(rf/reg-event :article/loaded     (fn [{:keys [db]} [_ {:keys [value]}]] …))   ;; success — :value
(rf/reg-event :article/load-error (fn [{:keys [db]} [_ {:keys [error]}]] …))   ;; failure — :error
```

Three small handlers, each doing one thing. This is the shape to prefer when the success and failure paths are substantial or diverge — each reads and tests on its own.

??? info "From re-frame v1"

    Your `:http-xhrio`-style success/failure events map straight onto `:on-success` / `:on-failure` — the [migration page](../core/25-from-re-frame-v1.md) walks the translation.

### One handler

Omit `:on-success` / `:on-failure` and the reply routes back to the *originating* event, merged into the message under `:rf/reply`. One handler then serves all three roles:

```clojure
;; From examples/core/managed_http_counter (core.cljs), condensed.
(rf/reg-event :counter/+1
  (fn [{:keys [db]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      (case (:status reply)
        :ok    {:db (-> db
                        (update :counter/count + (-> reply :value :delta))
                        (assoc :counter/status :idle :counter/error nil))}
        :error {:db (assoc db :counter/status :error
                              :counter/error  (:error reply))})
      ;; Initial branch — issue the request.
      {:db (assoc db :counter/status :loading)
       :fx [[:rf.http/managed {:request {:url "/api/inc.json"}}]]})))
```

The first time it runs there is no `:rf/reply`, so the handler issues the request. When the reply comes back, the runtime dispatches the *same* event again with `:rf/reply` merged into the message. If the original message was a map, the rest of it is still there, so request context like an id or slug stays in scope. Use this shape when request and reply are two faces of one small thing — a counter, a toggle, a fire-and-refresh.

!!! warning "Gotcha — the one-handler form wants a map message"

    Dispatch a map (`[:thing/load {:id "intro"}]`) or no payload. A bare value (`[:thing/load "intro"]`) still receives the reply, but there is nowhere to merge `"intro"`, so the reply branch sees only `{:rf/reply ...}`. Put request context in a map when you need it after the reply lands.

### Delivery rules

- **Reply targets must be event vectors.** When you supply `:on-success` / `:on-failure`, each must be an event vector. `nil` means "silence this side"; a keyword, map, string, or any other non-vector value is rejected when that side's reply is dispatched, with `:rf.error/http-bad-reply-target`, so a misshaped continuation cannot be silently rerouted.
- **The reply lands in the same [frame](../core/concepts/frames.md) the request went out from.** The fx carries the frame from the original dispatch through to the reply, so a frame leak — a dispatch firing after the frame has unwound — cannot happen here. ([Frame identity is carried, not found](../core/glossary.md#frame-identity-is-carried-not-found).)
- **A stale reply is never delivered.** A reply from a superseded request ([below](#cancellation-supersession-and-abort)) does not reach your app at all; it is trace-only.

### Timestamps come from a coeffect

If a reply handler wants to record *when* something completed, don't call `(js/Date.now)` in it — a live clock read won't replay the same way twice. Declare the time as a [coeffect](../core/glossary.md#coeffect) and read it as data:

```clojure
(rf/reg-event :article/loaded
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :data]      value)
             (assoc-in [:article :loaded-at] time-ms))}))
```

`:rf/time-ms` is a *recordable* coeffect: it's stamped onto the event envelope before the handler runs, so the durable write depends on a recorded value and [replays](../core/glossary.md#time-travel) identically. (The full grade distinction is [recordable vs ambient coeffects](../core/glossary.md#recordable-vs-ambient-coeffects).)

### Silencing a reply

Set `:on-success` or `:on-failure` to `nil` and that reply is dropped — fire-and-forget, useful for a telemetry beacon you genuinely don't care to handle. But the framework won't let you *accidentally* swallow an error: the first time a non-aborted failure is dropped by `:on-failure nil`, a one-shot `:rf.warning/failure-swallowed` trace fires (dev-only) so the silence is observable rather than invisible. Aborted requests are excluded — a cancelled request that no longer wants its reply is correct silence, not a bug.

## Failures are a closed set

The failure map always carries a `:kind` from a fixed, framework-reserved list. Not a string — a keyword from a known set:

| `:kind` | When it fires | Extra keys on the failure map |
|---|---|---|
| `:rf.http/transport` | Network, DNS, connection error, or request-preparation error before the HTTP transaction completed. | `:message`, `:cause`; request-preparation failures also carry `:stage :request-prep`. |
| `:rf.http/cors` | CORS rejection. Browser-only. | `:message`, `:url`. |
| `:rf.http/timeout` | The per-attempt timeout fired. | `:elapsed-ms`, `:limit-ms`; JVM failures may also carry `:message`. |
| `:rf.http/http-4xx` | A 4xx response, plus rare non-2xx responses that are not 5xx. Decode is skipped. | `:status`, `:status-text`, `:body`, `:headers`. |
| `:rf.http/http-5xx` | A 5xx response. Decode is skipped. | `:status`, `:status-text`, `:body`, `:headers`. |
| `:rf.http/decode-failure` | A 2xx response whose body the decode pipeline rejected. | `:body-text`, `:cause`, `:schema-validation-failure?`; keyword-cap failures also carry `:reason :too-many-keys` and `:limit`. |
| `:rf.http/accept-failure` | Your `:accept` fn returned `{:failure ...}`, threw, or returned a malformed shape. | `:detail`, `:decoded`, `:request-id`. |
| `:rf.http/aborted` | Aborted via `:request-id`, abort signal, or machine actor destroy. | `:request-id`, `:reason`; some paths also carry `:actor-id` or `:message`. |

The set is closed for v1; adding a category is a versioned framework change. That constraint buys you something real: `:rf.http/timeout` means exactly the same thing in your codebase, in mine, and in every tool watching the trace stream. Branch on the `:kind`, never on a stringified message — the same discipline you'd use on any framework [error record](../core/glossary.md#error-record). The natural handler shape is one `failure->message` fn holding a single `case` — the [tutorial builds it](tutorial.md), and the [RealWorld example](../../examples/real-apps/realworld_http) uses it to render one vocabulary on every screen.

Two classification rules catch newcomers:

- **Status is classified before the body is touched — decode runs only on 2xx.** A JSON endpoint behind a load balancer that 404s with an *HTML* error page is `:rf.http/http-4xx` with the raw HTML at `:body`, not a decode failure: the decoder never ran. If you want the structured error body many APIs return alongside a 4xx, decode `:body` yourself in the failure branch — the framework hands you the bytes and the status, on purpose.
- **An empty (or whitespace-only) 2xx body is not a decode failure** — it's a parsed value of `nil`. The bare `204 No Content` a PUT or DELETE replies with succeeds: `:decode :json` hands your `:on-success` the canonical `{:status :ok :value nil …}`. A schema `:decode` then decides whether `nil` is acceptable — `[:maybe …]` passes, a required `:map` rejects as an ordinary schema failure. Identical on the browser and the JVM, on purpose.

## Validating the body with `:decode`

By default `:decode` is `:auto`, which sniffs the Content-Type and parses accordingly. The 2xx body is exactly where a [schema](../core/glossary.md#schema) earns its keep — coercing and validating the shape your handler then trusts. Hand `:decode` a Malli schema and a malformed body becomes a clean `:rf.http/decode-failure` rather than a `NullPointerException` three handlers later:

```clojure
(def ArticleResponse
  "Validates and coerces the JSON body of GET /api/articles/:slug."
  [:map
   [:article [:map
              [:slug  :string]
              [:title :string]
              [:body  :string]]]])

{:fx [[:rf.http/managed
       {:request    {:url (str "/api/articles/" slug)}
        :decode     ArticleResponse
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

`:decode` also accepts a keyword (`:json` / `:text` / `:blob` / `:array-buffer` / `:form-data`) or a plain function `(fn [text headers] decoded)` when you need full control.

## A valid 200 can still be a failure: `:accept`

Some APIs answer `200 OK` and then tell you, *in the body*, that the thing you asked for isn't there — `{"article": null}` instead of a 404. The transport succeeded, the JSON parsed, the schema passed; your domain disagrees. `:accept` is a post-decode normaliser that gets the last word.

`:accept` is a function `(decoded → {:ok value} | {:failure failure-map})`. Return `{:ok v}` and `v` becomes the success payload; return `{:failure m}` and `m` rides into the failure path as `:rf.http/accept-failure`, with your map at `:detail`:

```clojure
;; Adapted from examples/real-apps/realworld_http — a 200 with a null article is a domain miss.
{:fx [[:rf.http/managed
       {:request {:url (str "/api/articles/" slug)}
        :decode  ArticleResponse
        :accept  (fn [decoded]
                   (if-let [article (:article decoded)]
                     {:ok article}
                     {:failure {:reason  :missing-article
                                :message "Response had no :article"}}))
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

The default `:accept` is just `{:ok decoded}` — every 2xx that decodes is a success. Three rules keep an explicit one predictable:

- `:accept` runs **only after a successful 2xx decode**. A non-2xx response is sorted by status long before this point, so your `:accept` never has to think about HTTP status.
- An `:accept` that **throws**, or returns a **malformed shape** (nil, a non-map, a map with neither `:ok` nor `:failure`, or one with *both*) still dispatches a reply — it can never strand the caller. It classifies as `:rf.http/accept-failure` with a framework-supplied `:detail`, and the pre-`:accept` value rides at `:decoded` so you can see what it choked on.
- An accept-failure is **not retryable**. Retrying the transport won't change the body — this is a domain decision, not a transport blip. If you need "retry after refreshing X," that's a [state machine](../machines/concepts.md), not `:accept`.

## Retry: transport retry as data

Add a `:retry` policy — itself plain data — and the runtime handles the backoff:

```clojure
(def data-fetch-retry
  "Retry policy for read-only fetches: transport blips, 5xx, timeouts.
   Not 4xx — the request shape was valid; retrying won't help."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})
```

- **`:on`** is the set of failure categories that trigger a retry.
- **`:max-attempts`** is the total *including* the first try — `1` means no retry.
- **`:backoff`** is exponential — `:base-ms` times `:factor` per attempt, capped at `:max-ms` — with optional `:jitter`, which adds ±25% randomness so a thousand clients don't retry in lockstep against your recovering server.

Only the final exhausted failure dispatches your failure handler; intermediate attempts are trace rows (`:rf.http/retry-attempt`), not events your code sees. `:rf.http/aborted` is never retryable.

`:on` is a **closed set**, drawn only from the retryable categories: `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`. A category is in the set when re-issuing the *same* request could plausibly change the outcome. The first three are the obvious transient cases; 4xx and CORS are admitted because a real slice of them is transient too (`429 Too Many Requests`, `408`, a just-deploying CORS edge) — but most aren't, so they're opt-in and want a narrow `:max-attempts`.

Two guards keep a malformed `:on` from silently doing nothing:

- Put a non-retryable category — or anything outside the `:rf.http/*` namespace — in `:on` and the request is rejected at dispatch with a named bad-retry-on error, rather than riding a useless policy for its lifetime.
- `:on` must be an actual *set* (`#{…}`). A bare keyword or a vector is rejected the same way — and for a sharp reason: the membership test is `contains?`, and `contains?` over a vector tests *indices*, not values, so a vector `:on` would silently disable retry for every category.

The real discipline isn't *whether* to retry but *what*. Read-only fetches are safe. User-initiated writes are not, because retrying a submit or a payment risks doing it twice. The production shape is one shared policy for reads and conspicuously *no* `:retry` on writes — the RealWorld example's login, register, and settings requests carry none.

??? info "Coming from Axios?"

    `axios-retry`'s `retryCondition` ≈ `:on`, `retryDelay` ≈ `:backoff` — except here the policy is inspectable data at the call site and every attempt is a trace row, not closure state buried inside an interceptor.

??? note "Going deeper"

    `:retry` owns **transport retry** only — decisions that are a pure function of failure category and attempt count. The moment a retry decision depends on anything else ("after a 401, refresh the token, *then* retry"; "the body says retry-after 5s"), it becomes **semantic retry**, and that belongs in a [state machine](../machines/concepts.md) driving the request, with transport `:retry` still active inside each attempt the machine launches. One test: pure-function-of-category stays here; stateful-decision graduates to a machine.

## Cancellation: supersession and abort

Three related surfaces, one per situation.

**Supersession — reuse a `:request-id`.** Give a request a stable `:request-id` — any `=`-comparable value: a keyword, a string, or a structural vector like `[:articles :load slug]` — and issuing a *new* request with the same id automatically supersedes the old one. The old reply is suppressed before delivery: your handler never sees it, only a trace row records it (`:reason :request-id-superseded`). This is the cure for the search-box race — the [tutorial demonstrates it](tutorial.md) — and it's a correctness guarantee, not an optimization: a suppressed reply *cannot* clobber fresh data no matter how late it arrives.

**Manual abort — `[:rf.http/managed-abort the-id]`.** Where a supersession quietly retires the previous request, a manual abort is an explicit "stop now." It aborts whichever request currently holds the id and *does* deliver a reply — a `:status :cancelled` envelope carrying `{:kind :rf.http/aborted :reason :user}` under `:error` — so a deliberate user-cancel can clear the spinner. A supersession suppresses silently (the new request *is* the cleanup); a manual abort speaks up (someone clicked "cancel"). The `:reason` tells them apart.

**External cancel — `:abort-signal`.** If the cancel signal you want to honour already lives outside re-frame — a parent widget's lifecycle, a shared `AbortController` — hand its `.signal` to the request under `:abort-signal`. You can supply it *together with* a `:request-id` and the framework guarantees exactly one terminal outcome no matter which fires first. (`:abort-signal` is browser-only — the JVM has no `AbortController`, so `:request-id` is the cross-host cancel handle.)

!!! note "Requests that die with their machine"

    There's a third `:reason`, `:actor-destroyed`. A `:rf.http/managed` request issued from *inside* a spawned [state-machine](../machines/concepts.md) actor is aborted automatically when that actor is destroyed — its outstanding work dies with it, no manual abort needed. Requests dispatched from ordinary event handlers have no such lifecycle peg and are not auto-cancelled; that's deliberate, and `:request-id` remains your app-level cancel handle for them.

The [managed-http counter example](../../examples/core/managed_http_counter) demonstrates the manual-abort path end-to-end — plus the 404-is-not-a-decode-failure rule — in one small file.

## Timeouts

Every attempt has a per-attempt timeout, default `30000` ms. Set `:timeout-ms` to change it; `nil` or `0` opts out entirely. A fired timeout classifies as `:rf.http/timeout` with `:elapsed-ms` and `:limit-ms` on the failure map, and is retryable under a `:retry` policy that includes it.

## Verb helpers

The canonical `[:rf.http/managed {:request {:method :get :url …} …}]` vector is always correct — but typing `{:request {:method :get :url …}}` on every call gets repetitive. The `re-frame.http` namespace ships a pure synthesis fn per HTTP verb that builds the same vector from a URL plus an optional args map:

```clojure
(:require [re-frame.http :as rf.http])

;; These two are exactly equal:
[:rf.http/managed {:request {:method :get :url "/api/items"} :on-success [:items/loaded]}]
(rf.http/get "/api/items" {:on-success [:items/loaded]})
```

Every verb is there — `rf.http/get` / `post` / `put` / `delete` / `patch` / `head` / `options` — each in a one-arg (`url`) and two-arg (`url args`) form. The helper pins `:method` and `:url`; everything else in the args map (`:decode`, `:retry`, `:on-success`, and the `:request` sub-keys like `:body` and `:headers`) passes straight through:

```clojure
(rf/reg-event :comment/create
  (fn [_ [_ slug text]]
    {:fx [(rf.http/post (str "/api/articles/" slug "/comments")
                        {:request    {:body {:comment {:body text}}
                                      :request-content-type :json}
                         :decode     CommentResponse
                         :on-success [:comment/created]
                         :on-failure [:comment/create-error]})]}))
```

Two notes. `rf.http/get` shadows `clojure.core/get`, so the namespace is meant to be *aliased* (`:as rf.http`) and called qualified — `(rf.http/get …)` reads as "an HTTP GET", which is the whole point. And the helpers live in `re-frame.http`, **not** on the `rf/` facade: you require them explicitly. They ship in the same `day8/re-frame2-http` artefact as the fx they expand to, so loading the helpers and registering `:rf.http/managed` are one dep decision.

## Testing without a network

Tests need no network: the canned-stub fxs (`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`) and the `with-managed-request-stubs` route-stubbing macro — all registered by requiring the sibling `re-frame.http.test-support` namespace — synthesize replies with the exact envelope a live request produces. The [tutorial's test step](tutorial.md) shows the pattern; [Test a cascade](../core/testing/cascades.md) is the full recipe; [re-frame.http](../api/re-frame.http.md) documents every stub surface.

## When not to reach for it

Managed HTTP is the right tool for a single request that gets a single reply. Where it isn't, reach for:

- **[Resources](../resources/concepts.md)** — the same server data read on several screens, with caching and invalidation. Declare it once; it rides this transport underneath. Hand-rolling `:loaded-at` freshness checks across features means you've outgrown raw requests.
- **A [state machine](../machines/concepts.md)** — a long-lived connection (WebSocket, SSE) or any multi-step flow where retry decisions depend on state. There is no managed streaming surface yet; that's an honest gap, not a hidden feature.
- **[Your own fx](custom-effects.md)** — wire-level weirdness: custom transports, exotic binary protocols, non-HTTP async APIs. The escape hatch is always there.
