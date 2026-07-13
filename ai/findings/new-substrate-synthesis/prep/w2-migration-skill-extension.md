# Reagent → `re-frame.ui` — the view-tier migration path (W2 graft draft)

> **Status: prep draft · 2026-07-13 · NOT installed.** Reference content for W2
> (Stage 5–6) to graft into `skills/re-frame-migration/` (likely as
> `references/reagent-to-ui.md` + a SKILL.md routing paragraph); W2 also assigns this
> path's rule ids in the MIGRATION.md corpus (cardinal rule 1: every rewrite cites an
> id — pre-graft, the rows below anchor to W1's migrator-table ids, MIG-nn; skill-drift
> gates ride W6, not W2). Anything below marked **STAGED:n**
> depends on an unshipped stage and MUST NOT be taught as current until it lands.
> Sources: `ai/findings/new-substrate-synthesis/10-migration-from-reagent.md` (the
> authoritative path), `ai/findings/new-substrate-synthesis/drafts/reagent-compat-boundary.md`
> (boundary mechanics), `prep/w1-migrator-rule-table.md` (the migrator rule table,
> MIG-01…34 — **normative on rewrite mechanics**; the tables below anchor to it),
> `spec/004-Views.md` §Removed forms + [TRANSITION],
> `ai/findings/new-substrate-synthesis/skill/SKILL.md` (the sibling `re-frame2-ui`
> authoring skill — target-form semantics live THERE; this page teaches only the
> migration delta).

## When this path activates

