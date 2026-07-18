# Spec 006 amendment (R-2): the internal observation port — SUPERSEDED (merged, then amended)

> **Status: SUPERSEDED — historical staging material. Do not apply.**
>
> **All three edits below have LANDED in `spec/006-ReactiveSubstrate.md`**, which is the
> current contract: Insertion 1 as §The internal observation port (adapter-internal),
> sitting exactly where this draft placed it — after `### Per-host implementation notes`,
> before `## What happens when a sub references an unknown sub`; Insertion 2 as the
> "**The internal observation port is not part of this contract**" blockquote in
> §The adapter API contract; the companion edit as the "**Observation-target consultation
> (observation-port substrate)**" paragraph in §The sub-override subscribe seam. Applying
> this draft again would duplicate all three.
>
> **The merged §The slice-scoped probe memo has since been amended** (⟨rf2-er64a⟩,
> ⟨rf2-2g7pxq⟩) and no longer reads as it does below. The current law is **per-host**:
>
> - **JVM** — a thread-local render scope (`*slice*`, opened by `with-slice-memo`); the
>   binding discards the table synchronously when the render thunk returns. There is no
>   microtask on the JVM.
> - **CLJS** — a module holder **released at the host microtask checkpoint**
>   (`queueMicrotask`, under a CAS guard). That checkpoint is the holder's **maximum**
>   lifetime, and what it guarantees is exactly this: no holder or table survives *past* it
>   into the next window. **The whole host-microtask window is one CLJS slice** — because
>   `queueMicrotask` is FIFO, a genuinely-later render in a microtask interposed *before*
>   that checkpoint finds the holder still installed and reuses it. That is a bounded
>   within-window economy and the intended design, not a shortfall; one holder can serve
>   several synchronous render passes.
>
> **§The slice-scoped probe memo below states the retired universal wording** — the table
> "scoped to the **current synchronous execution slice**", with no entry surviving into "a
> later slice" — which read as *sharing ends with the originating synchronous call*.
> PR #6070's executable inverse-FIFO proof disproved that for CLJS. It is preserved
> verbatim as design history and is deliberately NOT corrected in place; read it as
> archaeology, never as direction. Its shape source carries the same disproven inference —
> see the correction notice on [spikes/s3-ownership-report.md](../spikes/s3-ownership-report.md).
>
> Current authorities: `spec/006-ReactiveSubstrate.md` §The slice-scoped probe memo,
> [03-reactivity-and-ownership §3](../03-reactivity-and-ownership.md), and the
> `slice-memo*` docstring in `implementation/ui/src/re_frame/ui/reactive.cljc` (⟨rf2-5ea8f⟩).

**Original status:** DRAFT — not merged · 2026-07-12. Target: `spec/006-ReactiveSubstrate.md`.
> Semantics **and shapes final**: spike S-3 has run, and per the binding codex2
> disposition ([09 §codex2 disposition, F1](../09-review-disposition.md)) the spike's
> §5 target/evidence/lease model
> ([spikes/s3-ownership-report.md §5](../spikes/s3-ownership-report.md)) is the **sole
> shape source**. Items the spike did not exercise are marked **[S2-CONFIRM]** inline —
> conservative rules to confirm in Stage 2, not open designs. Sources:
> [03-reactivity-and-ownership §2–§4](../03-reactivity-and-ownership.md),
> [01-goals-and-invariants I-1..I-6](../01-goals-and-invariants.md), S-3 §5,
> [09 §codex2 disposition](../09-review-disposition.md).
>
> **Anchor audit 2026-07-12:** every placement anchor below re-verified to occur
> **exactly once** in checked-in `spec/006-ReactiveSubstrate.md` (heading forms
> `## Subscription cache — contract and operational semantics`,
> `### Per-host implementation notes`,
> `## What happens when a sub references an unknown sub`,
> `### Plain-atom adapter (JVM, SSR, headless)`; the quoted adapter-contract opening
> paragraph and canonical-mechanism blockquote; the quoted seam consult rule and the
> `**Override schema-validation.**` paragraph).

This amendment makes three edits to Spec 006:

