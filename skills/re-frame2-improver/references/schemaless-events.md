# Anti-pattern — Schemaless events at boundaries

`reg-event` handlers that ingest untrusted data — HTTP responses, WebSocket frames, `postMessage` payloads, query-string params, `localStorage` rehydration — without an always-on production validator at the boundary. Dev-only `:schema` and `reg-app-schema` are necessary but not sufficient: both are elided in production builds (`goog.DEBUG = false`), so the handler writes whatever the source returned straight into `app-db` in the deployed bundle.

## Detection rules

**The cardinal rule.** Any handler that crosses an untrusted boundary — HTTP response, WebSocket frame, `postMessage` payload, query-string param, `localStorage` rehydration, IndexedDB read, third-party iframe — is **flagged** unless **production validation** is wired for *the untrusted value itself*.

**Two boundary shapes, two production gates.** The untrusted value arrives in one of two places, and only the matching gate counts:

- **Event-payload** — the value rides *in the dispatched event vector* (a Managed HTTP reply envelope, appended as the last arg to a `:reply-to` / `:on-success` target; a `postMessage` arg; a query-string map dispatched as `[:route/params-received params]`). The always-on gate is the `:rf.schema/at-boundary` interceptor ref in metadata `:interceptors` (it forces the handler's `:schema` to run over the **event vector** at run-time, production included), **or** a Managed HTTP `:decode <Schema>` on the originating request, **or** a custom registered interceptor that Malli-validates the event vector outside any `goog.DEBUG` guard.
- **Body-read** — the handler *reads the value mid-body* (`(.getItem js/localStorage …)`, `js/window.location.search`, a stashed `(.-data msg)`, an IndexedDB cursor) then writes it to `app-db`. `:rf.schema/at-boundary` is **useless here** — the value never appears in the event vector it checks. The trust gate must wrap the **raw value**: a validating cofx / interceptor / fx-reply path that materialises and validates the value *before* the handler writes it, **or** an unconditional `(m/validate Schema raw)` / `m/coerce` in the body (not behind `(when ^boolean js/goog.DEBUG …)`). **The in-body spelling is sufficient on its own only when the value does not feed durable state.** A body-read feeding a *durable* write must *also* fold a **recorded** fact — a recordable cofx or an event-payload value, never a live host read at the write site — because replay (epoch-restore, SSR hydration, time-travel) re-runs the handler against whatever the host returns *then*; see the Regression example below and [`imperative-effects.md` §the durable/diagnostic fork](imperative-effects.md#reads--the-durablediagnostic-fork-ep-0010).

Detection logic, per candidate handler:

1. Ingests data from an untrusted source? No → not in scope.
2. **Where does the untrusted value arrive?** Classify event-payload vs body-read; it picks which gate counts. Matching gate present → not a finding.
3. No matching gate → **flag**, regardless of `:schema` / `reg-app-schema` (both dev-elided). A body-read handler carrying `:schema` + `:rf.schema/at-boundary` *for the event id only* is still a finding — see the Regression example.

Greppable signals — flag when **any** match AND no production gate is wired:

- A `reg-event` handler whose `:fx` includes `:rf.http/managed`, `:http-xhrio`, or websocket-id keywords, or a Managed-HTTP reply target (an `:on-success` / `:on-failure` / `:reply-to` handler) that writes the appended reply envelope's `(:value reply)` — or `(:body event)` / `(:data event)` from a network or `postMessage` source — into `app-db`.
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
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:url (str "/articles/" slug)}
            :on-success [:article/loaded]}]]}))                  ;; addresses the reply — but no :decode gate

(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ reply]]                                  ;; reply envelope appended as the last arg
    {:db (assoc db :article (:value reply))}))                  ;; trust everything that comes back
```

**After** — schema-validated boundary with a production gate:

```clojure
(def Article
  [:map
   [:slug    :string]
   [:title   :string]
   [:body    :string]
   [:authors [:vector [:map [:id :uuid] [:name :string]]]]])

(rf/reg-app-schema [:article :data] Article)          ;; dev-only — validates the article PAYLOAD slice only

