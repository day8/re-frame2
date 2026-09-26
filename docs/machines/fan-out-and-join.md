# 9. Fan-out and join

Some work splits into several children that run at once: the shards of a long
job, the fetches a boot sequence waits for. Put `:spawn-all` on the state that
does the work. Entering the state starts every child; the parent moves on when
the join resolves — when all of them finish, or the first one does.

```clojure
;; cf. examples/patterns/long_running_work
:working
{:spawn-all
 {:children
  [{:id :s1 :machine-id :work/processor :data {:shard :s1 :total 100}
    :on-done (fn [{:keys [data result]}] (assoc-in data [:results :s1] result))}
   {:id :s2 :machine-id :work/processor :data {:shard :s2 :total 100}}
   {:id :s3 :machine-id :work/processor :data {:shard :s3 :total 100}}]

  :join            :all
  :on-all-complete [:work/all-done]
  :on-any-failed   [:work/any-failed]}

 :on
 {:progress        {:action :record-progress}   ;; no :target — don't respawn
  :work/all-done   {:target :complete}
  :work/any-failed {:target :failed}
  :cancel          {:target :cancelled}}}
```

Each entry in `:children` is a spawn spec like a single
[`:spawn`](actors.md#spawn-spec-keys)'s, plus an `:id` that names the child
within the block; [Rules](#rules) lists the differences. The block's `:on-*` keys name the events the join sends the
parent; the parent's `:on` decides where each one goes. Leaving `:working` by
any route, `:cancel` included, destroys every child still running.

## How a child reports

Each child is an ordinary machine, and it **completes without any parent
vocabulary**, exactly the way it would under a single `:spawn` — by
entering a root-level `:final?` leaf, naming its result slot with
`:output-key`:

```clojure
:done   {:final? true :output-key :shard-result}                ;; success
:failed {:final? true :error? true :output-key :reason}         ;; failure
```

So one child machine composes unchanged under `:spawn` and under
`:spawn-all`. `:meta {:terminal? true}` on such a leaf is redundant with
`:final?`.

## The resolution event

The runtime owns the join bookkeeping. When the join resolves it fires the
parent event **and** destroys any siblings still in flight. The event carries
the decisive child and its result:
`[<parent-id> [<resolution-event…> <child-id> <result>]]`. `<child-id>` is the
`:id` the block gave that child, not its allocated actor id, and `<result>` is
one value, the child's `:output-key` slot (its error payload on
`:on-any-failed`).

## Rules

- **Each child needs a unique `:id`** (the join key) on top of the usual
  spawn keys. Duplicates are `:rf.error/machine-spawn-all-duplicate-id`.
- **The `:children` vector is part of the definition**, so the number of
  children is fixed there; a fn in its place is refused
  (`:rf.error/machine-spawn-all-bad-shape`).
- **There are no child-vocabulary keys.** The block declares only how results
  combine: `:children`, `:join`, `:on-all-complete`, `:on-some-complete`,
  `:on-any-failed`. Any other bare key is
  `:rf.error/machine-spawn-all-bad-shape`.
- **A child spec may declare `:on-done`** — a `:data` fold on the parent at
  that child's finality, run before the join fold. It must be a fn:
  registration refuses any other value (`:rf.error/machine-bad-on-done-clause`),
  because the join's events own control flow. It may **not** declare
  `:on-error` (`:rf.error/machine-unknown-spawn-key`): failure control flow
  under a join is the block's `:on-any-failed`, which decides for the whole
  fan-out.
- **`:join` is only `:all` or `:any`.** There is no `{:n n}` and no
  predicate. Quorum ("N of M") is the idiom below, not a `:join` mode.
- **`:on-all-complete` is required for `:all`.** **`:on-some-complete` is
  required for `:any`.** Missing either is
  `:rf.error/machine-spawn-all-bad-shape`.
- **`:on-any-failed` is optional, but without it a failure can leave the join
  waiting for ever.** Under `:all`, one failed child means the join can never
  complete; under `:any`, the join waits once every child has failed. The
  parent stays in the state, and the runtime warns
  `:rf.warning/spawn-all-join-unsatisfiable`. Declare `:on-any-failed`, or
  give the state an `:after` deadline.
- **An unregistered child type fails the whole invoke**, atomically —
  nothing is spawned, so an `:all` join cannot hang on a child that never
  runs (`:rf.error/machine-spawn-unregistered-type`).
- **A state takes `:spawn` or `:spawn-all`, never both**
  (`:rf.error/machine-spawn-all-with-spawn`).
- A wall-clock bound on the join is the same as single `:spawn`: `:after`
  or `:timeout` / `:on-timeout` on the spawn-all-bearing state.

## Quorum

Quorum counts successes in the parent's `:data` and decides at a deadline.
Each child's `:on-done` bumps the count, and the state's `:after` is a guarded
candidate vector that reads it:

```clojure
(defn count-done [{:keys [data]}]
  (update data :done-count (fnil inc 0)))

:guards {:quorum? (fn [{:keys [data]}] (>= (:done-count data 0) 2))}

:working
{:entry     (fn [_] {:data {:done-count 0}})
 :spawn-all {:children        [{:id :a :machine-id :work/fetch :on-done count-done}
                               {:id :b :machine-id :work/fetch :on-done count-done}
                               {:id :c :machine-id :work/fetch :on-done count-done}]
             :join            :all
             :on-all-complete [:work/all-done]}
 :after     {5000 [{:guard :quorum? :target :degraded}   ;; 2 of 3 by the deadline
                   {:target :failed}]}
 :on        {:work/all-done :complete}}
```

An `:always` guard cannot make this decision: a join child's completion folds
into the join without a parent macrostep, so `:always` is not re-checked as
each child finishes. The count lives in `:data`, which outlives the state, so `:entry`
resets it. Leaving the state destroys any child still running.

## When not to use `:spawn-all`

- **The list is known only at run time.** Emit one `[:rf.machine/spawn …]`
  per item from an action
  ([Imperative spawn and destroy](actors.md#imperative-spawn-and-destroy)).
  Those children have no parent, so pass each an address to report to in its
  `:data`.
- **The children are independently valuable** — fire-and-forget, with no
  cancel-the-rest. Use N separate `:spawn`s, one per state, rather than a
  join.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Registration throws `:rf.error/machine-spawn-all-bad-shape` on `:join` | `:join` was `{:n n}`, a predicate, or another non-enum | `:join` is only `:all` or `:any`. For quorum, count in each child's `:on-done` and decide in a guarded `:after` |
| Registration throws `:rf.error/machine-spawn-all-bad-shape` naming a child-event key | the `:spawn-all` block names an event for its children to dispatch | Delete it. The child completes by reaching a `:final?` leaf; read the result off the resolution event or a child `:on-done` |
| Registration throws `:rf.error/machine-unknown-spawn-key` on a `:spawn-all` child | the child spec declared `:on-error` | Route failure through the block's `:on-any-failed` — a join has no per-child error transition |
| `:join :all` rejected | missing `:on-all-complete` | Give `:on-all-complete` an event vector |
| `:join :any` rejected | missing `:on-some-complete` | Give `:on-some-complete` an event vector |
| The parent never leaves the state; `:rf.warning/spawn-all-join-unsatisfiable` | a child failed and the block has no `:on-any-failed` | Declare `:on-any-failed`, or give the state an `:after` deadline |
| Children torn down (or respawned) on a progress event | the parent's `:on` had a `:target` | Omit `:target` so the transition is targetless |