1. **Insertion 1** — a new top-level section, *The internal observation port*, carrying
   the port's normative semantics and final shapes (the six frozen invariants, the
   target/evidence/lease model, the six operations, staging/rollback, the reentrancy
   rules, the error split, the slice-scoped probe memo, the epoch final phase, the
   named cross-artifact seam).
2. **Insertion 2** — a short note inside *§The adapter API contract* stating the port
   exists and lives **outside** the closed public ten-fn map.
3. **Companion edit** — a scoping paragraph in *§The sub-override subscribe seam* for
   the observation-target consultation rule (verified needed; old→new pair below).

---

## Insertion 1 — new top-level section

**Placement.** Insert as a new `##` section immediately after the end of
`## Subscription cache — contract and operational semantics` (i.e. after its final
subsection `### Per-host implementation notes`) and before the existing anchor:

> `## What happens when a sub references an unknown sub`

Rationale for the placement: the port is the read-side protocol over exactly the cache
nodes that section defines — `acquire!`/`release!` are the ref-count attach/detach of
[§Reference counting and disposal]; `probe` is the ownership-free read the cache contract
otherwise has no name for.

**Text to insert:**

---

## The internal observation port (adapter-internal)

> **Status: normative.** Semantics frozen per R-2 (2026-07-11); shapes settled by spike
> S-3 (2026-07-11) and ruled binding (2026-07-12). This port is INTERNAL — it is NOT
> part of the public adapter API contract; see the scope statement below.

