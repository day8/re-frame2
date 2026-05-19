# 019-Cross-Cutting-Insight

The vision-of-record for how Causa renders re-frame2's **cross-cutting
runtime concerns** — SSR, Machines, Routes, Managed-Effects — without
fragmenting the chrome. Distils [the 2026-05-18 cross-cutting design
findings](#findings-anchor) into a normative reading: **5 visual idioms ×
4 areas = a 20-cell matrix of features**, each anchored on a concrete
bug-class.

This doc is the **complement** to [`000-Vision.md`](000-Vision.md) and
[`018-Event-Spine.md`](018-Event-Spine.md): Vision states the claim;
Event-Spine specifies the chrome; this doc shows how the chrome accommodates
the cross-cutting work without growing tabs.

---

## §0 The strategic shape (read this first)

Causa today nails the **synchronous, single-frame, single-cascade**
debugging story. The five canonical questions ([`000-Vision.md`](000-Vision.md)
§The five canonical questions) answer themselves on first paint.

What's under-developed is everything **across time**, **across boundaries**,
**across networks** — exactly the territory where SSR · Machines · Routes ·
Managed-Effects live. The four surfaces share three structural properties
that make them hard to debug from code alone:

1. **They unfold over wall-clock time.** A machine `:after` fires 30s after
   entry; a route's `:on-match` cascade lands 200ms after click; a streaming
   SSR boundary resolves seven chunks deep; an `:rf.http/managed` retry hits
   its third attempt after exponential backoff. Stack traces are
   insufficient — the wall clock IS the bug surface.
2. **They span boundaries.** Server → client (SSR); browser → backend
   (managed HTTP); parent machine → spawned child → in-flight HTTP; route
   URL → app-db slice → on-match cascade. Each boundary is a place where
   assumptions can quietly diverge.
3. **They have rich, structured failure taxonomies the runtime already
   emits.** `:rf.ssr/*`, `:rf.machine.*`, `:rf.route.*`, `:rf.http/*` — the
   framework spec is unusually careful about naming failure categories.
   Causa today funnels most of them through one generic Issues row; that
   wastes signal the framework paid to capture.

**The strategic move:** Causa's existing 6-tab chrome does NOT need a 7th,
8th, 9th tab. It needs **deep specialised renderings inside the existing
tabs**, surfaced through five reusable visual idioms:

- **Wall-clock timelines** — rings (timer countdown), waterfalls (retry
  backoff), swimlanes (nav-token races).
- **Boundary diffs** — server vs client (hydration), request vs response
  (wire-boundary), before vs after (app-db diff), parent vs child
  (cascade-divergence).
- **Cascade trees** — cancellation cascades, `:on-match` event chains,
  exit/entry cascade animations, hydration mismatch bisectors.
- **Schema explanations** — Malli explanations rendered inline, not buried
  in trace.
- **Lifecycle accounting** — per-frame teardown audit, per-instance trace,
  stale-suppression tally, recently-destroyed buffer.

Five idioms × four areas = **20 features**, sequenced into PR-sized work
[§6 Sequencing](#6-sequencing).

---

## §1 The five visual idioms

Each idiom has a uniform rendering vocabulary; the chrome should look
consistent across all four areas. An author who learns "the timer ring"
on a machine `:after` immediately understands "the retry waterfall" on an
HTTP `:rf.http/managed` because both speak the same wall-clock language.

### §1.1 Wall-clock timelines

Time IS the bug surface for `:after` timers, HTTP retry backoffs, route
nav-token races, WebSocket reconnects, streaming SSR boundary resolutions.

**Sub-idioms:**

- **Countdown ring** — a thin circle around a state node (or a wire
  endpoint) with an animated arc representing time-elapsed/total. Starts
  at 12 o'clock, rotates clockwise to fill. Live mode animates; retro
  (scrubber-driven) is static at the elapsed-fraction at the focused
  cascade's timestamp.
- **Waterfall** — vertical stack of timestamped rows; each row is an
  attempt / phase / chunk with its own duration band; total elapsed shown
  at the foot.
- **Swimlane** — horizontal bars on a shared time axis; each lane is one
  in-flight thing (nav-token, WebSocket connection-epoch, machine `:after`
  epoch). Suppressed completions render with a strike-through; the
  carried-vs-current token is visible.

**Reduced-motion handling:** the live arc animation flattens to a static
amber arc + numeric percentage. The waterfall becomes a static stack with
duration text. The swimlane stays — its content is structural.

### §1.2 Boundary diffs

A boundary is a place where two sides "should agree" and sometimes don't:

- **Server vs client** (hydration; SSR sub-recompute attribution).
- **Request vs response** (wire-boundary diff for managed effects).
- **Before vs after** (the existing app-db diff; extends to cross-frame).
- **Parent vs child** (cancellation cascade; shift-click multi-instance
  divergence).

**Uniform rendering:** two columns side-by-side, root flagged, walk to the
first divergent node, highlight with `⚠`, surface the upstream value that
each side observed. The user reads "server saw `:user/locale = nil`;
client saw `:user/locale = :en-US`" in one glance.

### §1.3 Cascade trees

A cascade is "one decision and its consequences laid out as a tree." Four
cases:

- **Cancellation cascade** — parent machine exits → destroy fx →
  in-flight HTTP aborts → child machine destroyed.
- **`:on-match` event chain** — route navigation → declared loader events
  drain (in order or in parallel) → on-error fires if any error.
- **Exit/entry cascade** — leaving a hierarchical state walks the exit
  chain up to the LCA; entering walks the entry chain down.
- **Hydration mismatch bisector** — depth-first walk over canonical EDN
  serialisation; first-divergent-node highlighted; side-by-side render
  for that node.

**Uniform rendering:** vertical waterfall, indentation = depth in the
cascade, each row carries source-coord + trace event id + timestamp. The
**root row** is the decision; the **leaf rows** are the consequences. The
total fan-out + duration prints at the foot.

### §1.4 Schema explanations

Malli rejections live in the trace today as `:rf.error/schema-validation-failure`
events with `:explain` payloads. The user shouldn't have to walk the trace
to find them. Two surfaces:

- **Inline Malli explanation** in the Issues tab — the human-readable
  variant of `:explain`, with the offending path highlighted, the schema
  source-coord linked.
- **Per-violation drilldown** — click a schema-violation row → opens the
  Event tab for the cascade that produced it; the violating handler's
  `reg-event-*` registration is linked.

This applies to: app-db shape violations, event coercion failures,
fx-args validation failures, route param/query validation failures,
schema-digest mismatches at hydration time.

### §1.5 Lifecycle accounting

Per-frame teardown, per-instance trace, stale-suppression tally,
recently-destroyed buffer. These are the "did anything leak / get stuck
/ silently discard?" surfaces:

- **Per-frame teardown auditor** (dev-only summary; deep auditor in CI) —
  at frame destroy, verify each slot was released; surface a one-line
  "frame destroyed cleanly" or a leak warning.
- **Per-instance trace strip** — for a focused machine instance, the last
  5 trace events filtered to just that instance.
- **Stale-suppression tally** — one chip per surface (timers, nav-tokens,
  WebSocket epochs, sub-warmups); count of stale events suppressed this
  session, click → grouped list.
- **Recently-destroyed buffer** — destroyed machine instances persist 30s
  in a recently-destroyed sub-list so the arc can still be inspected after
  the instance is gone.

---

## §2 The four areas

This section enumerates the bug classes Causa addresses in each area. Each
bug-class follows the **bug → example → insight → affordance** structure
from [`000-Vision.md`](000-Vision.md) §Bug-driven, not feature-driven.

### §2.1 Machines

(Full per-feature spec in [`003-Machine-Inspector.md`](003-Machine-Inspector.md);
this section catalogues the bug classes the Machine surfaces address.)

#### M.1 — "My machine is stuck. Why won't it transition?"

**Bug class:** Guard rejection (silent). An event fires, the chosen `:on`
entry's guard returns `false`, the snapshot doesn't move. The `:rf.machine.transition/suppressed`
trace is the only signal, and it's buried in the firehose.

**Example bug:** You dispatched `:auth/cancel` on machine `:checkout` in
state `:authing`, expected a transition to `:idle`, nothing happened. The
event landed; the guard `:no-pending-payment?` returned `false` because
`:data.pending-payment` was `4232`.

**Insight Causa provides:** The transition's edge in the chart **flashes red
400ms** with a tooltip: `:auth/cancel-attempted from :authing → :idle |
guard :no-pending-payment? returned FALSE (data: {:pending-payment 4232})`.
In the metadata rail, a "Recent guard rejections" section listing the
rejected transitions, each click-to-expand showing the `:data` snapshot at
evaluation time and the guard's source-coord.

**Affordance:** Machines tab — guard-verdict overlay (M-C1). Three clicks
total from "huh, nothing happened" to "ah, my guard is wrong."

#### M.2 — "My `:after` timer fired but nothing happened."

**Bug class:** Stale `:after` timer (epoch advanced; timer scheduled in a
prior visit) OR guard re-evaluation on `:after` resolution OR
`:rf.machine.timer/cancelled-on-resolution` paired with new schedule.

**Example bug:** You entered `:loading` with `:after 5000ms → :timeout`.
30 seconds passed. The snapshot is still `:loading`. The Trace tab shows
both `:rf.machine.timer/scheduled` (epoch 12) and
`:rf.machine.timer/stale-after` (epoch 13) — the state re-entered between
the schedule and the fire.

**Insight Causa provides:** On each state with a live `:after` timer, the
node has a **countdown ring** with an animated arc. Stale timers render
dashed/grey with a tooltip "this timer was scheduled in a prior visit and
is stale." Cancelled-on-resolution timers show the old ring fading out
(200ms), new ring fading in. Click any ring → timer detail popover with
`:scheduled-at`, `:delay`, `:epoch`, `:source` (`:literal` / `:sub` /
`:timeout-config` / `:fn`).

**Affordance:** Machines tab — `:after` countdown rings + scrubber-aware
retro-replay (M-C2). Time IS the bug surface; the ring makes wall clock
visible.

#### M.3 — "The cancellation cascade fired and I don't know what happened."

**Bug class:** Parent state exits; child's `:invoke` destroyed; child had
N in-flight HTTP requests; each aborts. The author sees a flurry of
`:rf.http/aborted-on-actor-destroy` traces in the firehose and one
`:rf.machine.lifecycle/destroyed`. They cannot reconstruct the cascade.

**Example bug:** You clicked Cancel on a checkout flow. The Trace tab shows
4 abort traces + 1 destroyed trace, scattered through 200 unrelated rows.
You can't tell which abort came from the cancel vs which were independent.

**Insight Causa provides:** A dedicated **"Cancellation cascade" detail
panel** that appears inline in the Machines tab when the focused cascade
triggered a destroy:

```
┌─ Cancellation cascade · cascade #449 ──────────────────────────────────┐
│  16:42:14.103   :checkout/main exits :processing → :cancelled           │
│         ├── :rf.machine/destroy → :http/post#347                        │
│         │     ├── :rf.http/aborted-on-actor-destroy  POST /finalize     │
│         │     ├── :rf.http/aborted-on-actor-destroy  GET /cart/lock     │
│         │     └── :rf.http/aborted-on-actor-destroy  POST /audit/log    │
│         └── :rf.machine.lifecycle/destroyed → :http/post#347            │
│  Total: 1 child destroyed · 3 requests aborted · 8ms elapsed            │
└─────────────────────────────────────────────────────────────────────────┘
```

What was "a flurry of confusing trace lines" becomes "one decision and its
consequences, laid out vertically." Cancellation is no longer a mystery;
it's a diagram.

**Affordance:** Machines tab — cancellation cascade visualiser (M-C3).
This is also a template for the SSR cancellation case — when an SSR
request times out, the same cascade-waterfall idiom shows what cleanup
ran (response slot dropped, request slot dropped, machine snapshots
discarded).

#### M.4 — "I'm in `:invoke-all` and it never joins."

**Bug class:** Four children spawned; join condition is `:all`. Two
completed; two never did. The user wants per-child status, what each is
doing right now, what the join-state map looks like (`:done #{:cfg :flag}
:failed #{} :resolved? false`), and whether `:on-any-failed` is wired.

**Example bug:** You entered `:hydrating` which declares `:invoke-all
{:children {:cfg ... :flag ... :user ... :dash ...} :join :all}`. Two
children completed in <200ms; two are still "running" 2 seconds in. The
machine hasn't advanced. The `[:rf/spawned <parent-id> <invoke-id>]` slot
in app-db carries the structured state but you don't read it directly.

**Insight Causa provides:** A dedicated **join card** in the metadata rail:

```
┌─ :invoke-all  ·  invoke-id [:hydrating :invoke-all] ─────────────┐
│ Join condition: :all                                              │
│ Resolved: ✗   (waiting for 2 of 4)                                │
│  ✓ :cfg     :load-config#1         done @ +124ms                  │
│  ✓ :flag    :load-feature-flags#2  done @ +89ms                   │
│  ⧖ :user    :load-user-profile#3   running 2.3s                   │
│  ⧖ :dash    :load-dashboards#4     running 2.4s                   │
│  :on-all-complete  → [:assets-loaded]                             │
│  :on-any-failed    → [:asset-load-failed]                         │
│  :cancel-on-decision?  true                                        │
└────────────────────────────────────────────────────────────────────┘
```

Click any running child → pivots to that child's machine. Click any
done/failed → opens the per-child completion event.

**Affordance:** Machines tab — `:invoke-all` join inspector (M-C4).

#### M.5 — "Instance #c-047 stuck in `:authing` for 30s; what guard is blocking it?"

**Bug class:** Per-instance debugging at scale. Mode C (4+ instances) ships
with cluster-by-state badges + shift-click divergence, but the per-instance
"what's stuck here" trace has no direct affordance.

**Insight Causa provides:** Click an instance row in the Mode C table →
opens a per-instance trace strip below the table showing the last 5 trace
events for THIS instance only. The "32s in state" auto-callout flags
suspiciously-long state occupancy. Filters out other instances' chatter.

**Affordance:** Machines tab — per-instance "why am I stuck" trace strip
(M-C5).

#### M.6 — Other machine bug classes (catalogued; see 003)

- **M.7** Hierarchical state cascade — exit-chain/entry-chain animation
  along the LCA (M-C6).
- **M.8** Microstep loop visualiser — when `:always` chains hit
  bounded-depth (M-C7).
- **M.9** Path-walked transition explainer — for hierarchical
  deepest-wins resolution (M-C8).
- **M.10** Spawn-ancestry tree in metadata rail (M-C9).
- **M.11** Snapshot diff across transitions — per-action attribution of
  `:data` mutations (M-C10).

### §2.2 Routes

(Full per-feature spec ships in [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md)
§Route content + [`018-Event-Spine.md`](018-Event-Spine.md) §Nav-token
popover; this section catalogues the bug classes.)

#### R.1 — "Stale data clobbered my new route."

**Bug class:** Stale nav-token race. User navigates A → B before A
finishes loading; A's late result lands and overwrites B's state. Spec
012's `:nav-token` epoch is the mechanism — but the user can't see it.

**Example bug:** You navigated from `:route/cart` to `:route/checkout` in
quick succession. The `:cart/load-items` loader from `:route/cart` was
still in-flight when you arrived at `:route/checkout`. Its result landed
800ms later, overwriting the checkout app-db state with cart data. You
see "stale clobber" in your UI but can't tell which nav was the late one.

**Insight Causa provides:** The **nav-token timeline** — a horizontal
swimlane visualisation pinned at the top of the Trace tab (or as the `r`-key
popover from any tab):

```
nav-1 │█████░░░░░░░░░░░░│░░░░ → suppressed (carried nav-1; current nav-2)
      │  :on-match drain
nav-2 │      ██████████████████████ → completed @ +812ms
      │       :on-match drain
nav-3 │                       ████│ in flight (185ms elapsed)
      │                        :on-match drain
```

Each navigation = one horizontal bar. Failed/stale completions get a
strike-through with the carried-vs-current token visible. Currently-in-flight
navigations have an animated leading edge.

Click any bar → spine seeks to that nav-token's allocation cascade.

**Affordance:** Nav-token timeline popover (R-C3); cross-cuts the Trace
tab and the Issues tab.

#### R.2 — "My route's `:on-match` events didn't fire."

**Bug class:** URL changed; the per-route loaders never dispatched.
Possibilities: route's `:on-match` is `nil`; nav-token was already
advanced (race); event handler threw silently; `:transition` is stuck on
`:error` from a prior nav.

**Insight Causa provides:** When the focused cascade is a routing cascade,
the Event tab's "fx handlers that ran" block adds a dedicated **`:on-match`
dispatch chain** sub-section showing the loader events, their drain
durations, and any `:on-error` consequences:

```
:on-match dispatch chain (3 events, drained 124ms):
  ✓ [:cart/load-items]       drained 87ms     src/cart/events.cljs:42 ↗
  ✓ [:user/load-prefs]       drained 31ms     src/user/events.cljs:18 ↗
  ✗ [:cart/load-promotions]  errored 6ms      src/cart/events.cljs:65 ↗
       → exception :promotions-service-unavailable
       → :on-error fired: [:route/cart-load-failed]
```

**Affordance:** Event tab — `:on-match` chain inline (R-C4).

#### R.3 — "The route matched but the wrong one."

**Bug class:** Two routes overlap; the 6-rule ranking cascade picks one;
the user wanted the other.

**Insight Causa provides:** A **match-rank explainer** tooltip on hover
over any route id:

```
Match rank for :route/cart.item-detail vs URL /cart/items/abc:
  Rule 1 (static count):     2 (this) vs 1 (:route/cart.* wildcard) → THIS WINS
  Rule 2 (length):           3 vs 2                                  → n/a
  ...
```

**Affordance:** App-db tab → `:rf/route` slice → rank-explainer (R-C5).

#### R.4 — Other route bug classes (catalogued)

- **R.5** `:rf.route/not-found` reason ambiguity — Malli explanation
  inline (R-C6).
- **R.6** `:can-leave` rejection with no UI — pending-navigation card
  (R-C7).
- **R.7** URL vs slice drift watchdog (R-C8 — ambitious).
- **R.8** Route-chain visualiser (`:parent` → child chain) (R-C9).
- **R.9** Open-redirect security advisory (R-C10).
- **R.10** Routing badge `🧭` on Event-list rows (R-C1).
- **R.11** `:rf/route` slice always-visible in App-db tab (R-C2).

### §2.3 SSR / Hydration

(Full per-feature spec in [`006-Hydration-Debugger.md`](006-Hydration-Debugger.md);
this section catalogues the bug classes.)

#### S.1 — "Hydration mismatch popped up; where in the tree?"

**Bug class:** Body hydration mismatch. Server-rendered tree and client
first-render tree disagree at a node. The `:rf.ssr/hydration-mismatch`
trace fires with `:first-diff-path`; the panel needs to render the
divergent node side-by-side with **sub-attribution** (which sub returned
which value on which side, and why).

**Example bug:** Server rendered `<span class="user-name">Guest</span>`;
client rendered `<span class="user-name">Alice</span>`. The console error
says "hydration mismatch at `[:div.app :header :span.user-name]`." You
don't know why — was the server missing the session? Was the sub computing
nil? Was the `:user/display-name` sub watching the wrong slice?

**Insight Causa provides:** The **hydration mismatch bisector** — walks
the canonical EDN serialisation depth-first, finds the first divergence,
surfaces both trees + the SUBs that produced them + the upstream `app-db`
slices both sides observed + a heuristic "likely cause" line:

```
Bisector:
  ✓ root <div id="app">
  ✓ <div.layout>
  ✓ <header>
  ✗ FIRST DIVERGENCE: <span.user-name>

Side-by-side:
┌─ Server ─────────────────────┬─ Client ──────────────────────┐
│  [:span.user-name "Guest"]    │  [:span.user-name "Alice"]    │
│  Subbed to: :user/display-name│  Subbed to: :user/display-name│
│  Server sub returned: "Guest" │  Client sub returned: "Alice" │
│  Server :user value: nil      │  Client :user value: {:id 1 …}│
│  Server :auth/session: nil    │  Client :auth state: :authed  │
└────────────────────────────────┴────────────────────────────────┘

Likely cause: server-side session cofx not injected (request cookie
was present but `:auth/server-init` event was not in `:on-create`)
↗ Open server-side init: src/server/init.cljs:42
```

The "Likely cause" line is heuristic — Causa suggests "session not
injected" when the divergent sub depends on `:auth/*` and the server's
`:auth/*` slice is empty.

**Affordance:** Issues tab — hydration mismatch bisector with
sub-attribution (S-C2). The hero SSR feature.

#### S.2 — "Server rendered a 500 page; what was the internal exception?"

**Bug class:** The public-error projection sanitises the internal trace.
In dev mode, `:dev-error-detail? true` includes `:details`. The author
needs the internal trace alongside the public projection to see the
security boundary.

**Insight Causa provides:** A dedicated **server error projection** detail
panel — shows the internal trace + the projector's source-coord + the
public projection map + the rendered client response. The author sees
exactly what crossed the wire and what did not.

**Affordance:** Issues tab / Event tab — server error projection trace
(S-C5).

#### S.3 — "Streaming SSR shipped a chunk that hydrated incorrectly."

**Bug class:** Per-subtree delta merged into client app-db but the view
tree didn't update as expected.

**Insight Causa provides:** A **streaming SSR boundary timeline** in the
Trace tab — vertical waterfall showing shell flush time, per-boundary
fallback emit times, per-boundary resolution times (or failures), final
payload chunk status. Each boundary that failed shows the throwable + the
fallback retention status.

**Affordance:** Trace tab — streaming SSR boundary waterfall (F-C10 /
S-C10).

#### S.4 — Other SSR bug classes (catalogued)

- **S.5** Head/meta hydration mismatch — head-model inspector (S-C6).
- **S.6** Hydration payload policy verdict — payload-keys vs whole-app-db
  (S-C4).
- **S.7** `:rf/hydrate` over-replaced (replace-app-db policy clobbered
  client-only state) (S-C3).
- **S.8** `:after`-server-side anomaly badge (S-C7).
- **S.9** Per-request frame teardown auditor — dev-mode summary; deep
  audit in CI (S-C8).
- **S.10** Trusted-shell opt visualiser (S-C9).
- **S.11** Schema digest mismatch — per-schema diff (S-C5b).
- **S.12** SSR ribbon indicator — `📄 SSR @ <hash>` (S-C1).
- **S.13** Side-by-side SSR replay — the post-v1 dream (S-C11).

### §2.4 Managed Effects

(`spec/Managed-Effects.md` names the eight-property contract that HTTP,
WebSocket, machine `:invoke`, SSR `:rf.server/*`, and flows all satisfy.
This section catalogues the bug classes Causa addresses uniformly across
all five surfaces.)

#### F.1 — "What happens when this effect crosses the wire?"

**Bug class:** Managed effects (HTTP, WS, invoke, server, flow) issue work
across a boundary; the author needs to see the entire wire interaction in
one picture — payload (post-elision) → wire (status / headers / timing
waterfall) → response → handler dispatched → app-db slice touched.

**Example bug:** Your `:order/submit` event issued `:rf.http/managed POST
/api/checkout/finalize`. The UI shows "success" but the order doesn't
appear in `:cart :orders`. You don't know: did the request go out? Did the
response come back? Was the `:on-success` handler invoked? Did the handler
write the right slice?

**Insight Causa provides:** The **wire-boundary diff** in the Event tab's
"fx handlers that ran" block:

```
┌─ :rf.http/managed  ·  POST /api/checkout/finalize ──────────────────────┐
│ Issued by:    cascade #347 :order/submit  ·  src/order/events.cljs:42 ↗ │
│ Request payload:                                                         │
│   { :cart-id "abc-123"                                                   │
│     :payment-method [● REDACTED · 92 bytes]                              │
│     :total-cents 4232 }                                                  │
│ ─── waterfall ──────────────────────────────────────────────             │
│   issued       16:42:14.103   ●                                          │
│   sent         16:42:14.105    ● 2ms                                     │
│   received     16:42:14.187     ●─────── 82ms                            │
│   decoded      16:42:14.189     ● 2ms                                    │
│   on-success   16:42:14.190     ● dispatched [:checkout/done abc-123]    │
│ ─────────────────────────────────────────────────────────────────────    │
│ Response:     200 OK { :order-id "ord-991" :receipt-url "..." }         │
│ Applied:                                                                 │
│   [:cart :status]   :submitting → :completed                             │
│   [:cart :order-id] (added: "ord-991")                                   │
│ Retry policy:  :retry {:on #{:rf.http/transport :rf.http/http-5xx}      │
│                        :max-attempts 3                                   │
│                        :backoff {:initial 100 :max 5000}}                │
│ Attempts (this call): 1 of 3                                             │
└────────────────────────────────────────────────────────────────────────────┘
```

The headline: **request → wire → response → handler → app-db slice
touched** in one compact panel. The waterfall makes timing visible. The
"Applied" section connects the http response to its downstream effect on
app-db. **Privacy markers are visible at every layer.**

For WebSocket: same shape but for a frame's send/recv, plus connection
state at issue time.
For machine `:invoke`: same shape but the "wire" is the spawn → run → reply.
For SSR `:rf.server/*`: same shape but the "wire" is the server response
accumulator's evolution.
For flows: same shape but inputs/outputs.

**Affordance:** Event tab — wire-boundary diff template (F-C2). The hero
managed-effects feature. The single biggest "I get re-frame2 now" win for
new authors. Implements the `Managed-Effects.md` eight-property contract
as visible UI.

#### F.2 — "What's the runtime currently busy doing?"

**Bug class:** Classic ops question — which HTTP requests are in flight,
which WebSocket connections are active, which machine actors are live,
which flows recomputed recently. The runtime maintains the registry; the
question deserves a one-glance answer.

**Insight Causa provides:** The **active managed-effects dashboard**:

```
┌─ Active managed effects (frame :app/main) ─────────────────────────────┐
│ HTTP (3 in-flight)                                                      │
│   ⧖ req-449  POST /api/checkout/finalize  4.2s elapsed  attempt 2/3    │
│   ⧖ req-512  GET  /api/cart/items         0.8s elapsed                  │
│   ⧖ req-518  GET  /api/user/preferences   0.3s elapsed                  │
│ WebSocket (1 connection)                                                │
│   ● ws://chat.example.com  :connected  ↑ 5 queued · ↓ 1 in-flight       │
│ Machine actors (2 live)                                                 │
│   ⧖ :auth/main#m-001  state :authing  for 32s                           │
│   ⧖ :http/post#h-018  state :sending  for 0.4s  (child of :auth/main)   │
│ SSR responses (0; client only)                                          │
│ Flows (12 registered, 1 recomputed in last 5s)                         │
│   ↺ :flow/cart-subtotal  recomputed @ 16:42:14.140                      │
└─────────────────────────────────────────────────────────────────────────┘
```

Surfacing: a ribbon chip `⧖ 3 HTTP · 1 WS · 2 actors` with click →
popover dashboard, OR a Cmd-K palette entry "Show active effects."

**Affordance:** Ribbon chip + popover dashboard (F-C4).
Erlang-Observer-for-managed-effects.

#### F.3 — "This request retried 5 times and finally failed. What was the backoff?"

**Bug class:** Silent retry chain. `:retry {:on ... :max-attempts 5 :backoff
{:initial 100 :max 5000}}` retried at growing intervals. Each attempt =
`:rf.http/handled` trace. User wants a timeline of attempts with timing.

**Insight Causa provides:** A **retry timeline** under the fx row:

```
:rf.http/managed  ·  POST /api/checkout/finalize  ·  retries
  Attempt 1   16:42:14.103   FAILED  :rf.http/transport  (net::ERR_CONNECTION_REFUSED)
  Attempt 2   16:42:14.205   FAILED  :rf.http/transport  (after 100ms backoff)
  Attempt 3   16:42:14.408   FAILED  :rf.http/transport  (after 200ms backoff)
  Attempt 4   16:42:14.812   SUCCESS 200 OK              (after 400ms backoff)
  Total elapsed: 712ms · Backoff sum: 700ms
```

**Affordance:** Event tab — retry timeline (F-C3).

#### F.4 — "A flow's `:output` threw and the cascade halted; what didn't run?"

**Bug class:** Flow cascade-halt. When `:rf.error/flow-eval-exception`
fires, the four-rule cascade-halt contract per Spec 013 means downstream
flows in topological order do NOT run. Authors rarely realise this.

**Insight Causa provides:** A high-priority **Issues entry** that lists
the subsequent flows that did NOT run:

```
⚠ EXCEPTION  cascade #449  FLOW CASCADE HALTED
  Flow:     :flow/cart-subtotal
  Inputs:   {:cart {:items [...] :discount-code "INVALID"}}
  Exception: ArithmeticException — Divide by zero
  src/cart/flows.cljs:42 ↗
  Recovery: prior values preserved; last-inputs NOT advanced; cascade halted
  → Subsequent flows in topological order DID NOT run:
       :flow/cart-total
       :flow/checkout-readiness
```

The "Subsequent flows did not run" list is the killer feature — prevents a
subtle data-corruption bug class.

**Affordance:** Issues tab — flow cascade-halt alarm (F-C8).

#### F.5 — Other managed-effects bug classes (catalogued)

- **F.6** Per-request response accumulator inspector (SSR) — shows the
  `:rf.server/set-status` / `:set-header` / `:set-cookie` evolution
  with last-write-wins warnings (F-C7).
- **F.7** Skipped-on-platform tally — `:rf.fx/skipped-on-platform` chip
  with click-to-filter (F-C9).
- **F.8** CRLF + open-redirect literal rendering in Issues — the attack
  vector visualised (F-C11).
- **F.9** Per-fx args + handler source-coord chip — every fx-row carries
  the registered fx's source coord (F-C6).
- **F.10** Expanded badge taxonomy — `🌐 🔌 📄 🌊 🤖` for HTTP /
  WebSocket / SSR / Flow / Machine `:invoke` (F-C1).
- **F.11** Cross-surface stale-suppression badge — unified `STALE`
  rendering across all four surfaces (F-C5).

---

## §3 The 5 × 4 matrix

The cells. Read across each row to see how an idiom recurs across all four
areas; read down each column to see how an area's bug classes group by
idiom.

|                         | Machines                                | Routes                                  | SSR                                          | Managed-Fx                              |
|-------------------------|------------------------------------------|------------------------------------------|----------------------------------------------|------------------------------------------|
| **Wall-clock timeline** | `:after` countdown rings (M-C2)          | Nav-token swimlanes (R-C3)               | Streaming boundary waterfall (S-C10)         | Retry timeline (F-C3); WebSocket reconnect (F.3) |
| **Boundary diff**       | Shift-click instance divergence (M.4)    | URL vs slice drift watchdog (R-C8)       | Hydration mismatch bisector (S-C2)           | Wire-boundary diff (F-C2)                |
| **Cascade tree**        | Cancellation cascade visualiser (M-C3)   | `:on-match` event chain (R-C4)           | Per-request response accumulator (F-C7)      | Active managed-effects dashboard (F-C4)  |
| **Schema explanation**  | Snapshot diff per transition (M-C10)    | Validation explanation in `:rf.route/not-found` (R-C6) | Server error projection (S-C5)               | Args validation (Spec 010 boundary) explainer (Issues) |
| **Lifecycle accounting**| Per-instance trace strip (M-C5); spawn ancestry (M-C9) | Pending-navigation card (R-C7)           | Per-request frame teardown auditor (S-C8)    | Stale-suppression tally (F-C5); skipped-on-platform tally (F-C9) |

The matrix is **descriptive**, not exhaustive — each cell points to the
primary feature in that idiom-area intersection. Many cells have multiple
features (see §2 for the full catalogue).

---

## §4 Surface placement — which tab grows which feature

The matrix's 20 features land in the 6 existing tabs + 3 popovers, with
NO new tabs. The placement is uniform across areas:

| Tab | Cross-cutting growth |
|---|---|
| **Event** (`e`) | Per-fx **wire-boundary diff** (F-C2). `:on-match` event chain (R-C4). Retry timeline (F-C3). Head model inspector (S-C6). Server error projection (S-C5). Per-fx source-coord chip (F-C6). |
| **App-db** (`a`) | `:rf/route` slice always-visible at top (R-C2). Hydration diff in App-db tab (S-C3). Route-chain visualiser (R-C9). Trusted-shell opt visualiser (S-C9). |
| **Views** (`v`) | (Largely unchanged; flows surface in the "Re-rendered" group when a flow's downstream sub recomputed.) |
| **Trace** (`t`) | **Wall-clock axis** for timer rings, retry waterfalls, deferred-dispatch arrivals. Nav-token timeline as sticky header (or via `r` popover). Streaming SSR boundary waterfall (F-C10 / S-C10). Skipped-on-platform tally chip (F-C9). |
| **Machines** (`m`) | All of §2.1's M-C* features. The cancellation cascade visualiser (M-C3) is the tab's hero growth. |
| **Issues** (`i`) | Hydration mismatch bisector (S-C2) — the SSR hero. Flow cascade-halt alarm (F-C8). Pending-navigation card (R-C7). CRLF + open-redirect rendering (F-C11). Open-redirect advisory (R-C10). Validation explanations inline (Malli rendering throughout). Per-request frame teardown summary (S-C8). |

| Popover | Cross-cutting role |
|---|---|
| **Nav-token timeline** (`r`) | NEW popover. Horizontal swimlanes; carried-vs-current tokens; click-bar → seek. |
| **Wire-trace** (`f`) | NEW popover. The Event-tab wire-boundary diff popped out — floats over any tab. |

| Ribbon (L1) | Cross-cutting growth |
|---|---|
| Active-managed-effects chip (`⧖ 3 HTTP · 1 WS · 2 actors`) — F-C4 entry. |
| SSR indicator chip (`📄 SSR @ <hash>`) — S-C1 entry. |
| Expanded event-list badge taxonomy on L2 rows: `🌐 🔌 📄 🌊 🤖 🧭` (F-C1, R-C1, S-C1). |

---

## §5 The recurring root causes

Across all four areas, the recurring root-cause categories are:

1. **Wall-clock / timing races** (stale tokens, stale epochs, retry
   timing, hydration timing).
2. **Boundary diffs** (server vs client, parent vs child, request vs
   response, before vs after).
3. **Cascade propagation** (parent destroys child, route triggers
   loaders, flow cascade halts, event triggers multiple fxs).
4. **Schema rejection / validation failures** (Malli explanations buried
   in traces).
5. **Resource lifecycle** (per-frame teardown, instance destruction,
   response accumulator clearing).

These five root-cause categories map 1:1 to the five visual idioms in
[§1](#1-the-five-visual-idioms). The matrix is the answer: a debugger that
excels at these five idioms — regardless of which surface they appear on —
is the design target. Causa's existing chrome (cascade-as-unit,
spine-binding, source-coord stamping) is the right substrate; this doc's
20 cells specify the richer renderings for these specific idioms.

---

## §6 Sequencing

A phase plan that turns the matrix into PR-sized work. Each phase is
sequenced so authors see compounding improvement week over week.

### Phase 1 — Quick wins compound (4 PRs)

1. **Badge expansion + routing badge** (F-C1, R-C1, S-C1) — add `🔌 📄 🌊
   🧭` to L2 event-list row badges; add SSR indicator to ribbon.
2. **`:rf/route` slice always-visible + per-event route-chain + match-rank
   tooltip** (R-C2, R-C5, R-C9) — App-db tab and Event tab inline content.
3. **Machine quick wins** (M-C1 guard-verdict overlay + M-C4 `:invoke-all`
   join card + M-C5 per-instance trace + M-C8 path-walked + M-C9 spawn-ancestry).
4. **Effect surface quick wins** (F-C3 retry timeline + F-C5 stale badge
   + F-C6 source-coord chip + F-C8 flow-cascade-halt alarm + F-C9
   skipped-on-platform tally).

Each is a small PR (<300 LoC); each lights up a meaningful workflow.

### Phase 2 — `:after` timer rings + Hydration first surface (3 PRs)

5. **M-C2 — `:after` timer countdown rings.** Big visual addition to
   Machines tab. Animated ring around state nodes.
6. **S-C3 — Hydration diff in App-db tab.** Pre-hydrate vs post-hydrate
   sticky watch.
7. **S-C4 + S-C5 + S-C6 — SSR inspectors** (payload policy verdict +
   error projection trace + head model inspector).

### Phase 3 — Hero features (3 PRs)

8. **F-C2 — Wire-boundary diff.** The hero managed-effects feature. One
   template per surface (HTTP / WS / invoke / server / flow).
9. **S-C2 — Hydration mismatch bisector.** The hero SSR feature. Needs
   canonical-EDN bisector + sub-attribution at hydration time.
10. **M-C3 — Cancellation cascade visualiser.** The hero machine feature.
    Needs cascade-grouping projection.

### Phase 4 — Cross-cutting + ambitious (3 PRs)

11. **R-C3 — Nav-token timeline + cross-surface stale-suppression badges.**
    Generalises the swimlane primitive across nav-tokens, machine `:after`
    epochs, WebSocket connection epochs.
12. **F-C4 — Active managed-effects dashboard.** Ribbon chip + popover
    dashboard.
13. **F-C10 / S-C10 — Streaming SSR boundary timeline.**

### Phase 5 — Polish + operational (when in real use)

- S-C8 — Per-request frame teardown auditor summary.
- M-C6 — Hierarchical state cascade highlighter (animation).
- M-C7 — Microstep loop visualiser.
- M-C10 — Snapshot diff visualisation across transitions.
- R-C8 — URL vs slice drift watchdog.
- S-C11 — Side-by-side SSR replay (post-v1 dream).

---

## §7 Open design questions (Mike's iteration surface)

These are the locked-but-iterable decisions where Mike may want to push
back after reading the spec as the destination:

1. **Hydration tab vs popover vs Issues-inline.** The bisector (S-C2)
   deserves more than a row. Options:
   - **(a)** Add Hydration as a **conditional 7th tab** (visible only
     when SSR is detected for the session). Tab count goes from 6 → 6-or-7.
   - **(b)** Promote it to an inline panel inside the Issues tab. Stays
     at 6.
   - **(c)** Make it a `h`-keyboard popover (consistent with Nav-token
     `r`).
   - **Lean: (c).** Keeps the chrome at 6 tabs; joins the popover
     pattern. But "always visible when relevant" is a strong (a) argument.

2. **Active managed-effects dashboard placement.**
   - **(a)** New dashboard, accessed via Cmd-K palette only.
   - **(b)** Inside the Trace tab as a sticky header above the firehose.
   - **(c)** Per-frame chip in the L1 ribbon. `⧖ 3 HTTP · 1 WS · 2
     actors` with click → opens the dashboard.
   - **Lean: (c).** Ribbon chip is one-glance operational; dashboard as
     popover is one click away.

3. **Security advisor voice (open-redirect, trusted-shell, payload
   leak).**
   - **(a)** Quiet, surface-only. Show the warning if asked; don't push.
   - **(b)** Loud, push to Issues tab. Make security mistakes a
     first-class issue.
   - **(c)** Configurable via Settings. Default to (a); power-users opt
     into (b).
   - **Lean: (a) for v1; (c) when patterns stabilise.** Causa is a
     debugger, not a linter.

4. **Per-request frame teardown auditor (S-C8) — Causa or tests?**
   - **(a)** Causa shows it dev-mode-only (slow path on every per-request
     frame destroy).
   - **(b)** Ship as a separate testing tool (load-test fixture); Causa
     surfaces nothing.
   - **(c)** Causa surfaces only the SUMMARY; deep audit lives in tests.
   - **Lean: (c).** Summary is a "did anything leak this session?" gut
     check; the deep audit is CI-grade.

5. **Wire-boundary diff (F-C2) — per-fx-id renderer hook?**
   - **(a)** Hardcode 5 templates — one per managed-effect surface.
   - **(b)** Each managed-effect surface registers its own per-fx-id
     renderer. New surfaces extend by registering.
   - **(c)** Single generic template that all 5 surfaces approximate.
   - **Lean: (b).** Matches the framework's open-extension pattern.

---

<a id="findings-anchor"></a>

## §8 Findings provenance

This spec distils the following findings (local-only, in
`ai/findings/`):

- **[2026-05-18 cross-cutting design]** —
  `ai/findings/2026-05-18-causa-ssr-machines-routes-fx-debugging.md`
  (1404 LoC). The primary source for §2 bug catalogues, §3 matrix, and §6
  sequencing.
- **[2026-05-17 Causa consolidated design]** —
  `ai/findings/2026-05-17-causa-consolidated-design.md` (2016 LoC). The
  locked R3 design vocabulary + the 6-tab inventory + the 4-layer chrome
  + the spine-binding architecture this all hangs off.
- **[2026-05-17 re-frame-10x vs Causa workflows]** —
  `ai/findings/2026-05-17-re-frame-10x-vs-causa-workflows.md` (492 LoC).
  The W1-W18 workflow catalogue + the gap analysis (Gap A through Gap I)
  that informed cross-tab rendering decisions.
- **[2026-05-17 Causa machine design]** —
  `ai/findings/causa-machines-design-2026-05-17.md` (619 LoC) +
  `ai/findings/causa-uc1-simulation-design-2026-05-17.md`. The UC1/UC2
  machine-inspector architecture that grounds §2.1.
- **[2026-05-17 10x config options]** —
  `ai/findings/2026-05-17-10x-config-options-for-causa.md` (390 LoC). The
  30+ `configure!` keys that inform [`015-Configuration.md`](015-Configuration.md).
- **[2026-05-17 event-spine pass 2]** —
  `ai/findings/2026-05-17-causa-event-spine-design-pass-2.md` (881 LoC).
  The 10x-respect 4-layer chrome design that grounds
  [`018-Event-Spine.md`](018-Event-Spine.md).

The findings carry the design-by-bug reasoning that this normative spec
condenses into requirements. Where the spec is opinionated (a "MUST"
clause), the findings carry the discussion that locked the opinion.

---

## §9 Cross-references

- [`000-Vision.md`](000-Vision.md) — the claim, the five canonical
  questions, the audience, the "where Causa fits" diagram.
- [`018-Event-Spine.md`](018-Event-Spine.md) — the 4-layer chrome
  contract; the spine sub; the 6-tab inventory; the popover invocation
  contract.
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) — the Machines
  tab's full feature spec; this doc's §2.1 catalogues the bug classes
  that motivate each feature there.
- [`006-Hydration-Debugger.md`](006-Hydration-Debugger.md) — the SSR
  hydration mismatch bisector spec; this doc's §2.3 catalogues the bug
  classes.
- [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) — the Event-tab,
  Issues-tab, Routes-content, Flows-content per-tab contracts; the
  wire-boundary diff, server error projection, head model inspector,
  retry timeline, on-match chain, pending-navigation card, and route-chain
  visualiser all land here.
- [`013-Trace-Bus.md`](013-Trace-Bus.md) — the trace fattening contract
  that enables context-at-position (Phase 5 prereq for per-instance
  replay).
- [`015-Configuration.md`](015-Configuration.md) — the `configure!` keys
  including `:filters/auto-hide-events`, `:buffer/retained-epochs`,
  `:theme`, `:keybinding/bindings`.
- [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md) — the
  eight-property contract uniformly satisfied across HTTP, WebSocket,
  `:invoke`, `:rf.server/*`, flows. This doc's wire-boundary diff (F-C2)
  is its visible UI.
- [`spec/Pattern-StaleDetection.md`](../../../spec/Pattern-StaleDetection.md)
  — the cross-cutting stale-detection pattern. Causa's
  stale-suppression tally (F-C5) and the nav-token timeline (R-C3) are
  both visual treatments of this pattern.
