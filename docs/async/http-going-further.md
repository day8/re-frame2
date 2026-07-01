# HTTP: going further

[The basics](http.md) cover everyday work — issue a request, handle the reply, classify failures, retry, win the search-box race. This page is the rest: trimming keys at the call site, the cross-cutting interceptor seam, keeping secrets off the trace, and the reply shape shared by the async surfaces. Reach for these when you need them; none is required to be productive.

## Fewer keys at the call site: the verb helpers

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

Two notes. `rf.http/get` shadows `clojure.core/get`, so the namespace is meant to be *aliased* (`:as rf.http`) and called qualified — `(rf.http/get …)` reads as "an HTTP GET", which is the whole point. And these helpers live in `re-frame.http`, **not** on the `rf/` facade: you require them explicitly. They ship in the same `day8/re-frame2-http` artefact as the fx they expand to, so loading the helpers and registering `:rf.http/managed` are one dep decision.

## Interceptors: stamp every request once

Threading `"Authorization"` into every call site is the kind of cross-cutting concern that belongs in one place. re-frame2 ships a **per-frame HTTP interceptor chain** for exactly this — the same `{:before :after}` onion you know from [event interceptors](../core/concepts/interceptors.md), but wrapping the transport instead of the event handler. A `:before` transforms the request on its way out; an `:after` transforms the reply on its way back.

`rf/reg-http-interceptor` takes a positional id and an interceptor map. Here's Bearer auth as a single registration — note it reads the token *fresh on every request*, so rotation is picked up with zero re-registration:

```clojure
(rf/reg-http-interceptor
  :app/bearer-auth
  {:doc    "Stamp Bearer <token> on every outgoing request."
   :before (fn [ctx]
             (let [token (-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Bearer " token)))))})

;; Now no handler threads auth — the header is added on the way out:
(rf/reg-event :articles/list
  (fn [_ _]
    {:fx [(rf.http/get "/articles" {:decode ArticleListResponse})]}))
```

Walk the two phases:

- The `:before` fn receives a ctx of `{:request :args :frame :event}` and returns a ctx whose `:request` is the modified envelope. (Above, `cond-> ctx` adds the header only when a token is present, leaving the ctx untouched otherwise.) It reads app state through `rf/app-db-value` — an accessor that hands you the frame's current [app-db](../core/glossary.md#app-db) value, never a live subscription.
- The `:after` fn is `(fn [ctx response] response')`. It sees the *same* ctx its `:before` produced — so a `:before` that stamps a start-time lets the matching `:after` compute an elapsed delta with no app state — plus the `{:kind :success …}` / `{:kind :failure …}` response, and it returns a possibly-transformed response that the `:on-success` / `:on-failure` dispatch then carries.

That ctx-carried-forward shape is what makes per-request concerns — response-time telemetry, rate-limit header parsing, flagging a 401 for an auth refresh — single-interceptor jobs.

A few rules that matter:

- **Chains are per-frame.** An interceptor registered on one [frame](../core/concepts/frames.md) never fires for a request from another. Multi-frame apps register independent chains.
- **Onion order.** `:before`s run in registration order, `:after`s in reverse — A-registered-before-B means `A.before → B.before → transport → B.after → A.after`. Exactly the event-interceptor mental model.
- **At least one phase is required.** A map with neither `:before` nor `:after` is rejected at registration with a named bad-interceptor error. A `:before`-only or `:after`-only interceptor is fine and composes cleanly.
- **A throw is named, not swallowed.** A `:before` or `:after` that throws classifies as a named interceptor-failed error (carrying the offending `:interceptor-id`); a request-side throw means the transport never sees the request. Wrap recoverable logic inside the interceptor yourself — the chain has no recovery cofx.
- **Clearing.** Inside a frame scope, `(rf/clear-http-interceptor id)` removes that frame's interceptor. Outside a frame scope, or when you want to name the frame directly, use `(rf/clear-http-interceptor frame-id id)`. Calling the single-arity form with no frame in scope fails loud with `:rf.error/no-frame-context`. Re-registering an existing id replaces it *in place* (hot-reload-friendly); clear-then-reg appends a fresh slot at the end.

