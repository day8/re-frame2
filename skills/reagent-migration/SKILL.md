---
name: reagent-migration
description: >
  Rewrites Reagent VIEW code into **Hicasso** (`re-frame.hicasso`, alias
  `h`) — re-frame2's re-frame-native view layer. A Reagent hiccup view
  becomes an `h/defview` mounted in brackets; `@(subscribe …)` becomes
  `(h/sub …)`; `#(dispatch …)` handlers become event vectors in the tree;
  Form-2 atoms and Form-3 lifecycle move out of the component.
  **First establish whether the user needs this at all.** re-frame2's
  Reagent adapter is first-class and actively supported, so an app moving
  from re-frame v1 keeps its view code and needs NO rewrite to land on
  re-frame2 — that is the `re-frame-migration` skill, and it finishes the
  job. This skill is a genuinely OPTIONAL second step, chosen for what
  Hicasso offers, and Hicasso is **pre-publication with no released Maven
  coordinate**. Staying on Reagent is a complete, supported configuration.
  Trigger on phrasing like "migrate my Reagent views to Hicasso", "port
  this component to h/defview", "what does r/atom become under Hicasso",
  "move off Reagent hiccup", or a Reagent view surface named in a Hicasso
  context (`reagent.core` / `r/atom` / `r/with-let` / `r/create-class` /
  `reagent.dom` `render` / `adapt-react-class` / `[:> …]` prop dialect /
  `@(subscribe …)` in a view).
  **Do not use** for: the re-frame v1→v2 events/subs/db migration
  (`re-frame-migration`), writing new re-frame2 code (`re-frame2`),
  greenfield setup (`re-frame2-setup`), or live-runtime inspection
  (`re-frame2-pair`). See `skills/README.md` §Skill routing for the map.
