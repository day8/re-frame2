# Pattern — ManagedHTTP

`:rf.http/managed` — the canonical HTTP fx for re-frame2. Two affordances on one registrar id: the **fx form** for direct use from event handlers, and the **machine-form wrapper** for `:invoke` from a parent state machine. The contract — args map, failure categories, retry, abort, reply addressing — is identical across both.

> Managed-HTTP is **v1-optional** but shipped in the CLJS reference. It lives in a separate artefact (`day8/re-frame2-http`); requiring `re-frame.http-managed` at app boot is what triggers its load-time fx registrations.

## When to use this pattern

Reach for it for any single-request / single-reply HTTP call. The fx bakes in transport, decoding, retry-with-backoff, abort, schema-driven decode, default reply-addressing, and frame-aware dispatch — so apps don't reinvent these per call site.

| Decision | Form |
|---|---|
| Event handler issues a one-off request; reply lands at the same handler or a sibling event | **fx form**: `:fx [[:rf.http/managed args]]` |
| Parent state machine wants the request tied to a state's lifetime, with auto-abort on state exit and `:after` timeout composition | **machine form**: `:invoke {:machine-id :rf.http/managed :data args}` |
| Parent state machine needs multiple concurrent requests with a join condition | **machine form** under `:invoke-all` |

Mix freely. Both surfaces coexist under one `:rf.http/managed` id in the registrar.

Out of scope here: streaming responses (chunked / SSE — sibling spec), bidirectional WebSocket (see `patterns/websocket.md`).

## The re-frame2 features this pattern uses

| Feature | Role |
|---|---|
| Co-located request and reply (default) | One event handler branches on `(:rf/reply msg)` — initial-dispatch branch issues the request; reply branch handles `{:kind :success :value v}` or `{:kind :failure :failure m}`. |
| Default reply addressing | Reply re-dispatches to the *originating* event id with `:rf/reply` merged onto the msg. Override with explicit `:on-success` / `:on-failure` for the separate-handler shape; pass `:on-failure nil` to swallow silently. |
| Schema-driven decode | `:decode <malli-schema>` runs the response through `010`'s decode pipeline; status-check fires BEFORE decode, so a 404 with HTML body classifies as `:rf.http/http-4xx`, never as `:rf.http/decode-failure`. |
| `:accept` post-decode normalisation | `(fn [decoded] {:ok v} or {:failure m})` lets a structurally-valid 200 surface as a domain failure. |
| `:retry` — transport-level only | Function of failure category + attempt count; nothing else enters. Semantic retry (refresh-then-retry, body-conditional, app-state-conditional) belongs in a state machine — see *§The retry-ownership boundary*. |
| `:request-id` abort | Stable `=`-comparable id (keyword, string, vector, uuid); `[:rf.http/managed-abort id]` cancels the in-flight request, dispatching `:rf.http/aborted` via the reply path. |
| Eight-category failure taxonomy | `:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/aborted`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/payload`. Closed set; both forms surface the same shape. |
| Machine-form wrapper | A child invokable machine of `:rf.http/managed` — `:invoke` it like any other; on the reply the wrapper transitions to `:succeeded` / `:failed` and dispatches `[<parent-id> [:succeeded value]]` / `[<parent-id> [:failed failure]]` back to the parent. Destroying the wrapper aborts the in-flight request. |

## Canonical declaration — fx form

```clojure
(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      ;; Reply branch — same handler, different path.
      (case (:kind reply)
        :success {:db (-> db
                          (assoc-in [:article :status] :loaded)
                          (assoc-in [:article :data]   (:value reply))
                          (assoc-in [:article :error]  nil))}
        :failure {:db (-> db
                          (assoc-in [:article :status] :error)
                          (assoc-in [:article :error]  (:failure reply)))})

      ;; Initial dispatch — issue the request.
      {:db (-> db
               (assoc-in [:article :status] :loading)
               (assoc-in [:article :error]  nil))
       :fx [[:rf.http/managed
             {:request {:method :get :url (str "/articles/" slug)}
              :decode  ArticleResponse           ;; Malli schema → decode + coerce
              :accept  (fn [decoded]
                         (if-let [a (:article decoded)]
                           {:ok a}
                           {:failure {:reason :missing-article}}))
              :retry   {:on           #{:rf.http/transport
                                        :rf.http/http-5xx
                                        :rf.http/timeout}
                        :max-attempts 4
                        :backoff      {:base-ms 250 :factor 2
                                       :max-ms  5000 :jitter true}}}]]})))
