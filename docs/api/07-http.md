# 07 — HTTP

Managed HTTP is the answer to "I want my app to talk to a server, but I don't want to write a custom fx-handler every time, and I want retries / cancellation / timeouts / decode / failure-classification to be the framework's problem, not mine." `:rf.http/managed` is one fx-id that takes one args map and gives you back one reply event with one closed taxonomy of failure kinds.

You don't get this for free — Spec 014 is an **optional capability**. Implementations ship it (the CLJS reference does, on Fetch in the browser and `java.net.http.HttpClient` on the JVM); ports that omit it must not reuse the `:rf.http/*` namespace for anything else. If you're using the CLJS reference, you have it; if you're vendoring a port that doesn't, you're back to writing your own.

This chapter covers the canonical fx, the verb helpers, the test stubs, the request-interceptor surface, and the closed failure taxonomy. The normative source — args map, decode pipeline, retry semantics, reply addressing — lives at [014-HTTPRequests.md](../../spec/014-HTTPRequests.md).

## The canonical fx

| API | Kind | Signature / shape | Status | Intuition |
|---|---|---|---|---|
| `[:rf.http/managed args-map]` | fx | args per [014 §The args map](../../spec/014-HTTPRequests.md#the-args-map) and `:rf.fx/managed-args` | v1 (optional capability) | The one fx-id. Args carry the request envelope, decode policy, accept fn, retry policy, timeout, success / failure target events, request-id (for abort), and optional abort-signal. |
| `[:rf.http/managed-abort request-id]` | fx | request-id | v1 (optional capability) | Abort the in-flight request with the given `:request-id`. The aborted request's reply fires with `{:rf/reply {:kind :failure :failure {:kind :rf.http/aborted ...}}}`. |

### A minimal request

```clojure
(rf/reg-event-fx :cart/load
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request    {:method :get :url "/api/cart"}
            :on-success [:cart/loaded]
            :on-failure [:cart/load-failed]}]]}))

(rf/reg-event-db :cart/loaded
  (fn [db [_ {:keys [rf/reply]}]]
    (assoc-in db [:cart :items] (:value reply))))
```

That's enough to issue a request, decode the JSON reply, and dispatch the result back. Retries, timeouts, schema validation, abort, decode customisation, accept-fn refinement — all of those are optional keys in the args map; you reach for them when the problem asks for them.

## Verb helpers

Call-site helpers for the common shapes. They're pure synthesis fns that produce the canonical `[:rf.http/managed args-map]` fx vector — no magic, no hidden state. The point is ergonomics: writing `(rf.http/get "/api/cart")` reads better at the call site than spelling out the full args map for a no-frills GET.

| API | Signature | Status | Intuition |
|---|---|---|---|
| `re-frame.http/get` | `(rf.http/get url)` / `(rf.http/get url args)` | v1 (optional) | "Synthesise a GET fx vector." Pure; no side effect — drop the result into `:fx`. |
| `re-frame.http/post` | `(rf.http/post url)` / `(rf.http/post url args)` | v1 (optional) | POST. Pass `:body` in `args`. |
| `re-frame.http/put` | `(rf.http/put url)` / `(rf.http/put url args)` | v1 (optional) | PUT. |
| `re-frame.http/delete` | `(rf.http/delete url)` / `(rf.http/delete url args)` | v1 (optional) | DELETE. |
| `re-frame.http/patch` | `(rf.http/patch url)` / `(rf.http/patch url args)` | v1 (optional) | PATCH. |
| `re-frame.http/head` | `(rf.http/head url)` / `(rf.http/head url args)` | v1 (optional) | HEAD. |
| `re-frame.http/options` | `(rf.http/options url)` / `(rf.http/options url args)` | v1 (optional) | OPTIONS. |

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

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `reg-http-interceptor` | Fn | `(reg-http-interceptor id before)` <br> `(reg-http-interceptor id opts before)` | v1 (optional) | Register a request-side interceptor on a frame's `:rf.http/managed` middleware chain. `before` is `(fn [ctx] ctx')` where ctx is `{:request :args :frame :event}`. `opts` carries `:frame` (default `:rf/default`) plus the standard `:rf/registration-metadata`. |
| `clear-http-interceptor` | Fn | `(clear-http-interceptor id)` <br> `(clear-http-interceptor frame id)` | v1 (optional) | Unregister an interceptor by id. Single-arity targets `:rf/default`. |

```clojure
(rf/reg-http-interceptor :auth/inject
  (fn [{:keys [request] :as ctx}]
    (assoc-in ctx [:request :headers "Authorization"]
              (str "Bearer " (token-from-app-db)))))
