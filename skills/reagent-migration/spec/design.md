# reagent-migration — Design

> **Skill-internal meta-doc.** Design rationale + locked decisions for the
> `reagent-migration` skill itself — not part of the user-facing or AI-facing
> skill contract. Not loaded during normal skill operation; it exists to
> re-author the skill from inputs. For the skill contract, see
> [`SKILL.md`](../SKILL.md).

A future agent could re-author this skill from this folder alone.

## 1. Goal

Help a programmer migrate **Reagent view code to re-frame2's `re-frame.ui`** compiled-view substrate, with the smallest correct diff and honest scoping. The skill is **knowledge Claude reads and applies with judgment** — it is explicitly **not** a codemod. Mike shelved the rewrite-clj tool (`tools/ui-migrator`, PR #6541) in favour of an AI skill that *reasons* on ambiguous views rather than emitting a flag.

## 2. Pillars (locked)

The same four pillars as the skill family, adapted to this domain.

1. **Correctness** — the mechanical rewrites are recipe-shaped and cite a `MIG-NN` id; the judgment cases are reasoned, not guessed. The skill **runs the project's own noninteractive compile/test gates** (verify-as-you-go, under the repo's trust-the-explicit-invoker `allowed-tools` baseline) but leaves the interactive visual confirmation — booting and eyeballing the render — to the programmer when no connected runtime exists.
2. **Idiomaticness** — the rewrite targets are verified against the shipped `re-frame.ui` surface (Spec 004; `implementation/ui/src/re_frame/ui.cljc`). The skill emits no staged form for a capability that hasn't landed.
3. **Context economy** — `SKILL.md` is a router; the three tier catalogues + mental-model + procedure + gotchas leaves load on demand.
4. **Assume training knowledge** — the agent knows Reagent, hiccup, React, re-frame2 events/subs. The skill teaches the **Reagent-view → re-frame.ui binding**: which construct is a mechanical rewrite, which is a judgment call, which is a hold.

## 3. Locked decisions

Preserve these unless Mike explicitly unlocks them.

### L1 — It is an AI skill, NOT a codemod

No rewrite-clj tool ships or is invoked. The `tools/ui-migrator` rewrite-clj approach was shelved. The skill's value on ambiguous views is that it **reasons** about the shape rather than printing a mechanical flag. If the skill ever appears to need a companion tool, **stop and ask Mike** — he ruled skill-only.

### L2 — OPTIONAL, SECOND, EXPERIMENTAL (the load-bearing framing)

The migration journey is (1) re-frame v1→v2 [the required foundation, the `re-frame-migration` skill], then (2) OPTIONALLY Reagent→re-frame.ui [this skill]. re-frame.ui is **experimental** and the skill says so plainly. **Staying on Reagent views is a first-class, fully-supported choice.** The "when to use" is narrow and self-limiting: *already on re-frame2 AND specifically wants to trial the experimental substrate.* A reviewer reading `SKILL.md` must not come away thinking re-frame.ui is required or production-ready. This framing is not decoration — it is the point.

### L3 — The MIG rule table is the shared vocabulary, not a duplicated corpus

The `MIG-01…35` ids are the framework's own Reagent→re-frame.ui rule numbering (the canonical W1/W2/S6 rulebook). The skill uses the ids as a shared vocabulary so a rewrite is auditable, but it does **not** ship or re-host the normative rule table — it distils the rules into M/D/R guidance + before→after examples. If the framework's rule table and this skill disagree on a rule's mechanics, the framework wins.

### L4 — The whole view is the unit of migration

Never half-migrate a view. A **reject or unshipped capability** holds the **entire** view on Reagent; a **judgment call** is decided with the author, then the **whole** view converts or the **whole** view holds — never a partial body (which neither compiles nor runs). Coherence over coverage. This is cardinal rule 2 in `SKILL.md`.

### L5 — Views only; name dataflow changes, never make them

The skill rewrites the view tier — hiccup, handlers, mounts, view-local state. It never edits events, subs, fx, machines, schemas, or routes. Where a view forces a dataflow change (a new `reg-sub` for a hoisted `r/track`, an event for a `r/cursor` write, app-db for product-meaning Form-2 state), the skill *names* it for the author. This mirrors the shelved tool's §Non-goals ("no cross-file moves; dataflow untouched").

### L6 — Incremental, closed-subtree passes

