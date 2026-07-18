# Story Tool Architecture — Decision Packet

**Decision issue:** `rf2-m6tu`

**Cross-check:** `rf2-94b0`

**Status:** Ready to lock

**Scope:** The architectural boundary of Story, not a backlog of every desirable Story feature

## Posture

Story is a pre-alpha tool, so compatibility is not yet a reason to preserve an awkward design. The target is a small, elegant, unusually powerful system that a trusted programmer — and the AI working with them — can understand and drive without ceremony.

That does not justify a platform inside the platform. The right architecture:

- keeps the authoring surface data-first and small;
- reuses re-frame2's frames, registries, events, snapshots, and diagnostic tools;
- makes interactive and headless operation two views of the same artefacts;
- prefers explicit escape hatches over policy engines;
- keeps production output clean; and
- assigns each capability to one owner.

The canonical unit remains the **variant frame**. A Story variant is registered EDN, receives a fresh frame when run, and follows one lifecycle:

```text
loaders → setup events → render → script/assertions
```

Human UI, tests, MCP, and other tools must consume that same registration, lifecycle, result, and snapshot model. A second agent-only artefact model would be architectural failure, even if its individual tools looked convenient.

## Context and Existing Constraints

The normative story contract already settles much of the shape:

- a variant is data, not a bag of function callbacks;
- each variant is isolated in a frame;
- modes and substrates are explicit snapshot axes;
- assertions are structured records;
- workspaces are registered data layouts; and
- external tools use public Story primitives rather than reaching into implementation atoms.

The implementation now proves those foundations are viable:

- all nine registration macros route through one compile-time `enabled?` gate;
- `run-variant` returns one unified result shape;
- loader completion has an explicit `:loaders-complete-when` escape hatch;
- multi-substrate rendering and inline failure projection exist;
- Story-MCP is a separate artefact;
- `reg-mode` contributes to snapshot identity; and
- Story embeds Xray's individual panels, including its machine inspector, instead of cloning their internals.

The two research passes converge on the seven decisions below. `rf2-94b0` also surfaces modes and the diagnostic ownership boundary as real architecture choices rather than feature polish. Those are included after the seven calls.

## Decision Summary

| # | Decision | Lock |
|---|---|---|
| 1 | MCP placement | Separate `tools/story-mcp` artefact over Story's public API |
| 2 | Substrate failure behaviour | Render every selected substrate; report failures inline per substrate |
| 3 | Assertion failure mode | Record and continue; adapters translate the final result into host failures |
| 4 | Long-lived loader completion | Simple default plus explicit `:loaders-complete-when` |
| 5 | Workspace persistence | Automatic local layout plus explicit durable EDN/transit export |
| 6 | Production elimination | Every Story registration and chrome entry passes one compile-time gate |
| 7 | 10x/Xray integration | Embed Xray-owned panels; do not reimplement diagnostic views |
| 8 | Additional: modes | First-class registered EDN tuples and a snapshot axis |
| 9 | Additional: machine visualisation | Xray/machines tooling owns charts; Story only supplies context and embeds |

## 1. MCP Is a Separate Artefact

### Decision

Keep the protocol server in `tools/story-mcp`. Story core exposes ordinary CLJ/CLJS functions; Story-MCP owns JSON-RPC, stdio, tool schemas, permissions, pagination, and wire redaction.

```clojure
;; Story core
(story/variants-with-tags #{:checkout})
(story/run-variant :story.checkout/card-declined
                   {:mode :Mode.app/dark-mobile})
(story/variant->edn :story.checkout/card-declined)

;; Story-MCP translates its wire calls to those same functions.
```

A live browser application remains accessible through the project Tool Pair. Story-MCP must not grow a second browser transport or pretend its standalone JVM registry is the browser's registry. Capability absence is an explicit error, never a misleading empty answer.

### Options Considered

**Put MCP in Story core.** This shortens the initial call path, but forces every Story user to carry transport, codec, schema, and protocol concerns. It also couples the ergonomic author API to a faster-moving agent protocol.

**Separate MCP artefact over a public Story API.** There is one small adapter boundary and independent release cadence. The same core functions remain useful from tests, REPLs, build tools, and the Tool Pair.

**MCP as a remote browser service.** This provides live state, but duplicates the Tool Pair's transport, authentication, attachment, and failure model.

### Consequences

- Story core stays useful without MCP dependencies.
- Agent operations mirror human operations and unified result records.
- MCP writes can be gated without weakening the ordinary runtime API.
- Browser-only requests must say that the browser is unreachable and point to the Tool Pair route.
- The public Story API becomes a deliberate product surface and needs contract tests.

This is a clean seam, not premature modularity: protocol transport is a genuinely different responsibility and already has a separate consumer population.

## 2. Substrate Failures Stay Inline

