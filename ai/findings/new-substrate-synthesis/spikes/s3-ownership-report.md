# Spike S-3 report — ViewCell ownership/concurrency (+ S-2 push falsification, S-5 input synchrony riders)

> **⚠ CORRECTION (⟨rf2-cpalh⟩) — one inference in §5 was later disproven. The report is
> otherwise unamended and still stands.**
>
> This is a dated experimental record of a throwaway spike, preserved verbatim: its 55/55
> fixtures stand, and its §5 target/evidence/lease model remains the binding **sole shape
> source** for the observation port (⟨09 codex2 F1⟩). One inference in
> [§5 The pass token](#the-pass-token-memo-table-lifetime--the-mechanism-s-3-was-asked-to-settle)
> is **wrong** and must not be built on:
>
> - **Disproven:** "React's scheduler yields via a macrotask, so the microtask always runs
>   at slice end", and therefore that a `queueMicrotask` clear ends sharing with the
>   originating synchronous call.
> - **Disproven:** "staleness impossible within a slice (single-threaded — no dispatch can
>   interleave)" — a dispatch **can** interleave within the window.
> - **The law, as PR #6070's executable inverse-FIFO proof settled it:** `queueMicrotask`
>   is FIFO, so a callback enqueued *before* the first probe drains *before* the clear. **The
>   whole bounded host-microtask window is one CLJS slice**, and a genuinely-later render
>   interposed before the checkpoint finds the holder still installed and reuses it. One
>   holder can serve several synchronous render passes. That wider lifetime is the
>   **intended design**, not a shortfall; the checkpoint is the holder's **maximum**
>   lifetime, guaranteeing only that no holder or table survives *past* it into the next
>   window.
>
> **The spike's conclusion survives; only its reason was wrong.** The memo is still safe —
> not because nothing can interleave, but because every table hit is re-validated against
> the handle's own exact `(frame, frame-epoch, registry-epoch)` + incarnation tag, so an
> interposed later render at a moved epoch fails the tag check and mints a fresh table
> rather than serving a stale value. The two-guard rule covers the rest. Exact
> incarnation/epoch tag safety and bounded within-window reuse are distinct properties.
>
> Current authorities: `spec/006-ReactiveSubstrate.md` §The slice-scoped probe memo,
> [03-reactivity-and-ownership §3](../03-reactivity-and-ownership.md), and the `slice-memo*`
> docstring in `implementation/ui/src/re_frame/ui/reactive.cljc` (⟨rf2-5ea8f⟩). The
> amendment draft that took this spike as its shape source is superseded for the same reason
> ([drafts/spec-006-observation-port-amendment.md](../drafts/spec-006-observation-port-amendment.md)).

**2026-07-11 23:23:18 AUSEST** · spike branch: **`spike/ui-s3-ownership`** (worktree
`re-frame2-worktrees/spike-ui-s3-ownership`, code under `spikes/ui-s3-ownership/`;
throwaway, no PR to main) · React **19.2.0** (from a pinned fresh install matching
`implementation/package.json`), node v24.13.0, jsdom 26.

## Verdicts

| Phase | Exit criterion (08 §1) | Verdict |
|---|---|---|
| **S-3** concurrency/ownership | 10k abandoned renders retain zero; reconnect provable on public React | **PASS** (55/55 checks) |
| **S-2** push falsification | confirms committed push economics, or reopens 03 | **PASS — push holds decisively** (pull 4.0×–6.5× worse, gap grows with scale) |
| **S-5** input synchrony | caret/IME matrix green under the sync door | **PASS** in jsdom (24/24), real-browser matrix flagged below |

## 1. Setup

- **Harness**: plain `node --expose-gc` scripts against React 19.2 + `react-dom/client`
  under jsdom. No shadow-cljs, no act(): deterministic driving via `flushSync`,
  `setImmediate` turns (React's scheduler yields via setImmediate in node, so
  time-sliced concurrent renders can be started, observed mid-flight, and interrupted
  deterministically), and `FinalizationRegistry` + forced GC for retention proofs.
