# HTTP: the managed request

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

Almost everything here is optional, which means the common case stays short. The only required key is `:request` with a `:url`. `:method` defaults to `:get`. `:decode` defaults to `:auto`, which sniffs the Content-Type. There's a 30-second per-attempt timeout, and no retry unless you ask. The full key-by-key contract — body thunks, multipart, credentials, the keyword-interning cap for untrusted JSON — is [spec 014](../../../spec/014-HTTPRequests.md).

!!! note "One-time setup"

    Managed HTTP ships in its own artefact, `day8/re-frame2-http`, so apps that never issue a request build a bundle clean of it. Add the dep and require `re-frame.http.managed` once at app boot — that registers `:rf.http/managed` and family.

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

!!! note "Do, observe"

    Run the request with Xray open: the issuing event row, the request issuance and any retries on the trace stream, then the reply arriving as an ordinary event row of its own — two ledger entries, one round trip.

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

??? note "For the categorically curious"

    Effects *sequence but never bind*: a handler can ask for several effects in order (the `:fx` vector), but never "do this effect, *then* feed its result into the next expression" — that would be monadic binding, the awaited-value shape. The result comes back as the next event instead, and relocating a reply target is a pure data transform (the role `Cmd.map` plays in Elm's command algebra) — never a hidden callback.

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

## Reads retry; writes don't

You saw the retry shape above. `:on` is the set of categories that trigger a retry. `:max-attempts` is the total *including* the first try. Backoff is exponential, with optional jitter, which stops a thousand clients retrying in lockstep against your recovering server. Two behaviors are worth knowing. Only the final exhausted failure dispatches your failure handler — intermediate attempts are trace rows, not events your code sees. And `:rf.http/aborted` is never retryable.

The real discipline isn't *whether* to retry but *what*. Read-only fetches are safe: a transient blip on a GET is exactly what retry is for. User-initiated writes are not, because retrying a submit or a payment risks doing it twice. So the production shape is one shared policy for reads (`data-fetch-retry` above) and conspicuously *no* `:retry` on writes. The RealWorld example's login, register, and settings requests carry none.

One boundary to hold onto. `:retry` owns **transport retry** only — decisions that are a pure function of failure category and attempt count. The moment a retry decision depends on anything else ("after a 401, refresh the token, *then* retry"; "the body says retry-after 5s") it becomes **semantic retry**, and that belongs in a [state machine](machines.md) driving the request, with transport `:retry` still active inside each attempt the machine launches.

> **Coming from Axios?** `axios-retry`'s `retryCondition` ≈ `:on`, `retryDelay` ≈ `:backoff` — except here the policy is inspectable data at the call site and every attempt is a trace row, not closure state inside an interceptor.

## The search-box race, cured

Give a request a stable `:request-id` and two things follow. A later `[:rf.http/managed-abort the-id]` can cancel it. And — the clever bit — issuing a *new* request with the same id automatically supersedes the old one. Supersession takes the stale path from the envelope tour: the old reply is suppressed, your handler never sees it, and only a trace row records it. So give every keystroke's search request the same `:request-id`, and the only reply you act on is the latest one. The race from the top of this page is gone, with zero lines of race-handling code. A *manual* abort is different: it does deliver a failure reply (`:kind :rf.http/aborted`, `:reason :user`), so a deliberate user-cancel can clean up the spinner. The [managed-http counter example](../../../examples/reagent/managed_http_counter/) demonstrates the manual-abort path end-to-end — plus the 404-is-not-a-decode-failure rule — in one small file.

## When not to reach for it

Managed HTTP is the right tool for a single request that gets a single reply. Here's where it isn't, and what to reach for instead:

- **The same server data read on several screens, with caching and invalidation** — that's a [resource](server-state.md), a declared, cached read of server state that rides this transport underneath. Declare it once. Hand-rolling `:loaded-at` freshness checks across features means you've outgrown raw requests.
- **Streaming, WebSockets, SSE** — out of scope for the single-request/single-reply shape. There is no managed streaming surface yet; that's an honest gap, not a hidden feature.
- **Wire-level weirdness** (custom transports, exotic binary protocols) — register your own fx; the escape hatch is always there.
- **Testing** needs no network: the canned-stub fxs (`:rf.http/managed-canned-success` / `-failure`, registered by requiring the sibling `re-frame.http.test-support` namespace) synthesize a reply with the exact envelope a live request produces — see [testing a full cascade](../how-to/test-a-cascade.md).

---

**You can now:**

- Issue an HTTP request as data from a pure handler, and handle its reply as an ordinary event — request, success, and failure each named.
- Read any managed async completion in this framework: forms, resources, machines, and routes all reply through the same envelope and the same closed status set.
- Predict how a failure classifies (status before decode, closed `:rf.http/*` kinds) and where a stale or cancelled reply goes (suppressed; never into your reducer).
- Choose retry policy deliberately: reads retry, writes don't, semantic retry graduates to a machine.
