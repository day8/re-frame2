---
name: reagent-migration
description: >
  Migrates Reagent VIEW code to **Freehand** (`re-frame.freehand`, alias
  `v`) — re-frame2's re-frame-native view layer. A Reagent hiccup view
  becomes a `v/defview` mounted in brackets and never called;
  `@(subscribe …)` becomes `(v/sub …)`; `#(dispatch …)` handlers lift to
  event vectors; Form-2 atoms and Form-3 lifecycle re-home to re-frame or
  to a registered behavior. This is the **optional, second** step of the
  journey: do the required re-frame v1→re-frame2 move first (the
  `re-frame-migration` skill), THEN — only if you want the re-frame-native
  view layer — reach for this. **Freehand is pre-alpha; staying on Reagent,
  UIx or Helix is a first-class, fully-supported choice.**
  Trigger on phrasing like "migrate my Reagent views to Freehand", "move
  off Reagent hiccup", "convert reg-view to defview", "adopt the
  re-frame-native views", or any Reagent view surface named in a Freehand
  context (`reagent.core` / `r/atom` / `r/with-let` / `r/create-class` /
  `reagent.dom` `render` / `adapt-react-class` / `@(subscribe …)` in a
  view / `:onClick` camelCase props).
  **Do not use** for: the re-frame v1→v2 events/subs/db migration
  (`re-frame-migration`), writing new re-frame2 code (`re-frame2`),
  greenfield setup (`re-frame2-setup`), or live-runtime inspection
  (`re-frame2-pair`). See `skills/README.md` §Skill routing for the map.
allowed-tools:
  - Bash(rg *)
  - Bash(rg -l *)
  - Bash(git -C * rev-parse *)
  - Bash(git -C * grep *)
  # Run the project's OWN noninteractive compile/test gates (verify-as-you-go).
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

Helps an author migrate **Reagent view code to Freehand** — `re-frame.freehand`, conventionally aliased `v`, re-frame2's re-frame-native view layer ([Spec 004](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md)). A Reagent hiccup view becomes a `v/defview` **declaration** that is mounted in brackets and never called; subscription derefs collapse to `(v/sub …)`; DOM handlers lift from closures to event vectors; and the two things Reagent kept inside the component — local atoms and lifecycle — move to where re-frame can see them.

## Read this first — where this skill sits (optional, second, pre-alpha)

This skill is **not** on anyone's critical path. Three facts frame every use:

1. **It is OPTIONAL and SECOND.** The migration journey is two moves, in order:
   - **(1) re-frame v1 → re-frame2** — the *required* foundation, owned by the **[`re-frame-migration`](../re-frame-migration)** skill. This moves events, subscriptions, `app-db`, effects, and boot. It leaves your **views on Reagent** via the fully-supported Reagent adapter.
   - **(2) Reagent views → Freehand** — *this* skill. Optional. Do it only *after* (1), and only if the author wants the re-frame-native view layer.
2. **Freehand is PRE-ALPHA.** Say so plainly; do not oversell it. Parts of the design are declared and not yet landed — an author-declared `:ref`, a trusted-markup verb, view-local `local` / `effect`. This skill names those gaps honestly and holds the affected views on Reagent. (The React host boundary, once the biggest gap, has since landed in both directions.)
3. **Staying on Reagent is a FIRST-CLASS choice.** A re-frame2 app running Reagent (or UIx, or Helix) views through its adapter is a complete, supported configuration — not a half-migrated one. Freehand is a **peer view layer**, not a successor. **Never imply the author "should" move.**

**When to use this skill (narrow and self-limiting):** the author is *already on re-frame2*, and *specifically wants to trial Freehand* for some or all of their views. That is the whole trigger. Anything short of both halves → they stay where they are, and this skill has no job.

## The mental model (read this before touching a view)

Reagent runs a view **at render time as an ordinary function** that returns hiccup. Freehand's view is a **declaration** — a descriptor value the runtime mounts. Four shifts follow, and internalising them is 80% of the migration → [`references/mental-model.md`](references/mental-model.md):