- **Derivation graph**: a **minimal stand-in** (`src/derivation.js`), *not* the real
  CLJS sub-cache — chosen for zero build latency and because the probe/acquire port
  does not exist in core yet. It reproduces exactly the semantics the port touches:
  canonical node cache keyed by query, refcount ownership with **per-lease unique
  callbacks**, the **synchronous zero-owner disposal edge** (no grace period — so
  acquire-before-release is load-bearing, as 03 §3 demands), per-event epochs, layered
  recompute with a value-equality cut, one notification per cell per epoch at epoch
  close (I-5/I-6), registry epoch + sub re-registration (HMR), and typed errors for
  the R-2 edge questions. The spine's documented bug classes (sibling watch-key
  clobber, pinned disposed reaction, redundant recompute storm, abandoned-mount leak —
  `implementation/core/src/re_frame/substrate/spine.cljs`) are encoded as fixtures.
- **ViewCell** (`src/viewcell.js`): the codex-04 hook skeleton — one `useRef` cell,
  one `useSyncExternalStore` over a scalar revision, per-render capture, the 8-step
  layout-commit reconciler, a lifetime layout effect for connect/disconnect, and the
  render-pass-scoped probe memo table. Render bodies are bracketed by a phase guard
  that throws on any ownership operation during render (0 violations across every run).

## 2. S-3 fixture results (55/55 PASS)

| # | Fixture | Result / numbers |
|---|---|---|
| 1 | **10,000 abandoned first renders** (Suspense-throw; 10k probing siblings + one suspender, incl. React 19 sibling-prerender retries) | 14,237 renders, **0 commits, 0 acquires, 0 owners, 0 derivation nodes created**; after unmount+GC **14,237/14,237 cells finalized** (100%) |
| 1b | **startTransition abort** (3,000-row transition, interrupted mid-flight by flushSync, then abandoned) | interrupted at 223/3000 renders, 0 commits, 0 acquires; **3/3 per-slice probe-memo tables GC-collected** |
| 2 | **Commit-gap**: source advanced between render and commit (newly-observed site — the case only the commit evidence check can catch) | corrected **before paint** (within the same synchronous flush, no macrotask), exactly 1 commit-correction, exactly 1 corrective re-render |
| 3 | **Conditional sites attach/detach only at commit** | sync toggle attaches/detaches exactly at commit (dropped node disposed); mid-transition probes never touch the committed set; interrupt+rebase leaves it intact; completion attaches at commit; phase guard: 0 render-phase ownership ops |
| 4 | **Two siblings share one node** | refcount 1→2→1, callbacks **distinct by construction** (owners keyed by lease identity — clobber impossible), both notified once per epoch, survivor unaffected by sibling unmount. **Retarget**: acquire-before-release kept a shared node alive through site retargeting (0 disposals, node identity preserved); raw-graph contrast confirms release-first *would* churn (dispose+recreate) |
| 5 | **StrictMode double-invoke** | double render + full effect replay settles to **one owner per site**, acquire/release balanced to exactly one live lease, **one notification per cell per epoch**, teardown balanced to zero |
| 6 | **Probe memo**: 100 sibling rows probing a shared parent chain | memo ON: shared parent computed **1×** per pass (99 memo hits); memo OFF: **100×**; commit created each canonical node exactly once (102 nodes). Abandoned pass: 0 owners, 0 nodes, memo tables **2/2 GC-unreachable** |
| 7 | **Activity** (real `React.Activity`, 19.2 stable export) | hide: ownership fully released (0 owners, 0 nodes), cell disconnected, `dispatch-fn` throws `:rf.error/dispatch-disconnected`, **zero invalidations delivered while hidden**; reveal: reacquired + corrected to the value dispatched while hidden, **local `useState` retained** (`a2:7`); unmount total |
| 8 | **HMR node replacement** (`reRegSub` disposes canonical node, notifies cause `:hmr`) | cell re-read the **new** canonical node; no pinned disposed node; release-after-dispose a safe no-op; teardown total |
| µ | **R-2 micro-tests** | read-after-release throws typed; acquire inside notification fan-out rejected (`:rf.error/reentrant-graph-op`); unknown sub + destroyed frame throw typed; frame destroy marks cells dead, `dispatch-fn` fails loudly, unmount after death safe |
| G | **Global** | 0 render-phase ownership violations, 0 late callbacks to released owners, across all fixtures |

Fixture-7 detail worth recording: on React 19.2, reveal **re-rendered the subtree**
(1 re-render, fresh capture), so reconnection correctness came through the normal
commit path (acquire + evidence check), not through a stale-closure correction. The
design's fallback (commit correction from the last hidden render's capture) was never
needed — but it exists and is what makes the answer robust if React ever revives
effects without re-rendering.

