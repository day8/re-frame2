# Testing and Xray product contract

## Recommendation

Testing and diagnostics are product surfaces, not documentation afterthoughts. Ship a supported `re-frame.hicasso.test` namespace and a dev-only adapter-neutral evidence provider. Do not expose the raw internal sink or invent evidence the interpreted runtime cannot know.

## Testing ladder

| Tier | Scope | Correct tool |
|---|---|---|
| L0 | event handlers, subscriptions, state transitions | pure CLJ/CLJS tests |
| L1 | codecs, intents, controlled-field/presence laws, native-form expansion and ABI helpers | pure data/property and macro-expansion tests |
| L2 | hook-free Hicasso bodies and owned Hicasso children | restricted semantic-tree harness |
| L3 | React lifecycle, hooks, context, refs, foreign hosts and native components | mounted React DOM tests |
| L4 | IME, caret, focus, hydration, layout, performance | Chromium, Firefox, and WebKit witnesses |

The L2 harness is not a JVM renderer or alternate execution semantics. It invokes the view body under a discardable subscription resolver and returns a versioned semantic tree with small `find`, `attrs`, `text`, and `intents` helpers. The body is the function itself when a test names it, and otherwise the one the minted `defview` head carries: the mint attaches it to the head under a dev-only property so a hook-free harness can reach it, which is a single own property and not a registry. That property does not survive an `:advanced` build with `goog.DEBUG` false, so a minted head refuses there.

Before defining another schema, audit the existing versioned structural-tree and Spec-011 assertion utilities and reuse compatible data/helpers only; do not inherit another renderer, SSR authority, or simulated React lifecycle. Hooks, `n/$` results, native components, and raw/foreign hosts are opaque and refuse with a pointer to L3. Missing fixtures refuse; they are never replaced with fake React dispatchers.

The mounted facade should provide isolated-frame `mount!`, `hydrate!`, `render!`, `dispatch-and-settle!`, `settle!`, `unmount!`, and `assert-clean!`. It should interoperate with Testing Library and user-event instead of introducing another selector language. Cleanup unmounts, waits for Hicasso quiescence, compares residue with the pre-mount baseline, and only then resets.

Every witness names the equality it proves. Authored-data equality, semantic assertion-tree equality, canonical DOM, intent streams, React server bytes, and hydrated browser behavior are distinct claims. A normalized tree is never a proxy for hydration-wire parity, and L2 never claims React lifecycle parity.

## Required browser and lifecycle witnesses

- StrictMode, retry, suspension, and genuinely abandoned renders.
- Conditional and changing read sets, keyed identity, and multiple roots/frames.
- Controlled input echo, rejection, normalization, revision reset, selection, and real IME composition.
- Context, refs, errors, foreign hooks, retained callbacks, HMR, and same-id frame replacement.
- The [canonical native-tier checklist](hot-path-architecture.md#canonical-native-tier-acceptance-checklist), using L1 expansion/property tests plus L3/L4 React and browser witnesses; this lane supplies test mechanisms but does not redefine checklist membership.
- Server bytes, hydration, adjacent text, `useId` prefixes, recoverable mismatch reporting, and two hydrating roots.
- React Activity hide/reveal: subscriptions and effects stop while hidden, then re-establish correctly on reveal.
- Exact post-quiescence residue rather than reset-masked cleanliness.

## Evidence contract

Xray and the AI pair consume the same versioned projection. Every envelope states schema, producer, read operation, scope, basis, completeness, and loss. Unknown is never encoded as an empty collection.

Useful basis/loss states include:

- `:opaque` / `:no-static-analysis` for facts an interpreted body cannot enumerate ahead of execution;
- `:host-opaque` for raw React internals;
- `:cap` for retention-window loss;
- `:uncorrelated` when an event-to-render relationship cannot be established.

Project current registrations from state Hicasso already retains and use the existing trace ring for history. Do not add a universal accumulator, universal occurrence identity, or second history buffer merely to make a panel look complete. A named operation such as mount/unmount correlation or capsule capture may allocate bounded, commit-owned identity. Production returns nil and contains no evidence, schema, or source sentinels.

Preserve the live Xray consumer contract while replacing producer semantics behind the adapter-neutral schema. Move every primary Xray/Story/Pair consumer off the experimental re-frame.ui/Freehand producer before those donor surfaces are disposed; fixture-only integrations may remain as named compatibility evidence, not an architecture foundation.

## Questions Xray must answer

1. What Hicasso views ran or committed in this epoch?
2. Which changed subscriptions intersected the current reads?
3. Did props, context, a read-set change, or a host boundary trigger the work?
4. Where are read fan-out, read-set churn, render storms, retries, and abandoned work concentrated?
5. Is time in the body, Hiccup lowering, React commit, layout, or paint?
6. Which boundary is a credible topology-tuning or native-island candidate, and is direct `n/$`, a named Hicasso-native component, UIx, or a foreign host the smallest fitting route?
7. What is unknown, capped, opaque, or uncorrelated?

Render timing is not commit evidence. Correlate event, subscription recomputation, boundary invalidation, body run, commit, and paint when the instruments support it; label every missing link. Xray owns bounded retention. React DevTools and browser performance tools remain the authority for React commits and paint.

Deterministic gates block ordinary changes. Distributional performance evidence uses pinned interleaved runs. Each instrument states its estimand and exercised population, carries a positive/sabotage control, and refuses to publish when its validity check fails.

Instrument qualification precedes the product row. The qualification states the failure class each control can detect, pins the estimator before candidate data is opened, and proves the population is non-empty. A benchmark implementation and its eligibility check land together; an invalid instrument produces no flattering fallback number.

Production-erasure tests use unique sentinels and a reachable positive-control sentinel. Drift-sensitive browser measurements carry an interleaved same-run floor/control.

## Failure and privacy contract

Every testing refusal is structured and source-located. Query results never leave the process unless an explicitly authorized consumer requests them, and query arguments pass through the existing privacy projector. Sink failures are contained. Performance collection is independently gated and off by default.

Optional modules contribute evidence only while installed and used. Forms, overlays, presence, resources, and native islands may add their own bounded projections—for example draft ownership, active top-layer region, transition posture, resource demand, or native read edges—but none adds a universal accumulator to ordinary Hicasso.

## Acceptance

Release requires positive and sabotaged negative controls for semantic trees and intents; dispatch-to-settled-DOM; lifecycle abandonment; cleanup residue; controlled-input browsers; the canonical SSR/hydration matrix; the canonical native-tier checklist; schema mismatch; cap/opaque/uncorrelated displays; privacy redaction; byte-equivalent Xray/Pair projections; and production erasure.
