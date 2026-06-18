# Facade Accretion — The Cross-Cutting Pattern (Pre-Alpha Removal Discipline)

Status: draft finding — **new in the 2026-06-18 fresh consolidation pass**.
Cross-cutting: this is the single mechanism beneath roughly half the per-area
findings in this corpus. Two independent lenses (minimalist facade, technical
taste) raised it as a cross-cutting observation; it had no standalone write-up
until now.

## Crowding Signal

The facade has accreted in **layers**. Each EP adds its new exports and leaves
the prior generation in place, demoted only by a docstring note ("RETAINED as
internal/migration surface", "publicly REPLACED by …"). The demotion **stops at
the docstring**: it never reaches the export list (`def` still on
`re-frame.core`), the manifest (`:facade? true` row still present), or the spec
(`spec/API.md` still rows it as current public API). The result is a facade that
reads as an archaeological dig — a reader meets two or three complete vocabularies
for one fact and must perform history recovery to know which one is alive.

The disposition data to do this correctly already exists. `migration.cljc:57-142`
records every retired symbol's disposition as data (`:publicly-replaced`,
`:re-expressed`, `:retained-internal`). That is the right tool. The gap is that
the disposition **never fires on the export list, the manifest, or the spec** —
it only annotates a docstring.

## The pre-alpha correction (the load-bearing point)

The natural-but-wrong remedy is "push every disposition through to `^:no-doc` +
manifest removal, but keep the surface reachable for migration." That is still a
back-compat reflex. **re-frame2 is pre-alpha: no back-compat shims, no migration
layers, build it right the first time.** So the remedy is sharper:

> For each retained surface, ask one question: **is it kept because the current
> internal design genuinely uses it, or kept because old code / old callers would
> otherwise break?** In pre-alpha the second answer is **delete**, not demote.

This collapses the usual three-tier outcome (public / retained-internal-for-
migration / gone) to **two**: a surface is either (a) genuinely used by the live
internal architecture — then it is *internal* (its own namespace, never facade /
manifest / API.md), or (b) kept only so prior callers keep working — then it is
**removed outright**. There is no "retained-as-a-migration-bridge" middle tier in
a pre-alpha framework with no external consumers to protect.

What this does **not** delete: substrate the current design actually depends on.
`re-frame.realm` backing SSR's internal `(realm, frame)` routing is "kept because
the design uses it" — legitimate, but internal-only. Pre-alpha kills the
retention-for-compat tier, not the in-use internal substrate.

## Instances this pattern unifies

Each of these is a layer left behind a docstring note; the per-area files carry
the detail. Under the pre-alpha discriminator above:

- **EP-0013 app/realm/module facade exports** —
  [retired-app-composition-vocabulary.md](retired-app-composition-vocabulary.md),
  [retired-vocab-inventory-classified.md](retired-vocab-inventory-classified.md).
  `rf/app` / `rf/module` are `:publicly-replaced` → remove. The realm
  install/query *facade* family is kept-for-compat → remove from the facade
  (the realm *machinery* stays internal where SSR uses it). **This resolves the
  inventory's explicit "Open ruling for Mike" (Cluster A) toward removal:**
  "keep them as labelled-internal *facade* exports" is the non-pre-alpha option.
- **Migration shims** (`migration-map` / `migration-explain` /
  `assert-process-local-frame-id!`) — a migration *layer*. Once the `rf2-pl97nd`
  scrub rewrites the internal call sites, they have no consumer. Pre-alpha keeps
  no migration layer, so the inventory's "PRESERVE — Bead-Plan step 16" stance is
  a pre-EP-0023-era hedge worth revisiting: delete with the scrub, don't enshrine.
- **The two-layer frame model** —
  [frame-object-record-unification.md](frame-object-record-unification.md). The
  EP-0013 record kept alongside the EP-0023 object is the deepest instance of
  accretion. Pre-alpha does not leave this "contentious / needs adjudication" —
  collapsing the layers is exactly what pre-alpha mandates.
- **Stale interceptor/cofx accessors** (EP-0017/0022 left `get-coeffect` /
  `assoc-coeffect` / `get-effect` / `assoc-effect` after removing their audience)
  — [registration-programmatic-forms.md](registration-programmatic-forms.md).
  Remove (the setters are dead even in tests).
- **Granular elision helpers** and **marks imperative residue** (EP-0015 layer
  beneath `project-egress`) —
  [elision-redaction-helpers.md](elision-redaction-helpers.md).
- **Five listener/sink registries** (EP-0008/0015 observation layers with zero
  production callers) —
  [listener-and-sink-registries.md](listener-and-sink-registries.md).
- **Schema setter singletons** beneath `set-schema-fns!`, **trace-projection
  facade re-exports**, **`make-frame-handle`**, the **HTTP test-support
  install/uninstall pair** — folded into boot/config, frame-state, and
  frame-targeting.

The one legitimate retention class is the **throwing removed-name stubs**
(`reg-event-db`, `path`, `unwrap-interceptor`, …): they keep a stale call failing
loudly with an actionable message. Even those are a developer-experience choice,
not a compatibility obligation — and they should be data-table-driven, not N
bespoke throwers (see registration-programmatic-forms.md).

## Proposed remedy

1. **Resolve every disposition to delete-or-genuinely-internal.** No
   "retained-for-migration" facade tier survives. The `migration.cljc`
   disposition data drives it; it must now fire on the export list, the manifest,
   and the spec rows, not just the docstring.
2. **Delete the migration shims with the scrub**, not after some indefinite
   migration window — there is no external EP-0013 consumer, and internal callers
   are being rewritten by `rf2-pl97nd` regardless.
3. **Generalize the `pl97nd.5` guardrail into a standing manifest-hygiene gate.**
   Today it fails only on *retired vocab* appearing as current public API.
   Generalize the invariant: **no `:facade? true` manifest row may carry a
   superseded / retained-for-compatibility disposition.** That makes the cleanup
   permanent — the *next* EP cannot demote-by-docstring-and-leave-the-export, so
   the facade stops re-accreting by construction.

## Classification

Posture/discipline finding + one generalized gate. It does not introduce new API;
it resolves the open facade ruling (toward removal) and turns the per-area
demote/remove recommendations into one enforceable rule. Most of the execution is
already owned by `rf2-pl97nd` for the EP-0013 layer; the new piece is (a) applying
the same delete-not-demote disposition to the non-EP-0013 layers above, and (b)
the generalized manifest-hygiene gate so it holds for future EPs.

## Why this is better

A pre-alpha framework's public surface should teach exactly one live model. Every
layer left behind "for now" is a tax the reader and every future agent pay
forever, and a back-compat reflex the pre-alpha posture explicitly rejects. The
project already has the data (dispositions) and a narrow gate; the move is to let
the disposition mean what it says — gone — and to enforce it so the surface stays
small as the design keeps moving.

## Implementation

- **Disposition: RULED (pre-alpha removal, Mike 2026-06-18).** Not a bead — the
  governing posture is set. It flips the per-area findings from
  demote-and-retain to delete-or-genuinely-internal.
- **One guardrail bead (ordinary).** Generalize the `rf2-pl97nd.5` retired-vocab
  gate into a standing manifest-hygiene check: *no `:facade? true` manifest row
  may carry a superseded / retained-for-compat disposition.* This is the durable
  enforcement that stops the next EP re-accreting; file it as a sibling of
  pl97nd.5 or fold into it.
- **No EP** — posture + a gate, no new public API.
- Execution of the per-layer removals lives in each area file's Implementation
  section; this file is the shared discriminator (use-by-the-live-design = keep
  internal; kept-for-compat = delete).
