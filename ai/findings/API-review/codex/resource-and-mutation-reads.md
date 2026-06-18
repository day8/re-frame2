# Resource And Mutation Reads

Status: draft finding.

## Crowding Signal

Resources and mutations are a rich surface, and most of the richness is
justified. The crowded part is that registration metadata, runtime state, app
view-model reads, and lifecycle commands have similar names and can look like
competing read APIs.

Current adjacent surfaces:

- `reg-resource`, `clear-resource`, `resource-spec`;
- `reg-mutation`, `clear-mutation`, `mutation-spec`;
- causal event vectors such as `[:rf.resource/ensure ...]`,
  `[:rf.resource/refetch ...]`, `[:rf.resource/release-owner ...]`,
  `[:rf.mutation/execute ...]`;
- passive subscription vectors such as `[:rf.resource/state q]`,
  `[:rf.resource/data q]`, `[:rf.resource/infinite-state q]`,
  `[:rf.mutation/state q]`;
- direct functions `resources`, `resource-state`, `mutations`,
  `mutation-state`;
- registrar introspection through `(rf/registrations :resource)` and
  `(rf/handler-meta :mutation id)`;
- raw runtime-db reads in tests and tools.

Implementation evidence:

- `implementation/core/src/re_frame/core_resources.cljc:24-184` exports the
  registration and clear functions through late-bound wrappers.
- `implementation/resources/src/re_frame/resources.cljc:161-258` defines
  `resources`, `resource-state`, `mutations`, and `mutation-state`.
- `implementation/resources/src/re_frame/resources/subs.cljc` and
  `mutation_subs.cljc` define the passive app read families.
- `spec/016-Resources.md:561-576` explicitly distinguishes registration
  lifecycle `clear-resource` from runtime cache lifecycle events.
- `examples/reagent/resources/core.cljs:319-453` reads app state through
  `[:rf.resource/*]` subscriptions.
- `implementation/resources/test/re_frame/resources_infinite_subs_cljs_test.cljc`
  asserts the infinite read model through `[:rf.resource/*]` subscriptions.
- `tools/xray/src/day8/re_frame2_xray/panels/resources_helpers.cljc` combines
  registrar metadata and live runtime state for inspection.

## Observed Use Cases

1. App views read server state passively through `:rf.resource/*`
   subscriptions.

2. App events cause work through `:rf.resource/*` and `:rf.mutation/*` event
   vectors.

3. Mutations expose per-instance status to views.

4. Resource examples use route ownership, event ownership, manual refresh, and
   machine-owned resources.

5. Infinite-feed examples read combined view models and page projections.

6. Xray lists registered resources and joins them with live runtime entries.

7. Tests assert cache entries, work-ledger rows, stale suppression, optimistic
   rollback, and SSR restore behavior.

8. SSR preloads resources and serializes the durable runtime projection.

## Proposed Cleanup

Teach three lanes, with one spelling per lane:

1. Authoring lane:

```clojure
(rf/reg-resource :article/by-slug spec)
(rf/reg-mutation :article/save spec)
```

Registration metadata is inspected through the registrar query API.

2. Command lane:

```clojure
(rf/dispatch [:rf.resource/ensure payload])
(rf/dispatch [:rf.resource/refetch payload])
(rf/dispatch [:rf.mutation/execute payload])
```

These are causal events. They are not reads.

3. App read lane:

```clojure
(rf/subscribe [:rf.resource/state query])
(rf/subscribe [:rf.resource/data query])
(rf/subscribe [:rf.resource/infinite-state query])
(rf/subscribe [:rf.mutation/state query])
```

Direct functions like `resource-state` and `mutation-state` should be documented
as tool/test projections over runtime state, not as app UI alternatives to the
subscription vectors.

The naming sore spot is `clear-resource` / `clear-mutation`. They are
registration-lifecycle operations, while resources also have runtime lifecycle
events like `:rf.resource/clear-scope`, `:rf.resource/remove`, and mutation
instance clear/reset operations. A cleaner public spelling would reserve
"clear" for one domain. For example:

```clojure
(rf/unreg-resource :article/by-slug)
(rf/unreg-mutation :article/save)
```

or keep the existing names but move them to an advanced registrar-lifecycle
section and never present them beside cache commands.

## Why This Is Better

Resources are processes. Mutations are causal writes. Their app-facing reads
are derivations over framework-owned runtime state. When those three roles are
kept separate, the surface is understandable even though the machinery is
large.

The Clojure-friendly API is the one where commands are data vectors, reads are
data query vectors, and metadata inspection is a registrar read. A direct helper
function can exist for tooling, but it should not compete with the data-shaped
language that makes replay, inspection, and tests work.

## Implementation

- **Vehicle: docs-first beads, + one decision bead** for `clear-resource` /
  `clear-mutation` naming (`unreg-*` vs keep-and-relocate). No EP - the surface is
  large but mostly justified.
- Beads: (1) docs - the three lanes (authoring / command / app-read); label direct
  `resource-state` / `mutation-state` as tool/test projections; (2) decision -
  reserve "clear" for one domain.
- Independent of the frame-grammar EP.
