# reagent-fresco-migration — Inputs

> **Skill-internal meta-doc.** The canonical inputs the skill leans on — not
> part of the skill contract. Not loaded during normal operation; a
> re-authoring pass needs these to reproduce the leaves. For the skill
> contract, see [`SKILL.md`](../SKILL.md).

## 1. Primary input — the shipped Fresco SOURCE

Fresco's door is **rostered in `spec/api-manifest.edn`** (graded by `lint.yml`'s
`api-manifest` job), but `spec/API.md` carries no Fresco section, so a manifest
row names a var and nothing there teaches its call shape. The manifest plus the
door source are the check, and reading the source is the check rather than a
polish pass.

- **`implementation/fresco/src/re_frame/fresco.cljc`** — the public door, and
  the whole of it. Three macros (`defview`, `event`, `defhost`) and the vars
  (`sub`, `error-boundary`, `reg-state`, `portal`, `route-link`,
  `as-element`, `as-component`, `frame-root`, `frame-provider`,
  `client-root`, `render!`, `unmount!`) — the fifteen names the manifest
  rosters. Its docstrings are unusually load-bearing — the four handler
  shapes, the `defhost` option roster, the `render!` root options and the
  held-open absences are all stated there.
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
  `native.cljc`, `server.cljs`, `substrate.cljs`. Each names its own public
  surface; `native.cljc` classifies its vars explicitly. (`tool.cljs` and
  `evidence.cljs` beside them are the devtools' reader door, which no view
  writes.) `substrate.cljs` is the
  one `end-state.md` reads: Fresco's own adapter, and the reason
  the adapter question reopens once no Reagent view is left.
- **The test kit** — `implementation/fresco/test_kit/src/re_frame/fresco/`
  `test.cljs`, `test/mounted.cljs`, `test/forms.cljs`, `test/runtime.cljs` and
  `test/server.cljs`. `hm/shadow!` is the migration's own instrument and it
  ships.
- **`implementation/fresco/test/**`** — the shipped witnesses. When a docstring
  and a test disagree about a call shape, the test is what runs. The
  `consumer_app.cljs` exemplar is the authoritative boot sequence.

## 2. The migration tool

- **`migration/reagent-to-fresco/codemod/`** — the reporter and `[:>]` fixer,
  and its `README.md` is the best single description of what Reagent's prop
  conversion did that Fresco's does not. The `test/corpus/` directory **is** its
  spec: each case carries an input, the expected output and the expected report.
  Read a corpus case rather than trusting prose about a W-rule.

## 3. The two-tier framing's evidence

L2 is the skill's most load-bearing claim, so its sources are named:

- **`implementation/adapters/reagent/README.md`** — "a first-class,
  actively-supported view substrate for re-frame2, and the canonical adapter the
  reference test suite runs against".
- **`CHANGELOG.md`** — `day8/re-frame2-reagent` as "the default browser
  substrate"; §What a release publishes lists the fourteen coordinates a `v*`
  tag publishes, including Fresco beside the Reagent adapter.
- **`implementation/fresco/deps.edn`** and
  **`.github/scripts/verify-version-lockstep.sh`** — the publication statement
  in Fresco's own words (PUBLISHED, shipping from a release stage of its own)
  and the lockstep `ARTEFACTS` inventory that enrols it. Measure the release
  set there, never from a guide page.
- **`skills/re-frame-migration/`** — what the required first step actually does,
  and that it leaves views on Reagent.

## 4. Where the design corpus is a HAZARD, not an input

`docs/design/fresco/**` describes the design and states forms that do not
exist. Measured examples — re-measure before quoting any of them, because a
verdict moves whenever the source or the page does:

| Stated | Reality in the source |
|---|---|
| an `h/fn` callback spelling, in design notes under `docs/design/fresco/` | the door exports no `h/fn`; the callback form is `h/event`, and no page under `docs/core/fresco/` names `h/fn` |
| "binding `:value` to a contenteditable throws at the source" | no contenteditable guard exists under `implementation/fresco/src/`, and the id the design corpus reserves for it, `:rf.error/fresco-contenteditable-not-controllable`, is in no source or spec row; `docs/core/fresco/04-controlled-inputs.md` says a `:value` on one passes to React untouched and raises nothing |

A page that agrees with the source is not a hazard: key maps "valid only at
`:on-key-down`/`:on-key-up`" matches the source, because `key-map-handler`
reads `.key` first and raises `:rf.error/fresco-intent-needs-the-event` where
the event carries none — which is what `docs/core/fresco/03-events-as-data.md`
says.

Two structural notes. **The published guide under `docs/core/fresco/` is a
page as well**, so a hazard can sit there as easily as in the design tree, and
"published" does not sort true from false. And the hazard is the whole
`docs/design/**` tree — `mkdocs.yml` excludes it from the published site
because it is a working RECORD — so name the tree rather than its
subdirectories, which move.

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
- **Freehand and `re-frame.ui`** — removed substrates, with no tree, API page
  or guide left to read. Their verbs (`v/html`, `v/spread-safe`,
  `v/defbehavior`, `{:compiled true}`) describe a *different substrate* and must
  never be carried across by analogy — the single most likely way an edit
  introduces a wrong spelling.

## 7. Update procedure

1. **A Fresco surface LANDS** → move its cases out of `catalog-reject.md` into
   the mechanical or judgment catalogue with the now-real target, and re-check
   `procedure.md`'s gate list. A vector at `:ref` is not such a surface: it
   crosses to React as data, and the callback function is the only ref
   spelling.
2. **A rule's tier changes** (M↔D↔R) → move its treatment between the
   catalogues, and re-check `procedure.md`'s gate list.
3. **A new construct needs a rule** → add a before→after (M), a decision (D) or a
   hold (R) to the matching catalogue, and add an eval if it exercises a new
   class.
4. **A provisional spelling settles** → re-verify every emitted verb against
   the door. The naming ledger holds several
   of these open deliberately, so this is a *when*, not an *if*.
5. **The release set changes** — Fresco leaves it, or Fresco and the adapters
   stop shipping at one version → design L2's honesty clauses and
   `procedure.md`'s pre-flight check 3 change. Update `spec/` first. A first
   `v*` tag is NOT such a change: every artefact gets its coordinate at once.
