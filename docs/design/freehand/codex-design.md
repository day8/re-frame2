# Better UI: Freehand, one substrate with two execution modes

Status: ratified pre-alpha target design; implementation is not yet complete. The
substrate is **Freehand**; its public namespace is `re-frame.freehand`,
conventionally aliased as `v`.

This is the product spine. It incorporates the ruled
[`decisions/D001-D021`](decisions/README.md) and defines the target until each
surface graduates into its canonical specification. It is not a second spec tree:
after a spec migration lands, that spec owns the contract. Required prototypes,
pilots, and measurements are acceptance obligations, not open product design. The
argued dossier, fitness harness, and `re-frame.ui` donor code supply evidence but
do not independently enlarge Freehand's API.

Notation:

- `v` — `re-frame.freehand`, including interpreted and compiled declarations;
- **interpreted** — the default full-Clojure execution mode;
- **compiled** — the finite compiler-owned execution mode selected at a declaration;
- `host` — the explicit browser/React integration surface;
- `web` — qualified DOM-platform semantics supplied by Freehand's web host;
- `t` — the common structural and mounted test surface.

## 1. Design at a glance

The product is one re-frame-native substrate with two required execution modes:

- **Interpreted Freehand** is the default. It interprets ordinary Hiccup, permits
  full Clojure in view bodies, and dynamically observes `sub` reads.
- **Compiled Freehand** uses finite compiler-visible sites, direct React/JVM
  emission, static manifests, and capability elision while preserving the same
  application-facing semantics.

The named useful code from `re-frame.ui` becomes Freehand's compiled-mode
implementation: its analyzer, both emitters, ViewCell reactor, presence runtime,
manifest/elision and diagnostic machinery, and test surface. `re-frame.ui` is in
donor mode now: no new standalone surface. Freehand does not preserve it as a
sibling product or inherit its API wholesale. There is no second compiler.

Execution mode is explicit at the declaration. Interpreted views are complete,
first-class application code. A programmer chooses compilation for a hot boundary,
static evidence, static assets, or predictable direct lowering. The framework does
not promote code automatically and compiled templates do not hide interpreted
subtrees.

### Product topology

| Surface | Relationship to re-frame | Role |
|---|---|---|
| interpreted Freehand | native; assumes re-frame | unrestricted default authoring and execution mode |
| compiled Freehand | native; assumes re-frame | finite-site lowering over the same declarations and ABI |
| `re-frame.ui` | native; donor only | temporary source and alpha-train migration surface; no new standalone features; deleted at the conformance-and-pilots gate |
| Reagent | independent | compatibility and established Hiccup ecosystem through an adapter |
| UIx / Helix | independent | direct React programming through an adapter |
| Replicant | independent | whole-state, component/subscription-free data renderer and a useful architectural alternative |
| third-party React libraries | independent | qualified leaves, behaviors, or wrappers |

Freehand succeeds `re-frame.ui`; it never depends on it. The donor is deleted when
§6 conformance is green, the pilots pass, and consumers have migrated—a gate, not a
date. The other libraries remain independent; Replicant is an alternative renderer,
not a renderer to co-mount inside React.

### Core commitments

1. One vector-called declared view is the unit of identity, invalidation, HMR,
   errors, profiling, testing, and compilation.
2. Props, children, keys, events, frames, controlled scheduling, structural output,
   and debug identity mean the same in both execution modes.
3. Render is passive. A selected commit publishes frame, dependencies, events, and
   evidence atomically.
4. Application state and causal lifetime belong to re-frame. Host nodes and
   imperative instances remain behind qualified host boundaries.
5. Important identity, intent, configuration, plans, and evidence are data.
   Execution machinery and opaque host objects remain code or private runtime state.
6. Separate React and JVM emitters prove parity against one semantic ABI; they do
   not need to be one implementation.

## 2. What data-oriented means

Data orientation is not primarily a syntax preference and it is not synonymous
with serializing everything. Its purpose is to separate declaration from execution.
When an important fact is a value, several consumers can reason about it before or
without performing it.

| Property | Leverage |
|---|---|
| value equality | stable identity and change detection without closure churn |
| inspectability | people, tools, and AI can ask what a node reads, emits, mounts, or owns |
| testability | equality and directed queries replace callback spelunking and fake renderers |
| traceability/replay | intent, effects, root plans, and selected evidence can be recorded and explained |
| composition | generic functions can transform, validate, merge, route, or interpret declarations |
| portability | one semantic value can feed React, JVM structure, SSR, docs, or static analysis |
| delayed interpretation | a qualified data identity can select host-specific code at the edge |
| compilation | finite shapes expose facts that can be checked, indexed, hoisted, and emitted directly |
| AI-assisted authoring | bounded schemas, descriptors, and findings let people and AI generate and repair code mechanically |

The design rule is:

> Make a fact data when more than one consumer benefits from naming, comparing,
> inspecting, transforming, recording, or interpreting it independently of the
> code that performs it.

Pure functions from data to data remain the engine. Turning ordinary calculations
or control flow into a map-based DSL does not create useful data orientation.

### The data plane

| Fact | Representation | Main consumers |
|---|---|---|
| UI structure | normalized Hiccup and a versioned semantic tree | runtime, compiler, JVM, SSR, tests |
| application intent | event vectors, shallow listener options, scalar projections, exact-key maps | host adapter, re-frame, traces, tests |
| reactive query identity | query vectors plus possible/realized site evidence | frame resolver, subscriptions, compiler, debugger |
| view contract | qualified descriptor, props schema, source, children policy, lowering/capabilities | host, HMR, compiler, catalog, editor, AI |
| reusable-control state | ordinary frame-scoped re-frame records keyed by controller kind and caller-supplied semantic address | component library, re-frame handlers, tests, tools |
| root plan | versioned Root Descriptor | mount, frame preflight, SSR, hydration, tools |
| presence | keyed retention plan and mount/exit attribute overrides | browser host, JVM tree, tests, accessibility checks |
| host request | qualified component/behavior id, public config, outward intents | browser adapter, structural host, traces |
| host command | registered operation plus caller-supplied semantic target and value arguments | re-frame effects, behavior adapter, traces, mounted tests |
| DOM top-layer state | closed qualified popover/modal desired-state properties | DOM host, compiler, JVM structure, browser tests |
| parameterized presentation | `render-fn`/`slot` descriptor | compiler, runtime, component library, tests |
| error containment | boundary, reset value, fallback, safe intent, and private frame error egress | host, re-frame, tests, telemetry adapter |
| diagnostics | props schema, typed errors/findings, recovery data | compiler, runtime, editor, CI, AI |
| observability | manifests, occurrences, causes, targets, sites, explicit loss | trace and view tools |
| app workflows | existing re-frame events, effects, resources, routes, frames, machines | runtime, SSR, replay, debugging |
| theming contract | tokens, CSS variables, `data-component`/`data-part` ids, bounded override data | CSS, catalog, tests, consumers |

The corresponding execution plane contains functions, compiler internals,
schedulers, DOM nodes, React elements, refs, observers, cleanup functions, and
third-party instances. A data value may point to registered execution code, but it
does not pretend that a Workbook, Vega View, or DOM node became immutable data.

### Replicant and the no-`sub` idea

Replicant’s renderer is a pure whole-state topology:

```clojure
(render whole-application-state) -> whole-hiccup-tree
```

It has no components, subscriptions, networking, or state manager. Events,
lifecycle requests, aliases, and mount/unmount presentation can remain data. That
is coherent for small applications, SSR, examples, and systems that already have a
purpose-built presentation model.

Freehand supports the same flow without giving up granular re-frame reads:

```clojure
(v/defview app-root [_]
  [app-page {:model (v/sub [:app/view-model])}])

(v/defview app-page [{:keys [model]}]
  ;; Everything below this boundary can be props-only.
  [workspace {:model model}])
```

One page-level query can supply a complete view model, leaving the tree below it
props-only. Removing `v/sub` would instead require passing app-db, rebuilding and
propagating a large presentation model, or inventing query injection. It would also
discard granular invalidation, derived-subscription reuse, frame/override
resolution, and per-boundary cause evidence.

Keep `v/sub`. Its query is data; the operation resolves it against the current frame
and records the read in the selected render bundle. Semantically a view is
`(frame-snapshot, props) -> semantic-tree`, while the ergonomic syntax leaves the
frame implicit.

Use a gradient:

- prefer props-only views for reusable and leaf presentation;
- use one page/root view-model query when that produces a clearer model;
- place scoped `v/sub` reads at application boundaries when locality, invalidation,
  derivation reuse, or frame ownership benefits;
- do not pass the entire database merely to satisfy a purity slogan;
- expose props-only, possible dependencies, and realized dependencies in tools.

Freehand extends the data orientation into queries, events, effects, frames,
resources, machines, root plans, manifests, causes, failures, and explicit evidence
loss. It does not add Replicant-style aliases as a third core composition form:
declared descriptors retain high-level identity, plain functions cover inline
reuse, and compilation needs an unambiguous boundary. Reconsider a pure library
alias only if pilots reveal many expansions that deserve neither a ViewCell nor a
React boundary.

## 3. Programmer model

