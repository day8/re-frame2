# Runtime

This chapter is about the surfaces that bring a registered variant to life — the four-phase lifecycle that allocates a per-variant frame, runs the variant's setup events, renders against the post-setup `app-db`, and walks the play script; the programmatic entry points (`run-variant`, `reset-variant`, `watch-variant`, `destroy-variant!`) that callers reach for from custom shells, test fixtures, and one-shot screenshot pipelines; the registry-query family that tools build against; the boot-time `configure!` surface that sets project-wide defaults; and the CLJS-only shell-mount surface that wires Story's three-pane chrome into the host's DOM.

The core of it is **one runtime, two consumer audiences**. *Story authors* run variants implicitly — the Story shell calls `run-variant` / `reset-variant` / `watch-variant` on the author's behalf as the user clicks through the sidebar. *Host applications and test fixtures* call the same fns directly when they want a one-shot render outside the chrome, a `cljs.test`-shaped assertion, or a snapshot-identity hash for visual-regression keying.

## Per-variant frame allocation

Every variant runs in its own frame. At variant mount the runtime calls `(rf/reg-frame variant-id {:doc ... :app-db {} :substrate :reagent ...})`, records side-table metadata (view id, decorators, play script, tags, modes, substrates), and runs the four-phase lifecycle. At unmount the runtime calls `(rf/destroy-frame! variant-id)` — any state-machines the variant spawned receive their `:rf.machine/destroy` event as part of frame teardown.

Hot-reload preserves the side-table; a re-registration of the same variant calls `reset-frame!` and re-runs the lifecycle.

### Coexistence with host application state

Story installs runtime slots into every variant frame's `app-db` under the reserved `:rf.story/*` namespace:

- `:rf.story/lifecycle` — discrete state of the four-phase lifecycle machine.
- `:rf.story/loaders-complete?` — boolean signal read by the `:loaders-complete-when` predicate path.
- `:rf.story/assertions` — vector of assertion records appended by `:rf.assert/*` handlers during phase 4.

A host application's `reg-event` handlers — and any other code path that writes `app-db` — MUST preserve the `:rf.story/*` namespace when seeding or resetting `db`. The hazard is the "replace-the-whole-db" idiom: a handler returning `{:db {...}}` built from scratch wipes the reserved slots and corrupts every Story variant that runs the event. Build the `:db` return by threading the incoming `db` through — `{:db (assoc db ...)}` or `{:db (merge db {...})}` — don't throw it away.

## The four-phase lifecycle

For every variant mount, strict order, drain to completion between phases:

| Phase | Trigger | Semantics |
|---|---|---|
| **1. Loaders** | Variant body's `:loaders` | For each event: `dispatch-sync` into the variant's frame, wait for drain to settle, evaluate `:loaders-complete-when` if provided. Long-lived fx (`:websocket`, `:interval`) are "complete" when the first message arrives; HTTP-flavoured fx is complete when the response event has been dispatch-synced. |
| **2. Setup** | `(concat story-setup variant-setup)` | `dispatch-sync` the `:setup` events in order. Drain to completion between events. |
| **3. Render** | View registered against post-setup `app-db` | The view renders with the effective args (five-layer precedence chain) and decorator stack (`(concat globals story variant)`) applied. |
| **4. Script** | Variant body's `:script` | For each step: dispatch-sync, drain. `:rf.assert/*` records into `:assertions`; failures don't throw — they accumulate. See [Scripts](script.md). |

Phase 1 and 4 are async-safe; phases 2 and 3 are sync. Loader failure modes are deterministic — handler-throw, typed `:loader-rejection`, and never-complete-predicate all surface as recorded assertions on `:rf.story/assertions` and park the lifecycle machine at `:error` / `:loading`. The play sequence never runs in a failed-loader case; `(run-variant)` resolves with `assertions-passing?` false.

## Programmatic runtime

All under `re-frame.story`. Reach for these from a custom shell, a test fixture, a `cljs.test` adapter, an MCP-tool body, or any host that wants to materialise a variant outside the standard Story chrome.