This is **step 2** of the two-step story. Step 1 — the main body of this skill — moved
the **dataflow** to re-frame2: events, subs, fx, a frame + `init!` boot, with views left
as they were on the **frozen stock-Reagent compatibility tier** (the one enumerated
step-1 adjustment being M-11's subscribing plain fns). Step 2 rewrites the **view tier**
to `re-frame.ui` (`defview`, compiled templates) — per subtree, on the author's
schedule, never big-bang. Activate when:

- the project is already on `day8/re-frame2` + the Reagent adapter (step 1 done, or it
  was always a v2 app), **and**
- the author asks to move views to `re-frame.ui` (`defview`, "compiled views",
  `ui/mount`) — for the whole app or a named subtree.

Do NOT route here for: the v1→v2 core migration (the main phases), authoring new `ui`
views on an already-migrated tree (the `re-frame2-ui` skill), or live-app inspection
(`re-frame2-pair`). The frozen tier is *supported* — correct-but-frozen, contract-suite
+ one smoke in CI, no new capabilities (spec/004 §Removed forms; its live home is
`spec/004A-Reagent-Compat.md` at the S7 deletion wave, [TRANSITION] markers until then).
Spec 004 mandates the frozen forms are "taught on exactly one migration page" — that
page is the docs migration page (W3; 08 §6 "one migration doc page"), so this reference
never re-teaches the frozen forms: it teaches only the rewrite away from them and the
boundary spellings. There is no deadline pressure: re-com and other third-party
Reagent widget libraries are the **last movers** and may stay embedded indefinitely.

**The mental model.** This is the strangler-fig migration you know from React
class→function-component codemods: a mechanical pass does the bulk, a human decides the
state-placement questions, and both worlds run in one app until the old one is empty.
Doc 10's estimated split for a typical codebase: **~80–90% mechanical (Type A — doc
10's Tier M)**, ~10–15% local transformation with a decision (Type B — Tier D), ~1–5%
genuine redesign (Tier R; the migrator flags in doc 10's M/D/R vocabulary) — and the
redesign residue concentrates exactly where Reagent was letting the app do something
unsound. The dataflow layer (events, subs, fx, machines, schemas, routes, resources)
**needs no rewrite** in step 2 — existing registrations are untouched; the only dataflow
additions are the ones step 2's own decisions create (a Form-2 product-state hoist to
app-db, an `r/track`→`reg-sub` move).

All five cardinal rules of this skill carry over unchanged — MIGRATION.md-cited
rewrites (this path's corpus ids are assigned at the W2 graft; until then cite this
page's rows via their W1 MIG anchors — W1 wins on mechanics), Type A
announced-then-applied, Type B asked-first, smallest correct
diff, the author runs compiles/tests/smokes. A
dedicated migrator tool automates the Type A table below **[STAGED:6 — W1: build +
first repo consumer S6]**; until it ships, apply the rules by grep + hand-edit,
sweep-announced as usual.

## Co-mounting: both substrates in one app

The boundary is a **rendering boundary, never a state boundary**: both tiers dispatch
into and subscribe against the same frames, one registrar, one epoch stream. Nothing
about co-mounting moves state. Three granularities, coarsest first — always take the
coarsest that fits:

1. **Sibling roots** (the recommended first cut). The legacy Reagent root and a
   `ui/mount` root live side by side on one page, sharing frames and nothing else — the
   boundary is the DOM. Convert a whole route/panel mount at a time.
2. **Inward nesting** — a legacy Reagent element inside a `ui` tree:
   `(ui/raw (reagent.core/as-element [legacy-widget {…}]))`. Same React root (never a
   nested `createRoot`). For legacy widgets (re-com) inside migrated views.
   **Conservative frame rule:** put an explicit frozen-tier
   `[rf/frame-provider {:frame the-frame-id}]` at the top of the embedded hiccup —
   ambient context propagation across the boundary is confirmed at S6
   **[STAGED:6 confirm]**; the explicit provider is redundant-but-harmless after.
   **Callbacks into the embedded hiccup** are ordinary closures — the compiler treats
   `ui/raw`'s argument as one opaque expression, so the foreign-boundary handler rules
   don't reach inside it; the blessed dispatch bridge is a `(ui/dispatch-fn)`
   **[STAGED:3]** made in the owning view and passed down as a prop (stable identity,
   committed-frame targeting, loud when disconnected) — pre-S3 the embedded subtree
   keeps its own step-1 dispatch spellings (registered legacy views / explicit
   `{:frame f}`) rather than parent-made closures.
3. **Outward nesting** — `(ui/->react a-defview)` hands a migrated subtree back to a
   remaining Reagent shell as a stable React component **[STAGED:6]**. Until S6 lands,
   convert only subtrees that own a whole mount (granularity 1) so the outward door
   isn't needed early; once it lands, panels can migrate inside an unconverted shell.

Ownership is simple: one React root owns any mixed tree; sibling roots are each torn
down by their creator; frames are owned by neither tier — a boundary crossing never
creates, re-seeds, or destroys a frame. The boot install is likewise untouched
mid-migration: keep the step-1 Reagent-adapter `rf/init!` while any compat-tier
subtree still renders through it; the once-per-app swap to `(rf/init! ui/adapter)`
*(MIG-33)* **[STAGED:2 — `ui/adapter` not yet exported]** waits until every root on
the page is converted, a human confirm (the tool can't know the page's root
inventory).

**Suggested loop per subtree** (doc 10 §Suggested mechanics): pick a leaf namespace →
**gate first** (W1 §Ordering): check each candidate view against the Type B and redesign
lists *before* touching its body — the unit of migration is the whole view, never half a
body, so a hit with no shipped answer (pre-S3: Form-2 state, lifecycle, unsplit or
guarded handlers, payload-forwarding fn props; unruled at ANY stage: `rf/route-link`
heads (MIG-32), runtime-built markup helpers (MIG-30), `capture-frame` in a view body
(MIG-31)) parks that view on the compat tier, and the answerable hits are resolved with
the author now → apply the Type A table to the views that pass (migrator when shipped) →
convert its mount to a co-mount boundary → verify (below) → work rootward.
**Pre-S6 the unit you pick must own a whole mount** (sibling roots, granularity 1): a
render-tree leaf still nested inside a Reagent parent has no way back to that parent
until the outward door `ui/->react` lands (S6), so early on you migrate route/panel by
route/panel — "rootward" means leaf→root *within* each converted mount, not fine-grained
nested-leaf conversion across the app. Convert shared components **last** — by then
every call site is already map-args (and a shared component still called from an
unconverted Reagent route cannot convert yet: it stays Reagent, embedded under `ui/raw`
in your already-converted trees, until that route migrates too — see the
missing-call-sites trap).

## Construct-by-construct

Type A — mechanical; announce the sweep, then apply. (Target-form semantics: the
`re-frame2-ui` skill; this table is only the rewrite rule. Mechanics are normative in
the W1 migrator rule table — each row anchors to its MIG id, and W1 wins on any
disagreement. Taught only there, same tiers: `doall`-stripping (MIG-12),
markup-`map`→`for` (MIG-13), explicit-frame ops (MIG-03), ns-requires fixup last
(MIG-24), the step-1 ambient-op grep (MIG-26).)

| Reagent construct | Rewrite | Guard |
|---|---|---|
| `(defn v [a b] hiccup)` Form-1 view — or its step-1 spelling `(rf/reg-view v [a b] hiccup)`, the dominant input (unwrap it) *(MIG-01)* | `(ui/defview v [{:keys [a b]}] hiccup)` | one props map, never positional; update **every call site** in the same change — `[v a b]` → `[v {:a a :b b}]`, **and a hiccup-returning fn call `(v a b)` becomes a view site too** (the compiler can't see through a fn call to a raw hiccup return); an already-map single arg stays; a zero-arg call site gains the explicit empty map (`[v]` → `[v {}]`); `[a & rest]`, multi-arity, or a param named `key` → asked-first, not this rewrite |
| `@(subscribe [:q])` — incl. the injected `subscribe` in a `reg-view` body *(MIG-02)* | `(ui/sub [:q])` | drop the deref — immediately-deref'd `subscribe` only; a stored/kept reaction is MIG-19/20 territory; see the loop trap below |
| `#(dispatch [:ev x])` on a **DOM/custom-element** `:on-*` prop *(MIG-04)* | `{:on-click [:ev x]}` | ONLY when the closure body is exactly one dispatch of a literal vector **with the event param unused** (a used `%` is the extraction row below, or a flag); a fn prop on an *internal-view* call site is the Type B forwarding question below; **never lift a vector that captures a loop binding** — compile error, extract a keyed child view |
| `#(do (.preventDefault %) (dispatch [:save]))` *(MIG-06)* | `{:on-click {:event [:save] :prevent-default true}}` | `preventDefault`/`stopPropagation` calls plus exactly one dispatch, nothing else; same loop-capture guard |
| `#(dispatch [:typed (.. % -target -value)])` *(MIG-05)* | `[:typed :rf.ui/value]` | value/checked/key extractions map 1:1 onto placeholder keywords — **top-level vector positions only** (an extraction nested in a subexpression is an asked-first split); closed vocabulary, anything else is `ui/event` **[STAGED:3]** (pre-S3 the hit parks the view — the mixed-handler question below) |
| hiccup tags, `:div.cls#id`, `:style` maps, `:class` vectors *(MIG-14)* | unchanged | this is the point of keeping hiccup — **literal props maps only**; a computed map (`merge`/`assoc`/a bound symbol) is the Type B `ui/spread` question below; two small compile-error flags ride this row: `#id` sugar plus an explicit `:id` on one element, and a collection or fn value in a DOM attr no rule owns (a collection outside `:class`/`:style`, a fn outside `:on-*`/`:ref`) |
| camelCase / alias spellings: `:onClick`, `:className`, `:htmlFor`, `:readOnly` *(MIG-11)* | pinned kebab: `:on-click`, `:class`, `:for`, `:read-only` | table-driven from React's published names; `:class-name`/`:html-for` are compile errors — one spelling per name; `:dangerouslySetInnerHTML` never respells — it isn't a prop here at all: delete it and the `:__html` expr becomes the element's sole child wrapped in `(ui/html …)` *(MIG-34)* |
| `^{:key k}` metadata / `:key` in meta *(MIG-07)* | `{:key k}` in the props map | keys must be unique **after string coercion** |
| callback ref: `{:ref (fn [node] (.focus node))}` *(MIG-29)* | `{:ref (ui/raw-fn (fn [node] (.focus node)))}` | `:ref` is a reserved React slot, never an event prop — the bare-fn shorthand does NOT apply; **DOM elements only** — a `:ref` on an internal-view call site is declared forwarding **[STAGED:3]**, asked-first; prefer object refs; a ref body reading view state is an asked-first call |
| `[:> ReactComp props]` / `adapt-react-class` *(MIG-09)* | `[ReactComp props]` | the head hoist is the mechanical part; **fn-valued props on the result are asked-first** (bare fns at foreign boundaries are compile errors — MIG-10; **an event VECTOR is not a foreign-boundary form either**: foreign components invoke their props themselves and nothing intercepts a vector there, so the component would receive data where it expects a function): `(ui/event …)` / `(ui/handler …)` **[STAGED:3]** for intent/payload/imperative work, `(ui/render-fn …)` **[STAGED:3]** for render-props, `(ui/raw-fn f)` when identity matters (the one auto-suggestable form today); `:f>`/`:r>` heads are a flagged decision, not this rewrite |
| `r/render` / `rdom/render` — or the React-18+ `reagent.dom.client` pair (`create-root` + `render`, the root threaded through a `defonce` atom) *(MIG-15)* | `(ui/mount [ui/frame-root {:id …} [app {}]] el)` | once per converted root; carry the step-1 frame plan over unchanged (`:id`, `:initial-events`, other `make-frame` opts) — or plan-less `frame-root {:id …}` to *adopt* a boot-created frame; `hydrate-root` is the SSR family **[STAGED:5]** |

The dispatch rows (event vectors, placeholders, and the callback forms) ride the S3
events stage **[STAGED:3]** — the grammar parses earlier, committed dispatch behaviour
lands with S3; the `defview`/template/`sub`/mount rows are the live S1–S2 surface.

Type B — local transformation, asked-first. Put the question to the author verbatim:

- **Form-2 / `with-let` local state** *(MIG-16)* → `(local initial)` **[STAGED:3 —
  until `local` lands, the state moves to app-db or the WHOLE view waits on the compat
  tier]**. Ask: *"Does this state have
  product meaning — should it survive remount, be visible to other views/tools/tests,
  appear in replay/time-travel, or feed subscription-derived computation? YES → model
  it in app-db (event + sub) as part of this
  migration. NO (genuinely ephemeral UI state: open/closed, hover, uncommitted draft) →
  `local`."* Answer per value — one view can hold both kinds. A `with-let` `finally`
  clause is unmount cleanup, not state — it maps to `effect` cleanup **[STAGED:3]**;
  cleanup-only `with-let` wrappers are real idiom and stage the same way.
- **`r/atom` holding *app state*** *(MIG-16/20)* — mechanical to *find* (the sweep
  grep-flags each site; doc 10's verdict table carries the find), never to apply: the
  fix is the Form-2 question's pre-answered YES branch — model it in app-db (event +
  sub), designed with the author (W1 files this under D/R — no cross-file
  state-hoisting). It was already wrong under v1 doctrine; a ratom *shared between
  views* as a store is the redesign entry below (MIG-20).
- **Lifecycle / Form-3** *(MIG-17)* (`:component-did-mount` etc.). Ask: *"Is this host/DOM/library
  setup, or domain work? Host setup → `effect` **[STAGED:3]** or a foreign-boundary
  component. Domain work ('mark viewed', fetch-on-show) → a route/domain event or the
  frame's `:initial-events` — there is deliberately no `:on-mount`."* A body doing
  both splits — each part takes its own lane. Any
  `:should-component-update` → delete it; memo-by-default covers it.
- **`r/track` / derived ratoms** *(MIG-19)* → `reg-sub`, usually a copy-paste of the compute fn
  into the dataflow layer. Ask: *"Is the computation pure over app state? YES →
  `reg-sub`. Pure over the view's own local state → it rides the Form-2 placement
  answer, not a sub. Does it side-effect? → that's a redesign (below), not a rewrite."*
- **Handler closures that also do local work** *(MIG-18)*
  (`#(do (set-open! false) (dispatch […]))`).
  Ask: *"Split it? The intent becomes an event vector on the element; the local part
  stays a bare fn (legal) or disappears with the local-state conversion."* Keeping it
  one bare fn is legal on a DOM `:on-*`, but the dispatch inside must respell to
  `(ui/dispatch-fn)` **[STAGED:3]** — a converted view has no ambient `dispatch` — so
  pre-S3 an unsplit hit parks the whole view on the compat tier. A dispatch under a
  conditional guard can't split into a bare vector either — its landing is
  `ui/event`'s nil ⇒ no-dispatch filtering **[STAGED:3]**, same pre-S3 park.
- **Fn-valued props on an *internal-view* call site** *(MIG-27)* — the pervasive
  callback-helper pattern: `[todo-input {:on-change #(dispatch [:edit %])
  :on-commit #(dispatch [:commit])}]`. A bare fn prop at a `defview` boundary is a
  compile error (invoker + phase unknown — this is NOT the DOM bare-fn shorthand).
  Forward the intent as **data**, coordinated with the child's own conversion in the
  same closed-subtree pass: a dispatch-only closure becomes the bare vector
  (`:on-commit [:commit]`) and the child places the forwarded vector in its own DOM
  `:on-*` position (runtime classification: vector → dispatch); a payload-carrying
  closure moves the extraction into the child at its DOM site (`ui/event` **[STAGED:3]**
  or a literal placeholder vector). Ask: *"which end owns the payload — is the parent's
  vector complete, or does the child's DOM site append the value?"* Placeholders
  (`:rf.ui/value` …) work at literal DOM sites only — never forward them as props.
  The dispatch-only forward is non-gating (rewrite + flag); the payload-carrying case
  gates pre-S3 — a forwarded vector cannot append the payload until `ui/event` lands.
- **Computed/dynamic DOM props map** *(MIG-28)* —
  `[:input (merge (dissoc props …) {:type "text" :value draft})]` →
  `[:input (ui/spread base overrides)]`, the one generic runtime prop-map conversion
  (shipped; DOM elements only — view call sites stay literal-map, never `ui/spread`).
  Ask: *"can the spread shrink?"* — a spread site forfeits the static manifest row and
  the controlled-input synchrony door (which needs a provable literal
  `:value`/`:checked` on the element): keep genuinely pass-through props in the spread,
  lift `:value` and handlers back to literals. A conditional `:ref` arm buried in the
  merge lifts out to an explicit `ui/raw-fn` prop (Type A table).
- **`reagent.dom.server/render-to-string`** *(MIG-23)* → the JVM emitter path **[STAGED:5]**;
  views using refs/effects/`ui/raw` need `client-only` **[STAGED:3]** or restructuring.
- **Third-party Reagent wrapper libs (re-com, …)** *(MIG-22)* — per library. Ask: *"Embed under
  `ui/raw` and defer, or replace with a native equivalent now?"* Embedding is the
  default; this decision is separable from the rest of the subtree. (Doc 10 files
  these under its redesign tier and the migrator flags them there; the decision
  *shape* is an asked-first per-library call, which is why this page walks it with
  the Type B questions.)

Redesign (rare — flag, plan with the author, never auto-rewrite): reactions/cursors/
`track!` driving side effects *(MIG-20)* (render-phase effects are compile errors in `ui`;
restructure as events/effects); ratoms shared between components as a side-channel
store *(MIG-20)* (app-db or props — no second state model); dynamic tag heads / runtime-assembled
hiccup *(MIG-21; a markup-building helper call in child position is the same hold —
MIG-30)* (a child view per branch, `ui/raw`, or `re-frame.ui.data` for genuinely
data-driven trees **[STAGED: wave-2, no stage]**); side-effecting sub bodies *(MIG-25)*
(subs are pure; the effectful part becomes leases/events).

## Verify per subtree

The `ui` compiler moves most of the old runtime failure class to **didactic compile
errors** — read them, fix the shape they name, never wrap around them. The residual
runtime class is frame scoping at boundaries and interaction-time behaviour, so the
done-bar per subtree is still live, not "compiles":

1. **Tier-1 structural tests** (`re-frame.ui.test`; JVM, no DOM): `(uit/render view
   {:app-db seed})`, structural `find`/`text`/`attrs`, and **event-intent assertions**
   — assert the button *carries* `[:cart/add id]` as data; no DOM click needed.
   `uit/dispatch!` drives Tier-1 state — real dispatch + drain to fixed point,
   re-render and assert, no flush call needed (`flush!` belongs to the Tier-3 mounted
   vocabulary).
2. **The `.cljc` law, checked before migrating the subtree:** Tier-1 needs the
   events/subs the subtree touches to be `.cljc`. If they are `.cljs`-only, port those
   namespaces first (usually trivial) or accept mounted-only coverage for now.
3. **Live boundary smoke** (the author runs it): boot the co-mounted app, render the
   migrated subtree, dispatch one event per interactive-element class and re-read the
   affected app-db slot, scan the trace for `:rf.error/*` — especially
   `no-frame-context` at boundaries and `frame-payload-conflict` from a config-carrying
   `frame-root` plan meeting the boot-created frame or a differing plan from another
   root (an identical plan is the idempotent no-op; the adopt form is plan-less).
4. **The rest of the app is untouched:** existing dataflow tests and the unconverted
   Reagent subtrees pass exactly as before — step 2 never edits existing events/subs/fx
   (its decisions may *add* a sub or event; they remove nothing — the one agreed
   dataflow edit is purifying a side-effecting sub body *(MIG-25)* before its reading
   view converts).

All four green = the subtree is migrated. Each converted subtree immediately gains
memo-by-default, Xray cause timelines, static interaction surfaces, Tier-1 tests,
state-preserving HMR, and the compiled render path (no interpretation cost) — the
payoff is per-subtree, not end-loaded.

## Traps — looks mechanical, isn't

- **Dispatch-lifting a closure with extra body.** Lifting `#(do (log!) (dispatch […]))`
  to a bare vector silently drops the extra work — verify the closure body is exactly
  one dispatch before lifting (the Type A guard); otherwise it's the Type B split.
- **`sub` inside `for`.** *(MIG-08)* In Reagent, `@(subscribe …)` inside a list body was legal; in
  `ui` it is a **compile error**. The mechanical deref-drop is not enough — index the
  rows into a child view (each row gets its own props + `sub`). The same extraction
  answers a handler vector capturing the loop binding — also a compile error; per-row
  intent needs per-row instances (capture-free literal vectors are fine shared across
  rows).
- **Positional→map conversion missing call sites.** Migrated `ui` call sites fail loud;
  call sites in *not-yet-migrated* Reagent namespaces keep passing positional args to a
  view that no longer accepts them. This is why shared components convert **last**.
- **Respelling `route-link` to a plain anchor.** `[rf/route-link {:to …}]` looks like
  one more head rewrite; it isn't — the runtime does not intercept plain
  `[:a {:href …}]` anchors (spec 012), and no compiled spelling of `route-link` is
  ruled yet (W1 §Open items 7). The view holds on the compat tier *(MIG-32)*.
- **Form-2 state auto-converted to `local`.** The conversion is mechanical; the
  *placement* is the judgment. Blindly keeping product state view-local carries a
  modelling bug across the migration — always ask the Form-2 question.
- **Key coercion collisions.** `{:key 1}` and `{:key "1"}` collide after string
  coercion — a Tier-1 render throws and the dev build warns
  (`:rf.error/ui-duplicate-key`), but only on the code path that renders both;
  production is silent.
- **Ambient frame across an inward boundary.** An embedded *registered* legacy view
  reads context only under the shared-context-object rule **[STAGED:6 confirm]** —
  until confirmed, the explicit `frame-provider` atop the embedded hiccup is mandatory,
  and a missed one fails at interaction time, not render time, for dispatch closures.
- **HMR at inward boundaries.** An edit that remounts the enclosing `ui` view remounts
  the embedded legacy subtree and loses its local ratom state — expected, not a bug.
  Don't place embedded legacy subtrees under Activity-hidden/presence-retained regions
  (**[STAGED:6]** fixture gates lifting this).
- **SSR across a boundary.** `ui/raw` is opaque to the JVM emitter (needs `client-only`
  **[STAGED:3/5]**); a `->react` export has no server story in v1. A page that needs
  real SSR migrates at **sibling-root** granularity, whole root per tier.
- **Porting performance folklore.** `sCU`, manual memo wrappers, `r/track` caching for
  render speed — delete on sight; memo-by-default + compiled renders replace them.
  Re-adding them is an anti-pattern the sibling skill also bans.