### Decision

`:substrates` is an opt-in axis on stories and variants. Story renders each selected, registered substrate independently and keeps a failure inside that cell.

```clojure
(story/reg-variant :story.ui.button/all-substrates
  {:substrates #{:reagent :uix :helix}
   :args       {:label "Save"}})
```

Conceptually:

```text
Reagent  [rendered button]  PASS
UIx      [rendered button]  PASS
Helix    [exception card]   FAIL — source and stack
```

A Helix failure must not blank the Reagent or UIx examples, abort the whole matrix, or hide evidence already collected. The unified run result carries substrate identity on the relevant sub-run and assertion records.

### Options Considered

**Fail the complete variant on the first substrate error.** Simple, but destroys comparison value and makes the least portable implementation control the entire experience.

**Make users register separate variants per substrate.** Explicit, but creates repetitive catalogue entries and obscures that the behaviour is intended to be identical.

**One variant with per-substrate cells and inline failure.** Preserves comparison, localises failure, and keeps substrate in snapshot identity without multiplying authoring.

### Consequences

- The UI needs a real error boundary per cell.
- Aggregate status may be failing while successful substrate cells remain usable.
- Snapshot and failure identity include substrate.
- Story does not promise automatic semantic equivalence between adapters; it gives the programmer excellent evidence to establish it.

## 3. Assertions Record, Continue, and Aggregate Once

### Decision

Assertion handlers never use throwing as their ordinary failure signal. They append structured records and allow the script to continue. The run computes one authoritative `:status`; host adapters then turn the records and run-level verdict into `clojure.test`, `cljs.test`, CI, UI, or MCP output.

```clojure
{:status :fail
 :assertions
 [{:assertion :rf.assert/path-equals
   :source    {:file "checkout_stories.cljs" :line 42}
   :expected  :declined
   :actual    :pending
   :status    :fail}
  {:assertion :rf.assert/effect-emitted
   :expected  :analytics/decline-shown
   :status    :pass}]}
```

The ergonomic test surface remains small:

```clojure
(deftest card-declined
  (story/is :story.checkout/card-declined))
```

`story/is` reports useful per-assertion failures while agreeing with the unified run verdict. Unexpected exceptions are still errors: Story captures and projects them; it does not mislabel them as assertion mismatches.

### Options Considered

**Throw immediately.** Familiar to unit-test libraries, but loses later failures, fights asynchronous event drains, and produces less useful UI and agent evidence.

**Record failures but let every consumer recompute pass/fail.** Flexible, but creates multiple verdict algorithms and eventual false-green results.

**Record all outcomes and aggregate once.** Maximum evidence with one truth.

### Consequences

- Scripts can reveal several independent defects in one run.
- `:cannot-run` remains distinct from pass and fail.
- Result records require a stable schema, source coordinates, and privacy handling.
- Test runners stay strict; “record, don't throw” is not “ignore failure.”

## 4. Loader Completion Uses a Minimal Explicit Escape Hatch

### Decision

Keep simple loaders automatic and give long-lived effects one declarative completion hook:

```clojure
(story/reg-variant :story.dashboard/live-feed
  {:loaders               [[:feed/connect]]
   :loaders-complete-when [[:rf.assert/dispatched?
                            [:feed/first-domain-message]]]
   :loaders-teardown      [[:feed/disconnect]]
   :setup                 [[:dashboard/select :today]]})
```

Request/response effects complete when their response event drains. For a long-lived source the default may proceed after its first emitted event, but the programmer must override this when “first event” is not the domain-ready condition — for example when the first WebSocket message is a heartbeat.

The hook stays data-first: a registered predicate event id or a vector of event predicates. Story must not invent Promise-returning loader callbacks, a polling DSL, or its own wall-clock scheduler. A team that needs a deterministic deadline expresses the timeout as application events.

### Options Considered

**Drain-only completion.** Elegant for finite effects, impossible for sockets, intervals, and subscriptions.

**Require explicit completion for every loader.** Precise, but adds ceremony to the overwhelmingly common request/response case.

**Default heuristic plus explicit completion predicate.** Low friction for simple cases and precise when semantics demand it.

**A general task/future abstraction.** Powerful, but adds a parallel async model and function-valued authoring slots.

### Consequences

- `:loaders-complete-when` is part of content hash and snapshot identity.
- Loader exceptions and incomplete predicates become structured run evidence.
- The docs must call the first-event default a heuristic, not a guarantee.
- Long-lived resource ownership remains with the application: use teardown events or a state machine's exit action.

## 5. Workspaces Have Ephemeral and Durable Persistence

### Decision

Support both intents without conflating them:

1. Interactive rearrangements auto-save locally, keyed by workspace and breakpoint.
2. “Save layout as…” creates a durable, explicit EDN/transit artefact for review, source control, or sharing.

