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
            :decode     :json
            :on-success [:cart/loaded]
            :on-failure [:cart/load-failed]}]]}))

(rf/reg-event :cart/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc-in db [:cart :items] value)}))

(rf/reg-event :cart/load-failed
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc-in db [:cart :load-error] error)}))

(rf/reg-sub :cart/items
  (fn [db _] (get-in db [:cart :items])))
```

Retries, timeouts, abort, decode and accept checks are further optional keys in the same args map.

There is no `re-frame.http` namespace and no per-verb helper. The fx are addressed by keyword, `reg-http-interceptor` is called on the facade as `rf/reg-http-interceptor`, and the stub transports are functions in `re-frame.http.stub-transport`. An app that issues many requests usually writes its own function that builds the args map, adding a base URL, default headers, a default `:decode` and body encoding; see [Your own request builder](../async/http.md#your-own-request-builder).

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
        - A Malli schema parses JSON and validates it. It decodes only a response whose `Content-Type` is JSON or absent; any other type fails as `:rf.http/decode-failure`. It needs Malli at run time, and `day8/re-frame2-http` does not bring it: add `day8/re-frame2-schemas` and require `re-frame.schemas` at boot. Without Malli, a request with a schema `:decode` is refused at dispatch with `:rf.error/schemas-artefact-missing`, before anything is sent. See [Response-body classification](#response-body-classification) for the schema's `:sensitive?` and `:large?` props.
        - A fn `(fn [body-text headers] → value)` does it all. It receives the raw body text (`""` for an empty body), and a throw fails as `:rf.http/decode-failure`.
    - `:retry`: `:on` must be a set drawn from the retryable kinds `#{:rf.http/transport :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`; `#{}` or `nil` retries nothing. `:max-attempts` is required in a `:retry` map and counts the first attempt, so `3` means up to two retries; a `:retry` map without it is refused rather than defaulted, because every extra attempt re-sends the request. `:backoff` defaults to `{:base-ms 250 :factor 2 :max-ms 5000}`, and `:jitter true` adds ±25%. Only the final failure reaches your reply target.
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
    | `:mode` | `:cors`, `:same-origin`, `:no-cors` | `:cors` | Browser Fetch mode. `:no-cors` cannot provide a readable cross-origin response; it is not a way to bypass CORS. CLJS only. |
    | `:cache` | `:default`, `:no-store`, `:reload`, `:no-cache`, `:force-cache`, `:only-if-cached` | `:default` | Browser HTTP-cache policy; see below. CLJS only. |
    | `:referrer` | same-origin URL string, `""`, or `"about:client"` | `"about:client"` | URL to use as referrer, no referrer, or the client's referrer. CLJS only. |
    | `:integrity` | a subresource-integrity string | none | Expected response hash, such as `"sha256-…"`. CLJS only. |
    | `:redirect` | `:follow`, `:error` or `:manual` | `:follow` | With `:error` or `:manual`, a redirect response fails the request instead of being followed. |
    | `:sensitive?` | boolean | `false` | The same as the top-level `:sensitive?`. |

    - `:params`: a sequential value repeats the key (`{:tag ["a" "b"]}` → `tag=a&tag=b`); an empty sequence adds nothing. `nil` emits an empty value (`tag=`). Keyword keys use their unqualified name, while keyword values keep their namespace without the colon (`{:sort :order/asc}` → `sort=order%2Fasc`). Other scalar values are stringified. Existing query pairs are retained; params are appended before any fragment.
    - `:body`: the fn is called before each attempt, so a retry gets a fresh body. A fn that throws, or a body the encoder rejects, fails the request as `:rf.http/transport` with `:stage :request-prep`.
    - `:request-content-type`: `:json` JSON-encodes the body and sets `application/json`; `:form` URL-encodes a map and sets `application/x-www-form-urlencoded`; `:text` stringifies the body and sets `text/plain`. An explicit MIME string stringifies the body and sets that MIME type: `"application/json"` does **not** JSON-encode a Clojure map, so use `:json` for that. Without the option, a Clojure map, sequence or set is JSON-encoded; other bodies pass through unchanged without an inferred header. A `nil` body sends no body and infers no content type. An explicit `Content-Type` header wins over the inferred header, but does not change the encoding selected here. JSON writes a keyword with its namespace and no colon (`:user/id` → `"user/id"`) and a UUID as its string.
    - `:cache`: `:default` uses normal HTTP freshness rules; `:no-store` bypasses the cache; `:reload` fetches and updates it; `:no-cache` revalidates a cached response; `:force-cache` accepts a cached response regardless of freshness; `:only-if-cached` requires one and requires `:mode :same-origin`. These are [Fetch options](https://fetch.spec.whatwg.org/#concept-request-cache-mode), separate from a re-frame resource cache. The browser validates their combinations. Although Fetch defines a navigation mode, application requests cannot use `:mode :navigate`.
- **Errors**, all raised at dispatch, before the request is sent. The effect throws: the request is not sent, no reply is dispatched, the event's other effects still run, and error listeners receive `:rf.error/fx-handler-exception`, whose `:exception` carries the id below:
    - `:rf.error/http-bad-request` — the final `:url`, after the `:before` interceptor chain, is missing, `nil`, non-string or blank.
    - `:rf.error/http-bad-retry-on` — `:retry :on` is not a set drawn from the retryable kinds, or a `:retry` map has no `:max-attempts`.
    - `:rf.error/http-bad-reply-target` — a reply-target value that is neither an event vector nor `nil`; `:reply-to` beside `:on-success` / `:on-failure` (`:reason :mixed-addressing`); or one of `:on-success` / `:on-failure` without the other (`:reason :unpaired-split`).
    - `:rf.error/http-no-reply-target` — none of `:reply-to`, `:on-success` or `:on-failure` is present.
    - `:rf.error/schemas-artefact-missing` — the `:decode` is a schema but Malli is not loaded, so the response could not be validated; or the schema carries a `:sensitive?` or `:large?` prop but `re-frame.schemas` is not loaded, so the response could not be classified. Add `day8/re-frame2-schemas` and require `re-frame.schemas` at boot.
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
    - The child completes like any child machine: under `:spawn`, the parent's `:on-done` receives the value as the completion's `:result` and its `:on-error` the failure; under `:spawn-all`, the result folds into the join.
    - Leaving the parent state aborts the request, and the parent receives no completion.
    - The child sets its own `:on-success` / `:on-failure`, so `:data` must not carry `:reply-to`, `:on-success` or `:on-failure`. Any of them makes the child throw `:rf.error/http-bad-reply-target` (`:reason :machine-owns-reply`, `:keys` naming them) when it starts, before a request is sent. The runtime reports the throw as `:rf.error/machine-action-exception`, with the error in its `:exception-data`, and routes it to the parent's `:spawn :on-error`.
    - See [From a state machine](../async/http.md#from-a-state-machine), which also shows `:spawn-all` fan-out.
- **Example**:
  ```clojure
  :loading
  {:spawn {:machine-id :rf.http/managed
           :data       {:request {:url "/api/me"} :decode :json}
           :on-done    :ready
           :on-error   :load-failed}}
  ```

## Replies

### Reply shape

Every reply is one map keyed on `:status`, which is `:ok`, `:error` or `:cancelled`:

```clojure
;; Success — :meta carries the response's :http-status, :status-text and :headers
;; (header names lower-cased)
{:status :ok :value decoded-body :meta {:http-status 200 :status-text "OK" :headers {…}} …}

;; Failure — per-kind tags (:http-status / :status-text / :message / ...) sit
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
- `:rf.reply/work-id` — `[:rf.work/http issuance attempt]`, the join to the trace stream, and `:rf.reply/work-kind :http`.
- `:rf.frame/id` — the issuing frame.
- `:completed-at` — the completion time in epoch milliseconds. The reply event's `:rf/time-ms` coeffect carries the same value.
- On `:cancelled`, `:rf.reply/cancel-reason` is `:user` (`:rf.http/managed-abort` or `:abort-signal`) or `:actor-destroyed`. Supersession, frame destroy and epoch restore deliver no reply.

### Reply addressing

Every request must name where its reply goes. The runtime appends the reply map as the last argument of the event vector you name:

- `:reply-to` sends both the success and the failure reply to one event; resources and mutations use the same key. A `:cart/load` handler addressed with `:reply-to [:cart/load]` receives `[:cart/load {:status :ok :value decoded-body …}]` (or the `:error` / `:cancelled` shape) and branches on `(:status reply)`. Put request context in the vector: `:reply-to [:cart/load ctx]` delivers `[:cart/load ctx <reply>]`.
- `:on-success` / `:on-failure` route the same reply map to two events, and are always supplied together. A `:cart/loaded` handler receives `[:cart/loaded {:status :ok :value decoded-body …}]` and destructures it directly: `[_ {:keys [value]}]` for success, `[_ {:keys [error]}]` for failure. Neither may appear beside `:reply-to`; the check is on key presence, so a `nil` value counts.
- `:reply-to nil` means fire-and-forget: no reply is delivered. `:on-success nil` or `:on-failure nil` drops one side of the split form.
- Supplying only `:on-success`, or only `:on-failure`, raises `:rf.error/http-bad-reply-target` with `:reason :unpaired-split`, and nothing is sent: the branch left out would drop its replies with no handler. Write `nil` for a side you mean to drop.
- Omitting all three keys raises `:rf.error/http-no-reply-target`: the framework never picks a reply target for you, because an implicit target can swallow failures.

The cell below answers two requests from a [stub transport](#testing-without-a-network) and shows the whole event each reply arrives as: the `:reply-to` vector, context map included, with the reply map appended.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.http.stub-transport :as stub-transport])

;; A fake backend for this frame: the cart loads, and a missing cart fails
;; with a 404. A real app installs no transport.
(stub-transport/install-transport! :app/cart
  (stub-transport/route-transport
    {[:get "/api/cart"]         {:body [{:id 1 :name "widget"}]}
     [:get "/api/cart/missing"] {:status 404 :status-text "Not Found" :body "no such cart"}}))

(rf/reg-event :cart/load
  (fn [_ [_ url]]
    {:fx [[:rf.http/managed {:request  {:method :get :url url}
                             :reply-to [:cart/reply {:url url}]}]]}))

;; Keep the whole event the reply arrived as.
(rf/reg-event :cart/reply
  (fn [{:keys [db]} event]
    {:db (assoc db :cart/last-reply event)}))

(rf/reg-sub :cart/last-reply (fn [db _] (:cart/last-reply db)))

(rf/reg-view reply-view []
  [:div
   [:button {:on-click #(dispatch [:cart/load "/api/cart"])} "Load the cart"]
   " "
   [:button {:on-click #(dispatch [:cart/load "/api/cart/missing"])} "Load a missing cart"]
   [:pre {:style {:white-space "pre-wrap"}}
    (pr-str @(subscribe [:cart/last-reply]))]])

[rf/frame-root {:id :app/cart}
 [reply-view]]
```

[Handling the reply](../async/http.md#handling-the-reply) shows both styles in full.

## Failure kinds (closed set)

A failure's `:kind` is one of seven values, all reserved under `:rf.http/*`. The set is closed, so a handler's `case` on `:kind` can be exhaustive.

| `:kind` | Meaning | Tags |
|---|---|---|
| `:rf.http/transport` | Network, DNS or connection error before any HTTP response, or a `:body` that could not be prepared. In the browser a Fetch rejection against a cross-origin URL carries `:cross-origin? true`: the browser reports a CORS refusal and a dropped connection the same way, so it may be either. | `:message`, `:cause`; `:stage :request-prep` for a body failure; `:cross-origin? true` (CLJS only) |
| `:rf.http/timeout` | The per-attempt timeout fired. | `:elapsed-ms`, `:limit-ms`; on the JVM also `:message` |
| `:rf.http/http-4xx` | A 4xx response, or any other non-2xx status below 500 (a 1xx, or a 3xx that was not followed). | `:http-status`, `:status-text`, `:body` (parsed when the Content-Type is JSON, else raw text), `:body-text` (raw text), `:headers` |
| `:rf.http/http-5xx` | A 5xx response. | `:http-status`, `:status-text`, `:body` (parsed when the Content-Type is JSON, else raw text), `:body-text` (raw text), `:headers` |
| `:rf.http/decode-failure` | A 2xx response whose body the decoder rejected. | `:body-text`, `:cause` (the message), `:schema-validation-failure?`; `:reason :too-many-keys` and `:limit` when the key cap was exceeded |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}`, threw, or returned anything but a map with exactly one of `:ok` / `:failure`. | `:decoded` (the body before `:accept`); `:detail` is `user-map`, `{:rf.http/bad-accept :threw :message …}` or `{:rf.http/bad-accept :malformed-return :returned …}` |
| `:rf.http/aborted` | Aborted via `:request-id`, `:abort-signal`, or the destruction of the actor or frame that issued it. | `:reason`, `:actor-id`; `:message` on some paths |

- The status is classified before the body is decoded, so a 4xx or 5xx response never runs `:decode`. Its raw text is on the failure map's `:body-text`, and its `:body` is that text parsed, keys as keywords, when the Content-Type is JSON (`json` or a `+json` suffix), else the text itself. The parse is best-effort: a body that does not parse, or exceeds the key cap, stays the raw string, and the kind is still `:rf.http/http-4xx` / `:rf.http/http-5xx`. An empty 2xx JSON body decodes to `nil` rather than failing.
- The first five kinds are the ones `:retry :on` accepts. `:rf.http/decode-failure`, `:rf.http/accept-failure` and `:rf.http/aborted` are never retried.
- Every failure map also names the request it came from: `:request {:method :url}` (the method sent, `:get` when the request set none), `:request-id`, `:attempt`, `:max-attempts` (when a retry policy was set) and `:work/id`.
- Resources and mutations store this same map as their `:error` (and a resource's `:refresh-error`), so one `case` on `:kind` serves both.

[Failures are a closed set](../async/http.md#failures-are-a-closed-set) explains the kinds in the guide.

## Request interceptors

Register an interceptor to change every request a frame issues: add an auth header, stamp a request id, log timings. Define it once at the top level with `rf/reg-http-interceptor`, then turn it on for a frame by naming its id in that frame's `:http-interceptors` config. Remove a definition with `(rf/clear :http-interceptor id)`. The facade has no `clear-http-interceptor`; `re-frame.http.managed/clear-http-interceptor` below is the function behind it. [Interceptors: stamp every request once](../async/http-going-further.md#interceptors-stamp-every-request-once) walks through the common cases.

### `reg-http-interceptor`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-http-interceptor id interceptor-map) → id
  ```
- **Description**: Registers an interceptor definition for `:rf.http/managed`: its `:before` fn can rewrite each outgoing request, and its `:after` fn each reply. The definition belongs to no frame; it runs only for a frame that names `id` in its `:http-interceptors` config.
    - `interceptor-map` carries at least one of `:before (fn [ctx] ctx')` and `:after (fn [ctx response] response')`, plus the standard `:rf/registration-metadata` keys.
    - A `:before` fn receives a ctx `{:request … :args … :frame … :event … :sensitive? …}` and returns it, possibly changed. `:sensitive?` is `true` when the args marked the request sensitive, at the top level or under `:request`; the `:request` left at the end of the chain is what is sent. An `:after` fn receives that final ctx and the reply map, so it can match a response to its request, and returns the reply.
    - `:before` fns run in the order the frame's `:http-interceptors` vector names them, before the request goes to the platform HTTP client. `:after` fns run in reverse of that order, after the reply is built and before it is dispatched. The reply then goes to `:on-success` when the final reply's `:status` is `:ok`, and to `:on-failure` otherwise.
    - `:before` fns run once per request, not per retry attempt: every attempt sends the request the chain produced. `:after` fns run once, on the final reply, including a `:cancelled` one.
    - A request uses the chain as it stood when the request was issued, for both halves and every retry. Registering, replacing or clearing an interceptor, or re-making the frame with a different `:http-interceptors`, affects only requests issued afterwards.
    - A `:before` can mark a request sensitive by setting `[:request :sensitive?] true`.
    - Registration reads no frame: call it at the top level, at namespace load, like any other `reg-*`. Load order configures nothing; a frame's `:http-interceptors` vector is the whole chain and its order. A frame that names no chain runs no interceptor, and a definition no frame names is unused.
    - The frame's ids resolve through its own registrations when a request is issued, so a later image's definition of an id shadows the app's for frames built on that image. Frames diverge by naming different ids.
    - Re-registering an existing id replaces its definition; every frame naming it runs the new one from its next request.
- **Errors**:
    - At registration: `:rf.error/http-bad-interceptor`, for an invalid shape, such as a non-keyword id, neither `:before` nor `:after`, or a non-fn slot. A `:frame` key raises it too, with a message naming `:http-interceptors`, since a definition belongs to no frame.
    - At `make-frame`: `:rf.error/http-bad-interceptor`, when `:http-interceptors` is not a vector of keywords.
    - When a request is issued: `:rf.error/unregistered-interceptor`, when the frame names an id no `reg-http-interceptor` defines. It names the id, and the request is not sent.
    - When a request runs. A failing `:before` means the request is not sent, and a failing `:after` means the reply is not delivered:
        - `:rf.error/http-interceptor-failed`: a `:before` or `:after` threw. Carries `:frame`, `:interceptor-id`, `:url` and `:cause`, plus `:phase :after` when an `:after` threw.
        - `:rf.error/http-interceptor-bad-return`: a `:before` or `:after` returned something other than a map. Carries `:id` and `:returned`.
- **Example**:
  ```clojure
  (rf/reg-http-interceptor :auth/inject
    {:before (fn [ctx]
               ;; read the token fresh on each request, so a rotated token is picked up
               (let [token (-> (rf/app-db-value (:frame ctx)) :auth :token)]
                 (cond-> ctx
                   token (assoc-in [:request :headers "Authorization"]
                                   (str "Bearer " token)))))})

  ;; Or with both sides — :before stamps a start mark, :after reads it.
  (rf/reg-http-interceptor :telemetry
    {:before (fn [ctx] (assoc ctx ::started (js/Date.now)))
     :after  (fn [ctx resp]
               (assoc resp :elapsed-ms (- (js/Date.now) (::started ctx))))})

  ;; The frame names its chain, in order.
  (rf/make-frame {:id :rf/default :http-interceptors [:auth/inject :telemetry]})
  ```

### `re-frame.http.managed/clear-http-interceptor`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-http-interceptor id) → id
  ```
- **Description**: Removes the interceptor definition `id`. Application code calls it through the facade, as `(rf/clear :http-interceptor id)`.
    - A definition belongs to no frame, so clearing it removes it for every frame at once. A frame that still names the id fails its next request at issue with `:rf.error/unregistered-interceptor`, as for an id never registered.
    - To stop one frame running an interceptor, re-make that frame without the id in its `:http-interceptors`.
- **Errors**:
    - `:rf.error/registrar-clear-bad-request`: through the facade, `(rf/clear :http-interceptor id opts)` with any opts map, since the clear takes no frame.
- **Example**:
  ```clojure
  (rf/clear :http-interceptor :auth/inject)
  ```

## Privacy and classification

Four declarations control what managed HTTP redacts from traces, off-box records and SSR payloads; there is no function for adding names to the denylists at runtime. [Keeping secrets out of the trace](../async/http-going-further.md#keeping-secrets-out-of-the-trace) and [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) explain the model end to end.

| Declaration | What it covers | Where declared |
|---|---|---|
| Built-in header denylist | A fixed set of always-sensitive header names (`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-Session-Token`, `X-CSRF-Token`, `X-XSRF-Token`, `Authentication`, `WWW-Authenticate`, `Proxy-Authenticate`), redacted in the `:headers` slot of every `:rf.http/*` trace regardless of `:sensitive?`. Case-insensitive; no frame can remove a name. | framework default |
| Built-in query-param denylist | A fixed set of always-sensitive query-param names (`api_key`, `apikey`, `api-key`, `access_token`, `accesstoken`, `auth`, `auth_token`, `authtoken`, `key`, `token`, `secret`, `password`, `passwd`, `session`, `session_id`, `sessionid`, `signature`, `sig`, `hmac`). The value is redacted inline in `:url` slots (`?api_key=:rf/redacted&page=2`), keeping the name and position. A hit also stamps `:sensitive? true` on the trace event, since the name alone is the signal. | framework default |
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
- Error bodies are always omitted from off-box records, whatever `:sensitive?` says: a 4xx/5xx `:body` and `:body-text`, and a decode-failure `:body-text`, are bodies no schema has classified, and error bodies often echo request context or tokens.

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

`re-frame.http.stub-transport` answers managed HTTP without a network, for tests and demos. A stub transport replaces only the host send — Fetch in the browser, `java.net.http.HttpClient` on the JVM — so every request still runs the real pipeline: the `:before` and `:after` interceptors, status classification, `:decode`, `:accept`, retry and backoff, timeouts, the privacy scrub, supersession and abort. Its replies carry every key listed under [Reply shape](#reply-shape). It ships in the same artefact, `day8/re-frame2-http`, and is not on the `re-frame.core` facade. [Testing without a network](../async/http.md#testing-without-a-network) and [Answer the HTTP](../core/testing/pipeline-runs.md#answer-the-http-a-stub-transport) show it in a test.

```clojure
(:require [re-frame.http.stub-transport :as stub-transport])
```

A **response**, wherever these functions take one, is a map:

- `:status` — defaults to 200. `:status-text` and `:headers` are optional.
- `:body` — a string is sent verbatim. Any other value is JSON-encoded, with `Content-Type: application/json` unless `:headers` names a content type, and is decoded as a server's body would be: object keys arrive as keywords and keyword values as strings. A `:decode` schema coerces them back.
- `:body-binary` — replaces `:body` for a binary read.
- `:delay-ms` — `route-transport` only: this answer waits that long.

An HTTP error is a response with its status: `{:status 503}` fails as `:rf.http/http-5xx`, with the body text on the failure's `:body`. `{:failure {:kind :rf.http/transport}}`, or `:rf.http/timeout`, is a network failure instead of a response.

A request is keyed by the method and the URL it goes out on: after the `:before` interceptors, with any `:params` merged in as a query string, so a request carrying `:params {:page 2}` to `/api/todos` is keyed `[:get "/api/todos?page=2"]`.

A frame made with the `:test` or `:story` preset sets `:rf.http/stub-only? true`, so its requests never reach the network: one that no stub transport answers fails as `:rf.http/transport`, with a message naming `with-transport` and `install-transport!`.

### `re-frame.http.stub-transport/route-transport`

- **Kind**: function
- **Signature**:
  ```clojure
  (route-transport routes)      → transport
  (route-transport routes opts) → transport
  ```
- **Description**: A transport that answers each request from `routes`.
    - `routes` is a route map, `{[<method> <url>] response-or-fn}`. A route's value is a response, or `(fn [wire-request] response)`. A request no route matches fails as `:rf.http/transport`, naming its method and URL.
    - `routes` may instead be one `(fn [wire-request] response)` that answers every request: a fake backend that routes for itself.
    - `wire-request` is the attempt as the host would send it, `{:method :url :headers :body :timeout-ms}`, with the body encoded.
    - `opts` takes `:delay-ms`, how long each answer waits, so a demo's loading states show. A response's own `:delay-ms` wins. With no delay the answer is inline: the `dispatch-sync` that issued the request has its reply before it returns.
- **Example**:
  ```clojure
  (deftest cart-loads
    (rf/with-new-frame [_ (rf/make-frame {})]
      (stub-transport/with-transport
        (stub-transport/route-transport
          {[:get "/api/cart"] {:body [{:id 1 :name "widget"}]}})
        (fn []
          (rf/dispatch-sync [:cart/load])
          (is (= 1 (count (rf/subscribe-once [:cart/items]))))))))
  ```

### `re-frame.http.stub-transport/pending-transport`

- **Kind**: function
- **Signature**:
  ```clojure
  (pending-transport) → {:transport t :exchanges a}
  ```
- **Description**: A transport that holds every request until the caller settles it, for in-flight, abort and late-completion tests. `t` is the transport, and `a` an atom of the exchanges it has received, in issue order, each `{:request wire-request :respond! f :fail! f :cancelled? f}`.
    - `(respond! response)` answers the request with a response map; `(fail! failure)` fails it with `{:kind :rf.http/transport}` or `{:kind :rf.http/timeout}`. Each returns whether it settled the request. Once the request has been answered, aborted or timed out, they deliver nothing and return `false`, so a test can answer an aborted request late and see the answer fenced.
    - `(cancelled?)` is `true` once the request was aborted or timed out.
    - An answer given from test code, outside any event, is dispatched like any reply, so wait for it (`poll-until`) before asserting on what it changed.
- **Example**:
  ```clojure
  (let [{:keys [transport exchanges]} (stub-transport/pending-transport)]
    (stub-transport/with-transport transport
      #(rf/dispatch-sync [:cart/load]))
    (is (= "/api/cart" (:url (:request (first @exchanges)))))  ;; held, not answered
    ((:respond! (first @exchanges)) {:body [{:id 1 :name "widget"}]}))
  ```

### `re-frame.http.stub-transport/with-transport`

- **Kind**: function
- **Signature**:
  ```clojure
  (with-transport transport thunk) → the value thunk returns
  ```
- **Description**: Sends every `:rf.http/managed` request issued while `thunk` runs through `transport`. The test scope.
    - The transport is captured when the request issues, so its retries, and an answer that arrives after the scope has exited — a delayed route, a held exchange answered later — still go through it.
    - A request is in the scope only if it issues while `thunk` runs, so issue requests with `dispatch-sync` inside it.
    - An inner scope shadows an outer one, and a scope wins over a frame's installed transport.

### `re-frame.http.stub-transport/install-transport!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-transport! frame transport) → nil
  ```
- **Description**: Sends every `:rf.http/managed` request `frame` issues through `transport` until the frame is destroyed: a demo's fake backend. `frame` is a frame id or a live frame value. `nil` uninstalls it. A `with-transport` scope still wins for the requests issued inside it.
- **Example**:
  ```clojure
  ;; cf. examples/core/login/core.cljs
  (stub-transport/install-transport! :rf/default model/demo-backend)
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
| `:rf.warning/http-header-invalid` | `:warning` | Per request header the platform's header builder rejects, such as an empty name or a value carrying `\r` or `\n`. That header is left out and the request goes on with the rest. Carries `:url`, `:header`, `:cause`; never the value. |
| `:rf.http/cljs-only-key-ignored-on-jvm` | `:warning` | JVM only, per occurrence: the request carried a key the JVM transport ignores (`:mode`, `:cache`, `:referrer`, `:integrity` or `:credentials` in `:request`, or `:abort-signal`). The request proceeds without it. Carries `:key`, `:url`. |
| `:rf.http/binary-decode-degraded-on-jvm` | `:warning` | JVM only, per request with an explicit `:decode` of `:blob`, `:array-buffer` or `:form-data`: the body arrives as a `byte[]` rather than the browser object. Carries `:decode`, `:url`. |
| `:rf.error/http-reply-tail-failed` | `:error` | In every build: delivering the reply threw after the transport completed (an `:after`, the reply target, or the completion cascade). There is no retry and no re-send; the reply is not delivered. |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded. Carries `:id`. |
| `:rf.http.interceptor/cleared` | `:info` | `(rf/clear :http-interceptor id)` removed an existing definition. Carries `:id`. |
| `:rf.error/http-interceptor-failed` | `:error` | An interceptor's `:before` or `:after` threw. Carries `:frame`, `:interceptor-id`, `:url`, `:cause`, plus `:phase :after` when an `:after` threw. The request is not sent (`:before`) or the reply is not delivered (`:after`). |
| `:rf.error/http-interceptor-bad-return` | `:error` | An interceptor's `:before` or `:after` returned something other than a map. Carries `:id`, `:returned`. The request is not sent (`:before`) or the reply is not delivered (`:after`). |

## Framework integration

Not for application code. The framework's own tests reach these across a namespace boundary; they are public for that reason only, at the implementation tier, and may change without notice.

- `re-frame.http.managed/clear-all-in-flight!` — aborts every in-flight request, then empties the registry.
- `re-frame.http.managed/in-flight-snapshot` — the in-flight requests keyed by request id, for every frame or for one frame.
- `re-frame.http.managed/actor-in-flight-snapshot` — the in-flight requests an actor issued, keyed by actor id.
- `re-frame.http.managed/seed-in-flight-for-test!` — records a fabricated in-flight request in the one registry real requests are filed in, so every snapshot and abort path sees it.
- `re-frame.http.managed/abort-on-actor-destroy` — aborts the requests an actor issued; the actor-destroy cascade calls it.
- `re-frame.http.managed/clear-all-http-interceptors!` — removes every interceptor definition.
- `re-frame.http.managed/interceptors-snapshot` — with no argument, every interceptor definition keyed by id; with a frame id, the chain a request from that frame would capture, in order.

## See also

- [re-frame.resources](re-frame.resources.md) — cached reads and mutations that issue their requests through `:rf.http/managed`.
- [re-frame.core](re-frame.core.md) — `:rf.http/managed` in the standard fx table, the effect map's `:sensitive` key, and the instrumentation and egress functions (`project-egress`, the observability-sink registration).
- [re-frame.schemas](re-frame.schemas.md) — the `:schema` registration key and per-slot `:sensitive?` / `:large?` schema props.
- [Test a pipeline run](../core/testing/pipeline-runs.md#answer-the-http-a-stub-transport) — stub transports with `dispatch-sync` and `poll-until`.
- [Interceptors and secrets](../async/http-going-further.md) — interceptors and trace redaction in the guide.