The interpreted path needs four ideas: a view declaration, plain `sub`, Hiccup, and
event intent.

```clojure
(ns app.cart
  (:require [re-frame.freehand :as v]
            [re-frame.freehand.test :as t]))

(v/defview cart-badge [_]
  [:button {:on-click [:cart/open]}
   "Cart (" (v/sub [:cart/count]) ")"])

(v/defview account-panel [_]
  (if-let [account (v/sub [:session/account])]
    [signed-in-panel {:account account}]
    [sign-in-panel {}]))
```

`sub` returns a value. Event vectors are data. User code sees no reaction object,
deref, subscription disposal, dispatch closure, hook-order rule, or view-local
application state.

### Views and helpers

The call convention is sharp:

- `v/defview` declares a mounted boundary and is called as `[view props]`;
- `defn` declares an inline helper and is called as `(helper args)`;
- directly calling a declared view is an authoring error;
- arbitrary functions are not internal vector heads.

```clojure
(defn price-line [{:keys [label amount]}]
  [:div.price-line [:span label] [:span (money amount)]])

(v/defview invoice [{:keys [id]}]
  [:section
   (price-line {:label "Subtotal"
                :amount (v/sub [:invoice/subtotal id])})
   [tax-summary {:invoice-id id}]])
```

Any `sub` read made by an inline helper belongs to the enclosing view. The compiled
checker may reject a helper that hides reactive sites or opaque markup. The recovery
is to pass a computed value, expose finite structure, extract a declared child, or
leave the parent interpreted.

There is no dual-entry descriptor with an interpreted direct-call path. Plain
functions already provide honest inline composition. Direct-call and plain-function
vector-head errors name the corresponding `[declared-view props]` or `(helper ...)`
recovery rather than exposing descriptor internals.

### Props, children, and keys

Every internal view receives one props map.

```clojure
(v/defview panel [{:keys [title children]}]
  [:section.panel
   [:h2 title]
   children])

[panel {:key panel-id :title "Details"}
 [details {:id panel-id}]]
```

The contract is:

- trailing children arrive as the reserved `:children` vector;
- `:key` chooses sibling identity and is stripped before the props map arrives;
- caller-authored `:children` inside the props map is rejected;
- a view can declare that it accepts no children;
- internal props use re-frame value equality for memoization;
- mutable host values belong at an explicit host boundary.

Props schemas are optional in `:re-frame.freehand/v1`, including for compiled
application views. They are required by build/catalog policy for shipped reusable
library views and whenever a report claims generated coverage of a particular
view. When present, `:props` uses the repository's vector-form Malli convention
behind the existing validator seam. Analyzer-derived prop slots remain private
compiler facts, not an inferred schema. A missing schema is reported as absent
rather than `:any`; a present map schema is closed by default. `:key` is outside
the schema, and children policy is descriptor metadata. Literal calls may be
checked statically, dynamic calls in development, and schema validation and
generation dependencies are removable from production.

Repeated view children use real keys:

```clojure
(v/defview todo-list [_]
  [:ul.todo-list
   (for [id (v/sub [:todo/visible-ids])]
     [todo-row {:key id :id id}])])
```

A plain helper can make a deliberately coarse region; a declared keyed child can
isolate a changing row. No region DSL is needed.

### Compiled declaration

Compilation is an explicit option on the same declaration form. It changes the
lowering, not the public view model.

```clojure
(ns app.todo
  (:require [re-frame.freehand :as v]))

(v/defview todo-row
  {:compiled true}
  [{:keys [id]}]
  (let [{:keys [title completed]} (v/sub [:todo/by-id id])]
    [:li {:class {:completed completed}}
     [:input {:type :checkbox
              :checked completed
              :on-change [:todo/toggle id]}]
     title]))
```

Call sites remain `[todo-row props]`, and structural tests do not change. Full
Clojure is not automatically a finite language, so `v/check` runs against the
interpreted declaration and reports required refactoring before `{:compiled true}`
is added. `:re-frame.freehand/v1` is the artifact-wide version of Freehand's
compiled grammar, carried by compiler findings and manifests; it is not a
compatibility profile negotiated between products or selected independently per
view. There is no second macro or second namespace.

### Framework-supplied views

Framework views use the same descriptor contract as application views; they are
not compiler intrinsics. The corpus-critical example is `v/route-link`:

```clojure
[v/route-link {:to :article :params {:slug slug} :class "title"}
 title]
```

It renders a real anchor with a strategy-correct `href`. Plain in-app left clicks
produce routing intent; modifier clicks, middle clicks, downloads, and non-`_self`
targets retain native behavior. A caller `:on-click` runs first and may veto. The
routing artifact owns href and navigation semantics through its existing late-bound
seam; Freehand owns only the ordinary view and host-neutral rendering. Absence of
the optional routing artifact fails loudly. The same declaration renders a
handler-free anchor for JVM/SSR and may itself be compiled without changing its
public contract.

### State ownership

Application and interaction state are re-frame facts. Controlled values and event
intents are the normal component API. Routes, events, resources, and machines own
causal lifetime; render and unmount do not fetch, seed, or clean up domain state.

DOM nodes, focus, geometry, observers, portal containers, and third-party instances
are host facts. They live behind the qualified boundaries in §5.

#### Semantic controllers

Reusable views are props-only by default. A component library may define a
**semantic controller** only when an interaction protocol genuinely spans events
and making every caller reproduce it would be unreasonable. Controller state is
ordinary frame-scoped re-frame data keyed by `(controller-kind, semantic-address)`.
The address is immutable caller-supplied EDN, never a React key, DOM id, runtime
token, or renderer-derived path.

Renderer occurrence and semantic state identity have different jobs. Occurrence
identity owns reconciliation, callbacks, presence, connections, and evidence;
semantic identity owns a field, dropdown, typeahead, or other interaction protocol.
Tools may join the two, but Freehand never derives one from the other. A reset or
session generation is separate again: the address says *which controller* and the
generation says *which baseline/session*. Two mounted writable owners of the same
kind and address are a development error unless that controller kind explicitly
permits sharing.

The component library owns each controller's state schema and semantic events such
as `edited`, `committed`, `cancelled`, `opened`, or `moved`. Freehand owns the common
address, generation, frame, event, and evidence laws—not widget taxonomies. It does
not publish generic `put`/`merge`/`toggle` view operations, reserve
`:rf.field/*`/`:rf.dropdown/*`/`:rf.typeahead/*`, or introduce a controller/reducer
DSL in v1. A raw storage-shaped event is acceptable only for protocol-free state;
once phase, generation, delayed acceptance, or caller intent exists, one semantic
library event must consult committed state. That event may update the controller
record and emit the caller's intent as effects of one transition; the view-side
site still produces exactly one event or `nil`.

A library may publish controller-kind metadata—state/address schema, evidence
label, and ownership mode—through its catalog. Tools consume metadata rather than
hard-coded event names. Freehand requires no `register-kind!`, reducer macro, or
controller DSL in v1. Debounce likewise begins as library policy and enters reserved
re-frame vocabulary only after independent UI and non-UI uses demonstrate the same
identity, cancellation, frame, SSR, and trace semantics.

Controller state persists until a semantic transition or its causal route, form,
record, or workflow owner clears it. Unmount removes only the occurrence-to-state
evidence join. Development tools report duplicate owners, orphaned/old records,
generation, last semantic cause, and retention owner rather than hiding cleanup
behind lifecycle callbacks.

Freehand exposes no renderer-derived state handle such as `v/self`. Promotion of a
library policy into framework vocabulary requires repeated independent use,
renderer-independent semantics, cross-mode evidence, material tooling leverage,
and explicit acceptance of its compatibility cost.

#### Buffered and revision fields

The first portable controller is the buffered/revision field. Its library contract
uses an explicit semantic address and required reset generation:

```clojure
[buffered-field
 {:control [:invoice invoice-id :amount]
  :value (v/sub [:invoice/amount invoice-id])
  :reset-key (v/sub [:invoice/amount-revision invoice-id])
  :on-commit [:invoice/amount-committed invoice-id]}]
```

`:validate` may provide pure advisory display feedback, and `:on-cancel` may expose
domain-significant cancellation. Neither becomes commit authority; application
acceptance remains a caller event.

The minimum live record is `{:reset-key reset-key, :draft draft}`; its presence
with a matching reset key means editing. The first actual edit starts the session;
focus alone does not. Edit synchronously publishes the controlled echo. Commit and
cancel consult the live matching record, clear it atomically, and are idempotent for
stale generations. Enter and blur share the commit transition; Escape clears before
a racing blur. A changed reset key exposes the external baseline immediately even
when its value equals the previous baseline. A caller with no external reset need
may pass a documented stable literal, but the prop remains required so the protocol
never has a weaker hidden mode.

IME composition suppresses Enter commit until composition ends. Controlled echo
must preserve the input node and selection; a transformed accepted value may reset
the caret only when the caller's new reset key establishes a new baseline.

The model and protocol are settled; the portable component claim remains gated by
the same-value, stale-blur, browser editing, HMR, frame, JVM, multiplicity, orphan,
and cost harnesses. Dropdown and typeahead pilots may reveal further shared
controller infrastructure. A high-rate or opaque editor may instead use a qualified
host-owned wrapper; that is an explicit protocol/performance escape, not generic
local state.

