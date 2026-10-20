(ns re-frame.machines.cofx-attach
  "Machine consumer-attachment for recordable coeffects. Per Spec 005
  §Consumer attachment.

  ## What this namespace does

  A machine whose guards / actions / `:entry` / `:exit` callbacks consume host
  facts (the wall-clock time, a generated UUID, a random draw, a recorded sub
  value) declares them ONCE, with **`:rf.cofx/requires`** at the top level of
  its definition. The handler ensures that one set onto the in-flight
  `:rf.cofx` record on every event addressed to the machine, before birth and
  before transition selection, so every callback the macrostep runs — those a
  same-macrostep `:raise` selects included — reads the same ensured record. A
  guard-consumed fact is therefore never generated mid-selection: a host read
  there would put nondeterminism in the fold's most sensitive spot (replay
  selecting a DIFFERENT transition). Generated values are written back into
  the record the epoch captures, so replay re-presents them.

  Three pieces:

    1. **Placement (registration-time hard error).** `:rf.cofx/requires` is
       legal ONLY at the machine root. Anywhere else — a named `:guards` /
       `:actions` entry, a transition slot, a state node, `:entry` / `:exit`,
       a region body — is `:rf.error/machine-cofx-requires-inline`, naming the
       site the author wrote.

    2. **Parse + ensure.** `parse-machine-requires` parses the declaration
       with the SAME `re-frame.cofx/parse-requires` the event path uses (so a
       malformed declaration raises the same categories), with `allow-sub?`
       true, and stamps the parsed set on the definition. `ensure-cofx`
       delivers it through `re-frame.cofx/deliver-declared-cofx` under the
       resolved mint policy.

    3. **Lint (dev-only diagnostic).** `:rf.warning/machine-cofx-consume-
       undeclared` — a named guard/action whose source reads a registered cofx
       the machine did not declare; `:rf.warning/machine-cofx-ambient-durable`
       — the machine declaring an AMBIENT-grade id (`durable state folds facts,
       never reads`).

  ## Sub-valued recordable source

  The declaration may carry the map form `{:rf/sub query-v :as fact-id}` — a
  sub-valued RECORDABLE coeffect source, accepted machines-only (the
  `allow-sub?` arity of `re-frame.cofx/parse-requires`). It is a RECORDED
  TOKEN FACT, not permission for a callback to read the sub cache: `query-v` is
  evaluated once per event against the committed pre-cascade frame-state and
  the value recorded on the token under `fact-id`; strict replay re-presents it
  verbatim. Core cofx reaches the sub through the machines-owned
  `:cofx/eval-recordable-sub` late-bind hook this ns publishes (core cofx
  cannot static-require subs / frame). It is a rung-3 tool of the minting
  ladder (derive-from-state ▸ payload threading ▸ recorded coeffect), NOT the
  default (Spec 005 §Causal host facts).

  XState offers NO parity guidance here — it has no replay contract; this
  surface is re-frame2's own (EP-0017 §Rationale — Why consumer attachment
  for machines)."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [re-frame.cofx :as rf.cofx]
            [re-frame.error :as rf.error]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.machines.choice :as rf.machines.choice]
            [re-frame.machines.grammar :as rf.machines.grammar]
            [re-frame.registrar :as rf.registrar]
            [re-frame.subs :as rf.subs]
            [re-frame.trace :as rf.trace
             #?@(:cljs [:include-macros true])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private requires-key :rf.cofx/requires)

(def ^:private parsed-key
  "Reserved machine-spec key carrying the parsed machine-level declaration,
  stamped by `parse-machine-requires` and read by `ensure-cofx` and
  `lint-machine!`. Owner-qualified under `:rf/*` (Conventions §The
  single-root reserved set), so it threads through the handler closure with
  the rest of the machine def and reverts atomically with re-registration."
  :rf/cofx-requires)

;; ---- placement (registration-time hard error) -----------------------------

(defn- requires-misplaced-error
  "Throw `:rf.error/machine-cofx-requires-inline` — `:rf.cofx/requires` was
  found somewhere other than the machine root. Registration is rejected.
  Spec 005 §Consumer attachment."
  [where detail]
  (rf.error/throw-error!
    :rf.error/machine-cofx-requires-inline
    'rf/reg-machine
    (str ":rf.cofx/requires is declared ONCE, at the top level of the machine "
         "definition — never on a :guards / :actions entry, a transition, a "
         "state, :entry / :exit or a region. The machine ensures that one set "
         "on every event addressed to it (Spec 005 §Consumer attachment). Move "
         "the requirement to the machine's top-level :rf.cofx/requires. Found "
         "at: " where ".")
    {:recovery :no-recovery
     :extra    detail}))

(defn- check-not-declared!
  "Reject `:rf.cofx/requires` on `v` — a slot value (a map, or a vector of
  candidate maps). `where` names the declaring site for the diagnostic."
  [where v]
  (cond
    (and (map? v) (contains? v requires-key))
    (requires-misplaced-error where {:offending v})

    (vector? v)
    (doseq [el v]
      (when (and (map? el) (contains? el requires-key))
        (requires-misplaced-error where {:offending el})))))

