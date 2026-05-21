(ns re-frame.story.ui.assertion-strip-cljs-test
  "CLJS-side regression net for the shared inline assertion strip.

  The strip is consumed by both the canvas inline strip and the
  workspace cell — see `re-frame.story.ui.canvas/render-assertions` +
  `re-frame.story.ui.workspace/variant-cell-inner`. The legacy inline
  rendering `pr-str`-ed raw assertion records; this strip lifts the
  Storybook-inspired shape that already lives in
  `re-frame.story.ui.test-mode.view`.

  Surface covered (pure + rendered):

  - `truncate`        — clamp with ellipsis
  - `value-display`   — clamp a detail :expected / :actual value with a
                        :long? flag for the click-to-reveal chord
  - `summary-line`    — fail → reason/expected vs actual; pass → blank;
                        skip → reason
  - `group-by-event`  — cluster records by dispatching :event, preserve
                        insertion order, nil-event records cluster under
                        a leading group
  - `assertion-strip` rendered hiccup shape — wrap div with the canonical
                        data-test, one row per record, group head only
                        appears when there are >1 groups, failed rows
                        seed the expanded set so the detail panel
                        renders on first paint
  - `render-row`      — status / label / glyph wiring; click toggles
                        through the `on-toggle` callback"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.ui.assertion-strip :as strip]))

;; ---- pure: truncate ------------------------------------------------------

(deftest truncate-short-passthrough
  (testing "strings shorter than the limit pass through unchanged"
    (is (= "abc"   (strip/truncate "abc" 10)))
    (is (= ""      (strip/truncate nil 10)))
    (is (= "1"     (strip/truncate "1" 10)))))

(deftest truncate-long-ellipsis
  (testing "strings longer than the limit truncate with a single ellipsis char"
    (let [out (strip/truncate "aaaaaaaaaaaaaaaaaa" 5)]
      (is (= 5 (count out))
          "output length matches the limit (ellipsis included)")
      (is (= "aaaa…" out)
          "last char is the ellipsis"))))

(deftest truncate-coerces-non-string
  (testing "non-string input is coerced through pr-str-ish (str)"
    (is (= "42"    (strip/truncate 42 10)))))

;; ---- pure: summary-line --------------------------------------------------

(deftest summary-line-pass-blank
  (testing "passing rows return the empty string — the label already names
            the assertion and the strip stays compact"
    (let [row {:status :pass
               :detail {:expected 1 :actual 1}}]
      (is (= "" (strip/summary-line row))))))

(deftest summary-line-fail-reason
  (testing "failing rows surface the :reason when present"
    (let [row {:status :fail
               :detail {:reason "values differ"}}]
      (is (= "values differ" (strip/summary-line row))))))

(deftest summary-line-fail-expected-actual
  (testing "failing rows with no :reason fall back to expected vs actual"
    (let [row {:status :fail
               :detail {:expected 99 :actual 0}}]
      (is (= "expected 99 · actual 0" (strip/summary-line row))))))

(deftest summary-line-skip-reason
  (testing "skipped rows surface the :reason (or a default placeholder)"
    (is (= "feature gated"
           (strip/summary-line {:status :skip
                                :detail {:reason "feature gated"}})))
    (is (= "skipped"
           (strip/summary-line {:status :skip :detail {}}))
        "no :reason → 'skipped' placeholder")))

(deftest summary-line-fail-truncates
  (testing "long :reason values clamp to the strip's character limit"
    (let [long-reason (apply str (repeat 200 "x"))
          row         {:status :fail :detail {:reason long-reason}}
          out         (strip/summary-line row)]
      (is (<= (count out) 72)
          "output respects the truncate-len cap")
      (is (re-find #"…$" out)
          "truncated output ends in an ellipsis"))))

;; ---- pure: group-by-event ------------------------------------------------

(deftest group-by-event-clusters
  (testing "records with the same :event cluster under one group in
            insertion order"
    (let [records [{:assertion :rf.assert/path-equals :event [:counter/inc]}
                   {:assertion :rf.assert/path-equals :event [:counter/inc]}
                   {:assertion :rf.assert/path-equals :event [:counter/dec]}]
          groups  (strip/group-by-event records)]
      (is (= 2 (count groups)))
      (is (= [:counter/inc] (-> groups (nth 0) :event)))
      (is (= 2 (-> groups (nth 0) :records count)))
      (is (= [:counter/dec] (-> groups (nth 1) :event)))
      (is (= 1 (-> groups (nth 1) :records count))))))

(deftest group-by-event-preserves-insertion-order
  (testing "the first occurrence of each :event sets that group's position
            in the output, even if the records interleave later"
    (let [records [{:event [:a]} {:event [:b]} {:event [:a]} {:event [:c]}]
          groups  (strip/group-by-event records)]
      (is (= [[:a] [:b] [:c]] (mapv :event groups))
          "groups appear in first-seen order")
      (is (= 2 (-> groups (nth 0) :records count))
          ":a cluster carries both records"))))

(deftest group-by-event-nil-event-cluster
  (testing "records with no :event (phase-0 setup assertions, decorator
            throws) cluster under a leading nil-event group"
    (let [records [{:assertion :rf.assert/x}
                   {:assertion :rf.assert/y :event [:click]}
                   {:assertion :rf.assert/z}]
          groups  (strip/group-by-event records)]
      (is (= 2 (count groups)))
      (is (nil? (-> groups (nth 0) :event))
          ":event nil cluster is its own group")
      (is (= 2 (-> groups (nth 0) :records count))
          "both nil-event records cluster together"))))

(deftest group-by-event-empty
  (testing "empty input → empty groups vector"
    (is (= [] (strip/group-by-event [])))
    (is (= [] (strip/group-by-event nil)))))

;; ---- pure: status-glyph map ----------------------------------------------

(deftest status-glyph-shape
  (testing "the three status glyphs (Storybook-inspired pattern #1)
            are exposed publicly so tests + downstream consumers can
            pin them"
    (is (= "✓" (:pass strip/status-glyph)))
    (is (= "✗" (:fail strip/status-glyph)))
    (is (= "⊘" (:skip strip/status-glyph)))))

;; ---- rendered: render-row ------------------------------------------------

(defn- find-prop
  "Walk `hiccup` and return the first prop-map carrying `(= (get m k) v)`.
  Returns nil when nothing matches. Used by these tests to assert the
  structured row treatment lands the canonical data-test attributes."
  [hiccup k v]
  (let [match? (fn [x]
                 (and (map? x) (= v (get x k))))]
    (letfn [(walk [node]
              (cond
                (match? node) node
                (vector? node)
                (some walk node)
                (seq? node)
                (some walk node)
                :else nil))]
      (walk hiccup))))

(deftest render-row-pass-shape
  (testing "a passing row carries the data-test stamps the chrome-level
            test widget reads + the spec-pinned status glyph"
    (let [row    {:status   :pass
                  :label    ":rf.assert/path-equals [[:counter] 1]"
                  :row-key  ":rf.assert/path-equals [[:counter] 1]"
                  :detail   {:expected 1 :actual 1}}
          hiccup (strip/render-row row false (fn [_]))]
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-row"))
          "row wrapper carries the canonical data-test")
      (let [row-wrapper (find-prop hiccup :data-test "story-canvas-assertion-row")]
        (is (= "pass" (:data-status row-wrapper))
            ":data-status reflects the row's status keyword (lowercased)"))
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-glyph")))
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-label"))))))

(deftest render-row-fail-shape-summary-on-reason
  (testing "a failing row surfaces the :reason as the inline summary AND
            carries the fail-status stamp"
    (let [row    {:status  :fail
                  :label   ":rf.assert/path-equals [[:counter] 99]"
                  :row-key ":rf.assert/path-equals [[:counter] 99]"
                  :detail  {:expected 99 :actual 0 :reason "values differ"}}
          hiccup (strip/render-row row true (fn [_]))
          wrap   (find-prop hiccup :data-test "story-canvas-assertion-row")]
      (is (= "fail" (:data-status wrap)))
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-summary"))
          "inline summary span renders when status is :fail with a :reason")
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "open? true → the detail panel renders inline"))))

(deftest render-row-skip-shape-no-detail-when-collapsed
  (testing "a skipped row stays collapsed by default — detail panel
            absent unless the caller passes open? true"
    (let [row    {:status  :skip
                  :label   ":rf.assert/skipped"
                  :row-key ":rf.assert/skipped"
                  :detail  {:reason "feature gated"}}
          hiccup (strip/render-row row false (fn [_]))]
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-summary"))
          ":skip rows surface the reason summary even when collapsed")
      (is (nil? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "detail panel suppressed when open? false"))))

;; ---- rendered: assertion-strip component ---------------------------------
;;
;; The component returns a reagent inner-fn (closure-with-state pattern).
;; Calling `(strip/assertion-strip assertions)` returns the outer fn; we
;; invoke it once with the assertions vector to get the inner fn, then
;; invoke that with the same vector to get the hiccup. This is the
;; standard reagent-with-init shape; tests pin the rendered tree without
;; standing up a React mount.

(defn- render-strip
  "Invoke the assertion-strip component and return its rendered hiccup.

  The strip renders each row as a Reagent component vector
  `[strip/render-row row open? toggle]` (the key MUST sit on a vector
  literal — rf2-5lw9w), so the raw hiccup carries un-expanded row
  elements. Tests that assert per-row `:key` meta read this raw form;
  tests that walk the row's inner shape use `render-strip-expanded`."
  [assertions]
  (let [inner (strip/assertion-strip assertions)]
    (inner assertions)))

(defn- expand-row-elements
  "Walk `hiccup` and replace each `[strip/render-row row open? toggle]`
  component vector with the hiccup that `render-row` produces — i.e. what
  Reagent expands the element into at mount time. Lets the shape-walking
  tests below assert the row's inner `data-test` tree even though the
  strip now emits component vectors (so React keys land on the element).
  Preserves all other nodes verbatim."
  [hiccup]
  (letfn [(render-row-element? [x]
            (and (vector? x)
                 (= strip/render-row (first x))))
          (expand [node]
            (cond
              (render-row-element? node) (apply strip/render-row (rest node))
              (vector? node)             (mapv expand node)
              (seq? node)                (map expand node)
              :else                      node))]
    (expand hiccup)))

(defn- render-strip-expanded
  "Render the strip and expand its row component-vectors into their inner
  hiccup so shape-walking tests see the rendered row tree."
  [assertions]
  (expand-row-elements (render-strip assertions)))

(deftest assertion-strip-empty-renders-nil
  (testing "empty / nil assertions → no inline strip"
    (is (nil? (render-strip [])))
    (is (nil? (render-strip nil)))))

(deftest assertion-strip-wrap-carries-canonical-data-test
  (testing "the wrap div stamps `story-canvas-assertion-strip` so the
            chrome-level test widget can scope queries to the strip"
    (let [assertions [{:assertion :rf.assert/path-equals
                       :passed?   true
                       :payload   [[:c] 1]
                       :expected  1 :actual 1}]
          hiccup     (render-strip assertions)]
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-strip"))))))

(deftest assertion-strip-one-row-per-record
  (testing "the strip renders exactly one row per assertion record"
    (let [assertions [{:assertion :rf.assert/path-equals
                       :passed? true :payload [[:c] 1] :expected 1 :actual 1}
                      {:assertion :rf.assert/path-equals
                       :passed? false :payload [[:c] 2] :expected 2 :actual 0
                       :reason "values differ"}
                      {:assertion :rf.assert/skipped
                       :passed? false :reason "feature gated"}]
          hiccup     (render-strip-expanded assertions)
          rows       (atom [])]
      (letfn [(walk [node]
                (cond
                  (and (map? node) (= "story-canvas-assertion-row" (:data-test node)))
                  (swap! rows conj node)
                  (vector? node) (run! walk node)
                  (seq? node)    (run! walk node)))]
        (walk hiccup))
      (is (= 3 (count @rows)))
      (is (= #{"pass" "fail" "skip"}
             (into #{} (map :data-status @rows)))))))

(deftest assertion-strip-fail-auto-expands
  (testing "pattern #2 — failed assertions land already-open; the
            detail panel renders on first paint without a user click"
    (let [assertions [{:assertion :rf.assert/path-equals
                       :passed? true :payload [[:c] 1] :expected 1 :actual 1}
                      {:assertion :rf.assert/path-equals
                       :passed? false :payload [[:c] 2]
                       :expected 2 :actual 0 :reason "values differ"}]
          hiccup     (render-strip-expanded assertions)]
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "the failing row's detail panel is open on first render"))))

(deftest assertion-strip-pass-stays-collapsed
  (testing "pattern #2 — passing assertions stay collapsed by default;
            no detail panel renders without a click"
    (let [assertions [{:assertion :rf.assert/path-equals
                       :passed? true :payload [[:c] 1] :expected 1 :actual 1}
                      {:assertion :rf.assert/path-equals
                       :passed? true :payload [[:c] 2] :expected 2 :actual 2}]
          hiccup     (render-strip-expanded assertions)]
      (is (nil? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "all-passing strip renders zero detail panels — the user sees
           just the row band"))))

(deftest assertion-strip-single-group-suppresses-head
  (testing "pattern #5 — when all records share a single :event (or
            all carry no event) the group head is suppressed to keep
            the strip compact"
    (let [assertions [{:assertion :rf.assert/path-equals :passed? true
                       :event [:click] :payload [[:c] 1]}
                      {:assertion :rf.assert/path-equals :passed? true
                       :event [:click] :payload [[:c] 2]}]
          hiccup     (render-strip assertions)]
      (is (nil? (find-prop hiccup :data-test "story-canvas-assertion-group-head"))
          "single-group renders no group-head — only the rows"))))

(deftest assertion-strip-multi-group-renders-heads
  (testing "pattern #5 — when records cluster across >1 :event slots,
            each cluster is labelled with a group head"
    (let [assertions [{:assertion :rf.assert/path-equals :passed? true
                       :event [:click]}
                      {:assertion :rf.assert/path-equals :passed? true
                       :event [:submit]}]
          hiccup     (render-strip assertions)
          heads      (atom [])]
      (letfn [(walk [node]
                (cond
                  (and (map? node) (= "story-canvas-assertion-group-head" (:data-test node)))
                  (swap! heads conj node)
                  (vector? node) (run! walk node)
                  (seq? node)    (run! walk node)))]
        (walk hiccup))
      (is (= 2 (count @heads))
          "one head per dispatching event"))))

;; ---- :key meta on the row seq --------------------------------------------
;;
;; Regression net for rf2-5lw9w. The inner row `for` previously attached
;; `^{:key ...}` to the function-CALL form `(render-row ...)`; in CLJS the
;; metadata is dropped at read time (it never transfers to render-row's
;; return value), so React saw an unkeyed row seq and warned 194×/run —
;; failing the Story/Causa feature-load browser gate all session. The fix
;; renders each row as a component vector `[render-row ...]` so the key
;; lands on the element. The pre-existing suite checked row SHAPE but never
;; the seq-key contract, which is why this slipped past node-test. These
;; tests pin `(meta element) :key` on every rendered row element.

(defn- collect-row-seq-elements
  "Walk `hiccup` and collect the elements of the inner row sequence — the
  Reagent component vectors a `for` produces, one per assertion record.
  Each such element is a vector whose head is the `render-row` fn (the
  component-position fn Reagent invokes). Returns them in encounter order
  so callers can assert per-element `:key` meta."
  [hiccup]
  (let [found (atom [])
        row-element? (fn [x]
                       (and (vector? x)
                            (fn? (first x))
                            (= strip/render-row (first x))))]
    (letfn [(walk [node]
              (cond
                (row-element? node) (swap! found conj node)
                (vector? node)      (run! walk node)
                (seq? node)         (run! walk node)
                :else               nil))]
      (walk hiccup))
    @found))

(deftest assertion-strip-row-seq-elements-carry-key-meta
  (testing "every rendered row element carries a unique :key in its
            metadata so React's row seq is keyed — the meta MUST sit on a
            vector literal, NOT a function-call form (rf2-5lw9w)"
    (let [assertions [{:assertion :rf.assert/path-equals
                       :passed? true :payload [[:c] 1] :expected 1 :actual 1}
                      {:assertion :rf.assert/path-equals
                       :passed? false :payload [[:c] 2] :expected 2 :actual 0
                       :reason "values differ"}
                      {:assertion :rf.assert/skipped
                       :passed? false :reason "feature gated"}]
          hiccup     (render-strip assertions)
          rows       (collect-row-seq-elements hiccup)]
      (is (= 3 (count rows))
          "one row element per assertion record")
      (is (every? vector? rows)
          "rows are component vectors — `[render-row ...]`, not call forms")
      (is (every? #(some? (:key (meta %))) rows)
          "every row element carries a :key in its metadata so React's
           row seq is keyed (no missing-key warning)")
      (is (= (count rows)
             (count (into #{} (map #(:key (meta %)) rows))))
          ":key values are unique across the row seq"))))

(deftest assertion-strip-row-key-meta-survives-multi-group
  (testing "the :key meta lands on row elements across multiple groups —
            the group/row index path keeps keys unique strip-wide"
    (let [assertions [{:assertion :rf.assert/path-equals :passed? true
                       :event [:click] :payload [[:c] 1]}
                      {:assertion :rf.assert/path-equals :passed? false
                       :event [:click] :payload [[:c] 2] :reason "differ"}
                      {:assertion :rf.assert/path-equals :passed? true
                       :event [:submit] :payload [[:c] 3]}]
          hiccup     (render-strip assertions)
          rows       (collect-row-seq-elements hiccup)
          keys       (map #(:key (meta %)) rows)]
      (is (= 3 (count rows))
          "one row element per record across both groups")
      (is (every? some? keys)
          "every cross-group row element is keyed")
      (is (= 3 (count (into #{} keys)))
          "keys are unique strip-wide even across groups"))))

;; ---- pure: value-display -------------------------------------------------

(deftest value-display-short-no-clamp
  (testing "a short value's :clamped equals :full and :long? is false —
            no click-to-reveal chord needed"
    (let [out (strip/value-display 42)]
      (is (= "42" (:full out)))
      (is (= "42" (:clamped out)))
      (is (false? (:long? out)))))
  (testing "a moderate map under the cap also passes through unclamped"
    (let [out (strip/value-display {:a 1 :b 2})]
      (is (false? (:long? out)))
      (is (= (:full out) (:clamped out))))))

(deftest value-display-long-clamps
  (testing "a value whose pr-str exceeds the detail cap clamps with an
            ellipsis and flags :long? so the renderer attaches the
            click-to-reveal chord"
    (let [big {:k (apply str (repeat 300 "x"))}
          out (strip/value-display big)]
      (is (true? (:long? out)))
      (is (re-find #"…$" (:clamped out))
          ":clamped ends in an ellipsis")
      (is (< (count (:clamped out)) (count (:full out)))
          ":clamped is shorter than the full pr-str")
      (is (= (pr-str big) (:full out))
          ":full carries the complete pr-str for the revealed view"))))

(deftest value-display-respects-explicit-cap
  (testing "the 2-arity form clamps at the caller-supplied length"
    (let [out (strip/value-display "abcdefghij" 5)]
      (is (true? (:long? out)))
      (is (= 5 (count (:clamped out)))))))

;; ---- rendered: detail-value ----------------------------------------------
;;
;; detail-value is a reagent component (closure-with-state). Invoke the
;; outer fn to get the inner render fn, then invoke that to get hiccup —
;; same shape as render-strip above.

(defn- render-detail-value
  [label v]
  (let [inner (strip/detail-value label v)]
    (inner label v)))

(deftest detail-value-short-no-reveal-chord
  (testing "a short value renders inline with NO reveal chord —
            click-to-reveal only attaches when the value is long"
    (let [hiccup (render-detail-value "expected" 7)]
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail-value"))
          "the value line carries the canonical data-test")
      (is (nil? (find-prop hiccup :data-test "story-canvas-assertion-detail-reveal"))
          "no reveal chord for a short value"))))

(deftest detail-value-long-renders-reveal-chord-collapsed
  (testing "a long value renders the reveal chord and starts collapsed —
            data-revealed is false on first paint so the panel stays
            compact — avoid blowing the panel height"
    (let [big    {:k (apply str (repeat 300 "x"))}
          hiccup (render-detail-value "actual" big)
          line   (find-prop hiccup :data-test "story-canvas-assertion-detail-value")]
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail-reveal"))
          "reveal chord present for a long value")
      (is (= "false" (:data-revealed line))
          "starts collapsed — full value hidden until the chord is clicked"))))
