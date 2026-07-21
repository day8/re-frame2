---
name: reagent-migration
description: >
  Migrates Reagent VIEW code to re-frame2's experimental **re-frame.ui**
  compiled-view substrate — a Reagent hiccup view becomes a compiled
  `ui/defview`, `@(subscribe …)` becomes `(sub …)`, `#(dispatch …)` handlers
  lift to data, and the frame becomes explicit. This is the **optional,
  second** step of the journey: do the required re-frame v1→re-frame2 move
  first (the `re-frame-migration` skill), THEN — only if you want the
  compiled-view substrate — reach for this. **re-frame.ui is EXPERIMENTAL;
  staying on Reagent views is a first-class, fully-supported choice.**
  Trigger on phrasing like "migrate my Reagent views to re-frame.ui",
  "adopt the compiled views", "convert reg-view to defview", "move off
  Reagent hiccup", "compiled-view substrate", or any Reagent view surface
  named in a re-frame.ui context (`reagent.core` / `r/atom` / `r/with-let` /
  `r/create-class` / `reagent.dom` `render` / `adapt-react-class` /
  `@(subscribe …)` in a view / `:onClick` camelCase props /
  `:dangerouslySetInnerHTML`).
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

Helps an author migrate **Reagent view code to re-frame2's `re-frame.ui`** — the compiled-view substrate ([Spec 004](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md)). A Reagent hiccup view becomes a `ui/defview` that the compiler analyses at build time; subscription derefs collapse to `(sub …)`; DOM handlers lift from closures to data; the frame stops being ambient and becomes an explicit, carried context.

## Read this first — where this skill sits (optional, second, experimental)

This skill is **not** on anyone's critical path. Three facts frame every use:

1. **It is OPTIONAL and SECOND.** The migration journey is two moves, in order:
   - **(1) re-frame v1 → re-frame2** — the *required* foundation, owned by the **[`re-frame-migration`](../re-frame-migration)** skill. This moves events, subscriptions, `app-db`, effects, and boot. It leaves your **views on Reagent** via the fully-supported Reagent adapter.
   - **(2) Reagent views → re-frame.ui** — *this* skill. Optional. Do it only *after* (1), and only if you specifically want the compiled-view substrate.
2. **re-frame.ui is EXPERIMENTAL.** Say so to the author plainly; do not oversell it. Parts of it are still staged (an explicit-frame `sub` pin). This skill names those gaps honestly and tells the author to keep the affected views on Reagent, or wait.
3. **Staying on Reagent views is a FIRST-CLASS, fully-supported choice.** A re-frame2 app running Reagent views through the Reagent adapter is a complete, supported configuration — not a half-migrated one. The Reagent adapter is actively maintained and lives on alongside `re-frame.ui`. **Never imply the author "should" move to `re-frame.ui`.**

**When to use this skill (narrow and self-limiting):** the author is *already on re-frame2*, and *specifically wants to trial the experimental `re-frame.ui` substrate* for some or all of their views. That is the whole trigger. Anything short of both halves → they stay on Reagent, and this skill has no job.

## The mental model (read this before touching a view)

re-frame v1/Reagent runs your view **at render time as an ordinary function** that returns hiccup; `re-frame.ui` **compiles the view at build time**. Four shifts follow, and internalising them is 80% of the migration → [`references/mental-model.md`](references/mental-model.md):

1. **Views COMPILE now.** `ui/defview` bodies are analysed statically. Idioms that only work because Reagent re-runs a plain fn every render — dynamic tag heads, runtime-assembled hiccup, lazy seqs — have no compiled spelling. The compiler is a wall you hit at *build time*, not a footgun you hit at runtime.
2. **Deref-drop.** A subscription is read with `(sub [:q])`, not `@(subscribe [:q])`. The reactive deref is gone; the compiler tracks the dependency.
3. **The frame is EXPLICIT.** A compiled `defview` has no ambient `subscribe`/`dispatch` in scope. Reads and writes go through the compiled `sub`/handler grammar, which resolves the *committed* frame. A bare `@(subscribe …)` inside a plain unregistered fn throws `:rf.error/no-frame-context`.
4. **Dispatch lifts to data.** `{:on-click #(dispatch [:ev x])}` becomes `{:on-click [:ev x]}` — the handler is *data* the compiler retains, not an opaque closure. This is why the migration is analysable at all.