## 3. S-2 benchmark — push economics confirmed

500 (and 100) mounted reactive views, one sub delta per epoch, 1000 epochs per run,
100-epoch warmup, 3 interleaved repetitions per mode, `flushSync` per epoch.
Ownership, commit reconciliation, and derivation settle are **identical in both
modes** — the notification protocol is the only variable. Pull = one global epoch
version as every cell's snapshot; all views re-render; `useMemo`/value-stability
prunes reconciliation of unchanged output.

| Mode | Views | Median wall (1000 epochs) | µs/epoch | Component renders | GC runs / GC ms (typical) |
|---|---|---|---|---|---|
| PUSH | 100 | **124.2 ms** | 124 | 1,000 | 1–2 / ~2 ms |
| PULL | 100 | 496.4 ms | 496 | 100,000 | 8–9 / ~47 ms |
| PUSH | 500 | **348.0 ms** | 348 | 1,000 | 4 / ~4 ms |
| PULL | 500 | 2,253.5 ms | 2,254 | 500,000 | 42 / ~230 ms |

- **pull/push = 4.0× at 100 views, 6.5× at 500 views — the gap grows with scale**
  (pull is O(N views) per epoch by construction: 500,000 renders vs 1,000).
- Allocation churn: pull's transient heap and GC activity are an order of magnitude
  worse (~60× GC count at 500 views; ~230 ms of its wall time is GC). Retained heap
  is flat in both (no leak in either protocol).
- Both arms carry the same per-epoch transact/settle baseline (app-db copy + 500
  layer-1 extractor recomputes), so the *protocol-only* delta is even larger than the
  wall ratio suggests. jsdom also underprices real-DOM commit work, which would widen
  the gap further in a browser.

**Exit**: push economics hold; the pull alternative is falsified as a live fork at
exactly the scale G-13 names. No grounds to reopen 03.

## 4. S-5 — the sync door works on public React APIs (24/24)

Mechanism validated: the door is nothing more than **not deferring the drain** —
`dispatch` runs the full transaction inline inside the DOM event listener; the uSES
notification lands inside React's discrete-event processing; React's own end-of-event
synchronous flush re-renders with the already-round-tripped value, so its
controlled-state restore compares equal and **never writes to the DOM**. No
`flushSync`, no private API, no special lane.

