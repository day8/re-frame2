# Motivating use cases

| Job | Design pressure | Hicasso consequence |
|---|---|---|
| Ordinary forms, lists, branches, and keyed collections | Closed compiled grammars impose extraction and syntax cliffs; ordinary Clojure composition is valuable. | Preserve one interpreted mode, helpers, loops, and ambient reads. |
| Dynamic and conditional reads | Compile-indexed sites give attribution but cannot express the desired authoring model. | Keep read-anywhere during direct synchronous boundary execution; solve collection safely at runtime. |
| Controlled input and IME | This defect-prone behavior needs a narrow framework law. | Keep the centralized door, but require Chromium, Firefox, and WebKit plus broader control/hydration coverage and published per-keystroke mechanics. |
| Ephemeral and host-private state | One application state needs pressure valves for drafts, revisions, composition, geometry, observers, and imperative handles. | Keep domain/workflow state in re-frame; put reusable control state in concern-named libraries and host-private facts in native React hosts. Do not add a generic Hicasso local-state DSL. |
| React libraries and compound components | Access to the React ecosystem and same-root hot path is a core adapter requirement; a large host algebra is not. | Complete the core host/outward bridge with real vendors and apply the [native-boundary law](design-laws.md#native-boundary) plus its single acceptance checklist. |
| Imperative SDKs | Ownership route and cleanup proof matter more than a neutral lifecycle DSL. | Use an ordinary Hicasso-native, UIx, or raw-React host component; publish recipes and adversarial cleanup tests first. |
| Routing | A late-bound plain route-link works without reactive-boundary machinery. | Keep routing small and test browser behavior plus SSR output. |
| Async resources and typeahead | Mounted demand is promising, but lifecycle machinery can create a second dependency ledger. | Spike only after root/commit ownership is sound; reuse committed read membership or kill the idea. |
| Theming and parts | Tokens, CSS variables, root scope, and ordinary composition cover most needs. | Defer parts registries/tree rewriting until a component-library caller proves them necessary. |
| Errors | React error regions are sufficient; expected failures remain data. | Ship a minimal wrapper, not a second exception model. |
| SSR and hydration | Full React semantics imply a core React server/hydration contract; JVM structural equality is not wire equality. | Inventory every public surface under the [canonical SSR/hydration matrix](react-compatibility-notes.md#public-surface-ssrhydration-matrix). A bounded Node/React service starts only for a named caller; no JVM twin. |
| Tooling and inspection | Committed/runtime evidence and static possibility are different facts. | Start with bounded lint and pure current-state projections for a named consumer. |
| Large/bulk UI | Fixed per-boundary machinery can fail product economics; the current broad comparator is instrument-limited. | Qualified sparse/broad/reorder/edit evidence, retained heap, the boundary-shell disposition, abandonment and teardown are release gates. Warm allocation is not: publish no allocation claim until its fitted-series instrument qualifies. |
| Reagent migration | Real conversions contain non-mechanical cases. | Build a reporter/linter and explicit refusal guidance before a rewriting codemod. |

## Evidence scope

The Hicasso census strongly supports data intents, controlled fields, loops/keys, route links, and read-at-point-of-use. It weakly represents refs, portals, foreign observers, and complex React integrations; rarity in one repository is not proof those jobs do not matter.

Keep the census alive as a requirements mine: job, observed frequency, failure classes, home, owner, and executable witness. Frequency can justify syntax; a rare critical job normally earns a first-class escape or optional module. Every core proposal is compared with the smallest equivalent direct-React, Hicasso-native, or established-library control before it earns universal cost.

The current dogfood screen is strong preference evidence for one ordinary list/form workload, not external adoption evidence. A full RealWorld-style application and one serious vendor integration are the next useful witnesses.
