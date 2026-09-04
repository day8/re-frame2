# The naming packet — every Hicasso name, defaults applied, one sitting overrides

**Published by `rf2-hic-065`.** This is the single consolidation point the whole
programme has been deferring names to. [`naming-ledger.md`](naming-ledger.md) is the
row-by-row record and stays the place a new question is appended; this page is what the
operator reads, and what `rf2-hic-066` sweeps from.

## How this works, and why nothing waits

Every row below carries a recommendation that is **applied as a default at publication**.
`rf2-hic-066` sweeps them at once, before `rf2-hic-060` and `rf2-hic-068` write the docs,
because a rename is cheapest before the reference pages exist. The operator's one sitting
then overrides any row **asynchronously**: an override lands in the ledger's override
column, `rf2-hic-066` re-runs as a diff sweep over just that row, and `rf2-hic-090`
recertifies. A late override costs a bounded diff sweep; it never costs a stall.

**To override a row**: name its number and the spelling you want. Nothing else is needed —
no rationale, no form. "Rows 19 and 29 as recommended, row 22 fold into the door instead"
is a complete instruction, and the mayor records it.

**A recommendation is a default, not a ruling.** Five rows carry actual operator rulings
(§2) and are not up for re-decision. Everything else in §3 is this packet's judgement and
is overturnable at no cost beyond the diff sweep.

## 1. Standing at publication

| Measure | Count |
|---|---|
| Ledger rows carried into the packet | 46 |
| Rows already RULED by the operator | 5 (§2) |
| Rows already APPLIED or SHIPPED before the packet | 5 (§2) |
| Rows recommended here as defaults | 36 (§3) |
| Public names in `implementation/hicasso`, measured | 105 (§4) |
| Public names the ledger did **not** carry | 60 (§4) |
| New ledger rows the census forces | 8 (§5, rows 47–54) |
| Rows that STOP as semantic, and file rather than sweep | 2 (§6) |

**Zero rows are left open.** Every one of the 54 rows has a disposition a sweep can act on.

## 2. Settled before the packet — recorded, not re-opened

### 2.1 Operator rulings (Mike, 2026-08-11)

These are rulings, not defaults. The packet records them; `rf2-hic-066` applies them in the
one sweep. The sitting's agenda is the packet residue, not these.

| # | Surface | Ruling | What `rf2-hic-066` does | Measured sweep cost |
|---|---|---|---|---|
| 1 | the one callback macro | `hfn` → **`h/event`**. `h/handler` rejected as a cross-adaptor false friend | rename the door macro and the corpus; **plus** the semantic residue in §6.1 | `h/fn` 251 occurrences / 61 files; `hfn` 205 / 56 |
| 13 | root lifecycle constructors | `root!` → **`mount!`**; `hydrate-root!` → **`hydrate!`**. The rest of the door stands as landed | rename two constructors; `render!`/`unmount!` untouched | `h/root!` 35 / 20; `hydrate-root!` 144 / 35. The guide already teaches `h/mount!` (12) against `h/root!` (1) |
| 18 | `hframe` | **RETIRE** in favour of core `rf/current-frame-id` + zero-arity `rf/capture-frame` admitted during a Hicasso body | **STOPS — semantic.** See §6.2 · **[Amended 2026-09-04, `rf2-87iu`: the stop is EXECUTED, and this cell is the last record still reading as though it stands.]** `rf2-t32wg` was ruled option C by the operator on 2026-08-30 and executed as `rf2-6c12m.13`, PR #8784: one semantic rule in core, `hframe` and its `h/` alias deleted with no compatibility alias. `hframe` has **zero** occurrences under `implementation/hicasso/src/` at `main`@`4f54988b07` — measured against a control of 37 for `defview` in the same tree, so the zero is an absence and not a failed probe. [`naming-ledger.md`](naming-ledger.md) row 18 already reads LANDED; this page did not. | `hframe` 124 / 30; `h/frame` 118 / 19 |
| 24 | mounted test facade re-render verb | **`hm/rerender!`**. `render!` rejected — it collides with the product facade's `h/render!` (row 13) | rename the shipped `hm/render!` | `hm/render!` 5 / 4. The guide already types `hm/rerender!` (1) against `hm/render!` (0) |
| 31 | motion respellings | stand **as taught** — `motion/presence` head, `::motion/mounting` / `::motion/unmounting` override keys | nothing on the guide side; the engine respells with row 5's namespace move | — |

### 2.2 Applied or shipped before the packet

