# O-17. Convert `day8.re-frame/http-fx` (`:http-xhrio`) requests to re-frame2 managed HTTP (`:rf.http/managed`)

> **Type B** (semantic rewrite, ask first). The agent identifies every `:http-xhrio` call site, surfaces the proposed `:rf.http/managed` shape per site, and asks the operator to approve before applying — the rewrite is a domain-level re-thinking of decode / failure / retry semantics, not a structural lift. Mechanical translation handles the common cases (GET / POST with JSON request and response, fixed `:on-success` / `:on-failure` event targets, no retry). Hits that lean on `:request-format` for non-JSON content types, custom `:response-format` decoders, ajax interceptor chains, in-flight abort via the underlying `XhrIo` handle, or per-request progress callbacks escalate to a human.

> **Cross-references.** Required-rule [M-31](README.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http) catalogues the `day8/re-frame2-http` Maven artefact that ships `:rf.http/managed` — adopting O-17 means picking up that dep alongside core. Required-rule [M-39](README.md#m-39-reg-http-interceptor--clear-http-interceptor--additive-request-side-middleware-on-rfhttpmanaged) names the per-frame request-side interceptor surface that replaces ajax-cljs's middleware chain. Companion opt-in [O-16](README.md#o-16-convert-day8re-frameasync-flow-fx-flows-to-reg-machine-rf2-qonq4) covers the sibling `async-flow-fx` add-on; codebases adopting both rewrites usually want to run O-17 first (so the orchestrating machine's `:spawn-all` children call the new managed-HTTP fx) and O-16 second.

---

## Summary

`day8.re-frame/http-fx` ([repo](https://github.com/day8/re-frame-http-fx)) is a v1-era add-on lib that ships a single fx — `:http-xhrio` — wrapping the Google Closure `XhrIo` transport behind a re-frame `reg-fx` registration. The fx takes an args map (`{:method ... :uri ... :params ... :format ... :response-format ... :on-success ... :on-failure ...}`) and dispatches a user-named success / failure event when the request resolves. The lib was the de facto HTTP layer for v1 codebases — `XhrIo` was the only host-portable Google-Closure-backed option in the pre-Fetch era.

re-frame2 covers the same use-case with `:rf.http/managed` (per [014-HTTPRequests.md](../../spec/014-HTTPRequests.md), shipped in the [M-31](README.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http) artefact `day8/re-frame2-http`): the request envelope is the same shape (`:method`, `:url`, `:headers`, `:params`, `:body`), but the response surface is richer — an eight-category closed failure taxonomy (`:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`, `:rf.http/aborted`), schema-driven Malli decode + `:accept` projection, first-class retry-with-backoff, per-attempt timeouts, abort via `:request-id`, classification ordering that puts status before decode, and a co-located reply addressing mode that lets one event handler branch on `(:rf/reply msg)` for both request issue and async result. Trace events (`:rf.http/retry-attempt`, the per-category failure traces, `:rf.warning/decode-defaulted`) integrate with the standard trace surface that 10x, Causa, Tool-Pair, and the `register-trace-listener!` listener API consume.

## Why the rewrite is opt-in

`day8.re-frame/http-fx` is an **add-on lib** with a separate Maven coordinate; nothing in re-frame's core surface depends on it, and nothing in re-frame2 breaks when a project keeps using it. A v1 codebase can in principle:

1. Continue to depend on `day8.re-frame/http-fx` (the lib is built on `reg-fx`, `reg-event-fx`, and `dispatch` — every surface it consumes is preserved in v2).
2. Migrate request-by-request to `:rf.http/managed` as part of broader v2 modernisation.

The rule is opt-in (O-rule, not M-rule) because (1) is technically valid. The migration agent does NOT auto-rewrite — every `:http-xhrio` site is surfaced for operator approval per call site, because the rewrite is semantic (the failure surface is a re-thinking, not a structural lift).

The agent SHOULD recommend (2) when the codebase is otherwise adopting re-frame2 idioms — managed-HTTP requests integrate with `:fx-overrides` test stubbing, the canned-stub fxs (`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure` per [M-31a](README.md#m-31a-managed-http-canned-stub-fxs-require-re-framehttp-test-support-rf2-cdmle)), the per-frame request-side interceptor surface ([M-39](README.md#m-39-reg-http-interceptor--clear-http-interceptor--additive-request-side-middleware-on-rfhttpmanaged)), schema-driven decode, transport-vs-semantic retry split, and the eight-category trace surface that 10x and Causa visualise out of the box; ajax-cljs's `:on-success` / `:on-failure` dispatch surface is opaque to all of them.

## Detection

The agent looks for:

- Maven coord `day8/re-frame-http-fx` in `deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn` (any version).
- `(:require [day8.re-frame.http-fx])` in any namespace (the require has no public symbols beyond the fx-registration side-effect; the require alone is enough to indicate adoption).
- `:http-xhrio` keys inside effect maps returned by `reg-event-fx` handlers — the unmistakable fingerprint. The key may appear at the top level (v1 effect-map shape, pre-M-8) or inside `:fx` (post-M-8 shape `:fx [[:http-xhrio {...}]]`).
- Less commonly: `(rf/reg-fx :http-xhrio ...)` registrations in user code that wrapped or overrode the lib's fx (uncommon — most projects use the lib's registration as-is).

Each call site is one request. The agent presents the request, the proposed `:rf.http/managed` form, and the diff for operator approval before any edit.

## `:http-xhrio` → `:rf.http/managed` concept mapping

The shapes are structurally similar — both take an args map and dispatch a result — but the vocabulary diverges in three places: response decoding (ajax-cljs uses `:response-format` keywords backed by `cljs-ajax` decoders; managed-HTTP uses `:decode` keywords or a Malli schema), failure projection (ajax-cljs hands the user the raw `XhrIo` response on `:on-failure`; managed-HTTP categorises into eight `:rf.http/*` kinds before the user sees the reply), and retry (ajax-cljs has none; managed-HTTP ships transport-level retry as a first-class slot).

| `:http-xhrio` concept | `:rf.http/managed` concept | Notes |
|---|---|---|
| `:method` (`:get`, `:post`, `:put`, ...) | `:request :method` (same keyword set) | One-to-one rename. Default is `:get` in both. |
| `:uri` | `:request :url` | Name change. Both accept a string; both URL-encode `:params`. |
| `:params` (query string for GET, body for POST) | `:request :params` (always query) + `:request :body` (request body) | ajax-cljs overloads `:params` for both query and body depending on `:method`; managed-HTTP splits them — `:request :params` is always the URL query (per [014 §Request envelope](../../spec/014-HTTPRequests.md#request-envelope)), `:request :body` is the request body. Rewrite GET as `:params {...}`; rewrite POST/PUT/PATCH as `:body {...}` (Clojure-coll bodies serialise per `:request-content-type`, per [014 §Body encoding](../../spec/014-HTTPRequests.md#body-encoding)). |
| `:headers` | `:request :headers` | One-to-one (map of string → string). |
| `:timeout` (ms; library default 0 / unbounded) | `:timeout-ms` (per-attempt; **default 30000 ms**) | ajax-cljs defaults to unbounded; managed-HTTP defaults to 30000 ms per attempt as a security floor (per [014 §`:timeout-ms` security defaults](../../spec/014-HTTPRequests.md#timeout-ms-security-defaults-rf2-it1cd)). The rewrite **leaves the default in place** unless the v1 site explicitly set `:timeout` to a non-default value; opt-outs (`:timeout-ms nil` / `:timeout-ms 0`) carry deliberate intent and should be rare. Apps facing untrusted upstreams MUST NOT default-port `:timeout 0` to `:timeout-ms 0`. |
| `:format (ajax/json-request-format)` (the request-body encoder helper) | `:request :request-content-type :json` (or `:form`, `:text`) | ajax-cljs takes a *fn* via `(ajax/json-request-format)`; managed-HTTP takes a *keyword* sugar. Most v1 sites are one of `json-request-format` / `url-request-format` / `text-request-format`; each lowers to the corresponding keyword. Custom formatters (rare) escalate to a human. |
| `:response-format (ajax/json-response-format {:keywords? true})` (the response decoder) | `:decode :json` (or `:text` / `:blob` / `:array-buffer` / `:form-data` / a Malli schema / a custom fn) | ajax-cljs's `:keywords? true` keywordises JSON object keys; managed-HTTP keywordises by default (with the keyword-interning cap of [014 §Keyword-interning cap](../../spec/014-HTTPRequests.md#keyword-interning-cap-rf2-wu1n5) for DoS resistance). For untrusted-origin JSON, prefer `:decode :text` and parse explicitly — see the security note below. **The canonical upgrade** is `:decode <MalliSchema>` (schema-driven decode + validation); for codebases without Malli schemas yet, `:decode :json` is the like-for-like rewrite. |
| `:on-success [:event-id]` | (default — co-located reply) OR `:on-success [:event-id]` | Two addressing modes. **Default (omitted)**: the reply dispatches back to the originating event id with `:rf/reply` merged into the original message — one handler with `(if-let [reply (:rf/reply msg)] ...handle-reply... ...issue-request...)` branches on the sentinel. **Explicit `:on-success [...]`**: the reply payload is appended as the last arg, matching the v1 dispatch shape. Pick the explicit form when porting v1 sites — it's the like-for-like rewrite and avoids the operator having to refactor the handler shape in the same pass. New code should prefer the co-located form (per [014 §Reply addressing](../../spec/014-HTTPRequests.md#reply-addressing)). |
| `:on-failure [:event-id]` (raw `XhrIo` response handed to handler) | `:on-failure [:event-id]` (categorised `:rf.http/*` failure) | The dispatch shape is the same; the **payload changes shape**. v1's `:on-failure` handler receives the raw `XhrIo` response (`{:status N :status-text "" :failure :error :response ... :headers {...}}`); v2's `:on-failure` handler receives `{:rf/reply {:kind :failure :failure {:kind <:rf.http/transport-or-similar> ...kind-tags...}}}` (per [014 §Failure categories](../../spec/014-HTTPRequests.md#failure-categories-closed-set)). The rewrite MUST update the handler body to read the new shape — branching on the v2 `:kind` (`:rf.http/transport` / `:rf.http/http-4xx` / `:rf.http/http-5xx` / `:rf.http/timeout` / `:rf.http/decode-failure` / `:rf.http/accept-failure` / `:rf.http/aborted` / `:rf.http/cors`) rather than the v1 `:status` / `:failure` slots. |
| `:on-request [:event-id]` (lib's per-request lifecycle hook) | (drop) | ajax-cljs dispatches `:on-request` before sending — used for request logging. The equivalent in v2 is a `register-trace-listener!` listener on `:rf.http/request-sent` (per [014 §Trace surface](../../spec/014-HTTPRequests.md)) — observer-shaped, not handler-shaped. Cross-cutting concerns should not ride request envelopes. |
| `:interceptors` (cljs-ajax interceptor chain — request transform / response transform middleware) | `(rf/reg-http-interceptor {:frame ... :id ... :before ...})` per [M-39](README.md#m-39-reg-http-interceptor--clear-http-interceptor--additive-request-side-middleware-on-rfhttpmanaged) | ajax-cljs's middleware chain (Bearer-auth header injection, dev-mode base-URL rewrite, correlation-id stamping) ports to the per-frame `reg-http-interceptor` surface. Register once per frame; every `:rf.http/managed` request from that frame picks up the transform. Per-request `:interceptors` (rare in v1) lower to bespoke `:request :headers` / `:request :url` shaping at the call site. |
| No retry support | `:retry {:on #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout} :max-attempts 4 :backoff {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}` | ajax-cljs has no retry; v1 codebases either implemented retry by hand (re-dispatching the request event from the `:on-failure` handler) or didn't bother. v2 ships transport-level retry as a first-class slot — function of attempt count + failure category, no body inspection (per [014 §Boundary — transport vs semantic retry](../../spec/014-HTTPRequests.md#boundary--transport-vs-semantic-retry)). **The rewrite SHOULD recommend adding `:retry`** for endpoints that benefit from it (idempotent reads against flaky upstreams); the operator picks the `:max-attempts` and `:backoff` shape per endpoint. Hand-rolled retry in `:on-failure` handlers becomes obsolete — flag those handlers for cleanup in the same pass. |
| Custom abort via the `XhrIo` handle | `:request-id <id>` + `(rf/dispatch [:rf.fx/managed-abort <id>])` | ajax-cljs callers reached into the underlying `XhrIo` handle to cancel — a CLJS-only escape hatch. v2 ships abort as a first-class slot: tag the request with a stable `:request-id` (keyword / vector / uuid), then dispatch `:rf.http/managed-abort` with that id. Abort always wins over the reply (per [014 §Abort precedence](../../spec/014-HTTPRequests.md#abort-precedence-abort-always-wins--rf2-wez75)) — the reply lands as `:rf.http/aborted` with `:reason :user`, never as the transport-completion category that arrived at the same time. The rewrite is mechanical when the v1 codebase already had an abort site; flag every reach-into-`XhrIo` for the cleaner v2 surface. |
| `:progress-cb` (per-XHR upload / download progress) | (escalate) | ajax-cljs exposed `XhrIo`'s progress events as a callback. Managed-HTTP does NOT ship per-request progress callbacks for v1 — streaming + bidirectional flows are out of scope per [014 §Abstract](../../spec/014-HTTPRequests.md#abstract). Sites that depend on progress events escalate to a human; the migration paths are (a) keep the v1 add-on for those specific endpoints, (b) drop the progress UI if it's nice-to-have, (c) wait for a future streaming-aware spec. |

## Before / after — representative GET + JSON decode

This is the canonical `:http-xhrio` shape from a typical v1 app — a GET that fetches an article by slug, decoding JSON, dispatching a load-success or load-error event with the result.

### Before — `:http-xhrio`

```clojure
(ns my-app.articles
  (:require [re-frame.core :as rf]
            [day8.re-frame.http-fx]                     ;; registers the :http-xhrio fx
            [ajax.core :as ajax]))

(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (-> db
             (assoc-in [:article :status] :loading)
             (assoc-in [:article :error]  nil))
     :http-xhrio {:method          :get
                  :uri             (str "/articles/" slug)
                  :timeout         10000
                  :response-format (ajax/json-response-format {:keywords? true})
                  :on-success      [:article/load-success]
                  :on-failure      [:article/load-failure]}}))

(rf/reg-event-db :article/load-success
  (fn [db [_ response]]
    (-> db
        (assoc-in [:article :status] :loaded)
        (assoc-in [:article :data]   response)
        (assoc-in [:article :error]  nil))))

(rf/reg-event-db :article/load-failure
  (fn [db [_ {:keys [status response]}]]
    (-> db
        (assoc-in [:article :status] :error)
        (assoc-in [:article :error]  {:status status :detail response}))))
```

### After — `:rf.http/managed` (like-for-like, explicit targets)

```clojure
(ns my-app.articles
  (:require [re-frame.core :as rf]
            [re-frame.http-managed]))                   ;; per M-31 — required so :rf.http/* fxs register

(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (-> db
             (assoc-in [:article :status] :loading)
             (assoc-in [:article :error]  nil))
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    (str "/articles/" slug)}
            :decode     :json                            ;; or a Malli schema — see below
            :timeout-ms 10000
            :on-success [:article/load-success]
            :on-failure [:article/load-failure]}]]}))

(rf/reg-event-db :article/load-success
  (fn [db [_ {:keys [value]}]]                          ;; reply lands as {:kind :success :value v}
    (-> db
        (assoc-in [:article :status] :loaded)
        (assoc-in [:article :data]   value)
        (assoc-in [:article :error]  nil))))

(rf/reg-event-db :article/load-failure
  (fn [db [_ {:keys [failure]}]]                        ;; reply lands as {:kind :failure :failure {...}}
    (-> db
        (assoc-in [:article :status] :error)
        (assoc-in [:article :error]
                  (case (:kind failure)
                    :rf.http/http-4xx       {:kind :not-found  :status (:status failure)}
                    :rf.http/http-5xx       {:kind :server-err :status (:status failure)}
                    :rf.http/transport      {:kind :network    :message (:message failure)}
                    :rf.http/timeout        {:kind :timeout    :elapsed-ms (:elapsed-ms failure)}
                    :rf.http/decode-failure {:kind :bad-payload :cause (:cause failure)}
                    {:kind :unknown :failure failure})))))
```

What changed:

- **The fx moves from a top-level effect key (`:http-xhrio`) into `:fx`.** This is the M-8 reshape — every fx in v2 lives in `:fx`. (Both shapes are valid in pre-M-8 v1 code; the post-M-8 shape is the v2-canonical form.)
- **The request envelope splits: `:uri` → `:request :url`, `:method` → `:request :method`.** Every wire-shape key (`:method`, `:url`, `:headers`, `:params`, `:body`) is nested under `:request`; lifecycle keys (`:decode`, `:accept`, `:retry`, `:timeout-ms`, `:on-success`, `:on-failure`, `:request-id`, `:abort-signal`) stay at the top level.
- **`:response-format (ajax/json-response-format {:keywords? true})` becomes `:decode :json`.** The keywordisation default is the same; the surface vocabulary is keyword-sugar instead of a fn-valued slot.
- **`:on-success` payload changes from `response` to `{:kind :success :value <response>}`.** The v1 handler received the decoded body as the last arg; the v2 handler receives a reply envelope. The handler body destructures `{:keys [value]}` to get the payload.
- **`:on-failure` payload changes from `{:status :response :failure}` to `{:kind :failure :failure {:kind <:rf.http/*> ...kind-tags...}}`.** The v1 handler keyed off `:status` (HTTP status code) and `:failure` (ajax-cljs's failure enum); the v2 handler keys off `(:kind failure)`, a closed `:rf.http/*` keyword. The rewrite of the failure body is the substantial part of every per-call-site conversion — the operator decides which categories the app handles distinctly vs lumps into a single "show error UI" branch.
- **The `(:require [re-frame.http-managed])` clause is added.** Per [M-31](README.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http), the managed-HTTP namespace's load-time registrations must fire before the `[:rf.http/managed ...]` entry hits the drain; without the require, the `:fx` runner raises `:rf.error/no-such-fx`. (The `[day8.re-frame.http-fx]` require is dropped.)

### After — `:rf.http/managed` (schema-driven decode, recommended)

The like-for-like rewrite above replaces `:response-format (ajax/json-response-format)` with `:decode :json` — same JSON parsing, no validation. The canonical v2 upgrade pairs `:decode` with a Malli schema, so the runtime validates the wire payload and surfaces `:rf.http/decode-failure` on a malformed-but-2xx response (per [014 §Decoding](../../spec/014-HTTPRequests.md#decoding) and [014 §Classification order](../../spec/014-HTTPRequests.md#classification-order)):

```clojure
(def ArticleResponse
  [:map
   [:id     :uuid]
   [:slug   :string]
   [:title  :string]
   [:body   :string]
   [:author [:map [:id :uuid] [:name :string]]]])

(rf/reg-event-fx :article/load
  {:rf.http/decode-schemas [ArticleResponse]}            ;; optional — pair-tool reflection
  (fn [{:keys [db]} [_ slug]]
    {:db (-> db
             (assoc-in [:article :status] :loading)
             (assoc-in [:article :error]  nil))
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    (str "/articles/" slug)}
            :decode     ArticleResponse                  ;; schema-driven
            :timeout-ms 10000
            :on-success [:article/load-success]
            :on-failure [:article/load-failure]}]]}))
```

The schema-driven path is **strictly stronger** than `:decode :json`: a 2xx response whose body fails to validate against the schema lands as `:rf.http/decode-failure` instead of as a hard-to-debug "the field I expected is missing" runtime error deeper in the app. Pair tools introspect `:rf.http/decode-schemas` to know which payloads a handler expects from the wire (per [014 §Schema reflection](../../spec/014-HTTPRequests.md#schema-reflection-optional-ergonomic)). The agent SHOULD propose the schema-driven form whenever the codebase already has Malli schemas in play (the M-x sweep for `reg-app-schema` / per-event `:spec` keys signals this); when no schemas exist yet, propose `:decode :json` as the immediate rewrite and surface "add `:rf.http/decode-schemas` per endpoint" as a follow-on modernisation.

## Mapping notes per `:http-xhrio` slot

### `:method` / `:uri` / `:params` / `:headers`

Mechanical rename (`:uri` → `:url`, all keys nested under `:request`). The split of v1's overloaded `:params` (query for GET, body for POST) into v2's `:request :params` (always query) + `:request :body` (always body) is the one judgement the agent makes — read the `:method` slot to disambiguate, and place the v1 `:params` map into the v2 slot the method implies.

### `:format` (request body encoder)

The v1 fn-valued slot lowers to a v2 keyword sugar on `:request :request-content-type`:

| v1 `:format` | v2 `:request :request-content-type` |
|---|---|
| `(ajax/json-request-format)` | `:json` (Clojure body → JSON wire bytes) |
| `(ajax/url-request-format)` | `:form` (Clojure body → URL-encoded form body) |
| `(ajax/text-request-format)` | `:text` (Clojure body → string body) |
| custom request formatter | escalate — the rewrite needs to know what the formatter does |

The v2 keyword sugar handles the common cases. If the v1 site already passes a `Blob` / `FormData` / `ArrayBuffer` as `:body` and lets the host set the Content-Type (multipart upload is the typical case), the v2 `:request :body` accepts the same host types — pass through without setting `:request-content-type`.

### `:response-format` (response body decoder)

Mechanical mapping in the common cases:

| v1 `:response-format` | v2 `:decode` |
|---|---|
| `(ajax/json-response-format {:keywords? true})` | `:decode :json` (or a Malli schema — recommended) |
| `(ajax/json-response-format)` (no keywordisation) | `:decode :text` + `(js/JSON.parse %)` in `:accept` (managed-HTTP keywordises JSON object keys by default; opt out via `:decode :text` and parse explicitly) |
| `(ajax/text-response-format)` | `:decode :text` |
| `(ajax/raw-response-format)` | `:decode :text` or `:decode :blob` (depending on whether the v1 code consumed text or bytes) |
| custom response decoder | escalate — a `(fn [response-text headers] decoded)` fn-valued `:decode` slot is supported (per [014 §Custom function](../../spec/014-HTTPRequests.md#custom-function)), but the rewrite needs to understand what the decoder does to map it faithfully |

**Security note: untrusted-origin JSON.** ajax-cljs's `{:keywords? true}` interns every JSON object key as a CLJS keyword — on the JVM (SSR / webhook receivers), keywords are interned and never garbage-collected, so a compromised upstream returning N unique-key JSON per response would permanently burn N keyword slots per response. Managed-HTTP enforces a per-request keyword-interning cap (`:rf.http/max-decoded-keys`, default 10000) and surfaces `:rf.http/decode-failure :reason :too-many-keys` on overflow (per [014 §Keyword-interning cap](../../spec/014-HTTPRequests.md#keyword-interning-cap-rf2-wu1n5)). For endpoints whose response shape is dynamic or attacker-influenceable (partner JSON APIs, webhook receivers, agent-controlled fetches), prefer `:decode :text` + explicit `(get response "key")` over string-keyed maps — the rewrite SHOULD surface this for every `:http-xhrio` call site against an untrusted origin and let the operator confirm.

### `:on-success` / `:on-failure` payload shape

The dispatch shape stays the same (`[<event-id> <payload>]`), but the payload changes:

- **`:on-success` payload** — v1: the decoded response. v2: `{:kind :success :value <decoded-response>}`. The handler destructures `{:keys [value]}` to get the original payload, or branches on `(:kind reply)` if it shares a handler with `:on-failure` (the co-located form, recommended for new code per [014 §Reply addressing](../../spec/014-HTTPRequests.md#reply-addressing)).
- **`:on-failure` payload** — v1: the raw `XhrIo` response (`{:status :status-text :failure :response :headers}`). v2: `{:kind :failure :failure {:kind <:rf.http/*> ...kind-tags...}}`. The handler MUST re-shape its `case` / `cond` to branch on the new closed-set `:kind` keyword rather than the v1 HTTP status code or ajax-cljs's `:failure` enum. The closed `:rf.http/*` taxonomy is richer (distinguishes transport from CORS from timeout from decode-failure from accept-failure) — handlers that previously lumped all failures into "show error" can opt into per-category UX (retry button on 5xx, network-error banner on transport, etc.) but don't have to.

The rewrite **flags every `:on-failure` handler for review**. The body almost always needs touching — the only exception is "swallow silently" handlers (`(fn [_ _] ...)`), which port unchanged.

### `:timeout`

ajax-cljs default is **unbounded**; managed-HTTP default is **30000 ms per attempt** as a security floor (per [014 §`:timeout-ms` security defaults](../../spec/014-HTTPRequests.md#timeout-ms-security-defaults-rf2-it1cd)). The rewrite:

- If the v1 site set `:timeout <ms>` explicitly: port to `:timeout-ms <ms>`.
- If the v1 site omitted `:timeout` (relying on the unbounded default): **port to the v2 default** by omitting `:timeout-ms`. Do NOT port to `:timeout-ms nil` / `:timeout-ms 0` (the explicit opt-outs) without operator confirmation — the v2 default exists precisely to defend against slow-loris upstreams. The operator opts out only when they genuinely need an unbounded read (rare; usually streaming or long-poll, both better served by a different fx).
- If the v1 site set `:timeout 0` (the ajax-cljs idiom for "unbounded"): flag for review — the v2 default of 30s is almost certainly the better choice; only port to `:timeout-ms 0` if the operator confirms unbounded is required.

### `:on-request` / lifecycle hooks

Drop. Per-request lifecycle hooks belong on the trace surface (`register-trace-listener!` filtered on the relevant `:rf.http/*` event) rather than as args-map slots — cross-cutting concerns should not ride request envelopes. Per [014 §Trace surface](../../spec/014-HTTPRequests.md).

### `:interceptors` (cljs-ajax middleware chain)

ajax-cljs supported a request-transform / response-transform middleware chain via the `:interceptors` slot — used for cross-cutting concerns (Bearer-auth header injection, correlation-id stamping, dev-mode base-URL rewrite, telemetry envelope wrapping). The v2 equivalent is the **per-frame request-side interceptor surface** introduced by [M-39](README.md#m-39-reg-http-interceptor--clear-http-interceptor--additive-request-side-middleware-on-rfhttpmanaged):

```clojure
;; v2 — one registration covers every outbound :rf.http/managed request from the frame
(rf/reg-http-interceptor
  {:frame  :rf/default
   :id     :auth/bearer
   :before (fn [req] (assoc-in req [:request :headers "Authorization"]
                               (str "Bearer " @auth-token)))})
```

Register once per frame at boot; every `:rf.http/managed` request from that frame picks up the transform. The rewrite is a separate pass — extract every cross-cutting concern from v1 `:interceptors` chains and re-register as `reg-http-interceptor` calls. Per-request `:interceptors` (used rarely in v1) lower to bespoke `:request :headers` / `:request :url` shaping at the call site; no v2 equivalent for "request-scoped middleware."

### Hand-rolled retry in `:on-failure` handlers

v1 codebases often implemented retry by hand: the `:on-failure` handler re-dispatches the request event with an incremented attempt count, gated on a sentinel in the failure payload. Replace with `:retry` on the v2 request:

```clojure
;; v2
{:fx [[:rf.http/managed
       {:request      {:method :get :url "/api/flaky"}
        :decode       :json
        :retry        {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
                       :max-attempts 4
                       :backoff      {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}
        :on-success   [:flaky/loaded]
        :on-failure   [:flaky/failed]}]]}
```

The retry decision is a pure function of attempt count + failure category — body-conditional retry ("the body says 'rate-limited, try again with the new token'") is **semantic retry** and belongs on a state machine, per [014 §Boundary — transport vs semantic retry](../../spec/014-HTTPRequests.md#boundary--transport-vs-semantic-retry). Hand-rolled retry that closes over body content / app state / other in-flight requests escalates — it's an O-16 / state-machine concern, not an O-17 mechanical rewrite.

### Abort

If the v1 codebase reached into the `XhrIo` handle to cancel, port to v2's first-class abort surface:

```clojure
;; v2 — tag the request, then dispatch the abort
{:fx [[:rf.http/managed
       {:request    {:method :get :url "/search" :params {:q query}}
        :request-id [:search query]                      ;; stable id, =-comparable
        :decode     :json
        :on-success [:search/results]
        :on-failure [:search/error]}]]}

;; cancel from another event
(rf/dispatch [:rf.http/managed-abort [:search query]])
```

Abort always wins over the natural-completion reply (per [014 §Abort precedence](../../spec/014-HTTPRequests.md#abort-precedence-abort-always-wins--rf2-wez75)). For spawned-actor contexts ([M-45](README.md#m-45-rfhttpmanaged-requests-issued-from-spawned-actors-abort-on-actor-destroy-additive)), abort-on-actor-destroy is automatic; per-app abort sites need the explicit `:request-id` + `:rf.http/managed-abort` pair.

## Explicit escalation cases — the agent surfaces and stops

The migration agent does NOT silently rewrite the following. It presents the call site, the reason for escalation, and waits for operator direction:

1. **`:progress-cb` (per-XHR upload / download progress).** Managed-HTTP does not ship per-request progress callbacks for v1. Sites that depend on progress UX (file-upload progress bars, big-download spinners) escalate. Paths: keep the v1 add-on for those specific endpoints; drop the progress UX if it's nice-to-have; wait for a future streaming-aware spec.

2. **Custom `:format` / `:response-format` fns (not one of the canonical helpers).** Bespoke encoders / decoders need the operator to confirm the equivalent `:request-content-type` / `:decode` shape — usually a Malli schema if the decode is shape-validating; a fn-valued `:decode` if the decode is genuinely arbitrary. Either path is supported by managed-HTTP, but the rewrite is not mechanical.

3. **Hand-rolled retry that closes over body content / app state / other in-flight requests.** Transport-vs-semantic retry boundary applies. Lift the call site into a state machine state, give that state a `:spawn` of `:rf.http/managed`, and write the semantic retry as a transition on the failure reply (per [014 §Boundary — transport vs semantic retry](../../spec/014-HTTPRequests.md#boundary--transport-vs-semantic-retry)). Escalate per call site — the rewrite is an O-16 / state-machine design conversation.

4. **cljs-ajax `:interceptors` chains with response-side transforms** (not just request-side). The v2 `reg-http-interceptor` surface per [M-39](README.md#m-39-reg-http-interceptor--clear-http-interceptor--additive-request-side-middleware-on-rfhttpmanaged) is **request-side only**. Response-side cross-cutting concerns (response logging, response-shape normalisation, response-cookie extraction) move to either `:accept` (per-request, for shape normalisation) or `register-trace-listener!` (cross-cutting, for logging / telemetry). Escalate so the operator can split the v1 chain along the request / response axis.

5. **`(rf/reg-fx :http-xhrio ...)` user-registrations that wrap or override the lib's fx.** Uncommon, but seen in projects with bespoke transport (custom auth-aware client, CSRF token-stamping). The v2 equivalent is either (a) `reg-http-interceptor` for cross-cutting request transforms (the canonical path), or (b) a user-registered fx alongside `:rf.http/managed` for genuinely-bespoke transport (rare; usually a code smell — `reg-http-interceptor` covers most of what bespoke fxs did in v1). Escalate per registration.

6. **GET requests with a request body** (uncommon but legal in HTTP — used for some GraphQL queries when the body is "too big" for the URL). ajax-cljs and managed-HTTP both support this, but the v1 → v2 split between `:params` (always query) and `:body` (always body) makes the v1 GET-with-body site visually look like it should port to `:params`. The agent flags every GET whose v1 site sets `:params` to a non-map or sets explicit headers indicating a body, and asks the operator to confirm the intent.

## Out of scope

- **`day8.re-frame/http-fx` itself does not ship under a new coordinate in re-frame2.** There is no `day8/re-frame2-http-fx` artefact. Operators who want to keep using the v1 add-on continue depending on `day8/re-frame-http-fx` as before; the fx surface it consumes (`reg-fx`, `reg-event-fx`, `dispatch`) is preserved.

- **Streaming, SSE, WebSocket, long-poll, chunked-transfer responses.** Managed-HTTP is single-request / single-reply per [014 §Abstract](../../spec/014-HTTPRequests.md#abstract). Sites that lean on `XhrIo`'s streaming events (rare in `:http-xhrio` v1 code — usually written against a different lib) escalate to a future streaming-aware spec.

- **The agent does not auto-detect "the right `:decode` shape" from the response body.** Determining whether an endpoint's response is best decoded as `:json` / `:text` / `Schema` is a design call the operator owns. The agent presents the v1 site and the candidate translation; the operator approves, edits, or skips.

- **Migrating to the co-located reply form** (one handler with `(if-let [reply (:rf/reply msg)] ...handle... ...issue...)` branching on the sentinel, per [014 §Reply addressing](../../spec/014-HTTPRequests.md#reply-addressing)) is a separate modernisation pass per handler — the like-for-like port to `:rf.http/managed` keeps the explicit `:on-success` / `:on-failure` shape. The agent SHOULD surface the co-located form as a follow-on for handlers that are otherwise simple two-handler pairs; the operator picks per handler.

- **Switching to schema-driven decode** (`:decode <MalliSchema>`) for endpoints that don't yet have schemas is a separate modernisation pass — adopting Malli schemas is its own scope ([O-3](README.md#o-3-add-malli-schemas-to-event-handlers-and-app-db-paths) covers the broader schema adoption). The agent SHOULD flag every `:decode :json` site as a candidate for schema-driven decode; the operator decides per endpoint.

## Reporting

When the agent applies this rule:

- The migration report lists every `:http-xhrio` call site it found, whether the operator approved the rewrite, and the resulting `:rf.http/managed` shape (decode keyword vs schema; retry policy; abort surface).
- If the `day8.re-frame/http-fx` dep is no longer referenced (all requests migrated), the agent flags the dep for removal in the same report; the operator confirms before the dep is dropped. The `day8/re-frame2-http` dep is added per [M-31](README.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http); the `re-frame.http-managed` require is added per namespace that dispatches the new fx.
- Every `:on-failure` handler whose body was rewritten to branch on the v2 `:rf.http/*` taxonomy is listed in the report with file/line — operators should review for UX changes (per-category messaging, retry buttons, network-error banners) the v1 surface did not allow.
- Each escalation case from above is listed with file/line, the specific reason it escalated, and the agent's recommended path forward.
