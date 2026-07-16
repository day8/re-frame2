# 10 — Migrating from Reagent: how mechanical is it?

> **The headline: migration is TWO independent steps, and step 1
> doesn't rewrite your views.** Stock Reagent is a supported (frozen) compatibility tier,
> so a v1 Reagent/re-com app first moves its **dataflow** to re-frame2 — events, subs,
> a `frame-root` mount — keeping registered views and every re-com widget as they are
> (one enumerated adjustment class: plain unregistered fns that use *ambient* frame ops —
> see §Step 1, precisely), and immediately gains Xray, epochs/time-travel, Story,
> schemas, machines, and resources. The view migration below is **step 2**: per-subtree,
> on your schedule, with the migrator. re-com widgets are the last movers (embedded
> Reagent subtrees / foreign heads until the `ui`-native answer lands — *[2026-07-16:
> now a directed program, epic `rf2-6ajm6z`; see `drafts/component-library-readiness.md`;
> the compat-embedding mechanics below stay the migration-time answer unchanged]*).

**One adapter still boots the process.** Incremental/co-mount below describes React
rendering boundaries, not adapter selection. The process installs exactly one of
`ui/adapter`, `reagent-adapter/adapter`, or `uix-adapter/adapter`; all frames, roots,
and subtrees use that choice. `ui/raw` and `ui/->react` pass ordinary React values and
never install a second adapter. UIx is a separate frozen compatibility boot choice,
not part of this Reagent-specific migration contract.

**Status:** final · 2026-07-12 (step-1 claim qualified to the checked-in Spec 006
plain-fn contract; boundary mechanics split out to
[drafts/reagent-compat-boundary.md](drafts/reagent-compat-boundary.md) — per the 09
§codex2 disposition, row 7). Scope: a re-frame(2) app whose views are Reagent —
Form-1/Form-2, ratoms, `with-let`, `subscribe`+deref, closure handlers — moving to this
library. The dataflow layer (events, subs, fx, machines, schemas, routes, resources)
**does not change at all**; migration is a view-tier rewrite.

## The headline answer

**~80–90% of a typical codebase migrates mechanically** — by rules a script (or an
AI agent with a checklist) applies without judgment. The remainder splits into **local
transformations with a decision** (~10–15%: Form-2 state, lifecycle methods) and **a
handful of genuine redesigns** (~1–5%: reaction/cursor cleverness, cross-frame tricks,
render-phase side effects — most of which were bugs waiting to happen and are the
*reason* this library exists). Old and new rendering trees co-mount at explicit
boundaries under the same process-installed adapter, so migration is incremental per
view subtree, not big-bang and never per-subtree adapter selection. Both nesting directions are
specified in the compatibility-boundary contract
([drafts/reagent-compat-boundary.md](drafts/reagent-compat-boundary.md)): legacy
subtrees inside `ui` trees via `ui/raw`, and migrated `defview` subtrees inside a
remaining Reagent shell via `ui/->react` — the outward bridge is **v1 and lands S6
with the migration wave** (delta #2, ruled 2026-07-12; 12 §2).

## Step 1, precisely: what "views unchanged" means

Step 1 inherits the **checked-in** re-frame2 frame-resolution contract (Spec 006
§Plain-fn footgun) — this is not a constraint the substrate work adds. A plain Reagent
fn (not registered via `reg-view`) cannot read the enclosing `frame-provider` context
(it lacks the `:contextType` that `reg-view` attaches), so its *ambient*
`(rf/subscribe …)` / `(rf/dispatch …)` raises `:rf.error/no-frame-context` — there is
no silent `:rf/default` fall-through.

**Unchanged through step 1:** registered views (`reg-view`/`reg-view*` — ambient reads
work, and `reg-view`'s injected lexical `dispatch`/`subscribe` keeps handler closures
working); pure presentational plain fns (props in, hiccup out — including fns handed
subscription values, handler fns, or captured ops as arguments); and explicit-frame
plain fns (the `{:frame f}` opts forms, a passed-in `(rf/capture-frame)` bundle,
`with-frame` on non-deferred paths).

**Needs the step-1 adjustment:** plain unregistered fns making *ambient* frame-scoped
calls. Render-time derefs fail loudly at first render (the dev-time plain-fn warning
also surfaces them); dispatch closures fail **at interaction time** — grep for them,
don't discover them by clicking. The prescribed rewrite, in preference order (the
migrator and the step-1 checklist flag every site):

1. **Register the fn** — wrap the `defn` with `reg-view` (defn-shape; a header-level
   change, body untouched), or `reg-view*` for computed ids / Form-3.
2. **Hoist the ambient op** to the nearest registered ancestor; pass the subscription
   value / injected `dispatch` / captured ops down as arguments (helper fns that are
   not views).
3. **Make the frame explicit** — `(subscribe q {:frame f})` / `(dispatch e {:frame f})`
   where the frame id is statically known.

For a v1 app this coincides with work the dataflow move requires anyway (v1's implicit
global app-db becomes a named frame); for an existing re-frame2 app it is already done.
Everything else in step 1 — events, subs, fx, the mount — is as the headline says.

## Tier M — mechanical (scriptable rules)

