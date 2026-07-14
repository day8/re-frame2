# 03 — Reactivity and ownership: sites, the ViewCell, frames

**Status:** final · 2026-07-12 (§3/§4 rewritten to the S-3 §5 port ABI and the 3-state
lifecycle per the binding codex2 disposition, 09 §codex2 F1/F6). The push-ownership
model is **committed** (the pull alternative is a falsification benchmark only — G-13;
if it ever wins, this document is rewritten, not toggled).

## 1. One reactive grammar

| Input | Spelling | Reactive | Time-travelled |
|---|---|---|---|
| App/framework state | `(sub [:query …])` | yes | yes (epochs) |
| Props | the props map | via parent | derived |
| Ephemeral component state | `(local init)` | this view only | no |
| Resource liveness | `(lease descriptor)` | n/a (declaration) | n/a |
| The frame | `(frame)` / handlers | n/a | n/a |

No sixth input. `local` is host component-local state, deliberately outside epochs —
nothing more (02 §5).

## 2. `sub` sites and the single joined binding

Every lexical `(sub …)` is a compile-indexed site; all of a view's sites share **one
ViewCell**: one `useSyncExternalStore`, one scalar revision snapshot, one notification
per run-to-completion drain after quiescence (I-3/I-4/I-6). Conditional reads are legal;
loops are rejected (finite sites).
Stabilization (I-8): literal queries are module constants; parametric sites reuse the
prior query object while args are `rf=`; sites return the prior exact value when the new
read is `rf=`.

**Why one revision integer is enough:** React's own
`useSyncExternalStore` snapshot re-check catches mid-pass movement of *watched* sites;
the commit reconciler's evidence comparison catches movement of *newly-observed* sites;
dropped-site changes cause at most one harmless extra render. Two guards, no third
mechanism.

## 3. Observation targets and the probe/acquire protocol

**Shape authority (binding):** spike S-3's §5 target/evidence/lease model
(`spikes/s3-ownership-report.md` §5) is the **sole ABI source** (09 §codex2 F1). 55/55
fixtures on React 19.2; the six frozen invariants untouched. Items S-3 did not exercise
are marked **[S2-CONFIRM]** — conservative rules to confirm in Stage 2, not open designs.

### The target — stable identity, never evidence

During render, each executed site resolves a first-class **observation target** via
`resolve-target` — the **only** resolution point: ambient frame, explicit pins, and the
Story override context all land there, and no later phase re-resolves context. A target
is a stable identity carrying **no node handle and no `:value`/`:version`**:

```clojure
{:kind :subscription  :frame-id :app  :query [:cart/total]}
;; stabilized: the prior query object is reused while args are rf= (I-8)

{:kind :story-override :query [:cart/total] :value 99   ; the pinned value IS
 :override-id <opaque> :version 7}                      ; the resolution
```

Everything the render *observed* — value, node key/version, liveness, epochs — is probe
**evidence**, a separate map:

```clojure
(probe target ?slice-memo)
;; => {:value <v>
;;     :node-version 42 | nil     ; nil = probed cold (no live node) — first-class
;;     :node-key k | nil
;;     :live? true|false
;;     :frame-epoch 17 :registry-epoch 3}
```

The separation is load-bearing: a captured node handle is a liability — under HMR the
node resolved at render can be disposed by commit time (S-3 fixture 8) — so commit
acquires the *identity* and **re-resolves the canonical node by `(frame, query)` at
acquire**. Node identity appears only in evidence, for comparison, never as an acquire
argument. Cold probes (`:node-version nil`) fall back to `rf=` on value at the commit
evidence check (fixture 2 is exactly this case).

Override resolution happens **once, at render**, inside `resolve-target` (the dev/full
skeleton reads the public Story override context with ordinary `useContext`); the
captured target — never a re-resolution — is what commit acquires (load-bearing for
Story overrides: commit must not consult context again). Override changes are a typed
render cause; sub output-schema validation still applies to override values. Two
independent lookups could tear; resolution is single by design. On the JVM there is no
context: headless tests pass overrides explicitly — `ui.test/render …
{:sub-overrides {…}}` — one honest option, not a pretended "same mechanism" (07 §2).

