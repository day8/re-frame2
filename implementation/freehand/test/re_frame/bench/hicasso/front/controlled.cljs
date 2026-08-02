(ns re-frame.bench.hicasso.front.controlled
  "THE CONTROLLED-ELEMENT CONVERGE — same turn, caret where the edit left
  it (rf2-fki5d, the residue rf2-n3dxw was left open on).

  Neither shipped path gives a store-backed field both halves of what it
  owes its user. React converges inside the discrete event and throws the
  caret to the end of the control; UIx's port of Reagent's workaround
  keeps the caret and arrives one animation frame late. The matrix is on
  `docs/design/hicasso/studio/controlled-input-two-implementations.md`.

  This is the third behaviour, and it lives in **the element path**
  rather than in a component — which is the whole reason it is cheap.
  [[install!]] is called from
  [[re-frame.bench.hicasso.front.codec/native-element]] on the elements
  it applies to, and wraps the change handler the author already wrote.
  The view is unchanged: an ordinary `:value` / `:on-input` pair, no
  ref, no effect, no escape hatch, and **nothing added to the boundary
  shell**, so HD-020's ≤2-hook budget is untouched. UIx's own answer to
  the same problem is a wrapper *component* per input carrying three
  hooks (`uix/compiler/input.cljs:132-143`).

  ## What runs, and when

  At the end of the change handler — still inside the discrete event,
  and still **ahead of React's own end-of-event restore**:

  1. `flushSync`, so the synchronous door's commit lands now rather than
     in the `finally` of React's `batchedUpdates$1`;
  2. if the field still disagrees with what the element renders, write
     the rendered value — which also makes React's later `updateInput` a
     no-op, because it only assigns when the two differ
     (`react-dom-client.development.js:1661-1667`), and a no-op there is
     what lets the caret survive;
  3. put the caret back **by offset from the END of the string**, which
     is Arm 2's algorithm and Reagent's before it — the offset a
     normalisation that changes the length preserves, where an absolute
     position does not.

  ## The trap, which is measured rather than assumed

  Step 2 has to know what the element renders **now**, and neither
  obvious source answers it.

  The handler's own closure carries the value from the render that
  *minted* it. That value is correct at the instant the handler is
  invoked — React dispatches to the props it last committed — and
  **stale the moment step 1 commits a new one**. Writing it back is not
  a missing improvement, it is a regression: on a keystroke the model
  took verbatim it wipes the character the user just typed.
  `front/controlled_dom_cljs_test` reproduces exactly that, by handing
  [[converge-to!]] the closure's value instead of the record's — one
  argument different, and the accepted keystroke disappears.

  Comparing the field against what the handler saw does not answer it
  either. `(= (.-value node) dom-value)` is **true in both cases**: on a
  refusal nothing re-rendered, so the field still shows the typed value;
  on a keystroke the model took verbatim the field shows the typed value
  *because that is what was committed*. The same reading, two opposite
  obligations.

  ## Where the per-instance record lives, and why it costs nothing

  The record is `node.defaultValue`, and **React already maintains it**.
  Whenever an `<input>` or `<textarea>` is genuinely controlled, every
  commit mirrors the committed `value` prop into the element's default
  value:

  | line (`react-dom@19.2.0`, `cjs/react-dom-client.development.js`) | what happens |
  |---|---|
  | `1671-1672` | `updateInput`: `null != value` → `setDefaultValue(element, type, value)` |
  | `1737-1741` | `setDefaultValue` assigns `node.defaultValue` unless it already matches |
  | `1721` | `initInput` sets it on mount, so the record exists before the first event |
  | `1842-1851` | `updateTextarea` does the same, when no `defaultValue` prop was written |

  It is the element's own bookkeeping — the value a `form.reset()` would
  return the field to — and it is exactly *the value the element last
  rendered*, per instance, on the node, with no `ref`, no `WeakMap`, no
  prop and no hook to keep it. Typing does not disturb it: the `value`
  IDL setter sets the value and the dirty flag, never the content
  attribute `defaultValue` reflects.

  **So the price the option was quoted at is not charged here.** What
  remains is the dependency, and [[last-rendered]] is the one place that
  names it. `front/controlled_dom_cljs_test` asserts the invariant
  directly, on a live element, across a re-render that moves the value —
  so if React ever stopped mirroring, a row named for the invariant goes
  red rather than five rows going subtly wrong.

  ## What [[install!]] refuses, and why each refusal is the record's

  Every guard below exists to keep [[last-rendered]] honest. Read them
  as *the conditions under which React's mirror is the rendered value*:

  - **`input` and `textarea` only.** A `select` has no text cursor to
    restore and no `defaultValue` mirror; nothing here applies to it.
  - **A `value` prop that is present and non-nil.** Absent, the element
    is uncontrolled, React writes no mirror, and there is nothing to
    converge *to*.
  - **No `defaultValue` prop of the author's.** On a `<textarea>` React
    honours it over the mirror (`:1852-1853`), so the record would be
    the author's constant rather than the rendered value.
  - **A type whose text cursor exists** — `text`, `search`, `url`,
    `tel`, `password`, or no `:type` at all, which is `text`.
    `setSelectionRange` is not applicable to the others, and
    `setDefaultValue` deliberately skips a focused `number` field
    (`:1738`), so the two exclusions are the same exclusion. React's own
    restore still converges the *value* on those types; there was never
    a caret there to lose.

  A guard that does not hold means no wrapper at all — not a wrapper
  that quietly does less. The element then behaves exactly as it did
  before this namespace existed.

  ## The guard that has to be taken twice

  Every condition above is read off the props that MINT a wrapper, and
  those props are one render old the moment step 1 returns — because
  step 1 *is* a render. A synchronous handler can re-render the same
  `<input>` from `text` to `number`; React keeps the node and updates
  the attribute, so a wrapper installed on a caret-bearing element
  goes on running against one that is not.

  So [[converge!]] asks the caret question again after the flush.
  Answered no, it does nothing at all — which matters twice over:
  `setSelectionRange` throws `InvalidStateError` on such a type, and
  `setDefaultValue` skips a focused `number` field (`:1738`), leaving
  the record holding what the *text* render wrote rather than what
  the element now renders. The throw is the measured defect;
  `arm1_controlled_grid_dom_cljs_test/a-type-change-inside-the-flush-leaves-the-converge-inert`
  quotes it and reds by name without this reading.

  ## The one handler, and the door

  `onChange` when the author wrote one, `onInput` otherwise. React's
  `SimpleEventPlugin` extracts `onInput` before `ChangeEventPlugin`
  extracts `onChange`, so wrapping the later of the two is what puts the
  converge after every handler the element has rather than between them.

  **The synchronous door only**, and that is a property of re-frame2
  rather than a limitation of this code: `dispatch` drains on a
  macrotask (Spec 002 §Drain scheduling), so at the end of the change
  handler the model has not moved, `flushSync` has nothing to flush, and
  the record still reads what the element rendered before the keystroke.
  The wrapper is a no-op there, correctly — a queued field converges one
  macrotask later, as it always did.

  An out-of-band correction — a server normalisation, a debounced
  validation — fires no change event, so nothing here runs and React's
  own restore is still what converges it. That is why a *range* selection
  still collapses across such a write: this converge is not on that path,
  and restoring two offsets is a different algorithm from the one it
  runs (rf2-n3dxw).

  ## Composition, measured (rf2-o27h3)

  A composing IME produces exactly the `input` events this wrapper runs
  on, so its conduct mid-composition is a measured property, not an
  intention — `bench/hicasso/ime_run.cjs` drives real CDP composition
  at it beside plain React and the UIx port. Measured: on a
  model-agreeing field the exchange **survives** — the value write never
  fires (and an equal write is short-circuited by the browser's own
  setter anyway), and the unconditional `setSelectionRange` did not
  disturb the composition range. On a field whose model refuses or
  normalises, the write lands **mid-composition and silently destroys
  the exchange** — precisely as React's own end-of-event restore does on
  the same field in the same turn, and as the UIx port does one frame
  later. Nowhere worse than the baseline, asserted comparatively; not
  composition-fenced either, same as the baseline. Whether this path
  should carve composition out — suppress the converge while
  `isComposing`, converge once at `compositionend` — is a behavioural
  choice inside HD-019's exception scope awaiting a ruling: rf2-digtt."
  (:require ["react-dom" :as react-dom]))

