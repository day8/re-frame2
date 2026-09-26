# Interceptors and secrets

Two jobs come up once a [managed HTTP](http.md) app is in production: stamping every
request with the same header or telemetry, and keeping tokens, passwords and PII out
of the trace.

## Interceptors: stamp every request once

Threading `"Authorization"` into every call site is a cross-cutting concern. re-frame2
ships a **per-frame HTTP interceptor chain** — the same `{:before :after}` onion as
[event interceptors](../core/interceptors.md), wrapping the *transport*. `:before`
transforms the request on the way out; `:after` transforms the reply on the way back.

`rf/reg-http-interceptor` takes a positional id and an interceptor map. Here's Bearer auth as a single registration — note it reads the token *fresh on every request*, so rotation is picked up with zero re-registration:

```clojure
(rf/reg-http-interceptor
  :app/bearer-auth
  {:doc    "Stamp Bearer <token> on every outgoing request."
   :frame  :app                                   ;; the frame whose requests it stamps
   :before (fn [ctx]
             (let [token (-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Bearer " token)))))})

;; Now no handler threads auth — the header is added on the way out:
(rf/reg-event :articles/list
  (fn [_ _]
    {:fx [[:rf.http/managed
           {:request    {:method :get :url "/articles"}
            :decode     ArticleListResponse
            :on-success [:articles/loaded]
            :on-failure [:articles/load-failed]}]]}))
```

The two phases:

