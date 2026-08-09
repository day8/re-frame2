# Hicasso adversarial risk register

A missing witness is not a confirmed defect. Each risk has a required contract, a deciding witness, and a remedy if the witness is red.

## Phase 1 kernel risks

| Risk | Required contract | Deciding witness | Remedy if red |
|---|---|---|---|
| Frame reincarnation and cached operations | Every delayed operation targets the intended frame incarnation; a reused public id cannot revive an old handle | Mount, dispatch, destroy, recreate the same id, then dispatch both current and retained callbacks | Key operation bundles by incarnation or evict them exactly on destruction |
| Process-global ownership | Independent roots and SSR requests cannot reset, adopt, dirty, or release one another's state | Overlapping hydration, independent root release, concurrent request frames and root-unmount failure | Move each mutable owner to root/adapter/request scope |
| Speculative render leakage | Render probes only; the selected commit owns reads and evidence; abandoned attempts leave zero residue | Suspense/transition abort, changed reads, delayed commit, error retry and residue census before any global reset | Make commit identity authoritative and replace time-based/reaper assumptions |
| Ambient-read extent | `sub` works only during direct synchronous execution of the active body; every deferred crossing refuses with source and recovery | Nested helper, branch, loop, render prop, event, promise, timer, lazy sequence and module escape matrix | Use an explicit collector stack/token for supported nesting and fail closed elsewhere |
| Controlled-input portability | Echo, rejection, normalization, composition, selection and revision remain correct across supported React/browser versions | WebKit/Firefox native composition and `beforeinput`, range/direction, autofill, reset, blur, unmount and upgrade matrix | Own the last committed model value or narrow/refuse a control whose platform contract cannot be made stable |
| HMR identity | Reload either preserves the stated identity/focus/state contract or promises a clean, diagnosable subtree remount | Focused/uncontrolled input, child hook state, active host, frame routing and cleanup through reload | Use the smallest stable shell justified by the witness or document/lint the remount contract |
| Callback identity and retirement | Retained handlers route to the correct frame and become harmless after retirement; ordinary code pays no universal proxy cost | Memoized foreign child retaining callbacks through rerender, unmount and same-id frame replacement | Add a localized host-edge stable-event primitive only when required |
| Hydration isolation | Adoption and mismatch attribution are root-scoped; simultaneous roots cannot cross-contaminate | Two overlapping hydrated roots with independent complaints and teardown | Root-scope adoption/evidence state and fail closed on ambiguous ownership |

These eight risks block the trustworthy-kernel exit. Native-surface risks enter only when Phase 3 introduces that optional surface.

## Phase 3 native-surface risks

| Risk | Required contract | Deciding witness | Remedy if red |
|---|---|---|---|
| Native-language leakage | Clauses 2–3 of the [native-boundary law](design-laws.md#native-boundary) | Native-form grammar row of the [canonical checklist](hot-path-architecture.md#canonical-native-tier-acceptance-checklist) | Refuse ambiguous mixing with a source-located recovery; keep the namespaces and syntax visibly distinct |
| Native boundary ABI drift | Clause 5 of the native-boundary law | Component-ABI, same-root and server rows of the canonical checklist | Shrink the one ABI and remove helpers that cannot prove parity |
| Optional native-tier rent | Clause 6 of the native-boundary law | Dependency-and-rent row of the canonical checklist | Split the namespace/module boundary until tree shaking is deterministic; refuse universal helper state |
| Hook-semantics fork | Clause 4 of the native-boundary law | Frame-and-store-lifecycle row of the canonical checklist | Factor and reuse the shared React seams before publishing the namespace; do not maintain a Hicasso-specific copy |

## Programme and evidence risks

| Risk | Required contract | Deciding witness | Remedy if red |
|---|---|---|---|
| Instrument self-flattery | An instrument qualifies before product data publishes; controls detect the named failure class and the estimator/population are pinned | Sabotage the clock, floor, population and masking assumptions and observe a refusal rather than a result | Withdraw the row, fix the instrument, and rerun without carrying forward its previous number |
| Comparator mismatch | Controls are tuned and behavior/read topology is equal, or every deliberate capability difference is stated beside the ratio | Canonical DOM, intent, read-shape and host-crossing audit before clock/heap comparison | Rebuild the control or reframe the claim as a priced capability comparison |
| Equality substitution | Each gate names authored-data, semantic-tree, DOM, intent, server-byte, hydration, commit or paint equality without treating one as another | Adjacent-text hydration sabotage and an L2-vs-mounted lifecycle counterexample | Replace the oracle with the platform-authoritative equality; keep the weaker result under its honest name |
| Experimental residue becomes architecture | One taught public story; no primary product/tool path relies on re-frame.ui or Freehand after Hicasso evidence is live | Dependency graph, namespace/doc census, production sentinels and Xray/Story/Pair consumer audit | Migrate consumers first, retain only named compatibility fixtures, then archive or remove the donor surface |

## Gate construction

- Publish the exercised population and inspect residue before fixture-global reset.
- Include a sabotage mutation that makes each correctness gate red.
- Keep source evidence, revision/substrate, inference and overturning observation traceable.
- Treat a precedent, census absence, descendant measurement or co-instrumented pair as context, never as a current gated result.
