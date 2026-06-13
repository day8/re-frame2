# HTTP: the managed request

> **Who this is for.** You've read [effects and coeffects](effects-and-coeffects.md) and you're about to talk to a server. If you know Axios with `axios-retry`, or RTK Query's `fetchBaseQuery` — the configured transport that sits *under* the cache — this page is re-frame2's version of that layer. Two deliberate divergences from both: completion comes back as an **event**, never an awaited value; and failure is a **closed keyword vocabulary**, never whatever the exception stringified to. (Caching, staleness, and invalidation live one layer up, in [resources](server-state.md) — this page is the transport they ride.)

**The takeaway: a reply is an event, not a resumed stack frame.**

## The fetch you write the first time

```clojure
;; The honeymoon version — do not ship this.
(-> (js/fetch "/api/articles/welcome")
    (.then #(.json %))
    (.then #(rf/dispatch [:article/loaded %])))
```

Clean, readable, ships on Tuesday — and it is a lie of omission. Walk through what's *not* there. No error handling, so a 500 lands in `:article/loaded` as garbage. No distinction between "the server said no" (a 404) and "the network is on fire" (DNS failed). No loading state. No timeout, so a black-hole server hangs forever. No retry, so a transient blip is a hard failure. No abort, so when the user types five letters into a search box, five requests race and *whichever lands last wins* — frequently not the one for the last letter typed. No way to test it without a real network or a hand-rolled mock. And one more: that `rf/dispatch` fires from inside a `.then` continuation — a fresh stack, long after the [frame](frames.md) it ran under unwound — so it carries no frame context and fails loudly with `:rf.error/no-frame-context`.

Seven sins and a frame leak in three lines. The fix is not more careful fetch code on every screen; it's one effect that already thought about all of it.

## A request is data

`:rf.http/managed` is a registered effect whose args map describes an HTTP request *as data*. You return the map; the runtime issues the request, decodes the body, retries with backoff if asked, classifies every failure into a named category, and dispatches the reply back into your app as an ordinary event. You never touch `js/fetch`.

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

