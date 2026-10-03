# Managed HTTP

<a id="managed-http-reference"></a>

`:rf.http/managed` is the one [effect](../core/glossary.md#effect) for talking to a
server. Describe the request as data; the runtime decodes, classifies failures,
retries, and cancels; the reply arrives as an ordinary [event](../core/glossary.md#event).

<a id="setup"></a>

!!! note "Optional artefact"

    Require `re-frame.http.managed` once at boot — Maven `day8/re-frame2-http`. Without
    it, `[:rf.http/managed …]` raises `:rf.error/no-such-fx`, and
    `rf/reg-http-interceptor` raises `:rf.error/http-artefact-missing`.

## The args map

`:rf.http/managed` takes one args map. Two things in it are required: the `:request`, with a `:url`, and a reply target, which is `:reply-to` or the `:on-success` / `:on-failure` pair. Everything else has a default, so the common case stays short:

```clojure
(ns app.article-http
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]))

{:fx [[:rf.http/managed
       {:request    {:url "/api/articles/intro"}
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

The [API reference](../api/re-frame.http.md#rfhttpmanaged-args-map) lists every key,
value and default.

## The request is a map

`:request` describes what goes over HTTP. To post a comment, add a method and
JSON body to the article request:

```clojure
(rf/reg-event :comment/create
  (fn [_ [_ slug text]]
    {:fx [[:rf.http/managed
           {:request    {:method :post
                         :url (str "/api/articles/" slug "/comments")
                         :params {:notify true}
                         :headers {"X-Client" "web"}
                         :body {:comment {:body text}}
                         :request-content-type :json}
            :decode     :json
            :on-success [:comment/created]
            :on-failure [:comment/create-error]}]]}))
```

`:params` is URL-encoded onto the URL; `:headers` maps names to strings.
`:request-content-type :json` serializes the body and sets `Content-Type`.
For a browser file upload, pass `js/FormData` as the body and leave that key off
so the platform supplies the multipart boundary. The [request reference](../api/re-frame.http.md#rfhttpmanaged-args-map)
records the other body encodings and transport options.

To put the same header on every request (auth, say), register one [HTTP interceptor](http-going-further.md#interceptors-stamp-every-request-once) instead of threading it through each call site; the interceptor also picks up a rotated token.

## Handling the reply

Your handler receives a reply map with one of three statuses:

| `:status` | What the handler reads |
|---|---|
| `:ok` | `:value` is the decoded 2xx body, or the value returned by `:accept`. `:meta` carries the HTTP status, status text and headers under lower-cased names. |
| `:error` | `:error` is the failure map. Branch on its `:kind`. |
| `:cancelled` | `:error` has `:kind :rf.http/aborted`; the reply also carries `:cancelled? true` and `:rf.reply/cancel-reason`. |

An obsolete completion is recorded as `:stale` and suppressed before dispatch.
Your handler never receives it. Managed HTTP does not emit `:partial`, the
uniform reply's status for protocols that return usable data with problems.

Read `:value` on success and `:error` on failure. The [reply reference](../api/re-frame.http.md#reply-shape)
records timing and identity fields; [Why no await](continuations-are-data.md#one-reply-map-under-every-async-surface)
explains the shared async reply model.

Use separate receiving events for success and failure. A single `:reply-to`
handler is useful when the outcomes share cleanup; [Advanced](#one-handler-with-reply-to)
shows that form.

??? info "Coming from Promises?"

    `:on-success` / `:on-failure` are `.then` / `.catch`; a single `:reply-to` handler that branches on `:status` is the `.finally`-plus-both-branches shape (a `let` above a `case`). [Coming from Promises](continuations-are-data.md#coming-from-promises) maps the whole triad, including why there is no `:on-finally`.

### Two handlers with :on-success / :on-failure

Name `:on-success` and `:on-failure` and each outcome lands in its own handler, with the reply map appended as the last event argument — `[:article/loaded {:status :ok :value <decoded> …}]`. Both handlers receive the same reply map; the two keys only choose which handler it goes to.

This page has no server, so the cell below installs stubs: `intro` answers with an article and `missing` with a 404. Click each button to send one request down each path:

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.http.test-support :as http-test-support])

(http-test-support/install-managed-request-stubs!
  {[:get "/api/articles/intro"]   {:reply {:ok {:title "Welcome" :body "The first article."}}}
   [:get "/api/articles/missing"] {:reply {:failure {:kind :rf.http/http-4xx :status 404}}}})

(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed {:request    {:url (str "/api/articles/" slug)}
                             :on-success [:article/loaded]
                             :on-failure [:article/load-error]}]]}))

(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (assoc db :article {:status :loaded :data value})}))

(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (assoc db :article {:status :error :error error})}))

;; Show the [:article] slice, with a button per article.
(rf/reg-sub :article (fn [db _] (:article db)))

(rf/reg-view article-view []
  [:div
   [:button {:on-click #(dispatch [:article/load "intro"])} "Load intro"]
   [:button {:on-click #(dispatch [:article/load "missing"])} "Load missing"]
   [:pre (pr-str @(subscribe [:article]))]])

;; :fx-overrides sends this frame's requests to the stubs. A real app leaves it out.
[rf/frame-root {:id :app/articles :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [article-view]]
```

`missing` reaches `:article/load-error` with a failure map whose `:kind` is `:rf.http/http-4xx`. The stubs answer at once, so the `:loading` status never shows.

Prefer this shape when the success and failure paths are substantial or diverge: each handler reads and tests on its own.

??? info "From re-frame v1"

    Your `:http-xhrio`-style success/failure events map straight onto `:on-success` / `:on-failure` — the [migration page](../core/25-from-re-frame-v1.md) walks the translation.

### Delivery rules

Each request names its receiving events. Reply targets are event vectors;
`nil` explicitly [silences a reply](#silencing-a-reply). Use either `:reply-to`
or `:on-success` / `:on-failure`; combining the forms is invalid, even when
one key is `nil`. The [args reference](../api/re-frame.http.md#rfhttpmanaged-args-map)
records these checks.

The effect carries the issuing [frame](../core/frames.md) through to the reply.
Your receiving event runs in that same frame without a separate callback or
frame capture.

## Failures are a closed set

The failure map always carries a `:kind`: a keyword from a fixed list of eight, never a string.

- **No response at all.** `:rf.http/transport` is a network, DNS or connection error, or a `:body` that could not be prepared. In the browser, the same trouble against a cross-origin URL reads as `:rf.http/cors`, because Fetch reports a CORS refusal and a dropped connection the same way. `:rf.http/timeout` means the per-attempt timeout fired.
- **An error status.** `:rf.http/http-4xx` and `:rf.http/http-5xx` carry the `:status`, `:headers` and the raw `:body`.
- **A 2xx the app rejected.** `:rf.http/decode-failure` means your `:decode` rejected the body; `:rf.http/accept-failure` means your [`:accept`](#a-valid-200-can-still-be-a-failure-accept) did.
- **Cancelled.** `:rf.http/aborted`, with a `:reason` saying who cancelled: `:user` for a manual abort or `:abort-signal`, `:actor-destroyed` for a machine actor's destroy. A supersession, a frame's teardown and an epoch restore abort too, but deliver no reply.

The API reference's [failure categories](../api/re-frame.http.md#failure-kinds-closed-set) table lists the extra keys each kind carries.

Branch on `:kind`, as in the [tutorial's message function](tutorial.md#step-2--turn-the-failure-into-something-a-user-can-read).

Two classification rules catch newcomers:

- **Status is classified before the body is touched — decode runs only on 2xx.** A JSON endpoint behind a load balancer that 404s with an *HTML* error page is `:rf.http/http-4xx` with the raw HTML at `:body`, not a decode failure: the decoder never ran. If you want the structured error body many APIs return alongside a 4xx, decode `:body` yourself in the failure branch — the framework hands you the bytes and the status, on purpose.
- **An empty (or whitespace-only) 2xx JSON body is not a decode failure** — it's a parsed value of `nil`. The bare `204 No Content` a PUT or DELETE replies with succeeds: `:decode :json` hands your `:on-success` the canonical `{:status :ok :value nil …}`. A schema `:decode` then decides whether `nil` is acceptable — `[:maybe …]` passes, a required `:map` rejects as an ordinary schema failure. Identical on the browser and the JVM, on purpose.

## Validating the body with `:decode`

By default `:decode` is `:auto`, which sniffs the Content-Type: JSON for a JSON type, a string for `text/*`, and otherwise the raw binary body (a `Blob` in the browser, bytes on the JVM). Hand `:decode` a Malli [schema](../core/glossary.md#schema), as the tutorial's Step 3 does, and a malformed 2xx body becomes a `:rf.http/decode-failure` routed to your failure handler. (Malli has to be in the build: see [tutorial step 3](tutorial.md#step-3--validate-the-body-with-a-schema).) A schema reads JSON only: a 2xx response that declares another Content-Type, such as `application/edn`, is a `:rf.http/decode-failure` whose `:cause` names the type, while a response with no Content-Type is parsed as JSON.

`:decode` also accepts a keyword (`:json` / `:text` / `:blob` / `:array-buffer` / `:form-data`) or a plain function `(fn [text headers] decoded)` when you need full control.

## Retry: transport retry as data

For a read, a short retry policy can recover from a temporary failure:

```clojure
:retry {:on #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
        :max-attempts 3
        :backoff {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}}
```

Add that entry to the HTTP args map. `:max-attempts` includes the first try, so
this allows two retries. Only the final exhausted failure dispatches your
failure handler; intermediate failures appear in traces. Cancellation also
stops a pending retry.

Leave automatic retry off writes unless the server provides an idempotency
contract: a timeout or lost response does not prove the write failed. The
[retry reference](../api/re-frame.http.md#rfhttpmanaged-args-map) records the
allowed categories and backoff defaults.

When another attempt first needs an application decision, such as refreshing
an auth token, coordinate it through event handlers or a [machine](http-machines.md).
Transport retry resends the same request; it does not refresh that token.

## Cancellation: supersession and abort

<a id="the-search-box-race-cured"></a>

**Supersession — reuse a `:request-id`.** Give a request a stable `:request-id` — any `=`-comparable value: a keyword, a string, or a structural vector such as `[:article/load slug]` — and issuing a *new* request with the same id automatically supersedes the old one. Ids belong to the frame that issued them, so the same id in code mounted in two frames never collides, and no frame can supersede or abort another's request. The old reply is suppressed before delivery: your handler never sees it, only a trace row records it (`:reason :request-id-superseded`). For the tutorial's single article display, use the stable id `:article/load`;
including the slug would give each article a separate request that could not
supersede another article's load ([latest article](tutorial.md#step-5--keep-the-latest-article)).

**Frame teardown.** A request issued from an ordinary event handler belongs to the frame that issued it. Destroying that frame, or restoring an earlier epoch in it, aborts every request it still has in flight, with or without a `:request-id`, and suppresses their replies; each leaves a `:rf.http/stale-suppressed` trace row whose `:recovery` (`:suppressed-on-frame-destroy` or `:suppressed-on-epoch-restore`) says which boundary it was. While the frame lives, `:request-id` is your app-level cancel handle.

**Manual abort — `[:rf.http/managed-abort the-id]`.** A manual abort stops whichever request currently holds the id and, unlike a supersession, *does* deliver a reply: a `:status :cancelled` reply carrying `{:kind :rf.http/aborted :reason :user}` under `:error`, so the handler for a user's cancel can clear the spinner. An id with nothing in flight (its reply has already landed, say) makes the abort a no-op.

Requests issued by a spawned actor are also cancelled when it is destroyed.
[HTTP from a machine](http-machines.md#cancellation) explains that lifetime and
which replies are suppressed.

The [managed-http counter example](../../examples/core/managed_http_counter) demonstrates the manual-abort path end-to-end — plus the 404-is-not-a-decode-failure rule — in one small file.

## Timeouts

Every attempt has a per-attempt timeout, default `30000` ms. Set `:timeout-ms` to change it; `nil` or `0` opts out entirely. A fired timeout classifies as `:rf.http/timeout` with `:elapsed-ms` and `:limit-ms` on the failure map, and is retryable under a `:retry` policy that includes it.

## From a state machine

Use a [state-bound HTTP request](http-machines.md) when leaving a machine state
should cancel its work, or several requests must finish together. The machine
form accepts the same args map, with the child managing its own reply address.

## Testing without a network

Tests need no network. Require `re-frame.http.test-support` from test code and use either of its two tools:

- `with-request-stubs` answers requests from a route map while a function runs. It matches a request on its `:method` and on its `:url` as the `:before` interceptors leave it, before `:params` is merged in. A request that matches no route receives a `:rf.http/transport` failure whose `:message` is `"no stub matched"`.
- The canned-stub effects, `:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure`, deliver one reply inline from `:fx`.

Both deliver the `:status` / `:value` / `:error` shape a live request does, without the live reply's identity and timing keys (`:rf.reply/work-id`, `:completed-at`). They supply an already-decoded reply: body encoding, `:decode`, `:accept`, retries, timeouts and request-id supersession do not run. Test those behaviors against a controlled HTTP endpoint. The [tutorial's test step](tutorial.md#step-6--test-it-without-a-network) shows the pattern, [Test a pipeline run](../core/testing/pipeline-runs.md) is the full recipe, and the [API reference](../api/re-frame.http.md#testing-without-a-network) documents every stub surface.

## Troubleshooting

| You see | What happened |
|---|---|
| `:rf.error/no-such-fx` naming `:rf.http/managed`, or `:rf.error/http-artefact-missing` from `rf/reg-http-interceptor` | The artefact isn't loaded. Require `re-frame.http.managed` once at boot. |
| `:rf.error/fx-handler-exception` on `:rf.http/managed`, its exception carrying `:rf.error/http-no-reply-target`, `:rf.error/http-bad-reply-target`, `:rf.error/http-bad-request`, `:rf.error/http-bad-retry-on` or `:rf.error/schemas-artefact-missing` | The args map was refused and nothing was sent: no reply target, a misshaped or mixed reply target, a bad `:url`, a bad `:retry :on`, or a `:decode` schema with slot marks while `re-frame.schemas` isn't loaded. The error names the key. |
| `:rf.error/machine-action-exception` from a spawned `:rf.http/managed` child, its `:exception-data` carrying `:rf.error/http-bad-reply-target` | The spawn's `:data` carried `:reply-to`, `:on-success` or `:on-failure`, and nothing was sent. Drop the key: the child reports back as `[:succeeded value]` / `[:failed failure]`. See [HTTP from a machine](http-machines.md). |
| An `:error` trace row named for a failure kind: `:rf.http/timeout`, `:rf.http/http-5xx`, … | The request failed, and the same failure map reached your failure target. The row is the dev trace's record of the failure, not a sign it went unhandled. |
| `:rf.http/issued`, later `:rf.http/stale-suppressed`, and no handler ran | The reply was suppressed: a newer request took the `:request-id`, or the frame was destroyed or restored to an earlier epoch. See [Cancellation](#cancellation-supersession-and-abort). |
| `:rf.warning/failure-swallowed` | A failure had no reply target and was dropped. See [Silencing a reply](#silencing-a-reply). |
| `:rf.error/http-interceptor-failed`, `:rf.error/http-interceptor-bad-return` or `:rf.error/http-reply-tail-failed` | An interceptor threw or returned a non-map, or delivering the reply threw after the response arrived. No reply is delivered. A `:before` failure also arrives as `:rf.error/fx-handler-exception` carrying the same id. |
| `:rf.error/no-frame-context` from `rf/reg-http-interceptor` | The registration names no frame. Pass `:frame`, or register inside a frame scope ([Interceptors](http-going-further.md#interceptors-stamp-every-request-once)). |
| `:rf.warning/http-malli-absent`, and a malformed body reached your handler | Malli isn't in the build, so the `:decode` schema was skipped. See [step 3 of the tutorial](tutorial.md#step-3--validate-the-body-with-a-schema). |
| `:rf.warning/http-header-invalid` | A request header was rejected and the request went without it. See [Malformed headers](#malformed-headers). |
| `:rf.http/cljs-only-key-ignored-on-jvm` or `:rf.http/binary-decode-degraded-on-jvm` | A browser-only option ran on the JVM. See [Running on the JVM](#running-on-the-jvm). |
| `:rf.error/bad-classification` naming `:rf.http/managed` | The `:carriers` block on your `:rf.http/managed` registration is malformed. See [HTTP trace redaction](http-tracing.md). |

Every other `:rf.http/*` trace row, and what each carries, is in [the API reference's trace table](../api/re-frame.http.md#trace-events).

## Advanced

<a id="one-handler"></a>

### One handler with :reply-to

A single receiving handler can share cleanup across success, failure and
cancellation ([the cleanup example](continuations-are-data.md#the-finally-job)).
For a small operation, the sending and receiving event can also be the same.
This cell names it `:article/fetch`, so it does not replace the earlier cell's
`:article/load`, and it uses the stubs that cell installed:

```cljs-rf2
;; cf. examples/real-apps/realworld_http/comments.cljs
;; Alternative to the separate :article/load and reply handlers.
(require '[re-frame.core :as rf]
         '[re-frame.http.managed])

(rf/reg-event :article/fetch
  (fn [{:keys [db]} [_ slug reply]]
    (if (nil? reply)
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:rf.http/managed
             {:request    {:url (str "/api/articles/" slug)}
              :request-id :article/fetch
              :reply-to   [:article/fetch slug]}]]}
      (case (:status reply)
        :ok {:db (assoc db :article {:status :loaded :data (:value reply)})}
        :error {:db (assoc db :article {:status :error :error (:error reply)})}
        :cancelled {:db (assoc-in db [:article :status] :idle)}))))

(rf/reg-sub :article/fetched (fn [db _] (:article db)))

(rf/reg-view fetch-view []
  [:div
   [:button {:on-click #(dispatch [:article/fetch "intro"])} "Fetch intro"]
   [:button {:on-click #(dispatch [:article/fetch "missing"])} "Fetch missing"]
   [:pre (pr-str @(subscribe [:article/fetched]))]])

[rf/frame-root {:id :app/fetch-demo :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [fetch-view]]
```

The initial `[:article/fetch slug]` has no reply. The completion arrives as
`[:article/fetch slug reply]`, and the `case` picks the branch from its
`:status`. Test for an absent reply before issuing work; cancellation is a
delivered reply and must not start another request.

### Silencing a reply

Write `:reply-to nil` and the whole reply is dropped — fire-and-forget, useful for a telemetry beacon you genuinely don't care to handle. Setting `:on-success` or `:on-failure` to `nil` silences just that one side of the split form. But the framework won't let you *accidentally* swallow an error: the first time a non-aborted failure is dropped because its reply has no target — `:reply-to nil`, `:on-failure nil`, or an `:on-success` with no `:on-failure` beside it — a one-shot `:rf.warning/failure-swallowed` trace fires (dev-only) so the silence is observable rather than invisible. Aborted requests are excluded — a cancelled request that no longer wants its reply is correct silence, not a bug.

### Your own request builder

The effect and machine forms both accept the same request args map. There is no
per-verb helper family; an ordinary function can build that map.

When several call sites share a base URL, default headers or body encoding,
put those defaults in an ordinary function:

```clojure
;; cf. examples/real-apps/realworld_http/http.cljs
(defn request
  "Assemble a `:rf.http/managed` args map for our API."
  [{:keys [method path body decode] :or {method :get decode :json} :as args}]
  (let [req (cond-> {:method  method
                     :url     (str "https://api.example.com" path)
                     :headers {"Accept" "application/json"}}
              body (assoc :body body :request-content-type :json))]
    (-> args
        (dissoc :method :path :body)
        (assoc :request req :decode decode))))

(rf/reg-event :comment/create
  (fn [_ [_ slug text]]
    {:fx [[:rf.http/managed
           (request {:method     :post
                     :path       (str "/articles/" slug "/comments")
                     :body       {:comment {:body text}}
                     :on-success [:comment/created]
                     :on-failure [:comment/create-error]})]]}))
```

The builder returns an args map, so it composes everywhere the args map is accepted — including the `:request` producers on [resources](../resources/concepts.md) and mutations, which take a map and would reject a pre-built fx vector.

[`examples/real-apps/realworld_http/http.cljs`](../../examples/real-apps/realworld_http) ships the full version of this pattern: `rh/request` sets the base URL, a default `Accept` header, `:decode :json`, and JSON body encoding, and every call site in that app goes through it.

### A valid 200 can still be a failure: `:accept`

Some APIs return `200 OK` with `{"article": null}` when an article is missing.
Use `:accept` after decoding to turn that domain result into a failure.

`:accept` is a function `(decoded → {:ok value} | {:failure failure-map})`. Return `{:ok v}` and `v` becomes the success payload; return `{:failure m}` and `m` rides into the failure path as `:rf.http/accept-failure`, with your map at `:detail`:

```clojure
{:fx [[:rf.http/managed
       {:request {:url (str "/api/articles/" slug)}
        :decode  :json
        :accept  (fn [decoded]
                   (if-let [article (:article decoded)]
                     {:ok article}
                     {:failure {:reason  :missing-article
                                :message "Response had no :article"}}))
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

The default `:accept` is just `{:ok decoded}` — every 2xx that decodes is a success. Three rules keep an explicit one predictable:

- `:accept` runs **only after a successful 2xx decode**. A non-2xx response is sorted by status long before this point, so your `:accept` never has to think about HTTP status. A `:decode` schema runs first too: one that requires `:article` rejects `{"article": null}` as a decode failure before `:accept` sees it.
- An `:accept` that **throws**, or returns a **malformed shape** (nil, a non-map, a map with neither `:ok` nor `:failure`, or one with *both*) still dispatches a reply — it can never strand the caller. It classifies as `:rf.http/accept-failure` with a framework-supplied `:detail`, `{:rf.http/bad-accept :threw :message …}` or `{:rf.http/bad-accept :malformed-return :returned …}`, and the pre-`:accept` value rides at `:decoded` so you can see what it choked on.
- An accept-failure is **not retryable**. Retrying the transport won't change the body — this is a domain decision, not a transport blip. If you need "retry after refreshing X," that's a [state machine](../machines/concepts.md), not `:accept`.

### Cancel through an external signal: `:abort-signal`

If the cancel signal you want to honour already lives outside re-frame — a parent widget's lifecycle, a shared `AbortController` — hand its `.signal` to the request under `:abort-signal`. You can supply it *together with* a `:request-id` and the framework guarantees exactly one terminal outcome no matter which fires first. (`:abort-signal` is browser-only — the JVM has no `AbortController`, so `:request-id` is the cross-host cancel handle.)

### Running on the JVM

The six Fetch-passthrough keys (`:credentials`, `:mode`, `:cache`, `:referrer`, `:integrity`, and the top-level `:abort-signal`) are meaningful against the browser Fetch API and have no `java.net.http.HttpClient` analogue. On the JVM:

- The request still goes out with the option dropped, and one `:rf.http/cljs-only-key-ignored-on-jvm` warning trace fires per occurrence so the degraded path is visible.
- `:redirect` is the exception: the JVM honours it.
- A relative `:url` fails outright: there is no page to resolve it against, so it becomes a `:rf.http/transport` failure naming the url. Use an absolute URL there, or a `:before` [interceptor](http-going-further.md#interceptors-stamp-every-request-once) that prefixes a base.
- An explicit `:decode :blob`, `:array-buffer` or `:form-data` delivers a byte array rather than the browser object, and emits one `:rf.http/binary-decode-degraded-on-jvm` warning trace per request.
- `:meta`'s `:status-text` is `""`: the JVM client exposes no reason phrase.
- `:rf.http/cors` never fires; only the browser emits it.

If a request runs on both hosts — SSR, a shared loader — keep cross-host code off these keys or feature-flag them at the call site.

### Malformed headers

!!! warning "Gotcha — a malformed header is dropped, not fatal"

    A header with an empty or control-character name, or a value carrying a raw `\r`/`\n` (the response-splitting guard), is rejected by the platform's header builder. On the JVM, so is a name the client reserves for itself, such as `Host` or `Connection`. Rather than failing the whole request, the runtime drops just that one pair, emits a redacted `:rf.warning/http-header-invalid` trace naming the offending header (the *value* is omitted — it may carry a secret), and sends the request with the remaining valid headers. So a stray newline in one interpolated header value quietly loses that header instead of taking down the call — watch the warning trace if a header you expected isn't arriving.

### Timestamps come from a coeffect

If a reply handler wants to record *when* something completed, don't call `(js/Date.now)` in it — a live clock read won't replay the same way twice. Declare the time as a [coeffect](../core/glossary.md#coeffect) and read it as data:

```clojure
(rf/reg-event :article/loaded
  {:rf.cofx/requires [:rf/time-ms]}
  (fn [{:keys [db rf/time-ms]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :data]      value)
             (assoc-in [:article :loaded-at] time-ms))}))
```

`:rf/time-ms` is a *recordable* coeffect: it's stamped onto the event envelope before the handler runs, so the durable write depends on a recorded value and [replays](../core/glossary.md#time-travel) identically. (The full grade distinction is [recordable vs ambient coeffects](../core/glossary.md#recordable-vs-ambient-coeffects).)
