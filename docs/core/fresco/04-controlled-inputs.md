# Controlled inputs

A controlled text field sends every edit through app-db and receives the
committed value back through a subscription. Fresco keeps that round trip in
the same browser turn and handles caret and IME behaviour for the normal
`:value`/`:on-input` form.

```clojure
(ns todo.fields
  (:require [re-frame.core :as rf]
            [re-frame.fresco :as h]))

(h/defview title-field [{:keys [id]}]
  [:input {:type     :text
           :value    (h/sub [:todo.ui/draft id])
           :on-input [:todo.ui/edit id ::h/value]}])
```

No local atom or caret helper is required. `:on-change` uses the same path. If
a field defines both handlers, both may fire and the runtime converges once.
Checkboxes use [`::h/checked`](glossary.md#hchecked); file inputs use
[`h/event`](glossary.md#event) because the platform owns their value.

## The controlled-field contract

For each keystroke, the path is:

`browser event → dispatch → event handler → app-db commit → subscription → view → DOM`

Fresco completes this path inside the discrete browser turn. That provides
four guarantees:

1. **The committed value returns in the same turn.** Fast typing does not lose
   or reorder characters merely because the field is controlled.
2. **The model remains authoritative.** The field displays the value committed
   by the handler. Accepted input remains, normalized input shows the
   normalized value, and rejected input returns to the unchanged model value.
3. **Caret and selection are preserved** when the model rejects or rewrites an
   edit, including edits in the middle of a string. The node is not remounted.
   This applies to `text`, `search`, `url`, `tel` and `password` inputs and to
   textareas. `email` and `number` inputs expose no selection to scripts, so a
   handler that rejects or rewrites an edit there leaves the caret at the end.
4. **IME composition is not interrupted.** Keyboard maps do not treat
   composition Enter or Escape as application commands, and a model correction
   waits until the composition closes.

A handler rejects an edit by returning `nil`; the view needs no special branch.
This one refuses titles longer than 80 characters:

```clojure
(rf/reg-event :todo.ui/edit
  (fn [{:keys [db]} [_ id typed]]
    (when (<= (count typed) 80)
      {:db (assoc-in db [:todo.ui :draft id] typed)})))
```

Controlled means the model owns the displayed value after every commit. If
autofill, a browser extension, or another script changes `.value`, the next
commit restores the model. An event delivered after the field unmounts is a
no-op, including a late blur; it leaves no retained input state.

Every controlled keystroke is a complete pipeline run;
[Performance](19-performance.md) has the measurements and budgets.

??? info "For readers coming from Reagent"
    A common Reagent pattern adds a local ratom to protect a field from async
    rendering. Do not copy that pattern into Fresco. The controlled path is
    synchronous and the runtime already preserves selection and composition.
    When an edit needs a separate draft/commit lifecycle, use the
    [forms module](05-forms.md) rather than a second atom.

## Supported controls

The same value-in, event-out form applies to the controls with a single
platform value:

| Control | Value supplied by the view | Event form |
| --- | --- | --- |
| Text-like `:input` (`text`, `email`, `password`, `search`, `url`, `tel`) | `:value` | `:on-input` with `::h/value` |
| `:textarea` | `:value` | `:on-input` with `::h/value` |
| Number, date, time, and range inputs | `:value` | `:on-input` with `::h/value`; parse the string in the handler |
| Checkbox | `:checked` | `:on-change` with `::h/checked` |
| Radio option | `:checked` for each option | `:on-change` carrying that option's value literally |
| `:select` | `:value` on the select | `:on-change` with `::h/value` |
| `:select` with `:multiple` | `:value` as a vector of the selected option values | `:on-change` with `::h/value`, which carries that same vector — `[]` when nothing is picked |
| File input | no controlled value | `:on-change` with `h/event`, reading `.files` |

A `nil` `:value` on a text-like input or a textarea shows an empty field, and
the field stays controlled, so a draft subscription may return `nil` before the
first edit.

```clojure
[:select {:value     (h/sub [:todo.ui/priority])
          :on-change [:todo.ui/set-priority ::h/value]}
 [:option {:value "normal"} "Normal"]
 [:option {:value "urgent"} "Urgent"]]

[:input {:type      :checkbox
         :checked   (h/sub [:todo/done? id])
         :on-change [:todo/set-done id ::h/checked]}]
```

Fresco reconciles a controlled value only for the controls in that table. A
contenteditable region has no single value to reconcile, so it is not a
controlled field: a `:value` on one is passed to React untouched, raises
nothing, and is never kept in step with app-db. Put a rich-text editor behind a
[foreign host](09-interop.md) or a [native island](10-native-tier.md) so it can
own the DOM it needs.

## Forward caller attributes safely

A reusable field can accept caller attributes while keeping its value and
handler: merge the caller map first and the owned entries last.

```clojure
(h/defview field [{:keys [id busy?] :as attrs}]
  [:input.form-control
   (merge (dissoc attrs :id :busy?)
          {:value    (h/sub [:todo.ui/field id])
           :disabled busy?
           :on-input [:todo.ui/edit-field id ::h/value]})])

[field {:id          :title
        :busy?       busy?
        :type        "text"
        :placeholder "Todo title"}]
```

The owned `:value`, `:disabled`, and `:on-input` replace a caller's entries
under the same keys, and they win by presence, not truthiness: when `busy?` is
`nil`, the owned `:disabled nil` still replaces a caller's `:disabled true`.
That protection is per map key, not per React prop: a caller's `:onInput` or
`"value"` is a different key, survives the merge, and lands on the same React
prop as the owned entry, with map iteration order deciding which wins. Forward
maps in kebab-keyword spelling, or `dissoc` the alternate spellings before
merging.

The tag's `.form-control` class combines with a caller's `:class`, which the
field does not own. To let the caller control a value, omit that key from the
owned map.

Do not forward `:key` or [`::h/revision`](glossary.md#hrevision): both describe
the element the wrapper writes, and are read only from that map.

## Reset with `::h/revision`

Place [`::h/revision`](glossary.md#hrevision) beside the controlled `:value`:

```clojure
(h/defview revertable-field [{:keys [id]}]
  [:input {:type        :text
           :value       (h/sub [:todo.ui/field id])
           ::h/revision (h/sub [:todo.ui/field-revision id])
           :on-input    [:todo.ui/edit-field id ::h/value]}])
```

The reset event updates both the value and the revision:

```clojure
(rf/reg-event :todo.ui/revert
  (fn [{:keys [db]} [_ id]]
    {:db (-> db
             (assoc-in [:todo.ui :fields id]
                       (get-in db [:todo.ui :saved id]))
             (update-in [:todo.ui :field-revisions id] (fnil inc 0)))}))
```

```clojure
[:button {:on-click [:todo.ui/revert id]} "Revert"]
```

Only a revision change triggers the reset behaviour. A new value under an
equal revision is an ordinary controlled update. Revisions compare with `=`,
so a freshly constructed but equal persistent value is unchanged.

A reset must be an explicit domain event. Fresco never infers a reset because
the incoming value equals a particular target. Value-based reset detection
cannot distinguish a user typing that value from an application reset, and it
cannot observe a same-value reassertion.

Async normalization uses the same rule: write the canonical value and advance
the revision when the server result should replace the current draft. This can
supersede text entered since the request began, so the event must encode the
application's intended conflict policy rather than treating every response as
a reset.

A reset keeps the existing DOM node and focus. It discards the current draft;
the caret moves to the end of the assigned model value, which is the browser's
normal behaviour for a value assignment.

!!! warning "A revision must be stable domain state"
    Do not create a revision in the view body with `random-uuid`, a render
    counter, or a collection index. That changes on every render and therefore
    resets on every render. Store a meaningful generation in app-db: a record
    id, load generation, form-open stamp, or counter incremented by a reset
    event.

`::h/revision` is legal only on controlled text input or textarea. Using it on
a `div`, `select`, value-less checkbox, or input with no `:value` raises
`:rf.error/fresco-revision-not-controlled`, naming the element and source.
The prop is consumed by Fresco and never becomes a DOM attribute on the client
or server. Like `:key`, it is read from whatever map reaches the element, so a
caller map forwarded with `merge` carries it through; `dissoc` it when you
forward ([above](#forward-caller-attributes-safely)).

Revision provides only re-baselining. It does not add commit, cancel,
acknowledgement, or caret-policy options. Use the forms module for a draft that
commits on Enter or blur and cancels on Escape.

## Troubleshooting

| Symptom | Error or cause | Fix |
| --- | --- | --- |
| Fast typing drops characters | The controlled write was deferred through a timer, debounce, queued effect, or promise | Commit the field value synchronously. Debounce downstream consumers, not the write |
| The caret moves to the end after each accepted/rejected edit | On an `email` or `number` input, expected: those types expose no selection. Otherwise the normal controlled path failed to preserve selection | On other types, treat this as a runtime bug and report it; do not remount or add a second writer |
| Enter commits unfinished composition text | A custom key handler bypassed Fresco's keyboard map | Use the data keyboard map so IME checks run centrally |
| A rejected edit kills the active composition | Another writer changed the DOM value during composition | Find the ref, foreign script, or uncontrolled sibling writing the same field; a controlled field must have one writer |
| IME text briefly lands stale and then corrects | The app-db write was deferred beyond the input turn | Keep the controlled write synchronous. The composition survives, but a deferred model update arrives late |
| Typing the application's reset value clears the field | Application code inferred reset from value equality | Advance `::h/revision` only for explicit reset events |
| The field resets on every render | The view creates a new revision while rendering | Read a stable revision written to app-db by events |
| A `revision="…"` attribute appears and the field never resets | The page used bare `:revision`; only the exact namespaced `::h/revision` is reserved | Use `::h/revision`. Other spellings are ordinary DOM attributes and may lose namespace information |
| Rendering raises `:rf.error/fresco-revision-not-controlled` | Revision was placed on a control outside the supported text path | Put it on the controlled input/textarea. Reset a select or checkbox by updating its model value |
| A file input's `:on-change` raises `:rf.error/fresco-file-input-value-marker` | The intent uses `::h/value`, which has no meaningful value on a file input | Use `h/event` and read `(.. e -target -files)` |
| Focus disappears after validation fails | Code remounted the input | Keep the node and let the controlled path restore the model value |

## When not to control a field

A controlled field writes app-db on every keystroke. Measure that path before
using it across a dense editable grid.

Use a [buffered field](05-forms.md#buffered-fields) when the user needs a draft
that commits on Enter or blur and cancels on Escape; keep a draft map in app-db
([Forms](05-forms.md#gate-validation-by-interaction)) when fields save together.

Use an uncontrolled input (`:default-value` without `:value`) when no other
part of the application needs the intermediate text, such as a scratch field
whose value is read only on blur. That choice keeps mid-edit text in the DOM,
so app-db, tests, SSR, and tools cannot observe it until the commit.

## Advanced

### Same-turn convergence and `flushSync`

A controlled event dispatches synchronously, and store notification is also
synchronous. React therefore receives the model echo during the same discrete
browser event.

On the event path, Fresco uses `flushSync` only for controlled-text convergence
(`h/render!` and `h/unmount!` also commit inside it). It commits the
pending value before React's end-of-event restore so that both the value and
selection are correct when the handler accepts, rejects, or normalizes an
edit. The path runs once per controlled text keystroke and once when an IME
composition closes. Pages without a controlled text input do not pay for it.
Using `flushSync` elsewhere in application code should be treated as an
architecture problem rather than a normal Fresco technique.

### Rejection and React restoration

When the handler returns `nil`, the DOM may briefly contain the typed
character. React restores the unchanged model value at the end of the discrete
event, but that restore normally loses selection. Fresco restores the caret
and selection on the controlled converge path. The handler still only returns
`nil`.

### Composition handling

While IME composition is active, Fresco does not overwrite the field with a
rejected or normalized model value. The handler may still receive each
composing update, but the visible composition remains intact until the user
commits it. The model correction then applies as one value.

This differs from plain React behaviour, where writing a corrected controlled
value during composition can abort the composition. Repeated aborts can also
compound normalized text; the runtime avoids both loss and duplication by
excluding the live composition from restoration.

### Resets that must wait

A revision change cannot always be applied immediately:

- **During composition**, applying it would abort the user's IME exchange. The
  field converges when composition ends, on blur, on unmount, or at the next
  non-composing input. Later accepted composing updates may supersede the reset
  by normal event order.
- **During hydration**, a revision received before the server node is adopted
  becomes the field's first observed revision and has no predecessor to compare
  against. The server node and any user draft survive. A later revision change
  resets normally. A caller that requires the swallowed reset must issue a new
  revision after adoption.

In both cases app-db remains the authoritative model; only the safe time for
writing the existing DOM node is deferred.
