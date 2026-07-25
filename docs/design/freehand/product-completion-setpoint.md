# Freehand product-completion setpoint

Status: **Accepted**

Ruling date: **2026-07-26**

This document records Mike's accepted completion of the original Freehand design:
DC-01 through DC-09 and execution rulings ER-01 through ER-08. It extends
[EP-0036](../../EP/EP-0036-the-freehand-view-substrate-programme.md); it does not
create a second programme or replace the canonical specifications.

Where an earlier working draft conflicts with this setpoint, this setpoint
controls until the owning specification lands. Once a vertical slice migrates its
surface, the canonical specification controls that surface.

## Working posture

Freehand is pre-alpha. Optimize for an elegant, powerful whole and excellent
ergonomics for programmers and the AIs working with them. Trust the programmer,
keep the paved path short, and avoid ceremony whose only purpose is to defend
against hypothetical misuse.

This is not permission to hide semantic uncertainty. Callback lifetime, state
ownership, SSR loss, compiler refusal, cleanup, and evidence completeness must be
honest. It is permission to leave reversible implementation details with the
programmer and to decline speculative frameworks.

## Problem statements

| ID | Problem |
|---|---|
| **P1** | Finished React elements are useful opaque children, but Freehand lacks a first-class inward React boundary that can own callback identity, retirement, structural evidence, SSR policy, and compiled parity. |
| **P2** | Promise-acquired imperative resources and host-created DOM panes are possible, but their causal ownership and reclamation recipes are neither paved nor proved. |
| **P3** | The form, controller, and controlled-input laws are stronger than the product programmers can import; current reference paths contain unsafe form semantics. |
| **P4** | The compiler and checker can overstate eligibility when a source-visible child expression becomes opaque before first render. |
| **P5** | Props-schema and controlled-input evidence can appear stronger or more complete than the facts available. |
| **P6** | Documentation, checker/build facts, mounted tests, examples, and Xray are not projections of one executable fixture authority. |
| **P7** | The ownership route through pure libraries, React components, behaviors, islands, and nested roots is scattered, so programmers learn the real costs after choosing a library. |
| **P8** | High-rate host input and frame-wide controlled flushing have an incompletely measured operating envelope, so proposed policy additions cannot yet be distinguished from gold plating. |

## Accepted design changes

### DC-01 — One declared React host ABI

Provide one public declaration, `v/defhost`, producing one stable qualified host
descriptor kind. It is the inward boundary for ordinary React value/callback
components and React-owned wrappers that may use hooks, context, refs, or compound
protocols internally.

The declaration owns shallow ordinary props, finite `:event` and `:handler`
callback positions, an explicit children policy, a required SSR policy, one
optional whole-ordinary-props adapter, and optional props-contract evidence.
Callback carriers materialize only at their declared positions. Finished raw
React elements remain legal opaque browser children with correspondingly weaker
identity, structure, SSR, and compiler claims.

[D022](decisions/D022-public-react-host-door.md) owns the detailed ruling.

### DC-02 — Deferred foreign-handle ownership recipe

Keep behavior `:connect` synchronous. It may start acquisition of a foreign handle
and return a private pending/ready/failed/closed owner immediately. Publish one
tested recipe covering latest desired config, late success and failure,
cooperative cancellation, close-before-resolve, exact-once finalization,
non-queued commands, and finalizer failure.

**re-frame event processing remains one complete synchronous pass.** A later
foreign completion may dispatch a new ordinary event; it never pauses or resumes
the original event. There is no awaited event, no suspended handler, and nothing
to resume — which is precisely why this recipe needs no task system, no async
runtime, and no scheduling policy. The difficulty is that the handle arrives
later, not that anything in re-frame is asynchronous.

Prove the recipe with a deterministic Promise-acquired surrogate whose acquisition
settles only on a controlled trigger, so the evidence is reproducible rather than
a timing observation. A real third-party witness — Vega Embed, a Maps loader, a
void-mutator SpreadJS-shaped adapter — is **evidence-deferred**: recorded as
not-now rather than dropped, and it does not gate the recipe. Do not add a task
system or helper until at least two more real integrations repeat irreducible
machinery.

