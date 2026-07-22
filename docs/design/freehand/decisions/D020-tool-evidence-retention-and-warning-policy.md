# D020 — Tool evidence, retention, and warning policy

Status: **Open**

Horizon: **Upcoming** — decide before the evidence API and Xray integration become
public

Sources: [Codex product spine](../codex-design.md), “Debugging” and lifecycle
sections; [Fable dossier](../fable-design.md), §2.5, §3.5, §7.1, and Appendix A.

## Decision

What stable facts does Freehand expose to tools, how are view occurrences
identified, how long is evidence retained, and which authoring problems are errors
versus warnings?

Both designs want one read-only evidence surface shared by interpreted and compiled
execution. They also agree that mount, unmount, reconnect, presence, and host
behavior lifecycle are tool facts—not application events. They diverge on identity:
Fable joins everything through a derived anchor that can also address
`[:rf/inst ...]`; the product spine keeps runtime occurrence identity separate from
caller-supplied application/controller identity.

Without a ruling, implementation details will leak into tools: interpreted cells
will report query keys, compiled cells will report lexical site ids, host adapters
will invent their own connection ids, and warning volume will be set piecemeal.

## Constraints already settled

- A selected commit publishes dependencies, event sites, frame, and tree evidence
  atomically; abandoned renders publish none.
- Every evidence projection states its scope, basis, completeness relative to that
  scope, and loss. “Unknown” must not look like “none.”
- Private handles, DOM nodes, React elements, callbacks, cleanup functions, and
  third-party instances never enter the evidence value.
- Lifecycle facts never dispatch domain events.
- Production must not pay an unbounded debugging or retention cost.
- Application-state addressing is governed separately by D003 and D004.

## Options

### A. Expose raw runtime tables

Consequences:

- Cheapest initial implementation.
- Makes tools depend on whether a view is interpreted or compiled and freezes
  private handle/cardinality choices as accidental ABI.
- Produces poor, unstable input for people, tests, editors, and AI.

### B. One versioned, host-neutral evidence schema keyed by occurrence

A representative record—not final field spelling—would have this shape:

```clojure
{:schema      :re-frame.freehand.evidence/v1
 :view-id     :app.todo/todo-row
 :source      {:file "src/app/todo.cljc" :line 42}
 :lowering    :interpreted
 :occurrence  {:parent <occurrence-id> :key 17}
 :generation  9
 :frame       <public-frame-id>
 :cause       {:kind :subscription :query [:todo/by-id 17]}
 :reads       {:scope {:committed-generation 9}
               :basis :observation
               :complete? true
               :loss nil
               :occurrences [...]
               :targets [...]}
 :event-sites {:scope {:committed-generation 9}
               :basis :observation
               :complete? true
               :loss nil
               :sites [...]}
 :lifecycle   {:phase :connected}
 :retention-loss nil}
```

Compiled possible-site projections use `:scope :possible-sites`,
`:basis :static-proof`, and `:complete? true`. An interpreted committed-render
projection uses a specific generation as its scope and `:basis :observation`; it
can still be complete for that generation. A union from a named test corpus remains
incomplete as a claim about all executions. Any cap sets `:complete? false` and
records explicit loss. A caller-supplied controller id may be included as public
props data, but it does not replace runtime occurrence identity.

Consequences:

- Gives both modes and every host boundary one vocabulary while preserving their
  honest differences.
- Supports `mounted-views`, `view-dependencies`, `view-event-sites`, and
  `explain-render` as projections rather than separate stores.
- Requires a small normalization layer and explicit schema versioning.

### C. Make a derived renderer anchor the universal identity

Consequences:

- Allows direct joins between view evidence and a generic `[:rf/inst anchor]`
  state tree.
- Couples tool identity to render position and makes tree refactors, unkeyed
  multiplicity, subtree tests, and SSR rooting part of application identity.
- Becomes unnecessary if D003 rejects generic substrate instance state.

### D. Retain the full evidence stream in production

Consequences:

- Maximizes post-failure history.
- Adds memory, privacy, serialization, and hot-path costs to every application.
- Encourages accidental capture of sensitive props or values and conflicts with the
  substrate's production-elision goal.

## Recommendation

Choose **B**.

- Key records by a runtime occurrence plus an internal generation. Keep any
  application/controller id separate and explicitly supplied.
- Use D012's scope/basis/completeness/loss vocabulary throughout the one schema.
  Never equate read occurrences, distinct resolved targets, or compiler sites.
- Record mount, update, reconnect, disconnect, HMR, presence, and host behavior
  phases in the development timeline. Do not route them through re-frame events.
- Reuse the existing per-frame retained-event ring and its one retention control;
  do not add a Freehand history store or root-level retention knob. Mounted
  occurrence records are live projections. When any emission cap is hit, report
  the loss in data rather than silently truncating.
- Compile detailed evidence out of production. Keep only deliberately enabled
  aggregate metrics and the minimal error envelope specified through D019.
- Emit recoverable authoring warnings once per stable source site and warning kind.
  A warning is on by default only for a detected contract misfire whose symptom
  would otherwise surface far from its cause; quality and predictive lints belong
  to opt-in `v/check` categories. Reserve hard errors for semantic corruption or
  ambiguity: malformed trees, illegal compiled forms, `sub` outside its permitted
  context, invalid event outcomes, and ownership violations. Keep categories
  configurable.

This ruling gives tooling and AI a dependable surface without making debugging
state part of the application model. It unlocks the conformance corpus, Xray views,
HMR diagnosis, host-leak assertions, and meaningful performance attribution.

The retention mechanism is the one specified by
[`spec/009-Instrumentation.md`](../../../../spec/009-Instrumentation.md); Freehand
adds evidence records and loss markers, not another observability product.
