# Controlled inputs

> **Draft.** No `implementation/hicasso/` package yet. Names marked **[unfrozen]** may change. Mechanisms are proven under `implementation/freehand/test/re_frame/bench/hicasso/`; product spellings and some call shapes are still settling.

A text field whose value lives in app-db: value in from a subscription, intent out
on every keystroke. That is the whole required path.

```clojure
(defview title-field [{:keys [id]}]
  [:input {:type     :text
           :value    (sub [:todo.ui/draft id])
           :on-input [:todo.ui/edit id ::h/value]}])
```

> **Ordinary `:value` and `:on-input` — no caret helper, no local atom.**

`:on-change` works the same way. If a field carries both, both still lower and
can fire; the runtime's converge (value + caret) wraps **`onChange` when both
are present**, otherwise `onInput`. For checkboxes, use
[`::h/checked`](03-events-as-data.md#the-value-placeholders) the same way — see
[Events as data](03-events-as-data.md).

## What you get

The value on screen is app-db state, so every keystroke is a round trip:
keystroke → dispatch → handler → commit → subscription → re-render → DOM value.
Hicasso keeps that path **inside the same browser turn**, so two things hold
without you writing either:

1. **Same-tick echo.** The accepted character is back in the field before the
   browser finishes the event. Fast typists do not drop characters.
2. **Caret and selection stay put** when the model rejects or rewrites what was
   typed — mid-string edits included. Remounting the input to "reset" it is not
   an answer here: remount destroys focus, selection, and composition.

Return `nil` from the handler and the field stays on the model value with the
caret intact. That is the same path as a successful write; you do not special-case
rejection in the view.

```clojure
(rf/reg-event :order/set-quantity
  (fn [{:keys [db]} [_ id typed]]
    (if (re-matches #"\d*" typed)
      {:db (assoc-in db [:orders id :qty] typed)}
      nil)))                                       ;; rejected — model unchanged
```

IME composition is handled for you on the data key-map: a composing Enter does
not submit, and a live composition is not overwritten when the model refuses or
normalises mid-draft. Details under [Advanced](#advanced) if you need them.

## Forwarding attributes

Sooner or later you wrap the controlled contract once and let call sites pass
placeholder, `name`, test id, and friends. The remainder rides under one reserved
key, `:&`:

```clojure
(defview field [{:keys [id busy?] :as attrs}]
  [:input.form-control {:& (dissoc attrs :id :busy?)
                        :value    (sub [:editor/field id])
                        :disabled busy?
                        :on-input [:editor/edit-field id ::h/value]}])

[field {:id :title :busy? busy? :type "text" :placeholder "Article Title"}]
```

**Literal keys always win** over anything in `:&`. A caller (or a hostile
remainder) cannot override your `:value` or `:on-input`. When you *want* the
caller's value to win, leave the literal out: `[:input {:& attrs}]`. Put the
element's own classes on the tag (`[:input.form-control …]`); `:key` and `:ref`
are never taken from `:&`.

The law is on the prop slot React ends up with, not the key spelling — so
`"key"`, `:x/ref`, or `:onInput` against your `:on-input` reach nothing that
matters either.

## Resets are by revision, not by value

Force a field back to a value with an **explicit revision** from the caller —
form reset, revert, server normalisation — not by comparing the incoming value
to a target. Value-equality reset is how a user who legitimately types that
value gets yanked mid-edit, and how a same-value reassertion becomes invisible.
The prop that carries the revision is still unnamed; see **Not settled yet**.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Characters drop when typing fast | Something async sat between keystroke and commit — debounce, `setTimeout`, a queued effect | Keep the controlled write synchronous; debounce *consumers* of the value, not the write itself |
| Caret jumps to the end on every keystroke | Value reasserted without caret preservation | Runtime bug, not your view — report it |
| Enter commits half-typed text mid-composition | A hand-written key handler that bypasses the intent path | Use the data key-map; the composition check lives there |
| Composition dies when the model refuses mid-draft | A value write during composition (browser treats that as abort) | Not this runtime on controlled text: composition is left alone until `compositionend` |
| An IME commit lands stale text, then corrects itself | Something async sat between keystroke and commit, so `compositionend` reconciled the field against a model the deferred write had not reached yet | Keep the controlled write synchronous. The composition survives either way — only what it commits is late |
| Typing the "reset" value clears the field | Reset keyed on value equality somewhere | Reset on an explicit revision |
| Focus lost after validation fails | Something remounted the node | Do not remount to reset — that is exactly what destroys focus |

## When not to control an input

Controlled means every keystroke writes app-db. On a dense grid that is one
dispatch per cell per keystroke — price it before you pay it.

If intermediate values are nobody's business — a search box that only feeds a
debounced query, a scratch field in a modal — leave the input uncontrolled and
commit on blur. That is the right shape, not a shortcut.

## Advanced

### Same-tick mechanics (and the one `flushSync`)

Dispatch for a controlled element runs **synchronously inside the discrete
browser event**, and store notification is synchronous too, so React commits the
echo in the same turn. The value is back in the DOM before the browser finishes
handling the event.

`flushSync` is not a general default. The one named exception is the
controlled-text **converge**: it flushes the pending commit before React's
end-of-event restore, so value *and caret* are correct in-turn — including when
the model rejected or normalised the keystroke. That path runs once per keystroke
on controlled text entry only (a caret-bearing `input` or a `textarea` with a
non-nil `:value`), and once at `compositionend` for the composition carve-out
below. Everywhere else it is inert. Needing `flushSync` for anything else is a
design problem, not a feature.

### Rejection and React's restore

When the handler returns `nil`, the DOM node may briefly hold the typed
character; React reasserts the model value when the discrete event ends. React's
restore throws the caret away, so the runtime restores caret and selection itself
on that converge path. You still only write `nil`.

### IME composition

Two rules, both automatic on the data key-map and controlled-text path:

**A composing Enter commits nothing.** Mid-composition, Enter picks an IME
candidate; it is not a submit. The key-map gates on the native event's
`isComposing` and on the legacy keyCode-229 signal some browsers still send.

**A live composition is not overwritten.** If the model refuses or normalises
what the IME has composed so far, nothing is written to the field while
composition is running — not the converge, and not React's end-of-event restore.
Your handler still runs on every composing update; the field keeps showing the
composition until the user commits it, and the refusal or normalisation applies
whole at `compositionend`.

That last point differs from plain React. On plain React a corrected value
written during composition can abort the exchange: in-flight kana vanish,
`compositionend` never fires, and the next update starts a fresh composition on a
half-written draft. On a *normalising* field it does not merely lose the
composition — each aborted draft is written back and the IME composes on top of
it, so `s`, `sh`, commit ends the model at `SSHSH` where Hicasso commits the `SH`
that was typed. Hicasso carves the live composition out of that restore on
purpose.

Scope: controlled **text** entry (same path as the rest of this page).
Composition behaviour is proven on Chromium; a WebKit IME misbehave is a bug
report, not a documented limit.

## Not settled yet

| Question | Status |
|---|---|
| The revision prop's spelling on a controlled element | Reset law fixed; the attribute name is still open |
| A buffered / draft-and-commit input ladder | Not in the first ship of same-tick echo + caret preservation |
| A controls kit that owns drafts and revisions | Not first ship — a draft is app-db state you write yourself |
| Whether `:on-input` or `:on-change` is the taught attribute | Both work; the runtime takes whichever runs last. Current screens use `:on-input` for text and `:on-change` for checkboxes |