### DC-03 — Pure form transitions and causal owner release

Ship a small side-effect-free CLJC form module over ordinary application data. It
registers no events or subscriptions and owns no atom, validator engine, or form
runtime.

Its starting public operation roster is:

```text
init · edit · visit · seed · reset · rebase · set-errors
attempt-submit · reset-key
```

The model distinguishes baseline, draft, visited, edited, per-leaf reset
revision, structured errors, submit attempt/status, stable vector leaf paths, and
causal ownership. Seed preserves unrelated state and edited leaves; reset and
rebase are distinct; invalid submit remains attemptable; stale async and buffered
operations are inert; composing Enter never commits.

Owner clearing is an explicit application transition that removes the form slice
and its controller records atomically. Unmount is not a domain event, and absence
of a mounted occurrence is not proof of orphaning.

### DC-04 — A small first-party control kit grown through witnesses

Keep pure transitions in `re-frame.freehand.form` and versioned control
descriptors and protocol machinery in `re-frame.freehand.controls`. Skins,
examples, and application compositions may be copied and adapted; correctness
machinery is not copy-only.

Start with `field`, `buffered-field`, and one causal owner-clear operation. Grow
through serious witnesses rather than a catalogue promise: form/fields,
typeahead with anchored menu or popover, splitter, and a fixed-size virtual
collection/table. Each witness must show a short call site, public props/parts,
ownership and cleanup, relevant browser laws, structural proof, parity or an
honest limitation, and measured work at its hard edge.

### DC-05 — Conservative compiled source judgment

Classify admitted child-producing expressions as a known template or boundary,
an audited scalar producer, or an opaque result. Opaque child results are
checker/build ineligible before render and report one stable id, source,
expression summary, and executable recoveries.

Carry the judgment through admitted control/binding forms, but do not invent a
general type system or a theoretical scalar allowlist. The checker and exact
build analyzer expose the same facts. Existing priced slot and crossing limits
remain honest limits.

### DC-06 — Orthogonal contract evidence

Report independent props facts—declaration, key closure, value validation, and
generator availability—rather than compressing them into “schema present.”
Application-private schemas remain optional; public library and catalogue
surfaces retain D011's stronger policy.

Report controlled-site knowledge independently as `known-open`,
`known-closed`, `dynamic`, or `unavailable`, including its basis. A legal
doorless site is reported rather than nagged. Incomplete evidence never becomes
apparent proof, and sensitive values never enter diagnostics.

### DC-07 — One executable fixture spine

Use one small workshop application and one stable fixture identity per claim.
Project each fixture into its guide source, checker/build facts, JVM structural
assertions, mounted browser proof, runnable route, and Xray evidence.

The first fixtures are the declared React host, serious form, and Vega async
owner. Maps, Motion, SpreadJS, and controls join as their slices land. Publish
`v/check` over the exact build analyzer and a small
`re-frame.freehand.test` lifecycle facade. Do not create a browser-action DSL, a
second test language, a second evidence store, or a documentation generator
before repetition earns it.

### DC-08 — Publish ownership routing and pave nested roots

Publish one short routing guide:

| Ownership shape | Route |
|---|---|
| Pure or framework-neutral core | ordinary value logic with re-frame as state adapter |
| React value/callback component | declared `v/defhost` |
| Hooks/context/refs/compound protocol | React-owned wrapper registered with `v/defhost`; keep the region React-primary |
| Imperative node-owned SDK | registered behavior |
| Freehand descriptor requested as a React component | `v/->react` |
| Host-created DOM pane | explicit nested root with authored identity, frame, and reclamation |
| Deliberately isolated region | explicit island/root with stated context and teardown costs |

State the permanent limits beside the routes: hooks stay outside `v/defview`; raw
elements are opaque; `asChild` and ref cloning stay in a React owner; nested roots
do not inherit the outer React context, bubbling, or Suspense; one subtree has one
exit-retention owner.

