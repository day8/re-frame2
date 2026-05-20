# API cheatsheet

One-line signatures for the public `re-frame.core` surface. **For full docstrings, design rationale, and gotchas, see `SKILL-REDIRECT.md` → *Definitive API reference*.** This page is a glance, not a manual. Alias conventions: `rf` is `[re-frame.core :as rf]`; `ts` is `[re-frame.test-support :as ts]`. Leaf cross-refs in `SKILL.md`'s loading map.

## Registration

| Surface | Shape |
|---|---|
| `rf/reg-event-db` | `(id [intercept?] (fn [db ev] new-db))` |
| `rf/reg-event-fx` | `(id [intercept?] (fn [cofx ev] fx-map))` |
| `rf/reg-event-ctx` | `(id [intercept?] (fn [ctx] ctx'))` |
| `rf/reg-fx` | `(id (fn [value] ...))` |
| `rf/reg-cofx` | `(id (fn [cofx & args] cofx'))` |
| `rf/reg-sub` | `(id [signals?] (fn [db\|inputs query-v] value))` |
| `rf/reg-view` | `(sym [args] body)` — defn-shape, auto-injects `dispatch`/`subscribe` |
| `rf/reg-view*` | `(id metadata? render-fn)` — runtime form |
| `rf/reg-frame` | `(id metadata-map)` |
| `rf/reg-app-schema` | `(path schema opts?)` — boundary validation; needs `day8/re-frame2-schemas` |
| `rf/reg-machine` / `rf/reg-machine*` | `(id machine-spec)` — needs `day8/re-frame2-machines` |
| `rf/reg-flow` | `(flow-map opts?)` — needs `day8/re-frame2-flows` |
| `rf/reg-route` | `(id metadata-map)` — needs `day8/re-frame2-routing` |
| `rf/reg-error-projector` | `(id metadata? (fn [trace-event] public-error))` — needs `day8/re-frame2-ssr` |
| `rf/reg-http-interceptor` | `({:frame :id :before})` — needs `day8/re-frame2-http` |

## Dispatch, subscribe, frames

| Surface | Shape |
|---|---|
| `rf/dispatch` | `(event)` / `(event opts)` — async queued |
| `rf/dispatch-sync` | `(event)` / `(event opts)` — drains to fixed point |
| `rf/subscribe` | `(query-v)` / `(frame-id query-v)` → reaction |
| `rf/subscribe-once` | `(query-v)` — one-shot: materialise + deref + unsubscribe |
| `rf/unsubscribe` | `(query-v)` / `(frame-id query-v)` |
| `rf/compute-sub` | `(query-v db)` — pure; bypass cache (preferred in tests) |
| `rf/with-frame` | `(frame-id body)` / `([sym expr] body)` — bind active frame |
| `rf/current-frame` | `()` — `:rf/default` outside any binding |
| `rf/dispatcher` / `rf/subscriber` | `()` — frame-bound closure; captures `(current-frame)` at call time; safe during render AND from async callbacks |
| `rf/bound-fn` | `([args] body+)` — macro form of `dispatcher` for callbacks; restores `*current-frame*` inside the body |
| `rf/frame-provider` | (CLJS) Reagent component `[frame-provider {:frame ...} & children]` |
| `rf/get-frame-db` | `(frame-id)` — value-form app-db read |
| `rf/snapshot-of` | `(path)` / `(path opts)` — `get-in` over the active frame |
| `rf/make-frame` / `rf/reset-frame!` / `rf/destroy-frame!` | low-level frame lifecycle |

## Machines

| Surface | Shape |
|---|---|
| `rf/sub-machine` | `(machine-id)` → reaction `{:state :data :tags}` |
| `rf/machine-has-tag?` | `(machine-id tag)` → reaction (boolean) |
| `rf/machines` / `rf/machine-meta` | id list / registered spec |
| `rf/machine-by-system-id` | `(system-id)` / `(... frame-id)` |
| `rf/dispatch-to-system` | `(system-id event)` / `(... frame-id)` |
| `rf/machine-transition` | `(machine snapshot event)` → `[snapshot' fx]` pure |
| `rf/create-machine-handler` | `(machine)` → event-fx handler |

## Routing — `day8/re-frame2-routing`

| Surface | Shape |
|---|---|
| `rf/match-url` | `(url)` → `{:route-id :params :query ...}` or `nil` |
| `rf/route-url` | `(route-id path-params)` / `(... query-params)` → `"/url"` |

## HTTP — `day8/re-frame2-http`

