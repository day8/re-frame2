# HTTP interceptors

Add authentication headers to every article request with one registration.
An HTTP interceptor can change outgoing requests in `:before` and incoming
replies in `:after`, for every frame that names it.

## Interceptors: stamp every request once

`rf/reg-http-interceptor` takes an id and an interceptor map. Read the token
in `:before` so each new request uses the current token:

```clojure
(ns app.article-interceptors
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]))

(rf/reg-http-interceptor
  :app/bearer-auth
  {:doc    "Stamp Bearer <token> on every outgoing request."
   :before (fn [ctx]
             (let [token (-> (rf/app-db-value (:frame ctx)) :auth :token)]
               (cond-> ctx
                 token (assoc-in [:request :headers "Authorization"]
                                 (str "Bearer " token)))))})

;; Now no handler threads auth — the header is added on the way out:
(rf/reg-event :article/load
  (fn [_ [_ slug]]
    {:fx [[:rf.http/managed
           {:request    {:url (str "/api/articles/" slug)}
            :decode     :json
            :on-success [:article/loaded]
            :on-failure [:article/load-error]}]]}))
```

The registration belongs to no frame. A frame turns it on by naming its id in
`:http-interceptors`; here that is `:app/articles`, the same frame used by the
tutorial:

```clojure
[rf/frame-root {:id :app/articles :http-interceptors [:app/bearer-auth]}
 [article-view]]
```

It also applies to HTTP requests issued by resources and mutations in that frame.

<a id="keeping-secrets-out-of-the-trace"></a>

`Authorization` is redacted from HTTP traces automatically. Request and response
bodies need their own declarations; [HTTP trace redaction](http-tracing.md) shows
where to put them.

## Transforming requests and replies

`:before` receives a context containing `:request`, `:args`, `:frame`,
`:event` and `:sensitive?`. Return that context with the modified request.
Read current app state through `rf/app-db-value`, as the auth example does.
To mark a request sensitive from an interceptor, set `[:request :sensitive?]`
to `true` in the context.

`:after` receives the final request context and a reply map. Return the reply,
with any transformations. On success, `:meta` includes the HTTP status and
lower-cased header names. For example, parse the rate-limit header once so
article handlers can read a structured value:

```clojure
(rf/reg-http-interceptor
  :app/rate-limit
  {:doc   "Parse X-RateLimit-* once; downstream handlers read :rate-limit."
   :after (fn [_ctx response]
            (let [remaining (some-> (get-in response [:meta :headers "x-ratelimit-remaining"])
                                    parse-long)]
              (cond-> response
                remaining (assoc :rate-limit {:remaining remaining}))))})

;; and the frame names both:
;; {:id :app/articles :http-interceptors [:app/bearer-auth :app/rate-limit]}
```

## Registration and lifetime

- An interceptor applies only to requests from a [frame](../core/frames.md)
  that names it in `:http-interceptors`. Register it at the top level, with no
  `:frame`; frames that need different behaviour name different ids.
- `:before` runs in the order of the frame's `:http-interceptors` vector;
  `:after` runs in reverse, as with [event interceptors](../core/interceptors.md).
  Load order sets nothing.
- The chain is captured at request start. `:before` runs once, and retries reuse
  that request, including its token. `:after` runs once on the final reply,
  including cancellation. Refreshing a token and issuing another request belongs
  in the application's authentication flow.
- Re-registering an id replaces it for every frame that names it.
  `(rf/clear :http-interceptor id)` removes it for every frame; to stop one
  frame running it, leave the id out of that frame's `:http-interceptors`.

[Add authentication](../core/how-to/add-auth.md#3-decorate-requests-once-at-the-frame-boundary)
uses this pattern for a complete login flow. The [interceptor reference](../api/re-frame.http.md#request-interceptors)
records the context fields and registration forms.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `:rf.error/unregistered-interceptor` | The frame's `:http-interceptors` names an id nothing registered | Require the namespace that registers it, or fix the id |
| `:rf.error/http-bad-interceptor` | Neither phase is a function, the id is not a keyword, or the map carries `:frame` | Supply at least one function phase and a keyword id; name the id in the frame's `:http-interceptors` instead of passing `:frame` |
| `:rf.error/http-interceptor-bad-return` | A phase returned a non-map | Return the request context from `:before` and the reply map from `:after` |
| `:rf.error/http-interceptor-failed` | A phase threw | Handle expected failures in the phase; a failed `:before` sends no request, and a failed `:after` delivers no reply |

## When to use a request builder

An interceptor transforms all requests from a frame. If only a group of call
sites shares a base URL or body encoding, use an ordinary [request-builder function](http.md#your-own-request-builder)
for that group.
