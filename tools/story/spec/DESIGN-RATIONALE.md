# Story — Design Rationale

> WHY each major design call was made. The capability docs say *what*
> the surface is; this document says *why* it's that shape and not
> some other plausible shape. The seven rf2-m6tu §6 decisions and the
> five ownership boundaries that accompany them, the Phase-2 SOTA
> additions, plus the calls that emerged during the IMPL-SPEC drafting
> itself.

## The seven architectural decisions (rf2-m6tu §6, resolved 2026-05-11)

The seven open questions from Phase 1 (rf2-m6tu §6) resolved as
follows. Each is binding for Stages 2–8.

### §separate-mcp-jar — MCP server ships as separate jar

**Decision.** The MCP agent surface ships as `day8/re-frame2-story-mcp`
at `tools/story-mcp/`. The Story core jar (`day8/re-frame2-story`)
carries **no** stdio / JSON-RPC dependency.

**Rationale.** The MCP server depends on transport machinery (stdio
adapter, JSON-RPC framing, asynchronous-handler runtime) that the
vast majority of Story consumers never load. Splitting at the jar
boundary keeps the Story core lean and lets the MCP surface evolve on
its own cadence. The pattern mirrors `tools/machines-viz/` vs.
`tools/machines-viz-mcp/` (per [`tools/README.md`](../../README.md)).

**Implication.** Story's core jar exposes the *read* primitives the
MCP server consumes (`registrations`, `frame-meta`, `run-variant`,
`snapshot-identity`, `variant->edn`); the MCP server packages them as
tools per the Storybook MCP Dev / Docs / Testing toolset split. See
[`006-MCP-Surface.md`](006-MCP-Surface.md).

### §inline-substrate-failures — render inline

**Decision.** A variant may declare `:substrates #{:reagent :uix}`
(subset, default = the host frame's adapter); when Story
renders the variant against multiple substrates the failure for each
is rendered **inline** with the variant pane — not auto-skipped.
`:reagent-slim` is reserved for addition at reagent-slim GA / first
published artefact — the same gate as Story's UI-shell migration (see
[`Principles.md`](Principles.md) §Reagent for the v1 UI shell) and the
template's fourth substrate choice (see
[`tools/template/spec/001-Substrate-Variants.md`](../../template/spec/001-Substrate-Variants.md)
§Future variants); until then it is not on the canonical substrate
enum.

**Rationale.** Substrate-portability gaps are the entire point of
multi-substrate rendering. Hiding failures hides the bugs Story
exists to surface. Stage 1 of any substrate-portability audit is
"look at the red panes."

**Implication.** The render shell treats per-substrate render in a
try/catch boundary; per-variant `:assertions` accumulates a
substrate-tagged failure entry; the multi-substrate pane shows the
error inline alongside the healthy substrates' renders. See
[`003-Render-Shell.md`](003-Render-Shell.md) §Multi-substrate.

### §record-not-throw — assertion failures record, don't throw

**Decision.** `:rf.assert/*` events **record** failures into the
variant's `:assertions` list and continue the play sequence. They do
not throw.

**Rationale.** Play sequences run to completion; the full picture of
"what went wrong" is more useful than "first failure halts
everything." Aligns with re-frame's run-to-completion drain semantics
([Spec 002](../../../spec/002-Frames.md)). Mirrors devcards' behaviour;
diverges from Storybook (which throws). Storybook's choice is
constrained by JavaScript's async-throw mess; we have no such
constraint.

**Implication.** Each `:rf.assert/*` handler returns a map describing
the assertion result; the play-runner concatenates these into the
variant's `:assertions` list. `run-variant`'s test-runner adapter
post-processes the `:assertions` list and translates failures into
the host test framework's failure signal — `cljs.test`'s `is`,
kaocha's reporter, etc. See [`004-Assertions.md`](004-Assertions.md).

### §loaders-complete-when-predicate — `:loaders-complete-when`

**Decision.** Variant body may include an optional
`:loaders-complete-when` event predicate. Default behaviour:

- HTTP-flavoured fx is "complete" when the response event has been
  dispatch-synced.
