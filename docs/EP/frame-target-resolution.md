# EP: Explicit Frame Target Resolution

Status: proposal

Type: Standards Track

Date: 2026-06-06

Created: 2026-06-06

Target Artifact: `day8/re-frame2-core`

Target API Surface:

- frame resolution
- dispatch and subscribe
- root frame providers
- framework effects
- SSR and hydration
- Xray, Story, pair tooling, and AI tool accessors

Requires:

- [Spec 002 - Frames](https://github.com/day8/re-frame2/blob/main/spec/002-Frames.md)
- [Spec 004 - Views](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md)
- [Spec 006 - Reactive Substrate](https://github.com/day8/re-frame2/blob/main/spec/006-ReactiveSubstrate.md)
- [Spec 009 - Instrumentation](https://github.com/day8/re-frame2/blob/main/spec/009-Instrumentation.md)
- [Spec 011 - SSR](https://github.com/day8/re-frame2/blob/main/spec/011-SSR.md)
- [Spec 014 - HTTP Requests](https://github.com/day8/re-frame2/blob/main/spec/014-HTTPRequests.md)
- [Runtime Architecture](https://github.com/day8/re-frame2/blob/main/spec/Runtime-Architecture.md)
- [Tool Pair](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md)
- [Xray API](../xray/api/index.md)
- [App/Runtime Partition EP](app-db-runtime-partition.md)

Benchmark References:

- TanStack Query `QueryClientProvider`
- React Redux `Provider`
- Apollo Client `ApolloProvider`
- Relay `RelayEnvironmentProvider`
- re-frame2 frames, SSR request frames, Xray, Story, and pair tools

## Abstract

This enhancement proposes removing the ambient `:rf/default` fallback from
frame-scoped operations.

Today many public and internal APIs resolve a missing frame through this chain:

```clojure
explicit frame -> dynamic frame -> React context -> :rf/default
```

That preserves re-frame v1 ergonomics, but it makes frame isolation depend on
absence. A call that loses its frame context does not fail; it silently targets
the host's default app frame. That is especially dangerous around async
callbacks, React portals, Xray, Story, SSR request frames, test fixtures, pair
tools, AI tools, and multi-frame applications.

The proposed rule is:

> A frame-scoped operation must resolve a frame from explicit frame context.
> Absence is an error. No operation may synthesize `:rf/default` from missing
> context.

`:rf/default` remains a legal explicit frame id. It is not a fallback target,
not created just because `init!` ran, and not the bottom tier of
`current-frame` resolution.

The goal is not to make re-frame2 more ceremonial. The goal is to make frame
identity a best-in-class isolation capability: explicit, inspectable,
tool-safe, SSR-safe, replayable, and easy for programmers and AI agents to
reason about at scale.

## Motivation

Frames are the public isolation primitive for:

- app-db;
- runtime-db;
- event routing;
- subscription caches;
- machine snapshots;
- route state;
- managed HTTP and future resource state;
- SSR request state;
- Story and test frames;
- Xray/tool state;
- trace and epoch history;
- privacy and elision policy.

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
- An AI tool invokes a mutating operation without a selected target frame and
  accidentally changes the default app instead of refusing the request.

The current code has diagnostics for some cases, but they are partial:

- dispatch fallthrough warnings are development-only;
- they only cover a subset of dispatch paths;
- subscribe fallthrough has a separate plain-function warning path;
- a handler that happens to exist on `:rf/default` can hide the bug;
- production builds keep the fallback behavior after warnings are elided;
- tool and SSR surfaces need stronger refusal semantics than a warning.

The smell is the same family as the app/runtime partition EP: a load-bearing
boundary is protected by convention and late diagnostics rather than by the
shape of the API.

## Goals

The frame target resolution work should:

- make frame identity explicit at every frame-scoped boundary;
- remove implicit fallback from missing context to `:rf/default`;
- preserve ergonomic ambient use when a real frame context exists;
- make async callbacks carry frame identity deliberately;
- make SSR request frames and client hydration impossible to confuse with a
  conventional process-global frame;
- make Xray, Story, pair tools, and AI tools distinguish their own frame from
  the target frame they inspect or mutate;
- make no-frame failures structured, traceable, and visible even though the
  error itself is frameless;
- keep privacy/elision fail-closed when no frame policy is available;
- update docs, skills, migration advice, examples, and conformance fixtures so
  agents and humans learn the same rule.

## Non-Goals

This EP should not:

- remove frames;
- ban `:rf/default` as an explicit user-selected frame id;
- require every in-view call to pass `{:frame ...}` when a valid root/provider
  context already exists;
- make Xray or pair tools unable to discover frames;
- solve app/runtime partitioning by itself;
- introduce backwards-compatibility shims for v1-style frameless calls.

## Developer And AI Use Cases

The feature should help programmers and AI maintainers answer concrete
questions:

- Which frame does this operation target?
- Did this event mutate the frame that rendered the view?
- Did an async callback preserve frame identity across the boundary?
- Is this SSR render using a request-local frame?
- Is Xray reading its host frame or its own UI frame?
- Can an AI tool mutate without an explicit selected target?
- When a value crosses an egress boundary, which frame's elision policy applies?
- Can replay, epoch inspection, and conformance tests prove that no operation
  silently repaired absence by selecting `:rf/default`?

Features that do not improve those answers should be treated as secondary.

## Benchmark Standard And Prior Art

This EP uses mature SPA libraries as a benchmark for explicit runtime
ownership. The comparison is not one-to-one; re-frame2 frames are broader than a
React context provider. The useful prior-art pattern is that serious runtime
containers are selected deliberately at the root or request boundary, then made
available to descendants through explicit context.

### TanStack Query

TanStack Query uses a `QueryClient` supplied through `QueryClientProvider`.
Queries and mutations do not guess a process-global query client when the
provider is absent. That explicit client boundary is part of why a query cache
can be scoped, hydrated, tested, and inspected.

The frame-target lesson for re-frame2 is:

- root-owned runtime containers should be explicit;
- provider context is valid ambient context;
- missing provider context should be a configuration error, not a silent write
  to a conventional global target.

re-frame2 should exceed the benchmark by making the target frame visible not
only to views, but also to events, fxs, SSR, Xray, Story, pair tools, trace
records, and AI tool contracts.

### React Redux

React Redux uses a `Provider` to make a store available to descendants. That
model makes store ownership explicit at the application root while preserving
ergonomic reads and dispatches inside the tree.

The frame-target lesson for re-frame2 is that root ceremony is acceptable when
it buys a durable boundary. The re-frame2 version should go further by allowing
multiple independent app, story, test, SSR, and tool frames in one process.

### Apollo And Relay

Apollo's `ApolloProvider` supplies a configured client through React context.
Relay's environment provider supplies a Relay environment to descendant hooks.
Both libraries treat cache/network ownership as an explicit environment, not as
an accidental global fallback.

The frame-target lesson for re-frame2 is that request/client/environment
identity is part of correctness. For SSR and hydration, choosing the wrong
runtime container can leak data, double-fetch, or render stale state.

### re-frame2 Opportunity

The benchmark libraries make provider ownership explicit mostly for view-layer
hooks. re-frame2 can make frame ownership stronger:

- event-causal operations can carry frame ids in dispatch envelopes;
- framework fxs can reject malformed frameless execution;
- SSR request frames can be naturally isolated;
- Xray can distinguish its own frame from the inspected host frame;
- privacy and elision can fail closed without borrowing another frame's policy;
- AI tool access can require an operating frame before mutation;
- epoch records and replay can verify the target frame as data.

That is the standard this EP sets. Matching the benchmark means no accidental
global fallback. Exceeding the benchmark means frame identity is part of the
whole runtime and tool model, not just the React provider tree.

## Design Rationale

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

If no source is available, the operation fails with
`:rf.error/no-frame-context`.

For this EP, "frame-scoped operation" means an API that reads, writes, clears,
registers, projects, or dispatches against frame-local app-db, runtime-db,
subscription cache, route, machine, HTTP, SSR, trace, epoch, mark, or elision
state. Process-global registrar queries such as `frame-ids`, `frame-meta`, and
`registrations` remain frame-neutral enumeration surfaces; they must not invent
a current frame, and they must not be forced through this resolver.

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

- `init!` installs adapters and runtime capabilities; it does not guarantee
  `:rf/default` exists;
- React context default is absence, not `:rf/default`;
- missing `:rf.frame/id` in framework fx context is an internal error, not a
  request to use `:rf/default`;
- docs should stop describing `:rf/default` as the frame you get when no frame
  is supplied.

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

Managed HTTP replies, timers, browser callbacks, websocket callbacks, machine
delays, route listeners, and future resource-query callbacks must capture the
frame at initiation. They must not rediscover a frame after the cascade has
ended.

Tool eval surfaces follow the same rule. Wrapping an `eval-cljs` form in
`with-frame` supplies a frame only for the synchronous evaluation of that form.
If the evaluated form returns a Promise or installs a callback, the continuation
must capture a frame handle or frame-bound function explicitly.

### Registration-Time Resolution

Registration-time frame resolution is distinct from operation-time resolution.

Some current registration surfaces resolve their target frame at namespace load
or boot time through `(or frame (frame/current-frame))`. Under this EP, a
registration that writes frame-local metadata must either:

- receive an explicit frame;
- run inside `with-frame`;
- run inside a frame `:on-create` hook;
- be classified as a genuinely global registration rather than frame-local
  state.

This rule applies to frame-local schemas, flows, elision declarations, HTTP
interceptors, and any future frame-local registrar side table. Boot-time
namespace loading is not a valid reason to select `:rf/default`.

### Views And Root Mounts

Application roots must establish a frame provider deliberately.

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

Xray may mount its own frame lazily, but it should not default the inspected
host target to `:rf/default`. A host can pass a target:

```clojure
(xray/init! {:own-frame    :rf/xray
             :target-frame :app/main})
```

or Xray can show an unselected-target state until the frame picker chooses one.
If Xray wants to auto-select a sole app frame, that should be modeled as an
explicit discovery policy in Xray or Tool-Pair, not as core `:rf/default`
fallback.

This is a deliberate vocabulary change from today's Xray facade, where
`init!` accepts `:default-frame`, `target-frame` reads through
`default-target-frame`, and `set-target-frame! nil` resets to `:rf/default`.
The migration should split Xray's own frame from the inspected target frame and
make `nil` mean unselected unless a host or discovery policy explicitly selects
a target.

Pair-MCP and AI tool APIs should reject mutating operations when no target frame
is selected. Reads may return structured `:rf.tool/no-frame-selected` data for
UX, but they should not read `:rf/default` by convention.

### SSR

SSR request and hydration APIs should require a frame.

```clojure
(ssr/hydrate! {:frame :app/main
               :render-tree-fn render-root})

(streaming-client/install! {:frame :app/main})
```

Server request frames are already naturally explicit. Client hydration should
match that discipline so duplicate fetches, head projection, hydration deltas,
and error projection all land on the intended frame.

Hydration payloads may carry `:rf/frame-id`, but that value is payload metadata
and validation evidence, not a no-opts target resolver. A host that wants the
payload frame to be the client target must pass that frame explicitly to
`hydrate!`, the root provider, streaming `install!`, resource preload, and Xray.
If an explicit client target conflicts with the payload's frame id, hydration
should surface a structured mismatch instead of silently choosing either side.

### Routes And URL Ownership

Routing has two frame identities:

- the event target frame for route handlers, route subscriptions, nav tokens,
  can-leave checks, and scroll restoration;
- the browser URL owner frame that receives popstate and is allowed to run
  `:rf.nav/push-url` or `:rf.nav/replace-url`.

Today the URL-owner contract has a structural `:rf/default` anchor: the default
frame owns the URL unless it opts out, and history listeners dispatch URL
changes to `url-owner-frame-id`. Under this EP, URL ownership must become an
explicit host/bootstrap policy, not another absence repair. An app bootstrap may
declare one URL-owning frame, but the routing runtime must not infer
`:rf/default` when no owner is declared.

Route transitions, `:rf.route/handle-url-change`, `:rf.route/transitioned`,
navigation tokens, can-leave restoration, scroll fxs, and history listeners
must all thread the selected frame. A missing URL owner should be a routing
configuration error or `:rf.error/no-frame-context`, and browser-originated
callbacks must capture the owner frame at listener installation or resolve it
through an explicit routing owner policy.

### Machines And Managed Effects

Framework effects that run inside a cascade should inherit the frame from the
fx context. Missing frame context in a lifecycle-critical fx is malformed
runtime state.

```clojure
{:rf.machine/spawn {...}} ;; frame comes from the dispatch envelope
```

After the app/runtime partition EP's coeffect rename, this inherited key should
be `:rf.frame/id`, not legacy `:frame`.

If an fx handler is called without a frame id, it should emit or throw
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

No-frame errors are themselves frameless. They need an always-on error-emission
path, not only per-frame epoch capture.

## Specification

### Central Resolver

The central frame APIs should have these semantics:

```clojure
(frame/current-frame)
;; lexical dynamic frame, or nil

(frame/resolve-current-frame)
;; dynamic frame, adapter/React-context frame, or nil

(frame/require-current-frame! operation payload)
;; frame id, or raises/emits :rf.error/no-frame-context
```

`frame/current-frame` and `frame/resolve-current-frame` are readers. They do not
repair absence. Call sites that require a frame must call a require helper or
otherwise produce a structured error.

The low-level readers may return nil so detection, frame pickers, and tooling
can model "no context" without throwing while they decide how to present the
state. Public frame-scoped operations are not low-level readers. `rf/dispatch`,
`rf/subscribe`, `rf/current-frame-id`, no-arg `rf/frame-handle`, no-arg
`rf/frame-bound-fn*`, and context-defaulting read/clear helpers should call the
require helper and fail outside context. This keeps the nil-returning resolver
from becoming a second, softer fallback contract.

Suggested error payload:

```clojure
{:rf.error/id :rf.error/no-frame-context
 :operation   :dispatch
 :where       're-frame.router/dispatch!
 :event-id    :todo/add
 :recovery    :supply-frame}
```

Resolution must fail before frame registry lookup. A missing frame context must
not be misreported as `:rf.error/frame-destroyed` for a synthesized
`:rf/default`.

The distinct explicit-but-missing case stays distinct: when a caller supplies
`{:frame :ghost}` or `:ghost` explicitly, resolution has succeeded and the
registry lookup may report `:rf.error/frame-destroyed` or another no-such-frame
shape. `:rf.error/no-frame-context` is reserved for absence of a target, not for
a bad explicit target.

### Dispatch And Router

Router envelope construction should follow this order:

1. explicit `{:frame ...}` wins;
2. otherwise require a resolved current frame;
3. no frame means no enqueue;
4. cross-frame dispatch diagnostics remain separate from missing-frame errors.

Remove:

- `:fell-through-to-default?`;
- the async-callback fallthrough warning category;
- the schema and instrumentation vocabulary that describes fallback as a
  successful routed state.

### Subscriptions And Read Helpers

Update:

- one-arity `subscribe`;
- one-arity `subscribe-once`;
- one-arity `unsubscribe`;
- `snapshot-of`;
- no-arg `sub-cache`;
- zero-arity `clear-sub-cache!`;
- `current-frame-id`;
- no-arg `frame-handle`;
- no-arg `frame-bound-fn*`;
- one-arity `machine-by-system-id`;
- `sub-machine`;
- `machine-has-tag?`.

Context-defaulting forms can remain only as context readers or operations. They
should fail when used outside context.

### Root, View, And Adapter Surfaces

Update React context and view providers:

- shared React context default becomes a no-provider sentinel, not
  `:rf/default`;
- corrupted context reports corruption or no-frame context deliberately;
- `frame-provider` requires `:frame`;
- the shared substrate spine must not repair missing frame props with
  `:rf/default`;
- `reg-view` continues to wire context;
- plain Reagent function warning becomes a sharper no-frame-context path.

### Framework Effects And Runtime Subsystems

Remove defensive `:or {frame-id :rf/default}` defaults and literal
`(or ... :rf/default)` fallbacks from framework fxs and runtime helpers:

- machine spawn/destroy/update-snapshot/timers;
- `machine-by-system-id`, `dispatch-to-system`, and
  `:rf.machine/dispatch-to-system`;
- HTTP interceptor registration and clearing;
- managed HTTP request and reply paths;
- route and navigation fxs;
- URL-owner resolution;
- history listener popstate dispatch;
- nav-token, can-leave, and scroll-restoration helpers;
- flows;
- resource query fxs;
- runtime partition writes.

Framework fxs invoked from a cascade should already receive the envelope frame.
If they do not, that is an invariant failure worth surfacing.

### SSR And Head

Update SSR APIs:

- `hydrate!` requires `:frame`;
- streaming client `install!` requires `:frame`;
- `active-head` no-arg form is removed or made context-required;
- payload `:rf/frame-id` is validated against the explicit target, not used as
  an implicit fallback;
- server examples create request frames explicitly;
- client examples pass the same frame into hydrate, root provider, resources,
  and Xray.

### Tooling

Tooling needs explicit own-frame and target-frame semantics:

- Xray own frame is explicit, commonly `:rf/xray`;
- Xray target frame starts unselected unless host config or explicit discovery
  policy selects it;
- Xray `init!` migrates from `:default-frame` toward distinct `:own-frame` and
  `:target-frame` vocabulary;
- Xray's own singleton `default-frame-id` (`:rf/xray`) remains separate from
  the inspected-host `default-target-frame` migration;
- Xray `target-frame` and `set-target-frame! nil` stop resetting through
  `default-target-frame`;
- pair-MCP operating-frame resolution must be reconciled with this EP;
- the pair CLI shim and preflight/discover hints must stop teaching
  `rf/init!` as a way to create `:rf/default`;
- pair precheck caches keyed on omitted frame must invalidate when a frame is
  explicitly selected;
- `eval-cljs` no-frame behavior must stop documenting `:rf/default` as the
  target;
- `eval-cljs {:frame ...}` remains a synchronous lexical binding only; async
  continuations inside the evaluated form still need explicit frame capture;
- AI mutation tools require an operating frame;
- read tools may return structured no-target data. Existing pair-MCP
  `:ambiguous-frame` shapes may remain tool-local, but they must be mapped
  deliberately against `:rf.tool/no-frame-selected` and
  `:rf.error/no-frame-context`;
- tool specs and conformance fixtures must change in the same work as the
  implementation.

### Privacy And Egress

Frame-qualified elision policy may be used only when a frame is known.

Frameless egress should:

- redact conservatively;
- omit values that require frame policy;
- or return structured data saying projection cannot be done safely.

It must not borrow `:rf/default` marks.

## Examples

### Explicit Application Root

```clojure
(def app-frame :app/main)

(rf/reg-frame app-frame
  {:on-create [:app/boot]})

(rf/init! reagent/adapter)

(mount-root!
  [rf/frame-provider {:frame app-frame}
   [app]])
```

### Async Callback

```clojure
(rf/reg-event-fx
  :profile/load-clicked
  (fn [cofx _]
    (let [frame-id (:rf.frame/id cofx)
          dispatch (:dispatch (rf/frame-handle frame-id))]
      {:fx [[:promise
             {:work       #(fetch-profile)
              :on-success #(dispatch [:profile/loaded %])}]]]})))
```

### SSR Hydration

```clojure
(ssr/hydrate!
  {:frame :app/main
   :render-tree-fn
   (fn []
     [rf/frame-provider {:frame :app/main}
      [app]])})
```

### Xray Host Target

```clojure
(xray/init!
  {:own-frame    :rf/xray
   :target-frame :app/main})
```

### Missing Context

```clojure
(rf/dispatch [:todo/add "Milk"])
;; => :rf.error/no-frame-context
```

## Alternatives Considered

### A. Keep Status Quo

Keep `:rf/default` as the universal bottom tier and rely on warnings.

Pros:

- lowest short-term churn;
- preserves re-frame v1 style examples.

Cons:

- wrong-frame writes remain possible;
- diagnostics are partial and often dev-only;
- tool and SSR boundaries inherit app-frame convenience semantics;
- this conflicts with the pre-alpha "build it right" posture.

### B. Role-Aware Compatibility Fallback

Keep fallback to `:rf/default` when it is the only live app frame, excluding
tool frames such as `:rf/xray` from ambiguity.

Pros:

- preserves single-app ergonomics;
- handles the common "app plus Xray" case.

Cons:

- requires frame roles before solving the immediate problem;
- still lets missing context proceed;
- creates more policy: app frame vs tool frame vs Story vs SSR vs test;
- harder to teach than "no frame context, no operation."

### C. Explicit Frame Context, No Default Fallback

Remove fallback completely. A frame may be ambient, but only when a root,
provider, cascade, or lexical binding supplied it explicitly.

Pros:

- strongest frame isolation;
- easiest rule to teach;
- makes async and tooling boundaries honest;
- matches explicit provider/client/environment patterns in benchmark libraries;
- fits pre-alpha no-compatibility posture.

Cons:

- large repo-wide migration;
- many tests, docs, examples, tools, and skills currently assume
  `:rf/default`;
- requires better root/bootstrap examples;
- revokes the earlier single-frame invisibility goal.

Recommendation: **Option C**.

## Backwards Compatibility

This is an intentionally breaking pre-alpha change.

The EP revokes this earlier goal from Spec 002 and the frame guide:

> Frame plurality is invisible to single-frame apps.

That goal was valuable while re-frame2 was optimizing for migration comfort. It
is less valuable than frame correctness once SSR, Story, Xray, pair tools, AI
tooling, resource management, managed effects, and runtime partitions all depend
on frame isolation.

Migration should be mechanical:

- choose an application frame id;
- register it explicitly;
- install a root provider;
- wrap tests in `with-frame` or pass `{:frame ...}`;
- capture frame handles for async callbacks;
- select an operating frame for tools;
- replace docs and skills that teach implicit `:rf/default`.

A migration may choose `:rf/default` as the explicit id. The runtime will not
infer it.

## Security And Privacy

This EP is security-relevant because frame target resolution controls which
runtime policy applies to egress, logs, traces, and AI tool payloads.

The security rule is:

> When no frame policy is available, do not borrow another frame's policy.

For human-facing tools, this prevents misleading attribution and wrong-frame
inspection. For AI or off-box egress, it prevents sensitive values from being
projected under the wrong elision registry.

No-frame errors must use an always-on error path so they are observable without
requiring a per-frame epoch target.

## Reference Implementation Plan

This should be implemented as a planned migration, not one giant patch. The
order matters because the fallback is embedded in docs, tests, tooling, and
developer education.

### 1. Contract And Inventory

Create beads for:

- central frame resolver contract;
- dispatch/router migration;
- subscription/read API migration;
- React context and adapter migration;
- registration-time frame-local surfaces;
- framework fx migration;
- route URL-owner migration;
- SSR/head/hydration migration;
- Xray/Story/tool target-frame migration;
- trace/elision projection migration;
- docs/API/migration/skills updates;
- conformance tests and examples cleanup.

Each bead should explicitly state whether it removes fallback, updates call
sites, updates tests, or updates documentation.

### 2. Central Frame Resolver

Change central frame APIs before touching callers:

- `frame/current-frame` returns the lexical dynamic frame or nil;
- `frame/resolve-current-frame` returns dynamic or React-context frame, or nil;
- add `frame/require-current-frame!`;
- remove `ensure-default-frame!` as an `init!` side effect;
- decide whether any test-only default-frame fixture survives;
- define the structured error payload for missing context.

### 3. Dispatch And Router

Update router envelope construction:

- explicit `{:frame ...}` wins;
- otherwise require a resolved current frame;
- no frame means no enqueue;
- remove `:fell-through-to-default?`;
- replace fallthrough warnings with missing-frame errors;
- preserve cross-frame dispatch diagnostics separately;
- emit no-frame errors before frame registry lookup.

Tests should cover:

- bare dispatch outside context fails;
- dispatch under `with-frame` works;
- dispatch under frame-provider works;
- async bare dispatch after context unwinds fails;
- frame-bound dispatch after context unwinds works;
- explicit `{:frame :rf/default}` works only if that frame exists.

### 4. Subscriptions And Read Helpers

Update read surfaces:

- one-arity `subscribe`, `subscribe-once`, and `unsubscribe`;
- `snapshot-of`;
- no-arg `sub-cache`;
- zero-arity `clear-sub-cache!`;
- `current-frame-id`;
- no-arg `frame-handle`;
- no-arg `frame-bound-fn*`;
- `machine-by-system-id`, `sub-machine`, and `machine-has-tag?`.

Tests should cover wrong-frame prevention for reads as well as writes.

### 5. Root, View, And Adapter Surfaces

Update React context and view providers:

- shared React context default becomes nil or a sentinel, not `:rf/default`;
- corrupted context reports absence or corruption deliberately;
- `frame-provider` requires `:frame`;
- shared substrate spine and adapter provider code stop repairing missing props;
- `reg-view` continues to wire context;
- plain Reagent function warning becomes a sharper no-frame-context path.

Docs and examples should teach root frame declaration first, then normal app
views.

### 6. Registration-Time Frame-Local Surfaces

Update no-frame `reg-*` forms that currently resolve a frame at registration
time:

- `reg-flow`;
- `reg-app-schema`;
- schema population helpers;
- elision declaration helpers;
- HTTP interceptor registration and clearing;
- future frame-local resource registrations.

Each surface must be classified as one of:

- global registration;
- explicit frame-local registration;
- context-required frame-local registration.

### 7. Framework Effects And Runtime Subsystems

Remove defensive defaults from framework fxs and runtime helpers:

- machine lifecycle fxs;
- `machine-by-system-id`, `dispatch-to-system`, and the
  `:rf.machine/dispatch-to-system` fx;
- managed HTTP request initiation and reply delivery;
- route and navigation fxs;
- URL-owner declaration, history listener dispatch, nav tokens, can-leave, and
  scroll restoration;
- flows;
- resource query fxs;
- runtime partition writes.

Sequence this with the app/runtime partition EP so framework fxs use
`:rf.frame/id` as the inherited frame key.

### 8. SSR And Head

Update SSR APIs:

- `hydrate!` requires `:frame`;
- streaming client `install!` requires `:frame`;
- `active-head` no-arg form is removed or made context-required;
- payload `:rf/frame-id` is validated against the explicit target, not used as
  an implicit fallback;
- server examples create request frames explicitly;
- client examples pass the same frame into hydrate, root provider, resources,
  and Xray.

### 9. Trace, Marks, And Elision

Update projection rules:

- no default frame for old or frameless trace events;
- redaction against a frame requires a frame-qualified policy;
- off-box egress without a frame policy fails closed;
- frameless boot/registration events are labelled as frameless rather than
  attributed to `:rf/default`;
- Xray can display frameless events separately;
- no-frame errors are emitted through the always-on error axis.

This step should coordinate with the app/runtime partition EP because elision
state is moving into runtime-db.

### 10. Xray, Story, Pair Tools, And Skills

Update tool surfaces:

- Xray mounts `:rf/xray` explicitly;
- Xray target frame starts as unselected unless host config or explicit
  discovery policy selects it;
- Xray `:default-frame`, `default-target-frame`, `target-frame`, and
  `set-target-frame! nil` are migrated together so there is no hidden
  `:rf/default` reset path;
- Xray frame picker drives target selection;
- panels that need host data refuse to read until selected;
- Xray runtime and pair-MCP mutation tools require explicit `:frame`;
- `discover-app` reports available frames and suggests a target, but does not
  mutate or read by falling back;
- Story variant frames and Story-to-Xray handoff pass explicit frame ids;
- Tool-Pair operating-frame resolution is reconciled with this contract.

Update skills:

- `skills/re-frame2`;
- `skills/re-frame2-setup`;
- `skills/re-frame2-xray`;
- `skills/re-frame2-pair`;
- `skills/re-frame2-implementor`;
- `skills/re-frame-migration`;
- docs skills that mention default frame behavior.

### 11. Docs, API, Migration, And Examples

Update:

- `docs/guide/18-frames.md`;
- `docs/guide/09*`, `14*`, `16*`, `19*`, `23*`, `25*` where they teach
  default frame convenience;
- `docs/xray/api/mount-control.md`;
- `docs/xray/api/config-keys.md`, `docs/xray/api/reference.md`, and
  `docs/xray/api/runtime-seam.md`;
- `docs/api/01-core.md`, `docs/api/04-machines.md`,
  `docs/api/05-flows.md`, `docs/api/07-http.md`,
  `docs/api/09-ssr.md`, `docs/api/13-lifecycle.md`, and
  `docs/api/14-adapters.md`;
- `docs/migration/from-re-frame-v1/README.md`;
- `spec/002-Frames.md`;
- `spec/004-Views.md`;
- `spec/006-ReactiveSubstrate.md`;
- `spec/009-Instrumentation.md`;
- `spec/011-SSR.md`;
- `spec/014-HTTPRequests.md`;
- `spec/Runtime-Architecture.md`;
- `spec/Conventions.md`;
- `spec/Spec-Schemas.md`;
- `spec/Tool-Pair.md`;
- examples and testbeds.

The migration guide should stop saying "today's re-frame is re-frame2 with only
`:rf/default` in play." The new story is:

> re-frame2 requires an application frame. A migration may choose
> `:rf/default` as the explicit id, but the runtime will not infer it.

### 12. Tests And Conformance

Add a conformance sweep:

- no bare app operation outside frame context succeeds;
- no React context default equals `:rf/default`;
- no framework fx defaults missing frame to `:rf/default`;
- no zero-arity or one-arity read/clear helper repairs missing context with
  `:rf/default`;
- no SSR convenience API mutates `:rf/default` when `:frame` is absent;
- no Xray panel writes to the host frame because its own context was lost;
- no off-box egress redacts against `:rf/default` by fallback;
- explicit `:rf/default` still works when a test or app registers it.

Broaden static checks beyond the current narrow regex:

```powershell
rg ":or \\{frame-id :rf/default\\}|\\(or [^)]*:rf/default\\)" implementation
rg ":rf/default" docs skills tools implementation spec
```

The goal is not to ban the keyword. The goal is to ban using it as an absence
repair.

## Acceptance Criteria And Rollout

This EP is implemented when:

- there is no central fallback from missing frame context to `:rf/default`;
- dispatch, subscribe, SSR, framework fx, Xray, and tool accessors require a
  real frame target;
- `:rf/default` remains usable only when explicitly registered and selected;
- no-frame errors are structured and visible through an always-on error path;
- privacy and elision fail closed when no frame policy is available;
- docs and skills teach explicit frame context;
- tests prove that losing frame context fails rather than mutating or reading
  another frame.

Rollout should be sequential because hot-zone specs, tools, docs, and tests all
reference the old contract. Do not dispatch implementation beads in parallel
across the same spec and core-runtime files without a coordinator.

## Open Decisions

1. Should `init!` stop creating any frame, or should a separate app bootstrap
   helper create a frame explicitly from opts?
   Recommendation: `init!` should not create `:rf/default`; an app bootstrap
   helper may create a named frame explicitly.
2. Should missing frame context throw synchronously everywhere, or should some
   read/tool surfaces return structured error data?
   Recommendation: mutating operations throw or emit structured errors; tool
   reads may return structured no-target data.
3. Should Xray or Tool-Pair auto-select the sole non-tool app frame?
   Recommendation: only as an explicit discovery policy outside the core
   resolver.
4. Should `:rf/default` remain reserved as a framework-known conventional id,
   or become just another explicit user-selected keyword?
   Recommendation: keep it reserved only if the reservation buys migration or
   docs clarity; otherwise make it ordinary.
5. Should migration tooling rewrite bare v1 calls into a `with-frame` root, or
   into explicit `{:frame :rf/default}` call sites?
   Recommendation: prefer root/provider wrapping; use explicit call-site frames
   for async callbacks, tests, tools, and SSR.
6. How does `:rf.error/no-frame-context` route so a frameless error is still
   surfaced?
   Recommendation: always-on error emission, not per-frame epoch only.
7. Do boot-time frame-local `reg-*` forms require explicit `:frame`?
   Recommendation: yes, unless the surface is classified as globally registered.

## Bead Structure

1. Decision/spec bead: accept explicit frame target resolution and update the
   normative spec language.
2. Resolver bead: change `frame/current-frame`, `resolve-current-frame`,
   React context default, and missing-frame error shape.
3. Router bead: remove dispatch fallback and fallthrough diagnostics; add
   no-frame dispatch tests.
4. Subs/read bead: remove subscribe/read fallback; add no-frame read tests.
5. Root/view bead: update frame-provider, reg-view docs, shared substrate spine,
   examples, and adapter tests.
6. Registration bead: classify and migrate frame-local `reg-*` surfaces.
7. Fx/runtime bead: remove fallback defaults from machines, HTTP, route, flow,
   resource-related fxs, URL-owner logic, history listener dispatch, nav-token
   helpers, can-leave restoration, and scroll restoration.
8. SSR bead: require frame in hydration, streaming, head, and request examples.
9. Elision/trace bead: remove default-frame attribution from projection and
   fail closed for frameless egress.
10. Xray/Story/tool bead: require explicit own-frame and target-frame selection;
    update pair-MCP accessors and Tool-Pair specs.
11. Docs/skills bead: update API docs, specs, migration guide, examples, and
    skills.
12. Conformance bead: add static and dynamic checks that prevent regression.

## Audit Evidence

Four read-only audits on 2026-06-07 cross-checked this EP against the current
codebase. The EP's direction is sound, but the following implications must stay
visible in implementation beads.

### 1. Tool-Pair Operating-Frame Contract

re-frame2 already has a four-tier operating-frame resolution contract for tools:

- [`spec/Tool-Pair.md:394-409`](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md)
- `skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs:264-312`

Two parts collide with this EP:

- tier 3 auto-selects the sole app frame from absence;
- `:rf/default` is a privileged app frame carved out from the `:rf/*` tool-frame
  exclusion.

The pin layer `reset-operating-frame` is already closer to the desired behavior:
it re-resolves and refuses ambiguity rather than falling back.

Implementation must reconcile `:rf.error/no-frame-context`,
`:ambiguous-frame`, and `:rf.tool/no-frame-selected`.

### 2. Revoked Single-Frame Ergonomic Goal

The EP overrides a stated design goal:

- `spec/002-Frames.md:21`: frame plurality is invisible to single-frame apps;
- `docs/guide/18-frames.md:51,82`: most users never type `frame` and
  `:rf/default` costs nothing.

This revocation should be explicit. The strongest principled support is
`spec/Principles.md` low-hidden-context guidance and `spec/AI-Audit.md`, which
already treats plain-function `:rf/default` routing as a gap to remove or make
loud.

### 3. Normative Always-Present Statements

These files contain normative statements that `:rf/default` always exists or is
the deliberate fallback:

| File | Statements to rewrite |
|---|---|
| `spec/Runtime-Architecture.md` | `:rf/default` always present; boot guarantees it. |
| `spec/006-ReactiveSubstrate.md` | React context default and adapter conformance require fallback. |
| `spec/002-Frames.md` | frame model, API-at-a-glance, priority list, reference implementation, and edge-case table. |
| `spec/Conventions.md` | universal default frame id reservation. |
| `spec/Tool-Pair.md` | always-pre-registered premises. |
| `spec/Ownership.md`, `spec/README.md` | ownership/index rows naming fallback as a contract. |

### 4. Fallthrough Warning Vocabulary

The fallthrough warning vocabulary is deleted, not edited. Retire it coherently
from:

- `spec/Spec-Schemas.md`;
- `spec/Conventions.md`;
- `spec/Security.md`;
- `spec/009-Instrumentation.md`;
- implementation warning tests.

The old vocabulary includes
`:rf.warning/dispatch-from-async-callback-fell-through-to-default` and schema
terms that describe routing to `:rf/default` as an expected outcome.

### 5. Core Runtime Gaps

Core runtime issues to handle:

- React-context corruption detector currently depends on `:rf/default` being a
  keyword-like default;
- no-frame errors are frameless and need always-on error emission;
- missing context must fail before registry lookup so it does not become
  `:rf.error/frame-destroyed`;
- elision and marks projection synthesize `:rf/default` and must fail closed;
- `ensure-default-frame!` is called by both `init!` and the test fixture.

### 6. Registration-Time Resolution

These registration-time surfaces resolve frame at namespace load or boot:

- `flows/registry.cljc`;
- `schemas/storage.cljc`;
- elision schema-population helpers;
- elision declaration helpers.

They need the registration-time rule in this EP, not only the operation-time
rule.

### 7. Feature Artifact Specifics

Feature-specific sites that need explicit migration:

- routing URL ownership currently has a structural `:rf/default` anchor in
  `url-owner-frame-id`, `url-bound?` exclusivity, history listener dispatch,
  nav-token finalization, can-leave restoration, and scroll restoration;
- literal `(or ... :rf/default)` idioms outnumber `:or {frame-id :rf/default}`;
- shared substrate spine has a frame-provider fallback used by multiple React
  adapters;
- managed HTTP request/reply paths, interceptor registration/clearing, and test
  stubs have frame defaults that must be migrated together;
- `machine-by-system-id` uses `frame/current-frame` in its one-arity form and
  feeds `dispatch-to-system`;
- `sub-machine` and `machine-has-tag?` delegate to no-frame subscribe paths.

### 8. App/Runtime Partition Sequencing

The app/runtime partition EP renames the cofx frame key toward
`:rf.frame/id`. This EP's framework-fx work should sequence with that rename
and use `:rf.frame/id` in examples and implementation.

### 9. Tooling Surfaces

Tooling surfaces to enumerate:

- Xray `default-target-frame`;
- Xray `defaults/default-frame-id` for the shell frame, which must stay distinct
  from inspected-target migration;
- Xray target-frame subscriptions and reset behavior;
- Xray `init! {:default-frame ...}` versus proposed `:target-frame`;
- Xray mount and spine defaults;
- `eval-cljs` no-frame documentation;
- `eval-cljs {:frame ...}` Promise/async documentation;
- pair-MCP conformance snapshots that bake in `:rf/default`;
- `discover-app` hint that says `init!` registers `:rf/default`;
- pair CLI shim hints in `skills/re-frame2-pair/scripts/ops.clj`;
- pair-MCP precheck cache keyed on omitted frame.

### 10. Docs And Skills

Docs and skills to update beyond the obvious specs:

- `docs/guide/18-frames.md`;
- `docs/guide/09*`, `14*`, `16*`, `19*`, `23*`, `25*`;
- `docs/api/01-core.md`, `04-machines.md`, `05-flows.md`, `07-http.md`,
  `09-ssr.md`, `13-lifecycle.md`, and `14-adapters.md`;
- `docs/xray/api/mount-control.md`;
- `docs/xray/api/config-keys.md`, `reference.md`, and `runtime-seam.md`;
- `skills/re-frame-migration/SKILL.md`;
- `skills/re-frame2-setup`;
- `skills/re-frame2-pair`;
- `skills/re-frame2-implementor`;
- `skills/re-frame-migration/references/auto-cross-cutting.md`.

The migration skill currently instructs agents to call
`frame/ensure-default-frame!`; that advice will become actively wrong.

### 11. Tests And Conformance To Invert

Existing positive assertions need inversion:

- `implementation/core/test/re_frame/dispatch_fallthrough_warn_test.clj`;
- `implementation/core/test/re_frame/dispatch_fallthrough_warn_dom_cljs_test.cljs`;
- `implementation/core/test/re_frame/views_current_component_cljs_test.cljs`;
- `implementation/core/test/re_frame/sub_cache_test.clj` zero-arity
  default-frame fixture expectations;
- conformance fixtures that stamp or expect `:frame :rf/default`.

Every conformance fixture must explicitly register and select its target frame.
Any fixture that dispatches framelessly and expects default stamping must be
reauthored.

## Sources Consulted

- Local source: `implementation/core/src/re_frame/frame.cljc`
- Local source: `implementation/core/src/re_frame/router.cljc`
- Local source: `implementation/core/src/re_frame/subs.cljc`
- Local source: `implementation/core/src/re_frame/core_machines.cljc`
- Local source: `implementation/machines/src/re_frame/machines.cljc`
- Local source: `implementation/routing/src/re_frame/routing/nav_fx.cljc`
- Local source: `implementation/routing/src/re_frame/routing/history.cljc`
- Local source: `implementation/routing/src/re_frame/routing/url_change.cljc`
- Local source: `implementation/routing/src/re_frame/routing/nav_token.cljc`
- Local source: `implementation/routing/src/re_frame/routing/can_leave.cljc`
- Local source: `implementation/routing/src/re_frame/routing/scroll.cljc`
- Local source: `implementation/http/src/re_frame/http_handlers.cljc`
- Local source: `implementation/http/src/re_frame/http_managed.cljc`
- Local source: `implementation/flows/src/re_frame/flows/registry.cljc`
- Local source: `implementation/ssr/src/re_frame/ssr/boot.cljc`
- Local source: `implementation/ssr/src/re_frame/ssr/streaming/client.cljs`
- Local source: `implementation/ssr/src/re_frame/ssr/head/registry.cljc`
- Local source: `tools/xray/src/day8/re_frame2_xray/core.cljs`
- Local source: `tools/xray/src/day8/re_frame2_xray/defaults.cljs`
- Local source: `skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs`
- Local source: `skills/re-frame2-pair/scripts/ops.clj`
- Local source: `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/eval_cljs.cljs`
- Local docs: `docs/api/01-core.md`
- Local docs: `docs/api/07-http.md`
- Local docs: `docs/api/13-lifecycle.md`
- Local docs: `docs/xray/api/mount-control.md`
- Local docs: `skills/re-frame-migration/SKILL.md`
- [TanStack Query: QueryClientProvider](https://tanstack.com/query/latest/docs/framework/react/reference/QueryClientProvider)
- [React Redux: Provider](https://react-redux.js.org/api/provider)
- [Apollo Client: ApolloProvider](https://www.apollographql.com/docs/react/api/react/ApolloProvider)
- [Relay: RelayEnvironmentProvider](https://relay.dev/docs/api-reference/relay-environment-provider/)
