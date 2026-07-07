# Tags — per-state intent + the `:rf/machine-has-tag?` query

## When to load

Reach for this leaf when a view needs to ask "is the machine in any in-flight state?" or "is the form read-only?" — without naming the specific state-keyword. Tags are how a state declares its **intent** so views can query the intent, not the state name. Pairs with `regions.md` for the parallel-region tag-union story.

> **Mental model — think in xstate, map onto re-frame2.** re-frame2's `:tags` ARE xstate's state `tags` — this is a clean convergence, name and concept. If you know "any active state carrying the tag puts it in the active configuration's tag set" from xstate, that's exactly the rule here (the union runs along the active path for compound machines and across all regions for parallel ones). The re-frame2-specific binding to learn is the query side: `:rf/machine-has-tag?` is a **subscription**, so views deref `@(rf/subscribe [:rf/machine-has-tag? id tag])` rather than reading a tag list off an `ActorRef`.

## Canonical declaration

A state node carries a `:tags` slot whose value is a **set of keywords**. There is no separate registration call; the slot is just a key on the state node, processed by the runtime via `compute-tags` (`re-frame.machines.transition`):

```clojure
{:loading
 {:tags #{:data/loading :data/in-flight :data/transient}
  :on   {:fetch-succeeded {:target :loaded :action :set-items}
         :fetch-failed    :error}}

 :fetching
 {:tags #{:data/fetching :data/in-flight :data/loaded}
  :on   {:fetch-succeeded {:target :loaded :action :set-items}}}

 :loaded
 {:tags #{:data/loaded}
  :on   {:fetch-started :fetching}}}
```

Both `:loading` and `:fetching` carry `:data/in-flight` — the view consumes that one tag and doesn't have to disjoin two state-keywords.

## The snapshot's `:tags` slot

The runtime maintains a derived `:tags` slot on the snapshot — the **union** of every currently-active state's tag set:

- **Flat machine** — the single active state's `:tags` set.
- **Compound (hierarchical)** — the union along the path from root to active leaf.
- **Parallel-region machine** — the union across every active state in every region (`compute-tags-parallel` in `re-frame.machines.parallel`, which calls `compute-tags` per region).

If the union is empty the slot is **elided** entirely (snapshot-size optimisation, `commit-tags` in `re-frame.machines.transition`). The `:rf/machine-snapshot` schema marks `:tags` as `{:optional true}` — both presence (with non-empty set) and absence are valid.

## Querying — `:rf/machine-has-tag?`

```clojure
@(rf/subscribe [:rf/machine-has-tag? :realworld/tags :tags/in-flight])        ;; truthy iff the tag is in the union
@(rf/subscribe [:rf/machine-has-tag? :ui/nine-states :mode/read-only])
```

`:rf/machine-has-tag?` is the registered subscription (`re-frame.machines`) — read it with the ordinary `subscribe` naming the vector, `(subscribe [:rf/machine-has-tag? machine-id tag])`; there is no named-read-sugar fn layered over it (a runtime-db framework read is a subscription vector, one grammar). It reads `[:rf.runtime/machines :snapshots <id> :tags]` from the runtime-db partition and tests `contains?`. Returns `false` for unknown or not-yet-initialised machines.

The sub is **derived directly off the snapshot's `:tags` slot** — a view that only cares about whether a specific tag is present re-renders only when the containment-bit flips, not on every snapshot mutation. `reg-sub`'s built-in equality dedup carries it.

## Consuming the tag union

The tag union lets one selector sub resolve the whole active configuration to a single render-model keyword: a **render-priority table** (plain `[{:tag :render} …]` data) consulted in order inside a `reg-sub` over `[:rf/machine <id>]`, so the root view branches with one `case` instead of nine boolean discriminator subs + a `cond`. Adding a tenth case is one data row. The full worked render-priority pattern is the sole carrier at [`../../patterns/nine-states.md`](../../patterns/nine-states.md) (`examples/patterns/nine_states/core.cljs`).

## Common gotchas

- **Tags are **sets of keywords** on state nodes — not on transitions, not on the snapshot's `:data`.** Only `#{...}` is accepted: a vector or single keyword is **not** coerced — registration (`validate-tags!`) rejects any non-set value fail-loud with `:rf.error/machine-bad-tags`.
- **The state declares intent, not identity.** `:tags #{:loading}` is OK; `:tags #{:my-feature/loading-state}` is overkill. Use the **per-axis** intent (`:data/loading`, `:form/in-flight`, `:mode/read-only`) so views can ask one tag-question that spans multiple states.
- **Tags compose, but state-keywords don't.** Two states in different regions can both carry `:in-flight` — the union picks them up correctly. Don't try to query the **state-keyword** directly across regions; the snapshot's `:state` is a region-name → state-keyword map (parallel machines) or a single keyword (flat), and view code shouldn't branch on either shape. Branch on tags.
- **`:rf/machine-has-tag?` is a subscription.** Inside a view it's `@(rf/subscribe [:rf/machine-has-tag? ...])`. Inside an event handler use the one-shot non-reactive read `subscribe-once` (never a bare reactive `subscribe` deref — that leaks a reaction), or `compute-sub` in tests. The snapshot is **not** in `db` (app-db) — it's in the runtime-db partition, so the direct branch is a `reg-event` handler reading the `:rf.db/runtime` coeffect: `(get-in runtime-db [:rf.runtime/machines :snapshots machine-id :tags])`.
- **No empty `:tags` slot needed.** A state that doesn't carry tags just omits the key. The runtime elides the snapshot's `:tags` when the union is empty — a snapshot's `(contains? snap :tags)` may be `false` even after the machine has settled.
- **`:rf/*` and `:rf.machine/*` keyword namespaces are reserved.** Application tag keywords use a feature prefix: `:auth/required`, `:cart/dirty`, `:ws/disconnected`. Don't tag with `:rf/anything`.

## Why tags exist

Per Spec 005 §State tags §What tags are *not*: tags are **not** an additional state machine, not flow-state predicates, not a way to encode transitions. They are a **query convenience** — a view-facing projection of "what does the currently-active configuration mean?" The state machine is still the source of truth; tags are the read-side index.

If a tag would only ever match exactly one state, the tag is redundant — query the state directly (`(= :loading (:state snap))`). Tags earn their tokens by matching N states with a shared intent.

## Deeper material

For the full state-tags contract — declaration shape, snapshot semantics, the rationale vs `:status` slices — see `SKILL-REDIRECT.md` → *EP — State machines (005)* §State tags, and `SKILL-REDIRECT.md` → *Pattern — Nine states* for the canonical worked example.

---

*Derived from `re-frame.machines.transition` (`compute-tags` / `commit-tags`) and `re-frame.machines.parallel` (`compute-tags-parallel` — the cross-region union), and the `:rf/machine-has-tag?` sub in `re-frame.machines` @ main `89bd9c3`. Citations are symbol-level (machines.cljc was split); re-verify after tag-union or `:rf/machine-has-tag?` changes.*
