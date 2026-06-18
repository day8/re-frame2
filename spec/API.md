# re-frame2 — API

> **Type:** Reference
> Reference for the CLJS implementation's API: signatures, status, cross-references. No rationale — per-Spec docs own the *why*. Pattern-level contracts live in [000-Vision §The pattern](000-Vision.md#the-pattern-js-cross-compile-language-agnostic) and the per-Spec docs. **`:fx-overrides` asymmetry:** id-valued at the pattern level; CLJS reference also accepts fn values — see [002 §`:fx-overrides`](002-Frames.md#fx-overrides--replace-fx-handlers).

## Conventions

- **Status** — exactly one base value, optionally combined with one or more parenthesised qualifiers. The closed set below is the same vocabulary the generated [`api-manifest.edn`](api-manifest.edn) curates (its `:status` field) — the two MUST agree:
  - Base values:
    - `v1` (ships in v1).
    - `v1 (preserved)` (exists in current re-frame; preserved unchanged).
    - `v1 (preserved + extended)` (exists today; v1 adds new arity or behaviour).
    - `EP-NNNN` (a surface introduced or reshaped by a named pre-alpha EP, shipping in v1 — e.g. `reg-event` (EP-0018), `reg-interceptor` (EP-0022); the canonical lineage is the named EP, cited in the row's Notes).
    - `post-v1 lib` (design spec in v1 Specs but ships in a post-v1 library).
    - `post-v1 (planned, rf2-<id>)` (specced normatively but not yet shipped; the impl is tracked by the named bead — per the Projection-maintenance rule below).
  - Qualifiers (parenthesised, combinable): `dev-only` (elided in production builds — the macro emit site or runtime body, depending on the API); `changed, EP-NNNN` (a preserved v1 surface a named EP changed — e.g. `v1 (changed, EP-0017)`); `optional capability` (ships only when the owning optional artefact is on the classpath, optionally narrowed `optional capability, dev/test`); `internal lowering only` (an EP surface retained as a framework-internal lowering seam, not a public authoring form — e.g. `EP-0022 (internal lowering only)`).
  - Examples: `v1`, `v1 (preserved)`, `v1 (dev-only)`, `v1 (preserved, dev-only)`, `v1 (changed, EP-0017)`, `EP-0018`, `EP-0022 (internal lowering only)`, `v1 (optional capability)`, `post-v1 lib`, `post-v1 (planned, rf2-<id>)`.
  - The `re-frame.alpha` namespace is dissolved — no APIs in this reference live outside `re-frame.core` (with the documented per-namespace exceptions: `re-frame.test-support` and `re-frame.test-helpers`).
- **Macro/Fn:** marked `M` (macro) or `Fn`.
- **Spec column** — names exactly the **canonical owning Spec** (the per-Spec doc whose contract this API implements). Migration rules and other cross-references are NOT in the Spec column; they appear in the Notes column when relevant.
- **Configure keys** — runtime configuration is uniformly via `(rf/configure! <key> <opts>)`. Every `<key>` is enumerated in [§Configure keys](#configure-keys) below; per-area tables call out which keys their APIs read but do not redefine the key's vocabulary.
- **Per-artefact public namespaces.** The core surfaces live in `re-frame.core`. Per-feature artefacts ship their own public namespace; consumers `:require` the namespace directly (with the documented exceptions of the epoch + SSR-query surfaces, which late-bind re-exports through `re-frame.core`). **Front-porch boundary.** `re-frame.core` is the small app-developer front porch — registration, dispatch, subscribe, the frame basics, interceptors, lifecycle, configure, and the core trace / egress / registrar-query / app-realm surfaces. The optional-feature **registration MACROS** stay on the façade (`reg-route`, `reg-flow`, `reg-app-schema` / `reg-app-schemas`, `reg-machine` / `defmachine`, `reg-resource` / `reg-mutation` / `reg-resource-scope`, `reg-error-projector`, `reg-head`, `reg-http-interceptor`) because they capture call-site source-coords and have no owned-namespace macro form — registration stays central. But the optional features' **non-registration query / introspection / lifecycle helpers are NOT re-exported** from `re-frame.core`; reach them through their owning namespace: `re-frame.schemas` (`app-schema-at` / `app-schema-meta-at` / `app-schemas` / `app-schemas-digest` / `set-schema-validator!` / `-explainer!` / `-printer!` / `set-schema-fns!`), `re-frame.machines` (`reg-machine*` / `make-machine-handler` / `machine-transition` / `machines` / `machine-meta` / `machine-by-system-id`), `re-frame.routing` (`match-url` / `route-url` / `current-url` / `clear-route`), `re-frame.flows` (`clear-flow`). The epoch + SSR-query re-exports remain on the façade as documented late-bind exceptions (rows below):

  | Namespace | Artefact | Surfaces |
  |---|---|---|
  | `re-frame.core` | core | the registration / dispatch / subscribe / interceptor / lifecycle / configure surfaces; **late-binds re-exports for two artefacts**: (a) the `re-frame.epoch` surface (`epoch-history`, `restore-epoch!`, `replace-app-db!`, `reset-app-db!`, `replace-runtime-db!`, `replace-frame-state!`, `register-epoch-listener!`, `unregister-epoch-listener!`, `projected-record`, `projected-history`); (b) the `re-frame.ssr` **query surface** (`render-to-string`, `render-tree-hash`, `project-error`, `render-head`, `active-head`, `head-model->html`, `head-snapshot`). The `streaming-render-shell` / `streaming-render-continuation` / `streaming-build-final-payload` triple is **not** re-exported — the streaming surface is host-adapter territory and the SSR-aware host (`re-frame.ssr.ring` / equivalents) requires `[re-frame.ssr :as ssr]` directly. Re-exports activate when the named artefact is on the classpath; absent artefacts surface `:rf.error/<feature>-artefact-missing` errors. |
  | `re-frame.test-support` | core | `dispatch-sequence`, `assert-path-equals` / `assert-db-equals`, `poll-until`, fixture machinery (per [§Testing](#testing)). **Runtime-state axis** — registrar, frames, `app-db`, drain. The `assert-*-equals` fn-family mirrors the `:rf.assert/*` Story event-family. View-tree assertions live in the sibling `re-frame.test-helpers`. |
  | `re-frame.test-helpers` | core | View-assertion helpers — hiccup-walk (`find-by-testid` / `find-by-attr` family, `text-content`, `extract-handler`, `invoke-handler`), the `testid` authoring helper, and the `expand-tree` walker (per [§Testing — View-assertion helpers](#testing--view-assertion-helpers)). **View-tree axis** — hiccup data, testids, attached handlers. Runtime-state assertions live in the sibling `re-frame.test-support`. |
  | `re-frame.ssr` | `day8/re-frame2-ssr` | `render-to-string`, `render-tree-hash`, `streaming-render-*`, `render-head`, `active-head`, `head-model->html`, `head-snapshot`, `project-error` (per [§SSR](#ssr-spec-011)). |
  | `re-frame.ssr.ring` | `day8/re-frame2-ssr-ring` | the Ring host-adapter (default-html-shell, streaming-prefix/suffix, trusted-shell hooks per Spec 011). |
  | `re-frame.schemas` | `day8/re-frame2-schemas` | `app-schemas`, `app-schema-at`, `app-schema-meta-at`, `app-schemas-digest`, `set-schema-validator!`/-explainer!/-printer!/`set-schema-fns!`, `validate-at-boundary-interceptor` (per [§Schemas](#schemas)). |
  | `re-frame.http` | `day8/re-frame2-http` | the verb helpers `get` / `post` / `put` / `delete` / `patch` / `head` / `options` (per [§HTTP requests](#http-requests-spec-014)). |
  | `re-frame.machines` | `day8/re-frame2-machines` (post-v1 scaffolding) | `reg-machine`, `make-machine-handler`, `machine-transition`, `sub-machine`, `machines`, `machine-meta`, the `:rf.machine/spawn` / `:rf.machine/destroy` fx (per [§Machines](#machines)). |
  | `re-frame.epoch` | `day8/re-frame2-epoch` | `epoch-history`, `restore-epoch!`, `replace-app-db!`, `reset-app-db!`, `replace-runtime-db!`, `replace-frame-state!`, `register-epoch-listener!`, `unregister-epoch-listener!`, `(rf/configure! :epoch-history ...)`. Re-exported through `re-frame.core` via late-bind hooks — `(:require [re-frame.epoch])` at boot before consuming the surfaces through `re-frame.core` (per [Tool-Pair §Time-travel — Artefact home](Tool-Pair.md#time-travel-epoch-snapshots-and-undo)). |
  | `re-frame.adapter.uix` / `re-frame.adapter.helix` | `day8/re-frame2-uix` / `day8/re-frame2-helix` | UIx- and Helix-specific surfaces (per [§UIx adapter](#uix-adapter-spec-006) / [§Helix adapter](#helix-adapter-spec-006)). |

- **Projection-maintenance rule.** This doc is a **non-canonical projection** — the canonical contract lives in the per-Spec docs cited in each row's Spec column. The projection MUST stay in sync with shipped artefacts. Every row carries: **owner** (Spec column) — the canonical spec doc; **artefact / namespace** — where the public-var lives (table above); **public-var status** — `v1` / `v1 (preserved)` / `post-v1 lib` / `post-v1 (planned, rf2-<id>)` for surfaces specced normatively but not yet shipped (the spec contract holds; the impl is tracked by the named bead); **verification pointer** — the conformance fixture, the per-artefact test, or the AI-Audit row that asserts the row holds. Rows that document a surface neither shipped nor on a tracking bead MUST be cut from this projection — the design's normative claim then lives only in the owner spec.

---

## Tier taxonomy

Every public-surface row in this document is either **supported** — a documented API that downstream apps and tools may rely on, in the status the row's Status column gives — or, for the one `implementation` tier, **public-for-technical-reasons only** (exported but explicitly *not* a surface to depend on; see below). *Supported* is not the same as *front-porch*: a system that ships `register-epoch-listener!`, `projected-history`, adapter hooks, and `replace-frame-state!` has a large public surface, but only a small slice of it is what a new app developer should ever reach for. The **Tier** column on every table below records that slice, using a **closed vocabulary** of eight values:

| Tier | Meaning | Who reaches for it |
|---|---|---|
| **front-porch** | The tight set a new app developer needs to build a working app — register / dispatch / subscribe, the frame basics, the everyday reads. Loaded by default by the Guide and the skills. | Every app author, day one. |
| **advanced** | Power-user surfaces that solve real problems but are not first-reach — epoch-listener registration, trace projection, flows internals, the `*`-twin fn forms, the lower-level lifecycle (`install-adapter!`, `init-platform`). Opt-in. | Experienced authors, library authors, niche cases. |
| **tooling** | Dev / inspection surfaces consumed by Story, Xray, the pair-MCP servers, and other [Spec 009](009-Instrumentation.md) / [Tool-Pair](Tool-Pair.md) tools — trace listeners, the registrar query API, off-box-egress projections, the epoch query surface, the Story run-result read accessors. Not for application logic. | Tool and instrumentation authors. |
| **adapter** | The substrate-adapter hooks — the per-substrate `adapter` Var, the hooks and `set-*!` seams a substrate adapter implements or installs. | Adapter authors (Reagent / UIx / Helix / custom). |
| **testing** | Test-only helpers — fixture machinery, assertion helpers, view-tree walkers, the HTTP-stub surfaces. Elided or simply absent from production paths. | Test authors. |
| **internal-public** | A **supported host/tool embedding point**: stable, exported, and safe to call, but **not** an application surface — provided for a specific tool/host *integration*, not for app logic. The Xray `mount-<panel>!` / `mount-shell!` family is the canonical (and now sole) instance — a host that builds its own Xray chrome may call them; an app never should. **Narrower than it looks:** it is *not* the bucket for every exported helper (those are `implementation`); it is the small set a host may legitimately embed against. | Specific host/tool integrations only. |
| **implementation** | **Public-for-technical-reasons only** — exported because a sibling namespace, the tool chrome, or a test must reach it across a namespace boundary (ClojureScript has no cheap cross-namespace-private seam), but it is **NOT a supported surface**: an app or tool MUST NOT depend on it, and it may change or vanish without notice. The Story `run-variant` / migration vocabulary, the per-feature artefact lifecycle/validation/cache helpers (`re-frame.schemas`, `re-frame.ssr`, `re-frame.routing`, `re-frame.machines`, `re-frame.flows`), the Ring host-adapter internals, and the Xray panel-leaf `Panel` reg-views all live here. | Nobody downstream — internal plumbing that merely happens to be `public`. |
| **deprecated** | On the way out; a replacement exists. Retained only long enough for callers to migrate. (Pre-alpha currently carries none — removed surfaces live in [§Removed / not shipped](#removed--not-shipped), not here.) | Nobody new — migrate off. |

**The Guide and the skills load FRONT-PORCH only by default.** A new app developer reading the Guide, or an AI agent operating through the skills, sees the front-porch tier and nothing else unless they opt in. Advanced, tooling, adapter, testing, and internal-public surfaces are **opt-in** — reached by explicitly pulling in the relevant chapter, the relevant artefact, or the relevant tool. The `implementation` tier is **never** reached for: it is not opt-in, it is off-limits — exported only for technical reasons (see the table) and excluded from the supported surface entirely. The acceptance bar for this taxonomy: a new app developer can read the one-page front-porch list (the union of all `front-porch` rows below) and never trip over trace projection, epoch-listener registration, adapter hooks, or Xray internals.

**Tier vs Status.** Tier and Status are orthogonal axes. *Status* records shipping lineage (`v1` / `v1 (preserved)` / `post-v1 lib` / …). *Tier* records who-reaches-for-it. A surface can be `v1` and `advanced` (`register-epoch-listener!`), or `post-v1 lib` and `front-porch` (the Story `run` verb is `post-v1 lib`/`tooling`; a hypothetical post-v1 ergonomic core helper would be `post-v1 lib`/`front-porch`). Read the two columns together.

**The front-porch / back-room split is the first instance, generalised.** The multi-frame surface (§View ergonomics, per [002 §The multi-frame surface](002-Frames.md#the-multi-frame-surface--choose-by-intent)) was already organised as a *front-porch / back-room* split — `dispatch` / `subscribe` / `with-frame` on the porch, `frame-bound-fn*` and the `{:frame …}` override in the back room. The Tier column generalises that split across the whole API: front-porch is the porch; advanced / tooling / adapter / testing / internal-public are the back rooms (all still *supported*); `implementation` is below the floorboards — exported but off the supported surface entirely.

**Closed vocabulary, restrictive-by-default.** The eight values above are the complete set — no ninth tier is added without an explicit governance decision. When a surface is genuinely ambiguous between two tiers, pick the **more restrictive** one (advanced over front-porch; internal-public over advanced; `implementation` over internal-public when the var is not actually a host/tool *integration* point but merely an exported helper) and note the call in the row's Notes. The downstream manifest consumes `Tier` as a first-class field, so the value must come from this closed set. **The `internal-public` / `implementation` boundary**: ask "is this a surface a host or tool legitimately embeds against?" — if yes, `internal-public` (the Xray mount family); if it is exported only so the framework's own namespaces / tests can reach it across a boundary, `implementation`. The two were previously conflated under an over-broad `internal-public`; the split keeps the *supported* surface honest.

**What the Tier column covers.** Tier is a property of a **public var** (a fn / macro / Var). The tables that enumerate *keyword-addressed registrations* — standard events (`:rf.route/navigate`, `:rf/hydrate`, …), standard subs (`:rf/route`, `:rf/response`, …), standard fx (`:rf.nav/push-url`, `:rf.http/managed`, …), standard cofx, reserved fx-ids, the `:fx`-entry catalogue, the spec-internal schemas, the configure keys, and the error/trace-event catalogues — are **not** vars and carry no Tier column. They are part of the contract of whichever artefact owns them; their availability follows that artefact's tier (e.g. the `:rf.route/*` events ship with the `advanced` routing artefact). The front-porch one-page list is the union of `front-porch`-tiered **var** rows.

### Tiering of cross-tool surfaces (Story, Xray, pair-MCP)

Story, Xray, and the MCP support namespaces ship their *own* public-var rows in their *own* specs ([007-Stories.md](007-Stories.md), [tools/xray/spec](../tools/xray/spec), [Tool-Pair.md](Tool-Pair.md)); this projection rows only the slices that surface through `re-frame.core` or the per-feature artefacts. The Tier taxonomy is nonetheless **repo-wide and authoritative over those surfaces** — the per-tool specs classify against this closed vocabulary rather than inventing local terms. The standing classifications (resolved here, so the per-tool specs reference rather than re-litigate):

- **Story facade** — the public execution verbs `run` / `is` / `explain` and the registration macros (`reg-story` / `reg-variant` / `reg-workspace` / `reg-tag` / `reg-decorator` / `reg-story-panel`) are **tooling** (a Storybook-shaped dev surface, not application logic). The **run-result read accessors** — `run-result`, `result-status`, `result-passed?`, `run-result-schema`, `valid-run-result?`, `explain-run-result` — are **tooling** too: the statement-of-record for reading what a `run` produced (per [story spec 017 §Run result](../tools/story/spec/017-Testing-Story.md)). The `run-variant` / `is-variant` / `run-plan` / `is-plan` / `watch-variant` / `reset-variant` implementation/migration vocabulary is **`implementation`** — public-for-technical-reasons only (the chrome and tests reach it across namespaces), explicitly NOT the supported execution surface. (This slice was previously described as "not public, unrowed" while the manifest parked it in an over-broad `internal-public`; the `implementation` tier is its honest home — public as a var, off the supported surface.) (Absorbs story F-8.)
- **Xray `mount-<panel>!` family** (`mount-epoch-panel!`, `mount-app-db-diff!`, `mount-trace!`, `mount-machine-inspector!`, `mount-routing!`, `mount-segment-inspector!`, the master `mount-shell!`, …) — **internal-public**, the canonical (and now sole) instance of that tier. They are exported and stable, but they are a host-embed surface, not an application or even general-tool surface: a host that builds its own Xray chrome may call a `mount-<panel>!`; an app never should. This resolves the prior "public vs internal-but-stable" question against the closed vocabulary — the answer is `internal-public`. (Resolves xray M2.)
- **Xray panel-leaf `Panel` reg-views** (`day8.re-frame2-xray.panels.<area>/Panel`, the Static-mode `panel`) — **`implementation`**. They are exported only so the shell can compose them across namespaces; they are NOT a host-facing single-panel embed surface — a host embeds the *full shell* via `mount-shell!` (per [008-Embedding-Contract](../tools/xray/spec/008-Embedding-Contract.md)), never a bare `Panel`. Demoted off `internal-public` (now reserved for the supported mount-embed surface) so the leaves do not read as a supported embed API.
- **Xray panel-helper functions** (the per-panel render/projection helpers beneath the mount-fns) — **tooling** where they are a documented panel-author surface, otherwise unrowed-internal. (Absorbs xray H6.)
- **pair-MCP support namespaces** — the trace/egress surfaces they consume (`elide-wire-value`, `sensitive?`, the registrar query API, the epoch query surface) are **tooling**, tiered at their `re-frame.core` / artefact home rows below.

### Not-rowed internal carve-outs

The only **internal** (unrowed, MUST-NOT-depend-on) carve-outs in `re-frame.core` are two JVM-only macro-helpers re-exposed purely so pre-split tests can reach them:

- `re-frame.core/expand-reg-view` — `^:no-doc`; the canonical home is `re-frame.core-reg-view-macro/expand-reg-view`.
- `re-frame.core/parse-reg-view-args` — `^:no-doc`; the canonical home is `re-frame.core-reg-view-macro/parse-reg-view-args`.

Neither is rowed in this projection. Applications and tools MUST NOT depend on these re-exports; reach the canonical homes directly. These are *not* a tier — they are below the public surface entirely.

---

## Registration

> **Return value.** Every `reg-*` row below returns its **primary id** — the keyword (or path, for `reg-app-schema`) the caller registered with. `reg-flow` returns the `:id` value of its flow-map (the primary id is carried by the map, not a separate arg). Per [Conventions §`reg-*` return-value convention](Conventions.md#reg--return-value-convention).

| API | M/Fn | Signature | Status | Tier | Spec | Notes |
|---|---|---|---|---|---|---|
| `reg-event` | M | `(reg-event id ?metadata handler)` | EP-0018 | front-porch | 002 | The ONE public event form (EP-0018) — a two-arg `(fn [coeffects event-vec] effect-map)` handler, coeffects in, a closed `#{:db :rf.db/runtime :fx}` effects map out (or `nil` no-op). The db write is an explicit `{:db …}` effect; there is no db-only return shape. Coeffects declared uniformly via `:rf.cofx/requires`. Full-context work is expressed with a registered interceptor (`reg-interceptor`, referenced by id — EP-0022). Metadata-map superset middle slot carries the reserved `:interceptors` key (a vector of interceptor **refs**). The former `reg-event-db`/`reg-event-fx` are removed and public `reg-event-ctx` is demoted to a framework-internal primitive — calling any of the retired names is a hard error (see [§The retired event-registration names](001-Registration.md#the-retired-event-registration-names) + the [Removed §](#removed--not-shipped)). |
| `reg-sub` | M | `(reg-sub id ?metadata input-fn? computation-fn)` | v1 (preserved + extended) | front-porch | 002 | The only sub-registration form in v2. Three input-production modes (app-db reader / static `:<-` / parametric `input-fn`) — see [§`reg-sub` input-production modes](#reg-sub-input-production-modes). The optional first fn is a v2 **`input-fn`** (`query-v → vector-of-query-vectors`), NOT a v1 reaction-returning signal fn. `:<-` sugar preserved. |
| `reg-fx` | M | `(reg-fx id ?metadata handler)` | v1 (preserved + extended) | front-porch | 002 | Unary or binary handler. |
| `reg-cofx` | M | `(reg-cofx id ?metadata supplier)` | v1 (changed, EP-0017) | front-porch | 001, 002 | Register a coeffect id with a **value-returning supplier** (`(fn [] v)` / `(fn [arg] v)`) and a registration grade — ambient (default) or recordable (`:recordable? true`, optionally `:provided? true`). A handler takes delivery by declaring `:rf.cofx/requires` (the value arrives FLAT under the id; [001 §`:rf.cofx/requires`](001-Registration.md#rfcofxrequires--the-declaration-key)). `:rf/time-ms` is the framework's one provided recordable registration. The ctx→ctx handler shape and `inject-cofx` are retired (EP-0017 slice A, no alias). See [§Coeffects](001-Registration.md#coeffects--reg-cofx-value-returning-graded). |
| `reg-interceptor` | M | `(reg-interceptor id ?metadata descriptor)` | EP-0022 | front-porch | 001, 002 | The public application-authoring form for an interceptor — a first-class registered program member (registrar kind `:interceptor`). `descriptor` is one of `{:before f}` / `{:after f}` / `{:before f :after f}` (static) or `{:factory f}` (a parameterized family; the factory takes ONE arg and is the mechanism the standard `[:rf.interceptor/path …]` rides). Event/frame `:interceptors` chains reference registered interceptors by id (bare keyword) or `[id arg]`, never inline values. Captures source coords; surfaces via `handler-meta :interceptor`. A migration value carrying an `:id` is accepted at this boundary only (the id must match). Replaces `->interceptor` as the public authoring surface. See [001 §Interceptors](001-Registration.md#interceptors--reg-interceptor-the-interceptor-registrar) + [002 §Registered interceptors and the chain grammar](002-Frames.md#registered-interceptors-and-the-chain-grammar). |
| `reg-interceptor*` | Fn | `(reg-interceptor* id ?metadata descriptor)` | EP-0022 | advanced | 001 | Plain-fn surface beneath `reg-interceptor` for tooling / REPL / programmatic registration — no macro source-coordinate capture. The `*` follows Clojure's `let`/`let*` idiom (per [Conventions §`*`-suffix naming](Conventions.md#-suffix-naming-for-fn-versions-of-macros)). |
| `reg-frame` | M | `(reg-frame id metadata)` | v1 | front-porch | 002 | Atomic create + register. |
| `make-frame` | Fn | `(make-frame opts)` / `(make-frame opts descriptors)` → live frame **object** | EP-0023 | advanced | 002 | The EP-0023 OBJECT constructor: builds a live frame from `:images` (always a vector) and **returns the runnable live frame object** in all cases — `dispatch` / `subscribe` / `destroy-frame!` accept it (or its `:id`). Opts: `:images` / `:id` (registers in the process-local live-frame registry, fail-loud on a duplicate) / `:initial-db` (seed frame state) / `:capabilities` / `:adapter`. A **record-only** config key (`:on-create`, `:fx-overrides`, `:platform`, `:ssr`, `:doc`, `:preset`, `:tags`, …) fails loud `:rf.error/make-frame-record-only-key` rather than silently dropping — the record-config surface lives on the advanced `re-frame.frame/make-frame` (gensym id). The named `reg-frame` is the front-porch path; `make-frame` is for tools / tests / dynamic / image-loaded frames. Repointed onto the object constructor in the EP-0023 collapse FINALE (rf2-32siq3.48); the dual-export transition guard is retired. |
| `reg-view` | M | `(reg-view sym [args] body+)` / `(reg-view sym docstring [args] body+)` / `(reg-view ^{:rf/id :explicit/id} sym [args] body+)` | v1 | front-porch | 004 | Defn-shape; auto-defs the symbol; auto-derives id from `(keyword *ns* sym)`; auto-injects `dispatch` / `subscribe` as lexical bindings; rejects non-defn-shape bodies at macroexpand. |
| `reg-view*` | Fn | `(reg-view* id render-fn)` / `(reg-view* id metadata render-fn)` | v1 | advanced | 004 | Plain-fn surface beneath `reg-view`. No auto-def, no auto-inject, no compile check. Use for computed ids, library-generated views, Reagent Form-3 (`create-class`), or registration without a Var. The `*` follows Clojure's `let`/`let*`, `fn`/`fn*` idiom (per [Conventions](Conventions.md)). |
| `reg-machine` | M | `(reg-machine machine-id machine-spec)` / `(reg-machine machine-id opts machine-spec)` | v1 | advanced | 005 | Optional `re-frame.machines` artefact. Walks the literal spec form at expansion time; co-locates per-element source on each `:guards` / `:actions` entry + a reference-site `:source-coords` on each `:states`-tree map node. Top-level call-site coords land on `handler-meta`. The optional `opts` map carries an event-vector `:schema` (the `:where :event` boundary on the dispatched outer vector) — the machine + event-vector-schema shape. |
| `defmachine` | M | `(defmachine name spec)` / `(defmachine name docstring spec)` | v1 | advanced | 005 | `def`-shape for the `def`-then-register pattern. Walks the literal spec at the definition site, stamping per-element source onto the def'd value so it travels into a later `(reg-machine id name)`. Does not register. |
| `reg-app-schema` | M | `(reg-app-schema path schema)` / `(reg-app-schema path schema opts)` | v1 | advanced | 010 | Optional `re-frame.schemas` artefact. **Path is the registration id.** App-db schemas are path-keyed and live in the schemas artefact's per-frame side-table (app-db schemas are NOT a registrar kind). Every other `reg-*` is keyword-id-keyed; here the first arg is the path vector (e.g. `[:user]`) and `(app-schema-at [:user])` / `(app-schema-meta-at [:user])` look up by the same vector. The asymmetry is principled (paths are first-class in `get-in` / `assoc-in` grain — schemas-at-paths matches the dataflow grain), not accidental. Per [Conventions §`reg-*` return-value rule](Conventions.md#reg--return-value-convention). |
| `reg-app-schemas` | M | `(reg-app-schemas {path-1 schema-1, path-2 schema-2, ...})` / `(reg-app-schemas {…} opts)` — bulk plural form for feature-modular apps that register 5–20 paths against the same prefix (per Conventions §Feature-modularity prefix convention). Each entry routes through the singular `reg-app-schema` and is stamped with this call's source-coords. Returns the vector of paths registered | v1 | advanced | 010 | |
| `reg-flow` | Fn | `(reg-flow flow)` / `(reg-flow flow opts)` | v1 | advanced | 013 | Optional flows artefact. `opts` is a map (currently `{:frame frame-id}`). The shipped surface is opts-map only — same shape as `dispatch` / `subscribe` / `clear-flow`. Returns the flow's `:id` (per Conventions §`reg-*` return-value convention). **The `:flow` registrar slot is last-registration-wins across frames** (the same id registered against multiple frames shares one registrar slot, keyed by `flow-id` only); for full per-frame discovery use `@re-frame.flows/flows` — the per-frame runtime registry is the source of truth for evaluation. Per [013-Flows.md §Frame-scoping](013-Flows.md#frame-scoping) and. |
| `reg-route` | M | `(reg-route id metadata)` | v1 | advanced | 012 | Optional routing artefact. |
| `reg-head` | M | `(reg-head id ?metadata head-fn)` | v1 | advanced | 011 | Optional SSR artefact. New registry kind `:head`; routes name a registered head via `:head` route metadata. Captures source-coords; under the optional-artefact wrapper convention the surface routes through the `:ssr/reg-head` late-bind hook. |
| `reg-error-projector` | M | `(reg-error-projector id ?metadata projector-fn)` | v1 | advanced | 011 | Optional SSR artefact. New registry kind `:error-projector`; named per-frame via the frame's `:ssr {:public-error-id ...}` metadata (per `reg-frame` / `make-frame`). |

### `reg-sub` input-production modes

`reg-sub` supports **three input-production modes**. Every subscription has an *input query-vector producer*: layer-1 has no producer; `:<-` is the literal producer; `input-fn` is the query-parametric producer.

| Mode | Form | Meaning |
|---|---|---|
| App-db reader | `(reg-sub id computation-fn)` | No upstream subscriptions. The computation fn receives `app-db` and the outer `query-v`. |
| Static inputs | `(reg-sub id :<- q1 :<- q2 computation-fn)` | Inputs are literal query vectors known at registration. |
| Parametric inputs | `(reg-sub id input-fn computation-fn)` | Inputs are computed from the outer `query-v` when a concrete cache entry is materialized. |

The two-function form's first fn is a v2 **`input-fn`** — a **pure** function from the outer `query-v` to a vector of input query vectors. It is **not** a v1 signal function: it must not call `subscribe`, deref `app-db`, dispatch, mutate, or perform IO; it receives only the outer `query-v`; and it must not return live reactions. The `computation-fn` receives the vector of resolved input values (in the same order) and the outer `query-v`.

```clojure
(rf/reg-sub
  :article/page
  (fn input-fn [[_ article-id]]
    [[:article/by-id article-id]
     [:comments/for-article article-id]
     [:viewer/current]])
  (fn computation-fn [[article comments viewer] [_ article-id]]
    {:id article-id :article article :comments comments
     :can-edit? (:edit? viewer)}))
```

**Input grammar.** An `input-fn` MUST return a vector, and every element of that vector MUST be a query vector (a vector whose first element is a keyword):

```clojure
input-return := [query-vector*]      ;; query-vector := vector with a keyword head
```

```clojure
;; Accepted
[[:article/by-id id] [:viewer/current]]   ;; multiple inputs
[[:item/by-id id]]                        ;; single input — still a vector OF query vectors
[]                                        ;; no inputs (unusual but valid)

;; Rejected — see :rf.error/sub-input-fn-bad-return
:viewer/current                           ;; bare keyword
[:article/by-id id]                       ;; scalar query vector (ambiguous: arg vs two inputs)
[[:article/by-id id] :viewer]             ;; mixed vector + bare keyword
{:article [:article/by-id id]}            ;; map return
```

The scalar query-vector rejection is deliberate: `[:x :y]` is ambiguous at this boundary (one query with argument `:y`, vs two inputs). The only accepted single-query spelling is `[[:x :y]]`. No bare keyword shorthand, no map return, no reaction/derefable. Use `:<-` for static inputs; reach for `input-fn` **only** when the upstream query vectors need values from the outer `query-v`. The static `:<-` form is exactly a constant `input-fn` (`(fn [_] [[:items] [:filter]])`). Per [006 §Subscription input producers](006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn), [008 §`compute-sub` algorithm](008-Testing.md#compute-sub-algorithm), and [Conventions §`reg-sub` input grammar](Conventions.md#reg-sub-input-grammar--input-fn-returns-a-vector-of-query-vectors). Registration-shape and input-return errors signal loudly via `:rf.error/reg-sub-bad-args`, `:rf.error/sub-input-fn-exception`, and `:rf.error/sub-input-fn-bad-return` (catalogued in [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue)).

### Clearing registrations

| API | M/Fn | Signature | Status | Tier |
|---|---|---|---|---|
| `clear-event` | Fn | `(clear-event)` / `(clear-event id)` | v1 (preserved) | advanced |
| `clear-sub` | Fn | `(clear-sub)` / `(clear-sub id)` | v1 (preserved) | advanced |
| `clear-fx` | Fn | `(clear-fx)` / `(clear-fx id)` | v1 (preserved) | advanced |
| `destroy-frame!` | Fn | `(destroy-frame! frame-id)` — the normative teardown boundary. Per-feature artefacts (flows, machines, schemas, SSR, epoch) hang their frame-scoped cleanup off this call; flows release per [013 §Frame-destroy teardown](013-Flows.md#frame-destroy-teardown). | v1 | front-porch |
| `reset-frame!` | Fn | `(reset-frame! frame-id)` | v1 | advanced |
| `clear-sub-cache!` | Fn | `(clear-sub-cache! frame-id?)` | v1 (preserved) | advanced |

---

## Dispatch and subscribe

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `dispatch` | M | `(dispatch event)` / `(dispatch event opts)` | v1 (preserved + extended); macro captures call-site for `:rf.trace/call-site` | front-porch | 002 |
| `dispatch*` | Fn | `(dispatch* event)` / `(dispatch* event opts)` | — fn form for HoF / programmatic dispatch (no call-site stamping) | advanced | 002 |
| `dispatch-sync` | M | `(dispatch-sync event)` / `(dispatch-sync event opts)` | v1 (preserved + extended); macro captures call-site for `:rf.trace/call-site` | front-porch | 002 |
| `dispatch-sync*` | Fn | `(dispatch-sync* event)` / `(dispatch-sync* event opts)` | — fn form for HoF / programmatic sync dispatch | advanced | 002 |
| `subscribe` | M | `(subscribe query-v)` / `(subscribe frame-id query-v)` | v1 (preserved + extended); macro captures call-site for `:rf.trace/call-site` | front-porch | 002 |
| `subscribe*` | Fn | `(subscribe* query-v)` / `(subscribe* frame-id query-v)` | — fn form for HoF / programmatic subscribe | advanced | 002 |
| `subscribe-once` | Fn | `(subscribe-once query-v)` / `(subscribe-once frame-id query-v)` → value (subscribe + deref + immediate unsubscribe; one-shot, non-reactive read for handler bodies, machine actions, REPL) | v1 | advanced | 006 |
| `unsubscribe` | Fn | `(unsubscribe query-v)` / `(unsubscribe frame-id query-v)` → nil (decrement the cache ref-count; ref-count→0 schedules disposal after the configured `:sub-cache` grace-period). Carved out from the [Conventions §Tear-down verb axis](Conventions.md#tear-down-verb-axis--clear--vs-destroy-) — `clear-sub` is already taken by the symmetric inverse of `reg-sub` (the registrar decrement), so `un-` is reserved as the singular form for the sub-cache ref-count decrement. | v1 | advanced | 006 |

To read a machine's snapshot, subscribe to the canonical `[:rf/machine machine-id]` vector (see [§Standard registered subs (machines)](#standard-registered-subs-machines)).

`opts` map keys: `:frame`, `:fx-overrides`, `:interceptor-overrides`, `:trace-id`, `:source`. Envelope shape and semantics: see [002 §Routing: the dispatch envelope](002-Frames.md#routing-the-dispatch-envelope).

**Canonical `event` / `query-v` shape (best practice).** `[<id>]` (trivial), `[<id> <single-scalar>]` (single-arg), `[<id> {<k> <v>}]` (multi-arg → single map payload). Variadic `[<id> a b c]` is tolerated by the runtime for v1-migration and caller convenience; the linter nudges new code toward the map form. Full rationale and cross-refs: [Conventions §Canonical event-vector shape](Conventions.md#canonical-event-vector-shape-best-practice).

### `dispatch-*` family taxonomy

Per audit-of-audits state-machines #10, the `dispatch-*` family has two sub-shapes that look alike on first read but answer different questions. Both *are* dispatch operations — the family-prefix is honest — but they sit in different sub-families.

**Stamping-pair sub-family** (`dispatch` / `dispatch-sync` macros + `dispatch*` / `dispatch-sync*` fn variants).
The pair-shape question is **"do you want call-site stamping or not?"** The macro form captures `:rf.trace/call-site` from the surrounding source position so tooling can navigate from a trace event back to the originating expression. The `*` fn-form skips the stamping — needed for HoF composition (`(map dispatch* events)`) where a macro can't sit inside the higher-order call. Both shapes route through the same dispatcher; only the trace stamping differs.

**Named-target sugar sub-family** (`dispatch-to-system`, per [005 §Cross-machine messaging by name](005-StateMachines.md)).
This question is **"do you have a `:system-id` instead of a target machine-id?"** `dispatch-to-system` is sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))` — it dispatches just like the macros, but it resolves the target through the per-frame `[:rf.runtime/machines :system-ids]` reverse index first. It is **not** outside the dispatch family; it's a named-addressing sugar built on top of `dispatch`. The hyphen-after-`dispatch` reads as "dispatch with extra routing logic on top," not "different kind of dispatch."

The two sub-families compose: `dispatch-to-system` ultimately calls `dispatch`, so the same `:rf.trace/call-site` stamping fires (call-site at the `dispatch-to-system` invocation, since that's the macro the user wrote). When future named-addressing variants land (per actor-model patterns) they slot into the same sub-family; new call-site-stamping variants slot into the same pair.

---

## View ergonomics

The multi-frame surface is organised by **intent**, not mechanism (a front-porch / back-room split — per [002 §The multi-frame surface](002-Frames.md#the-multi-frame-surface--choose-by-intent)):

- **Single-frame** (no frames in play): `dispatch`, `dispatch-sync`, `subscribe`.
- **Scope:** `with-frame`, `with-new-frame`, `frame-provider`.
- **Hold** (carry a frame's ops as a value, across async): `frame-handle` (common), `frame-bound-fn` / `frame-bound-fn*` (advanced).
- **Override:** the `{:frame …}` opt — first-class explicit routing for tools / tests / SSR / fx handlers.
- **Reads / lifecycle:** `app-db-value`, `current-frame-id`, `snapshot-of`, `destroy-frame!`, `make-frame`, `reg-frame`, `frame-ids`, `frame-meta` (see [§Public registrar query API](#public-registrar-query-api)).

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `frame-provider` | Component (Reagent) | `[rf/frame-provider {:frame :todo} & children]` | v1 | front-porch | 002 |
| `with-frame` | M | `(with-frame :keyword body)` — pin to an existing frame-id. Vector arg is a compile-time error (use `with-new-frame`) | v1 | front-porch | 002 |
| `with-new-frame` | M | `(with-new-frame [sym expr] body)` — eval `expr`, bind `sym`, run body, destroy frame on exit. Keyword arg is a compile-time error (use `with-frame`) | v1 | front-porch | 002 |
| `frame-handle` | Fn | `(frame-handle)` *or* `(frame-handle frame-id)` → `{:frame :dispatch :dispatch-sync :subscribe}` — the keystone OPERATION BUNDLE. Captures the frame at CREATION; its ops always target the captured frame and survive async. Read app-db via `(app-db-value (:frame h))`, not the handle | v1 | front-porch | 002, 004 |
| `frame-bound-fn` | M | `(frame-bound-fn [args] body)` → frame-rebinding closure (`fn`-syntax sugar over `frame-bound-fn*`). Re-establishes `*current-frame*` (captured at lex-binding) for the body | v1 | advanced | 002 |
| `frame-bound-fn*` | Fn | `(frame-bound-fn* f)` *or* `(frame-bound-fn* frame-id f)` → frame-rebinding closure. The `*`-twin of the `frame-bound-fn` macro — wrap an existing callback so its body always runs with `*current-frame*` re-bound. Advanced; prefer `frame-handle` for the common dispatch / subscribe case | v1 | advanced | 002 |
| `view` | Fn | `(view view-id)` → **render-fn** (runtime-lookup handle; returns the registered render-fn, *not* hiccup). Use in hiccup as `[(rf/view :id) args...]` — the lookup form for late-binding a registered view by id. | v1 | advanced | 001, 004 |

`with-frame` (pin) and `with-new-frame` (eval-bind-run-destroy) are documented in [002 §with-frame and with-new-frame](002-Frames.md#with-frame-and-with-new-frame). The macros are non-overlapping: each rejects the other's argument shape at compile time, with `:recovery` pointing the caller at the right sibling.

`frame-handle` is the keystone affordance — it replaces the removed `dispatcher` / `subscriber` nouns and is the single answer to "carry a frame's dispatch/subscribe ops across an async boundary." **The handle is locked:** a per-call `:frame` opt MUST NOT override the frame captured at handle creation — the captured frame always wins (per [002 §`frame-handle`](002-Frames.md#frame-handle--the-keystone-affordance-cljs-reference)). `frame-bound-fn` is a CLJS-only macro; CLJS users either reach it via `rf/frame-bound-fn` (after `(:require [re-frame.core :as rf])`) or `:require-macros [re-frame.core :refer [frame-bound-fn]]`. `frame-bound-fn*` is a plain fn, available on both CLJS and JVM (no macro indirection).

---

## UIx adapter (Spec 006)

UIx-specific surfaces live in `re-frame.adapter.uix` (artefact `day8/re-frame2-uix`) — they are NOT re-exported from `re-frame.core` because core has no static dependency on the adapter (the dependency direction is adapter → core per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention)). Apps targeting UIx `:require [re-frame.adapter.uix :as uix-adapter]` and call the surfaces directly.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `uix-adapter/adapter` | Var (map) | `{:make-state-container … :render … :dispose-adapter! …}` | v1 | adapter | 006 |
| `uix-adapter/use-subscribe` | Fn (UIx hook) | `(use-subscribe query-v)` / `(use-subscribe frame-kw query-v)` → current sub value | v1 | adapter | 006 |
| `uix-adapter/use-current-frame` | Fn (UIx hook) | `(use-current-frame)` → frame-kw | v1 | adapter | 006 |
| `uix-adapter/frame-provider` | Fn (UIx component) | `($ uix-adapter/frame-provider {:frame :session} child-1 child-2)` — idiomatic `$` trailing children | v1 | adapter | 002, 006 |
| `uix-adapter/wrap-view` | Fn | `(wrap-view id metadata user-fn)` → wrapped fn (source-coord injection per Spec 006 §Source-coord annotation) | v1 | adapter | 006 |
| `uix-adapter/flush-views!` | Fn | `(flush-views!)` / `(flush-views! f)` — wraps React's `act()` for tests | v1 | adapter | 006, 008 |
| `uix-adapter/set-hiccup-emitter!` | Fn | `(set-hiccup-emitter! f)` — install render-tree → HTML fn (parity with the Reagent adapter's late-bind seam) | v1 | adapter | 006, 011 |

Per Decision 1 the hook is named `use-subscribe` (matching the React/UIx idiom). Per Decision 3 there is no auto-injection — UIx components call the hook and `(:dispatch (rf/frame-handle))` directly. Per Decision 4 `reg-view` (the Reagent macro) does NOT cover UIx; UIx users register with `rf/reg-view*` if they need registry-keyed view addressing.

The shared React Context that backs `frame-provider` lives in `re-frame.adapter.context` (CLJS-only file in core, factored out per Decision 2) — both the Reagent adapter and the UIx adapter consume the same `createContext` object so a future mixed-substrate app's frame-provider chain composes across substrates.

---

## Helix adapter (Spec 006)

Helix-specific surfaces live in `re-frame.adapter.helix` (artefact `day8/re-frame2-helix`) — they are NOT re-exported from `re-frame.core` because core has no static dependency on the adapter (the dependency direction is adapter → core per [Conventions §Adapter shipping convention](Conventions.md#adapter-shipping-convention)). Apps targeting Helix `:require [re-frame.adapter.helix :as helix-adapter]` and call the surfaces directly. The Helix adapter mirrors the UIx adapter exactly — the eight UIx-adapter decisions transfer one-for-one.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `helix-adapter/adapter` | Var (map) | `{:make-state-container … :render … :dispose-adapter! …}` | v1 | adapter | 006 |
| `helix-adapter/use-subscribe` | Fn (Helix hook) | `(use-subscribe query-v)` / `(use-subscribe frame-kw query-v)` → current sub value | v1 | adapter | 006 |
| `helix-adapter/use-current-frame` | Fn (Helix hook) | `(use-current-frame)` → frame-kw | v1 | adapter | 006 |
| `helix-adapter/frame-provider` | Fn (Helix component) | `($ helix-adapter/frame-provider {:frame :session} child-1 child-2)` — idiomatic `$` trailing children | v1 | adapter | 002, 006 |
| `helix-adapter/wrap-view` | Fn | `(wrap-view id metadata user-fn)` → wrapped fn (source-coord injection per Spec 006 §Source-coord annotation) | v1 | adapter | 006 |
| `helix-adapter/flush-views!` | Fn | `(flush-views!)` / `(flush-views! f)` — wraps React's `act()` for tests | v1 | adapter | 006, 008 |
| `helix-adapter/set-hiccup-emitter!` | Fn | `(set-hiccup-emitter! f)` — install render-tree → HTML fn (parity with the Reagent and UIx adapters' late-bind seam) | v1 | adapter | 006, 011 |

Per (transferring Decision 1) the hook is named `use-subscribe`. Per Decision 3 there is no auto-injection — Helix components call the hook and `(:dispatch (rf/frame-handle))` directly. Per Decision 4 `reg-view` (the Reagent macro) does NOT cover Helix; Helix users register with `rf/reg-view*` if they need registry-keyed view addressing.

The shared React Context that backs `frame-provider` lives in `re-frame.adapter.context` (CLJS-only file in core, factored out per Decision 2) — the Reagent, UIx, and Helix adapters all consume the same `createContext` object so a mixed-substrate app's frame-provider chain composes across substrates.

---

## Routing (Spec 012)

`reg-route` is rowed canonically in [§Registration](#registration).

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `match-url` | Fn | `(match-url url)` → `{:route-id :params :query :validation-failed?}` or `nil` | v1 | advanced | 012 |
| `route-url` | Fn | `(route-url route-id path-params)` / `(route-url route-id path-params query-params)` → URL string | v1 | advanced | 012 |
| `route-link` | Fn (registered view at `:route/link`) | `[rf/route-link {:to :route-id :params {...} :query {...} :fragment "..." & html-attrs} & children]` | v1 | advanced | 012 |

`reg-route` metadata reserved keys: `:doc`, `:path`, `:params`, `:query`, `:query-defaults`, `:query-retain`, `:tags`, `:parent`, `:on-match`, `:on-error`, `:can-leave`, `:scroll`. Canonical detail in [012-Routing.md](012-Routing.md); shape in [Spec-Schemas §`:rf/route-metadata`](Spec-Schemas.md#rfroute-metadata).

`route-link` click rules: a plain primary-button click (no modifier keys, no `defaultPrevented`) calls `.preventDefault` and dispatches `[:rf/url-requested {:url <synthesised> :to <route-id> :params {...} :query {...} :fragment "..."}]`. Modifier-key clicks (cmd / ctrl / shift / alt) and auxiliary-button clicks (middle-click) defer to the browser so the native `href` opens in a new tab. A caller-supplied `:on-click` runs first; if it calls `.preventDefault` (or otherwise leaves `defaultPrevented` true) the framework's interception is skipped. Keys other than `:to` / `:params` / `:query` / `:fragment` / `:on-click` pass through to the underlying `<a>` element. Detailed semantics in [012-Routing.md §Linking from views](012-Routing.md#linking-from-views--plain-anchor-semantics).

Standard route-related events:

| Event | Notes | Spec |
|---|---|---|
| `:rf.route/navigate` | Navigate to a registered route. | 012 |
| `:rf.route/handle-url-change` | URL-change handler for popstate / initial load / SSR (default scroll `:restore`). Co-equal sibling of `:rf.route/transitioned`, not a delegate. | 012 |
| `:rf.route/transitioned` | URL-change handler for forward navigation (link click / programmatic push; default scroll `:top`). | 012 |
| `:rf/url-requested` | The user clicked a framework-owned link. | 012 |
| `:rf.route/navigation-blocked` | A `:can-leave` guard rejected a navigation. | 012 |
| `:rf.route/continue` | User-dispatched event proceeding a blocked navigation. | 012 |
| `:rf.route/cancel` | User-dispatched event abandoning a blocked navigation. | 012 |

Standard route-related subs:

| Sub | Returns | Spec |
|---|---|---|
| `:rf/route` | The full `:rf/route` slice `{:route-id :params :query :transition :error}` | 012 |
| `:rf.route/id` | Current route id | 012 |
| `:rf.route/params` | Current path params | 012 |
| `:rf.route/query` | Current query params | 012 |
| `:rf.route/transition` | `:idle` / `:loading` / `:error` | 012 |
| `:rf.route/error` | Current error map (when `:transition = :error`) | 012 |
| `:rf.route/fragment` | Current URL fragment (string or nil) | 012 |
| `:rf.route/chain` | Vector of route ids from parent-most to current (per `:parent` links) | 012 |
| `:rf/pending-navigation` | The pending-nav slot (per `:rf/pending-navigation` schema) when a navigation is blocked; `nil` otherwise | 012 |

Standard route-related fx (canonical detail in [012-Routing.md](012-Routing.md)):

| Fx | Args | Platforms |
|---|---|---|
| `:rf.nav/push-url` | URL string | `:client` |
| `:rf.nav/replace-url` | URL string | `:client` |
| `:rf.nav/scroll` | scroll-spec map | `:client` |
| `:rf.route/with-nav-token` | `{:rf/reply-to <reply-target> :nav-token <token>}` | universal |

Standard route-related cofx (canonical detail in [012-Routing.md](012-Routing.md)):

| Cofx | Delivers | Spec |
|---|---|---|
| `:rf.route/nav-token` | The active navigation epoch token (read from `[:rf.runtime/routing :current :nav-token]`), delivered flat under the coeffect key `:rf.route/nav-token` — declare via `{:rf.cofx/requires [:rf.route/nav-token]}` in an `:on-match`-reached handler to capture the epoch live at scheduling time for stale-result suppression. Per [012 §Navigation tokens](012-Routing.md#navigation-tokens--stale-result-suppression). | 012 |

---

## SSR (Spec 011)

> **Namespace:** the surfaces below live in `re-frame.ssr` (artefact `day8/re-frame2-ssr`); consumers `(:require [re-frame.ssr :as ssr])`. The Ring host-adapter lives in `re-frame.ssr.ring` (artefact `day8/re-frame2-ssr-ring`). The **SSR query surface** (`render-to-string`, `render-tree-hash`, `project-error`, `render-head`, `active-head`, `head-model->html`, `head-snapshot`) is **also late-bind re-exported through `re-frame.core`** — one of the two documented façade exceptions (the other is epoch), per the §Conventions per-artefact namespace table. The **streaming** surface (`streaming-render-*`) and the **Ring host-adapter** (`re-frame.ssr.ring`) are NOT re-exported — they are host-adapter territory; an SSR-aware host requires the namespace directly. Apps targeting SSR add the artefacts to their deps regardless.

`reg-head` and `reg-error-projector` are rowed canonically in [§Registration](#registration). The head-fn signature is `(fn [db route] head-model)`; the projector-fn signature is `(fn [trace-event] :rf/public-error)`.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `render-to-string` | Fn | `(render-to-string view-or-hiccup opts)` → HTML string | v1 | advanced | 011 |
| `render-tree-hash` | Fn | `(render-tree-hash render-tree)` → 32-bit FNV-1a structural hash (lowercase hex). Identical output on JVM and CLJS for the same canonical-EDN representation. Per [011 §Hydration-mismatch detection](011-SSR.md). | v1 | advanced | 011 |
| `project-error` | Fn | `(project-error frame-id trace-event)` → `:rf/public-error`. Applies the active error-projector (selected by the frame's `:ssr {:public-error-id ...}` metadata) for the named frame. Per [011 §Server error projection](011-SSR.md). | v1 | advanced | 011 |
| `render-head` | Fn | `(render-head head-id opts)` → `:rf/head-model` | v1 | advanced | 011 |
| `active-head` | Fn | `(active-head)` / `(active-head frame-id)` → `:rf/head-model` | v1 | advanced | 011 |
| `head-model->html` | Fn | `(head-model->html head-model)` / `(head-model->html head-model {:wrap? bool})` → inner-head HTML string | v1 | advanced | 011 |
| `head-snapshot` | Fn | `(head-snapshot frame-id)` → `{head-id → :rf/head-model}`. Read the per-frame snapshot of last-produced head-models. Returns `{}` for a frame that has never seen a `render-head` call (or whose snapshot has been cleared via per-request frame teardown). Useful for tests, introspection, and tools. Re-exported as `rf/head-snapshot`. Per [011 §Head/meta contract](011-SSR.md). | v1 | advanced | 011 |
| `streaming-render-shell` | Fn | `(streaming-render-shell root-hiccup)` → `{:shell-html "…" :continuations [{:id <id> :subtree <hiccup>} …]}`. Walks the tree once; at each `:rf/suspense-boundary` emits a `<template …suspense-fallback>` placeholder + records a continuation. Per [011 §Streaming SSR](011-SSR.md#streaming-ssr) — (a). | v1 | advanced | 011 |
| `streaming-render-continuation` | Fn | `(streaming-render-continuation frame-id entry)` → `{:id … :html "…" :delta {…} :failed? bool}`. Drains one continuation against `frame-id`'s app-db; snapshots before-db / after-db and computes the per-subtree delta. Catches throws and surfaces the original fallback HTML inline (per [011 §Failure semantics — inline fallback](011-SSR.md#failure-semantics--inline-fallback)). | v1 | advanced | 011 |
| `streaming-build-final-payload` | Fn | `(streaming-build-final-payload frame-id render-hash opts)` → canonical `:rf/hydration-payload`. Called after all continuations drain to populate the `__rf_payload` final chunk. | v1 | advanced | 011 |

Standard SSR-related events:

| Event | What it does | Spec |
|---|---|---|
| `:rf/server-init` | Per-request server-side initialisation. Reads request cofx; dispatches setup events. `:platforms #{:server}`. | 011 |
| `:rf/hydrate` | Seed the client-side `app-db` from the server-supplied payload. Runs once on client bootstrap. | 011 |

Standard SSR-related fx (server-only; `:platforms #{:server}`):

| Fx | Args | Spec |
|---|---|---|
| `:rf.server/set-status` | `:int` (per `:rf.fx.server/set-status-args`) | 011 |
| `:rf.server/set-header` | `{:name :value}` (per `:rf.fx.server/set-header-args`) | 011 |
| `:rf.server/append-header` | `{:name :value}` (per `:rf.fx.server/append-header-args`) | 011 |
| `:rf.server/set-cookie` | `:rf.server/cookie` map | 011 |
| `:rf.server/delete-cookie` | `{:name ?:path ?:domain}` | 011 |
| `:rf.server/redirect` | `{:location ?:status}` (default `:status 302`); truncates HTML | 011 |

Standard SSR-related subs:

| Sub | Returns | Spec |
|---|---|---|
| `:rf/response` | The current request's response accumulator (status / headers / cookies / redirect) | 011 |
| `:rf/head` | The head model for the active route (resolved via `(active-head)`) | 011 |
| `:rf/public-error` | The sanitised public-error projection when an error page is being rendered; `nil` otherwise | 011 |

Standard cofx (server-only):

| Cofx | Returns | Spec |
|---|---|---|
| `:rf.server/request` | The active HTTP request map | 011 |

`reg-fx`'s `:platforms` metadata key (a set containing `:server` and/or `:client`) gates fx execution by active platform; **default `#{:server :client}` (universal)** when the key is absent. Skipped fx emit a `:rf.fx/skipped-on-platform` trace event. Detail in [011 §`:platforms` metadata on `reg-fx`](011-SSR.md#platforms-metadata-on-reg-fx).

SSR error-projection policy is **per-frame metadata** (see [Conventions §Configuration surfaces](Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) bucket 3): a frame opts in via the `:ssr {:public-error-id ... :dev-error-detail? ...}` map on its `reg-frame` / `make-frame` metadata. See [011 §Server error projection](011-SSR.md#server-error-projection) for the keys.

---

## HTTP requests (Spec 014)

`:rf.http/managed` is the canonical, optional HTTP-request fx — `v1 (optional capability)`. CLJS reference ships it on Fetch (browser) and `java.net.http.HttpClient` (JVM). Args, behaviours, decode pipeline, retry semantics, abort surface, failure taxonomy, and reply addressing are normatively defined in [014-HTTPRequests.md](014-HTTPRequests.md); the surface below is the API-level summary.

| API | Kind | Signature / shape | Status | Tier | Spec |
|---|---|---|---|---|---|
| `:rf.http/managed` | fx | `[:rf.http/managed args-map]` — args per [014 §The args map](014-HTTPRequests.md#the-args-map) and `:rf.fx/managed-args` | v1 (optional capability) | — (fx-id; follows the `advanced` HTTP artefact) | 014 |
| `:rf.http/managed-abort` | fx | `[:rf.http/managed-abort request-id]` — abort the in-flight request with the given `:request-id` | v1 (optional capability) | — (fx-id) | 014 |
| `:rf.http/managed-canned-success` | fx | `[:rf.http/managed-canned-success {:value v}]` — synthesises the canonical success reply (per [014 §Testing](014-HTTPRequests.md#testing)). Registered at load of `re-frame.http-test-support` (NOT `re-frame.http-managed`); the stubbing macros (rows below) ship in the same namespace per (audit-of-audits #15). | v1 (optional capability, dev/test) | — (fx-id; test) | 014 |
| `:rf.http/managed-canned-failure` | fx | `[:rf.http/managed-canned-failure {:kind <:rf.http/*> :tags {...}}]` — synthesises the canonical failure reply. Same registration gate (`re-frame.http-test-support`) and same co-location with the stubbing macros. | v1 (optional capability, dev/test) | — (fx-id; test) | 014 |
| `with-managed-request-stubs` | M | `(with-managed-request-stubs route-map body+)` — route-map `{[<method> <url>] {:reply ...}}` per [014 §Testing](014-HTTPRequests.md#testing). Lives in `re-frame.http-test-support` (the single home for HTTP test surfaces — audit-of-audits #15 closed the prior split that placed the macros in `re-frame.http-managed`); see [Spec 008 §HTTP test surfaces](008-Testing.md#http-test-surfaces--single-namespace). | v1 (optional capability, dev/test) | testing | 014 |
| `with-managed-request-stubs*` | Fn | `(with-managed-request-stubs* route-map body-fn)` — plain-fn surface beneath `with-managed-request-stubs`; the `*` follows the Clojure `let`/`let*`, `fn`/`fn*` idiom (per [Conventions](Conventions.md)). Ships in `re-frame.http-test-support`. Use for computed route-maps or non-literal bodies. Per [014 §Testing](014-HTTPRequests.md#testing). | v1 (optional capability, dev/test) | testing | 014 |
| `reg-http-interceptor` | Fn | `(reg-http-interceptor id interceptor-map)` — register an HTTP interceptor on a frame's `:rf.http/managed` middleware chain (per [014 §Middleware](014-HTTPRequests.md#middleware)). `id` is a keyword; `interceptor-map` carries at least one of `:before (fn [ctx] ctx')` and `:after (fn [ctx response] response')`, plus optional `:frame` (the EP-0002 *override*; absent, the carried scope it registers under resolves it — registering under no scope raises `:rf.error/no-frame-context`, never `:rf/default`) and any `:rf/registration-metadata` (`:doc` / `:tags` / `:schema` / `:sensitive?`). The surface mirrors the event-interceptor `{:id :before :after}` shape — symmetric request/response sides; `:before` chain in registration order, `:after` chain in reverse. | v1 (optional capability) | advanced | 014 |
| `clear-http-interceptor` | Fn | `(clear-http-interceptor id)` / `(clear-http-interceptor frame id)` — unregister an interceptor by id (per [014 §Middleware](014-HTTPRequests.md#middleware)). Single-arity resolves the frame from the carried scope; two-arity names it explicitly. Either raises `:rf.error/no-frame-context` when the frame is absent — no `:rf/default` fallback (EP-0002). | v1 (optional capability) | advanced | 014 |
| `re-frame.http/get`     | Fn | `(rf.http/get url)` / `(rf.http/get url args)` — build a `[:rf.http/managed {:request {:method :get :url url} ...}]` fx vector (per [014 §Call-site helpers](014-HTTPRequests.md#call-site-helpers)). | v1 (optional capability) | advanced | 014 |
| `re-frame.http/post`    | Fn | `(rf.http/post url)` / `(rf.http/post url args)` — POST helper; same shape as `get`. | v1 (optional capability) | advanced | 014 |
| `re-frame.http/put`     | Fn | `(rf.http/put url)` / `(rf.http/put url args)` — PUT helper. | v1 (optional capability) | advanced | 014 |
| `re-frame.http/delete`  | Fn | `(rf.http/delete url)` / `(rf.http/delete url args)` — DELETE helper. | v1 (optional capability) | advanced | 014 |
| `re-frame.http/patch`   | Fn | `(rf.http/patch url)` / `(rf.http/patch url args)` — PATCH helper. | v1 (optional capability) | advanced | 014 |
| `re-frame.http/head`    | Fn | `(rf.http/head url)` / `(rf.http/head url args)` — HEAD helper. | v1 (optional capability) | advanced | 014 |
| `re-frame.http/options` | Fn | `(rf.http/options url)` / `(rf.http/options url args)` — OPTIONS helper. | v1 (optional capability) | advanced | 014 |

Public API surface in `re-frame.core` for ports that ship Spec 014. Ports that omit it MUST NOT register `:rf.http/*` for any other purpose (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)).

The raw `install-managed-request-stubs!` / `uninstall-managed-request-stubs!` pair is NOT a `re-frame.core` façade export — it is test-support infrastructure reached through its home namespace `re-frame.http-test-support` (`(:require [re-frame.http-test-support :as http-test-support])`). The ergonomic `with-managed-request-stubs` macro is the façade surface; use the raw pair only when stubs must span multiple `deftest`s.

The verb helpers (`get` / `post` / `put` / `delete` / `patch` / `head` / `options`) live in `re-frame.http` — users `(:require [re-frame.http :as rf.http])` alongside `re-frame.core`. They're pure synthesis fns that produce the canonical `[:rf.http/managed args-map]` fx vector; the namespace ships in `day8/re-frame2-http` (same artefact as the fx they reference) so loading the helpers and the fx are a single dep decision.

### Reply-payload shape

Every reply lands as `{:rf/reply {:kind :success :value v}}` or `{:rf/reply {:kind :failure :failure {:kind <:rf.http/*> ...}}}`. Default reply addressing dispatches `[<originating-event-id> (assoc original-msg :rf/reply ...)]` back to the same handler; explicit `:on-success` / `:on-failure` targets append the reply payload as the last event-vector arg. Both shapes detailed in [014 §Reply addressing](014-HTTPRequests.md#reply-addressing).

### Failure categories (closed set)

The eight `:kind` values inside a failure reply, all reserved under `:rf.http/*` (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). See [014 §Failure categories](014-HTTPRequests.md#failure-categories-closed-set) for tags-by-kind:

| `:kind` | Meaning |
|---|---|
| `:rf.http/transport` | Network / DNS / connection error pre-HTTP |
| `:rf.http/cors` | CORS preflight rejected (CLJS-only) |
| `:rf.http/timeout` | Per-attempt timeout fired |
| `:rf.http/http-4xx` | Non-2xx 4xx response |
| `:rf.http/http-5xx` | Non-2xx 5xx response |
| `:rf.http/decode-failure` | 2xx response but decode rejected the body |
| `:rf.http/accept-failure` | `:accept` returned `{:failure user-map}` |
| `:rf.http/aborted` | Request aborted via `:request-id` or `:abort-signal` |

### Trace events emitted by `:rf.http/managed`

| `:operation` | `:op-type` | When |
|---|---|---|
| `:rf.http/retry-attempt` | `:info` | Per intermediate attempt that matched `:retry :on`; carries `:attempt`, `:max-attempts`, `:failure`, `:next-backoff-ms` |
| `:rf.http.interceptor/registered` | `:info` | A `reg-http-interceptor` succeeded; carries `:frame`, `:id` (per [014 §Middleware](014-HTTPRequests.md#middleware)) |
| `:rf.http.interceptor/cleared` | `:info` | A `clear-http-interceptor` removed an existing slot; carries `:frame`, `:id` |
| `:rf.error/http-interceptor-failed` | `:error` | A request-interceptor `:before` threw; carries `:frame`, `:interceptor-id`, `:url`, `:cause`. The request is NOT dispatched (per [014 §Middleware §Failure mode](014-HTTPRequests.md#failure-mode)) |

### Schema-reflection metadata

Handlers may declare `:rf.http/decode-schemas [<schema> ...]` in their `reg-event` metadata-map; pair tools and generators read it via `(rf/handler-meta :event id)`. Optional, never enforced — see [014 §Schema reflection](014-HTTPRequests.md#schema-reflection-optional-ergonomic).

---

## Resources (Spec 016)

The Resources artefact (`day8/re-frame2-resources`, post-v1 optional) ships declarative cached server-state. Its full registration / event / sub / accessor surface is normatively defined in [016-Resources.md](016-Resources.md); this projection rows the **facade exports** classified by the standing diff-time rule (every new `re-frame.core` facade export is classified + justified when it lands). The resource/mutation registrars and the `:rf.resource/*` / `:rf.mutation/*` keyword-addressed events, subs, and trace ops follow the artefact's tier (optional capability) and carry no Tier column (they are not vars).

| API | M/Fn | Signature | Status | Tier | Spec | Notes |
|---|---|---|---|---|---|---|
| `reg-resource` | Fn | `(reg-resource resource-id resource-spec)` | post-v1 lib (optional capability) | advanced | 016 | The `:resource` registrar kind. Late-bound by the Resources artefact. **Front-room of the resources surface** but `advanced` (not front-porch): an optional post-v1 capability a new app does not reach for on day one. |
| `clear-resource` | Fn | `(clear-resource resource-id)` | post-v1 lib (optional capability) | advanced | 016 | Registration-lifecycle removal (the `clear-` registrar decrement per [Conventions §Tear-down verb axis](Conventions.md#tear-down-verb-axis--clear--vs-destroy-)); disposes resource-runtime state per [016 §Registration](016-Resources.md#registration). |
| `reg-mutation` | Fn | `(reg-mutation mutation-id mutation-spec)` | post-v1 lib (optional capability) | advanced | 016 | The `:mutation` registrar kind — a named causal write. `:rf.mutation/execute` carries the EP-0016 call-site `:reply-to` continuation target. |
| `clear-mutation` | Fn | `(clear-mutation mutation-id)` | post-v1 lib (optional capability) | advanced | 016 | Registration-lifecycle removal of a mutation. |
| `reg-resource-scope` | Fn | `(reg-resource-scope scope-id resolver-spec)` — register a pure named db-derived scope resolver `{:inputs {name [:db <rf-path>]} :resolve (fn [inputs _ctx] scope-or-nil)}` (whole-db `(fn [db _ctx] …)` is tooling-marked explicit-cost sugar). The `:resource-scope` registrar kind. | post-v1 lib (optional capability) | advanced | 016 | **Facade classification (EP-0016 D3):** a `re-frame.core` export of the optional Resources artefact, justified as the single scope-resolution currency reused by resource registration, route resources, event ensure, subscriptions, invalidation descriptors, exact targets, and `clear-scope`. `advanced` — an optional-artefact authoring surface, not front-porch. Per [016 §Named resource-scope resolvers](016-Resources.md#named-resource-scope-resolvers-reg-resource-scope). |
| `clear-resource-scope` | Fn | `(clear-resource-scope scope-id)` | post-v1 lib (optional capability) | advanced | 016 | The `clear-` registrar decrement counterpart of `reg-resource-scope` (per [Conventions §Tear-down verb axis](Conventions.md#tear-down-verb-axis--clear--vs-destroy-)). |
| `resolve-resource-scope` | Fn | `(resolve-resource-scope db scope-id)` — resolve a named scope resolver against a supplied db value; returns the canonical scope or `nil`. A **resolver helper**, not an effect (no app-state / dispatch side effects) — but it emits `:rf.resource/scope-resolved` dev trace, so it is not a pure data helper. | post-v1 lib (optional capability) | advanced | 016 | **Facade classification (EP-0016 D3 / issue 7):** a `re-frame.core` export justified as the ergonomic helper for the logout/account-switch idiom — resolve the concrete old scope from the handler's coeffect db (no `:snapshot-db` payload, which would be an egress-bearing record under EP-0015). A plain function over the resolver registry; no new effect-API surface, no resolution-timing ambiguity. It emits `:rf.resource/scope-resolved` dev-time trace evidence like every resolution site (observed resolver, not a pure data helper). Per [016 §`clear-scope` resolves the concrete scope from the coeffect db](016-Resources.md#clear-scope-resolves-the-concrete-scope-from-the-coeffect-db-not-a-snapshot). |

> **Request decoration (EP-0016 Rider 3) reuses the existing HTTP facade.** Auth/tracing/base-URL/retry decoration for resources and mutations is **not** a new resources surface — it is the existing `reg-http-interceptor` / `clear-http-interceptor` ([§HTTP requests](#http-requests-spec-014)), registered once per frame and applied to every `:rf.http/managed` request (resource reads, mutations, plain managed calls). No new facade export is introduced for decoration; the doctrine is ownership (transport decoration lives in the managed-HTTP seam), per [016 §Request decoration belongs to the managed-HTTP seam](016-Resources.md#request-decoration-belongs-to-the-managed-http-seam-not-the-resource-declaration).

---

## Effect-map shape

Closed: `#{:db :rf.db/runtime :fx}`. Ordinary app handlers return only `:db` + `:fx`; `:rf.db/runtime` is reserved by convention for framework / runtime-extension authority (it writes the runtime-db partition — non-framework handlers emitting it are surfaced by dev diagnostics, not silently dropped). See [Spec-Schemas §:rf/effect-map](Spec-Schemas.md#rfeffect-map). Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` from v1 migrate via [MIGRATION.md §M-8](../migration/from-re-frame-v1/README.md).

| Key | Notes |
|---|---|
| `:db` | New `app-db` partition (replaces). The app-facing state key. |
| `:rf.db/runtime` | New runtime-db partition (replaces). Reserved by convention for framework / runtime-extension authority — ordinary app handlers do not emit it. |
| `:fx` | Vector of `[fx-id args]` pairs. |

Standard `:fx` entries:

| `[fx-id args]` | Args | Status | Spec | Notes |
|---|---|---|---|---|
| `[:dispatch [event-id ...]]` | event vector | v1 | 002 | |
| `[:dispatch-later {:ms ms :event event-vec}]` | options map | v1 | 002 | |
| `[:http args]` | impl-specific | — | — | user-registered via `reg-fx`. |
| `[:rf.http/managed args-map]` | args per [014 §The args map](014-HTTPRequests.md#the-args-map) | v1 (optional capability) | 014 | Framework-provided when the implementation ships Spec 014. CLJS reference: ships on Fetch + JVM `HttpClient`. See also `:rf.http/managed-abort`, `:rf.http/managed-canned-success`, `:rf.http/managed-canned-failure`. |
| `[:rf.nav/push-url url-string]` | URL string | v1 | 012 | |
| `[:raise event-vec]` | event vector | v1 | 005 | *machine-only*: reserved fx-id recognised by the machine handler; routes the event back into the same machine, atomic and pre-commit. Outside a machine action's `:fx`, this fx-id is unbound. |
| `[:rf.machine/spawn spawn-spec]` | spawn-spec map (per `:rf.fx/spawn-args`: `:machine-id`/`:definition`, `:id-prefix`, `:data`, `:on-spawn`, `:start`) | v1 | 005 | Canonical actor-lifecycle fx (registered globally by `re-frame.machines`); installs a new dynamic actor (whose snapshot lives at `[:rf.runtime/machines :snapshots <gensym'd-id>]`). On the *declarative* `:spawn` / `:spawn-all` path the reducer binds the assigned id into the spawning machine's own `:data` under `:rf/spawned` (XState-context parity, per [005 §Recording the spawned id user-side](005-StateMachines.md#recording-the-spawned-id-user-side)); `:on-spawn` is advisory-only and cannot record it (its return is dropped). Emitted from any event handler's `:fx` (including machine actions and the `:spawn` desugar). |
| `[:rf.machine/destroy actor-id]` | actor id (keyword) | v1 | 005 | Canonical actor-destroy fx (registered globally by `re-frame.machines`); runs the actor's `:exit` action, dissociates `[:rf.runtime/machines :snapshots <actor-id>]`, and clears the actor's event-handler registration. Symmetric counterpart to `:rf.machine/spawn`. |

---

## Public registrar query API

For tooling, agents, story tools, 10x.

> **Realm-targeted query (EP-0013 D1 stage 8).** Each registrar-query workhorse grows a **map-shaped** realm-targeted form alongside its process-global keyword arities — `(registrations {:realm r :kind k})`, `(handler-meta {:realm r :kind k :id id})`, `(handler-ids {:realm r :kind k})`. The map form reads **only the specified realm's registrar** ("realm-targeted registrar queries return only that realm's registrations"); `:realm` is a realm map, a realm-id keyword, or absent/nil for the **default realm**. Map-shaped is the form ruled in [EP-0013](../docs/EP/EP-0013-app-values-and-runtime-realms.md) open-issue 11 (unambiguous against the keyword arities — a `kind` is a keyword, never a map — and extensible). The default-realm keyword arities are **byte-identical**: a single-realm caller never spells a realm (the absence-is-default rule). The realm is **retained internal substrate** (EP-0023): the public construction/installation vocabulary that once seated it (`rf/realm` / `rf/install!` / `rf/app` / `rf/module`) has been removed from the facade — the public model is the **frame-targeted** form below.

> **Frame-targeted query (EP-0023, rf2-wkw8na).** The same trio ALSO grows a **map-shaped** frame-targeted form — `(registrations {:frame f :kind k})`, `(handler-meta {:frame f :kind k :id id})`, `(handler-ids {:frame f :kind k})` — that resolves the `(kind, id)` set **through live frame `f`'s own sealed image generation** (EP-0023 §Frame-derived live registration resolution — "target frame → resolved image generation → registration resolution"), surfacing the `:rf.provenance/ns` + inline/image + replacement/standard facts the resolved descriptors carry. `:frame` is a **registered frame id** (keyword) OR a **direct live frame object** (`rf/make-frame`'s return value) — the same target shapes `rf/reload-images!` accepts. This is the promised-but-unshipped READ of the EP-0023 model: the public tooling surface a tool reaches *instead of* the internal `re-frame.live-frame` / `re-frame.image-assembly` namespaces. **Fail-loud (EP-0023 §Id Spaces — the address families are distinct):** a `:frame` that does not resolve to a live frame carrying a generation throws `:rf.error/frame-no-generation` (**no** fallback to the default/realm registrar — the read needs a live EP-0023 frame), and a map mixing `:frame` + `:realm` throws `:rf.error/frame-realm-mixed-address` (a silent priority rule would re-introduce the (realm, frame) ambiguity EP-0023 removes). The dedicated raw read `frame-generation` (below) returns the whole sealed generation. The default-realm keyword arities and the `{:realm …}` form are **byte-identical** — only a caller passing `{:frame …}` reaches the generation path.

| API | M/Fn | Signature | Status | Tier | JVM-runnable? | Spec |
|---|---|---|---|---|---|---|
| `registrations` | Fn | `(registrations kind)` / `(registrations kind pred-fn)` → `{id metadata-map}`. **Use when you want metadata** — registry walks that read source-coords, `:rf/sensitive`, `:rf/machine?`, `:platforms`, etc. **Realm-targeted (EP-0013 stage 8):** `(registrations {:realm r :kind k})` → the `{id metadata}` for `:kind` in realm `r`'s own registrar (only that realm's registrations); an optional `:pred` filters by metadata as the positional `pred-fn` does. **Frame-targeted (EP-0023, rf2-wkw8na):** `(registrations {:frame f :kind k})` → the `{id metadata}` for `:kind` resolved through live frame `f`'s own sealed image generation (only the ids that frame's image carries, with `:rf.provenance/ns` + replacement facts); `:frame` is a frame id or a direct frame object; an optional `:pred` filters. Fail-loud on an unresolvable `:frame` or a `:frame` + `:realm` mix (see note above). | v1 | tooling | ✓ | 002 |
| `handler-ids` | Fn | `(handler-ids kind)` → id set (canonical alias for `(-> (registrations kind) keys set)`). **Use when you only need to enumerate** — completion lists, existence checks, set-shaped intersections; saves both the metadata-map allocations and the `keys` walk. **Realm-targeted (EP-0013 stage 8):** `(handler-ids {:realm r :kind k})` → the id set under `:kind` in realm `r`'s own registrar. **Frame-targeted (EP-0023, rf2-wkw8na):** `(handler-ids {:frame f :kind k})` → the id set under `:kind` resolved through live frame `f`'s sealed image generation (only the ids that frame's image carries). Fail-loud on an unresolvable `:frame` or a `:frame` + `:realm` mix (see note above). | v1 | tooling | ✓ | 002 |
| `handler-meta` | Fn | `(handler-meta kind id)` → registration-metadata map. View registrations include source-coord keys (`:ns` / `:line` / `:column` / `:file`) per `:rf/source-coord-meta` ([Spec-Schemas](Spec-Schemas.md#rfsource-coord-meta)); pair tools resolve `data-rf2-source-coord` DOM annotations to `:file` via this lookup. **Realm-targeted (EP-0013 stage 8):** `(handler-meta {:realm r :kind k :id id})` → the metadata for `[k id]` in realm `r`'s own registrar (only that realm's registration). **Frame-targeted (EP-0023, rf2-wkw8na):** `(handler-meta {:frame f :kind k :id id})` → the metadata for `[k id]` resolved through live frame `f`'s sealed image generation (with `:rf.provenance/ns` + replacement facts), or `nil` when that frame's image carries no such `[k id]`. Fail-loud on an unresolvable `:frame` or a `:frame` + `:realm` mix (see note above). | v1 | tooling | ✓ | 002 |
| `frame-generation` | Fn | `(frame-generation f)` → the **sealed, resolved image generation** live frame `f` is running — the inert image-assembly value with the four documented stable public keys `:rf.gen/resolver` (`{[kind id] descriptor}`), `:rf.gen/images`, `:rf.gen/requires` (`#{:rf.capability/* …}`), `:rf.gen/kinds`. `f` is a registered frame id or a direct frame object. The dedicated raw read over the EP-0023 frame→generation model — for `describe-image`-style views that want the whole generation (selected registrations, capability set, replacement facts) without per-kind round-trips; the `:frame` arities of the trio above give per-`(kind, id)` resolution with provenance. **Fail-loud** `:rf.error/frame-no-generation` when `f` does not resolve to a live frame carrying a generation (no nil-as-default, no realm fallback). Per EP-0023 §Public API / Use-Case 7 — the public tooling surface tools (Pair MCP, Xray) reach instead of `re-frame.live-frame` / `re-frame.image-assembly` internals. | EP-0023 | tooling | ✓ | 002 |
| `frame-ids` | Fn | `(frame-ids)` / `(frame-ids ns-prefix)` | v1 | tooling | ✓ | 002 |
| `frame-meta` | Fn | `(frame-meta frame-id)` | v1 | tooling | ✓ | 002 |
| `app-db-value` | Fn | `(app-db-value frame-id)` → the **app-db** partition value (plain map) — the out-of-band value read (renamed from `get-frame-db`). The front-porch read is `subscribe`; `app-db-value` is the non-reactive snapshot read for tools, tests, REPL, and fx/handler bodies. | v1 | advanced | ✓ | 002 |
| `runtime-db-value` | Fn | `(runtime-db-value frame-id)` → the **runtime-db** partition value (framework-owned subsystem state). Returns nil for an unknown frame. The tool/privileged-runtime read of the framework partition (EP-0001). | v1 | tooling | 002 |
| `frame-state-value` | Fn | `(frame-state-value frame-id)` → the coherent **frame-state** projection `{:rf.db/app <app-db> :rf.db/runtime <runtime-db>}`. The full-frame read for SSR / epoch / time-travel / Xray (EP-0001). | v1 | tooling | 002 |
| `snapshot-of` | Fn | `(snapshot-of path)` / `(snapshot-of path opts)` | v1 | tooling | ✓ | 002 |
| `sub-topology` | Fn | `(sub-topology)` → `{sub-id {:input-kind <kind> :inputs <inputs> :doc :ns :line :file}}` — static dependency graph over the registrar. Pure data; the per-entry `:doc` / `:ns` / `:line` / `:file` keys are present when registration carries them. `:input-kind` discriminates `:db` (layer-1; `:inputs []`), `:static` (`:<-` chains; `:inputs` is the vector of literal input query vectors), and `:parametric` (`input-fn`; `:inputs :parametric` — the realized edge set is NOT statically enumerable, only the literal `:<-` edges are). Live realized parametric edges per concrete query vector are exposed by the sub-cache inspection surface, not here. Per [006 §Subscription input producers](006-ReactiveSubstrate.md#subscription-input-producers--app-db-reader-static-parametric-input-fn). | v1 | tooling | ✓ | 002 |
| `sub-cache` | Fn | `(sub-cache frame-id)` → live cache state | v1 | tooling | ✗ (CLJS-only) | 002 |

Schema-introspection accessors — `app-schemas`, `app-schema-at`, `app-schemas-digest` — are rowed canonically in [§Schemas](#schemas).

`compute-sub` is rowed canonically in [§Testing](#testing) (pure sub computation against an `app-db` value).

---

## App values and composition (EP-0013)

> **Superseded by EP-0023 — removed from the public facade (rf2-pl97nd.2).** This section is retained as a stable anchor for inbound cross-references; the surface it once documented is no longer public.


> **The retired composition vocabulary has left the public facade.** EP-0013's app/realm/module composition surface — `rf/app` / `rf/module` (+ the `rf/app-registrations` / `rf/app-requires` / `rf/app-owns` inspectors), `rf/install!` / `rf/reinstall!`, `rf/realm` / `rf/dispose-realm!`, and the realm reads `rf/realm-ids` / `rf/frame-realm` / `rf/installed-app` — was **removed from `re-frame.core`** (EP-0023, rf2-pl97nd.2). The public model is **`image → frame → event stream`**: a feature namespace registers ordinary `reg-*` forms; an **`rf/image`** selects them by `:include-ns` provenance and is supplied to **`make-frame`** (see [§Registration](#registration)) via `:images`; **`reload-images!`** hot-reloads a frame's image generation in place (preserving frame memory); and a frame is addressed by its **process-local frame id** (or a direct frame object for tests), with no realm coordinate. The realm/app-value machinery is **retained as internal substrate** (the registrar-backed installation path that frame resolution still routes through — see [Runtime-Subsystems §What a realm owns](Runtime-Subsystems.md#what-a-realm-owns)), but it is no longer public vocabulary. Migration guidance is inspectable as data via `rf/migration-map` / `(rf/migration-explain :rf/app)` (et al.); see [EP-0023 §Backwards Compatibility](../docs/EP/EP-0023-image-loaded-frames.md#backwards-compatibility-and-ep-0013-partial-supersession). For tooling that reads a frame's resolved registrations, use the **frame-targeted** `{:frame f …}` registrar queries + `frame-generation` ([§Public registrar query API](#public-registrar-query-api)).

---

## Schemas

> **Namespace:** the introspection surfaces below live in `re-frame.schemas` (artefact `day8/re-frame2-schemas`); consumers `(:require [re-frame.schemas :as schemas])`. They are not re-exported from `re-frame.core` — apps targeting schemas add the artefact and require the namespace directly. The registration macros (`reg-app-schema` / `reg-app-schemas`) live in `re-frame.core` and route through the schemas artefact at registration time. Per the §Conventions per-artefact namespace table.

`reg-app-schema` is rowed canonically in [§Registration](#registration).

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `app-schemas` | Fn | `(app-schemas)` / `(app-schemas {:frame frame-id})` | v1 | tooling | 010 |
| `app-schema-at` | Fn | `(app-schema-at path)` / `(app-schema-at path {:frame frame-id})` | v1 | tooling | 010 |
| `app-schema-meta-at` | Fn | `(app-schema-meta-at path)` / `(app-schema-meta-at path opts-or-frame-id)` — return the full registration-metadata map (`:path`, `:schema`, `:frame`, plus source-coords `:ns` / `:line` / `:file` and the rest of `:rf/registration-metadata`) for a registered `app-db` schema, or `nil`. Pair-tool and 10x consumers reach for this when they need the registration anchor (e.g. click-back-to-code); the lighter `app-schema-at` is the right call when only the schema value is needed. Per [010 §Schemas as a tooling/agent surface](010-Schemas.md) and [Spec-Schemas §`:rf/app-schema-meta`](Spec-Schemas.md#rfapp-schema-meta). | v1 | tooling | 010 |
| `app-schemas-digest` | Fn | `(app-schemas-digest)` / `(app-schemas-digest {:frame frame-id})` → string | v1 | tooling | 010 |
| `set-schema-validator!` | Fn | `(set-schema-validator! validate-fn)` — install the validator (`(fn [schema value] truthy?)`, same shape as `malli.core/validate`) every dev-time schema-validation site routes through. Swaps **only** the validator — the explainer/printer are left untouched (use `set-schema-fns!` to install all three atomically). `nil` disables validation entirely. Default ships Malli's `validate`; this seam lets apps swap in their own validator to drop the Malli dep. Last-write-wins; returns the installed validator. Per [010 §Default validator and the validator-fn extension point](010-Schemas.md#default-validator-and-the-validator-fn-extension-point). | v1 | advanced | 010 |
| `set-schema-fns!` | Fn | `(set-schema-fns! {:validate validate-fn :explain explain-fn :print print-fn})` — atomically install any subset of the validator / explainer / printer bundle from one map. Each key is optional; an absent key leaves the existing registration in place. The honest one-call substitute-Malli boot pattern (a Zod / clojure.spec port installs all three together so they never drift mid-boot). `:print nil` coerces to the default EDN canonicaliser (parity with `set-schema-printer!`); `:validate nil` / `:explain nil` disable that fn. Last-write-wins per key; returns the **installed bundle** as a map `{:validate … :explain … :print …}` reflecting the live state of all three fns after the call (a bundle setter returns its bundle, not just the validator). Per [010 §Default validator and the validator-fn extension point](010-Schemas.md#default-validator-and-the-validator-fn-extension-point). | v1 | advanced | 010 |
| `set-schema-explainer!` | Fn | `(set-schema-explainer! explain-fn)` — install the explainer used to enrich `:rf.error/schema-validation-failure` traces' `:explain` key. Companion to `set-schema-validator!`. Per [010 §Default validator and the validator-fn extension point](010-Schemas.md#default-validator-and-the-validator-fn-extension-point). | v1 | advanced | 010 |
| `set-schema-printer!` | Fn | `(set-schema-printer! print-fn)` — install the **schema-print companion** the digest pipeline (per [010 §Schema digest](010-Schemas.md#schema-digest)) hashes. `print-fn` is `(fn [schema-value] canonical-string)` and MUST be pure + deterministic across runtimes. `nil` falls back to the default EDN canonicaliser so the digest is never undefined. Parallel to `set-schema-validator!` / `set-schema-explainer!`: non-Malli ports register their own serialiser so cross-runtime digest comparison reflects their port's contract. Per [010 §Default validator and the validator-fn extension point](010-Schemas.md#default-validator-and-the-validator-fn-extension-point). | v1 | advanced | 010 |
| `validate-at-boundary-interceptor` | Var (interceptor value) | `validate-at-boundary-interceptor` — a **pre-built interceptor value**, not a fn (interceptor `:id` is `:rf.schema/at-boundary`); do **not** call it as a fn (invoking `(rf/validate-at-boundary-interceptor ...)` raises `ArityException`). Under EP-0022 a public `:interceptors` chain carries refs, not inline values: reference the registered `:rf.schema/at-boundary` interceptor by id (the value Var is the registration-boundary input, not a chain entry). | v1 | advanced | 010 |

See [010 §Schemas](010-Schemas.md) for `:schema` metadata, validation timing, and dev/prod elision. (v1's `:spec` metadata key was renamed to `:schema` — a breaking rename with no back-compat alias; the framework no longer accepts `:spec` on `reg-*` metadata. Per [MIGRATION §M-54](../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema).)

---

## Event-emit (always-on, production-survivable)

Per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production) (#2). A minimal always-on listener surface that survives `:advanced` + `goog.DEBUG=false` and delivers one tight record per processed event. Parallel to (not a fallback for) the dev-only trace surface; per-event only — no per-sub, per-fx, or per-`:rf.event/db-changed` records. Record shape `{:event :event-id :frame :time :outcome :elapsed-ms}` (the always-on event-emit substrate's own record fields, distinct from trace `:tags` keys); the `:event` slot is passed through [`rf/elide-wire-value`](#size-elision-wire-boundary-walker) once before fan-out, so schema-marked `:sensitive?` paths land as `:rf/redacted` and `:large?` paths land as `:rf.size/large-elided`.

> **Advanced corpus-wide hook — not the off-box default (EP-0015 §9).** This registry fans an UNPROJECTED record across EVERY frame; it is not routed under any frame's egress policy. The NORMAL production observation surface for hosted back-ends (Datadog / Honeycomb / Sentry / …) is the frame-owned `:observability :handled-events` sink ([Spec 015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy)), declared on `reg-frame` and wired with [`register-observability-sink!`](#frame-owned-observability-sinks-ep-0015-9--spec-015), which hands the sink an already-PROJECTED record. Reach for the corpus-wide listener only for an intentionally cross-frame hook or a record the sink routing does not carry.

> Sensitive data marking is path-based per the upcoming data-classification mechanism (separate spec doc; in progress). The legacy handler-meta `:sensitive?` annotation that previously drove substrate-level record drop has been removed.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `register-event-listener!` | Fn | `(register-event-listener! id listener-fn)` — `listener-fn` receives one event-record per processed event (shape above). Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`. | v1 | tooling | 009 |
| `unregister-event-listener!` | Fn | `(unregister-event-listener! id)` → nil | v1 | tooling | 009 |

## Error-emit (always-on, production-survivable)

Per [009 §What IS available in production](009-Instrumentation.md#what-is-available-in-production). Sibling of the event-emit surface above; runs through the always-on error-emit substrate. Survives `:advanced` + `goog.DEBUG=false`.

> **Advanced corpus-wide hook — not the off-box default (EP-0015 §9).** This registry fans the record across EVERY frame UNPROJECTED — the `:event` vector is wire-elided, but the `:exception` rides RAW and no frame egress policy is applied, so raw owner-local data can leave a frame here. It is the documented exception to the always-on axis's 'structured data only' rule, for post-mortem shippers that need the host throwable + stack. The NORMAL off-box error-observation surface is the frame-owned `:observability :errors` sink ([Spec 015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy)), declared on `reg-frame` and wired with [`register-observability-sink!`](#frame-owned-observability-sinks-ep-0015-9--spec-015), which PROJECTS the record under the owning frame's classification + the sink's egress profile (sensitive paths redacted, `:exception` dropped under `:rf.egress/public-error`) BEFORE the sink sees it. Off-box shippers (Sentry / Honeybadger / Rollbar) should prefer the frame sink; reach for the corpus-wide listener only for an intentionally cross-frame hook or a frameless record the sink routing does not carry.

The listener payload is a **union of three record shapes**: (a) the **per-event error record** — `{:error :event :event-id :frame :time :exception :elapsed-ms}` — fanned out by `dispatch-on-error!` once per catalogued production-reachable per-event runtime `:rf.error/*` event; (b) the **frame-teardown report** — `{:error :rf.error/frame-teardown-failed :frame :hook-failures :reason :recovery :time}` — one bounded record per frame destroy whose best-effort cleanup hooks threw (EP-0008 promotion criterion; see [009 §Observability channels and the promotion criterion](009-Instrumentation.md#observability-channels-and-the-promotion-criterion)); and (c) the **six EP-0008-promoted SSR non-event categories** (`:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload` — incl. a pre-frame frameless `:frame nil` sub-path — `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed`), flat union records `{:error :frame :time …category keys…}`. The two non-event arms (b) and (c) ride the general `dispatch-error-record!` helper and carry no `:event` / `:event-id`; the teardown report carries a `:hook-failures` vector instead of the per-event `:exception` / `:elapsed-ms` slots. Listener bodies MUST branch on `(:error record)` (or otherwise tolerate a record with no top-level `:event` / `:exception`) rather than assuming the per-event shape. The per-event record's `:event` slot is passed through [`rf/elide-wire-value`](#size-elision-wire-boundary-walker) once before fan-out, so schema-marked `:sensitive?` paths land as `:rf/redacted` and `:large?` paths land as `:rf.size/large-elided`. This is the single error-observability surface; recovery is the framework's typed per-category default, not app-steerable (the per-frame `:on-error` recovery policy was removed). Per-listener exceptions are isolated — a buggy listener cannot block siblings or the cascade.

> Sensitive data marking on the error-emit substrate is path-based per the upcoming data-classification mechanism (separate spec doc; in progress). The legacy handler-meta `:sensitive?` annotation that previously drove substrate-level event redaction has been removed — the per-path elision wire-walker is now the sole redaction surface on this path.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `register-error-listener!` | Fn | `(register-error-listener! id listener-fn)` — `listener-fn` receives one error-record per `:rf.error/*` event (shape above). Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`. | v1 | tooling | 009 |
| `unregister-error-listener!` | Fn | `(unregister-error-listener! id)` → nil | v1 | tooling | 009 |

## Tracing

All tracing is **dev-only** (elided in production). See [009 §Tracing](009-Instrumentation.md) for emit semantics and synchronous listener delivery.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `register-listener!` | Fn | `(register-listener! key callback-fn)` — `callback-fn` receives one trace event per call | v1 (dev-only) | tooling | 009 |
| `unregister-listener!` | Fn | `(unregister-listener! key)` → nil | v1 (dev-only) | tooling | 009 |
| `emit-trace-event!` | Fn | `(emit-trace-event! op-type operation tags)` → nil | v1 (dev-only) | tooling | 009 |
| `re-frame.interop/debug-enabled?` | Var | `^boolean`. **CLJS**: alias of `goog.DEBUG` — constant-folded by Closure under `:advanced`, so `:advanced` + `goog.DEBUG=false` builds DCE every `(when interop/debug-enabled? ...)` branch. **JVM**: a `def` read ONCE at ns-load from the Java system property `-Dre-frame.debug` (winning on conflict) or the environment variable `RE_FRAME_DEBUG`; defaults `true` (dev parity). Accepts the conventional false-y vocabulary case-insensitively (`false`, `0`, `no`, `off`, empty string) with whitespace trimmed; anything else leaves the flag at `true`. Set BEFORE `re-frame.interop` loads. SSR / webhook receivers / long-running JVMs facing untrusted input MUST set the gate `false` explicitly — per [009 §JVM builds](009-Instrumentation.md#jvm-builds) and [Security §Production gates](Security.md#production-gates). | v1 | tooling | 009 |
| `re-frame.performance/enabled?` | Var | `^boolean` `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}` to bracket event dispatch / sub recompute / fx walk / view render in `performance.mark` + `performance.measure` calls (User-Timing entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`). **Compile-time only** — not a `(rf/configure! ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` + default the bracket DCEs and shipped binaries carry zero User-Timing instrumentation. CLJS-only — JVM is a no-op. See [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation) and [Tool-Pair §Performance API consumption](Tool-Pair.md#performance-api-consumption) | v1 | tooling | 009 |
| `trace-buffer` | Fn | `(trace-buffer)` / `(trace-buffer opts)` → vector of trace events, oldest-first | v1 (dev-only) | tooling | 009 |
| `clear-trace-buffer!` | Fn | `(clear-trace-buffer!)` → nil | v1 (dev-only) | tooling | 009 |
| `(rf/configure! :trace-buffer {:cascades-retained N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | — (configure key) | 009 |
| `group-cascades` | Fn | `(group-cascades events)` → vector of cascade records `{:dispatch-id :event :handler :fx :effects :subs :renders :other}`, sorted by emission order. Pure data; JVM-runnable. Re-exported from `re-frame.trace.projection` (see [009 §Cascade projection](009-Instrumentation.md#cascade-projection-group-cascades--domino-bucket)). | v1 (dev-only) | tooling | 009 |
| `domino-bucket` | Fn | `(domino-bucket trace-event)` → `#{:event :handler :fx :effect :sub :render :other}`. Classifies a raw trace event into the six-domino slot used by `group-cascades`. Pure data. | v1 (dev-only) | tooling | 009 |

### Trace-emission opt-out (per-handler metadata)

Event-handler registration accepts a `:rf.trace/no-emit? true` metadata flag. When set, the runtime suppresses **every** trace emission and event-emit record within the handler's scope — the handler runs invisibly to the trace surface, the event-emit substrate, and (transitively) the epoch buffer. Used by framework-internal bookkeeping handlers (Xray, Story, re-frame2-pair-mcp, story-mcp) that would otherwise saturate the trace stream. Per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned) the `:rf.trace/*` namespace is framework-owned.

| Metadata key | Where | Value | Default | Effect |
|---|---|---|---|---|
| `:rf.trace/no-emit?` | `reg-event` metadata map | boolean | `false` | When `true`, suppresses all trace + event-emit emissions inside the handler's scope. Per [009 §Trace-emission opt-out](009-Instrumentation.md#trace-emission-opt-out-rftraceno-emit-event-meta). |
| `:rf.trace/frame-no-emit?` | `reg-frame` config map | boolean | `false` | When `true`, marks the frame a tool / inspector frame: the runtime suppresses every trace emission tagged with that frame, so the inspector's own reactivity does not flood the shared ring it inspects. The frame-scoped sibling of `:rf.trace/no-emit?`. Per [009 §Frame-level trace-emission opt-out](009-Instrumentation.md#frame-level-trace-emission-opt-out-rftraceframe-no-emit-frame-config). |

### Epoch history (per Tool-Pair)

Per-frame epoch snapshots, recorded on each drain-completion in dev builds. Used by pair-shaped tools for time-travel and post-mortem analysis. **Production builds elide entirely.**

All rows below are the Tool-Pair time-travel surface — pair-shaped dev tools (Xray, the pair-MCP servers), so they tier **tooling** (the classification guidance also calls epoch-listener registration "advanced power-user"; either way the surface is back-room and opt-in — never front-porch). The state-injection members are the partition-aware mutators `replace-app-db!` / `reset-app-db!` / `replace-runtime-db!` / `replace-frame-state!` (EP-0001, Mike rulings #1 + #10 — they supersede the old `reset-frame-db!`, whose db-shaped name would have silently replaced runtime-db too).

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `epoch-history` | Fn | `(epoch-history frame-id)` → vector of epoch records. Returns `[]` for an unknown / destroyed frame (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | tooling | Tool-Pair |
| `restore-epoch!` | Fn | `(restore-epoch! frame-id epoch-id)` → boolean (true on success). Rewinds to the record's canonical `:frame-state-after` (BOTH partitions — reviving machines / routes / elision / SSR), not just the app-db projection (EP-0001, Mike ruling #2). Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false` for an unknown / destroyed frame (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | tooling | Tool-Pair |
| `replace-app-db!` | Fn | `(replace-app-db! frame-id app-db)` → boolean — replace **only** the app-db partition (state injection). Records a synthetic epoch. Emits `:rf.error/no-such-handler` (kind `:frame`) / returns `false` for an unknown / destroyed frame. Renamed from `reset-frame-db!` (Mike ruling #10). | v1 (dev-only) | tooling | Tool-Pair |
| `reset-app-db!` | Fn | `(reset-app-db! frame-id)` → boolean — reset the app-db partition to `{}` while preserving live runtime-db (machines / routes survive). The app-db-only sibling of the whole-frame `reset-frame!` (EP-0001, Mike ruling #10). | v1 (dev-only) | tooling | Tool-Pair |
| `replace-runtime-db!` | Fn | `(replace-runtime-db! frame-id runtime-db)` → boolean — replace **only** the runtime-db partition. Privileged runtime / full-frame tool surface. | v1 (dev-only) | tooling | Tool-Pair |
| `replace-frame-state!` | Fn | `(replace-frame-state! frame-id frame-state)` → boolean — replace **both** partitions atomically (`{:rf.db/app … :rf.db/runtime …}`); the full-frame install for tool-driven replay / fixture install. A db-shaped name never silently replaces runtime-db — this is the explicit full-frame surface (Mike ruling #10). | v1 (dev-only) | tooling | Tool-Pair |
| `register-epoch-listener!` | Fn | `(register-epoch-listener! key callback-fn)` — assembled-epoch listener. Process-global; a callback whose previously-observed frame is destroyed receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 (dev-only) | tooling | Tool-Pair, 009 |
| `unregister-epoch-listener!` | Fn | `(unregister-epoch-listener! key)` | v1 (dev-only) | tooling | Tool-Pair, 009 |
| `(rf/configure! :epoch-history {:depth N})` | — | See [§Configure keys](#configure-keys). | v1 (dev-only) | — (configure key) | Tool-Pair |
| `app-db-value` / `runtime-db-value` / `frame-state-value` (cross-ref to [§Public registrar query API](#public-registrar-query-api)) | Fn | Partition readers — app-db, runtime-db, and the coherent frame-state projection. Each returns `nil` for an unknown / destroyed frame (per [Tool-Pair §Surface behaviour against destroyed frames](Tool-Pair.md#surface-behaviour-against-destroyed-frames)). | v1 | advanced/tooling | 002 |

Trace events emitted by epoch-history machinery:

| `:operation` | Tags |
|---|---|
| `:rf.epoch/snapshotted` | `:frame`, `:rf.epoch/id`, `:rf.trace/event-id` |
| `:rf.epoch/restored` | `:frame`, `:rf.epoch/id` |
| `:rf.epoch/db-replaced` | `:frame`, `:rf.epoch/id` |
| `:rf.epoch/restore-unknown-epoch` | `:frame`, `:rf.epoch/id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | `:frame`, `:rf.epoch/id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | `:frame`, `:rf.epoch/id`, `:missing` |
| `:rf.epoch/restore-version-mismatch` | `:frame`, `:rf.epoch/id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `:frame`, `:rf.epoch/id` |
| `:rf.epoch/restore-non-ok-record` | `:frame`, `:rf.epoch/id`, `:rf.epoch/outcome`, `:halt-reason` |
| `:rf.epoch/replace-during-drain` | `:frame` |
| `:rf.epoch/replace-schema-mismatch` | `:frame`, `:failing-paths` |
| `:rf.epoch.cb/silenced-on-frame-destroy` | `:frame`, `:cb-id` |

<a id="elide-wire-value-the-wire-boundary-walker"></a>

### Size-elision wire-boundary walker

> Cross-reference: see [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) — `elide-wire-value` is named there as the **single normative emission site** for the `:rf/redacted` sentinel. Every off-box egress (trace forwarders, MCP servers, error monitors) routes through this walker; the trust-boundary surfaces catalogued in [Security.md](Security.md) compose against this primitive.

The framework primitive that walks tree-shaped values at the wire boundary and substitutes elision markers for sensitive or large slots. Consumed by every tool that emits wire data (the off-box error-monitor forwarders, the Xray-MCP / re-frame2-pair-mcp / story-mcp servers per [Tool-Pair.md](Tool-Pair.md), the on-box dev panels). The walker is the **single normative emission site** for the `:rf/redacted` sensitive sentinel and the `:rf.size/large-elided` marker; per-tool reimplementation is prohibited.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `elide-wire-value` | Fn | `(elide-wire-value v opts)` → `v` or an elision-marker substitution. `opts` is a map: `{:rf.size/include-large? <bool> :rf.size/include-sensitive? <bool> :rf.size/include-digests? <bool> :rf.size/threshold-bytes <int> :path [...] :frame <frame-id>}`. Defaults: both `include-*` flags `false` (maximum elision); `:rf.size/threshold-bytes` falls back to `(rf/configure! :elision ...)` then `16384`. Walks `v` consulting `[:rf.runtime/elision :declarations]` and `[:rf.runtime/elision :sensitive-declarations]` of the named frame's **runtime-db**; substitutes `:rf/redacted` for sensitive slots and `:rf.size/large-elided` markers for large slots. Composition rule (normative): when both predicates match the **sensitive drop wins** — the size marker is suppressed because it would leak `:path` / `:bytes` / `:digest`. Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces) and [Spec-Schemas §`:rf/elision-marker`](Spec-Schemas.md#rfelision-marker). | v1 | tooling | 009 |
| `elision-declarations` | Fn | `(elision-declarations)` / `(elision-declarations frame-id)` → the current `[:rf.runtime/elision :declarations]` map for the frame (or `{}`). Pair-tool / introspection reader for paths nominated for elision (frame-sourced — installed by `reg-frame` `:large {:app-db ...}`). The zero-arity form resolves the frame from the carried scope; under no scope it raises `:rf.error/no-frame-context` (EP-0002 — no `:rf/default` default). Per [009 §Size elision in traces](009-Instrumentation.md#size-elision-in-traces). | v1 | tooling | 009 |

**Frame-owned declaration path (EP-0015 §8).** The `[:rf.runtime/elision]` registry has exactly two slots: `:declarations` (`:large?` paths) and `:sensitive-declarations` (`:sensitive?` paths). Durable app-db classification is **frame-owned** — declared on `reg-frame` as `:large {:app-db [...]}` / `:sensitive {:app-db [...]}` and installed by `re-frame.frame-classification` under `:source :frame`. A `reg-app-schema` `{:large? true}` / `{:sensitive? true}` slot prop is **no longer** a second route into this registry: schemas describe shape and validation, not durable app-db egress policy (the schema's `:sensitive?` still drives schema-validation-failure-trace redaction, and machine / resource `:data-schema` per-slot props still classify owner-local data — EP-0005, unchanged). Per [015 §Frame-owned durable classification](015-Data-Classification.md#frame-owned-durable-classification) and `implementation/core/src/re_frame/elision.cljc` L1-16.

### Record-level egress projection (EP-0015 / Spec 015)

> Cross-reference: [015 §Projection](015-Data-Classification.md#projection) is the normative home; `project-egress` is the public, record-level boundary primitive layered over `elide-wire-value`. The six-member `:rf.egress/*` profile enum is the named-boundary vocabulary; the boolean `:rf.size/*` flags remain the advanced override layer beneath it (EP-0015 §10/§11).

`rf/project-egress` is the required projection step before any off-box sink. It dispatches on a record's `:kind` (the `:rf.observe/*` record kinds) to a **private** per-kind projector — only `project-egress` is public (EP-0015 issue 2: the name names the *boundary*, not a record kind) — and delegates every tree-shaped slot to [`rf/elide-wire-value`](#elide-wire-value-the-wire-boundary-walker). A profile resolves to a `:rf.size/*` opt-set; an explicit `:rf.size/*` boolean composes on top (the override wins).

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `project-egress` | Fn | `(project-egress record-or-value)` / `(project-egress record-or-value opts)` → a value safe to ship under the resolved profile. `opts` conforms to [`:rf/project-egress-opts`](Spec-Schemas.md#rfproject-egress-opts): `{:rf.egress/profile <closed six-member enum> :frame <frame-id> :path [...] :rf.size/include-sensitive? <bool> :rf.size/include-large? <bool> :rf.size/include-digests? <bool> :rf.size/threshold-bytes <int> :as-of-epoch <epoch>}`. Dispatches on a record's `:kind` (`:rf.observe/handled-event` / `:rf.observe/error`) to a private per-kind projector, falling back to walking a kindless input as a tree-shaped value (the direct-read path); delegates tree-shaped slots to `elide-wire-value`. Profile resolves to a `:rf.size/*` opt-set; explicit `:rf.size/*` booleans compose on top (override wins). Unknown profile raises `:rf.error/unknown-egress-profile` (closed enum). Fail-closed when no frame is known (no `:rf/default` synthesis). Per [015 §`project-egress`](015-Data-Classification.md#project-egress--the-record-level-boundary-primitive). | v1 | tooling | 015 |

### Frame-owned observability sinks (EP-0015 §9 / Spec 015)

> Cross-reference: [015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy) is the normative home. The **normal** production observability story (Datadog / Sentry / Honeycomb): an app declares a sink under a frame's `:observability` config (`{:handled-events [{:sink <id> :rf.egress/profile … :opts {…}}] :errors [...]}`) and registers the concrete sink fn against that `<id>` with `rf/register-observability-sink!`. The runtime routes one `:rf.observe/handled-event` record per processed event and one `:rf.observe/error` record per `:rf.error/*` site through [`rf/project-egress`](#record-level-egress-projection-ep-0015--spec-015) — under the owning frame's classification and the entry's egress profile (default `:rf.egress/off-box-observability`) — to the declared sink. **Sinks consume already-projected records only** (no sink-local redaction). Always-on (survives `:advanced` + `goog.DEBUG=false`). The lower-level `register-event-listener!` / `register-error-listener!` corpus-wide registries remain for advanced integration. Routing is **fail-closed**: an unresolved frame or absent `:observability` policy routes nothing (no `:rf/default` synthesis), and a throwing sink is isolated from its siblings.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `register-observability-sink!` | Fn | `(register-observability-sink! sink-id f)` — register the concrete observability sink fn `f` under the keyword `sink-id` the frame's `:observability {:sink <sink-id> …}` entry names. `f` receives one **already-projected** `:rf.observe/handled-event` / `:rf.observe/error` record (projected under the owning frame's classification + the entry's egress profile); its return value is ignored. Re-registering the same id replaces. Returns `sink-id`. The framework ships no Datadog / Sentry client (EP-0015 Non-Goals); the sink fn is an app / integration-library concern. **Always-on**. Per [015 §Frame-owned observability sink policy](015-Data-Classification.md#frame-owned-observability-sink-policy). | v1 | tooling | 015 |
| `unregister-observability-sink!` | Fn | `(unregister-observability-sink! sink-id)` → nil | v1 | tooling | 015 |

### DOM source-coord annotations (mandatory)

Per [Spec 006 §Source-coord annotation](006-ReactiveSubstrate.md#source-coord-annotation-mandatory) and [Tool-Pair §Source-mapping](Tool-Pair.md), every adapter whose host has a DOM-attribute concept MUST inject `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. Format and exemptions (Fragments, non-DOM roots) are documented in Spec 006 §Source-coord annotation. Annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production `:advanced` builds elide the attribute via dead-code elimination — there is no DOM-bytes cost in shipped bundles. The JVM SSR emitter mirrors the same contract per [Spec 011 §Source-coord annotation under SSR](011-SSR.md#source-coord-annotation-under-ssr).

### Error contract

Errors are emitted as structured trace events with `:op-type :error` (or `:warning` / `:info` / `:fx` / `:flow` / `:frame`) and a per-category `:operation` keyword. The complete normative catalogue — every `:rf.error/*`, `:rf.warning/*`, `:rf.fx/*`, `:rf.cofx/*`, `:rf.ssr/*`, `:rf.epoch/*`, `:rf.flow/*`, `:rf.http/*`, `:rf.http.interceptor/*`, `:rf.frame/*`, and `:rf.route.nav-token/*` event the runtime emits — lives at [009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) (single source of truth for category names, `:op-type` discriminator, trigger conditions, default `:recovery`, and `:tags` payload keys). Per-category Malli `:tags` schemas are canonicalised at [Spec-Schemas §Per-category `:tags` schemas](Spec-Schemas.md#per-category-tags-schemas) — one schema per catalogue row.

Recent additions consumers should be aware of: `:rf.ssr/version-mismatch`, `:rf.ssr/schema-digest-mismatch`, `:rf.ssr/compatibility-check-skipped` (the SSR hydration compatibility-check trio,), and `:rf.cofx/skipped-on-platform` (the platform-gating mirror of `:rf.fx/skipped-on-platform`). The catalogue at 009 is the single source of truth — do not duplicate the table here.

Per-Spec emit-sites: [002-Frames](002-Frames.md), [005-StateMachines](005-StateMachines.md), [006-ReactiveSubstrate](006-ReactiveSubstrate.md), [010-Schemas](010-Schemas.md), [011-SSR](011-SSR.md), [012-Routing](012-Routing.md), [013-Flows](013-Flows.md), [014-HTTPRequests](014-HTTPRequests.md), [Tool-Pair](Tool-Pair.md). Each catalogue row's "Per [N]" cross-link names the owning Spec section.

### Privacy (Spec 009 §Privacy / sensitive data in traces)

> Cross-reference: see [Security.md §Privacy / secret handling](Security.md#privacy--secret-handling) for the framework-wide pattern-level posture; the trust-boundary catalogue lives in [Security.md](Security.md). The **cross-artefact inventory + composition order** (every privacy surface in `re-frame.core`, `re-frame.http`, `re-frame.schemas`, `re-frame.epoch`, `tools/mcp-base`, with the data-flow from handler exit to off-box wire) lives in [Privacy.md](Privacy.md). Per **EP-0015** (frame-owned egress policy, accepted 2026-06-11) the public classification model is **frame-owned `:sensitive` / `:large` classification** declared on the frame (see [015 §Frame-owned durable classification](015-Data-Classification.md)) and **registration-owned** `:sensitive` payload classification on `reg-event` / `reg-sub` / `reg-flow`, projected at trust boundaries by `project-egress` and the `:rf.egress/*` profiles. The imperative `add-marks` / `set-marks` path-marks API and the positional `redact-interceptor` are **no longer part of the public façade** (EP-0015 §3 + §7); their underlying fns remain internal/test helpers only.

Per [Spec 009 §Privacy](009-Instrumentation.md) the runtime stamps `:sensitive? true` at the top level of every trace event emitted inside the scope of a handler whose declared path overlap classifies sensitivity. (The legacy handler-meta `:sensitive?` annotation has been removed; sensitive-data marking is path-based per the data-classification mechanism in [Spec 015](015-Data-Classification.md).) Framework-published trace consumers (Sentry/Honeybadger forwarders, re-frame2-pair server, Xray, Story, story-mcp, re-frame2-pair-mcp) MUST default-drop the stamped events at their egress boundary.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `sensitive?` | Fn | `(sensitive? trace-event)` → `boolean`. True iff `trace-event` is a map carrying `:sensitive? true` at the top level (not under `:tags`). The framework-published predicate every consumer composes against — replaces per-consumer reimplementations of the same five-token check. | v1 | tooling | 009 |

---

## Spec-internal schemas

Per [Spec-Schemas.md](Spec-Schemas.md), the spec's own runtime shapes are described as Malli schemas registered at runtime. These are the **conformance contract** an implementation validates against.

| Schema | Describes | Spec |
|---|---|---|
| `:rf/dispatch-envelope` | Internal envelope wrapping every dispatch | 002 |
| `:rf/dispatch-opts` | The user-facing opts map for `dispatch` / `dispatch-sync` / `subscribe` | 002 |
| `:rf/registration-metadata` | Common metadata-map shape across `reg-*` | 001 / 010 |
| `:rf/effect-map` | Return value of `reg-event` handlers — **closed**: `#{:db :rf.db/runtime :fx}` (`:rf.db/runtime` reserved by convention for framework authority; app handlers use only `:db` / `:fx`) | 002 |
| `:rf/trace-event` | Universal trace event shape | 009 |
| `:rf/error-event` | Refinement of `:rf/trace-event` for `:op-type :error` / `:warning` (unified error/warning envelope) | 009 |
| `:rf/handler-body-dsl` | Conformance corpus handler-body DSL (host-agnostic event/sub bodies; small-DSL grammar) | 008 / Spec-Schemas |
| `:rf/transition-table` | State-machine transition table grammar | 005 |
| `:rf/machine-snapshot` | Runtime snapshot of a machine instance | 005 |
| `:rf/hydration-payload` | Wire format for SSR hydration | 011 |
| `:rf/response` | HTTP-response accumulator owned by the request frame during SSR | 011 |
| `:rf.server/cookie` | Structured-cookie shape for `:rf.server/set-cookie` / `:rf.server/delete-cookie` | 011 |
| `:rf/head-model` | SSR head/meta data model (title, meta, link, json-ld, html/body attrs) | 011 |
| `:rf/public-error` | Sanitised, client-safe projection of an internal error trace event | 011 |
| `:rf.fx.server/set-status-args` / `:rf.fx.server/set-header-args` / `:rf.fx.server/append-header-args` / `:rf.fx.server/set-cookie-args` / `:rf.fx.server/delete-cookie-args` / `:rf.fx.server/redirect-args` | Args of standard `:rf.server/*` SSR fx | 011 |
| `:rf/frame-meta` | Returned by `(frame-meta frame-id)` | 002 |
| `:rf/variant` | Story-variant artefact contract (post-v1 lib) — variants are data, no fn-valued slots | 007 |
| `:rf/epoch-record` | Per-frame epoch snapshot record (Tool-Pair) | Tool-Pair |
| `:rf.fx/dispatch-args` | Args of standard `:dispatch` fx (and `:raise`, same shape) | 002 / 005 |
| `:rf.fx/dispatch-later-args` | Args of standard `:dispatch-later` fx | 002 |
| `:rf.fx/http-args` | Args of `:http` fx (user-owned recommendation) | Pattern-RemoteData |
| `:rf.fx/nav/push-url-args` | Args of `:rf.nav/push-url` fx | 012 |
| `:rf.fx/nav/replace-url-args` | Args of `:rf.nav/replace-url` fx | 012 |
| `:rf.fx/nav/scroll-args` | Args of `:rf.nav/scroll` fx | 012 |
| `:rf.fx/with-nav-token-args` | Args of `:rf.route/with-nav-token` fx wrapper | 012 |
| `:rf.fx/spawn-args` | Args of `:rf.machine/spawn` fx (the canonical actor-lifecycle fx-id; emitted from any event handler's `:fx`) | 005 |
| `:rf.fx/managed-args` | Args of `:rf.http/managed` fx (request envelope, decode, accept, retry, timeout-ms, on-success/on-failure, request-id, abort-signal) | 014 |
| `:rf.fx/managed-abort-args` | Args of `:rf.http/managed-abort` fx (request-id) | 014 |
| `:rf.http/reply` | Reply-payload envelope `{:kind :success :value v}` / `{:kind :failure :failure {:kind <:rf.http/*> ...}}` lands under `:rf/reply` | 014 |
| `:rf/route-rank` | Structural-rank tuple for route-precedence sorting | 012 |
| `:rf/pending-navigation` | Pending-navigation slot when `:can-leave` guard rejects | 012 |
| `:rf/elision-registry` | Per-frame size-elision declaration registry in the reserved runtime-db child `[:rf.runtime/elision]` | 009 |
| `:rf/elision-marker` | Wire shape `rf/elide-wire-value` substitutes for an elided large value (`:rf.size/large-elided`) | 009 |
| `:rf/project-egress-opts` | The opts map `rf/project-egress` accepts (`:rf.egress/profile` + advanced `:rf.size/*` overrides) | 015 |

Schemas are **open** by default (consumers tolerate unknown keys; producers grow shapes additively); `:closed true` is opt-in at boundary-validation sites and on the effect-map.

---

## Testing

The testing surface lives across three namespaces. `re-frame.core` carries the production primitives that double as testing entry points (`make-frame`, `with-frame`, `with-new-frame`, `dispatch-sync`, `with-fx-overrides`, `app-db-value`, `snapshot-of`, `compute-sub`, `machine-transition`, `sub-topology`). `re-frame.test-support` ships the test-only fixture machinery and test-flavoured helpers. `re-frame.test-helpers` ships the view-assertion helpers (hiccup-walk + `testid` authoring). `re-frame.test-support` does **not** re-export from `re-frame.core` — a test file requires both `[re-frame.core :as rf]` and `[re-frame.test-support :as ts]`, and additionally `[re-frame.test-helpers :as th]` for view-assertion tests. See [008-Testing.md](008-Testing.md) for fixtures, framework adapters, and `re-frame-test` compatibility.

| API | M/Fn | Signature | Status | Tier | Spec | Notes |
|---|---|---|---|---|---|---|
| `dispatch-sequence` | Fn | `(dispatch-sequence events)` / `(dispatch-sequence events opts)` | v1 | testing | 008 | `opts`: `:after-each (fn [db ev] ...)`, `:frame`. Returns final `app-db`. Lives in `re-frame.test-support`. |
| `assert-path-equals` | Fn | `(assert-path-equals path expected-val)` / `(assert-path-equals path expected-val opts)` | v1 | testing | 008 | Path-form sync assertion. Mismatch fires `clojure.test/is`-style failure via `do-report`. Lives in `re-frame.test-support`. **Mirrors the `:rf.assert/path-equals` event** used inside a Story `:play` block — same name root so the fn-side and event-side are navigable without a translation table. The wider sibling event family (`:rf.assert/sub-equals`, `:rf.assert/state-is`, `:rf.assert/dispatched?`, `:rf.assert/no-warnings`, `:rf.assert/effect-emitted`, `:rf.assert/path-matches`) lives in [007 §Play functions](007-Stories.md#play-functions); runner and reporting channel differ. Choose by test surface: `assert-path-equals` from a `deftest` body, `:rf.assert/path-equals` from a story variant's `:play` vector. |
| `assert-db-equals` | Fn | `(assert-db-equals expected-db)` / `(assert-db-equals expected-db opts)` | v1 | testing | 008 | Full-db sync assertion (no `:rf.assert/*` event analog — the event family is path-keyed). Mismatch fires `clojure.test/is`-style failure via `do-report`. Companion to `assert-path-equals`. Lives in `re-frame.test-support`. |
| `poll-until` | Fn | `(poll-until pred)` / `(poll-until pred opts)` | v1 | testing | 008 | Bounded-deadline poll. JVM: synchronous — returns the truthy value, throws `ex-info` carrying `:rf.error/id` `:rf.error/poll-until-timeout` (the canonical discriminator, per [Spec 009](009-Instrumentation.md)) on timeout. CLJS: returns a `js/Promise` resolving with the truthy value or rejecting on timeout. Opts: `:timeout-ms` (default 2000), `:interval-ms` (default 5), `:label`. Lives in `re-frame.test-support`. |
| `with-fx-overrides` | M | `(with-fx-overrides {fx-id -> override, …} body+)` | v1 | testing | 002, 008 | Lexical-scope `:fx-overrides` binding. Every `dispatch` / `dispatch-sync` inside the body merges the supplied map into its envelope's `:fx-overrides`. Precedence: per-call opt > lexical `with-fx-overrides` > per-frame `:fx-overrides`. Composes with `with-frame`. Lives in `re-frame.core`. Renamed from `with-overrides` per [MIGRATION §M-50](../migration/from-re-frame-v1/README.md#m-50-with-overrides-macro-renamed-to-with-fx-overrides). |
| `compute-sub` | Fn | `(compute-sub query-v db)` | v1 | testing | 008 | Pure sub computation against an `app-db` value. Lives in `re-frame.core`. |
| `snapshot-registrar` / `restore-registrar!` / `with-fresh-registrar` / `make-reset-runtime-fixture` | Fn | per docstring | v1 | testing | 008 | Fixture machinery. Lives in `re-frame.test-support`. |

### Testing — view-assertion helpers

`re-frame.test-helpers` ships the hiccup-walk view-assertion surface — call the view-fn directly, walk the returned hiccup, assert on content or invoke a handler. JVM-runnable; no JSDOM, no React, no `act()`. Pairs with `render-to-string` (the HTML-string view-test path per Spec 011): hiccup-walk for structure / handler assertions, `render-to-string` for HTML-markup assertions. Per [008-Testing §View-assertion helpers](008-Testing.md#view-assertion-helpers-re-frametest-helpers).

| API | M/Fn | Signature | Status | Tier | Spec | Notes |
|---|---|---|---|---|---|---|
| `expand-tree` | Fn | `(expand-tree tree) → tree` | v1 | testing | 008 | Recursively expand fn-components and Form-3 class components inside a hiccup tree. After expansion every vector's first element is a keyword tag or a non-component value. Lives in `re-frame.test-helpers`. |
| `attrs` | Fn | `(attrs node) → map?` | v1 | testing | 008 | Return the attrs map of a hiccup node, or `nil`. Lives in `re-frame.test-helpers`. |
| `children` | Fn | `(children node) → vector` | v1 | testing | 008 | Return the child elements — everything after the tag (and optional attrs map). Lives in `re-frame.test-helpers`. |
| `text-content` | Fn | `(text-content node) → string` | v1 | testing | 008 | Recursively collect string leaves under `node` and join. Numbers coerce to strings; nils are skipped. Lives in `re-frame.test-helpers`. |
| `extract-handler` | Fn | `(extract-handler node event-key) → fn?` | v1 | testing | 008 | Return the value of `event-key` from `node`'s attrs map, or `nil`. Lives in `re-frame.test-helpers`. |
| `find-by-attr` | Fn | `(find-by-attr tree attr val) → node?` | v1 | testing | 008 | First hiccup node whose attrs map carries `attr == val`, or `nil`. Generic over the attribute keyword (`:data-testid`, `:data-test`, `:id`, custom). Lives in `re-frame.test-helpers`. |
| `find-all-by-attr` | Fn | `(find-all-by-attr tree attr val) → vector` | v1 | testing | 008 | Every matching node, in depth-first order. Lives in `re-frame.test-helpers`. |
| `find-by-attr-prefix` | Fn | `(find-by-attr-prefix tree attr prefix) → vector` | v1 | testing | 008 | Every node whose `attr` value (a string) STARTS with `prefix`. Non-string attr values do not match. Lives in `re-frame.test-helpers`. |
| `find-by-testid` | Fn | `(find-by-testid tree test-id) → node?` | v1 | testing | 008 | Convenience over `find-by-attr` keyed on `:data-testid`. Lives in `re-frame.test-helpers`. |
| `find-all-by-testid` | Fn | `(find-all-by-testid tree test-id) → vector` | v1 | testing | 008 | Convenience over `find-all-by-attr` keyed on `:data-testid`. Lives in `re-frame.test-helpers`. |
| `find-by-testid-prefix` | Fn | `(find-by-testid-prefix tree prefix) → vector` | v1 | testing | 008 | Convenience over `find-by-attr-prefix` keyed on `:data-testid`. Lives in `re-frame.test-helpers`. |
| `invoke-handler` | Fn | `(invoke-handler node event-key & args) → any` | v1 | testing | 008 | Find the handler under `event-key` on `node` and call it with `args`. Returns the handler's return value. THROWS when `node` is not a hiccup vector, the node has no attrs map, or no handler is registered — the throwing failure mode is deliberate (a missing handler is almost always a test bug). Lives in `re-frame.test-helpers`. |
| `testid` | Fn | `(testid id)` / `(testid id extra) → map` | v1 | testing | 008 | Build an attrs map carrying `:data-testid id`. The 2-arity merges `extra` into the map; `:data-testid` always wins on collision. Authoring helper at the view call site. Lives in `re-frame.test-helpers`. |

---

## Standard interceptors

Under [EP-0022](../docs/EP/EP-0022-registered-interceptors.md) the public interceptor-authoring surface is **`reg-interceptor`** ([§Registration](#registration)), and event/frame `:interceptors` chains carry interceptor **references** (a bare keyword id or `[id arg]`), never inline values. The v2 framework-standard interceptor surface is exactly **one** interceptor — `:rf.interceptor/path` — referenced as `[:rf.interceptor/path <path-vector>]`. There is no public `rf/path` value constructor and no standard `unwrap` (EP-0022 §No standard `unwrap` — handler destructuring, or a project-registered interceptor for intentional chain-wide event reshaping, replaces it). The earlier line (keep specific-work helpers, drop trivial `(->interceptor :before f)` ones) narrows to path-only because `path` is coupled to app-db commit no-op semantics and justifies the `:factory` mechanism. Five v1 interceptors removed: `debug`, `trim-v`, `on-changes`, `enrich`, `after` (per [MIGRATION §M-21](../migration/from-re-frame-v1/README.md#m-21-drop-debug-trim-v-on-changes-enrich-after-interceptors)).

`inject-cofx` / `inject-cofx*` — the v1 coeffect-injection interceptors — are **removed** (EP-0017 slice A, no alias). They are **not on the public `re-frame.core` facade and carry no API-manifest row** (a removed surface is not part of the canonical public API): there is no public `inject-cofx` var to call. Coeffect delivery is no longer a chain member: a handler declares `:rf.cofx/requires` on its registration metadata and the value-returning supplier's result arrives flat in the coeffects map ([§Registration — `reg-cofx`](#registration), [001 §`:rf.cofx/requires`](001-Registration.md#rfcofxrequires--the-declaration-key)). The removal alarm survives internally: a stale call still raises the always-on hard error `:rf.error/inject-cofx-removed` naming `:rf.cofx/requires`. See [docs/api/15 — Removed](../docs/api/15-removed.md).

| Surface | Shape | Tier | Purpose |
|---|---|---|---|
| `[:rf.interceptor/path <path-vector>]` | interceptor **reference** (the one standard interceptor; the canonical `:factory` consumer) | front-porch | Focus a handler on an `app-db` sub-slice: `:before` stages the focused slice as `:db`, `:after` widens the returned slice back into full app-db. Preserves the frame-commit `identical?` no-op — an unchanged focused slice widens back to the original app-db object, not an `assoc-in` allocation ([002 §Standard `:rf.interceptor/path`](002-Frames.md#standard-rfinterceptorpath)). A non-vector/malformed path arg is `:rf.error/path-interceptor-bad-path`. |
| `reg-interceptor` | M (registrar) | front-porch | The public application-authoring form for any non-standard interceptor — analytics, logging, validation, ad-hoc context manipulation. The resulting interceptor is named, addressable, queryable, and referenced by id from chains. Rowed in [§Registration](#registration). |
| `->interceptor*` | Fn (internal lowering constructor) | advanced | The framework-internal constructor that lowers a descriptor into an executable chain entry (`(->interceptor* & {:keys [id before after source-coord]})`). **NOT a public application-authoring API** and MUST NOT appear in a public event/frame chain (EP-0022). |

The retired v1 public interceptor-authoring helpers and their replacements:

| Removed / retired API | Replaced by |
|---|---|
| `path` (public value constructor) | the standard reference `[:rf.interceptor/path <path-vector>]` (EP-0022) |
| `unwrap` | handler destructuring (the M-19 canonical map-payload form), or a project-registered interceptor for intentional chain-wide event reshaping (EP-0022 — no standard `unwrap`) |
| `->interceptor` (public authoring macro) | `reg-interceptor` (EP-0022 — `->interceptor` survives only as the internal lowering constructor) |
| `debug` | Trace surface ([009](009-Instrumentation.md)) + 10x / re-frame-pair |
| `trim-v` | Canonical map-payload call shape ([M-19](../migration/from-re-frame-v1/README.md#m-19-multi-positional-dispatch--subscribe-vectors--map-payload-form-opt-in)) |
| `on-changes` | Flows ([Spec 013](013-Flows.md)) |
| `enrich` | Flows (derived state) / `:schema` (validation) / a project-registered interceptor (escape hatch) |
| `after` | Registered fx (`:fx [[:my-fx ...]]`) for side-effects; a project-registered interceptor for context-shaped work |

### `reg-flow` / `clear-flow` (Spec 013)

`reg-flow` is rowed canonically in [§Registration](#registration); required flow-map keys are `:id`, `:inputs`, `:output`, `:path`. Both surfaces take an optional opts map (`{:frame frame-id}`) selecting the owning frame: `(reg-flow flow)` / `(reg-flow flow opts)` and `(clear-flow id)` / `(clear-flow id opts)`. `clear-flow` is rowed canonically in [§Clearing registrations](#clearing-registrations); it deregisters the flow from the named frame and `dissoc-in`s its `:path` from that frame's `app-db` only (per Spec 013 §Frame-scoping).

**Frame-destroy teardown.** `destroy-frame!` releases every per-frame piece of flow state (registry slot, `last-inputs` rows, registrar entries for ids the destroyed frame was last owner of) per [Spec 013 §Frame-destroy teardown](013-Flows.md#frame-destroy-teardown). Sibling frames' state is preserved.

**Flow-eval failures in production.** A throw inside a flow's `:output` fn surfaces as `:rf.error/flow-eval-exception` on the **always-on error-emit substrate** — registered `register-error-listener!` callbacks fire under CLJS `:advanced` + `goog.DEBUG=false`. The error is NOT trace-only. Per [Spec 013 §Failure semantics](013-Flows.md#failure-semantics) rule 4 and [009 §Production builds](009-Instrumentation.md#production-builds-zero-overhead-zero-code).

Reserved fx-ids for runtime flow management via `:fx`:

| Name | Kind | Signature | Status |
|---|---|---|---|
| `:rf.fx/reg-flow` | Reserved fx-id | `[:rf.fx/reg-flow flow-map]` — register a flow at runtime via `:fx` | v2 |
| `:rf.fx/clear-flow` | Reserved fx-id | `[:rf.fx/clear-flow id]` — clear a registered flow via `:fx` | v2 |

---

## Interceptor / context plumbing

The interceptor context accessors `get-coeffect` / `assoc-coeffect` / `get-effect` / `assoc-effect` are **removed** from the public `re-frame.core` façade and carry **no API-manifest row** (a removed surface is not part of the canonical public API). Post-EP-0017/EP-0022 they lost their audience — the setters had zero callers, the getters one. The intended interceptor model is to author with `reg-interceptor` and let the `:before` / `:after` fns receive and return the context map directly: read coeffects with `(get-in ctx [:coeffects k])` and write effects with `(assoc-in ctx [:effects k] v)`. The underlying `re-frame.interceptor/{get,assoc}-{coeffect,effect}` fns remain in their owning namespace as framework-internal context helpers; they are not a public surface. See [§Removed / not shipped](#removed--not-shipped).

---

## Lifecycle / utility

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `init!` | Fn | `(init! adapter-map)` — idempotent boot. Required arg: the adapter spec map. Each adapter ns exports an `adapter` Var; consumers require the ns and pass the Var, e.g. `(rf/init! reagent/adapter)`. Calling `(init!)` with no args raises a language-level `ArityException` at compile/load time ( — the no-arg arity was cut so the missing-adapter mistake surfaces before runtime). Calling `(init! nil)` or `(init! :reagent)` raises `:rf.error/no-adapter-specified` at runtime. Per [006 §Adapter selection at boot](006-ReactiveSubstrate.md#adapter-selection-at-boot). Installs adapters and runtime capabilities only — it creates **no** frame (EP-0002: no auto `:rf/default`); the app registers its frame with `reg-frame` and establishes it at the root | v1 | front-porch | 006 |
| `init-platform` | Fn | `(init-platform p)` — set the host-wide active-platform marker (`:server` or `:client`). The runtime tracks the active platform so `reg-fx`/`reg-cofx` `:platforms` metadata can gate execution (per [011 §Effect handling on the server](011-SSR.md#effect-handling-on-the-server)). CLJS hosts default to `:client`, JVM hosts to `:server`; call at boot to override (CLJS-on-Node SSR runtime: `(rf/init-platform :server)`; JVM-runnable test simulating browser: `(rf/init-platform :client)`). Per-frame `:config :platform` (set by the `:ssr-server` preset) still wins over this host-wide marker. `p` must be `:server` or `:client`; anything else raises `:rf.error/invalid-platform`. Idempotent / re-callable. Sibling boot call to `init!` — independent (adapter install vs platform marker). | v1 | advanced | 011 |
| `install-adapter!` | Fn | `(install-adapter! adapter-map)` — must be called before any frame is created. Lower-level than `init!`; most consumers call `init!` instead | v1 | advanced | 006 |
| `destroy-adapter!` | Fn | `(destroy-adapter!)` — tear down the installed adapter. Calls the adapter spec's `:dispose-adapter!` fn (if present), clears the install slot so a new adapter can install, and flips the `adapter-disposed?` breadcrumb. Per [Conventions §Tear-down verb axis](Conventions.md#tear-down-verb-axis--clear--vs-destroy-) — `destroy-` cluster (lifecycle boundary; symmetric with `install-adapter!` and with `destroy-frame!`). The adapter-spec **map key** `:dispose-adapter!` (an internal contract slot adapters implement) is unchanged. | v2 | advanced | 006 |
| `current-adapter` | Fn | `(current-adapter)` → discriminator keyword (`:rf.adapter/reagent` / `:rf.adapter/reagent-slim` / `:rf.adapter/uix` / `:rf.adapter/helix` / `:rf.adapter/plain-atom` / `:rf.adapter/ssr` / `:custom`) or `nil` when no adapter is installed. Answers "what substrate am I on?" — predicate / branch code. For the spec map (fn handles, identity checks), use `current-adapter-spec`. | v1 | advanced | 006 |
| `current-adapter-spec` | Fn | `(current-adapter-spec)` → the installed adapter spec map (the value passed to `(rf/init! ...)`) or `nil` when no adapter is installed. Answers "give me the adapter fns to call" — tools, routing, identity checks across the install/dispose lifecycle. For the discriminator keyword, use `current-adapter`. | v1 | advanced | 006 |
| `adapter-disposed?` | Fn | `(adapter-disposed?)` → `true` iff the most recent lifecycle event was a successful `destroy-adapter!` and no subsequent `install-adapter!` has fired. `false` for never-installed (fresh process) and after a fresh install. Read-only — the breadcrumb is owned by the install / destroy pair. Use to distinguish `:rf.error/no-adapter-installed` (fresh process) from `:rf.error/adapter-disposed` (torn down). Per [006 §Disposed-vs-never-installed](006-ReactiveSubstrate.md#disposed-vs-never-installed). | v1 | advanced | 006 |
| `configure!` | Fn | `(configure! key opts)` — runtime config; key vocabulary in [§Configure keys](#configure-keys). One of three orthogonal configuration surfaces per [Conventions §Configuration surfaces](Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) (`configure!` for process-level data knobs; `set-!` / `install-!` for adapter-pluggable hooks; per-frame metadata for frame-scoped overrides). | v1 | front-porch | — |

---

## Feature inspection

re-frame2's optional capabilities ship as separate Maven artefacts (`day8/re-frame2-<feature>`) whose implementation namespaces core reaches through the late-bind hook registry at call time (per [Conventions §Facade re-export, artefact require](Conventions.md#facade-re-export-artefact-require)). The upside is bundle-isolation — an app that omits a feature does not carry its code. The downside this front-porch closes: the late-binding is otherwise invisible, so a developer who forgets to `:require` the impl artefact calls a re-exported fn that *exists* and is met with an opaque artefact-missing error. These three surfaces make the optional-feature inventory self-explaining.

The known optional features are `:schemas`, `:machines`, `:routing`, `:flows`, `:http`, `:ssr`, `:epoch` (the closed per-feature split set per [Conventions §Artefact tiers](Conventions.md#artefact-tiers)).

**These three fns ship to production.** They are runtime queries, not dev-time instrumentation, so — unlike the trace / epoch surfaces — they are NOT gated on `interop/debug-enabled?` and do NOT elide under `:advanced` + `goog.DEBUG=false`. A production caller may legitimately probe `(rf/feature-loaded? :routing)` before taking a routing-dependent path. The feature→coordinate mapping is **static data in the always-loaded `re-frame.core` facade** (a plain table of `{:feature {:maven … :require … :spec …}}` strings), never a live `:require` reaching into the optional impl namespaces — a live reach-in would create a hard facade→optionals reference that pulls every optional artefact into every production bundle, breaking bundle-isolation. `feature-loaded?` detects presence by a pure keyword lookup in the always-loaded late-bind hooks atom against a representative key the impl publishes at ns-load — no reach into the optional namespace.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `features` | Fn | `(features)` → map of every optional feature keyword to its inspection entry: the static coordinate data (`:maven` / `:require` / `:spec`) merged with the live `:loaded?` boolean. E.g. `{:epoch {:maven "day8/re-frame2-epoch" :require "re-frame.epoch" :spec "Tool-Pair (Time-travel / epoch)" :loaded? true} …}`. Ships to production (NOT elided). | v1 | advanced | — |
| `feature-loaded?` | Fn | `(feature-loaded? feature)` → `true` when the feature's impl artefact is on the classpath, `false` otherwise (including for an unknown feature keyword). Pure keyword lookup against the always-loaded late-bind hooks atom — no require into the optional namespace. Ships to production (NOT elided). | v1 | advanced | — |
| `require-feature!` | Fn | `(require-feature! feature)` → `true` when the feature is loaded; otherwise throws `:rf.error/feature-not-loaded` carrying the EXACT copy-pasteable Maven coordinate (`:maven`) + require namespace (`:require-ns`) and a `:reason` string naming both (an unknown feature keyword throws `:rf.error/unknown-feature` with the `:known` set). Use as a self-explaining early guard at the top of feature-dependent code. Ships to production (NOT elided). | v1 | advanced | — |

> **Artefact-missing errors carry the require.** This front-porch is paired with a hard rule: *every* artefact-missing error in the framework — including the existing late-bind facade throws (`:rf.error/<feature>-artefact-missing`, raised via `re-frame.late-bind/require-fn!` from the `re-frame.core-<feature>` wrappers) — carries the exact copy-pasteable Maven coordinate and the namespace to require at app boot in its `:reason` slot. The named pattern is documented once at [Conventions §Facade re-export, artefact require](Conventions.md#facade-re-export-artefact-require).

---

## Configure keys

Runtime configuration is uniformly via `(rf/configure! <key> <opts>)`. Every framework-owned key is enumerated here. Keys are plural-noun-shaped; opts are an open map of per-key settings.

| Key | Opts shape | Default | Status | Spec |
|---|---|---|---|---|
| `:epoch-history` | `{:depth N :trace-events-keep N :redact-fn (fn [record] …)}` — `:depth` non-negative integer (0 disables the ring); `:trace-events-keep` non-negative integer caps raw `:trace-events` retention (per [Security §Epoch privacy posture](Security.md#epoch-privacy-posture--raw-in-process-records-vs-projected-egress)); `:redact-fn` is `fn?` or `nil` — invoked once per assembled record at build-time (between `build-record` and ring-append / listener fan-out) so ring + listeners see the same redacted shape, with the `:rf.epoch/sensitive?` rollup computed first, throws caught and surfaced as `:rf.warning/epoch-redact-fn-exception` with fallback to the raw record. Per [Tool-Pair §Time-travel](Tool-Pair.md#time-travel-epoch-snapshots-and-undo). | `{:depth 50, :redact-fn nil}` | v1 (dev-only) | Tool-Pair |
| `:trace-buffer` | `{:cascades-retained N}` — non-negative integer cascade-slot count; 0 disables retention (the surface stays live) | `{:cascades-retained 50}` | v1 (dev-only) | 009 |
| `:elision` | `{:rf.size/threshold-bytes N}` — non-negative integer; 0 disables runtime auto-detect (only declared / schema entries elide) | `{:rf.size/threshold-bytes 16384}` | v1 | 009 |

> **Retired key.** The earlier `:sub-cache {:grace-period-ms N}` knob is gone. Sub-cache disposal is now synchronous on derefer-count → 0 (per [006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal)); there is no deferred-grace timer to configure.

SSR error-projection policy (`:public-error-id`, `:dev-error-detail?`) is **not** a `configure` key — it is per-frame metadata on the frame's `:ssr` map (see [Conventions §Configuration surfaces](Conventions.md#configuration-surfaces-configure-vs-set--vs-per-frame-metadata) bucket 3 and [011 §Server error projection](011-SSR.md#server-error-projection)). Different frames in the same process can carry different projector / dev-detail settings, so the natural lifetime is per-frame, not process-global.

### Opts-key naming rule

The opts map for any `configure` key mixes two shapes deliberately, and the choice is **not** stylistic — it encodes which contract owns the sub-key:

- **Framework-owned semantic sub-keys use a namespaced keyword** under a reserved `:rf.<area>/*` sub-namespace (per [Conventions §Reserved namespaces](Conventions.md#reserved-namespaces-framework-owned)). The namespace identifies the cross-spec policy area the sub-key participates in — the same key shape appears verbatim wherever that policy is consumed, not only inside `configure`. Example: `:elision` carries `{:rf.size/threshold-bytes N}` because `:rf.size/threshold-bytes` is the same per-call policy key consumed by `rf/elide-wire-value` and the wire-elision walker (per [Conventions §Reserved namespaces — `:rf.size/*`](Conventions.md#the-single-root-reserved-set)). The namespaced form makes the cross-surface identity grep-visible and prevents collision with adjacent per-knob settings.
- **Ergonomic per-knob sub-keys are unqualified bare keywords** (`:depth`, `:trace-events-keep`, `:redact-fn`). These sub-keys are local to a single `configure` key's opts map — they do not appear elsewhere in the framework's vocabulary, so a framework-owned namespace would add noise without adding identity. The bare form is the default at this leaf position; reach for it whenever the knob is unique to one `configure` key.

The discriminator is **whether the sub-key names a cross-surface policy slot or a one-off knob**. A sub-key earns a `:rf.<area>/*` namespace when it names a contract that lives in more than one place (`:rf.size/threshold-bytes` is read by `:elision`, by `elide-wire-value`, and by the MCP wire walker). A sub-key stays bare when it is local to its parent `configure` key (`:cascades-retained` under `:trace-buffer` and `:depth` under `:epoch-history` each tune one ring's slot count and live nowhere else in the vocabulary — separate knobs, no shared contract, so each stays bare).

New `configure` keys MUST apply the same rule: if a sub-key participates in a cross-spec policy area, qualify it under the area's reserved namespace; otherwise leave it bare. The rule is closed — there is no third shape (no `:configure/depth`, no `:rf.configure/*` prefix). A sub-key that would want a third shape is evidence the proposed knob is doing two things and should be split.

### Fixed-and-additive

The configure-keys vocabulary is fixed-and-additive (Spec-ulation): existing keys cannot be renamed or removed; new keys are added by extending the table. User code that wraps `configure` should pattern-match on known keys and ignore unknown ones.

---

## Machines

Split between the v1 machine-as-event-handler foundation and the post-v1 `re-frame.machines` scaffolding library — see [005-StateMachines.md §Disposition](005-StateMachines.md#disposition). The machine *is* the event handler: a machine is registered as one `reg-event` whose body comes from `make-machine-handler`.

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `reg-machine` | M | `(reg-machine machine-id machine-spec)` / `(reg-machine machine-id opts machine-spec)` — registers a machine as an event handler. Walks the literal spec form at expansion time and co-locates per-element source on each `:guards` / `:actions` entry + a reference-site `:source-coords` on each `:states`-tree map node. The optional `opts` map carries an event-vector `:schema` — the machine + event-vector-schema shape. | v1 | advanced | 005 |
| `reg-machine*` | Fn | `(reg-machine* machine-id machine-spec)` / `(reg-machine* machine-id machine-spec opts)` — plain-fn surface beneath the macro. No source-coord walking. The optional `opts` map carries the event-vector `:schema`. | v1 | advanced | 005 |
| `defmachine` | M | `(defmachine name [docstring] spec)` — `def`-shape that walks the literal `spec` at the definition site and co-locates per-element source onto the def'd value, for the `def`-then-register shape `(defmachine m {…})` / `(reg-machine :id m)`. Does not register. | v1 | advanced | 005 |
| `make-machine-handler` | Fn | `(make-machine-handler spec)` → event-handler fn | v1 | advanced | 005 |
| `machine-transition` | Fn | `(machine-transition definition snapshot event)` → `[next-snapshot effects]` | v1 | advanced | 005 |
| `machines` | Fn | `(machines)` → seq of registered machine-ids | v1 | tooling | 005 |
| `machine-meta` | Fn | `(machine-meta machine-id)` → registration metadata; `:guards` / `:actions` entries carry co-located `:source-coords` / `:source-code` and each `:states`-tree map node carries its own reference-site `:source-coords` when registered via the macro | v1 | tooling | 005 |
| `machine-by-system-id` | Fn | `(machine-by-system-id system-id)` / `(machine-by-system-id system-id frame-id)` → spawned-machine id bound to `system-id` in the frame's `[:rf.runtime/machines :system-ids]` reverse index (or `nil`). Per [005 §Named addressing via `:system-id`](005-StateMachines.md). | v1 | advanced | 005 |
| `dispatch-to-system` | Fn | `(dispatch-to-system system-id event)` / `(dispatch-to-system system-id event frame-id)` — sugar over `(when-let [m (machine-by-system-id system-id)] (dispatch [m event]))`; no-op when the system-id is unbound. Per [005 §Cross-machine messaging by name](005-StateMachines.md). | v1 | advanced | 005 |
| `machine-has-tag?` | Fn | `(machine-has-tag? machine-id tag)` → reaction whose value is `true` iff the machine's snapshot's `:tags` set contains `tag`. Sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])`. Per [005 §State tags](005-StateMachines.md). | v1 | advanced | 005 |
| `:rf.machine/spawn` (fx) | — | Canonical actor-lifecycle fx (registered globally by `re-frame.machines`). Args per `:rf.fx/spawn-args`. | v1 | — (fx-id) | 005 |
| `:rf.machine/destroy` (fx) | — | Canonical actor-destroy fx (registered globally by `re-frame.machines`). Args: an actor id. | v1 | — (fx-id) | 005 |
| `:raise` (fx) | — | Reserved fx-id inside a machine action's `:fx` (machine-internal, routed pre-commit). Args: an event vector. | v1 | — (fx-id) | 005 |
| `:final?` / `:output-key` (state-node keys) | — | `:final?` marks a leaf state as terminal — entering it auto-destroys the machine. `:output-key` (requires `:final?`) designates the child's `:data` slot reported back via the parent's `:on-done`. Capability axis `:fsm/final-states`. Per ; see [005 §Final states](005-StateMachines.md#final-states-final--on-done--output-key). | v1 | — (spec key) | 005 |
| `:on-done` (`:spawn` spec key) | — | `(fn [{:keys [data result]}] new-data)` on the parent's `:spawn` map. Fires synchronously when the spawned child enters a `:final?` state; `result` is the child's `:data` slot named by the final state's `:output-key` (or `nil`). Returns the parent's new `:data` map. Per and. | v1 | — (spec key) | 005 |
| `:child-machine` (transition-table key) | — | Declarative state-scoped child-machine binding. | post-v1 lib | — (spec key) | 005 |
| `machine->xstate-json` | Fn | `(machine->xstate-json definition)` → JSON | post-v1 lib | tooling | 005 |
| `machine->mermaid` | Fn | `(machine->mermaid definition)` → string | post-v1 lib | tooling | 005 |

Canonical descriptions (factory purity, spec keys, snapshot location, registration-time validation, etc.) in [005-StateMachines.md](005-StateMachines.md) and [Spec-Schemas](Spec-Schemas.md).

v1 transition-table grammar subset is enumerated in [005 §Capability matrix](005-StateMachines.md#capability-matrix); shape in [Spec-Schemas §`:rf/transition-table`](Spec-Schemas.md#rftransition-table).

### Standard registered subs (machines)

| Standard sub | Returns | Spec |
|---|---|---|
| `[:rf/machine <machine-id>]` | The machine's snapshot `{:state :data}` (or `nil` if not yet initialised) | 005 |

The canonical machine read is the registered `[:rf/machine machine-id]` subscription vector — see [005 §Subscribing to machines](005-StateMachines.md#subscribing-to-machines-via-sub-machine).

---

## Story / variant / workspace library (post-v1)

See [007-Stories.md](007-Stories.md).

All Story surfaces are **tooling** (a Storybook-shaped dev surface — registration, execution, introspection — not application logic). Absorbs story F-8; see [§Tiering of cross-tool surfaces](#tiering-of-cross-tool-surfaces-story-xray-pair-mcp).

| API | M/Fn | Signature | Status | Tier | Spec |
|---|---|---|---|---|---|
| `reg-story` | M | `(reg-story id metadata)` | post-v1 lib | tooling | 007 |
| `reg-variant` | M | `(reg-variant id metadata)` | post-v1 lib | tooling | 007 |
| `reg-workspace` | M | `(reg-workspace id metadata)` | post-v1 lib | tooling | 007 |
| `reg-tag` | M | `(reg-tag id metadata)` | post-v1 lib | tooling | 007 |
| `reg-decorator` | M | `(reg-decorator id metadata)` | post-v1 lib | tooling | 007 |
| `reg-story-panel` | M | `(reg-story-panel id metadata)` | post-v1 lib | tooling | 007 |
| `run` | Fn | `(run target)` / `(run target opts)` → promise/future of the unified **run-result**. `target` is a keyword (registered variant) OR a map (inline plan). The single execution verb. | post-v1 lib | tooling | 007 |
| `is` | Fn | `(is target)` / `(is target opts)` → runs `target` and reports each assertion to `clojure.test` / `cljs.test`. JVM blocks (bounded by `:timeout-ms`, default `30000`) and returns the run-result; CLJS returns the run promise. | post-v1 lib | tooling | 007 |
| `explain` | Fn | `(explain target)` / `(explain target opts)` → the plan's `:explain` map (args-validation, sub-overrides, decorators, …) without running. | post-v1 lib | tooling | 007 |
| `variants-with-tags` | Fn | `(variants-with-tags tag-set)` → seq of variant ids | post-v1 lib | tooling | 007 |
| `snapshot-identity` | Fn | `(snapshot-identity variant-id)` → `{:variant-id ... :content-hash "..."}` | post-v1 lib | tooling | 007 |
| `story-view` | Fn | `(story-view variant-id)` → hiccup | post-v1 lib | tooling | 007 |

The **public execution surface is exactly the three verbs** `run` / `is` / `explain` (each accepts a registered-variant keyword OR an inline-plan map). `run-variant` / `is-variant` / `run-plan` / `is-plan` / `watch-variant` / `reset-variant` are implementation / migration vocabulary, NOT the P1 public surface — they are not rowed here. The unified **run-result** is the single execution-record boundary; read its verdict via `result-status` / `result-passed?` (`:pass` / `:fail` / `:cannot-run` / `:error` — there is no `:passing?` boolean). Canonical execution model: [story spec 017 §Public execution API](../tools/story/spec/017-Testing-Story.md) (the `re-frame2-story` library spec, the normative home for the verbs + run-result shape).

---

## Removed / not shipped

These surfaces are **removed or renamed** — not part of the public projection and not a tier (the `deprecated` tier is reserved for surfaces still shipping while on the way out; pre-alpha carries none). This is a migration table: each row names what to use instead.

| API | What to do | Reference |
|---|---|---|
| `dispatch-with` (master) | Use `(dispatch event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-sync-with` (master) | Use `(dispatch-sync event {:fx-overrides {...}})` | MIGRATION M-4 |
| `dispatch-to` (proposed earlier) | Use `(dispatch event {:frame :todo})` | 002 |
| `subscribe-to` (proposed earlier) | Use `(subscribe query-v {:frame :todo})` | 002 |
| `frame-dispatcher` / `bound-dispatcher` / `bound-subscriber` (proposed earlier) | Use `(rf/frame-handle)` (the keystone OPERATION BUNDLE — captures the frame at creation; safe during render and from async callbacks) | 002 |
| `bound-fn` (CLJS macro) | Use `frame-bound-fn` (the macro is renamed; same `fn`-syntax + frame-capture) | 002 |
| `dispatcher` | Use `(:dispatch (rf/frame-handle))` *or* the `dispatch` injected in a `reg-view` body | 002 |
| `subscriber` | Use `(:subscribe (rf/frame-handle))` *or* the `subscribe` injected in a `reg-view` body | 002 |
| `frame-bound-fn` (fn form, 1- and 2-arity) | Renamed to `frame-bound-fn*` (the `*`-twin); `frame-bound-fn` is now the macro | 002 |
| `current-frame` | Renamed to `current-frame-id` (returns a frame-id keyword) | 002 |
| `get-frame-db` | Renamed to `app-db-value` (returns the app-db VALUE, a plain map) | 002 |
| `enable-performance-api-tracing!` (proposed earlier) | Performance-API instrumentation is gated on the compile-time `re-frame.performance/enabled?` `goog-define`, not a runtime toggle (see [009 §Performance instrumentation](009-Instrumentation.md#performance-instrumentation)) | 009 |
| `add-trace-listener` / `remove-trace-listener` (proposed earlier) | Use `register-listener!` / `unregister-listener!` | 009 |
| `register-trace-listener` / `unregister-trace-listener` (no-bang, proposed earlier) | Renamed to `register-listener!` / `unregister-listener!` (bang form matches the side-effecting nature of listener registration) | 009 |
| Bare `[:my-view "args"]` keyword-tagged hiccup | Use the Var form `[my-view "args"]` (canonical) or `[(rf/view :my-view) "args"]` for late-binding by id | 004 |
| `h` macro (proposed earlier) | Removed. Use the Var form `[my-view "args"]` or `[(rf/view :my-view) "args"]` | 004 |
| `reg-global-interceptor` | Use `reg-frame :interceptors` (frame-level is the canonical "global within this frame"). For cross-frame observation use `register-listener!`. | MIGRATION M-17 |
| `clear-global-interceptor` | No replacement needed — re-register `reg-frame` with an updated `:interceptors` vector (absent-key semantics clear it). | MIGRATION M-17 |
| `reg-sub-raw` | Use `reg-sub` (app-db reads), Pattern-AsyncEffect (non-app-db sources), state machines (lifecycle), or the [006](006-ReactiveSubstrate.md) adapter contract (bridging external reactivity). | MIGRATION M-18 |
| `reg-event-db` | Use `reg-event` (EP-0018, no alias) — destructure `:db` from the coeffects map and wrap the return in `{:db …}`: `(reg-event id (fn [{:keys [db]} ev] {:db BODY}))`. A stale call raises the always-on hard error `:rf.error/reg-event-db-removed` naming `reg-event`. The `^:no-doc` facade throwing stub carries no API-manifest row. | [001 §The retired event-registration names](001-Registration.md#the-retired-event-registration-names) |
| `reg-event-fx` | Use `reg-event` (EP-0018, no alias) — `reg-event` IS the identical shape under the bare name (coeffects in, effects out); just rename the call. A stale call raises `:rf.error/reg-event-fx-removed` naming `reg-event`. | [001 §The retired event-registration names](001-Registration.md#the-retired-event-registration-names) |
| `reg-event-ctx` | Demoted to a framework-internal primitive (EP-0018). Express application full-context work as a **registered interceptor** (`reg-interceptor` with `:before`/`:after`, referenced by id from a `reg-event` chain — EP-0022). A stale public call raises `:rf.error/reg-event-ctx-removed`. | [001 §The retired event-registration names](001-Registration.md#the-retired-event-registration-names) |
| `get-coeffect` / `get-effect` | Removed from the façade (no audience post-EP-0017/EP-0022; carry no manifest row). Inside a `reg-interceptor` `:before`/`:after` fn read the context map directly: `(get-in ctx [:coeffects k])` / `(get-in ctx [:effects k])`. The owning-namespace `re-frame.interceptor/get-coeffect` / `get-effect` fns remain framework-internal. | 001, 002 |
| `assoc-coeffect` / `assoc-effect` | Removed from the façade (zero callers; carry no manifest row). Inside a `reg-interceptor` `:before`/`:after` fn write the context map directly: `(assoc-in ctx [:coeffects k] v)` / `(assoc-in ctx [:effects k] v)`. The owning-namespace `re-frame.interceptor/assoc-coeffect` / `assoc-effect` fns remain framework-internal. | 001, 002 |
| `re-frame.alpha/reg` | The shipped per-kind registrars: `reg-event` / `reg-sub` / `reg-fx` / `reg-cofx` / `reg-flow`. (The v1 event trio `reg-event-db` / `reg-event-fx` / `reg-event-ctx` is **not** a v2 target — those are removed/withdrawn throwing stubs and migration inputs only, see the rows above and EP-0018; `reg-event` is the single event-registration form.) | MIGRATION M-23 |
| `re-frame.alpha/sub` | Vector-form `(rf/subscribe [::id arg])`. | MIGRATION M-23 |
| `re-frame.alpha/reg-sub-lifecycle` and built-in lifecycle policies (`:safe`, `:no-cache`, `:reactive`, `:forever`) | Sub-cache uses a single algorithm — synchronous ref-counting (dispose on derefer-count → 0), per [Spec 006 §Reference counting and disposal](006-ReactiveSubstrate.md#reference-counting-and-disposal). For specific edge cases file a follow-up bead. | MIGRATION M-23 |

---

## Cross-references

- [000-Vision.md](000-Vision.md) — principles and design decisions
- [002-Frames.md](002-Frames.md) — frames, dispatch envelope, drain semantics, overrides, machine foundations
- [004-Views.md](004-Views.md) — view registration, hiccup forms
- [005-StateMachines.md](005-StateMachines.md) — machine library design (post-v1)
- [007-Stories.md](007-Stories.md) — story/variant/workspace library design (post-v1)
- [008-Testing.md](008-Testing.md) — testing API and patterns
- [009-Instrumentation.md](009-Instrumentation.md) — trace event stream, listeners, error contract
- [010-Schemas.md](010-Schemas.md) — Malli schemas
- [014-HTTPRequests.md](014-HTTPRequests.md) — `:rf.http/managed` request fx (optional capability)
- [MIGRATION.md](../migration/from-re-frame-v1/README.md) — AI-driven migration spec
