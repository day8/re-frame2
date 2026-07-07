# Anti-pattern — Schemaless events at boundaries

`reg-event` handlers that ingest untrusted data — HTTP responses, WebSocket frames, `postMessage` payloads, query-string params, `localStorage` rehydration — without an always-on production validator at the boundary. Dev-only `:schema` and `reg-app-schema` are necessary but not sufficient: both are elided in production builds (`goog.DEBUG = false`), so the handler writes whatever the source returned straight into `app-db` in the deployed bundle.

## Detection rules

**The cardinal rule.** Any handler that crosses an untrusted boundary — HTTP response, WebSocket frame, `postMessage` payload, query-string param, `localStorage` rehydration, IndexedDB read, third-party iframe — is **flagged** unless **production validation** is wired for *the untrusted value itself*.

**Two boundary shapes, two production gates.** The untrusted value arrives in one of two places, and only the matching gate counts:

- **Event-payload** — the value rides *in the dispatched event vector* (an HTTP `:rf/reply`, a `postMessage` arg, a query-string map dispatched as `[:route/params-received params]`). The always-on gate is the `:rf.schema/at-boundary` interceptor ref in metadata `:interceptors` (it forces the handler's `:schema` to run over the **event vector** at run-time, production included), **or** a Managed HTTP `:decode <Schema>` on the originating request, **or** a custom registered interceptor that Malli-validates the event vector outside any `goog.DEBUG` guard.
- **Body-read** — the handler *reads the value mid-body* (`(.getItem js/localStorage …)`, `js/window.location.search`, a stashed `(.-data msg)`, an IndexedDB cursor) then writes it to `app-db`. `:rf.schema/at-boundary` is **useless here** — the value never appears in the event vector it checks. The gate must wrap the **raw value**: an unconditional `(m/validate Schema raw)` / `m/coerce` in the body (not behind `(when ^boolean js/goog.DEBUG …)`), **or** — better — a validating cofx / interceptor / fx-reply path that materialises and validates the value *before* the handler writes it.

Detection logic, per candidate handler:

1. Ingests data from an untrusted source? No → not in scope.
2. **Where does the untrusted value arrive?** Classify event-payload vs body-read; it picks which gate counts. Matching gate present → not a finding.
3. No matching gate → **flag**, regardless of `:schema` / `reg-app-schema` (both dev-elided). A body-read handler carrying `:schema` + `:rf.schema/at-boundary` *for the event id only* is still a finding — see the Regression example.

Greppable signals — flag when **any** match AND no production gate is wired:

- A `reg-event` handler whose `:fx` includes `:rf.http/managed`, `:http-xhrio`, or websocket-id keywords, or whose body reads `(:rf/reply event)` / `(:body event)` / `(:data event)` from a network or `postMessage` source.
- A handler bound to `:*/loaded`, `:*/received`, `:*/decoded`, `:*/synced`, `:*/rehydrated`, `:*/restored` whose `:db` write is `(assoc db :foo/bar payload)` where `payload` originated outside the app's own dispatches.
- An unstructured second arg — `(fn [{:keys [db]} [_ data]] {:db (assoc db :remote data)})` — where `data` is the raw boundary payload.
- A handler that reads `js/window.location.search`, `js/localStorage`, `js/sessionStorage`, `js/postMessage`, or IndexedDB results and writes the result to `app-db`.
- New handlers whose `app-db` writes use paths absent from `(re-frame.schemas/app-schemas)` (the `app-schemas` query lives on `re-frame.schemas`, not `re-frame.core`).

## Why it's an anti-pattern

`app-db` is the trust boundary. The whole substrate downstream of it (subs, views, machine reads, story snapshots, time-travel) assumes the values it reads conform to the application's mental model. A schemaless boundary event smuggles arbitrary data past the gate — a stale API field, a server schema change, a malformed query string, a tampered `localStorage` payload — and the failure surfaces hundreds of dispatches later in a sub that crashes on a missing key. Schemas at boundaries are Cardinal Rule #4 (`skills/re-frame2/SKILL.md`). Because `:schema` and `reg-app-schema` are dev-elided, a handler carrying only those is validated in dev but unvalidated in the deployed bundle — the exact place the boundary matters.

## The canonical fix

[`schemas.md`](../../re-frame2/references/fundamentals/schemas.md), spec source [`spec/010-Schemas.md`](../../../spec/010-Schemas.md) — wire an always-on gate matched to the boundary shape (per the two-gate split in Detection rules). `:schema` and `reg-app-schema` stay valuable dev-time tools but do not satisfy this rule alone. The `:rf.error/schema-validation-failure` error category is the corresponding instrumentation signal.

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
      {:db (assoc-in db [:article :data] (:value reply))}       ;; reply validated by Article on decode + path schema in dev
      {:db (assoc-in db [:article :status] :loading)            ;; lifecycle status lives OFF the schema'd payload path
       :fx [[:rf.http/managed
             {:request {:url (str "/articles/" slug)}
              :decode  Article}]]})))                            ;; ALWAYS-ON — Managed HTTP decode runs in prod
