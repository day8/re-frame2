# Codex review: `new-substrate-synthesis`

**Date:** 2026-07-11

**Posture:** pre-alpha, blank slate, no backward-compatibility constraint

**Objective:** a re-frame2-native React substrate with exceptional ergonomics,
production efficiency, and debuggability—without speculative machinery or gold
plating

React behavior in this review was cross-checked against the official React 19.2
references for [`Activity`](https://react.dev/reference/react/Activity),
[`hydrateRoot`](https://react.dev/reference/react-dom/client/hydrateRoot),
[`StrictMode`](https://react.dev/reference/react/StrictMode), and
[`useSyncExternalStore`](https://react.dev/reference/react/useSyncExternalStore).

## Executive verdict

The synthesis has an excellent core and is substantially better than choosing
Reagent, UIx, Helix, or any one of the source studies wholesale. It has a credible
path to a masterpiece. It is **not complete or implementation-ready as written**.

The main issue is not a lack of ideas. It is that several individually attractive
ideas have been composed without yet resolving their boundary semantics. In
particular:

1. a re-frame2 frame is treated as if it were a React hydration root;
2. frame creation is placed in the view tree despite the render-purity invariant;
3. React disconnection, Activity hiding, and actual unmount are collapsed into one
   lifecycle state;
4. Story overrides are described as two independent lookups instead of one captured
   observation target;
5. pre-hydration event replay and automatic static-island inference are promised far
   ahead of a complete browser contract;
6. the `.cljc`/JVM behavior of stateful and host-only view features is not defined;
7. several important host capabilities—especially Replicant-equivalent declarative
   presence and a trusted-markup boundary—are absent; and
8. the Xray proposal adds surfaces before defining the exact evidence joins into the
   Xray that already exists.

My recommendation is **not** to discard the synthesis. Preserve its compiler-owned
view language, normalized AST, direct client lowering, ViewCell ownership protocol,
capability specialization, debug manifests, and falsifiable performance gates.
Correct the identity and lifecycle models, reduce the v1 promise, and then implement
one thin vertical slice end to end.

### Readiness scorecard

| Area | Assessment | Why |
|---|---|---|
| Product north star | Strong | The priorities and stop conditions are unusually explicit. |
| View/compiler direction | Strong, incomplete | The normalized AST and direct lowering are right; dynamic boundaries and host capabilities need firmer laws. |
| Reactive ownership | Promising, spike required | Probe/acquire is the right shape, but override capture, reconnect, and pull-vs-push behavior remain load-bearing. |
| Frames | Partly correct | Scope and carry rules are good; ENSURE timing and root/frame identity are not. |
| Lifecycle | Incorrect in one load-bearing place | Activity disconnection and unmount cannot share the stated preservation/reconnect semantics. |
| Events | Good foundation, overextended | Event vectors are valuable; raw event/form payloads and resumable replay undermine the data contract. |
| SSR/hydration | Not ready | Structural parity is promising, but the island identity model is wrong and failure/version/security contracts are incomplete. |
| Production model | Strong intent, incomplete accounting | Capability erasure and budgets are good; the capability matrix omits several runtime-bearing features. |
| Debugging/Xray | Strong evidence model, weak integration plan | The two evidence layers are excellent; the proposal should enrich current Xray surfaces rather than create a second information architecture. |
| Testing | Strong, with overclaims | The gates are excellent; arbitrary state generation, JVM parity, memo, and lifecycle claims need qualification. |
| Delivery scope | Too broad | Resumability, a permanently maintained toy DOM emitter, and speculative interpreter work should not sit on the alpha path. |

## What should survive intact

These are the synthesis's strongest decisions and should be treated as the default
unless a spike disproves them:

- **A compiler-owned `defview` language and one normalized AST.** This is the source of
  ergonomic Clojure data syntax, direct React output, JVM structural rendering,
  compile-time diagnostics, and production erasure. It is the unifying idea, not just
  an optimization.
- **Direct internal component calls with a Clojure props ABI.** Internal views should
  not pay general registry or JS-props conversion costs. Dynamic lookup remains an
  explicit boundary.
- **One ViewCell per reactive view.** Conditional subscription reads must be legal,
  speculative renders must acquire nothing, and commit must reconcile exact ownership.
  The render-probe/commit-acquire split is a strong concurrency model.
- **`rf=` value reuse at the React snapshot boundary.** Returning the prior identical
  snapshot when the next value is `rf=` is the right way to turn re-frame2 structural
  sharing into React bailout value.
- **Event intent as data.** Literal event vectors, with a very small set of compiler-
  visible projections, improve intent, Story, headless tests, SSR analysis, and Xray.
- **Capability-driven specialization and proof of absence.** A production build should
  contain only the machinery reachable from compiled capabilities, and CI should prove
  forbidden debug symbols and branches are absent.
- **Two-layer debugging evidence.** Compile-time site/manifests plus committed runtime
  instance records avoid both private Fiber inspection and fabricated speculative
  history.
- **Falsifiable gates.** Bundle budgets, ownership leak tests, DOM/event parity,
  abandoned-render stress, and real-consumer gates are much stronger than adjectives.
- **Public React behavior only.** No correctness dependency on private Fiber fields,
  undocumented context slots, or speculative-render telemetry.
- **A small public surface with explicit escape hatches.** The substrate should make the
  common re-frame2 path beautiful while keeping foreign React mechanics visibly
  foreign.

## Goals: what else matters

The existing ranking in [01-goals-and-invariants](../01-goals-and-invariants.md) is a
good start. I would use the following ordered goals. Ordering matters when two good
ideas conflict.

1. **Correctness under modern React.** Concurrent rendering, StrictMode, hydration,
   Activity, error recovery, and HMR must not leak owners, publish speculative state,
   dispatch render-time domain events, or leave stale callbacks live.
2. **re-frame2-native conceptual integrity.** Frame identity is carried; events are the
   state transition boundary; subscriptions are reads; routes/resources/machines remain
   normal re-frame2 projections. The UI substrate must not create a second state model.
3. **Semantic economy.** One obvious spelling for common work, orthogonal identities,
   few magic modes, precise errors, and concepts whose names match their mechanics.
4. **Exceptional production efficiency.** Low mount/update/allocation cost, bounded
   retained state, minimal generated code, transitive dead-code elimination, and no
   default observability tax beyond structured fatal errors.
5. **Causal debuggability.** A committed repaint can be joined to the exact changed
   observation(s), props slots, event/epoch, source site, root, frame, and occurrence—
   without private React internals.
6. **Determinism and testability.** Equivalent inputs produce equivalent normalized
   output across CLJS and JVM; identity, cleanup, versioning, and failure are
   reproducible; clocks and terminal transitions are controllable in tests.
7. **Web-platform correctness, accessibility, security, and privacy.** DOM property
   semantics, forms, custom elements, trusted HTML, CSP-safe payloads, hydration data,
   focus/inert behavior, and off-box debug egress are design inputs, not polish.
8. **Failure isolation and operability.** A broken root, payload, frame, view, adapter,
   or developer tool should have an explicit and narrow blast radius. Long-running SPAs
   and HMR sessions must remain bounded.
9. **Interop and composability.** React libraries, custom elements, portals, refs, and
   render callbacks must be usable through explicit boundaries without contaminating
   the fast internal path.
10. **Evolvability.** Versioned manifests/wire contracts, no private React dependency,
    good compiler architecture, fast incremental builds, and room to replace lowering
    strategies without changing application semantics.
11. **Adoptability.** Backward compatibility is not required, but mechanical migration,
    excellent diagnostics, editor feedback, and understandable generated output still
    matter. A masterpiece nobody can move to is not finished.

The useful negative goals are: no general animation framework, no second application
state model, no runtime hiccup interpreter on the fast path, no hidden cross-frame
reads, no private-Fiber debugger, and no resumability platform without an independent
consumer and browser conformance suite.

## The identity model needs one correction

The synthesis repeatedly states that “N `frame-root`s = N islands” and that island
identity is the frame id ([06](../06-ssr-islands.md),
[guide 08](../guide/08-ssr.md)). That is not a valid React architecture. React hydrates
DOM roots; re-frame2 frames are state/routing scopes. They may often coincide in a demo,
but they are neither equivalent nor one-to-one.

React may selectively hydrate `Suspense`/`Activity` subtrees *within* one root, but that
does not turn those boundaries into independent `hydrateRoot` identities. The synthesis
should either retire the overloaded word “island” or define two separate concepts:
framework-owned DOM roots and React-owned selective-hydration boundaries.

The design needs five explicit identities:

| Identity | Means | Cardinality/relationship |
|---|---|---|
| `root-id` | One React DOM render/hydration unit | Owns one container and one React root; can contain several frame scopes. |
| `frame-id` | One re-frame2 isolated state/runtime world | Can be referenced by several roots; a root can reference several frames. |
| `render-key` | One committed view instance | Belongs to one root and reads one ambient frame at a time. |
| `occurrence-path` | One repeated host-node occurrence inside an instance | Needed when a single view renders keyed rows without making each row a view. |
| `observation-target` | The exact value source captured during render | A real subscription node, Story override, or future typed source; it is not necessarily owned. |

This separation is not ceremony. It fixes SSR identity, multi-root apps, nested frame
providers, Story/Xray mounts, debug joins, error scoping, and future streaming.

### Required root contract

An independently hydratable unit should have a root manifest resembling:

```clojure
{:root-id             :page/shop
 :element-locator     {:id "shop-root"}
 :view-id             :shop/app
 :props               {...}
 :frame-payload-ids   [:frame/shop :frame/session]
 :render-fingerprint  "..."
 :build-digest        "..."
 :identifier-prefix   "rf2-shop-"
 :phase               :server}
```

The exact keys can change; the separations cannot. Never use mount position as durable
identity. A root installs its referenced frame payloads idempotently and then hydrates
its DOM container. Several roots may share one installed frame. One root may contain a
nested `frame-provider` pointing at another already-live frame.

Failure scopes then become precise:

- a root DOM/fingerprint mismatch fails or client-renders that root only;
- a bad frame payload affects roots that depend on that payload;
- a global envelope/build incompatibility can reject the page's hydration set;
- a broken developer annotation never changes app semantics.

## Must-resolve findings before implementation

### A1. `frame-root` ENSURE cannot mutate from render

[03 §8](../03-reactivity-and-ownership.md) puts create-if-absent and initial-event
seeding behind a component in the view tree, while invariant I-1 forbids render-time
mutation. React may call, restart, abandon, or replay render. Moving ENSURE to an effect
is also insufficient: descendants need the frame during that same render, and initial
events would happen after the first output.

**Decision:** make ENSURE a host preflight operation.

- The compiler extracts unconditional `frame-root` plans before React/JVM rendering.
- A plan may depend only on root inputs and pure, hoistable computations.
- The host ensures frames and runs initial events before invoking the root render.
- The emitted `frame-root` component only scopes the already-live frame.
- Conditional, reactive, or list-generated ENSURE sites are compile errors. The message
  should tell the author to create the frame in boot/event infrastructure and use
  `frame-provider` to scope it.
- Abandoned render, StrictMode replay, HMR, and error recovery tests must prove that
  frame creation and initial events do not repeat.

The newer Mike ruling to split `frame-root` (ENSURE) from `frame-provider` (SCOPE) is a
good API ruling. It is not yet the shape of all checked-in spec/runtime/docs, which
still contain the older merged provider. The synthesis should label this as a required
ruling/implementation reconciliation gate, not as existing conformance.

### A2. Probe and acquire must share one captured observation target

[03 §3](../03-reactivity-and-ownership.md) says Story overrides are consulted
“identically” by probe and acquire. Two independent resolutions can tear. More
importantly, commit cannot safely rediscover render-scoped React context, and no design
should read `Context._currentValue`.

**Decision:** `sub` resolves a first-class target during render and carries it to commit.

```clojure
{:kind       :subscription
 :frame-id   :app
 :query      [:cart/total]
 :node       <opaque-node>
 :version    42}

{:kind       :story-override
 :query      [:cart/total]
 :value      99
 :override-id <opaque-id>
 :version    7}
```

- The dev/full skeleton reads Story context with public `useContext` during render.
- Probe captures the exact target and its observed version/value.
- Commit acquires that exact target. An override target acquires no real derivation
  lease and reports `:owned? false` honestly.
- If the target/version changed across render→commit, the ViewCell schedules the normal
  correction path.
- Override changes produce a typed render cause.
- Schema validation still applies to override values.

The current Story seam is a CLJS React-context feature. The guide's suggestion that JVM
headless Story uses the “same mechanism” is false unless a separate host-neutral input
is designed. Either scope Story overrides to mounted CLJS tests, or expose an explicit
`ui.test/render` option such as `{:sub-overrides ...}`. Do not disguise two mechanisms
as one.

Story also mounts by registered **view id**, not by `render-key`; the latter is an
instance id allocated after a mount exists.

### A3. Disconnection, Activity hiding, and unmount are different states

[03 §4](../03-reactivity-and-ownership.md) says “disconnected” means unmount or Activity
hide, while preserving local state and the ViewCell so the instance may reconnect.
Actual React unmount destroys component state and instance identity. It does not later
reconnect.

Use these states instead:

- `:connected` — committed, visible/active, owns its observations and leases;
- `:activity-disconnected` — React retains identity/local state but framework ownership
  is released; reveal reacquires and corrects if evidence advanced;
- `:unmounted` — the instance is gone, its cell and local state are gone, and late
  callbacks fail/no-op according to their explicit contract;
- `:dead` — a retained handle has been invalidated because its frame/adapter/root was
  destroyed; it cannot reconnect.

If public React behavior cannot support the Activity contract, leave Activity
unsupported. The synthesis already has the right instinct here; it needs the correct
state machine and vocabulary.

### A4. Remove domain lifecycle events from the core view declaration

The `:on-mount` / `:on-unmount` options in [02](../02-programming-model.md) turn React
mechanical lifecycle into re-frame2 domain events. StrictMode effect replay, Activity,
HMR, error recovery, hydration recovery, and real unmount make “once” and “viewed”
semantics impossible to infer from connection mechanics. The guide's analytics example
would double-count or count the wrong concept.

**Decision:** remove these options from `defview` v1.

- Use ordinary React effects/ref cleanup for host synchronization.
- Dispatch domain visibility events from route/domain transitions.
- If a foreign system genuinely needs connect/disconnect callbacks, put them behind an
  explicitly mechanical interop API whose replay semantics are documented.
- Keep declarative presence separate; presence is a renderer state machine, not a
  domain mount event.

### A5. Pre-hydration event replay is not an earned v1 feature

[06](../06-ssr-islands.md) and the guide promise a roughly 1 KB bootstrap that delegates,
queues, and replays event vectors before hydration. A correct implementation must define
at least:

- supported event types, capture/bubble order, passive listeners, propagation and
  cancellation;
- default browser actions and when `preventDefault` occurs;
- coexistence with React's synthetic event system, portals, and non-bubbling events;
- controlled inputs, submitters, duplicate form fields, files, IME/composition, and
  selection;
- `target` versus `currentTarget`, and DOM mutation before replay;
- queue bounds, expiry, deduplication, navigation, hydration failure, and exactly-once
  behavior;
- CSP/XSS-safe side tables, payload schema/version/authentication, and privacy/redaction;
- the actual compressed cost of the decoder, site table, and semantics—not just the
  bootstrap loop.

The proposed `form-data` placeholder can contain duplicate keys and files and is not an
EDN value. The raw event placeholder is a host object and is definitely not serializable.
Both contradict the blanket claim that handlers are serializable data.

**Decision for v1:** retain event vectors in the compiler manifest/JVM tree, but lower
them to normal React handlers on the client. Support only cheap scalar projections such
as current value, checked state, and key. Use explicit `ui/event` or `ui/handler` for
form/file/raw-event mechanics. Emit no executable handler attributes into HTML and make
no resumability claim.

Keep delegation/replay as a throwaway research spike after alpha. It may graduate only
with an independent product consumer, a browser/security conformance matrix, measured
benefit over hydration, and a hard size budget. Guide examples authored by this project
are not independent demand.

### A6. Automatic static-island inference is unsound

“No subscriptions, handlers, or leases” does not prove a root needs no client runtime.
Local state, effects, refs, context, client-only nodes, portals, error boundaries,
presence, custom-element property assignment, foreign components, future root renders,
and HMR can all require a client.

**Decision:** define a transitive `requires-client-runtime?` capability, but do not let
that capability alone decide application intent.

- Prefer an explicit static host entry (`render-static`) or root-manifest policy.
- Elide hydration only when the compiler proves no client capability **and** the host
  declares that the root will never receive a client update.
- Revisit automatic elision after a real server consumer establishes the needed shape.

### A7. The JVM contract for host-bearing views is missing

The synthesis advertises `.cljc` views and “if the browser renders it, the server renders
it,” while `local` is React state and `effect`, refs, portals, error boundaries, and
client-only nodes have host-specific behavior. A pure JVM tree walk cannot reproduce
mounted state transitions or effects.

Choose and document one honest contract. The cleanest is:

- pure structure, props, subscriptions, conditions, lists, event intent, and trusted
  markup have full JVM rendering semantics;
- `local` contributes only its declared initial render value in a structural render;
  its setter is unavailable and invoking it fails with a typed test/host error;
- effects do not run in structural rendering and are represented only as capability
  metadata, or are rejected by strict headless mode;
- refs are absent on JVM; portals/client-only use explicit deterministic fallbacks;
- error-boundary server behavior follows the server renderer's explicit failure policy,
  not React client recovery;
- mounted tests are required for state transitions, effects, refs, focus, portals,
  presence timing, and error recovery.

Alternatively, strict `ui.test/render` may reject any stateful/host-bearing view unless
host stubs are supplied. Either answer is defensible. The current universal-equivalence
claim is not.

Also change “byte-compatible HTML” claims to **normalized structural equivalence** unless
the renderer truly pins byte-for-byte output. Current re-frame2 SSR's render hash is a
structural fingerprint, not a promise that two HTML serializers emit identical bytes.

### A8. Error boundaries need a complete, explicit surface

The `:catch` view option says “dispatch an event and render a fallback,” but leaves out
which errors are caught, reset behavior, dispatch timing, nested boundaries, fallback
inputs, hydration/server behavior, and destroyed-frame handling. React error boundaries
do not catch event-handler or arbitrary asynchronous errors, and dispatching from render
would violate I-1.

Prefer an explicit component:

```clojure
(ui/error-boundary
  {:fallback    [problem-panel]
   :reset-key   route-id
   :on-error    [:ui/render-failed]}
  [page])
```

Specify that the optional event is dispatched after the failing commit through a
captured live frame, that fallback rendering cannot recursively dispatch, which error
phases React catches, how `reset-key` works, and what JVM/SSR does. A compiler-generated
wrapper could still implement the component; the semantics should not hide in every
`defview` declaration.

### A9. Event boundary rules need tightening

The event design is close, but four choices should change:

1. **Loops:** do not reject every vector handler inside `for`. Permit committed callback
   sites that do not capture loop-scoped bindings. A capture-free literal can share one
   callback. If a vector captures the row binding, fail with a precise instruction to
   extract a keyed child view. Add a per-key callback registry only if benchmarks later
   prove it necessary.
2. **Bare functions:** a bare function is not an innocuous “opaque escape”; it hides
   phase and identity semantics. Use explicit `ui/raw-handler`/`ui/opaque-fn` at foreign
   callback boundaries. A native DOM function literal may be shorthand for committed
   `ui/handler`, because the invoker and phase are known.
3. **Dynamic event vectors:** a vector forwarded through props cannot be scanned for
   placeholders by the compiler without a runtime walker. Reusable controls should
   accept an event id/context and construct the literal vector at the DOM site, or accept
   an explicit `ui/event` object with declared projection semantics.
4. **Callback taxonomy:** the compiler's decision table must include invoker, phase,
   identity expectation, committed-versus-current capture, provenance, and
   serializability—not only “function or render function.”

Pin one canonical DOM prop spelling (`:on-key-down` versus `:on-keydown`) and define
capture/passive/once/prevent/stop semantics explicitly.

### A10. `ui/mount` must accept a compiled root, not arbitrary runtime hiccup

Examples show `(ui/mount [ui/frame-root ...])`, but the compiler cannot guarantee a
closed normalized AST if `ui/mount` accepts an arbitrary vector assembled at runtime.
Make `mount` a macro over a literal root form, or make it accept a compiled view/root
descriptor plus props. Dynamic roots must be explicit and should carry the same manifest
and capability metadata.

## Important missing capabilities

### Declarative presence—Replicant-equivalent mounting/unmounting

This is the most important omission. The substrate needs the capability represented by
Replicant's mounting/unmounting semantics, but expressed in React ownership terms. It
should be a small declarative presence primitive, not a general animation system.

Required contract:

- keyed children enter `:mounting`, settle to `:present`, enter `:unmounting`, and stay
  mounted until an explicit transition completion or bounded timeout;
- removal followed by reinsertion of the same key has deterministic interruption and
  re-entry rules;
- an exiting child is non-interactive and appropriately `inert`/`aria-hidden` unless an
  explicit accessible policy says otherwise;
- reduced-motion mode takes a deterministic immediate/short path;
- hydration does not fabricate an initial enter transition unless requested;
- test APIs can advance/finish transitions without wall-clock sleeps;
- cleanup is terminal and exactly once; retained children release all ownership when
  finally removed;
- the compiler records presence sites and occurrence keys so Xray can explain retained
  nodes and terminal cleanup;
- JVM rendering chooses a documented stable phase (normally `:present`) and exposes
  presence metadata to structural tests.

The syntax can use reserved nodes such as `::ui/mounting` and `::ui/unmounting`, a
`ui/presence` node, or another concise spelling. The important part is parity of
capability and deterministic semantics. It must not be implemented using domain
`:on-mount` events.

### Trusted markup

CMS/Markdown use is inevitable. Add an opaque, branded trusted-markup token:

```clojure
(ui/trusted-html (html/mark-trusted sanitized-html))
```

Raw strings must never be accepted as HTML. The brand constructor is the security
boundary; client and server renderers must share the contract; manifests/debug views
must not leak the content by default; and hydration/hash tests must cover it. This is a
small feature that prevents many applications from inventing unsafe escape hatches.

### Custom elements and DOM property semantics

The static DOM table needs an explicit custom-element contract: attribute versus
property assignment, boolean values, class/style behavior, native custom events, refs,
and SSR serialization. Do not force every custom element through `ui/raw`; that would
discard the compiler's value. A bounded `ui/custom-element` or tag-classification rule is
enough.

### Document head and React metadata

re-frame2 already has an authoritative head model. State whether substrate views may use
React metadata/resource hoisting and how it composes with that model. Prefer one
authoritative application head lane and explicit foreign interop; silent double
ownership is difficult to debug and can diverge under SSR.

### Accessibility diagnostics

Use the compiler for high-confidence checks only: missing accessible names on obvious
controls, invalid literal ARIA names/values, clickable non-interactive literal elements,
and presence exits left interactive. Warnings need an escape/suppression with a reason.
Do not attempt a complete accessibility theorem or runtime framework.

## Reactivity and frame refinements

### Runtime-only commits can re-render app views that consume runtime projections

[03 §6](../03-reactivity-and-ownership.md) says a runtime-only commit re-renders nothing
app-side. That is false for views subscribed to route, machine, resource, mutation, or
other runtime-db projections. The accurate claim is:

> A runtime-only commit does not notify app-db layer-1 subscribers. It does notify
> consumers of affected runtime projections; the commit remains atomic across both
> partitions.

### `ui/frame` and cross-frame truth

If `ui/frame` returns `subscribe`, then an application can carry it and read another
frame, even if the docs discourage doing so. Existing capture-frame contracts also carry
operations. Do not claim that cross-frame spelling is structurally impossible.

Either:

- make the everyday hold dispatch-only and expose broader capture as a clearly named
  interop/testing escape; or
- admit that explicit carried cross-frame access exists, while teaching subtree scoping
  and event/domain composition as the normal design.

The latter is likely more honest and keeps rare imperative integrations possible.

### `local` is host-local state, not future frame state

Do not justify `local` by saying its implementation may later move into a frame. That
would change ownership, persistence, SSR, time travel, and event semantics. Keep its
meaning precise: host component-local state, deliberately outside re-frame2 epochs.
Durable/domain state belongs in the frame.

### Effect dependency semantics

If dependency comparison uses `rf=` rather than React's `Object.is`, document its cost
and behavior for broad values. Specify cleanup order, StrictMode replay, HMR, Activity
disconnect/reconnect, and frame invalidation. Avoid naming any effect mode “once”; the
honest term is mount/connect semantics under the stated React lifecycle.

### Pull versus push is a real spike, not an editorial choice

The proposed pull alternative may cause every reactive ViewCell to report a changed
snapshot for an epoch, because `useSyncExternalStore` updates bypass `React.memo` around
the same component. It may also lose exact subscription-cause evidence unless a dev
graph remains. Benchmark both alternatives with equal correctness and debug contracts;
do not choose pull merely because it deletes a graph.

## Production contract corrections

The capability table in [05](../05-production.md) needs at least these transitive bits:

```text
sub local effect event lease frame-scope frame-ensure
presence error-boundary portal client-only trusted-markup
custom-element foreign-react dynamic-view debug-site
```

Not every bit needs a runtime module, but every bit that changes generated code,
hydration, ownership, or server behavior must be represented.

Other corrections:

- An event-only component still needs committed callback state/ref and a commit/layout
  update so callbacks see current props/frame. Describe that cost honestly.
- “No handler allocation” cannot be absolute while raw handlers, local callbacks, and
  foreign render functions exist. Promise no avoidable per-render allocation on the
  compiled vector path.
- “No wrapper components” cannot be absolute around context providers, error boundaries,
  or presence. Promise no incidental wrapper on ordinary internal view/DOM paths.
- Memo-by-default is reasonable, but `rf=`-equal props imply no prop-driven repaint only;
  internal subscription, local state, and context changes can still render.
- `:memo false` has no demonstrated need. Mutable foreign values belong at an explicit
  boundary; remove the option until a consumer earns it.
- A dynamic production `ui/view id` needs a production registry entry. Dev-only string
  IDs cannot simultaneously support dynamic production lookup; separate static internal
  ids, dynamic public ids, and dev metadata.
- Size and timing budgets are excellent hypotheses, not correctness law. If a budget
  misses, first optimize or prune the feature; do not weaken ownership or hydration
  semantics.
- “All reachable feature powersets” is not a practical gate. Test every generated shape,
  every known interaction, pairwise capability combinations, and targeted high-risk
  triples. Compare DOM, events, owners, cleanup, and hydration with debug interventions
  disabled.
- The browser artifact must exclude the JVM renderer; the UI source artifact may contain
  `.cljc` compiler/emitter code used by the existing SSR artifact. Replace the conflicting
  “separate SSR emitter”/“no second server artifact” wording with one packaging diagram.

## Xray and observability

The answer to “do we need better Xray observability?” is yes—but primarily through
better evidence and joins, not more top-level panels.

### Keep and formalize

- compile manifest: view/site/source/capability/props/event metadata;
- committed instance registry: root, frame, parent, generation, connection state,
  observations, ownership, render count;
- typed render causes: subscription target/version, prop slots, local state, context,
  Story override, HMR, reconnect correction, hydration recovery, epoch restore;
- direct parent `render-key` links for compiler-owned views;
- no publication from speculative renders and no private Fiber traversal.

### Correct the proposed Xray shape

1. **Use one canonical cause vector.** The existing Reactive view cannot assume “sub or
   props.” Publish `:rf.view/causes` as a vector because a render can have several causes.
2. **Join into existing surfaces first.** Enrich Reactive, Event, Epoch, Issues, and the
   existing source/open-editor gestures. Do not immediately add mounted-views,
   SSR/islands, and heatmap panels. Add a panel only after an information-architecture
   review shows the question cannot be answered clearly in an existing surface.
3. **Use direct hierarchy for the new substrate.** `parent-render-key` removes the need
   for Fiber or DOM walking. Legacy adapters can keep a fallback.
4. **Add occurrence identity.** A component `render-key` is insufficient to distinguish
   rows emitted by one keyed `for`. DOM annotations, event evidence, and presence records
   need `occurrence-path`, for example `[{ :site 17 :key order-id }]`.
5. **Be honest about Story overrides.** Display them as visual overrides with no real
   subscription node and `:owned? false`; they are not evidence that a subscription
   computes the shown value.
6. **Model restore as a new cause.** A time-travel repaint is caused by a restore
   operation token that names its target epoch. Do not attribute the repaint to the old
   target epoch record or reconstruct/backfill speculative history.
7. **Report loss.** Every bounded ring/detail buffer should expose
   `total`, `retained`, and `dropped`. Exact heatmaps/counts cannot be promised from a
   capped trace without loss accounting.
8. **Bound prop precision.** Top-level changed prop slots are a sound cheap promise.
   “Exact nested key path” requires retained prior values/deep diff and should not be
   promised by default.
9. **Explain production weight from the manifest.** Xray should answer “why does this
   view carry a cell/presence/client runtime?” from capability bits and source sites.
10. **Route all new values through existing egress policy.** Props, override values,
    occurrence keys, query args, event payloads, and hydration data may be sensitive.
    Existing off-box redaction/classification is the mandatory path.

A minimal committed instance record could be:

```clojure
{:render-key        1042
 :parent-render-key 1039
 :root-id           :page/shop
 :frame-id          :shop
 :view-id           :cart/row
 :generation        3
 :connection        :connected
 :observations      [{:kind :subscription
                      :query [:cart/item 17]
                      :target-id 88
                      :version 12
                      :owned? true}]
 :rf.view/causes    [{:kind :subscription
                      :target-id 88
                      :from 11
                      :to 12}]}
```

The schema, bounds, and privacy projection must be versioned. The particular field names
are less important than a single authoritative record shared by Story, Xray, compiler
diagnostics, and tests.

## Testing corrections and additions

Keep the three-tier strategy and gates in [07](../07-testing.md), with these changes:

- A props schema can generate props; it cannot generate an app-db that satisfies
  arbitrary subscriptions. Require application-supplied state generators, query
  fixtures, or scenario constructors.
- JVM/client parity applies to the defined structural subset and normalized semantics,
  not every host-bearing view behavior.
- “`rf=` props ⇒ zero render” must be scoped to prop-driven work when no sub/local/context
  input changed.
- Activity hide/reveal and actual unmount need separate fixtures and assertions.
- Story override tests must assert that real derivation ownership is absent and that
  subscription assertions bypass overrides.
- Add a root/frame matrix: one root/one frame, one root/several frames, several roots/one
  frame, several roots/several frames, nested providers, out-of-order hydration, and one
  failed root.
- Add frame ENSURE preflight tests: conditional/repeated sites rejected, initial events
  exactly once, abandoned React render causes no frame mutation.
- Add observation-target render→commit race tests for both real subscriptions and Story
  overrides.
- Add presence tests for exit retention, re-entry interruption, reduced motion,
  hydration, accessibility, terminal cleanup, and fake-clock completion.
- Add trusted-markup tests for brand enforcement, client/server parity, and XSS payloads.
- Add capability-equivalence and proof-of-absence tests for every runtime-bearing feature.
- Add late-callback tests across unmount, Activity disconnect, frame destruction, adapter
  disposal, HMR replacement, and root teardown.
- Add restore-cause tests that point to the restore operation and target epoch without
  rewriting old evidence.
- If early event replay ever returns, require a dedicated browser/security conformance
  project before it joins the substrate suite.

Avoid guide aphorisms such as “if you flush twice you have two tests” or “hard setup means
the view reads too much.” They are useful heuristics, not contracts.

## Delivery changes

The current delivery plan is thoughtful, but two items should leave the critical path:

- Do not keep a toy direct-DOM emitter green forever. The normalized AST and conformance
  fixtures provide the architectural seam. Fund a native reconciler only after a real
  React limitation is measured and a consumer exists.
- Do not make resumable delegation/replay a v1 stage. Keep it as an isolated research
  branch with stop conditions.

Recommended sequence:

### Stage 0 — settle the load-bearing rulings

Freeze the identity table, root manifest, frame ENSURE preflight, observation-target
protocol, lifecycle states, JVM host-bearing semantics, event projections, error
boundary, presence, and production capability vocabulary. Reconcile the newer
`frame-root`/`frame-provider` ruling with checked-in specs and implementation.

### Stage 1 — the thinnest dual-host vertical slice

Implement `defview`, normalized AST, literal props/DOM, internal child views, conditions,
keyed lists, direct client lowering, JVM structural output, root descriptor, escaping,
and structural parity. No subscriptions, effects, presence, or SSR payload yet.

### Stage 2 — re-frame2 ownership

Implement frame scope, preflight ENSURE, ViewCell, real observation targets, conditional
subs, `rf=` snapshot reuse, commit reconciliation, abandonment/disposal, and `flush!`.
Run the push-vs-pull spike with identical gates.

### Stage 3 — committed host behavior

Implement event vectors and scalar projections, `local`, effects, refs, captured frame
dispatch, explicit foreign callbacks/components, error boundaries, portals, client-only,
and exact lifecycle cleanup. Each capability gets client, headless, and production
semantics before the next is added.

### Stage 4 — presence and web boundaries

Implement declarative presence, trusted markup, custom elements, head ownership policy,
forms/IME guidance, and high-confidence accessibility diagnostics. This stage is bounded;
it is not an animation/form/a11y framework.

### Stage 5 — debugging as a first consumer

Emit versioned manifests and committed instance/cause records, then integrate them into
existing Xray and Story surfaces. Xray is an alpha dependency because excellent
debuggability is a product goal, not a post-release addon.

### Stage 6 — SSR roots and hydration

Implement independent `root-id` manifests, separately keyed frame payloads,
fingerprints/digests/prefixes, order-independent idempotent installation, failure
isolation, and the static-root explicit policy. Reuse current re-frame2 SSR security and
egress contracts.

### Stage 7 — production specialization and consumer gates

Generate capability-specialized skeletons, prove debug and unused feature absence,
benchmark representative real apps, test interaction combinations, and migrate at least
one non-trivial application plus Story and Xray. Optimize after profiles; prune features
that cannot justify their weight.

## Recommended decision table

| Question | Recommendation |
|---|---|
| Core authoring model | Compiled `.cljc` `defview` with closed normalized AST. |
| Client lowering | Direct JSX/React element output; no general runtime interpreter. |
| JVM lowering | Structural renderer for a precisely defined subset plus explicit fallbacks/metadata. |
| Reactive unit | One ViewCell per reactive view; retain push and pull as a gated spike until measured. |
| Story override | First-class captured observation target; no fake real subscription ownership. |
| Frame scope | Ambient carried frame through public React context. |
| Frame ENSURE | Host/compiler preflight, never render mutation. |
| Hydration identity | Independent `root-id`; frame payload identity is separate. |
| Events | Data vectors lowered to normal React handlers in v1; value/checked/key projections only. |
| Raw callbacks | Explicit boundary with invoker/phase/identity semantics. |
| Pre-hydration replay | Defer; research only. |
| Static output | Explicit static-root policy plus compiler proof; no automatic inference from three missing features. |
| Memo | Default for internal views; no `:memo false` until demanded. |
| Lifecycle events | Remove from core; mechanical effects and domain events stay separate. |
| Error handling | Explicit error-boundary node with reset and phase semantics. |
| Presence | Include before alpha as a bounded Replicant-equivalent capability. |
| Trusted HTML | Include before alpha through a branded token. |
| Custom elements/head/a11y | Define bounded contracts; avoid framework expansion. |
| Xray | Enrich existing evidence spine and panels first; add occurrence identity and loss accounting. |
| Native DOM renderer | Architectural seam only; no permanently maintained implementation without demand. |
| Custom data interpreter | Exclude until an independent consumer earns the security and maintenance cost. |

## API and guide inconsistencies to clean up

These are smaller than the architectural findings, but resolving them will prevent the
guide from teaching contracts the implementation cannot keep:

- “No `#js` ever” conflicts with the raw React example that uses `#js`. Say “no JS props
  objects on compiled DOM/internal view paths; foreign React interop may require them.”
- `:on-keydown` and `:on-key-down` both appear. Choose one spelling.
- The guide forwards event vectors through props while the compiler relies on literal
  placeholder discovery. Pick the literal-at-DOM-site or explicit compiled-event rule.
- “Scenes mount by registry render-key” confuses view id with instance id.
- “Every frame-root is an island” and “island identity is frame id” must be replaced by
  the root/frame model above.
- “Unmount/Activity preserve local state and reconnect” must distinguish the states.
- “If the browser renders it, the server renders it” must be limited to the normalized
  structural subset.
- “Byte-compatible HTML” must become normalized structural equivalence unless bytes are
  genuinely pinned.
- “Runtime-only commits rerender nothing app-side” must acknowledge runtime projection
  consumers.
- Dynamic view lookup in production conflicts with dev-only registry ids.
- `ui/frame` claims no cross-frame spelling while returning carried subscribe operations.
- Event vectors are called universally serializable while raw event/form-data values are
  proposed.
- The packaging text alternates between a separate SSR artifact and no second server
  artifact.
- The current checked-in merged `frame-provider` contract, newer split ruling, and
  synthesis wording need one explicit migration/reconciliation note.

## Definition of “complete” for this design

The design is complete enough to implement when all of the following have normative
answers and executable fixtures:

- every syntax form has static, client, JVM, SSR, debug, and production-specialization
  semantics;
- root, frame, instance, occurrence, and observation identities are independent and
  versioned;
- every mutation is assigned to preflight, event, commit, layout/effect, or teardown—
  never an ambiguous “lifecycle” phase;
- abandoned render, StrictMode, Activity, HMR, unmount, error recovery, destroyed frames,
  and adapter disposal have exact ownership outcomes;
- every dynamic boundary states its invoker, phase, identity, capture, provenance,
  serializability, and failure policy;
- the JVM subset and host-only fallbacks are explicit;
- hydration names its DOM root independently from frame payloads and has bounded failure,
  version, security, and redaction behavior;
- the capability lattice covers all client-bearing features and is tested for presence
  and absence;
- Xray/Story consume one versioned evidence schema and never require speculative or
  private React state;
- accessibility, trusted markup, custom elements, forms, head ownership, and presence
  have bounded contracts;
- performance budgets are measured on representative real consumers, with correctness
  gates held constant;
- the public guide contains no claim stronger than the conformance suite.

## Final assessment

The synthesis is **directionally excellent, conceptually ambitious, and incomplete**.
Its best contribution is the combination of compiler ownership, direct lowering,
ViewCell concurrency discipline, capability erasure, and first-class causal evidence.
That combination is genuinely fresh and re-frame2-native.

The masterpiece move now is subtraction and boundary precision:

- separate React roots from re-frame2 frames;
- move ENSURE out of render;
- capture observation targets once;
- tell the truth about Activity versus unmount;
- remove domain lifecycle events;
- defer resumability and automatic static islands;
- define the JVM subset;
- add bounded presence and trusted markup;
- integrate evidence into the Xray already present; and
- prove one vertical slice before expanding the surface.

With those changes, the design has a credible path to all three headline outcomes:
excellent ergonomics in source, exceptionally low production cost, and debugging that
can explain not merely that React rendered, but exactly which re-frame2 fact made the
committed UI change.
