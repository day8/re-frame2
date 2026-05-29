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
yet exist (e.g. schema-fail wiring, a reactive recompute probe).

## Shipping vs target (grounding delta)

The shipping Story implementation is close to the target; this table is
the single inventory of what SHIPS today versus what is NET-NEW, so the
code migration is not confused with greenfield design.

| Surface | Status | Note |
|---|---|---|
| `reg-story` / `reg-variant`, data-shaped bodies | SHIPS | `tools/story/src/re_frame/story.cljc`, `schemas.cljc` (functions only behind ids). |
| `:rf.assert/*` family (7 ids) | SHIPS | The canonical seven per [`004-Assertions.md`](004-Assertions.md). |
| `:assert-db` / `:assert-dom` script steps | SHIPS | Folded into the one assertion atom (§Assertions — one atom, two positions); do not drop them. |
| bare event-vector script/setup shorthand | SHIPS (`play/runner.cljc` `coerce-script`) | P1 removes this authoring ambiguity; every setup/script step normalizes to a tagged step (§Script step grammar). |
| `:events` / `:play-script` / `:plays` | SHIPS | Renamed to `:setup` / `:script` / named-scripts (§Public vocabulary). |
| `:extends` | SHIPS as **straight merge** (child replaces) | The per-field plan-time merge (§Merge rules) is a **replacement** of this, not a refinement; a test MUST pin parent+child `:setup` *append*. |
| `:decorators` incl. `[:rf.story/force-fx-stub …]` | SHIPS | The **real** fx-override authoring surface; `:fx-overrides` is the derived frame slot (§The effect-override surface). |
| run-result key | SHIPS as `:lifecycle` | This document's top-level `:status` is **NET-NEW** (§Run result); the runtime's record-result map uses `:lifecycle` today. |
| `:skipped?` on no-DOM assertion records | SHIPS | This IS the `:cannot-run` case at assertion granularity — reconcile, do not duplicate (§`:cannot-run`). |
| `:rf.assert/schema-error` + schema-fail-the-run | NET-NEW | Today schema violations are trace events feeding a UI panel with **no fail mechanism** — must be wired (§Schema rule). |
| reactive recompute / render-count probe | NET-NEW (no seam) | `compute-sub` bypasses the cache; counts live only in test files — a probe must be lifted into instrumentation first. |
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
| `golden slice` | A deferred curated regression artifact. P1 ships canonicalization and the narrative projection first; once the hash/projection corpus is proven, P1.5 MAY store a canonicalized epoch/run slice as `:golden` and compare `canonicalize(new) == :golden`. |
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
`:script` → `:play-script` into the stored body so every shipping
RUNTIME reader — phase-2 events, the play runner's `variant-body->plays`,
snapshot identity, the workspace/canvas readers, and the recorder —
consumes the shipping slot unchanged while the tree migrates. This
lowering is the sanctioned temporary normalization, not a long-lived
shim: it is removed once the runtime is routed through the variant-plan
compiler (which already normalizes both spellings, §Compiler API and
normalization contract). Until then, a variant authored with `:setup` /
`:script` runs identically to one authored with the shipping slots.

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
                 :on-create [event-vector ...]
                 :fx-overrides {fx-id override-ref-or-data}
                 :interceptor-overrides {...}
                 :interceptors [...]}
         :args {...}
         :argtypes {...}                  ; control metadata; schema-derived where possible
         :view-args-schema optional-schema ; explicit view input contract copied from view :rf/props
         :effective-args {...}            ; post-control-override args; drives render-variant
         :setup [setup-step ...]
         :db-seed optional-db-or-patch    ; schema-checked direct state seed
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

### Run artifact

A run artifact is the low-level evidence emitted by generated tests,
failed runs, replay, determinism checks, or tool/agent exploration.

Required P1 shape:

```clojure
{:artifact/kind :rf.test/run-artifact
 :seed optional
 :event-program [...]
 :fx-decisions [...]
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
`:sub-overrides` lowers to `[:world :render :sub-overrides]`; `:fidelity`
is computed from the resolved world inputs, so authors SHOULD NOT have to
type it.

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
  `{:status :error …}` when a flush/dispatch throws. A flush timeout
  reports `:cannot-run` or `:error` per policy (`flush-timeout-result`) —
  **never a silent pass**.

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

**Bare event-vector shorthand is NOT supported.** The grammar is
uniformly tagged because an app event genuinely named `:dispatch`,
`:click`, `:wait`, `:type`, or `:focus` would otherwise be silently
un-dispatchable — a reserved-word hazard precisely in the slot where it
bites hardest. `explain` therefore shows identical normalized forms for
setup and script.

### Inline script assertions vs terminal assertions

An assertion inside `:script` (via `[:assert …]`) is a **checkpoint**: it
must be true at that exact point in the script. An assertion in
`:assertions` is **terminal**: it runs after script completion. The same
expectation SHOULD NOT be duplicated in both places unless the timing
distinction matters.

## Composition

### `:extends` — inherits context, never behaviour

Use `:extends` for direct specialization of another variant. The
governing principle: **`:extends` inherits the context/world, never the
behaviour/judgement.** New keys self-classify by that principle.

Inherited through `:extends`:

- `:world` context, including setup, args/argtypes, frame config, render
  fixtures, network stubs, platforms, and workshop context;
- checks, because checks are the inheritable expectation form;
- tags.

Not inherited through `:extends`:

- ordinary terminal assertions;
- script steps.

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

The shipping `:assert-db` step folds to `:rf.assert/path-equals`; a
`:rf.assert/dom-*` family carries a `:dom` token (folding the shipping
`:assert-dom`). Assertions in `:assertions` are own-only terminal
expectations; they MUST NOT inherit through `:extends`.

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
- `:rf.assert/visual-snapshot` (`:browser`)
- `:rf.assert/a11y` (`:browser` for axe-style; MAY require only `:hiccup`
  for explicitly structural checks)

Future assertion candidates after instrumentation support include
`:rf.assert/caused` and `:rf.assert/no-cascade-rerender`; both require a
reactive/render-count probe and are not P1 unless that probe lands first.

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

P1 selects only among tiers with **real seams**
(`:headless` / `:hiccup` / `:dom` / `:browser`). `:reactive-counts`
requires a NET-NEW recompute probe lifted into instrumentation first.

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

## Run result

All runners MUST return the same shape. (Today the runtime uses a
`:lifecycle` key; this top-level `:status` is NET-NEW.) Run result slots
are projections from the epoch tape wherever possible. The result shape
is API-stable, but the storage/source of truth is one tape so Story UI,
CI, docs, agents, and future golden/diff tools cannot disagree about what
happened.

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
the tape evidence (the shipping per-frame `trace-accumulators` siphon for
warnings/effects is superseded by this projection).

`(story/project-evidence epoch-tape {:script coerced-script})` is pure —
`:rf/epoch-record` vector in, evidence map out — so it runs under
`clojure -M:test` with no runtime. It returns:

| Slot | Source in the tape | Rule |
|---|---|---|
| `:epoch-tape` | the retained vector, verbatim | the evidence source, when retained |
| `:schema-violations` | each epoch's `:trace-events` | every `:rf.error/schema-validation-failure` error trace, keyed by the §Schema-rule surface selector (`[:event id]`, `[:cofx id]`, `[:fx-args id]`, `[:sub-return id query-v]`, `[:app-db registered-path path]`, `[:machine-data machine-id phase]`) so the multiset matcher pairs declared expectations to emitted failures |
| `:warnings` | each epoch's `:trace-events` | every `:op-type :warn` trace event, in tape order |
| `:effects` | each epoch's `:effects` row | the rows the framework already projected at settle time (`re-frame.epoch.capture/project-all`), concatenated in dispatch order, each stamped with its `:epoch-id` |
| `:sub-runs` / `:renders` | each epoch's `:sub-runs` / `:renders` rows | concatenated in tape order, each stamped with `:epoch-id` |
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
epoch lands in exactly one span.

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
{:runner :headless | :hiccup | :dom | :browser | :auto
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
- Golden slices are deferred. P1.5 MAY let a curated variant carry a
  `:golden` slice once canonicalization has an adversarial corpus proving
  that semantic differences perturb the hash and volatile fields do not.

Combined with `restore-epoch`, this projection is the spine of **both**
Test mode and Docs mode: the same evidence produces the test result and a
scrubbable causal storyboard. This is the move that fuses the testing and
storytelling halves.

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
  `{:elapsed-ms :dispatch-id :source :source-coord :runner :variant/id :plan-hash}`;
- impose a total per-slot ordering (effects = emission order; sub-runs =
  topo-then-id; epochs = dispatch order);
- enumerate the `:plan-hash` input fields;
- compute `:run-hash` over the canonical epoch slice.

It MUST reconcile the shipping `:rf/snapshot-canonical-v1` tuple and the
current `:variant-id` key spelling with any normalized-plan `:variant/id`
spelling. It MUST be built **before** anything consumes it (else the
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
| `canonicalize` | The single canonical projection of any Story value: strip the volatile-field set + `:rf.story/*` accumulator keys recursively, reconcile `:variant-id` → `:variant/id`, then impose total per-slot ordering. Re-exported as `story/canonicalize`. |
| `content-hash` | 8-char-hex hash of the **ordered** value with NO volatile strip — the low primitive the snapshot tuple hashes, so snapshot identity is byte-stable across the fold. |
| `canonical-hash` | 8-char-hex hash of the `canonicalize`d (stripped) projection — the determinism / semantic-diff / run-equivalence hash. |
| `plan-hash` | `canonical-hash` over `plan-hash-input-keys` (`[:story/id :world :script :expect :required-runner :tags]`). |
| `run-hash` | `canonical-hash` over `run-hash-input-keys` (`[:status :app-db :epoch-tape :assertions :checks :effects :schema-violations :warnings :sub-overrides :fidelity]`). |

The volatile-field set stripped by `canonicalize` is
`{:elapsed-ms :dispatch-id :source :source-coord :runner :variant/id
:plan-hash :run-hash}` — `:run-hash` is the symmetric companion to
`:plan-hash` (a run-result carries its own `:run-hash`, which must not
feed a re-canonicalization of that result). Ordering is: maps key-sorted
by the canonicalised key's `pr-str`; sets element-sorted; vectors/seqs
(effects, epochs, trace events) keep producer order, which the producer
emits deterministically (effects in emission order, epochs in dispatch
order) — so reordering effects or epochs is a *semantic* change that
perturbs the canonical value.

`plan-hash` and `run-hash` are the same `canonical-hash` primitive applied
to enumerated slices; there is no second hash implementation. The
metamorphic relation holds: an inline plan and the normalized plan of a
registered variant describing the same behaviour produce the same
`plan-hash` regardless of provenance slots (`:plan/id`, `:variant/id`,
`:source-chain`, `:explain`, `:evidence`).

**Snapshot-identity migration path.** The fold is a pure relocation of the
hashing code, not a version bump. Snapshot identity hashes its tuple
through the strip-free `content-hash`, so the snapshot content-hash is
**byte-identical** to the pre-fold value and existing visual-regression
baselines stay valid with no re-stamp. The volatile strip + `:variant-id`
reconciliation apply only on the `canonicalize` / `canonical-hash` path
(determinism, diff, `:plan-hash`, `:run-hash`); the snapshot tuple keeps
its `:variant-id` slot exactly as before. A `content-hash` consumer keeps
variant-id sensitivity; a `canonical-hash` consumer treats variant-id as
volatile.

The adversarial corpus ships in
`re-frame.story-fingerprint-test` (JVM, the full corpus) and
`re-frame.story-fingerprint-cljs-test` (host-portability): paired
volatile-only twins MUST canonicalize `=` and hash equal; paired
single-field semantic twins (app-db, effect, assertion verdict, status,
epoch db-after, warning) MUST canonicalize `not=` and hash unequal.

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
(`first-bad-epoch`). Both live in `re-frame.story.invariants`; the
fixture USES the utility for its "which epoch failed?" reporting so the
live and post-hoc verdicts agree by construction.

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

A Storybook→Story concept map (arg / argType / decorator / parameter /
viewport → Story field) MUST ship in docs so migrants are at home.

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
  `:narrative` projection; golden slices are deferred until the
  canonicalization corpus proves stable;
- `render-variant` and the controls panel drive the same plan; view arg
  schemas validate `:effective-args` and derive controls/docs where
  possible; the Storybook→Story map ships;
- `:sub-overrides` exists for labelled view-state variants without
  pretending to prove real subscription logic;
- visual and a11y assertions are first-class runner-tiered assertions (a
  hosted visual review service remains out of scope);
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
