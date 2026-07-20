(ns re-frame.ui.conditional-root-annotation-cljs-test
  "rf2-hac8p (98.1 slice c — audit obligation #1): the compiler's host-root
  view-evidence annotation reaches COMMON CONDITIONAL roots. A view whose host
  root is an `if` / `if-not` / `when` / `when-not` / `cond` / `case` / if-let
  family gains `data-rf2-source-coord` / `data-rf-view` on whichever concrete DOM
  arm renders — the same view identity on every arm. Proven here by a REAL
  react-dom/server render in the NODE CLJS suite (`npm run test:cljs`): the dev
  build (goog.DEBUG true) carries the annotation in the serialised markup; the
  `:advanced` + goog.DEBUG=false elision is covered by the shared elision probe.

  This is the acceptance teeth the audit reopening demanded: the defect surfaces
  on a real CLJS render, not only in the JVM structural emit (which the sibling
  `emit_cljs_view_evidence_annotation_jvm_test` pins on the emit-data side)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ["react-dom/server" :as rds]
            [re-frame.ui :refer [defview]]
            [re-frame.ui.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Conditional-root views (capability-free — render standalone through jsx2)
;; ---------------------------------------------------------------------------

(defview if-root
  "Host root is an (if …): each arm is a single compiler-owned element, so BOTH
  arms are tagged — whichever renders reports the view."
  [{:keys [big?]}]
  (if big? [:div.big "big"] [:section.small "small"]))

(defview when-root
  "One-arm (when …): the element arm is tagged; the implicit nil else renders
  nothing and stays exempt."
  [{:keys [show?]}]
  (when show? [:p.shown "shown"]))

(defview if-let-root
  "The if-let family desugars to let-over-if — the descent reaches both arms."
  [{:keys [v]}]
  (if-let [x v] [:span.has (str x)] [:span.none "none"]))

(defview case-root
  "A (case …) host root: each clause branch and the default is a concrete
  element and is tagged."
  [{:keys [k]}]
  (case k
    :a [:a.arm-a "a"]
    :b [:b.arm-b "b"]
    [:i.arm-d "d"]))

(defview fragment-root
  "Retained exemption: a fragment root has no single positional host node, so it
  carries no annotation (the same host-root exemption a non-DOM root gets)."
  [{:keys [a b]}]
  [:<> [:dt a] [:dd b]])

;; ---------------------------------------------------------------------------

(defn- render [view props] (rds/renderToStaticMarkup (rt/jsx2 view props)))

(defn- tagged-as?
  "The rendered markup carries the view-evidence pair AND the view tag ends in
  `/<vname>` (so the annotation is THIS view's, not some inner element's)."
  [html vname]
  (and (str/includes? html "data-rf2-source-coord=\"")
       (boolean (re-find (re-pattern (str "data-rf-view=\"[^\"]*/" vname "\"")) html))))

(deftest an-if-root-tags-whichever-arm-renders
  (testing "the true arm (a <div>) carries the view-evidence annotation"
    (let [html (render if-root #js {:big? true})]
      (is (str/includes? html "<div ") "the div arm rendered")
      (is (tagged-as? html "if-root")
          "RED before the walk descends :if — the div host root was untagged")))
  (testing "the false arm (a <section>) ALSO carries it"
    (let [html (render if-root #js {:big? false})]
      (is (str/includes? html "<section ") "the section arm rendered")
      (is (tagged-as? html "if-root")))))

(deftest a-when-root-tags-the-concrete-arm-and-exempts-the-nil-arm
  (testing "the present arm (a <p>) is tagged"
    (let [html (render when-root #js {:show? true})]
      (is (str/includes? html "<p ") "the p arm rendered")
      (is (tagged-as? html "when-root"))))
  (testing "the absent arm renders nothing — no host node, no annotation"
    (let [html (render when-root #js {:show? false})]
      (is (not (str/includes? html "data-rf-view=\""))
          "an empty render carries no host-root annotation"))))

(deftest an-if-let-root-tags-both-arms
  (testing "the truthy-binding arm is tagged"
    (let [html (render if-let-root #js {:v 5})]
      (is (str/includes? html "<span class=\"has\"") "the has arm rendered")
      (is (tagged-as? html "if-let-root"))))
  (testing "the else arm is tagged too"
    (let [html (render if-let-root #js {:v nil})]
      (is (str/includes? html "<span class=\"none\"") "the none arm rendered")
      (is (tagged-as? html "if-let-root")))))

(deftest a-case-root-tags-every-clause-and-the-default
  (doseq [[k tag] [[:a "<a "] [:b "<b "] [:z "<i "]]]
    (let [html (render case-root #js {:k k})]
      (is (str/includes? html tag) (str "the " k " arm rendered"))
      (is (tagged-as? html "case-root")
          (str "the " k " arm (clause or default) carries the annotation")))))

(deftest a-fragment-root-carries-no-annotation
  (let [html (render fragment-root #js {:a "x" :b "y"})]
    (is (str/includes? html "<dt>x</dt>") "the fragment spliced its children")
    (is (not (str/includes? html "data-rf-view=\""))
        "a fragment root has no single host node — retained exemption")))
