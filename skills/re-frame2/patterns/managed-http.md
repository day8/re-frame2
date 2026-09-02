# Pattern — Managed HTTP

`:rf.http/managed` — the canonical HTTP fx for re-frame2. Two affordances on one registrar id: the **fx form** for direct use from event handlers, and the **machine-form wrapper** for `:spawn` from a parent state machine. The contract — args map, failure categories, retry, abort, reply addressing — is identical across both.

`:rf.http/managed` is one of the four shipped instances of the **managed external effect** umbrella — alongside state-machine `:spawn`, `:rf.server/*`, `:rf.flow/*` (a WebSocket connection is the app/library-built case; re-frame2 ships **no** `:rf.ws/*`). All four inherit the eight common properties (effect-as-data, framework-owned lifecycle, structured failure taxonomy, trace-bus observability, elision composition, built-in retry/abort/teardown, pair-tool override seam, in-flight registry). **Property 9 — the uniform reply envelope** — is *async-only*: it applies to the async-completing family (managed HTTP, resources, mutations, machine async work, route loaders, future/test timer surfaces), **not** the synchronous `:rf.server/*` / `:rf.flow/*`. Managed HTTP is in that family — completion is one reply map with a closed `:status` `{:ok :error :cancelled}` (plus `:stale`, which is suppressed and never delivered to an app target), delivered to your reply target **appended as its final argument**, correlated by `:rf.reply/work-id`, with stale suppression. There is **one dialect**: the canonical envelope IS what your reply handler receives (no `{:kind :success/:failure}` reshape). **Reply addressing is required.** `:reply-to` is the unified target — one event for *both* success and failure, on which you branch on `(:status reply)`; `:on-success` / `:on-failure` are the split *routing sugar* over it (both receive the identical envelope; an explicit `nil` silences a branch). Omitting **every** reply target fails loud at fx-call time with `:rf.error/http-no-reply-target` — there is **no** co-located default that routes the reply back to the originating event. Read `:value` on `:ok`, the classified `:rf.http/*` map from `:error` on `:error` / `:cancelled`. (An HTTP `:request-id` is abort/correlation metadata, not a second stale key — the single attempt identity is `:rf.reply/work-id` on the reply, `:work/id` on any durable ledger row.) See [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md); the rest is HTTP-specific.

> Managed-HTTP is **v1-optional** but shipped in the CLJS reference. It lives in `day8/re-frame2-http`; requiring `re-frame.http.managed` at app boot triggers its load-time registrations.

## When to load

Reach for it for any single-request / single-reply HTTP call. The fx bakes in transport, decoding, retry-with-backoff, abort, schema-driven decode, reply addressing, and frame-aware dispatch.

| Decision | Form |
|---|---|
| Event handler issues a one-off request | **fx form**: `:fx [[:rf.http/managed args]]` |
| Parent state machine wants the request tied to a state's lifetime, with auto-abort on exit and `:after` timeout composition | **machine form**: `:spawn {:machine-id :rf.http/managed :data args}` |
| Parent state machine needs multiple concurrent requests with a join condition | **machine form** under `:spawn-all` |

Mix freely. Both surfaces coexist under one `:rf.http/managed` id.

Out of scope: streaming responses (chunked / SSE), bidirectional WebSocket (see `patterns/websocket.md`).

## The re-frame2 features this pattern uses