(defn- noop [])

;; ---------------------------------------------------------------------------
;; The per-instance record
;; ---------------------------------------------------------------------------

(defn last-rendered
  "**The value this element's last render put on it**, per instance, read
  off the node.

  React's mirror of the committed `value` prop — see the table in the
  namespace docstring for the four lines of `react-dom` that maintain
  it. The one place this namespace depends on that behaviour, so it is
  the one place a row has to pin, and
  `front/controlled_dom_cljs_test/the-record-is-the-elements-own-and-is-not-the-closures`
  pins it.

  `js/undefined` on anything that is not a live form control — a
  synthetic target in a unit test, an element React never controlled —
  which is what makes [[converge!]] inert there rather than defensive
  about it."
  [node]
  (.-defaultValue node))

;; ---------------------------------------------------------------------------
;; The converge
;; ---------------------------------------------------------------------------

(defn converge-to!
  "Make `node` show `rendered`, and put the caret back by offset from the
  END of the string.

  Split out from [[converge!]] because `rendered` is the whole of the
  design: hand it [[last-rendered]] and every row of the family lands;
  hand it the change handler's own closure value and an accepted
  keystroke is wiped off the screen. The test that reproduces the trap
  calls this function twice with one argument different, which is the
  shortest true statement of it.

  The offset is taken from the end because that is the half a
  normalisation preserves. `\"1,234\"` + `5` becomes `\"12,345\"` — one
  character longer than what was typed — and the caret belongs after the
  `5`, which is `0` from the end in both strings and `6` in neither.

  Writing the value only when it differs is not a micro-optimisation:
  assigning `value` moves the cursor to the end of the control, so a
  write that changed nothing would undo the caret this function is here
  to restore."
  [node dom-value caret-was rendered]
  (when-not (= (.-value node) rendered)
    (set! (.-value node) rendered))
  (let [offset (- (count dom-value) caret-was)
        c      (max 0 (- (count (.-value node)) offset))]
    (.setSelectionRange node c c))
  nil)

