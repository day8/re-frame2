# API cheatsheet

One-line signatures for the public `re-frame.core` surface. **For full docstrings, design rationale, and gotchas, see `SKILL-REDIRECT.md` → *Definitive API reference*.** This page is a glance, not a manual. Alias conventions: `rf` is `[re-frame.core :as rf]`; `ts` is `[re-frame.test-support :as ts]`. Leaf cross-refs in `SKILL.md`'s loading map.

## Registration

| Surface | Shape |
|---|---|
| `rf/reg-event` | `(id meta? (fn [cofx ev] effects-map-or-nil))` — the **one** event form (coeffects in, closed effects map out); `{:db ...}` is the db-write effect. Full-context work → a registered interceptor referenced by id in `meta?`'s `:interceptors` (EP-0022) |
| `rf/reg-fx` | `(id [metadata?] (fn [ctx args] ...))` — `ctx` is `{:frame :event}`; `args` is the `:fx` entry's 2nd slot |
| `rf/reg-cofx` | `(id [metadata?] (fn [] value) \| (fn [arg] value))` — **value-returning** supplier (EP-0017); returns the coeffect value directly. `arg` is the per-call arg from a `[id arg]` declaration. `meta` may carry `:recordable?` / `:provided?` / `:schema` / `:platforms`. Consumed via `:rf.cofx/requires` (not `inject-cofx`, removed) |
| `rf/reg-sub` | `(id (fn [db query-v] value))` layer-1 · `(id :<- […] … (fn [inputs query-v] value))` static · `(id (fn [query-v] [[:q…]…]) (fn [inputs query-v] value))` parametric — input fn returns a **vector of query vectors** (EP-0004), NOT `subscribe` reactions |
| `rf/reg-view` | `(sym [args] body)` — defn-shape, auto-injects `dispatch`/`subscribe` |
| `rf/reg-view*` | `(id metadata? render-fn)` — runtime form |
| `rf/reg-frame` | `(id metadata-map)` |
| `rf/reg-app-schema` | `(path schema opts?)` — boundary validation; needs `day8/re-frame2-schemas` |
| `rf/reg-machine` / `rf/reg-machine*` | `(id machine-spec)` — needs `day8/re-frame2-machines` |
| `rf/reg-flow` | `(flow-map opts?)` — needs `day8/re-frame2-flows` |
| `rf/reg-route` | `(id metadata-map)` — needs `day8/re-frame2-routing` |
| `rf/reg-error-projector` | `(id metadata? (fn [trace-event] public-error))` — needs `day8/re-frame2-ssr` |
| `rf/reg-http-interceptor` | `(id interceptor-map)` — `interceptor-map` carries `:before` / `:after` / `:frame` / metadata; needs `day8/re-frame2-http` |

The `reg-event` metadata-map is the one **superset** middle slot — reflection keys **and** a reserved `:interceptors` key carrying interceptor **references**: `(id {:doc ... :schema ... :interceptors [:auth/required [:rf.interceptor/path [:cart]]]} handler)`. The historical positional interceptor vector is retired; `(reg-event :id [i1 i2] handler)` and `(reg-event :id {:doc ...} [i1 i2] handler)` are registration errors. A malformed metadata value is `:rf.error/reg-event-bad-interceptors`, and an inline interceptor value (not a ref) in the chain is `:rf.error/inline-interceptor-removed` (EP-0022). There is one public event registrar: `reg-event-db` / `reg-event-fx` / `reg-event-ctx` are removed (EP-0018) — a db-only handler returns `{:db ...}`, and full-context work is a registered interceptor (`reg-interceptor`) referenced by id. Verified against `implementation/core/src/re_frame/events.cljc` `resolve-interceptors` and Spec 001 §Allowed forms of the middle slot. `reg-fx`/`reg-cofx`/`reg-error-projector` take a metadata-map only in that slot.

## Dispatch, subscribe, frames

