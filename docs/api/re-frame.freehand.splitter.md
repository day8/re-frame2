# re-frame.freehand.splitter

!!! warning "Pre-alpha — `day8/re-frame2-freehand` is not published"

    Freehand ships inside the re-frame2 monorepo and is **not published to
    Clojars**, and there is no date at which it will be. You resolve it with
    `:local/root` from a checkout — see
    [Install](../core/freehand/get-running/install.md). The public surface is deliberately
    still open: verbs can change while we learn from real apps.

`re-frame.freehand.splitter` is the control kit's **pointer witness** (EP-0036;
artefact `day8/re-frame2-freehand`, conventionally aliased `split`) — a
resizable pane divider.

[`re-frame.freehand.controls`](re-frame.freehand.controls.md) holds the kit's
*form* controls: both read a `form/field` projection and both sit inside the
controlled-input door. A splitter reads no form and enters no door. What it is a
witness for is the other half of the same slice — a control whose gesture is a
**pointer drag**, and whose keyboard path is the *same control* rather than a
second one bolted to the side of it.

Everything on this page is **advanced** tier. A resizable pane divider is opt-in
layout machinery, not the day-one set: reach for it when the problem appears.

```clojure
(:require [re-frame.core :as rf]
          [re-frame.freehand :as v]
          [re-frame.freehand.splitter :as split])

[split/splitter {:split      (v/sub [:layout/split])
                 :on-start   [:layout/split-started]
                 :on-preview [:layout/split-moved]
                 :on-commit  [:layout/split-committed]
                 :on-cancel  [:layout/split-cancelled]}]
```

and the four handlers behind it, which is the whole application:

```clojure
(rf/reg-event :layout/split-started
  (fn [{:keys [db]} _] {:db (update db :layout/split split/start)}))
(rf/reg-event :layout/split-moved
  (fn [{:keys [db]} [_ at]] {:db (update db :layout/split split/move at)}))
(rf/reg-event :layout/split-committed
  (fn [{:keys [db]} [_ at]] {:db (update db :layout/split split/commit at)}))
(rf/reg-event :layout/split-cancelled
  (fn [{:keys [db]} _] {:db (update db :layout/split split/cancel)}))
```

## Two clocks, and you pick where the boundary is

A pointer offers moves at the host's rate — 60, 120, 240 a second, more under
coalescing — so a splitter that turned each one into a domain event would make
every drag a burst of reducer work whose size is a property of the user's
hardware.

