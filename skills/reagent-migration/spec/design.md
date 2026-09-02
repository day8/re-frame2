# reagent-migration — Design

> **Skill-internal meta-doc.** Design rationale + locked decisions for the
> `reagent-migration` skill itself — not part of the user-facing or AI-facing
> skill contract. Not loaded during normal skill operation; it exists to
> re-author the skill from inputs. For the skill contract, see
> [`SKILL.md`](../SKILL.md).

A future agent could re-author this skill from this folder alone.

## 0. The 2026-08-14 retarget (rf2-r4j91)

The skill was authored against **Freehand** (`re-frame.freehand`, alias `v`).
Mike retired Freehand and `re-frame.ui` on 2026-08-14 and ruled the architecture
to be **a Reagent adapter and a UIx adapter, plus Hicasso as re-frame2 native**.
The destination is therefore `re-frame.hicasso` (alias `h`), and the locks below
are the post-retarget set. Three of the original locks changed materially and are
recorded as such rather than quietly rewritten — **L1**, **L2** and **L8**.

**UIx is deliberately out of scope**, by Mike's narrowing on the same day: *"We
only need a skill to migrate a Reagent code base to use hicasso instead. We'll
worry about UIx to hicasso later."* No bead was filed for it and the skill does
not mention it as future work.

## 1. Goal

Help a programmer rewrite **Reagent view code into Hicasso** (`re-frame.hicasso`,
aliased `h`) with the smallest correct diff and honest scoping — and, before any
of that, **establish whether they need to at all.**

## 2. Pillars (locked)

1. **Correctness** — the mechanical rewrites are recipe-shaped and cite a
   `MIG-NN` id; the judgment cases are reasoned, not guessed. The skill **runs
   the project's own noninteractive compile/test gates** (verify-as-you-go, under
   the repo's trust-the-explicit-invoker `allowed-tools` baseline) but leaves the
   interactive visual confirmation to the programmer.
2. **Idiomaticness** — every rewrite target is verified against the **shipped**
   Hicasso source. The skill emits no form for a surface that has not landed.
3. **Context economy** — `SKILL.md` is a router; the three tier catalogues +
   mental-model + procedure + gotchas leaves load on demand.
4. **Assume training knowledge** — the agent knows Reagent, hiccup, React,
   re-frame2 events/subs. The skill teaches the **Reagent-view → Hicasso
   binding**: which construct is a mechanical rewrite, which is a judgment call,
   which is a hold.

## 3. Locked decisions

Preserve these unless Mike explicitly unlocks them.

### L1 — The VIEW rewrite is AI judgment; a reporter/fixer exists and is invoked

**Amended by rf2-r4j91.** The original lock read *"no rewrite tool ships or is
invoked — Mike ruled skill-only"*, and that is no longer the state of the tree:
`migration/reagent-to-hicasso/codemod/` shipped under `rf2-2rtt6.143` with its
own ratification, and the skill was still saying there was no tool to run.

The amended lock keeps the part that was actually load-bearing and drops the part
that had gone stale:

- **No tool converts views.** The skill's value on an ambiguous view is that it
  *reasons* about the shape rather than printing a flag. That stands.
- **A source-text reporter DOES ship and the skill runs it first.** Its two
  halves are a census of Reagent API call sites (the inventory that sizes the
  job) and a fixer for the `[:> …]` prop dialect at React crossings, six of whose
  families are decidable from source text. Not routing to it was a defect: the
  skill was leaving the one automated half of the migration on the floor.

If a *view* codemod ever seems needed, stop and ask Mike.

### L2 — The rewrite is OPTIONAL and the skill must not overstate the need

**Strengthened by rf2-r4j91, and it is the point of the skill.** The original
lock framed the journey as (1) v1→v2 required, then (2) optionally
Reagent→Freehand. That is still the shape, but the first half is now stronger
than "optional" implied, and the skill must say so before it does anything:

**With a first-class Reagent adapter, an app moving from re-frame v1 keeps its
view code and needs no rewrite to land on re-frame2.** `day8/re-frame2-reagent`
is the default browser substrate and the adapter the reference suite runs
against. The v1→v2 move *completes* on its own.

So the second step is chosen for what Hicasso offers, not required to arrive. The
skill states the trade in both directions and takes an explicit yes.
**Trust the programmer: state the choice, do not herd.** A migration guide that
implies the rewrite is necessary is worse than no guide, because it costs its
reader work they did not have to do.

Two further honesty obligations ride here:

- **Hicasso is PRE-PUBLICATION.** No Maven coordinate, absent from the lockstep
  array and the release matrix. A project adopts it from source or not at all —
  and the coordinate printed in the draft guide's installation page does not
  resolve. Pre-flight check 3 in `procedure.md` exists for this.
- **Staying on Reagent is a complete, supported configuration**, never a
  half-migrated one.

### L3 — The MIG ids are the shared vocabulary, not a normative corpus

The `MIG-NN` ids are this skill's own numbering, cited so an author can audit any
change. **An id names the Reagent CONSTRUCT the skill recognises, not the
destination shape** — which is what let the numbering survive the retarget: the
same `r/create-class` is still MIG-17 even though its answer changed from a
registered behavior to a callback ref. If the skill and the shipped source
disagree, the source wins.

### L4 — The whole view is the unit of migration

Never half-migrate a view. A **hold** holds the **entire** view on Reagent; a
**judgment call** is decided with the author, then the **whole** view converts or
the **whole** view holds. Coherence over coverage. This is cardinal rule 2.

Under Hicasso this lock has teeth it did not have before: a leftover
`#(dispatch …)` closure is passed to React **by identity** and fails only at
click time, with core's `:rf.error/no-frame-context`. Nothing catches a
half-migrated body earlier.

### L5 — Views only; name dataflow changes, never make them

The skill rewrites the view tier. Where a view forces a dataflow change (a new
`reg-sub` for a hoisted `r/track`, an event for a `r/cursor` write, app-db for a
Form-2 flag), the skill *names* it for the author. This matters more here than on
a substrate with a host-local cell: with **no view-local state tier at all**, the
Form-2 conversion path runs through app-db far more often, and it is still the
author's to write.

### L6 — Incremental, closed-subtree passes

Migrate leaf → root, closing a subtree from the bottom up. A recommended default,
not a hard wall: `h/as-component` bridges a converted view up to a parent staying
on Reagent, UIx or plain React, so a stranded view is never un-renderable.

### L7 — The skill runs the compile/test gates; the programmer owns visual confirmation

The skill discovers and runs the nearest safe noninteractive gate itself. The
done-bar for a subtree is compiles + tests pass + rendered, and "compiles"
carries little weight: the three failures that bite hardest — a surviving
closure, a surviving `^{:key …}`, a Reagent introspection call — all compile
clean.

### L8 — RETIRED: there is no compiled/interpreted split to manage

**Retired by rf2-r4j91.** The original lock was *"migrate interpreted; never
promote mid-flight"*, which was about Freehand's `{:compiled true}` opt-in and
its finite grammar. **Hicasso has no such tier.** It walks the tree at render,
full stop — which is why dynamic tag heads and runtime-built markup are ordinary
pass-through here rather than judgment calls. Emitting `{:compiled true}` against
Hicasso would be inventing an option that does not exist, so the lock is retired
rather than restated.

### L9 — Emit only what has shipped, and READ THE DOOR to find out

Hicasso's **draft guide teaches forms that do not exist** —
an `h/fn` spelling, a key-map restriction to
`:on-key-down`/`:on-key-up`. The authority is
`implementation/hicasso/src/re_frame/hicasso.cljc` and the `impl/` namespaces
beside it, not a design page and not the guide.

**This is now the highest-risk lock in the folder**, because unlike Freehand
there is no `spec/API.md` roster to check against — Hicasso is pre-publication
and unrostered. The check is reading the source.