Freehand exposes no local-state, ref, effect, or hook forms. React-owned protocols
use a qualified host leaf or UIx/Helix wrapper.

## 4. Common semantics

### Passive render and atomic selection

A mounted boundary renders against one exact frame incarnation and produces a
candidate bundle:

```clojure
{:descriptor-revision revision
 :frame-incarnation   frame
 :dependencies        candidate-dependencies
 :events              candidate-event-sites
 :tree-evidence        candidate-tree}
```

The host publishes the entire bundle only when the render is selected for commit
and its descriptor revision, frame incarnation, and generation remain current. An
abandoned or stale render publishes none of it. Subscription ownership and event
bodies therefore cannot come from different generations.

Commit acquires new dependencies before releasing old ones. Disconnect deactivates
subscriptions, callbacks, presence timers, and host behaviors owned by that exact
generation.

Every interpreted shell observes frame context unconditionally. A provider retarget
from frame A to frame B must rebind a child even when props are equal. A compiled
shell may elide frame machinery only when its manifest proves that neither the view
nor its events are frame-sensitive.

### Subscription law

An interpreted view records `v/sub` reads made on the same render thread. Full Clojure
control flow and ordinary helper calls are allowed. The selected commit reconciles
the targets actually read.

A compiled view gives each compiler-visible read a finite lexical site id. Its
manifest can describe possible sites before render and active sites afterward. A
proved sub-free view can omit the reactive bridge.

Both execution modes guarantee:

- query resolution through the exact ambient frame;
- a plain stabilized return value;
- invalidation of the owning declared boundary when an active target changes;
- acquire-before-release for conditional target changes;
- no ownership or evidence from abandoned renders;
- a loud failure for reads outside an active declared render.

`v/sub` is render-only: it always means “declare a reactive read owned by this
boundary.” REPLs, tests, tools, and other deliberately non-reactive code use the
existing frame-explicit one-shot operation instead:

```clojure
(rf/subscribe-once [:basket/total] {:frame :app/main})
```

That operation resolves, probes, returns a value, and releases without installing
a view dependency. `v/event`, `v/handler`, timers, promises, and host callbacks do
not receive a dual-mode `v/sub`; calling it there is the same typed phase error.
Diagnostics distinguish no active render, a conveyed wrong render thread, and a
missing/dead frame, because their recoveries differ.

Handle cardinality is internal. The runtime may deduplicate an invalidation edge by
equal resolved target while retaining occurrence records for query/value identity.
The compiler may own balanced handles per lexical site. Tools report both read
occurrences and distinct resolved targets without equating them.

Capture is same-render-thread only. A `sub` reached through `future`, `pmap`,
`bound-fn`, or another conveyed child thread fails before probing and points to
same-thread realization or keyed child views.

### Event law

#### Intent and projections

```clojure
[:button {:on-click [:cart/add product-id]} "Add"]

[:input {:value email
         :on-input [:form/edit :email ::v/value]}]

[:input {:type :checkbox
         :checked dark?
         :on-change [:prefs/set-dark ::v/checked]}]

[:form {:on-submit {:event [:article/save article-id]
                    :prevent-default true}}
 ...]

[:canvas {:on-pointer-down
          (v/event [e]
            [:canvas/pressed (point-in-canvas e)])}]
```

The reserved scalar projections are `::v/value`, `::v/checked`, and `::v/key`.
At firing time the native or qualified host adapter obtains the live scalar
payload, and one pure Freehand materializer replaces every matching top-level
argument marker before passing the resulting plain vector to ordinary re-frame
dispatch. Position zero may not be a marker; nested markers remain ordinary data;
and a requested but unavailable payload is a typed error with no dispatch. Literal,
forwarded, `v/event`, options-map, interpreted, compiled, production, and test paths
all use this same materializer. General re-frame dispatch gains no payload-map
semantics.

After shallow listener options are interpreted, an event-producing site yields
exactly one event vector or `nil`. A vector of event vectors is an error. Multi-step
work is represented by one semantic event whose re-frame handler returns the
required effects, keeping one inspectable causal unit instead of a miniature
dispatcher in the view.

#### Keyboard conditions and committed decisions

Plain keyboard branching has one additional closed data form on `:on-key-down` and
`:on-key-up`:

```clojure
{:on-key-down
 {"Enter"     {:event [:picker/accept] :prevent-default true}
  "Escape"    [:picker/close]
  "ArrowDown" {:event [:picker/move 1] :prevent-default true}}}
```

Keys are exact `KeyboardEvent.key` strings and values are the existing event forms:
vector, options map, `v/event`, or `nil`. Selection is one level; a missing key is a
no-op; no branch matches during IME composition; and selected browser mechanics run
before dispatch. Mixed listener-option/key maps are errors. There are no wildcards,
regexes, modifiers, ordering, platform aliases, or state predicates in v1. Those
cases use `v/event` or a qualified host boundary.

The form remains only if the dropdown and typeahead pilots demonstrate repeated
state-independent use without expanding it; otherwise it is deleted before release.

An intent may carry identity, generation, or a candidate value. When acceptance
depends on changing application state, the re-frame event handler decides against
the exact committed frame at dispatch; a render-time callback must not close over
a stale guard. This law covers stale blur, delayed dropdown selection, and similar
races without introducing substrate-local application state.

#### Callback roles and identity

Each callback form has one phase and identity contract:

| Form | Role | Identity law |
|---|---|---|
| event vector/options | application intent; inspectable and structurally testable | stable committed adapter per site |
| `v/event` | consume callback arguments and return one event vector or `nil`; no `sub`, hooks, refs, or effects | stable committed adapter per site; body changes publish atomically |
| `v/handler` | explicit imperative foreign work, not render or state storage | stable committed adapter per site; retired at disconnect |
| `v/render-fn` | pure foreign render-phase callback; no `sub`, dispatch, hooks, or refs | no public stability guarantee; safe reuse is an optimization |
| `v/raw-fn` | expert seam when authored callback identity is protocol data | exactly the supplied identity; no stabilization |
| UIx/Helix wrapper | hooks, context, refs, effects, portals, Suspense, compound React protocols | wrapper's React contract |

Bare functions remain legal at native `:on-*` sites because the site's committed
outer adapter owns their lifecycle, and as opaque values passed between internal
views. A bare function in a declared foreign callback position is rejected because
the invoker, phase, and identity contract are unknown. There is no separate
`v/dispatcher`; `v/event` is the explicit conversion seam for foreign arguments.
There are no dependency arrays: selected-commit publication supplies freshness.

Each runtime event/handler site is owned by `(committed normalized-node identity,
callback-prop)`. Its stable proxy reads the exact committed body and frame when
invoked. Node identity is key-aware within its sibling scope and positional only
when no key exists. A render builds a candidate table; commit publishes it with the
dependency bundle. Equal values at two nodes therefore retain independent proxies,
lifecycle, and `:once` state. An abandoned render never updates a proxy. Removal,
key changes, replacement, disconnect, and incompatible HMR retire the exact proxy;
a retired proxy is inert and emits development evidence rather than dispatching
into a successor owner. `v/render-fn` is excluded from this mutable-proxy scheme
because it may run during an uncommitted foreign render.

Native attachment required for `:passive` or `:once` is internal event-adapter
lifecycle, not a public effect system.

### Controlled inputs

Controlled editing uses one host law in both execution modes:

| Question | Contract |
|---|---|
| Controlled node? | native node whose normalized props contain `:value` or `:checked`; presence, including `nil`, is the fact |
| Door event props? | `:on-input` and `:on-change` only |
| Handler outcomes? | synchronously known vector, options map carrying a vector, or synchronous `v/event` returning vector/`nil` |
| Scheduling? | materialize, dispatch, drain re-frame, and flush dirty ViewCells observing the exact committed frame before returning to native/React processing |
| `nil` outcome? | no dispatch |
| Forwarded attrs? | `v/spread-safe` preserves owned controlled props/handlers; general spread does not |
| Foreign component? | opaque; its qualified host boundary owns the callback/scheduling contract |

`v/handler`, promises, arbitrary callbacks, capture/passive listener lanes, and
foreign callback protocols are not door-eligible. `:on-before-input` remains an
ordinary event: it precedes the DOM mutation, so `target.value` is not generally the
candidate value and composition needs a separate browser-proved contract before it
could enter the door.

The compiler may precompute eligibility when the grammar proves the final
normalized facts; otherwise it emits the common runtime predicate or rejects the
site. The interpreter recognizes the same predicate. Controlled-prop or handler-
class flapping is development evidence and may not silently change a promoted
view's scheduling. The public guarantee is the observable same-tick round trip,
not targeted isolation: unrelated dirty cells on the same frame may ride the flush
and must be included in latency evidence. Acceptance means no lost characters,
caret jumps, selection damage, or IME failure in the real-browser contention
matrix.

### Identity, HMR, and errors