```clojure
(story/reg-workspace :Workspace.checkout/review
  {:layout   :variants-grid
   :variants [:story.checkout/happy
              :story.checkout/card-declined
              :story.checkout/network-down]
   :columns  3})
```

The local form answers “where did I leave my desk?” The durable form answers “what layout should the team use?” Export must be visible and copyable; Story must not silently rewrite source files. A live registration may preview the saved id immediately, while committing the emitted form remains the programmer or agent's explicit act.

### Options Considered

**Local storage only.** Delightful for solo iteration, but not reviewable, portable, or reproducible.

**Registered artefacts only.** Durable, but turns every drag into an authoring ceremony.

**Local auto-save plus explicit export/save-as.** Matches the two real user intents with little API.

### Consequences

- Local persistence is best effort and may disappear without affecting canonical registrations.
- Breakpoint is part of the local key.
- The durable encoding must be deterministic, versioned enough to reject incompatible data clearly, and free of runtime-only values.
- Import/export uses the same workspace schema as `reg-workspace`, not a second layout model.

## 6. Story Is Compile-Time Dev-Only

### Decision

All nine registration macros and every route into Story chrome pass through the single `re-frame.story.config/enabled?` compile-time gate. Production builds set the closure define to false; dead-code elimination removes registrations, bodies, chrome, and their transitive reachability.

```clojure
(story/reg-variant :story.account/locked
  {:setup  [[:account/seed-locked]]
   :script {:script [[:assert-db [:account/status] :locked]]}})
```

expands in the guarded shape:

```clojure
(when re-frame.story.config/enabled?
  (story/reg-variant* ...))
```

There must be one gate implementation (`emit-reg`), not nine subtly different macros. A production-DCE sentinel should prove the compiled output contains no known registration ids or Story mount surface.

### Options Considered

**Runtime-only enable/disable.** Hides the UI but ships story data and code.

**Tag some registrations as production-safe.** Sounds flexible, but weakens a simple guarantee and creates a policy surface with no demonstrated production consumer.

**Compile-time elimination of all Story registrations.** Strong promise, small mechanism, easy to test.

### Consequences

- Cross-library `:extends` needs to work only in Story-enabled builds.
- Consumers must set the production closure define correctly; published build examples should make that the default.
- Functions used by both the application and a story remain because the application reaches them; Story-only reachability disappears.
- If a future production catalogue is wanted, it should be a separately named product mode, not an exception punched through this invariant.

## 7. Embed Xray; Never Rebuild 10x

### Decision

Story owns narrative context and Xray owns diagnostics. Story mounts Xray's individual panels into its inspector:

```clojure
{:epoch    xray-panels/mount-epoch-panel!
 :app-db   xray-panels/mount-app-db-diff!
 :views    xray-panels/mount-reactive-panel!
 :trace    xray-panels/mount-trace!
 :machines xray-panels/mount-machine-inspector!
 :routing  xray-panels/mount-routing!}
```

Story supplies the selected variant, frame, script span, epoch/cascade focus, and “open in Xray” actions. Xray supplies app-db diffs, time travel, subscription invalidation, trace details, routing, machine inspection, redaction markers, and its panel-local state.

If Xray is absent, Story shows a clear unavailable state and continues to run variants. Story must not take a hard dependency merely to avoid an empty inspector. `reg-story-panel` remains useful for third-party Story panels, but it is not the Xray integration mechanism; Xray's explicit per-panel mount API is.

### Options Considered

**Copy the useful 10x panels into Story.** Initially seamless, then two diff engines, two trace vocabularies, two redaction policies, and two maintenance burdens.

**Only link out to a separate Xray window.** Clean ownership, but excessive context switching for ordinary failure diagnosis.

**Embed Xray panels and offer pop-out/focus.** One diagnostic implementation with a coherent Story workflow.

### Consequences

- Xray needs a small, stable embedding and focus contract.
- Story owns the context bridge and lifecycle cleanup, not panel internals.
- Xray upgrades can evolve presentation while preserving the mount contract.
- Story feature work must resist adding “just one small duplicate inspector”; that is how the boundary erodes.

## 8. Additional Architecture Choice: Modes Are Registered Tuples

### Decision

Ship `reg-mode` as a first-class, EDN-only primitive:

```clojure
(story/reg-mode :Mode.app/dark-mobile
  {:axis :theme-and-device
   :args {:theme :dark
          :viewport :mobile
          :locale :en-AU}})
```

Effective args merge in one documented order, and each `(variant × mode × substrate)` has an independent snapshot identity. An agent can enumerate modes and run the matrix without generating duplicate variants.

### Why This Is Architectural

Modes could have been toolbar-only UI state, tags, decorators, or combinatorial variants. Making them registered tuples instead establishes a shared identity axis for UI, snapshots, tests, share URLs, and MCP. That cross-surface role should be locked now.