| Reagent | Here | Rule |
|---|---|---|
| `(defn my-view [a b] [:div …])` (Form-1) | `(ui/defview my-view [{:keys [a b]}] [:div …])` | wrap positional args into one named map; update call sites `[my-view a b]` → `[my-view {:a a :b b}]` |
| `@(subscribe [:q])` | `(sub [:q])` | drop the deref, rename |
| `(dispatch [:ev x])` in a handler closure | `{:on-click [:ev x]}` | when the closure body is exactly a dispatch of a literal vector — the overwhelmingly common case — lift the vector out |
| `#(dispatch [:typed (-> % .-target .-value)])` | `[:typed :rf.ui/value]` | the value/checked/key extraction patterns map 1:1 onto placeholders |
| hiccup: tags, `:div.cls#id`, `:style` maps, `:class` vectors | identical | none — this is the point of keeping hiccup |
| `(for … ^{:key k} [child …])` / `:key` in meta | `{:key k}` in props | move key from metadata to the props map |
| `[:> ReactComp props]` / `adapt-react-class` | `[ReactComp props]` | foreign heads are direct; callbacks pick a form from the 02 §3 table |
| `reagent.core/atom` used as *app state* | it was already wrong in re-frame; move to app-db | flagged by grep; the fix is the standard re-frame discipline |
| `r/render` / `rdom/render` mount | `(ui/mount [ui/frame-root {:id … :initial-events […]}] el)` | one-time, per root |

A migration tool (or the `re-frame-migration` skill pattern this repo already has) can do
all of the above from the AST, flagging anything it can't prove. Two rules need care but
stay mechanical: positional→map argument conversion must update every call site in the
same change, and dispatch-lifting must verify the closure had no other body.

## Tier D — local transformation, human decision (minutes per site)

- **Form-2 / `with-let` local state.** `(let [open? (r/atom false)] (fn [] …))` becomes
  `(let [[open? set-open!] (local false)] …)` — mechanical *if* the atom is genuinely
  ephemeral UI state. The decision: does this state have product meaning? Then it goes to
  app-db instead (the doctrine, guide 03). A migrator can convert and *flag* for the
  human call.
- **Lifecycle methods / Form-3.** `:component-did-mount` doing DOM/library work →
  `(effect :connect …)` or `(effect [deps] …)`. Doing *domain* work ("mark viewed") →
  a route/domain event (the `:on-mount` option deliberately doesn't exist — 02 §1).
  Class components with `:should-component-update` → delete it; memo-by-default covers
  it.
- **`r/track` / derived ratoms.** Become registered subs (`reg-sub`) — usually a
  copy-paste of the compute fn into the dataflow layer, where it gains caching and Xray
  visibility.
- **Handler closures that also do local work** (`#(do (set-open! false) (dispatch […]))`)
  — stay as bare fns on DOM elements (legal, = `ui/handler` shorthand) or split: local
  work stays a fn, intent becomes a vector on the natural element.
- **`reagent.dom.server/render-to-string`** → the JVM emitter path, with the 06 §1 subset
  check (views using refs/effects need `client-only`/restructure for SSR paths).

## Tier R — redesign (rare, and usually a latent bug being surfaced)

- **Reactions/cursors/`track!` driving side effects** — render-phase effects are
  compile-errors here; restructure as events/effects. Every one of these was a
  concurrency hazard in Reagent too.
- **Ratoms shared between components as a side-channel store** — app-db or props; the
  substrate refuses a second state model on purpose.
- **Dynamic tag heads / runtime-assembled hiccup** — bind attrs, split branches, or the
  `re-frame.ui.data` interpreter for genuinely data-driven UI (CMS trees).
- **Render-phase `subscribe` with side-effecting sub bodies** — subs are pure here; the
  effectful part moves to leases/events (guide 03).
- **Third-party Reagent wrapper libs** (re-com etc.) — per-library: embed the old tree
  under `ui/raw` (same-root, via `r/as-element` — no second React root; frame scoping,
  teardown, and the SSR `client-only` fallback per the boundary contract,
  [drafts/reagent-compat-boundary.md](drafts/reagent-compat-boundary.md) §2) until a
  native equivalent exists.

## What migrating buys, view by view

Each converted view immediately gains: memo-by-default (no `sCU` folklore), the causes
timeline in Xray, static interaction surface (buttons say what they dispatch), Tier-1
headless tests (assert the event vector — no DOM), HMR with preserved state, and the
compiled render path (no interpretation cost). The incremental co-mount story means the
payoff arrives per-subtree, not at the end.

## Suggested mechanics

1. Run the migrator over one leaf namespace; review its M-tier rewrites and D-tier flags.
2. Convert the subtree's mount to a co-mount boundary — coarsest granularity that fits
   (sibling roots first; nested `ui/raw` / `ui/->react` for genuinely interleaved UI —
   the three granularities and their ownership/teardown rules are the boundary
   contract's §1/§5); run the app (HMR makes this loop fast) + Tier-1 tests. Note the
   outward direction (`ui/->react`, a migrated subtree inside a remaining Reagent
   shell) becomes available at S6 with the migration wave. Do not call `rf/init!` at
   the boundary; the process's one boot adapter remains in force.
3. Work rootward; convert shared components last (their call sites are already map-args
   by then).
4. The dataflow layer, tests for it, and all tooling keep working throughout — nothing in
   events/subs/fx changes.

**Verdict: mechanical enough to script for the great majority, incremental by
construction, with the non-mechanical residue concentrated exactly where Reagent was
letting apps do something unsound.** The migrator tool itself is a Stage-6 deliverable
(08 §2) and the repo's own examples/testbeds migration is its first consumer.
