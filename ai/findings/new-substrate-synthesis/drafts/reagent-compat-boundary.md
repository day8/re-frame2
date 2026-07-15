# DRAFT — The Reagent compatibility boundary contract

> **Status: DRAFT — not merged · 2026-07-12.** Executes the accepted codex2 Finding 7
> per the binding disposition ([09 §codex2 disposition](../09-review-disposition.md),
> rows 7 and 4); answers implementer questions Q56–Q59. Consumers:
> [10-migration-from-reagent.md](../10-migration-from-reagent.md) (step-1 qualification,
> Tier R, mechanics), the live compatibility appendix (§8 below — the contract's
> normative home after the Spec-004 rewrite), the 12 §2 matrix row for `->react`
> ("compat-boundary fixtures, both nesting directions"), W9/W13 CI planning, and the W1
> migrator. Checked-in spec text is cited read-only. Where the suite is genuinely
> unruled, the conservative contract is written and marked **[S6-CONFIRM]** — confirm at
> the S6 migration wave, when both doors are implemented and the boundary fixtures
> exist. House style: `⟨source⟩` provenance tags; British "serialisable".

## 1. Scope: two rendering tiers, one installed adapter, three granularities

**The two tiers.** The **new-UI tier** is `re-frame.ui` — compiled `defview`s under a
`ui` host root. The **frozen Reagent tier** is stock Reagent — Form-1/2/3, the
`reg-view` family, the ratom substrate, the checked-in Spec 006 Reagent adapter —
correct but frozen per the ratified Adapters decision. ⟨08 §5 Adapters, 08 §6⟩

**The v1 adapter-selection law is unchanged.** A process installs exactly one adapter
at boot. Its surviving browser choices are `ui/adapter`, `reagent-adapter/adapter`, and
`uix-adapter/adapter`; every frame and root in that process uses that one choice.
Nothing in this boundary contract adds per-frame or within-frame adapter selection.
The two directions below are ordinary React-value interop (`ui/raw` takes an element;
`ui/->react` returns a component), not a mechanism that installs or routes to a second
adapter. A use case that truly requires two installed adapters remains separate-process
work in v1. UIx is a separately-owned frozen compatibility adapter, primarily governed
by Spec 006/API/Conventions/Ownership; it is not folded into this Reagent-specific
boundary or the Reagent-specific 004A appendix. ⟨checked-in Spec 006 §Adapter selection
at boot / §Single adapter per process; 08 §5 Adapters⟩

**The boundary is a rendering boundary, never a state boundary.** The dataflow layer —
frames, events, subs, fx, machines, schemas, routes, resources — is tier-independent:
one process-global registrar, one frame registry, one epoch stream. Both tiers dispatch
into and subscribe against the same frames. Nothing in this contract moves state across
anything; only *rendering* crosses. ⟨10 Status, 08 §6⟩

**No artifact coupling.** Both doors traffic in plain React values — `ui/raw` takes a
React **element** in; `ui/->react` hands a React **component** out. Neither door makes
`day8/re-frame2-ui` depend on the compat adapter or vice versa; the crossing code lives
in **application** namespaces, and neither door changes the process's installed
adapter. G-12 (`ui` never depends on the compat adapter) holds at the artifact level by
construction. ⟨08 §5 Adapters, 02 §6⟩

**Three supported granularities**, coarsest first — the migration default is to use the
coarsest one that fits:

1. **Sibling roots** (no React nesting). One page runs a legacy Reagent root and a `ui`
   root side by side under the **same process-installed adapter**; they share frames and
   nothing else. No React bridging, no context plumbing — the boundary is the DOM. This
   is the recommended first cut for step-2 migration (convert a whole route/panel mount
   at a time). It is not one adapter per root. ⟨10 mechanics, 06 §2⟩
2. **Inward nesting** — an existing React/Reagent element inside a `ui` tree, via
   `ui/raw` (§2). For legacy widgets (re-com) inside migrated views.