Prove the behavior-plus-nested-root route with a Maps overlay witness and publish
small comparative ceremony tallies. Do not add `v/->element`, behavior outlets,
or a portal manager until Maps plus a second independent integration show the
same substantial ownership steps.

### DC-09 — Measure the two-clock seam before adding policy

Let behaviors own high-rate host motion while semantic `started`, optional
`preview`, and `committed` events cross into re-frame. Keep host-local geometry
and motion out of app-db unless the application genuinely needs the live values.

Measure offered and observed host events, accepted semantic events, reducer
applications, commits, presentation opportunities, backlog, settlement tail,
equality cost, and host update cost across realistic rates and dirty-sibling
loads. Publish distributions and deterministic correctness under D021. Do not add
generic throttle, debounce, coalescing, equality, or preview vocabulary until an
attributed material bottleneck repeats in two realistic witnesses.

## Accepted execution rulings

1. **ER-01 — Run the `v/$` comparison now.** Compare one virtual-table fixture
   across tuned interpreted Freehand, compiled Hiccup, and a `v/$` spike while
   preserving descriptor, callback, ViewCell, structure, and evidence semantics.
   Pause only discretionary compiled-Hiccup completion. The experiment cannot
   change the architecture without a later Mike ruling.
2. **ER-02 — Fix the React-door surface.** The name is `v/defhost`; there is one
   host kind, no runtime `v/host`, no `:kind` split, and no `v/react-el`.
3. **ER-03 — Land an honest host vertical slice.** Interpreted mounting,
   committed callback lifecycle, structure, SSR, and checker/build behavior land
   together. A compiled crossing either works or refuses at build time with
   source and recovery.
4. **ER-04 — Keep forms and controls in Freehand's distribution.** Use
   `re-frame.freehand.form` and `re-frame.freehand.controls`; do not create a
   separate artifact or registry now.
5. **ER-05 — Fix witness order, not catalogue size.** Form/fields, then
   typeahead/popover, splitter, and fixed-size virtual table. The architecture
   comparison may run in parallel.
6. **ER-06 — Fix the productivity surface.** Public paths are `v/check` and
   `re-frame.freehand.test`, backed by the shared workshop/fixture identity.
7. **ER-07 — Delegate reversible details.** The programmer owns private
   structures, decomposition, diagnostic prose, source layout, and benchmark
   mechanics. Return to Mike only for a new public concept, a contradiction, an
   evidence-gated graduation, or the `v/$` architecture result.
8. **ER-08 — Preserve subscription overrides as a test/tool seam.** Story's exact
   subscription-query-to-pinned-value capability survives through
   `re-frame.freehand.test`, not through a general production `v/*` API. It is
   dev/test-only, matches the whole query vector by ordinary equality, does not
   mutate app-db or satisfy subscription assertions, falls through to ordinary
   subscription behavior on a miss, does not create a second reactive state
   system, states its fidelity and loss, and cleans up exactly. Story's provider
   consumes the seam; ordinary application code does not. D012 records the
   evidence amendment and Spec 008 owns the implementation contract.

## Delivery discipline

Deliver the setpoint as thin vertical slices: owning spec, implementation,
conformance, fixture, guide, and evidence together where applicable. A prose
phase must not block runnable work. New public verbs use the existing
API-manifest serial lane.

The programmer may adjust reversible names not fixed above, initial corpus sizes,
benchmark parameters, private workshop layout, and implementation mechanics.
Workers and AIs must not implement from superseded examples.

The only deliberately deferred product choices are:

- whether the `v/$` evidence justifies replacing compiled Hiccup;
- whether repeated compound or nested-root ceremony earns `v/->element` or a
  behavior outlet;
- whether repeated async integrations earn a reusable helper; and
- whether attributed measurements earn scheduling, throttling, preview, or
  equality vocabulary.

These are evidence gates, not missing prerequisites.
