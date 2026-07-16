# S3 bead briefs — paste-ready for the S2-boundary filing

**Status:** draft · 2026-07-12 12:22:40 AUSEST (completeness pass additions
2026-07-12: S3g brief, §S4 filing notes, §Coverage note; correctness pass 2026-07-12:
renumbered .13–.19 → .14–.20 — **rf2-vxgfnd.13 is already taken** by the Spec-004
rewrite-merge bead filed 2026-07-12) · epic rf2-vxgfnd files each
stage's children at the prior stage boundary; these are the S3 children (proposed
rf2-vxgfnd.14–.20), drafted so the S2-boundary filing is a paste. S3 = "events +
debugging-as-consumer" per 12 §3 + 08 §2 stage 3. House shape mirrors the filed S2
briefs (.7–.12). Every ⟨fill-at-boundary⟩ item below MUST be resolved by the filer at
the S2 boundary before the paste — the DESCRIPTION blocks are otherwise final.
NOT beads yet; no bd commands were run to produce this file.

---

## Sequencing header

**The spine:**

```
S2 merges (.7–.12, verify MERGED not just closed)
        │
        ▼
  S3a (.14) committed event wiring          ← needs .8 ViewCell committed slots,
        │                                      .9 frame wiring, .10 epoch/flush (Q51)
        ├──────────────┬──────────────┐
        ▼              ▼              │
  S3b (.15)       S3c (.16)           │      S3d (.17) .react tier ← needs .8 + .11 (HMR);
  local/effect/   error-boundary/     │      parallel-safe with S3b/S3c on code surface;
  dispatch-fn     client-only/        │      PRE-FILING GATE: demand-bar consumer or
        │         foreign callbacks   │      Mike row-delta
        │              │              │           │
        └──────┬───────┴──────────────┴───────────┘
               ▼
  S3e (.18) evidence schema + ui.tool/* + Xray consumption
               │                            (framework half after S3a; Xray half after
               │                             framework half)
               ├──────────────────────┐
               ▼                      ▼
  S3f (.19) gates G-7/G-11/G-8   S3g (.20) W7a Story + Pair + mcp-conformance
  + guide activations                 (parallel-safe with S3f; tools/ surface;
  + RealWorld vertical page           needs .17's ui.tool + .11's HMR machinery)
  (last of the gate spine)
```

**Spec-009 hot-zone dwell order (one-owner-at-a-time, never parallel):**
S3a (3 warning rows + `ui-dispatch-unwired` retirement) → S3b (`dispatch-disconnected` +
`render-phase-set!`) → S3d (conditional `jvm-host-op` clause edit, only if
[S3-CONFIRM] #6 → id reuse) → S3e row 0 (evidence/trace shapes). This refines the prep
batch's single-§2.3-paste framing per the 009 co-edit invariant (rows land in the PR
that mints the emit — 009 catalogue rule + the 12 §2b Q61 spec-landing rule: no
checked-in spec claims unimplemented behaviour). Each dwell also carries its
`spec/Spec-Schemas.md` `:tags` co-edit (itself hot-zone). If the mayor prefers one
dwell, collapse into S3a only by re-sequencing S3a AFTER S3b's emits exist — not
recommended.

**Hot-zone flags per brief:** S3a (spec/009 + Spec-Schemas) · S3b (spec/009 +
Spec-Schemas, behind S3a) · S3c (none expected; a possible spec/004-rewrite section
edit for the `:on-error` payload pin — 004 is not on the fixed CLAUDE.md hot-zone list
but coordinate one-owner-at-a-time per the program rule) · S3d (conditional spec/009
clause edit) · S3e (spec/009 + Spec-Schemas row-0 dwell; the Xray tab work itself is
tools/ surface, parallel-safe vs implementation/) · S3f (.github/workflows, sole
toucher; possibly top-level implementation/shadow-cljs.edn if G-8 adds a testbed
build-id — testbed build-ids live in hot-zone shadow) · S3g (none expected — tools/ +
skills surfaces only; skill↔MCP drift gates apply).

**Coordination with S2 outcomes:** S3a consumes .8's ViewCell committed-slot API, .9's
frame-target wiring, and .10's epoch-drain/`flush!` (the Q51 scope pin) — dispatch
committed-frame semantics and the sync door's event→drain→commit sequence are built ON
those, not beside them. S3d consumes .11's generation/HMR machinery (the Fast-Refresh
generation pin). S3e consumes whatever lifecycle/evidence emits .8/.10 actually landed
(row-0 scope shrinks to the remainder). Beads close on PR-open — verify each S2 PR is
MERGED before dispatching its dependents.

---

## S3a (proposed rf2-vxgfnd.14)

**Title:** `ui S3a: committed event wiring — event vectors go live: dispatch, placeholders, sync door, provenance`
**Type:** feature · P2 · **Depends on:** rf2-vxgfnd.8, rf2-vxgfnd.9, rf2-vxgfnd.10

**DESCRIPTION (paste):**

