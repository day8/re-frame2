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
                        skip → reason; error → the captured error's
                        :message (rf2-uky0n)
  - `group-by-event`  — cluster records by dispatching :event, preserve
                        insertion order, nil-event records cluster under
                        a leading group
  - `assertion-strip` rendered hiccup shape — wrap div with the canonical
                        data-test, one row per record, group head only
                        appears when there are >1 groups, failed AND
                        errored rows seed the expanded set so the detail
                        panel renders on first paint
  - `render-row`      — status / label / glyph wiring; click toggles
                        through the `on-toggle` callback"
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.story.ui.assertion-strip :as rf.story.ui.assertion-strip]))

;; ---- pure: truncate ------------------------------------------------------

(deftest truncate-short-passthrough
  (testing "strings shorter than the limit pass through unchanged"
    (is (= "abc"   (rf.story.ui.assertion-strip/truncate "abc" 10)))
    (is (= ""      (rf.story.ui.assertion-strip/truncate nil 10)))
    (is (= "1"     (rf.story.ui.assertion-strip/truncate "1" 10)))))

(deftest truncate-long-ellipsis
  (testing "strings longer than the limit truncate with a single ellipsis char"
    (let [out (rf.story.ui.assertion-strip/truncate "aaaaaaaaaaaaaaaaaa" 5)]
      (is (= 5 (count out))
          "output length matches the limit (ellipsis included)")
      (is (= "aaaa…" out)
          "last char is the ellipsis"))))

(deftest truncate-coerces-non-string
  (testing "non-string input is coerced through pr-str-ish (str)"
    (is (= "42"    (rf.story.ui.assertion-strip/truncate 42 10)))))

;; ---- pure: summary-line --------------------------------------------------

(deftest summary-line-pass-blank
  (testing "passing rows return the empty string — the label already names
            the assertion and the strip stays compact"
    (let [row {:status :pass
               :detail {:expected 1 :actual 1}}]
      (is (= "" (rf.story.ui.assertion-strip/summary-line row))))))

(deftest summary-line-fail-reason
  (testing "failing rows surface the :reason when present"
    (let [row {:status :fail
               :detail {:reason "values differ"}}]
      (is (= "values differ" (rf.story.ui.assertion-strip/summary-line row))))))

(deftest summary-line-fail-expected-actual
  (testing "failing rows with no :reason fall back to expected vs actual"
    (let [row {:status :fail
               :detail {:expected 99 :actual 0}}]
      (is (= "expected 99 · actual 0" (rf.story.ui.assertion-strip/summary-line row))))))

(deftest summary-line-skip-reason
  (testing "skipped rows surface the :reason (or a default placeholder)"
    (is (= "feature gated"
           (rf.story.ui.assertion-strip/summary-line {:status :skip
                                :detail {:reason "feature gated"}})))
    (is (= "skipped"
           (rf.story.ui.assertion-strip/summary-line {:status :skip :detail {}}))
        "no :reason → 'skipped' placeholder")))

