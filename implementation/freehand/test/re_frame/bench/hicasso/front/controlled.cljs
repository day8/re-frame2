(ns re-frame.bench.hicasso.front.controlled
  "THE CONTROLLED-ELEMENT CONVERGE — same turn, caret where the edit left
  it (rf2-fki5d, the residue rf2-n3dxw was left open on), and a live IME
  composition carved out of it (rf2-digtt).

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
  shell**, so HD-020's ≤2-hook budget is untouched.

  One thing is *not* in the element path, and [[shadow-component]] says
  why: React's own end-of-event restore is not ours to skip, so the
  composition carve-out needs a fiber of its own. It is one internal
  component carrying one `useState`, standing in front of controlled
  `input`/`textarea` elements and nowhere else — still not the boundary
  shell, and still not the author's to know about. UIx's answer to the
  *caret* problem is a wrapper component per input carrying three hooks
  (`uix/compiler/input.cljs:132-143`); this one is not that wrapper, and
  the caret half of the mechanism is still the element path's.

  **That hook is priced rather than asserted away.**
  `shapes/hook_budget_dom_cljs_test` counts every hook React is asked for
  across each tier-1 shape and separates the two questions: the shell
  budget is still `2 × boundaries` in the declared order, and the
  shadow's `useState` is counted per controlled TEXT FIELD from a number
  the file declares. It also asserts that no hook is ever interleaved
  into a shell's pair, which is what makes \"not in the shell\" a
  measurement. A page with no controlled input pays nothing.

  ## What runs, and when

  At the end of the change handler — still inside the discrete event,
  and still **ahead of React's own end-of-event restore** — unless the
  event arrived mid-composition, which is [[composing-input?]]'s
  question and the whole of the carve-out's first half:

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
  - **A change handler to run after.** No handler, no end of a handler
    to converge at.

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

  **The same measurement is why [[shadow-component]] stands in front of
  every controlled `input`/`textarea` rather than only the convergeable
  ones.** That row asserts React kept the NODE across the type change —
  a premise that holds only while the element's React *type* is stable,
  and a component chosen by a predicate reading `:type` would flip from
  the wrapper to the bare tag under exactly that keystroke, remounting
  the field and taking the focus with it. So the component question is
  the tag and the controlled `value`, and the `:type` question is asked
  inside, where a wrong answer costs nothing.

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

  ## The composition carve-out (rf2-digtt), and why it is two halves

  A composing IME produces exactly the `input` events this wrapper runs
  on. `bench/hicasso/ime_run.cjs` drives real CDP composition at it
  beside plain React and the UIx port, and what it measured is the whole
  reason the carve-out exists: on a field whose model **refuses or
  normalises** the composition text, the value written back lands
  mid-composition and **silently destroys the exchange** — no
  `compositionend`, the in-flight kana gone, a fresh `compositionstart`
  on the IME's next update. Plain React does it in the same turn through
  its own restore; the UIx port does it one frame later.

  The operator ruled the carve-out IN (2026-08-03, recorded as an
  addendum to HD-019): controlled-text convergence is suppressed while a
  composition is live, and the field converges ONCE at `compositionend`
  against the then-current model. A composition is a browser-owned draft
  of the user's; destroying it silently is not a parity target worth
  keeping. **On a refusing or normalising field this runtime therefore
  diverges from plain React deliberately** — the composition survives to
  its commit and the refusal lands whole, visibly, at `compositionend`.

  It takes two halves because **two different writes destroy the
  exchange, and only one of them is ours**:

  1. **This converge**, suppressed by [[composing-input?]] at the end of
     the change handler. One reading, one branch.
  2. **React's own end-of-discrete-event restore**, which is not ours to
     skip. `ChangeEventPlugin` banks a restore target for every
     controlled change; the `finally` of `batchedUpdates$1` flushes
     pending sync work and then hands the committed props to
     `updateInput`, which assigns `element.value` **whenever it differs
     from the controlled value**. Skipping only half (1) falls through to
     exactly the plain-React conduct the harness measured aborting the
     exchange — which is why a bare `when-not composing` is proven
     insufficient rather than merely incomplete.

  [[shadow-component]] is half (2), and it is stated as a property
  rather than as a trick: **while a composition is live, the value React
  sees agrees with the live DOM draft**, so React's restore finds
  nothing to write and its own `element.value !== value` guard is what
  does the skipping. Nothing here reaches into React's internals, mutates
  props React holds, or intercepts a value setter.

  ## The shadow is released unconditionally, and that is the safety rider

  The worst thing this could degrade to is a field stuck showing a draft
  the model never agreed to. It cannot, because **every path out of a
  composition releases the shadow** and there is no path that only
  *some* of them cover:

  - `compositionend` — the ordinary end, commit or cancel alike;
  - a change event that is **not** composing — which is what recovers a
    composition some *other* value write aborted silently, since the
    abort itself fires nothing;
  - `blur` — the composition the browser abandoned with the focus;
  - unmount, for free: the shadow is this component's own `useState` and
    cannot outlive the element that holds it. There is no registry, no
    node property and no module-level record to strand.

  A release is `set-shadow(nil)`, and a release when nothing is held is
  free — React bails out of an update to an identical state. So the
  degenerate outcome is a converge, which is exactly today's conduct.

  The release also runs **before** the author's handler at every slot
  that has one, so a handler that throws cannot strand a shadow either.
  `arm1_controlled_grid_dom_cljs_test` §7 witnesses the blur, the
  non-composing keystroke and the unmount paths in a real React tree.

  ## The revision prop, and why it costs this file one read (rf2-zq8kh)

  `::h/revision` re-baselines the field to the model on an explicit
  caller revision change, and NEVER on value equality — HD-019's reset
  law, kept from D016. The whole of its delivery is
  [[install!]]'s one `unchecked-get`: **zero new machinery**, because the
  transport already exists. The codec mints a fresh props object per
  element per render, React marks a host update on props identity, and
  the commit's `updateInput` assigns whenever the DOM disagrees — so a
  revision change re-runs the body, the re-run re-commits the element,
  and the commit re-asserts the model over whatever draft was in the
  field. No hook, no ref, no comparison record, no keyed re-render, and
  **no third `flushSync` site**.

  The last of those is worth stating as the mechanism clause rather than
  as a tally, because this file already reads the caret twice and the two
  are not in tension. HD-019 grants the `flushSync` exception to an
  AUDITED MECHANISM, not to a count: [[converge!]] holds the one
  `flushSync` expression in the namespace, it is reached from exactly two
  call sites (the keystroke path and the `compositionend` path), at most
  one fires per event, and the second caret read inside it is the same
  element in the same exchange. The revision adds no call site to that
  set. In-turn resets ride the keystroke converge; deferred resets ride
  the `compositionend` site; out-of-band resets ride ordinary commits.

  ### Mid-composition, the reset defers to the exchange's close

  Not a new deferral — the carve-out's own, inherited, with no new
  machinery and no revision-comparison state. The argument is mechanical
  before it is philosophical: **there is no cancel primitive to build an
  immediate variant from.** The only immediate write available is
  `element.value`, and mid-composition that write silently aborts the
  exchange — no `compositionend`, a fresh `compositionstart` on the IME's
  next update — which on a normalising field corrupts the commit, the
  measured `SSHSH` row. So a revision arriving mid-composition lands at
  the close, through every release path this file already has:
  `compositionend`, a non-composing change, blur, unmount.

  **The honest limit, stated rather than overclaimed.** The deferral
  cannot STRAND the field — every exit converges it to the then-current
  model. It cannot promise the reset survives: on an accepting field the
  model keeps taking every composing update while the shadow is held, so
  a post-bump dispatch — including the composition's own final input
  event — supersedes the reset by ordinary event order, exactly as it
  would at rest, and discarded pre-reset content can ride back in through
  the draft echo. \"The reset cannot be lost\" is false; \"the deferral
  cannot strand the field\" is what is true.

  ### Mid-hydration, the reset defers past adoption — for React's reason

  The design claimed a mid-adoption revision would land on the first
  post-adoption commit, on the SERVER's node. It does not: React discards
  the server's node when any client render arrives before adoption has
  completed. The witness runs a control arm to place the blame correctly
  — an identical mid-adoption render carrying an UNCHANGED revision loses
  the node the same way — so this is adoption's conduct rather than the
  prop's, and the revision neither causes it nor escapes it. The
  documented conduct is the deferral, which was pre-committed rather than
  improvised. After adoption a bump keeps the node and lands the reset,
  and that is the case the shipped feature rests on."
  (:require ["react" :as react]
            ["react-dom" :as react-dom]))

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
;; The composition reading
;; ---------------------------------------------------------------------------