S3 committed event wiring (epic rf2-vxgfnd; blocked by rf2-vxgfnd.8 + .9 + .10 — verify
all three MERGED, not just closed). NORMATIVE SOURCES:
ai/findings/new-substrate-synthesis/02-programming-model.md §3 (handler decision table,
placeholder vocabulary, the sync-door law + S-5-CONFIRMED trigger predicate),
03-reactivity-and-ownership.md §3 (committed-frame resolution), 12-implementation-plan.md
§2b event rows, and drafts/spec-009-ui-catalogue-rows.md §2.3 (the paste batch) — all
tracked: read them in your own worktree at the revision you are implementing, and do
not replace them with a copy from another checkout. Deliver in
implementation/ui (+ one spec/009 dwell): (1) Replace the S1 dispatch-hook seam
(re_frame/ui/runtime.cljs `set-dispatch-hook!`/`dispatch-event!`, default hook throws
`:rf.error/ui-dispatch-unwired`) with real committed-frame dispatch: an event vector in
an `:on-*` position dispatches to the committed frame; per-site stable callbacks read
committed slots (the ruled committed-slots law includes `local` values — `local` itself
lands S3b; do not preclude it); keep the capture-hook seam for ui.test. (2) Placeholder
splicing at dispatch time: the closed scalar vocabulary `:rf.ui/value` / `:rf.ui/checked`
/ `:rf.ui/key` splices at top-level positions of literal vectors only; formalize the S1
console.warn into the catalogued `:rf.warning/placeholder-in-dynamic-vector` emit.
(3) The sync-input door with the S-5-confirmed predicate KEPT UNCHANGED: dispatches from
`:on-input`/`:on-change`/`:on-before-input` sites where the compiler proves the element
controlled (literal `:value`/`:checked` co-present on the same element as the
vector-handler site) drain synchronously within the DOM event — event → drain → commit →
snapshot advance before React's discrete re-render; dynamic props maps, `ui/spread`,
`ui/event`, and bare-fn sites fall back to standard batching with a dev diagnostic
naming the door conditions; ship the risk-register fixture asserting non-input
dispatches still batch (I-6). (4) Dispatch provenance stamped at the cause site, never
reconstructed (04 design rule): the dispatch envelope carries view-id + site id
(manifest `:sites :events`) + occurrence-path + static classification, extending the
`:rf.event/dispatched` trace tags — S3e's Xray rows consume this; the trace-shape
catalogue addition rides THIS PR's 009 dwell. (5) Dev safety net:
`:rf.warning/unregistered-event-id` at render with element coordinates + the
process-global-registrar false-positive caveat in the message. (6) HOT-ZONE 009 dwell —
sole 009 owner while open: paste from the prep batch §2.3 the three rows this PR emits
(`unregistered-event-id`, `placeholder-in-dynamic-vector`, `render-phase-dispatch` — the
dispatch-during-render warning is minted here with the dispatch wiring), retire the
`:rf.error/ui-dispatch-unwired` staging row (this PR deletes the stub, so it carries the
retirement; strikethrough-with-pointer per the plain-fn precedent), resolve the
[CONFIRM-AT-LANDING] cells against the implementation, and land the Spec-Schemas `:tags`
co-edits. The remaining §2.3 rows ride S3b/S3d per the co-edit invariant. Acceptance:
decision-table vector-row fixtures; sync-door fixture (S-5 predicate) + fallback-diagnostic
fixture + non-input-batching fixture, CLJS unit tier (G-8's real-browser matrix is S3f,
not here); placeholder splice fixtures incl. the dynamic-vector warn; grep-clean — no
`ui-dispatch-unwired` emit survives in src; S3-tagged conformance-profile rows touching
handlers pass. Worker rules: worktree guard; foreground gates; commit-then-rebase, never
stash; 009 + Spec-Schemas edits in the same PR as the emits, one hot-zone owner at a time.

**OPEN-ITEMS (resolve before filing):** ⟨fill-at-boundary⟩ exact ViewCell
committed-slot accessor names from merged .8 · ⟨fill-at-boundary⟩ the epoch-drain entry
point + the Q51 `flush!`-scope ruling from .10 (the door drains through it, never a
parallel path) · ⟨fill-at-boundary⟩ .9's landed frame-target resolution surface for
committed-frame dispatch · ⟨fill-at-boundary⟩ confirm the `ui-dispatch-unwired` stub
still exists at boundary (if an S2 PR already deleted it + retired the row, drop that
half of deliverable 6) · ⟨fill-at-boundary⟩ strikethrough-vs-deletion mechanics per the
plain-fn precedent's exact form at paste time.

---

## S3b (proposed rf2-vxgfnd.15)

**Title:** `ui S3b: local + effect + dispatch-fn — ephemeral state, host sync, the stable external dispatcher`
**Type:** feature · P2 · **Depends on:** rf2-vxgfnd.14 (S3a)

**DESCRIPTION (paste):**

S3 body forms + escape-hatch dispatcher (epic rf2-vxgfnd; blocked by S3a — committed
dispatch is the substrate). NORMATIVE SOURCES:
ai/findings/new-substrate-synthesis/02-programming-model.md §5 (local/effect semantics +
the ruled placement law), 03-reactivity-and-ownership.md §6 (effect contract), 06 §1
(JVM subset rows), 12-implementation-plan.md §2b (local·effect / dispatch-fn / lease
rows), drafts/spec-009-ui-catalogue-rows.md §2.3 — all tracked; read them in your own
worktree at the revision you are implementing, and do not replace them with a copy from
another checkout. Deliver in implementation/ui
(+ one spec/009 dwell, sequenced behind S3a's merge): (1) `local` live: host
component-local state deliberately outside re-frame2 epochs; `[value set-fn]` binding;
set re-renders (contrast use-ref); NARROW placement-law fixtures (the F8 ruling):
same-view committed handlers MAY read local values — the guide's search-box seam (local
keystroke text inside `[:search/run text]`) is the canonical conforming fixture. JVM:
initial-value-only + typed setter error is ALREADY SHIPPED (S1 `:rf.error/jvm-host-op`
row; 06 §1) — CONFIRM against the live `local`, do not re-mint; if the row's
"local setters at S3+" clause needs its live-tense flip, that clause edit rides this
PR's 009 dwell. (2) `effect` live: rf= value-deps form + the `:connect` form; cleanup
honoured on dep change, disconnect, unmount; StrictMode dev replay/cleanup
idempotent-safety fixtures; abandoned renders never run effects (zero-retention fixture
extending the S2 leak gates); JVM: effects DO NOT RUN — recorded as capability metadata
only (confirm the S1 capability recording covers the live form). (3) `ui/dispatch-fn`:
returns the per-site stable committed-frame dispatcher for foreign/external callbacks
(guide 05 media-bridge is the consumer); event-boundary decision-table fixtures; the
leaked-listener detector — invocation while the owning cell is `:disconnected`/`:dead`
rejects the dispatch (never routes to a stale/destroyed frame) and emits
`:rf.error/dispatch-disconnected`. (4) `lease` view-level resource-lease semantics
CONFIRMATION fixture (12 §2b: S2 landed owner-token semantics; S3 confirms the
view-level behaviour — a marked confirm, no new machinery). (5) `local` render-phase
guard: `set!` during a render pass emits `:rf.warning/render-phase-set!` (mirror S3a's
landed drop-vs-proceed resolution for `render-phase-dispatch`). (6) HOT-ZONE 009 dwell:
paste `:rf.error/dispatch-disconnected` (ALWAYS-ON — add to the enumeration paragraph +
at least one test through the error-emit listener) and `:rf.warning/render-phase-set!`
from prep §2.3, resolve [CONFIRM-AT-LANDING] cells, two Spec-Schemas `:tags` co-edits.
Acceptance: placement-law fixtures green; StrictMode replay/cleanup fixtures; effect
leak fixture; decision-table fixtures for dispatch-fn; JVM-subset enforcement via
ui.test (07 §2); listener-exercise test for the always-on row; S3-tagged
conformance-profile rows touching local/effect/dispatch-fn pass. Worker rules: worktree
guard; foreground gates; commit-then-rebase, never stash; 009 dwell only after S3a's
merge (one hot-zone owner at a time).

