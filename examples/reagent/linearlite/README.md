# linearlite — EP-0019 optimistic-mutation + rollback worked example

A small issue tracker, in the style of Linearlite. It's a board of cards you
**create**, **retitle**, and move between **statuses**.

It exists to show one hard UX problem. Writing to a server takes time, but the
user expects the screen to react *now*. So you show the change immediately,
before the server has agreed to it. Then, when the server replies, you either
keep the change or take it back.

That "show it now, take it back if it fails" move is the **optimistic update**.
Done by hand, it's a bug farm: a `:saving?` flag, a hidden copy of the old
value, a race between the reply and the next click, and an undo path you wrote
from memory that doesn't quite match the screen. This example does none of that
by hand. The whole move is a built-in runtime feature
([EP-0019](../../../docs/EP/EP-0019-optimistic-mutation-rollback.md),
[Spec 016 §Optimistic mutations](../../../spec/016-Resources.md#optimistic-mutations)).
You write only the *forward* change — "make the card look like this." The
runtime does the rest: it applies the change before the request goes out,
remembers exactly what was there before, and puts it back untouched if the write
fails.

This is the **write-side flagship** of the dogfood examples. It mirrors
[`reagent/infinite_feed/`](../infinite_feed/), the read-side EP-0021 load-more
dogfood. There the runtime owns accumulating pages of data you read. Here it owns
applying your optimistic guess, recording the true inverse, and the
conflict-aware rollback — and you write only the forward patch.

> **Coming from TanStack Query / SWR?** You know this shape: an `onMutate` that
> writes the cache early, a context object holding the snapshot, and an `onError`
> that restores it. re-frame2 keeps the idea but drops the bookkeeping. There's
> no context object to remember to return, because the runtime snapshots the
> inverse for you. The one deliberate difference is on conflict (below). TanStack
> always restores its captured context; re-frame2 can decide a stale inverse is
> the *wrong* thing to restore, and refetch instead.

## What this demonstrates

The whole point fits in four facts, all in one small app.

- **The write shows immediately — and the view never touches the data.** Each
  [mutation](../../../docs/resources/glossary.md#mutation) declares an
  **`:optimistic`** forward patch: `(fn [params] -> {target patch-fn})`. It's the
  exact-target twin of a resource's `:patches`. That patch runs *before* the
  request is sent, so the new card appears, or the title changes, or the card
  jumps columns the instant the user acts. The
  [view](../../../docs/guide/glossary.md#view) does **not** change state to make
  this happen. There is no app-db issue list, no `:saving?` flag, no hand-rolled
  copy. The board is one managed
  [resource](../../../docs/resources/glossary.md#resource), and every write
  patches that one cache entry in place.

- **The inverse is recorded for you, so it's correct by construction.** You write
  only the forward patch — "set this title," "move this card." You never say how
  to undo it. When the patch applies, the runtime snapshots the whole *before*
  value of each touched entry — the exact map that existed, kept cheaply by
  structural sharing, not a guess reconstructed later. So the undo is the real
  thing that was there. That's exactly the bit hand-rolled optimistic code tends
  to get subtly wrong.

- **The reply settles the same way every time — no clock-watching.** A success
  reply **commits**: the mutation's **`:populates`** re-seeds the board with the
  server's authoritative value, so a card's temporary id is replaced by the
  server's and the optimistic marker clears. A failure reply **rolls back**: the
  recorded *before* is restored exactly, and the change visibly reverts. No
  wall-clock race decides which wins. The verdict keys off recorded facts — which
  write this is, and each entry's revision when the patch applied — not "whichever
  reply landed last."

- **Rollback is the thing you actually watch — the headline.** A **"Fail the next
  write"** toggle tells the demo backend to answer the next write with a `503`.
  Tick it, then create or retitle or move a card. The optimistic change paints
  instantly, the request fails, and the runtime snaps the board back to exactly
  its pre-click state. The new card **vanishes**, the retitled card **reverts**,
  the moved card **jumps back** — and the card wears a "failed — reverted" badge.
  No manual undo, no app-db bookkeeping, no flag you forgot to reset.

The board is read passively through the resource sub `[:rf.resource/data …]`.
Each in-flight write is watched through the mutation view-model
`[:rf.mutation/state {:instance …}]`. Its **`:optimistic?`** flag is true while a
write's optimistic value is on screen but not yet settled — so a card can wear a
"saving…" badge over the value you're hoping sticks.

## Why this shape

A few choices in the source are worth a sentence each. Every one is a place the
framework draws a line you'd otherwise have to police yourself.

**Conflict policy: the read path is the recovery authority.** Each mutation names
`:on-conflict :invalidate` — the default, spelled out here because this is the
example whose job is to *show* it. Here's the case it handles. A write fails and
wants to roll back, but while it was in flight a *competing* write already moved
the same entry. Restoring our recorded inverse now would clobber that newer change
with stale data. So instead the runtime refetches the authoritative value and lets
the read path heal the cache. (This is the deliberate difference from TanStack/SWR,
which restore captured context unconditionally — fine until two writes overlap.)

**Scope is a fail-closed leak boundary, even on a write.** The board is one public
board, so the resource carries the explicit, auditable `:scope :rf.scope/global`
claim — there's no implicit default to fall back on. A per-team board would carry a
[scope](../../../docs/resources/glossary.md#scope) resolver instead, and the
optimistic apply would inherit the same fail-closed rule a read has: a scope that
resolves to nil drops the target rather than writing it globally. This matters
because an optimistic apply *writes* the cache, so it needs the same boundary a
read does — one team's optimistic guess must never paint into another team's board.

**Everything else is plain, idiomatic re-frame2.** The board is one managed
resource. Each of the three writes is a named
[`reg-mutation`](../../../docs/resources/glossary.md#mutation). And the demo chrome
— the fail-next-write toggle, the new-issue draft, the id of the card being
inline-edited — are ordinary app-db slices: an
[event](../../../docs/guide/glossary.md#event) writes, a
[subscription](../../../docs/guide/glossary.md#subscription) reads, and the view
reads the sub. No raw atoms through views, no frame-ids as view args, no vestigial
timing. The only durable "server state" — the issue board itself — lives in the
runtime resource cache, not app-db, exactly where state you don't own belongs.

## No backend ships with the example

There's no server here. So the example overrides the
[`:rf.http/managed`](../../../docs/resources/glossary.md#managed-http) effect — the
one transport every read and write lowers onto — with a small canned stub. The
stub holds the canonical board in a closure (it *is* the demo server) and builds
the authoritative reply for each read and write, delegating to the
framework-shipped `:rf.http/managed-canned-success` / `-failure`
([Spec 014 §Testing](../../../spec/014-HTTPRequests.md)). It delays each reply by a
small `:after-ms`, so the optimistic value is *visibly* painted before the reply
lands. That delay rides the framework's `:dispatch-later` (tape-visible and
time-travel-safe, **not** a raw `js/setTimeout`), so even the demo's fake latency
plays by the rules. The **fail-next-write** flag is read from app-db at request
time: when armed, the next *write* answers a `503` (driving the rollback) and then
disarms itself.

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

## How to run

```bash
shadow-cljs watch examples/linearlite
```

Then open [http://localhost:8044](http://localhost:8044) and the board loads.

**Try the rollback:** tick **"Fail the next write"**, then create / retitle /
move an issue. The change paints immediately, the request fails, and the
optimistic change reverts.

## Related examples

- [`examples/reagent/realworld_resources/`](../realworld_resources/) — the **`:optimistic-tags`** (tag-addressed) counterpart: a favorite that flips across the detail, every list, and the session feed at once.
- [`examples/reagent/infinite_feed/`](../infinite_feed/) — the read-side EP-0021 load-more dogfood; this example is its write-side flagship sibling.
- [`examples/reagent/resources/`](../resources/) — the single-page resource lifecycle (ensure / refetch / owners / causes) the board read builds on.
