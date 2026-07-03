# Story — Testing P1 (vocabulary, plans, runners, evidence)

> The normative P1 contract for Story-as-test and the variant-plan
> execution model. Locks the public authoring vocabulary
> (`:setup` / `:script` / `:world` / `:expect` / `:evidence`), the
> three execution verbs (`run` / `is` / `explain`), the `:cannot-run`
> third result state, composition + conflict resolution, the schema
> floor, the runner-capability model, and the epoch-tape evidence
> projection. Promoted from the NewTestStory finding (2026-05-29) into
> committed spec under EPIC rf2-5x1wt.
>
> **Ownership boundary.** [`spec/008-Testing.md`](../../../spec/008-Testing.md)
> owns the general testing substrate (frame fixtures, `dispatch-sync`,
> effect overrides, headless sub computation, pure machine transitions,
> hiccup/view testing, `clojure.test` / `cljs.test` integration, and the
> non-Story-specific evidence tools). Story owns variant/plan
> registration, Story-specific composition, the Story UI test mode and
> workshop surfaces, runner integration, and curation/promotion. Story
> MUST build on the testing substrate and MUST NOT introduce a second
> runtime model.

## Normative posture

This document uses MUST / SHOULD / MAY in the usual sense (MUST required
for P1; SHOULD expected unless a concrete implementation constraint says
otherwise; MAY optional). Pre-alpha posture applies: the target API is
built correctly rather than preserving legacy spellings through
compatibility shims. The `:events` → `:setup` / `:play-script` →
`:script` rename (§Public vocabulary) is a **clean pre-alpha rename, not
a long-lived compatibility layer.**

Where a rule changes shipping behaviour this document says so explicitly
(§Shipping vs target). A reader must never assume a hook that does not
yet exist (e.g. browser-tier pixel diffing).

## Shipping vs target (grounding delta)

The shipping Story implementation is close to the target; this table is
the single inventory of what SHIPS today versus what is NET-NEW, so the
code migration is not confused with greenfield design.

| Surface | Status | Note |
|---|---|---|
| `reg-story` / `reg-variant`, data-shaped bodies | SHIPS | `tools/story/src/re_frame/story.cljc`, `schemas.cljc` (functions only behind ids). |
| `:rf.assert/*` family (7 dispatched ids) | SHIPS | The seven **dispatched** `reg-event` ids per [`004-Assertions.md`](004-Assertions.md); the canonical set is **eight** once the tape-evaluated `:rf.assert/schema-error` (next row, §Schema rule) is counted. |
| `:assert-db` / `:assert-dom` script steps | SHIPS | Folded into the one assertion atom (§Assertions — one atom, two positions); do not drop them. |
| bare event-vector script/setup shorthand | SHIPS (`play/runner.cljc` `coerce-script`) | P1 removes this authoring ambiguity; every setup/script step normalizes to a tagged step (§Script step grammar). |
| `:events` / `:play-script` / `:plays` | SHIPS | Renamed to `:setup` / `:script` / named-scripts (§Public vocabulary). |
| `:extends` | SHIPS as **straight merge** (child replaces) | The per-field plan-time merge (§Merge rules) is a **replacement** of this, not a refinement; a test MUST pin parent+child `:setup` *append*. |
| `:decorators` incl. `[:rf.story/force-fx-stub …]` | SHIPS | The **real** fx-override authoring surface; `:fx-overrides` is the derived frame slot (§The effect-override surface). |
| run-result key | SHIPS as `:lifecycle` | This document's top-level `:status` is **NET-NEW** (§Run result); the runtime's record-result map uses `:lifecycle` today. |
| `:skipped?` on no-DOM assertion records | SHIPS | This IS the `:cannot-run` case at assertion granularity — reconcile, do not duplicate (§`:cannot-run`). |
| `:rf.assert/schema-error` + schema-fail-the-run | NET-NEW | Today schema violations are trace events feeding a UI panel with **no fail mechanism** — must be wired (§Schema rule). |
| reactive recompute / render-count probe | SHIPS as a PROJECTION (rf2-5x1wt.30) | Spec 009 already emits one `:rf.sub/run` per true sub recompute and one `:rf.view/rendered` per view render, both retained in the epoch tape. The probe is `re-frame.story.play.evidence/reactive-counts` — a pure projection over those rows, NOT a new core seam — surfaced as the `:reactive-counts` run-result slot and advertised by the `:cljs-reactive` runner. |
| `:sub-overrides` for view-state variants | NET-NEW | Explicit lower-fidelity rendering affordance (§View-state subscription overrides); not proof of real subscription logic. |
| view arg schema consumption | SHIPS partially / P1 hardens | Controls already derive from view schemas; P1 copies the view props schema into the plan and validates `:effective-args` before render (§View arg schemas). |
| `variant-plan`, `explain`, `reg-fragment` / `reg-check`, runner abstraction, inline plans, run-artifacts, `canonicalize` / fingerprinting, narrative projection, `render-variant` | NET-NEW | This document. |

### Shared primitive lock

Four names are shared across the concept, spec, and implementation
surfaces; their meaning is locked:

| Primitive | Locked meaning |
|---|---|
| `settled-boundary` | The author-facing settlement contract for `[:dispatch event-vector]`. In `:headless` it is the existing `dispatch-sync` run-to-fixed-point drain, renamed/projected rather than reimplemented; richer runners add adapter-supplied reactive/DOM/React flushes with a declared bound. Runners return `:cannot-run` when they cannot satisfy the required boundary. |
| `canonicalize` | The single canonical projection operation for determinism, semantic diff, snapshot identity, `:plan-hash`, `:run-hash`, golden-slice comparison, and inline-plan-to-registered-variant equivalence. It lives in a fingerprinting namespace, not `re-frame.story.canonical` (the canonical-vocabulary installer), and folds the existing `re-frame.story.identity` `canonical-form` / `content-hash` / `snapshot-tuple` path into one primitive. |
| `golden slice` | A curated regression artifact — the P1.5 surface, now **landed**. P1 shipped canonicalization and the narrative projection first; on that proven hash/projection corpus, a curated run may be frozen as a `:rf.test/golden` slice (the `canonicalize`d behavioural surface) and a later run asserted to `canonicalize` `=` to it. See [§Golden slices](#golden-slices). |
| `three verbs` | The public execution surface is `(story/run target opts)`, `(story/is target opts)`, and `(story/explain target)`, where a keyword target means a registered variant and a map target means an inline plan. `run-variant` / `is-variant` / `run-plan` / `is-plan` are implementation/migration vocabulary, not the P1 public surface. |

## Public vocabulary

P1 public authoring vocabulary is:

- `:setup` for preconditions.
- `:script` for ordered behaviour under test.
- `:checks` for reusable/inheritable assertion packs.
- `:assertions` for own-only terminal assertions.
- `:compose` for explicit fragment/check composition.
- `:extends` for state/config specialization.

Current implementation terms map as follows:

| Current implementation term | P1 target term |
|---|---|
| `:events` | `:setup` |
| `:play-script` | `:script` |
| `:plays` | named scripts in the normalized plan |

The target public API uses `:setup` and `:script`. Because the project
is pre-alpha, this is a clean rename, not a long-lived compatibility
layer. Implementation commits MAY temporarily normalize current terms
while the tree is migrated, but the accepted authoring surface after M0
is the target vocabulary. Named `:plays` are preserved as named scripts
in the normalized plan; they are not dropped in P1. The legacy `:play`
event-vector slot was already removed (rf2-0wrud); P1 does not
reintroduce it.

The variant schema (`re-frame.story.schemas/Variant`) accepts both
spellings and enforces that an author picks ONE setup surface
(`:setup` xor `:events`) and ONE play surface
(`:script` xor `:play-script` xor `:plays`). The registrar's
`schemas/lower-public-vocabulary` step folds `:setup` → `:events` and
`:script` → `:play-script` into the stored body so every shipping slot
reader sees the shipping spelling while the tree migrates. The
**`run-variant` lifecycle runtime is now routed through the variant-plan
compiler** (rf2-5x1wt.22): `re-frame.story.runtime/prepare-context`
compiles the normalized plan once, phase 2 dispatches the plan's
`[:world :setup]` (the parent story's id-grammar `:events` prepended),
and phase 4 drives the plan's `[:world :scripts]` (the named plays,
`:plays` preserved). The compiler normalizes both spellings, so the
lifecycle no longer depends on the lowering. The lowering REMAINS for the
remaining shipping-slot readers that are NOT yet plan-routed — snapshot
identity (`re-frame.story.identity`), the workspace/canvas readers, and
the recorder — and for the play-runner's `variant-body->plays` (still
consulted by the live-canvas auto-run + step-debugger paths). It is fully
removed once those readers also consume the plan. A variant authored with
`:setup` / `:script` runs identically to one authored with the shipping
slots, and through the plan-routed lifecycle a composed `:compose`
fragment's `:setup` is now executed in phase 2.

### Four-bucket authoring model

The normalized plan is organized around four buckets:

| Bucket | Authored? | Purpose |
|---|---|---|
| `:world` | yes | Context/harness: frame, setup, args, view arg schema, route, render fixtures, app-db seed, network stubs, fx/interceptor overrides, platforms, and workshop context. |
| `:script` | yes | Ordered behaviour under test: dispatches, waits, interactions, checkpoints, and captions. |
| `:expect` | yes | Judgement: checks and assertions. Checks are the inheritable expectation form; ordinary assertions are local. |
| `:evidence` | no | Derived proof from the epoch tape and runner output: traces, schema failures, effects, renders, sub-runs, narrative, run artifact, hashes, and semantic diffs. |

Variant bodies MAY keep ergonomic top-level keys such as `:setup`,
`:args`, `:checks`, and `:assertions`. The plan compiler MUST lower them
into `:world` / `:script` / `:expect` and MUST derive `:evidence` from
the run, never from author input.

## Artifacts

### Story variant

A Story variant is the curated author-facing artifact. It is registered,
browsable, shareable, documentable, and suitable as a regression. It is
the curated artifact for **both** testing and storytelling. Variant
bodies MUST remain data-shaped; functions live behind registered ids.

### Variant plan

A variant plan is the normalized executable artifact. Every registered
variant and every inline plan MUST be normalized before execution. The
plan is a **superset** that carries the workshop surface as well as the
test surface, so one compiler serves both render and test.

Required normalized plan shape:

```clojure
{:plan/id    optional-keyword
 :variant/id optional-keyword
 :story/id   optional-keyword
 :source-chain [...]
 :world {:frame {:preset optional-keyword
                 :initial-events [[event-vector] ...]
                 :fx-overrides {fx-id override-ref-or-data}
                 :interceptor-overrides {interceptor-id override-ref}}
         :args {...}
         :argtypes {...}                  ; control metadata; schema-derived where possible
         :view-args-schema optional-schema ; explicit view input contract copied from view :rf/props
         :effective-args {...}            ; post-control-override args; drives render-variant
         :setup [setup-step ...]
         :db-seed optional-db-or-patch    ; schema-checked direct state seed (§Setup — Direct app-db seeding)
         :render {:sub-overrides {query-vector data}} ; view-state affordance
         :network {[method url] {:reply {:ok data}}}  ; or {:reply {:failure data}}
         :fidelity #{:real-setup :db-seed :sub-overrides}
         :decorators [...]                ; view wrapping
         :modes {...} :viewports [...] :backgrounds [...] :variants-grid {...} :substrates [...]
         :platforms #{:client}}
 :script [script-step ...]
 :expect {:checks [check-id ...]
          :assertions [assertion-vector ...]}
 :required-runner #{capability-token ...} ; capability tokens, not a tier scalar
 :evidence {:source :epoch-tape           ; derived at run time
            :narrative optional}
 :tags #{keyword ...}
 :explain {...}}
```

`:world :platforms` defaults to `#{:client}`. SSR/hydration work MAY
extend this with `#{:server :client}` later without changing the
artifact model. For author ergonomics, registered variants MAY continue
to author `:setup`, `:args`, `:argtypes`, `:sub-overrides`, `:checks`,
and `:assertions` at the top level; the plan compiler lowers them into
this shape and `explain` shows both source and normalized locations.

### Compiler API and normalization contract

The compiler is a **pure data → data** function (so it is JVM-runnable
and host-free) exposed through three entry points:

```clojure
(story/variant-plan target)        ; -> normalized plan
(story/variant-plan target opts)
(story/explain       target)       ; -> the plan's :explain map
(story/explain       target opts)
```

`target` is a keyword (a registered variant id, resolved through the
Story side-table) or a map (an inline plan body, with an optional
`:variant/id`). `opts` MAY carry `:lookup` — a `(variant-id) →
raw-body` fn or a `{variant-id → raw-body}` map — used to resolve
`:extends` parents and the keyword target; it defaults to the
side-table. Because `:lookup` accepts raw bodies, the compiler owns
parent-chain resolution rather than depending on registration-time
merge.

**Normalization (author spelling → normalized slot):**

| Author key | Normalized slot |
|---|---|
| `:setup` / `:events` | `[:world :setup]` (both spellings; `:setup` is the target) |
| `:script` / `:play-script` / `:plays` | `:script` (the primary play); the full named-play set is preserved under `[:world :scripts]` so `:plays` is not dropped |
| `:checks` | `[:expect :checks]` |
| `:assertions` | `[:expect :assertions]` |
| `:args` / `:argtypes` | `[:world :args]` / `[:world :argtypes]` |
| (derived) view props schema | `[:world :view-args-schema]` — copied from the `:component` view's metadata (§View arg schemas) |
| (derived) resolved args | `[:world :effective-args]` — the args feeding the view; validated against the view-args schema before render |
| `:sub-overrides` | `[:world :render :sub-overrides]` |
| `:network` / `:fx-overrides` | `[:world :network]` / `[:world :frame :fx-overrides]` |
| platforms / tags / workshop slots (`:decorators`, `:modes`, `:viewport`, …) | preserved under `:world` (and top-level `:tags`) |

Script steps lower through the shipping coercion (bare event-vector
shorthand lifts to `[:dispatch …]`; the `:dispatch` / `:dispatch-sync`
/ `:wait` / `:assert-*` tag grammar is preserved verbatim), so the
normalized `:script` matches what the runner executes.

**Parent chain (`:extends`).** The compiler resolves the chain root to
child and applies the §`:extends` principle — context flows down,
verdict is local:

- `:setup` **appends** root → child (a common silent-regression site);
- world context (`:args` / `:argtypes` deep-merge; frame/render/network/
  workshop slots) and `:checks` **inherit** root → child;
- ordinary terminal `:assertions` and `:script` are **child-only**;
- `:tags` union.

**Args and `[:arg key]`.** `:args` resolve through the deep-merge
precedence chain (later/closer-to-child wins). Every `[:arg key]`
placeholder in setup, script, and sub-overrides is substituted before
the plan is returned; a placeholder referencing an undeclared arg
**fails** plan construction with `:rf.error/story-missing-arg`. `explain`
records each substitution as `{:key … :value …}`.

**Cycle/unknown detection.** A missing keyword target fails with
`:rf.error/story-unknown-variant`; an unregistered `:extends` parent
with `:rf.error/story-extends-unknown`; a repeated id (or a chain past
the depth cap) with `:rf.error/story-extends-cycle` /
`:rf.error/story-extends-chain-too-long`.

**Required runner (initial).** The compiler computes an initial
`:required-runner` capability **set** by unioning the tokens each setup
step, script step, and terminal assertion declares (app-db work needs no
token and resolves to `:headless`; DOM steps/assertions add `:dom`,
visual adds `:pixels`, etc.). This is a coarse first cut; the
per-assertion capability registry (a later bead) supersedes the static
map without reshaping the plan.

**Explain data.** `(story/explain target)` returns the plan's `:explain`
map: source chain, parent chain, field-level merge decisions, resolved
args + substitutions, final setup order and script order, checks and
terminal assertions, required runner, platforms, tags, and source
coords. Strict-conflict resolution and composed-fragment/check
explanation (§Conflict resolution) are filled in by the composition
bead; the foundation emits an empty `:compose` slot.

### Inline plan

An inline plan is an executable plan map that is not registered as a
Story variant. Inline plans MUST NOT appear in Story navigation. They
MAY compose registered fragments and checks. They MUST return the same
run-result shape as registered variants.

**Execution (rf2-5x1wt.20).** All three verbs accept a map target as well
as a keyword: `(story/run inline-plan opts)` / `(story/is inline-plan
opts)` / `(story/explain inline-plan)`. `variant-plan` / `explain` compile
a map directly (the compiler accepts an unregistered map — rf2-5x1wt.24).
The RUN path (`story/run` / `story/is` for a map target) is the
registry-free twin of the registered-variant lifecycle, and reuses it
rather than forking it:

1. the plan is compiled ONCE (`variant-plan` accepts the map; `opts` MAY
   thread `:fragment-lookup` / `:check-lookup` / `:lookup` so the plan can
   compose REGISTERED fragments + checks, or run host-free);