**OPEN-ITEMS (resolve before filing):** ⟨fill-at-boundary⟩ drop-vs-proceed for the two
render-phase rows — mirror whatever S3a lands for `-dispatch`; if S3a hasn't merged at
filing time, note "mirror S3a's resolution" in the bead verbatim · ⟨fill-at-boundary⟩
recovery-keyword spellings against landed emit code · ⟨fill-at-boundary⟩ whether .10's
`flush!`/act semantics change the StrictMode fixture shape · ⟨fill-at-boundary⟩ the
lease-confirmation fixture shape against the .7/.8 landed lease API · ⟨fill-at-boundary⟩
whether the `jvm-host-op` local-setter live-tense clause edit rides here or S3d
(whichever 009 dwell comes first after `local` lands takes it).

---

## S3c (proposed rf2-vxgfnd.16)

**Title:** `ui S3c: error-boundary + client-only + foreign callback tier — the explicit boundaries`
**Type:** feature · P2 · **Depends on:** rf2-vxgfnd.14 (S3a)

**DESCRIPTION (paste):**

S3 boundary components + foreign callback tier (epic rf2-vxgfnd; blocked by S3a —
`:on-error` and `ui/event` ride committed dispatch). NORMATIVE SOURCES:
ai/findings/new-substrate-synthesis/02-programming-model.md §3 (decision table) + §6
(error-boundary semantics, client-only, refs), 06 §1/§3 (server failure policy,
capability-free fallbacks), 07 §3 (the interop conformance set) — all tracked; read
them in your own worktree at the revision you are implementing, and do not replace them
with a copy from another checkout. Deliver in
implementation/ui: (1) `ui/error-boundary {:fallback view :reset-key val :on-error
[:ev …]}` per the 02 §6 phase semantics: catches render/lifecycle throws below it (NOT
event-handler or async errors — those keep their typed paths); `:on-error` dispatches
AFTER the failing commit through a captured live frame (never during render, I-1);
fallback renders with `:error` + declared props and CANNOT recursively dispatch
(fixture); changing `:reset-key` clears the caught error (retry = state change);
JVM/SSR renders the child per the server failure policy (06 §1) — boundaries are a
client recovery mechanism. Plus the port-throw seam: S2's ViewCell maps observation-port
throws (`read-after-release`, typed `no-such-sub`) to "the view error boundary" — this
bead makes that surface real; fixture proving a port throw lands at the nearest
boundary. (2) PIN the `:on-error` dispatch payload shape — flagged UNSPECIFIED by the
guide correctness pass (guide 02/10 dispatch `[:ui/render-failed]` / `[:ui/tile-crashed]`
with no stated payload). Pin in this PR + the owning spec section: recommended shape —
the declared vector is dispatched with ONE appended serialisable error-report map
(:view-id, :render-key, boundary identity, and a serialisable-safe error summary —
message/type, never the raw JS error object; raw-object needs stay on the host
`create-root` error callbacks). Exact key roster pinned at landing; if the pin forks
02 §6 semantics it returns to Mike as a delta per the blessed-table protocol.
(3) `ui/client-only` S3 half: browser-only subtree with the MANDATORY capability-free
`:fallback` (compiler-checked per 06 §3 — add the analyzer check + roster id if S1 did
not ship one; the S1e roster has no client-only id); client-gate fixture — client
renders the client template, JVM/first-hydration renders the fallback; the single-root
SSR phase flip is OUT OF SCOPE (completes S5 per 12 §2b). (4) The foreign callback tier's
committed behaviour (the 02 §3 decision table — grammar shipped S1; behaviour lands
here): `ui/event` (sees committed slots + the live event; `nil` return ⇒ no dispatch;
form/file payload cases), `ui/handler` (imperative, per-site stable), `ui/render-fn`
(runs during render; purity — no dispatch/sub/lease/hooks; S3a's `render-phase-dispatch`
warning fires on violation), `ui/raw-fn` (identity-as-protocol pass-through + the
callback-ref form: React invokes callback refs during commit BEFORE layout publication,
so no committed-slot promise — the explicit form marks that), and the DOM bare-fn
shorthand (per-site stable, committed closure). Foreign-boundary REJECTIONS shipped at
S1e (`bare-fn-prop`, `bare-fn-ref`, `loop-capturing-handler`) — behaviour fixtures here,
no new rejections. (5) Foreign-component embedding fixtures from the 07 §3 interop set:
foreign heads with raw JS values passing through, render props, object-vs-callback refs,
error boundaries incl. reset-key + on-error timing. Acceptance: 02 §6 phase-semantics
fixtures (on-error after-commit timing; no recursive dispatch; reset-key retry);
port-throw-to-boundary fixture; client-gate fixture on both hosts; per-row
decision-table fixtures for all four committed forms; JVM tier via ui.test. No hot-zone
files expected (ui artefact + tests; the payload-pin spec edit touches the 004-rewrite
section — not on the fixed hot-zone list, but coordinate one-owner-at-a-time per the
program rule). Worker rules: worktree guard; foreground gates; commit-then-rebase,
never stash.

