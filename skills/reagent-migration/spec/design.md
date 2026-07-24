# reagent-migration — Design

> **Skill-internal meta-doc.** Design rationale + locked decisions for the
> `reagent-migration` skill itself — not part of the user-facing or AI-facing
> skill contract. Not loaded during normal skill operation; it exists to
> re-author the skill from inputs. For the skill contract, see
> [`SKILL.md`](../SKILL.md).

A future agent could re-author this skill from this folder alone.

## 1. Goal

Help a programmer migrate **Reagent view code to Freehand** (`re-frame.freehand`, aliased `v`) — re-frame2's re-frame-native view layer — with the smallest correct diff and honest scoping. The skill is **knowledge Claude reads and applies with judgment**; it is explicitly **not** a codemod. The rewrite-clj tool approach was shelved in favour of an AI skill that *reasons* on ambiguous views rather than emitting a flag.

## 2. Pillars (locked)

The same four pillars as the skill family, adapted to this domain.

1. **Correctness** — the mechanical rewrites are recipe-shaped and cite a `MIG-NN` id; the judgment cases are reasoned, not guessed. The skill **runs the project's own noninteractive compile/test gates** (verify-as-you-go, under the repo's trust-the-explicit-invoker `allowed-tools` baseline) but leaves the interactive visual confirmation — booting and eyeballing the render — to the programmer when no connected runtime exists.
2. **Idiomaticness** — every rewrite target is verified against the **shipped** Freehand surface (`spec/API.md`, `docs/api/re-frame.freehand.md`). The skill emits no form for a surface that has not landed.
3. **Context economy** — `SKILL.md` is a router; the three tier catalogues + mental-model + procedure + gotchas leaves load on demand.
4. **Assume training knowledge** — the agent knows Reagent, hiccup, React, re-frame2 events/subs. The skill teaches the **Reagent-view → Freehand binding**: which construct is a mechanical rewrite, which is a judgment call, which is a hold.

## 3. Locked decisions

Preserve these unless Mike explicitly unlocks them.

### L1 — It is an AI skill, NOT a codemod

No rewrite tool ships or is invoked. The skill's value on ambiguous views is that it **reasons** about the shape rather than printing a mechanical flag. If the skill ever appears to need a companion tool, **stop and ask Mike** — he ruled skill-only.

### L2 — OPTIONAL, SECOND, PRE-ALPHA (the load-bearing framing)

The migration journey is (1) re-frame v1→v2 [the required foundation, the `re-frame-migration` skill], then (2) OPTIONALLY Reagent→Freehand [this skill]. Freehand is **pre-alpha** and the skill says so plainly. **Staying on Reagent, UIx or Helix is a first-class, fully-supported choice** — Freehand is a *peer view layer*, not a successor. The "when to use" is narrow and self-limiting: *already on re-frame2 AND specifically wants to trial Freehand.* A reviewer reading `SKILL.md` must not come away thinking Freehand is required or production-ready. This framing is not decoration — it is the point.

### L3 — The MIG ids are the shared vocabulary, not a normative corpus

The `MIG-NN` ids are this skill's own numbering for the rewrites it applies, cited so an author can audit any change. The skill distils rules into M/D/R guidance + before→after examples; the normative contract is Spec 004 and the exported roster in `spec/API.md`. If the skill and the spec disagree, the spec wins.

### L4 — The whole view is the unit of migration

Never half-migrate a view. A **hold** (an unlanded surface) holds the **entire** view on Reagent; a **judgment call** is decided with the author, then the **whole** view converts or the **whole** view holds. A converted `v/defview` has no ambient `subscribe`/`dispatch`, so a partial body does not run. Coherence over coverage. This is cardinal rule 2 in `SKILL.md`.

### L5 — Views only; name dataflow changes, never make them

The skill rewrites the view tier — hiccup, handlers, mounts, view-held state. It never edits events, subs, fx, machines, schemas, or routes. Where a view forces a dataflow change (a new `reg-sub` for a hoisted `r/track`, an event for a `r/cursor` write, app-db for a Form-2 flag), the skill *names* it for the author. This matters more under Freehand than it did on a substrate with a host-local cell: with no `local`, the Form-2 conversion path runs through app-db far more often, and it is still the author's to write.

### L6 — Incremental, closed-subtree passes

Migrate leaf → root, closing a subtree from the bottom up. Under Freehand this is a **hard** constraint rather than a preference: there is no outward React bridge, so a converted view can only be mounted by another Freehand view or by a root. Each pass ends compiling, rendering, and tested, so an interrupted migration resumes cleanly.

