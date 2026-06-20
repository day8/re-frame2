# linearlite — EP-0019 optimistic-mutation + rollback worked example

A small **Linearlite-class issue tracker** — a board of issues you **create**,
**retitle**, and move between **statuses** — where every write applies
**optimistically** and **rolls back on failure**. Built on the first-class
**optimistic mutation** primitive ([EP-0019](../../../docs/EP/EP-0019-optimistic-mutation-rollback.md),
[Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations)).

This is the **write-side flagship** dogfood — the counterpart of
[`reagent/infinite_feed/`](../infinite_feed/) (the read-side EP-0021 load-more
dogfood). There the runtime owned the page accumulation; **here the runtime owns
the optimistic apply, the recorded snapshot inverse, the per-entry revision
token, and the conflict-aware rollback** — the author writes only the *forward*
patch.

## What this demonstrates

The four facts the optimistic primitive surfaces, in one cohesive app:

- **The write shows immediately.** Each mutation declares **`:optimistic`** — the
  exact-target twin of `:patches`: `(fn [params] -> {target patch-fn})`. The
  patch runs at **phase 1.5**, *before* the request lowers, so the new card
  appears / the title changes / the card moves the instant the user acts. The
  view **never mutates app-db** — there is **no app-db issue list, no `:saving?`
  flag, no manual optimistic copy**. The board is a single managed `reg-resource`
  entry; every write patches *that* entry in place.

- **The inverse is runtime-recorded.** The author writes only the **forward**
  patch. The runtime snapshots each touched entry's whole `:before` value
  (verbatim, by structural sharing) and its `:revision` at apply time, on the
  mutation instance row's `:patch-summary` `:rollback` slot. The author never
  describes how to undo a change — the inverse is **truthful by construction**
  (the exact entry that existed, never a reconstruction).

- **The reply settles deterministically.** An accepted **`:ok`** reply
  **commits** — the mutation's **`:populates`** overwrites the optimistic guess
  with the server's authoritative board (a temp issue id is replaced by the
  server's, the optimistic marker clears). An accepted **`:error`** reply
  **rolls back** — the recorded `:before` is restored verbatim. There is **no
  wall-clock race**: the verdict keys on the work-id + generation acceptance and
  the per-entry revision, both canonical recorded facts.

- **Rollback is what you see — the headline.** A **"Fail the next write"**
  toggle arms the demo backend to answer the next mutation with a **503**. The
  optimistic change paints immediately, the request fails, and the runtime rolls
  the board back to exactly its pre-click state — the new card **vanishes**, the
  retitled card **reverts**, the moved card **snaps back** — with a "failed —
  reverted" badge. **No manual undo, no app-db bookkeeping.**

The board is read passively through `[:rf.resource/data …]` (the resource sub
family); each in-flight write is watched through `[:rf.mutation/state {:instance
…}]` — including the derived **`:optimistic?`** flag (EP-0019 Rider 1), true
while a live optimistic apply is showing between phase 1.5 and settle, so the
card renders a "saving…" badge over its optimistic value.

