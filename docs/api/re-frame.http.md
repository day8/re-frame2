# Managed HTTP

Managed HTTP is an optional capability. An event dispatches the `[:rf.http/managed args-map]` fx. The implementation owns retries, cancellation, timeouts, decode, and failure classification. **The request is data** — there is no verb-helper namespace to require: the fx, its abort/canned siblings, the request-interceptor middleware, and the test stubs are keyword-addressed or re-exported on the `re-frame.core` façade.

The CLJS reference ships managed HTTP (Fetch in the browser, `java.net.http.HttpClient` on the JVM). Ports that omit it must not reuse the `:rf.http/*` namespace for anything else.

See [Managed HTTP — The request is a map](../async/http.md) for the teaching guide.

```clojure
(:require [re-frame.core :as rf]
          ;; Registers the `:rf.http/managed` fx at ns-load. Require it once,
          ;; at boot — nothing on the `rf/` façade does it for you.
          [re-frame.http.managed])
```

The `:rf.http/managed` fx is keyword-addressed — you use it in an event's `:fx`, and `re-frame.http.managed` is what registers it. The interceptor fn (`reg-http-interceptor`) is re-exported on the `re-frame.core` façade — clearing an interceptor from the façade is the kind-keyed `(rf/clear :http-interceptor id)`, not a `clear-http-interceptor` re-export — while the test-stub helpers are reached through `re-frame.http.test-support`. Everything ships in the `day8/re-frame2-http` artefact.

