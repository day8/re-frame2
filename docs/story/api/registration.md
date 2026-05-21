# Registration

This chapter is about the surfaces that tell Story *what variants exist* — the seven `reg-*` macros, their `*`-suffix runtime helpers, the inclusion-tag and mode vocabulary, the two boot-time entry points for global args and global decorators, and the canonical-vocabulary auto-install that fires on the first registration. The core of it is **two parallel surfaces** — `reg-story` / `reg-variant` / etc. for authoring, and `reg-story*` / `reg-variant*` / etc. for programmatic registration. The macro form is what you write; the `*`-fn form is what hot-reload tooling, fixture loaders, and the MCP write surface call.

Every registration is **idempotent**: re-registering the same id replaces the entry in place. The seven `reg-*` macros all DCE under `:advanced` compilation when `re-frame.story.config/enabled?` is `false` — production builds carry zero Story registration bytes, and `mount-shell!` short-circuits before any DOM call.

## The seven registration macros

All under `re-frame.story`. All paired with a `*`-suffix runtime fn for programmatic use.

### `reg-story`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-story id metadata)
  ```
- **Description**: Register a story (a cluster of variants under one heading). `metadata` is an EDN map with `:doc`, `:component`, `:args`, `:tags`, `:decorators`, and optional `:variants` (Form B desugaring).
- **Example**:
  ```clojure
  (story/reg-story :story.counter
    {:doc       "The app counter."
     :component :app.ui/counter
     :args      {:label "Count"}})
  ```

### `reg-variant`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-variant id metadata)
  ```
- **Description**: Register one variant — view, args, setup events, decorators, `:play-script`. The single most-called macro in a typical stories namespace.
- **Example**:
  ```clojure
  (story/reg-variant :story.counter/at-five
    {:component :app.ui/counter
     :events    [[:counter/initialise 5]]})
  ```

### `reg-workspace`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-workspace id metadata)
  ```
- **Description**: Register a workspace — a curated grid of variants for side-by-side review. `metadata` carries `:doc`, `:cells` (an ordered vector of variant ids), and optional `:layout`.

### `reg-decorator`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-decorator id metadata)
  ```
- **Description**: Register a decorator — a fn that wraps a variant's render (locale provider, theme provider, mock-API context). Three kinds: `:hiccup` (wraps the rendered tree), `:frame-setup` (runs at frame creation), `:fx-override` (registers fx stubs).

### `reg-story-panel`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-story-panel id metadata)
  ```
- **Description**: Register a custom panel in the Story chrome — the inspection / control panes that sit beside the rendered variant. Late-bind via `:render` as a `:view` id. Five placement slots: `:right` / `:left` / `:bottom` / `:top` / `:modal`.

### `reg-tag`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-tag id metadata)
  ```
- **Description**: Register a tag (free-form classification — `#{:auth-required :empty-state :error}`). Tags filter the variant catalogue. The seven canonical tags (`:dev`, `:docs`, `:test`, `:screenshot`, `:experimental`, `:internal`, `:agent`) auto-install on first registration.

### `reg-mode`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-mode id metadata)
  ```
- **Description**: Register a mode — a saved tuple of args the chrome toggles into (light/dark theme, en/fr locale, desktop/mobile viewport). Layer 3 of the five-layer args precedence chain.

The macros expand to their `*`-suffix runtime fns — `reg-story` expands to `(reg-story* id body)` — so the canonical-vocabulary auto-install (see below) fires from the macro form, programmatic-form, or fixture-load form alike.

### Combined `reg-story` Form B

The combined form `(reg-story id {:variants {...} ...})` desugars at macro-expansion time to N independent `reg-variant` calls plus the bare `reg-story`. This is the ergonomic shortcut for a story whose every variant is a thin override of the same parent body — the parent's `:component` / `:args` / `:decorators` flow into each variant by deep-merge.

```clojure
(story/reg-story :story.counter
  {:component :app.ui/counter
   :args      {:label "Count" :max 100}
   :variants  {:empty      {:events [[:counter/initialise 0]]}
               :at-five    {:events [[:counter/initialise 5]]}
               :at-max     {:events [[:counter/initialise 100]]}}})