| # | Surface | State | Why it landed early |
|---|---|---|---|
| 16 | `re-frame.hicasso.forms` | **SHIPPED** (`rf2-sh56`) | Operator ruling, 2026-08-12: the forms module is V0 scope. The name was never in question — it is the spelling `05-forms.md`'s `:require` already teaches |
| 21 | `:server` policy option | **APPLIED** (`rf2-mo4o`) | The divergence had become code-vs-code inside one artefact — `n/defcomponent` refused every key but `:server` while `defhost` refused `:server` itself. A defect, not a taste question |
| 23 | `ht/tree` | **APPLIED** (`rf2-0ckh`) | The kit minted `ht/render` two and a half hours *after* the row recorded `ht/tree`, against the header rule. Reconciled to the row |
| 13 | `release!` off the door, `render!`/`unmount!` promoted | **HALF LANDED** (`rf2-31xm`, `rf2-e2al`) | By a correctness route, not a naming one: `release!`'s page-wide reset emptied the runtime under every other root. Faults, not spellings |
| 42 | `hm/settle-until!` | **SHIPPED** (`rf2-6m4w` ruled, `rf2-aiq7` shipped) | Ruled rather than left to the sitting: `drained`/`drain!` promise queue quiescence the door cannot deliver |

## 3. The packet's recommendations, in the five buckets

Every row here is **applied as a default at publication**. The Override column is the
sitting's.

### 3.1 Bucket A — facade dispositions (`specification.md` §4, `lanes/ergonomics-api.md`)

| # | Current name | Candidate(s) | Recommendation — applied as default | Why (one line) | Witness | Override |
|---|---|---|---|---|---|---|
| 2 | `:&` merge key | remove; a pure owned-wins merge recipe | **remove from the grammar** | Row 34 verified across all 22 chapters that no page invents a merge symbol, so the grammar entry has no consumer to serve | spec §4 disposition; row 34's corpus sweep | |
| 3 | `h/reg-state` | remove from adaptor core; reconsider in forms | **remove from core** | The forms module now owns the addressed-draft door (row 16, shipped), which is the second consumer the core sugar was standing in for | `rf2-sh56`; HS-42 records what is on the door, which is not an endorsement of membership | |
| 4 | `subscribe-once` | internal/advanced | **internal until a caller proves `sub` inadequate** | No witness application reached it; a public name with no consumer is a freeze obligation bought for nothing | Checkpoint 2's authoring reports | |
| 5 | presence namespace | the optional motion namespace name | **`re-frame.hicasso.motion`** | Fourth in the optional-module family beside `.forms`, `.overlay`, `.server`; the guide's ch12 already teaches `motion/*` (row 31) | `motion.cljs` ships under this name today | |
| 6 | `route-link` home | the routing-integration namespace name | **`re-frame.hicasso.routing`** (provisional — the namespace does not exist yet) | Same family rule as row 5; `h/route-link` sits on the door today and HS-40 records it there | `check_facade_inventory.py` attributes `h/route-link` → HS-40 | |
| C2-1 | where a `h/reg-state` concern is declared | `subs`, `events`, or `db` | **moot if row 3 removes it**; otherwise declare it in `db` | The keyword names an address, not an action — the only one of the three that reads correctly on both sides | `naming-findings-cp2.md` C2-1; slice vs Todo answered differently | |
| C2-2 | a name for the `:ui` root | `h/state-path`, or a reader/clear pair | **mint nothing** | Conditional on row 3; `impl.state` already carries `ui-root` and `clear-event-id`, so a mint here is an export decision the removal dissolves | `naming-findings-cp2.md` C2-2; two applications, one hand-written literal path each | |
| C2-4 | `::h/clear` | add to the reserved-data list | **add it to the reserved vocabulary — rename nothing** | Same disposition row 35 gave `::h/navigate`; it is author-written, which is the stronger case for listing | `naming-findings-cp2.md` C2-4; reached twice by the Todo class | |

### 3.2 Bucket B — the `n/` roster

The native tier is the one surface whose spellings were ratified against a real three-route
corpus. **Checkpoint 3 met no evidence against any taught name and proposes no change to
any.** Every row here reads *keep*.

| # | Current name | Candidate(s) | Recommendation — applied as default | Why (one line) | Witness | Override |
|---|---|---|---|---|---|---|
| 7 | `n/$` | keep | **keep** | The one native authoring form; a ruled surface | `three_way_parity_cljs_test.cljs` | |
| 8 | `n/props` | keep | **keep** | Normative grammar | `native_grammar_cljs_test.cljs` | |
| 9 | `n/defcomponent` | keep | **keep** | The island declaration door | `native_abi_cljs_test.cljs` | |
| 10 | `n/use-sub` / `n/use-frame` | keep | **keep** | React's own hook vocabulary, which the reader already carries across the fence | `native_hooks_cljs_test.cljs` | |
| 29 | `n/memo` / `n/lazy` + the loader contract | keep both names; the loader is open as a *shape* | **keep the names**; keep the implemented direct-component loader, described as a deliberate ergonomic adaptation of the `React.lazy` shape | Both are the React spellings; the loader's shape is the only open question and the shipped one is better than the shape it adapts | `lazy_boundary_dom_cljs_test.cljs` | |
| 43 | `n/component` | keep | **keep, classified SURFACE** | Two independent reasons and it needs one: `defcomponent`'s expansion names it in the consumer's namespace, and a code-split chunk `def`s its island through it directly | `native_surface_cljs_test.cljs`; `lazy_boundary_dom_cljs_test.cljs` | |
| 44 | `n/marker` / `n/tier-sentinel` | keep both | **keep, classified SURFACE** | `marker` is what every ABI helper and both embedding directions read; `tier-sentinel` is published precisely so the bundle scan and the runtime cannot spell it differently | `native_surface_cljs_test.cljs`; `check_bundle_isolation.cjs` | |
| 45 | `n/el`, `n/props*`, `n/declared-server` | none (not consumer names) | **classified INTERNAL, no rename** | Each is public for exactly one reason — a macro emits a call to it in the *consumer's* namespace, so privacy would break the consumer's compile | `native_surface_cljs_test.cljs` reads each expansion and finds the symbol | |
| C3-2 | `n/declared-server`'s own spelling | `declared-policy` | **keep `declared-server`** | It is INTERNAL by row 45, so its spelling is not a consumer's problem; renaming an expansion target buys nothing a reader can see | `naming-findings-cp3.md` C3-2 | |
| 46 | `n/prop-slots` | none; or move behind `impl` | **keep public — DIVERGES from C3-1's recommended disposition** | It is named by no expansion, so row 45's licence does not reach it; what buys its publicity is the parity witness driving the rule as its own arm, and making it private would delete the property C3-1 itself calls good | `native_grammar_cljs_test.cljs`, *the-macro-and-the-runtime-share-one-rule-rather-than-two-copies* | |

