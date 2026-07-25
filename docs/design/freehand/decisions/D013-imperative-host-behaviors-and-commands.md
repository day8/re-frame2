# D013 — Imperative host behaviors and commands

Status: **Ruled**
Ruling: **Use registered connect/update/disconnect behaviors with closed
`:passive`/`:layout` timing, a bounded command channel, and semantic-id targets;
defer any scroll-window primitive.**

Horizon: **Immediate**

## Decision

What is Freehand's smallest honest integration boundary for an imperative
library that owns a DOM node or opaque instance, and should that boundary also
support commands from re-frame into the live instance?

This decision must settle:

1. whether the registered behavior/attach protocol is part of Freehand;
2. what lifecycle and context the registered implementation receives;
3. whether commands are part of the protocol or are left to wrappers and
   application-specific registries; and
4. how a command identifies one live instance without turning renderer position
   into application identity.

The answer is needed before absorbing `re-frame.ui`. Its refs, effects, and
compiled React hook tier are not crossing into Freehand. The behavior registry
and explicit React wrapper must cover the useful jobs instead; otherwise those
forms will return under less coherent names.

## The problem

Some integrations are not meaningfully described by immutable React props
alone. Vega's direct View API, SpreadJS, Mapbox, CodeMirror, canvas renderers,
observers, and measurement code all create opaque state, update it in place,
listen for host events, and release resources.

A spreadsheet is a tangible example:

```clojure
(host/defbehavior workbook
  {:connect    connect-workbook!
   :update     update-workbook!
   :disconnect disconnect-workbook!})

(v/defview sheet [{:keys [document on-change]}]
  [:div.sheet
   {::v/behavior
    [workbook {:document document
               :on-change on-change}]}])
```

The tree should reveal that the node has a workbook behavior and show its public
configuration. It must not contain the Workbook object, a DOM node, a cleanup
closure, or an application-created ref.

There is a second direction. An application may need to ask the current workbook
to export, focus a cell, print, or scroll to a location:

```clojure
{:fx [[:re-frame.freehand.host/command
       {:target [:invoice-sheet invoice-id]
        :op     :export-xlsx
        :args   {:filename "invoice.xlsx"}}]]}
```

Desired state can usually flow through behavior config and `update`. A one-shot
operation such as export is different: representing it as a sticky prop invents
edge detection, acknowledgement, and replay rules. Without a bounded command
path, programmers will retain refs, build private instance registries, or wrap
every imperative library in a bespoke React component.

The danger is the opposite extreme: a general command bus plus renderer-derived
addresses becomes a service locator into live UI objects. It weakens testability,
makes commands race mounting, and quietly turns render identity into domain
identity.

## Settled constraints

This decision does not reopen the following points shared by the two designs:

- Freehand has two inward host routes: D022's qualified React host descriptor and
  this decision's registered one-node behavior. A simple React leaf and a
  hook-owning UIx/Helix/React wrapper are implementation shapes behind the same
  D022 descriptor, not separate host kinds. A behavior is not a neutral
  hook/effect system.
- The use site is data: a qualified behavior id plus public configuration and
  outward event intents. The implementation is registered code.
- `connect` runs only after a selected commit. `update` observes committed
  `rf=` changes. `disconnect` runs exactly once for each committed connection.
- Connect/disconnect may be replayed across StrictMode, HMR, or host recovery;
  implementations must tolerate a later fresh connection.
- Configuration contains Clojure values and event intents, never callbacks,
  nodes, refs, cleanup functions, or preconstructed host instances.
- Opaque memory remains private to the host adapter. It never enters app-db,
  structural trees, event vectors, or serializable traces.
- One DOM node has at most one behavior. If the behavior owns descendants, the
  node is opaque and Freehand children are rejected.
- Outward events dispatch through the exact committed frame and connection
  generation. A disconnected generation is inert.
- Lifecycle facts are tool evidence, not domain mount/unmount events.
- The JVM retains an inert behavior marker and public config, or renders an
  explicitly declared fallback.
- `re-frame.ui` is a donor, not a second product. `local`, refs/effects as view
  forms, and the compiled hook tier die with it; no compatibility escape tier
  survives.

## Options

### Option A — wrappers only

Do not put an imperative behavior protocol in Freehand. Every integration uses a
React/UIx/Helix wrapper, which owns refs, effects, state, and the third-party
instance.

Consequences:

- Freehand stays very small and delegates to a well-understood React mechanism.
- React-owned libraries fit naturally.
- Direct DOM libraries acquire unnecessary React component ceremony.
- Public configuration and lifecycle become opaque to JVM tests and tools.
- Every wrapper invents its own committed-frame, replay, cleanup, and command
  discipline. The old effect/ref jobs have not disappeared; they have merely
  fragmented.
- Non-React hosts cannot reuse the integration declaration.

This is a good escape hatch, but an incomplete primary answer.

### Option B — minimal behaviors, no commands

Adopt only `connect`, `update`, and `disconnect`. All application-to-instance
requests must be expressed as desired configuration. True commands use an
explicit wrapper or an application-owned side registry.

Consequences:

- The lifecycle contract is compact, inspectable, and sufficient for charts,
  observers, measurement, and most value-synchronised widgets.
- It keeps Freehand out of instance addressability.
- Export, focus, scroll, print, editor transactions, and similar operations have
  no paved path.
- Teams will either model commands as awkward pulse props or recreate a less
  disciplined target registry. Those workarounds are difficult to inspect and
  clean up.

This is Codex's smallest surface, but it leaves an important part of the
SpreadJS/editor class deliberately unsolved.

### Option C — the full attach surface

Adopt Fable's attach registry including a context with dispatch, frame access,
anchor identity, arbitrary named commands, and framework-provided behaviors such
as a scrolling/window behavior.

Consequences:

- It covers both events-out and commands-in with one data-oriented abstraction.
- Active attachments and command traffic can be inspected and traced.
- Direct frame access encourages hidden subscriptions and stale imperative reads
  instead of values being supplied through config.
- Targeting derived anchors contradicts the rule that Freehand does not invent
  application-state or command identity from render position.
- A growing set of blessed behaviors would turn a small host protocol into a UI
  service framework.
- The initial contract would be larger than the library pilots have justified.

### Option D — behaviors plus a bounded, data command channel

Adopt the minimal behavior lifecycle and add optional registered commands, with
strict limits:

- commands are finite keyword operations registered with the behavior type;
- arguments and recorded outcomes are values;
- a command targets a caller-supplied semantic instance id declared at the use
  site, never a derived occurrence, key path, DOM selector, or host object;
- targets must be unique among live connections in their command scope;
- commands run only against the currently committed connection, are never
  queued for a future mount, and are never replayed after reconnect;
- a missing, duplicate, stale, or unsupported target produces typed evidence;
- return values are not host handles; asynchronous success/failure returns
  through configured event intent;
- behavior context offers committed-generation dispatch and diagnostic identity,
  but not an unrestricted frame query function.

For example:

```clojure
(host/defbehavior workbook
  {:connect    connect-workbook!
   :update     update-workbook!
   :disconnect disconnect-workbook!
   :commands   {:export-xlsx export-xlsx!
                :focus-cell  focus-cell!}})

(v/defview sheet [{:keys [sheet-id document on-change]}]
  [:div.sheet
   {::v/behavior
    [workbook {:instance  [:invoice-sheet sheet-id]
               :document  document
               :on-change on-change}]}])
```

The exact location of `:instance` in the final schema is secondary. Its law is
not: it is caller-authored value identity and is required only for a
command-addressable behavior.

Consequences:

- The common case pays only the three lifecycle operations.
- The real command-shaped cases get one traceable route instead of refs and side
  registries.
- The framework must maintain a small live target index and specify failure and
  teardown races.
- Commands can still be overused where configuration or domain events would be
  clearer; diagnostics and documentation must teach the distinction.
- Because commands are effects, structural tests assert their data and unit tests
  exercise the event that emits them; mounted tests prove the real host action.

## Recommendation

Choose **Option D**, under the public term **behavior** rather than introducing a
second `attach` concept.

The base API should remain the Codex protocol: `host/defbehavior` and a qualified
behavior value on one node, with `connect`, `update`, and `disconnect`. A
registration chooses `:timing :passive` (the default) or `:timing :layout` for
host work that must finish before paint; this is closed registry metadata, not a
general lifecycle callback. Add the bounded command channel as an optional
capability of that same registry. Do not add general refs, effects, mount
callbacks, direct frame reads, derived command anchors, or a catalogue of
framework-owned behaviors.

This is the smallest surface that honestly replaces the relevant ref/effect jobs
being retired with `re-frame.ui`. It accepts Fable's strongest command argument
without accepting the larger attach context or renderer-derived address model.

