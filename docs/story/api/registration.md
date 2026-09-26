# Registration

This page covers everything that tells Story what exists: the nine `reg-*` macros and their `*` functions, the id shape and body keys of each kind, global args and decorators, the built-in decorators, and the vocabulary Story installs on the first registration. Write the macros; call the `*` functions from code that builds registrations at runtime.

Registering an id again replaces its entry in place. Under `:advanced` compilation with `re-frame.story.config/enabled?` set to `false`, the `reg-*` macros compile to nothing, so a production build carries no Story registrations, and `mount-shell!` returns before touching the DOM.

## The nine registration macros

All under `re-frame.story`. All paired with a `*`-suffix runtime fn for programmatic use.

### `reg-story`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-story id metadata)
  ```
- **Description**: Register a story (a cluster of variants under one heading). `metadata` is an EDN map with `:doc`, `:component`, `:args`, `:tags`, `:decorators`, and optional `:variants` (the [combined form](#the-combined-reg-story-form)). [Story body](#story-body) lists every key.
- **Example**:
  ```clojure
  (rf.story/reg-story :story.counter
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
- **Description**: Register one variant — view, `:args`, `:setup` events, decorators, `:script`. The single most-called macro in a typical stories namespace.
- **Example**:
  ```clojure
  (rf.story/reg-variant :story.counter/at-five
    {:component :app.ui/counter
     :setup     [[:counter/initialise 5]]})
  ```

### `reg-workspace`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-workspace id metadata)
  ```
- **Description**: Register a workspace — an arrangement of variants for side-by-side review. `metadata` carries `:layout` and the slot that layout needs: `:variants` (an ordered vector of variant ids) for `:grid` and `:tabs`, `:content` for `:prose`, and nothing, `:for` or `:variants` for `:variants-grid`. See [Workspace body](#workspace-body).

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
- **Description**: Register a tag (free-form classification — `#{:auth-required :empty-state :error}`). Tags filter the variant catalogue. The seven canonical tags (`:dev`, `:docs`, `:test`, `:screenshot`, `:experimental`, `:internal`, `:agent`) and the five `:state/*` tags (`:state/empty`, `:state/small`, `:state/medium`, `:state/large`, `:state/special`) auto-install on first registration.

### `reg-mode`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-mode id metadata)
  ```
- **Description**: Register a mode — a saved tuple of args the chrome toggles into (light/dark theme, en/fr locale). Layer 3 of the five-layer args precedence chain.

### `reg-fragment`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-fragment id metadata)
  ```
- **Description**: Register a fragment — reusable setup, script and world context that a variant pulls in by naming its id in `:compose`. A fragment is flat: it carries no `:compose` or `:extends`, and no `:checks` or `:assertions`. See [Fragment and check bodies](#fragment-and-check-bodies).
- **Example**:
  ```clojure
  (rf.story/reg-fragment :fragment.login/submitted-wrong-password
    {:setup [[:login/flow [:login/submit {:email "ada@example.com" :password "wrong"}]]]})
  ```

### `reg-check`

- **Kind**: macro
- **Signature**:
  ```clojure
  (reg-check id metadata)
  ```
- **Description**: Register a check — a named, reusable pack of assertions. A variant takes a check through `:checks` or `:compose`, and checks inherit through `:extends`, where a variant's own `:assertions` do not. A failed check shows its id and the assertion records inside it.
- **Example**:
  ```clojure
  (rf.story/reg-check :check/no-runtime-warnings
    {:assertions [[:rf.assert/no-warnings]]})
  ```

Each macro expands to its `*` function — `reg-story` expands to `(reg-story* id body)` — so the vocabulary auto-install (below) runs whichever form registers first.

### The combined `reg-story` form

`(reg-story id {:variants {...} ...})` expands to one `reg-variant` call per entry plus the plain `reg-story`. Use it when every variant is a small override of the same parent: the parent's `:component`, `:args` and `:decorators` reach each variant through the usual layering.

```clojure
(rf.story/reg-story :story.counter
  {:component :app.ui/counter
   :args      {:label "Count" :max 100}
   :variants  {:empty      {:setup [[:counter/initialise 0]]}
               :at-five    {:setup [[:counter/initialise 5]]}
               :at-max     {:setup [[:counter/initialise 100]]}}})
```

This registers `:story.counter/empty`, `:story.counter/at-five` and `:story.counter/at-max`, plus the parent `:story.counter` itself. Each variant id takes the story's name as its namespace.

## The `*` functions

Each macro has a function partner under `re-frame.story`, taking the same `id` and `body`:

| Function | Registers |
|---|---|
| `(reg-story* id body)` | a story |
| `(reg-variant* id body)` | a variant |
| `(reg-workspace* id body)` | a workspace |
| `(reg-mode* id body)` | a mode |
| `(reg-fragment* id body)` | a fragment |
| `(reg-check* id body)` | a check |
| `(reg-story-panel* id body)` | a story panel |
| `(reg-decorator* id body)` | a decorator |
| `(reg-tag* id body)` | a tag |

Call them where a macro cannot go: inside a higher-order function, a fixture loader, an MCP write tool, or a hot-reload tool that builds registrations from other data. Both forms write the same registry and run the same auto-install.

## Ids and bodies

### Id shapes

Each kind checks its id at registration and throws `:rf.error/<kind>-id-shape` (such as `:rf.error/variant-id-shape`) on a miss:

| Kind | Id shape | Example |
|---|---|---|
| story | unqualified, name starting `story.` | `:story.login` |
| variant | namespace starting `story.` | `:story.login/error` |
| workspace | namespace `Workspace` or starting `Workspace.` | `:Workspace.login/all-states` |
| mode | namespace `Mode` or starting `Mode.` | `:Mode.app/dark` |
| fragment, check, decorator, story-panel, tag | any keyword | `:fragment.login/filled`, `:check/no-warnings` |

### Closed bodies

Every body is a closed map. An unknown or misspelt key throws `:rf.error/<kind>-shape`, such as `:rf.error/variant-shape`, and the message names the key and the nearest valid one. A value of the wrong shape throws the same error. The tables below list every key each kind accepts.

### Story body

| Key | Value |
|---|---|
| `:doc` | A string. |
| `:component` | The view id every variant renders, unless it names its own. |
| `:args` | Default args for every variant. |
| `:argtypes` | Control choices, keyed by arg: `{:heading {:control :textarea}}`. The controls are `:text`, `:textarea`, `:number`, `:boolean`, `:select`, `:radio`, `:date` and `:color`; `:select` and `:radio` take `:options`. |
| `:decorators` | Decorator references, `[[decorator-id & args] ...]`, applied inside the global decorators. |
| `:tags` | The default tags for variants that declare none. |
| `:substrates` | The default substrates, from `#{:reagent :uix :fresco}`. |
| `:platforms` | A subset of `#{:client :server}`; `#{:client}` by default. Shown in Explain and Docs mode. |
| `:modes` | Mode ids. Shown in Docs mode; the shell applies the modes chosen in the toolbar. |
| `:viewport` | A viewport preset id (`:full`, `:mobile-portrait`, `:mobile-landscape`, `:tablet`, `:desktop`, `:desktop-wide`) or `{:width w :height h}`. |
| `:background` | A background preset id (`:light`, `:dark`, `:paper`, `:midnight`, `:transparent`) or a CSS colour string. |
| `:xray-panel` | The Xray panel the right rail opens on: `:epoch` (default), `:app-db`, `:views`, `:trace`, `:machines` or `:routing`. |
| `:xray` | A preset for the full Xray shell: `{:open? bool :panel panel-id :filters {:out [event-id ...] :in [event-id ...]}}`. Its `:panel` also picks the right rail's panel when the body names no `:xray-panel`. |
| `:dispatch-console?` | `true` opens the Dispatch console by default. |
| `:images` | `rf/image` values the variants' frames resolve behaviour through. |
| `:variants` | A map of variant name to variant body, each registered as `:<story-id>/<name>` (the combined form above). |

### Variant body

A variant renders its story's `:component` unless it names its own. Its args, argtypes and decorators layer over the story's, and its tags, substrates, viewport, background and Xray settings fall back to the story's when it declares none.

| Key | Value |
|---|---|
| `:doc` | A string. |
| `:component` | The view id, when the variant renders a different view from its story. |
| `:extends` | Another variant id to inherit from (see the tutorial's chapter 7). |
| `:compose` | Fragment and check ids, applied in order. |
| `:setup` | Event vectors dispatched before the script. Only dispatch steps may appear here. |
| `:script` | A step vector, or `{:script [...] :auto-run? bool :name "..."}`. See [Scripts](script.md). |
| `:plays` | Named scripts, `[{:name "..." :script [...] :auto-run? bool} ...]`, instead of `:script`. Declaring both throws. |
| `:args` | Args, deep-merged over the story's. |
| `:argtypes` | Control choices, as on the story; the variant's win. |
| `:db-seed` | App-db slices, keyed by top-level key or path vector, merged in and schema-checked before the script. Marks the variant `:db-seed` fidelity. |
| `:sub-overrides` | Subscription query vector to pinned value, for rendering only. Marks the variant `:sub-overrides` fidelity. |
| `:network` | Managed-HTTP stubs, `{[method url] {:reply {:ok data}}}` or `{:reply {:failure {:kind ...}}}`. `method` is one of `:get`, `:post`, `:put`, `:patch`, `:delete`, `:head` and `:options`. |
| `:fx-overrides` | Effect id to a replacement effect handler, installed on the variant's frame. |
| `:interceptor-overrides` | Interceptor reference to a replacement, or to `nil` to remove it, on the variant's frame. |
| `:assertions` | Assertions run after the script settles. Not inherited. |
| `:checks` | Check ids. Inherited through `:extends`. |
| `:tags` | Tags; a `:!tag` entry removes an inherited tag. |
| `:decorators` | Decorator references, applied inside the story's. |
| `:loaders` | Event vectors dispatched while the variant loads, before `:setup`. |
| `:loaders-complete-when` | A registered event id, or event vectors, that decide when loading is done. |
| `:loaders-teardown` | Event vectors dispatched when the variant's frame is destroyed. |
| `:args->events` | Arg key to event id. After `:setup`, each mapped arg's value is dispatched to its event as `[event-id value]`, so the frame holds what the control shows; a Controls edit re-runs the variant with the new value. |
| `:substrates` | The substrates to render under, from `#{:reagent :uix :fresco}`. |
| `:platforms` | A subset of `#{:client :server}`. |
| `:modes` | Mode ids, as on the story. |
| `:viewport`, `:background` | As on the story; the variant's win, and both beat the toolbar. |
| `:xray-panel`, `:xray`, `:dispatch-console?` | As on the story; the variant's win. |
| `:frame-binding` | `:fresh` (default) or `:attached`, shown on the sidebar's frame chip. |
| `:mcp-bound` | `true` marks the frame chip as bound to an MCP session. |
| `:sensitive`, `:large` | `{:app-db [path ...]}`: app-db paths to redact, or to replace with a size marker, when observed. See [Privacy](#privacy--variant-body-classification). |
| `:images` | `rf/image` values the variant's frame resolves behaviour through. |
| `:source`, `:origin`, `:run-artifact` | Written by Story: the source location, the tool that registered the variant, and the run a promoted variant came from. |

`:resolve-conflicts` is rejected: a variant resolves a `:compose` conflict by stating the value itself.

### Fragment and check bodies

A fragment accepts `:doc`, `:args`, `:argtypes`, `:setup`, `:script`, `:network`, `:sub-overrides`, `:db-seed`, `:fx-overrides`, `:interceptor-overrides`, `:loaders`, `:loaders-teardown` and `:decorators`, with the variant meanings above. It rejects `:compose` and `:extends`, so fragments never nest, and `:checks` and `:assertions`, which belong to checks.

A check accepts `:doc` and a required `:assertions` vector.

### Workspace body

| Key | Value |
|---|---|
| `:doc` | A string. |
| `:layout` | Required: `:grid`, `:variants-grid`, `:tabs` or `:prose`. |
| `:variants` | Variant ids, in order. Required by `:grid` and `:tabs`. A `:variants-grid` that lists them renders exactly those instead of enumerating a story; it takes `:variants` or `:for`, not both. |
| `:for` | The story a `:variants-grid` enumerates. Without it, the workspace id names the story. |
| `:columns` | A fixed column count for `:grid` and `:variants-grid`. |
| `:content` | For `:prose`, required: `[{:type :prose :body "markdown"} {:type :variant :id variant-id} ...]`. |
| `:isolation` | For `:variants-grid`: `:isolated` (default) mounts every cell at once; `:shared` mounts one at a time. |
| `:tags` | Tags. |
| `:modes` | Mode ids; accepted, but the shell does not act on them. |

A body whose slots do not match its `:layout` throws `:rf.error/workspace-shape`.

### Mode body

`:args` (required) is the map the mode adds to the args chain. `:axis` groups modes in the toolbar, one active per axis; modes without one can all be on together. `:doc` is a string.

### Decorator body

| `:kind` | Other keys |
|---|---|
| `:hiccup` | `:wrap`, a function of the rendered body and the effective args that returns hiccup. |
| `:frame-setup` | At least one of `:init` (event vectors dispatched before render), `:app-db-patch` (a map merged into app-db) and `:teardown` (event vectors dispatched when the frame is destroyed). |
| `:fx-override` | `:fx-id` and `:response`, or `:ref-args? true` to take both from the reference, as `force-fx-stub-id` does. |

Every decorator body also accepts `:doc`.

### Story-panel body

`:title` (a string), `:placement` (`:right`, `:left`, `:bottom`, `:top` or `:modal`) and `:render` (a view id, rendered with the selected variant id) are required. `:for` optionally limits the panel to a set of variant and story ids; the panel shows when the selected variant, or its story, is in the set. `:doc` is a string.

### Tag body

`:doc` is a string. `:axis` groups the tag in the sidebar's tag filter. `:default-filter` is `:include` (the default) or `:exclude`, which hides the tag's variants from the sidebar until you toggle the tag on in the filter.

## Unregister + reset

### `unregister!`

- **Signature**:
  ```clojure
  (unregister! kind id) → nil
  ```
- **Description**: Remove a single id under `kind`. Kinds: `:story` / `:variant` / `:workspace` / `:fragment` / `:check` / `:story-panel` / `:tag` / `:mode` / `:decorator`.

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
- **Description**: Reset every Story registration. Wipes the registrar's side-table, resets everything `configure!` set (global args and decorators, editor, project root, egress profile), and resets the auto-install gate so the next `reg-*` re-installs the canonical vocabulary.

`clear-all!` is the test-isolation primitive. Tests that want a known starting state call `clear-all!` in a fixture; the next `reg-*` in the test body auto-installs the canonical vocabulary (the canonical and `:state/*` tags, the `:rf.assert/*` handlers, `force-fx-stub`, the layout-debug decorator trio, the lifecycle machine, the built-in panels) before the test's own registrations land.

## Canonical-vocabulary auto-install

Story's built-in vocabulary installs on the first `reg-*` call, so there is no boot step to write.

The first call to any of the nine `*` functions flips a single boolean gate in `re-frame.story.canonical` and runs the installers: they register the seven canonical tags and the five `:state/*` tags, the lifecycle machine, the `:rf.assert/*` event handlers, the `force-fx-stub` decorator, the three layout-debug decorators, the toolbar's coeffects and subscriptions, and, in ClojureScript, the Reagent substrate, open-in-editor and the built-in panels.

### `install-canonical-vocabulary!`

- **Signature**:
  ```clojure
  (install-canonical-vocabulary!) → nil
  ```
- **Description**: Idempotent explicit boot. The auto-install path makes it unnecessary; call it when a host wants a literal boot step, or when a test wants the vocabulary installed before any `reg-*` call.

The gate flips before the installers run, so the registrations they make do not trigger the install again. After that, each `reg-*` call pays only a `deref` and a check.

`(clear-all!)` resets the gate to `false`; the next `reg-*` re-runs the full auto-install path. A fixture that calls `install-canonical-vocabulary!` after `clear-all!` does no harm — the auto-install the first `reg-*` would run finds the gate already flipped — but it needs no such step.

## Global args and global decorators

Global args, the first of the five args layers, and the global-decorator stack are project-wide defaults the host sets once at boot, through `configure!`. The decorator stack also has `reg-global-decorator`, which registers a decorator and adds it to the stack in one call.

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
- **Description**: Register a decorator and add it to the global stack in one call, as `reg-decorator` followed by `configure! {:rf.story/global-decorators [...]}` would. Prefer it for a decorator used only globally. The earliest registered is outermost, and re-registering an id replaces its entry at the same position, so hot reload does not reorder the stack.

### `clear-global-decorator`

- **Signature**:
  ```clojure
  (clear-global-decorator id) → nil
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
1. global args      ← (rf.story/configure! {:rf.story/global-args {...}})    — boot
2. story args       ← :args on the parent (reg-story)                         — story default
3. mode args        ← active :mode's :args (reg-mode)                          — saved tuple
4. variant args     ← :args on the variant (reg-variant)                      — per-scenario
5. cell-overrides   ← Controls edits at runtime (run opts :cell-overrides)     — live edit
                     ↓
              effective args (deep-merge, vectors replaced)
```

Global args suit boot configuration such as a theme, a locale or feature flags; story args set the parent's defaults; mode args switch every variant at once from the toolbar; variant args set one scenario; cell overrides let a reader turn a knob without editing source. Put a value in the layer whose scope matches the variants it should reach.

## Built-in decorator `*-id` Vars

Four built-in decorators ship with Story. Refer to each through its `*-id` Var on `re-frame.story` in a `:decorators` slot, rather than through the keyword it is registered under: a misspelt Var fails to compile, and the keyword can change without breaking your stories.

### `force-fx-stub-id`

- **Kind**: Var
- **Description**: The registered decorator id for the built-in `force-fx-stub` decorator — Story's universal effect-mocking primitive. Referenced as `[rf.story/force-fx-stub-id fx-id response]`, it takes over every call to `fx-id`, recording the call with its payload and `response` instead of performing the effect; nothing is dispatched back. One decorator covers HTTP, websockets, analytics, storage, navigation, geolocation, and anything else registered with `reg-fx`, and `:rf.assert/effect-emitted` still sees the effect as emitted.

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
(rf.story/reg-variant :story.auth/login-pending
  {:decorators [[rf.story/force-fx-stub-id :http {:status :pending}]]
   :script     [[:dispatch [:auth/login]]
                [:dispatch-sync [:rf.assert/effect-emitted :http]]]})

;; Layout debug a button variant.
(rf.story/reg-variant :story.button/pressed
  {:decorators [[rf.story/layout-debug-outline-id]
                [rf.story/layout-debug-pseudo-id #{:hover}]]})
```

## Privacy — variant-body classification

A variant declares its sensitive / large app-db paths via the `:sensitive` / `:large` slots on its body, keyed by `:app-db`:

```clojure
(rf.story/reg-variant :story.auth/login-form
  {:component :my-app.views/login-form
   :args      {:user/email "ada@example.com"
               :user/password "•••••"}
   :sensitive {:app-db [[:user :password] [:auth :token]]}
   :large     {:app-db [[:docs :csv-upload]]}})
```

Right after it creates the variant's frame, and before any lifecycle or setup event runs, Story records these paths in the frame's classification registry, the same registry a `reg-event` handler writes when it returns `:sensitive` or `:large`. So a classified path is already redacted in any trace the variant's setup emits, and every Story surface that redacts (assertion records, the trace buffer, the recorder, DOM capture) sees it. A malformed declaration throws a classification-effect shape error.

The slots take only `:app-db` paths, and only on the variant body: a frame config carrying `:sensitive {:app-db …}` throws. Story has no function for marking paths after the frame exists. Transient values are classified where they are registered: event arguments and effect or coeffect values through `:sensitive` / `:large` metadata on `reg-event`, `reg-fx` or `reg-cofx`, and HTTP carriers on the `:rf.http/managed` effect's `:carriers` block.

## See also

- [Scripts](script.md) — the `:script` grammar a `reg-variant`'s `:script` slot accepts. The canonical seven `:rf.assert/*` events that drive the variant's assertion accumulator.
- [Runtime](runtime.md) — `configure!`'s full key surface, the variant lifecycle, `run-variant` / `reset-variant` / `watch-variant` / `destroy-variant!`, the registry-query family, the shell-mount surface.
- [MCP surface](mcp-surface.md) — the public read primitives Story exposes for the MCP jar to consume; the public write primitives behind the gated agent-write surface; the late-bind `reg-story-panel` contract.
- [Reference](reference.md) — the full symbol table for `Ctrl-F` use.
- [Story tutorial — Your first variant](../01-first-variant.md) — the chapter-1 worked walkthrough.
- [Story tutorial — Workspaces, modes, and composition](../07-workspaces-modes-composition.md) — workspaces, modes, the args editor.
- [Framework API — Schemas and data classification](../../api/re-frame.schemas.md) — the framework's commit-plane `:sensitive` / `:large` classification model.
