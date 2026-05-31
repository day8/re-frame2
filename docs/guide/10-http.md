# 10 - HTTP

Network calls are where naive apps go to die. Not because a single fetch is hard — `fetch('/api/thing')` is one line a child could write — but because a *real* request has eight states, not two, and the seven you didn't think about are the ones that ship. This chapter is about one managed-effect shape that already thought about all eight, so you don't have to re-think them on every screen, in every project, for the rest of your career.

## The fetch you write, and the fetch you wish you'd written

Let me show you the request everyone writes the first time, because it's instructive in exactly how it's wrong.

```clojure
;; The honeymoon version.
(-> (js/fetch "/api/counter")
    (.then #(.json %))
    (.then #(rf/dispatch [:counter/loaded %])))
```

There it is. Clean, readable, ships on Tuesday. And it is a lie of omission so complete that I genuinely admire it. Walk through what's *not* there. There's no error handling, so a 500 lands in `:counter/loaded` as garbage and your view renders garbage. There's no distinction between "the server said no" (a 404) and "the network is on fire" (DNS failed) — both vanish into the same unhappy `.catch` you didn't write. There's no loading state, so the user stares at stale data wondering if they clicked the button. There's no timeout, so a request to a black-hole server hangs forever. There's no retry, so a transient blip is a hard failure. There's no abort, so when the user types five letters into a search box, five requests race and *whichever one lands last wins* — which is, with delicious frequency, not the one for the last letter they typed. And there's no way to test it without a real network or a hand-rolled mock that goes stale the instant the response shape moves.

That's seven sins in three lines. The honeymoon version isn't *wrong*, exactly — it's *unfinished*, and the finishing is the entire job. Here's the same request, done:

```clojure
(rf/reg-event-fx :counter/load
  (fn [{:keys [db]} _]
    {:db (assoc db :counter/status :loading)
     :fx [[:rf.http/managed
           {:request {:url "/api/counter"}
            :decode  CounterResponse}]]}))
```

That's it. That handler has retry available, abort available, a timeout, status-before-decode error classification, schema-validated decoding, a closed set of named failure categories, and a stub story for tests — and you can see exactly *none* of that machinery, because the machinery isn't yours. You returned a map. The runtime did the rest. The rest of this chapter is what "the rest" is, and why it's worth understanding even though you'll rarely touch it.

## A request is an effect, described as data

If you've read [chapter 07 on effects and coeffects](07-effects-and-coeffects.md), you already have the whole frame for this and you might not realise it. The thesis of that chapter was: your handler stays pure by *describing* the side effects it wants as data, and the runtime actions them at domino three. `{:fx [[:localstorage/set ...]]}` doesn't write to localStorage; it's a sticky note that says "please write to localStorage," and the runtime reads the note. HTTP is the same move, scaled up to the messiest side effect there is.

`:rf.http/managed` is a registered effect — an fx, exactly like the ones you met in chapter 07 — whose args map describes an HTTP request *as data*. You hand it a map: here's the URL, here's the method, here's the body, here's how to decode the reply, here's when to retry. The runtime's side of that effect issues the actual request, decodes the body, runs retry-with-backoff if you asked for it, sorts every possible failure into a closed set of named categories, and dispatches the reply back into your app as an ordinary event. You write no fetch. You write no `.then` chain. You never touch `js/fetch` or `java.net.http.HttpClient`. You return a map and walk away, and the cascade brings the answer back to you later.

Here's the smallest interesting version — a button that asks the server for an increment and applies the reply to the counter from [chapter 03](03-first-app.md):

```clojure
(rf/reg-event-fx :counter/+1
  (fn [{:keys [db]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply branch — the answer came back.
      {:db (update db :count + (-> reply :value :delta))}

      ;; Initial branch — issue the managed request.
      {:fx [[:rf.http/managed
             {:request {:url "/api/inc.json"}}]]})))
```