(deftest summary-line-fail-truncates
  (testing "long :reason values clamp to the strip's character limit"
    (let [long-reason (apply str (repeat 200 "x"))
          row         {:status :fail :detail {:reason long-reason}}
          out         (rf.story.ui.assertion-strip/summary-line row)]
      (is (<= (count out) 72)
          "output respects the truncate-len cap")
      (is (re-find #"…$" out)
          "truncated output ends in an ellipsis"))))

(deftest summary-line-error-message
  (testing "an errored row surfaces the captured error's :message — the
            one sentence saying what went wrong. This is the setup-failure
            case: a :setup that throws, or (rf2-0ae7o.13) one dispatching
            an event nobody registered, lands an :rf.error/exception record
            whose :error map carries the message and whose :reason is nil"
    (let [row {:status :error
               :detail {:reason nil
                        :error  {:message "no handler registered for :your/setup-event"
                                 :stack   nil
                                 :data    nil}}}]
      (is (= "no handler registered for :your/setup-event"
             (rf.story.ui.assertion-strip/summary-line row))))))

(deftest summary-line-error-falls-back-to-reason
  (testing "an errored row with no :error :message falls back to :reason,
            then to a bare \"error\" placeholder — the summary is never
            blank for an error, because a blank summary is exactly the
            defect (a silent grey row saying nothing)"
    (is (= "setup blew up"
           (rf.story.ui.assertion-strip/summary-line
             {:status :error :detail {:reason "setup blew up"}}))
        "string :reason reads as-is")
    (is (= "{:code 42}"
           (rf.story.ui.assertion-strip/summary-line
             {:status :error :detail {:reason {:code 42}}}))
        "non-string :reason renders through pr-str")
    (is (= "error"
           (rf.story.ui.assertion-strip/summary-line
             {:status :error :detail {}}))
        "neither message nor reason → the 'error' placeholder")
    (is (= "error"
           (rf.story.ui.assertion-strip/summary-line
             {:status :error :detail {:error {:message nil}}}))
        "a non-string :message does not answer for the summary")))

(deftest summary-line-error-prefers-message-over-reason
  (testing "when both are present the error's :message wins — it is the
            more specific of the two"
    (let [row {:status :error
               :detail {:reason "generic"
                        :error  {:message "specific boom"}}}]
      (is (= "specific boom" (rf.story.ui.assertion-strip/summary-line row))))))

(deftest summary-line-non-error-ignores-error-key
  (testing "CONTROL — the :error arm must not leak into the other statuses.
            A :pass row still reads blank and a :fail row still reads its
            :reason even when an :error map is present in the detail"
    (is (= "" (rf.story.ui.assertion-strip/summary-line
                {:status :pass
                 :detail {:expected 1 :actual 1
                          :error {:message "should not be read"}}}))
        ":pass stays blank — the label already names the assertion")
    (is (= "values differ" (rf.story.ui.assertion-strip/summary-line
                             {:status :fail
                              :detail {:reason "values differ"
                                       :error {:message "should not be read"}}}))
        ":fail still prefers its own :reason")))

;; ---- pure: group-by-event ------------------------------------------------

(deftest group-by-event-clusters
  (testing "records with the same :event cluster under one group in
            insertion order"
    (let [records [{:assertion :rf.assert/path-equals :event [:counter/inc]}
                   {:assertion :rf.assert/path-equals :event [:counter/inc]}
                   {:assertion :rf.assert/path-equals :event [:counter/dec]}]
          groups  (rf.story.ui.assertion-strip/group-by-event records)]
      (is (= 2 (count groups)))
      (is (= [:counter/inc] (-> groups (nth 0) :event)))
      (is (= 2 (-> groups (nth 0) :records count)))
      (is (= [:counter/dec] (-> groups (nth 1) :event)))
      (is (= 1 (-> groups (nth 1) :records count))))))

(deftest group-by-event-preserves-insertion-order
  (testing "the first occurrence of each :event sets that group's position
            in the output, even if the records interleave later"
    (let [records [{:event [:a]} {:event [:b]} {:event [:a]} {:event [:c]}]
          groups  (rf.story.ui.assertion-strip/group-by-event records)]
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
          groups  (rf.story.ui.assertion-strip/group-by-event records)]
      (is (= 2 (count groups)))
      (is (nil? (-> groups (nth 0) :event))
          ":event nil cluster is its own group")
      (is (= 2 (-> groups (nth 0) :records count))
          "both nil-event records cluster together"))))

(deftest group-by-event-empty
  (testing "empty input → empty groups vector"
    (is (= [] (rf.story.ui.assertion-strip/group-by-event [])))
    (is (= [] (rf.story.ui.assertion-strip/group-by-event nil)))))

;; ---- pure: status-glyph map ----------------------------------------------

