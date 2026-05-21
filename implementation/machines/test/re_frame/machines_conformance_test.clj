(ns re-frame.machines-conformance-test
  "Per rf2-d0wem (and rf2-ra1he §TE5). Drives every conformance-corpus
  fixture whose `:fixture/calls` exercise `:machine-transition`
  through `re-frame.machines/machine-transition` and `=`-checks the
  result against the recorded `:expect-next-snapshot` /
  `:expect-effects`.

  The conformance corpus at `spec/conformance/fixtures/*.edn` is the
  normative behaviour description for `machine-transition`. Pre-rf2-d0wem
  only the core artefact's `re-frame.conformance-test` ran the corpus.
  This namespace wires the machine-related Mode B fixtures into the
  machines artefact's own CI gate, so the spec-defined invariants run
  on every machines-touching PR (and any machines refactor that breaks a
  fixture surfaces at the machines artefact's gate rather than only
  downstream at core's).

  Scope:
    - Mode B fixtures only — `:fixture/calls` containing
      `:call :machine-transition`. Mode A (dispatch-driven) fixtures
      live downstream in the core artefact's runner, which has the
      frame + dispatch loop.
    - Pure-function assertions only — `:expect-next-snapshot` and
      `:expect-effects` per `spec/conformance/README.md` §Mode B.
    - No side-effects, no frame, no app-db. The machines artefact's
      transition engine is JVM-runnable from arguments alone.

  Capability tagging:
    The conformance README §Capability tagging says conformance is
    graded against the port's claimed capability list. For this Mode B
    runner the practical capability set is everything the
    `machine-transition` primitive covers — :fsm/flat, :fsm/hierarchical,
    :fsm/parallel-regions, :fsm/eventless-always, :fsm/delayed-after,
    :fsm/tags, :fsm/final-states, plus :actor/spawn-destroy and
    :actor/invoke (both surface as spawn/destroy fx in the result
    vector, no live actor needed). Anything else (e.g. :core/error,
    :routing/*, :ssr/*) is core's surface and the fixture is skipped.

  This is a per-artefact gate; the core artefact's runner exercises the
  full corpus end-to-end through the dispatch loop. Both gates running
  is intentional belt-and-braces."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [re-frame.conformance :as conformance]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]))

;; ---- fixture discovery ----------------------------------------------------

(def fixtures-dir
  "The corpus lives at `spec/conformance/fixtures/` at the repo root.
  Tests run from `implementation/machines/`, so the relative path is
  `../../spec/conformance/fixtures`."
  (io/file "../../spec/conformance/fixtures"))

(defn- load-fixture [file]
  (try
    ;; A handful of fixtures use `::name` (auto-resolved keyword) which
    ;; pure `clojure.edn` cannot read without a *reader-resolver*. Match
    ;; the core runner's translation so the fixture loads as bare data:
    ;; rewrite `::name` → `:rf.machine.timer/name` (the only use in the
    ;; corpus today, for synthetic timer events).
    (let [raw   (slurp file)
          fixed (str/replace raw #"::([a-zA-Z][a-zA-Z0-9_-]*)"
                             ":rf.machine.timer/$1")]
      (edn/read-string fixed))
    (catch Throwable e
      {:fixture/load-error (.getMessage e)
       :fixture/file       (.getName file)})))

(def machine-call-ops
  "The Mode-B `:call` ops this artefact's runner executes:
  `:machine-transition` (pure transition) and `:reg-machine` (pure
  registration validation, pinning the registration-error taxonomy)."
  #{:machine-transition :reg-machine})

(defn- has-machine-call?
  "True if any `:fixture/calls` entry uses a `:call` this runner executes."
  [fixture]
  (some (fn [c] (contains? machine-call-ops (:call c)))
        (or (:fixture/calls fixture) [])))

(defn all-machine-transition-fixtures
  "Every fixture file whose `:fixture/calls` contains at least one
  `:machine-transition` or `:reg-machine` call. Returns a vector of
  `[filename fixture]` pairs in stable lex order."
  []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (sort-by #(.getName %))
       (map (fn [f] [(.getName f) (load-fixture f)]))
       (filter (fn [[_ fx]]
                 (and (not (:fixture/load-error fx))
                      (has-machine-call? fx))))
       vec))

;; ---- claimed capability set -----------------------------------------------
;;
;; Per `spec/conformance/README.md` §Capability tagging, the runner only
;; executes fixtures whose `:fixture/capabilities` are a subset of the
;; port's claimed list. For this Mode B runner the claim is the FSM /
;; actor capability surface `machine-transition` covers — pure-function
;; capabilities only. Anything that requires the dispatch loop, a frame,
;; or app-db state (the schemas / SSR / routing / flow surfaces) is
;; out-of-scope for this runner and the fixture is reported as skipped.

(def claimed-capabilities
  "Capabilities the pure machine-transition primitive covers.
  Per rf2-d0wem this is the FSM/actor capability surface; any fixture
  declaring capabilities outside this set is reported as skipped (it
  belongs to core's downstream runner, not this Mode B gate)."
  #{:fsm/flat
    :fsm/hierarchical
    :fsm/parallel-regions
    :fsm/eventless-always
    :fsm/delayed-after
    :fsm/tags
    :fsm/final-states
    ;; rf2-vf5cf: the registration-error taxonomy (Spec 009 thrown-error
    ;; shape) — pinned by the `:reg-machine` Mode-B op against the pure
    ;; `validate-machine!` validator.
    :fsm/registration-validation
    :actor/spawn-destroy
    :actor/invoke
    :actor/spawn-and-join
    :actor/system-id
    :actor/own-state
    ;; :core/* tags appear on a few machine fixtures alongside the FSM
    ;; capability they exercise (e.g. tags-round-trip-pr-str declares
    ;; both :fsm/tags and :core/event-handler). For the Mode B subset
    ;; the :core/* tags are no-ops — the call is pure — so we claim them
    ;; here to avoid spurious skips.
    :core/event-handler
    :core/sub
    :core/fx
    :core/error
    :core/trace
    :core/frame})

(def claimed-spec-versions
  "Fixture spec versions this runner conforms against. Matches the core
  runner's set (rf2-d0wem time)."
  #{"1.0"})

(defn- runnable-capability-set?
  "True iff every capability the fixture declares is in `claimed-capabilities`."
  [fixture]
  (let [caps (or (:fixture/capabilities fixture) #{})]
    (every? claimed-capabilities caps)))

(defn- spec-version-claimed?
  "True if the fixture's spec version (if any) is in `claimed-spec-versions`."
  [fixture]
  (let [v (:fixture/spec-version fixture)]
    (or (nil? v) (contains? claimed-spec-versions v))))

;; ---- machine-action body realisation --------------------------------------
;;
;; The conformance corpus describes machine action bodies in the
;; handler-body DSL (see `spec/conformance/README.md` §Handler-body DSL).
;; The DSL interpreter for VALUE forms lives in `re-frame.conformance`
;; (already a dep of this artefact via core/src). The action-body
;; reducer here is the same shape as `re-frame.conformance-test`'s
;; `realise-machine-handlers` in core — duplicated here rather than
;; reaching across artefacts because the core helper lives in
;; core/test/, not core/src/, and the test-tree isn't on this
;; artefact's classpath.

(defn- realise-machine-action
  "Build a `(fn [{:keys [data event]}])` from a DSL body. The fn returns
  `{:data <maybe-new-data> :fx <vec-of-fx>}` matching the action's
  canonical return shape per Spec 005 §Actions (rf2-grw4i / rf2-v0rrr —
  single context-map arg)."
  [steps]
  (fn [{:keys [data event]}]
    (let [eval-value (requiring-resolve 're-frame.conformance/eval-value*)
          final
          (reduce
            (fn [{:keys [data] :as ctx} step]
              (case (first step)
                :set    (let [[_ path v] step]
                          (assoc ctx :data
                                 (assoc-in data path (eval-value v ctx))))
                :fx     (let [[_ a b] step]
                          (update ctx :fx (fnil conj [])
                                  [a (eval-value b ctx)]))
                :throw  (throw (ex-info (str (second step))
                                        {:from-fixture? true}))
                ctx))
            {:data data :event event :fx []}
            steps)]
      (cond-> {}
        (not= data (:data final)) (assoc :data (:data final))
        (seq (:fx final))         (assoc :fx (:fx final))))))

(defn- realise-machine-guard
  "Build a `(fn [{:keys [data event]}])` from a DSL body — returns a
  boolean. Per Spec 005 §Guards (rf2-grw4i / rf2-v0rrr — single context-
  map arg)."
  [steps]
  (fn [{:keys [data event]}]
    (let [eval-value (requiring-resolve 're-frame.conformance/eval-value*)
          step       (first steps)]
      (when (and (vector? step) (= :fn (first step)))
        (boolean (eval-value step {:data data :event event}))))))

(defn- realise-machine-handlers
  "Walk `:fixture/handlers :machine-action` + `:machine-guard` and produce
  `{:actions {id fn} :guards {id fn} :on-spawn-actions {id fn}}`. The
  `:on-spawn-actions` map mirrors `:machine-action` bodies realised as
  on-spawn callbacks `(fn [data spawned-id] new-data)` — the corpus uses
  the same DSL body for both surfaces (per `re-frame.conformance/realise-on-spawn-handler`)."
  [fixture]
  (let [handlers (or (:fixture/handlers fixture) {})]
    {:actions
     (into {}
           (for [[id steps] (:machine-action handlers)]
             [id (realise-machine-action steps)]))
     :guards
     (into {}
           (for [[id steps] (:machine-guard handlers)]
             [id (realise-machine-guard steps)]))
     :on-spawn-actions
     (into {}
           (for [[id steps] (:machine-action handlers)]
             [id (conformance/realise-on-spawn-handler steps)]))}))

;; ---- single :machine-transition call --------------------------------------

(defn- run-machine-transition-call
  "Execute one `:machine-transition` call. Returns
  `{:passed? bool :detail msg}` matching the core runner's contract."
  [call realised]
  (let [{:keys [actions guards on-spawn-actions]} realised
        ;; Merge fixture-registered handlers into the definition's
        ;; named-binding maps (same shape as core's run-call). Fixture
        ;; bindings live alongside any short-names the def declares;
        ;; the engine follows short-name → registered-id → fn through
        ;; the combined map.
        definition (-> (:definition call)
                       (update :actions          #(merge actions %))
                       (update :guards           #(merge guards %))
                       (update :on-spawn-actions #(merge on-spawn-actions %)))
        r          (try (machines/machine-transition definition
                                                     (:snapshot call)
                                                     (:event call))
                        (catch Throwable e
                          {::result/snap nil
                           ::result/fx   [:error (.getMessage e)]}))
        snap-out   (::result/snap r)
        fx-out     (::result/fx r)
        want-snap  (:expect-next-snapshot call)
        want-fx    (or (:expect-effects call) [])
        ok-snap?   (= want-snap snap-out)
        ok-fx?     (= want-fx (vec fx-out))]
    {:passed? (and ok-snap? ok-fx?)
     :detail  (when-not (and ok-snap? ok-fx?)
                (str "machine-transition\n"
                     "    event:             " (:event call) "\n"
                     "    expected snapshot: " want-snap "\n"
                     "    actual   snapshot: " snap-out "\n"
                     "    expected effects:  " want-fx "\n"
                     "    actual   effects:  " fx-out))}))

;; ---- single :reg-machine call ---------------------------------------------
;;
;; The `:reg-machine` Mode-B op pins the registration-error taxonomy
;; (Spec 009 §The thrown-error shape — the :rf.error/id ex-data contract)
;; against the pure registration validator `validate-machine!`. A
;; well-formed `:definition` validates silently; a malformed one throws an
;; ex-info whose `:rf.error/id` ex-data slot names the category the
;; fixture's `:expect-error` pins. No registrar, no substrate, no app-db —
;; the validator is a pure leaf fn of the machine map.

(defn- run-reg-machine-call
  "Execute one `:reg-machine` call. Returns `{:passed? bool :detail msg}`.

  Validates `(:definition call)` via `re-frame.machines/validate-machine!`.
  - With `:expect-error <category-kw>`: passes iff the validator throws an
    ex-info whose `(:rf.error/id (ex-data e))` equals the category.
  - With `:expect-valid? true` (or no `:expect-error`): passes iff the
    validator does NOT throw (a well-formed control case)."
  [call]
  (let [definition (:definition call)
        want-error (:expect-error call)
        thrown     (try (machines/validate-machine! definition) nil
                        (catch clojure.lang.ExceptionInfo e e)
                        (catch Throwable e e))]
    (if want-error
      (let [got-id (when (instance? clojure.lang.ExceptionInfo thrown)
                     (:rf.error/id (ex-data thrown)))
            ok?    (= want-error got-id)]
        {:passed? ok?
         :detail  (when-not ok?
                    (str "reg-machine\n"
                         "    expected error :rf.error/id: " want-error "\n"
                         "    actual   error :rf.error/id: " got-id "\n"
                         "    thrown:                       " (some-> thrown ex-message)))})
      ;; control: must NOT throw
      {:passed? (nil? thrown)
       :detail  (when (some? thrown)
                  (str "reg-machine\n"
                       "    expected: no error (well-formed machine)\n"
                       "    thrown:   " (ex-message thrown)))})))

;; ---- fixture-level pass/fail ----------------------------------------------

(defn- run-fixture
  "Run every `:machine-transition` and `:reg-machine` call in the fixture;
  return `{:fixture-id ... :passed? bool :failures [detail ...]}`. Other
  `:call` ops in the same fixture are ignored — they belong to primitives
  that don't ship in this artefact."
  [fixture]
  (let [realised   (realise-machine-handlers fixture)
        calls      (or (:fixture/calls fixture) [])
        results    (->> calls
                        (keep (fn [c]
                                (case (:call c)
                                  :machine-transition (run-machine-transition-call c realised)
                                  :reg-machine        (run-reg-machine-call c)
                                  nil)))
                        vec)
        failures   (filterv (complement :passed?) results)]
    {:fixture-id (:fixture/id fixture)
     :calls-run  (count results)
     :passed?    (empty? failures)
     :failures   (mapv :detail failures)}))

;; ---- the test entrypoint --------------------------------------------------

(deftest run-machines-conformance-corpus
  (let [results (atom [])]
    (doseq [[fname fixture] (all-machine-transition-fixtures)]
      (cond
        (not (spec-version-claimed? fixture))
        (swap! results conj {:fixture-id   (:fixture/id fixture)
                             :fname        fname
                             :skipped?     true
                             :reason       "spec-version not in claimed set"
                             :spec-version (:fixture/spec-version fixture)})

        (not (runnable-capability-set? fixture))
        (swap! results conj {:fixture-id   (:fixture/id fixture)
                             :fname        fname
                             :skipped?     true
                             :reason       "capabilities outside Mode B claim"
                             :capabilities (:fixture/capabilities fixture)})

        :else
        (swap! results conj (assoc (run-fixture fixture) :fname fname))))
    (let [all     @results
          run     (remove :skipped? all)
          passed  (filter :passed? run)
          failed  (remove :passed? run)
          skipped (filter :skipped? all)]
      ;; Silent-on-success (rf2-try1x): summary prints only on failure.
      (when (seq failed)
        (println)
        (println "Machines conformance corpus (Mode B :machine-transition):")
        (println "  total fixtures (filtered to :machine-transition calls):" (count all))
        (println "  runnable:                                              " (count run))
        (println "  passed:                                                " (count passed))
        (println "  failed:                                                " (count failed))
        (println "  skipped (out-of-scope for Mode B):                     " (count skipped))
        (when (seq skipped)
          (println)
          (println "Skipped:")
          (doseq [s skipped]
            (println "  " (:fixture-id s) "—" (:reason s)
                     (or (:capabilities s) (:spec-version s)))))
        (println)
        (println "Failures:")
        (doseq [f failed]
          (println "  " (:fixture-id f))
          (doseq [d (:failures f)]
            (println "    " d))))
      (is (zero? (count failed))
          (str "All Mode B :machine-transition fixtures must pass; "
               (count failed) " failed.")))))