### `run-variant`

- **Signature**:
  ```clojure
  (run-variant variant-id) → result-map
  (run-variant variant-id opts) → result-map
  ```
- **Description**: Materialise the variant — allocate the frame, run the four-phase lifecycle, return the result map. One-shot; no live updates. The result carries `:frame` / `:app-db` / `:assertions` / `:rendered-hiccup` / `:elapsed-ms` / `:snapshot` / `:decorators`.

### `reset-variant`

- **Signature**:
  ```clojure
  (reset-variant variant-id) → nil
  ```
- **Description**: Reset the variant's frame to its post-events baseline. The Story shell calls this when the user clicks "reset" on a variant.

### `watch-variant`

- **Signature**:
  ```clojure
  (watch-variant variant-id) → live-result-map
  (watch-variant variant-id callback)
  ```
- **Description**: Like `run-variant` but the result map updates live as `app-db` changes. Use for live shells; use `run-variant` for one-shot screenshots.

### `unwatch-variant`

- **Signature**:
  ```clojure
  (unwatch-variant variant-id) → nil
  ```
- **Description**: Stop the live update channel for `variant-id`. Idempotent.

### `destroy-variant!`

- **Signature**:
  ```clojure
  (destroy-variant! variant-id) → nil
  ```
- **Description**: Tear down the variant's frame. Any spawned state-machines receive their `:rf.machine/destroy` event. Idempotent.

### `execute-play!`

- **Signature**:
  ```clojure
  (execute-play! variant-id) → assertions-vec
  ```
- **Description**: Re-run only phase 4 (the `:script`) against the variant's current `app-db`. Use for the step-debugger UI's "re-run from here" affordance.

### `lifecycle-state`

- **Signature**:
  ```clojure
  (lifecycle-state variant-id) → keyword
  ```
- **Description**: The current state of the variant's lifecycle machine — one of `:idle` / `:loading` / `:events` / `:rendering` / `:playing` / `:done` / `:error`.

The `opts` map for `run-variant` accepts:

```clojure
{:active-modes    [:Mode.app/dark-large]   ;; coll of mode ids, deep-merged into args
 :cell-overrides  {:label "Override"}      ;; controls-panel-shaped runtime overrides
 :substrate       :reagent                 ;; / :uix / :helix
 :render?         true                     ;; when truthy, :rendered-hiccup is populated
 :assertions      <hook>}                  ;; assertions hook (re-frame.story.assertions)
```

The result map shape:

```clojure
{:frame           :story.counter/at-five
 :app-db          {...}
 :assertions      [{:assertion :rf.assert/path-equals :passed? true ...} ...]
 :rendered-hiccup [...]    ;; or nil when :render? was falsy
 :elapsed-ms      12
 :snapshot        {:variant-id :story.counter/at-five :content-hash "..."}
 :decorators      {:hiccup [...] :frame-setup [...] :fx-override [...]}}
```

## Args + decorator resolution

### `resolve-args`

- **Signature**:
  ```clojure
  (resolve-args variant-id) → map
  (resolve-args variant-id opts) → map
  ```
- **Description**: Materialise the effective args map for a variant given the active modes + cell overrides. The five-layer precedence chain (global → story → mode → variant → cell-override), deep-merged for maps, vector-replaced for vectors.

### `resolve-decorators`

- **Signature**:
  ```clojure
  (resolve-decorators variant-id) → map
  (resolve-decorators variant-id opts) → map
  ```
- **Description**: Return the variant's resolved decorator stack classified by kind: `{:hiccup [...] :frame-setup [...] :fx-override [...] :errors [...]}`. Composition order: `(concat globals story variant)`.

### `variant-frames`

- **Signature**:
  ```clojure
  (variant-frames) → set
  ```
- **Description**: The set of variant-ids currently allocated as frames.

### `variant-frame?`

- **Signature**:
  ```clojure
  (variant-frame? variant-id) → bool
  ```