(defn- always-entries
  "A state node's `:always` candidates (a `:type :choice` node's `:choice`
  vector, which lowers to `:always`), or `[]`. Malformed shapes degrade to
  `[]`: `validate-machine!` refuses them under their own categories."
  [node]
  (let [a (if (rf.machines.choice/choice-node? node) (:choice node) (:always node))]
    (if (nil? a)
      []
      (or (rf.machines.grammar/candidate-maps a) []))))

(defn- walk-nodes-with-path
  "Yield `[absolute-path node]` pairs for every map state node, recursing
  through map `:states`; for a parallel machine, per region. This sweep runs
  before `validate-machine!`, which refuses any other shape."
  [machine]
  (letfn [(states-of [n]
            (let [s (:states n)] (when (map? s) s)))
          (walk [path nodes]
            (mapcat (fn [[k n]]
                      (when (map? n)
                        (let [p (conj path k)]
                          (cons [p n] (walk p (states-of n))))))
                    nodes))]
    (if (and (map? (:regions machine)) (seq (:regions machine)))
      (mapcat (fn [[_r body]] (walk [] (states-of body))) (:regions machine))
      (walk [] (states-of machine)))))

(defn- clause-entries
  "The entries of `node`'s `:on` / `:after` clause, or nil when the clause is
  absent or not a map (`validate-machine!` refuses a malformed clause)."
  [node slot]
  (let [clause (get node slot)]
    (when (map? clause) clause)))

(defn- check-slots!
  "Every transition and callback slot of `node`, labelled under the key the
  author wrote: a choice node's candidates as `:choice`, a `:timeout`'s
  `:on-timeout`, and a `:spawn`'s `:on-error` / `:on-done` apart from `:on`."
  [where node]
  (doseq [[_ev v] (clause-entries node :on)]    (check-not-declared! (str where " :on") v))
  (doseq [[_d v]  (clause-entries node :after)] (check-not-declared! (str where " :after") v))
  (check-not-declared! (str where " :on-timeout") (:on-timeout node))
  (doseq [slot [:on-timeout :on-error :on-done]]
    (check-not-declared! (str where " :spawn " slot) (get-in node [:spawn slot])))
  (let [label (if (rf.machines.choice/choice-node? node) " :choice" " :always")]
    (doseq [e (always-entries node)] (check-not-declared! (str where label) e)))
  (when (contains? node :on-done)
    (check-not-declared! (str where " :on-done") (:on-done node)))
  (check-not-declared! (str where " :entry") (:entry node))
  (check-not-declared! (str where " :exit")  (:exit node)))

(defn- check-placement!
  "Reject `:rf.cofx/requires` everywhere but the machine root: named
  `:guards` / `:actions` entries, every state node and its slots, region
  bodies, and the root's own transition slots."
  [machine]
  (doseq [slot [:guards :actions]
          [eid v] (get machine slot)]
    (when (and (map? v) (contains? v requires-key))
      (requires-misplaced-error (str slot " " eid) {:offending v})))
  (doseq [[path node] (walk-nodes-with-path machine)]
    (let [where (str "state " (peek path))]
      (when (contains? node requires-key)
        (requires-misplaced-error where {:offending (select-keys node [requires-key])}))
      (check-slots! where node)))
  (when (map? (:regions machine))
    (doseq [[r body] (:regions machine)
            :when (map? body)]
      (when (contains? body requires-key)
        (requires-misplaced-error (str "region " r) {:offending (select-keys body [requires-key])}))
      (check-slots! ":rf/root" body)))
  (check-slots! ":rf/root" machine))

;; ---- parse (registration-time) --------------------------------------------

(defn parse-machine-requires
  "Check placement and parse the machine-level `:rf.cofx/requires`, returning
  `machine` with the parsed set stamped under `:rf/cofx-requires`. Per Spec 005
  §Consumer attachment.

  Parsing goes through `re-frame.cofx/parse-requires` with `allow-sub?` true,
  so a malformed declaration raises `:rf.error/cofx-request-invalid` / a
  duplicate id `:rf.error/cofx-name-collision` exactly as a `reg-event`
  handler's does, and the `{:rf/sub query-v :as fact-id}` source is accepted.
  Pure and idempotent; called at registration on the definition as written,
  before `validate-machine!`, and by `make-machine-handler`."
  [machine]
  (check-placement! machine)
  (assoc machine parsed-key
         (rf.cofx/parse-requires [:rf/machine requires-key] (get machine requires-key) true)))

;; ---- the ensure step -------------------------------------------------------

