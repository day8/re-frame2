# MIG-24's end state — when no Reagent view remains

> The closing half of MIG-24, and the one part of it a per-namespace pass
> never reaches: once **every** view is converted, the adapter install and the
> `reagent/reagent` coordinate become the author's two calls. It lives here
> rather than in [`catalog-mechanical.md`](catalog-mechanical.md) so an M-tier
> load does not carry it; routed from [`../SKILL.md`](../SKILL.md)'s done
> checklist and from the stub under MIG-24.

The rest of MIG-24 is about a namespace. There is one require the whole-app case
reaches that a per-namespace sweep never does, and the skill would otherwise
leave the author holding a dependency for nothing.

Once **every** view in the app is converted, `re-frame.adapter.reagent` is no
longer earning its place: its only job was ever the substrate half of Spec 006
— the container `app-db` lives in, plus a derived value that says when it
moved. Fresco ships an adapter of its own, so that job has a second answer:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.fresco :as h]
          [re-frame.fresco.substrate :as substrate])

(rf/init! substrate/adapter)          ;; :kind :rf.adapter/fresco
```

`re-frame.fresco.substrate` ships inside `day8/re-frame2-fresco`, so that line
costs no coordinate. What follows from it is **two** decisions, and this
migration only measured the first.

**1 — the adapter install, and `day8/re-frame2-reagent` with it.** This one
follows from the views alone. That artefact ships exactly one namespace,
`re-frame.adapter.reagent`, so once the install line is gone nothing else in
the app was using it.

**2 — the `reagent/reagent` coordinate.** This one does *not* follow. The skill
is **views only** (cardinal rule 5) and never read the rest of the codebase, and
an `r/atom` in a helper or a `reagent.ratom/run!` in a watcher is an ordinary
thing to still be holding. *Zero Reagent views is not zero Reagent.* The
coupling also runs the way that surprises people: `day8/re-frame2-reagent` is
what puts `reagent/reagent` on the classpath, since it declares the stock
dependency and core declares none. An app that never listed Reagent itself and
still calls `r/atom` somewhere **breaks when the adapter coordinate goes** — and
the repair is to *add* a direct `reagent/reagent` entry, not remove one.

So decide 2 on a measurement rather than an inference from 1. Re-run the
reporter (step 0) over the **whole** repository, not the subtree this migration
touched, and read its census half — it counts view-substrate API call sites
across the nine recognised Reagent namespaces (`reagent.core`, `reagent.dom`,
`reagent.dom.client`, `reagent.ratom`, `reagent.dom.server`, and their four
`reagent2.*` siblings) plus everything under the `re-frame.adapter.` prefix, and
reports what it cannot resolve instead of skipping it:

```
0 view-substrate API call site(s) across 0 file(s) that name Reagent and 0 that name a re-frame2 adapter — 0 mechanical, … — ZERO ENTRIES: bounded by the ROSTER, not by the corpus; read :census :summary :caveat
```

**Read the second number, not the first.** The reporter says what it measures:
its estimand is *rostered view-substrate API call sites, addressed at the CALL*.
A call-site count is the wrong instrument for a dependency question, because a
dependency is created by a **reference**, not by a call. This is not a
hypothetical gap —

```clojure
(ns app.core (:require [reagent.core :as r]))
(def factory r/atom)          ;; passed as a value; never in call-head position
```

— censuses as **0 call sites, no `UNRESOLVED`**, because the require does carry
an alias and nothing is called. `factory` still resolves `reagent.core/atom`,
and the build still needs `reagent/reagent`. The same blind spot covers Reagent
Vars handed around as values, type and protocol references, and any
build/preload configuration that names Reagent outside scanned CLJS call heads.

So the licence is the **files** half of that line: `0 file(s) that name Reagent`,
after you have removed the requires you proved orphaned, **and** no `UNRESOLVED`
clause. One file still naming a recognised Reagent namespace keeps the
coordinate, whatever the call count says. Pair it with a plain textual sweep of
the whole repository — sources *and* dependency/build configuration
(`deps.edn`, `shadow-cljs.edn`, preloads) — for the non-call references the
reporter does not claim to census. Anything that turns up names what has to
stay; when in doubt, keep `reagent/reagent`, which costs a coordinate and never
a broken build.

**Both calls are the author's** (cardinal rule 5): name the option and what each
rests on, then let them decide. Keeping the Reagent adapter is a complete,
supported configuration whatever the views are written in, and an app that may
grow a Reagent or UIx subtree later has a standing reason to keep it. None of it
arises while a single Reagent view survives — until then MIG-15's *the install
stays* is the whole of the answer.