**The conflict policy.** Each mutation names `:on-conflict :invalidate` (the
default, named here for the dogfood): on a failure rollback where a competing
write moved the touched entry while ours was in flight, the **read path is the
recovery authority** — the runtime refetches the authoritative value rather than
restoring a now-stale inverse (re-frame2's deliberate divergence from
TanStack/SWR's unconditional context restore).

**Scope is the fail-closed leak boundary.** The board is a single public board,
so it carries the explicit, auditable `:scope :rf.scope/global` claim. A
per-team board would carry a scope resolver instead, and the optimistic apply
would be **fail-closed** (a nil-resolving scope drops the target rather than
writing globally) — the same leak boundary a read has, because an optimistic
apply *writes* the cache.

## Idiomatic re-frame2

The board is a single managed resource; every write is a named `reg-mutation`;
the demo seam (the fail-next-write toggle) and the inline edit draft are
ordinary app-db slices driven by events and read by subs. **No raw atoms through
views, no frame-ids as view args, no vestigial timing.** The only durable
"server state" — the issue board — lives in the runtime resource cache, not
app-db.

## No backend ships with the example

It overrides `:rf.http/managed` (the fx resources + mutations lower every
read/write onto) with a canned stub that holds the canonical board in a closure
and synthesises the authoritative reply for each read/write, delegating to the
framework-shipped `:rf.http/managed-canned-success` / `-failure`
([Spec 014 §Testing](../../../spec/014-HTTPRequests.md)) with `:after-ms` (the
deferred reply rides `:dispatch-later` — tape-visible, time-travel-safe, **not**
raw `js/setTimeout`) so the optimistic value is visibly painted before the reply
lands. The **fail-next-write** flag is read from app-db at request time: when
armed, the next *write* answers a 503 (driving the rollback arc) and disarms.

## Status

The optimistic-mutation runtime has **landed and graduated** (EP-0019): the
`:optimistic` / `:optimistic-tags` forward grammar, the runtime-recorded
snapshot inverse + per-entry revision, the deterministic commit / rollback /
reconcile settle protocol, the `:on-conflict` rule, the derived `:optimistic?`
flag, and the trace family (`:rf.mutation/optimistic-applied` /
`-rolled-back` / `-reconciled`) are all real and operational.

## Files

```
linearlite/
  core.cljs    — the :linearlite/board resource, the three `:optimistic`
                 mutations (create / edit-title / change-status), the canned
                 :rf.http/managed stub + fail-next-write seam, the board route
                 (route entry ensures the board), the passive board view +
                 watched mutation instances, mount.
  index.html   — minimal host page.
```

The example synthesises its server replies **in-app** via the canned
`:rf.http/managed` stub, so there is **no `api/` asset to stage** — the board is
held in the stub's closure, not a static file tree.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/linearlite
```

The watch build emits `main.js` into `out/examples/linearlite/`. With the
top-level `:dev-http` server wired (port **8044**), open
[http://localhost:8044](http://localhost:8044) and the board loads (the source
dir's hand-written [`index.html`](index.html) resolves at `/`, the compiled
`main.js` falls through to `out/examples/linearlite/`). (`npm run test:adapter-smokes`
does not build this example — it compiles and serves only the three adapter
testbeds; see [`examples/reagent/README.md`](../README.md).)

**Try the rollback:** tick **"Fail the next write"**, then create / retitle /
move an issue. The change paints immediately, the request fails, and the
optimistic change reverts.

## Coverage

The example tree is test-free, but this example's wiring is pinned by
a direct headless CLJS fixture, **`re-frame.linearlite-example-cljs-test`**
(`implementation/adapters/reagent/test/re_frame/`, run by `npm run test:cljs`).
It requires this example's production `linearlite.core` and drives the
composition directly: route entry ensures the board under a `[:route …]` owner;
each of the three `:optimistic` mutations applies its forward patch *before* the
reply (the view reads the optimistic value + the `:optimistic?` flag); a
successful reply commits the server board via `:populates`; and a failed reply
rolls the optimistic change back to exactly the pre-write board (the demo
headline). The generic optimistic-mutation runtime contract (the settle
protocol, the conflict rule, the stale/superseded suppression, the
restore-dangle) is pinned in `implementation/resources/test/`. See the
[coverage table](../README.md#coverage-level-per-reagent-example).

## Cross-references

- [`docs/EP/EP-0019-optimistic-mutation-rollback.md`](../../../docs/EP/EP-0019-optimistic-mutation-rollback.md) — the EP (the accepted design + the riders).
- [`spec/016-Resources.md §Optimistic mutations`](../../../spec/016-Resources.md#optimistic-mutations) — the normative spec (forward plan, snapshot inverse, settle protocol, `:on-conflict`).
- [`examples/reagent/realworld_resources/`](../realworld_resources/) — the **`:optimistic-tags`** (tag-addressed) counterpart: a favorite that flips across the detail, every list, and the session feed at once.
- [`examples/reagent/infinite_feed/`](../infinite_feed/) — the read-side EP-0021 load-more dogfood this is the write-side flagship sibling to.
- [`examples/reagent/resources/`](../resources/) — the single-page resource lifecycle (ensure / refetch / owners / causes) the board read builds on.
