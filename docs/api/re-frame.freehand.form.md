# re-frame.freehand.form

!!! warning "Pre-alpha — `day8/re-frame2-freehand` is not published"

    Freehand ships inside the re-frame2 monorepo and is **not published to
    Clojars**, and there is no date at which it will be. You resolve it with
    `:local/root` from a checkout — see
    [Install](../core/freehand/install.md). The public surface is deliberately
    still open: verbs can change while we learn from real apps.

`re-frame.freehand.form` is **pure form transitions over ordinary application
data** (EP-0036; artefact `day8/re-frame2-freehand`, conventionally aliased
`form`).

It registers no event and no subscription, owns no atom, mounts nothing and
reads no frame. Every operation is `(f form …) -> form`, so you own event
registration, validation policy, app-db placement and rendering — exactly as you
do today. **This is not a form runtime**: there is no validator engine, no schema
renderer and no generic local store, and there will not be.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.freehand.form :as form])

(rf/reg-event :editor/opened
  (fn [{:keys [db]} [_ article]]
    {:db (assoc db :editor (form/init article))}))

(rf/reg-event :editor/edited
  (fn [{:keys [db]} [_ path text]]
    {:db (update db :editor form/edit path text)}))

(rf/reg-sub :editor/field
  (fn [db [_ path]] (form/field (:editor db) path)))
```

Three registrations, and they are the whole wiring for a form of any size.

## The value

A form is a plain map with seven documented slots, read the ordinary way. There
is no reader for each key; the two functions that *are* readers derive something
a key lookup does not give you.

```clojure
{:baseline {…}   ; the last values the DOMAIN accepted
 :draft    {…}   ; what the controls show and edit
 :visited  #{…}  ; leaf paths the user has focused and left
 :edited   #{…}  ; leaf paths the user has CHANGED
 :resets   {…}   ; leaf path -> reset revision (a generation)
 :errors   {…}   ; leaf path -> structured message
 :submit   {:attempted? false :attempts 0 :pending nil}}