**A reconciliation the sitting would otherwise argue.** `rf2-hic-085` records the native
namespace as having *fifteen* public vars against Checkpoint 3's fourteen, because
`n/tier-sentinel` was absent from that census. Both figures were right when written and the
number today is **fourteen**: CP3's 14 + `tier-sentinel` = 15, then `rf2-e0d2` made
`check-child!` private — it was named by no expansion and reached by nothing outside its
namespace, so its publicity bought nothing. The count is now pinned mechanically at
10 SURFACE + 4 INTERNAL by `native_surface_cljs_test.cljs`, and §4's independent
source-side census agrees at 14.

### 3.3 Bucket C — root lifecycle, error region, `as-element`

| # | Current name | Candidate(s) | Recommendation — applied as default | Why (one line) | Witness | Override |
|---|---|---|---|---|---|---|
| 11 | `h/as-element` | keep | **keep** | Names the direction by what it returns, which is the rule row 28 keeps from the other side | `codec_cljs_test.cljs` | |
| 12 | `h/error-boundary` | keep | **keep** | HS-09; the authoring report's `h/boundary` is a stale spelling in a published report, not a live candidate | `naming-findings-cp2.md`, *Recorded, and not a naming question*; `slice/views.cljs:304, 455` | |
| 20 | root-lifecycle contract shape — `(node config view)`, `:frame` + `:initial-events` / `:identifier-prefix`; `h/render!` takes `(handle view)` | keep the config map; or the prototype's positional `root!` | **keep as taught** | A config map is what lets `:identifier-prefix` join `hydrate!` without a second arity, and `:initial-events` is borrowed from core's frame-root vocabulary rather than minted here | guide ch01, ch18; `public_root_lifecycle_dom_cljs_test.cljs` | |
| 27 | `h/portal` + `{:target …}` | keep | **keep as taught** | spec §4.3 pins "a tiny optional portal helper" without naming it or its option | HS-20; `portal_dom_cljs_test.cljs` | |
| 28 | `h/as-component` — the outward bridge | keep | **keep as taught** | spec §4.3 pins the bridge, not the name; `as-element`/`as-component` name each direction by what it returns | HS-21 | |
| 26 | interop `:slots #{…}` | keep | **keep as taught** | spec §4.3 pins the concept, not the spelling | `host_slots_dom_cljs_test.cljs` | |

### 3.4 Bucket D — namespace, package and artifact names

| # | Current name | Candidate(s) | Recommendation — applied as default | Why (one line) | Witness | Override |
|---|---|---|---|---|---|---|
| 14 | package/artifact ns | `re-frame.hicasso` | **`re-frame.hicasso`** | Every optional module already inherits this prefix; changing it now moves rows 5, 6, 15, 16, 17 and 22 with it | the shipped tree | |
| 15 | test-kit namespace | `re-frame.hicasso.test` | **keep** | Row 24 settled `re-frame.hicasso.test.mounted` as the mounted facade's home, leaving `ht` the L0–L2 surface this row pins | `test_kit/src/` | |
| 17 | overlay module ns | `re-frame.hicasso.overlay` | **`re-frame.hicasso.overlay`** | Third in the optional-module family; ch13 already teaches `overlay/*` | `overlay.cljs` ships under this name | |
| 19 | artifact coordinates `io.github.day8/re-frame2-hicasso` | keep; or align with core's bare `day8/` | **keep as taught** — but see the counter-candidate | ch01 is the only coordinate any reader has seen, and both group ids are conventions the org already publishes under. *Counter-candidate on record*: `day8/re-frame2-hicasso`, on the ground that an adaptor should match its core's family | guide ch01; row 14 covers the ns name only, never the coordinate | |
| 22 | `re-frame.hicasso.server` + the eight `server/render` option spellings | keep; or fold server rendering into an existing namespace | **keep as taught** — the eight spellings settle as a set, not one at a time | A **Node-side CLJS** module beside `.forms`/`.overlay`/`.motion` is one more optional module, not a new pattern; hydration parity holds by construction only while one runtime renders both halves | ch18 *"there is no parallel JVM string emitter"*; `ssr/entry.cljs` runs the Hicasso runtime under `react-dom/server` | |

