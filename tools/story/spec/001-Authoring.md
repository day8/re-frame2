# Story — Authoring

> The registration surface — the seven `reg-*` macros, the EDN-first
> variant contract, the inclusion-tag vocabulary, source-coord
> stamping, and the macro-time validation rules. The contract Stage 2
> implements and Stages 3–8 consume.

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
 :play                  [[:event-id args ...]]   ; post-render events; phase 4
 :args                  {<arg-key> <value>}      ; override/extend story args
 :argtypes              {<arg-key> {...}}        ; override story argtypes
 :tags                  #{:dev :docs ...}        ; subset of registered tags
 :decorators            [[:dec-id args ...]]
 :loaders               [[:loader-event-id ...]] ; async setup; phase 1
 :loaders-complete-when <pred>                   ; vector or registered event-id; see 002-Runtime
 :args->events          {<arg-key> <event-id>}   ; arg → app-db mapping (spec/007 §Args mapping)
 :platforms             #{:server :client}
 :substrates            #{:reagent :uix ...}
 :modes                 #{<mode-id> ...}}        ; cell = (variant × mode)
```

The `:rf/variant` schema (in
[`spec/Spec-Schemas.md`](../../../spec/Spec-Schemas.md)) enforces the
no-fn-slots rule. Stage 2 macros validate the body against this schema
and reject with `:rf.error/variant-shape` on miss.

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
{:doc      "..."
 :layout   :grid | :prose | :variants-grid | :tabs | :custom
 :variants [<variant-id> ...]                    ; for :grid / :variants-grid / :tabs
 :content  [{:type :prose :body "md..."} ...]    ; for :prose
 :render   <view-id>                              ; for :custom (a registered view)
 :modes    #{<mode-id> ...}}
```

The `:variants-grid` layout (per Phase 2 SOTA refinement) renders
every variant of a single parent story side-by-side; it differs from
`:grid` (which renders an explicit `:variants` list) by enumerating
variants from the registry.

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
{:doc  "..."
 :kind :frame-setup
 :init [[:event-id args ...]]                    ; events to dispatch into the variant's frame
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
The render shell reads `(rf/registrations :story-panel)` and lays them out.
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
(rf/reg-tag :auth/regression-set
  {:doc  "Auth regression-suite variants."
   :axis :team})

(rf/reg-tag :alpha
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
(rf/reg-mode :Mode.app/dark-mobile
  {:doc  "Dark theme on a mobile viewport."
   :args {:theme :dark :viewport :mobile :locale :en}})

(rf/reg-mode :Mode.app/light-desktop
  {:args {:theme :light :viewport :desktop :locale :en}})
```

When a variant is rendered against mode M, M's `:args` deep-merge into
the variant's effective args (precedence: global < mode < story <
variant). Each `(variant × mode)` cell has an independent
`snapshot-identity` — see [`002-Runtime.md`](002-Runtime.md) §Snapshot
identity.

## Authoring grammar — worked examples

Worked examples illustrating every aspect of the authoring grammar.

### Minimal example

```clojure
(ns app.stories.button
  (:require [re-frame.story :as story]
            [app.ui.button :refer [button]]))

(story/reg-story :story.ui.button
  {:doc       "Primary action button."
   :component button
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

### Decorators and `:args->events`

```clojure
(rf/reg-decorator :centered-layout
  {:doc  "Centre the rendered content."
   :kind :hiccup
   :wrap (fn [body _]
           [:div.flex.items-center.justify-center.h-screen body])})

(rf/reg-decorator :mock-auth
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
  {:doc    "Full login flow."
   :events [[:auth/initialise]]
   :play   [[:auth/email-changed "alice@example.com"]
            [:auth/password-changed "hunter2"]
            [:auth/login-pressed]
            [:rf.assert/path-equals  [:auth :status] :authenticated]
            [:rf.assert/sub-equals   [:auth/current-user] {:id 42 :name "Alice"}]
            [:rf.assert/state-is     :auth/login :authenticated]
            [:rf.assert/no-warnings]
            [:rf.assert/effect-emitted :navigate]]
   :tags   #{:dev :docs :test}})
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
(rf/reg-mode :Mode.app/dark-mobile
  {:args {:theme :dark :viewport :mobile}})

(rf/reg-mode :Mode.app/light-desktop
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
play-runner copies the `:source` of each `:play` event into the
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