The mounted occurrence identity is `(view-id, parent, key-or-position)`. Compiler
signatures and runtime body revisions are internal generations, not application
identity. Occurrence identity is not a writable controller address; §3 requires
explicit semantic identity for that job.

HMR preserves a compatible shell, invalidates its revision, and commits new
dependencies/events together. An incompatible descriptor or host signature
remounts cleanly.

Failures carry stable ids, source, phase, bounded relevant values, and a concise
recovery. Errors do not publish partial render evidence or expose analyzer
internals as the programmer-facing explanation.

#### Error boundaries

`v/error-boundary` provides common interpreted/compiled containment for render,
normalization, validation, and descendant React render/lifecycle failures that a
React boundary can catch:

```clojure
[v/error-boundary
 {:reset-key route-revision
  :fallback  [broken-page {}]
  :on-error  [:telemetry/ui-render-failed]}
 [workspace-page {:workspace-id workspace-id}]]
```

It has one child region. `:fallback` is static structure, a declared view, or a pure
`v/render-fn` of the safe summary. A changed `:reset-key` retries by ordinary
remount; there is no reset ref. The prior committed child bundle remains
authoritative until the fallback is selected; the thrown candidate publishes no
partial bundle.
After the fallback is selected, an optional `:on-error` prefix receives one safe
summary exactly once for that captured failure generation. If the fallback throws,
the failure propagates to the nearest outer boundary. Event-handler, async,
re-frame-handler, behavior, and transport failures remain with their actual owners.

The safe summary is bounded value data: diagnostic and report ids, failing and
boundary view ids, finite phase, safe source/root/frame/revision correlation facts,
and evidence scope/basis/completeness/loss. It excludes raw props, app-db, event
payloads, exceptions, arbitrary `ex-data`, nodes, and host instances. In parallel,
the host promotes one private record per failure generation through the existing
frame error-egress path, carrying the safe summary plus the opaque exception and a
capped host/React stack for the configured observer. Redaction, source maps,
transport, retry, and vendor integration belong there; sink failure cannot replace
the user's fallback. Freehand never captures app-db or event history by default.
Applications that need a redacted snapshot or recent event ids obtain them through
their own allow-listed error observer or instrumentation.
The JVM structural host can assert fallback structure; an SSR response/stream render
uses the Root Descriptor's server error projection rather than pretending that a
client recovery commit occurred.

## 5. Composition, presence, and host boundaries

### Props forwarding and parameterized content

Use the same forms in both execution modes:

- `v/spread-safe` forwards caller DOM attrs beneath literal owned props. It denies
  `:key`, `:ref`, `:value`, `:checked`, `:default-value`, and owned handlers,
  composes class, and preserves the controlled-input proof.
- `v/spread` is the visible open-props escape at a foreign boundary. Literal owned
  props win over the opaque forwarded map.
- ordinary trailing children serve a default/fixed region;
- compound child views serve multiple fixed regions;
- `v/render-fn` plus `v/slot` serve a parameterized row, cell, or item renderer;
- stateful customization is a declared child view.

```clojure
(v/defview data-table [{:keys [rows row]}]
  [:table
   [:tbody
    (for [[i item] (map-indexed vector rows)]
      [:tr {:key (:id item)}
       (v/slot row i item)])]])

[data-table
 {:rows people
  :row (v/render-fn [_ person]
         [:td (:name person)])}]
```

A slot body is pure and compiler-visible. A custom row needing a subscription or
event mounts a statically named child view.

### Theming and semantic parts

Component libraries use two existing planes rather than a Freehand theme service:

- the styling plane uses ordinary component variants, CSS classes, namespaced
  custom properties, root/scope `data-theme`, and finite public `data-part` values
  scoped by a literal `data-component` marker;
- the structural plane uses children, compound child views, `v/render-fn`/`v/slot`,
  or a declared child boundary.

Public token and part ids are versioned component-library API and belong in the
component's schema/catalog entry. CSS inheritance is the default for live theme
switching, avoiding one reactive token subscription per compiled leaf. Freehand
adds no theme registry, theme context, token subscription DSL, or host-independent
styling language.

A library may accept a bounded `:parts` map for per-instance classes, `data-*`, and
approved ARIA/DOM attributes. Each declared part merges through `v/spread-safe`,
with the component's owned literals winning. A part override cannot change keys,
refs, children, node/behavior ownership, controlled values/defaults/handlers,
library-owned handlers, required roles/accessibility relations, or top-layer
desired state. Unknown parts and denied attributes are source-located development
findings. Structural replacement uses the structural plane; arbitrary late
tree-to-tree transforms are not a portable compiled-library contract.

### Presence as data

Mounting and unmounting presentation is data over the keyed `presence` primitive:

```clojure
(v/presence {:timeout-ms 250}
  [:div.toast
   {:key toast-id
    :class ["transition-opacity" "opacity-100"]
    ::v/mounting {:class ["opacity-0"]}
    ::v/unmounting {:class ["opacity-0"]
                   :inert true
                   :aria-hidden true}}
   message])
```

The contract is bounded:

- keyed children move through `:mounting`, `:present`, and `:unmounting`;
- mounting attrs apply to the initial committed phase, then yield to base attrs;
- unmounting attrs apply while the exiting node is retained;
- `:timeout-ms` is mandatory and is the deterministic terminal safety bound;
- overrides can change presentation/accessibility attrs but not keys, children,
  controlled values, refs, or event ownership;
- re-entry cancels removal according to the presence state machine;
- the JVM emits the present/base state with qualified presence metadata;
- `t/flush-presence!` advances a fake clock in tests;
- development checks warn about retained interactive content without `inert` or
  appropriate assistive-technology hiding.

`v/presence-phase` remains for the uncommon child whose structure, rather than
attributes, depends on phase.

Mounting, present, re-entry, unmounting, and removal are development facts keyed by
occurrence and generation and queryable by tools and tests. They never dispatch
domain events or own fetching, seeding, cancellation, or durable cleanup.

### DOM top layer

The web host recognizes a closed qualified pair of desired-state properties:

```clojure
[:div {:popover :auto
       ::web/popover-open? open?
       :on-toggle
       (v/event [e]
         (conj on-open-change (= "open" (.-newState e))))}]

[:dialog {::web/modal-open? open?
          :on-cancel [:dialog/cancelled]}]
```

`::web/popover-open?` is legal only with a valid `:popover` mode;
`::web/modal-open?` is legal only on `<dialog>` and maps to `showModal()`/`close()`.
An ordinary non-modal dialog uses the platform `:open` attribute. The DOM host
diffs desired state after a selected commit, treats equal values as no-ops, and
fences calls by node occurrence and generation. Browser dismissal never mutates
application state implicitly: ordinary native events reconcile it through intent,
and development reports a controlled top-layer node with no reconciliation
handler. Invalid or disconnected host operations become typed development evidence
with recovery guidance rather than swallowed platform exceptions.

The JVM/SSR tree retains the semantic element and qualified desired-state fact but
does not claim that a browser top layer opened; hydration performs the first host
operation after commit. Positioning uses CSS anchor positioning or a separate
one-node behavior. Enter/exit retention uses `v/presence`. These intrinsics do not
own focus policy, keyboard semantics, timers, geometry, or domain lifecycle.
React portals remain in explicit wrappers; Freehand has no neutral portal primitive
or target registry. Reconsider one only after multiple non-overlay integrations
cannot fit a leaf, behavior, outward bridge, or wrapper.

### Three host shapes

| Shape | Use |
|---|---|
| qualified host leaf | React component with values in and callbacks out |
| registered behavior | one DOM node owned by an imperative library with connect/update/disconnect and optional finite commands |
| UIx/Helix wrapper | React-owned hooks, context, refs, effects, portals, compound cloning, or callback protocols |

Bare React component heads are outside the Freehand tree. A host descriptor names
the boundary, and a declared wrapper states its structural/SSR policy.

#### Outward React bridge

Some React libraries demand a component value as a prop—for example a grid cell
renderer or drag overlay. `v/->react` is the outward half of the same wrapper
boundary, not a fourth host shape:

```clojure
{:cellRenderer (v/->react person-cell)}

(v/->react person-cell {:map-props cell-props})
```

The bridge accepts only a declared descriptor. It returns a memoized React
component cached by descriptor identity and stable adapter identity across body
revisions. At mount an exact reserved `frame` prop selects an existing live frame;
otherwise the component consumes ambient frame context. Missing, malformed, or
dead frames fail loudly, and the bridge never creates or owns a frame.

Without `:map-props`, the bridge shallowly copies own enumerable props other than
reserved `frame` into the declared prop ABI by exact name and leaves each value
untouched. With `:map-props`, the stable top-level adapter receives raw foreign
props and returns the one Freehand props map. There is no deep conversion,
automatic key conversion, mapper registry, callback guessing, children conversion,
ref forwarding, or lifecycle option surface. An inline adapter that repeatedly
remints the React component is a development diagnostic. Protocols needing hooks,
refs, compound cloning, React children, or imperative handles use a real wrapper.

Inside, props, subscriptions, events, HMR, errors, and evidence retain ordinary
Freehand semantics. `v/->react` raises the common typed host-operation error on
the JVM. Each structural use site therefore needs a truthful SSR adapter or
`v/client-only` with an explicit fallback; the bridge does not infer server support
from the foreign library.