## Cardinal rules (the invariants)

1. **This is an AI skill that applies JUDGMENT — it is NOT a codemod.** There is no rewrite-clj tool to run (that approach was shelved). The skill's power is that for an ambiguous view it *reasons* about the right shape rather than emitting a flag. Apply the mechanical rewrites directly; for the judgment cases, decide with the author.
2. **The whole view is the unit of migration — never half-migrate a view.** When a view raises a judgment call (D-tier), *decide it with the author*, then convert the **whole** view or hold the **whole** view — never a partial body. When a view hits a reject or a construct with no compiled spelling (R-tier), hold the whole view on Reagent and say why. (A few D rules are non-gating — MIG-27's plain fn prop is legal-and-opaque, MIG-28 emits its `ui/spread` with a named check — so read the catalogue row for the per-rule semantics rather than reflexively holding.) A body with some sites rewritten and some not does not compile and does not run: coherence over coverage.
3. **Incremental, never big-bang.** Migrate one namespace / one closed subtree at a time; verify it renders and its tests pass; then move on. A bad bulk conversion is worse than none — [`references/procedure.md`](references/procedure.md).
4. **The [MIG rule catalog](references/catalog-mechanical.md) is the shared vocabulary.** Every rewrite cites a `MIG-NN` id (the same ids the framework's own rule table uses), so the author can trace any change back to a rule. Don't invent transforms; if a construct matches no rule, treat it as a reject-and-hold (rule 2).
5. **Views only.** This skill rewrites the **view tier** — hiccup, handlers, mounts, view-local state. It never touches events, subs, fx, machines, schemas, or routes (that dataflow is re-frame2 already, from step 1). Where a view forces a dataflow change (a new `reg-sub`, a hoisted event), the skill *names* it for the author to do — it does not reach across into the dataflow layer itself.
6. **The skill runs the compile/test gates; the programmer owns the visual confirmation.** Migration is verify-as-you-go, so the skill **discovers and runs the nearest safe noninteractive gate itself** — compile the subtree and run its tests (`npx shadow-cljs compile …`, `npm test`, `clojure -M:test`, whatever the project uses) under the repo's trust-the-explicit-invoker `allowed-tools` baseline. "Compiles" is necessary but not sufficient — a converted view must still be *rendered* and eyeballed, because a few gaps (a converted subtree referenced from unconverted Reagent) surface only at runtime. That interactive render-and-eyeball step is the programmer's when there is no connected browser/runtime to drive (or a `re-frame2-pair` read when there is).

## The transformation catalog — organised by tier

The rule catalog is split three ways by **what you do with the rule**, not by construct. Load the tier you need:

- **[`references/catalog-mechanical.md`](references/catalog-mechanical.md) — M-tier ("do this").** Unambiguous, safe, observably-identical rewrites with a before→after for each: `reg-view`/Form-1 → `ui/defview` (MIG-01), deref-drop (MIG-02), dispatch-lifting + `%`-extraction + `preventDefault` (MIG-04/05/06), key-meta → prop (MIG-07), foreign heads (MIG-09), prop respelling (MIG-11), `doall` strip (MIG-12), plain hiccup pass-through (MIG-14), mount (MIG-15), ns requires (MIG-24), callback ref → `ui/raw-fn` (MIG-29), `capture-frame` → `(frame)` (MIG-31), compiled `route-link` (MIG-32), adapter boot (MIG-33), `dangerouslySetInnerHTML` → `ui/html` (MIG-34). Apply these directly.
- **[`references/catalog-judgment.md`](references/catalog-judgment.md) — D-tier ("here's how to DECIDE").** The cases that earn the skill its keep: view-local state (MIG-16 Form-2/`with-let`), lifecycle (MIG-17 Form-3), non-conforming `:on-*` handlers (MIG-18), SSR path routing (MIG-23), derived state (MIG-19 `track`/`cursor`/`reaction`), the ratom-as-store restructure (MIG-20), computed DOM props + the bare-symbol trap (MIG-28), third-party Reagent wrappers (MIG-22), and the callback-prop / loop-key / foreign-boundary decisions (MIG-27/08/10/13/30/03/26). For each: the *decision* the AI makes, not a flag.
- **[`references/catalog-reject.md`](references/catalog-reject.md) — R-tier ("don't migrate this — stay on Reagent, or wait").** The honesty backbone. Genuine rejects with no compiled equivalent (Reagent introspection/scheduler MIG-35, dynamic tag heads MIG-21, effectful sub bodies MIG-25) **and** the experimental capability gaps that remain unshipped (the explicit-frame arity-1 `sub` pin MIG-03). This list is what makes the migration honest: some views should not move yet.

## The procedure (incremental)

Full loop in [`references/procedure.md`](references/procedure.md). The shape:

1. **Scope a closed subtree.** Pick a namespace or a leaf-to-root view subtree that does not call *into* views staying on Reagent. Leaf-to-root is the preferred low-wrapper default, not a hard rule — the outward `ui/->react` bridge has shipped, so an unconverted Reagent parent *can* render an exported converted child through it (MIG-22); scoping a closed subtree just avoids that boundary wrapper.
2. **Assess the view first (rule 2).** Scan each candidate view for D/R hits. An **R** hit (a reject or an unshipped-capability construct) → hold the whole view on Reagent. A **D** hit → *decide it with the author*, then convert the whole view or hold the whole view — don't reflexively leave it behind, and don't half-migrate it (the non-gating D rules, MIG-27/28, convert with their noted check).
3. **Apply the M-tier rewrites** to the clean views, atomically per view (a header change and all its call sites in one edit).
4. **Fix the ns requires last** (MIG-24): add `[re-frame.ui :as ui :refer [defview sub]]`; drop `reagent.*` requires only when nothing in the namespace still needs them.
5. **Compile + test the subtree (the skill runs the gates); the programmer renders + eyeballs it.** Only then move to the next.

## Gotchas

The traps that mangle a view silently → [`references/gotchas.md`](references/gotchas.md): the **bare-symbol trap** (`[:li item]` — `item` is *content*, not a spread props map; never auto-spread it), **whole-view coherence** (rule 2), **keyed-child extraction** (a `sub` or a loop-capturing handler inside a `for` is a compile error — extract a keyed child view), **dynamic tag heads** (`[(if big? :h1 :h2) …]` has no compiled form), and **data-vectors-are-not-hiccup** (`[:buy 1]` in an `:on-click` is an event, not an element).

## Done checklist

- [ ] Only views on an already-re-frame2 app were touched (step 1 — the v1→v2 move — is complete; the author explicitly wants the experimental substrate).
- [ ] Each converted view is whole — no half-migrated bodies (rule 2).
- [ ] Every rewrite cites its `MIG-NN` id so the author can audit it.
- [ ] The D-tier views were *decided with the author*, not silently rewritten.
- [ ] The R-tier / capability-gap views were left on Reagent with an honest reason ("re-frame.ui doesn't handle this yet — keep this on Reagent, or wait").
- [ ] Requires cleaned up last (MIG-24); no orphaned `reagent.*` requires, none dropped that a held view still needs.
- [ ] The subtree compiles and its tests pass (the skill ran the gates), and the programmer has **rendered** and eyeballed the converted views.

Hand off: *"Views migrated to `re-frame.ui` where it made sense; the rest stay on Reagent (a fully-supported configuration). Switch to **`re-frame2`** for new application code, or **`re-frame2-pair`** for live inspection."*

## Anti-patterns

- **Don't run this before the v1→v2 migration.** re-frame.ui is a re-frame2 substrate; it presupposes step 1 is done. → [`re-frame-migration`](../re-frame-migration).
- **Don't sell re-frame.ui as required or production-ready.** It is experimental and optional; the Reagent adapter is a first-class, supported home for views indefinitely.
- **Don't half-migrate a view** (cardinal rule 2) — coherence over coverage.
- **Don't auto-spread a bare symbol child** (`[:li item]`) — it is content, not props (the bare-symbol trap).
- **Don't emit a staged form** for a gap that hasn't shipped (the `sub` frame-pin) — name the gap and hold the view on Reagent.
- **Don't reach into the dataflow layer** — name the `reg-sub`/event the view needs; let the author write it (cardinal rule 5).

---

*The rule ids (`MIG-NN`) match re-frame2's own Reagent→re-frame.ui rule table. `re-frame.ui` reference: [Spec 004 — Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md). Full skill-routing map: [`skills/README.md` §Skill routing](../README.md#skill-routing--single-source).*
