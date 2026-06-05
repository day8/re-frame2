# API cheatsheet

One-line signatures for the public `re-frame.core` surface. **For full docstrings, design rationale, and gotchas, see `SKILL-REDIRECT.md` → *Definitive API reference*.** This page is a glance, not a manual. Alias conventions: `rf` is `[re-frame.core :as rf]`; `ts` is `[re-frame.test-support :as ts]`. Leaf cross-refs in `SKILL.md`'s loading map.

## Registration

| Surface | Shape |
|---|---|
| `rf/reg-event-db` | `(id meta? intercept? (fn [db ev] new-db))` |
| `rf/reg-event-fx` | `(id meta? intercept? (fn [cofx ev] fx-map))` |
| `rf/reg-event-ctx` | `(id meta? intercept? (fn [ctx] ctx'))` |
| `rf/reg-fx` | `(id [metadata?] (fn [ctx args] ...))` — `ctx` is `{:frame :event}`; `args` is the `:fx` entry's 2nd slot |
| `rf/reg-cofx` | `(id [metadata?] (fn [ctx] ctx') \| (fn [ctx value] ctx'))` — `ctx` is the interceptor ctx; assoc into `(:coeffects ctx)`. `value` is the `inject-cofx` per-call arg |
| `rf/reg-sub` | `(id [signals?] (fn [db\|inputs query-v] value))` |
| `rf/reg-view` | `(sym [args] body)` — defn-shape, auto-injects `dispatch`/`subscribe` |
| `rf/reg-view*` | `(id metadata? render-fn)` — runtime form |
| `rf/reg-frame` | `(id metadata-map)` |
| `rf/reg-app-schema` | `(path schema opts?)` — boundary validation; needs `day8/re-frame2-schemas` |
| `rf/reg-machine` / `rf/reg-machine*` | `(id machine-spec)` — needs `day8/re-frame2-machines` |
| `rf/reg-flow` | `(flow-map opts?)` — needs `day8/re-frame2-flows` |
| `rf/reg-route` | `(id metadata-map)` — needs `day8/re-frame2-routing` |
| `rf/reg-error-projector` | `(id metadata? (fn [trace-event] public-error))` — needs `day8/re-frame2-ssr` |
| `rf/reg-http-interceptor` | `(id interceptor-map)` — `interceptor-map` carries `:before` / `:after` / `:frame` / metadata; needs `day8/re-frame2-http` |

The `reg-event-*` middle slots are positional and **both optional, independently**: `(id metadata? interceptors? handler)`. You may pass the metadata-map alone, the interceptor-vector alone, **or both together** — `(reg-event-fx :id {:doc ... :schema ...} [some-interceptor] handler)` is the canonical form when an event needs both reflection metadata and an interceptor chain (e.g. `:schema` metadata + `rf/validate-at-boundary-interceptor`). The fn discriminates by type: a map is metadata, a vector is interceptors. Putting `:interceptors` *inside* the metadata-map silently drops the chain — the runtime emits `:rf.warning/interceptors-in-metadata-map`. Verified against `implementation/core/src/re_frame/events.cljc` `normalise-args` (the 3-arg `(metadata interceptors handler)` form) and Spec 001 §Allowed forms of the middle slot (form 3: "Both — metadata map AND a positional interceptors vector"). `reg-fx`/`reg-cofx`/`reg-error-projector` take a metadata-map only in that slot.

## Dispatch, subscribe, frames

| Surface | Shape |
|---|---|
| `rf/dispatch` | `(event)` / `(event opts)` — async queued |
| `rf/dispatch-sync` | `(event)` / `(event opts)` — drains to fixed point |
| `rf/subscribe` | `(query-v)` / `(frame-id query-v)` → reaction |
| `rf/subscribe-once` | `(query-v)` — one-shot: materialise + deref + unsubscribe |
| `rf/unsubscribe` | `(query-v)` / `(frame-id query-v)` |
| `rf/compute-sub` | `(query-v db)` — pure; bypass cache (preferred in tests) |
| `rf/with-frame` | `(frame-id body)` — pin `body` to an existing frame (lexical scope) |
| `rf/with-new-frame` | `([sym expr] body)` — create+own+destroy a frame for `body` |
| `rf/frame-provider` | (CLJS) Reagent component `[frame-provider {:frame ...} & children]` |
| `rf/frame-handle` | `()` / `(frame-id)` → `{:frame :dispatch :dispatch-sync :subscribe}` — operation bundle captured at creation; survives async |
| `rf/frame-bound-fn` | `([args] body+)` — macro: fn that re-binds the captured frame in its body (async callbacks) |
| `rf/frame-bound-fn*` | `(f)` / `(frame-id f)` — `*`-twin of the macro; wraps an existing fn value |
| `rf/current-frame-id` | `()` — active frame id; `:rf/default` outside any binding |
| `rf/app-db-value` | `(frame-id)` — value-form app-db read (plain map, no deref) |
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
| `rf/make-machine-handler` | `(machine)` → event-fx handler |

