# Anti-pattern — Schemaless events at boundaries

`reg-event` handlers that ingest untrusted data — HTTP responses, WebSocket frames, `postMessage` payloads, query-string params, `localStorage` rehydration — without an always-on production validator at the boundary. Dev-only `:schema` and `reg-app-schema` are necessary but not sufficient: both are elided in production builds (`goog.DEBUG = false`), so the handler writes whatever the source returned straight into `app-db` in the deployed bundle.

## Detection rules

**The cardinal rule.** Any handler that crosses an untrusted boundary — HTTP response, WebSocket frame, `postMessage` payload, query-string param, `localStorage` rehydration, IndexedDB read, third-party iframe — is **flagged** unless **production validation** is wired for *the untrusted value itself*.

**Two boundary shapes, two production gates.** The untrusted value arrives in one of two places, and only the matching gate counts:

- **Event-payload** — the value rides *in the dispatched event vector* (an HTTP `:rf/reply`, a `postMessage` event arg, a query-string map dispatched as `[:route/params-received params]`). The always-on gate is the `:rf.schema/at-boundary` interceptor ref in metadata `:interceptors` (it forces the handler's `:schema` to run over the **event vector** at run-time, production included), **or** a Managed HTTP `:decode <Schema>` on the originating request (validates the response at decode time), **or** a custom registered interceptor (referenced by id) that Malli-validates the event vector outside any dev-only `goog.DEBUG` guard.
- **Body-read** — the handler *reads the value mid-body* (`(.getItem js/localStorage …)`, `js/window.location.search`, a stashed `(.-data msg)`, an IndexedDB cursor result) then writes it to `app-db`. `:rf.schema/at-boundary` is **useless here** — the value never appears in the event vector it checks. The always-on gate must wrap the **raw value**: an unconditional `(m/validate Schema raw)` / `m/coerce` / `m/explain` in the body (not behind `(when ^boolean js/goog.DEBUG …)`), **or** — better — move the read into a validating cofx / interceptor / fx-reply path that materialises and validates the value *before* the handler writes it.

Detection logic, per candidate handler:

1. Does the handler ingest data from an untrusted source? If no → not in scope for this leaf.
2. **Where does the untrusted value arrive?** Classify the boundary shape; it picks which gate counts (see above). If a matching gate is present → not a finding.
3. If no matching gate → **flag**, regardless of whether `:schema` and `reg-app-schema` are attached — both are dev-elided, so the boundary is open in the deployed bundle. In particular, a body-read handler carrying `:schema` plus `:rf.schema/at-boundary` *for the event id only* is still a finding: the interceptor validates the (trusted) dispatch, not the (untrusted) value the body fetched.

Greppable signals — flag when **any** match AND no production gate is wired:

- A `reg-event` handler whose `:fx` includes `:rf.http/managed`, `:http-xhrio`, or websocket-id keywords, or whose body reads `(:rf/reply event)` / `(:body event)` / `(:data event)` from a network or `postMessage` source.
- A handler bound to `:*/loaded`, `:*/received`, `:*/decoded`, `:*/synced`, `:*/rehydrated`, `:*/restored` whose `:db` write is `(assoc db :foo/bar payload)` where `payload` originated outside the application's own dispatches.
- An unstructured second arg — `(fn [{:keys [db]} [_ data]] {:db (assoc db :remote data)})` — where `data` is the raw boundary payload.
- A handler that reads `js/window.location.search`, `js/localStorage`, `js/sessionStorage`, `js/postMessage`, or IndexedDB results in its body (often via fx) and writes the result to `app-db`.
- New handlers whose `app-db` writes use paths absent from `(re-frame.schemas/app-schemas)` (the `app-schemas` query lives on `re-frame.schemas` — no longer re-exported from `re-frame.core`).

Structural signal: the boundary between **untrusted input** and **trusted `app-db`** is crossed without a Malli schema gate **that runs in production**.

## Why it's an anti-pattern

`app-db` is the trust boundary. The whole substrate downstream of it (subs, views, machine reads, story snapshots, time-travel) assumes the values it reads conform to the application's mental model. A schemaless boundary event smuggles arbitrary data past the gate — a stale API field, a server schema change, a malformed query string, a tampered `localStorage` payload — and the failure surfaces hundreds of dispatches later in a sub that crashes on a missing key. Schemas at boundaries are Cardinal Rule #4 (`skills/re-frame2/SKILL.md`).

The runtime offers two complementary dev-time tools: handler `:schema` (validates the **event vector** before the handler runs) and `reg-app-schema` (validates the **app-db path** after the handler writes). **Both are dev-elided in production** (gated on `goog.DEBUG`). The always-on `:rf.schema/at-boundary` interceptor re-runs the handler's `:schema` over the **event vector** in production — closing an *event-payload* boundary (Managed HTTP `:decode` is the other always-on gate there). It cannot see a value the handler reads mid-body, so a *body-read* boundary needs the **raw value itself** validated. A handler carrying only `:schema` and/or `reg-app-schema` is validated in dev but unvalidated in the deployed bundle — the exact place the boundary matters.

## The canonical fix

[`skills/re-frame2/references/fundamentals/schemas.md`](../../re-frame2/references/fundamentals/schemas.md), spec source [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) — at a minimum, an always-on gate matched to the boundary shape (event-payload: `:rf.schema/at-boundary` or Managed HTTP `:decode`; body-read: an always-on `m/validate` over the raw value, or a validating cofx). `:schema` and `reg-app-schema` stay valuable dev-time tools, but do not satisfy this rule on their own. The `:rf.error/schema-validation-failure` error category is the corresponding instrumentation signal.

## Worked example

**Before** — schemaless boundary handler:

```clojure
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :article (:value reply))}                  ;; trust everything that comes back
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:rf.http/managed {:request {:url (str "/articles/" slug)}}]]})))
```

**After** — schema-validated boundary with a production gate:

```clojure
(def Article
  [:map
   [:slug    :string]
   [:title   :string]
   [:body    :string]
   [:authors [:vector [:map [:id :uuid] [:name :string]]]]])

(rf/reg-app-schema [:article :data] {:schema Article})          ;; dev-only — validates the article PAYLOAD slice only

(rf/reg-event :article/load
  {:doc    "Load one article by slug."
   :schema [:cat [:= :article/load] [:map [:slug :string]]]
   :interceptors [:rf.schema/at-boundary]}                      ;; ALWAYS-ON ref — runs in production
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc-in db [:article :data] (:value reply))}       ;; reply is validated by Article on decode AND by the path schema in dev
      {:db (assoc-in db [:article :status] :loading)            ;; lifecycle status lives off the schema'd payload path
       :fx [[:rf.http/managed
             {:request {:url (str "/articles/" slug)}
              :decode  Article}]]})))                            ;; ALWAYS-ON — Managed HTTP decode runs in prod
```

## Regression example — body-read boundaries need the value validated, not the event

This is the trap. The handler below carries **both** a `:schema` for its event id **and** the `:rf.schema/at-boundary` interceptor ref in metadata `:interceptors` — exactly the shape that closes an *event-payload* boundary. It is **still a finding**, because the untrusted value (`localStorage`) is read *inside the body*: the interceptor validates the (empty, trusted) `[:session/rehydrate]` dispatch and never touches the parsed payload. The same trap applies to any body-read source — query string, a stashed `postMessage`, an IndexedDB cursor.

```clojure
(def Session
  [:map [:user/id :uuid] [:user/roles [:set :keyword]]])

(rf/reg-app-schema [:session] {:schema Session})                 ;; dev-only — elided in production

(rf/reg-event :session/rehydrate
  {:schema [:cat [:= :session/rehydrate]]                        ;; dev-only — pins the (trusted) dispatch shape
   :interceptors [:rf.schema/at-boundary]}                        ;; validates the EVENT VECTOR — not the localStorage value
  (fn [{:keys [db]} _]
    (let [raw (.getItem js/globalThis.localStorage "session")]   ;; <-- untrusted body read
      {:db (assoc db :session (js->clj (js/JSON.parse raw)))}))) ;; production: writes arbitrary localStorage straight in
```

**Why it flags.** `:rf.schema/at-boundary` runs the handler's `:schema` over `[:session/rehydrate]` — which the app dispatched itself and is trivially valid. The interceptor has no visibility into `raw`; nothing validates the JSON a tampered or stale `localStorage` returns, and in production the `reg-app-schema` write-check is elided too.

**The fix — validate the raw value, two equivalent shapes:**

```clojure
;; (a) Always-on Malli check over the raw value, before the write:
(rf/reg-event :session/rehydrate
  (fn [{:keys [db]} _]
    (let [raw    (.getItem js/globalThis.localStorage "session")
          parsed (some-> raw js/JSON.parse (js->clj :keywordize-keys true))]
      (if (m/validate Session parsed)                            ;; ALWAYS-ON — runs in production
        {:db (assoc db :session parsed)}
        {:fx [[:session/clear-corrupt]]}))))                     ;; reject, don't ingest

;; (b) Better — move the read+validation into a value-returning cofx; the handler only sees a clean value:
(rf/reg-cofx :session/stored
  {:doc "Materialise + validate the persisted session; nil if absent/corrupt."}
  (fn []
    (let [parsed (some-> (.getItem js/globalThis.localStorage "session")
                         js/JSON.parse (js->clj :keywordize-keys true))]
      (when (m/validate Session parsed) parsed))))               ;; ALWAYS-ON validation; returns the value

(rf/reg-event :session/rehydrate
  {:rf.cofx/requires [:session/stored]}
  (fn [{:keys [db session/stored]} _]                            ;; the validated value arrives flat
    (cond-> {} stored (assoc :db (assoc db :session stored)))))
```

Both gates validate the **value the body would have read**, so the boundary is closed in the production bundle — not just the dispatch shape. For an *event-payload* source instead (query-string params or a `postMessage` arg dispatched into the event vector), the gate is `:rf.schema/at-boundary` in metadata `:interceptors`, not a body validator.

> **Validation closes the trust boundary, not the replay boundary.** A boundary read that feeds **durable** state (here `:session/rehydrate` writes the validated value straight into `:db`) is also subject to the durable-write rule — an ambient host read re-reads the *current* host on every replay, so epoch restore / SSR / time-travel diverge from the recorded boot. The fix that makes the fact **recordable** (the boot/restore token carries the value via `:rf.cofx/requires`, not a fresh host read at the write site) is owned in full by [`imperative-effects.md` §Reads — the durable/diagnostic fork](imperative-effects.md#reads--the-durablediagnostic-fork-ep-0010). A boot/rehydrate value is the legitimate edge (the persisted durable state itself, no prior epoch to diverge from); a read landing only in a diagnostic / host-transient slot stays an ordinary ambient cofx.

## Edge cases — when schemaless is fine

- **Internal-only events** that never touch untrusted data — UI toggles, navigation events with structurally-fixed arg shapes (`[:menu/toggle]`, `[:nav/to :route-id]`). The args come from the application's own code; no boundary is crossed.
- **Pre-alpha throwaway prototypes** where the schema would churn faster than the data — but mark the path with a `TODO` and add the production gate before it stabilises into a feature.
- **Events whose payload is genuinely opaque** to the handler (it just forwards the value to another fx without inspecting it) — the always-on gate still applies if the value originated outside the app's own dispatches; the validator pins the **shape of the forwarded slot** so the downstream receiver can rely on it.
