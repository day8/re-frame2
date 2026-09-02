# reagent-migration — Authoring Prompt

> **Skill-internal meta-doc.** A self-contained prompt that re-authors the
> `reagent-migration` skill from this `spec/` folder alone. Not part of the
> skill contract. For the skill contract, see [`SKILL.md`](../SKILL.md).

## The prompt

> *I'm re-authoring the `reagent-migration` skill at `skills/reagent-migration/`. The skill rewrites **Reagent view code into Hicasso** — `re-frame.hicasso`, aliased `h`, re-frame2's re-frame-native view layer. The VIEW rewrite is **AI judgment, not a codemod**; a source-text **reporter/fixer** for the `[:> …]` prop dialect does ship at `migration/reagent-to-hicasso/codemod/` and the skill runs it first.*
>
> *Read these first, in order:*
>
> *1. `skills/reagent-migration/spec/design.md` — the locked decisions (L1–L11, L8 retired). The load-bearing ones: **L2** (the rewrite is OPTIONAL and the skill must not overstate the need — with a first-class Reagent adapter an app keeps its view code and needs no rewrite to land on re-frame2, and Hicasso is pre-publication with no Maven coordinate), **L9** (emit only what has shipped, and READ THE DOOR — the draft guide teaches forms that do not exist), L1 (no view codemod, but run the reporter), L4 (whole view is the unit), L6 (closed subtrees, relaxed by `h/as-component`), L10 (generic to any Reagent app).*
> *2. `skills/reagent-migration/spec/inputs.md` — the inputs. The primary one is **`implementation/hicasso/src/`**, because Hicasso is unrostered: there is no `spec/API.md` section to check a verb against, so reading the source IS the check. `impl/intent.cljs`, `impl/codec.cljs`, `impl/slot.cljc`, `impl/state.cljc`, `impl/mount.cljs` decide the behaviours the catalogues teach. `docs/design/hicasso/**` including `draft-guide/` is a HAZARD — §4 of that file tabulates seven measured examples of it teaching a form that does not exist.*
> *3. `skills/re-frame-migration/` — the structural sibling. Match its SHAPE: `SKILL.md` router + `README.md` + the distribution triad (`LICENSE` / `package.json` / `.claude-plugin/plugin.json`) + `references/` leaves + `spec/` meta-docs + `evals/evals.json`. Match its front-matter format, voice, and cardinal-rules density.*
>
> *Then write the skill with the file structure locked in `design.md` §4: `SKILL.md` (router) + seven reference leaves (`mental-model`, `catalog-mechanical` (M-tier, before→after), `catalog-judgment` (D-tier, "how to DECIDE"), `catalog-reject` (R-tier, the honesty backbone — deliberately SHORT), `procedure` (reporter first, then incremental closed-subtree passes, plus the shipped test kit and `hm/shadow!`), `ssr-hydrate` (MIG-23's SSR-then-hydrate recipe, severed so a client-only migration never loads it), `gotchas`) + three `spec/` meta-docs + `evals/evals.json`.*
>
> ***The first thing `SKILL.md` does is establish whether it has a job.*** *Put the trade in a two-column table — what the rewrite buys against what it costs — and take an explicit yes. Both columns measured against shipped surface. Never imply the author should move.*
>
> *Cardinal rules to bake into `SKILL.md`: (1) the view rewrite is judgment, but run the reporter first; (2) the whole view is the unit — never half-migrate; (3) incremental, never big-bang; (4) cite `MIG-NN` ids, which name the Reagent CONSTRUCT not the destination; (5) views only — name dataflow changes; (6) emit only what has shipped, read the door; (7) the skill runs the noninteractive compile/test gates itself, the programmer owns the interactive visual confirmation.*
>
> *The four view shifts the mental model teaches: brackets mount and parens inline (`h/defview` mints a real React function component; a plain fn in head position is a loud error); deref-drop (`@(subscribe …)` → `(h/sub …)`, which returns the value and is the AMBIENT collector — legal in a `when`, a `for`, an inlined helper, recording an edge only where the read happens); handlers become DATA whose **shape** selects the behaviour (intent vector / key map / `h/event` / plain fn, with `::h/value` and `::h/checked` the only two markers and `::h/prevent` the only author-written reserved head — the navigate head is Hicasso-internal, minted by `h/route-link`, and navigation is `h/route-link` or an ordinary routing event); and the view holds NO state (no `local`, no `use-state`, no cell — app-db via `h/reg-state`, `forms/buffered-field`, or a React island with real hooks).*
>
> *Front-matter `description` is self-limiting and leads with the check: trigger on Reagent-view→Hicasso phrasing (`h/defview`, deref-drop, moving off Reagent hiccup, `r/atom`/`r/with-let`/`r/create-class`/`adapt-react-class`/the `[:>]` prop dialect), AND state the negatives: the v1→v2 migration is `re-frame-migration` and it COMPLETES, authoring is `re-frame2`, and staying on Reagent is fine.*
>
> *Voice: tight, declarative, recipe-shaped; full sentences over dash-chained fragments. Tables for rule lookups; code blocks for before→after shapes. Cite `MIG-NN` in every catalogue.*
>
> *Don't: ship or invoke a VIEW codemod; overstate the need (the Reagent adapter is first-class and Hicasso is pre-publication — say both); emit `h/fn`, a plain `merge` for caller attrs, or any other verb the draft guide teaches and the door does not export; carry a **Freehand or `re-frame.ui`** spelling across by analogy (`v/html`, `v/spread-safe`, `v/defbehavior`, `{:compiled true}`, `ui/local` — all belong to retired substrates); write `*.md` outside `skills/reagent-migration/` (except the index registration in `skills/README.md` + the docs mirror/nav); commit anything under `ai/`; use this repo's testbeds or paths as examples (stay generic); claim AI authorship in commits/PR.*
>
> *Open the PR titled `feat(skills): reagent-migration — Reagent→Hicasso view migration`. Body: the sibling shape matched, the M/D/R catalogue distilled (which rules), the two-tier framing and its exact wording, the reporter-first procedure, the per-tier evals, and which gates cover `skills/` and which do not. Surface OQ1/OQ2/OQ3 from `design.md` for Mike.*

## Notes on the reauthoring contract

- The prompt is a one-shot: feed it to a fresh session, it produces the skill.
- It assumes repo read access. **Verifying every emitted verb against
  `implementation/hicasso/src/` is the job, not an optional polish pass** — and
  there is no roster shortcut, because Hicasso is unrostered.
- It does not ask the session to verify the resulting skill's judgment calls —
  that verification is Mike's.

## When to re-author

- **A major Hicasso surface lands** — a data `:ref` spelling, a view-local
  state tier. It moves cases between catalogues; the server-render door did
  exactly that, moving MIG-23 out of R-tier. Reach for a full reauthor only when the *shape*
  of the skill changes, not for a surface that slots into the existing tiers.
- **The positioning changes** — Hicasso publishes a Maven coordinate, or the
  adapter story changes → design L2 changes; update this `spec/` folder first,
  then the skill.
- **A provisional spelling settles** (`hfn`→`h/event` (landed), `root!`→`mount!`) → edit the
  leaves; this is not a reauthor.

Otherwise, edit the leaves directly; reauthoring from scratch is for major
surface changes.
