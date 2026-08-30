# re-frame-migration — Improving the skill

> **Skill-internal meta-doc.** How a maintainer *finds* and *folds in* improvements to the `re-frame-migration` skill from real-world use — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; it guides the maintainer (or an improver agent) between releases. For the skill contract, see [`SKILL.md`](../SKILL.md). For the locked design, see [`design.md`](design.md); for the inputs and the *mechanical* update procedure (a known MIGRATION.md rule changed → which leaf to touch), see [`inputs.md` §6 Update procedure](inputs.md#6-update-procedure).

This doc covers the layer above the mechanical update procedure: **how do you discover what the skill is missing in the first place, and how do you decide whether a candidate finding earns a change?** It is generic maintenance methodology — it applies to any maintainer improving this skill against any v1 codebase, not to one observed migration. It has two parts:

1. **The friction loop** — the method for *finding* candidate improvements.
2. **The quality bar** — the standard every candidate must clear before it earns a change.

---

## Part 1 — The friction loop (how to find improvements)

A migration skill is only as good as the friction it has been driven through. You cannot armchair-author the gaps; they surface only when the skill is run against a real codebase under real conditions. The loop has four steps.

### 1. Drive the skill against a REAL, substantial app — not a toy

Toy migrations do not surface friction. A hand-rolled fixture with three events and one sub exercises the happy path and nothing else; it never trips the rules at scale, so the gaps never appear.

Drive the skill against an app that has the things a toy lacks:

- a **real boot sequence** (a custom init, an ordered startup, a hydration step) rather than a single mount;
- **real v1 add-on libraries** (routing, form, http, persistence add-ons) pulled in transitively, vendored, or git-sourced — each with its own off-contract `re-frame.*` usage;
- **signal-fn / dynamic subscriptions** and other surfaces that the mechanical rewrites only partially reach;
- **enough volume** that an ordering or sequencing mistake compounds instead of staying invisible.

The richness of the host app is the precondition for the loop; everything downstream depends on it. A skill validated only against a toy is unvalidated.

### 2. Harvest friction continuously into a running log

Do not wait until the migration is "done" to reflect. Keep a running log *during* the run, and treat **every** moment the skill was **wrong, silent, confusing, or missing a rule** as a candidate improvement:

- **Wrong** — the skill applied a rewrite that didn't compile, didn't behave, or named the incorrect target.
- **Silent** — the skill said nothing where the author needed a warning (a footgun it walked the author straight into).
- **Confusing** — the author had to re-derive context the skill should have supplied; the phasing or routing sent them the wrong way.
- **Missing** — a v1 surface the skill had no rule, recipe, or even a "stop and ask" for.

Capturing these as they happen — not from memory afterwards — is what makes the harvest complete. Each log entry is a *candidate*, not yet an accepted change; the quality bar (Part 2) decides which ones earn a change.

### 3. Reach RUNTIME — the richest, most-missed seam

This is the step a careless loop skips, and it is where most of the value is. **A loop that stops at "it compiles" harvests almost nothing.**

The highest-value gaps are **compile-invisible**. They do not show up as a red compiler; they show up only when the migrated app is *running* and you inspect its live state. Classes of gap that are invisible until runtime:

- **Silent-fail classes** — a rewrite that compiles and then no-ops at runtime (a registration that silently doesn't take, a handler that swallows instead of acting).
- **Boot footguns** — an init / startup ordering that compiles cleanly but leaves the app in the wrong state at first paint.
- **Registration gaps** — a feature whose registration didn't carry over (e.g. a state machine never registered), so the app boots but the feature is inert.
- **State-replace clobber** — a db-replace / reset path that compiles but wipes initialised state the rest of the app depends on.

To reach these you must read the **live runtime**, not just the source: inspect the running app's state (its app-db) and its feature snapshots (machine state and the like) via a devtools / pair-REPL surface, drive the app's real flows, and watch what actually happens. The migration skill's own loop ([`design.md` §L3](design.md#l3--the-skill-runs-the-gates-it-discovers-the-boot-smoke-test-is-the-done-bar)) runs the project's compile / test gates and drives the boot smoke-test when a runtime is connected — but a mid-migration agent reaches only the runtime it is handed, and reports the smoke pending otherwise. The *maintainer's* improvement loop has no such excuse: it must reach runtime deliberately, because this loop governs how you discover what the skill should teach in the first place, and that discovery is impossible from a compile alone.

> **Field evidence the loop works, and that runtime is where the value is.** One real migration of a substantial v1 app to re-frame2 yielded roughly two dozen generic skill-improvement candidates plus several framework candidates — and the *most valuable* of them were compile-invisible: they were found only by reaching runtime and reading live app-db and machine snapshots. A compile-only loop would have shipped them as latent footguns.

### 4. Fold validated candidates back — split the edit by kind

A candidate that clears the quality bar (Part 2) gets folded back into the skill. **Split the edit by kind**, because the two kinds live in different places and have different owners:

- **Skill-ergonomics** — the structure, phasing, explanation, and routing of `SKILL.md` and its reference leaves. "The skill sent me the wrong way", "the phasing buried the floor gate", "a footgun needed a louder warning here". These are edits to the *skill's* presentation of the migration, not to the rule corpus.
- **Corpus-correctness** — the rules themselves, which live in the migration corpus (the M-rules in MIGRATION.md), not in the skill. "There's no rule for this v1 surface", "this rule's type is wrong", "the rewrite shape is incorrect". The skill is downstream of the corpus ([`design.md` §L1](design.md#l1--migrationfrom-re-frame-v1readmemd-is-the-source-of-truth)); a corpus-correctness finding is fixed in the corpus, and the skill's index / sequencing is then re-aligned per [`inputs.md` §6 Update procedure](inputs.md#6-update-procedure).

Judge each candidate against the quality bar before folding. A candidate that is really a framework bug (Part 2 step 2) is folded into neither the skill nor the corpus — it is routed upstream.

---

## Part 2 — The quality bar (what earns a change)

The friction loop produces candidates; most of them should *not* become changes as written. The quality bar is the standard each candidate must clear. Three gates, applied in order.

### 1. The generic gate (non-negotiable, most-violated)

> **Would someone migrating a *different* v1 repo hit this?**

This is the single most important — and most violated — gate. It is constantly tempting to encode a quirk of the one repo you happened to drive the skill against as a general rule. **Resist it.** A symptom that depends on the observed repo's particular naming, its particular dependency choices, or its particular code shape does **not** earn a skill or corpus change. It is a fact about that repo, not about migration.

A candidate passes only if the underlying gap is structural: any v1 codebase with that *class* of surface (not that specific instance of it) would hit it. If you cannot state the gap without naming the observed repo, it has not passed — either generalise it or drop it.

### 2. Route the finding — framework bug vs skill/corpus gap

A candidate that passes the generic gate is still not yet a skill change, because the right fix may not be in the skill at all. Decide which layer owns it:

- **Framework / spec bug** — the gap is in re-frame2 *itself*: a spec/implementation contradiction, a missing error guard, a surface that should behave one way and doesn't. The fix is to re-frame2, not to the skill. **Do not paper over a framework bug with a skill workaround** — a workaround in the skill leaves the framework broken for everyone who doesn't use the skill, and rots the moment the framework is fixed.
- **Skill / corpus gap** — the framework is correct, but the skill or the corpus failed to *route the migrator through it*: an undocumented silent no-op, a missing recipe, a missing rule, wrong phasing. The fix is to `SKILL.md` (skill-ergonomics) or to MIGRATION.md (corpus-correctness), per the Part 1 step 4 split. **Do not push a documentation gap into the framework** — adding a guard or a special case to the framework to compensate for a missing recipe bloats the framework to fix a doc.

The two failure modes are symmetric and both common: papering a framework bug over with a skill note, and inflating the framework to cover a missing doc. Name the actual layer; fix it there.

> **Note on filing mechanics.** A framework finding from a migration is filed against the re-frame2 repo as a GitHub issue — the skill never patches MIGRATION.md inline, and `bd` (the monorepo's internal tracker) is never invoked from the published skill. See [`design.md` §4 Out of scope](design.md#out-of-scope) and the published-skill baseline in [`../../README.md`](../../README.md). This doc decides *whether and where* a finding is a change; the filing recipe lives there.

### 3. Write it self-contained and generic

A candidate that passes both gates is written up so the maintainer (or improver agent) can act on it later without re-deriving the context. Two halves, both load-bearing:

- **The general rule, with NO repo-specific names.** State the *class* of surface and the *class* of failure, in terms any v1 migrator would recognise. A write-up that only describes one app's symptom is not actionable as a rule — the next maintainer cannot tell what to change.
- **Concrete field evidence.** Pair the general rule with the concrete observation that produced it: "a real migration compiled clean, then broke at X at runtime" — the specific seam, the specific live-state symptom, what was inspected to find it. A rule with no evidence is unweighable: the maintainer cannot judge its severity, its likelihood, or whether it is real.

A write-up that is *only* the symptom (no general rule) or *only* the rule (no evidence) fails this gate. The pair is the deliverable.

---

## Summary

| Step | Question | Output |
|---|---|---|
| Loop 1 | Is the host app real and substantial? | A migration worth harvesting |
| Loop 2 | Where was the skill wrong / silent / confusing / missing? | A running candidate log |
| Loop 3 | What breaks only at runtime under live introspection? | The high-value, compile-invisible candidates |
| Loop 4 | Skill-ergonomics or corpus-correctness? | Candidates split by edit kind |
| Bar 1 | Would a *different* v1 repo hit this? | Repo-quirks dropped |
| Bar 2 | Framework bug or skill/corpus gap? | Each finding routed to its owning layer |
| Bar 3 | General rule **and** field evidence? | An actionable, self-contained write-up |

The loop without the bar produces noise (repo quirks dressed as rules); the bar without the loop produces nothing (you cannot judge candidates you never harvested). Run both.
