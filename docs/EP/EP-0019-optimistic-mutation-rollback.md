# EP-0019: Optimistic Mutation Rollback For Resources

Status: final
Type: standards-track

> This EP defines the optimistic-mutation surface deferred by
> [EP-0016](EP-0016-resource-mutation-completion.md) (issue 9, ruled
> 2026-06-11): a write applies a recorded optimistic patch to the resource
> cache *before* the server confirms, and the runtime deterministically
> **commits**, **rolls back**, or **reconciles** that patch when the reply
> settles, fails, or is superseded. Its normative home is
> [`spec/016-Resources.md`](../../spec/016-Resources.md); this document is the
> rationale record behind that amendment.
>
> **Graduated `proposal → final` 2026-06-17 (Mike, operator graduation; bead
> `rf2-pyahbf`).** The seven open issues are ruled (see [§Open Issues](#open-issues)
> and the [§Recommendation](#recommendation)) and the recommended cut shipped: a
> registration-level **`:optimistic`** forward plan with a runtime-recorded
> snapshot inverse + per-entry revision token, the deterministic
> **commit / rollback / reconcile** settle protocol keyed on the work-id +
> generation acceptance verdict, **`:on-conflict :invalidate`** as the default,
> **`:optimistic-tags`** for the cross-view-consistency demand, and the reserved
> trace/instance slots filled with the three new `:rf.mutation/*` ops. The
> implementation landed and was dogfooded across
> `implementation/resources/{mutation_events,mutation_registry,mutation_runtime,ssr,state}.cljc`
> (revision token, optimistic apply, settle/rollback/reconcile, Xray view,
> realworld driver). A Linearlite-class dogfood driver (`rf2-tideyl`) is a
> post-graduation enhancement, **not** a graduation blocker. `final` asserts the
> **decisions are settled** and the normative home governs — where this EP and the
> spec differ, the spec governs.

## Abstract

Optimistic mutation rollback is the single most visible parity gap between
re-frame2 resources and TanStack Query / RTK Query / SWR. The read path,
mutation path, scoped invalidation, named scope resolvers, mutation reply
continuations (`:reply-to`), and instance-keyed mutation state have all landed
(Spec 016 §Mutations). What is missing is the *before-the-server-confirms*
write: flip the favorite heart, increment the count, move the card to the top
of the list — immediately, on the record, and revert deterministically if the
write fails or is overtaken.

This EP proposes:

- a registration-level **`:optimistic`** plan — the symmetric, *forward* twin
  of the already-landed success-time `:patches` / `:populates` / `:removes`
  plan — applied at execute time (phase 1.5), recorded as **inverse patches**
  per touched entry plus a **per-entry revision token** captured at apply time;
- a deterministic **settle protocol**: an accepted `:ok` reply **commits**
  (the optimistic patch is reconciled against the authoritative success-time
  plan), an accepted `:error`/`:cancelled` reply **rolls back** (the inverse
  patches replay), and a **stale/superseded** reply does neither — its inverse
  patches are discarded and the *current* generation owns the entry;
- **rollback-by-invalidation on conflict** (`:on-conflict :invalidate`, the
  default): when a touched entry's revision token has moved since the
  optimistic apply (a concurrent mutation, a refetch, a populate from another
  write landed in between), the runtime does **not** blindly restore the stale
  inverse — it marks the entry stale and lets the read path re-fetch the
  authoritative value, because the captured inverse is no longer a truthful
  "before";
- **tag-addressed optimistic patching** (`:optimistic-tags`) so one write can
  optimistically touch *every* cached entry carrying a tag (the
  cross-view-consistency demand — flip a favorite and have it flip on the
  detail, the global list, *and* the session feed at once) without the author
  enumerating each scoped key;
- the **trace + Xray surface**: the reserved `:snapshot-id` / `:rollback` /
  `:reconciliation-refetches` slots on the mutation instance row and the
  `:rf.mutation/succeeded` trace (already shipped as nil placeholders) are
  filled, plus new `:rf.mutation/optimistic-applied` /
  `:rf.mutation/rolled-back` / `:rf.mutation/reconciled` trace ops.

It deliberately does **not** add a normalized entity/graph cache, an offline
write queue, or a general cache-transaction language.

## Motivation

### The gap is named, reserved, and consumer-shaped

EP-0016 deferred optimistic rollback with a precise forward scope (issue 9
disposition):

> the follow-on scope **keeps tag-addressed patching** (the
> `patch-article-everywhere` cross-view-consistency demand is the canonical
> motivating case; exact-key-only would ship the machinery while missing the
> demand that justified it) and excludes the normalized graph cache; the
> identity substrate for per-entry revision tracking is settled by EP-0012's
> disposition 5; the recommended driver when the trigger fires is a
> Linearlite-class port (the local-first ecosystem's canonical
> optimistic-workload benchmark).

The implementation already reserves the shape. The mutation instance row
(`re_frame/resources/mutation_runtime.cljc`, `empty-instance`, lines 126–134)
carries `:affected-keys` and `:patch-summary` with the comment "the later
optimistic slice fills the symmetric rollback half without a shape change." The
success handler (`mutation_events.cljc`, lines 1092–1103) already builds the
`:patch-summary` with explicit reserved nil slots:

```clojure
:snapshot-id nil :rollback nil :reconciliation-refetches nil
```

The registration spec already lists `:optimistic` and `:rollback` among the
deferred mutation-only keys (`spec/016-Resources.md` line 663). So this is not a
green-field design: it is filling a shape the landed surface was built to
receive.

### The user-visible gap

*(Pre-graduation motivation — the gap this EP filled. Now shipped: the
mutations tutorial, [`docs/guide/tutorial/04-mutations-and-invalidation.md`](../guide/tutorial/04-mutations-and-invalidation.md),
teaches the optimistic variant as a current feature.)*

Before this EP, the guide was explicit about the limit: `:populates` was a
*forward-only* seed — optimistic rollback was a deferred feature, not a current
one — so authors were told not to reach for populate expecting TanStack-style
optimistic updates that revert on failure, because that shape wasn't available
yet.

At that point a favorite heart flipped only **after** the server confirmed
(`:populates` ran on the success path). That was the single most jarring
difference from a TanStack/SWR app, where the heart flips on click and reverts
on a 500.

### Why a bead is not enough

The unresolved alternatives are real and non-mechanical: how optimistic patches
are recorded (inverse-patch vs full snapshot), how rollback stays deterministic
under concurrency (the hard part — restore-stale-inverse is a *bug*, not a
feature), how the optimistic apply composes with the already-landed
fail-closed scope/invalidation machinery, and what the public author surface
looks like (a forward+inverse pair, a single forward fn with an auto-derived
inverse, or an explicit transaction). Those are EP-grade decisions, and getting
them wrong ships a cache-coherence footgun.

## Goals / Non-Goals

### Goals

- Define the public author surface for an optimistic write (what the consumer
  writes at `reg-mutation` and `:rf.mutation/execute`).
- Define how optimistic patches are **recorded** (inverse + revision token),
  **committed**, **rolled back**, and **reconciled**.
- Make rollback **deterministic** for both failed mutations **and**
  stale/superseded replies.
- Make rollback **conflict-aware**: a touched entry that moved since the apply
  is reconciled by invalidation, never by restoring a stale inverse.
- Compose cleanly with scoped invalidation, named scope resolvers, and
  instance-keyed mutation state — reuse the one invalidation engine, one
  scope-resolution currency, one durable instance row.
- Surface the whole optimistic lifecycle to Xray/trace (fill the reserved
  slots; add the apply/rollback/reconcile ops).
- Keep the success-path semantics (`:patches` / `:populates` / `:removes` /
  `:invalidates`) **unchanged** for non-optimistic writes.

### Non-Goals

- **No normalized entity/graph cache** (Apollo/Relay) — EP-0016 issue 9
  excludes it; resource entries remain the unit of cache identity.
- **No offline write queue / persistence / cross-tab broadcast** — those are
  separate deferred slices (Spec 016 §Deferred slices).
- **No general public cache-transaction / `{:op …}` language** — EP-0016 Rider
  2 already declined this; internal operation records remain private.
- **No automatic inverse derivation from arbitrary patch fns** — the author
  declares the inverse, or uses the runtime's snapshot-of-touched-entries
  default (see Decision 2); the runtime does not diff arbitrary closures.
- **No optimistic `:reply-to` semantics change** — `:reply-to` continues to
  fire only on the accepted terminal reply, after settle. An optimistic apply
  is *not* a reply.

## Relationships

- **[EP-0016](EP-0016-resource-mutation-completion.md) /
  [Spec 016](../../spec/016-Resources.md) (final).** This EP is the follow-on
  EP-0016 issue 9 explicitly defers and scopes. It reuses, unchanged:
  map-form exact targets (Rider 2), the cache-consequence callback signature
  `(params result)` (§Cache-consequence callback signatures), the per-target
  scoped invalidation engine + three-rung cross-scope lattice (Decision 2),
  named scope resolvers (Decision 3), populate-as-authoritative-load (Rider 1),
  and the deterministic phase order (§Phase order) — into which the optimistic
  apply inserts a new phase 1.5 and a settle-time reconcile in phase 4.
- **[EP-0012](EP-0012-path-optics-and-canonical-forms.md) (final).** The
  per-entry **revision token** identity substrate is settled by EP-0012's
  disposition 5 (canonical EDN identity for params/scopes/work ids). An
  optimistic apply records the touched entry's `:rf/scoped-resource-key` plus a
  monotone per-entry revision; the conflict check is a canonical-identity
  comparison, not a value diff.
- **[EP-0011](EP-0011-uniform-async-reply-envelope.md) (final) /
  [EP-0010](EP-0010-causal-world-inputs.md) (final).** Commit/rollback are
  driven by the same accepted/stale-suppressed reply boundary the mutation
  runtime already enforces; the apply/settle timestamps are EP-0010 causal
  facts, never ambient reads.
- **[EP-0001](EP-0001-frame-partitions.md) (final).** Optimistic patches mutate
  only `:rf.runtime/resources` (runtime-db); the recorded inverse + revision
  ride the mutation **instance** row at `:rf.runtime/mutations` — durable
  framework-owned state, committed by the one cascade. No app-db write.
- **[EP-0015](EP-0015-frame-owned-egress-policy.md) (final).** Inverse patches
  and snapshots can contain sensitive/large cached values; the recorded inverse
  on the instance row and every new trace op pass through the egress projection
  policy (snapshots are a prime `:large?` candidate — see §Security).
- **[EP-0014](EP-0014-derivation-and-process-algebra.md) (final).** Tag-addressed
  optimistic patching reuses the resource tag index (the same `:tags` derivation
  the invalidation engine matches against), not a second matcher.

## Specification

> The normative contract lives in
> [`spec/016-Resources.md`](../../spec/016-Resources.md); the text below is the
> design record behind that amendment. Where this EP and the spec differ, the
> spec governs.

This EP has **four decisions** and **three riders**.

### Decision 1: the optimistic plan is a registration-level forward plan

A mutation MAY declare an **`:optimistic`** plan on `reg-mutation` — the
*forward* twin of the landed success-time `:patches` / `:populates` /
`:removes` plan. It is a fn of the **one canonical signature** every
cache-consequence callback already uses, `(params)` — note there is **no
`result`**, because the optimistic apply runs *before* the request is sent and
no reply exists yet:

```clojure
(rf/reg-mutation :conduit/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :request       (fn [{:keys [slug]} _ctx]
                    {:request {:method :post
                               :url    (str "/articles/" slug "/favorite")}
                     :decode  schema/ArticleResponse})

   ;; NEW (this EP): the FORWARD optimistic patch, applied at execute time.
   ;; Same map-form exact targets as :patches; each value is a patch-fn
   ;; (fn [old-data] -> new-data) — NO mutation result yet.
   :optimistic
   (fn [{:keys [slug]}]
     {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
      (fn [article]
        (-> article
            (assoc-in [:article :favorited] true)
            (update-in [:article :favoritesCount] inc)))})

   ;; UNCHANGED (EP-0016): the authoritative success-time consequences.
   :populates   (fn [{:keys [slug]} result]
                  {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
                   result})
   :invalidates (fn [{:keys [slug]} _result]
                  [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}
                   {:scope {:from-db :conduit/session} :tags #{[:feed]}}])})
```

The author writes the forward patch only. The runtime **derives the inverse**
by snapshotting each touched entry's `:data` *before* applying the forward
patch (Decision 2). The forward patch-fn keys are the same **map-form exact
targets** (EP-0016 Rider 2) as `:patches`, scope-resolved at execute time
through the same `resolve-exact-target-scope` path (`mutation_events.cljc`
lines 152–175). A target whose `{:from-db …}` scope resolves nil is
**fail-closed** (dropped) exactly as in `apply-patches`.

> **Why not a forward+inverse pair the author writes?** Rejected (see
> §Alternatives). An author-written inverse drifts from the forward patch and is
> a fresh footgun; the snapshot-of-touched-entries inverse is always truthful
> by construction and costs one structural-shared reference per entry.

### Decision 2: recording — snapshot inverse + per-entry revision token

When `:rf.mutation/execute` applies an `:optimistic` plan, for each resolved
scoped key it records, on the mutation **instance** row
(`:rf.runtime/mutations`, the durable framework partition):

```clojure
;; the reserved :patch-summary slots (mutation_events.cljc 1102–1103),
;; now FILLED for an optimistic write:
:patch-summary
{:patched   [...]            ;; (unchanged success-path facts)
 :snapshot-id <opaque-id>    ;; identifies this optimistic apply
 :rollback                   ;; the recorded inverse, per touched entry:
 [{:resource/key <scoped-resource-key>     ;; EP-0012 canonical identity
   :revision     <rev-at-apply>            ;; the entry's revision when we applied
   :before       <entry-before-or-:absent> ;; structural-shared snapshot
   :forward      <applied-forward-summary>}]
 :reconciliation-refetches [...]}          ;; filled at settle (Decision 3)
```

**Inverse = entry snapshot, not value diff.** `:before` is the whole resource
**entry** as it stood immediately before the forward patch (`:data`, `:status`,
freshness timers), captured by reference (structural sharing — the cache already
shares structure on `=`, see `patch-entry`, `mutation_runtime.cljc` 234–261).
For a key with no entry, `:before` is `:absent` (rollback removes it). This
makes the inverse **truthful by construction**: rollback restores exactly the
entry that existed, including its freshness, never a reconstructed approximation.

**Revision token.** Each resource entry gains a monotone per-entry
`:revision` counter (a small fact on the entry, EP-0012-canonical), bumped on
**every authoritative durable entry write** a rollback could clobber — load
success, populate, patch, optimistic apply, invalidation-driven refetch success
— and **never gated on `(= old new)` of `:data`** (byl7bk ruling; see Open Issue
5). The bump is unconditional precisely because `entry-succeeded` re-stamps
`:loaded-at` / `:stale-at` / `:tags` even when `:data` is `=`-shared
(`state.cljc` 684, 690–691, 697): a value-changing-only token would miss that
freshness settle and let a later rollback clobber newer freshness with a stale
snapshot. `:revision` is a **distinct fact, not a reuse of `:generation`**
(`:generation` bumps at load *start*, so it would false-conflict on every
in-flight refetch). The optimistic apply records the revision it observed. The
conflict check at settle (Decision 3) is
`(= recorded-revision current-revision)` — a canonical-identity comparison, not
a value compare. This is the EP-0012 disposition-5 identity substrate the
EP-0016 ruling names.

**Concurrency: optimistic patches stack as an ordered ledger.** Two concurrent
optimistic writes touching the same entry each record their own
`:snapshot-id` + inverse, in apply order, on their own instance rows. The entry
carries an ordered list of the live optimistic apply ids touching it
(`:optimistic-applies` on the entry — a small fact, pruned as each settles). The
inverse a rollback replays is **the recorded entry-before for *that* apply**;
the conflict check (revision moved) is what catches "someone else committed in
between" — see Decision 3.

### Decision 3: the settle protocol — commit / rollback / reconcile

The landed phase order (Spec 016 §Phase order) gains an apply phase and a
reconcile in the consequence phase. The full normative order for an optimistic
write:

1. **Resolve** canonical params + execution scope.
1.5. **Optimistic apply** — snapshot each touched entry, record inverse +
    revision on the instance row, apply the forward patch, bump each entry's
    revision, emit `:rf.mutation/optimistic-applied`.
2. **Send** the managed request.
3. **Accept / suppress** the host reply (the landed work-id + generation gate).
4. **Settle the optimistic apply** *then* apply success-time consequences:
   - **`:ok` (accepted) → COMMIT.** The authoritative `:populates` / `:patches`
     run (they overwrite the optimistic value with the server's), then
     `:invalidates`. The optimistic apply is *cleared* from each entry's live
     list. Populate-as-authoritative-load (Rider 1) means a populated key is
     not re-fetched by this mutation's own invalidation. The recorded inverse is
     discarded (the commit superseded it). Emit `:rf.mutation/reconciled`.
   - **`:error` / accepted `:cancelled` → ROLLBACK.** For each recorded inverse,
     apply the **conflict rule** below. Emit `:rf.mutation/rolled-back`.
   - **stale / superseded → NEITHER.** A stale reply (a re-execute under the
     same instance, or `:rf.mutation/clear`) is suppressed exactly as today
     (the mandatory stale-suppression boundary). Its optimistic apply was
     **already superseded** by the newer execute's apply (which re-snapshotted
     the entry *as the optimistic value*, recording its own inverse), so the
     stale reply's inverse is **discarded, never replayed** — the current
     generation owns the entry. This is the determinism guarantee for
     superseded responses.
5. **Instance settlement** (settle the row, work-ledger row).
6. **Continuation** — `:reply-to`, unchanged (fires only for the accepted
   terminal reply, after settle).

#### The conflict rule (rollback-by-invalidation, the default)

When a rollback wants to restore a recorded inverse, it first checks the touched
entry's **current revision** against the **recorded revision**:

| Condition | Action |
|---|---|
| current revision **==** recorded revision (no one else touched it) | **restore** the recorded `:before` entry verbatim (structural-shared) — the truthful, conflict-free rollback |
| current revision **moved** (a concurrent mutation, refetch, or populate landed) | **`:on-conflict :invalidate`** (default): the recorded inverse is a **stale "before"** and restoring it would clobber newer truth — so the runtime marks the entry stale (one scoped `:rf.resource/invalidate-tags` of the entry's own tags, in its own scope) and lets the read path re-fetch the authoritative value. Emit `:rf.mutation/rolled-back` with `:conflict true`. |

`:on-conflict` is a registration-level option with members:

- **`:invalidate`** (default, recommended) — refetch the authoritative value on
  conflict. Never restores a stale inverse. This is the deterministic,
  always-correct choice and matches the EP-0016 ruling's
  `:on-conflict :invalidate` name.
- **`:force`** — restore the recorded inverse even on conflict (last-write-wins
  by the rolling-back mutation). For entries the author *knows* are
  single-writer. Tooling warns that `:force` can clobber a concurrent write.

> **This is the hard correctness core.** TanStack/SWR's `onError` rollback
> restores a captured context unconditionally; under concurrent optimistic
> writes that can resurrect a stale value. re-frame2's deliberate divergence is
> to make conflict-aware **invalidation** the default — the read path is the one
> authority that can always recover truth, so a contested rollback defers to it
> rather than guessing.

#### Determinism summary

- **failed mutation, no conflict** → exact entry restored.
- **failed mutation, conflict** → entry invalidated + refetched (authoritative).
- **stale/superseded reply** → its inverse discarded; current generation owns
  the entry (the newer apply already recorded the truthful inverse).
- **success** → authoritative `:populates`/`:patches`/`:invalidates`; optimistic
  value overwritten.

Every branch is a function of the work-id + generation acceptance verdict and
the per-entry revision — both already canonical, recorded facts. There is no
wall-clock race in the decision.

### Decision 4: tag-addressed optimistic patching (`:optimistic-tags`)

The exact-key `:optimistic` plan (Decision 1) requires the author to name each
touched scoped key. The canonical cross-view-consistency demand — flip a
favorite and have it flip on the **detail**, **every list containing the
article**, and the **session feed** at once — needs to touch entries the author
cannot enumerate (which lists currently cache this article?). This EP adds a
**tag-addressed** optimistic plan, the optimistic twin of the landed
tag-addressed `:invalidates`:

```clojure
:optimistic-tags
(fn [{:keys [slug]}]
  [{:scope :rf.scope/global
    :tags  #{[:article slug]}
    ;; patch EVERY cached entry carrying [:article slug] in global scope:
    :patch (fn [old-data] (assoc-in old-data [:article :favorited] true))}
   {:scope {:from-db :conduit/session}
    :tags  #{[:feed]}
    :patch (fn [old-data] ,,, )}])
```

It reuses the **same tag index** the invalidation engine matches against (the
resource `:tags` derivation, EP-0014) and the **same per-target scope
descriptor** grammar (EP-0016 Decision 2) — `:scope` is `:rf.scope/same` /
`:rf.scope/global` / concrete / `{:from-db …}`, fail-closed on nil. Each matched
entry gets the same snapshot-inverse + revision treatment as Decision 2; the
conflict rule (Decision 3) applies per matched entry independently.

This keeps the EP-0016 ruling's commitment: tag-addressed patching ships
*because* `patch-article-everywhere` is the demand that justifies the machinery.

### Rider 1: optimistic writes still suppress stale; `:reply-to` is unchanged

An optimistic write is **not** a reply. `:reply-to` (EP-0016 D1) continues to
fire only for the accepted terminal reply, after settle (phase 6). The
optimistic apply (phase 1.5) emits its own `:rf.mutation/optimistic-applied`
trace but dispatches no continuation. The instance-keyed sub
(`[:rf.mutation/state {:instance …}]`) gains a derived `:optimistic?` flag
(true between phase 1.5 and settle) so a view can render "pending, but showing
my optimistic value."

### Rider 2: optimistic apply requires an explicit scope (fail-closed), unlike execution scope

EP-0016 made the mutation **execution** scope fail-*open* (a write has no
cached-read leak boundary of its own; §Mutation scope is two distinct scopes).
But an optimistic apply **writes the cache** — it has the same leak boundary a
read does. Therefore each optimistic target's scope is **fail-closed**: a
`{:from-db …}` that resolves nil drops that target (never an implicit global
write), identically to `apply-patches`. The execution-scope fail-open default
still governs which scope the *success-time* invalidation runs in; the
optimistic apply's per-target scope is independent and fail-closed.

### Rider 3: no optimistic apply for `:before-request` invalidation timing

A mutation with `:invalidate-timing :before-request` already stales entries
before the request. Composing that with an optimistic apply that immediately
*re-populates* the same entries is contradictory (stale-then-optimistic-fresh).
This EP makes `:optimistic` / `:optimistic-tags` **incompatible** with
`:before-request` timing — a loud registration error
(`:rf.error/mutation-optimistic-before-request`), not a silent precedence rule.
Optimistic writes use the default `:after-success` timing.

## Public API sketch (what the consumer writes)

The full optimistic favorite, end to end — the heart flips on click, reverts on
failure, reconciles on conflict:

```clojure
;; REGISTRATION — the write plus its forward optimistic patch.
(rf/reg-mutation :conduit/favorite
  {:params-schema [:map [:slug :string]]
   :scope         :rf.scope/global
   :on-conflict   :invalidate                ;; NEW (default; shown for clarity)
   :request       (fn [{:keys [slug]} _ctx]
                    {:request {:method :post
                               :url    (str "/articles/" slug "/favorite")}
                     :decode  schema/ArticleResponse})

   :optimistic                               ;; NEW — forward patch, fn of params only
   (fn [{:keys [slug]}]
     {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
      (fn [article]
        (-> article
            (assoc-in [:article :favorited] true)
            (update-in [:article :favoritesCount] inc)))})

   :populates                                ;; UNCHANGED — authoritative on :ok
   (fn [{:keys [slug]} result]
     {{:resource :conduit/article :params {:slug slug} :scope :rf.scope/global}
      result})
   :invalidates                              ;; UNCHANGED — per-scope descriptors
   (fn [{:keys [slug]} _result]
     [{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}
      {:scope {:from-db :conduit/session} :tags #{[:feed]}}])})

;; CALL SITE — unchanged. No new payload key required for the common case;
;; the optimistic plan lives on the registration, like every other consequence.
(rf/reg-event-fx :ui/favorite
  (fn [{:keys [db]} [_ slug favorited?]]
    {:fx [[:dispatch [:rf.mutation/execute
                      {:mutation (if favorited? :conduit/unfavorite :conduit/favorite)
                       :params   {:slug slug}
                       :instance [:favorite slug]
                       :cause    [:click :ui/favorite slug]}]]]}))

;; VIEW — the instance sub gains :optimistic?; the heart can render its
;; optimistic value while still pending.
(reg-view favorite-button [{:keys [article]}]
  (let [{:keys [slug favorited favoritesCount]} article
        fav @(subscribe [:rf.mutation/state {:instance [:favorite slug]}])]
    [:button {:disabled (:pending? fav)            ;; still pending-aware
              :class    (when favorited "active")  ;; the value is already optimistic in cache
              :on-click #(dispatch [:ui/favorite slug favorited])}
     [:i.ion-heart] " " favoritesCount]))
```

The deliberate shape: **the optimistic plan is a registration consequence**,
just like `:populates` / `:invalidates`. The call site does not change, and the
view reads the optimistic value straight from the cache (the heart is already
flipped because the entry was patched at phase 1.5). The author writes one
forward patch and gets truthful inverse + conflict-aware rollback for free.

A per-call opt-out (`:rf.mutation/execute` `{:optimistic? false}`) is reserved
for the rare case a single call wants the pessimistic path — see Open Issue 4.

## Surfacing to tooling (Xray / trace)

The reserved slots are filled and three trace ops are added:

| Trace op | When | Carries |
|---|---|---|
| `:rf.mutation/optimistic-applied` | phase 1.5 | `:snapshot-id`, the touched `:affected-keys`, per-key `:revision`, `:tag-matched-keys` (for `:optimistic-tags`), elided `:before`-summary |
| `:rf.mutation/rolled-back` | phase 4 (error/cancel) | `:snapshot-id`, per-key `:restored` vs `:conflict` (`:invalidate`/`:force`), `:refetched` keys |
| `:rf.mutation/reconciled` | phase 4 (ok) | `:snapshot-id`, which optimistic keys the authoritative populate/patch overwrote, `:reconciliation-refetches` |

These ride the existing `:rf.mutation/*` family (the success trace already
reserves `:patch-summary`). The mutation instance row's `:patch-summary`
`:snapshot-id` / `:rollback` / `:reconciliation-refetches` slots (today nil) are
populated, so Xray's mutation view can show, per write: what was optimistically
applied, whether it committed or rolled back, and whether any rollback hit a
conflict and refetched instead. Every recorded `:before` snapshot and trace
value passes through the EP-0015 egress projection (snapshots are `:large?`
candidates — §Security).

## Rationale

The shape follows three re-frame2 laws already established by Spec 016 and the
EP corpus:

- **Cache consequences are declarative data on the registration.** The
  optimistic plan is the forward twin of `:patches`; making it a registration
  consequence (not a call-site closure) keeps the "which reads does this write
  touch" answer in one inspectable place, reuses the map-form target + scope
  grammar, and lets Xray name it.
- **The runtime owns the inverse.** An author-written inverse is a drift
  footgun; a snapshot-of-touched-entries inverse is truthful by construction and
  nearly free (structural sharing). This mirrors how the runtime — not the
  author — owns reply addressing, stale suppression, and work identity.
- **The read path is the recovery authority.** When a rollback is contested,
  defer to the one mechanism that can always recover truth (refetch) rather than
  restoring a guess. `:on-conflict :invalidate` as the default is the
  re-frame2-shaped answer to the concurrency problem TanStack/SWR handle by
  unconditional context restore.

### Alignment to gold-standard references

- **TanStack Query** (`onMutate` → cancel queries → snapshot → optimistic
  `setQueryData` → `onError` rollback from context → `onSettled` invalidate).
  re-frame2 maps this to: phase 1.5 apply = `onMutate`+`setQueryData`; the
  recorded snapshot inverse = the `onMutate` context; phase-4 rollback =
  `onError`; phase-4 invalidate/populate = `onSettled`. **Divergence:** the
  apply is declarative registration data, not an imperative callback; the
  inverse is runtime-recorded, not author-returned; and rollback is
  conflict-aware (`:on-conflict :invalidate`) rather than unconditional context
  restore.
- **RTK Query** (`onQueryStarted` → `updateQueryData` returns a `patchResult`
  with `.undo()` → `.undo()` on error). re-frame2's recorded inverse **is** the
  `patchResult.undo()` made declarative and durable (on the instance row,
  replayable), and `updateQueriesData` (patch-many) is `:optimistic-tags`.
  **Divergence:** RTK's `.undo()` is unconditional; ours checks the revision and
  invalidates on conflict.
- **SWR** (`mutate(key, fn, {optimisticData, rollbackOnError, populateCache,
  revalidate})`). re-frame2 maps `optimisticData` → `:optimistic`,
  `rollbackOnError` → the default rollback (always on for an accepted error),
  `populateCache` → the landed `:populates`, `revalidate` → the landed
  `:invalidates`. **Divergence:** SWR's rollback restores the snapshot;
  re-frame2 reconciles by invalidation on conflict.
- **XState v5** (the standing gold standard for *machine* semantics). Not a
  direct analogue here — optimistic rollback is cache, not statechart — but the
  *spirit* (a transition that can be deterministically reversed; no stuck
  in-flight identity, surfaced not silently dropped — cf. the
  `dangling-on-restore` treatment) is preserved: every optimistic apply has a
  recorded, deterministic terminal disposition (commit / rollback / discard),
  visible in the trace.

## Backwards Compatibility

Purely additive. A mutation with no `:optimistic` / `:optimistic-tags`
behaves **exactly** as today — the success path (`:patches` / `:populates` /
`:removes` / `:invalidates`) is unchanged; the phase order gains phase 1.5 only
when an optimistic plan is present. The reserved instance-row /
`:patch-summary` slots are already nil today, so filling them is no shape
change (the implementation was built for this — `mutation_runtime.cljc`
126–134). Pre-alpha: no external consumers, no migration window.

## Bead Plan / Reference Implementation

Proposed sequence (not a one-PR requirement):

1. **Spec 016 amendment** — add §Optimistic mutations (the four decisions + three
   riders), the phase-1.5 / reconcile additions to §Phase order, the new trace
   ops to the `:rf.mutation/*` family, and worked examples. Un-defer
   `:optimistic` / `:rollback` from the registration-spec deferred-keys list
   (line 663). Hot-zone: sequence behind any other live spec/016 work.
2. **Per-entry revision token** — add a **distinct** `:revision` fact to the
   resource entry (NOT a reuse of `:generation`; base value `0`), bump it on
   **every authoritative durable entry write** (unconditionally, never gated on
   `(= old new)` of `:data` — see Open Issue 5); the conflict-check helper.
   (`state.cljc` + `mutation_runtime.cljc`.)
3. **Optimistic apply (phase 1.5)** — `:optimistic` resolution + snapshot-inverse
   recording on the instance row; reuse `resolve-exact-target-scope` +
   `validate-target-map!`. Emit `:rf.mutation/optimistic-applied`.
4. **Settle protocol** — commit/rollback/reconcile in the success & failure
   handlers; the conflict rule + `:on-conflict`. Stale-suppression discard path.
   Emit `:rf.mutation/rolled-back` / `:reconciled`.
5. **Tag-addressed optimistic** (`:optimistic-tags`) — reuse the tag index +
   per-target scope descriptors.
6. **Subs + tooling** — `:optimistic?` derived flag on `:rf.mutation/state`;
   fill the `:patch-summary` slots; Xray mutation-view rows; egress projection on
   snapshots.
7. **Conformance tests** — the validation plan below (small synthetic cases).
8. **Guide + dogfood** — rewrite tutorial Part 4's "Honest limits on
   `:populates`" note; add the optimistic favorite to `realworld_resources`; the
   recommended larger driver is a **Linearlite-class port** (EP-0016 issue 9).

### Validation plan (general laws, small synthetic cases first)

1. Optimistic apply patches the entry at phase 1.5, before the request is sent.
2. An accepted `:ok` reply commits: authoritative `:populates`/`:patches`
   overwrite the optimistic value; the optimistic apply clears from the entry.
3. An accepted `:error` reply with **no conflict** restores the exact recorded
   `:before` entry (including freshness).
4. An accepted `:error` reply **with a conflict** (entry revision moved) does
   **not** restore; it invalidates + refetches (`:on-conflict :invalidate`).
5. `:on-conflict :force` restores the inverse even on conflict (with a warning).
6. A **stale/superseded** reply discards its inverse and never rolls back; the
   newer generation's optimistic value (or its settle) owns the entry.
7. Two concurrent optimistic writes on the same entry each record their own
   inverse + revision; the later commit and the earlier rollback compose
   deterministically per the conflict rule.
8. `:optimistic-tags` patches every tag-matched entry across scopes; each
   matched entry rolls back independently.
9. A `{:from-db …}` optimistic target that resolves nil is fail-closed (dropped).
10. `:optimistic` + `:before-request` timing is a loud registration error.
11. `:reply-to` fires once, after settle, for the accepted reply only — the
    optimistic apply dispatches no continuation.
12. Trace evidence: `optimistic-applied` / `rolled-back` / `reconciled` carry
    snapshot id, per-key revision, conflict/restore disposition; the instance
    row's `:patch-summary` slots are filled.
13. Epoch restore: a `:pending` optimistic write dangles (the landed
    `dangling-on-restore` path) — Open Issue 3 governs whether its optimistic
    apply rolls back on the dangle.

## Open Issues

> These were the genuine design decisions the operator ruled at graduation
> (`proposal → final`, 2026-06-17). The recommendations below were adopted as the
> shipped cut (Open Issue 5 carries its own load-bearing inline ruling, `byl7bk`,
> 2026-06-15); they are kept verbatim as the record of what was ruled.

1. **Author-written inverse vs runtime snapshot inverse.** This EP recommends
   the runtime snapshot-of-touched-entries inverse (truthful by construction,
   no drift). The alternative is an explicit forward+inverse pair the author
   writes (matches RTK's manual `patchResult` more literally, lets the author
   record a *semantic* inverse cheaper than a full entry snapshot for huge
   entries). **Recommendation:** runtime snapshot inverse as the only public
   form; revisit a semantic-inverse opt-in only if a Linearlite-class entry
   proves the snapshot cost real.

2. **`:on-conflict` default — `:invalidate` vs `:force`.** This EP recommends
   `:invalidate` (defer to the read path; never resurrect a stale value). It is
   the always-correct choice but costs a refetch on every contested rollback.
   `:force` is cheaper but can clobber a concurrent write. **Recommendation:**
   `:invalidate` default, `:force` opt-in with a tooling warning. (Ruling needed
   because this is the core correctness/UX trade.)

3. **Optimistic apply on epoch-restore dangle.** A `:pending` optimistic write
   dangles to `:error` on restore (the landed `dangling-on-restore` path,
   `mutation_runtime.cljc` 170–219). Should the dangle **roll back** the
   optimistic apply (the entry shows the optimistic value with no in-flight write
   to confirm it)? **Recommendation:** yes — a dangle is an accepted-error-shaped
   terminal, so it should trigger the same conflict-aware rollback. But this
   touches the restore reconciler; confirm the ordering is sound.

4. **Per-call optimistic opt-out shape.** Should `:rf.mutation/execute` accept
   `{:optimistic? false}` to force the pessimistic path for one call (e.g. a
   bulk/replay context where the optimistic flash is unwanted)? Or is the
   registration plan always-on? **Recommendation:** reserve `{:optimistic?
   false}` as the only per-call override (a boolean disable, not a per-call
   plan — a per-call forward plan would re-introduce call-site cache logic the
   EP-0016 doctrine pushes onto the registration).

5. **Revision token scope — per-entry vs reuse `:generation`.** The conflict
   check needs a per-*entry* monotone token. The resource entry already carries
   `:generation` (the work/stale-suppression identity). Is `:generation`
   sufficient as the conflict token, or does it move for reasons orthogonal to
   "the cached value changed" (e.g. a refetch that returns `=` data)?

   **Ruling (byl7bk, 2026-06-15) — load-bearing amendment, gates the
   settle-protocol slice.** Use a **distinct `:revision` fact** (NOT a reuse of
   `:generation`), bumped on **every authoritative durable entry write a rollback
   could clobber** — *not* "value-changing only." This corrects the EP's earlier
   weaker lean toward bumping only on a value-changing write.

   - **Why not gate on `(= old new)` of `:data`.** `entry-succeeded` re-stamps
     `:loaded-at` / `:stale-at` *even when `:data` is `=`-shared* — the
     structural-sharing branch keeps the old `:data` identity but still writes a
     fresh `:loaded-at` / `:stale-at` / `:tags` (`state.cljc` 684, 690–691, 697).
     `patch-entry` and `populate-entry` do the same. So a "value-changing only"
     token would **miss a refetch-returning-equal-data freshness settle**: a later
     rollback would then silently clobber newer authoritative
     `:loaded-at` / `:stale-at` / `:tags` with a stale snapshot — a real
     cache-coherence bug. The conflict token therefore tracks *any* authoritative
     durable write to the entry, not just value changes.
   - **Why a distinct `:revision`, not `:generation`.** `:generation` bumps at
     load **start** (`entry-start-load`, `state.cljc` ~660), so reusing it would
     **false-conflict on every in-flight refetch** — the entry's identity would
     "move" the instant any load begins, before any authoritative write lands.
     `:revision` is the per-entry write identity; `:generation` is the
     work/stale-suppression identity. They are distinct facts.
   - **Bump rule.** Bump `:revision` on `entry-succeeded` **unconditionally** (not
     gated on `(= old new)`), on populate, on patch, on optimistic apply, and on
     an invalidation-driven settle — every authoritative durable write.
   - **Bias to over-bump.** When in doubt, bump. A *false* conflict costs one
     refetch (and is made safe by the `:on-conflict :invalidate` default — the
     read path recovers truth). A *missed* conflict means a stale inverse silently
     clobbers newer truth — silent corruption. The asymmetry favours over-bumping.
   - **New entry fact (impl note).** The base entry shape (`empty-entry*`,
     `state.cljc` 188–211) has **no `:revision` key today**; the deferred EP-0019
     impl wave adds `:revision` as a new durable entry fact (base value `0`,
     alongside `:generation`).

6. **Optimistic `:removes` and `:populates`-of-absent.** Decision 1 covers
   forward *patch* of existing entries. Should an optimistic plan also support
   optimistic **remove** (a delete that vanishes the card immediately, restored
   on failure) and optimistic **seed** of an absent entry (`:before :absent`)?
   **Recommendation:** yes — both fall out of the snapshot inverse (`:absent`
   sentinel) at no extra mechanism; include them in Decision 1's grammar rather
   than a follow-on. Flag for the operator because it widens the surface.

7. **Naming: `:optimistic` vs `:optimistic-patches`.** The success-path twin is
   `:patches`; symmetry argues `:optimistic-patches`. But `:optimistic` reads
   better at the call site and matches SWR's `optimisticData`. **Recommendation:**
   `:optimistic` (forward exact) + `:optimistic-tags` (forward tag-addressed),
   per EP-0007 one-name-per-fact. Operator to confirm the spelling.

## Recommendation

Accept this EP as the optimistic-rollback follow-on EP-0016 issue 9 defers,
scoped exactly as that disposition names: keep tag-addressed patching, exclude
the normalized graph cache, use the EP-0012 disposition-5 revision identity
substrate, and drive the dogfood with a Linearlite-class port.

The recommended cut:

- a registration-level **`:optimistic`** forward plan (the twin of `:patches`),
  with a **runtime-recorded snapshot inverse** + per-entry revision token;
- a deterministic **commit / rollback / reconcile** settle protocol keyed on the
  landed work-id + generation acceptance verdict;
- **`:on-conflict :invalidate`** as the default — the read path recovers truth on
  a contested rollback, the re-frame2-shaped divergence from TanStack/SWR's
  unconditional context restore;
- **`:optimistic-tags`** for the cross-view-consistency demand;
- the reserved trace/instance slots filled and three new `:rf.mutation/*` ops.

This closes the most visible parity gap while keeping the resource cache a
declarative, inspectable, fail-closed surface rather than an imperative
optimistic-write engine.

## Spec 016 changes (landed)

> This records the `spec/016-Resources.md` amendment that graduated with this EP.
> The normative text lives in the spec ([§Optimistic mutations](../../spec/016-Resources.md));
> the quote below is the design record. Where the two differ, the spec governs.

A new subsection **§Optimistic mutations** landed after
[§Map-form exact resource targets](../../spec/016-Resources.md), and the
deferred-keys line changed. The normative text:

> **§Optimistic mutations (`:optimistic`, `:optimistic-tags`, `:on-conflict`).**
> A mutation MAY declare an optimistic plan applied to the resource cache
> *before* the request is sent (phase 1.5). `:optimistic` is `(fn [params] ->
> {target patch-fn})` over the same map-form exact targets as `:patches`, where
> each `patch-fn` is `(fn [old-data] -> new-data)` — there is **no result**, the
> reply does not yet exist. `:optimistic-tags` is the tag-addressed twin (the
> same per-target scope descriptors as `:invalidates`). Each optimistic target's
> scope is **fail-closed** (a `{:from-db …}` resolving nil is dropped) — unlike
> the fail-open execution scope, because an optimistic apply writes the cache.
>
> The runtime **records the inverse**: for each touched key it snapshots the
> resource entry (`:before`, structural-shared; `:absent` for a missing entry)
> and its per-entry `:revision` at apply time, on the mutation instance row's
> `:patch-summary` `:rollback` slot. The author does not write the inverse.
>
> The settle protocol is deterministic, keyed on the work-id + generation
> acceptance verdict:
>
> - an accepted **`:ok`** reply **commits** — the authoritative `:populates` /
>   `:patches` overwrite the optimistic value, then `:invalidates` runs; the
>   recorded inverse is discarded;
> - an accepted **`:error`** / `:cancelled` reply **rolls back** — for each
>   recorded inverse, if the entry's current `:revision` equals the recorded one
>   the `:before` entry is restored verbatim; if it **moved**, `:on-conflict`
>   governs: `:invalidate` (default) marks the entry stale and refetches the
>   authoritative value; `:force` restores the (stale) inverse anyway;
> - a **stale/superseded** reply rolls back nothing — its inverse is discarded,
>   the current generation owns the entry (the newer apply recorded the truthful
>   inverse).
>
> `:optimistic` / `:optimistic-tags` are **incompatible** with
> `:invalidate-timing :before-request` (a loud registration error,
> `:rf.error/mutation-optimistic-before-request`). The optimistic lifecycle is
> trace-visible — `:rf.mutation/optimistic-applied` / `rolled-back` /
> `reconciled` — and the instance row's reserved `:snapshot-id` / `:rollback` /
> `:reconciliation-refetches` slots are populated; all snapshots/trace values
> pass through the EP-0015 egress projection.

And the deferred-keys line (`spec/016-Resources.md`) changed: `:optimistic` /
`:rollback` are removed from the deferred set (they are now the landed
registration keys `:optimistic` / `:optimistic-tags` / `:on-conflict`), and
§Deferred slices no longer lists "optimistic rollback" as a later slice — it
points at the normative §Optimistic mutations as its home.

## Security, Privacy, And Observability

A recorded inverse is a **snapshot of cached resource data** — exactly the
sensitive/large surface EP-0015 governs. The `:before` entry on the instance
row, and every value on the new trace ops, MUST pass through the frame egress
projection: snapshots are prime `:large?` candidates (a full entry per touched
key) and may carry `:sensitive?` fields. The trace ops report *that* an
optimistic apply happened and its per-key disposition; the elided `:before`
summary follows the same projection the success `:patch-summary` already uses.

A `:cross-scope?`-style broad optimistic apply is **not** offered — optimistic
patching is exact-key or tag-addressed-within-named-scopes only, both
fail-closed. There is no scope-agnostic optimistic write, by construction, so
the optimistic surface cannot leak a write across users/tenants the way an
audited `:cross-scope?` invalidation deliberately can.
