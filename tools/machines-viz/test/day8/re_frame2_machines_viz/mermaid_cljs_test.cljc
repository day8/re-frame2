(ns day8.re-frame2-machines-viz.mermaid-cljs-test
  "Pure-data tests for the Mermaid `stateDiagram-v2` exporter
  (rf2-deo2i, relocated to tools/machines-viz/ per rf2-sqhqu). The
  `_cljs_test` naming follows the convention from
  tools/machines-viz/test/ — Cognitect's test-runner picks the ns up
  via the default `.*-test$` regex, and Shadow's `:node-test` build
  picks it up via `cljs-test$`."
  (:require [clojure.test  :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [day8.re-frame2-machines-viz.mermaid :as m]))

(defn- deep-strings
  "Every string anywhere in `m` (deep walk)."
  [m]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when (string? x) (vswap! acc conj x)) x) m)
    @acc))

;; -----------------------------------------------------------------------------
;; Fixtures — small, hand-curated machine definitions per Spec 005
;; §Transition table grammar.

(def idle-loading-success-error
  "The canonical small machine from the bead description: idle →
  loading → success / error. Two final states."
  {:initial :idle
   :states  {:idle    {:on {:start :loading}}
             :loading {:on {:ok :success :err :failed}}
             :success {:final? true}
             :failed  {:final? true}}})

(def compound-machine
  "A compound machine with one nested region — exercises Mermaid's
  `state X { ... }` block."
  {:initial :unauth
   :states  {:unauth        {:on {:login :authenticated}}
             :authenticated {:initial :browsing
                             :states  {:browsing {:on {:checkout :paying}}
                                       :paying   {:on {:done :browsing}}}
                             :on      {:logout :unauth}}}})

(def duplicate-nested-leaves-machine
  "Two compound parents both contain an :idle leaf. Mermaid ids must
  use the full path so those leaves do not collapse."
  {:initial :left
   :states  {:left  {:initial :idle
                     :states  {:idle {:on {:swap [:right :idle]}}}}
             :right {:initial :idle
                     :states  {:idle {}}}}})

(def vector-path-target-machine
  "Compound transitions may target absolute vector paths."
  {:initial :unauth
   :states  {:unauth {:on {:login [:authenticated :browsing]}}
             :authenticated
             {:initial :browsing
              :states  {:browsing {:on {:logout [:unauth]}}}}}})

(def timed-and-eventless-machine
  "State-node :after and :always transitions are static topology and
  should render as lossy labelled edges."
  {:initial :loading
   :states  {:loading {:after {5000  :timeout
                               30000 {:target :hard-error
                                      :guard  :still-loading?}}
                       :on    {:loaded :checking}}
             :checking {:always [{:guard :ready? :target :ready}
                                 {:target :blocked}]}
             :timeout {}
             :hard-error {}
             :ready {}
             :blocked {}}})

(def parallel-region-machine
  "A :type :parallel root with two independent region state trees."
  {:type    :parallel
   :regions {:data {:initial :nothing
                    :states  {:nothing {:on {:fetch :loading}}
                              :loading {}}}
             :form {:initial :neutral
                    :states  {:neutral {:on {:submit :correct}}
                              :correct {:final? true}}}}})

(def namespaced-ids-machine
  "Machine using namespaced and hyphenated ids — exercises
  sanitise-id."
  {:initial :auth/idle
   :states  {:auth/idle      {:on {:rf/load :auth/loading}}
             :auth/loading   {:on {:done :auth/idle}}}})

;; -----------------------------------------------------------------------------
;; Tests

(deftest emit-returns-fenced-block-by-default
  (testing "default output is wrapped in a ```mermaid fence"
    (let [out (m/emit idle-loading-success-error)]
      (is (str/starts-with? out "```mermaid\n"))
      (is (str/ends-with?   out "\n```")))))

(deftest emit-unfenced-omits-the-fence
  (testing ":fenced? false strips the markdown fence"
    (let [out (m/emit idle-loading-success-error {:fenced? false})]
      (is (not (str/includes? out "```")))
      (is (str/includes? out "stateDiagram-v2")))))

(deftest emit-includes-stateDiagram-v2-header
  (testing "every emit starts the diagram with `stateDiagram-v2`"
    (is (str/includes? (m/emit idle-loading-success-error)
                       "stateDiagram-v2"))))

(deftest emit-renders-initial-state-edge
  (testing "[*] --> initial-state appears at the top of the diagram"
    (is (str/includes? (m/emit idle-loading-success-error)
                       "[*] --> idle"))))

(deftest emit-renders-transitions
  (testing "every statically-resolvable transition appears as an edge"
    (let [out (m/emit idle-loading-success-error)]
      (is (str/includes? out "idle --> loading : start"))
      (is (str/includes? out "loading --> success : ok"))
      (is (str/includes? out "loading --> failed : err")))))

(deftest emit-renders-final-state-edges
  (testing ":final? true states render `state --> [*]`"
    (let [out (m/emit idle-loading-success-error)]
      (is (str/includes? out "success --> [*]"))
      (is (str/includes? out "failed --> [*]")))))

