(ns day8.re-frame2-machines-viz.cross-emitter-agreement-cljs-test
  "Cross-emitter agreement (G9) regression tests — the three viz emitters
  (chart / mermaid / SCXML) must AGREE on how they project a machine
  (001-Topology-Parity.md §3.1 G9: 'faithful across all three emitters'). A
  diagram that drops or inverts a transition in ONE emitter mis-teaches the
  user relative to the others.

  rf2-mnp93.4 — the canonical asymmetry these tests pin: an INTERNAL
  (action-only, no-`:target`) `:on` / `:after` / `:always` transition (Spec
  005 §Transition slots: 'omit for internal'). Pre-fix:

  - CHART: charted internal `:on` (self-anchored, `:internal? true`) but
    SILENTLY DROPPED internal `:after` / `:always` (the `resolve-target-path`
    inside `keep` returned nil for a target-less candidate) — inconsistent
    even WITHIN the chart.
  - MERMAID: dropped EVERY target-less candidate (internal `:on`, `:after`,
    AND `:always`).
  - SCXML: kept all of them as target-less `<transition>`s.

  Three emitters, three different projections of ONE machine. These tests
  assert the post-fix agreement: every emitter SURFACES every internal
  candidate (chart self-anchors `:internal?`, mermaid renders a note, SCXML
  emits a target-less `<transition>`)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [clojure.string :as str]
            [day8.re-frame2-machines-viz.ai-generate :as ai]
            [day8.re-frame2-machines-viz.chart.layout :as layout]
            [day8.re-frame2-machines-viz.mermaid :as mermaid]
            [day8.re-frame2-machines-viz.scxml :as scxml]))

;; ---------------------------------------------------------------------------
;; Fixtures — the internal-transition shapes the three emitters used to
;; disagree on.

(def internal-on-machine
  {:initial :a :states {:a {:on {:tick {:action :log}}}}})

(def internal-after-machine
  {:initial :a :states {:a {:after {1000 {:action :timeout-log}}}}})

(def internal-always-machine
  {:initial :a :states {:a {:always [{:action :poll}]}}})

(def all-three-internal-machine
  "One state carrying an internal `:on`, `:after`, AND `:always` — the exact
  bead-mnp93.4 repro fixture."
  {:initial :a
   :states  {:a {:on     {:tick {:action :log}}
                 :after  {1000 {:action :timeout-log}}
                 :always [{:action :poll}]}}})

;; --- per-emitter 'surfaces this internal candidate?' probes --------------

(defn- chart-internal-edges
  "The internal (self-anchored, `:internal? true`) edges the chart projects
  for a definition."
  [definition]
  (->> (layout/project-definition definition)
       :edges
       (filter :internal?)))

(defn- mermaid-body [definition]
  (mermaid/emit definition {:fenced? false :header-comment? false}))

