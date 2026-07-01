# HTTP: the managed request

Sooner or later your app has to talk to a server, and the moment it does you inherit a small zoo of problems: errors, timeouts, retries, loading states, stale replies racing each other. This page introduces `:rf.http/managed`, the one [effect](../core/glossary.md#effect) that thinks about all of that for you. We'll build up one piece at a time — starting from the smallest request that works, and ending with a search box whose keystroke race is cured for free.

The one idea underneath everything here: **a reply is an [event](../core/glossary.md#event), not a resumed stack frame.** You issue a request as plain data from a pure [event handler](../core/glossary.md#event-handler), finish, and later the answer arrives as an ordinary event — success and failure each named. That's the whole trick, and the rest of this page is consequences of it.

> **Who this is for.** You've read [effects and coeffects](../core/concepts/effects-and-coeffects.md) and you're about to talk to a server. Everything below assumes you're comfortable returning an [effect map](../core/glossary.md#effect-map) as data from a handler.

## The smallest request that works

Issuing a request takes two handlers: one to send it, one to receive the reply. Here is the whole thing.

```clojure
;; 1. Send: returns an effect map describing the request as data.
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:url (str "/api/articles/" slug)}
            :on-success [:article/loaded]
            :on-failure [:article/load-error]}]]}))

;; 2. Receive: the reply lands as an ordinary event, payload appended.
(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :status] :loaded)
             (assoc-in [:article :data]   value))}))

(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
             (assoc-in [:article :status] :error)
             (assoc-in [:article :error]  failure))}))
```

That's it. `:rf.http/managed` is a registered [effect](../core/glossary.md#effect) — a named, declarative request for the runtime to act on your behalf. You hand it a map describing the request; the runtime issues it, decodes the body, sorts any failure into a named category, and dispatches the reply back into your app as an event. You never touch `js/fetch`.

Notice what the first handler did *not* do: it never paused to `await` the server. It returned a map and finished. When the response lands — milliseconds or seconds later — the runtime [dispatches](../core/glossary.md#dispatch) a **new event**.

How does the reply data reach the receive handler? Every re-frame event is a vector — `[:event-id arg1 arg2 …]` — and the runtime simply **appends the reply as one more argument** on the end. So `:article/loaded` is dispatched as `[:article/loaded {:kind :success :value <decoded>}]`. The handler's second parameter, `[_ {:keys [value]}]`, destructures that vector: the `_` skips the event-id (the Clojure idiom for "an argument I'm deliberately ignoring"), and `{:keys [value]}` pulls `:value` out of the appended reply map. The success payload is `{:kind :success :value <decoded>}`; the failure payload is `{:kind :failure :failure <failure-map>}`. Three small handlers, each doing one thing, and the failure path has its own name instead of being an afterthought bolted onto the success path.

The only required key is `:request` with a `:url`. `:method` defaults to `:get`. Almost everything else has a sane default, which is why the common case stays this short.

That's the **separate-handlers** shape — one of [two equal ways to handle the reply](#two-ways-to-handle-the-reply); the other folds all three roles into a single handler.

> **What's in a reply — and the one `:kind` to watch.** Both replies are plain maps. Success is `{:kind :success :value <decoded-body>}`; failure is `{:kind :failure :failure <failure-map>}`. Mind the nesting: the *reply* has a `:kind` that is `:success` or `:failure`, and a failure's inner `:failure` map carries its **own** `:kind` — the category (`:rf.http/timeout` and friends, [below](#failures-are-a-closed-set)). So `(:kind reply)` tells you *which path*; `(:kind (:failure reply))` tells you *which failure*. This is HTTP's short spelling of one framework-wide reply contract — the full `:status`-based envelope is in [going further](http-going-further.md#one-envelope-under-every-async-surface), but everyday requests never need it.

> **For JavaScript developers.** The version you'd write by hand the first time is three clean lines:
>
> ```clojure
> (-> (js/fetch "/api/articles/welcome")
>     (.then #(.json %))
>     (.then #(rf/dispatch [:article/loaded %])))
> ```
>
> It ships on Tuesday and it's missing almost everything: no error handling (a 500 lands as garbage), no way to tell "the server said no" from "the network is on fire," no loading state, no timeout, no retry, no abort, no way to test without a real network. And the `rf/dispatch` fires from inside a `.then` — a fresh stack, long after the [frame](../core/concepts/frames.md) (one isolated, running instance of your app) that the request ran under has unwound — so it fails loud with a no-frame-context error: the reply has nowhere to land. That's seven sins and a frame leak in three lines. The managed effect already thought about all of them, and threads the frame through so the reply lands back where it started.

> **Coming from TanStack Query / RTK Query?** This page is re-frame2's version of `fetchBaseQuery` — the configured transport that sits *under* the cache. Two things differ from what you know. The reply comes back as an **event**, never an awaited value. And a failure is one keyword from a fixed list, never whatever the exception stringified to. Caching and invalidation live one layer up, in [resources](../resources/concepts.md) — this page is the transport they ride on.

> **One-time setup.** Managed HTTP ships in its own artefact, `day8/re-frame2-http`, so apps that never issue a request build a bundle clean of it. Add the dep and require `re-frame.http.managed` once at app boot — that registers `:rf.http/managed` and family. Call a managed fx without the artefact loaded and the `re-frame.core` re-exports fail loud with a named "HTTP artefact missing" error rather than a silent miss.

## Reading the reply correctly

Two small things in the receive handlers above are load-bearing, and both trip people up the first time.

**The reply lands in the same [frame](../core/concepts/frames.md) the request went out from.** The fx carries the frame from the original dispatch through to the reply, so the naive fetch's frame leak — firing a dispatch from a `.then` after the frame has unwound — simply cannot happen here. ([Frame identity is carried, not found](../core/glossary.md#frame-identity-is-carried-not-found); the request envelope is one of the places the frame stamp rides along.)

**A durable timestamp comes from a [coeffect](../core/glossary.md#coeffect), not the wall clock.** If your success handler wants to record *when* the article loaded, do not call `(js/Date.now)` inside it — that read happens live and wouldn't replay the same way twice. Declare the time as a coeffect — a fact the framework hands you as data — and read it from the coeffects map:

```clojure
(rf/reg-event :article/loaded
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :status]    :loaded)
             (assoc-in [:article :data]      value)
             (assoc-in [:article :loaded-at] time-ms))}))
```

`:rf/time-ms` is a *recordable* coeffect: it's stamped onto the event envelope before the handler runs, so the durable write depends on a recorded value and [replays](../core/glossary.md#time-travel) identically. Reading the clock live would be an *ambient* read — fine for a throwaway display hint, wrong for anything that lands in [app-db](../core/glossary.md#app-db). (The full grade distinction is [recordable vs ambient coeffects](../core/glossary.md#recordable-vs-ambient-coeffects).)

> **Going deeper.** Why an event and not a resumed call? Start from a fact established in [events and the cascade](../core/concepts/events-and-the-cascade.md): your app's whole state — [app-db, the single map that holds it](../core/concepts/app-db.md) — is the running total of every event ever dispatched. Think of that event stream as a *ledger*: an append-only record of everything that ever influenced state. For replay and time-travel to work, the ledger has to contain *everything* that moved the state. An awaited value slips in through the call stack and leaves no line in the ledger. A reply *event* lands in the ledger as its own line — traceable, serializable, replayable. The reply is a *causal token*: facts like *when it completed* travel on it, which is exactly why `:loaded-at` reads from carried data rather than the clock. [Continuations are data](continuations-are-data.md) is the essay-length why.

> **Do, observe.** Run the request with [Xray](../core/glossary.md#xray) open: the issuing event row, the request issuance on the [trace stream](../core/glossary.md#trace-stream), then the reply arriving as an ordinary event row of its own — two ledger entries, one round trip.

## Two ways to handle the reply

There are two **equal** ways to wire the reply — pick by fit, not correctness.

### Separate handlers

The form shown above: the request handler names `:on-success` and `:on-failure`, and each outcome lands in its own handler — three in all: one to issue the request, one for success, one for failure. Each path reads and tests on its own.

```clojure
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed {:request    {:url (str "/api/articles/" slug)}
                             :on-success [:article/loaded]
                             :on-failure [:article/load-error]}]]}))

(rf/reg-event :article/loaded     (fn [{:keys [db]} [_ {:keys [value]}]]   …))   ;; success
(rf/reg-event :article/load-error (fn [{:keys [db]} [_ {:keys [failure]}]] …))   ;; failure
```

> **From re-frame v1.** Your `:http-xhrio`-style success/failure events map straight onto `:on-success` / `:on-failure` — the [migration page](../core/25-from-re-frame-v1.md) walks the translation.

### One handler

Omit `:on-success` / `:on-failure` and the reply routes back to the *originating* event, merged into the message under `:rf/reply`. One handler then serves all three roles:

```clojure
;; From examples/core/managed_http_counter (core.cljs), condensed.
(rf/reg-event :counter/+1
  (fn [{:keys [db]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      (case (:kind reply)
        :success {:db (-> db
                          (update :counter/count + (-> reply :value :delta))
                          (assoc :counter/status :idle :counter/error nil))}
        :failure {:db (assoc db :counter/status :error
                                :counter/error  (:failure reply))})
      ;; Initial branch — issue the request.
      {:db (assoc db :counter/status :loading)
       :fx [[:rf.http/managed {:request {:url "/api/inc.json"}}]]})))
```

Read that handler bottom-up. The first time it runs there is no `:rf/reply`, so the handler issues the request. When the reply comes back, the runtime dispatches the *same* event again with `:rf/reply` in the message. If the original message was a map, the rest of that map is still there too, so request context like an id or slug is still in scope.

> **Gotcha — the one-handler form wants a map message.** Dispatch a map (`[:thing/load {:id "intro"}]`) or no payload. A bare value (`[:thing/load "intro"]`) still receives the reply, but there is nowhere to merge `"intro"`, so the reply branch sees only `{:rf/reply ...}`. Put request context in a map when you need it after the reply lands.

### Which to use

They're equals; pick by fit:

- **Separate handlers** when the success and failure paths are substantial or diverge — they read and test independently.
- **One handler** when request and reply are two faces of one thing and the reply logic is small (a counter, a toggle, a fire-and-refresh) — request and both outcomes sit in one place, with the original args still in scope.

## The request is a map

So far the `:request` map has carried only a `:url`. It *is* the wire request, expressed as data, and it grows to cover any real call. A POST with a JSON body, query params, and an auth header is still just data:

```clojure
(rf/reg-event :comment/create
  (fn [{:keys [db]} [_ slug text]]
    {:fx [[:rf.http/managed
           {:request    {:method  :post
                         :url     (str "/api/articles/" slug "/comments")
                         :params  {:notify true}
                         :headers {"Authorization" (str "Bearer " (:token db))}
                         :body    {:comment {:body text}}
                         :request-content-type :json}   ;; serialises :body + sets Content-Type
            :decode     CommentResponse
            :on-success [:comment/created]
            :on-failure [:comment/create-error]}]]}))
```

`:url` is the only required key inside `:request`; everything else has a sane default. Three conveniences are worth calling out from that example:

- **`:params` builds the query string for you.** Hand it a map and it URL-encodes each pair and merges them onto `:url` — no hand-built `?a=1&b=2`.
- **`:request-content-type` serialises `:body` and stamps the header in one move.** `:json` runs the `:body` map through JSON serialisation and sets `Content-Type: application/json`; `:form` URL-encodes it as a form body instead. For a file upload, hand a `js/FormData` straight in as `:body` and leave `:request-content-type` off — the platform sets the multipart boundary itself.
- **A bad `:url` fails loud, not silent.** If a `:url` comes through blank, nil, or a non-string, the dispatch is rejected with a named bad-request error rather than letting a nil URL fall through to the transport as an opaque failure.

Here is the full `:request` envelope:

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

> **Gotcha — don't thread auth by hand.** Threading `"Authorization"` into each call site by hand gets old fast — and breaks the moment a token rotates. Don't. Register one [HTTP interceptor](http-going-further.md#interceptors-stamp-every-request-once) instead and drop the header from your handlers entirely. The example above threads it inline only to show the envelope key exists.

> **Gotcha — the CLJS-only keys are silently no-ops on the JVM.** The six Fetch-passthrough keys above (`:credentials`, `:mode`, `:cache`, `:referrer`, `:integrity`, and the top-level `:abort-signal`) are meaningful against the browser Fetch API and have no `java.net.http.HttpClient` analogue. On the JVM the request still goes out — the option is just dropped — and one `:rf.http/cljs-only-key-ignored-on-jvm` warning trace fires per occurrence so the degraded path is visible rather than mysterious. (`:redirect` is the exception: it *is* honoured on the JVM.) If a request runs on both hosts — SSR, a shared loader — keep cross-host code off these keys or feature-flag them at the call site. The same asymmetry hits `:rf.http/cors`, which only the browser ever emits.

> **Gotcha — a malformed header is dropped, not fatal.** A header with an empty or control-character name, or a value carrying a raw `\r`/`\n` (the response-splitting guard), is rejected by the platform's header builder. Rather than failing the whole request, the runtime drops just that one pair, emits a redacted `:rf.warning/http-header-invalid` trace naming the offending header (the *value* is omitted — it may carry a secret), and sends the request with the remaining valid headers. So a stray newline in one interpolated header value quietly loses that header instead of taking down the call — watch the warning trace if a header you expected isn't arriving.

## Failures are a closed set

When something goes wrong, your `:on-failure` handler receives `{:kind :failure :failure <map>}`, and that failure map always carries a `:kind` from a fixed, framework-reserved list. Not a string — a keyword from a known set:

| `:kind` | When it fires | Extra keys on the failure map |
|---|---|---|
| `:rf.http/transport` | Network, DNS, connection error, or request-preparation error before the HTTP transaction completed. | `:message`, `:cause`; request-preparation failures also carry `:stage :request-prep`. |
| `:rf.http/cors` | CORS rejection. Browser-only. | `:message`, `:url`. |
| `:rf.http/timeout` | The per-attempt timeout fired. The default is 30 seconds; `:timeout-ms nil` or `:timeout-ms 0` opts out. | `:elapsed-ms`, `:limit-ms`; JVM failures may also carry `:message`. |
| `:rf.http/http-4xx` | A 4xx response, plus rare non-2xx responses that are not 5xx. Decode is skipped. | `:status`, `:status-text`, `:body`, `:headers`. |
| `:rf.http/http-5xx` | A 5xx response. Decode is skipped. | `:status`, `:status-text`, `:body`, `:headers`. |
| `:rf.http/decode-failure` | A 2xx response whose body the decode pipeline rejected. | `:body-text`, `:cause`, `:schema-validation-failure?`; keyword-cap failures also carry `:reason :too-many-keys` and `:limit`. |
| `:rf.http/accept-failure` | Your `:accept` fn returned `{:failure ...}`, threw, or returned a malformed shape. | `:detail`, `:decoded`, `:request-id`. |
| `:rf.http/aborted` | Aborted via `:request-id`, abort signal, or machine actor destroy. | `:request-id`, `:reason`; some paths also carry `:actor-id` or `:message`. |

The set is closed for v1; adding a category is a versioned framework change. That constraint buys you something real: `:rf.http/timeout` means exactly the same thing in your codebase, in mine, and in every tool watching the trace stream. Branch on the `:kind`, never on a stringified message — same discipline you'd use on any framework [error record](../core/glossary.md#error-record). (The [RealWorld example](../../examples/real-apps/realworld_http) — a full Conduit/Medium clone built on re-frame2, which this page draws several snippets from — maps this vocabulary to user-facing strings in one place, in a `failure->message` fn.)

Now the rule that catches every newcomer once: **decode runs only on 2xx responses — status is classified before the body is touched.** Picture a JSON endpoint behind a load balancer that 404s with an *HTML* error page. Instinct says decode failure. It isn't. It's `:rf.http/http-4xx` with the raw HTML at `:body`, because status was checked first and the decoder never ran. "The server said no" matters more than "and the no was shaped like HTML." If you want the structured error body many APIs return alongside a 4xx, decode `:body` yourself in the failure branch — the framework hands you the bytes and the status, on purpose.

One quieter classification fact: an **empty (or whitespace-only) 2xx body is not a decode failure** — it's a parsed value of `nil`. The bare `204 No Content` (or a `200`-with-no-body) that a PUT or DELETE replies with is the common case, and it succeeds: `:decode :json` hands your `:on-success` `{:kind :success :value nil}`. A schema `:decode` then decides whether `nil` is acceptable — `[:maybe …]` passes, a required `:map` rejects as an ordinary schema failure. This behaves identically on the browser and the JVM, on purpose.

## Reply maps at a glance

Every live request reply has one of these public shapes:

| Reply | Map shape | Notes |
|---|---|---|
| Success | `{:kind :success :value value}` | `value` is the decoded 2xx body. If you supplied `:accept`, this is the value inside `{:ok value}`. |
| Failure | `{:kind :failure :failure failure-map}` | `failure-map` is one of the category maps listed above. Branch on `(-> reply :failure :kind)`. |

Explicit reply handlers receive the reply map as the last event argument. In the one-handler form, the same reply map is merged into the originating event message under `:rf/reply`. A stale reply from a superseded request is not delivered to your app at all; it is trace-only.

`:on-success` and `:on-failure` must be event vectors when you supply them. `nil` means "silence this side"; a keyword, map, string, or any other non-vector value is rejected when that side's reply is dispatched, with `:rf.error/http-bad-reply-target`, so a misshaped continuation cannot be silently rerouted.

### Handling a failure

Classifying a failure is only half the job — the handler has to *do* something with it. Because the category is a keyword from a closed set, that's a `case`, and it's the natural place to turn a failure into something a user can read:

```clojure
(defn failure->message [failure]
  (case (:kind failure)
    :rf.http/timeout    "The server took too long. Try again."
    :rf.http/transport  "You appear to be offline."
    :rf.http/http-5xx   "Something went wrong on our end."
    (:rf.http/http-4xx
     :rf.http/decode-failure
     :rf.http/accept-failure) "We couldn't load that."
    "Something unexpected happened."))

(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
             (assoc-in [:article :status]  :error)            ;; clears the spinner
             (assoc-in [:article :message] (failure->message failure)))}))
```

The view reads `[:article :status]` and `[:article :message]` like any other state — the spinner clears, the message shows — because the failure path writes app-db exactly the way the success path does. Keeping the `case` in one `failure->message` fn (the shape the [RealWorld example](../../examples/real-apps/realworld_http) uses) means every screen renders the same vocabulary the same way.

## Validating the body with `:decode`

By default `:decode` is `:auto`, which sniffs the Content-Type and parses accordingly. But the 2xx body is exactly where a [schema](../core/glossary.md#schema) earns its keep — coercing and validating the shape your handler then trusts. Hand `:decode` a Malli schema (a data vector that describes the expected shape) and a malformed body becomes a clean `:rf.http/decode-failure` rather than a `NullPointerException` three handlers later:

```clojure
(def ArticleResponse
  "Validates and coerces the JSON body of GET /api/articles/:slug."
  [:map
   [:article [:map
              [:slug  :string]
              [:title :string]
              [:body  :string]]]])

;; :decode runs only on 2xx; a body that fails the schema becomes
;; :rf.http/decode-failure, never a surprise nil downstream.
{:fx [[:rf.http/managed
       {:request    {:url (str "/api/articles/" slug)}
        :decode     ArticleResponse
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

`:decode` also accepts a keyword (`:json` / `:text` / `:blob` / `:array-buffer` / `:form-data`) or a plain function `(fn [text headers] decoded)` when you need full control.

### A valid 200 can still be a failure: `:accept`

Status-before-decode handles the wire. But some APIs answer `200 OK` and then tell you, *in the body*, that the thing you asked for isn't there — `{"article": null}` instead of a 404. The transport succeeded, the JSON parsed, the schema passed; by every wire signal this is a success. Your domain disagrees. That's what `:accept` is for: a post-decode normaliser that gets the last word.

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

The default `:accept` is just `{:ok decoded}` — every 2xx that decodes is a success. You only reach for an explicit one when "the wire said 200" and "the domain is satisfied" can come apart. A few rules keep it predictable:

- `:accept` runs **only after a successful 2xx decode** — that is, after status has been classified and after the body has decoded. A non-2xx response is sorted by status long before this point and never reaches `:accept`, so your `:accept` never has to think about HTTP status.
- An `:accept` that **throws**, or returns a **malformed shape** (nil, a non-map, a map with neither `:ok` nor `:failure`, or one with *both*) still dispatches a reply — it can never strand the caller. It classifies as `:rf.http/accept-failure` with a framework-supplied `:detail`, and the pre-`:accept` value rides at `:decoded` so you can see what it choked on.
- An accept-failure is **not retryable**. Retrying the transport won't change the body — this is a domain decision, not a transport blip. If you need "retry after refreshing X," that's a [state machine](../machines/concepts.md), not `:accept`.

## Reads retry; writes don't

A read-only GET that hits a transient network blip should just try again. Add a `:retry` policy — itself plain data — and the runtime handles the backoff:

```clojure
(def data-fetch-retry
  "Retry policy for read-only fetches: transport blips, 5xx, timeouts.
   Not 4xx — the request shape was valid; retrying won't help."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

{:fx [[:rf.http/managed
       {:request    {:url (str "/api/articles/" slug)}
        :decode     ArticleResponse
        :retry      data-fetch-retry
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

`:on` is the set of failure categories that trigger a retry. `:max-attempts` is the total *including* the first try (so `1` means no retry). Backoff is exponential — `:base-ms` times `:factor` per attempt, capped at `:max-ms` — with optional `:jitter`, which adds ±25% randomness so a thousand clients don't retry in lockstep against your recovering server. Only the final exhausted failure dispatches your failure handler; intermediate attempts are trace rows (`:rf.http/retry-attempt`), not events your code sees. And `:rf.http/aborted` is never retryable.

`:on` is a **closed set**, drawn only from the retryable categories: `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`. A category is in the set when re-issuing the *same* request could plausibly change the outcome. The first three are the obvious transient cases; 4xx and CORS are admitted because a real slice of them is transient too (`429 Too Many Requests`, `408`, a just-deploying CORS edge) — but most aren't, so they're opt-in and want a narrow `:max-attempts`.

Two guards keep a malformed `:on` from silently doing nothing:

- Put a non-retryable category — or anything outside the `:rf.http/*` namespace — in `:on` and the request is rejected at dispatch with a named bad-retry-on error, rather than riding a useless policy for its lifetime.
- `:on` must be an actual *set* (`#{…}`). A bare keyword or a vector is rejected the same way — and for a sharp reason: the membership test is `contains?`, and `contains?` over a vector tests *indices*, not values, so a vector `:on` would silently disable retry for every category.

The real discipline isn't *whether* to retry but *what*. Read-only fetches are safe. User-initiated writes are not, because retrying a submit or a payment risks doing it twice. So the production shape is one shared policy for reads (`data-fetch-retry` above) and conspicuously *no* `:retry` on writes — the RealWorld example's login, register, and settings requests carry none.

> **Coming from Axios?** `axios-retry`'s `retryCondition` ≈ `:on`, `retryDelay` ≈ `:backoff` — except here the policy is inspectable data at the call site and every attempt is a trace row, not closure state buried inside an interceptor.

> **Going deeper.** `:retry` owns **transport retry** only — decisions that are a pure function of failure category and attempt count. The moment a retry decision depends on anything else ("after a 401, refresh the token, *then* retry"; "the body says retry-after 5s"), it becomes **semantic retry**, and that belongs in a [state machine](../machines/concepts.md) driving the request, with transport `:retry` still active inside each attempt the machine launches. The boundary is one test: pure-function-of-category stays here; stateful-decision graduates to a machine.

## The search-box race, cured

Here's the problem from the top of the page, made concrete. The user types five letters into a search box; five requests race; whichever lands last wins — often *not* the one for the last letter. The fix is one key.

Give a request a stable `:request-id` — any `=`-comparable value works: a keyword, a string, or a structural vector like `[:articles :load slug]` — and issuing a *new* request with the same id automatically supersedes the old one. The old reply is suppressed, your handler never sees it, only a trace row records it. So give every keystroke's search request the *same* `:request-id`, and the only reply you act on is the latest:

```clojure
;; Every keystroke reuses the same :request-id, so each new search
;; supersedes the one before it. Only the latest reply ever lands.
(rf/reg-event :search/query-changed
  (fn [{:keys [db]} [_ q]]
    {:db (assoc db :search/q q)
     :fx [[:rf.http/managed
           {:request    {:url "/api/search" :params {:q q}}
            :request-id :search/in-flight       ;; stable across keystrokes
            :decode     SearchResults
            :on-success [:search/results]
            :on-failure [:search/error]}]]}))
```

The race is gone, with zero lines of race-handling code. (And that's not just an optimization — see [the correctness boundary](http-going-further.md#one-envelope-under-every-async-surface) for why the suppressed reply *cannot* clobber fresh data even if it arrives late.)

A *manual* abort is different from a supersession. Where reusing a `:request-id` quietly retires the previous request, a manual abort is an explicit "stop now." `[:rf.http/managed-abort the-id]` aborts whichever request currently holds the id and *does* deliver a failure reply — `{:kind :rf.http/aborted, :reason :user}` — so a deliberate user-cancel can clear the spinner. A supersession suppresses silently (the new request *is* the cleanup); a manual abort speaks up (someone clicked "cancel"). The `:reason` tells the two apart: `:user` for a manual abort, `:request-id-superseded` for a supersession (trace-only — it never reaches a handler). The [managed-http counter example](../../examples/core/managed_http_counter) demonstrates the manual-abort path end-to-end — plus the 404-is-not-a-decode-failure rule — in one small file.

If the cancel signal you want to honour already lives outside re-frame — a parent widget's lifecycle, a shared `AbortController` — hand its `.signal` straight to the request under `:abort-signal`. It attaches a cancellation source to the same one request, so you can supply it *together with* a `:request-id` and the framework guarantees exactly one terminal outcome no matter which fires first. (`:abort-signal` is browser-only — the JVM has no `AbortController`, so `:request-id` is the cross-host cancel handle.)

> **Requests that die with their machine.** There's a third `:reason`, `:actor-destroyed`. A `:rf.http/managed` request issued from *inside* a spawned [state-machine](../machines/concepts.md) actor is aborted automatically when that actor is destroyed — its outstanding work dies with it, no manual abort needed. Requests dispatched from ordinary event handlers have no such lifecycle peg and are not auto-cancelled; that's deliberate, and `:request-id` remains your app-level cancel handle for them.

### Silencing a reply

Set `:on-success` or `:on-failure` to `nil` and that reply is dropped — fire-and-forget, useful for a telemetry beacon you genuinely don't care to handle. But the framework won't let you *accidentally* swallow an error: the first time a non-aborted failure is dropped by `:on-failure nil`, a one-shot `:rf.warning/failure-swallowed` trace fires (dev-only) so the silence is observable rather than invisible. Aborted requests are excluded — a cancelled request that no longer wants its reply is correct silence, not a bug.

## The args map at a glance

Every key you can hand `:rf.http/managed`, in one place. Only `:request` (with a `:url`) is required; each has a sane default, which is why the common case stays short.

| Key | What it does | Default |
|---|---|---|
| `:request` | The wire request as data — `:method` / `:url` / `:headers` / `:params` / `:body` and the [rest of the envelope](#the-request-is-a-map). | **required** |
| `:on-success` | Event vector for success replies. Omit it to route success back to the originating event; set it to `nil` to [silence](#silencing-a-reply) success replies. | omitted |
| `:on-failure` | Event vector for failure replies. Omit it to route failure back to the originating event; set it to `nil` to [silence](#silencing-a-reply) failure replies. | omitted |
| `:decode` | Parse and [validate the 2xx body](#validating-the-body-with-decode) — a [schema](../core/glossary.md#schema), a keyword, or a fn. | `:auto` |
| `:accept` | A [post-decode domain check](#a-valid-200-can-still-be-a-failure-accept) — a 200 can still be a failure. | `{:ok decoded}` |
| `:retry` | A [transport-retry policy](#reads-retry-writes-dont) — `:on` set, `:max-attempts`, `:backoff`. | none |
| `:request-id` | A stable id; a new request with the same id [supersedes the old](#the-search-box-race-cured). | none |
| `:abort-signal` | An external `AbortController` `.signal` to cancel through. Browser-only. | none |
| `:timeout-ms` | Per-attempt timeout; `nil` or `0` opts out. | `30000` |
| `:sensitive?` | Redact body, params, and all URL values in traces — see [keeping secrets](http-going-further.md#keeping-secrets-out-of-the-trace). | `false` |
| `:rf.http/max-decoded-keys` | Caps how many unique JSON object keys the decoder may intern. Raise it only for unusually large trusted payloads. | `10000` |

Beyond the everyday surface, [HTTP: going further](http-going-further.md) covers the verb-helper shorthand (`rf.http/get` / `post` / …), the interceptor seam for cross-cutting concerns like auth, keeping secrets out of traces, and the one reply contract shared across every async surface.

## When not to reach for it

Managed HTTP is the right tool for a single request that gets a single reply. Here's where it isn't, and what to reach for instead — this is the HTTP corner of [the four homes](../core/glossary.md#the-four-homes-where-state-lives) router:

- **The same server data read on several screens, with caching and invalidation** — that's a [resource](../resources/concepts.md), a declared, cached read of server state that rides this transport underneath. Declare it once. Hand-rolling `:loaded-at` freshness checks across features means you've outgrown raw requests.
- **Streaming, WebSockets, SSE** — out of scope for the single-request/single-reply shape. There is no managed streaming surface yet; that's an honest gap, not a hidden feature.
- **Wire-level weirdness** (custom transports, exotic binary protocols) — register your own fx; the escape hatch is always there.
- **Testing** needs no network: the canned-stub fxs (`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`, registered by requiring the sibling `re-frame.http.test-support` namespace) synthesize a reply with the exact envelope a live request produces — see [testing a full cascade](../core/how-to/test-a-cascade.md).

The less common keys follow the same rules: a body function can defer body construction until the request runs, multipart sends form-data, `:rf.http/max-decoded-keys` caps decoded keyword interning at `10000` by default, `:sensitive?` hides request and response values in traces, and unsupported host work becomes a named HTTP failure instead of silently doing nothing.
