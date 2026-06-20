(ns re-frame.machines.grammar
  "Shared machine-grammar primitives. Per Spec 005 §Transitions and
  §Transition resolution.

  ## Why this namespace exists

  The state-machine grammar — the shape of a state-tree, what a
  transition `:target` may be, and how a target resolves to a node —
  is a contract two layers must agree on EXACTLY:

    1. **registration-time validation** (`lifecycle-fx.validation`)
       rejects a machine whose targets don't resolve, BEFORE the
       runtime ever drives it; and
    2. **the runtime engine** (`transition` / `parallel`) resolves the
       same targets against the same tree at dispatch time.

  Naming the descent + form grammar once, here, makes both layers consume
  ONE source of truth rather than each owning a private copy of the
  `:states`-tree descent and the transition-value form grammar. A single
  home means a grammar addition or XState-parity refinement updates one
  implementation, with no risk that registration accepts a shape the
  runtime drives differently (or rejects one the runtime would handle).
  This is the sibling of `re-frame.machines.path-walk` (the deepest-wins
  leaf→root walk): both
  are leaf namespaces (no require on `transition` / `validation` /
  `parallel`) so either layer can require them without a load cycle.

  ## Surface

      (node-at states path)              ;; absolute root→leaf descent
      (history-node? node)               ;; :type :history pseudo-state?
      (transition-value-form v)          ;; :keyword | :vec-target |
                                         ;;   :candidate-vector | :map |
                                         ;;   :nil | :other
      (candidate-targets v)              ;; [{:present? bool :target t} ...]

  `node-at` takes a `:states` map directly (NOT a machine) — the lowest
  common denominator, so a flat machine's `:states`, a region body's
  `:states`, or any nested compound's `:states` all resolve through the
  same fn. The runtime's `transition/node-at` is a thin wrapper that
  passes `(:states machine)`; the validators pass the scope `:states`
  map they walk.")

#?(:clj (set! *warn-on-reflection* true))

;; ---- state-tree descent ---------------------------------------------------

(defn node-at
  "Walk a `:states` map down absolute `path`, returning the leaf
  state-node (or nil if `path` doesn't resolve). `states` is a flat
  machine's `:states` map, a single region body's `:states`, or any
  compound's `:states` — region names are never part of a within-scope
  path. The single home for the root→leaf descent the runtime
  (`transition/node-at` → here) and the registration validators
  (`validation` → here) both rely on, so the two can never drift.

  An empty `path` resolves to nil (the scope root is the `states` map
  itself, not a node)."
  [states path]
  (loop [m states
         p path]
    (cond
      (empty? p) nil
      :else
      (let [n (get m (first p))]
        (cond
          (nil? n)        nil
          (= 1 (count p)) n
          :else           (recur (:states n) (rest p)))))))

(defn history-node?
  "True iff `node` is a `:type :history` pseudo-state. Per Spec 005
  §History states — a targetable pseudo-state that is never an occupied
  active leaf. Shared by the runtime (`transition`) and the registration
  validators so the one-line predicate has a single home."
  [node]
  (= :history (:type node)))

;; ---- transition-value form grammar ----------------------------------------
;;
;; Per Spec 005 §Transitions §Multiple-candidate transitions and §Delayed
;; `:after` transitions: a transition-table slot value (`:on <key>` clause,
;; an `:after` delay entry, `:always`, an `:on-done`, a `:spawn :on-error`)
;; takes one of a small closed set of forms. Both the runtime normaliser
;; (`transition/normalise-candidates`) and the validation target walker
;; (`validation/candidate-targets`) discriminate on the SAME forms but
;; build DIFFERENT shapes from them — the runtime needs the candidate maps
;; (and throws on an unrecognised form with a slot-specific error id), the
;; validator needs each declared `:target` tagged with whether the
;; `:target` key was PRESENT (an absent `:target` is an internal
;; transition — always fine — distinct from a malformed `{:target nil}`).
;;
;; This is the shared form recogniser the two build on, so the cond arms
;; are written once. `:nil` is broken out from `:other` because the two
;; callers treat it identically to the forbidden-transition internal form
;; (XState v5 `on: {E: undefined}`), never as a malformed value.

(defn transition-value-form
  "Classify a transition-table slot value into its grammar form, the
  closed set both the runtime normaliser and the registration target
  walker discriminate on:

    :nil              — nil value (forbidden-transition / internal no-op;
                        the natural Clojure analogue of XState `undefined`)
    :keyword          — a bare keyword (sibling target)
    :candidate-vector — a non-empty vector of maps (multiple guarded
                        candidates, first-guard-pass wins)
    :vec-target       — any other vector (an absolute path target)
    :map              — a single transition map
    :other            — none of the above (malformed)

  Note `:vec-target` matches any vector that is NOT the non-empty
  all-maps candidate form — including the empty vector, which both
  callers treat as a (non-resolving) absolute-path target rather than a
  distinct malformed form."
  [v]
  (cond
    (nil? v)        :nil
    (keyword? v)    :keyword
    (and (vector? v) (seq v) (every? map? v)) :candidate-vector
    (vector? v)     :vec-target
    (map? v)        :map
    :else           :other))

(defn candidate-vector-form?
  "True iff `v` is the multi-candidate VECTOR form (`[{…} {…}]`) — the
  only value-form the inline-source macro keys per-index. Shared by the
  runtime (`transition/candidate-vector-form?`) and any caller that must
  decide whether a selected candidate's index is meaningful for the
  spec-path discriminator."
  [v]
  (= :candidate-vector (transition-value-form v)))

(defn candidate-targets
  "Normalise a transition slot value to the seq of `:target`s it
  declares, each tagged with `:present?` (whether the `:target` KEY was
  present, distinguishing an internal transition — `:target` absent,
  always fine — from a malformed `{:target nil}`). The registration
  target validator consumes this; the runtime normaliser
  (`normalise-candidates`) builds the candidate maps from the SAME
  form recogniser (`transition-value-form`) so the two never drift.

    a keyword              -> the target IS the keyword
    a vector of maps       -> each map's :target (present? per map)
    any other vector       -> the target IS the vector (absolute path)
    a single map           -> its :target (present? = has :target key)
    nil / other            -> no targets

  An empty vector yields no targets (it has no targetable form)."
  [v]
  (case (transition-value-form v)
    :keyword          [{:present? true :target v}]
    :candidate-vector (mapv (fn [m] {:present? (contains? m :target)
                                     :target   (:target m)}) v)
    :vec-target       [{:present? true :target v}]
    :map              [{:present? (contains? v :target) :target (:target v)}]
    ;; :nil / :other contribute no target.
    []))