3. **Outward nesting** — a `defview` subtree inside a remaining Reagent parent, via
   `ui/->react` (§3). For migrated leaves/panels inside a legacy shell. `->react` is
   **v1, lands S6 with the migration wave** (delta #2, ruled 2026-07-12; 12 §2 row).

## 2. Direction A — inward: a Reagent subtree inside a `ui` tree (`ui/raw`)

**Mechanism — same-root embedding, no second React root.** The spelling is:

```clojure
[:div.toolbar
 (ui/raw (reagent.core/as-element [legacy-datepicker {:on-pick pick!}]))]
```

`r/as-element` converts legacy hiccup to a React element; `ui/raw` places it in child
position. The legacy components render as ordinary (class) components **inside the
enclosing `ui` root's React tree**. The contract explicitly does **not** create a
nested React root at an inward boundary: a `createRoot` inside a rendered subtree
breaks context propagation, event delegation, and StrictMode/Activity semantics, and
doubles the scheduling surfaces. Doc 10's earlier Tier-R phrase "`ui/raw` + a Reagent
root at the boundary" is superseded by this contract — the *root* stays the `ui` host
root; only the *elements* are Reagent's. ⟨10 Tier R (superseded wording), 02 §6⟩

`as-element` itself is a pure conversion — no registration, no ownership, no reactions
are created at conversion time (Reagent's reactive machinery attaches inside the class
components' own lifecycles) — so the inward door is speculative-render-safe under I-1.
⟨I-1, 03 §3⟩

**Reactivity inside the subtree.** The frozen tier's ratom/reaction machinery runs
untouched: legacy components track their derefs and force-update through Reagent's own
queue. One page therefore runs two render schedulers — Reagent's inside the embedded
subtree, the `ui` commit machinery outside it. That is bounded by the boundary and
stated honestly: the `ui` drain/commit guarantees (all queued writes executed, then at
most one notification per dirty cell in one post-quiescence render batch with
correct-before-paint) apply to `ui` cells only, never to embedded legacy components.
⟨03 §3, 08 §5 Adapters "no parity with `ui` features"⟩

**Frame propagation inward.** The checked-in contract is the anchor: a legacy view
reads the ambient frame only if it is **registered** (`reg-view` attaches the
`:contextType`); a plain fn cannot read provider context and its ambient frame op
raises `:rf.error/no-frame-context` (§4). For registered legacy views inside an inward
boundary, the frame arrives via React context — which requires the compiled substrate's
context tier and the frozen tier's provider to be **the same context object**. The
checked-in precedent already demands exactly this shape: the shared React Context in
`re-frame.adapter.context` exists "so a mixed-substrate app's frame-provider chain
composes across substrates" (checked-in `spec/API.md` §UIx adapter notes). This
contract therefore REQUIRES:

- `day8/re-frame2-ui`'s React-context frame tier binds to the shared
  `re-frame.adapter.context` object, not a fresh context. **[S6-CONFIRM]** (verify at
  implementation; a fresh context object breaks both directions silently).
- Production erasure MUST NOT elide the live frame-context Provider above a `ui/raw`
  site — embedded registered legacy views read it at runtime. **[S6-CONFIRM]** the
  mechanism (always-on provider vs. raw-site-triggered retention).
- Until both are confirmed, the **conservative authoring rule** stands: place an
  explicit frozen-tier `[rf/frame-provider {:frame the-frame-id}]` at the top of the
  embedded hiccup (frame id obtained on the `ui` side — a literal, or `(frame)` ops).
  Redundant-but-harmless once the shared-object rule is confirmed.

⟨checked-in spec/006 §Plain-fn footgun + §Frame-provider via React context, 03 §8⟩

**Callbacks inward.** Fns closed over in the `as-element` hiccup are ordinary closures
(the `ui` compiler sees `(ui/raw expr)` as one opaque dynamic expression — the §3
handler table's foreign-boundary compile error does not reach inside it). They carry no
committed-slot promise. The blessed dispatch bridge is `(ui/dispatch-fn)` created in
the owning `ui` view and passed down as a prop — stable identity, committed-frame
targeting, loud failure when disconnected. Legacy code inside the subtree otherwise
dispatches through its own frozen-tier paths (injected lexical `dispatch` in registered
views, captured ops). ⟨02 §3, 03 §6⟩

**Teardown inward.** React unmount of the boundary site unmounts the legacy class
components; Reagent reactions dispose in `componentWillUnmount` per the frozen
contract; `reagent-adapter/with-resource-lease` releases on unmount as today. No
compat-tier resource survives the boundary unmount. **Activity/presence caveat:** the
frozen tier predates Activity semantics — do not place embedded legacy subtrees under
`ui` Activity-hidden or presence-retained regions until a compat-suite fixture proves
class-component hide/reveal lifecycle behaviour. **[S6-CONFIRM]** (fixture named in
§7). ⟨03 §4, 02 §7⟩

**HMR inward.** Editing legacy code reloads through the frozen tier's existing
(weaker) story: the embedded subtree re-renders when the boundary site next re-renders
(the `ui` HMR generation bump re-renders the site, re-invoking `as-element`; legacy
state preservation then follows plain React reconciliation — same class, state kept).
No `ui`-grade HMR guarantees (hook-signature analysis, site identity, generation
tracking) exist inside the frozen subtree; an edit that remounts the enclosing `ui`
view remounts the legacy subtree and loses its local ratom state. Stated honestly, not
patched. ⟨03 §10⟩

**SSR inward.** A `ui/raw` site is opaque to the JVM emitter: on SSR paths it requires
the `client-only` sibling fallback (the existing 02 §6 rule — the fallback must be
capability-free per 06 §3). Mixed-tier server rendering inside one root is NOT
supported: a legacy view that needs server output stays on the frozen tier's own SSR
path (the checked-in render-tree/hiccup contract) at whole-root granularity — i.e.
granularity 1, sibling roots. ⟨02 §6, 06 §1, 06 §3⟩

## 3. Direction B — outward: a `defview` subtree inside a Reagent parent (`ui/->react`)

**Availability.** `ui/->react` is **v1** (delta #2, ruled 2026-07-12 under Mike's
delegation; the original wave-2 premise was factually wrong — guide 02 teaches it) and
**lands S6** with the migration wave. The inward door (§2) rides the S3
foreign-boundary work; both doors exist before the repo migration needs them.
⟨09 codex2 row 7, 12 §2⟩

**Mechanism.** `(ui/->react view)` returns a React component. Contract points:

- **Memoised per view id — the identical component object across calls and across HMR
  generations.** `->react` exports the view's *stable shell* (03 §10), never a per-call
  wrapper; otherwise every legacy parent re-render would remount the exported subtree.
  Repeated `(ui/->react product-card)` calls return the same object. ⟨03 §10⟩
- **No new React root; no root manifest; no host preflight.** The exported subtree
  renders inside the legacy root, which owns its lifecycle. There is no `ui` root
  descriptor, no hydration, no ENSURE — **frame creation stays with the host app's
  boot/event infrastructure** (its existing `make-frame`/`reg-frame` boot). An exported
  view scopes and resolves frames; it never creates them. (`frame-root` cannot appear
  inside a `defview` body anyway — it is a root-form top-region form, 03 §8 — so
  exports are scope-only by construction.) ⟨03 §8, 06 §2⟩
- **Frame acquisition.** The exported view resolves its frame by the compiled
  substrate's ordinary chain: explicit pin → dynamic binding → React context → loud
  `:rf.error/no-frame-context`. Under the §2 shared-context-object rule, a frozen-tier
  `[rf/frame-provider {:frame …}]` (SCOPE) or `[rf/frame-root {:id …}]` (ENSURE) above the
  exported component in the legacy tree scopes it with **zero extra spelling**. With no
  frame boundary above it, the exported view fails loud — never a silent default. **[S6-CONFIRM]**
  rides the same shared-object confirmation as §2. ⟨rewrite Abstract pt 2, checked-in
  spec/006 §Frame-provider via React context⟩
- **Ownership and scheduling.** The exported subtree participates fully in the `ui`
  commit/drain machinery of its frame: ViewCells attach at commit, every queued write
  epoch executes, and each dirty cell is notified once in the post-quiescence batch,
  correct-before-paint. Multiple exported subtrees under one legacy root are independent
  `ui` subtrees; drain batching spans them normally. The legacy parent's
  Reagent scheduling coexists outside them. ⟨03 §2–§3⟩

**Props across the outward boundary.** The exported component reads props per the
view's declared props ABI (deterministic slot names preserving namespace+name, 02 §1).
Consequences, stated so nobody discovers them in production:

- From a **JS codebase**: write the ABI slot names directly — the primary `->react`
  use case (guide 02).
- From **Reagent hiccup**: `[:> Exported {…}]` runs Reagent's `convert-prop-value`,
  which **camelises** keys (`:on-select` → `onSelect`) — that does not match the ABI's
  namespace+name-preserving encoding. Blessed spellings: (a) single-segment unqualified
  prop names (`[:> Exported {:product p}]`), where the two encodings coincide
  **[S6-CONFIRM** — the identity claim rides the S1 props-ABI freeze**]**; (b) the
  frozen tier's conversion-bypassing interop head `[:r> Exported #js {…ABI slots…}]`
  (the same escape the checked-in `rf/frame-provider` mount uses); (c) rename
  hyphenated/namespaced props at the boundary. Dev builds diagnose a received slot that
  matches no declared slot but whose de-camelisation does
  (`:rf.warning/compat-camelised-prop` — proposed id; **[S6-CONFIRM]** + a Spec 009
  catalogue row at promotion, one-catalogue rule). ⟨02 §1, checked-in spec/006
  §Frame-provider via React context (`:r>` precedent)⟩
- **Fn props from the legacy parent** are foreign values: on DOM sites inside the view
  they classify at runtime as bare handlers (legal — known native event property); at
  foreign boundaries inside the view the explicit forms apply as usual. EDN props pass
  through by value; `rf=` memoisation applies normally. ⟨02 §3⟩

**Teardown outward.** Legacy-parent unmount runs the exported subtree's ordinary
disconnect: leases and observation targets release synchronously per the commit
contract; nothing leaks into the legacy root. A frame destroyed under a still-mounted
exported view is the standard loud path (`:rf.error/frame-destroyed`); `dispatch-fn`
fails in every non-connected state. ⟨03 §3–§4, 03 §11⟩

**HMR outward.** Editing the `defview` source takes the full `ui` HMR path (stable
shell, hook-signature hash, site identity) — the exported component IS the shell, so
subtree state survives both legacy-parent re-renders and same-signature edits. Editing
the legacy parent re-renders per the frozen tier's existing behaviour; the stable
component object keeps React from remounting the exported subtree. ⟨03 §10⟩

**SSR outward — unsupported in v1.** The frozen tier's server path renders hiccup on
the JVM; an exported React component is not renderable there. On legacy server-rendered
pages an exported subtree must be excluded from server markup (render a placeholder
container server-side; the subtree renders client-side after mount). The frozen tier
has no `ui/client-only`, so this is an application convention, not a checked form;
accept the client-side pop-in or migrate that page's root to a `ui` root (granularity
1) to get real SSR. Stated honestly; no better story is promised for v1. ⟨06 §1, 06 §3⟩

## 4. The plain-fn contract: what completes step 1 unchanged (Q57)

The checked-in constraint, verbatim in effect: a plain Reagent fn (not registered via
`reg-view`) cannot read the closest enclosing `frame-provider` because it lacks the
`:contextType` that `reg-view` attaches; its ambient `(rf/subscribe …)` /
`(rf/dispatch …)` resolves to nil and raises `:rf.error/no-frame-context` — no silent
`:rf/default` fall-through. This is the **checked-in re-frame2 frame-resolution
contract**, not a new constraint introduced by the substrate work; migration step 1
inherits it. ⟨checked-in spec/006 §Plain-fn footgun⟩

**Classes that complete step 1 unchanged:**

1. **Registered views** — `reg-view` / `reg-view*` — the `:contextType` machinery gives
   ambient reads, and `reg-view` additionally injects lexical `dispatch`/`subscribe`
   bound at render, so handler closures keep working at interaction time.
2. **Pure presentational plain fns** — props in, hiccup out; no ambient frame op.
   Includes fns that receive subscription *values*, handler fns, or captured ops as
   arguments. In a well-factored codebase this is most plain fns.
3. **Explicit-frame plain fns** — the `(subscribe q {:frame f})` / `(dispatch e
   {:frame f})` opts forms for a statically known frame; fns using a passed-in
   `(rf/capture-frame)` ops bundle; `with-frame` on non-deferred call paths (the
   checked-in enumeration: `reg-view`, `with-frame`, a captured `capture-frame`, the
   `{:frame …}` opt).

**The class that does NOT complete step 1 unchanged:** plain (unregistered) fns making
**ambient** frame-scoped calls — a render-time `@(rf/subscribe [:q])` or a
`#(rf/dispatch [:ev])` closure handed to the DOM. Two failure modes, one deferred:
render-time derefs fail loudly at first render (and the frozen tier's dev-time plain-fn
warning surfaces them); dispatch closures fail **at interaction time** — grep for them,
do not discover them by clicking.

**The prescribed rewrite** (in preference order; the W1 migrator and the step-1
checklist flag every site):

1. **Register the fn** — wrap the `defn` with `reg-view` (defn-shape; a header-level
   change, body untouched) or `reg-view*` for computed ids / Form-3. This is THE
   step-1 adjustment for view-shaped fns.
2. **Hoist the ambient op** to the nearest registered ancestor and pass the
   subscription value / injected `dispatch` / captured ops down as arguments — for
   helper fns that are not views.
3. **Make the frame explicit** — the `{:frame f}` opts form where the frame id is
   statically known (single-frame apps: the one id from the boot `reg-frame`).

⟨checked-in spec/006 §Plain-fn footgun, 10 step 1, 09 codex2 row 7⟩

## 5. Root ownership and teardown — the consolidated rules

1. **One React root owns any mixed tree.** Inward: the `ui` host root owns everything,
   embedded legacy components included. Outward: the legacy root owns everything,
   exported `ui` subtrees included. Nested React roots are not part of this contract.
2. **Sibling co-mounted roots are each owned by their creator** — the legacy root by
   the app's existing mount code, the `ui` root by `ui/mount`/host fns — and torn down
   by their owner. They share frames only.
3. **Frames are owned by neither tier and never by a boundary.** Creation/destruction
   lives in boot/event infrastructure (host preflight ENSURE on `ui` roots; the app's
   existing boot on legacy roots). A boundary crossing never creates, re-seeds, or
   destroys a frame; unmounting either side of a boundary leaves frames live.
4. **Teardown is total on each side's own terms.** `ui` cells release ownership
   synchronously at disconnect (commit contract); frozen-tier reactions/leases dispose
   per the checked-in adapter contract. The compat suite carries a boundary leak
   fixture in both directions (§7).

⟨03 §3–§4, 03 §8, 06 §2⟩

## 6. SSR and HMR limits — the honest table

| | Inward (`ui/raw`) | Outward (`->react`) | Sibling roots |
|---|---|---|---|
| **SSR** | `client-only` sibling fallback mandatory; no mixed-tier JVM render | **Unsupported v1** — placeholder container server-side, client-side render after mount | Full: each root uses its own tier's SSR path |
| **HMR** | `ui` guarantees outside the boundary only; legacy subtree = frozen-tier reload behaviour; enclosing-view remount loses legacy local state | Full `ui` HMR inside the export (stable shell); legacy parent edits = frozen-tier behaviour | Full per root, each on its own tier's story |
| **Activity/presence** | Do not place legacy subtrees under hidden/retained regions pending the §7 fixture **[S6-CONFIRM]** | `ui` semantics apply normally | n/a |

⟨02 §6, 03 §10, 06 §1–§3⟩

## 7. The retained CI surface — three suites, one Reagent smoke here (Q59)

W9's shared-matrix collapse and W13's compatibility freezes name **three different
causal suites**. The plan names all three explicitly; no reading of "all legacy
coverage is retired" is correct. ⟨11 W9/W13, 09 codex2 row 7⟩

**Suite 1 — the new-UI conformance suite** (`ui-conformance`). The `re-frame.ui`
contract surface: the Tier-1 parity corpus, the Tier-3 ownership/commit walkthrough
(07 §3), the HMR matrix, and the G-roster gates. This becomes the forward conformance
owner instead of treating the legacy shared parameterisation as product parity. Built
from S1 (`ui.test` critical path) onward. ⟨07 §1–§3, 11 W9⟩

**Suite 2 — the frozen-Reagent compatibility suite** (`reagent-compat`). Pinned once at
the freeze, then maintenance-only (bugs fixed when they block migration):

- the checked-in Spec 006 adapter-contract fixtures against `reagent-adapter/adapter`
  (state container, subscribe-container, derived values, flush, dispose);
- `reg-view`/`reg-view*` registration + frame-context resolution fixtures, **including
  the plain-fn footgun fixture** (`:rf.error/no-frame-context` raised, no silent
  default);
- distinct `frame-provider` SCOPE fixtures and `frame-root` ENSURE fixtures (each
  rejecting the other's key — `frame-provider` given `:id`, `frame-root` given `:frame`);
  `with-resource-lease`; the sub-override subscribe seam (Story's `:sub-overrides` rung
  keeps working on the frozen tier);
- **new boundary fixtures, both directions** (the 12 §2 `->react` row's named
  fixtures): inward raw-embedding with frame scoping + teardown/leak check; outward
  export under a legacy provider with frame acquisition, teardown/leak check, and the
  stable-component-object-across-HMR assertion; the Activity/class-component lifecycle
  probe (§2 **[S6-CONFIRM]**).

**Suite 3 — the frozen-UIx compatibility suite** (`uix-compat`). Separately owned by
Spec 006/API/Conventions/Ownership, pinned from the existing UIx adapter-contract and
UIx-specific CLJS coverage at the S7 freeze, then maintenance-only. It keeps the UIx
classpath probe and exactly one UIx mount → subscribe → dispatch → re-render browser
smoke. It is not a feature-parity suite and gains no new `re-frame.ui` capabilities.

**Plus exactly one Reagent browser smoke owned here.** The Reagent adapter smoke
(`implementation/adapters/reagent/testbed/spec.cjs` — mount + dispatch + assert)
survives. The UIx smoke survives under `uix-compat`; the Helix smoke deletes with its
adapter. Net after adding the new-UI smoke: exactly three browser smokes remain—one
each for `re-frame.ui`, Reagent, and UIx. ⟨08 §5 Adapters, 11 W13⟩

## 8. The normative home after the Spec-004 rewrite: a live compatibility appendix (Q58)

Per the binding disposition (row 4): frozen stock Reagent gets a **live compatibility
appendix** — an addressable current contract, NOT git-history/tag provenance. ⟨09
codex2 row 4⟩

**Name:** **`spec/004A-Reagent-Compat.md` — "Spec 004A — The stock-Reagent
compatibility tier (live appendix to Spec 004)."** It sorts beside the rewritten
`spec/004-Views.md`, which points at it from §Removed forms; `spec/Ownership.md`'s
re-scoped Reagent row names it as the owning spec. It lands **with the S7
compatibility-freeze / Helix-and-slim deletion wave** (until then the pre-rewrite
004/006 text still governs the shipping adapters via
the [TRANSITION] markers). Contents: the freeze rules (correct-but-frozen, no new
capabilities, no `ui`-feature parity, sunset unpromised/demand-reviewed post-1.0), the
preserved normative sections for Form-1/2/3 + the `reg-view` family + the Reagent
adapter + frame-context resolution (carried forward from the pre-rewrite revisions as
live text, with the git tag as provenance only), **this boundary contract promoted into
it**, and the three-suite CI surface (§7).

**API/facade export rows the appendix retains** (moved under a
`v1 (frozen — compat tier)` status, not deleted; this supersedes the rewrite draft's
ripple instructions to *remove* them — they relocate):

| Row | Kind / tier today | Retained because |
|---|---|---|
| `re-frame.core/reg-view` | M · front-porch · 004 | the tier's registration surface; the step-1 prescribed rewrite depends on it |
| `re-frame.core/reg-view*` | Fn · advanced · 004 | computed ids, Form-3, library-generated views |
| `re-frame.core/view` | Fn · advanced · 001/004 | legacy runtime lookup of registered render-fns (distinct from wave-2 `ui/view`) |
| `re-frame.core/frame-provider` | Component (Reagent) · front-porch · 002 | SCOPE-only frame scoping for the tier AND both boundary directions (§2/§3); rejects `:id` (names `frame-root`) |
| `re-frame.core/frame-root` | Component (Reagent) · front-porch · 002 | ENSURE the tier's named frame — create-if-absent / reuse-no-reseed / no destroy-on-unmount; rejects `:frame` (names `frame-provider`); rf2-nyea0r split |
| `reagent-adapter/adapter` | Var · adapter · 006 | the tier's 11-key adapter map |
| `reagent-adapter/flush-views!` | Fn · adapter · 006/008 | the tier's test flush |
| `reagent-adapter/set-hiccup-emitter!` | Fn · adapter · 006/011 | the tier's SSR seam |
| `reagent-adapter/with-resource-lease` | Component · adapter · 006 | lease lifecycle in legacy trees |
| `expand-reg-view` / `parse-reg-view-args` | `^:no-doc` carve-outs | live while the macro lives (unrowed, as today) |

Conventions rows retained (re-scoped "compat tier", not removed): the facade export
list entries for `reg-view`/`reg-view*`; the `*`-suffix pair table's `reg-view` row +
the asymmetry footnote (re-worded, not deleted — the pair still exists, in the
appendix); the `reg-view` auto-id derivation section (also re-homed as the `defview`
id rule — the same derivation serves both, stated once each side). The UIx adapter rows
are **retained in their existing Spec 006/API/Conventions/Ownership homes**, not moved
into 004A; the Helix rows delete with that adapter. No new
capabilities are ever added to a retained row (the freeze rule); any new export
touching the compat tier still passes the standing diff-time facade-classification
rule. ⟨checked-in spec/API.md §Registration + §View ergonomics + §Reagent adapter,
checked-in spec/Conventions.md, 09 codex2 row 4⟩

**Required cross-edits noted for their owners** (not made here): the rewrite draft's
"preserved in git history and by tag" wording and its Conventions/API ripple rows
685–687/698 ("remove `reg-view`…") take the row-4 correction — *move to 004A*, not
remove; the rewrite's §Removed forms gains the 004A pointer. Owned by the rewrite
fold-in per the disposition. ⟨09 codex2 rows 4–5⟩

## 9. [S6-CONFIRM] roster

1. **Shared context object** — `day8/re-frame2-ui`'s React-context frame tier binds to
   `re-frame.adapter.context` (load-bearing for both directions; §2, §3).
2. **Provider retention under erasure** — production builds keep the live frame
   Provider above `ui/raw` sites; mechanism (always-on vs. site-triggered) (§2).
3. **Activity/class-component lifecycle** — fixture proving frozen-tier behaviour under
   `ui` Activity/presence retention before lifting the §2 authoring restriction (§2, §6).
4. **Outward prop-encoding identity claim** — single-segment unqualified names encode
   identically on both sides of `[:> Exported {…}]`; rides the S1 props-ABI freeze (§3).
5. **`:rf.warning/compat-camelised-prop`** — proposed dev diagnostic + its Spec 009
   catalogue row (one-catalogue rule) (§3).
6. **Combined flush helper** — whether boundary fixtures need a single flush idiom
   spanning `ui.test/flush!` and `reagent-adapter/flush-views!`, or the two-call
   ordering the compat suite pins suffices (§7).

## Q56–Q59 answer map

- **Q56** (two co-mount directions; who owns frame context, roots, teardown, HMR, SSR):
  §§2–3 (per direction), §5 (ownership/teardown), §6 (SSR/HMR).
- **Q57** (which plain fns complete step 1 unchanged; prescribed rewrite): §4.
- **Q58** (live normative home + retained facade/API/Conventions rows): §8.
- **Q59** (deleted vs retained CI): §7 — deleted: Helix/slim-only coverage and the
  legacy cross-adapter parity framing; retained: `ui-conformance` (new),
  `reagent-compat` (pinned), `uix-compat` (pinned), and one browser smoke for each.
