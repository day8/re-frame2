# linearlite — EP-0019 optimistic-mutation + rollback worked example

A small **Linearlite-class issue tracker** — a board of cards you **create**,
**retitle**, and move between **statuses** — built around one stubborn UX
problem: a write to a server takes time, but a user expects the screen to react
*now*. So you show the change immediately, before the server has agreed to it —
and then, when the server comes back, you either keep the change or take it back.

That "show it now, take it back if it fails" dance is the **optimistic update**.
Done by hand it's a notorious bug farm: a `:saving?` flag here, a shadow copy of
the old value squirrelled away there, a race between the reply and the next
click, and an undo path you wrote from memory that doesn't quite match what was
really on screen. This example does none of that by hand. The whole dance is a
first-class runtime primitive ([EP-0019](../../../docs/EP/EP-0019-optimistic-mutation-rollback.md),
[Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations)),
and the author writes only the *forward* change — "make the card look like this."
The runtime owns the rest: applying the change before the request goes out,
remembering exactly what was there before, and putting it back, untouched, if
the write fails.

It's the **write-side flagship** of the dogfood examples — the mirror image of
[`reagent/infinite_feed/`](../infinite_feed/), the read-side EP-0021 load-more
dogfood. There the runtime owned accumulating pages of data you read; **here it
owns applying your optimistic guess, recording the truthful inverse, and the
conflict-aware rollback** — and you write the forward patch and nothing else.

> **Coming from TanStack Query / SWR?** You know this shape: an `onMutate` that
> writes the cache early, a context object holding the snapshot, an `onError`
> that restores it. re-frame2 keeps the idea and removes the bookkeeping — there's
> no context object to remember to return, because the runtime snapshots the
> inverse for you. The one deliberate divergence is on conflict (below): where
> TanStack restores its captured context unconditionally, re-frame2 can decide a
> stale inverse is the *wrong* thing to restore and refetch instead.

## What this demonstrates

The whole point fits in four facts, all in one cohesive app.

- **The write shows immediately — and the view never touches the data.** Each
  [mutation](../../../docs/resources/glossary.md#mutation) declares an
  **`:optimistic`** forward patch: `(fn [params] -> {target patch-fn})`, the
  exact-target twin of a resource's `:patches`. That patch runs *before* the
  request is sent, so the new card appears, or the title changes, or the card jumps
  columns the instant the user acts. Crucially, the
  [view](../../../docs/guide/glossary.md#view) does **not** mutate state to make
  this happen — there is no app-db issue list, no `:saving?` flag, no hand-rolled
  shadow copy. The board is a single managed
  [resource](../../../docs/resources/glossary.md#resource), and every write patches
  *that* one cache entry in place.

- **The inverse is recorded for you, so it's true by construction.** You write
  only the forward patch — "set this title," "move this card." You never describe
  how to undo it. At the moment the patch applies, the runtime snapshots the whole
  *before* value of each touched entry — the exact map that existed, kept by
  structural sharing, not a reconstruction someone tried to derive later. The undo
  is therefore the real thing that was there, which is precisely the bit hand-rolled
  optimistic code tends to get subtly wrong.

- **The reply settles deterministically — no clock-watching.** A success reply
  **commits**: the mutation's **`:populates`** re-seeds the board with the server's
  authoritative value, so a card's temporary id gets replaced by the server's and
  the optimistic marker clears. A failure reply **rolls back**: the recorded
  *before* is restored verbatim and the change visibly reverts. There's no
  wall-clock race deciding which wins, because the verdict keys off recorded facts
  — which write this is, and each entry's revision at apply time — not "whichever
  reply landed last."

- **Rollback is the thing you actually watch — the headline.** A **"Fail the next
  write"** toggle arms the demo backend to answer the next write with a `503`. Tick
  it, then create or retitle or move a card: the optimistic change paints
  instantly, the request fails, and the runtime snaps the board back to exactly its
  pre-click state — the new card **vanishes**, the retitled card **reverts**, the
  moved card **jumps back** — and the card wears a "failed — reverted" badge. No
  manual undo, no app-db bookkeeping, no flag you forgot to reset.

The board is read passively through the resource sub `[:rf.resource/data …]`;
each in-flight write is watched through the mutation view-model
`[:rf.mutation/state {:instance …}]`, whose **`:optimistic?`** flag is true while
a write's optimistic value is on screen but not yet settled — so a card can wear a
"saving…" badge over the value you're hoping sticks.

## Why this shape

A few decisions in the source are worth a sentence, because each one is a place
the framework draws a line you'd otherwise have to police yourself.

**Conflict policy: the read path is the recovery authority.** Each mutation names
`:on-conflict :invalidate` — the default, spelled out here because this is the
example whose job is to *show* it. The subtle case it handles: a write fails and
wants to roll back, but while it was in flight a *competing* write already moved
the same entry. Restoring our recorded inverse now would clobber that newer change
with stale data. So instead the runtime refetches the authoritative value and lets
the read path heal the cache. (This is the deliberate divergence from TanStack/SWR,
which restore captured context unconditionally — fine until two writes overlap.)

**Scope is a fail-closed leak boundary, even on a write.** The board is one public
board, so the resource carries the explicit, auditable `:scope :rf.scope/global`
claim — there's no implicit default to fall back on. A per-team board would carry a
[scope](../../../docs/resources/glossary.md#scope) resolver instead, and the optimistic
apply would inherit the same fail-closed rule a read has: a scope that resolves to
nil drops the target rather than writing it globally. The reason it matters is that
an optimistic apply *writes* the cache, so it needs the same boundary a read does —
one team's optimistic guess must never paint into another team's board.

**Everything else is plain, idiomatic re-frame2.** The board is a single managed
resource; each of the three writes is a named
[`reg-mutation`](../../../docs/resources/glossary.md#mutation); and the demo chrome
— the fail-next-write toggle, the new-issue draft, the id of the card being
inline-edited — are ordinary app-db slices: an
[event](../../../docs/guide/glossary.md#event) writes, a
[subscription](../../../docs/guide/glossary.md#subscription) reads, the view reads
the sub. No raw atoms through views, no frame-ids as view
args, no vestigial timing. The only durable "server state" — the issue board
itself — lives in the runtime resource cache, not app-db, exactly where state you
don't own belongs.

## No backend ships with the example

There's no server here, so the example overrides the
[`:rf.http/managed`](../../../docs/resources/glossary.md#managed-http) effect — the
one transport every read and write lowers onto — with a small canned stub. The stub
holds the canonical board in a closure (it *is* the demo server) and synthesises
the authoritative reply for each read and write, delegating to the
framework-shipped `:rf.http/managed-canned-success` / `-failure`
([Spec 014 §Testing](../../../spec/014-HTTPRequests.md)). It defers each reply by a
small `:after-ms` so the optimistic value is *visibly* painted before the reply
lands — and that delay rides the framework's `:dispatch-later` (tape-visible and
time-travel-safe, **not** a raw `js/setTimeout`), so even the demo's fake latency
plays by the rules. The **fail-next-write** flag is read from app-db at request
time: when armed, the next *write* answers a `503` (driving the rollback arc) and
disarms itself.

## Status

The optimistic-mutation runtime has **landed and graduated** (EP-0019): the
`:optimistic` / `:optimistic-tags` forward grammar, the runtime-recorded snapshot
inverse and per-entry revision, the deterministic commit / rollback / reconcile
settle protocol, the `:on-conflict` rule, the derived `:optimistic?` flag, and the
trace family (`:rf.mutation/optimistic-applied` / `-rolled-back` / `-reconciled`)
are all real and operational.

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
