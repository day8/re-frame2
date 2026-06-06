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

## Recommendation

Adopt explicit frame target resolution now, while re-frame2 is still pre-alpha.

This is a large migration, but the rule is simple:

> If code cannot say which frame it means, it is not allowed to touch a frame.

That rule makes frames a real isolation primitive for applications, SSR, Story,
Xray, pair tooling, and future resource/work-ledger features.