;; ---------------------------------------------------------------------------
;; Error-final terminal KIND — EP-0011 reply-envelope completion status.
;;
;; Spec 005 §:final? lets a `:final?` leaf carry `:error? true`. A child
;; finishing via an `:error?` final completes as `:status :error` (vs a plain
;; final's `:status :ok`) and routes the spawning parent's `:on-error`
;; instead of `:on-done`. Mermaid's `[*]` terminal has no error-terminal
;; glyph, so — same posture as the action-only / history annotations — the
;; emitter surfaces the distinction visibly via a `note` on the error final
;; rather than letting it collapse into a plain success terminal.

(def success-and-error-finals-machine
  "Two terminals of distinct KIND: a plain success final + an `:error?`
  error final."
  {:initial :running
   :states  {:running {:on {:ok :ok :boom :boom}}
             :ok      {:final? true}
             :boom    {:final? true :error? true}}})

(deftest emit-marks-error-final-distinctly
  (testing "an :error? final is visibly marked (a `note` annotation tying
            it to the EP-0011 :status :error / parent :on-error routing);
            a success final renders as a plain `state --> [*]` terminal
            with no such note"
    (let [out (m/emit success-and-error-finals-machine)]
      (is (str/includes? out "ok --> [*]")
          "the success final renders the plain terminal edge")
      (is (str/includes? out "boom --> [*]")
          "the error final still renders the terminal edge")
      (is (str/includes? out "note right of boom")
          "the error final carries a note marking the error terminal")
      (is (str/includes? out "error terminal")
          "the note text names the error-terminal completion")
      (is (not (str/includes? out "note right of ok"))
          "the success final carries NO error-terminal note"))))

(deftest emit-includes-omission-caveat-by-default
  (testing "the `:after` + `:spawn-all` omission caveat lands at the top"
    (let [out (m/emit idle-loading-success-error)]
      (is (str/includes? out "%% Generated by day8.re-frame2-machines-viz.mermaid"))
      (is (str/includes? out ":after rings + :spawn-all rows omitted")))))

(deftest emit-can-suppress-header-comment
  (testing ":header-comment? false drops the %% caveat lines"
    (let [out (m/emit idle-loading-success-error {:header-comment? false})]
      (is (not (str/includes? out "%%"))))))

(deftest emit-renders-compound-state-block
  (testing "compound states render as `state X { ... }` with inner [*] --> initial"
    (let [out (m/emit compound-machine)]
      (is (str/includes? out "state authenticated {"))
      (is (str/includes? out "[*] --> authenticated__browsing"))
      (is (str/includes? out "}")))))

(deftest emit-renders-cross-region-transitions
  (testing "transitions across compound boundaries render as flat edges"
    (let [out (m/emit compound-machine)]
      ;; unauth --> authenticated (top-level edge)
      (is (str/includes? out "unauth --> authenticated : login"))
      ;; authenticated --> unauth (compound-state-out edge, on the
      ;; compound state's `:on` map)
      (is (str/includes? out "authenticated --> unauth : logout"))
      ;; nested edges still emit
      (is (str/includes? out "authenticated__browsing --> authenticated__paying : checkout"))
      (is (str/includes? out "authenticated__paying --> authenticated__browsing : done")))))

(deftest emit-path-qualifies-duplicate-nested-leaves
  (testing "duplicate child names under different parents get distinct Mermaid ids"
    (let [out (m/emit duplicate-nested-leaves-machine)]
      (is (str/includes? out "[*] --> left__idle"))
      (is (str/includes? out "[*] --> right__idle"))
      (is (str/includes? out "left__idle --> right__idle : swap")))))

(deftest emit-renders-vector-path-targets
  (testing "absolute vector-path targets render without crashing"
    (let [out (m/emit vector-path-target-machine)]
      (is (str/includes? out "unauth --> authenticated__browsing : login"))
      (is (str/includes? out "authenticated__browsing --> unauth : logout")))))

(deftest emit-sanitises-namespaced-and-hyphenated-ids
  (testing "namespaced + hyphenated keywords map to INJECTIVE hex-escaped ids"
    (let [out (m/emit namespaced-ids-machine)]
      ;; rf2-mnp93.6 — the id is INJECTIVE: every non-alphanumeric char is
      ;; `_<hex>`-escaped (`/` → `_2f`), so `:auth/idle` → `auth_2fidle`
      ;; and `:auth/loading` → `auth_2floading`. The pre-fix naive
      ;; `[^a-zA-Z0-9_]`-collapse merged `:auth/idle` with a hypothetical
      ;; `:auth-idle` (both → `auth_idle`); the hex escape keeps them
      ;; distinct (`auth_2fidle` vs `auth_2didle`).
      (is (str/includes? out "auth_2fidle"))
      (is (str/includes? out "auth_2floading"))
      ;; :rf/load → "rf/load" as the edge label (sanitise-label keeps
      ;; the slash; only sanitise-id escapes it)
      (is (str/includes? out "auth_2fidle --> auth_2floading : rf/load")))))

(deftest emit-validates-definition
  (testing "definitions missing :initial or :states throw :invalid-definition"
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {:initial :idle})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {:initial :idle :states {}})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {:type :parallel :regions {}})))
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs ExceptionInfo)
                 (m/emit {:type :parallel
                          :regions {:data {:initial :idle
                                           :states  {}}}})))))

