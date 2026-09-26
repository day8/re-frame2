# Runtime

This chapter is about the surfaces that bring a registered variant to life — the lifecycle that allocates a per-variant frame, runs its loaders and setup events, renders against the post-setup `app-db`, and walks the script; the programmatic entry points (`run-variant`, `reset-variant`, `watch-variant`, `destroy-variant!`) that callers reach for from custom shells, test fixtures, and one-shot screenshot pipelines; the registry-query family that tools build against; the boot-time `configure!` surface that sets project-wide defaults; and the CLJS-only shell-mount surface that wires Story's three-pane chrome into the host's DOM.

The core of it is **one runtime, two consumer audiences**. *Story authors* run variants implicitly — the Story shell runs and resets them on the author's behalf as the user clicks through the sidebar and presses **Run all** or **Re-run**. *Host applications and test fixtures* call the same fns directly when they want a run outside the chrome, a `clojure.test` or `cljs.test` assertion, or a snapshot-identity hash for visual-regression keying.

## Per-variant frame allocation

Every variant runs in its own frame, registered under the variant's id and carrying the variant's decorator stack. The frame stays allocated after a run until `destroy-variant!` tears it down; any state machines the variant spawned receive their `:rf.machine/destroy` event as part of that teardown. Running a variant again first resets its frame in place, so every run starts from a fresh `app-db` while a mounted view keeps its subscriptions.

### Coexistence with host application state

Story keeps runtime slots in every variant frame's `app-db` under the reserved `:rf.story/*` namespace:

- `:rf.story/lifecycle` — a copy of the lifecycle machine's current state, for direct reading.
- `:rf.story/loaders-complete?` — the flag a `:loaders-complete-when` event handler sets to say the loaders are done.
- `:rf.story/assertions` — the vector of assertion records the `:rf.assert/*` handlers append during the run.

A host application's `reg-event` handlers — and any other code path that writes `app-db` — MUST preserve the `:rf.story/*` namespace when seeding or resetting `db`. The hazard is the "replace-the-whole-db" idiom: a handler returning `{:db {...}}` built from scratch wipes the reserved slots and corrupts every Story variant that runs the event. Build the `:db` return by threading the incoming `db` through — `{:db (assoc db ...)}` or `{:db (merge db {...})}` — don't throw it away.

## The four-phase lifecycle

For every run, in strict order, draining the frame's queue between steps. Before phase 1 the runtime resets the frame, applies the decorator stack's `:frame-setup` work and merges any `:db-seed` into `app-db`.

| Phase | Trigger | Semantics |
|---|---|---|
| **1. Loaders** | Variant body's `:loaders` | Dispatch each event into the variant's frame and drain. The phase is complete when `:loaders-complete-when` holds: by default as soon as the queue drains; otherwise a registered event id whose handler sets `:rf.story/loaders-complete?`, a function of `app-db`, or a vector of events that must all have been dispatched. |
| **2. Setup** | The plan's `:setup` | Dispatch the setup events in order — an `:extends` parent's first, then composed fragments', then the variant's own — draining between events. |
| **3. Render** | The shell or `render-variant` | The view renders against the post-setup `app-db` with the effective args (the five-layer precedence chain) and the decorator stack (globals, then story, then variant). `run` and `run-variant` do not render. |
| **4. Script** | The plan's scripts | Walk the steps in order. `:rf.assert/*` records accumulate in `:assertions`; failures don't throw. See [Scripts](script.md). |

A loader that throws, or a `:loaders-complete-when` that never holds, records a failing assertion and parks the lifecycle at `:loading`; a headless run then skips setup and the script, and the run does not pass.

## The execution verbs

