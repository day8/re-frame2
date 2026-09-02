# reagent-migration — Inputs

> **Skill-internal meta-doc.** The canonical inputs the skill leans on — not
> part of the skill contract. Not loaded during normal operation; a
> re-authoring pass needs these to reproduce the leaves. For the skill
> contract, see [`SKILL.md`](../SKILL.md).

## 1. Primary input — the shipped Hicasso SOURCE

Hicasso is **pre-publication and unrostered**: there is no `spec/API.md` section
and no published API reference to check a verb against. So unlike its
predecessor, this skill's primary input is the source itself, and reading it is
the check rather than a polish pass.

- **`implementation/hicasso/src/re_frame/hicasso.cljc`** — the public door, and
  the whole of it. Three macros (`defview`, `event`, `defhost`) and the vars
  (`sub`, `error-boundary`, `reg-state`, `portal`, `route-link`,
  `as-element`, `as-component`, `mount!`, `hydrate!`, `render!`, `unmount!`).
  Its docstrings are unusually load-bearing — the four handler shapes, the
  `defhost` option roster, the `root!` opts and the held-open absences are all
  stated there.
- **The `impl/` namespaces beside it**, which is where a *behaviour* is decided:
  - `impl/intent.cljs` — the four `on-*` shapes, the two markers, the two
    reserved heads, the key map and its composition gate, the event-first
    argument law, every intent refusal id.
  - `impl/codec.cljs` — prop lowering, `:key`, `:ref`, the `[:>]`
    crossing, the reserved-name skips.
  - `impl/slot.cljc` — the canonical prop-name rule (the MIG-11 table). Shared
    with the codemod, which is why the two cannot drift.
  - `impl/state.cljc` — `reg-state`'s signature, address shape and key rules.
  - `impl/mount.cljs` — `root!` / `render!` / `unmount!` arities and the
    hot-reload idiom.
  - `impl/controlled.cljs` — what "controlled" actually covers (input/textarea,
    not select) and `::h/revision`.
- **The optional modules** — `forms.cljs`, `motion.cljs`, `overlay.cljs`,
  `native.cljc`. Each names its own public surface; `native.cljc` classifies its
  vars explicitly.
- **The test kit** — `implementation/hicasso/test_kit/src/re_frame/hicasso/`
  `test.cljs` and `test/mounted.cljs`. `hm/shadow!` is the migration's own
  instrument and it ships.
- **`implementation/hicasso/test/**`** — the shipped witnesses. When a docstring
  and a test disagree about a call shape, the test is what runs. The
  `consumer_app.cljs` exemplar is the authoritative boot sequence.

## 2. The migration tool

- **`migration/reagent-to-hicasso/codemod/`** — the reporter and `[:>]` fixer,
  and its `README.md` is the best single description of what Reagent's prop
  conversion did that Hicasso's does not. The `test/corpus/` directory **is** its
  spec: each case carries an input, the expected output and the expected report.
  Read a corpus case rather than trusting prose about a W-rule.

## 3. The two-tier framing's evidence

L2 is the skill's most load-bearing claim, so its sources are named:

- **`implementation/adapters/reagent/README.md`** — "a first-class,
  actively-supported view substrate for re-frame2, and the canonical adapter the
  reference test suite runs against".
- **`CHANGELOG.md`** — `day8/re-frame2-reagent` as "the default browser
  substrate"; the thirteen published coordinates, which do **not** include
  Hicasso.
- **`implementation/hicasso/deps.edn`** — the pre-publication statement in its
  own words: no Maven coordinate, absent from the lockstep array and the deploy
  matrix.
- **`skills/re-frame-migration/`** — what the required first step actually does,
  and that it leaves views on Reagent.

## 4. Where the design corpus is a HAZARD, not an input

`docs/design/hicasso/**` describes the design and states forms that do not
exist. Four measured examples, **re-measured 2026-09-02** — and the two verdicts
that moved are the argument for re-measuring rather than quoting:

