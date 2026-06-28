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
| `rf/reg-app-schema` | `(path {:schema schema :frame frame?})` — schema rides `:schema`, optional frame target rides `:frame`; boundary validation; needs `day8/re-frame2-schemas` |
| `rf/reg-machine` | `(id metadata? machine-spec)` — registration macro stays on the façade (call-site coord capture, no owned-ns macro form); needs `day8/re-frame2-machines`. The plain-fn runtime form `reg-machine*` is the owned-ns surface `re-frame.machines/reg-machine*`, **not** on the `rf/` façade |
| `rf/reg-flow` | `(flow-map opts?)` — needs `day8/re-frame2-flows` |
| `rf/reg-route` | `(id metadata-map path)` — path is the third positional arg; needs `day8/re-frame2-routing` |
| `rf/reg-error-projector` | `(id metadata? (fn [trace-event] public-error))` — needs `day8/re-frame2-ssr` |
| `rf/reg-http-interceptor` | `(id interceptor-map)` — `interceptor-map` carries `:before` / `:after` / `:frame` / metadata; needs `day8/re-frame2-http` |

The `reg-event` metadata-map is the one **superset** middle slot — reflection keys **and** a reserved `:interceptors` key carrying interceptor **references**: `(id {:doc ... :schema ... :interceptors [:auth/required [:rf.interceptor/path [:cart]]]} handler)`. There is no positional interceptor vector — `(reg-event :id [i1 i2] handler)` and `(reg-event :id {:doc ...} [i1 i2] handler)` are registration errors. A malformed metadata value is `:rf.error/reg-event-bad-interceptors`, and an inline interceptor value (not a ref) in the chain is `:rf.error/inline-interceptor-removed` (EP-0022). There is one public event registrar — no `reg-event-db` / `reg-event-fx` / `reg-event-ctx` (EP-0018): a db-only handler returns `{:db ...}`, and full-context work is a registered interceptor (`reg-interceptor`) referenced by id. Verified against `implementation/core/src/re_frame/events.cljc` `resolve-interceptors` and Spec 001 §Allowed forms of the middle slot. `reg-fx`/`reg-cofx`/`reg-error-projector` take a metadata-map only in that slot.

## Dispatch, subscribe, frames

| Surface | Shape |
|---|---|
| `rf/dispatch` | `(event)` / `(event opts)` — async queued; `opts` may carry `:rf.cofx` (EP-0017 recordable coeffects — a flat `fact-name → value` map; pin durable `:rf/time-ms` and other recordable facts; runtime stamps `:rf/time-ms` when omitted). There is no `:rf.world/inputs` opt — passing it is a hard error `:rf.error/world-inputs-renamed` |
| `rf/dispatch-sync` | `(event)` / `(event opts)` — drains to fixed point; same `:rf.cofx` opt |
| `rf/subscribe` | `(query-v)` / `(frame-id query-v)` → reaction |
| `rf/subscribe-once` | `(query-v)` — one-shot: materialise + deref + unsubscribe |
| `rf/unsubscribe` | `(query-v)` / `(frame-id query-v)` |
| `rf/compute-sub` | `(query-v db)` — pure; bypass cache (preferred in tests) |
| `rf/with-frame` | `(frame-id body)` — pin `body` to an existing frame (lexical scope) |
| `rf/with-new-frame` | `([sym expr] body)` — create+own+destroy a frame for `body` |
| `rf/frame-provider` | (CLJS) per-adapter React-context component, ONE component / TWO config shapes (EP-0024 amended). **SCOPE-only** `[frame-provider {:frame ...} & children]`: provides an already-existing frame id; creates / refreshes / destroys nothing; fails loud if absent. **ENSURE** `[frame-provider {:id ... :images ... :initial-events ...} & children]`: creates the frame if absent, reuses-no-reseed if present, provides its id to descendants; no destroy-on-unmount (same opts as `make-frame`). Shape selected by `:frame` vs `:id` |
| `rf/capture-frame` | `()` / `(frame-id)` → `{:frame :dispatch :dispatch-sync :subscribe}` — the one public carry primitive; frame api captured at creation, survives async |
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
| `re-frame.machines/dispatch-to-system` | `(system-id event)` / `(... frame-id)` — owned-ns surface, **not** on the `rf/` façade; the canonical action-side messaging surface is the reserved fx `[:rf.machine/dispatch-to-system system-id event]` |
| `re-frame.machines/machine-transition` | `(machine snapshot event)` → `[snapshot' fx]` pure — owned-ns surface, **not** on the `rf/` façade |
| `re-frame.machines/make-machine-handler` | `(machine)` → event-fx handler — owned-ns surface, **not** on the `rf/` façade |

