(ns ^:no-doc re-frame.freehand.control
  "The whole of Freehand's semantic-controller infrastructure: ONE
  function, [[record-key]], that answers the key a writable controller's
  record lives under.

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

  What is left for the substrate is exactly one thing — IDENTITY. Per
  D004 a controller record is addressed by the pair

      (controller kind, caller-supplied :control address)

  and never by renderer occurrence identity. That distinction is the
  whole reason this namespace exists: a derived anchor makes a sort, a
  view rename or a parent extraction into a silent state migration,
  whereas `[:invoice 42 :amount]` survives all of them. [[record-key]] is
  where the pair is formed and where an absent address is refused, so a
  controller cannot half-implement the rule — asking for the key IS what
  makes a controller writable.

  Deliberately NOT on the `re-frame.freehand` door. A controller needs no
  authoring verb: the address arrives as an ordinary prop and the state
  moves through ordinary re-frame. Publishing a verb here would advertise
  controllers as a normal way to hold state, which is the opposite of the
  ruling.

  Normative owner:
  [`spec/004-Views.md` §Semantic controllers](../../../../../spec/004-Views.md#semantic-controllers)."
  (:require [re-frame.error :as error]))

(def ^:private where
  "The raising site the address diagnostic names."
  're-frame.freehand.control/record-key)

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

(defn record-key
  "The key the record of a writable controller of `kind` lives under: the
  pair of the controller KIND and the `:control` address carried by
  `props`.

      (control/record-key :my.ui/buffered-field props)
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

  Two occurrences passed the SAME address share one record ON PURPOSE:
  the address is the caller's statement about which state this is, and
  sharing is how two views onto one draft are spelled. It is not
  diagnosed here."
  [kind props]
  (let [address (:control props)]
    (when (nil? address)
      (missing-address! kind props))
    [kind address]))
