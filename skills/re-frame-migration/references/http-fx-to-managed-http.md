# O-17 — translate `http-fx` (`:http-xhrio`) to re-frame2 **managed HTTP** (`:rf.http/managed`)

The v1 add-on `day8.re-frame/http-fx` ships a single fx — `:http-xhrio` — wrapping the Google Closure `XhrIo` transport. It was the de-facto HTTP layer for v1 apps. The re-frame2 successor is **managed HTTP** (`:rf.http/managed`, Spec 014, shipped in `day8/re-frame2-http`): the same request envelope, but a structured closed failure taxonomy, schema-driven decode, first-class retry/backoff, per-attempt timeouts, and abort.

> **Forced, not optional.** `http-fx` `:refer`s the removed `re-frame.core/console` and **fails to compile** the moment re-frame2 is on the classpath — see [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). The add-on does **not** keep working. You must convert (this guide) or remove it **before the project compiles**. Choosing *convert vs remove* is the operator's call; doing *something* is not optional.

> **Type B — ask first.** The failure surface is a re-thinking, not a structural lift. Surface the proposed `:rf.http/managed` shape per call site and wait for approval before editing.

> **Verify before you write.** The request / reply shapes below are summarised from [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md). Re-read [§Request envelope](../../../spec/014-HTTPRequests.md#request-envelope), [§Reply addressing](../../../spec/014-HTTPRequests.md#reply-addressing), and [§Failure categories (closed set)](../../../spec/014-HTTPRequests.md#failure-categories-closed-set) against the live http artefact before emitting a request — the spec is the contract, this page is the on-ramp. `:rf.http/managed` ships in `day8/re-frame2-http` (per [M-31](breaking-changes.md#required-m-rules-by-trigger-surface)); a require of `re-frame.http.managed` registers the fx (without it the `:fx` runner raises `:rf.error/no-such-fx`).

## Detection

- Maven coord `day8.re-frame/http-fx` (any version) in `deps.edn` / `project.clj` / `shadow-cljs.edn` / `bb.edn`.
- `(:require [day8.re-frame.http-fx])` in any namespace (side-effects the `:http-xhrio` fx registration).
- `:http-xhrio` keys in effect maps from `reg-event-fx` handlers — the fingerprint. May sit at the top level (pre-M-8) or inside `:fx` (post-M-8 shape `:fx [[:http-xhrio {...}]]`).

Each `:http-xhrio` call site is **one request**. Present the request, the proposed `:rf.http/managed` shape, and the diff for operator approval before any edit.

## Construct mapping — `:http-xhrio` args → `:rf.http/managed` args

The shapes are similar — both take an args map and dispatch a result — but the vocabulary diverges on decode, failure projection, and retry. Every wire-shape key nests under `:request`; lifecycle keys (`:decode`, `:retry`, `:timeout-ms`, `:on-success`, `:on-failure`, `:request-id`) stay at the top level.

| `:http-xhrio` slot | `:rf.http/managed` slot | Notes |
|---|---|---|
| the `:http-xhrio` fx-id | the `:rf.http/managed` fx-id | one fx, one args map; lives inside `:fx` (M-8). |
| `:method` (`:get` / `:post` / ...) | `:request :method` | same keyword set; default `:get` both sides. |
| `:uri` | `:request :url` | **name change** — `:uri` → `:url`. Both accept a string. |
| `:params` (query for GET, body for POST) | `:request :params` (always query) **or** `:request :body` (always body) | v1 overloaded `:params`; managed-HTTP splits them. Read `:method`: GET → `:request :params`; POST/PUT/PATCH → `:request :body`. See [§Request envelope](../../../spec/014-HTTPRequests.md#request-envelope). |
| `:headers` | `:request :headers` | one-to-one (string → string map). |
| `:format (ajax/json-request-format)` (request encoder fn) | `:request :request-content-type :json` (or `:form` / `:text`) | v1 took a fn; managed-HTTP takes a keyword sugar. `json-request-format`→`:json`, `url-request-format`→`:form`, `text-request-format`→`:text`. Custom formatters escalate. |
| `:response-format (ajax/json-response-format {:keywords? true})` (decoder fn) | `:decode :json` (or `:text` / `:blob` / a Malli schema / a fn) | **Like-for-like ONLY when v1 keywordised.** `:decode :json` **always** keywordises JSON object keys (no `:keywords? false` knob — `re-frame.http.json/json-parse` keywordises unconditionally) and enforces the keyword-interning cap. So `:decode :json` is the like-for-like rewrite **only** for v1 sites that passed `{:keywords? true}`. v1 sites that used `{:keywords? false}`, **omitted** the response-format options, or whose handlers read **string** keys (`(get resp "id")`) get **silently keyword-shifted** — escalate (see the **String-key trap** note below). The **canonical upgrade** is `:decode <MalliSchema>` (validates the payload). See [§Decoding](../../../spec/014-HTTPRequests.md#decoding). |
| `:timeout` (ms; library default 0 / unbounded) | `:timeout-ms` (per-attempt; **default 30000**) | port an explicit `:timeout <ms>` to `:timeout-ms <ms>`; leave the default in place when v1 omitted it. Do **not** port to `:timeout-ms 0` / `nil` (unbounded) without operator confirmation — the 30s default is a security floor. See [§`:timeout-ms` security defaults](../../../spec/014-HTTPRequests.md#timeout-ms-security-defaults). |
| `:on-success [:event-id]` | `:on-success [:event-id]` — payload reshapes | dispatch shape identical; the **payload becomes the canonical `{:status :ok :value <decoded> …}` envelope**, appended as the last event arg. The handler reads `{:keys [value]}`. See [§Reply payload shape](../../../spec/014-HTTPRequests.md#reply-payload-shape--the-one-canonical-envelope). |
| `:on-failure [:event-id]` (raw `XhrIo` response) | `:on-failure [:event-id]` — payload reshapes | dispatch shape identical; the **payload becomes the canonical `{:status :error :error {:kind <:rf.http/*> …} …}` envelope** (an abort is `{:status :cancelled :error {:kind :rf.http/aborted …} …}`). The handler pulls the classified failure map from `:error` and keys off its `(:kind error)`, a closed `:rf.http/*` keyword — **not** the v1 `:status` / `:failure` enum. See [§Failure categories (closed set)](../../../spec/014-HTTPRequests.md#failure-categories-closed-set). |
| (no retry support) | `:retry {:on #{...} :max-attempts N :backoff {...}}` | managed-HTTP ships transport-level retry as a first-class slot. `:on` is a closed subset of `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}` ([M-31b](breaking-changes.md#appendix--v2-pre-rename-only-rules-not-for-a-v1v2-migration) — v2-pre-rename-only; v1's `:http-xhrio` had no managed retry, so this is shape guidance, not a v1 migration step). Recommend it for idempotent reads against flaky upstreams; hand-rolled retry in `:on-failure` becomes obsolete. See [§Retry and backoff](../../../spec/014-HTTPRequests.md#retry-and-backoff). |
| custom abort via the `XhrIo` handle | `:request-id <id>` + `(rf/dispatch [:rf.http/managed-abort <id>])` | tag the request with a stable `=`-comparable id, then dispatch the abort. Abort always wins over the natural reply. See [§`:request-id` (internal)](../../../spec/014-HTTPRequests.md#request-id-internal). |

### The reply shape (verified against Spec 014)

When a managed request resolves, the runtime appends the reply payload as the last arg of the named event:

```clojure
;; success → :on-success target
[:article/load-success {:status :ok :value <decoded-and-accepted-payload>
                        ;; …plus :work/id / :work/kind :http / :work/status :completed
                        ;; / :attempt / :rf.frame/id / :completed-at / :correlation
                        }]

;; failure → :on-failure target
[:article/load-failure {:status :error
                        :error  {:kind <one of :rf.http/*>
                                 ;; ...category-specific tags...
                                 }
                        ;; …plus :work/status :failed / :work/id / etc.
                        }]
```

The outer `:status` (`:ok` / `:error` / `:cancelled`) discriminates the branch; the classified `:rf.http/*` failure map rides verbatim under `:error`, and its inner `:kind` names the closed-set failure category (rf2-ibksxg — one canonical envelope, no `{:kind :success/:failure}` reshape; per [§Reply payload shape](../../../spec/014-HTTPRequests.md#reply-payload-shape--the-one-canonical-envelope)). The closed failure categories and their tags:

| `:kind` | When | Tags |
|---|---|---|
| `:rf.http/transport` | network / DNS / connection error before the HTTP transaction completed | `:message`, `:cause` |
| `:rf.http/cors` | CORS preflight / policy rejection (CLJS-only) | `:message`, `:url` |
| `:rf.http/timeout` | per-attempt timeout fired | `:elapsed-ms`, `:limit-ms` |
| `:rf.http/http-4xx` | non-2xx 4xx response | `:status`, `:status-text`, `:body`, `:headers` |
| `:rf.http/http-5xx` | non-2xx 5xx response | same as `:http-4xx` |
| `:rf.http/decode-failure` | 2xx body the decode pipeline rejected (schema reject / JSON parse / custom-fn throw) | `:body-text`, `:cause`, `:schema-validation-failure?` |
| `:rf.http/accept-failure` | `:accept` returned `{:failure m}` — the user map sits at `:detail` | `:detail`, `:decoded` |
| `:rf.http/aborted` | aborted via `:request-id` / `:abort-signal` | `:request-id`, `:reason` |

## The upgrades managed HTTP adds over raw `http-fx`

Call these out when recommending the conversion — they are why the operator wants it, not just compile-survival:

- **Structured, closed failure taxonomy.** v1 handed `:on-failure` the raw `XhrIo` response; the operator parsed `:status` by hand. Managed HTTP classifies into the eight `:rf.http/*` kinds **before** the handler sees the reply, so per-category UX (retry button on 5xx, network banner on transport, "bad payload" on decode-failure) is a `case` on `(:kind failure)`.
- **Decode + Content-Type / JSON handling.** `:decode :json` keywordises **unconditionally** with a keyword-interning cap (DoS resistance for untrusted origins) — there is **no** `:keywords? false` opt-out; `:decode <MalliSchema>` validates the wire payload and surfaces a malformed-but-2xx body as `:rf.http/decode-failure` instead of a deep runtime error. When a v1 site deliberately kept **string** keys, the v2 path is `:decode :text` + an explicit parse into a string-keyed map (per [§Keyword-interning cap](../../../spec/014-HTTPRequests.md#keyword-interning-cap)) — not `:decode :json`.
- **Retry / backoff.** First-class `:retry` (closed `:on` set, `:max-attempts`, exponential `:backoff` with jitter) — a pure function of attempt count + failure category. Hand-rolled retry in `:on-failure` handlers becomes obsolete.
- **Timeout bounding.** A 30s-per-attempt default floor (vs v1's unbounded default) defends against slow-loris upstreams; opt-outs (`:timeout-ms 0` / `nil`) carry deliberate intent.
- **Optional schema validation + reflection.** `:decode <schema>` plus optional `:rf.http/decode-schemas` metadata lets pair tools know which payloads a handler expects from the wire.
- **Cross-host JVM/CLJS parity.** The same fx works server-side (SSR / webhook receivers) on a `java.net.http.HttpClient`-backed transport; CLJS-only Fetch options degrade to silent no-ops with a `:rf.http/cljs-only-key-ignored-on-jvm` warning trace (per [§JVM transport](../../../spec/014-HTTPRequests.md#jvm-transport--degraded-behaviour-for-cljs-only-options)).
- **Abort + trace integration.** First-class `:request-id`-driven abort, and a trace surface (retry attempts, per-category failures) that Xray / Tool-Pair visualise out of the box.

## Worked before → after — a GET with JSON decode

A representative `:http-xhrio` request: fetch an article by slug, decode JSON, dispatch a load-success or load-failure event.

### Before — `:http-xhrio`

```clojure
(ns my-app.articles
  (:require [re-frame.core :as rf]
            [day8.re-frame.http-fx]                    ;; registers the :http-xhrio fx
            [ajax.core :as ajax]))

(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :http-xhrio {:method          :get
                  :uri             (str "/articles/" slug)
                  :timeout         10000
                  :response-format (ajax/json-response-format {:keywords? true})
                  :on-success      [:article/load-success]
                  :on-failure      [:article/load-failure]}}))

(rf/reg-event-db :article/load-success
  (fn [db [_ response]]                                ;; v1: the decoded body, raw
    (-> db
        (assoc-in [:article :status] :loaded)
        (assoc-in [:article :data]   response))))

(rf/reg-event-db :article/load-failure
  (fn [db [_ {:keys [status response]}]]               ;; v1: the raw XhrIo response
    (-> db
        (assoc-in [:article :status] :error)
        (assoc-in [:article :error]  {:status status :detail response}))))
```

### After — `:rf.http/managed`

```clojure
(ns my-app.articles
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]))                  ;; per M-31 — registers the :rf.http/* fxs

(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:method :get
                         :url    (str "/articles/" slug)}   ;; :uri → :request :url
            :decode     :json                                ;; or a Malli schema (recommended)
            :timeout-ms 10000                                ;; :timeout → :timeout-ms
            :on-success [:article/load-success]
            :on-failure [:article/load-failure]}]]}))

(rf/reg-event :article/load-success
  (fn [{:keys [db]} [_ {:keys [value]}]]               ;; reply lands as {:status :ok :value v …}
    {:db (-> db
             (assoc-in [:article :status] :loaded)
             (assoc-in [:article :data]   value))}))

(rf/reg-event :article/load-failure
  (fn [{:keys [db]} [_ {:keys [error]}]]               ;; reply lands as {:status :error :error {...} …}
    {:db (-> db
             (assoc-in [:article :status] :error)
             (assoc-in [:article :error]
                       (case (:kind error)              ;; the classified :rf.http/* category
                         :rf.http/http-4xx       {:kind :not-found   :status (:status error)}
                         :rf.http/http-5xx       {:kind :server-err  :status (:status error)}
                         :rf.http/transport      {:kind :network     :message (:message error)}
                         :rf.http/timeout        {:kind :timeout     :elapsed-ms (:elapsed-ms error)}
                         :rf.http/decode-failure {:kind :bad-payload :cause (:cause error)}
                         {:kind :unknown :error error})))}))
```

What changed:

- **The fx moves from a top-level `:http-xhrio` key into `:fx [[:rf.http/managed {...}]]`** (the M-8 reshape — every fx lives in `:fx`).
- **The request envelope splits:** `:uri` → `:request :url`, `:method` → `:request :method`; every wire key nests under `:request`, lifecycle keys stay at the top level.
- **`:response-format (ajax/json-response-format {:keywords? true})` → `:decode :json`** — like-for-like **only because this site set `{:keywords? true}`** (keyword sugar replaces the fn-valued slot; canonical upgrade is `:decode <MalliSchema>`). Had it used `{:keywords? false}` / omitted the options / read string keys, `:decode :json` would NOT be like-for-like — see the String-key trap escalation.
- **`:timeout 10000` → `:timeout-ms 10000`.**
- **`:on-success` payload `response` → `{:keys [value]}`** — the handler destructures `:value` from the canonical `{:status :ok :value ...}` reply.
- **`:on-failure` payload `{:status :response}` → `{:keys [error]}`** — pull the classified failure map from `:error`, then key off its `(:kind error)`, the closed `:rf.http/*` taxonomy. Re-shaping the failure body is the substantial part of every per-call-site conversion — the operator decides which categories get distinct UX.
- **`(:require [day8.re-frame.http-fx])` + `[ajax.core]` dropped; `(:require [re-frame.http.managed])` added** (M-31). The `day8.re-frame/http-fx` coord is dropped once every request is converted.

## Escalate — the agent surfaces and stops

Do **not** silently rewrite these; present the call site, the reason, and wait for direction:

- **`:progress-cb` (per-XHR upload / download progress).** Managed HTTP ships no per-request progress callbacks — streaming is out of scope. Keep the v1 add-on for those endpoints, drop the progress UI, or wait for a streaming-aware spec.
- **Custom `:format` / `:response-format` fns** (not one of the canonical helpers). The operator confirms the equivalent `:request-content-type` / `:decode` shape — a Malli schema if shape-validating, a fn-valued `:decode` if genuinely arbitrary. Supported, but not mechanical.
- **String-key JSON trap — `:keywords? false`, omitted response-format options, or string-key handlers.** v2 `:decode :json` keywordises object keys **unconditionally** (no `:keywords? false`). A v1 site that read JSON with **string** keys — explicit `(ajax/json-response-format {:keywords? false})`, an **omitted** `:response-format` that defaulted to string keys, or an `:on-success` handler that does `(get resp "id")` / `(get-in resp ["a" "b"])` — will **succeed at the request layer but break downstream**: handlers, schema validators, and tests that expect string keys now receive keyword keys (and, for untrusted origins, attacker-controlled keys get interned up to the cap). **Detect** these and escalate: grep each `:on-success`/`:on-failure` handler body for string-keyed access on the reply, and check the v1 `:response-format` for `:keywords? false` / omission. The recommended v2 path when string keys must be preserved is **`:decode :text` + an explicit parser** (e.g. `(js->clj (js/JSON.parse body))` / Cheshire with no `:key-fn`) into a string-keyed map — never `:decode :json`. Present the affected handlers; the operator confirms keep-string-keys (text + parse) vs adopt-keyword-keys (update the handlers/schemas).
- **Hand-rolled retry that closes over body content / app state.** Body-conditional retry is **semantic** retry — it belongs on a state machine (see [O-16](async-flow-to-machines.md)), per [§Boundary — transport vs semantic retry](../../../spec/014-HTTPRequests.md#boundary--transport-vs-semantic-retry). Transport-level retry (attempt count + category only) ports to `:retry`; semantic retry escalates.
- **cljs-ajax `:interceptors` chains.** The v2 equivalent is the per-frame symmetric `reg-http-interceptor` surface ([M-39](breaking-changes.md#required-m-rules-by-trigger-surface)) — `:before` for request-side, `:after` for response-side. Extract each cross-cutting concern and re-register; escalate per chain so the operator picks the surface.
- **`(rf/reg-fx :http-xhrio ...)` user-registrations** that wrapped / overrode the lib's fx (bespoke transport, CSRF stamping). The v2 path is `reg-http-interceptor` for cross-cutting transforms, or a bespoke fx alongside `:rf.http/managed` for genuinely-custom transport. Escalate per registration.

## Reporting

- List every `:http-xhrio` call site found, whether the operator approved each rewrite, and the resulting `:rf.http/managed` shape (decode keyword vs schema; retry policy; abort surface).
- List every `:on-failure` handler whose body was reshaped to the `:rf.http/*` taxonomy with file/line — the operator reviews for per-category UX the v1 surface did not allow.
- List every site that hit the **String-key trap** (v1 `:keywords? false` / omitted response-format / string-key handler) with file/line and the operator's decision (keep string keys via `:decode :text` + parse, vs adopt keyword keys + update handlers/schemas) — a request that succeeds with a silently keyword-shifted payload is a latent regression.
- When the `day8.re-frame/http-fx` dep is no longer referenced, flag it for removal; the operator confirms before the coord is dropped. The `day8/re-frame2-http` dep is added per M-31; `re-frame.http.managed` is required per namespace that dispatches the fx.
- List each escalation with file/line, the reason, and the recommended path.

---

*Authoritative contract: [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md). v1 add-on: [`http-fx`](https://github.com/day8/re-frame-http-fx). Forced-compile context: [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). Sibling guide: [`async-flow-to-machines.md`](async-flow-to-machines.md) (O-16).*
