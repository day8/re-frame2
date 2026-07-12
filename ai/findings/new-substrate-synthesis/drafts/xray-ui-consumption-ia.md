# Xray consumption of the UI substrate — the S3 enrichment IA

**Status:** draft for S3 dispatch · 2026-07-12. Owning design doc: [`../04-debugging.md`](../04-debugging.md)
(§5 "Tool integration" — **enrich existing surfaces first**; new panels come only after an
information-architecture review). Workstream: W7a ([`../11-adoption-workstreams.md`](../11-adoption-workstreams.md)).
Stage: the S3 epic ([`../12-implementation-plan.md`](../12-implementation-plan.md) — "debugging-as-consumer").
Lifecycle facts per [`../03-reactivity-and-ownership.md`](../03-reactivity-and-ownership.md) §4.
Shipped-Xray ground truth: `tools/xray/spec/*` as of today. Manifest ground truth: the merged S1
registrar entry (`implementation/ui/src/re_frame/ui/compiler.cljc` → `registrar/register! :view id
{… :rf.ui/manifest manifest}`).

This page is the concrete IA the S3 worker implements from. Taste decisions are made **here**,
before the stage, not inside an implementation branch. Scope is Xray only — the Story and Pair
sides of W7a have their own contracts (04 §5); their S3 consumption is filed as the S3g brief in
[s3-bead-briefs.md](s3-bead-briefs.md).

**W7a's "manifest reverse-indexes" note:** the reverse-index *data* (which views read sub X;
which views dispatch event Y) ships as the `re-frame.ui.tool` projections `view-dependencies` /
`view-event-sites` over registrar manifests (04 §5 — the S3e brief's tool tier), not as an Xray
panel; the browse-manifests-as-structure UI is exactly the §3 Static-mode question deferred to
the post-S3 IA review. Xray's S3 consumption of manifests is the §2A site join and the §2B
descriptor chips only.

## 0. Vocabulary mapping — 04's surface names → the shipped IA

04 §5 was written against Xray's conceptual surfaces; the shipped IA (post rf2-5gl5r, rf2-gbz39,
rf2-ee38b.2) names them differently. This mapping is normative for S3 — do not go hunting for
panels that no longer exist:

| 04 §5 says | Shipped surface (real name) | Spec of record |
|---|---|---|
| "Reactive view gains the causes vector + occurrence identity" | The **Views** tab (Dynamic L4, mnemonic `v`, registry key `:views`) | `tools/xray/spec/021-Dynamic-Panel-Designs.md` §3 (012-Views.md is superseded — do not edit it except its pointer) |
| "Event view gains event-site provenance" | The **Epoch** panel's DISPATCH SITE section + the **L2 event list** hover tooltip + the **Trace** tab's dispatch rows | `018-Event-Spine.md` §4/§5.1/§5.3 · `016-Auxiliary-Panels.md` · `023-Trace-Panel.md` |
| "Epoch timeline gains per-view commit rows and the `:epoch-restore` cause" | Per-view commit rows land in the **Views** tab — the Epoch panel deliberately dropped render rows ("renders → Views tab", 018 §5.1 "What's dropped"). The `:epoch-restore` cause renders wherever causes render (Views tab rows for the post-restore epoch). | `021` §3 · `002-Time-Travel.md` |
| "Issues gains the new warning families" | There is **no Issues tab** (removed per rf2-gbz39 Option (c)). The families flow into the **inline issue surfaces**: Epoch-panel inline surfacing + the L2 event-row pink-wash + the always-on issues ribbon signal. | `018` §5.4 · `016` |
| Manifests as browsable app structure (04 §1's before-mount evidence layer; no 04 §5 bullet names a panel for it — correctness pass note) | The **Frames** tab's per-frame descriptor sets (`[:view id]` descriptors of the resolved image, EP-0023) — plus the registrar reads the Views tab already does for view metadata. Note the Static-mode Views sub-tab was **removed** (rf2-b2fif); reinstating one is a new-panel question, not an S3 enrichment (§3 below). | `026-Module-View-Panel.md` §8 |

## 1. Enrichment inventory (surface → what it gains)

Everything below is enrichment of an existing surface. The v1 emit obligation is the **schema**,
not panels (04 §5); every trace shape Xray consumes must exist as a Spec 009 catalogue row first
(one-catalogue rule, rf2-cs0kd1).

| # | Existing surface | Gains |
|---|---|---|
| A | **Epoch panel · DISPATCH SITE** (+ L2 hover tooltip, Trace-tab dispatch rows) | View-instance provenance on dispatches originating from compiled view event sites: view id · site identity (from the manifest's `:sites :events`) · occurrence-path · the site's static classification (`:vector` / `:options` / `:fn` / `:dynamic`, `:serializable?`) |
| B | **Frames tab · descriptor rows** | `[:view id]` descriptors enriched from the registrar entry's `:rf.ui/manifest`: template fingerprint, hook signature, capability bits, site counts, source chip |
| C | **Views tab** | Committed instance records as the row substrate for substrate views: occurrence identity on keyed rows; the three-state lifecycle with **qualified retroactive labels** (runtime state / current tool label / historical inference distinguished in the row grammar); `:observations` closing the shared-sub gap (021 §3.2 constraint 2) for substrate views; `:owned? false` honesty on Story-override edges |
| D | **Views tab · per-view cause labels** (+ the §3.6 causation chip) | `:rf.view/causes` **as a vector** — multi-cause summary in the row, full vector in the chip; bounded prop precision (changed top-level slots only); the `:epoch-restore` cause carrying operation token + target epoch |
| E | **Inline issue surfaces** (Epoch inline + L2 pink-wash + issues ribbon) | The new dev warning families (03 §11 + 04 §6) — flows through the existing `:op-type` severity predicate; near-zero Xray code |

**Global empty-state law (binding for every row below):** each enrichment is *evidence-keyed*.
It renders iff the focused cascade carries the new-substrate evidence ops, or the registrar
`:view` entry carries `:rf.ui/manifest`. An app with no UI-substrate views (legacy `reg-view`
adapters, or no views at all) sees **exactly today's rendering** — no placeholder rows, no
"(no manifest)" captions, no empty chip strips. This is Xray's existing silent-when-zero grammar
(sections ABSENT entirely, never "(none)" — 018 §5.1) applied to enrichment deltas.

## 2. Per-surface evidence rows

### 2A. Event/trace lens — view-site provenance on dispatches

The DISPATCH SITE section today reads `:rf.trace/call-site` off the `:rf.event/dispatched` trace
and renders `src/cart/views.cljs:127 [code] · via :ui · origin :app`. Per the 04 design rule —
**attribution is emitted at the cause site, never reconstructed** — a dispatch that leaves a
compiled event site carries its provenance on the dispatch envelope; Xray never walks DOM or
Fiber to recover it.

| Field | Source datum | Formatting rule |
|---|---|---|
| View id | dispatch-envelope tag stamped at the site (view id from the emitting instance) | Second caption line under the source-coord chip: `view :cart/row · site :on-click #17` — view id in the accent keyword tone the panel already uses |
| Site identity | the envelope's site id, joined to the manifest entry in `:sites :events` (`{:prop :handler :path :classification :serializable?}`) via the registrar `:view` entry | `site <prop> #<site-id>`; the hover tooltip discloses the site's template path + the registered handler vector shape |
| Occurrence | `occurrence-path` (04 §1 — e.g. `[{:site 17 :key 42}]`) | Appended ` · key 42` for a single-segment path; multi-segment paths render the full vector in the tooltip only. **Occurrence keys route through the classification policy before display off-box** (04 §7) — a redacted key renders its sentinel per 018 §12, never the raw value |
| Static classification | manifest event-site `:classification` + `:serializable?` | A serialisable `:vector`/`:options` site adds nothing (the shape is already the EVENT section). A `:dynamic` site appends the muted token `(dynamic)` — the static surface is honest about what it cannot know (04 §3); never fabricate an event shape for it. `:fn` sites render `(fn — opaque)` with the "body merely dispatches — use a vector" suggestion in the tooltip where the manifest carries it |

Sibling rows, same fields, denser packing:

- **L2 event list** — no new columns and no glyphs (the row anatomy is locked glyph-free per
  rf2-pjjwh). The hover tooltip (the designated home of dropped detail, 018 §4) gains one line:
  `view :cart/row · site #17 · key 42`.
- **Trace tab** — the two-line `event:dispatch` row's second line gains `· view :cart/row site#17`
  after the source coord. Two-line rows are this surface's forensic budget; nothing else changes.

**Loss accounting:** dispatch provenance rides the trace ring; the existing per-frame ring +
suppressed-counter contract (013-Trace-Consumer) already owns drop accounting here. No new
display — a dropped dispatch trace was already a dropped row.

**Empty state:** dispatches not originating from a compiled site (handler-fn dispatches, timers,
fx, legacy adapter views) render the DISPATCH SITE section exactly as today. No `view` line, no
placeholder.

### 2B. Frames tab — `:view` descriptors gain manifest facts

The Frames tab already renders each frame's resolved image as `{[kind id] descriptor}` rows with
per-descriptor provenance (026 §8). Enrichment: when a `[:view id]` descriptor's registrar entry
carries `:rf.ui/manifest`, the row gains a muted chip strip.

| Field | Source datum (registrar `:view` entry → `:rf.ui/manifest`) | Formatting rule |
|---|---|---|
| Template fingerprint | `:template-fingerprint` (`tf1-…`) | Short-hash chip (first 8 chars after the `tf1-` prefix); full value in the tooltip. Answers "are these two frames running the same compiled view?" at a glance |
| Hook signature | `:hook-signature` (`hs1-…`) | Tooltip-only (it matters for HMR preserve-vs-remount forensics, not at-a-glance) |
| Capability bits | `:capabilities` (`#{:raw :html :foreign :custom-element :spread}`) | Compact lower-case tokens in manifest order, e.g. `raw · foreign`. Empty set → **no strip segment** (absence is the production-weight story — "why does this view carry a client runtime?" answered by the bits that ARE there, 04 §4) |
| Site counts | `:sites` (`{:events [...] :subs [...] :leases [...]}`) | `2 subs · 3 events · 1 lease`; zero-count kinds omitted |
| Source | `:source` (`{:file :line :column}`) | The standard `[code]` source-coord chip — same affordance as every other click-to-source chip; paths are project-relative (04 §7) |

**Bounded precision:** the chip strip is summary-only. Prop slots, props schema, per-site shapes
live in the drill (the descriptor's expanded inspection), rendered via `inspect` so classification
sentinels apply. Manifests carry shapes and source, never live values (04 §7) — there is nothing
to redact at this surface, but the renderer goes through the standard path anyway.

**Loss accounting:** none needed — the registrar is not a bounded buffer.

**Empty state:** a `[:view id]` descriptor without `:rf.ui/manifest` (legacy `reg-view`
registration) renders exactly as today. A frame with no `:view` descriptors: unchanged. Production
builds carry no manifests (the emit is `goog.DEBUG`-gated, I-12) — moot for Xray, which is itself
erased from production.

### 2C. Views tab — instance records, occurrence identity, the three-layer lifecycle grammar

Committed instance records (04 §1) become the row substrate for substrate views in the panel's
existing sections. Published only at connected commit; speculative renders publish nothing — so a
row here is always a fact about a commit, never about an attempted render.

**VIEWS RE-RENDERED rows** (one row per committed instance record in the focused epoch):

| Field | Source datum (instance record) | Formatting rule |
|---|---|---|
| Name | `:view-id` (+ the manifest's `:display-name` — it lives inside `:rf.ui/manifest`, not as a top-level registrar-entry key) | Existing grammar: display name + `[code]` chip (chip now sourced from the manifest `:source`) + hover-highlight on the rendered root (the compile-time `data-rf2-source-coord`/render-key annotation keeps today's attribute vocabulary working day one — 04 §4) |
| Instance | `:render-key`, `:parent-render-key` | `#1042` muted suffix; two instances of one view are distinguishable by render-key alone. `:parent-render-key` powers the drill's hierarchy line — it does NOT put a parent name in the row (the rf2-8ve8z lock: the parent is never named in props attribution — stands) |
| Occurrences | `occurrence-path` on the record's evidence | A row whose instance emitted occurrence-distinct evidence carries `· N occurrences`; the drill lists each path (`[{:site 17 :key 42}]`). Keys render through the classification policy (04 §7) |
| Generation | `:generation` | Shown only when the epoch contains an HMR cause (`gen 3` muted); otherwise omitted |
| Cause summary | `:rf.view/causes` | §2D below |

**Lifecycle rows — the three-layer grammar (binding, 03 §4 / 09 §codex2 F6).** The panel's
UNMOUNTED VIEWS section keeps its name and its one-row-per-view shape; the per-row *tag* is where
honesty lands. The runtime emits exactly three states, and the cleanup-time fact is always
`:disconnected {:reason :unknown}` — so the tag grammar distinguishes three things that today's
single `unmounted` tag conflates:

| Layer | Row tag | Rule |
|---|---|---|
| Runtime state (the emitted fact) | `disconnected · cause unknown` | The only tag a live `:disconnected` interval may carry. No tool may label it Activity-hidden or unmounted without proof — rendering `unmounted` here would be fabricated precision |
| Current tool label | same as runtime state until proof arrives | The tag IS the tool label; it updates in place when an annotation lands — transitions update the record and the row **without fabricating renders** |
| Historical inference (qualified retroactive annotation of the *prior interval*) | `activity-hidden (proven: reconnect)` · `unmounted (proven: host teardown)` · `unmounted (inferred: gc · approximate)` | Rendered in the annotation style (dim/italic tag + proof token), visually distinct from the runtime-state style. The gc form is explicitly qualified: no timestamp is shown for it (there is none — 03 §4), and the tooltip says "best-effort, eventual" |
| Dead | `dead · frame destroyed` | `:dead` renders as a runtime state (it is one); the accompanying loud error is already an issue-surface concern |

Legacy adapter views keep today's plain `unmounted` tag — their evidence never claimed more.

**Observations → the reactive-flow graph.** The record's `:observations` vector is the deref-subs
evidence 021 §3.2 constraint 2 has been waiting for: for substrate views the sub→view edges come
from observations (all readers, not just the recompute-triggering view), so the `×N (shared)`
annotation and the precise reactive-vs-props tag render fully. A `{:kind :story-override …
:owned? false}` observation renders as an `override`-labelled edge and is **never** drawn as a
computing subscription — a visual override, not evidence (04 §1). Legacy views keep the current
triggering-view-only limitation; the graph must not fabricate edges for them.

**Loss accounting (04 §2 — the binding display rule):** every bounded buffer reports `total` /
`retained` / `dropped`. Section headers currently claim exact counts (`VIEWS RE-RENDERED (12)`).
When the instance-record buffer reports `dropped > 0` for the focused epoch, the header renders
`VIEWS RE-RENDERED (12 of 15 · 3 dropped)` and the L3 tab-strip count badge for the Views tab
carries a muted `+` (a truncated count must not present as complete). When `dropped = 0` the
rendering is byte-identical to today — the loss chrome itself is evidence-keyed.

**Empty state:** an epoch with no substrate-view commits renders the panel exactly as today
(legacy rows unchanged; the sparse "no reactive cascade" case untouched). No lifecycle tags, no
occurrence suffixes, no loss chrome appear for apps without the substrate.

### 2D. Causes — the vector, rendered honestly

`:rf.view/causes` is a **vector** (04 §2): a commit can have several causes, and the header
summarises. The Views tab's per-view cause label today is singular (`← :sub-id` / `← props`,
rf2-bhi3t). The upgrade:

| Case | Row renders | Drill (the §3.6 causation chip / tooltip) |
|---|---|---|
| One cause | Exactly today's grammar: `← :cart/item` / `← props` — zero visual delta for the common case | Full cause map: target, query, version from→to, epoch, upstream event/sub |
| N > 1 causes | `← 3 causes` summary (04 §2's header rule: "3 dependencies changed in `::refresh-complete`") | The full vector, one line per cause in emit order, each in its per-kind grammar |
| `:prop` cause | `← props(:qty :status)` — **changed top-level slots only**, names not values. This is the sound cheap promise (04 §2): nested paths are a dev *diff view on demand* behind the drill, never a default emit, and never in the row | Drill offers the on-demand deep diff; prop values render via `inspect` so §12 sentinels apply |
| `:epoch-restore` | `← restore (op <token> → epoch #N)` — the repaint is caused by the restore *operation*; the row names the operation token + target epoch | Old epoch records are never rewritten or back-filled — scrubbing back over pre-restore epochs shows their original causes |
| `:story-override` | `← override` with the `:owned? false` honesty carried into the drill (override id, from→to) | |
| `:foreign-or-react` | `← foreign/react` — the honest fallback; never fabricate precision | |
| `:mount` | No cause label — the existing `(mounted)` grammar already conveys the first connected commit | |

`:hmr` / `:hmr-remount` / `:hydration-correction` / `:reconnect-correction` / `:local-state` /
`:frame` / `:context` / `:resource` render in the same row-summary + drill-detail split, each with
the evidence column 04 §2 assigns it. The mount row and the single-cause row are deliberately
byte-identical to today: enrichment must not make the common case noisier.

### 2E. Warning families — ride the existing issue surfaces

The dev-runtime warning roster (03 §11: `:rf.warning/unregistered-event-id`,
`:rf.warning/placeholder-in-dynamic-vector`, `:rf.warning/cross-frame-carried-op`,
`:rf.warning/render-phase-dispatch` / `-set!`; plus 04 §6's dev-runtime and suppressible
accessibility warnings) flows into the inline issue surfaces **automatically**: the issue
predicate keys off the universal `:op-type` severity axis (`:op-type :warning` / `:rf.warning/*`),
not an enumerated op list (018 §4, rf2-b8guz). The S3 obligation is therefore framework-side —
each family lands as a Spec 009 catalogue row with the right `:op-type` — plus a spec note on the
Xray side. Every warning carries the stable site id shared between build logs and Xray (04 §6), so
the inline row's `[code]` chip resolves.

## 3. The sub-domain tab question — recommendation: no new tab at S3; named question for the IA review

Mike's standing rule: cohesive sub-domains earn their own L4 tab rather than piling into a busy
tab (Routing, Resources, Graph, Frames all landed that way). Do mounted-views + manifests justify
one **now**?

**Recommendation: no — ride the existing tabs through S3, and put one named question to the
post-S3 IA review.** Reasons, in order of weight:

1. **The ruling caps S3 at enrichment.** New panels (mounted views, SSR/roots, heatmap) come only
   after an IA review shows an existing surface can't answer the question (04 §5). A new tab is
   the flagged IA-review item, not an S3 deliverable — this page merely tees the review up.
2. **A "Views" tab already exists.** The Dynamic L4 Views tab owns per-epoch render evidence, and
   §2C/§2D land the instance-record, lifecycle, occurrence, and causes content there. A second
   views-shaped surface would collide with the hardest-won name in the tab bar.
3. **The genuinely uncovered question is Static-shaped.** "What is mounted *right now*, with what
   lifecycle labels?" and "browse the manifests as structure" are event-independent questions —
   if a surface ever earns tab space, it is a **Static-mode sub-tab**, not a tenth Dynamic tab.
   And note the history: the Static Views sub-tab was removed (rf2-b2fif) because "the info is
   already in the source code" — a rationale that **predates the manifest**. The manifest turns
   view structure into queryable data (sites, capability bits, fingerprints, prop slots — the W7a
   manifest reverse-indexes), which is exactly the calculus the IA review should re-run.
4. **The demand evidence doesn't exist yet.** The sub-domain has no users until S3 ships; the
   enriched Views tab + Frames descriptors + the Pair's read-only projections (`view-manifest`,
   `mounted-views`, `explain-render` — the tool tier, 04 §5) may cover the questions in practice.
   Earn the tab with observed gaps, not anticipation.

**The tradeoff stated:** riding existing tabs means "current mounted set across epochs" has no
single at-a-glance home during S3 — it is assembled from the Views tab (per-epoch) and the Pair
projection (`mounted-views`). If S3 dogfooding shows that assembly failing repeatedly — operators
paging through epochs to reconstruct the mounted set — that is the IA-review trigger, and the
review's question is: *reinstate a Static-mode Views sub-tab (registry browse over manifests +
live mounted set with the three-layer lifecycle grammar), reversing rf2-b2fif on new evidence — or
keep the two-surface split.* Cost of deferring: a stage of mild friction for one question. Cost of
not deferring: a tab bought before its demand is proven, against a standing ruling.

## 4. Explicit S3 non-goals

- **No new panels** (the 04 §5 ruling). No mounted-views panel, no SSR/roots panel, no heatmap
  panel. The v1 emit obligation is the schema.
- **No timeline scrubbing additions.** The existing ribbon/L2 scrubber and the Epoch panel's
  explicit rewind are untouched; no per-view timeline scrubber, no render-timeline lane.
- **No render-flame views.** No flamegraph, no waterfall of commit durations. React Performance
  Tracks correlate independently via render-key/epoch ids (04 §4) — that is the flame story.
- **Restore/time-travel interactions unchanged.** Passive scrub rebases panels; explicit rewind
  stays the Epoch-panel button → `:rf.xray/reset-to-epoch` with the six named restore failures
  (002-Time-Travel). S3 adds only the `:epoch-restore` **cause rendering** (operation token +
  target epoch, §2D); old epoch records are never rewritten.
- **No L2 row-anatomy changes.** The event list stays glyph-free, four columns, 80-char cap
  (rf2-pjjwh); provenance rides the tooltip and the L4 panels.
- **No reveal affordances for classified values.** Occurrence keys, override values, query args,
  prop values all route through the standard classification policy (04 §7, 018 §12). Nothing in
  this delta adds a sentinel bypass.

## 5. Implementation checklist (the S3 bead lifts this)

Ordering: catalogue rows first (everything consumes them), then the small self-contained
provenance rows, then the Views-tab body of work, then the sweeps. Per the standing rule, **every
Xray PR updates `tools/xray/spec/*` in the same PR** — the spec file named per row is the one to
touch; a PR that deliberately leaves a listed file untouched states "spec unchanged because X".

| # | Enrichment | Data source | Xray spec file(s) to update |
|---|---|---|---|
| 0 | **Precondition (framework-side, sequenced before all Xray rows):** Spec 009 catalogue rows for every consumed shape — dispatch-site provenance tags, instance-record publish, causes vector, lifecycle transitions + retroactive annotations, loss counters, the 03 §11 / 04 §6 warning families (one-catalogue rule, rf2-cs0kd1) | S3 emit work in `re-frame.ui` / core | none (framework `spec/009` + `spec/Spec-Schemas.md`; hot-zone — sequence, never parallel) |
| 1 | DISPATCH SITE view-site provenance line (§2A) | `:rf.event/dispatched` trace tags + registrar `:rf.ui/manifest` `:sites :events` | `018-Event-Spine.md` §5.1 · `016-Auxiliary-Panels.md` (Epoch tab) |
| 2 | L2 hover-tooltip provenance line + Trace-tab dispatch-row second-line provenance (§2A) | same envelope tags | `018-Event-Spine.md` §4 · `023-Trace-Panel.md` (row anatomy) · `013-Trace-Consumer.md` only if new op families join the ring contract |
| 3 | Views tab: causes-vector row grammar + bounded prop precision + on-demand prop diff drill (§2D) | instance record `:rf.view/causes` | `021-Dynamic-Panel-Designs.md` §3 (§3.2 attribution note + §3.6 chip) |
| 4 | Views tab: instance-record rows — render-key/parent-render-key, occurrence identity, generation (§2C) | committed instance records in the focused epoch's trace slice | `021-Dynamic-Panel-Designs.md` §3 |
| 5 | Views tab: three-layer lifecycle tags in UNMOUNTED VIEWS (runtime state / tool label / qualified inference) (§2C) | lifecycle facts + retroactive annotations (03 §4) | `021-Dynamic-Panel-Designs.md` §3 |
| 6 | Views tab: `:observations` → full sub→view edges + `×N (shared)` for substrate views; `:owned? false` override-edge honesty (§2C) | instance record `:observations` | `021-Dynamic-Panel-Designs.md` §3.5 (constraint 2 gains its "closed for substrate views" note) |
| 7 | Loss-accounting chrome: `(N of M · K dropped)` section headers + L3 tab-badge `+` when `dropped > 0` (§2C) | buffer `total`/`retained`/`dropped` (04 §2) | `021-Dynamic-Panel-Designs.md` §3 · `018-Event-Spine.md` §5 (tab strip) |
| 8 | `:epoch-restore` cause rendering (operation token + target epoch) (§2D) | restore-operation evidence on the post-restore epoch's causes | `021-Dynamic-Panel-Designs.md` §3 · `002-Time-Travel.md` (cross-reference only — interactions unchanged) |
| 9 | Frames tab `[:view id]` descriptor chip strip (fingerprint · caps · site counts · `[code]`) (§2B) | registrar `:view` entry `:rf.ui/manifest` | `026-Module-View-Panel.md` §8 |
| 10 | Warning families: verify the issue predicate covers each new `:rf.warning/*` id end-to-end (Epoch inline + pink-wash + ribbon); no new renderer expected (§2E) | catalogue rows from row 0 | `018-Event-Spine.md` §5.4 note · `016-Auxiliary-Panels.md` |
| 11 | Attribution routing: substrate views bypass the legacy post-render/cloneElement attribution machinery entirely (they never need it — 04 §1); the machinery itself stays for legacy adapters and is **deleted at W13**, not S3 | n/a (routing change) | the spec file owning the touched attribution section (state "no visual change") |
| 12 | Registry catalogue: every new `:rf.xray/*` sub/event/effect these rows add | n/a | `014-Registry-Catalogue.md` |
| 13 | Acceptance: the §1 empty-state law asserted — a legacy-only fixture app renders byte-identical panels with the enrichment code present; plus the 04 §8 gates that land on Xray surfaces (two instances distinguishable; occurrence-paths on keyed rows; override rows honest; restore causes name op + target; loss accounting present; no fabricated lifecycle labels) | fixtures per 07 §6 | `017-Test-Coverage-Matrix.md` |

Rows 1–2 are parallel-safe with each other; rows 3–8 all touch `021` §3 and the Views panel —
**one worker, sequential**, per the same-surface clustering rule. Row 9 is isolated. Row 0 is
hot-zone (framework spec tree) and gates everything.
