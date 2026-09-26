# Managed HTTP

Describe an HTTP request as data in an event's `:fx`, and the runtime issues it, applies your timeout and retry policy, decodes and validates the body, classifies any failure into a closed set of kinds, and dispatches the reply to an event you name. Aborting and superseding a request by id are built in.

Use `:rf.http/managed` directly for a request whose reply one event handles, such as a login or a one-off load you keep in your own `app-db`. When the same server data is read from more than one place, or needs caching, deduplication, staleness or a refetch after a write, register a [resource](re-frame.resources.md) instead. Resources and mutations issue their requests through this same fx: their request fns return the args map described below, and the runtime supplies the reply addressing.

Managed HTTP ships in the optional artefact `day8/re-frame2-http`. Require `re-frame.http.managed` once at boot: loading it registers the `:rf.http/managed` fx. A facade call such as `rf/reg-http-interceptor` made without the artefact raises `:rf.error/http-artefact-missing`.

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
- **Options**:
    - `:request` — the request envelope: `:url` (required), `:method` (default `:get`), `:headers`, `:params` (a query-param map, encoded onto the URL), `:body` and `:request-content-type` (`:json`, `:form`, `:text` or a MIME string, which serialises `:body` and sets `Content-Type`; a Clojure collection `:body` without it is sent as JSON).
    - `:decode` — how to read a 2xx body. Absent or `:auto` picks from the response `Content-Type`; `:json`, `:text`, `:blob`, `:array-buffer` and `:form-data` force one; a Malli schema parses JSON and validates it; a fn `(fn [body-text headers] → value)` does it all. A schema needs Malli at run time, and `day8/re-frame2-http` does not bring it: add `day8/re-frame2-schemas` and require `re-frame.schemas` at boot. Without Malli the schema is skipped: the parsed JSON goes on to `:accept` and the success reply with no coercion or validation, and a dev build emits `:rf.warning/http-malli-absent` once per process. See also [Response-body classification](#response-body-classification).
    - `:accept` — `(fn [decoded] → {:ok value} | {:failure user-map})`, a domain check after decoding. `{:ok value}` makes `value` the success `:value`; `{:failure user-map}` fails the request as `:rf.http/accept-failure`.
    - `:retry` — `{:on #{categories} :max-attempts N :backoff {:base-ms :factor :max-ms :jitter}}`. `:on` must be a set drawn from the retryable kinds `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`; `#{}` or `nil` retries nothing. `:max-attempts` counts the first attempt, so `3` means up to two retries; without it nothing is retried. `:backoff` defaults to `{:base-ms 250 :factor 2 :max-ms 5000}`, and `:jitter true` adds ±25%. Only the final failure reaches your reply target.
    - `:timeout-ms` — per-attempt timeout, default 30000. An explicit `nil` or `0` turns it off.
    - `:rf.http/max-decoded-keys` — the most unique JSON object keys the decoder will intern for this request, default 10000. Exceeding it fails the request as `:rf.http/decode-failure` with `:reason :too-many-keys`.
    - `:reply-to` — the event vector that receives both the success and the failure reply; see [Reply addressing](#reply-addressing).
    - `:on-success` / `:on-failure` — separate success and failure event vectors (or `nil`), used instead of `:reply-to`.
    - `:request-id` — any `=`-comparable value naming the request, for [`:rf.http/managed-abort`](#rfhttpmanaged-abort-request-id) or supersession: issuing a new request with the same id while one is in flight supersedes it, and the old reply is never delivered. Ids are per frame, so two frames running the same code do not cancel each other.
    - `:abort-signal` — an external abort signal (CLJS only).
    - `:sensitive?` — redacts this request in traces; see [Privacy and classification](#privacy-and-classification).
- **Errors** (all raised at dispatch, before the request is sent):
    - `:rf.error/http-bad-request` — the final `:url`, after the `:before` interceptor chain, is missing, `nil` or blank.
    - `:rf.error/http-bad-retry-on` — `:retry :on` is not a set drawn from the retryable kinds.
    - `:rf.error/http-bad-reply-target` — a reply-target value that is neither an event vector nor `nil`, or `:reply-to` beside `:on-success` / `:on-failure` (`:reason :mixed-addressing`).
    - `:rf.error/http-no-reply-target` — none of `:reply-to`, `:on-success` or `:on-failure` is present.
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
- **Description**: Aborts the in-flight request with that `:request-id`, issued from the same frame; an id with nothing in flight is a no-op. Its reply target (`:reply-to` or `:on-failure`) receives the cancelled reply `{:status :cancelled :cancelled? true :rf.reply/cancel-reason :user :error {:kind :rf.http/aborted ...}}`, as described under [Reply addressing](#reply-addressing).
- **Example**:
  ```clojure
  (rf/reg-event :request/abort
    (fn [_ [_ request-id]]
      {:fx [[:rf.http/managed-abort request-id]]}))
  ```

## Reply addressing

Every reply is one map keyed on `:status`, which is `:ok`, `:error` or `:cancelled`:

```clojure
;; Success — :meta carries the response's :status, :status-text and :headers
;; (header names lower-cased)
{:status :ok :value decoded-body :meta {:status 200 :status-text "OK" :headers {…}} …}

;; Failure — per-kind tags (:status / :status-text / :message / ...) sit
;; flat on the failure map beside :kind
{:status :error
 :error  {:kind :rf.http/<category> ...}}

;; Abort / cancel
{:status :cancelled :cancelled? true :rf.reply/cancel-reason :user
 :error  {:kind :rf.http/aborted …}}
```

A timeout is a failure: `:status :error` with `:kind :rf.http/timeout`. The reply also carries `:attempt` and, when the request had a `:request-id`, `:correlation {:request-id …}`.

Every request must name where its reply goes. The runtime appends the reply map as the last argument of the event vector you name:

- `:reply-to` sends both the success and the failure reply to one event; resources and mutations use the same key. A `:cart/load` handler addressed with `:reply-to [:cart/load]` receives `[:cart/load {:status :ok :value decoded-body …}]` (or the `:error` / `:cancelled` shape) and branches on `(:status reply)`. Put request context in the vector: `:reply-to [:cart/load ctx]` delivers `[:cart/load ctx <reply>]`.
- `:on-success` / `:on-failure` route the same reply map to two events. A `:cart/loaded` handler receives `[:cart/loaded {:status :ok :value decoded-body …}]` and destructures it directly: `[_ {:keys [value]}]` for success, `[_ {:keys [error]}]` for failure. Neither may appear beside `:reply-to`; the check is on key presence, so a `nil` value counts.
- `:reply-to nil` means fire-and-forget: no reply is delivered. `:on-success nil` or `:on-failure nil` drops one side of the split form.
- Omitting all three keys raises `:rf.error/http-no-reply-target`: the framework never picks a reply target for you, because an implicit target can swallow failures.

[Handling the reply](../async/http.md#handling-the-reply) shows both styles in full.

## Failure categories (closed set)

A failure's `:kind` is one of eight values, all reserved under `:rf.http/*`. The set is closed, so a handler's `case` on `:kind` can be exhaustive.

| `:kind` | Meaning |
|---|---|
| `:rf.http/transport` | Network, DNS or connection error before any HTTP response. |
| `:rf.http/cors` | CORS preflight rejected (CLJS only). |
| `:rf.http/timeout` | The per-attempt timeout fired. |
| `:rf.http/http-4xx` | A 4xx response. |
| `:rf.http/http-5xx` | A 5xx response. |
| `:rf.http/decode-failure` | A 2xx response whose body the decoder rejected. |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}`, threw, or returned neither shape. |
| `:rf.http/aborted` | Aborted via `:request-id` or `:abort-signal`. |

- The status is classified before the body is read, so a 4xx or 5xx response is never decoded: its raw body is on the failure map's `:body`. An empty 2xx JSON body decodes to `nil` rather than failing.
- The first five kinds are the ones `:retry :on` accepts. `:rf.http/decode-failure`, `:rf.http/accept-failure` and `:rf.http/aborted` are never retried.
- Every failure map also names the request it came from: `:request {:method :url}`, `:request-id`, `:attempt`, `:max-attempts` (when a retry policy was set) and `:work/id`.
- Resources and mutations store this same map as their `:error` (and a resource's `:refresh-error`), so one `case` on `:kind` serves both.

[Failures are a closed set](../async/http.md#failures-are-a-closed-set) lists the tags each kind carries.

## Request interceptors

Register an interceptor to change every request a frame issues: add an auth header, stamp a request id, log timings. Call it as `rf/reg-http-interceptor`, and remove one with `(rf/clear :http-interceptor id)`: the facade has no `clear-http-interceptor`, although the artefact function below keeps that name. [Interceptors: stamp every request once](../async/http-going-further.md#interceptors-stamp-every-request-once) walks through the common cases.

### `reg-http-interceptor`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-http-interceptor id interceptor-map)
  ```
- **Description**: Registers an interceptor on a frame's `:rf.http/managed` chain: its `:before` fn can rewrite each outgoing request, and its `:after` fn each reply. Returns `id`.
    - `interceptor-map` carries at least one of `:before (fn [ctx] ctx')` and `:after (fn [ctx response] response')`, plus an optional `:frame` and the standard `:rf/registration-metadata` keys.
    - A `:before` fn receives a ctx `{:request … :args … :frame … :event …}` and returns it, possibly changed; the `:request` left at the end of the chain is what is sent. An `:after` fn receives that final ctx and the reply map, so it can match a response to its request, and returns the reply.
    - `:before` fns run in registration order, before the request goes to the platform HTTP client. `:after` fns run in reverse registration order, after the reply is built and before it is dispatched.
    - If a `:before` throws, the request is not sent; if an `:after` throws, the reply is not delivered. Either way `:rf.error/http-interceptor-failed` fires with `:frame`, `:interceptor-id`, `:url` and `:cause`, plus `:phase :after` when an `:after` threw.
    - The target frame is the explicit `:frame` if given, else the frame in scope (`with-frame`, an `:initial-events` step). With neither, it raises `:rf.error/no-frame-context` rather than falling back to `:rf/default`.
    - Re-registering an existing id replaces it in place, keeping its position in the chain. After a `clear-http-interceptor`, registering the same id appends it to the end.
    - An invalid shape (a non-keyword id, neither `:before` nor `:after`, a non-fn slot, a non-keyword `:frame`) raises `:rf.error/http-bad-interceptor`.
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

### `clear-http-interceptor`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-http-interceptor id)
  (clear-http-interceptor id {:frame target})
  ```
- **Description**: Removes an interceptor by id. Application code calls it through the facade, as `(rf/clear :http-interceptor id)` or `(rf/clear :http-interceptor id {:frame target})`.
    - `(clear-http-interceptor id)` uses the frame in scope. With none it raises `:rf.error/no-frame-context` rather than clearing from `:rf/default`.
    - `(clear-http-interceptor id {:frame target})` names the frame, like `reg-http-interceptor`'s `:frame`. `target` is a frame-id keyword or a live frame value.
    - The opts map must be exactly `{:frame target}` with a non-nil target. `{}`, `{:frame nil}`, a misspelled or extra key (`{:fram f}`) and a non-map second argument all raise `:rf.error/http-bad-interceptor` before any frame is resolved.
    - Through the facade, `(rf/clear :http-interceptor id opts)` checks the same `{:frame target}` shape itself, so a malformed opts map there raises `:rf.error/registrar-clear-bad-request` instead.
    - These two forms are the whole public surface; there is no frame-first `(clear-http-interceptor frame id)` form.
- **Example**:
  ```clojure
  (rf/clear :http-interceptor :auth/inject {:frame :app/main})
  ```

## Privacy and classification

HTTP carries secrets: passwords in request bodies, auth tokens in request headers, personal data in response bodies. The framework keeps these out of its own traces, off-box records and SSR payloads. Four declarations control what is redacted; there is no function for adding names to the denylists at runtime. [Keeping secrets out of the trace](../async/http-going-further.md#keeping-secrets-out-of-the-trace) and [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) explain the model end to end.

| Declaration | What it covers | Where declared |
|---|---|---|
| Built-in header denylist | A fixed set of always-sensitive header names (`Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-CSRF-Token`, …), redacted in the `:headers` slot of every `:rf.http/*` trace regardless of `:sensitive?`. Case-insensitive; no frame can remove a name. | framework default |
| Built-in query-param denylist | A fixed set of always-sensitive query-param names (`api_key`, `access_token`, `token`, `secret`, `password`, `session`, `signature`, …). The value is redacted inline in `:url` slots (`?api_key=:rf/redacted&page=2`), keeping the name and position. A hit also stamps `:sensitive? true` on the trace event, since the name alone is the signal. | framework default |
| Managed-HTTP carriers | App-specific sensitive names, declared on the `:rf.http/managed` `reg-fx` registration. `:headers` is a vector of names added to the built-ins, never a policy map, since the header built-ins cannot be removed. `:query-params` is a vector of names to add, or an `{:include […] :except […]}` policy map with effective policy `(defaults − except) ∪ include`; `:except` drops a built-in name from this app's own dev trace. Names are case-insensitive; a malformed block raises `:rf.error/bad-classification`. | `reg-fx :rf.http/managed` `:carriers {:headers […] :query-params […]}` |
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

### `re-frame.http.managed/managed-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (managed-handler frame-ctx args-map)
  ```
- **Description**: The `:rf.http/managed` fx handler. It is public so that an app can re-register `:rf.http/managed` with a `:carriers` block, as in the example above. Pass it to `reg-fx`; do not call it from app code.

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

    A token in the response body, classified by the `:decode` schema, and the same token stored in `app-db` need two declarations, because nothing carries one to the other. When the reply handler writes the token into `app-db`, classify that path too: return `:sensitive [[:auth :token]]` alongside `:db` from the `reg-event` handler (see [Classify a durable secret in app-db](../core/how-to/keep-secrets-out-of-traces.md#classify-a-durable-secret-in-app-db) and the commit-plane effects in [re-frame.core](re-frame.core.md#effects-and-interceptors)). Never copy a secret into an `app-db` path you have not classified, such as a JWT duplicated at `[:auth :user :token]`: the copy ships raw until that path is classified or the duplicate is removed.

## Testing without a network

`re-frame.http.test-support` drives managed HTTP in tests without a network: two fx that synthesise a reply inline, and helpers that answer requests from a route map. It ships in the same artefact, `day8/re-frame2-http`. Require it from test code only; loading it also registers the canned-reply fx. None of it is on the `re-frame.core` facade.

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
              :reply-to [:counter/loaded]}]]}))   ;; receives {:status :ok :value {:delta 5} …}
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
  (with-request-stubs route-map body-fn)
  ```
- **Description**: Calls `body-fn`, answering matching `:rf.http/managed` requests made while it runs from `route-map` instead of the network.
    - `route-map` is `{[<method> <url>] {:reply {:ok <value>}}}` for a success, or `{[<method> <url>] {:reply {:failure <failure-map>}}}` for a failure.
    - Plain `dispatch-sync` calls inside `body-fn` are routed by method and URL, with no `:fx-overrides` needed. A per-call `:fx-overrides` still wins.
    - Routes match the method and URL after the `:before` interceptors run, that is, what would actually be sent. A request with no matching route receives a synthesised `:rf.http/transport` failure.
    - Scopes nest: an inner scope's route map shadows the outer one, which is restored when the inner scope exits. The stub is registered once, when `re-frame.http.test-support` loads, and nothing is registered per scope, so a frame created before the scope was entered also routes through it.
    - The override target is the internal `:rf.test/managed-http-scope-stub` fx. When you need an `:fx-overrides` target yourself, use `:rf.http/managed-test-stub` from `install-managed-request-stubs!`.
- **Example**:
  ```clojure
  (deftest cart-loads
    (http-test-support/with-request-stubs
      {[:get "/api/cart"] {:reply {:ok [{:id 1 :name "widget"}]}}}
      (fn []
        ;; No manual :fx-overrides — auto-routes by method + URL.
        (rf/dispatch-sync [:cart/load])
        (is (= 1 (count (rf/subscribe-once [:cart/items])))))))
  ```

### `re-frame.http.test-support/install-managed-request-stubs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-managed-request-stubs! route-map)
  ```
- **Description**: Installs `route-map` as stubs that stay in place until `uninstall-managed-request-stubs!`, for stubs that span several `deftest`s. It registers the `:rf.http/managed-test-stub` fx, the stable `:fx-overrides` target, and returns that fx-id.
    - Unlike `with-request-stubs`, it does not reroute `:rf.http/managed`. Dispatch with `{:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}`, or wrap dispatches in `with-fx-overrides`, to route through it.
    - A nested install saves the previous stub handler, and the matching uninstall restores it (LIFO).
- **Example**:
  ```clojure
  ;; Stubs that span several deftests — install once, route via :fx-overrides.
  (http-test-support/install-managed-request-stubs!
    {[:get "/api/cart"] {:reply {:ok [{:id 1 :name "widget"}]}}})

  (rf/dispatch-sync [:cart/load]
                    {:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}})
  ```

### `re-frame.http.test-support/uninstall-managed-request-stubs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (uninstall-managed-request-stubs!)
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
| `:rf.http/<category>` | `:error` | A failure other than an abort, keyed by its `:kind` (`:rf.http/timeout`, `:rf.http/http-5xx`, …). Carries the redacted failure map. |
| `:rf.http/aborted` | `:info` | An abort, whatever its `:reason` (`:user`, `:request-id-superseded`, `:actor-destroyed`, `:frame-destroyed`, `:epoch-restored`, …). Carries `:kind`, `:request-id`, `:reason`, `:actor-id`, `:url`. |
| `:rf.http/stale-suppressed` | `:info` | An app reply was suppressed (supersession, obsolete actor target, epoch restore, frame destroy). Carries `:rf.reply/status :stale`, `:rf.reply/work-status :suppressed`, `:rf.reply/work-id`, `:rf.reply/carried` / `:rf.reply/current`, `:recovery`. |
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

## See also

- [re-frame.resources](re-frame.resources.md) — cached reads and mutations that issue their requests through `:rf.http/managed`.
- [re-frame.core](re-frame.core.md) — `:rf.http/managed` in the standard fx table, the `:sensitive` commit-plane effect, and the instrumentation and egress functions (`project-egress`, the observability-sink registration).
- [re-frame.schemas](re-frame.schemas.md) — the `:schema` registration key and per-slot `:sensitive?` / `:large?` schema props.
- [re-frame.test-support](re-frame.test-support.md) — combining HTTP stubs with `dispatch-sync` and `poll-until`.
- [Interceptors and secrets](../async/http-going-further.md) — interceptors and trace redaction in the guide.
