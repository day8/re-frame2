# Hicasso invariant and capability ledger

One page to check a change against: every invariant the product commits to, every capability and the rent it pays, and the provisional facade as the specification currently spells it.

Nothing here is new. Every row is transcribed from the [product specification](specification.md), the [design laws](lanes/design-laws.md), the [public-language lane](lanes/ergonomics-api.md), or the [decision brief](decision-brief.md), and cites its owner. **Where a row and its owner disagree the owner governs and the row is the defect** — fix the row; questions are not settled here.

Cite key: `§n` is a [product specification](specification.md) section; a capitalised word plus a number, such as *React 5*, is that numbered law in the named section of the [design laws](lanes/design-laws.md).

## 1. Invariants

| # | Invariant | What it requires, and what it forbids | Owner |
|---|---|---|---|
| I1 | One interpreted semantics | Interpreted Hiccup is the only ordinary meaning. No `:fast` flag, compiled body, alternate renderer, automatic specialization, or profile-dependent meaning; no public option selects an execution mode. | [§3.1](specification.md#31-one-language-and-one-state-owner) · [React 5](lanes/design-laws.md#react-and-ownership) · [Economics 5](lanes/design-laws.md#economics-and-scope) |
| I2 | Two explicit languages | `[...]` always means interpreted Hiccup. `n/$` compiles only its own explicit native form and never analyses or rewrites a `defview` body; native construction is explicit at the form or component boundary. | [Native 2](lanes/design-laws.md#native-boundary) · [§3.1](specification.md#31-one-language-and-one-state-owner) |
| I3 | One state owner | re-frame2 owns application state. Subscriptions are the only adapter reactive source; the re-frame2 state commit is the application write clock. No ratom-like second model. | [§3.1](specification.md#31-one-language-and-one-state-owner) · [State 1–2](lanes/design-laws.md#state-and-reactivity) |
| I4 | Named state pressure valves | Durable or application-visible state, including drafts affecting validation, navigation, replay, or collaboration, takes an explicit re-frame2 address. High-frequency state that only operates a native widget may stay host-private and is diagnostic-opaque by contract. DOM-owned state is an explicit interop choice, never a hidden substitute. | [§3.1](specification.md#31-one-language-and-one-state-owner) · [State 3](lanes/design-laws.md#state-and-reactivity) |
| I5 | Render probes, commit owns | Render is speculative: it probes reads, acquires no durable ownership, and publishes no committed evidence. The selected commit reconciles exactly its read and host set; disconnect releases it. Abandoned renders leave no subscriptions and no diagnostic records; teardown is exact and testable, with zero residue after quiescence. | [§3.2](specification.md#32-react-owns-react-facts) · [React 1–3](lanes/design-laws.md#react-and-ownership) |
| I6 | React owns React facts | React owns component identity, hooks, refs, effects, errors, context, concurrency, Suspense, Activity, hydration, commit, and DOM reconciliation. Hicasso uses React's public contracts rather than simulating them. | [§3.2](specification.md#32-react-owns-react-facts) · [React 4](lanes/design-laws.md#react-and-ownership) |
| I7 | The ambient-read extent | `sub` is legal during the direct synchronous execution of the active body, helpers, branches, and loops included. A read deferred through a callback, promise, timer, lazy sequence, or other escaped extent refuses with source and recovery. Dynamic reads reconcile from the selected render without a general per-boundary dependency ledger. | [§3.3](specification.md#33-dynamic-composition-is-a-feature) · [State 4–5](lanes/design-laws.md#state-and-reactivity) |
| I8 | Capability pays rent | A facility used by few boundaries adds no standing machinery to all boundaries. Optional libraries and the native namespace are separately reachable and absent from a production bundle that does not use them. Stable callback machinery, read ledgers, static manifests, and lifecycle histories require a measured caller before they exist. Standing cost is the first budget; Hiccup lowering is the second. | [§3.4](specification.md#34-capability-pays-rent) · [Economics 1–2](lanes/design-laws.md#economics-and-scope) |
| I9 | The two-hook ceiling | The ordinary boundary's context plus external-store hooks consume the whole budget. Optional capabilities may not add a hook to every boundary or a universal ownership graph; freeing or replacing one requires its own correctness and whole-shell measurement. Measure the do-nothing and read-free boundary whenever the shell changes. | [§3.4](specification.md#34-capability-pays-rent) · [State 6](lanes/design-laws.md#state-and-reactivity) |
| I10 | Data by default | Markup and ordinary event intent are data; an explicit handler form covers value-first or calculated events; ordinary functions cross host boundaries unchanged when a JavaScript API genuinely expects executable behaviour. Position never silently changes the meaning of the same function form, and prevention is explicit and uniform. | [§3.5](specification.md#35-data-by-default-functions-when-the-contract-is-executable) · [Language 1, 4](lanes/design-laws.md#language-and-interop) |
| I11 | Loud, stable failure | Every refusal carries a stable error id, source coordinate, view, frame where relevant, tree path or host-prop position, offending value, expected shape, and actionable recovery. Unknown, opaque, capped, and uncorrelated are first-class results, never rendered as an authoritative empty one. | [§3.6](specification.md#36-loud-stable-failure) · [Language 6](lanes/design-laws.md#language-and-interop) · [Evidence 6](lanes/design-laws.md#evidence-and-tools) |
| I12 | Production erasure | Diagnostic and profiling sentinels are absent from default production bundles; tool evidence is versioned, privacy-projected, loss-accounted, and absent from production. Developer products are development-only. | [§6](specification.md#6-performance-contract) · [§10](specification.md#10-xray-and-runtime-evidence) · [Evidence 5](lanes/design-laws.md#evidence-and-tools) |
| I13 | Interop and SSR are core contracts | Every public view, host, root, resource boundary, and native escape renders deterministically on the server or refuses with recovery, and has explicit hydration behaviour under the [canonical matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix). Only the deployable Node service is optional. | [§1](specification.md#1-product-shape) · [Language 8](lanes/design-laws.md#language-and-interop) |
| I14 | One facade | Applications get one obvious `h` facade; optional capabilities live in clearly named namespaces with bundle-reachability proofs, and no benchmark-only aliases. | [§4](specification.md#4-target-programming-model) · [Language 7](lanes/design-laws.md#language-and-interop) |
| I15 | Controlled fields are a framework law | Interpreted controlled fields converge within the turn that edited them, echo only committed state, and preserve caret, selection, and in-flight composition across that echo. A rejected or normalized value echoes as the committed one rather than being silently accepted, and reset is an explicit revision that preserves element identity. Owned `value`, `checked`, handler, key, and revision slots win by presence, not truthiness; a forwarded attribute cannot replace one. Each field kind and edge in the §4.2 roster carries an explicit support or refusal policy; drafts, touched/submit-attempt validation, and mutation status stay out of the boundary shell. | [§4.2](specification.md#42-controlled-fields) · [Language 3](lanes/design-laws.md#language-and-interop) · [authoring laws 6–7](lanes/ergonomics-api.md#authoring-laws) |

## 2. Capabilities and the rent they pay

| Capability | Home | Cost rule | Owner |
|---|---|---|---|
| Interpreted core | Views, reads, Hiccup lowering, event intents, controlled fields, roots, SSR/hydration semantics | Small fixed boundary shell; variable cost follows actual reads and markup | [§1](specification.md#1-product-shape) |
| React bridge | Declared hosts, raw elements, providers, refs, render props, outward embedding, server policies | Paid only at the crossing | [§1](specification.md#1-product-shape) · [§4.3](specification.md#43-host-interop) |
| Native hot path | `re-frame.hicasso.native`, UIx, or raw React/JavaScript | Paid only by the selected region; absent when the namespace is unused | [§1](specification.md#1-product-shape) · [§5](specification.md#5-native-react-hot-path) |
| Optional libraries | Forms, overlays, presence, routing integration, deployable Node/React SSR service | Named consumer required; zero reachable production code when absent | [§1](specification.md#1-product-shape) · [Economics 4](lanes/design-laws.md#economics-and-scope) |
| Developer products | Testing, Xray, lint, migration, AI-readable evidence | Development-only; erased from production | [§1](specification.md#1-product-shape) · [§9](specification.md#9-testing-as-a-product-surface) |

Entry rule: a feature enters core only when it removes repeated ceremony or a centralized defect class, has at least one paying witness, adds no unexplained standing boundary cost, and beats the smallest equivalent direct-React, Hicasso-native, or established-library alternative. Recipes graduate to APIs only after repetition proves prose inadequate ([§2.1](specification.md#21-use-case-compass), [Economics 3–4](lanes/design-laws.md#economics-and-scope)).

## 3. Provisional facade — ordinary surface (`h`)

| Surface | Contract | Owner |
|---|---|---|
| `h/defview` | Define a React/re-frame boundary; use it as a Hiccup head; direct Clojure invocation refuses | [§4](specification.md#4-target-programming-model) · [core surface](lanes/ergonomics-api.md#proposed-core-surface) |
| `h/sub` | Return a subscription value during the direct synchronous body; legal in branches, loops, and ordinary helpers | [§4](specification.md#4-target-programming-model) · [core surface](lanes/ergonomics-api.md#proposed-core-surface) |
| `h/handler` | Capture the current frame; run later; dispatch a returned event vector; ignore `nil`; one meaning in every position | [§4](specification.md#4-target-programming-model) · [§4.1](specification.md#41-events) |
| `h/defhost` | Declare a foreign React ABI, ReactNode-valued positions, and server policy once | [§4](specification.md#4-target-programming-model) · [§4.3](specification.md#43-host-interop) |
| `h/as-element` | Explicitly lower Hiccup returned through a render prop or foreign callback | [§4](specification.md#4-target-programming-model) · [interop](lanes/ergonomics-api.md#interop-contract) |
| attribute merge | Pure owned-wins helper or recipe for forwarded attributes; public only if a witness needs it | [§4](specification.md#4-target-programming-model) · [exclusions](lanes/ergonomics-api.md#surface-boundaries-and-exclusions) |
| root lifecycle | `h/mount!`, `h/hydrate!`, `h/render!`, `h/unmount!` with idempotent handles | [§4](specification.md#4-target-programming-model) · [core surface](lanes/ergonomics-api.md#proposed-core-surface) |
| server/hydration contract | A Render or Client-only policy for every inventory id under the canonical matrix; root-scoped identity, errors, adoption, cleanup | [§4](specification.md#4-target-programming-model) · [matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) |
| `h/error-boundary` | A minimal error-region surface | [§4](specification.md#4-target-programming-model) · [core surface](lanes/ergonomics-api.md#proposed-core-surface) |
| interpreted grammar | A fragment head, a raw React element head, one props map, and a small reserved-data vocabulary: event value, checked value, explicit prevention, controlled-value revision | [§4](specification.md#4-target-programming-model) · [core surface](lanes/ergonomics-api.md#proposed-core-surface) |

`rf/current-frame-id` and `rf/capture-frame` remain the frame doors; Hicasso does not duplicate them ([§4](specification.md#4-target-programming-model)).

## 4. Provisional facade — native surface (`n`)

Separately imported as `re-frame.hicasso.native`, conventionally aliased `n`. The [native-boundary law](lanes/design-laws.md#native-boundary) owns its semantics; the [canonical native-tier checklist](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist) owns its release proof.

| Surface | Contract | Owner |
|---|---|---|
| `n/$` | Compile one explicit element form to direct React construction; native props, callbacks, children, keys, and refs apply, with no Hicasso intent or controlled-field lowering | [§4](specification.md#4-target-programming-model) · [grammar](lanes/ergonomics-api.md#provisional-n-grammar) |
| `n/props` | Disambiguate a dynamic map or JavaScript object as the props operand; the marker itself emits no wrapper | [§4](specification.md#4-target-programming-model) · [grammar](lanes/ergonomics-api.md#provisional-n-grammar) |
| `n/defcomponent` | Define a top-level native function component with stable identity, source/HMR metadata, one props/children ABI, and an explicit server policy defaulting to Client-only | [§4](specification.md#4-target-programming-model) · [native surface](lanes/ergonomics-api.md#optional-native-surface) |
| `n/use-sub` | Read re-frame2 state through a native React hook under the current Hicasso frame | [§4](specification.md#4-target-programming-model) · [Native 4](lanes/design-laws.md#native-boundary) |
| `n/use-frame` | Obtain frame-locked operations from that same context | [§4](specification.md#4-target-programming-model) · [Native 4](lanes/design-laws.md#native-boundary) |
| native ABI helpers | Preserve the component contract through memoization, lazy loading, refs, and outward/inward embedding where raw React would erase the marker | [§4](specification.md#4-target-programming-model) · [Native 5](lanes/design-laws.md#native-boundary) |

React hooks remain React's surface and may be called directly inside `n/defcomponent`; wrappers are added only after repeated island code proves material value ([§4](specification.md#4-target-programming-model)).

## 5. Prototype-to-product dispositions

The prototype-to-product disposition prunes rather than adds ([decision brief](decision-brief.md#part-ii--review-hicasso-against-the-goal), [§4](specification.md#4-target-programming-model)); each row is also a [naming-ledger](naming-ledger.md) row.

| Prototype surface | Disposition | Owner |
|---|---|---|
| `h/fn` | Becomes `h/handler`, with one invariant meaning everywhere | [§4](specification.md#4-target-programming-model) · [ledger 1](naming-ledger.md) |
| `:&` merge key | Demoted out of the grammar; a pure owned-wins merge helper or recipe replaces it, public only on a witness | [§4](specification.md#4-target-programming-model) · [ledger 2](naming-ledger.md) |
| `h/reg-state` | Leaves the adaptor core; reconsidered only in a forms layer | [§4](specification.md#4-target-programming-model) · [ledger 3](naming-ledger.md) |
| `subscribe-once` | Internal until a caller proves `sub` inadequate | [§4](specification.md#4-target-programming-model) · [ledger 4](naming-ledger.md) |
| presence | Moves to its optional motion namespace | [§7](specification.md#7-complete-use-case-coverage) · [ledger 5](naming-ledger.md) |
| route-link | Moves to its optional routing-integration namespace | [§7](specification.md#7-complete-use-case-coverage) · [ledger 6](naming-ledger.md) |
| the two missing conversions | `h/as-element` and the outward bridge — a native React parent rendering a minted Hicasso view under the shared frame — are genuinely absent and are added | [decision brief](decision-brief.md#part-ii--review-hicasso-against-the-goal) · [§4.3](specification.md#43-host-interop) |

## 6. Names freeze on witnesses

Phase 0 freezes the laws and classifications, not the spellings. Ordinary authoring names freeze from the [Phase 2](specification.md#phase-2--ship-one-lovable-vertical-slice) application witness; host, outward-bridge, and hot-path names freeze from the [Phase 3](specification.md#phase-3--make-the-native-hot-path-excellent) witnesses, and the native grammar and ABI only when every row of the [canonical native-tier checklist](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist) passes. The public model should not grow beyond sections 3 and 4 above without a witnessed need ([§4](specification.md#4-target-programming-model)).

Until then nobody renames mid-flow: prototype names are used consistently, every naming question any bead finds is appended to the [naming ledger](naming-ledger.md) as a row, and one consolidation sitting rules them together.

## 7. Recorded freezes

Append-only. A bead that measures or witnesses one of the invariants above records its result here as its own `###` subsection and flips its row below to *recorded*; it does not rewrite sections 1–6. Where a freeze narrows an invariant, the invariant row gains a link to the subsection and keeps its wording.

| Paragraph owed | Owner bead | Status |
|---|---|---|
| The ambient-read extent as enforced — the legality matrix and each refusal (narrows I7) | rf2-hic-011 | not yet recorded |
| The callback and frame-incarnation rule for a same-public-id frame (narrows I5) | rf2-hic-013 | not yet recorded |
| The HMR contract: what survives a reload and what is cleanly remounted (narrows I5) | rf2-hic-015 | not yet recorded |
| The two-hook ceiling frozen with its measurement, and the chosen collector substrate (narrows I9) | rf2-hic-018 | not yet recorded |

No freeze is recorded yet: this page is the [Phase 0](specification.md#phase-0--freeze-the-invariants-and-establish-the-product-spine) ledger, and the witnesses are Phase 1 work.
