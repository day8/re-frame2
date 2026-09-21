# Evidence ledger

Distinguish **source observation**, **runtime/docs measurement**, **inference**. Pin `a5f883b5691b83ac258121eb5c046df87be6d03d`. 2026-09-14 +10:00.

## Clock

| Fact | Kind | Citation |
|---|---|---|
| HEAD `a5f883b569`, `main`, clean | source | `git rev-parse` / `git status --porcelain` at start |
| Timestamp 2026-09-14 22:41:07 +10:00 | measurement | `Get-Date` |
| HEAD at close `f680007c6b` | source | `git rev-parse` after Conduit stop |
| Window `a5f883b569..f680007c6b` is Story spec + beads, not Resources | source | `git diff --stat` |

## Competitors (docs, 2026-09-14)

| Fact | Kind | Citation |
|---|---|---|
| Query `staleTime` default 0; `gcTime` 5 min; silent retry 3× client | docs | https://tanstack.com/query/latest/docs/framework/react/guides/important-defaults |
| Query optimistic: UI `variables` **or** cache `onMutate` snapshot + `onError` restore | docs | https://tanstack.com/query/latest/docs/framework/react/guides/optimistic-updates |
| Query prefetch / router loaders; `prefetchQuery` deprecated | docs | https://tanstack.com/query/latest/docs/framework/react/guides/prefetching |
| Query SSR: never module-level QueryClient; HydrationBoundary | docs | https://tanstack.com/query/latest/docs/framework/react/guides/ssr |
| persistQueryClient official plugin; maxAge 24h | docs | persistQueryClient guide |
| Devtools official extra `@tanstack/react-query-devtools` | docs | Devtools guide |
| npm latest Query **5.102.8** (2026-08-27); Solid Query **6.0.0-rc.3** (2026-09-04) | docs | npmjs / GitHub tags |
| TanStack DB **0.9.0** (2026-09-10), BETA, separate package | docs | npm + https://tanstack.com/db/latest/docs/overview |
| RTK `providesTags` / `invalidatesTags` | docs | redux-toolkit automated-refetching |
| SWR `optimisticData` + `rollbackOnError` | docs | https://swr.vercel.app/docs/mutation |
| Apollo normalized `__typename:id` cache | docs | Apollo caching overview |
| RealWorld OpenAPI server `https://api.realworld.show/api` | docs | realworld-apps openapi.yml |

## In-repo product (source)

| Fact | Kind | Citation |
|---|---|---|
| 8 Conduit resources; 60s stale; 5 min GC; no `:infinite` | source | `examples/real-apps/realworld_resources/resources.cljs` 41–50, 110–295 |
| Viewer scope three-branch fail-closed | source | `scope.cljs` 107–119 |
| Favourite `:optimistic-tags` + scoped `:invalidates` + `:populates` + `:on-conflict :invalidate` | source | `mutations.cljs` 163–185 |
| Favorited-list membership needs `[:favorited-articles username]` in params | source | `mutations.cljs` 142–161 |
| Route `:resources`, home articles blocking, feed `:when` following | source | `routing.cljs` 65–109 |
| Conduit client-only; SSR demo is `resources_ssr` | source | `routing.cljs` 19–21 |
| Demo backend in-process, 20ms, one user, remembers writes | source | `realworld_shared/demo_backend.cljs` 44–51, 62–67 |
| How to run | source | README 199–205; `implementation/package.json` `dev:example` |
| Conduit `index.html` has no Xray host | source | `index.html` |
| Tutorial Part 1 offline; Part 2 `api.realworld.io` + global scope | source | `docs/resources/tutorial/01`, `02` |
| Translation Landed list + “every Landed pinned” | source | `coming-from-tanstack-query.md` 175–200 |
| 016 does NOT cover GraphQL, entity cache, `:select`, `:cache-key`, fetch-from-subscribe | source | `spec/016-Resources.md` 1877–1882 |
| Six conformance fixtures | source | `spec/conformance/fixtures/resources-*.edn` |
| `ensure-dedupes-in-flight` | source | `resources_runtime_cljs_test.cljc` 787–800 |
| HTTP sibling hand-rolled favourite rollback | source | `realworld_http/favorites.cljs` 133–203 |
| Story `force-fx-stub` | source | `tools/story/src/re_frame/story.cljc` |

## Runtime this pass

| Fact | Kind | Citation |
|---|---|---|
| Conduit live `http://127.0.0.1:8051/` | measurement | serve-example log; GET 200 |
| Home 10 articles, Hello Conduit | measurement | conduit-walk ledger; screenshot `01-home.png` |
| keep-previous observed on page 2 | measurement | ledger `keepingPreviousSeen: true` |
| Logged-out favourite → `/login` | measurement | ledger |
| Sign-in demo | measurement | ledger |
| Unfavourite Hello 1→0 with `optimistic` class | measurement | ledger |
| Comment appeared | measurement | ledger `n: 2` |
| Favourite article-2 0→1; detail primary; **onTab 1** | measurement | conduit-walk-favorite.cjs stdout |
| JVM scope-leak 9 tests / 38 assertions, 0 fail | measurement | clojure `-n` that ns |
| JVM optimistic-settle 14 / 124, 0 fail | measurement | same |
| JVM mismatch warning 6 / 20, 0 fail | measurement | same |
| JVM runtime 46 / 2635, 0 fail | measurement | same |

## Inferences (do not promote without a new measurement)

| Inference | Why it is not a finding yet |
|---|---|
| Post-logout Hello heart `active` is anonymous cache hit of pre-login seed | Consistent with 60s freshness + viewer-scoped invalidation + demo one-user flags. Not a proven leak. |
| Page-1 wait saw Article 10 | keep-previous; wait condition was wrong. |
| Dedupe holds in the browser | Only JVM/fixture; no request ledger. |
| Query would clobber contested optimistic | Docs of the cache recipe; no twin app. |

## What this run did not do

Sibling `astra/` / `fable/` reports unread. Story reports unread. No TanStack twin. No Xray UI. No 5xx refresh. No AbortController. No Alice/Bob. No red plant on a tracked fixture. No Clojars install of a fresh tutorial project.
)
