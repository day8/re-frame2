(ns re-frame.freehand.controlled
  "The CONTROLLED-INPUT DOOR — the one exact predicate that decides which
  event sites take the synchronous round trip (ruled by D009).

  A controlled input is the one place where React's render cycle collides
  with a live DOM node the user is typing into. The DOM value is only
  PROVISIONAL until application state round-trips back through render, and
  React enforces that literally: at the end of a discrete event it
  RESTORES a controlled node's value from the props it last rendered. So a
  keystroke whose state change has not landed by then is not merely late —
  it is erased, and with it the caret position, the selection, and any IME
  composition in flight.

  Making every event synchronous would fix that by imposing its cost and
  its reentrancy on the whole system. The useful design is the opposite: a
  door narrow enough to recognise MECHANICALLY, and identical in both
  execution modes.

  ## The door, exactly

  A site is inside the door when ALL of these hold:

  1. the element is one of the [[controlled-tags]] — the natives whose
     value React restores;
  2. its FINAL-NORMALIZED props carry `value` or `checked` — presence, so
     an explicit `nil` counts;
  3. the firing attribute normalizes to `onInput` or `onChange`;
  4. the handler's outcome is SYNCHRONOUSLY known to be one event vector
     or `nil` — a vector, an options map carrying one, or `v/event`;
  5. no listener option moves the site onto a different native attachment
     lane: `:capture` and `:passive` are out.

  Everything else batches. `v/handler`, a bare function, a promise and a
  foreign callback protocol are all outside, because none of them tells
  the runtime — before the listener returns — what state change is coming.

  `:on-before-input` is deliberately OUTSIDE. `beforeinput` fires BEFORE
  the DOM mutation, so `target.value` is not generally the candidate
  value; admitting it would need its own browser-backed projection and
  composition contract, not a name added to a list.

  ## Why normalized SLOTS, and not authored keywords

  Both walks project an attribute key onto the React prop SLOT they
  actually write — the namespace is dropped, aliases resolve, `:on-input`
  becomes `onInput`. Judging the AUTHORED keyword would leave every other
  spelling of one emitted prop outside the door: `:x/value` reaches
  React's `value` and makes the node controlled just as `:value` does. So
  the door reads the slot, through the same
  [[re-frame.freehand.rules/caller-key-slot]] projection the emitters and
  the `v/spread-safe` deny law already read — one canonicalization, so
  membership and emission agree by construction.

  ## One predicate, two modes

  [[door?]] is the whole rule and it is COMMON. The compiled analyzer
  applies it at compile time over statically proved facts; the
  interpreted walk applies it at render time over the props it just
  normalized. Neither owns a second copy, so promotion cannot silently
  move a field from synchronous to batched — the failure D009 names as
  the one promotion parity break that would matter.

  What the door DOES is not here: opening it commits the site's
  synchronous dispatcher, which is
  [[re-frame.freehand.cell]]'s frame-scoped flush. This namespace owns the
  controlled-input VOCABULARY — which tags and slots make a node
  controlled, and what an EMPTY one of those slots is
  ([[empty-control-slot]]) — and no behaviour beyond it.

  INTERNAL. There is no authoring verb: a controlled input is recognised,
  never declared.

  Normative owner:
  [`spec/004-Views.md`](../../../../../spec/004-Views.md) §Controlled
  inputs."
  (:require [re-frame.freehand.rules :as rules]))

#?(:clj (set! *warn-on-reflection* true))

;; ---------------------------------------------------------------------------
;; The closed vocabularies
;; ---------------------------------------------------------------------------

