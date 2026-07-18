# Staged review — the UI guide (`guide/`)

**When:** 2026-07-14 17:27 AUSEST
**Scope:** all 11 pages of `ai/findings/new-substrate-synthesis/guide/` (README + 01–10)
**Brief:** (1) live coding as a teaching tool · (2) completeness · (3) correctness · (4) voice

---

## Stage 1 — Live coding as a teaching tool

The guide teaches a live system with static prose. Meanwhile the repo's differentiating
capability — an attachable, dispatchable, time-travellable runtime (Pair, Xray, Story,
the REPL) — appears only as a *subject* (chapter 06 describes the tools) and never as a
*method* (no page asks the reader to touch a running app). Five concrete moves, ordered
by value-for-effort:

### 1.1 Reframe the S3 gap as REPL-first teaching (cheap, do now)

The guide repeatedly apologises that buttons don't dispatch until S3 ("until then the
vector is data you can read and assert"). But the live loop — dispatch → drain → sub →
repaint — **shipped with S2**. Only the DOM listener wiring is missing. So the honest,
and pedagogically superior, framing is: *until S3, you are the click.* Chapter 01's
counter should end with a REPL beat:

> Mount the app, then evaluate `(rf/dispatch <frame> [:count/inc])` in your REPL —
> watch the view repaint. That's the whole loop; S3 merely wires the button to do what
> you just did.

This turns a staging limitation into the deepest lesson the library has: the UI is an
event stream you can drive from anywhere — a button, a REPL, a test, an AI pair.

### 1.2 "See it in Xray" beats at the end of concept sections (cheap)

Each core concept the guide states is directly observable in a tool, and the guide
already asserts so without showing it ("you can read what the button does without
running anything — and so can Xray"). Add a two-to-four-line *see it live* strip:

- 03 Subscriptions → open the inspector's Dependencies panel on `order-summary`; the
  static column is what you just wrote.
- 04 Events → hover the button; the vector is right there, before any click.
- 05 Frames → mount the two-frame page; watch `:shop` epochs advance while `:assist`
  stays still.
- 07 Performance → the model-in-one-paragraph is *verifiable*: type in the filter box
  and read the render timeline — one epoch, one tile.

These need no new infrastructure; they need sentences. (Marker discipline applies: the
causes/manifest surfaces are S3, so the strips carry the same *(lands S3)* markers.)

### 1.3 A pair-programming interlude (medium; the flagship)

Nothing in the guide shows the workflow the repo is proudest of: an AI attached to the
running app. Proposal: a short page (or a closing section in 06) that is a lightly
annotated transcript of a real session against the chapter-10 dashboard —
"why did `metric-tile` re-render?" → the pair reads the causes; "break the filter
handler, hot-swap the fix, scrub back, replay". The retro loop (cascade → patch →
restore-epoch → retry) demonstrates trace, epochs, and hot-swap in one story that no
static paragraph can. This is also the page that makes *AI-first* concrete for a guide
reader. (It rides shipped machinery — Pair + core frames — not the UI substrate's S3
surfaces, though handler hot-swap of compiled `defview` bodies should be sanity-checked
against the build-digest caveat in 01.)

### 1.4 The guide playground: one mount for every chapter (medium)