Two lines of fx and not one of them mentions the network. No decode (the default sniffs the `Content-Type`). No method (the default is `:get`). No reply addressing (the default sends the reply *back to this same handler*, with the answer tucked under `:rf/reply`). That last default is the one that makes the shape sing, so look at it twice: the *same* event id handles both the outgoing request and the incoming reply, and you tell them apart by asking `(:rf/reply msg)`. One handler, two roles. Request goes out the bottom branch; answer comes in the top.

That co-location — request and reply living in one handler — is the reason `:rf.http/managed` exists at all instead of "register your own `:http` fx and invent your own conventions like everyone always did." It makes the shape *uniform across every app that uses it*. And uniformity, it turns out, is the entire point. Hold that thought; I'm going to earn it.

## Why this exists at all (a short history of everybody reinventing the same thing)

Here's the embarrassing part: we kept *rewriting this*. Every re-frame v1 app anyone shipped, every consulting codebase anyone audited, every open-source app anyone cribbed from — same five lines of homemade `:http` fx, registered fresh in every project, with subtly different opinions quietly baked in. [Chapter 07 sketches](07-effects-and-coeffects.md) how you'd register your own fx that calls `fetch` and dispatches a follow-up event. That works! It's a fine exercise! It is also the exact thing every single team reinvented, and every team's version drifted in a *slightly* different direction on the same handful of questions:

- **Where does the response shape live?** Some teams handed the success handler `{:status 200 :body ...}`. Some unwrapped `:body` immediately. Some handed over the raw `Response` object and let the handler sort it out. Three teams, three shapes, and cross-team code-sharing died right there at the seam.
- **How do you classify a 4xx versus a 5xx?** Some treated any non-2xx as failure. Some routed 401 through a special auth path. Some let the success handler branch on the status code and discovered the bug three releases later when a 304 quietly fell through a `cond`.
- **Transport error, decode error, or HTTP error?** Usually nobody distinguished them. `(.catch ...)` swallowed all three into one `:on-error` with a string, the string got `console.log`'d, and the build shipped.
- **Do retries exist?** Sometimes! Hand-rolled, per-endpoint, often inconsistent about whether the retry clears the loading flag between attempts, often forgetting it counts against the rate limit.
- **How do you abort a stale request?** Mostly you didn't, and search-as-you-type raced its way to the wrong answer.
- **How do you stub it in a test?** Each team grew its own mock, and the mock went stale the day the response shape changed.

Every one of those answers was *reasonable in isolation*. The catastrophe was collective: nothing composed. A pair tool couldn't introspect "an HTTP request" in your app because there was no such thing in the framework — only a thousand local variations on the theme, none of which the tooling had ever heard of.

So re-frame2 picked one canonical answer to each question and shipped it as `:rf.http/managed`. Adopt it and you get retry, abort, frame-aware reply addressing, schema-driven decode, a closed `:rf.http/*` failure vocabulary, status-before-decode classification, and test stubs — *as one uniform surface*. Pair tools introspect that surface. Conformance fixtures grade against it. AI scaffolds emit code that fits it. The lower-level escape hatch — write your own fx — is still right there for the genuinely weird cases (custom transports, raw bytes, idiosyncratic binary protocols). But the common case, which is ninety-five percent of all the HTTP you'll ever write, has exactly one shape now, and everything in the ecosystem already knows it.

## The request map, key by key

The args map is small, and almost all of it is optional. The only thing you *must* supply is `:request` with a `:url` in it. Everything else has a default that's right more often than not.

```clojure
{:request    {:method  :post
              :url     "/api/counter"
              :params  {:scope "session"}
              :body    {:delta 1}
              :request-content-type :json
              :headers {"X-Trace" "abc"}}
 :decode     CounterResponse                  ;; Malli schema | keyword | fn | :auto
 :accept     (fn [decoded] {:ok decoded})     ;; default: success on 2xx
 :retry      {:on #{:rf.http/transport :rf.http/http-5xx}
              :max-attempts 4
              :backoff      {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}
 :timeout-ms 30000
 :on-success [:counter/loaded]                ;; default: back to the originator
 :on-failure [:counter/load-error]            ;; default: back to the originator
 :request-id :counter/load                    ;; for abort + supersede
 :abort-signal external-controller-signal}
```

