# Second Resources review

Reviewed on 15 September 2026 against source baseline `f2f9ffd654b70da97e907758cc73daa3bd205d57`. The initial capture found Grok unchanged and Fable revised; both then published further revisions during this review. The final reviewed snapshot is [round 6](evidence/sibling-review/round-6/receipt.json), captured at 07:57 Sydney time. [Round 5](evidence/sibling-review/round-5/receipt.json) preserves the initial capture and Astra's previous corpus under `astra-before/`. The numbering includes incremental captures from the first review.

## The finding that changes the recommendation sequence

The tutorial repair is already on main. [PR #9838](https://github.com/day8/re-frame2/pull/9838) merged at `cc26e2d900c741a065b79a4f81d648e0b8fd0d5c`, and bead `rf2-oyr9f` is closed. I verified ancestry, read the revised dependencies, viewer-scope transition, portable auth forms and tests, and preserved the [six current pages](evidence/sibling-review/round-5/tutorial-source-receipt.json). These are the only changed files among the original 160 source pins.

The current Part 2 dependency block now loads managed HTTP in a detached consumer, with only local-root prefixes substituted: [new probe, exit 0](evidence/tutorial-current-re-frame2-1.log). The repair author also reports a compiled consumer, 25/25 browser checks covering two viewers and cold restore, and six JVM tests with eleven assertions. That is attributed developer-run evidence, not a new Astra browser run or a novice study. [PR body and limits](evidence/sibling-review/round-5/tutorial-pr.json).

Accordingly, the headline, R01/R10, evaluation protocol and recommendations no longer present the four old tutorial defects as current blockers. The next task is observing unassisted authoring and diagnosis on the repaired path. Some settings, registration and editor material remains incomplete, and the local backend route was not exercised in that repair; those limits do not reopen the completed bead wholesale.

The Xray boot failure discovered during that walkthrough is also repaired: [PR #9839](https://github.com/day8/re-frame2/pull/9839), bead `rf2-izhgo`, commit `d240f7adbd449c94853ef7ff1ac7c03ff6e2a644`. The collector now recognizes Xray's own frameless subscription traces before counting sensitive suppression. Source and ancestry were checked; the [closure record](evidence/sibling-review/round-5/xray-bead.json) attributes the browser regression checks to that worker. This is evidence that an attached dev build can be exercised, not proof of the comparative causal-diagnosis task. The standard Conduit build's preload configuration is unchanged.

## A policy distinction verified this round

Fable retained an uncertain claim about the migration scorecard's GC wording. Source review and two new controlled probes establish a precise difference:

| Same configured interval: 1,000 ms | Resources | TanStack Query 5.102.8 |
|---|---|---|
| While owned | Settlement arms a check; an owned check re-arms | Adding the observer cancels pending collection |
| Final owner leaves at logical time 1,990 | Existing check remains due at 2,000 | A new timer becomes due at 2,990 |
| Collection in the probe | 2,000: 10 ms after release | 2,990: 1,000 ms after release |

[Resources log](evidence/gc-review-re-frame2-1.log), [TanStack result](evidence/gc-tanstack-review.json). Both passed their assertions. These use captured timer requests or a controlled timeout provider, not wall-clock sleeps. They do not measure real browser scheduling or memory use.

The shipped scorecard's claim that the defaults match is therefore too strong. Our own “five-minute inactive retention” wording was also imprecise and is corrected to a GC interval. The practical question is whether returning soon after leaving should reliably reuse data. Teach the current behavior and test that navigation experience before changing runtime policy; do not turn a different retention contract into a claim that GC is unimplemented.

## Where the siblings now agree

Fable withdrew the mistaken public `clear-resource` export claim, the phantom aborted-event reference and the `:sensitive?` tutorial claim. It now separates data-dependent reads from dispatch ordering, identifies the tutorial's original global-scope error, accepts a dev-only Xray launch, and uses existing optimistic-reconciliation traces rather than proposing another runtime field. These are useful corrections and stronger agreement on facts.

The final revisions resolve several further disagreements. Fable acknowledges comparator isolation boundaries, says the two-versus-three-refetch count was a recipe artifact, withdraws the proposed `handler-meta` arity after exercising its existing map argument, and allows read-API simplification when a real task earns it. Grok also rejects the blanket boundary claim and changes dependent reads from BEHIND to a qualified DIVERGENT/TARGET judgment. These corrections supersede our earlier criticisms; repeating those criticisms as current would now misrepresent the reports. [Fable's responses](evidence/sibling-review/round-6/fable/sibling-review.md), [Grok's synthesis](evidence/sibling-review/round-6/grok/intermediate/sibling-synthesis.md).

Fable's WIN labels now describe structural differences, not measured productivity. That is clearer, but an alternative implementation can serve the same job without sharing Resources' mechanism. Our verdict remains an advantage hypothesis: measure the coordination work across every affected view, including scope/replan setup and recovery. Agreement on mechanisms and restrained investment is stronger than agreement on relative ergonomics; cross-reading is also synthesis, not three additional independent replications.

Both siblings now recognize the tutorial repair. Fable's claim that nobody has walked the repaired consumer path overlooks the repair author's recorded browser/JVM walkthrough; Grok more carefully says it has not rerun that consumer itself. Neither the walkthrough nor the repaired Xray boot establishes the unassisted authoring or comparative diagnosis task. A standard build without preloads does not rule out a development launch: the Xray repair was reproduced through a command-line configuration merge.

Dependent-read disagreement is partly terminology and partly missing evidence. A continuation can express another request; the editor example only joins a read to seed a draft. A complete A-result-to-B-request recipe, including changed-A and stale-continuation behavior, has not been compared here. Calling the feature possible does not settle its ergonomic cost, and it does not justify a new planner by itself.

The latest review also resolves the proposed request-ledger helper into documentation. Source confirms `with-request-stubs` uses the public `rf/with-fx-overrides` seam. A recording fixture can use that seam, but it must preserve request/reply middleware when testing those guarantees; merely replacing managed HTTP with an atom append does not do so. The evaluation plan now names the supported route explicitly. The migration recipe likewise keeps per-registration freshness separate from frame-level revalidation.

The revised corpus keeps the investment focused: use the repaired tutorial, compare one feature extension, make one existing-tool diagnosis excellent, and correct the remaining migration-summary claims. Persistence and live-data jobs remain visible gaps whose implementation should be driven by a concrete consumer need.