(defn composing-input?
  "Did this change event arrive in the middle of a live IME composition?

  Read off the **native** event, for the reason
  [[re-frame.bench.hicasso.front.intent/composing?]] states at length:
  React hands a handler a synthetic event built by copying an enumerated
  interface, and what is not on the list is not on the event.

  This is deliberately **not** that gate, and not a second spelling of
  it. The key-map's gate answers a KEY event, and it needs the legacy
  keyCode-229 signal because that is all some IMEs send on a keydown. An
  `input` event has no `keyCode` to read, and every browser that fires
  composition events at all sets `isComposing` on the input events a
  composition produces. Two different events, two readings; folding them
  together would put a key signal on a path that cannot produce one.

  A raw DOM event has no `nativeEvent`, and a synthetic `#js {:target …}`
  from a node-side row has neither — so both read *not composing*, which
  is what keeps every row written before the carve-out reading as it did."
  [e]
  (true? (some-> (.-nativeEvent e) (.-isComposing))))

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
  had it been minted as a `number` field to begin with.

  ## The two call sites, and why they are one behaviour

  Since rf2-digtt this runs at the end of a change handler that is
  **not** composing, and again once at `compositionend`. HD-019 grants
  the `flushSync` exception to an audited call site rather than to a
  count, and its addendum audits the second: same element, same
  namespace, same door, and at most one of them per event. The
  composition path is the keystroke path with its convergence deferred
  to the end of the exchange — not a second mechanism."
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
;; The guards
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