- The `:before` fn receives a ctx of `{:request :args :frame :event :sensitive?}` and returns a ctx whose `:request` is the modified request map. `:sensitive?` says whether the args marked the request sensitive; to mark it from here, set `[:request :sensitive?]` to `true`. (Above, `cond-> ctx` adds the header only when a token is present, leaving the ctx untouched otherwise.) It reads app state through `rf/app-db-value` — an accessor that hands you the frame's current [app-db](../core/glossary.md#app-db) value, never a live subscription.
- The `:after` fn is `(fn [ctx response] response')`. It sees the *same* ctx its `:before` produced — so a `:before` that stamps a start-time lets the matching `:after` compute an elapsed delta with no app state — plus the canonical `{:status :ok …}` / `{:status :error …}` response, and it returns a possibly-transformed response that the `:on-success` / `:on-failure` dispatch then carries. On success the response also carries the wire facts under `:meta` ([Handling the reply](http.md#handling-the-reply)), so header-driven concerns need no side channel.

That ctx-carried-forward shape, plus the `:meta` wire facts, is what makes per-request concerns — response-time telemetry, rate-limit header parsing, flagging a 401 for an auth refresh — single-interceptor jobs. Here is rate-limit parsing as a working registration — one parse per response, and every `:on-success` handler downstream reads the structured slot instead of a header string:

```clojure
(rf/reg-http-interceptor
  :app/rate-limit
  {:doc   "Parse X-RateLimit-* once; downstream handlers read :rate-limit."
   :frame :app
   :after (fn [_ctx response]
            (let [remaining (some-> (get-in response [:meta :headers "x-ratelimit-remaining"])
                                    parse-long)]
              (cond-> response
                remaining (assoc :rate-limit {:remaining remaining}))))})
```

The rules that matter:

- **Chains are per-frame.** An interceptor registered on one [frame](../core/frames.md) never fires for a request from another. Multi-frame apps register independent chains. So registration must name its frame: pass `:frame`, as above, or register inside a frame scope such as `rf/with-frame`. With neither, a top-level registration raises `:rf.error/no-frame-context` and installs nothing. [Add authentication](../core/how-to/add-auth.md#3-decorate-requests-once-at-the-frame-boundary) wires a full auth flow this way, including a 401 that logs the user out.
- **Onion order.** `:before`s run in registration order, `:after`s in reverse — A-registered-before-B means `A.before → B.before → transport → B.after → A.after`. Exactly the event-interceptor mental model.
- **At least one phase is required.** A map with neither `:before` nor `:after` is rejected at registration with `:rf.error/http-bad-interceptor`, as is a phase that isn't a fn, or an id or `:frame` that isn't a keyword. A `:before`-only or `:after`-only interceptor is fine and composes cleanly.
- **Each phase returns a map.** A `:before` returns the request ctx; an `:after` returns the reply map. Returning `nil`, a vector, or anything else fails the interceptor with `:rf.error/http-interceptor-bad-return`, carrying the interceptor's `:id` and what it `:returned`; the effect is the same as a throw (next rule). So a bad interceptor cannot erase the request or reply.
- **A throw is named, not swallowed.** A `:before` or `:after` that throws classifies as `:rf.error/http-interceptor-failed` (carrying the offending `:interceptor-id`); a request-side throw means the transport never sees the request. Wrap recoverable logic inside the interceptor yourself — the chain has no recovery cofx.
- **Clearing.** Inside a frame scope, `(rf/clear :http-interceptor id)` removes that frame's interceptor; there is no `rf/clear-http-interceptor`. Outside a frame scope, or when you want to name the frame directly, use the opts form `(rf/clear :http-interceptor id {:frame frame-id})` — the trailing `{:frame …}` opts map, mirroring `reg-http-interceptor`'s `:frame`. Calling the single-arity form with no frame in scope fails loud with `:rf.error/no-frame-context`. Re-registering an existing id replaces it *in place* (hot-reload-friendly); clear-then-reg appends a fresh slot at the end.

`reg-http-interceptor` is the one HTTP registration on the `rf/` facade; everything else on `re-frame.http.managed` is addressed by keyword. Resources and mutations get *their* request decoration here too: register the auth interceptor once and every `:rf.http/managed` request, whether you issued it directly or a [resource](../resources/concepts.md) did, carries the header.

## Keeping secrets out of the trace

HTTP is where the secrets are: passwords ride request bodies, auth tokens ride request headers, user PII rides response bodies. And every step of a managed request can land on the dev [trace stream](../core/glossary.md#trace-stream) — the retry attempt, the failure category, the swallowed-failure warning — so without care the transport becomes the app's biggest leak. Managed HTTP applies [data classification](../core/glossary.md#data-classification) at that egress boundary so the real value renders on-box but a redaction sentinel is what crosses into a trace, Xray, or an off-box log.

**Sensitive headers are redacted always — no flag required.** A closed, framework-owned denylist of header *names* — `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`, `X-API-Key`, `X-CSRF-Token`, and a handful more — is redacted to `:rf/redacted` in every `:rf.http/*` trace event, whether or not the request is marked sensitive. The name *is* the signal: a leaked `Authorization` header is a leak even from a handler nobody thought to flag. Matching is case-insensitive, and the built-in set is immutable — no frame can remove a name. The same is true on the URL side: a denylisted query-string parameter (`?api_key=…`, `?access_token=…`, `?token=…`, `?signature=…`) has its **value** scrubbed inline (`?api_key=:rf/redacted&page=2`), name and position preserved so you can still see which endpoint was hit, and that hit alone stamps the trace `:sensitive?`.

**For app-specific carriers, declare them on the fx registration.** Got an `X-Honeycomb-Team` or a `shop_token` that the built-in lists don't know about? Don't reach for a per-call flag and don't mutate a global — name them once on the `:rf.http/managed` registration, and they union onto the immutable defaults:

```clojure
(rf/reg-fx :rf.http/managed
  {:carriers {:headers      ["X-Honeycomb-Team"]
              :query-params ["shop_token"]}}
  re-frame.http.managed/managed-handler)
```

Names match case-insensitively. A malformed block fails loud with `:rf.error/bad-classification`.

**For a whole request, set `:sensitive?`.** When an *entire* request is sensitive — a login POST whose body is the password — flag it and the framework redacts the body, the `:params`, and *every* URL query value (not just the denylisted ones) on the way to the trace. The flag lives either under `:request` or at the top level of the args map; the two are equivalent, and either being true wins:

```clojure
(rf/reg-event :auth/login
  (fn [_ [_ creds]]
    {:fx [[:rf.http/managed
           {:request    {:method :post :url "/auth/login" :body creds}
            :sensitive? true                ;; body + params + all URL values redacted in traces
            :on-success [:auth/logged-in]
            :on-failure [:auth/login-failed]}]]}))
```

**For response bodies, mark the slots on your `:decode` schema.** The response is classified per-slot through the schema you already hand `:decode` — the owner's natural declaration of the body's shape doubles as its sensitivity map. Mark `{:sensitive? true}` on a slot and that field is redacted in the trace; mark `{:large? true}` and it's elided to a size marker; an unmarked sibling rides in the clear:

```clojure
;; [:token] is redacted in traces; [:user-id] rides verbatim.
:decode [:map
         [:token {:sensitive? true} :string]
         [:user-id :int]]
```

This is the schema's job whether or not the request also carries the coarse `:sensitive?` flag, which redacts the whole body where the marks pick out single fields. The marks are read by the schema walker in `day8/re-frame2-schemas`, so require `re-frame.schemas`: without it, a request whose `:decode` schema declares a mark is refused at dispatch with `:rf.error/schemas-artefact-missing` and never sent. All of this rides the dev trace surface, so it [elides](../core/glossary.md#elide) wholesale in production along with the rest of tracing — the redaction step costs nothing in a release build. When trace data is exported outside the app, re-frame2 keeps secrets out by default: sensitive slots are denied, unknown error bodies stay local, and export policy can exclude specific places where secrets may appear. For the framework-wide story, see [keep secrets out of traces](../core/how-to/keep-secrets-out-of-traces.md).

!!! warning "Gotcha — a 4xx/5xx error body is always omitted off-box"

    A non-2xx response surfaces its raw body at `:body`, and that body never ran through your `:decode` schema (status is classified *before* decode), so its shape is unknown. Off-box egress therefore drops it unconditionally — error responses routinely echo back request context or tokens. On your local dev trace you still see it; it's the off-box boundary that fails closed. If you need fields out of an error body, decode `:body` yourself in the failure branch where the value stays on-box.