The fixture pipeline already proves guide fences compile and render headlessly. The
live-coding extension: one dev build (`guide-playground`) that mounts each chapter's
example under its own frame — the counter, the signup form, the dashboard. Every "see
it in Xray" strip then has a canonical target, and a reader's first `npm run
guide-playground` gives them the whole guide as a running app. The bar from the README
extends naturally: if a live exercise needs ceremony beyond one command, that's an API
bug, not a documentation problem.

### 1.5 Converge fixtures and Story scenes at S6 (later; flag now)

When Story integration lands, guide fixtures and Story variants want to be the same
EDN — one source rendering as (a) a guide fence, (b) a CI fixture, (c) an openable
scene with a time-travel scrubber. Worth a line in the fixture-pipeline draft now so
S6 doesn't build a parallel corpus.

**Anti-goal:** don't turn every page into a lab. The strips are optional sidebars; the
prose must keep teaching on its own for the AI-reader and the offline reader.

---

## Stage 2 — Completeness

### 2.1 The missing "How it works" page (requested; recommend `11-how-it-works.md`)

The guide states *what* the model guarantees and 07 states the economics, but nowhere
explains the **mechanism** — and this library's central claims (no interpreter, no
hooks ceremony, correct memoization, two emitters that cannot drift) are exactly the
kind readers won't trust without a mechanism sketch. Curious seniors and evaluators
will go looking; today they'd land in the design docs, which are contributor-voiced.
Proposed outline (each section: the mechanism in two-three paragraphs, then "where you
feel it" cross-links back to guides):

1. **The compiler.** What `defview` lowers to — a before/after fence: hiccup in,
   direct element construction out; static subtrees hoisted to module constants; which
   parts stay dynamic (branches, `for` bodies). Why there is no interpreter in the
   bundle, and what the closed template grammar buys (the 02 fences: literal heads,
   compile-time keys).
2. **The build digest and the registry.** The honest home for 01's densest paragraph
   (cache-blockers, build hooks, version-zero passes, why a misconfigured dev bundle
   fails loudly). 01 keeps the recipe; this page keeps the why.
3. **The reactive core.** How `sub` re-renders a view with one React bridge: read-site
   registration, the pending render batch and the host checkpoint that closes it, one
   notification per batch, identical references short-circuiting child comparators. Ownership: acquire/release across mount,
   unmount, Activity hide, abandoned renders — why there is no tearing window and no
   order-A-data-against-order-B-id frame.
4. **Memoization that is correct, not heuristic.** `rf=` per prop slot; identity fast
   path; why value equality is sound for CLJS data and where host values honestly fall
   back to identity.
5. **Events without closures.** How a vector site compiles; the manifest; placeholder
   splicing at dispatch time; the sync-input door mechanics — event → dispatch →
   commit → value back in `:value` pre-repaint, and why that kills caret jumps.
6. **One template, two emitters.** The shared conversion/escaping rule table; the JVM
   string emitter; fingerprints; why parity is structural, not best-effort.
7. **The dev/prod split.** How causes, manifests, and coords attach in dev and how
   Closure elision removes them; the CI gates (bundle absence, size budget, output
   shape, G-13) that turn 07's claims into proofs.

Source material largely exists in design docs 02/03/05 — this page is a synthesis with
a user-facing register, not new research. Placement after 07 keeps the learning path
clean; 01 and 07 both link to it ("if you want to know why this is true → 11").

### 2.2 Other missing pages

- **Talking to servers** (highest-value gap). `lease`/`:rf/resource` are introduced
  twice, but the fetch side never appears: 10 says "wire your transport as ordinary
  re-frame2 fx" and stops. A consumer's first real question — *how does data get in?*
  — has no page. One chapter showing `:rf.http/managed` → `:metrics/arrived` →
  resource status → `lease`, end to end, would complete the Nine-States story the
  README gestures at. (Even pre-S3 it can be REPL-driven per stage 1.1.)
- **Routing, the view-layer face.** Core routing has its own guide, but the UI guide
  never shows a link. How do you write navigation — an `<a>` with `:href` plus an
  event? What does an active-route class read? Per-pane routes were a headline in the
  repo README and 04 already leans on "the route's `:on-match`". A short page or a
  05/04 section.
- **Migration from Reagent** *(lands S6, flag now)*. Design doc 10 exists; the guide
  README's four-line "Coming from Reagent?" will not carry a real port. The page is
  the translation table: Form-2/`with-let` → `local`+`effect`, ratoms/cursors/track →
  the four inputs, `r/adapt-react-class` → template heads, `reagent.dom/render` →
  `mount`, plus `ui/->react` for incremental cohabitation.
- **A "where did X go?" appendix for React people.** Context, portals, refs, Suspense,
  `useEffect`-for-data — each with the one-line answer and a pointer (subs and frames;
  wave-2; `ui/raw-fn`; status values; events/fx). The README's "Coming from React"
  paragraph promises this implicitly; a table delivers it. Cheap and high-empathy.

### 2.3 Missing sections in existing pages

- **01:** a five-line project skeleton (where `deps.edn`, `shadow-cljs.edn`, `src/`,
  the build entry live) before "The whole app"; move the digest deep-dive out (→ 2.1
  §2), keep the recipe + one symptom sentence.
- **02:** a short **styling** stance — what `:class`/`:style` compile to, that CSS
  itself is yours (plain CSS, Tailwind, whatever), and that presence phases are
  class-driven by design. Readers coming from styled-components will look for this
  and find silence. Also: move **Presence** (S4) below Props discipline/Interop so
  the shipped core reads contiguously.
- **03:** one sentence + pointer on *who fulfils* `:rf/resource` (the fx/resource
  system, → Talking-to-servers page) — the lease section currently reads as if data
  appears by magic.
- **04:** a **global listeners / keyboard shortcuts** cross-reference — the
  `error-reporter` pattern in 05 answers it, but a 04 reader searching "keydown on
  window" won't find it.
- **06:** a REPL example of the `re-frame.ui.tool` surface (`view-manifest` on the
  counter view) — ties into stage 1; today the namespace is named but never shown.
- **07:** a "measure it yourself" paragraph: the npm scripts that run the perf-bundle
  and size gates against *your* app, and what number to compare with the ≤ 4 KB
  kernel budget.
- **08:** one line on **streaming SSR** (in scope? out of scope? ruled later?) — the
  contract is silent and evaluators will ask; plus a pointer to the `ssr-ring`
  ecosystem adapter mentioned in the repo layout.
- **09:** precede the Tier-3 example with the *simplest possible* mounted test (mount,
  one query, done) before the full Promise-ceremony version; the current single
  example is the guide's hardest code and it arrives unannounced.
- **10:** close the worked app with two codas — the SSR variant of the same dashboard
  (three lines: it's 08's mount with a manifest), and the live-coding coda ("open
  Xray; type in the filter; read the timeline" / "ask your pair why the tile
  repainted").

### 2.4 Lean on `docs/core/`, don't duplicate it

The shipped human guide (`docs/core/` — frames, subscriptions, effects, coeffects,
errors, observability, testing, plus `how-to/`) already covers the dataflow half of
several gaps above. The UI guide's new pages should link into it hard: the
Talking-to-servers page teaches the *view-facing* seam (lease → status → tile) and
defers fx mechanics to core; the routing section defers route registration the same
way. Today the guide cites core concepts without ever linking to their chapters —
worth a pass adding those links so the two guides read as one shelf.

### 2.5 Ordering note

With 11 (How it works) and a Talking-to-servers page, the arc becomes: learn (01–05),
observe (06), trust (07, 11), deploy (08), verify (09), build (10, servers). That's a
coherent shelf; routing/migration slot in behind their stages.

---

## Stage 3 — Correctness

*(Verification ran three ways: guide vs `implementation/ui` on main, guide vs the
synthesis design docs, guide examples vs the core spec. Findings below.)*

### 3.1 Guide vs shipped implementation (main @ 2026-07-14)

**Holds up well.** Verified against `implementation/ui/`: the guide-09 fixture file
exists and enrolls exactly the five behaviours the README claims; `defview`, `sub`,
`adapter`, `mount`, `frame-root`, `frame-provider`, `raw`, `html`, `spread` all
exported; the Tier-1/Tier-3 test surface (`render`, `find`, `find-all`, `attrs`,
`text`, `frame`, `dispatch!`, `with-root`, `query`, `flush!`, `:sub-overrides`) all
present; JVM `flush!` synchronous returning nil as documented; placeholder set is
exactly `#{:rf.ui/value :rf.ui/checked :rf.ui/key}` with no `:rf.ui/event` /
`:rf.ui/form-data`; React pinned 19.2.0; shadow-cljs 3.4.10; `:cache-blockers` +
`:build-defaults` build-hook exactly as the 01 recipe shows; G-13 wired
(`ui/g13` source path, three builds, `test:ui-g13` script). Unshipped names (`local`,
`effect`, `ui/event`, `ui/handler`, `render-fn`, `dispatch-fn`, `error-boundary`,
`presence`, `client-only`, `->react`, `element`, `render-static`,
`flush-presence!`) are all correctly stage-marked in the guide — the stage honesty is
real.

