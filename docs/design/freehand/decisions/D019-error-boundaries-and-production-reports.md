# D019 — Error boundaries and production reports

Status: **Open**

Horizon: **Upcoming**

## Decision

Should Freehand provide a declarative error boundary shared by interpreted and
compiled views? If it does, what failures does it catch, how is recovery
controlled, what data may reach re-frame, and how does a production error sink
receive useful host details without placing exceptions or app-db snapshots in the
view data plane?

## The problem

Render failures are inevitable: a nil assumption, malformed Hiccup, a foreign
React component throwing, a conversion error, or stale data violating a component
contract. Freehand's atomic selected-commit law ensures an abandoned render does
not publish half of its dependencies, callbacks, or evidence. It does not decide
what the user sees next or how the failure reaches production telemetry.

A desired authoring shape is small:

```clojure
[v/error-boundary
 {:reset-key route-revision
  :fallback  [broken-page {}]
  :on-error  [:telemetry/ui-render-failed]}
 [workspace-page {:workspace-id workspace-id}]]
```

The boundary should show `broken-page`, publish no candidate state from the failed
render, and issue at most one data intent after the fallback commits. Changing
`:reset-key` retries the child. There should be no boundary ref or imperative
`reset!` handle.

Production reporting creates harder questions:

- A JavaScript `Error`, React component stack, DOM node, or host instance is not
  application data.
- Error messages and props can contain secrets or personal information.
- Capturing all of app-db or the last N event payloads by default is a privacy,
  volume, and replay-honesty problem.
- Development history may not exist in a production build.
- Sending only "render failed" is safe but often insufficient to find the view or
  release that failed.
- StrictMode, retries, and HMR must not report the same captured failure repeatedly.

The decision therefore needs two deliberately different channels: safe,
serializable failure data for the application, and opaque host error detail on the
existing frame-owned error-egress path.

## Settled constraints

- A candidate render that throws owns nothing and publishes no dependencies,
  event sites, tree evidence, or host work.
- Errors carry stable diagnostic ids, view ids, source/phase where available, and
  concise recovery guidance. Analyzer internals are not the public explanation.
- Interpreted and compiled declarations use the same boundary and error schema.
- Lifecycle and render errors are not domain mount/unmount events. An app receives
  an error intent only when the author explicitly supplies `:on-error`.
- An error site produces at most one event vector; there is no secondary event DSL
  or vector of intents.
- Exceptions, React elements, nodes, callbacks, refs, and host instances do not
  enter app-db, structural values, event vectors, or serializable traces.
- Debug evidence is richer in development and may be absent or capped in
  production. Completeness must be explicit.
- Frames already own error/observability egress. Freehand should promote a bounded
  record onto that path rather than add a second reporter slot to Root Descriptors.
- Error boundaries do not catch every kind of failure. Event-handler, asynchronous
  task, resource, and re-frame handler failures remain with their existing owners.
- `re-frame.ui` is a donor. Freehand needs one error contract, not donor-specific
  boundary behavior or diagnostics retained as a compatibility surface.

## Failure classes

A decision is easier to reason about when the catch boundary is explicit.

| Failure | Boundary fallback? | Reporting owner |
|---|---:|---|
| Freehand child render throws | yes | Freehand boundary + frame error egress |
| Hiccup normalization or common prop/event validation throws | yes | Freehand boundary + frame error egress |
| descendant React component throws during render/lifecycle where React boundaries apply | yes in browser | React host through the same Freehand boundary |
| JVM structural child render throws | yes, same semantic boundary | JVM host/test evidence |
| fallback itself throws | no at that boundary; propagates outward | nearest ancestor, then frame error egress |
| re-frame event/sub/resource/mutation handler throws | no | existing re-frame error path |
| DOM event callback or timer/promise throws asynchronously | no | browser/global or owning wrapper path |
| behavior `connect`/`update`/command throws after commit | not a render fallback by default | D013 behavior diagnostics + frame error egress |
| SSR transport/stream failure outside view evaluation | no | server/root host |

Trying to make one component boundary catch all of these would conceal causal
ownership and disagree with React's actual guarantees.

## Options

### Option A — no Freehand boundary

Let errors escape to the root. Applications needing containment write React
boundaries or use server/test try/catch independently.

Consequences:

- Freehand adds no recovery API.
- React applications can use existing ecosystem tools.
- Structural JVM output, compiled/interpreted identity, frame evidence, and React
  containment acquire different contracts.
- An application must expose React machinery merely to put a fallback around a
  Freehand subtree.
- Library components cannot offer renderer-consistent containment.

