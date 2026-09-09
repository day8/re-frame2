(ns re-frame.hicasso.impl.controlled
  "The controlled-element converge: a store-backed `<input>` / `<textarea>`
  converges to its model in the same discrete event with the caret where
  the edit left it, and a live IME composition is carved out of that.

  It lives in the element path, not in a component. `install!` is called
  from `re-frame.hicasso.impl.codec/native-element` and wraps the change
  handler the author wrote, so the view stays an ordinary `:value` /
  `:on-input` pair and the boundary shell gains no hook. At the end of
  that handler — still ahead of React's own end-of-event restore — it
  `flushSync`es, writes the value the element last rendered if the field
  disagrees (which makes React's later write a no-op, and a no-op there is
  what lets the caret survive), and puts the caret back by offset from
  the END of the string. The value the element last rendered is read off
  `node.defaultValue`, which React mirrors the committed `value` prop into
  on every commit, so the per-instance record costs no ref, no map and no
  hook. Only the synchronous door reaches it: a queued dispatch has not
  moved the model by the end of the handler, so the wrapper is a no-op
  there and the field converges one macrotask later as it always did.

  The composition carve-out is two halves because two writes can destroy
  a live composition and only one of them is ours: this converge, which
  `composing-input?` suppresses; and React's own restore, which
  `shadow-component` makes a no-op by rendering the live DOM draft as the
  value while a composition is held. The shadow is one internal component
  with one `useState`, in front of every controlled `input` / `textarea`;
  the field converges once, at `compositionend`, so on a refusing or
  normalising field this runtime deliberately diverges from plain React
  and the refusal lands whole rather than mid-composition.

  `::h/revision` re-baselines the field on an explicit caller revision
  change, never on value equality, and costs this file one
  `unchecked-get`: the codec mints fresh props per render and React's
  per-commit controlled re-assert carries the reset, so there is no third
  `flushSync` site. Mid-composition it defers to the exchange's close;
  mid-adoption it is absorbed.

  Design record: docs/design/hicasso/decisions.md HD-019 and its addendum
  (the flushSync exception, the carve-out, the shadow's hook priced);
  docs/design/hicasso/studio/controlled-input-two-implementations.md (the
  matrix, the trap, why the record is React's own, the IME measurements);
  docs/design/hicasso/studio/revision-prop-spec.md (the revision prop,
  the mid-composition deferral, the mid-adoption absorption)."
  (:require [re-frame.hicasso.impl.error :refer [fail!]]
            ["react" :as react]
            ["react-dom" :as react-dom]))

(defn- noop [])

;; ---------------------------------------------------------------------------
;; The per-instance record
;; ---------------------------------------------------------------------------

(defn last-rendered
  "The value this element's last render put on it, read off the node:
  React's mirror of the committed `value` prop in `node.defaultValue`,
  maintained on every commit of a genuinely controlled `<input>` or
  `<textarea>` and undisturbed by typing. The one place this namespace
  depends on that behaviour, so it is the one place a row pins it —
  `arm1/controlled_grid_dom_cljs_test/the-record-is-reacts-own-mirror-and-is-not-the-handlers-closure`
  (`bench/hicasso`). `js/undefined` on anything that is not a live form
  control, which is what makes `converge!` inert there."
  [node]
  (.-defaultValue node))

;; ---------------------------------------------------------------------------
;; The composition reading
;; ---------------------------------------------------------------------------

(defn composing-input?
  "Did this change event arrive in the middle of a live IME composition?
  Read off the NATIVE event's `isComposing` — React's synthetic event
  copies an enumerated interface, and what is not on the list is not on
  the event. Deliberately not `impl.intent/composing?`, the key-map's
  gate: that one answers a KEY event and needs the legacy keyCode-229
  signal, while an `input` event has no `keyCode` and every browser that
  fires composition events sets `isComposing` on it. A raw DOM event or a
  synthetic `#js {:target …}` has no `nativeEvent` and reads *not
  composing*."
  [e]
  (true? (some-> (.-nativeEvent e) (.-isComposing))))

;; ---------------------------------------------------------------------------
;; The converge
;; ---------------------------------------------------------------------------

(defn converge-to!
  "Make `node` show `rendered` and put the caret back by offset from the
  END of the string. `rendered` is the whole of the design: hand it
  `last-rendered` and every row lands; hand it the change handler's own
  closure value — correct when the handler was invoked and stale the
  moment the flush commits — and an accepted keystroke is wiped off the
  screen
  (`controlled-dom-cljs-test/the-closure-value-wipes-a-keystroke-the-model-took-verbatim`).
  The offset is taken from the end because that is the half a
  normalisation preserves: `\"1,234\"` + `5` becomes `\"12,345\"`, and the
  caret belongs after the `5`, which is `0` from the end in both strings.
  The value is written only when it differs, because assigning `value`
  moves the cursor to the end of the control."
  [node dom-value caret-was rendered]
  (when-not (= (.-value node) rendered)
    (set! (.-value node) rendered))
  (let [offset (- (count dom-value) caret-was)
        c      (max 0 (- (count (.-value node)) offset))]
    (.setSelectionRange node c c))
  nil)

(defn converge!
  "Converge `node` against the model, inside the discrete event: read the
  field's value and caret FIRST (the flush may write both), `flushSync`,
  then `converge-to!` the rendered value. Inert rather than wrong where it
  does not apply: a caret that is not a number is not a text-entry
  control, and a record that is not a string is an element React never
  mirrored.

  The caret is read a SECOND time after the flush because the flush is a
  render. `install!` judged the element caret-bearing from the props that
  minted this wrapper, and a synchronous handler may re-render the same
  `<input>` from `text` to `number` — React keeps the node — so the
  wrapper from the text render runs against an element with no caret,
  where `setSelectionRange` throws `InvalidStateError` and the mirror is
  stale (`setDefaultValue` skips a focused `number` field). Answered no,
  the element is left as React leaves it
  (`arm1/controlled_grid_dom_cljs_test/a-type-change-inside-the-flush-leaves-the-converge-inert`,
  `bench/hicasso`).

  This is the one `flushSync` expression in the namespace, reached from
  two call sites — the non-composing keystroke and `compositionend` — of
  which at most one fires per event; HD-019 grants the exception to that
  audited mechanism, not to a count."
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
  `setSelectionRange` applies to — spelled as the platform spells them,
  which is why `caret-type?` folds a prop before asking rather than
  widening this set."
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
  `<input>` does for the five `caret-types` and for no `:type` at all,
  which is `text`.

  The type is folded to lower case at the comparison, because HTML
  matches that enumerated attribute ASCII case-insensitively while this
  predicate reads the author's own spelling (the codec hands it on
  unchanged): an exact compare would read `<input type=\"TEXT\">` as
  having no caret, install nothing, and hand the field to React's own
  restore with the caret thrown to the end — no throw, no id, no warning.
  The attribute that ships stays the author's. The `string?` guard makes
  it total: `:type 0` survives the codec as a number with no
  `toLowerCase`. The fold costs ~8 ns per call, about 0.1% of one field's
  render, and a fold-on-miss variant was measured and rejected —
  docs/design/hicasso/studio/controlled-input-two-implementations.md,
  §The type fold, priced."
  [tag js-props]
  (or (identical? "textarea" tag)
      (let [t (unchecked-get js-props "type")]
        (or (nil? t)
            (and (string? t) (contains? caret-types (.toLowerCase t)))))))

(defn- change-slot
  "The slot whose handler runs LAST for one keystroke — `onChange` when
  the author wrote one, else `onInput`, since React extracts `onInput`
  before `onChange` — or nil when there is no change handler and so
  nothing to converge after."
  [js-props]
  (cond
    (fn? (unchecked-get js-props "onChange")) "onChange"
    (fn? (unchecked-get js-props "onInput"))  "onInput"
    :else                                     nil))

(defn- controlled-text-tag?
  "Is this a form control React mirrors a controlled `value` onto: an
  `input` or `textarea` with a non-nil `value`? The `:type` is deliberately
  not asked, and that is the whole difference from `convergeable?`: this
  predicate chooses a React element TYPE, and an element type that changed
  under a live field would remount it — losing focus, selection and any
  composition — where the attribute change React performs on a `text` →
  `number` keystroke loses nothing
  (`controlled-dom-cljs-test/the-element-type-does-not-move-when-the-input-type-does`)."
  [tag js-props]
  (and (convergeable-tag? tag)
       (some? (unchecked-get js-props "value"))))

(defn- convergeable?
  "Is this an element whose rendered value React records on the node, with
  a handler to converge at the end of? Each clause is a condition on the
  record: a `controlled-text-tag?` (uncontrolled, there is no mirror); no
  author `defaultValue` (on a `<textarea>` React honours it over the
  mirror); a caret type (`setSelectionRange` is not applicable to the
  others, and React's own restore already converges their value); and a
  change handler. A guard that does not hold means no wrapper at all."
  [tag js-props]
  (and (controlled-text-tag? tag js-props)
       (nil? (unchecked-get js-props "defaultValue"))
       (caret-type? tag js-props)
       (some? (change-slot js-props))))

;; ---------------------------------------------------------------------------
;; The composition shadow — the half of the carve-out that is React's
;; ---------------------------------------------------------------------------

(def ^:private native-tag-key
  "Where `shadow-component` records the tag it renders, so `element-tag`
  can answer for an emitted element without knowing this namespace."
  "hicassoNativeTag")

(def revision-slot
  "The private slot `re-frame.hicasso.impl.codec/native-element` stashes a
  `::h/revision` on, and `install!` deletes as it reads. It exists for the
  length of one `install!` call; this namespace owns the name so the codec
  cannot drift from it, and the delete is what makes *never a DOM
  attribute* true by construction."
  "hicassoRevision")

(defn- shadowed-props
  "The props the native tag renders with while `shadow` is held: the
  author's, with the value and three handlers replaced. While a
  composition is live the value React sees is the live DOM draft, so
  React's restore — which assigns `element.value` only when it differs
  from the controlled value — finds nothing to write. Nothing here reaches
  React's internals.

  Three slots, and at each the hold or release happens FIRST, before the
  author's handler, so a handler that throws cannot strand a shadow: the
  change slot holds the draft while composing and releases otherwise
  (the release renders inside the converge's own `flushSync` — one flush,
  both jobs); `onCompositionEnd` releases, runs the author, then converges
  once against the model the author left behind, which is where a refusal
  lands; `onBlur` releases and nothing else, since a blurred field has no
  caret worth restoring. A release when nothing is held is free — React
  bails out of an update to an identical state — so the degenerate
  outcome is a converge. The author's handler at each slot is preserved
  and called."
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
  "The internal component that holds the composition shadow for `tag`:
  one `useState`, no ref, no effect, no props of its own. It stands in
  front of EVERY controlled `input` / `textarea`, not only the
  convergeable ones, and asks `convergeable?` again inside — a component
  chosen by a predicate reading `:type` would flip to the bare tag when a
  handler re-rendered the field from `text` to `number`, remounting an
  element React would have kept. Where the answer is no the props go
  through by identity."
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
  everything else. The one reader an element-tree test needs, so walking
  what the codec emitted stays a question about the DOM."
  [e]
  (let [t (.-type e)]
    (or (when (fn? t) (unchecked-get t native-tag-key))
        t)))

;; ---------------------------------------------------------------------------
;; Installation — what the element path calls
;; ---------------------------------------------------------------------------

(defn install!
  "Prepare `js-props` — the object the codec has just built and is about
  to hand to `createElement` — and answer what to render them as: the tag
  itself for everything that is not a controlled `input` / `textarea`,
  and the shadow component otherwise.

  Where the element is `convergeable?` the change handler is wrapped so
  the field converges in-turn with the caret intact, unless the event
  arrived mid-composition. The wrapper closes over the author's handler
  and deliberately NOT over the value — the stale reading `converge-to!`
  exists to keep out of the write.

  The revision marker (`revision-slot`) is read and deleted on every
  native element, one `unchecked-get`. Present, the element is REFUSED
  with `:rf.error/hicasso-revision-not-controlled` unless it is a
  `controlled-text-tag?`; nothing else happens, because the reset rides
  React's per-commit re-assert off the fresh props the codec mints per
  render. The predicate is type-blind, so state the coverage exactly: a
  `:div`, a `select`, a value-less `<input>` and a checkbox written with
  `:checked` and no `:value` are refused; a checkbox carrying
  `value=\"yes\"` is accepted with the revision inert, and an
  `<input type=\"number\">` is accepted with caret semantics that do not
  apply. A non-empty `:value` on a file input is left to the platform,
  whose `InvalidStateError` is the report."
  [tag js-props]
  (when-not (undefined? (unchecked-get js-props revision-slot))
    (js-delete js-props revision-slot)
    (when-not (controlled-text-tag? tag js-props)
      (fail! :rf.error/hicasso-revision-not-controlled
             're-frame.hicasso.impl.controlled/install!
             (str "A revision belongs on a controlled text field, and this is not "
                  "one. :re-frame.hicasso/revision re-baselines a controlled "
                  "<input> or <textarea> to its model, so it needs both of those: "
                  "an `input`/`textarea` tag, and a non-nil :value to re-baseline "
                  "TO. This is a " (pr-str tag) " and the trigger has no field to "
                  "fire at.")
             {:tag tag})))
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
