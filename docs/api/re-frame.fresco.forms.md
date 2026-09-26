# re-frame.fresco.forms

Use this module for a text field that edits a draft and commits it on Enter or
blur. `buffered-field` keeps the draft in `app-db` in front of the committed value,
and at commit time decides whether the commit still belongs to the edit the user
made.

It is an optional namespace: an application that never requires it carries none of
its code.

```clojure
(:require [re-frame.fresco :as h]
          [re-frame.fresco.forms :as forms])
```

It is built from ordinary Fresco parts: the draft is an `h/reg-state` concern, the
reset is `::h/revision`, the protocol is three ordinary events, and the field is
one `h/defview`, so it adds no hooks beyond a view's own.
[Forms](../core/fresco/05-forms.md) teaches the prop table, the rejection rule and
the recipes the module leaves to the application.

## The field

### `buffered-field`

- **Kind**: var (view)
- **Signature**:
  ```clojure
  [forms/buffered-field {:control     address
                         :value       committed
                         ::h/revision generation
                         :on-commit   event-v
                         :on-cancel   event-v?
                         …attrs}]
  ```
- **Description**: Renders a controlled `<input>` whose edits go to an `app-db`
  draft in front of the committed value.
    - `:control` is an opaque address for the draft, not an `app-db` path; `:value`
      is the committed value; `::h/revision` is the caller's generation counter, and
      is what a rejection is made of.
    - `:control`, `:value`, `:on-commit`, `:on-cancel`, `:key` and `::h/revision`
      belong to the field. Every other prop reaches the `<input>` unchanged, with
      `:type` defaulting to `"text"`.
    - The protocol is three events in the module's own keyword namespace: `::edit`
      on `:on-input`, `::commit` on Enter and on blur, and `::cancel` on Escape. They
      are written into the field rather than exported as names; a test that drives
      the field by hand spells them through `re-frame.fresco.test.forms`.
    - The module has no error ids of its own. A bad `:control` raises `reg-state`'s
      `:rf.error/fresco-state-bad-argument` at the field's first render, and
      `::h/revision` on a non-text field raises
      `:rf.error/fresco-revision-not-controlled`.
- **Example**:
  ```clojure
  [forms/buffered-field
   {:control     [:todo id :title]
    :value       (h/sub [:todo/title id])
    ::h/revision (h/sub [:todo/title-revision id])
    :on-commit   [:todo/title-committed id]
    :placeholder "What needs doing?"}]
  ```

## The draft's home

### `drafts`

- **Kind**: var
- **Signature**:
  ```clojure
  forms/drafts
  ```
- **Description**: The `h/reg-state` concern every buffered draft lives under, and
  the address an application clears to end a draft: on route entry, an explicit
  cancel, or a successful save reply.
    - The value under one control is `{:revision r :draft text}`. Absence means no
      editing session, and is the only way to say so, which is what makes a repeated
      commit idempotent.
    - A draft survives re-render, remount, virtualization and navigation, so ending
      one is the application's call.
- **Example**:
  ```clojure
  (dispatch [::h/clear forms/drafts [:todo 7 :title]])
  ```

## See also

- [Fresco API reference](../core/fresco/api-reference.md) — the full contract.
- [`re-frame.fresco`](re-frame.fresco.md) — `h/reg-state` and `::h/revision`.