**Marker-freshness flags (guide vs main):**

1. **`lease` exists on main** (`re-frame.ui/lease`), but the README still calls it
   "the one straggler" carrying *(lands S2)*, and 03/10 hedge "view-level lease
   semantics confirm at S3". If lease's S2 slice has landed, the README straggler
   note is stale; if only the var landed, 03's hedge is right and the README should
   say that instead.
2. ~~The host tier vs 08's S5 marker~~ — **resolved, no guide change**: `hydrate-root`
   exists on main as the ruled spelling and its docstring says every hydrate fails
   loud pre-S5 (`:rf.error/root-manifest-invalid`), which is exactly what the guide's
   *(lands S5)* marker claims.
3. **Two catalogued ids are ahead of their code:** `:rf.error/dispatch-disconnected`
   and `:rf.warning/unregistered-event-id` appear in 06's catalogue list but exist
   nowhere in the implementation yet (their features are S3). 06's stage note
   technically covers this, but the catalogue paragraph reads as shipped; a marker on
   those two ids (or an "ids land with their features" clause) keeps 06 honest.

### 3.2 Guide vs design docs

**Agrees on essentially everything checked (15/15 claims).** The stage ledger matches
12-implementation-plan §2b and 08-delivery §2 row for row (including `lease` S2 with
S3 confirmation, `->react` S6, `client-only` S3 with its S5 phase flip); wave-2 names
and the 2026-07-12 blessed-table qualifier are corroborated; the placeholder
vocabulary, sync-door predicate, callback decision table, `local` doctrine (ruled
2026-07-12), memoization posture (`:memo false` deliberately absent), loop/branch
rules, error-boundary contract, root identity (including the `_S` slug escape and the
manifest-wins hydration rule), Tier-1 subset, causes vocabulary, tool namespace,
≤ 4 KB kernel, G-8/G-13, and the SSR guarantees all agree with their owning docs.

