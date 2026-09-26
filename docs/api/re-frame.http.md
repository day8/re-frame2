# Managed HTTP reference

Describe an HTTP request as data in an event's `:fx`, and the runtime issues it, applies your timeout and retry policy, decodes and validates the body, classifies any failure into a closed set of kinds, and dispatches the reply to an event you name. Aborting and superseding a request by id are built in.

Use `:rf.http/managed` directly for a request whose reply one event handles, such as a login or a one-off load you keep in your own `app-db`. When the same server data is read from more than one place, or needs caching, deduplication, staleness or a refetch after a write, register a [resource](re-frame.resources.md) instead. Resources and mutations issue their requests through this same fx: their request fns return the args map described below, and the runtime supplies the reply addressing.

Managed HTTP ships in the optional artefact `day8/re-frame2-http`. Require `re-frame.http.managed` once at boot: loading it registers the `:rf.http/managed` fx. Without it, each use of the fx reports `:rf.error/no-such-fx` and no request is made. A facade call such as `rf/reg-http-interceptor` made without the artefact raises `:rf.error/http-artefact-missing`.

```clojure
(:require [re-frame.core :as rf]
          ;; Registers the `:rf.http/managed` fx at ns-load. Require it once,
          ;; at boot — nothing on the `rf/` facade does it for you.
          [re-frame.http.managed])
```

```clojure
(rf/reg-event :cart/load
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request    {:method :get :url "/api/cart"}
            :on-success [:cart/loaded]
            :on-failure [:cart/load-failed]}]]}))

(rf/reg-event :cart/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc-in db [:cart :items] value)}))

(rf/reg-sub :cart/items
  (fn [db _] (get-in db [:cart :items])))
```

Retries, timeouts, abort, decode and accept checks are further optional keys in the same args map.