### The port (internal; Spec 006 amendment R-2)

**The six invariants are frozen:** render resolves and probes without ownership · commit
acquires the exact captured target (the *identity*; the canonical *node* is re-resolved
at acquire) · acquire-before-release prevents zero-owner churn · release is synchronous
and idempotent · moved evidence corrects before paint · drain quiescence notifies each
dirty cell once for the post-quiescence render batch.

**The seam, named.** The port is six functions in **`re-frame.substrate.observation`**
(core artifact `day8/re-frame2`); its **sole consumer** is the `day8/re-frame2-ui` view
runtime (the ViewCell/commit reconciler). It lives **outside** Spec 006's closed public
ten-fn adapter map (that is what keeps "existing adapters do not change" true) and is
not consumable by apps or adapters — no feature predicate. Versioning: the R-6 lockstep
release train (08 §5), plus an explicit guard — the namespace exports an integer
`port-abi-version`; `re-frame2-ui` asserts the version it compiled against at load and
fails loudly on skew (`:rf.error/observation-port-version-mismatch`), so artifact drift
is a boot error, never undefined behaviour.

```clojure
(resolve-target site-ctx)     ; render: the ONLY resolution point → target
(probe target ?slice-memo)    ; render: pure evidence read (shape above)
(acquire! target on-change)   ; commit-only: re-resolves canonical node, +1 owner → lease
(current? lease target)       ; the commit kept-check, one predicate
(read lease)                  ; => {:value v :version n}; typed error after release
(release! lease)              ; synchronous, idempotent (second call no-ops)
```

- **The lease IS the owner token** — an opaque host object with **identity** equality
  (never `=`). Owners are keyed by lease identity with per-lease callbacks: the spine's
  sibling-callback clobber is structurally impossible; StrictMode release/reacquire is
  naturally balanced (fixtures 4, 5).
- **`current?`** ≡ not released ∧ node not disposed ∧ same frame ∧ same stabilized
  query. An unchanged live lease is **retained untouched**; a disposed HMR node fails
  the check, so the next render probes fresh and the next commit acquires the new
  canonical node — no cell can pin a disposed node (fixture 8). `release!` on a lease
  whose node was disposed out from under it is a no-op.
- **Static override lease.** `acquire!` on a `:story-override` target returns a
  **static lease**: `:owned? false` reported honestly, `read` yields the pinned
  value/version, `release!` no-ops, no callback is registered; `current?` fails when
  the site's override id/version moved, which retargets through the normal staged path
  — one uniform commit path. *(Shape ruled; unprototyped in S-3 — its Tier-3 fixture is
  a named Stage-2 obligation.)*