## Routing — `day8/re-frame2-routing`

| Surface | Shape |
|---|---|
| `rf/match-url` | `(url)` → `{:route-id :params :query ...}` or `nil` |
| `rf/route-url` | `(route-id path-params)` / `(... query-params)` → `"/url"` |

## HTTP — `day8/re-frame2-http`

Production fx surface: `re-frame.http-managed`. Test surfaces (canned-stub fxs + `with-managed-request-stubs` family): `re-frame.http-test-support` — the test machinery consolidates into one namespace; tests `:require` it explicitly.

| Surface | Shape |
|---|---|
| `rf/with-managed-request-stubs` | macro: `(stubs & body)` — needs `re-frame.http-test-support` in require closure |
| `rf/with-managed-request-stubs*` | fn: `(stubs thunk)` — needs `re-frame.http-test-support` |
| `rf/install-managed-request-stubs!` / `uninstall-managed-request-stubs!` | per-call fx-overrides — needs `re-frame.http-test-support` |
| `rf/clear-http-interceptor` | `(id)` / `(frame id)` — production surface, `re-frame.http-managed` |

## Test support — `re-frame.test-support` (see `cross-cutting/testing.md`)

| Surface | Shape |
|---|---|
| `ts/make-reset-runtime-fixture` | `(opts?)` → fixture-fn for `(use-fixtures :each ...)` |
| `ts/with-fresh-registrar` | `(body-fn)` — registrar snapshot/restore bracket |
| `ts/snapshot-registrar` / `ts/restore-registrar!` | low-level snapshot/restore |
| `ts/dispatch-sequence` | `(events)` / `(events opts)` — sync-drain each, `:after-each` hook |
| `ts/assert-path-equals` | `(path expected-val)` / `(path expected-val opts)` — mirrors `:rf.assert/path-equals` |
| `ts/assert-db-equals` | `(expected-db)` / `(expected-db opts)` — companion full-db form |

## View tests — `re-frame.test-helpers` (see `cross-cutting/testing.md`)

The view-tree assertion axis (commonly aliased `:as h`). Walk hiccup by `:data-testid`; the single-frame e2e trio brackets a fresh frame and stashes the root view.

| Surface | Shape |
|---|---|
| `h/with-app-fixture` | macro: `(opts body+)` / `(opts frame-id body+)` — opts `:install` `:root-view` `:root-view-args` `:frame-config`; brackets a fresh single frame |
| `h/expect-text` | `(testid expected)` (stashed root view) / `(tree testid expected)` — asserts `:data-testid` node text via `clojure.test/is` |
| `h/wait-until` | `(pred)` / `(pred opts)` / `(testid expected)` / `(testid expected opts)` — bounded poll; opts `:timeout-ms` (2000) `:interval-ms` (5) `:label`. JVM-sync (throws on timeout) / CLJS-Promise (rejects on timeout) |
| `h/find-by-testid` / `h/find-all-by-testid` / `h/find-by-testid-prefix` | `(tree testid)` → hiccup node(s) |
| `h/text-content` / `h/invoke-handler` | `(node)` → text · `(node event-key & args)` → calls the handler under `event-key` (e.g. `:on-click`) |
| `h/testid` | `(testid)` / `(testid attrs)` — attrs-fragment authoring helper for view call sites |

## SSR — `day8/re-frame2-ssr`

| Surface | Shape |
|---|---|
| `rf/render-to-string` | `(tree)` / `(tree opts)` — opts: `:doctype?` `:emit-hash?` |
| `rf/render-tree-hash` | `(tree)` → `"fnv1a-32bit-hex"` |
| `rf/project-error` | `(frame-id trace-event)` → public-error-map |
| `rf/reg-head` | `(id metadata? (fn [db route] head-model))` — register a head-fragment producer; routes name a head via `:head` route metadata |
| `rf/render-head` | `(head-id frame-id)` / `(head-id {:frame :route})` → produced `:rf/head-model` for a frame's app-db + active route |
| `rf/active-head` | `()` / `(frame-id)` → the active route's `:head` model (or the default head when none configured) |
| `rf/head-model->html` | `(head-model)` → inner-head HTML fragment in canonical order |
| `rf/head-snapshot` | `(frame-id)` → `{head-id → last-produced head-model}` (`{}` if none); tests / tools |