### L10 — Generic to ANY Reagent consumer app

The skill works on a consumer's Reagent codebase. Examples are abstract
(`price`, `dropdown`, `item`) — never this repo's testbeds, paths, or naming.

### L11 — Findings stay local

Design exploration happens in `ai/` (gitignored); never committed.

## 4. File structure (locked)

```
skills/reagent-migration/
├── SKILL.md                       (router: the "do you need this" framing + reporter + mental model + cardinal rules + tier routing + procedure + gotchas + done)
├── README.md                      (human-facing intro; the optional-second positioning)
├── LICENSE                        (MIT)
├── package.json                   (npm metadata; `files` OMITS evals/ + spec/)
├── .claude-plugin/plugin.json     (Claude Code plugin metadata; status pre-alpha)
├── references/
│   ├── mental-model.md            (the Reagent→Hicasso view shift)
│   ├── catalog-mechanical.md      (M-tier — "do this", before→after per rule)
│   ├── catalog-judgment.md        (D-tier — "here's how to DECIDE")
│   ├── catalog-reject.md          (R-tier — "don't migrate this / stay on Reagent")
│   ├── procedure.md               (reporter first, then incremental closed-subtree passes; the test kit)
│   ├── ssr-hydrate.md             (MIG-23's SSR-then-hydrate recipe — severed from catalog-judgment.md under rf2-n87aa; a client-only migration never loads it)
│   └── gotchas.md                 (three leftovers/three ids, metadata keys, dialect edges, guide-vs-door)
├── evals/
│   └── evals.json                 (trigger fixtures + behavioural fixtures across the M/D/R tiers)
└── spec/
    ├── design.md                  (this file — locked decisions)
    ├── inputs.md                  (the canonical inputs the skill leans on)
    └── authoring-prompt.md        (one-shot reauthor prompt)
```

`evals/` and `spec/` are authoring-time scaffolding — not part of the
distributable (`package.json` `files` omits them). Every reference leaf stays one
level deep from `SKILL.md`.

**Seven reference leaves, not the original six** (rf2-n87aa, 2026-09-02).
`ssr-hydrate.md` was severed from `catalog-judgment.md`, which had reached 1.8x
the family line ceiling and failed the catalogue test in `skills/README.md`
§Leaf size discipline: MIG-23's recipe is ~110 self-contained lines that a
client-only SPA migration — the common case — never reads, yet every D-tier load
carried it. `SKILL.md` routes to it directly, so the one-level rule holds.

## 5. Where this diverges from `re-frame-migration`

- **Different migration.** `re-frame-migration` moves events/subs/db from v1 to
  v2 (`M-N`/`O-N` rules). This skill rewrites *views* from Reagent into Hicasso
  (`MIG-NN` rules). They compose — but note the asymmetry that L2 turns on: the
  first is required and completes; the second is optional and may never be taken.
- **Fewer leaves.** The domain is narrower (view tier only).
- **Honest scoping is a first-class deliverable.** `catalog-reject.md` is not a
  footnote. It is deliberately **short** now — Hicasso has a first-class
  foreign-React door, callback refs, an error boundary, portals, an
  ephemeral-state sugar and a real test kit — and a short honest list is worth
  more than a long stale one.

## 6. Open questions (deferred to Mike)

- **OQ1 — the MIG-13 auto-apply threshold** (the keyed-literal-`map` case): ship
  as an M sub-rule, or keep D? Deferred; the skill treats it as D.
- **OQ2 — a runnable VIEW migrator?** Still rejected — Mike ruled skill-only for
  the view tier, and the shipped codemod is a `[:>]`-dialect reporter/fixer, a
  different population. Revisit only if field use shows the M-tier rewrites are
  applied identically at scale.
- **OQ3 — what happens to this skill when Hicasso publishes?** L2's honesty
  clauses (pre-publication, adopt-from-source, pre-flight check 3) all change on
  the day a Maven coordinate exists. Update this folder first, then the skill.
