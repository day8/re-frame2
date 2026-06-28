# Orchestrating a large migration — a collision-free partition for the Phase-3 sweep

**Opt-in. For LARGE migrations only.** The default migration is a single session walking the phases: one agent applies the per-rule recipes in `MIGRATION.md` order, asks at each Type-B checkpoint, and announces before each mass rewrite. That single-threaded shape is correct for the normal case — most apps need little more than M-0 ([`../SKILL.md`](../SKILL.md)). Do **not** reach for the machinery on this page unless the migration is genuinely big.

When is it big enough? When the [Phase-0a inventory](inventory-and-plan.md) comes back with something like **~30 source files** and **several rule-families colliding inside the same files** (interceptor chains *and* dispatch consolidation *and* a boot-flow→machine conversion all landing in `core.cljs`). At that size the single-session march becomes hard to keep coherent, and the moment the work is split — a solo developer interleaving the units, or the units fanned out across multiple agents to land it in hours rather than days — naive splitting produces merge conflicts and silent reverts. This page is the methodology that keeps a large sweep coherent: how to **partition** it into collision-free units and **sequence** them, whether one developer walks those units on a plain local branch or they run concurrently across workers.

It does not replace the phases — it **schedules** them. You still run Phase 0a/0b first (the inventory and the floor gate are inherently up-front and single-threaded), you still cite a rule id per rewrite, and you still finish with the Phase-4 boot smoke-test. What changes is the middle: the Phase-3 sweep becomes a planned set of disjoint worker-units instead of one agent marching the wall.

## Gate the fan-out on inventory completeness

The entire fan-out is *derived* from the Phase-0a inventory — the partition (§1), the id-contract (§2), and the waves (§4) all read off the `file → rules` map it produced. That makes the inventory a **single point of failure**: a file or rule the inventory missed simply falls **outside the partition** — no unit owns it, no worker rewrites it, and because the partition forbids any worker from reaching into a file it doesn't own, nothing inside the fan-out ever picks it up. **The partition inherits the inventory's blind spots.**

So gate the fan-out on the inventory being **provably complete**, not merely drafted:

- **Before Wave 1 starts, the Phase-0a per-rule completeness check must have PASSED** — [`inventory-and-plan.md` §Step 5 — The per-rule completeness gate](inventory-and-plan.md#step-5--the-per-rule-completeness-gate), which iterates the full `breaking-changes.md` `M-`/`O-` rule index, greps each rule's trigger across every git-tracked file, and surfaces every 0-site rule for explicit confirmation. An incomplete inventory must be **closed before the fan-out, not during it**: once units are partitioned and workers are running, a late-discovered surface has no owner and no clean way in. Closing it after the fact means re-partitioning mid-flight — exactly the collision the one-file-one-owner rule exists to avoid.

### Worker expect-and-report — the residual-leak backstop

The completeness gate closes the *known* blind spots up front. The backstop for anything that still slips through is the worker itself. Instruct every worker, as part of its unit brief:

> While applying your unit's rules, if you find a v1 trigger surface in **your** files that is **not** in your assigned rule-list, do **not** silently fix it and do **not** silently skip it. **Stop and report it to the orchestrator.**

A silent fix is an out-of-bounds edit waiting to happen — the surface may belong to a rule another unit owns, or need an id from the Wave-0 contract you weren't handed — and a silent skip re-creates the exact silent miss the inventory gate exists to eliminate. Reporting it back lets the orchestrator **reconcile the surface into the inventory** and re-partition if the new work crosses a unit boundary. That turns an incomplete inventory from a silent gap into a **caught-and-fed-back signal**.

Make it an explicit line in every unit's deliverable: **"no un-inventoried trigger surfaces left unreported in my files."** A unit isn't done when its listed rules are applied — it's done when its files carry no v1 surface the orchestrator hasn't been told about.

Five parts, in the order you apply them.

## 1. One-file-one-owner — the collision-free partition

**The invariant: two workers must NEVER touch the same source file.** Concurrent edits to one file either merge-conflict (the lucky case — you find out at rebase) or, worse, one worker's branch silently reverts the other's changes to that file when both edit it from the same base and the second merge wins. There is no halfway: a file is owned by exactly one worker-unit, and that unit applies **all** of that file's rules.

Derive the partition straight from the Phase-0a inventory. [Step 3 of the inventory](inventory-and-plan.md#step-3--inventory-the-apps-own-v1-re-frame-features) already produced a **file → rules** map — every app-source site that trips an M/O-rule, keyed by file. Invert and group it into **disjoint worker-units**:

- **Group by file, not by rule.** The instinct is "one worker per rule-family" — a worker for all the M-70 interceptor chains, another for all the M-8 dispatch consolidations. That is exactly wrong: M-70 and M-8 sites co-habit `events.cljs`, so two rule-family workers collide there. Instead, each **file** goes to one unit, and that unit applies *every* rule that file trips (its M-70 chains, its M-8 effect maps, its M-1 off-contract requires, all of it).
- **A worker-unit is a set of files, never a fraction of one.** Bundle small related files (a feature's `events.cljs` + `subs.cljs` + `views.cljs`) into one unit so a worker owns a coherent surface. A hot, many-rule file (the app's `core.cljs`) is often a unit of its own.
- **The partition must be a partition** — every inventoried file appears in exactly one unit; their file-sets are pairwise disjoint and their union is the whole changed surface. Write it down as the manifest the fan-out runs from: `unit-A → {events.cljs, subs.cljs}`, `unit-B → {core.cljs}`, … No file is named twice.

The output is the spine of everything below: the wave plan (§4) orders these units, the id-contract (§2) lets them coordinate without sharing a file, and the bridge idiom (§3) is how a unit retargets a producer that lives in *another* unit's file without reaching across the boundary.

## 2. The Wave-0 id-contract — coordinate without sharing files

The one-file rule raises an immediate question: if unit A owns the boot machine and unit B's HTTP handler must dispatch *into* that machine, how do they agree on the address without B editing A's file (or A editing B's)? The answer is that re-frame2's two cross-cutting surfaces — **machines** and **interceptors** — are addressed **by id**, and an id is a value, not a file location. So you coordinate on the *ids*, published once, ahead of the fan-out.

**Publish the id-contract as Wave 0** — a short, agreed list, before any worker starts:

- **Machine-ids.** Each machine is `reg-machine`'d **once**, by its owning unit, under a feature-prefixed keyword (`:app/boot`, `:wizard/checkout`). That keyword **is** the address: any other unit makes the machine advance by dispatching `[:app/boot <event>]` — it references the id, it never re-registers it.
- **Interceptor-ids.** Each shared interceptor is `reg-interceptor`'d **once**, by its owning unit, under an id (`:auth/required`, `:app/with-progress`). Every consumer unit references it by id in its events' `:interceptors [:auth/required]` metadata (per [M-70](auto-cross-cutting.md#event-interceptor-chains--metadata-interceptors-m-70--mechanical-loud-at-runtime-not-loud-at-compile)) — it never redefines the value.

The contract is just *"id → owning unit"* for every machine-id and interceptor-id the migration introduces. Publishing it up front buys two things: **no two units define the same id** (the owner is named, so there is one `reg-machine`/`reg-interceptor` per id — a double-registration is caught at planning, not at a runtime collision), and **consumers only ever reference** (a unit that dispatches `[:app/boot …]` or lists `:interceptors [:auth/required]` touches only its own files). The id is the seam; the contract is what keeps the seam from needing a shared edit.

## 3. The bridge-handler idiom — the general cross-file coordination tool

The id-contract handles the *forward* direction (a consumer references an owner's id). The harder direction is **retargeting a producer**: unit A converts a global async flow into a machine `:app/boot`, and now every handler that used to dispatch the awaited global event (`[:config-loaded]`) must dispatch the **addressed** form (`[:app/boot [:config-loaded]]`) instead — or the machine never advances and the app hangs silently. But those producers live **across many units' files** (`[:config-loaded]` from the config unit, `[:user-loaded]` from the auth unit). Editing them directly would violate the one-file-one-owner rule: unit A would be reaching into unit B's file.

**The bridge handler is the tool that retargets a producer without editing the producer's file.** It is a one-line `reg-event` placed in **YOUR** file (the owning unit's file) that re-dispatches the still-public event into the machine/handler you own:

```clojure
;; In unit A's file. The global [:config-loaded] stays public (other units
;; still listen for it); this bridge re-dispatches it into the machine A owns.
(rf/reg-event :config-loaded
  (fn [_ ev] {:fx [[:dispatch (into [:app/boot] [ev])]]}))
```

Because the bridge lives in the owner's file and only *references* the public event id, the producer units never have to change — you retarget the flow entirely from your own side of the partition. That makes it the **general** partition-coordination tool, not just an async-flow detail: any time a unit needs to redirect traffic that originates in another unit's file, a bridge handler in the owning file does it without crossing the boundary.

This idiom gets its full treatment — the producer-graph wiring pass, when to re-address vs. bridge, the silent-stuck-boot failure mode — in the async-flow conversion guide; read it there and apply it generally: [`async-flow-to-machines.md` §Construct mapping](async-flow-to-machines.md#construct-mapping--async-flow-rule-spec--reg-machine) (the CRITICAL "retarget the producers" note and the cross-file wiring-pass note under it).

## 4. Wave sequencing — foundations publish first, fan-out references after

The units do not all start at once. Order them in **waves**, by who publishes an id and who references it:

1. **Wave 0 — the id-contract itself.** Not code: the agreed *"id → owning unit"* list (§2). It exists before any worker runs so every unit knows the addresses it will reference.
2. **Wave 1 — foundational / id-publishing units.** The units that `reg-machine`/`reg-interceptor` the shared ids, plus the boot/init wiring ([M-40](breaking-changes.md#required-m-rules-by-trigger-surface) `init!` + the app frame). These establish the addresses the contract named. Run them first so the published ids actually exist in the tree before anything references them.
3. **Wave 2+ — feature fan-out units.** The per-feature units that *reference* the published ids — handlers dispatching `[:app/boot …]`, events listing `:interceptors [:auth/required]`, bridges retargeting producers into Wave-1 machines. These are the parallel-safe bulk: each owns disjoint files, each only references ids Wave 1 already published, so they can all run concurrently.

Why this order and not the reverse: a fan-out unit that references `:app/boot` before Wave 1 registered it is referencing a phantom — and because the partition forbids it from defining the id itself (that is Wave 1's job), it would be stuck. Publishing the foundations first means every later reference resolves to a real, owned registration. The dependency is **id-publication**, so the wave boundary is "does this unit *define* a shared id, or only *use* one?" Definers go in Wave 1; users fan out after.

## 5. The all-or-nothing single compile gate

Set expectations honestly, because this is the part that surprises workers: **the project does not compile until the whole sweep lands.** A v1 add-on that `:refer`s the removed `re-frame.core/console` fails to compile the moment re-frame2 is on the classpath (per the forced compile-gate in [`breaking-changes.md`](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in)); the coord swap ([M-0](breaking-changes.md#required-m-rules-by-trigger-surface)) only resolves once *every* broken add-on is dropped-or-converted *and* its usage across all units is rewritten. So mid-sweep there is **no clean per-file compile** — a worker that finishes its unit cannot prove it green in isolation, because the rest of the tree is still half-converted and the classpath is still broken.

Two consequences for how you run the fan-out:

- **Workers produce diffs, not green builds.** A unit's deliverable is its file-set rewritten per its rules, cited and self-consistent, **with no un-inventoried trigger surface left unreported** (per the [worker expect-and-report contract](#worker-expect-and-report--the-residual-leak-backstop) above) — not a passing compile. Judge a unit on whether it applied every rule its files trip (the inventory says which) and whether it surfaced anything its rule-list didn't cover, not on a build it structurally cannot run yet.
- **Green comes at ONE post-sweep gate.** Once all waves have landed and the broken add-ons are gone, you run the **first** real compile of the whole tree — and debug it as a single consolidated pass, not per-file. Errors surface together; trace each to its unit, fix, recompile the whole tree. Budget for this gate explicitly: it is where the migration actually turns green, and it is one focused debugging session, not N small ones. Then run the Phase-4 boot smoke-test ([`runtime-smoke-test.md`](runtime-smoke-test.md)) — because "compiles" is still not the done-bar.

Plan for this from the start: the partition and waves are organised to converge on a single compile gate, so do not promise per-unit green and do not let a worker spin trying to compile a tree that cannot yet compile.

## How it composes with the cardinal rules

This methodology is a *scheduling* layer over the existing rules — it changes who does the work and in what order, not what the rules are.

- **It IS cardinal rule 4 ("announce before a mass rewrite").** Rule 4 requires you to announce the sweep — the rules, the file counts, the diff shape — and pause for the author before touching source. For a large migration, **the partition manifest + the wave plan + the id-contract are that announcement**, at the right altitude. The author reviews one artefact (which files, which units, which ids, which order) and approves the whole sweep, instead of N separate per-rule announcements. The partition *is* the "announce before mass rewrite" gate, scaled up.
- **The execution substrate is a separate choice — the worktree/PR/CI harness is just one.** The partition, the id-contract, and the single compile gate are what the methodology *is*; they say nothing about *how* the rewrites get applied or by whom. A solo developer applies the units one at a time on a plain local branch, commits each, and runs the single consolidated gate (§5) on that branch — no fan-out, no PRs. Or you fan the units out across multiple agents to run concurrently. **If** you fan out, cardinal rule 5's orchestrated-execution mode is **one** clean way to keep the one-file-one-owner partition collision-free: each worker owns its files in an isolated worktree and — at the **single** gate (§5), since per-unit green is impossible — runs the consolidated build in its sandbox and posts command + result to a per-unit PR, with Type-B checkpoints and the boot-smoke verdict surfaced asynchronously for the author to ratify. That worktree/PR/CI machinery is one substrate that *implements* the partition, not a presupposition of it. And the partition is what makes any of these converge cleanly: one-file-one-owner means disjoint file-sets, hence disjoint diffs — no collisions however you land them (a solo dev's sequential commits never conflict; fanned-out branches merge without stepping on each other).

The net shape: **plan up front** (inventory → partition → id-contract → waves), **apply the units** (each owns disjoint files, references published ids, bridges across boundaries, produces diffs — walked solo on a branch or fanned out across agents), **converge once** (a single consolidated compile gate, then the boot smoke-test). The per-rule recipes are unchanged underneath — this is how you run many of them coherently without the units stepping on each other.

---

*Default path (normal-sized migration): the single-session phases in [`../SKILL.md`](../SKILL.md) + the [`sequencing.md`](sequencing.md) walk order. Partition source: [`inventory-and-plan.md`](inventory-and-plan.md). Bridge idiom (full treatment): [`async-flow-to-machines.md`](async-flow-to-machines.md). Composes with cardinal rule 4, and (as one example execution substrate) cardinal rule 5's orchestrated-execution mode — [`../SKILL.md` §Cardinal rules](../SKILL.md#cardinal-rules-the-invariants).*