## Schemas — `day8/re-frame2-schemas`

| Surface | Shape |
|---|---|
| `rf/app-schema-at` / `rf/app-schemas` / `rf/app-schemas-digest` | read-only schema queries |
| `rf/set-schema-validator!` / `rf/set-schema-explainer!` | swap-in non-Malli validator |
| `rf/validate-at-boundary-interceptor` | production-side validation interceptor |

## Trace and epoch — `day8/re-frame2-epoch`

| Surface | Shape |
|---|---|
| `rf/register-listener!` / `rf/unregister-listener!` / `rf/emit-trace-event!` | trace plumbing |
| `rf/trace-buffer` / `rf/clear-trace-buffer!` | retain-N ring |
| `rf/epoch-history` | `(frame-id)` → `[epoch-records]` |
| `rf/restore-epoch` | `(frame-id epoch-id)` → bool |
| `rf/register-epoch-listener!` / `rf/unregister-epoch-listener!` | per-drain-settle listener |
| `rf/reset-frame-db!` | `(frame-id new-db)` → bool — dev/pair-tool write |

## Interceptors, boot, introspection

| Surface | Shape |
|---|---|
| `rf/->interceptor` | `({:id :before :after})` → interceptor |
| `rf/get-coeffect` / `rf/assoc-coeffect` / `rf/get-effect` / `rf/assoc-effect` | inside an interceptor |
| `rf/inject-cofx` | `(id)` / `(id value)` — cofx injector; `value` is the per-call arg passed to the cofx handler's 2-arity form |
| `rf/path` / `rf/unwrap-interceptor` | std interceptors |
| `rf/init!` | `(adapter-map)` — install adapter + ensure `:rf/default`. No registry. |
| `rf/install-adapter!` / `rf/destroy-adapter!` / `rf/current-adapter` / `rf/current-adapter-spec` | low-level adapter ops; `current-adapter` → discriminator keyword, `current-adapter-spec` → spec map |
| `rf/clear-event` / `rf/clear-sub` / `rf/clear-fx` / `rf/clear-flow` / `rf/clear-sub-cache!` | targeted deregistration |
| `rf/configure!` | `(:epoch-history\|:trace-buffer\|:elision opts)` — runtime knobs (`:elision` opts `{:rf.size/threshold-bytes N}`) |
| `rf/registrations` / `rf/handler-meta` / `rf/handler-ids` | registrar reads |
| `rf/features` | `()` → map of every optional-feature keyword → `{:maven :require :spec :loaded?}`. Ships to production (not elided) |
| `rf/feature-loaded?` | `(feature)` → bool — is the optional feature's impl artefact on the classpath. Known: `:schemas` `:machines` `:routing` `:flows` `:http` `:ssr` `:epoch` |
| `rf/require-feature!` | `(feature)` → `true`, or throws `:rf.error/feature-not-loaded` carrying the exact Maven coord + require form. Self-explaining early guard before a feature-dependent path |
| `rf/frame-ids` / `rf/view` | registry reads |
| `rf/frame-meta` | `(frame-id)` → flat map: `:id` + preset-expansion (`:preset` `:fx-overrides` `:drain-depth` `:doc` `:tags` `:url-bound?` `:platform` `:on-error` …) + lifecycle (`:created-at` `:destroyed?` `:listeners`) — all top-level per Spec-Schemas `:rf/frame-meta` |
| `rf/sub-cache` (CLJS) / `rf/sub-topology` | dynamic / static sub graph reads |

Optional-artefact surfaces raise `:rf.error/<artefact>-artefact-missing` (registrations / writes) or degrade to `nil`/`[]`/`false` (read-only queries) when the artefact is absent.

---

*Derived from `implementation/core/src/re_frame/core.cljc` (the public surface) and the per-artefact source trees under `implementation/{machines,schemas,routing,http,ssr,epoch,flows}/` @ main `1ed174f`. Re-verify when new public surface lands. This is the public-surface glance, not an exhaustive export inventory — for the complete facade see `SKILL-REDIRECT.md` → Definitive API reference.*