**Divergences worth recording:**

1. **`:rf.ui.compile/bad-test-root` + the single-view test-root tightening** appear
   in the guide and in the *shipped implementation* (`re-frame.ui.test`, with a JVM
   test asserting it) but not in `drafts/root-identity-and-mount.md` §9, which still
   says `ui.test/render` takes "the same grammar `mount` takes". The **design draft is
   the stale artefact** — it should adopt the tightening the code and guide already
   implement. (No guide change.)
2. **Heatmap staging**: guide 07 said *(lands S3)* twice while 04-debugging gates the
   heatmap behind an information-architecture review (and guide 06 already said so).
   **Fixed in the guide** — 07 now matches 06 and the design doc.
3. **`:epoch-restore`** is the design-doc-ruled render-cause name (04-debugging §2
   causes table; Xray IA draft) — the guide agrees with its owning doc, so it stands.
   The residual issue is design-corpus vs core-spec naming drift
   (`:rf.epoch/restored` / `:epoch-restored` elsewhere) — one to resolve at spec
   promotion, not in the guide.
4. **"Patched React 19.2.4+ peers"** (05-production §5) vs guide 01's plain-install
   framing — most plausibly "React's own patch releases ≥ 19.2.4", in which case the
   guide is fine; if it means locally patched peers, 01's install story must say so.
   Flagged for a ruling rather than guessed at.

### 3.3 Guide examples vs core re-frame2 spec

