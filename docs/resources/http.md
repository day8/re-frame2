# HTTP: the managed request

Sooner or later your app has to talk to a server, and the moment it does you inherit a small zoo of problems: errors, timeouts, retries, loading states, stale replies racing each other. This page introduces `:rf.http/managed`, the one [effect](../guide/glossary.md#effect) that thinks about all of that for you. We'll build up one piece at a time — starting from the smallest request that works, and ending with a search box whose keystroke race is cured for free.

The one idea underneath everything here: **a reply is an [event](../guide/glossary.md#event), not a resumed stack frame.** You issue a request as plain data from a pure [event handler](../guide/glossary.md#event-handler), finish, and later the answer arrives as an ordinary event — success and failure each named. That's the whole trick, and the rest of this page is consequences of it.

> **Who this is for.** You've read [effects and coeffects](../guide/concepts/effects-and-coeffects.md) and you're about to talk to a server. Everything below assumes you're comfortable returning an [effect map](../guide/glossary.md#effect-map) as data from a handler.

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

That's it. `:rf.http/managed` is a registered [effect](../guide/glossary.md#effect) — a named, declarative request for the runtime to act on your behalf. You hand it a map describing the request; the runtime issues it, decodes the body, sorts any failure into a named category, and dispatches the reply back into your app as an event. You never touch `js/fetch`.

Notice what the first handler did *not* do: it never paused to `await` the server. It returned a map and finished. When the response lands — milliseconds or seconds later — the runtime [dispatches](../guide/glossary.md#dispatch) a **new event**.

How does the reply data reach the receive handler? Every re-frame event is a vector — `[:event-id arg1 arg2 …]` — and the runtime simply **appends the reply as one more argument** on the end. So `:article/loaded` is dispatched as `[:article/loaded {:kind :success :value <decoded>}]`. The handler's second parameter, `[_ {:keys [value]}]`, destructures that vector: the `_` skips the event-id (the Clojure idiom for "an argument I'm deliberately ignoring"), and `{:keys [value]}` pulls `:value` out of the appended reply map. The success payload is `{:kind :success :value <decoded>}`; the failure payload is `{:kind :failure :failure <failure-map>}`. Three small handlers, each doing one thing, and the failure path has its own name instead of being an afterthought bolted onto the success path.

The only required key is `:request` with a `:url`. `:method` defaults to `:get`. Almost everything else has a sane default, which is why the common case stays this short.

> **For JavaScript developers.** The version you'd write by hand the first time is three clean lines:
>
> ```clojure
> (-> (js/fetch "/api/articles/welcome")
>     (.then #(.json %))
>     (.then #(rf/dispatch [:article/loaded %])))
> ```
>
> It ships on Tuesday and it's missing almost everything: no error handling (a 500 lands as garbage), no way to tell "the server said no" from "the network is on fire," no loading state, no timeout, no retry, no abort, no way to test without a real network. And the `rf/dispatch` fires from inside a `.then` — a fresh stack, long after the [frame](../guide/concepts/frames.md) (one isolated, running instance of your app) that the request ran under has unwound — so it fails loud with a no-frame-context error: the reply has nowhere to land. That's seven sins and a frame leak in three lines. The managed effect already thought about all of them, and threads the frame through so the reply lands back where it started.

> **Coming from TanStack Query / RTK Query?** This page is re-frame2's version of `fetchBaseQuery` — the configured transport that sits *under* the cache. Two things differ from what you know. The reply comes back as an **event**, never an awaited value. And a failure is one keyword from a fixed list, never whatever the exception stringified to. Caching and invalidation live one layer up, in [resources](concepts.md) — this page is the transport they ride on.

> **One-time setup.** Managed HTTP ships in its own artefact, `day8/re-frame2-http`, so apps that never issue a request build a bundle clean of it. Add the dep and require `re-frame.http.managed` once at app boot — that registers `:rf.http/managed` and family. Call a managed fx without the artefact loaded and the `re-frame.core` re-exports fail loud with a named "HTTP artefact missing" error rather than a silent miss.

## Reading the reply correctly

Two small things in the receive handlers above are load-bearing, and both trip people up the first time.

**The reply lands in the same [frame](../guide/concepts/frames.md) the request went out from.** The fx carries the frame from the original dispatch through to the reply, so the naive fetch's frame leak — firing a dispatch from a `.then` after the frame has unwound — simply cannot happen here. ([Frame identity is carried, not found](../guide/glossary.md#frame-identity-is-carried-not-found); the request envelope is one of the places the frame stamp rides along.)

**A durable timestamp comes from a [coeffect](../guide/glossary.md#coeffect), not the wall clock.** If your success handler wants to record *when* the article loaded, do not call `(js/Date.now)` inside it — that read happens live and wouldn't replay the same way twice. Declare the time as a coeffect — a fact the framework hands you as data — and read it from the coeffects map:

```clojure
(rf/reg-event :article/loaded
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :status]    :loaded)
             (assoc-in [:article :data]      value)
             (assoc-in [:article :loaded-at] time-ms))}))
```

`:rf/time-ms` is a *recordable* coeffect: it's stamped onto the event envelope before the handler runs, so the durable write depends on a recorded value and [replays](../guide/glossary.md#time-travel) identically. Reading the clock live would be an *ambient* read — fine for a throwaway display hint, wrong for anything that lands in [app-db](../guide/glossary.md#app-db). (The full grade distinction is [recordable vs ambient coeffects](../guide/glossary.md#recordable-vs-ambient-coeffects).)

> **Going deeper.** Why an event and not a resumed call? Start from a fact established in [events and the cascade](../guide/concepts/events-and-the-cascade.md): your app's whole state — [app-db, the single map that holds it](../guide/concepts/app-db.md) — is the running total of every event ever dispatched. Think of that event stream as a *ledger*: an append-only record of everything that ever influenced state. For replay and time-travel to work, the ledger has to contain *everything* that moved the state. An awaited value slips in through the call stack and leaves no line in the ledger. A reply *event* lands in the ledger as its own line — traceable, serializable, replayable. The reply is a *causal token*: facts like *when it completed* travel on it, which is exactly why `:loaded-at` reads from carried data rather than the clock. [Continuations are data](../guide/explanation/continuations-are-data.md) is the essay-length why.

> **Do, observe.** Run the request with [Xray](../guide/glossary.md#xray) open: the issuing event row, the request issuance on the [trace stream](../guide/glossary.md#trace-stream), then the reply arriving as an ordinary event row of its own — two ledger entries, one round trip.

### When request and reply belong together

Sometimes the request and reply really are two faces of one thing. Omit `:on-success` / `:on-failure` and the reply routes back to the *originating* event, merged into the message under `:rf/reply`. One handler then serves both roles:

```clojure
;; From examples/reagent/managed_http_counter (core.cljs), condensed.
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

Read that handler bottom-up. The first time it runs there is no `:rf/reply` in the message, so `if-let` falls to the else branch and issues the request. When the reply comes back, the runtime re-dispatches the *same* event with `:rf/reply` filled in, the `if-let` binds it, and the `case` routes on `:success` / `:failure`. Prefer two handlers by default — the separation reads more clearly and tests more easily. Co-locate only when the reply logic is trivial and tightly coupled to the request.

> **From re-frame v1.** Your `:http-xhrio`-style success/failure events map straight onto `:on-success` / `:on-failure` — the [migration page](../guide/25-from-re-frame-v1.md) walks the translation.

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
| `:mode` `:redirect` `:cache` `:referrer` `:integrity` | — | Fetch passthroughs. All but `:redirect` are CLJS-only. |

> **Gotcha — don't thread auth by hand.** Threading `"Authorization"` into each call site by hand gets old fast — and breaks the moment a token rotates. Don't. Register one [HTTP interceptor](#interceptors-stamp-every-request-once) instead and drop the header from your handlers entirely. The example above threads it inline only to show the envelope key exists.

> **Gotcha — the CLJS-only keys are silently no-ops on the JVM.** The six Fetch-passthrough keys above (`:credentials`, `:mode`, `:cache`, `:referrer`, `:integrity`, and the top-level `:abort-signal`) are meaningful against the browser Fetch API and have no `java.net.http.HttpClient` analogue. On the JVM the request still goes out — the option is just dropped — and one `:rf.http/cljs-only-key-ignored-on-jvm` warning trace fires per occurrence so the degraded path is visible rather than mysterious. (`:redirect` is the exception: it *is* honoured on the JVM.) If a request runs on both hosts — SSR, a shared loader — keep cross-host code off these keys or feature-flag them at the call site. The same asymmetry hits `:rf.http/cors`, which only the browser ever emits.

> **Gotcha — a malformed header is dropped, not fatal.** A header with an empty or control-character name, or a value carrying a raw `\r`/`\n` (the response-splitting guard), is rejected by the platform's header builder. Rather than failing the whole request, the runtime drops just that one pair, emits a redacted `:rf.warning/http-header-invalid` trace naming the offending header (the *value* is omitted — it may carry a secret), and sends the request with the remaining valid headers. So a stray newline in one interpolated header value quietly loses that header instead of taking down the call — watch the warning trace if a header you expected isn't arriving.

## Failures are a closed set

When something goes wrong, your `:on-failure` handler receives `{:kind :failure :failure <map>}`, and that failure map always carries a `:kind` from a fixed, framework-reserved list. Not a string — a keyword from a known set:

| `:kind` | When it fires |
|---|---|
| `:rf.http/transport` | Network / DNS / connection error before the HTTP transaction completed. |
| `:rf.http/cors` | CORS rejection. Browser-only. |
| `:rf.http/timeout` | The per-attempt timeout fired (default 30s; `:timeout-ms nil` opts out). |
| `:rf.http/http-4xx` | A 4xx response. The raw body rides at `:body` — decode is skipped on non-2xx. |
| `:rf.http/http-5xx` | A 5xx response. Same shape. |
| `:rf.http/decode-failure` | A 2xx response whose body the decode pipeline rejected. |
| `:rf.http/accept-failure` | Your `:accept` fn classified a structurally valid 200 as a domain failure (below). |
| `:rf.http/aborted` | Aborted via `:request-id` or abort signal (below). |

The set is closed for v1; adding a category requires a spec change. That constraint buys you something real: `:rf.http/timeout` means exactly the same thing in your codebase, in mine, and in every tool watching the trace stream. Branch on the `:kind`, never on a stringified message — same discipline you'd use on any framework [error record](../guide/glossary.md#error-record). (The [RealWorld example](../../examples/reagent/realworld/) — a full Conduit/Medium clone built on re-frame2, which this page draws several snippets from — maps this vocabulary to user-facing strings in one place, in a `failure->message` fn.)

Now the rule that catches every newcomer once: **decode runs only on 2xx responses — status is classified before the body is touched.** Picture a JSON endpoint behind a load balancer that 404s with an *HTML* error page. Instinct says decode failure. It isn't. It's `:rf.http/http-4xx` with the raw HTML at `:body`, because status was checked first and the decoder never ran. "The server said no" matters more than "and the no was shaped like HTML." If you want the structured error body many APIs return alongside a 4xx, decode `:body` yourself in the failure branch — the framework hands you the bytes and the status, on purpose.

One quieter classification fact: an **empty (or whitespace-only) 2xx body is not a decode failure** — it's a parsed value of `nil`. The bare `204 No Content` (or a `200`-with-no-body) that a PUT or DELETE replies with is the common case, and it succeeds: `:decode :json` hands your `:on-success` `{:kind :success :value nil}`. A schema `:decode` then decides whether `nil` is acceptable — `[:maybe …]` passes, a required `:map` rejects as an ordinary schema failure. This behaves identically on the browser and the JVM, on purpose.

## Validating the body with `:decode`

By default `:decode` is `:auto`, which sniffs the Content-Type and parses accordingly. But the 2xx body is exactly where a [schema](../guide/glossary.md#schema) earns its keep — coercing and validating the shape your handler then trusts. Hand `:decode` a [Malli schema](../../spec/010-Schemas.md) (Malli is the data-described schema library re-frame2 uses throughout — a schema is itself just a vector of data) and a malformed body becomes a clean `:rf.http/decode-failure` rather than a `NullPointerException` three handlers later:

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
;; Adapted from examples/reagent/realworld — a 200 with a null article is a domain miss.
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

The race is gone, with zero lines of race-handling code. (And that's not just an optimization — see [the correctness boundary](#one-envelope-under-every-async-surface) below for why the suppressed reply *cannot* clobber fresh data even if it arrives late.)

A *manual* abort is different from a supersession. Where reusing a `:request-id` quietly retires the previous request, a manual abort is an explicit "stop now." `[:rf.http/managed-abort the-id]` aborts whichever request currently holds the id and *does* deliver a failure reply — `{:kind :rf.http/aborted, :reason :user}` — so a deliberate user-cancel can clear the spinner. A supersession suppresses silently (the new request *is* the cleanup); a manual abort speaks up (someone clicked "cancel"). The `:reason` tells the two apart: `:user` for a manual abort, `:request-id-superseded` for a supersession (trace-only — it never reaches a handler). The [managed-http counter example](../../examples/reagent/managed_http_counter/) demonstrates the manual-abort path end-to-end — plus the 404-is-not-a-decode-failure rule — in one small file.

If the cancel signal you want to honour already lives outside re-frame — a parent widget's lifecycle, a shared `AbortController` — hand its `.signal` straight to the request under `:abort-signal`. It attaches a cancellation source to the same one request, so you can supply it *together with* a `:request-id` and the framework guarantees exactly one terminal outcome no matter which fires first. (`:abort-signal` is browser-only — the JVM has no `AbortController`, so `:request-id` is the cross-host cancel handle.)

> **Requests that die with their machine.** There's a third `:reason`, `:actor-destroyed`. A `:rf.http/managed` request issued from *inside* a spawned [state-machine](../machines/concepts.md) actor is aborted automatically when that actor is destroyed — its outstanding work dies with it, no manual abort needed. Requests dispatched from ordinary event handlers have no such lifecycle peg and are not auto-cancelled; that's deliberate, and `:request-id` remains your app-level cancel handle for them.

### Silencing a reply

Set `:on-success` or `:on-failure` to `nil` and that reply is dropped — fire-and-forget, useful for a telemetry beacon you genuinely don't care to handle. But the framework won't let you *accidentally* swallow an error: the first time a non-aborted failure is dropped by `:on-failure nil`, a one-shot `:rf.warning/failure-swallowed` trace fires (dev-only) so the silence is observable rather than invisible. Aborted requests are excluded — a cancelled request that no longer wants its reply is correct-by-design silence, not a bug.

## Keeping secrets out of the trace

HTTP is where the secrets are: passwords ride request bodies, auth tokens ride request headers, user PII rides response bodies. And every step of a managed request can land on the dev [trace stream](../guide/glossary.md#trace-stream) — the retry attempt, the failure category, the swallowed-failure warning — so without care the transport becomes the app's biggest leak. Managed HTTP applies [data classification](../guide/glossary.md#data-classification) at that egress boundary so the real value renders on-box but a redaction sentinel is what crosses into a trace, Xray, or an off-box log. Three layers cooperate, and two of them need no opt-in.

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

This is the schema's job whether or not the request also carries the coarse `:sensitive?` flag (the flag is the whole-body hammer; the schema marks are the scalpel). All of this rides the dev trace surface, so it [elides](../guide/glossary.md#elide) wholesale in production along with the rest of tracing — the redaction step costs nothing in a release build. (The full denylists, the `{:include :except}` carrier-policy form, and the off-box fail-closed rules are in [spec 014 §Privacy](../../spec/014-HTTPRequests.md#privacy); the framework-wide story is [keep secrets out of traces](../guide/how-to/keep-secrets-out-of-traces.md).)

> **Gotcha — a 4xx/5xx error body is always omitted off-box.** A non-2xx response surfaces its raw body at `:body`, and that body never ran through your `:decode` schema (status is classified *before* decode), so its shape is unknown. Off-box egress therefore drops it unconditionally — error responses routinely echo back request context or tokens. On your local dev trace you still see it; it's the off-box boundary that fails closed. If you need fields out of an error body, decode `:body` yourself in the failure branch where the value stays on-box.

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

Threading `"Authorization"` into every call site is the kind of cross-cutting concern that belongs in one place. re-frame2 ships a **per-frame HTTP interceptor chain** for exactly this — the same `{:before :after}` onion you know from [event interceptors](../guide/concepts/interceptors.md), but wrapping the transport instead of the event handler. A `:before` transforms the request on its way out; an `:after` transforms the reply on its way back.

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

- The `:before` fn receives a ctx of `{:request :args :frame :event}` and returns a ctx whose `:request` is the modified envelope. (Above, `cond-> ctx` adds the header only when a token is present, leaving the ctx untouched otherwise.) It reads app state through `rf/app-db-value` — an accessor that hands you the frame's current [app-db](../guide/glossary.md#app-db) value, never a live subscription.
- The `:after` fn is `(fn [ctx response] response')`. It sees the *same* ctx its `:before` produced — so a `:before` that stamps a start-time lets the matching `:after` compute an elapsed delta with no app state — plus the `{:kind :success …}` / `{:kind :failure …}` response, and it returns a possibly-transformed response that the `:on-success` / `:on-failure` dispatch then carries.

That ctx-carried-forward shape is what makes per-request concerns — response-time telemetry, rate-limit header parsing, flagging a 401 for an auth refresh — single-interceptor jobs.

A few rules that matter:

- **Chains are per-frame.** An interceptor registered on one [frame](../guide/concepts/frames.md) never fires for a request from another. Multi-frame apps register independent chains.
- **Onion order.** `:before`s run in registration order, `:after`s in reverse — A-registered-before-B means `A.before → B.before → transport → B.after → A.after`. Exactly the event-interceptor mental model.
- **At least one phase is required.** A map with neither `:before` nor `:after` is rejected at registration with a named bad-interceptor error. A `:before`-only or `:after`-only interceptor is fine and composes cleanly.
- **A throw is named, not swallowed.** A `:before` or `:after` that throws classifies as a named interceptor-failed error (carrying the offending `:interceptor-id`); a request-side throw means the transport never sees the request. Wrap recoverable logic inside the interceptor yourself — the chain has no recovery cofx.
- **Clearing.** `(rf/clear-http-interceptor id)` removes the slot (single-frame); `(rf/clear-http-interceptor frame-id id)` targets a frame. Re-registering an existing id replaces it *in place* (hot-reload-friendly); clear-then-reg appends a fresh slot at the end.

`reg-http-interceptor` and `clear-http-interceptor` are the only two HTTP surfaces re-exported onto the `rf/` facade (everything else lives in `re-frame.http` / `re-frame.http.managed`). This same seam is where resources and mutations get *their* request decoration too — register the auth interceptor once and every `:rf.http/managed` request, whether you issued it directly or a [resource](concepts.md) did, carries the header. The normative contract is [spec 014 §Middleware](../../spec/014-HTTPRequests.md#middleware).

## One envelope under every async surface

Everything above is HTTP-specific spelling over a deeper, framework-wide contract. You don't need this section to be productive — but it's the seam that makes resources, machines, and routing feel the same, so it's worth one read.

"A reply is an event" isn't just an HTTP convenience. It's [**the uniform reply**](../guide/glossary.md#the-uniform-reply), and every managed async surface completes through it: HTTP, [resources and mutations](concepts.md), [state-machine async work](../machines/concepts.md), and [route loaders](../routing/concepts.md). Those pages lean on this section instead of re-teaching it. The normative contract is [Managed-Effects](../../spec/Managed-Effects.md).

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
| `:error` | Completed with a failure; reply is current. `:error` carries a family `:kind` — for HTTP, one of the `:rf.http/*` categories above. |
| `:cancelled` | Intentionally cancelled while still correlated with the target. `:cancel/reason` present. |
| `:stale` | Completed *after* its correlation became obsolete. The app target is never dispatched; **no app-state mutation happens**. |

The `{:kind :success …}` / `{:kind :failure …}` payloads your `:on-success` / `:on-failure` handlers receive are this same envelope in HTTP's clothing: `:kind :success` is `:status :ok`, and `:kind :failure` is `:status :error` with the failure under `:error`. One contract; HTTP just hands your handlers the shorter spelling. (Timeout is not its own status, either — it's `:status :error` with `:kind :rf.http/timeout`. One fact, named once.)

Four rules finish the tour:

- **Stale suppression is the correctness boundary.** A newer request supersedes an older one — that's the search-box race. The old completion is classified `:stale`, the app target is skipped, and the trace records the carried-versus-current correlation. Your handler never sees a stale answer, so it can never overwrite fresh data with old. Cancellation is only an optimization here; *suppression* is what actually keeps state correct.
- **Cancellation is data, not the absence of a reply.** A live user-cancel dispatches `:status :cancelled` with a `:cancel/reason`. A supersession suppresses as `:stale`. Either way there's a value describing what happened — never a silently dropped continuation.
- **Completion timestamps ride the reply.** A reply is a causal token. Facts like *when it completed* travel on it (`:completed-at`), and handlers derive durable timestamps from that carried data. The `:rf/time-ms` declaration earlier is this same rule, wearing HTTP's public payload shape.
- **HTTP's `:on-success` / `:on-failure` / `:rf/reply` are public sugar over this envelope.** They're what you write on HTTP — `:rf.http/managed` does not accept a bare `:rf/reply-to`. But the general async model, shared by resources, mutations, machines, and routing, is the envelope. Each surface picks its own public spelling; the substrate underneath is one.

> **Going deeper.** Effects *sequence but never bind*: a handler can ask for several effects in order (the `:fx` vector), but never "do this effect, *then* feed its result into the next expression" — that would be monadic binding, the awaited-value shape, and it's exactly what re-frame2 refuses. The result comes back as the next event instead, and relocating a reply target is a pure data transform (the role `Cmd.map` plays in Elm's command algebra) — never a hidden callback.

## When not to reach for it

Managed HTTP is the right tool for a single request that gets a single reply. Here's where it isn't, and what to reach for instead — this is the HTTP corner of [the four homes](../guide/glossary.md#the-four-homes-where-state-lives) router:

- **The same server data read on several screens, with caching and invalidation** — that's a [resource](concepts.md), a declared, cached read of server state that rides this transport underneath. Declare it once. Hand-rolling `:loaded-at` freshness checks across features means you've outgrown raw requests.
- **Streaming, WebSockets, SSE** — out of scope for the single-request/single-reply shape. There is no managed streaming surface yet; that's an honest gap, not a hidden feature.
- **Wire-level weirdness** (custom transports, exotic binary protocols) — register your own fx; the escape hatch is always there.
- **Testing** needs no network: the canned-stub fxs (`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`, registered by requiring the sibling `re-frame.http.test-support` namespace) synthesize a reply with the exact envelope a live request produces — see [testing a full cascade](../guide/how-to/test-a-cascade.md).

The full key-by-key contract — body thunks, multipart, the keyword-interning DoS cap (`:rf.http/max-decoded-keys`, default `10000`), the `:sensitive?` trace flag, per-host degradations — is [spec 014](../../spec/014-HTTPRequests.md).
