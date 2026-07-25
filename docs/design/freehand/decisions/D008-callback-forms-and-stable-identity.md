# D008 — Which callback forms exist, and what owns stable identity?

Status: **Ruled**
Ruling: **Use the closed callback roster with per-site committed slots; do not
add `v/dispatcher` or a dependency-annotated `v/event`.**

Horizon: **Immediate**

## Decision

The ruling fixes the finite callback roster and defines when a JavaScript callback keeps its
identity, which render/commit generation it reads, and when it becomes inert.

Without this decision, integration with React libraries may appear correct while
quietly causing stale closures, memo churn, duplicate subscriptions, or a callback
that fires into a replacement frame after its owner has disappeared.

## Why this is a real problem

Foreign APIs use functions for several different protocols:

```clojure
;; A user action whose argument must become plain event data.
[date-picker {:on-change
              (v/event [date]
                [:booking/departure-changed (iso-date date)])}]

;; A foreign owner calls this while rendering each row.
[virtual-list {:render-item
               (v/render-fn [{:keys [id]}]
                 [result-row {:id id}])}]

;; An imperative protocol, not application intent.
[canvas-host {:measure (v/handler [node] (measure! node))}]
```

Those functions have different phases and ownership. Treating them all as a bare
function prevents the runtime and compiler from knowing whether dispatch, render,
hooks, refs, or stable identity are legal.

## Settled constraints

- Event vectors are the paved path for application intent.
- A callback that produces intent yields one event vector or `nil`.
- Decisions that depend on changing application state belong in the receiving
  re-frame event handler, not a render-captured callback guard.
- Hooks, effects, refs, portals, context, and React-specific callback protocols
  belong in a UIx/Helix wrapper or qualified host boundary.
- Equal callback values at two different runtime sites must never share lifecycle,
  `:once` state, frame ownership, or diagnostic identity.
- Candidate render evidence becomes live atomically at commit; an abandoned render
  must not update callbacks.
- Interpreted and compiled modes expose the same forms and lifetime laws.

## Options

### A. Accept bare functions and infer their role from the prop name

Consequences:

- Familiar React code usually works with little ceremony.
- Unknown foreign components make reliable inference impossible.
- Phase, identity, dispatch, and cleanup contracts remain implicit.
- Compiler and JVM support become guesses, and diagnostics arrive after integration
  failures.

### B. Use a closed, role-specific roster with site-owned adapters

The roster is event vectors plus `v/event`, `v/handler`, `v/render-fn`, `v/raw-fn`,
and an explicit React wrapper for React-owned protocols.

Consequences:

- Each form has one teachable phase and test contract.
- The runtime can provide committed callbacks without stale closures.
- Foreign integrations pay a visible annotation at the boundary.
- The implementation must maintain candidate-versus-committed callback tables and
  precise cleanup.

### C. Require wrappers for every callback-bearing foreign component

Consequences:

- React protocol ownership is always explicit.
- Simple value-in/intent-out controls acquire excessive wrapper boilerplate.
- The common event grammar cannot directly cover popular leaf controls.

### D. Add a separate `v/dispatcher` prefix-to-callback form

For example, `(v/dispatcher [:booking/set])` would append callback arguments and
dispatch.

Consequences:

- The common foreign callback becomes very short.
- Appending raw callback arguments invites mutable dates, events, and host objects
  into event vectors.
- `v/event` already expresses conversion to intentional plain data.
- It adds another form whose difference from a vector or `v/event` must be taught.

## Recommendation

Choose **B: the closed role-specific roster with site-owned adapters**, and do not
add `v/dispatcher` initially.

The forms should have these contracts:

| Form | Contract | Identity |
|---|---|---|
| event vector/options | declarative application intent | stable adapter owned by the committed event site |
| `v/event` | synchronous callback-argument conversion to one event vector or `nil`; no `sub`, hooks, refs, or effects | stable committed adapter per site; body/payload changes publish atomically |
| `v/handler` | explicit imperative foreign work; not a disguised render or state store | stable committed adapter per site; cleanup at disconnect |
| `v/render-fn` | pure function invoked during the foreign owner's render; may return Freehand content but may not `sub`, dispatch, use hooks, or touch refs | no cross-mode identity guarantee; an implementation may reuse it while descriptor and captures are `rf=`-equal |
| `v/raw-fn` | expert pass-through when authored function identity is itself protocol data | exactly the supplied identity; Freehand promises no stabilization |
| UIx/Helix wrapper | React owns hooks, effects, context, refs, Suspense, cloning, or compound protocols | wrapper's React contract |

For event vectors, `v/event`, and `v/handler`, the runtime should mint one proxy for
`(committed normalized-node identity, callback-prop)`. The proxy reads the exact
committed body and frame at invocation. A later selected commit may update that
body without changing the proxy identity; a key change, node replacement,
disconnect, or incompatible HMR generation retires it. A retired proxy is inert
and emits development evidence rather than dispatching into a new owner.

`v/render-fn` is intentionally different. It can be invoked during an uncommitted
candidate render, so a globally mutable “latest body” proxy would be unsafe under
concurrent rendering. Its identity may therefore change on any render; compiled
lowering is free to reuse it when proven captures are `rf=`-equal. APIs that treat
callback identity as a separate protocol use `v/raw-fn`, D014's component bridge,
or a wrapper instead of asking Freehand to guess.

Bare functions remain legal at native `:on-*` sites and as opaque values passed
between internal views; the site's stable outer adapter owns native callback
lifetime. They are rejected in declared foreign callback positions, where phase
and identity are otherwise unknown, with a diagnostic that names the roster.
D022's host declaration identifies those positions through its mandatory
`:callbacks` map; a protocol-heavy foreign surface remains React-owned behind
that same declared host boundary.

Per-site ownership is the public law, not the private key representation. The
interpreted runtime may key a site by committed normalized-node identity while the
compiled tier uses an owner plus lexical site id. Both must keep equal values at
different sites independent and make retired proxies inert with development
evidence. Do not add dependency arrays: committed proxy publication provides
fresh meaning without foreign identity churn.

## Consequences to verify

- Two equal event vectors at two nodes produce distinct adapters and distinct
  diagnostic sites.
- A callback from an abandoned render never becomes callable as the committed
  implementation.
- Frame retargeting updates the committed target even when props are equal.
- Event and handler proxies do not retain disconnected views, frames, or host
  objects.
- `v/render-fn` works under StrictMode and concurrent abandoned renders without a
  mutable-current-body race.
- Library pilots measure callback identity churn; a new shorthand is justified only
  by repeated evidence, not imagined convenience.

## Dependencies and what this unlocks

This depends on mounted occurrence identity, atomic candidate publication, the
event projection decision, and qualified host schemas. It unlocks predictable
integration with date pickers, virtualized lists, Vega-style render hooks, SpreadJS
adapters, memoized React children, and deterministic callback diagnostics.

## Design sources

- [Codex design, §4 “Event law”](../codex-design.md#event-law) gives the role-specific
  callback table and per-event-site ownership law.
- [Codex design, §5 “Host ownership routes”](../codex-design.md#host-ownership-routes) places
  React-owned callback protocols in wrappers.
- [Fable design, §2.1 “Dream code”](../fable-design.md#21-dream-code) introduces the
  dispatcher and four-form escape roster.
- [Fable design, §2.3 “The event grammar”](../fable-design.md#23-the-event-grammar)
  requires callbacks to be per site and committed-frame aware.