```

**A leaf path is a non-empty vector** — `[:email]`, `[:contact :email]`,
`[:lines "line-7" :qty]`. Address rows by their **stable domain id** rather than
their index, and a path keeps meaning the same leaf across an insert, a delete
and a re-sort.

**`:visited` and `:edited` are two sets.** `:edited` is *the user changed this*
and is what protects a leaf from a late load; `:visited` is *the user has been
here and left* and is what reveals an error. A field the user tabbed through is
visited and not edited. Collapsing them into one `touched` set either makes a
tab-through wipe-protected, or makes an untouched required field scream.

## Opening and editing

### `init`

```clojure
(form/init)
(form/init baseline)
```

A fresh form whose baseline **and** draft are `baseline`. Nothing visited,
nothing edited, no leaf reset, no errors, no submit attempted.

A later authoritative load is [`seed`](#seed) or [`rebase`](#rebase) —
re-`init`ing on a late reply is the wipe this model exists to stop.

### `edit`

```clojure
(form/edit form path value)
```

The user changed the leaf at `path`. Writes the draft leaf and marks the leaf
**edited unconditionally**, including at a value equal to the baseline: edited
means *the user has been typing here*, not *the value currently differs*, and a
leaf typed into and corrected back is still theirs.

### `visit`

```clojure
(form/visit form path)
```

The user has been at the leaf and left it — the blur. Marks the leaf visited,
which is what lets that leaf's error appear, and changes no value.

## The outside world

### `seed`

```clojure
(form/seed form values)
```

A late authoritative load, taken **leafwise**, skipping every leaf the user has
already edited. This is the fix for the oldest bug in forms: a slow GET returns
while the user is typing, the handler `assoc`s the payload over the draft, and
the keystrokes are gone.

- **Edited leaves survive** — in the draft *and* in the baseline, so the work is
  neither displayed over nor silently re-based under.
- **Unrelated state survives** — a leaf the payload does not mention is
  untouched, including a sibling of one that is, and a whole sub-tree it omits.
- **Nested and keyed rows work the same way** — `{:lines {"line-7" {:qty 3}}}`
  seeds `[:lines "line-7" :qty]`.

A seeded leaf moves baseline and draft together and clears the error its old
value carried. Nothing is marked visited or edited: the server typing is not the
user typing.

### `rebase`

```clojure
(form/rebase form values)
```

The **baseline** moved and the draft stands — a save landed, or an
authoritative refresh arrived. A leaf whose draft now equals the new baseline
stops being edited; there is nothing left to protect there. No reset revision
advances, because a rebase is an acceptance rather than a rejection, so a
control holding a live buffer keeps it. A pending submit settles.

### `reset`

```clojure
(form/reset form)
(form/reset form path)
```

Discard the user's work — the whole form, or one leaf — and **advance the reset
revision** of every leaf discarded.

The revision is the point. A caller rejects a draft by reasserting the value it
already had, so nothing derived from the value can observe the decision;
advancing the generation is the one signal equality is blind to. Scoped to a
leaf, `reset` advances that leaf's generation and no other, so rejecting one
field does not abandon what the user is typing in the next.

## Validation and submission

### `set-errors`

```clojure
(form/set-errors form {[:contact :email] "malformed"})
(form/set-errors form {})
```

Replace the errors with a map of **leaf path** to structured message.
Replacement rather than merge, because that is what a validation pass produces;
a server rejection that must not lose a client-side error merges its own map and
passes the result.

What counts as an error is yours. A message is any EDN — a string, a keyword, a
map with a code and arguments for translation.

### `attempt-submit`

```clojure
(form/attempt-submit form)
```

Always marks the form attempted, which reveals every error at once — including
on leaves the user never visited, which is exactly what a submit is for. An
invalid form stays attemptable.

When there are no errors it also opens a pending save at `[:submit :pending]`,
stamped with the attempt number. **That stamp is the staleness fence**: carry it
out with the request and compare it on the way back, so a slow first reply
landing after a second attempt is inert.

```clojure
(rf/reg-event :editor/submitted
  (fn [{:keys [db]} _]
    (let [f     (form/attempt-submit (:editor db))
          token (get-in f [:submit :pending])]
      (cond-> {:db (assoc db :editor f)}
        token (assoc :fx [[:http {:on-success [:editor/saved token]}]])))))
```

## The two derivations

### `field`

```clojure
(form/field form path)
;; => {:re-frame.freehand/path [:contact :email]
;;     :value "a@b.c" :baseline "a@b.c" :error nil
;;     :visited? false :edited? false :show-error? false :reset-key 0}
```

The **narrow read**: everything one control needs about one leaf, and nothing
about any other. Registered once, it is one subscription for a form of any size.

It recomputes on every keystroke, as every subscription over app-db does, and
its *value* changes only when that leaf's does — so a character typed in
`:notes` does not re-render `:email`. Reading the container instead
(`(:email (v/sub [:editor/draft]))`) inverts that: every keystroke publishes a
new draft map, every field's read changes value, and because a controlled
input's synchronous door flushes on the same frame, all of them re-render
*inside* the keystroke. Whole-container reads make keystroke latency scale with
the size of the form.

`:show-error?` is the **reveal** policy and not the validation policy: an error
shows once the user has visited the leaf, or once a submit has been attempted.

### `reset-key`

```clojure
(form/reset-key form path)   ;=> 0, then 1 after a reset
```

The leaf's current reset revision — the generation a buffered control fences
against through `v/controller-current?`. `0` for a leaf that has never been
reset, so a control is fenced from its first render rather than from its first
rejection.

### `path-key`

```clojure
form/path-key   ;=> :re-frame.freehand/path
```

The reserved key a [`field`](#field) projection carries its leaf path under —
the projection's identity as well as its data.

## Related

- [The first-party control kit](re-frame.freehand.controls.md) — `field`,
  `buffered-field` and the causal owner's `release`.
- [`re-frame.freehand`](re-frame.freehand.md) — the one public door.
- [Spec 004 §Forms and the first-party control
  kit](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md) — the
  normative contract.