## Images and frames (EP-0023, `re-frame.core`)

The public multi-frame model is `image -> frame -> event stream`: an image is the selected registration set a frame runs, a frame is the isolated execution context, the event stream is the program. A single-frame app never spells `image` — `reg-*` writes the default registration source and a frame resolves the implicit *default image* over it. Reach for explicit images only when the default process-wide set stops being the right boundary (two surfaces on one page, a tool beside its target, progressive doc examples, library packaging, isolated test/story frames).

| Surface | Shape |
|---|---|
| `rf/image` | `({:id … :select-ns {:include [<ns-glob> …] :exclude [<ns-glob> …]} :registrations {…}})` → **inert image value** (pure data, no registrar side effect). `:select-ns :include` selects by source-ns (`:rf.provenance/ns`); glob grammar `*`=one segment, `**`=zero-or-more; a zero-match include pattern fails image assembly; `:exclude` subtracts. Supplied to a frame via the `:images` vector — composition resolves by **image order** (the later image wins; assert on shadows via `rf/frame-shadows`). There are no `:include-ns` / `:exclude-ns` / `:replace` / `:replace-standard` / `:rf.image/requires` keys — passing them fails loud. |

`make-frame` is the **one** EP-0024 constructor — accepts image-selection (`:images`) AND record-config opts (`:id` / `:initial-events` / `:on-destroy` / …) in one call and returns the live frame **value** (read its id via `frame-value->id`; `dispatch` / `subscribe` / `destroy-frame!` take the value or its id). A frame-targeted `reload-images!` swaps a live frame's image generation while preserving its memory. Construct image *values* with `rf/image`; for a callable frame at the app root, `reg-frame` it and scope with `frame-provider {:frame …}` (see [`../fundamentals/frames.md`](../fundamentals/frames.md)); for a per-mount lifetime use `frame-provider {:id …}` (ensure) or explicit `make-frame` + `destroy-frame!`.

## Composition: `image → frame → event stream`