(defn- scxml-internal-transition-count
  "The number of target-less `<transition>` elements SCXML emits (the
  internal-transition projection)."
  [definition]
  (let [out (scxml/spec->scxml definition)]
    ;; A target-less transition has NO `target=` attribute. Count
    ;; <transition ...> openings that carry no target.
    (->> (re-seq #"<transition\b[^>]*>" out)
         (remove #(str/includes? % "target="))
         count)))

;; ---------------------------------------------------------------------------
;; G9 — every emitter SURFACES the internal transition.

(deftest internal-on-surfaced-by-all-three-emitters
  (testing "rf2-mnp93.4 — an internal action `:on` is surfaced by chart
            (self-anchored :internal?), mermaid (note), AND SCXML (target-
            less <transition>)"
    ;; CHART
    (let [edges (chart-internal-edges internal-on-machine)
          tick  (first (filter #(= :tick (:event %)) edges))]
      (is (some? tick) "chart surfaces the internal :tick")
      (is (true? (:internal? tick)) "chart flags it :internal?")
      (is (= (:source tick) (:target tick)) "chart self-anchors it"))
    ;; MERMAID
    (let [out (mermaid-body internal-on-machine)]
      (is (str/includes? out "note right of a") "mermaid surfaces it as a note")
      (is (str/includes? out "tick / log") "mermaid note carries the action"))
    ;; SCXML
    (is (= 1 (scxml-internal-transition-count internal-on-machine))
        "scxml emits the target-less <transition>")))

(deftest internal-after-surfaced-by-all-three-emitters
  (testing "rf2-mnp93.4 — an internal action `:after` is surfaced by ALL
            three emitters (pre-fix: dropped by chart AND mermaid)"
    (let [edges (chart-internal-edges internal-after-machine)
          aft   (first (filter :after edges))]
      (is (some? aft) "chart surfaces the internal :after (pre-fix dropped)")
      (is (true? (:internal? aft)) "chart flags it :internal?")
      (is (= 1000 (:after aft))))
    (let [out (mermaid-body internal-after-machine)]
      (is (str/includes? out "note right of a"))
      (is (str/includes? out "after(1000) / timeout-log")))
    (is (= 1 (scxml-internal-transition-count internal-after-machine)))))

(deftest internal-always-surfaced-by-all-three-emitters
  (testing "rf2-mnp93.4 — an internal action `:always` is surfaced by ALL
            three emitters (pre-fix: dropped by chart AND mermaid)"
    (let [edges (chart-internal-edges internal-always-machine)
          alw   (first (filter :always? edges))]
      (is (some? alw) "chart surfaces the internal :always (pre-fix dropped)")
      (is (true? (:internal? alw)) "chart flags it :internal?"))
    (let [out (mermaid-body internal-always-machine)]
      (is (str/includes? out "note right of a"))
      (is (str/includes? out "always / poll")))
    (is (= 1 (scxml-internal-transition-count internal-always-machine)))))

(deftest all-three-internal-kinds-agree-across-emitters
  (testing "rf2-mnp93.4 (bead repro) — a state with an internal :on + :after
            + :always: the chart charts THREE internal edges, mermaid emits
            THREE note lines, SCXML emits THREE target-less <transition>s.
            Pre-fix the COUNTS diverged (chart 1, mermaid 0, SCXML 3)."
    (let [chart-count   (count (chart-internal-edges all-three-internal-machine))
          out           (mermaid-body all-three-internal-machine)
          ;; Mermaid note-body lines for the three internal candidates.
          mermaid-lines (->> (str/split-lines out)
                             (filter #(or (str/includes? % "tick / log")
                                          (str/includes? % "after(1000) / timeout-log")
                                          (str/includes? % "always / poll")))
                             count)
          scxml-count   (scxml-internal-transition-count all-three-internal-machine)]
      (is (= 3 chart-count)  "chart: 3 internal edges")
      (is (= 3 mermaid-lines) "mermaid: 3 internal note lines")
      (is (= 3 scxml-count)  "scxml: 3 target-less <transition>s")
      (is (= chart-count mermaid-lines scxml-count)
          "ALL THREE emitters agree on the internal-transition count (G9)"))))

(deftest no-emitter-draws-a-phantom-arrow-for-internal-transition
  (testing "rf2-mnp93.4 — an internal transition must NOT become a visible
            state-change arrow in any emitter (it is action-only; the config
            is unchanged). The chart self-anchors (source==target), mermaid
            uses a note, SCXML a target-less transition — none invents a
            cross-state edge."
    ;; CHART: every internal edge is a self-loop (source == target), never a
    ;; cross-state arrow.
    (doseq [e (chart-internal-edges all-three-internal-machine)]
      (is (= (:source e) (:target e))
          "chart internal edge is a self-loop, not a cross-state arrow"))
    ;; MERMAID: no `a --> <other>` arrow (the only state is :a; an internal
    ;; transition draws no arrow).
    (let [out (mermaid-body all-three-internal-machine)]
      (is (not (str/includes? out "a --> "))
          "mermaid draws no arrow for an internal transition"))
    ;; SCXML: the internal transitions carry no target= attribute.
    (let [out (scxml/spec->scxml all-three-internal-machine)]
      (is (not (str/includes? out "target="))
          "scxml internal transitions carry no target"))))

;; ---------------------------------------------------------------------------
;; G9 — SCXML round-trip preserves the internal action transition (so the
;; codec agrees with itself + the other emitters on the SEMANTICS, not just
;; the topology). Ties rf2-mnp93.4 (cross-emitter) to rf2-mnp93.5 (round-trip
;; validity): a dropped/inverted internal transition would break BOTH.

(deftest internal-action-round-trips-without-semantic-inversion
  (testing "rf2-mnp93.4/.5 — the internal action transitions the emitters
            surface also SURVIVE the SCXML round-trip as VALID internal
            action transitions (not the `{}` forbidden block)"
    (is (= internal-on-machine
           (-> internal-on-machine scxml/spec->scxml scxml/scxml->spec)))
    (is (= internal-after-machine
           (-> internal-after-machine scxml/spec->scxml scxml/scxml->spec)))
    (is (= internal-always-machine
           (-> internal-always-machine scxml/spec->scxml scxml/scxml->spec)))
    (is (= all-three-internal-machine
           (-> all-three-internal-machine scxml/spec->scxml scxml/scxml->spec))
        "the combined repro fixture round-trips exactly")))

;; ---------------------------------------------------------------------------
;; G9 — chart + mermaid agree on INJECTIVE node-ids (rf2-mnp93.6). The chart's
;; `node-id` was already injective (rf2-ee38b.21); mermaid's `sanitise-id` was
;; not. They must now mint the SAME id for the same path so a tool reading
;; both addresses every node identically.

(deftest chart-and-mermaid-mint-the-same-injective-ids
  (testing "rf2-mnp93.6 — the mermaid output addresses each state by the SAME
            injective hex-escaped id the chart's public `node-id` mints (so a
            tool reading both emitters addresses every node identically). The
            three collision-class forms `:a/b` / `:a-b` / `:a_b` stay distinct."
    (let [m   {:initial :start
               :states  {:start {:on {:one :a/b :two :a-b :three :a_b}}
                         :a/b {} :a-b {} :a_b {}}}
          out (mermaid/emit m {:fenced? false :header-comment? false})]
      (doseq [k [:a/b :a-b :a_b]]
        (testing (pr-str k)
          ;; mermaid must reference the EXACT chart node-id for this state.
          (is (str/includes? out (layout/node-id [k]))
              "mermaid output contains the chart's injective node-id")))
      ;; The three chart node-ids are pairwise distinct (no collision) — and
      ;; the mermaid output therefore contains three distinct target nodes.
      (is (= 3 (count (distinct (map #(layout/node-id [%]) [:a/b :a-b :a_b])))))
      (is (str/includes? out (str "start --> " (layout/node-id [:a/b])  " : one")))
      (is (str/includes? out (str "start --> " (layout/node-id [:a-b])  " : two")))
      (is (str/includes? out (str "start --> " (layout/node-id [:a_b])  " : three"))))))

;; ---------------------------------------------------------------------------
;; G9 — history pseudo-states agree across the three emitters (rf2-m285a).
;; A `:type :history` node must surface as a HISTORY pseudo-state in every
;; emitter (NOT an ordinary occupiable state): chart → `history-marker` node,
;; mermaid → labelled `H`/`H*` marker, SCXML → `<history>` element. Pre-fix all
;; three rendered it as a normal state/edge — three emitters mis-teaching the
;; same topology.

(def deep-history-machine
  {:initial :off
   :states  {:off    {:on {:resume [:player :hist]}}
             :player {:initial :stopped
                      :states  {:stopped {:on {:play :playing}}
                                :playing {:on {:stop :stopped}}
                                :hist    {:type :history :deep? true}}
                      :on      {:power-off :off}}}})

(deftest history-pseudo-state-agrees-across-emitters
  (testing "rf2-m285a — a `:type :history` node surfaces as a history
            pseudo-state in chart, mermaid, AND SCXML (never an ordinary
            occupiable state)"
    ;; CHART — a `history-marker`-flagged, non-occupiable node.
    (let [hist (->> (layout/project-definition deep-history-machine)
                    :nodes
                    (filter #(= [:player :hist] (:path %)))
                    first)]
      (is (true? (:history? hist)) "chart parses it as a history pseudo-state")
      (is (true? (:deep? hist))    "deep variant preserved")
      (is (false? (:final? hist))  "never final")
      (is (false? (:compound? hist)) "never compound"))
    ;; MERMAID — labelled H* marker, not a bare leaf id / final edge.
    (let [out (mermaid/emit deep-history-machine {:fenced? false :header-comment? false})]
      (is (str/includes? out "state \"H*\" as player__hist")
          "mermaid declares the deep-history H* marker")
      (is (not (str/includes? out "player__hist --> [*]"))
          "mermaid does not render it as a final state"))
    ;; SCXML — a <history type="deep"> element, not <state>/<final>.
    (let [out (scxml/spec->scxml deep-history-machine)]
      (is (str/includes? out "<history ") "scxml emits a <history> element")
      (is (str/includes? out "type=\"deep\"") "deep variant preserved")
      (is (not (re-find #"<state id=\"player___hist\"" out))
          "scxml does NOT emit it as an occupiable <state>"))
    ;; SCXML round-trips back to `:type :history` (not a state).
    (let [back (-> deep-history-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= :history (get-in back [:states :player :states :hist :type]))
          "round-trips back to a :type :history pseudo-state"))))

;; ---------------------------------------------------------------------------
;; G9 — the `:reenter?` EXTERNAL-restart axis agrees across the three emitters
;; (rf2-9dj21r). Spec 005 §Self-transitions / XState v5: a targeted transition
;; is INTERNAL by default; `:reenter? true` is the external restart opt-in
;; (re-run :exit/:entry, restart :after/:spawn). Pre-fix NONE of the three
;; emitters represented the axis, so `{:target :same-state}` and `{:target
;; :same-state :reenter? true}` — two RUNTIME-DISTINCT machines — produced
;; IDENTICAL chart topology, Mermaid, SCXML export, AND SCXML import. These
;; tests pin that the with/without-`:reenter?` forms are now DISTINCT in every
;; emitter (and that the axis survives the SCXML round-trip).

(def reenter-self-machine
  {:initial :a :states {:a {:on {:ping {:target :same-state :reenter? true}}}}})

(def internal-default-self-machine
  {:initial :a :states {:a {:on {:ping {:target :same-state}}}}})

(deftest reenter-axis-distinct-across-all-three-emitters
  (testing "rf2-9dj21r — chart edge data, Mermaid, AND SCXML export all
            represent `:reenter? true` DISTINCTLY from the internal default"
    ;; CHART — the parsed edge carries :reenter? true (vs absent), AND the two
    ;; mint DISTINCT edge ids (so xyflow keeps both on a shared chart).
    (let [r-edge (first (filter #(= :ping (:event %))
                                (:edges (layout/project-definition reenter-self-machine))))
          i-edge (first (filter #(= :ping (:event %))
                                (:edges (layout/project-definition internal-default-self-machine))))]
      (is (true? (:reenter? r-edge)) "chart: external edge carries :reenter?")
      (is (not (contains? i-edge :reenter?)) "chart: internal default does not")
      (is (not= (:id r-edge) (:id i-edge)) "chart: distinct edge ids"))
    ;; MERMAID — the external edge carries the ↻ marker; the internal one does not.
    (let [r-out (mermaid/emit reenter-self-machine {:fenced? false :header-comment? false})
          i-out (mermaid/emit internal-default-self-machine {:fenced? false :header-comment? false})]
      (is (str/includes? r-out "↻") "mermaid: external carries the ↻ marker")
      (is (not (str/includes? i-out "↻")) "mermaid: internal default has no marker")
      (is (not= r-out i-out) "mermaid: the two outputs DIFFER"))
    ;; SCXML EXPORT — the external transition emits type="external"; the internal
    ;; default does not. The two exports DIFFER.
    (let [r-xml (scxml/spec->scxml reenter-self-machine)
          i-xml (scxml/spec->scxml internal-default-self-machine)]
      (is (str/includes? r-xml "type=\"external\"") "scxml: external emits type=external")
      (is (not (str/includes? i-xml "type=\"external\"")) "scxml: internal default does not")
      (is (not= r-xml i-xml) "scxml: the two exports DIFFER")))

  (testing "rf2-9dj21r — SCXML IMPORT preserves `:reenter? true` (the round-trip
            keeps the two machines runtime-distinct on re-import)"
    (let [r-back (-> reenter-self-machine scxml/spec->scxml scxml/scxml->spec)
          i-back (-> internal-default-self-machine scxml/spec->scxml scxml/scxml->spec)]
      (is (= reenter-self-machine r-back)
          "the external machine round-trips with :reenter? true intact (the map
           form is not collapsible to a bare keyword, so it survives verbatim)")
      (is (true? (get-in r-back [:states :a :on :ping :reenter?]))
          "the imported external candidate carries :reenter? true")
      ;; The internal default's sole target-only candidate normalises to the
      ;; bare-keyword shorthand (`:same-state`) — the canonical, semantically
      ;; identical form — carrying NO :reenter?.
      (is (= :same-state (get-in i-back [:states :a :on :ping]))
          "the internal default round-trips to the target-only shorthand")
      (is (not= r-back i-back)
          "the two round-tripped specs remain DISTINCT (import did not collapse
           the external machine into the internal default)"))))

;; ---------------------------------------------------------------------------
;; Parallel-ROOT :on / :after ancestor fallback (rf2-656ivk / rf2-m3otj2)
;;
;; The parallel-root `:on` / `:after` ancestor fallbacks (Spec 005 §Root
;; parallel :on / §Root-level :after — the viz counterparts to the
;; machines-core parallel-root fix) must surface across ALL THREE emitters.
;; Pre-fix: chart projected only the root :on (dropped the root :after);
;; SCXML dropped both (only :on-done survived); mermaid dropped the
;; action-only :on (target `when-let`) and the :after entirely.

(def root-on-after-machine
  "A parallel root carrying BOTH a target-bearing :on AND a target-bearing
  :after to region substates — the cross-emitter repro fixture (canonical
  bare-target shorthand, so the SCXML round-trip is exact)."
  {:type    :parallel
   :on      {:go [:a :two]}
   :after   {1000 [:b :two]}
   :regions {:a {:initial :one :states {:one {} :two {}}}
             :b {:initial :one :states {:one {} :two {}}}}})

(deftest parallel-root-on-after-surfaced-by-all-three-emitters
  (testing "rf2-656ivk / rf2-m3otj2 — a target-bearing root :on AND root
            :after both surface in chart, mermaid, AND SCXML"
    ;; CHART — both project as MACHINE-ROOT-sourced region-scoped edges.
    (let [edges    (:edges (layout/project-definition root-on-after-machine))
          root-eds (filter :parallel-root-on? edges)]
      (is (= 2 (count root-eds)) "chart projects both root transitions")
      (is (= 1 (count (filter :parallel-root-after? root-eds)))
          "chart flags exactly one as the :after edge")
      (is (= #{[:a :two] [:b :two]} (set (map :to-path root-eds)))))
    ;; MERMAID — both render as root-fallback edges (one labelled after(...)).
    (let [out (mermaid-body root-on-after-machine)]
      (is (str/includes? out "--> a__two : go (root fallback)")
          "mermaid renders the root :on fallback edge")
      (is (str/includes? out "--> b__two : after(1000) (root fallback)")
          "mermaid renders the root :after fallback edge"))
    ;; SCXML — both emit direct <parallel> transitions AND round-trip.
    (let [out  (scxml/spec->scxml root-on-after-machine)
          back (scxml/scxml->spec out)]
      (is (str/includes? out "event=\"go\"") "scxml emits the root :on")
      (is (str/includes? out "event=\"after.1000\"") "scxml emits the root :after")
      (is (= root-on-after-machine back)
          "scxml round-trips both root transitions exactly"))))

(deftest parallel-root-action-only-on-after-surfaced-by-all-three
  (testing "rf2-656ivk / rf2-m3otj2 — an ACTION-ONLY root :on AND root :after
            surface in all three (chart self-anchors, mermaid notes, SCXML
            target-less transition)"
    (let [m {:type    :parallel
             :on      {:ping {:action :log-ping}}
             :after   {2000 {:action :timeout-log}}
             :regions {:a {:initial :one :states {:one {}}}
                       :b {:initial :one :states {:one {}}}}}]
      ;; CHART — both self-anchored on the machine-root chip.
      (let [edges (->> (layout/project-definition m) :edges (filter :parallel-root-on?))]
        (is (= 2 (count edges)))
        (is (every? :internal? edges) "both action-only root transitions self-anchor"))
      ;; MERMAID — both notes on the parallel root.
      (let [out (mermaid-body m)]
        (is (str/includes? out "ping / log-ping"))
        (is (str/includes? out "after(2000) / timeout-log")))
      ;; SCXML — both round-trip with their action names recovered.
      (let [back (-> m scxml/spec->scxml scxml/scxml->spec)]
        (is (= m back) "the action-only root :on + :after round-trip")))))

;; ---------------------------------------------------------------------------
;; rf2-5uhdaz — the FLAT-machine counterpart to the parallel-root action-only
;; `:on` agreement above. A TARGETLESS (action-only) MACHINE-LEVEL (top-level)
;; `:on` fallback runs its action + leaves the state unchanged; pre-fix the
;; chart AND mermaid dropped it (SCXML already surfaced it).

(deftest targetless-machine-level-on-surfaced-by-all-three
  (testing "rf2-5uhdaz — a targetless action-only machine-level :on fallback
            surfaces in chart (self-anchored on the machine-root chip),
            mermaid (note on the root-fallback node), AND SCXML (target-less
            <transition>)"
    (let [m {:initial :a
             :on      {:ping {:action :log-ping}}   ;; no :target
             :states  {:a {} :b {}}}]
      ;; CHART — self-anchored on the MACHINE-ROOT chip.
      (let [edges (->> (layout/project-definition m)
                       :edges
                       (filter #(and (:machine-level? %) (= :ping (:event %)))))
            e     (first edges)]
        (is (= 1 (count edges)) "chart surfaces the targetless fallback")
        (is (true? (:internal? e)) "chart flags it :internal? (self-anchored)")
        (is (= layout/machine-root-id (:source e) (:target e))
            "chart self-anchors it on the machine-root chip"))
      ;; MERMAID — a note on the root-fallback node.
      (let [out (mermaid-body m)]
        (is (str/includes? out "note right of rf_2emachines_2dviz_2emermaid_2froot_2dfallback")
            "mermaid surfaces it as a note on the root-fallback node")
        (is (str/includes? out "ping / log-ping") "mermaid note carries the action"))
      ;; SCXML — a target-less <transition> (already faithful pre-fix).
      (is (= 1 (scxml-internal-transition-count m))
          "scxml emits the target-less machine-level <transition>"))))

;; ---------------------------------------------------------------------------
;; rf2-f8fgz5 — a REGION's OWN top-level `:on-done` (Spec 005 §Parallel
;; `:on-done`: "a compound region reaching its own :final? child raises a
;; region-local done.state.<region-compound> that the region's :on-done
;; takes … exactly the compound case, scoped to one region"). Pre-fix,
;; mermaid's `render-region-block` destructured only `{:keys [initial
;; states on]}` — never `:on-done` — so this shape vanished from Mermaid
;; with zero trace while SCXML preserved the `done.state.<region> ->
;; <sibling-region>` transition. Scoped to mermaid<->SCXML agreement — the
;; chart projector (`chart/layout.cljc`) has its OWN pre-existing gap for
;; this shape (`project-flat` never reads a definition's top-level
;; `:on-done` at all), tracked separately as rf2-2ydc87; it is NOT part of
;; this fix.

(def region-on-done-machine
  "A parallel machine whose region :a completes into sibling region :b."
  {:type    :parallel
   :regions {:a {:initial :a1
                 :on-done :b
                 :states  {:a1 {:on {:go :a2}}
                           :a2 {:final? true}}}
             :b {:initial :b1
                 :states  {:b1 {:on {:go :b2}}
                           :b2 {:final? true}}}}})

(def region-on-done-action-only-machine
  "A parallel machine whose region :a's own :on-done is ACTION-ONLY (no
  target — the engine just runs :log; the region stays in its final
  config)."
  {:type    :parallel
   :regions {:a {:initial :a1
                 :on-done {:action :log}
                 :states  {:a1 {:on {:go :a2}}
                           :a2 {:final? true}}}
             :b {:initial :b1
                 :states  {:b1 {:on {:go :b2}}}}}})

(deftest region-on-done-target-bearing-surfaced-by-mermaid-and-scxml
  (testing "rf2-f8fgz5 — a region's own top-level :on-done with a keyword
            target surfaces as a completion transition in BOTH mermaid AND
            SCXML, resolving to the SIBLING region in both"
    ;; MERMAID
    (let [out (mermaid-body region-on-done-machine)]
      (is (str/includes? out "a --> b : ✓ done")
          "mermaid renders the region's completion edge to its sibling region"))
    ;; SCXML
    (let [out (scxml/spec->scxml region-on-done-machine)]
      (is (str/includes? out "event=\"done.state.a\" target=\"b\"")
          "scxml renders the identical done.state.<region> -> <sibling> transition"))))

(deftest region-on-done-action-only-surfaced-by-mermaid-and-scxml
  (testing "rf2-f8fgz5 — a region's own top-level :on-done that is
            ACTION-ONLY (no target) surfaces in BOTH mermaid (a note on the
            region root) AND SCXML (a target-less <transition> with the
            action comment)"
    ;; MERMAID
    (let [out (mermaid-body region-on-done-action-only-machine)]
      (is (str/includes? out "note right of a")
          "mermaid surfaces the action-only region on-done as a note on the region root")
      (is (str/includes? out "on-done: ✓ done / log")))
    ;; SCXML
    (let [out (scxml/spec->scxml region-on-done-action-only-machine)]
      (is (str/includes? out "event=\"done.state.a\"><!-- action: log --></transition>")
          "scxml surfaces the action-only region on-done as a target-less transition"))))

;; ---------------------------------------------------------------------------
;; EP-0011 — error-final terminal KIND surfaces in ALL THREE emitters.
;;
;; Spec 005 §:final? lets a `:final?` leaf carry `:error? true`: a child
;; finishing via an error final lowers to the uniform reply envelope as
;; `:status :error` and routes the spawning parent's `:spawn` `:on-error`
;; (vs a plain final's `:status :ok` / `:on-done`). The KIND must not
;; collapse silently on any surface: the chart paints the error-hue ring,
;; the SCXML emitter carries `data_rf_error_final` (lossless round-trip),
;; and the Mermaid emitter notes the error terminal.

(def success-and-error-finals-machine
  {:initial :running
   :states  {:running {:on {:ok :ok :boom :boom}}
             :ok      {:final? true}
             :boom    {:final? true :error? true}}})

(deftest error-final-kind-surfaced-by-all-three-emitters
  (testing "EP-0011 — an :error? final reads distinctly from a success
            final in chart, mermaid, AND SCXML; a plain :final? does not
            pick up the error KIND"
    ;; CHART — the layout node carries :error? (gated on :final?).
    (let [nodes  (:nodes (layout/project-definition success-and-error-finals-machine))
          by-lbl (into {} (map (juxt :label identity) nodes))]
      (is (true?  (:error? (get by-lbl "boom"))) "chart flags the error final")
      (is (false? (:error? (get by-lbl "ok")))   "chart leaves the success final plain")
      (is (false? (:error? (get by-lbl "running"))) "a non-final node is never error"))
    ;; MERMAID — the error final gets a note; the success final does not.
    (let [out (mermaid-body success-and-error-finals-machine)]
      (is (str/includes? out "ok --> [*]")   "success terminal renders plainly")
      (is (str/includes? out "boom --> [*]") "error terminal renders the edge")
      (is (str/includes? out "note right of boom") "error terminal carries a note")
      (is (not (str/includes? out "note right of ok"))
          "success terminal carries no error note"))
    ;; SCXML — the error final carries the carrier attr AND round-trips :error?.
    (let [out  (scxml/spec->scxml success-and-error-finals-machine)
          back (scxml/scxml->spec out)]
      (is (str/includes? out "<final id=\"boom\" data_rf_error_final=\"true\"")
          "scxml carries the error-final attribute")
      (is (str/includes? out "<final id=\"ok\"")
          "scxml emits the success final as a plain <final>")
      (is (= success-and-error-finals-machine back)
          "scxml round-trips the error-final spec exactly")
      (is (true? (get-in back [:states :boom :error?]))
          "the error terminal reconstructs :error? true")
      (is (nil? (get-in back [:states :ok :error?]))
          "the success terminal carries no :error? bit"))))

;; ---------------------------------------------------------------------------
;; rf2-egupfk — the three emitters agree on DEFINITION-SHAPE VALIDATION.
;;
;; Pre-fix each emitter hand-rolled its own `valid-state-tree?` / parallel
;; check and the copies had DRIFTED:
;;
;;   - SCXML accepted MALFORMED PARALLEL REGION BODIES (a region with no
;;     `:initial` / `:states`, or a nil region) that AI + Mermaid rejected —
;;     it checked only `(and (map? regions) (seq regions))`, never that each
;;     region was itself a valid state tree.
;;   - AI required a KEYWORD `:initial` (the machine contract — Spec 005
;;     §Transition table grammar: state ids are keywords) while Mermaid +
;;     SCXML accepted ANY TRUTHY `:initial` (a string / number slipped
;;     through).
;;
;; The fix routes all three through the SHARED `grammar/valid-definition?`
;; (adopting the STRICT reading: keyword `:initial`, well-formed regions), so
;; a definition is accepted by ALL THREE or rejected by ALL THREE. Each keeps
;; its surface-specific error id (`:ai-generate/invalid-spec` /
;; `:mermaid/invalid-definition` / `:scxml/invalid-spec`).

(defn- ai-rejects?
  "True when the AI emitter rejects `definition` (its resolver returns the
  definition verbatim as EDN, so the parse succeeds and only the shape
  validation can reject)."
  [definition]
  (try
    (ai/generate-machine "x" {:resolver (constantly (pr-str definition))})
    false
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ true)))

(defn- mermaid-rejects? [definition]
  (try (mermaid/emit definition) false
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ true)))

(defn- scxml-rejects? [definition]
  (try (scxml/spec->scxml definition) false
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ true)))

(def malformed-parallel-region-body-machine
  "THE SCXML drift repro: a parallel root whose region :a carries NO
  `:initial` / `:states` (an empty region body). Pre-fix SCXML emitted it
  happily; AI + Mermaid rejected it. Now all three reject."
  {:type :parallel :regions {:a {}}})

(def nil-parallel-region-machine
  "A parallel root with a nil region body — the more degenerate SCXML-drift
  shape."
  {:type :parallel :regions {:a nil}})

(def region-missing-states-machine
  {:type :parallel :regions {:a {:initial :x}}})

(def region-non-keyword-initial-machine
  {:type :parallel :regions {:a {:initial "x" :states {:x {}}}}})

(def non-keyword-initial-machine
  "THE AI drift repro: a flat machine whose `:initial` is a STRING, not a
  keyword. Pre-fix AI rejected it (keyword? guard) while Mermaid + SCXML
  accepted it (truthy `:initial`). Now all three reject."
  {:initial "a" :states {:a {}}})

(def numeric-initial-machine
  {:initial 42 :states {:a {}}})

(def invalid-definitions
  "Definitions EVERY emitter must reject (post-fix agreement)."
  [nil
   42
   "not-a-machine"
   [:a :b]
   {:not-a-machine 42}
   {:initial :a}                                   ;; missing :states
   {:initial :a :states {}}                        ;; empty :states
   non-keyword-initial-machine                     ;; AI-drift
   numeric-initial-machine
   {:type :parallel}                               ;; missing :regions
   {:type :parallel :regions {}}                   ;; empty :regions
   malformed-parallel-region-body-machine          ;; SCXML-drift
   nil-parallel-region-machine
   region-missing-states-machine
   region-non-keyword-initial-machine])

(def valid-definitions
  "Definitions EVERY emitter must accept (post-fix agreement)."
  [{:initial :a :states {:a {}}}
   {:initial :a :states {:a {:on {:go :b}} :b {:final? true}}}
   {:type :parallel :regions {:a {:initial :x :states {:x {}}}}}
   {:type :parallel :regions {:a {:initial :x :states {:x {}}}
                              :b {:initial :y :states {:y {}}}}}])

(deftest all-three-emitters-reject-the-same-invalid-definitions
  (testing "rf2-egupfk — AI, Mermaid, AND SCXML reject every malformed
            definition identically (the previously-divergent shapes now
            agree)"
    (doseq [d invalid-definitions]
      (let [a (ai-rejects? d)
            m (mermaid-rejects? d)
            s (scxml-rejects? d)]
        (is (true? a) (str "AI must reject "      (pr-str d)))
        (is (true? m) (str "Mermaid must reject " (pr-str d)))
        (is (true? s) (str "SCXML must reject "   (pr-str d)))
        (is (= a m s) (str "all three must AGREE on rejecting " (pr-str d)))))))

(deftest all-three-emitters-accept-the-same-valid-definitions
  (testing "rf2-egupfk — AI, Mermaid, AND SCXML accept every well-formed
            definition identically"
    (doseq [d valid-definitions]
      (let [a (ai-rejects? d)
            m (mermaid-rejects? d)
            s (scxml-rejects? d)]
        (is (false? a) (str "AI must accept "      (pr-str d)))
        (is (false? m) (str "Mermaid must accept " (pr-str d)))
        (is (false? s) (str "SCXML must accept "   (pr-str d)))
        (is (= a m s) (str "all three must AGREE on accepting " (pr-str d)))))))

(deftest scxml-now-rejects-malformed-parallel-region-bodies
  (testing "rf2-egupfk — SCXML (the pre-fix LAX emitter) now rejects a
            parallel region body with no :initial/:states, matching AI +
            Mermaid (pre-fix SCXML emitted it)"
    (is (scxml-rejects? malformed-parallel-region-body-machine)
        "SCXML rejects the empty region body it used to accept")
    (is (scxml-rejects? nil-parallel-region-machine))
    (is (scxml-rejects? region-missing-states-machine))
    (is (scxml-rejects? region-non-keyword-initial-machine))
    ;; Still throws the SCXML-surface id (not the AI/Mermaid ids).
    (let [d (try (scxml/spec->scxml malformed-parallel-region-body-machine) nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :scxml/invalid-spec (:rf.error/id d))
          "SCXML keeps its surface-specific error id"))))

(deftest all-three-now-reject-non-keyword-initial
  (testing "rf2-egupfk — Mermaid + SCXML (the pre-fix LAX emitters) now
            reject a non-keyword :initial, matching AI (the machine contract:
            state ids are keywords)"
    (doseq [d [non-keyword-initial-machine numeric-initial-machine]]
      (is (mermaid-rejects? d) (str "Mermaid now rejects " (pr-str d)))
      (is (scxml-rejects? d)   (str "SCXML now rejects "   (pr-str d)))
      (is (ai-rejects? d)      (str "AI still rejects "    (pr-str d))))
    ;; Each keeps its surface-specific error id.
    (let [md (try (mermaid/emit non-keyword-initial-machine) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))
          sd (try (scxml/spec->scxml non-keyword-initial-machine) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (= :mermaid/invalid-definition (:rf.error/id md)))
      (is (= :scxml/invalid-spec (:rf.error/id sd))))))

(deftest invalid-definition-summaries-stay-value-free-across-emitters
  (testing "rf2-egupfk — the shared value-free summary excludes the raw
            :data slot in every emitter's thrown error (a malformed
            definition can carry live runtime values under :data)"
    (let [secret "cross-emitter-secret-42"
          ;; Malformed (empty :states) but carries a secret :data slot.
          bad    {:initial :a :states {} :data {:token secret}}
          leaks? (fn [d] (some #(and (string? %) (str/includes? % secret))
                               (tree-seq coll? seq d)))
          md     (try (mermaid/emit bad) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))
          sd     (try (scxml/spec->scxml bad) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))
          ad     (try (ai/generate-machine "x" {:resolver (constantly (pr-str bad))}) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
      (is (some? (:definition-summary md)) "mermaid carries a value-free summary")
      (is (some? (:spec-summary sd))       "scxml carries a value-free summary")
      (is (some? (:spec-summary ad))       "ai carries a value-free summary")
      (is (not (leaks? md)) "mermaid summary omits the :data secret")
      (is (not (leaks? sd)) "scxml summary omits the :data secret")
      (is (not (leaks? ad)) "ai summary omits the :data secret"))))
