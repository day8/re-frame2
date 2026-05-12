# 18 — From re-frame v1

> **If you're skipping this chapter, the upshot:** re-frame2 is a small breaking-change set with an automated migration skill (`re-frame-migration`). Bump the deps, run the skill, fix a handful of compile errors — that's the median migration.

This is the appendix-shaped chapter for migrators. It tells you which deps move, which skill drives the work, and the broad categories of breakage to expect. The exhaustive rule list lives in [`spec/MIGRATION.md`](../../spec/MIGRATION.md) (40+ M- and O-rules) and is consumed by the migration skill; this chapter does not duplicate it.

If you don't have a v1 codebase to bring across, skip to [19 — Adapters](19-adapters.md) and [20 — Where to go next](20-where-next.md).

## Deps to update

re-frame2 is **pay-as-you-go** — capabilities ship as separate artefacts so unused ones don't bundle. The shape of the migration is therefore:

1. **Swap the core coord.** Remove `re-frame/re-frame`. Add `day8/re-frame2`.
2. **Add a substrate adapter** for whichever view library you use:
    - Reagent → `day8/re-frame2-reagent` *(target Reagent v2)*.
    - UIx / Helix → use the matching adapter if you've already moved off Reagent.
3. **Add per-feature artefacts only for features you actually use.** Don't add them all "to be safe" — the migration skill will tell you which ones the codebase trips. The current split is `day8/re-frame2-{machines, flows, routing, http, ssr, schemas, epoch}`.
4. **Bump Reagent to v2** if you're on Reagent (re-frame2's reference targets Reagent 2).
5. **Don't bump other deps in the same change.** Keep React, shadow-cljs, etc. on their current versions until the migration settles — separate failure modes are easier to debug separately.

The skill handles every part of this for you; the list above is so you know what's coming.

## The skill that drives the migration

The migration is automated. The primary entry point is the **`re-frame-migration`** skill that ships in this repo:

[`skills/re-frame-migration/`](../../skills/re-frame-migration/) — the Claude Code skill.

It walks six phases (orient → bump → sweep → verify → optional modernisations → report), applies the mechanical (Type A) rewrites unprompted, and stops at every judgment-call (Type B) site to ask. `spec/MIGRATION.md` is its source of truth.

A paste-ready kickoff prompt lives at [`skills/re-frame-migration/reference/kickoff-prompt.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/reference/kickoff-prompt.md). The shape of the workflow:

1. Open a fresh Claude Code session in the root of your v1 project.
2. Paste the kickoff prompt. The session loads the skill and walks the phases autonomously.
3. Answer questions at the Type B checkpoints — the agent explains the risk and waits for your call before rewriting.
4. Run your test suite. The agent re-verifies and produces a migration report.

The skill is the recommended path for any project larger than a toy. The fallback — read `spec/MIGRATION.md` and walk the rules by hand — works for small codebases or first-time-readers who want to see the surface up close.

## Problems you might run into

These are the broad categories of breakage. The skill identifies and resolves them; this section is for orientation when you're reading a diff and asking "what kind of thing am I looking at?"

- **Registrar imports.** Code that requires `re-frame.db`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar`, or `re-frame.alpha` directly is out of contract and gets rewritten or flagged. The single-import contract in v2 is `(:require [re-frame.core :as rf])`.
- **Removed surfaces.** `dispatch-with` / `dispatch-sync-with` (replaced by two-arg `dispatch` with an opts map), `reg-global-interceptor` (frame-scoped interceptors only in v2), `reg-sub-raw` (use `reg-sub` or the substrate adapter), `^:flush-dom` event metadata (use `:dispatch-later {:ms 0}`).
- **Removed interceptors.** `debug`, `trim-v`, `on-changes`, `enrich`, and `after` are gone. Each has a defined replacement (trace, the canonical event shape, flows, schemas, `->interceptor`). The retained set is `inject-cofx`, `path`, `unwrap`, and the `->interceptor` primitive.
- **Effect-map shape.** Top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` shorthands fold into the `:fx` vector. The `:db` slot is unchanged.
- **Test harness rename.** `re-frame-test` becomes `re-frame.test-support`. The ns moves; the test bodies usually don't need to change.
- **View-rendering boundary.** Plain Reagent fns continue to work but get a runtime warning if rendered under a non-default frame's subtree. Single-frame apps don't see the warning. `reg-view` adoption is opt-in modernisation, not required for migration.
- **App-db access.** Direct access to `re-frame.db/app-db` was always off-contract; in v2 it's even more so. The accessor is `(rf/get-frame-db :rf/default)` and returns a plain map.

If a failure doesn't match any of the above, the skill surfaces it for human review rather than guessing. The cardinal rule is *don't invent migration rules*.

### Migrating an HTTP layer

A concrete instance of "problems you might run into": v1 codebases that registered their own `:http` fx — or used `re-frame-http-fx`, `re-frame-fetch-fx`, or one of their cousins — migrate onto `:rf.http/managed` (see [10 — Doing HTTP requests](10-doing-http-requests.md)). The skill recognises the shape; the rewrite is mechanical (Type A):

1. Add the `day8/re-frame2-http` artefact and `(:require [re-frame.http-managed])` from the namespaces that issue requests (per [`MIGRATION.md` §M-31](../../spec/MIGRATION.md#m-31-managed-http-spec-014-ships-in-a-separate-artefact--day8re-frame2-http)).
2. Replace `[:http {:url ... :on-success ... :on-error ...}]` fx vectors with `[:rf.http/managed {:request {:url ...} :on-success ... :on-failure ...}]`. Wire-shape keys (`:method`, `:url`, `:body`, `:headers`, `:params`) move inside `:request`.
3. Rename `:on-error` → `:on-failure`. The reply payload appends as the last argument; destructure `{:keys [value]}` for success, `{:keys [failure]}` for failure.
4. Adopt the closed `:rf.http/*` failure category set in error-handling branches — code that branched on `(:status err)` becomes branching on `(:kind failure)`.
5. (Optional modernisation, not required for migration.) Convert per-call success handlers to default reply addressing if the pre-request and post-reply logic naturally co-locate at one event id.

The skill applies steps 1–4 unprompted and stops at step 5 for review.

## A note on the tooling

`re-frame-10x` — the v1 devtools panel — has been renamed and reimplemented as **`re-frame-causa`** for v2. It is **not** the v1 10x ported to v2; it's a from-scratch reimplementation against re-frame2's own trace bus and epoch-history surfaces (see [15 — Tooling](15-devtools-and-pair-tools.md)). If your v1 project depended on the 10x panel during development, the v2 equivalent is causa, not 10x. The mental model — events, subs, app-db diff, time-travel — carries over; the wiring underneath does not.

## Where to read next

- [`skills/re-frame-migration/SKILL.md`](https://github.com/day8/re-frame2/blob/main/skills/re-frame-migration/SKILL.md) — the skill that drives the migration end-to-end.
- [`spec/MIGRATION.md`](../../spec/MIGRATION.md) — the authoritative rule list. The skill consumes this directly.
- [19 — Adapters](19-adapters.md) — substrate-agnostic story, the `init!` call shape, the three adapter packages, the slim-Reagent option.
- [20 — Where to go next](20-where-next.md) — once the migration settles, where to head next.