(rf/reg-event-fx :article/load
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

Almost everything is optional. The only required key is `:request` with a `:url`; `:method` defaults to `:get`, `:decode` defaults to `:auto` (sniffs the Content-Type), there's a 30-second per-attempt timeout, and no retry unless you ask. The full key-by-key contract — body thunks, multipart, credentials, the keyword-interning cap for untrusted JSON — is [spec 014](../../../spec/014-HTTPRequests.md).

> **One-time setup.** Managed HTTP ships in its own artefact, `day8/re-frame2-http`, so apps that never issue a request build a bundle clean of it. Add the dep and require `re-frame.http-managed` once at app boot — that registers `:rf.http/managed` and family.

## The reply is an event

There is no `await` — no point where the handler *pauses* until the server answers and resumes. The handler above returned a map and finished. When the response lands, the runtime dispatches a **new event** with the reply appended as the last argument:

```clojure
(rf/reg-event-fx :article/loaded
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :status]    :loaded)
             (assoc-in [:article :data]      (:article value))
             (assoc-in [:article :loaded-at] time-ms))}))

(rf/reg-event-db :article/load-error
  (fn [db [_ {:keys [failure]}]]
    (-> db
        (assoc-in [:article :status] :error)
        (assoc-in [:article :error]  failure))))
```

The success payload is `{:kind :success :value <decoded>}`; the failure payload is `{:kind :failure :failure <failure-map>}`. Three small handlers — issue, succeed, fail — each single-purpose, with the failure path *named* rather than an afterthought. Two details are load-bearing:

- **`:loaded-at` comes from a declared coeffect, not the wall clock.** A durable timestamp must replay faithfully, so the handler declares `:rf.cofx/requires [:rf/time-ms]` and reads the framework-stamped time flat from its coeffects map — never a fresh `(js/Date.now)` inside the handler.
- **The reply lands in the same frame the request went out from.** The fx threads the frame from the dispatch envelope through to the reply — the naive fetch's frame leak cannot happen here.

Why an event and not a resumed call? Because [app-db is the sum of an event ledger](events-and-the-cascade.md), and the ledger must contain everything that ever influenced state. An awaited value slips in through the call stack, leaving no line; a reply event lands in the ledger — traceable, serializable, replayable. [Continuations are data](../explanation/continuations-are-data.md) is the essay-length why.

> **Do, observe.** Run the request with Xray open: the issuing event row, the request issuance and any retries on the trace stream, then the reply arriving as an ordinary event row of its own — two ledger entries, one round trip.

### The co-located form

When request and reply genuinely belong together, omit `:on-success` / `:on-failure` and the reply routes back to the *originating* event id, merged into the message under `:rf/reply`. One handler serves both roles:

```clojure
;; From examples/reagent/managed_http_counter (core.cljs), condensed.
(rf/reg-event-fx :counter/+1
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

Request goes out the bottom branch; answer comes in the top. Prefer two handlers by default; co-locate when the reply logic is trivial and tightly coupled to the request.

> **Coming from re-frame v1?** Your `:http-xhrio`-style success/failure events map straight onto `:on-success` / `:on-failure` — the [migration page](../25-from-re-frame-v1.md) walks the translation.

## One envelope under every async surface

Here is the part worth learning once, because it pays everywhere. "A reply is an event" is not an HTTP convenience — it is one framework-wide contract, the **uniform reply envelope**, and every managed async surface completes through it: HTTP, [resources and mutations](server-state.md), [state-machine async work](machines.md), and [route loaders](routing.md). Those pages lean on this section rather than re-teaching it; the normative contract is [Managed-Effects](../../../spec/Managed-Effects.md).

The envelope has two pieces: a **reply target** (where completion is dispatched — canonically `:rf/reply-to` with an event-vector prefix) and a **reply map** (what it carries). On live completion the runtime dispatches the target event with the reply map appended as the final argument:

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

The `{:kind :success …}` / `{:kind :failure …}` payloads your `:on-success` / `:on-failure` handlers receive above are this same envelope in HTTP's public clothing: `:kind :success` is `:status :ok`, and `:kind :failure` is `:status :error` with the failure category under `:error`. One contract; HTTP just hands your handlers the friendlier spelling.

Timeout is not a status — it's `:status :error` with an error of `:kind :rf.http/timeout`. One fact, named once.

Four rules complete the tour:

- **Stale suppression is the correctness boundary.** When a newer request supersedes an older one (the search-box race), the old completion is not delivered as `:ok` — it is classified `:stale`, the app target is skipped, and the trace records the carried-versus-current correlation. Your handler never sees a stale answer, so it can never overwrite fresh data with old. **Cancellation is only an optimization** — a cancelled fetch may still produce a late host completion; suppression is what keeps state correct.
- **Cancellation is data, not the absence of a reply.** A live user-cancel dispatches `:status :cancelled` with a `:cancel/reason`; a supersession suppresses as `:stale`. Either way there is a value describing what happened — never a silently dropped continuation.
- **Completion timestamps ride the reply.** A reply is a causal token: facts like *when it completed* travel on it (`:completed-at`), and handlers derive durable timestamps from carried data — the `:rf/time-ms` declaration above is this rule wearing HTTP's public payload shape.
- **HTTP's `:on-success` / `:on-failure` / `:rf/reply` are public sugar that lowers onto this envelope.** They're what you write on HTTP — `:rf.http/managed` does not accept a bare `:rf/reply-to` — but the general async model, shared by resources, mutations, machines, and routing, is the envelope. Each surface chooses its public spelling; the substrate is one.

<details markdown="1">
<summary>For the categorically curious</summary>

Effects *sequence but never bind*: a handler can ask for several effects in order (the `:fx` vector), but never "do this effect, *then* feed its result into the next expression" — that would be monadic binding, the awaited-value shape. The result comes back as the next event instead, and relocating a reply target is a pure data transform (the role `Cmd.map` plays in Elm's command algebra) — never a hidden callback.
</details>

## Failures are a closed set — and status comes before decode

Every failure carries a `:kind` from a fixed, framework-reserved vocabulary. Not a string; a keyword from a known list:

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

The set is closed for v1 — adding a category requires a spec change. That buys something real: `:rf.http/timeout` means *exactly the same thing* in your codebase, in mine, and in every tool watching the trace stream. The RealWorld example's `failure->message` maps this vocabulary to user-facing strings in one place.

Now the rule that catches every newcomer exactly once: **decode runs only on 2xx responses — status is classified before the body is touched.** Picture a JSON endpoint behind a load balancer that 404s with an *HTML* error page. Instinct says decode failure; wrong — it's `:rf.http/http-4xx` with the raw HTML at `:body`, because status was checked first and the decoder never ran. "The server said no" matters more than "and the no was shaped like HTML." Want the structured error body many APIs return alongside a 4xx? Decode `:body` yourself in the failure branch — the framework hands you the bytes and the status, on purpose.

## Reads retry; writes don't

You saw the retry shape above: `:on` is the set of categories that trigger a retry, `:max-attempts` is the total *including* the first try, and backoff is exponential with optional jitter (which stops a thousand clients retrying in lockstep against your recovering server). Two behaviors worth knowing: only the final exhausted failure dispatches your failure handler — intermediate attempts are trace rows, not events your code sees — and `:rf.http/aborted` is never retryable.

The real discipline isn't *whether* to retry but *what*. Read-only fetches are safe — a transient blip on a GET is exactly what retry is for. User-initiated writes are not: retrying a submit or a payment risks doing it twice. So the production shape is one shared policy for reads (`data-fetch-retry` above) and conspicuously *no* `:retry` on writes — the RealWorld example's login, register, and settings requests carry none.

One boundary: `:retry` owns **transport retry** only — decisions that are a pure function of failure category and attempt count. The moment a retry decision depends on anything else ("after a 401, refresh the token, *then* retry"; "the body says retry-after 5s") it is **semantic retry**, and it belongs in a [state machine](machines.md) driving the request, with transport `:retry` still active inside each attempt the machine launches.

> **Coming from Axios?** `axios-retry`'s `retryCondition` ≈ `:on`, `retryDelay` ≈ `:backoff` — except here the policy is inspectable data at the call site and every attempt is a trace row, not closure state inside an interceptor.

## The search-box race, cured

Give a request a stable `:request-id` and two things follow: a later `[:rf.http/managed-abort the-id]` can cancel it, and — the clever bit — issuing a *new* request with the same id automatically supersedes the old one. Supersession takes the stale path from the envelope tour: the old reply is suppressed, your handler never sees it, only a trace row records it. Give every keystroke's search request the same `:request-id` and the only reply you act on is the latest one — the race from the top of this page is gone, with zero lines of race-handling code. A *manual* abort, by contrast, does deliver a failure reply (`:kind :rf.http/aborted`, `:reason :user`) so a deliberate user-cancel can clean up the spinner. The [managed-http counter example](../../../examples/reagent/managed_http_counter/) demonstrates the manual-abort path end-to-end — plus the 404-is-not-a-decode-failure rule — in one small file.

## When not to reach for it

- **The same server data read on several screens, with caching and invalidation** — that's a [resource](server-state.md), declared once and riding this transport underneath. Hand-rolling `:loaded-at` freshness checks across features means you've outgrown raw requests.
- **Streaming, WebSockets, SSE** — out of scope for the single-request/single-reply shape. There is no managed streaming surface yet; that's an honest gap, not a hidden feature.
- **Wire-level weirdness** (custom transports, exotic binary protocols) — register your own fx; the escape hatch is always there.
- **Testing** needs no network: the canned-stub fxs (`:rf.http/managed-canned-success` / `-failure`, registered by requiring the sibling `re-frame.http-test-support` namespace) synthesize a reply with the exact envelope a live request produces — see [testing a full cascade](../how-to/test-a-cascade.md).

---

**You can now:**

- Issue an HTTP request as data from a pure handler, and handle its reply as an ordinary event — request, success, and failure each named.
- Read any managed async completion in this framework: forms, resources, machines, and routes all reply through the same envelope and the same closed status set.
- Predict how a failure classifies (status before decode, closed `:rf.http/*` kinds) and where a stale or cancelled reply goes (suppressed; never into your reducer).
- Choose retry policy deliberately: reads retry, writes don't, semantic retry graduates to a machine.

**Next:** [Server state: resources](server-state.md) — the cache layer over this transport · [No await: continuations are data](../explanation/continuations-are-data.md) — the deep why.