**Clean:** `(rf/reg-event id (fn [cofx ev] {:db …}))` is exactly the one v2 event form
(Spec 001/EP-0018; the retired names are hard errors); `(rf/reg-sub id (fn [db q] …))`
is Mode-1 `reg-sub` per API.md; `(rf/init! ui/adapter)` appears verbatim in Spec 006
(§ adapter selection at boot); `:initial-events` is the correct frame-plan key with
exactly-once ENSURE semantics (Spec 002/EP-0027 — re-recorded but not replayed on
reuse, matching 01/05's prose); `compute-sub` is the Spec-008 testing surface guide 09
cites; and `:rf.ui/*`, `:rf.error/*`, `:rf.warning/*` are all reserved rows in
Conventions.md, with the three placeholder keywords named there as the closed
vocabulary. `:rf/resource` matches Spec 016 with two spec-superset details worth a
guide clause: the query map also takes `:scope`, and the status enum also includes
`:idle` — 03/10 show a subset and could say so ("the full enum is Spec 016's").

**Two real errors to fix:**

1. **06's catalogue sentence is wrong about where three ids live.** "The runtime ids
   are catalogued (Spec 009)" — but `:rf.error/dispatch-disconnected`,
   `:rf.warning/unregistered-event-id`, and `:rf.warning/placeholder-in-dynamic-vector`
   are catalogued in **Spec 004-Views**, not 009 (only `no-frame-context` and
   `ui-test-overlapping-act` have 009 rows). Fix the citation ("catalogued in the
   spec's error registers — Spec 004 for the view-layer ids, Spec 009 for the
   runtime-wide ones") or move the rows.
2. **The `:epoch-restore` cause keyword doesn't exist in the spec.** A restore emits
   `:rf.epoch/restored` (Tool-Pair §Time-travel); the suppression-reason spelling is
   `:epoch-restored`. If the render-cause vocabulary means to introduce a *new*
   `:epoch-restore` cause kind, it drifts from both existing spellings; align it
   (likely `:epoch-restored`) or cite the design-doc ruling that names it.

---

## Stage 4 — Voice

**Overall:** dense, confident, information-rich — and closer to a spec brief than to
the "friendly, gentle, informative" target. The bones are excellent: concepts arrive
in the right order, every claim has an example, forward references are disciplined,
and the aphorisms ("you are the click", "There is no fifth") give it personality worth
keeping. The problems are concentrated and fixable:

### 4.1 The README opens with contributor talk

The first paragraph a guide reader meets is fixture-pipeline meta — CI coverage
percentages, a JVM test path, the `;; guide:no-fixture` convention, a pointer into
`drafts/`. That is maintainer information on the user guide's front door. Move it to a
short "About these docs" note at the bottom (or into the fixture draft) and open the
README with what the second paragraph already does well: what the library is, the
chapter table, and the two "coming from" on-ramps.

### 4.2 Getting-started buries its lede under the digest machinery

01's shadow-cljs paragraph ("On a daemon's version-zero pass, the hook clears retained
output for UI macro consumers…") is the densest prose in the corpus, positioned before
the reader has seen a single view. A newcomer needs: paste these two settings, here's
the one symptom if you forget them, the why lives in [How it works]. Same page: the
peer-floor sentence ("planned as React 19.2.4+… takes effect when the artifact's peer
contract ships") is release-engineering voice; a footnote carries it fine.

### 4.3 Stage notes have grown into a second navigation system

Every chapter now opens with an italic stage paragraph, and some (08, 09) thread stage
qualifiers through nearly every section. The honesty is non-negotiable pre-alpha; the
*layout* is negotiable. Recommendation: one standard single-line badge under each H1
("Everything unmarked ships on main today; *(lands S3)* marks a final contract landing
later"), keep the inline markers, and delete the per-page stage paragraphs — their
content is recoverable from the markers themselves. 08 additionally wants its
parenthetical stage clauses pulled to section-head badges; three markers inside one
sentence (the `client-only` line) is where gentle readers give up.

### 4.4 Fragment chains where sentences should be

The register memory applies ("tight, not terse; full sentences over dash-chained
fragments"). The reference-shaped pages (04's table, 07's table) earn their density;
the teaching pages don't. Worst offenders: 03's `local` block
("`(local initial)` → `[value set!]`. Re-renders this view only; host state
underneath; not time-travelled — deliberately.") and several 05 bullets. These want
one pass converting arrows and semicolon chains into sentences — the content is
right; the rhythm is a spec's.

### 4.5 Progressive-disclosure inversions (two)

- 02 teaches **Presence** (S4, animation lifetimes) *before* Props discipline and
  Interop — the advanced optional feature interrupts the core path. Move it after
  Interop, or to the end of the page.
- 09's first mounted-test example is the hardest code in the guide (nested Promise
  chains, `async done`, rejection callbacks). Show the trivial mounted test first;
  then the full-ceremony one, introduced as "the complete shape you'll copy for real
  suites".

### 4.6 Gentleness, specifically

Mostly the sternness lands as charm ("read twice" is fine; the didactic-error framing
is genuinely friendly). Three spots overshoot: "What a Turing-complete mess they have
become" belongs to the repo README's polemic register, not a guide teaching newcomers
(02 note: it isn't in the guide — checked; the guide's equivalents are fine); "that's
an API bug, not a documentation problem" is contributor-facing (moves out with 4.1);
and the bare "There is no fifth." could take a softening clause ("There is no fifth —
and that's the feature") without losing its point. Everything else: the examples do
the gentle work, and they're good.

### 4.7 What's already right (keep)

The four-inputs table opening 03; the decision table in 04 with its two worked rows;
the "What you just didn't write" closer in 10; the "Coming from…" paragraphs; the
did-you-mean error framing in 05; chapter 10 as a whole — it's the best page and the
model for the register the rest should relax into.

---

## Applied 2026-07-14 (same session)

Done directly in `guide/`:

- **README** — contributor/fixture meta moved to a bottom "About these docs" note;
  front door now opens with the pitch + chapter table; `lease` straggler clause
  harmonised with 03; "where did X go?" table added for React arrivals; chapter 11
  row added. *(4.1, 3.1-1, 2.2)*
- **01** — digest paragraph replaced with recipe + symptom + pointer to 11;
  peer-floor sentence parenthesised; minimal project-skeleton note added; stage note
  reframed as the "you are the click" REPL beat. *(4.2, 1.1)*
- **02** — Presence moved below Interop (shipped core reads contiguously); short
  Styling section added. *(4.5, 2.3)*
- **03** — "no fifth" softened; `local` fragment chain rewritten as sentences;
  lease section gains the `:idle`/`:scope` subset clause and the who-fulfils pointer
  (resource system + core guide + 10); "See it live" beat on subs. *(4.4, 4.6, 3.3,
  1.2)*
- **04** — "See it live" beat after the vector pitch; window-listener cross-reference
  to 05's `error-reporter`. *(1.2, 2.3)*
- **05** — "See it live" beat on frame isolation. *(1.2)*
- **06** — catalogue citation corrected (Spec 004 view-layer ids + Spec 009) with the
  ships-with-its-stage clause; schematic `view-manifest` REPL fence; new "Pairing
  with an AI on the live app" section. *(3.3-1, 1.2, 1.3)*
- **07** — heatmap markers corrected to "staged behind its Xray integration review"
  (stage note + both mentions); measure-your-own-build paragraph
  (shadow build-report). *(3.2-2, 2.3)*
- **08** — `ssr-ring` scope note under "Rendering a page". *(2.3)*
- **09** — simple read-only mounted test now precedes the full-ceremony example.
  *(4.5)*
- **10** — "Two codas" closer: serve it (SSR) + drive it (Xray/pair). *(2.3, 1.2)*
- **11-how-it-works.md** — new page: compiler, build digest, reactive core
  (render-probe/commit-acquire, drain economics), `rf=` memoization, compiled event
  sites + sync door, two emitters, HMR mechanics, dev/prod split. Sourced from design
  docs 02/03/05. *(2.1)*

## Addendum — frame-initialisation ruling (2026-07-14, later the same session)

Mike ruled while pair-reviewing guide 01's test example: **one way to create and
initialise frames** — `rf/make-frame` + `:initial-events` (with `[:rf/set-db {…}]` as
the standard seeding step). `ui.test/frame {:app-db seed}` and `ui.test/render`'s
`{:app-db v}` frame-minting option are a second initialisation grammar (the fact that
they desugar to `[:rf/set-db]` defends the semantics, not the API surface) and are
**removed from the contract**. Applied here: guide 01/04/09/10 respelled to
`rf/make-frame` (04 and 10 now initialise through the app's own events — the stronger
idiom), 09's Tier-1 bullet and stage note restated, `07-testing.md`'s contract table
updated. The implementation respell (delete `ui.test/frame`, drop `render`'s
`{:app-db}` branch, ~56 call sites across 15 files incl. the guide-09 fixture) is
bead-filed for dispatch.

## Remaining follow-ups (not done this session)

1. **Talking-to-servers page** — the highest-value content gap (fetch side of
   `:rf/resource`, `:rf.http/managed` → event → lease, end to end). *(2.2)*
2. Routing's view-layer face (links, active state, per-pane routes). *(2.2)*
3. Migration-from-Reagent page — S6, flag only. *(2.2)*
4. docs/core cross-link pass through all chapters. *(2.4)*
5. Guide playground build + Story/fixture convergence note in the pipeline draft
   (S6). *(1.4, 1.5)*
6. Per-page stage-note compression to a standard one-line badge — deliberately *not*
   done wholesale: the notes carry real per-surface information; revisit if they're
   still growing at S3. *(4.3)*
7. Upstream, non-guide: `drafts/root-identity-and-mount.md` §9 adopt the test-root
   tightening; `:epoch-restore(d)` naming drift at spec promotion; "patched React
   peers" wording ruling. *(3.2)*
