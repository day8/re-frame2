# Conventions

> **Type:** Convention
> Locked runtime conventions that span the spec — reserved namespaces for framework-owned ids, reserved fx-ids and state-node keys, reserved app-db keys, and the feature-modularity id-prefix convention.

## Reserved namespaces (framework-owned)

re-frame2 reserves **one root keyword namespace** for framework-owned ids: `:rf/*`. Every framework runtime id — events, fx, cofx, app-db keys, trace operations, error categories, warnings, registrar mutations, machine lifecycle events, routing events, navigation fx, SSR advisories — lives under `:rf/*` or one of its sub-namespaces. User code MUST NOT register handlers, fx, subs, or frames under `:rf/*` — with one carve-out: `:rf/default` is an ordinary, un-privileged frame id an app or test MAY explicitly register (the runtime never synthesises it). Tooling and migration agents check for collisions.

The previous v1-and-early-v2 scheme used 14 separate top-level prefixes (`:registry/*`, `:machine/*`, `:route/*`, `:nav/*`, `:re-frame/*`, ...). That design grew by accretion as new Specs landed and is exactly the place-vs-name accumulation [Principles §Name over place](Principles.md#name-over-place) names. v2 collapses to one root with hierarchical sub-namespaces.

Every framework-emitted op-type is `:rf.<family>`, every `:operation` is `:rf.<family>/<op>`, and every top-level framework `:tags` key is namespaced under its domino family (`:rf.sub/*`, `:rf.view/*`, `:rf.fx/*`, `:rf.event/*`, `:rf.epoch/*`, `:rf.cofx/*`) or — for the cross-cutting correlation spine — under `:rf.trace/*`. The one carve-out is `:frame` (the universal per-event routing tag), which stays bare per [009 §Canonical per-frame routing key](009-Instrumentation.md#tags-is-the-open-ended-bag). The authoritative op-type / operation / tag-key tables live in [009 §`:op-type` vocabulary](009-Instrumentation.md#op-type-vocabulary) and [009 §`:tags` is the open-ended bag](009-Instrumentation.md#tags-is-the-open-ended-bag). This applies to the **trace-tag layer** (keys under a trace event's `:tags`): the epoch id is `:rf.epoch/id` and dispatch correlation is `:rf.trace/dispatch-id` on every trace event. The bare spellings `:epoch-id` / `:dispatch-id` / `:event-id` are the **record/projection layer** — the `:rf/epoch-record` fields and `group-by-event` / event-bundle output slots — a separate, internally-consistent vocabulary where bareness signals "projected record slot, not raw tag"; see [Spec-Schemas §`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) for the two-layer canonical statement.

<a id="the-two-regime-naming-rule--closed-local-grammar-stays-bare-cross-surface-policy-is-namespaced"></a>

### The two-regime naming rule — closed local grammar stays bare, cross-surface policy is namespaced

One discriminator decides whether a framework key is spelled bare or under a `:rf.<area>/*` namespace, and it governs every row of the reserved set below:

1. **Closed-local-grammar keys stay bare.** A key read *only* inside its own owning grammar — one that never travels beyond the surface that defines it — stays unqualified. It has no cross-surface identity to protect, so a framework namespace would add noise without adding meaning. Examples: `:raise` (a machine-internal self-event that never reaches `do-fx`, per [005 §Drain semantics](005-StateMachines.md#drain-semantics)); the closed transition-table state-node keys (`:on` / `:entry` / `:always` / …); the commit-plane classification effects and `reg-*` metadata keys (`:sensitive` / `:large`, per [015 §The keyword namespacing rule](015-Data-Classification.md#the-keyword-namespacing-rule)); ergonomic per-knob `configure` sub-keys local to one opts map (`:depth`, `:events-retained`, per [API.md §Opts-key naming rule](API.md#opts-key-naming-rule)).

2. **Cross-surface policy keys are namespaced.** A key that names a contract read in more than one place — a slot, discriminator value, or policy flag whose identity must stay stable across surfaces — lives under a reserved `:rf.<area>/*` sub-namespace so its cross-surface identity is grep-visible and collision-proof. Examples: `:rf.egress/profile` and its profile-value enum, `:rf.observe/*` record kinds, the `:rf.size/*` walker flags (read by `:elision`, by `elide-wire-value`, and by the MCP wire walker alike), and every surface-specific fx-id under `:rf.<surface>/*`.

The rule is **closed** — there is no third shape. A key that seems to want one (a dotted-but-unscoped `:launch/auto-open?`, an `:rf.configure/*` prefix) is evidence the key is doing two things and should be split. The inherited unqualified runtime primitives `:dispatch` / `:dispatch-later` are the one grandfathered exception to regime 2's "namespace anything cross-surface" reach: they are cross-surface fx-ids but keep re-frame's original bare spelling because they sit at the load-bearing centre of every event drain. This general rule is restated in surface-local form at [015 §The keyword namespacing rule](015-Data-Classification.md#the-keyword-namespacing-rule) (classification keys) and [API.md §Opts-key naming rule](API.md#opts-key-naming-rule) (`configure` sub-keys) — this is the hoisted canonical statement they instance.

### The single-root reserved set

| Sub-namespace | Used for | Spec |
|---|---|---|
| `:rf/*` | Pattern-level events emitted or consumed by the framework (e.g. `:rf/hydrate`, `:rf/server-init`, and the **framework-standard `:rf/set-db`** — the public app-db seeding event, registered in both the regular registrar and the EP-0023 image standard registry; replaces the whole app-db partition with one map argument, rides normal post-commit schema validation/rollback, and fails loud on a missing/`nil`/non-map argument with `:rf.error/set-db-bad-value`; re-registering it in app code is a reserved-id collision per [EP-0027 §`:rf/set-db`](../docs/EP/EP-0027-frame-initial-events.md)); pattern-level effect-map keys; reserved hiccup heads (`:rf/suspense-boundary` per [011 §Streaming SSR](011-SSR.md#streaming-ssr) — (a)); the **named-path-declaration slot key `:rf/path`** (the path slot of a named-path-declaration map, per [EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md) and [§The `:rf/path` algebra](#the-rfpath-algebra)). **`:rf/default`** sits in this `:rf/*` scheme but, per [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md), it is an **ordinary frame id with no framework privilege** — not auto-created by `init!`, not a resolution fallback, not inferred from a missing frame; it is merely a legal id a small app or migration may **explicitly** register and select (the runtime never synthesises it). **Note:** there is no reserved app-db root `:rf/runtime` — framework durable state lives in the **runtime-db** partition under the coeffect/effect key `:rf.db/runtime` (per [§Reserved partition keys](#reserved-partition-keys) and [§Reserved runtime-db keys](#reserved-runtime-db-keys)); a stray `:rf/runtime` root in app-db is a hard error in final form (per [§The legacy `:rf/runtime` root](#the-legacy-rfruntime-root-hard-error-in-final-form)). | 002 / 011 / 012 |
| `:rf.db/*` | The two-partition frame-state vocabulary — `:rf.db/runtime` (the framework-owned **runtime-db** partition, a coeffect/effect key and the runtime-db slot inside a frame-state projection) and `:rf.db/app` (the app-db slot inside a frame-state projection; the *event-context* spelling of app-db stays the inherited bare `:db`). Per [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract) and [§Reserved partition keys](#reserved-partition-keys). | 002 |
| `:rf.runtime/*` | Runtime-db **subsystem children** — `:rf.runtime/machines`, `:rf.runtime/routing`, `:rf.runtime/elision`, `:rf.runtime/ssr`, and (the post-v1 Resources artefact) `:rf.runtime/resources` + `:rf.runtime/work-ledger` + `:rf.runtime/mutations`. Each is a reserved sub-tree of the runtime-db partition. Per [§Reserved runtime-db keys](#reserved-runtime-db-keys). | 002 / 005 / 009 / 011 / 012 / 016 |
| `:rf.capability/*` | **Capability-map** key namespace — the explicit dependency-surface keys naming the runtime services available to a runtime (ordinary maps or records with documented functions and lifecycle). Reserved members: `:rf.capability/http` (HTTP execution), `:rf.capability/clock` (the clock), `:rf.capability/random` (randomness source), `:rf.capability/schemas` (schema validation), `:rf.capability/routes` (route integration), `:rf.capability/ssr` (SSR hooks), plus injected **test doubles** under the same segment. **Note:** there is no **image-declared** host-capability surface — no `:rf.image/requires`, no `make-frame :capabilities` map, and no `:rf.gen/requires` / `:rf.error/image-missing-capability` assembly check. This `:rf.capability/*` host-service vocabulary is a separate concern (the absence is scoped to the three image-capability keys, never the bare "capability" segment); model a host dependency through ordinary registration selection, frame configuration, or adapter setup. Reserved whether or not a port ships the capability layer — ports MUST NOT register the namespace for any other purpose. | Runtime-Subsystems / EP-0023 |
| `:rf.realm/*`, `:rf.module/*`, `:rf.app/*` | **Retired** — the EP-0013 realm / app-value / module construction-and-install vocabulary. The substrate that carried these namespaces (the realm installation container, the app value, the module value, and their `install!` / `reinstall!` / `realm` / `module` / `app` constructors) does not exist in the current model ([EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)). These namespaces are **not reserved or emitted by the current model** — they are documented historically only in [EP-0013](../docs/EP/EP-0013-app-values-and-runtime-realms.md). The public composition model is `image → frame → event stream`; declare registration sets and their capability requirements through the `:rf.image/*` namespace (below). Migration users: see the EP docs for the old shape and what replaced it. | EP-0013 (historical) |
| `:rf.provenance/*` | Registration **source-provenance** namespace ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Registration Source Store). The keys naming WHERE a registration descriptor came from, recorded on every `reg-*` descriptor so an image's `:select-ns` selector can choose registrations by their source. Reserved members: the **canonical source-namespace key `:rf.provenance/ns`** (a canonical STRING — the dot-separated Clojure namespace a registration was authored in; a production descriptor field, not optional debug metadata; equality is ordinary string equality; the implementation keeps one string per source namespace per source store, not one per descriptor); the **inline image-id key `:rf.provenance/image`** (the `:id` of the `rf/image` value that supplied an inline descriptor — inline descriptors do not enter the provenance source store and are selected because their containing image value was supplied) and the **inline source-coordinate key `:rf.provenance/inline`** (the `[<registrar-section> <id>]` tuple naming an inline descriptor's section/id so errors and cross-image shadows can name it). Selection is by the registration's SOURCE namespace (`:rf.provenance/ns`), never by the registration-id keyword namespace. Reserved whether or not a port ships the image layer — ports MUST NOT register the namespace for any other purpose; the member set is fixed-and-additive by Spec change. | EP-0023 |
| `:rf.image/*` | Image-**value** fact-key namespace ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Image / §Public API; [EP-0026](../docs/EP/EP-0026-image-api-simplification.md) §Image Keys) — the owner-qualified keys naming the **normalized internal** FACTS an `rf/image` value carries (NOT the authoring surface). Reserved members: the **image-id slot key `:rf.image/id`** (the image value's stable id — the shadow report names images by id, and image ids MUST be unique within an `:images` composition), the **glob-selection slots `:rf.image/include-ns` / `:rf.image/exclude-ns`** (the normalized `:select-ns :include` / `:exclude` glob-pattern vectors, each `[]` when none — the selector runs each against descriptor `:rf.provenance/ns`; `:exclude-ns` is the subtractive narrowing glob, applied to the glob-selected set only, never to inline `:registrations`), and the **inline-descriptor slot `:rf.image/inline`** (the vector of inline descriptors lowered from `:registrations`, `[]` when none — selected unconditionally because their image was supplied). The PUBLIC **source** keys `:id` / `:select-ns` / `:registrations` ([§The public `rf/image` source keys](#the-public-rfimage-source-keys) below) stay bare; `:select-ns` is the single `{:include [globs] :exclude [globs]}` selection map that normalizes to `:rf.image/include-ns` / `:rf.image/exclude-ns`. The keys `:include-ns` / `:exclude-ns` / `:replace` / `:replace-standard` / `:rf.image/requires` are NOT reserved members; a `rf/image` spec carrying one fails loud (`:rf.error/invalid-image`) with a migration diagnostic — `:select-ns` is the route for `:include-ns` / `:exclude-ns`, image-order layering + the shadow report take the place of `:replace`, protected standards take the place of `:replace-standard`, and there are no image-declared host capabilities (`:rf.image/requires` is gone). Read by the `rf/image` constructor and `rf/make-frame`. Reserved whether or not a port ships the image layer — ports MUST NOT register the namespace for any other purpose; the member set is fixed-and-additive by Spec change. | EP-0023 / EP-0026 |
| `:rf.standard/*` | Framework-**standard descriptor** replacement-policy namespace ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Image Patching And Overrides; [EP-0026](../docs/EP/EP-0026-image-api-simplification.md) §Framework Standard Registrations). The keys on a framework standard registration that govern whether the standard's OWNER may replace it. **EP-0026:** the public `:replace-standard` image opt-in is **RETIRED** — framework standards are **protected**: a public app image MUST NOT shadow one (an app descriptor colliding with a standard's `[kind id]` fails loud, `:rf.error/image-standard-replacement-forbidden`), because a standard encodes an execution invariant. These keys remain the predicate the standard OWNER's internal define/revise path reads (a public app-facing standard-replacement hook, if ever wanted, is a separate standards-track decision; EP-0026 does not add one). Reserved members: the **replaceability flag `:rf.standard/replaceable?`** (default `false` — a standard registration is non-replaceable unless it opts in) and the **conformance-requirement key `:rf.standard/requires-conformance`** (a set of named invariants a replacement MUST satisfy — e.g. the invariant-coupled standard `:rf.interceptor/path`, which the first version keeps non-replaceable outright). Reserved whether or not a port ships the image layer — ports MUST NOT register the namespace for any other purpose; the member set is fixed-and-additive by Spec change. | EP-0023 / EP-0026 |
| `:rf.frame/id` | The current frame's id, threaded as a coeffect on every event context (the runtime-context spelling; distinct from the public `:frame` dispatch/subscribe opt, which is unchanged). Per [002 §Event context threads both partitions](002-Frames.md#event-context-threads-both-partitions). | 002 |
| `:rf.frame/<gensym>` | Anonymous frame-identifier namespace, owned by `make-frame` (e.g. `:rf.frame/123` for a gensym'd frame id). Per [EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Id Spaces a frame id is unique within the **process-local live-frame registry** (distinct from registration ids, which are scoped by the resolved image generation and may be reused across images); a `make-frame` call with no `:id` is local-only and bypasses the registry (the caller holds the returned frame object directly). | 002 / EP-0023 |
| `:rf.frame/<operation>` | Frame-lifecycle trace-operation namespace, owned by the router and frame lifecycle (e.g. `:rf.frame/drain-interrupted`, `:rf.frame/destroyed`). | 002 / 009 |
| `:rf.frame/*` (object slots) | Live **frame-object** slot keys, owned by `make-frame` ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Frame). Reserved members: the **object marker `:rf.frame/object`** (a `true` value at this key means "this is a live frame OBJECT" — the discriminator a target-resolution site reads to tell a direct frame object from a frame-id keyword without guessing); the **generation reference `:rf.frame/generation`** (the resolved, sealed image generation the frame is running — the slot a re-`make-frame` reload swaps and the `.9` resolution seam reads, while every other frame slot continues unchanged); and the creation-input slots `:rf.frame/initial-events` (the recorded setup-event script, per [EP-0027](../docs/EP/EP-0027-frame-initial-events.md)) and `:rf.frame/adapter` (the host adapter binding/configuration). (There is no `:rf.frame/capabilities` slot — image-declared host capabilities are not part of the model, [EP-0026](../docs/EP/EP-0026-image-api-simplification.md).) These slots live on the live object only — they are NOT part of the serializable frame-state value (`:rf.db/app` / `:rf.db/runtime`), which must not contain host handles. Reserved whether or not a port ships the image layer — ports MUST NOT register the namespace for any other purpose; the member set is fixed-and-additive by Spec change. | EP-0023 |
| `:rf.gen/*` | Sealed-**generation** fact-key namespace ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Specification Summary / §Image; [EP-0026](../docs/EP/EP-0026-image-api-simplification.md) §Shadow Report / §Generation Provenance) — the keys naming the slots of a resolved image generation, the inert, immutable data structure a frame resolves registration lookups against (safe to share across frames). Reserved members: the **resolver `:rf.gen/resolver`** (the heart — a map from a `[kind id]` pair to exactly ONE descriptor, id-disjoint by `(kind, id)` after selection and image-order layering; `resolve-descriptor` is the read API); the **`:rf.gen/images`** slot (the vector of normalized image values the generation was assembled from, in `:images` order — the later image wins); the **`:rf.gen/kinds`** slot (the set of registrar kinds present in the resolver, for tools); and the **`:rf.gen/shadows`** slot (the cross-image **shadow report** — a flat `[{:registration [kind id] :image <defined-in> :shadowed-by <winner>} …]` list, one entry per cross-image shadow naming the loser image and the FINAL winner; read via the `:rf.gen/shadows` key of `rf/frame-generation` — [EP-0026](../docs/EP/EP-0026-image-api-simplification.md) §Shadow Report). There is no **`:rf.gen/requires`** slot ([EP-0026](../docs/EP/EP-0026-image-api-simplification.md)) — there is no required-capability set on a resolved generation. Per-descriptor layer facts (source namespace, owning image, tier) live on each resolved descriptor's `:rf.provenance/*`; a frame's full layer view is a recomputable projection of the resolver plus that metadata and is **not** a separate `:rf.gen/*` key (mirrors are projections). Reserved whether or not a port ships the image layer — ports MUST NOT register the namespace for any other purpose; the member set is fixed-and-additive by Spec change. | EP-0023 / EP-0026 |
| ~~`:rf.reload/*`~~ | **RETIRED (rf2-lxwpob).** Was the hot-reload **report** fact-key namespace ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Hot Reload) naming the slots of the report the retired `reload-images!` call returned (`:rf.reload/diff`, the `{:added #{[kind id] …} :changed #{…} :removed #{…} :retained #{…}}` `[kind id]`-delta set). Image hot-reload is now re-`make-frame`-ing an `:id`-bearing frame; the equivalent diff is a **plain, un-namespaced** `{:added :changed :removed :retained}` map — the `generation-diff` accessor's return value, over two `frame-generation` reads — with no wrapper key (the caller already holds the frame; there is no separate report to key). | EP-0023 |
| `:rf.registry/*` | Registrar mutation trace operations (`:rf.registry/handler-registered`, `:rf.registry/handler-cleared`, `:rf.registry/handler-replaced`) | 001 / 009 |
| `:rf.handler/*` | Handler **registration-metadata** namespace — the DEBUG-gated source-as-data slot on a handler's registry meta. Closed reserved member: the **handler form-source key `:rf.handler/source`** (the `pr-str` of the whole macro-captured `(reg-event …)` form, dev-only, CLJS-production-elided; read via `(rf/handler-meta …)`). This is the ONE canonical public source-as-data key — `reg-event` stamps it directly on the `:event` slot, and a machine guard/action DERIVES it under the same key from the enclosing machine spec (Spec 005). The machine spec's *internal* per-element slots `:source-code` / `:source-coords` (below) are NOT `:rf.handler/*` members — they are embedded intermediate spec data re-keyed to `:rf.handler/source` at the `handler-meta` boundary; `:source-coords` is additionally the general Spec 006 source-coord slot. Reserved whether or not a port ships form-source capture — ports MUST NOT register the namespace for any other purpose; the member set is fixed-and-additive by Spec change. Per [009 §`:rf.handler/source`](009-Instrumentation.md#rfhandlersource--debug-gated-handler-form-source-capture) and [§Reserved registration metadata](#reserved-registration-metadata-framework-owned). | 009 / 005 |
| `:rf.fx/*` | Effect-resolution advisories (`:rf.fx/skipped-on-platform`, `:rf.fx/override-applied`); reserved fx-ids in machine `:fx` (`:rf.fx/spawn-args`) | 002 / 009 |
| `:rf.cofx` (envelope field) + `:rf.cofx/*` | The **recordable-coeffect envelope field** `:rf.cofx` — the flat `fact-name → value` map of recordable coeffects on every dispatch / reply token ([EP-0017](../docs/EP/EP-0017-recordable-coeffects.md), [002 §Recordable coeffects](002-Frames.md#recordable-coeffects)) — and the `:rf.cofx/*` sub-namespace. Reserved `:rf.cofx/*` members: the **declaration key `:rf.cofx/requires`** (the registration-metadata vector of consumed coeffect ids, [001 §`:rf.cofx/requires`](001-Registration.md#rfcofxrequires--the-declaration-key)); cofx-resolution advisories that ride the error envelope but are not necessarily failures — `:rf.cofx/skipped-on-platform` (a `:warning` with `:recovery :skipped` when a registered cofx's `:platforms` set excludes the active platform; mirror of `:rf.fx/skipped-on-platform`); the success-path trace op `:rf.cofx/run` and its `:rf.cofx/id` / `:rf.cofx/value` / `:rf.cofx/arg` / `:rf.cofx/elapsed-ms` tags (`:rf.cofx/value` carries the produced value, redacted by marks; `:rf.cofx/arg` carries the requirement-arg of a parameterized `[id arg]` requirement, per [EP-0017](../docs/EP/EP-0017-recordable-coeffects.md)); and the slice-B generation trace op `:rf.cofx/generated` (reserved). The cofx error family (`:rf.error/unregistered-cofx`, `:rf.error/missing-required-cofx`, `:rf.error/cofx-value-invalid`, `:rf.error/cofx-name-collision`, `:rf.error/cofx-registration-invalid`, `:rf.error/cofx-request-invalid`, `:rf.error/inject-cofx-removed`) lives under `:rf.error/*`. (The retired *draft* dispatch opt `:rf.world/inputs` earns **no** dedicated error id — it rides the generic `:rf.warning/unknown-dispatch-opt` surface with a did-you-mean, per [§The tombstone rule](#the-tombstone-rule--dedicated-retired-name-error-ids-only-for-shipped-names).) Per [009 §Error namespace convention](009-Instrumentation.md#error-namespace-convention--six-prefix-shapes), [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server), and [§Recordable-coeffect fact naming](#recordable-coeffect-fact-naming-rfcofx) below. | 001 / 002 / 009 / 011 |
| `:rf.error/*` | Error trace operations (handler exception, sub exception, fx exception, etc.) | 009 |
| `:rf.warning/*` | Warning trace operations (e.g. `:rf.warning/plain-fn-under-non-default-frame-once`, `:rf.warning/resource-load-more-owner-ignored` — a `:rf.resource/load-more` given a non-route `:owner`: the owner is ignored (no durable lease attaches) and the page still appends, per [§No silent swallow](#no-silent-swallow--recognised-input-must-signal) and [016 §Causal event — load-more](016-Resources.md#causal-event--rfresourceload-more-r2)) | 009 |
| `:rf.machine/*` | Machine lifecycle and transition trace operations (`:rf.machine/transition`, `:rf.machine/snapshot-updated`); machine framework subs (`[:rf/machine <id>]`) | 005 |
| `:rf.machine.lifecycle/*`, `:rf.machine.timer/*`, `:rf.machine.event/*`, `:rf.machine.microstep/*`, `:rf.machine.history/*` | Sub-areas of machine traces (further hierarchy under `:rf.machine`). `:rf.machine.history/*` is the history-pseudo-state trace family — `:rf.machine.history/restored` (a transition resolved a `:type :history` pseudo-state and re-entry resolved the recorded-or-default configuration) and `:rf.machine.history/recorded` (a history-bearing compound was exited and its last-active configuration was written into the `:rf/history` snapshot slot); per [005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target) and [009 §History trace events](009-Instrumentation.md#history-trace-events-rfmachinehistory). | 005 / 009 |
| `:rf.route/*` | Framework routing events (`:rf.route/navigate`, `:rf.route/transitioned`, `:rf.route/handle-url-change`, `:rf.route/not-found`, `:rf.route/navigation-blocked`, `:rf.route/continue`, `:rf.route/cancel`); framework route subs (`[:rf.route/id]`, `[:rf.route/params]`, etc.); route trace operations | 012 |
| `:rf.nav/*` | Navigation fx ids (`:rf.nav/push-url`, `:rf.nav/replace-url`, `:rf.nav/scroll`, `:rf.nav/external`) | 012 |
| `:rf.ssr/*` | SSR-specific advisories (hydration mismatch, head mismatch, etc.) | 011 |
| `:rf.server/*` | Server-side response-shape fx (`:rf.server/set-status`, `:rf.server/set-cookie`, `:rf.server/redirect`, `:rf.server/error-projection`) | 011 |
| `:rf.epoch/*` | Tool-Pair epoch operations | Tool-Pair |
| `:rf.xray/*` | Canonical-devtools namespace for Xray (per [Tool-Pair §Canonical devtools](Tool-Pair.md)) — events, subs, fxs, app-db keys, trace operations, AND boot-time configure! keys owned by the Xray devtool family. Framework-distance-zero alongside `:rf.epoch/*`; reserved sub-namespace for canonical devtools under the framework root. The framework reserves the segment; **Xray owns the member set** — the canonical config-key roster + per-key semantics live in [`tools/xray/spec/015-Configuration.md`](../tools/xray/spec/015-Configuration.md#configuration-keys) (an instance of the `:rf.<tool>/*` reserve-and-delegate convention in the next row, which Story also follows under `:rf.story/*` for its `configure!` surface — e.g. `:rf.story/global-args`, `:rf.story/global-decorators`). Third-party libraries MUST NOT register under `:rf.*` (per [§Library-owned prefixes](#library-owned-prefixes) below). | Tool-Pair |
| `:rf.<tool>/*` (convention) | The reserved-namespace pattern every canonical re-frame2 tool follows for its `configure!` boot-time surface — each tool owns its own `:rf.<tool>/*` sub-namespace under the framework root for events, subs, fxs, app-db keys, and config keys. Today: `:rf.xray/*` (Xray, this table row) and `:rf.story/*` (Story — its `configure!` surface uses `:rf.story/*` keys, e.g. `:rf.story/global-args`). The pattern is **prescriptive for canonical devtools** ([Tool-Pair §Canonical devtools](Tool-Pair.md)) — third-party libraries MUST NOT reserve under `:rf.*` (they own their own top-level prefix per [§Library-owned prefixes](#library-owned-prefixes)). Tool boot-time `configure!` keys NEVER use bare names (`:editor`, `:auto-open?`) or dotted-but-unscoped names (`:launch/auto-open?`, `:experimental/static-mode?`); the `:rf.<tool>/*` namespace is the collision protection, the greppability anchor, and the IDE auto-completion seed. | Conventions |
| `:rf.privacy/*` | Cross-tool privacy gates — config keys that more than one re-frame2 tool (Xray, Story, future tools) reads from the same atom. The canonical member is `:rf.privacy/show-sensitive?` (the trace-bus privacy gate per [Spec 009 §Privacy](009-Instrumentation.md#privacy--sensitive-data-in-traces)); set once by either tool's `configure!`, every tool's trace consumer honours it. The reservation is at the keyword-prefix level — future cross-tool privacy knobs (`:rf.privacy/include-large?`, etc.) land here without further coordination. Per [`spec/Privacy.md`](Privacy.md). | Privacy / Conventions |
| `:rf.assert/*` | Assertion-event vocabulary used by the optional `day8/re-frame2-story` library's play functions and test runner | 007 |
| `:rf.test/*` | Test-runner-internal events and fx-stub ids | 008 |
| `:rf.http/*` | Managed-HTTP fx ids (`:rf.http/managed`, `:rf.http/managed-abort`, `:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`); reply-payload `:kind` values for the closed eight-category failure taxonomy (`:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`, `:rf.http/aborted`); registration metadata key `:rf.http/decode-schemas`; trace operations (`:rf.http/retry-attempt`); the security args-map slot `:rf.http/max-decoded-keys` (per-request keyword-interning cap, — default 10000). Reserved whether or not the implementation ships Spec 014 — ports that omit `:rf.http/managed` MUST NOT register the namespace for any other purpose. | 014 |
| `:rf.http.interceptor/*` | Lifecycle trace operations for the per-frame `:before`/`:after` interceptor chain — request- and response-side (`:rf.http.interceptor/registered`, `:rf.http.interceptor/cleared`) per [014 §Middleware](014-HTTPRequests.md#middleware). | 014 |
| `:rf.size/*` | Size-elision wire markers and policy keys. Reserved members: the wire marker `:rf.size/large-elided` (per [Spec-Schemas §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker)); the per-call policy keys `:rf.size/elision-policy`, `:rf.size/threshold-bytes`, `:rf.size/include-large?`, `:rf.size/include-digests?`, `:rf.size/include-sensitive?` (default `false`; when `true`, sensitive values flow through the wire-elision walker without drop) consumed by `rf/elide-wire-value` (per [API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker)); the dev-mode warning category `:rf.warning/large-value-unschema'd` (catalogued in [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces)). Durable app-db large classification is nominated by the EP-0025 commit-plane `:large` / `:clear-large` effects (per [§Reserved commit-plane classification effects](#reserved-commit-plane-classification-effects)) into the per-frame `:rf.runtime/elision` registry; subsystem instance data via projection-relative declarations; the size backstop additionally auto-elides an oversized value even at an undeclared path. (Owner-local schema `:large?` props remain a nomination path for schema-owned *transient* data and validation-failure-trace elision, not for durable app-db paths — EP-0025.) Reserved whether or not the implementation ships the elision walker — ports that omit it MUST NOT register the namespace for any other purpose. Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). | 009 |
| `:rf.elision/*` | Sentinel-handle namespace for the `:rf.size/large-elided` marker's `:handle` slot — the EDN form `[:rf.elision/at <path>]` an agent passes to `get-path` to re-fetch an elided value. Reserved at the keyword (not segment) level: the only conformant tail is `at`. Per [Spec-Schemas §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker). | 009 |
| `:rf.egress/*` | Cross-surface egress-policy **projection-profile** vocabulary — the named boundary a value is being projected toward, sitting atop the boolean `:rf.size/*` override flags. Reserved members are the `:rf.egress/profile` slot key (carried on a frame `:observability` sink-entry per [002 §Frame configuration](002-Frames.md) and on a `project-egress` call) and its **closed six-member profile enum** (additions require a recorded ruling): `:rf.egress/off-box-observability` (hosted monitoring — Datadog / Sentry / Honeycomb), `:rf.egress/off-box-tool` (MCP / AI / tool wire), `:rf.egress/local-redacted` (local dev-UI default), `:rf.egress/local-raw` (trusted local operator), `:rf.egress/ssr-hydration` (the projection applied after the SSR allowlist — defence-in-depth, never a parallel SSR mechanism), and `:rf.egress/public-error` (client-safe server error projection). (There is no derived-sensitivity declassification key `:rf.egress/output-sensitivity` or its `:rf.egress/inherit` / `:rf.egress/sensitive` / `:rf.egress/public` value set — classification does not propagate, so there is nothing to declassify.) The keys are **flat under `:rf.egress/*`** — no sub-namespaces. Reserved whether or not the implementation ships the projection layer — ports that omit it MUST NOT register the namespace for any other purpose. Per [015 §Projection profiles](015-Data-Classification.md#projection-profiles--the-rfegress-enum-provisional) and [015 §No propagation, no taint](015-Data-Classification.md#no-propagation-no-taint). | 015 |
| `:rf.observe/*` | Observation-**record** kind vocabulary — the record `:kind` discriminator values `project-egress` dispatches on, the sibling of the `:rf.egress/*` projection-profile *values* in the row above. Closed reserved set of three members: `:rf.observe/handled-event` (one production-survivable record per dequeued event — `:frame` / `:event-id` / `:status` / `:elapsed-ms` / `:effects` / correlation ids; the off-box default omits the `:event` args slot entirely), `:rf.observe/error` (the error record — `:frame` / `:error` / `:event` / `:tags` / `:exception` / correlation ids, projected per the resolved profile), and `:rf.observe/derived-tree` (a rendered hiccup / resolved `:effective-args` / snapshot body, walked PATH-BASED through the frame's classification — EP-0025 removed the value-match dual, so a value re-keyed off its classified app-db path ships raw). These are the `:kind` values flowing on the third of the three observation streams — the bounded, projected, frame-`:observability`-routed **production observation stream** (distinct from the dev trace stream and the dev epoch stream). A kindless input is treated as a tree-shaped direct-read value and walked whole, NOT a reserved `:rf.observe/*` record. Owned by the `re-frame.projection` namespace's record projector. Reserved whether or not the implementation ships the projection layer — ports that omit it MUST NOT register the namespace for any other purpose. Per [015 §The three observation streams](015-Data-Classification.md#the-three-observation-streams) and [015 §The keyword namespacing rule](015-Data-Classification.md#the-keyword-namespacing-rule). | 015 |
| `:rf.mcp/*` | Cross-MCP wire-vocabulary markers emitted on the wire by the MCP servers (re-frame2-pair-mcp / story-mcp). Reserved members: `:rf.mcp/overflow` (cap-trip indicator, per [`tools/mcp-conformance/TOKEN-BUDGETS.md`](../tools/mcp-conformance/TOKEN-BUDGETS.md)); `:rf.mcp/summary` (lazy-summary slot); `:rf.mcp/diff-from` (diff-encode base reference); `:rf.mcp/dedup-table` (structural dedup table); `:rf.mcp/ref` (back-reference key paired with `:rf.mcp/dedup-table` — the integer id at each dedup site that points into the table, per re-frame2-pair-mcp `tools/dedup.cljs`); `:rf.mcp/cache-hit` (per-session cache marker); `:rf.mcp/cursor-stale` (cursor age-out `:reason`); `:rf.mcp/invalid-arg` (per-call arg-validation rejection wrapper); `:rf.mcp/result` (wire-fidelity typed result envelope — `:value` / `:nil` / `:eval-error` / `:unserializable` discriminator emitted by the runtime-side classifier for `eval-cljs` / `handler-meta`, per [Tool-Pair §Wire fidelity](Tool-Pair.md#wire-fidelity-typed-result-envelope-dispatch-consequence-arg-echovalidate)). Owned by the MCP servers (not part of the framework runtime vocabulary) but reserved cross-server so an agent that learns one marker shape sees it byte-identical across the servers. Canonical naming home: [`tools/mcp-conformance/NAMING.md` §Error-vocabulary alignment](../tools/mcp-conformance/NAMING.md#error-vocabulary-alignment); canonical key constants: [`tools/mcp-base/src/re_frame/mcp_base/vocab.cljc`](../tools/mcp-base/src/re_frame/mcp_base/vocab.cljc). Reserved whether or not the implementation ships the MCP servers — ports MUST NOT register the namespace for any other purpose. | Tool-Pair |
| `:rf.trace/*` | The trace-channel namespace — covers (1) the trace-channel control slots that ride on event-meta or on emitted trace events, (2) the cross-cutting **trace-correlation** tag keys stamped on every event inside a cascade, AND (3) the per-frame trace-ring retention knob `:rf.trace/events-retained`. Distinct from the `:rf.<prefix>/*` trace-operation namespaces (`:rf.frame/*`, `:rf.registry/*`, `:rf.machine/*`, …) which name `:operation` values, and from the domain tag-key namespaces (`:rf.sub/*`, `:rf.view/*`, `:rf.fx/*`, `:rf.event/*`, `:rf.epoch/*`) which name a single domino-family's `:tags` keys. **Trace-channel control slots:** `:rf.trace/no-emit?` (event-meta opt-out — when truthy on a dispatched event's meta, the handler's cascade emits no trace events; per [009 §Trace-emission opt-out](009-Instrumentation.md#trace-emission-opt-out-rftraceno-emit-event-meta)); `:rf.trace/trigger-handler` (optional top-level slot on a trace event naming the in-scope handler that produced it and carrying its registration-site `:source-coord`; per [009 §`:rf.trace/trigger-handler`](009-Instrumentation.md#rftracetrigger-handler--naming-the-in-scope-handler) and [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)); `:rf.trace/call-site` (optional top-level slot on a trace event carrying the compile-time invocation coord stamped by the dispatching macro; per [009 §`:rf.trace/call-site`](009-Instrumentation.md#rftracecall-site--naming-the-invocation-line) and [Spec-Schemas §`:rf/trace-event`](Spec-Schemas.md#rftrace-event)). **Trace-correlation `:tags` keys** (the cascade's correlation spine — stamped on sub-runs, renders, errors, every event regardless of domino family, so they have no single domain home): `:rf.trace/dispatch-id` (the per-cascade correlation id on every trace event in a cascade), `:rf.trace/parent-dispatch-id` (inter-cascade lineage on `:rf.event/dispatched` events), `:rf.trace/event-id` (the cascade run's id — read off `:sub/run` / `:view/render` attribution, epoch records, and the cascade aggregator), `:rf.trace/trace-id` (the dispatch envelope's correlation id), `:rf.trace/phase` (`:run-start` / `:run-end` / `:rollback`). **Trace-ring retention knob:** `:rf.trace/events-retained` (per-frame metadata key set on `reg-frame`, default 50; sets the number of retained slots — one slot per **event** / pipeline run — in the frame's per-frame trace ring per [009 §Per-frame trace rings](009-Instrumentation.md#per-frame-trace-rings-event-keyed-dev-only); also accepted on `(rf/configure! {:trace-buffer {:events-retained N}})` as the process-default for frames that did not set per-frame metadata; part of the `event-*` noun family per [§The `event-*` noun family](#the-event--noun-family)). This row is **framework-reserved and additive**: the trace-channel control slots, the correlation keys, and the retention knob are the three member groups today; further trace-channel-cross-cutting keys land here by extending this row in a Spec change. Reserved whether or not the implementation ships the call-site macro or the no-emit? meta — ports MUST NOT register the namespace for any other purpose. | 009 |
| `:rf.route.nav-token/*` | Navigation-token lifecycle trace operations. Closed reserved set of two members: `:rf.route.nav-token/allocated` (fresh nav-token cascade begins) and `:rf.route.nav-token/stale-suppressed` (async result carrying a now-superseded token; handler does NOT run). Per [012 §Trace events](012-Routing.md#trace-events) and [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue). | 012 / 009 |
| `:rf.adapter/*` | Substrate-adapter `:kind` discriminator values returned by `(rf/current-adapter)`. Canonical members: `:rf.adapter/reagent`, `:rf.adapter/reagent-slim`, `:rf.adapter/uix`, `:rf.adapter/helix`, `:rf.adapter/plain-atom`, `:rf.adapter/ssr`. Third-party adapters publish their own `:kind` values outside `:rf.adapter/*` — the reserved namespace prevents silent collision with framework-owned discriminators. Per [006 §Adapter introspection](006-ReactiveSubstrate.md#adapter-introspection). | 006 |
| `:rf.resource/*` | Resources artefact namespace ([016-Resources](016-Resources.md)) — the public resource events (`:rf.resource/ensure`, `:rf.resource/refetch`, `:rf.resource/invalidate-tags`, `:rf.resource/release-owner`, `:rf.resource/clear-scope`, `:rf.resource/remove`), the passive resource subs (`:rf.resource/state`, `:rf.resource/data`, `:rf.resource/status`, `:rf.resource/loading?`, `:rf.resource/fetching?`, `:rf.resource/stale?`, `:rf.resource/error`, `:rf.resource/refresh-error`, `:rf.resource/has-data?`, `:rf.resource/previous-data`), the focus/reconnect revalidation events (`:rf.resource/window-focused`, `:rf.resource/network-reconnected` — first public-beta gate), the named-scope-resolver registrar surface (`reg-resource-scope` / `clear-resource-scope`, the `:resource-scope` registrar kind — [001 §Registry model](001-Registration.md#registry-model--the-canonical-kind-keyword-set)) and the `{:from-db <id>}` resolver-reference form, the `:invalidates` descriptor-vector flag `:refetch-populated?` (the EP-0016 Rider 1 opt-in to same-mutation refetch of a key this mutation populated — [016 §Populate is an authoritative load](016-Resources.md#populate-is-an-authoritative-load)), and the `:rf.resource/*` trace-operation family (including the EP-0016 D3 `:rf.resource/scope-resolved` op — a named resolver evaluation). The framework-internal reply sub-namespace is `:rf.resource.internal/*` (`:rf.resource.internal/succeeded`, `:rf.resource.internal/failed`, `:rf.resource.internal/aborted`, `:rf.resource.internal/stale-fired`, `:rf.resource.internal/gc-fired`, `:rf.resource.internal/stale-suppressed`) — user code MUST NOT dispatch these. Reserved whether or not the implementation ships the Resources artefact — ports that omit it MUST NOT register the namespace for any other purpose. | 016 |
| `:rf.mutation/*` | Resources-artefact MUTATION namespace ([016-Resources §Deferred slices](016-Resources.md#deferred-slices) / [EP-0003 §Mutations](../docs/EP/EP-0003-resource-queries.md#mutations), the first public-beta gate) — the public mutation events (`:rf.mutation/execute`, which accepts the EP-0016 call-site `:reply-to` continuation target, and `:rf.mutation/clear`), the passive mutation-instance subs (`:rf.mutation/state`, `:rf.mutation/status`, `:rf.mutation/pending?`, `:rf.mutation/result`, `:rf.mutation/error`), and the `:rf.mutation/*` trace-operation family (`:rf.mutation/started`, `:rf.mutation/succeeded`, `:rf.mutation/failed`, `:rf.mutation/cleared`, `:rf.mutation/stale-suppressed`, `:rf.mutation/replied` — the EP-0016 D1 call-site `:reply-to` continuation-dispatch trace). The framework-internal reply sub-namespace is `:rf.mutation.internal/*` (`:rf.mutation.internal/succeeded`, `:rf.mutation.internal/failed`) — user code MUST NOT dispatch these. Mutation runtime state is keyed by a generated mutation **instance id** `[:rf.mutation/instance mutation-id generation]` (or the caller-supplied `:instance`); the mutation's work-ledger **work id is not a `:rf.work/mutation` head** — it is built under the resource head as `[:rf.work/resource [:rf.mutation instance-id] generation]` (`:work/kind :mutation`), so `:rf.mutation/instance` names the instance-state key, NOT a `:rf.work/*` head (one-name-per-fact). Reserved whether or not the implementation ships the Resources artefact. | 016 |
| `:rf.scope/*` | Resource cache-scope policy keywords ([016-Resources §Scope resolution](016-Resources.md#scope-resolution)). Closed reserved members: `:rf.scope/global` (the explicit, auditable global-scope claim), `:rf.scope/from-caller` (scope required from the use site), `:rf.scope/same` (the EP-0016 invalidation-descriptor / map-target scope meaning "the mutation's resolved execution scope" — the default when a descriptor or exact target omits `:scope`, and the meaning of the bare `:invalidates` tag-set shorthand; per [016 §Scoped invalidation descriptors](016-Resources.md#scoped-invalidation-descriptors-per-target)), and the scope-shape head keyword `:rf.scope/session` used in example session scopes (`[:rf.scope/session {…}]`). The **named-resolver reference form** is the map `{:from-db <resolver-id>}` (a `:from-db`-keyed map, NOT a `:rf.scope/*` keyword — it names a `reg-resource-scope` resolver resolved at use time against the frame db; per [016 §Resolver references](016-Resources.md#resolver-references--from-db-id)); the reserved-but-unshipped route/runtime source is `{:from-route …}` / the `[:runtime path]` input source ([EP-0014](../docs/EP/EP-0014-derivation-and-process-algebra.md)). There is **no silent `:rf.scope/global` default** — `:scope` is required at `reg-resource` (fail-closed). Reserved whether or not the implementation ships the Resources artefact. | 016 |
| `:rf.work/*` | Frame work-ledger work-id head keywords ([016-Resources §Frame work ledger](016-Resources.md#frame-work-ledger), [Managed-Effects §Work-id heads](Managed-Effects.md), [EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md)). The resource writer's work-id head is `:rf.work/resource` (the `[:rf.work/resource resource-key generation]` shape); the mutation writer reuses the same `:rf.work/resource` shape keyed by the mutation instance (the `[:rf.work/resource [:rf.mutation instance-id] generation]` form), `:work/kind :mutation`. EP-0011's uniform reply envelope landed three further heads, each with its own work-id tuple: `:rf.work/http` (`[:rf.work/http logical-id issuance attempt]` — managed HTTP, where `issuance` is the monotonic per-request-id re-issuance counter, Spec 014), `:rf.work/route` (`[:rf.work/route route-id nav-token loader-id]` — route loaders, Spec 012), and `:rf.work/machine` (`[:rf.work/machine actor-id work-bearing-path generation]` — machine async work, Spec 005). A fourth head, `:rf.work/timer` (`[:rf.work/timer logical-timer-id generation]`), exists **only** in the EP-0011 test-only managed-timer conformance probe; the public managed-timer surface and its `:rf.timer/*` reservation are deferred, so the test-only `:rf.work/timer` evidence does NOT imply a shipped public `:rf.timer/after`. The segment is named neutrally so later work-ledger writers (streams, spawned actors, the public timer surface) add their own `:rf.work/<kind>` head here. Reserved whether or not a port ships the Resources artefact. | 016 / 014 / 012 / 005 |
| `:rf.schema/*` | Schema / validation namespace. Closed reserved set of two members: `:rf.schema/violation` (hot-reload schema-mismatch warning — fires when a file-save re-evaluates `reg-app-schema` with a different schema for the same path and the live app-db value at that path no longer validates against the **new** schema; `:op-type :warning`, recovery `:logged-and-skipped`; per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) and [010 §Schema migration on hot-reload](010-Schemas.md#schema-migration-on-hot-reload)) and `:rf.schema/at-boundary` (the boundary-validation interceptor's `:id` — per [API.md §`validate-at-boundary-interceptor`](API.md#schemas) and [010 §Production builds](010-Schemas.md#production-builds)). There is no `:spec` per-`reg-*` metadata key, no `:rf.spec/*` trace namespace, and no bare `:spec/*` interceptor-id namespace; all are `:rf.schema/*` under the unified `schema` vocabulary. Migration: see [MIGRATION §M-54](../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema). | 009 / 010 |
| `:rf.path/*` | Path-algebra namespace ([EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md), [§The `:rf/path` algebra](#the-rfpath-algebra)). Closed reserved member at the segment level: the **template-parameter segment** data form `[:rf.path/param <name>]` — the canonical stored shape of a path-template variable (a 2-vector headed by `:rf.path/param` with a keyword name). This is the ONLY template-variable shape that appears in any stored or serialized path; the `'?name` quote-symbol spelling is declaration-boundary sugar normalized into it (one fact, one identity). Distinct from the path-slot declaration key **`:rf/path`** (under the bare `:rf/*` root, the named-path-declaration map's path slot — first row of this table's scheme). Reserved whether or not a port ships the named-declaration surface — ports MUST NOT register the namespace for any other purpose. | EP-0012 |
| `:rf.interceptor/*` | Framework-owned **standard interceptor reference** id namespace ([EP-0022](../docs/EP/EP-0022-registered-interceptors.md), [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar), [002 §Registered interceptors and the chain grammar](002-Frames.md#registered-interceptors-and-the-chain-grammar)). Interceptors are a first-class `:interceptor` registrar kind; application interceptor ids are application-owned (under app feature namespaces), and this namespace reserves the ids of **framework-registered** standard interceptors that event/frame `:interceptors` chains reference. The initial reserved member is **`:rf.interceptor/path`** — the one standard interceptor, the canonical `:factory` consumer referenced as `[:rf.interceptor/path <path-vector>]`, specified to preserve the frame-commit `identical?` no-op ([002 §Standard `:rf.interceptor/path`](002-Frames.md#standard-rfinterceptorpath)). Distinct from `:rf.http.interceptor/*` (the per-frame `:before`/`:after` HTTP middleware lifecycle traces — request- and response-side, row above). Reserved whether or not a port ships any standard interceptor beyond `path` — ports MUST NOT register the namespace for any other purpose; the member set is fixed-and-additive by Spec change. | EP-0022 / 001 / 002 |

### Error-id and warning-id grammar

Error and warning ids follow `:rf.error/<kebab-id>` and `:rf.warning/<kebab-id>` — a single-segment kebab-case category under the reserved sub-namespace. The `:rf.error/*` and `:rf.warning/*` table rows above reserve the namespaces; the per-category vocabulary (the closed set of `<category>` values, what each one means, and which trace `:operation` it maps to) is enumerated in [009 §Error namespace convention](009-Instrumentation.md#error-namespace-convention--six-prefix-shapes). The same `:rf.<prefix>/<category>` shape applies to `:rf.fx/*` advisories, `:rf.ssr/*` advisories, and `:rf.epoch/*` operations — Conventions reserves the prefixes; 009 owns the per-prefix grammar.

**Registration-shape category grammar**: a registration-time authoring mistake — a caller handed a `reg-*` (or registration-adjacent) surface a structurally-wrong argument (a mis-shaped metadata map, a malformed spec, a non-path path, a bad batch) — is spelled `<surface>-bad-<slot>`: the owning surface first, the `bad` adjective in the middle, the offending slot last (e.g. `route-bad-metadata`, `resource-bad-spec`, `mutation-bad-spec`, `app-schema-bad-metadata`, `app-schemas-bad-arg`, `reg-sub-bad-args`, `make-frame-bad-opts`, `machine-bad-schemas`, `flow-bad-marks`). This is the plurality pattern the catalogue already follows; it fixes the id's word order so a reader who learned one surface's registration-mistake id can guess the next surface's — and so a generator emitting error branches, conformance rows, or tests against the catalogue writes the predictable spelling. Prefer `bad` over `invalid` (one adjective, not two), and put the surface before the adjective (`<surface>-bad-<slot>`, not `bad-<surface>-<slot>` or `invalid-<surface>-<slot>`). Value-domain violations (a runtime value failing its schema) are NOT registration-shape errors and keep their own category names.

**Co-edit invariant**: every new `:rf.<area>/<category>` event added by a feature Spec MUST land as a row in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) in the same PR as the owning Spec change. The catalogue is closed-vocabulary; an entry referenced without a matching row there is a contract bug, not a deferred follow-up.

<a id="the-tombstone-rule--dedicated-retired-name-error-ids-only-for-shipped-names"></a>

### The tombstone rule — dedicated retired-name error ids only for shipped names

A **dedicated retired-name error id** (a bespoke `:rf.error/<x>-retired` / `:rf.error/<x>-renamed` / `:rf.error/<x>-removed` category whose sole job is to fire when a caller supplies a specific retired name) MAY exist **only for a name that shipped in a released artefact.** A name that only ever lived in the spec's own **drafts** — never released to consumers — earns **no** dedicated error id; it rides the generic closed-key surface for its shape (`:rf.warning/unknown-dispatch-opt` for a dispatch opt, `:rf.error/*-invalid-key` / the generic closed-map rejection for a registration key, EP-0026's generic retired-image-key rejection for an image key), with a **did-you-mean** appended to name the canonical replacement.

**Why the line is drawn at *shipped*.** A dedicated retirement error earns its always-on production detection path — a keyword check every conforming port must implement forever — because a name with **wide training-data exposure** (a real, shipped v1 API a stale generator may still emit) needs a fail-loud, named-replacement error to steer the author. A draft-only name has no such exposure: no consumer ever typed it, no generator was trained on it, so a bespoke always-on guard is a detection path for keywords its users cannot possibly produce. Accepting the stale-generator argument for *every* renamed draft name makes tombstone accumulation unbounded — each spec revision would leave a permanent catalogue row and a mandatory port detection path. Bounding dedicated tombstones to the **shipped** surface keeps the retirement-error vocabulary finite.

**The justified permanent tombstones** — `:rf.error/reg-event-db-removed`, `:rf.error/reg-event-fx-removed`, `:rf.error/reg-event-ctx-removed` (the removed / demoted v1 registration API, [EP-0018](../docs/EP/EP-0018-one-event-registration.md)) — are exactly the shipped-name case: real v1 API with wide training-data exposure. They keep their dedicated, always-on, named-replacement error ids. **The draft-only counter-examples** — the dispatch opts `:rf.world/inputs` (renamed to `:rf.cofx`, [EP-0017](../docs/EP/EP-0017-recordable-coeffects.md)) and `:dispatched-at` (retired, [EP-0010](../docs/EP/EP-0010-causal-world-inputs.md)) — named facts only ever spelled in the spec's drafts; under this rule they earn no dedicated id and ride the generic unknown-dispatch-opt warning with a did-you-mean (per [002 §The `:rf.cofx` envelope field](002-Frames.md#recordable-coeffects) + [002 §`:dispatched-at` is retired](002-Frames.md#dispatched-at-is-retired)). EP-0026's retired image keys show the same mechanism for draft-era registration-key renames: the generic closed-key error, no per-key id.

### v1 `:re-frame/*` namespace

The v1 framework prefix `:re-frame/*` is **not** a runtime-resolved alias in v2. The runtime does not coerce `:re-frame/<x>` to `:rf/<x>`; direct authoring of `:re-frame/*` ids does not resolve. The v1→v2 path is the mechanical rewrite owned by the migration agent (per [MIGRATION §M-20](../migration/from-re-frame-v1/README.md#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix)) — every `:re-frame/<x>` reference is rewritten to `:rf/<x>` (or to the per-rule replacement when the id names a v1 feature removed in v2) at migration time. The prefix is not reserved here.

### `re-frame.alpha` is dissolved

The v1 `re-frame.alpha` namespace is **not part of v2**. The generalised `reg`/`sub`/`reg-sub-lifecycle` surface — together with the built-in lifecycle policies `:safe`, `:no-cache`, `:reactive`, `:forever` and the query-map `:re-frame/q` shape — is removed. This is pre-v1 cleanup, not deprecation. The canonical surfaces are:

- **Per-kind registration macros**: `reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-flow`, `reg-route`, `reg-machine`, `reg-app-schema`, `reg-view`.
- **Vector-form subscribe**: `(rf/subscribe [::id arg])`.

The per-frame sub-cache uses a single disposal algorithm — synchronous ref-counting (dispose on derefer-count → 0) — per [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal). For one-shot or persistent-value edge cases that would have leaned on a specific lifecycle policy, file a bead naming the actual need rather than reaching for a removed API.

Migration entries: [MIGRATION §M-23](../migration/from-re-frame-v1/README.md#m-23-re-framealpha-is-removed).

### User-defined route ids

User-defined route ids are **not** namespaced under any framework prefix. Routes are user-facing names; pick a feature prefix per the [feature-modularity convention](#feature-modularity-prefix-convention) below — `:cart/show`, `:auth/login-page`, `:account.profile/show`. The framework's routing concerns (events that drive navigation, subs that read the route slice) live under `:rf.route/*`; user route ids share the user-feature namespace with their adjacent events and subs. This stops the framework prefix from leaking into app code and removes the `:route/*` ambiguity (was it a framework operation or a user route-id? — the v2 answer is unambiguously the latter has no `:route/*` prefix at all).

### Library-owned prefixes

A handful of canonical libraries reserve prefixes outside the framework `:rf/*` root. These prefixes are **library-owned** (canonical when the library is loaded), not **framework-reserved** (closed by Spec change). The distinction matters: framework-reserved names are fixed-and-additive in the table above; library-owned prefixes belong to the library's own surface and would only collide with user code that loads the library and ignores its convention.

| Library-owned prefix | Library | Used for | Spec |
|---|---|---|---|
| `:story.<...>` | `day8/re-frame2-story` (optional) | Story ids (`:story.auth.login-form`) and variant ids (`:story.auth.login-form/empty`) | [007](007-Stories.md) |
| `:Workspace.<...>` | `day8/re-frame2-story` (optional) | Workspace ids (`:Workspace.Auth/all-states`) | [007](007-Stories.md) |

Library-owned prefixes do **not** violate the single-root invariant on framework-reserved ids (the rule that framework names live under `:rf/*` only) — they are user-space names that the library claims by convention. The framework's own assertion-event vocabulary used by the stories library's play functions and test runner is `:rf.assert/*` (per the table above) and remains framework-reserved.

Library-owned prefixes live **outside** `:rf.*` (e.g., Story's `:story.*`, Workspace's `:Workspace.*`). The exception is **canonical devtools recognised in [`spec/Tool-Pair.md`](Tool-Pair.md)** — they reserve a sub-namespace **under** `:rf.*` (e.g., `:rf.xray/*` for Xray, `:rf.epoch/*` for the epoch surface). The reservation rides framework-distance-zero status: canonical devtools ship lockstep with the framework, their wire vocabulary is part of the framework contract, and a third-party library claiming `:rf.<x>/*` would silently collide with framework-owned discriminators. Third-party libraries MUST NOT reserve under `:rf.*`.

### Discipline

- **User-registered ids must not collide.** A user may not `(reg-event :rf/hydrate ...)` to override a framework event without going through the documented `:initial-events` / re-registration extension points. The linter rule is: `:rf/*` and any `:rf.X/*` sub-namespace is reserved (the explicit `:rf/default` frame registration is the one allowed exception). The rule applies regardless of the segment shape under the sub-namespace — a user registration of either `:rf.frame/<gensym>` (the identifier form) or `:rf.frame/<operation>` (the trace-operation form) is a collision; both rows above sit inside the same closed reserved set.
- **Library authors choose their own prefixes.** Third-party libraries SHOULD use their library name as a top-level segment (`:reagent/*`, `:re-pressed/*`). Avoid re-using `:rf/*`.
- **Trace-event `:operation` vocabulary is open by default.** A library may add its own `:my-lib.error/*` / `:my-lib.fx/*` prefix for advisories it emits — but the framework's reserved set is closed (additive only by Spec change).

The reserved set is **fixed-and-additive**: names already in the table cannot be repurposed; new sub-namespaces are added by extending the table in a Spec change. New Spec areas ship under `:rf.<spec-area>/*` rather than inventing a top-level prefix.

### The public `rf/image` source keys

`rf/image` is a plain function ([API.md](API.md)) that accepts a **source map** and returns an inert image **value**. The public source map accepts **exactly three** top-level keys ([EP-0026](../docs/EP/EP-0026-image-api-simplification.md) §Image Keys); the normalized value carries the owner-qualified `:rf.image/*` slots above. The source keys are the authoring surface; the `:rf.image/*` namespace names the normalized internal form.

| Source key | Required? | Meaning |
|---|---|---|
| `:id` | recommended | The image's stable id. The shadow report names images by id, and image ids MUST be **unique within an `:images` composition** (two images sharing an id in one composition is an error — `:rf.error/image-duplicate-image-id`). Anonymous images (no `:id`) are valid for local tests/examples that do not participate in composition; a synthesized id must still appear in provenance. |
| `:select-ns` | optional | A single **`{:include [globs] :exclude [globs]}`** map that **selects** existing namespace-authored registrations by their `:rf.provenance/ns` source namespace. `:include` is REQUIRED (a non-empty vector of namespace-glob strings); `:exclude` is optional (defaults to `[]`). The selected set is `union(:include) minus union(:exclude)`, with exclusion **global** to the image selection (a namespace matched by any `:exclude` is never selected — no re-admission). An `:include` pattern matching **no** loaded registration source namespace **fails loud** (`:rf.error/image-zero-match`); an `:exclude` pattern matching nothing is a no-op. `:select-ns` **selects, it does not load** — it filters registrations the runtime already knows about, so it never forces a require and never defeats DCE. Normalizes to `:rf.image/include-ns` / `:rf.image/exclude-ns`. An image with no `:select-ns` selects no namespace-authored registrations (it may still define inline `:registrations`). |
| `:registrations` | optional | Inline registrar-keyed sections that **define** new registrations image-locally — the image-level analogue of a namespace's `(def …)`. Lowered to `:rf.image/inline` descriptors, selected unconditionally (their image was supplied), never by `:select-ns`. Inline grammar is standardized for **exactly four kinds** — `:reg-event` / `:reg-sub` / `:reg-fx` / `:reg-cofx` — each lowering through that kind's own registrar parser (`:reg-sub` accepts only the layer-1 db-reader form `(fn [db query] …)`). Every other section key (`:reg-interceptor`, `:reg-view`, `:reg-frame`, `:reg-route`, `:reg-head`, `:reg-error-projector`, `:reg-flow`, `:reg-resource`, `:reg-mutation`, `:reg-resource-scope`) **fails loud** with an unsupported-inline-kind diagnostic — those kinds stay namespace-authored until their owning spec defines an inline lowering. |

An image may carry **both** `:select-ns` and `:registrations`, but the two must be **disjoint**: a `[kind id]` may not be both selected and defined inline in the same image. To override a selected registration, define the override in a **later** image and compose (see [002 §Image resolution and composition](002-Frames.md#image-resolution-and-composition)).

**Inline tuple grammar** ([EP-0026](../docs/EP/EP-0026-image-api-simplification.md) §Inline Registration Grammar): each entry is `[id body]` (metadata defaults to `{}`) or `[id metadata body]` (explicit metadata map). Every inline registration carries a body — a **metadata-only `[id metadata]` tuple is INVALID** (EP-0026 reverses EP-0023's metadata-only allowance) and fails loud, as do arities outside 2–3.

**Retired source keys** ([EP-0026](../docs/EP/EP-0026-image-api-simplification.md)): `:include-ns`, `:exclude-ns`, `:replace`, `:replace-standard`, and `:rf.image/requires` are removed from the public `rf/image` surface. A spec carrying any of them **fails loud** (`:rf.error/invalid-image`) with a migration diagnostic — they MUST NOT be accepted as aliases and MUST NOT be ignored. Migration: `:include-ns` / `:exclude-ns` → one `:select-ns {:include … :exclude …}`; `:replace` → put the winner in a **later** image and read the `:rf.gen/shadows` report on `rf/frame-generation` to assert on what it shadowed (there is no acknowledgement key); `:replace-standard` → removed (standards are protected); `:rf.image/requires` → removed end-to-end (model a host dependency through ordinary registration selection, frame configuration, or adapter setup). An **unknown** top-level source key (one outside `:id` / `:select-ns` / `:registrations` and not a recognized retired key) also fails loud.

### The naming rules (one name per fact)

The reserved-namespace scheme above fixes *where* a framework id lives; these
rules fix *that there is exactly one of it*. Together with the
attribute-shaped-name rule, they complete the project's naming discipline:
re-frame2 had naming *conventions* (reserved namespaces, attribute-shaped keys)
but no naming *rules* for synonyms, layers, and carriers, and the gap let
parallel spellings for single facts re-grow surface by surface. The rationale —
the review-cycle defect class these rules close, with worked instances — is the
[EP-0007 rationale record](../docs/EP/EP-0007-one-name-per-fact.md); this section
is the authoritative rule text.

The rule in one sentence: **every fact has one canonical name per layer; stable
APIs accept one spelling; where two layers legitimately use different words for
related concepts, the distinction is recorded as a named vocabulary rule, not
left as accident.** Spelled out:

1. **One canonical spelling per fact per layer.** A fact appearing in multiple
   places carries the same key everywhere *within its layer*. The frame id in
   *runtime context* is `:rf.frame/id` at every site that reads it; the runtime
   partition slot is `:rf.db/runtime` at every coeffect/effect site. A second
   spelling for the same fact inside one layer is a defect, not a convenience.
2. **No stable accepted synonyms.** A stable API accepts exactly **one**
   spelling. A retired or alternative spelling is a **hard error naming the
   canonical key**, never a silently-normalised alias — e.g. the redirect
   surface rejects `:url` / `:to` with
   [`:rf.error/redirect-retired-target-key`](011-SSR.md) rather than coercing
   them to `:location`. Temporary **migration aliases** are allowed only when an
   explicit bead/EP ruling names the alias, the canonical spelling, the
   diagnostic, and the sunset trigger; they are migration mechanics, never part
   of the stable contract. (This is the naming-axis counterpart of the v1
   `:re-frame/*` rule above — the runtime does not coerce a retired spelling to
   the canonical one; the migration agent rewrites it at migration time.)
3. **Cross-layer distinctions are named rules.** Where layers legitimately use
   different words for related concepts, the distinction is *recorded as a rule
   here*, so it reads as intent rather than inconsistency. The standing rules:
   - **Public-opt vs runtime-context spelling.** `:frame` is the public
     dispatch/subscribe opt and the universal per-event routing trace tag (the
     bare carve-out noted in [§Reserved namespaces](#reserved-namespaces-framework-owned)
     and [009 §`:tags` is the open-ended bag](009-Instrumentation.md#tags-is-the-open-ended-bag));
     `:rf.frame/id` is the *same stamp's* runtime-context coeffect spelling.
     Two layers, two different spellings for one fact (per
     [EP-0002 R3](../docs/EP/EP-0002-frame-target-resolution.md)).
   - **Partition slot vs subsystem child.** `:rf.db/*` names partition *slots*
     of frame-state (`:rf.db/app`, `:rf.db/runtime`); `:rf.runtime/*` names
     subsystem *children* inside the runtime partition (`:rf.runtime/machines`,
     `:rf.runtime/routing`, …). The different prefixes are this rule: each
     `:rf.runtime/*` child is globally greppable when detached
     from its parent slot. (The two rows are defined in
     [§The single-root reserved set](#the-single-root-reserved-set).)
   - **HTTP-response vocabulary vs navigation vocabulary.** Server
     response-shape surfaces use HTTP **header** vocabulary — `:location` for a
     redirect target (per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server));
     client **navigation** surfaces use `:url` (per [012-Routing](012-Routing.md)).
     Different concepts, different words — not synonyms for one
     fact.
   - **The async reply-target spelling is `:rf/reply-to` (never bare
     `:reply-to`).** The one direct continuation-target key for a managed
     async effect is `:rf/reply-to` — the routing `:rf.route/with-nav-token`
     wrapper, and any framework-internal direct reply target, spell it that
     way ([EP-0011](../docs/EP/EP-0011-uniform-async-reply-envelope.md) §Reply
     Target; [Managed-Effects §The uniform reply envelope](Managed-Effects.md#the-uniform-reply-envelope)).
     A bare `:reply-to` is **not** the framework spelling. Managed HTTP's
     `:on-success` / `:on-failure` are the two-target **routing sugar** over
     that one target — they do not reshape the reply (both receive the one
     [canonical reply envelope](Managed-Effects.md#the-uniform-reply-envelope)
     verbatim; the retired `{:kind :success/:failure}` HTTP dialect and its
     compat-reply reshape do not exist). The resources/mutations
     call-site `:reply-to` ([Spec 016](016-Resources.md)) is EP-0016's
     mutation-completion key and carries the same canonical reply map — a
     distinct surface, not a second async-reply dialect.
4. **One authoritative home per fact; mirrors are projections.** Denormalised
   copies — indexes, dual-homed owners, derived fields — are declared
   recomputable projections of the authoritative home, never co-equal sources,
   and a projection MUST NOT mint a new key for the same fact. The
   *state-ownership* half of this rule is
   [`spec/Runtime-Subsystems.md` §Derived rule 2 — one authoritative home per fact; mirrors are recomputable projections](Runtime-Subsystems.md#derived-rule-2--one-authoritative-home-per-fact-mirrors-are-recomputable-projections);
   stated here because it is a *naming* discipline too. Worked instance: the
   resource work-ledger keys one identity on `:rf.work/resource` (per
   [§The single-root reserved set](#the-single-root-reserved-set) and
   [016 §Frame work ledger](016-Resources.md#frame-work-ledger)); its
   denormalised fields are projections of that head, not a second identity.

**The `schema` family — a named cross-layer vocabulary (rule 3).** "Schema"
names four *different* validators across the registration surface. The word is
shared — every one of them is a Malli-shaped value-validator — but
each validates a different fact at a different layer, so the generic `:schema`
name is *qualified* wherever a visible sibling would make it ambiguous (e.g. a
machine's data validator is `:data-schema`, since beside the machine's other
keys a bare `:schema` could not say *which* fact it bounds). The four are recorded here as
one vocabulary rule, not four accidents:

| Validator | Spelled | Validates | Layer / owner |
|---|---|---|---|
| `reg-event` `:schema` | `:schema` (the `reg-event` metadata-map key) | the **event args** — the payload positions of the event vector | app-owned, per-handler ([§Reserved registration metadata](#reserved-registration-metadata-framework-owned), [API.md §`reg-event`](API.md)) |
| machine `:data-schema` | `:data-schema` (the `reg-machine` key) | a machine's **`:data`** context map | app-owned, per-machine. Qualified — not bare `:schema` — because a visible sibling (the transition table, the spawn spec) makes a generic `:schema` ambiguous about *which* fact it bounds. The rename is [EP-0005](../docs/EP/EP-0005-machine-data-schema.md); it is this rule's worked precedent |
| `reg-app-schema` | the registrar name itself (the validator *is* the registration) | **app-db paths** — a value at a `[:k …]` path in the app-db partition | app-owned, per-frame side-table ([010-Schemas §Per-frame schemas](010-Schemas.md#per-frame-schemas)). Validates app-db **only** (per [010 §App schemas validate the app-db partition only](010-Schemas.md#app-schemas-validate-the-app-db-partition-only)) |
| runtime-db schema | registered at boot as a runtime-db validator (NOT via `reg-app-schema`) | the **runtime-db partition** (`:rf.runtime/*` subsystem state, machine `:snapshots`, …) | **framework-owned** — registered by the runtime, refined per-machine from registered `:data` shapes; user code MUST NOT register against it ([§Reserved runtime-db keys](#reserved-runtime-db-keys)) |

The shared
`schema` word is *kept* across the four — the discipline is to qualify (rule 3)
where a sibling creates ambiguity, not to mint four unrelated words for one
concept (which rule 1 would forbid in reverse).

**Enforcement.** A retired spelling appearing in framework source is a CI
failure (the no-floor-lint treatment) where the shape allows it, not a doc note.
The new-surface review question — *"does this introduce a second spelling for an
existing fact?"* — belongs on the EP template and the implementor skill: a
second spelling is then a named violation, not a per-review judgment call. The
retired app/realm/module composition vocabulary ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) / [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md))
is enforced the same way on the public teaching surface:
`scripts/check_retired_composition_vocab.py` fails the docs build if a retired
construction/install/inspection symbol (e.g. `rf/install!`, `rf/realm`,
`installed-app`) reappears as live API in a fenced code block outside the narrow
historical allowlist. The exact term lists, the historical carve-out, and the
ordinary-English allowances are pinned below.

<a id="retired-composition-vocabulary--the-hard-rule"></a>

### Retired composition vocabulary — the hard rule

There is no realm / app-value / module **construction-and-install** model
([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) / [EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)).
The substrate that would carry it (the realm installation container, the app value,
the module value, and their constructors) **does not exist** — there is no
realm machinery to read. This is a **hard rule**, recorded
here so follow-up work does not re-legitimize the retired model: the only place
the retired vocabulary may appear is **historical discussion**, and only there.

**The live public model is `image → frame → event stream`.** Compose behaviour
by declaring an [`rf/image`](API.md) (a registration-source selection over
`:rf.provenance/*` source namespaces via `:select-ns`, plus any inline
`:registrations`), loading one or more into a frame (later image wins), and
processing that frame's events as an event stream.

**Preferred replacements** (the canonical live nouns — use these, never the
retired ones):

| Use this | …not this retired noun |
|---|---|
| **frame** | realm, operating realm, app-frame |
| **image** | app, app-value, module |
| **resolved image generation** (the sealed `:rf.gen/*` lookup table) | installed app, the realm's registry |
| **registration source** (`:rf.provenance/*`) | module, realm-routed registrations |
| **event stream** (a frame processes its events as a stream) | event program, realm-routing |
| **frame target** (a dispatch/subscribe addressed to a frame) | realm-targeted, `:realm` targeting |

**Disallowed as live architectural nouns / surfaces** (a CI failure where the
shape allows it — fenced code, emitted wire/tool output, or live teaching
prose): `realm`, `app-value`, `module`, `app-frame`, `operating realm`,
`program member`, `realm-routed`, `realm-targeted`, `:realm` targeting,
`:rf.realm/id` as **public or tool output**, `rf/realm`, `rf/app`, `rf/module`,
and the `install` / `reinstall` / `installed-app` family (`rf/install!`,
`rf/reinstall!`, `rf/dispose-realm!`, `rf/installed-app`, `rf/realm-ids`,
`rf/frame-realm`, `rf/app-registrations`, `rf/app-owns`, `rf/app-requires`).

**Ordinary, non-architectural English remains allowed** — these are words, not
the retired model: **application** (the thing a user builds), **app-db** (the
sanctioned existing partition term, [EP-0023:204](../docs/EP/EP-0023-image-loaded-frames.md)),
**programmer**, **pair-program**, and operating-system **program** / **process**
(an OS process, not an "event program").

**The historical carve-out is `docs/EP/**` only.** The EP design and
supersession docs **are** the retirement record — they carry worked examples of
the retired construction as their *subject*. A retired spelling is
legitimate **only** where the SUBJECT is the retirement itself, i.e. an `EP-*`
doc. Everywhere else — the spec, the guide, the API reference, the skills, the
migration guidance, the examples, the generated docs, the tests, and the repo
support files — must be clean. Removed-context *mention* in prose ("`rf/realm`
was retired") is always fine; the gate never scans prose, inline code spans, or
masked code-fence comments. What is forbidden is the retired model reappearing as
**live, copy-pasteable, recommended** vocabulary.

**Enforcement.** `scripts/check_retired_composition_vocab.py` (wired into
`.github/workflows/docs.yml`) is the guardrail. Its banned-term list and its
file allowlist track this rule exactly: the allowlist is the `docs/EP/**` prefix
plus the `EP-*.md` basename glob (EP docs that live under `spec/`), and nothing
else. A retired spelling reappearing as live API anywhere outside `docs/EP/**`
FAILS without the allowlist being touched; widening the allowlist to relegitimise
a non-EP surface is a rule violation, not a fix.

## Reserved fx-ids

re-frame2 reserves a small set of fx-ids — the runtime, the machine handler, and the navigation layer recognise them by name. User code MUST NOT register a `reg-fx` handler for these ids; doing so is a collision the registrar warns about.

The **Override tier** column records whether a `:fx-overrides` entry targeting the reserved id is honoured (**OVERRIDABLE**) or ignored (**REJECT**), per the state-installation criterion in [§Reserved fx-id override tiering](#reserved-fx-id-override-tiering) below.

| Reserved fx-id | Recognised by | Used for | Override tier | Spec |
|---|---|---|---|---|
| `:dispatch` | runtime `do-fx` | Standard intra-frame dispatch | OVERRIDABLE | 002 |
| `:dispatch-later` | runtime `do-fx` | Delayed dispatch | OVERRIDABLE | 002 |
| `:raise` | machine handler (`make-machine-handler`) | Self-event addressed to the same machine; processed atomically pre-commit. **Outside a machine action's `:fx`, `:raise` is unbound** and `:rf.error/no-such-handler` is the failure mode. | N/A (machine-internal; never reaches `do-fx`) | 005 |
| `:rf.machine/spawn` | `re-frame.machines` (canonical) | Spawn a dynamic actor; record its id into the parent's `:data` via `:on-spawn`. Registered globally so user event handlers (and machine actions) emit it from `:fx` to register a new live actor. Args per `:rf.fx/spawn-args`. | REJECT | 005 |
| `:rf.machine/destroy` | `re-frame.machines` (canonical) | Destroy a dynamic actor: runs the actor's `:exit` action, dissociates its snapshot at `[:rf.runtime/machines :snapshots <actor-id>]`, and clears its event handler from the frame-local registry. Symmetric counterpart to `:rf.machine/spawn`. Per [005 §`:raise`, `:rf.machine/spawn`, and `:rf.machine/destroy` are reserved fx-ids inside `:fx`](005-StateMachines.md#raise-rfmachinespawn-and-rfmachinedestroy-are-reserved-fx-ids-inside-fx). | REJECT | 005 |
| `:rf.machine/dispatch-to-system` | `re-frame.machines` (canonical) | A machine action sends a message to its spawned child actor addressed by `:system-id`. Args are the single 2-element pair `[<system-id> <event-vector>]`; resolves the binding in the emitting frame's `[:rf.runtime/machines :system-ids]` reverse index and dispatches the event to the bound actor (no-op when unbound). The fx counterpart to the `dispatch-to-system` fn (`re-frame.core`). Per [005 §Cross-machine messaging by name](005-StateMachines.md#cross-machine-messaging-by-name). | OVERRIDABLE (pure lookup-then-dispatch; no runtime-db write) | 005 |
| `:rf.fx/reg-flow` | runtime `do-fx` | Register a flow at runtime (per [013 §Dynamic toggle via fx](013-Flows.md#dynamic-toggle-via-fx)). Args: the 3-slot triple `[flow-id metadata derive-fn]`. | REJECT | 013 |
| `:rf.fx/clear-flow` | runtime `do-fx` | Clear a registered flow; `dissoc-in` on its `:output-path`. Args: a flow id. | REJECT | 013 |
| `:rf.nav/push-url` | `re-frame.routing` (canonical) | `pushState` for the URL. `:client` platform only. Per [012 §Effects (`reg-fx`)](012-Routing.md#effects-reg-fx). | OVERRIDABLE | 012 |
| `:rf.nav/replace-url` | `re-frame.routing` (canonical) | `replaceState` for the URL. `:client` platform only. Per [012 §Effects (`reg-fx`)](012-Routing.md#effects-reg-fx). | OVERRIDABLE | 012 |
| `:rf.nav/scroll` | `re-frame.routing` (canonical) | Apply a scroll strategy. Args `{:strategy :from :to :saved-pos :fragment}`. `:client` platform only. Per [012 §Scroll restoration](012-Routing.md#scroll-restoration). | OVERRIDABLE | 012 |
| `:rf.nav/capture-scroll` | `re-frame.routing` (canonical) | Capture current scroll position before leaving a route. `:client` platform only. Per [012 §Scroll restoration](012-Routing.md#scroll-restoration). | OVERRIDABLE | 012 |
| `:rf.route/with-nav-token` | `re-frame.routing` (canonical) | Threads `:nav-token` into a downstream dispatch for stale-result suppression. Universal platform. Per [012 §Navigation tokens — stale-result suppression](012-Routing.md#navigation-tokens--stale-result-suppression). | REJECT (dropping the nav-token silently defeats stale-result suppression) | 012 |

<a id="reserved-fx-id-override-tiering"></a>

### Reserved fx-id override tiering

A `:fx-overrides` map (per [002 §`:fx-overrides`](002-Frames.md#fx-overrides--replace-fx-handlers)) may target a reserved fx-id, and the reserved set is tiered against override by the **state-installation criterion**:

- A reserved fx is **OVERRIDABLE** when its body only *routes* dispatches or *touches host/browser state* WITHOUT writing the frame runtime-db. The override (fn-value or keyword-redirect) is honoured exactly as for a user fx-id — it pre-empts the reserved body. This is the legitimate test/story affordance (capture a dispatch without queueing it; no-op a navigation).
- A reserved fx is **REJECT** when its body *installs or clears* durable frame-internal runtime state that later framework behaviour depends on (machine snapshots, flow registry entries, the nav-token). An override is **ignored**: the runtime emits [`:rf.error/reserved-fx-override`](009-Instrumentation.md#error-event-catalogue) and runs the real reserved body. In production builds the effective override map is stripped of REJECT keys loudly before the fx walk, and the REJECT keys are excluded from cascade inheritance (a per-call override never propagates into a `[:dispatch …]` child).

Reject-all (refusing fn-value override of `:dispatch` / `:dispatch-later` too) is **not** the rule — that would reverse the standing ruling that those routing-primitive overrides pre-empt the reserved body, which the testing surface depends on.

### Fx-id namespacing rule — three reserved fx-id sub-namespaces

The reserved fx-ids above span **three sub-namespaces** under the `:rf/*` root, plus a small set of unqualified runtime ids (`:dispatch`, `:dispatch-later`, `:raise`). Each sub-namespace carries a different kind of fx; the rule names which is which so a generator scaffolding fx-emitting code picks the right prefix without scanning the whole table. Audit Finding 2.

| Sub-namespace | Carries | Rule |
|---|---|---|
| `:rf.fx/*` | **Generic framework fx-ids** — operations the framework provides for cross-cutting concerns that are not bound to a single feature surface. | An fx-id lives under `:rf.fx/*` when the operation is **framework-supplied for general use** and the fx semantics are not bound to a specific feature substrate (machines, routing, …). Members: `:rf.fx/reg-flow`, `:rf.fx/clear-flow`, and the spawn-args reserved key `:rf.fx/spawn-args` (an args-payload shape, not an fx-id itself). |
| `:rf.<surface>/*` | **Surface-specific fx-ids** — operations bound to a named feature artefact (machines, routing, HTTP, etc.). | An fx-id lives under `:rf.<surface>/*` when the operation is part of the named surface's runtime contract and a port that omits the surface MUST NOT register the namespace. Members: `:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.machine/dispatch-to-system` (machines); `:rf.route/with-nav-token` (routing); `:rf.http/managed`, `:rf.http/managed-abort`, etc. (HTTP). |
| `:rf.nav/*` | **Navigation-primitive fx-ids** — the small set of fx-ids that map directly onto host-platform navigation primitives (`pushState`, `replaceState`, scroll APIs). | An fx-id lives under `:rf.nav/*` when it is a **navigation primitive** — a thin wrapper over a browser/host navigation API that is invoked by the routing artefact but is not itself "route-aware" (no nav-token threading, no `:on-match` dispatch, no schema validation). The split from `:rf.route/*` is deliberate: `:rf.nav/*` fx-ids could be invoked by a non-routing app that wants the primitive without the routing slice; `:rf.route/*` fx-ids require the routing artefact's slice and tokens to be meaningful. Members: `:rf.nav/push-url`, `:rf.nav/replace-url`, `:rf.nav/scroll`, `:rf.nav/capture-scroll`. |

**Historical carve-out — `:rf.fx/reg-flow` / `:rf.fx/clear-flow`.** These two fx-ids look like surface-specific operations (they register / clear flows — clearly bound to the flows surface) yet they live under `:rf.fx/*` rather than `:rf.flow/*`. This is the **single principled exception**: the v1→v2 rename moved v1's unqualified `:reg-flow` / `:clear-flow` to `:rf.fx/reg-flow` / `:rf.fx/clear-flow` to align with the [Reserved namespaces](#reserved-namespaces-framework-owned) single-root rule; a second rename to `:rf.flow/reg-flow` / `:rf.flow/clear-flow` would be a back-to-back churn on the migration agent's mechanical rewrite (per [MIGRATION §M-20](../migration/from-re-frame-v1/README.md#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix)) for marginal naming-axis benefit. v1 holds the current placement; future surface-specific fx-id additions follow the `:rf.<surface>/*` rule above.

**Reserved fx-ids elsewhere in the framework follow the rule.** `:rf.http/managed` and family (per [014-HTTPRequests](014-HTTPRequests.md)) sit under `:rf.http/*` per the surface-specific rule. The bare unqualified set (`:dispatch`, `:dispatch-later`, `:raise`) is bare by the [two-regime naming rule](#the-two-regime-naming-rule--closed-local-grammar-stays-bare-cross-surface-policy-is-namespaced) above, not by migration cost — and for two distinct reasons. `:dispatch` / `:dispatch-later` are **inherited runtime primitives** at the load-bearing centre of every event drain; they carry re-frame's original unqualified spelling. `:raise` is bare because it is a **closed local-grammar key**: a machine-internal self-event addressed to the same machine, appended to that machine's pre-commit raise-queue and processed atomically — it never reaches `do-fx` at all (per [005 §Drain semantics](005-StateMachines.md#drain-semantics)), so it is a discriminator inside the machine's own grammar rather than a cross-surface fx-id. It is deliberately unqualified to match re-frame's existing bare reserved fx names, per [005 §Drain semantics](005-StateMachines.md#drain-semantics) — **not** a pre-namespace-consolidation carry-over (there was no `:raise` before v2's machines). All three are documented in their unqualified form in the table above.

**Spawn-spec keys.** Inside a `[:rf.machine/spawn <spec>]` entry, the spec map uses the following reserved keys (per [005 §Spawn-spec keys](005-StateMachines.md#spawn-spec-keys) and [Spec-Schemas §`:rf.fx/spawn-args`](Spec-Schemas.md#standard-fx-args-schemas)): `:machine-id`, `:definition`, `:id-prefix`, `:data`, `:on-spawn`, `:start`, `:fixed-actor-id` (explicit actor-address input — was the overloaded `:spawn-id`), `:system-id`. Two further keys are reserved for the runtime to stamp on declarative-`:spawn` spawns: `:rf/parent-id` (the parent machine's registration-id) and `:rf/invoke-id` (the declarative spawn invocation path — the absolute prefix-path of the `:spawn`-bearing state node; was `:rf/spawn-id`) — together these address the runtime spawn registry slot at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`. The spawned actor's snapshot lives at the runtime-managed `[:rf.runtime/machines :snapshots <gensym'd-id>]` — the spec does NOT carry a `:path` or `:collection` key. A **bare** spawn-spec key outside the reserved set is rejected at registration with `:rf.error/machine-unknown-spawn-key` (per [§No silent swallow](#no-silent-swallow--recognised-input-must-signal) — a misspelt `:machine` for `:machine-id` would leave the spawn under-specified, so it fails loud rather than being silently unused). User extensions MUST be **namespaced** — those pass (as do the runtime-stamped `:rf/parent-id` / `:rf/invoke-id`).

### Reserved state-node keys (machine transition tables)

Inside a transition-table state node, the following keys are reserved by the runtime — per [005 §Transition table grammar](005-StateMachines.md#transition-table-grammar) and [005 §Capability matrix](005-StateMachines.md#capability-matrix). User-defined keys MUST be namespaced to avoid colliding with the reserved set.

| Reserved state-node key | Used for | Capability axis | Spec |
|---|---|---|---|
| `:on` | Event-driven transition map | core (flat FSM) | 005 |
| `:entry` / `:exit` | Single-fn-or-id action on entering / leaving the state | core (flat FSM) | 005 |
| `:meta` | Tooling-visible metadata; e.g. `{:terminal? true}` | core (flat FSM) | 005 |
| `:states` | Nested compound states (when present, the state is a compound state) | `:fsm/hierarchical` | 005 |
| `:always` | Eventless transition slot — fires when guard becomes true | `:fsm/eventless-always` | 005 |
| `:after` | Delayed transition slot — fires after a time delay | `:fsm/delayed-after` | 005 |
| `:spawn` | Declarative actor-spawn-on-entry / destroy-on-exit (sugar over imperative `:rf.machine/spawn` / `:rf.machine/destroy`); see [005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn) | `:actor/declarative-spawn` | 005 |
| `:spawn-all` | Declarative spawn-and-join — N parallel `:spawn`s plus a closed two-member `:all` / `:any` join condition; see [005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) | `:actor/spawn-and-join` | 005 |

The reserved set is **fixed-and-additive**: existing reserved keys cannot be repurposed; new keys are added by Spec change. The table above is a **partial illustration** — the authoritative, complete bare-key vocabulary is the [Spec-Schemas §`:rf/state-node`](Spec-Schemas.md#rfstate-node) grammar (`:type` / `:initial` / `:states` / `:always` / `:after` / `:spawn` / `:spawn-all` / `:on-done` / `:tags` / `:final?` / `:timeout` / `:on-timeout` / `:choice` / `:schemas` / `:internal-events` / `:guards` / `:actions` / …). A **bare** key outside that vocabulary is rejected at registration with `:rf.error/machine-unknown-node-key` (per [§No silent swallow](#no-silent-swallow--recognised-input-must-signal) — a bare unknown key reads as a typo, never silently ignored; `:type :history` / `:type :choice` pseudo-states carry their own closed key-sets). User-defined keys MUST be **namespaced** (`:myapp/note`) — those are the open-map carve-out and pass untouched. The `:tags` slot is a strict `[:set :keyword]`: a non-set value is `:rf.error/machine-bad-tags` (no silent coercion).

The reserved set is **fixed-and-additive**: existing reserved fx-ids cannot be repurposed; new ones are added by Spec change. Library- and feature-owned fx ids should be namespaced (`:auth.login/issue-request`, `:my-lib.fx/store`) to avoid colliding with the reserved unqualified set.

<a id="reserved-registration-metadata-framework-owned"></a>

### Reserved registration metadata (framework-owned)

The metadata map accepted by the `reg-event-*` family (the optional middle slot, distinct from the positional `:interceptors` vector) carries a small set of reserved framework-namespaced keys. Most are **stamped by framework registration sites** and read by the runtime; user code MUST NOT colonise those. The one **app-authored** exception is `:rf.cofx/requires` (the coeffect-declaration key): the app writes it, the runtime reads it. The reserved namespace protects all of them from accidental collision.

| Reserved registration-meta key | Stamped by | Read by | Meaning | Spec |
|---|---|---|---|---|
| `:rf/framework-authority?` | framework subsystem registrars (the routing façade on every `reg-event`; the SSR façade on `:rf/hydrate`; the Resources artefact on every `:rf.resource/*` and `:rf.resource.internal/*` `reg-event`) | the runtime, when assembling the event context | Marks the handler as a legitimate **runtime-db writer** — one that may return a `:rf.db/runtime` effect without firing the `:rf.warning/app-handler-runtime-effect` dev diagnostic. The general minting mechanism per [002 §Minting framework-write authority](002-Frames.md#minting-framework-write-authority). Reserved **by convention**, NOT a capability gate: the effect applies either way. | 002 |
| `:rf/machine?` / `:rf/machine` | the machine registrar (`reg-machine`) | the runtime + `(rf/machines)` / machine tooling | Discriminate a machine event handler and carry its spec. `:rf/machine? true` **implies** `:rf/framework-authority?` (the runtime folds the implication into the authority check), so a machine handler mints runtime-db write authority without a separate key. | 005 |
| `:rf.cofx/requires` | the app (on `reg-event` and machine named guard/action entries) | the runtime (context assembly) + `handler-meta` / tooling | Declares the recordable / ambient coeffects a handler consumes — a vector of registered coeffect ids, `[id arg]` for parameterized suppliers ([EP-0017](../docs/EP/EP-0017-recordable-coeffects.md)). Unlike the other rows this is an **app-authored** declaration, not a framework stamp; it is reserved here because the runtime reads it to assemble the coeffects map (declared-only delivery). With the one event form (EP-0018) it lives uniformly on every event — no db-only handler exception. Schema [`:rf.cofx/requires`](Spec-Schemas.md#rfcofxrequires); contract [001 §`:rf.cofx/requires`](001-Registration.md#rfcofxrequires--the-declaration-key). | 001 / 002 |
| `:rf.handler/source` | the `reg-event` macro (auto-capture); a code-gen pipeline / user metadata-map may stamp it explicitly (override wins) | `handler-meta` / dev tooling (Xray Epoch panel, IDE inspectors, the re-frame2-pair MCP `handler-meta` tool) | The **source-as-data** slot: the `pr-str` of the whole `(reg-event …)` form the user wrote, so a tool renders the handler source inline without leaving the browser. **DEBUG-gated / dev-only**: two-layer CLJS-production elision DCEs both the literal source bytes and the keyword's reachability (JVM always-on; the `re-frame.debug=false` property flips the same gate). A machine guard/action carries the same key **DERIVED** from the enclosing machine spec's internal `:source-code` slot (Spec 005) — the internal `:source-code` / `:source-coords` machine slots are re-keyed to `:rf.handler/source` at the `handler-meta` boundary, they are not a second public spelling. Never lands in app-db / runtime-db / a frame-state snapshot — it lives only on registry meta. Per [009 §`:rf.handler/source`](009-Instrumentation.md#rfhandlersource--debug-gated-handler-form-source-capture) and [001 §Registration-metadata elision classification](001-Registration.md#registration-metadata-elision-classification). | 009 / 005 |

The reserved set is **fixed-and-additive**: existing keys cannot be repurposed; new ones are added by Spec change. Keys outside the reserved set are tolerated as open-map user metadata. Routing-shipped events that touch the route slice inherit `:rf/framework-authority?` by sitting in the routing façade; an application that legitimately needs to write runtime-db from its own handler may stamp `:rf/framework-authority? true` itself — but the convention is that ordinary app code reaches subsystem state through public framework subs and effects, not by writing the runtime-db partition directly.

**Production elision of registration metadata.** A registration-metadata key is **elidable** in production iff it has ZERO production runtime use AND zero production observability use — i.e. it is pure dev/authoring documentation. Two keys qualify: **`:doc`** (stripped from public `(rf/handler-meta …)` and DCE'd from the bundle under `:advanced` + `goog.DEBUG=false`), and the DEBUG-gated **`:rf.handler/source`** source-as-data slot (elided at *capture* — the macro emits `nil` and the registrar-side merge is gated — so it never enters production meta at all, a distinct mechanism from the `:doc` stored-then-stripped strip; verified by the elision probe). Every other standard key is **load-bearing** and MUST be retained — `:sensitive?` / `:large?` (redaction / egress projection), `:tags` / `:interceptors` / the resource-mutation runtime keys (runtime behaviour), `:schema` / `:data-schema` (the source the `:sensitive?` / `:large?` redaction declarations are *precomputed from* at registration — for the schema's own egress products, validation-failure trace + owner-local schema'd data; not durable app-db classification, which is the commit-plane effects per EP-0025 — plus dev introspection), `:rf.cofx/requires` (declared-only delivery is runtime behaviour), `:rf/id` + the handler fn. The normative elidable-vs-retained classification table — including the source-coord Policy A / Policy B split — lives at [001 §Registration-metadata elision classification](001-Registration.md#registration-metadata-elision-classification). Adding a key to the elidable set is a Spec change.

### Recordable-coeffect fact naming (`:rf.cofx`)

[EP-0017](../docs/EP/EP-0017-recordable-coeffects.md) lands the **recordable-coeffect** surface: the flat `:rf.cofx` envelope map ([002 §The `:rf.cofx` envelope field](002-Frames.md#the-rfcofx-envelope-field)), the graded `reg-cofx` registrar, and the `:rf.cofx/requires` declaration ([001 §Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded)). The naming conventions:

- **One fact per owner-qualified key.** Each leaf of `:rf.cofx` is a single registered coeffect id naming one fact: `:rf/*` for framework facts, the subsystem root for subsystem facts (`:rf.route/location`, …), and an application namespace for app facts. The map is **flat** — no grouping sub-maps (`{:uuid {…} :random {…}}` is gone); provenance lives in the registration (`handler-meta`, `:doc`, `:schema`), not in nesting. This is the one-name-per-fact rule (above) applied to coeffects: one fact, one id, one access path.
- **`rf.`-prefixed namespaces are reserved.** Application coeffect ids MUST NOT register under `:rf/*` or any `:rf.X/*` sub-namespace; app facts use the feature-modularity app namespaces (`:counter/delta`, `:checkout/idempotency-key`). This is an **owner-qualified-naming convention enforced by lint/tooling**, **not** a runtime registration-time guard: `reg-cofx` does **not** reject an `rf.`-prefixed id at registration, because the framework and its subsystems legitimately register many `:rf.*` coeffect ids and the registration site cannot structurally distinguish an app id from a framework/subsystem one. The deeper check is the recommended cofx lint ([EP-0017 §9 Reflection, trace, and tooling](../docs/EP/EP-0017-recordable-coeffects.md#9-reflection-trace-and-tooling)). (Distinct from the genuine registration-time collisions below: registering a coeffect id that collides with `:db` / `:event` or with another already-registered coeffect id **is** `:rf.error/cofx-name-collision`.)
- **`:rf/time-ms` is the framework's one provided registration.** It is the single built-in coeffect — `{:recordable? true :provided? true}` — stamped at enqueue on every dispatch and reply envelope ([002 §Envelope stamping](002-Frames.md#envelope-stamping-rftime-ms)). It is the canonical durable wall-clock fact; the framework ships **no** other standard coeffect (no `:rf/uuid`, `:rf/random`, … — apps own supplier semantics, the framework records/replays/enforces). It is also the always-safe-to-surface leaf under EP-0015 projection (every other leaf follows per-leaf projection / sensitivity rules).
- **`:db` and `:event` are the fold's arguments**, not coeffect ids — delivered to every handler regardless of declaration. Registering a coeffect id colliding with `:db` or `:event` is `:rf.error/cofx-name-collision`.

<a id="reserved-app-db-keys"></a>
<a id="rfruntime-sub-container-catalogue"></a>

## The two-partition frame contract

A frame owns **two durable state partitions**, committed coherently by one pipeline run:

1. **app-db** — the user-owned application-data partition. Exposed as the ordinary `:db` coeffect/effect. Holds nothing but app data; user code MUST NOT colonise it with framework bookkeeping.
2. **runtime-db** — the framework-owned durable runtime partition. Exposed (internally) as the reserved `:rf.db/runtime` coeffect/effect. Holds the machine / routing / elision / SSR subsystem state.

The two are held as **ONE physical frame-state container** with **app-db and runtime-db projection reactions** layered over it (per [002 §One physical container, two projections](002-Frames.md#one-physical-container-two-projection-reactions) and [006 §Frame-state container and partition projections](006-ReactiveSubstrate.md#frame-state-container-and-partition-projections)). A **frame-state** value is the coherent projection of both partitions:

```clojure
{:rf.db/app     <app-db>
 :rf.db/runtime <runtime-db>}
```

`:db` (the app handler key) and `:rf.db/app` (the frame-state slot) are two spellings of the same app-db value: handlers and ordinary `:db` effects always use the inherited bare `:db`; the qualified `:rf.db/app` exists only so a frame-state projection can name both partitions without overloading `:db`. The full normative contract — accessors, mutators, write authority, and the projection model — lives in [002 §The two-partition frame contract](002-Frames.md#the-two-partition-frame-contract).

### Reserved partition keys

| Key | Location | Owner | Meaning |
|---|---|---|---|
| `:db` | coeffects/effects | app | The app-db partition. Kept unqualified — inherited re-frame vocabulary. |
| `:event` | coeffects | framework/app | The event vector. Kept unqualified — inherited. |
| `:rf.db/runtime` | coeffects/effects | framework | The runtime-db partition. Reserved **by convention** (NOT a security boundary, per [002 §Write authority is by convention](002-Frames.md#write-authority-is-by-convention)): app code can technically emit it, but it is for framework/runtime extensions only. |
| `:rf.frame/id` | coeffects | framework | The current frame's id (the runtime-context spelling). |
| `:rf.db/app` | inside a frame-state value | framework | The app-db slot in a frame-state projection. |
| `:rf.db/runtime` | inside a frame-state value | framework | The runtime-db slot in a frame-state projection. |

The reserved set is **fixed-and-additive**: existing partition keys cannot be repurposed; new ones are added by Spec change.

<a id="reserved-commit-plane-classification-effects"></a>

### Reserved commit-plane classification effects

The closed top-level effect map a `reg-event` handler returns reserves **four further commit-plane effect keys** beyond `:db` / `:rf.db/runtime` / `:fx` — the EP-0025 data-classification effects. They are **commit-plane** effects, applied **WITH the `:db` write** at the commit step (a frame-state transform into the per-frame elision declaration registry, `[:rf.runtime/elision …]`) — **not** `do-fx`-dispatched fx-ids, so they do **not** belong to the [§Reserved fx-ids](#reserved-fx-ids) table (a different plane). User code MUST NOT use these top-level keys for any other meaning.

| Reserved effect key | Plane | Used for | Spec |
|---|---|---|---|
| `:sensitive` | commit-plane | Classify each `[path]` **sensitive** — durable app-db egress redaction (redaction sentinel). Value-independent; applied with the `:db` write. | 015 / 002 |
| `:large` | commit-plane | Classify each `[path]` **large** — durable app-db egress size-marker. Value-independent; applied with the `:db` write. | 015 / 002 |
| `:clear-sensitive` | commit-plane | Un-classify each `[path]` from the sensitive axis (independent of `:large`). | 015 / 002 |
| `:clear-large` | commit-plane | Un-classify each `[path]` from the large axis (independent of `:sensitive`). | 015 / 002 |

Each takes a **vector of `:rf/path` vectors** (`[[path] …]`); a malformed payload is rejected **fail-loud pre-commit** with [`:rf.error/classification-effect-shape`](009-Instrumentation.md#error-event-catalogue) (no `:db` commit). The two axes are independent and the writes are value-independent (classify a path before any value lands). The same `:sensitive` / `:large` names are reserved at the **registration layer** (transient payload + subsystem declarations, per [Spec-Schemas](Spec-Schemas.md)); these four are the **durable app-db** lowering. See [002 §Commit-plane data-classification effects](002-Frames.md#commit-plane-data-classification-effects-ep-0025) and [015 §Data Classification](015-Data-Classification.md). The reserved set is **fixed-and-additive**.

<a id="the-legacy-rfruntime-root-hard-error-in-final-form"></a>

### The legacy `:rf/runtime` root — hard error in final form

There is no reserved app-db root `:rf/runtime`. Framework durable state lives in the runtime-db partition, addressed by the `:rf.runtime/*` children below. A stray `:rf/runtime` root surviving in app-db — whether written by user code or carried over from v1-shaped code — is a **hard error**: a handler whose `:db` effect carries a top-level `:rf/runtime` key **throws** `:rf.error/legacy-runtime-root` (per [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)) at the event-commit boundary. There is no migration alias and no `:rf.warning/runtime-state-dropped` containment diagnostic: an ordinary `:db` return replaces only app-db and cannot touch runtime-db.

## Reserved runtime-db keys

Runtime-db is a map whose top-level children are **framework-owned subsystem sub-trees**, each qualified under `:rf.runtime/*`. The runtime owns them; user code MUST NOT write under them directly — it reaches subsystem state only through public framework subscriptions (`[:rf/machine <id>]`, `[:rf.route/*]`) and tool/full-frame APIs (per [002 §Subscriptions read the partition they belong to](002-Frames.md#subscriptions-read-the-partition-they-belong-to)). The reserved set is **fixed-and-additive**: existing children cannot be repurposed; new ones (e.g. the post-v1 `:rf.runtime/resources`) are added by Spec change.

This table is the **canonical home for the reserved `:rf.runtime/*` key set** — [Runtime-Subsystems.md](Runtime-Subsystems.md), which names the five-clause contract every one of these children satisfies, references this table for clause 1 (subtree) rather than duplicating it. Each child also carries a grading row there (and, for the resource trio, in [016 §Runtime-subsystem graduation](016-Resources.md#runtime-subsystem-graduation)).

| Reserved runtime-db key | Owner | Used for | Spec |
|---|---|---|---|
| `:rf.runtime/machines` | machine runtime | The machine runtime — `:snapshots`, `:system-ids`, `:spawned`, `:spawn-counter`. | 005 |
| `:rf.runtime/routing` | routing runtime | The routing runtime — `:current` route slice + `:pending-navigation`. (The nav-token / pending-nav **counters** and the saved scroll positions are host-side transient caches, **not** runtime-db — held outside the frame value so an epoch restore cannot rewind + recycle a token; see [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression) and [012 §Scroll restoration](012-Routing.md#scroll-restoration).) | 012 |
| `:rf.runtime/elision` | instrumentation | The wire-elision declaration registry — `:declarations`, `:sensitive-declarations`. | 009 |
| `:rf.runtime/ssr` | SSR | The SSR hydration metadata — `:hydration`. | 011 |
| `:rf.runtime/resources` | Resources artefact | The resource cache (post-v1 Resources artefact, [016-Resources](016-Resources.md)) — closed slot set `:entries` / `:tag-index` / `:owner-index`. Allocated lazily — absent until the first resource write. `:tag-index` / `:owner-index` are recomputable-from-`:entries` (rebuilt on restore/hydration, never trusted from the snapshot). | 016 |
| `:rf.runtime/work-ledger` | Resources artefact (resource + mutation writers) | The frame work ledger (post-v1 Resources artefact, [016-Resources](016-Resources.md)) — serializable in-flight work records keyed by `:work/id`. Named **neutrally**: its two landed writers are resources (`:work/kind :resource`) and mutations (`:work/kind :mutation`); later slices extend it to timers, streams, route loaders, spawned actors, and machine async work. Host handles (AbortControllers, timeout/poll handles, promises) live in side tables keyed by `[frame-id work-id]`, **not** runtime-db. | 016 |
| `:rf.runtime/mutations` | Resources artefact | The mutation-instance runtime (post-v1 Resources artefact, [016-Resources](016-Resources.md)) — serializable mutation **instance** rows keyed by mutation instance id. Allocated lazily — **absent until the app registers a mutation**. The in-flight attempt rides the neutral `:rf.runtime/work-ledger` (work-kind `:mutation`) rather than minting its own work subtree; host handles live in the shared `[frame-id work-id]` side tables. | 016 |

The full runtime-db value shape is pinned at [Spec-Schemas §`:rf/runtime-db`](Spec-Schemas.md#rfruntime-db). Each child is allocated lazily — absent until the first subsystem write — and per-frame isolation is automatic (each frame owns its own runtime-db). Locating framework runtime state in the **runtime-db partition** (rather than in the app-db root, where it was) is the named mechanism by which machine / routing / elision / ssr state inherits [000 §Frame state revertibility](000-Vision.md#frame-state-revertibility): runtime-db is part of the one frame-state container, so every subsystem's durable state walks back atomically with app-db on a frame revert.

### Runtime-db sub-container catalogue

The subsystem children and their per-frame absolute paths inside runtime-db (the four v1 subsystems, plus the post-v1 Resources-artefact trio — resources / work-ledger / mutations):

| Subsystem | Path (inside runtime-db) | Contents | Owning Spec |
|---|---|---|---|
| Machines | `[:rf.runtime/machines :snapshots]` | Map of `<machine-id> → :rf/machine-snapshot`. Each registered machine's snapshot lives at `[:rf.runtime/machines :snapshots <id>]`. | [005 §Where snapshots live](005-StateMachines.md#where-snapshots-live) |
| Machines | `[:rf.runtime/machines :system-ids]` | Per-frame reverse index for `:system-id` named-machine addressing — `{<system-id> → <gensym'd-machine-id>}`. A spawn whose args carry `:system-id` writes a slot here; destroy clears it. `(rf/machine-by-system-id sid)` reads against this slot. Allocated lazily — absent until the first system-id-bound spawn. | [005 §Named addressing via `:system-id`](005-StateMachines.md#named-addressing-via-system-id) |
| Machines | `[:rf.runtime/machines :spawned]` | Per-frame **declarative-`:spawn` / `:spawn-all` spawn registry** — `{<parent-machine-id> → {<invoke-id> → <slot>}}`, where `<invoke-id>` is the absolute prefix-path of the `:spawn`-bearing state node and `<slot>` is either a single `<gensym'd-spawned-id>` keyword (for `:spawn`) or a join-bookkeeping map (for `:spawn-all`). Allocated lazily — absent until the first declarative-`:spawn` / `:spawn-all` spawn. | [005 §Declarative `:spawn`](005-StateMachines.md#declarative-spawn) / [005 §Spawn-and-join via `:spawn-all`](005-StateMachines.md#spawn-and-join-via-spawn-all) |
| Machines | `[:rf.runtime/machines :spawn-counter]` | Per-frame **hand-emitted-spawn fallback counter** — `{<machine-id> <int>}`. The `:rf.machine/spawn` fx-handler's fallback allocator bumps this slot when a spawn's args carry no explicit actor-address input (`:fixed-actor-id`) and the gensym path must mint a fresh `<id-prefix>#<n>` id (per [005 §Spawn id format](005-StateMachines.md#spawn-id-format---keyword-resolved)). Declarative `:spawn` does NOT use this slot — its counter lives inside the parent's snapshot at `[:rf.runtime/machines :snapshots <parent-id> :rf/spawn-counter]`. Allocated lazily — absent until the first hand-emitted spawn. The two-tier split is pattern contract per [005 §Spawn-id allocator — counter location](005-StateMachines.md#spawn-id-allocator--counter-location). | [005 §Spawn-id allocator — counter location](005-StateMachines.md#spawn-id-allocator--counter-location) |
| Routing | `[:rf.runtime/routing :current]` | The current route slice (`:id` `:params` `:query` `:transition` `:error` `:fragment` `:nav-token`). Schema `:rf/route-slice`. | [012 §The route slice](012-Routing.md#the-rfroute-slice) |
| Routing | `[:rf.runtime/routing :pending-navigation]` | The pending-navigation slot, populated by the runtime when a `:can-leave` guard rejects a navigation; cleared by `:rf.route/continue` or `:rf.route/cancel`. Allocated lazily — absent until the first guard rejection. Schema `:rf/pending-navigation`. (Runtime-db so it stays subscribable + restores in local replay, but **SSR-stripped** off the hydration payload.) | [012 §Navigation blocking](012-Routing.md#navigation-blocking--pending-nav-protocol) |
| Elision | `[:rf.runtime/elision :declarations]` | The **large**-axis classification registry — `{<path-as-vector> → {:source <src> :hint <str-or-nil>}}` where `<src>` is `:effect` (EP-0025 commit-plane `:large` effect), `:machine` / `:resource` / `:route` (subsystem projection-relative declaration lowered per instance), or `:flow` (a flow output declaration). **App-db durable large classification is the commit-plane `:large` effect** (EP-0025 — schema `:large?` props no longer feed this registry for durable app-db); un-schema'd slots exceeding the threshold fire the dev-mode `:rf.warning/large-value-unschema'd` advisory, and the size backstop auto-elides an oversized value even at an undeclared path. The declaration *records* are runtime bookkeeping and live in runtime-db (not in an app-db path the app can accidentally replace) — so they walk back atomically with the frame on a revert. Consulted by the `rf/elide-wire-value` walker at every wire-boundary emit. | [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) / [015 §Durable app-db](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects) |
| Elision | `[:rf.runtime/elision :sensitive-declarations]` | The **sensitive**-axis sibling — `{<path-as-vector> → {:source <src> :hint <str-or-nil>}}`, populated by the EP-0025 commit-plane `:sensitive` effect (`:source :effect`), subsystem projection-relative declarations, and flow outputs (the SAME sources as the large axis; the two axes are independent and cleared independently). Consumed at egress by the path walker (durable app-db / runtime-db redaction). Schema-attached `:sensitive?` slot props are NOT a source for this registry (EP-0025 — they drive only validation-failure-trace redaction). | [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) / [015 §Durable app-db](015-Data-Classification.md#durable-app-db--the-four-commit-plane-effects) |
| SSR | `[:rf.runtime/ssr :hydration]` | The SSR hydration metadata block — `{:server-hash <8-char-hex-or-absent> :version <int-or-absent>}`. Written by the reference `:rf/hydrate` event handler from the payload's `:rf/render-hash` and `:rf/version`; consumed by `verify-hydration!` (reads `:server-hash` after the first client render) and by the `:rf.ssr/check-version` fx (reads `:version` for the version-compatibility check). Allocated lazily — absent on frames that never hydrated. User code MUST NOT write here; implementations overriding the reference `:rf/hydrate` handler MUST preserve the `:server-hash` write or pass `:server-hash` opt to `verify-hydration!`. | [011 §The :rf/hydrate event](011-SSR.md#the-rfhydrate-event) |
| Resources | `[:rf.runtime/resources :entries]` | The resource cache entries — `{<scoped-resource-key> <entry>}`, where `<scoped-resource-key>` is `[cache-scope resource-id canonical-params]` and `<entry>` stores `:status` / `:data` / `:error` / `:refresh-error` / timestamps / `:generation` / `:request-id` / `:tags` / `:active-owners` / `:current-work`. The durable cache fact (rides restore/SSR). Allocated lazily. | [016 §Cache home and write authority](016-Resources.md#cache-home-and-write-authority) |
| Resources | `[:rf.runtime/resources :tag-index]` | Reverse index `{<tag> #{<scoped-resource-key> …}}` for tag invalidation. **Recomputable-from-`:entries`** — rebuilt from the installed `:entries` on restore/hydration, never trusted from the serialized snapshot, and need not ride the durable wire. | [016 §Invalidation](016-Resources.md#invalidation) / [016 §Restore and replay](016-Resources.md#restore-and-replay) |
| Resources | `[:rf.runtime/resources :owner-index]` | Reverse index `{<owner> #{<scoped-resource-key> …}}` for owner-driven release/refetch decisions. **Recomputable-from-`:entries`** — same rebuild-not-trust rule as `:tag-index`. | [016 §Active owners and causes](016-Resources.md#active-owners-and-causes) / [016 §Restore and replay](016-Resources.md#restore-and-replay) |
| Work ledger | `[:rf.runtime/work-ledger]` | Serializable in-flight work records keyed by `:work/id` (`[:rf.work/resource resource-key generation]` for the resource writer) — `:work/kind` / `:work/frame` / `:resource/key` / `:generation` / `:transport` / `:status` / `:owners` / `:causes` / `:cancellable?` / `:started-at` / `:deadline-at`. Terminal rows are pruned (the ledger stays bounded); only non-terminal rows' summaries ride the hydration/epoch wire; restored non-terminal rows reconcile to dangling. Host handles are NOT here — they live in side tables keyed by `[frame-id work-id]`. Named neutrally for future multi-writer use. | [016 §Frame work ledger](016-Resources.md#frame-work-ledger) |
| Mutations | `[:rf.runtime/mutations]` | Serializable mutation **instance** rows keyed by mutation instance id — `:mutation/id` / `:instance/id` / `:status` (`:idle` / `:pending` / `:success` / `:error`) / `:result` / `:error` / `:scope` / `:params` / `:generation` / `:current-work` / `:started-at` / `:settled-at` / `:affected-keys` / `:patch-summary`. Keyed by instance id (not mutation id) so concurrent submissions of the same mutation never clobber each other's pending/result/error state. Allocated lazily by the optional Resources artefact; absent in an app that registers no mutations. The in-flight attempt rides the neutral `:rf.runtime/work-ledger` (work-kind `:mutation`); host handles live in side tables. | [016 §Deferred slices](016-Resources.md#deferred-slices) / [EP-0003 §Mutations](../docs/EP/EP-0003-resource-queries.md#mutations) |

User registrations and writes must avoid `:rf.runtime/*`. The migration agent flags any user-registered **app-db** schema or write under `[:rf.runtime/* …]` as a Type-B migration. Schema-bearing implementations (re-frame2 reference) register the runtime-db schema at boot as a **runtime-db** validator (NOT an app-db schema — per [010 §App schemas validate app-db only](010-Schemas.md#app-schemas-validate-the-app-db-partition-only)); per-machine refinements of `:snapshots` are composed from registered machines' `:data` shapes.

**The clobber footgun is eliminated structurally, not merely warned.** Under the old single-app-db model, an event handler returning a from-scratch `:db` effect (`{:db (build-fresh-db ...)}`) wholesale-replaced `app-db` and silently dropped the `:rf/runtime` root, killing every live machine snapshot. The two-partition contract removes the footgun at its root: an ordinary `:db` effect replaces **only app-db** — runtime-db is a separate partition the handler never holds, so a fresh-map return cannot touch it (per [002 §An ordinary `:db` return replaces only app-db](002-Frames.md#an-ordinary-db-return-replaces-only-app-db)). No preservation/merge code is needed; the partition does what the old `:rf.warning/runtime-state-dropped` warning only asked app authors to do by hand.

**A partition, not a root.** Framework state lives in a *separate partition*, not one reserved key inside app-db: app-db is a **pure application contract** an AI agent can read without framework noise (`reg-app-schema` describes app data and nothing else), `:db` means literally "the app's map", and each subsystem is a qualified `:rf.runtime/*` child that tools can dump or redact independently. There is no app-db `:rf/runtime` root.

### Reserved snapshot-internal keys (machine runtime)

A machine snapshot at `[:rf.runtime/machines :snapshots <id>]` is described in [005 §Snapshot shape](005-StateMachines.md#snapshot-shape) as `{:state :data :tags? :meta?}` — the user-facing contract. The runtime also stamps a closed set of `:rf/*` slots **inside the snapshot** (some at the snapshot root, some inside `:data`) to thread per-machine bookkeeping through pure transitions and the SSR-survivable persisted state. These slots are framework-owned: user code MUST NOT write under them; conformance fixtures that pin them MUST treat them as the runtime's by-product. The reserved set is **fixed-and-additive** — names already in this table cannot be repurposed; new keys are added by Spec change.

| Reserved snapshot-internal key | Location | Value shape | Read/write rules | Spec |
|---|---|---|---|---|
| `:rf/spawn-counter` | snapshot root | `{<id-prefix> <int>}` — per-spawned-id-prefix integer counter map | Written by the pure spawn-id allocator (`allocate-spawned-id`) on every declarative `:spawn` / `:spawn-all` so id sequencing is deterministic from the snapshot. (Hand-emitted `:rf.machine/spawn` fxs bypass this snapshot-internal counter and bump the parallel app-db-resident counter at `[:rf.runtime/machines :spawn-counter <machine-id>]` instead — see [005 §Spawn-id allocator — counter location](005-StateMachines.md#spawn-id-allocator--counter-location) for the two-tier split rationale.) `synthesise-initial-snapshot` stamps an empty map at registration. Hand-built fixture snapshots may omit the slot — `(fnil inc 0)` defaults absent slots to 0. Persists across `pr-str` / `read-string` round-trip. | 005, |
| `:rf/history` | snapshot root | `{<compound-decl-path> <recorded-config>}` — map keyed by compound declaration path (a `[:vector :keyword]`) to that compound's recorded configuration | Written by the runtime during a history-bearing compound's **exit cascade** (per [005 §History states](005-StateMachines.md#history-states-type-history--shallow--deep--default-target)). The recorded value is a `[:vector :keyword]` absolute LEAF PATH for a **deep**-history compound (`:deep? true`) or a single `:keyword` DIRECT CHILD for a **shallow**-history compound (the runtime cascades its `:initial` chain on restore). NOT a single config — a machine may own several history-bearing compounds, each recorded independently; under `:type :parallel` the keys are **region-qualified** (head segment is the region name) so per-region recordings never collide. Read-only for users; `synthesise-initial-snapshot` does **not** seed it — allocated lazily, absent until a history-bearing compound is first exited (a machine with no history pseudo-states never carries the slot). Vectors-and-keywords only — EDN-clean; persists across `pr-str` / `read-string` round-trip. A recorded path that the current (hot-reloaded) definition no longer declares is a *dangling recorded path* — on restore the runtime falls back to the pseudo-state's `:default-target` / the compound's `:initial` rather than entering it. | 005 |
| `:rf/machine-type` | snapshot root | `<machine-id>` keyword OR an inline-`:definition` spec map | Stamped by the spawn-fx onto a SPAWNED actor's snapshot (absent on singleton snapshots) so the actor's TYPE — and hence its handler — is recoverable purely from the revertible snapshot. A `:machine-id` spawn stores the registered TYPE keyword (the type outlives instances); an inline `:definition` spawn stores the spec map verbatim. The lazy resolver reads it on dispatch to re-materialise the actor's handler; the epoch-restore precondition reads it to admit a spawned-actor snapshot as a valid restore target. This is what makes a spawned actor's LIVENESS a pure function of the runtime-db partition (where the snapshot lives) — there is no per-instance handler registration holding liveness outside the frame value. Persists across `pr-str` / `read-string` round-trip. Per [005 §Liveness is derived from runtime-db](005-StateMachines.md#liveness-is-derived-from-runtime-db). | 005 |
| `:rf/bootstrap-pending?` | snapshot root | `true` (otherwise the slot is absent) | Stamped by `synthesise-initial-snapshot` (and by the spawn-fx for spawned actors) on the freshly-allocated snapshot. The first event addressed to the machine runs the initial-entry cascade, then clears the slot via `dissoc`. NEVER true on a snapshot that has already processed an event. The slot is purely a "first dispatch" marker — it survives `pr-str` / `read-string` so a snapshot persisted mid-bootstrap (the SSR boundary case) resumes correctly. | 005, |
| `:rf/after-epoch` | inside `:data` | `{<decl-path-vector> <non-negative int>}` | The wall-clock `:after`-timer epoch map for flat / compound machines, keyed per scheduling node (its declaring state path), per [005 §Delayed `:after` transitions](005-StateMachines.md#delayed-after-transitions) §Hierarchy interaction. `commit-snapshot` bumps ONLY the entries for nodes the transition exits / enters, so a still-active parent's entry — and its in-flight timer — survive a child-only sibling transition. A `:rf.machine.timer/after-elapsed` synthetic event carries `[delay-key epoch decl-path]`; the runtime fires the transition iff the scheduling node is still on the active path AND the carried epoch matches that node's current per-path entry. | 005, |
| `:rf/after-epoch-by-region` | inside `:data` | `{<region-name> {<decl-path-vector> <non-negative int>}}` | Per-region per-decl-path `:after`-timer epoch map for parallel-region machines, per [005 §Per-region `:always` / `:after` / `:spawn` scoping](005-StateMachines.md#per-region-always--after--spawn-scoping). Replaces `:rf/after-epoch` when the machine is `:type :parallel` — a sibling region's transition does not invalidate this region's in-flight timers via the shared `:data` slot. Each region carries its own per-node epoch map. | 005, |
| `:rf/self-id` | inside `:data` | `<spawned-machine-id>` keyword | Stamped by the spawn-fx on the spawned actor's initial `:data` so the actor knows its own address (e.g. for self-`:dispatch`, for the actor's body to read `(:rf/self-id data)`). Equal to the gensym'd id of the spawned actor; absent on singleton-machine snapshots. | 005 |
| `:rf/parent-id` | inside `:data` | `<parent-machine-id>` keyword | Stamped by the spawn-fx on a declarative-`:spawn` / `:spawn-all` spawned actor's initial `:data`. The finalize-cascade reads it to locate the parent's snapshot at `[:rf.runtime/machines :snapshots <parent-id>]` for the `:on-done` callback. Absent on hand-emitted (non-declarative) spawns. | 005 |
| `:rf/invoke-id` | inside `:data` | `<vector-of-keywords>` — the absolute prefix-path of the `:spawn`-bearing state node (the declarative spawn invocation path) | Stamped by the spawn-fx on a declarative-`:spawn` / `:spawn-all` spawned actor's initial `:data`. Together with `:rf/parent-id` it addresses the runtime spawn-registry slot at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`. This is the **invocation-path** identity (was `:rf/spawn-id`), distinct from the spawned actor's own instance address (`:rf/self-id`) and from the explicit actor-address INPUT on the InvokeSpec (`:fixed-actor-id`). | 005 |
| `:rf/spawn-all-id` | inside `:data` | `<vector-of-keywords>` — the `:spawn-all`-bearing state node's prefix-path | Stamped by the spawn-fx on each child of a `:spawn-all` spawn. The finalize-cascade uses it to locate the parent's join bookkeeping map at `[:rf.runtime/machines :spawned <parent-id> <invoke-all-id>]`. | 005 |
| `:rf/spawn-all-child-id` | inside `:data` | child-machine-id keyword (the `:id` of the child in the `:spawn-all` `:children` map) | Stamped alongside `:rf/spawn-all-id` so the finalize-cascade can mark exactly which child finished inside the parent's join bookkeeping. | 005 |
| `:rf/spawned` | inside `:data` (on the **SPAWNING / parent** machine) | `{<invoke-id> <spawned-id-or-children-map>}` — per-invoke map keyed by the `:spawn`-bearing state's absolute prefix-path. Value is the bare `<spawned-id>` keyword for a single `:spawn`, or a `{<child-id> <spawned-id>}` map for a `:spawn-all`. | Written by the **pure transition reducer** at declarative-`:spawn` / `:spawn-all` allocate-time, binding the assigned actor id(s) into the SPAWNING machine's own `:data` so an action can read the id of an actor it spawned and emit `[:rf.machine/destroy <id>]` — the re-frame2 spelling of XState v5's `spawn(...)`-into-`context` capture. The REVERSE direction of the child-lineage stamps `:rf/self-id` / `:rf/parent-id` / `:rf/invoke-id` above: those record the CHILD's own lineage on the CHILD; this records the CHILD's id on the PARENT, keyed by the SAME `<invoke-id>` the child carries under `:rf/invoke-id` (so `(get-in data [:rf/spawned invoke-id])` mirrors the runtime registry slot at `[:rf.runtime/machines :spawned <parent-id> <invoke-id>]`). Keyed by `<invoke-id>` (not a lossy single 'last-spawned' slot) so multi-spawn never clobbers. Always-written on a declarative spawn; absent on hand-emitted (non-declarative) spawns. For a **region-scoped** spawn the key is the in-region prefix-path (the region machine's own frame of reference), whereas the registry / child `:rf/invoke-id` carry the region-name-prefixed path. Persists across `pr-str` / `read-string` round-trip. | 005 |
| `:rf/snapshot-version` | inside `:meta` | `int` | Versioning slot for snapshot/definition compatibility checks. When a definition's transition shape changes incompatibly, the author bumps `:meta :rf/snapshot-version` on the definition; restore compares the snapshot's version against the definition's and emits `:rf.error/machine-snapshot-version-mismatch` (or, on the epoch-restore path, `:rf.epoch/restore-version-mismatch`) on disagreement. Per [005 §Snapshot shape](005-StateMachines.md#snapshot-shape) (invariant 4), [Spec-Schemas §`:rf/machine-snapshot`](Spec-Schemas.md#rfmachine-snapshot), and [Tool-Pair.md §Time-travel](Tool-Pair.md#time-travel). | 005 / Tool-Pair |

**Persistence posture.** Each row's transience is explicit in the "Read/write rules" column. The persisting slots (`:rf/spawn-counter`, `:rf/history`, `:rf/machine-type`, `:rf/after-epoch`, `:rf/after-epoch-by-region`, `:rf/self-id`, `:rf/parent-id`, `:rf/invoke-id`, `:rf/spawn-all-id`, `:rf/spawn-all-child-id`, `:rf/spawned`, `:rf/snapshot-version`) ride the snapshot across `pr-str` / `read-string` and through SSR hydration ([011](011-SSR.md)) and Tool-Pair epoch replay. `:rf/history` riding the (revertible) snapshot is what gives first-class history its restore-epoch! / SSR-hydration behaviour for free, with no parallel side-table. The persistence of `:rf/machine-type` is load-bearing — it is how a spawned actor's liveness rides the (revertible) frame value rather than a parallel registrar. The only transient snapshot-root slot is `:rf/bootstrap-pending?` (cleared on first event). **There is no `:rf/finished?` slot** — finality is *recomputed* at the lifecycle-handler boundary from the post-transition `:state` (active leaf `:final?`, or every region final for a parallel machine), never stamped onto the snapshot, so the pure `machine-transition` surface stays free of runtime-only bookkeeping (per [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key)).

**Runtime-stamped machine-spec keys (sibling vocabulary, NOT snapshot-internal).** The runtime ALSO stamps a small set of `:rf/*` slots on the live machine-spec value (the runtime's "machine" record threaded through `apply-transition-once` and the lifecycle handlers) — these are NOT snapshot-internal and do NOT persist; they are reconstructed at handler-call time from the registrar and the dispatched event:

- `:rf/frame` — the owning frame's id (the carried frame from the dispatch run; never defaulted — a missing frame here is an internal invariant failure, `:rf.error/no-frame-context`)
- `:rf/platform` — the active platform (`:client` / `:server`) per [011](011-SSR.md)
- `:rf/parent-id` — the machine's own id (or the parent's id for spawned actors), used for trace addressing
- `:rf/region` — present iff the spec is a synthetic region-machine of a `:type :parallel` parent; the region-name keyword used by `after-epoch-path` to scope timers per [005 §Per-region scoping](005-StateMachines.md#per-region-always--after--spawn-scoping)

These spec-level keys are stamped by `prepare-machine-ctx` (and by the parallel-regions synthesiser) and are visible to user callbacks via the unified context-map's `:meta` key (every machine callback receives a single context-map arg).

**Open-map invariant.** Snapshots are open maps: user `:data` keys at any depth are fine. The runtime-reserved set above is the **closed** subset of `:rf/*`-prefixed slots the runtime owns inside the snapshot. The migration agent flags any user write to `[:rf.runtime/machines :snapshots <id> :data :rf/<reserved>]` or to `[:rf.runtime/machines :snapshots <id> :rf/<reserved>]` as a collision.

### Reserved sub-ids

The reserved set of framework-shipped sub-ids:

| Reserved sub-id | Returns | Spec |
|---|---|---|
| `[:rf/machine <machine-id>]` | The named machine's snapshot, or `nil` if not initialised. | 005 |
| `[:rf/machine-has-tag? <machine-id> <tag>]` | `true` iff the machine's current snapshot's `:tags` set contains `tag`. | 005 |
| `[:rf/route]` / `[:rf.route/id]` / `[:rf.route/params]` / `[:rf.route/query]` / `[:rf.route/fragment]` / `[:rf.route/transition]` / `[:rf.route/error]` / `[:rf.route/chain]` | Route-related reads | 012 |
| `[:rf/pending-navigation]` | The pending-navigation slot (or `nil`) — populated when a `:can-leave` guard rejects; reads the runtime-db projection. | 012 |

**A runtime-db framework read is a subscription vector — one read grammar.** The framework runtime-db reads above (plus, for the optional Resources artefact, `[:rf.resource/state <query>]` / `[:rf.mutation/state {:instance <instance>}]`) are read with the ordinary `subscribe` naming their reserved `:rf/*` vector — there is no named-read-sugar fn layered over them (the `sub-machine` / `sub-route` / `sub-pending-navigation` / `sub-resource` / `sub-mutation` sugar fns were removed in rf2-il99l3, reversing rf2-2cmcas). The vector is the semantic identity the rest of the system depends on: a `:<-` chain names the vector, the machine-selector recognizer matches `[:rf/machine …]` / `[:rf/machine-has-tag? …]`, and the granular reads (`:rf.route/id`, `:rf.resource/data`, …) are vector-only — a sugar fn could never ride inside a `:<-` vector, so it could only duplicate the grammar, never replace it. Ordinary app-db content (including a flow's output, which lands in app-db) is read the same way, with the plain `subscribe` naming an app-authored sub-id.

For the user-facing API surface (signatures, status, cross-references) see [API.md](API.md). For machine read mechanics see [005 §Subscribing to machines via the `:rf/machine` sub](005-StateMachines.md#subscribing-to-machines-via-the-rfmachine-sub).

## Event-pipeline vocabulary — the terms one event traverses

> This is the authoritative home for the **event-pipeline** vocabulary — the fixed stage sequence one event traverses, its write/read split at the commit seam, the world/frame pair, and the pipeline / run / epoch triple. Every Spec, doc, tool, and example spells these the same way; the mechanical contracts live in [002-Frames §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics) (drain / dispatch vocabulary) and [009-Instrumentation §Cascade projection](009-Instrumentation.md#event-bundle-projection-group-by-event--domino-bucket) (trace vocabulary). The retired **event cascade** / **the loop** / **six dominoes** framings are demoted (see the deprecation table below); they survive only as a first-contact mnemonic, never as formal terms.

### The core terms

| Term | Is | Is not |
|---|---|---|
| **event pipeline** | The **fixed stage sequence one event traverses**. It has a **write side** and a **read side**, split at the **commit** seam — nothing crosses between them except the committed value. The write side is **transactional up to the commit** (a handler's `:db` write either commits in full or not at all); it is **best-effort after** it (post-commit effects fire in order but are not rolled back on a later failure — per [002 §`:fx` ordering and atomicity guarantees](002-Frames.md#fx-ordering-and-atomicity-guarantees)). | Not a loop, not a run-to-fixed-point family (that is a **drain**), not a single traversal (that is a **pipeline run**). Not "the six dominoes" as a formal term. |
| **world** | The **map of declared facts assembled for a handler** — the coeffects a handler reads plus the app-state entry. **App-state is one entry in the world, always present, but not otherwise special**: it sits alongside every other declared fact and is committed by the same seam. | Not a synonym for app-db. Not a privileged root the other facts hang off. |
| **frame** | A **running world** — a world under a live runtime (its own queue, drain state, caches, and resolved image generation). The isolated runtime boundary the corpus already names; see [§Frame vocabulary](#frame-vocabulary--one-name-per-frame-fact) below for the five frame facts. | Not the static world map on its own (a world becomes a frame when a runtime drives it). |
| **commit** | The **seam** between the write side and the read side. The one place the write side's assembled-and-transformed value becomes durable; the only thing that crosses to the read side. Write side is transactional up to here, best-effort after. | Not a stage that transforms the value further; it is the boundary the value passes through unchanged once decided. |

### The stages

The pipeline stages, in order, split across the commit seam:

| Side | Stage | What it does |
|---|---|---|
| **write** | **assemble** | Build the **world** — gather the declared facts (coeffects + app-state) the handler will read. |
| **write** | **transform** | Run the handler over the world; produce the intended new value and the declared effects. |
| **write** | **commit** | The seam. Make the write durable (the `:db` write transitions atomically, one step, before any effect runs). |
| **write** | **perform** | Run the declared effects in source order — best-effort, post-commit. |
| **read** | **derive** | Recompute the subscriptions whose inputs the commit invalidated. |
| **read** | **render** | Repaint the views the derivation changed. |

**Write side runs per event; read side runs once per drain at settle.** A [**drain**](002-Frames.md#run-to-completion-dispatch-drain-semantics) processes the originating event plus every event its handlers dispatch, each running its own full **assemble → transform → commit → perform** write side, back-to-back to fixed point. The read side (**derive → render**) runs **once**, at drain settle, over the final committed state — not once per event. This is why **drain** is kept as a distinct term: it is the scheduling unit that bounds when the read side runs.

### The triple — pipeline / run / epoch

Three distinct nouns for three distinct things; do not use one for another:

| Noun | Is |
|---|---|
| **pipeline** | The **structure** — the fixed stage sequence itself (assemble → transform → commit → perform → derive → render). It does not "happen"; it is the shape every traversal follows. |
| **run** (a **pipeline run**) | **One traversal** of the pipeline — one dequeued event going from assemble to commit (and the drain's shared read side at settle). This is the unit that replaces the retired "event cascade" for a *single* traversal. |
| **epoch** | The **record a run leaves** — one [`:rf/epoch-record`](Spec-Schemas.md#rfepoch-record) per dequeued event, the durable, snapshottable boundary a run produces (per [002 §Drain versus event — the epoch unit](002-Frames.md#drain-versus-event--the-epoch-unit)). |

### Deprecations

| Retired term | Replacement | Note |
|---|---|---|
| **event cascade** (the event-traversal sense) | **pipeline run** (one traversal) **or** **drain** (the to-fixed-point family) | Choose by what you mean: a single event's traversal is a **run**; the run-to-completion family of events is a **drain**. |
| **the loop** | *(no formal replacement)* | Demoted to a **first-contact mnemonic** at most — legitimate only in an introductory "here's the shape" gloss, never as a formal term in a contract, catalogue, or API. |
| **six dominoes** / **the six dominoes** | *(no formal replacement)* | Same demotion — a first-contact mnemonic at most. The stages are named **assemble → transform → commit → perform → derive → render**; that is the formal vocabulary. |

### SENSE GUARD — only the event-traversal sense renames

The word "cascade" carries **two unrelated senses** in the corpus, and only one is retired:

- **The event-traversal sense** — "the event cascade", a dispatched event fanning out through its handlers — **IS retired** here (→ **pipeline run** / **drain**).
- **The machines *cancellation cascade*** — the state-machine mechanism whereby cancelling a parent actor cancels its descendants ([005-StateMachines](005-StateMachines.md)) — **KEEPS its name.** It is a distinct, correctly-named concept; it is not an event traversal. Do not rename it, do not flag it as residue, and do not let a mechanical event-cascade sweep touch it.

> **Downstream note.** This section is the **foundation** the pipeline-vocabulary epic briefs from. The sweeping mechanical renames across the corpus, the docs/core owner pages, and the core-implementation public-key renames are tracked as separate beads (spec prose sweep, docs sweep, and the atomic implementation Tier-2 key rename). The **public-key renames** in the `event-*` noun family (below) are ruled here but landed by the implementation bead so the key spelling and the impl that emits it change together — see [§The `event-*` noun family](#the-event--noun-family).

### The `event-*` noun family

The retention / bundling nouns use the **`event-*`** family. The unit of retention and bundling is the **event** (one dequeued event = one **pipeline run** = one **epoch**, 1:1 by per-event atomicity); "event" is the crispest unit, since a child dispatch is its own event with a parent link. The ruled renames:

| Noun | Kind |
|---|---|
| **`:events-retained`** | per-frame trace-ring retention knob (frame metadata + `configure! {:trace-buffer …}`) |
| **`:rf.trace/events-retained`** | namespaced trace-ring retention knob |
| **event bundles** | `trace-buffer` default return shape |
| **`group-by-event`** | the `re-frame.trace.projection` grouping fn |
| **event-bundle** | off-box streaming wire vocabulary |

**Docstring guard.** The retention knob's documentation MUST say **one slot per EVENT** (per dequeued event / per pipeline run), regardless of how many **trace events** its run emitted. `events` (dequeued events / traversals) vs `trace-events` (individual instrumentation emissions) is the one known misread — a run that emits 50,000 trace events still consumes exactly **one** retained slot.

**No collision with the epoch store.** The epoch store's own `:epoch {:depth}` knob is **UNCHANGED** — it is a separate surface (epoch-history depth, not trace-ring retention) and does not collide with the `event-*` family.


## Frame vocabulary — one name per frame fact

> **[EP-0024](../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md) §Vocabulary.** A frame shows up under several distinct facts — an address you route to, a live value you own, a closure you carry into a callback, a serializable projection, and the registration generation it runs against. [EP-0007 one-name-per-fact](../docs/EP/EP-0007-one-name-per-fact.md) requires each fact carry exactly one name. This section pins those five names so every Spec, tool, and example spells them the same way; the normative contracts live in [002-Frames](002-Frames.md) and [API.md](API.md). Do **not** use retired composition vocabulary as current public frame vocabulary.

| Term | Is | Is not |
|---|---|---|
| **frame id** | The stable public **routing address** of a live frame inside the process — data: serializable, comparable, traceable, and the value an opts map (`{:frame …}`), a provider, a tool, or a trace carries. The public `dispatch` / `subscribe` / read / scope surfaces target a frame **by its id**. | Not the live value. Not a framework-privileged keyword (`:rf/default` is an ordinary id per [EP-0002](../docs/EP/EP-0002-frame-target-resolution.md)). |
| **frame value** | The live **lifecycle token** `make-frame` returns. It owns (or reaches through one registry entry) the frame id, both durable partitions, the runtime-subsystem state, queue/drain state, caches, lifecycle hooks, and the resolved image generation (per [Runtime-Subsystems §One frame value owns every per-frame subsystem](Runtime-Subsystems.md#one-frame-value-owns-every-per-frame-subsystem--one-registry-one-teardown-path)). Its representation is not an app-facing data contract; callers read its id through the public accessor rather than depending on its shape. | Not a second public routing spelling — passing a frame value to `dispatch` / `subscribe` is not the canonical app form (read its id and pass the id). Not the serializable frame-state value. |
| **frame api** | The value `rf/capture-frame` returns — a bundle of frame-bound ops (`:frame` + `:dispatch` + `:dispatch-sync` + `:subscribe`) already targeted at the frame resolved when it was captured. The carry primitive for callbacks that fire after the render or lexical frame context has unwound. | Not a frame value and not a frame id; it carries operations, it is not the frame. Spelled lowercase **frame api** so it never reads as the public re-frame2 API. |
| **frame-state value** | The **serializable projection** of a frame's two partitions — `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}` (per [§The two-partition frame contract](#the-two-partition-frame-contract)) — the value that rides serialization, hydration, restore, and time-travel. | Not the live frame value: it carries no host handles, no queue/drain state, no resolved generation. |
| **resolved image generation** | The **sealed registration generation** a frame resolves registration lookups against while it runs — the inert, immutable `:rf.gen/*` data structure ([EP-0023](../docs/EP/EP-0023-image-loaded-frames.md) §Image) held as a slot **on the frame value**. | Not a registrar and not mutable: it is the frozen lookup table a frame reads, swapped (not edited) by hot reload. |

These names are stable across the corpus. The lifecycle, scope, and carry **surfaces** that produce and consume these values (`make-frame` / `destroy-frame!` / `with-frame` / `frame-provider` / `capture-frame`, the routing-vs-ownership-vs-carry split) are owned by [002-Frames](002-Frames.md) and the [API.md](API.md) facade rows — this section governs only the **fact names**, not the surface placement (which the [§Facade policy](#facade-policy--a-facade-exports-the-front-porch-the-workshop-lives-behind-a-door) above governs).

## Cross-MCP indicator-field vocabulary (suppression counters)

The MCP servers (re-frame2-pair-mcp / story-mcp) consult the framework's wire-elision walker (`rf/elide-wire-value`, per [API.md §`rf/elide-wire-value`](API.md#elide-wire-value-the-wire-boundary-walker)) when assembling tool-response payloads. The walker drops `:sensitive? true` leaves and elides over-threshold `:large? true` leaves at the wire boundary; the **count of suppressed items** must surface back to the calling agent on the response map so the LLM can pattern-match "the payload was filtered" without re-inferring it from absence. Without a pinned vocabulary, each server invents its own slot shape (`:dropped-sensitive` vs `:redacted-count` vs `:n-sensitive-dropped`) and the agent host has to special-case per server. The cross-server value proposition collapses if every server invents its own dialect — same anti-pattern the `:rf.mcp/*` wire-marker pin (above) and the `tools/mcp-conformance/NAMING.md` verb pin defend against.

### Reserved indicator slots (MCP-shaped returns)

| Indicator slot | Meaning | When present | Owner |
|---|---|---|---|
| `:dropped-sensitive` | Integer count of leaves the walker dropped because they sat at a schema-declared `:sensitive? true` slot (Malli `:sensitive?` property). | On every tool response that walked a tree-typed payload, when the count is non-zero. Omit when zero. | MCP servers (cross-server reserved) |
| `:elided-large` | Integer count of leaves the walker replaced with the `:rf.size/large-elided` marker because they sat at a schema-declared `:large? true` slot. | On every tool response that walked a tree-typed payload, when the count is non-zero. Omit when zero. | MCP servers (cross-server reserved) |

**Unqualified, not namespaced.** These two slots are reserved as **unqualified keys** (`:dropped-sensitive`, `:elided-large`) — not under `:rf.size/*` or `:rf.mcp/*`. The rationale: they ride alongside tool-shaped payloads (`{:trace [...] :dropped-sensitive 3}`, `{:db {...} :elided-large 2}`) where the tool's own slot vocabulary is unqualified by convention; introducing a namespaced key here would split the response shape across two key conventions and burn agent-host pattern-match budget for no information gain. The wire **markers** at the leaf-substitution site stay namespaced (`:rf.size/large-elided`, `:rf/redacted`) — those are addressable values the agent re-fetches; these counts are scalar summaries on the envelope.

**Streaming payloads.** Subscribe-style notifications (per re-frame2-pair-mcp's `subscribe` per [`tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md`](../tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md)) carry the same two slots on **each progress payload** and on the final summary — see [`skills/re-frame2-pair/references/streaming-subscriptions.md`](../skills/re-frame2-pair/references/streaming-subscriptions.md#privacy-posture) for the live shape.

**Conformance gate.** Per Spec 009 §"Size elision in traces" — "Indicator field on tool responses" (MUST-level: tools that return structured response maps MUST carry an `:elided-large` count alongside the existing `:dropped-sensitive` count, one MUST-level row per consumer-facing tool that walks a tree-typed payload). The shape-conformance test lives in [`tools/mcp-conformance/wire-vocab/`](../tools/mcp-conformance/wire-vocab) (cross-server vocabulary gate); the per-server catalogue entries (re-frame2-pair-mcp's [`003-Tool-Catalogue.md`](../tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md) and story-mcp's [`002-Tool-Registry.md`](../tools/story-mcp/spec/002-Tool-Registry.md)) document each tool's indicator-field row.

### Reserved panel-chrome surface (on-box consumers)

Dev-tools panels that render trace data inherited from `re-frame2-xray` and the optional `day8/re-frame2-story` library (per [Tool-Pair.md](Tool-Pair.md)) surface the same two counters as **panel chrome**, not as JSON fields. The chrome shape is:

| Chrome string | Meaning | Where rendered |
|---|---|---|
| `[● REDACTED N]` | N leaves dropped because they matched `:sensitive?`. Mirror of `:dropped-sensitive` on MCP returns. | Xray panel bottom-rail indicator; story trace-panel inline marker; analogous slots in any future on-box panel. |
| `[● ELIDED N]` | N leaves replaced with a `:rf.size/large-elided` marker. Mirror of `:elided-large` on MCP returns. | Same surfaces as `[● REDACTED N]`; the two indicators may render side-by-side when both are non-zero. |

The bullet glyph (`●`) and the square-bracket delimiters are the canonical shape — the user clicks the indicator to opt in for a single fetch (per Spec 009 §"Consumer-side defaults", on-box listener integrations). The pair is bullet-identical across panels so an agent watching a panel screenshot recognises the indicator on either consumer.

### Cross-references

- [`tools/mcp-conformance/NAMING.md`](../tools/mcp-conformance/NAMING.md) — canonical verb-vocabulary home for the MCP triplet; the **indicator-field vocabulary** above is its scalar-counter peer.
- [`tools/mcp-conformance/TOKEN-BUDGETS.md`](../tools/mcp-conformance/TOKEN-BUDGETS.md) — cross-MCP token-budget posture; indicator counters ride the same response envelope the `max-tokens` / `:rf.mcp/overflow` contract governs.
- [`tools/mcp-conformance/wire-vocab/`](../tools/mcp-conformance/wire-vocab) — Malli + grep conformance gate over the `:rf.mcp/*` / `:rf.size/*` wire-marker namespaces (the **values** these counters describe).
- [Spec 009 §"Size elision in traces" — Indicator field on tool responses](009-Instrumentation.md#size-elision-in-traces) — the MUST-level requirement this convention pins.
- [Spec 009 §"Privacy / sensitive data in traces"](009-Instrumentation.md#privacy--sensitive-data-in-traces) — the `:sensitive?` mechanism whose drops the `:dropped-sensitive` counter sums.

## Privacy config-knob naming (on-box UI vs off-box wire egress)

> Cross-reference: [Privacy.md §Config knobs](Privacy.md#config-knobs) — the cross-artefact inventory of every privacy surface (Spec 010 schema meta, Spec 014 HTTP denylists, Spec 015 data classification, the epoch `:redact-fn`, the cross-MCP filters) plus the composition order from handler exit to off-box wire. This section pins the verb split; Privacy.md pins where each verb is consumed.

Consumers of the `:sensitive?` filter (per [Spec 009 §"Privacy / sensitive data in traces"](009-Instrumentation.md#privacy--sensitive-data-in-traces)) expose a user-controllable knob that decides whether sensitive values pass through the consumer's surface. Two consumer classes exist, and they MUST use different verbs for the knob name — the verb encodes which trust boundary the user is crossing:

- **On-box devtools UI consumers** (Xray panel, story trace panel, future on-box panels) use the **`show-sensitive?`** verb under the `:trace/*` ns (e.g. `:trace/show-sensitive?`). The semantics are "the panel is for me, do I want to look" — the sensitive values are already in the same process as the operator; the toggle controls UI visibility, not egress.
- **Off-box LLM-egress consumers** (re-frame2-pair-mcp, story-mcp wire pipelines; re-frame2-pair preload before fan-out to a hosted LLM endpoint) use the **`include-sensitive?`** verb, **unqualified** (the bare key on a per-call args map; e.g. `(rf/elide-wire-value v {:include-sensitive? false})`, or `{:rf.size/include-sensitive? false}` when carried alongside the size-elision policy keys per [Conventions §Reserved namespaces `:rf.size/*`](#the-single-root-reserved-set)). The semantics are "do I cross the trust boundary out of the process" — the toggle controls wire egress, not panel visibility.

Both verbs default to **suppress** (`show-sensitive? false`, `include-sensitive? false`) per Spec 009's default-private posture. The verb choice is the discriminator: a reader scanning a config flag knows whether the knob governs UI visibility (`show-`) or wire egress (`include-`) without re-deriving from context.

A sixth consumer adding a knob picks the verb by trust-boundary class — on-box panel uses `show-sensitive?`, off-box wire uses `include-sensitive?`. Cross-reference: [Spec 009 §Privacy / sensitive data in traces — Consumer-side defaults](009-Instrumentation.md#privacy--sensitive-data-in-traces).

## Feature-modularity prefix convention

A *feature* is identified by its **id prefix**, not by a registry kind. By convention a feature with prefix `:cart`:

- Event ids: `:cart/...` and `:cart.<area>/...` (`:cart/initialise`, `:cart.item/add`)
- Sub ids: `:cart/...` (`:cart/items`, `:cart/total`)
- View ids: `:cart/...` (`:cart/summary`, `:cart.item/row`)
- App-db slice: `[:cart]`
- Schemas registered under `[:cart]` paths
- Fx specific to the feature: `:cart.<sub-area>/...` (`:cart.persistence/save`)

A feature does not reach into another feature's slice directly — it goes through the other feature's subs (to read) and dispatches the other feature's events (to write). Construction prompt CP-6 enforces this at scaffold time.

Full rationale: [000-Vision §Pointers to per-area Specs (Features)](000-Vision.md#pointers-to-per-area-specs) and [Construction-Prompts.md §CP-6](Construction-Prompts.md).

## The `:rf/path` algebra

This is the normative home for one path algebra and one canonical-identity rule, stated once, with laws every consumer inherits ([EP-0012](../docs/EP/EP-0012-path-optics-and-canonical-forms.md), ACCEPTED). app-db and runtime-db focus, schema paths, redaction-mark paths, flow inputs/outputs, route params, resource cache keys, work ids, and future feature-module declarations all cite this section instead of restating fragments. Plain vector paths stay valid and mechanically migratable from re-frame v1; this section names the algebra behind them, it does not add a public optics API.

**Internal-only, standing (EP-0012 disposition 1; re-recorded, rf2-woxepk).** The *semantics below are normative immediately*. The reference helpers (conceptually `rf.path/{get,lookup,put,over,compose,prefix?,overlap?,instantiate}` and `rf.identity/{canonical,canonical-bytes}`) stay **internal** — there is no `re-frame.core` facade export and none is classified. The original two-or-more-consumers gate fired against framework **artefact families** (core, flows, schemas, routing, resources, machines all cite the internal namespace) — a population that proves shared semantics, not public demand. A per-op production census (excluding `path.cljc` and tests) found only the boundary validators clear it — `normalize-concrete` (6 families) and `segment?` (3) — while the app-usable ops (`get`/`put`/`over`/`lookup`/`compose`/`instantiate`) have 0-1 production consumers each; an honest per-op graduation today would publish two validators no app author calls while the ergonomic ops stayed internal, a lopsided surface. The gate is **re-armed on app-facing demand**: an op graduates only when an app-facing consumer needs it — a guide, pattern, or migration doc that must *teach* the op, or an external request — never when framework artefact families accumulate. If an op ever graduates, its public home is the `re-frame.path` namespace, **never** `re-frame.core`, and **never** the bare name `path` ([EP-0022](../docs/EP/EP-0022-registered-interceptors.md) tombstone — `re-frame.core/path` already throws `:rf.error/path-removed`). Subsystems MUST NOT keep private ad hoc overlap, canonicalization, or path-round-trip logic once these helpers exist — there is no "tool-only" path semantics, and public helpers (when exposed) obey the identical laws.

### Path shape and segment domain

A **concrete `:rf/path`** is a vector of EDN path segments that focuses a value inside an ordinary Clojure/EDN value:

```clojure
[]
[:user]
[:cart :items 42 :qty]
[:rf.runtime/routing :current :params :slug]
```

The empty vector `[]` is the **root path** — it focuses the entire value. The primary container is a vector; APIs MAY accept any sequential collection for migration ergonomics, but the canonical form is a vector and **all stored declarations MUST normalize to a vector**.

**Segment domain (the shared upper bound).** Concrete segments MUST be portable EDN identity values usable as associative keys or vector indexes: keywords, strings, symbols, integers (in the safe-integer range — see below), booleans, UUIDs, instants, and `nil` (when the host can represent them as EDN). Functions, atoms, promises, DOM nodes, AbortControllers, opaque host objects, and other host handles are **not** valid segments. This is the shared upper bound, not a requirement that every subsystem accept every type: a spec MAY *narrow* the domain for its surface (e.g. flows exclude `nil` output segments; SSR allowlists are single-segment) as long as it records the narrowing as a **stated policy over the shared definition**, never a private re-definition. Concrete runtime paths MUST NOT contain host values; such values are rejected at the boundary that accepts the path.

**Partition-relative.** This algebra defines path *semantics*, not partition *ownership*. The owning spec still selects the root value (app-db, runtime-db, the sub/flow output, the event arg-map, the machine `:data` value); the shared algebra applies after that selection.

### Path operations

```clojure
(get value path)        ;; the focused value, or nil when missing
(get value path nf)     ;; nf when missing
(lookup value path)     ;; {:present? true :value v} | {:present? false}
(put value path x)      ;; value with x installed at path
(over value path f)     ;; value with f applied to the current focus
(compose p q)           ;; the two paths appended (canonical vector)
(prefix? p q)           ;; true when p is a prefix of q
(overlap? p q)          ;; true when either path is a prefix of the other
```

`over` on a missing path calls `f` with `nil`, matching `update-in`, unless a surface explicitly provides a not-found-aware operation. A subsystem that must distinguish missing from present `nil` uses `lookup` first.

### Path laws

For concrete paths and EDN values, conforming helpers MUST satisfy these laws:

```text
lookup(put(s, p, x), p) = {:present? true, :value x}            ;; put-lookup
if lookup(s, p) = {:present? true, :value x}
  then put(s, p, x) = s                                          ;; lookup-put
put(put(s, p, x), p, y) = put(s, p, y)                           ;; put-put
compose(p, []) = p ; compose([], p) = p                          ;; compose units
compose(compose(p, q), r) = compose(p, compose(q, r))            ;; compose assoc.
get(s, compose(p, q), nf) = get(get(s, p), q, nf)                ;; get-compose
  when lookup(s, p) is present and the focus supports q
over(s, p, identity) = s    when lookup(s, p) is present         ;; over identity
over(s, p, f) = put(s, p, f(get(s, p)))                          ;; over (nil-on-missing get)
```

The root-path laws are:

```text
get(s, [], nf)  = s
lookup(s, [])   = {:present? true, :value s}
put(s, [], x)   = x
over(s, [], f)  = f(s)
overlap?([], p) = true
```

The laws are not decorative: raw `assoc-in` violates the root-path law — `(assoc-in {:a 1} [] {:b 2})` returns `{:a 1, nil {:b 2}}`, assoc'ing under the key `nil` instead of replacing the root. A `put` that delegates to `assoc-in` is therefore non-conforming; the required behaviour is `put(s, [], x) = x`. A second realistic violation: a `put` that "optimizes" `nil` writes by `dissoc`-ing breaks put-lookup at `x = nil` — exactly the missing-vs-present-`nil` ambiguity below.

**Intermediate-container policy (stated once).** Missing or segment-incompatible intermediate values are created as **maps**, matching Clojure's `assoc-in` / `update-in` map-creation behaviour. Vector indexes are supported only for in-range non-negative integer segments; a vector faced with a non-integer or out-of-range segment is replaced by a fresh map (defining the vector-index case explicitly rather than letting a host `assoc` throw). This keeps `put` total so the put-lookup law holds for every path, while never corrupting a compatible existing container.

### Missing versus present `nil`

`nil` is a valid EDN value, and an absent key differs from a key present with value `nil`:

```clojure
(lookup {} [:page])        ;; => {:present? false}
(lookup {:page nil} [:page]) ;; => {:present? true :value nil}
```

Canonical identity preserves this distinction: `(canonical {})` is **not** `(canonical {:page nil})`. A surface MAY intentionally elide `nil` before canonicalization, but that is a surface-specific policy (routing's query printing omits a `nil` query key; resource params get no such elision for free), never the canonical-identity rule.

### Path prefix and overlap

```clojure
(prefix? [:cart] [:cart :items 42])        ;; => true
(prefix? [:cart :items 42] [:cart])        ;; => false
(overlap? [:cart :items] [:cart :items 42]) ;; => true (parent/child)
(overlap? [:cart :items 42] [:cart :items 43]) ;; => false (siblings)
(overlap? [] [:anything])                  ;; => true
```

`overlap?` is true exactly when either path is a prefix of the other, and it is **symmetric**. This is the relation flows already need: flow B depends on flow A when A's output overlaps one of B's inputs, and two output paths in one frame are invalid when they overlap. Flows MUST use the shared relation, not a private one. (Path *templates* need a separate `may-overlap?` relation because variables stand for many concrete values; that is tooling-only and not required for concrete runtime flow sorting.)

### Named path declarations and templates (disposition 2)

A named path declaration is a data map; this EP reserves **`:rf/path`** as the path slot:

```clojure
{:id      :invoice/customer-email
 :rf/path [:billing :invoices :by-id [:rf.path/param :invoice-id] :customer :email]
 :params  [:map [:invoice-id :uuid]]
 :owner   :billing/invoices
 :schema  :app/email
 :privacy #{:sensitive}}
```

A **path template** is a declaration-time path with named variables. The **canonical stored shape** of a template variable is the explicit data form **`[:rf.path/param <name>]`** (under the reserved `:rf.path/*` namespace). The `'?name` quote-symbol spelling is **declaration-boundary sugar**, normalized into the data form. The data form is what `CEDN-1` encodes and what traces/Xray display — **`'?name` never appears in any stored or serialized shape** (one fact, one identity); and frame-config path maps accept **concrete paths only** (no templates), a stated narrowing. A concrete runtime path that literally contains the symbol `?invoice-id` is just a symbol segment — it is not substituted unless processed as a template declaration. Instantiation is pure; an unbound parameter fails closed (an unbound param would silently produce a `nil` segment), and — because instantiation is a **concrete-path producer** whose result is fed to the path getters/setters — a *bound* parameter whose value is **outside the concrete-segment domain** (a host object, a function, or a composite vector/map/set) also fails closed with `:rf.error/bad-path`, the same validated boundary frame classification and resource scope route through (it never smuggles a non-portable segment into a path presented as concrete). Named paths are optional for application authors but SHOULD be preferred when a path carries ownership, schema, privacy, projection, or derivation metadata.

### Canonical EDN identity

Canonical identity is a pure function over portable EDN, used for every equality-sensitive runtime identity: resource params and scopes, scoped resource keys, work ids, route path/query params (after route-specific coercion), named path declarations and templates, schema digest path keys, and any future derivation/process identity. **Equal facts produce the same identity across CLJ/CLJS hosts, and unsupported values fail closed.**

Canonical identity is **not** stringification. `str`, `pr-str` over unordered host maps, `JSON.stringify`, and object identity are not valid identity contracts — they differ by host, leak insertion order, or depend on references.

**Identity vs digest (disposition 5).** The canonical EDN value **is the identity** everywhere — storage, work ledger, traces, epoch/replay records. A **digest** is an *optional, versioned, always-recomputable projection* for size-constrained surfaces (the existing `:rf.size/include-digests?` flag is the precedent); it is never an independent identity fact, never required for correctness, and never the authoritative stored key in v1. Tools SHOULD retain a human-readable EDN projection for debugging (Xray rows stay readable).

The **`CEDN-1` canonical EDN domain** is: `nil`, booleans, strings, keywords, symbols; portable integers in the ECMAScript safe-integer range `[-9007199254740991, 9007199254740991]`; UUIDs and instants (as EDN values or explicit tagged data); and vectors, lists, maps, and sets whose nested values are canonical EDN values. A subsystem MAY choose a smaller input domain for safety, stated explicitly; that is not a fork of the encoding.

The canonicalizer **MUST reject by default** (fail closed, error id `:rf.error/non-edn-identity` — under the reserved `:rf.error/*` namespace and grammar above): functions; atoms, refs, volatiles, promises, futures; DOM nodes, React elements, AbortControllers, request handles, timers; arbitrary host objects or class instances; floating-point values, ratios, arbitrary-precision decimals, NaN, and infinities (unless a future spec encodes the numeric class); and mutable by-reference objects. An out-of-domain value buried anywhere in a structure fails the **whole** identity closed, never a host-comparison fallback. APIs that need such values MUST encode them into portable EDN first (e.g. coerce a host date to an EDN `#inst` instant at the boundary, after which it is an instant fact, not a host object).

### Canonical byte encoding (`CEDN-1`)

`CEDN-1` is the reference byte encoding — an internal comparison/digest format, not a display or URL format. Implementations MAY store a normalized EDN projection or a digest over these bytes, but equality-sensitive comparison MUST be equivalent to comparing `CEDN-1` bytes. It encodes a UTF-8 token stream with a **type tag before every value**:

| EDN value | Canonical token |
|---|---|
| `nil` | `n` |
| Boolean | `b:0` or `b:1` |
| String | `s:` plus a canonical EDN string literal over Unicode scalar values |
| Keyword | `k:` plus the canonical EDN keyword token, without auto-resolved `::` shorthand |
| Symbol | `y:` plus the canonical EDN symbol token |
| Portable integer | `i:` plus base-10 digits in the safe range, no leading `+`, no leading zero except `0` |
| UUID | `u:` plus lower-case RFC 4122 text |
| Instant | `t:` plus RFC 3339 UTC text with millisecond precision |
| Vector | `v[` elements in order `]` |
| List | `l(` elements in order `)` |
| Set | `q#{` elements sorted by their `CEDN-1` bytes `}` |
| Map | `m{` key/value pairs sorted by key `CEDN-1` bytes `}` |

Adjacent element tokens, and each map key from its value, are separated by a single ASCII space. String/keyword/symbol encoders MUST reject names that cannot round-trip through portable EDN readers on both CLJ and CLJS. Instant encoding normalizes equivalent instants to UTC before printing (timezone text from the source literal is not identity). The type tag is part of the bytes, so `"42"`, `42`, `:42`, `[1 2]`, and `(1 2)` cannot collide; **heterogeneous map keys are therefore legal** within the supported domain (the sort key is the complete type-tagged key byte sequence). If a value is outside the domain, the whole identity fails closed.

**Map key canonicalization.** Map entries MUST be ordered deterministically by the `CEDN-1` bytes of their keys — not by insertion order, hash-map iteration order, locale, or host identity. Compute each key's bytes, sort lexicographically, encode entries in that order. Duplicate canonical keys are invalid and MUST be rejected before the value becomes a cache key, route identity, or work id.

```clojure
(= (canonical {:page 1 :tag "cljs"}) (canonical {:tag "cljs" :page 1})) ;; => true
```

**Sequences and sets.** Vectors and lists preserve their kind and element order and are distinct EDN facts (not silently collapsed). Sets are unordered — canonical encoding sorts elements by their canonical element encoding — and remain distinct from vectors/lists. Subsystems SHOULD prefer vectors for public identity tuples (idiomatic, order-preserving, already used for event vectors, resource keys, owner tokens, causes, and work ids).

### Resource identity and work ids

A **scoped resource key** is `[canonical-scope resource-id canonical-params]`, where scope and params use the shared canonical rule, so map insertion order cannot change the key:

```clojure
(def scope-a [:rf.scope/session {:user-id "u-42" :tenant-id "acme"}])
(def scope-b [:rf.scope/session {:tenant-id "acme" :user-id "u-42"}])
(= (canonical scope-a) (canonical scope-b)) ;; => true
```

**Work ids** build on the same identity (`[:rf.work/resource scoped-resource-key generation]`). One work record MUST have one canonical id — a subsystem MUST NOT carry a second near-duplicate stale-suppression key for the same facts under a different head keyword (one name per fact, applied to identity).

**Supersession discriminators — one concept, three context-local names.** A monotone integer that lets a subsystem detect "an older operation must not write over a newer one" recurs across three subsystems under three names, *not* unified into one global counter — each names a different fact and lives on a different state-boundary:

- **`:generation`** (resources) — a *load-start / stale-suppression* identity. Allocated host-side (the per-frame high-water mark in `re-frame.resources.state/generation-cache`, off the epoch/SSR egress wire so a restore cannot rewind+recycle it), recorded on the durable entry, and embedded in the work id (above). It bumps when a load *begins*, so a late reply from a superseded fetch is dropped.
- **`:revision`** (resources) — a *write-settle* identity on the *same* entry, DISTINCT from `:generation`: durable runtime-db state bumped UNCONDITIONALLY on every authoritative durable write that *settles* (load success, populate, patch, the optimistic apply). The EP-0019 optimistic-rollback settle protocol compares it (`revision-conflict?`) to decide whether a recorded inverse is still a truthful "before" or has been overtaken. (`:generation` bumps at load *start*, `:revision` only when a write *lands* — see [016-Resources §Status semantics](016-Resources.md).)
- **`:spawn-counter`** (machines, `:rf/spawn-counter`) — a *spawn-id sequencing* counter, a per-id-prefix `{<id-prefix> <int>}` map living in the machine snapshot root / runtime-db (`[:rf.runtime/machines :spawn-counter <machine-id>]`), bumped by the pure spawn-id allocator so `<type>#<n>` instance ids are deterministic from the snapshot (and so replay / restore reproduce the same sequence) — see [005-StateMachines §Spawn-id allocator — counter location](005-StateMachines.md#spawn-id-allocator--counter-location).

The frame-state-boundary placement is opposite: the resource `:generation` is host-side (must NOT ride the restore/SSR wire so a token cannot be rewound and recycled), while the machine `:spawn-counter` is runtime-db state (MUST ride the wire so a restored snapshot resumes id sequencing deterministically). This note exists only so a reader meeting the second name does not mistake it for the first.

### Routes are prisms (deferred to Spec 012)

Registered route patterns define a lawful partial round trip (a *prism*) between URLs and route data — `match-url(route-url(...))` returns canonical route data, query keys are emitted in deterministic canonical order, `nil` query values are elided, and out-of-domain params fail closed. The prism laws are normative but their **conformance and consumer wiring live in [012-Routing.md](012-Routing.md)** (a tier-2 consumer sweep); this foundation slice states only the shared path/identity definition they cite. A future data-form route pattern (disposition 4) MUST normalize into the canonical `[:rf.path/param …]` template shape — a second template grammar would be the per-subsystem redefinition this algebra exists to prevent.

## Canonical event-vector shape (best practice)

The **canonical** call-shape for an event vector — and for the parallel subscribe query vector — is **id-first, with at most one trailing map**:

```clojure
[<event-id>]                   ;; trivial — id only
[<event-id> <single-scalar>]   ;; single-argument
[<event-id> {<k> <v> ...}]     ;; multi-argument — single map payload
```

Same shape for `subscribe`: `[:items-filtered {:status :pending :limit 20}]`.

This is **best practice, not enforced**. The framework runtime accepts variadic vectors (`[<id> a b c]`) — the v1 multi-positional shape is tolerated for migration and caller convenience, and the linter nudges new code toward the map form (per [MIGRATION §M-19](../migration/from-re-frame-v1/README.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in)). Boundary validators (`dispatch`, `dispatch-sync`, the pair-tool MCP dispatch surface) check `vector?` only; they do not reject variadic shape.

Why the canonical form is `[<id> <map>]`:

- **Name over place.** Map keys carry meaning that positional args don't; see [Principles §Name over place](Principles.md#name-over-place).
- **Refactor stability.** Adding a field is one key, not a coordinated edit across every call site.
- **Generator-amenable.** AI scaffolds (per [Construction-Prompts §CP-1](Construction-Prompts.md#cp-1-add-an-event-handler)) emit the map form; round-tripping through the spec preserves it.
- **`unwrap` sugar.** The optional `unwrap` interceptor (per [API §Standard interceptors](API.md#standard-interceptors)) assumes this exact shape — handlers using it destructure the payload directly: `(fn [_ {:keys [...]}] ...)`.
- **Redactable at egress.** Trace/error redaction is path-based, and a path can only address the arg-map (`(second event)`). A secret in a **positional** arg is not path-addressable, so under the fail-open EP-0025 model it ships RAW into every trace/error sink — prefer the map payload form for sensitive args, then classify the path (per [015-Data-Classification §Registration-owned transient classification](015-Data-Classification.md#registration-owned-transient-classification) and [docs/core §keep-secrets-out-of-traces](../docs/core/how-to/keep-secrets-out-of-traces.md)).

Cross-refs: [002 §Routing — canonical call shapes table](002-Frames.md#routing-the-dispatch-envelope), [Construction-Prompts §CP-1 — Call-shape convention](Construction-Prompts.md#cp-1-add-an-event-handler), [MIGRATION §M-19](../migration/from-re-frame-v1/README.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in).

## `reg-sub` input grammar — `input-fn` returns a vector of query vectors

`reg-sub`'s two-function form takes a v2 **`input-fn`** as its optional first function (`(reg-sub id input-fn computation-fn)`). The `input-fn` is a **pure** function from the outer subscription `query-v` to the input query vectors this subscription depends on. There is exactly **one** legal return shape:

> An `input-fn` MUST return a vector, and every element of that vector MUST be a query vector (a vector whose first element is a keyword).

```clojure
input-return := [query-vector*]      ;; query-vector := vector with a keyword head
```

```clojure
;; Accepted
[[:article/by-id id] [:viewer/current]]   ;; multiple inputs
[[:item/by-id id]]                        ;; single input — still a vector OF query vectors
[]                                        ;; no inputs (valid, unusual)

;; Rejected — signals :rf.error/sub-input-fn-bad-return
:viewer/current                           ;; bare keyword (no shorthand)
[:article/by-id id]                       ;; scalar query vector — ambiguous, rejected
[[:article/by-id id] :viewer]             ;; mixed vector + bare keyword
{:article [:article/by-id id]}            ;; map return
```

The grammar is **intentionally narrow** to remove the v1 shape ambiguity: no bare keyword shorthand, no map return, no scalar single-query return, no live reaction / derefable. The scalar query-vector rejection is deliberate — `[:x :y]` is ambiguous (one query with argument `:y`, vs two inputs); the only accepted single-query spelling is `[[:x :y]]`. An `input-fn` is **not** a v1 signal function: it must not call `subscribe`, deref `app-db`, dispatch, mutate, or perform IO; it receives only the outer `query-v`; and it must not choose its edge set from `app-db` (that would break the fixed-topology-per-cache-entry invariant — thread `app-db`-derived parameters through the outer query vector at the call site instead).

Use `:<-` for static inputs (it is exactly a constant `input-fn`); reach for `input-fn` only when the upstream query vectors need values carried by the outer `query-v`. Owned by [006 §Subscription input producers](006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn); mirrored in [API §`reg-sub` input-production modes](API.md#reg-sub-input-production-modes). The registration-shape and input-return error categories (`:rf.error/reg-sub-bad-args`, `:rf.error/sub-input-fn-exception`, `:rf.error/sub-input-fn-bad-return`) are catalogued in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) per the §Error-id and warning-id grammar co-edit invariant above.

## Tool dispatch frame-envelope convention

Canonical re-frame2 devtools (Xray, Story, future tools under `:rf.<tool>/*` — per [§Reserved namespaces](#reserved-namespaces-framework-owned)) host their own state in their own frame. A tool view sitting under a `frame-provider` for, say, `:rf.xray` MUST end up dispatching its own events (`:rf.xray.epoch/set-db-view-mode`, etc.) into `:rf.xray` — never into `:rf/default` or into the inspected app frame. The convention has two cases — pick by **the moment the dispatching fn is invoked**, not by what the call site looks like.

| Case | Convention | Where the frame is captured |
|---|---|---|
| **Synchronous dispatch from a tool view (or a tool handler).** The dispatch fires while the surrounding `frame-provider` / handler binding is still live — typical reg-view bodies, reg-event bodies, sync helpers called from either. | Bare `(rf/dispatch [...])` — OR the explicit two-arg form `(rf/dispatch [...] {:frame :rf.xray})` for a self-documenting call site. | The framework captures `:frame` at dispatch time from the active resolution chain (per [002 §How `:frame` gets attached](002-Frames.md#how-frame-gets-attached)). Either shape is correct. |
| **Async boundary** — the dispatching fn is created during render/handler-time but FIRES later: React `:on-click` / `:on-change` callbacks, `setTimeout`, `setInterval`, `Promise.then`, websocket `onmessage`, intersection-observer callbacks, third-party SDK callbacks. | `(let [{:keys [dispatch]} (rf/capture-frame :rf.xray)] (fn [...] (dispatch [...])))` — captures the frame at frame-api creation and locks the op to it. `capture-frame` is the ONE public carry primitive (API-shrink #1, rf2-csbbwu removed `frame-bound-fn` / `frame-bound-fn*` from the facade); for an arbitrary fn body reach for the equivalent `with-frame` + `current-frame-id` idiom: `(let [frame (rf/current-frame-id)] (fn [...] (rf/with-frame frame ...)))`. | The frame api's op (or the `with-frame` block in the arbitrary-fn idiom) carries the captured frame to every later invocation, so the dispatch is routed correctly regardless of when the callback eventually fires. (Per [002 §`capture-frame`](002-Frames.md#capture-frame--the-keystone-affordance-cljs-reference).) |

**Why two cases, not one.** The framework's dispatch resolution chain (per [002 §Frame target resolution](002-Frames.md#frame-target-resolution--the-carried-invariant)) reads `:frame` correctly from explicit opts, from `reg-view`-injected closures, AND from the router-established dynamic binding around every running handler. The first case lets tools rely on those mechanisms without ceremony. The second case is the one place the scope unwinds — async callbacks fire on a fresh JS stack with no dynamic binding, no React-context tier, no router scope; with no carried stamp a bare dispatch there fails loudly with `:rf.error/no-frame-context` (EP-0002). `capture-frame` is what bridges that gap by capturing the frame as closure state at the moment the callback is constructed.

**`capture-frame` is NOT a fix for "view doesn't update on click".** That symptom — the app-db slot changes, the sub recomputes, but the rendered tree doesn't refresh until an unrelated repaint — is a different bug class entirely: the Reagent adapter's **lazy-seq deref tracking** failure (a `(for ...)` body holding `@(rf/subscribe ...)` that hasn't realised by the time the reactive scope unwinds). The fix is `doall` / `mapv` / `into [:<>]` around the lazy seq inside the render-fn, NOT a `capture-frame` wrap. Per [006 §Lazy-seq deref tracking](006-ReactiveSubstrate.md#lazy-seq-deref-tracking-reagent-adapter). The two failure modes look superficially similar ("click does nothing visible") but the cures don't overlap — diagnosing the wrong class is a debugging trap. If the dispatch reached the right frame's handler and the app-db slot changed, the issue is reactive tracking, not envelope routing.

Tool authors writing new devtool surfaces should default to the synchronous shape for view-internal wiring and reach for `capture-frame` only at genuine async boundaries — over-wrapping is harmless but adds noise; under-wrapping at an async boundary **fails closed**: the bare dispatch raises `:rf.error/no-frame-context` (there is no `:rf/default` to fall open to), the loud signal that a `capture-frame` wrap is missing. (There is no `:rf.warning/dispatch-from-async-callback-fell-through-to-default` warning; it described falling through to a default as a tolerated-but-warned outcome.)

See [002 §`capture-frame`](002-Frames.md#capture-frame--the-keystone-affordance-cljs-reference) for the primitive and [006 §Lazy-seq deref tracking](006-ReactiveSubstrate.md#lazy-seq-deref-tracking-reagent-adapter) for the adjacent-but-distinct Reagent bug class.

## `:interceptors` in the metadata-map — the superset middle slot (`reg-event`)

For `reg-event`, the metadata-map is the **one superset middle-slot shape**: it carries *reflection* keys (`:doc`, `:schema`, `:tags`, `:platforms`, `:ns`, `:line`, `:file`) **and** a reserved **`:interceptors`** key. Under [EP-0022](../docs/EP/EP-0022-registered-interceptors.md) `:interceptors` is a vector of interceptor **references** (a bare keyword id, or an `[id arg]` parameterized ref) — **not** a vector of inline interceptor maps/values. Each interceptor behaviour is registered once with `reg-interceptor` ([001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)) and referenced here by id; the by-reference chain grammar, override semantics, and standard `:rf.interceptor/path` ref live in [002 §Registered interceptors and the chain grammar](002-Frames.md#registered-interceptors-and-the-chain-grammar). The positional interceptor **vector** middle slot is retired; `[i1 i2]` migrates to `{:interceptors [ref1 ref2]}`. A registration carries *both* reflection metadata *and* an interceptor chain in one map, with one home for the fact. The image descriptor format is this exact shape — its descriptor map *is* this metadata-map.

```clojure
;; the superset form — reflection metadata AND the chain (refs, EP-0022), in one map
(rf/reg-event :cart.item/add
  {:doc          "Add an item to the cart."
   :schema       CartItemAddEvent
   :interceptors [:cart/undoable
                  [:rf.interceptor/path [:cart]]]}   ;; interceptor refs, not inline values
  (fn [{:keys [db]} [_ item]] {:db (update db :items conj item)}))

;; no event interceptors — metadata only
(rf/reg-event :cart.item/add
  {:doc "Add an item to the cart." :schema CartItemAddEvent}
  (fn [{:keys [db]} [_ item]] {:db (update db :items conj item)}))
```

**Retired positional vector.** Supplying a positional interceptor vector — either as the middle slot or after a metadata map — is a loud registration error. The repair is to merge the chain into metadata `:interceptors`. A malformed `:interceptors` value (a non-vector, or a vector carrying an entry that is neither a keyword id nor an `[id arg]` ref) is a loud `:rf.error/reg-event-bad-interceptors`; an inline interceptor **map / value / Var** in a public chain is `:rf.error/inline-interceptor-removed` (register it and reference it by id; per [002 §Event and frame chain grammar](002-Frames.md#event-and-frame-chain-grammar)). There is no `:rf.warning/interceptors-in-metadata-map`: `:interceptors` in the metadata-map is the documented home, not a typo — the silent-drop footgun is structurally gone (the chain is honoured, not dropped).

**A *bare* interceptor is rejected loudly, not silently dropped.** Because an interceptor is a *map* (`{:id … :before … :after …}`), `(rf/reg-event :id mw/some-interceptor (fn …))` used to be read as the metadata-map and the chain never ran (field-confirmed via the rf8 migration). The runtime now throws `:rf.error/reg-event-bare-interceptor` at registration (an ERROR — the chain cannot be honoured and there is no safe continue): a map carrying `:before` / `:after` in the middle slot is the tell. The chain is **not** coerced; the caller writes `(rf/reg-event :id {:interceptors [mw/some-interceptor]} (fn …))`. (The loud-failure sibling of the warning above, per [§No silent swallow](#no-silent-swallow--recognised-input-must-signal).)

This rule is `reg-event`-specific. `reg-frame`'s metadata-map *does* recognise `:interceptors` (per [Spec 002 §`:interceptors` — *add* interceptors to a frame's events](002-Frames.md#interceptors--add-interceptors-to-a-frames-events)) — frames have no positional middle slot, so frame-level interceptors live on the metadata-map by necessity.

**`reg-event` is the single public event-registration form** ([EP-0018](../docs/EP/EP-0018-one-event-registration.md)): coeffects in, a closed effects map out. There is no `reg-event-db` / `reg-event-fx`; `reg-event-ctx` is a framework-internal `context -> context` primitive (interceptors own the public full-context niche). The retired public names raise their naming hard errors (per [001 §The retired event-registration names](001-Registration.md#the-retired-event-registration-names)). The common pure-db handler gains a `:keys [db]` destructure and a `{:db …}` return wrap.

## No silent swallow — recognised input MUST signal

This is the repo-level honest-signal rule the [§`:interceptors` in the metadata-map](#interceptors-in-the-metadata-map--the-superset-middle-slot-reg-event) registration-shape errors above are instances of. It is the normative realisation of [Principles §No silent swallow](Principles.md#no-silent-swallow) — that principle names the *why*; this section is the MUST.

> **Rule.** A user-supplied value that is **recognised as input** but **cannot be honoured** MUST produce a structured warning or error. Silent ignore is allowed **only** for explicitly namespaced extension keys in an extension map.

"Recognised as input" means the value reached a slot the runtime reads — an opts key in a known opts position, an fx-override of a reserved fx-id, a callback whose return the runtime consumes, one of a pair of explicit options. "Cannot be honoured" means the runtime declines to act on it: it's an unknown key, the override is ignored, the return is dropped, the two options conflict. In every such case the runtime MUST signal — a dev-gated `:rf.warning/*` advisory (per [§Error-id and warning-id grammar](#error-id-and-warning-id-grammar)) when the cascade can continue safely, or a `:rf.error/*` when it cannot. **Silence is a dishonest signal**: the caller observes success, the value vanishes, and the defect surfaces far from its cause with no breadcrumb to follow.

### The extension-key carve-out is load-bearing

The single exception — silent ignore of **explicitly namespaced extension keys in an open extension map** — is what makes the rule compatible with [§Reserved namespaces](#reserved-namespaces-framework-owned)' open-map accretion and [Principles §Spec-ulation](Principles.md#spec-ulation). An open map (registration metadata, a machine snapshot's `:data`, a spawn-spec, a frame config) tolerates user-namespaced keys it does not recognise — that is how a producer adds vocabulary without a coordinated breaking change. Those keys are **not "recognised input the runtime declined to honour"**; they are foreign keys the runtime was never asked to interpret. The discriminator is recognition: a key in the framework's known set that the runtime drops is a silent swallow (banned); a user-namespaced key in an explicitly open map that the runtime never claims to read is accretion (allowed). A *bare* or *framework-namespaced* key the runtime does not recognise is on the wrong side of the line — it reads as a typo of a real key and MUST signal (the `:rf.warning/unknown-dispatch-opt` case; and for a malformed `reg-event-*` interceptor chain, the loud `:rf.error/reg-event-bad-interceptors`).

### Pre-alpha is the window to be strict

The rule ships at full strictness: every recognised-but-unhonourable input warns or errors, with no tolerance grace period. The **only** sanctioned future relaxation is an opt-in `:allow-unknown? true` escape hatch on the relevant surface — a caller who wants forward-compatibility against a newer producer's vocabulary can suppress the unknown-key warning for that call. The escape hatch is **not shipped pre-1.0** and is recorded here only to fix the one forward-compat lever so a later relaxation is additive (a new opt) rather than a contract change to the rule itself. Absent the opt, the default is strict.

### Per-surface applications

The rule is cross-cutting; each surface applies it in its own spec and registrar, NOT here. The concrete instances that motivated naming the rule (each fixed in its own change, each an application of this principle):

| Surface | Recognised-but-unhonourable input |
|---|---|
| Core dispatch | unknown opts key in the dispatch opts map (`:rf.warning/unknown-dispatch-opt`) |
| Core fx | reserved-fx fn-override that was silently ignored while `:rf.fx/override-applied` lied that it applied |
| Managed HTTP | `:on-failure nil` swallowing a real (non-aborted) failure with no dev-time signal |
| SSR | conflicting `:payload-keys` + `:payload-policy` explicit opts (consolidated to one `:payload`, fail-closed) |
| State machines | `:on-spawn` callback return silently dropped while the teaching surface implied it was recorded |
| State machines | machine **state-node** / **spawn-spec** keys: a BARE key outside the closed `:rf/state-node` / spawn-spec vocabulary was silently ignored (an XState-trained author's `:invoke` for `:spawn`, `:on-entry` for `:entry` — registration succeeded, the child never spawned / the action never ran). Now hard-rejected: an unknown bare state-node key is `:rf.error/machine-unknown-node-key`, an unknown bare `:spawn` / `:spawn-all`-child key is `:rf.error/machine-unknown-spawn-key` (ERROR, not a warning — `:meta` is the sanctioned bare free slot and namespaced keys stay open, so a bare unknown key has zero legitimate use and the misfiring cascade cannot continue). `:type :history` / `:type :choice` pseudo-states keep their own closed-key-set rejections. And a non-set `:tags` slot is `:rf.error/machine-bad-tags` — the former silent vector/keyword→set **coercion** is removed, mirroring `:internal-events`' set-form hard-reject (naming rule 2: "never a silently-normalised alias"). NAMESPACED user keys (`:myapp/*`) pass (the open-map carve-out). Registration-time only, off the hot path. |
| pair-mcp | `:unknown-tool` error envelope that dead-ended an agent with no `:hint` / `tools/list` pointer |
| Schemas | `reg-app-schema` silently accepting a bare keyword as opts and registering against the default frame |
| Resources | `:rf.resource/load-more` given a non-route `:owner` is `:rf.warning/resource-load-more-owner-ignored` — a WARNING (the run continues safely: the owner is dropped, the page still appends), per [016 §Causal event — load-more](016-Resources.md#causal-event--rfresourceload-more-r2) |
| Core registration | `reg-event` silently dropping a BARE interceptor (a map handed where the positional `[vector]` was required — an interceptor is a map, so it read as the metadata-map and the chain never ran); now rejected loudly with `:rf.error/reg-event-bare-interceptor` (ERROR — the chain cannot be honoured, no `:allow-unknown?` carve-out applies, and the call is not coerced `bare → [bare]`) |
| Core registration | `reg-event` receives a malformed metadata `:interceptors` value or the retired positional interceptor vector middle slot; rejected loudly rather than silently dropping or merging chains. The malformed-value rejection is `:rf.error/reg-event-bad-interceptors`; the retired vector slot is `:rf.error/reg-event-bad-middle-slot` / `:rf.error/reg-event-bad-arity` depending on shape. |
| Core registration | `reg-*` registration-metadata KEYS (the middle-slot map on `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-interceptor`): a BARE key outside the kind's recognised vocabulary is a likely typo and is signalled — an unknown bare key warns (`:rf.warning/unknown-registration-key`, the cascade continues; the key is stored but unread), and a RETIRED v1 bare key (canonically `:spec`, renamed to `:schema` per [MIGRATION §M-54](../migration/from-re-frame-v1/README.md) — silently swallowing it disabled payload validation) hard-errors (`:rf.error/retired-registration-key`, naming the canonical replacement). NAMESPACED extension keys (`:myapp/*`) are the open-map carve-out and pass silently. The three-way classifier is registration-time only (off the hot path); it mirrors `reg-route`'s `:rf.error/route-bad-metadata` guard and `re-frame.image`'s retired-key redirect. |

New surfaces apply the rule by mechanism: if a recognised input cannot be honoured, signal it — and reach for the extension-key carve-out only when the dropped key is a user-namespaced key in an explicitly open map. A surface that seems to *need* silent-ignore of a recognised input is evidence of a missing warning, not an exception to the rule — file a bead against the owning spec rather than swallowing.

## Event payloads SHOULD be serialisable data

**CAUSAL TOKENS ARE VALUES** applies to two slots: the envelope's `:rf.cofx` map (host facts folded into a write) and the dispatched event's own payload. `:rf.cofx` is a MUST — [002 §Recordable coeffects](002-Frames.md#recordable-coeffects) structurally validates every supplied/generated fact as EDN, always-on, a hard error in production too (`:rf.error/cofx-value-invalid`). The event payload is the sibling slot, but it is a **SHOULD**, not a MUST:

> **Rule.** Event vectors SHOULD contain recordable, serialisable data — plain EDN (keywords, strings, numbers, booleans, nil, maps, vectors, sets), never a host handle (a function, a `Promise`/`AbortController`, a DOM node, a `Date`, a `RegExp`, or their JVM counterparts).

**Why a SHOULD, not a MUST.** Two carve-outs the framework deliberately keeps open forbid promoting this to an always-on structural gate: an event schema declared `:any` legitimately admits anything (including a callback an app author chooses to carry, at their own replay risk), and ad-hoc CLJS test payloads routinely thread a bare fn or atom through an event vector as a quick assertion hook. A hard MUST would break both. The SHOULD keeps the discipline visible without foreclosing them.

**Why it matters anyway.** re-frame2's client kernel already owns ~90% of what a local-first / offline architecture needs (EP-0017 recordable cofx, O(1) frame-state restore, id-valued fx suppression under replay — see [000-Vision §Goal #10 dispositions](000-Vision.md#goal-10-dispositions-real-spa-concerns-scope)) — and the one door-holding invariant such an architecture would need is that a dispatched event is a **value**: a deterministic event journal (the mutation-intent events + their `:rf.cofx` envelopes) is only replayable, diffable, and transportable if every event in it is recordable data. A non-serialisable payload doesn't just risk an SSR / replay / epoch-history footgun today; it forecloses that seam tomorrow.

**The dev-mode lint.** `:rf.warning/non-serialisable-event-payload` — a dev-only advisory the router emits when a dispatched event's payload carries a host handle, walking the SAME closed detection set the reply-map / reply-target data-only invariant uses ([Managed-Effects §The reply map](Managed-Effects.md#the-reply-map), `re-frame.reply/host-handle?`). WARNING, not an error: the dispatch proceeds unchanged (`:recovery :no-recovery`) — permissive `:any` payloads and ad-hoc test payloads still rely on it. Bounded (a node-visit budget caps a pathological deeply-nested payload so the lint itself cannot become a hot-path cost) and dev-only (`interop/debug-enabled?`-gated, DCE'd in production, same posture as every other `:rf.warning/*` diagnostic). Catalogued at [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue). Cross-referenced from [002 §The `:rf.cofx` envelope field](002-Frames.md#the-rfcofx-envelope-field) (the sibling MUST) and [000-Vision §Goal #10 dispositions](000-Vision.md#goal-10-dispositions-real-spa-concerns-scope) (the Local-first / sync disposition this SHOULD clause makes coherent).

## Facade policy — a facade exports the front porch, the workshop lives behind a door

Sibling governance rule to [§No silent swallow](#no-silent-swallow--recognised-input-must-signal): that rule keeps a surface *honest*; this one keeps a facade *legible*.

re-frame2 has three facade namespaces — `re-frame.core` (the framework), `re-frame.story` (the stories library), and `day8.re-frame2-xray.core` (the Xray devtool). Each one is the single namespace a consumer requires to use its product. Left ungoverned, each draws the line between "what a newcomer needs first" and "what a power user reaches for occasionally" by accretion — and they draw it differently. The failure mode is a front porch with the workshop, the fuse box, and three oscilloscopes bolted to it where a newcomer expected a doorbell: the one var they need is buried among forty they will never call, and the namespace stops telling them where to start.

> **Rule.** A facade leads with **front-porch** surfaces — the vars a typical consumer reaches for in ordinary use. Advanced and tooling surfaces *default* to **explicit subnamespaces** (`re-frame.<area>` / `re-frame.story.<area>` / `day8.re-frame2-xray.<area>`) rather than the facade, **unless a deliberate single-import re-export is justified** (per [§The ruling](#keep-product-facades-coherent-no-wholesale-tier-equals-namespace-split) below). **A subnamespace require is itself a signal** — typing `(:require [re-frame.tooling])` is the consumer announcing "I am in advanced territory now", which a bare `re-frame.core` require never has to whisper; the policy's job is to keep that signal meaningful, not to forbid every advanced var from the facade.

The tier vocabulary that classifies a surface — `front-porch` | `advanced` | `tooling` | `adapter` | `testing` | `internal-public` | `implementation` | `deprecated` (eight values) — is owned by [API.md](API.md); this section governs *placement against* those tiers, not the taxonomy itself.

**The `Tier` column is a documentation/review tier, not a namespace boundary.** The `Tier` value on a surface's API.md row (the field the downstream manifest consumes — per [API.md §Tier taxonomy](API.md#tier-taxonomy)) records *who reaches for it* so the Guide, the skills, and a reviewer know what to foreground. It is **not** an instruction that every `advanced` or `tooling` surface must live in its own namespace. Namespaces split around **dependency shape, product workflows, and reader expectations** — not by mechanically mapping one tier to one namespace. A wholesale tier-equals-namespace carve-up is rejected (see the ruling below): it would scatter one product's app-authoring vocabulary across several requires and tend to silt up a vague `re-frame.runtime` junk drawer that means "everything that isn't front-porch", which is exactly the legibility loss this section exists to prevent.

**The diff-time obligation — classify and justify each new facade export when it lands.** This rule is **active now** (it does not wait for the deferred audit below). A diff that adds a public var to a facade namespace MUST, in the same change (its PR), record the var against the same four fields the [pre-release facade audit](#keep-product-facades-coherent-no-wholesale-tier-equals-namespace-split) will eventually collect into a manifest table — so the diff-time entry is a forward-fill of that manifest, not a separate ritual:

1. **Tier** — name the var's tier per the API.md taxonomy above (`front-porch` | `advanced` | `tooling` | `adapter` | `testing` | `internal-public` | `deprecated`).
2. **Owner spec** — name the spec document that owns the surface's contract (the artefact home it belongs to — e.g. 005 for a machines surface, 010 for a schemas surface, 011 for SSR), per [Ownership.md](Ownership.md).
3. **Facade-placement justification** — state why a `front-porch` classification earns a facade export, or why an `advanced` / `tooling` surface is nonetheless on the facade rather than behind a subnamespace door. "It was convenient" is not a justification; "every consumer calls it on their first hour" is.
4. **Recommended action** — record the placement verdict the eventual audit will inherit: **keep** (front-porch, stays on the facade), **rename** (verb/spelling fix per the [lifecycle-verb law](#lifecycle-verb-law--a-closed-verb-vocabulary-for-naming-lifecycle-and-facade-apis) below), **move** (belongs behind a subnamespace door at audit time), or **internal-public** (re-exported for one consumer / the reference, not a consumer-facing surface).

A var that classifies as anything but `front-porch` and lands on the facade **without these four fields stated** is a reviewable defect, not a style nit — it is the accretion this rule exists to stop, caught at the one moment (the diff) when the cost of moving it is a one-line require change rather than a consumer-visible break. A *justified* non-front-porch export (an intentional single-import re-export, per the ruling below) is not a defect — the defect is the *silent* one. This diff-time gate is the **forward half** of the facade audit: the full retrospective sweep of every existing non-front-porch export — and the manifest table it produces — stays deferred to API-freeze / external-alpha prep (see the audit obligation below), but every *new* export classifies itself as it lands so that sweep never has to reconstruct intent after the fact.

### Keep product facades coherent; no wholesale tier-equals-namespace split

There is **no wholesale tier-equals-namespace carve-up**. Product facades stay coherent on purpose. The policy above (front-porch on the facade; classify-and-justify each facade addition at diff time) holds; what it does *not* license is splitting a product's surfaces across many namespaces merely because the `Tier` column says they are not all `front-porch`.

**Single-import product facades are kept coherent on purpose.** `re-frame.core`'s single-import ergonomics for the app-authoring surfaces — machines, routes, flows, schemas, frames, dispatch, subscribe — are *valuable*. A developer building a normal app reaches for vocabulary across several of these in one sitting; making them learn and require several namespaces to assemble an ordinary app is a worse experience than a somewhat large `rf` facade. The legibility this section protects is the *front-porch-vs-workshop* line **within** a coherent product facade, not a mandate to shard the facade itself.

**Existing non-front-porch facade exports are intentional exceptions, not quiet violations.** `re-frame.core`, `re-frame.story`, and `day8.re-frame2-xray.core` today re-export a number of surfaces an API.md row classifies as `advanced` or `tooling`. Under this ruling those are **deliberate single-import re-exports**, pending the audit below — they are *recorded exceptions*, not silent policy breaches to be flagged one-by-one. The diff-time classify-and-justify obligation above still applies to *new* facade additions; it is the forward gate, while the standing exports are grandfathered into the audit.

**The audit obligation — before the first meaningful external release.** Before re-frame2's first meaningful external **alpha / beta** release — **not** "before v1" — every non-front-porch `re-frame.core` (and, by extension, facade) export is reviewed and either **moved** to an existing feature / tool namespace or **documented as an intentional single-import re-export** with its justification. The deadline is the *external release*, not v1, because the moment real users copy examples and require the facade, a later namespace move becomes a breaking change for them even while the project is still pre-v1. The audit is the one cheap window to settle placement; after it, moves cost consumer breakage.

**Story / Xray nuance — tooling is their front porch.** `re-frame.story` and `day8.re-frame2-xray.core` are tooling **products**. For them, "tooling" surfaces can legitimately *be* the front porch — a Story user's everyday vocabulary is the story-authoring and evidence surfaces, even though the global tier classification calls them `tooling`. Splitting those facades into subnamespaces (e.g. `re-frame.story.testing` / `re-frame.story.evidence` / `re-frame.story.fingerprint`) is justified only when the subdomains are **genuinely separate workflows** a user enters at different times — *not* merely because the global `Tier` column says `tooling`. The discriminator is the user's workflow, not the tier label.

This ruling fixes the *policy*; the facade audit above is the *application*, deferred to its own pre-release pass. Nothing here mandates a particular subnamespace, and nothing here licenses sharding a coherent product facade to satisfy the tier column.

### Removing or demoting a facade export — delete, don't demote (pre-alpha)

The diff-time obligation above is the **add** side: it governs what goes *onto* a facade. This is its **symmetric half** — what to do when a facade export is superseded (an EP replaced it) or turns out to have been an accidental/internal export that never belonged on the front porch. The answer is sharp: **delete or move it; never leave it as a permanent demoted export.** A facade that keeps superseded vars around "for migration" accretes exactly the stale public surface the add side works to prevent — and demotion-by-docstring alone does not remove that surface, it only annotates it.

> **Rule.** A superseded or accidental public facade export has exactly **two** fates, never three. Ask one question: *is it kept because the live internal design genuinely uses it, or kept only because old callers would break?*
>
> - **(a) genuinely used internally** → it becomes **internal**. Move it to its own namespace at tier `implementation` (or `internal-public` *only* for a deliberate, supported host/tool embed seam), and it **leaves the facade**: dropped from the export list (the `def` off `re-frame.core` / the product facade), dropped from its `:facade? true` manifest row, and dropped from the [API.md](API.md) current rows.
> - **(b) kept only for back-compat** → **removed outright.** There is **no retained-for-migration facade tier**; the `deprecated` tier stays empty in pre-alpha (per [API.md §Tier taxonomy](API.md#tier-taxonomy), removed surfaces live in [API.md §Removed / not shipped](API.md#removed--not-shipped), not as demoted exports).

**The disposition must fire on the surface, not just the docstring.** A demotion is only real when it lands on all three of: the **export list** (the `def`/re-export off the facade namespace), the **api-manifest rows**, and the **[API.md](API.md) rows** — not merely the var's docstring. A docstring that says "internal, do not use" while the var still re-exports from `re-frame.core`, still carries a `:facade? true` manifest row, and still appears in the API.md current tables is the precise failure mode this rule names: a documented demotion that left the public surface intact. Annotation is not removal.

**The one legitimate retention class.** A removed name may be retained *only* as a `^:no-doc` throwing stub — a var under the old name whose sole behaviour is to raise a loud, actionable "this was removed; use X" error (per the retired-name hard-error convention). That is a **DX choice, not a compat tier**: it converts a silent `Unknown var` into a guided failure, is driven from a single removed-names data table rather than `N` bespoke throwers, and carries **no manifest row** (the manifest generator drops `^:no-doc` vars, so a correctly-resolved removal produces no `:facade? true` row to flag). Migration **data or diagnostics**, if any are retained at all, live behind their own `re-frame.migration` namespace — they are **never** re-exported from `re-frame.core`.

**Who rules.** The same diff-time bar as additions: the PR that supersedes or internalises a surface **classifies its disposition** (which fate, and why) in the same change. A mechanical "this is dead, delete it" needs no escalation; a genuine *"is this still used internally?"* judgment call escalates to the operator, exactly as a contested tier on the add side does.

This rule is **enforced by the public-facade manifest-hygiene CI check** (the guardrail that joins each `:facade? true` manifest row against its disposition and fails on a faceted row that has been superseded), the symmetric enforcement partner to the api-manifest drift-check that backs the add side.

## Lifecycle-verb law — a closed verb vocabulary for naming lifecycle and facade APIs

Sibling governance rule to [§Facade policy](#facade-policy--a-facade-exports-the-front-porch-the-workshop-lives-behind-a-door): that rule decides *where* a surface lives; this one decides *what it is called*. A new lifecycle or facade API should be nameable by following the law, not by taste — so two authors adding symmetric teardown surfaces a year apart reach the same verb.

The law is a **closed verb vocabulary**. Each verb owns one shape of operation; the discriminator is mechanism, not feel. The [§Tear-down verb axis](#tear-down-verb-axis--clear--vs-destroy-) and the [§Naming — bang suffix](#naming-when-does-a-surface-carry-) sections are the in-depth treatment of two slices of this law (`clear-`/`destroy-` and the `!` axis respectively); this section is the **complete closed roster** they sit inside.

| Verb | Owns | Mechanism (the discriminator) |
|---|---|---|
| `clear-*` | Drop registrations or caches. | Removes id(s) from a registry the process owns, or drops a process-local cache / buffer outright. Symmetric inverse of `reg-*`. Detail at [§Tear-down verb axis](#tear-down-verb-axis--clear--vs-destroy-). |
| `unregister-*` | Remove **one** thing by id. | Single-id removal from a global listener-style table where `clear-*` would read as "drop the whole registry". Used where the registration is a hook, not a registrar entry (`unregister-listener!`). |
| `destroy-*` | Tear down an **instance** plus the resources it owns. | The target has **identity and a creation moment**; teardown invalidates downstream consumers and releases per-instance machinery (`destroy-frame!`, `destroy-adapter!`). Lifecycle symmetry with the creating call. Detail at [§Tear-down verb axis](#tear-down-verb-axis--clear--vs-destroy-). |
| `reset-*` | Keep identity, return to baseline. | The instance survives; its state is wound back to initial. Distinct from `destroy-*` (identity is **retained**, not removed). The facade currently has no dedicated `reset-*` occurrence: the former `reset-app-db!` (app-db partition → `{}` while frame identity, runtime-db, sub-cache, and queue survive) was consolidated into `replace-frame-state!`'s partial-map contract — `(replace-frame-state! id {:rf.db/app {}})` (rf2-t3lftq, API-shrink #3) — because a single-purpose reset call carried no mechanism distinct enough from "replace this partition with an empty map" to earn a dedicated verb, mirroring the reasoning that already retired the compound `reset-frame!`. A WHOLE-FRAME reset is a **named composition**, not a single verb — `destroy-frame!` then re-`reg-frame` under the same id (rf2-lxwpob retired the compound `reset-frame!` that used to spell this: a composition with no atomicity contract of its own does not earn a dedicated verb). |
| `unsubscribe` | Release one ref-count. | Decrements a live ref-count on a cache entry (the inverse of `subscribe`, **not** of `reg-sub`). A carved-out singleton — `un-` is a one-element set, not a generalisable prefix. Detail at [§Carve-out: `unsubscribe`](#carve-out-unsubscribe). |
| `watch-*` | Start observing; **return a 0-arity stop fn**. | Begins an observation that runs until stopped; the **return value is the stop handle** — `(let [stop (watch-x …)] … (stop))`. There is **no `unwatch-*`** verb: the stop fn closes over exactly what it must tear down, so a separate id-keyed unregister surface is redundant. |
| `attach!` / `detach!` | Add / remove a **listener** to an existing thing. | A listener bound to a host or framework surface, paired symmetrically. The `!` marks the process-level mutation per [§Naming](#naming-when-does-a-surface-carry-). |
| `mount!` / `unmount!` | Put a UI shell **into / out of the DOM**. | A DOM or render-shell lifecycle — the component or panel enters / leaves the document. Paired; `!` per [§Naming](#naming-when-does-a-surface-carry-). |
| `open!` / `close!` | Toggle **visibility**, not lifecycle. | Shows / hides an already-mounted surface. **Not** a teardown verb: `close!` leaves the instance alive and re-`open!`-able; a closed panel still exists. Reach for `destroy-*` / `unmount!` when the thing actually goes away. |

**The boundaries that earn their keep.** Three pairs of verbs sit close enough to be confused; the law fixes which is which:

- `destroy-*` vs `reset-*` — both rewind state, but `destroy-*` **removes the identity** (the id no longer resolves) while `reset-*` **keeps it** (same id, baseline state). A frame whose `app-db` you reset via `(replace-frame-state! id {:rf.db/app {}})` is still addressable and running; a frame you `destroy-frame!` is gone. A WHOLE-frame reset is the `destroy-frame!` + re-`reg-frame` composition, not a single verb.
- `close!` vs `unmount!`/`destroy-*` — `close!` is **visibility** (the thing is hidden but intact and reopenable); `unmount!`/`destroy-*` is **lifecycle** (the thing leaves the DOM / is torn down). A modal you `close!` keeps its state for the next `open!`; one you `unmount!` does not.
- `watch-*`'s stop-fn vs an `unwatch-*` that does not exist — observation teardown rides the **returned stop fn**, never a separate id-keyed verb. If you find yourself wanting `unwatch-x`, you wanted to capture and call the stop fn `watch-x` already handed you.

### `configure!` (mutation) vs `describe-config` / `current-config` (reads) — resolving the bang inconsistency

The law extends to the **configuration surface**, and in doing so resolves an inconsistency the three facades carry today. The config-**mutation** verb is `configure!` — it ends in `!` because it mutates a process-level slot (per [§Naming](#naming-when-does-a-surface-carry-) bucket 2/3), exactly like `attach!` and `mount!`. The config-**read** verbs are `describe-config` (the declared shape / schema of the config surface) and `current-config` (the live values now in effect) — no bang, because reads mutate nothing.

| Surface | Verb | `!`? | Owns |
|---|---|---|---|
| Mutate config | `configure!` | yes | Set process-level / tool-level config knobs. |
| Read the config **shape** | `describe-config` | no | The declared key set + value shapes the surface accepts. |
| Read the **live** config | `current-config` | no | The values currently in effect. |

**Config mutation takes the bang.** Config mutation is a process-level mutation, so it takes the `!` the rest of the bang axis mandates for that mechanism: it is **`configure!`** across `re-frame.core`, `re-frame.story`, and `day8.re-frame2-xray.core` (e.g. `(story/configure! {…})`, `(xray-config/configure! {…})`). The read pair `describe-config` / `current-config` is the no-bang counterpart on every facade. The [§Configuration surfaces](#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) bucketing applies to `configure!` for bucket 1.

### How to name a new lifecycle or facade surface

Ask, in order:

1. Does it **drop registrations / a cache**? → `clear-*`. **Remove one hook by id**? → `unregister-*`.
2. Does it **tear down an instance + its owned resources** (identity goes away)? → `destroy-*`. **Wind an instance back to baseline** (identity stays)? → `reset-*`.
3. Does it **release one ref-count** on a cache entry? → `unsubscribe` (the carve-out — do not coin a new `un-*`).
4. Does it **start an observation**? → `watch-*`, **returning a 0-arity stop fn** (never an `unwatch-*`).
5. Is it a **listener** add/remove? → `attach!` / `detach!`. A **DOM/shell** in/out? → `mount!` / `unmount!`. A **visibility** toggle? → `open!` / `close!`.
6. Is it **config mutation**? → `configure!`. A **config read**? → `describe-config` (shape) / `current-config` (live values).

The roster is **closed**: a surface that fits none of these is evidence the law is missing a verb — file a bead against this section rather than coining `dispose-` / `teardown-` / `shutdown-` / a fresh `un-*`. Adding a verb is a Spec change to this table, not a per-author call.

## Implementation note — persistent data structures

Conformant implementations need a structural-sharing persistent collection library for `app-db` and frame state. CLJS gets this free; other in-scope JS-cross-compile-language ports pick a host-idiomatic library (Immer or Immutable.js for TypeScript / Squint; im.kt or `kotlinx.collections.immutable` for Kotlin/JS; native PDS from the source language for Fable (F#) / Scala.js / PureScript / Melange / ReScript / Reason). For the per-host options, why this is pattern-required, and how it composes with [Goal 2 — Frame state revertibility](000-Vision.md#frame-state-revertibility), see [000-Vision §Host-profile matrix — Note on persistent data structures](000-Vision.md#note-on-persistent-data-structures).

## Naming: when does a surface carry `!`?

The bang (`!`) suffix on a public surface marks **process-level state mutation that the registrar abstraction does not already own**. The rule is principled, not stylistic, and slots every framework surface into one of four buckets. New surfaces pick their bucket by mechanism, not by feel.

### 1. Registry-shaped registrations — **no bang**

`reg-*` and `clear-*` mutate the registrar, but the registrar IS the side-effect abstraction. Calling `reg-event` to install a handler is no more "imperative" than calling `defn` — the verb's whole purpose is to extend a registry. Adding a bang would tag every registration in the framework, which is the opposite of useful signal.

- `reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-frame`, `reg-flow`, `reg-route`, `reg-machine`, `reg-app-schema`, `reg-view`, `reg-view*`, `reg-head`, `reg-error-projector`, `reg-http-interceptor`
- `clear-event`, `clear-sub`, `clear-fx`, `clear-flow`, `clear-route`, `clear-http-interceptor`, `destroy-frame!`

```clojure
(rf/reg-event :cart.item/add  (fn [{:keys [db]} [_ item]] {:db ...}))   ;; no bang
(rf/clear-event :cart.item/add)                                         ;; no bang
```

### 2. Listener registrations — **bang**

The caller hands a fn to a global hook the framework will invoke from arbitrary call sites. This is **not** a registrar-shaped operation — the listener table is a process-level mutable slot the surface mutates directly — so the bang earns its keep.

- `register-listener!`, `unregister-listener!` (the epoch drain-settle listener is the `:epoch` stream of this verb)
- `register-observability-sink!`, `unregister-observability-sink!` (runtime sink/listener installation — NOT a declarative `reg-*` registrar entry)

```clojure
(rf/register-listener! ::audit  (fn [event] ...))           ;; bang — hooks a global
(rf/unregister-listener!   ::audit)
```

### 3. Adapter / platform installation — **bang**

Process-level state mutation outside the registrar — installing or tearing down the runtime's substrate adapter, swapping in a different schema validator, dropping the subscription cache. These surfaces touch implementation-defined slots that have nothing to do with the per-frame registries.

- `install-adapter!`, `dispose-adapter!`
- `set-schema-validator!`, `set-schema-explainer!`
- `clear-sub-cache!`

```clojure
(rf/install-adapter!     reagent-adapter/adapter)           ;; bang — installs runtime
(rf/set-schema-validator! my-validator-fn)                  ;; bang — swaps a global
```

### 4. Dispatch and subscribe — **no bang**

`dispatch` / `subscribe` are frame-relative side-effects, but the side-effect IS the program's normal mode of operation. Banging them would noise every domino call site in every event handler and every view. This is the "IO is the program" exemption — the same reason `defn` doesn't end in `!` despite being a top-level effect.

- `dispatch`, `dispatch-sync`, `dispatch-later`
- `subscribe`, `unsubscribe`

```clojure
(rf/dispatch  [:cart.item/add {...}])                       ;; no bang
@(rf/subscribe [:cart/items])                               ;; no bang
```

### How to slot a new surface

When adding a public surface, ask in order:

1. Does it extend a registry by id? → bucket 1 (no bang).
2. Does it install a fn into a global listener slot? → bucket 2 (bang).
3. Does it mutate process-level state outside the registrar? → bucket 3 (bang).
4. Is it a domino-shaped side-effect — dispatch, subscribe, drain? → bucket 4 (no bang).

The four buckets are exhaustive for the surfaces in [API.md](API.md). The `register-listener!` rename rationale (no-bang → bang once the listener-registration shape was recognised) is recorded at [API.md §Removed / not shipped](API.md#removed--not-shipped). Surfaces that genuinely don't fit are evidence of a missing bucket — file a bead against this section rather than coining a fifth shape.

## Tear-down verb axis — `clear-` vs `destroy-`

The bang axis above answers *whether* a tear-down surface carries `!`. The **verb axis** answers *which verb the surface uses*. The taxonomy is two-valued over registrar/cache decrements and lifecycle teardown: `clear-` for in-process registrar / cache / buffer decrements, `destroy-` for lifecycle-boundary teardown. Listener / DOM / visibility teardown verbs (`unregister-*`, `detach!`, `unmount!`, `close!`) are governed by the lifecycle-verb law above, not this axis. Every surface on this axis picks exactly one verb — except for one carved-out singular case (`unsubscribe`) explained below. New surfaces pick their verb by mechanism, not by feel.

### `clear-*` — registrar / cache / buffer decrement (in-process)

Symmetric inverse of `reg-*`. Removes an id from a registry the process owns (event registrar, sub registrar, fx registrar, flow registrar, route registrar, http-interceptor registrar) or drops a process-local cache / buffer outright.

- Single-id decrement, registry-shaped: `clear-event`, `clear-sub`, `clear-fx`, `clear-flow`, `clear-route`, `clear-http-interceptor` (bucket 1, no bang)
- Drop-everything, process-level: `clear-sub-cache!`, `clear-trace-buffer!` (bucket 3, bang)

### `destroy-*` — lifecycle boundary

Tears down something with **identity and a creation moment** — a frame, an adapter installation. The pair `(reg-frame …)` / `(destroy-frame! …)` and `(install-adapter! …)` / `(destroy-adapter!)` are lifecycle symmetries: the second call invalidates downstream subscribers, releases per-instance machinery, and (for the adapter) flips the `adapter-disposed?` breadcrumb.

- `destroy-frame!`
- `destroy-adapter!`

### `destroy-adapter!` sits on this axis

Adapter teardown is `destroy-adapter!`: adapter installation is a lifecycle boundary, symmetric with `destroy-frame!`. There is no `dispose-*` verb on the public surface and no alias; stale `dispose-adapter!` call sites raise an unresolved-symbol at compile time. Per the [Migration corpus M-53](../migration/from-re-frame-v1/README.md#m-53-tear-down-verb-rename--dispose-adapter--destroy-adapter) for the v1→v2 mapping. (The adapter-spec map key `:dispose-adapter!` is an internal contract slot and is unchanged — only the public wrapper name moves.)

### Carve-out: `unsubscribe`

`unsubscribe` and `clear-sub` are distinct names. `clear-sub` is the symmetric inverse of `reg-sub` — the **registrar** decrement that removes a registration. The two operations are semantically distinct:

| Surface | Decrements | Symmetric with |
|---|---|---|
| `clear-sub` | the sub **registrar** (the registered handler fn for an id) | `reg-sub` |
| `unsubscribe` | the sub **cache** (a live ref-count for a `query-v` shape) | `subscribe` |

Collapsing them would conflate registration with caching. The `un-` prefix is therefore carved out as the singular form for the cache ref-count decrement. Rule of thumb: if the framework grows another `un-*` surface, you have probably misread the axis — `un-` is a one-element set.

A WHOLE-frame reset is **not** part of the tear-down axis and is **not** a dedicated verb (rf2-lxwpob retired the compound `reset-frame!`) — it is `destroy-frame!` followed by `reg-frame` re-using the prior frame's id, composed by the caller from the two axis verbs above. A partition-scoped baseline-reset (app-db → `{}`, keeping the frame alive throughout) is a DIFFERENT, narrower concern from a destroy+recreate compound — it is expressed as `(replace-frame-state! id {:rf.db/app {}})` rather than a dedicated `reset-*` verb (rf2-t3lftq, API-shrink #3 retired the former `reset-app-db!`); see [§Lifecycle-verb law](#lifecycle-verb-law--a-closed-verb-vocabulary-for-naming-lifecycle-and-facade-apis).

### How to slot a new tear-down surface

When adding a new tear-down surface, ask:

1. Does it release a registered id, or drop a process-local cache / buffer? → `clear-*`.
2. Does it tear down something with identity and a paired creation moment? → `destroy-*`.

If neither fits, the surface is evidence the axis is missing a bucket — file a bead against this section rather than reaching for `dispose-` / `reset-` / `un-` / `remove-`. (`un-` is reserved for `unsubscribe`'s carve-out and does not generalise.)

## Configuration surfaces: `configure!` vs `set-!` vs per-frame metadata

re-frame2 has three orthogonal configuration surfaces. The user-facing question "where do I configure X?" depends on the **lifetime** of X and on whether the consumer needs to hand the framework a specific **implementation reference** (a function or component) versus just a keyword/value setting. The three buckets are exhaustive; every framework-owned config option slots into exactly one. New options pick their bucket by mechanism, not by feel. The mutation verb is `configure!` and the read pair is `describe-config` / `current-config` per [§Lifecycle-verb law — `configure!` vs reads](#configure-mutation-vs-describe-config--current-config-reads--resolving-the-bang-inconsistency).

### 1. `(rf/configure! {key opts})` — process-level runtime knobs

For knobs that apply globally to the framework runtime, are addressed by a **keyword** (no impl-reference required), and whose values are plain data (numbers, booleans, small maps). `configure!` takes a **single nested map** keyed by these top-level keywords; a missing top-level key leaves that subsystem untouched. The full key vocabulary is enumerated at [API.md §Configure keys](API.md#configure-keys) and is fixed-and-additive.

- `(rf/configure! {:epoch-history {:depth 50}})` — ring-buffer depth for the Tool-Pair epoch surface
- `(rf/configure! {:trace-buffer {:events-retained 200}})` — retained event-slot count for trace events (one slot per event / pipeline run)
- `(rf/configure! {:elision {:rf.size/threshold-bytes 16384}})` — wire-elision size threshold
- `(rf/configure! {:epoch-history {:depth 50} :trace-buffer {:events-retained 200} :elision {:rf.size/threshold-bytes 16384}})` — all three composed in one value

The opts-map sub-keys mix two shapes: cross-surface policy slots use a namespaced keyword under the area's reserved `:rf.<area>/*` sub-namespace (e.g. `:rf.size/threshold-bytes` — the same key the wire-elision walker reads); one-off per-knob settings stay bare (e.g. `:depth`, `:grace-period-ms`). The rule is closed and the discriminator is **whether the sub-key names a cross-spec policy slot or a one-off knob** — full statement at [API.md §Configure keys — Opts-key naming rule](API.md#opts-key-naming-rule).

### 2. `set-!` / `install-!` fns — adapter-pluggable hooks

For substitution points where the consumer hands the framework a **specific implementation** (a function or component) that the framework will hold a strong reference to and call from arbitrary sites. The bang earns its keep because the surface mutates an implementation-defined process-level slot (per [§Naming](#naming-when-does-a-surface-carry-) bucket 3).

- `(rf/install-adapter! reagent/adapter)` — install the reactive-substrate adapter
- `(rf/set-schema-validator! malli.core/validate)` — swap the schema validator
- `(rf/set-schema-explainer! malli.core/explain)` — swap the schema explainer

These are NOT folded under `configure` because keyword-keyed addressing loses the type information that the consumer needs to pass an actual fn/component reference: `configure` is for *data*, `set-!` is for *impls*.

### 3. Per-frame metadata — frame-scoped overrides

For configuration whose lifetime is a single frame's existence — expressed at frame creation via `reg-frame`'s metadata map or per-dispatch via the `dispatch` opts argument (per [002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides)). These keys flow through the dispatch envelope; per-call merges over per-frame on key conflict.

- `:fx-overrides` — replace registered fx handlers by id, for the lifetime of one frame (or one dispatch)
- `:interceptor-overrides` — replace interceptors in the chain by `:id`
- `:interceptors` — *add* (prepend) interceptors to the chain
- `:initial-events` — ordered setup events dispatched synchronously at frame creation (seed app-db via a leading `[:rf/set-db {…}]` step)
- `:on-destroy` — lifecycle event fired before frame teardown
- `:ssr {:public-error-id ... :dev-error-detail? ...}` — SSR error-projection policy (per [011](011-SSR.md))

### How to slot a new config option

When adding a new configuration surface, ask in order:

1. Does it hand the framework a fn or component the framework must hold by reference? → bucket 2 (`set-!` / `install-!`).
2. Is it a global runtime knob with a plain-data value? → bucket 1 (`configure!`).
3. Does it apply only to a specific frame's lifetime (or a single dispatch)? → bucket 3 (per-frame metadata via `reg-frame` or dispatch opts).

If the option seems to want two buckets, the option is doing two things and should be split. If it fits none, file a bead against this section rather than coining a fourth surface.

## `*`-suffix naming for fn-versions of macros

When a macro has a fn-version (the unsweetened, runtime-callable surface) that is NOT reachable under the macro's own name (see Convention A below), the fn gets a `*` suffix. Standard Clojure idiom — `let` / `let*`, `fn` / `fn*`. The macro is the ergonomic surface (parses extra shapes, captures source-coords from `&form`, defs Vars, injects locals, per-element source-coord walks, or defn-shape expansion); the `*`-fn is the plain-fn delegate for a non-literal body, a computed id, registration without the macro tier, or higher-order use.

The current pairs:

| Macro (ergonomic) | Fn (`*` form) | Spec |
|---|---|---|
| `reg-view` | `reg-view*` | [004 §reg-view*](004-Views.md#reg-view--the-plain-fn-escape-hatch) |
| `reg-machine` | `reg-machine*` | [005 §reg-machine vs reg-machine*](005-StateMachines.md) |
| `->interceptor` | `->interceptor*` | [001 §Source-coordinate capture](001-Registration.md#source-coordinate-capture-cljs-reference) — definition-site coord stamping. Framework-**internal** lowering constructor only (EP-0022): the public application-authoring form for interceptors is `reg-interceptor`, and `->interceptor` values MUST NOT appear in a public event/frame `:interceptors` chain (which carries refs). |

(`inject-cofx` / `inject-cofx*` are **removed** — EP-0017; coeffect consumption is declared with the `:rf.cofx/requires` registration-metadata key, not invoked at a call site. See [001 §`inject-cofx` is removed](001-Registration.md#inject-cofx-is-removed).)

Future macros that want fn partners follow the same convention.

The convention applies **only where adding the `*` partner buys something** the macro's own-name Convention-A alias (below) cannot — per-element source-coord walks (`reg-machine`), defn-shape expansion (`reg-view`), or the framework-internal-only status of a lowering constructor (`->interceptor`). Where a plain HoF / programmatic caller only ever needs "the same operation, no call-site capture," the macro's own name carries the fn value too (Convention A) — a separate `*`-suffixed Var would be a pure redundant twin. rf2-m90brg retired exactly that redundancy: `dispatch*` / `dispatch-sync*` / `subscribe*` / `reg-interceptor*` are GONE from the `re-frame.core` facade (no back-compat alias, pre-alpha) — `dispatch` / `dispatch-sync` / `subscribe` / `reg-interceptor` moved onto Convention A alongside `reg-event` / `reg-sub` / etc.; a JVM programmatic caller reaches the owning ns fn directly (`re-frame.router/dispatch!` / `-dispatch-sync!`, `re-frame.subs/subscribe`, `re-frame.interceptor-registry/reg-interceptor*` — that owning-ns fn keeps its OWN `*` name per its OWN home namespace's convention; only the `re-frame.core` facade re-export is gone).

**Coverage is asymmetric on purpose.** A reader sees `reg-view*` / `reg-machine*` / `->interceptor*` and may infer a uniform convention; reaching for `reg-event*` (or, since rf2-m90brg, `dispatch*` / `subscribe*` / `reg-interceptor*`) then fails to resolve. The asymmetry is principled (only the macros above have a reason for a facade-level `*` partner) but easy to misread — surface this footnote when documenting new `reg-*` rows in [`spec/API.md`](API.md) §Registration.

### Convention A — same-name CLJS value-alias (no `*` twin)

For `reg-event`, `reg-sub`, `reg-fx`, `reg-cofx`, `reg-frame`, `reg-flow`, `reg-route`, `reg-app-schema`, `reg-app-schemas`, `reg-interceptor`, `dispatch`, `dispatch-sync`, and `subscribe`, the CLJS fn-alias lives under the macro's **own name** (per `re-frame.core` CLJS aliases): the macro stamps source-coords from `&form` on JVM (and captures `:rf.trace/call-site` for `dispatch` / `dispatch-sync` / `subscribe` specifically, per [009 §`:rf.trace/call-site` — naming the invocation line](009-Instrumentation.md#rftracecall-site--naming-the-invocation-line)); on CLJS, in VALUE position (not call position — e.g. an argument, a `let`-binding, `(or dispatch-fn rf/dispatch)`) the SAME name resolves to a plain-fn Var instead, for HoF / programmatic callers (`(map dispatch events)` — the macro can't ride a HoF position). The call-site stamp (where applicable) is the only thing the value-alias path loses. Adding a `reg-event*` / `dispatch*` synonym would be a pure alias and add no value; that's not done. (See [Cross-Spec-Interactions §Family asymmetry](Cross-Spec-Interactions.md#21-family-asymmetry--only-reg-view-keeps-a--suffixed-fn-partner) for why the family is intentionally asymmetric, and rf2-m90brg for the dispatch/subscribe/reg-interceptor collapse onto this convention.)

The `dispatch` / `dispatch-sync` / `subscribe` macros are the canonical invocation surface in user code — they pay no extra runtime cost in production (the call-site stamp DCEs under `:advanced` + `goog.DEBUG=false`) and let tooling render two click-to-jump links per error: registration-site (`:rf.trace/trigger-handler`) and invocation-site (`:rf.trace/call-site`). The value-alias forms exist for higher-order use and programmatic / REPL paths where there is no syntactic call site to attribute to.

## Value-vs-fn naming — `-interceptor` suffix telegraphs value-shape

re-frame2 ships two classes of named, public, **callable-looking** surfaces. They share kebab-case identifiers and live side-by-side in user code, but only one class is actually a function. The convention answers a single question — *"if I see this name, is it a Var holding a value or a callable fn?"* — and lets future audit passes stop re-flagging the same fn-shaped callbacks as "name lies about value".

The discriminator is mechanical, not stylistic. Every public surface that *looks* callable slots into exactly one of the two classes below; new surfaces pick their class by mechanism.

### Class 1 — Interceptor values (Vars holding maps · **NOT callable**)

> **EP-0022 interaction.** Public event/frame `:interceptors` chains carry interceptor **references** (keyword ids / `[id arg]`), not inline interceptor values ([002 §Registered interceptors and the chain grammar](002-Frames.md#registered-interceptors-and-the-chain-grammar)). The `-interceptor` suffix convention below still governs any Var that *holds* a pre-built interceptor map (e.g. one passed to `reg-interceptor` at the registration boundary, where values are accepted — [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar)); it does not license dropping such a Var into a public chain. The chain-entry naming for the standard interceptor is the reserved ref `[:rf.interceptor/path …]`, not a `path`-suffixed value Var.

A Var bound to a pre-built interceptor map ([§Standard interceptors](API.md#standard-interceptors), [§`reg-event` interceptor chain](#interceptors-in-the-metadata-map--the-superset-middle-slot-reg-event)). Calling such a Var as a fn (`(rf/validate-at-boundary-interceptor ...)`) raises `ArityException`.

Current Class-1 surfaces in [API.md](API.md): `validate-at-boundary-interceptor` (Spec 010 — production-boundary schema validation). The convention also governs the **factory variant** — a *fn that returns* a Class-1 value (a parameterized interceptor built at the registration boundary, then passed to `reg-interceptor`): the **returned interceptor value** is the Class-1 artefact, and it inherits the rule below, while the factory fn itself does not carry the suffix because it IS a fn. (No public factory surface currently ships — a parameterized family is the `reg-interceptor` `:factory` mechanism keyed by id, not a public value-returning fn; there is no public `redact-interceptor` factory, its underlying fn internal router plumbing only.)

**Rule.** Class-1 surfaces MUST carry the `-interceptor` suffix on the Var name. The suffix telegraphs *value-shape* at the call site — a reader who sees the suffix knows the Var holds a pre-built interceptor map, not a fn that needs invoking. A factory variant (a fn that builds a Class-1 value at the registration boundary) returns a `-interceptor`-suffixed value; the factory fn itself does not carry the suffix because it IS a fn.

```clojure
;; correct — suffix telegraphs value-shape at the reg-interceptor registration boundary,
;; where pre-built interceptor VALUES are accepted (public event/frame chains carry refs
;; only — see the EP-0022 note above)
(rf/reg-interceptor :rf.schema/at-boundary validate-at-boundary-interceptor)  ;; Var · value

;; reading this without the convention — is `validate-at-boundary-interceptor` a fn? a Var? Did the
;; author mean `(validate-at-boundary-interceptor)`? The suffix removes the ambiguity.
```

### Class 2 — Function callbacks in spec-keyed slots (slot values that ARE fns · **callable**)

A slot in a registration map whose value the framework invokes as a fn at runtime. The slot-keyword identifies the surface (`:on-spawn`, `:on-done`, `:on-error`, `:guard`, `:action`, `:entry`, `:exit`, `:after`); the value MAY be a `(fn [...])` literal OR a registered keyword id the framework resolves through a registrar (machines resolve `:action` / `:entry` / `:exit` through the action registry per [Spec 005](005-StateMachines.md)).

Current Class-2 slots: `:on-spawn`, `:on-done`, `:on-error`, `:on-destroy` (lifecycle callbacks per [Spec 002](002-Frames.md), [Spec 005](005-StateMachines.md), [Spec 014](014-HTTPRequests.md)); `:guard`, `:action`, `:entry`, `:exit`, `:after` (machine-spec slots per [Spec 005](005-StateMachines.md)).

**Rule.** Class-2 slot-keywords keep their natural verb / noun naming. They do **NOT** carry an `-fn` suffix, an `-interceptor` suffix, or any other shape annotation. The slot-keyword's surrounding context — the spec-document that defines the slot, the registration map's enclosing macro — makes the fn-shape unambiguous. A reader meeting `:on-spawn` in a machine spec does not need a suffix to know the slot value is a fn; the slot-keyword's spec entry tells them so.

```clojure
;; correct — no suffix on slot-keywords; the slot's spec defines the value-shape
(rf/reg-machine :auth.login/flow
  {:initial :idle
   :states  {:idle      {:on {:submit :submitting}}
             :submitting {:entry :fire-request                  ;; registered action id
                          :on    {:success :complete
                                  :fail    :failed}}
             :complete   {:on-done   (fn [ctx] ...)             ;; fn literal
                          :on-error  ::handle-error}}})         ;; registered fn id
```

### How to slot a new surface

When adding a callable-looking public surface, ask in order:

1. Is the surface a Var bound to a pre-built interceptor map (calling it as a fn would raise `ArityException`)? → Class 1, carry the `-interceptor` suffix.
2. Is the surface a slot-keyword in a registration map whose value the framework invokes as a fn? → Class 2, keep natural verb / noun naming; **no suffix**.

If the surface seems to want both shapes — a name that doubles as both a Var and a fn — split it into two surfaces with distinct names. If it fits neither, file a bead against this section rather than coining a third shape.

**Why two classes need two rules.** The Class-1 / Class-2 split is real because the two surfaces resolve at *different times*: Class-1 Vars resolve at *read* time (the reader knows immediately whether the slot is a value or a call); Class-2 slot-values resolve at *runtime* through the slot's keyword identity (the reader looks up the slot's spec to learn the value-shape). The `-interceptor` suffix gives the Class-1 reader the same up-front clarity that the slot-keyword + spec gives the Class-2 reader. Surfacing this here saves future audit passes from re-flagging Class-2 fn-callbacks as "name lies about value" — they don't lie, the slot-keyword is the disambiguator.

Cross-references: [API.md §Standard interceptors](API.md#standard-interceptors) (Class-1 surfaces · current); [Spec 005 §Machine spec](005-StateMachines.md) (Class-2 slots · machines); [Spec 002 §Per-frame and per-call overrides](002-Frames.md#per-frame-and-per-call-overrides) (Class-2 slots · frame lifecycle).

## High-frequency abbreviations — `fx`, `cofx`, `db` are brand tokens, not aliases

`fx`, `cofx`, and `db` are **deliberate terse abbreviations for the highest-frequency tokens in re-frame's vocabulary** — they are not aliases for `effect`, `coeffect`, and `app-db` and the longer forms are not part of the API surface. The choice is inherited from re-frame v1 and preserved in v2.

Rationale:

- **Frequency.** `:db` and `:fx` appear in every event handler's effect map. `reg-cofx` / `:rf.cofx/requires` / the `:rf.cofx` envelope key appear in every coeffect site. Spelling them out (`reg-coeffect`, `:rf.coeffect/requires`, `effect-map`) would inflate handler bodies and registration sites by 10-15% for zero readability gain once the reader has met the abbreviations.
- **Brand.** These tokens are part of how re-frame *reads*. The terseness is a feature: a handler body's structural shape is recognisable at a glance precisely because `:db` and `:fx` are short.
- **One obvious way.** [Principles §Regularity over cleverness](Principles.md) argues for one obvious way to do a thing. The abbreviations are that one way — there is no `effect` / `coeffect` / `app-db` synonym in the public API. A fresh reader meets the abbreviations once (in the API reference, in the guide's first event-handler chapter); from then on the surface is uniform.

The rule for new public surfaces: if a token belongs to the closed handler vocabulary (`fx`, `cofx`, `db`, `event`, `interceptor`, `frame`, `id`, `kind`) use the established short form; if a token names a per-feature concept (`flow`, `route`, `head`, `machine`, `schema`) the spelled-out form is the canonical name. Do not coin alias pairs.

## `reg-*` return-value convention

Every `reg-*` registration surface returns its **primary id** — the keyword (or path, for `reg-app-schema`) the caller registered with. This is uniform across the family: `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-frame` / `reg-view` / `reg-view*` / `reg-machine` / `reg-machine*` / `reg-app-schema` / `reg-route` / `reg-flow` / `reg-resource-scope` / `reg-head` / `reg-error-projector` / `reg-resource` / `reg-mutation` all return their first positional id argument. `reg-app-schema` returns its `path` (the path IS the registration id for app-db schemas, even though app-db schemas are not a registrar kind — they live in the schemas artefact's per-frame side-table per [010-Schemas §Per-frame schemas](010-Schemas.md#per-frame-schemas)).

The uniformity is load-bearing. It lets call-site code thread the registration id without a separate literal:

```clojure
(let [event-id (rf/reg-event :cart.item/add ...)]
  (rf/dispatch [event-id {:id ...}]))

(let [machine-id (rf/reg-machine :auth.login/flow ...)]
  (rf/dispatch [machine-id :submit]))
```

Tooling, generators, and CP scaffolds rely on the return value to chain registrations into wiring code. The contract is **fixed-and-additive**: future `reg-*` surfaces ship with the same return shape.

## `reg-*` frame-binding convention — opts kwarg, not main arg

The `:frame` keyword is **the mounting concern** for `reg-*` surfaces whose registrations are frame-scoped — it answers "which frame's registry does this slot live in", and is orthogonal to the registration's identity and behaviour. The uniform shape across the family is therefore: **`:frame` rides in the registration-metadata map, never mixed into the main registration arg or the handler/value slot**. For surfaces that carry an explicit metadata middle slot (`reg-app-schema`, `reg-flow`), `:frame` is a key in that middle map alongside `:doc` (and, for `reg-flow`, `:inputs` / `:schema`; `reg-app-schema`'s schema is the positional value slot, NOT a metadata key — rf2-qm7k83 Part A); for a teardown surface whose primary arg is itself the id (`clear-flow`), `:frame` rides a trailing `opts` map (there is no metadata slot on a `clear-*` surface).

```clojure
;; correct — :frame separated from the registration's identity/behaviour
(rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:area]} (fn [w h] (* w h)))
(rf/reg-flow :area {:inputs [[:w] [:h]] :output-path [:area] :frame :session} (fn [w h] (* w h)))

(rf/reg-app-schema [:user] UserSchema)
(rf/reg-app-schema [:user] {:frame :session} UserSchema)

(rf/clear-flow :flow-id)
(rf/clear-flow :flow-id {:frame :session})
```

The convention extends `dispatch` / `subscribe`'s opts-map shape — `:frame` is the same mounting key in the same kwarg position across the dispatch/subscribe/`reg-*`/`clear-*` family. Most `reg-*` surfaces adopt this shape: `:frame` lives in the trailing `opts` kwarg, not inside the registration's primary argument. The one principled exception is `reg-http-interceptor` (`(rf/reg-http-interceptor id interceptor-map)`): because HTTP interceptors are themselves data — a registration IS an interceptor-map carrying `:before` / `:after` / `:frame` / `:rf/registration-metadata` — the shape mirrors the event-interceptor `{:id :before :after}` mental model (Spec 002) and folds `:frame` into the interceptor-map alongside its sibling slots. The family is uniform on intent (`:frame` is a kwarg, never a positional arg); the HTTP surface differs only in that its primary argument IS a map of kwargs.

## `reg-view` auto-id derivation rule

Per [Spec 004 §reg-view](004-Views.md#reg-view-is-the-multi-frame-contract), the `reg-view` macro auto-derives the registered id from the symbol you supply:

```
id = (keyword (str *ns*) (str sym))
```

This matches Clojure's `defn` Var-naming idiom: the symbol is the source of truth; the registry id mirrors it. Override the auto-derivation by attaching `^{:rf/id :explicit/id}` metadata to the symbol:

```clojure
(reg-view counter [label] body)
;; ⇒ id is :my.ns/counter

(reg-view ^{:rf/id :widget/counter} counter [label] body)
;; ⇒ id is :widget/counter
```

The metadata-override syntax is the single supported way to set a non-auto-derived id at the macro surface. Other slot metadata (e.g. `:doc`) lives on the same metadata map: `^{:doc "..." :rf/id :widget/x}`. For computed ids, drop to `re-frame.core/reg-view*`.

## Render-tree shape vs runtime lookup — Vars and ids

Render trees use Vars; runtime lookups use ids. `reg-view` bridges them — auto-defs the symbol AND auto-derives the registry id. The same render/lookup split applies to `reg-view*`: it registers a fn under an id without a Var def; consumers retrieve it via `(rf/view id)` and inline it into render trees.

```clojure
;; reg-view: auto-defs the symbol AND registers under an auto-derived id
(rf/reg-view counter [label] [:button label])

[counter "Hello"]                    ;; render tree — Var reference
(rf/view :my.ns/counter)             ;; runtime lookup — id

;; reg-view*: registers under an id, no Var binding
(rf/reg-view* :feature/widget (fn [args] [:span args]))

[(rf/view :feature/widget) "x"]      ;; render tree — splice the looked-up fn
```

A bare `[:keyword args]` head in a render tree is an **HTML element** (Reagent's existing semantics) — the runtime does not intercept the keyword case to dispatch via the views registry. See [Spec 004 §Calling a registered view](004-Views.md#calling-a-registered-view) and [Cross-Spec-Interactions §21 Family asymmetry](Cross-Spec-Interactions.md#21-family-asymmetry--only-reg-view-keeps-a--suffixed-fn-partner).

## React keys: stable per-row identity, never positional, when rows can mutate

When a re-frame2 view renders a collection of sibling rows whose membership or ordering can mutate at runtime (rows added, removed, reordered, filtered, or replaced), each sibling MUST be keyed on a value that follows row identity, not row position.

This is a re-frame2 discipline because re-frame2 makes the mutable-collection case routine: a workspace lists every variant, a trace ribbon lists every captured trace, a controls repeater lists every entry the user has added. Whichever React substrate is mounted under the adapter port, React's reconciler uses `:key` to decide which DOM nodes (and which component instances, and which `r/with-let` brackets, and which `use-effect` cleanups) survive a re-render. A positional key (`(map-indexed (fn [i row] ^{:key i} [row-view row]))`) silently tells the reconciler that row `i` after the mutation IS row `i` before the mutation — even when the underlying row identity differs. The result is a class of bugs where a deletion leaks the deleted row's component state into the row that took its slot, an insertion mounts at the wrong index, and a `with-let` init body that should re-fire on identity change is silently retained.

### Key naming

Namespace the key value with a single-letter source prefix so a 10x / Xray inspector can read what "kind of row" a duplicate-key warning came from at a glance:

| Prefix | Source                                                              | Example                       |
|--------|---------------------------------------------------------------------|-------------------------------|
| `v:`   | Variant cells (one row per registered variant)                      | `(str "v:" variant-id)`       |
| `t:`   | Trace rows (one row per captured trace)                             | `(str "t:" trace-id)`         |
| `r:`   | Mutable repeater rows (rows the user adds / removes / reorders)     | `(str "r:" row-id)`           |
| `<i>`  | Bare positional, integer only                                        | `^{:key i} [field-view ...]`  |

Bare positional `^{:key i}` is acceptable **only when the row collection has fixed arity** — a destructured 2-tuple, a 3-row form layout where the row count is a compile-time constant. The moment a row collection can grow, shrink, or reorder, the prefix-namespaced identity scheme above is required.

### `r/with-let` init keys

`r/with-let` (and any substrate equivalent that brackets first-render initialisation) re-fires its init body when the surrounding component remounts. When `:key` is the only signal that triggers a remount, the init body re-fires on key change but NOT on input change inside a single mounted instance. View bodies that read external inputs (variant id, run-key tuple, hot-reload tick) MUST include every relevant input in the `:key` tuple — otherwise the init body captures the first render's value and silently goes stale.

```clojure
;; WRONG — init captures the first run-key seen; subsequent changes to
;; (run-key) are ignored inside the surviving with-let bracket.
[^{:key (str "v:" variant-id)} [cell variant-id (run-key)]]

;; RIGHT — every input that should re-init the cell rides the key tuple.
[^{:key [(str "v:" variant-id) (run-key)]} [cell variant-id (run-key)]]
```

### Failure mode

The class of bug the rule catches: when a list-rendering body's `^{:key ...}` is a positional index (`^{:key i}`) but the underlying identity is something else (a `variant-id`, a `trace-id`, a `row-id`), Reagent / React reuse the wrong DOM nodes on reorder. A row keyed by index `2` becomes "the third row", which means a deletion shifts every downstream row's identity by one and surfaces as silent state bleed across cells, repeaters, and trace-ribbon entries. The cure is mechanical (replace `:key i` with `:key (str "<prefix>:" stable-id)`); the diagnostic is hard (the wrong rendering is internally consistent — only an interaction probe reveals the swap).

## Namespace size

A namespace is sized by **cohesion + coupling**, not line count. It is okay for a namespace to be large if it is internally cohesive and largely decoupled from other namespaces. File size alone is **not** a split trigger.

**Split when concerns are independent.** A namespace is appropriately split when its sub-parts could each live alone behind a small public seam — they share little internal state, they evolve on different cadences, and a reader of one part rarely needs the others on screen. The exemplar is `re-frame.routing` (PR #2309): a 2184-LoC `routing.cljc` carrying 11 genuinely independent concerns (pattern compile, route ranking, nav-token allocation, URL parse, scroll restore, …) split cleanly into 13 concern-per-file siblings behind a thin facade. The seams already existed in the code's shape; the split made them visible.

**Keep cohesive when the file is one algorithm.** A namespace is appropriately kept whole when it implements ONE algorithm whose **internal closures-over-state** would be torn into fake seams by a split — the "parts" only make sense in each other's presence, and any seam between them is a lie about the code's actual coupling. The exemplar is `re-frame.machines.transition`: a 1759-LoC transition engine that reads as one machine, kept cohesive because splitting it would invent public-shaped interfaces for what is genuinely one closure over the in-flight transition record.

The test is not "how many lines?" but "would a competent reader, asked to change one part, need to read the others?" If yes — keep it together. If no — split, and let the public seam between siblings stay narrow.

## Packaging conventions

re-frame2 ships as **multiple Maven artefacts**. A user picks the artefacts their app needs; bundle isolation is structural, not vigilance-based — the wrong feature or the wrong substrate is *absent from the classpath*, not eliminated by a hopeful pass of dead-code analysis.

### Artefact tiers

The CLJS reference's artefact set partitions across three tiers (the per-feature tier additionally enumerates post-v1 artefacts, tagged inline, so it stays the complete capability list).

**Core** — `day8/re-frame2`. The always-needed surface: registry, drain, fx, dispatch, subscribe, frame-provider, trace.

**Per-feature** — `day8/re-frame2-<feature-id>`. Optional capabilities. The feature-id matches the spec topic. Rows tagged **(post-v1)** name an artefact whose contract is settled and whose coordinate is fixed, but which ships after v1 — they are listed here so this table stays the single complete per-feature enumeration:

| Artefact | Spec | Feature |
|---|---|---|
| `re-frame2-machines` | [005](005-StateMachines.md) | State machines |
| `re-frame2-flows` | [013](013-Flows.md) | Flows |
| `re-frame2-routing` | [012](012-Routing.md) | Routing |
| `re-frame2-http` | [014](014-HTTPRequests.md) | Managed HTTP |
| `re-frame2-ssr` | [011](011-SSR.md) | SSR & hydration |
| `re-frame2-schemas` | [010](010-Schemas.md) | Malli schema layer |
| `re-frame2-epoch` | [Tool-Pair](Tool-Pair.md) | Tool-Pair epoch surfaces |
| `re-frame2-resources` **(post-v1)** | [016](016-Resources.md) | Resources / declarative server-state — the richest per-feature artefact: it alone contributes three runtime subsystems (`:rf.runtime/resources` / `:rf.runtime/work-ledger` / `:rf.runtime/mutations`) and three registrar kinds (`:resource` / `:mutation` / `:resource-scope`) |
| `re-frame2-story` **(post-v1)** | [007](007-Stories.md) | Stories / Storybook-class component playground — the variant grammar, play/assertion runner, and workspace surface (owns the `:story.*` / `:Workspace.*` library-owned prefixes and the `:rf.story/*` config namespace) |

Other post-v1 per-feature artefacts follow this same convention as their coordinates settle.

**Per-adapter** — `day8/re-frame2-<adapter>`. Each adapter implements the [Spec 006 substrate contract](006-ReactiveSubstrate.md) for one rendering substrate:

| Artefact | Spec | Adapter (substrate it covers) |
|---|---|---|
| `re-frame2-reagent` | [006](006-ReactiveSubstrate.md) | Reagent (browser default) |
| `re-frame2-uix` | [006](006-ReactiveSubstrate.md) | UIx |
| `re-frame2-helix` | [006](006-ReactiveSubstrate.md) | Helix |

In the repository layout the three adapters live under `implementation/adapters/<name>/` (one directory per adapter); per-feature artefacts stay flat under `implementation/<name>/`. The canonical directory name is `adapters/`, not `substrates/` — the directory holds the per-substrate **adapter** implementations of [Spec 006's substrate contract](006-ReactiveSubstrate.md), not the substrates themselves. Maven artefact names are unchanged — the on-disk grouping is a CLJS-reference repo concern; consumers of the published jars see the same coordinates as before.

### Independence rule

Each per-feature artefact is **independent**. Core MUST NOT transitively `:require` any per-feature ns. Cross-references between features (e.g., flows depending on schemas at runtime) go through the late-bind hook registry, not direct requires. The discipline is exactly what makes opt-in work: a consumer who omits `re-frame2-schemas` does not pay for it, and the features that *would* benefit from schemas if present detect the absence and degrade silently.

The independence rule applies to the per-adapter tier too: adapters depend on core; core never depends on an adapter. The runtime's substrate-aware seams (e.g. `re-frame.ssr/install-render-to-string!`) are call-back hooks the adapter ns wires from its own load-time, not requires from core.

### Optional-artefact wrapper convention

<a id="facade-re-export-artefact-require"></a>

**The pattern, named — *facade re-export, artefact require*.** This is re-frame2's optional-capability shape: the public-API surface is **re-exported from the always-loaded facade** (`re-frame.core`), but each surface's **implementation lives in a separate, optionally-present Maven artefact** (`day8/re-frame2-<feature>`) that the facade reaches only at call time through the late-bind hook table. The facade carries the name and the contract; the artefact carries the code. The single-import ergonomics of a monolith are preserved while the bundle-isolation of a modular split is kept — but the binding is invisible at the call site, so the pattern carries two paired obligations (the self-explaining front-porch and the require-carrying error, both below) that make the invisibility safe.

Each optional-artefact wrapper lives in core under `re-frame.core-<feature>` (e.g. `re-frame.core-routing`, `re-frame.core-flows`). The wrapper publishes the public-API fns that consumers reach via `re-frame.core` re-exports, but the **implementation** of each fn lives in a separate Maven artefact (`day8/re-frame2-<feature>`).

Core MUST NOT statically `:require` the producing namespace — that would pull the feature's implementation onto every consumer's classpath even when no feature surface is used. Each wrapper fn instead looks the producing fn up through the [late-bind hook table](../implementation/core/src/re_frame/late_bind/directory.cljc) at call time, which the producing artefact populates from its own ns-load.

The single-import contract is preserved: users continue to write `rf/reg-flow` after `(:require [re-frame.core :as rf])` — the wrapper ns is reached via `re-frame.core`'s re-export. When the producing artefact is absent the wrapper raises a documented `:rf.error/<feature>-artefact-missing` ex-info with `:where 'rf/<surface>`, `:recovery :no-recovery`, and a `:reason` string naming the artefact and the ns to require at boot.

The wrappers live in sibling namespaces rather than in `core.cljc` itself so `core.cljc` stays free of optional-artefact glue. The file naming uses `core_<feature>` rather than `core/<feature>` because CLJS goog.provide for `re-frame.core` overwrites its parent object.

#### Obligation 1 — every artefact-missing error carries the require

The late-binding is invisible at the call site: a developer who forgets to `:require` the impl artefact calls a re-exported fn that *exists*. An opaque "no such hook" failure would leave them stranded. So the pattern is HARD-CONSTRAINED: **every artefact-missing error MUST carry the exact copy-pasteable Maven coordinate and the namespace to require at app boot.** The CLJS reference centralises this in [`re-frame.late-bind/require-fn!`](../implementation/core/src/re_frame/late_bind.cljc) — the single throw skeleton the `re-frame.core-<feature>` wrappers route through. Its `:reason` slot reads `"<where> requires <maven> on the classpath; add it to deps and require <require-ns> at app boot."`, and its ex-data carries `:rf.error/id`, `:where`, `:recovery :no-recovery`. A port MUST mirror this: an artefact-missing error that does not name its fix is a contract break, not a stylistic difference.

#### Obligation 2 — the feature-inspection front-porch

The inverse query — *which* optional features are present, and what to add for the absent ones — is exposed as three production-shipping `re-frame.core` surfaces (per [API §Feature inspection](API.md#feature-inspection)):

- `(rf/features)` → a map of every optional feature keyword to its coordinate data (`:maven` / `:require` / `:spec`) merged with a live `:loaded?` boolean.
- `(rf/feature-loaded? :epoch)` → boolean presence check.
- `(rf/require-feature! :epoch)` → asserts presence, throwing the exact copy-pasteable coordinate when absent (an early, self-explaining guard).

**Static-data hard constraint (the production implication).** The feature→coordinate mapping these surfaces read MUST be **static data in the always-loaded facade** — a plain table of `{:feature {:maven … :require … …}}` strings. It MUST NOT `:require` (live-reach) into the optional impl namespaces. A live reach-in would create exactly the hard facade→optionals reference the whole pattern exists to avoid: it would pull every optional artefact (epoch, machines, schemas, flows, routing, http, ssr) onto every production classpath and break [§Bundle-isolation conformance](#bundle-isolation-conformance). Presence is therefore detected without reaching in — `feature-loaded?` does a pure keyword lookup in the always-loaded late-bind hooks atom against a representative key the impl publishes at its own ns-load. These three fns ship to production (runtime queries, not instrumentation — NOT elided), and the CLJS reference proves the static-table claim through the bundle-isolation, elision, and perf-bundle gates.

### Late-bind hook key grammar

Every key published through the `re-frame.late-bind` hook registry follows a closed grammar so the namespace prefix is predictable and the table stays browsable. The full inventory lives at [`implementation/core/src/re_frame/late_bind/directory.cljc`](../implementation/core/src/re_frame/late_bind/directory.cljc) (the drift-test source of truth per H8 /); the grammar is:

| Prefix shape | Producer | When to use | Example |
|---|---|---|---|
| `:<feature>/<surface>` | A single per-feature artefact ns | The default case: an optional artefact publishes its public-API impl behind a late-bind seam. `<feature>` matches the feature-id (the artefact name without the `re-frame2-` prefix). `<surface>` names the function — `<verb-noun>` shape, kebab-case, bang-suffixed only when the producer mutates process-level state per [§Naming: when does a surface carry `!`?](#naming-when-does-a-surface-carry-). | `:flows/reg-flow`, `:schemas/validate-app-schema!`, `:machines/spawn-fx`, `:ssr/render-to-string`, `:epoch/settle!`, `:http/clear-all-in-flight!` |
| `:adapter/<surface>` | Chained across every shipped CLJS adapter | Substrate-routed seams — each adapter ns registers a fn keyed by `:adapter/<surface>`; the runtime dispatches through `current-adapter` to pick the right one. The drift directory marks these `:chained? true` with `:producer-ns` as a vector of every adapter ns. | `:adapter/current-frame`, `:adapter/ratom`, `:adapter/wrap-view` |
| `:rf2/<runtime-stamp>` | Core (or an artefact publishing a globally-visible runtime fact) | Runtime-static, framework-owned stamps that any artefact can read — version numbers, build flags, schema digests, etc. The `:rf2/` prefix marks the key as "framework-global, not feature-scoped." Used sparingly — most cross-feature plumbing should pick a feature prefix instead. | `:rf2/runtime-version` |

**Rules.**

1. The `<feature>` segment names a single artefact (`flows`, `schemas`, `machines`, `routing`, `http`, `ssr`, `epoch`, `reagent`, `views`, `subs`, `router`, `trace`, `event-emit`, `error-emit`, `privacy`). New artefacts pick a single-segment kebab-case feature-id that matches the producing namespace's last segment.
2. Multi-word `<surface>` is kebab-case (`reg-flow`, `clear-all-in-flight!`, `render-to-string`, `app-schemas-digest`). The `:` between prefix and surface is the only separator; no dots inside the surface segment.
3. **The drift test enforces directory ↔ producer-ns parity** (`implementation/core/test/re_frame/late_bind_drift_test.clj`). A `set-fn!` call site whose key isn't in the directory — or a directory entry whose key isn't reached by any `set-fn!` — fails CI. Add a hook = update both the producer ns AND the directory entry in a single change.
4. The hook key is **stable across the artefact's lifecycle** — adapters / consumers reference it by string-spelled keyword, so renaming a hook key is a breaking change of the same magnitude as renaming a public fn.
5. Hooks that fan out chronologically (callback-style listeners called in registration order rather than overwriting) are documented with `:chained? true` in the directory; this is an additive property of the same naming grammar.
6. **Hot-path consumers MUST use a sticky (per-key cached) resolution form.** The late-bind registry exposes two read shapes: a re-dereferencing form (reads the hooks map every call), and a per-key cached form whose cache is invalidated on `set-fn!` / `chain-fn!`. Drain-frequency call sites — interceptor chain wiring, fx dispatch, epoch capture, the per-event drain — MUST resolve through the cached form. The unmemoised form is reserved for boot-time and rare-path callers where allocator pressure and atom-deref count are not load-bearing. The reason is performance-pattern contract, not implementation discretion: a 100-event drain reads ~6+ hook keys per event, so the unmemoised form costs hundreds of atom-derefs per drain and busts the V8 megamorphic IC at the hooks-map deref site. A port that wires hot-path consumers through the unmemoised form ships a measurable drain-throughput regression versus the CLJS reference. Cache invalidation on `set-fn!` / `chain-fn!` keeps hot-reload — re-registration of a hook function — observable on the next call without per-call atom-deref cost.

The grammar holds across every per-feature artefact split landed under the Strategy B rollout; new per-feature splits MUST mint hook keys under their own `<feature>` segment so the naming stays grep-friendly across the codebase.

### Naming convention

The artefact-naming convention is `re-frame2-<thing>`, where `<thing>` is the feature-id (per Spec topic) or adapter name. The Maven group is `day8` for the CLJS reference's published artefacts.

The `*`-suffix convention for fn-versions of macros (per the Clojure idiom of `let`/`let*`, `fn`/`fn*`; see [§`*`-suffix naming for fn-versions of macros](#-suffix-naming-for-fn-versions-of-macros)) is orthogonal to artefact naming: `*`-suffix is symbol-naming inside an artefact; `re-frame2-<thing>` is the artefact's coordinate.

### Bundle-isolation conformance

A production-elision build of an app that consumes `day8/re-frame2` plus `day8/re-frame2-reagent` carries `re-frame.adapter.reagent` strings AND does NOT carry `re-frame.adapter.uix` or `re-frame.adapter.helix` strings, AND does NOT carry the namespaces of any per-feature artefact the app didn't add to its `deps.edn`. The check is a grep over the advanced-compile output.

### Lockstep versioning through 1.0

Through the v0.0.1.alpha → 1.0 stretch every artefact ships at the **same version** sourced from the repo-root `VERSION` file. The mechanism is structural: every artefact's `:clein/build :version` declares the relative path `"../../VERSION"`, and every non-core artefact references core via `{:local/root "../core"}` (rewritten to `{:mvn/version <VERSION>}` on the throwaway runner checkout at deploy time). The lockstep contract is enforced by [`.github/scripts/verify-version-lockstep.sh`](../.github/scripts/verify-version-lockstep.sh), invoked by both `.github/workflows/test.yml` (PR-time drift detection) and `.github/workflows/release.yml` (pre-deploy gate). Independent versioning is revisited post-1.0; until then, adding a literal `:mvn/version` for a `day8/re-frame2-*` artefact in a committed `deps.edn` is a contract break that the verify script flags.

The release pipeline — topological deploy DAG, recovery procedure when a partial deploy fails, pre-flight checklist — is documented in [docs/release-process.md](../docs/release-process.md).

### Cross-references

- [§Adapter shipping convention](#adapter-shipping-convention) below — the per-adapter tier in operational detail.
- [README §Project layout](../README.md#project-layout) — the per-artefact directory structure under `implementation/`.
- [docs/release-process.md](../docs/release-process.md) — operational doc for cutting a release: topological DAG, recovery procedure, lockstep enforcement.

## Adapter shipping convention

Adapters ship as **separate Maven artefacts** alongside the core (per [§Packaging conventions §Per-adapter](#packaging-conventions) above). The CLJS reference's published artefact set is:

| Artefact | Contents |
|---|---|
| `day8/re-frame2` | Core: registry, drain, fx, dispatch, subscribe, frame-provider, trace, the substrate-adapter contract, the headless plain-atom adapter. Seven per-feature surfaces ship as separate artefacts alongside core: `day8/re-frame2-schemas`, `day8/re-frame2-machines`, `day8/re-frame2-routing`, `day8/re-frame2-flows`, `day8/re-frame2-http`, `day8/re-frame2-ssr`, `day8/re-frame2-epoch`. The per-feature split set is closed. |
| `day8/re-frame2-reagent` | Reagent adapter (`re-frame.adapter.reagent`) |
| `day8/re-frame2-schemas` | Schemas (Spec 010) — `re-frame.schemas`, the Malli-backed schema-attachment surface (`reg-app-schema`, `app-schema-at`, `app-schemas`, the validation hot-path entry points). |
| `day8/re-frame2-machines` | State machines (Spec 005) — `re-frame.machines`, the machine grammar surface (`reg-machine`, `make-machine-handler`, `machine-transition`, the `:rf/machine` framework sub, the `:rf.machine/spawn` / `:rf.machine/destroy` actor-lifecycle fxs, the in-snapshot `:rf/spawn-counter` allocator (per-machine-id, lives inside each machine's snapshot for pure-functional allocation)). |
| `day8/re-frame2-routing` | Routing (Spec 012) — `re-frame.routing`, the route grammar (`reg-route`, `match-url`, `route-url`), the `:rf.route/navigate` / `:rf.route/transitioned` / `:rf/url-requested` / `:rf.route/handle-url-change` / `:rf.route/continue` / `:rf.route/cancel` events, the `:rf.nav/push-url` / `:rf.nav/replace-url` / `:rf.nav/scroll` reserved fxs, and the `:rf/route` / `:rf.route/{id,params,query,transition,error}` framework reg-subs. |
| `day8/re-frame2-flows` | Flows (Spec 013) — `re-frame.flows`, the flow grammar (`reg-flow`, `clear-flow`), the `:rf.fx/reg-flow` / `:rf.fx/clear-flow` runtime fxs, the per-frame flow registry, the topological-sort engine, and the `run-flows-on-db` walker (the flow transform that runs as the outermost `:after`, right after the handler, rewriting the pending `:db` effect before the single deferred install). |
| `day8/re-frame2-http` | Managed HTTP (Spec 014) — `re-frame.http.managed`, the production-eligible `:rf.http/managed` and `:rf.http/managed-abort` fxs, the in-flight request registry, the Fetch / `java.net.http.HttpClient` transport adapters, the encode / decode pipeline, the retry-with-backoff machinery, and the eight-category `:rf.http/*` failure taxonomy. The HTTP test surface — the two canned-stub fxs (`:rf.http/managed-canned-success` and `:rf.http/managed-canned-failure`) AND the `with-managed-request-stubs` family of macros / fns — ships in the same Maven artefact under the sibling `re-frame.http.test-support` namespace (single discoverable home for HTTP test surfaces). Tests opt in by `:require`-ing `re-frame.http.test-support`; production code paths must not (per Spec 014 §Test-support require). |
| `day8/re-frame2-ssr` | SSR & hydration (Spec 011) — `re-frame.ssr`, the pure hiccup → HTML emitter (`render-to-string`), the FNV-1a structural render-tree hash (`render-tree-hash`), the `:rf/hydrate` event with `:replace-frame-state` semantics, the seven `:rf.server/*` server-only fxs (`set-status`, `set-header`, `append-header`, `set-cookie`, `delete-cookie`, `redirect`, `safe-redirect`), the per-request HTTP response accumulator in a framework-private side-channel atom keyed by frame-id (read via `get-response`, not an `app-db` path), the `reg-error-projector` registry kind plus the built-in `:rf.ssr/default-error-projector`, the SSR error-projection trace listener, and the `data-rf2-source-coord` annotation on registered-view roots. |
| `day8/re-frame2-epoch` | Epoch / time-travel ([Tool-Pair](Tool-Pair.md) §Time-travel) — `re-frame.epoch`, the per-frame `:rf/epoch-record` ring buffer (`epoch-history`), the `(rf/configure! {:epoch-history {:depth N}})` knob, the `register-epoch-listener!` / `unregister-epoch-listener!` listener API, the `restore-epoch!` rewind with its seven documented failure modes (`:rf.epoch/restore-unknown-epoch`, `:rf.epoch/restore-schema-mismatch`, `:rf.epoch/restore-missing-handler`, `:rf.epoch/restore-version-mismatch`, `:rf.epoch/restore-during-drain`, `:rf.epoch/restore-non-ok-record`, plus `:rf.error/no-such-handler` for the unknown-frame case), the per-run trace-capture buffer the router and the trace surface feed via the `:epoch/capture-event` (per-trace emit), `:epoch/settle!` (per-event drain-settle), and `:epoch/commit-halt-record!` (per-event depth-halt boundary) late-bind hooks, the `:rf.epoch/snapshotted` and `:rf.epoch/restored` trace events, and the `:sub-runs` / `:renders` / `:effects` per-run projections. |
| `day8/re-frame2-uix` | UIx adapter (`re-frame.adapter.uix`) — the `use-subscribe` hook, `flush-views!` test-flush wrapping React's `act()`, the source-coord wrapping component, and the UIx-side `frame-provider` consuming the shared React context. Targets UIx 2.x. |
| `day8/re-frame2-helix` | Helix adapter (`re-frame.adapter.helix`) — the `use-subscribe` hook, `flush-views!` test-flush wrapping React's `act()`, the source-coord wrapping component, and the Helix-side `frame-provider` consuming the shared React context. Targets Helix 0.2.x. Helix and UIx share the React + hooks substrate model, so the UIx adapter's design decisions transfer unchanged. |

A consumer picks their substrate by adding the matching adapter alongside the core:

```clojure
;; deps.edn for a Reagent app
{:deps {day8/re-frame2         {:mvn/version "2.0.0"}
        day8/re-frame2-reagent {:mvn/version "2.0.0"}}}

;; deps.edn for a UIx app
{:deps {day8/re-frame2     {:mvn/version "2.0.0"}
        day8/re-frame2-uix {:mvn/version "2.0.0"}}}

;; deps.edn for a Helix app
{:deps {day8/re-frame2       {:mvn/version "2.0.0"}
        day8/re-frame2-helix {:mvn/version "2.0.0"}}}

;; deps.edn for a Reagent app that uses Spec 010 schemas
{:deps {day8/re-frame2         {:mvn/version "2.0.0"}
        day8/re-frame2-reagent {:mvn/version "2.0.0"}
        day8/re-frame2-schemas {:mvn/version "2.0.0"}}}

;; deps.edn for a Reagent app that uses Spec 005 state machines
{:deps {day8/re-frame2          {:mvn/version "2.0.0"}
        day8/re-frame2-reagent  {:mvn/version "2.0.0"}
        day8/re-frame2-machines {:mvn/version "2.0.0"}}}
```

**Rationale.** Bundle isolation is guaranteed by structure rather than by careful dead-code elimination: a Reagent-only application simply does not have UIx code on the classpath. The Closure Compiler's DCE does not have to be perfect; the wrong substrate is structurally absent. This reinforces the substrate-independence-of-core thesis (Spec 006 §The reactive-substrate adapter contract) at the package layer. The same argument generalises to per-feature artefacts (e.g. `day8/re-frame2-schemas`, `day8/re-frame2-machines`, `day8/re-frame2-routing`, `day8/re-frame2-flows`, `day8/re-frame2-http`, `day8/re-frame2-ssr`, `day8/re-frame2-epoch`): an app that doesn't register any schemas doesn't carry the `re-frame.schemas` namespace or its Malli dep on its classpath; an app that doesn't register any machines doesn't carry the `re-frame.machines` namespace, the machine-transition engine, or the `:rf.machine.spawn/spawned` / `:rf.machine/destroyed` trace strings; an app that doesn't register any routes doesn't carry the `re-frame.routing` namespace, the route-rank / pattern-compile / nav-token machinery, the `:rf/route` reg-sub family, or any `:rf.route/*` / `:rf.nav/*` keyword strings; an app that doesn't register any flows doesn't carry the `re-frame.flows` namespace, the per-frame flow registry, the topological-sort engine, the dirty-check `last-inputs` map, or the `run-flows-on-db` walker (the outermost-`:after` flow transform); an app that doesn't issue any managed-HTTP requests doesn't carry the `re-frame.http.managed` namespace, the in-flight request registry, the Fetch / `HttpClient` transport adapters, the encode / decode pipeline, the retry-with-backoff machinery, or any of the `:rf.http/*` keyword strings; an app that doesn't render server-side doesn't carry the `re-frame.ssr` namespace, the pure hiccup → HTML emitter, the FNV-1a render-tree-hash machinery, the per-request response accumulator (a framework-private side-channel atom keyed by frame-id), the seven `:rf.server/*` server-only fxs, the `reg-error-projector` registry kind plus its built-in default, or any of the `:rf.ssr/*` / `:rf.server/*` keyword strings; an app that doesn't consume the pair-tool / time-travel surface doesn't carry the `re-frame.epoch` namespace, the per-frame `:rf/epoch-record` ring buffer, the per-run trace-capture path, the `:sub-runs` / `:renders` / `:effects` projection walker, the schema-validate / machine-version / missing-reference predicates, or any of the `:rf.epoch/*` keyword strings.

**Dependency direction.** Adapter and feature artefacts depend on core; core never depends on either. The runtime's cross-namespace seams (e.g. `re-frame.ssr/install-render-to-string!`, `re-frame.schemas/validate-app-schema!`, `re-frame.machines/reg-machine`, `re-frame.routing/reg-route`, `re-frame.flows/reg-flow`, `re-frame.http.test-support/install-managed-request-stubs!`, `re-frame.epoch/settle!` / `re-frame.epoch/restore-epoch!`) are call-back hooks the producing artefact wires from its own load-time, not requires from core. The wiring goes through `re-frame.late-bind`'s hook table — when the producing artefact isn't on the classpath, the consuming code's lookup returns `nil` and the call no-ops cleanly (or, for active surfaces like `rf/reg-machine` / `rf/reg-route` / `rf/reg-flow` / `rf/with-managed-request-stubs` / `rf/render-to-string` / `rf/render-tree-hash` / `rf/reg-error-projector`, throws a clear `:rf.error/<feature>-artefact-missing`). The epoch surface is dev-tier — its public re-exports (`rf/epoch-history`, `rf/restore-epoch!`, `(rf/configure! {:epoch-history ...})`, and the `:epoch` stream of `rf/register-listener!` / `rf/unregister-listener!`) degrade silently to empty-vector / false / no-op when the artefact is absent rather than throwing, since the surface is already gated on `interop/debug-enabled?` and a release build that omits the artefact must not raise from a leftover dev-time call site.

**Views-layer decoupling — partial.** The Reagent-coupled views layer (`re-frame.views`) currently lives in core because the CLJS reference is Reagent-default. The React **frame Context** has been factored out of `re-frame.views` into `re-frame.adapter.context` (CLJS-only file in core) so the UIx adapter consumes the *same* createContext object — that's the slice the UIx-adapter work needed. The rest of `re-frame.views` (the `reg-view` macro, the source-coord injection walk, the per-render-key trace plumbing) stays Reagent-flavoured; UIx users call `reg-view*` (plain-fn) and the UIx adapter wraps user components for source-coord injection at the substrate boundary. Full views-layer decoupling — moving every Reagent symbol out of core — remains an optional future step.

**SA-4 — untracked note (no bead filed yet).** Per [SPEC-AUTHORING §SA-4](SPEC-AUTHORING.md), "optional future step" is a post-v1 design direction with no concrete tracking bead, so it does not qualify as `:post-v1 tracked` (which requires a `rf2-<id>`). **Fires-when trigger:** either (a) a non-React-family adapter (a substrate that cannot carry the Reagent-flavoured `re-frame.views` symbols) needs core clean of Reagent, or (b) core bundle-size pressure makes the residual Reagent symbols in core a measured cost worth removing. Absent either trigger the partial decoupling above (frame Context factored into `re-frame.adapter.context`) is the settled v1 position; a tracking bead is filed only when a trigger fires — otherwise this remains an untracked note, not committed work.

**Conformance check (bundle isolation).** A production-elision build of an app that consumes `day8/re-frame2-reagent` carries `re-frame.adapter.reagent` strings AND does NOT carry `re-frame.adapter.uix` or `re-frame.adapter.helix` strings (and, symmetrically, a UIx app's bundle contains `re-frame.adapter.uix` and is clean of `re-frame.adapter.reagent`). The CI's bundle-grep step (`scripts/check-bundle-isolation` invoked by examples/scripts) builds both the Reagent counter and the UIx counter under `:advanced` and asserts each pair of substrate-specific symbols is absent from the wrong bundle. The same applies to feature artefacts: a counter-style app that registers no schemas builds an `:advanced` bundle clean of `re-frame.schemas` symbols and Malli code; an app that registers no machines builds an `:advanced` bundle clean of `re-frame.machines` symbols, `reg-machine` / `machine-handler` / `machine-transition` strings, and the `:rf.machine.spawn/spawned` / `:rf.machine/destroyed` trace strings; an app that registers no flows builds an `:advanced` bundle clean of `re-frame.flows` symbols, the topological-sort engine, and the dirty-check `last-inputs` machinery; an app that issues no managed-HTTP requests builds an `:advanced` bundle clean of `re-frame.http.managed` symbols, the in-flight registry, the Fetch transport adapter, and every `:rf.http/*` keyword string; an app that doesn't add the epoch artefact builds an `:advanced` bundle clean of `re-frame.epoch` symbols, the per-frame `:rf/epoch-record` ring buffer, the per-run trace-capture path, the `:sub-runs` / `:renders` / `:effects` projection walker, and every `:rf.epoch/*` trace string. The check is a grep over the advanced-compile output.

**Adapter `adapter` Var convention.** Each adapter namespace exports an `adapter` Var holding the spec map; consumers require the namespace and pass the Var to `(rf/init! adapter-map)`. There is no default-adapter registry and no ns-load side-effect. The Reagent adapter exports `re-frame.adapter.reagent/adapter`, the UIx adapter `re-frame.adapter.uix/adapter`, the Helix adapter `re-frame.adapter.helix/adapter`; SSR exports `re-frame.ssr/adapter` (the JVM-side substrate); plain-atom exports `re-frame.substrate.plain-atom/adapter`. Future adapters follow the same convention: a `def adapter` at the bottom of the adapter namespace, value being the ten-fn spec map (six required + three optional + one lifecycle). See [Spec 006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot) for the boot-time wiring and the rationale.

### Adapter test matrix policy

Reagent is the **canonical adapter**: the full re-frame2 test suite (every `clojure -M:test` run, every `node-test` build, every `:browser-test` run, every `examples` run, every conformance fixture) executes against the Reagent adapter. The UIx and Helix adapters are **smoke-tested** at the adapter level: a single mount → subscribe → dispatch → re-render browser smoke per adapter, owned by the adapter (`implementation/adapters/<name>/testbed/spec.cjs`), proves the ten-fn substrate contract (Spec 006 §The reactive-substrate adapter contract) wires up end-to-end. Each non-canonical adapter additionally ships a **curated example subset** — counter + login per Decision 7 of each adapter's locked-decision set (realworld is skipped for both UIx and Helix; deferred until a substrate user wants it). Because the `examples/` tree is test-free, those curated example pages carry *compile coverage* only (`test:examples-compile`); they are not themselves a per-page runtime gate. The substrate-agnostic behaviour they replay (login machine, Malli schemas, managed-HTTP stub) is covered by the canonical Reagent suite and the feature artefacts' own tests; the adapter-specific view-layer surface (the `use-subscribe` hook, capture-frame capture, render flush, source-coord DOM annotation) is covered by the adapter's own CLJS tests. Full per-adapter-matrix conformance — every test, every fixture, every example, against every shipped adapter — remains a per-adapter responsibility, not a re-frame2-core responsibility. The policy is a deliberate concentration of the test budget on the substrate the spec was authored against.

## Per-port conventions

Conventions that exist only because of a host-language constraint live here. Each entry names the constraint, the port(s) it applies to, and the convention the spec adopts in response.

### CLJS — `goog.provide` collision: dash-form sub-namespaces of facade namespaces

ClojureScript compiles each namespace to a `goog.provide` call, which unconditionally **overwrites the parent object** on the host. The consequence: a host cannot carry both `re-frame.core` AND `re-frame.core.flows` as namespaces — loading `re-frame.core` wipes the `re_frame.core` object and with it every `re_frame.core.<sub>` previously defined under it. The canonical write-up is [clojurescript.org/about/differences](https://clojurescript.org/about/differences) (search "goog.provide"). This is structural to the CLJS compilation model, not a bug; the JVM does not share the constraint.

For sub-namespaces of a facade namespace (the canonical case is `re-frame.core` — the user-facing API surface — but the same applies to any other facade a port chooses to ship), the CLJS reference adopts a **dash-form** naming convention: substitute a hyphen for the dot between the facade name and the sub-name.

| Wrong (collides) | Right (dash form) |
|---|---|
| `re-frame.core.flows` | `re-frame.core-flows` |
| `re-frame.core.machines` | `re-frame.core-machines` |
| `re-frame.core.routing` | `re-frame.core-routing` |
| `re-frame.core.schemas` | `re-frame.core-schemas` |
| `re-frame.core.ssr` | `re-frame.core-ssr` |
| `re-frame.core.epoch` | `re-frame.core-epoch` |
| `re-frame.core.http` | `re-frame.core-http` |

The user-facing alias `(:require [re-frame.core :as rf])` still resolves the documented symbols — `rf/reg-flow`, `rf/reg-machine`, etc. — via re-exports inside `re-frame.core` itself. The sub-namespace's existence is an implementation detail of the per-feature artefact (per [§Packaging conventions](#packaging-conventions)); the dash-form name is what makes the artefact loadable alongside core on the CLJS host.

The convention applies wherever a port targets a host with the `goog.provide`-style "parent object" model. Ports whose host language does not share the constraint (the JVM port, ports using flat module systems) MAY use dot-form sub-namespaces — but the dash-form is portable and the spec recommends it uniformly for symmetry across ports.

## Cross-references

- [000-Vision.md](000-Vision.md) — goals, constraints, the pattern's minimal core.
- [Principles.md](Principles.md) — the discipline principles that motivate these conventions.
- [001-Registration.md](001-Registration.md) — registration metadata-map shape; what each `reg-*` accepts.
- [002-Frames.md §Run-to-completion dispatch](002-Frames.md#run-to-completion-dispatch-drain-semantics) — the drain / dispatch mechanics the [§Event-pipeline vocabulary](#event-pipeline-vocabulary--the-terms-one-event-traverses) names.
- [009-Instrumentation.md §Event-bundle projection](009-Instrumentation.md#event-bundle-projection-group-by-event--domino-bucket) — the trace projection surface the [§`event-*` noun family](#the-event--noun-family) renames.
- [MIGRATION.md](../migration/from-re-frame-v1/README.md) — framework-keyword consolidation under `:rf/*` ([§M-20](../migration/from-re-frame-v1/README.md#m-20-framework-keyword-consolidation--rf-as-the-single-root-prefix)) and the Type-A vs Type-B migration classification.