```

## Regression example — body-read boundaries need the value validated, not the event

This is the trap. The handler below carries **both** a `:schema` for its event id **and** the `:rf.schema/at-boundary` interceptor ref — exactly the shape that closes an *event-payload* boundary. It is **still a finding**, because the untrusted value (`localStorage`) is read *inside the body*: the interceptor validates the (empty, trusted) `[:session/rehydrate]` dispatch and never touches the parsed payload. The same trap applies to any body-read source — query string, a stashed `postMessage`, an IndexedDB cursor.

```clojure
(def Session
  [:map [:user/id :string] [:user/roles [:vector :string]]])    ;; JSON-representable — the persisted shape

(rf/reg-app-schema [:session] {:schema Session})                 ;; dev-only — elided in production

(rf/reg-event :session/rehydrate
  {:schema [:cat [:= :session/rehydrate]]                        ;; dev-only — pins the (trusted) dispatch shape
   :interceptors [:rf.schema/at-boundary]}                        ;; validates the EVENT VECTOR — not the localStorage value
  (fn [{:keys [db]} _]
    (let [raw (.getItem js/globalThis.localStorage "session")]   ;; <-- untrusted body read
      {:db (assoc db :session (js->clj (js/JSON.parse raw)))}))) ;; production: writes arbitrary localStorage straight in
```

**Why it flags.** `:rf.schema/at-boundary` validates only the trivially-valid `[:session/rehydrate]` dispatch; it has no visibility into `raw`, and the `reg-app-schema` write-check is elided in production — so nothing validates the JSON a tampered/stale `localStorage` returns.

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

Both gates validate the **value the body would have read**, closing the boundary in production — not just the dispatch shape.

> **Validation closes the trust boundary, not the replay boundary.** A boundary read feeding **durable** state (here `:session` in `:db`) is separately subject to the durable-write rule — see [`imperative-effects.md` §the durable/diagnostic fork](imperative-effects.md#reads--the-durablediagnostic-fork-ep-0010). A boot/rehydrate value is the legitimate edge (the persisted durable state itself — no prior epoch to diverge from).

## Edge cases — when schemaless is fine

- **Internal-only events** that never touch untrusted data — UI toggles, navigation with fixed arg shapes (`[:menu/toggle]`, `[:nav/to :route-id]`). The args come from the app's own code; no boundary is crossed.
- **Pre-alpha throwaway prototypes** where the schema would churn faster than the data — but mark the path with a `TODO` and add the production gate before it stabilises.
- **Events whose payload is genuinely opaque** to the handler (it forwards the value to another fx without inspecting it) — the always-on gate still applies if the value originated outside the app's own dispatches; the validator pins the **shape of the forwarded slot** so the downstream receiver can rely on it.
