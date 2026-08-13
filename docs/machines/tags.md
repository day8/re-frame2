# 3. Tags

<a id="tags"></a>
<a id="state-tags"></a>

The [first machine](tutorial.md) already put `#{:auth/busy}` on `:submitting`
so the view could ask "busy?" without naming that state. This page is that
idea as a contract.

A view often does not care which exact state a machine is in. It cares about a
semantic question: is this flow busy? read-only? terminal?

A **[state tag](glossary.md#state-tag)** is a label on a state that answers
those questions without forcing the view to enumerate state names.

## Declare tags on states

Start from the login table. Tag the states the view actually asks about:

```clojure
:submitting  {:tags #{:auth/busy}   …}
:authed      {:tags #{:auth/authed}
              :meta {:terminal? true}}
:locked-out  {:tags #{:auth/locked :auth/terminal}
              :meta {:terminal? true}}
```

If login later grows a second in-flight state (refreshing a token, restoring
a session), put `#{:auth/busy}` on that state too. The view that asks for the
tag does not change.

`:tags` is a **set of keywords** on a state node. A vector or a lone keyword is
rejected at registration with `:rf.error/machine-bad-tags`.

Tags label **intent**, not identity. Good tags name what the state means to the
rest of the program:

```clojure
:tags #{:auth/busy}
:tags #{:mode/read-only}
:tags #{:ws/connected}
```

Weak tags repeat the state's identity:

```clojure
:tags #{:auth-login/submitting-state}
```

Use a per-axis namespace (`:data/…`, `:form/…`, `:mode/…`) so one question can
span several states. The `:rf/*` and `:rf.*/*` namespaces are reserved; tag with
your own feature prefix. Dotted forms such as `:ui.state/loading` are fine.

If a tag will only ever match one state, skip it and read `:state` directly.

## The snapshot's `:tags`

The runtime unions the tags on every active state and writes the result onto the
[snapshot](glossary.md#snapshot):

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting
;;     :data  {...}
;;     :tags  #{:auth/busy}}
```

How the union is computed depends on the machine's shape:

| Machine shape | Tag projection |
|---|---|
| Flat | tags on the active state |
| [Hierarchical](hierarchical-states.md) | union along the active path, root to leaf |
| [Parallel](parallel-states.md) | union of every active state in every region |

The runtime owns `:tags`. An action cannot return `{:tags …}` — the slot is a
projection of `:state`. When no active state declares tags, the runtime
**elides** the key. Do not declare `:tags #{}` to force the slot; omit it.

## Query one tag

<a id="querying-with-machine-has-tag"></a>

The query is this subscription:

```clojure
@(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])
;; => true or false
```

```clojure
(rf/reg-view sign-in-button []
  (let [busy? @(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])]
    [:button {:disabled busy?} "Sign in"]))
```

It returns `false` for an unknown or not-yet-initialised machine. The sub is
derived: a view that asks one tag re-renders when that membership flips, not on
every `:data` write.

There is no `machine-has-tag?` function and no `[:rf/machine-has-tag? …]`
vector. The membership question is `[:rf.machine/has-tag? machine-id tag]`.

Need the whole set? Read the snapshot:

```clojure
(:tags @(rf/subscribe [:rf/machine :auth.login/flow]))
;; => #{:auth/busy}
```

Use that form for selectors and render-priority tables.

<a id="collapsing-many-states-into-one-render-decision"></a>

## Advanced: collapsing many states into one render decision

Several tags can be live at once in a [parallel](parallel-states.md) machine, but
a page can render only one main view.

The [Nine States example](../../examples/patterns/nine_states) is one
`:type :parallel` machine with three regions — `:data`, `:form`, `:mode`. Each
state advertises a per-axis tag:

```clojure
;; cf. examples/patterns/nine_states
:loading   {:tags #{:data/loading :data/transient} :on {...}}
:empty     {:tags #{:data/empty}                   :on {...}}
:incorrect {:tags #{:form/invalid}                 :on {...}}
:correct   {:tags #{:form/success :form/transient} :on {...}}
:done      {:tags #{:mode/done :mode/read-only :mode/terminal}}
```

Make the tie-breaker **plain data**: a table read top to bottom, plus a selector
sub that returns the first matching tag's render keyword.

```clojure
;; cf. examples/patterns/nine_states — the example carries all ten rows
(def render-priority
  [{:tag :mode/done    :render :done}
   {:tag :form/success :render :correct}
   {:tag :form/invalid :render :incorrect}
   {:tag :data/loading :render :loading}
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

The root view branches once:

```clojure
(rf/reg-view root-view []
  (case @(rf/subscribe [:ui/render])
    :done      [view-done]
    :correct   [view-correct]
    :incorrect [view-incorrect]
    :loading   [view-loading]
    :error     [view-error]
    :empty     [view-empty]
    :some      [view-some]
    [:p "(unrecognised state)"]))
```

The order is a product decision — archived beats a form acknowledgement beats
the data bucket — living in one table. Adding a render case is one row plus one
`case` clause.

```clojure
(rf/reg-view new-todo-form []
  (let [read-only? @(rf/subscribe [:rf.machine/has-tag? :ui/nine-states :mode/read-only])]
    [:button {:disabled read-only?} "Add"]))
```

The form does not ask whether `:mode` is `:done`. It asks whether the screen is
read-only.

## Tags as a cross-region signal

In a [parallel machine](parallel-states.md) the tag union spans every region. One
region can advertise a tag; another region's guard reads it.

```clojure
(rf/reg-machine :checkout/page
  {:type :parallel
   :data {}

   :guards
   {:form-valid?
    (fn [{:keys [tags]}]
      (contains? tags :form/valid))}

   :regions
   {:form
    {:initial :editing
     :states  {:editing {:tags #{:form/editing}
                         :on   {:complete :valid}}
               :valid   {:tags #{:form/valid}}}}

    :checkout
    {:initial :idle
     :states  {:idle       {:on {:submit {:target :submitting
                                          :guard  :form-valid?}}}
               :submitting {:tags #{:checkout/submitting}}}}}})
```

The checkout region does not need the form region's state names. It reads
`:form/valid`.

A region guard or action also receives `:all-state`, the region → state map,
when the precise sibling state matters:

```clojure
(fn [{:keys [all-state]}]
  (= :valid (:form all-state)))
```

Prefer tags. They survive a sibling refactor.

Two rules:

- **A tag is a guard input, not a trigger.** A tag appearing does not fire a
  transition. The dependent region still moves on its next event or a guarded
  `:always`; the guard only reads the sibling's tag when it runs.
- **`:tags` and `:all-state` are parallel-only.** A flat or compound machine's
  guard/action context is `{:data :event :state :meta}`. There is no sibling to
  coordinate with.

The frozen-snapshot selection rules live in
[Parallel states → Coordinating regions](parallel-states.md#coordinating-regions-tags-as-statein).

## What tags are not

- **Not transition labels.** `:tags` is a state-node slot. Transitions carry
  none.
- **Not a trigger.** A tag appearing never fires a transition by itself. For a
  move that should follow a condition on its own, use a guarded `:always`.
- **Not user-writable.** An action cannot return `:tags`. The runtime projects
  the slot from active state.
- **Not `:meta`.** A state's `:meta` (for example `{:terminal? true}`) is static,
  tooling-visible metadata. `:tags` is the live projection of the active
  configuration. Both can sit on the same state; they are not the same thing.
- **Not a replacement for `[:rf/machine id]`.** When a view needs the whole
  snapshot it still subscribes to `:rf/machine`. `[:rf.machine/has-tag? …]` is
  the predicate-shaped question.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Registration throws `:rf.error/machine-bad-tags` | `:tags` is a vector or a lone keyword | Write a set: `#{:data/in-flight}` |
| Snapshot has no `:tags` key | No active state declares tags; the empty union is elided | Omit the key; `(contains? (:tags snap) x)` is still false |
| Guard ctx has no `:tags` / `:all-state` | The machine is flat or compound | Those keys exist only inside a parallel region |