Most of that, on most requests, you leave off. But two pieces are worth narrating because they're where the design decisions live.

### `:request` is the wire shape, and `:body` can be lazy

`:method` defaults to `:get`; `:url` is required; `:params` get URL-encoded and merged onto the URL. The interesting key is `:body`. It can be a Clojure collection (encoded per `:request-content-type`), a string, a `Blob` / `FormData` / `ArrayBuffer` — or **a thunk, `(fn [] body)`**, which the runtime calls *just before sending*. The thunk earns its keep in three situations: when serialising the body is expensive and you don't want to pay for it until the request is actually about to ship; when the body is a single-shot stream that a retry would need a *fresh* handle for; and when the body's contents are only knowable at send time — a fresh CSRF token, a current timestamp, the latest snapshot of some state that's still changing.

```clojure
:body (fn [] (build-large-payload (current-state)))
```

Each retry re-invokes the thunk, so retries don't ship a stale or already-consumed body. The headers, credentials, redirect mode, and the CLJS-only Fetch passthroughs (`:mode`, `:cache`, `:referrer`, `:integrity`) all live inside `:request` too. On the JVM those CLJS-only keys are harmless no-ops — one `:rf.http/cljs-only-key-ignored-on-jvm` trace per occurrence and nothing else.

### Default reply addressing — the co-located handler

When you leave off `:on-success` and `:on-failure`, the fx grabs the originating event id out of the dispatch envelope and, when the reply lands, dispatches:

```clojure
[<originating-event-id> (assoc original-msg :rf/reply <reply-payload>)]
```

— which is to say, *back to you*, with the answer merged in under `:rf/reply`. So a fuller handler with both branches looks like this:

```clojure
(rf/reg-event-fx :counter/load
  (fn [{:keys [db]} [_ {:as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply path — branch on what came back.
      (case (:kind reply)
        :success
        {:db (-> db
                 (assoc :counter/status :loaded)
                 (assoc :count (-> reply :value :count)))}

        :failure
        {:db (-> db
                 (assoc :counter/status :error)
                 (assoc :counter/error  (:failure reply)))})

      ;; Initial path — fire the request, show the loading state.
      {:db (-> db
               (assoc :counter/status :loading)
               (assoc :counter/error  nil))
       :fx [[:rf.http/managed
             {:request {:url "/api/counter"}
              :decode  CounterResponse}]]})))
```

The reply payload's outer shape is `{:kind :success :value v}` or `{:kind :failure :failure m}`, and inside a failure the `:kind` under `:failure` names the precise category. This co-located form is the default because it keeps the whole round-trip in one place you can read top to bottom.

When co-location *doesn't* fit — auth flows that already live in a state machine ([chapter 12](12-machines.md)), or endpoints whose success and failure paths diverge so hard that one handler would be a mess — you supply explicit `:on-success` / `:on-failure` event vectors and the reply payload gets appended as the last argument. Pass `nil` to either one to silence it entirely (the move for fire-and-forget telemetry beacons, where nobody's waiting for the answer).

## Failures are a closed set, and that's the gift

Here's where the design pays for itself. Every failure that `:rf.http/managed` produces carries a `:kind` keyword from a *fixed, framework-reserved* `:rf.http/*` vocabulary. Not a string. Not whatever-the-exception-stringified-to. A keyword from a known list:

| `:kind` | When it fires |
|---|---|
| `:rf.http/transport` | Network, DNS, or connection error *before* the HTTP transaction even completed. |
| `:rf.http/cors` | CORS preflight rejected. CLJS-only. |
| `:rf.http/timeout` | The per-attempt timeout fired. |
| `:rf.http/http-4xx` | A 4xx response. The raw body is at `:body` — decode is *skipped* on non-2xx. |
| `:rf.http/http-5xx` | A 5xx response. Same shape as `:http-4xx`. |
| `:rf.http/decode-failure` | A 2xx response whose body the decode pipeline rejected. |
| `:rf.http/accept-failure` | Your `:accept` function returned `{:failure user-map}`. |
| `:rf.http/aborted` | The request was aborted via `:request-id` or `:abort-signal`. |