| Stated | Reality at tip |
|---|---|
| an `h/fn` spelling | **fixed.** Swept to `h/event` on 2026-08-15; `docs/core/hicasso/api-reference.md` carries the ledger row and no page teaches it |
| a Maven coordinate on the installation page | **fixed, and by removal.** `00-installation.md` now states `day8/re-frame2-hicasso` is not published with no date at which it will be, and resolves it by `:local/root`. There is no coordinate printed to fail — an answer saying so is CORRECT |
| key maps "valid only at `:on-key-down`/`:on-key-up`" | **stands.** `impl/intent.cljs`'s `lower-prop` reaches `key-map-handler` at every `event-prop?` position; `docs/core/hicasso/03-events-as-data.md` still states the restriction |
| "binding `:value` to a contenteditable throws at the source" | **stands.** No contenteditable guard exists under `implementation/hicasso/src/`, and the id `troubleshooting.md` names for it is in no source or spec row |

Two structural notes. **`draft-guide/` is no longer the guide**: under rf2-0yp7w
that corpus shipped to `docs/core/hicasso/`, leaving one rewrite-audit note in
the design tree — so a hazard here can now be in the *published* guide, as two
of the four rows are, and "it was only the draft" no longer sorts true from
false. And the hazard is the whole `docs/design/**` tree — `mkdocs.yml` excludes
it from the published site precisely because it is a working RECORD — so naming
subdirectories only invites the list to go stale, which is what happened to the
`draft-guide/` naming above.

The guide is still worth **reading**: it is the best available account of intent,
and its migration chapter and its census/fixer description are accurate because
they were written against the shipped tool. Read it for *why*; read the source
for *what*.

## 5. Tertiary inputs (shape the discipline, not quoted)

- **The four-pillar design rationale** — reproduced in `design.md` §2 so this
  folder is self-contained.
- **`skills/re-frame-migration/`** — the closest structural sibling. Voice,
  density, front-matter shape and the "cardinal rules" style all mirror it.
- **`skills/re-frame2/SKILL.md`** — the canonical authoring-pattern example.

## 6. What the skill does NOT consume

- **The v1→v2 corpus** (`migration/from-re-frame-v1/README.md`, the `M-N`/`O-N`
  rules) — that is the *other* migration, the required first step.
- **`examples/**`** — worked examples are for authoring, not this migration.
- **Freehand and `re-frame.ui`** — both retired, and both trees were **removed**
  on 2026-08-16 (rf2-0yp7w); their API pages and guide went with them, so there
  is nothing left to read and nothing to consume. `spec/004-Views.md`, which
  carried the last of that prose, was deleted on 2026-08-18 (rf2-h89ri). Its
  verbs (`v/html`, `v/spread-safe`, `v/defbehavior`, `{:compiled true}`)
  described a *different substrate* and must never be carried across by
  analogy — the single most likely way a future edit reintroduces a wrong
  spelling.

## 7. Update procedure

1. **A Hicasso surface LANDS** → move its cases out of `catalog-reject.md` into
   the mechanical or judgment catalogue with the now-real target, and re-check
   `procedure.md`'s gate list. This has already fired once: the server-render
   door landed and MIG-23 moved from R to D. The remaining candidate is a data
   `:ref` spelling (currently reserved and refused).
2. **A rule's tier changes** (M↔D↔R) → move its treatment between the
   catalogues, and re-check `procedure.md`'s gate list.
3. **A new construct needs a rule** → add a before→after (M), a decision (D) or a
   hold (R) to the matching catalogue, and add an eval if it exercises a new
   class.
4. **A provisional spelling settles** — `hfn`→`h/event` (landed), `root!`→`mount!` — →
   re-verify every emitted verb against the door. The naming ledger holds several
   of these open deliberately, so this is a *when*, not an *if*.
5. **Hicasso publishes a coordinate** → design L2's honesty clauses and
   `procedure.md`'s pre-flight check 3 all change. Update `spec/` first.