The compiled UI substrate (`re-frame.ui`) reads subscriptions through a six-operation
**observation port** rather than through the reactive `subscribe`/deref path the current
view layers use. The port exists because concurrent React separates *rendering* (which
may run, restart, or be abandoned) from *committing* (which alone may own resources):
the port splits "read a subscription's value" (render-safe, ownership-free) from "own a
subscription node" (commit-only), so the sub-cache's ref-counting and synchronous
disposal contract ([§Reference counting and disposal](#reference-counting-and-disposal))
is never driven from a speculative render. ⟨03 §3, I-1/I-2⟩

### Scope — outside the closed public adapter contract, one named consumer

The port is **adapter-internal**: a private surface between the core's sub-cache and the
`re-frame.ui` substrate's view runtime (the ViewCell/commit reconciler, specified with
the Spec 004 rewrite). It is **not** an entry in the adapter spec map. The public
adapter API contract remains exactly as [§The adapter API contract](#the-adapter-api-contract)
states it — six required functions, three optional functions, one lifecycle function,
plus the `:kind` discriminator (the 11-key adapter spec map) — **closed for v1**.
Existing adapters (Reagent, reagent-slim, UIx, Helix, plain-atom) implement none of the
port's operations and are unchanged by this section. No feature predicate is added; a consumer cannot branch on
the port's presence because the port is not consumable. ⟨03 §3⟩

**The seam, named.** The port's concrete surface is the namespace
**`re-frame.substrate.observation`** in the core artifact (`day8/re-frame2`), a sibling
of the existing `re-frame.substrate.*` internals. Its **sole consumer** is the
**`day8/re-frame2-ui`** artifact's view runtime. The seam is versioned by two rules
⟨09 codex2 F1; R-6, 08 §5⟩:

1. **Lockstep release train (R-6).** Core and UI artifacts release together; the port
   may change shape between releases without deprecation ceremony because no third
   party may consume it.
2. **Explicit ABI guard.** `re-frame.substrate.observation` exports an integer
   **`port-abi-version`**; `re-frame2-ui` records the version it compiled against and
   asserts it at load, failing loudly on skew with
   `:rf.error/observation-port-version-mismatch` (always-on; catalogue row required on
   promotion). Artifact drift is a boot error, never undefined behaviour.

### Observation targets — stable identity, never evidence

During render, each executed subscription site resolves a first-class **observation
target** via `resolve-target` — the **only** resolution point: ambient frame, explicit
frame pins, and the Story override context all resolve there, and no later phase
re-resolves context. A target is a **stable identity**; it carries **no node handle and
no `:value`/`:version`** for the `:subscription` kind. ⟨S-3 §5⟩

```clojure
{:kind :subscription  :frame-id :app  :query [:cart/total]}
;; stabilized: the prior query object is reused while args are rf=

{:kind :story-override :query [:cart/total] :value 99   ; the pinned value IS
 :override-id <opaque> :version 7}                      ; the resolution
```

- A `:subscription` target names a sub-cache node **by identity** — `(frame, query)` —
  in a named frame. It deliberately does NOT capture the node: under hot reload the
  node resolved at render can be disposed by commit time, so `acquire!` re-resolves the
  canonical node by identity at acquire. A captured handle could pin a disposed node;
  an identity re-resolved at acquire cannot. ⟨S-3 §5, fixture 8⟩
- A `:story-override` target names a pinned value resolved from the Story override
  context ([§The sub-override subscribe seam](#the-sub-override-subscribe-seam-debug-gated)).
  The pinned value rides the target because the value IS the resolution — there is no
  node to re-resolve. Resolution happens **once, at render**; the captured target —
  never a re-resolution — is what commit acquires (load-bearing: commit must not
  consult context again). An override target acquires no derivation lease and reports
  **`:owned? false`** honestly; override changes are a typed render cause; sub
  output-schema validation still applies to override values.

The `site-ctx` carrier — how a compiled site presents ambient frame, pins, and the
override context to `resolve-target` — is host-internal and not part of the port ABI.
The ABI is the target/evidence/lease value shapes plus the six operations' semantics.

### Probe evidence

`probe` is a pure, ownership-free read of a resolved target. It returns **evidence** —
what this render observed — never a handle:

```clojure
(probe target ?slice-memo)
;; => {:value <v>
;;     :node-version 42 | nil     ; nil = probed cold (no live node) — first-class
;;     :node-key k | nil
;;     :live? true|false
;;     :frame-epoch 17
;;     :registry-epoch 3}
```

Probe may read a live cached node; otherwise it computes pure against the current frame
snapshot through the slice memo (below), creating no cache entry, no watch, and no
disposal obligation. Cold probes (`:node-version nil`) are first-class: the commit
evidence comparison falls back to `rf=` on value for them. ⟨S-3 §5, fixture 2⟩

### The six frozen invariants

These are normative (R-2). Each names the bug class it deletes.

1. **Render resolves and probes without ownership.** A render pass may resolve targets
   and probe their values; it MUST NOT increment a ref-count, register a watch or
   callback, or materialise a cache node that outlives the pass. *(Deletes:
   abandoned-render leaks; StrictMode double-render breakage; speculative publication —
   per I-1.)*
2. **Commit acquires the exact captured target.** The layout commit acquires the targets
   recorded in the committed capture — the captured *identity*, with no re-resolution of
   context (overrides, pins, ambient frame). The canonical *node* is re-resolved by
   `(frame, query)` at acquire; node identity lives only in evidence. *(Deletes:
   render→commit context tears — two lookups that could disagree; pinned disposed nodes
   under HMR; per I-2.)*
3. **Acquire before release.** On retarget or dependency change, commit acquires every
   newly-observed or retargeted target **before** releasing anything, so a shared node
   can never fall through its zero-owner disposal edge ([§Reference counting and
   disposal](#reference-counting-and-disposal)) mid-reconciliation. *(Deletes:
   zero-owner disposal churn — dispose-then-rebuild of a node both old and new sets
   use.)*
4. **Release is synchronous and idempotent.** Releasing a lease detaches ownership
   in-tick (the 1 → 0 edge disposes synchronously, per the cache contract), and a second
   release of the same lease is a no-op. *(Deletes: deferred-release windows; cleanup
   paths that double-release under error recovery.)*
5. **Moved evidence corrects before paint.** At commit, each acquired node's version
   (and the frame/registry epochs) is compared against the render's probe evidence; any
   movement in the render→commit gap advances the cell's revision and notifies, and the
   host corrects **before paint**. *(Deletes: painting a frame computed from stale
   reads.)*
6. **One notification per dirty cell per render batch — the boundary is the host
   checkpoint, not drain finalization and not epoch close.** Source-side notification is
   constant work — mark the cell stale with target/version/epoch/cause evidence, never
   execute a prop-dependent query (per I-5). Every queued write-side event executes and
   commits its own epoch record, and each mark joins the pending read/render window. That
   window is armed by the first dirty mark and closed at the next CLJS host microtask
   checkpoint or an explicit headless/test flush; the scheduler takes no hook from router
   drain finalization and observes no drain boundary. Each dirty cell flushes **exactly
   once** per window, so a run-to-completion drain never splits across batches, drains
   reaching the same checkpoint may share one, and only a real host yield renders them
   separately. Coalescing keys on pending cell state, never the epoch tag and never time.
   *(Deletes: zombie children; N-notifications-per-event fan-out; epoch-count inference
   about render or commit count.)*

### The port operations (final)

⟨S-3 §5 — the sole shape source; 09 codex2 F1⟩

```clojure
(resolve-target site-ctx)     ; render: the ONLY resolution point → target
(probe target ?slice-memo)    ; render: pure evidence read (shape above)
(acquire! target on-change)   ; commit-only: re-resolves canonical node, +1 owner → lease
(current? lease target)       ; the commit kept-check, one predicate
(read lease)                  ; => {:value v :version n}; typed error after release
(release! lease)              ; synchronous, idempotent (second call no-ops)
```

Mapping onto the cache contract: `acquire!` is the ref-count attach of
[§Lookup algorithm](#lookup-algorithm) plus callback registration; `release!` is the
subscriber detach of [§Reference counting and disposal](#reference-counting-and-disposal);
`probe` is an ownership-free read with no existing public name (`subscribe-once`
attaches-and-detaches; `probe` never attaches). `resolve-target` and `current?` have no
cache-contract counterpart — they are the capture and kept-check layer a concurrent
host requires.

### Lease semantics

- **The lease IS the owner token.** Leases are opaque host objects with **identity**
  equality — never `=`. Owners are keyed by lease identity with **per-lease unique
  callbacks**, which makes the sibling-callback-clobber bug class structurally
  impossible and makes StrictMode's release/reacquire naturally balanced. ⟨S-3
  fixtures 4, 5⟩
- **`current?`** ≡ not released ∧ node not disposed ∧ same frame ∧ same stabilized
  query. It is the single commit kept-check: an unchanged live lease is **retained
  untouched**; a disposed node (HMR), a frame swap, or a restabilized query fails the
  check and classifies the site as retargeted.
- **Read-after-release** throws typed `:rf.error/read-after-release`, always — it is a
  substrate bug, never an app error. It costs nothing: the commit path checks
  `current?` first and the render path falls back to `probe`, so the throw is
  unreachable in correct generated code. ⟨S-3 µ⟩
- **HMR node replacement.** Sub re-registration disposes the canonical node *then*
  notifies former owners once with cause `:hmr`. Two idempotence extensions carry the
  whole story: `release!` on a lease whose node was disposed out from under it is a
  no-op, and `current?` treats a disposed node as "not current", so the next render
  probes fresh and the next commit acquires the new canonical node. No cell can pin a
  disposed node. ⟨S-3 fixture 8⟩

### The static override lease

`acquire!` on a `:story-override` target returns a **static lease** — one uniform
commit path with honest ownership reporting ⟨S-3 §5; 09 codex2 F1⟩:

- `:owned? false` — tools and instance records show the site as not owning a real
  subscription;
- `read` returns the pinned value and the override's version;
- `release!` is a no-op; **no callback is registered** (a pinned value never
  invalidates);
- `current?` holds while the site's captured override tokens still match under the
  **split equality law** — `:override-id` (slot identity) by plain `=`, `:version` (the
  movement token) by the frozen `rf=` law (core-local `node-value=`), so NaN-to-NaN
  retains — and fails when the override changed or was removed — retargeting through
  the normal staged commit path, exactly like a real node.

*(Shape ruled and final. S-3 itself did not prototype this — no Story context existed in
the spike harness — but its Tier-3 mounted Story-context fixture has since **landed** with
the ViewCell layer: `implementation/ui/test/re_frame/ui/mounted_story_override_image_schema_dom_cljs_test.cljs`
proves the NaN→NaN keep and the map→NaN retarget in a live mount, and the port's own NaN
split-equality assertion lives in
`implementation/core/test/re_frame/observation_port_cljs_test.cljc`.)*

### Transactional multi-acquire — staging and rollback

Commit's dependency reconciliation is transactional ⟨09 codex2 F1 — binding⟩:

1. Every newly-observed or retargeted target is acquired **before anything is
   released** (invariant 3), and the resulting leases are **staged** — provisional,
   not yet installed.
2. **On any acquisition failure**, every newly acquired staged lease is
   **synchronously released** — in reverse acquisition order, so layered acquisitions
   unwind symmetrically (the ordering is observable only in dispose traces)
   **[S2-CONFIRM — order only]** — and **the prior committed set remains installed**:
   the cell keeps its previous committed dependency set and previously published
   values, the reconcile aborts, and the acquisition's typed error propagates.
3. Only after every staged acquisition has succeeded does commit release the prior
   leases of dropped/retargeted sites and install retained + staged leases as the
   committed dependency set.

The first-failure case is safe by ordering alone (nothing has been released); the
k-th-failure case is safe by rollback (staged leases 1..k-1 cannot leak). Nodes shared
with the prior committed set survive rollback trivially — their prior owner is still
attached; nodes created solely by a rolled-back acquisition dispose on their zero-owner
edge, correctly. A multi-target reconcile-failure fixture is a named Stage-2
obligation.

### Callback and reentrancy rules

Spike-validated ⟨S-3 §5, µ⟩:

- `on-change` is **constant-work**: mark-dirty with node-key/version/epoch/cause; it
  never computes (invariant 6, I-5).
- `acquire!`/`release!` called from **inside the owner-notification fan-out** throw
  `:rf.error/reentrant-graph-op` (dev-asserted). The rule is cheap because the fan-out
  is separated from the cell flush: React-driven acquire/release during the read/render
  commits *caused by* the render batch's notification are outside the fan-out
  and always legal.

Conservative, not exercised by S-3 ⟨09 codex2 F1 — write the conservative rule⟩:

- `acquire!` and `release!` themselves **never invoke `on-change` synchronously** — no
  fan-out during acquire/release. Acquire returns state via the lease; movement in the
  render→commit gap is the commit evidence comparison's job (invariant 5), not a
  callback's. **[S2-CONFIRM]**
- **HMR-disposal notifications queue.** The dispose-then-notify-once-with-cause-`:hmr`
  ordering IS S-3-validated; the delivery turn is specified conservatively: the
  notification rides the same constant-work mark-dirty path and is flushed at the
  notification boundary the re-registration closes — coalesced once per cell, never
  delivered mid-registry-mutation. **[S2-CONFIRM — queue alignment only]**

### Error contract — internally fail-loud, publicly recover-to-nil

The port and the public read API split deliberately ⟨09 codex2 F1 — binding⟩:

- **The port is fail-loud.** Every port operation throws typed on failure:
  - `:rf.error/no-such-sub` — the target's own query names an unregistered sub, at
    `probe` or `acquire!`. This is the **same catalogue id** the public surface records
    ([§What happens when a sub references an unknown sub](#what-happens-when-a-sub-references-an-unknown-sub));
    the spike's `:rf.error/no-sub` spelling is **superseded and must not survive
    anywhere** — one condition, one catalogue id, two emit surfaces.
  - `:rf.error/frame-destroyed` — `probe`/`acquire!` against a destroyed frame. Again
    the existing always-on catalogue id; its 009 row gains the port's **throwing** emit
    surface on promotion (public recovery column unchanged).
  - `:rf.error/read-after-release` (always) and `:rf.error/reentrant-graph-op` (dev) —
    new ids; catalogue rows required on promotion.
- **The public API is untouched.** `subscribe` and `subscribe-once` keep their
  checked-in recovery-to-`nil` semantics (`:replaced-with-default`) for unknown subs
  and destroyed frames — nothing in this amendment changes
  [§`subscribe-once`](#subscribe-once-query-v--value--subscribe-once-query-v-frame-f--value)
  or the unknown-sub section.
- **Why the split is safe.** The port's callers are generated commit/render machinery,
  not app code; transactional staging (above) makes every acquire failure
  non-corrupting, and the ViewCell maps port throws to the view error boundary (the
  Spec 004 rewrite's surface). Loud-at-the-seam plus recover-at-the-public-surface
  keeps one catalogue and two honest behaviours.
- **In-graph input resolution is unchanged.** The fail-loud rule governs the port's
  *entry point* (the target's own query). A sub **body's** `:<-` reference to an
  unregistered input keeps the graph's own documented behaviour — one
  `:rf.error/no-such-sub` error event, `nil` substituted, body still runs — identically
  under `probe` (including cold probes) and under public `subscribe`; a sub body that
  *throws* during a probe propagates (fail-loud). **[S2-CONFIRM — cold-probe edge set:
  unknown input mid-graph, sub-body exception, cycle detection]**

### The slice-scoped probe memo

> **⚠ SUPERSEDED — retired wording, preserved as design history.** The "**current
> synchronous execution slice**" lifetime stated below was disproven for CLJS by PR #6070's
> executable inverse-FIFO proof. The current law is per-host and lives in
> `spec/006-ReactiveSubstrate.md` §The slice-scoped probe memo: on the JVM the thread-local
> scope discards synchronously when the render thunk returns; on CLJS **the whole
> host-microtask window is one slice**, so a genuinely-later callback interposed before the
> microtask checkpoint reuses the still-installed holder. See this document's header.

Probes are ownership-free, so N sibling sites probing the same query during one render
pass (first-mount fan-out: N rows probing `[:orders/by-id id]`) would recompute shared
derivation parents N times. The port permits one mitigation: a **slice-scoped pure memo
table** — the optional `?slice-memo` argument to `probe`. Within one slice, probes
share computed derivation parents; the table dies with the slice. No entry survives
into cache state, ownership state, or a later slice.

**Lifetime (S-3-settled).** There is no public React render-pass token; the table is
scoped to the **current synchronous execution slice**: created lazily on first probe,
cleared by `queueMicrotask`, and belt-and-braces tagged with
`(frame, frame-epoch, registry-epoch)` — invalidated on any mismatch. A time-sliced
pass spanning k slices builds k tables, so the economy is **once-per-slice, not
once-per-pass** — bounded, allocation-trivial, and requiring zero React internals; an
interrupted or abandoned slice's table becomes unreachable garbage. ⟨S-3 §5, fixtures
1b/6⟩

**The memo is an economy, never an authority.** A stale memoized value that survives
into a committed capture is harmless because the **two-guard rule** already covers it:
(1) React's own snapshot re-check catches mid-pass movement of *watched* sites; (2) the
commit reconciler's evidence comparison (invariant 5) catches movement of
*newly-observed* sites — commit compares acquired versions against probe evidence and
corrects before paint. No third mechanism exists or is needed. A memo table that
outlives its slice is a conformance bug (a leak fixture pins it).

### Render-batch finalization — the host-checkpoint boundary

On the observation-port substrate, the invalidation algorithm's Phase 3
([§Invalidation algorithm](#invalidation-algorithm) — "notify subscribers") is realised
as constant-work stale-marking (invariant 6), and the commit sequence gains an
**adapter-internal final phase — the render batch**: a
run-to-completion drain may settle several queued events, each committing its own epoch
record after settling derivations (Phases 1–2) and marking dirty cells (Phase 3). Those
marks accumulate in a pending read/render window armed by the first of them and closed at
the next CLJS host microtask checkpoint (or an explicit headless/test flush) — the
scheduler has no hook from router drain finalization and observes no drain boundary. When
the window closes, each dirty ViewCell is flushed **once** into the host scheduler and
React performs one read/render batch. A synchronous drain therefore cannot be split
across batches and its N epochs coalesce into one; several drains finishing before the
same checkpoint may share a batch, and drains separated by a real host yield render
separately. `flush-render!` and the test-only `ui.test/flush!` both settle the
drain **before** React renders; a re-entrant `flushSync`-style forcing into an open drain is a
dev error carrying epoch evidence
(`:rf.error/flush-in-open-epoch`, dev tier — catalogue row required per
[009 §Error event catalogue](009-Instrumentation.md#error-event-catalogue) on
promotion). This phase is adapter-internal: it adds no public contract fn and does not
alter `flush-render!`'s public signature or semantics.

---

*End of Insertion 1.*

---

## Insertion 2 — note inside §The adapter API contract

**Placement.** In `## The adapter API contract`, immediately after the opening paragraph
whose anchor text is:

> "Every adapter implements the surface below. The contract is **closed for v1** — the
> function set is fixed, signatures are fixed, dispose-after-use is fixed; new adapter
> capabilities ship post-v1 additively (a new fn with a feature predicate consumers can
> branch on)."

and before the blockquote beginning "**The adapter contract is the canonical mechanism
for bridging external reactive sources**".

**Text to insert:**

> **The internal observation port is not part of this contract.** The compiled UI
> substrate reads subscriptions through an adapter-internal observation port (per
> [§The internal observation port](#the-internal-observation-port-adapter-internal))
> that lives **outside** this closed ten-fn map: no entry is added to the adapter spec
> map, no signature here changes, and existing adapters implement nothing new. The
> port's sole consumer is the `day8/re-frame2-ui` view runtime, via the core-internal
> `re-frame.substrate.observation` namespace on the lockstep release train. The
> closed-for-v1 statement above is unaffected by the port's existence.

---

## Companion edit — §The sub-override subscribe seam

**Verification.** The edit **is needed.** The seam's current consult rule —

> "**Consult — `:subs/resolve-sub-override`.** Inside the same
> `(when interop/debug-enabled? …)` envelope that gates the observational subscribe-time
> hooks (`:views/record-view-deref!`, the plain-fn warning), `subscribe` consults the
> hook with the query-vector."

— describes a per-`subscribe`-call resolution feeding a constant reaction. On the
observation-port substrate there is no per-deref consult: override resolution happens
**once, at render**, inside `resolve-target`, producing a captured
`{:kind :story-override …}` target, and commit acquires that exact captured target as a
static lease (invariant 2). Two independent lookups (render-time and deref-time) could
tear — one site half-seeing reality — which is precisely the bug class the captured
target deletes. The existing text stays correct *for the current adapters' `subscribe`
path*; it needs an explicit scoping paragraph so the two mechanisms cannot be read as
one.

**Old → new pair.** No existing sentence is deleted. Insert the following paragraph at
the end of the seam section (after the "**Override schema-validation.**" paragraph, before
the `### Plain-atom adapter (JVM, SSR, headless)` heading):

**New text:**

> **Observation-target consultation (observation-port substrate).** On the compiled UI
> substrate the override consult is folded into `resolve-target`
> ([§The internal observation port](#the-internal-observation-port-adapter-internal)):
> the render pass consults the override context **once per site, at render**, and a HIT
> resolves the site's captured target to `{:kind :story-override …}` — the pinned value
> rides the target — instead of a real sub-cache node. Commit acquires that exact
> captured target as a **static lease** (`:owned? false` reported honestly, `read`
> yields the pinned value, `release!` no-ops, no callback) — there is no deref-time
> re-consult and no constant reaction. Everything else in this section is unchanged and
> applies to both mechanisms: the honesty boundary (an override NEVER reaches
> `compute-sub`, so no subscription assertion can be satisfied by one), the override
> schema-validation rule, the production elision envelope, and the bundle-isolation
> split. The constant-reaction realisation above remains the contract for the current
> adapters' `subscribe` path.

---

## Cross-reference updates (mechanical, on merge)

- `## Cross-references` (end of 006): add a row for the R-1 Spec-004 rewrite once merged
  (the ViewCell/commit reconciler is the port's sole consumer).
- [Spec 009 §Error event catalogue] — one-catalogue rule:
  - `:rf.error/no-such-sub` and `:rf.error/frame-destroyed`: **existing rows** gain the
    observation port's **throwing** emit surface (internal fail-loud; the public
    surfaces' `:replaced-with-default` recovery column is unchanged). No new ids;
    `:rf.error/no-sub` does not exist.
  - New rows: `:rf.error/read-after-release` (always-on),
    `:rf.error/reentrant-graph-op` (dev), `:rf.error/flush-in-open-epoch` (dev),
    `:rf.error/observation-port-version-mismatch` (always-on).
- [Conventions.md — packaging]: the `re-frame.substrate.observation` seam rides the
  R-6 lockstep release train with `day8/re-frame2-ui`; the `port-abi-version` guard is
  the seam's drift check.
- [Ownership.md]: the observation port's owning spec is 006 (this section); the
  ViewCell/commit-reconciler consumer contract is owned by the Spec 004 rewrite.