```

The interceptor runs *before* the request is dispatched to the platform's HTTP client. If a `:before` throws, the request is **not** dispatched; `:rf.error/http-interceptor-failed` fires with `:frame`, `:interceptor-id`, `:url`, and `:cause`. See [014 §Middleware](../../spec/014-HTTPRequests.md#middleware).

## Testing: stubbed responses

Tests want to drive the cascade without hitting the network. The test-support surface provides canned-reply fx and a stubbing macro that reroutes requests at the routes you name.

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `[:rf.http/managed-canned-success {:value v}]` | fx | — | v1 (optional, dev/test) | Synthesise the canonical success reply directly into `:fx`. Useful for "stub THIS request inline" patterns. Registered at load of `re-frame.http-test-support`. |
| `[:rf.http/managed-canned-failure {:kind <:rf.http/*> :tags {...}}]` | fx | — | v1 (optional, dev/test) | Synthesise the canonical failure reply directly into `:fx`. |
| `with-managed-request-stubs` | M | `(with-managed-request-stubs route-map body+)` | v1 (optional, dev/test) | Lexical-scope stubbing. `route-map` is `{[<method> <url>] {:reply <value-or-failure>}}`. Inside the body, requests matching a stubbed route bypass the real client. |
| `with-managed-request-stubs*` | Fn | `(with-managed-request-stubs* route-map body-fn)` | v1 (optional, dev/test) | Plain-fn surface beneath the macro. Use for computed route-maps or non-literal bodies. |
| `install-managed-request-stubs!` | Fn | `(install-managed-request-stubs! route-map)` | v1 (optional, dev/test) | Lower-level than `with-managed-request-stubs`: install stubs that persist until `uninstall-managed-request-stubs!`. Use when stubs span multiple `deftest`s. |
| `uninstall-managed-request-stubs!` | Fn | `(uninstall-managed-request-stubs!)` | v1 (optional, dev/test) | Drop installed stubs; restore real-request routing. Idempotent. |

All the test-support surfaces live in `re-frame.http-test-support` (the single home per audit of audits #15). One namespace; same artefact (`day8/re-frame2-http`) as the production code.

```clojure
(deftest cart-loads
  (with-managed-request-stubs
    {[:get "/api/cart"] {:reply [{:id 1 :name "widget"}]}}
    (rf/dispatch-sync [:cart/load])
    (is (= 1 (count (subscribe-once [:cart/items]))))))
```

## Schema-reflection metadata

Handlers may declare `:rf.http/decode-schemas [<schema> ...]` in their `reg-event-fx` metadata-map; pair tools and generators read it via `(rf/handler-meta :event id)`. Optional, never enforced — pure metadata for tooling. See [014 §Schema reflection](../../spec/014-HTTPRequests.md#schema-reflection-optional-ergonomic).

## Trace events emitted by `:rf.http/managed`

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http/retry-attempt` | `:info` | Per intermediate attempt that matched `:retry :on`. Carries `:attempt`, `:max-attempts`, `:failure`, `:next-backoff-ms`. |
| `:rf.warning/decode-defaulted` | `:warning` | The request relied on `:decode :auto` (the default). Informational; not an error. |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded. Carries `:frame`, `:id`. |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing slot. |
| `:rf.error/http-interceptor-failed` | `:error` | A request-interceptor `:before` threw. Carries `:frame`, `:interceptor-id`, `:url`, `:cause`. The request is NOT dispatched. |

## See also

- [03 — Effects and interceptors](03-effects.md) — `:rf.http/managed` rowed in the standard fx table.
- [08 — Schemas](08-schemas.md) — `:rf.http/decode-schemas` and the `:schema` metadata key.
- [10 — Testing](10-testing.md) — patterns for combining HTTP stubs with `dispatch-sequence`.
- [Spec 014 — HTTP Requests](../../spec/014-HTTPRequests.md) — the normative source.
