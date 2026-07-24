(ns ^:no-doc re-frame.freehand.control
  "The whole of Freehand's semantic-controller infrastructure: WHERE a
  writable controller's record lives ([[record-key]]) and WHEN that
  record is still the one the caller means ([[reset-revision]] and
  [[current?]]).

  A reusable view is props-only by default — value in, intent out. A
  SEMANTIC CONTROLLER is the exception a library earns when a control
  owns a protocol that spans several interactions (a commit-on-blur
  draft, an open flag plus an active option, a settled query beside a
  typed one). Its state is ORDINARY re-frame frame data moved by ORDINARY
  registered events, so a controller is a `v/defview` plus `reg-sub`s and
  `reg-event`s and nothing else. There is no controller registry, no
  reducer language, and no second state system: D003 rules that generic
  storage machinery would become `local` in app-db under another name,
  and D017 rules that the widget vocabulary belongs to the component
  library rather than to the substrate.

  What is left for the substrate is two things, and both are questions a
  library would otherwise answer twice and differently.

  **IDENTITY.** Per D004 a controller record is addressed by the pair

      (controller kind, caller-supplied :control address)

  and never by renderer occurrence identity. A derived anchor makes a
  sort, a view rename or a parent extraction into a silent state
  migration, whereas `[:invoice 42 :amount]` survives all of them.
  [[record-key]] is where the pair is formed and where an address that is
  absent — or explicitly `nil`, which is a different mistake and gets its
  own diagnostic — is refused, so a controller cannot half-implement the
  rule; asking for the key IS what makes a controller writable.

  **CURRENCY.** Per D016 a BUFFERED controller — one that holds a draft
  the user is editing and commits it later — additionally belongs to a
  GENERATION, named by the caller's `:reset-key`. A draft is eligible
  only while its generation is the caller's current one; work from a
  superseded generation must not land, and must not land in EITHER
  direction — it is neither displayed nor committed. [[reset-revision]]
  is where the caller's generation is taken and where its absence — or an
  explicit `nil`, again a separate diagnostic — is refused; [[current?]]
  is the fence itself, asked identically on the read side and the write
  side so the two cannot drift.

  Neither `nil` refusal is a truthiness test wearing a presence test's
  clothes. `false`, `0` and `\"\"` are perfectly good addresses and
  generations and pass unremarked; what is refused is `nil` specifically,
  because the domain of an ADDRESS and the domain of a GENERATION exclude
  it — a controller keyed by `nil` collides with every other address-less
  controller, and [[current?]] exists precisely because `(= nil nil)` is
  true, so a `nil` generation would read as current against an unstamped
  record.

  The fence is a comparison over ORDINARY FRAME DATA, and that is the
  whole design: a draft carries the generation it was made under, so a
  superseded draft is INVISIBLE rather than erased. Nothing has to be
  reset during render, no host slot is adjusted mid-render, and an
  abandoned or replayed render changes nothing — the same shape
  [[re-frame.freehand.cell]] uses when it re-checks currency at the
  narrowest boundary and publishes nothing if it lost, and the same shape
  [[re-frame.freehand.errors]] uses for its `:reset-key`. One idea, three
  users, no second mechanism.

  All three CROSS THE DOOR, as `v/controller-key`,
  `v/controller-revision` and `v/controller-current?` — this namespace
  stays internal and the door names them for the author. A component
  library is consumer code by construction (D017 gives it the widget
  vocabulary outright), so leaving the mechanism unpublished would oblige
  a library to reach into an internal namespace to write an ordinary
  field. The advertising cost the door pays — a published authoring verb
  can read as an invitation to hold state in a control — is carried by
  the `advanced` tier instead: the Guide and the skills load front-porch
  only, so these are reachable by the library author who needs them and
  invisible to the app author who does not.

  The door spelling is also what the two refusals below NAME as their
  raising site, so a diagnostic never points an author at a namespace
  they never typed.

  Normative owner:
  [`spec/004-Views.md` §Semantic controllers](../../../../../spec/004-Views.md#semantic-controllers)."
  (:require [re-frame.error :as error]
            [re-frame.freehand.eq :as eq]))

(def ^:private where
  "The raising site the address diagnostic names — the DOOR spelling, not
  this namespace's, because that is the name the author wrote."
  're-frame.freehand/controller-key)

(def ^:private where-revision
  "The raising site the reset-revision diagnostic names — the DOOR
  spelling, for the same reason."
  're-frame.freehand/controller-revision)

;; ABSENCE AND ILLEGALITY ARE TWO DIFFERENT MISTAKES, so they are two
;; different diagnostics.
;;
;; `nil` is not in the value domain of an address or of a generation, so a
;; caller who passes one has not omitted anything — they have supplied a
;; value the contract does not admit, and almost always because an
;; expression UPSTREAM of the call answered nothing (a route param not yet
;; resolved, a subscription that has not landed, a `get` on the wrong key).
;; Reporting that as "rendered with no :control" points the author at the
;; call site, which is the one place the mistake is not. The prop is right
;; there.
;;
;; The two are told apart by PRESENCE, never by truthiness — the discipline
;; the controlled-input rulings fixed. A truthiness test standing in for a
;; presence test is wrong wherever the value domain has falsey members; here
;; the domains exclude `nil` outright, so `contains?` decides which of the
;; two refusals is the honest one and the refusal itself is total either way.

(defn- missing-address!
  [kind props]
  (error/throw-error!
    :rf.error/view-control-address-missing
    where
    (str "A writable controller of kind " (pr-str kind) " was rendered with no :control "
         "address. Controller state is keyed by the controller kind plus an address its "
         "CALLER supplies, because a renderer-derived anchor turns a sort, a view rename or "
         "a parent extraction into a silent state migration. Pass the domain identity that "
         "owns the state — :control [:invoice invoice-id :amount] — or, if this control has "
         "no state of its own, drop the controller and take the value and the intent as "
         "ordinary props.")
    {:recovery :supply-a-control-address
     :extra    {:kind  kind
                :props (error/diag-value-summary props)}}))

(defn- nil-address!
  [kind props]
  (error/throw-error!
    :rf.error/view-control-address-nil
    where
    (str "A writable controller of kind " (pr-str kind) " was rendered with :control nil. "
         "The prop IS there, so nothing was forgotten at the call site — nil is simply not "
         "an address. An address names the domain thing that owns the state, and every "
         "controller passed nil would share ONE record keyed by nil, which presents as one "
         "field editing another and has no local explanation. A nil here is almost always "
         "an expression UPSTREAM answering nothing: a route parameter not yet resolved, a "
         "subscription that has not landed, a lookup on a key that moved. Fix what produced "
         "the nil, render the control only once its identity exists, or — if this control "
         "has no state of its own — drop the controller and take the value and the intent "
         "as ordinary props.")
    {:recovery :fix-what-produced-the-nil-control-address
     :extra    {:kind  kind
                :props (error/diag-value-summary props)}}))

(defn- missing-revision!
  [kind props]
  (error/throw-error!
    :rf.error/view-control-reset-revision-missing
    where-revision
    (str "A buffered controller of kind " (pr-str kind) " was rendered with no :reset-key. "
         "A buffered control holds a draft the caller can reject, and rejection is often "
         "spelled by REASSERTING the same value — so the value cannot be the reset signal: "
         "value-equality is blind to it, and the rejected draft would silently survive. The "
         "generation is therefore the caller's own revision, and it is required rather than "
         "optional so that every buffered control is fenced and none is fenced by accident. "
         "Pass the revision the caller advances when it establishes a new baseline — "
         ":reset-key (v/sub [:invoice/amount-revision invoice-id]) — or, when this control "
         "never needs an external reset, pass a stable literal such as 0, which says exactly "
         "that.")
    {:recovery :supply-a-reset-key
     :extra    {:kind  kind
                :props (error/diag-value-summary props)}}))

(defn- nil-revision!
  [kind props]
  (error/throw-error!
    :rf.error/view-control-reset-revision-nil
    where-revision
    (str "A buffered controller of kind " (pr-str kind) " was rendered with :reset-key nil. "
         "The prop IS there, so nothing was forgotten at the call site — nil is simply not "
         "a generation. The fence answers `not current` for an UNSTAMPED record, so a nil "
         "generation would make every draft in this control permanently invisible while the "
         "control went on accepting keystrokes. A nil here is usually an uninitialised "
         "counter read before its baseline exists. Seed the caller's revision, or say "
         "outright that this control never externally resets by passing a stable literal — "
         ":reset-key 0 — which is a statement rather than a silence.")
    {:recovery :seed-the-callers-reset-revision
     :extra    {:kind  kind
                :props (error/diag-value-summary props)}}))

(defn record-key
  "The key the record of a writable controller of `kind` lives under: the
  pair of the controller KIND and the `:control` address carried by
  `props`.

      (v/controller-key :my.ui/buffered-field props)
      ;; => [:my.ui/buffered-field [:invoice 42 :amount]]

  The kind is in the key so that two controller families addressed at the
  same domain identity read two records rather than one another's; the
  address is caller-supplied EDN naming the domain thing that owns the
  state.

  An absent `:control` is refused with
  `:rf.error/view-control-address-missing` rather than defaulted, because
  every controller that skipped the address would then share one record
  keyed by `nil` — a collision that presents as one field editing another
  and has no local explanation.

  `nil` IS NOT AN ADDRESS, and it is refused separately, with
  `:rf.error/view-control-address-nil`. The two are different mistakes:
  an absent prop is a call site that forgot something, while an explicit
  `nil` is a call site that supplied a value from an expression which
  answered nothing, and the fix for the second is upstream of the render.
  Presence decides which — never truthiness — so a refusal never has to
  guess at an author's intent from a falsey value.

  Two occurrences passed the SAME address share one record ON PURPOSE:
  the address is the caller's statement about which state this is, and
  sharing is how two views onto one draft are spelled. It is not
  diagnosed here."
  [kind props]
  (if (and (map? props) (contains? props :control))
    (let [address (:control props)]
      (when (nil? address)
        (nil-address! kind props))
      [kind address])
    (missing-address! kind props)))

(defn reset-revision
  "The GENERATION a buffered controller of `kind` is currently rendering
  under: the caller's `:reset-key`, taken from `props`.

      (v/controller-revision :my.ui/buffered-field props)
      ;; => 12

  It is any EDN the caller likes — a counter, a timestamp, the id of the
  decision that set the baseline — because the fence only ever asks
  whether two of them are `rf=`. What it must NOT be is the value: see
  [[current?]].

  REQUIRED, and refused with
  `:rf.error/view-control-reset-revision-missing` when absent. Optional
  would be worse than absent. A control with no revision can buffer
  perfectly well right up to the first time a caller rejects an edit, so
  omission produces a control that works in development and loses a
  rejection in production — and a caller reading a component's props
  cannot tell the two apart. Requiring it makes the obligation visible at
  every call site, and a caller that genuinely never resets says so with
  a stable literal: `:reset-key 0` reads as \"do not externally reset an
  active edit\", which is a statement rather than a silence.

  `nil` IS NOT A GENERATION, and it is refused separately, with
  `:rf.error/view-control-reset-revision-nil` — the same split
  [[record-key]] makes, for the same reason. [[current?]] answers `not
  current` for an unstamped record, so a nil generation would leave every
  draft in the control permanently invisible while the control went on
  accepting keystrokes; an uninitialised counter read before its baseline
  exists is the ordinary way to produce one, and the fix is upstream."
  [kind props]
  (if (and (map? props) (contains? props :reset-key))
    (let [revision (:reset-key props)]
      (when (nil? revision)
        (nil-revision! kind props))
      revision)
    (missing-revision! kind props)))

(defn current?
  "The GENERATION FENCE. Is work `stamped` with one generation still
  current against `revision`, the caller's generation now?

      (v/controller-current? (:reset-key record) revision)

  ONE predicate, asked at both boundaries a buffered controller has:

  - the READ, where a draft is displayed only while it is current, so a
    superseded draft falls through to the caller's live baseline; and
  - the WRITE, where a commit consults committed state and only a current
    record may produce the caller's intent.

  They are the same question and they are asked through the same
  function, because a controller whose display and whose commit disagreed
  about which generation is live would commit something the user could
  not see.

  **Why a revision and not the value.** The case this exists for is a
  caller REJECTING an edit by reasserting what it already had: the
  accepted value is `\"10\"`, the user drafts `\"bad\"`, the caller refuses
  it and stands by `\"10\"`. The value before the rejection and the value
  after it are identical, so value-equality sees nothing happen and keeps
  the rejected draft on screen — the exact bug a hand-rolled buffered
  input reproduces every time. A revision the caller advances says \"this
  is a NEW baseline decision\" in the one case equality cannot.

  **Total, and safe in the missing direction.** An absent stamp is not
  current, whatever `revision` is: a record with no generation cannot
  prove it belongs to this one, and the safe answer for work that cannot
  prove its currency is that it does not have it. A `nil` `stamped` is
  therefore an ABSENT RECORD and nothing else — [[reset-revision]] refuses
  a `nil` generation at the door, so no live record can carry one. That is
  also what makes
  a draft written from a superseded render BORN STALE rather than
  quietly authoritative — it is stamped with the generation its render
  displayed, and if the caller has moved on it is never shown and never
  committed. Comparison is `rf=` — the corpus's equality — so a revision
  may be a collection without a controller having to remember that."
  [stamped revision]
  (and (some? stamped)
       (some? revision)
       (eq/rf= stamped revision)))