The behavioral split should be taught plainly:

- **state/configuration changed** → event updates app state → view supplies new
  config → behavior `update` reconciles the host;
- **host emitted information** → behavior maps it through a configured intent
  using committed-generation dispatch;
- **perform this one-shot host operation now** → one data command effect;
- **React owns the protocol** → use a wrapper, not a behavior.

### Proposed command semantics

1. A command is produced by a normal re-frame handler as an effect value.
2. The host adapter resolves the caller-supplied target in the effect's exact
   frame/root command scope. If multiple roots sharing a frame can expose the same
   target, the effect must name the caller-authored Root Descriptor id; Freehand
   never guesses from whichever DOM node mounted last.
3. It snapshots the active connection generation and invokes the registered
   operation synchronously or starts its host-owned asynchronous work.
4. If the connection changes before an asynchronous completion, the old context
   is inert. Completion may dispatch only through the generation-fenced context.
5. The trace records target, behavior id, operation, generation, and outcome class;
   it does not record the host instance.
6. No command is retained for a target that is absent. A future mount must be
   driven by state/config or a fresh event, not an old imperative request.
7. Trace/epoch replay records command data but never re-invokes the host operation;
   a replay needs a fresh live command to cross the imperative boundary.
8. The test/tool plane exposes read-only active-connection and command-traffic
   projections without exposing private instances or adding application events.

## Consequences and cautions

- Explicit target ids add a small amount of ceremony only to command-addressable
  integrations. Passive charts and observers need none.
- A command id is not component state identity. Applications may deliberately use
  the same domain value for both, but Freehand neither derives nor couples them.
- `focus` and `scroll` do not automatically justify commands. Native attributes,
  state-driven config, and browser APIs expressed by a small behavior remain the
  first choices.
- High-rate editor content should normally remain host-owned and commit on an
  explicit boundary. An `update` must diff against the host's current value so an
  echoed app update does not reset selection or composition.
- A command registry is not a request/response RPC layer. If a result matters to
  domain state, the behavior dispatches a configured result intent.
- No bundled `:v.scroll/window` behavior should be accepted until a virtual-list
  pilot demonstrates that the reusable implementation belongs in the substrate
  rather than a component library.

## Implementation evidence

The ruled contract remains implementation-proven when one direct Vega integration
and one SpreadJS- or editor-shaped integration demonstrate:

- commit-only connect and atomic config/event publication;
- layout-timed measure-then-place without a visible wrong-position paint;
- update without cursor/selection destruction;
- disconnect exactly once, including root teardown, HMR, and presence removal;
- stale outward callbacks becoming inert;
- a command succeeding against one explicit target and failing visibly against a
  missing, duplicate, or disconnected target;
- no host object in app-db, the structural tree, event values, or trace payloads;
- inert JVM output or an explicit visible fallback; and
- structural, mounted, and cleanup tests using the common Freehand test surface.

## Dependencies and what this unlocks

Depends on:

- the common descriptor and semantic-tree ABI;
- atomic selected render-bundle commit;
- exact root teardown and occurrence/generation tracking;
- the re-frame effect path and versioned evidence schema; and
- the absorption disposition for `re-frame.ui` refs/effects/hooks.

Unlocks:

- deletion of the donor's ref/effect placement machinery;
- direct Vega, SpreadJS, Mapbox, canvas, editor, observer, and measurement pilots;
- a principled answer for cleanup and outward host events;
- D014's sharper boundary between behaviors and React wrappers; and
- D015's placement behavior without making portals or generic lifecycle public.

## Source basis

- [Codex design — Host ownership routes](../codex-design.md#host-ownership-routes) defines
  the minimal registered behavior and its lifecycle laws.
- [Codex design — React-library integration](../codex-design.md#react-library-integration)
  classifies direct Vega and SpreadJS as behavior-or-wrapper integrations.
- [Codex design — Absorption and retirement](../codex-design.md#absorption-and-retirement-of-re-frameui)
  retires neutral local/ref/effect/hook forms.
- [Fable design §2.5](../fable-design.md#25-the-data-orientation-doctrine) proposes
  the attach context, active registry, and commands-in/events-out surface.
- [Fable design §3.4](../fable-design.md#34-shape-absorption-operator-ruling) assigns
  the donor's host-form jobs to the registry and wrapper.
- [Fable design §8](../fable-design.md#8-for-the-operator) records the richer
  attach/commands surface as open question Q7.