| Feature | Role |
|---|---|
| Required reply addressing | Every request MUST name a reply target — one of `:reply-to`, `:on-success`, `:on-failure`. Omitting all three fails loud at fx-call time (`:rf.error/http-no-reply-target`); there is no co-located default. |
| Unified vs split targets | `:reply-to` is one target for both success and failure — the reply handler branches on `(:status reply)` handling `{:status :ok :value v …}`, `{:status :error :error m …}`, or `{:status :cancelled :error m …}`. `:on-success` / `:on-failure` are the split routing sugar over it; pass `:on-failure nil` to swallow. |
| Schema-driven decode | `:decode <malli-schema>` runs via `010`'s decode pipeline. Status-check fires BEFORE decode, so a 404 with HTML body classifies as `:rf.http/http-4xx`, never `:rf.http/decode-failure`. |
| `:accept` post-decode normalisation | `(fn [decoded] {:ok v} or {:failure m})` lets a structurally-valid 200 surface as a domain failure. |
| `:retry` — transport-level only | Function of failure category + attempt count; nothing else. Semantic retry belongs in a state machine — see *§The retry-ownership boundary*. |
| `:request-id` abort | Stable `=`-comparable id; `[:rf.http/managed-abort id]` cancels in-flight, delivering a `{:status :cancelled …}` envelope (its `:error` carries `{:kind :rf.http/aborted …}`) to your reply target. |
| Eight-category failure taxonomy | `:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/aborted`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`. Closed set. |
| Machine-form wrapper | A child invokable machine of `:rf.http/managed` — `:spawn` it like any other; on reply it transitions to `:succeeded` / `:failed` and dispatches `[<parent-id> [:succeeded value]]` / `[<parent-id> [:failed failure]]` back. Destroying the wrapper aborts in-flight. |

## Canonical declaration — fx form

The issuing handler declares the request and names its reply target; a **dedicated reply handler** receives the canonical envelope appended as its final argument and branches on `(:status reply)`. The request MUST address its reply — here with the unified `:reply-to`.

```clojure
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    {:db (-> db (assoc-in [:article :status] :loading)
                (assoc-in [:article :error]  nil))
     :fx [[:rf.http/managed
           {:request   {:method :get :url (str "/articles/" slug)}
            :decode    ArticleResponse
            :accept    (fn [decoded] (if-let [a (:article decoded)]
                                       {:ok a} {:failure {:reason :missing-article}}))
            :retry     {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
                        :max-attempts 4
                        :backoff      {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}
            :reply-to  [:article/load-replied]}]]}))   ;; ← required: names the reply target

;; The reply target. The canonical envelope is appended as the final arg;
;; branch on :status. `:reply-to` delivers ALL delivered terminal statuses
;; here (:ok / :error / :cancelled), so handle each — the failure path is
;; impossible to overlook. `:stale` is suppressed BEFORE dispatch, so it
;; never reaches this handler: a reply that arrives here is always current.
(rf/reg-event :article/load-replied
  (fn [{:keys [db]} [_ reply]]
    (case (:status reply)
      :ok        {:db (-> db (assoc-in [:article :status] :loaded)
                             (assoc-in [:article :data]   (:value reply))
                             (assoc-in [:article :error]  nil))}
      :error     {:db (-> db (assoc-in [:article :status] :error)
                             (assoc-in [:article :error]  (:error reply)))}
      :cancelled {:db (assoc-in db [:article :status] :cancelled)})))
