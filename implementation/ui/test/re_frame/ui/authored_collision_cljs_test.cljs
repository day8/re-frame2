(ns re-frame.ui.authored-collision-cljs-test
  "rf2-x1nbv (98.1 slice c — audit obligation #2): the host-root view-evidence
  annotation obeys the AUTHORED-OWN-VALUE-WINS collision law. When the view author
  supplies `data-rf-view` / `data-rf2-source-coord` on the compiler-owned host
  root — as a literal attr, a dynamic attr, a `ui/spread` merge, or a
  `ui/spread-safe` owned map — the authored value is PRESERVED; the compiler
  evidence only fills a key the author did not supply (the adapter /
  trust-programmer law).

  Proven by a REAL react-dom/server render in the NODE CLJS suite. BEFORE the fix,
  `annotate-host-root-props` stamped both keys unconditionally (`unchecked-set`
  after final props construction), so the framework value OVERWROTE the author's —
  the exact CLJS<->JVM divergence the PR #6566 audit reproduced (JVM already let an
  authored `ui/spread` value win).

  CROSS-HOST MATRIX: this file and its JVM twin `authored_collision_jvm_test`
  assert the SAME authored values for the SAME four prop-construction paths, so
  together they pin the collision law cross-host BY CONSTRUCTION. The shared parity
  corpus carries NO collision fixture on purpose — the annotation spelling is an
  elision sentinel that legitimately survives (as an authored attr) in the
  advanced/prod bundle, which the prod-elision corpus's blunt annotation strip
  would misread as a divergence."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ["react-dom/server" :as rds]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Collision views — the host root authors an annotation-spelled attribute
;; through each of the four prop-construction paths.
;; ---------------------------------------------------------------------------

(defview static-collision
  "Literal host-root attrs of the same spelling as the annotation."
  []
  [:div.host {:data-rf-view "authored-view"
              :data-rf2-source-coord "authored-coord"}
   "body"])

(defview dynamic-collision
  "A DYNAMIC (prop-valued) host-root attr — resolved at render, still authored."
  [{:keys [v]}]
  [:div {:data-rf-view v}])

(defview spread-collision
  "A `ui/spread` runtime prop map carrying the annotation spelling."
  []
  [:div (ui/spread {:data-rf-view "spread-view" :id "sc"})])

(defview safe-owned-collision
  "A `ui/spread-safe` OWNED map authors the annotation spelling — the owned own
  value wins over the compiler evidence (and over the caller)."
  [{:keys [caller]}]
  [:div (ui/spread-safe {:data-rf-view "owned-view"} caller)])

(defview no-collision
  "No authored collision — the canonical compiler evidence is present (the
  hac8p regression guard: authored-wins must not suppress the honest stamp)."
  []
  [:div.plain "x"])

;; ---------------------------------------------------------------------------

(defn- render [view props] (rds/renderToStaticMarkup (rt/jsx2 view props)))

(deftest a-literal-authored-value-wins
  (let [html (render static-collision #js {})]
    (testing "the author's literal own values are preserved on the host root"
      (is (str/includes? html "data-rf-view=\"authored-view\"")
          "RED before the set-if-absent law — the framework value overwrote it")
      (is (str/includes? html "data-rf2-source-coord=\"authored-coord\"")))
    (testing "the compiler evidence did NOT overwrite either authored value"
      (is (not (str/includes? html "/static-collision\""))
          "the canonical view id must not appear — the authored value won")
      (is (not (re-find #"data-rf2-source-coord=\"[^\"]*:static-collision:" html))
          "the canonical source coord must not appear either"))))

(deftest a-dynamic-authored-value-wins
  (let [html (render dynamic-collision #js {:v "dynamic-view"})]
    (is (str/includes? html "data-rf-view=\"dynamic-view\"")
        "a prop-valued host-root attr survives — authored-own-value-wins")
    (is (not (str/includes? html "/dynamic-collision\""))
        "the canonical view id did not overwrite the authored dynamic value")))

(deftest a-ui-spread-authored-value-wins
  (let [html (render spread-collision #js {})]
    (is (str/includes? html "data-rf-view=\"spread-view\"")
        "a ui/spread-merged value survives — matching the JVM ui/spread path")
    (is (not (str/includes? html "/spread-collision\""))
        "the canonical view id did not overwrite the spread value")))

(deftest a-spread-safe-owned-authored-value-wins
  ;; the props ABI carries CLJS-map values inside the JS props object (as the
  ;; parity corpus does), so `caller` reaches the view as a CLJS map.
  (let [html (render safe-owned-collision #js {:caller {:data-c "c"}})]
    (is (str/includes? html "data-rf-view=\"owned-view\"")
        "the spread-safe OWNED value survives — owned wins over the evidence")
    (is (not (str/includes? html "/safe-owned-collision\""))
        "the canonical view id did not overwrite the owned value")))

(deftest no-collision-still-carries-the-canonical-evidence
  (let [html (render no-collision #js {})]
    (is (str/includes? html "data-rf-view=\"")
        "an uncollided host root still gains the honest annotation")
    (is (boolean (re-find #"data-rf-view=\"[^\"]*/no-collision\"" html))
        "the canonical view id is stamped when the author supplies none")))
