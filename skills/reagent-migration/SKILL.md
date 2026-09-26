---
name: reagent-migration
description: >
  Rewrites Reagent view code into Fresco (`re-frame.fresco`, alias `h`),
  re-frame2's optional re-frame-native view layer: a view becomes an
  `h/defview`, `@(subscribe …)` becomes `(h/sub …)`, handler closures become
  event vectors, and Form-2/Form-3 state and lifecycle move out of the
  component. Runs a reporter to size the job, applies the mechanical rewrites,
  decides judgment cases with the author, and keeps the rest on Reagent.
  Opt-in: use it only on an app already on re-frame2 whose author wants Fresco
  — "migrate my Reagent views to Fresco", "port this component to h/defview",
  or `r/atom`, `r/with-let`, `r/create-class` or `[:> …]` sites raised in a
  Fresco context. The Reagent adapter is first-class, so a v1→v2 move keeps
  its views and is `re-frame-migration`'s job. Not for new re-frame2 code
  (`re-frame2`), project setup (`re-frame2-setup`) or live-app inspection
  (`re-frame2-pair`).
allowed-tools:
  - Bash(rg *)
  - Bash(rg -l *)
  - Bash(git -C * rev-parse *)
  - Bash(git -C * grep *)
  # Run the project's OWN noninteractive compile/test gates (verify-as-you-go),
  # and the migration reporter, which is an ordinary `clojure -Sdeps … -M -m` run.
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

Helps an author rewrite **Reagent view code into Fresco** — `re-frame.fresco`, conventionally aliased `h`, re-frame2's re-frame-native view layer. A Reagent hiccup view becomes an `h/defview` mounted in brackets; subscription derefs collapse to `(h/sub …)`; DOM handlers stop being closures and become data the tree retains; and the two things Reagent kept inside the component — local atoms and lifecycle — move to where re-frame can see them.

**What it changes:** the consumer's view namespaces (bodies, call sites, requires, the root), and nothing in the dataflow layer. It runs the project's own compile and test commands as it goes, and runs the reporter's file-rewriting `--rewrite --write` mode only as the last step, on a tree the author can diff.

## Read this first — establish that the user needs this at all

**This skill is not on anyone's critical path, and the first thing it does is check whether it has a job.**

re-frame2 ships **first-class, actively-supported adapters**. `day8/re-frame2-reagent` is the default browser substrate and the adapter the reference suite runs against; `day8/re-frame2-uix` is its peer. An app moving from re-frame v1 to re-frame2 swaps the dependency, installs the adapter with `rf/init!`, and **keeps its view code**. That is a *finished* migration, not a half-finished one, and it is the [`re-frame-migration`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration) skill's job.

So rewriting views into Fresco is a **separate, optional second step, and it is a rewrite rather than a respelling.** Views change shape: parameters become one props map, handlers become data, view-held state leaves the component. Nobody has to take that step to be on re-frame2, and taking it costs real work.

**Say the trade plainly and let the author decide.** Both columns below are measured against the shipped surface, not the design corpus.

| What the rewrite buys | What it costs |
|---|---|
| **Handlers are data in the tree.** `{:on-click [:cart/add id]}` — "what does this button do?" is an equality check in a test, with no browser and no click simulation, and a tool can read it. | **One more coordinate, on the route you already use.** `day8/re-frame2-fresco` is in the re-frame2 release set and ships at core's version, so your build resolves it however it resolves `day8/re-frame2` — from source until a release, as every re-frame2 artefact does. It adds a dependency, not a separate path to wait for. |
| **`h/sub` returns the value.** No reaction object in application code, nothing to deref, nothing to hold; a read inside a `when` or a `for` records an edge only where it happens. | **There is no view-local state tier at all.** Every `r/atom` in a component becomes an ownership decision — app-db, the forms module, or a React island. There is no cell to translate into. |
| **One boundary model.** An `h/defview` is a real React function component and a legal hiccup head; a plain function in head position is a loud error, not a silent embedding. Reagent's Form-1/2/3 folklore collapses. | **Not every Reagent construct has a home.** `component-did-update` has no mechanism, and Reagent's own component introspection and schedulers assume that renderer's objects. Those views stay on Reagent. |
| **Foreign React is declared once, and its callbacks need no declaration.** `h/defhost` names a crossing once; each callback's contract is inferred from the prop's spelling exactly as on a native tag, and a one-line `:callbacks` override covers the vendor whose `on*`-named prop is really a render prop. | **Some spellings are provisional.** Fresco is pre-alpha and names may move, so every spelling is checked against the shipped door rather than remembered. |
| **Keystrokes are IME-safe centrally.** A key map is composition-gated once, which is the half a hand-written `.key` test does not have. | **The rewrite is per-view and irreversible in practice.** A converted view has no ambient `subscribe`/`dispatch`; there is no gradual half-state inside one view. |