### L7 — The skill runs the compile/test gates; the programmer owns visual confirmation

The skill **discovers and runs the nearest safe noninteractive gate itself** (compile the subtree, run its tests) under the repo's trust-the-explicit-invoker `allowed-tools` baseline ([`skills/README.md` §Published-skill `allowed-tools` baseline](../../README.md#published-skill-allowed-tools-baseline-security-policy)) — verify-as-you-go, not an arbitrary executor. "Compiles" is necessary but not the done-bar, and it carries less weight than it would on a compile-first substrate: interpreted Freehand moves most view errors to run time by design. The done-bar for a subtree is compiles + tests pass + rendered.

### L8 — Migrate interpreted; never promote mid-flight

Freehand is interpreted by default and `{:compiled true}` is an opt-in promotion per declaration. The skill emits interpreted declarations only. Promotion is a post-migration performance decision on a hot leaf, taken with measurements; opting in mid-migration converts legal bodies into build failures for no benefit.

### L9 — Emit only what has shipped

Freehand's design corpus (the EP, the decision records, the draft guide) describes forms that are **not exported**: `local`, `effect`, `ref`, an outward React bridge, a trusted-markup verb, a read-only checker. The skill checks `spec/API.md` before writing a verb and names the gap when there isn't one. A skill that confidently emits a verb that does not exist is worse than no skill.

### L10 — Generic to ANY Reagent consumer app

The skill works on a consumer's Reagent codebase. Examples are abstract (`price`, `dropdown`, `item`) — never this repo's testbeds, paths, or naming. Give the generic mechanism; repo specifics are illustrative examples only.

### L11 — Findings stay local

Design exploration happens in `ai/` (gitignored); never committed. This skill's commit contains only `skills/reagent-migration/**` plus its index registration (`skills/README.md`, the docs mirror + nav).

## 4. File structure (locked)

```
skills/reagent-migration/
├── SKILL.md                       (router: framing + mental model + cardinal rules + tier routing + procedure + gotchas + done)
├── README.md                      (human-facing intro; the optional/second/pre-alpha positioning)
├── LICENSE                        (MIT)
├── package.json                   (npm metadata; `files` OMITS evals/ + spec/)
├── .claude-plugin/plugin.json     (Claude Code plugin metadata; status pre-alpha)
├── references/
│   ├── mental-model.md            (the Reagent→Freehand view shift)
│   ├── catalog-mechanical.md      (M-tier — "do this", before→after per rule)
│   ├── catalog-judgment.md        (D-tier — "here's how to DECIDE")
│   ├── catalog-reject.md          (R-tier — "don't migrate this / stay on Reagent, or wait")
│   ├── procedure.md               (incremental closed-subtree passes; the structural test surface)
│   └── gotchas.md                 (brackets-vs-parens, one-props-map, bare-symbol trap, whole-view coherence)
├── evals/
│   └── evals.json                 (trigger fixtures + behavioural fixtures across the M/D/R tiers)
└── spec/
    ├── design.md                  (this file — locked decisions)
    ├── inputs.md                  (the canonical inputs the skill leans on)
    └── authoring-prompt.md        (one-shot reauthor prompt)
```

`evals/` and `spec/` are authoring-time scaffolding — not part of the skill a consumer loads, so they are **not part of the distributable** (`package.json` `files` omits them). Every reference leaf stays one level deep from `SKILL.md`; each leaf ≤250 lines / ≤16 KB.

## 5. Where this diverges from `re-frame-migration`

- **Different migration.** `re-frame-migration` moves events/subs/db from v1 to v2 (`M-N`/`O-N` rules, the `migration/from-re-frame-v1/README.md` corpus). This skill moves *views* from Reagent to Freehand (`MIG-NN` rules). They compose: v1→v2 first, then this.
- **Fewer leaves.** The domain is narrower (view tier only), so three tier catalogues + three supporting leaves suffice.
- **Pre-alpha honesty is a first-class deliverable.** `catalog-reject.md` is not a footnote — the hold list is what keeps the migration honest about a view layer whose host boundary has not landed.

## 6. Open questions (deferred to Mike)

- **OQ1 — the MIG-13 auto-apply threshold** (the keyed-literal-`map` case): ship as an M sub-rule, or keep D? Deferred; the skill treats it as D (confirm the candidate `for`).
- **OQ2 — a runnable migrator?** Explicitly rejected for now — Mike ruled skill-only. If field use shows the M-tier rewrites are applied identically at scale, revisit as a separate bead.
