(ns re-frame.machines.internal-events
  "Public / private `:internal-events` (EP-0029 A6).

  ## What an internal event is

  An INTERNAL event is an event the machine raises and handles entirely
  WITHIN its own run-to-completion logic — machine plumbing that is NOT part
  of the machine's PUBLIC event surface. A view, a test, or another handler
  must not be able to drive the machine by dispatching one. The machine
  declares them up front:

      {:internal-events #{:tick :retry/internal}
       :states
       {:waiting
        {:entry {:raise :tick}
         :on    {:tick {:target :checking}}}}}

  An internal `:raise` (`[:raise [:tick]]` from an `:entry` / `:exit` /
  transition action) may PRODUCE `:tick`; the raised event re-enters the
  macrostep's internal-event queue and runs NORMAL transition selection
  against the `:on` map (per Spec 005 §Order with `:raise`). What internal
  events ADD over an ordinary `:on` clause is the public / private
  BOUNDARY: an EXTERNAL dispatch of a declared internal event is REJECTED.

  ## DIVERGENCE from XState — a Clojure SET, NOT an array (A6)

  XState v6's `internalEvents` is an ARRAY. re-frame2 declares them as a
  Clojure SET form — `:internal-events #{:tick}` — a re-frame2 idiom: a set
  is the natural membership collection (the runtime boundary check is one
  `contains?`), order is irrelevant, and a duplicate is structurally
  impossible. This is alpha-drift-confirmed against xstate v6 alpha.3 (no
  grammar drift vs the EP basis). Behavioural parity (a private event
  surface) without API-mimicry (the JavaScript array form).

  An internal event's HANDLER is an ordinary `:on` clause (`:on {:tick
  {:target :checking}}` above) — that is HOW the machine handles the raised
  event, and is the EXPECTED shape (it is NOT a collision; the EP example
  declares exactly this). What `:internal-events` ADDS is purely the
  visibility rule: the SAME name, dispatched from OUTSIDE, is refused.

  ## The public / private boundary — enforced at the dispatch boundary

  The boundary is enforced at the MACHINE DISPATCH BOUNDARY — the handler
  entry, where an event arrives via `reg-event` dispatch (`rf/dispatch
  [:my-machine [:tick]]`). An internal `:raise` is drained INSIDE the
  macrostep through the FIFO internal-event queue (`transition` /
  `parallel`); it NEVER re-enters via `reg-event` dispatch. So the only
  events reaching the handler entry are EXTERNAL dispatches — and a routed
  inner event whose key is a declared internal event is rejected there:
  `internal-event-external?` is the boundary predicate, the handler emits
  `:rf.error/machine-internal-event-external-dispatch` and short-circuits
  with NO state change (atomic — the committed snapshot is untouched,
  exactly the benign no-op shape an unhandled event takes). A self-raised
  internal event handled within the machine's own plumbing is unaffected;
  only the outside caller is refused.

  ## Registration rules (fail-loud — EP-0029 A6 + Backwards Compatibility)

  Validated on the registered spec so a misdeclaration is caught BEFORE the
  runtime ever drives the machine:

    - `:internal-events` (when present) MUST be a SET of keywords — a vector
      (the rejected XState array form), a non-set collection, or a set with
      a non-keyword member fails loud
      (`:rf.error/machine-bad-internal-events`).
    - a declared internal event MUST NOT be a RESERVED `:rf/*` framework
      event (the single-root reserved namespace per Conventions.md — the
      synthetic creation marker `:rf.machine/start`, the `:rf.machine/done`
      completion signal, `:rf.machine.timer/after-elapsed`, the
      `:rf.machine.spawn/*` family, …). A framework-owned lifecycle event is
      inherently PUBLIC framework traffic threaded through the standard
      dispatch pipeline; it cannot be repurposed as a machine's PRIVATE
      event — declaring one internal is the public-vs-private collision the
      grammar rejects (`:rf.error/machine-internal-event-reserved`).

  This is a LEAF namespace (no require on `transition` / `validation` /
  `parallel` / `registration`) so the validators and the dispatch boundary
  can both require it without a load cycle — the sibling of
  `re-frame.machines.choice` / `re-frame.machines.timeout`."
  (:require [clojure.string :as str]
            [re-frame.error :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- declaration accessor + boundary predicate ----------------------------

(defn internal-events
  "The machine's declared `:internal-events` as a SET (or nil when the
  machine declares none). The single accessor both the boundary predicate
  and the public-collision validator read, so the declaration shape has one
  reader."
  [machine]
  (:internal-events machine))

(defn internal-event-external?
  "True iff `inner-event` (the routed inner event vector arriving at the
  machine dispatch boundary, e.g. `[:tick]`) names a declared INTERNAL
  event of `machine` — i.e. an EXTERNAL dispatch of a private event that
  the boundary must REJECT. nil-safe on both arguments: a machine with no
  `:internal-events` is never external-rejecting, and a non-vector / empty
  inner event names no internal event.

  This is the dispatch-boundary check ONLY. An internal `:raise` is drained
  inside the macrostep's FIFO internal-event queue and never reaches the
  handler entry, so this predicate never fires for a self-raised event —
  only for an outside caller's `rf/dispatch`."
  [machine inner-event]
  (boolean
    (when-let [ie (internal-events machine)]
      (and (vector? inner-event)
           (seq inner-event)
           (contains? ie (first inner-event))))))

;; ---- registration-time validation -----------------------------------------
;;
;; Per EP-0029 A6 + Backwards-Compatibility: a malformed `:internal-events`
;; declaration, or a name that is BOTH a declared internal event AND a
;; public `:on` transition key, fails loud at registration.

(defn- internal-events-error
  "Build an `:internal-events`-grammar validation ex-info with the canonical
  thrown-error shape (Spec 009). `error-id` is the `:rf.error/id`
  discriminator; `reason` is the human diagnostic; `extra` merges per-site
  slots."
  [error-id reason extra]
  (error/thrown-ex-info error-id 'rf/reg-machine reason
                        {:recovery :fix-registration :extra extra}))

(defn- reserved-rf-event?
  "True iff `event-id` is a RESERVED `:rf/*` framework event — its keyword
  namespace is `rf` or begins with `rf.` (the single-root reserved scheme
  per Conventions.md). Restated here (rather than required from `transition`)
  so this leaf ns needs no require on the runtime engine — mirrors the
  inverse of `transition/unhandled-event-no-op?`'s reserved-namespace test."
  [event-id]
  (let [ns (when (keyword? event-id) (namespace event-id))]
    (boolean (and ns (or (= ns "rf") (str/starts-with? ns "rf."))))))

(defn validate-internal-events!
  "Validate the machine's `:internal-events` declaration at registration
  (EP-0029 A6). A machine that declares no `:internal-events` is a no-op.
  Enforces every rule in the ns docstring:

    - `:internal-events` MUST be a SET of keywords (the re-frame2 set-form
      divergence from XState's array) — a vector / non-set / non-keyword
      member fails with `:rf.error/machine-bad-internal-events`;
    - no declared internal event may be a RESERVED `:rf/*` framework event —
      a framework-owned lifecycle event is inherently public and cannot be
      repurposed as a machine's private event
      (`:rf.error/machine-internal-event-reserved`)."
  [machine]
  (when (contains? machine :internal-events)
    (let [ie (:internal-events machine)]
      ;; Shape: a SET of keywords. A vector is the REJECTED XState array
      ;; form; a non-set collection or a non-keyword member is malformed.
      (when-not (and (set? ie) (every? keyword? ie))
        (throw (internal-events-error
                 :rf.error/machine-bad-internal-events
                 (str "a machine's :internal-events declaration "
                      (pr-str ie) " is malformed — it MUST be a SET of"
                      " keywords, e.g. #{:tick :retry/internal}. A VECTOR is"
                      " the REJECTED XState array form (EP-0029 A6"
                      " operator-ruled divergence — re-frame2 uses a Clojure"
                      " set: membership is the natural shape, order is"
                      " irrelevant, duplicates are impossible).")
                 {:internal-events ie})))
      ;; Public / private split: a declared internal event must NOT be a
      ;; RESERVED `:rf/*` framework event — those are framework-owned public
      ;; lifecycle traffic (the synthetic creation marker, the `:on-done`
      ;; completion signal, the `:after` timer event, the spawn kick-off)
      ;; and can never be a machine's PRIVATE event.
      (let [reserved (filterv reserved-rf-event? ie)]
        (when (seq reserved)
          (throw (internal-events-error
                   :rf.error/machine-internal-event-reserved
                   (str "the machine declares the RESERVED framework event(s) "
                        (pr-str (vec reserved)) " in :internal-events — a"
                        " reserved :rf/* event (the synthetic :rf.machine/start"
                        " creation marker, :rf.machine/done, the"
                        " :rf.machine.timer/after-elapsed timer event, the"
                        " :rf.machine.spawn/* family, …) is framework-owned"
                        " PUBLIC lifecycle traffic threaded through the standard"
                        " dispatch pipeline; it cannot be repurposed as a"
                        " machine's PRIVATE event (EP-0029 A6 — the public /"
                        " private split). Declare a non-reserved internal event"
                        " name, e.g. :tick.")
                   {:internal-events ie
                    :reserved        (vec reserved)})))))))