- **Description**: Predicate.

## Snapshot identity + share

### `snapshot-identity`

- **Signature**:
  ```clojure
  (snapshot-identity variant-id) → map
  (snapshot-identity variant-id opts) → map
  ```
- **Description**: The variant's snapshot identity — the variant id plus a content-hash over its setup (args, events, modes, substrate). Returns `{:variant-id ... :content-hash "..."}`. Used by visual-regression keying + the Story recorder to identify what the user is looking at without leaking the variant's args. The hash computes over real values (pre-substitution); downstream emission goes through `elide-wire-value`.

### `variant-share-url`

- **Signature**:
  ```clojure
  (variant-share-url variant-id) → string
  (variant-share-url variant-id base-url opts) → string
  ```
- **Description**: Build a sharable URL for `variant-id` against `base-url`. Encodes active modes + cell-overrides + substrate so a paste-and-open session reproduces the cell. The chrome's `url-state` pushState wiring keeps the browser's address bar in lockstep with this encoder so Cmd-L Cmd-C copies the same URL the builder produces. Pure data → data; JVM + CLJS portable.

## Assertion-side accessors

### `read-assertions`

- **Signature**:
  ```clojure
  (read-assertions variant-id) → assertions-vec
  ```
- **Description**: The current `:rf.story/assertions` vector for `variant-id`. Each entry is a `:rf.assert/*` record.

### `assertions-passing?`

- **Signature**:
  ```clojure
  (assertions-passing? result) → bool
  ```
- **Description**: Project over a `run-variant` result map (or a raw assertions vector) — true iff every record is `:passed? true`. The single primitive a `cljs.test`-style adapter calls.

### `canonical-assertion-ids`

- **Signature**:
  ```clojure
  (canonical-assertion-ids) → set
  ```
- **Description**: The seven canonical `:rf.assert/*` event-ids as a set, for tooling that enumerates the assertion vocabulary.

## Registry queries

The query family Story exposes for its own chrome, the MCP jar, and any tooling that walks the registrar's side-table.

### `registrations`

- **Signature**:
  ```clojure
  (registrations kind)
  ```
- **Description**: All registrations for `kind` (Story kinds: `:story`, `:variant`, `:workspace`, `:story-panel`, `:tag`, `:mode`, `:decorator`).

### `handler-meta`

- **Signature**:
  ```clojure
  (handler-meta kind id)
  ```
- **Description**: The registered body for `id`.

### `ids`

- **Signature**:
  ```clojure
  (ids kind)
  ```
- **Description**: All registered ids of `kind`.

### `registered?`

- **Signature**:
  ```clojure
  (registered? kind id) → bool
  ```
- **Description**: Predicate.

### `all-kinds-with-counts`

- **Signature**:
  ```clojure
  (all-kinds-with-counts)
  ```
- **Description**: Map from each registered kind to its count.

### `variants-of`

- **Signature**:
  ```clojure
  (variants-of story-id)
  ```
- **Description**: Variant ids whose namespaced id-prefix matches `story-id`.

### `variants-by-story`

- **Signature**:
  ```clojure
  (variants-by-story)
  ```
- **Description**: Map from parent-story-id to its variant ids.

### `variants-with-tags`

- **Signature**:
  ```clojure
  (variants-with-tags tag-set)
  ```
- **Description**: Variant ids whose `:tags` intersect the filter set.

### `list-tags`

- **Signature**:
  ```clojure
  (list-tags)
  ```
- **Description**: All registered tags (canonical + project).

### `list-modes`

- **Signature**:
  ```clojure
  (list-modes)
  ```
- **Description**: All registered modes.

### `canonical-tags`

- **Kind**: Var (set)
- **Description**: The seven canonical tags.

### `canonical-axes`

- **Kind**: Var
- **Description**: The four canonical axes (audience / lifecycle / quality / status).

### `canonical-status-values`

- **Kind**: Var
- **Description**: The status-axis tag values.

### `canonical-role-values`

