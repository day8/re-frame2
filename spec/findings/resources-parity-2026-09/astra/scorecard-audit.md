# Published Resources scorecard crosswalk

The [preserved migration page](evidence/local-source/docs/resources/coming-from-tanstack-query.md) has 22 dimension rows: 17 Landed, three Different by design, one Out of scope and one Deferred. A Landed row means implemented and covered by relevant tests, not that every whole-application variation or comparative ergonomic benefit has been demonstrated.

The test files below are under the preserved `implementation/resources/test/re_frame/` unless another location is specified. The entire Resources JVM module was executed. CLJS-only/browser branches are not implied by that result. Selected Conduit/Xray suites were separately compiled and executed. [Run receipts](evidence.md).

| Published dimension | Jobs | Supporting test or evidence | Scope of support / correction |
|---|---|---|---|
| Keyed cache — Landed | R02 | resources_cache_key_identity_cljs_test.cljc; resources_runtime_cljs_test.cljc | Canonical key identity and runtime sharing; browser request ledger also confirms dedupe. |
| Cache home — Different | R02, R10 | state.cljc cache paths; frame isolation tests | Per-frame partition is real. Describing TanStack clients as necessarily module-global is inaccurate. |
| Staleness — Landed | R04 | resources_runtime_cljs_test.cljc; resources_revalidation_cljs_test.cljc | Explicit/default freshness and active-stale policy. Native defaults differ from Conduit's one-minute configuration. |
| Request deduplication — Landed | R02 | resources_runtime_cljs_test.cljc; work-ledger tests; controlled browser case | One in-flight key joins work; observed two owners/one request. |
| Fresh-skip — Landed | R02 | resources_runtime_cljs_test.cljc; controlled browser case | Browser third owner caused zero extra requests for a fresh entry. |
| Garbage collection — Landed | R03 | resources_invalidation_gc_cljs_test.cljc; resources_scoped_owner_lifecycle_cljs_test.cljc | Owner release and collection policy, not a multi-hour memory benchmark. |
| Scope boundary — Different | R10 | resources_scope_leak_boundary_cljs_test.cljc; resources_from_db_scope_cljs_test.cljc | Explicit scope, wrong scope and unresolved failure are pinned. A programmer can still explicitly declare the wrong policy. |
| Invalidation — Landed | R06 | resources_invalidation_descriptors_cljs_test.cljc; controlled membership experiment | Scoped descriptors and application-level membership test. RTK has declarative invalidation too. |
| Mutations — Landed | R06 | resources_mutation_cljs_test.cljc | Instance lifecycle and request/reply processing. No implication of durable offline writes. |
| Patch/populate/seed — Landed | R06 | resources_populate_exact_target_cljs_test.cljc; resources_mutation_cljs_test.cljc; Conduit integration suite | Reply-driven cache changes, plus application membership reconciliation. |
| Completion continuation — Landed | R06 | resources_reply_lowering_cljs_test.cljc; Conduit editor/settings integration tests | Accepted completion and workflow addressing. Session ownership of application continuations remains a separate question. |
| Projection/select — Different | R14 | resources_runtime_cljs_test.cljc; subscription model | Ordinary subscription projection is implemented. No comparative render-work benchmark was run. |
| Optimistic rollback — Landed | R07 | resources_optimistic_apply_cljs_test.cljc; resources_optimistic_settle_cljs_test.cljc; browser overlap | Revision-aware settlement exercised, including newer success/older rejection. Competitors have alternatives to naive snapshot rollback. |
| Polling — Landed | R05 | resources_polling_cljs_test.cljc; resources_timer_rearm_cljs_test.cljc | Owned/visible policy and timer state; not a browser background-throttling study. |
| Focus/reconnect — Landed | R05 | resources_revalidation_cljs_test.cljc | Active-stale scans and frame lifecycle declarations; browser host transitions not compared here. |
| Prefetch/route plan — Landed | R03 | resources_route_prefetch_cljs_test.cljc; resources_route_plan_recovery_cljs_test.cljc | Route-plan warming, ownership and recovery. No literal hover-to-navigation browser benchmark. |
| Infinite/load-more — Landed | R09 | resources_infinite_load_more_cljs_test.cljc; resources_infinite_subs_cljs_test.cljc | Next-page and retained-page model. Bidirectional feeds and retention ceilings require separate assessment. |
| Keep previous while paging — Landed | R08 | resources_route_cljs_test.cljc; resources_route_replan_cljs_test.cljc | Route/read projection support. A `previous-data` value is not proof of fresh current-page data. |
| SSR/hydration — Landed | R11 | resources_ssr_cljs_test.cljc; resources_ssr_projected_key_refetch_cljs_test.cljc; resources_infinite_ssr_restore_cljs_test.cljc | Projection/hydration hooks and serialized-key boundaries are tested. Complete streamed browser hydration remains unexecuted. |
| Devtools — Landed | R15 | tools/xray resource helpers/tests; selected 79-test CLJS run | Substantial implementation and helper coverage. Full interactive causal-diagnosis workflow was not executed. |
| Normalized/GraphQL — Out | R13 | Spec 016 deferred/refusal sections | Deliberate scope decision; still a gap for applications needing those outcomes. |
| Offline/cross-tab — Deferred | R12, R13 | Spec 016 deferred slices | No supported equivalent established. TanStack's persistence and experimental broadcast must be distinguished. |