The public composition model is `image → frame → event stream` (`rf/image` + `rf/make-frame`); there is **no realm / app / module composition vocabulary** on the `re-frame.core` facade. A registrar-query map is ALWAYS frame-targeted (`(rf/registrations {:frame f :kind k})` — a map without `:frame` is an error). Frame isolation plus image assembly are the whole composition story — see [`../fundamentals/frames.md` §Frame isolation is the whole isolation story](../fundamentals/frames.md#frame-isolation-is-the-whole-isolation-story). There is no `rf/migration-map` / `rf/migration-explain` facade read — those names do not exist.

## Routing — `day8/re-frame2-routing`

| Surface | Shape |
|---|---|
| `re-frame.routing/match-url` | `(url)` → `{:route-id :params :query :fragment ...}` or `nil` — owned-ns surface, **not** on the `rf/` façade (the `reg-route` registration macro stays on `rf/`) |
| `re-frame.routing/route-url` | `(route-id path-params)` / `(... query-params)` / `(... query-params fragment)` → `"/url"` — 4-arity appends `#fragment` (nil/empty omitted, percent-encoded); owned-ns surface, **not** on the `rf/` façade |

## HTTP — `day8/re-frame2-http`

Production fx surface: `re-frame.http.managed`. Test surfaces (canned-stub fxs + `with-managed-request-stubs` family): `re-frame.http.test-support` — the test machinery consolidates into one namespace; tests `:require` it explicitly.

| Surface | Shape |
|---|---|
| `rf/with-managed-request-stubs` | macro: `(stubs & body)` — needs `re-frame.http.test-support` in require closure |
| `rf/with-managed-request-stubs*` | fn: `(stubs thunk)` — needs `re-frame.http.test-support` |
| `http-test-support/install-managed-request-stubs!` / `uninstall-managed-request-stubs!` | per-call fx-overrides — **not** on the `rf/` façade; call through `re-frame.http.test-support` |
| `rf/clear-http-interceptor` | `(id)` / `(frame id)` — production surface, `re-frame.http.managed` |

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
| `re-frame.schemas/app-schema-at` / `app-schemas` / `app-schemas-digest` | read-only schema queries — owned-ns surface, **not** on the `rf/` façade (the `reg-app-schema` registration macro stays on `rf/`) |
| `re-frame.schemas/set-schema-validator!` / `set-schema-explainer!` | swap-in non-Malli validator — owned-ns surface, **not** on the `rf/` façade |
| `rf/validate-at-boundary-interceptor` | production-side validation interceptor |

## Privacy / egress — `015 Data Classification` (see `cross-cutting/privacy-and-elision.md`)

Owner classifies / framework projects / sinks consume. Classification keys are *declarations*, not calls — they live in `reg-frame` / registration / schema maps:

| Surface | Shape |
|---|---|
| durable app-db classification effects | `{:db … :sensitive [[:auth :token]] :large [[:docs :csv]] :clear-sensitive [[…]] :clear-large [[…]]}` — returned by a handler alongside `:db`, applied at commit; the durable app-db route (NOT a frame annotation). Value-independent; malformed → fail-loud pre-commit |
| `:rf.http/managed` `:carriers` + frame `:observability` | `(reg-fx :rf.http/managed {:carriers {:headers […] :query-params […]}} h)` — app-specific HTTP carrier names (transient-payload case); `(reg-frame id {:observability {…}})` — production sink policy. (The frame has NO `:sensitive` / `:large` key — durable app-db is the effects above; HTTP carriers ride `:rf.http/managed`.) |
| frame `:observability` | `{:handled-events [{:sink <id> :rf.egress/profile :rf.egress/off-box-observability :opts {…}}] :errors [...]}` — production sink policy (fail-closed, frame-scoped) |
| registration `:sensitive` / `:large` | `{:sensitive [[:password]] :large [[:blob]]}` on `reg-event`/`reg-sub`/`reg-fx`/`reg-flow` — transient-payload paths; `[[]]` = whole shape |
| subsystem `:sensitive` / `:large` | `(reg-machine id {:sensitive [[:data :token]] …})` / `(reg-resource id {:sensitive [[:data :ssn]] …})` — projection-relative durable classification, lowered per instance (the machine/resource snapshot-egress route) |
| schema `:sensitive?` / `:large?` | `[:token {:sensitive? true} :string]` Malli prop — HTTP `:decode` body (transient) + validation-FAILURE-trace redaction only. NOT a route for durable app-db OR machine `:data` snapshot egress |
| `rf/project-egress` | `(record-or-value opts)` — record-level boundary primitive; `opts` `{:rf.egress/profile <closed six-member enum> :frame … :path […]}`. Required before any off-box sink; fail-closed when no frame known |
| `rf/elide-wire-value` | `(value opts)` — low-level tree-shaped-value walker `project-egress` delegates to; advanced `:rf.size/include-sensitive?` / `:include-large?` / `:include-digests?` overrides |
| `rf/register-observability-sink!` | `(sink-id fn)` — register the concrete sink fn for a frame `:observability` sink id; fn receives an **already-projected** record (no sink-local redaction) |
| `rf/projected-record` | `(record)` — dev-only projected epoch/observation record read |
| `rf/register-listener!` `:events` / `:errors` (+ `rf/unregister-listener!`) | advanced low-level listener registries beneath frame `:observability` |

Six `:rf.egress/profile` values (closed enum): `:rf.egress/off-box-observability` · `off-box-tool` · `local-redacted` (local default) · `local-raw` (trusted-local opt-in) · `ssr-hydration` · `public-error`. Classification is **fail-open** (an unclassified path ships raw; no propagation/taint). **Not part of the API (use instead):** `add-marks` / `set-marks` and the frame `:sensitive {:app-db …}` annotation → the `:sensitive` classification effect (alongside `:db`); `redact-interceptor` → registration `:sensitive`; `declare-sensitive-header!` / `declare-sensitive-query-param!` AND the frame `:sensitive {:http …}` block → the `:rf.http/managed` `reg-fx` registration's `:carriers` block; `:rf.egress/output-sensitivity` (propagation/declassification) → classify the output path directly. (A `reg-frame` carrying a `:sensitive` / `:large` key fails loud.)

## Trace and epoch — `day8/re-frame2-epoch`

| Surface | Shape |
|---|---|
| `rf/register-listener!` / `rf/unregister-listener!` / `rf/emit-trace-event!` | trace plumbing |
| `rf/trace-buffer` / `rf/clear-trace-buffer!` | retain-N ring |
| `rf/epoch-history` | `(frame-id)` → `[epoch-records]` |
| `rf/restore-epoch!` | `(frame-id epoch-id)` → bool |
| `rf/register-epoch-listener!` / `rf/unregister-epoch-listener!` | per-drain-settle listener |
| `rf/replace-app-db!` | `(frame-id app-db)` → bool — app-db-only state injection (dev/pair-tool) |
| `rf/reset-app-db!` | `(frame-id)` → bool — app-db → {}, runtime-db preserved |
| `rf/replace-runtime-db!` | `(frame-id runtime-db)` → bool — runtime-db-only write (privileged) |
| `rf/replace-frame-state!` | `(frame-id frame-state)` → bool — replace BOTH partitions atomically (full-frame install) |

## Interceptors, boot, introspection

| Surface | Shape |
|---|---|
| `rf/reg-interceptor` | `(id ?metadata descriptor)` — the **public** interceptor-authoring form (EP-0022); descriptor one of `{:before}` / `{:after}` / `{:before :after}` / `{:factory}`. Chains reference it by id; `->interceptor` is internal-only |
| interceptor context (inside `:before` / `:after`) | work the ctx map directly: `(get-in ctx [:coeffects k])` / `(get-in ctx [:effects k])` to read, `assoc-in` to write. There are no façade accessors — `rf/get-coeffect` / `assoc-coeffect` / `get-effect` / `assoc-effect` do not exist |
| `:rf.cofx/requires` (metadata) | declare a handler's coeffect dependencies on `reg-event`: `{:rf.cofx/requires [:rf/time-ms [:ui/local-theme "k"]]}`. The declared values arrive **flat** in the coeffects map under their ids. Declared-only delivery; uniformly available to every event handler (EP-0017 / EP-0018) |
| `:rf.cofx` (envelope field / dispatch opt) | flat `fact-name → value` map of recordable coeffects on every dispatch / reply envelope (EP-0017). `:rf/time-ms` is the framework's one built-in (recordable, provided, stamped at enqueue). Read durable time off it via `:rf.cofx/requires [:rf/time-ms]` → `(fn [{:keys [rf/time-ms]} ev] …)`. There is no `inject-cofx` (`:rf.error/inject-cofx-removed`) |
| `[:rf.interceptor/path path-vector]` | the **one** framework-standard interceptor ref (EP-0022; `:factory`), e.g. `[:rf.interceptor/path [:cart]]` — focuses `:db` on a slice, preserves the `identical?` no-op. No public `rf/path` constructor; no standard `unwrap` (use handler destructuring, or a project-registered `:app/unwrap`) |
| `rf/init!` | `(adapter-map)` — install adapter/runtime capabilities; creates no frame. No registry. |
| `rf/install-adapter!` / `rf/destroy-adapter!` / `rf/current-adapter` / `rf/current-adapter-spec` | low-level adapter ops; `current-adapter` → discriminator keyword, `current-adapter-spec` → spec map |
| `rf/clear-event` / `rf/clear-sub` / `rf/clear-fx` / `rf/clear-sub-cache!` | targeted deregistration. `clear-flow` is the owned-ns surface `re-frame.flows/clear-flow`, **not** on the `rf/` façade (the `reg-flow` registration macro stays on `rf/`) |
| `rf/configure!` | `(:epoch-history\|:trace-buffer\|:elision opts)` — runtime knobs (`:elision` opts `{:rf.size/threshold-bytes N}`) |
| `rf/registrations` / `rf/handler-meta` / `rf/handler-ids` | registrar reads |
| `rf/features` | `()` → map of every optional-feature keyword → `{:maven :require :spec :loaded?}`. Ships to production (not elided) |
| `rf/feature-loaded?` | `(feature)` → bool — is the optional feature's impl artefact on the classpath. Known: `:schemas` `:machines` `:routing` `:flows` `:http` `:ssr` `:epoch` |
| `rf/require-feature!` | `(feature)` → `true`, or throws `:rf.error/feature-not-loaded` carrying the exact Maven coord + require form. Self-explaining early guard before a feature-dependent path |
| `rf/frame-ids` / `rf/view` | registry reads |
| `rf/frame-meta` | `(frame-id)` → flat map: `:id` + preset-expansion (`:preset` `:fx-overrides` `:drain-depth` `:doc` `:tags` `:url-bound?` `:platform` `:initial-events` `:on-destroy` `:sensitive` `:large` `:observability` …) + lifecycle (`:created-at` `:destroyed?` `:listeners`) — all top-level per Spec-Schemas `:rf/frame-meta`. **No `:on-error` recovery-policy slot** — recovery is framework-owned |
| `rf/sub-cache` (CLJS) / `rf/sub-topology` | dynamic / static sub graph reads |

Optional-artefact surfaces raise `:rf.error/<artefact>-artefact-missing` (registrations / writes) or degrade to `nil`/`[]`/`false` (read-only queries) when the artefact is absent.

---

*Derived from `implementation/core/src/re_frame/core.cljc` (the public surface) and the per-artefact source trees under `implementation/{machines,schemas,routing,http,ssr,epoch,flows}/` @ main `1ed174f`. Re-verify when new public surface lands. This is the public-surface glance, not an exhaustive export inventory — for the complete facade see `SKILL-REDIRECT.md` → Definitive API reference.*