(rf/reg-event :article/load
  {:doc    "Load one article by slug; address its reply to :article/loaded."
   :schema [:cat [:= :article/load] [:map [:slug :string]]]}    ;; dev-only — pins the (trusted) trigger shape
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    {:db (assoc-in db [:article :status] :loading)              ;; lifecycle status lives OFF the schema'd payload path
     :fx [[:rf.http/managed
           {:request    {:url (str "/articles/" slug)}
            :decode     Article                                 ;; ALWAYS-ON — decode validates the untrusted body in prod
            :on-success [:article/loaded]
            :on-failure [:article/load-failed]}]]}))

(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ reply]]                                  ;; reply envelope appended as the last arg
    {:db (assoc-in db [:article :data] (:value reply))}))       ;; :value already validated by :decode Article (prod) + path schema (dev)

(rf/reg-event :article/load-failed
  (fn [{:keys [db]} [_ reply]]
    {:db (assoc-in db [:article :error] (:error reply))}))      ;; classified :rf.http/* map at :error
```

## Regression example — body-read boundaries need the value validated, not the event

This is the trap. The handler below carries **both** a `:schema` for its event id **and** the `:rf.schema/at-boundary` interceptor ref — exactly the shape that closes an *event-payload* boundary. It is **still a finding**, because the untrusted value (`localStorage`) is read *inside the body*: the interceptor validates the (empty, trusted) `[:session/rehydrate]` dispatch and never touches the parsed payload. The same trap applies to any body-read source — query string, a stashed `postMessage`, an IndexedDB cursor.

```clojure
(def Session
  [:map [:user/id :string] [:user/roles [:vector :string]]])    ;; JSON-representable — the persisted shape

(rf/reg-app-schema [:session] Session)                 ;; dev-only — elided in production

(rf/reg-event :session/rehydrate
  {:schema [:cat [:= :session/rehydrate]]                        ;; dev-only — pins the (trusted) dispatch shape
   :interceptors [:rf.schema/at-boundary]}                        ;; validates the EVENT VECTOR — not the localStorage value
  (fn [{:keys [db]} _]
    (let [raw (.getItem js/globalThis.localStorage "session")]   ;; <-- untrusted body read
      {:db (assoc db :session (js->clj (js/JSON.parse raw)))}))) ;; production: writes arbitrary localStorage straight in
```

**Why it flags.** `:rf.schema/at-boundary` validates only the trivially-valid `[:session/rehydrate]` dispatch; it has no visibility into `raw`, and the `reg-app-schema` write-check is elided in production — so nothing validates the JSON a tampered/stale `localStorage` returns.

**The fix — a recordable validating cofx.** The value feeds **durable** app-db (`:session`), so two boundaries must close at once: the **trust** boundary (validate the untrusted value, always-on) *and* the **replay** boundary (a durable write folds a *recorded* fact, never a live host read). One `reg-cofx` **recordable generator** does both — it reads `localStorage` once at processing-start, validates it, records the result on the causal token, and re-presents that recorded value verbatim under epoch-restore / SSR-hydration / time-travel:

```clojure
(rf/reg-cofx :session/stored
  {:recordable? true                                            ;; durable write folds a RECORDED fact
   :doc "Materialise + validate the persisted session. Returns the validated
         session, else nil — when storage is ABSENT (first run, Node, SSR) or
         its contents are UNUSABLE (unparsable JSON, or JSON failing Session).
         Total by construction: it never throws. A recordable generator."}
  (fn []
    (let [parsed (try
                   (some-> (.-localStorage js/globalThis)        ;; PROPERTY first — absent on Node/SSR
                           (.getItem "session")                  ;; nil on a first run
                           js/JSON.parse                         ;; throws on a corrupt entry…
                           (js->clj :keywordize-keys true))
                   (catch :default _ nil))]                      ;; …so decoding is bounded here, never propagated
      (when (m/validate Session parsed) parsed))))               ;; ALWAYS-ON validation; recorded + re-presented

(rf/reg-event :session/rehydrate
  {:rf.cofx/requires [:session/stored]}
  (fn [{:keys [db session/stored]} _]                            ;; the validated value arrives flat
    (cond-> {} stored (assoc :db (assoc db :session stored)))))
```

The generator's `(m/validate Session parsed)` is the always-on **trust** gate — it runs in production (no `goog.DEBUG` guard), so a tampered or stale `localStorage` payload is rejected before it reaches `app-db`. `:recordable? true` is what makes it **replay-safe**: without it the cofx is *ambient* (the `reg-cofx` default), so epoch-restore / SSR-hydration / time-travel re-run the generator against **live** `localStorage` and the replayed `:session/rehydrate` folds a *different* value than the one first written. This is the canonical recordable-generator shape ([`cofx.md`](../../re-frame2/references/fundamentals/cofx.md) §the app-owned recordable generator — the shipped `:todo.storage/todos` boot read).

**Absent and unusable are two conditions, and the generator has to be total over both.** Different causes, same delivered value, and neither may throw:

- **Absent** — `globalThis.localStorage` does not exist on Node, under SSR, or in a headless test runner, and `getItem` returns `nil` on a first run. This is the ordinary cold-boot case, so a throw here would break every new app and every Node test. The **property lookup must be the first link in the `some->` chain**: written the other way round, `(some-> (.getItem js/globalThis.localStorage "session") …)` evaluates the method call *before* `some->` can test anything, so on a host without storage it throws rather than short-circuiting. Property-first is what turns "no storage" into a `nil` flowing through the pipeline. The `try` still wraps the property access, because in a browser with site data blocked the access itself throws instead of yielding `undefined`.
- **Unusable** — a hand-edited, truncated or stale-format entry makes `js/JSON.parse` throw, and a well-formed value of the wrong shape fails `Session`. Anomalous rather than ordinary, but the caller cannot control it either and it has the same sane default: treat it as *no session* and let the app render its signed-out state.

**Why the unusable case must not simply throw.** A throw out of a coeffect supplier is not a loud failure. The framework catches it, emits `:rf.error/coeffect-exception`, and sets `:rf/skip-handler?` — so `:session/rehydrate` **never runs**, and what you observe is a boot step that silently did not happen. That is strictly harder to diagnose than the `nil` the handler already handles. Failing loudly on corrupt persisted state is a legitimate policy; a supplier throw is not how you spell it. If the app must *act* on the difference — "your session expired" versus "you were never signed in" — return a data-shaped result (`{:status :ok :session …}` / `{:status :invalid}` / `{:status :absent}`) and branch on it in the handler, rather than splitting the two into a throwing path and a `nil` path.

**Do not reach for `:platforms #{:client}` in place of that guard.** The sibling `:local-storage/set` **fx** in [`imperative-effects.md`](imperative-effects.md#worked-example) carries it, and for an fx a platform skip is benign — the effect simply does not run. For a **required recordable cofx** it is not: a platform-skipped generator produces no fact at all, so a universally-dispatched `:session/rehydrate` follows the *missing-required* path instead of the promised `nil` path. Keep this cofx universal and `nil`-off-browser — the shape the shipped `:todo.storage/todos` boot read already uses.

> **Two boundaries, and this read crosses both.** Validation alone closes the **trust** boundary; it does not close the **replay** boundary. For a body-read that does **not** feed durable state — the handler rejects the value, or forwards it to an fx without folding it into `app-db` / runtime-db / a snapshot — an unconditional `(m/validate Schema raw)` **in the handler body** (never behind `(when ^boolean js/goog.DEBUG …)`) is a sufficient trust gate on its own. What forces the recordable cofx *here* is the **durable** destination (`:session`): a boundary read feeding durable state is separately subject to the durable-write rule — durable state folds a *recorded* fact, never an ambient/inline host read at the write site — see [`imperative-effects.md` §the durable/diagnostic fork](imperative-effects.md#reads--the-durablediagnostic-fork-ep-0010). A boot/rehydrate read is **not** exempt: epoch-restore, SSR-hydration, and time-travel re-fold `:session/rehydrate`, so it must fold the *recorded* value, not a live re-read. (The ambient-boot-read shape — a `localStorage` read registered ambient and folded into durable app-db on `:*/initialise` — was ruled a replay hole and remediated to a recordable generator in the shipped examples; see EP-0017 §Implementation errata.)

## Edge cases — when schemaless is fine

- **Internal-only events** that never touch untrusted data — UI toggles, navigation with fixed arg shapes (`[:menu/toggle]`, `[:nav/to :route-id]`). The args come from the app's own code; no boundary is crossed.
- **Pre-alpha throwaway prototypes** where the schema would churn faster than the data — but mark the path with a `TODO` and add the production gate before it stabilises.
- **Events whose payload is genuinely opaque** to the handler (it forwards the value to another fx without inspecting it) — the always-on gate still applies if the value originated outside the app's own dispatches; the validator pins the **shape of the forwarded slot** so the downstream receiver can rely on it.
