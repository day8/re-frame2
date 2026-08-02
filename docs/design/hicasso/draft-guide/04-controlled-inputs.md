# Controlled inputs

> **Draft ahead of the product artefact.** This page teaches the landed surface —
> ruled in [decisions.md](../decisions.md) (HD-001…HD-027), witnessed by the bench
> arm's tests under `implementation/freehand/test/re_frame/bench/hicasso/` — but no
> `implementation/hicasso/` artefact ships yet, and spellings marked **[unfrozen]**
> stay provisional until the API freeze.

A controlled text input is the hardest correctness problem a view layer has, and
almost none of the difficulty is visible in the code you write.

Here is what you write:

```clojure
(defview title-field [{:keys [id]}]
  [:input {:type     :text
           :value    (sub [:todo.ui/draft id])
           :on-input [:todo.ui/edit id ::h/value]}])
```

That's it. Value in from a subscription, intent out to an event — ordinary
`:value` and `:on-input`, no ceremony. The rest of this page is about the
machinery underneath, because you need to know what it guarantees and where it
stops.

## Why this is hard

The value shown on screen lives in app-db, which means every keystroke makes a round
trip: keystroke → dispatch → event handler → app-db commit → subscription →
re-render → DOM value.

If any part of that is asynchronous, you get the classic failure set. Fast typists
drop characters. The caret jumps to the end of the field on every edit. IME
composition breaks mid-word. Reagent pays for this with a dedicated caret-heuristic
module that tracks the last DOM value and repositions the cursor — the comment in
its source says the alternative "gives the user a jarring experience," which is
generous.

## The synchronous door

HD-019 names the mechanism instead of leaving it to taste, because the first
controlled-input commit cannot stall on an undecided design.

A controlled element's intent **dispatches synchronously inside the discrete
browser event**, and the subscription layer's store notification runs
synchronously too, so React commits the echo in the same turn. The value is back
in the DOM before the browser's event handling finishes.

`flushSync` is never the general default, and the one exception is named rather
than left to taste: the controlled-text **converge** flushes the door's pending
commit before React's end-of-event restore, so value *and caret* are correct
in-turn — including when the model rejected or normalised the keystroke. What you
get for free is exactly what the first example shows: ordinary `:value` and
`:on-change`/`:on-input`, no ceremony, and a caret that stays put. The boundary
is equally exact: the exception is one audited call site, reached from the
element path once per keystroke, on controlled text entry only (a caret-bearing
`input` or a `textarea` with a non-nil `:value`), and inert everywhere else.
Needing `flushSync` anywhere else would still be a finding, not an implementation
detail.

## What the door guarantees

Two requirements from the fitness harness, and they are v0's acceptance bar:

**R-A1 — same-tick echo.** A keystroke flows event → commit → re-render such that
the DOM value never lags the accepted keystroke. No dropped characters, no
reordering, under the substrate's scheduling. A substrate that batches or defers
renders must state its input-path exception mechanically — the synchronous door *is*
that statement.

**R-A2 — caret and selection preservation** when the rendered value differs from
what was typed. Reject, transform, reformat: mid-string edits included, and IME
composition is never broken by value reassertion. **Remount-as-reset is
disqualified** — it destroys focus, selection, and composition state, so "just
change the key" is not an available answer here.

Six browser witnesses prove the door, all on a 100-cell editing grid: same-turn
echo, mid-string caret, selection, IME composition (including the keyCode-229
signal), unchanged-model rejection, and async normalisation. K4 in
[validation.md](../validation.md) kills the whole programme if controlled text fails
same-tick echo or IME on Chromium and WebKit for a simple form. This is not a
polish item.

## Rejection: when the model says no

Your handler doesn't have to accept what was typed:

```clojure
(rf/reg-event :order/set-quantity
  (fn [{:keys [db]} [_ id typed]]
    (if (re-matches #"\d*" typed)
      {:db (assoc-in db [:orders id :qty] typed)}
      nil)))                                       ;; rejected — no-op, model unchanged
```

The rejected path leans on **React's own end-of-discrete-event restore** for the
*value*: the DOM node briefly holds the typed character, the model doesn't change,
and React reasserts the model value when the discrete event ends. The **caret**,
though, React's restore throws away — so the runtime takes it itself, in the
element path, which is what the converge above is for. The gap was measured
before it was closed (HD-019 records both beads), and R-A2 is why closing it was
not optional. Either way, none of this is your code: return `nil` from the
handler and the field shows the model, caret intact.

## Forwarding attributes onto a controlled input

Sooner or later you write a `field` wrapper: one place that owns the controlled
contract, and call sites that still need to pass a placeholder, a `name`, a test id.
The remainder rides in one reserved key, `:&`:

```clojure
(defview field [{:keys [id busy?] :as attrs}]
  [:input.form-control {:& (dissoc attrs :id :busy?)   ;; whatever the caller sent
                        :value    (sub [:editor/field id])
                        :disabled busy?
                        :on-input [:editor/edit-field id ::h/value]}])

[field {:id :title :busy? busy? :type "text" :placeholder "Article Title"}]
```

**The literal keys you write always win.** Not "usually", not "if you pick the right
merge form" — always. So a caller who forwards a whole props map, a theme that
supplies part attributes, or a genuinely hostile remainder carrying `:value` and
`:on-input` all reach nothing that matters. Your `:value` is your `:value`.

That is the whole design, and it exists because of the thing it deletes. In the
predecessor there are **three** merge forms and the wrong one is silent: pick the
general spread on a controlled input and caret and IME protection stop, with no
error raised anywhere. Correctness by choice of syntax is the worst kind, because
the code looks fine.

Two consequences worth knowing.

**Say it by not saying it.** When you *want* the caller's value to win, leave the
literal out. `[:input {:& attrs}]` takes everything the caller sent. The default is
the safe direction and the override is the explicit one, which is the way round you
want when the thing being defended is a caret.

**Classes compose on the tag.** `:key` and `:ref` are never taken from `:&`, and a
literal `:class` wins outright like any literal — so put your element's own classes
on the tag (`[:input.form-control {:& attrs}]`) and the shorthand merge combines
them with whatever the caller brought.

**Spelling it differently does not get round it.** The law is enforced on the prop
slot React ends up with, not on the key as written, so a remainder carrying
`"key"`, `:x/ref` or an `:onInput` against your own `:on-input` reaches none of
them. You do not have to think about this; it is here so you know there is nothing
to think about.

## Resets are by revision, never by value

When you need to force a field back to a value — a form reset, a "revert" button, a
server-supplied normalisation — **the trigger is an explicit caller revision, not
value equality.**

The reason is a specific bug. If you reset whenever the incoming value equals some
target, then a user who legitimately types that value gets their field yanked out
from under them, and a rejected same-value reassertion becomes invisible. Explicit
revision means the reset fires exactly when the caller says so: zero stale paints,
loop-impossible, correct on retry.

This is the predecessor's ruled reset law, kept deliberately. The prop that carries
the revision does not have a Hicasso spelling yet; see **Not settled yet**.

## Troubleshooting

This table names mechanisms; the door's failures are behavioural, not error ids.

| Symptom | What went wrong | Fix |
|---|---|---|
| Characters drop when typing fast | The dispatch path went async — a debounce, a `setTimeout`, a queued effect between keystroke and commit | Keep the controlled path on the synchronous door; debounce the *consumer* of the value, not the write |
| Caret jumps to end of field on every keystroke | The value was reasserted without caret preservation | R-A2 territory — a real bug in the runtime, not in your view |
| IME composition breaks mid-word | Value reassertion during composition | The runtime fences composition; a hand-written handler bypassing the intent path won't |
| Typing the "reset" value clears the field | Reset keyed on value equality somewhere | Reset on explicit revision |
| Field goes read-only after an async normalise | The model round-trip didn't complete | Async normalisation is one of the six door witnesses; check the handler returns a `:db` write |
| Focus lost after a validation failure | Something remounted the node | Remount-as-reset is disqualified precisely for this |

## When not to control an input

Controlled means every keystroke writes to app-db. On a 100-cell grid that is 100
event dispatches and 100 commits per pass, which is why the predecessor's grid
example deliberately went uncontrolled. Price it before you reach for it: the
per-keystroke budget in [validation.md](../validation.md) demands a stated path for
both a 4-field form and a 100-cell grid, and "which subs recompute" matters as much
as "which boundaries re-render."

If a field's intermediate values are nobody's business — a search box whose only
consumer is a debounced query, a scratch field in a modal — an uncontrolled input
with a commit on blur is not a compromise. It is the right shape.

## Not settled yet

| Question | Status |
|---|---|
| The revision prop's spelling on a controlled element | **Not addressed.** The reset law is ruled; the attribute that carries the revision is unnamed |
| The buffered / draft-and-commit input ladder | **Post-v0, explicitly.** The charter defers "the full buffered/revision input ladder"; v0 ships R-A1/R-A2 and no more |
| The controls kit that owns drafts and revisions | **Post-v0.** HD-009 leans on it for ephemeral state, and it does not exist in v0 — so in v0 a draft is app-db state you write yourself |
| Which props count as controlled | HD-010's merge law names `:value` and `:checked`; the converge's own scope is exact (caret-bearing `input` or `textarea`, non-nil `:value`); whether the controlled roster is longer is unstated |
| Whether `:on-input` or `:on-change` is the taught attribute | The landed screens write `:on-input` for text and `:on-change` for checkboxes; the choice is convention, not ruling |