```

The reply dispatched to `:article/load-replied` is `[:article/load-replied {:status :ok :value article … :rf.reply/work-id […] :completed-at …}]` on success, or `[:article/load-replied {:status :error :error {:kind :rf.http/… …} …}]` on failure. Prefer the split `:on-success [:article/loaded] :on-failure [:article/load-error]` when you want one single-purpose handler per branch; both styles deliver the identical canonical envelope.

## Canonical declaration — machine-form wrapper

```clojure
(rf/reg-machine :app/auth
  {:initial :idle
   :states
   {:idle           {:on {:login :authenticating}}
    :authenticating
    {;; Wrapper alive at [:rf.runtime/machines :snapshots :rf.http/managed#N] (runtime-db) while this state is active.
     ;; Exiting destroys the wrapper, which aborts the in-flight request.
     :spawn {:machine-id :rf.http/managed
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

**Args carrier.** Every fx-form key passes through (`:request`, `:decode`, `:accept`, `:retry`, `:timeout-ms`, etc). **Reply addressing is overridden**: the wrapper assigns `:on-success` / `:on-failure` back to itself to self-route, so any `:reply-to` / `:on-success` / `:on-failure` you put in `:data` is ignored — you address the parent through the wrapper's `:succeeded` / `:failed` transitions, not a reply-target key.

**Multiple concurrent requests under one parent.** Use `:spawn-all`:

```clojure
{:hydrating
 {:spawn-all
  {:children [{:id :user  :machine-id :rf.http/managed :data {:request {:url "/api/me"}}}
              {:id :prefs :machine-id :rf.http/managed :data {:request {:url "/api/prefs"}}}]
   :join :all
   :on-child-done   :hydrate/child-done    ;; child-keyword children dispatch on success (REQUIRED)
   :on-child-error  :hydrate/child-error    ;; child-keyword children dispatch on failure (REQUIRED)
   :on-all-complete [:hydrate/done]
   :on-any-failed   [:hydrate/aborted]}}}
```

Each child wrapper aborts when the join resolves and surviving siblings are torn down; per-sibling cascade fires independently.

## The retry-ownership boundary

A single test: **does the retry decision depend on anything other than failure category and attempt count?**

| Decision | Owner |
|---|---|
| "After a 5xx, wait `backoff(N)` and try again." | `:rf.http/managed` `:retry` — transport. |
| "After a network timeout, retry with backoff." | `:rf.http/managed` `:retry` — transport. |
| "After a 401, refresh the token, **then** retry." | State machine — semantic. |
| "Body says `:rate-limited`, wait the hinted delay." | State machine — semantic. |
| "Retry only if user is still on the page." | State machine — semantic. |

Both layers compose. A machine's `:spawn` spawns a managed request that itself retries 5xx; once that loop terminates the machine sees one `:succeeded` / `:failed` and transitions. The full auth-machine worked example combining 5xx-retry-at-transport AND 401-refresh-at-semantic lives in SKILL-REDIRECT.md → *Pattern — Boot* §Worked example — auth-machine and the retry-ownership boundary.

## Variations

**Reply addressing.** Required — supply `:reply-to` (unified: one target for both branches), or `:on-success` / `:on-failure` (split routing sugar over it — both receive the identical reply; either overrides the `:reply-to` base for its branch; `:on-failure nil` swallows). Omitting every target fails loud (`:rf.error/http-no-reply-target`); **there is no reply-to-origin default**. **The delivered reply IS the canonical EP-0011 envelope** (no reshape, no separate `:kind` dialect), appended as the target's final argument: `{:status :ok :value v …}`, `{:status :error :error m …}`, or `{:status :cancelled :error m …}`, plus `:rf.reply/work-id` / `:completed-at`. Branch on `(:status reply)`; read the decoded body from `:value` on `:ok` and the classified `:rf.http/*` failure map from `:error` on `:error` / `:cancelled`. A stale envelope (`:status :stale`, suppressed by work-id) never dispatches an app target, so a reply reaching your handler is always current.

**Abort.** `:request-id <id>` → `[:rf.http/managed-abort id]` cancels; the reply target receives a `{:status :cancelled …}` envelope whose `:error` carries `{:kind :rf.http/aborted …}`. External `AbortController` via `:abort-signal`. The wrapper form aborts automatically on state-exit.

**`:body` thunks.** `:body (fn [] big-blob)` defers materialisation until after backoff. Each retry re-invokes — fresh handle per attempt.

**Schema reflection.** `:rf.http/decode-schemas [...]` in handler metadata is reflective sugar for pair tools / generators; runtime does NOT cross-check.

**Frame awareness.** Reply dispatches inherit the originating event's `:frame`; the request crosses frame boundaries cleanly.

## Anti-patterns

- **Encoding semantic retry into `:retry :on`.** `:retry` is category + attempt count only. Lift to a state machine the moment retry needs to inspect body, refresh a token, or check app state.
- **Reaching for the raw `:http` fx when `:rf.http/managed` would do.** `:http` is for wire-level control (custom transport, raw bytes). Common case is `:rf.http/managed` — what pair tools, `:fx-overrides`, and conformance fixtures key off.
- **Decoding before status check.** Runtime classifies status BEFORE decode; `:decode` only runs on 2xx. Don't write decoders that throw on 4xx.
- **Passing `:reply-to` / `:on-success` / `:on-failure` through the wrapper's `:spawn :data`.** They get overridden — the wrapper self-routes to the parent. Use the fx form directly when you want explicit reply addressing.
- **Storing the abort handle in `app-db`.** Not a value. Use `:request-id`; the runtime holds the handle.
- **Re-implementing exponential backoff with `:dispatch-later`.** That's what `:retry :backoff` is for — including epoch carry, traces, and per-attempt timeout composition.

## Worked example

`examples/core/managed_http_counter/` — each button issues a managed HTTP request. Covers success (`GET /api/inc.json`), 404 (`:rf.http/http-4xx` with HTML body — NOT `:rf.http/decode-failure` despite `:decode :json`), canned-success stub for retry-recover, and `:request-id` cancellation via `:rf.http/managed-abort`.

For the machine-form wrapper in production, see the auth-flow worked example — SKILL-REDIRECT.md → *Pattern — Boot* §Worked example — auth-machine and the retry-ownership boundary.

## Pointers

- Full spec — args map, request envelope, failure categories, reply payload, test stubs (`with-managed-request-stubs`, ships in `re-frame.http.test-support`) → SKILL-REDIRECT.md → *EP — HTTP requests (014)*.
- Schema-driven decode → SKILL-REDIRECT.md → *EP — Schemas (010)*.
- Retry-ownership worked example (401-then-refresh) → SKILL-REDIRECT.md → *Pattern — Boot* §Worked example — auth-machine and the retry-ownership boundary.
- `:spawn` substrate → SKILL-REDIRECT.md → *EP — State machines (005)*.

---

*Derived from `examples/core/managed_http_counter/` and `implementation/http/` @ main.*