(deftest emit-error-omits-raw-definition
  ;; EP-0015 (rf2-8nzxib) — an invalid definition can carry a :data slot
  ;; of live runtime values; the thrown error keeps a value-FREE summary
  ;; (category, key SET, counts), never the raw definition.
  (testing "invalid-definition ex-data carries a value-free summary, not the raw definition"
    (let [secret "api-key-leak-7f3c"
          ;; Invalid (empty :states) but carries a secret :data slot.
          def    {:initial :idle :states {} :data {:api-key secret}}
          d      (try (m/emit def) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :mermaid/invalid-definition (:rf.error/id d)) "category preserved")
      (is (not (contains? d :definition)) "no raw :definition slot")
      (is (some? (:definition-summary d)) "value-free summary present")
      (is (not (some #(str/includes? % secret) (deep-strings d)))
          "the secret must not survive anywhere in ex-data"))))

(deftest emit-renders-wildcard-transitions
  (testing ":* wildcard transitions render as real topology"
    (let [m   {:initial :a
               :states  {:a {:on {:* :b
                                  :go :b}}
                         :b {}}}
          out (m/emit m)]
      (is (str/includes? out "a --> b : go"))
      (is (str/includes? out "a --> b : *")))))

(deftest emit-handles-map-shaped-transition-spec
  (testing "map-shaped transition specs with :target render their target and guard"
    (let [m   {:initial :a
               :states  {:a {:on {:go {:target :b
                                       :action :record
                                       :guard  :ready?}}}
                         :b {}}}
          out (m/emit m)]
      (is (str/includes? out "a --> b : go [ready?]")))))

(deftest emit-renders-multiple-candidate-transition-vectors
  (testing "candidate vectors render every target-bearing branch"
    (let [m   {:initial :editing
               :states  {:editing {:on {:submit [{:target :rate-limited
                                                  :guard  :over-limit?}
                                                 {:target :validating
                                                  :guard  :email-valid?}
                                                 {:target :rejected}]}}
                         :rate-limited {}
                         :validating {}
                         :rejected {}}}
          out (m/emit m)]
      ;; rf2-mnp93.6 — `:rate-limited` → `rate_2dlimited` (hyphen hex-escaped).
      (is (str/includes? out "editing --> rate_2dlimited : submit [over-limit?]"))
      (is (str/includes? out "editing --> validating : submit [email-valid?]"))
      (is (str/includes? out "editing --> rejected : submit")))))

(deftest emit-renders-after-and-always-transitions
  (testing ":after and :always targets render as lossy labelled edges"
    (let [out (m/emit timed-and-eventless-machine)]
      (is (str/includes? out "loading --> timeout : after(5000)"))
      ;; rf2-mnp93.6 — `:hard-error` → `hard_2derror` (hyphen hex-escaped).
      (is (str/includes? out "loading --> hard_2derror : after(30000) [still-loading?]"))
      (is (str/includes? out "checking --> ready : always [ready?]"))
      (is (str/includes? out "checking --> blocked : always")))))

(deftest emit-renders-top-level-fallback-on
  (testing "top-level :on is explicit via a synthetic root fallback node"
    (let [m   {:initial :a
               :states  {:a {}
                         :b {}}
               :on      {:reset :b
                         :*     :a}}
          out (m/emit m)]
      ;; rf2-mnp93.6 — the reserved root-fallback segment is a namespaced
      ;; keyword; every `.`/`-`/`/` hex-escapes (`_2e`/`_2d`/`_2f`).
      (is (str/includes? out "state \"root fallback\" as rf_2emachines_2dviz_2emermaid_2froot_2dfallback"))
      (is (str/includes? out "rf_2emachines_2dviz_2emermaid_2froot_2dfallback --> b : reset (root fallback)"))
      (is (str/includes? out "rf_2emachines_2dviz_2emermaid_2froot_2dfallback --> a : * (root fallback)")))))

(deftest emit-targetless-machine-level-on-renders-note
  (testing "rf2-5uhdaz — a TARGETLESS (action-only) machine-level :on
            fallback runs its action + leaves the state unchanged (XState v5
            targetless semantics). It has no arrow to draw, so it surfaces as
            a note on the `root fallback` node rather than being silently
            dropped (the chart self-anchors it on the MACHINE-ROOT chip)."
    (let [m   {:initial :a
               :states  {:a {} :b {}}
               :on      {:ping {:action :log-ping}}}   ;; no :target
          out (m/emit m {:fenced? false :header-comment? false})]
      ;; The fallback alias is declared (its source node).
      (is (str/includes? out "state \"root fallback\" as rf_2emachines_2dviz_2emermaid_2froot_2dfallback"))
      ;; The action-only fallback surfaces as a note on the alias node.
      (is (str/includes? out "note right of rf_2emachines_2dviz_2emermaid_2froot_2dfallback")
          "the targetless machine-level :on surfaces as a note")
      (is (str/includes? out "ping / log-ping")
          "the inherited fallback action is visible in the note")
      (is (not (str/includes? out "--> a : ping"))
          "no phantom arrow for the action-only fallback"))))

(deftest emit-targetless-machine-level-on-wildcard-renders-note
  (testing "rf2-5uhdaz — a `:*` wildcard targetless machine-level :on
            surfaces as a note too (action-only inherited fallback)"
    (let [m   {:initial :a
               :states  {:a {} :b {}}
               :on      {:* {:action :audit}}}   ;; wildcard, no :target
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of rf_2emachines_2dviz_2emermaid_2froot_2dfallback"))
      (is (str/includes? out "* / audit")
          "the wildcard action-only fallback is visible in the note"))))

(deftest emit-renders-parallel-region-machines
  (testing ":type :parallel renders each region inside a synthetic parallel root"
    (let [out (m/emit parallel-region-machine)]
      ;; rf2-mnp93.6 — the reserved parallel-root segment hex-escapes too.
      (is (str/includes? out "[*] --> rf_2emachines_2dviz_2emermaid_2fparallel_2droot"))
      (is (str/includes? out "state rf_2emachines_2dviz_2emermaid_2fparallel_2droot {"))
      (is (str/includes? out "state data {"))
      (is (str/includes? out "[*] --> data__nothing"))
      (is (str/includes? out "data__nothing --> data__loading : fetch"))
      (is (str/includes? out "state form {"))
      (is (str/includes? out "[*] --> form__neutral"))
      (is (str/includes? out "form__neutral --> form__correct : submit"))
      (is (str/includes? out "form__correct --> [*]"))
      (is (str/includes? out "broadcast macrostep semantics are lossy")))))

(deftest emit-surfaces-internal-and-drops-fn-shaped-transitions
  (testing "internal (action-only) transitions surface as a note; fn-shaped transitions are dropped"
    (let [m   {:initial :a
               :states  {:a {:on {:go     {:action :record}        ;; internal (no :target)
                                  :reset  (fn [_ _] {:state :a})}} ;; fn-shaped
                         :b {}}}
          out (m/emit m)]
      ;; rf2-mnp93.4 — an INTERNAL action-only candidate has no arrow to
      ;; draw, but it MUST NOT be silently dropped (the chart self-anchors
      ;; it, SCXML emits a target-less <transition>). Mermaid surfaces it
      ;; as a note so the three emitters agree.
      (is (str/includes? out "note right of a"))
      (is (str/includes? out "go / record"))
      ;; A fn-shaped transition cannot be statically rendered (we can't run
      ;; it to learn its target), so it is still dropped.
      (is (not (str/includes? out "reset")))
      ;; No state-change arrow is drawn from :a (the action-only :go is a
      ;; note, the fn-shaped :reset is dropped).
      (is (not (str/includes? out "a --> "))))))

;; -----------------------------------------------------------------------------
;; Smoke: round-trip the bead's example through emit + verify the
;; shape a GitHub reader would see.

(deftest emit-smoke-round-trip
  (testing "the canonical bead fixture emits a plausible Mermaid block"
    (let [out (m/emit idle-loading-success-error)]
      ;; Has the fence
      (is (str/starts-with? out "```mermaid"))
      ;; Has the header
      (is (str/includes? out "stateDiagram-v2"))
      ;; Has the initial-edge
      (is (str/includes? out "[*] --> idle"))
      ;; Has every transition
      (is (str/includes? out "idle --> loading : start"))
      (is (str/includes? out "loading --> success : ok"))
      (is (str/includes? out "loading --> failed : err"))
      ;; Has both terminal edges
      (is (str/includes? out "success --> [*]"))
      (is (str/includes? out "failed --> [*]"))
      ;; Mentions the omission caveat
      (is (str/includes? out ":after rings + :spawn-all rows omitted"))
      ;; Closes the fence
      (is (str/ends-with? out "```")))))

;; -----------------------------------------------------------------------------
;; :on-done (XState onDone) completion transition (rf2-41goo)
;;
;; Spec 005 §The done-state signal: a COMPOUND `:on-done` advances the
;; outer flow to a SIBLING; a PARALLEL-ROOT `:on-done` runs action-only
;; (no target — rendered as a note, no phantom arrow). Pre-rf2-41goo
;; Mermaid projected NO onDone edge.

(def compound-on-done-machine
  "Spec 005 example: compound `:flow` whose `:on-done` advances to the
  SIBLING `:next` when its `:final?` `:paid` is reached."
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done :next
                    :states  {:collecting {:on {:submit :submitting}}
                              :submitting {:on {:ok :paid}}
                              :paid       {:final? true}}}
             :next {:on {:reset [:flow]}}}})