| Surface | Shape |
|---|---|
| `rf/dispatch` | `(event)` / `(event opts)` — async queued; `opts` may carry `:rf.cofx` (EP-0017 recordable coeffects — a flat `fact-name → value` map; pin durable `:rf/time-ms` and other recordable facts; runtime stamps `:rf/time-ms` when omitted). The retired `:rf.world/inputs` opt is a hard error `:rf.error/world-inputs-renamed` |
| `rf/dispatch-sync` | `(event)` / `(event opts)` — drains to fixed point; same `:rf.cofx` opt |
| `rf/subscribe` | `(query-v)` / `(frame-id query-v)` → reaction |
| `rf/subscribe-once` | `(query-v)` — one-shot: materialise + deref + unsubscribe |
| `rf/unsubscribe` | `(query-v)` / `(frame-id query-v)` |
| `rf/compute-sub` | `(query-v db)` — pure; bypass cache (preferred in tests) |
| `rf/with-frame` | `(frame-id body)` — pin `body` to an existing frame (lexical scope) |
| `rf/with-new-frame` | `([sym expr] body)` — create+own+destroy a frame for `body` |
| `rf/frame-provider` | (CLJS) Reagent component `[frame-provider {:frame ...} & children]` |
| `rf/frame-handle` | `()` / `(frame-id)` → `{:frame :dispatch :dispatch-sync :subscribe}` — the one public carry primitive; operation bundle captured at creation, survives async |
| `rf/current-frame-id` | `()` — active frame id; raises `:rf.error/no-frame-context` outside any frame scope |
| `rf/app-db-value` | `(frame-id)` — value-form **app-db** partition read (plain map, no deref) |
| `rf/runtime-db-value` | `(frame-id)` — value-form **runtime-db** partition read (framework state; tools / privileged runtime) |
| `rf/frame-state-value` | `(frame-id)` → `{:rf.db/app … :rf.db/runtime …}` — the whole frame (SSR / epoch / tools) |
| `rf/snapshot-of` | `(path)` / `(path opts)` — `get-in` over the active frame's app-db |
| `rf/make-frame` / `rf/reset-frame!` / `rf/destroy-frame!` | low-level frame lifecycle (`reset-frame!` clears BOTH partitions) |

## Machines

| Surface | Shape |
|---|---|
| `[:rf/machine machine-id]` | subscription vector → reaction `{:state :data :tags}` (the canonical machine read) |
| `rf/machine-has-tag?` | `(machine-id tag)` → reaction (boolean) |
| `re-frame.machines/machines` / `re-frame.machines/machine-meta` | id list / registered spec — owned-ns surface, **not** on the `rf/` façade |
| `re-frame.machines/machine-by-system-id` | `(system-id)` / `(... frame-id)` — owned-ns surface, **not** on the `rf/` façade |
| `rf/dispatch-to-system` | `(system-id event)` / `(... frame-id)` |
| `re-frame.machines/machine-transition` | `(machine snapshot event)` → `[snapshot' fx]` pure — owned-ns surface, **not** on the `rf/` façade |
| `re-frame.machines/make-machine-handler` | `(machine)` → event-fx handler — owned-ns surface, **not** on the `rf/` façade |

## Images and frames (EP-0023, `re-frame.core`)

The public multi-frame model is `image -> frame -> event stream`: an image is the selected registration set a frame runs, a frame is the isolated execution context, the event stream is the program. A single-frame app never spells `image` — `reg-*` writes the default registration source and a frame resolves the implicit *default image* over it. Reach for explicit images only when the default process-wide set stops being the right boundary (two surfaces on one page, a tool beside its target, progressive doc examples, library packaging, isolated test/story frames).

| Surface | Shape |
|---|---|
| `rf/image` | `({:include-ns [<ns-glob> …] :registrations {…} :rf.image/requires #{…} :replace {…} :replace-standard {…} :id …})` → **inert image value** (pure data, no realm/registrar side effect). `:include-ns` selects by source-ns (`:rf.provenance/ns`); glob grammar `*`=one segment, `**`=zero-or-more; a zero-match pattern fails image assembly. Supplied to a frame via the `:images` vector. |