```

The expansion produces three `reg-variant` calls — `:story.counter/empty`, `:story.counter/at-five`, `:story.counter/at-max` — plus the parent `:story.counter` itself. The variant ids are namespaced under the story's id; the parent's args and component flow into each variant's resolved args via the standard precedence chain.

## The seven `*`-suffix runtime helpers

All under `re-frame.story`. The `*`-suffix helpers are the programmatic write path; the macros above expand into them.

### `reg-story*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-story* id body)
  ```
- **Description**: Programmatic story registration.

### `reg-variant*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-variant* id body)
  ```
- **Description**: Programmatic variant registration.

### `reg-workspace*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-workspace* id body)
  ```
- **Description**: Programmatic workspace registration.

### `reg-mode*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-mode* id body)
  ```
- **Description**: Programmatic mode registration.

### `reg-story-panel*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-story-panel* id body)
  ```
- **Description**: Programmatic panel registration.

### `reg-decorator*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-decorator* id body)
  ```
- **Description**: Programmatic decorator registration.

### `reg-tag*`

- **Kind**: function
- **Signature**:
  ```clojure
  (reg-tag* id body)
  ```
- **Description**: Programmatic tag registration.

Reach for the `*` forms when authoring inside a higher-order fn, a fixture loader, an MCP write tool, or a hot-reload pipeline that synthesises registrations from another data source. Authoring-site registrations use the macros above; both paths land on the same registrar side-table and fire the same auto-install gate.

## Unregister + reset

### `unregister!`

- **Signature**:
  ```clojure
  (unregister! kind id) → nil
  ```
- **Description**: Remove a single id under `kind`. Kinds: `:story` / `:variant` / `:workspace` / `:story-panel` / `:tag` / `:mode` / `:decorator`.

### `clear-kind!`

- **Signature**:
  ```clojure
  (clear-kind! kind) → nil
  ```
- **Description**: Remove every registration of `kind`. Used by test fixtures and hot-reload.

### `clear-all!`

- **Signature**:
  ```clojure
  (clear-all!) → nil
  ```
- **Description**: Reset every Story registration. Wipes the registrar's side-table AND clears the global-decorators vector AND resets the auto-install gate so the next `reg-*` re-installs the canonical vocabulary.

`clear-all!` is the test-isolation primitive. Tests that want a known starting state call `clear-all!` in a fixture; the next `reg-*` in the test body auto-installs the canonical vocabulary (the seven canonical tags, the `:rf.assert/*` handlers, `force-fx-stub`, the layout-debug decorator trio, the lifecycle machine, the v1.0 panel set) before the test's own registrations land.

## Canonical-vocabulary auto-install

The canonical Story vocabulary auto-installs on the first `reg-*` call. Authors don't call `(story/install-canonical-vocabulary!)` explicitly; the boot is implicit, matching Storybook's ergonomic.

The seven `reg-*` macros expand to their `*`-fn helpers, and the first call to ANY of those helpers flips a single boolean gate in `re-frame.story.canonical` and runs the installer chain: register the seven canonical tags, register the `:rf.assert/*` event handlers, register the `force-fx-stub` decorator, register the layout-debug decorator trio, register the toolbar cofx + subs, register the lifecycle machine, register the v1.0 SOTA panel set, and (CLJS only) register the multi-substrate Reagent default.

### `install-canonical-vocabulary!`

- **Signature**:
  ```clojure
  (install-canonical-vocabulary!) → nil
  ```
- **Description**: Idempotent explicit boot. New code should rely on the auto-install path — the explicit call is retained only as a literal-boot affordance for hosts that want one and as a JVM-test diagnostic that asserts a known starting state without a body-of-test `reg-*` call.

The auto-install gate flips true *before* the installer chain runs, so the registrar writes triggered by the chain itself hit the early-return branch and don't recurse. Subsequent `reg-*` calls (after the gate has flipped) are a single `deref` + `nil` check — negligible on the hot path.

`(clear-all!)` resets the gate to `false`; the next `reg-*` re-runs the full auto-install path. Test fixtures that called `clear-all!` followed by `install-canonical-vocabulary!` under v1 still work — the explicit call is a no-op overlap with the auto-install path that would fire on the first body-of-test `reg-*` anyway — but new tests can drop the explicit boot step entirely.

## Global args and global decorators

Layer 1 of the five-layer args precedence chain (global → story → mode → variant → cell-override) and the global-decorator stack are both project-wide defaults the host application sets once at boot. Both flow through `configure!`; the global-decorator stack additionally has per-registration helpers for the common "register-and-opt-in" path.

### `configure!` (global args + decorators)

- **Signature**:
  ```clojure
  (configure! opts) → nil
  ```
- **Description**: Top-level Story configuration. See [Runtime §configure!](runtime.md#configure) for the full key surface. The two relevant keys here are `:rf.story/global-args` (replace the global args map) and `:rf.story/global-decorators` (replace the global-decorator ref vector).

### `reg-global-decorator`

- **Signature**:
  ```clojure
  (reg-global-decorator id body) → id
  (reg-global-decorator id body ref-args) → id
  ```
- **Description**: Register a decorator AND opt it into the global stack in one call. Symmetric to the host calling `reg-decorator` + `configure! {:rf.story/global-decorators [...]}` in sequence; preferred when the decorator is exclusively a global-stack member. Earliest-registered-first; re-registering the same id REPLACES the entry in place (same position in the global vector) so hot-reload doesn't reshuffle the stack order.

### `unreg-global-decorator!`

- **Signature**:
  ```clojure
  (unreg-global-decorator! id) → nil
  ```
- **Description**: Remove `id` from the global-decorators vector. The decorator's registration body is NOT unregistered — call `unregister!` for that. Idempotent.

### `global-decorators`

- **Signature**:
  ```clojure
  (global-decorators) → vec
  ```
- **Description**: Return the current ordered vector of global-decorator references (`[[decorator-id & args] ...]`). Earliest-registered first; this is the prefix applied to every variant's resolved decorator stack.

The five-layer precedence diagram (later wins):

```
1. global args      ← (story/configure! {:rf.story/global-args {...}})       — boot
2. story args       ← :args on the parent (reg-story)                         — story default
3. mode args        ← active :mode's :args (reg-mode)                          — saved tuple
4. variant args     ← :args on the variant (reg-variant)                      — per-scenario
5. cell-overrides   ← controls-panel edits at runtime (:story/set-arg)         — live edit
                     ↓
              effective args (deep-merge, vectors replaced)
```

Each layer scopes a different authoring intent: global args ride boot configuration (themes, locales, feature flags); story args set the parent default; mode args pivot the entire variant-grid against a chrome-level toggle; variant args carry the per-scenario override; cell-overrides give the reader a knob without mutating source. Reach for the lowest-numbered layer that scopes the change you want.

## Built-in decorator `*-id` Vars

Three built-in decorators ship with Story's canonical vocabulary. Each has a public `*-id` Var on `re-frame.story` for use in variant `:decorators` slots — the Var pattern lets the registered-id keyword stay opaque (Story can rename the underlying registration without breaking author code).

### `force-fx-stub-id`

- **Kind**: Var
- **Description**: The registered decorator id for the built-in `force-fx-stub` decorator — Story's universal effect-mocking primitive. One decorator covers HTTP, websockets, analytics, storage, navigation, geolocation, and anything else registered with `reg-fx`.

### `layout-debug-measure-id`

- **Kind**: Var
- **Description**: The Storybook-style layout measure overlay decorator id. Surfaces margin / padding / size annotations on every descendant element.

### `layout-debug-outline-id`

- **Kind**: Var
- **Description**: The Pesticide-style coloured outlines decorator id. Surfaces every descendant element's box with a per-element-type colour.

### `layout-debug-pseudo-id`

- **Kind**: Var
- **Description**: The pseudo-state forcing decorator id. Ref-args is a set from `#{:hover :focus :active :visited}`; default is `#{:hover}`.

Worked example:

```clojure
;; Stub :http for a login-pending variant.
(story/reg-variant :story.auth/login-pending
  {:decorators  [[story/force-fx-stub-id :http {:status :pending}]]
   :play-script [[:dispatch [:auth/login]]
                 [:dispatch-sync [:rf.assert/effect-emitted :http]]]})

;; Layout debug a button variant.
(story/reg-variant :story.button/pressed
  {:decorators [[story/layout-debug-outline-id]
                [story/layout-debug-pseudo-id #{:hover}]]})
```

## Privacy primitives

Story authors declare per-frame path-marks against `app-db` using the framework's `add-marks` / `set-marks` primitives. Both are re-exported from `re-frame.core` for author-discoverability — same primitives, same data model, same per-frame semantics — so authors scanning `re-frame.story`'s public surface for privacy primitives find them without chasing cross-references.

### `add-marks`

- **Signature**:
  ```clojure
  (add-marks variant-id marks-map) → nil
  ```
- **Description**: Merge marks additively into the variant frame's mark-set. Re-export of `re-frame.core/add-marks`.

### `set-marks`

- **Signature**:
  ```clojure
  (set-marks variant-id marks-map) → nil
  ```
- **Description**: Replace the variant frame's mark-set wholesale. Re-export of `re-frame.core/set-marks`.

A `marks-map` is `{path mark, ...}` where `path` is a `get-in`-shaped vector and `mark` is one of `:sensitive` / `:large`. Variant-body usage scopes marks to that variant's frame; the framework's `elide-wire-value` walker substitutes `:rf/redacted` / `:rf/large` at every wire-egress observation point.

## See also

- [Play scripts](play-script.md) — the `:play-script` grammar a `reg-variant`'s `:play-script` slot accepts. The canonical seven `:rf.assert/*` events that drive the variant's assertion accumulator.
- [Runtime](runtime.md) — `configure!`'s full key surface, the four-phase variant lifecycle, `run-variant` / `reset-variant` / `watch-variant` / `destroy-variant!`, the registry-query family, the shell-mount surface.
- [MCP surface](mcp-surface.md) — the public read primitives Story exposes for the MCP jar to consume; the public write primitives behind the gated agent-write surface; the late-bind `reg-story-panel` contract.
- [Reference](reference.md) — the full symbol table for `Ctrl-F` use.
- [Story tutorial — Your first story](../01-first-story.md) — the chapter-1 worked walkthrough.
- [Story tutorial — Workspaces + args editor](../04-workspaces.md) — workspaces, modes, the args editor.
- [Framework API — Schemas and data classification](../../api/08-schemas.md) — `add-marks` / `set-marks` framework definitions.