```

The fx defaults to **reply-to-origin**: the reply lands at `:article/load` (the originating event id) with `:rf/reply` merged onto the msg. The handler's `if-let` branches.

## Canonical declaration — machine-form wrapper

```clojure
(rf/reg-machine :app/auth
  {:initial :idle
   :states
   {:idle           {:on {:login :authenticating}}

    :authenticating
    {;; The wrapper actor is alive at [:rf/machines :rf.http/managed#N]
     ;; while this state is active. Exiting the state destroys the
     ;; wrapper, which aborts the in-flight request via the
     ;; :http/abort-on-actor-destroy hook.
     :invoke {:machine-id :rf.http/managed
              :data       {:request {:method :get :url "/api/me"}
                           :decode  :json
                           :retry   {:on #{:rf.http/transport :rf.http/http-5xx}
                                     :max-attempts 4
                                     :backoff {:base-ms 250 :factor 2
                                               :max-ms 5000 :jitter true}}}}
     :after  {30000 :timed-out}                   ;; wall-clock guard on the state
     :on     {:succeeded :authenticated           ;; payload at (:rf/event msg)
              :failed    :login-failed}}

    :authenticated {} :login-failed {} :timed-out {}}})
```

The wrapper handles its own `:rf.http/succeeded` / `:rf.http/failed` events internally and dispatches a clean `[parent-id [:succeeded value]]` or `[parent-id [:failed failure]]` to the parent — which the parent's `:on` map treats as ordinary FSM events. No glue code.

**Args carrier.** Every key the fx form's args map accepts may be passed through the parent's `:invoke :data` — `:request`, `:decode`, `:accept`, `:retry`, `:timeout-ms`, `:request-content-type`, etc. **Not passed through**: `:on-success` / `:on-failure` — the wrapper overrides these to route the reply back to itself.

**Multiple concurrent requests under one parent.** Use `:invoke-all`:

```clojure
{:hydrating
 {:invoke-all
  {:children       [{:id :user  :machine-id :rf.http/managed
                     :data {:request {:url "/api/me"}}}
                    {:id :prefs :machine-id :rf.http/managed
                     :data {:request {:url "/api/prefs"}}}]
   :join           :all
   :on-all-complete [:hydrate/done]
   :on-any-failed   [:hydrate/aborted]}}}
```

Each child wrapper aborts on cancel-on-decision; per-sibling cascade fires the abort hook independently.

## The retry-ownership boundary

A single test: **does the retry decision depend on anything other than failure category and attempt count?**

| Decision | Owner |
|---|---|
| "After a 5xx, wait `backoff(N)` and try again." | `:rf.http/managed` `:retry` — transport. |
| "After a network timeout, retry with backoff." | `:rf.http/managed` `:retry` — transport. |
| "After a 401, refresh the token, **then** retry." | State machine — semantic. |
| "Body says `:rate-limited`, wait the hinted delay." | State machine — semantic. |
| "Retry only if user is still on the page that issued the write." | State machine — semantic. |
| "Retry boot's failed load-asset, but not if the user navigated away." | State machine — semantic. |

Both layers compose. A machine's `:invoke` spawns a managed request that itself retries 5xx; once that loop terminates, the machine sees a single `:succeeded` / `:failed` event and transitions. No call site has to choose one layer — non-trivial requests configure both.

The full auth-machine worked example demonstrating 5xx-retry-at-transport AND 401-vs-refresh-at-semantic together lives in `patterns/boot.md` (the canonical illustration referenced from the spec).

## Variations

**Reply addressing.** Default is reply-to-origin. Override with explicit `:on-success` / `:on-failure` event vectors for the separate-handler shape. Pass `:on-failure nil` to swallow silently. The dispatched reply event carries the standard `:rf/reply` envelope: `{:kind :success :value v}` or `{:kind :failure :failure m}`.

**Abort.** `:request-id <id>` makes the request abortable: `[:rf.http/managed-abort id]` cancels it; the reply path dispatches `:rf.http/aborted`. External `AbortController` is also supported via `:abort-signal`. The wrapper form aborts automatically on state-exit (`:after` firing, parent transition, `:rf.machine/destroy`) — no manual cancellation slot needed.

**`:body` thunks.** Pass `:body (fn [] big-blob)` to defer body materialisation until after backoff delays elapse. Each retry re-invokes the thunk, so a single-shot stream gets a fresh handle per attempt.

**Schema reflection.** Declare `:rf.http/decode-schemas [Schema1 Schema2]` in the handler's metadata for tooling-time introspection (pair tools, generators). The runtime does NOT cross-check; it is reflective sugar.

**Frame awareness.** Reply dispatches inherit the originating event's `:frame`; the request crosses frame boundaries cleanly. No special-casing.

## Anti-patterns

- **Encoding semantic retry into `:retry :on`.** `:retry` is a function of category + attempt count — that's it. The moment the retry decision needs to inspect the response body, refresh a token, or check app state, lift the call site into a state machine. Bloating `:retry` with predicates hides control flow from traces.
- **Reaching for the raw `:http` fx when `:rf.http/managed` would do.** `:http` exists for wire-level control (custom transport, raw bytes, idiosyncratic protocols). For the common case, `:rf.http/managed` is the canonical answer and is what pair tools, `:fx-overrides`, and conformance fixtures key off.
- **Decoding before checking status.** Don't write a custom decode that throws on 4xx — the runtime classifies status BEFORE decode (a 404 with HTML body surfaces as `:rf.http/http-4xx` with raw body at `:body`, never as `:rf.http/decode-failure`). Your `:decode` only runs on 2xx.
- **Passing `:on-success` / `:on-failure` through the wrapper's `:invoke :data`.** They get overridden — the wrapper routes replies back to itself. Apps that want explicit reply addressing should use the fx form directly; the wrapper is for `:invoke`-orchestrated cases.
- **Storing the abort handle in `app-db`.** It's not a value. Use `:request-id` (any `=`-comparable value); the runtime holds the handle internally and you abort by id.
- **Re-implementing exponential backoff with `:dispatch-later`.** That's what `:retry :backoff` is for. The runtime version handles attempt-counter epoch carry, retry-attempt traces, and per-attempt timeout composition; rolling your own bypasses all of it.

## Worked example

`examples/reagent/managed_http_counter/` — counter where each button issues a managed HTTP request and the reply lands back via the default reply-addressing path. Covers the success path (`GET /api/inc.json`), the 404 path (`:rf.http/http-4xx` with HTML body — no `:rf.http/decode-failure` despite `:decode :json` because status-check fires first), the canned-success stub for the retry-recover demo, and `:request-id`-driven cancellation via `:rf.http/managed-abort`.

For the machine-form wrapper in production use, see the auth-flow worked example in `patterns/boot.md`.

## Pointers

- Full spec — args map, request envelope, every key — → SKILL-REDIRECT.md → *EP — HTTP requests (014)*.
- The eight failure categories, classification order, reply-payload shape → SKILL-REDIRECT.md → *EP — HTTP requests (014)*.
- Schema-driven decode + `010` integration → SKILL-REDIRECT.md → *EP — Schemas (010)*.
- Test stubs (`with-managed-request-stubs`) → SKILL-REDIRECT.md → *EP — HTTP requests (014)* §Testing.
- The retry-ownership worked example → `patterns/boot.md`.
- `:invoke` substrate the wrapper rides on → SKILL-REDIRECT.md → *EP — State machines (005)*.