This vocabulary is *closed for v1* — adding a category requires a spec change, not a library release. Which sounds like bureaucracy until you realise what it buys: the word `:rf.http/timeout` means *exactly the same thing* in your codebase, in mine, in the realworld example, and in the pair tool that's watching your trace bus. "Failure" stops being a project-local string everyone has to re-learn and becomes a shared noun.

## The decode pipeline, and the one rule that bites everyone

`:decode` controls how the response body gets parsed, and it takes four shapes: a Malli schema (the canonical form — `:decode CounterResponse` parses by content-type, runs Malli's `decode`, and hands the validated value on); a keyword to force a format (`:json`, `:text`, `:blob`, `:array-buffer`, `:form-data`, no Malli step); a function `(fn [body-text headers] decoded)` for full control; or `:auto`, the default, which sniffs the `Content-Type` and routes JSON to JSON, text to text, anything else to a blob. (When `:auto` resolves and you didn't explicitly ask for it, the runtime drops a single informational `:rf.warning/decode-defaulted` trace — not an error, just visible in tooling, a gentle nudge to be explicit.)

Now the rule that catches every newcomer exactly once:

**Decode runs only on 2xx responses. Status is classified *before* the body is touched.**

This is load-bearing, so let me make it concrete. Picture a JSON endpoint that's behind a load balancer, and the load balancer 404s with an *HTML* error page. The body is HTML; your decoder expects JSON. The naive instinct is that this surfaces as a decode failure — you asked for JSON, you got HTML, decode blew up. *Wrong.* It surfaces as `:rf.http/http-4xx`, with the raw HTML sitting untouched at `:body`. The status was checked first, the request was already classified as an HTTP failure, and the decoder never ran. The HTTP failure category is the load-bearing fact for the caller — "the server said no" matters more than "and the no was shaped like HTML." If you *want* to dig the structured error body out of a 4xx (many APIs return a useful `{:errors ...}` alongside a 4xx), you decode the raw `:body` yourself in the failure branch. The framework won't do it for you, on purpose. (Spec 014 calls this the classification order, if you want the chapter and verse.)

## Retry, backoff, and the discipline of *not* retrying

```clojure
:retry {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
        :max-attempts 4
        :backoff      {:base-ms 250
                       :factor  2
                       :max-ms  5000
                       :jitter  true}}
```

`:max-attempts` is the *total* including the first try, so `1` means no retry. `:on` is the set of failure categories that trigger a retry — and `:rf.http/aborted` is *never* retried, no matter what you put in the set, because retrying a request the user deliberately cancelled would be insane. Backoff is exponential with optional ±25% jitter (the jitter is what stops a thousand clients from all retrying in lockstep and re-DDOSing your recovering server), capped at `:max-ms`.

There's a subtlety worth knowing: **only the final, exhausted failure dispatches your `:on-failure`.** Intermediate attempts that match `:retry :on` do *not* fire your failure handler — your code sees the success reply if *any* attempt succeeds, and exactly *one* failure reply (carrying "max attempts reached") if they all fail. You never have to write `:on-failure` logic that distinguishes "failed for real" from "failed but we'll try again." That distinction is the runtime's problem. For debugging, each intermediate attempt emits a `:rf.http/retry-attempt` trace event, so pair tools and the Xray panels ([chapter 17](17-tooling.md)) show you the whole retry saga even though your handler only ever saw the ending.

The real-world discipline here isn't *whether* to retry — it's *what* to retry. Read-only data fetches are safe to retry; a transient blip on a GET is exactly what retry is for. User-initiated writes are *not*. Retrying a login, a submit, a delete risks doing the thing twice, and "the bank charged me twice because the first response was just slow" is a bug report you do not want. So the common shape is one shared retry policy for reads, and conspicuously *no* retry on writes:

```clojure
(def data-fetch-retry
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

;; Reads retry — loading the persisted counter:
[:rf.http/managed {:request {:url "/api/counter"} :decode CounterResponse :retry data-fetch-retry}]

;; Writes don't — saving the counter:
[:rf.http/managed {:request {:method :post :url "/api/counter"
                             :body {:count (:count db)} :request-content-type :json}
                   :decode  CounterResponse}]
```

The realworld example does exactly this; you can read it in `examples/reagent/realworld/http.cljs`.

## Abort, and the search-box race

Remember the five-letters-five-requests problem from the top of the chapter? This is its cure. Put a stable `:request-id` on a request and you get two things: a subsequent `:rf.http/managed-abort` fx can cancel it by id, and — the clever bit — *issuing a new request with the same id automatically supersedes the old one*. The id can be anything `=`-comparable: a keyword, a string, a vector, a uuid.

```clojure
(rf/reg-event-fx :counter/start-long
  (fn [{:keys [db]} [_ {:as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; ...handle the late reply (or its :rf.http/aborted form)...
      {:fx [[:rf.http/managed-abort :counter/long]            ;; cancel the previous
            [:rf.http/managed
             {:request    {:url "/api/long"}
              :request-id :counter/long
              :decode     CounterResponse}]]})))
```

When two in-flight requests share an id, the new one wins and the old one aborts with `:reason :request-id-superseded`. A manual `:rf.http/managed-abort` aborts whoever currently holds the id, with `:reason :user`. Either way the aborted request comes back through the reply path with `:kind :rf.http/aborted`, so you can clean up state (clear the spinner, mostly) and otherwise ignore the corpse. For search-as-you-type, give every keystroke's request the same `:request-id`, and the framework guarantees the only reply you act on is the latest one. The race is gone, and you didn't write a single line of race-handling code.

There's an alternative for when you already own an `AbortController`: `:abort-signal (.-signal my-controller)` threads your signal straight through to the transport (CLJS-only; it's a Fetch feature). The two mechanisms are mutually exclusive — pick one and stick with it for a given request.

## It already knows about frames

Multi-frame apps — Story canvases, split-pane editors, server renders, multiple windows — are [chapter 18's](18-frames.md) topic, but `:rf.http/managed` handles them so quietly you should know it's happening. The reply dispatch lands in the **same frame** the request went out from. The fx captures `:frame` from the dispatch envelope and threads it through to the reply. A request that originated in your `:left` pane replies into `:left`; a request that originated in a per-request SSR frame ([chapter 20](20-server-side.md)) replies into *that* frame, and the drain settles before render. You write nothing. The framework just doesn't cross the streams. This is the kind of thing you only notice when it's *missing* — and with `:rf.http/managed`, it never is.

## Testing without a network

Because the request is data and the reply comes back as an ordinary event, you can stub the whole round-trip with no network and no global `fetch` monkey-patch. The `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure` fxs synthesise a reply with the exact same envelope a live request would produce, and the `with-managed-request-stubs` helper wires a whole test's worth of canned replies in one place. The full story lives in [chapter 13 on testing](13-testing.md#stubbing-without-mocking) — the short version is that "test the handler that issues an HTTP request" stops being a chore involving mock servers and becomes a pure-function test like every other handler in your app.

## The slice the reply lands in: Pattern-RemoteData

`:rf.http/managed` gives you the *mechanics* of a request — fire, decode, retry, abort. It deliberately says nothing about the *shape of the slice* you write the reply into. But across every feature that loads remote data, the same shape keeps showing up anyway, so it's worth naming. The recurring slice has five keys — `:status`, `:data`, `:error`, `:loaded-at`, `:attempt` — and the load-bearing detail is the `:loading` versus `:fetching` split: `:loading` is the *first* fetch (nothing to show yet, spin the spinner), `:fetching` is *revalidating* over data you already have (keep showing the old data, don't flash a spinner over content the user is reading). Getting that one distinction right is the difference between a UI that feels solid and one that strobes.

