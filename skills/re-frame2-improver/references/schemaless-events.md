# Anti-pattern — Schemaless events at boundaries

`reg-event-fx` (or `reg-event-db`) handlers that ingest untrusted data — HTTP responses, WebSocket frames, `postMessage` payloads, query-string params, `localStorage` rehydration — without an always-on production validator at the boundary. Dev-only `:spec` and `reg-app-schema` are necessary but not sufficient: both are elided in production builds, so the handler writes whatever the source returned straight into `app-db` in the deployed bundle.

## Detection rules

**The cardinal rule.** Any handler that crosses an untrusted boundary — HTTP response, WebSocket frame, `postMessage` payload, query-string param, `localStorage` rehydration, IndexedDB read, third-party iframe — is **flagged** unless **production validation** is wired. Production validation means an always-on gate: the `[spec/validate-at-boundary]` interceptor on the handler, an equivalent always-on Malli-check interceptor, or a `:decode` schema on a Managed HTTP request that runs in production builds. `:spec` on the handler metadata and `reg-app-schema` on the destination path are necessary but **not sufficient on their own** — both are dev-elided in production builds (`goog.DEBUG = false`), so a handler that relies only on them is unvalidated in the deployed bundle.

Greppable signals — flag when **any** of these match AND no production gate is wired:

- `reg-event-fx` whose `:effects` (return-map `:fx`) include `:rf.http/managed`, `:http-xhrio`, websocket-id keywords, or whose handler reads `(:rf/reply event)` / `(:body event)` / `(:data event)` from a network or `postMessage` source.
- `reg-event-db` named `:*/loaded`, `:*/received`, `:*/decoded`, `:*/synced`, `:*/rehydrated`, `:*/restored` whose handler writes `(assoc db :foo/bar payload)` where `payload` originated outside the application's own dispatches.
- Events that take an unstructured second arg — `(fn [db [_ data]] (assoc db :remote data))` — where `data` is the raw boundary payload.
- Handlers that read `js/window.location.search`, `js/localStorage`, `js/sessionStorage`, `js/postMessage`, or `IndexedDB` results in their bodies (often via fx) and write the result to `app-db`.
- New handlers introduced in a feature whose `app-db` writes use paths absent from `(rf/app-schemas)`.

Detection logic (apply for each candidate handler):

1. Does the handler ingest data from an untrusted source? (See list above.) If no → not in scope for this leaf.
2. Is **at least one** of these production-gate mechanisms present?
   - `[spec/validate-at-boundary]` (or an equivalent always-on validator) in the handler's interceptor chain.
   - Managed HTTP `:decode <Schema>` on the originating request — runs in production.
   - A custom interceptor that performs Malli validation **outside `(when ^boolean js/goog.DEBUG …)`** or any dev-only conditional.
   If yes → not a finding.
3. If no → **flag**, regardless of whether `:spec` and `reg-app-schema` are attached. Both are dev-elided in production; the boundary is open in the deployed bundle.

Structural signal: the boundary between **untrusted input** and **trusted `app-db`** is crossed without a Malli schema gate **that runs in production**.

## Why it's an anti-pattern

`app-db` is the trust boundary. The whole substrate downstream of it (subs, views, machine reads, story snapshots, time-travel) assumes the values it reads conform to the application's mental model. A schemaless boundary event smuggles arbitrary data past the gate — a stale API field, a server schema change, a malformed query string, a tampered `localStorage` payload — and the failure surfaces hundreds of dispatches later in a sub that crashes on a missing key. Schemas at boundaries are Cardinal Rule #4 (`skills/re-frame2/SKILL.md`).

The runtime offers two complementary dev-time tools: handler `:spec` (validates the **event vector** before the handler runs) and `reg-app-schema` (validates the **app-db path** after the handler writes). The first catches malformed dispatches; the second catches malformed writes. **Both are dev-elided in production** (gated on `goog.DEBUG`) unless explicitly attached at the boundary via the always-on `validate-at-boundary` interceptor (or an equivalent always-on validator, e.g. a Managed HTTP `:decode` schema on the originating request). A handler that carries only `:spec` and / or `reg-app-schema` is validated in dev but unvalidated in the deployed bundle — the exact place the boundary matters.