(deftest status-glyph-shape
  (testing "the status glyphs (Storybook-inspired pattern #1) are exposed
            publicly so tests + downstream consumers can pin them. The
            first three are the SHARED assertion vocabulary
            (`predicates/assertion-glyph`); `:error` is the strip's own
            extension over it — a run-level verdict the shared
            three-valued map deliberately does not carry."
    (is (= "✓" (:pass rf.story.ui.assertion-strip/status-glyph)))
    (is (= "✗" (:fail rf.story.ui.assertion-strip/status-glyph)))
    (is (= "⊘" (:skip rf.story.ui.assertion-strip/status-glyph)))
    (is (= "✖" (:error rf.story.ui.assertion-strip/status-glyph))
        ":error carries its OWN glyph so an errored row stays
         distinguishable from a failed one (spec/018 §12.6) — it shares
         the red band, and the glyph is what tells them apart, mirroring
         the test-mode pane's own precedent")))

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

(defn- find-string
  "Walk `hiccup` and return the first STRING node containing `s`, or nil.

  `find-prop` above finds prop MAPS only, so it cannot see a bare string
  child — which is exactly how the detail panel renders an error message
  (`(str (:message error))` sits as a direct child of the line div). This
  sibling walker covers that case."
  [hiccup s]
  (letfn [(walk [node]
            (cond
              (string? node)  (when (not= -1 (.indexOf node s)) node)
              (vector? node)  (some walk node)
              (seq? node)     (some walk node)
              :else           nil))]
    (walk hiccup)))

(deftest render-row-pass-shape
  (testing "a passing row carries the data-test stamps the chrome-level
            test widget reads + the spec-pinned status glyph"
    (let [row    {:status   :pass
                  :label    ":rf.assert/path-equals [[:counter] 1]"
                  :row-key  ":rf.assert/path-equals [[:counter] 1]"
                  :detail   {:expected 1 :actual 1}}
          hiccup (rf.story.ui.assertion-strip/render-row row false (fn [_]))]
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
          hiccup (rf.story.ui.assertion-strip/render-row row true (fn [_]))
          wrap   (find-prop hiccup :data-test "story-canvas-assertion-row")]
      (is (= "fail" (:data-status wrap)))
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-summary"))
          "inline summary span renders when status is :fail with a :reason")
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "open? true → the detail panel renders inline"))))

