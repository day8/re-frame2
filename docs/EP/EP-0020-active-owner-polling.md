# EP-0020: Active-Owner Polling For Resource Revalidation

Status: final
Type: standards-track

> **Graduated `accepted → final` 2026-06-17 (Mike, operator graduation).** This
> EP added *interval revalidation* (polling) to Spec 016
> Resources, on the recommended cut: resource-level `:poll-interval-ms`, an
> unconditional active-owner tick (Open Question 3 → (a)),
> default-pause-when-hidden with `:poll-when-hidden?` reserved (OQ 2), the
> `:poll` cause (OQ 8) and `:poll-interval-ms` spelling (OQ 7); data-derived
> intervals (OQ 4), poll-failure back-off (OQ 5), and the per-use route/ensure
> override (OQ 6) are reserved to future consumer-driven work; the adapter
> `use-resource-lease` ergonomics (OQ 1) is a separate adapter bead. The
> normative contract lives in [`spec/016-Resources.md` §Polling](../../spec/016-Resources.md#polling)
> (a sibling of §Stale and GC scheduling), with the `:poll-interval-ms`
> spec-key, the `:poll` cause, and the `:rf.resource/poll-scheduled` /
> `:rf.resource/poll-fired` trace ops. The implementation + tests landed: the
> advisory-timer side-table (`re-frame.resources.timers`, the new `:poll` kind),
> the focus/reconnect scan-and-refetch core (`re-frame.resources.events`, the
> `entry-revalidation-in-flight?` coalescing gate), and the polling conformance
> suite (`resources_polling_cljs_test.cljc`). The §Specification section below is
> the design record; where it and the spec differ, the spec governs. `final`
> asserts the **decisions are settled** and the normative home governs.

## Abstract

Add **active-owner polling** to Spec 016: a resource may declare a poll
interval (`:poll-interval-ms`), and while an entry has at least one active owner
the runtime periodically re-runs its load by event. Polling is the third member
of the *cache freshness* family — alongside the already-landed `:stale-after-ms`
timer and the focus/reconnect active-stale scan — and it reuses both: the
host-side advisory timer side-table for scheduling and the existing
`:rf.resource/refetch` causal path (cause `:poll`, never an owner) for the tick.
Polling is **owner-driven, not component-driven** (the deliberate divergence
from TanStack Query / SWR), it pauses when the entry has no active owner and
when the document is hidden, and a poll tick joins/dedupes with in-flight work
and respects generation + stale-reply suppression exactly like every other
refetch.

## Motivation

[EP-0003 §Prior Art](EP-0003-resource-queries.md) benchmarked re-frame2
Resources against TanStack Query, RTK Query, and SWR. Three of the four make
interval refetching a first-class, one-line resource feature:

- **TanStack Query** — `refetchInterval: 5000` (and
  `refetchIntervalInBackground` to keep polling a hidden tab). The interval can
  be a function of the latest data, so a query can poll until a job completes
  and then stop.
- **SWR** — `refreshInterval: 5000`, plus `refreshWhenHidden` /
  `refreshWhenOffline` toggles. Default is "do not refresh a hidden tab."
- **RTK Query** — `pollingInterval: 5000` on a subscription, with
  `skipPollingIfUnfocused`.

re-frame2 today has the *adjacent* primitives — `:stale-after-ms` /
`:gc-after-ms` scheduling ([Spec 016 §Stale and GC
scheduling](../../spec/016-Resources.md#stale-and-gc-scheduling)) and
focus/reconnect active-stale revalidation (the
`:rf.resource/window-focused` / `:rf.resource/network-reconnected` events,
landed earlier) — but **no polling primitive**. Polling is explicitly
named a deferred later slice in [Spec 016 §Deferred
slices](../../spec/016-Resources.md#deferred-slices) ("polling/interval
revalidation") and in the reserved-but-unused key list ("`:poll-ms`" among the
rejected v1 keys, `spec/016-Resources.md` line 663).

The motivation is parity *demand*, not API shape. Dashboards, build/CI status,
notification counts, presence, queue depth, and "is this long job done yet"
reads all want a declarative "keep this fresh every N ms while someone is
looking at it." Today an app must hand-roll that with a recurring `:dispatch`
plus a `setInterval`-style effect, manually pausing on unmount and tab-hide —
exactly the per-feature bookkeeping Resources exists to remove.

### Why this is owner-driven, not component-driven

This is the load-bearing re-frame2 divergence. TanStack/SWR/RTK tie polling to a
*mounted React component observer*: the hook that reads the query is the thing
that keeps it polling, and unmounting the last observer stops the poll. re-frame2
already has a sharper, framework-owned version of "is anyone looking at this":
the **active-owner lease** ([Spec 016 §Active owners and
causes](../../spec/016-Resources.md#active-owners-and-causes)). An owner is a
liveness lease held by a route entry, a machine instance, an SSR render, or an
app-minted lease — and it already answers exactly the question polling needs:
*"Owners answer: should invalidation refetch now or only mark stale? **Should
polling continue?** May the entry be garbage-collected?"* (Spec 016 line 270 —
the spec already reserves the polling decision to the owner model). Polling
therefore rides the lease that already exists, not a second component-observer
concept. A poll is "while a live cause keeps this entry alive, keep it fresh."

## Goals

- Add a declarative `:poll-interval-ms` resource policy (and the route/event
  per-use override) that revalidates an active-owner entry on a fixed interval,
  without any component-side fetch call.
- Reuse the existing host-side advisory timer side-table
  (`re-frame.resources.timers`) — one new `:poll` timer kind beside `:stale` /
  `:gc` — so there is no second scheduling substrate.
- Reuse the existing causal refetch path: a poll tick dispatches
  `:rf.resource/refetch` with **cause `:poll`** (a cause, never an owner — it
  creates no liveness), so generation + stale-reply suppression and in-flight
  dedupe all apply unchanged.
- Pause polling when an entry has **no active owner**, and (by default) when the
  **document is hidden**; resume on owner re-attach / tab return.
- Compose cleanly with `:stale-after-ms`, focus/reconnect revalidation, and
  invalidation — no double-fetch, one coalesced in-flight attempt.
- Surface polling to tooling: a `:rf.resource/poll-scheduled` trace and a poll
  column in the Xray resource-instance table; the existing
  `:rf.resource/work-started` / `fetch-started` / `revalidate-scan`-style traces
  carry the `:poll` cause.
- Stay SSR/restore-safe: a poll timer is a host-transient advisory handle (never
  on the SSR / hydration / epoch wire), armed lazily client-side, cancelled on
  frame destroy.

## Non-Goals

- **No component-observer polling** — re-frame2 does not gain a
  React-hook-lifecycle poll. Polling is owner-driven (see Open Question 1 for
  the `:lease`-on-mount ergonomics question).
- **No `:poll-interval-ms` as a function of data in v1** — TanStack's
  "poll until the job is done, computed from the last response" is a useful but
  larger feature (it needs the tick to re-evaluate a predicate against decoded
  data). Reserved; see Open Question 4.
- **No always-on / background-tab polling by default** — `refetchIntervalInBackground`
  / `refreshWhenHidden` are an explicit opt-in, never the default (Open
  Question 2 rules the exact spelling and whether it ships in v1).
- **No new transport, no new work-ledger writer** — a poll tick is an ordinary
  resource refetch through managed HTTP; it mints no new work-ledger authority.
- **No polling of owner-free entries** — an entry no one owns is not pinned
  alive by a poll; polling never extends GC (it is a freshness mechanism, not a
  liveness one).
- **No subscription-driven polling** — subscriptions stay passive reads; a poll
  is caused by the runtime's timer, exactly as stale/GC timers are.

## Relationships

- **[EP-0003](EP-0003-resource-queries.md) / [Spec
  016](../../spec/016-Resources.md) (final).** This EP is a post-final additive
  amendment. It does not reopen the read-resource foundation, the work ledger,
  route ownership, SSR, the cache-hit/lifecycle FSM, or HTTP-only graduation. It
  completes the "polling/interval revalidation" line item in §Deferred slices.
- **[Spec 016 §Stale and GC
  scheduling](../../spec/016-Resources.md#stale-and-gc-scheduling).** Polling is
  a sibling of stale/GC scheduling and reuses its substrate
  (`re-frame.resources.timers` — advisory host timer, durable re-check on fire,
  cancel-then-arm reschedule, frame-destroy teardown via the single
  `:resources/on-frame-destroyed!` hook). The proposed §Polling section lives
  next to it.
- **[EP-0016](EP-0016-resource-mutation-completion.md) (final).** EP-0016
  amended Spec 016 with mutation completion, scoped invalidation descriptors,
  and named scope resolvers. Polling composes with that surface: a poll tick is
  an ordinary `:rf.resource/refetch` and a polled scoped resource resolves its
  scope through the same `reg-resource-scope` resolvers.
- **Focus/reconnect active-stale revalidation (landed).** Polling
  reuses the exact scan-and-refetch discipline of
  `re-frame.resources.events/revalidate-handler`: select active-owner entries,
  skip those with a live in-flight refetch (the `entry-revalidation-in-flight?`
  coalescing gate), dispatch background `:rf.resource/refetch` with a cause. A
  poll tick is the timer-driven counterpart of a focus/reconnect signal.
- **[EP-0010](EP-0010-causal-world-inputs.md) (final).** A poll tick's freshness
  basis is the firing token's causal `:rf/time-ms`, not an ambient host clock
  read — the same World-Input discipline the focus/reconnect scan follows
  (`re-frame.resources.events/revalidate-handler`). The
  timer itself is host-transient scheduling (allowed to read the wall clock to
  arm), but the durable freshness decision uses the causal time.
- **[EP-0002](EP-0002-frame-target-resolution.md) (final).** A poll timer is
  keyed by `[frame-id resource-key :poll]` and its tick dispatches at the
  carried frame; no ambient default-frame fallback.
- **[EP-0011](EP-0011-uniform-async-reply-envelope.md) (final).** A poll tick's
  reply lowers through the same managed-HTTP reply + work-ledger correlation +
  stale-suppression substrate as any refetch; polling adds no new reply shape.
- **[EP-0015](EP-0015-frame-owned-egress-policy.md) (final).** A poll's cause,
  scope, params, and trace evidence pass through the egress projection policy
  like every other resource trace row; the poll interval is non-sensitive
  metadata.
- **[EP-0014](EP-0014-derivation-and-process-algebra.md) (final).** If polling
  later gains a data-derived stop predicate (Non-Goal / Open Question 4), it
  should be shaped as a declared derivation (inputs → output), not an anonymous
  closure over the entry.

## Specification (design record — the spec governs)

> This is the design record behind the Spec 016 amendment that graduated with
> this EP: the **§Polling** section sibling to §Stale and GC scheduling, in
> [`spec/016-Resources.md`](../../spec/016-Resources.md#polling). Where this
> record and the spec differ, the spec governs.

### Decision 1: `:poll-interval-ms` resource policy

`reg-resource` accepts an optional `:poll-interval-ms` policy key, alongside the
existing `:stale-after-ms` / `:gc-after-ms` policy keys validated in
`re-frame.resources.registry/validate-resource-spec!`
(`implementation/resources/src/re_frame/resources/registry.cljc:125`) and traced
on `:rf.resource/registered` (`registry.cljc:204-210`):

```clojure
(rf/reg-resource :dashboard/build-status
  {:scope            :rf.scope/global
   :params-schema    [:map [:repo :string]]
   :poll-interval-ms 5000          ;; revalidate every 5s while actively owned
   :stale-after-ms   0             ;; (optional) treat as immediately stale
   :request
   (fn [{:keys [repo]} _ctx]
     {:request {:method :get :url (str "/repos/" repo "/build")}
      :decode  :json})
   :tags (fn [_params _value] #{[:build repo]})})
```

Semantics (MUST):

- `:poll-interval-ms` is a positive integer of milliseconds. A non-positive or
  absent value means **no polling** (the resource never arms a poll timer) —
  exactly the disarm rule `re-frame.resources.timers/schedule!` already applies
  to a non-positive stale/GC delay (`timers.cljc:179`).
- While a poll-enabled entry has **at least one active owner** and the runtime
  is on a client platform, the runtime arms a `:poll` timer for
  `:poll-interval-ms` after the entry's last load settle (and re-arms after each
  subsequent settle — the cancel-then-arm reschedule of `timers.cljc:177-184`).
- On fire, the `:poll` timer dispatches a **re-checking internal event** whose
  handler verifies the live durable entry before doing anything (the timer is
  advisory — identical discipline to `:stale` / `:gc`,
  [Spec 016 §Stale and GC
  scheduling](../../spec/016-Resources.md#stale-and-gc-scheduling) rule 2). If
  the entry still has an active owner and is not paused, the handler dispatches a
  background `:rf.resource/refetch` with **cause `:poll`** and re-arms the next
  poll.

### Decision 2: polling is owner-driven and pauses fail-safe

A poll is **owner-driven**: it tracks the active-owner lease, never a component
observer. The pause/resume rules (MUST):

| Condition | Polling state | Mechanism |
|---|---|---|
| Entry has ≥1 active owner, document visible | **runs** | poll timer armed; tick → `:rf.resource/refetch` cause `:poll` |
| Entry's **last owner released** | **stops** | the same release path that drops the lease cancels the poll timer (a poll never pins an owner-free entry) |
| **Document hidden** (tab backgrounded) | **paused** (default) | poll ticks are suppressed while `document.visibilityState != "visible"`; resumes on tab return (which also fires the existing focus revalidation) |
| **Frame destroyed** | **stops** | `:poll` timers released via the single `:resources/on-frame-destroyed!` teardown hook, composed with the stale/GC timer + work-ledger + listener release (`re-frame.resources.timers/release-frame!`) |
| `:rf.resource/clear-scope` / `:rf.resource/remove` | **stops** | entry removed → `:rf.resource/cancel-timers` already cancels both timers for the key (`timers.cljc:285`); the `:poll` kind joins that cancel |

The default-hidden-pause matches SWR's and RTK's defaults and TanStack's
`refetchIntervalInBackground: false` default. A future `:poll-when-hidden?`
opt-in (Open Question 2) would keep polling a hidden tab for the
true-background-monitor case.

A poll tick carries **cause `:poll`, never an owner** — exactly as the
focus/reconnect scan refetches carry `:focus` / `:reconnect` as causes
(`re-frame.resources.events/revalidate-handler`,
`events.cljc:547-589`). Polling therefore creates no liveness, extends no GC,
and an entry stops polling the instant its last real owner is released.

### Decision 3: a poll tick joins/dedupes with in-flight work

A poll tick MUST be **coalescing**: if the entry already has a live in-flight
refetch (`:current-work` pointing at a non-terminal, non-`:abort-requested`
record), the tick is a no-op — it does NOT force a second generation. This is
the exact `entry-revalidation-in-flight?` gate the focus/reconnect scan already
uses (`events.cljc:488-506`), which prevents back-to-back signals from churning
abort/supersede. A slow endpoint whose response takes longer than the poll
interval therefore never stacks overlapping requests: the next tick that finds
work in flight skips, and the interval effectively backs off to the response
time.

When a poll tick *does* start work, it goes through the ordinary
`:rf.resource/refetch` path — a new generation, a work-ledger record, managed-HTTP
lowering, and stale-reply suppression by `:work/id` + `:generation`. A poll
reply that lands after the entry was superseded (a newer manual refetch, an
invalidation, a clear-scope) is suppressed by the single
`:rf.resource/stale-suppressed` boundary, never written. Polling adds **zero new
race surface** because it is just a timer-driven refetch.

### Decision 4: interaction with the rest of the lifecycle

- **`:stale-after-ms`.** Polling and staleness are orthogonal. A poll tick is an
  unconditional active-owner refetch *by the interval* (it does not first check
  `:stale?`), because the consumer who declared a poll interval is asserting "I
  want this re-read every N ms." (See Open Question 3 — whether a poll should
  instead be *stale-gated*, refetching only if the entry is stale at tick time,
  is the one genuinely open semantic.) The structural-sharing rule
  ([Spec 016 §Structural sharing](../../spec/016-Resources.md#structural-sharing))
  means an unchanged poll response preserves the old `:data` value, so views
  stay quiet when nothing changed.
- **Focus/reconnect revalidation.** A tab return both fires the focus
  active-stale scan *and* resumes the poll. The in-flight coalescing gate makes
  the overlap idempotent: whichever starts work first sets `:current-work`, and
  the other becomes a no-op. No double-fetch.
- **Invalidation / mutation.** A `:rf.resource/invalidate-tags` (or a mutation
  `:invalidates`) that refetches an actively-owned entry resets its load
  timestamp, which reschedules the poll (cancel-then-arm) — so an invalidation
  "resets the clock," it does not stack a poll on top of the invalidation
  refetch.
- **Background-refresh failure.** A failed poll tick is a *background refresh
  failure* — the entry stays `:loaded`, keeps prior `:data`, records
  `:refresh-error`, and the **next poll still fires** (a transient 503 must not
  permanently stop a monitor). Whether repeated poll failures should back off is
  Open Question 5.

### Proposed public-API surface (what the consumer declares)

**Resource-level (the common case):**

```clojure
(rf/reg-resource :notifications/unread-count
  {:scope            {:from-db :app/session}
   :params-schema    [:map]
   :poll-interval-ms 15000
   :request (fn [_ _ctx] {:request {:method :get :url "/notifications/unread"}
                          :decode :json})
   :tags    (fn [_ _] #{[:notifications]})})
```

**Per-use override (route resource / event ensure):** a route or `ensure`/`refetch`
payload may set `:poll-interval-ms` to override (or disable, with `0`) the
resource's declared interval for that owner context — mirroring how route
resources already override `:params` / `:scope` / `:blocking?`
([Spec 016 §Route integration](../../spec/016-Resources.md#route-integration)):

```clojure
(rf/reg-route :route/dashboard
  {:path "/dashboard"
   :resources
   [{:resource         :dashboard/build-status
     :params           {:repo "re-frame2"}
     :scope            :rf.scope/global
     :poll-interval-ms 3000        ;; this route wants a tighter poll
     :blocking?        false}]})
```

The per-use override is resolved at ensure/route-entry time and stored with the
entry's poll policy so the timer arms against the effective interval. (Open
Question 6: whether the per-use override ships in v1 or only the resource-level
declaration.)

### Proposed trace addition

One new op joins the `:rf.resource/*` family
([Spec 016 §Xray and AI
tooling](../../spec/016-Resources.md#xray-and-ai-tooling)):

- **`:rf.resource/poll-scheduled`** — a `:poll` timer armed for an entry
  (carries `:rf.frame/id`, `:resource/key`, `:delay-ms`), the poll counterpart
  of `:rf.resource/stale-scheduled` / `gc-scheduled` emitted by
  `re-frame.resources.timers/schedule-timers-handler` (`timers.cljc:260-266`).

A poll **tick** that starts work needs no new op — it rides the ordinary
`:rf.resource/work-started` / `:rf.resource/fetch-started` traces with cause
`:poll`, exactly as a focus refetch rides them with cause `:focus`. A tick that
coalesces (in-flight skip) or pauses (no owner / hidden) emits a
`:rf.resource/refetch-decision`-style summary so a "why didn't my poll fire"
question is answerable in the trace stream. The Xray resource-instance table
gains a **poll** column (interval, next-tick estimate, paused-reason).

## Rationale

The design is deliberately the *smallest* primitive that reaches parity, because
the two substrates it needs already exist and are battle-tested:

1. **Scheduling** is the advisory host-timer side-table
   (`re-frame.resources.timers`). It already has: a transient host cache keyed
   by `[frame-id resource-key kind]`, never on the SSR/restore/epoch wire
   (`timers.cljc:107-118`); cancel-then-arm reschedule (`timers.cljc:177`); a
   re-checking internal event on fire so the timer is advisory and a hidden tab
   delaying it is harmless (`timers.cljc:152-164`); SSR no-op via the carried
   `:server?` flag (`timers.cljc:256`); and frame-destroy teardown via the
   single `:resources/on-frame-destroyed!` hook (`timers.cljc:296-306`). Adding a
   `:poll` kind beside `:stale` / `:gc` is a few lines, not a new subsystem.

2. **The tick** is the focus/reconnect scan-and-refetch core
   (`re-frame.resources.events/revalidate-handler`, `events.cljc:547-589`). It
   already: selects active-owner entries, skips entries with live in-flight work
   (`entry-revalidation-in-flight?`, the coalescing gate), dispatches background
   `:rf.resource/refetch` with a **cause** (never an owner), and bases its
   freshness decision on the causal `:rf/time-ms` not an ambient clock
   (the EP-0010 discipline). A poll is the same operation triggered
   by a timer instead of a host focus/online event.

Polling therefore introduces **no new race surface, no new transport, no new
work-ledger writer, and no new teardown path** — it is a composition of two
landed mechanisms. That is the whole argument for it being a small slice rather
than its own EP-sized contract.

The owner-driven choice (vs component-driven) is forced by the re-frame2 model,
not a preference: views are passive reads ([Spec 016 §Active owners and
causes](../../spec/016-Resources.md#active-owners-and-causes) — "Views stay
passive"), so a *view* cannot be the thing that drives a fetch; the *owner lease*
already is the framework's "is this entry live and worth keeping fresh" concept,
and the spec already reserves the polling decision to it (line 270). Tying
polling to the lease means polling composes with routes, machines, SSR, and
restore for free — a route that owns a resource polls it while the route is
active and stops on route leave, with no extra wiring.

## Backwards Compatibility

Purely additive. `:poll-interval-ms` is a new optional resource-spec key;
resources that omit it behave exactly as today (no poll timer is armed — the
non-positive-delay disarm path). No existing API changes shape. The one reserved
key the spec lists as "rejected/unused in v1" is spelled `:poll-ms`
(`spec/016-Resources.md` line 663); this EP proposes the clearer
`:poll-interval-ms` (aligning with RTK's `pollingInterval` and reading as an
interval, not a duration-until) and would update that reserved-key note (Open
Question 7 confirms the spelling).

## Bead Plan / Reference Implementation

A proposed slice sequence once accepted (not a single PR; the EP-0003 slice
discipline):

1. **Spec 016 §Polling amendment** — the §Specification text above graduates
   into a new §Polling section beside §Stale and GC scheduling; `reg-resource`
   spec-key docs gain `:poll-interval-ms`; §Deferred slices crosses polling off;
   the reserved-key note updates; §Xray gains `:rf.resource/poll-scheduled`.
   (Hot-zone file — sequential, Mike rules acceptance first.)
2. **Timer substrate** — add the `:poll` kind + `:rf.resource.internal/poll-fired`
   re-check event to `re-frame.resources.timers`; extend
   `schedule-timers-handler` to arm the poll timer from the entry's effective
   interval; emit `:rf.resource/poll-scheduled`.
3. **Tick handler** — add `poll-fired-handler` to `re-frame.resources.events`,
   reusing the `entry-revalidation-in-flight?` coalescing gate and the
   `:rf.resource/refetch` cause-`:poll` dispatch; pause on no-owner / hidden;
   re-arm after settle.
4. **Pause/resume + visibility** — gate poll ticks on `document.visibilityState`
   (reuse the `visibilitychange` listener wiring already in
   `re-frame.resources.revalidate-listeners`); cancel poll timers on last-owner
   release and on entry removal.
5. **Registry validation** — validate `:poll-interval-ms` in
   `validate-resource-spec!`; trace it on `:rf.resource/registered`.
6. **Per-use override** (if ruled in for v1, Open Question 6) — resolve route /
   ensure / refetch payload `:poll-interval-ms`.
7. **Tests** — start/stop/pause/resume, owner release stops polling, hidden-tab
   pause, in-flight coalescing (no overlap on a slow endpoint), focus/reconnect
   coexistence (no double-fetch), stale-reply suppression of a late poll reply,
   invalidation resets the poll clock, background poll failure keeps polling.
   (CLJS unit tests per the standing policy; a candidate test file
   `resources_polling_cljs_test.cljc` beside the existing
   `resources_revalidation_cljs_test.cljc`.)
8. **Tooling/docs** — Xray poll column; guide section "polling vs invalidation
   vs focus/reconnect."

## Open Issues

These were the genuine design decisions Mike ruled at graduation
(`accepted → final`). Each carries its recommendation, **all adopted as the
shipped cut** (see the header blockquote and the [§Recommendation](#recommendation));
they are kept verbatim as the record of what was ruled.

1. **Owner ergonomics for a "just polling" read with no natural owner.** A
   dashboard widget that polls but is not route-owned or machine-owned needs
   *some* owner to keep the poll alive (an owner-free entry does not poll, by
   design). The lease mechanism exists (`[:lease …]` app-minted owners with a
   matching `:rf.resource/release-owner` path), but it is per-feature wiring.
   Should this EP add a small ergonomic — e.g. an adapter-level
   "lease-while-mounted" helper that mints a lease on mount and releases on
   unmount — so a view can declaratively own a polled resource for its lifetime?
   **Recommendation:** keep the v1 primitive owner-driven with the existing
   `[:lease …]` path, and file a *separate* adapter-ergonomics bead for a
   `use-resource-lease` / `with-resource-lease` mount-lifecycle helper (it is an
   adapter concern, not a runtime-contract concern, and it is the only place the
   component-observer model legitimately re-enters). This keeps EP-0020's runtime
   contract clean while acknowledging the real ergonomic gap.

   **DELIVERED (rf2-cxozh4).** The mount-lifecycle helper shipped in the adapter
   layer, exactly as recommended — the runtime contract is untouched. Two
   substrate-shaped surfaces over the ONE shared implementation
   (`re-frame.adapter.resource-lease`, core/CLJS): `use-resource-lease`
   (`re-frame.adapter.uix` / `re-frame.adapter.helix` — a React hook) and
   `with-resource-lease` (`re-frame.adapter.reagent` — a Form-3 component). Each
   mints a unique `[:lease …]` owner on mount (dispatching `:rf.resource/ensure`)
   and releases it on unmount (`:rf.resource/release-owner`), carrying the
   surrounding `frame-provider`'s frame. It is DATA-only — it `:require`s no
   resources artefact (the two events are dispatched by keyword id), so it is
   inert in an app without `day8/re-frame2-resources`; under SSR the lifecycle
   never fires (a natural no-op); and the per-instance lease token is idempotent
   under a StrictMode / hot-reload re-mount.

2. **Hidden-tab polling opt-in: ship in v1 or reserve?** TanStack
   (`refetchIntervalInBackground`), SWR (`refreshWhenHidden`), and RTK
   (`skipPollingIfUnfocused`) all expose a hidden-tab toggle. Default-pause-when-hidden
   is unambiguous; the question is whether the *opt-in to keep polling hidden*
   (`:poll-when-hidden? true`) ships now or is reserved.
   **Recommendation:** ship the default-pause behaviour in v1 and **reserve**
   `:poll-when-hidden?` for the first consumer that needs a true background
   monitor (a status board on an always-on display). Defaulting safe and
   deferring the escape hatch matches the project's "name it, defer the contract
   until a consumer" discipline.

3. **Is a poll tick unconditional, or stale-gated?** Two coherent semantics:
   (a) **unconditional** — every interval, refetch an active-owned entry
   regardless of `:stale?` (the consumer asked for "re-read every N ms"); or
   (b) **stale-gated** — a poll only refetches if the entry is *also* stale at
   tick time (poll is "wake up and check staleness," letting `:stale-after-ms`
   set the real cadence). TanStack/SWR/RTK are all effectively (a) — the interval
   *is* the cadence.
   **Recommendation:** (a) unconditional, because it matches every prior-art tool
   and is what "poll interval" means to consumers; `:stale-after-ms` remains the
   *separate* knob for "don't refetch on focus/route-entry unless older than X."
   Document the composition explicitly. This is the single most load-bearing
   semantic ruling in the EP.

4. **Data-derived dynamic interval / stop predicate (TanStack's
   `refetchInterval: (data) => …`).** Polling until a long job completes (then
   stopping) is a real demand. It needs the tick to evaluate a function/predicate
   against the latest decoded data.
   **Recommendation:** **Non-Goal for v1, reserved.** When a consumer needs it,
   shape it as an EP-0014 declared derivation (inputs → next-interval-or-stop),
   not an anonymous closure over the entry, so it stays inspectable. A static
   integer interval covers the dashboard/notification/presence majority.

5. **Repeated poll-failure back-off.** A poll against a down endpoint will fire
   every interval forever (each a background `:refresh-error`). Should the EP
   define an exponential/capped back-off on consecutive poll failures, resetting
   on success?
   **Recommendation:** **reserve, do not ship in v1.** Transport retry is the
   transport adapter's concern (Spec 016 line 359 — "Transport retry belongs to
   the transport adapter"); a poll that keeps firing on a flaky endpoint is
   *correct* (it is a monitor). If a consumer needs back-off, it belongs with the
   managed-HTTP retry policy, not the poll timer. Document the interaction and
   leave back-off out of the v1 poll primitive.

6. **Does the per-use route/ensure override ship in v1, or only resource-level
   `:poll-interval-ms`?** The route override ("this route wants a tighter poll
   than the resource declares") is ergonomic but adds resolution surface.
   **Recommendation:** ship **resource-level only** in v1; reserve the per-use
   override for a follow-up if a consumer proves the resource-level interval is
   too coarse. The resource-level declaration is the 90% case and keeps the
   ensure/route resolution path unchanged.

7. **Spelling: `:poll-interval-ms` vs the reserved `:poll-ms`.** Spec 016 line
   663 reserves `:poll-ms` as a rejected/unused key. This EP proposes
   `:poll-interval-ms` (reads as an interval, aligns with RTK's
   `pollingInterval`, parallels `:stale-after-ms` / `:gc-after-ms` as a `*-ms`
   policy key).
   **Recommendation:** adopt `:poll-interval-ms`; update the reserved-key note.
   (`:poll-after-ms` was considered and rejected — "after" implies a one-shot
   delay like stale/GC, but polling is recurring.)

8. **Does polling need its own `:cause` taxonomy entry, or is `:poll` enough?**
   The cause list in Spec 016 (line 311-319) enumerates `:focus`, `:reconnect`,
   `:ssr-preload`, `:hydration`, etc. `:poll` is the natural sibling.
   **Recommendation:** add the single cause keyword `:poll` to the enumerated
   causes; no further taxonomy needed. (Trivial, listed only for completeness so
   the cause enum stays a closed documented set.)

## Resolved Decisions

Ruled by Mike at graduation (`accepted → final`, 2026-06-17),
adopting the recommended cut. One row per Open Question. These rows are normative;
the [§Open Issues](#open-issues) above carry the full rationale, and the
§Specification body has been reconciled to them.

| # | Decision | Resolution |
|---|----------|-----------|
| **R1** | Owner ergonomics for a "just polling" read with no natural owner? (Open Question 1) | Keep the v1 primitive **owner-driven** with the existing `[:lease …]` path; **file a separate adapter-ergonomics bead** for a `use-resource-lease` / `with-resource-lease` mount-lifecycle helper. The runtime contract stays clean; the component-observer model re-enters only at the adapter layer. |
| **R2** | Hidden-tab polling opt-in — ship in v1 or reserve? (Open Question 2) | **Ship default-pause-when-hidden in v1; reserve `:poll-when-hidden?`** for the first true-background-monitor consumer. Default safe, defer the escape hatch. |
| **R3** | Is a poll tick unconditional or stale-gated? (Open Question 3) | **(a) unconditional** — every interval refetches an active-owned entry regardless of `:stale?` (matches all prior-art tools; the interval *is* the cadence). `:stale-after-ms` stays the separate "don't refetch on focus/route-entry unless older than X" knob. The single most load-bearing semantic ruling. |
| **R4** | Data-derived dynamic interval / stop predicate? (Open Question 4) | **Non-Goal for v1, reserved.** When a consumer needs it, shape it as an EP-0014 declared derivation (inputs → next-interval-or-stop), not an anonymous closure. A static integer interval covers the dashboard/notification/presence majority. |
| **R5** | Repeated poll-failure back-off? (Open Question 5) | **Reserve, do not ship in v1.** A poll that keeps firing on a flaky endpoint is correct (it is a monitor); transport retry belongs to the transport adapter / managed-HTTP retry policy, not the poll timer. |
| **R6** | Per-use route/ensure override, or resource-level only? (Open Question 6) | **Resource-level `:poll-interval-ms` only in v1;** reserve the per-use override for a follow-up if a consumer proves the resource-level interval too coarse. Keeps the ensure/route resolution path unchanged. |
| **R7** | Spelling: `:poll-interval-ms` vs reserved `:poll-ms`? (Open Question 7) | Adopt **`:poll-interval-ms`** (reads as an interval, aligns with RTK's `pollingInterval`, parallels `:stale-after-ms` / `:gc-after-ms`); update the reserved-key note. |
| **R8** | Does polling need its own `:cause` entry? (Open Question 8) | Add the single cause keyword **`:poll`** to the enumerated causes; no further taxonomy. |

## Recommendation

Accept this EP as a focused, additive Spec 016 amendment that completes the
"polling/interval revalidation" deferred slice. The design is small by
construction: it reuses the landed advisory-timer side-table for scheduling and
the landed focus/reconnect scan-and-refetch core for the tick, adds one resource
policy key (`:poll-interval-ms`), one timer kind (`:poll`), one cause (`:poll`),
and one trace op (`:rf.resource/poll-scheduled`). The load-bearing divergence
from TanStack/SWR/RTK — **owner-driven, not component-driven** — is forced by
re-frame2's passive-view model and is already reserved to the active-owner lease
by the spec.

The recommended cut:

- ship resource-level `:poll-interval-ms` (Open Question 6 → resource-level
  only);
- unconditional active-owner tick (Open Question 3 → option a);
- default-pause-when-hidden, reserve `:poll-when-hidden?` (Open Question 2);
- reserve data-derived intervals (Open Question 4) and poll-failure back-off
  (Open Question 5) to future consumer-driven work;
- file the adapter `use-resource-lease` ergonomics as a separate bead (Open
  Question 1);
- adopt the `:poll-interval-ms` spelling (Open Question 7) and the `:poll` cause
  (Open Question 8).

That reaches the parity demand the epic identified without expanding the
resource runtime's contract surface beyond a single freshness knob.