| Check | Sync door | Async fallback (the failure the law prevents) |
|---|---|---|
| Mid-string edit (`hello world` → `helloX world`, caret 6) | store+DOM round-trip within the event, **0 controlled write-backs, caret stays at 6** | React restores the old value mid-typing (write-back observed), value corrects one microtask later, **caret jumps to end (12)** |
| Lazy `e.target.value` read in the queued handler | n/a (read is in-event) | **keystroke fully swallowed** — the deferred handler reads the *reverted* DOM value and round-trips the old text; not flicker, total loss |
| Rapid typing (dispatch per keystroke, same task) | `a`+`b` → `ab`; 50 keystrokes lossless; one epoch per keystroke | second keystroke lands on the reverted field → final `b`, **`a` dropped** |
| IME composition (compositionstart/update/end + input events) | **zero DOM writes during the entire composition**; composed text round-trips | a controlled write-back **lands inside the active composition** (kills IME in real browsers) |
| Non-input dispatches (I-6 integrity) | click handler dispatching 2 events → 2 epochs, 2 cell notifications, **one** batched render pass | — |
| Mixed handler (sync dispatch + `local` set! in one event) | **exactly one host render pass**, both updates visible (03 §3's promise) | — |

**Trigger-predicate implications (02 §3 provisional predicate):**
- The provisional predicate (compiler-proven controlled element: literal
  `:value`/`:checked` co-present with the vector-handler site) is *sufficient* — the
  door mechanism needs nothing else, and non-input dispatches keep full batching, so
  over-application costs latency only, never correctness. Keep it.
- The lazy-read finding sharpens the fallback diagnostic: event **vectors with
  `:rf.ui/value` splice at dispatch time inside the event** (eager), so a batched
  vector dispatch degrades to *flicker + caret loss*; only `ui/event` closures that
  read `e.target.value` inside deferred work hit *total character loss*. The dev
  diagnostic for a batched dispatch at a controlled-input site should say both: name
  the sync-door conditions, and warn that reading the input's value asynchronously
  reads post-restore state.
- The door composes with `local`: the sync drain commits first, the host batches the
  rest into the same discrete render pass — one paint, as 03 §3 states.

**jsdom limits (honest):** jsdom implements input `value`/`selectionStart` (and moves
the caret to end on programmatic value writes — verified by baseline probe — so caret
loss is genuinely observable) and delivers real `InputEvent`/`CompositionEvent`
objects through React's root listeners. It does **not** implement a real IME
(candidate windows, composition commit by the OS), real paint timing, or
per-browser event-order differences. Still needing a real browser (Playwright,
Stage 3 / G-8): actual IME composition on Chromium/WebKit (the async arm's
write-during-composition should visibly cancel composition), caret behaviour of the
no-op restore across browsers, pre-paint correction observed against rAF ordering,
and `onChange` composition-polyfill differences.

## 5. R-2 deliverable — proposed final ObservationTarget / Probe / Lease shapes

Grounded in what the prototype actually needed. The six frozen invariants are
untouched; everything below is the shape layer.

### ObservationTarget (resolved once, at render; recorded in the capture)

```clojure
{:kind     :subscription          ; | :story-override
 :frame-id :app
 :query    [:cart/total]}         ; stabilized: prior query object reused when rf=
;; override variant adds:  :override-id <opaque>  :value <v>  :version 7
```

**Change from the 03 §3 sketch: the target carries no `:node`.** The prototype showed
a captured node handle is a liability — under HMR replacement (fixture 8) the node
resolved at render can be disposed by commit time; commit must re-resolve the
canonical node by `(frame, query)` at acquire. Node identity lives in *evidence*
(below) for comparison only, never as an acquire argument.

### Probe — what it *resolves* vs *reads*

`resolve-target` (render): the **only** resolution point — override context, pins,
ambient frame all land here (08 §4 risk row). `probe` (render): pure read of the
resolved target, returning **evidence**, never a handle:

```clojure
(probe target ?pass-memo)
;; => {:value <v>
;;     :node-version 42 | nil     ; nil = probed cold (no live node)
;;     :node-key k | nil
;;     :live? true|false
;;     :frame-epoch 17
;;     :registry-epoch 3}
```

Cold probes (`:node-version nil`) are first-class: commit's evidence comparison falls
back to `rf=` on value for them (fixture 2 is exactly this case). Probe may read a
live cached node; otherwise it computes pure against the current frame snapshot
through the pass memo.

### Acquire / Lease

```clojure
(acquire! target on-change)   ; commit-only => Lease
(current? lease target)       ; the commit kept-check, one predicate
(read lease)                  ; => {:value v :version n}
(release! lease)              ; synchronous, idempotent
```

- **Lease equality**: leases are opaque host objects with **identity equality; the
  lease IS the owner token**. Owners are keyed by lease identity, which makes the
  spine's sibling-callback-clobber bug *structurally impossible* (fixture 4) and
  makes StrictMode's release/reacquire naturally balanced (fixture 5). No `=` on
  leases; the kept-check is `(current? lease target)` ≡ not released ∧ node not
  disposed ∧ same frame ∧ same stabilized query.
- **Callback reentrancy**: `on-change` is constant-work (mark-dirty with
  node-key/version/epoch/cause; never computes — I-5). `acquire!`/`release!` from
  inside the owner-notification fan-out throw `:rf.error/reentrant-graph-op`
  (dev-asserted; validated). The rule is cheap because the fan-out is separated from
  the cell flush: React-driven acquire/release (renders/commits caused by the epoch-
  close notify) are outside the fan-out and always legal.
- **Read-after-release**: typed `:rf.error/read-after-release`, always thrown — it is
  a substrate bug, never app error. It costs nothing: the render path checks
  `current?` first and falls back to probe, so the throw is unreachable in correct
  generated code (validated: never hit outside the micro-test).
- **HMR node replacement**: re-registration disposes the canonical node *then*
  notifies former owners once with cause `:hmr`. Two idempotence extensions carry the
  whole story: `release!` on a lease whose node was disposed out from under it is a
  no-op, and the kept-check treats a disposed node as "not current" so the next
  render probes fresh and the next commit acquires the new canonical node. No cell
  can pin a disposed node (fixture 8, end-to-end).
- **Acquisition failure**: `acquire!` throws typed (`:rf.error/no-sub`,
  `:rf.error/frame-destroyed`). Because commit acquires **before** releasing
  anything, a failed acquire aborts the reconcile with the cell's previous committed
  dependency set fully intact — failure is loud and non-corrupting by ordering alone.
- **Override targets**: `acquire!` on a `:story-override` target returns a **static
  lease** — `:owned? false`, `read` returns the override value, `release!` no-op, no
  callback — one uniform commit path with honest ownership reporting. *(Not
  prototyped — no Story context in the harness; shape follows directly from the
  static-lease concept and needs its Tier-3 fixture in Stage 2.)*

### The pass token (memo-table lifetime — the mechanism S-3 was asked to settle)

> **⚠ CORRECTION — the paragraph below carries the disproven inference.** See the correction
> notice at the top of this report. The recommendation ("adopt slice-scoped") was adopted, but *slice* is
> bounded **per-host**: on CLJS the whole host-microtask window is one slice, so sharing does
> **not** end with the originating synchronous call and a dispatch **can** interleave before
> the clear. Preserved verbatim as the spike's record; read it as archaeology, never as
> direction.

There is no public React render-pass token. The prototype's answer: scope the table
to the **current synchronous execution slice** — created lazily on first probe,
cleared by `queueMicrotask` (React's scheduler yields via a macrotask, so the
microtask always runs at slice end), and belt-and-braces tagged with
`(frame, frame-epoch, registry-epoch)` and invalidated on any mismatch. Properties
validated: shared-parent economy 1× vs 100× per pass (fixture 6); tables from
interrupted/abandoned passes GC-unreachable (fixtures 1b, 6c: 5/5 collected); staleness
impossible within a slice (single-threaded — no dispatch can interleave) and covered
by the two-guard rule across slices. **Narrowing vs the 03 §3 wording**: a time-sliced
pass spanning k slices builds k tables (fixture 1b: 3 tables over an interrupted
3,000-row transition), so the economy is once-per-slice, not once-per-pass. That is
bounded, allocation-trivial, and requires zero React internals — recommend R-2 adopt
"slice-scoped" as the normative lifetime.

### Lifecycle: a spec-relevant finding on 03 §4

On public React there is **no cleanup-time signal distinguishing Activity-hide from
unmount** — both run identical effect cleanup. The port can implement exactly three
observable states (`:connected` / `:disconnected` / `:dead`); `:activity-disconnected`
vs `:unmounted` is only distinguishable *retroactively* (a reconnect happened / the
fiber was collected). Behaviour is identical either way — ownership released,
`dispatch-fn` fails typed, zero invalidations delivered — all validated. Recommend
03 §4 keep its four rows but mark the hide/unmount split as a **tooling-level
retroactive label**, not a port-level fact.

## 6. Risks for Stage 2

1. **Stand-in ≠ real sub-cache.** The port must graft probe's "pure compute against
   the current frame snapshot without caching" onto the real memo/trace/dispose
   machinery — spine history (laziness, `:rf.sub/run` trace timing, dispose
   tombstones) shows this is where the subtlety lives. The spike proves the contract,
   not the graft.
2. **Reveal-path coverage.** On 19.2 Activity reveal always re-rendered, so the
   stale-closure reconnect correction never fired. Keep a Stage-2 fixture that would
   catch React reviving effects *without* a re-render (the commit-correction path
   handles it by construction, but it is currently unexercised by React itself).
3. **03 §4 wording** needs the three-observable-states amendment above before the
   spec promotion, or tools will promise a fact the platform cannot deliver.
4. **Memo economy is per-slice**, not per-pass: first mounts that time-slice lose
   some sharing (bounded, k tables for k slices). If a future profile shows this
   matters, the only fix is a React-internal token — accept per-slice now.
5. **S-5 real-browser matrix** (IME, caret-on-restore, paint timing) remains for the
   G-8 gate; jsdom evidence is strong but not final for composition UX.
6. **S-2 in a real browser** will widen, not narrow, the push margin (real DOM commit
   costs on pull's 500× render volume) — worth one Playwright confirmation run when
   the Stage-2 harness exists, but not blocking.

## 7. Artefacts

- Branch `spike/ui-s3-ownership`, single commit, code under `spikes/ui-s3-ownership/`
  (`src/derivation.js`, `src/viewcell.js`, `src/harness.js`,
  `test/s3-fixtures.js`, `test/s2-bench.js`, `test/s5-input.js`).
- Run: `npm install && npm run s3 && npm run s2 && npm run s5` (all foreground;
  s3 = 55/55, s5 = 24/24, s2 prints the benchmark table).