(def parallel-on-done-machine
  "Spec 005 example: parallel-root `:on-done` runs action-only."
  {:type    :parallel
   :on-done {:action :announce}
   :regions {:fetch    {:initial :loading :states {:loading {:on {:loaded :done}} :done {:final? true}}}
             :validate {:initial :checking :states {:checking {:on {:ok :done}} :done {:final? true}}}}})

(deftest emit-compound-on-done-renders-sibling-completion-edge
  (testing "rf2-41goo — a compound `:on-done` renders the completion edge
            `<compound> --> <sibling> : ✓ done` (the Stately 'do the
            sub-flow, then continue' arrow), resolved to the SIBLING"
    (let [out (m/emit compound-on-done-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "flow --> next : ✓ done")
          "the compound advances to its sibling on completion"))))

(deftest emit-parallel-on-done-renders-completion-note
  (testing "rf2-41goo — a parallel-root `:on-done` (action-only, no
            target) renders as a note on the parallel root (no phantom
            target arrow), surfacing the ✓ done completion + its action"
    (let [out (m/emit parallel-on-done-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of")
          "the parallel-root completion renders as a note")
      (is (str/includes? out "on-done: ✓ done / announce")
          "the note carries the completion + its action"))))

(deftest emit-no-on-done-renders-no-completion-affordance
  (testing "rf2-41goo — a machine with no :on-done renders no ✓ done chip
            (no false-positive completion arrows)"
    (let [out (m/emit compound-machine {:fenced? false :header-comment? false})]
      (is (not (str/includes? out "✓ done"))))))

;; ---- compound ACTION-ONLY :on-done note (rf2-ay42f) --------------------
;;
;; rf2-ay42f — a COMPOUND whose `:on-done` is ACTION-ONLY (no :target; the
;; engine just runs an action when the sub-flow completes; the machine
;; stays in the all-final config) is a documented Spec 005 shape
;; (`on-done-edges` docstring in chart/layout.cljc names it). The chart +
;; SCXML emitters both surface it as a terminal completion affordance, but
;; PRE-FIX mermaid SILENTLY DROPPED it: `collect-on-done-edges` kept only
;; target-bearing candidates, and the note path covered only the
;; parallel-root. So the G9 'faithful across all three emitters' claim was
;; false for this shape. The fix renders a `note` (the same way the
;; parallel-root action-only :on-done is rendered), so all three emitters
;; surface the completion.

(def compound-action-only-on-done-machine
  "rf2-ay42f — a compound `:flow` whose `:on-done` is ACTION-ONLY (no
  :target — the engine runs `:announce` on completion; the machine stays
  in the all-final config). The shape `on-done-edges` names in its
  docstring."
  {:initial :flow
   :states  {:flow {:initial :collecting
                    :on-done {:action :announce}
                    :states  {:collecting {:on {:submit :submitting}}
                              :submitting {:on {:ok :paid}}
                              :paid       {:final? true}}}}})

