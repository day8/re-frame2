# re-frame.hicasso.forms

The optional forms module: one view that keeps an app-db **draft** in front of a
committed value, and decides at commit time whether the commit still belongs to
the edit the user made.

```clojure
(:require [re-frame.hicasso :as h]
          [re-frame.hicasso.forms :as forms])
```

Nothing new sits underneath it. The draft is an `h/reg-state` concern, the reset
is `::h/revision`, the protocol is three ordinary events, and the field is one
`h/defview` boundary — so the module costs no hook beyond the shell's own, and an
application that never requires this namespace carries none of it.

This page is the manifest-tracked index of the module's public vars. The prop
table, the rejection rule and the recipes the module deliberately leaves to the
application live in [Forms](../core/hicasso/05-forms.md) and the
[Hicasso API reference](../core/hicasso/api-reference.md).

## The field

### `buffered-field`

- **Kind**: Var (view)
- **Signature**:
  ```clojure
  [forms/buffered-field {:control     address
                         :value       committed
                         ::h/revision generation
                         :on-commit   event-v
                         :on-cancel   event-v?
                         …attrs}]
  ```
- **Description**: A controlled `<input>` with an app-db draft in front of the
  committed value. `:control` is an **opaque address**, not a path; `:value` is
  the committed value; `::h/revision` is the caller's generation counter and is
  what a rejection is made of. `:value`, `:on-commit`, `:on-cancel`, `:key` and
  `::h/revision` are the field's own — every other prop reaches the `<input>`
  unchanged, with `:type` defaulting to `"text"`.
  - The protocol is three ordinary events in the module's own keyword namespace
    (`::edit` on `:on-input`, `::commit` on Enter and blur alike, `::cancel` on
    Escape), written into the field's intents rather than exported as names. A
    test that drives the field by hand spells them through
    `re-frame.hicasso.test.forms`.
  - It mints no refusal id of its own: a bad `:control` is `reg-state`'s
    `:rf.error/hicasso-state-bad-argument` at the field's first render, and
    `::h/revision` on a non-text field is
    `:rf.error/hicasso-revision-not-controlled`.
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

- **Kind**: Var
- **Signature**:
  ```clojure
  forms/drafts
  ```
- **Description**: The `h/reg-state` concern every buffered draft lives under, and
  the address an application clears to end one — route entry, an explicit cancel,
  a successful save reply. The value under one control is
  `{:revision r :draft text}`; **absence means no editing session**, and it is the
  only spelling of none, which is what makes every repeated commit idempotent. A
  draft survives re-render, remount, virtualization and navigation on purpose, so
  ending one is the application's call.
- **Example**:
  ```clojure
  (dispatch [::h/clear forms/drafts [:todo 7 :title]])
  ```

## See also

- [Forms](../core/hicasso/05-forms.md) — the chapter that governs the surface
- [Hicasso API reference](../core/hicasso/api-reference.md) — the full contract
- [`re-frame.hicasso`](re-frame.hicasso.md) — the door, including `h/reg-state`
  and `::h/revision`