### Consequences

- The registration is tiny: doc, optional axis, and args.
- Modes do not become an open-ended environment/configuration framework.
- Axis grouping affects selection UX, not merge semantics.
- Workspace-level mode orchestration can wait until demonstrated; the primitive does not require it.

## 9. Additional Architecture Choice: Machine Charts Have One Owner

### Decision

Story may show a compact current-state cue and link narrative events to machine evidence. Full statecharts, transition arcs, guards, action traces, and interactive inspection remain owned by Xray/machines tooling and arrive through `mount-machine-inspector!`.

Story must not add a Story-specific machine registry, chart engine, or machine-viz adapter layer.

### Options Considered

**Build machine visualisation into Story.** Attractive in a component scenario, but duplicates a specialised tool and makes Story depend on machine semantics.

**A generic Story panel adapter for machine-viz.** Better than duplication, but still adds an unnecessary abstraction now that Xray exposes the exact panel Story needs.

**Use the Xray machine panel directly.** Smallest surface and one owner for the evidence model.

### Consequences

- A machine-centric variant remains ordinary Story data.
- `:rf.assert/state-is` and trace records provide the bridge.
- A standalone machines-viz product can still exist; Story does not mediate it.

## Sequencing

The architecture should land by dependency, not by visual ambition:

1. **Lock the artefact model:** registered stories, variants, workspaces, modes, substrates, and snapshot identity.
2. **Lock the run contract:** fresh frame, four lifecycle phases, loader completion, unified records, one verdict.
3. **Prove production elision:** single macro gate plus compiled-output sentinel.
4. **Build the human tool on those contracts:** matrices, inline failures, local workspace state, durable export.
5. **Embed Xray through its narrow mount/focus API.**
6. **Expose the same operations through Story-MCP and the Tool Pair live route.**
7. **Add high-value, low-architecture-cost refinements:** `:variants-grid` and layout-debug controls.
8. **Demand-gate later polish:** performance ribbon and design-token panel. Do not revive QR sharing, remote catalogue federation, a second app-db diff, or a Story-owned statechart engine without new evidence.

This ordering makes the UI and agent tools clients of a proven kernel. It also keeps pre-alpha freedom where it is useful: result schemas, loader semantics, and identity can become elegant before external compatibility makes them expensive to change.

## Comparison Check

Current official Storybook concepts validate the value of args, globals, play functions, and composed catalogues, but they do not require copying Storybook's JavaScript authoring or exception model:

- [Args](https://storybook.js.org/docs/writing-stories/args) support a serialisable parameter surface.
- [Play functions](https://storybook.js.org/docs/writing-stories/play-function) support scenario execution attached to a story.
- [Toolbars and globals](https://storybook.js.org/docs/essentials/toolbars-and-globals) demonstrate the value of named environment axes.
- [Storybook composition](https://storybook.js.org/docs/sharing/storybook-composition) demonstrates catalogue federation, but remote federation is not needed for the local-first v1 architecture.

The useful lesson is parity of capability, not parity of mechanism. re-frame2 can do better where its event model and frame isolation permit it: EDN registrations, deterministic fresh frames, accumulated assertion evidence, explicit snapshot axes, and one shared runtime for programmer and agent.

## Rejected Expansion

The following are intentionally outside this architecture lock:

- a new statechart runtime for Story;
- first-party visual-regression hosting or baseline storage;
- remote Story federation;
- a second MCP/browser attachment transport;
- Story-owned app-db diffing, trace, time travel, or subscription inspection;
- function-valued CSF-style factories;
- a general async task framework for loaders;
- production Story registrations hidden behind runtime flags; and
- automatic source-file rewriting when a layout or captured state is saved.

These are not forbidden forever. They simply lack evidence strong enough to justify weakening the current small model.

## Codex Recommendation

Lock all nine decisions as one coherent architecture.

The seven Mike calls fit together: a data-first Story kernel runs isolated variant frames; failures become rich evidence rather than control flow; modes and substrates are explicit identity axes; workspaces serve both local flow and deliberate sharing; compile-time elimination protects production; Xray owns diagnostics; and a separate MCP artefact gives AI the same powers through the same contracts.

The most important refinement is to state the ownership boundaries precisely:

- Story-MCP owns protocol transport; Story owns artefacts and execution.
- The Tool Pair is the only live-browser agent door.
- Story owns scenario context; Xray owns diagnostic computation and panels.
- Local storage owns ephemeral layout preference; exported registered EDN owns durable team intent.
- The unified run result owns truth; UI, tests, and agents only project it.

That is sufficiently powerful for a masterpiece and sufficiently small to avoid gold plating. Build the kernel and seams to a very high standard, then demand evidence before adding new abstractions.