| Surface | Shape |
|---|---|
| `rf/with-managed-request-stubs` | macro: `(stubs & body)` |
| `rf/with-managed-request-stubs*` | fn: `(stubs thunk)` |
| `rf/install-managed-request-stubs!` / `uninstall-managed-request-stubs!` | per-call fx-overrides |
| `rf/clear-http-interceptor` | `(id)` / `(frame id)` |

## Test support — `re-frame.test-support` (see `cross-cutting/testing.md`)

| Surface | Shape |
|---|---|
| `ts/reset-runtime-fixture` | `(opts?)` → fixture-fn for `(use-fixtures :each ...)` |
| `ts/with-fresh-registrar` | `(body-fn)` — registrar snapshot/restore bracket |
| `ts/snapshot-registrar` / `ts/restore-registrar!` | low-level snapshot/restore |
| `ts/dispatch-sequence` | `(events)` / `(events opts)` — sync-drain each, `:after-each` hook |
| `ts/assert-state` | `(expected-db)` / `(path expected-val)` / `(... opts)` |

## SSR — `day8/re-frame2-ssr`

| Surface | Shape |
|---|---|
| `rf/render-to-string` | `(tree)` / `(tree opts)` — opts: `:doctype?` `:emit-hash?` |
| `rf/render-tree-hash` | `(tree)` → `"fnv1a-32bit-hex"` |
| `rf/project-error` | `(frame-id trace-event)` → public-error-map |

## Schemas — `day8/re-frame2-schemas`

| Surface | Shape |
|---|---|
| `rf/app-schema-at` / `rf/app-schemas` / `rf/app-schemas-digest` | read-only schema queries |
| `rf/set-schema-validator!` / `rf/set-schema-explainer!` | swap-in non-Malli validator |
| `rf/at-boundary` | production-side validation interceptor |

## Trace and epoch — `day8/re-frame2-epoch`

| Surface | Shape |
|---|---|
| `rf/register-trace-cb!` / `rf/remove-trace-cb!` / `rf/emit-trace-event!` | trace plumbing |
| `rf/trace-buffer` / `rf/clear-trace-buffer!` | retain-N ring |
| `rf/epoch-history` | `(frame-id)` → `[epoch-records]` |
| `rf/restore-epoch` | `(frame-id epoch-id)` → bool |
| `rf/register-epoch-cb!` / `rf/remove-epoch-cb!` | per-drain-settle listener |
| `rf/reset-frame-db!` | `(frame-id new-db)` → bool — dev/pair-tool write |

## Interceptors, boot, introspection

| Surface | Shape |
|---|---|
| `rf/->interceptor` | `({:id :before :after})` → interceptor |
| `rf/get-coeffect` / `rf/assoc-coeffect` / `rf/get-effect` / `rf/assoc-effect` | inside an interceptor |
| `rf/inject-cofx` | `(id & args)` — cofx injector |
| `rf/path` / `rf/unwrap` | std interceptors |
| `rf/init!` | `(adapter-map)` — install adapter + ensure `:rf/default`. No registry. |
| `rf/install-adapter!` / `rf/destroy-adapter!` / `rf/current-adapter` / `rf/current-adapter-spec` | low-level adapter ops; `current-adapter` → discriminator keyword, `current-adapter-spec` → spec map |
| `rf/clear-event` / `rf/clear-sub` / `rf/clear-fx` / `rf/clear-flow` / `rf/clear-sub-cache!` | targeted deregistration |
| `rf/configure` | `(:epoch-history\|:trace-buffer\|:sub-cache opts)` — runtime knobs |
| `rf/registrations` / `rf/handler-meta` / `rf/handler-ids` | registrar reads |
| `rf/frame-ids` / `rf/view` | registry reads |
| `rf/frame-meta` | `(frame-id)` → flat map: `:id` + preset-expansion (`:preset` `:fx-overrides` `:drain-depth` `:doc` `:tags` `:url-bound?` `:platform` `:on-error` …) + lifecycle (`:created-at` `:destroyed?` `:listeners`) — all top-level per Spec-Schemas `:rf/frame-meta` |
| `rf/sub-cache` (CLJS) / `rf/sub-topology` | dynamic / static sub graph reads |

Optional-artefact surfaces raise `:rf.error/<artefact>-artefact-missing` (registrations / writes) or degrade to `nil`/`[]`/`false` (read-only queries) when the artefact is absent.

---

*Derived from `implementation/core/src/re_frame/core.cljc` (the public surface) and the per-artefact source trees under `implementation/{machines,schemas,routing,http,ssr,epoch,flows}/` @ main `89bd9c3`. Re-verify when new public surface lands.*
