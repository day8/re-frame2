# Story — Public API

> The consolidated public API surface for `day8/re-frame2-story` —
> every `reg-*`, every fn, every fx-id, every cofx-id. Each entry
> cross-links to the capability doc where the contract is spelled
> out in full.

## Facade re-export discipline

`re-frame.story` is the **user-callable facade** for Story. The
re-export rule is:

- **User-callable surfaces re-export.** The registration macros + their
  `*`-fn partners, the run/reset/watch/destroy lifecycle, the registry
  query family, the assertion + recorder facades, the canonical
  vocabulary tables, `configure!`, the `*-id` Vars for built-in
  decorators, the shell-mount surface (CLJS-only), `variant-share-url`,
  and `add-marks` / `set-marks` (privacy primitives re-export per
  [Conventions §Privacy primitives — `add-marks` / `set-marks` re-export](Conventions.md#privacy-primitives--add-marks--set-marks-re-export))
  all sit on the facade.
- **Chrome internals + theme tokens require sub-ns access.** Theme
  tokens (`re-frame.story.theme.*`), the chrome-host surface
  (`re-frame.story.ui.xray-embed/*`, `re-frame.story.xray-preset/*`,
  `re-frame.story.ui.keybindings/*`), the URL-state engine
  (`re-frame.story.ui.url-state/*`), and the schema-validation panel
  installer all require a direct `:require` of the sub-namespace. They
  are public but called from the chrome itself, the shell bootstrap, or
  the Xray preset — not from user story bodies.

The split mirrors `re-frame.core`'s practice: the facade carries the
ergonomic surface; sub-namespace requires are the discoverability
signal that a surface is chrome-internal even when public. The rule is
de-facto in the code today; this paragraph names it so authors writing
to `re-frame.story` know which side of the line a given surface lives
on (rf2-u3e4q follow-on, Finding #6 of the rf2-u6o12 audit at
`ai/findings/2026-05-20-tools-story-api-review.md` (local-only)).

## Registration macros

All under `re-frame.story`. All DCE under `:advanced` (see
[`005-SOTA-Features.md`](005-SOTA-Features.md) §Production elision).

| Macro | Signature | Spec |
|---|---|---|
| `reg-story` | `(reg-story id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-story |
| `reg-variant` | `(reg-variant id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-variant |
| `reg-workspace` | `(reg-workspace id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-workspace |
| `reg-decorator` | `(reg-decorator id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-decorator |
| `reg-story-panel` | `(reg-story-panel id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-story-panel + [`003-Render-Shell.md`](003-Render-Shell.md) §Panel registration contract |
| `reg-tag` | `(reg-tag id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-tag |
| `reg-mode` | `(reg-mode id metadata)` | [`001-Authoring.md`](001-Authoring.md) §reg-mode |

### Combined `reg-story` Form B

`(reg-story id {:variants {...} ...})` desugars at macro-expansion
time to N independent `reg-variant` calls plus the bare `reg-story`.
See [`001-Authoring.md`](001-Authoring.md) §Combined form (Form B)
desugaring.

## Programmatic runtime

All under `re-frame.story`.

| Fn | Signature | Spec |
|---|---|---|
| `run-variant` | `(run-variant variant-id)` / `(run-variant variant-id opts)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `reset-variant` | `(reset-variant variant-id)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `watch-variant` | `(watch-variant variant-id callback)` — returns a 0-arity unsubscribe fn (call it to stop watching; there is no separate `unwatch-variant`). | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `destroy-variant!` | `(destroy-variant! variant-id)` | [`002-Runtime.md`](002-Runtime.md) §Per-variant frame allocation |
| `variants-with-tags` | `(variants-with-tags tags)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `variants-of` | `(variants-of story-id)` | [`006-MCP-Surface.md`](006-MCP-Surface.md) §Story's public read primitives |
| `variant->edn` | `(variant->edn variant-id)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `workspace->edn` | `(workspace->edn workspace-id)` | [`002-Runtime.md`](002-Runtime.md) §Programmatic API |
| `snapshot-identity` | `(snapshot-identity variant-id)` / `(snapshot-identity variant-id opts)` | [`002-Runtime.md`](002-Runtime.md) §Snapshot-identity computation |
| `read-assertions` | `(read-assertions variant-id)` | [`004-Assertions.md`](004-Assertions.md) |
| `assertions-passing?` | `(assertions-passing? result)` | [`004-Assertions.md`](004-Assertions.md) |
| `variant-share-url` | `(variant-share-url variant-id base-url opts)` | [`005-SOTA-Features.md`](005-SOTA-Features.md) §Share URL (retired QR popover) |

### Execution verbs — `run` / `is` / `render-variant`

The three public execution verbs (spec/017 §Public execution API). Each
accepts a keyword (a registered variant) OR a map (an inline plan).

| Fn | Signature | Spec |
|---|---|---|
| `run` | `(run target)` / `(run target opts)` | [`017-Testing-Story.md`](017-Testing-Story.md) §Public execution API. Returns a promise/future of the unified run-result. |
| `is` | `(is target)` / `(is target opts)` | [`017-Testing-Story.md`](017-Testing-Story.md) §Public execution API. Runs `target` + reports each assertion to `clojure.test` / `cljs.test`. |
| `render-variant` | `(render-variant target)` / `(render-variant target opts)` | [`017-Testing-Story.md`](017-Testing-Story.md) §Args, controls, and `render-variant`. |
| `result-status` / `result-passed?` | `(result-status result)` / `(result-passed? result)` | [`017-Testing-Story.md`](017-Testing-Story.md) §Run result. The unified verdict (`:pass` / `:fail` / `:cannot-run` / `:error`). There is NO `:passing?` boolean. |

#### `story/is` — JVM blocks, CLJS hands back the promise (rf2-zaklu)

`story/is` is **asymmetric across hosts by design**, and the asymmetry
is load-bearing:

- **JVM** (the canonical headless test gate) — `is` BLOCKS on the run
  promise, fires one `clojure.test` report per assertion synchronously,
  and returns the unified run-result. The block is bounded by
  `:timeout-ms` (default `30000`); a run that does not resolve inside
  the window throws a `java.util.concurrent.TimeoutException` rather
  than hanging the test JVM. Pass a custom bound for a slow integration
  run, or a tight one for a fast unit gate:

  ```clojure
  (story/is :story.slow/integration {:timeout-ms 120000})
  ```

- **CLJS** — the run is async and the caller cannot block, so `is`
  returns the run **promise**. Chain `then` (or use `cljs.test`'s
  `(async done …)` form) and call `(story/report-result! result)` when
  it resolves. `:timeout-ms` is **inert on CLJS** — there is no blocking
  deref to bound; the caller's own async deadline governs.

`opts` other than `:timeout-ms` thread through to `story/run` (the
runner / plan-compiler seams); `:timeout-ms` is stripped before the
run so the runner never sees a key it does not own.

## Registry queries

| Fn | Signature | Returns |
|---|---|---|
| `registrations` | `(registrations kind)` | All registrations for `kind` (Story kinds: `:story`, `:variant`, `:workspace`, `:story-panel`, `:tag`, `:mode`, `:decorator`). |
| `handler-meta` | `(handler-meta kind id)` | The registered body for `id`. |
| `ids` | `(ids kind)` | All registered ids of `kind`. |
| `registered?` | `(registered? kind id)` | Boolean. |
| `list-tags` | `(list-tags)` | All registered tags (canonical + project). |
| `list-modes` | `(list-modes)` | All registered modes. |
| `canonical-tags` | `canonical-tags` | The seven canonical tags as a set. |
| `canonical-assertion-ids` | `(canonical-assertion-ids)` | The seven `:rf.assert/*` event ids. |
| `registered-substrates` | `(registered-substrates)` | CLJS-only. The substrate set as registered via `register-substrate!`. |

## Write helpers (used by MCP write surface and hot-reload tooling)

All under `re-frame.story`. The `*`-suffix runtime helpers are
public; their unsuffixed macro counterparts cover authored cases.

| Fn | Signature | Purpose |
|---|---|---|
| `reg-story*` | `(reg-story* id body)` | Programmatic story registration. |
| `reg-variant*` | `(reg-variant* id body)` | Programmatic variant registration. |
| `reg-workspace*` | `(reg-workspace* id body)` | Programmatic workspace registration. |
| `reg-mode*` | `(reg-mode* id body)` | Programmatic mode registration. |
| `reg-story-panel*` | `(reg-story-panel* id body)` | Programmatic panel registration. |
| `reg-decorator*` | `(reg-decorator* id body)` | Programmatic decorator registration. |
| `reg-tag*` | `(reg-tag* id body)` | Programmatic tag registration. |
| `unregister!` | `(unregister! kind id)` | Remove a registration. |
| `clear-kind!` | `(clear-kind! kind)` | Remove all of a kind. |
| `clear-all!` | `(clear-all!)` | Reset Story state entirely. |

## Test-fixture helper (`re-frame.story.test-support`) — rf2-lh99f

The canonical per-test reset. **Require it DIRECTLY** —
`(:require [re-frame.story.test-support :as story-test])` — it is NOT
on the `re-frame.story` facade because it pulls `cljs.test` /
`clojure.test` (which the production facade must not), mirroring
`re-frame.test-support` itself (not re-exported on `re-frame.core` for
the same reason).

| Fn | Signature | Purpose |
|---|---|---|
| `use-fixtures` | `(use-fixtures {:adapter … :install …})` | Build a `clojure.test` / `cljs.test` `:each` fixture FUNCTION that does the canonical per-test reset. Use the fn form — never the map form (which silently skips every `deftest` on the JVM half of a `.cljc` test). |
| `with-clean-registry` | `(with-clean-registry {:adapter … :install …} thunk)` | Programmatic bracket form — run `thunk` inside a full reset, return its value. |

Both take `:adapter` (REQUIRED — the substrate adapter to install; Story
src cannot pick one because the JVM and browser want different adapters)
and `:install` (optional — a 0-arity fn or coll of them, called after the
canonical vocabulary is re-installed). The helper resets the framework
runtime via registrar **snapshot/restore**, so framework registrations
(including the `:rf/machine` sub) survive — closing the silent
`:pre-mount` footgun a hand-rolled `registrar/clear-all!` reset opens.
Stop hand-rolling `reset-all!`; use this.

## Global decorators (`reg-global-decorator`) — rf2-835ey

Story ships story-level + variant-level + **global** decorators. The
global tier is the parity surface for Storybook's `preview.ts`
`decorators: [...]` — "wrap every variant in the design system's theme
provider" lives here, set once at boot. The resolved per-variant stack
is `(concat globals story variant)` with globals as the outermost wrap.
All under `re-frame.story`.

| Fn | Signature | Purpose |
|---|---|---|
| `reg-global-decorator` | `(reg-global-decorator id body)` / `(reg-global-decorator id body ref-args)` | Register a decorator body (delegates to `reg-decorator*`) AND append a `[id & ref-args]` reference to the global stack. Re-registering the same id REPLACES the entry in place so a hot-reload of the body doesn't reshuffle order. Returns the decorator id. |
| `unreg-global-decorator!` | `(unreg-global-decorator! id)` | Remove `id` from the global-decorators vector (the registered decorator body is left intact — call `unregister!` for that). Idempotent. |
| `global-decorators` | `(global-decorators)` | Return the current ordered ref vector (`[[decorator-id & args] ...]`), earliest-registered first. |

`configure!`'s `:rf.story/global-decorators` key (above) is the
declarative bulk-set form for the same vector; `reg-global-decorator`
is the register-and-opt-in-in-one-call form. `clear-all!` clears the
global vector so stale ref-by-id entries don't bleed across tests.

## Variant `:script` slot (rf2-0wrud)

`:script` is the public phase-4 play surface (spec/017
§Public vocabulary). `:play-script` is the transitional spelling the
registrar still lowers from — author against `:script`. The legacy
`:play` event-vector slot has been removed — pre-alpha posture, no
transitional dual-acceptance. See [`001-Authoring.md`](001-Authoring.md)
§`:script` for the full authoring contract.

| Step                                 | Semantics                                                  |
|--------------------------------------|------------------------------------------------------------|
| `[:dispatch event-vec]`              | `rf/dispatch` (async) into the variant's frame             |
| `[:dispatch-sync event-vec]`         | `rf/dispatch-sync` (synchronous) into the variant's frame  |
| `[:wait ms]`                         | Sleep N ms                                                 |
| `[:assert assertion-atom]`           | Evaluate a canonical `[:rf.assert/…]` atom at this point — the primary assertion form (see [Canonical assertion events](#canonical-assertion-events--the-one-assertion-vocabulary)) |
| `[:assert-db path value]`            | **Sugar** → folds to `[:assert [:rf.assert/path-equals path value]]` |
| `[:assert-db path :pred fn-or-sym]`  | **Sugar** → folds to `[:assert [:rf.assert/path-matches path [:fn …]]]` |
| `[:assert-dom selector :visible]`    | **Sugar** → folds to `[:assert [:rf.assert/dom-visible selector]]`  |
| `[:assert-dom selector :hidden]`     | **Sugar** → folds to `[:assert [:rf.assert/dom-hidden selector]]`   |
| `[:assert-dom selector :text txt]`   | **Sugar** → folds to `[:assert [:rf.assert/dom-text selector txt]]` |
| `[:click selector]`                  | Synthetic click event at selector                          |
| `[:type selector text]`              | Synthetic input event at selector with `text`              |

The `:assert-db` / `:assert-dom` steps are ergonomic **sugar** the plan
compiler folds onto the canonical `:rf.assert/*` atom before the run
loop executes — they are NOT a second assertion vocabulary. See
[Canonical assertion events — the ONE assertion vocabulary](#canonical-assertion-events--the-one-assertion-vocabulary)
for the fold table and author guidance.

Body forms (shown against the public `:script` key; the registrar
lowers the transitional `:play-script` spelling to the same shape):

- Bare vector — `:script [[:dispatch-sync [:foo]] ...]`
- Map         — `:script {:script [...] :auto-run? bool :name str}`

The canonical seven `:rf.assert/*` events (per
[`004-Assertions.md`](004-Assertions.md)) ride the `:dispatch-sync`
rail: `[:dispatch-sync [:rf.assert/path-equals [:n] 3]]`. The
assertion handler runs synchronously and records into
`:rf.story/assertions` on the variant's frame.

The pure runner lives at
[`re-frame.story.play.runner`](../src/re_frame/story/play/runner.cljc)
(parser + state machine, JVM-testable); the impure driver at
[`re-frame.story.play.runner-events`](../src/re_frame/story/play/runner_events.cljc)
(dispatch + DOM + scheduler).

## Recorder facade

The recorder captures canvas-dispatched events into a play body for
codegen back into a `reg-variant` snippet. The emitted EDN uses the
transitional `:play-script` spelling the registrar lowers; `:script` is
the public phase-4 key authors target (spec/017 §Public vocabulary).
The facade exposes six entries on `re-frame.story` (per spec/005
§Recorder + [001-Authoring.md](001-Authoring.md) §Recorder); the richer
recorder-driven `:play-script` translator (rf2-d5u89 — derives `:click`
/ `:type` / `:wait` steps from the recorder's `:entries` stream) lives
under the recorder's sub-namespace.

| Fn | Signature | Purpose |
|---|---|---|
| `start-recording!` | `(start-recording! variant-id)` | Begin recording dispatched events against `variant-id`'s frame. |
| `stop-recording!` | `(stop-recording!)` | Stop the in-flight recording; return the captured events. |
| `clear-recording!` | `(clear-recording!)` | Drop the buffer + return the recorder to idle. |
| `recording?` | `(recording?)` | Predicate — is a recording in flight? |
| `recorder-state` | `(recorder-state)` | Read-only view of the current recorder state map. |
| `gen-play-snippet` | `(gen-play-snippet events opts)` | Pure codegen: render a captured `events` vector as a `(reg-variant <id> {... :play-script {:script [...]}})` EDN snippet. Each captured event vector is wrapped as `[:dispatch-sync <event-vec>]` (rf2-0wrud). See [005-SOTA-Features.md](005-SOTA-Features.md) §Recorder for the round-trip contract. |

### Rich-DSL `:play-script` export — out of facade

The richer DOM-capture-aware translator (tagged `:click` / `:type` /
`:wait` steps derived from the recorder's `:entries` capture stream —
rf2-d5u89) is exported by **`re-frame.story.recorder.play-export`**
(sub-namespace; not re-exported through `re-frame.story`). The entry
fns are `recording->play-script` (translate captured `:entries` into a
normalised `:play-script` body map) and `render-play-script` /
`render-variant-form` (render the map to EDN). The facade exposes only
`gen-play-snippet` (the simpler event-vector → `:dispatch-sync` step
projection) as the canonical entry; consumers wanting the rich
DOM-derived DSL `:require` the sub-namespace directly.

## Effects (fx) registered by Story

| Fx id | Payload | Notes |
|---|---|---|
| `:story/set-arg` | `{:variant <id> :key <k> :value <v>}` | Dispatched by control widgets when args change. |
| `:story/run-play` | `{:variant <id>}` | Run the play sequence (used by play-stepper). |
| `:story/reset` | `{:variant <id>}` | Reset variant to post-events baseline. |
| `:story/save-layout-as` | `{:workspace <id> :body <transit>}` | Persist the active layout as a registered workspace. |

## Coeffects (cofx) registered by Story

| Cofx id | Shape | Notes |
|---|---|---|
| `:story/active-modes` | `[<mode-id> ...]` | The chrome-toolbar's active mode-set (rf2-p0mv). See [`010-Toolbar.md`](010-Toolbar.md). |
| `:story/active-args` | `{<arg-key> <value>}` | Deep-merge of all active modes' `:args`. See [`010-Toolbar.md`](010-Toolbar.md). |

## Canonical assertion events — the ONE assertion vocabulary

**The `:rf.assert/*` events are the canonical, primary assertion
vocabulary** (spec/004 §Canonical assertion vocabulary). Every
assertion in Story — whether authored in a variant's `:assertions`
slot, dispatched as an `[:assert [:rf.assert/…]]` script checkpoint, or
written with an `:assert-db` / `:assert-dom` script step — resolves to
ONE `:rf.assert/*` atom and produces ONE assertion-record shape. There
is no second assertion vocabulary; `:assert-db` / `:assert-dom` are
**script-step sugar**, not a parallel surface (see below).

The seven `:rf.assert/*` events register at Story load. All record
into `:assertions` rather than throwing — see
[`004-Assertions.md`](004-Assertions.md) §Record-don't-throw.

| Event id | Payload | Semantics |
|---|---|---|
| `:rf.assert/path-equals` | `[path expected]` | `(= (get-in @app-db path) expected)` |
| `:rf.assert/path-matches` | `[path malli-schema]` | `(m/validate schema (get-in @app-db path))` |
| `:rf.assert/sub-equals` | `[sub-vec expected]` | `(= @(subscribe sub-vec) expected)` |
| `:rf.assert/dispatched?` | `[event-vec]` | Was this event dispatched against this frame? |
| `:rf.assert/state-is` | `[machine-id state]` | Active state of `reg-machine` machine-id is state. |
| `:rf.assert/no-warnings` | `[]` | No `:rf.warn/*` events seen during play. |
| `:rf.assert/effect-emitted` | `[fx-id]` or `[fx-id pred]` | Did the variant's drain emit fx-id? `pred`, when present, is a unary fn `(pred fx-id) → truthy?` — see [`004-Assertions.md`](004-Assertions.md) §`:rf.assert/effect-emitted` payload shape. |

### `:assert-db` / `:assert-dom` are sugar over `:rf.assert/*`

The `:assert-db` / `:assert-dom` script steps in the [`:script` slot
table](#variant-script-slot-rf2-0wrud) above are **ergonomic sugar**
the plan compiler folds onto the canonical atoms BEFORE the run loop
executes — they are not a second vocabulary:

| Script-step sugar                    | Folds to canonical atom                       |
|--------------------------------------|-----------------------------------------------|
| `[:assert-db path expected]`         | `[:rf.assert/path-equals path expected]`      |
| `[:assert-db path :pred fn-or-sym]`  | `[:rf.assert/path-matches path [:fn …]]`      |
| `[:assert-dom sel :visible]`         | `[:rf.assert/dom-visible sel]`                |
| `[:assert-dom sel :hidden]`          | `[:rf.assert/dom-hidden sel]`                 |
| `[:assert-dom sel :text txt]`        | `[:rf.assert/dom-text sel txt]`               |

The fold lives in `re-frame.story.assertions/fold-script` (pure data →
data); the runtime consumes the folded plan, so `exec-step!` only ever
sees the canonical `[:assert <atom>]` checkpoint. **Author guidance:**
reach for `:assert-db` / `:assert-dom` for the common app-db-equality
and DOM-presence checks (terser inline in a `:script`); drop to the
explicit `[:assert [:rf.assert/…]]` checkpoint when you need an
assertion the sugar doesn't cover (`:sub-equals`, `:state-is`,
`:no-warnings`, `:effect-emitted`, `:dispatched?`). Both positions
produce the same record. See [`004-Assertions.md`](004-Assertions.md)
§Assertions — one atom, two positions.

## Shell lifecycle

| Fn | Signature | Spec |
|---|---|---|
| `mount-shell!` | `(mount-shell! dom-node)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |
| `unmount-shell!` | `(unmount-shell!)` / `(unmount-shell! handle)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |
| `active-shell` | `(active-shell)` | [`003-Render-Shell.md`](003-Render-Shell.md) §Shell lifecycle |

## Theme tokens (`re-frame.story.theme.*`)

The chrome's design-token namespaces. The token maps are the public
contract third-party Story-panel authors honour — call sites consume
tokens, never raw hex / font-family / duration / shadow literals.
See [`016-Design-Tokens.md`](016-Design-Tokens.md) for the full
contracts.

| Namespace | Public surface | Purpose |
|---|---|---|
| `re-frame.story.theme.typography` | `sans-stack`, `mono-stack`, `display-stack`, `type-scale`, `weights`, `inject-font-faces!` | IBM Plex Sans + IBM Plex Mono stacks plus the type-scale / weights maps; `inject-font-faces!` injects `local()`-only `@font-face` rules at shell mount. See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Typography. |
| `re-frame.story.theme.colors` | `tokens` | Semantic colour map (`:bg-1` / `:bg-2` / `:bg-3` / `:bg-canvas` / `:bg-overlay` / `:text-primary` / `:text-secondary` / `:text-tertiary` / `:accent-amber` / `:accent-amber-soft` / `:accent-amber-deep` / `:border-subtle` / `:danger` / `:danger-bg` / `:tag-*-bg` / `:tag-*-fg` / …). See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Colour. |
| `re-frame.story.theme.motion` | `durations`, `easings`, `transitions` | Duration / easing maps plus pre-composed `transitions` for chrome surfaces; consumes `--motion-scale` CSS variable for global motion sensitivity. See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Motion. |
| `re-frame.story.theme.depth` | `shadows` | Elevation shadow scale (`:elev-1` / `:elev-2` / `:elev-3` / …). See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Depth. |
| `re-frame.story.theme.glyphs` | `story-glyph`, `variant-glyph`, `workspace-glyph`, `chevron-right`, `external-link` | Inline-SVG glyph fns for the three sidebar row types plus utility glyphs. Each fn accepts an optional pixel size; SVG draws via `currentColor` so CSS controls colour. See [`016-Design-Tokens.md`](016-Design-Tokens.md) §Iconography. |

### Token contract

- **No raw `font-family` at call sites.** Chrome consumes
  `sans-stack` / `mono-stack` / `display-stack` from
  `theme.typography`; raw `font-family` literals are banned (rf2-2rwdc
  AC#5).
- **No raw hex literals at call sites.** Chrome consumes
  `(:token-name colors/tokens)`; raw `#xxxxxx` literals are banned
  (rf2-i3i5j AC#3).
- **No raw `transition` literals at call sites.** Chrome consumes
  `(:row motion/transitions)` etc.; raw `transition` strings are
  banned (rf2-3lt89 follow-on sweep).
- **`prefers-reduced-motion: reduce` is honoured.** Chrome motion
  falls back to static states behind the user-agent media query.

## Chrome-host surface

The Story chrome's per-panel mount lifecycle plus the bridges-only
wiring helper. The contract Story consumes from Xray is
`panels/mount-<panel>!` (each panel a separate fn on
`day8.re-frame2-xray.panels`); Story owns the panel-host that drives
the lifecycle.

The **Audience** column names who's expected to call each surface
(rf2-8ns6j follow-on, Finding #3 of the rf2-u6o12 audit at
`ai/findings/2026-05-20-tools-story-api-review.md` (local-only)):

- `user-app` — the host application; safe to call from app code.
- `chrome-shell` — Story's own shell; called by the embed component,
  the Xray preset, or the shell's bootstrap. Not part of the user
  surface but unavoidably public because the shell needs it.
- `pure-data-for-help` — pure-data tables consumed by the help-overlay
  / cheat-sheet renderer. Public so consumers can walk the same data.

| Surface | Where it lives | Kind | Audience | Purpose |
|---|---|---|---|---|
| `xray-embed-panel` | `re-frame.story.ui.xray-embed` | Reagent component | `user-app` (rare) / `chrome-shell` | The RHS Xray-host Reagent component. Renders the chip-row picker plus the Xray panel-host `<div>` that one of `panels/mount-<panel>!` mounts into. Feature-detect-safe: renders a graceful no-op when Xray's preload is not on the classpath. See [`003-Render-Shell.md`](003-Render-Shell.md) §Xray per-panel embed. |
| `mount-fn-for` | `re-frame.story.ui.xray-embed` | Pure dispatch fn | `chrome-shell` | Pure dispatch: `(mount-fn-for panel-id)` returns the Xray `mount-<panel>!` fn for `panel-id` (one of `:epoch` / `:app-db` / `:views` / `:trace` / `:machines` / `:routing`; rf2-5gl5r retired `:event-detail` in favour of `:epoch`; rf2-gbz39 dropped `:issues` with the Xray Issues tab), or nil for an unknown id. Compile-time symbol resolution via a `case` dispatch — no runtime namespace walk. See [`003-Render-Shell.md`](003-Render-Shell.md) §The contract — `panels/mount-<panel>!`. |
| `popout-full-shell!` | `re-frame.story.ui.xray-embed` | User-callable lifecycle | `user-app` | Pop out the full Xray 4-layer shell into a second window via `day8.re-frame2-xray.mount/popout!`. Gated on `xray-preset/xray-available?` so the chip remains a graceful no-op when Xray's preload is not on the build. |
| `xray-preset/wire-cross-host!` | `re-frame.story.xray-preset` | Internal bridge | `chrome-shell` | Bridges-only host-wiring helper. Called by the shell on every variant selection; threads through Xray's host-installation hooks (project-root propagation, keybinding installation) but does NOT mount Xray — the embed's panel-host owns the per-panel mount. See [`003-Render-Shell.md`](003-Render-Shell.md) §`wire-cross-host!` — bridges-only, no mount. |
| `xray-preset/xray-available?` | `re-frame.story.xray-preset` | Pure predicate | `user-app` / `chrome-shell` | Pure predicate: true when Xray's preload is on the build (the preload namespace resolved at compile time). The chip-row, popout, and `wire-cross-host!` all check this — Story is feature-detect-safe and degrades gracefully when Xray is absent. App code MAY call this to gate UI affordances that depend on Xray being present. |
| `xray-preset/propagate-project-root!` | `re-frame.story.xray-preset` | Internal bridge | `chrome-shell` | Bridges Story's `:rf.story/project-root` from `configure!` into Xray's slot so Xray-as-RHS source-coord chips share the same on-disk root (rf2-r1uod; symmetric to shop's rf2-6jyf6). |
| `keybindings/bindings` | `re-frame.story.ui.keybindings` | Pure data table | `pure-data-for-help` | The canonical `{key → handler}` table for the chrome-visibility hotkeys (`f` / `s` / `a` / `t`). Public so the help overlay's cheat-sheet section and the `015-Test-Coverage.md` matrix row can both walk the table. See [`014-Chrome-Features.md`](014-Chrome-Features.md) §Chrome-visibility hotkeys. |
| `keybindings/shortcut-keys` | `re-frame.story.ui.keybindings` | Pure data → data | `pure-data-for-help` | Pure data → data: the sorted list of bound keys. Consumed by the first-visit help overlay so the rendered shortcut table stays in lockstep with the registry. |
| `keybindings/install!` / `keybindings/uninstall!` | `re-frame.story.ui.keybindings` | Installer pair (canonical shape) | `chrome-shell` | Install / teardown the single `window#keydown` capture-phase listener that backs the hotkey registry. Idempotent; no listener leak across re-mounts. Production builds with `re-frame.story.config/enabled?` false never install. The pair follows the canonical chrome-installer shape per [Conventions §Chrome-installer pair shape](Conventions.md#chrome-installer-pair-shape). |

## URL surfaces

Story carries **three** URL surfaces, each with distinct rules. Only
the share-URL builder sits on the facade; the other two are
chrome-internal (per the [facade re-export
discipline](#facade-re-export-discipline) above). They are documented
together as a cluster so authors generating share / address-bar / embed
code can see the three axes at a glance (rf2-zex19 follow-on, Finding
#9 of the rf2-u6o12 audit at
`ai/findings/2026-05-20-tools-story-api-review.md` (local-only)):

| Surface | Lives in | Source of truth | Persistence | Encodes | Consumer |
|---|---|---|---|---|---|
| `variant-share-url` | `re-frame.story` (facade) / `re-frame.story.share` | The arguments passed in (variant-id + active modes + cell-overrides + substrate) | URL only — surfaced via the browser's address bar (`url-state` pushState wiring) | Variant id, active modes, cell-overrides, substrate | The browser's address bar (Cmd-L Cmd-C copies it); embed iframes; pasted into chat / docs / bug reports. Variant-scoped, includes cell-overrides. |
| `url-from-state` (+ `params-from-state`) | `re-frame.story.ui.url-state` (sub-ns) | The live shell state (selected workspace, mode tab, viewport, background, tag filter) | URL + localStorage round-trip (see [`014-Chrome-Features.md`](014-Chrome-Features.md) §URL state) | Chrome-scoped state — no cell-overrides | Address bar; the chrome's own URL during interactive use. |
| `embed-flag-from-current-url` (+ `hydrate-embed-flag!`) | `re-frame.story.ui.url-state` (sub-ns) | The current page URL's `?embed=1` query string | URL only — never persisted to localStorage; one-shot at shell mount | The `:embed?` chrome-state flag (boolean) | The embed-mode flag (rf2-pucku). Hydrated once at mount, then ignored on subsequent navigations. |

The cluster gives the user three different "URLs from one shell":
the **share** URL (variant-scoped, includes cell-overrides — surfaced
as the live browser address-bar URL per rf2-ymnfx Issue B; there is
no separate Share button or QR popover),
the **address-bar** URL (chrome-scoped, no cell-overrides), and
the **embed flag** (chrome-state, URL-only, one-shot). A reader
generating URL-handling code consults this table to find the right
axis before reaching into the implementation.

## Configuration

| Var / fn | Notes |
|---|---|
| `goog-define :rf.story/enabled?` | Compile-time DCE flag; `true` in dev, `false` in `:advanced`. See [`005-SOTA-Features.md`](005-SOTA-Features.md). |
| `configure!` | `(configure! {:rf.story/global-args {...} :rf.story/global-decorators [[<dec-id> & ref-args] ...] :rf.story/editor :vscode :rf.story/project-root "..." :rf.privacy/show-sensitive? false})` — set global config at boot. Every key lives under `:rf.story/*` per the `:rf.<tool>/*` convention (spec/Conventions §Reserved namespaces); the cross-tool privacy flag uses the shared `:rf.privacy/*` reservation. `:rf.story/global-decorators` replaces the global-decorators ref vector (rf2-9qpk3 — Storybook `preview.ts` `decorators: [...]` parity); each entry is `[decorator-id & ref-args]`, the same shape a `:decorators` slot takes, and the decorator bodies must already be registered via `reg-decorator` (or `reg-global-decorator`). The resolved per-variant stack is `(concat globals story variant)` with the earliest entry the outermost wrap; `nil` / `[]` clears it. See [Global decorators](#global-decorators-reg-global-decorator--rf2-835ey). `:rf.story/project-root` is bridged into Xray's slot via `re-frame.story.xray-preset/propagate-project-root!` so Xray-as-RHS source-coord chips share the same on-disk root (rf2-r1uod; symmetric to shop's rf2-6jyf6). `:rf.privacy/show-sensitive?` is the on-box dev override that gates whether the chrome's diagnostic surfaces (Xray Event Detail, the `:test` mode pane row-detail disclosures) render path-marked values in clear vs. `:rf/redacted`; defaults to `false`. The off-box wire-egress equivalent (`:include-sensitive?` for MCP, per [spec/Conventions §Privacy config-knob naming](../../../spec/Conventions.md)) is owned by `tools/story-mcp/`. |

## Privacy

Story participates in the framework's path-level data-classification
contract — see [`000-Vision.md` §Privacy posture](000-Vision.md#privacy-posture-path-level-data-classification--spec-015)
for the marquee posture statement, and the per-surface entries:

| Surface | Behaviour | Spec |
|---|---|---|
| `story/add-marks` / `story/set-marks` (re-exports of `re-frame.core/add-marks` + `set-marks`) | Declare per-frame path-marks against `app-db`. Re-exports of the framework primitives (rf2-l6hzv) — same primitives, same data model, same per-frame semantics. `add-marks` merges additively; `set-marks` replaces the frame mark-set wholesale. Story-author discoverability aliases so authors scanning `re-frame.story`'s public surface for privacy primitives find them without chasing cross-references. See [Conventions.md §Privacy primitives — `add-marks` / `set-marks` re-export](Conventions.md#privacy-primitives--add-marks--set-marks-re-export). | [framework spec/015](../../../spec/015-Data-Classification.md) §App-db marks |
| `reg-variant` body — per-frame marks | Variant body MAY include `(story/add-marks <variant-id> {path mark, ...})` or `(story/set-marks <variant-id> {path mark, ...})` to declare `app-db` marks scoped to that variant's frame. The `:loaders` / `:events` / `:play-script` registrations honour the standard `:sensitive` / `:large` registration grammar. | [`000-Vision.md` §Privacy posture](000-Vision.md#privacy-posture-path-level-data-classification--spec-015) + [spec/015](../../../spec/015-Data-Classification.md) |
| Assertion records | `:rf.assert/*` records build `:actual` / `:expected` / `:payload` slots through `re-frame.elision/elide-wire-value` before landing in `:assertions`. The `:rf/redacted` sentinel is a legal `:expected` value for pinning the redaction contract. | [`004-Assertions.md`](004-Assertions.md) §Privacy |
| Error-projection records | `:rf.error/exception` records pass `ex-data` through `re-frame.elision/elide-wire-value`; exception `:message` strings are NOT auto-walked (author responsibility — see spec/Security.md §Author guidance for exceptions under path-level `:sensitive?`). | [`002-Runtime.md`](002-Runtime.md) §Error projection §Privacy |
| MCP read surface | Story core returns marks-as-data; wire substitution to `:rf/redacted` happens at the MCP jar's egress boundary, not in Story core. | [`000-Vision.md` §Privacy posture](000-Vision.md#privacy-posture-path-level-data-classification--spec-015) §MCP read surface |
| Snapshot-identity | Content-hash computes over real values (pre-substitution); the hash itself is unredacted but downstream emission of the inputs goes through the wire-elision walker. | [`002-Runtime.md`](002-Runtime.md) §Snapshot-identity computation + [`000-Vision.md` §Privacy posture](000-Vision.md#privacy-posture-path-level-data-classification--spec-015) §Snapshot-identity |

## Cross-references

- [`000-Vision.md`](000-Vision.md) — what Story is and isn't.
- [`001-Authoring.md`](001-Authoring.md) — the macros in full,
  with worked examples.
- [`002-Runtime.md`](002-Runtime.md) — the four-phase lifecycle and
  programmatic runtime.
- [`003-Render-Shell.md`](003-Render-Shell.md) — the UI shell.
- [`004-Assertions.md`](004-Assertions.md) — assertion vocabulary +
  play sequence.
- [`005-SOTA-Features.md`](005-SOTA-Features.md) — panels + v1/v1.1/v2
  ship lists + production elision.
- [`006-MCP-Surface.md`](006-MCP-Surface.md) — MCP boundary.
- [`007-Mode-Tabs.md`](007-Mode-Tabs.md) — the per-variant `:dev` /
  `:docs` / `:test` mode-tab contract and persistence.
- [`008-Docs-Mode.md`](008-Docs-Mode.md) — Docs pane projection and
  metadata sections.
- [`009-Test-Mode.md`](009-Test-Mode.md) — Test pane auto-run +
  summary surface.
- [`010-Toolbar.md`](010-Toolbar.md) — toolbar chrome + mode toggle.
- 011 + 012 — RETIRED per rf2-sgdd3 (actions panel + scrubber +
  trace panel deleted in favour of embedded Xray). See
  `003-Render-Shell.md` §Right-hand pane for the post-rf2-sgdd3 RHS
  contract.
- [`013-Static-Build.md`](013-Static-Build.md) — static-export
  surface + suppression rules.
- [`014-Chrome-Features.md`](014-Chrome-Features.md) — schema-validation
  panel, sidebar tag-as-badge, first-visit help overlay, command palette,
  Phase 3 chrome cluster (hotkeys / sidebar-search / skeleton /
  viewport-px / docs-TOC / embed-mode).
- [`015-Test-Coverage.md`](015-Test-Coverage.md) — browser-feature
  coverage matrix.
- [`016-Design-Tokens.md`](016-Design-Tokens.md) — chrome-identity
  typography / colour / motion / depth / iconography / toolbar 5-cluster
  token contracts.
- [`Conventions.md`](Conventions.md) — Story-specific naming and
  structural conventions (reserved namespaces, id grammars, macro/`*`-
  fn split, chrome-installer pair shape, `*-id` Var pattern, token-
  banning, `add-marks` / `set-marks` re-export).
- [`Principles.md`](Principles.md) — design principles.
- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) — why each call was
  made.
- [`tools/story-mcp/spec/API.md`](../../story-mcp/spec/API.md) — the
  MCP jar's tool surface.