### 3.5 Bucket E — everything the ledger gathered en route

| # | Current name | Candidate(s) | Recommendation — applied as default | Why (one line) | Witness | Override |
|---|---|---|---|---|---|---|
| 23 | test kit L2 `ht/tree` + `{:subs …}` | `ht/tree`; `ht/render` | **`ht/tree`** — applied | L2 returns a data tree and never DOM, so `render` misdescribes it and collides with `h/render!` and `server/render` | the kit's own docstring: *"It is not a renderer"* | |
| 25 | `hm/shadow!` + `{:reference :candidate :initial-events :script}` | keep; `:seed` was ch19's original | **keep as taught with `:initial-events`** | One seeding vocabulary across `h/mount!`, `hm/mount!` and `hm/shadow!` | `shadow_dom_cljs_test.cljs` | |
| 30 | overlay surface — heads, seven options, `:rf.error/hicasso-overlay-anchor-missing` | keep | **keep as taught** — the option set settles as one family | The id is the corpus's single deliberate mint, so it is a complaint-catalogue obligation as well as a spelling | Stage C census: 33 ids cited, 32 attested | |
| 32 | `forms/buffered-field` + its five props | keep | **keep as taught** | Reset unifies on `::h/revision`, which the controlled-input law already owns, so the field adds no second reset vocabulary | `forms_dom_cljs_test.cljs`; D016 | |
| 33 | `:demand true` in `[:rf/resource …]` | keep | **keep as taught** | `:keep-previous?` is struck from the mint list — it is attested core-resources vocabulary, not a Hicasso mint | `typeahead/demand_dom_cljs_test.cljs` | |
| 34 | owned-wins merge — no symbol minted | mint nothing; or a named helper | **mint nothing** | Verified across all 22 chapters that no page invents a symbol; this recipe is what stands in for the `:&` grammar row 2 removes | corpus sweep, ch02 and ch04 | |
| 35 | `::h/navigate` reserved head | keep | **keep, and add it to the reserved-data list** | The brief's list omits it, so what this row owes is a reserved-vocabulary entry rather than a rename | `route_link_cljs_test.cljs` | |
| 36 | `:prefetch :intent` on `route-link` | keep | **keep as taught** | Routing's own `:rf.route/prefetch` event and its `:intent` value are attested, so only the link-side acceptance is new | the retired decline id stays tombstoned, never reused (`rf2-hic-021` law) | |
| 37 | Xray evidence-envelope keyword spellings | keep | **keep as taught** | spec §10 pins this vocabulary in prose but not the keyword forms, and these are what a Tool-Pair consumer types against | `evidence_schema_cljs_test.cljs` | |
| 38 | hydration-mismatch report `{:id :root :where :error}` | keep | **keep as taught** | `:rf.ssr/hydration-mismatch` is attested in re-frame.ssr; only the report-map keys are the mint | `identifier_prefix_ssr_dom_cljs_test.cljs` | |
| 39 | migration tool surface | none — every one matches the codemod source | **no naming question** | Recorded so the sitting sees the whole sweep; of the migration surfaces only shadow mode (row 25) is a mint | `migration/reagent-to-hicasso/codemod` | |
| 40 | twelve **reserved** complaint ids | keep; or respell when the surface is built | **keep as minted** | A reservation is cheap to hold and expensive to omit — a refusal with no id is invisible to a round trip and gets a second spelling from whoever builds the surface | [`complaints.md`](../../../../implementation/hicasso/spec/complaints.md) | |
| 41 | `hm/advance-clock!` + `{:clock true}` | keep | **keep both as shipped** | The verb is a carry-over, not a mint: `re-frame.freehand.presence-runtime` and `re-frame.ui.presence-runtime` both carry it, and a conformance fixture spells the step `:advance-clock` | `test_kit_clock_dom_cljs_test.cljs` | |
| C2-3 | the L3 enqueued-work verb | `hm/drain!`; a `:until` option on `settle!` | **moot — ruled and shipped as `hm/settle-until!`** (row 42) | `interop/next-tick` is a macrotask with no fixed tick count, so an honest door states a condition and a deadline rather than queue quiescence | `rf2-6m4w`; `rf2-aiq7` | |

## 4. The completeness census — the don't-forget guarantee

The ledger is appended to by whoever finds a question, which makes it complete only by
luck. This section diffs it against the artefact instead.

### 4.1 Method

The roster is **not** a grep over raw source. These files document themselves at length and
their docstrings carry worked `(def …)`, `(defn …)` and `(defhost …)` examples that a grep
counts as definitions. The census reuses `check_facade_inventory.py`'s `code_only`, which
blanks strings, comments and reader-discarded forms, and walks what survives for
`def`-headed forms — so the roster is what the reader would *evaluate*.