All 17 Landed dimensions have identifiable relevant tests. This audit does **not** certify every subclaim within those broad rows. In particular, production wire cancellation, full browser hydration and human tool ergonomics cannot be inferred from module tests or helper coverage.

## Material documentation corrections

- The migration page's registration-summary row still says “their `clear-*`”; the public API reference correctly teaches `rf/clear`. A [runtime namespace probe](evidence/facade-portability-re-frame2-1.log) confirms `re-frame.core/clear`, `resource-state` and `mutation-state` exist; `clear-resource`, `clear-mutation`, `resource-meta` and `mutation-meta` do not. Internal wrappers are not facade exports. Correct that migration summary without rewriting the already-correct API reference or restoring duplicate APIs.
- The GC row's “default matches” overstates equality: matching `300000` values do not provide the same retention after the last owner leaves. New controlled [Resources](evidence/gc-review-re-frame2-1.log) and [TanStack](evidence/gc-tanstack-review.json) probes show the difference. Resources retains its pending eligibility check; TanStack starts a fresh inactivity interval. GC is implemented, so keep Landed while correcting the policy comparison.
- Cache scope in Resources is explicit and auditable; it does not make an erroneous global declaration impossible. Avoid an unconditional cross-viewer safety claim.
- TanStack supports request-specific clients and prefetching outside component render; SWR supports cache providers. These are ordinary documented workflows, not exceptional workarounds.
- RTK tag invalidation and SWR's mutation/race handling should receive full credit. Automatic consequences and safe optimistic strategies are not unique inventions of Resources.
- Infinite-feed continuity, freshness and retention are separate properties. Describe the page-zero refresh default as preserving the tail, without implying that the tail was refreshed.

Primary comparison sources: [TanStack SSR](https://tanstack.com/query/latest/docs/framework/react/guides/ssr), [prefetching](https://tanstack.com/query/latest/docs/framework/react/guides/prefetching), [SWR provider](https://github.com/vercel/swr-site/blob/main/content/docs/advanced/cache.mdx), [RTK tags](https://redux-toolkit.js.org/rtk-query/usage/automated-refetching), [SWR mutations](https://github.com/vercel/swr-site/blob/main/content/docs/mutation.mdx).

The [sibling review](sibling-review.md) independently corroborated 17/17 test pins. That supports the existing implementation label; it does not turn every browser or productivity claim green. Conversely, missing Conduit demonstrations do not make implemented infinite/SSR features merely specified. Route `:after` must be described as dispatch ordering, and `:keep-previous?` belongs to the route/ensure projection policy rather than being advertised as a resource-registration option.

On 15 September, Fable withdrew its erroneous public-API contradiction and phantom-token claims, split the dependent-read row and adopted a dev-only Xray launch. These are stronger factual agreement, not new independent runtime evidence. The tutorial repair is also now merged; its old dependency/scope/portability failures must no longer be used to score current onboarding as blocked. [Second review and repair verification](review-round-2.md).
