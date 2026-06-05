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
  (->> (layout/parse-definition definition)
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