**When this skill has a job (both halves, or it does not):** the author is *already on re-frame2*, and *specifically wants Fresco* for some or all of their views. Anything short of both → they stay where they are and you say so.

**Never imply the author "should" move.** A migration guide that implies the rewrite is necessary is worse than no guide: it costs the reader work they did not have to do.

## Start with the reporter — it is a real tool and it runs first

The **prop-dialect fixer and the view-substrate API census are automated**, and they run before you touch anything. From the consumer's own project — no re-frame2 checkout is needed, and none is created:

```bash
clojure -Srepro \
  -Sdeps '{:deps {day8/re-frame2-fresco-codemod
                  {:git/url   "https://github.com/day8/re-frame2.git"
                   :git/sha   "8b17cc53d517de9359f5174a0d2fcfa4748091ab"
                   :deps/root "migration/reagent-to-fresco/codemod"}}}' \
  -M -m re-frame.migration.fresco.codemod path/to/consumer/src/ --report out.edn
```

It reads source text on a bare JVM, loads no re-frame2, touches no file, and writes an EDN report (without `--report`, `reagent-to-fresco-report.edn` beside the first path scanned). Expect one stderr line, `Use of :paths external to the project has been deprecated`: the tool puts a shared `.cljc` file on its own classpath deliberately, so it is not a failure. The Fresco jar does not carry the reporter, so this git coordinate is how it is delivered; pin a newer `:git/sha` from `git ls-remote https://github.com/day8/re-frame2.git refs/heads/main` if you want one.

The report has two halves that answer different questions:

- **The census** (`:census`) — every rostered view-substrate API **call site**, across Reagent's API and re-frame2's own adapters under `re-frame.adapter.`. It sizes the job, and its classes route to MIG rules.
- **The fixer** (`:entries`) — every `[:> …]`-family crossing into React, where Reagent converted the prop dialect and Fresco does not. Six rewrite families (W1–W6) are decidable from source text; everything else is a named refusal with a recovery sentence.

How to read both halves, and what the report cannot see, is [`references/procedure.md`](references/procedure.md) Step 0. `--rewrite` turns the same command into a dry run of the fixer and `--rewrite --write` **rewrites the consumer's files** — the six decidable families only — which is the LAST step of a migration, not the first (Step 6).

## The mental model (read this before touching a view)

Reagent runs a view **at render time as an ordinary function** that returns hiccup. Fresco's `h/defview` mints **a real React function component** which is a legal hiccup head and nothing else. Four shifts follow, and internalising them is most of the migration → [`references/mental-model.md`](references/mental-model.md):

1. **Brackets mount, parens inline.** `[todo-row {:id id}]` mounts a boundary that owns its own subscription edges and memoisation; `(row-bits id)` is an ordinary `defn` helper running inside whoever called it. Changing brackets to parens changes **ownership**, not spelling.
2. **Deref-drop.** A subscription is read with `(h/sub [:q])`, not `@(subscribe [:q])`. It returns the **value** — there is no reaction object. The read is ambient: legal inside a `when`, a `for`, or an inlined helper, and a branch not taken contributes no edge.
3. **Handlers become data.** `{:on-click #(dispatch [:ev x])}` becomes `{:on-click [:ev x]}`. The **shape** of the value at an `on-*` position selects the behaviour — vector, key map, `h/event`, or plain function — so there is no roster of blessed prop names and `:on-click` and `:onClick` read the same.
4. **The view holds no state.** Fresco has **no `local`, no `use-state`, no cell of any kind**, and that absence is the design. Product state goes to app-db (`h/reg-state` is the sugar); a draft-and-commit control is `re-frame.fresco.forms/buffered-field`; genuine widget mechanics go to a React island — a UIx `defui` or a raw React function component mounted through `h/defhost`, with `re-frame.fresco.native`'s two hooks `use-sub` / `use-frame` for Fresco state — where React's own hooks are legal.

## Cardinal rules (the invariants)