1. **Brackets mount, parens inline.** `v/defview` binds a descriptor, not a function. `[greeting {:name n}]` mounts a boundary that owns its own subscriptions, memoisation and error containment; `(greeting-bits n)` is an ordinary `defn` helper running inside whoever called it. Calling a declared view raises `:rf.error/view-called-directly`. Changing brackets to parens changes **ownership**, not spelling.
2. **Deref-drop.** A subscription is read with `(v/sub [:q])`, not `@(subscribe [:q])`. `v/sub` returns the **value**; there is no reaction object and nothing to deref.
3. **Dispatch lifts to data.** `{:on-click #(dispatch [:ev x])}` becomes `{:on-click [:ev x]}` — the handler is *data* the tree retains, so a structural test can assert what a button does without a browser.
4. **The view holds no state and no lifecycle.** Freehand has no `local`, no `ref` and no `effect` — deliberately. Product state goes to app-db behind events; a control that owns a genuine multi-interaction protocol becomes a **semantic controller**; DOM-owning work becomes a **registered behavior** over one node.

**Freehand is interpreted by default.** `{:compiled true}` on a declaration is an opt-in, one-line promotion for a hot leaf *after* the migration lands. This matters enormously for scoping: the finite-grammar refusals (dynamic heads, runtime-assembled markup, a bare fn at a render slot) only bite a declaration that opted in. **Migrate interpreted; promote later, if ever.**

## Cardinal rules (the invariants)

1. **This is an AI skill that applies JUDGMENT — it is NOT a codemod.** There is no rewrite tool to run. The skill's power is that for an ambiguous view it *reasons* about the right shape rather than emitting a flag. Apply the mechanical rewrites directly; for the judgment cases, decide with the author.
2. **The whole view is the unit of migration — never half-migrate a view.** A converted `v/defview` has no ambient `subscribe`/`dispatch`, so a body with some sites rewritten and some not does not run. When a view raises a judgment call (D-tier), decide it with the author, then convert the **whole** view or hold the **whole** view. When a view needs a surface with no Freehand equivalent (R-tier), hold it on Reagent and say why.
3. **Incremental, never big-bang.** Migrate one namespace / one closed subtree at a time; verify it renders and its tests pass; then move on — [`references/procedure.md`](references/procedure.md).
4. **The MIG rule catalog is the shared vocabulary.** Every rewrite cites a `MIG-NN` id, so the author can trace any change back to a rule. Don't invent transforms; if a construct matches no rule, treat it as a hold (rule 2).
5. **Views only.** This skill rewrites the **view tier** — hiccup, handlers, mounts, view-held state. It never touches events, subs, fx, machines, schemas, or routes (that dataflow is re-frame2 already, from step 1). Where a view forces a dataflow change (a new `reg-sub`, a hoisted event), the skill *names* it for the author — it does not reach across into the dataflow layer.
6. **Emit only what has shipped.** Freehand's public door is `re-frame.freehand` plus its test sibling `re-frame.freehand.test`, and [`spec/API.md`](https://github.com/day8/re-frame2/blob/main/spec/API.md) is the roster. Design documents describe forms that are not exported yet. **If a verb is not in the API catalogue, do not write it** — name the gap and hold the view.
7. **The skill runs the compile/test gates; the programmer owns the visual confirmation.** Migration is verify-as-you-go, so the skill **discovers and runs the nearest safe noninteractive gate itself** — compile the subtree and run its tests (`npx shadow-cljs compile …`, `npm test`, `clojure -M:test`, whatever the project uses). "Compiles" is necessary but not sufficient: a converted view must still be *rendered* and eyeballed, which stays the programmer's when there is no runtime to drive (or a `re-frame2-pair` read when there is).

## The transformation catalog — organised by tier

The rule catalog is split three ways by **what you do with the rule**, not by construct. Load the tier you need:

- **[`references/catalog-mechanical.md`](references/catalog-mechanical.md) — M-tier ("do this").** Unambiguous, observably-identical rewrites with a before→after for each: `reg-view`/Form-1 → `v/defview` (MIG-01), deref-drop (MIG-02), dispatch-lifting + payload projection + `preventDefault` (MIG-04/05/06), key-meta → prop (MIG-07), prop respelling (MIG-11), `doall` strip (MIG-12), plain hiccup pass-through (MIG-14), mount (MIG-15), ns requires (MIG-24), `route-link` head-rename (MIG-32). Apply these directly.
- **[`references/catalog-judgment.md`](references/catalog-judgment.md) — D-tier ("here's how to DECIDE").** The cases that earn the skill its keep: view-local state (MIG-16 Form-2/`with-let`), lifecycle (MIG-17 Form-3), non-conforming `:on-*` handlers (MIG-18), derived state (MIG-19), the ratom-as-store restructure (MIG-20), SSR path routing (MIG-23), plain-fn ambient reads (MIG-26), fn-valued props on internal views (MIG-27), computed props (MIG-28), runtime-built markup (MIG-30), and the loop / render-prop shaping calls (MIG-08/13). For each: the *decision* the AI makes, not a flag.
- **[`references/catalog-reject.md`](references/catalog-reject.md) — R-tier ("don't migrate this — stay on Reagent").** The honesty backbone: trusted markup (MIG-34), DOM refs (MIG-29), Reagent introspection and schedulers (MIG-35), and the frame-pinned read (MIG-03). This list is what makes the migration honest: some views have no Freehand equivalent. (Its old foreign-React holds MIG-09/10/22 landed — the leaf now records them under §No longer a hold, a judgment call rather than a wait.)