- **Internally fail-loud; publicly recover-to-nil.** The port throws typed:
  `:rf.error/no-such-sub` on an unknown sub (the **same** catalogue id the public
  surface records — the spike's `:rf.error/no-sub` spelling is superseded and must not
  survive anywhere), `:rf.error/frame-destroyed` on probe/acquire against a destroyed
  frame (the existing always-on catalogue row gains the port's throwing emit surface on
  promotion), `:rf.error/read-after-release` (a substrate bug, always thrown;
  unreachable in correct generated code — the render path checks `current?` first), and
  `:rf.error/reentrant-graph-op` (dev-asserted). The **public** API is untouched:
  `subscribe`/`subscribe-once` keep `:replaced-with-default` recovery-to-`nil`. One
  condition, one catalogue id, two surfaces; the ViewCell maps port throws to the view
  error boundary (the Spec 004 rewrite's surface), and commit staging (below) keeps
  every failure non-corrupting.

**Callback/reentrancy rules.** `on-change` is constant-work (mark-dirty with
node-key/version/epoch/cause; it never computes — I-5). `acquire!`/`release!` from
inside the owner-notification fan-out throw `:rf.error/reentrant-graph-op`;
React-driven acquire/release during the read/render commits *caused by* the
drain-quiescence notification batch are outside the fan-out and always legal
(S-3-validated). Two conservative rules S-3 did
not exercise: `acquire!`/`release!` themselves never invoke `on-change` synchronously —
no fan-out during acquire/release **[S2-CONFIRM]**; and HMR-disposal notifications
(dispose canonical node → notify former owners once, cause `:hmr` — that ordering IS
S-3-validated) queue to the notification boundary the re-registration closes, coalesced
once per cell, never delivered mid-registry-mutation **[S2-CONFIRM — queue alignment
only]**.

Probes are ownership-free: no ref-count, no watch, no zero-owner cache node.
**First-mount fan-out mitigation:** probes share a **slice-scoped pure memo table** —
the `?slice-memo` argument — so N sibling rows probing `[:orders/by-id id]` compute
shared derivation parents once per slice, not once per row. There is no public React
render-pass token; the table is scoped to the **current synchronous execution slice**:
created lazily on first probe, cleared by `queueMicrotask`, belt-and-braces tagged with
`(frame, frame-epoch, registry-epoch)` and invalidated on any mismatch. A time-sliced
pass spanning k slices builds k tables (fixture 1b: 3 tables across an interrupted
3,000-row transition) — the economy is **once-per-slice, not once-per-pass**; bounded,
allocation-trivial, zero React internals. An interrupted or abandoned slice's table
becomes unreachable garbage, and a *stale* memoized value that survives into a
committed capture is harmless **because the two-guard rule (I-4/§2) already covers
it** — commit compares acquired versions against probe evidence and corrects before
paint. The memo is an economy, never an authority. A named fixture (07 §3) and risk row
(08 §4) pin this.

### The commit algorithm

At layout commit, for the committed capture only:

1. Reject captures whose view generation is stale (HMR); invalidate for a fresh render.
2. Establish/revalidate the cell connection; a dead cell fails loudly.
3. Kept-check every previously-committed site with `(current? lease target)`: unchanged
   live leases are retained untouched; a failed check (disposed node, frame swap,
   restabilized query, moved override) classifies the site as retargeted.
4. **Stage-acquire** every newly-observed or retargeted target **before releasing
   anything** (a shared node can never fall through its zero-owner disposal edge on
   retarget). Staged leases are provisional: **on any acquisition failure, every newly
   acquired staged lease is synchronously released** (reverse acquisition order
   **[S2-CONFIRM — order only]**), **the prior committed set remains installed**, the
   reconcile aborts, and the acquisition's typed error propagates — the first-failure
   case is safe by ordering alone; the k-th-failure case is safe by rollback; partial
   acquisition can never leak or corrupt.
5. Compare each acquired node's version and the frame/registry epochs against the
   render's probe evidence.
6. Publish the committed frame, event-slot values, site values, and (dev) instance
   record — before the user can interact with the new DOM.
7. Release the prior leases of dropped and retargeted sites; install retained + staged
   leases as the committed dependency set.
8. If any evidence moved in the render→commit gap, advance the revision and notify —
   React corrects **before paint**.

Notifications are constant-work (mark stale with target/version/epoch/cause — never
execute a prop-dependent query, I-5). Every queued write-side event executes and commits
its own epoch record inside the run-to-completion drain; only when that drain reaches
quiescence are dirty cells advanced once and React given one read/render batch. The
automatic path is microtask-aligned, and a real host yield separates drains and therefore
separates render batches. On CLJS, `ui.test/flush!` returns a Promise and forces the same
quiescent boundary under React 19 `act`; its thunk arity runs the write inside that
boundary, then alternates dirty-cell drains and React commits until neither side can
expose more work. Calling it from an open drain throws synchronously, before Promise
construction or host work, with frame + epoch evidence. On the JVM it drains the
headless registry synchronously and returns nil.

**The synchrony exception:** controlled-input sites drain synchronously within the DOM
event (02 §3 — the one sanctioned sync door; caret/IME correctness over batching
purity). A handler that mixes `local` `set!` with dispatch in one discrete event yields
one host render pass: the sync drain commits first, the host batches the rest.

## 4. Lifecycle: runtime state, tool label, historical inference

Public React provides **no cleanup-time signal distinguishing Activity-hide from
unmount** (S-3-verified), so the *runtime* implements exactly three observable states —
and behaves identically for hide and unmount at disconnect time, which S-3 validated end
to end (reveal reacquires, version-checks, corrects before paint):

| Runtime state | Meaning | Ownership | Can resume? |
|---|---|---|---|
| `:connected` | committed, active | owns targets + leases | — |
| `:disconnected` | effects cleaned up (hide *or* unmount — indistinguishable at that moment) | **released** | if React reconnects the same cell (Activity reveal): reacquire + correct before paint; if not, the cell is garbage |
| `:dead` | frame/adapter/root destroyed under a retained handle | released | no — reconnection fails loudly |

Three layers, honestly separated (09 §codex2 F6, binding):

- **Runtime state — the emitted fact.** The immediate fact emitted at cleanup is
  **`:disconnected {:reason :unknown}`**. The runtime never claims to know hide vs
  unmount at that moment, because the platform does not say.
- **Current tool label.** While a cell sits in `:disconnected` with reason `:unknown`,
  tools display exactly that — a disconnected cell of unknown cause. No tool may label
  a live `:disconnected` interval as Activity-hidden or unmounted without the proof
  below.
- **Historical inference — qualified retroactive annotation.** Later evidence may
  annotate the **prior interval**, never the present:
  - a **reconnect** proves the *preceding* interval was an Activity hide — the
    annotation upgrades that interval's reason to `:activity-hidden {:proof :reconnect}`
    (at the moment the proof arrives the cell is already `:connected` again);
  - an **explicit host/root teardown** (root unmount, parent teardown, frame destroy)
    proves the interval ended in unmount — `:unmounted {:proof :host-teardown}`;
  - **GC inference**, where a tool retains it, is best-effort and eventual: it carries
    **no exact unmount timestamp**, cannot fire while tooling accidentally retains the
    cell, and is implemented over a **bounded, non-retaining tombstone**
    (weak/finalization); its annotation is explicitly qualified —
    `:unmounted {:proof :gc-inference :qualified true}`.

Local state is retained by React for hidden trees and destroyed on real unmount — a fact
the runtime *learns retroactively* (the reconnect proof above), never observes at
cleanup. Hidden renders may probe but never acquire and receive no invalidations;
`dispatch-fn` fails in every non-connected state (leaked-listener detector).

**Leases under Activity:** hide releases lease owners (hidden UI must not poll); whether
reveal refetches is the **resource layer's freshness policy**, not the lease's — a fresh
cache re-attaches without network (same rule as hydration, 06 §3). Reveal-cost is
therefore a cache-policy decision apps control, not a substrate surprise.

Frame destroy marks that frame's cells dead, detaches leases/owners, clears pending dirty
entries, and schedules a loud error for still-mounted views scoped to it. Adapter
disposal follows the same ordering, every step idempotent.

## 5. Local state

`(local init)` → `[value set!]`; host `useState`; re-renders this view only. Doctrine
(unchanged, rf2-5sjbg lineage): product-meaning state lives in app-db; `local` is for
keystroke-latency ephemera; when field text *is* product state, dispatch placeholders
instead. `set!` during render is a dev error.

## 6. Effects

`(effect [deps…] body)` — passive host effect; **deps compared by `rf=`** (documented
cost: broad values walk; keep deps narrow); cleanup fn honored on dep change, disconnect,
and unmount; StrictMode dev replay is expected and must be idempotent-safe (that's what
cleanup is for). `(effect :connect body)` runs at each connect, cleanup at each
disconnect — there is deliberately no "once"/"mount" name (02 §5). Effects synchronize
with the host world; app state goes through events. `(ui/dispatch-fn)` is the stable
committed-frame dispatcher; it fails loudly in non-connected states.

## 7. Resource leases

`(lease descriptor)` — recorded at render, reconciled by one aggregated passive effect
after commit: new sites `:rf.resource/ensure` with per-site process-unique owners;
dropped/retargeted/disconnected sites `:rf.resource/release-owner`. Conditional
descriptors legal; loops rejected. Reads stay passive (`(sub [:rf/resource …])`).
Routes/events/machines remain the preferred causal owners.

## 8. Frames

**ENSURE is host preflight, never render (I-1).** The compiler extracts
**unconditional `frame-root` plans** from the root form: before React (or the JVM
renderer) is invoked, the host ensures the frames and drains `:initial-events` — exactly
once, unaffected by abandoned renders, StrictMode replay, HMR, or error recovery (a
fixture pins each). The emitted `frame-root` component then only **scopes** the
already-live frame. `frame-root` sites must sit in the **top region of the root form**
(unconditional, compile-extractable); conditional, reactive, or list-generated sites are
compile errors ("create frames in boot/event infrastructure; scope with
`frame-provider`"). `frame-root` keeps ENSURE's *semantics* (create-if-absent, seed once,
never destroy on unmount); only the *timing* lives at host preflight, out of the render
phase.

- **`frame-provider`** (SCOPE) — pure context over a live frame. Did-you-mean errors both
  ways (`frame-root` given `:frame` / `frame-provider` given `:id`), per rf2-nyea0r.
  *Reconciliation:* both the split and the compiled substrate's host-preflight ENSURE
  have **landed** — the checked-in `re-frame.ui` substrate extracts frame plans and runs
  ENSURE at host preflight before React (#5711), while the frozen Reagent (and retiring
  UIx/Helix) adapters ship `frame-root` (ENSURE) and a SCOPE-only `frame-provider`
  (rf2-nyea0r, #5691) with commit-owned two-pass ENSURE for their lifetime. What remains
  under R-7 (08 §5) is promoting this already-live contract into the spec — not moving
  runtime timing.
- **`(frame)`** — the hold (capture-frame per rf2-y6dz8t): the frame ops map for rare
  imperative needs. **Honesty:** a carried ops map *can* be used under a different
  frame's subtree — cross-frame access via carry exists and is not claimed impossible.
  The doctrine (frames are isolated; no cross-frame reads) is held by: no cross-frame
  *spelling*, teaching subtree scoping, and a dev diagnostic when a carried `subscribe`
  runs under a different ambient frame than its origin. Tool-tier needs live in
  `re-frame.ui.tool`.
- **Roots ↔ frames are many-to-many** (I-8): several roots may reference one installed
  frame; one root may scope several frames via nested providers. Root identity and the
  hydration contract live in 06 §2.

## 9. Scheduling summary

Event drain settles derivations → dirty cells advance once → React renders affected memo
boundaries. **A runtime-only commit does not notify app-db layer-1 subscribers; it does
notify consumers of affected runtime projections** (route/machine/resource subs) — the
commit stays atomic across both partitions.

## 10. Hot code reloading (this must be *excellent*, not adequate)

The dev loop is the product's first impression; HMR is a designed contract with fixtures,
not a hope. The pieces, end to end:

- **Stable shells.** `defview` exports a stable component shell keyed by view id; the
  registry holds the current implementation descriptor. Re-evaluating a namespace (shadow
  hot reload *or* REPL redefinition — one path, 02 §8) replaces the descriptor and bumps
  its generation; the shell identity never changes, so React state, refs, and cell
  identity survive.
- **Hook-signature hash decides preserve vs remount.** The compiler hashes the ordered
  user hook sites (`sub`/`lease`/event sites excluded — they reconcile through the
  cell/manifest, not React hook order). Same signature: mounted cells mark stale, next
  render runs the new body, commit reconciles changed sites — **state preserved**.
  Changed signature: the shell deliberately remounts — **never a corrupted hook order**.
  Dev's fixed full hook skeleton (I-15) exists precisely so adding your first `sub` to a
  view is a same-signature edit.
- **Site identity survives edits.** Sites are keyed by source anchor + structural path +
  generation (I-8): adding a sibling above a lease doesn't retarget its owner; when
  identity can't be preserved safely, the compiler reports a remount/release rather than
  guessing. Ownership correctness wins every ambiguity.
- **Frames are untouched by reload.** ENSURE ran at host preflight; re-running the mount
  fn after reload re-executes preflight, which finds the frames live and **does not
  re-seed** — app state survives the edit (the `frame-root` reuse story — preflight
  timing makes it exact). `:initial-events` re-run only on a genuinely new
  frame id.
- **Registration changes propagate.** `reg-sub` replacement follows the existing cache
  invalidation rules; cells re-read the new canonical node on notification — no cell may
  pin a disposed reaction (the spine's documented failure, made a fixture). `reg-event`
  replacement is invisible to views (handlers resolve at dispatch). View re-registration
  is the shell path above; **the Pair's hot-swap is this same mechanism** invoked over
  nREPL (04 §5).
- **Fast Refresh integration.** Shells register with React Refresh under the view
  identity so editor-driven refresh and shadow reload agree; DevTools names survive.
- **What the author experiences:** edit a view → save → the component updates in place
  against live frame state, sub sites re-reconciled, presence/exit states intact, in
  under a watch-loop second (G-14 budgets it). Edit a sub → dependent views repaint with
  causes attributed `:hmr`. Change a view's hooks → that subtree remounts cleanly, and
  the console says why.
- **Gates:** the HMR fixture matrix (same-signature preserve, changed-signature remount,
  sub replacement, stale-cell rejection at commit (step 1), frame non-reseed, site
  retarget-on-anchor-loss) runs in Stage 2 — deliberately early — because the guide sells
  hot reload as the default workflow from page one (08 §2).

## 11. Runtime error taxonomy

Per Spec 009's one-catalogue rule, every id below gets a catalogue row when this
promotes to spec. Always-on unless marked dev. The observation port additionally throws
the canonical `:rf.error/no-such-sub` — an **existing** catalogue id, no new spelling
(`:rf.error/no-sub` does not exist); its row and `:rf.error/frame-destroyed`'s gain the
port's throwing emit surface on promotion.

| Id | When | Tier |
|---|---|---|
| `:rf.error/no-frame-context` | sub/handler/lease with no ambient frame | always |
| `:rf.error/frame-destroyed` | mounted view's frame destroyed under it | always |
| `:rf.error/dispatch-disconnected` | `dispatch-fn` called while not connected | always |
| `:rf.error/read-after-release` | port `read` on a released lease — substrate bug, unreachable in correct generated code | always |
| `:rf.error/reentrant-graph-op` | port `acquire!`/`release!` from inside the owner-notification fan-out | dev |
| `:rf.error/observation-port-version-mismatch` | `re-frame2-ui` loaded against a core exporting a different `port-abi-version` | always |
| `:rf.error/view-not-found` | `ui/view` unknown id (pre-React) **[WAVE-2 — lands with `ui/view`]** | always |
| `:rf.error/root-hydration-mismatch` | root fingerprint/digest disagreement | always |
| `:rf.error/frame-payload-invalid` | payload fails validation at install | always |
| `:rf.error/flush-in-open-epoch` | re-entrant flushSync-style forcing | dev |
| `:rf.warning/unregistered-event-id` | data handler names no handler | dev |
| `:rf.warning/placeholder-in-dynamic-vector` | runtime vector carries `:rf.ui/*` | dev |
| `:rf.warning/cross-frame-carried-op` | carried subscribe under foreign ambient frame | dev |
| `:rf.warning/render-phase-dispatch` / `-set!` | render purity violations | dev |
| `:rf.error/jvm-host-op` | invoking a host-only op in a JVM structural render (a `local` setter, `dispatch-fn`) — the typed error 06 §1 and 07 §2 promise | always (JVM) |
| compile-error roster | 02 §2/§3, 04 §6 | build |