**OPEN-ITEMS (resolve before filing):** ⟨fill-at-boundary⟩ whether S1 shipped any
client-only compile arm/macro recognition (grep the analyzer at boundary; the S1e
roster carries no client-only id — likely the whole macro lands here) ·
⟨fill-at-boundary⟩ the `:on-error` payload-pin venue: confirm the 004 rewrite's
error-boundary section is on main (S1 atomic-merge outcome) and cite its exact heading;
confirm no later pass already pinned the shape · ⟨fill-at-boundary⟩ captured-frame
mechanics against .9's landed frame wiring.

---

## S3d (proposed rf2-vxgfnd.17)

**Title:** `ui S3d: the .react/* interop tier — seven wrappers per the frozen contract (inherits rf2-kvtn97)`
**Type:** feature · P2 · **Depends on:** rf2-vxgfnd.8, rf2-vxgfnd.11 · parallel-safe
with S3b/S3c on code surface; conditional 009 dwell sequences behind S3b's

**PRE-FILING GATE (the demand-bar escape — resolve BEFORE this bead cuts):** the interop
contract §7 records that NO guide consumer exists (the blessed-table citation was
corrected 2026-07-12, codex2 F5) and the audit obligation rides forward: confirm a
concrete consumer (migration bridge or foreign-widget embedding in the suite), or the
`.react/*` row RETURNS TO MIKE as a row-level delta per the 12 §4 blessing protocol
before the tier's beads dispatch. ⟨fill-at-boundary⟩

**DESCRIPTION (paste):**