## The canonical fix

[`skills/re-frame2/reference/fundamentals/schemas.md`](../../re-frame2/reference/fundamentals/schemas.md) — at a minimum, an always-on gate at the boundary: the `[spec/validate-at-boundary]` interceptor on the handler, or a Managed HTTP `:decode <Schema>` on the originating request, or an equivalent custom always-on Malli-check interceptor. `:spec` on the handler metadata and `reg-app-schema` on the destination path are valuable dev-time tools that surface mismatches early — but they do not satisfy this rule on their own, because both are elided when `goog.DEBUG` is false.

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
  {:doc  "Load one article by slug."
   :spec [:cat [:= :article/load] [:map [:slug :string]]]}      ;; dev-only — pins dispatch shape in dev
  [spec/validate-at-boundary]                                   ;; ALWAYS-ON — runs in production
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :article (:value reply))}                  ;; reply is validated by Article on decode
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:rf.http/managed
             {:request {:url (str "/articles/" slug)}
              :decode  Article}]]})))                            ;; ALWAYS-ON — Managed HTTP decode runs in prod
```

## Regression example — dev-only validation isn't enough

The handler below carries **both** `:spec` and `reg-app-schema`, and looks like the "After" shape above. It is **still a finding** — no always-on gate is attached.

```clojure
(def Article
  [:map
   [:slug :string] [:title :string] [:body :string]])

(rf/reg-app-schema [:article] Article)                          ;; dev-only — elided in production

(rf/reg-event-fx :article/load
  {:doc  "Load one article by slug."
   :spec [:cat [:= :article/load] [:map [:slug :string]]]}      ;; dev-only — elided in production
  ;; NO [spec/validate-at-boundary] in the interceptor chain.
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :article (:value reply))}                  ;; production: writes raw HTTP body unchecked
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:rf.http/managed {:request {:url (str "/articles/" slug)}}]]})))
                                                                  ;; ^ no :decode either
```

**Why it flags.** In dev, `goog.DEBUG = true` runs the `:spec` and `reg-app-schema` validators — mismatches surface fast. In a production build the JIT/CC-elision strips both, and the handler writes whatever the server returned straight into `app-db`. The trust boundary is open in the bundle the user actually ships. The fix is to add `[spec/validate-at-boundary]` to the interceptor chain (validates the event vector at run-time, production included) or a Managed HTTP `:decode Article` on the request (validates the response at decode time, production included) — ideally both.

Other untrusted-boundary shapes that hit the same rule (the source varies, the gate is identical):

```clojure
;; query-string ingestion
(rf/reg-event-db :route/params-received                          ;; flag — no always-on gate
  {:spec [:cat keyword? :map]}
  (fn [db [_ params]] (assoc db :route/params params)))

;; localStorage rehydration
(rf/reg-event-fx :session/rehydrate                              ;; flag — no always-on gate
  (fn [_ _]
    (let [raw (.getItem js/localStorage "session")]
      {:db (assoc db :session (js->clj (js/JSON.parse raw)))})))

;; postMessage payload
(rf/reg-event-db :postmessage/received                           ;; flag — no always-on gate
  (fn [db [_ msg]] (assoc db :embed/state (.-data msg))))
```

Each writes an unvalidated payload past the trust boundary in production. Each is flagged regardless of any dev-only `:spec` / `reg-app-schema` it might carry.

## Edge cases — when schemaless is fine

- **Internal-only events** that never touch untrusted data — UI toggles, navigation events with structurally-fixed arg shapes (`[:menu/toggle]`, `[:nav/to :route-id]`). The handler's args come from the application's own code; no boundary is crossed.
- **Pre-alpha throwaway prototypes** where the schema would churn faster than the data — but mark the path with a `TODO` and add the production gate before the path stabilises into a feature.
- **Events whose payload is genuinely opaque** to the handler (it just forwards the value to another fx without inspecting it) — the always-on gate still applies if the value originated outside the app's own dispatches; the validator pins the **shape of the forwarded slot** so the downstream receiver can rely on it.