### Option B — a minimal boundary with fallback only

Provide `[v/error-boundary {:fallback ... :reset-key ...} child]`. It catches
render-class failures and renders the fallback, but emits no event and defines no
production error-egress integration.

Consequences:

- Containment and recovery remain compact and testable.
- Applications can use global browser/server telemetry separately.
- Global reports lose precise Freehand boundary/view/frame context.
- Every app invents a bridge from a caught failure to reporting and may duplicate
  reports during retries.

### Option C — boundary plus serializable `:on-error`

Append a safe failure envelope to an optional event prefix after the fallback
commits:

```clojure
[:telemetry/ui-render-failed
 {:diagnostic-id :re-frame.freehand/render-failed
  :view-id       :app.workspace/workspace-page
  :phase         :render
  :source        {:file "src/app/workspace.cljc" :line 41}
  :frame-id      :main
  :fingerprint   "..."
  :evidence      {:scope {:committed-generation 812}
                  :basis :observation
                  :complete? false
                  :loss {:reason :production-policy}}}]
```

Consequences:

- Application policy remains in a normal re-frame event and can add explicitly
  selected domain context.
- The envelope is testable and traceable as data.
- The raw exception and React component stack either have to be reduced into the
  envelope or are lost.
- Careless inclusion of message, props, event payloads, or state can leak data.

### Option D — boundary, safe intent, and existing frame error egress

Combine Option C with one promoted record on re-frame's existing always-on error
axis and frame-owned observability sink. The private egress record contains the
opaque exception and host stack plus the same safe public summary. An application
adapter may send it to Sentry or another service. It cannot return host objects
into Freehand or dispatch through an abandoned render.

Consequences:

- The application gets inspectable data; telemetry gets the actual host detail it
  needs.
- Privacy and transport policy remain explicit in the existing frame observer/sink
  instead of every view.
- Freehand must define de-duplication, sink-failure isolation, and what source
  metadata survives production optimisation.
- No new Root Descriptor reporter slot or retention surface is required.
- There is a risk of growing an observability product inside the view substrate;
  the promoted-record contract must stop at delivery of one bounded record.

### Option E — automatic snapshot and event-history capture

Every report includes a redacted app-db snapshot and the last N events, as
suggested by the richer Fable dossier.

Consequences:

- Reproduction can be excellent when redaction and replay are complete.
- "Redacted" is application-specific; the substrate cannot safely infer it.
- Event payloads and state frequently contain tokens, personal data, documents,
  or large foreign values.
- Production history has storage and performance cost and may still be incomplete.
- It couples basic containment to instrumentation/history policy and invites a
  false promise that every report is replayable.

This may be a valuable opt-in application telemetry facility, but should not be
the Freehand boundary default.

## Recommendation

Choose **Option D**, reusing the frame error-egress architecture with a deliberately
small production contract, and reject automatic Option E capture.

### Boundary semantics

1. `v/error-boundary` is a declared core boundary with one child region.
2. `:fallback` is static Hiccup/a declared view, or a pure `v/render-fn` receiving
   the **safe failure summary**. The no-`sub`/no-effect rule applies to the
   render-fn; a declared fallback is an ordinary fresh mounted view and may
   subscribe normally.
3. `:reset-key` is a caller-owned value. When it changes by `rf=`, Freehand clears
   the captured failure and remounts/retries the child. A retry button dispatches a
   normal event that changes this value. No boundary ref API exists.
4. A failure keeps the prior committed child bundle untouched until the fallback
   is selected, then disconnects the failed subtree through ordinary teardown.
   Candidate evidence from the thrown render is never published as committed.
5. `:on-error`, when present, is one event prefix. Freehand appends the safe
   summary and dispatches exactly once after the fallback commit for that captured
   failure generation.
6. If the fallback throws, the error propagates to the next outer boundary. A
   boundary never tries to catch its own fallback indefinitely.
7. Development HMR may retry a failed boundary after the descriptor revision
   changes, but this is visible evidence and must not generate duplicate
   production reports for the old failure generation.

### Safe public summary

The event/structural summary should contain only bounded values whose production
policy is explicit:

- stable diagnostic id and failure fingerprint;
- failing declared view id and boundary view id;
- phase (`:render`, `:normalize`, `:foreign-render`, and similar finite values);
- source coordinates when retained in the build;
- root/frame public id, descriptor revision, and a bounded occurrence correlation
  token if safe and available—never a promise of cross-session replay identity;
- evidence scope, basis, completeness, and loss using D020's common vocabulary;
  and
- a report correlation id.

