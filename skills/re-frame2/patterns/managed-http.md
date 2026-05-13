# Pattern — ManagedHTTP

`:rf.http/managed` — the canonical HTTP fx for re-frame2. Two affordances on one registrar id: the **fx form** for direct use from event handlers, and the **machine-form wrapper** for `:invoke` from a parent state machine. The contract — args map, failure categories, retry, abort, reply addressing — is identical across both.

`:rf.http/managed` is one instance of the **managed external effect** umbrella — alongside `:rf.ws/*`, state-machine `:invoke`, `:rf.server/*`, and `:rf.flow/*`. All five inherit the same eight-property contract (effect-as-data, framework-owned lifecycle, structured failure taxonomy, trace-bus observability, elision composition, built-in retry/abort/teardown, reply addressing, pair-tool override seam). See [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md) for the umbrella; the rest of this leaf is HTTP-specific.

> Managed-HTTP is **v1-optional** but shipped in the CLJS reference. It lives in `day8/re-frame2-http`; requiring `re-frame.http-managed` at app boot triggers its load-time registrations.

## When to use this pattern

Reach for it for any single-request / single-reply HTTP call. The fx bakes in transport, decoding, retry-with-backoff, abort, schema-driven decode, default reply-addressing, and frame-aware dispatch.

| Decision | Form |
|---|---|
| Event handler issues a one-off request | **fx form**: `:fx [[:rf.http/managed args]]` |
| Parent state machine wants the request tied to a state's lifetime, with auto-abort on exit and `:after` timeout composition | **machine form**: `:invoke {:machine-id :rf.http/managed :data args}` |
| Parent state machine needs multiple concurrent requests with a join condition | **machine form** under `:invoke-all` |

Mix freely. Both surfaces coexist under one `:rf.http/managed` id.

Out of scope: streaming responses (chunked / SSE), bidirectional WebSocket (see `patterns/websocket.md`).

## The re-frame2 features this pattern uses

| Feature | Role |
|---|---|
| Co-located request and reply (default) | One handler branches on `(:rf/reply msg)` — initial-dispatch branch issues the request; reply branch handles `{:kind :success :value v}` or `{:kind :failure :failure m}`. |
| Default reply addressing | Reply re-dispatches to the *originating* event id with `:rf/reply` merged. Override with explicit `:on-success` / `:on-failure`; pass `:on-failure nil` to swallow. |
| Schema-driven decode | `:decode <malli-schema>` runs via `010`'s decode pipeline. Status-check fires BEFORE decode, so a 404 with HTML body classifies as `:rf.http/http-4xx`, never `:rf.http/decode-failure`. |
| `:accept` post-decode normalisation | `(fn [decoded] {:ok v} or {:failure m})` lets a structurally-valid 200 surface as a domain failure. |
| `:retry` — transport-level only | Function of failure category + attempt count; nothing else. Semantic retry belongs in a state machine — see *§The retry-ownership boundary*. |
| `:request-id` abort | Stable `=`-comparable id; `[:rf.http/managed-abort id]` cancels in-flight, dispatching `:rf.http/aborted` via the reply path. |
| Eight-category failure taxonomy | `:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/aborted`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/payload`. Closed set. |
| Machine-form wrapper | A child invokable machine of `:rf.http/managed` — `:invoke` it like any other; on reply it transitions to `:succeeded` / `:failed` and dispatches `[<parent-id> [:succeeded value]]` / `[<parent-id> [:failed failure]]` back. Destroying the wrapper aborts in-flight. |

## Canonical declaration — fx form

```clojure
(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      (case (:kind reply)
        :success {:db (-> db (assoc-in [:article :status] :loaded)
                            (assoc-in [:article :data]   (:value reply))
                            (assoc-in [:article :error]  nil))}
        :failure {:db (-> db (assoc-in [:article :status] :error)
                            (assoc-in [:article :error]  (:failure reply)))})
      {:db (-> db (assoc-in [:article :status] :loading)
                  (assoc-in [:article :error]  nil))
       :fx [[:rf.http/managed
             {:request {:method :get :url (str "/articles/" slug)}
              :decode  ArticleResponse
              :accept  (fn [decoded] (if-let [a (:article decoded)]
                                       {:ok a} {:failure {:reason :missing-article}}))
              :retry   {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
                        :max-attempts 4
                        :backoff      {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}}]]})))