allowed-tools:
  - Bash(rg *)
  - Bash(rg -l *)
  - Bash(git -C * rev-parse *)
  - Bash(git -C * grep *)
  # Run the project's OWN noninteractive compile/test gates (verify-as-you-go),
  # and the migration reporter, which is an ordinary `clojure -M:run`.
  # These routine wildcards are blessed by the published-skill allowed-tools
  # baseline (skills/README.md §Published-skill allowed-tools baseline —
  # trust the explicit invoker); the skill discovers and runs the nearest safe
  # gate, it never wildcards an arbitrary shell.
  - Bash(npm *)
  - Bash(npx *)
  - Bash(clojure *)
  - Bash(shadow-cljs *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# reagent-migration

Helps an author rewrite **Reagent view code into Hicasso** — `re-frame.hicasso`, conventionally aliased `h`, re-frame2's re-frame-native view layer. A Reagent hiccup view becomes an `h/defview` mounted in brackets; subscription derefs collapse to `(h/sub …)`; DOM handlers stop being closures and become data the tree retains; and the two things Reagent kept inside the component — local atoms and lifecycle — move to where re-frame can see them.

## Read this first — establish that the user needs this at all

**This skill is not on anyone's critical path, and the first thing it does is check whether it has a job.**

re-frame2 ships **first-class, actively-supported adapters**. `day8/re-frame2-reagent` is the default browser substrate and the adapter the reference suite runs against; `day8/re-frame2-uix` is its peer. An app moving from re-frame v1 to re-frame2 swaps the dependency, installs the adapter with `rf/init!`, and **keeps its view code**. That is a *finished* migration, not a half-finished one, and it is the [`re-frame-migration`](../re-frame-migration) skill's job.

So rewriting views into Hicasso is a **separate, optional second step, and it is a rewrite rather than a respelling.** Views change shape: parameters become one props map, handlers become data, view-held state leaves the component. Nobody has to take that step to be on re-frame2, and taking it costs real work.

**Say the trade plainly and let the author decide.** Both columns below are measured against the shipped surface, not the design corpus.

| What the rewrite buys | What it costs |
|---|---|
| **Handlers are data in the tree.** `{:on-click [:cart/add id]}` — "what does this button do?" is an equality check in a test, with no browser and no click simulation, and a tool can read it. | **Hicasso is pre-publication.** There is no released Maven coordinate; a project adopts it from source. If yours has no path to that, there is nothing to migrate onto yet. |
| **`h/sub` returns the value.** No reaction object in application code, nothing to deref, nothing to hold; a read inside a `when` or a `for` records an edge only where it happens. | **There is no view-local state tier at all.** Every `r/atom` in a component becomes an ownership decision — app-db, the forms module, or a React island. There is no cell to translate into. |
| **One boundary model.** An `h/defview` is a real React function component and a legal hiccup head; a plain function in head position is a loud error, not a silent embedding. Reagent's Form-1/2/3 folklore collapses. | **Not every Reagent construct has a home.** `component-did-update` has no mechanism, and Reagent's own component introspection and schedulers assume that renderer's objects. Those views stay on Reagent. |
| **Foreign React is declared once, and its callbacks need no declaration.** `h/defhost` names a crossing once; each callback's contract is inferred from the prop's spelling exactly as on a native tag, and a one-line `:callbacks` override covers the vendor whose `on*`-named prop is really a render prop. | **Some spellings are provisional.** Hicasso is pre-alpha and names may move; the frame verb it once shipped, `h/hframe`, is already gone in favour of core's `rf/current-frame-id` and zero-arity `rf/capture-frame`, legal inside a body. |
| **Keystrokes are IME-safe centrally.** A key map is composition-gated once, which is the half a hand-written `.key` test does not have. | **The rewrite is per-view and irreversible in practice.** A converted view has no ambient `subscribe`/`dispatch`; there is no gradual half-state inside one view. |

**When this skill has a job (both halves, or it does not):** the author is *already on re-frame2*, and *specifically wants Hicasso* for some or all of their views. Anything short of both → they stay where they are and you say so.

**Never imply the author "should" move.** A migration guide that implies the rewrite is necessary is worse than no guide: it costs the reader work they did not have to do.

## Start with the reporter — it is a real tool and it runs first

Unlike the view rewrite, the **prop-dialect fixer and the Reagent API census are automated**, and they run before you touch anything:

```bash
cd re-frame2/migration/reagent-to-hicasso/codemod
clojure -M:run path/to/consumer/src/          # scan: report only, touch nothing
```

It reads source text on a bare JVM, loads no re-frame2, and writes a deterministic EDN report with two halves that answer different questions:

- **The fixer** (`:entries`) — every `[:> …]`-family crossing into React. Reagent converted the prop dialect at those sites and Hicasso does not, so a crossing can keep rendering while sending different values. Six rewrite families (W1–W6) are decidable from source text; everything else is a named refusal with a recovery sentence.
- **The census** (`:census`) — every Reagent API **call site**: `r/atom`, `r/with-let`, `r/create-class`, `r/cursor`, `r/as-element`, `r/reactify-component`, root mounting. This is the inventory that tells you how big the job actually is, and it is the half a `[:>]`-only report leaves invisible.

Read the report first. It is exhaustive over what it touched or refused and carries a count of sites left alone, so *"not in the report"* is unambiguous. Its `h/defhost` sketches list the callback positions a site uses, and the usual case needs no `:callbacks` at all — Hicasso infers the contract from the spelling. The one thing to check against the library's own documentation is each `on*`-named prop: a render prop the vendor named `on*` (Fluent's `onRenderCell`, Ant's `onRow`) would infer `:event`, whose wrapper returns `nil` and blanks the UI, so that prop gets a `{:callbacks {… :render}}` override on the host.

`--rewrite --write` applies only the six decidable families, and it is the LAST step, not the first: port and prove a screen by hand, then apply the mechanical edits. Full detail is in the codemod's own README.

## The mental model (read this before touching a view)

Reagent runs a view **at render time as an ordinary function** that returns hiccup. Hicasso's `h/defview` mints **a real React function component** which is a legal hiccup head and nothing else. Four shifts follow, and internalising them is most of the migration → [`references/mental-model.md`](references/mental-model.md):

1. **Brackets mount, parens inline.** `[todo-row {:id id}]` mounts a boundary that owns its own subscription edges and memoisation; `(row-bits id)` is an ordinary `defn` helper running inside whoever called it. Changing brackets to parens changes **ownership**, not spelling.
2. **Deref-drop.** A subscription is read with `(h/sub [:q])`, not `@(subscribe [:q])`. It returns the **value** — there is no reaction object. The read is ambient: legal inside a `when`, a `for`, or an inlined helper, and a branch not taken contributes no edge.
3. **Handlers become data.** `{:on-click #(dispatch [:ev x])}` becomes `{:on-click [:ev x]}`. The **shape** of the value at an `on-*` position selects the behaviour — vector, key map, `h/event`, or plain function — so there is no roster of blessed prop names and `:on-click` and `:onClick` read the same.
4. **The view holds no state.** Hicasso has **no `local`, no `use-state`, no cell of any kind**, and that absence is the design. Product state goes to app-db (`h/reg-state` is the sugar); a draft-and-commit control is `re-frame.hicasso.forms/buffered-field`; genuine widget mechanics go to a React island — a UIx `defui` or a raw React function component mounted through `h/defhost`, with `re-frame.hicasso.native`'s two hooks `use-sub` / `use-frame` for Hicasso state — where React's own hooks are legal.

## Cardinal rules (the invariants)

1. **The view rewrite is JUDGMENT, not a codemod — but the reporter is a real tool.** Run the reporter first (above); it inventories the Reagent surface and fixes the prop dialect at React crossings. No tool converts views: for an ambiguous view the skill *reasons* about the right shape rather than emitting a flag.
2. **The whole view is the unit of migration — never half-migrate a view.** A converted `h/defview` has no ambient `subscribe`/`dispatch`, so a body with some sites rewritten and some not does not work. When a view raises a judgment call (D-tier), decide it with the author, then convert the **whole** view or hold the **whole** view. When a view needs a surface Hicasso does not have (R-tier), hold it on Reagent and say why.
3. **Incremental, never big-bang.** Migrate one namespace / one closed subtree at a time; verify it renders and its tests pass; then move on — [`references/procedure.md`](references/procedure.md).
4. **The MIG rule catalog is the shared vocabulary.** Every rewrite cites a `MIG-NN` id, so the author can trace any change back to a rule. The id names the **Reagent construct you found**, not the destination shape. If a construct matches no rule, treat it as a hold (rule 2).
5. **Views only.** This skill rewrites the **view tier** — hiccup, handlers, mounts, view-held state. It never touches events, subs, fx, machines, schemas, or routes (that dataflow is re-frame2 already, from step 1). Where a view forces a dataflow change (a new `reg-sub`, a hoisted event), the skill *names* it for the author — it does not reach across into the dataflow layer.
6. **Emit only what has shipped, and read the door to find out.** Hicasso's public surface is `re-frame.hicasso` plus six optional modules (`.forms`, `.motion`, `.overlay`, `.native`, `.server`, `.substrate`) and the test kit (`re-frame.hicasso.test*` — `.test`, `.test.mounted`, `.test.forms`, `.test.runtime`, `.test.server`). **No guide page is the API**: pages have taught forms that do not exist — an `h/fn` spelling, since swept — and one still overstates the shipped surface today, restricting key maps to `:on-key-down`/`:on-key-up` when the lowering accepts one anywhere. If a verb is not in the shipped door, do not write it: name the gap and hold the view.
7. **The skill runs the compile/test gates; the programmer owns the visual confirmation.** Migration is verify-as-you-go, so the skill **discovers and runs the nearest safe noninteractive gate itself** — compile the subtree and run its tests (`npx shadow-cljs compile …`, `npm test`, `clojure -M:test`, whatever the project uses). "Compiles" is necessary but not sufficient: Hicasso moves most view errors to run time by design.

## The transformation catalog — organised by tier

Split three ways by **what you do with the rule**, not by construct. Load the tier you need:

- **[`references/catalog-mechanical.md`](references/catalog-mechanical.md) — M-tier ("do this").** Unambiguous, observably-identical rewrites with a before→after for each: Form-1 → `h/defview` (MIG-01), deref-drop (MIG-02), dispatch-lifting and the two markers (MIG-04/05), `preventDefault` (MIG-06), key-meta → `:key` prop (MIG-07), the prop dialect (MIG-11), `doall` strip (MIG-12), plain hiccup pass-through (MIG-14), root mounting (MIG-15), ns requires (MIG-24), keystroke handlers → a key map (MIG-33). Apply these directly.
- **[`references/catalog-judgment.md`](references/catalog-judgment.md) — D-tier ("here's how to DECIDE").** The cases that earn the skill its keep: view-local state (MIG-16 Form-2/`with-let`), lifecycle (MIG-17 Form-3), non-conforming `:on-*` handlers (MIG-18), derived state (MIG-19), the ratom-as-store restructure (MIG-20), plain-fn ambient reads (MIG-26), fn-valued props on internal views (MIG-27), computed props (MIG-28), foreign React heads and their fn-valued props (MIG-09/10/22), SSR-then-hydrate (MIG-23, whose recipe is its own leaf — below), and the loop / render-prop shaping calls (MIG-08/13). For each: the *decision* the AI makes, not a flag.
- **[`references/catalog-reject.md`](references/catalog-reject.md) — R-tier ("don't migrate this — stay on Reagent").** The honesty backbone, and it is short: Reagent introspection and schedulers (MIG-35), a frame-pinned reactive read (MIG-03), and `component-did-update`'s prev-props protocol (MIG-36).
- **[`references/ssr-hydrate.md`](references/ssr-hydrate.md) — MIG-23's SSR-then-hydrate recipe**, and the one leaf a client-only migration never opens: the cold two-process boot condition, the server half, the three ordered client calls, and the `:identifier-prefix` contract. Load it only when a hydrating root is in scope.

## The procedure (incremental)

Full loop in [`references/procedure.md`](references/procedure.md). The shape:

1. **Run the reporter** and read both halves. That is the inventory the plan is built on.
2. **Scope a closed subtree.** Convert leaf views first, closing bottom-up so each pass ends renderable and tested. Leaf-first is the clean default, not a wall — `h/as-component` mounts a converted view under a parent staying on Reagent, UIx or plain React when one is unavoidable.
3. **Assess the view first (rule 2).** Scan each candidate for D/R hits. An **R** hit → hold the whole view on Reagent. A **D** hit → decide it with the author, then convert the whole view or hold the whole view.
4. **Apply the M-tier rewrites** to the clean views, atomically per view (a header change and all its call sites in one edit).
5. **Fix the ns requires and the root last** (MIG-24, MIG-15): add `[re-frame.hicasso :as h]`; drop `reagent.*` requires only when nothing in the namespace still needs them.
6. **Compile + test the subtree (the skill runs the gates); the programmer renders + eyeballs it.** Only then move to the next.

## Gotchas

The traps that mangle a view silently → [`references/gotchas.md`](references/gotchas.md). The one to internalise before anything else:

**A leftover `#(dispatch …)` closure compiles, renders, and fails at CLICK time.** Hicasso passes an unmarked plain function through to React **by identity** — deliberately, so `React.memo` and handler-identity bail-outs keep working — so nothing refuses it at render. When the browser invokes it later there is no render extent, ambient dispatch has nothing to resolve, and it raises `:rf.error/no-frame-context`. That is the whole-view-coherence law (rule 2) with teeth: grep the converted bodies for surviving closures rather than finding them by clicking.

**And it is one of three.** The same half-conversion fails at three different times under three different ids: a leftover ambient `rf/subscribe`/`rf/dispatch` inside the render extent — the body *or* a helper it inlines — refuses at RENDER with `:rf.error/ambient-frame-refused`; the closure above fails at CLICK with `:rf.error/no-frame-context`; an `h/sub` hoisted out into a callback or timer fails at FIRE with `:rf.error/hicasso-sub-outside-render`. Branch on `:rf.error/id` and read `:reason`, which names the fix — the table and the complaint shape are in [`references/gotchas.md`](references/gotchas.md).

The rest: the **bare-symbol trap** (`[:li item]` — `item` is *content*), **brackets vs parens**, **the exactly-one-props-map law**, **data-vectors-are-not-hiccup** (`[:buy 1]` in an `:on-click` is an event), **markers do not nest** (a `::h/value` below the vector's top level arrives as a literal keyword), and **a key map at `:on-submit` prevents on every branch**.

## Done checklist

- [ ] The author was told they do not have to do this, and chose to anyway; the app is already on re-frame2 and Hicasso is reachable from its build.
- [ ] The reporter was run and both halves of its report were read.
- [ ] Each converted view is whole — no half-migrated bodies (rule 2), and no surviving `#(dispatch …)` closure.
- [ ] Every rewrite cites its `MIG-NN` id so the author can audit it.
- [ ] The D-tier views were *decided with the author*, not silently rewritten.
- [ ] The R-tier views were left on Reagent with an honest reason.
- [ ] Every Hicasso verb emitted exists in the shipped door, checked there rather than in a design page (cardinal rule 6).
- [ ] Requires cleaned up last (MIG-24); no orphaned `reagent.*` requires, none dropped that a held view still needs.
- [ ] **If no Reagent view remains**, the author was *told* the adapter choice is now open — `re-frame.hicasso.substrate/adapter` retires `day8/re-frame2-reagent`, and dropping `reagent/reagent` too is a **separate** call resting on a whole-repository measurement — the reporter's *files that name Reagent* count, not its call-site count, plus a textual sweep of dependency config — rather than on the view count, because this skill never read the non-view code (MIG-24) — and made both calls themselves. Not decided for them, and never raised while a Reagent view is still standing.
- [ ] The subtree compiles and its tests pass (the skill ran the gates), and the programmer has **rendered** and eyeballed the converted views.

Hand off: *"Views rewritten into Hicasso where it made sense; the rest stay on Reagent, which is a fully-supported configuration. Switch to **`re-frame2`** for new application code, or **`re-frame2-pair`** for live inspection."*

## Anti-patterns

- **Don't run this before the v1→v2 migration.** Hicasso is a re-frame2 view layer; it presupposes step 1 is done. → [`re-frame-migration`](../re-frame-migration).
- **Don't sell the rewrite as required.** The Reagent and UIx adapters are first-class homes for views indefinitely, and Hicasso has no published coordinate yet.
- **Don't half-migrate a view** (cardinal rule 2) — coherence over coverage.
- **Don't auto-spread a bare symbol child** (`[:li item]`) — it is content, not props.
- **Don't emit a verb because a guide page names it.** The `h/fn` spelling that used to be the standing example was swept to `h/event` on 2026-08-15, but the class did not go with it: the shipped guide still restricts key maps to `:on-key-down`/`:on-key-up` when the lowering accepts one at any event position. Check the door. (A plain `merge` with the owned keys last IS the shipped spelling for forwarding caller attrs; there is no reserved merge key.)
- **Don't invent a listener-options map.** There is no `{:event […] :prevent-default true}`, no `:capture`, no `:passive`, no `:once`, no `:stop-propagation` — not undocumented, unrepresentable. `::h/prevent` is a reserved head and imperative event work belongs in `h/event`.
- **Don't declare `:callbacks` for the usual case, and don't skip the check for the unusual one** — the contract is inferred from the spelling, and the override exists for the vendor's `on*`-named render prop, where the inferred `:event` wrapper blanks the UI silently. Check every `on*` prop's return value against the library's documentation.
- **Don't reach into the dataflow layer** — name the `reg-sub`/event the view needs; let the author write it (cardinal rule 5).

---

*Hicasso's public door is [`implementation/hicasso/src/re_frame/hicasso.cljc`](https://github.com/day8/re-frame2/blob/main/implementation/hicasso/src/re_frame/hicasso.cljc) — read it, not a design page, for what has shipped. Full skill-routing map: [`skills/README.md` §Skill routing](../README.md#skill-routing--single-source).*
