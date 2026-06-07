# EP: Explicit Frame Target Resolution

Status: proposal

Date: 2026-06-06

Related:

- [Spec 002 - Frames](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md)
- [Spec 004 - Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md)
- [Spec 006 - Reactive Substrate](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md)
- [Spec 009 - Instrumentation](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md)
- [Spec 011 - SSR](https://github.com/day8/re-frame2/blob/main/spec/011-SSR.md)
- [Spec 014 - HTTP Requests](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md)
- [Runtime Architecture](https://github.com/day8/re-frame2/blob/main/spec/Runtime-Architecture.md)
- [Xray API](../xray/api/index.md)
- [App/Runtime Partition EP](app-db-runtime-partition.md)

## Summary

This enhancement proposes removing the ambient `:rf/default` fallback from
frame-scoped operations.

Today many public and internal APIs resolve a missing frame through this chain:

```clojure
explicit frame -> dynamic frame -> React context -> :rf/default
```

That preserves re-frame v1 ergonomics, but it makes frame isolation depend on
absence. A call that loses its frame context does not fail; it silently targets
the host's default app frame. That is especially dangerous around async
callbacks, React portals, Xray, Story, SSR request frames, test fixtures, and
multi-frame applications.

The proposed rule is:

> A frame-scoped operation must resolve a frame from explicit frame context.
> Absence is an error. No operation may synthesize `:rf/default` from missing
> context.

`:rf/default` can remain a legal frame id for applications that explicitly
choose it. It is no longer a fallback target, no longer created just because
`init!` ran, and no longer the bottom tier of `current-frame` resolution.

## Why This EP Exists

The design reviews in `ai/findings/2026-06-06.design-review-codex.md` and
`ai/findings/2026-06-06.design-review-claude.md` identified default-frame
fallback as a frame-isolation fault line.

The first formulation was compatibility-preserving:

> If `:rf/default` is the only app frame, keep implicit fallback even when
> Xray or tool frames also exist.

That formulation required a frame taxonomy:

- app frames count for ambiguity;
- tool frames such as `:rf/xray` do not;
- SSR, Story, and test frames need their own classification;
- `:rf/default` remains the compatibility anchor.

The follow-up decision is stronger and cleaner:

> re-frame2 is pre-alpha; prefer correctness and clarity over compatibility.
> Remove the compatibility fallback instead of refining it.

That means the issue is not "multiple non-default frames are ambiguous." The
issue is that a missing frame target is allowed to proceed at all.

## Problem

Frames are the public isolation primitive for:

- app-db;
- event routing;
- subscription caches;
- machine snapshots;
- route state;
- SSR request state;
- Xray/tool state;
- trace and epoch history;
- runtime-owned partitions.

If a frame-scoped operation falls through to `:rf/default`, isolation can fail
silently.

Examples:

- A Promise callback was created inside `:admin/app`, fires after the render
  scope has unwound, and bare `(rf/dispatch [:save])` mutates `:rf/default`.
- A plain Reagent function is rendered under a non-default frame provider, but
  lacks the adapter context wiring used by `reg-view`; its subscriptions read
  from `:rf/default`.
- Xray chrome lives in `:rf/xray`, but a deferred click handler loses context
  and writes `:rf.xray/*` UI state into the host app frame.
- SSR hydration code omits `:frame` and hydrates `:rf/default` even when the
  actual client app frame is request- or mount-specific.
- A machine lifecycle fx runs without a frame in its fx context and mutates the
  default frame's machine table instead of failing as a malformed internal fx.

The current code has diagnostics for some cases, but they are partial:

- dispatch fallthrough warnings are development-only;
- they only cover a subset of dispatch paths;
- subscribe fallthrough has a separate plain-function warning path;
- a handler that happens to exist on `:rf/default` can hide the bug;
- production builds keep the fallback behavior after warnings are elided.

The smell is the same family as the app/runtime partition EP: a load-bearing
boundary is protected by convention and late diagnostics rather than by the
shape of the API.

## What We Learned In This Session

The `:rf/default` fallback is broader than dispatch.

The central resolver lives in `implementation/core/src/re_frame/frame.cljc`:

```clojure
(defn current-frame []
  (or *current-frame* :rf/default))

(defn resolve-current-frame []
  ;; CLJS: adapter/current-frame hook, then current-frame
  ;; CLJ:  current-frame
  ...)
```

On CLJS, React-shaped adapters add the middle tier:

```clojure
dynamic frame -> React context -> :rf/default
```

The discovered fallback surfaces are:

| Area | Current fallback shape | Why it matters |
|---|---|---|
| Dispatch / dispatch-sync | Router envelope computes default frame through `frame/resolve-current-frame`; missing context can enqueue work on `:rf/default`. | Wrong-frame writes are the highest-risk failure. |
| Subscriptions | One-arity `subscribe`, `subscribe-once`, and `unsubscribe` call `frame/resolve-current-frame`. | Wrong-frame reads can hide state bugs and render the wrong app. |
| Current-frame helpers | `current-frame-id`, no-arg `frame-handle`, no-arg `frame-bound-fn*`, `snapshot-of`, and no-arg `sub-cache` can capture or read `:rf/default`. | Async helpers can accidentally preserve the wrong target. |
| React context | The shared React context is created with default value `:rf/default`; corrupted or missing context recovers to `:rf/default`. | Absence is encoded as a real frame id. |
| Reagent views | `provider/current-frame` falls through to `:rf/default`; plain-function warnings detect one subcase. | View code can silently escape a provider. |
| Elision | No-arg declarations and schema-population helpers target `:rf/default`; `elide-wire-value` falls back to current frame / default. | Privacy redaction can read the wrong mark registry. |
| HTTP | HTTP interceptor registration and clearing default to `:rf/default` when no frame is supplied. | Request middleware can attach to the wrong app frame. |
| SSR | `hydrate!`, streaming client install, and `active-head` have default-frame convenience forms. | Hydration must be tied to the actual app frame, not a conventional id. |
| Machines | Several machine lifecycle fxs defensively default missing `:frame` to `:rf/default`. | Internal malformed fx context should fail, not mutate another frame. |
| Trace / marks projection | Some legacy or frameless trace projection assumes `:rf/default` for grouping or elision. | Tooling can misattribute events or redact with the wrong frame policy. |
| Xray | Xray state lives in `:rf/xray`, but its observed host target defaults to `:rf/default`. | Xray needs an explicit observed target distinct from its own frame. |
| Docs / skills / migration | API docs, migration docs, Xray docs, and skills teach default-frame fallback in many places. | The implementation change must be paired with a documentation and agent-training change. |

This is why the fix should be a cross-repo EP rather than a small router patch.

## Proposed Contract

### Frame Target Invariant

Every frame-scoped operation must resolve its target frame from one of these
sources:

1. An explicit frame id or frame handle passed to the API.
2. A lexical `with-frame` binding.
3. A React frame provider surrounding the render.
4. A frame-bound function or frame handle captured while a valid frame context
   was present.
5. The active dispatch envelope while an event cascade is running.
6. A tool, Story, SSR, or test harness target selected explicitly by that
   harness.

If no source is available, the operation fails with `:rf.error/no-frame-context`.

### `:rf/default`

`:rf/default` remains a valid keyword and can remain useful in tests, examples,
or small applications that explicitly choose that id:

```clojure
(rf/reg-frame :rf/default {:doc "The app frame for this program."})
(rf/with-frame :rf/default
  (rf/dispatch [:app/boot]))
```

But the runtime must not create or select it implicitly.

In particular:

- `init!` installs the adapter and runtime capabilities; it does not guarantee
  `:rf/default` exists.
- React context default is absence, not `:rf/default`.
- missing `:frame` in framework fx context is an internal error, not a request
  to use `:rf/default`.
- docs should stop describing `:rf/default` as "the frame you get when no frame
  is supplied."

### Public API Shape

There are two acceptable call styles:

```clojure
;; Explicit target.
(rf/dispatch [:todo/add "Milk"] {:frame :app/main})
(rf/subscribe :app/main [:todo/items])

;; Ambient, but only because a real context exists.
(rf/with-frame :app/main
  (rf/dispatch [:todo/add "Milk"])
  @(rf/subscribe [:todo/items]))
```

The first style is the right shape for callbacks, tools, tests, and SSR
helpers. The second style is the right shape inside application roots and view
subtrees.

The wrong shape is:

```clojure
;; Outside any frame context.
(rf/dispatch [:todo/add "Milk"])
```

That must fail immediately.

### Async Boundaries

Async code must carry frame identity explicitly.

Existing helpers already point in the right direction:

```clojure
(let [dispatch (:dispatch (rf/frame-handle :app/main))]
  (.then promise #(dispatch [:loaded %])))

(def on-message
  (rf/frame-bound-fn* :app/main
    (fn [msg]
      (rf/dispatch [:ws/message msg]))))
```

The no-arg capture forms should only work when a real frame context exists at
capture time. If capture happens outside a frame, they should fail with
`:rf.error/no-frame-context` instead of capturing `:rf/default`.

### Views And Root Mounts

Application roots must establish a frame provider deliberately.

Example:

```clojure
(def app-frame :app/main)

(rf/reg-frame app-frame
  {:on-create [:app/boot]})

(rf/init! reagent/adapter)

(rdc/render root
  [rf/frame-provider {:frame app-frame}
   [app-root]])
```

`reg-view` and adapter hooks continue to make the common render path ergonomic.
The difference is that there is no frame-shaped value underneath the tree unless
the host supplied one.

### Xray And Tool Frames

Xray has two distinct frame concepts:

| Frame | Meaning |
|---|---|
| `:rf/xray` | Xray's own state frame. |
| target frame | The host app frame Xray is inspecting. |

Both must be explicit.

Xray should still be able to mount its own frame lazily, but it should not
default the inspected host target to `:rf/default`. A host can pass a target:

```clojure
(xray/init! {:target-frame :app/main})
```

or Xray can show an "unselected target" state until the frame picker chooses
one. If Xray wants to auto-select a sole app frame, that should be modelled as
an explicit discovery policy in Xray, not as core `:rf/default` fallback.

Tool APIs such as pair-MCP and Xray runtime accessors should reject mutating
operations when no target frame is selected. Reads may return structured
`:rf.tool/no-frame-selected` data for UX, but they should not read
`:rf/default` by convention.

### SSR

SSR request and hydration APIs should require a frame.

```clojure
(ssr/hydrate! {:frame :app/main
               :render-tree-fn render-root})

(streaming-client/install! {:frame :app/main})
```

Server request frames are already naturally explicit. Client hydration should
match that discipline so duplicate fetches, head projection, hydration deltas,
and error projection all land on the same intended frame.

### Machines And Managed Effects

Framework effects that run inside a cascade should inherit the frame from the
fx context. Missing frame context in a lifecycle-critical fx is malformed
runtime state.

For example:

```clojure
{:rf.machine/spawn {...}} ;; frame comes from the dispatch envelope
```

If the fx handler is called without `{:frame ...}`, it should emit or throw
`:rf.error/no-frame-context`; it should not repair the call by mutating
`:rf/default`.

The same rule applies to HTTP middleware, timers, route fxs, flows, resource
queries, and future managed work ledgers.

### Trace, Projection, And Elision

Trace projection must distinguish:

- frame-qualified events;
- cascade events whose frame can be recovered from the dispatch id;
- genuinely frameless events such as boot-time registration;
- malformed events that should have carried a frame.

For privacy, elision must not borrow `:rf/default` marks for a frameless value.
When a value is crossing an off-box boundary and no frame policy is available,
the safe default is to redact conservatively or report that the value cannot be
projected safely.

## Options Considered

### Option A: Keep Status Quo

Keep `:rf/default` as the universal bottom tier and rely on warnings.

Pros:

- Lowest short-term churn.
- Preserves re-frame v1 style examples.

Cons:

- Wrong-frame writes remain possible.
- Diagnostics are partial and often dev-only.
- Tool and SSR boundaries inherit app-frame convenience semantics.
- This conflicts with the pre-alpha "build it right" posture.

### Option B: Role-Aware Compatibility Fallback

Keep fallback to `:rf/default` when it is the only live app frame, excluding
tool frames such as `:rf/xray` from ambiguity.

Pros:

- Preserves single-app ergonomics.
- Handles the common "app plus Xray" case.

Cons:

- Requires frame roles before solving the immediate problem.
- Still lets missing context proceed.
- Creates more policy: app frame vs tool frame vs Story vs SSR vs test.
- Harder to teach than "no frame context, no operation."

### Option C: Explicit Frame Context, No Default Fallback

Remove fallback completely. A frame may be ambient, but only when a root,
provider, cascade, or lexical binding supplied it explicitly.

Pros:

- Strongest frame isolation.
- Easiest rule to teach.
- Makes async and tooling boundaries honest.
- Fits pre-alpha no-compatibility posture.

Cons:

- Large repo-wide migration.
- Many tests, docs, examples, tools, and skills currently assume `:rf/default`.
- Requires better root/bootstrap examples.

Recommendation: **Option C**.

## Implementation Plan

*See also the Audit Addendum below for additional surfaces, files, and call
sites found by a 2026-06-07 codebase sweep — several of the plan steps below are
extended there.*

This should be implemented as a planned migration, not one giant patch. The
order matters because the fallback is embedded in docs, tests, tooling, and
developer education.

### 1. Contract And Inventory Beads

Create beads for:

- central frame resolver contract;
- dispatch/router migration;
- subscription/read API migration;
- React context and adapter migration;
- framework fx migration;
- SSR/head/hydration migration;
- Xray/Story/tool target-frame migration;
- trace/elision projection migration;
- docs/API/migration/skills updates;
- conformance tests and examples cleanup.

Each bead should explicitly state whether it removes fallback, updates call
sites, updates tests, or updates documentation.

### 2. Central Frame Resolver

Change the central frame APIs before touching callers:

- `frame/current-frame` returns the lexical dynamic frame or nil.
- `frame/resolve-current-frame` returns the dynamic or React-context frame, or
  nil.
- add a helper such as `frame/require-current-frame!` that raises or emits
  `:rf.error/no-frame-context`.
- remove `ensure-default-frame!` as an `init!` side effect.
- define the structured error payload for missing context:

```clojure
{:rf.error/id :rf.error/no-frame-context
 :operation   :dispatch
 :where       're-frame.router/dispatch!
 :event-id    :todo/add
 :recovery    :supply-frame}
```

### 3. Dispatch And Router

Update router envelope construction:

- explicit `{:frame ...}` wins;
- otherwise require a resolved current frame;
- no frame means no enqueue;
- remove `:fell-through-to-default?`;
- replace fallthrough warnings with missing-frame errors;
- preserve cross-frame dispatch diagnostics separately.

Tests should cover:

- bare dispatch outside context fails;
- dispatch under `with-frame` works;
- dispatch under frame-provider works;
- async bare dispatch after context unwinds fails;
- frame-bound dispatch after context unwinds works;
- explicit `{:frame :rf/default}` works only if that frame exists.

### 4. Subscriptions And Read Helpers

Update:

- one-arity `subscribe`;
- one-arity `subscribe-once`;
- one-arity `unsubscribe`;
- `snapshot-of`;
- no-arg `sub-cache`;
- `current-frame-id`;
- no-arg `frame-handle`;
- no-arg `frame-bound-fn*`.

The no-arg forms can remain, but only as context readers. They should fail when
used outside context.

Tests should cover wrong-frame prevention for reads as well as writes.

### 5. Root, View, And Adapter Surfaces

Update React context and view providers:

- shared React context default becomes nil or a sentinel, not `:rf/default`;
- corrupted context reports absence rather than recovering to default;
- `frame-provider` requires `:frame`;
- `reg-view` continues to wire context;
- plain Reagent function warning can become a sharper no-frame-context path.

Docs and examples should teach root frame declaration first, then normal app
views.

### 6. Framework Effects And Runtime Subsystems

Remove defensive `:or {frame-id :rf/default}` defaults from framework fxs and
runtime helpers:

- machine spawn/destroy/update-snapshot/timers;
- `dispatch-to-system`;
- HTTP interceptor registration and clearing;
- route and navigation fxs;
- flows;
- future resource query fxs;
- runtime partition writes.

Framework fxs invoked from a cascade should already receive the envelope frame.
If not, that is an invariant failure worth surfacing.

### 7. SSR And Head

Update SSR APIs:

- `hydrate!` requires `:frame`;
- streaming client `install!` requires `:frame`;
- `active-head` no-arg form is removed or made context-required;
- server examples create request frames explicitly;
- client examples pass the same frame into hydrate, root provider, and Xray.

### 8. Trace, Marks, And Elision

Update projection rules:

- no default frame for old or frameless trace events;
- redaction against a frame requires a frame-qualified policy;
- off-box egress without a frame policy fails closed;
- frame-less boot/registration events are labelled as frameless rather than
  attributed to `:rf/default`;
- Xray can display frameless events separately.

This step should coordinate with the App/Runtime Partition EP because elision
state is moving into runtime-db.

### 9. Xray, Story, Pair Tools, And Skills

Update tool surfaces:

- Xray mounts `:rf/xray` explicitly.
- Xray target frame starts as unselected unless the host supplies
  `:target-frame` or Xray applies an explicit discovery policy.
- Xray frame picker drives target selection; panels that need host data refuse
  to read until selected.
- Xray runtime / pair-MCP mutation tools require explicit `:frame`.
- `discover-app` may report available frames and suggest a target, but should
  not mutate or read by falling back.
- Story variant frames and Story->Xray handoff pass explicit frame ids.

Update skills:

- `skills/re-frame2`
- `skills/re-frame2-xray`
- `skills/re-frame-migration`
- `docs/skills/re-frame2-xray.md`
- any pair-MCP or Story skill references that tell agents bare
  `dispatch`/`subscribe` targets `:rf/default`.

Agents should be taught the new preflight:

> What frame am I operating on? If none is explicit, do not dispatch, subscribe,
> restore, inspect app-db, or read sensitive data.

### 10. Docs, API, Migration, And Examples

Update all references that teach default-frame fallback:

- `docs/api/01-core.md`
- `docs/api/02-views.md`
- `docs/api/04-machines.md`
- `docs/api/07-http.md`
- `docs/api/09-ssr.md`
- `docs/api/13-lifecycle.md`
- `docs/xray/api/*`
- `docs/migration/from-re-frame-v1/README.md`
- `spec/002-Frames.md`
- `spec/004-Views.md`
- `spec/006-ReactiveSubstrate.md`
- `spec/011-SSR.md`
- `spec/014-HTTPRequests.md`
- examples and testbeds.

The migration guide should stop saying "today's re-frame is re-frame2 with
only `:rf/default` in play." The new story is:

> re-frame2 requires an application frame. A migration may choose
> `:rf/default` as the explicit id, but the runtime will not infer it.

### 11. Tests And Conformance

Add a conformance sweep:

- no bare app operation outside frame context succeeds;
- no React context default equals `:rf/default`;
- no framework fx defaults missing frame to `:rf/default`;
- no SSR convenience API mutates `:rf/default` when `:frame` is absent;
- no Xray panel writes to the host frame because its own context was lost;
- no off-box egress redacts against `:rf/default` by fallback;
- explicit `:rf/default` still works when a test or app registers it.

Add static checks where useful:

```powershell
rg ":or \\{frame-id :rf/default\\}|or \\(:frame|resolve-current-frame|current-frame\\)" implementation
rg ":rf/default" docs skills tools implementation
```

The goal is not to ban the keyword. The goal is to ban using it as an absence
repair.

## Bead Plan

Suggested beads:

| Bead | Scope |
|---|---|
| Decision bead | Accept explicit frame target resolution and remove ambient default fallback. |
| Resolver bead | Change `frame/current-frame`, `resolve-current-frame`, React context default, and missing-frame error shape. |
| Router bead | Remove dispatch fallback and fallthrough diagnostics; add no-frame dispatch tests. |
| Subs bead | Remove subscribe/read fallback; add no-frame read tests. |
| Root/view bead | Update frame-provider, reg-view docs, examples, and adapter tests. |
| Fx bead | Remove fallback defaults from machines, HTTP, route, flow, and resource-related fxs. |
| SSR bead | Require frame in hydration, streaming, head, and request examples. |
| Elision/trace bead | Remove default-frame attribution from projection and fail closed for frameless egress. |
| Xray/Story/tool bead | Require explicit own-frame and target-frame selection; update pair-MCP accessors. |
| Docs/skills bead | Update API docs, specs, migration guide, examples, and skills. |
| Conformance bead | Add static/dynamic checks that prevent regression. |

## Open Questions

*See also the Audit Addendum below for additional open questions found by a
2026-06-07 codebase sweep.*

1. Should `init!` stop creating any frame, or should a separate app bootstrap
   helper create a frame explicitly from opts?
2. Should missing frame context throw synchronously everywhere, or should some
   read/tool surfaces return structured error data?
3. Should Xray auto-select the sole non-tool frame as an explicit discovery
   policy, or should it always start with no selected target?
4. Should `:rf/default` remain reserved as a framework-known conventional id,
   or become just another explicit user-selected keyword?
5. Should migration tooling rewrite bare v1 calls into a `with-frame` root, or
   into explicit `{:frame :rf/default}` call sites?

## Acceptance Criteria

This EP is implemented when:

- there is no central fallback from missing frame context to `:rf/default`;
- dispatch, subscribe, SSR, framework fx, Xray, and tool accessors require a
  real frame target;
- `:rf/default` remains usable only when explicitly registered and selected;
- docs and skills teach explicit frame context;
- tests prove that losing frame context fails rather than mutating or reading
  another frame.

## Audit Addendum (2026-06-07): Implications Surfaced By A Codebase Sweep

Four read-only audits (core runtime; per-feature artefacts + framework fxs;
tooling/Tool-Pair/Xray/pair-MCP; spec/docs/skills/tests) cross-checked this EP
against the current codebase. The EP's direction and problem statement are sound
and well-grounded. The following implications were **missing or
under-specified** and should be folded into the plan. Citations are `file:line`
as of the sweep.

### 1. Reconcile With The Shipped Tool-Pair Operating-Frame Contract (highest priority — currently unaddressed)

re-frame2 already has a **4-tier operating-frame resolution** contract for tools
([`spec/Tool-Pair.md:394-409`](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md);
`skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs:264-312`). Two parts
of it directly collide with this EP and must be reconciled, not broken anew:

- **Tier 3 auto-selects the *sole* app frame from absence**
  (`runtime.cljs:292-312`; guaranteed at `Tool-Pair.md:403`) — itself "ambient
  resolution from absence," exactly what the EP bans. Decide explicitly whether
  tier-3 survives.
- **`:rf/default` is a *privileged app frame*** — the named carve-out from the
  `:rf/*`-tool-frame exclusion (`Tool-Pair.md:407`; `runtime.cljs:264-282`
  `reserved-tool-frame?`). The EP's "`:rf/default` is no longer special / not
  created by `init!`" makes that carve-out dead code; rule on it.
- Already-aligned (note it): the **pin layer** `reset-operating-frame` does NOT
  fall back to `:rf/default` — it re-resolves tiers 3→4 and refuses with
  `:ambiguous-frame` (`runtime.cljs:170-185`). So only tier-3 + the carve-out
  collide.
- Reconcile `:rf.error/no-frame-context` with the existing `:ambiguous-frame`
  refusal and `:rf.tool/no-frame-selected`.

**Action:** add
[`spec/Tool-Pair.md`](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md)
(hot-zone) to plan step #9; this resolves EP Open-Question 3 (it's already
answered by the tier-3 discovery policy).

### 2. Decision To Surface: This EP Revokes A Stated Design Goal (the single-frame ergonomic)

The EP silently overrides a stated design goal and a headline teaching promise:

- [`spec/002-Frames.md:21`](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md)
  — design goal: "Frame plurality is invisible to single-frame apps. No new API
  surfaces in user code unless the user opts in."
- `docs/guide/18-frames.md:51,82` — "You will write whole real applications and
  never type the word `frame`… `:rf/default` is invisible scaffolding… costs you
  **nothing**."

The EP's "bare dispatch must fail" revokes both for every single-frame app.
**The EP's Recommendation should state which goal it overrides**, OR consider a
**differentiated stance**: require an explicit frame for framework / tool / SSR
/ internal-fx code (clearly correct, low ergonomic cost), but for *user*
`dispatch`/`subscribe` keep ambient resolution and error only once a **second
app frame** exists (ambient-to-the-sole-frame is unambiguous and safe in a
single-app program; the wrong-frame bug it prevents cannot occur there — the
residual risk is tool/SSR code losing context, which the framework/tool
requirement already covers).

Conversely, the EP's **strongest unused ally**:
[`spec/Principles.md`](https://github.com/day8/re-frame2/blob/main/spec/Principles.md)
§"Low hidden context" (P8) +
[`spec/AI-Audit.md:112,272`](https://github.com/day8/re-frame2/blob/main/spec/AI-Audit.md)
already grade the plain-fn `:rf/default` routing as a gap whose resolution is
"remove the footgun or make it loud" — cite this as principled backing.

### 3. Normative "always present" Statements Missing From The Plan

These assert the contract the EP removes and are NOT all in the EP's file list —
add to plan step #10 (hot-zone files sequential):

| File:line | Statement to rewrite |
|---|---|
| `spec/Runtime-Architecture.md:125, 235` | "`:rf/default` is always present… lands here"; boot step 5 "`:rf/default` frame is guaranteed present" — **file entirely absent from EP** |
| `spec/006-ReactiveSubstrate.md:890-893` | "Default value is `:rf/default` — the Spec **guarantees this frame always exists**" |
| `spec/006-ReactiveSubstrate.md:1120, 1122` | the **normative adapter-conformance table** ("dynamic-var → React-context → `:rf/default`"; "Missing/nil `:frame` → falls through to `:rf/default` (deliberate default)") — adapters are graded against this |
| `spec/002-Frames.md:15, 39/43, 219-221, 396, 403/647, 525-568 (ref-impl block), 787-799` | the headline frame-model framing, the "defaults to `:rf/default`" API-at-a-glance, the frame-attachment priority list ending "4. Default", the "falls through… not a bug" (which the EP inverts to a bug), and the `read-frame-from-context` reference impl + edge-case table |
| `spec/Conventions.md:18, 206` | "the **universal default frame id**" reservation — ties to EP Open-Question 4 (hot-zone) |
| `spec/Tool-Pair.md:403, 407, 409` | "always pre-registered" premises (see §1) |
| `spec/Ownership.md:23`, `spec/README.md:61` | index/ownership rows literally naming "`:rf/default` fallback" as an owned contract |

### 4. The Fallthrough-Warning Vocabulary Is *deleted*, Not Edited — Retire It Coherently

Plan step #3 removes `:fell-through-to-default?` and the async-callback
fallthrough warning. That warning is a **reserved vocabulary term** with
downstream definitions that must be retired together (Spec-ulation: reserved
vocab can't vanish silently):

- `spec/Spec-Schemas.md:1312` — `DispatchFromAsyncCallbackFellThroughTags`
  (`[:routed-to [:= :rf/default]]`); also envelope-promotion rule
  `Spec-Schemas.md:127` (`(merge {:event event :frame :rf/default …} opts)`) and
  `118, 376, 1399`.
- `spec/Conventions.md:84, 326` (reserved-vocabulary entries for
  `:rf.warning/dispatch-from-async-callback-fell-through-to-default`);
  `spec/Security.md:200`; the `spec/009-Instrumentation.md` warning/error
  catalogue.

**Action:** add these to plan steps #3/#11 as "retire the warning category + its
schema + reserved-vocab + catalogue rows."

### 5. Core-Runtime Gaps (add to the named plan steps)

- **React-context corruption detector depends on `:rf/default`**
  (`adapter/context.cljs:36, 129-141, 188-198`): the detector uses "value is a
  keyword (`:rf/default`)" to tell *corrupted* from *no-provider*. A nil
  sentinel breaks that — need a distinct non-frame sentinel (nil = corruption,
  sentinel = no-provider) OR fold `:rf.error/frame-context-corrupted` into
  `:rf.error/no-frame-context`. Add to plan step #5 + an open question.
- **`:rf.error/no-frame-context` emission is itself frameless**
  (`router.cljc:387-393` drops untagged traces from per-frame epoch capture;
  `frame.cljc:74` trace-ring routing returns nil with no in-flight frame;
  `cofx.cljc:330-341`): the error announcing "no frame" risks being invisible to
  per-frame tooling → must route through the **always-on error-emit axis**, not
  the per-frame epoch axis. Reconcile with the Spec 009 catalogue. Add an open
  question.
- **Disambiguate `:rf.error/no-frame-context` from `:rf.error/frame-destroyed`**
  (`router.cljc:2284-2297, 2334-2347`): with `init!` no longer creating
  `:rf/default`, a fall-through that previously hit a live `:rf/default` now
  resolves to `:rf/default` and hits the *frame-destroyed* branch. Resolution
  must fail **before** the registry lookup so no-frame-context fires first. Add
  to plan step #3.
- **Redaction chokepoints synthesize `:rf/default`** (security-sensitive — add
  to plan step #8): `marks/project-trace-event` `marks.cljc:842`
  `(or (:frame tags) :rf/default)` selects the elision registry for a frameless
  trace; `elision/elide-wire-value` `elision.cljc:524` is a **double** fallback
  `(or (:frame opts) (frame/current-frame) :rf/default)`; plus
  `elision/declarations` & `sensitive-declarations` no-arg
  (`elision.cljc:126, 132`). All must **fail closed** (redact conservatively /
  skip projection), not borrow `:rf/default` marks.
- **`ensure-default-frame!` has a second caller — the test fixture**
  (`core.cljc:1681` init!; `test_support.cljc:423`
  `make-reset-runtime-fixture`; ~12 test files call it directly): the fixture is
  the larger consumer. Plan step #2 / Open-Q1 must decide: delete outright
  (every test registers `:rf/default` explicitly) or keep test-only.

### 6. Registration-Time Vs Operation-Time Resolution (new subsection needed)

`reg-flow` (`flows/registry.cljc:518`), `reg-app-schema`
(`schemas/storage.cljc:42-45, 471`), and the elision `populate-*-from-schemas!`
no-arg forms (`elision.cljc:169, 180, 189`) resolve the target frame at
**registration** time via `(or frame (frame/current-frame))` — and
registrations typically run at namespace-load/boot, when there is no frame
context, so they would error under the EP.

Add a "Registration-time frame resolution" subsection ruling that no-`:frame`
`reg-*` forms either require explicit `:frame` or are legal only inside a
`with-frame`/`:on-create` context — distinct from the operation-time rule.

### 7. Feature-Artefact Specifics (the plan lists these only generically)

- **Routing URL ownership is a structural `:rf/default` anchor**, independent of
  the resolver: `url-owner-frame-id` returns `:rf/default` as the implicit URL
  owner (`nav_fx.cljc:43-52, 59`; `history.cljc:64, 88, 93`). Needs explicit
  URL-owner declaration; the generic "route fxs inherit the cascade frame" rule
  does not cover it.
- **The literal-`:rf/default` idiom defeats the EP's conformance regex.** The
  EP's `rg ":or \{frame-id :rf/default\}"` catches only ~6 machine sites; ~16
  sites use `(or … :rf/default)` literally (`http_middleware.cljc:168,198,200`;
  `http_managed.cljc:220`;
  `routing/{can_leave:268,navigate:391,nav_token:108,url_change:255,283,scroll:150}`;
  `machines/lifecycle_fx/registration.cljc:313,586,749`; `elision.cljc:524`;
  `marks.cljc:842`; `substrate/spine.cljs:856`). **Broaden the conformance
  regex** to also match `\(or [^)]*:rf/default\)`. Name
  `reg-http-interceptor`/`clear-http-interceptor` as *registration* surfaces
  (not cascade fxs).
- **The frame-provider default lives in the substrate-shared spine**, not just
  Reagent: `substrate/spine.cljs:856` `(or frame-kw :rf/default)` is the single
  site all three React adapters route through (Reagent `provider.cljs:112` is a
  second, "defensive default for tooling-generated trees that elide the prop").
  Plan step #5 should name the spine core.
- **Managed-HTTP reply has a frameless branch**: `dispatch-reply-via-late-bind!`
  omits `:frame` when nil → re-resolves post-cascade
  (`http_encoding.cljc:261-265`; `http_transport.cljc:738-754`;
  `http_handlers.cljc:208,247`). Frame must be required-and-captured at request
  initiation; no frameless reply branch. Add to "Async Boundaries."
- **`sub-machine`/`machine-has-tag?`** (`core_machines.cljc:200,217`) delegate
  to `subscribe` with no explicit-frame arity — they need one post-migration.

### 8. Cross-EP Sequencing With The App/Runtime Partition EP

The partition EP renames the cofx frame key `:frame`/`:rf/frame` →
`:rf.frame/id` (`docs/EP/app-db-runtime-partition.md:213, 297, 313, 883`). This
EP's framework-fx step (#6) and its `:rf.machine/spawn` example read `:frame`.

**Sequence step #6 after/with the partition EP's `:rf.frame/id` cofx rename, and
use `:rf.frame/id` as the inherited frame key**; align examples across the two
EPs (the partition EP's examples still show `:rf.frame/id :rf/default`).

### 9. Tooling Surfaces To Enumerate (beyond the Xray two-frame split the EP already covers)

- Xray hard-defaults the **inspected host target** to `:rf/default` across ~7
  sites the plan (#9) doesn't name: `defaults/default-target-frame`
  (`defaults.cljs:30-39`), the `:rf.xray/target-frame` sub default
  (`epoch.cljs:44`), the `:rf.xray/set-target-frame` nil-reset (`epoch.cljs:106`;
  `core.cljs:199`), `epoch.cljs:123`, `mount.cljs:488`, `spine.cljs:653/667`,
  `core/target-frame` (`core.cljs:177-193`). Need an "unselected" sentinel.
  (xray spec-pair-update rule: `tools/xray/spec/*` move in the same PR.)
- **`eval-cljs`** no-`:frame` path is *documented* to target `:rf/default`
  (`tools/re-frame2-pair-mcp/.../eval_cljs.cljs:200-261`; catalogue
  `003-Tool-Catalogue.md:729, 733-744`) — a bare `(rf/dispatch …)`/
  `(rf/subscribe …)` in an unwrapped form would now raise
  `:rf.error/no-frame-context`. Add to plan #9 + fix the catalogue default-doc.
- The **mcp-conformance corpus bakes `:rf/default`** as the resolved frame
  across dozens of fixtures (`conformance_test.cljs` snapshot/reset-frame-db/
  dispatch/dispatch-dry-run/operating-frame fixtures) and `discover-app`'s
  `:no-frames-registered` hint says "Call `(rf/init!)` to register `:rf/default`"
  (`discover_app.cljs:182-185`) — which the EP invalidates. Plan #11 must be
  "**invert/retire the existing fixtures + rewrite the hint**," not just "add a
  new sweep."
- The pair-MCP **precheck cache** keys an omitted-`:frame` hash on the
  runtime-resolved operating frame (`precheck.cljs:119-150`,
  `[:operating-frame nil]`) — must change in lockstep with the resolver.

### 10. Docs & Skills The Plan (#9/#10) Omits

- Docs: **`docs/guide/18-frames.md`** (the entire `:rf/default` teaching chapter
  — priority; line 187 teaches async-callback fallthrough as expected
  behaviour), plus `docs/guide/{25,16,14,19,23,09}*`, and
  **`docs/xray/api/mount-control.md:90,98`** ("Most apps run one host frame
  (`:rf/default`) and Xray observes it **implicitly**").
- Skills: **`skills/re-frame2-setup`** ("`:rf/default` is always
  auto-registered"), **`skills/re-frame2-pair`** ("eval helpers warn-and-default
  to `:rf/default`"), **`skills/re-frame2-implementor`** ("views run in the
  context of a default frame"), and
  **`skills/re-frame-migration/references/auto-cross-cutting.md:301,316`** which
  **actively instructs agents to call `frame/ensure-default-frame!`** — the
  function the EP deletes (will generate broken migration advice). Add all to
  plan #9.

### 11. Tests/Conformance To Invert (plan #11 lists abstract checks, not the existing positive assertions)

- `implementation/core/test/re_frame/dispatch_fallthrough_warn_test.clj` +
  `…_dom_cljs_test.cljs` — assert dispatch falls through to `:rf/default` + the
  warning fires → invert to "raises `:rf.error/no-frame-context`."
- `implementation/core/test/re_frame/views_current_component_cljs_test.cljs:49,68`
  — assert `(= :rf/default (views/current-frame))` for no-hook/nil-hook →
  invert.
- ~20+ fixtures under `spec/conformance/fixtures/` stamp `:frame :rf/default`
  (dispatch-envelope, drain-depth-limit, epoch-record-shape, frame-lifecycle,
  http-interceptor-*, ssr-*, …). State the migration rule: **every conformance
  fixture must explicitly register its target frame**; any that dispatch
  frame-lessly and expect default-stamping must be reauthored.

### New Open Questions (add to the EP's Open Questions)

- How does `:rf.error/no-frame-context` route so a *frameless* error is still
  surfaced (always-on axis vs per-frame epoch)?
- Does tier-3 sole-app auto-resolution (Tool-Pair) survive, and is `:rf/default`
  still carved out of the tool-frame filter once it is no longer auto-created?
- Is the contract uniform across all frame-scoped ops, or differentiated
  (framework/tool/SSR require-explicit; user dispatch/subscribe
  ambient-until-2nd-app-frame)? (See §2.)
- Registration-time: do boot-time `reg-*` no-`:frame` forms require explicit
  `:frame`? (See §6.)

## Recommendation

Adopt explicit frame target resolution now, while re-frame2 is still pre-alpha.

This is a large migration, but the rule is simple:

> If code cannot say which frame it means, it is not allowed to touch a frame.

That rule makes frames a real isolation primitive for applications, SSR, Story,
Xray, pair tooling, and future resource/work-ledger features.
