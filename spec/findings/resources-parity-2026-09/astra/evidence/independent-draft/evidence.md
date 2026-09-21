# Resources research evidence

The [baseline](evidence/baseline.json) records the research date, repository revision, Node version and prompt hash. [Local source manifest](evidence/local-source-manifest.json) preserves hashes for 160 files and corresponding copies under `evidence/local-source/`. The source copies pin the quoted implementation rather than relying on mutable line numbers. [Upstream versions](evidence/upstream-versions.json) records registry version/integrity metadata; the TanStack fixture also has its own package lock.

## Executed checks

| Check | Result | Receipt and interpretation |
|---|---|---|
| Resources JVM module | Exit 0; 872 tests / 7,763 assertions | [Log](evidence/resources-jvm-re-frame2-1.log). Executed from the absolute Resources module directory. Includes runtime, mutation, scope, route, infinite, replay and projection suites; CLJS-only branches are not implied. |
| Independent CLJS build | Exit 0; Conduit 324 files / 0 warnings; research tests 313 files / 4 inference warnings | [Build log](evidence/cljs-build-re-frame2-1.log), [config generator](evidence/prepare-build.clj). Separate output/cache; absolute repository source paths; no production-source changes. |
| Selected Conduit/Xray CLJS suites | Exit 0; 79 tests / 724 assertions | [Log](evidence/conduit-cljs-re-frame2-2.log), [runner](evidence/cljs/resources_research/runner.cljs). Node integration tests, not rendered Xray UI. |
| Conduit browser and controlled cases | Exit 0 on final attempt 9 | [Log](evidence/conduit-browser-re-frame2-9.log), [smoke](evidence/conduit-browser-smoke.json), [controlled results](evidence/conduit-controlled-cases.json), [browser runner](evidence/browser-probe.cjs), [case implementation](evidence/conduit-cases.cjs). Fresh browser, actual Conduit source and shared demo backend. |
| Tutorial literal dependency probe | Exit 1; missing managed HTTP namespace | [Log](evidence/consumer-literal-re-frame2-1.log), [dependency set](evidence/consumer/literal-deps.edn), [probe](evidence/consumer/probe.clj). Paths relocated to this checkout; dependency content retained. |
| Tutorial dependency repair | Exit 0; namespace loads | [Log](evidence/consumer-repaired-re-frame2-1.log), [repaired deps](evidence/consumer/repaired-deps.edn). Adds only the missing HTTP artifact. Does not certify the complete tutorial UI. |
| TanStack installation/build | Exit 0; 11 packages installed; browser bundle built | [Install](evidence/tanstack-install-re-frame2-1.log), [build](evidence/tanstack-build-re-frame2-1.log), [TSX source](evidence/tanstack/app.tsx). Existing machine caches/network; no onboarding-speed benchmark or TypeScript typecheck claim. |
| TanStack core experiments | Exit 0; eight case families | [Log](evidence/tanstack-core-re-frame2-1.log), [results](evidence/tanstack-core-cases.json), [script](evidence/tanstack/core-cases.mjs). Dedupe fault controls, freshness policies, refresh errors, scoped keys, retained observer, serialized hydration, bounded infinite pages and mutation serialization. |
| TanStack React/Devtools browser | Exit 0 on final attempt 2 | [Log](evidence/tanstack-browser-re-frame2-2.log), [smoke](evidence/tanstack-browser-smoke.json), [membership controls](evidence/tanstack-membership-cases.json). Two detail observers share one initial request; list membership follows the accepted mutation when invalidation is present. |

Browser screenshots: [Conduit](evidence/resources-browser.png), [TanStack](evidence/tanstack-browser.png). The research host uses a minimal HTML shell and does not load Conduit's external styling. These images establish rendered application state, not visual-design quality. The TanStack fixture is a compact representative slice, not a complete RealWorld implementation or an equivalent app-size measurement.

The Resources controlled ledger has 29 requests across several scenarios. That total is not compared with the smaller TanStack fixture's total; only matched assertions are compared. Backend transitions and reply delivery are separated. A server-state marker is synthetic; none of these cases proves actual authentication enforcement, socket cancellation or durable offline recovery.

## Controls and investigation corrections

The missing-invalidation membership oracle produced fail/pass/fail in both browser fixtures. The TanStack dedupe oracle separately produced fail/pass/fail by adding/removing a spurious key segment. The current Resources implementation passed the newer-success/older-rejection case and excluded a held old-viewer representation after identity change plus route replan.

Initial research failures are retained with their distinct attempt numbers. They include a missing Node module search path, a wrong DOM test ID, using `compute-sub` with frame options instead of its required frame-state value, and an incomplete synthetic User rejected by the example's schema. These were fixture mistakes, not library defects. A standalone identity-storage call also omitted route replanning; correcting the scenario to perform the full identity-change cause resolved the resulting idle-view error. Unresolved scopes intentionally fail closed loudly; the attempted raw sub was not evidence of a usable cold-boot UI.