(deftest render-row-error-shape-summary-on-message
  (testing "an ERRORED row is not a failed row. It carries its own
            :data-status, surfaces the captured error's :message as the
            inline summary, and its expanded detail renders that message —
            the one sentence that says what went wrong. Before rf2-uky0n
            the strip had no :error arm at all, so this row painted as a
            grey `·` with no summary and a detail panel that never read
            :error"
    (let [row    {:status  :error
                  :label   ":rf.error/exception"
                  :row-key ":rf.error/exception"
                  :detail  {:event  [:your/setup-event {}]
                            :phase  :phase-2-events
                            :reason nil
                            :error  {:message "no handler registered for :your/setup-event"
                                     :stack   nil
                                     :data    nil}}}
          hiccup (rf.story.ui.assertion-strip/render-row row true (fn [_]))
          wrap   (find-prop hiccup :data-test "story-canvas-assertion-row")
          summ   (find-prop hiccup :data-test "story-canvas-assertion-summary")]
      (is (= "error" (:data-status wrap))
          ":data-status reads 'error', NOT 'fail' — the canvas surface
           tells the two apart")
      (is (some? summ)
          "the inline summary span renders — an errored row must never be
           a silent glyph with no text")
      (is (= "no handler registered for :your/setup-event" (:title summ))
          "the summary's :title carries the untruncated message")
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "open? true → the detail panel renders inline")
      (is (some? (find-string hiccup "no handler registered for :your/setup-event"))
          "the error message itself appears inside the rendered hiccup —
           this is the assertion the pre-fix renderer fails, because
           row-detail never destructured :error"))))

(defn- find-detail-value-element
  "Walk `hiccup` and return the first `[detail-value <label> <v>]` component
  VECTOR whose label is `label`, or nil.

  `detail-value` is a Reagent component, so `row-detail` emits it as an
  un-expanded component vector — its inner `:data-test` prop map does not
  exist until Reagent invokes it. `find-prop` therefore cannot see it; this
  walker matches on the component fn in head position instead."
  [hiccup label]
  (letfn [(match? [x]
            (and (vector? x)
                 (= rf.story.ui.assertion-strip/detail-value (first x))
                 (= label (second x))))
          (walk [node]
            (cond
              (match? node)  node
              (vector? node) (some walk node)
              (seq? node)    (some walk node)
              :else          nil))]
    (walk hiccup)))

(deftest render-row-error-detail-renders-error-data
  (testing "a captured error carrying :data gets a second detail line
            through `detail-value`, so a large map clamps rather than
            blowing the panel into the canvas height"
    (let [row    {:status  :error
                  :label   ":rf.error/exception"
                  :row-key ":rf.error/exception"
                  :detail  {:reason nil
                            :error  {:message "kaboom"
                                     :data    {:cause :network}}}}
          hiccup (rf.story.ui.assertion-strip/render-row row true (fn [_]))
          el     (find-detail-value-element hiccup "error data")]
      (is (some? (find-string hiccup "kaboom"))
          "the message line renders")
      (is (some? el)
          ":data routes through detail-value, which carries the clamping
           + click-to-reveal chord")
      (is (= {:cause :network} (nth el 2))
          "the error's :data map is what gets handed to detail-value")))
  (testing "CONTROL — an error with no :data adds no value line, and the
            walker itself is exercised in the negative direction"
    (let [row    {:status  :error
                  :label   ":rf.error/exception"
                  :row-key ":rf.error/exception"
                  :detail  {:reason nil :error {:message "kaboom" :data nil}}}
          hiccup (rf.story.ui.assertion-strip/render-row row true (fn [_]))]
      (is (some? (find-string hiccup "kaboom"))
          "the message line still renders — only the :data line is absent")
      (is (nil? (find-detail-value-element hiccup "error data"))
          "no error :data → no 'error data' value line"))))

(deftest render-row-skip-shape-no-detail-when-collapsed
  (testing "a skipped row stays collapsed by default — detail panel
            absent unless the caller passes open? true"
    (let [row    {:status  :skip
                  :label   ":rf.assert/skipped"
                  :row-key ":rf.assert/skipped"
                  :detail  {:reason "feature gated"}}
          hiccup (rf.story.ui.assertion-strip/render-row row false (fn [_]))]
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-summary"))
          ":skip rows surface the reason summary even when collapsed")
      (is (nil? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "detail panel suppressed when open? false"))))

;; ---- rendered: assertion-strip component ---------------------------------
;;
;; The component returns a reagent inner-fn (closure-with-state pattern).
;; Calling `(rf.story.ui.assertion-strip/assertion-strip assertions)` returns the outer fn; we
;; invoke it once with the assertions vector to get the inner fn, then
;; invoke that with the same vector to get the hiccup. This is the
;; standard reagent-with-init shape; tests pin the rendered tree without
;; standing up a React mount.

(defn- render-strip
  "Invoke the assertion-strip component and return its rendered hiccup.

  The strip renders each row as a Reagent component vector
  `[rf.story.ui.assertion-strip/render-row row open? toggle]` (the key MUST sit on a vector
  literal — rf2-5lw9w), so the raw hiccup carries un-expanded row
  elements. Tests that assert per-row `:key` meta read this raw form;
  tests that walk the row's inner shape use `render-strip-expanded`."
  [assertions]
  (let [inner (rf.story.ui.assertion-strip/assertion-strip assertions)]
    (inner assertions)))

(defn- expand-row-elements
  "Walk `hiccup` and replace each `[rf.story.ui.assertion-strip/render-row row open? toggle]`
  component vector with the hiccup that `render-row` produces — i.e. what
  Reagent expands the element into at mount time. Lets the shape-walking
  tests below assert the row's inner `data-test` tree even though the
  strip now emits component vectors (so React keys land on the element).
  Preserves all other nodes verbatim."
  [hiccup]
  (letfn [(render-row-element? [x]
            (and (vector? x)
                 (= rf.story.ui.assertion-strip/render-row (first x))))
          (expand [node]
            (cond
              (render-row-element? node) (apply rf.story.ui.assertion-strip/render-row (rest node))
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
                       :passed? false :reason "feature gated"}
                      ;; The captured setup-failure record, verbatim: NO
                      ;; :status key, :passed? false, an :error map. The
                      ;; projection reaches :error through its
                      ;; `(or (:error rec) (:exception rec))` arm.
                      {:assertion :rf.error/exception
                       :passed?   false
                       :event     [:your/setup-event {}]
                       :phase     :phase-2-events
                       :reason    nil
                       :error     {:message "no handler registered for :your/setup-event"
                                   :stack   nil
                                   :data    nil}}]
          hiccup     (render-strip-expanded assertions)
          rows       (atom [])]
      (letfn [(walk [node]
                (cond
                  (and (map? node) (= "story-canvas-assertion-row" (:data-test node)))
                  (swap! rows conj node)
                  (vector? node) (run! walk node)
                  (seq? node)    (run! walk node)))]
        (walk hiccup))
      (is (= 4 (count @rows)))
      (is (= #{"pass" "fail" "skip" "error"}
             (into #{} (map :data-status @rows)))
          "all four verdicts the projection can produce reach the strip as
           distinct :data-status values — an errored row is not a failed
           one"))))

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

(deftest assertion-strip-error-auto-expands
  (testing "pattern #2 extends to ERRORS — a variant whose :setup failed
            lands its captured :rf.error/exception record already-open, so
            the author reads what went wrong without a click.

            The fixture is the REAL captured record shape (rf2-uky0n): NO
            :status key, :passed? false, an :error map. That matters — with
            :status omitted the projection reaches its
            `(or (:error rec) (:exception rec))` arm, which is the arm real
            captured records take. A fixture that stamped :status would
            exercise a different arm and could pass while the real thing
            stayed broken"
    (let [assertions [{:assertion :rf.error/exception
                       :passed?   false
                       :event     [:your/setup-event {}]
                       :phase     :phase-2-events
                       :reason    nil
                       :error     {:message "no handler registered for :your/setup-event"
                                   :stack   nil
                                   :data    nil}}]
          hiccup     (render-strip-expanded assertions)
          wrap       (find-prop hiccup :data-test "story-canvas-assertion-row")]
      (is (= "error" (:data-status wrap))
          "the unstamped record projects to :error, not :fail")
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "the errored row's detail panel is open on first render — the
           seed-open set must include :error, not just :fail")
      (is (some? (find-string hiccup "no handler registered for :your/setup-event"))
          "and the message is actually rendered in it"))))

(deftest assertion-strip-error-stamped-status-reads-the-same
  (testing "CONTROL for the stamped form — a record carrying an explicit
            :status :error takes the projection's earlier `verdict/statuses`
            arm, and must render identically to the unstamped one above.
            Both arms, one rendering"
    (let [assertions [{:assertion :rf.error/exception
                       :status    :error
                       :passed?   false
                       :event     [:your/setup-event {}]
                       :phase     :phase-2-events
                       :error     {:message "no handler registered for :your/setup-event"}}]
          hiccup     (render-strip-expanded assertions)
          wrap       (find-prop hiccup :data-test "story-canvas-assertion-row")]
      (is (= "error" (:data-status wrap)))
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "stamped :error auto-expands too")
      (is (some? (find-string hiccup "no handler registered for :your/setup-event"))))))

(deftest assertion-strip-throwing-setup-reads-the-same
  (testing "CONTROL for the other producer — a setup handler that THROWS
            lands the same record shape with the throwable's message, and
            must read identically to the no-handler refusal"
    (let [assertions [{:assertion :rf.error/exception
                       :passed?   false
                       :event     [:your/setup-event {}]
                       :phase     :phase-0-setup
                       :reason    nil
                       :error     {:message "kaboom" :stack nil :data nil}}]
          hiccup     (render-strip-expanded assertions)
          wrap       (find-prop hiccup :data-test "story-canvas-assertion-row")]
      (is (= "error" (:data-status wrap)))
      (is (some? (find-prop hiccup :data-test "story-canvas-assertion-detail"))
          "a throwing setup auto-expands on the same path")
      (is (some? (find-string hiccup "kaboom"))
          "the throwable's message reaches the detail panel"))))

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
;; failing the Story/Xray feature-load browser gate all session. The fix
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
                            (= rf.story.ui.assertion-strip/render-row (first x))))]
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

;; ---- :key meta on the OUTER group seq (rf2-uhq5j / rf2-5lw9w-one-up) ------
;;
;; The strip nests two `for` seqs: outer groups → inner rows. The inner
;; row keys are pinned above. The OUTER group element's
;; `^{:key (str "group-" gi)}` (assertion_strip.cljs:468) was never
;; asserted — a missing/duplicate group key warns in React exactly like
;; the rf2-5lw9w row-key bug, one level up, and would slip past node-test
;; the same way. These tests pin `(meta element) :key` on every group
;; element so a regression there can't go silent.

(defn- collect-group-seq-elements
  "Walk `hiccup` and collect the outer group elements — the `[:div ...]`
  vectors the group `for` produces, one per dispatching :event cluster.
  Each carries `:data-test \"story-canvas-assertion-group\"` in its prop
  map. Returns them in encounter order so callers can assert per-element
  `:key` meta."
  [hiccup]
  (let [found (atom [])
        group-element? (fn [x]
                         (and (vector? x)
                              (= :div (first x))
                              (map? (second x))
                              (= "story-canvas-assertion-group"
                                 (:data-test (second x)))))]
    (letfn [(walk [node]
              (cond
                (group-element? node) (swap! found conj node)
                (vector? node)        (run! walk node)
                (seq? node)           (run! walk node)
                :else                 nil))]
      (walk hiccup))
    @found))

(deftest assertion-strip-group-seq-elements-carry-key-meta
  (testing "every outer group element carries a unique :key in its
            metadata so React's GROUP seq is keyed — the rf2-5lw9w
            failure mode one level up (the outer for over groups)"
    (let [assertions [{:assertion :rf.assert/path-equals :passed? true
                       :event [:click] :payload [[:c] 1]}
                      {:assertion :rf.assert/path-equals :passed? false
                       :event [:submit] :payload [[:c] 2] :reason "differ"}
                      {:assertion :rf.assert/path-equals :passed? true
                       :event [:reset] :payload [[:c] 3]}]
          hiccup     (render-strip assertions)
          groups     (collect-group-seq-elements hiccup)
          keys       (map #(:key (meta %)) groups)]
      (is (= 3 (count groups))
          "one group element per dispatching event")
      (is (every? vector? groups)
          "group elements are vector literals — the :key sits on the
           element React receives, not a call form")
      (is (every? some? keys)
          "every group element carries a :key in its metadata so React's
           group seq is keyed (no missing-key warning)")
      (is (= (count groups) (count (into #{} keys)))
          ":key values are unique across the group seq"))))

;; ---- pure: value-display -------------------------------------------------

(deftest value-display-short-no-clamp
  (testing "a short value's :clamped equals :full and :long? is false —
            no click-to-reveal chord needed"
    (let [out (rf.story.ui.assertion-strip/value-display 42)]
      (is (= "42" (:full out)))
      (is (= "42" (:clamped out)))
      (is (false? (:long? out)))))
  (testing "a moderate map under the cap also passes through unclamped"
    (let [out (rf.story.ui.assertion-strip/value-display {:a 1 :b 2})]
      (is (false? (:long? out)))
      (is (= (:full out) (:clamped out))))))

(deftest value-display-long-clamps
  (testing "a value whose pr-str exceeds the detail cap clamps with an
            ellipsis and flags :long? so the renderer attaches the
            click-to-reveal chord"
    (let [big {:k (apply str (repeat 300 "x"))}
          out (rf.story.ui.assertion-strip/value-display big)]
      (is (true? (:long? out)))
      (is (re-find #"…$" (:clamped out))
          ":clamped ends in an ellipsis")
      (is (< (count (:clamped out)) (count (:full out)))
          ":clamped is shorter than the full pr-str")
      (is (= (pr-str big) (:full out))
          ":full carries the complete pr-str for the revealed view"))))

(deftest value-display-respects-explicit-cap
  (testing "the 2-arity form clamps at the caller-supplied length"
    (let [out (rf.story.ui.assertion-strip/value-display "abcdefghij" 5)]
      (is (true? (:long? out)))
      (is (= 5 (count (:clamped out)))))))

;; ---- rendered: detail-value ----------------------------------------------
;;
;; detail-value is a reagent component (closure-with-state). Invoke the
;; outer fn to get the inner render fn, then invoke that to get hiccup —
;; same shape as render-strip above.

(defn- render-detail-value
  [label v]
  (let [inner (rf.story.ui.assertion-strip/detail-value label v)]
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
