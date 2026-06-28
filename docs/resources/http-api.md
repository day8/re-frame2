# 07 — HTTP

Managed HTTP is the answer to "I want my app to talk to a server, but I don't want to write a custom fx-handler every time, and I want retries / cancellation / timeouts / decode / failure-classification to be the framework's problem, not mine." `:rf.http/managed` is one fx-id that takes one args map and gives you back one reply event with one closed taxonomy of failure kinds.

You don't get this for free — Spec 014 is an **optional capability**. Implementations ship it (the CLJS reference does, on Fetch in the browser and `java.net.http.HttpClient` on the JVM); ports that omit it must not reuse the `:rf.http/*` namespace for anything else. If you're using the CLJS reference, you have it; if you're vendoring a port that doesn't, you're back to writing your own.

This chapter covers the canonical fx, the verb helpers, the test stubs, the request-interceptor surface, and the closed failure taxonomy. The normative source — args map, decode pipeline, retry semantics, reply addressing — lives at [014-HTTPRequests.md](../../spec/014-HTTPRequests.md).

## The canonical fx

### `[:rf.http/managed args-map]`

- **Kind**: fx
- **Args**: per [014 §The args map](../../spec/014-HTTPRequests.md#the-args-map) and `:rf.fx/managed-args`
- **Description**: The one fx-id. Args carry the request envelope, decode policy, accept fn, retry policy, timeout, success / failure target events, request-id (for abort), and optional abort-signal.
- **In the wild**: [managed_http_counter](https://github.com/day8/re-frame2/tree/main/examples/core/managed_http_counter) · [realworld](https://github.com/day8/re-frame2/tree/main/examples/real-apps/realworld_http)

### `[:rf.http/managed-abort request-id]`

- **Kind**: fx
- **Args**: request-id
- **Description**: Abort the in-flight request with the given `:request-id`. The aborted request's reply fires with `{:rf/reply {:kind :failure :failure {:kind :rf.http/aborted ...}}}`.

### A minimal request

```clojure
(rf/reg-event :cart/load
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request    {:method :get :url "/api/cart"}
            :on-success [:cart/loaded]
            :on-failure [:cart/load-failed]}]]}))

(rf/reg-event :cart/loaded
  (fn [{:keys [db]} [_ {:keys [rf/reply]}]]
    {:db (assoc-in db [:cart :items] (:value reply))}))
```

That's enough to issue a request, decode the JSON reply, and dispatch the result back. Retries, timeouts, schema validation, abort, decode customisation, accept-fn refinement — all of those are optional keys in the args map; you reach for them when the problem asks for them.

## Verb helpers

Call-site helpers for the common shapes. They're pure synthesis fns that produce the canonical `[:rf.http/managed args-map]` fx vector — no magic, no hidden state. The point is ergonomics: writing `(rf.http/get "/api/cart")` reads better at the call site than spelling out the full args map for a no-frills GET.

### `re-frame.http/get`

- **Signature**:
  ```clojure
  (rf.http/get url)
  (rf.http/get url args)
  ```
- **Description**: "Synthesise a GET fx vector." Pure; no side effect — drop the result into `:fx`.

### `re-frame.http/post`

- **Signature**:
  ```clojure
  (rf.http/post url)
  (rf.http/post url args)
  ```
- **Description**: POST. Pass `:body` in `args`.

### `re-frame.http/put`

- **Signature**:
  ```clojure
  (rf.http/put url)
  (rf.http/put url args)
  ```
- **Description**: PUT.

### `re-frame.http/delete`

- **Signature**:
  ```clojure
  (rf.http/delete url)
  (rf.http/delete url args)
  ```
- **Description**: DELETE.

### `re-frame.http/patch`

- **Signature**:
  ```clojure
  (rf.http/patch url)
  (rf.http/patch url args)
  ```
- **Description**: PATCH.

### `re-frame.http/head`

- **Signature**:
  ```clojure
  (rf.http/head url)
  (rf.http/head url args)
  ```
- **Description**: HEAD.

### `re-frame.http/options`

- **Signature**:
  ```clojure
  (rf.http/options url)
  (rf.http/options url args)
  ```
- **Description**: OPTIONS.

The verb helpers live in `re-frame.http` — users `(:require [re-frame.http :as rf.http])` alongside `re-frame.core`. The namespace ships in `day8/re-frame2-http`, the same artefact as the fx itself, so loading the helpers and the fx is a single dep decision.

```clojure
{:fx [(rf.http/get "/api/cart"
        {:on-success [:cart/loaded]
         :on-failure [:cart/load-failed]
         :retry      {:on #{:rf.http/transport :rf.http/timeout}
                      :max-attempts 3
                      :backoff-ms 100}})]}
```

## Reply addressing

Every reply lands under `:rf/reply` in the dispatched event's payload map. Two shapes:

```clojure
;; Success
{:rf/reply {:kind :success :value decoded-body}}

;; Failure
{:rf/reply {:kind    :failure
            :failure {:kind  :rf.http/<category>
                      :tags  {...}}}}
```

**Default reply addressing** dispatches `[<originating-event-id> (assoc original-msg :rf/reply ...)]` back to the same handler — your `:cart/load` handler sees the reply at `:rf/reply`. **Explicit `:on-success` / `:on-failure`** targets append the reply payload as the last event-vector arg — your `:cart/loaded` handler sees `[:cart/loaded {:rf/reply ...}]`. Both shapes detailed in [014 §Reply addressing](../../spec/014-HTTPRequests.md#reply-addressing).

## Failure categories (closed set)

Eight failure `:kind` values, all reserved under `:rf.http/*`. The set is closed — ports that ship Spec 014 deliver exactly these eight categories, and your handler's failure switch can be exhaustive.

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

See [014 §Failure categories](../../spec/014-HTTPRequests.md#failure-categories-closed-set) for tags-by-kind.

## Request-interceptor middleware

Sometimes you want to inject behaviour into every request — adding an auth header, stamping a request ID, logging. Re-frame2's answer is a small middleware surface that mirrors the rest of the `reg-*` family.

### `reg-http-interceptor`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-http-interceptor id interceptor-map)
  ```
- **Description**: Register an HTTP interceptor on a frame's `:rf.http/managed` middleware chain. `interceptor-map` carries at least one of `:before (fn [ctx] ctx')` (request-side) and `:after (fn [ctx response] response')` (response-side), plus optional `:frame` (the explicit-frame *override*) and the standard `:rf/registration-metadata`. The target frame is the explicit `:frame`, else the carried scope it registers under (`with-frame` / an `:initial-events` step); registering under **no** scope raises `:rf.error/no-frame-context` — there is no `:rf/default` default. The `:before` chain runs in registration order; the `:after` chain runs in REVERSE registration order; `:after` sees the SAME ctx the `:before` chain produced (request-correlated handling).
- **In the wild**: [realworld](https://github.com/day8/re-frame2/tree/main/examples/real-apps/realworld_http)

### `clear-http-interceptor`

- **Kind**: function
- **Signature**:
  ```clojure
  (clear-http-interceptor id)
  (clear-http-interceptor frame id)
  ```
- **Description**: Unregister an interceptor by id. The single-arity `(clear-http-interceptor id)` resolves the frame from the carried scope it runs under; the two-arity `(clear-http-interceptor frame id)` names the frame explicitly. Either form raises `:rf.error/no-frame-context` when the frame is absent (single-arity under no scope, or two-arity passed `nil`) — it never clears against a synthesised `:rf/default`.

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

The `:before` runs before the request is dispatched to the platform's HTTP client; the `:after` runs after the response is built and BEFORE `:on-success` / `:on-failure` fire. If either throws, the corresponding side is not delivered (request: not dispatched; response: reply suppressed) and `:rf.error/http-interceptor-failed` fires with `:frame`, `:interceptor-id`, `:url`, `:cause`, and `:phase`. See [014 §Middleware](../../spec/014-HTTPRequests.md#middleware).

## Testing: stubbed responses

Tests want to drive the cascade without hitting the network. The test-support surface provides canned-reply fx and a stubbing macro that reroutes requests at the routes you name.

### `[:rf.http/managed-canned-success {:value v}]`

- **Kind**: fx
- **Description**: Synthesise the canonical success reply directly into `:fx`. Useful for "stub THIS request inline" patterns. Registered at load of `re-frame.http.test-support`.

### `[:rf.http/managed-canned-failure {:kind <:rf.http/*> :tags {...}}]`

- **Kind**: fx
- **Description**: Synthesise the canonical failure reply directly into `:fx`.

### `with-managed-request-stubs`

- **Kind**: macro
- **Signature**:
  ```clojure
  (with-managed-request-stubs route-map body+)
  ```
- **Description**: Lexical-scope stubbing. `route-map` is `{[<method> <url>] {:reply {:ok <value>}}}` (success) or `{[<method> <url>] {:reply {:failure <failure-map>}}}` (failure). Inside the body, requests matching a stubbed route bypass the real client — the helper installs the `:rf.http/managed → :rf.http/managed-test-stub` override for the body, so plain `dispatch-sync` calls auto-route by method + URL with NO manual `:fx-overrides`.

### `with-managed-request-stubs*`

- **Kind**: function
- **Signature**:
  ```clojure
  (with-managed-request-stubs* route-map body-fn)
  ```
- **Description**: Plain-fn surface beneath the macro. Use for computed route-maps or non-literal bodies. Like the macro, it installs the `:rf.http/managed` override for `body-fn`'s dynamic extent, so dispatches inside auto-route with no manual `:fx-overrides`.

### `re-frame.http.test-support/install-managed-request-stubs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (install-managed-request-stubs! route-map)
  ```
- **Description**: Lower-level than `with-managed-request-stubs`: registers the `:rf.http/managed-test-stub` fx that persists until `uninstall-managed-request-stubs!`. Use when stubs span multiple `deftest`s. Unlike the wrapper, this does NOT install the `:rf.http/managed` override — dispatch with `{:fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}` (or wrap dispatches in `with-fx-overrides`) to route through it. **Not a `re-frame.core` façade export** — call it through its home namespace `re-frame.http.test-support`.

### `re-frame.http.test-support/uninstall-managed-request-stubs!`

- **Kind**: function
- **Signature**:
  ```clojure
  (uninstall-managed-request-stubs!)
  ```
- **Description**: Drop installed stubs; restore real-request routing. Idempotent. **Not a `re-frame.core` façade export** — call it through its home namespace `re-frame.http.test-support`.

All the test-support surfaces live in `re-frame.http.test-support`. One namespace; same artefact (`day8/re-frame2-http`) as the production code. The ergonomic `with-managed-request-stubs` macro is re-exported on the `re-frame.core` façade; the raw `install`/`uninstall` pair is reached only through the home namespace.

```clojure
(deftest cart-loads
  (with-managed-request-stubs
    {[:get "/api/cart"] {:reply {:ok [{:id 1 :name "widget"}]}}}
    (rf/dispatch-sync [:cart/load])
    (is (= 1 (count (subscribe-once [:cart/items]))))))
```

## Schema-reflection metadata

Handlers may declare `:rf.http/decode-schemas [<schema> ...]` in their `reg-event` metadata-map; pair tools and generators read it via `(rf/handler-meta :event id)`. Optional, never enforced — pure metadata for tooling. See [014 §Schema reflection](../../spec/014-HTTPRequests.md#schema-reflection-optional-ergonomic).

## Privacy and classification

HTTP is the canonical privacy surface in any app: passwords ride request bodies, auth tokens ride request headers, PII rides response bodies. The framework keeps these off its own observability wire — traces, off-box records, SSR payloads — without you writing a `beforeSend` scrub. Four declaration surfaces cooperate, none of them a process-global mutation. The normative source is [014 §Privacy](../../spec/014-HTTPRequests.md#privacy); the model end-to-end is [keep secrets out of traces](../guide/how-to/keep-secrets-out-of-traces.md).

| Surface | What it covers | Where declared |
|---|---|---|
| **Built-in header denylist** | A closed, **immutable** set of always-sensitive header names (`Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-Auth-Token`, `X-CSRF-Token`, …). Redacted in every `:rf.http/*` trace's `:headers` slot regardless of any `:sensitive?` flag; case-insensitive. No frame can remove a name. | framework default |
| **Built-in query-param denylist** | A closed, **immutable** set of always-sensitive query-param names (`api_key`, `access_token`, `token`, `secret`, `password`, `session`, `signature`, …). The **value** is redacted inline in `:url` slots (`?api_key=:rf/redacted&page=2`); name + position preserved. A hit also stamps `:sensitive? true` on the event — the name is the signal. | framework default |
| **Managed-HTTP carriers** | App-specific sensitive header / query-param names, declared on the **`:rf.http/managed` `reg-fx` registration** (the transient-payload case); they **union** onto the built-in defaults. | `reg-fx :rf.http/managed` `:carriers {:headers […] :query-params […]}` |
| **Per-request / per-call `:sensitive?`** | The coarse opt-in that redacts a single request's body / params / all URL param values wholesale. | the `:rf.http/managed` args map (`:sensitive?` at top level, or under `:request`) |

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

### Response-body classification — on the `:decode` schema

The denylists and `:sensitive?` flag cover request carriers; the **response body** is a registration-owned transient payload, classified **per-slot via `:sensitive?` / `:large?` props on the request's `:decode` schema**. The `:decode` schema is the owner's natural declaration of the body shape, so per-slot props are the *one* route — there is no second route to classify it. These props fire **independently of** the per-call `:sensitive?` flag.

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

- **Per-slot.** A `:sensitive?` slot redacts to `:rf/redacted`; a `:large?` slot elides to the size marker; both → sensitive wins. A non-marked sibling rides verbatim. A root-level prop classifies the whole body (e.g. `[:string {:sensitive? true}]` for an opaque-token response).
- **Off-box fail-closed.** Only an **introspectable** Malli schema (the raw EDN `[op props? …]` vector form) carries per-slot marks the walker can read. An **unschematized** body — the keyword decode modes (`:json` / `:text` / …), a custom decoder fn, a registry-keyword ref, or a compiled `m/schema` object — has an unknown shape, so it is **omitted entirely off-box** (fail-closed) rather than shipped raw.
- **Raw error bodies are unconditionally omitted off-box.** A 4xx/5xx `:body` and a decode-failure `:body-text` are never decoded (status classification runs before decode), so they fail closed off-box irrespective of `:sensitive?` — error bodies frequently echo request context or tokens.

!!! warning "Classification does not propagate — declare each surface a secret crosses"

    A token in the response body (classified by the `:decode` schema) and the same token stored durably in `app-db` are **two** declarations on two surfaces. There is no propagation that carries one to the other. When `:on-success` writes the token into `app-db`, classify that durable path too — a `reg-event` returning `:sensitive [[:auth :token]]` alongside `:db` ([01 — Core §Standard events](../guide/api/01-core.md) and [keep secrets out of traces](../guide/how-to/keep-secrets-out-of-traces.md)). And never copy a secret into a sibling app-db path you have not classified (e.g. a JWT duplicated at `[:auth :user :token]`): the copy ships raw until *that* path is classified or the duplicate is dropped.

## Trace events emitted by `:rf.http/managed`

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http/retry-attempt` | `:info` | Per intermediate attempt that matched `:retry :on`. Carries `:attempt`, `:max-attempts`, `:failure`, `:next-backoff-ms`. |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded. Carries `:frame`, `:id`. |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing slot. |
| `:rf.error/http-interceptor-failed` | `:error` | A request-interceptor `:before` threw. Carries `:frame`, `:interceptor-id`, `:url`, `:cause`. The request is NOT dispatched. |

## See also

- [03 — Effects and interceptors](../guide/api/03-effects.md) — `:rf.http/managed` rowed in the standard fx table.
- [08 — Schemas](../guide/api/08-schemas.md) — `:rf.http/decode-schemas`, the `:schema` metadata key, and per-slot `:sensitive?` / `:large?` schema props.
- [10 — Testing](../guide/api/10-testing.md) — patterns for combining HTTP stubs with `dispatch-sequence`.
- [11 — Instrumentation](../guide/api/11-instrumentation.md) — `project-egress`, the wire-boundary walker, and the observability-sink surface.
- [Keep secrets out of traces](../guide/how-to/keep-secrets-out-of-traces.md) — the full classification + projection model.
- [Spec 014 — HTTP Requests](../../spec/014-HTTPRequests.md) — the normative source (incl. §Privacy).
