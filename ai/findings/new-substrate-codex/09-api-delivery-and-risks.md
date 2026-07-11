# API, delivery plan, and risks

## Proposed artefact

```clojure
day8/re-frame2-ui
```

The artefact depends on re-frame2 core and patched React/React DOM 19.2.4+ peer packages within the 19.x line. It has no dependency on Reagent, reagent-slim, UIx, or Helix. RSC is outside this artefact; any host that separately installs `react-server-dom-*` must keep those packages on a patched 19.2.4+ line too rather than assuming the UI peer declaration secures them.

The compiler/macro code runs on JVM during ClojureScript compilation and does not enter the browser bundle. JVM view definitions are emitted from the same macro package and consumed by the existing `day8/re-frame2-ssr` artefact.

## Namespace surface

### `re-frame.ui`

| Surface | Kind | Purpose |
|---|---|---|
| `adapter` | Var | Existing Spec 006 adapter map installed with `rf/init!`. |
| `defview` | Macro | Define/register a named compiled view with one props map. |
| `sub` | Macro | Read and capture a re-frame2 subscription value; ambient or explicit-frame arity. |
| `event` | Macro | Generate a stable callback that evaluates and dispatches event data. |
| `handler` | Macro | Generate a stable imperative callback over committed values. |
| `render-fn` | Macro | Generate a pure render-scoped callback for foreign render/comparator/formatter props; no identity guarantee. |
| `raw-handler` | Macro | Explicitly pass an opaque callback identity unchanged. |
| `lease` | Macro | `(lease descriptor)` / `(lease descriptor {:cause data})`; declare commit-owned re-frame2 resource liveness at a stable site. |
| `dispatch-fn` | Fn/compiler form | `(dispatch-fn)`; stable function dispatching into the cell's committed ambient frame. Explicit targets use `rf/capture-frame`. |
| `frame` | Component | Existing scope/ensure frame-provider semantics in template form. |
| `view` | Fn | Runtime lookup of a registered view's public-props React wrapper by ID; unknown ID fails loudly. |
| `element` | Macro | Runtime React component type with compiled public/foreign props and children. |
| `spread` | Macro | Explicit generic runtime prop-map conversion/merge. |
| `raw` | Macro | Pass an existing React element through a template position. |
| `client-only` | Macro | `(client-only {:fallback template} client-template)`; CLJS element plus required capability-free JVM/first-hydration fallback. |
| `portal` | Macro | React DOM portal preserving logical frame/owner context. |
| `error-boundary` | Component | Framework-owned class boundary; `{:fallback defview :fallback-props map :reset-key value}`. |
| `create-root` | Fn | Create a React DOM root. |
| `render!` | Fn | Render a root `defview` with props and explicit frame config. |
| `hydrate-root` | Fn | Validate/install hydration state and hydrate a root view. |
| `unmount!` | Fn | Unmount and release a root. |
| `flush!` | Fn | Test-only `act` settlement helper. |

Direct event vectors in native `:on-*` attrs are syntax recognized by `defview`, not another public function.

The initial alpha should not add aliases for Reagent, UIx, Helix, or raw React spellings. One canonical name per operation is enough.

### `re-frame.ui.react`