S3 interop tier (epic rf2-vxgfnd; blocked by rf2-vxgfnd.8 + .11 — the Fast-Refresh
generation pin needs the landed HMR machinery; INHERITS rf2-kvtn97, whose notes carry a
shepherd RULING). NORMATIVE CONTRACT:
ai/findings/new-substrate-synthesis/drafts/ui-react-interop-contract.md — per rf2-kvtn97
this draft IS the normative contract for the tier; confirm-or-correct each of its eight
[S3-CONFIRM] roster items in the PR body; a genuine semantics fork returns as a Mike
delta per the blessed-table protocol. The draft is tracked: read it in your own worktree
at the revision you are implementing, and do not replace it with a copy from another
checkout. Deliver in implementation/ui, namespace
`re-frame.ui.react`: (1) The seven wrappers per contract §2: `use-ref`, `use-effect`,
`use-layout-effect`, `use-effect-event`, `use-context`, `use-id` (host hooks;
fixed-shape lowering with the kernel-held rf=-per-slot deps comparison of §2.0) +
`lazy` (def-level constructor over a foreign loading thunk; `{:fallback tpl}`
capability-free single-site Suspense containment — [S3-CONFIRM] #3/#4). Anything not in
the contract does not exist in this tier. (2) Position law via the EXISTING analyzer
(analyze.cljc `walk-expr` FQN-set + `:in-loop?` `fail!` machinery, extended with
conditional-position and inside-fn flags): the six hooks legal only where they evaluate
unconditionally exactly once per render; `lazy` def-level only + the kernel dev
diagnostic for dynamic cases; didactic rejections with the hoist/extract escapes; roster
ids `:rf.ui.compile/react-hook-in-loop` / `-in-branch` / `-in-fn` (or one id —
[S3-CONFIRM] #2's split, resolved in-PR). NO Spec 009 catalogue rows for these compile
ids — RULED (rf2-kvtn97 notes, shepherd 2026-07-12): compile-time diagnostic ids get no
catalogue rows; S1 precedent (analyzer ids prose-only; only runtime-tier ids get rows).
Do not draft rows; treat them as S1e-roster compile diagnostics. (3) Hook-signature hash
contribution (fingerprint.cljc is ground truth): each of the six contributes its kind
keyword in source order to a new `:react` vector; the input shape-version integer bumps
1→2 (`[2 {:locals [...] :effects [...] :react [...]}]`, prefix `"hs1-"` unchanged);
`lazy` contributes nothing. THE BUMP IS A ONE-TIME GLOBAL REMOUNT WAVE ([S3-CONFIRM]
#1): every existing hook signature changes, so every mounted dev view remounts once on
upgrade and every build-digest triple changes — land the bump and the wrappers in ONE
PR, never split; the dev console says why (03 §10); call the wave out in the PR body and
in chat at merge; no SSR manifests exist pre-S5, so the digest ripple is bounded to
dev/HMR. Honest asymmetry documented: adding your first wrapper is a remount edit, dev
and prod alike. (4) JVM stubs per contract §4: `use-ref` inert; `use-effect`/
`use-layout-effect` metadata-only (existing effect row idiom); `use-effect-event`
returns a fn raising `:rf.error/jvm-host-op` if invoked ([S3-CONFIRM] #6 — id reuse vs
dedicated id); `use-context` returns the JVM-provided test value
(`ui.test/render` option `{:react-context-values {token value}}`, JVM-side token keying)
or fails loud, never silently nil; `use-id` deterministic inert string (prefix +
occurrence-path counter) + the hydrating-root mismatch diagnostic ([S3-CONFIRM] #7);
`lazy` never invokes the thunk — renders `:fallback` else `:nothing`. CONDITIONAL
HOT-ZONE 009 dwell (sequence behind S3b's dwell): if #6 resolves to reuse, the landed
`jvm-host-op` row's emit-surface sentence gains the `re-frame.ui.react` JVM stubs
(clause edit per prep §2.3's note); if a dedicated id is ruled, draft its row then.
(5) HMR/Fast Refresh per contract §5: add/remove/reorder any hook ⇒ clean remount with
full effect cleanup; same-signature edits preserve state; THE IMPLEMENTATION PIN — the
view generation participates in the kernel deps comparison so Fast Refresh's
cleanup+setup re-run is never suppressed by rf=-equal deps; `use-effect-event`
latest-body swap; `lazy` module reload mints a new component identity (foreign-boundary
cost, stated). Hook-signature + HMR-contribution fixtures (the 12 §2b proof column).
(6) Capability bits ([S3-CONFIRM] #8): effects/refs/context/foreign-components folds
per contract §4; `use-id` recommended exempt (static-root-safe); mapped against the
05 §1 sixteen-bit vocabulary. (7) Manifest recording: every wrapper site (all seven
names) recorded with kind + source coordinates + template path (I-8 identity), consumed
by Xray/Story pre-mount. (8) CLOSE rf2-kvtn97 on merge — its close condition is this
bead absorbing the contract. Acceptance: per-wrapper call-shape fixtures on both hosts;
position-law rejection fixtures (didactic, with escapes); the remount-wave fixture
(adding a wrapper changes the signature and remounts); the Fast-Refresh generation
fixture; the eight [S3-CONFIRM] dispositions enumerated in the PR body. Worker rules:
worktree guard; foreground gates; commit-then-rebase, never stash; the conditional 009
edit only after S3b's dwell merges.

**OPEN-ITEMS (resolve before filing):** ⟨fill-at-boundary⟩ the pre-filing demand-bar
gate above (consumer confirmed, or Mike row-delta — do not file/dispatch past it) ·
⟨fill-at-boundary⟩ .11's landed generation/HMR API names for the §5 pin ·
⟨fill-at-boundary⟩ whether S3b's dwell already took the `jvm-host-op` local-setter
live-tense clause (this bead then adds only the react-stub surface sentence).

---

## S3e (proposed rf2-vxgfnd.18)

**Title:** `ui S3e: evidence schema + ui.tool/* + Xray consumption (W7a) — debugging as first consumer`
**Type:** feature · P2 · **Depends on:** rf2-vxgfnd.14 (S3a provenance), rf2-vxgfnd.8 ·
Causa/Story-priority surface

**DESCRIPTION (paste):**

S3 debugging-as-consumer (epic rf2-vxgfnd; blocked by S3a — dispatch provenance — and
rf2-vxgfnd.8 — instance records ride ViewCell commits). NORMATIVE SOURCES:
ai/findings/new-substrate-synthesis/drafts/xray-ui-consumption-ia.md (THE concrete IA
this bead implements — taste decisions are made there, not in the branch),
04-debugging.md §1/§2/§5/§7/§8, 03 §4 (lifecycle facts + qualified retroactive
annotations) — all tracked; read the IA page + 04 in your own worktree at the revision
you are implementing, and do not replace them with a copy from another checkout.
Shipped-Xray ground truth: tools/xray/spec/* at dispatch time.
Deliver, STRICTLY ORDERED (the IA page's checklist row 0 gates everything):
(1) FRAMEWORK-SIDE FIRST — the evidence schema emits in implementation/ui + their
Spec 009 catalogue rows (HOT-ZONE dwell, sequenced behind S3a/S3b's dwells; the
one-catalogue rule rf2-cs0kd1 — every trace shape Xray consumes exists as a catalogue
row first; these trace/instrumentation shapes were NOT pre-drafted in the 009 prep
batch — draft at landing): committed instance records published at CONNECTED COMMIT
only (speculative renders publish nothing): `:view-id`, `:render-key`,
`:parent-render-key`, `:generation`, occurrence-path evidence, `:observations` (all
readers incl. `{:kind :story-override … :owned? false}`); `:rf.view/causes` as a VECTOR
with the per-kind grammars (`:prop` = changed top-level slot names only — the bounded
cheap promise; `:epoch-restore` carrying operation token + target epoch, old epochs
never rewritten; `:foreign-or-react` as the honest fallback); lifecycle facts +
qualified retroactive annotations (S2b emitted the three-state facts — verify, extend
only the remainder); buffer loss counters `total`/`retained`/`dropped` (04 §2); verify
S3a's dwell carried the dispatch-provenance tag rows, else add them here. (2) The
`re-frame.ui.tool` namespace (tool tier, dev-only, in-artefact per 12 §1): the five
read-only projections `view-manifest`, `mounted-views`, `explain-render`,
`view-dependencies`, `view-event-sites` (04 §5) — goog.DEBUG-gated/elision-safe; this
is the tool-tier-absent-from-production surface G-7/G-11 prove at S3f. (3) Xray
enrichment per the IA checklist rows 1–13 (tools/xray + tools/xray/spec): rows 1–2
(Epoch DISPATCH SITE view-site provenance + L2 hover tooltip + Trace-row second line —
parallel-safe pair); rows 3–8 (Views tab: causes-vector row grammar + prop-diff drill,
instance-record rows, the three-layer lifecycle tag grammar in UNMOUNTED VIEWS —
runtime state / tool label / qualified inference, never fabricated precision;
`:observations` → full sub→view edges + `×N (shared)` + honest override edges;
loss-accounting chrome `(N of M · K dropped)` + L3 badge `+`; `:epoch-restore` cause
rendering) — ONE worker, sequential, all touch 021 §3; row 9 (Frames-tab `[:view id]`
descriptor chip strip: fingerprint short-hash, capability tokens, site counts, `[code]`
chip) — isolated; row 10 (warning families ride the existing `:op-type` issue predicate
— verify end-to-end, near-zero new code); row 11 (substrate views bypass the legacy
attribution machinery; the machinery stays for legacy adapters, deletes at W13 not S3);
row 12 (014-Registry-Catalogue for any new `:rf.xray/*` ids); row 13 (acceptance).
(4) Binding laws from the IA page: the GLOBAL EMPTY-STATE LAW — every enrichment is
evidence-keyed; a legacy-only app renders byte-identical panels (acceptance fixture);
NO NEW TABS (ruled — enrichment only; the Static-mode Views sub-tab question is teed
for the post-S3 IA review, not built); no reveal affordances for classified values
(occurrence keys/props route through the classification policy + 018 §12 sentinels);
L2 row anatomy locked (tooltip-only additions). (5) STANDING RULE: every Xray PR
updates tools/xray/spec/* in the SAME PR — the per-row spec files are named in the
checklist; a deliberately untouched listed file states "spec unchanged because X".
Acceptance: the row-13 roster — empty-state byte-identity fixture; two instances of one
view distinguishable by render-key alone; occurrence-paths on keyed rows through the
classification policy; override rows honest (`:owned? false`); restore causes name op +
target epoch; loss accounting present and itself evidence-keyed; no fabricated
lifecycle labels — plus the 04 §8 gates that land on Xray surfaces; tests are CLJS unit
tests, not Playwright (repo ruling). Split note: the mayor may split framework-side
(1–2) from Xray-side (3–5) into two sequential dispatches — do not bundle past the
4-bead timeout shape. Worker rules: worktree guard; foreground gates;
commit-then-rebase, never stash; 009 dwell one-owner-at-a-time.

**OPEN-ITEMS (resolve before filing):** ⟨fill-at-boundary⟩ inventory which evidence
emits S2 actually landed (.8's lifecycle facts + annotations, .10's epoch ops) — row-0
scope shrinks to the remainder · ⟨fill-at-boundary⟩ whether S3a's dwell carried the
provenance trace rows · ⟨fill-at-boundary⟩ re-verify tools/xray/spec/* ground truth
against any Xray PRs merged after 2026-07-12 (the IA page's §0 mapping is dated) ·
⟨fill-at-boundary⟩ instance-record buffer sizing/loss-counter API against .10's landed
ring shape.

---

## S3f (proposed rf2-vxgfnd.19)

**Title:** `ui S3f: S3 gates G-7/G-11/G-8 + guide-fixture activations + RealWorld-resources vertical page`
**Type:** task · P2 · **Depends on:** rf2-vxgfnd.14–.18 (S3a–S3e)

**DESCRIPTION (paste):**

S3 gates + stage proof (epic rf2-vxgfnd; blocked by S3a–S3e — gates wire against
shipped surfaces; every stage wires its gates into CI IN that stage). NORMATIVE
SOURCES: ai/findings/new-substrate-synthesis/07-testing.md §5 (gate table),
12-implementation-plan.md §2b residual gates + §3 S3 line,
drafts/guide-fixture-pipeline.md §3/§6 (bridge-phase activation design), 08 §5 proof-app
ruling — all tracked; read them in your own worktree at the revision you are
implementing, and do not replace them with a copy from another checkout.
Deliver: (1) G-7 dev/prod equivalence wired into CI: per generated shape +
pairwise capabilities + high-risk triples (not powersets); committed
DOM/events/owners/cleanup agree with debug off; StrictMode-dev settles to prod
outcomes. (2) G-11 elision wired: exact absence of debug + absence rosters — including
the tool tier (`re-frame.ui.tool` absent from production builds, the 12 §2b proof for
that row) and the goog.DEBUG-gated manifest emits; ride the existing elision/
bundle-isolation harness patterns (test:elision precedent). (3) G-8 real-browser input
matrix — the named S3 residual gate (12 §2b; S-5's evidence is jsdom-only):
Chromium/WebKit IME composition, caret-on-restore, event ordering, pre-paint timing
under the sync door; CORRECTNESS FIRST, then event→commit within 10% of hand-written
React p95 and one commit per input. This is the sanctioned real-browser exception to
the CLJS-unit-default ruling; if it needs a new testbed, the build-id + :dev-http port
land in top-level implementation/shadow-cljs.edn — HOT-ZONE, sequence that edit.
(4) Guide-fixture S3 activations per the pipeline design (bridge phase — hand-mirrored
fixtures under implementation/ui/test/, manifest-driven, zero new CI wiring): flip
shipped-stages to include `:s3` (the manifest gate's activation forcer goes red until
every `:stage :s3` row activates and every `:until :s3` adaptation is removed);
activate/de-adapt: guide 03 §local + §effect, guide 04 WHOLE chapter (placeholders,
`ui/event`, sync door, decision table, loop rules — didactic-error fences assert
compile-error-with-id), guide 02 §error-boundary + the `.react` tier, guide 06
(runtime-warning ids + `ui.tool` fences), guide 10 §risky-part; every activation
updates its manifest row; fixtures carry guide:begin/end delimiters; run the local
drift script pre-PR (not CI-wired until S6). Per the pipeline's one-chapter-per-bead
rule the mayor MAY split the activations into per-chapter satellites at dispatch — this
bead then owns the gates + shipped-stages flip + manifest closure. (5) The
RealWorld-resources VERTICAL PAGE rider (08 §5 ruling; 12 §3): migrate ONE vertical
page of RealWorld-resources to `re-frame.ui` at S3 to surface ergonomic problems early
— leases/auth/routing only as that page needs them; do NOT distort the app toward
unneeded features; file every ergonomic finding as a bead, never silently patch around.
(6) Stage acceptance: COUNTER + DASHBOARD MUST FEEL COMPLETE, HOT RELOAD INCLUDED
(12 §3) — a stated walkthrough in the PR body (mount, interact through the sync door,
hot-edit with state preservation + a deliberate remount edit, trip an error boundary,
inspect it all in Xray); plus the G-14 guide-fixture wall-clock budget assertion now
the corpus exists at S3 scale. Acceptance: G-7/G-11/G-8 green in CI on this PR;
manifest gate green with shipped-stages `#{:s1 :s2 :s3}`; no `:until :s3` adaptations
survive; the vertical page renders + its tests green; TESTING.md rows added (mirror
#5703's pattern). HOT-ZONE: .github/workflows — sole workflows toucher; workflow edit
as its own final commit (mirror #5703's commit discipline); possibly
implementation/shadow-cljs.edn (G-8 testbed). Worker rules: worktree guard; foreground
gates (never background the gate runs or pipe through tail — stranded-worker failure
mode); commit-then-rebase, never stash.

**OPEN-ITEMS (resolve before filing):** ⟨fill-at-boundary⟩ the shipped-stages anchor
(pipeline [S3-CONFIRM]: test-tree set vs artefact-level var — check whether .12 named
it when wiring S2 gates) · ⟨fill-at-boundary⟩ whether the guide-fixture manifest + gate
landed during the S1 remainder/S2 as the pipeline recommended, or must land here first
· ⟨fill-at-boundary⟩ the G-14 guide-fixture budget number, measured against the
S2-scale corpus (pipeline suggests ≤10 s at S2 scale, revisit) · ⟨fill-at-boundary⟩
DOM-mount fences: browser tier vs compile-only during the bridge (adapter-smoke overlap
argues compile-only) · ⟨fill-at-boundary⟩ WHICH RealWorld-resources page — Mike/mayor
names it at the boundary · ⟨fill-at-boundary⟩ G-8 harness shape: new testbed (hot-zone
shadow edit) vs extending an existing framework testbed.

---

## S3g (proposed rf2-vxgfnd.20)

**Title:** `ui S3g: W7a Story + Pair + mcp-conformance consumption — the other two debugging consumers`
**Type:** feature · P2 · **Depends on:** rf2-vxgfnd.18 (S3e — ui.tool + evidence
schema), rf2-vxgfnd.11 (HMR machinery — Pair hot-swap IS that path), rf2-vxgfnd.8
(the S2-landed static override lease) · parallel-safe with S3f (tools/ surface) ·
Causa/Story-priority surface

*Coverage rationale (completeness pass, 2026-07-12): doc 11's W7a row is Stage 3 and
names three consumers — Xray (S3e), Story, and Pair-MCP + mcp-conformance. The original
.13–.18 set covered only the Xray half; this brief is the other two. Scope fence: W7b
(Story's shell/canvas/variant-mounting migration to new roots) is S5–6, NOT here;
`flush-presence!` is S4 (rides the §S4 filing notes below).*

**DESCRIPTION (paste):**

S3 Story + Pair consumption (epic rf2-vxgfnd; blocked by S3e — the ui.tool projections
and evidence schema must exist — and rf2-vxgfnd.11 + .8). NORMATIVE SOURCES:
ai/findings/new-substrate-synthesis/04-debugging.md §5 (the Story and Pair bullets are
the contract), 03-reactivity-and-ownership.md §3 (override-lease honesty) + §10
(REPL=HMR one path), 07-testing.md §2 (the JVM override door),
drafts/ui-test-selector-grammar.md (the assertion vocabulary) — all tracked; read them
in your own worktree at the revision you are implementing, and do not replace them with
a copy from another checkout. Shipped ground truth at
dispatch: tools/story/, tools/re-frame2-pair-mcp/, tools/mcp-conformance/. Deliver:
(1) STORY sub-override rung for substrate views: Story's `:sub-overrides` resolves
through the observation-target protocol's static override lease (landed S2b — Story
overrides resolve at render, commit acquires the resolved override target), with
`:owned? false` honesty end-to-end: override records visible to tools, sub assertions
bypass overrides (07 §3), and the S3e Xray override edges render these very records.
The JVM rung stays the EXPLICIT `ui.test/render {:sub-overrides {query value}}` option
— one mechanism per host, both named, never pretended to be the same (07 §2; 04 §5).
(2) STORY variant vocabulary over ui.test trees: variants mount scenes by VIEW ID and
assert on JVM structural trees + app-db via the selector grammar
(`find`/`find-all`/`text`/`attrs` — the ui-test-selector-grammar draft), CLJS-unit-test
shape per repo ruling — this is W7a's "ui.test-tree variant vocabulary"; Story's
assertion/result core is substrate-independent and survives unchanged (11 W7b note).
(3) PAIR hot-swap: hot-swap = the HMR path (03 §10) over nREPL — REPL re-eval and
file-save reload are ONE path (.11's REPL=HMR guarantee), so the framework side is
already built; this bead wires the Pair-MCP surface to it and documents the
remount-vs-preserve behaviour (hook-signature semantics) in the Pair skill references.
(4) PAIR read-only projections: expose the five `re-frame.ui.tool` fns
(`view-manifest`, `mounted-views`, `explain-render`, `view-dependencies`,
`view-event-sites`) through Pair-MCP tools — tool tier, dev-only, never the authoring
namespace (04 §5); these are also W7a's "manifest reverse-indexes" data surface.
(5) mcp-conformance descriptor updates for every new/changed Pair-MCP tool (11 W7a),
and the skill↔MCP drift gates stay green (skills/re-frame2-pair references updated in
the same PR where tool surfaces change). Acceptance: a Story variant of a substrate
view green with an override (record shows `:owned? false`; assertion path bypasses it);
a Pair session hot-swaps a `defview` (same-signature edit preserves state; signature
edit remounts, console says why); each projection callable over the Pair-MCP transport
with a substrate view mounted; mcp-conformance green; CLJS unit tests, not Playwright
(repo ruling). No hot-zone files expected (tools/ + skills/ surfaces; parallel-safe vs
implementation/). Worker rules: worktree guard; foreground gates; commit-then-rebase,
never stash.

**OPEN-ITEMS (resolve before filing):** ⟨fill-at-boundary⟩ verify .8/.12 landed the
static-override-lease consumption + the Tier-3 override fixture (do not duplicate) ·
⟨fill-at-boundary⟩ whether Pair hot-swap needs ANY framework seam beyond .11's
REPL=HMR path (expected: none) · ⟨fill-at-boundary⟩ the mcp-conformance descriptor
scope against the then-current Pair-MCP tool roster · ⟨fill-at-boundary⟩ whether the
mayor folds this into S3e's split at dispatch (keep the Story/Pair halves sequential
with S3e's Xray half if folded — same 4-bead-timeout caution).

---

## S4 filing notes (for the S3-boundary filing — NOT S3 beads)

*Added by the completeness pass 2026-07-12: S4 has no dedicated prep artifact, by
design — its two big contracts are already ruled (the 02 §7 presence contract + the
decision-record presence row; the `custom-element` `{:properties #{…}}` closed grammar,
delta #1). These notes capture what the S4 boundary filing must include beyond those
rulings, so the S4 filer starts from a checklist, not a re-derivation.*

1. **Presence + `presence-phase`** per 02 §7 and the ratified presence decision row:
   `:timeout-ms` unit-suffixed; completion on transition/animation end with the timeout
   as MANDATORY safety bound; keyed children required, fail-loud; `presence-phase` is
   the single phase read and returns `:present` outside a boundary (reusable children);
   Xray identity = presence-site + occurrence key. JVM renders `:present` (12 §2b).
   Fixture roster = 07 §3's presence block verbatim: exit retention, re-entry
   interruption, reduced motion, hydration no-fabricated-enter, inert exits, terminal
   exactly-once cleanup, fake-clock completion.
2. **`ui.test/flush-presence!`** (07 §2 row — advance presence transitions without
   wall-clock) + the Story presence rung (the W7a leftover S3g fences off) + guide-09
   `flush-presence!` example activation.
3. **`custom-element` macro** per the ruled closed grammar; conversion-table property
   rows are already contract-owned (jvm-tree-and-conversion-contract Q16 ruling);
   W14 custom-element fixtures.
4. **Head policy + trusted-markup hardening + the `raw` foreign-boundary corpus**
   (`ui/html` itself landed S1 — 08 §2 wins; S4 hardens policy + sanitisation guidance;
   the corpus is W14 work). Include the compat-boundary's named pending fixture:
   embedded legacy subtrees under Activity-hidden / presence-retained regions
   (reagent-compat-boundary §2's authoring restriction stands until that fixture
   lands).
5. **A11y diagnostics** — exactly the 04 §6 high-confidence roster (missing accessible
   names on obvious controls, invalid literal ARIA names/values, click handlers on
   non-interactive literal elements, presence exits left interactive), each
   suppressible WITH A REASON; stable site ids shared with build logs.
6. **The S4 spec/009 dwell — rows NOT pre-drafted.** The 009 prep batch
   (drafts/spec-009-ui-catalogue-rows.md) has no S4 batch because the synthesis names
   no S4 id spellings yet. The S4 filing drafts them with their features per the
   co-edit invariant; expected families: presence keyed-children/timeout fail-loud
   errors, the suppressible a11y warning family (04 §6), any custom-element
   property-mismatch diagnostic, `flush-presence!` misuse (ui.test tier). Compile-tier
   ids (analyzer rejections) get NO rows — the rf2-kvtn97 ruling. Extend the prep
   batch's §2 with the S4 batch at that filing.
7. **Guide-fixture S4 activations** (pipeline §6 table): guide 02 §presence +
   §custom-elements; guide 09 flush-presence! examples; manifest `:until :s4`
   adaptations removed; shipped-stages flips to include `:s4`.
8. **W14 conformance corpus additions begin** (11 W14, Stage 4–5): dual-emitter parity
   fixtures for the S4 surfaces; the existing-fixture view-shape sweep can start here
   and completes at S5.
9. **Gates:** no new named G-gate lands at S4; G-7's pairwise-capability matrix and
   G-11's absence rosters GROW to cover presence/custom-element bits (wire the
   additions in-stage per the 12 §3 standing rule).

---

## Coverage note — confirm-roster ownership (completeness pass, 2026-07-12)

Every stage-tagged CONFIRM item across the prep shelf resolves to a named owner; the
S2-boundary filer need not re-derive this:

- **[S2-CONFIRM]** (spec-006-observation-port-amendment ×4): owned by rf2-vxgfnd.7
  (its DESCRIPTION names "the four [S2-CONFIRM] items"; the HMR-disposal queue item
  also lands in .11 if .7 marks it conservative).
- **[S3-CONFIRM]**: interop-contract roster ×8 → S3d (confirm-or-correct in the PR
  body; the catalogue-row half of item 2 is RULED — rf2-kvtn97 NOTES). 009 prep §2.3
  cells → S3a/S3b/S3d dwells. Fixture-pipeline items → S3f OPEN-ITEMS, except the
  extractor-language item (reassigned to S6, docs-move PR-B — pipeline §7).
- **[S5-CONFIRM]** (spec-011 roster ×6 + the two 009 §2.4 id spellings): owned by the
  S5 epic's Spec-011 bead, filed at the S4 boundary (stated in the 011 draft header).
- **[S6-CONFIRM]**: g10 roster → the S6 G-10 gate-wiring bead (g10 §10); w11 roster →
  the S6 W11 bead (w11 §8), react-peer ruling taken JOINTLY with G-10 (one ruling,
  both tables); reagent-compat-boundary §9 roster (incl. the shared-context-object
  rule and the two 004A/migrator watch items that cite it) → the S6 migration-wave
  bead that lands `ui/->react`; docs-move §6 items → the S6 W2/W3 move dispatch
  (PR-A/B/C); 009 §2.5 compat-camelised-prop spelling → the S6 paste per the batch
  protocol.

Workstreams with NO prep artifact and no S3–S4 exposure — deliberately deferred, for
the record: W6 (other skills — S5–6, trails API stabilisation; budgeted by the
skill↔MCP drift gates), W7b (tools' own UIs — S5–6; the migrator catalogue + boundary
contract are its prep), W8 (template — S6; deiym split gates exist), W12 (repo
meta-docs — S6–7), plus W9's TESTING.md rewrite half (S5–6; its S1 `ui.test` half
shipped, its guide-fixture half is pipeline §6). W4's S3 slice is S3f's vertical page;
its full migration is S6 with the migrator as first consumer.

---

*Drafting notes for the filer:* child numbering .14–.20 reflects the epic state at the
2026-07-12 correctness pass (.13 is taken by the Spec-004 rewrite-merge bead filed the
same day); re-verify at filing and renumber again if the epic gained further children. Priorities mirror the S2 siblings
(P2). The kdoai/backlog posture does not apply here — these dispatch per the program's
budget rules (08 §5 B-lite: never saturate all six slots on the substrate). Previously
ruled beads touching S3 surfaces (rf2-uhk9ko, rf2-6gzobp, frame-chain tail) must be
merged or explicitly sequenced AT DISPATCH TIME per the epic's standing rule — verify
then, not from this document. The §S4 filing notes above are carried forward to the
S3-boundary filing; they are not S3 beads.