Repeating `{:request {:method :get :url …}}` at every call site is an **app** concern, not a framework one: an app that issues many requests writes one request-builder fn over the args map, carrying the policy a per-verb helper cannot — a base URL, default headers, a default `:decode`, body encoding. See [Your own request builder](../async/http.md#your-own-request-builder).

## Keyword surfaces — the managed-HTTP fx

### `[:rf.http/managed args-map]`

- **Kind**: fx
- **Args**: per [Managed HTTP — The request is a map](../async/http.md#the-request-is-a-map) and `:rf.fx/managed-args`
- **Description**: The one managed-HTTP fx-id. Keys in the args map:
  - `:request` — the request envelope; `:url` is **required**. A missing / nil / blank final URL raises `:rf.error/http-bad-request` at dispatch, after the `:before` chain runs.
  - `:decode` — decode policy.
  - `:accept` — accept fn.
  - `:retry` — `{:on #{categories} :max-attempts N :backoff {:base-ms :factor :max-ms :jitter}}`. `:on` must be a set drawn from the retryable subset `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`. Anything else raises `:rf.error/http-bad-retry-on` at dispatch.
  - `:timeout-ms` — per-attempt timeout, default 30000. An explicit `nil` or `0` opts out.
  - `:reply-to` — the unified reply target: one event vector for **both** the success and the failure reply (the app branches on `:status`). The same call-site key resources / mutations use.
  - `:on-success` / `:on-failure` — the split routing sugar: success / failure target events; event vector or nil. Any other value raises `:rf.error/http-bad-reply-target`. The two styles are **exclusive** — `:reply-to` beside either branch key raises the same error with `:reason :mixed-addressing`. Supplying **none** of the three raises `:rf.error/http-no-reply-target` at dispatch.
  - `:request-id` — for abort / supersede.
  - `:abort-signal` — optional (CLJS).
  - `:sensitive?` — see [Privacy and classification](#privacy-and-classification).

### `[:rf.http/managed-abort request-id]`

- **Kind**: fx
- **Args**: request-id
- **Description**: Abort the in-flight request with the given `:request-id`. The aborted request's reply is the canonical `{:status :cancelled :cancel/reason :user :error {:kind :rf.http/aborted ...}}` envelope, appended to the request's reply target (`:reply-to` or `:on-failure`) per [Reply addressing](#reply-addressing).
- **Example**:
  ```clojure
  (rf/reg-event :request/abort
    (fn [_ [_ request-id]]
      {:fx [[:rf.http/managed-abort request-id]]}))
  ```

### A minimal request

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

Retries, timeouts, schema validation, abort, decode customisation, and accept-fn refinement are optional keys in the args map.

## Reply addressing

Every reply is the one canonical reply envelope — a plain map keyed on a closed `:status`:

```clojure
;; Success
{:status :ok :value decoded-body …}

;; Failure — per-kind tags (:status / :status-text / :message / ...) ride
;; flat on the failure map alongside :kind
{:status :error
 :error  {:kind :rf.http/<category> ...}}

;; Abort / cancel
{:status :cancelled :cancel/reason :user
 :error  {:kind :rf.http/aborted …}}
```

Every request must address its reply; where the payload lands depends on how:

- **Unified `:reply-to`** — one event vector for **both** the success and the failure reply, the canonical envelope appended as the last arg. A `:cart/load` handler sees `[:cart/load {:status :ok :value decoded-body …}]` (or the `:error` / `:cancelled` shape) and branches on `(:status reply)`. Ride request context along the prefix — `:reply-to [:cart/load ctx]` delivers `[:cart/load ctx <envelope>]`. The same call-site key resources / mutations use.
- **Split `:on-success` / `:on-failure`** — pure routing sugar. Both receive the identical envelope appended as the last event-vector arg. Exclusive with `:reply-to`: a map carrying both raises `:rf.error/http-bad-reply-target` (`:reason :mixed-addressing`) at dispatch, on key presence rather than value. A `:cart/loaded` handler sees `[:cart/loaded {:status :ok :value decoded-body …}]` and destructures it directly (`[_ {:keys [value]}]` for success, `[_ {:keys [error]}]` for failure).
- **`:reply-to nil`** — the fire-and-forget spelling: no receiver at all. `:on-success nil` / `:on-failure nil` silences ONE side of the split form.
- **Omitting all of `:reply-to` / `:on-success` / `:on-failure`** raises `:rf.error/http-no-reply-target` at dispatch — the co-located default (reply merged under `:rf/reply` back to the originating event) was retired pre-alpha; the framework never silently addresses the reply.
- Any other non-vector value for a reply-target key raises `:rf.error/http-bad-reply-target`.

Both shapes are detailed in [Managed HTTP — Handling the reply](../async/http.md#handling-the-reply).

## Failure categories (closed set)

Eight failure `:kind` values, all reserved under `:rf.http/*`. The set is closed, so a handler's failure switch can be exhaustive.

| `:kind` | Meaning |
|---|---|
| `:rf.http/transport` | Network / DNS / connection error pre-HTTP. |
| `:rf.http/cors` | CORS preflight rejected (CLJS-only). |
| `:rf.http/timeout` | Per-attempt timeout fired. |
| `:rf.http/http-4xx` | Non-2xx 4xx response. |
| `:rf.http/http-5xx` | Non-2xx 5xx response. |
| `:rf.http/decode-failure` | 2xx response but decode rejected the body. |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}`. |
| `:rf.http/aborted` | Request aborted via `:request-id` or `:abort-signal`. |

See [Managed HTTP — Failures are a closed set](../async/http.md#failures-are-a-closed-set) for tags-by-kind.

## Request-interceptor middleware

A middleware surface that mirrors the rest of the `reg-*` family. Use it to inject behaviour into every request: an auth header, a request-id stamp, logging. The fn form `reg-http-interceptor` is re-exported on the `re-frame.core` façade; the façade inverse is the kind-keyed `(rf/clear :http-interceptor id)` rather than a `clear-http-interceptor` re-export (the artefact fn below keeps its own name). A façade call with `day8/re-frame2-http` absent raises `:rf.error/http-artefact-missing`. The data-shaped `:rf.fx/*` siblings below are keyword-addressed.

### `reg-http-interceptor`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-http-interceptor id interceptor-map)
  ```
- **Description**: Register an HTTP interceptor on a frame's `:rf.http/managed` middleware chain. Returns `id`.
  - `interceptor-map` carries at least one of `:before (fn [ctx] ctx')` (request-side) and `:after (fn [ctx response] response')` (response-side), plus optional `:frame` (explicit-frame override) and the standard `:rf/registration-metadata`.
  - The target frame is the explicit `:frame` when given, else the carried scope it registers under (`with-frame` / an `:initial-events` step). Registering under **no** scope raises `:rf.error/no-frame-context` — there is no `:rf/default` default.
  - An invalid shape (non-keyword id, neither `:before` nor `:after`, a non-fn slot, a non-keyword `:frame`) raises `:rf.error/http-bad-interceptor`.
  - Re-registering an existing id replaces the slot in place, position preserved. After a `clear-http-interceptor`, re-registering the same id appends to the end of the chain.
  - The `:before` chain runs in registration order; the `:after` chain runs in reverse registration order. `:after` sees the same ctx the `:before` chain produced (request-correlated handling).

### `clear-http-interceptor`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-http-interceptor id)
  (clear-http-interceptor id {:frame target})
  ```
- **Description**: Unregister an interceptor by id. The public surface is **exact** — the two shapes above, nothing else.
  - `(clear-http-interceptor id)` resolves the frame through the ambient (carried-scope) chain it runs under. Under no scope it raises the always-on `:rf.error/no-frame-context` rather than clearing against a synthesised `:rf/default`.
  - `(clear-http-interceptor id {:frame target})` names the frame explicitly via the trailing opts map, mirroring `reg-http-interceptor`'s `:frame` and the family's public-frame-targeting law (never a positional frame arg on a public surface). `target` is a present, non-nil frame-id keyword or a live frame value.
  - The two-arity opts map is **fail-closed**: it MUST be exactly `{:frame target}`. A `{}`, a `{:frame nil}`, a typo'd or extra key (`{:fram f}`), and a non-map second arg all raise `:rf.error/http-bad-interceptor` BEFORE any ambient frame is resolved or touched — never reinterpreted as a positional frame nor silently cleared against the ambient scope (rf2-s32bf). This is distinct from the single-arity no-scope `:rf.error/no-frame-context`.
  - The frame-first `(frame id)` spelling is a separate artefact-internal seam (`clear-http-interceptor*`), reached directly by internal cleanup that already holds a resolved frame — frame teardown, actor destroy — **not** a public arity of `clear-http-interceptor`.
- **Example**:
  ```clojure
  (rf/clear :http-interceptor :auth-header)
  ```

### Worked examples

```clojure
(rf/reg-http-interceptor :auth/inject
  {:before (fn [{:keys [request] :as ctx}]
             (assoc-in ctx [:request :headers "Authorization"]
                       (str "Bearer " (token-from-app-db))))})

;; Or with both sides — :before stamps a start mark, :after reads it.
(rf/reg-http-interceptor :telemetry
  {:before (fn [ctx] (assoc ctx ::started (js/Date.now)))
   :after  (fn [ctx resp]
             (assoc resp :elapsed-ms (- (js/Date.now) (::started ctx))))})
```

`:before` runs before the request is dispatched to the platform's HTTP client. `:after` runs after the response is built and before `:on-success` / `:on-failure` fire. If either throws, the corresponding side is not delivered: a throwing `:before` means the request is not dispatched; a throwing `:after` means the reply is suppressed. In both cases `:rf.error/http-interceptor-failed` fires with `:frame`, `:interceptor-id`, `:url`, and `:cause`. On the response side it also stamps `:phase :after`; that tag is absent for `:before`. See [Managed HTTP — Interceptors](../async/http-going-further.md#interceptors-stamp-every-request-once).

## Testing: stubbed responses

Test-support surface for driving the pipeline without the network: canned-reply fx, plus a scoped helper that reroutes requests at named routes. None of it is on the `re-frame.core` façade — `with-request-stubs` and the raw `install` / `uninstall` pair are all reached through the home namespace `re-frame.http.test-support`.

### `[:rf.http/managed-canned-success {:value v}]`

- **Kind**: fx
- **Description**: Synthesise the canonical success reply (`{:status :ok :value v}`) directly into `:fx`, for inline "stub THIS request" patterns.
  - `:value` defaults to `{:stubbed true}` when absent.
  - `:after-ms` (optional) — a positive value defers the reply via a `:dispatch-later` tick. Absent, `0`, or non-positive delivers immediately.
  - Runs the frame's `:before` and `:after` interceptor chains around the synthesised reply, exactly like the real transport path. Reply addressing (`:reply-to` / `:on-success`) applies as for `:rf.http/managed`; an unaddressed stub reply is silently dropped.
  - Registered at load of `re-frame.http.test-support`.
- **Example**:
  ```clojure
  (rf/reg-event :counter/retry-recover
    (fn [_ _]
      {:fx [[:rf.http/managed-canned-success
             {:request  {:method :get :url "api/flaky"}
              :decode   :json
              :value    {:delta 5}
              :reply-to [:counter/retry-recover]}]]}))
  ```

### `[:rf.http/managed-canned-failure {:kind <:rf.http/*> :tags {...}}]`

- **Kind**: fx
- **Description**: Synthesise the canonical failure reply directly into `:fx`.
  - `:kind` defaults to `:rf.http/transport`. `:tags` merge into the `:error` map.
  - An `:rf.http/aborted` kind yields `:status :cancelled`; every other kind yields `:status :error`.
  - Supports the same optional `:after-ms` deferral, interceptor-chain behaviour, and reply addressing (`:reply-to` / `:on-failure`) as `:rf.http/managed-canned-success`.
  - Registered at load of `re-frame.http.test-support`.
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
- **Description**: Scoped stubbing for `body-fn`'s dynamic extent.
  - `route-map` is `{[<method> <url>] {:reply {:ok <value>}}}` (success) or `{[<method> <url>] {:reply {:failure <failure-map>}}}` (failure).
  - Inside `body-fn`, requests matching a stubbed route bypass the real client. The helper binds one stable override target — `:rf.test/managed-http-scope-stub`, in the test-runner-internal `:rf.test/*` fx-stub family, registered once when `re-frame.http.test-support` loads — as the `:rf.http/managed` override, and carries the scope's route map on a dynamic var. It mints **no** per-scope fx and performs no registrar write; nested scopes compose because an inner scope's route-map binding shadows the outer's and restores it on exit. Because the target is registered at load time rather than minted per scope, it resolves through the sealed image generation of a frame created before the scope was entered — a `dispatch-sync` in a pre-created frame still routes through the stub.
  - Plain `dispatch-sync` calls auto-route by method + URL with no manual `:fx-overrides`. A per-call `:fx-overrides` still wins.
  - Routes match against the post-`:before` request (the method + URL the pipeline would actually issue). A request with no matching route receives a synthesised `:rf.http/transport` failure reply.
  - Not a `re-frame.core` façade export — call it through its home namespace `re-frame.http.test-support`.
- **Example**:
  ```clojure
  (http-test-support/with-request-stubs
    {[:get "/articles"] {:reply {:ok [:hello :world]}}}
    (fn []
      ;; No manual :fx-overrides — auto-routes by method + URL.
      (rf/dispatch-sync [:articles/list])))
  ```

### `re-frame.http.test-support/install-managed-request-stubs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-managed-request-stubs! route-map)
  ```
- **Description**: Lower-level than `with-request-stubs`. Use it when stubs span multiple `deftest`s. It registers the `:rf.http/managed-test-stub` fx — the stable, documented `:fx-overrides` target — which persists until `uninstall-managed-request-stubs!`. Returns the stub fx-id.
  - Unlike the wrapper, this does **not** bind the `:rf.http/managed` override. Dispatch with `{:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}` (or wrap dispatches in `with-fx-overrides`) to route through it.
  - Nested installs snapshot the prior handler and restore it on uninstall (LIFO).
  - Not a `re-frame.core` façade export — call it through its home namespace `re-frame.http.test-support`.
- **Example**:
  ```clojure
  ;; Stubs that span several deftests — install once, route via :fx-overrides.
  (re-frame.http.test-support/install-managed-request-stubs!
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
- **Description**: Tear down the most recent install. When installs were nested, it restores the previously installed stub handler (LIFO); otherwise it clears the stub fx and restores real-request routing. Idempotent — a teardown without a matching install is a safe no-op. Not a `re-frame.core` façade export — call it through its home namespace `re-frame.http.test-support`.
- **Example**:
  ```clojure
  (re-frame.http.test-support/uninstall-managed-request-stubs!)
  ```

All test-support surfaces live in `re-frame.http.test-support` — one namespace, same artefact (`day8/re-frame2-http`) as the production code.

```clojure
(deftest cart-loads
  (http-test-support/with-request-stubs
    {[:get "/api/cart"] {:reply {:ok [{:id 1 :name "widget"}]}}}
    (fn []
      (rf/dispatch-sync [:cart/load])
      (is (= 1 (count (subscribe-once [:cart/items])))))))
```

## Privacy and classification

HTTP carries secrets: passwords in request bodies, auth tokens in request headers, PII in response bodies. The framework keeps these off its own observability wire — traces, off-box records, SSR payloads. Four declaration surfaces cooperate, none of them a process-global mutation. See [Managed HTTP — Keeping secrets out of the trace](../async/http-going-further.md#keeping-secrets-out-of-the-trace) and [keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) for the model end-to-end.

| Surface | What it covers | Where declared |
|---|---|---|
| Built-in header denylist | A closed, **immutable** set of always-sensitive header names (`Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-CSRF-Token`, …). Redacted in every `:rf.http/*` trace's `:headers` slot, regardless of any `:sensitive?` flag. Matching is case-insensitive. No frame can remove a name. | framework default |
| Built-in query-param denylist | A closed, **immutable** set of always-sensitive query-param names (`api_key`, `access_token`, `token`, `secret`, `password`, `session`, `signature`, …). The value is redacted inline in `:url` slots (`?api_key=:rf/redacted&page=2`), with name and position preserved. A hit also stamps `:sensitive? true` on the event; the name is the signal. | framework default |
| Managed-HTTP carriers | App-specific sensitive header / query-param names, declared on the `:rf.http/managed` `reg-fx` registration (the transient-payload case). `:headers` is a vector of names — vector-only, because header names always union onto the built-ins. `:query-params` is a vector of names (union) OR an `{:include […] :except […]}` policy map with effective policy `(defaults − except) ∪ include`; `:except` subtracts a built-in query-param default for this app's own dev trace. Names are case-insensitive. A malformed block fails loud. | `reg-fx :rf.http/managed` `:carriers {:headers […] :query-params […]}` |
| Per-request / per-call `:sensitive?` | The coarse opt-in that redacts a single request's body / params / all URL param values wholesale. | the `:rf.http/managed` args map (`:sensitive?` at top level, or under `:request`) |

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
            :sensitive? true}]]}))
```

### `re-frame.http.managed/managed-handler`

- **Kind**: function
- **Signature**:
  ```clojure
  (managed-handler frame-ctx args-map)
  ```
- **Description**: The `:rf.http/managed` fx handler body. It is re-exported so an app can re-register `:rf.http/managed` to declare its `:carriers` block (as in the example above) without knowing the internal handler fn. Do not call it directly from app code — pass it to `reg-fx`.

### Response-body classification — on the `:decode` schema

The denylists and `:sensitive?` flag cover request carriers. The response body is a registration-owned transient payload, classified per-slot via `:sensitive?` / `:large?` props on the request's `:decode` schema. The `:decode` schema is the owner's natural declaration of the body shape, so per-slot props are the one route to classify it. These props fire independently of the per-call `:sensitive?` flag.

```clojure
(rf/reg-event :auth/login
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request {:method :post :url "/auth/login" :body creds}
            ;; [:token] redacts; [:user-id] rides verbatim
            :decode  [:map
                      [:token {:sensitive? true} :string]
                      [:user-id :int]]
            :on-success [:auth/logged-in]}]]}))
```

- **Per-slot.** A `:sensitive?` slot redacts to `:rf/redacted`. A `:large?` slot elides to the size marker. When a slot carries both, sensitive wins. A non-marked sibling rides verbatim. A root-level prop classifies the whole body — e.g. `[:string {:sensitive? true}]` for an opaque-token response.
- **Off-box fail-closed.** Only an introspectable Malli schema (the raw EDN `[op props? …]` vector form) carries per-slot marks the walker can read. An unschematized body has an unknown shape, so it is omitted entirely off-box (**fail-closed**) rather than shipped raw. Unschematized means any of: the keyword decode modes (`:json` / `:text` / …), a custom decoder fn, a registry-keyword ref, or a compiled `m/schema` object.
- **Raw error bodies are unconditionally omitted off-box.** A 4xx/5xx `:body` and a decode-failure `:body-text` are never decoded, because status classification runs before decode. They therefore fail closed off-box irrespective of `:sensitive?`. Error bodies frequently echo request context or tokens.

!!! warning "Classification does not propagate — declare each surface a secret crosses"

    A token in the response body (classified by the `:decode` schema) and the same token stored durably in `app-db` are **two** declarations on two surfaces. There is no propagation that carries one to the other. When `:on-success` writes the token into `app-db`, classify that durable path too: a `reg-event` returning `:sensitive [[:auth :token]]` alongside `:db` ([re-frame.core.md §Standard events](re-frame.core.md) and [keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md)). And never copy a secret into a sibling app-db path you have not classified — e.g. a JWT duplicated at `[:auth :user :token]`. The copy ships raw until *that* path is classified or the duplicate is dropped.

## Trace events emitted by `:rf.http/managed`

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http/retry-attempt` | `:info` | Per intermediate attempt that matched `:retry :on`. Carries `:request-id`, `:url`, `:attempt`, `:max-attempts`, `:failure`, `:next-backoff-ms` (`nil` on the final exhaustion row). |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded. Carries `:frame`, `:id`. |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing slot. Carries `:frame`, `:id`. |
| `:rf.error/http-interceptor-failed` | `:error` | An interceptor `:before` or `:after` threw. Carries `:frame`, `:interceptor-id`, `:url`, `:cause` (plus `:phase :after` on the response side). Request side: the request is NOT dispatched; response side: the reply is suppressed. |

## See also

- [re-frame.core.md](re-frame.core.md) — `:rf.http/managed` rowed in the standard fx table; `:sensitive` on Standard events; and the instrumentation/egress surface (`project-egress`, the wire-boundary walker, the observability-sink registration).
- [re-frame.schemas.md](re-frame.schemas.md) — the `:schema` metadata key and per-slot `:sensitive?` / `:large?` schema props.
- [re-frame.test-support.md](re-frame.test-support.md) — patterns for combining HTTP stubs with `dispatch-sync` and `poll-until`.
- [Keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md) — the full classification + projection model.
- [Managed HTTP reference](../async/http.md) — the guide-level reference; [Interceptors and secrets](../async/http-going-further.md) carries §Keeping secrets out of the trace.
