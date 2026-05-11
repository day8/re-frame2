# Tags — per-state intent + the `has-tag?` query

## When to load

Reach for this leaf when a view needs to ask "is the machine in any in-flight state?" or "is the form read-only?" — without naming the specific state-keyword. Tags are how a state declares its **intent** so views can query the intent, not the state name. Pairs with `regions.md` for the parallel-region tag-union story.

## Canonical declaration

A state node carries a `:tags` slot whose value is a **set of keywords**. There is no separate registration call; the slot is just a key on the state node, processed by the runtime via `compute-tags` (`implementation/machines/src/re_frame/machines.cljc:311`):

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
- **Parallel-region machine** — the union across every active state in every region (`machines.cljc:295`, Stage 2 / rf2-l67o).

If the union is empty the slot is **elided** entirely (snapshot-size optimisation, `machines.cljc:327` `commit-tags`). The `:rf/machine-snapshot` schema marks `:tags` as `{:optional true}` — both presence (with non-empty set) and absence are valid.

## Querying — `rf/has-tag?`

```clojure
@(rf/has-tag? :realworld/tags :tags/in-flight)        ;; truthy iff the tag is in the union
@(rf/has-tag? :ui/nine-states :mode/read-only)
```

`has-tag?` is sugar over `(subscribe [:rf/machine-has-tag? machine-id tag])` (`implementation/core/src/re_frame/core.cljc:1084`). The underlying sub (`machines.cljc:2122`) reads `[:rf/machines <id> :tags]` and tests `contains?`. Returns `false` for unknown or not-yet-initialised machines.

The sub is **derived directly off the snapshot's `:tags` slot** — a view that only cares about whether a specific tag is present re-renders only when the containment-bit flips, not on every snapshot mutation. `reg-sub`'s built-in equality dedup carries it.

## Canonical worked example

From `examples/reagent/nine_states/core.cljs:487-512` — a render-priority table consults the tag union and resolves to one render-model keyword:

```clojure
(def render-priority
  [{:tag :mode/done       :render :done}             ;; mode region wins outright
   {:tag :form/success    :render :correct}          ;; form's transient acks next
   {:tag :form/invalid    :render :incorrect}
   {:tag :data/loading    :render :loading}          ;; data region's lifecycle
   {:tag :data/error      :render :error}
   {:tag :data/empty      :render :empty}
   {:tag :data/some       :render :some}])

(rf/reg-sub :ui/render
  :<- [:rf/machine :ui/nine-states]
  (fn sub-ui-render [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))

;; The root view: one `case`, not nine boolean discriminator subs + a cond.
[case @(subscribe [:ui/render])
   :done      [view-done]
   :loading   [view-loading]
   :error     [view-error]
   ;; ...
   ]
```

The render-priority table is plain data — adding a tenth case is one row.

## Common gotchas

- **Tags are **sets of keywords** on state nodes — not on transitions, not on the snapshot's `:data`.** A vector or single keyword is coerced to a set (`machines.cljc:303`), but the canonical form is `#{:foo :bar}`.
- **The state declares intent, not identity.** `:tags #{:loading}` is OK; `:tags #{:my-feature/loading-state}` is overkill. Use the **per-axis** intent (`:data/loading`, `:form/in-flight`, `:mode/read-only`) so views can ask one tag-question that spans multiple states.
- **Tags compose, but state-keywords don't.** Two states in different regions can both carry `:in-flight` — the union picks them up correctly. Don't try to query the **state-keyword** directly across regions; the snapshot's `:state` is a region-name → state-keyword map (parallel machines) or a single keyword (flat), and view code shouldn't branch on either shape. Branch on tags.
- **`has-tag?` is a subscription.** Inside a view it's `@(rf/has-tag? ...)`. Inside an event handler it's a `subscribe` deref or a `compute-sub` call (in tests). Don't reach for it inside a `reg-event-db` body — read the snapshot's `:tags` set directly off `db` via `(get-in db [:rf/machines machine-id :tags])` if you need to branch inside an event.
- **No empty `:tags` slot needed.** A state that doesn't carry tags just omits the key. The runtime elides the snapshot's `:tags` when the union is empty — a snapshot's `(contains? snap :tags)` may be `false` even after the machine has settled.
- **`:rf/*` and `:rf.machine/*` keyword namespaces are reserved.** Application tag keywords use a feature prefix: `:auth/required`, `:cart/dirty`, `:ws/disconnected`. Don't tag with `:rf/anything`.

## Why tags exist

Per Spec 005 §State tags §What tags are *not*: tags are **not** an additional state machine, not flow-state predicates, not a way to encode transitions. They are a **query convenience** — a view-facing projection of "what does the currently-active configuration mean?" The state machine is still the source of truth; tags are the read-side index.

If a tag would only ever match exactly one state, the tag is redundant — query the state directly (`(= :loading (:state snap))`). Tags earn their tokens by matching N states with a shared intent.

## Deeper material

For the full state-tags contract — declaration shape, snapshot semantics, the rationale vs `:status` slices — see `SKILL-REDIRECT.md` → *EP — State machines (005)* §State tags, and `SKILL-REDIRECT.md` → *Pattern — Nine states* for the canonical worked example.