Two refinements were forced by going wider than the one door that gate reads:

- **A definition opens a line.** That gate accepts a `def`-headed form anywhere, which is
  safe on a door calling no `def*`-named function. `evidence.cljs` calls two —
  `(let [ds (defects p)]` and `(defects-message ds)` — and reading those as definitions
  minted the phantom public names `p` and `ds`. Every real definition in this tree opens
  its line (top level, or nested inside a reader conditional); a call does not. Mid-line
  `def*` heads are **printed under their own heading** rather than dropped, so the skip is
  visible instead of assumed.
- **The whole shipped surface**: `src/**` minus `impl/**`, plus the two `test_kit`
  namespaces. `impl/**` is excluded by name and the exclusion is stated here rather than
  buried, because it is the one judgement in the method.

The census takes its repository root as an **absolute argument and prints it**. A script
that derives its root from its own invocation path can walk a sibling worktree and report
that as your answer, which for a census would substitute somebody else's completeness
figure for yours.

The full script is reproduced in §7 so `rf2-hic-064` can re-run it, and §7 records why it
is not yet a gate.

### 4.2 The count

| Namespace | Public names | Already in the ledger | Unrostered |
|---|---|---|---|
| `re-frame.hicasso` | 16 | 14 | 2 |
| `re-frame.hicasso.native` | 14 | 14 | 0 |
| `re-frame.hicasso.forms` | 4 | 0 | 4 |
| `re-frame.hicasso.overlay` | 2 | 2 | 0 |
| `re-frame.hicasso.motion` | 1 | 1 | 0 |
| `re-frame.hicasso.server` | 6 | 1 | 5 |
| `re-frame.hicasso.tool` | 4 | 0 | 4 |
| `re-frame.hicasso.evidence` | 20 | 0 | 20 |
| `re-frame.hicasso.test` | 23 | 3 | 20 |
| `re-frame.hicasso.test.mounted` | 15 | 10 | 5 |
| **Total** | **105** | **45** | **60** |

**Agreement with the standing gate.** `check_facade_inventory.py` reports *"16 names on
`re-frame.hicasso`"* and this census reports 16 for that namespace — an exact match, from
two independent walks of the same door. `native_surface_cljs_test.cljs` pins
`re-frame.hicasso.native` at 10 SURFACE + 4 INTERNAL = 14, read from the **compiler** at
expansion; this census reads the **source** and also reports 14. Neither agreement was
arranged: the gate and the test were written before this census existed.

**What the census caught that no gate does.** `check_facade_inventory.py` reads one door by
design, and says so — *"adding a second door here is a data change; deciding what its public
roster IS is not, and is filed rather than guessed"*. So the 58 unrostered names outside
`re-frame.hicasso` were outside every existing gate's reach. §5 gives each a disposition.

### 4.3 The seeded positive control

A census that cannot be shown to bite proves nothing. One public definition was planted in
`implementation/hicasso/src/re_frame/hicasso/motion.cljs` — deliberately omitted from the
ledger — and the census was re-run:

```
MISS re-frame.hicasso.motion          motion/hic065-census-positive-control
CENSUS: 106 public names across 10 shipped namespaces; 61 NOT rostered in naming-ledger.md
  UNROSTERED  re-frame.hicasso.motion  motion/hic065-census-positive-control
```

105 → 106 and 60 → 61, with the planted name named. The plant was then reverted and the
restore **verified by hashing the bytes** rather than by reading a diff: `git hash-object`
returned `ce1cf16681b879913f0342d0c14e8966718b4704` before the plant and the same digest
after the revert, and the census returned to 105/60. Nothing was committed.

The control does a second job. The plant existed in exactly one checkout, so a run that had
wandered into a sibling worktree would have come back at 105/60 **without** the control
line — the discrimination is the red itself, which no reported path can give you, because a
repository-relative path cannot tell two checkouts apart.

## 5. The rows the census forces — new ledger rows 47–54

Sixty names, eight rows. They are grouped because each family settles as a set and not one
name at a time — the same rule row 22 already applies to the eight `server/render` option
spellings, and the same reason: a reader who has to rule on `evidence/scopes` separately
from `evidence/scope?` is being asked a question that has no separate answer. **Every
member is named verbatim below**, so grouping costs no completeness.