Only the final successful scripts/receipts support the corresponding browser result. Successful earlier narrow cases are not used to conceal failed expanded cases. Every saved gate log and exit file has a distinct attempt number. Build warnings remain in the log. No repository-wide test suite was run or claimed.

## Tooling and evidence limits

Pair's `discover_app` returned `{:ok? false, :reason :nrepl-unreachable, :build :app}`. It identified a stale/unreachable configured nREPL connection. Shared services were not restarted. Public source/registry inspection was used locally, while full Pair operations and an independently timed Xray-versus-Devtools diagnosis remain unexecuted.

The assistant walkthrough located the mutation's declarations, inspected metadata, altered an isolated application consequence and verified the repair. It exposed real recovery steps but is not an independent model evaluation. Story variants, complete replay-to-regression promotion, generated API clients and editor inference productivity were not exercised.

A complete first-session tutorial, production-like network cancellation, focus/reconnect browser transitions, cold-browser token restoration, bidirectional infinite UI and streamed SSR hydration remain specified follow-up experiments. Relevant unit/module tests do not erase those boundaries.

## Primary sources and versions

[Initial source receipts](evidence/primary/sources.json) and [supplemental receipts](evidence/primary/supplement-sources.json) retain URLs, retrieval times and hashes. Some web-tool fetches failed; official Markdown/source copies supplied the content. The initial `tq-streamed` response was an invalid 19-byte page and is not supporting evidence; the corrected `experimental_streamedQuery` page is preserved separately.

| Publisher | Sources used | Status / scope |
|---|---|---|
| TanStack Query | [Defaults](https://tanstack.com/query/latest/docs/framework/react/guides/important-defaults), [optimistic updates](https://tanstack.com/query/latest/docs/framework/react/guides/optimistic-updates), [mutations](https://tanstack.com/query/latest/docs/framework/react/guides/mutations), [query options](https://tanstack.com/query/latest/docs/framework/react/guides/query-options), [cancellation](https://tanstack.com/query/latest/docs/framework/react/guides/query-cancellation), [prefetching](https://tanstack.com/query/latest/docs/framework/react/guides/prefetching), [SSR](https://tanstack.com/query/latest/docs/framework/react/guides/ssr), [network modes](https://tanstack.com/query/latest/docs/framework/react/guides/network-mode), [infinite queries](https://tanstack.com/query/latest/docs/framework/react/guides/infinite-queries), [persistence](https://tanstack.com/query/latest/docs/framework/react/plugins/persistQueryClient), [broadcast](https://tanstack.com/query/latest/docs/framework/react/plugins/broadcastQueryClient) | v5 moving docs; executed behavior pinned to 5.102.8; broadcast/streamed APIs marked experimental where documented |
| Redux Toolkit | [Tags](https://redux-toolkit.js.org/rtk-query/usage/automated-refetching), [optimistic cache updates](https://redux-toolkit.js.org/rtk-query/usage/manual-cache-updates), [streaming](https://redux-toolkit.js.org/rtk-query/usage/streaming-updates), [codegen](https://redux-toolkit.js.org/rtk-query/usage/code-generation), [infinite queries](https://redux-toolkit.js.org/rtk-query/usage/infinite-queries) | 2.12.0 metadata; docs/source inspected, not executed |
| Vercel SWR | [Mutation](https://github.com/vercel/swr-site/blob/main/content/docs/mutation.mdx), [provider/cache](https://github.com/vercel/swr-site/blob/main/content/docs/advanced/cache.mdx), [subscription](https://github.com/vercel/swr-site/blob/main/content/docs/subscription.mdx) | 2.5.1 metadata; official repository content used after site and old source paths failed |
| Apollo | [Cache model](https://www.apollographql.com/docs/react/caching/overview), [cache interaction](https://www.apollographql.com/docs/react/caching/cache-interaction) | 4.3.0 metadata; normalization and membership distinctions; not executed |
| Riverpod | [Disposal](https://riverpod.dev/docs/concepts2/auto_dispose), [offline persistence](https://riverpod.dev/docs/concepts2/offline), [mutations](https://riverpod.dev/docs/concepts2/mutations) | 3.4.3 release receipt; persistence and mutation APIs experimental |
| TanStack DB | [Repository/status](https://github.com/TanStack/db), [overview](https://tanstack.com/db/latest/docs/overview), [Query collection](https://tanstack.com/db/latest/docs/collections/query-collection) | 0.9.0 metadata; beta, separate packages and synchronization costs; not executed |
| RealWorld | [Official implementation index](https://github.com/realworld-apps/realworld#implementations) | Establishes available app ecosystem; no reviewed community TanStack frontend was selected as representative. A controlled compact counterpart was used. |

Relevant bead history is retained in [Resources bead search](evidence/resources-beads-all.json). Closed scope/API simplifications were read as implemented decisions, not reopened from old descriptions. Subsequent corpus revisions must preserve this independent evidence baseline.