- Long-lived fx (`:websocket`, `:interval`, `:firestore`, etc.) is
  "complete" when the first message arrives.
- Authors override via a vector-of-event-vectors or a registered
  predicate-event id.

**Rationale.** The
[spec/007 §Loader-lifecycle](../../../spec/007-Stories.md) phrasing
— "no further events are in flight against the variant's frame" —
works for request/response fx but never satisfies for long-lived fx.
The default-plus-override design keeps simple cases simple and makes
the long-lived case explicit.

**Implication.** Stage 3 (runtime) implements the four-phase
lifecycle with the loader-complete check after each loader's drain.
The default predicate is "first non-loader event seen by the frame,
or loader's drain settles with no in-flight fx, whichever comes
first." Stage 2 macro validates that `:loaders-complete-when`
resolves to a registered event id or is a literal data form (vector
of event vectors). See [`002-Runtime.md`](002-Runtime.md).

**Open flag.** The default ("first non-loader event seen, or drain
settles") is a heuristic; the override is the safety valve. There's
a future world where the default misfires (e.g. a websocket's first
message is a heartbeat that isn't semantically "the data is ready").
The override exists for exactly that case; authors who hit it should
file a Pattern doc, not work around it in the variant body.

### §both-workspace-persistence — local + transit

**Decision.** Workspace layouts persist **both** ways:

- **Default: local-storage.** Interactive rearrangements auto-save
  to local storage keyed by `[workspace-id breakpoint]`. Per-user,
  per-browser.
- **Save-as registered artefact.** A "Save layout as `:Workspace.x/y`"
  button serialises the current layout to transit, re-registers it
  under the chosen id, and exports the transit blob for cross-machine
  sharing.

**Rationale.** Nubank's workspaces ships both for the same reason:
ephemeral edits should not require a registration ceremony;
deliberate-share edits need a durable artefact. Local-storage is
"where am I right now"; transit-exported is "this is the team layout
for Friday's review."

**Implication.** The render shell wires the local-storage save on
every layout change and adds the "Save layout" affordance.
`tools.story.workspace.transit/workspace->edn` returns the serialised
form. See [`003-Render-Shell.md`](003-Render-Shell.md) §Workspace
persistence.

### §DCE-dev-only — registration is dev-only

**Decision.** `reg-story`, `reg-variant`, `reg-fragment`, `reg-check`,
`reg-workspace`, `reg-mode`, `reg-story-panel`, `reg-decorator`, and
`reg-tag` are **all dev-only**. Under `:advanced` compiler builds, all
nine macros elide to `nil` — every one routes through
`re-frame.story.macros/emit-reg`, the single site that lays down the
`(when re-frame.story.config/enabled? …)` elision gate.

**Rationale.** Cross-library `:extends` (where lib A registers
`:story.x/parent` and lib B has `:extends :story.x/parent`) becomes
irrelevant in production builds because no registrations exist.
There is no consumer code that cares about a story registration *at
runtime in production* — Story is a dev tool. Eliding the
registration form-by-form collapses an entire class of "this won't
dead-code-eliminate cleanly" bugs.

**Implication.** Stage 2 macros expand to one form in dev mode (the
registration call) and to `nil` in production. Compile-time flag is
`goog-define :rf.story/enabled?` (default `true`; downstream apps
override to `false` for prod builds). See
[`005-SOTA-Features.md`](005-SOTA-Features.md) §Production elision.

### §xray-embed — embed via Xray's per-panel mount API

**Decision.** Story does **not** reimplement a diagnostics UI. Story
mounts Xray's own panels into its right-hand inspector through
Xray's explicit per-panel mount API (Xray is the structural
successor to re-frame-10x, per
[`tools/xray/spec/DESIGN-RATIONALE.md`](../../xray/spec/DESIGN-RATIONALE.md)
Lock #1). The RHS hosts **one** panel at a time; a chip-row picker
swaps the lens at runtime, and a story may preselect one through the
`:xray-panel` slot.

The integration point is a single embed descriptor — the sole
mount-routing owner — pairing each panel id with the
`day8.re-frame2-xray.panels/mount-<panel>!` fn that renders it:

```clojure
;; tools/story/src/re_frame/story/ui/xray_embed.cljs
[{:panel :epoch    :chip? true :mount xray-panels/mount-epoch-panel!}
 {:panel :app-db   :chip? true :mount xray-panels/mount-app-db-diff!}
 {:panel :views    :chip? true :mount xray-panels/mount-reactive-panel!}
 {:panel :trace    :chip? true :mount xray-panels/mount-trace!}
 {:panel :machines :chip? true :mount xray-panels/mount-machine-inspector!}
 {:panel :routing  :chip? true :mount xray-panels/mount-routing!}
 ;; non-chip: routable, but never offered in the picker
 {:panel :event-spine :chip? false :mount xray-panels/mount-event-spine!}]
```

Six chip panels plus the non-chip `:event-spine` band. The chip-row
catalog, the valid-slot set, and the mount dispatch are all *derived*
from this one descriptor, so a panel cannot be half-wired; `:chip?`
is what keeps the exposed set a deliberate curated subset. `:epoch`
is the default lens.

**Rationale.** Unchanged from the original call — which is why the
mechanism could be replaced without reopening the decision. Each
panel's UX is already best-in-class inside Xray (carrying forward the
design re-frame-10x established). Reimplementing any of it inside
Story would (a) double the implementation surface, (b) split the
maintenance work, (c) drift over time. Embedding keeps one source of
truth.

Per-panel mounting beats the whole-shell embed it replaced on two
further counts. **Width:** Xray's four-layer chrome needs roughly
720px to render usefully and Story's RHS is 320px, so the whole shell
clipped horizontally on every render. **Focus:** Story is a workshop
— the user hovers over one component asking one diagnostic question,
and one panel is one diagnostic lens.

**Implication.** Story owns the *integration* — the descriptor, the
chip row, the context bridge, and lifecycle cleanup. Xray owns the
panel internals and stays its own artefact, on its own release
cadence. Every mount path wraps the panel in
`[rf/frame-provider {:frame :rf/xray} …]`, so a panel's own state
resolves on `:rf/xray` regardless of the host's React context.

`reg-story-panel` is **not** this mechanism. It remains Story's own
chrome extension point (a11y, schema validation, layout debug) plus a
user-facing authoring hook for third-party panels; the machines-viz
panel-adapter promise was dropped on exactly that reading
(`rf2-w9rohp`, closed), with the Machines chip above standing as
Story's machine-chart surface. The provenance for the per-panel
change lives in the `re-frame.story.ui.xray-embed` namespace
docstring. See
[`005-SOTA-Features.md`](005-SOTA-Features.md) §Xray epoch panel
embed.

## The five ownership boundaries (rf2-m6tu)

The same decision packet closed with a refinement the seven calls
depend on: state the ownership boundaries precisely, so that a later
feature cannot quietly annex a neighbour's job. They are recorded
here because they are the test a Story change has to pass, not
commentary on it.

1. **Story-MCP owns protocol transport; Story owns artefacts and
   execution.** The split is the one §separate-mcp-jar makes
   physical — transport lives behind a jar boundary precisely so it
   cannot leak into the core.
2. **The Tool Pair is the only live-browser agent door**
   (`rf2-3fc89f.22`, closed). Story does not grow a second, parallel
   channel for agents to reach a running browser.
3. **Story owns scenario context; Xray owns diagnostic computation
   and panels.** This is §xray-embed stated as a rule rather than a
   mechanism, and it is what makes "just one small duplicate
   inspector" the way the boundary erodes.
4. **Local storage owns ephemeral layout preference; exported
   registered EDN/transit owns durable team intent.** A pane the user
   dragged is not a decision the team made; only the exported form
   travels.
5. **The unified run result owns truth; UI, tests, and agents only
   project it.** Three consumers, one record — none of them may
   compute a fourth answer of its own.

## Decisions surfaced during IMPL-SPEC drafting

These decisions emerged while writing the original 8,614-word
IMPL-SPEC; flagged for Mike's review per the "Mike's delegation
extends to additional decisions surfaced during this stage"
instruction.

### §reagent-ui-shell — UI shell is Reagent at v1

The Story tool's own chrome (sidebar, control panel, trace ribbon,
etc.) is rendered using Reagent
(`implementation/adapters/reagent/`). reagent-slim is still landing
(rf2-5djt); Story should not block on it. Stage 8 may revisit and
migrate once reagent-slim is GA. See
[`003-Render-Shell.md`](003-Render-Shell.md) §UI shell substrate.

