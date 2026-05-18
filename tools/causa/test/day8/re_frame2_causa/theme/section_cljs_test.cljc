(ns day8.re-frame2-causa.theme.section-cljs-test
  "Pure-data tests for the shared section-row primitive (rf2-pie8q).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `perf_tier_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

  1. **Structural identity** — outer container + header + body all
     carry the expected testids; header glyph reflects `:expanded?`.
  2. **Collapsed semantics** — when `:expanded? false`, body div is
     omitted entirely (matches managed_fx_template's contract).
  3. **`:count*` option** — silent when nil, renders ` (N)` when set
     (event_detail's INTERCEPTORS / EFFECTS HANDLERS RAN contract).
  4. **`:container-padding` option** — defaults to event_detail's
     `\"8px 12px\"` and is overridable to `\"8px 0\"` for
     managed_fx_template's rhythm."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.theme.section :as section]))

;; ---- hiccup walker -----------------------------------------------------

(defn- walk-hiccup
  "Depth-first seq of every hiccup vector inside `tree`."
  [tree]
  (let [acc (volatile! [])]
    (letfn [(visit [node]
              (when (vector? node)
                (vswap! acc conj node)
                (doseq [child node] (visit child))))]
      (visit tree)
      @acc)))

(defn- find-by-testid
  [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (walk-hiccup tree)))

(defn- testids
  [tree]
  (->> (walk-hiccup tree)
       (keep (fn [n] (when (map? (second n))
                       (:data-testid (second n)))))
       set))

(defn- all-strings
  "Depth-first collection of every string descendant in `tree`."
  [tree]
  (let [acc (volatile! [])]
    (letfn [(visit [node]
              (cond
                (string? node)     (vswap! acc conj node)
                (sequential? node) (doseq [c node] (visit c))
                (map? node)        nil
                :else              nil))]
      (visit tree)
      @acc)))

;; ---- (1) structural identity --------------------------------------------

(deftest renders-outer-header-body-testids
  (let [out (section/section-row {:label "REQUEST"
                                  :testid "rf-causa-managed-fx-section-request"}
                                 [:span "payload"])
        ids (testids out)]
    (is (contains? ids "rf-causa-managed-fx-section-request")
        "outer container carries the base testid")
    (is (contains? ids "rf-causa-managed-fx-section-request-header")
        "header carries -header suffix")
    (is (contains? ids "rf-causa-managed-fx-section-request-body")
        "body carries -body suffix when expanded (the default)")))

(deftest header-glyph-reflects-expanded
  (testing "expanded? true → ▼ glyph"
    (let [out (section/section-row {:label "X" :testid "t" :expanded? true} "b")]
      (is (some #(= "▼" %) (flatten out)))
      (is (not-any? #(= "▶" %) (flatten out)))))
  (testing "expanded? false → ▶ glyph"
    (let [out (section/section-row {:label "X" :testid "t" :expanded? false} "b")]
      (is (some #(= "▶" %) (flatten out)))
      (is (not-any? #(= "▼" %) (flatten out))))))

(deftest expanded-default-is-true
  (let [out (section/section-row {:label "X" :testid "t"} "b")]
    (is (some #(= "▼" %) (flatten out))
        "no :expanded? key → defaults to true → ▼ glyph + body rendered")
    (is (contains? (testids out) "t-body"))))

;; ---- (2) collapsed semantics --------------------------------------------

(deftest collapsed-omits-body-div
  (let [out (section/section-row {:label "REQUEST" :testid "t"
                                  :expanded? false}
                                 [:span {:data-testid "payload-marker"} "p"])
        ids (testids out)]
    (is (contains? ids "t")           "outer container still renders")
    (is (contains? ids "t-header")    "header still renders")
    (is (not (contains? ids "t-body")) "body div is omitted when collapsed")
    (is (not (contains? ids "payload-marker"))
        "body hiccup is NOT mounted when collapsed (matches
         managed_fx_template's pre-refactor behaviour)")))

;; ---- (3) :count* option -------------------------------------------------

(deftest count-silent-when-nil
  (let [out  (section/section-row {:label "INTERCEPTORS" :testid "t"} "b")
        text (apply str (all-strings out))]
    (is (not (re-find #"\(" text))
        "no count-in-parens when :count* is nil")))

(deftest count-renders-in-parens
  (let [out  (section/section-row {:label "INTERCEPTORS" :testid "t"
                                   :count* 3}
                                  "b")
        text (apply str (all-strings out))]
    (is (re-find #"\(3\)" text)
        "count renders as (N) after the label")))

;; ---- (4) :container-padding option --------------------------------------

(deftest container-padding-default-matches-event-detail
  (let [out      (section/section-row {:label "X" :testid "t"} "b")
        outer    (find-by-testid out "t")
        padding  (get-in outer [1 :style :padding])]
    (is (= "8px 12px" padding)
        "default padding matches event_detail's pre-refactor rhythm")))

(deftest container-padding-overridable
  (let [out      (section/section-row {:label "X" :testid "t"
                                       :container-padding "8px 0"} "b")
        outer    (find-by-testid out "t")
        padding  (get-in outer [1 :style :padding])]
    (is (= "8px 0" padding)
        "managed_fx_template can opt into its tighter no-horizontal-pad
         rhythm by passing :container-padding explicitly")))

;; ---- (5) ladder-style consistency check ---------------------------------

(deftest dotted-border-bottom-always-rendered
  (testing "border-bottom is the dotted-rule rhythm that draws the
            stacked-section separator"
    (let [out   (section/section-row {:label "X" :testid "t"} "b")
          outer (find-by-testid out "t")
          bb    (get-in outer [1 :style :border-bottom])]
      (is (string? bb))
      (is (re-find #"dotted" bb)
          "the separator rule is dotted, not solid"))))