(defn- controlled-text-tag?
  "Is this a form control React mirrors a controlled `value` onto?

  **The `:type` is deliberately not asked**, and that omission is the
  whole difference between this and [[convergeable?]]. This predicate
  chooses a React element TYPE, and an element type that changed under a
  live field would remount it — losing the focus, the selection and any
  composition in flight — where the attribute change React actually
  performs loses nothing. The type-flip row in
  `arm1_controlled_grid_dom_cljs_test` measures precisely that node
  identity, and a `text` → `number` keystroke is the case it measures."
  [tag js-props]
  (and (convergeable-tag? tag)
       (some? (unchecked-get js-props "value"))))

(defn- convergeable?
  "Is this element one whose rendered value React records on the node,
  and which has a handler to converge at the end of? See the namespace
  docstring — each clause is a condition on the record, not a taste
  about which elements deserve the behaviour."
  [tag js-props]
  (and (controlled-text-tag? tag js-props)
       (nil? (unchecked-get js-props "defaultValue"))
       (caret-type? tag js-props)
       (some? (change-slot js-props))))

;; ---------------------------------------------------------------------------
;; The composition shadow — the half of the carve-out that is React's
;; ---------------------------------------------------------------------------

(def ^:private native-tag-key
  "Where [[shadow-component]] records the tag it renders, so
  [[element-tag]] can answer for an emitted element without knowing
  this namespace exists."
  "hicassoNativeTag")

(def revision-slot
  "The private slot [[re-frame.bench.hicasso.front.codec/native-element]]
  stashes a `::h/revision` on, and [[install!]] deletes as it reads.

  It exists for exactly the length of one `install!` call. The codec
  reads the revision off the author's own pre-merge map and has nowhere
  to put it except the object it is already building; this namespace owns
  the name so the codec cannot drift from it, and the delete is what
  makes \"never a DOM attribute\" true by construction rather than by a
  strip at each of the three exits (React, the DOM, the server bytes)."
  "hicassoRevision")