This contains only the Clojure-friendly stable Hook wrappers and `lazy` described in [the lifecycle design](07-lifecycle-interop-ssr-hmr.md#thin-react-namespace). It does not re-export the whole React module.

### Tool-tier namespaces

Manifest and mounted-instance projections live in `re-frame.ui.tool` and enter the re-frame2 API manifest at the tooling tier. They are reachable by Xray and tests but are not the application authoring surface.

### No new SSR artefact

The JVM emitter targets the existing `day8/re-frame2-ssr` protocol. A tiny bridge namespace may be necessary, but creating a second server product would split ownership and invite parity drift.

## `defview` options

Keep the initial options set closed:

| Option | Meaning |
|---|---|
| `:id` | Override derived registry ID. |
| `:props` | Optional Malli-compatible props schema for development and literal call-site checks. |
| `:display-name` | Rare React DevTools override; normally generated. |

There is no general wrapper/HOC list, lifecycle map, custom comparator, render scheduler, or compiler plug-in map in alpha.

If mutable foreign values make default internal memoization unsuitable, the component belongs at an explicit foreign boundary or its subscription should project a stable immutable value. Adding per-view comparator policy before evidence would complicate both semantics and debugging.

## Root options

`render!` and `hydrate-root` accept:

```clojure
{:frame {:id ...
         :images ...
         :initial-events ...}
 :on-caught-error ...
 :on-uncaught-error ...
 :on-recoverable-error ...
 :identifier-prefix ...}
```

The shown frame map is the ensure shape. An existing frame uses the scope shape `{:frame {:frame frame-target}}`, where `frame-target` is a live frame value or ID. Both are passed to the existing merged frame-provider contract. Error callbacks are additive; the framework-owned callbacks always emit the structured re-frame2 error before invoking an application callback.

`hydrate-root` additionally consumes/locates the existing hydration payload and validates version, schema, frame, and template digests before React hydration.

## Internal contracts

### Compiler descriptor

Production descriptor:

```clojure
{:view-id ...
 :component ...
 :prop-slots ...
 :capability-bits integer
 :sub-site-count n
 :event-site-count n
 :lease-site-count n}
```

Actual production representation should be compact JS constants. Development attaches the manifest described elsewhere.

### Subscription observer

The private probe/acquire/read/release API is the only required re-frame2 core runtime addition. Its contract tests must run independently of React.

### Template AST

The normalized AST is a compiler-internal data format in alpha. Making it public would freeze a large surface and encourage runtime generation. Tools consume the emitted manifest, not arbitrary AST construction APIs.

## Delivery strategy

The fastest route to truth is a sequence of vertical proofs, each with a stop condition.

### Stage 0: three feasibility spikes

Build throwaway, test-owned spikes for:

1. **Codegen:** `defview` with props, DOM, branch, list, event vector; inspect advanced output against hand-written JSX runtime.
2. **Concurrency:** one ViewCell with conditional reads, owner-free probe, commit acquire, abandoned first mount, source change between render/commit, and Activity disconnect/hidden update/reconnect.
3. **Dual host:** one normalized template emitted to direct CLJS React and JVM render tree, then hydrated in a parity fixture.

Exit criteria:

- codegen meets direct-render target;
- 10,000 abandoned renders retain no owners;
- an Activity-hidden tree owns nothing and reconnects the latest capture without duplicate owners or lost local state;
- server/client structural fixtures agree.

If any spike requires runtime Hiccup, render-time ownership, or separate host parsers, stop and revise the architecture before product code.

### Stage 1: pure compiled UI vertical slice

Implement:

- `defview` props ABI and pure `render-fn` interop callbacks;
- native DOM, internal views, branches, keyed `for`, fragments;
- direct JSX runtime and JVM emitters;
- root render/hydrate/unmount;
- compiler lints for props, DOM, keys, and unsupported dynamic markup;
- static hoisting and capability classification.

Use one static app plus SSR/hydration fixture. Do not add subscriptions yet; isolate compiler quality.

### Stage 2: reactivity and frames

Implement:

- observer core port;
- ViewCell/capture/commit, reversible connect/disconnect, and permanent death;
- `ui/sub`, dynamic dependency sets, query stabilization, value stabilization;
- ambient and explicit frames;
- adapter epoch dirty-set coalescing;
- root/nested frame components;
- `flush!` and `flush-render!` integration.

Run concurrency, equality, fan-in, sibling-shared-node, frame-swap, cache disposal, and hot sub-registration fixtures before events or resources expand the surface.

### Stage 3: events and debugging

Implement:

- direct event vectors;
- `ui/event`, `ui/handler`, `ui/render-fn`, `dispatch-fn`;
- stable committed handler slots;
- view/event/sub manifests;
- committed instance registry and parent owner context;
- DOM source annotations;
- render cause linkage to current epochs/Xray;
- production elision gates.

The counter and dashboard examples should already feel complete here. If ordinary code still needs `useCallback`, manual frame capture, or console instrumentation, fix the core ergonomics before adding interop breadth.

### Stage 4: lifecycle, resources, and interop

Implement:

- thin React Hook namespace and analyzer lint;
- aggregated `ui/lease` effect;
- reversible Activity/unmount disconnection distinct from permanent disposal;
- foreign components, render props, refs, portals, error boundary;
- `spread`, `raw`, and `client-only` cost boundaries;
- Strict Mode fixtures;
- full SSR/hydration/resource/HMR integration.

Each feature must have a real example and a bundle reachability test. Optional code splitting/stream-boundary sugar waits unless the existing SSR marker cannot be expressed cleanly through the foreign/template surface.

### Stage 5: performance and alpha gate

Run the complete [performance contract](08-production-performance.md), remove unearned APIs, publish generated JS examples and benchmark methodology, and only then call the artefact alpha.

Alpha requires:

- no known concurrency/lifecycle leaks;
- all absence scans passing;
- current Chrome/Firefox/WebKit correctness smoke;
- JVM/browser parity suite;
- Xray causal walkthrough;
- quickstart-to-testing guide examples executable;
- adapter/core/spec contract updates reviewed together.

## Reuse posture

Study and reuse proven shapes, not dependencies by reflex:

- UIx demonstrates the element compiler/analyzer architecture and supplies a strong test oracle for DOM conversion and Hook lint behavior.
- Current re-frame2 UIx/Helix spine tests supply concurrency and disposal regressions the ViewCell must inherit.
- reagent-slim supplies absence and bundle-isolation gates.
- Existing re-frame2 SSR, frame provider, resource lease owner mint, trace projection, and error infrastructure should be called rather than copied.

Depending on UIx or Helix would pull their general wrapper/runtime and preserve per-read Hook integration, defeating the narrow artefact. Copying source is a licensing and maintenance decision, not assumed by this design. A small purpose-built compiler can share behavioral fixtures without sharing code.

## Risk register

| Risk | Why it matters | Mitigation / stop condition |
|---|---|---|
| Macro language becomes a second Clojure | Deep form walking can grow without limit. | Closed template grammar; explicit dynamic boundaries; no runtime fallback. Reject a feature without multiple real sites. |
| Probe computes twice on first mount | Pure probe plus commit acquire may duplicate expensive subscription work. | Measure. Prefer existing live node when present. Only explore promotable detached plans if first-mount profiles prove material cost; do not prebuild that complexity. |
| Commit ordering tear | New dependency can change between render and acquire. | Epoch/version evidence plus post-acquire comparison and synchronous correction before paint. Dedicated adversarial fixtures. |
| Activity reconnect uses stale capture | Hidden trees lose subscriptions while React may still commit low-priority renders. | Prove public-effect reconnection uses the latest committed closure; reacquire/version-check before paint. If the proof fails, leave Activity unsupported—never publish speculative capture globally. |
| Capture allocation dominates small views | One local capture is required for concurrent correctness. | Capability-specialize pure views; compact fixed arrays/bitsets; benchmark representation. Never move capture into shared mutable pending state. |
| `rf=` prop comparison walks large equal values | Ergonomics can hide expensive equality. | Identity short circuit, stable subscription publication, dev hot-prop diagnostics, benchmark. Avoid user comparator sprawl. |
| Site IDs drift under edits | Resource/event identity could be misassociated on HMR. | Source anchor + structural path + generation validation. Release/remount on ambiguity. Correctness over preservation. |
| Hook linter misses macro-expanded cases | Invalid Hook order is catastrophic. | Use analyzer information and compile fixtures modeled on UIx. Unknown higher-order Hook shapes require explicit custom-Hook declaration or fail closed. |
| CLJS/JVM emitter drift | Hydration bugs are correctness bugs. | One normalized AST, semantic parity corpus, fingerprint in payload, first-diff diagnostics. No independent parsers. |
| Foreign component hides DOM/source | Xray cannot annotate internals it does not own. | Honest boundary: parent template site + React DevTools name; no private Fiber dependency. |
| Production debug retention | Excellent debugging can quietly tax every user. | Separate compile-time branches, string/symbol reachability gates, bundle diff on every debug feature. |
| ViewCell couples to React internals | React upgrades become dangerous. | Use public Hooks/context/root APIs only; no Fiber or DevTools hook. Pin/test React minor upgrades. |
| Resource leases cause render side effects | Hidden ensure during render would leak and loop. | Capture only; one passive commit effect; owners distinct per site; existing resource events remain authority. |
| Strict syntax blocks legitimate data-authored UI | Some products genuinely render UI from data. | Separate optional interpreter artefact after a concrete consumer; never contaminate core. |
| Performance thresholds are gamed | Unequal fixtures can manufacture a win. | Shared state transitions/DOM assertions, emitted-code review, public harness, compare distributions and retained symbols. |

## Ruled design questions

These are decisions, not implementation options:

- Client literal markup compiles to JSX runtime; it is not runtime Hiccup.
- The JVM and CLJS paths share a normalized compiler AST.
- `ui/sub` returns a value and is not a Hook.
- A reactive view owns one external-store Hook.
- Subscription/resource ownership begins only after an effect-connected commit and releases on disconnection.
- Event vectors are the primary DOM interaction form.
- Event callbacks read committed, not speculative, values.
- Props are one named map with a direct internal JS ABI.
- Loading/resources stay explicit re-frame2 state and commands.
- No compatibility mode ships in the core artefact.
- Debug manifests and instance histories are absent from production.

Reopening one requires new evidence that invalidates the associated correctness or cost argument.

## Prototype decisions that remain empirical

These do not change the programming model and should be settled by the spikes:

- compact JS object versus array layout for ViewCell and capture;
- bitset representation for touched sites above 32 sites;
- `jsx/jsxs` versus `createElement` output in the actual Closure toolchain;
- exact query-site stable-key representation;
- whether stable equal-result publication should also move into re-frame2's derived-node core after the cell-level proof;
- site source-anchor hash algorithm;
- root DOM annotation strategy for exotic namespace/SVG cases;
- exact Activity reconnect behavior of the pinned React patch line;
- final relative bundle thresholds after a reproducible shared-chunk baseline.

No application-facing option should be created merely to expose one of these choices.

## Documentation as a design gate

The [guide](guide/README.md) intentionally uses only proposed canonical APIs. Every guide example should become a compile/run fixture. If a common task requires explaining internal cells, manual memoization, or a long escape-hatch sequence, that is evidence the API needs revision.

Conversely, the manual must state real costs and restrictions. Hiding strict template boundaries from new users would make the first dynamic use case feel like a betrayal rather than an intentional design.

## Final recommendation

Proceed with Stage 0. The synthesis is strong enough to prototype and narrow enough to falsify quickly.

Do not begin by forking Reagent or wrapping UIx. Begin with direct emitted code, an owner-free observer seam, and hostile concurrent/Activity-render fixtures. If those three pieces work and meet the gates, the rest is disciplined productization. If they do not, no amount of API polish will make the substrate a masterpiece.