An **offer** is therefore not an **intent**. Every offer is settled first —
clamped and quantized by [`settle`](#settle) — and an intent is produced only
where the settled value *differs* from the one being rendered. Offers landing
inside the current step, or past a bound already reached, produce nothing at all.

You then choose the stream you want by wiring, or not wiring, one prop:

| `:on-preview` | what app-db sees during a drag |
|---|---|
| wired | one intent per accepted step — the split tracks the pointer |
| absent | nothing — the pane moves once, at `:on-commit` |

Neither is a mode. `:on-commit` carries the settled value either way, so a call
site wiring only `:on-commit` is complete. There is no throttle, no scheduler and
no timing verb, because the reduction is arithmetic you can call.

## The keyboard is the same control

Everything directional goes through two pure functions, and the application never
sees which device produced a value: [`key-intent`](#key-intent) turns a key into
a move, [`intent-at`](#intent-at) applies it, and both end at the same
[`settle`](#settle) the pointer's offers end at, under the same bounds and the
same right-to-left mirror. A keystroke and a one-step drag leave app-db in the
same state.

The one asymmetry is real rather than an oversight: **a keystroke is a whole
gesture.** It has no start and no end to report, so it produces the terminal
intent and nothing else, while a drag reports `:on-start`, its accepted moves,
and then `:on-commit` or `:on-cancel`.

## Where the state is

In your frame, at a path you chose. There is no local state system, no host slot
and no controller record: `:split` is `{:at … :baseline … :dragging? …}`, moved
by the transitions below and persisted by whatever you persist with.

**Pointer capture is routing, not authority.** `setPointerCapture` on
`pointerdown` is what makes every later move for that pointer arrive at this
element wherever on screen the pointer travels — which is why this control adds
no `window` or `document` listener, and therefore has nothing to remove at
unmount. It is taken best-effort: a host that declines degrades to the ordinary
bubbling path, and every law below still holds, because none of them was ever
decided by the capture.

**Liveness is decided in the handler, against committed state.** [`move`](#move)
is inert unless the value says a gesture is live, so a late preview changes
nothing and a [`cancel`](#cancel) *beats* it rather than racing it. An
application ending a drag for its own reasons — a route leaving, a layout
replaced — works for free: clearing `:dragging?` **is** the end of the gesture.

## The control

### `splitter`

```clojure
[split/splitter {:split      (v/sub [:layout/split])
                 :orientation :horizontal
                 :bounds     {:min 0.15 :max 0.85 :step 0.005}
                 :on-start   [:layout/split-started]
                 :on-commit  [:layout/split-committed]}]
```

One `role="separator"` element, placed by you between two panes you lay out.

| prop | |
|---|---|
| `:split` | **required** — the value [`init`](#init) makes |
| `:on-commit` | **required** — the settled position at the end of a gesture |
| `:on-start` | optional — the gesture began |
| `:on-preview` | optional — one intent per accepted step during a drag |
| `:on-cancel` | optional — the gesture was abandoned |
| `:orientation` | optional — `:horizontal` (default) or `:vertical` |
| `:rtl?` | optional — mirror the arrows and the pointer axis |
| `:bounds` | optional — folded over [`default-bounds`](#default-bounds) |
| anything else | forwarded through `v/spread-safe` |

The track it measures is the separator's **parent** — a split layout is a
container holding a pane, this separator and another pane, so measuring the
parent measures exactly the box the two panes divide. The control therefore owns
no layout, no pane sizing and no measurement prop.

Everything visual — the grip, the hit area, the cursor, the highlight while
dragging — is ordinary CSS against `[data-component="re-frame/splitter"]` and
`[data-dragging]`, or ordinary children. This is a control, not a widget
catalogue.

## The arithmetic

### `settle`

```clojure
(split/settle 0.4237 {:min 0.0 :max 1.0 :step 0.01})   ;=> 0.42
(split/settle 1.4    {:min 0.0 :max 0.8 :step 0.01})   ;=> 0.8
```

The raw position as the split it actually **names**: clamped into `:min`/`:max`
and quantized to `:step`. Total — every real number answers a split inside the
bounds.

This is the whole two-clock reduction. A pointer's offers are settled and
compared against the split on screen, so an offer settling to the value already
rendered produces no intent — which is most of them, and is why a drag across a
pane costs one intent per step rather than one per frame.

### `key-intent`

```clojure
(split/key-intent "ArrowRight" {:orientation :horizontal})            ;=> [:step 1]
(split/key-intent "ArrowRight" {:orientation :horizontal :rtl? true}) ;=> [:step -1]
(split/key-intent "ArrowRight" {:orientation :vertical})              ;=> nil
(split/key-intent "Home"       {:orientation :horizontal :rtl? true}) ;=> [:to :min]
```

The keyboard law as a pure function of the key and the geometry. Answers a move
— `[:step ±1]`, `[:page ±1]`, `[:to :min]`, `[:to :max]` — or `nil` for every key
the splitter does not claim.

**Only the arrow keys are physical**, so only they are mirrored under `:rtl?`.
`PageUp` / `PageDown` / `Home` / `End` name the *value* — smaller, larger,
minimum, maximum — and a mirrored `Home` would be a bug in every writing
direction.

This is a *different law* from
[`controls/key-intent`](re-frame.freehand.controls.md#key-intent), which is the
buffered field's Enter/Escape rule. Two laws with one good name, kept apart by
living in two namespaces rather than by being prefixed inside one.

### `intent-at`

```clojure
(split/intent-at [:step 1] 0.42 (split/bounds {}))   ;=> 0.43
(split/intent-at nil       0.42 (split/bounds {}))   ;=> nil
```

The split a [`key-intent`](#key-intent) move names, applied to the current
position. It ends at [`settle`](#settle), which is the point: the keyboard
reaches positions through the same clamp and the same quantum a pointer offer
does, so the two paths cannot drift apart by rounding differently.

### `fraction-at`

```clojure
(split/fraction-at {:x 120 :y 40}
                   {:left 0 :top 0 :width 400 :height 200}
                   {:orientation :horizontal})        ;=> 0.3
```

The **raw** fraction of a rect that a point names, along the axis
`:orientation` gives — mirrored under `:rtl?` by the same mirror
[`key-intent`](#key-intent) applies to an arrow.

Unclamped and unquantized: a point outside the rect answers a fraction outside
0–1, and [`settle`](#settle) is what decides. Keeping the two apart is what lets
a bound be proven *as* a bound rather than inferred from a clamp that already
happened. `nil` where the rect has no extent along that axis.

## The transitions

Five ordinary functions over an ordinary value. Your handlers call them; nothing
here touches a frame, a host or a registry.

### `init`

```clojure
(split/init 0.5)   ;=> {:at 0.5 :baseline 0.5 :dragging? false}
```

Open a split value. `:at` is what is rendered, `:baseline` is what a cancel
restores, `:dragging?` is the application's half of the gesture fence. Three
keys, all readable, all ordinary — a subscription over any of them is a plain
`reg-sub`, and the whole value serializes.

### `start`

```clojure
(split/start s)
```

Begin a gesture: mark it live and take the current `:at` as the baseline a cancel
would restore.

**Idempotent**, because a `pointerdown` while a drag is already live is a second
finger rather than a new gesture, and re-baselining there would quietly make the
first finger's movement unrestorable.

### `move`

```clojure
(split/move s 0.43)
(split/move s 0.43 {:min 0.15 :max 0.85 :step 0.005})
```

Move a **live** gesture. A move arriving when no gesture is live changes nothing
— the pure half of the two-owner fence. That is not defensive coding: an accepted
offer and the frame it is accepted against are a tick apart, so a preview
dispatched just before a cancel legitimately lands just after it, and the handler
has to be the thing that decides.

### `commit`

```clojure
(split/commit s 0.43)
(split/commit s 0.43 {:min 0.15 :max 0.85 :step 0.005})
```

End the gesture at a position, **keeping** it: `:at` and `:baseline` both settle
there and the gesture is no longer live.

It commits from a non-live state too, which is what makes a keystroke a whole
gesture — one call, no start to pair with, the same terminal the pointer reaches.

### `cancel`

```clojure
(split/cancel s)
```

End the gesture, restoring the baseline it started from.

Ending twice is ending once, and cancelling a gesture that never started restores
the baseline the value already had — so you may cancel from a route change, an
`Escape` of your own, or a lost connection without asking whether a drag is in
flight.

## Bounds

### `bounds`

```clojure
(split/bounds {:min 0.15})
;=> {:min 0.15 :max 1.0 :step 0.01 :page-step 0.1}
```

A partial bounds map folded over [`default-bounds`](#default-bounds) — the one
place completion happens, so nothing downstream has to ask whether a key is
there.

### `default-bounds`

```clojure
split/default-bounds
;=> {:min 0.0 :max 1.0 :step 0.01 :page-step 0.1}
```

The bounds a splitter uses when you name none: the whole track, in one-percent
steps, ten percent to a page.

`:step` is the control's **quantum** and both paths use it — an arrow key moves
one, and a pointer offer is accepted only when it crosses one. A single quantum
is what makes "a keystroke and a one-step drag agree" a statement about the
control rather than a coincidence of two independently-rounded numbers.

## Theming

### `component-id`

```clojure
split/component-id   ;=> "re-frame/splitter"
```

The `data-component` scope every part id is addressed under.

### `parts`

```clojure
split/parts   ;=> #{"separator"}
```

The public part roster — a deliberate subset, and here a subset of one, because
the control renders one element and everything inside it is your children.

A part id is API: a stylesheet reaching `[data-part="separator"]` breaks silently
if it is renamed, exactly as a prop does.

## The absences, which are part of the contract

- **No blur handler.** A drag's liveness is the capture, which a blur does not
  touch, and a keystroke is already complete when it ends. Nothing is pending at
  a blur, so there is nothing to flush.
- **No unmount event, and no cleanup hook to hang one on.** The control holds
  nothing that outlives its node: the capture dies with the element and the value
  belongs to the owner. Cleanup follows the *owner* — the route, the workflow,
  the record — exactly as
  [`controls/release`](re-frame.freehand.controls.md#release) does for a form.
- **No `Escape` during a drag.** The cancel a browser reports is
  `pointercancel`, and it is wired. An application wanting a key to abandon a
  drag dispatches its own cancel event from its own key handler — which works,
  completely, because `:dragging?` is ordinary application state and
  [`cancel`](#cancel) is an ordinary function.

## Coming from re-com

re-com's `h-split` / `v-split` own the panes, the layout and the percentage, and
hand you `:on-split-change`. This owns none of that: the layout is yours, the
value is yours, and the splitter is one child you place between two panes. The
mapping is small — `:split-perc` is `:at` as a 0–1 fraction, `:on-split-change`
is `:on-commit`, and `:margin` / `:width` / `:class` are CSS on your own element
— and deliberately not an API-compatible one. There is no `h-split` here to wrap
your panes, because a layout DSL is what re-frame2 is trying not to have.

## Related

- [`re-frame.freehand.controls`](re-frame.freehand.controls.md) — the kit's form
  controls, the other half of the same slice.
- [`re-frame.freehand`](re-frame.freehand.md) — the one public door.
- [Spec 004 §The first-party control
  kit](https://github.com/day8/re-frame2/blob/main/spec/004-Views.md) — the
  normative contract.
