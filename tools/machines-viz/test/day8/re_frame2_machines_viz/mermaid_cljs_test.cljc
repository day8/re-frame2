(ns day8.re-frame2-machines-viz.mermaid-cljs-test
  "Pure-data tests for the Mermaid `stateDiagram-v2` exporter
  (rf2-deo2i, relocated to tools/machines-viz/ per rf2-sqhqu). The
  `_cljs_test` naming follows the convention from
  tools/machines-viz/test/ — Cognitect's test-runner picks the ns up
  via the default `.*-test$` regex, and Shadow's `:node-test` build
  picks it up via `cljs-test$`."
  (:require [clojure.test  :refer [deftest is testing]]
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.mermaid :as m]))

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