Object `make-frame` (returns the live frame object, accepts `:images`) and frame-targeted `reload-images!` are the in-progress facade wave (implementation in `re-frame.live-frame`); today the facade still exports the EP-0013 record `make-frame`. Construct image *values* with `rf/image`; create callable frames with `reg-frame` + `frame-provider` (see [`../fundamentals/frames.md`](../fundamentals/frames.md)).

## EP-0013 → EP-0023: the retired composition surface

EP-0023 supersedes EP-0013's public app/realm vocabulary. The public composition model is `image → frame → event stream` (`rf/image` + `rf/make-frame`). The EP-0013 app/realm construction/install/query family is **NOT on the `re-frame.core` facade** — the realm machinery is retained only as the **internal installation substrate** (the `re-frame.realm` / `re-frame.app-value` namespaces, used by SSR routing and tooling that requires them directly). Do not reach for these as public APIs; use the image/frame replacement.

| Retired EP-0013 surface | Public EP-0023 replacement |
|---|---|
| `rf/app` (app value) | `rf/image` — construct an image and supply it via `:images` |
| `rf/module` (module descriptor) | an image fragment — register ordinary `reg-*` forms; an `rf/image` selects them by `:include-ns` provenance glob |
| `rf/install!` / `rf/reinstall!` | `rf/make-frame` with `:images` / `rf/reload-images!` against a frame target |
| `rf/installed-app` | inspect a frame's resolved image generation |
| `rf/realm` / `rf/dispose-realm!` | target a frame (frame id, or a direct frame object for tests); `rf/make-frame` / `rf/destroy-frame!` are the lifecycle pair |
| `rf/realm-ids` / `rf/frame-realm` | the public address is the frame id (`rf/frame-ids`); the installation boundary is internal substrate (read `re-frame.realm` directly only when tooling truly needs it) |
| `rf/app-registrations` / `rf/app-requires` / `rf/app-owns` | inspect a frame's resolved image generation |
| `(realm, frame)` two-part address | a single frame target |

The EP-0013 realm-targeted **map-shaped** registrar queries (`(rf/registrations {:realm r …})`, etc.) were **removed from the public facade** — a registrar-query map is now ALWAYS frame-targeted (`(rf/registrations {:frame f :kind k})`), and a map without `:frame` is an error. Public registrar queries resolve through the target frame; a single-frame caller never spells a container. A realm-scoped read is internal-only (`re-frame.realm/realm-registrations`, for tooling that requires the ns directly).

## Routing — `day8/re-frame2-routing`

| Surface | Shape |
|---|---|
| `rf/match-url` | `(url)` → `{:route-id :params :query :fragment ...}` or `nil` |
| `rf/route-url` | `(route-id path-params)` / `(... query-params)` / `(... query-params fragment)` → `"/url"` — 4-arity appends `#fragment` (nil/empty omitted, percent-encoded) |

## HTTP — `day8/re-frame2-http`

Production fx surface: `re-frame.http-managed`. Test surfaces (canned-stub fxs + `with-managed-request-stubs` family): `re-frame.http-test-support` — the test machinery consolidates into one namespace; tests `:require` it explicitly.

| Surface | Shape |
|---|---|
| `rf/with-managed-request-stubs` | macro: `(stubs & body)` — needs `re-frame.http-test-support` in require closure |
| `rf/with-managed-request-stubs*` | fn: `(stubs thunk)` — needs `re-frame.http-test-support` |
| `http-test-support/install-managed-request-stubs!` / `uninstall-managed-request-stubs!` | per-call fx-overrides — **not** on the `rf/` façade; call through `re-frame.http-test-support` |
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

## Privacy / egress — `015 Data Classification` (see `cross-cutting/privacy-and-elision.md`)

Owner classifies / framework projects / sinks consume. Classification keys are *declarations*, not calls — they live in `reg-frame` / registration / schema maps:

| Surface | Shape |
|---|---|
| frame `:sensitive` / `:large` | `(reg-frame id {:sensitive {:app-db [[…]] :http {:headers […] :query-params […]}} :large {:app-db [[…]]}})` — durable app-db + frame-local HTTP carrier classification |
| frame `:observability` | `{:handled-events [{:sink <id> :rf.egress/profile :rf.egress/off-box-observability :opts {…}}] :errors [...]}` — production sink policy (fail-closed, frame-scoped) |
| registration `:sensitive` / `:large` | `{:sensitive [[:password]] :large [[:blob]]}` on `reg-event`/`reg-sub`/`reg-fx`/`reg-flow` — transient-payload paths; `[[]]` = whole shape |
| schema `:sensitive?` / `:large?` | `[:token {:sensitive? true} :string]` Malli prop — machine `:data-schema`, resource `:data-schema`/`:params-schema`, HTTP `:decode` (owner-local schema'd data only; NOT app-db) |
| `:rf.egress/output-sensitivity` | `:rf.egress/inherit` (default) \| `:rf.egress/sensitive` \| `:rf.egress/public` — derived-output declassification on a `reg-sub`/`reg-flow`; `:public` is an audited claim (Xray enumerates) |
| `rf/project-egress` | `(record-or-value opts)` — record-level boundary primitive; `opts` `{:rf.egress/profile <closed six-member enum> :frame … :path […]}`. Required before any off-box sink; fail-closed when no frame known |
| `rf/elide-wire-value` | `(value opts)` — low-level tree-shaped-value walker `project-egress` delegates to; advanced `:rf.size/include-sensitive?` / `:include-large?` / `:include-digests?` overrides |
| `rf/register-observability-sink!` | `(sink-id fn)` — register the concrete sink fn for a frame `:observability` sink id; fn receives an **already-projected** record (no sink-local redaction) |
| `rf/projected-record` | `(record)` — dev-only projected epoch/observation record read |
| `rf/register-event-listener!` / `rf/register-error-listener!` (+ `unregister-*`) | advanced low-level listener registries beneath frame `:observability` |

Six `:rf.egress/profile` values (closed enum): `:rf.egress/off-box-observability` · `off-box-tool` · `local-redacted` (local default) · `local-raw` (trusted-local opt-in) · `ssr-hydration` · `public-error`. **Retired (removed from the public façade, EP-0015):** `add-marks` / `set-marks` → frame `:sensitive {:app-db …}`; `redact-interceptor` → registration `:sensitive`; `declare-sensitive-header!` / `declare-sensitive-query-param!` → frame `:sensitive {:http …}`.

## Trace and epoch — `day8/re-frame2-epoch`

| Surface | Shape |
|---|---|
| `rf/register-listener!` / `rf/unregister-listener!` / `rf/emit-trace-event!` | trace plumbing |
| `rf/trace-buffer` / `rf/clear-trace-buffer!` | retain-N ring |
| `rf/epoch-history` | `(frame-id)` → `[epoch-records]` |
| `rf/restore-epoch!` | `(frame-id epoch-id)` → bool |
| `rf/register-epoch-listener!` / `rf/unregister-epoch-listener!` | per-drain-settle listener |
| `rf/replace-app-db!` | `(frame-id app-db)` → bool — app-db-only state injection (dev/pair-tool; renamed from `reset-frame-db!`) |
| `rf/reset-app-db!` | `(frame-id)` → bool — app-db → {}, runtime-db preserved |
| `rf/replace-runtime-db!` | `(frame-id runtime-db)` → bool — runtime-db-only write (privileged) |
| `rf/replace-frame-state!` | `(frame-id frame-state)` → bool — replace BOTH partitions atomically (full-frame install) |

## Interceptors, boot, introspection

| Surface | Shape |
|---|---|
| `rf/reg-interceptor` | `(id ?metadata descriptor)` — the **public** interceptor-authoring form (EP-0022); descriptor one of `{:before}` / `{:after}` / `{:before :after}` / `{:factory}`. Chains reference it by id; `->interceptor` is internal-only |
| interceptor context (inside `:before` / `:after`) | work the ctx map directly: `(get-in ctx [:coeffects k])` / `(get-in ctx [:effects k])` to read, `assoc-in` to write. The façade accessors `rf/get-coeffect` / `assoc-coeffect` / `get-effect` / `assoc-effect` are **removed** (no audience post-EP-0017/EP-0022) |
| `:rf.cofx/requires` (metadata) | declare a handler's coeffect dependencies on `reg-event`: `{:rf.cofx/requires [:rf/time-ms [:ui/local-theme "k"]]}`. The declared values arrive **flat** in the coeffects map under their ids. Declared-only delivery; uniformly available to every event handler (EP-0017 / EP-0018) |
| `:rf.cofx` (envelope field / dispatch opt) | flat `fact-name → value` map of recordable coeffects on every dispatch / reply envelope (EP-0017; renamed + flattened from EP-0010's `:rf.world/inputs`). `:rf/time-ms` is the framework's one built-in (recordable, provided, stamped at enqueue). Read durable time off it via `:rf.cofx/requires [:rf/time-ms]` → `(fn [{:keys [rf/time-ms]} ev] …)`. `inject-cofx` is **removed** (`:rf.error/inject-cofx-removed`) |
| `[:rf.interceptor/path path-vector]` | the **one** framework-standard interceptor ref (EP-0022; `:factory`), e.g. `[:rf.interceptor/path [:cart]]` — focuses `:db` on a slice, preserves the `identical?` no-op. No public `rf/path` constructor; no standard `unwrap` (use handler destructuring, or a project-registered `:app/unwrap`) |
| `rf/init!` | `(adapter-map)` — install adapter/runtime capabilities; creates no frame. No registry. |
| `rf/install-adapter!` / `rf/destroy-adapter!` / `rf/current-adapter` / `rf/current-adapter-spec` | low-level adapter ops; `current-adapter` → discriminator keyword, `current-adapter-spec` → spec map |
| `rf/clear-event` / `rf/clear-sub` / `rf/clear-fx` / `rf/clear-flow` / `rf/clear-sub-cache!` | targeted deregistration |
| `rf/configure!` | `(:epoch-history\|:trace-buffer\|:elision opts)` — runtime knobs (`:elision` opts `{:rf.size/threshold-bytes N}`) |
| `rf/registrations` / `rf/handler-meta` / `rf/handler-ids` | registrar reads |
| `rf/features` | `()` → map of every optional-feature keyword → `{:maven :require :spec :loaded?}`. Ships to production (not elided) |
| `rf/feature-loaded?` | `(feature)` → bool — is the optional feature's impl artefact on the classpath. Known: `:schemas` `:machines` `:routing` `:flows` `:http` `:ssr` `:epoch` |
| `rf/require-feature!` | `(feature)` → `true`, or throws `:rf.error/feature-not-loaded` carrying the exact Maven coord + require form. Self-explaining early guard before a feature-dependent path |
| `rf/frame-ids` / `rf/view` | registry reads |
| `rf/frame-meta` | `(frame-id)` → flat map: `:id` + preset-expansion (`:preset` `:fx-overrides` `:drain-depth` `:doc` `:tags` `:url-bound?` `:platform` `:on-create` `:on-destroy` `:sensitive` `:large` `:observability` …) + lifecycle (`:created-at` `:destroyed?` `:listeners`) — all top-level per Spec-Schemas `:rf/frame-meta`. **No `:on-error` recovery-policy slot** — recovery is framework-owned, that key was removed |
| `rf/sub-cache` (CLJS) / `rf/sub-topology` | dynamic / static sub graph reads |

Optional-artefact surfaces raise `:rf.error/<artefact>-artefact-missing` (registrations / writes) or degrade to `nil`/`[]`/`false` (read-only queries) when the artefact is absent.

---

*Derived from `implementation/core/src/re_frame/core.cljc` (the public surface) and the per-artefact source trees under `implementation/{machines,schemas,routing,http,ssr,epoch,flows}/` @ main `1ed174f`. Re-verify when new public surface lands. This is the public-surface glance, not an exhaustive export inventory — for the complete facade see `SKILL-REDIRECT.md` → Definitive API reference.*
