# HTTP: the managed request

Sooner or later your app has to talk to a server, and the moment it does you inherit a small zoo of problems: errors, timeouts, retries, loading states, stale replies racing each other. This page introduces `:rf.http/managed`, the one effect that thinks about all of that for you. By the end you'll issue an HTTP request as plain data from a pure handler, and handle its reply as an ordinary event — with success and failure each named, and the search-box race cured for free.

> **Who this is for.** You've read [effects and coeffects](effects-and-coeffects.md) and you're about to talk to a server. If you know RTK Query's `fetchBaseQuery` — the configured transport that sits *under* the cache — this page is re-frame2's version of that layer. Two things differ. The reply comes back as an **event**, never an awaited value. And a failure is one keyword from a fixed list, never whatever the exception stringified to. Caching and invalidation live one layer up, in [resources](server-state.md). This page is the transport they ride on.

**The takeaway: a reply is an event, not a resumed stack frame.**

## The fetch you write the first time

```clojure
;; The honeymoon version — do not ship this.
(-> (js/fetch "/api/articles/welcome")
    (.then #(.json %))
    (.then #(rf/dispatch [:article/loaded %])))
```

Clean, readable, ships on Tuesday. It's also missing almost everything, and the gaps are worth naming because each one is a thing the managed effect handles for you. There's no error handling, so a 500 lands in `:article/loaded` as garbage. There's no way to tell "the server said no" (a 404) from "the network is on fire" (DNS failed). No loading state. No timeout, so a dead server hangs forever. No retry, so a transient blip is a hard failure. No abort, so if you type five letters into a search box, five requests race and whichever lands last wins — often not the one for the last letter. No way to test it without a real network or a hand-rolled mock. And one more, easy to miss: that `rf/dispatch` fires from inside a `.then`. That's a fresh stack, long after the [frame](frames.md) — the isolated runtime context the request ran under — has unwound. So the dispatch carries no frame context and fails loudly with `:rf.error/no-frame-context`.

That's seven sins and a frame leak in three lines. The fix isn't more careful fetch code on every screen; that just spreads the problem around. It's one effect that already thought about all of it.

## A request is data

`:rf.http/managed` is a registered effect — a named, declarative request for the runtime to do something on your behalf. Its args map describes an HTTP request *as data*. You return the map. The runtime issues the request, decodes the body, retries with backoff if you asked, sorts every failure into a named category, and dispatches the reply back into your app as an ordinary event. You never touch `js/fetch`.

```clojure
;; Adapted from examples/reagent/realworld (http.cljs + articles.cljs).
(def ArticleResponse
  "Malli schema for GET /api/articles/:slug — validates and coerces the JSON body."
  [:map
   [:article [:map
              [:slug  :string]
              [:title :string]
              [:body  :string]]]])

(def data-fetch-retry
  "Retry policy for read-only fetches: transport blips, 5xx, timeouts.
   Not 4xx — the request shape was valid; retrying won't help."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (-> db
             (assoc-in [:article :status] :loading)
             (assoc-in [:article :error]  nil))
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    (str "/api/articles/" slug)}
            :decode     ArticleResponse
            :retry      data-fetch-retry
            :on-success [:article/loaded]
            :on-failure [:article/load-error]}]]}))
```

Almost everything here is optional, which means the common case stays short. The only required key is `:request` with a `:url`. `:method` defaults to `:get`. `:decode` defaults to `:auto`, which sniffs the Content-Type. There's a 30-second per-attempt timeout, and no retry unless you ask. Here is the whole args map at a glance — every public key, its default, and the section that goes deep on it:

| Key | Default | What it does |
|---|---|---|
| `:request` | **required** | The wire envelope — `:method` / `:url` / `:headers` / `:params` / `:body` / `:request-content-type` / `:credentials` and friends. See [the request is a map](#the-request-is-a-map). |
| `:decode` | `:auto` | How to parse the 2xx body — a Malli schema, a keyword (`:json` / `:text` / `:blob` / `:array-buffer` / `:form-data`), or `(fn [text headers] decoded)`. Runs only on 2xx. |
| `:accept` | `{:ok decoded}` | Post-decode normaliser `(decoded → {:ok v} | {:failure m})` — lets a structurally valid 200 surface as a domain failure. See [`:accept`](#a-valid-200-can-still-be-a-failure-accept). |
| `:retry` | no retry | `{:on #{categories} :max-attempts N :backoff {…}}`. See [reads retry; writes don't](#reads-retry-writes-dont). |
| `:timeout-ms` | `30000` | Per-attempt wall-clock timeout. `nil` or `0` opts out. |
| `:on-success` | originating event + `:rf/reply` | Where the success reply lands. |
| `:on-failure` | originating event + `:rf/reply` | Where the failure reply lands. `nil` silences it (with a warning). |
| `:request-id` | none | A stable `=`-comparable id for abort + supersession. See [the search-box race, cured](#the-search-box-race-cured). |
| `:abort-signal` | none | An external `AbortController.signal`. CLJS-only. |
| `:sensitive?` | `false` | Marks body / headers / params / decoded value as sensitive for the trace stream. |
| `:rf.http/max-decoded-keys` | `10000` | Per-request cap on unique JSON object keys interned — the DoS guard for untrusted JSON. |

The full key-by-key contract — body thunks, multipart, the keyword-interning cap, per-host degradations — is [spec 014](../../../spec/014-HTTPRequests.md).

> **One-time setup.** Managed HTTP ships in its own artefact, `day8/re-frame2-http`, so apps that never issue a request build a bundle clean of it. Add the dep and require `re-frame.http.managed` once at app boot — that registers `:rf.http/managed` and family. Call a managed fx without the artefact loaded and the `re-frame.core` re-exports hand you `:rf.error/http-artefact-missing` rather than a silent miss.

### The request is a map

The `:request` map *is* the wire request, expressed as data. `:url` is the only required key inside it; everything else has a sane default. The keys are deliberately small and host-portable:

| Key | Default | Notes |
|---|---|---|
| `:method` | `:get` | `:get` / `:head` / `:post` / `:put` / `:patch` / `:delete` / `:options`. |
| `:url` | **required** | A string. Validated at dispatch time *after* the interceptor chain runs, so a base-URL interceptor is honoured. |
| `:headers` | none | Map of string → string (or string → vector for multi-valued). Names are case-insensitive. |
| `:params` | none | Map of query-string params. URL-encoded and merged onto `:url` for you — no manual `?a=1&b=2`. |
| `:body` | none | A Clojure collection, string, `FormData`, `Blob`, `ArrayBuffer`, or a **thunk** `(fn [] body)` invoked at send-time. |
| `:request-content-type` | none | `:json` / `:form` / `:text` / an explicit MIME. Sugar that both sets `Content-Type` and serialises `:body`. |
| `:credentials` | `:same-origin` | `:omit` / `:same-origin` / `:include`. CLJS-only; the JVM transport ignores it. |
| `:mode` `:redirect` `:cache` `:referrer` `:integrity` | — | Fetch passthroughs. All but `:redirect` are CLJS-only. |

So a real write — a POST with a JSON body, query params, and an auth header — is still just data:

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

`:request-content-type :json` runs the `:body` map through JSON serialisation and stamps `Content-Type: application/json`; `:form` URL-encodes it as a form body instead. For a file upload, hand a `js/FormData` straight in as `:body` and leave `:request-content-type` off — the platform sets the multipart boundary itself. And if a `:url` comes through blank, nil, or a non-string, the dispatch fails loudly with `:rf.error/http-bad-request` rather than letting a nil URL fall through to the transport as an opaque `:rf.http/transport` error.

> **Stamping a token on *every* request.** Threading `"Authorization"` into each call site by hand gets old fast — and breaks the moment a token rotates. Don't. Register one [HTTP interceptor](#middleware-stamp-every-request-once) instead and drop the header from your handlers entirely. The example above threads it inline only to show the envelope key exists.

## The reply is an event

There is no `await`. The handler — the function that runs in response to an event — never pauses to wait for the server and then resumes. The handler above returned a map and finished. When the response lands, the runtime dispatches a **new event**, with the reply appended as the last argument:

```clojure
(rf/reg-event :article/loaded
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :status]    :loaded)
             (assoc-in [:article :data]      (:article value))
             (assoc-in [:article :loaded-at] time-ms))}))

(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
             (assoc-in [:article :status] :error)
             (assoc-in [:article :error]  failure))}))
```

The success payload is `{:kind :success :value <decoded>}`. The failure payload is `{:kind :failure :failure <failure-map>}`. Three small handlers — issue, succeed, fail. Each does one thing, and the failure path has its own name instead of being an afterthought tacked onto the success path. Two details here are load-bearing, and they trip people up the first time:

- **`:loaded-at` comes from a declared coeffect, not the wall clock.** A coeffect is an input the framework hands the handler rather than something the handler reaches out and grabs. A durable timestamp must replay the same way every time, so the handler declares `:rf.cofx/requires [:rf/time-ms]` and reads the framework-stamped time straight from its coeffects map. Never call `(js/Date.now)` inside the handler.
- **The reply lands in the same frame the request went out from.** The fx threads the frame from the dispatch envelope through to the reply. The naive fetch's frame leak cannot happen here.

Why an event and not a resumed call? Because [app-db — your app's single state map — is the sum of an event ledger](events-and-the-cascade.md), and the ledger must contain everything that ever influenced state. An awaited value slips in through the call stack and leaves no line in the ledger. A reply event lands in the ledger — traceable, serializable, replayable. [Continuations are data](../explanation/continuations-are-data.md) is the essay-length why.

> **Do, observe.** Run the request with Xray open: the issuing event row, the request issuance and any retries on the trace stream, then the reply arriving as an ordinary event row of its own — two ledger entries, one round trip.

### The co-located form

Sometimes the request and reply really do belong together. Omit `:on-success` / `:on-failure` and the reply routes back to the *originating* event id, merged into the message under `:rf/reply`. One handler then serves both roles:

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

The request goes out the bottom branch; the answer comes in the top. Prefer two handlers by default — the separation reads more clearly and stays easy to test. Co-locate only when the reply logic is trivial and tightly coupled to the request.

> **Coming from re-frame v1?** Your `:http-xhrio`-style success/failure events map straight onto `:on-success` / `:on-failure` — the [migration page](../25-from-re-frame-v1.md) walks the translation.

## One envelope under every async surface

Learn this part once, because it pays everywhere. "A reply is an event" isn't just an HTTP convenience. It's one framework-wide contract, the **uniform reply envelope**, and every managed async surface completes through it: HTTP, [resources and mutations](server-state.md), [state-machine async work](machines.md), and [route loaders](routing.md). Those pages lean on this section instead of re-teaching it. The normative contract is [Managed-Effects](../../../spec/Managed-Effects.md).

The envelope has two pieces. A **reply target** says where completion is dispatched — canonically `:rf/reply-to` with an event-vector prefix. A **reply map** says what it carries. When the work completes, the runtime dispatches the target event with the reply map appended as the final argument:

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
| `:error` | Completed with a failure; reply is current. `:error` carries a family `:kind` — for HTTP, one of the `:rf.http/*` categories below. |
| `:cancelled` | Intentionally cancelled while still correlated with the target. `:cancel/reason` present. |
| `:stale` | Completed *after* its correlation became obsolete. The app target is never dispatched; **no app-state mutation happens**. |

The `{:kind :success …}` / `{:kind :failure …}` payloads your `:on-success` / `:on-failure` handlers receive are this same envelope in HTTP's clothing: `:kind :success` is `:status :ok`, and `:kind :failure` is `:status :error` with the failure under `:error`. One contract; HTTP just hands your handlers the shorter spelling.

Timeout is not its own status. It's `:status :error` with an error of `:kind :rf.http/timeout`. One fact, named once.

Four rules finish the tour:

- **Stale suppression is the correctness boundary.** A newer request supersedes an older one — that's the search-box race. The old completion is not delivered as `:ok`. It's classified `:stale`, the app target is skipped, and the trace records the carried-versus-current correlation. Your handler never sees a stale answer, so it can never overwrite fresh data with old. Cancellation is only an optimization here: a cancelled fetch may still produce a late host completion. Suppression is what actually keeps state correct.
- **Cancellation is data, not the absence of a reply.** A live user-cancel dispatches `:status :cancelled` with a `:cancel/reason`. A supersession suppresses as `:stale`. Either way there's a value describing what happened — never a silently dropped continuation.
- **Completion timestamps ride the reply.** A reply is a causal token. Facts like *when it completed* travel on it (`:completed-at`), and handlers derive durable timestamps from that carried data. The `:rf/time-ms` declaration above is this same rule, wearing HTTP's public payload shape.
- **HTTP's `:on-success` / `:on-failure` / `:rf/reply` are public sugar over this envelope.** They're what you write on HTTP — `:rf.http/managed` does not accept a bare `:rf/reply-to`. But the general async model, shared by resources, mutations, machines, and routing, is the envelope. Each surface picks its own public spelling; the substrate underneath is one.

> **For the categorically curious.** Effects *sequence but never bind*: a handler can ask for several effects in order (the `:fx` vector), but never "do this effect, *then* feed its result into the next expression" — that would be monadic binding, the awaited-value shape. The result comes back as the next event instead, and relocating a reply target is a pure data transform (the role `Cmd.map` plays in Elm's command algebra) — never a hidden callback.

## Failures are a closed set — and status comes before decode

Every failure carries a `:kind` from a fixed, framework-reserved list. Not a string — a keyword from a known set:

| `:kind` | When it fires |
|---|---|
| `:rf.http/transport` | Network / DNS / connection error before the HTTP transaction completed. |
| `:rf.http/cors` | CORS rejection. Browser-only. |
| `:rf.http/timeout` | The per-attempt timeout fired. |
| `:rf.http/http-4xx` | A 4xx response. The raw body rides at `:body` — decode is skipped on non-2xx. |
| `:rf.http/http-5xx` | A 5xx response. Same shape. |
| `:rf.http/decode-failure` | A 2xx response whose body the decode pipeline rejected. |
| `:rf.http/accept-failure` | Your `:accept` fn classified a structurally valid 200 as a domain failure. |
| `:rf.http/aborted` | Aborted via `:request-id` or abort signal. |

The set is closed for v1; adding a category requires a spec change. That constraint buys you something real. `:rf.http/timeout` means exactly the same thing in your codebase, in mine, and in every tool watching the trace stream. The RealWorld example's `failure->message` maps this vocabulary to user-facing strings in one place.

Now the rule that catches every newcomer once, so here it is up front: **decode runs only on 2xx responses — status is classified before the body is touched.** Picture a JSON endpoint behind a load balancer that 404s with an *HTML* error page. Instinct says decode failure. It isn't. It's `:rf.http/http-4xx` with the raw HTML at `:body`, because status was checked first and the decoder never ran. "The server said no" matters more than "and the no was shaped like HTML." If you want the structured error body many APIs return alongside a 4xx, decode `:body` yourself in the failure branch. The framework hands you the bytes and the status, on purpose.

There's a quieter classification fact worth pinning here too. An **empty (or whitespace-only) 2xx body is not a decode failure** — it's a parsed value of `nil`. The bare `204 No Content` (or a `200`-with-no-body) that a PUT or DELETE replies with is the common case, and it succeeds: `:decode :json` hands your `:on-success` `{:kind :success :value nil}`. A schema `:decode` then gets to decide whether `nil` is acceptable — `[:maybe …]` passes, a required `:map` rejects as an ordinary schema failure. This behaves identically on the browser and the JVM, on purpose, so a write handler that returns no content reads the same way everywhere.

## A valid 200 can still be a failure: `:accept`

Status before decode handles the wire. But some APIs answer `200 OK` and then tell you, *in the body*, that the thing you asked for isn't there — `{"article": null}` instead of a 404. The transport succeeded, the JSON parsed, the schema passed; by every wire signal this is a success. Your domain disagrees. That's what `:accept` is for: a post-decode normaliser that gets the last word on whether a structurally valid response is actually a success.

`:accept` is a function `(decoded → {:ok value} | {:failure failure-map})`. Return `{:ok v}` and `v` becomes the success payload; return `{:failure m}` and `m` rides into the failure path as a `:rf.http/accept-failure`, with your map at `:detail`:

```clojure
;; Adapted from examples/reagent/realworld — a 200 with a null article is a domain miss.
{:fx [[:rf.http/managed
       {:request {:method :get :url (str "/api/articles/" slug)}
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

- `:accept` runs **only after a successful 2xx decode** — step 4 in the pipeline, after status (step 2) and decode (step 3). A non-2xx never reaches it, so your `:accept` never has to think about HTTP status; that was already classified.
- An `:accept` that **throws**, or returns a **malformed shape** (nil, a non-map, a map with neither `:ok` nor `:failure`, or one with *both*) still dispatches a reply — it can never strand the caller. It classifies as `:rf.http/accept-failure` with a framework-supplied `:detail`, and the pre-`:accept` value rides at `:decoded` so you can see what it choked on.
- An accept-failure is **not retryable** and not a member of `:retry :on`. Retrying the transport won't change the body, so this is a domain decision, not a transport blip — if you need "retry after refreshing X", that's a [state machine](machines.md), not `:accept`.

## Reads retry; writes don't

You saw the retry shape above. `:on` is the set of categories that trigger a retry. `:max-attempts` is the total *including* the first try (so `1` means no retry). Backoff is exponential — `:base-ms` times `:factor` per attempt, capped at `:max-ms` — with optional `:jitter`, which adds ±25% randomness so a thousand clients don't retry in lockstep against your recovering server. Two behaviors are worth knowing. Only the final exhausted failure dispatches your failure handler — intermediate attempts are trace rows (`:rf.http/retry-attempt`), not events your code sees. And `:rf.http/aborted` is never retryable.

`:on` is a **closed set**, drawn only from the retryable categories: `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`. A category is in the set when re-issuing the *same* request could plausibly change the outcome. The first three are the obvious transient cases; 4xx and CORS are admitted because a real slice of them is transient too (`429 Too Many Requests`, `408`, a just-deploying CORS edge) — but most aren't, so they're opt-in and want a narrow `:max-attempts`. The other categories are non-retryable *by construction*: `:rf.http/decode-failure` would reproduce deterministically, `:rf.http/accept-failure` is your own domain verdict, and `:rf.http/aborted` must never re-issue (abort always wins). Put a non-retryable category — or anything outside the `:rf.http/*` namespace — in `:on` and the request is rejected at dispatch with `:rf.error/http-bad-retry-on`, rather than riding a useless policy for its lifetime. (A non-set `:on`, like a bare keyword or a vector, is rejected the same way — `contains?` over a vector tests indices, not values, so a vector would silently disable retry.)

The real discipline isn't *whether* to retry but *what*. Read-only fetches are safe: a transient blip on a GET is exactly what retry is for. User-initiated writes are not, because retrying a submit or a payment risks doing it twice. So the production shape is one shared policy for reads (`data-fetch-retry` above) and conspicuously *no* `:retry` on writes. The RealWorld example's login, register, and settings requests carry none.

One boundary to hold onto. `:retry` owns **transport retry** only — decisions that are a pure function of failure category and attempt count. The moment a retry decision depends on anything else ("after a 401, refresh the token, *then* retry"; "the body says retry-after 5s") it becomes **semantic retry**, and that belongs in a [state machine](machines.md) driving the request, with transport `:retry` still active inside each attempt the machine launches.

> **Coming from Axios?** `axios-retry`'s `retryCondition` ≈ `:on`, `retryDelay` ≈ `:backoff` — except here the policy is inspectable data at the call site and every attempt is a trace row, not closure state inside an interceptor.

## The search-box race, cured

Give a request a stable `:request-id` — any `=`-comparable value works, a keyword, a string, or a structural vector like `[:articles :load slug]` — and two things follow. A later `[:rf.http/managed-abort the-id]` fx can cancel it. And — the clever bit — issuing a *new* request with the same id automatically supersedes the old one. Supersession takes the stale path from the envelope tour: the old reply is suppressed, your handler never sees it, and only a trace row records it. So give every keystroke's search request the same `:request-id`, and the only reply you act on is the latest one. The race from the top of this page is gone, with zero lines of race-handling code.

```clojure
;; Every keystroke reuses the same :request-id, so each new search
;; supersedes the one before it. Only the latest reply ever lands.
(rf/reg-event :search/query-changed
  (fn [{:keys [db]} [_ q]]
    {:db (assoc db :search/q q)
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/search" :params {:q q}}
            :request-id :search/in-flight       ;; stable across keystrokes
            :decode     SearchResults
            :on-success [:search/results]
            :on-failure [:search/error]}]]}))
```

A *manual* abort is different from a supersession. `[:rf.http/managed-abort the-id]` aborts whichever request currently holds the id and *does* deliver a failure reply — `{:kind :rf.http/aborted, :reason :user}` — so a deliberate user-cancel can clear the spinner. A supersession suppresses silently (the new request *is* the cleanup); a manual abort speaks up (someone clicked "cancel"). The `:reason` discriminates them: `:user` for a manual abort, `:request-id-superseded` for a supersession (trace-only — it never reaches a handler). The [managed-http counter example](../../../examples/reagent/managed_http_counter/) demonstrates the manual-abort path end-to-end — plus the 404-is-not-a-decode-failure rule — in one small file.

> **Requests that die with their machine.** There's a third `:reason`, `:actor-destroyed`. A `:rf.http/managed` request issued from *inside* a spawned [state-machine](machines.md) actor is aborted automatically when that actor is destroyed — its outstanding work dies with it, no manual abort needed. Requests dispatched from ordinary event handlers have no such lifecycle peg and are not auto-cancelled; that's deliberate, and `:request-id` remains your app-level cancel handle for them.

### Silencing a reply

Set `:on-success` or `:on-failure` to `nil` and that reply is dropped — fire-and-forget, useful for a telemetry beacon you genuinely don't care to handle. But the framework won't let you *accidentally* swallow an error: the first time a non-aborted failure is dropped by `:on-failure nil`, a one-shot `:rf.warning/failure-swallowed` trace fires (dev-only) so the silence is observable rather than invisible. Aborted requests are excluded — a cancelled request that no longer wants its reply is correct-by-design silence, not a bug.

## Fewer keys at the call site: the verb helpers

The canonical `[:rf.http/managed {:request {:method :get :url …} …}]` vector is always correct — but typing `{:request {:method :get :url …}}` on every call gets repetitive. The `re-frame.http` namespace ships a pure synthesis fn per HTTP verb that builds the same vector from a URL plus an optional args map:

```clojure
(:require [re-frame.http :as rf.http])

;; These two are exactly equal:
[:rf.http/managed {:request {:method :get :url "/api/items"} :on-success [:items/loaded]}]
(rf.http/get "/api/items" {:on-success [:items/loaded]})
```

Every verb is there — `rf.http/get` / `post` / `put` / `delete` / `patch` / `head` / `options` — each in a one-arg (`url`) and two-arg (`url args`) form. The helper pins `:method` and `:url` for you; everything else in the args map (`:decode`, `:retry`, `:on-success`, and the `:request` sub-keys like `:body` and `:headers`) passes straight through:

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

## Middleware: stamp every request once

Threading `"Authorization"` into every call site is the kind of cross-cutting concern that belongs in one place, not sprinkled across handlers. re-frame2 ships a **per-frame HTTP interceptor chain** for exactly this — the same `{:before :after}` onion you know from [event interceptors](interceptors.md), but wrapping the transport instead of the event handler. A `:before` transforms the request on its way out; an `:after` transforms the reply on its way back.

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

The `:before` fn receives a ctx of `{:request :args :frame :event}` and returns a ctx whose `:request` is the modified envelope. The `:after` fn is `(fn [ctx response] response')` — it sees the *same* ctx its `:before` produced (so a `:before` that stamps a start-time lets the `:after` compute an elapsed delta with no app state), plus the `{:kind :success …}` / `{:kind :failure …}` response, and returns a possibly-transformed response that the `:on-success` / `:on-failure` dispatch then carries. That ctx-carried-forward shape is what makes per-request concerns — response-time telemetry, rate-limit header parsing, flagging a 401 for an auth refresh — single-interceptor jobs.

A few rules that matter:

- **Chains are per-frame.** An interceptor registered on one [frame](frames.md) never fires for a request from another. Multi-frame apps register independent chains.
- **Onion order.** `:before`s run in registration order, `:after`s in reverse — A-registered-before-B means `A.before → B.before → transport → B.after → A.after`. Exactly the event-interceptor mental model.
- **At least one phase is required.** A map with neither `:before` nor `:after` is rejected at registration with `:rf.error/http-bad-interceptor`. A `:before`-only or `:after`-only interceptor is fine and composes cleanly.
- **A throw is named, not swallowed.** A `:before` or `:after` that throws classifies as `:rf.error/http-interceptor-failed` (carrying the offending `:interceptor-id`); a request-side throw means the transport never sees the request. Wrap recoverable logic inside the interceptor yourself — the chain has no recovery cofx.
- **Clearing.** `(rf/clear-http-interceptor id)` removes the slot (single-frame); `(rf/clear-http-interceptor frame-id id)` targets a frame. Re-registering an existing id replaces it *in place* (hot-reload-friendly); clear-then-reg appends a fresh slot at the end.

`reg-http-interceptor` and `clear-http-interceptor` are the only two HTTP surfaces re-exported onto the `rf/` facade (everything else lives in `re-frame.http` / `re-frame.http.managed`). This same seam is where resources and mutations get *their* request decoration too — register the auth interceptor once and every `:rf.http/managed` request, whether you issued it directly or a [resource](server-state.md) did, carries the header. The normative contract is [spec 014 §Middleware](../../../spec/014-HTTPRequests.md#middleware).

## When not to reach for it

Managed HTTP is the right tool for a single request that gets a single reply. Here's where it isn't, and what to reach for instead:

- **The same server data read on several screens, with caching and invalidation** — that's a [resource](server-state.md), a declared, cached read of server state that rides this transport underneath. Declare it once. Hand-rolling `:loaded-at` freshness checks across features means you've outgrown raw requests.
- **Streaming, WebSockets, SSE** — out of scope for the single-request/single-reply shape. There is no managed streaming surface yet; that's an honest gap, not a hidden feature.
- **Wire-level weirdness** (custom transports, exotic binary protocols) — register your own fx; the escape hatch is always there.
- **Testing** needs no network: the canned-stub fxs (`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`, registered by requiring the sibling `re-frame.http.test-support` namespace) synthesize a reply with the exact envelope a live request produces — see [testing a full cascade](../how-to/test-a-cascade.md).

---

**You can now:**

- Issue an HTTP request as data from a pure handler, and handle its reply as an ordinary event — request, success, and failure each named.
- Read any managed async completion in this framework: forms, resources, machines, and routes all reply through the same envelope and the same closed status set.
- Issue any verb with headers, query params, and a serialised body — by hand or through the `rf.http/get` / `post` / … helpers — and surface a structurally-valid-but-wrong 200 as a failure with `:accept`.
- Predict how a failure classifies (status before decode, closed `:rf.http/*` kinds, empty 2xx body is `nil`) and where a stale or cancelled reply goes (suppressed; never into your reducer).
- Choose retry policy deliberately: reads retry from the closed `:on` set, writes don't, semantic retry graduates to a machine.
- Stamp cross-cutting concerns — auth, correlation ids, telemetry — onto every request from one `reg-http-interceptor`, instead of threading them per call site.