1. **The view rewrite is judgment, not a codemod — but the reporter is a real tool.** Run the reporter first (above); it inventories the Reagent surface and fixes the prop dialect at React crossings. No tool converts views: for an ambiguous view the skill *reasons* about the right shape rather than emitting a flag.
2. **The whole view is the unit of migration — never half-migrate a view.** A converted `h/defview` has no ambient `subscribe`/`dispatch`, so a body with some sites rewritten and some not does not work. When a view raises a judgment call (D-tier), decide it with the author, then convert the **whole** view or hold the **whole** view. When a view needs a surface Fresco does not have (R-tier), hold it on Reagent and say why.
3. **Incremental, never big-bang.** Migrate one namespace / one closed subtree at a time; verify it renders and its tests pass; then move on — [`references/procedure.md`](references/procedure.md).
4. **The MIG rule catalog is the shared vocabulary.** Every rewrite cites a `MIG-NN` id, so the author can trace any change back to a rule. The id names the **Reagent construct you found**, not the destination shape. If a construct matches no rule, treat it as a hold (rule 2).
5. **Views only.** This skill rewrites the **view tier** — hiccup, handlers, mounts, view-held state. It never touches events, subs, fx, machines, schemas, or routes (that dataflow is re-frame2 already, from step 1). Where a view forces a dataflow change (a new `reg-sub`, a hoisted event), the skill *names* it for the author — it does not reach across into the dataflow layer.
6. **Emit only what has shipped, and read the door to find out.** Fresco's public surface is `re-frame.fresco` plus six optional modules (`.forms`, `.motion`, `.overlay`, `.native`, `.server`, `.substrate`) and the test kit (`re-frame.fresco.test*` — `.test`, `.test.mounted`, `.test.forms`, `.test.runtime`, `.test.server`). **No guide or design page is the API**: pages drift from the door, and design notes describe forms that never shipped (there is no `h/fn`; the callback form is `h/event`). If a verb is not in the shipped door, do not write it: name the gap and hold the view.
7. **The skill runs the compile/test gates; the programmer owns the visual confirmation.** Migration is verify-as-you-go, so the skill **discovers and runs the nearest safe noninteractive gate itself** — compile the subtree and run its tests (`npx shadow-cljs compile …`, `npm test`, `clojure -M:test`, whatever the project uses). "Compiles" is necessary but not sufficient: Fresco moves most view errors to run time by design.

## The transformation catalog — organised by tier

Split three ways by **what you do with the rule**, not by construct. Load the tier you need:

- **[`references/catalog-mechanical.md`](references/catalog-mechanical.md) — M-tier ("do this").** Unambiguous rewrites with a before→after for each: Form-1 → `h/defview` (MIG-01), deref-drop (MIG-02), dispatch-lifting and the two markers (MIG-04/05), `preventDefault` (MIG-06), key-meta → `:key` prop (MIG-07), the prop dialect (MIG-11), `doall` strip (MIG-12), plain hiccup pass-through (MIG-14), root mounting (MIG-15), ns requires (MIG-24), keystroke handlers → a key map (MIG-33). Apply these directly. They preserve *what* the view does — with one divergence to know before you start: the handler-lifting rules (MIG-04/05, MIG-06, MIG-33) also change *when* the event drains, because a Fresco intent dispatches synchronously where `rf/dispatch` queued. [`references/mental-model.md`](references/mental-model.md) §3 states it once; MIG-04/05 carries the one check and the one escape.
- **[`references/catalog-judgment.md`](references/catalog-judgment.md) — D-tier ("here's how to DECIDE").** The cases that earn the skill its keep: view-local state (MIG-16 Form-2/`with-let`), lifecycle (MIG-17 Form-3), non-conforming `:on-*` handlers (MIG-18), derived state (MIG-19), the ratom-as-store restructure (MIG-20), plain-fn ambient reads (MIG-26), fn-valued props on internal views (MIG-27), computed props (MIG-28), foreign React heads — `[:> …]` and `r/adapt-react-class` — and their fn-valued props (MIG-09/10/22), SSR-then-hydrate (MIG-23, whose recipe is its own leaf — below), and the loop / render-prop shaping calls (MIG-08/13). For each: the *decision* the AI makes, not a flag.
- **[`references/catalog-reject.md`](references/catalog-reject.md) — R-tier ("don't migrate this — stay on Reagent").** The honesty backbone, and it is short: Reagent introspection and schedulers (MIG-35), a frame-pinned reactive read (MIG-03), and `component-did-update`'s prev-props protocol (MIG-36).
- **[`references/ssr-hydrate.md`](references/ssr-hydrate.md) — MIG-23's SSR-then-hydrate recipe**, and the one leaf a client-only migration never opens: the cold two-process boot condition, the server half, the three ordered client calls, and the `:identifier-prefix` contract. Load it only when a hydrating root is in scope.

## The procedure (incremental)

Full loop in [`references/procedure.md`](references/procedure.md). The shape:

1. **Run the reporter** and read both halves. That is the inventory the plan is built on.
2. **Scope a closed subtree.** Convert leaf views first, closing bottom-up so each pass ends renderable and tested. Leaf-first is the clean default, not a wall — `h/as-component` mounts a converted view under a parent staying on Reagent, UIx or plain React when one is unavoidable.
3. **Assess the view first (rule 2).** Scan each candidate for D/R hits. An **R** hit → hold the whole view on Reagent. A **D** hit → decide it with the author, then convert the whole view or hold the whole view.
4. **Apply the M-tier rewrites** to the clean views, atomically per view (a header change and all its call sites in one edit).
5. **Fix the ns requires and the root last** (MIG-24, MIG-15): add `[re-frame.fresco :as h]`; drop `reagent.*` requires only when nothing in the namespace still needs them.
6. **Compile + test the subtree (the skill runs the gates); the programmer renders + eyeballs it.** Only then move to the next.

## Gotchas

The traps that mangle a view silently → [`references/gotchas.md`](references/gotchas.md). The one to internalise before anything else:

**A half-converted view fails at three different times under three different ids**, and only the first is caught before a user finds it: a leftover ambient `rf/subscribe`/`rf/dispatch` in the body *or* a helper it inlines refuses at render (`:rf.error/ambient-frame-refused`); a surviving `#(dispatch …)` closure compiles, renders, and fails on click (`:rf.error/no-frame-context`), because Fresco hands a plain function to React untouched; an `h/sub` moved into a callback or timer fails when it fires (`:rf.error/fresco-sub-outside-render`). So grep converted bodies for `#(`, `(fn [`, `subscribe` and `dispatch` rather than finding them by clicking.

The rest — brackets versus parens, the bare-symbol trap, the `::h/…` keyword roster, and an index of the silent traps each rule carries — is in the gotchas file.

## Done checklist

- [ ] The author was told they do not have to do this, and chose to anyway; the app is already on re-frame2 and Fresco is reachable from its build.
- [ ] The reporter was run and both halves of its report were read.
- [ ] Each converted view is whole — no half-migrated bodies (rule 2), and no surviving `#(dispatch …)` closure.
- [ ] Every rewrite cites its `MIG-NN` id so the author can audit it.
- [ ] The D-tier views were *decided with the author*, not silently rewritten.
- [ ] The R-tier views were left on Reagent with an honest reason.
- [ ] Every Fresco verb emitted exists in the shipped door, checked there rather than in a design page (cardinal rule 6).
- [ ] Requires cleaned up last (MIG-24); no orphaned `reagent.*` requires, none dropped that a held view still needs.
- [ ] **If no Reagent view remains**, the author was told the adapter choice is now open and made both calls themselves: swapping to `re-frame.fresco.substrate/adapter` retires `day8/re-frame2-reagent`, while dropping `reagent/reagent` rests on a whole-repository measurement, not the view count ([`references/end-state.md`](references/end-state.md)). Never raised while a Reagent view still stands.
- [ ] The subtree compiles and its tests pass (the skill ran the gates), and the programmer has **rendered** and eyeballed the converted views.

Hand off: *"Views rewritten into Fresco where it made sense; the rest stay on Reagent, which is a fully-supported configuration. Switch to **`re-frame2`** for new application code, or **`re-frame2-pair`** for live inspection."*

## Anti-patterns

- **Don't run this before the v1→v2 migration.** Fresco is a re-frame2 view layer; it presupposes step 1 is done. → [`re-frame-migration`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-migration).
- **Don't sell the rewrite as required.** The Reagent and UIx adapters are first-class homes for views indefinitely. And don't invent a publication gap either: Fresco ships in the same release set as the adapters ([`references/procedure.md`](references/procedure.md) pre-flight check 3).
- **Don't invent a listener-options map.** There is no `{:event […] :prevent-default true}`, no `:capture`, no `:passive`, no `:once`, no `:stop-propagation` — not undocumented, unrepresentable. `::h/prevent` is a reserved head and imperative event work belongs in `h/event`.
- **Don't declare `:callbacks` for the usual case, and don't skip the check for the unusual one** — the contract is inferred from the spelling, and the override exists for the vendor's `on*`-named render prop, where the inferred `:event` wrapper blanks the UI silently. Check every `on*` prop's return value against the library's documentation.

---

*Fresco's public door is [`implementation/fresco/src/re_frame/fresco.cljc`](https://github.com/day8/re-frame2/blob/main/implementation/fresco/src/re_frame/fresco.cljc) — read it, not a design page, for what has shipped. Full skill-routing map: [`skills/README.md` §Skill routing](https://github.com/day8/re-frame2/blob/main/skills/README.md#skill-routing--single-source).*