2. an ANONYMOUS frame id is minted in the reserved `:rf.story.inline/*`
   namespace ([Conventions.md §`:rf.story.*` framework carve-out](Conventions.md#the-rfstory-framework-carve-out)
   owns the closed member set) — never a registered variant id, so the Story side-table
   queries (variant ids, variants-by-story) can't surface it and a
   concurrent registered run can't collide; the frame is stamped
   `:rf/inline? true`. That stamp is the discriminator the live-frame
   navigation enumeration (`variant-frames` / `variant-frame?`) consults:
   an inline frame carries `:rf/story?` (it IS Story-managed) yet is
   EXCLUDED from the navigable-variant enumeration because it is also
   stamped `:rf/inline?`. The MUST-NOT is thus enforced for the whole
   in-flight window between allocation and teardown — not merely by the
   side-table absence (which alone would leave the live frame transiently
   navigable while a run is in flight);
3. the decorator stack is resolved from the plan's already-merged
   `[:world :decorators]` refs (the variant/story side-table is NOT read);
4. the SAME phase pipeline (loaders → setup → script) drives the run, all
   sourced from the plan — `[:world :setup]`, `[:world :scripts]`,
   `[:world :frame :fx-overrides]`, `[:expect :checks]` / `[:expect
   :assertions]`;
5. the one unified run-result is assembled from the plan + the frame's
   accumulated assertions + the epoch tape (the same `run-result`
   boundary), so an inline plan and a registered variant describing the
   same behaviour produce equivalent app-db + assertion records after
   `canonicalize` (the metamorphic relation);
6. the anonymous frame is torn down when the run resolves — on BOTH the
   success path AND the failure path. A run that fails AFTER the frame is
   allocated (a `:db-seed` schema violation, a throw in loaders / setup /
   the script) runs the SAME teardown a successful run does: the plan's
   `[:world :loaders-teardown]` events then the `[:world :decorators]`
   frame-setup `:teardown` events (§Loader teardown contract, 002-Runtime),
   using the decorator stack that was set up at the point of failure. The
   success and failure paths converge on identical cleanup — so a resource
   an inline loader or frame-setup decorator opened is never leaked on the
   failure path. Nothing is registered.

A plan-construction failure for a map target (an unknown composed
fragment, a missing `[:arg …]`, a misplaced `[:assert …]` in setup, …)
surfaces as the SAME structured `:rf.error/story-*` error result a
registered variant's malformed plan produces — the frame is never
allocated, so there is nothing to tear down.

### Run artifact

A run artifact is the low-level evidence emitted by generated tests,
failed runs, replay, determinism checks, or tool/agent exploration.

Required P1 shape:

```clojure
{:artifact/kind :rf.test/run-artifact
 :seed optional
 :event-program [...]
 :fx-decisions {fx-id override}
 :network {[method url] {:reply …}}  ; optional — per-route HTTP stubs, re-installed on replay
 :epoch-tape [...]
 :trace [...]
 :result run-result
 :shrink-path optional
 :created-at instant-or-string
 :source optional}
```

Run artifacts are not Story variants. A run artifact MAY be promoted into
a variant plan or curated Story variant (§Promotion).

### The effect-override surface

Authors override effects through a **first-class `:fx-overrides`** slot
on the variant/fragment body, normalized to
`[:world :frame :fx-overrides]`. The shipping
`[:rf.story/force-fx-stub …]` decorator becomes sugar that the plan
compiler **lowers into `:fx-overrides`**. `:decorators` is reserved for
view wrapping (theme/provider/chrome). This makes the conflict model
(§Merge rules) target the surface authors actually type.

### The interceptor-override surface

Authors override interceptors through a **first-class
`:interceptor-overrides`** map on the variant/fragment body, normalized to
`[:world :frame :interceptor-overrides]`. It is the interceptor analog of
`:fx-overrides`: a `{interceptor-id → override}` map keyed by **the exact
interceptor id**, resolved per-key under the strict-conflict ladder
(§Merge rules / §Conflict resolution).

Per EP-0022 (registered interceptors), re-frame2 interceptor chains carry
**serializable references only** — an event/frame chain names interceptors
by id (`{:interceptors [:rf/redact-interceptor :app/unwrap]}`), and an
inline interceptor value in a chain is rejected loud at registration with
`:rf.error/inline-interceptor-removed`. An interceptor is authored once
with `reg-interceptor` and referenced by its id everywhere; `->interceptor`
is the internal constructor, not an authoring surface. Story inherits this
model directly:

- a story plan never carries an **inline interceptor chain** — there is no
  `:interceptors [...]` plan slot. The only story interceptor surface is
  `:interceptor-overrides`, keyed by interceptor id;
- an `:interceptor-overrides` key matches the registered interceptor by
  **exact id** (the same id the chain references). The override value is
  the swapped descriptor/ref the runner installs under that id for the
  duration of the variant;
- because keys are ids (not values), the strict-conflict composition
  (§Variant-owned-wins) resolves per id deterministically — two fragments
  overriding the same interceptor id while the variant is silent is a hard
  conflict, exactly as for `:fx-overrides`.

### The network surface

Managed HTTP stubbing is first-class world input:

```clojure
{:network {[:get "/api/cart"]      {:reply {:ok {:items []}}}
           [:post "/api/checkout"] {:reply {:failure {:kind :rf.http/http-4xx
                                                       :status 409}}}}}
```

The plan compiler MUST lower `:network` to the existing managed-request
stub machinery. `:network` is not a replacement for generic
`:fx-overrides`; it is the higher-level affordance for `:rf.http/managed`
because route-level replies, mixed success/failure, and flaky/retry cases
are otherwise flattened into one coarse fx replacement.

## Registration API

### Variants

```clojure
(story/reg-variant :story.checkout/form/submitted
  {:setup      [[:dispatch [:cart/add {:sku "A"}]]]
   :script     [[:dispatch [:checkout/submit]]]
   :checks     [:check/no-runtime-errors]
   :assertions [[:rf.assert/path-equals [:checkout :state] :submitted]]
   :tags       #{:test}})
```

The plan compiler MUST normalize legacy/source forms (`:events`,
`:play-script`) into `:setup` / `:script` dispatch steps during
migration.

### Fragments

Fragments are reusable setup/script/frame/args pieces.

```clojure
(story/reg-fragment :fragment.checkout/cart-with-sku
  {:args  {:sku "A"}
   :setup [[:dispatch [:cart/add {:sku [:arg :sku]}]]]})
```

P1 fragments MUST be flat. A fragment MUST NOT compose another fragment.
Cycles MUST be impossible in P1.

Shared behaviour prefixes are expressed as explicit script fragments
(never inherited through `:extends`, §`:extends`):

```clojure
(story/reg-fragment :fragment.checkout/ready-to-submit
  {:setup  [[:dispatch [:cart/add {:sku "A"}]]]
   :script [[:dispatch [:checkout/open]]
            [:dispatch [:checkout/type-address {:postcode "2000"}]]]})

(story/reg-variant :story.checkout/submits
  {:compose    [:fragment.checkout/ready-to-submit :fragment.checkout/http-success]
   :script     [[:dispatch [:checkout/submit]]]
   :assertions [[:rf.assert/path-equals [:checkout :state] :submitted]]})
```

### Checks

Checks are reusable assertion packs.

```clojure
(story/reg-check :check/no-runtime-errors
  {:assertions [[:rf.assert/no-warnings]]})
```

Checks SHOULD be used for invariants and inherited expectations.
(`:rf.assert/no-schema-errors` is intentionally absent — schema-clean is
a knob-free runner floor, §Schema rule.)

## Args, controls, and `render-variant`

`:args` are Story controls/parameters and fragment/template inputs. They
are not hidden app-db mutations. When args feed the registered view,
`:effective-args` MUST satisfy the view arg schema if one exists.
`:argtypes` carry control metadata and SHOULD be schema-derived from the
view arg schema where possible, so controls appear without
hand-authoring.

Setup and script steps MAY consume args through data placeholders:

```clojure
{:args   {:sku "A" :postcode "2000"}
 :setup  [[:dispatch [:cart/add {:sku [:arg :sku]}]]]
 :script [[:dispatch [:checkout/type-address {:postcode [:arg :postcode]}]]]}
```

The plan compiler MUST resolve `[:arg key]` placeholders before
execution. If a placeholder references a missing arg, plan construction
MUST fail. `explain` MUST show arg substitutions.

**Run opts feed the SAME effective args the result reports.** The
effective args are the full precedence chain
`global < story < active-modes < variant < cell-overrides`
(`re-frame.story.args/resolve-args`). The runner threads the per-run
layers — the UI/test surfaces' `:active-modes` and `:cell-overrides` —
INTO plan compilation (the `:run-args` compiler input), so the substituted
`[:arg key]` placeholders in **setup, script, db-seed, network replies, and
sub-overrides**, the `[:world :args]` / `[:world :effective-args]` slots,
AND the `:plan-hash` all use the SAME effective args the run-result reports
under `:effective-args`. A cell override or an active mode therefore
executes the EXACT scenario the result claims — the runner never substitutes
the static variant args while reporting the override/mode-aware ones
(rf2-2cpoo). The runtime executes `[:world :scripts]` (the normalized plays),
so those plays carry the resolved placeholders, not the raw `[:arg …]` forms.
(`render-variant` keeps its own post-compile path: it layers
`:control-overrides` on top of the plan-time effective args and re-resolves
sub-overrides, because rendering does not execute setup/script/db-seed.)

The canvas/controls/docs **decorator-resolution** front door
(`decorators/resolve-decorators`) likewise threads the per-run layers into the
plan it recompiles to read `[:world :decorators]` (rf2-eyrpr). That recompile
substitutes every `[:arg key]` in the variant body, so a key resolvable ONLY
through a mode / cell / global / story layer (never the variant chain) must be
supplied via the same `{:active-modes :cell-overrides}` opts — otherwise the
decorator recompile fails `:rf.error/story-missing-arg` even though the runner
compile (with run opts) substitutes it cleanly. The common case (every
`[:arg]` key declared on the variant or its `:extends` chain) is unaffected;
the threading is purely additive. The hot-reload fingerprint poll
(`resolution-fingerprints`) threads the same opts for the same reason — the
fingerprints are body-derived and run-layer-invariant, the opts only let the
ref-collection compile succeed.

`:effective-args` (the args after control-panel overrides) is a
first-class plan field, and `(story/render-variant target opts)` renders
the workshop view from the **same plan** the runner consumes. The live
controls/visual-narrative experience and the test runner drive one plan,
not two paths.

P1 render API:

```clojure
(story/render-variant target opts)
;; -> {:status :rendered | :invalid-args | :cannot-run | :error
;;     :plan normalized-plan
;;     :plan-hash string
;;     :frame frame-id
;;     :effective-args {...}
;;     :validation optional-validation-result
;;     :rendered host-render-result}
```

`render-variant` MUST normalize the same target shapes as `story/run`: a
keyword target resolves a registered variant and a map target is an
inline plan. By default it prepares `:world` for rendering and renders
the active view. It MUST NOT execute `:script` or terminal `:expect`
unless a future option explicitly asks for a test run.

When a host renders the active view, it MUST apply the variant's
view-wrapping `:hiccup` decorators (the compiled plan's
`[:world :decorators]`, threaded onto the render inputs) — the SAME
decorate-and-render seam the live canvas uses
(`re-frame.story.ui.multi-substrate/render-decorated-view`, which resolves
the refs and wraps via `safe-decorated-view`). A host that paints the bare
view would diverge from the live canvas for any decorated variant
(theme / provider / chrome dropped). With no host installed (the bare JVM),
`render-variant` returns `:cannot-run` — never a silent empty render.

### View arg schemas

A registered view MAY expose a schema for its explicit args/props through
view registration metadata. In the framework specs this is `:rf/props`;
`:rf/args` is macro-captured argument-symbol introspection, not the
validation schema. The plan compiler MUST copy the props schema into
`:view-args-schema` when available, and Story MUST use it to:

- validate `:effective-args` before render;
- derive `:argtypes` / controls when explicit argtypes are absent;
- document the view's explicit input contract;
- fail plan construction or render preparation when required args are
  missing or malformed.

This schema applies only to explicit view inputs. It does not validate
values returned from subscriptions (those remain subscription output
schemas) and it does not seed app-db.

**Metadata key (M0-confirmed).** `reg-view` stamps a view's symbol
metadata (minus `:rf/id`) onto its `:view` registrar slot, so the props
schema rides on that slot. The compiler resolves it **first match wins**
over `[:rf/props :schema]`: `:rf/props` (the canonical props-schema key
per [Spec-Schemas §`:rf/registration-metadata`](../../../spec/Spec-Schemas.md#rfregistration-metadata),
primary) → `:schema` (the post-M-54 `reg-*` metadata key, the alternative
location). `:rf/props` wins outright when present — there is NO
composition (a view's only schema surface is its props). `:spec` is NOT a
resolution key: it is dead post-M-54 (the framework reads `:schema` only
on `reg-*` metadata, with no back-compat shim — see
[MIGRATION §M-54](../../../migration/from-re-frame-v1/README.md#m-54-schema-vocabulary-unification--spec--schema)).

Every Story consumer reads this schema through the ONE shared resolver
`re-frame.story.view-args/compiled-view-args-schema`, which compiles the
variant-plan and returns the schema the compiler wrote to
`[:world :view-args-schema]` — the single source of truth (the compiled-
plan invariant). No consumer reads the bare registrar body. So the
controls-panel derivation (`re-frame.story.ui.controls`) and the schema-
validation panel (`re-frame.story.ui.schema-validation`) read the SAME
compiled slot, and a view that declares a props schema gets controls AND
validation from one source — even when its `:component` is
`:extends`-inherited or `:compose`-d in.

**Plan slots.** When the variant's resolved `:component` view carries a
schema, the compiler writes:

| Plan slot | Meaning |
|---|---|
| `[:world :view-args-schema]` | the view's explicit-input schema, copied verbatim |
| `[:world :effective-args]` | the args that feed the view — at plan time the resolved arg-map; the render path layers control-panel overrides on top |
| `[:explain :view-args-schema]` / `[:explain :effective-args]` | the same, surfaced in `explain` (and downstream docs) |
| `[:explain :view-args-validation]` | `{:status :ok :missing [] :malformed []}` for a passing plan (an invalid one throws — see below) |

`[:world :effective-args]` is recorded even when no view schema is on
file (it is the resolved arg-map); the `:view-args-schema` /
`:view-args-validation` slots are present only when a schema exists.

**Validation, two tiers (both pure / JVM-testable).**

- **Required-key presence (host-free floor).** For a top-level
  `[:map …]` schema, every entry NOT marked `{:optional true}` whose key
  is absent from `:effective-args` is a missing-required violation. This
  needs no Malli runtime, so it runs under `clojure -M:test`.
- **Malformed-value (validator-driven).** When the caller threads a
  `{:validate :explain}` validator pair (e.g. the Malli late-bind hook
  the renderer already uses), each present entry's value is validated
  against its entry schema; a failure carries the validator's
  explanation. With no validator, malformed-value checking soft-passes
  (matching `re-frame.story.ui.schema-validation/args-violations`).

A missing-required or malformed view input **FAILS plan construction**
with `:rf.error/story-view-args-invalid`, whose ex-data carries the
failing arg key(s), the Malli schema `:path` (`[k]` for a top-level map
entry), the `:effective-args`, and the source `:variant/id`.
`variant-plan` opts `:view-lookup` (a `(view-id) → view-meta` fn or map,
defaulting to the framework `:view` registrar) and `:validator-fns`
thread the view-metadata source and the malformed-value validator, so
the compiler stays a pure data → data fn for host-free tests.

**Sharp boundary.** View-arg schemas validate the **explicit view
inputs** only (the `:args` that feed the rendered view). They are a
different contract from subscription-output schemas, which validate
values supplied by subscriptions and `:sub-overrides` (§View-state
subscription overrides). The two MUST NOT be conflated: `:sub-overrides`
lower to `[:world :render :sub-overrides]` and are never checked against
the `:view-args-schema`.

### View-state subscription overrides

Story MUST support a deliberate lower-fidelity path for
view-state/design variants:

```clojure
{:args {:message "Invalid password"}
 :sub-overrides {[:login/state]    :error
                 [:login/error]    [:arg :message]
                 [:login/attempts] 1}}
```

Rules:

- `:sub-overrides` keys are exact subscription query vectors after arg
  substitution.
- values MUST be data-shaped and MUST validate against the subscription
  output schema when one exists.
- overrides are visible in `explain`, run results, and docs output.
- plans using overrides MUST carry `:fidelity` including
  `:sub-overrides`.
- overrides are for rendering/view-state exploration. They MUST NOT
  satisfy `:rf.assert/sub-equals` as proof of real subscription logic;
  subscription correctness remains tested by `compute-sub`, real setup
  events, or schema-checked app-db seeds.

The fidelity ladder is: real setup events first, schema-checked app-db
seed second, subscription overrides third. The third path is useful and
legitimate, but it must be labelled. In the normalized plan, top-level
`:sub-overrides` lowers to `[:world :render :sub-overrides]` and
`:db-seed` lowers to `[:world :db-seed]` (see §Setup — Direct app-db
seeding); `:fidelity` is computed from the resolved world inputs, so
authors SHOULD NOT have to type it. All three rungs are wired end-to-end
(rf2-blw1q closed the `:db-seed` middle-rung gap).

**Compiler contract (as implemented).** The plan compiler
(`re-frame.story.plan`):

1. **Resolves `[:arg key]` placeholders inside the override VALUES** (and
   keys), through the same one-level `substitute-args` pass setup / script
   / network use. A placeholder referencing an undeclared arg FAILS plan
   construction with `:rf.error/story-missing-arg` — the override value is
   not exempt from the args contract.
2. **Composes through fragments + the parent chain.** A composed fragment
   MAY contribute `:sub-overrides`; fragment maps merge in declared order,
   then the variant chain (where `:extends` context flowed down) wins per
   query key. The result is one flat `{query-vector value}` map.
3. **Validates each resolved value against the subscription's OUTPUT
   schema** when one is on file (via the `:sub-lookup` opt — a `(sub-id) →
   sub-meta` fn / map, defaulting to the framework `:sub` registrar; the
   schema rides on the sub-metadata `:schema` slot). This is the
   subscription-output contract, **distinct** from the view-arg/props
   schema (§View arg schemas — sharp boundary). A value that violates a
   present output schema FAILS plan construction **before render** with
   `:rf.error/story-sub-override-invalid`, whose ex-data carries the
   offending `:violations` (each `{:query-v :sub-id :value :schema
   :explain}`). A sub with no output schema soft-passes; with no validator
   threaded the value-against-schema check soft-passes (the host-free
   floor — the same convention the view-arg malformed-value check uses).
4. **Lowers the resolved map to `[:world :render :sub-overrides]`** and
   **marks `[:world :fidelity]`** with `:sub-overrides`. `:fidelity` is a
   SET drawn from `#{:real-setup :db-seed :sub-overrides}`, computed from
   the world inputs: `:real-setup` when setup events / a script drive real
   state, `:db-seed` when a schema-checked app-db seed is present
   (reserved world slot), `:sub-overrides` when any override resolves. A
   pure design variant yields `#{:sub-overrides}`; a hybrid carries both.
   Because `:fidelity` lives in `:world`, it participates in `:plan-hash`.

| Plan slot | Meaning |
|---|---|
| `[:world :render :sub-overrides]` | the resolved `{query-vector value}` map, `[:arg]` placeholders substituted |
| `[:world :fidelity]` | the resolved fidelity-ladder set (present when non-empty) |
| `[:explain :sub-overrides]` | `{:overrides … :validation {:status :ok :violations []}}` — overrides are visible in explain |
| `[:explain :fidelity]` | the same fidelity set, surfaced for docs / review |

**Single-source resolution.** Both the live canvas and `render-variant`
resolve the override map from the COMPILED variant-plan — the canvas
re-substitutes the plan's composed `[:render-raw :sub-overrides]` against
the post-control effective args through the same resolver
(`re-frame.story.render/resolve-render-sub-overrides`) `render-variant`
uses; neither re-reads the bare registrar body. This is what makes the two
agree on overrides contributed by a `:compose`d fragment or an `:extends`
parent (the plan compiler is the single merge authority — §305-306, the
same rule decorators follow). A consumer reading `(:sub-overrides body)`
straight off the side-table would see only the variant's OWN slot and drop
the composed / inherited overrides.

**Render-path read + the honesty rule (rf2-7pgiz — wired).** Overrides
feed the RENDER PATH only, and they now **surface at render**: a
normally-authored view's `@(rf/subscribe [:q])` paints the pinned value
with no events, no app-db seed. The carriage is a React context
(`re-frame.adapter.sub-override-context`), NOT a dynamic var — the var
does not survive into the view's deferred React render (the view renders
in its own reaction, after a `binding` would have unwound; empirically
confirmed under react-dom/server). The render path
(`re-frame.story.sub-overrides/override-provider`, used by the canvas's
`sub-overrides-scope` and the host's `render-host-scope`) wraps the
variant view in that context's Provider; `re-frame.subs/subscribe`
consults the resolver published under the `:subs/resolve-sub-override`
late-bind hook (dev-only, inside `subscribe`'s `interop/debug-enabled?`
gate — it DCEs in production) and, on an **exact** query-vector HIT (`=`
on the whole vector — no prefix / sub-id fuzzing, so an override never
leaks into a sibling query the author did not name; a `nil`-valued
override is a genuine hit), short-circuits build-and-cache with a constant
reaction holding the pinned value. See [006 §The sub-override subscribe
seam](../../spec/006-ReactiveSubstrate.md#the-sub-override-subscribe-seam-debug-gated)
for the core-side contract.

The override never writes app-db and never calls `compute-sub`. That
boundary is what keeps `:rf.assert/sub-equals` honest: the assertion
evaluates a sub through `compute-sub` against the frame's app-db snapshot
(`re-frame.story.assertions/evaluate-sub-equals`), which the override does
not touch — so a `:sub-overrides` value does NOT satisfy a subscription
assertion. Subscription correctness is proven by real setup events, a
schema-checked app-db seed, or `compute-sub`, never by an override (unless
a future assertion explicitly opts into override-source semantics).

**Override schema-validation (rf2-7pgiz fold-in).** When an override HIT
targets a sub that declares an output `:schema`, the pinned value is
validated against that schema (dev-only, through the same registered
validator [010 §`:sub-return`](../../../spec/010-Schemas.md#validation-order-on-event-processing)
uses). A violating override emits `:rf.error/schema-validation-failure
:where :sub-override` and surfaces `nil` (mirroring `:sub-return`'s
`:replaced-with-default`). This closes the "pin a state the real
derivation could never produce" gap: an override that violates the sub's
own output contract is reported, not silently shown. A sub with no
`:schema`, or an override that conforms, surfaces unchanged.

The pure data → data resolver fns (`resolve` / `read` / `overridden?`)
remain JVM-runnable for the plan-compiler and resolver tests; the LIVE
render carriage is the React context above, not the dynamic-var `read`.

### Network stubs

Top-level `:network` lowers to `[:world :network]` and then to the
existing managed HTTP request-stub machinery. It is the preferred
Story/test surface for `:rf.http/managed` because it preserves
request-level intent:

```clojure
{:network {[:get "/api/session"] {:reply {:ok {:user/id 42}}}
           [:post "/api/login"]  {:reply {:failure {:kind :rf.http/http-4xx
                                                     :status 401}}}}}
```

Rules: keys are `[method url]` pairs matching the managed request helper;
values are data-shaped replies; unmatched requests fail closed with the
existing "no stub matched" failure; `:network` participates in `explain`,
`:plan-hash`, and narrative/evidence projection; generic `:fx-overrides`
still exists for non-HTTP effects and unusual cases.

## Setup and script

### Setup

`:setup` establishes preconditions. Setup steps MAY dispatch real events;
the distinction between setup and script is intent and assertion
visibility, not whether events are real. Setup SHOULD use dispatches when
event/cofx/schema validation is part of establishing a realistic
precondition. Direct app-db seeding MAY be added, but if supported it
MUST validate affected app-db schemas before script execution (it
bypasses event/cofx validation).

#### Direct app-db seeding — `:db-seed` (as implemented)

`:db-seed` is the implemented direct-seed slot — the MIDDLE rung of the
fidelity ladder (`#{:real-setup :db-seed :sub-overrides}`). A variant /
fragment authors a `{path → value}` map; the path is a top-level app-db
key (or a path vector). The plan compiler:

1. **Resolves `[:arg key]` placeholders inside the seed values** through
   the same one-level `substitute-args` pass setup / script / network /
   `:sub-overrides` use (a missing arg FAILS plan construction with
   `:rf.error/story-missing-arg`).
2. **Composes through fragments + the parent chain** — a composed
   fragment MAY contribute `:db-seed`; fragment maps merge in declared
   order, then the variant chain (where `:extends` context deep-merged)
   wins per key. The result is one flat `{path → value}` map.
3. **Lowers it to `[:world :db-seed]`** (present only when non-empty; it
   participates in `:plan-hash` because the seed IS part of the variant's
   identity) and **marks `[:world :fidelity]`** with `:db-seed`.

The runtime, BEFORE any loaders or setup events run, merges the seed into
the variant frame's app-db (a non-destructive top-level merge, so
framework-reserved slots survive and a following `:real-setup` runs on
top of the seed) and then **schema-validates the seeded app-db**. Per the
rule above, direct seeding bypasses event / cofx validation but MUST
validate the affected app-db schema: the runtime walks the frame's
REGISTERED app-db schemas and validates each seeded slice, REUSING the
existing schemas late-bind seam (`:schemas/frame-schema-entries` +
`:schemas/validate-with-registered-fn` / `:schemas/explain-with-registered-fn`
— the SAME validator the `:sub-return` path and the `:sub-override`
fold-in reach; no new mechanism, no hard dep on the schemas artefact —
when it is absent the check soft-passes). A seed that violates a
registered schema FAILS the run with `:rf.error/story-db-seed-invalid`,
whose ex-data carries every `:violations` entry (`{:path :value :schema
:explain}`); the run never reaches the script. With no schemas artefact /
no registered schema the seed is applied unchecked (the host-free floor).

### Script and `settled-boundary`

`:script` is an ordered step program describing behaviour under test.
Normal author-facing dispatch uses:

```clojure
[:dispatch event-vector]
```

This means: dispatch the event into the variant frame and **advance when
the runner reaches `settled-boundary`**. `settled-boundary` is not a new
headless scheduler. In the headless case it is the existing
`dispatch-sync` run-to-fixed-point drain; richer runners add adapter
flushes:

- `:headless` — the frame's event queue is drained AND all synchronous
  re-dispatches have settled;
- `:cljs-reactive` — the above AND reaction recomputation has flushed;
- `:dom` / `:browser` — the above AND the adapter's `act()` / microtask
  flush has completed, within a declared maximum.

The runner takes a **flush-fn from the adapter-aware caller**; it MUST
NOT hard-code `dispatch-sync`. A step that requires a React/DOM flush
MUST require `>= :cljs-reactive`, so a `:headless` runner **refuses** it
(§`:cannot-run`) rather than under-flushing and passing falsely. In the
target, `[:dispatch]` is the settled author step and `dispatch-sync` is
the headless *implementation*. `[:dispatch-sync event-vector]` MAY remain
as a low-level escape step but SHOULD NOT be the normal authoring form;
an explicit `[:dispatch-sync …]` keeps its current meaning.

#### Concrete contract surface

The contract is named and exposed by the `re-frame.story.play.settled-boundary`
namespace. It does **not** introduce a second quiescence engine; it names
the existing framework drain and a flush-hook seam over it.

- `boundary-levels` — the ladder vector `[:headless :cljs-reactive :dom
  :browser]`, cheapest → richest; `boundary>=` compares two boundaries on
  it (unknown boundaries fail closed). `step-required-boundary` maps a
  script step to the minimum boundary it needs (`[:dispatch …]` →
  `:headless`; `[:click …]` / `[:type …]` / `[:assert-dom …]` → `:dom`).
- `drain-sync!` — the headless `settled-boundary`: `dispatch-sync*`
  (= `router/dispatch-sync!`) projected under the boundary name. This is
  the existing run-to-fixed-point drain (Spec 002 §dispatch-sync), not a
  reimplementation.
- **flush-hooks** — the adapter-aware caller supplies a hooks map:

  ```clojure
  {:provides  :headless | :cljs-reactive | :dom | :browser
   :dispatch! (fn [frame-id event-vector] …)         ; enqueue / fire
   :flush!    {:headless      (fn [frame-id] …)       ; drain to fixed point
               :cljs-reactive (fn [frame-id] …)       ; + reaction flush
               :dom           (fn [frame-id] …)}      ; + act()/microtask
   :timeout-ms optional-number}
  ```

  `headless-flush-hooks` is the default the JVM / node-runtime headless
  runner uses (`:provides :headless`, `:dispatch!` routed through
  `drain-sync!`). Adapter callers register richer hooks declaring a higher
  `:provides`; the runner resolves them through the
  `:settled-boundary-hooks` late-bind slot and never reaches for
  `dispatch-sync` directly.
- `dispatch-and-settle!` — the entry point `[:dispatch event-vector]`
  lowers to: dispatch through the supplied `:dispatch!`, then run each
  registered flush whose level is `<= required` in ladder order. It
  returns `{:status :settled :boundary <required>}` on success, a
  `:cannot-run` refusal when the runner's `:provides` does not reach the
  required boundary (the event is **not** dispatched — fail-closed), or
  `{:status :error …}` when a flush/dispatch throws. When the hooks declare
  `:timeout-ms`, the flush phase is bounded: a wall-clock deadline
  (`re-frame.interop/now-ms` + `:timeout-ms`) is stamped before the flush
  loop and re-checked after each flush fn returns; an over-budget flush
  phase stops and reports `:cannot-run` (reason `:flush-timeout`, via
  `flush-timeout-result`) — **never a silent pass**. Because the flush fns
  run synchronously, this bounds a sequence of slow flushes and detects an
  over-budget flush once it returns; it does not preempt a single flush fn
  that hangs forever (that is the caller's own thread/timeout box). With no
  `:timeout-ms` the flush phase is unbounded (the headless default's only
  flush is the synchronous `dispatch-sync*` drain, which cannot time out).

The play runner's `[:dispatch …]` step (`re-frame.story.play.runner-events/
exec-dispatch!`) routes through `dispatch-and-settle!`, so in headless it
settles synchronously to fixed point and is no longer an async-yield step.

### Script step grammar

P1 uses **one tagged step grammar** across `:setup` and `:script`, but
not every step is legal in both positions:

| Step | Meaning | Minimum runner |
|---|---|---|
| `[:dispatch event-vector]` | settled event dispatch | `:headless` |
| `[:wait-until predicate-spec]` | settle-on-condition; deterministic if queue/state-based | depends on predicate |
| `[:wait ms]` | bounded wall-clock sleep; the explicit determinism opt-out | runner-dependent |
| `[:assert assertion-vector]` | checkpoint assertion at this point in the script; illegal in `:setup` | depends on assertion |
| `[:click selector]` | DOM click | `:dom` / `:browser` |
| `[:type selector text]` | DOM text input | `:dom` / `:browser` |
| `[:focus selector]` | DOM focus | `:dom` / `:browser` |

Future steps MAY add drag, keypress, pointer, file, route, or agent/MCP
operations. Every step MUST declare its runner requirement.

**Bare event-vector shorthand is NOT the P1 public form** — bare vectors
are accepted ONLY as a transitional MIGRATION lift (see §Script step
runner > Migration normalization, where `coerce-script` lifts a bare event
vector to `[:dispatch event-vector]`), and that lift is slated for removal
at GA. The P1 public grammar is uniformly tagged because an app event
genuinely named `:dispatch`, `:click`, `:wait`, `:type`, or `:focus` would
otherwise be silently un-dispatchable — a reserved-word hazard precisely in
the slot where it bites hardest; the lift never re-wraps a step whose head
is a known tag. `explain` therefore shows identical normalized forms for
setup and script.

#### Script step runner

The tagged grammar is executed by the play runner: the pure step
vocabulary + state machine in `re-frame.story.play.runner`
(`step-types` / `step-arity-ok?` / `coerce-script` / `step-assertion` /
`step-wait-until`) and the impure step executor in
`re-frame.story.play.runner-events` (`exec-step!`). The runner is the
single executor across the live canvas auto-run, the interactive
step-debugger, and the headless run / replay paths; it does not branch
on caller.

- **Migration normalization.** Bare event-vector shorthand
  (`[:my/event …]`) is the MIGRATION form, not the P1 public grammar.
  `coerce-script` lifts a bare event vector to `[:dispatch event-vector]`;
  an already-tagged step round-trips unchanged. The plan compiler
  (`re-frame.story.plan`) applies this same coercion to BOTH `:setup` and
  `:script`, so `[:world :setup]` and `:script` carry tagged steps
  uniformly. Because every tag in the grammar (`:dispatch`,
  `:wait-until`, `:wait`, `:assert`, `:focus`, …) is a known step,
  `coerce-script` never lifts one — a tagged step is never re-wrapped as
  a `[:dispatch [:assert …]]`.
- **`[:dispatch event-vector]`** dispatches the event and settles to
  `settled-boundary` (the §Concrete contract surface
  `dispatch-and-settle!`). In `:headless` this is the `dispatch-sync*`
  run-to-fixed-point drain, so the event has committed by the time the
  step returns; a richer runner adds reactive / DOM flushes through its
  flush-hooks.
- **`[:wait-until predicate-spec]`** advances when a queue/state predicate
  becomes true. It is DETERMINISTIC (the determinism gate accepts it;
  only bare `[:wait ms]` is refused, §Determinism gate). The
  predicate-spec is one of:
  - `[:db path expected]` — `(= (get-in @app-db path) expected)`;
  - `[:db path :pred fn-or-sym]` — `(pred (get-in @app-db path))` (a fn
    is preferred / advanced-CLJS-safe; a symbol is the JVM/dev escape
    hatch);
  - `[:queue-empty]` — the frame's event queue has drained, which the
    settled boundary guarantees before the next step runs.

  In headless the preceding `[:dispatch …]` already settled to a fixed
  point, so the predicate is checked once synchronously. A predicate that
  never becomes true TIMES OUT READABLY — a step-fail carrying the unmet
  predicate-spec — NEVER a silent pass.
- **`[:wait ms]`** is the bounded wall-clock sleep, the explicit
  determinism OPT-OUT. The determinism gate (`assert-deterministic`)
  REFUSES a program containing a bare `[:wait ms]` with `:cannot-run`
  rather than running it flakily. `[:wait-until pred]` is the
  deterministic alternative and SHOULD be preferred.
- **`[:assert assertion-vector]`** evaluates a `:rf.assert/*` assertion
  atom at THIS exact point in the script — the in-script checkpoint
  position of the one assertion atom (§Inline script assertions vs
  terminal assertions). The checkpoint routes by assertion family,
  matching how each family is evaluated everywhere else:
  - **Dispatchable assertions** (the canonical handler-backed ids —
    `:rf.assert/path-equals` / `path-matches` / `sub-equals` /
    `dispatched?` / `state-is` / `no-warnings` / `effect-emitted`) are
    DISPATCHED into the frame. The standard reg-event handler
    (`re-frame.story.assertions`) records the canonical assertion record
    on the frame's `:rf.story/assertions` slot, and the checkpoint
    surfaces that record as the step's pass/fail. It records EXACTLY ONE
    assertion (the wrapped atom's handler is the sole recorder — the
    assertion-slot mirror that `:assert-db` / `:assert-dom` use is skipped
    for `[:assert …]` to avoid double-counting).
  - **DOM-family assertions** (`:rf.assert/dom-visible` / `dom-hidden` /
    `dom-text`) are evaluated by the DOM executor directly (no dispatch);
    a no-DOM headless runner records `:cannot-run`.
  - **Tape-evaluated assertions** carry NO reg-event handler — they are
    minted by the result boundary against the epoch tape, NOT dispatched.
    This is the schema-error declaration (§Schema rule), the causal /
    cascade family (§Causal and cascade assertions), and the browser-tier
    oracle family (§Visual, a11y, and browser checks). An in-script
    checkpoint for one of these records a no-op step (the result boundary
    owns the verdict); dispatching it would mint a spurious
    `:rf.error/no-such-handler` trace AND skip the real tape evaluation.

  `[:assert …]` is REJECTED in `:setup` at plan-compile time (see below).
- **`[:focus selector]`** (with `[:click …]` / `[:type …]` /
  `[:assert-dom …]`) is a DOM step. It requires the `:dom` capability
  token (§Requirement inference) and the `:dom` settled boundary
  (§Concrete contract surface). Under a `:headless` runner it REFUSES
  with `:cannot-run` (a no-DOM step records a skip, never a silent pass);
  under a `:dom` / `:browser` runner it fires the synthetic focus event.

**`[:assert …]` is illegal in `:setup`.** The plan compiler REJECTS an
`[:assert …]` checkpoint in `:setup` (or its shipping `:events` spelling)
at plan-compile time with `:rf.error/story-assert-in-setup`. Setup
establishes preconditions; it does not judge. The author resolves the
error by moving the assertion to `:script` (as an `[:assert …]`
checkpoint) or to the terminal `:assertions` slot. The reject runs on the
fully-resolved setup (inherited ⧺ composed ⧺ own), so a misplaced verdict
surfaces before any run — the same way the other `:rf.error/story-*` plan
errors do.

**Source metadata is preserved for narrative projection.** The runner
keeps the script step on every step-result and trace record, and the
evidence projection (`re-frame.story.play.evidence/narrative`) attributes
each contiguous run of committed epochs to the script step whose dispatch
opened it — so the script spans project over the epoch beats (the spine
the epoch-narrative work consumes).

### Inline script assertions vs terminal assertions

An assertion inside `:script` (via `[:assert …]`) is a **checkpoint**: it
must be true at that exact point in the script. An assertion in
`:assertions` is **terminal**: it **AUTO-RUNS** after script completion and
contributes a `:pass` / `:fail` verdict to the run result. The same
expectation SHOULD NOT be duplicated in both places unless the timing
distinction matters.

**Terminal `:assertions` auto-run against the FINAL settled state.** After
the script phase settles (after the auto-plays complete, or — for a variant
with no script — after setup settles), the runtime EVALUATES every terminal
`:assertions` entry and records its verdict as an assertion record on the
frame's `:rf.story/assertions` slot, exactly like an in-script `[:assert …]`
checkpoint. The two positions differ only in *when* the verdict runs:
terminal = "check the FINAL settled state" (post-script), checkpoint =
"check here, now" (at that exact script position). There is no
"doc/explain-only" terminal assertion — a terminal `:assertions` entry is a
live expectation that passes or fails the run.

Terminal assertions route through the SAME executor the in-script
checkpoints use, so they evaluate by the SAME family rules
(§Script step runner, `[:assert …]`):

- **Handler-backed (dispatchable) atoms** (`:rf.assert/path-equals` /
  `path-matches` / `sub-equals` / `dispatched?` / `state-is` /
  `no-warnings` / `effect-emitted`) are DISPATCHED into the frame; the
  reg-event handler records the canonical record.
- **DOM-family atoms** are evaluated by the DOM executor (`:cannot-run`
  under a headless runner).
- **Tape-evaluated atoms** (`:rf.assert/schema-error`, the causal / cascade
  family, the browser-tier oracle family) are NOT dispatched here — they are
  minted by the result boundary against the epoch tape (§Schema rule,
  §Causal and cascade assertions, §Visual, a11y, and browser checks), so the
  terminal auto-run records a no-op for them and does NOT double-process the
  verdict.

This applies equally to **registered variants** and **inline plans** — both
source their terminal atoms from the compiled plan's `[:expect :assertions]`
and run them through the one terminal-assertion lifecycle step.

## Composition

### `:extends` — inherits context, never behaviour

Use `:extends` for direct specialization of another variant. The
governing principle: **`:extends` inherits the context/world, never the
behaviour/judgement.** New keys self-classify by that principle.

Inherited through `:extends`:

- `:world` context, including setup, args/argtypes, frame config, render
  fixtures, network stubs, decorators, platforms, and workshop context;
- checks, because checks are the inheritable expectation form;
- tags.

Not inherited through `:extends`:

- ordinary terminal assertions;
- script steps.

Decorators are `:world` context: a child that declares no `:decorators`
INHERITS the parent's stack; a child that declares its own REPLACES them
(child-wins, the same scalar context-key rule as the other world slots —
no per-key concat). Decorator resolution therefore reads from the
compiled plan's merged `[:world :decorators]`, for BOTH the registered
path and the inline path (the same single-merge-authority rule §305-306
pins for inline plans). The variant/story side-table raw body is NOT the
decorator source — the registrar stores it raw with `:extends` intact,
and the plan compiler is the single merge authority that resolves the
chain.

Script is behaviour under test. A child variant MUST NOT silently run a
parent script. A parent's ordinary assertions describe the parent's
terminal state; a child may intentionally move beyond it. Shared
behaviour prefixes MUST be explicit fragments; reusable always-on
expectations MUST be checks. In short: context flows down; verdict is
local unless promoted to a check.

```clojure
(story/reg-variant :story.login/error-after-submit
  {:extends    :story.login/filled
   :script     [[:dispatch [:auth/login-pressed]]]
   :assertions [[:rf.assert/path-equals [:auth :state] :error]]})
```

### `:compose`

Use `:compose` to include fragments and checks explicitly:

```clojure
{:compose [:fragment.checkout/cart-with-sku
           :check/no-runtime-errors]
 :script  [[:dispatch [:checkout/submit]]]}
```

Composition order is declared order.

### Total resolution order

The plan compiler MUST resolve in this order:

1. Resolve parent chain from root to child.
2. Merge inherited story/variant metadata.
3. Apply composed fragments/checks in declared order.
4. Apply variant-owned values.
5. Resolve args and arg placeholders.
6. Build final setup order.
7. Build final script order.
8. Expand checks into grouped assertions.
9. Compute runner requirements.
10. Attach explain data.

Execution order is:

1. Allocate frame.
2. Run loaders, if present.
3. Run setup.
4. Render if the chosen runner requires rendering.
5. Run script.
6. Run checks.
7. Run terminal assertions.
8. Build run result.

### Merge rules

This per-field plan-time merge **replaces** the shipping
registration-time straight-merge (where the child wholesale replaces; see
§Shipping vs target). The change is intentional. A test MUST pin that
parent + child `:setup` *append* (a common silent-regression site). The
existing drop-shadowed-siblings exclusive-group machinery is the
per-field precedent.

Append, preserving order:

- setup;
- script from `:compose` fragments only; script never appends through
  `:extends`;
- checks;
- tags where additive semantics are intended.

Deep merge:

- args;
- argtypes;
- docs/metadata where explicitly safe.

Strict conflict:

- `:fx-overrides`;
- `:interceptor-overrides`;
- incompatible frame presets;
- duplicate unique step ids;
- any field marked strict by schema.

### Conflict resolution

**The variant owns its end-state.** A strict-conflict field set
**directly in the variant body wins**; composition only fills fields the
variant left unset. The single true hard error is **two fragments
conflicting while the variant is silent** — resolved by the variant
stating the wanted value.

P1 does not include `:resolve-conflicts`. It buys little power beyond the
priority ladder and adds a stale-resolution surface. The explanation rule
is still mandatory: `explain` MUST list resolved strict conflicts,
unresolved strict conflicts, winning sources, losing sources, and the
priority rule that chose the winner.

## Strict composition

This section pins the implemented `reg-fragment` / `reg-check` / `:compose`
contract (rf2-5x1wt.15) — the concrete surface the plan compiler enforces
on top of the conceptual model in §`:compose` / §Merge rules / §Conflict
resolution above. It is the authoritative reference for what an
implementation MUST accept, reject, and order.

### `reg-fragment` and `reg-check`

```clojure
(story/reg-fragment id body)   ;; reusable setup/script/world mixin
(story/reg-check    id body)   ;; named, reusable assertion pack
```

Both register into the Story side-table under new kinds — `:fragment` and
`:check` — queryable through the existing registry surface
(`(story/registered? :fragment id)`, `(story/handler-meta :check id)`).
Fragment and check ids are bare keywords; the example shapes
(`:fragment.<path>/<name>`, `:check/<name>`) are a convention, not a
locked grammar.

A **fragment body** carries world/behaviour only — `:args`, `:argtypes`,
`:setup` (or `:events`), `:script` (or `:play-script`), `:network`,
`:fx-overrides`, `:interceptor-overrides`, `:loaders`,
`:loaders-teardown`, `:decorators`. It MUST NOT carry judgement
(`:checks` / `:assertions`) and MUST NOT carry `:compose` or `:extends`
(flat-fragment rule, below). The body is stored verbatim — the compiler
normalizes both the public (`:setup` / `:script`) and shipping (`:events`
/ `:play-script`) spellings at compose time.

A **check body** carries `:assertions` (required) and an optional `:doc`.
It carries no world or behaviour. Check identity is preserved end-to-end
(below).

### `:compose` resolution

A variant (or inline plan) names registered fragments and checks in a
`:compose [id …]` vector, applied in **declared order**. The compiler
resolves each id against the fragment registry first, then the check
registry; an id matching neither FAILS plan construction with
`:rf.error/story-compose-unknown`.

`:compose` is a **child-only directive** — like `:extends`, it is not
itself inherited down an `:extends` chain. It is applied at step 3 of the
total resolution order, between the parent-chain merge (step 2) and the
variant-owned values (step 4).

### Flat fragments

A composed fragment that itself carries `:compose` or `:extends` FAILS
with `:rf.error/story-compose-nested-fragment`. The `Fragment` schema
rejects both at registration; the compiler re-checks at compose time as
the load-bearing guard (a programmatic registration or an inline plan may
bypass the macro/schema path). This makes cycles impossible in P1 — there
is no fragment DAG (a P1 non-goal).

### Total merge order (as implemented)

For a variant with parent chain `[root … parent child]` and a `:compose`
list of fragments `F1 … Fn`:

| Slot | Rule |
|---|---|
| `:setup` | APPEND: inherited (root→parent) ++ composed fragments (declared order) ++ child's own — variant-owned setup lands last. |
| `:script` | APPEND through `:compose` only: composed fragments (declared order) ++ child's own. Parent scripts never append (a child does not silently run a parent's behaviour). |
| `:args` / `:argtypes` | DEEP-MERGE root → fragments → child (last wins). |
| `:checks` | inherited+own (root→child) ++ composed check-ids. |
| `:assertions` | child-only (own terminal judgement). |
| `:network` | per-route merge: composed fragments under the variant chain. |
| `:fx-overrides` / `:interceptor-overrides` | strict-conflict, per-key (below). |

### Variant-owned-wins for strict-conflict fields

The strict-conflict fields are the override **maps** `:fx-overrides` and
`:interceptor-overrides`, resolved **per key**:

- the variant chain OWNS any key it set directly (the parent-chain-merged
  value, where `:extends` context flowed down) — composed fragments only
  fill keys the variant left unset;
- exactly one composed fragment setting a key → that value fills it;
- two+ composed fragments setting the same key to the **same** value → no
  conflict;
- two+ composed fragments setting the same key to **different** values
  while the variant is silent → **HARD ERROR**
  (`:rf.error/story-compose-conflict`), carrying every conflicting
  `{:field :key :sources :values}`. The variant resolves it by stating the
  wanted value directly in its body (which then wins per variant-owned).

`explain` records the settled conflicts under `:strict-conflicts` — each
`{:field :key :winner :winning-source :losing-sources :rule}` — and the
resolved `:compose` entries under `:compose` (classified `:fragment` vs
`:check`). Unresolved conflicts throw, so `:strict-conflicts` only ever
lists conflicts the priority ladder settled.

### No `:resolve-conflicts` in P1

There is no `:resolve-conflicts` escape hatch. The `Variant` schema
rejects a `:resolve-conflicts` key with `:rf.error/variant-shape` — the
absence is enforced, not merely undocumented. The only resolution is the
variant-owned-wins priority ladder above.

### Check identity is preserved

A composed (or inherited) check rides the plan as its **id** in
`[:expect :checks]`, never inlined into the assertion list. The runner
expands a check-id into grouped assertions keyed by the check id, so a
failed check result shows both the check id and the underlying assertion
records (§Checks).

## Checks and assertions

### Assertions — one atom, two positions

The assertion atom is the data vector `[:rf.assert/id & args]`:

```clojure
[:rf.assert/path-equals [:checkout :state] :submitted]
```

It is allowed in exactly two positions: `:assertions` (terminal) and
`[:assert …]` inside `:script` (mid-script checkpoint). The
`[:dispatch-sync [:rf.assert/*]]` event rail is the runner's **headless
implementation**, never an authoring form.

**One record shape, regardless of position.** Both positions resolve to
the SAME assertion atom and produce the SAME assertion record. A terminal
`:assertions` entry and the SAME atom written as an in-script `[:assert …]`
checkpoint are byte-for-byte identical atoms; the only difference is *when*
the verdict runs (terminal = after the script; checkpoint = at that exact
script position). The mid-script checkpoint dispatches its wrapped atom
through the canonical `:rf.assert/*` handler, so it lands the canonical
record — never a second, position-specific shape.

**The shipping ergonomic steps fold onto the one atom.** The plan compiler
rewrites the shipping `:assert-db` / `:assert-dom` script steps into the
canonical `[:assert assertion-atom]` checkpoint during script
normalization, so every in-script assertion — whatever sugar the author
typed — collapses to the one atom:

| Shipping step | Folds to |
|---------------|----------|
| `[:assert-db path expected]` | `[:assert [:rf.assert/path-equals path expected]]` |
| `[:assert-db path :pred f]` | `[:assert [:rf.assert/path-matches path [:fn f]]]` |
| `[:assert-dom sel :visible]` | `[:assert [:rf.assert/dom-visible sel]]` |
| `[:assert-dom sel :hidden]` | `[:assert [:rf.assert/dom-hidden sel]]` |
| `[:assert-dom sel :text txt]` | `[:assert [:rf.assert/dom-text sel txt]]` |

The `:assert-db` predicate form folds to `:rf.assert/path-matches` wrapping
the predicate in a Malli `[:fn …]` schema — the one canonical way to
express an arbitrary predicate against a path, so no parallel
predicate-assertion id is introduced.

**The DOM family carries the `:dom` runner requirement.** The DOM
assertion family `:rf.assert/dom-visible` / `:rf.assert/dom-hidden` /
`:rf.assert/dom-text` is **NET-NEW** (the shipping vocabulary had only the
seven below plus an ad-hoc synthetic `:rf.assert/dom` record the runtime
minted). Each folded DOM id rides the `:dom` capability token via the
requirement registry (§Runner requirements), so a folded `:assert-dom`
step keeps the exact runner requirement the raw step had. The DOM runner
that *evaluates* these ids lands later; until then a headless run that
reaches one refuses with `:cannot-run` (the `:dom` gate), never a silent
pass.

**The seven shipping ids are preserved.** The fold collapses *authoring
sugar* onto existing atoms; it does not retire any of the seven shipping
`:rf.assert/*` ids (listed under §Canonical P1 assertions). `:rf.assert/
path-equals` and `:rf.assert/path-matches` gain the `:assert-db` fold
targets; the DOM family is the only NET-NEW addition this fold introduces.

**Unknown ids fail plan construction.** Every authored assertion atom —
terminal `:assertions` OR an in-script `[:assert …]` checkpoint (including
the folded `:assert-db` / `:assert-dom` steps) — MUST name a recognised
`:rf.assert/*` id. An unknown id FAILS plan construction with
`:rf.error/story-unknown-assertion`, before any run, rather than recording
a vacuous `:rf.assert/unknown` pseudo-record at run time. There is no
`:rf.assert/no-schema-errors` author surface — schema-clean is the
knob-free runner floor (§Schema rule), enforced by post-run evidence-slot
validation, not an opt-in assertion.

Assertions in `:assertions` are own-only terminal expectations; they MUST
NOT inherit through `:extends`.

### Checks

Checks are named assertion packs. Checks preserve identity in results,
and they **inherit and compose** (the inheritable form, distinct from
own-only assertions). A failed check result MUST show both the check id
and the underlying assertion records.

### Canonical P1 assertions

The existing `:rf.assert/*` family (per [`004-Assertions.md`](004-Assertions.md))
remains the base. P1 SHOULD include or retain:

- `:rf.assert/path-equals`
- `:rf.assert/path-matches`
- `:rf.assert/sub-equals`
- `:rf.assert/dispatched?`
- `:rf.assert/state-is`
- `:rf.assert/no-warnings`
- `:rf.assert/effect-emitted`
- `:rf.assert/schema-error` (NET-NEW; §Schema rule)
- `:rf.assert/dom-visible` / `:rf.assert/dom-hidden` / `:rf.assert/dom-text`
  (NET-NEW; the `:dom` family the shipping `:assert-dom` step folds to —
  §Assertions — one atom, two positions)
- `:rf.assert/visual-snapshot` (`:browser` / `:pixels`)
- `:rf.assert/a11y` (`:browser` / `:a11y-engine` for axe-style)
- `:rf.assert/a11y-structural` (`:hiccup` — the explicitly STRUCTURAL
  accessibility check; runs on the rendered hiccup tree without a real
  browser, §Visual, a11y, and browser checks)

`:rf.assert/caused` and `:rf.assert/no-cascade-rerender` both require the
reactive/render-count probe. That probe SHIPS (rf2-5x1wt.30) as the
`re-frame.story.play.evidence/reactive-counts` PROJECTION over the
`:rf.sub/run` / `:rf.view/rendered` rows the epoch tape already retains —
not a new core seam — so these assertions run under the `:cljs-reactive`
runner (and `:cannot-run` under `:headless` / `:hiccup`, which do not
flush reactions).

#### Causal and cascade assertions (rf2-5x1wt.31)

`:rf.assert/caused` and `:rf.assert/no-cascade-rerender` are **causal**
assertions: they project a CAUSE→EFFECT relationship from the SAME reactive
evidence the tape already carries — the `:rf.sub/run` / `:rf.view/rendered`
rows stamped with the dispatching cascade's `:cause-event-id` (Spec 009
§`:rf.sub/cause-event-id`), surfaced in the `reactive-counts` `:by-cause`
projection. They add **no new trace op-type and no new accumulator** — the
tape is the source of truth (§Risks — evidence projections drift).

Like `:rf.assert/schema-error` they carry NO `reg-event` handler: they are
NOT dispatched into the frame. They are **tape-evaluated** in the result
boundary (`re-frame.story.result/match-causal-expectations`) against the
projected `:reactive-counts` / `:sub-runs` / `:renders`. The declared atom
names the cause event and (optionally) the effect surface + a count bound:

```clojure
[:rf.assert/caused              {:event :counter/inc :sub :total}]      ; e recomputed :total ≥ 1
[:rf.assert/caused              {:event :counter/inc :view :badge :exactly 1}]
[:rf.assert/no-cascade-rerender {:event :unrelated/event}]              ; e caused 0 effects
[:rf.assert/no-cascade-rerender {:event :counter/inc :view :counter :max 2}]
```

- `:event` (required) is the **cause** — the dispatching cascade's event-id.
  An atom that names no `:event` FAILS (a causal assertion that asserts
  nothing measurable).
- `:sub` / `:view` (optional) select the **effect surface** measured: the
  recompute count of the named sub, or the render count of the named view.
  Absent, the surface is the cause's total recompute + render count.
- `:min` / `:max` / `:exactly` bound the effect count. The per-id DEFAULT is
  `:rf.assert/caused` → `{:min 1}` (the cause produced the effect at least
  once) and `:rf.assert/no-cascade-rerender` → `{:min 0 :max 0}` (the cause
  did NOT over-render); an author MAY override either bound.

These are PURE expectations, **not** agreement-floor signals — an over-render
is a `:fail` of a declared `:rf.assert/no-cascade-rerender`, not a tape-floor
failure on its own (`tape-shows-failure?` does not read reactive counts). A
run with NO reactive rows is caught upstream by the `:reactive-counts`
fail-closed evidence-slot check (it reports `:cannot-run`, never a silent
pass against an empty projection).

There is no `:rf.assert/no-schema-errors` author surface — schema-clean
is the knob-free floor (§Schema rule), so the only schema author surface
is `:rf.assert/schema-error`. Additional assertions MAY be added for
hiccup, DOM, and trace shape; each MUST declare a runner requirement.

## Schema rule

Any emitted `:rf.error/schema-validation-failure` MUST fail the run
unless exactly consumed by an expected schema assertion, even if
recovery/rollback leaves final app-db acceptable. This is a runner
invariant; there is **no per-variant `:schema-policy` knob** (the absence
is the feature).

NET-NEW wiring: fail runs by projecting
`:rf.error/schema-validation-failure` from the epoch tape's
`:trace-events`. Do not add a parallel per-frame `:schema-violations`
accumulator; a second capture path can drift from the trace evidence the
UI already reads.

**Exact-consumption matching (multiset):**

1. Collect every schema validation failure emitted during the run; key
   each by surface-specific selector:
   - `:event`: `[:event failing-id]`, with optional `:path` only when the
     trace provides one;
   - `:cofx`: `[:cofx failing-id]`;
   - `:fx-args`: `[:fx-args fx-id]`;
   - `:sub-return`: `[:sub-return failing-id query-v]`;
   - `:app-db`: `[:app-db registered-path path]`;
   - `:machine-data`: `[:machine-data machine-id phase]`.
2. Each declared `[:rf.assert/schema-error {…}]` consumes **exactly one**
   matching violation; N declared of a key consume N.
3. Fail if a declared expectation matches no violation.
4. Fail if any violation is left unconsumed.

```clojure
;; expects exactly one event validation violation for :checkout/submit
[:rf.assert/schema-error {:where :event :event :checkout/submit}]
```

### The schema-violation invariant {#schema-violation-invariant}

The invariant is implemented as a **refinement of the agreement floor**, not
an opt-in (rf2-5x1wt.21). It rests on three pieces, all pure data → data:

- **`:rf.assert/schema-error` is recognised but NOT dispatched.** It is in
  `assertions/canonical-assertion-ids` (so plan construction accepts it) and
  requires the `:schema` capability token, but it is **not** installed as a
  `reg-event` handler — it carries no app-db semantics. It declares an
  EXPECTED violation that the result boundary evaluates against the
  projected epoch-tape evidence, never a dispatched event into the frame.
  There is deliberately no `:rf.assert/no-schema-errors`: a schema-clean run
  is the knob-free FLOOR, refined by these expectations.

- **Exact consumption is a pure multiset match.**
  `result/match-schema-expectations` pairs the declared
  `:rf.assert/schema-error` atoms against the tape's projected violations
  (`evidence/schema-violations` — the SINGLE source; no second accumulator):
  each declared atom's surface selector (`assertions/schema-error-selector`,
  which mirrors `evidence/violation-selector` key-for-key) consumes exactly
  one same-selector violation; a bare `[:rf.assert/schema-error]` is the
  `[:any]` wildcard that consumes any one remaining violation. Concrete
  expectations pair before wildcards so a wildcard never starves a concrete
  match, and a consumed violation leaves the pool so N expectations of a
  selector consume exactly N violations. It returns the schema-error
  assertion records, the exactly-consumed selectors, the unmatched
  expectations, and the unconsumed violations.

- **`run-result` wires the verdict.** A declared expectation that matched a
  violation mints a `:pass` record; an **unmatched** expectation mints a
  `:fail` record — a *missing* expected violation fails the run. The
  matcher's **multiset `:unconsumed` vector** — the violations left after the
  exact pairing, NOT a set-subtraction over the consumed selectors — is the
  agreement floor's schema signal (`evidence/evidence-shows-failure?`'s
  `:unconsumed` arity). This is load-bearing: a set of consumed *selectors*
  collapses duplicates, so when M&lt;N expectations match a selector with N
  violations the set would falsely excuse **all** N and report `:pass` (the
  rf2-5mrnwx false-green). Threading the matcher's already-correct multiset
  `:unconsumed` keeps the (N−M) genuinely-unconsumed violations as a floor
  signal, so an exactly-expected violation does **not** trip the floor while a
  partially- or un-consumed one does. A *different* violation than expected
  therefore fails twice over (a `:fail` record for the missing expected
  violation AND the floor on the emitted-but-unexpected one). The public
  `story/tape-shows-failure?` keeps its set-keyed `consumed-selectors`
  convenience arity (the caller-supplied escape hatch, correct when at most
  one violation exists per selector); `run-result` also subtracts that
  caller-supplied set from the multiset `:unconsumed` before the floor.

**Rollback does not hide a violation.** The floor reads the retained epoch
*tape*, not the final app-db. A handler whose schema validation failed but
whose recovery/rollback left the final app-db acceptable still emitted a
`:rf.error/schema-validation-failure` trace into its epoch's `:trace-events`,
and that trace stays on the tape regardless of the `:ok` epoch outcome or the
clean final db. So the run fails unless the violation is exactly
expected+consumed — the rollback cannot mask it.

## Runner model

### Runner kinds and capabilities

Required runner kinds:

| Runner | Proves |
|---|---|
| `:headless` | app-db, events, effects, schema errors, trace events, pure subscriptions. |
| `:hiccup` | hiccup structure, text, handler wiring, render-to-string facts. |
| `:cljs-reactive` | reactive subscription cache, recompute count, render cause, over-render checks. |
| `:dom` | DOM events, focus, visibility, browser APIs. |
| `:browser` | real browser layout, screenshots, a11y engine, pixel diffs. |

Capability is modeled as a **set of tokens**, not a total-order scalar,
because the kinds are orthogonal (`:hiccup-structure` vs
`:reactive-counts` are incomparable). Concrete runners advertise tokens,
e.g. `#{:app-db :effects :schema :hiccup-structure :reactive-counts :dom
:pixels :a11y-engine}`. Each assertion/step declares the tokens it
requires; a runner is **valid** iff its token set is a superset;
**cheapest** = first in a small cost-ordered concrete-runner list whose
set qualifies.

P1 selects among the tiers with **real seams**
(`:headless` / `:hiccup` / `:cljs-reactive` / `:dom` / `:browser`).
`:reactive-counts` is proven by `:cljs-reactive` via the
`re-frame.story.play.evidence/reactive-counts` projection over the
`:rf.sub/run` / `:rf.view/rendered` rows the epoch tape already retains
(rf2-5x1wt.30) — a projection, not a NET-NEW core seam. `:cljs-reactive`
sits between `:hiccup` and `:dom` on the cost ladder (it flushes
reactions; it does not drive synthetic DOM events).

MCP is not a runner tier. It is a transport/control surface over the same
`story/run` and `story/explain` APIs. A live agent run differs by frame
binding (`:fresh` vs `:attached`), not by a separate execution model.

### Runner policy — single pass, refuse above it

The runner selector MUST run the plan in a **single pass under the
selected runner policy**. The default policy for `story/run` and
`story/is` is fixed-runner, defaulting to `:headless` unless the caller
supplies `:runner`. Under fixed-runner policy, an assertion or step that
requires unavailable evidence records `:cannot-run` (pure set-difference)
rather than escalating the whole plan — so one `:visual-snapshot`
assertion does not drag a 95%-headless variant to `:browser`.

Run options MAY include `{:runner :browser}` to select a richer concrete
runner, or `{:escalate true}` / `{:runner :auto}` to request the cheapest
concrete runner that can satisfy all selected step/assertion
requirements. If no available runner can satisfy the requested policy,
the run returns `:cannot-run`. The runner MUST NOT fake an assertion it
cannot prove.

### `:cannot-run`

Refusal shape:

```clojure
{:status :cannot-run
 :required-runner  #{:pixels}
 :available-runner #{:app-db :effects}
 :reason :assertion-requires-browser
 :assertion [:rf.assert/visual-snapshot ...]}
```

`:cannot-run` is a distinct **third** status (not pass, fail, or skip).
It generalizes the shipping no-DOM `:skipped?` on assertion records —
route `:skipped?` through this refusal shape rather than maintaining two
"ran but proved nothing" vocabularies. Preflight computes the evidence a
step/assertion requires; post-run validation confirms the required
evidence slots are actually present in the result/tape. A runner must
fail closed if either side says the proof is unavailable.

The variant-level **aggregation rule** MUST be defined once: a variant
whose only unmet assertions are `:cannot-run` is itself `:cannot-run`,
not a silent pass. An inherited check contributes assertion requirements,
but it does not silently raise the whole child run to a richer tier.
`explain` MUST attribute unmet or requested runner requirements to the
originating check/assertion (no hidden global effect). Story UI MUST
render `:cannot-run` distinctly. CI MUST define a policy per gate
(§CI policy).

## Runner requirements

The runner-requirement registry is the keystone that decides WHICH
concrete runner a plan needs and REFUSES (`:cannot-run`) rather than
faking a proof a runner cannot supply. It lives in
`re-frame.story.requirements` — the single home so requirement inference,
runner selection, the `:cannot-run` refusal, and post-run evidence-slot
validation all read ONE source of truth. Every fn there is pure data →
data, so the whole registry runs under `clojure -M:test`.

### The capability ladder

The five runner KINDS (§Runner kinds and capabilities) are NOT a single
total order — `:hiccup-structure` and `:reactive-counts` are orthogonal,
neither subsumes the other — so a runner advertises a SET of capability
TOKENS and each step/assertion declares the set it requires. The P1
capability tokens are:

`:app-db`, `:effects`, `:schema`, `:trace`, `:pure-subs`,
`:hiccup-structure`, `:reactive-counts`, `:dom`, `:pixels`,
`:a11y-engine`.

The cost-ordered concrete runners (cheapest → richest) advertise these
token sets, each a superset of the cheaper one for the ordered tokens
plus its own orthogonal additions:

| Runner | Provides (cumulative) |
|---|---|
| `:headless` | `:app-db :effects :schema :trace :pure-subs` |
| `:hiccup` | headless ∪ `:hiccup-structure` |
| `:cljs-reactive` | hiccup ∪ `:reactive-counts` |
| `:dom` | cljs-reactive ∪ `:dom` |
| `:browser` | dom ∪ `:pixels :a11y-engine` |

`:cljs-reactive` sits between `:hiccup` and `:dom` on the cost-ordered
selection list (rf2-5x1wt.30): its distinguishing token,
`:reactive-counts`, has a real seam — the
`re-frame.story.play.evidence/reactive-counts` PROJECTION over the
`:rf.sub/run` / `:rf.view/rendered` rows the epoch tape already retains
(§1a; a projection, not a NET-NEW core seam). It flushes reactions (so
subs deref and views render, landing those rows) but stops short of
synthetic DOM events. A requirement on `:reactive-counts` resolves to
`:cljs-reactive` under `:auto`, and to `:cannot-run` under a fixed
`:headless` / `:hiccup` runner (which do not flush reactions). A runner is
**valid** for a set of required tokens iff its token set is a superset;
**cheapest** is the first runner on the cost-ordered list whose set
qualifies. The post-run fail-closed slot check still refuses a
`:cljs-reactive` run whose tape produced no reactive rows — the projection
being available does not weaken the floor.

### Requirement inference

Each script/setup STEP and each ASSERTION atom declares the capability
tokens it requires:

- `[:dispatch …]` / `[:dispatch-sync …]` → `#{:app-db}` (the headless
  floor drains the queue to a fixed point); `[:wait …]` / `[:wait-until
  …]` → `#{}` (the boundary ladder governs flush, not the capability set);
  `[:click …]` / `[:type …]` / `[:focus …]` / `[:assert-dom …]` →
  `#{:dom}`.
- An in-script `[:assert <atom>]` checkpoint folds the WRAPPED atom's
  tokens (so `[:assert [:rf.assert/visual-snapshot …]]` requires
  `:pixels`).
- The seven shipping `:rf.assert/*` ids require app-db / trace tokens;
  `:rf.assert/schema-error` requires `:schema`; the DOM family requires
  `:dom`; `:rf.assert/visual-snapshot` requires `:pixels` and
  `:rf.assert/a11y` requires `:a11y-engine`; the reactive-count
  assertions `:rf.assert/caused` / `:rf.assert/no-cascade-rerender`
  require `:reactive-counts` (proven by `:cljs-reactive` via the
  `evidence/reactive-counts` projection; `:cannot-run` under
  `:headless` / `:hiccup`).

A plan's `:required-runner` slot is the UNION of every setup-step,
script-step, and terminal-assertion token set — capability tokens, NOT a
tier scalar. The plan compiler (`re-frame.story.plan`) fills the slot
through this registry, so the inference has one home.

### `:cannot-run` refusal

A required token the chosen runner cannot prove produces a `:cannot-run`
refusal (the distinct THIRD status, never a silent pass — §`:cannot-run`).
This is the SAME refusal vocabulary as the `settled-boundary` boundary
refusal (`re-frame.story.play.settled-boundary`), expressed on the
capability axis:

```clojure
{:status           :cannot-run
 :required-runner  #{:dom}          ; the tokens the unit needs
 :available-runner #{:app-db …}     ; the chosen runner's tokens
 :missing          #{:dom}          ; required − available (the gap)
 :reason           :runner-lacks-capability
 :runner           :headless        ; the runner that refused
 :unit             [:rf.assert/dom-visible "[x]"]}  ; the originating step/assertion
```

The two policies (§Runner policy):

- FIXED-RUNNER (default): the caller's `:runner` (or `:headless`) runs the
  WHOLE plan in one pass; a unit whose tokens it lacks refuses
  per-requirement (pure set-difference) — one `:visual-snapshot` does NOT
  drag a 95%-headless variant to `:browser`. `:reason
  :runner-lacks-capability`.
- AUTO / ESCALATE (`{:runner :auto}` / `{:escalate true}`): choose the
  CHEAPEST concrete runner whose token set satisfies ALL selected
  requirements (a `:reactive-counts` requirement escalates to
  `:cljs-reactive`). When NO concrete runner can satisfy the union, the
  whole run is `:cannot-run` with `:reason :no-runner-satisfies`.

The variant-level **aggregation rule** is stated once: a variant whose
only unmet expectations are `:cannot-run` is itself `:cannot-run`, not a
silent pass. Precedence is `:error` > `:fail` > `:cannot-run` > `:pass` —
a real failure or error is never masked by a refusal.

### Fail-closed evidence-slot validation

A proof is honoured only when BOTH sides agree it is available:

1. **Preflight** — the chosen runner's token set must be a superset of the
   requirement (the selection / refusal above).
2. **Post-run** — a step/assertion that REQUIRED a proof must find the
   corresponding evidence SLOT populated in the projected run evidence
   (`re-frame.story.play.evidence/project-evidence`). A required slot the
   tape never produced FAILS CLOSED to `:cannot-run` (the proof was
   promised but not delivered), NEVER `:pass`.

The token→slot map lists ONLY tokens whose evidence slot is non-empty for
EVERY healthy run of an assertion requiring them — so an empty slot
genuinely means "proof promised, not delivered": `:effects → :effects`,
`:schema → :schema-violations`, `:reactive-counts → :reactive-counts`
(present only when the tape carried `:rf.sub/run` / `:rf.view/rendered`
rows — a `:cljs-reactive` run whose tape carried none refuses,
fail-closed), `:pixels`/`:a11y-engine` → browser-only oracle slots.

The always-on / **empty-is-healthy** tokens are DELIBERATELY absent — they
impose no post-run check because an empty slot is their NORMAL passing
state, so a presence gate would emit a false `:cannot-run`:

- `:app-db` — its proof is the final db itself, validated by the
  assertion's own evaluation.
- `:trace` — the trace stream is always-on; its only projections
  (`:warnings`, `:schema-violations`) are FILTERED views, not a faithful
  presence slot for the whole stream. `:rf.assert/no-warnings` PASSES when
  `:warnings` is empty and `:rf.assert/dispatched?` proves against trace
  DISPATCH rows, not warnings — so a `:trace → :warnings` gate would
  false-refuse both healthy cases.
- `:hiccup-structure` / `:dom` — `:renders` is empty whenever an assertion
  legitimately asserts ABSENCE (`:rf.assert/dom-hidden`, a structural-a11y
  check that finds no offending node). The runner-selection PREFLIGHT
  already refuses these against a runner that cannot render; a post-run
  render-count gate would only add false refusals.

Because the check reads the SAME `project-evidence` projection the
run-result slots derive from, a duplicate accumulator cannot report green
while the tape is empty.

### Run-path wiring (rf2-baah3)

The run path (`re-frame.story.runtime` — `run-variant` / `run-inline-plan`)
THREADS this registry end-to-end, so the selection + refusal + validation
above are run-time behaviour, not merely pure facts the registry can
compute:

1. it normalizes the run opts (`normalize-run-opts`) and SELECTS the runner
   (`select-runner`) from the plan's `:required-runner` once, up front (the
   cheapest capable runner under `:auto`, or the caller's fixed runner);
2. it surfaces the chosen `:runner` and the plan's `:required-runner` on the
   unified result;
3. it folds the per-requirement refusals into the result's `:unmet` /
   `:cannot-run` slot — the `:auto` no-capable-runner refusal, the
   fixed-runner per-unit `unmet-assertions` / `unmet-steps` gaps, AND the
   post-run `validate-run-evidence` fail-closed slot check (read against the
   SAME `project-evidence` projection the result slots derive from).

So an UNMET requirement (a `:pixels` `:rf.assert/visual-snapshot` under
`:headless`) makes the run `:cannot-run` — the distinct THIRD status — never
a false pass; a met-and-passing headless run is not falsely refused (the
empty-is-healthy slots impose no gate).

### MCP is a frame binding, not a runner tier

MCP is NOT a runner. It is a transport/control surface over the same
`story/run` / `story/explain` (§Runner kinds). A live agent run differs
only by FRAME BINDING (`:fresh` vs `:attached`) — modeled as the
`:frame-binding` run-opt, never a capability token or a runner kind.

### Run / `is` opts

`normalize-run-opts` is the ONE place the run/`is` opts (§Public execution
API) collapse into the canonical selection shape:

```clojure
{:runner :headless | :hiccup | :cljs-reactive | :dom | :browser | :auto
 :escalate boolean              ; synonym for :runner :auto when true
 :frame-binding :fresh | :attached
 :platform :client | :server}
```

`:escalate true` OR `:runner :auto` collapse into `{:mode :auto}` (the
cheapest qualifying runner is chosen); any other recognised `:runner` (one
of the `runner-kinds`) → `{:mode :fixed :runner <kind>}`. The default is
fixed `:headless`. An unrecognised `:runner` not in `runner-kinds` (e.g.
`:gpu`) falls back to the fixed `:headless` policy — an unknown tier never
silently escalates. `:frame-binding` and `:platform` carry through
untouched so the three-verb surface (`run` / `is` / `explain`, shipped as
the P1 public execution API — §three verbs) and the MCP transport thread
the SAME normalization.

## Run result

All runners MUST return the same shape. (Today the runtime uses a
`:lifecycle` key; this top-level `:status` is NET-NEW.) Run result slots
are projections from the epoch tape wherever possible. The result shape
is API-stable, but the storage/source of truth is one tape so Story UI,
CI, docs, agents, and future golden/diff tools cannot disagree about what
happened.

**Schema-backed, frozen contract (rf2-3nbl5.6).** This shape is a
**frozen public contract** — the ONE result language spoken IDENTICALLY
across `story/run`, `story/is`, `story/render-variant`, the Story UI Test
mode, story-mcp `run-variant` / `read-failures`, and generated run
artifacts. A result object moves **CLJS test → Story UI → MCP with NO
semantic translation**. The contract is **executable**: the Malli schema
`re-frame.story.result/RunResult` (with `AssertionRecord` / `CheckRecord`,
re-exported as `story/run-result-schema` + `story/valid-run-result?` /
`story/explain-run-result`) is the statement of record, and the
cross-surface round-trip is gated by
`re-frame.story-mcp.run-result-roundtrip-test`. The map schema is
deliberately **open** — the verdict + judgement + agreement-floor slots
are pinned, while the evidential `.4` projections and the identity /
timing / provenance slots (`:frame`, `:decorators`, …) ride along. The
verdict is `:status`, read via `story/result-status` /
`story/result-passed?`; there is **NO `:passing?` boolean** and **no
lifecycle-as-verdict** (the clean break, rf2-ba86n.17 — a boolean could
not express the distinct `:cannot-run` THIRD outcome, and a lifecycle
state is the frame's mount state, not the run's judgement).

```clojure
{:status :pass                          ; :pass | :fail | :cannot-run | :error
 :variant/id optional-keyword
 :plan-hash string
 :run-hash string                       ; over the canonical epoch slice
 :runner runner-kind
 :required-runner #{capability-token ...}
 :fidelity #{keyword ...}
 :elapsed-ms number
 :app-db final-db-or-redacted
 :sub-overrides {query-vector data}
 :epoch-tape [epoch-record ...]         ; evidence source when retained
 :narrative [narrative-span ...]        ; required scrubbable projection
 :assertions [assertion-record ...]
 :checks [check-record ...]
 :schema-violations [schema-record ...] ; projected from epoch trace events
 :warnings [warning-record ...]
 :effects [effect-record ...]
 :trace-summary {...}
 :run-artifact optional
 :cannot-run optional-refusal
 :error optional-error}
```

Assertion record:

```clojure
{:assertion assertion-id
 :status :pass                          ; :pass | :fail | :cannot-run | :error
 :passed? boolean
 :payload [...]
 :expected optional
 :actual optional
 :reason optional-keyword-or-string
 :source optional-source-coord
 :runner runner-kind
 :elapsed-ms optional-number}
```

Check record:

```clojure
{:check check-id
 :status :pass                          ; :pass | :fail | :cannot-run | :error
 :assertions [assertion-record ...]}
```

Narrative span (projection):

```clojure
{:step script-step
 :caption optional-string               ; story-beat
 :epochs [epoch-beat ...]}

{:epoch-id epoch-id
 :dispatch-id optional-dispatch-id
 :trigger-event optional-event-vector
 :db-before optional-db-or-redacted
 :db-after optional-db-or-redacted
 :effects [effect-record ...]
 :sub-runs [sub-run-record ...]
 :renders [render-record ...]
 :trace-events [trace-event ...]}
```

Story Test mode, CI, `clojure.test`, and MCP MUST consume this shape
rather than maintaining independent result schemas. This unification
fixes the documented "false GREEN," where run-state and the assertions
slot disagreed.

### Run-result evidence projection {#run-result-evidence-projection}

The evidential run-result slots are **projections from one retained epoch
tape**, not a second capture path. The boundary lives in
`re-frame.story.play.evidence` (re-exported as `story/project-evidence`);
the runner reads the retained tape via `re-frame.core/epoch-history` and
merges the projection into the run-result. There is exactly one source of
truth — Story UI, CI, docs, agents, and the golden/diff tools cannot
disagree about what happened, and no parallel accumulator can drift from
the tape evidence (the former per-frame `trace-accumulators` siphon for
warnings/effects was superseded by this projection and fully removed in
rf2-luzky).

`(story/project-evidence epoch-tape {:script coerced-script})` is pure —
`:rf/epoch-record` vector in, evidence map out — so it runs under
`clojure -M:test` with no runtime. It returns:

| Slot | Source in the tape | Rule |
|---|---|---|
| `:epoch-tape` | the retained vector, verbatim | the evidence source, when retained |
| `:schema-violations` | each epoch's `:trace-events` | every `:rf.error/schema-validation-failure` error trace, keyed by the §Schema-rule surface selector (`[:event id]`, `[:cofx id]`, `[:fx-args id]`, `[:sub-return id query-v]`, `[:app-db registered-path path]`, `[:machine-data machine-id phase]`) so the multiset matcher pairs declared expectations to emitted failures |
| `:warnings` | each epoch's `:trace-events` | every `:op-type :warning` trace event, in tape order (the canonical severity discriminator every `(trace/emit! :warning …)` site produces; the framework never emits `:warn`) |
| `:effects` | each epoch's `:effects` row | the rows the framework already projected at settle time (`re-frame.epoch.capture/project-all`), concatenated in dispatch order, each stamped with its `:epoch-id` |
| `:sub-runs` / `:renders` | each epoch's `:sub-runs` / `:renders` rows | concatenated in tape order, each stamped with `:epoch-id` |
| `:reactive-counts` | the `:sub-runs` / `:renders` rows above | recompute / render counts (rf2-5x1wt.30) — `{:sub-recomputes :view-renders :by-sub-id :by-view :by-render-key :by-cause :per-epoch}`; PRESENT only when the tape carried at least one reactive row (a bare headless dispatch-only tape omits it, so the fail-closed slot check is honest). `:by-cause` credits each row to the dispatching event's `:rf.sub/cause-event-id` / `:rf.view/cause-event-id` attribution |
| `:narrative` | the script steps over the epoch beats | the two-level projection below |

**Two-level narrative.** The author's `:script` steps form the outer spans;
the epoch beats committed while settling each step are the inner level.
Because a `[:dispatch …]` step settles to a fixed point (§Script and
`settled-boundary`), **one dispatch step MAY span multiple epoch beats** — a
handler that re-dispatches produces several committed epochs, all
attributable to the one authored step. Pure assertion/wait steps that
commit no epoch produce empty spans; epochs preceding the first dispatch
step (setup-phase cascades, framework bootstrap) collect under a leading
`nil`-step span so no tape evidence is dropped. Attribution is **exact**
when the runner stamps each committed epoch with `:rf.story/script-idx`
(the per-step settle boundary), and falls back to an even forward
partition across the dispatch steps for a bare `epoch-history` tape. Every
epoch lands in exactly one span. When several `:auto-run?` plays run in
one settle (the multi-play sequencer), the narrative spans the
**concatenated** play scripts, so the per-step settle boundaries MUST
ACCUMULATE across the whole sequence — the sequencer clears the boundaries
ONCE up front and each play APPENDS its absolute boundaries (the
append-only epoch tape is never reset between plays), keeping the
positional boundary→script-step zip aligned. Clearing per-play would drop
every earlier play's boundaries and mis-attribute later-play effects to
earlier-play steps — a green run with false provenance (rf2-76l69l).

**Narrative navigation (the scrub backbone).** The two-level `:narrative`
is a *tree* (spans over beats), but a Test-mode / Docs-mode **scrub** moves
*linearly* through every beat in tape order. Four pure helpers flatten the
tree into the ordered, addressable beat sequence the scrub walks — the
navigation MATH, JVM-testable independent of any UI:

| Helper | Returns |
|---|---|
| `(story/narrative-beats narrative)` | the ordered beat vector; each beat is the inner `epoch-beat` augmented with `:beat-idx` (the 0-based scrub address), `:span-idx`, owning `:step`, and `:span-caption`. Spans with no beats (a pure assertion/wait step) contribute nothing — the scrub only stops on committed epochs |
| `(story/beat-count narrative)` | the number of scrubbable beats — the scrub slider's extent (positions `0 … (dec beat-count)`) |
| `(story/beat-at narrative idx)` | the flattened beat at scrub position `idx`, or nil out of range |
| `(story/beat-epoch-ids narrative)` | the ordered `:epoch-id` vector; the Nth element is the `restore-epoch` target for scrub position N. Aligned 1:1 with `narrative-beats` |

The flattened sequence **agrees with the tape by construction**: every
committed epoch appears exactly once, in tape order, regardless of how the
spans group them — none invented, none dropped. The scrub UI (the slider /
keyboard navigation that calls `restore-epoch` per `beat-epoch-ids`) lives
ABOVE this boundary; only the navigation projection ships in P1. The UI
itself is owned by its North-Star spec —
[`020-Story-UI-Inspector-And-Xray.md`](020-Story-UI-Inspector-And-Xray.md)
§3 (Evidence spine — display) — not by this substrate spec.

**The agreement floor.** `(story/tape-shows-failure? epoch-tape
consumed-selectors)` is the consistency invariant: a run MUST NOT be
reported `:pass` while the tape carries failure evidence — an unconsumed
`:rf.error/schema-validation-failure`, a non-`:ok` epoch `:outcome`, or an
`:error`-outcome effect row. The optional `consumed-selectors` set excuses
schema violations exactly consumed by `:rf.assert/schema-error`
expectations (§Schema rule). The runner asks this of the *projected
evidence*, not of a sibling accumulator, so **no duplicate accumulator can
report green when the tape shows a failure**. `run-hash` (§Canonicalization)
already hashes `:epoch-tape` alongside the projected `:schema-violations` /
`:warnings` / `:effects`, so a divergent projection perturbs the
run-equivalence hash.

### Unified run result {#unified-run-result}

There is **one run-result shape**, assembled by **one boundary** —
`re-frame.story.result/run-result` (re-exported as `story/run-result`).
Before unification three result vocabularies coexisted and could disagree
(the documented "false GREEN"): the runtime's `:lifecycle`-keyed map, the
play runner's per-step `run-state` machine, and `replay-result`'s tape-
projected shape. They are now folded onto the ONE shape, derived — wherever
the tape carries the evidence — from `evidence/project-evidence` (the single
tape projection; there is **no parallel accumulator**). The judgement slots
(`:assertions` / `:checks`) fold the `:rf.story/assertions` accumulator (the
ONE non-tape input — an assertion verdict is the one fact the tape does not
carry); every evidential slot is a `.4` projection.

**The three record shapes carry a unified `:status`.** Each assertion record
and check record carries `:status ∈ #{:pass :fail :cannot-run :error}`
alongside the `.18` atom / accumulator fields, so the run aggregation reads
ONE field. `result/record-status` derives it: an explicit `:status` wins;
else `:cannot-run?` / a no-DOM `:skipped?` → `:cannot-run` (the §`:cannot-run`
rule generalizes the shipping `:skipped?`); `:exception` / `:error` →
`:error`; `:passed?` true/false → `:pass` / `:fail`; a record with no
outcome is vacuously `:pass` (the §Story-as-test duality).

**Check records group assertions.** `result/check-records` pairs the plan's
expanded `{check-id [assertion-atom …]}` (via `plan/expand-checks`, §Total
resolution order step 8) against the run's evaluated records — each check id
groups the records its atoms produced (matched by assertion id **and**
payload, so two same-id assertions in one check disambiguate), and the
check's `:status` aggregates its group. A failed check shows BOTH the check
id AND the underlying records (§Checks).

**The verdict — the aggregation rule, stated once.**
`requirements/aggregate-status` is the ONE rule over the assertion records +
the `:cannot-run` refusals: `:error` > `:fail` > `:cannot-run` > `:pass`.
The agreement floor (`tape-shows-failure?`) then escalates a would-be
`:pass` to `:fail` when the tape carries unconsumed failure evidence — so a
run NEVER reads green while the tape is red, and the verdict derives from the
PROJECTED evidence, not a sibling accumulator. A run whose only unmet
expectations are `:cannot-run` is itself `:cannot-run` (a refusal, never a
silent pass); a zero-assertion clean run is `:pass` (vacuously green).

**The runtime consumes the folded plan.** The `.18` fold (`:assert-db` /
`:assert-dom` → the canonical `[:assert assertion-atom]` checkpoint) is
applied at script-resolution time. The `run-variant` lifecycle drives the
**plan's `[:world :scripts]`** (folded by the compiler's `normalize-scripts`,
rf2-5x1wt.22); the live-canvas auto-run + step-debugger paths fold at their
own resolution sites (`runner-events/variant-plays` / `variant-play-script`
/ `play/variant-play-steps` all run `assertions/fold-script`). Either way
the runtime drives the ONE assertion atom in its checkpoint position —
there is **no synthetic `:rf.assert/db` / `:rf.assert/dom` rail**. A folded
`:assert-db` dispatches the real
`:rf.assert/path-equals` (or, for the `:pred` form, `:rf.assert/path-matches`
wrapping a `[:fn …]` schema — a `[:fn sym]` symbol resolves at validation
time) handler, which records the canonical record; a folded `:assert-dom` is
evaluated by the DOM executor and records a canonical `:rf.assert/dom-*`
record. One assertion-record vocabulary, one recorder.

**Test mode and CI consume the unified result.** The Story Test mode
aggregation (`ui.state.tests/aggregate-summary` / `record-test-run` /
`test-summary`) buckets by each record's unified `:status` and counts
`:cannot-run` distinctly (the sidebar dot renders pass / fail / cannot-run /
running / pending). The CI runner's terminal-state read recognizes
`:cannot-run` as a terminal verdict (`ci-runner/terminal?`), and the play
runner's `finish` computes `:pass` / `:fail` / `:cannot-run` by the same
aggregation rule (a run whose only non-pass steps are refusals is
`:cannot-run`).

**The `story/is` → clojure.test / cljs.test bridge.** `(story/is target
opts)` runs `target` and REPORTS each assertion to clojure.test / cljs.test
at per-assertion granularity. The pure projection
`result/result->reports` turns a unified result into the ordered vector of
report maps — one `{:type :pass|:fail|:error …}` per assertion record, plus
a trailing run-level report when the verdict is not covered by a single
assertion (a tape-floor `:fail`, a run-level `:cannot-run`, or a vacuous-
green `:pass` so a zero-assertion run still emits one positive signal). A
`:cannot-run` assertion reports `:fail` (the runner proved nothing — never a
silent pass). On the JVM (the canonical headless test gate) `story/is`
blocks on the run promise and fires `clojure.test/do-report` synchronously,
returning the unified result; on CLJS — where the run is async — it returns
the promise (chain `then`, or use the `cljs.test` `(async done …)` form and
call `story/report-result!` when it resolves). `report-result!` is the pure-
report seam both runtimes share.

## Public execution API — the three verbs

The primary API is **three verbs** dispatching on target type — a keyword
is a registry lookup, a map is an inline plan:

```clojure
(story/run     target opts)    ; -> run-result (or host promise/future of one)
(story/is      target opts)    ; -> reports to clojure.test / cljs.test, per-assertion granularity
(story/explain target)         ; -> explain map
```

This collapses the potential
`run-variant` / `is-variant` / `run-plan` / `is-plan` surface into three
verbs. Only `run-variant` ships today; the point is to avoid multiplying
public execution entry points as inline plans arrive. The variant-vs-plan
distinction is real for *authoring* (`reg-variant` registers; an inline
map does not) but is spurious at execution and MUST NOT leak into the
verbs. `(variant-plan target)` returns the normalized plan for either
target kind; `(story/explain target)` explains either a registered
variant or an inline map.

P1 run/is opts:

```clojure
{:runner :headless | :hiccup | :cljs-reactive | :dom | :browser | :auto
 :escalate boolean              ; synonym for :runner :auto when true
 :frame-binding :fresh | :attached
 :platform :client | :server}
```

Default `:runner` is `:headless` for test execution. Story UI rendering
uses `render-variant`, not a browser-tier test run by default.

## Explain API

`explain` MUST show:

- source chain;
- parent chain;
- composed fragments/checks;
- field-level merge decisions;
- strict conflicts, including variant-owned wins and unresolved
  sibling-fragment conflicts, with winning/losing sources + reasons;
- args and substitutions;
- view arg schema and effective-args validation;
- final setup order;
- final script order;
- checks and expanded assertions;
- runner requirements and chosen runner, plus any inherited-check unmet
  requirement or explicitly requested richer runner attributed to its
  originating check;
- schema expectations;
- platforms and tags.

This API is required before composition is considered complete.
Composition without explanation is hidden global state.

## Epoch tape and narrative

The `=`-comparable epoch tape is the asset no Storybook/Playwright stack
can copy. P1 makes it first-class:

- Every run-result MUST include the **scrubbable `:narrative`
  projection** (§Run result): author script steps form spans, and epoch
  beats inside each span carry `:db-before`, `:db-after`, `:effects`,
  `:sub-runs`, `:renders`, `:trace-events`, and the triggering
  event/dispatch id.
- Schema failures, warnings, effects, semantic diffs, and run artifacts
  SHOULD be projected from the same epoch tape instead of separately
  accumulated. The concrete projection boundary — epoch records to
  run-result slots, the two-level narrative, and the green-while-red
  agreement floor — is pinned in [§Run-result evidence
  projection](#run-result-evidence-projection).
- Golden slices (the deferred P1.5 surface) are now landed: a curated run
  may be frozen as a `:rf.test/golden` slice — the `canonicalize`d
  behavioural surface — and a later run asserted to canonicalize `=` to it.
  The contract is pinned in [§Golden slices](#golden-slices); it builds
  directly on the now-proven canonicalization (the determinism gate +
  semantic diff are its adversarial corpus).

Combined with `restore-epoch`, this projection is the spine of **both**
Test mode and Docs mode: the same evidence produces the test result and a
scrubbable causal storyboard. This is the move that fuses the testing and
storytelling halves. The pure navigation backbone — `narrative-beats` /
`beat-count` / `beat-at` / `beat-epoch-ids`, flattening the span tree into
the ordered beats a scrub steps through and the `restore-epoch` targets it
hands them — ships in P1 (§Run-result evidence projection). The scrub
**UI** that drives `restore-epoch` from a slider is the layer above this
boundary and is deferred.

## Canonicalization

`(story/canonicalize result)` is the **single** primitive that
determinism, semantic-diff, snapshot-identity, `:plan-hash` / `:run-hash`,
future golden-slice comparison, and the inline-plan-to-registered-variant
metamorphic relation all consume. Implementation MUST live in a
fingerprinting namespace, not `re-frame.story.canonical` (which already
installs canonical vocabulary), and MUST fold the existing
`re-frame.story.identity` `canonical-form` / `content-hash` /
`snapshot-tuple` path into one implementation. It MUST:

- strip `:rf.story/*` accumulator keys from app-db;
- project away volatile record fields
  `{:elapsed-ms :dispatch-id :source :source-coord :runner :variant/id :plan-hash :run-hash}`
  (plus the per-run epoch / trace stamps `:epoch-id :trace-id :committed-at :schema-digest`);
- impose a total per-slot ordering (effects = emission order; sub-runs =
  topo-then-id; epochs = dispatch order);
- **type-tag the canonical form** so the four collection kinds are
  mutually distinguishable — a map, set, vector, and seq each wrap under a
  reserved structural tag (`:rf/map` / `:rf/set` / `:rf/vec` / `:rf/seq`), so
  `{}` ≠ `[]` ≠ `#{}` and `{:k 1}` ≠ `[:k 1]` canonically (rf2-lvrqa);
- **fold functions to the `:rf/opaque-fn` sentinel** so a hashed slice
  carrying a fn (a `:fx-overrides` plan slot, an app-db
  closure-as-value, an effect `:args` callback) hashes DETERMINISTICALLY
  across processes rather than embedding the fn's object identity via
  `pr-str` (rf2-4gwja) — the deliberate trade-off is that two values
  differing ONLY in fn identity hash equal (determinism is the contract);
- **normalise host-divergent numbers to a bit-stable form** so the
  cross-host byte-stability contract holds for scalars too (rf2-vvqeo).
  Integers pass through verbatim (host-identical `pr-str`, so existing
  hashes do not rebase), but two number sub-kinds are NOT host-portable
  through raw `pr-str` and MUST be normalised: a **ratio** (JVM
  `clojure.lang.Ratio`; CLJS has none, reading `1/3` as the double 0.333…)
  is coerced to its double value, and a **fractional or special double**
  folds to `[:rf/double "<16-hex IEEE-754 bits>"]` — the bit pattern is
  host-invariant for a given logical double, where `pr-str` formatting
  ("1.0" vs "1", "1.0E21" vs "1e+21") is not. An integer-valued double
  within 64-bit integer range folds to that integer (host-mandated: in CLJS
  `1.0` IS the integer `1`, so the only cross-host-stable choice is to treat
  a double `1.0` and the integer `1` as canonically equal). `##NaN` folds to
  the single `:rf/nan` sentinel — collapsing every NaN bit-pattern to one
  value AND giving the set/map `pr-str` sort a deterministic order in NaN's
  presence (NaN is not `=`-reflexive, so a bare `(sort-by pr-str)` was
  comparator-unstable). `##Inf` / `##-Inf` ride the `:rf/double` bit path
  (their bits are host-stable). Set element ordering additionally uses a
  **total comparator with a deterministic equal-`pr-str` tiebreak**, so two
  distinct elements that canonicalise to the same `pr-str` (e.g. two
  `:rf/opaque-fn` sentinels) get a stable relative order rather than the
  comparator-unstable order a bare `(sort-by pr-str)` left;
- enumerate the `:plan-hash` input fields;
- compute `:run-hash` over the canonical epoch slice.

It MUST reconcile the shipping snapshot tuple — whose `:rf/snapshot-canonical`
data slot reads its value straight from `fingerprint/canonical-version`
(the single version source, currently `:rf/snapshot-canonical-v2`), NOT a
hardcoded literal — and the current `:variant-id` key spelling with any
normalized-plan `:variant/id` spelling. It MUST be built **before** anything consumes it (else the
metamorphic acceptance gate is vacuous and near-duplicate canonicalizers
drift), and MUST ship with an adversarial corpus: semantic differences
must change the hash, volatile fields must not, and existing snapshot
identity baselines must have a deliberate migration path before consumers
beyond determinism/diff are wired.

### Concrete primitive contract

The primitive lives in `re-frame.story.fingerprint` (NOT
`re-frame.story.canonical`, the vocabulary installer). The former
`re-frame.story.identity` `canonical-form` / `content-hash` hashing is
folded into it; `re-frame.story.identity` now re-exports those two vars
from the fingerprint ns, so there is exactly one canonical path.

The public surface, all routed through one projection + one hash:

| Fn | Meaning |
|---|---|
| `canonicalize` | The single canonical projection of any Story value: strip the volatile-field set + `:rf.story/*` accumulator keys recursively, reconcile `:variant-id` → `:variant/id`, then impose total per-slot ordering with structural type tags (`:rf/map` / `:rf/set` / `:rf/vec` / `:rf/seq`) and the `:rf/opaque-fn` fn-fold. Re-exported as `story/canonicalize`. |
| `hash-canonical` | 8-char-hex hash of an **already-canonical** value — prepends `canonical-version` and renders `[version canonical-value]` via a SINGLE `pr-str`, with NO second `canonical-form` pass. The load-bearing primitive `content-hash` / `canonical-hash` share, and the one a caller holding a `canonicalize`d value (the determinism gate, golden) hashes through so its hash equals `run-hash` without re-canonicalizing (the type-tagged `canonical-form` is NOT idempotent). |
| `content-hash` | `hash-canonical` of `(canonical-form x)` — NO volatile strip. The low primitive the snapshot tuple hashes. |
| `canonical-hash` | `hash-canonical` of `(canonicalize x)` — the determinism / semantic-diff / run-equivalence hash. |
| `plan-hash` | `canonical-hash` over `plan-hash-input-keys` (`[:story/id :world :script :expect :required-runner :tags]`). |
| `run-hash` | `canonical-hash` over `run-hash-input-keys` (`[:status :app-db :epoch-tape :assertions :checks :effects :schema-violations :warnings :sub-overrides :fidelity]`). |

The volatile-field set stripped recursively by `canonicalize` is
`{:elapsed-ms :dispatch-id :source :source-coord :runner :variant/id
:plan-hash :run-hash :epoch-id :trace-id :committed-at :schema-digest}` —
`:run-hash` is the symmetric companion to `:plan-hash` (a run-result
carries its own `:run-hash`, which must not feed a re-canonicalization of
that result), and `:epoch-id` / `:trace-id` / `:committed-at` /
`:schema-digest` are the reserved per-run epoch / trace stamps added for
the determinism gate (§Determinism gate). The genuinely-common stamps a
trace event / epoch record also carries (`:id`, `:time`, `:frame`, and the
volatile `:tags` keys) are stripped **structurally** — only on their
carrier map — so app-db data on those keys survives. Ordering is: maps
key-sorted
by the canonicalised key's `pr-str`; sets element-sorted; vectors/seqs
(effects, epochs, trace events) keep producer order, which the producer
emits deterministically (effects in emission order, epochs in dispatch
order) — so reordering effects or epochs is a *semantic* change that
perturbs the canonical value. Each collection is wrapped under a reserved
structural type tag — a map → `[:rf/map [k v …]]`, a set → `[:rf/set [e
…]]`, a vector → `[:rf/vec [e …]]`, a seq → `[:rf/seq [e …]]` — so the four
kinds are mutually distinguishable after `pr-str` and a map<->vector or
set<->vector type flip is a *semantic* difference, never a silent
collision (rf2-lvrqa). A function value canonicalises to the stable
`:rf/opaque-fn` sentinel, never an object-identity `pr-str`, so a hashed
slice carrying a fn is deterministic across processes (rf2-4gwja). Because
the tags make `canonical-form` NON-idempotent, the hash is taken over the
canonical value ONCE via `hash-canonical` (no second `canonical-form`
pass).

`plan-hash` and `run-hash` are the same `canonical-hash` primitive applied
to enumerated slices; there is no second hash implementation. The
metamorphic relation holds: an inline plan and the normalized plan of a
registered variant describing the same behaviour produce the same
`plan-hash` regardless of provenance slots (`:plan/id`, `:variant/id`,
`:source-chain`, `:explain`, `:evidence`).

**Snapshot-identity migration path.** The rf2-5x1wt.3 fold was a pure
relocation of the hashing code; the rf2-lvrqa soundness fix (type tags +
`:rf/opaque-fn` + the `hash-canonical` single-pass hash) is a deliberate
canonical-form REVISION, so `canonical-version` bumps
`:rf/snapshot-canonical-v1` → `:rf/snapshot-canonical-v2`. Because the
version is the first hashed slot of EVERY hash, the bump re-stamps every
value `content-hash` / `canonical-hash` / `plan-hash` / `run-hash` emit,
including the snapshot content-hash. There are NO in-repo stored hash
fixtures — every consumer (the JVM + CLJS fingerprint corpus, the
story-mcp + mcp-conformance snapshot checks) asserts hash STABILITY (same
input → same hash) and SENSITIVITY (different input → different hash), never
a pinned hex literal — so the only baselines the bump invalidates are
EXTERNAL visual-regression baselines, which re-capture on their next run.
Pre-alpha, that re-stamp is cheap, and the v1 → v2 bump is exactly the
signal that drives it. The volatile strip + `:variant-id` reconciliation
still apply only on the `canonicalize` / `canonical-hash` path (determinism,
diff, `:plan-hash`, `:run-hash`); the snapshot tuple keeps its `:variant-id`
slot. A `content-hash` consumer keeps variant-id sensitivity; a
`canonical-hash` consumer treats variant-id as volatile.

The adversarial corpus ships in
`re-frame.story-fingerprint-test` (JVM, the full corpus) and
`re-frame.story-fingerprint-cljs-test` (host-portability): paired
volatile-only twins MUST canonicalize `=` and hash equal; paired
single-field semantic twins (app-db, effect, assertion verdict, status,
epoch db-after, warning) MUST canonicalize `not=` and hash unequal.

## Network world

The author-facing `:network` surface and its `[method url] → {:reply …}`
shape are introduced in §The network surface and §Network stubs. This
section pins the **compiler contract** for the first-class `:network`
world slot (rf2-5x1wt.14) — the lowering to the managed-request stub
machinery, the conflict semantics versus generic `:fx-overrides`, and the
explain / `:plan-hash` participation.

### Reuse, not reinvention

`:network` MUST lower to the existing managed-request stub helper
`re-frame.http.test-support/install-managed-request-stubs!` (Spec 014
§Testing) — Story does NOT introduce a second HTTP mock. That helper takes
the same `{[method url] {:reply <:ok|:failure>}}` route map the author
writes, registers a per-call stub fx (id `:rf.http/managed-test-stub`),
and returns the fx id; an unmatched managed request synthesises the
helper's existing "no stub matched" transport failure (fail-closed). The
helper lives in the `day8/re-frame2-http` artefact; Story declares it as a
runtime dependency (the stub install is a runtime concern for running a
`:network` variant, so the dep rides Story's main `:deps`, not the `:test`
alias).

### What the compiler emits

When a variant's resolved (merged + arg-substituted) `:network` route map
is non-empty, `re-frame.story.plan/compile-body`:

| Plan slot | Value | Why |
|---|---|---|
| `[:world :network]` | the per-route reply map, verbatim | the **source of truth** — feeds `:plan-hash` (through the `:world` slot of `plan-hash-input-keys`), `explain`, and the narrative/run-artifact evidence |
| `[:world :frame :fx-overrides]` | the author's frame overrides **merged with** `{:rf.http/managed :rf.http/managed-test-stub}` | the **lowering** — points the variant frame's `:rf.http/managed` at the stub fx; the runner installs the route map via `install-managed-request-stubs!` when it creates the frame |
| `[:explain :network]` | `{:routes <route-map> :lowered-to {:rf.http/managed :rf.http/managed-test-stub}}` | per-route stubs + the managed-stub lowering are visible in `explain` |

`re-frame.story.plan/managed-fx-id` (`:rf.http/managed`) and
`re-frame.story.plan/managed-stub-fx-id` (`:rf.http/managed-test-stub`)
name the two fx ids so the plan declares the lowering without a
compile-time dependency on the http artefact; `lower-network` is the pure
data → data primitive that derives the override map. The
per-route reply data may carry `[:arg key]` placeholders, substituted on
the same pass as setup / script / sub-overrides; an undeclared arg FAILS
plan construction with `:rf.error/story-missing-arg`.

Because `:network` is world context, it **inherits through `:extends`**
(deep-merged root → child): a child variant's routes merge over the
parent's, and the single managed-stub override covers the inherited and
own routes alike.

### Conflict with generic `:fx-overrides`

`:network` is the dedicated affordance for `:rf.http/managed`; generic
`:fx-overrides` still serve every non-HTTP effect and the unusual cases.
When both `:network` and an explicit author `:fx-overrides` target
`:rf.http/managed`, the compiler MUST FAIL plan construction with
`:rf.error/story-network-fx-conflict` (carrying the `:variant/id`, the
conflicting `:fx-id`, the `:network` routes, and the author
`:fx-overrides`). Letting one silently win would flatten exactly the
route-level intent `:network` exists to preserve, so the two-owner case is
a hard error the author resolves by dropping one surface. An
`:fx-overrides` on any **other** fx coexists cleanly — it merges alongside
the derived managed-stub entry.

### Plan-hash participation

`:network` participates in `:plan-hash` with no extra wiring: the route
map lives under `[:world :network]`, and `plan-hash-input-keys` already
hashes the whole `:world` slot. A semantic change to any per-route reply
(success payload, failure `:kind`, status) therefore perturbs the
`:plan-hash`; a `canonicalize`-volatile change does not.

**Run-artifact wiring (rf2-tymyh).** The per-route reply data is
preserved in `explain`, `:plan-hash`, (through `[:world :network]`) the
narrative evidence, AND the low-level run artifact. When a plan is
coerced to a `:rf.test/run-artifact` (the determinism gate / golden
capture's `re-frame.story.determinism/->artifact`), its
`[:world :network]` route map is carried onto the artifact's `:network`
slot alongside the `[:world :frame :fx-overrides]` redirect on
`:fx-decisions`. `replay-run-artifact` then **re-installs** those route
stubs (`re-frame.http.test-support/install-managed-request-stubs!`, the
same seam a live run uses) around the replay, so a replayed `:network` request
matches its route and synthesises the recorded reply rather than
fail-closing on "no stub matched". Carrying only the `:fx-decisions`
redirect would point the replay at a stub fx registered with NO routes,
so every replayed request would fail-closed — the `:network` artifact
slot is what makes the round-trip succeed (§Run artifact and replay).

## Unit and integration testing adjustments

The general testing-substrate counterpart of these additions
(inline-plan execution surface, invariant sentinels, first-bad-epoch,
run-artifact replay/determinism, and the cookbook) is owned by
[`spec/008-Testing.md`](../../../spec/008-Testing.md) §Story plan
execution surface and evidence tools — those tools live **below** Story
and run without the Story UI. Story consumes them; it does not own them.

Equivalent inline plans and registered variants SHOULD be testable as a
**metamorphic relation**: when they describe the same behaviour and
runner, they MUST produce the same final app-db and assertion records
after `canonicalize`.

## Invariant sentinels

The epoch tape is also the substrate for **invariant sentinels** — the
"this MUST hold after every committed epoch" assertion form (NewTestStory
§A1 / §A2). Two surfaces share one notion of an invariant: a live
fixture (`with-invariants`) and a pure post-hoc utility
(`first-bad-epoch`). Both live in `re-frame.story.invariants` and both
evaluate through `check-epoch` (the one per-epoch evaluation primitive),
so a violation the live sentinel reports and the violation
`first-bad-epoch` returns are computed identically — the live and post-hoc
verdicts agree by construction.

### What an invariant is

An invariant is a predicate that MUST hold after every committed epoch.
It reads one `:rf/epoch-record` — its settled `:db-after`, its
`:trigger-event`, its `:trace-events` — and answers "is the world still
well-formed here?". Authors MAY express an invariant as:

- a bare 1-arg fn `(fn [epoch] truthy?)` over the whole epoch record;
- a `{:id … :check (fn [epoch] …)}` map (`:pred` is accepted as an alias
  for `:check`);
- a `[:db path pred]` / `[:db path = expected]` data shorthand for the
  common "a value at an app-db path satisfies a predicate / equals a
  literal" case — so the most frequent invariant stays data-shaped
  (matching the §Story-variant data-shaped posture) and carries
  `:path` / `:expected` / `:actual` onto the violation report.

### `with-invariants` — the live sentinel

```clojure
(test/with-invariants [invariant-spec …] body…)
```

`with-invariants` registers ONE epoch listener (via
`re-frame.core/register-epoch-listener!`) before `body` runs, checks
every invariant after EACH committed epoch, and on exit unregisters the
listener — even if `body` throws. It:

- reports each violation through `clojure.test` / `cljs.test` (via
  `do-report`, the same non-throwing channel
  `re-frame.test-support/assert-path-equals` uses), so a violation
  registers a counted failure;
- reports each violation **exactly once per failing epoch** — a
  re-fire of the same record (a back-filled render re-notify, §Run
  result) is de-duplicated on the `[invariant-id epoch-id]` key;
- reports a `:pass` for every invariant that held across the run, so a
  green sentinel is visible rather than silent;
- **NEVER throws from the epoch listener**: a violated OR a broken
  (throwing) predicate is caught and reported, and the run continues.
  Isolation is twofold — `register-epoch-listener!` already isolates
  listener exceptions (Spec 009 §`register-epoch-listener!`), and the
  per-epoch check itself catches predicate exceptions and reports them
  as violations carrying the isolated `:error`.

The sentinel observes every frame's epochs for the duration of `body`;
scope an invariant to one frame by reading `(:frame epoch)` inside its
`:check`. It works with fresh and destroyed frames: a frame destroyed
mid-`body` simply commits no further epochs, and the listener is removed
on the way out regardless.

### `first-bad-epoch` — the pure post-hoc utility

```clojure
(test/first-bad-epoch epoch-tape invariant)
```

`first-bad-epoch` is **pure** — a retained epoch tape and an invariant
(any of the authored shapes above) in, a result map or `nil` out. It
walks the tape forward and returns the **first** epoch where the
invariant fails, enriched with the spec's named slots — the failing
`:epoch` record verbatim, the `:trigger-event`, a shallow top-level
`:db-diff` (the set of changed app-db keys), and the epoch's
`:trace-events` when retained — or `nil` when the invariant holds across
the whole tape. An empty (or `nil`) tape returns `nil`; a failure in the
first epoch returns that epoch. Because it never touches the runtime, it
runs under `clojure -M:test` with no host, and the future Story UI
first-bad-epoch view (§Story UI requirements) reads exactly this
projection.

## Story UI requirements

P1 Story UI MUST:

- run a variant through the plan runner;
- show pass/fail/cannot-run/error distinctly;
- show check and assertion rows;
- show source coords when available;
- expose or link to `explain`;
- preserve current Test mode behaviour while adopting the shared run
  result.

Future Story UI SHOULD show a cascade timeline scoped to the current
variant, first-bad-epoch, app-db/effect/trace diffs, the scrubbable
`:narrative` projection (with `restore-epoch`), and richer
browser/visual/a11y runner output when requested.

## Visual, a11y, and browser checks

Visual, a11y, and browser checks are not a separate testing system. They
are runner-tiered assertions on the same Story variant or inline plan and
MUST opt into their required runner:

- visual snapshots require `:browser` (`:pixels`);
- axe-style a11y checks require `:browser` (`:a11y-engine`);
- structural a11y checks MAY require only `:hiccup`;
- DOM interaction checks require `:dom` or `:browser`.

They SHOULD reuse the same run-result shape, and SHOULD pair pixel/DOM
findings with app-db, args, trace, and epoch evidence when possible.

### The browser-tier assertion ids (rf2-5x1wt.28)

Three assertion ids cover the visual / a11y surface; each carries its
capability requirement through the requirement registry
(`re-frame.story.requirements/assertion-capabilities`) and is recognised by
the plan compiler (`re-frame.story.assertions/known-assertion-ids`):

| Assertion | Token | Runner | Proof |
|---|---|---|---|
| `:rf.assert/visual-snapshot` | `:pixels` | `:browser` | real-browser screenshot + pixel diff (or the reused `content-hash` snapshot identity) |
| `:rf.assert/a11y` | `:a11y-engine` | `:browser` | axe-style scan (reuses the `re-frame.story.ui.a11y` axe-core hook) |
| `:rf.assert/a11y-structural` | `:hiccup-structure` | `:hiccup` | pure structural a11y facts over the rendered hiccup tree |

`:rf.assert/visual-snapshot` and `:rf.assert/a11y` are browser-only. The
NET-NEW `:rf.assert/a11y-structural` is the spec's *structural a11y checks
MAY require only `:hiccup`* rung: it inspects the rendered hiccup TREE
(data) for structural facts — an `:img` with no `:alt`, an interactive
control with no accessible name, a positive `:tabIndex` — without a real
browser or the axe engine. Because the hiccup tree is data, it is fully
JVM-testable and the `:hiccup` runner satisfies it. Semantic checks that
genuinely need layout/contrast (colour contrast, computed visibility) stay
on `:rf.assert/a11y`.

### The executor: reuse, not a second system

The browser-tier executor (`re-frame.story.play.browser`) is the SAME
shape as the DOM executor (`re-frame.story.play.dom`): pure-data evaluators
with a single host probe (`browser-available?`) isolated at the edge. It
produces the ONE assertion record (§Run result — Assertion record) every
other assertion produces — a visual/a11y finding rides the EXISTING
assertion-record accumulator, NOT a parallel result accumulator. **No new
run-result slot is added**; a finding is an assertion record like any
other, so Test mode, CI, `clojure.test`, and MCP read it through the
unified result with no special-casing.

The executor REUSES the two seams the tree already ships rather than adding
a differ or a second axe loader:

- visual — `re-frame.story.identity/snapshot-identity` (the `content-hash`
  visual-regression KEY the MCP `snapshot-identity` tool already surfaces)
  is the snapshot identity the finding records; a real pixel diff lands
  with the `:pixels` browser runner;
- a11y — `re-frame.story.ui.a11y` (the in-browser axe-core panel + its
  `violations-by-frame` atom the MCP `read-a11y-violations` tool already reads). The
  CLJS-only panel REGISTERS its violations-reader into a one-way late-bound
  seam (`register-a11y-reader!`); the below-the-UI `.cljc` executor reads
  through it without a compile-time dependency on the UI ns (the
  bundle-isolation rule: nothing in a production path `:require`s the UI).

### Run-path routing (rf2-9ikj0)

The browser-tier executor is reached from the run path the SAME way the DOM
family is: the in-script `[:assert …]` executor
(`re-frame.story.play.runner-events`) routes a browser-tier oracle atom to
`browser/eval-browser-assertion` and records its canonical assertion record
on the frame's accumulator (the terminal `:assertions` route through the
SAME executor). It is NOT classified as a tape-evaluated family — it has its
own executor, so a `:rf.assert/a11y-structural` checkpoint EVALUATES rather
than recording a no-op skip.

`:rf.assert/a11y-structural` walks the rendered hiccup TREE, which the run
path supplies through the `:render-hiccup` late-bind seam (`frame-id →
hiccup-tree`): a `:hiccup`-or-richer host installs it so the structural-a11y
check runs on the normal test path. When NO tree is available (the bare
headless floor — no `:render-hiccup` host), the check FAILS CLOSED to
`:cannot-run` rather than passing vacuously over a nil tree — the honesty
floor (§`:cannot-run`). The browser-only pair (`:rf.assert/visual-snapshot`
/ `:rf.assert/a11y`) record `:cannot-run` headless through their own
`browser-available?` guard.

### Fail-closed: headless returns `:cannot-run`

A browser-tier assertion under a runner that lacks its token resolves to
`:cannot-run` — the distinct THIRD status, never a silent pass
(§`:cannot-run`). This is enforced on two sides: the capability gate
(§Runner requirements) refuses a `:pixels` / `:a11y-engine` requirement
under a headless runner at selection time, and the executor itself returns
a `:cannot-run` record when `browser-available?` is false (JVM, node, or a
CLJS REPL with no `js/window`). A headless run therefore returns
`:cannot-run` for `:rf.assert/visual-snapshot` and `:rf.assert/a11y`; the
structural check runs at `:hiccup`.

## CI policy

CI MUST keep cheap headless plan tests on the normal path. CI SHOULD run
browser/visual/a11y checks only for variants that require them, or in
dedicated gates.

Every CI gate MUST define `:cannot-run` policy: fail the gate; report
inconclusive; or route to a richer runner.

The Story play/script gate MUST continue to exist under a current name.
If `test:story-play-scripts` is renamed, docs and package scripts MUST be
updated together. Browser-tier visual/a11y gates MUST run only variants
whose assertions require them, or a deliberate selected subset.

### Failed-run artifacts in CI

A failed plan run MAY emit its `:rf.test/run-artifact` (§Artifacts — Run
artifact, §Run artifact and replay) so the failure is replayable off-CI.
The artifact is the serializable, data-shaped record of the one run —
seed, event program, fx decisions, epoch tape, trace, and run-result —
enough to replay it deterministically (§Run artifact and replay) and to
feed the determinism gate (§Determinism gate) and the semantic diff
(§Semantic diff). A gate MAY capture the artifact for a failed run and
upload it as a CI artifact; it MAY also be promoted into a curated
regression variant via `story/promote-run-artifact!` (§Promotion). The
substrate GUARANTEES the artifact exists and is canonicalizable; wiring
an upload step into a specific workflow gate is a CI-mechanics decision,
NOT part of this spec.

### Browser-tier gate policy (rf2-5x1wt.28)

The structural-a11y check (`:rf.assert/a11y-structural`, `:hiccup`) carries
NO browser dependency, so it runs on the NORMAL test path — the executor's
structural-issue walk is a pure JVM/CLJS test (`clojure -M:test` /
`npm run test:cljs`), needing no dedicated browser gate. The
browser-only ids (`:rf.assert/visual-snapshot` / `:rf.assert/a11y`) require
a real-browser runner (`:pixels` / `:a11y-engine`); their gate runs ONLY
the variants whose assertions require those tokens (the `:required-runner`
union selects them), or a deliberately curated subset — never the whole
corpus dragged to `:browser`.

The `:cannot-run` policy for the browser-tier gate is **route to a richer
runner**: a variant carrying a `:pixels` / `:a11y-engine` assertion is
selected INTO the browser gate (where it can be proven), rather than being
failed or reported inconclusive on the cheap headless path. On the cheap
headless path a browser-tier assertion is `:cannot-run` and the gate's
policy there is **report inconclusive** (the headless gate proves the
headless floor; it does not fail a run merely for carrying a browser-tier
assertion it was never meant to attempt). This split keeps the cheap path
cheap and confines real-browser cost to the variants that genuinely need
it.

This is the normative POLICY; the concrete CI workflow that wires a
dedicated browser-tier gate (selecting variants by `:required-runner` and
running them under a `:pixels` / `:a11y-engine` runner) is a separate
piece of work and is NOT part of this spec change.

## Promotion

Required promotion API:

```clojure
(story/materialize-variant-plan artifact opts) ; -> normalized plan
(story/promote-run-artifact! artifact {:variant/id :story.checkout/regression-042})
```

Promotion MUST preserve a link to the source artifact; produce
data-shaped variant/plan content; make setup/script/checks/assertions
readable; and avoid registering every generated failure automatically.

Projection rule: replayable event programs become `:script` when they
represent behaviour under test, and `[:world :setup]` when they are only
preconditions. The recorder/codegen output SHOULD share this contract, so
a recorded interaction and a promoted failure are indistinguishable as
authored variants.

### Promotion bridge

The promotion API ships in the `re-frame.story.promotion` namespace
(re-exported on the `re-frame.story` facade). It is two functions with a
deliberate purity split:

```clojure
(story/materialize-variant-plan artifact opts)
;; -> a readable, normalized variant plan. PURE — registers NOTHING.

(story/promote-run-artifact! artifact {:variant/id :story.checkout/regression-042})
;; -> registers a NAMED variant. The ONLY function that registers.
```

`materialize-variant-plan` projects a run artifact into the four-bucket
authoring shape and compiles it through the `variant-plan` compiler
(§Variant plan) read-only, returning a normalized `:world` / `:script` /
`:expect` plan. It is side-effect-free, so a tool or author MAY
materialize a plan to READ what a promotion would produce before deciding
to commit it.

`promote-run-artifact!` is the single registering entry. It MUST be
called with an explicit `:variant/id`; a missing id is an error
(`:rf.error/story-promote-no-id`), never a silent default. There is no
auto-register path — a generated failure becomes a curated variant ONLY
through this named call. The function builds the same variant body
`materialize-variant-plan` compiles and writes it into the Story
side-table via the `reg-variant` write path (shape validation, `:extends`
resolution, source-coord stamping). The registered variant is
indistinguishable from a hand-authored one except for its `:run-artifact`
provenance slot. Production builds (`config/enabled?` false)
short-circuit before the write.

**Setup/script projection.** The artifact's `:event-program` is one
ordered program; the promotion policy in `opts` selects where the cut
between PRECONDITION (`[:world :setup]`) and BEHAVIOUR-UNDER-TEST
(`:script`) falls:

- `:setup` + `:script` — an explicit partition, used verbatim.
- `:setup-count n` — the first `n` steps are preconditions; the rest are
  behaviour (clamped to the program bounds).
- neither (default) — the whole program is behaviour (`:script`); nothing
  is demoted to a silent precondition without a hint. This is the
  conservative reading: a captured run IS the behaviour the artifact
  recorded.

**Source-artifact link.** Both the materialized plan and the promoted
variant body preserve a back-link to the source artifact under the
`:run-artifact` slot — the same slot a replay run-result carries (§Run
result), so provenance reads the same everywhere. The link is a TRIMMED
provenance view: the replayable + identifying core (`:artifact/kind`,
`:seed`, `:event-program`, `:fx-decisions`, `:shrink-path`,
`:created-at`, `:source`) WITHOUT the bulky captured evidence
(`:epoch-tape`, `:trace`, `:result`). A curated variant can explain where
it came from and re-derive the run; it does not drag a full epoch tape
into the registrar side-table.

## Run artifact and replay

The `:rf.test/run-artifact` shape (§Artifacts — Run artifact) is the
serializable, data-shaped record of ONE run: enough to replay it
deterministically and to project the same evidence a fresh run would
produce. Replay lives **below** Story — it runs without the Story UI — and
is the foundation the determinism gate and semantic diff build on. The
base schema + the replay function ship in the
`re-frame.story.artifact` namespace.

### The base schema

A run artifact is a map carrying:

```clojure
{:artifact/kind :rf.test/run-artifact  ; the discriminating tag
 :seed          optional
 :event-program [step …]               ; the dispatch program (setup ⧺ script)
 :fx-decisions  {fx-id override}       ; the fx overrides reapplied on replay
 :network       {[method url] {:reply …}} ; per-route HTTP stubs re-installed on replay
 :epoch-tape    [epoch-record …]       ; the captured tape, when retained
 :trace         [trace-event …]        ; optional flat trace
 :result        run-result             ; the projected run-result (§Run result)
 :shrink-path   optional
 :created-at    instant-or-string
 :source        optional}
```

- `:artifact/kind` is `:rf.test/run-artifact`, distinguishing a run
  artifact from a normalized plan or a curated variant body.
- `:event-program` is a vector of **tagged** script steps — the same
  grammar the play runner executes (§Script step grammar). A bare event
  vector lifts to `[:dispatch …]` through the runner's `coerce-script`, so
  a recorder MAY hand a flat event list and get a legal program. Setup and
  script fold into ONE ordered program (setup first); replay re-runs the
  whole program, then projects the captured tape. A dispatch step MAY carry
  a trailing opts map holding its captured flat `:rf.cofx` envelope —
  `[:dispatch evec {:rf.cofx {…}}]` — so a recorded recordable coeffect
  (the framework `:rf/time-ms` plus any provided recordable facts, EP-0017)
  rides the artifact and re-presents verbatim on replay (below). A step
  with no captured coeffects stays the bare 2-element shape (zero ceremony).
- `:fx-decisions` is the `{fx-id override}` map of fx decisions applied at
  capture time — the same shape the dispatch `:fx-overrides` opt and the
  per-frame frame-config `:fx-overrides` slot carry (Spec 002
  §`:fx-overrides`). Replay REAPPLIES them, so a run that stubbed an HTTP
  effect replays against the same stub rather than firing the live effect.
- `:network` is the per-route HTTP reply map (`{[method url] {:reply …}}`,
  §The network surface) captured from the plan's `[:world :network]` slot
  when the artifact was materialized (rf2-tymyh). The `:network` world slot
  lowers to a `:fx-decisions` redirect (`{:rf.http/managed
  :rf.http/managed-test-stub}`) — but that redirect targets a stub fx that
  is only registered WITH the routes by
  `install-managed-request-stubs!`. So `:fx-decisions` alone is not enough:
  replay carries `:network` to RE-INSTALL the route stubs (below), without
  which a replayed `:network` request would fail-closed.

`re-frame.story.artifact/make-run-artifact` is the **pure** constructor
(data → data, JVM-runnable): it coerces the `:event-program`, folds
optional `:setup` / `:script` sugar into it, stamps `:artifact/kind`, and
defaults `:fx-decisions` to `{}`. `run-artifact?` is the recogniser (the
`:artifact/kind` tag plus a vector `:event-program`). `program-events`
projects the artifact to its ordered dispatched event vectors (for
promotion + diagnostics).

### `replay-run-artifact`

```clojure
(story/replay-run-artifact artifact)
(story/replay-run-artifact artifact opts)
;; -> run-result (§Run result)
```

Replay MUST:

- **Replay the dispatch program into a FRESH frame** — a unique
  `:rf.test.replay/*` frame allocated for the replay (or the caller's
  `:frame`), so the replay never observes a sibling run's app-db. A frame
  the replay allocated is torn down before return; a caller-supplied
  `:frame` is left intact (the caller owns its lifecycle).
- **Reapply the fx decisions / overrides** — the artifact's
  `:fx-decisions` ride the per-call `:fx-overrides` on every replayed
  dispatch, routed through the **same** `settled-boundary`
  (§Script and `settled-boundary`) a live run uses. Replay never reaches
  for `dispatch-sync` directly; the boundary's `:cannot-run` / `:error`
  refusals fire unchanged, so a step that needs a richer boundary than the
  replay runner provides refuses rather than under-flushing.
- **Replay under STRICT recordable-coeffect mint policy** — every replayed
  dispatch stamps `:rf.cofx/mint-policy :strict` (EP-0017 §6 binding point
  1; Tool-Pair §Replay). Replay is the faithful re-run of a captured run,
  not authoring: a generator-backed recordable fact ABSENT from the record
  fails loudly (`:rf.error/missing-required-cofx`) rather than minting a
  fresh, divergent host value mid-replay. When a dispatch step carries its
  captured `:rf.cofx` envelope (above), replay re-presents it verbatim
  under the per-call opt, so a handler that declares `:rf.cofx/requires`
  reads the recorded provided facts (and the recorded `:rf/time-ms`)
  instead of restamping. The strict opt and the recorded envelope ride the
  per-call dispatch opts THROUGH the same `settled-boundary` `:dispatch!`
  seam the fx decisions wrap — so both survive whatever dispatch path the
  (possibly richer-adapter) runner owns. A bare step (no recorded
  envelope) still replays strict; with no declared recordable fact the
  policy is inert (zero ceremony).
- **Re-install the `:network` route stubs** (when the artifact carries a
  non-empty `:network` map) — replay calls
  `re-frame.http.test-support/install-managed-request-stubs!` with the route
  map for the duration of the replay (then uninstalls in a `finally`), the SAME seam a
  live `:network` run uses. This registers `:rf.http/managed-test-stub`
  WITH the routes, so the `:fx-decisions` redirect resolves to a stub that
  matches each request and synthesises the recorded reply. Without this a
  replayed `:network` request would fail-closed on "no stub matched"
  (rf2-tymyh). The install routes through `re-frame.http.test-support`
  (Spec 014 §Testing) and raises `:rf.error/http-artefact-missing` if that
  namespace is absent — the same opt-in a live `:network` run requires.
- **Capture a NEW epoch tape** — read from `re-frame.core/epoch-history`
  after the program settles, NOT the artifact's captured tape. The replay
  proves what the program does NOW.
- **Return the shared run-result shape** (§Run result) — the tape is
  projected through the single evidence boundary
  (`re-frame.story.play.evidence/project-evidence`,
  §Run-result evidence projection), so `:status`, `:epoch-tape`,
  `:schema-violations`, `:warnings`, `:effects`, `:sub-runs`, `:renders`,
  and the two-level `:narrative` all derive from ONE tape. The result
  carries a back-link `:run-artifact` to the replayed source.

The replay `:status` follows the agreement floor
(§Run-result evidence projection): `:cannot-run` if any step refused,
`:error` if any step or the tape errored, `:fail` if the tape carries
unconsumed failure evidence, `:pass` otherwise — computed from the
PROJECTED evidence and the per-step settle outcomes, never a sibling
accumulator, so a replay cannot read green while the tape is red.

`opts` MAY carry `:frame` (replay into a caller-owned frame),
`:hooks` (richer settled-boundary flush-hooks — a `:dom` adapter declares
`:provides :dom`; the fx decisions still wrap its `:dispatch!`), and
`:frame-config` (extra `reg-frame` config for the allocated frame).

The replay result is **stable + canonicalizable** (§Canonicalization): it
feeds cleanly through `canonicalize` / `run-hash`, so the determinism gate
(`test/assert-deterministic`) and semantic diff (`test/diff-run-artifacts`)
build directly on it. Construction and result projection are pure, so the
artifact schema + the result shape are exercised under `clojure -M:test`
with no runtime; the headless replay path settles synchronously to a fixed
point via the headless flush-hooks, so the live-frame replay also runs
headless.

## Generated runs and artifacts

A property-style Story run GENERATES an event program from a seed, replays it
(§Run artifact and replay), and judges the result. Most generated programs
pass and are throwaway; a failing one is the interesting case. Generated runs
**emit artifacts FIRST**: every generated run produces a `:rf.test/run-artifact`
carrying the `:seed` it was generated from (and, for a shrunk failure, the
`:shrink-path`), so a generated failure is replayable off-CI and promotable
into a curated regression variant (§Promotion). The artifact also carries the
CONCRETE `:event-program` — the host-portable tagged-step data — and
cross-host replay (author on a CLJS browser, CI gate on JVM) rides THAT
program, not the raw `:seed` (the `:seed` reproduces the program only WITHIN
the host that generated it — see §Generator-agnostic). The `:seed` /
`:shrink-path` artifact slots already exist (§Artifacts — Run artifact); the
generated-run producer fills them. **Curated promotion only** — a generated
failure becomes a named variant ONLY through the explicit
`promote-run-artifact!` call (§Promotion), never automatically.

The base ships in `re-frame.story.generate`, re-exported as
`story/check-property!` (the property entry point) + `story/sweep-faults!`
(the fault-lattice sweep below).

### Generator-agnostic; reproducible from the seed

A generated run is driven by a caller-supplied `gen-fn` — a pure
`(fn [seed] event-program)` returning a deterministic event program (tagged
steps, or bare event vectors the artifact coerces). This namespace bundles
**no generator library**; the CHOICE of generator (a `clojure.test.check`
generator, a hand-rolled state-machine walk, a recorded-interaction fuzzer)
is the caller's, by wrapping its draw in a `gen-fn`. The seed sequence is a
pure, seedable splitmix64 PRNG (`seed-seq`), so a property over N seeds is
reproducible WITHIN A HOST: on the same host, the same root seed replays the
same N programs, and recording the root `:seed` on the result reproduces the
whole run there.

**Seed reproducibility is within-host; cross-host replay rides the recorded
`:event-program`.** `next-seed`'s exact 64-bit bit-pattern differs JVM↔CLJS:
the JVM mixes with native wrapping `long` arithmetic, while CLJS computes the
mix in `js/BigInt` and then truncates the result into the JS safe-integer
range (a JS `number` cannot hold 64 exact bits). The same root `:seed` therefore
unfolds DIFFERENT program seeds on the two hosts, so a recorded bare `:seed`
reproduces a failure only on the host that produced it. This is intentional —
the value proposition does NOT depend on cross-host seed portability, because
every emitted artifact carries the CONCRETE `:event-program` (host-portable
tagged-step data); a promoted regression replays that program verbatim on any
host (§Promotion / §Run artifact and replay). The recorded `:seed` is
provenance — "this is the seed that drew this program on this host" — not the
cross-host reproducer.

### Shrinking — deterministic program-prefix delta-debug

When a generated program FAILS (`:fail` / `:error`; a `:cannot-run` is
inconclusive, not a falsification), `shrink-program!` searches for a SMALLER
failing program by deterministic delta-debugging over the event program: it
drops step windows (largest chunks first, then finer) and keeps any reduction
that STILL fails, until no single-step removal keeps the failure. The
`:shrink-path` records the ordered sequence of KEPT reductions, so a consumer
sees how the minimal failing case was reached. Shrinking reuses the SAME
replay path as the original run, so a shrunk artifact replays identically; a
`:max-replays` cap bounds the search (a partial shrink is still a smaller,
valid artifact).

### Fault lattice sweep

A fault lattice sweep (`sweep-faults!`) replays ONE base program across a
small lattice of FAULT cells, where each cell is a different `:fx-decisions`
map — the **same fx-override world input** `replay-run-artifact` already
reapplies, and the same surface the `:network` world slot lowers to. A
"fault" is just an fx override that fails / delays / mis-replies; there is
**no new fault-injection contract surface**. The sweep collects one
seed-bearing `:rf.test/run-artifact` per cell (each carrying its faulted
`:fx-decisions` so it replays against the same fault) and reports the cells
whose run FAILED, so an author can see which fault states falsify the program
and promote any cell's artifact (§Promotion). The `:cannot-run` policy is the
runner's existing one — a cell whose program cannot run under the chosen
runner refuses, it does not falsify.

### Pure / JVM-testable

The seed PRNG (`next-seed` / `seed-seq`), the failure predicate (`failing?`),
the shrink candidate enumeration (`drop-candidates`), and seed-bearing
artifact construction (`generated-artifact`) are pure data → data and run
under `clojure -M:test` with no host. The run / shrink / sweep DRIVERS
(`check-property!`, `shrink-program!`, `sweep-faults!`) replay into fresh
`:rf.test.replay/*` frames (torn down by the replay path), so the JVM gate
exercises the full generated-run → artifact → promotion flow against a live
frame synchronously.

## Determinism gate

The determinism gate answers one question: does this plan / artifact
produce the **same run every time**? It is the first consumer of
`canonicalize` (§Canonicalization) beyond snapshot identity, and it builds
directly on run-artifact replay (§Run artifact and replay). The base ships
in the `re-frame.story.determinism` namespace and is re-exported as
`test/assert-deterministic` (the testing-substrate surface owned by
[`spec/008-Testing.md`](../../../spec/008-Testing.md) — the tool lives
**below** Story and runs without the Story UI).

```clojure
(test/assert-deterministic plan-or-artifact)
(test/assert-deterministic plan-or-artifact opts)
;; -> {:status :deterministic     :run-hash <hash> :runs N :hashes [...]}
;;  | {:status :non-deterministic :divergence {…}  :runs N :hashes [...] :results [...]}
;;  | {:status :cannot-run        :reason :determinism-wall-clock-wait :wait-steps [...]}
```

`plan-or-artifact` is a `:rf.test/run-artifact`, a normalized variant plan
(its `[:world :setup]` ⧺ `:script` fold into the replay event program and
its `[:world :frame :fx-overrides]` become the replay fx decisions), or a
`:setup` / `:script` / `:event-program` body. `opts` MAY carry `:runs`
(replay count, default 2, minimum 2) and the `:hooks` / `:frame-config`
threaded to `replay-run-artifact`.

### What determinism means: canonical equality over N fresh-frame replays

The gate replays the event program into **N FRESH frames** and compares
the runs through `canonicalize`. A run is **deterministic** iff every
replay's canonical **run-slice** (`run-hash-input-keys` — `:status`, the
final `:app-db`, the `:epoch-tape`, the `:assertions` / `:checks` verdicts,
and the projected `:effects` / `:schema-violations` / `:warnings` /
`:sub-overrides` / `:fidelity`) is `=`. The slice — not the whole result —
is the authority, because a run-result also carries pure provenance (the
`:frame` replay id, the `:run-artifact` back-link, the per-step
`:replay-steps`) that legitimately differs per replay and is excluded from
`run-hash` for exactly this reason. The `run-hash` is the cheap
discriminator; canonical equality of the slice is the authority, so a hash
collision can never report a false `:deterministic`. A `:non-deterministic`
result names the FIRST run whose canonical slice differs from run 0, with
both run-hashes, and returns the per-run results for a downstream semantic
diff (`test/diff-run-artifacts`).

### Canonicalization MUST strip / normalize the per-run stamps

A fresh frame restarts the **process-global** epoch / dispatch / trace-id
counters and allocates a new generated `:rf.test.replay/*` frame id, so two
semantically-equal runs stamp different values for every piece of per-run
bookkeeping. `canonicalize` MUST strip / normalize these before comparison
so the gate is not blinded by them:

- **wall-clock timestamps** — `:committed-at` (epoch record), `:time`
  (trace event), `:elapsed-ms` (run / assertion record), and the
  framework-stamped `:rf/time-ms` nested inside the flat `:rf.cofx`
  recordable-coeffect map that rides a `:rf.event/dispatched` trace event's
  `:tags` (rf2-jt854w — EP-0010 dev-stamps the envelope's recordable-coeffect
  map onto the enqueue trace; EP-0017 / rf2-alc1lf renamed the field from the
  nested `:rf.world/inputs` to the flat `:rf.cofx` map and the framework time
  fact from `:time-ms` to `:rf/time-ms`). The `:rf.cofx` map itself is
  **semantic** (caller-supplied owner-qualified facts — the app's
  `:counter/delta`, a subsystem's `:rf.route/location`, … — are the
  deterministic causal token a scripted / replayed run pins, and a real
  difference in them MUST perturb the hash), but
  its framework-filled `:rf/time-ms` is epoch-ms wall-clock filled fresh per
  dispatch, so two semantically-equal fresh-frame replays stamp different
  values — it is stripped one level deeper, like `:committed-at`;

- **elapsed durations** — `:elapsed-ms`, and the dev-only handler
  wall-clock trace tags `:rf.event/elapsed-ms` (event run),
  `:rf.fx/elapsed-ms` (fx handler), and `:rf.cofx/elapsed-ms` (cofx
  handler); two semantically-equal runs replayed into fresh frames measure
  different handler durations (JIT / scheduling jitter), so all three are
  stripped or a TIMED fx / cofx would false-drift the gate;
- **generated dispatch ids** — `:dispatch-id`, the `:rf.trace/dispatch-id`
  trace tag;
- **generated frame ids** — `:frame` (the fresh `:rf.test.replay/*` id),
  wherever it rides an epoch record or a trace event's `:tags`;
- **runtime object identities** — `:epoch-id` / `:trace-id` (the
  process-global counters), `:schema-digest`, the `:rf.trace/trace-id` tag,
  and the trace event's `:id`;
- **intentionally-unspecified source order** — maps are key-sorted and
  sets element-sorted by `canonicalize`; effects and epochs keep producer
  order (which IS semantic — reordering them is a real difference).

The strip is split by SAFETY: reserved / framework-specific keys
(`:epoch-id`, `:dispatch-id`, `:trace-id`, `:committed-at`,
`:schema-digest`, plus `:elapsed-ms` / `:source` / `:runner` already in the
volatile set) are stripped **recursively** by the projection; the
genuinely-common keys a trace event / epoch record also carries (`:id`,
`:time`, `:frame`, the volatile `:tags` keys, and the nested
`[:tags :rf.cofx] :rf/time-ms`) are stripped
**structurally** — only on their trace-event (`:operation` + `:op-type`) or
epoch-record (`:epoch-id` + a record slot) carrier — so an app-db value
that legitimately keys on `:id` / `:time` / `:frame` survives and a real
semantic difference there is still detected. The semantic trace tags
(`:rf.trace/event-id`, the event payload `:rf.event/v`, a changed
`:rf.event/db`) are left intact.

This per-run-stamp strip applies on the `canonicalize` / `canonical-hash`
(= determinism / diff / `:run-hash`) path ONLY — the strip-free
`content-hash` that snapshot identity hashes does not see it. (Snapshot
content-hash VALUES still change with the rf2-lvrqa canonical-form revision
+ `canonical-version` v2 bump, which re-stamps every hash; see
§Snapshot-identity migration path. This strip is orthogonal to that — it
adds no NEW strip to the snapshot path.)

### The bare-`[:wait ms]` opt-out → `:cannot-run`

Determinism guarantees apply only to plans free of wall-clock steps
(§Unit and integration testing adjustments, §Script step grammar):
`[:wait-until pred]` (queue/state-based) is deterministic and preferred;
bare `[:wait ms]` is the explicit opt-out. `assert-deterministic` MUST
**refuse** a plan / artifact whose event program contains a bare
`[:wait ms]` with `:cannot-run` (the spec's third result state) rather than
running it flakily. The refusal is a **pure pre-flight** — computed before
any replay — and carries `:reason :determinism-wall-clock-wait` and the
offending `:wait-steps`. (A virtual clock that would make `[:wait ms]`
deterministic remains a non-goal, §P1 non-goals.)

### Pure / JVM-testable

The verdict logic (`wait-steps`, `has-wall-clock-wait?`,
`cannot-run-wait-refusal`, `->artifact`, `compare-runs`) is pure data →
data and runs under `clojure -M:test` with no runtime; the gate's headless
replay path settles synchronously to a fixed point via the headless
flush-hooks, so the full gate also runs headless. The strip's
host-portability is pinned on CLJS alongside the fingerprint primitive.

## Semantic diff

The semantic diff answers a different question from the determinism gate:
not *is this run stable?* but *how do these two runs differ in behaviour?*
It is the readable companion to a `:non-deterministic` divergence and to any
before/after comparison (a regression baseline vs HEAD, a fix's before/after,
a property-shrunk failure vs its neighbour). It ships in the
`re-frame.story.diff` namespace and is re-exported as
`test/diff-run-artifacts` (the testing-substrate surface owned by
[`spec/008-Testing.md`](../../../spec/008-Testing.md) — the tool lives
**below** Story and runs without the Story UI).

```clojure
(test/diff-run-artifacts baseline current)
(test/diff-run-artifacts baseline current opts)
;; -> {:same? true}
;;  | {:same? false :facets #{facet …} <facet> <readable-delta> …}
```

`baseline` and `current` are each either a `:rf.test/run-artifact` — which is
**replayed** into a fresh frame to obtain a run-result (the impure path, the
same replay the determinism gate drives, §Run artifact and replay) — or an
already-computed run-result (used directly, the pure path). `opts` MAY carry
the `:frame` / `:hooks` / `:frame-config` threaded to `replay-run-artifact`.

### Canonicalize first, then diff

Both runs are projected through `canonicalize` (§Canonicalization) **before**
any facet is compared, so the diff shows the **semantic** difference and not
the per-run noise. A fresh-frame replay restarts the process-global epoch /
dispatch / trace-id counters and allocates a new `:rf.test.replay/*` frame
id, so two semantically-equal runs differ in dozens of stamps; without the
canonicalize-first step a diff would drown in them. What survives the
projection is exactly the behavioural surface the determinism gate compares
— so a diff that finds **no facets** (`{:same? true}`) is the same judgement
`assert-deterministic` renders `:deterministic`, and a diff that finds facets
explains a `:non-deterministic` divergence in readable terms.

The `:same?` judgement is canonical equality of the run-**slice**
(`run-hash-input-keys` — the behavioural surface: `:status`, the final
`:app-db`, the `:epoch-tape`, the `:assertions` / `:checks` verdicts, the
projected `:effects` / `:schema-violations` / `:warnings`, and
`:sub-overrides` / `:fidelity`), the EXACT slice + judgement the determinism
gate's `compare-runs` uses — so a diff agrees with the determinism gate on
what counts as the same run. The pure provenance a run-result also carries
(the `:frame` replay id, the `:run-artifact` back-link, the per-step
`:replay-steps`) is excluded, exactly as it is from `run-hash`.

### What the diff covers

Each facet is projected independently and contributes to the result **only
when it differs**, so the diff localises *where* two runs parted and stays
small.

**INVARIANT — the facet set IS the canonical slice.** The `diff-runs` facet
names are EXACTLY `run-hash-input-keys`, the slice `:same?` is judged over
(`:trace-ops` is the readable projection of the `:epoch-tape` slot's causal op
spine, so it stands in for `:epoch-tape` 1:1). This is bidirectional and both
directions are load-bearing: every slice slot is covered (so a diff the gate
calls different always localises *where*), AND no facet sits *outside* the
slice (so no facet is dead — a facet for a slot the `:same?` judgement ignores
could never fire through `diff-runs`, and would overstate coverage while
disagreeing with the determinism gate / golden verdict). `:status` is in both
the facet set and the slice; it is listed last below as the headline a reader
wants first.

- `:app-db` — a readable key-path delta of the final app-db
  (`:added` / `:removed` / `:changed`, each entry a `{:path … :baseline …
  :current …}` over the differing leaf paths) — a 100-key db with one changed
  key yields a one-entry delta, never a database dump;
- `:assertions` — a **verdict** delta over the assertion records, keyed by
  the stable assertion selector `[:assertion :payload]`
  (`:added` / `:removed` selectors + `:changed` verdict flips, each entry a
  `{:selector … :baseline … :current …}`) — a `:pass` → `:fail` flip on one
  assertion reads as a one-entry `:changed`, not a wall of records, and the
  `:payload` disambiguates two same-id assertions at different paths;
- `:checks` — a verdict delta over the check records, keyed by the `:check`
  id (the same `:added` / `:removed` / `:changed` shape) — the check identity
  is its id, not its underlying assertion records;
- `:effects` — a **multiset** delta of the projected effect rows
  (`:only-baseline` / `:only-current`) — the effects one run emitted and the
  other did not (emitting an effect twice vs once IS a difference);
- `:schema-violations` — a multiset delta over the violation **surface
  selectors** (§Schema rule, `evidence/violation-selector` — `[:event id]`,
  `[:app-db registered-path path]`, …), not the full diagnostic records, so a
  re-ordered-but-equal set of violations is not a diff while a genuinely-new
  failure surface is;
- `:warnings` — a multiset delta over the projected warning rows
  (`:op-type :warning`, `:only-baseline` / `:only-current`) — the warnings one
  run raised and the other did not;
- `:trace-ops` — the ordered trace `:operation` sequence (the causal op
  spine) projected from `:epoch-tape`, reported as both spines plus the
  `:first-divergence` index — order is semantic, so a re-ordered or dropped op
  reads as a difference;
- `:sub-overrides` — a delta over the resolved `{query-vector value}` override
  map (§View-state subscription overrides), keyed by query vector
  (`:added` / `:removed` / `:changed`, each entry a `{:query … :baseline …
  :current …}`) — an override added/removed or whose pinned value differs;
- `:fidelity` — a **set** delta over the fidelity-ladder rung set
  (`:only-baseline` / `:only-current`) — the rungs one run rested on and the
  other did not (e.g. a baseline using `:real-setup` vs a current that fell
  back to `:sub-overrides`);
- `:status` — the top-level run status, when it differs (a `:pass` → `:fail`
  flip is the headline a reader wants first).

`:sub-runs` is **not** a facet. It is deliberately excluded from the run-slice
(`run-hash-input-keys`) — sub-runs are over-recomputed evidence, not a
determinism input — so a `:sub-runs`-only delta does not perturb the `:same?`
judgement, exactly as the determinism gate and the golden verdict treat it.
Wiring a `:sub-runs` facet into `diff-runs` would make it dead code (it could
never fire on a pure sub-runs delta) and would let the diff overstate coverage
relative to the slice it shares with the gate. `diff-sub-runs` survives as a
standalone **diagnostic** fn for callers that want to inspect the view-fact
delta directly; it is not part of the `:same?` judgement.

**The non-empty-`:facets` invariant.** A `:same? false` diff ALWAYS carries a
non-empty `:facets`. The per-surface facets above cover every run-slice slot,
but should some slice slot ever diverge with no specific facet firing (an
`:epoch-tape` change that is not a trace-op delta, or a future slice key added
without its facet), the assembler falls back to a coarse `:slice-keys` facet
naming WHICH `run-hash-input-keys` slot perturbed the judgement
(`[{:slice-key k} …]`). A diff NEVER returns `{:same? false :facets #{}}` — an
undiagnosable verdict that says two runs differ without saying how.

### A readable diff, not a data dump

`diff-run-artifacts` returns `{:same? true}` for behaviourally-identical runs
and otherwise a map carrying **only** the facets that actually differ, with
`:facets` naming them up front. A matching facet contributes nothing. This is
the semantic-diff contract: surface the semantic difference, suppress the
volatile noise.

### Pure / JVM-testable

The facet diff fns (`diff-app-db`, `diff-assertions`, `diff-checks`,
`diff-effects`, `diff-schema-violations`, `diff-warnings`, `diff-trace-ops`,
`diff-sub-overrides`, `diff-fidelity`) and the assembler
(`diff-runs`) are pure data → data — two run-results in, a readable diff out
(the diagnostic-only `diff-sub-runs` is pure too but is not wired into
`diff-runs` — it is outside the `:same?` slice)
— and run under
`clojure -M:test` with no runtime; only the artifact-replay entry path is
impure, and when both inputs are run-results `diff-run-artifacts` is itself
pure. The facet fns receive the **noise-stripped but shape-preserved**
run-results (`strip-noise` — the volatile / per-run-stamp / `:rf.story/*`
strip without the map-flattening ordering pass `canonicalize` applies), so
they read named slots while seeing none of the per-run noise.

## Golden slices {#golden-slices}

A **golden slice** is a curated canonicalized run regression artifact — the
deferred P1.5 surface (§Shared primitive lock), unblocked now that
`canonicalize` is proven by the determinism gate and the semantic diff. It
freezes a run's behaviour once and asserts later runs still match it:

    capture: golden  = canonicalize(behavioural-slice(run))
    compare: match?  = (= golden  canonicalize(behavioural-slice(new-run)))

It is the **third artifact below Story** — not a Story variant (no curation
lineage, no navigation slot) and not a run artifact (no replayable
`:event-program`); it is a frozen canonical EXPECTATION. It MUST NOT add a
slot to the run-result schema (§Run result): it freezes the canonicalized
SLICE of an existing result, it does not extend the result shape.

### Why canonicalize, not store the raw run

A raw run carries per-RUN noise a fresh-frame replay restamps every time
(the process-global epoch / dispatch / trace-id counters, the
`:rf.test.replay/*` frame id, the wall-clock `:committed-at` / `:time` /
`:elapsed-ms`). Storing a raw run as a golden would make every rerun a false
mismatch. So a golden MUST store the `canonicalize`d slice — the SAME
primitive (§Canonicalization) the determinism gate compares N replays
through and the semantic diff projects before comparing. A golden mismatch
is therefore always a SEMANTIC difference (app-db, effect, assertion
verdict, schema failure, trace spine), never volatile drift. This is exactly
why the surface was deferred until `canonicalize` was proven: the golden
REUSES that one strip path rather than inventing a second canonicaliser to
drift apart.

### The behavioural slice

The slice frozen into a golden is `fingerprint/run-hash-input-keys` — the
behavioural surface (`:status`, final `:app-db`, the `:epoch-tape`, the
`:assertions` / `:checks` verdicts, the projected `:effects` /
`:schema-violations` / `:warnings`, and the resolved `:sub-overrides` /
`:fidelity`). This is the SAME slice `run-hash` hashes and the determinism
gate (`compare-runs`) and the semantic diff (`diff-runs`'s `:same?`
judgement) compare — so a golden match, a determinism `:deterministic`, and
a diff `{:same? true}` are the **one judgement under three names**. The pure
provenance a run also carries (`:frame` replay id, `:run-artifact`
back-link, `:replay-steps`) is excluded for the same reason `run-hash`
excludes it: it legitimately differs per run and would make a golden
brittle.

### The `:rf.test/golden` slice shape

```clojure
{:golden/kind :rf.test/golden
 :canonical   <canonicalize(behavioural-slice(run))> ; the frozen expectation
 :run-hash    <run-hash(run)>                         ; cheap pre-check
 :slice-keys  [k …]                                   ; the frozen surface
 :golden/meta {:variant/id … :doc … :created-at … :source …} ; optional curation
 :run-result  {…}}                                    ; optional, for the readable diff
```

`:canonical` is the load-bearing slot — the frozen, host-portable canonical
projection compared on every later run. `:run-hash` is the cheap
discriminator (a mismatched hash short-circuits to "different"; a matching
hash is CONFIRMED by canonical equality, so a hash collision can never
report a false GREEN). `:slice-keys` records the surface the slice was taken
over, so a future slice-key change is detectable rather than silent.
`:golden/meta` carries curation provenance and is NEVER part of the compared
`:canonical` — re-curating a golden's doc does not perturb the regression
baseline. `:run-result` is the optional retained source slice (captured with
`:keep-run-result true`) that powers the readable mismatch diff (below).

### Capture / compare contract

```clojure
(story/capture-golden target opts)  ; -> :rf.test/golden slice
(story/golden-match?  golden run opts) ; -> boolean
(story/compare-golden golden run opts) ; -> readable report
```

- **`capture-golden`** freezes a `target` into a golden slice. `target` is a
  run-result (used directly — the PURE path, `clojure -M:test` with no
  runtime) or a `:rf.test/run-artifact` (REPLAYED into a fresh frame via
  `replay-run-artifact` first, so a golden frozen from an artifact captures
  the fresh-frame run the determinism gate + diff would produce). `opts` MAY
  carry `:meta`, `:keep-run-result`, and the replay opts (`:frame` /
  `:hooks` / `:frame-config`).
- **`golden-match?`** returns true iff the new run's `canonicalize`d
  behavioural slice is `=` to the golden's frozen `:canonical`. It checks
  the cheap `:run-hash` first, then confirms with canonical equality (the
  AUTHORITY). The match is robust to per-run noise — frame / epoch / trace
  ids and timestamps do NOT cause a false mismatch, because `canonicalize`
  strips them on both sides (reusing the determinism strip rules) — and
  sensitive to a real semantic difference, which perturbs the canonical
  value.
- **`compare-golden`** returns a READABLE report: `{:match? true …}` on
  match, else `{:match? false :run-hash … :golden-run-hash … :diff …}`. On
  mismatch the report MUST DELEGATE to the semantic diff
  (`diff-runs` / `diff-run-artifacts`, §Semantic diff) — it MUST NOT
  reinvent the diff — so the report localises WHERE the run parted from the
  baseline (a one-key app-db drift reads as a one-entry `:app-db` facet, an
  effect-only change as `:effects`, a status flip as `:status`). The
  delegated diff reads named run-result slots, which the lossy `:canonical`
  ordering cannot supply; so the readable diff requires the golden's source
  slice (kept via `:keep-run-result true`, or supplied as
  `:golden-run-result`). Absent it, the report still states the mismatch
  FACT (both run-hashes) with `:diff :unavailable-no-run-result`.

### Pure / JVM-testable

The slice + capture + match / report logic (`behavioural-slice`,
`slice-canonical`, `make-golden`, `golden?`, `golden-match?`,
`compare-golden`) is pure data → data — a run-result in, a golden / verdict
out — and runs under `clojure -M:test` with no runtime. The only impurity is
the artifact / plan capture path, which replays into a fresh frame via the
existing `replay-run-artifact` seam. The golden module `:require`s ONLY the
pure fingerprint / diff modules + the artifact replay seam, so it introduces
no hard `:require` of a test-only dep into the production Story path.

## Relationship to the workshop surface (storytelling is in-scope)

Story is a **workshop**, not only a test engine. The shipping authoring
vocabulary — `:args` / `:argtypes` / controls, view arg schemas,
`:decorators`, `:modes` / `:viewports` / `:backgrounds`, `:substrates`,
`:variants-grid`, loaders, docs/static/share — is **in scope** and MUST
flow through the variant-plan to both the runners and the workshop UI.
One `:extends` definition is shared with
[`001-Authoring.md`](001-Authoring.md); one plan compiler serves render
and test.

| Want | Use |
|---|---|
| child IS-A specialization, appears under the parent in the tree | `:extends` |
| anonymous reusable mixin with no lineage | `:compose` fragment |
| view wrapping (theme/provider/chrome) | `:decorator` |
| the state matrix (theme × viewport × …) | `:variants-grid` / `:modes` |

### Workshop superset — what the plan carries (rf2-5x1wt.24)

The variant-plan compiler is the SINGLE compiler for render AND test, so
the workshop vocabulary rides the SAME normalized plan the runner
consumes (`re-frame.story.plan/variant-plan`). The compiler preserves
every workshop slot under `:world`:

| Authored slot | Plan slot | Meaning |
|---|---|---|
| `:component` | `[:world :component]` | the active view id `render-variant` paints |
| `:args` / `:argtypes` | `[:world :args]` / `[:world :argtypes]` | control values + control metadata |
| (resolved) | `[:world :effective-args]` | the args feeding the view — at plan time the resolved arg-map; `render-variant` layers the live control overrides on top |
| `:decorators` | `[:world :decorators]` | view-wrapping decorators (theme / provider / chrome) — NOT fx overrides |
| `:fx-overrides` | `[:world :frame :fx-overrides]` | effect overrides — a frame slot, a distinct surface from `:decorators` |
| `:modes` / `:substrates` | `[:world :modes]` / `[:world :substrates]` | saved-tuple modes + the substrate set |
| `:viewport` / `:background` | `[:world :viewport]` / `[:world :background]` | per-variant viewport + canvas background |
| `:sub-overrides` | `[:world :render :sub-overrides]` | view-state overrides (the design-fidelity rung) |
| `:variants-grid` | (workspace-level — `reg-workspace`) | the state matrix is a WORKSPACE layout, not a per-variant body slot; it enumerates registered variants rather than riding one |

### `render-variant` — render through the same plan

`(story/render-variant target opts)` is the workshop render verb (§Args,
controls, and `render-variant`). It normalizes `target` through the SAME
`variant-plan` compiler the runner uses (a keyword resolves a registered
variant; a map is an inline plan), layers `opts`'s `:control-overrides` on
top of the plan's `[:world :effective-args]` (deep-merge, the same
precedence the run path uses), **re-validates** the post-control effective
args against the `[:world :view-args-schema]`, computes the `:plan-hash`
over the normalized plan, resolves the active view + render inputs, and
renders the view through the host renderer.

- A control that drives an invalid view input ⇒ `:invalid-args` (render
  STOPS before the view is called). A control driving a control-bound
  `:sub-overrides` value re-resolves against the post-control effective
  args, so the live view reflects the control.
- The `:plan-hash` is `fingerprint/plan-hash` over the SAME normalized
  plan, so a runner (`story/run`) and `render-variant` agree on it
  whenever the behaviour-relevant inputs match — render and run cannot
  diverge on what the plan was.
- `render-variant` prepares `:world` and renders the active view; it does
  NOT execute `:script` or terminal `:expect` (rendering is not a test
  run). The host render is a CLJS late-bound hook (`:render-host`); the
  bare JVM has none, so `render-variant` returns `:cannot-run` there
  rather than a silent empty render.

### Storybook → Story concept map

A migrant from Storybook is at home with this mapping (the full prose
version ships in the docs guide):

| Storybook | re-frame2 Story | Notes |
|---|---|---|
| `Meta` / `export default` (CSF) | `reg-story` | the story-level defaults (component, args, argTypes, tags) |
| `Story` / named export | `reg-variant` | one curated variant; ALSO a test (Story-as-test duality) |
| `args` | `:args` | control values; `[:arg key]` placeholders thread them into setup/script |
| `argTypes` | `:argtypes` | control metadata; schema-derived from the view arg schema where possible |
| `args` after controls panel | `:effective-args` | the post-control args `render-variant` feeds the view |
| `decorators` | `:decorators` | view wrapping ONLY (theme/provider/chrome); fx mocking is `:fx-overrides` |
| `parameters` | `:viewport` / `:background` / `:modes` / `:xray` / … | named workshop slots, not one opaque bag |
| `parameters.viewport` | `:viewport` | per-variant viewport |
| `parameters.backgrounds` | `:background` | per-variant canvas background |
| Chromatic modes / globals | `:modes` | saved tuples deep-merged into args |
| `loaders` | `:loaders` (+ `:loaders-teardown`) | async preconditions before render |
| `play` function | `:script` | ordered behaviour; tagged steps, not imperative `userEvent` calls |
| `expect(...)` in `play` | `:assertions` / `[:assert …]` | the one assertion atom, terminal or mid-script checkpoint |
| MSW network mocks | `:network` | route-level managed-HTTP stubs (lowered to the managed-request stub fx) |
| `render` / custom render | `:component` + `:sub-overrides` | the active view + the design-fidelity view-state overrides |
| docs / autodocs | `:doc` + the narrative projection | the scrubbable epoch-tape storyboard is the asset Storybook cannot copy |

The deliberate divergences from Storybook: a Story variant IS a test (one
artifact, two outputs); `parameters` are named, typed slots rather than an
opaque bag; the `play` script is data-shaped tagged steps (replayable +
`=`-comparable) rather than imperative `userEvent` calls; and the
`render-variant` render path drives the SAME plan the runner does, so the
workshop preview and the regression test never disagree.

## P1 non-goals

The following are not P1: remote cross-project Story composition;
provider verification / Pact-like backend contract harness; full
deterministic virtual clock/network scheduler; mutation testing; model
checking / linearizability fuzzing; generic JS framework support;
first-party hosted visual review service; nested fragment DAGs.

(Consumer-side fx stubs, the near-term oracles, visual + a11y
runner-tiered assertions, and property/model generation that emits
run-artifacts are IN scope.)

## P1 acceptance

P1 is complete when:

- a registered variant compiles to a stable plan; an inline map runs via
  the same verbs;
- the plan compiler lowers author ergonomics into `:world`, `:script`,
  `:expect`, and derives `:evidence` from the run;
- the three verbs share one runner/result implementation for registered
  variants and inline maps;
- Test mode and `clojure.test` consume the same result shape;
- `explain` accounts for the final plan, including conflicts,
  inherited-check requirements, and any explicitly requested richer
  runner;
- fragments and checks compose deterministically; the variant owns its
  end-state for strict conflicts; sibling strict conflicts fail;
  `:resolve-conflicts` is absent; checks inherit and ordinary
  assertions/scripts do not (parent + child setup appends);
- one assertion atom in two positions; `:assert-db` / `:assert-dom`
  folded; no `:no-schema-errors` knob is introduced;
- schema violations are projected from epoch trace events and fail unless
  exactly consumed (multiset matching);
- `:network` lowers to existing managed-request stubs and supports mixed
  route replies;
- capabilities are sets; the plan runs single-pass under the
  selected/requested runner policy; assertions whose required evidence is
  unavailable report `:cannot-run`; refusal carries
  required/available/reason; post-run evidence-slot validation confirms
  proof exists; `:cannot-run` reconciles with the shipping `:skipped?`;
- `canonicalize` exists in the fingerprinting path and backs determinism,
  diff, snapshot-identity, `:plan-hash`, and
  inline-plan-to-registered-variant equivalence;
- the run-result is unified and carries an epoch-keyed two-level
  `:narrative` projection; golden slices (§Golden slices) freeze the
  `canonicalize`d behavioural slice and compare a later run `=` to it, with
  a mismatch report delegated to the semantic diff;
- `render-variant` and the controls panel drive the same plan; view arg
  schemas validate `:effective-args` and derive controls/docs where
  possible; the Storybook→Story map ships;
- `:sub-overrides` exists for labelled view-state variants without
  pretending to prove real subscription logic;
- visual and a11y assertions are first-class runner-tiered assertions (a
  hosted visual review service remains out of scope);
- causal / cascade assertions (`:rf.assert/caused` /
  `:rf.assert/no-cascade-rerender`) project cause→effect from the existing
  `:cause-event-id` reactive evidence (no new trace op-type, no second
  accumulator); generated / property-style runs emit seed-bearing
  `:rf.test/run-artifact`s with shrink data and a fault-lattice sweep over the
  existing fx-override world input; a generated failure promotes through the
  existing curated bridge with its seed + source link preserved;
- the unit-test cookbook ships, and the Story play/script CI gate stays
  green.

## See also

- [`001-Authoring.md`](001-Authoring.md) — registration surface; the
  `:setup` / `:script` rename lands on the macro bodies there.
- [`004-Assertions.md`](004-Assertions.md) — the canonical `:rf.assert/*`
  family and record-don't-throw semantics this document builds on.
- [`009-Test-Mode.md`](009-Test-Mode.md) — the in-canvas test runner pane
  that must adopt the unified run-result shape.
- [`spec/007-Stories.md`](../../../spec/007-Stories.md) — the framework's
  normative Story contract; §Setup, script, and assertions (the canonical
  vocabulary) points here.
- [`spec/008-Testing.md`](../../../spec/008-Testing.md) — the general
  testing substrate that owns the inline-plan execution surface and the
  evidence tools.