(def controlled-tags
  "The CLOSED roster of native tags whose value React restores from props
  at the end of a discrete event — and therefore the only tags where the
  synchronous round trip is load-bearing rather than overhead.

  A custom element carrying a `value` property is deliberately outside:
  React does not restore it, so it has no hazard to fix, and whatever
  synchronous protocol it needs is its own adapter's to declare. A `:div`
  carrying `:value` is outside for the same reason — `value` is not a
  property it has."
  #{:input :textarea :select})

(def controlled-slots
  "The FINAL-NORMALIZED prop slots whose PRESENCE makes a supported native
  node controlled. Presence, not truth: an explicit `nil` `:value` is a
  controlled node whose value is empty, and treating it as uncontrolled
  would silently drop exactly the site an author was most careful about."
  #{"value" "checked"})

(def door-slots
  "The FINAL-NORMALIZED handler slots that can open the door — the two
  attributes that fire AFTER the DOM mutation, so the live payload is the
  candidate value the state must now match.

  `onBeforeInput` is not here, and the capture-phase spellings
  (`onInputCapture`, `onChangeCapture`) are not here either: a capture
  listener is a different native attachment lane and has not been proven
  through the same browser matrix."
  #{"onInput" "onChange"})

(def synchronous-outcomes
  "The CLOSED roster of callback roles whose outcome is synchronously
  known to be one event vector or `nil`, and so can complete a round trip
  before the listener returns.

  `:handler` and `:bare-fn` are excluded on purpose — not because they
  cannot dispatch, but because what they dispatch (and whether they
  dispatch at all, or later, from a promise) is unknowable at the moment
  the door would have to open. `:render-fn` and `:raw-fn` never reach an
  event position's dispatch path at all. `:key-map` is absent for a
  different reason again: it is legal only on a key listener, and no key
  listener is a [[door-slots]] slot, so admitting it here would decide
  nothing."
  #{:event-vector :event-options :event})

;; ---------------------------------------------------------------------------
;; Final normalization
;; ---------------------------------------------------------------------------

(defn prop-slot
  "The FINAL-NORMALIZED React prop slot an authored attribute key is
  written into — the one canonicalization the door reads.

      (prop-slot :value)      ;=> \"value\"
      (prop-slot :x/value)    ;=> \"value\"
      (prop-slot :on-input)   ;=> \"onInput\"

  Delegates to [[re-frame.freehand.rules/caller-key-slot]] rather than
  restating it, so the door and the emitters can never disagree about
  which prop a key IS. `nil` for a key that names no attribute at all."
  [k]
  (rules/caller-key-slot k))

(defn controlled-slot?
  "Is `slot` one of the [[controlled-slots]]?"
  [slot]
  (contains? controlled-slots slot))

(defn door-slot?
  "Is `slot` one of the [[door-slots]] — an attribute that can open the
  door on a controlled node?"
  [slot]
  (contains? door-slots slot))

(defn controlled-props?
  "Does this element's authored attribute key sequence make it controlled
  — do any of the keys normalize onto a [[controlled-slots]] slot?

  Reads keys only. The VALUE is irrelevant by design (D009: presence,
  including `nil`), which is also why this needs no access to the
  normalized values and can run before they are converted."
  [attr-keys]
  (boolean (some #(controlled-slot? (prop-slot %)) attr-keys)))

;; ---------------------------------------------------------------------------
;; Presence, once the value IS in hand
;; ---------------------------------------------------------------------------

(def controlled-empty-values
  "What React reads as a CONTROLLED but EMPTY [[controlled-slots]] slot: an
  empty string clears a `value`, and `false` unchecks a `checked`.

  React's own signal for an UNCONTROLLED node is the prop's ABSENCE — and
  it reads an explicit `null` the same way, loudly. So an emitter that
  dropped an explicitly nil controlled prop under its generic
  nil-attribute law would hand React the opposite of what the author wrote
  and the door already decided. React acts on that: it warns the node
  changed control, and it KEEPS the value it last rendered rather than
  clearing the field."
  {"value"   ""
   "checked" false})

(defn empty-control-slot
  "The React prop slot an explicitly nil attribute `k` on `tag` must still
  be WRITTEN into, paired with the controlled-empty value it takes — or
  `nil` when this is not a controlled slot on a supported native control,
  where an emitter's ordinary nil-attribute omission stands.

      (empty-control-slot :input :x/value) ;=> [\"value\" \"\"]
      (empty-control-slot :input :checked) ;=> [\"checked\" false]
      (empty-control-slot :input :title)   ;=> nil
      (empty-control-slot :div   :value)   ;=> nil

  Scoped to exactly the door's first two facts, and read through the same
  [[prop-slot]] projection, so a spelling that makes a node CONTROLLED is
  the spelling that clears it.

  An ENTRY rather than the value alone, because the empty `checked` value
  IS `false`: a caller asking `(when-let [v …])` would silently drop the
  unchecked case — the same presence-versus-truth collapse this rule
  exists to undo."
  [tag k]
  (when (contains? controlled-tags tag)
    (find controlled-empty-values (prop-slot k))))

;; ---------------------------------------------------------------------------
;; The compiled tier's vocabulary, mapped onto the roster's
;; ---------------------------------------------------------------------------

(def ^:private compiled-roles
  {:vector   :event-vector
   :options  :event-options
   :key-map  :key-map
   :ui-event :event
   :handler  :handler
   :fn       :bare-fn})

(defn compiled-role
  "The roster ROLE a compiled handler classification names — the bridge
  that lets the analyzer ask [[door?]] the same question the interpreted
  runtime asks, in the same vocabulary.

  TOTAL: an unmapped classification (`:dynamic` — a handler expression
  whose class no static fact pins) answers `:dynamic`, which is in no
  door roster. A dynamic site is not 'outside the door' as a verdict; it
  is a site whose verdict the RUNTIME predicate owns, because the value
  that decides it does not exist until then."
  [classification]
  (get compiled-roles classification :dynamic))

;; ---------------------------------------------------------------------------
;; The door
;; ---------------------------------------------------------------------------

(defn door?
  "The ONE controlled-input door predicate. True when this site takes the
  synchronous round trip; false — for every other site in the system —
  when it batches.

  It is a property of the whole element, not of a handler in isolation,
  so it is asked after every prop has been normalized. The site is a map
  of the five facts D009 enumerates, all already final-normalized:

      {:tag         :input        ;; the element's tag keyword
       :controlled? true          ;; `value`/`checked` present in the
                                  ;;   final props (`controlled-props?`)
       :slot        \"onInput\"     ;; this handler's final prop slot
       :role        :event-vector ;; the callback's roster role
       :capture?    false         ;; listener options that would move the
       :passive?    false}        ;;   site to another attachment lane

  Missing keys read as absent facts, so a partially-known site answers
  false rather than guessing — the safe direction, because a site wrongly
  BATCHED is slow and a site wrongly SYNCHRONOUS is a reentrancy hazard
  on every keystroke in the application."
  [{:keys [tag controlled? slot role capture? passive?]}]
  (boolean
    (and (contains? controlled-tags tag)
         controlled?
         (door-slot? slot)
         (contains? synchronous-outcomes role)
         (not capture?)
         (not passive?))))
