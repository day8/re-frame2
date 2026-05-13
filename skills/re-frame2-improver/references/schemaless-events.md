# Anti-pattern — Schemaless events at boundaries

`reg-event-fx` (or `reg-event-db`) handlers that ingest untrusted data — HTTP responses, WebSocket frames, `postMessage` payloads, query-string params — with no `:spec` on the handler and no `reg-app-schema` for the destination `app-db` path. The event vector is a payload of unknown shape; the handler writes it into `app-db` without validating either side.

## Detection rules

Greppable signals:

- `reg-event-fx` whose `:effects` (return-map `:fx`) include `:rf.http/managed`, `:http-xhrio`, websocket-id keywords, or whose handler reads `(:rf/reply event)` / `(:body event)` — and whose declaration has **no** `:spec` metadata key.
- `reg-event-db` named `:*/loaded`, `:*/received`, `:*/decoded`, `:*/synced` whose handler writes `(assoc db :foo/bar payload)` — without a corresponding `reg-app-schema` somewhere in the codebase for `[:foo/bar]` or `[:foo]`.
- Events that take an unstructured second arg — `(fn [db [_ data]] (assoc db :remote data))` where `data` is the raw HTTP body — and no validator-at-boundary interceptor (`spec/validate-at-boundary`).
- New handlers introduced in a feature whose `app-db` writes use paths absent from `(rf/app-schemas)`.

Structural signal: the boundary between **untrusted input** and **trusted `app-db`** is crossed without a Malli schema gate.

## Why it's an anti-pattern

`app-db` is the trust boundary. The whole substrate downstream of it (subs, views, machine reads, story snapshots, time-travel) assumes the values it reads conform to the application's mental model. A schemaless boundary event smuggles arbitrary data past the gate — a stale API field, a server schema change, a malformed query string — and the failure surfaces hundreds of dispatches later in a sub that crashes on a missing key. Schemas at boundaries are Cardinal Rule #4 (`skills/re-frame2/SKILL.md`).

The runtime offers two complementary tools: handler `:spec` (validates the **event vector** before the handler runs) and `reg-app-schema` (validates the **app-db path** after the handler writes). The first catches malformed dispatches; the second catches malformed writes. Both are dev-elided in production unless explicitly attached at the boundary via `validate-at-boundary`.

## The canonical fix

[`skills/re-frame2/reference/fundamentals/schemas.md`](../../re-frame2/reference/fundamentals/schemas.md) — `reg-app-schema` for the destination path, `:spec` on the handler metadata, and the `validate-at-boundary` interceptor for handlers that must validate in production builds.

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

**After** — schema-validated boundary:

```clojure
(def Article
  [:map
   [:slug    :string]
   [:title   :string]
   [:body    :string]
   [:authors [:vector [:map [:id :uuid] [:name :string]]]]])

(rf/reg-app-schema [:article] Article)

(rf/reg-event-fx :article/load
  {:doc  "Load one article by slug."
   :spec [:cat [:= :article/load] [:map [:slug :string]]]}
  [spec/validate-at-boundary]
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      {:db (assoc db :article (:value reply))}                  ;; now validated by reg-app-schema
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:rf.http/managed
             {:request {:url (str "/articles/" slug)}
              :decode  Article}]]})))                            ;; Managed HTTP decode also runs Article
```

## Edge cases — when schemaless is fine

- **Internal-only events** that never touch untrusted data — UI toggles, navigation events with structurally-fixed arg shapes (`[:menu/toggle]`, `[:nav/to :route-id]`). The handler's args come from the application's own code; a `:spec` adds runtime cost in dev for no boundary-trust gain.
- **Pre-alpha throwaway prototypes** where the schema would churn faster than the data — but mark the path with a `TODO` and add the schema before the path stabilises into a feature.
- **Events whose payload is genuinely opaque** to the handler (it just forwards the value to another fx without inspecting it) — but then `:spec` should still pin the **shape of the forwarded slot** so the receiver downstream can rely on it.