All under `re-frame.story`. These are the verbs a test calls. The [tutorial's chapter 4](../04-the-variant-is-a-test.md) shows them in a test namespace.

A test namespace that runs variants needs a substrate adapter installed and `re-frame.epoch` loaded, because the runner reads each run's evidence from the epoch tape. `re-frame.test-support/make-reset-runtime-fixture` with `{:adapter re-frame.substrate.plain-atom/adapter}` installs the adapter; add `:async? true` for `cljs.test` async tests.

### `run`

- **Signature**:
  ```clojure
  (run target) → promise
  (run target opts) → promise
  ```
- **Description**: Run `target` and resolve with the unified run result. A keyword `target` is a registered variant; a map is an inline plan, a variant body that is compiled, run in a fresh anonymous frame and torn down, and never registered. On the JVM the promise is a `CompletableFuture`, so `@(run id)` blocks for the result; in CLJS it is a `js/Promise`. It never rejects: an unknown variant, a failed setup or a thrown handler resolves with `:status :error`.
- **Options**:

    | Key | Value |
    |---|---|
    | `:runner` | `:headless` (default), `:hiccup`, `:cljs-reactive`, `:dom` or `:browser` to fix the runner; `:auto` to take the cheapest one whose capabilities cover the plan. An unknown value falls back to `:headless`. |
    | `:escalate` | `true` means the same as `:runner :auto`. |
    | `:active-modes` | Mode ids whose args join the args chain, in order. |
    | `:cell-overrides` | Arg overrides, as the Controls panel makes them. |
    | `:substrate` | The substrate to render under. |

    `run` executes the plays that run on mount: a `:script` unless it sets `:auto-run? false`, and the first of `:plays` plus any other play that sets `:auto-run? true`.

### `is`

- **Signature**:
  ```clojure
  (is target) → result | promise
  (is target opts) → result | promise
  ```
- **Description**: Run `target` as `run` does and report the result to `clojure.test` or `cljs.test`: one report per assertion record, plus a failing report when the run is `:cannot-run`, `:error`, or `:fail` from the tape floor without a failing assertion to carry it. A run that passes with no assertions reports one pass. On the JVM `is` blocks and returns the result; `opts` also takes `:timeout-ms` (default `30000`), past which it throws a `TimeoutException` rather than hanging the test run. In CLJS it returns a promise that resolves with the result once it has reported, for use inside `cljs.test/async`. A `target` that is already a run result is reported as it is.

### `report-result!`

- **Signature**:
  ```clojure
  (report-result! result) → result
  ```
- **Description**: Report an already-resolved run result through `clojure.test` or `cljs.test`, exactly as `is` does.

### `explain`

- **Signature**:
  ```clojure
  (explain target) → map
  (explain target opts) → map
  ```
- **Description**: Compile `target` without running it and return how it was assembled. The map carries `:source`, `:source-chain` and `:parent-chain`; `:compose` and `:strict-conflicts`; `:merge`, the strategy applied to each field; `:args`, `:substitutions`, `:effective-args`, `:view-args-schema` and `:view-args-validation`; `:network`, `:sub-overrides`, `:db-seed` and `:fidelity`; `:setup-order`, `:script-order`, `:checks` and `:assertions`; `:required-runner`; `:platforms`; and `:tags`. `opts` takes `:active-modes` and `:cell-overrides`, so the args match a run under them. It throws when the target cannot compile, for example `:rf.error/story-unknown-variant` for an unregistered id or `:rf.error/story-compose-conflict` for a `:compose` conflict.

### `variant-plan`

- **Signature**:
  ```clojure
  (variant-plan target) → plan
  (variant-plan target opts) → plan
  ```
- **Description**: The compiled plan that `run` executes and the canvas renders: `:variant/id`, `:story/id`, `:world` (setup, scripts, args, decorators, fidelity and the other world inputs), `:expect` (checks and assertions), `:required-runner`, `:tags`, `:source`, `:source-chain` and `:explain`. `explain` returns this plan's `:explain` with the run-time args folded in.

### `render-variant`

- **Signature**:
  ```clojure
  (render-variant target) → map
  (render-variant target opts) → map
  ```
- **Description**: Render `target`'s view from its plan without running `:script` or assertions. `opts` takes `:control-overrides`, arg overrides applied on top of the plan's args. The result carries `:status` (`:rendered`, `:invalid-args`, `:cannot-run` or `:error`), `:plan`, `:plan-hash`, `:frame`, `:effective-args`, `:validation` and `:rendered`. An override that breaks the view's `:rf/props` schema stops before the view is called, with `:invalid-args`. On the JVM, with no host renderer, the status is `:cannot-run`.

## Programmatic runtime

All under `re-frame.story`. Reach for these from a custom shell, a test fixture, a `cljs.test` adapter, an MCP-tool body, or any host that wants to materialise a variant outside the standard Story chrome.

### `run-variant`

- **Signature**:
  ```clojure
  (run-variant variant-id) → promise
  (run-variant variant-id opts) → promise
  ```
- **Description**: Allocate the variant's frame, run the lifecycle, and resolve with the unified run result, the same shape `run` resolves with. On the JVM the promise is a `CompletableFuture`; in CLJS a `js/Promise`. The frame stays allocated afterwards, so `read-assertions` and `lifecycle-state` can inspect it. Rendering is `render-variant`'s — `run-variant` produces no rendered output.

### `reset-variant`

- **Signature**:
  ```clojure
  (reset-variant variant-id) → promise
  (reset-variant variant-id opts) → promise
  ```
- **Description**: Tear the variant's frame down and run it again from its declared start, resolving as `run-variant` does. The Story shell calls this when the user resets a variant.

### `watch-variant`

- **Signature**:
  ```clojure
  (watch-variant variant-id callback) → unsubscribe-fn
  ```
- **Description**: Call `callback` on every lifecycle transition of the variant's frame, with `{:frame-id <id> :from <state> :to <state> :event <event>}`. Returns a zero-argument function that unsubscribes.

### `destroy-variant!`

- **Signature**:
  ```clojure
  (destroy-variant! variant-id) → nil
  ```
- **Description**: Tear down the variant's frame. Any spawned state-machines receive their `:rf.machine/destroy` event. Idempotent.

### `lifecycle-state`

- **Signature**:
  ```clojure
  (lifecycle-state variant-id) → keyword
  ```
- **Description**: The current state of the variant's lifecycle machine — one of `:pre-mount` / `:mounting` / `:loading` / `:ready` / `:error`. Returns `:pre-mount` when the variant has not been run yet.

The `opts` map for `run-variant` and `reset-variant` accepts:

```clojure
{:active-modes    [:Mode.app/dark-large]   ;; coll of mode ids, deep-merged into args
 :cell-overrides  {:label "Override"}      ;; controls-panel-shaped runtime overrides
 :substrate       :reagent}                ;; / :uix
```

The result carries `:status`, `:variant/id`, `:frame`, `:lifecycle`, `:runner`, `:required-runner`, `:assertions`, `:checks`, `:schema-violations`, `:consumed-selectors`, `:warnings`, `:app-db`, `:effects`, `:effective-args`, `:decorators`, `:images`, `:sub-runs`, `:renders`, `:epoch-tape`, `:narrative`, `:snapshot`, `:plan-hash`, `:run-hash` and `:elapsed-ms`. The [tutorial's chapter 4](../04-the-variant-is-a-test.md) shows one.

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
- **Description**: Return the variant's resolved decorator stack classified by kind: `{:hiccup [...] :frame-setup [...] :fx-override [...] :errors [...] :fingerprints {...}}`. Each entry carries the reference's `:id`, `:args` and the registered `:body`; `:errors` lists references that did not resolve. Composition order: `(concat globals story variant)`.

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
- **Description**: The variant's snapshot identity — the variant id plus a content hash over the canonicalised variant, its resolved args, decorators, loaders, substrate and active modes. Returns `{:variant-id ... :active-modes [...] :substrate ... :content-hash "<8 hex digits>"}`. `opts` takes `:active-modes`, `:cell-overrides` and `:substrate`. Used by visual-regression keying ([chapter 8](../08-snapshot-identity-and-sharing.md#local-visual-review)) to identify what the user is looking at without leaking the variant's args. The hash computes over real values (pre-substitution); downstream emission goes through `project-egress`.

### `variant-share-url`

- **Signature**:
  ```clojure
  (variant-share-url variant-id) → string
  (variant-share-url variant-id opts) → string
  (variant-share-url variant-id base-url opts) → string
  ```
- **Description**: Build a sharable URL for `variant-id`. `opts` takes `:active-modes`, `:cell-overrides` and `:substrate`, so a paste-and-open session reproduces the cell. The one- and two-argument forms return the query string alone, as `variant=story.login-form%2Fidle`; the three-argument form prefixes `base-url`, as `http://localhost:8043/?variant=story.login-form%2Fidle`. The chrome's `url-state` pushState wiring keeps the browser's address bar in lockstep with this encoder so Cmd-L Cmd-C copies the same URL the builder produces. Pure data → data; JVM + CLJS portable.

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
  (assertions-passing? result-or-assertions) → bool
  ```
- **Description**: Given a run result, true iff the run's `:status` is `:pass`, so a `:cannot-run` run or a schema-floor `:fail` is false even when every assertion record passed. Given a bare assertions vector, such as `read-assertions` returns, true iff every record has `:passed? true`; an empty vector passes.

### `canonical-assertion-ids`

- **Signature**:
  ```clojure
  (canonical-assertion-ids) → set
  ```
- **Description**: The eight canonical `:rf.assert/*` ids as a set: the seven dispatched assertion events plus `:rf.assert/schema-error`. `known-assertion-ids` adds the DOM, a11y, visual and reactive-count ids the plan compiler also accepts.

## Registry queries

The query family Story exposes for its own chrome, the MCP jar, and any tooling that walks the registrar's side-table.

### `registrations`

- **Signature**:
  ```clojure
  (registrations kind)
  ```
- **Description**: All registrations for `kind`, as a map from id to body (Story kinds: `:story`, `:variant`, `:workspace`, `:fragment`, `:check`, `:story-panel`, `:tag`, `:mode`, `:decorator`).

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
- **Description**: The set of variant ids whose effective tags — inherited from a story or `:extends` parent, less any `:!tag` removals — intersect `tag-set`.

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

- **Kind**: Var (map)
- **Description**: The five canonical tag axes, each mapped to `{:user-extensible? bool}` and, for the three with a recommended vocabulary, `:values`: `:status` (`#{:alpha :beta :stable :deprecated}`), `:role` (`#{:design :dev :product}`), `:state` (`#{:empty :small :medium :large :special}`), and the open `:team` and `:feature`.

### `canonical-status-values`

- **Kind**: Var (set)
- **Description**: The recommended `:status` values, `#{:alpha :beta :stable :deprecated}`.

### `canonical-role-values`

- **Kind**: Var (set)
- **Description**: The recommended `:role` values, `#{:design :dev :product}`.

### `tags-by-axis`

- **Signature**:
  ```clojure
  (tags-by-axis axis) → set
  ```
- **Description**: The registered tag ids whose `:axis` is `axis`, such as `:status`; the empty set when none is.

### `tags-without-axis`

- **Signature**:
  ```clojure
  (tags-without-axis) → set
  ```
- **Description**: The registered tag ids that declare no `:axis`. The sidebar's tag filter shows them in its OTHER row.

### `tags-default-excluded`

- **Signature**:
  ```clojure
  (tags-default-excluded) → set
  ```
- **Description**: The registered tag ids whose body sets `:default-filter :exclude`.

### `tag->axis-index`

- **Signature**:
  ```clojure
  (tag->axis-index) → map
  ```
- **Description**: Map from every registered tag id to its axis; a tag with no axis maps to `:re-frame.story.registrar/no-axis`.

`registered-substrates`, the substrate set, sits with `register-substrate!` under [Substrate registration](#substrate-registration-cljs-only).

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
(rf.story/configure!
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
   :rf.story/project-root "/path/to/my-app"

   ;; On-box dev-UI egress profile — the per-(tool, frame) privacy boundary
   :rf.story/egress-profile :rf.egress/local-redacted})
```

Two key behaviours are worth pinning:

- **`:rf.story/project-root` bridges into Xray**. When set, Story propagates the value into Xray's own `:rf.xray/project-root` slot via `re-frame.story.xray-preset/propagate-project-root!` so the Xray-as-RHS source-coord chips share the same on-disk root. The bridge is one-way; hosts that want Xray pointed at a different root call `xray-config/configure!` directly AFTER `rf.story/configure!`.
- **`:rf.story/egress-profile` is Story's on-box visibility boundary**. On-box visibility is a **named boundary profile per (tool, frame)** — there is no process-global on/off privacy toggle (see [EP-0015](../../EP/EP-0015-frame-owned-egress-policy.md)). The value is one of the six closed `:rf.egress/*` profiles; in practice the two on-box members: `:rf.egress/local-redacted` (the default — suppress sensitive display, fail-closed) or `:rf.egress/local-raw` (the trusted-local opt-in — show path-marked-sensitive values verbatim on your own machine). Every value-bearing Story surface (the recorder, the per-variant trace-buffer listener, the play-assertion listeners) projects through the centralized `re-frame.core/project-egress` walker under this profile, and shows a `[● REDACTED]` hint where it redacts. An unknown profile raises `:rf.error/unknown-egress-profile`; `nil` resets to the redacting default.

## Substrate registration (CLJS-only)

### `register-substrate!`

- **Signature**:
  ```clojure
  (register-substrate! substrate-id render-fn) → nil
  ```
- **Description**: Register a substrate render fn under `substrate-id`. `render-fn` takes `(variant-id view-id args)` and returns a hiccup vector (Reagent) or a React element (UIx). The host calls this once at boot for each substrate it wants Story to render against (`:uix`, etc.). The `:reagent` substrate is registered automatically by the canonical-vocabulary auto-install.

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
  (mount-shell! dom-node) → handle / nil
  ```
- **Description**: Mount the Story shell at `dom-node` and return its handle, `{:root <react-root> :node <dom-node>}`. One shell at a time: mounting while a shell is mounted tears the previous one down first. There is no options map — what the shell opens on (selected variant or workspace, mode tab, modes, viewport, background) is read from the page URL's query parameters at mount, over a localStorage fallback. Returns nil without any DOM call when `dom-node` is nil or in production builds (`re-frame.story.config/enabled?` false).

### `unmount-shell!`

- **Signature**:
  ```clojure
  (unmount-shell!) → nil
  (unmount-shell! handle) → nil
  ```
- **Description**: Unmount the active shell, or the shell named by `handle` (the value `mount-shell!` returned). Idempotent.

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

## Coeffects registered by Story

An event handler can read the toolbar's state through two coeffects, declared under `:rf.cofx/requires`. The same two ids are registered as subscriptions, for a view.

| Cofx id | Shape | Notes |
|---|---|---|
| `:story/active-modes` | `[<mode-id> ...]` | The toolbar's active modes. |
| `:story/active-args` | `{<arg-key> <value>}` | The deep-merge of the active modes' `:args`, the mode layer of the precedence chain. |

## See also

- [Registration](registration.md) — the registration macros that populate the registrar `run-variant` walks.
- [Scripts](script.md) — phase 4's full grammar; `read-assertions` / `assertions-passing?` consumers.
- [MCP surface](mcp-surface.md) — the same fns above, consumed by the `tools/story-mcp/` jar over JSON-RPC.
- [Story tutorial — Your first variant](../01-first-variant.md) — `mount-shell!` in context.
- [Story tutorial — Snapshot identity and sharing](../08-snapshot-identity-and-sharing.md) — `snapshot-identity` + `variant-share-url` in worked usage.
- [Framework API — Lifecycle](../../api/re-frame.core.md) — `rf/init!` runs before `mount-shell!`. The adapter must be installed before Story attaches.
- [Xray API — Configuration keys](../../xray/api/config-keys.md) — `:rf.xray/project-root`, the slot Story's `:rf.story/project-root` bridges into.