| New row | Surface — every member named | Recommendation — applied as default | Why (one line) | Witness |
|---|---|---|---|---|
| 47 | `h/defview` — the primary authoring macro | **keep** | The one name every reader types first, and the ledger never carried it; there is no candidate because the whole corpus, the guide and HS-01 spell it this way | HS-01; `check_facade_inventory.py` attributes it BY NAME |
| 48 | `h/use-subs` — the grouped read | **keep, classified SURFACE** | One fixed site takes the whole read-set, which is the control the ordinary path is measured against; it reached the door with no row anywhere until `rf2-2l8pw` minted HS-41 | HS-41; `readset_group_census_cljs_test.cljs` |
| 49 | `re-frame.hicasso.forms` ids — `forms/drafts`, `forms/edit-id`, `forms/commit-id`, `forms/cancel-id` | **keep all four as shipped**, and record that they are ids rather than doors | Each is public *as an id and not as a door* — written into the field's own intents, so it is already visible in the rendered tree, in Xray and in a captured intent; a test that could not name it would be asserting on a literal | `forms.cljs` docstrings; `forms_cljs_test.cljs` |
| 50 | `re-frame.hicasso.server` — `server/document`, `server/fresh-frame-id`, `server/payload-script`, `server/setup-events`, `server/render-twice` (`server/render` is row 22) | ~~keep all five as shipped~~ — **OVERRIDDEN (operator, 2026-08-15, `rf2-sc1dt`): keep `server/document`, `server/payload-script` and `server/render-twice`; `server/fresh-frame-id` and `server/setup-events` go PRIVATE.** Public surface is four names | The default kept all five as "the request pipeline `server/render` composes". The sitting split them on evidence instead: each survivor does something for an external host that `server/render`'s returns alone cannot — re-wrap a mutated payload against the pinned script id and the EDN-aware escaper, rebuild the envelope without re-spelling `escape-html`/`escape-attr`, run a determinism check whose `:differs-at` diagnoses a red run and which cannot move to a test kit without `react-dom/server`. The two going private have no such story, and `fresh-frame-id` structurally cannot: `server/render` mints its own id and forbids overriding it, so no public path consumes the return value. Zero test churn; the `hicasso.ssr` bundle sentinel is untouched. Implemented by `rf2-34sdz`; full ruling on ledger row 50 | `server_render_ssr_dom_cljs_test.cljs`; `ssr/entry.cljs` |
| 51 | `re-frame.hicasso.tool` — `tool/read-mounted-boundaries`, `tool/read-read-attribution`, `tool/read-intents`, `tool/explain-render` | **keep all four as shipped** | They are the tool-tier reader door in full — *the four reads Xray and the AI pair consume, and the only door either of them has* — and the `read-*` prefix is what marks them as projections of state the runtime already retains rather than an accumulator | `tool.cljs` namespace docstring; `tool_reads_cljs_test.cljs` |
| 52 | `re-frame.hicasso.evidence` — `evidence/schema`, `producer`, `basis-kinds`, `scopes`, `loss-reasons`, `unknown`, `reads`, `scope?`, `loss?`, `unseeing-bases`, `axis-keys`, `projection-fields`, `projection-invariants`, `defects`, `defects-message`, `projection`, `capped`, `envelope-fields`, `envelope`, `retention` | **keep all twenty as shipped** — the vocabulary settles as one closed set | This is one versioned adapter-neutral schema and its vocabularies are closed on purpose; respelling any member is a schema version bump, not a rename, and row 37 already keeps the keyword spellings a consumer types | `evidence_schema_cljs_test.cljs`; spec SN §10 |
| 53 | `re-frame.hicasso.test` L0–L2 — `ht/ladder`, `ht/tree-version`, `ht/boundary?`, `ht/host?`, `ht/callback?`, `ht/controlled?`, `ht/view-name`, `ht/host-policy`, `ht/element-props`, `ht/materialize`, `ht/revision`, `ht/capture-intents`, `ht/fire!`, `ht/find`, `ht/attrs`, `ht/text`, `ht/intents`, `ht/role`, `ht/accessible-name`, `ht/unnamed-controls` | **keep all as shipped** | They are one ladder, published as data (`ht/ladder`) precisely so the kit's own refusals can cite it; the predicates and accessors are named for what they answer and no member has a competing spelling anywhere in the corpus | `test.cljs` — *"THE TESTING LADDER, as data"*; `test_kit_cljs_test.cljs`, `test_kit_a11y_cljs_test.cljs` |
| 54 | `re-frame.hicasso.test.mounted` counters — `hm/counted`, `hm/census`, `hm/bodies-run`, `hm/residue`, `hm/this-frame` | **keep all five as shipped** | The residue vocabulary `hm/assert-clean!` compares against, published so a test can ask what a page *retains* (`census`) separately from what a change *cost* (`bodies-run`) without either asserting; `this-frame` is the stand-in a shadow run compares two isolated mounts as | `mounted.cljs` docstrings; `test_kit_mounted_dom_cljs_test.cljs` |

**Nothing here is a rename.** All 60 names read *keep*, which is the honest outcome: they
are internal-facing surfaces that grew under a consistent hand, and the reason they were
missing from the ledger is that the ledger is appended to by whoever meets a *question* —
and nobody met one here. That is exactly the failure mode the don't-forget guarantee
exists to catch, and catching it as *sixty names, no renames* is the cheap version.

## 6. The two rows that STOP — semantic, not mechanical

The standing rule is that a semantic row files a bead rather than improvising, and
`rf2-hic-066` never improvises. Two rows qualify.

### 6.1 Row 1 — the name is ruled; the contract it names is not settled

**The rename proceeds.** `hfn` → `h/event` is an operator ruling and this packet does not
re-open it.

