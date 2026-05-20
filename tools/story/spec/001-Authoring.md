# Story — Authoring

> The registration surface — the seven `reg-*` macros, the EDN-first
> variant contract, the inclusion-tag vocabulary, source-coord
> stamping, and the macro-time validation rules. The contract Stage 2
> implements and Stages 3–8 consume.

## Boot — auto-install of the canonical vocabulary (rf2-p1ydc)

The canonical Story vocabulary — the seven canonical tags (`:dev`,
`:docs`, `:test`, `:screenshot`, `:experimental`, `:internal`,
`:agent`), the `:rf.assert/*` event handlers, the built-in
`:rf.story/force-fx-stub` decorator, the layout-debug decorator trio,
the toolbar cofx + subs, the lifecycle machine, the v1.0 SOTA panel
set, and (CLJS only) the multi-substrate Reagent default —
**auto-installs on the first `reg-*` call**. Authors don't need to
call `(story/install-canonical-vocabulary!)` explicitly; the boot is
implicit, matching Storybook's ergonomic.

```clojure
(ns app.stories
  (:require [re-frame.story :as story]))

;; No (story/install-canonical-vocabulary!) call. The first reg-* line
;; below auto-installs the canonical vocabulary. :dev / :docs / :test
;; etc. are registered by the time the body's :tags is validated.
(story/reg-story :story.ui.button
  {:doc       "Primary action."
   :component :app.ui/button
   :tags      #{:dev :docs}})
```

### Trigger condition

Per-process. The auto-install gate is a single boolean atom in
`re-frame.story.canonical`. The first call to ANY of the seven
runtime helpers — `reg-story*` / `reg-variant*` / `reg-workspace*` /
`reg-mode*` / `reg-story-panel*` / `reg-decorator*` / `reg-tag*` —
flips the gate true and runs the canonical installer chain. The
seven `reg-*` macros expand to those runtime helpers, so the same
gate fires from macro-site or programmatic-site registrations alike.

Frames don't enter into it. The registrar's side-table is per-process
(a single `defonce` atom keyed by Story kind); the auto-install gate
is symmetric. Parallel frames share the canonical vocabulary —
there's no per-frame install state to track.

### Idempotency

The auto-install gate flips true **before** the installer chain runs,
so the registrar writes triggered by the chain itself (e.g.
`install-canonical-tags!` calling `reg-tag*`) hit the early-return
branch and don't recurse. Subsequent `reg-*` calls (after the gate
has flipped) are a single `deref` + `nil` check — negligible on the
hot path.

### Explicit call is legacy (rf2-y8gag — audit D-2)

`re-frame.story/install-canonical-vocabulary!` is still public and
idempotent — calling it explicitly hits the same installer chain —
but **no authoring or example code calls it any more**. Per the
audit-D-2 cleanup (rf2-y8gag) the explicit call has been removed
from every canonical example testbed (`tools/story/testbeds/...`),
the `tools/template/` scaffold emissions, the `docs/story/` tutorial
set, and the `skills/re-frame2/references/` reference doc. New code
should rely on the auto-install path; the explicit entry stays
available only as a literal-boot affordance for hosts that want one
and as a JVM-test diagnostic that asserts a known starting state
without running a `reg-*` call. Calling it BOTH ways — explicit
boot AND letting auto-install fire — still lands on identical
state.

### Test fixtures — `clear-all!` resets the gate

`(re-frame.story/clear-all!)` wipes the registrar's side-table AND
resets the auto-install gate to `false`. The next `reg-*` call in a
fresh test runs the full auto-install path again. Test fixtures that
called `clear-all!` followed by `install-canonical-vocabulary!`
under v1 still work — the explicit call is a no-op overlap with the
auto-install path that would fire on the first body-of-test `reg-*`
anyway — but new tests can drop the explicit boot step entirely.

### Rationale (vs explicit-only boot)

Pre-alpha posture: clean either-or, no soft "warn on missing vocab"
half-measure (per the rf2-p1ydc dispatch directive). Auto-install is
the clean ergonomic — Storybook has no explicit boot step, neither
should Story. The seven canonical tags live under reserved
namespaces (`:dev` / `:docs` / etc. — un-namespaced; `:rf.story/*`
for the framework-owned bodies); authors cannot legitimately "want a
different `:dev`" so there's no surprise factor from auto-installing
a vocabulary the author chose to omit. The implicit boot is the
right default; the explicit call remains available only for hosts
that want a literal boot step.

The auto-install hook is wired via the `re-frame.story.late-bind`
shim (`:ensure-canonical-installed` key) to avoid a circular require
between `re-frame.story.registrar` (consumer) and
`re-frame.story.canonical` (producer). Same pattern Story uses for
`:tap-stub-event` / `:drop-assertion-accumulators`.

## Mental model — the three pillars (rf2-kemcu + rf2-adtmk + rf2-x6u3l)

Before the registration-macro reference, the three concepts that
land Story in a single picture. Every later slot on every later
macro feeds into one of these three pillars:

| Pillar | The Storybook reader's question | Story's answer | Headline section |
|---|---|---|---|
| **Args** | "What inputs does this variant exercise?" | One EDN map per layer; five layers compose by deep-merge; the merged map IS the variant's parameter surface | [§Args — the typed parameter surface](#args--the-typed-parameter-surface-rf2-kemcu) |
| **Frame** | "How do I write a stateful story without tripping the hooks-inside-stories rule?" | Every variant IS a frame; per-variant `app-db`; no shared mutable state; no render fn, no hooks to break | [§Frame-as-isolation — the stateful-stories answer](#frame-as-isolation--the-stateful-stories-answer-rf2-adtmk-i-1) |
| **Schema** | "How do I declare controls?" | Author writes the view's Malli schema once; controls auto-derive; `:argtypes` is the override channel, almost never needed | [§Schema → controls — THE controls story](#schema--controls--the-controls-story-rf2-x6u3l-i-2) |

The three pillars compose: a variant's effective Args feed the
controls panel (whose widget shapes derive from the view's schema)
and the per-variant frame is what the Args drive against. Storybook
ships these as three independent feature sets (`args` / `useArgs` /
`argTypes`); Story ships them as a single coherent model where each
pillar reinforces the others.

### Args — the typed parameter surface (rf2-kemcu)

Args are the typed parameter surface for stories — one map describes
the variant's inputs, drives the controls panel, gates visual-
regression keys, and feeds the MCP write surface. The args map is
the **central concept**, not one slot among many; every other slot
on the variant body either feeds args (`:argtypes`, `:args->events`)
or runs against the frame the args drive.

The five-layer precedence diagram, in strict later-wins order:

```
1. global args      ← (story/configure! {:rf.story/global-args {...}})       — boot
2. story args       ← :args on the parent (reg-story)                        — story default
3. mode args        ← active :mode's :args (reg-mode)                        — saved tuple
4. variant args     ← :args on the variant (reg-variant)                     — per-scenario
5. cell-overrides   ← controls-panel edits at runtime (:story/set-arg)       — live edit
                     ↓
              effective args (deep-merge, vectors replaced)
                     ↓
              the variant's view renders against this map,
              against its own frame, with its own app-db.
```

Deep-merge for nested maps; override-by-replacement for vectors.
This matches Storybook's convention and is the contract every
authoring helper depends on. The canonical lookup is
`(get-effective-args variant-id {:mode ... :overrides ...})` per
[`002-Runtime.md`](002-Runtime.md) §Args resolution precedence.

Why five layers? Each layer scopes a different authoring intent.
Global args ride boot configuration (themes, locales, feature
flags); story args set the parent default; mode args pivot the
entire variant-grid against a chrome-level toggle (light/dark,
desktop/mobile, `en`/`fr`); variant args carry the per-scenario
override that names the story (the "at-five" in `:story.counter/at-
five`); cell-overrides give the reader a knob without mutating
source. Five layers; five distinct authoring intents; one merged
map.

```clojure
;; A counter story exercising every layer:

;; 1. Global (set once at boot)
(story/configure! {:rf.story/global-args {:theme :light :locale :en}})

;; 2. Story default
(story/reg-story :story.counter
  {:component :app.ui/counter
   :args      {:label "Count" :max 100}})

;; 3. Mode args (saved tuple — the toolbar toggles into this)
(story/reg-mode :Mode.app/dark-large
  {:args {:theme :dark :size :large}})

;; 4. Variant override
(story/reg-variant :story.counter/at-five
  {:events [[:counter/initialise 5]]
   :args   {:label "Count from 5"}})

;; 5. The reader edits :max in the controls panel → cell-override.
;;    Effective args at that moment:
;;    {:theme :dark :locale :en :size :large
;;     :label "Count from 5" :max <whatever-they-typed>}
```

Read the table above as the **five points of leverage** an author
has over a story's parameter surface; reach for the lowest-numbered
layer that scopes the change you want.

Cross-references: [§Schema → controls — THE controls story](#schema--controls--the-controls-story-rf2-x6u3l-i-2)
below for how the merged args feed the controls panel.
[`002-Runtime.md`](002-Runtime.md) §Args resolution precedence
for the runtime lookup contract.

### Frame-as-isolation — the stateful-stories answer (rf2-adtmk, I-1)

A Storybook reader landing on Story asks the natural question:
"How do I write a stateful story?" In Storybook the answer is
`useArgs()` + the Args panel + a render fn — with the well-known
hooks-inside-stories re-render gotcha and one global app instance
that decorators wrap per-story.

**Story's answer is structurally different.** Every variant is
mounted in its own isolated re-frame frame; every `(variant × mode ×
cell)` triple gets a fresh `app-db`. There is no shared mutable
state to coordinate around, no render fn to host a hook, no hooks
gotcha to trip on. Stateful stories aren't a separate authoring
mode in Story — they are the default.

| Storybook concept | The well-known limitation | Story's structural answer |
|---|---|---|
| One global app instance | Cross-story state bleed; decorators reset by convention | Per-variant frame; no shared `app-db`; bleed is impossible by construction |
| `useArgs()` inside a render fn | Hooks-inside-stories re-render gotcha | No render fn — the view is a registered `reg-view`; the variant body is pure data |
| `useState(...)` for ephemeral UI state | Lives in React tree, not in app state | Per-variant `app-db` IS the state — read it with subs, write it with events |
| Cross-cell coordination | Authors invent shared globals or rely on decorator order | Two cells of the same variant in a `:variants-grid` are independent by construction |

This is the headline payoff of being a re-frame native — frames are
the existing isolation primitive; Story just allocates one per
variant.

```clojure
;; Three independent stateful cells, one declaration:
(story/reg-story :story.counter
  {:component :app.ui/counter
   :variants  {:empty   {:events [[:counter/initialise 0]]}
               :at-five {:events [[:counter/initialise 5]]}
               :driven  {:events [[:counter/initialise 0]]
                         :play-script
                         [[:dispatch-sync [:counter/increment]]
                          [:dispatch-sync [:counter/increment]]
                          [:dispatch-sync [:counter/increment]]
                          [:dispatch-sync [:rf.assert/sub-equals
                                           [:counter/value] 3]]]}}})

;; Mount :Workspace.counter/auto-grid (a :variants-grid workspace).
;; Three cells render side-by-side, each in its own frame:
;;   - cell A has :count 0 in its app-db
;;   - cell B has :count 5 in its app-db
;;   - cell C has :count 3 in its app-db (after :play-script settles)
;; Incrementing cell A does not affect B or C. No opt-in needed.
```

See [§Stateful variants — every variant IS a frame](#stateful-variants--every-variant-is-a-frame-rf2-wqdq1)
below for the full contract and Storybook contrast table, and
[`002-Runtime.md`](002-Runtime.md) §Per-variant frame allocation
for the runtime mechanics.

### Schema → controls — THE controls story (rf2-x6u3l, I-2)

A Storybook reader who lands on Story expects to write `argTypes:
{ size: { control: { type: 'range', min: 0, max: 100 } } }` and
similar for every controllable arg. **Story's answer is shorter.**

The view's Malli schema is the source of truth for control widgets.
Write `[:int {:min 0 :max 100}]` once on the view's `:rf/schema` and
**every** story of that view gets the slider. No `:argtypes`
plumbing per-story, no per-control widget wiring, no parallel-vocab
maintenance burden.

| Storybook | Story | Note |
|---|---|---|
| `argTypes: { size: { control: { type: 'range', min: 0, max: 100 } } }` per story | `[:int {:min 0 :max 100}]` on the view's schema, once | Story authors don't write `:argtypes` for typed args |
| `argTypes: { variant: { control: { type: 'select', options: ['primary','secondary','danger'] } } }` per story | `[:enum :primary :secondary :danger]` on the view's schema | Enum members ARE the select options |
| `argTypes: { disabled: { control: 'boolean' } }` | `:boolean` on the view's schema | Trivial; Story's schema already says it |
| `argTypes: { tags: { control: { type: 'object' } } }` (no widget for nested edit) | `[:vector :string]` → repeater rows; `[:map [k v] ...]` → labelled group; `[:tuple X Y]` → fixed-arity row | Story's schema-walker recurses into collections — see [§Schema-derivation pipeline](#schema-derivation-pipeline) |

The headline mapping (more in [§Schema-derivation pipeline](#schema-derivation-pipeline) below):

| Schema fragment | Auto-derived control |
|---|---|
| `:string` | text input |
| `:int` / `:double` with `:min :max` | slider |
| `:int` / `:double` without bounds | number input |
| `:boolean` | toggle |
| `[:enum a b c]` | select |
| `[:map ...]` | labelled group, recursive |
| `[:vector X]` / `[:set X]` | repeater (rows + `[+]` / `[-]`) |
| `[:tuple X Y ...]` | fixed-arity row |

The contract: push every typed constraint up to the view's
`:rf/schema` and the controls panel renders the right widget for
free. `:argtypes` on the story or variant body is the override
channel for the cases the schema can't say enough — see the [edge
cases](#when-to-write-argtypes-the-edge-cases) below.

```clojure
;; Author writes ONE schema on the view:
(rf/reg-view :app.ui/button
  [:map
   [:label    :string]
   [:variant  [:enum :primary :secondary :danger]]
   [:size     [:int {:min 8 :max 64}]]
   [:disabled? :boolean]]
  (fn [args] [:button.btn ...]))

;; Every story of the view gets controls for free:
(story/reg-story :story.ui.button
  {:component :app.ui/button
   :args      {:label "Click me" :variant :primary :size 16 :disabled? false}})
;;
;; controls panel:
;;   :label     → text input
;;   :variant   → select [:primary :secondary :danger]
;;   :size      → slider 8–64
;;   :disabled? → toggle
;;
;; No :argtypes was written. The view's schema said enough.
```

Cross-references: [§Controls — schema-derived, zero-`:argtypes`](#controls--schema-derived-zero-argtypes-rf2-b87h2)
below for the full mapping + worked example.
[§Schema-derivation pipeline](#schema-derivation-pipeline) for the
walker contract.
[`spec/Spec-Schemas.md`](../../../spec/Spec-Schemas.md) for the
framework's schema registry.

## Registration macros (all DCE under `:advanced`)

All registration macros live under `re-frame.story` and shadow the
existing `re-frame.core/reg-*` calling convention. Under `:advanced`
they elide to `nil`; see [`005-SOTA-Features.md`](005-SOTA-Features.md)
and [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) §DCE-dev-only for the
elision contract.

### `(reg-story id metadata)` and the combined form

```clojure
(reg-story id metadata)
```

| Slot | Shape | Notes |
|---|---|---|
| `id` | keyword in `:story.<dotted-path>` namespace | Per [spec/007 §Canonical id grammar](../../../spec/007-Stories.md). |
| `metadata` | map | Body shape below. |

Metadata keys:

```clojure
{:doc        "..."                 ; string
 :component  reg-view-id           ; keyword id of a registered :view
 :decorators [[:dec-id args ...]]  ; vector of decorator vectors (id-valued)
 :args       {<arg-key> <value>}   ; story-level defaults
 :argtypes   {<arg-key> {...}}     ; control spec; auto-derived from :component's schema
 :tags       #{:dev :docs ...}     ; subset of registered tags
 :modes      #{<mode-id> ...}      ; saved-tuple modes (see reg-mode below)
 :substrates #{:reagent :uix ...}  ; default substrate set for variants
 :platforms  #{:server :client}    ; SSR opt-in per spec/007 §:platforms
 :variants   {<variant-name> <variant-body>}}  ; Form B (sugar) — see Combined form
```

`:variants` is the Form-B combined-form sugar. The macro desugars to N
independent `reg-variant` calls at expansion time so hot-reload-by-variant
works (per [spec/007 §Combined `reg-story` form](../../../spec/007-Stories.md)).

### `(reg-variant id metadata)`

```clojure
(reg-variant id metadata)
```

| Slot | Shape | Notes |
|---|---|---|
| `id` | keyword `:story.<path>/<variant>` | Per spec/007. |
| `metadata` | map | Body shape below. |

Body shape (locked at
[spec/007 §Variant artefact contract](../../../spec/007-Stories.md) —
no fn-slots):

```clojure
{:doc                   "..."
 :extends               <variant-id>             ; parent variant; merged at registration
 :events                [[:event-id args ...]]   ; setup events; phase 2
 :play-script           {:script [...steps]      ; post-render rich-DSL script; phase 4
                         :auto-run? true         ; (rf2-0wrud — canonical AND ONLY phase-4 slot)
                         :name "happy path"}
 :args                  {<arg-key> <value>}      ; override/extend story args
 :argtypes              {<arg-key> {...}}        ; override story argtypes
 :tags                  #{:dev :docs ...}        ; subset of registered tags
 :decorators            [[:dec-id args ...]]
 :loaders               [[:loader-event-id ...]] ; async setup; phase 1
 :loaders-complete-when <pred>                   ; vector or registered event-id; see 002-Runtime
 :args->events          {<arg-key> <event-id>}   ; arg → app-db mapping (spec/007 §Args mapping)
 :platforms             #{:server :client}
 :substrates            #{:reagent :uix ...}
 :modes                 #{<mode-id> ...}         ; cell = (variant × mode)
 :causa-panel           <panel-kw>               ; (rf2-v1ach) default Causa panel for the RHS embed
 :causa                 {:panel <panel-kw>       ;   nested form (same slot, alongside other :causa keys)
                         :open?  <bool>          ;   per-story Causa preset — see §Causa preset slot
                         :tab    <kw>            ;   (deprecated alias for :panel)
                         :filters {...}
                         :focus   {...}}}
```

### `:play-script` — the canonical phase-4 surface (rf2-0wrud)

`:play-script` is the canonical AND ONLY phase-4 play surface
(rf2-0wrud, 2026-05-20). Pre-alpha posture: the legacy `:play`
event-vector slot has been removed — no transitional dual-acceptance.
Authors compose post-render behaviour as a sequence of TAGGED steps:

| Step                                  | Semantics                                                |
|---------------------------------------|----------------------------------------------------------|
| `[:dispatch event-vec]`               | `rf/dispatch` (async) into the variant's frame           |
| `[:dispatch-sync event-vec]`          | `rf/dispatch-sync` (synchronous) into the variant's frame |
| `[:wait ms]`                          | Sleep N ms (`setTimeout` CLJS / `Thread/sleep` JVM)       |
| `[:assert-db path value]`             | Assert `(= (get-in @app-db path) value)`                  |
| `[:assert-db path :pred fn-or-sym]`   | Assert custom predicate (fn preferred under advanced CLJS) |
| `[:assert-dom selector :visible]`     | Assert selector resolves to a visible DOM node            |
| `[:assert-dom selector :hidden]`      | Assert selector resolves to nothing (or hidden node)      |
| `[:assert-dom selector :text txt]`    | Assert selector's text-content matches `txt`              |
| `[:click selector]`                   | Synthetic click event at selector                         |
| `[:type selector text]`               | Synthetic `input` event at selector with `text`           |

The canonical seven `:rf.assert/*` assertion events (per
[`004-Assertions.md`](004-Assertions.md)) ride the `:dispatch-sync`
rail: `[:dispatch-sync [:rf.assert/path-equals [:n] 3]]`. The
assertion handler runs synchronously and records into
`:rf.story/assertions` on the variant's frame — identical semantics
to what the legacy `:play` slot delivered.

Two body shapes are accepted:

- **Bare vector** — `:play-script [[:dispatch-sync [:foo]] ...]`
  shorthand for `{:script <vector> :auto-run? true}`.
- **Map**       — `:play-script {:script [...] :auto-run? bool :name str}`
  for explicit opt-out of auto-run on mount or naming the play.

The runner's `coerce-script` also tolerates bare event vectors at the
script level — `[[:counter/inc] ...]` lifts each entry to
`[:dispatch <event-vec>]`. Prefer explicit `:dispatch-sync` wrapping
when porting code that depended on the legacy `:play` slot's
drain-to-completion ordering.

The `:rf/variant` schema (in
[`spec/Spec-Schemas.md`](../../../spec/Spec-Schemas.md)) enforces the
no-fn-slots rule. Stage 2 macros validate the body against this schema
and reject with `:rf.error/variant-shape` on miss.

#### `:causa-panel` — RHS Causa embed default (rf2-v1ach)

The optional `:causa-panel` slot declares which Causa panel renders
by default in the RHS Causa embed (per
[`003-Render-Shell.md`](003-Render-Shell.md) §Right-hand pane). The
slot's value is one of the seven canonical Causa panel ids:

| `:causa-panel` value | Causa panel rendered (chip label)             | Typical use case                                  |
|----------------------|-----------------------------------------------|---------------------------------------------------|
| `:event-detail`      | Event — six-domino cascade view (default)     | "What happened on the last event?"                |
| `:app-db`            | App-db — structural diff across the cascade   | State-shape stories (counter, settings, modals)   |
| `:views`             | Views — per-view sub-invalidation surface     | Reactive-graph debugging, sub-fan-out stories     |
| `:trace`             | Trace — trace-buffer feed for the cascade     | Side-effect / fx-heavy stories                    |
| `:machines`          | Machines — chart + arc/ring overlays          | Statechart-driven UIs                             |
| `:routing`           | Routing — registered routes + simulate-URL    | Routing demos, deep-link stories                  |
| `:issues`            | Issues — cascade-scoped issues feed           | Stories that exercise issue / warning surfaces    |

The slot resolves variant-first, then story-level, then the framework
default `:event-detail`. The user can swap panels at runtime via the
chip-row picker in the RHS — their click is sticky for the session
and overrides the declared default. Unknown keywords fall back to
`:event-detail` so a typo doesn't blank the embed. The canonical id
list lives in
[`re-frame.story.ui.causa-embed/panel-catalog`](../src/re_frame/story/ui/causa_embed.cljs);
the Causa-side mount surface (one `mount-<panel>!` per id) lives at
[`day8.re-frame2-causa.panels`](../../causa/src/day8/re_frame2_causa/panels.cljs).

Two equivalent forms are accepted on the variant (or story) body:

```clojure
;; Top-level form — recommended when there's no other :causa preset.
(story/reg-variant :story.counter/at-five
  {:events       [[:counter/initialise 5]]
   :causa-panel  :app-db})              ; "state-shape story → app-db lens"

;; Nested form — recommended when the variant also carries other
;; :causa preset slots (`:open?` / `:filters` / `:focus`). Lets the
;; author group all Causa-side configuration in one map.
(story/reg-variant :story.routing/deep-link
  {:events [[:router/navigate "/checkout/42"]]
   :causa  {:panel   :routing
            :filters {:out [:router/url-change]}}})
```

Both forms read the same logical slot — `:causa-panel` on the body
beats `[:causa :panel]` nested. The variant body's `:causa-panel`
also wins over the parent story's; the resolver lives in
`re-frame.story.ui.causa-embed/resolve-panel`.

Both the cross-package `:rf/variant` schema in
[`Spec-Schemas.md`](../../../spec/Spec-Schemas.md) and Story's local
`Variant` schema (`tools/story/src/re_frame/story/schemas.cljc`) are
open by convention — the top-level `:causa-panel` keyword passes
validation as an open-shape addition, and the nested form rides the
`:causa` slot's `CausaPreset` schema (whose `:panel` key is locked
there). See [`003-Render-Shell.md`](003-Render-Shell.md) §Right-hand
pane and §Mount lifecycle for how the RHS embed consumes the slot.

### `(reg-workspace id metadata)`

```clojure
(reg-workspace id metadata)
```

| Slot | Shape | Notes |
|---|---|---|
| `id` | keyword `:Workspace.<path>/<name>` | Per spec/007. |
| `metadata` | map | Body shape below. |

Body:

```clojure
{:doc       "..."
 :layout    :grid | :prose | :variants-grid | :tabs | :custom
 :variants  [<variant-id> ...]                    ; for :grid / :variants-grid / :tabs (explicit list)
 :for       <story-id>                            ; for :variants-grid only — auto-enumerate
 :columns   <integer>                             ; for :grid / :variants-grid — column count
 :content   [{:type :prose :body "md..."} ...]    ; for :prose
 :render    <view-id>                              ; for :custom (a registered view)
 :modes     #{<mode-id> ...}                       ; future-reserved — see below
 :isolation :isolated | :shared}                   ; rf2-gqid4 — :variants-grid only
```

The `:variants-grid` layout (per Phase 2 SOTA refinement) renders
every variant of a single parent story side-by-side; it differs from
`:grid` (which renders an explicit `:variants` list) by enumerating
variants from the registry.

#### `:variants-grid` — explicit `:variants` vs auto-enumerated `:for` (rf2-pgccv)

`:variants-grid` accepts the variant set via **one** of two slots —
they are alternatives, not co-equals. Declaring both raises
`:rf.error/workspace-shape` at registration.

| Slot | Shape | Behaviour |
|---|---|---|
| `:variants` | `[<variant-id> ...]` (explicit vector) | Renders exactly the listed variants in declared order. Use when you want a curated subset, or to interleave variants from sibling stories. |
| `:for` | `<story-id>` (single keyword) | Auto-enumerates every registered variant of the named parent story, in registration order. Use when you want "all variants of this story, no maintenance." New variants added to the story appear automatically without touching the workspace body. |

Equivalent to the Storybook 8 contrast between an explicit `subcomponents`
list and an auto-enumerated `*` glob — Story names the two paths
explicitly so the authoring intent is in the body.

```clojure
;; Explicit list — cherry-pick three of the counter's eight variants:
(story/reg-workspace :Workspace.counter/curated
  {:layout   :variants-grid
   :variants [:story.counter/empty
              :story.counter/at-five
              :story.counter/overflow]
   :columns  3})

;; Auto-enumerate — every variant of :story.counter, in registration order:
(story/reg-workspace :Workspace.counter/auto-grid
  {:layout  :variants-grid
   :for     :story.counter
   :columns 2})
```

Other layouts (`:grid`, `:tabs`) take `:variants` only — they have no
single parent-story to enumerate against. `:prose` and `:custom`
ignore both slots.

#### Workspace `:modes` slot — future-reserved (v1) (rf2-q5e36)

The workspace body's `:modes` slot is **declared but not honoured by
the runtime in v1**. Workspaces inherit the chrome's `:active-modes`
selection (the chrome-level toolbar, per
[`010-Toolbar.md`](010-Toolbar.md)). Workspace-local mode scoping
(override / union / cell fan-out matrix) is a v2 follow-up; the slot
is reserved so existing bodies don't break when the runtime starts
honouring it. **This paragraph is authoritative** for the workspace
`:modes` slot semantics. The chrome-level toolbar is the only path
that affects rendering today; see
[`010-Toolbar.md`](010-Toolbar.md) §State location for the
`:active-modes` slot every workspace render consumes via
`re-frame.story.ui.state/shell-state-atom`.

#### Workspace `:isolation` slot — `:variants-grid` mount strategy (rf2-gqid4)

The optional `:isolation` slot tunes how `:variants-grid` mounts its
cells. Two values:

| Value       | Mount strategy                                         | When to use                                                                                                                                                                                                                                            |
|-------------|--------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `:isolated` | **(default)** every cell mounts in parallel, each scoped to its variant-allocated frame via `frame-provider-ns-safe`. Baseline frame-isolation contract. | Most stories. The decorator + frame-provider pipeline keeps each cell's app-db / subscribes independent.                                                                                                                                              |
| `:shared`   | cells mount ONE at a time with a prev/next navigator (◀ N/total ▶). Same serialised-mount strategy as `:tabs`. | Views whose `:component` internally hardcodes a frame-provider (e.g. `gallery_chrome.cljs` / rf2-sszlr). Parallel cells of such views share interior state because the last-seeded cell's app-db clobbers siblings — serialised mount restores per-variant state. |

Only `:variants-grid` honours `:isolation`; other layouts ignore it.
`:tabs` already serialises by construction.

```clojure
(story/reg-workspace :Workspace.gallery/all
  {:layout    :variants-grid
   :isolation :shared})    ; render cells serially with prev/next nav
```

Belt-and-braces to the Causa modal-positioning fix (rf2-om6fa);
together they cover the full-viewport modal stack symptom *and* the
remaining interior state-bleed.

### `(reg-decorator id metadata)`

```clojure
(reg-decorator id metadata)
```

| Slot | Shape | Notes |
|---|---|---|
| `id` | keyword | Decorator id. |
| `metadata` | map | Body shape below. |

Body (one of three kinds per spec/007):

```clojure
;; Hiccup wrapper
{:doc  "..."
 :kind :hiccup
 :wrap (fn [body args] [:div ... body])}        ; ONLY closure allowed at registration site

;; Frame setup
{:doc      "..."
 :kind     :frame-setup
 :init     [[:event-id args ...]]                ; events to dispatch into the variant's frame at allocation
 :teardown [[:event-id args ...]]                ; events to dispatch-sync into the variant frame at destroy
 :app-db-patch {<path> <value>}}                 ; or a static app-db patch

;; Fx override (force-fx-stub)
{:doc  "..."
 :kind :fx-override
 :fx-id     <fx-id>                              ; the fx the decorator stubs
 :response  <data>}                              ; the stubbed response
```

**Closure caveat.** A decorator's `:wrap` slot *is* a function — but
it lives at the *decorator registration site*, not the variant body.
Variant bodies reference decorators by id; the closure is registered
once and shared. The "variant body is pure data" rule is preserved.

#### `:teardown` — symmetric counterpart of `:init`

The optional `:teardown` slot on a `:frame-setup` decorator carries a
vector of event vectors that the runtime dispatch-syncs into the
variant frame just before `destroy-frame!` runs (per
[`002-Runtime.md`](002-Runtime.md) §Loader teardown contract). This
is the recommended path for closing resources a `:frame-setup`
decorator's `:init` events opened — a polling interval started by
`:init` should be cancelled by `:teardown`; a websocket subscription
opened by `:init` should be closed by `:teardown`.

```clojure
(story/reg-decorator :feed/live-subscription
  {:doc      "Opens the live-feed websocket on mount; closes on destroy."
   :kind     :frame-setup
   :init     [[:feed/subscribe]]
   :teardown [[:feed/close-socket]]})
```

Composition order. When multiple `:frame-setup` decorators carry
`:teardown` events, the runtime dispatches them in reverse-
declaration order — innermost (variant-level) teardowns fire before
outermost (story-level / global) teardowns. This mirrors function-
scope cleanup conventions and keeps a resource opened by an outer
decorator alive while inner decorators that may depend on it tear
down first.

Teardown events run after lifecycle watchers + assertion accumulators
have been cleared but before `rf/destroy-frame!`'s own walk; the
variant frame's `app-db` is still readable, so a teardown handler may
inspect state when computing what to clean up. Teardown events that
throw are caught and projected into the variant's last
`:rf.story/assertions` record as `:rf.error/exception` with
`:phase :phase-teardown`; teardown never aborts `destroy-frame!`.

**When to prefer a state machine instead.** For any resource with a
non-trivial lifetime (reconnect logic, backoff, multi-step
shutdown), the spec/005 machine pattern (described in
[`002-Runtime.md`](002-Runtime.md) §Machine lifecycle on variant
unmount and §Loader teardown contract) is the better surface — the
actor's `:exit` action captures the cleanup, and the state-machine
runtime owns the destroy walk. `:teardown` on `:frame-setup` is the
lightweight option for resources that need exactly one cancel event.

#### Global decorators — `configure! :rf.story/global-decorators` (rf2-9qpk3 · audit C-1 / F-1)

The **global-decorators** slot on `configure!` is the project-wide
defaults layer — symmetric to `:rf.story/global-args` for args. The
host calls it once at boot:

```clojure
(story/reg-decorator :app/theme-provider
  {:kind :hiccup
   :wrap (fn [body _args] [theme-provider {:theme :light} body])})

(story/configure!
  {:rf.story/global-decorators [[:app/theme-provider]]})
```

Every variant's resolved decorator stack is then `(concat globals
story-decorators variant-decorators)`. The global slot is the
Storybook `preview.ts` `decorators: [...]` parity (Feature-Parity-
Audit C-1 / F-1) — projects with a design-system theme provider
write the wrap once at boot rather than listing the decorator id on
every `reg-story`.

`reg-global-decorator` is the ergonomic alternative that registers
the body AND opts it in to the global stack in one call. The
`configure!` slot is the canonical *batch* shape — a project that
keeps its global stack in one place (`preview.cljs`-style) declares
the vector once.

Composition is additive, not override-style: a per-variant
`:decorators` slot does NOT override globals — both layers compose
in the order documented in the table below. This matches Storybook's
actual `preview.ts` semantics (preview decorators wrap every story
regardless of per-story declarations) and keeps the layer model
symmetric with `:rf.story/global-args` (also additive — Layer 1 of
the precedence chain, never overridden by a later layer).

#### Composition order — the `:fx-override` asymmetry (rf2-a6l59)

A variant's effective decorator stack is **global** decorators
(from `configure! :rf.story/global-decorators`) followed by the
**story-level** decorators followed by the **variant-level**
decorators (declared order in each layer). The three decorator kinds
compose differently under that stack, and the asymmetry is
deliberate but easy to trip on if you arrive expecting a single
rule:

| Kind | Composition rule | Intuition |
|---|---|---|
| `:hiccup` | **Outermost wraps innermost.** Global decorators are outermost; story decorators sit inside globals; variant decorators are innermost. Walked LIFO so the *last* decorator declared on the variant ends up *closest* to the rendered view. | "Wrap" is geometric — a global theme provider sits outside the story-level decorators sits outside the variant-level layout-debug overlay. |
| `:frame-setup` | **Declared order** (globals first, then story decorators, then variant decorators). Each runs its `:init` events / `:app-db-patch` against the variant's frame in turn; later writes overlay earlier ones at the same path. | First in, last write. Variant-level setup overrides story-level setup overrides global setup at the path level. |
| `:fx-override` | **Declared order, but collisions on `:fx-id` resolve LAST-WINS.** Globals register first; story decorators second; variant decorators last; if multiple layers target the same `:fx-id` the innermost wins. | The innermost decorator is closest to the variant's intent and should override outer stubs. |

The asymmetry: `:hiccup` reads as **"outermost wraps innermost"**
(story first, geometrically outside), while `:fx-override` reads as
**"innermost overrides outermost"** (variant last, semantically wins).
A reader who internalises the wrapping rule for `:hiccup` will be
briefly surprised by `:fx-override`'s inversion. Both rules are the
right call for their kind — wrapping nests, overrides last-write —
but the inversion is worth naming because the same author can hit
both in the same variant body.

```clojure
;; Story-level :fx-override registered first (the "base mock");
;; variant-level :fx-override registered second wins on collision.
(story/reg-story :story.checkout/flow
  {:decorators [[:force-fx-stub :http/managed
                 {:response {:status 200 :body {:items []}}}]]})

(story/reg-variant :story.checkout/flow/server-error
  {:decorators [[:force-fx-stub :http/managed                       ;; SAME :fx-id
                 {:response {:status 500 :body :rf.http/failed}}]]})
;;                                                  ^^^^^^^^^^^^
;;                                                  variant-level wins
```

For collisions on `:hiccup` decorator ids the registry is keyed by
id, so two decorators with the same id raise `:rf.error/decorator-id-collision`
at registration — id collisions are an authoring bug, not a
composition rule.

### `(reg-story-panel id metadata)`

```clojure
(reg-story-panel id metadata)
```

Body:

```clojure
{:doc          "..."
 :title        "Display name"
 :placement    :right | :left | :bottom | :top | :modal
 :render       <view-id>                            ; registered :view that renders the panel
 :for          #{<context-id>}                     ; optional — restrict to specific story-tool contexts
 :enabled-when (optional)}                          ; optional registry-predicate fn
```

Per [spec/007 §Story-tool extension hook](../../../spec/007-Stories.md).
The render shell reads `(story/registrations :story-panel)` and lays them out.
The five-rule embed contract is defined in
[`006-MCP-Surface.md`](006-MCP-Surface.md) and
[`003-Render-Shell.md`](003-Render-Shell.md).

### `(reg-tag id metadata)`

```clojure
(reg-tag id metadata)
```

Body (all slots optional):

```clojure
{:doc            "..."
 :axis           :status                       ; optional — facet grouping hint
 :default-filter :exclude}                      ; optional — :include (default) | :exclude
```

The seven canonical tags (`:dev`, `:docs`, `:test`, `:screenshot`,
`:experimental`, `:internal`, `:agent`) register at Story load.
Project tags must be registered before use. An unregistered tag on a
variant's `:tags` set raises `:rf.error/unknown-tag` at registration.

#### `:axis` — facet grouping (SB9 parity)

The optional `:axis` slot is a keyword classifier (e.g. `:status`,
`:role`, `:team`, `:feature`) that groups registered tags into
collapsible facet rows in the sidebar tag-filter UI. Tags registered
without `:axis` render in a trailing un-grouped row. Mirrors
Storybook 9's status / role / team / feature axes (rf2-v05qb SB9
parity).

Query the axis grouping via:

- `(story/tags-by-axis :status)` — set of tag ids on a given axis.
- `(story/tags-without-axis)` — set of tag ids with no axis.

The `:axis` slot is purely a UI grouping hint — it does not affect
variant `:tags` set semantics or `variants-with-tags` filtering.

#### `:default-filter` — sidebar boot state (SB9 parity)

The optional `:default-filter` slot is `:include` (default) or
`:exclude`. When `:exclude`, the sidebar pre-excludes variants
carrying this tag at boot. Use this to register tags like `:internal`
or `:experimental` whose variants should be hidden from the default
dev shell until manually toggled on.

Query the default-excluded set via:

- `(story/tags-default-excluded)` — set of tag ids with `:default-filter :exclude`.

```clojure
(story/reg-tag :auth/regression-set
  {:doc  "Auth regression-suite variants."
   :axis :team})

(story/reg-tag :alpha
  {:doc            "Pre-release status."
   :axis           :status
   :default-filter :exclude})
```

#### `!`-prefix removal syntax

`!`-prefix removal syntax: `:!dev` removes `:dev` from the inherited
tag set when included in a child variant's `:tags`. Per Phase 1 §2.1
Storybook-borrowed ergonomic.

### `(reg-mode id metadata)`

```clojure
(reg-mode id metadata)
```

Body:

```clojure
{:doc  "..."
 :axis <axis-keyword>                           ; optional — toolbar grouping
 :args {<arg-key> <value>}}                     ; the saved-tuple
```

The optional `:axis` slot groups modes for the chrome-level toolbar
([`010-Toolbar.md`](010-Toolbar.md) §Optional grouping). Modes sharing
an axis (e.g. `:theme`) are mutually exclusive in the toolbar's
single-select UX; un-axis-tagged modes are toolbar-multi-select.
`:axis` does not affect the `:args` deep-merge — it is purely a
toolbar grouping hint.

Example:

```clojure
(story/reg-mode :Mode.app/dark-mobile
  {:doc  "Dark theme on a mobile viewport."
   :args {:theme :dark :viewport :mobile :locale :en}})

(story/reg-mode :Mode.app/light-desktop
  {:args {:theme :light :viewport :desktop :locale :en}})
```

When a variant is rendered against mode M, M's `:args` deep-merge into
the variant's effective args (precedence: global < mode < story <
variant). Each `(variant × mode)` cell has an independent
`snapshot-identity` — see [`002-Runtime.md`](002-Runtime.md) §Snapshot
identity.

## Controls — schema-derived, zero-`:argtypes` (rf2-b87h2)

> Full reference for the headline branded above in [§Schema →
> controls — THE controls story](#schema--controls--the-controls-story-rf2-x6u3l-i-2).
> That section is the marketing/contrast pitch; this one is the
> technical mapping.

The controls panel is **auto-derived** from the view's registered
Malli schema. The schema-on-the-view IS the source of truth — the
authoring story is "register your view with a schema; controls
appear." Author-supplied `:argtypes` exists for the edge cases where
the schema doesn't say enough; in most stories it is empty.

This is the largest authoring win Story carries over Storybook: where
Storybook authors write per-story `argTypes: { size: { control: {
type: 'range', min: 0, max: 100 } } }`, Story authors write
`[:int {:min 0 :max 100}]` once on the view and **all** stories of
that view get the slider for free.

### The mapping

| Schema fragment | Auto-derived control | Notes |
|---|---|---|
| `:string` | text input | |
| `:int` (optional `[:int {:min :max}]`) | number input or slider | Slider when both `:min` and `:max` are bounded. |
| `:double` (optional `[:double {:min :max}]`) | number input or slider | As above. |
| `:boolean` | toggle | |
| `:keyword` | text input | Keyword-coercion at edit time. |
| `[:enum a b c]` | select | Options are the enum members in declared order. |
| `[:map [k1 s1] [k2 s2] ...]` | labelled group of nested rows | One row per key, recursive on each `s_i`. |
| `[:vector X]` | repeater (rows + `[+]` / `[-]`) | Adds seed a default from `X` (text → "", number → 0, etc.). |
| `[:set X]` | repeater (deduplicated) | As above; round-trips through `set`. |
| `[:tuple X Y ...]` | fixed-arity row (no `[+]` / `[-]`) | One row per declared position. |

The full collection-recursion contract — including how editing
position `i` writes through to `[:cell-overrides variant-id arg-key i]`
— lives in [§Schema-derivation pipeline](#schema-derivation-pipeline)
below. That section is the technical reference; this one is what a
new author needs to know to write their first controlled variant.

### Worked example

```clojure
;; Author writes one schema on the view:
(rf/reg-view :app.ui/button
  [:map
   [:label    :string]
   [:variant  [:enum :primary :secondary :danger]]
   [:size     [:int {:min 8 :max 64}]]
   [:disabled? :boolean]]
  (fn [args] [:button.btn {:class (str "btn-" (name (:variant args)))} (:label args)]))

;; Every story of the view gets controls for free — no :argtypes:
(story/reg-story :story.ui.button
  {:doc       "Primary action button."
   :component :app.ui/button
   :args      {:label "Click me" :variant :primary :size 16 :disabled? false}})
```

The reader's controls panel shows:

- `:label` → text input
- `:variant` → select `[:primary :secondary :danger]`
- `:size` → slider 8–64 (because both bounds are present)
- `:disabled?` → toggle

No `:argtypes` was written. Author-supplied `:argtypes` overrides the
auto-derivation key-by-key when needed (e.g. force a free-form text
input for a keyword-typed arg).

### When to write `:argtypes` (the edge cases)

`:argtypes` is for the cases the schema can't say enough:

- The view has no registered schema (legacy code).
- The author wants a different widget than the auto-derivation picks
  (e.g. a stepper instead of a slider for a bounded `:int`).
- The arg is computed from multiple schema fields and needs a custom
  label / grouping.

In all other cases, push the constraint up to the view's schema and
let Story derive the control. The schema is the single source of
truth; `:argtypes` is the override channel.

See [§Schema-derivation pipeline](#schema-derivation-pipeline) for
the full walker + override contract.

## Stateful variants — every variant IS a frame (rf2-wqdq1)

> Full reference for the headline branded above in [§Frame-as-
> isolation — the stateful-stories answer](#frame-as-isolation--the-stateful-stories-answer-rf2-adtmk-i-1).
> That section is the marketing/contrast pitch with the Storybook
> answer side-by-side; this one is the runtime contract and worked
> examples.

Stateful stories — "this variant shows the counter at 5" or "this
variant shows the form mid-submission" — are the canonical case
Storybook handles with `useArgs()` / `useState` inside a render fn,
hitting the well-known hooks-inside-stories re-render limitation.
Story's answer is structurally different: **every variant is mounted
in its own isolated re-frame frame**, so there is no shared mutable
state between variants and no hooks-inside-stories problem.

This means "stateful variants" in Story is not a separate authoring
mode — it's the default. You drive state with `:events` (setup) and
`:play-script` (post-render), and you read it with subs (the canvas
renders against the variant's frame) or assertions (which read through
`:rf.assert/sub-equals` etc.).

### The contract

- **Per-variant frame allocation.** Every `(variant × mode × cell)`
  triple is mounted in a unique frame; subs / events / fx within that
  cell run against that frame's `app-db`. See
  [`002-Runtime.md`](002-Runtime.md) §Per-variant frame allocation.
- **No cross-cell bleed.** Two cells of the same variant in a
  `:variants-grid` workspace each get their own frame. Incrementing
  the counter in cell A does not affect cell B.
- **No render fn, no hooks limitation.** The view is a registered
  `reg-view`; the variant body is pure data. There is no place to
  call a hook and no place for a hook to break.

### Worked example — counter at three different states

```clojure
(rf/reg-view :app.ui/counter
  (fn [_]
    (let [n @(rf/subscribe [:counter/value])]
      [:div
       [:button {:on-click #(rf/dispatch [:counter/decrement])} "−"]
       [:span.value n]
       [:button {:on-click #(rf/dispatch [:counter/increment])} "+"]])))

(story/reg-story :story.counter
  {:component :app.ui/counter
   :variants {:empty    {:events [[:counter/initialise]]}
              :at-five  {:events [[:counter/initialise]
                                  [:counter/set 5]]}
              :driven   {:events [[:counter/initialise]]
                         :play-script
                         [[:dispatch-sync [:counter/increment]]
                          [:dispatch-sync [:counter/increment]]
                          [:dispatch-sync [:counter/increment]]
                          [:dispatch-sync [:rf.assert/sub-equals
                                           [:counter/value] 3]]]}}})
```

Three variants, three independent app-dbs. Mount the
`:Workspace.counter/auto-grid` workspace and all three render
side-by-side, each cell starting fresh.

### Storybook contrast

| Storybook pattern | Story counterpart |
|---|---|
| `render: (args) => { const [a, setA] = useArgs(); ... }` | `:play-script` body dispatching events into the variant's frame |
| `useState(...)` inside the render fn | The view's subs read from the per-variant frame's `app-db` |
| Hooks-inside-stories re-render gotcha | None — there is no render fn |
| One global app instance, decorator-wrapped per story | Per-variant frame; no global shared state |

The structural win: **two cells of the same variant in a grid are
independent by construction**, with no opt-in needed. The cost: the
mental model is "frames, not hooks" — if you arrive from Storybook
expecting a render fn, the first chapter to read is this one.

See [`002-Runtime.md`](002-Runtime.md) §Per-variant frame allocation
for the runtime contract, and the worked example in
[§Play + assertions](#play--assertions) below for a full play body.

## Authoring grammar — worked examples

Worked examples illustrating every aspect of the authoring grammar.

### Minimal example

```clojure
(ns app.stories.button
  (:require [re-frame.core :as rf]
            [re-frame.story :as story]))

;; The view is registered under a keyword id (per spec/004 §reg-view).
(rf/reg-view :app.ui/button
  (fn [args]
    [:button.btn (:label args)]))

(story/reg-story :story.ui.button
  {:doc       "Primary action button."
   :component :app.ui/button             ;; keyword id of a registered :view
   :args      {:label "Click me"}})

(story/reg-variant :story.ui.button/default
  {:doc "Default state."})
```

Three forms: a story and a variant. The variant inherits the story's
`:component` and `:args`. With `:tags` defaulted to `#{:dev :docs}` it
appears in the dev tool and docs page.

### With controls (auto-derived from schema)

```clojure
;; The view ships a Spec 010 schema:
(rf/reg-view :app.ui/button
  [:map
   [:label :string]
   [:variant [:enum :primary :secondary :danger]]
   [:disabled? :boolean]]
  (fn [args]
    [:button.btn {:class (str "btn-" (name (:variant args)))} (:label args)]))

;; The story consults the registered schema to auto-derive argtypes:
(story/reg-story :story.ui.button
  {:doc       "Primary action button."
   :component :app.ui/button
   :args      {:label    "Click me"
               :variant  :primary
               :disabled? false}})
```

Story tool emits controls automatically:
- `:label` → `:text`
- `:variant` → `:select` with options `[:primary :secondary :danger]`
- `:disabled?` → `:boolean`

Author writes zero `:argtypes` unless overriding the auto-derivation.
See [§Controls — schema-derived, zero-`:argtypes`](#controls--schema-derived-zero-argtypes-rf2-b87h2)
for the full schema → control mapping table and override-channel
discussion.

### Decorators and `:args->events`

```clojure
(story/reg-decorator :centered-layout
  {:doc  "Centre the rendered content."
   :kind :hiccup
   :wrap (fn [body _]
           [:div.flex.items-center.justify-center.h-screen body])})

(story/reg-decorator :mock-auth
  {:doc  "Inject a mock authenticated user into the variant's frame."
   :kind :frame-setup
   :init [[:auth/restore-session {:user "alice"}]]})

(rf/reg-event-fx :story.auth/set-logged-in
  (fn [_ [_ v]]
    (if v
      {:fx [[:dispatch [:auth/restore-session {:user "alice"}]]]}
      {:fx [[:dispatch [:auth/log-out]]]})))

(story/reg-variant :story.auth.dashboard/logged-in
  {:decorators [[:centered-layout]
                [:mock-auth]]
   :args       {:logged-in? true}
   :args->events {:logged-in? :story.auth/set-logged-in}
   :events     [[:dashboard/initialise]]
   :tags       #{:dev :docs :test}})
```

### Play + assertions

```clojure
(story/reg-variant :story.auth.login-form/happy-path
  {:doc         "Full login flow."
   :events      [[:auth/initialise]]
   :play-script [[:dispatch-sync [:auth/email-changed "alice@example.com"]]
                 [:dispatch-sync [:auth/password-changed "hunter2"]]
                 [:dispatch-sync [:auth/login-pressed]]
                 [:dispatch-sync [:rf.assert/path-equals  [:auth :status] :authenticated]]
                 [:dispatch-sync [:rf.assert/sub-equals   [:auth/current-user] {:id 42 :name "Alice"}]]
                 [:dispatch-sync [:rf.assert/state-is     :auth/login :authenticated]]
                 [:dispatch-sync [:rf.assert/no-warnings]]
                 [:dispatch-sync [:rf.assert/effect-emitted :navigate]]]
   :tags        #{:dev :docs :test}})
```

### Loaders with `:loaders-complete-when`

```clojure
(rf/reg-event-fx :charts.heatmap/subscribe
  (fn [_ _]
    {:fx [[:websocket {:url     "wss://api/heatmap"
                       :on-message [:charts/heatmap-tick]}]]}))

(story/reg-variant :story.charts.heatmap/live
  {:doc     "Renders against a live websocket fixture."
   :loaders [[:charts.heatmap/subscribe]]
   :loaders-complete-when [[:rf.assert/dispatched? [:charts/heatmap-tick]]]
   :events  []
   :tags    #{:dev}})
```

The `:websocket` fx never "completes"; `:loaders-complete-when`
declares the predicate that means "we've received enough data to
render."

### Composed variant via `:extends`

```clojure
(story/reg-variant :story.auth.login-form/loading
  {:events     [[:auth/initialise]
                [:auth/email-changed "alice@example.com"]
                [:auth/login-pressed]]
   :decorators [[:force-fx-stub :http {:status :pending}]]})

(story/reg-variant :story.auth.login-form/loading-with-prefill
  {:extends :story.auth.login-form/loading
   :events  [[:auth/initialise]
             [:auth/email-changed "alice@example.com"]
             [:auth/password-changed "hunter2"]    ; prefill password
             [:auth/login-pressed]]
   :tags    #{:dev :docs}})
```

Resolution at registration time. Cycles raise
`:rf.error/extends-cycle`.

### Modes (saved tuples)

```clojure
(story/reg-mode :Mode.app/dark-mobile
  {:args {:theme :dark :viewport :mobile}})

(story/reg-mode :Mode.app/light-desktop
  {:args {:theme :light :viewport :desktop}})

(story/reg-story :story.ui.button
  {:component :app.ui/button
   :modes     #{:Mode.app/dark-mobile :Mode.app/light-desktop}})
```

The story renders against each mode. Each `(variant × mode)` pair has
its own `snapshot-identity` so a visual-regression service iterates
cells independently.

### Substrates

```clojure
(story/reg-variant :story.ui.button/all-substrates
  {:substrates #{:reagent :uix :helix}})
```

The multi-substrate pane (render shell) renders each substrate
side-by-side. Substrate-specific failures render inline; see
[`003-Render-Shell.md`](003-Render-Shell.md) and
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md)
§inline-substrate-failures.

### Combined form (Form B) desugaring

```clojure
;; Author writes:
(story/reg-story :story.auth.login-form
  {:doc       "..."
   :component login-form
   :args      {:placeholder "you@example.com"}
   :variants  {:empty            {:events [[:auth/initialise]]}
               :validation-error {:events [[:auth/initialise]
                                           [:auth/email-changed "x"]
                                           [:auth/login-pressed]]
                                  :tags   #{:dev :docs :test}}}})

;; The macro expands to three independent calls (story + N variants):
(do
  (story/reg-story* :story.auth.login-form
    {:doc "..." :component login-form :args {...}})
  (story/reg-variant* :story.auth.login-form/empty
    {:events [[:auth/initialise]]})
  (story/reg-variant* :story.auth.login-form/validation-error
    {:events [...] :tags #{...}}))
```

The macro emits one top-level form per variant, preserving
hot-reload-by-variant. The `*`-suffix runtime helpers are public for
tooling that programmatically generates registrations (e.g. the MCP
write surface — see [`006-MCP-Surface.md`](006-MCP-Surface.md)).

## Source-coord stamping

Per
[Spec 009 — Instrumentation](../../../spec/009-Instrumentation.md) and
the [`feedback_file_spec_beads_for_implementation_findings`](../../../AGENTS.md)
discipline, every registration captures `{:file <s> :line <n>}` at
macro-expansion time. Story propagates this through:

- Variant registry: `(:source (get-variant id)) => {:file ... :line ...}`.
- `:assertions` list: each assertion entry carries `:source`.
- The story tool's "Open in editor" affordance (v1.1) reads `:source`.

Stage 2's `reg-story*` / `reg-variant*` macros stamp `:source` from
`&form`'s `:line` / `:file` meta into the registry entry. The
play-runner copies the `:source` of each `:play-script` step into the
corresponding `:assertions` record.

## Schema-derivation pipeline

The control-derivation logic walks the registered Malli schema for
`:component` (or an explicit `:schema` slot on the variant/story
body — Story consults the variant body first, then the parent story,
then the registered view) and emits a `{arg-key → widget-spec}` map:

**Scalar forms**

- `:string` → `{:widget :text}`
- `:int` / `:double` (optional `:min` / `:max`) → `{:widget :number}`
- `:boolean` → `{:widget :boolean}`
- `:keyword` → `{:widget :text}` (keyword-coercion at edit time)
- `[:enum a b c]` → `{:widget :select :options [a b c]}`

**Collection forms (rf2-agshe)**

The walker recurses into Malli's standard collection operators. Each
collection widget-spec carries enough data for the renderer to expand
it into nested rows:

- `[:map [k1 s1] [k2 s2] ...]`
  → `{:widget :group :kind :map
      :entries [{:key k1 :widget <recur s1>}
                {:key k2 :widget <recur s2>} ...]}`

  The renderer shows a labelled header per key and one nested row per
  entry. Editing entry `kN` writes through to `[:cell-overrides
  variant-id arg-key kN]`.

- `[:vector X]`
  → `{:widget :repeater :kind :vector :element <recur X>}`

  The renderer shows the current entries one-per-row with an inline
  `[-]` button each and a trailing `[+]` button. Adding seeds a
  default value derived from `X` (`:text → ""`, `:number → 0`,
  `:boolean → false`, `:select → first option`, `:group → {}`,
  nested `:repeater → []`, nested `:tuple → [default-per-position]`).
  Removing an entry slices the underlying vector. Editing entry `i`
  writes through to `[:cell-overrides variant-id arg-key i]`.

- `[:set X]`
  → `{:widget :repeater :kind :set :element <recur X>}`

  Identical to `:vector` but the value round-trips through `set` —
  duplicates collapse and entry order is by stringified value (stable).

- `[:tuple X Y ...]`
  → `{:widget :tuple :kind :tuple
      :positions [<recur X> <recur Y> ...]}`

  Fixed-arity sibling of `:repeater` — one row per declared position;
  no `[+]` / `[-]` affordance. Editing position `i` writes through to
  `[:cell-overrides variant-id arg-key i]`.

When the schema-walker has nothing to consult (no `:schema`, no
matching `:argtypes` key), the controls panel falls back to inferring a
widget-spec from the *value's* CLJS shape — maps recurse as `:group`,
vectors as `:repeater`, scalars classify by `string?`/`integer?`/etc.
This keeps "zero argtypes" stories from collapsing to inert `:text`
widgets for non-trivial value shapes.

Author-supplied `:argtypes` overrides the auto-derivation key-by-key.
Stage 2 owns the macro side; Stage 4 (controls layer) owns the
rendering — see [`003-Render-Shell.md`](003-Render-Shell.md). The
nested-collection walker landed via rf2-agshe.