`reg-http-interceptor` and `clear-http-interceptor` are the only two HTTP surfaces re-exported onto the `rf/` facade (everything else lives in `re-frame.http` / `re-frame.http.managed`). This same seam is where resources and mutations get *their* request decoration too: register the auth interceptor once and every `:rf.http/managed` request, whether you issued it directly or a [resource](../resources/concepts.md) did, carries the header.

## Keeping secrets out of the trace

HTTP is where the secrets are: passwords ride request bodies, auth tokens ride request headers, user PII rides response bodies. And every step of a managed request can land on the dev [trace stream](../core/glossary.md#trace-stream) — the retry attempt, the failure category, the swallowed-failure warning — so without care the transport becomes the app's biggest leak. Managed HTTP applies [data classification](../core/glossary.md#data-classification) at that egress boundary so the real value renders on-box but a redaction sentinel is what crosses into a trace, Xray, or an off-box log. Three layers cooperate, and two of them need no opt-in.

**Sensitive headers are redacted always — no flag required.** A closed, framework-owned denylist of header *names* — `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-CSRF-Token`, and a handful more — is redacted to `:rf/redacted` in every `:rf.http/*` trace event, whether or not the request is marked sensitive. The name *is* the signal: a leaked `Authorization` header is a leak even from a handler nobody thought to flag. Matching is case-insensitive, and the built-in set is immutable — no frame can remove a name. The same is true on the URL side: a denylisted query-string parameter (`?api_key=…`, `?access_token=…`, `?token=…`, `?signature=…`) has its **value** scrubbed inline (`?api_key=:rf/redacted&page=2`), name and position preserved so you can still see which endpoint was hit, and that hit alone stamps the trace `:sensitive?`.

**For app-specific carriers, declare them on the fx registration.** Got an `X-Honeycomb-Team` or a `shop_token` that the built-in lists don't know about? Don't reach for a per-call flag and don't mutate a global — name them once on the `:rf.http/managed` registration, and they union onto the immutable defaults:

```clojure
(rf/reg-fx :rf.http/managed
  {:carriers {:headers      ["X-Honeycomb-Team"]
              :query-params ["shop_token"]}}
  re-frame.http.managed/managed-handler)
```

**For a whole request, set `:sensitive?`.** When an *entire* request is sensitive — a login POST whose body is the password — flag it and the framework redacts the body, the `:params`, and *every* URL query value (not just the denylisted ones) on the way to the trace. The flag lives either under `:request` or at the top level of the args map; the two are equivalent, and either being true wins:

```clojure
(rf/reg-event :auth/login
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request    {:method :post :url "/auth/login" :body creds}
            :sensitive? true}]]}))      ;; body + params + all URL values redacted in traces
```

**For response bodies, mark the slots on your `:decode` schema.** The response is classified per-slot through the schema you already hand `:decode` — the owner's natural declaration of the body's shape doubles as its sensitivity map. Mark `{:sensitive? true}` on a slot and that field is redacted in the trace; mark `{:large? true}` and it's elided to a size marker; an unmarked sibling rides in the clear:

```clojure
;; [:token] is redacted in traces; [:user-id] rides verbatim.
:decode [:map
         [:token {:sensitive? true} :string]
         [:user-id :int]]
```

This is the schema's job whether or not the request also carries the coarse `:sensitive?` flag (the flag is the whole-body hammer; the schema marks are the scalpel). All of this rides the dev trace surface, so it [elides](../core/glossary.md#elide) wholesale in production along with the rest of tracing — the redaction step costs nothing in a release build. When trace data is exported outside the app, re-frame2 keeps secrets out by default: sensitive slots are denied, unknown error bodies stay local, and export policy can exclude specific places where secrets may appear. For the framework-wide story, see [keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).

> **Gotcha — a 4xx/5xx error body is always omitted off-box.** A non-2xx response surfaces its raw body at `:body`, and that body never ran through your `:decode` schema (status is classified *before* decode), so its shape is unknown. Off-box egress therefore drops it unconditionally — error responses routinely echo back request context or tokens. On your local dev trace you still see it; it's the off-box boundary that fails closed. If you need fields out of an error body, decode `:body` yourself in the failure branch where the value stays on-box.

## One envelope under every async surface

Everything on the [HTTP basics](http.md) page is HTTP-specific spelling over a framework-wide reply shape. You don't need this section to be productive — but it's the seam that makes resources, machines, and routing feel the same, so it's worth one read.

"A reply is an event" isn't just an HTTP convenience. It's [**the uniform reply**](../core/glossary.md#the-uniform-reply), and every managed async surface completes through it: HTTP, [resources and mutations](../resources/concepts.md), [state-machine async work](../machines/concepts.md), and [route loaders](../routing/concepts.md). Those pages lean on this section instead of re-teaching it.

The envelope has two pieces. A **reply target** says where completion is dispatched. A **reply map** says what it carries. When the work completes, the runtime dispatches the target event with the reply map appended as the final argument:

```clojure
[:article/load-replied
 {:id 42}                                        ;; your carried context
 {:status       :ok                              ;; the reply map
  :value        {:title "Welcome"}
  :work/id      [:rf.work/http :article/by-id 42 1]
  :completed-at 1781078400456}]
```

**The status set is closed** — five outcomes, never quietly a sixth:

| `:status` | Meaning |
|---|---|
| `:ok` | Completed successfully; reply is current. `:value` present. |
| `:partial` | Completed with usable data *and* structured problems (the motivating case is GraphQL, which returns both in one response). Plain HTTP never emits `:partial`. |
| `:error` | Completed with a failure; reply is current. `:error` carries a family `:kind` — for HTTP, one of the [`:rf.http/*` categories](http.md#failures-are-a-closed-set). |
| `:cancelled` | Intentionally cancelled while still correlated with the target. `:cancel/reason` present. |
| `:stale` | Completed *after* its correlation became obsolete. The app target is never dispatched; **no app-state mutation happens**. |

The `{:kind :success …}` / `{:kind :failure …}` payloads your `:on-success` / `:on-failure` handlers receive are this same envelope in HTTP's clothing: `:kind :success` is `:status :ok`, and `:kind :failure` is `:status :error` with the failure under `:error`. One contract; HTTP just hands your handlers the shorter spelling. (Timeout is not its own status, either — it's `:status :error` with `:kind :rf.http/timeout`. One fact, named once.)

Four rules finish the tour:

- **Stale suppression is the correctness boundary.** A newer request supersedes an older one — that's the search-box race. The old completion is classified `:stale`, the app target is skipped, and the trace records the carried-versus-current correlation. Your handler never sees a stale answer, so it can never overwrite fresh data with old. Cancellation is only an optimization here; *suppression* is what actually keeps state correct.
- **Cancellation is data, not the absence of a reply.** A live user-cancel dispatches `:status :cancelled` with a `:cancel/reason`. A supersession suppresses as `:stale`. Either way there's a value describing what happened — never a silently dropped continuation.
- **Completion timestamps ride the reply.** The reply carries facts about the work, including when it completed. HTTP exposes that time through the [`:rf/time-ms` declaration](http.md#reading-the-reply-correctly); full reply maps carry it as `:completed-at`. Either way, handlers use the carried value rather than reading the clock again.
- **HTTP's `:on-success` / `:on-failure` / `:rf/reply` are public sugar over this envelope.** They're what you write on HTTP — `:rf.http/managed` does not accept a bare `:rf/reply-to`. But the general async model, shared by resources, mutations, machines, and routing, is the envelope. Each surface picks its own public spelling; the substrate underneath is one.

> **Going deeper.** Effects *sequence but never bind*: a handler can ask for several effects in order (the `:fx` vector), but never "do this effect, *then* feed its result into the next expression" — that would be monadic binding, the awaited-value shape, and it's exactly what re-frame2 refuses. The result comes back as the next event instead, and relocating a reply target is a pure data transform (the role `Cmd.map` plays in Elm's command algebra) — never a hidden callback.