(defn- shadowed-props
  "The props the native tag is rendered with while `shadow` is held —
  the author's, with the value and three handlers replaced.

  `shadow` is `nil` when no composition is live, and the value the
  element renders is then the author's own; while it is held, **the
  value React sees is the live DOM draft**, which is the entire
  mechanism: React's restore assigns `element.value` only when it
  differs from the controlled value, so agreeing with the draft is what
  makes the restore a no-op. See the namespace docstring.

  Three slots, and each is a release or a hold. **The hold or release
  happens FIRST at every one of them**, before the handler the author
  wrote — not for ordering's sake but because that is what makes the
  release unconditional: a handler of the author's that throws must not
  be able to strand a shadow, and the worst it can then leave behind is
  a converge.

  - the **change slot** — [[install!]]'s converging handler, wrapped.
    Composing, the live draft is held and the converge inside has
    already declined to run; not composing, the shadow is released, and
    releasing *before* the inner handler means the release renders
    inside the converge's own `flushSync` — one flush, both jobs.
  - **`onCompositionEnd`** — release, then converge once against the
    then-current model, which is why the converge is the one thing here
    that runs after the author (their handler may move the model, and
    the model this converges against is the one it leaves behind). This
    is where a refusal lands, whole and visible, on a field whose model
    would have destroyed the exchange to say so.
  - **`onBlur`** — release, and nothing else. A blurred field has no
    caret worth restoring; the release alone re-renders the model's
    value and React's own commit writes it.

  The author's handler at each slot is preserved and called; an element
  with no handler at a slot simply has one now, which is invisible."
  [props shadow set-shadow]
  (let [out      (js/Object.assign #js {} props)
        release! (fn [] (set-shadow nil))
        slot     (change-slot props)
        inner    (unchecked-get props slot)
        ended    (unchecked-get props "onCompositionEnd")
        blurred  (unchecked-get props "onBlur")]
    (when (some? shadow)
      (unchecked-set out "value" shadow))
    (unchecked-set out slot
                   (fn hicasso-composition-shadow [e]
                     (if (composing-input? e)
                       (set-shadow (some-> (.-target e) (.-value)))
                       (release!))
                     (inner e)
                     nil))
    (unchecked-set out "onCompositionEnd"
                   (fn hicasso-composition-end [e]
                     (release!)
                     (when (fn? ended) (ended e))
                     (when-some [node (.-target e)]
                       (converge! node))
                     nil))
    (unchecked-set out "onBlur"
                   (fn hicasso-composition-release [e]
                     (release!)
                     (when (fn? blurred) (blurred e))
                     nil))
    out))

(defn- shadow-component
  "The internal component that holds the composition shadow for `tag`.

  ONE `useState`, no ref, no effect, and no props of its own: the author
  writes `[:input {:value … :on-input …}]` and the element path decides
  this stands in front of it. It is a *public-React* mechanism on
  purpose — the ruling's preference and this file's — because the only
  supported way to change what React's controlled restore compares
  against is to change what React rendered.

  **It stands in front of every controlled `input`/`textarea`, not only
  the convergeable ones**, and asks [[convergeable?]] again inside. A
  component chosen by a predicate that reads `:type` would flip to the
  bare tag the moment a synchronous handler re-rendered the field from
  `text` to `number` — remounting the element React would otherwise have
  kept, which is a strictly worse outcome than the inert render this
  costs. Where the answer is no the props go through **by identity**: no
  copy, no closures, nothing but the fiber and its one hook cell."
  [tag]
  (let [component (fn [props]
                    (let [hook       (react/useState nil)
                          shadow     (aget hook 0)
                          set-shadow (aget hook 1)]
                      (react/createElement
                       tag
                       (if (convergeable? tag props)
                         (shadowed-props props shadow set-shadow)
                         props))))]
    (unchecked-set component "displayName" (str "hicasso/controlled-" tag))
    (unchecked-set component native-tag-key tag)
    component))

(def ^:private shadow-input (shadow-component "input"))
(def ^:private shadow-textarea (shadow-component "textarea"))

(defn element-tag
  "The native tag `e` will render — `\"input\"` for a controlled field
  whose element type is the shadow component, and `(.-type e)` for
  everything else.

  The one reader an element-tree test needs, so that walking what the
  codec emitted stays a question about the DOM rather than about this
  namespace. `front/census_article_editor_cljs_test` picks its inputs
  out of a rendered fieldset with it."
  [e]
  (let [t (.-type e)]
    (or (when (fn? t) (unchecked-get t native-tag-key))
        t)))

;; ---------------------------------------------------------------------------
;; Installation — what the element path calls
;; ---------------------------------------------------------------------------

(defn install!
  "Prepare `js-props` and answer **what to render them as**.

  Mutates the props object the codec has just built and is about to hand
  to `createElement`, and returns the component for it: the tag itself
  for everything that is not a controlled `input`/`textarea`, and
  [[shadow-component]]'s otherwise.

  Where the element is [[convergeable?]] the change handler is wrapped
  so the field converges in-turn with the caret intact — unless the
  event arrived mid-composition, which is the carve-out's first half and
  is one reading of the native event. A fresh wrapper per render is the
  same shape the codec already has for every lowered intent, and it
  closes over nothing but the author's handler — deliberately **not**
  over the value, which is the stale reading [[converge-to!]] exists to
  keep out of the write.

  ## The revision marker, read and deleted

  One `unchecked-get` on every native element, which is what the reset
  trigger costs an element that does not carry one. Present, it is
  deleted before anything else can see it — the delete is the whole of
  \"never a DOM attribute\" — and the element is REFUSED if it is not a
  [[controlled-text-tag?]]. Nothing else happens, because nothing else
  needs to: the reset rides React's own per-commit controlled re-assert
  off the fresh props object the codec mints per render, so there is no
  hook, no ref, no comparison record and **no third `flushSync` site**
  here. See [[re-frame.bench.hicasso.front.codec/revision-key]].

  The acceptance predicate is [[controlled-text-tag?]] — the one that
  already chooses the shadow component, reused rather than duplicated —
  and it is deliberately type-blind, so state the coverage honestly
  rather than overstating it. A `:div`, a `select`, a value-less
  `<input>` and a checkbox written idiomatically with `:checked` and no
  `:value` are all refused. A checkbox carrying a form-submission
  `value=\"yes\"` is ACCEPTED, and the revision is simply inert there; an
  `<input type=\"number\">` is likewise accepted, with caret semantics
  that do not apply to it. \"A checkbox is refused\" is the wrong
  sentence; \"a value-less checkbox is refused\" is the right one."
  [tag js-props]
  (when-not (undefined? (unchecked-get js-props revision-slot))
    (js-delete js-props revision-slot)
    (when-not (controlled-text-tag? tag js-props)
      (throw (ex-info (str "A revision belongs on a controlled text field, and this is not one. "
                           "[:rf.error/hicasso-revision-not-controlled]")
                      {:rf.error/id :rf.error/hicasso-revision-not-controlled
                       :where       'front.controlled/install!
                       :reason      (str ":re-frame.hicasso/revision re-baselines a controlled "
                                         "<input> or <textarea> to its model, so it needs both "
                                         "of those: an `input`/`textarea` tag, and a non-nil "
                                         ":value to re-baseline TO. This is a " (pr-str tag)
                                         " and the trigger has no field to fire at.")
                       :recovery    :put-the-revision-on-a-controlled-input-or-textarea
                       :tag         tag}))))
  (when (convergeable? tag js-props)
    (let [slot    (change-slot js-props)
          handler (unchecked-get js-props slot)]
      (unchecked-set js-props slot
                     (fn hicasso-converging-change [e]
                       (handler e)
                       (when-not (composing-input? e)
                         (when-some [node (.-target e)]
                           (converge! node)))
                       nil))))
  (if (controlled-text-tag? tag js-props)
    (case tag
      "input"    shadow-input
      "textarea" shadow-textarea)
    tag))
