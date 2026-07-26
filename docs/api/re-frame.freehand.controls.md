# re-frame.freehand.controls

!!! warning "Pre-alpha — `day8/re-frame2-freehand` is not published"

    Freehand ships inside the re-frame2 monorepo and is **not published to
    Clojars**, and there is no date at which it will be. You resolve it with
    `:local/root` from a checkout — see
    [Install](../core/freehand/get-running/install.md). The public surface is deliberately
    still open: verbs can change while we learn from real apps.

`re-frame.freehand.controls` is the **first-party control kit** (EP-0036;
artefact `day8/re-frame2-freehand`, conventionally aliased `c`) — a small kit
grown one serious witness at a time rather than promised as a catalogue.

Skins, layouts and application compositions are meant to be copied and adapted.
What is *not* copy-only is the correctness machinery: the narrow read, the
generation fence, the composing-Enter rule and the exact release, because those
are the parts every hand-rolled copy gets wrong in the same way.

```clojure
(:require [re-frame.freehand :as v]
          [re-frame.freehand.controls :as c])

(v/defview contact-form [_]
  [:form
   [c/field {:field    (v/sub [:editor/field [:contact :email]])
             :on-edit  [:editor/edited      [:contact :email]]
             :on-visit [:editor/visited     [:contact :email]]
             :type     "email"}]
   [c/field {:field   (v/sub [:editor/field [:contact :phone]])
             :on-edit [:editor/edited      [:contact :phone]]}]])
```

## Both controls read a leaf, and there is no way to read a container

Neither control has a `:value` prop. The only value either accepts is a
[`form/field`](re-frame.freehand.form.md#field) projection, passed as `:field`.
A caller's forwarded attributes go through `v/spread-safe`, whose deny law
refuses `value` on a component's own controlled element in **every** build — so
the whole-draft read is not merely discouraged here, it is unspellable.

That matters more on a form than anywhere else. Every keystroke publishes a new
draft map, so a field reading the container is invalidated by a character typed
in any *other* field; and because a controlled input's synchronous door drains
re-frame and flushes the dirty cells on *this* frame before returning into
React, those fields re-render inside the keystroke rather than after it.

## Where the state is

In the form, which is ordinary application data at a path you chose. Neither
control owns a record, a host slot or a local buffer:
[`buffered-field`](#buffered-field)'s draft **is** the form's draft leaf. What
makes it buffered is that the *domain* hears about it only on commit — not that
the text is hidden somewhere re-frame cannot see it.

That is also why [`release`](#release) is exact and why nothing here can be
orphaned: there is no second store to forget.

## The controls

### `field`

```clojure
[c/field {:field    (v/sub [:editor/field [:contact :email]])
          :on-edit  [:editor/edited      [:contact :email]]
          :on-visit [:editor/visited     [:contact :email]]
          :class    "input"}]
```

One native `<input>` inside the controlled-input door, publishing every
keystroke.

| prop | |
|---|---|
| `:field` | **required** — a `form/field` projection |
| `:on-edit` | **required** — the intent the keystroke produces; the live value is appended |
| `:on-visit` | optional — the intent the blur produces |
| anything else | forwarded to the `<input>` through `v/spread-safe` |

`:on-edit` is completed with `::v/value`, so the handler receives
`[… path text]` and calls `form/edit`. The site is a literal event vector on a
native `<input>` carrying `value`, which is exactly the shape the door
recognises — so the round trip is synchronous and the caret, the selection and
any composition in flight survive it.

It renders one element and stamps one attribute of its own beyond the contract:
`aria-invalid`, from the projection's `:show-error?`. The label, the message and
the layout are yours — this is a control, not a widget catalogue, and a form kit
that owned the markup would be un-adaptable exactly where design systems differ.

### `buffered-field`

```clojure
[c/buffered-field {:field     (v/sub [:editor/field [:invoice :amount]])
                   :on-edit   [:editor/edited    [:invoice :amount]]
                   :on-commit [:editor/committed [:invoice :amount]]
                   :on-cancel [:editor/reverted  [:invoice :amount]]}]
```

The same leaf, committing on **blur** or **Enter** instead of on every
keystroke.

| prop | |
|---|---|
| `:field` | **required** — a `form/field` projection |
| `:on-edit` | **required** — every keystroke, with the live value appended |
| `:on-commit` | **required** — blur and non-composing Enter, with the leaf's `:reset-key` appended |
| `:on-cancel` | optional — non-composing Escape |
| anything else | forwarded through `v/spread-safe` |

The commit intent carries the leaf's reset revision, and the receiving handler
compares it against the committed form before acting:

```clojure
(rf/reg-event :editor/committed
  (fn [{:keys [db]} [_ path revision]]
    (let [f (:editor db)]
      (when (v/controller-current? revision (form/reset-key f path))
        {:fx [[:dispatch [:invoice/amount-set (get-in f (into [:draft] path))]]]}))))
```

The decision is taken **in the handler against committed state**, never by a
guard captured during render, which is what lets a cancel *beat* a late blur
rather than race it. And the generation is a revision rather than the value
because the case that matters is a caller rejecting a draft by reasserting what
it already had — the value before that decision and the value after it are
equal.

## The keyboard law

### `key-intent`

```clojure
(c/key-intent "Enter"  false)   ;=> :commit
(c/key-intent "Enter"  true)    ;=> nil
(c/key-intent "Escape" false)   ;=> :cancel
(c/key-intent "a"      false)   ;=> nil
```

[`buffered-field`](#buffered-field)'s keyboard branch, as a pure function of the
key and whether an IME composition was in flight.

**A composing Enter commits nothing.** Typing Japanese, Chinese or Korean
through an input method ends each phrase with an Enter that *accepts the
candidate* and belongs entirely to the IME. A control that reads it as a commit
fires the domain event mid-word, on every phrase the user types. It is invisible
to every keyboard the author owns, and it makes the control unusable for a large
fraction of the world's writing systems. A composing Escape is the IME's too.

It is public and pure so the law is provable by calling it, with no browser and
no host event, and so a kit member with its own keyboard protocol answers the
composition question through this function rather than re-deciding it.

### `commit-key`

```clojure
c/commit-key   ;=> "Enter"
```

The key that commits a buffered draft.

### `cancel-key`

```clojure
c/cancel-key   ;=> "Escape"
```

The key that abandons a buffered draft.

## Cleanup

### `release`

```clojure
(rf/reg-event :editor/closed
  (fn [{:keys [db]} [_ id]]
    {:db (c/release db [:forms id] [:forms id :attachments])}))
```

The **causal owner's** clear: the named form slices leave `db` together, in one
value.

Cleanup follows the owner, never the render. Freehand exposes no unmount
callback, no dispose registration and no per-occurrence teardown slot, and it
never will: unmount-driven cleanup destroys a draft when a virtualized row
scrolls out of view, and makes retention a property of what is on screen rather
than of what the work is. The route, workflow or record whose lifetime the form
actually follows dispatches an ordinary event, and that event calls this.

**Exact, and atomic.** Only the named paths go — never "every form" — and they
go in one value, so no intermediate state exists in which the owner is gone and
its form is not. A path whose parent is absent removes nothing and *creates*
nothing, so releasing twice is the same as releasing once.

## Related

- [Pure form transitions](re-frame.freehand.form.md) — the value both controls
  read.
- [`re-frame.freehand`](re-frame.freehand.md) — the one public door.
- [Spec 004 §The first-party control
  kit](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md) — the
  normative contract.
