# State tags

Some questions a view asks aren't "which state is the machine in?" but "is the machine *busy*?" — across `:loading`, `:retrying`, `:reconnecting`, and whatever in-flight state you add next month. A **[state tag](glossary.md#state-tag)** is a semantic label you pin to a state so a view can ask for the *intent* — busy, read-only, terminal — instead of enumerating the exact state names that happen to mean it. *Ask, don't tell.*

Reach for tags when:

- several states share a meaning a view cares about ("any of these is loading-ish"), and you don't want a boolean sub per state;
- a page's render decision slices across more than one axis (data cardinality × form validity × mode) and you want one decision table instead of an N-way `cond`;
- a guard in one [parallel region](concepts.md#when-the-machine-grows) needs to know what a *sibling* region is doing without reaching into its private state.

If a label would only ever match exactly one state, skip it — query the state directly with `(= :loading (:state snap))`. Tags earn their keep by matching *many* states with one shared intent.

> **Coming from XState.** re-frame2's `:tags` *are* XState's state `tags` — same name, same concept, a clean convergence. `tags: ['busy']` becomes `:tags #{:busy}` (a Clojure set, not a JS array), and the union rule is identical: any active state carrying the tag puts it in the snapshot's tag set. The one shift to learn is the *query* side. XState reads `state.hasTag('busy')` off a living actor; in re-frame2 a machine is an [event handler](../core/glossary.md#event-handler) with no actor object, so the membership question is a **[subscription](../core/concepts/subscriptions.md)** — `@(rf/machine-has-tag? id :busy)`. The full row-by-row map is in [Coming from XState](coming-from-xstate.md#the-mapping). This page assumes the [machine grammar](concepts.md) — `reg-machine`, the transition table, the `{:state :data}` snapshot — from the concepts chapter.

## Declaring tags on a state

`:tags` is a state-node key whose value is a **set of keywords**. There's no separate registration step — it's just another slot on the state, alongside `:on`, `:entry`, and `:after`:

```clojure
(rf/reg-machine :todos/loader
  {:initial :idle
   :states
   {:idle     {:on {:fetch :loading}}

    :loading  {:tags #{:data/in-flight}
               :on   {:ok :ready :fail :retrying}}

    :retrying {:tags #{:data/in-flight}            ;; same intent as :loading
               :on   {:ok :ready :give-up :error}}

    :ready    {:tags #{:data/ready}}
    :error    {:tags #{:data/error}}}})
```

Both `:loading` and `:retrying` wear `:data/in-flight`. A view that wants "show the spinner while a request is in flight" consumes that one tag and never disjoins two state keywords — and the day you add a third in-flight state, it's one `:tags` entry and the view picks it up for free.

Two rules:

- **Tags label *intent*, not *identity*.** `:tags #{:data/in-flight}` is good; `:tags #{:todos.loader/loading-state}` just re-encodes the state name and earns nothing. Use a per-axis namespace (`:data/…`, `:form/…`, `:mode/…`) so one tag-question can span several states.
- **The `:rf/*` / `:rf.*/*` namespaces are framework-reserved.** Tag with your own feature prefix — `:auth/busy`, `:cart/dirty`, `:ws/disconnected`. Any unreserved namespace is fair game, dotted forms (`:ui.state/loading`) included.

> **Tags ride on *states*, never on transitions.** `:tags` is a state-node slot only — there is no tag on an `:on` entry, an `:always`, or an `:after`. "Was this transition tagged?" is already answered by the trace vocabulary, so a transition carries no tag. (Adding transition tags later would be non-breaking — but today, tags are not on transitions.)

## The snapshot's `:tags` slot

At every transition the runtime walks the machine's active states, unions their tag sets, and stamps the result onto the [snapshot](glossary.md#snapshot) at `:tags`. The snapshot gains one optional slot:

```clojure
@(rf/subscribe [:rf/machine :todos/loader])
;; => {:state :retrying :data {…} :tags #{:data/in-flight}}
```

How the union is computed depends on the machine's shape:

- **Flat machine** — the single active state's `:tags`.
- **Compound (hierarchical) machine** — the union along the active path: root → every compound ancestor → leaf.
- **Parallel machine** — the union across *every* active state in *every* region. (This is what makes tags the cross-region signal in the last section.)

`:tags` is **read-only** for you. It's a pure projection of `:state` — set the state, the tags follow — so an action can't return `:tags` in its `{:data :fx}` effect map; the runtime owns the slot. And when the union is **empty** (no active state declares any tag), the runtime **elides** the key entirely: a tag-free machine carries no `:tags` slot at all, so `(contains? snap :tags)` can be `false` even after the machine has settled. Don't declare an empty `:tags #{}` to "have the slot" — just omit it.

## Querying with `machine-has-tag?`

The framework ships one **derived subscription** for the containment question — *does this machine's snapshot carry this tag?*

```clojure
;; The query vector …
@(rf/subscribe [:rf/machine-has-tag? :todos/loader :data/in-flight])   ;; => true | false
;; … and its sugar, re-exported on the re-frame.core facade:
@(rf/machine-has-tag? :todos/loader :data/in-flight)                   ;; => true | false
```

In a view that's all you need to render on intent:

```clojure
(reg-view spinner-or-list []
  (if @(rf/machine-has-tag? :todos/loader :data/in-flight)
    [spinner]
    [todo-list]))
```

Because the sub is **derived directly off the snapshot's `:tags` slot** — it reads the containment bit with `get-in` rather than chaining the whole `[:rf/machine id]` snapshot — a view that asks one tag re-renders only when *that bit* flips, not on every `:data` mutation or unrelated transition. It returns `false` for an unknown or not-yet-initialised machine, so there's no nil-guard to write.

Need the whole set rather than one bit? That's the ordinary snapshot read:

```clojure
(:tags @(rf/subscribe [:rf/machine :todos/loader]))   ;; => #{:data/in-flight}
```

> **Reading tags inside an event handler.** A snapshot lives in [runtime-db](../core/glossary.md#runtime-db), not app-db, so a handler that needs to branch on a tag reads it from the `:rf.db/runtime` coeffect rather than from `:db`:
>
> ```clojure
> (fn [{rt :rf.db/runtime} _]
>   (get-in rt [:rf.runtime/machines :snapshots :todos/loader :tags]))
> ```

## Collapsing many states into one render decision

This is where tags pay off hardest. The [Nine States example](../../examples/patterns/nine_states) models a page as one `:type :parallel` machine with three orthogonal regions — `:data` (request lifecycle + cardinality), `:form` (validation), `:mode` (active / archived). Every state wears a per-axis tag:

```clojure
;; (excerpt from the three regions) — each state announces its intent
:loading   {:tags #{:data/loading :data/transient}        :on {…}}
:empty     {:tags #{:data/empty}                           :on {…}}
:incorrect {:tags #{:form/invalid}                         :on {…}}
:correct   {:tags #{:form/success :form/transient}         :on {…}}
:done      {:tags #{:mode/done :mode/read-only :mode/terminal}}
```

Three axes run at once, so several tags are live simultaneously — `:data/loading` *and* `:form/invalid` *and* `:mode/active`. The page can only draw one thing, so it needs a tie-breaker. Make it **plain data**: a render-priority table read top to bottom, plus one selector sub that returns the first matching tag's render-model keyword.

```clojure
(def render-priority             ;; excerpt — the runnable example carries all ten rows
  [{:tag :mode/done    :render :done}        ;; archived trumps everything
   {:tag :form/success :render :correct}     ;; transient form acks next
   {:tag :form/invalid :render :incorrect}
   {:tag :data/loading :render :loading}     ;; then the data lifecycle
   {:tag :data/error   :render :error}
   {:tag :data/empty   :render :empty}
   {:tag :data/some    :render :some}])

(rf/reg-sub :ui/render
  :<- [:rf/machine :ui/nine-states]
  (fn [snap _]
    (let [tags (:tags snap)]
      (some (fn [{:keys [tag render]}]
              (when (contains? tags tag) render))
            render-priority))))
```

The root view's entire branching logic is then one `case` — the only place the UI ever forks:

```clojure
(reg-view root-view []
  (case @(subscribe [:ui/render])
    :done      [view-done]
    :correct   [view-correct]
    :incorrect [view-incorrect]
    :loading   [view-loading]
    :error     [view-error]
    :empty     [view-empty]
    :some      [view-some]
    [:p "(unrecognised state)"]))
```

Nine states, three regions, one branch site. The priority order is a *product decision* — "an archived page beats a form acknowledgement beats the data bucket" — living in one readable table, not smeared across nine views. Adding a tenth render case is one row in `render-priority` plus one `case` clause; the machine and the other views don't move.

And the controls disable themselves the same ask-don't-tell way — they ask for the *intent*, not the state:

```clojure
(reg-view new-todo-form []
  (let [read-only? @(rf/machine-has-tag? :ui/nine-states :mode/read-only)]
    [:button {:disabled read-only?} "Add"]))
```

Notice what the form *doesn't* ask: "is the `:mode` region in `:done`?" It asks "is this read-only?" Move `:mode/read-only` onto a different state tomorrow and this view doesn't change a line.

## Tags as a cross-region signal (`stateIn`)

In a parallel machine the tag union spans *all* regions, which makes it the channel one region uses to read what a sibling is doing. This is the classic statechart coordination primitive: a guard in region A predicates on region B's state. XState spells it `stateIn({ form: 'valid' })` (and W3C SCXML has `In(...)`); re-frame2 ships the *behaviour* without a separate `stateIn` operator — a deliberate divergence for behavioural parity, not API mimicry. **A sibling region advertises a tag, and any region's guard reads the tag union off its context map.**

A guard or action running *inside a parallel region* gets two extra keys in its context map, alongside the usual `:data` / `:event` / `:state` / `:meta`:

- **`:tags`** — the machine-wide tag union (the **coarse**, idiomatic `stateIn` substitute);
- **`:all-state`** — the full `{region → active-state}` map (the **precise** read).

```clojure
(rf/reg-machine :ui/checkout
  {:type   :parallel
   :data   {}
   :guards {:form-valid? (fn [{:keys [tags]}]              ;; reads a SIBLING region's tag
                           (contains? tags :form/valid))}
   :regions
   {:form     {:initial :editing
               :states  {:editing {:tags #{:form/editing} :on {:complete :valid}}
                         :valid   {:tags #{:form/valid}}}}
    :checkout {:initial :idle
               :states  {:idle       {:on {:submit {:target :submitting
                                                    :guard  :form-valid?}}}
                         :submitting {:tags #{:checkout/submitting}}}}}})
```

`:checkout`'s `:submit` fires only once the `:form` region has advertised `:form/valid`. The precise variant reads the sibling's state value directly instead:

```clojure
:guards {:form-valid? (fn [{:keys [all-state]}] (= :valid (:form all-state)))}
```

Prefer the **tag** form: a tag is a named, tool-legible render-state, and it survives the sibling region renaming its internal states as long as the tag stays put.

Two things to hold onto:

- **A tag is a guard *input*, not a *trigger*.** A tag flipping on does not *fire* anything — there is no "on this tag appearing" transition. The dependent region still moves on its next event (or an eventless `:always`); the guard merely *reads* the sibling's advertised tag when it runs.
- **These keys are parallel-only, and frozen.** A flat or compound machine's guard/action context is exactly `{:data :event :state :meta}` — no `:tags` / `:all-state`, because a flat machine has no sibling to coordinate with (its own `:state` is its `stateIn`). In a parallel machine both keys reflect the configuration *as of the start of the macrostep*, so every region selects against the same frozen sibling view, independent of region declaration order. The exact same-event convergence paths — `:raise` for same-macrostep coupling, a guarded `:always` for next-event coupling — are spelled out in the spec.

## What tags are *not*

- **Not transition labels.** `:tags` is a state-node slot; transitions carry no tags.
- **Not an autonomous driver.** A tag appearing never fires a transition by itself — it's read by guards, it doesn't trigger them. For a state change that should follow a `:data` condition on its own, use a guarded eventless `:always`.
- **Not user-writable.** An action can't return `:tags` in its `{:data :fx}` map — the slot is runtime-owned and derived from `:state`.
- **Not the `:meta` slot.** A state's `:meta` (e.g. `{:terminal? true}`) is static, tooling-visible metadata; `:tags` is the runtime projection of the *active* configuration. Both can sit on the same state; they are not synonyms.
- **Not a replacement for `[:rf/machine id]`.** When a view needs the whole snapshot it still subscribes to `:rf/machine`; `machine-has-tag?` is for the predicate-shaped question. Both are first-class.

## Further reading

- [Machines: Concepts](concepts.md#guards-and-actions) — tags in the context of the full recognition kit (guards, actions, `:after`); [when the machine grows](concepts.md#when-the-machine-grows) covers parallel regions.
- [Coming from XState](coming-from-xstate.md#the-mapping) — the row-by-row XState → re-frame2 mapping, including `tags` and `stateIn`.
- [`re-frame.machines` API](../api/re-frame.machines.md#machine-has-tag) — the exhaustive contract for the `machine-has-tag?` sugar and the `[:rf/machine-has-tag? …]` / `[:rf/machine …]` subscription vectors.
- [Nine States example](../../examples/patterns/nine_states) — the render-priority pattern above, complete and runnable.
- **Normative contract:** [Spec 005 §State tags](../../spec/005-StateMachines.md#state-tags) — the semantic contract, the compound/parallel union rule, the empty-set elision, and the cross-region "tags as `stateIn`" coordination detail (the `:raise` vs `:always` convergence paths).
- These features realise the broader statechart ideas of state labels and cross-region coordination — see the [XState statecharts docs](https://stately.ai/docs/state-machines-and-statecharts) and [Fulcro statecharts](https://fulcrologic.github.io/statecharts/) for the lineage re-frame2 maps from.