## The procedure (incremental)

Full loop in [`references/procedure.md`](references/procedure.md). The shape:

1. **Scope a closed subtree.** Convert leaf views first, closing bottom-up so each pass ends renderable and tested. Leaf-first is the clean default, not a wall — the outward bridge `v/->react` mounts a converted view under a parent staying on Reagent when one is unavoidable.
2. **Assess the view first (rule 2).** Scan each candidate for D/R hits. An **R** hit → hold the whole view on Reagent. A **D** hit → decide it with the author, then convert the whole view or hold the whole view.
3. **Apply the M-tier rewrites** to the clean views, atomically per view (a header change and all its call sites in one edit).
4. **Fix the ns requires and the root last** (MIG-24, MIG-15): add `[re-frame.freehand :as v]`; drop `reagent.*` requires only when nothing in the namespace still needs them.
5. **Compile + test the subtree (the skill runs the gates); the programmer renders + eyeballs it.** Only then move to the next.

## Gotchas

The traps that mangle a view silently → [`references/gotchas.md`](references/gotchas.md): the **bare-symbol trap** (`[:li item]` — `item` is *content*, not a spread props map), **brackets vs parens** (the ownership change that reads like a spelling change), **whole-view coherence** (rule 2), **the exactly-one-props-map law** (there is no zero-arg and no positional `defview`), **data-vectors-are-not-hiccup** (`[:buy 1]` in an `:on-click` is an event), and **`:ref` has no author-facing spelling yet**.

## Done checklist

- [ ] Only views on an already-re-frame2 app were touched (step 1 — the v1→v2 move — is complete; the author explicitly wants Freehand).
- [ ] Each converted view is whole — no half-migrated bodies (rule 2).
- [ ] Every rewrite cites its `MIG-NN` id so the author can audit it.
- [ ] The D-tier views were *decided with the author*, not silently rewritten.
- [ ] The R-tier views were left on Reagent with an honest reason ("Freehand exports no trusted-markup verb / no author-declared ref — keep this on Reagent").
- [ ] Every Freehand verb emitted appears in `spec/API.md` (cardinal rule 6).
- [ ] Requires cleaned up last (MIG-24); no orphaned `reagent.*` requires, none dropped that a held view still needs.
- [ ] The subtree compiles and its tests pass (the skill ran the gates), and the programmer has **rendered** and eyeballed the converted views.

Hand off: *"Views migrated to Freehand where it made sense; the rest stay on Reagent (a fully-supported configuration). Switch to **`re-frame2`** for new application code, or **`re-frame2-pair`** for live inspection."*

## Anti-patterns

- **Don't run this before the v1→v2 migration.** Freehand is a re-frame2 view layer; it presupposes step 1 is done. → [`re-frame-migration`](../re-frame-migration).
- **Don't sell Freehand as required or production-ready.** It is pre-alpha and optional; the Reagent, UIx and Helix adapters are first-class homes for views indefinitely.
- **Don't half-migrate a view** (cardinal rule 2) — coherence over coverage.
- **Don't auto-spread a bare symbol child** (`[:li item]`) — it is content, not props.
- **Don't reach for `{:compiled true}` during the migration.** Promotion is a separate, later decision on a hot leaf; opting in mid-migration turns interpreted-legal bodies into build failures for no benefit.
- **Don't emit a verb because a design document names it.** `local`, `effect`, `ref` and a trusted-markup form appear in Freehand's design corpus and none is exported. (The outward bridge `v/->react` *is* exported now — which is exactly why you check `spec/API.md` before assuming either way, cardinal rule 6.)
- **Don't reach into the dataflow layer** — name the `reg-sub`/event the view needs; let the author write it (cardinal rule 5).

---

*Freehand reference: [Spec 004 — Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md); the exported roster is [`spec/API.md`](https://github.com/day8/re-frame2/blob/main/spec/API.md). Full skill-routing map: [`skills/README.md` §Skill routing](../README.md#skill-routing--single-source).*
