# Anti-pattern — Schemaless events at boundaries

`reg-event-fx` (or `reg-event-db`) handlers that ingest untrusted data — HTTP responses, WebSocket frames, `postMessage` payloads, query-string params, `localStorage` rehydration — without an always-on production validator at the boundary. Dev-only `:schema` and `reg-app-schema` are necessary but not sufficient: both are elided in production builds, so the handler writes whatever the source returned straight into `app-db` in the deployed bundle.

## Detection rules

**The cardinal rule.** Any handler that crosses an untrusted boundary — HTTP response, WebSocket frame, `postMessage` payload, query-string param, `localStorage` rehydration, IndexedDB read, third-party iframe — is **flagged** unless **production validation** is wired for *the untrusted value itself*. `:schema` on the handler metadata and `reg-app-schema` on the destination path are necessary but **not sufficient on their own** — both are dev-elided in production builds (`goog.DEBUG = false`), so a handler that relies only on them is unvalidated in the deployed bundle.

**Two boundary shapes, two production gates.** The untrusted value arrives in one of two places, and only the matching gate counts:

- **Event-payload boundary** — the untrusted value rides *in the dispatched event vector* (an HTTP `:rf/reply`, a `postMessage` event arg, a query-string map dispatched as `[:route/params-received params]`). The always-on gate is `[rf/validate-at-boundary-interceptor]` on the handler (it forces the handler's `:schema` to run over the event vector at run-time, production included) **or** a Managed HTTP `:decode <Schema>` on the originating request (validates the response at decode time, production included). `validate-at-boundary-interceptor` only validates the **event vector** against `:schema` — it does **not** see values the handler fetches inside its own body.
- **Body-read boundary** — the handler *reads the untrusted value mid-body* (`(.getItem js/localStorage …)`, `js/window.location.search`, `(.-data msg)` pulled from a stashed source, an IndexedDB cursor result) and then writes it to `app-db`. `validate-at-boundary-interceptor` is **useless here** — the value never appears in the event vector it checks. The always-on gate must wrap the **raw value**: a `(m/validate Schema raw)` (or `m/coerce` / `m/explain`) call in the handler body that runs unconditionally (not behind `(when ^boolean js/goog.DEBUG …)`), **or** — better — move the read into a validating cofx / interceptor / fx-reply path that materialises and validates the value *before* the handler writes it.

Greppable signals — flag when **any** of these match AND no production gate is wired:

- `reg-event-fx` whose `:effects` (return-map `:fx`) include `:rf.http/managed`, `:http-xhrio`, websocket-id keywords, or whose handler reads `(:rf/reply event)` / `(:body event)` / `(:data event)` from a network or `postMessage` source.
- `reg-event-db` named `:*/loaded`, `:*/received`, `:*/decoded`, `:*/synced`, `:*/rehydrated`, `:*/restored` whose handler writes `(assoc db :foo/bar payload)` where `payload` originated outside the application's own dispatches.
- Events that take an unstructured second arg — `(fn [db [_ data]] (assoc db :remote data))` — where `data` is the raw boundary payload.
- Handlers that read `js/window.location.search`, `js/localStorage`, `js/sessionStorage`, `js/postMessage`, or `IndexedDB` results in their bodies (often via fx) and write the result to `app-db`.
- New handlers introduced in a feature whose `app-db` writes use paths absent from `(rf/app-schemas)`.

Detection logic (apply for each candidate handler):

1. Does the handler ingest data from an untrusted source? (See list above.) If no → not in scope for this leaf.
2. **Where does the untrusted value arrive?** Classify the boundary shape — it picks which gate counts:
   - **Event-payload** (the value is *in the dispatched event vector*): a matching production gate is **one** of —
     - `[rf/validate-at-boundary-interceptor]` in the handler's interceptor chain (validates the event vector against `:schema` at run-time, production included), **or**
     - Managed HTTP `:decode <Schema>` on the originating request (validates the response at decode time, production included), **or**
     - a custom interceptor that Malli-validates the event vector **outside `(when ^boolean js/goog.DEBUG …)`** or any dev-only conditional.
   - **Body-read** (the handler *fetches the value mid-body* — `localStorage`, query string, IndexedDB, a stashed `postMessage` source): `validate-at-boundary-interceptor` does **not** count — it only checks the event vector, which never contains the fetched value. A matching production gate is **one** of —
     - an always-on `(m/validate Schema raw)` / `(m/coerce Schema raw …)` / `(m/explain …)` over the **raw value**, run unconditionally (not behind a dev-only `goog.DEBUG` guard), before the write, **or**
     - the read moved into a validating cofx (`reg-cofx` that materialises *and* validates) / interceptor / fx-reply path, so the handler only ever sees a value already validated.
   If a matching gate for the boundary shape is present → not a finding.
3. If no matching gate → **flag**, regardless of whether `:schema` and `reg-app-schema` are attached. Both are dev-elided in production; the boundary is open in the deployed bundle. In particular, a body-read handler carrying `:schema` + `[rf/validate-at-boundary-interceptor]` **for the event id only** is still a finding — the interceptor validates the (trusted) dispatch, not the (untrusted) value the body fetched.

Structural signal: the boundary between **untrusted input** and **trusted `app-db`** is crossed without a Malli schema gate **that runs in production**.

## Why it's an anti-pattern

`app-db` is the trust boundary. The whole substrate downstream of it (subs, views, machine reads, story snapshots, time-travel) assumes the values it reads conform to the application's mental model. A schemaless boundary event smuggles arbitrary data past the gate — a stale API field, a server schema change, a malformed query string, a tampered `localStorage` payload — and the failure surfaces hundreds of dispatches later in a sub that crashes on a missing key. Schemas at boundaries are Cardinal Rule #4 (`skills/re-frame2/SKILL.md`).

The runtime offers two complementary dev-time tools: handler `:schema` (validates the **event vector** before the handler runs) and `reg-app-schema` (validates the **app-db path** after the handler writes). The first catches malformed dispatches; the second catches malformed writes. **Both are dev-elided in production** (gated on `goog.DEBUG`). The always-on `validate-at-boundary-interceptor` re-runs the handler's `:schema` over the **event vector** in production — so it closes an *event-payload* boundary (Managed HTTP `:decode` is the other always-on gate there). It does **not** rescue `reg-app-schema`'s write-check, and it cannot see a value the handler reads mid-body — a *body-read* boundary (`localStorage`, query string, IndexedDB) needs the **raw value itself** validated, by an always-on `m/validate` in the body or a validating cofx. A handler that carries only `:schema` and / or `reg-app-schema` is validated in dev but unvalidated in the deployed bundle — the exact place the boundary matters.

## The canonical fix

[`skills/re-frame2/references/fundamentals/schemas.md`](../../re-frame2/references/fundamentals/schemas.md) — at a minimum, an always-on gate matched to the boundary shape:

- **Event-payload boundary** (value in the event vector): the `[rf/validate-at-boundary-interceptor]` interceptor on the handler, or a Managed HTTP `:decode <Schema>` on the originating request, or an equivalent custom always-on Malli-check interceptor over the event vector.
- **Body-read boundary** (value fetched mid-body — `localStorage`, query string, IndexedDB, a stashed `postMessage` source): an always-on `(m/validate Schema raw)` over the raw value before the write, or — preferably — move the read into a validating cofx / interceptor / fx-reply path so the handler only ever sees a validated value. `validate-at-boundary-interceptor` does **not** apply here; it only checks the event vector.

`:schema` on the handler metadata and `reg-app-schema` on the destination path are valuable dev-time tools that surface mismatches early — but they do not satisfy this rule on their own, because both are elided when `goog.DEBUG` is false.

Spec source: [`spec/010-Schemas.md`](../../../spec/010-Schemas.md). The `:rf.error/schema-validation-failure` error category is the corresponding instrumentation signal.

## Worked example

**Before** — schemaless boundary handler:

```clojure
(rf/reg-event-fx :article/load
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

(rf/reg-app-schema [:article] Article)                          ;; dev-only — surfaces mismatches in dev

(rf/reg-event-fx :article/load
  {:doc    "Load one article by slug."
   :schema [:cat [:= :article/load] [:map [:slug :string]]]}    ;; dev-only — pins dispatch shape in dev
  [rf/validate-at-boundary-interceptor]                         ;; ALWAYS-ON — runs in production
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :article (:value reply))}                  ;; reply is validated by Article on decode
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:rf.http/managed
             {:request {:url (str "/articles/" slug)}
              :decode  Article}]]})))                            ;; ALWAYS-ON — Managed HTTP decode runs in prod
```

## Regression example — dev-only validation isn't enough

The handler below carries **both** `:schema` and `reg-app-schema`, and looks like the "After" shape above. It is **still a finding** — no always-on gate is attached.

```clojure
(def Article
  [:map
   [:slug :string] [:title :string] [:body :string]])

(rf/reg-app-schema [:article] Article)                          ;; dev-only — elided in production

(rf/reg-event-fx :article/load
  {:doc    "Load one article by slug."
   :schema [:cat [:= :article/load] [:map [:slug :string]]]}    ;; dev-only — elided in production
  ;; NO [rf/validate-at-boundary-interceptor] in the interceptor chain.
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :article (:value reply))}                  ;; production: writes raw HTTP body unchecked
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:rf.http/managed {:request {:url (str "/articles/" slug)}}]]})))
                                                                  ;; ^ no :decode either
```

**Why it flags.** In dev, `goog.DEBUG = true` runs the `:schema` and `reg-app-schema` validators — mismatches surface fast. In a production build the JIT/CC-elision strips both, and the handler writes whatever the server returned straight into `app-db`. The trust boundary is open in the bundle the user actually ships. The fix is to add `[rf/validate-at-boundary-interceptor]` to the interceptor chain (validates the event vector at run-time, production included) or a Managed HTTP `:decode Article` on the request (validates the response at decode time, production included) — ideally both.

Other untrusted-boundary shapes that hit the same rule — but watch *which* gate applies, because the boundary shape decides it:

```clojure
;; query-string ingestion — EVENT-PAYLOAD (params ride in the event vector)
(rf/reg-event-db :route/params-received                          ;; flag — no always-on gate
  {:schema [:cat keyword? :map]}
  (fn [db [_ params]] (assoc db :route/params params)))
;;   Gate: [rf/validate-at-boundary-interceptor] — validates the event vector in prod.

;; postMessage payload — EVENT-PAYLOAD (msg arrives as the event arg)
(rf/reg-event-db :postmessage/received                           ;; flag — no always-on gate
  (fn [db [_ msg]] (assoc db :embed/state (.-data msg))))
;;   Gate: [rf/validate-at-boundary-interceptor] — validates the event vector in prod.

;; localStorage rehydration — BODY-READ (handler fetches the value itself)
(rf/reg-event-fx :session/rehydrate                              ;; flag — no always-on gate
  (fn [{:keys [db]} _]
    (let [raw (.getItem js/localStorage "session")]
      {:db (assoc db :session (js->clj (js/JSON.parse raw)))})))
;;   Gate: [rf/validate-at-boundary-interceptor] does NOTHING here — the fetched
;;   value never enters the event vector. Validate the raw value (m/validate) or
;;   move the read into a validating cofx. See the regression example below.
```

The event-payload shapes are flagged unless `validate-at-boundary-interceptor` (or a Managed HTTP `:decode`) is wired; the body-read shape is flagged until the **fetched value itself** is validated — `validate-at-boundary-interceptor` is the wrong gate for it.

## Regression example — body-read boundaries need the value validated, not the event

This handler carries **both** a `:schema` for its event id **and** `[rf/validate-at-boundary-interceptor]`. For an event-payload boundary that would close the gate. It is **still a finding** — the untrusted value (`localStorage`) is read *inside the body*, so the interceptor validates the (empty, trusted) `[:session/rehydrate]` dispatch and never touches the parsed `localStorage` payload.

```clojure
(def Session
  [:map [:user/id :uuid] [:user/roles [:set :keyword]]])

(rf/reg-app-schema [:session] Session)                           ;; dev-only — elided in production

(rf/reg-event-fx :session/rehydrate
  {:schema [:cat [:= :session/rehydrate]]}                       ;; dev-only — pins the (trusted) dispatch shape
  [rf/validate-at-boundary-interceptor]                          ;; validates the EVENT VECTOR — not the localStorage value
  (fn [{:keys [db]} _]
    (let [raw (.getItem js/globalThis.localStorage "session")]   ;; <-- untrusted body read
      {:db (assoc db :session (js->clj (js/JSON.parse raw)))}))) ;; production: writes arbitrary localStorage straight in
```

**Why it flags.** `validate-at-boundary-interceptor` runs the handler's `:schema` over `[:session/rehydrate]` — which the app dispatched itself and is trivially valid. The interceptor has no visibility into `raw`; nothing validates the JSON a tampered or stale `localStorage` returns. In production the `reg-app-schema` write-check is elided too, so attacker-controlled or malformed session data lands in `app-db` unchecked.

**The fix — validate the raw value, two equivalent shapes:**

```clojure
;; (a) Always-on Malli check over the raw value, before the write:
(rf/reg-event-fx :session/rehydrate
  (fn [{:keys [db]} _]
    (let [raw    (.getItem js/globalThis.localStorage "session")
          parsed (some-> raw js/JSON.parse (js->clj :keywordize-keys true))]
      (if (m/validate Session parsed)                            ;; ALWAYS-ON — runs in production
        {:db (assoc db :session parsed)}
        {:fx [[:session/clear-corrupt]]}))))                     ;; reject, don't ingest

;; (b) Better — move the read+validation into a cofx; the handler only sees a clean value:
(rf/reg-cofx :session/stored
  {:doc "Materialise + validate the persisted session; nil if absent/corrupt."}
  (fn [ctx]
    (let [parsed (some-> (.getItem js/globalThis.localStorage "session")
                         js/JSON.parse (js->clj :keywordize-keys true))]
      (assoc-in ctx [:coeffects :session]
                (when (m/validate Session parsed) parsed)))))    ;; ALWAYS-ON validation in the cofx

(rf/reg-event-fx :session/rehydrate
  [(rf/inject-cofx :session/stored)]
  (fn [{:keys [db session]} _]                                   ;; :session is already validated data
    (cond-> {} session (assoc :db (assoc db :session session)))))
```

Both gates validate the **value the body would have read**, so the boundary is closed in the production bundle — not just the dispatch shape.

## Edge cases — when schemaless is fine

- **Internal-only events** that never touch untrusted data — UI toggles, navigation events with structurally-fixed arg shapes (`[:menu/toggle]`, `[:nav/to :route-id]`). The handler's args come from the application's own code; no boundary is crossed.
- **Pre-alpha throwaway prototypes** where the schema would churn faster than the data — but mark the path with a `TODO` and add the production gate before the path stabilises into a feature.
- **Events whose payload is genuinely opaque** to the handler (it just forwards the value to another fx without inspecting it) — the always-on gate still applies if the value originated outside the app's own dispatches; the validator pins the **shape of the forwarded slot** so the downstream receiver can rely on it.