#### Qualified React leaves

A value-in/callback-out React component is a qualified leaf:

```clojure
(def date-picker-host
  (host/component ::date-picker DatePicker))

(v/defview booking-date [_]
  (v/client-only
   {:fallback [:input {:type :date
                       :value (v/sub [:booking/date-iso])
                       :read-only true}]}
   [date-picker-host
    {:selected (->js-date (v/sub [:booking/date]))
     :onChange (v/event [date]
                 [:booking/date-picked (from-js-date date)])}]))
```

#### Registered behaviors

A DOM-owned imperative library can use a registered behavior:

```clojure
(host/defbehavior vega-view
  {:connect    connect-vega!
   :update     update-vega!
   :disconnect disconnect-vega!
   :ssr        :inert})

(v/defview chart [{:keys [spec data on-signal]}]
  [:div.chart
   {::v/behavior [vega-view
                  {:spec spec
                   :data data
                   :on-signal on-signal}]}])
```

`host/defbehavior` binds `vega-view` to a qualified id and registers its
implementation. The use site is data: that id plus public configuration and event
intents. The browser adapter owns the code, node, and opaque memory.

Behavior semantics:

- `connect` runs after a selected commit and returns private memory;
- a registration chooses one closed host timing: `:passive` by default or
  `:layout` for measurement/mutation that must finish before paint; timing is
  registry metadata, not use-site callback syntax, and the JVM records it without
  pretending to execute it;
- `update` runs when public config changes by `rf=`;
- `disconnect` runs exactly once for that committed connection before memory is
  released; reconnect/replay is allowed, so implementations must tolerate replay;
- Freehand behavior config contains Clojure values and event intents, not callbacks,
  nodes, refs, or preconstructed instances;
- behavior id/config commits atomically with frame, dependencies, and events;
- one node has at most one behavior;
- a behavior that owns descendants marks the node opaque; common Hiccup children
  are then rejected so React and the imperative library never reconcile the same
  subtree;
- outward intents dispatch through the exact committed frame; a disconnected
  generation is inert;
- behavior context offers generation-fenced dispatch and diagnostic identity, not
  an unrestricted frame query or subscription function;
- development evidence records connect, update, reconnect, and disconnect facts
  against the occurrence and connection generation, never as domain events;
- nodes and instances never enter app-db, event vectors, structural values, or
  serializable traces;
- the JVM retains an inert behavior marker and public config, or renders an explicit
  fallback when the behavior owns visible content.

A `:layout` behavior must prove that measure-then-place produces no visible
wrong-position frame. It must also state whether it positions once, responds to
resize/scroll through bounded observers, or tracks continuously; a silent animation
loop is not permitted. The mounted overlay pilot verifies both timing and total
observer/listener cleanup.

#### Commands

A behavior may additionally register a finite command map for genuinely one-shot
host operations such as export, print, or focus-cell:

```clojure
(host/defbehavior workbook
  {:connect    connect-workbook!
   :update     update-workbook!
   :disconnect disconnect-workbook!
   :commands   {:export-xlsx export-xlsx!
                :focus-cell  focus-cell!}})

{::v/behavior
 [workbook {:instance [:invoice-sheet invoice-id]
            :document document
            :on-result [:invoice/workbook-result]}]}

{:fx [[:re-frame.freehand.host/command
       {:target [:invoice-sheet invoice-id]
        :op :export-xlsx
        :args {:filename "invoice.xlsx"}}]]}
```

Only command-addressable uses pay for `:instance`. It is caller-supplied value
identity, unique within the exact frame/root command scope—not an occurrence, key
path, selector, node, or host object. Commands are ordinary re-frame effects with
value arguments. They target only the currently committed connection, are never
queued for a future mount, and are never replayed after reconnect or trace replay.
When multiple roots sharing a frame could expose the same target, the effect names
the caller-authored Root Descriptor id; Freehand never chooses the most recently
mounted node.
Missing, duplicate, stale, or unsupported targets produce typed evidence. A
command cannot return a host handle; asynchronous completion dispatches through a
configured generation-fenced intent. Traces retain target, behavior id, operation,
generation, and outcome class, never the private instance.

Desired host state still flows through config and `update`; commands are the narrow
escape for an operation that should happen once. This is not a request/response
bus, ref registry, service locator, or catalog of framework-owned behaviors.

This is not a general `on-mount`/`on-unmount` callback. It is a finite host adapter
protocol. Mapbox, direct Vega, canvas, observers, editors, or direct SpreadJS may
fit it. Radix context/portals and hook-based React adapters remain wrappers.

### Roots, structural rendering, and SSR

Both execution modes consume one versioned Root Descriptor containing root identity,
mounted view, frame preflight, and SSR/hydration facts. The live DOM container and
Root handle remain host objects. `unmount!` performs total teardown and records the
result; it does not become a data event inside the view tree.

The descriptor is tooling/compiler data, not page-one ceremony. The single-root
paved path remains the donor shape with only the namespace changed:

```clojure
(def mounted
  (v/mount [app-root {}]
           (.getElementById js/document "app")))

(v/unmount! mounted)
```

Identity derives when unambiguous; an application supplies `:root-id` only for a
real collision or stable external identity. The advanced create/render/hydrate
operations consume the same descriptor and return an opaque handle. The same root
form is accepted by structural rendering, so a normal test does not reconstruct a
parallel boot plan. Root acceptance includes this minimal boot path, explicit-id
multi-root use, frame preflight, hydration, failed-root isolation, and total teardown.

The versioned structural host retains declared view boundaries, public props,
children, data intents/key maps, presence and top-layer metadata, error boundaries,
and host behavior/component/command markers. Host internals remain opaque.

A browser-only host leaf uses `client-only` with an explicit fallback. A host with
real server support can provide an explicit JVM/SSR adapter. A React component does
not acquire server semantics merely because it can create browser DOM.

## 6. Compiled mode

### What compilation buys

The compiled mode provides:

- direct React lowering and a JVM structural emitter;
- finite subscription, event/key-map, slot, host, presence, top-layer, error, and
  capability manifests;
- generated prop-slot comparators;
- static diagnostics and source coordinates;
- static asset and SSR evidence;
- omission of unused machinery, including the reactive ViewCell shell when proved
  safe;
- predictable failure outside the finite language.

It still pays for dynamic expressions, subscription derivation, React element
creation in dynamic regions, and React reconciliation. Compilation is a capability,
not an instruction to compile every view.

### Language boundary

| Concern | Interpreted mode | Compiled mode |
|---|---|---|
| body | full Clojure | closed compiler-visible template/control forms |
| `sub` | same-thread dynamic capture, including helpers | finite visible sites; helpers may compute values but not hide reads |
| declared reads | no `:reads` form; inline `v/sub` only | no `:reads` form; finite inline sites provide static possible-site evidence |
| heads | runtime choices among descriptors allowed | statically named descriptors or finite explicit branches |
| child markup | arbitrary Hiccup values/sequences | compiler-owned structure; no interpreted fallback |
| loops | ordinary Clojure realization | compiler-owned keyed loop; reactive rows become child views |
| props | runtime maps normalized at the edge | literal shape plus common spread forms |
| events | runtime-classified common grammar, including exact-key maps | static where knowable, honestly dynamic otherwise; same materializer and selection law |
| children | reserved trailing `:children` | identical contract |
| parameterized content | ordinary pure function permitted | lexically visible `render-fn`/`slot` |
| presence | runtime keyed plan/overrides | statically recognized plan/overrides |
| top layer | qualified desired-state facts | statically recognized desired-state facts; host action remains commit-time |
| host | qualified descriptor or behavior id/config | statically named descriptor/behavior; inert JVM marker or fallback |
| local/hooks/effects | rejected; use a host boundary | rejected; use a host boundary |
| structural output | runtime JVM interpreter | JVM emitter; equal common semantic value |

There is no “compiled except for this unknown subtree.” Recovery is to expose a
finite choice, pass a computed value, extract an interpreted or compiled child,
qualify a host leaf, register a bounded behavior, or keep the parent interpreted.
`:re-frame.freehand/v1` contains neither `v/interp` nor an automatic dynamic-markup
walk. The standard recovery for inert “markup already in hand” is
`[v/markup {:value markup}]`: an ordinary declared interpreted child. The compiled
parent sees one statically named descriptor boundary; the child owns the walk and
normal ViewCell/evidence. Manifests mark the crossing as interpreted and occurrence
evidence counts its mounts as `:interp-slots`. This is a visible boundary, not an
inline grammar valve. Any future valve is a new grammar decision and version.

`:reads` is not part of v1. Reconsider it only for a concrete SSR, catalog, or
tooling need, and only if undeclared reads are rejected before completeness is
claimed.

### Checker-first workflow

The analyzer runs read-only against an interpreted declaration before compilation
is selected and returns stable EDN:

```clojure
{:view-id          :app.people/people-list
 :source           {:file "src/app/people.cljc" :line 42 :column 1}
 :current-lowering :interpreted
 :target-grammar   :re-frame.freehand/v1
 :compile-eligible? false
 :findings         [{:id       :re-frame.freehand.compile/opaque-markup-call
                     :source   {:line 47 :column 5}
                     :form     '(render-person person)
                     :reason   :markup-hidden-from-analyzer
                     :recovery [:extract-declared-child
                                :make-template-visible
                                :keep-interpreted]}]}
```

The checker never edits code or recommends compilation from a percentage threshold.
Changing the declaration is the final step, not the discovery mechanism.

### Descriptor ABI and cross-mode calls

Both execution modes register one public descriptor shape:

```clojure
{:re-frame.freehand/view true
 :view-id                  :app.todo/todo-row
 :source                   {...}
 :lowering                 :interpreted ; or :compiled
 :props-schema             <schema-or-absent>
 :children-policy          :none} ; :optional or :required
```

The map above is the descriptor's inspection/registry projection, and its key roster
is closed in both directions — an extra key is as much a defect as a missing one.
The host `mount` entry and the structural `tree` entry are descriptor entries but are
deliberately **not** projected: browser heads resolve through the mount entry and
structural heads through the tree entry, and neither shape is a contract an
application may depend on. Signature and body generations stay internal likewise.

The public var holds a descriptor value, not an ordinary function, and a direct call
raises a didactic error naming the three legal recoveries — at runtime as well as in
checker output. Vector-head classification is total: a Freehand descriptor is an
internal boundary, a keyword is a DOM/custom element, a declared host descriptor is a
foreign boundary, and anything else is an error naming those three legal recoveries.

An interpreted parent can mount a compiled descriptor. A compiled parent can mount
a statically named interpreted descriptor through one emitted interpreted-child
boundary. Dynamic head selection belongs inside that interpreted child. The
analyzer recognizes the shared descriptor metadata as an internal view rather than
a foreign component.

### Conformance contract and donor deletion gate

This is Freehand's internal two-mode contract and the `re-frame.ui` deletion gate.
Every row must be green; parity is not a cross-product compatibility negotiation.

| Surface | Required parity |
|---|---|
| calls | declared vars are vector-called descriptors; direct calls are plain helpers only |
| identity/HMR | qualified view id plus `(parent, key-or-position)` occurrence; controller address separate; generations internal |
| children | statically named interpreted/compiled children cross through descriptors; no hidden walker |
| props | one map, reserved `:children`, stripped `:key`, shared equality/conversion; optional schema with common metadata/validation semantics |
| events | one projection materializer, options/key-map grammar, site/proxy lifetime, and atomic selected bundle |
| controlled input | one exact door predicate, frame-scoped synchronous flush, and real-browser contention matrix |
| frame | interpreted shell always observes context; compiled elision requires proof |
| subscriptions | render-only `v/sub`, value/resolution/invalidation/commit safety parity; one-shot reads remain separately named; handle counts internal |
| controller state | props-only default; ordinary frame data keyed by kind plus explicit address; semantic transitions and owner cleanup; no lifecycle cleanup |
| state/host | local/effect/ref forms rejected; opaque facts remain behind qualified boundaries |
| presence | one keyed retention/override/timeout/accessibility/test contract |
| top layer | one popover/modal desired-state pair, commit/generation law, structural metadata, and browser matrix; no neutral portal |
| behavior | one id/config/timing/optional-command protocol; commit-only connection; explicit command target; private memory; marker/fallback on JVM |
| outward React | descriptor-only stable bridge; common frame/props semantics; explicit mapper and SSR policy |
| errors | one boundary/reset/fallback/safe-intent contract plus private frame error egress; failed candidates publish nothing |
| roots | one Root Descriptor, preflight, identity, teardown, SSR, and hydration contract |
| routing link | one ordinary descriptor over Spec 012's href/click law; real anchor and native modifier behavior in both modes |
| structure | one versioned tree and conversion table; explicit host policy |
| diagnostics/tools | one versioned occurrence schema with scope/basis/completeness/loss, stable ids/source/recovery, bounded retention, and provable-only static accessibility findings |

The React and JVM emitters remain separate. They share host-neutral normalizers
where practical and prove parity through a cross-mode conformance corpus.

## 7. Practical fitness

### Programmer and AI ergonomics

Page one remains `defview`, `sub`, Hiccup, and event vectors. Compilation adds one
declaration option only where selected. The sharp helper/view rule, one props map,
qualified host boundaries, and typed checker output remove common ambiguities for
both people and coding agents.

The finite-language boundary remains visible. An interpreted view can decline a
compiler refactor without becoming second-class. The single declaration form
keeps lowering capabilities and restrictions from becoming a second authoring API.

Reusable public leaves and row/cell templates should normally be compiled once
they pass `v/check`; application pages and composition boundaries should normally
remain interpreted. This is library placement guidance, not a second ABI or a
quota. Measurements and clarity can justify exceptions in either direction.

### Re-implementing re-com

A library built on Freehand would be re-frame-native, not a drop-in continuation of
re-com’s independence from re-frame.

| Problem class | Design answer | Assessment |
|---|---|---|
| layout | semantic elements, flex/grid CSS, small props-only views | simpler; usually finite and compilable |
| controlled inputs | value + forwarded intent + projection materializer + `spread-safe` | simpler; no atom/value polymorphism or closure stack |
| buffered inputs | addressed semantic library controller with required reset key; qualified host-owned escape | architecture settled; portable claim gated by reset/caret/IME/cost harness |
| validation/status | derived subscriptions and re-frame state | traceable and renderer-independent |
| async dropdown/typeahead | props-only where practical; library semantic controller for cross-event interaction; resources/machines own async work | clear causal lifetime; correlation, supersession, debounce, and late-result races remain data and are headlessly provable |
| popover/focus/measurement | qualified popover/modal desired state; layout-timed behavior for placement/measurement; wrapper for React protocols | bounded timing and tracking policy; no neutral portal |
| enter/exit | keyed presence plus mounting/unmounting data | inspectable and deterministic |
| tables/virtual lists | keys, windowing, pure row slot; behavior/wrapper for scrolling | sufficient; row slot is load-bearing |
| parts/themes | CSS tokens, public `data-part`, bounded safe part maps, composition slots | one portable contract; no late transform seam |
| validation/docs | optional substrate schema, required by reusable-library policy | one source for dev checks, docs, catalog, generation, and AI where public |
| debugging | occurrence evidence joined to explicit controller identity, intent, cause, and loss | stronger than component-local reflection |

The library accepts values, not “atom or value,” and keeps components props-only
unless they own a documented semantic controller. It preserves caller attributes,
controlled semantics, accessibility, composition slots, semantic parts, and
diagnostics, while forbidding late transforms that rewrite keys, handlers, or
controlled props after analysis. Parts and theming are a redesign, not a
compatibility exercise; §8 pilots determine completeness.

### React-library integration

| Library shape | Representative API | Boundary | Consequence |
|---|---|---|---|
| declarative leaf | `react-vega` `spec` and options | qualified React leaf | static chart remains data-oriented |
| imperative view | Vega View API for data, resize, signals | direct Vega behavior or `react-vega` wrapper | View/listeners private; config/intents visible; disconnect tested |
| large widget | SpreadJS Workbook from `workbookInitialized`, instance methods, nested sheets | direct Workbook behavior with explicit command target, or React wrapper | one opaque owner; finite commands/events cross a small data API |
| component-as-prop | AG Grid cell renderer, drag overlay, virtual row component | `v/->react` descriptor bridge with optional top-level prop adapter | stable frame-aware React identity; foreign parameter object projected explicitly |
| compound/context/ref | Radix `asChild`, prop cloning, forwarded refs, portals | UIx/React wrapper | do not emulate React composition in neutral Hiccup |
| headless hook adapter | TanStack `useReactTable` over framework-neutral core | core as value logic or hook wrapper | do not recreate hooks; state in, intent out |
| simple controlled control | date picker/select value + callback | qualified leaf with `v/event` | no bespoke adapter per leaf |

These cases fit the three host shapes. They do not justify neutral refs, effects,
hooks, or arbitrary mount callbacks.

### Testing

| Layer | Runs | Proves |
|---|---|---|
| re-frame unit | events, subs, semantic controller transitions, resources, machines without renderer | state transition, stale-generation fencing, owner cleanup, and causal lifetime |
| structural view | JVM/common tree for both modes | branches, keys, props, text, materialized intent/key maps, presence/top-layer/behavior/error declarations, parity |
| mounted browser | React DOM, top layer, presence, behaviors/commands, wrappers, error boundaries | controlled editing, IME, focus, transitions, containment, cleanup, and third-party protocols |
| end-to-end | application browser flow | routing, network boundaries, production assembly |

The pure structural surface is small:

```clojure
(deftest save-button-carries-intent
  (let [tree   (t/render [save-button {:article-id 42}])
        button (t/find tree #(= :button (:tag %)))]
    (is (= "Save" (t/text button)))
    (is (= [:article/save 42]
           (:on-click (t/attrs button))))))
```

`render`, `find`, `find-all`, `text`, and `attrs` query semantic values. They do
not simulate behavior. The test namespace exposes the production event materializer
and a materialize-then-dispatch seam, so a structural test can select an exact-key
branch, supply literal scalar payload, and assert the same final vector production
uses. A controller test dispatches that intent through real re-frame and inspects
ordinary frame state. There is no structural `click!`, mirrored dispatcher, gesture
DSL, or fake browser.