```

Defaults to **reply-to-origin**: reply lands at `:article/load` with `:rf/reply` merged.

## Canonical declaration — machine-form wrapper

```clojure
(rf/reg-machine :app/auth
  {:initial :idle
   :states
   {:idle           {:on {:login :authenticating}}
    :authenticating
    {;; Wrapper alive at [:rf/machines :rf.http/managed#N] while this state is active.
     ;; Exiting destroys the wrapper, which aborts the in-flight request.
     :invoke {:machine-id :rf.http/managed
              :data       {:request {:method :get :url "/api/me"}
                           :decode  :json
                           :retry   {:on #{:rf.http/transport :rf.http/http-5xx}
                                     :max-attempts 4
                                     :backoff {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}}}
     :after  {30000 :timed-out}
     :on     {:succeeded :authenticated
              :failed    :login-failed}}
    :authenticated {} :login-failed {} :timed-out {}}})
```

The wrapper handles its own internal events and dispatches `[parent-id [:succeeded value]]` or `[parent-id [:failed failure]]` to the parent — ordinary FSM events.

**Args carrier.** Every fx-form key passes through (`:request`, `:decode`, `:accept`, `:retry`, `:timeout-ms`, etc). **Not passed through**: `:on-success` / `:on-failure` — the wrapper overrides these to self-route.

**Multiple concurrent requests under one parent.** Use `:invoke-all`:

```clojure
{:hydrating
 {:invoke-all
  {:children [{:id :user  :machine-id :rf.http/managed :data {:request {:url "/api/me"}}}
              {:id :prefs :machine-id :rf.http/managed :data {:request {:url "/api/prefs"}}}]
   :join :all
   :on-all-complete [:hydrate/done]
   :on-any-failed   [:hydrate/aborted]}}}
```

Each child wrapper aborts on cancel-on-decision; per-sibling cascade fires independently.

## The retry-ownership boundary

A single test: **does the retry decision depend on anything other than failure category and attempt count?**

| Decision | Owner |
|---|---|
| "After a 5xx, wait `backoff(N)` and try again." | `:rf.http/managed` `:retry` — transport. |
| "After a network timeout, retry with backoff." | `:rf.http/managed` `:retry` — transport. |
| "After a 401, refresh the token, **then** retry." | State machine — semantic. |
| "Body says `:rate-limited`, wait the hinted delay." | State machine — semantic. |
| "Retry only if user is still on the page." | State machine — semantic. |

Both layers compose. A machine's `:invoke` spawns a managed request that itself retries 5xx; once that loop terminates the machine sees one `:succeeded` / `:failed` and transitions. The full auth-machine worked example combining 5xx-retry-at-transport AND 401-refresh-at-semantic lives in `patterns/boot.md`.

## Variations

**Reply addressing.** Default is reply-to-origin. Override with explicit `:on-success` / `:on-failure` event vectors. Pass `:on-failure nil` to swallow. Reply envelope: `{:kind :success :value v}` or `{:kind :failure :failure m}`.

**Abort.** `:request-id <id>` → `[:rf.http/managed-abort id]` cancels; reply path dispatches `:rf.http/aborted`. External `AbortController` via `:abort-signal`. The wrapper form aborts automatically on state-exit.

**`:body` thunks.** `:body (fn [] big-blob)` defers materialisation until after backoff. Each retry re-invokes — fresh handle per attempt.

**Schema reflection.** `:rf.http/decode-schemas [...]` in handler metadata is reflective sugar for pair tools / generators; runtime does NOT cross-check.

**Frame awareness.** Reply dispatches inherit the originating event's `:frame`; the request crosses frame boundaries cleanly.

## Anti-patterns

- **Encoding semantic retry into `:retry :on`.** `:retry` is category + attempt count only. Lift to a state machine the moment retry needs to inspect body, refresh a token, or check app state.
- **Reaching for the raw `:http` fx when `:rf.http/managed` would do.** `:http` is for wire-level control (custom transport, raw bytes). Common case is `:rf.http/managed` — what pair tools, `:fx-overrides`, and conformance fixtures key off.
- **Decoding before status check.** Runtime classifies status BEFORE decode; `:decode` only runs on 2xx. Don't write decoders that throw on 4xx.
- **Passing `:on-success` / `:on-failure` through the wrapper's `:invoke :data`.** They get overridden. Use the fx form directly when you want explicit reply addressing.
- **Storing the abort handle in `app-db`.** Not a value. Use `:request-id`; the runtime holds the handle.
- **Re-implementing exponential backoff with `:dispatch-later`.** That's what `:retry :backoff` is for — including epoch carry, traces, and per-attempt timeout composition.

## Worked example

`examples/reagent/managed_http_counter/` — each button issues a managed HTTP request. Covers success (`GET /api/inc.json`), 404 (`:rf.http/http-4xx` with HTML body — NOT `:rf.http/decode-failure` despite `:decode :json`), canned-success stub for retry-recover, and `:request-id` cancellation via `:rf.http/managed-abort`.

For the machine-form wrapper in production, see the auth-flow in `patterns/boot.md`.

## Pointers

- Full spec — args map, request envelope, failure categories, reply payload, test stubs (`with-managed-request-stubs`) → SKILL-REDIRECT.md → *EP — HTTP requests (014)*.
- Schema-driven decode → SKILL-REDIRECT.md → *EP — Schemas (010)*.
- Retry-ownership worked example → `patterns/boot.md`.
- `:invoke` substrate → SKILL-REDIRECT.md → *EP — State machines (005)*.

---

*Derived from `examples/reagent/managed_http_counter/` and `implementation/http/` @ main `89bd9c3`.*