- **Kind**: Var
- **Description**: The role-axis tag values.

### `tags-by-axis`

- **Signature**:
  ```clojure
  (tags-by-axis)
  ```
- **Description**: Map from axis → tag-ids registered against it.

### `tags-without-axis`

- **Signature**:
  ```clojure
  (tags-without-axis)
  ```
- **Description**: Tags not registered against any axis (project tags).

### `tags-default-excluded`

- **Signature**:
  ```clojure
  (tags-default-excluded)
  ```
- **Description**: Tags the sidebar tag-filter excludes by default.

### `tag->axis-index`

- **Signature**:
  ```clojure
  (tag->axis-index)
  ```
- **Description**: Map from tag-id → axis.

### `registered-substrates`

- **Signature**:
  ```clojure
  (registered-substrates)
  ```
- **Description**: CLJS-only. The substrate set as registered via `register-substrate!`.

### `variant-substrates`

- **Signature**:
  ```clojure
  (variant-substrates variant-id)
  ```
- **Description**: The substrate set for a specific variant.

## `configure!`

The boot-time entry point for project-wide defaults. The host calls it once before mounting the shell.

### `configure!`

- **Signature**:
  ```clojure
  (configure! opts) → nil
  ```
- **Description**: Set Story's global config. Every key lives under the `:rf.story/*` reserved sub-namespace. The known-keys set is **closed and small** — an unknown key (a typo like `:rf.story/edtior`) fails loudly at boot with `:rf.error/unknown-story-config-key` rather than silently no-opping.

The full v1 key surface:

```clojure
(story/configure!
  {;; Args — Layer 1 of the five-layer precedence chain
   :rf.story/global-args
   {:theme :light :locale :en}

   ;; Decorators — the project-wide prefix of every variant's stack
   :rf.story/global-decorators
   [[:app/theme-provider :dark]
    [:app/locale-provider :en]]

   ;; Editor — drives the source-coord 'Open in editor' chip
   :rf.story/editor :cursor    ;; / :vscode (default) / :idea / {:custom <tpl>}

   ;; On-disk root — prepended to classpath-relative source-coord :file slots
   :rf.story/project-root "C:/Users/me/code/my-app"

   ;; On-box dev-UI egress profile — the per-(tool, frame) privacy boundary
   :rf.story/egress-profile :rf.egress/local-redacted})
```

Two key behaviours are worth pinning:

- **`:rf.story/project-root` bridges into Xray**. When set, Story propagates the value into Xray's own `:rf.xray/project-root` slot via `re-frame.story.xray-preset/propagate-project-root!` so the Xray-as-RHS source-coord chips share the same on-disk root. The bridge is one-way; hosts that want Xray pointed at a different root call `xray-config/configure!` directly AFTER `story/configure!`.
- **`:rf.story/egress-profile` is Story's on-box visibility boundary**. On-box visibility is a **named boundary profile per (tool, frame)** — there is no process-global on/off privacy toggle (see [EP-0015](../../EP/EP-0015-frame-owned-egress-policy.md)). The value is one of the six closed `:rf.egress/*` profiles; in practice the two on-box members: `:rf.egress/local-redacted` (the default — suppress sensitive display, fail-closed) or `:rf.egress/local-raw` (the trusted-local opt-in — show path-marked-sensitive values verbatim on your own machine). Every value-bearing Story surface (the recorder, the per-variant trace-buffer listener, the play-assertion listeners) projects through the centralized `re-frame.core/project-egress` walker under this profile. Lifting to `:rf.egress/local-raw` is an operator act and is itself trace-visible (auditable); narrowing back to the redacting default retroactively scrubs the per-variant buffers. An unknown profile raises `:rf.error/unknown-egress-profile`; `nil` resets to the redacting default.

## Substrate registration (CLJS-only)

### `register-substrate!`

- **Signature**:
  ```clojure
  (register-substrate! substrate-id render-fn) → nil
  ```
