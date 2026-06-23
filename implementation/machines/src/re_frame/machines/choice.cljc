(ns re-frame.machines.choice
  "`:type :choice` transient / choice states (EP-0029 A5).

  ## What a `:choice` state is

  A choice state is an IMMEDIATE routing node: enter it, evaluate its
  guarded candidates in declaration order, take the first whose `:guard`
  passes, and leave — all within the same macrostep, with NO event needed.
  xstate/SCXML term: a **transient** (eventless) state. It names the
  intent — \"this is a decision node\" — more clearly than encoding the
  same routing as an `:always`-bearing ordinary state.

      {:checking
       {:type :choice
        :choice [{:guard :valid?
                  :target :accepted}
                 {:target :rejected}]}}

  The candidate VECTOR is the same first-guard-pass-wins candidate form a
  normal `:on` / `:after` / `:always` slot takes (`{:guard … :target …
  :action …}` maps). The LAST candidate is conventionally an unguarded
  DEFAULT / else branch (`{:target :rejected}`) so the choice state always
  resolves — see the registration rules below.

  ## DIVERGENCE from XState — declarative candidate array, NOT a fn (A2 / C1)

  XState v6 lets a `choice` state's routing be a `choice`-FUNCTION. re-frame2
  REJECTS that (EP-0029 A2 \"a function may never be the edge\" / C1
  \"opaque function-valued transitions\"). re-frame2's `:choice` is a
  DECLARATIVE guarded-candidate array `[{:guard … :target …}]` — the edge
  topology stays data so diagrams, model tests, conformance fixtures, and AI
  tools can read it. A function-valued `:choice` (`:choice (fn …)`) fails
  LOUD at registration with `:rf.error/machine-bad-choice`.

  ## Relationship to `:always` — distinct intent, ONE mechanism

  A choice state IS an immediate eventless routing node, which is exactly
  what `:always` already implements: on entry (or any transition that lands
  in the state) the runtime walks the candidate vector leaf→root, takes the
  first guard-pass, and settles it within the macrostep drain (Spec 005
  §Eventless `:always` / §Drain semantics). So `:choice` DESUGARS — at
  registration / transition normalisation time — into an `:always` slot
  carrying the same candidate vector, dropping the `:type :choice` / `:choice`
  keys. The whole `:always` machinery (the leaf→root candidate walk, the
  first-guard-pass select, the macrostep microstep loop, the birth-time
  settle so a transient INITIAL choice leaf resolves on start) then drives it
  unchanged — no duplicate engine code. The grammar is distinct; the runtime
  is reused. This mirrors how `:timeout` desugars onto `:after`
  (`re-frame.machines.timeout`): a named-intent authoring concept lowering
  onto an existing mechanism.

  ## Registration rules (fail-loud — EP-0029 A5 + Backwards Compatibility)

  Validated on the RAW (pre-desugar) spec so diagnostics name the
  `:type :choice` / `:choice` keys the author wrote:

    - `:type :choice` REQUIRES a `:choice` slot, and a `:choice` slot
      REQUIRES `:type :choice` — one without the other is meaningless
      (`:rf.error/machine-choice-missing-choice` /
      `:rf.error/machine-choice-without-type`).
    - the `:choice` value MUST be a NON-EMPTY vector of candidate maps — a
      fn (the rejected XState form), a keyword, an empty vector, or any
      other shape fails loud (`:rf.error/machine-bad-choice`).
    - a choice state MUST NOT also declare ordinary waiting-state behaviour
      — `:entry`, `:exit`, `:on`, `:always`, `:after`, `:timeout`,
      `:on-timeout`, `:spawn`, `:spawn-all`, `:initial`, `:states`,
      `:final?` — a choice state only routes
      (`:rf.error/machine-choice-extra-keys`).
    - the `:choice` vector MUST end with an unguarded DEFAULT / else
      candidate (a candidate with no `:guard`, an unconditional fallback).
      Without one the choice state could be ENTERED with every guard
      failing and NO candidate to take — a stuck transient node. This is
      the static \"no matching candidate + no default\" rejection (the EP's
      fail-loud case); guards are runtime fns so the only registration-time
      guarantee that a choice always resolves is a present default
      (`:rf.error/machine-choice-no-default`).
    - a choice candidate that targets the choice state's OWN declaring
      state is a self-loop (an immediate eventless cycle), rejected exactly
      as an `:always` self-loop is (`:rf.error/machine-choice-self-loop`)."
  (:require [re-frame.error :as error]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- predicates ------------------------------------------------------------

(defn choice-node?
  "True iff `node` is a `:type :choice` transient/choice state. The leaf
  predicate the desugar fast-path scan + the validators share."
  [node]
  (and (map? node)
       (= :choice (:type node))))

(def reserved-choice-keys
  "The state-node keys a choice state MUST NOT declare alongside
  `:type :choice` / `:choice` (EP-0029 A5: a choice state only routes — it
  carries no ordinary waiting-state behaviour). A choice state declares
  EXACTLY `:type` + `:choice` (+ tooling `:meta` / `:source-coords`)."
  #{:entry :exit :on :always :after :timeout :on-timeout
    :spawn :spawn-all :initial :states :final? :output-key})

;; ---- desugaring `:choice` → `:always` -------------------------------------
;;
;; `:choice` is a DISTINCT authoring concept that LOWERS onto the existing
;; `:always` eventless-transition mechanism (EP-0029 A5 — "distinct intent,
;; one mechanism"). The desugar runs at registration / transition
;; normalisation time so the runtime never sees `:type :choice` / `:choice`
;; directly — it sees the equivalent `:always` candidate vector. The grammar
;; (the `:type :choice` marker + the `:choice` slot, with its own validation)
;; is what the AUTHOR writes; the `:always` slot is what the ENGINE drives.

(defn desugar-node-choice
  "Desugar a single state-node's `:type :choice` / `:choice` into an
  `:always` slot carrying the same candidate vector, dropping the
  `:type` / `:choice` keys. A node that is not a choice state is returned
  unchanged (identity-preserving). Validation has already rejected a choice
  state that ALSO declares `:always` (a reserved key), so this never
  clobbers an existing `:always`."
  [node]
  (if-not (choice-node? node)
    node
    (-> node
        (assoc :always (:choice node))
        (dissoc :type :choice))))

(defn- walk-states
  "Recursively desugar `:choice` on every node under a `:states` map,
  returning the rewritten `:states` map. Nil-safe. A choice state is a
  leaf (validation rejects `:states` on it), so the recursion only descends
  ordinary compound nodes."
  [states]
  (when states
    (reduce-kv
      (fn [acc k node]
        (assoc acc k
               (let [node' (desugar-node-choice node)]
                 (cond-> node'
                   (:states node') (update :states walk-states)))))
      {}
      states)))

(defn- any-choice?
  "True iff ANY node in the machine (every state under `:states`, every
  region root + its states) declares `:type :choice`. A cheap
  short-circuiting structural scan that lets `desugar-choices` return the
  input UNCHANGED (no map rebuild) for the overwhelmingly common
  choice-free machine on the hot per-transition path."
  [machine]
  (letfn [(scan-states [states]
            (some (fn [[_ n]]
                    (or (choice-node? n)
                        (scan-states (:states n))))
                  states))]
    (or (scan-states (:states machine))
        (some (fn [[_ body]]
                (or (choice-node? body)
                    (scan-states (:states body))))
              (:regions machine)))))

(defn desugar-choices
  "Rewrite a whole machine spec so every `:type :choice` transient state is
  lowered into an ordinary state carrying its `:choice` candidate vector
  under `:always`, leaving NO `:type :choice` / `:choice` keys for the
  runtime to see. Pure; idempotent. Handles flat / compound machines
  (`:states`) and parallel machines (`:regions` — each region body is a
  state-node with its own `:states`).

  Fast-path: a machine with NO `:type :choice` anywhere is returned
  UNCHANGED (no map rebuild), so the choice-free common case on the hot
  per-transition path pays only one short-circuiting scan.

  This is the single normalisation seam the registration parser and the
  pure `machine-transition` / birth-cascade entries all apply, so
  registration validation, the transition engine, and the conformance
  `:machine-transition` op all observe the SAME desugared `:always` form
  (they can never drift). It runs AFTER the `:timeout` desugar — a choice
  state cannot carry `:timeout` (validation rejects it), so the order is
  immaterial for correctness, but choice runs second so a future grammar
  that lowered onto a choice would already be settled."
  [machine]
  (if-not (any-choice? machine)
    machine
    (cond-> machine
      (:states machine)  (update :states walk-states)
      (:regions machine) (update :regions
                                 (fn [regions]
                                   (reduce-kv
                                     (fn [acc rn body]
                                       (assoc acc rn
                                              (cond-> (desugar-node-choice body)
                                                (:states body)
                                                (update :states walk-states))))
                                     {}
                                     regions))))))

;; ---- registration-time validation -----------------------------------------
;;
;; Validation runs on the RAW (pre-desugar) machine so error messages name
;; the `:type :choice` / `:choice` keys the author wrote. It enforces, per
;; EP-0029 A5 + Backwards-Compatibility, the rules catalogued in the ns
;; docstring.

(defn- choice-error
  "Build a `:choice`-grammar validation ex-info with the canonical
  thrown-error shape (Spec 009). `error-id` is the `:rf.error/id`
  discriminator; `reason` is the human diagnostic; `extra` merges
  per-site slots."
  [error-id reason extra]
  (error/thrown-ex-info error-id 'rf/reg-machine reason
                        {:recovery :fix-registration :extra extra}))

(defn- candidate-vector?
  "True iff `v` is the declarative guarded-candidate array form a `:choice`
  slot MUST take — a NON-EMPTY vector of maps. Mirrors
  `grammar/transition-value-form`'s `:candidate-vector` arm; restated here
  so this leaf ns needs no require on the runtime grammar ns."
  [v]
  (and (vector? v) (seq v) (every? map? v)))

(defn- candidate-self-target?
  "True iff a `:choice` candidate's `:target` resolves to the choice
  state's own declaring `path` (an immediate eventless self-loop). A
  keyword target self-targets when it equals the declaring state's own key
  (the last element of `path`); a vector target self-targets when it equals
  `path`. A candidate with NO `:target` is an internal action-only branch
  (never a self-loop — symmetric with `:always`). Mirrors
  `validation/always-self-loop?`."
  [path candidate]
  (let [target (:target candidate)]
    (cond
      (nil? target)     false
      (keyword? target) (= target (peek path))
      (vector? target)  (= target (vec path)))))

(defn validate-node-choice!
  "Validate one state-node's `:type :choice` / `:choice` grammar at
  absolute `path` (the declaring state's path; `state-key` its leaf key).
  Enforces every rule in the ns docstring. A node that declares NEITHER
  `:type :choice` NOR `:choice` is a no-op."
  [path state-key node]
  (let [has-type?   (= :choice (:type node))
        has-choice? (contains? node :choice)
        choice      (:choice node)]
    (cond
      ;; Neither marker — not a choice state, nothing to validate.
      (and (not has-type?) (not has-choice?))
      nil

      ;; `:choice` slot without the `:type :choice` marker — the candidate
      ;; vector has no transient node to route from.
      (and has-choice? (not has-type?))
      (throw (choice-error
               :rf.error/machine-choice-without-type
               (str "state " state-key " declares a :choice candidate vector"
                    " but is not a :type :choice state — a :choice slot is"
                    " meaningful only on a transient choice state (EP-0029 A5)."
                    " Add :type :choice, or move the candidates to :always.")
               {:state state-key}))

      ;; `:type :choice` with no `:choice` slot — a choice state must
      ;; declare its candidates.
      (and has-type? (not has-choice?))
      (throw (choice-error
               :rf.error/machine-choice-missing-choice
               (str "state " state-key " is a :type :choice state but declares"
                    " no :choice candidate vector — a choice state MUST name the"
                    " guarded candidates it routes among (EP-0029 A5). Add a"
                    " :choice [{:guard … :target …} … {:target <default>}] vector.")
               {:state state-key}))

      ;; Both markers present — the `:choice` value must be the declarative
      ;; NON-EMPTY candidate-vector form. A fn is the REJECTED XState
      ;; choice-function (A2 / C1); a keyword / empty vector / other shape
      ;; is malformed.
      (not (candidate-vector? choice))
      (throw (choice-error
               :rf.error/machine-bad-choice
               (str "state " state-key " declares a malformed :choice "
                    (pr-str choice) " — a :choice must be a DECLARATIVE,"
                    " NON-EMPTY vector of guarded-candidate maps"
                    " ([{:guard … :target …} … {:target <default>}]). A"
                    " function-valued :choice is REJECTED (EP-0029 A2 / C1"
                    " operator-ruled divergence — a function may never be the"
                    " edge; the candidate array keeps the routing declarative).")
               {:state state-key :choice choice}))

      :else
      (do
        ;; A choice state only routes — it must not carry ordinary
        ;; waiting-state behaviour (A5).
        (let [extra (filterv #(contains? node %) reserved-choice-keys)]
          (when (seq extra)
            (throw (choice-error
                     :rf.error/machine-choice-extra-keys
                     (str "state " state-key " is a :type :choice state but also"
                          " declares " (pr-str extra) " — a choice state ONLY"
                          " routes its guarded candidates immediately on entry;"
                          " it carries no :entry / :exit / :on / :always / :after"
                          " / :timeout / :spawn / :spawn-all / :initial / :states"
                          " / :final? behaviour (EP-0029 A5). Remove the extra"
                          " keys, or use an ordinary state with :always.")
                     {:state state-key :extra-keys extra}))))
        ;; The candidate vector MUST end with an unguarded DEFAULT / else
        ;; branch so the choice state always resolves — the static
        ;; "no matching candidate + no default" rejection.
        (when-not (some #(not (contains? % :guard)) choice)
          (throw (choice-error
                   :rf.error/machine-choice-no-default
                   (str "state " state-key " is a :type :choice state whose"
                        " every :choice candidate is GUARDED — there is no"
                        " unconditional DEFAULT / else branch. If every guard"
                        " fails the choice state has NO candidate to take and"
                        " is stuck (EP-0029 A5). Add a final unguarded"
                        " candidate, e.g. {:target <fallback>}.")
                   {:state state-key :choice choice})))
        ;; A candidate that targets the choice state's own declaring state is
        ;; an immediate eventless self-loop — rejected exactly as an
        ;; `:always` self-loop is.
        (doseq [candidate choice]
          (when (candidate-self-target? path candidate)
            (throw (choice-error
                     :rf.error/machine-choice-self-loop
                     (str "state " state-key " declares a :choice candidate that"
                          " targets itself — an immediate eventless self-loop"
                          " runs to depth-exceeded or is a no-op. Target a"
                          " distinct state (EP-0029 A5).")
                     {:state state-key}))))))))