The tension is that Story is *for* re-frame2 apps and most of those
will eventually move to reagent-slim. Stage 8 re-opens this
decision once reagent-slim hits GA; until then Reagent.

### §public-ns-root — `re-frame.story` for user-facing API

All public `reg-*` macros and the `run-variant` family live under
`re-frame.story`. Internal namespaces live under `tools.story.*` (see
[`003-Render-Shell.md`](003-Render-Shell.md) §Namespace layout). This
matches the convention from `re-frame.adapter.reagent`,
`re-frame.ssr`, etc.

### §reg-mode-in-v1 — `reg-mode` ships in v1

Per Phase 2 §5.2 #3, the Chromatic-style mode primitive (saved tuples
of global args) lands in v1 — not v1.1 — because the implementation
cost is small (it's a saved `args` map plus a snapshot-identity
contribution) and the agent-integration benefit is large (MCP can
iterate variants × modes without combinatorial registration).

### §variants-grid-in-v1 — `:variants-grid` workspace layout ships in v1

Per Phase 2 §5.2 #4. devcards-style multi-variant viewing has no JS
competitor; the implementation cost is layout-only.

### §qr-retired — QR code share affordance retired

Originally proposed as a v1 polish (Phase 2 §5.2 #6, "tiny
implementation, high signal"). Retired before ship: the security audit
(rf2-20w5i) flagged the third-party QR-image service as an off-box leak
of author-typed `:cell-overrides`, and rf2-ymnfx Issue B then retired
the whole Share/QR popover as redundant with the live address-bar URL
(Cmd-L / Cmd-A / Cmd-C copies it). No `qr-code` dep, no `share/qr`
namespace, no QR affordance ships.

### §layout-debug-in-v1 — Layout-debug trio ships in v1

Per Phase 2 §5.2 #2. DOM-mutating utility; framework-agnostic; cheap.

### §perf-ribbon-in-v1.1 — Perf ribbon ships in v1.1

Per Phase 2 §5.2 #1. Live FPS/INP/CLS/memory + Reagent-render-profiling
at 50ms refresh; non-trivial implementation (requires
`PerformanceObserver`, frame-loop sampler, Reagent profile hooks).
Defer to first follow-up release.

### §design-tokens-in-v1.1 — Design-token panel ships in v1.1, conditional

Per Phase 2 §5.2 #5. Iff `re-com` or the host design system emits
Style-Dictionary-shaped tokens. Stage 6 ships the panel; activation
is conditional on token emission upstream.

(Distinct from **Story's own chrome-identity tokens** —
typography / colour / motion / backdrop / glyphs / toolbar
5-cluster — which shipped in Phase 1 + Phase 2 and are normative
per [`016-Design-Tokens.md`](016-Design-Tokens.md). The §design-
tokens-in-v1.1 panel here is a Storybook-style affordance that
RENDERS a host app's design tokens for the developer to inspect;
the chrome's own tokens are the substrate that affordance would
render against.)

### §xray-is-a-declared-dependency — Xray is Story's diagnostic engine, not an optional plugin

An earlier draft of this document promised the opposite: that Story
"never takes a hard dependency on Xray", that the embed surface would
check the classpath and render a short "Xray is not loaded in this
build" placeholder when Xray was missing, and that the
popout-to-full-Xray chip was gated on the same check.

The shipped artefact could never reach that state, and rf2-r8trk
retired the promise rather than the coupling. Story's shell composes
Xray unconditionally in three places: the RHS inspector mounts Xray's
own panels through `day8.re-frame2-xray.panels/mount-<panel>!`, the
popout escape hatch calls `day8.re-frame2-xray.mount/popout!`, and the
evidence spine routes focus commands through
`day8.re-frame2-xray.core/focus!`. All three are hard compile-time
`:require`s. The "graceful absence" branch was unreachable code
guarded by a vacuous predicate — `xray-available?` reduced to
`(some? xray-mount/open!)` *after* a direct require, so any build that
compiled had already proved the symbol bound.

Worse, `tools/story/deps.edn` declared no Xray dependency at all. The
repository-wide Shadow build masked that by carrying
`../tools/xray/src` on its global `:source-paths`, so every in-repo
build compiled while a fresh consumer whose only tool dependency was
`day8/re-frame2-story` could not compile the shell at all.

So the package graph now matches the product. `day8/re-frame2-xray` is
a declared, lockstep-versioned Story dependency; the absence branch,
its placeholder, and the availability predicate are gone; and
installing Story is one coordinate. This is the honest shape of the
six-panel + event-spine artefact Story actually ships, and it avoids
inventing optional-plugin machinery for a lens the shell cannot
render without.

Story and Xray remain separate artefacts on the same release cadence —
the dependency runs Story → Xray only, never back, so there is no
cycle. Both are dev-only tools; neither reaches a production bundle,
so the bundle-isolation contract (which forbids `implementation/` →
`tools/`) is untouched.

No feature-detect survives. The last one — a probe for
`day8.re-frame2-xray.filters.config/configure!` — was guarding a
namespace Xray has never exposed, so it was permanently false and
Story's `:xray {:filters …}` preset silently did nothing despite
validating cleanly against its schema. rf2-q5pd6 removed the probe and
wired the slot to the surface Xray had shipped all along: the
`:rf.xray/filters` seed on `config/configure!`, plus the
`:rf.xray/hydrate-filters` event on the `:rf/xray` frame.

The one genuine piece of translation at that seam is a SHAPE
difference, not an availability question. Story's public API is a
vector of bare event-id keywords; Xray matches on pills. Xray's
`canonicalise-pill` reads a bare keyword as the `:never` kind, so
handing Story's shape over verbatim would have swapped one silent
no-op for another. `xray-preset/lower-filters` is the boundary that
keeps Story's compact authoring API and gives Xray its canonical
representation.

## Phase-2 SOTA additions — tier choices

Phase 2's six concrete additions to Phase 1's feature spec:

| Item | Tier | Rationale |
|---|---|---|
| In-canvas live performance ribbon | v1.1 | Non-trivial implementation; defer for first follow-up. SOTA-table-stakes per Phase 2 §5.2 #1. |
| Layout-debug overlay trio | v1 | Cheap; framework-agnostic; all three together are best-in-class. |
| `reg-mode` saved-tuple primitive | v1 | Small implementation cost; large agent-integration benefit. |
| `:variants-grid` workspace layout | v1 | Layout-only; unique to re-frame2 (no JS workshop ships it). |
| Design-token panel | v1.1 conditional | Iff upstream emits tokens; defer panel activation. |
| Per-variant QR sharing | v1 | Tiny implementation; high signal. |
| App-db snapshot diff (data-space VR) | v2 roadmap | Unique to re-frame2's data-centric model; no JS analogue. |

## Why a lifecycle machine via re-frame.machines?

(Anticipating future readers.) The four-phase loader lifecycle is
*not* implemented as a `reg-machine` state machine. Reasoning:

- The lifecycle is intra-frame coordination, not user-facing state.
- Each variant *is* its own frame; the lifecycle runs once per frame
  mount, not as a long-lived state of the application.
- `:loaders-complete-when` is a *predicate* over events seen on the
  frame, not a transition guard — re-frame's drain machinery already
  has the right primitive.

A machine would over-shape the lifecycle; the existing four-phase
sequence + drain-settle check is closer to the problem domain.

## Rejected — what Story deliberately doesn't ship

Each named with rationale so contributors have a clear "no" list.

The first cluster below — **Storybook commodity patterns** — is the
named "no" list relative to Storybook 8 specifically. Story is the
re-frame2 workshop equivalent and Storybook is the popular comparator;
copying its identity-bearing choices would erase Story's identity. The
positive contracts these entries reject are pinned in
[`016-Design-Tokens.md`](016-Design-Tokens.md) (typography / colour /
motion / backdrop / iconography / toolbar 5-cluster) and in
§record-not-throw above. The cross-references below carry the reader
to the relevant lock. See the rf2-38pb9 audit verdict (warm-slate +
amber + Plex + motion-as-language >> Storybook's cold-grey + pink +
Inter + flat) for the comparator pass.

### Storybook commodity patterns (the four-pattern cluster)

The four entries that follow — brand-pink-on-cold-grey, commodity-default
fonts, addon-per-concern panels, throw-on-first-failure — are the four
Storybook-comparator rejections recorded under the rf2-aezbb follow-on
to the rf2-38pb9 audit. Each names the Storybook practice, what Story
does instead, and the lock the positive contract lives under. These are
the deliberately-named "do not drift back" markers for future workers;
they pair with the §record-not-throw + §inline-substrate-failures locks
above (which are also Storybook-comparator rejections, written as part
of the original seven §rf2-m6tu §6 decisions rather than as a comparator
sweep). Treat the cluster's intro paragraph above and the four entries
below as a single discoverable subsection — proposals to revisit any of
the four bear the burden of disturbing the comparator-rejection lock,
not just the individual rationale.

### Rejected: Brand-pink-on-cold-grey chrome palette — we use amber-on-warm-slate

**Rejected.** Storybook 8 ships a cold-grey chrome (`#1B1C1F` / `#2D3036`
/ `#9E9E9E` neutrals) accented by its brand pink (`#FF4785`). The
combination reads as the rubric's "predictable layout that lacks
context-specific character" — a generic dark-mode neutral plus a brand
hot pink, the pattern AI-generated component-explorer chromes
converge on.

**Why.** Story's identity palette is **warm-slate + amber** — a warm
neutral substrate with amber as the accent, the inverse of the
cold-grey + pink convergence point. Per
[`016-Design-Tokens.md`](016-Design-Tokens.md) §Colour the palette is
locked: warm-slate `:bg-*` tokens (substrate with a touch of warmth so
the chrome reads as a workshop rather than a forensic console), amber
`:accent-amber*` tokens (the active-row / active-chip / sidebar-glyph
identity), and the semantic foreground tokens (`:text-primary` /
`:text-secondary` / `:text-tertiary`). The pairing also achieves the
**two-surface, two-role** signal when Xray lands in the RHS: Xray is
cool-grey + cyan (diagnostic), Story is warm-slate + amber (workshop)
— the user reads "workshop" vs "diagnostic" without needing labels.
Raw hex literals at call sites are banned (rf2-i3i5j AC#3); the
contract is enforced via the `theme.colors/tokens` map.

### Rejected: Commodity-default fonts (Inter / Nunito Sans / system-ui)

**Rejected.** Storybook 8 ships Nunito Sans + the system stack. The
broader JS-tooling field converges on Inter (Vercel, Linear, Stripe,
many AI-generated landing pages) or `system-ui` (the cookie-cutter
floor: GitHub, npm, most CRA defaults). The 2026 convergence point on
"workshop UI" typography is Inter + JetBrains Mono.

**Why.** Story's canonical sans + mono pair is **IBM Plex Sans + IBM
Plex Mono**. Per [`016-Design-Tokens.md`](016-Design-Tokens.md)
§Typography the pair is locked: Plex carries IBM's editorial bias —
geometric without being sterile, with characterful italics, a
confident `g`, and a mono sibling tuned to the same proportions. The
sans + mono pair share design DNA so chrome that mixes them (a
variant id rendered next to its status text) holds together
typographically. **Crucially, the pair distinguishes Story from Xray
visually**: Xray uses Inter + JetBrains Mono — Story's Plex pair is
the typographic complement, not the same family. Raw `font-family`
strings at call sites are banned (rf2-2rwdc AC#5); `sans-stack` /
`mono-stack` / `display-stack` are the public contract.

### Rejected: Addon-per-concern panel architecture (Storybook's eight + addons)

**Rejected.** Storybook ships a panel-per-concern surface: theme switcher,
viewport switcher, locale toggle, a11y panel, actions panel, measure
overlay, outline overlay, highlight overlay — each is a separate
addon with its own registration, its own toolbar slot, its own
configuration surface, and its own bundle weight. The user composes
chrome by installing-and-configuring eight addons.

**Why.** Story collapses these eight concerns into **two registered
primitives** plus one decorator: `reg-mode :axis` for the
theme / viewport / locale / background axis (saved-tuple modes —
toggleable on the toolbar, multi-select across axes, single-select
within axis — see [`010-Toolbar.md`](010-Toolbar.md)); `reg-story-panel`
for a11y / actions / measure / outline / highlight as registered
panels (one registration mechanism, declarative placement; see
[`014-Chrome-Features.md`](014-Chrome-Features.md)); and
`force-fx-stub` for the actions-panel equivalent's stub-then-record
discipline (see [`004-Assertions.md`](004-Assertions.md)). Three
re-frame2 primitives replace eight Storybook addons — the
collapse is what makes Story authorable from a single artefact rather
than a stack of addon configurations.

### Rejected: Throw-on-first-failure assertion semantics

**Rejected.** Storybook's `play` function throws on the first failed
expectation (the JS test-runner integration depends on the throw to
signal failure to the harness). The remainder of the play sequence
never executes; later expectations are not evaluated; the user sees
"first failure" rather than "full picture."

**Why.** Story's `:rf.assert/*` events **record** failures into the
variant's `:assertions` list and continue the play sequence (see
§record-not-throw above for the architectural lock). Storybook's
choice is constrained by JavaScript's async-throw mess and the
test-runner protocol; we have no such constraint. The
record-don't-throw discipline pairs with the four-phase lifecycle (a
play sequence runs to completion against the frame's drain semantics,
per [Spec 002](../../../spec/002-Frames.md)) and the test-runner
adapter post-processes the `:assertions` list into the host test
framework's failure signal. The full picture of "what went wrong" is
strictly more useful than "first failure halts everything."

### Rejected: CSF Factories (JS) — we use EDN-first

**Rejected.** Storybook v10 introduced CSF Factories for type-safe
story-as-test-fixture. CSF still permits inline JSX in `:render`.

**Why.** EDN-first variant bodies are *strictly stronger* than CSF
Factories (per Phase 2 §5.1 #10): they round-trip across the
network, feed the MCP pipeline cleanly, and contain no closures. The
data-only constraint eliminates an entire class of "your story works
but doesn't serialise" bugs. Accepting `:render` fn-slots would
re-import that complexity.

### Rejected: First-party visual-regression service

**Rejected.** Storybook + Chromatic, Percy, Argos. Backstop. Etc.

**Why.** Pixel capture, baseline storage, and PR-review UX are
*services* — they want infrastructure, billing, ops. Story should
not be in that business. The right shape is a hook
(`snapshot-identity`, stable iframes) that downstream services
consume. This is the dominant pattern across modern workshops
(Ladle, RC, Histoire all defer).

### Rejected: Component-co-located fixtures

**Rejected.** RC's file-system-fixture model wires sidebar structure
to file paths.

**Why.** re-frame2's registered artefacts are the canonical
structure mechanism; file-system convention duplicates the registry.
The [Spec 007](../../../spec/007-Stories.md) canonical id grammar
(`:story.<path>/<variant>`) already gives a hierarchical name; the
story-tool's sidebar is built from that namespace graph. File-system
colocation would be a second source of truth.

### Rejected: Statechart visualisation engine

**Rejected (delegated).** Phase 1 §6.8 split: Story ships a one-line
current-state indicator only; the full chart-rendering work lives in
`day8/re-frame2-machines-viz`.

**Why.** Auto-layout for hierarchical statecharts with parallel
regions is specialised work (XState invested years on Stately). The
bundle weight of layout engines (`@xyflow/react`, `d3-hierarchy`,
`elkjs`) shouldn't land on every Story consumer.

### Rejected: Pixel-scrubber UI

**Rejected.** BackstopJS's tactile pixel scrubber is a great UX for
pixel visual regression.

**Why.** Story's data-space scrubber via Xray's epoch panel covers
the same UX *better* for re-frame2 apps — scrub through events with
`app-db` following, not through static pixels. Pixel scrubbing is a
downstream visual-regression-service concern. Story does not host
pixels.

### Rejected: BackstopJS-style baseline storage

**Rejected.** Same rationale as the visual-regression service — services
handle baselines.

### Rejected: First-party SSR rendering pipeline

**Rejected (delegated).** Story exposes `:platforms #{:server
:client}` per variant; the server-side pane uses `re-frame.ssr`
([Spec 011](../../../spec/011-SSR.md)'s artefact). Story doesn't
ship its own JVM render path.

**Why.** SSR is owned by Spec 011 and `day8/re-frame2-ssr`; reusing
that artefact preserves single-source-of-truth for server-render
decisions.

### Rejected: MCP server in-process

**Rejected.** [Spec 007](../../../spec/007-Stories.md) doesn't
mention MCP; Phase 1 §6.1 proposed an external jar. Locked above
(§separate-mcp-jar).

**Why.** stdio + JSON-RPC dependencies are dead weight in a typical
production deploy. Splitting the jar keeps the Story core lean.

### Rejected: Built-in pixel diff under `:test` tag

**Rejected.** A `:test`-tagged variant runs `run-variant` and asserts
on `:assertions` + `:app-db`. It does **not** capture or diff pixels.

**Why.** Pixel diff is downstream. Stories-as-tests are
**state-space** tests — `app-db` reaches the expected state — not
pixel-space tests. This is the
[Spec 007 §Story-as-test-duality](../../../spec/007-Stories.md) lock.

### Rejected: Full Xray reimplementation

**Rejected (delegated).** Story embeds Xray's panels (Xray being the
structural successor to re-frame-10x); it does not own a parallel
implementation. See §xray-embed above.

**Why.** Xray's UX is mature; replicating it would split maintenance
and drift. The right primitive is "Xray is a peer artefact, Story
integrates."

## Open items (deliberate punts)

These were named in the original IMPL-SPEC §13.2 as deliberate
punts. They live closer to the implementation; future implementer
choices are auditable here.

1. **Async-result shape for `run-variant`.** ~~Promise vs.
   `manifold.deferred`~~ — **PICKED + LOCKED:** native `js/Promise`
   on CLJS, `java.util.concurrent.CompletableFuture` on the JVM
   (manifold dropped — no extra dependency), abstracted behind
   `re-frame.story.async`. See `002-Runtime.md` §Programmatic API.

2. **Mode × Variant × Substrate snapshot-identity matrix.** Three
   options: nested hash (substrate is leaf); composite key
   (`[variant-id mode-id substrate]`); or substrate as a separate
   axis with its own hash slot. Stage 3 picks.

3. **Decorator argument shapes per `:kind`.** The three kinds
   (`:hiccup`, `:frame-setup`, `:fx-override`) — Stage 2's Malli
   schema for `reg-decorator` bodies covers per-kind required keys.

4. **Hot-reload semantics for `reg-decorator` re-registration.** If a
   `:hiccup` decorator's `:wrap` closure changes, do all variants
   using it re-render automatically? Reagent's reactive graph handles
   subscription changes; decorator changes need explicit propagation
   (mark variants stale, re-mount).

5. **`:rf.assert/effect-emitted` semantics under `force-fx-stub`.** If
   a variant stubs `:http` and then asserts
   `:rf.assert/effect-emitted :http`, does the assertion pass? The
   fx *is* emitted; the stub just intercepts. Stage 5 clarifies.

6. **MCP protocol version.** Landed at `2025-06-18` per
   [`tools/story-mcp/spec/`](../../story-mcp/spec/) §Wire Protocol.

## Verification

- All seven §rf2-m6tu §6 architectural decisions are documented as
  decided.
- The twelve Phase 2 §5.1 high-confidence ship items appear in
  [`005-SOTA-Features.md`](005-SOTA-Features.md) §v1 ship list.
- The six Phase 2 §5.2 additions appear in v1 or v1.1 with explicit
  rationale.
- The seven Phase 1 §6 questions are addressed above with rationale.
- The Rejected list is concrete and rationale-bearing.