(deftest emit-compound-action-only-on-done-renders-completion-note
  (testing "rf2-ay42f — a COMPOUND action-only :on-done (no target) renders
            a completion note (no phantom arrow) so mermaid is faithful to
            the chart + SCXML emitters (closing the G9 cross-emitter gap)"
    (let [out (m/emit compound-action-only-on-done-machine
                      {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of flow")
          "the compound's completion renders as a note on the compound")
      (is (str/includes? out "on-done: ✓ done / announce")
          "the note carries the completion + its action")
      ;; an action-only :on-done has NO sibling — so NO phantom `✓ done`
      ;; EDGE (the target-bearing form's `--> ... : ✓ done` arrow).
      (is (not (str/includes? out "--> flow : ✓ done"))
          "no phantom completion arrow for an action-only compound :on-done"))))

(deftest emit-region-compound-action-only-on-done-renders-note
  (testing "rf2-ay42f — a compound INSIDE a parallel region whose :on-done
            is action-only also renders a completion note (the walk covers
            nested + region-scoped compounds, not just the top level)"
    (let [machine {:type    :parallel
                   :regions {:audio {:initial :session
                                     :states  {:session {:initial :idle
                                                         :on-done {:action :chime}
                                                         :states  {:idle {:on {:go :busy}}
                                                                   :busy {:final? true}}}}}
                             :video {:initial :hidden
                                     :states  {:hidden {:on {:show :shown}}
                                               :shown  {:final? true}}}}}
          out     (m/emit machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of audio__session")
          "the region-nested compound's completion renders as a note")
      (is (str/includes? out "on-done: ✓ done / chime")
          "the note carries the completion + its action"))))

(deftest emit-target-bearing-on-done-renders-edge-not-note
  (testing "rf2-ay42f regression guard — a TARGET-bearing compound :on-done
            still renders the sibling completion EDGE (not a note); only
            the action-only form gets a note"
    (let [out (m/emit compound-on-done-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "flow --> next : ✓ done")
          "a target-bearing :on-done keeps its sibling completion arrow")
      (is (not (str/includes? out "note right of flow"))
          "a target-bearing :on-done does NOT also render a note"))))

;; ---- parallel-ROOT :on / :after ancestor fallback (rf2-656ivk / rf2-m3otj2)
;;
;; A `:type :parallel` ROOT may declare its OWN `:on` (the ancestor fallback)
;; and its OWN `:after` (the timer-driven analog). A TARGET-bearing root
;; transition renders a `root fallback --> <region-substate>` edge; an
;; ACTION-ONLY one renders a note on the parallel root. Pre-fix mermaid
;; dropped the action-only root :on (the `when-let` target guard) and the
;; root :after entirely (destructured only `regions on on-done`).

(deftest emit-parallel-root-on-target-bearing-renders-fallback-edge
  (testing "rf2-656ivk — a target-bearing root :on renders a `root fallback
            --> <region-substate>` edge into the region it moves"
    (let [m   {:type    :parallel
               :on      {:one {:target [:a :two]}}
               :regions {:a {:initial :one :states {:one {} :two {}}}
                         :b {:initial :one :states {:one {}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "state \"root fallback\" as ")
          "the root-fallback alias state is declared")
      (is (str/includes? out "--> a__two : one (root fallback)")
          "the root :on hangs into region :a's :two node"))))

(deftest emit-parallel-root-on-action-only-renders-note
  (testing "rf2-656ivk — an ACTION-ONLY root :on (no target — moves no region)
            renders as a note on the parallel root (pre-fix it was DROPPED)"
    (let [m   {:type    :parallel
               :on      {:ping {:action :log-ping}}
               :regions {:a {:initial :one :states {:one {}}}
                         :b {:initial :one :states {:one {}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of rf_2emachines_2dviz_2emermaid_2fparallel_2droot")
          "the action-only root :on surfaces as a note on the parallel root")
      (is (str/includes? out "ping / log-ping")
          "the note carries the event + action"))))

(deftest emit-parallel-root-after-target-bearing-renders-fallback-edge
  (testing "rf2-m3otj2 — a target-bearing root :after renders a root-fallback
            edge labelled `after(<delay>)`"
    (let [m   {:type    :parallel
               :after   {500 {:target [:a :two]}}
               :regions {:a {:initial :one :states {:one {} :two {}}}
                         :b {:initial :one :states {:one {}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "--> a__two : after(500) (root fallback)")
          "the root :after hangs into region :a's :two node, labelled after(500)"))))

(deftest emit-parallel-root-after-multi-region-renders-both-edges
  (testing "rf2-m3otj2 — a multi-region root :after renders one fallback edge
            per region-qualified target"
    (let [m   {:type    :parallel
               :after   {1000 {:target [[:a :two] [:b :two]]}}
               :regions {:a {:initial :one :states {:one {} :two {}}}
                         :b {:initial :one :states {:one {} :two {}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "--> a__two : after(1000) (root fallback)"))
      (is (str/includes? out "--> b__two : after(1000) (root fallback)")))))

(deftest emit-parallel-root-after-action-only-renders-note
  (testing "rf2-m3otj2 — an ACTION-ONLY root :after renders as a note on the
            parallel root (no arrow to draw)"
    (let [m   {:type    :parallel
               :after   {2000 {:action :timeout-log}}
               :regions {:a {:initial :one :states {:one {}}}
                         :b {:initial :one :states {:one {}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of rf_2emachines_2dviz_2emermaid_2fparallel_2droot")
          "the action-only root :after surfaces as a note")
      (is (str/includes? out "after(2000) / timeout-log")
          "the note carries the delayed action"))))

(deftest emit-parallel-region-top-level-action-only-on-renders-note
  (testing "rf2-pdvtxt — an ACTION-ONLY (target-less) REGION-level top-level
            :on fallback (`:on {:abort {:action :log}}` on a region) runs its
            action and moves no state. Like the flat machine + parallel-root
            action-only fallbacks, it has no arrow to draw, so it MUST surface
            as a note on THAT region's `root fallback` alias — pre-fix it fell
            through every collector (targetless region :on produced no edge;
            the per-region note walk only covered region STATES; the root note
            helper only covered the parallel-root :on/:after). The chart
            self-anchors it on the region container + SCXML emits a target-less
            <transition>, so dropping it broke three-emitter agreement."
    (let [m   {:type    :parallel
               :regions {:fetch    {:initial :loading
                                    :on      {:abort {:action :log}} ;; targetless
                                    :states  {:loading {} :done {:final? true}}}
                         :validate {:initial :checking
                                    :states  {:checking {}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      ;; the region declares its own `root fallback` alias node …
      (is (str/includes? out "state \"root fallback\" as fetch__rf_2emachines_2dviz_2emermaid_2froot_2dfallback")
          "region :fetch declares its own root-fallback alias for its top-level :on")
      ;; … and the targetless fallback surfaces as a note on THAT alias.
      (is (str/includes? out "note right of fetch__rf_2emachines_2dviz_2emermaid_2froot_2dfallback")
          "the region's action-only top-level :on surfaces as a note on its fallback alias")
      (is (str/includes? out "abort / log")
          "the note carries the region fallback event + action")
      (is (not (str/includes? out "--> fetch__rf_2emachines_2dviz_2emermaid_2froot_2dfallback : abort"))
          "no phantom arrow for the action-only region fallback")
      (is (not (str/includes? out "fetch__ "))
          "no degenerate region-scoped empty-path target leaks into the diagram"))))

;; ---- REGION's OWN top-level :on-done (rf2-f8fgz5) -----------------------
;;
;; Spec 005 §Parallel `:on-done`: "A compound region reaching its own
;; :final? child raises a region-local done.state.<region-compound> that
;; the region's :on-done takes … exactly the compound case, scoped to one
;; region". Pre-fix `render-region-block` destructured only `{:keys
;; [initial states on]}` — never `:on-done` — so this shape vanished from
;; Mermaid with zero trace while SCXML preserved it (empirically verified:
;; `scxml/spec->scxml` on the identical shape emits `<transition
;; event="done.state.a" target="b" .../>`, `b` being the SIBLING region's
;; own qualified id), breaking the G9 cross-emitter parity invariant.

(deftest emit-region-on-done-target-bearing-renders-sibling-region-edge
  (testing "rf2-f8fgz5 — a region's own top-level :on-done with a KEYWORD
            target renders a `<region> --> <sibling-region> : ✓ done`
            edge, matching SCXML's `done.state.<region> -> <sibling>`
            transition"
    (let [m   {:type    :parallel
               :regions {:a {:initial :a1
                             :on-done :b
                             :states  {:a1 {:on {:go :a2}}
                                       :a2 {:final? true}}}
                         :b {:initial :b1
                             :states  {:b1 {:on {:go :b2}}
                                       :b2 {:final? true}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "a --> b : ✓ done")
          "region :a's on-done advances to sibling region :b on completion")
      (is (not (str/includes? out "note right of a\n"))
          "a target-bearing region on-done does NOT also render a note"))))

(deftest emit-region-on-done-action-only-renders-note-on-region-root
  (testing "rf2-f8fgz5 — a region's own top-level :on-done that is
            ACTION-ONLY (no target) has no sibling-region arrow to draw,
            so it surfaces as a note on the region's OWN container (the
            region root) — matching SCXML's target-less
            `<transition event=\"done.state.<region>\">` + action comment"
    (let [m   {:type    :parallel
               :regions {:a {:initial :a1
                             :on-done {:action :log}
                             :states  {:a1 {:on {:go :a2}}
                                       :a2 {:final? true}}}
                         :b {:initial :b1
                             :states  {:b1 {:on {:go :b2}}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of a")
          "the region's own action-only on-done surfaces as a note on the region root")
      (is (str/includes? out "on-done: ✓ done / log")
          "the note carries the completion + its action")
      (is (not (str/includes? out "a --> b"))
          "no phantom completion arrow for an action-only region on-done"))))

;; ---- internal (action-only) :on / :after / :always notes (rf2-mnp93.4) --
;;
;; An INTERNAL transition candidate (a map omitting `:target` — Spec 005
;; §Transition slots: "omit for internal") runs only its `:action`; the
;; config is unchanged, so there is NO arrow to draw. Pre-fix, the `:on` /
;; `:after` / `:always` collectors all SILENTLY DROPPED every target-less
;; candidate, while the chart self-anchored it (`:internal? true`) and SCXML
;; emitted a target-less `<transition>` — breaking the G9 'faithful across
;; all three emitters' parity claim (001-Topology-Parity.md §3.1). The fix
;; surfaces them as a note (exactly the rf2-ay42f action-only :on-done
;; pattern), so all three emitters agree.

(deftest emit-internal-on-transition-renders-note
  (testing "rf2-mnp93.4 — an INTERNAL (action-only) :on candidate renders as
            a note (NOT silently dropped) carrying `<event> / <action>`"
    (let [m   {:initial :a :states {:a {:on {:tick {:action :log}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of a"))
      (is (str/includes? out "tick / log"))
      (is (not (str/includes? out "a --> "))
          "no phantom arrow for an internal action :on"))))

(deftest emit-internal-after-and-always-render-notes
  (testing "rf2-mnp93.4 — internal (action-only) :after / :always candidates
            render as notes too (the SAME asymmetry the :on branch had)"
    (let [m   {:initial :a
               :states  {:a {:after  {1000 {:action :timeout-log}}
                             :always [{:action :poll}]}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "note right of a"))
      (is (str/includes? out "after(1000) / timeout-log")
          "internal :after surfaces as a note line")
      (is (str/includes? out "always / poll")
          "internal :always surfaces as a note line")
      (is (not (str/includes? out "a --> "))
          "no phantom arrow for internal :after / :always"))))

(deftest emit-internal-transition-with-guard-renders-note
  (testing "rf2-mnp93.4 — an internal candidate's guard surfaces in its note
            line: `<event> [guard] / <action>`"
    (let [m   {:initial :a :states {:a {:on {:tick {:action :log :guard :ready?}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "tick [ready?] / log")))))

(deftest emit-mixes-internal-note-with-targeted-edge
  (testing "rf2-mnp93.4 — a state with BOTH a targeted :on AND an internal
            :on renders the arrow for the targeted one and a note for the
            internal one (the mixed-candidate case is not all-or-nothing)"
    (let [m   {:initial :a
               :states  {:a {:on {:go   :b                 ;; targeted → arrow
                                  :tick {:action :log}}}    ;; internal → note
                         :b {}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "a --> b : go") "the targeted :on keeps its arrow")
      (is (str/includes? out "note right of a") "the internal :on gets a note")
      (is (str/includes? out "tick / log")))))

;; ---- INJECTIVE sanitise-id (rf2-mnp93.6) --------------------------------
;;
;; `sanitise-id` mapped every non-[a-zA-Z0-9_] char to `_`, so `:auth/login`,
;; `:auth-login`, and `:auth_login` all collapsed to `auth_login`: two
;; distinct states became ONE Mermaid node, and every edge to/from either
;; pointed at the merged node — silently mis-wiring the diagram. Hyphens are
;; pervasive in re-frame keywords (`:logged-in`, `:rate-limited`), so the
;; collision is readily reachable. The fix ports the chart's injective
;; hex-escape scheme (`:a/b` → `a_2fb`, `:a-b` → `a_2db`): distinct keywords
;; map to distinct mermaid nodes.

(deftest emit-sanitise-id-is-injective
  (testing "rf2-mnp93.6 — `:a/b` and `:a-b` mint DISTINCT mermaid nodes (the
            non-injective collapse merged them into one)"
    (let [m   {:initial :a
               :states  {:a {:on {:go :auth/login :back :auth-login}}
                         :auth/login {} :auth-login {}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      ;; The two edges target DISTINCT nodes (pre-fix both read `a --> auth_login`).
      (is (str/includes? out "a --> auth_2flogin : go")
          ":auth/login → auth_2flogin (slash hex-escaped to _2f)")
      (is (str/includes? out "a --> auth_2dlogin : back")
          ":auth-login → auth_2dlogin (hyphen hex-escaped to _2d)")
      (is (not= "auth_2flogin" "auth_2dlogin")
          "the two ids are distinct (sanity)"))))

(deftest emit-sanitise-id-distinguishes-all-three-collision-forms
  (testing "rf2-mnp93.6 — `:a/b`, `:a-b`, AND `:a_b` (the three forms the
            naive collapse merged) all mint DISTINCT mermaid nodes"
    (let [m   {:initial :start
               :states  {:start {:on {:one :a/b :two :a-b :three :a_b}}
                         :a/b {} :a-b {} :a_b {}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      (is (str/includes? out "start --> a_2fb : one")  ":a/b → a_2fb")
      (is (str/includes? out "start --> a_2db : two")  ":a-b → a_2db")
      (is (str/includes? out "start --> a_5fb : three") ":a_b → a_5fb (underscore → _5f)")
      ;; All three node-ids are pairwise distinct — no merge.
      (is (= 3 (count (distinct ["a_2fb" "a_2db" "a_5fb"])))))))

(deftest emit-sanitise-id-no-two-consecutive-underscores-within-segment
  (testing "rf2-mnp93.6 — within a single segment the escaper never emits two
            consecutive underscores, so the `__` path separator can never be
            confused with segment content (it only appears BETWEEN path
            segments)"
    (let [m   {:initial :left
               :states  {:left  {:initial :child-node
                                 :states  {:child-node {:on {:swap [:right :child-node]}}}}
                         :right {:initial :child-node
                                 :states  {:child-node {}}}}}
          out (m/emit m {:fenced? false :header-comment? false})]
      ;; A hyphen inside a segment is `_2d`, NOT a bare `_`, so a compound id
      ;; reads `left__child_2dnode` — the ONLY `__` is the path boundary.
      (is (str/includes? out "left__child_2dnode")
          "compound id: hyphen → _2d, single __ is the path separator")
      (is (str/includes? out "right__child_2dnode")
          "the two same-named child-node leaves stay DISTINCT (path-qualified)"))))

;; ---------------------------------------------------------------------------
;; rf2-m285a — `:type :history` pseudo-states render as a LABELLED history
;; marker (`H` shallow / `H*` deep) inside the owning compound, NOT as an
;; ordinary bare-id leaf state in incoming edges.

(def shallow-history-machine
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? false}}
                      :on      {:power-off :off}}}})

(def deep-history-machine
  (assoc-in shallow-history-machine [:states :player :states :hist]
            {:type :history :deep? true}))

(def default-target-history-machine
  (assoc-in shallow-history-machine [:states :player :states :hist]
            {:type :history :deep? false :default-target :playing}))

(deftest emit-history-marker-shallow
  (testing "rf2-m285a — a SHALLOW history pseudo-state declares a labelled
            `H` marker inside its owning compound block"
    (let [out (m/emit shallow-history-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "state \"H\" as player__hist")
          "the history node is declared as a labelled `H` marker, not a bare state")
      ;; the incoming edge still lands on the marker id
      (is (str/includes? out "player__hist")
          "incoming :target :hist edge targets the marker"))))

(deftest emit-history-marker-deep
  (testing "rf2-m285a — a DEEP history pseudo-state declares `H*`"
    (let [out (m/emit deep-history-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "state \"H*\" as player__hist")
          "deep history renders the H* glyph"))))

(deftest emit-history-default-target-note
  (testing "rf2-m285a — a history :default-target surfaces as a documenting
            %% comment so the default config is visible"
    (let [out (m/emit default-target-history-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "state \"H\" as player__hist"))
      (is (str/includes? out "%% history default-target: playing")
          "the default-target rides a %% comment"))))

(deftest emit-history-not-an-ordinary-final-or-state-decl
  (testing "rf2-m285a — the history node never appears as a `--> [*]` final
            edge nor a `state X { }` compound block"
    (let [out (m/emit shallow-history-machine {:fenced? false :header-comment? false})]
      (is (not (str/includes? out "player__hist --> [*]"))
          "a history pseudo-state is not a final state")
      (is (not (str/includes? out "state player__hist {"))
          "a history pseudo-state is not a compound block"))))

;; ---- the :reenter? external-restart axis (rf2-9dj21r) ------------------
;;
;; A TARGETED transition is INTERNAL by default; `:reenter? true` is the
;; EXTERNAL restart opt-in (Spec 005 §Self-transitions / XState v5). The
;; Mermaid emitter must render the two DISTINCTLY (a `↻` marker on the
;; reentering edge label) — pre-fix the same `:target` with vs without
;; `:reenter?` produced byte-identical Mermaid.

(def reenter-self-machine
  {:initial :a
   :states  {:a {:on {:ping {:target :same-state :reenter? true}}}}})

(def internal-self-machine
  {:initial :a
   :states  {:a {:on {:ping {:target :same-state}}}}})

(deftest emit-reenter-axis-visually-distinct
  (testing "rf2-9dj21r — a `:reenter? true` edge carries the `↻` marker on
            its Mermaid label"
    (let [out (m/emit reenter-self-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "ping ↻")
          "the external-restart edge label carries the ↻ reenter marker")))

  (testing "rf2-9dj21r — the internal-default edge has NO `↻` marker"
    (let [out (m/emit internal-self-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out ": ping") "the edge is labelled `ping`")
      (is (not (str/includes? out "↻"))
          "the internal default carries no reenter marker")))

  (testing "rf2-9dj21r — the with/without-`:reenter?` Mermaid outputs DIFFER"
    (is (not= (m/emit reenter-self-machine {:fenced? false :header-comment? false})
              (m/emit internal-self-machine {:fenced? false :header-comment? false}))
        "external vs internal must produce DISTINCT Mermaid")))

;; ---- consumer-attachment :rf.cofx/requires — intentional omission ------
;;
;; rf2-skhlw2.1 — Mermaid INTENTIONALLY omits EP-0017 consumer-attachment
;; `:rf.cofx/requires` (the chart is the "which transitions consume which
;; facts" surface). Lock the omission so the lossy posture stays documented +
;; deliberate, and guard against any `:rf.world/inputs` / `inject-cofx`
;; vocabulary slipping in (the bead's negative regression).

(def cofx-bearing-machine
  {:initial :idle
   :guards  {:within-window? {:rf.cofx/requires [:rf/time-ms]
                              :fn (fn [_] true)}}
   :actions {:schedule-retry {:rf.cofx/requires [:payment/retry-jitter-ms]
                             :fn (fn [_] nil)}}
   :states  {:idle {:on {:go {:target :busy
                              :guard  :within-window?
                              :action :schedule-retry}}}
             :busy {}}})

(deftest mermaid-omits-cofx-requires-vocabulary
  (testing "rf2-skhlw2.1 — Mermaid surfaces guard/action NAMES but omits the
            consumer-attachment requires diet (documented lossy omission)"
    (let [out (m/emit cofx-bearing-machine {:fenced? false :header-comment? false})]
      ;; the guard NAME still renders on the transition label (existing
      ;; contract — Mermaid edge labels carry `event [guard]`).
      (is (str/includes? out "within-window?"))
      ;; the requires diet + its cofx ids are NOT emitted
      (is (not (str/includes? out "rf.cofx")))
      (is (not (str/includes? out "requires")))
      (is (not (str/includes? out "rf/time-ms")))
      (is (not (str/includes? out "retry-jitter-ms")))
      ;; the bead's negative regression: NO retired/foreign cofx vocabulary
      (is (not (str/includes? out "rf.world/inputs")))
      (is (not (str/includes? out "inject-cofx"))))))
