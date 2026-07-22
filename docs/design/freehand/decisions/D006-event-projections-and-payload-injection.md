# D006 — How should event projections and payload injection work?

Status: **Open**

Horizon: **Immediate**

## Decision

Define how declarative event vectors obtain scalar values from a live native or
foreign callback, and decide whether payload-aware expansion belongs to Freehand's
event adapter or to re-frame's general dispatch API.

## Why this is a real problem

A reusable control should forward intent as data without constructing an opaque
closure merely to read one browser property:

```clojure
(v/defview email-field [{:keys [value on-change]}]
  [:input {:value value
           :on-input (conj on-change ::v/value)}])
```

If `on-change` is `[:account/email-edited]`, typing `"mike@example.com"` should
dispatch:

```clojure
[:account/email-edited "mike@example.com"]
```

The semantic event vector is inspectable before mounting, but the value exists only
when the host callback fires. Production, JVM structural tests, browser tests, and
the compiled emitter need one exact rule for connecting those two facts.

The absorbed compiler currently uses donor-qualified projection keywords. Freehand
cannot retain `:rf.ui/*` names in its permanent grammar after `re-frame.ui` is
deleted.

## Settled constraints

- Event intent is an ordinary vector and a site produces exactly one event or
  `nil`.
- The closed scalar projection set is value, checked state, and key.
- Projection is shallow and by value. A reserved keyword nested in another value is
  ordinary application data.
- Host objects must not enter intent vectors. Foreign dates, selections, and other
  objects are converted to plain values by `v/event` or a qualified adapter.
- Interpreted and compiled modes must materialize the same vector.
- Controlled-input scheduling needs to know the materialized event synchronously.
- Tests must exercise production semantics rather than maintain a test-only splice
  convention.

## Options

### A. Materialize at the Freehand event site, then use ordinary dispatch

The native or qualified host adapter builds a small payload map from the live
callback and applies one pure event materializer:

```clojure
{:re-frame.freehand/value   (.. e -target -value)
 :re-frame.freehand/checked (.. e -target -checked)
 :re-frame.freehand/key     (.-key e)}
```

The materialized, projection-free vector is then sent through normal re-frame
dispatch. Structural tests invoke the same materializer through Freehand's test
surface.

Consequences:

- Projection semantics stay at the layer that understands UI callback payloads.
- Ordinary application dispatch remains ordinary; a projection keyword in a domain
  event is not secretly interpreted.
- Production and tests can still share exactly one pure implementation.
- A small materialization operation must be exposed to the test tooling, even if it
  is not promoted as an everyday application API.

### B. Add a payload-map arity to general dispatch

Both the event adapter and tests call something like:

```clojure
(rf/dispatch-sync intent {::v/value "mike@example.com"})
```

Consequences:

- Production and test calls visibly use the same entry point.
- Any dispatcher can deliberately supply a payload.
- UI projection semantics become part of the general event runtime and must be
  explained for every dispatch source.
- It enlarges re-frame's core contract for a concern owned by one substrate.
- Accidental reserved keywords in non-UI events become harder to reason about.

### C. Generate extraction closures and remove projection values

Every site uses `v/event` or compiler-generated code to read the callback object.

Consequences:

- There is no reserved data vocabulary.
- Simple controls lose a structural, JVM-testable intent.
- Interpreted and compiled authoring diverges unless the closure DSL becomes another
  shared language.
- Forwarded intent prefixes become needlessly awkward.

### D. Permit nested paths or a general projection expression language

Examples might include `[:target :files 0 :name]` or arbitrary transforms.

Consequences:

- More callbacks can be represented as data.
- The grammar becomes a miniature expression language with validation, missing-value
  semantics, host coupling, and a much larger compatibility burden.
- `v/event` already handles this uncommon residue more honestly.

## Recommendation

Choose **A: materialize projections in Freehand's event adapter and dispatch the
result as an ordinary event vector**.

Use the permanent Freehand-qualified spellings `::v/value`, `::v/checked`, and
`::v/key`. Replace donor `:rf.ui/*` spellings mechanically during migration; do not
make them aliases in the final grammar. Register the closed trio in the reserved
keyword catalogue; adding a fourth projection requires a new grammar decision.

The recommended materializer has deliberately small semantics:

1. Validate that the event id at position zero is not a projection marker.
2. Replace matching markers only in the vector's top-level argument positions.
3. Replace every occurrence, not merely the first.
4. Leave nested markers untouched as ordinary data.
5. If a requested payload is unavailable, report a typed error and do not dispatch
   a malformed event.
6. Return a plain vector before re-frame sees it.

For native events the adapter supplies the three normalized scalar values that are
meaningful for that event. A qualified foreign leaf supplies its own plain payload
values or uses `v/event` for conversion. No DOM event, React synthetic event, or
third-party object enters the event vector.

The production materializer should be directly reusable by Freehand's test
surface. That satisfies Fable's “one production and test mechanism” requirement
without teaching every re-frame dispatch about UI payloads.

Expose that seam through the test namespace as a pure materialize operation and a
materialize-then-dispatch helper. General `rf/dispatch` gains no payload-map arity.

## Consequences to verify

- Forwarded vectors such as `(conj on-change ::v/value)` expand identically in both
  execution modes.
- Options maps and `v/event` results pass through the same final materializer; there
  is not one projection path per event form.
- Projection happens at firing time from the live callback payload, never from a
  render-captured value.
- Structural tests can supply a literal payload and assert the final dispatched
  vector without a browser.
- Diagnostics identify the view, node occurrence, event attribute, missing marker,
  and source location when available.

## Dependencies and what this unlocks

This decision supplies part of the versioned compiled-tier grammar and therefore
blocks the donor spec rename, both emitters, event diagnostics, and the controlled
input door. It unlocks data-oriented reusable controls and production-equivalent
JVM tests without a callback DSL.

## Design sources

- [Codex design, §4 “Event law”](../codex-design.md#event-law) specifies the closed
  `::v/value`/`::v/checked`/`::v/key` trio and shallow dispatch-time normalization.
- [Fable design, §2.3 “The event grammar”](../fable-design.md#23-the-event-grammar)
  makes the payload map explicit and argues for a single production/test mechanism.
