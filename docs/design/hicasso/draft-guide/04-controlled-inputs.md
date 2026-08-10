# Controlled inputs

A controlled text field keeps its value in app-db. The value comes in from a
subscription, and an [intent](glossary.md#intent) goes out on every keystroke. That is the whole
required path.

```clojure
(h/defview title-field [{:keys [id]}]
  [:input {:type     :text
           :value    (h/sub [:todo.ui/draft id])
           :on-input [:todo.ui/edit id ::h/value]}])
```

> **You write ordinary `:value` and `:on-input`, with no caret helper and no
> local atom.**

`:on-change` works the same way. If a field carries both, both can fire, and
the runtime converges once. For checkboxes, use
[`::h/checked`](03-events-as-data.md) in the same way — the placeholders and
the key map are taught in [Events as data](03-events-as-data.md).

Controlled inputs are a framework law, not a pattern you assemble. The defect
class is too subtle and too common to solve again in each application. The
defects are: dropped keystrokes under fast typing, carets that jump to the
end, IME compositions destroyed in the middle of a word, and rejections that
do not show. You write the two attributes above. The framework enforces the
law below.

## What the law guarantees

The value on screen is app-db state, so every keystroke is a round trip:
keystroke → dispatch → handler → commit → subscription → re-render → DOM value.
The framework keeps that whole path **inside the same browser turn**. Because
of this, four guarantees hold without any code from you:

1. **Same-turn echo.** Dispatch runs synchronously inside the discrete browser
   event. The commit's echo is back in the field before the browser finishes
   the event — on screen within one frame. Fast typists do not drop or
   reorder characters.
2. **Committed echo.** The field shows what your handler committed, not what
   the user typed. If you accept the keystroke, it stays. If you rewrite it,
   the rewrite shows. If you reject it, the field holds the model value.
3. **Caret and selection stay in place** when the model rejects or rewrites
   the typed text, including edits in the middle of the string. The framework
   never remounts the input to reset it. A remount destroys focus, selection,
   and any live composition.
4. **Composition is safe.** An Enter during composition selects an IME
   candidate and commits nothing. A live composition is never overwritten
   while it runs. A refusal or normalization lands whole when the composition
   ends.

Rejection uses the same path as acceptance. Return `nil` from the handler,
and the field stays on the model value with the caret intact. You do not
write special rejection code in the view:

```clojure
(rf/reg-event :order/set-quantity
  (fn [{:keys [db]} [_ id typed]]
    (if (re-matches #"\d*" typed)
      {:db (assoc-in db [:orders id :qty] typed)}
      nil)))                                       ;; rejected — model unchanged
```

Two smaller clauses complete the law. Controlled means the model owns the
screen value at every commit. So foreign drift — autofill, a browser
extension, a script that writes `.value` — is repaired the next time the
element commits. Events that land after unmount find nothing. A blur
delivered to a field that has just left the tree is a no-op, with zero
residue.

Every keystroke on a [controlled field](glossary.md#controlled-field) is a full pipeline run: state write,
subscription recomputation, [boundary](glossary.md#boundary) run, React commit, painted echo. The
[performance chapter](18-performance.md) publishes that path, with counts for
the four-field editor and the controlled grid. This page owns the law; that
page owns the cost.

??? info "If you come from Reagent"
    The usual Reagent pattern wraps an input in a local ratom, so that async
    rendering cannot drop keystrokes or move the caret. Do not carry that
    pattern here. The controlled path is synchronous end to end, and the
    runtime owns caret and composition. A local atom adds nothing, and it
    re-creates the twin-atom stack that the [forms module](05-forms.md)
    exists to remove.

## The whole control family

The same value-in / intent-out shape covers every control for which the
platform gives you a value:

| Control | Value in | Intent out |
|---|---|---|
| `:input` (text, email, password, search, url, tel) | `:value` | `:on-input` with [`::h/value`](glossary.md#hvalue) |
| `:textarea` | `:value` | `:on-input` with [`::h/value`](glossary.md#hvalue) |
| `:input` number, date, time, range | `:value` | `:on-input` with [`::h/value`](glossary.md#hvalue) — the DOM hands you strings; parse in the handler |
| `:input` checkbox | `:checked` | `:on-change` with [`::h/checked`](glossary.md#hchecked) |
| `:input` radio | `:checked` per option | `:on-change` carrying the option's own value literally |
| `:select` | `:value` on the select | `:on-change` with [`::h/value`](glossary.md#hvalue) |
| `:input` file | none — the platform owns a file input's value | `:on-change` with [`h/event`](glossary.md#hevent), reading `.files` off the event |

```clojure
[:select {:value     (h/sub [:cart/shipping])
          :on-change [:cart/set-shipping ::h/value]}
 [:option {:value "standard"} "Standard"]
 [:option {:value "express"}  "Express"]]

[:input {:type      :checkbox
         :checked   (h/sub [:cart/gift-wrap?])
         :on-change [:cart/set-gift-wrap ::h/checked]}]
```

A control that is *not* on the list refuses instead of half-working. A
contenteditable region is the named refusal. It has no single true value for
the controlled contract to converge on, so a `:value` binding on it refuses
at source. The recovery is real: rich text is a job for a
[declared host](09-interop.md) or a [named native island](10-native-tier.md),
where the editor library owns the DOM that it must own.

## Forwarding attributes

At some point you wrap a field once, and you let call sites pass placeholder,
`name`, a test id, and more. The recipe is an ordinary merge, with your owned
entries last:

```clojure
(h/defview field [{:keys [id busy?] :as attrs}]
  [:input.form-control
   (merge (dissoc attrs :id :busy?)
          {:value    (h/sub [:editor/field id])
           :disabled busy?
           :on-input [:editor/edit-field id ::h/value]})])

[field {:id :title :busy? busy? :type "text" :placeholder "Article Title"}]
```

**The literal keys you write always win.** The law binds on the React slot
where a key lands, not on its spelling. A forwarded `:onInput` against your
`:on-input`, or `"value"` against your `:value`, reaches nothing that
matters. A forwarded map can never replace the owned value, checked, handler,
key, or revision slots of a control. When you *want* a caller's value to win,
do not write your literal. Put the element's own classes on the tag
(`[:input.form-control …]`). Never forward `:key` or [`::h/revision`](glossary.md#hrevision): these
keys address the element *you* wrote, and the runtime reads them only from
the map you wrote.

## Resets are by revision, never by value

To force a field back to a value — a form reset, a revert, a
server-normalized write that comes back — is a real [intent](glossary.md#intent), and it has its
own door: an **explicit revision** from the caller. The runtime never infers
a reset from a comparison of the incoming value with a target. With a
value-equality reset, a user who types the "reset" value loses the edit in
progress. Also, a same-value reassertion (the caller rejects a draft and
restores the old value) becomes invisible.

[`::h/revision`](glossary.md#hrevision) is that one door. The view reads it like any other value,
beside `:value`:

```clojure
(h/defview revertable-field [{:keys [id]}]
  [:input {:type        :text
           :value       (h/sub [:editor/field id])
           ::h/revision (h/sub [:editor/baseline id])
           :on-input    [:editor/edit-field id ::h/value]}])
```

The revert event does both halves: it restores the value **and** moves the
revision. A `[:button {:on-click [:editor/revert id]} "Revert"]` fires it:

```clojure
(rf/reg-event :editor/revert
  (fn [{:keys [db]} [_ id]]
    {:db (-> db
             (assoc-in [:editor :fields id] (get-in db [:editor :saved id]))
             (update-in [:editor :baselines id] inc))}))   ;; the revision moves
```

**Only a revision change resets.** A `:value` that moves under an unchanged
revision is ordinary controlled conduct: the field shows it, and nothing
consults the revision on the way. The comparison is `=`, so a revision value
that is equal but freshly built is inert. The same move covers async
normalization. When the server's canonical form of the typed text comes back,
write the value and move the revision. The field re-baselines to it, even if
the user has started to type again.

**A reset is not a remount.** The draft in the field goes. The node itself
stays, and the focus on it stays. The caret lands at the end of the model
value on the commit that carries the reset. That is the platform's own
conduct for a `value` assignment.

!!! warning "Write a revision the way you would write an instance key"
    A revision minted in render changes on every render, so it resets the
    field on every render. Never use a render-order index, a counter that the
    body increments, or `random-uuid`. A revision is a domain fact that your
    *events* put in app-db: a record id, a load generation, a "form opened
    at" stamp, or a counter that your revert handler increments.

The runtime refuses the prop loudly on anything that is not controlled text —
a `:div`, a `select`, a value-less checkbox, an input with no `:value`. The
refusal is `:rf.error/hicasso-revision-not-controlled`, and it names the
element and the source. The runtime never reads the prop from a forwarded
map, so a caller cannot force a re-baseline on a field whose author did not
write one. The prop never reaches the DOM as an attribute, on the client or
on the server.

This is the single prop and nothing more: no commit or cancel [intents](glossary.md#intent), no
acknowledgement that a reset landed, no caret-policy options. When you want
draft-and-commit behavior — free edits, commit on Enter or blur, cancel on
Escape — use the [forms module](05-forms.md). The module *consumes* this
trigger; it does not extend it.

## Troubleshooting

| Symptom | Error / mechanism | Fix |
|---|---|---|
| Characters drop when typing fast | Something async sat between keystroke and commit — debounce, `setTimeout`, a queued effect | Keep the controlled write synchronous; debounce *consumers* of the value, not the write itself |
| Caret jumps to the end on every keystroke | Value reasserted without caret preservation | Runtime bug, not your view — report it |
| Enter commits half-typed text mid-composition | A hand-written key handler bypassed the [intent](glossary.md#intent) path | Use the data key map ([Events as data](03-events-as-data.md)); the composition check lives there |
| Composition dies when the model refuses mid-draft | On the controlled path this cannot happen — composition is left alone until it ends. Something else wrote the field: a ref, a foreign script, an uncontrolled sibling | Find the second writer; the controlled element must have one |
| An IME commit lands stale text, then corrects itself | Something async sat between keystroke and commit, so the composition's close reconciled against a model the deferred write had not reached | Keep the controlled write synchronous; the composition survives either way — only what it commits is late |
| Typing the "reset" value clears the field | A reset keyed on value equality somewhere in your code | Move [`::h/revision`](glossary.md#hrevision); a value comparison is not a reset |
| The field resets on every render | A revision minted in render — `random-uuid`, a render-order index, a counter the body increments | Read a revision your *events* wrote into app-db |
| The field never resets, and devtools shows a `revision="…"` attribute | A bare `:revision`. The match is the exact namespaced keyword. Every other spelling flows through as an ordinary attribute, silently. A namespaced value loses its namespace on the way, so two distinct revisions can collapse to one | Write [`::h/revision`](glossary.md#hrevision) |
| `:rf.error/hicasso-revision-not-controlled` at render | [`::h/revision`](glossary.md#hrevision) on something that is not controlled text — a `:div`, a `:select`, a value-less checkbox | Put the revision on the controlled `input`/`textarea` whose draft it re-baselines; a select or checkbox resets by writing the model |
| Focus lost after validation fails | Something remounted the node | Never remount to reset — that is exactly what destroys focus |

## When not to control an input

Controlled means that every keystroke writes app-db. On a dense grid, that is
one dispatch per cell per keystroke. Measure that cost before you accept it
(the [performance chapter](18-performance.md) owns the method).

If the user's edit must be a *draft* — commit on Enter or blur, revert on
Escape, reject without loss of the field — do not build that yourself on top
of this page. That is the [forms module](05-forms.md).

If no other code needs the intermediate values — a scratch field in a [modal](glossary.md#overlay),
a filter box that only feeds a debounced query — leave the input uncontrolled
(`:default-value` to seed it, no `:value`) and commit on blur. DOM-owned
state is a legitimate explicit choice. The cost is this: app-db, tests, and
tools cannot see the text in mid-edit.

## Advanced

### Same-turn mechanics, and the one `flushSync`

Dispatch for a controlled element runs synchronously inside the discrete
browser event. Store notification is synchronous too, so React commits the
echo in the same turn. The value is back in the DOM before the browser
finishes the event.

`flushSync` is not a general default. The one exception is the
controlled-text **converge**. The converge flushes the pending commit before
React's end-of-event restore, so value *and caret* are correct in the same
turn — including when the model rejected or normalized the keystroke. That
path runs once per keystroke, on controlled text entry only (an `input` that
has a caret, or a `textarea`, with a non-nil `:value`). It also runs once at
the close of an IME composition. Everywhere else it is inert, and a page with
no [controlled input](glossary.md#controlled-field) pays nothing for it. If your own code needs `flushSync`
for anything else, that is a design problem, not a feature.

### Rejection and React's restore

When the handler returns `nil`, the DOM node can hold the typed character for
a short time. React reasserts the model value when the discrete event ends.
React's restore discards the caret, so the runtime itself restores caret and
selection on that converge path. You still only write `nil`.

### IME composition

Two rules apply. Both are automatic on the controlled-text path and the data
key map:

**An Enter during composition commits nothing.** In mid-composition, Enter
selects an IME candidate; it is not a submit. The key map gates that
centrally. The signal mechanics (native event, legacy fallbacks) are in the
Advanced section of [Events as data](03-events-as-data.md).

**A live composition is not overwritten.** If the model refuses or normalizes
the text that the IME composed so far, nothing writes the field while the
composition runs — not the converge, and not React's end-of-event restore.
Your handler still runs on every composing update. The field continues to
show the composition until the user commits it, and the refusal or
normalization applies whole when the composition ends.

That second rule is a deliberate divergence from plain React. In plain React,
a corrected value written during composition aborts the exchange: the kana in
flight vanish, and the next update starts a fresh composition on a
half-written draft. On a *normalizing* field, plain React does worse than
lose the composition. Each aborted draft is written back, and the IME
composes on top of it. So the sequence `s`, `sh`, commit ends the model at
`SSHSH`, where this runtime commits the `SH` that was typed. The live
composition is excluded from that restore on purpose.

### A reset that cannot land immediately

There are two cases. Both are documented conduct, not bugs.

**In mid-composition.** A revision change that arrives while a composition
runs lands at the close of the exchange: composition end, blur, unmount, or
the next non-composing input. The reason is the same reason that nothing else
writes the field there — the only immediate write available would abort the
composition silently. The model is correct at all times; the screen converges
at the close.

The deferral cannot strand the field: every exit converges the field to the
model current at that time. But the deferral does not promise that the reset
*survives*. On a field that accepts, the model continues to take every
composing update while the reset is pending. So an edit after the reset,
including the composition's own final input, supersedes the reset by ordinary
event order, exactly as it would at rest.

**In mid-hydration.** A revision that moves before the runtime adopts the
server-rendered tree is absorbed. The adoption render reads it as the field's
*first* revision — a change with no predecessor. So nothing fires, the
server's node survives, and any user draft in the field survives with it. The
next revision change after adoption resets the field normally, on that same
node. The field is never stranded. A reset that fell in this window is
swallowed; the caller must send it again.
