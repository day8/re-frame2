# Errors and warnings

Every resources error fails closed: a missing scope policy, a malformed request, or a
scope that can't be resolved raises rather than falling back to a shared cache entry.
Each error's `ex-data` carries its id under `:rf.error/id`, a `:reason` naming the
fix, and the failing surface under `:where`. The tables below group the ids by when
they fire. [Testing resources](testing.md) turns the same failures into assertions,
and the [API reference](../api/re-frame.resources.md) lists each command's errors in
place.

Some problems raise nothing — a permanent skeleton, an invalidation that refreshes
nothing. [Troubleshooting](concepts.md#troubleshooting) in the model covers those.

## At registration

| Error | Cause | Fix |
|---|---|---|
| `:rf.error/resources-artefact-missing` | Forgot the require | `(:require [re-frame.resources])` at boot |
| `:rf.error/resource-missing-scope-policy` | `:scope` omitted, or not one of the two shapes | Declare `:scope` as `:rf.scope/global` or `{:from-db <id>}` |
| `:rf.error/resource-bad-spec` | A malformed spec: the request fn in the metadata, no `:params-schema`, a bad `:gc-after-ms`, `:stale-after-ms` or `:infinite`, … | The `:reason` names the key |
| `:rf.error/infinite-missing-next-page-param` | `:infinite true` without `:next-page-param` | [Paginate a feed](how-to/paginate-a-feed.md) |
| `:rf.error/invalid-resource-scope-spec` | A malformed `reg-resource-scope`: `:inputs` not `{name [:db path]}`, or no resolver fn in the third slot | Fix the resolver registration |
| `:rf.error/resource-scope-source-reserved` | A `reg-resource-scope` input of `[:runtime …]`, a reserved source | Read the input from app-db with `[:db path]` |
| `:rf.error/mutation-bad-spec` | A malformed `reg-mutation`, including an unknown `:invalidate-timing` or `:on-conflict` | The `:reason` names the key |
| `:rf.error/mutation-optimistic-before-request` | An optimistic plan with `:invalidate-timing :before-request` | [Pick one](how-to/invalidate-after-a-mutation.md#when-the-invalidation-fires-invalidate-timing) |

## Causing and reading

| Error | Cause | Fix |
|---|---|---|
| `:rf.error/resource-not-registered` | An ensure, refetch or subscription names an unregistered id | Require the namespace that registers it |
| `:rf.error/resource-invalid-params` | Params don't conform to `:params-schema` — checked once the [schemas](../core/how-to/validate-with-schemas.md) artefact is loaded | Fix the params; the error redacts classified values |
| `:rf.error/resource-non-edn-params` | A resource's or mutation's params carry a float, a ratio, a function or another host value, none of which can be part of a cache key | Pass it as a string or an integer, or leave it out |
| `:rf.error/resource-reserved-request-key` | The request fn returned `:request-id`, `:on-success` or `:on-failure` | Drop them; the runtime routes the reply |
| `:rf.error/resource-unknown-transport` | A `:transport` other than `:rf.http/managed`. The spec registers; the first load or write raises | Omit `:transport`, or use `:rf.http/managed` |
| `:rf.error/http-artefact-missing` | A load or write without `re-frame.http.managed` | `(:require [re-frame.http.managed])` at boot |
| `:rf.error/reply-invalid-target` | An ensure's or execute's `:reply-to` is not a non-empty vector with a keyword head | Pass an event vector, such as `[:todo/loaded]` |
| `:rf.error/reply-non-data-target` | A `:reply-to` carries a fn or another host object | Pass data only; the reply is appended to the vector |
| `:rf.error/resource-sub-unresolved-scope` | A subscription's scope resolver returned `nil` | Resolve only when logged in, or don't subscribe |
| `:rf.error/resource-scope-unresolved-reference` | An ensure's, execute's or `invalidate-tags`' `{:from-db …}` scope resolved to `nil` | Dispatch it once the resolver's inputs are in app-db |
| `:rf.error/resource-scope-not-registered` | A `{:from-db id}` names a resolver nothing registered | Register it with `reg-resource-scope` |
| `:rf.error/resource-invalid-scope` | A misspelled `:rf.scope/*` keyword, `[:rf.scope/global]` in a vector, or a `{:from-db …}` where a concrete scope is required (`clear-scope`) | Use the bare keyword; resolve with `rf/resolve-resource-scope` first |
| `:rf.error/resource-route-blocking` (on `:rf.route/error`) | A blocking read's first load failed; its failure is under `:error` | Retry with `:rf.resource/refetch`; the route returns to `:idle` when it loads |
| `:rf.error/resource-route-plan` (on `:rf.route/error`) | A route entry's `:params`, `:scope` or `:when` threw or returned nothing usable | Fix the entry; the original error data is under `:cause` |
| `:rf.error/resource-ssr-blocking-timeout` | Under SSR, a blocking resource did not settle within the render deadline. It is reported, not thrown, and the resource settles as a first-load failure | Fix the fetch, or render the resource's error state |
| `:rf.error/no-frame-context` | `resource-state` or `mutation-state` without `:frame` | Pass `:frame` |
| `:rf.error/infinite-missing-page-accessor` | A feed's pages aren't vectors and it declares no `:page->items` | [Paginate a feed](how-to/paginate-a-feed.md) |

## Writing

| Error | Cause | Fix |
|---|---|---|
| `:rf.error/mutation-not-registered` | An execute names an unregistered mutation | Require the namespace that registers it |
| `:rf.error/mutation-invalid-params` | Params don't conform to the mutation's `:params-schema` — checked once the schemas artefact is loaded | Fix the params |
| `:rf.error/mutation-non-serializable-instance-id` | `:instance` isn't EDN | A keyword, string or vector of them |
| `:rf.error/mutation-invalid-invalidation` | `:invalidates` returned neither a tag set nor a descriptor vector | Return `#{tag …}` or `[{:scope … :tags …} …]` |
| `:rf.error/mutation-invalid-target` | A `:patches`, `:populates`, `:removes` or `:optimistic` target's `:params` can't be part of a cache key | Fix the target's params |
| `:rf.error/resource-invalidate-scope-required` | A direct `:rf.resource/invalidate-tags` with no `:scope` | Name the scope, or opt into `:cross-scope? true` |
| `:rf.error/resource-cross-scope-cause-required` / `:rf.error/resource-cross-scope-scope-conflict` | A `:cross-scope? true` invalidation without `:cause`, or with a `:scope` | Add `:cause`; drop `:scope` |

## Warnings

Warnings don't stop the event. They ride the trace stream, so Xray shows them in dev,
and production builds compile them out.

| Warning | Meaning |
|---|---|
| `:rf.warning/mutation-scope-mismatch` | A descriptor matched nothing in its scope, but its tags match in another ([the scope footgun](how-to/invalidate-after-a-mutation.md#the-scope-footgun-and-how-to-disarm-it)) |
| `:rf.warning/mutation-target-skipped` | A post-write target named an unregistered resource or wasn't a map; the rest still applied |
| `:rf.warning/optimistic-tags-descriptor-skipped` | A malformed `:optimistic-tags` descriptor was skipped |
| `:rf.warning/optimistic-force-clobber` | `:on-conflict :force` restored a snapshot over newer data |
| `:rf.warning/resource-load-more-owner-ignored` | A load-more carried an `:owner`, which it never takes |
