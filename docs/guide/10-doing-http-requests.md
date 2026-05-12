# 10 — Doing HTTP requests

Most SPAs spend their lives talking to a server. A handler dispatches; a fetch goes out; some milliseconds later a reply lands; the handler integrates the reply; the view re-renders. Repeat a few thousand times per session.

[Chapter 04](04-events-state-cycle.md) gave the counter a network reach: `:counter/inc-from-server` issued a managed request, the reply landed back, the count moved. That sketch is the spine of this chapter. We'll keep the counter as the worked example and unpack every contract row `:rf.http/managed` carries — request shape, decode pipeline, retry, abort, frame-aware reply — by extending the counter one feature at a time. The full normative contract lives in [`spec/014-HTTPRequests.md`](../../spec/014-HTTPRequests.md); this chapter is the human-track companion.

## What `:rf.http/managed` is

A registered fx whose args map describes an HTTP request *as data*, and whose runtime side issues the request, decodes the body, runs retry-with-backoff if you asked for it, classifies failures into a closed set of `:rf.http/*` categories, and dispatches the reply back into the runtime.

You don't write the fetch. You don't write the `.then` chain. You don't reach for `js/fetch` or `java.net.http.HttpClient` directly. You return a map, the runtime does the rest.

A first taste, in the simplest possible form — a button that asks the server for an increment and applies the reply to the counter:

```clojure
(rf/reg-event-fx :counter/+1
  (fn [{:keys [db]} [_ msg]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply branch — same handler, different role.
      {:db (update db :count + (-> reply :value :delta))}

      ;; Initial branch — issue the managed request.
      {:fx [[:rf.http/managed
             {:request {:url "/api/inc.json"}}]]})))
```

Two lines of fx, no decode, no accept, no retry. Default `:method` is `:get`. Default decode is `:auto` (sniff the response Content-Type). Default reply addressing dispatches *back to this same event id* with `:rf/reply` merged into the original message — so one handler covers both roles, distinguishable by `(:rf/reply msg)`.

This default — co-located request and reply — is the whole reason `:rf.http/managed` exists as a distinct surface from "register your own `:http` fx and roll your own conventions." The shape is uniform across applications. Tooling can introspect. Tests can stub. Pair tools, retry policy, error projection, and frame-aware reply addressing all compose against one canonical envelope.

## Why it exists (vs raw `js/fetch` + ad-hoc effects)

We kept rewriting this. Every re-frame v1 app we shipped, every consulting codebase we audited, every open-source app we cribbed from — same five lines of `:http` fx, registered fresh in every project, with subtly drifting opinions baked in. The obvious thing in v1 was to register your own `:http` fx (chapter 04 sketches one), have it call `js/fetch`, and dispatch user-named follow-up events on resolution. That worked. It was also the thing every team ended up reinventing, and every team's answer drifted in subtly different directions on the same six questions:

- **Where does the response shape live?** Some of us put `{:status 200 :body ...}`; some unwrapped `:body` immediately; some handed the raw `Response` to the success handler. Cross-project code-sharing died at this seam.
- **How are 4xx and 5xx classified?** Some of us treated any non-2xx as failure; some forwarded 401 specially through a separate auth path; some let the success handler branch on status and discovered the bug three releases later when a 304 quietly fell through.
- **What's a transport error vs a decode error vs an HTTP error?** Usually nothing — `(.catch ...)` swallowed them all into `:on-error` with a string, and we'd `console.log` the string and ship.
- **Do retries exist?** Sometimes, hand-rolled, often per-endpoint, often inconsistent on whether the retry counts toward the rate limit, often forgetting to clear the `:loading?` flag between attempts.
- **How do you abort a stale request?** Mostly we didn't. Search-as-you-type with five letters in flight raced its way to whichever response landed last.
- **How do you stub it for tests?** Each team grew its own, and the stubs went stale the moment the response shape did.

Each team's answer was reasonable in isolation. The cost was that nothing composed across teams — pair tools couldn't introspect "an HTTP request" because there was no such thing in the framework, only a thousand variations on it. So we picked one canonical answer for each question, locked it in [Spec 014](../../spec/014-HTTPRequests.md), and shipped it as `:rf.http/managed`. Apps that adopt it get retry, abort, frame-aware reply addressing, schema-driven decode, the closed `:rf.http/*` failure category set, status-before-decode classification, and test stubs *as a single uniform surface*. Pair tools introspect that surface; conformance fixtures grade against it; AI scaffolds emit code that fits it.

The lower-level option (write your own fx) is still there for wire-level control — custom transport, raw bytes, idiosyncratic protocols. `:rf.http/managed` covers the common case, ergonomically, and pair tools can rely on it.