It should not contain raw props, app-db, event payloads, host nodes/instances, the
exception object, or arbitrary `ex-data`. A human message may be present in
development; production inclusion is sink policy because messages often embed
values.

### Private frame error egress

The browser/server host promotes at most one record per failure generation onto the
existing frame-owned error/observability path, containing:

- the safe summary;
- the opaque exception available only during the call;
- the host/React component stack, capped according to frame policy; and
- bounded Freehand evidence already retained for that occurrence.

Observer/sink code owns redaction, source-map processing, transport, retry, and
vendor integration. A sink failure is isolated, recorded through the host's final
fallback logger, and never replaces the user's fallback.

If an application wants a redacted state snapshot or recent event ids, its
explicit `:on-error` handler or error observer may obtain them through existing
application instrumentation and an allow-list it owns. Freehand neither captures
nor promises replayable history by default. Event **ids without payloads** may be
added later as an opt-in bounded context if production measurement proves useful.

This accepts Fable's central point—that an occurrence plus carefully chosen
application context is far more useful than a bare exception—while retaining
Codex's smaller evidence contract and keeping replay history outside the substrate.

## Consequences

- Applications get consistent containment and reset behavior in browser, JVM
  structural tests, interpreted views, and compiled views.
- A telemetry integration is configured once per root, not threaded through every
  component.
- There are intentionally two representations: safe data for re-frame and opaque
  detail for host reporting. Collapsing them would either leak objects into data or
  starve telemetry.
- A structural test can assert fallback and the `:on-error` intent. It does not
  simulate Sentry; observer/sink adapters receive focused unit/mounted tests.
- React event-handler and asynchronous errors remain outside render boundaries and
  must be tested through their actual owners.
- Production source coordinates and component stacks have bundle/privacy cost; a
  build profile may cap or omit them but must label completeness.
- Report correlation and deduplication add a small runtime responsibility even
  when development evidence is otherwise eliminated.

## Evidence required to close

The implementation/pilots must demonstrate:

- a thrown interpreted child and a thrown compiled child producing the same safe
  summary and fallback structure;
- no dependency/event/tree publication from the failed candidate;
- a foreign React render error reaching the same Freehand boundary;
- `:on-error` firing once only after fallback commit under StrictMode, HMR, and
  repeated parent renders;
- retry on `:reset-key`, followed by correct fresh subscriptions and callbacks;
- fallback failure propagating to an ancestor without a loop;
- JVM structural behavior and honest SSR behavior; an SSR response render
  propagates to the server error projector rather than simulating client recovery;
- production builds with full, capped, and unavailable evidence markers;
- observer/sink failure isolation and no exception/host object entering event data;
- an allow-list-based application snapshot example proving that capture is opt-in;
  and
- teardown of a failed subtree containing presence and a connected behavior.

## Dependencies and what this unlocks

Depends on:

- atomic selected render-bundle commit and total disconnect;
- stable descriptors, view ids, source coordinates, and occurrence/generation
  evidence;
- one versioned diagnostic/evidence schema across both modes;
- existing frame error egress, data classification, and SSR projection policy;
- common `v/render-fn` purity rules; and
- D013's error handling for post-commit behavior failures.

Unlocks:

- production-safe containment for application and component-library pilots;
- one browser/server telemetry adapter boundary;
- parity tests for thrown renders and recovery;
- deletion of donor-specific error/diagnostic façades after migration; and
- clear guidance for debugging failures that cannot be reproduced from production
  history.

## Source basis

- [Codex design — Identity, HMR, and errors](../codex-design.md#identity-hmr-and-errors)
  requires stable ids/source/phase, atomic failure, and concise recovery.
- [Codex design — Passive render and atomic selection](../codex-design.md#passive-render-and-atomic-selection)
  defines why a thrown candidate cannot publish partial ownership or evidence.
- [Codex design — Debugging](../codex-design.md#debugging) defines evidence
  completeness and production elimination/capping.
- [Fable design §2.7](../fable-design.md#27-errors-and-diagnostics) proposes
  `v/error-boundary`, post-commit `:on-error`, reset behavior, and production
  context.
- [Fable design Appendix A.7](../fable-design.md#appendix-a--semantic-traces)
  traces abandoned capture, fallback commit, and teardown.
- [Fable design §7](../fable-design.md#7-risks-wounds-obligations) supplies the
  privacy, identity, HMR, and evidence risks this contract must fence.
- [`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md),
  [`spec/015-Data-Classification.md`](../../../../spec/015-Data-Classification.md),
  and [`spec/011-SSR.md`](../../../../spec/011-SSR.md) provide the existing error
  egress, privacy, and server-projection mechanisms.