- **Description**: Register a substrate render fn under `substrate-id`. The host calls this once at boot for each substrate it wants Story to render against (`:uix`, `:helix`, etc.). The `:reagent` substrate is registered automatically by the canonical-vocabulary auto-install.

### `registered-substrates` (substrate registration)

- **Signature**:
  ```clojure
  (registered-substrates) → set
  ```
- **Description**: The set of registered substrate ids. Used by tooling that enumerates available substrates for a variant's `:substrates` opt-in.

## Shell lifecycle (CLJS-only)

The three-pane Reagent component that constitutes Story's UI. The host calls `mount-shell!` from its entry namespace when a hash-routed `#/stories` triggers Story mode.

### `mount-shell!`

- **Signature**:
  ```clojure
  (mount-shell! mount-point opts) → nil
  ```
- **Description**: Mount the Story shell at `mount-point` (a DOM node). The opts map carries `:initial-variant`, `:initial-mode`, `:theme`, etc. Production builds (`re-frame.story.config/enabled?` false) short-circuit before any DOM call.

### `unmount-shell!`

- **Signature**:
  ```clojure
  (unmount-shell!) → nil
  ```
- **Description**: Unmount the shell. Idempotent.

### `active-shell`

- **Signature**:
  ```clojure
  (active-shell) → map / nil
  ```
- **Description**: Inspectable handle on the active shell — returns nil when no shell is mounted.

## Static-mode probe

### `static-mode?`

- **Signature**:
  ```clojure
  (static-mode?) → bool
  ```
- **Description**: True iff Story is running in static-export mode (the bundle was built with `:closure-defines {re-frame.story.config/static-mode? true}`). The shell itself flips its dev-time affordances (hot-reload poll, first-visit help overlay auto-open) off when the flag is true. Surfaced here for tooling / examples that want to render a "this is a published static site" badge.

## Stage marker

### `stage`

- **Kind**: Var
- **Description**: The current Story development stage marker. Surfaced for tools that gate behaviour on Story's advertised maturity tier.

## Effects and coeffects registered by Story

Phase 4's `:dispatch` rail emits these fx; the chrome's control widgets and toolbar consume them.

| Fx id | Payload | Notes |
|---|---|---|
| `:story/set-arg` | `{:variant <id> :key <k> :value <v>}` | Dispatched by control widgets when args change. Drives Layer 5 (cell-override) of the precedence chain. |
| `:story/run-play` | `{:variant <id>}` | Run the play sequence (the play-stepper "Run" affordance). |
| `:story/reset` | `{:variant <id>}` | Reset variant to post-events baseline. |
| `:story/save-layout-as` | `{:workspace <id> :body <transit>}` | Persist the active layout as a registered workspace. |

| Cofx id | Shape | Notes |
|---|---|---|
| `:story/active-modes` | `[<mode-id> ...]` | The chrome-toolbar's active mode-set. Cofx-injected into Layer 3 of the precedence chain. |
| `:story/active-args` | `{<arg-key> <value>}` | Deep-merge of all active modes' `:args`. |
| `:story/substrate` | `:reagent` / `:uix` / `:helix` | The active substrate. |

## See also

- [Registration](registration.md) — the registration macros that populate the registrar `run-variant` walks.
- [Scripts](script.md) — phase 4's full grammar; `read-assertions` / `assertions-passing?` consumers.
- [MCP surface](mcp-surface.md) — the same fns above, consumed by the `tools/story-mcp/` jar over JSON-RPC.
- [Story tutorial — Your first variant](../01-first-variant.md) — `mount-shell!` in context.
- [Story tutorial — Snapshot identity and sharing](../08-snapshot-identity-and-sharing.md) — `snapshot-identity` + `variant-share-url` in worked usage.
- [Framework API — Lifecycle](../../guide/api/13-lifecycle.md) — `rf/init!` runs before `mount-shell!`. The adapter must be installed before Story attaches.
- [Xray API — Configuration keys](../../xray/api/config-keys.md) — `:rf.xray/project-root`, the slot Story's `:rf.story/project-root` bridges into.