That convention is **Pattern-RemoteData**: the slice schema paired with a four-event lifecycle (`/load`, `/loaded`, `/load-failed`, optionally `/reset`), convenience subs (`:loading?` / `:fetching?`), optimistic-update rollback, and a `:loaded-at` / `:stale-after-ms` freshness story. If "a form that loads, submits, validates, and shows errors" is the shape you actually want, that's [chapter 11 on forms](11-forms.md) — Pattern-RemoteData is its load-side cousin. The realworld example runs the full shape across its `articles`, `feed`, `profile`, and `comments` slices.

## Two worked examples to read with this chapter

There are two runnable demos, deliberately different in scope.

The **counter, extended** — [`examples/reagent/managed_http_counter/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/managed_http_counter) — is the same counter from [chapter 03](03-first-app.md) with HTTP bolted on, and it's the one to read first because the spine you already know carries through unchanged. Five buttons, each one exercising a different slice of the contract: **+1** does a real round-trip (`GET /api/inc.json` returning `{"delta": 1}`, decoded as JSON via default `:auto`, replying back to the originating handler); **Fail** hits a 404 that returns HTML and proves the status-before-decode rule (the failure is `:rf.http/http-4xx`, *not* a decode failure, with the HTML at `:body`); **Retry-recover** drives the `:rf.http/managed-canned-success` stub at app level to show the success envelope without a live retry; and **Start long** / **Cancel** demonstrate abort-by-`:request-id` end to end. The whole thing is about 200 lines. Put it in front of you, change the request shape, and watch the reply path adapt.

The **realworld app** — [`examples/reagent/realworld/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld) — is the breadth demo: the canonical Spec 014 build against the [RealWorld Conduit spec](https://github.com/gothinkster/realworld). It exercises default *and* explicit reply addressing, schema-driven decode on every request, the `:rf.http/decode-schemas` reflection metadata (so tooling can ask "what shapes does this handler expect from the wire?" via `(rf/handler-meta :event :articles/load)` without ever invoking the handler), the reads-retry / writes-don't policy split, abort-by-`:request-id` on `:articles/load` and `:feed/load`, automatic frame-aware replies, and a `realworld.http/failure->message` projection that maps the closed `:rf.http/*` vocabulary to human-readable messages. It's a worked sketch — broader than the counter, narrower than a polished production clone — that touches every Spec 014 affordance once.

## Migrating a v1 HTTP layer

If you're arriving from a re-frame v1 codebase with your own `:http` fx (or `re-frame-http-fx` / `re-frame-fetch-fx`), the mapping is mostly mechanical and [chapter 25 walks it](25-from-re-frame-v1.md#http-folds-onto-rfhttpmanaged). The short version: your hand-rolled success/failure events become the co-located `:rf/reply` branch (or explicit `:on-success` / `:on-failure`), your ad-hoc status strings become the closed `:rf.http/*` set, and the retry logic you hand-rolled in 2019 and nobody's dared touch since gets deleted in favour of a `:retry` map.

## The stakes, stated plainly

Your codebase should not carry HTTP archaeology. Every stratum of "this is how *we* handle 401s," every project-local `:on-error` convention, every per-endpoint retry policy someone wrote once and everyone now tiptoes around — that's debt the framework should be paying down for you, not debt your team re-amortises every six months when the person who understood it leaves. The point of `:rf.http/managed` was never that it's the cleverest HTTP fx anyone could write. The point is that it's *the same* HTTP fx every re-frame2 app writes. That sameness is the whole asset. It's what lets a pair tool see every request your app issues without you instrumenting anything. It's what makes `:rf.http/timeout` mean one thing everywhere. It's what lets a stranger read your handler and know — without hunting down your bespoke fetch wrapper — what "failure" means here, whether retry happened, and where the reply lands. Adopt it, and the next person who joins your team doesn't have to learn your HTTP layer. They already know it. It's the one everybody knows.
