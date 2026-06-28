# EP-0002: Explicit Frame Target Resolution

Status: final

> **Decision recorded 2026-06-09 (see [§Resolved Decisions](#resolved-decisions)).**
> **Option C** (remove the ambient `:rf/default` fallback) is **accepted**, and the
> EP is implemented **per the [Appendix reframing](#appendix-design-review-commentary--toward-a-smaller-more-carried-rule)** — the
> positive *carried-invariant* formulation, not the subtractive as-written body. The
> normative core of the reframed contract lives in
> [`spec/002-Frames.md` §Frame target resolution](../../spec/002-Frames.md#frame-target-resolution--the-carried-invariant).
> This EP lands the **foundation** — decision + normative spec core +
> chain decomposition; the downstream migration was tracked through the
> implementation ledger below.

## Implementation ledger

EP-0002's decisions froze before the repo-wide migration finished. The build
history is therefore recorded explicitly, following the final-with-ledger
pattern now codified by EP-0009.

### Open errata

None known.

### Resolved build records

- **Closed (PR #3662, merge commit `b1aa6808d`)** — accepted
  Option C via the appendix reframing, authored the normative Spec 002 carried
  invariant, decomposed and executed the downstream serial chain, completed the
  docs tail, completed the final correctness/completeness review, and closed
  after the 11/11 chain plus greenup rounds landed fully green.

### Deliberate divergences from the EP body

- **Missing URL owner is a documented NO-OP, not a config-error.** The EP body's
  §Routes And URL Ownership sketch (above) proposed that "a missing URL owner
  *should be* a routing configuration error or `:rf.error/no-frame-context`."
  The landed contract diverges deliberately: an app with **no** frame declaring
  `:url-bound? true` simply has **no URL owner** — outbound `:rf.nav/push-url` /
  `:rf.nav/replace-url` fxs no-op and the inbound `popstate` listener skips. This
  is a sanctioned routing-config no-op, **not** an error and **not** a
  default-frame write, normatively pinned at
  [012 §Multi-frame routing](../../spec/012-Routing.md#multi-frame-routing) and
  [012 §URL ownership is an explicit declaration](../../spec/012-Routing.md#url-ownership-is-an-explicit-declaration-no-default-frame-floor).
  The carried invariant only forbids *synthesising a default owner from absence*;
  having no owner at all is a coherent, intentional configuration (story-variant
  frames, devcards, per-test fixtures, SSR-per-request), so it stays silent
  rather than loud. `:rf.error/no-frame-context` is reserved for a frame-scoped
  *operation* reached with no frame established — registering zero URL owners is
  not such an operation. (The honest-divergence record follows the EP-0006
  pattern.)

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

## Relationships

This is a cross-cutting safety proposal that should resolve before large
frame-aware features harden their public APIs.

- **Resolve before frame-aware features.** This EP should be resolved before
  large frame-aware features — such as [resource queries](EP-0003-resource-queries.md),
  Xray control surfaces, SSR hydration helpers, and work-ledger tooling — harden
  their public APIs, because each of those features carries (or depends on) an
  explicit frame target and should not be built against the ambient
  `:rf/default` fallback this EP removes.
- **Composes with the app/runtime partition.** Both partitions in the
  [App/Runtime Partition EP](EP-0001-frame-partitions.md) are frame-owned;
  resolving the frame target precedes committing or projecting either partition.
- **Subscription inputs inherit the resolved frame.** Parametric subscription
  input query vectors are frame-agnostic data resolved in the outer
  subscription's frame; see the [Parametric Subscription Inputs
  EP](EP-0004-subscription-inputs.md).

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
(let [dispatch (:dispatch (rf/capture-frame :app/main))]
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
`rf/subscribe`, `rf/current-frame-id`, no-arg `rf/capture-frame`, no-arg
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
- no-arg `capture-frame`;
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
          dispatch (:dispatch (rf/capture-frame frame-id))]
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

## Rejected Ideas

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

The recommended option is **Option C** — explicit frame context, no default
fallback. See [Recommendation](#recommendation) below.

## Backwards Compatibility

This is an intentionally breaking pre-alpha change.

The EP revokes this earlier goal from Spec 002 and the frame guide:

> Frame plurality is invisible to single-frame apps.

That goal was valuable while re-frame2 was optimizing for migration comfort. It
is less valuable than frame correctness once SSR, Story, Xray, pair tools, AI
tooling, resource management, managed effects, and runtime partitions all depend
on frame isolation.

## Migration

Migration is mechanical:

- choose an application frame id;
- register it explicitly;
- install a root provider;
- wrap tests in `with-frame` or pass `{:frame ...}`;
- capture frame handles for async callbacks;
- select an operating frame for tools;
- replace docs and skills that teach implicit `:rf/default`.

A migration may choose `:rf/default` as the explicit id. The runtime will not
infer it.

The full implementation order — sequential because the fallback is embedded in
hot-zone specs, tests, tooling, docs, and developer education — lives in
[Reference Implementation Plan](#reference-implementation-plan) and
[Bead Structure](#bead-structure) below.

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
- no-arg `capture-frame`;
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

- `docs/guide/concepts/frames.md`;
- the topical `docs/guide/concepts/*` pages and
  `docs/guide/25-from-re-frame-v1.md` where they teach default frame
  convenience;
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

## Open Issues

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

## Recommendation

Adopt **Option C**: remove the ambient `:rf/default` fallback. Frame-scoped
operations must resolve their target from explicit frame context — a frame
id/handle, a provider, a cascade, a lexical binding, or a tool/session target —
and missing context must fail loudly rather than mutate or read the wrong frame.

This is the strongest frame-isolation rule, the easiest to teach ("no frame
context, no operation"), and the one that makes async and tooling boundaries
honest. It matches the explicit provider/client/environment patterns in the
benchmark libraries and fits the pre-alpha no-compatibility posture. The cost — a
repo-wide migration of tests, docs, examples, tools, and skills that assume
`:rf/default`, and revoking the earlier single-frame invisibility goal — is
accepted because frame correctness now underpins SSR, Story, Xray, pair tools, AI
tooling, resource management, managed effects, and the runtime partition.

## Resolved Decisions

Recorded 2026-06-09 under Mike's "action all EPs / keep going". Two things are
settled here: (1) the seven Open Issues are resolved
against their Recommendations, and (2) the EP is implemented **via the Appendix
reframing**, not its subtractive as-written body. Where the body and the appendix
describe the same rule differently, the **appendix formulation is the binding
form** and the body is read as supporting motivation.

### The reframing ruling (binding)

The contract is authored as the appendix argues — *one carried invariant*, the
*scope/hold/override* triad, *one frame stamp*, *foregrounded hold*, a
*strict-core-vs-tiered-discovery* split, and a *replay-determinism* lead. Concretely:

- **R1 — Lead with the carried invariant (appendix A).** The headline is positive
  and singular: *frame identity travels with every causal token; an operation reads
  its frame from the token it holds, never discovers one from the ambient world.*
  "Absence is an error" is the **corollary**, not the headline. The body's
  subtractive framing ("remove the fallback / delete the vocabulary") is retained
  only as migration mechanics, not as the statement of the rule.
- **R2 — Reuse scope/hold/override (appendix B), not a bespoke six-source list.**
  The §Frame Target Invariant six-source list is **superseded** by the published
  [`docs/api/02-views.md`](../api/02-views.md) triad — **scope** (`with-frame` /
  `with-new-frame` / `frame-provider`), **hold** (`capture-frame` /
  `frame-bound-fn` / `frame-bound-fn*` / captured envelope), **override** (the
  per-call `{:frame …}`). The six sources are instances of these three. The triad
  collapses at a boundary to the only distinction that matters: **carried as a
  value** (hold + override) vs **ambient in an established scope** (scope).
- **R3 — One canonical frame stamp (appendix C).** `:frame`, `:rf.frame/id`,
  `:rf/frame-id`, `url-owner-frame-id`, `:target-frame`, `:own-frame`, and
  `default-target-frame` are unified into **one inspectable carried shape** — the
  *frame stamp* — that appears identically wherever a causal token flows. The
  genuinely distinct **roles** (URL *owner* vs inspected *target* vs Xray *own*)
  become **qualified** stamps, not unrelated keys. This folds the EP-0001
  `:frame → :rf.frame/id` context-key rename into the larger unification.
- **R4 — Foreground hold (appendix D).** The async-safe *captured handle* is the
  **primary** mental model and resolution carrier; `with-frame` is demoted to
  synchronous-lexical-block sugar used inside roots, never near an async hop. The
  body's special async rule largely dissolves because the fragile primitive is no
  longer first.
- **R5 — Scope the strict core against the tiered discovery layer (appendix F).**
  The **embedded app/runtime path** is strict: absence is
  `:rf.error/no-frame-context`, justified by **replay-determinism + temporal
  non-locality** (NOT purity). The **interactive Tool-Pair / Xray / pair-MCP
  discovery layer KEEPS its proven four-tier contract**, including tier-3
  sole-app-frame *unique resolution* (unique resolution is not synthesis). Tier 3
  is **not** reconciled away — it is scoped to where an operator is present and
  ambiguity can prompt. `:rf.error/no-frame-context`, `:ambiguous-frame`, and
  `:rf.tool/no-frame-selected` are reconciled into **one ladder**: *absent →
  ambiguous → unselected*.
- **R6 — Lead the rationale with replay determinism (appendix G).** A
  silently-defaulted frame **poisons replay** — epoch replay, `restore-epoch!`,
  time-travel, and Story / Causa determinism become unsound. "Frames are carried so
  history is replayable" is the central argument; the benchmark libraries
  (TanStack / Redux / Apollo / Relay) are supporting, not central. Detection shifts
  left where the shape allows (conformance lint + registration-time flagging), and
  the frameless error carries **capture-site ancestry** through the existing
  `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id` correlation graph.
- **R7 — The values ranking (appendix coda).** The one-time ranking the appendix
  asks for is **adopted**: *explicit, carried frame identity outranks v1 call-shape
  fidelity.* The bare-function call shape survives in this EP only where that shape
  is free (inside an established `with-frame` / provider scope); rootless bare calls
  are the loud-failure case. The revocation of "frame plurality is invisible to
  single-frame apps" is **refined, not wholesale**: plurality stays invisible
  *inside a frame's scope*; only the absence of **any** scope is made loud.

### Open-Issue resolutions

The seven Open Issues are resolved against their Recommendations (Mike accepted
them), each re-expressed through the reframing above:

1. **`init!` creates no frame.** `init!` installs adapters and runtime
   capabilities; it does **not** create or guarantee `:rf/default`. A separate app
   bootstrap helper may create a named frame explicitly from opts.
   `ensure-default-frame!` is removed as an `init!` side effect.
2. **Mutating ops fail; tool reads may return structured data.** A frame-scoped
   *mutation* with no carried stamp raises/emits `:rf.error/no-frame-context`. Tool
   *reads* may return structured no-target data on the *absent → ambiguous →
   unselected* ladder (R5) rather than throwing, so frame pickers can render the
   state.
3. **Unique resolution only at the discovery layer.** Sole-app-frame resolution
   (tier 3) lives **only** in the interactive Tool-Pair / Xray / pair-MCP discovery
   layer, never in the embedded core resolver. It is unique resolution, not
   synthesis, and it is gated on operator presence (R5).
4. **`:rf/default` becomes an ordinary id.** The reservation buys neither migration
   nor docs clarity once "the runtime never infers it" is the rule, so `:rf/default`
   is demoted to an ordinary user-selectable keyword. It remains a legal explicit id
   (a migration may choose it) but carries no framework privilege and no special
   lookup tier. (This is the "otherwise make it ordinary" branch of the
   Recommendation.)
5. **Migration prefers root/provider wrapping.** Migration tooling rewrites bare v1
   calls into a `with-frame` / provider root; explicit `{:frame …}` call sites are
   for async callbacks, tools, tests, and SSR — i.e. the *hold* / *override* cases
   (R2, R4).
6. **Always-on emission for the frameless error.** `:rf.error/no-frame-context` is
   emitted through the always-on error axis (the production-survivable error-emit
   listener), not per-frame epoch capture — the error is itself frameless. It
   carries capture-site ancestry per R6.
7. **Boot-time frame-local `reg-*` need an explicit frame.** Registration-time
   frame-local surfaces (`reg-flow`, `reg-app-schema`, schema-population, elision
   declarations, HTTP interceptor registration, future frame-local registrars) must
   receive an explicit frame, run inside `with-frame`, run inside an `:on-create`
   hook, or be classified as genuinely global. Namespace-load time is not a reason
   to select `:rf/default`.

### What is superseded

For agents reading the body above against this section: the **§Frame Target
Invariant** six-source list, the §Public API Shape framing, and the migration
inventory's per-site "remove the default" phrasing are **subordinate** to the
reframing rulings. The normative statement of the contract is
[`spec/002-Frames.md` §Frame target resolution](../../spec/002-Frames.md#frame-target-resolution--the-carried-invariant);
where the EP body and that spec section differ, the spec section governs.

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
- `docs/guide/concepts/frames.md` §"A default frame would hide cross-frame
  leaks": most users never type `frame` and `:rf/default` costs nothing.

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

- `docs/guide/concepts/frames.md`;
- the topical `docs/guide/concepts/*` pages and
  `docs/guide/25-from-re-frame-v1.md`;
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
- Local source: `implementation/http/src/re_frame/http/handlers.cljc`
- Local source: `implementation/http/src/re_frame/http/managed.cljc`
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

---

## Appendix: Design-Review Commentary — Toward a Smaller, More *Carried* Rule

*Added 2026-06-07. This is a review of the EP above, not a change to its recommendation. Option C is the right call and a clean pre-alpha break is the right courage. The notes below argue that the **same rule** can be stated more elegantly, carried more simply, and made more unmistakably re-frame2 — mostly by leaning on vocabulary and contracts the project has **already shipped** elsewhere.*

### A. Reframe the rule positively: frame identity is *carried*, not *found*

The EP is written subtractively — "remove the `:rf/default` fallback," "delete the fallthrough vocabulary," "remove defensive defaults." That states the *fix* but hides the *principle*. The principle is positive and singular:

> Frame identity is a value that travels with every causal token — a dispatch, an fx context, a captured callback, an epoch record, an SSR payload. An operation reads its frame from the token it is holding. It never *discovers* one from the ambient world.

"Absence is an error" is then a **corollary**, not the headline: a token with no frame stamp cannot be honoured. This is worth doing because it (1) gives an agent or programmer *one* thing to learn instead of a ban-list; (2) explains *why* async / tooling / SSR are the danger zones (they are exactly where a token crosses a boundary and can lose its stamp); and (3) makes every site in the 12-bead migration the *same* edit — "stamp the token / read the stamp" — rather than thirty special cases. The masterpiece sentence is currently on line 62; it should be the thing you cannot miss.

### B. Don't invent a sixth-source list — reuse the published *scope / hold / override* taxonomy

§Frame Target Invariant introduces six resolution sources. That is still a *fallback chain* — the old four-tier chain with its worst rung amputated; the architecture of "search a priority list of ambient places" survived the surgery. It is also a *new* vocabulary, when [`docs/api/02-views.md`](../api/02-views.md) already organises the multi-frame surface into three intents:

- **scope** — `frame-provider`, `with-frame` / `with-new-frame`;
- **hold** — `capture-frame`, `frame-bound-fn` / `frame-bound-fn*`;
- **override** — the per-call `{:frame …}`.

The EP's six sources are just instances of these three (explicit arg → *override*; `with-frame` / provider → *scope*; captured handle / dispatch envelope → *hold*; harness selection → *override*). Re-expressing the resolver in the **already-taught** triad — rather than a bespoke priority list — means one vocabulary across views, resolution, and migration. And the triad collapses further into the only distinction that matters at a boundary: **carried as a value** (*hold* + *override*) vs **ambient in an established scope** (*scope*). The danger was never "ambient"; it was "ambient *with an invented floor*." Remove the floor and ambient-from-an-explicit-scope is honest.

### C. One carrier, one name: unify frame-identity-as-data

The EP disciplines *resolution* but leaves *naming* fragmented. Frame identity already travels under at least `:frame` (dispatch opt), `:rf.frame/id` (cofx, post-partition), `:rf/frame-id` (SSR payload), plus `url-owner-frame-id`, `:target-frame`, `:own-frame`, and `default-target-frame` (tools). For a project whose ethos is *data-first* with a *single reserved root*, that is vocabulary sprawl at precisely the boundary this EP is trying to make rigorous.

The masterpiece move is to make the *carrier* of frame identity one canonical, inspectable shape — call it the **frame stamp** — that appears identically wherever a causal token flows: dispatch metadata, fx cofx, captured handle, epoch record, trace event, SSR payload. Then *resolution* = "read the stamp on the token I hold," *no-frame-context* = "this token carries no stamp," and the genuinely distinct **roles** (URL *owner* vs inspected *target* vs Xray's *own*) become *qualified* stamps rather than unrelated keys. This folds the partition-EP's `:frame → :rf.frame/id` rename into a larger, principled unification, and it makes the conformance check trivial: every causal token either carries a well-formed stamp or is explicitly classified frameless. One key to teach, one shape to validate, one thing for Xray to render.

### D. The closure *is* the frame — foreground *hold*, demote `with-frame`

The EP lists `with-frame` as resolution-source #2, ahead of captured handles. But its own §Async Boundaries concedes that `with-frame` "supplies a frame only for the synchronous evaluation"; it evaporates at the first `.then` / `setTimeout`. That is **the same failure family the EP exists to abolish** — a context that looks present and silently isn't.

re-frame2 already has the more honest primitive: the *hold* intent **reifies the frame into a value**. `capture-frame` hands you `{:dispatch :subscribe …}` bound to a frame; `frame-bound-fn*` captures one; `reg-view` injects frame-bound `dispatch` / `subscribe` as lexical bindings. In that model the frame is **the functions you are holding** — nothing ambient, nothing to evaporate, identity carried by construction. That is exactly what async and tooling need, and it is more functional (values over dynamic scope).

So invert the teaching hierarchy: the captured handle (*hold*) is the robust carrier and the primary mental model; `with-frame` (*scope*) is convenience sugar for a synchronous lexical block, used inside roots and never near an async hop. The EP currently needs a special async rule *because* it put the fragile primitive first; foreground *hold* and the special case largely dissolves.

### E. You are revoking far less than the EP claims

§Backwards Compatibility frames this as surrendering the prized goal *"frame plurality is invisible to single-frame apps."* It doesn't. A single-frame app under Option C still has exactly **one** root `frame-provider`; *inside that tree* every call stays ambient and ergonomic — no `{:frame}` typing, no ceremony. What dies is not in-tree invisibility but **rootless** invisibility: bare calls with *no* established scope at all — which are exactly the async-callback, tool, test-fixture, and SSR-helper cases the EP rightly calls dangerous.

Reframing the revocation this way is more honest *and* more elegant: the conceptual change shrinks to "you need a root; inside it, nothing changes," the migration reads as far less violent, and the goal is *refined* rather than *revoked* — "plurality is invisible inside a frame's scope; the absence of any scope is the only thing made loud." Rewriting §Backwards Compatibility around that sentence will lower resistance without weakening the rule.

### F. Unique resolution is *not* synthesis — and the tool layer already proved the elegant answer

This is the EP's biggest under-argued move, and the audit makes it sharper than the EP admits. The EP rejects Option B (sole-live-frame fallback) as "more policy" that "still lets missing context proceed." But [`spec/Tool-Pair.md`](https://github.com/day8/re-frame2/blob/main/spec/Tool-Pair.md) **already ships a four-tier operating-frame contract** that is more sophisticated than either the status quo or Option C:

1. explicit override → 2. session-pinned selection → 3. **sole registered *app* frame** (reserved `:rf/*` tool frames excluded from the count) → 4. **`:ambiguous-frame`** when two or more app frames remain.

Tool-Pair explicitly **rejects** the `:rf/default` fallback ("a common naïve implementation would fall back to `:rf/default` at tier 4 … the hybrid contract rejects that … silently landing reads or writes there masks the ambiguity"). So the project has *already converged*, where it meets real operators, on the genuinely elegant rule: **resolve when the answer is unique; refuse when it is plural; never synthesise.** The core EP wants to outlaw tier 3 — and Audit §1 lists it as a "collision to reconcile."

The distinction the EP never draws is that **resolving a unique answer is not synthesising `:rf/default`.** Inventing a frame from nothing is unsound; observing that exactly one registered app frame *is* the answer is a total, honest function. The real objection to tier-3-in-the-core is not impurity — it is **temporal non-locality**: "sole frame" is true *until a second frame appears*, so adding Xray, Story, or an SSR frame silently changes the meaning of distant, untouched application code, with no operator present to prompt and replay-determinism (below) at stake. *That* is the argument for "always explicit in embedded code," and it is strong — but it is a different and better argument than "more policy."

The resolution is not to reconcile tier 3 away; it is to **scope the two contracts to where each is correct**:

- **Embedded app/runtime path** (no operator, silent non-locality, must replay) → Option C, strict: absence is `:rf.error/no-frame-context`. Justify it explicitly with non-locality + replay, *not* with purity.
- **Interactive discovery layer** (Tool-Pair, Xray, pair-MCP — an operator or agent is driving, ambiguity can prompt) → keep the four-tier contract, including tier-3 unique resolution. It already works and it preserves the "identical to single-frame re-frame" operator UX Tool-Pair deliberately guarantees.

Then reconcile `:rf.error/no-frame-context`, `:ambiguous-frame`, and `:rf.tool/no-frame-selected` into **one ladder** (absent → ambiguous → unselected), rather than three vocabularies that meet awkwardly. An agent reading this EP *will* notice that the core forbids what the tools rely on; the EP should answer that out loud, and the answer is "different layer, different operator-presence, same stamp."

### G. Make the rationale, the detection, and the error more re-frame2

Three sharpenings that use machinery the project already has:

- **Lead with replay determinism, not the benchmark libraries.** TanStack / Redux / Apollo justify explicit providers for *view hooks*. re-frame2's decisive argument is one none of them can make: a silently-defaulted frame **poisons replay**. If an operation's target frame cannot be reconstructed *from data*, then epoch replay, `restore-epoch!`, time-travel, and Story / Causa determinism are unsound — the same record can re-run against a different frame. "Frames must be *carried* so history is *replayable*" is the re-frame2 sentence; the benchmark table is supporting, not central.
- **Shift detection left.** The EP makes every failure a *runtime* error and adds a *grep* as the regression guard. The best error is the one that cannot be written. Promote the static sweep to a first-class **conformance lint** in CI (part of the contract, not a regex in prose), and let `reg-view` / macro expansion flag a bare `rf/dispatch` in a view body where the injected frame-bound `dispatch` was the intended call. Catch at compile / registration where the shape allows; reserve runtime errors for the genuinely dynamic async case.
- **Attribute the frameless error causally.** The suggested payload is *static* (`:operation :where :event-id`). re-frame2 already threads `:rf.trace/dispatch-id` / `:rf.trace/parent-dispatch-id`. A no-frame error should carry its *capture-site ancestry* — "this callback was captured at handler X in frame Y; the cascade ended; the continuation fired with no stamp" — so the hardest case (the EP's own "brutal to debug" async clobber) becomes fully attributed through the existing correlation graph, even though the error itself is frameless.

### H. Let the artefact embody the rule

A meta-point, since the spec *is* the product. The idea here is one sentence and a short derivation; the document runs ~1300 lines because the *invariant* and the *migration inventory* are interleaved. Split them: a tight normative core (the carried invariant, the scope/hold/override triad, the one stamp, the absent→ambiguous→unselected ladder — a page an agent can hold entirely) and a companion carrying the 12 beads and 11 audit tables. The very thing the EP argues *for* — a load-bearing boundary enforced by the **shape** of the API rather than by accumulated diagnostics — should be visible in the EP's **own** shape.

### Net

The direction is right and the courage is right. The opportunity is to state it as *one carried invariant*, reuse the *scope/hold/override* vocabulary the Views chapter already teaches, unify frame-identity into *one stamp*, foreground the *hold* primitive that is async-safe by construction, scope the strict core against Tool-Pair's already-proven tiered discovery layer instead of "reconciling it away," and let **replay determinism** — re-frame2's true differentiator — be the reason. Same rule; smaller to state, harder to misuse, and unmistakably re-frame2.

### Coda: The Values Tension Underneath

Every tactical note above circles one structural fact worth stating plainly: **two of re-frame2's stated values are mutually exclusive by construction, and this EP inherits the contradiction rather than resolving it.**

- *Ambient ergonomics* — `(rf/dispatch [:foo])`, callable with no receiver — is implicit context.
- *Loud failure / no silent swallow* is the demand that a wrong target never pass unnoticed.

Implicit context fails **silently** — that is what *implicit* means. The two cannot both hold at full strength, and this EP is the seam where they tear. So is the `{:db fresh-map}` boot handler that silently drops a live machine's `:rf/runtime` snapshot — a sibling hazard in the same family: an **implicit singleton** (the ambient frame; the ambient app-db a wholesale `:db` overwrites), bought for ergonomics and paid for with a silent-wrong-target bug, then patched with a diagnostic. re-frame2's recurring failure mode is the implicit singleton, and the cure it keeps reaching for — *more diagnostics* — treats the symptom. The cure that treats the cause is **no ambient target to get wrong**, which satisfies *both* values at once: wrong-target stops being *caught* and becomes *unrepresentable*.

The EP travels ~90% of that road and halts at the call shape. `rf/dispatch` survives as a free function that *throws* when frameless — rather than a call that simply **is not reachable** without a frame source. That final 10% is held by exactly one value: **fidelity to v1's call shape.** That is the value foreclosing the more elegant design, and releasing it makes the framework *smaller* — delete the bare function and the central resolver, the `:rf.error/no-frame-context` path, the six-source list, and the retired fallthrough vocabulary all have nothing left to resolve.

None of this repudiates the values. It asks for a one-time **ranking** of two that currently sit unranked:

> Explicit, carried frame identity outranks v1 call-shape fidelity.

Make that ranking once and the rest is mechanical: keep *bounded* ambient (`with-frame`) for synchronous call trees, mandate *held* handles across async, and delete only the *world-level default* and the *bare-function shape*. Decline it and you are choosing "v1, but multi-frame-safe" over "the most principled isolation model, v1-shaped only where that shape is free." Both are legitimate products — but only one is the masterpiece this codebase keeps saying it wants, and pre-alpha is the last moment the choice is cheap.
