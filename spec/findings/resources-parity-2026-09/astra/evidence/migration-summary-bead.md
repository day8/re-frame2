Correct three concrete statements in docs/resources/coming-from-tanstack-query.md. This is a documentation correction on one surface; preserve the existing runtime and the public API reference.

1. Line 182 describes :gc-after-ms as running after the last owner is released and says the default matches TanStack gcTime. The values match, but the retention guarantees do not. Resources arms GC on settlement, re-arms when a check finds an owner or in-flight work, and leaves that check armed on owner release. A newly owner-free idle entry can therefore be collected at the next pending check without receiving a full new interval. TanStack Query 5.102.8 starts a fresh gcTime timer when its final observer leaves.

   Independently executed controlled-scheduler comparison: both policies 1000 ms; Resources settle at t=0, owned check at t=1000 re-arms t=2000, owner leaves t=1990, collection t=2000. TanStack observer leaves t=1990, collection t=2990 (entry still present at 2989). The probe captures scheduling/delivers due events, not real browser timing. Sources: implementation/resources/src/re_frame/resources/events.cljc release-owner-from-identities and gc-fired-handler; existing resources_invalidation_gc_cljs_test.cljc; TanStack query-core query.ts removeObserver and removable.ts scheduleGc.

2. Line 194 says :keep-previous? is on the route/resource. State the supported route-entry/ensure projection policy without suggesting a reg-resource metadata key.

3. Line 220 says the registration functions have their clear-* counterparts. The public facade uses rf/clear with the registrar kind and id. Internal re-frame.core-resources wrappers are not re-frame.core exports. The API reference already documents the correct grammar; do not add aliases or rewrite it to match the stale summary.

Acceptance: the three rows describe current behavior and public spellings. Keep GC and previous-data capability credited as implemented; qualify comparison semantics rather than demoting Landed. Verify links/Markdown through the applicable docs checks. A new runtime policy or a runtime test that simply mirrors this wording is not requested.

Evidence: ai/findings/Resources/astra/review-round-2.md; evidence/gc-review-re-frame2-1.log and gc-tanstack-review.json (both probes passed); evidence/facade-portability-re-frame2-1.log; source pin f2f9ffd654b70da97e907758cc73daa3bd205d57. The research scripts and receipts are ignored local artifacts, with the reproducing sequence stated above for portability. This is separate from the completed tutorial repair rf2-oyr9f / PR #9838.