There is no `re-frame.http` namespace and no per-verb helper. The fx are addressed by keyword, `reg-http-interceptor` is called on the facade as `rf/reg-http-interceptor`, and the test stubs are functions in `re-frame.http.test-support`. An app that issues many requests usually writes its own function that builds the args map, adding a base URL, default headers, a default `:decode` and body encoding; see [Your own request builder](../async/http.md#your-own-request-builder).

[Managed HTTP](../async/http.md) teaches the model.

## Effects

### `[:rf.http/managed args-map]`

- **Kind**: effect (reserved fx-id)
- **Payload**: the args map below. [The request is a map](../async/http.md#the-request-is-a-map) covers the `:request` envelope.
- **Description**: Issues one HTTP request and dispatches its reply to the target the args map names. In the browser it uses Fetch; on the JVM, `java.net.http.HttpClient`.
    - Destroying the issuing frame, or restoring an epoch in it, aborts the frame's in-flight requests; no reply is delivered.
    - A request issued from inside a spawned machine actor is aborted when that actor is destroyed. Its reply target receives `:status :cancelled` with `:rf.reply/cancel-reason :actor-destroyed`, unless the target is the actor itself, which receives nothing.
    - An abort that lands between retry attempts cancels the pending retry.
    - On the JVM, a relative `:url` fails as `:rf.http/transport`, cookies are neither sent nor stored, the response `:status-text` is `""`, and a connection not made within 10 seconds fails as `:rf.http/timeout`. `:abort-signal` and the CLJS-only `:request` keys are ignored, with a `:rf.http/cljs-only-key-ignored-on-jvm` trace.
- **Options**: `:request` is required, and every request names where its reply goes with `:reply-to` or `:on-success` / `:on-failure`.

    | Key | Value | Default | Meaning |
    |---|---|---|---|
    | `:request` | map | required | The request envelope, in the second table below. |
    | `:decode` | `:auto`, `:json`, `:text`, `:blob`, `:array-buffer`, `:form-data`, a Malli schema or a fn | `:auto` | How to read a 2xx body; the forms are listed below this table. JSON object keys decode to keywords. |
    | `:accept` | `(fn [decoded] …)` | none: every decoded 2xx body is a success | A domain check after decoding. Returning `{:ok value}` makes `value` the success `:value`; returning `{:failure user-map}` fails the request as `:rf.http/accept-failure`. |
    | `:retry` | `{:on #{kinds} :max-attempts N :backoff {:base-ms :factor :max-ms :jitter}}` | none: nothing is retried | The retry policy; its rules are below this table. |
    | `:timeout-ms` | milliseconds, `nil` or `0` | `30000` | The per-attempt timeout. An explicit `nil` or `0` turns it off. |
    | `:rf.http/max-decoded-keys` | integer | `10000` | The most unique JSON object keys the decoder will intern for this request. Exceeding it fails the request as `:rf.http/decode-failure` with `:reason :too-many-keys`. |
    | `:reply-to` | event vector or `nil` | none | Receives both the success and the failure reply; see [Reply addressing](#reply-addressing). |
    | `:on-success` / `:on-failure` | event vector or `nil` | none | Separate success and failure targets, used instead of `:reply-to`. |
    | `:request-id` | any `=`-comparable value | none | Names the request, for [`:rf.http/managed-abort`](#rfhttpmanaged-abort-request-id) or supersession; see below this table. |
    | `:abort-signal` | an external abort signal | none | Aborts the request when it fires. CLJS only. |
    | `:sensitive?` | boolean | `false` | Redacts this request in traces; see [Privacy and classification](#privacy-and-classification). |

    - `:decode` takes four forms:
        - `:auto`, the default when absent: a `json` or `+json` `Content-Type` decodes as JSON, `text/*` as a string, and anything else, including no `Content-Type`, as a binary body (a `Blob` in the browser, a `byte[]` on the JVM).
        - `:json`, `:text`, `:blob`, `:array-buffer` or `:form-data` forces one.
        - A Malli schema parses JSON and validates it. It decodes only a response whose `Content-Type` is JSON or absent; any other type fails as `:rf.http/decode-failure`. It needs Malli at run time, and `day8/re-frame2-http` does not bring it: add `day8/re-frame2-schemas` and require `re-frame.schemas` at boot. Without Malli the schema is skipped: the parsed JSON goes on to `:accept` and the success reply with no coercion or validation, and a dev build emits `:rf.warning/http-malli-absent` once per process. See [Response-body classification](#response-body-classification) for the schema's `:sensitive?` and `:large?` props.
        - A fn `(fn [body-text headers] → value)` does it all. It receives the raw body text (`""` for an empty body), and a throw fails as `:rf.http/decode-failure`.
    - `:retry`: `:on` must be a set drawn from the retryable kinds `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`; `#{}` or `nil` retries nothing. `:max-attempts` counts the first attempt, so `3` means up to two retries; without it nothing is retried. `:backoff` defaults to `{:base-ms 250 :factor 2 :max-ms 5000}`, and `:jitter true` adds ±25%. Only the final failure reaches your reply target.
    - `:request-id`: issuing a new request with the same id while one is in flight supersedes it, and the old reply is never delivered. Ids are per frame, so two frames running the same code do not cancel each other.

    The `:request` envelope:

    | Key | Value | Default | Meaning |
    |---|---|---|---|
    | `:url` | a non-blank string | required | The request URL. On the JVM it must be absolute. |
    | `:method` | `:get`, `:head`, `:post`, `:put`, `:patch`, `:delete` or `:options` | `:get` | The HTTP method. |
    | `:headers` | header name to a string, or to a vector of strings for a repeated header | none | Names are case-insensitive. A header the platform rejects is left out, with a `:rf.warning/http-header-invalid` trace. |
    | `:params` | a query-param map | none | Encoded onto the URL before any `#fragment`; its rules are below this table. |
    | `:body` | a Clojure collection, a string, a `FormData`, `Blob` or `ArrayBuffer`, or a no-argument fn returning one | none | The request body; its rules are below this table. |
    | `:request-content-type` | `:json`, `:form`, `:text` or a MIME string | none: chosen from `:body` | Serialises `:body` and sets `Content-Type`, unless `:headers` already sets one; the rule without it is below this table. |
    | `:credentials` | `:omit`, `:same-origin` or `:include` | `:same-origin` | Passed to Fetch. CLJS only. |
    | `:mode`, `:cache`, `:referrer`, `:integrity` | Fetch's values | Fetch's defaults | Passed to Fetch. CLJS only. |
    | `:redirect` | `:follow`, `:error` or `:manual` | `:follow` | With `:error` or `:manual`, a redirect response fails the request instead of being followed. |
    | `:sensitive?` | boolean | `false` | The same as the top-level `:sensitive?`. |

    - `:params`: a vector value repeats the key (`{:tag ["a" "b"]}` → `tag=a&tag=b`), an empty vector adds nothing, and a keyword key is written by its name.
    - `:body`: the fn is called before each attempt, so a retry gets a fresh body. A fn that throws, or a body the encoder rejects, fails the request as `:rf.http/transport` with `:stage :request-prep`.
    - `:request-content-type`: without it, a Clojure map, sequence or set is sent as JSON, and anything else is sent as it is with no `Content-Type`. JSON writes a keyword with its namespace and no colon (`:user/id` → `"user/id"`) and a UUID as its string.
- **Errors**, all raised at dispatch, before the request is sent. The effect throws: the request is not sent, no reply is dispatched, the event's other effects still run, and error listeners receive `:rf.error/fx-handler-exception`, whose `:exception` carries the id below:
    - `:rf.error/http-bad-request` — the final `:url`, after the `:before` interceptor chain, is missing, `nil` or blank.
    - `:rf.error/http-bad-retry-on` — `:retry :on` is not a set drawn from the retryable kinds.
    - `:rf.error/http-bad-reply-target` — a reply-target value that is neither an event vector nor `nil`, or `:reply-to` beside `:on-success` / `:on-failure` (`:reason :mixed-addressing`).
    - `:rf.error/http-no-reply-target` — none of `:reply-to`, `:on-success` or `:on-failure` is present.
    - `:rf.error/schemas-artefact-missing` — the `:decode` schema carries a `:sensitive?` or `:large?` prop, but `re-frame.schemas` is not loaded, so the response could not be classified. Add `day8/re-frame2-schemas` and require `re-frame.schemas` at boot.
    - `:rf.error/bad-classification` — the `:carriers` block on the `:rf.http/managed` registration is malformed; see [Privacy and classification](#privacy-and-classification). `reg-fx` accepts the block, and every request refuses it until it is fixed. `:bad-key` names the offending slot. In a development build this id also escapes the event: `dispatch-sync` throws it, and the event's later effects do not run.
- **Example**:
  ```clojure
  (rf/reg-event :cart/load
    (fn [_ _]
      {:fx [[:rf.http/managed
             {:request    {:method :get :url "/api/cart"}
              :request-id :cart/load
              :timeout-ms 5000
              :retry      {:on           #{:rf.http/transport :rf.http/http-5xx}
                           :max-attempts 3}
              :on-success [:cart/loaded]
              :on-failure [:cart/load-failed]}]]}))
  ```

### `[:rf.http/managed-abort request-id]`

- **Kind**: effect (reserved fx-id)
- **Payload**: the `:request-id` of an in-flight request.
- **Description**: Aborts the in-flight request with that `:request-id`, issued from the same frame; an id with nothing in flight is a no-op. Its reply target (`:reply-to` or `:on-failure`) receives the cancelled reply `{:status :cancelled :cancelled? true :rf.reply/cancel-reason :user :error {:kind :rf.http/aborted ...}}`, as described under [Reply shape](#reply-shape). See [Cancellation: supersession and abort](../async/http.md#cancellation-supersession-and-abort), which compares abort with supersession.
- **Example**:
  ```clojure
  (rf/reg-event :request/abort
    (fn [_ [_ request-id]]
      {:fx [[:rf.http/managed-abort request-id]]}))
  ```

### `{:spawn {:machine-id :rf.http/managed :data args-map}}`

- **Kind**: machine (registered when `re-frame.machines` is also loaded)
- **Payload**: `:data` is an `:rf.http/managed` args map without reply addressing.
- **Description**: Runs one request as a child machine, so the request lives exactly as long as the parent state that spawns it.
    - The child finishes in a final `:succeeded` or `:failed` state holding the reply's `:value`, or its `:error` failure map, under `:rf/result`.
    - Under `:spawn`, the parent receives `[:succeeded value]` or `[:failed failure]`. Under `:spawn-all`, the result folds into the join and no event is sent.
    - Leaving the parent state aborts the request, and neither event is sent.
    - The child sets its own `:on-success` / `:on-failure`, so `:data` must not carry `:reply-to`, `:on-success` or `:on-failure`. Any of them makes the child throw `:rf.error/http-bad-reply-target` (`:reason :machine-owns-reply`, `:keys` naming them) when it starts, before a request is sent. The runtime reports the throw as `:rf.error/machine-action-exception`, with the error in its `:exception-data`, and routes it to the parent's `:spawn :on-error`.
    - See [From a state machine](../async/http.md#from-a-state-machine), which also shows `:spawn-all` fan-out.
- **Example**:
  ```clojure
  :loading
  {:spawn {:machine-id :rf.http/managed
           :data       {:request {:url "/api/me"} :decode :json}}
   :on    {:succeeded :ready
           :failed    :load-failed}}
  ```

## Replies

### Reply shape

Every reply is one map keyed on `:status`, which is `:ok`, `:error` or `:cancelled`:

```clojure
;; Success — :meta carries the response's :status, :status-text and :headers
;; (header names lower-cased)
{:status :ok :value decoded-body :meta {:status 200 :status-text "OK" :headers {…}} …}

;; Failure — per-kind tags (:status / :status-text / :message / ...) sit
;; flat on the failure map beside :kind
{:status :error
 :error  {:kind :rf.http/<kind> ...}}

;; Abort / cancel
{:status :cancelled :cancelled? true :rf.reply/cancel-reason :user
 :error  {:kind :rf.http/aborted …}}
```

A timeout is a failure: `:status :error` with `:kind :rf.http/timeout`. The reply also carries `:attempt` and, when the request had a `:request-id`, `:correlation {:request-id …}`.

A reply from a real request (not a [test stub](#testing-without-a-network)) adds:

- `:rf.reply/work-status` — `:completed` (`:ok`), `:failed` (`:error`), `:timed-out` (a `:rf.http/timeout` failure) or `:cancelled`.
- `:rf.reply/work-id` — `[:rf.work/http logical-id issuance attempt]`, the join to the trace stream, and `:rf.reply/work-kind :http`.
- `:rf.frame/id` — the issuing frame.
- `:completed-at` — the completion time in epoch milliseconds. The reply event's `:rf/time-ms` coeffect carries the same value.
- On `:cancelled`, `:rf.reply/cancel-reason` is `:user` (`:rf.http/managed-abort` or `:abort-signal`) or `:actor-destroyed`. Supersession, frame destroy and epoch restore deliver no reply.

### Reply addressing

Every request must name where its reply goes. The runtime appends the reply map as the last argument of the event vector you name:

- `:reply-to` sends both the success and the failure reply to one event; resources and mutations use the same key. A `:cart/load` handler addressed with `:reply-to [:cart/load]` receives `[:cart/load {:status :ok :value decoded-body …}]` (or the `:error` / `:cancelled` shape) and branches on `(:status reply)`. Put request context in the vector: `:reply-to [:cart/load ctx]` delivers `[:cart/load ctx <reply>]`.
- `:on-success` / `:on-failure` route the same reply map to two events. A `:cart/loaded` handler receives `[:cart/loaded {:status :ok :value decoded-body …}]` and destructures it directly: `[_ {:keys [value]}]` for success, `[_ {:keys [error]}]` for failure. Neither may appear beside `:reply-to`; the check is on key presence, so a `nil` value counts.
- `:reply-to nil` means fire-and-forget: no reply is delivered. `:on-success nil` or `:on-failure nil` drops one side of the split form.
- Supplying only `:on-success`, or only `:on-failure`, leaves the other branch unaddressed: its reply is dropped, as with `nil`. A dropped failure other than an abort emits `:rf.warning/failure-swallowed` once per process in a dev build.
- Omitting all three keys raises `:rf.error/http-no-reply-target`: the framework never picks a reply target for you, because an implicit target can swallow failures.

[Handling the reply](../async/http.md#handling-the-reply) shows both styles in full.

## Failure kinds (closed set)

A failure's `:kind` is one of eight values, all reserved under `:rf.http/*`. The set is closed, so a handler's `case` on `:kind` can be exhaustive.

| `:kind` | Meaning | Tags |
|---|---|---|
| `:rf.http/transport` | Network, DNS or connection error before any HTTP response (in the browser, against a same-origin URL; see `:rf.http/cors`), or a `:body` that could not be prepared. | `:message`, `:cause`; `:stage :request-prep` for a body failure |
| `:rf.http/cors` | A Fetch network rejection (a `TypeError`) against a cross-origin URL (CLJS only). The browser reports a CORS rejection and a network failure the same way, so a dropped connection to a cross-origin host also reads as `:rf.http/cors`. | `:message`, `:url` |
| `:rf.http/timeout` | The per-attempt timeout fired. | `:elapsed-ms`, `:limit-ms`; on the JVM also `:message` |
| `:rf.http/http-4xx` | A 4xx response, or any other non-2xx status below 500 (a 1xx, or a 3xx that was not followed). | `:status`, `:status-text`, `:body` (raw text), `:headers` |
| `:rf.http/http-5xx` | A 5xx response. | `:status`, `:status-text`, `:body` (raw text), `:headers` |
| `:rf.http/decode-failure` | A 2xx response whose body the decoder rejected. | `:body-text`, `:cause` (the message), `:schema-validation-failure?`; `:reason :too-many-keys` and `:limit` when the key cap was exceeded |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}`, threw, or returned anything but a map with exactly one of `:ok` / `:failure`. | `:decoded` (the body before `:accept`); `:detail` is `user-map`, `{:rf.http/bad-accept :threw :message …}` or `{:rf.http/bad-accept :malformed-return :returned …}` |
| `:rf.http/aborted` | Aborted via `:request-id`, `:abort-signal`, or the destruction of the actor or frame that issued it. | `:reason`, `:actor-id`; `:message` on some paths |

- The status is classified before the body is decoded, so a 4xx or 5xx response is never decoded: its raw body is on the failure map's `:body`. An empty 2xx JSON body decodes to `nil` rather than failing.
- The first five kinds are the ones `:retry :on` accepts. `:rf.http/decode-failure`, `:rf.http/accept-failure` and `:rf.http/aborted` are never retried.
- Every failure map also names the request it came from: `:request {:method :url}` (the method sent, `:get` when the request set none), `:request-id`, `:attempt`, `:max-attempts` (when a retry policy was set) and `:work/id`.
- Resources and mutations store this same map as their `:error` (and a resource's `:refresh-error`), so one `case` on `:kind` serves both.

[Failures are a closed set](../async/http.md#failures-are-a-closed-set) explains the kinds in the guide.

## Request interceptors

Register an interceptor to change every request a frame issues: add an auth header, stamp a request id, log timings. Call it as `rf/reg-http-interceptor`, and remove one with `(rf/clear :http-interceptor id)`. The facade has no `clear-http-interceptor`; `re-frame.http.managed/clear-http-interceptor` below is the function behind it. [Interceptors: stamp every request once](../async/http-going-further.md#interceptors-stamp-every-request-once) walks through the common cases.

### `reg-http-interceptor`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-http-interceptor id interceptor-map) → id
  ```
- **Description**: Registers an interceptor on a frame's `:rf.http/managed` chain: its `:before` fn can rewrite each outgoing request, and its `:after` fn each reply.
    - `interceptor-map` carries at least one of `:before (fn [ctx] ctx')` and `:after (fn [ctx response] response')`, plus an optional `:frame` and the standard `:rf/registration-metadata` keys.
    - A `:before` fn receives a ctx `{:request … :args … :frame … :event … :sensitive? …}` and returns it, possibly changed. `:sensitive?` is `true` when the args marked the request sensitive, at the top level or under `:request`; the `:request` left at the end of the chain is what is sent. An `:after` fn receives that final ctx and the reply map, so it can match a response to its request, and returns the reply.
    - `:before` fns run in registration order, before the request goes to the platform HTTP client. `:after` fns run in reverse registration order, after the reply is built and before it is dispatched.
    - `:before` fns run once per request, not per retry attempt: every attempt sends the request the chain produced. `:after` fns run once, on the final reply, including a `:cancelled` one.
    - A request uses the chain as it stood when the request was issued, for both halves and every retry. Registering, replacing or clearing an interceptor affects only requests issued afterwards.
    - A `:before` can mark a request sensitive by setting `[:request :sensitive?] true`.
    - The target frame is the explicit `:frame` if given, else the frame in scope (`with-frame`, an `:initial-events` step); it never falls back to `:rf/default`.
    - Re-registering an existing id replaces it in place, keeping its position in the chain. After a `clear-http-interceptor`, registering the same id appends it to the end.
- **Errors**:
    - At registration:
        - `:rf.error/http-bad-interceptor`: an invalid shape, such as a non-keyword id, neither `:before` nor `:after`, a non-fn slot or a non-keyword `:frame`.
        - `:rf.error/no-frame-context`: no `:frame` and no frame in scope.
    - When a request runs. A failing `:before` means the request is not sent, and a failing `:after` means the reply is not delivered:
        - `:rf.error/http-interceptor-failed`: a `:before` or `:after` threw. Carries `:frame`, `:interceptor-id`, `:url` and `:cause`, plus `:phase :after` when an `:after` threw.
        - `:rf.error/http-interceptor-bad-return`: a `:before` or `:after` returned something other than a map. Carries `:id` and `:returned`.
- **Example**:
  ```clojure
  (rf/reg-http-interceptor :auth/inject
    {:frame  :app/main
     :before (fn [ctx]
               ;; read the token fresh on each request, so a rotated token is picked up
               (let [token (-> (rf/app-db-value (:frame ctx)) :auth :token)]
                 (cond-> ctx
                   token (assoc-in [:request :headers "Authorization"]
                                   (str "Bearer " token)))))})

  ;; Or with both sides — :before stamps a start mark, :after reads it.
  (rf/reg-http-interceptor :telemetry
    {:frame  :app/main
     :before (fn [ctx] (assoc ctx ::started (js/Date.now)))
     :after  (fn [ctx resp]
               (assoc resp :elapsed-ms (- (js/Date.now) (::started ctx))))})
  ```

### `re-frame.http.managed/clear-http-interceptor`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-http-interceptor id)                 → id
  (clear-http-interceptor id {:frame target}) → id
  ```
- **Description**: Removes an interceptor by id. Application code calls it through the facade, as `(rf/clear :http-interceptor id)` or `(rf/clear :http-interceptor id {:frame target})`.
    - `(clear-http-interceptor id)` uses the frame in scope; it never clears from `:rf/default`.
    - `(clear-http-interceptor id {:frame target})` names the frame, like `reg-http-interceptor`'s `:frame`. `target` is a frame-id keyword or a live frame value, and the opts map must be exactly `{:frame target}`.
    - These two forms are the whole public surface; there is no frame-first `(clear-http-interceptor frame id)` form.
- **Errors**:
    - `:rf.error/no-frame-context`: `(clear-http-interceptor id)` with no frame in scope.
    - `:rf.error/http-bad-interceptor`: the opts map is not exactly `{:frame target}` with a non-nil target: `{}`, `{:frame nil}`, a misspelled or extra key (`{:fram f}`), or a non-map second argument. Raised before any frame is resolved.
    - `:rf.error/registrar-clear-bad-request`: through the facade, `(rf/clear :http-interceptor id opts)` checks the same `{:frame target}` shape itself, so a malformed opts map there raises this instead.
- **Example**:
  ```clojure
  (rf/clear :http-interceptor :auth/inject {:frame :app/main})
  ```

## Privacy and classification

Four declarations control what managed HTTP redacts from traces, off-box records and SSR payloads; there is no function for adding names to the denylists at runtime. [Keeping secrets out of the trace](../async/http-going-further.md#keeping-secrets-out-of-the-trace) and [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) explain the model end to end.

| Declaration | What it covers | Where declared |
|---|---|---|
| Built-in header denylist | A fixed set of always-sensitive header names (`Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-CSRF-Token`, …), redacted in the `:headers` slot of every `:rf.http/*` trace regardless of `:sensitive?`. Case-insensitive; no frame can remove a name. | framework default |
| Built-in query-param denylist | A fixed set of always-sensitive query-param names (`api_key`, `access_token`, `token`, `secret`, `password`, `session`, `signature`, …). The value is redacted inline in `:url` slots (`?api_key=:rf/redacted&page=2`), keeping the name and position. A hit also stamps `:sensitive? true` on the trace event, since the name alone is the signal. | framework default |
| Managed-HTTP carriers | App-specific sensitive names, declared on the `:rf.http/managed` `reg-fx` registration. `:headers` is a vector of names added to the built-ins, never a policy map, since the header built-ins cannot be removed. `:query-params` is a vector of names to add, or an `{:include […] :except […]}` policy map with effective policy `(defaults − except) ∪ include`; `:except` drops a built-in name from this app's own dev trace. Names are case-insensitive. The block is checked when a request reads it, not at registration, so a malformed block fails each request with `:rf.error/bad-classification` (see the [`:rf.http/managed` errors](#rfhttpmanaged-args-map)). | `reg-fx :rf.http/managed` `:carriers {:headers […] :query-params […]}` |
| Per-request `:sensitive?` | Redacts one request's body, params and all URL param values. | the `:rf.http/managed` args map (`:sensitive?` at top level, or under `:request`) |

```clojure
;; managed-HTTP carrier extensions (union onto the immutable built-ins)
(rf/reg-fx :rf.http/managed
  {:carriers {:headers      ["X-Honeycomb-Team"]
              :query-params ["shop_token"]}}
  re-frame.http.managed/managed-handler)

;; per-call opt-in for a single sensitive request
(rf/reg-event :api/login
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request    {:method :post :url "/auth/login" :body creds}
            :sensitive? true
            :on-success [:api/logged-in]
            :on-failure [:api/login-failed]}]]}))
```

### Response-body classification

The denylists and the `:sensitive?` flag cover what the request carries. A response body is classified by `:sensitive?` / `:large?` props on the slots of the request's `:decode` schema, which already describes the body's shape. These props apply whether or not the request sets `:sensitive?`.

```clojure
(rf/reg-event :auth/login
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request {:method :post :url "/auth/login" :body creds}
            ;; [:token] redacts; [:user-id] rides verbatim
            :decode  [:map
                      [:token {:sensitive? true} :string]
                      [:user-id :int]]
            :on-success [:auth/logged-in]
            :on-failure [:auth/login-failed]}]]}))
```

- A `:sensitive?` slot is redacted to `:rf/redacted` and a `:large?` slot is replaced with the size marker; a slot carrying both is treated as sensitive. Unmarked siblings are traced verbatim. A prop on the root classifies the whole body, for example `[:string {:sensitive? true}]` for a response that is an opaque token.
- Only a Malli schema in the raw EDN vector form `[op props? …]` has per-slot marks the walker can read. Any other `:decode` (a keyword mode such as `:json` or `:text`, a custom decoder fn, a registry-keyword ref, a compiled `m/schema` object) leaves the body's shape unknown, so the body is omitted from off-box records entirely rather than shipped raw.
- Error bodies are always omitted from off-box records, whatever `:sensitive?` says: a 4xx/5xx `:body` and a decode-failure `:body-text` are raw text that no schema has classified, and error bodies often echo request context or tokens.

!!! warning "Classification does not propagate — declare each surface a secret crosses"

    A token in the response body, classified by the `:decode` schema, and the same token stored in `app-db` need two declarations, because nothing carries one to the other. When the reply handler writes the token into `app-db`, classify that path too: return `:sensitive [[:auth :token]]` alongside `:db` from the `reg-event` handler (see [Classify a durable secret in app-db](../core/how-to/keep-secrets-out-of-traces.md#classify-a-durable-secret-in-app-db) and the effect map's `:sensitive` key in [re-frame.core](re-frame.core.md#the-effect-map)). Never copy a secret into an `app-db` path you have not classified, such as a JWT duplicated at `[:auth :user :token]`: the copy ships raw until that path is classified or the duplicate is removed.

### `re-frame.http.managed/managed-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (managed-handler frame-ctx args-map)
  ```
- **Description**: The `:rf.http/managed` fx handler. It is public so that an app can re-register `:rf.http/managed` with a `:carriers` block, as in the carriers example above. Pass it to `reg-fx`; do not call it from app code.

## Testing without a network

`re-frame.http.test-support` drives managed HTTP in tests without a network: two fx that synthesise a reply inline, and helpers that answer requests from a route map. It ships in the same artefact, `day8/re-frame2-http`. Require it from test code only; loading it also registers the canned-reply fx. None of it is on the `re-frame.core` facade. [Testing without a network](../async/http.md#testing-without-a-network) and [Answer the HTTP](../core/testing/pipeline-runs.md#answer-the-http-canned-replies-by-method-and-url) show the stubs in a test.

A stubbed reply carries only `:status`, `:value` or `:error`, and `:meta` when you supply one. It has no `:attempt`, `:correlation`, `:completed-at` or `:rf.reply/*` keys, and a stubbed `:cancelled` reply has no `:cancelled?` or `:rf.reply/cancel-reason`.

An `:fx-overrides` redirect to a stub effect the frame cannot find reports `:rf.error/override-fallthrough` and sends the real request, so require `re-frame.http.test-support` before creating the frame.

```clojure
(:require [re-frame.http.test-support :as http-test-support])  ;; test-only; also registers the canned fxs
```

### `[:rf.http/managed-canned-success {:value v}]`

- **Kind**: effect (reserved fx-id)
- **Payload**: an `:rf.http/managed` args map, plus `:value`, `:meta` and `:after-ms`.
- **Description**: Delivers a success reply (`{:status :ok :value v}`) without making a request, for stubbing one request inline in `:fx`.
    - `:value` defaults to `{:stubbed true}`.
    - `:meta` (optional) is copied to the reply's `:meta` slot, so `:after` interceptors that read headers can be tested without a network. Without it the reply has no `:meta`.
    - `:after-ms` (optional) — a positive value delays the reply by a `:dispatch-later` tick. Absent, `0` or negative delivers immediately.
    - The frame's `:before` and `:after` interceptor chains run around the reply, as on the real path. Reply addressing (`:reply-to` / `:on-success`) works as for `:rf.http/managed`; a stub reply with no target is dropped silently.
- **Example**:
  ```clojure
  ;; cf. examples/core/managed_http_counter/core.cljs
  (rf/reg-event :counter/load
    (fn [_ _]
      {:fx [[:rf.http/managed-canned-success
             {:request  {:method :get :url "api/flaky"}
              :decode   :json
              :value    {:delta 5}
              :reply-to [:counter/loaded]}]]}))   ;; receives {:status :ok :value {:delta 5}}
  ```

### `[:rf.http/managed-canned-failure {:kind <:rf.http/*> :tags {...}}]`

- **Kind**: effect (reserved fx-id)
- **Payload**: an `:rf.http/managed` args map, plus `:kind`, `:tags` and `:after-ms`.
- **Description**: Delivers a failure reply without making a request.
    - `:kind` defaults to `:rf.http/transport`. `:tags` are merged into the `:error` map.
    - An `:rf.http/aborted` kind produces `:status :cancelled`; every other kind produces `:status :error`.
    - `:after-ms`, the interceptor chains and reply addressing (`:reply-to` / `:on-failure`) work as for `:rf.http/managed-canned-success`.
- **Example**:
  ```clojure
  (rf/reg-event :flaky/load
    (fn [_ _]
      {:fx [[:rf.http/managed-canned-failure
             {:on-failure [:flaky/load-error]
              :kind       :rf.http/http-5xx
              :tags       {:status 503 :status-text "Service Unavailable"}}]]}))
  ```

### `re-frame.http.test-support/with-request-stubs`

- **Kind**: function
- **Signature**:
  ```clojure
  (with-request-stubs route-map body-fn) → the value body-fn returns
  ```
- **Description**: Calls `body-fn`, answering matching `:rf.http/managed` requests made while it runs from `route-map` instead of the network.
    - `route-map` is `{[<method> <url>] {:reply {:ok <value>}}}` for a success, or `{[<method> <url>] {:reply {:failure <failure-map>}}}` for a failure. A success may add `:meta` beside `:ok`, which becomes the reply's `:meta`. A failure map's `:kind` defaults to `:rf.http/transport`; its other keys become the failure's tags.
    - Plain `dispatch-sync` calls inside `body-fn` are routed by method and URL, with no `:fx-overrides` needed. A per-call `:fx-overrides` still wins.
    - Routes match the `:method` and `:url` as the `:before` interceptors leave them, before `:params` is merged onto the URL, so key a request carrying `:params {:page 2}` by its bare `:url`. A request with no matching route receives a synthesised `:rf.http/transport` failure carrying `:message "no stub matched"`, `:method` and `:url`.
    - Scopes nest: an inner scope's route map shadows the outer one, which is restored when the inner scope exits. The stub is registered once, when `re-frame.http.test-support` loads, and nothing is registered per scope, so a frame created before the scope was entered also routes through it.
    - Drive the test with `dispatch-sync`. A request queued with `rf/dispatch` runs after `body-fn` returns: in CLJS it then fails in its effect (reported as `:rf.error/fx-handler-exception`); on the JVM the queued run carries the scope's bindings and is still answered.
    - The override target is the internal `:rf.test/managed-http-scope-stub` fx. When you need an `:fx-overrides` target yourself, use `:rf.http/managed-test-stub` from `install-managed-request-stubs!`.
- **Example**:
  ```clojure
  (deftest cart-loads
    (rf/with-new-frame [_ (rf/make-frame {})]
      (http-test-support/with-request-stubs
        {[:get "/api/cart"] {:reply {:ok [{:id 1 :name "widget"}]}}}
        (fn []
          ;; No manual :fx-overrides — auto-routes by method + URL.
          (rf/dispatch-sync [:cart/load])
          (is (= 1 (count (rf/subscribe-once [:cart/items]))))))))
  ```

### `re-frame.http.test-support/install-managed-request-stubs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-managed-request-stubs! route-map) → :rf.http/managed-test-stub
  ```
- **Description**: Installs `route-map` as stubs that stay in place until `uninstall-managed-request-stubs!`, for stubs that span several `deftest`s. It registers the `:rf.http/managed-test-stub` fx, the stable `:fx-overrides` target, and returns that fx-id.
    - Unlike `with-request-stubs`, it does not reroute `:rf.http/managed`. Dispatch with `{:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}`, or wrap dispatches in `with-fx-overrides`, to route through it.
    - A nested install saves the previous stub handler, and the matching uninstall restores it (LIFO).
    - A frame created without an id before the install cannot see `:rf.http/managed-test-stub`: its redirect falls through with `:rf.error/override-fallthrough` and the real request is sent. Install first, or use `with-request-stubs`.
- **Example**:
  ```clojure
  ;; Stubs that span several deftests — install once, route via :fx-overrides.
  (http-test-support/install-managed-request-stubs!
    {[:get "/api/cart"] {:reply {:ok [{:id 1 :name "widget"}]}}})

  (rf/with-new-frame [_ (rf/make-frame {})]   ;; created after the install, so it sees the stub fx
    (rf/dispatch-sync [:cart/load]
                      {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}))
  ```

### `re-frame.http.test-support/uninstall-managed-request-stubs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (uninstall-managed-request-stubs!) → nil
  ```
- **Description**: Removes the most recent install. If installs were nested it restores the previously installed stub handler (LIFO); otherwise it clears the stub fx, so requests go to the network again. Idempotent: an uninstall with no matching install does nothing.
- **Example**:
  ```clojure
  (http-test-support/uninstall-managed-request-stubs!)
  ```

## Trace events

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http/issued` | `:info` | Once per issued request, inside the issuing fx handler, before any supersession or attempt. Carries `:rf.reply/work-id` (the attempt-1 work id), `:rf.reply/work-kind :http`, `:request-id`, `:url`, `:method`, `:frame`, and `:reply-to` (each branch's target event-id). |
| `:rf.http/retry-attempt` | `:info` | Per intermediate attempt that matched `:retry :on`. Carries `:request-id`, `:url`, `:attempt`, `:max-attempts`, `:failure`, `:next-backoff-ms` and `:recovery`: a backoff and `:retried` on an intermediate row, `nil` and `:no-recovery` on the final row. |
| `:rf.http/replied` | `:info` | The completion row (success or failure), built from the reply map before it is dispatched. Identity facts (`:status`, `:rf.reply/work-id`, `:attempt`, `:completed-at`) are verbatim; the wire-bearing slots (`:value` / `:error` / `:meta`) go through the trace elider and the header denylist. |
| `:rf.http/<kind>` | `:error` | A failure other than an abort, keyed by its `:kind` (`:rf.http/timeout`, `:rf.http/http-5xx`, …). Carries the redacted failure map. |
| `:rf.http/aborted` | `:info` | An abort, whatever its `:reason` (`:user`, `:request-id-superseded`, `:actor-destroyed`, `:frame-destroyed`, `:epoch-restored`, …). Carries `:kind`, `:request-id`, `:reason`, `:actor-id`, `:url`. |
| `:rf.http/stale-suppressed` | `:info` | An app reply was suppressed (supersession, obsolete actor target, epoch restore, frame destroy). Carries `:rf.reply/status :stale`, `:rf.reply/work-status :suppressed`, `:rf.reply/work-id`, `:rf.reply/carried` / `:rf.reply/current`, `:rf.reply/stale-reason` and `:recovery`. `:recovery` names the trigger (`:superseded-by-fresh-request`, `:actor-destroyed-target-obsolete`, `:suppressed-on-epoch-restore`, `:suppressed-on-frame-destroy`); `:rf.reply/stale-reason` is `:rf.http/actor-destroyed-target-obsolete` for an obsolete actor target and `:rf.http/request-id-superseded` for the other three. |
| `:rf.http/aborted-on-actor-destroy` | `:info` | One per request cancelled because the actor that spawned it was destroyed. Carries `:request-id`, `:actor-id`, `:url`. |
| `:rf.warning/failure-swallowed` | `:warning` | Once: a failure other than an abort had no reply target (`:on-failure nil`, or the branch left unaddressed). Carries `:url`, `:failure`. |
| `:rf.warning/http-malli-absent` | `:warning` | Once per process: a schema `:decode` ran without Malli, so the parsed body went on unvalidated. Carries `:reason`, `:schema`. |
| `:rf.warning/http-header-invalid` | `:warning` | Per request header the platform's header builder rejects, such as an empty name or a value carrying `\r` or `\n`. That header is left out and the request goes on with the rest. Carries `:url`, `:header`, `:cause`; never the value. |
| `:rf.http/cljs-only-key-ignored-on-jvm` | `:warning` | JVM only, per occurrence: the request carried a key the JVM transport ignores (`:mode`, `:cache`, `:referrer`, `:integrity` or `:credentials` in `:request`, or `:abort-signal`). The request proceeds without it. Carries `:key`, `:url`. |
| `:rf.http/binary-decode-degraded-on-jvm` | `:warning` | JVM only, per request with an explicit `:decode` of `:blob`, `:array-buffer` or `:form-data`: the body arrives as a `byte[]` rather than the browser object. Carries `:decode`, `:url`. |
| `:rf.error/http-reply-tail-failed` | `:error` | In every build: delivering the reply threw after the transport completed (an `:after`, the reply target, or the completion cascade). There is no retry and no re-send; the reply is not delivered. |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded. Carries `:frame`, `:id`. |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing interceptor. Carries `:frame`, `:id`. |
| `:rf.error/http-interceptor-failed` | `:error` | An interceptor's `:before` or `:after` threw. Carries `:frame`, `:interceptor-id`, `:url`, `:cause`, plus `:phase :after` when an `:after` threw. The request is not sent (`:before`) or the reply is not delivered (`:after`). |
| `:rf.error/http-interceptor-bad-return` | `:error` | An interceptor's `:before` or `:after` returned something other than a map. Carries `:id`, `:returned`. The request is not sent (`:before`) or the reply is not delivered (`:after`). |

## Framework integration

Not for application code. The framework's own tests reach these across a namespace boundary; they are public for that reason only, at the implementation tier, and may change without notice.

- `re-frame.http.managed/clear-all-in-flight!` — aborts every in-flight request, then empties the registry.
- `re-frame.http.managed/in-flight-snapshot` — the in-flight requests keyed by request id, for every frame or for one frame.
- `re-frame.http.managed/actor-in-flight-snapshot` — the in-flight requests an actor issued, keyed by actor id.
- `re-frame.http.managed/seed-in-flight-for-test!` — records a fabricated in-flight request through the same path a real one takes, so both indexes stay consistent.
- `re-frame.http.managed/abort-on-actor-destroy` — aborts the requests an actor issued; the actor-destroy cascade calls it.
- `re-frame.http.managed/clear-all-http-interceptors!` — empties every frame's interceptor chain.
- `re-frame.http.managed/interceptors-snapshot` — a frame's interceptor chain, in order.
- `re-frame.http.test-support/run-request-chain` — runs a request's `:before` interceptors, as the canned effects do, and returns the resulting context.
- `re-frame.http.test-support/capture-and-run-request-chain` — the same, also returning the chain it captured, for a caller that goes on to run the `:after` half.
- `re-frame.http.test-support/emit-canned-success!` — delivers a canned success reply for a request whose `:before` half already ran.
- `re-frame.http.test-support/canned-success-handler` and `canned-failure-handler` — the handlers behind the two canned effects.

## See also

- [re-frame.resources](re-frame.resources.md) — cached reads and mutations that issue their requests through `:rf.http/managed`.
- [re-frame.core](re-frame.core.md) — `:rf.http/managed` in the standard fx table, the effect map's `:sensitive` key, and the instrumentation and egress functions (`project-egress`, the observability-sink registration).
- [re-frame.schemas](re-frame.schemas.md) — the `:schema` registration key and per-slot `:sensitive?` / `:large?` schema props.
- [Test a pipeline run](../core/testing/pipeline-runs.md#answer-the-http-canned-replies-by-method-and-url) — HTTP stubs with `dispatch-sync` and `poll-until`.
- [Interceptors and secrets](../async/http-going-further.md) — interceptors and trace redaction in the guide.
