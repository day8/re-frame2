# Machine Helper Surfaces

Status: draft finding.

## Crowding Signal

Machine reads are available both as ordinary subscription vectors and as helper
functions. The helpers are convenient, but they compete with the data-shaped
subscription language that tools, derived subs, and non-Reagent substrates
already use.

Current similar spellings:

- `@(rf/sub-machine :auth/login)`
- `@(rf/subscribe [:rf/machine :auth/login])`
- `(rf/machine-has-tag? :auth/login :auth/busy)`
- `@(rf/subscribe [:rf/machine-has-tag? :auth/login :auth/busy])`
- `:<- [:rf/machine :auth/login]` in derived subs
- adapter hooks like `(uix-adapter/use-subscribe [:rf/machine-has-tag? ...])`

Implementation evidence:

- `implementation/core/src/re_frame/core_machines.cljc:217-245` says
  `sub-machine` and `machine-has-tag?` are sugar over subscription vectors.
- `implementation/core/src/re_frame/core.cljc:1561-1580` re-exports the helper
  functions from `re-frame.core`.
- `spec/005-StateMachines.md:3660-3719` documents both helper and subscription
  vector surfaces as equivalent.

## Observed Use Cases

1. Reagent examples often use `(rf/machine-has-tag? ...)` directly in views,
   for example `examples/reagent/realworld/articles.cljs:481-494`.

2. Derived subscriptions use data vectors, for example
   `examples/reagent/nine_states/core.cljs:499-504` and
   `examples/reagent/realworld/articles.cljs:350-379`.

3. UIx and Helix examples use adapter hooks over data query vectors, for
   example `examples/uix/login_uix/core.cljs:298-338` and
   `examples/helix/login_helix/core.cljs:327-370`.

4. Tests and construction prompts assert against
   `@(rf/subscribe [:rf/machine ...] {:frame f})`.

5. Tools need machine selectors as data so derivation graph tooling can inspect
   the query vector without executing helper functions.

6. Machine actions need cross-actor messaging. The current surface exposes both
   the `dispatch-to-system` helper and the `:rf.machine/dispatch-to-system` fx
   id.

## Proposed Cleanup

Teach subscription vectors as the canonical read API:

```clojure
(rf/subscribe [:rf/machine :auth/login])
(rf/subscribe [:rf/machine-has-tag? :auth/login :auth/busy])
```

Use helper functions only as optional compatibility/convenience wrappers. Do
not present them as coequal front-door forms in new docs, examples, or tests.

For machine action messaging, make effect data the canonical surface:

```clojure
{:fx [[:rf.machine/dispatch-to-system
       [:child/system-id [:child/event payload]]]]}
```

If `dispatch-to-system` remains, document it as a thin constructor for that
effect tuple, not as a separate operation family.

## Why This Is Better

Machine state is part of the same derivation lattice as subscriptions, flows,
routes, and resources. Query vectors keep that fact visible. They are data, so
they can be recorded, displayed, analyzed, compared, and generated.

Clojure APIs become durable when values carry the meaning. Wrapper functions
can be pleasant, but they should not hide the one algebra all tooling depends
on.

## Fresh consolidation pass note (2026-06-18)

The fresh empirical lens reconfirms this file's ruling: `machine-has-tag?` = 64
example hits (keep), `sub-machine` = 0 anywhere (drop or move to re-frame.machines),
`dispatch-to-system` = 1 test, `defmachine` tests-only. One cross-lens conflict,
resolved here: the minimalist lens proposed moving all three helpers including
`machine-has-tag?` off the facade - overruled by adoption; `machine-has-tag?`
stays, and the minimalist recommendation holds only for `sub-machine`.

## Implementation

- **Vehicle: docs-first beads + one small facade-pruning bead.** No EP.
- Beads: (1) docs - teach `[:rf/machine ...]` vectors and the
  `:rf.machine/dispatch-to-system` effect tuple as canonical; label helpers as
  Reagent convenience; (2) drop `sub-machine` from the facade (0 callers);
  (3) optional - demote `dispatch-to-system` / `defmachine` to `re-frame.machines`.
  KEEP `machine-has-tag?`.
- Low risk; independent of the frame-grammar EP.