(defn ensure-cofx
  "Ensure `machine`'s parsed declaration onto the in-flight `:rf.cofx` record
  `recorded`, returning the (possibly generation-augmented) record — equal to
  `recorded` when nothing was generated, and `recorded` itself when the
  machine declares nothing.

  Runs once per event addressed to the machine, before birth and before
  transition selection (Spec 005 §Consumer attachment). Delivery is
  `re-frame.cofx/deliver-declared-cofx` — the event path's satisfaction
  surface — under the effective `mint-policy`, so `:strict` (replay, the
  `:test` preset) refuses to mint a declared-absent generator-backed fact and
  surfaces `:rf.error/missing-required-cofx`. Machine callbacks read the WHOLE
  record off `:rf.cofx`, so the delivered flat spread is discarded.
  `frame-id` tags the emits; `failing-id` is the machine / actor id."
  [machine recorded frame-id failing-id mint-policy]
  (let [parsed (get machine parsed-key)]
    (if (empty? parsed)
      recorded
      (:rf.cofx
       (rf.cofx/deliver-declared-cofx {} parsed (or recorded {}) failing-id
                                      frame-id mint-policy)))))

;; ---- lint (dev-only diagnostic) -------------------------------------------
;;
;; Per Spec 005 §Consumer attachment + EP-0017 §9: recommendation-grade,
;; registration-time, DCE'd in production. The consume-undeclared scan is a
;; shallow source-form heuristic over the `:source-code` the macro captured; a
;; false negative is acceptable (an undeclared read is simply un-ensured).

(defn- source-code->form
  "Best-effort read of the macro-captured `:source-code` (a `pr-str` string)
  back to a form for the token scan, with the safe EDN reader. A non-string is
  returned as-is; an unreadable string yields nil — left unscanned, never
  mis-flagged."
  [source-code]
  (if (string? source-code)
    (try
      (edn/read-string source-code)
      (catch #?(:clj Throwable :cljs :default) _ nil))
    source-code))

(defn- emit-consume-undeclared!
  [machine-id slot entry-id leaf]
  (rf.trace/emit! :warning :rf.warning/machine-cofx-consume-undeclared
               {:machine-id machine-id
                :slot       slot
                :entry-id   entry-id
                :rf.cofx/id leaf
                :recovery   :warned-and-proceeded}))

(defn- emit-ambient-durable!
  [machine-id cofx-id]
  (rf.trace/emit! :warning :rf.warning/machine-cofx-ambient-durable
               {:machine-id machine-id
                :rf.cofx/id cofx-id
                :recovery   :warned-and-proceeded}))

(defn lint-machine!
  "Run the dev-only consumer-attachment lints over `machine` (carrying the
  parsed declaration), tagging diagnostics with `machine-id`. Emits
  `:rf.warning/*` diagnostics, never throws; a no-op in production."
  [machine-id machine]
  (when rf.interop/debug-enabled?
    (let [parsed   (get machine parsed-key)
          declared (set (map :id parsed))]
      ;; ambient-durable — an ambient-grade id folded into a machine, whose
      ;; actions may write durable `:data`.
      (doseq [{:keys [id]} parsed
              :let  [meta (rf.registrar/lookup :cofx id)]
              :when (and meta (not (:recordable? meta)))]
        (emit-ambient-durable! machine-id id))
      ;; consume-undeclared — a registered cofx id appearing in a named
      ;; entry's source that the machine did not declare.
      (doseq [slot            [:guards :actions]
              [entry-id entry] (get machine slot)
              :let  [form (when (map? entry) (source-code->form (:source-code entry)))]
              :when form
              t     (set (filter keyword? (tree-seq coll? seq form)))
              :when (and (qualified-keyword? t)
                         (not (contains? declared t))
                         (some? (rf.registrar/lookup :cofx t)))]
        (emit-consume-undeclared! machine-id slot entry-id t)))))

;; ---- sub-valued recordable source evaluator -------------------------------
;;
;; Core `re-frame.cofx/deliver-declared-cofx` delivers a machine sub-valued
;; recordable source (`{:rf/sub query-v :as fact-id}`) through the
;; `:cofx/eval-recordable-sub` late-bind hook — core cofx cannot static-require
;; `subs` / `frame`, and publishing the hook from the machines artefact keeps
;; the capability machines-scoped. The query is computed against the committed
;; PRE-CASCADE frame-state via the pure `compute-sub` path, which reads the
;; full `{:rf.db/app … :rf.db/runtime …}` value, so an app-db sub and a
;; runtime-db sub compute coherently. At ensure time the frame's stored state
;; IS the pre-cascade snapshot (the CSI §2 processing-start grain).

(rf.late-bind/set-fn! :cofx/eval-recordable-sub
  (fn eval-recordable-sub [query-v frame-id]
    (rf.subs/compute-sub query-v (rf.frame/frame-state-value frame-id))))