**What stops is the sentence the new name makes.** Read at source,
`impl/intent.cljs` says *"ONE callback form, and the POSITION selects the contract"* and
tabulates three: a native `:on-*` prop is **event** (a returned vector is dispatched), a
`defhost` `:callbacks` entry is **as declared** (`:event`, `:handler` or `:render`), and
any other prop position is **render** — pure, return not dispatched, and dispatching from
inside is `:rf.error/hicasso-intent-at-a-non-event-contract` named at the position. The
`hfn` docstring agrees: *"The value is an ORDINARY FUNCTION … The contract comes from the
position it is written at."*

**[Amended 2026-08-29, PR #8755 (`rf2-6c12m.24`).]** Read at source today, `impl/intent.cljs`
tabulates **two**: event and render, inferred from the position at a native tag and at a host
alike, with `:callbacks` as an optional `:event` / `:render` override for an `on*`-named render
prop. `:handler` is deleted and `:rf.error/hicasso-intent-at-a-non-event-contract` is retired; a
vector at a render position crosses as data. The row's argument below reads the same with two
contracts as with three.

So the ruled name states **one** of the three contracts the form can carry. That is not an
argument against the name — the ruling's ground, that `handler` is a cross-adaptor false
friend, is untouched by this and `h/callback` would state no contract at all. It is a
**documentation obligation the rename creates**: under the name `h/event` the position
table has to be adjacent to the name wherever the name is taught, or a reader reasonably
concludes the return is always dispatched. Filed as `rf2-0fd3b`, sequenced after
`rf2-hic-066`, because deciding where that table goes is a writing decision and not a
mechanical substitution.

**The bead's premise was checked and is partly false.** The advisory alleged the guide
teaches a *position-invariant* contract, which would make the rename a false name on
different behaviour. Measured: the guide teaches `h/fn` in 47 places and `h/event` in
zero, so there is no `h/event` contract in the corpus to diverge from — the divergence is
between the advisory's reading and the source, not between two shipped descriptions.

### 6.2 Row 18 — the preferred alternative does not work today

> **[Amended 2026-09-04, `rf2-87iu`. It works now, and this section is history.]** The seam this
> section says must land first **has landed**. `rf2-t32wg` was ruled option C by the operator on
> 2026-08-30 — *a refusing render extent may still expose its declared frame to the pure identity and
> capture doors, while stateful ambient operations stay refused*, branched in `require-current-frame!`
> with the mismatch check first — and executed as `rf2-6c12m.13`, PR #8784, which deleted `hframe`,
> its `h/` alias, its error id and its documentation with **no compatibility alias**. So the heading
> above is false at tip and the section below is kept as the record of why the row was held from
> 2026-08-15 to 2026-08-30, not as a live constraint. Row 18 in the table above and in
> [`naming-ledger.md`](naming-ledger.md) is **EXECUTED**. The occurrence figures in the paragraphs
> below are likewise historical: they were taken before the sweep, `implementation/hicasso/src/`
> reads **zero** `hframe` today, and the survivors elsewhere are docs, bench and test trees.

`hframe` RETIRES by operator ruling, in favour of core `rf/current-frame-id` and zero-arity
`rf/capture-frame` admitted during a Hicasso body. But **zero-arity `rf/capture-frame`
inside a Hicasso body refuses today** — measured, with `:operation :capture-frame`,
`:substrate :hicasso`, extent `hicasso/boundary-render` (`rf2-lvelh`).

So the retire is semantic work and not a deletion: the ambient-refusal seam must first
admit exactly `:current-frame-id` and `:capture-frame` in-body, resolving to the body's
`:extent-frame`, while ambient `rf/subscribe` and `rf/dispatch` stay refused. `rf2-hic-066`
files that seam bead and does not sweep row 18 until it lands. The 124 `hframe` occurrences
across 30 files stay put in the meantime, which is the header rule working as intended.

**A precedent worth carrying into the sitting**, because it settles a question that would
otherwise be argued: HS-43 carries `h/hframe` and HS-42 carries `h/reg-state` while rows 18
and 3 recommend retiring both. *An inventory row records what is on the door; it does not
endorse membership.* Minting a row for a name you intend to retire is not a contradiction,
and the sitting need not clear the inventory before deciding.

## 7. Re-running the census — for `rf2-hic-064`

`rf2-hic-064` re-runs both the census and its positive control. Both are now a **committed
gate** — `implementation/hicasso/scripts/check_naming_census.py` — rather than a script
reproduced in this section, which is what `rf2-hxbhe` promoted. Until that landed the census
was a one-shot measurement; it is now re-runnable by anyone, with its controls attached.

```
python3 implementation/hicasso/scripts/check_naming_census.py <ABSOLUTE repo root>
python3 implementation/hicasso/scripts/check_naming_census.py --self-test
```

**[Amended 2026-08-30, `rf2-6c12m.8`.]** The gate is deleted — PR #8775 retired `check_naming_census.py`, its CI job and its spine lane together with the ledger it rostered against, which is design history at [`naming-ledger.md`](naming-ledger.md) beside this page. The two commands above no longer resolve; what this section preserves is the census's method and its positive control, which a future re-run would rebuild from.

It exits 0 when every public name is rostered, 1 naming each name that is not, and 2 when it
REFUSES — a namespace source that has moved, a roster that came back empty, a missing
ledger, or a ledger it could not read a single code span from. A refusal is distinct from a
red on purpose: an absence check that inspected nothing otherwise reports the same green as
one that inspected everything.

**Two properties the gate keeps, both of which were found the hard way.** *A definition
opens a line*: `check_facade_inventory.py` accepts a `def`-headed form anywhere, which is
safe on the one door it reads, and here it minted the phantom public names `p` and `ds` out
of the two calls in `evidence.cljs` — `(let [ds (defects p)]` and `(defects-message ds)`.
*The root is an absolute argument and is printed*, because a script deriving its root from
its own invocation path can walk a sibling worktree and report that as the census; a
relative path is refused rather than resolved against the working directory.

**The positive control now ships.** It used to be a manual dance recorded here — plant a
public `(defn …)`, confirm the count rises by one, revert, verify the restore with `git
hash-object`. It is now a fixture inside `--self-test`: a seeded export with no ledger row,
which the census must catch and name, run on every invocation rather than when somebody
remembers. The live form still works and was re-run at promotion — a public `defn` planted
in `motion.cljs` moved the census 105 → 106 and named it under `UNROSTERED`, reverted, the
restore verified at `ce1cf16681b879913f0342d0c14e8966718b4704` either side.

**Measured at publication, with rows 47–54 in the ledger**, and re-measured identically by
the committed gate: *"CENSUS: 105 public names across 10 shipped namespaces; 0 NOT rostered
in naming-ledger.md"*, exit **0**. That is the bead's acceptance condition met mechanically
rather than by inspection. A non-zero count from here is a public name minted since
publication with no ledger row — precisely what section 3 of
[`dispositions.md`](dispositions.md) forbids.

> **[Amended 2026-09-04, `rf2-87iu`. Nothing below stands; both homes are gone, and this is where the
> consequence for §13 is recorded.]** The `hicasso-naming-census` job was deleted from
> `.github/workflows/test.yml` by `49aa8116c4` on 2026-08-30 and the `scripts/test-fast-pr.sh` block
> by `bb3a92cd73` four minutes earlier, both `rf2-6c12m.8` / PR #8775, together with
> `check_naming_census.py` itself and the matching `SPINE_LANES` entry named in the last sentence
> below. **No naming-census instrument survives at tip**: four fixed-string probes — `naming_census`,
> `naming-census`, `naming census`, `check_naming` — return hits only in prose and in two tombstone
> comments (`.github/workflows/test.yml` and `scripts/check_fast_pr_gap.py`, both inside the
> surviving guide-samples lane's comment block), and no script, job or npm lane matches any of them.
> The paragraph below is kept because it records how the gate was wired and why it rode the spine
> rather than the invariants chain, which is what a future rebuild would need.
>
> **The dated sentence `rf2-87iu` asks for, stated plainly. §13's *small, internally coherent
> language* bullet ([`specification.md` §13](specification.md#13-definition-of-done)) is held by the
> 2026-08-20 reading — 106 public names across 11 shipped namespaces, 0 unrostered, self-test exit 0
> over 12 checks including the seeded-export positive control — and NOT by any live instrument.**
> That end state was chosen, not drifted into: the operator's own 2026-08-30 pass deleted the checker
> deliberately, and its stated reason is on the record in `implementation/hicasso/spec/README.md` —
> *closed programme records were being gated as if live*. **What is not on the record is whether that
> pass weighed this particular consequence**, and this note does not assume it did: the reason it
> gives is about gating a closed ledger, and §13 is not mentioned. So the clause is witnessed by a
> dated snapshot, which is a legitimate end state for a closed programme record and is also the one
> thing an audit cannot verify. **If a live witness is wanted again, what would settle it** is an
> instrument that enumerates shipped public names the way the deleted one did — reading `(def
> ^{:doc ...})` forms, which `motion.cljs` and `overlay.cljs` carry, and excluding `defview` samples
> inside docstrings — with the seeded-export positive control that made its zero readable. A
> hand-rolled enumeration is not that, and would fail on exactly those two shapes.

**Standing since `rf2-st1x5`,** in two homes rather than the three that bead anticipated.
The `hicasso-naming-census` job in `.github/workflows/test.yml` runs it unconditionally —
the ledger arms no classifier output, so a deleted row would otherwise next redden on
somebody else's source PR — and that job is named in `all-required-passed`'s `needs:`,
without which it would exist and gate nothing. The local lane is `scripts/test-fast-pr.sh`
rather than the `test:hicasso-invariants` chain its seven siblings ride, because the live
run takes an **absolute** repo root and npm runs package scripts through `sh` on
Linux/macOS and `cmd.exe` on Windows: `$PWD` expands in one and stays literal in the other,
`%CD%` is the mirror image, and no single spelling resolves on both. The spine is one POSIX
shell on all three platforms. `scripts/check_fast_pr_gap.py` carries the matching
`SPINE_LANES` entry, so the job's two steps are not over-reported as a local gap.