## The request map

The args map for `:rf.http/managed` is small. The required keys are `:request` (with `:url` inside it). Everything else has a sensible default.

```clojure
{:request    {:method  :post
              :url     "/api/counter"
              :params  {:scope "session"}
              :body    {:delta 1}
              :request-content-type :json
              :headers {"X-Trace" "abc"}}
 :decode     CounterResponse           ;; Malli schema, kw, fn, or :auto
 :accept     (fn [decoded] {:ok decoded})  ;; default = success-on-2xx
 :retry      {:on #{:rf.http/transport :rf.http/http-5xx}
              :max-attempts 4
              :backoff      {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}
 :timeout-ms 30000
 :on-success [:counter/loaded]         ;; default = back to originator
 :on-failure [:counter/load-error]     ;; default = back to originator
 :request-id :counter/load             ;; for abort + supersede
 :abort-signal external-controller-signal}
```

The full table — every key, every type, every default — is in [Spec 014 §The args map](../../spec/014-HTTPRequests.md#the-args-map). Two pieces are worth highlighting in narrative.

### `:request` carries the wire shape

`:method` defaults to `:get`. `:url` is required. `:params` get URL-encoded and merged onto the URL. `:body` may be a Clojure collection (encoded per `:request-content-type`), a string, a `Blob` / `FormData` / `ArrayBuffer`, or **a thunk `(fn [] body)`**. The thunk is called at request-send time — useful for very-large payloads you don't want to hold in memory between dispatch and send, and for retries that need a fresh body handle.

Headers, credentials, redirect mode, and the CLJS-only Fetch passthroughs (`:mode`, `:cache`, `:referrer`, `:integrity`) all live inside `:request`. On the JVM, the per-row CLJS-only keys are no-ops with a single `:rf.http/cljs-only-key-ignored-on-jvm` trace per occurrence.

### Default reply addressing — co-located handler

```clojure
{:fx [[:rf.http/managed {:request {...}}]]}
```

When you don't specify `:on-success` / `:on-failure`, the fx captures the originating event id from the dispatch envelope's cofx. On reply, it dispatches:

```clojure
[<originating-event-id> (assoc original-msg :rf/reply <reply-payload>)]
```

So your handler ends up looking like:

```clojure
(rf/reg-event-fx :counter/load
  (fn [{:keys [db]} [_ {:as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply path
      (case (:kind reply)
        :success
        {:db (-> db
                 (assoc :counter/status :loaded)
                 (assoc :count (-> reply :value :count)))}

        :failure
        {:db (-> db
                 (assoc :counter/status :error)
                 (assoc :counter/error  (:failure reply)))})

      ;; Initial path
      {:db (-> db
               (assoc :counter/status :loading)
               (assoc :counter/error  nil))
       :fx [[:rf.http/managed
             {:request {:url "/api/counter"}
              :decode  CounterResponse}]]})))
```

One handler, two roles, distinguishable by the `:rf/reply` sentinel. The reply payload's outer shape is `{:kind :success :value v}` or `{:kind :failure :failure m}`; the inner `:kind` (under `:failure`) names the `:rf.http/*` category.

When the co-located shape doesn't fit — typical for auth flows that already live in a state machine, or for endpoints whose success and failure paths diverge dramatically — supply explicit `:on-success` / `:on-failure` event vectors and the reply payload appends as the last argument. Pass `nil` to silence (fire-and-forget telemetry beacons).

### Failure shapes are closed-set

Every failure carries a `:kind` keyword in the framework-reserved `:rf.http/*` namespace plus category-specific tags:

| `:kind` | When |
|---|---|
| `:rf.http/transport` | Network / DNS / connection error before the HTTP transaction completed. |
| `:rf.http/cors` | CORS preflight rejected. CLJS-only. |
| `:rf.http/timeout` | Per-attempt timeout fired. |
| `:rf.http/http-4xx` | 4xx response. The raw body is at `:body` (decode is skipped on non-2xx). |
| `:rf.http/http-5xx` | 5xx response. Same shape as `:http-4xx`. |
| `:rf.http/decode-failure` | A 2xx response whose body the decode pipeline rejected. |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}`. |
| `:rf.http/aborted` | The request was aborted via `:request-id` or `:abort-signal`. |

The vocabulary is **closed for v1** — additions require a Spec change. See [Spec 014 §Failure categories](../../spec/014-HTTPRequests.md#failure-categories-closed-set) for the full table.

## Decode pipeline

The `:decode` key controls how the response body is parsed.

**Decode runs only on 2xx responses.** This is the load-bearing classification rule: status is checked *before* the body is touched. A JSON endpoint that returns an HTML 404 from a load balancer surfaces as `:rf.http/http-4xx` with the raw HTML at `:body`, not as `:rf.http/decode-failure`. The HTTP failure category is the load-bearing piece of information for the caller; if you want to see the structured error body that an API might return alongside a 4xx, you decode the raw `:body` yourself in the failure-handling branch. (Spec 014 §Classification order.)

The `:decode` key takes:

- **A Malli schema** (the canonical form): `:decode CounterResponse`. The fx parses the body by content-type, runs Malli's `decode`, hands the validated value to `:accept`. A 2xx body that fails to decode classifies as `:rf.http/decode-failure`.
- **A keyword**: `:decode :json` (force JSON), `:text`, `:blob`, `:array-buffer`, `:form-data`. No Malli step.
- **A function** `(fn [body-text headers] decoded)` — full control. Throwing on a 2xx classifies as `:rf.http/decode-failure`.
- **`:auto`** (the default): sniff the response Content-Type. `application/json*` → JSON. `text/*` → text. Otherwise blob. Whenever `:auto` resolves and the user did NOT explicitly supply `:decode`, the runtime emits a single `:rf.warning/decode-defaulted` trace per request — informational, not an error, just visible in tooling so you can choose to be explicit.

## Retry and backoff

```clojure
:retry {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
        :max-attempts 4
        :backoff      {:base-ms 250
                       :factor  2
                       :max-ms  5000
                       :jitter  true}}
```

`:max-attempts` is the total including the first; `1` means no retry. `:on` names which failure categories trigger a retry — `:rf.http/aborted` is never retried regardless. Backoff is exponential with optional ±25% jitter, capped at `:max-ms`.

**Only the final exhausted-retries failure dispatches `:on-failure`.** Intermediate attempts that match `:retry :on` do NOT dispatch the failure handler — the user sees the success reply if any attempt succeeds, and exactly one failure reply (with `:max-attempts` reached) if every attempt fails. For debugging visibility, each intermediate attempt emits a `:rf.http/retry-attempt` trace event. Pair tools and causa panels surface the per-attempt trace; user code only sees the final outcome.

A common shape in real apps: declare a shared retry policy for read-only data fetches, and *don't* retry user-initiated actions (login, submit, delete — single user-initiated action per click).

```clojure
(def data-fetch-retry
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

;; Apply to reads — e.g. loading the persisted counter:
[:rf.http/managed {:request {:url "/api/counter"} :decode CounterResponse :retry data-fetch-retry}]

;; Don't apply to writes — e.g. saving the counter:
[:rf.http/managed {:request {:method :post :url "/api/counter"
                             :body {:count (:count db)} :request-content-type :json}
                   :decode  CounterResponse}]
```

The realworld example does exactly this — see `examples/reagent/realworld/http.cljs`.

## Abort by `:request-id`

A stable id on the request lets a subsequent `:rf.http/managed-abort` fx cancel the in-flight request. The id can be anything `=`-comparable: a keyword, a string, a vector, a uuid.

```clojure
(rf/reg-event-fx :counter/start-long
  (fn [{:keys [db]} [_ {:as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; ...handle the late reply (or its :rf.http/aborted form)...
      {:fx [[:rf.http/managed-abort :counter/long]           ;; cancel previous
            [:rf.http/managed
             {:request    {:url "/api/long"}
              :request-id :counter/long
              :decode     CounterResponse}]]})))
```

When two in-flight requests share an id, issuing a new one with the same id supersedes the old one — the previous request aborts with `:reason :request-id-superseded`. A manual `:rf.http/managed-abort` aborts whichever request currently holds the id with `:reason :user`. Aborted requests dispatch `:on-failure` (or the default reply path) with `:kind :rf.http/aborted`.

The external-handle alternative — `:abort-signal (.-signal my-controller)` — threads a user-owned `AbortController.signal` directly through to the underlying transport. CLJS-only (Fetch supports it). The two mechanisms are mutually exclusive — pick one.

## Lazy `:body` thunk

If `:body` is a thunk `(fn [] body)`, the fx invokes it *just before sending* (after `:retry :backoff` delays elapse). Each retry re-invokes the thunk to obtain a fresh handle.

This is useful when:

- The body is expensive to serialise and you don't want to pay the cost until the request is actually about to ship.
- The body is a single-shot stream that can't be replayed — a thunk lets retries obtain a fresh handle each time.
- The body's contents depend on something computable only at send time (a fresh CSRF token, a current timestamp).

```clojure
:body (fn [] (build-large-payload (current-state)))
```

## Frame awareness

The reply dispatch lands in the **same frame** the request was issued from. The fx captures `:frame` from the dispatch envelope's cofx and threads it through to the reply dispatch's `{:frame ...}` opt. Multi-frame apps (story tools, SSR, multi-window) work without extra ceremony — the request that originated in `:left` replies into `:left`; the request that originated in the per-request SSR frame replies into that same per-request SSR frame and the drain settles before render.

## Test stubs

For stubbing managed-HTTP requests in tests — the `:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure` fxs and the `with-managed-request-stubs` helper — see [13 — Testing §Stubbing managed HTTP](13-testing.md#stubbing-managed-http).

## The standard request-lifecycle slice

`:rf.http/managed` gives you the *mechanics* of a request — fire it, decode the reply, retry, abort. It doesn't dictate the *shape* of the slice you write the reply into. Across every feature that loads remote data, the same five-key `:status` / `:data` / `:error` / `:loaded-at` / `:attempt` shape recurs — with the load-bearing `:loading` (first fetch) vs `:fetching` (revalidating over existing `:data`) split so revalidation doesn't flash a spinner over loaded content.

That's **Pattern-RemoteData** — convention, not Spec. See [`spec/Pattern-RemoteData.md`](../../spec/Pattern-RemoteData.md) for the full slice schema, the four-event lifecycle (`/load`, `/loaded`, `/load-failed`, optionally `/reset`), convenience subs (`:loading?` / `:fetching?`), optimistic-update rollback, and the `:loaded-at` / `:stale-after-ms` freshness story. The RealWorld example exercises the full shape across `articles`, `feed`, `profile`, and `comments` slices.

## Worked example — `examples/reagent/managed_http_counter/`

The runnable demo of every contract above lives in [`examples/reagent/managed_http_counter/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/managed_http_counter). It's the same counter from chapter 03, extended with HTTP — each button issues a managed request and the reply lands back in app-db. The counter's spine carries through unchanged; HTTP layers on top.

Five buttons, each exercising a different slice of the contract:

- **+1** — `GET /api/inc.json`. A static asset; the response `{"delta": 1}` decodes as JSON via default `:auto` decoding; the reply lands at the originating `:counter/+1` handler via default reply addressing; the handler branches on `(:rf/reply msg)` to apply the increment. *Real round-trip through Fetch.*

- **Fail** — `GET /api/does-not-exist`. The endpoint 404s with HTML; per the status-before-decode classification rule, the failure category is `:rf.http/http-4xx` (with the raw HTML at `:body`), not `:rf.http/decode-failure`. The handler's failure branch records the error.

- **Retry-recover** — exercises the `:rf.http/managed-canned-success` stub at app level. The :counter/retry-recover handler issues `[:rf.http/managed-canned-success {... :value {:delta 5}}]` directly; the stub synthesises the success reply with `{:delta 5}`; the handler increments by 5. Same reply shape as a live retry-recover would produce.

- **Start long** — issues a `GET /api/long` with `:request-id :counter/long`. The request stays in flight; clicking **Cancel** issues `[:rf.http/managed-abort :counter/long]` which aborts the in-flight handle. The aborted reply dispatches `:rf.http/aborted` via the default reply path back to `:counter/start-long`, which clears `:status`.

The whole example is ~200 lines of CLJS. Drop it in front of you while reading; tweak the request shape and watch the reply path adapt.

## Worked example — `examples/reagent/realworld/`

For breadth across the contract — auth, routing, optimistic updates, pagination, forms, SSR-relevant payload concerns — see [`examples/reagent/realworld/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld). It's the canonical Spec 014 demo, built on the [RealWorld Conduit spec](https://github.com/gothinkster/realworld).

What it specifically exercises from this chapter:

- **Default reply addressing** — `realworld.comments/:article/load` issues `:rf.http/managed` with no explicit `:on-success` / `:on-failure` and branches on `(:rf/reply msg)`. One handler, two roles.
- **Explicit `:on-success` / `:on-failure`** — every other endpoint (auth, articles list, profile, comments, favourites, follow, settings, editor) uses the separate-handler shape: a small DB-only handler per success / failure.
- **Schema-driven decode** — every request passes a Malli schema as `:decode`. Decode runs only on 2xx; a 4xx HTML page never produces `:rf.http/decode-failure`.
- **Schema reflection** — every event handler that issues a managed request declares `:rf.http/decode-schemas` in its registration metadata. Tooling can introspect via `(rf/handler-meta :event :articles/load)` without invoking the handler.
- **Retry + backoff** — read-only data fetches share a `data-fetch-retry` policy; user-initiated writes (login, submit, delete) deliberately do NOT retry.
- **Abort by `:request-id`** — `:articles/load` and `:feed/load` use stable `:request-id` keywords; `(:articles/cancel)` and `(:feed/cancel)` issue `:rf.http/managed-abort` to cancel an in-flight load when the user navigates away or re-issues mid-fetch.
- **Frame awareness** — replies route back to the originating frame automatically; the test fixtures spin per-test frames via `make-frame` and assert against `(get-frame-db f)`.
- **Failure projection** — `realworld.http/failure->message` projects the closed-set `:rf.http/*` failure categories to human-readable messages, surfacing the Conduit `{:errors {:body [...]}}` shape when present.

The realworld example is a worked sketch — broader than the counter demo, narrower than a polished production clone, deliberately exercising every Spec 014 affordance one place at a time.

## Migrating from re-frame v1's ad-hoc HTTP

For callers coming from a v1 codebase with their own `:http` fx (or `re-frame-http-fx` / `re-frame-fetch-fx`), see [18 — From re-frame v1 §Migrating an HTTP layer](18-from-re-frame-v1.md#migrating-an-http-layer).

## Reference and tooling

The pieces below are opt-in. A first-pass reader can skip this section; come back when wiring causa panels, pair tools, or AI scaffolds against the managed-HTTP surface.

### Schema reflection — `:rf.http/decode-schemas`

Pair tools and AI generators want to know which schemas a handler expects from the wire — without invoking the handler. Declare them at registration time via the `:rf.http/decode-schemas` metadata key:

```clojure
(rf/reg-event-fx :counter/load
  {:doc                    "Load the persisted counter."
   :rf.http/decode-schemas [CounterResponse]}     ;; declared up-front for tooling
  (fn [{:keys [db]} [_ {:as msg}]]
    (if-let [reply (:rf/reply msg)]
      ...
      {:fx [[:rf.http/managed
             {:request {:url "/api/counter"}
              :decode  CounterResponse}]]})))     ;; same schema at the call site
```

Then `(rf/handler-meta :event :counter/load)` returns metadata carrying `:rf.http/decode-schemas [CounterResponse]`, which tools introspect. **Optional, never enforced** — the runtime does not cross-check that the call-site `:decode` matches the declared schemas. The metadata is reflective sugar; runtime enforcement would want a `defmanaged-event-fx` macro that DRYs the declaration, which is out of v1 scope.

## The stakes

Your codebase shouldn't carry HTTP archaeology. Every layer of "this is how we used to handle 401s," every project-local `:on-error` convention, every per-endpoint retry policy that someone hand-rolled in 2019 and nobody's touched since — that's debt the framework should be paying down for you, not debt your team should be re-amortising every six months. The point of `:rf.http/managed` isn't that it's the cleverest HTTP fx anyone could write; it's that it's *the same* HTTP fx every re-frame2 app writes. That sameness is the load-bearing piece. It's what lets pair tools see every request your app issues without instrumentation. It's what lets the closed-set `:rf.http/*` failure vocabulary mean the same thing across codebases. It's what lets a stranger read your handler and know — without reading your `:http` fx — what "failure" means, whether retry happened, and where the reply lands. The canonical envelope is the only thing that lets tooling see all your requests at once. Adopt it, and the next time someone joins your team they don't have to learn your HTTP layer; they already know it.

## Cross-references

- [`spec/014-HTTPRequests.md`](../../spec/014-HTTPRequests.md) — the normative contract: every key, every default, every failure shape.
- [`spec/Pattern-RemoteData.md`](../../spec/Pattern-RemoteData.md) — the 5-key request-lifecycle slice; managed-HTTP writes through this slice.
- [`spec/Pattern-AsyncEffect.md`](../../spec/Pattern-AsyncEffect.md) — the generic six-step async shape that managed-HTTP specialises.
- [`spec/Pattern-StaleDetection.md`](../../spec/Pattern-StaleDetection.md) — epoch carry; managed requests inherit it.
- [`examples/reagent/managed_http_counter/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/managed_http_counter) — the runnable per-button demo of every contract row.
- [`examples/reagent/realworld/`](https://github.com/day8/re-frame2/tree/main/examples/reagent/realworld) — the canonical breadth demo across auth, routing, forms, machines, optimistic updates, and SSR-relevant payload concerns.

## Next

- [11 — The server side](11-server-side.md) — SSR and hydration; the `:platforms` story for fx that should only run in one place; how the per-request frame composes with managed-HTTP for setup fetches.