Migrate leaf → root, closing a subtree from the bottom up — the recommended default now the outward `ui/->react` bridge has shipped: it minimises `ui/->react` boundary wrappers and keeps subtrees pure `ui`, though a converted `defview` can also be consumed by a Reagent parent through the bridge. Each pass ends compiling, rendering, and tested, so an interrupted migration resumes cleanly.

### L7 — The skill runs the compile/test gates; the programmer owns visual confirmation

The skill **discovers and runs the nearest safe noninteractive gate itself** (compile the subtree, run its tests) under the repo's trust-the-explicit-invoker `allowed-tools` baseline ([`skills/README.md` §Published-skill `allowed-tools` baseline](../../README.md#published-skill-allowed-tools-baseline-security-policy)) — verify-as-you-go, not an arbitrary executor (no `Bash(*)`, no migration machinery). "Compiles" is necessary but not the done-bar — a converted subtree can compile and fail only at render (a `MIG-35` introspection call, a converted view called from unconverted Reagent). The genuinely-interactive step — booting a dev build and eyeballing the render — stays with the programmer when no connected browser/runtime exists. The done-bar for a subtree is compiles + tests pass + rendered.

### L8 — Generic to ANY Reagent consumer app

The skill works on a consumer's Reagent codebase. Examples are abstract (`price`, `dropdown`, `item`) — never this repo's testbeds, paths, or naming. Per the standing rule: give the generic mechanism; repo-specifics are examples only.

### L9 — Findings stay local

Design exploration happens in `ai/` (gitignored); never committed. This skill's commit contains only `skills/reagent-migration/**` plus its index registration (`skills/README.md`, the docs mirror + nav). The salvage sources (the `ai/findings` prep table, the shelved tool's fixtures) are READ, never copied or committed.

## 4. File structure (locked)

```
skills/reagent-migration/
├── SKILL.md                       (router: framing + mental model + cardinal rules + tier routing + procedure + gotchas + done)
├── README.md                      (human-facing intro; the optional/second/experimental positioning)
├── LICENSE                        (MIT)
├── package.json                   (npm metadata; `files` OMITS evals/ + spec/)
├── .claude-plugin/plugin.json     (Claude Code plugin metadata; status pre-alpha)
├── references/
│   ├── mental-model.md            (the re-frame v1→re-frame.ui view shift — the one thing to internalise)
│   ├── catalog-mechanical.md      (M-tier — "do this", before→after per rule)
│   ├── catalog-judgment.md        (D-tier — "here's how to DECIDE")
│   ├── catalog-reject.md          (R-tier — "don't migrate this / stay on Reagent, or wait" — the honesty backbone)
│   ├── procedure.md               (incremental, closed-subtree passes)
│   └── gotchas.md                 (bare-symbol trap, whole-view coherence, keyed-child, dynamic heads, staged-gap trap)
├── evals/
│   └── evals.json                 (trigger fixtures + behavioural fixtures across the M/D/R tiers)
└── spec/
    ├── design.md                  (this file — locked decisions)
    ├── inputs.md                  (the canonical inputs the skill leans on)
    └── authoring-prompt.md        (one-shot reauthor prompt)
```

`evals/` and `spec/` are authoring-time scaffolding — not part of the skill a consumer loads, so they are **not part of the distributable** (`package.json` `files` omits them). Every reference leaf stays one level deep from `SKILL.md` (no SKILL → A → B chains).

## 5. Where this diverges from `re-frame-migration`

- **Different migration.** `re-frame-migration` moves events/subs/db from v1 to v2 (`M-N`/`O-N` rules, the `migration/from-re-frame-v1/README.md` corpus). This skill moves *views* from Reagent to re-frame.ui (`MIG-NN` rules). They compose: v1→v2 first, then this.
- **Fewer leaves.** The domain is narrower (view tier only), so three tier catalogues + three supporting leaves suffice — no floor-gate, no add-on-library conversions, no per-feature-artefact matrix.
- **Experimental honesty is a first-class deliverable.** `catalog-reject.md` is not a footnote — the R-tier / capability-gap list is what keeps the migration honest about an experimental substrate.

## 6. Open questions (deferred to Mike)

- **OQ1 — auto-apply threshold for MIG-13** (the keyed-literal-`map` case): ship as an M sub-rule, or keep D? Deferred; the skill treats it as D (confirm the candidate `for`).
- **OQ2 — a runnable `migrate.bb`?** Explicitly rejected for now — Mike ruled skill-only. If field use shows the M-tier rewrites are applied identically at scale, revisit as a separate bead (not this one).
