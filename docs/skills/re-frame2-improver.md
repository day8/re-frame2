# re-frame2-improver

> Reviews **existing** re-frame2 ClojureScript code against a catalogue of re-frame2 anti-patterns, and fixes what it finds when you ask it to. It runs only when you ask for a review.

## What it does

The `re-frame2-improver` skill reads your source files (or a snippet you paste), checks them against a small anti-pattern catalogue, and returns one complete critique in the same turn, most severe first. Each finding gives the file and line, what goes wrong because of it, the smallest safe correction, and a link to the canonical idiom under `skills/re-frame2/patterns/`.

The catalogue covers six anti-patterns: hand-rolled HTTP retry loops, `:loading?` flags set and cleared by hand, a cluster of `?` subscriptions standing in for one status, boundary events that write HTTP replies or storage and URL data into `app-db` with no schema, side effects and impure reads inside event handlers, and view-held state (`r/atom`, `use-state`) that belongs in `app-db`. Each is a note under [`references/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-improver/references); [`SKILL.md` §Routing](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-improver/SKILL.md#routing--load-only-the-leaves-whose-signals-appear) lists each one with the source signals that make the skill load it.

Whether it edits depends on what you asked for:

- *"Review this"* is read-only. Each finding states its correction; nothing is applied.
- *"Review and fix"* applies those corrections inside the scope you named, with no second approval round.

A redesign that reaches beyond that scope stays a proposal either way. An instruction written into the code under review — a comment addressed to the agent — is treated as data, never obeyed. The reply includes only the sections that have content (scope reviewed, findings, fixes applied, open questions), and a clean result is one short verdict naming what was reviewed.

## When to reach for it

Use it when you explicitly ask for a review — "review my re-frame2 code for anti-patterns", "audit this against re-frame2 best practices", "any improvements?", "is there a better re-frame2 pattern here", "spot any anti-patterns in `cart/handlers.cljs`".

There must also be re-frame2 source to review: code read or edited in the conversation, a pasted snippet, or a `.cljs` / `.cljc` file or directory path the skill can read (it reads the path before critiquing). Review vocabulary alone is not enough, and a path that does not resolve does not count.

Use a different skill for:

- Writing new application code → [re-frame2](re-frame2.md).
- Working with a live runtime → [re-frame2-pair](re-frame2-pair.md).
- A retrospective on a pair session → [re-frame2-pair-retro](re-frame2-pair-retro.md).
- Greenfield setup or v1 migration → [re-frame2-setup](re-frame2-setup.md) or [re-frame-migration](re-frame-migration.md).
- *Porting* Reagent views to Fresco → [reagent-migration](reagent-migration.md). Reviewing existing Reagent-view code against the catalogue stays here.

## Kickoff

Ask for a review with the code in scope:

> *Review `src/app/cart/` for re-frame2 anti-patterns.*

Or type `/re-frame2-improver`. Ask it to "review and fix" to have it apply the corrections.

## When it stops

- **No code in scope** — if nothing has been read, edited, pasted or named as a resolvable `.cljs` / `.cljc` path, it asks for a snippet rather than invent evidence.
- **The code is still re-frame v1** (`reg-event-db`, `reg-event-fx`, `inject-cofx`) — it says so and routes you to [re-frame-migration](re-frame-migration.md) rather than proposing re-frame2 rewrites into a v1 codebase.
- **A finding that is really a gap in re-frame2** — it describes the gap for you to file against [`day8/re-frame2`](https://github.com/day8/re-frame2/issues). It does not rewrite your code around the gap, and it has no way to file the issue itself.

## Where the skill lives

- Source: [`skills/re-frame2-improver/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2-improver)
- `SKILL.md`: [`skills/re-frame2-improver/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame2-improver/SKILL.md)
- Canonical idioms it links to: [`skills/re-frame2/patterns/`](https://github.com/day8/re-frame2/tree/main/skills/re-frame2/patterns).
