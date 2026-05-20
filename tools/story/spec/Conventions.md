# Conventions

> **Type:** Convention
> Locked naming and structural conventions that span the Story spec — reserved namespaces for Story-owned ids, the canonical id grammars, the `reg-*` macro / `*`-fn split, the chrome-installer pair shape, the `*-id` Var pattern, and the token-banning rules.

The framework's [`spec/Conventions.md`](../../../spec/Conventions.md) catalogues conventions that span the **whole** spec; this document catalogues the conventions that span only Story. The two are read together: Story honours every framework convention (single `:rf/*` root, `*`-suffix on programmatic fn partners, bang on process-level lifecycle, `:rf.assert/*` is framework-reserved per Story's library-owned `:story.*` carve-out), and adds the Story-specific rules below.

## Reserved namespaces (Story-owned)

Story is a [**library-owned prefix**](../../../spec/Conventions.md#library-owned-prefixes) under the framework's single-root convention. The carve-outs live outside `:rf.*` (`:story.*` and `:Workspace.*` for user-facing ids) and inside `:rf.story.*` (for Story-emitted framework events). Both sets are reserved by Story's spec; both follow the framework's "fixed-and-additive" discipline.

### The `:story.*` / `:Workspace.*` user-facing carve-outs

| Sub-namespace | Used for | Spec |
|---|---|---|
| `:story.<path>/<name>` | User-authored story ids (`:story.auth/login-form`) and variant ids (`:story.auth.login-form/empty`). The path segments mirror the source-tree feature hierarchy; the trailing `/<name>` names the story or variant within that path. | [001-Authoring.md](001-Authoring.md) §Story id grammar |
| `:Workspace.<path>/<name>` | User-authored workspace ids (`:Workspace.Auth/all-states`). The capitalised root segment is the convention — workspaces are top-level affordances, not story-graph leaves. | [001-Authoring.md](001-Authoring.md) §Workspace id grammar |
| `:Mode.<path>/<name>` | User-authored mode ids consumed by the toolbar (`:Mode.Theme/dark`, `:Mode.Viewport/mobile`). The capitalised root names the axis; the trailing `/<name>` names the value. | [010-Toolbar.md](010-Toolbar.md) §Mode id grammar |

User code MUST use one of these grammars for the corresponding registration kind. The capitalised root segments (`:Workspace.*`, `:Mode.*`) are visually distinct from the lower-case `:story.*` so a reader scanning a registration form can tell the kind from the id alone.

### The `:rf.story.*` framework carve-out

Story-emitted framework events and panel ids live under the framework's `:rf.*` root, in the `:rf.story.*` sub-namespace. The framework's [`spec/Conventions.md`](../../../spec/Conventions.md) reserves the sub-namespace; Story owns the closed member set.

| Sub-namespace | Closed member set | Spec |
|---|---|---|
| `:rf.story/*` | Story-emitted events (`:rf.story/save-current-as-variant`) and built-in decorator ids (`:rf.story/force-fx-stub`). | [005-SOTA-Features.md](005-SOTA-Features.md) |
| `:rf.story.panel/*` | Story-panel registration ids (`:rf.story.panel/schema-validation`, `:rf.story.panel/epoch-view`). The `panel` segment is the discriminator so a reader scanning a panel id can tell the registry kind. | [003-Render-Shell.md](003-Render-Shell.md) §Panel registration contract |
| `:rf.story.layout-debug/*` | Built-in layout-debug decorator ids (`:rf.story.layout-debug/measure`, `:rf.story.layout-debug/outline`, `:rf.story.layout-debug/pseudo`). | [005-SOTA-Features.md](005-SOTA-Features.md) §Layout debug |

Third-party Story extensions MUST NOT register handlers, fx, subs, panels, or decorators under `:rf.story.*`. Library authors choose their own top-level prefix per [framework Conventions §Library-owned prefixes](../../../spec/Conventions.md#library-owned-prefixes).

## `reg-*` macro / `*`-fn split

Every registration kind in Story ships **two surfaces** under the same name:

- The `reg-<kind>` **macro** — author-facing. Captures call-site source-coords (file + line + ns) via `&form` meta + `*file*` + `(ns-name *ns*)`, threads them into the runtime helper via `re-frame.story.macros`. This is the form authors write in their stories ns.
- The `reg-<kind>*` **fn** — programmatic. Same body shape, no source-coord stamping (callers supply coords explicitly if needed). This is the form MCP write tools and hot-reload tooling call.

Mirror of [`re-frame.core`](../../../implementation/core/src/re_frame/core.cljc)'s `dispatch` / `dispatch*` precedent. The seven registration kinds ship both surfaces uniformly:

| Macro | `*`-fn partner | Body shape | Spec |
|---|---|---|---|
| `reg-story` | `reg-story*` | story body + optional `:variants` desugaring (Form B) | [001-Authoring.md](001-Authoring.md) |
| `reg-variant` | `reg-variant*` | variant body (`:doc`, `:component`, `:args`, `:decorators`, `:play`, …) | [001-Authoring.md](001-Authoring.md) |
| `reg-workspace` | `reg-workspace*` | workspace body (`:layout`, `:variants`, `:content`, `:render`, …) | [001-Authoring.md](001-Authoring.md) |
| `reg-mode` | `reg-mode*` | mode body (`:doc`, `:axis`, `:args`) | [001-Authoring.md](001-Authoring.md) + [010-Toolbar.md](010-Toolbar.md) |
| `reg-story-panel` | `reg-story-panel*` | panel body (`:title`, `:placement`, `:render`) | [001-Authoring.md](001-Authoring.md) + [003-Render-Shell.md](003-Render-Shell.md) |
| `reg-decorator` | `reg-decorator*` | decorator body (three kinds: `:hiccup` / `:frame-setup` / `:fx-override`) | [001-Authoring.md](001-Authoring.md) + [002-Runtime.md](002-Runtime.md) |
| `reg-tag` | `reg-tag*` | tag body (`:doc`, `:axis`, `:default-filter`) | [001-Authoring.md](001-Authoring.md) |

Each macro elides to `nil` under `:advanced` builds via the `re-frame.story.config/enabled?` sentinel — see [Principles §Production elision strict](Principles.md#production-elision-strict). The `*`-fn partner does not elide automatically; production builds DCE it via Closure when nothing references it. Tests that drive registration programmatically use the `*`-fn partner.

### Why `reg-story-panel` is three tokens (and not `reg-panel`)

`reg-story-panel` is the only **three-token** name in the `reg-*`
family — every other registration kind is two tokens (`reg-story`,
`reg-variant`, `reg-workspace`, `reg-decorator`, `reg-tag`, `reg-mode`).
The longer name has been examined and intentionally retained
(rf2-u1w4w follow-on, Finding #2 of the rf2-u6o12 audit at
`ai/findings/2026-05-20-tools-story-api-review.md` (local-only)):

- **The registry kind is `:story-panel`** (per [`API.md` §Registry queries](API.md#registry-queries)
  — `(registrations :story-panel)` / `(ids :story-panel)` /
  `(registered? :story-panel <id>)`). The two-token kind keyword is the
  discriminating noun; the macro name mirrors the kind keyword so the
  surface stays self-describing — a reader who sees `reg-story-panel`
  knows the registry kind is `:story-panel` without consulting the
  spec.
- **`reg-panel` would conflict with the framework's panel concept.** The
  framework's `re-frame.core` does not own a panel registry, but the
  word `panel` is ambient in re-frame application code (route-as-panel,
  panel components, panel ids in `app-db`). The `story-panel` qualifier
  signals that the registered surface is **a Story chrome panel** —
  rendered next to the variant canvas, registered through Story's late-
  bind contract (per [003-Render-Shell.md](003-Render-Shell.md) §Panel
  registration contract) — not a generic application panel.
- **The two-token reading is not load-bearing.** The cluster's
  cognitive shape is "every `reg-*` registers one kind"; whether the
  hyphenated noun is one or two tokens is a surface detail. The
  occasional new reader who conflates `reg-story-panel` with
  `reg-story` recovers from the conflation by reading the docstring or
  the `:story-panel` registry-kind keyword.

The rename to `reg-panel` is mechanical (one macro + its `*`-fn partner
+ test selectors + spec references) but the contract surface is broad
(authoring docs, MCP write surface, Causa late-bind contract). The
3-token form is retained; this section names the rationale so future
audits do not re-open the question without new evidence.

## Chrome-installer pair shape

Story's chrome ships several subsystems with their own install / teardown / hydrate surface (keybindings, URL state, recorder, toolbar persistence, element inspector, save-variant modal, schema-validation panel). The canonical shape:

| Slot | Purpose |
|---|---|
| `install!` | Bang-suffixed lifecycle entry — install the listener / register the cofx / mount the host. Idempotent — re-installing replaces the previous registration without leaking state. Gated behind `re-frame.story.config/enabled?` so production builds DCE the lot. |
| `uninstall!` | Bang-suffixed teardown — symmetric to `install!`. Returns the subsystem to its pre-install state. Idempotent — a second call is a no-op. |
| `hydrate!` *(when applicable)* | Bang-suffixed one-shot hydrator for persisted state (typically localStorage- or URL-sourced). Distinct from `install!` because hydration is a one-time read at mount; `install!` covers ongoing lifecycle. Subsystems that own no persisted state ship no `hydrate!`. |

**The canonical pair is `install!` / `uninstall!`** — `install!` for set-up, `uninstall!` for tear-down. The pair is symmetric, idempotent, and conditional on `re-frame.story.config/enabled?`. Subsystems that own persisted state additionally ship `hydrate!` (one-shot, called after `install!` at mount time). Subsystems that own multiple hydration sources name them by source: `hydrate-from-storage!`, `hydrate-from-url!` — same shape, source-suffixed.

Pre-Conventions Story shipped six divergent shapes (`install!`/`remove!`, `install-popstate-listener!`/`remove-popstate-listener!`, `install-state-watcher!`/`remove-state-watcher!`, `tear-down!` as umbrella, no-teardown subsystems with `mark-seen!`/`reset-seen!`, no-install subsystems with only hydrators). The canonical shape above is the target; existing subsystems whose names diverge from `install!`/`uninstall!`/`hydrate!` are catalogued in `tools/story/spec/API.md` §Chrome-host surface and migrate by future bead. New subsystems MUST use the canonical pair.

## The `*-id` Var pattern for built-in decorator ids

Story ships a small set of built-in decorator ids (the `:rf.story/force-fx-stub` universal-mock primitive, the three `:rf.story.layout-debug/*` overlays). The keyword IS the API; the Var is a name for the keyword. Story exposes each as a `*-id`-suffixed Var on the public facade:

| Var | Holds | Spec |
|---|---|---|
| `force-fx-stub-id` | `:rf.story/force-fx-stub` | [005-SOTA-Features.md](005-SOTA-Features.md) §`force-fx-stub` |
| `layout-debug-measure-id` | `:rf.story.layout-debug/measure` | [005-SOTA-Features.md](005-SOTA-Features.md) §Layout debug |
| `layout-debug-outline-id` | `:rf.story.layout-debug/outline` | [005-SOTA-Features.md](005-SOTA-Features.md) §Layout debug |
| `layout-debug-pseudo-id` | `:rf.story.layout-debug/pseudo` | [005-SOTA-Features.md](005-SOTA-Features.md) §Layout debug |

The `-id` suffix is load-bearing — it signals "this is a registered id, not the registration itself or its handler." Authors writing decorator references in variant bodies use the Var (`story/force-fx-stub-id`) rather than the verbose keyword path. Third-party panel authors whose decorator ids are short MAY skip the `-id` suffix; the convention is for Story's own built-ins where the keyword path is verbose enough to be worth aliasing.

## Token-banning at chrome consumers

Per [016-Design-Tokens.md](016-Design-Tokens.md), chrome consumers consume **design tokens**, not raw literals. Three banned patterns at chrome call sites:

- **No raw `font-family` literals.** Chrome consumes `sans-stack` / `mono-stack` / `display-stack` from `re-frame.story.theme.typography`. The rule is enforced as a ban under rf2-2rwdc AC#5.
- **No raw hex literals (`#xxxxxx`).** Chrome consumes `(:token-name re-frame.story.theme.colors/tokens)`. The rule is enforced as a ban under rf2-i3i5j AC#3.
- **No raw `transition` literals.** Chrome consumes `(:row re-frame.story.theme.motion/transitions)` (or any pre-composed transition). The rule is enforced as a ban under rf2-3lt89 follow-on sweep.

The bans are in the linter / CI rules, not just doc'd — see [016-Design-Tokens.md](016-Design-Tokens.md) §Token contract. Third-party Story panel authors honour the same contract: panels that ship in user repos use the same token namespaces so light / dark / future themes apply uniformly.

## Privacy primitive — `reg-marks` re-export

Per [framework spec/015](../../../spec/015-Data-Classification.md), `reg-marks` declares per-frame path-marks (`:sensitive`, `:large`) against `app-db`. Variant bodies use it to declare path-marks scoped to a single variant's frame:

```clojure
(story/reg-variant :story.auth/login-form
  {:component login-form
   :args {:user/email "ada@example.com"
          :user/password "•••••"}})

(story/reg-marks :story.auth/login-form
  {:sensitive [[:user :password]
               [:auth :token]]})
```

`story/reg-marks` is a re-export of `re-frame.core/reg-marks` (no fork; same primitive, same data model, same per-frame semantics). The re-export lives on the Story facade purely for **discoverability** — authors scanning `re-frame.story`'s public surface for privacy primitives find one without chasing cross-references into `re-frame.core`. See [framework Conventions §Library-owned prefixes](../../../spec/Conventions.md#library-owned-prefixes) for the principle that canonical devtools re-export framework primitives where ergonomic.

## Cross-references

- [framework spec/Conventions.md](../../../spec/Conventions.md) — the spec-wide convention catalogue Story inherits.
- [Principles.md](Principles.md) — the non-negotiables (EDN-first, production elision strict, no new framework registries, …) that drive the conventions above.
- [API.md](API.md) — the consolidated surface listing every macro / fn / fx-id / cofx-id covered by the conventions.
- [001-Authoring.md](001-Authoring.md) — registration grammar (id grammars table above cross-links each row to the spec section).
- [010-Toolbar.md](010-Toolbar.md) — mode id grammar.
- [016-Design-Tokens.md](016-Design-Tokens.md) — token contracts (ban rules above cross-link).