Mounted tests use real DOM queries and one flush/act boundary. Presence uses a fake
clock for deterministic retention and a real browser for CSS/accessibility. A Vega
or SpreadJS test connects the real behavior/wrapper, drives visible behavior,
asserts outward intent, and disconnects to prove cleanup. A Radix test exercises
keyboard/focus behavior through the real primitive.

Accessibility proof has two lanes. Static checks report only facts the analyzer or
complete structural tree can prove—for example, a statically nameless interactive
element. Dynamic content and opaque foreign interiors are marked unknown, not
guessed. Roles, accessible names, keyboard behavior, focus containment/return,
background inertness, and top-layer nesting are then checked on the complete tree or
in a real browser as appropriate.

The parity corpus compares whole structural values for children, spreads, slots,
forwarded events, key maps, keys, conditional subscriptions, cross-mode children,
controller joins, presence, top-layer facts, behavior/command markers, host
fallbacks, error boundaries, frame retarget, and HMR fencing.

### Fitness-harness closure

The product spine carries the harness obligations that are otherwise easy to lose
during decomposition:

| Harness pressure | Required proof |
|---|---|
| route links are a dominant application shape | real href, native modifier/middle/download/target behavior, caller veto, SSR shell, and absent-routing diagnostics |
| asynchronous controls race | correlation, debounce cancel-and-replace, supersession, stale completion, unmount, and retry tests over ordinary re-frame state |
| per-keystroke state traffic has a cost | event/write/sub-recompute/render-commit counts for a four-field form and 100-cell editing grid, in both modes, plus the uncontrolled grid alternative |
| overlays measure and track | named before-paint phase, zero wrong-position paint, declared tracking frequency, and cleanup after close/unmount/re-entry |
| accessibility diagnostics can overclaim | static findings only when provable; complete-tree and browser checks own dynamic/composite semantics |
| roots can be correct but unpleasant | minimal one-root boot, structural reuse of the same form, explicit multi-root identity, hydration, and total teardown examples/tests |

### Debugging

#### Evidence model

Both execution modes feed one versioned, host-neutral, read-only evidence schema
keyed by runtime occurrence plus internal generation. Each committed record uses
the same view id, source, frame id, lowering, cause, lifecycle, and connection
vocabulary. A caller-supplied controller address or command target may be joined to
that record, but never replaces occurrence identity.

Every evidence projection states four independent facts: **scope**, **basis**,
**completeness relative to that scope**, and **loss**. In particular:

- a compiled possible-site manifest is static proof complete for compiler-visible
  sites in its grammar version;
- one selected commit's reads/events/tree are observations complete for that exact
  generation unless capped;
- an interpreted union from a named CI corpus is observed evidence, not proof of
  all possible executions;
- an intentional wrapper interior is opaque rather than empty;
- any cap marks the projection incomplete and records reason and dropped count.

The finite basis vocabulary includes `:static-proof`, `:declaration`,
`:observation`, and `:opaque`. A declaration may claim complete possible reads only
when the analyzer/runtime rejects undeclared reads; an unenforced annotation remains
an incomplete declaration, never proof.

Read occurrences, distinct resolved targets, and compiler sites remain separate
cardinalities. Props-only boundaries are explicit. Host leaves/behaviors expose
qualified identity, public config/props, connection generation, outward intents,
and command outcome class—never private instances, hooks, or stacks. Controller
evidence exposes kind, explicit address, generation, last semantic cause, retention
owner, duplicate ownership, and orphan age without exposing a generic mutation API.

The existing `view-manifest`, `mounted-views`, `explain-render`,
`view-dependencies`, and `view-event-sites` projections gain lowering and
scope/basis/completeness/loss markers rather than forking by execution mode.
Mount, update, reconnect, disconnect, HMR, presence, behavior, command, and error
boundary phases are timeline facts; they never dispatch domain events.

#### Retention and warning policy

Retention reuses the existing per-frame retained-event ring and its one control.
Mounted occurrence records are live projections. Freehand adds no second history
store or Root Descriptor retention knob. Detailed evidence is compiled out of
production; only deliberately enabled aggregate metrics and the minimal D019 error
envelope remain, each with honest loss markers.

Recoverable authoring warnings emit once per stable source site and warning kind.
Default-on warnings are reserved for detected contract misfires whose symptom would
otherwise surface far away; predictive and quality lints are opt-in `v/check`
categories. Hard errors are reserved for semantic corruption or ambiguity such as
malformed trees, illegal compiled forms, render-phase violations, invalid event
outcomes, duplicate writable ownership, and invalid host targets. Categories remain
configurable without changing semantics.

#### Questions tools answer

A programmer can answer:

1. Which occurrence rendered, and where is its source?
2. Which frame incarnation and descriptor generation did it use?
3. Was the cause props, a subscription, frame retarget, HMR, presence, behavior,
   foreign host, or mount?
4. Which reads and event sites were selected in the same generation?
5. Which semantic controller or command target is joined to this occurrence, and
   who owns its retention?
6. What scope and basis does the evidence cover, is it complete there, and what was
   lost?
7. What is the smallest recovery for the failure?

Compiled views have a deliberately cheap demote-to-debug path: remove
`{:compiled true}`, reproduce with normal Clojure REPL evaluation and stack traces,
fix the declaration, run `v/check`, and restore the option. Call sites, structural
tests, descriptor identity, and evidence queries remain unchanged throughout.

## 8. Performance, implementation, and scope

### Optimization workflow

Compilation is required as a product capability, not as a mandatory percentage.

1. inspect render cause and dependency fan-out;
2. narrow subscriptions and choose keyed boundaries;
3. window large collections or use a purpose-built host integration;
4. compile a remaining hot boundary, or compile for static evidence/assets;
5. measure again with the same counters.

The profiler reports view id/source, cause, self time, normalized node count,
subscription occurrences, distinct targets, host time, and evidence loss. Counters
survive compilation; the manifest adds possible-site knowledge. The framework does
not rewrite declarations or expose a cache tower.

If interpreted views are fast, they remain interpreted. If many need compilation,
the compiled mode is a coherent path. If a host library or React reconciliation
dominates, another Hiccup compiler is not the answer.

### Measurement obligations

Performance claims stay attached to a small repeatable harness rather than
folklore or a compilation quota.

| ID | Claim | Representative workload | Required evidence |
|---|---|---|---|
| B1 | direct lowering reduces view work | the same 10³–10⁴-node finite template in interpreted and compiled modes | p50/p95 self time, per-node cost, allocation, equal structural/browser output |
| B2 | capability elision matters | cells-shaped mass mount with three-read and sub/event/presence/host-free arms | mount decomposition, retained objects, exact omitted-ViewCell count |
| B3 | generated comparison isolates rows | 10,000 keyed records, repeated with a visible window of about 40 | committed/skipped rows, comparator time, end-to-end latency |
| B4 | controlled editing survives contention and scales honestly | typing, selection, and IME while a heavy sibling is dirty at 20 Hz; four-field form and 100-cell editing grid in both modes | event-to-commit and settlement/presentation p50/p95/p99, zero dropped input, stable caret/composition, event/write/sub-recompute/render-commit counts per keystroke |
| B5 | shipped cost remains bounded | representative interpreted, compiled, and mixed production bundles | gzip, parse/eval, initial mount, per-promotion delta, reachable runtime modules |

B1–B5 exist from the first working relevant slices. Deterministic properties are
hard gates on ordinary CI: equal output, zero dropped input, exact attributable
commit counts, manifest/cell elision, row commit counts, and bundle reachability.
Wall-clock and byte distributions are mandatory evidence, not automatic numeric
pass/fail thresholds. They run on a pinned scheduled/release worker, with a small PR
smoke subset only where stable. An adverse trend requires attribution and an
explicit disposition; a public numerical claim is withdrawn or qualified when its
cited artifact no longer supports it.

Every result names revision, fixture parameters, build/instrumentation mode,
browser/runtime, hardware class, warm-up/sample policy, distribution, and baseline.
The baselines are interpreted versus compiled Freehand, the absorbed tier versus a
named donor cut, before versus after the implementation change, and substrate self
time versus end-to-end host time. This policy sets no folklore threshold, does not
auto-promote declarations, and does not require compiled mode to win every workload.

### Absorption and retirement of `re-frame.ui`

Implementation reuse does not confer API status. The analyzer, both emitters,
ViewCell reactor, manifest/elision machinery, diagnostic taxonomy, presence
runtime, structural test surface, and useful evidence tooling move under Freehand
ownership. Donor-contract differences have explicit dispositions:

| Donor difference | Freehand disposition |
|---|---|
| `local` and its placement machinery | delete unconditionally; controlled props are normal and narrow library-owned semantic controllers replace legitimate cross-event protocols; the buffered controller's portable claim remains acceptance-gated |
| placeholder provenance | materialize top-level `::v/value` / `::v/checked` / `::v/key` at the Freehand event site from live scalar payload, then use ordinary dispatch; no donor spelling or general dispatch payload arity survives |
| instance state | do not copy `local`, generic storage verbs, or derived writable anchors; controller state is ordinary frame data keyed by library controller kind plus explicit caller-supplied semantic address, joined to occurrence only in evidence |
| compiled parent → interpreted child | add the one emitted descriptor boundary required for promotion of one declaration at a time; do not hide an interpreter walk inside compiled markup |
| controlled scheduling | both modes use the final-normalized native `:on-input`/`:on-change` predicate, one frame-scoped synchronous scheduling implementation, and one contention/caret/IME matrix; `beforeinput` stays outside the door |
| refs, effects, and the React hook tier | delete as neutral forms; one-node behavior owns bounded DOM/imperative lifecycle and optional explicit-target commands, while UIx/Helix wrappers own React protocols. Absorb `spread-safe`/`spread` and `render-fn`/`slot` as common grammar without the donor hook tier |
| key-condition event maps | admit the closed exact-key `:on-key-down`/`:on-key-up` form with existing event values and pre-dispatch mechanics; composition, modifiers, predicates, and richer chords remain `v/event`/wrapper work |
| presence | absorb the runtime and make the interpreted mode join the same keyed retention/override/timeout/accessibility/test contract |
| callable JVM view values | replace with the shared non-`IFn` descriptor; direct invocation remains a helper-only operation and no callable compatibility layer survives |

The donor's ordinary route-link implementation crosses by rename as
`v/route-link` over Spec 012's late-bound semantics; it is common framework view
code, not a compiler form or a second routing contract.

Direction is fixed: donor mode now; the alpha surface may coexist only while code,
Spec 004, tools, and consumers migrate; delete the standalone artifact when §6 and
the component/library pilots are green and consumers have moved—a gate, not a date.
The new package never depends on the donor. Any temporary forwarding facade lives
only in `re-frame.ui`, gains no semantics, and is not Freehand API. EP-0030 and the
detailed donor record remain evidence; EP-0036 owns programme slicing and migration.

### Technical dependency order

This order expresses technical dependencies, not one task or waterfall stage per
number. EP-0036's vertical F0–F6 slices take the thinnest runnable path through it;
no slice waits for an entire architectural layer when its own prerequisites are
already green.

1. **Common ABI:** descriptor, props/children/key/schema metadata, semantic tree,
   event materializer/options/key maps, controlled predicate, frame binding,
   controller identity/evidence, presence, top-layer facts, behavior/command marker,
   error boundary, Root Descriptor, and evidence schema.
2. **Atomic shell:** one selected render-bundle commit, with abandoned render, HMR,
   frame retarget, key reorder, disconnect, and same-thread capture tests.
3. **Interpreted mode:** full-Clojure interpreter, dynamic target capture, HMR
   descriptors, JVM structure, props-only/read evidence, and qualified host
   descriptors.
4. **Compiler transplant:** fold the useful analyzer and emitter code into Freehand,
   recognize common vars/descriptors and `{:compiled true}`, reject unsupported
   forms, normalize forwarded projections, and retain separate React/JVM emitters.
5. **Checker and conformance:** read-only findings, cross-mode structural
   equality, event/controller/frame/HMR/error parity, schema policy, and production
   isolation of test/debug code.
6. **Browser laws:** controlled input/IME, keyed presence/re-entry/accessibility,
   top-layer reconciliation and layout timing, behavior
   command/commit/replay/cleanup, provable-only accessibility diagnostics, error
   containment, route-link native behavior, root ergonomics/teardown, fallback
   hydration.
7. **Component pilots:** controlled field, buffered field, popup, async typeahead,
   virtual table with row slot, public schemas, tokens, and semantic parts. The
   typeahead includes debounce/supersession/stale-result races; the field/table pair
   publishes per-keystroke work counts. Start controllers as ordinary re-frame
   registrations; extract no DSL before repeated mechanics exist.
8. **Library pilots:** React-Vega/Vega, SpreadJS/editor behavior and commands, Radix,
   TanStack Table, and an AG Grid-style `v/->react` cell through the appropriate
   leaf, bridge, behavior, or wrapper.

B1–B5 fixtures arrive alongside the first step that can run each workload, rather
than as a final optimization project.

### Release acceptance

Every §6 row is mandatory; the stage table below determines when its evidence is
required and when the donor may be deleted.

The buffered controller protocol is settled without making widget events substrate
vocabulary, but its portable claim still requires the §3 harness.

Release evidence is staged:

| Stage | Required gate |
|---|---|
| implementation/pre-alpha | each available B1–B5 fixture asserts semantic/correctness invariants and publishes diagnostic timing/byte distributions |
| donor deletion | all §6 rows green; named component and library pilots pass; donor worklist disposed; B1–B5 evidence published for the absorbed implementation |
| beta | deterministic count/correctness/elision/reachability checks stay green; current B1–B5 distributions name baselines; every adverse trend has an explicit disposition |

Timing and byte measurements are evidence, not release thresholds. A numerical
product claim is itself acceptance-gated by its cited evidence.

### Deliberate non-goals

- a second compiler, `v/interp`, or hidden compiled interpreter fallback;
- a permanent `re-frame.ui` sibling or compatibility facade;
- preserving every `re-frame.ui` API because implementation code proved useful;
- a third, “unprofiled” Freehand mode for local state, hooks, refs, or effects;
- automatic promotion or a “hot 5%” rule;
- callable declared views or a region DSL;
- a `:reads` query-template language in `:re-frame.freehand/v1`;
- renderer-derived state anchors or a public `v/self` handle;
- generic component-local maps/storage verbs, framework widget event namespaces, or
  a controller/reducer DSL;
- render/unmount ownership of domain resources;
- neutral hooks, refs, effects, portals, or arbitrary lifecycle callbacks;
- a queued/replayed host command bus, host-handle registry, or service locator;
- a payload-map arity on general re-frame dispatch;
- React callback-protocol guessing;
- arbitrary late tree transforms as a portable theme system;
- automatic app-db/event-history capture in error reports;
- Replicant-style aliases without demonstrated component-library demand;
- structural click simulation or a second behavior-testing language;
- serializing DOM nodes, React elements, callbacks, cleanup functions, or host
  instances;
- claiming separate emitters are literally one implementation.

The ambition is concentrated in a small declarative core plus bounded edge
contracts: declared view, props/children/key, render-only `sub`, event intent,
compiled mode, semantic library controllers, keyed presence, DOM top-layer state,
qualified host boundary and commands, semantic tree, Root Descriptor, error
boundary, checker, and versioned evidence. It does not require a feature for every
React or Reagent idiom.

## References

### Repository evidence and donor code

- `spec/004D-Freehand-Compiled-Grammar.md` — the donor-era compiled view language,
  moved here by EP-0036's ownership cut; it owns the compiled tier only.
- `spec/004-Views.md` — the common Freehand declaration, authoring, semantic, and
  host-boundary contract, established as a skeleton by the same cut; each section
  lands with its vertical slice.
- `spec/004B-UI-Tree-and-Conversion.md` — semantic tree and conversion tables.
- `spec/004C-Roots-and-Mount.md` — Root Descriptor, identity, preflight, hydration,
  mount, and teardown.
- `implementation/ui/src/re_frame/ui/compiler/analyze.cljc` — finite grammar,
  manifests, controlled predicate, and internal-view classification.
- `implementation/ui/src/re_frame/ui/compiler/emit_cljs.cljc` and
  `emit_jvm.cljc` — React and JVM lowering.
- `implementation/ui/src/re_frame/ui/reactive.cljc`, `events.cljs`, and
  `viewcell.cljs` — subscriptions, committed callbacks, frames, and selection.
- `implementation/ui/src/re_frame/ui/presence_runtime.cljc` — keyed presence and
  fake-clock retention.
- `implementation/ui/src/re_frame/ui/test.cljc` and `tool.cljc` — test and evidence
  surfaces.
- `examples/real-apps/realworld_resources/ui_editor.cljc` and
  `article_editor.cljs` — compiled and Reagent application forms.
- `studio/fitness-harness.md` — component-library fitness
  cases.

### External

- [Replicant](https://github.com/cjohansen/replicant),
  [Hiccup](https://replicant.fun/hiccup/),
  [lifecycle hooks](https://replicant.fun/life-cycle-hooks/), and
  [aliases](https://replicant.fun/alias/).
- [react-vega](https://github.com/vega/react-vega#readme).
- [SpreadJS React guide](https://developer.mescius.com/spreadjs/docs/v19/javascript-frameworks/UsingSpreadSheetswithReact)
  and [SpreadSheets events](https://developer.mescius.com/spreadjs/docs/javascript-frameworks/UsingSpreadSheetswithReact/UsingtheSpreadSheetsElement).
- [Radix composition](https://www.radix-ui.com/primitives/docs/guides/composition).
- [TanStack React Table](https://tanstack.com/table/latest/docs/framework/react/react-table).
- [React Testing Library](https://testing-library.com/docs/react-testing-library/intro/)
  and [Playwright component testing](https://playwright.dev/docs/test-components).
- [day8/re-com](https://github.com/day8/re-com).