(defn converge!
  "Converge `node` against the model, inside the discrete event.

  `dom-value` and the caret are read FIRST, because step 1 may write
  both: they are what the user's edit left behind, and the caret is
  only meaningful as an offset into that string.

  Three readings make this inert rather than wrong where it does not
  apply. A caret that is not a number is not a text-entry control — a
  synthetic target in a unit test, or a type whose selection is not
  applicable. A record that is not a string is an element React never
  wrote a mirror for. And the caret is read a SECOND time, after the
  flush, because the flush is a render.

  ## Why the caret is read twice

  [[install!]] decided this element was caret-bearing from the props
  that minted *this* wrapper, and those props are one render old the
  moment `flushSync` returns. A synchronous handler may re-render the
  same `<input>` from `text` to `number` — React keeps the node and
  updates the attribute, so the wrapper from the text render is still
  the one running, against an element that no longer has a caret.

  Both halves of the converge are wrong there. `setSelectionRange`
  throws `InvalidStateError` on a type whose selection is not
  applicable — that is the measured failure, and it is the whole of
  why this is a correctness fix rather than a tidy-up. And the record
  is stale rather than merely unused: `setDefaultValue` deliberately
  skips a focused `number` field (`react-dom@19.2.0:1738`), so
  `defaultValue` still holds what the *text* render put there. The
  converge would write that over the field mid-event; React's own
  end-of-event restore happens to put the committed value back
  afterwards, so the field survives — but only by accident, and the
  write was never this code's to make.

  So the post-flush reading is not a guard bolted onto the caret
  restore; it is the same question [[install!]] asked, asked again at
  the only other moment it can change. Answered no, the element is
  left exactly as React leaves it — which is what it would have been
  had it been minted as a `number` field to begin with."
  [node]
  (let [dom-value (.-value node)
        caret-was (.-selectionStart node)]
    (when (number? caret-was)
      (react-dom/flushSync noop)
      (let [rendered (last-rendered node)]
        (when (and (string? rendered)
                   (number? (.-selectionStart node)))
          (converge-to! node dom-value caret-was rendered)))))
  nil)

;; ---------------------------------------------------------------------------
;; Installation — what the element path calls
;; ---------------------------------------------------------------------------

(def ^:private caret-types
  "The `<input>` types with a text entry cursor — the ones
  `setSelectionRange` applies to. Everything else has no caret to lose,
  and React's own restore already converges its value."
  #{"text" "search" "url" "tel" "password"})

(defn- convergeable-tag?
  "One JS `switch` on the tag string, so every `[:div …]` on the page
  pays exactly that and nothing else."
  [tag]
  (case tag
    ("input" "textarea") true
    false))

(defn- caret-type?
  "Does this element have a text cursor? A `<textarea>` always does; an
  `<input>` does for the five applicable types, and for no `:type` at
  all, which is `text`."
  [tag js-props]
  (or (identical? "textarea" tag)
      (let [t (unchecked-get js-props "type")]
        (or (nil? t) (contains? caret-types t)))))

(defn- change-slot
  "The slot whose handler runs LAST for one keystroke, or nil when the
  element has no change handler and so nothing to converge after."
  [js-props]
  (cond
    (fn? (unchecked-get js-props "onChange")) "onChange"
    (fn? (unchecked-get js-props "onInput"))  "onInput"
    :else                                     nil))

(defn- convergeable?
  "Is this element one whose rendered value React records on the node?
  See the namespace docstring — each clause is a condition on the
  record, not a taste about which elements deserve the behaviour."
  [tag js-props]
  (and (convergeable-tag? tag)
       (some? (unchecked-get js-props "value"))
       (nil? (unchecked-get js-props "defaultValue"))
       (caret-type? tag js-props)))

(defn install!
  "Wrap the change handler on `js-props` so the element converges in-turn
  with the caret intact. Mutates the props object the codec has just
  built and is about to hand to `createElement`, and returns it.

  A fresh wrapper per render is the same shape the codec already has for
  every lowered intent, and it closes over nothing but the author's
  handler — deliberately **not** over the value, which is the stale
  reading [[converge-to!]] exists to keep out of the write."
  [tag js-props]
  (when (convergeable? tag js-props)
    (when-some [slot (change-slot js-props)]
      (let [handler (unchecked-get js-props slot)]
        (unchecked-set js-props slot
                       (fn hicasso-converging-change [e]
                         (handler e)
                         (when-some [node (.-target e)]
                           (converge! node))
                         nil)))))
  js-props)
