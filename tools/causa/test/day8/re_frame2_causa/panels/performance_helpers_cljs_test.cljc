(ns day8.re-frame2-causa.panels.performance-helpers-cljs-test
  "Pure-data tests for Causa's Performance panel helpers (Phase 5,
  rf2-75121).

  ## Why the `.cljc` + `_cljs_test` naming

  Same dual-target pattern as `issues_ribbon_helpers_cljs_test.cljc`:

    - Cognitect's test-runner (CLJ) picks it up via the default
      `.*-test$` regex on the ns name.
    - Shadow's `:node-test` build picks it up via the `cljs-test$`
      regex on the ns name.

  ## What's under test

    1. **classify-tier** — duration → perf tier per spec/007 §Colour
       scale (boundaries: 16ms, 50ms, 100ms).
    2. **tier-colour / -glyph / -label** — stable mappings.
    3. **over-budget?** — threshold-based classification.
    4. **slice-duration / cascade-duration** — :time delta math.
    5. **step-breakdown** — per-domino slice durations.
    6. **project-cascade / project-cascades** — row projection.
    7. **tier-counts / over-budget-count** — aggregates.
    8. **project-feed** — the composite the panel reads.
    9. **find-row** — selection lookup.
   10. **breakdown-segments** — view-side bar layout.
   11. **format-duration / format-event / truncate** — formatting."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [re-frame.trace.projection :as projection]
            [day8.re-frame2-causa.panels.performance-helpers :as h]))

;; ---- fixture builders ----------------------------------------------------

(defn- dispatched-ev
  "Build a `:event/dispatched` trace event for `dispatch-id`."
  [id time dispatch-id event-vec]
  {:id        id
   :op-type   :event
   :operation :event/dispatched
   :time      time
   :tags      {:dispatch-id dispatch-id
               :event       event-vec
               :origin      :app}})

(defn- handler-ev
  "Build a `:run-end` handler trace event."
  [id time dispatch-id]
  {:id id :time time :op-type :event :operation :event
   :tags {:dispatch-id dispatch-id :phase :run-end}})

(defn- fx-emit-ev
  "Build a `:event/do-fx` trace event — the third domino marker."
  [id time dispatch-id]
  {:id id :time time :op-type :event :operation :event/do-fx
   :tags {:dispatch-id dispatch-id}})

(defn- effect-ev
  [id time dispatch-id fx-id]
  {:id id :time time :op-type :fx :operation :rf.fx/handled
   :tags {:dispatch-id dispatch-id :fx-id fx-id}})

(defn- sub-ev
  [id time dispatch-id sub-id]
  {:id id :time time :op-type :sub/run :operation :sub/run
   :tags {:dispatch-id dispatch-id :sub-id sub-id}})

(defn- render-ev
  [id time dispatch-id render-key]
  {:id id :time time :op-type :view :operation :view/render
   :tags {:dispatch-id dispatch-id :render-key render-key}})

(defn- cascade-events
  "Build a representative one-cascade trace stream where each domino
  marker is offset by `+step-ms` from the previous one. Used to
  exercise the duration math against a known set of bookends."
  [dispatch-id event-vec base-time]
  [(dispatched-ev (* 10 dispatch-id) base-time dispatch-id event-vec)
   (handler-ev   (+ (* 10 dispatch-id) 1) (+ base-time 1)  dispatch-id)
   (fx-emit-ev   (+ (* 10 dispatch-id) 2) (+ base-time 3)  dispatch-id)
   (effect-ev    (+ (* 10 dispatch-id) 3) (+ base-time 5)  dispatch-id :db)
   (effect-ev    (+ (* 10 dispatch-id) 4) (+ base-time 7)  dispatch-id :dispatch)
   (sub-ev       (+ (* 10 dispatch-id) 5) (+ base-time 8)  dispatch-id :foo)
   (render-ev    (+ (* 10 dispatch-id) 6) (+ base-time 10) dispatch-id :app)
   (render-ev    (+ (* 10 dispatch-id) 7) (+ base-time 12) dispatch-id :header)])

;; ---- (1) tier classification --------------------------------------------

(deftest classify-tier-spec-boundaries
  (testing "fast < 16ms"
    (is (= :fast (h/classify-tier 0)))
    (is (= :fast (h/classify-tier 1)))
    (is (= :fast (h/classify-tier 15)))
    (is (= :fast (h/classify-tier 15.9))))
  (testing "medium = [16, 50)"
    (is (= :medium (h/classify-tier 16)))
    (is (= :medium (h/classify-tier 30)))
    (is (= :medium (h/classify-tier 49))))
  (testing "slow = [50, 100)"
    (is (= :slow (h/classify-tier 50)))
    (is (= :slow (h/classify-tier 75)))
    (is (= :slow (h/classify-tier 99))))
  (testing "blocking >= 100"
    (is (= :blocking (h/classify-tier 100)))
    (is (= :blocking (h/classify-tier 500)))
    (is (= :blocking (h/classify-tier 1000))))
  (testing "nil / negative / non-numeric collapse to :fast"
    (is (= :fast (h/classify-tier nil)))
    (is (= :fast (h/classify-tier -5)))
    (is (= :fast (h/classify-tier "not a number")))))

(deftest tier-colour-mirrors-spec
  (testing "perf-scale hex strings match tools/causa/spec/007-UX-IA.md §Colour system"
    (is (= "#4ADE80" (h/tier-colour :fast)))      ; green
    (is (= "#FBBF24" (h/tier-colour :medium)))    ; yellow
    (is (= "#FB923C" (h/tier-colour :slow)))      ; orange
    (is (= "#F87171" (h/tier-colour :blocking)))  ; red
    (is (= "#6B7080" (h/tier-colour :unknown))))) ; fallback

(deftest tier-glyph-pairs-shape-with-colour
  (testing "Colour is never alone (spec/007 §Colour is never alone)"
    ;; Within-budget tiers — calm dot
    (is (= "●" (h/tier-glyph :fast)))
    (is (= "●" (h/tier-glyph :medium)))
    ;; Over-budget tiers — attention triangle
    (is (= "▲" (h/tier-glyph :slow)))
    (is (= "▲" (h/tier-glyph :blocking)))))

(deftest tier-label-stable
  (is (= "fast"     (h/tier-label :fast)))
  (is (= "medium"   (h/tier-label :medium)))
  (is (= "slow"     (h/tier-label :slow)))
  (is (= "blocking" (h/tier-label :blocking))))

(deftest tier-order-is-fastest-first
  (is (= [:fast :medium :slow :blocking] h/tier-order)))

;; ---- (2) over-budget? ---------------------------------------------------

(deftest over-budget-honours-threshold
  (testing "duration at or above budget is over-budget"
    (is (true? (h/over-budget? 16 16)))
    (is (true? (h/over-budget? 16 17)))
    (is (true? (h/over-budget? 16 100))))
  (testing "duration below budget is within-budget"
    (is (false? (h/over-budget? 16 0)))
    (is (false? (h/over-budget? 16 15.9))))
  (testing "nil / non-number guards"
    (is (false? (h/over-budget? nil 100)))
    (is (false? (h/over-budget? 16 nil)))
    (is (false? (h/over-budget? "x" 50)))
    (is (false? (h/over-budget? 16 "x")))))

(deftest default-budget-is-one-frame-at-60fps
  (testing "16ms default budget per dispatch instructions"
    (is (= 16 h/default-budget-ms))))

;; ---- (3) slice / cascade duration ---------------------------------------

(deftest slice-duration-bookends
  (testing "max - min across the events"
    (is (= 5 (h/slice-duration [{:time 10} {:time 12} {:time 15}])))
    (is (= 0 (h/slice-duration [{:time 100}])))
    (is (nil? (h/slice-duration [])))
    (is (nil? (h/slice-duration [{:no-time-here true}])))))

(deftest cascade-duration-spans-every-event
  (testing "duration is max-time minus min-time across the whole cascade"
    (let [cascade {:dispatch-id 1
                   :handler  {:time 5}
                   :fx       {:time 8}
                   :effects  [{:time 10} {:time 12}]
                   :subs     [{:time 14}]
                   :renders  [{:time 18} {:time 20}]
                   :other    []}]
      (is (= 15 (h/cascade-duration cascade))))))

(deftest cascade-duration-nil-when-no-times
  (let [cascade {:dispatch-id 1
                 :handler nil
                 :fx nil
                 :effects []
                 :subs []
                 :renders []
                 :other []}]
    (is (nil? (h/cascade-duration cascade)))))

;; ---- (4) step-breakdown -------------------------------------------------

(deftest step-breakdown-derives-per-domino-slices
  (let [cascade {:dispatch-id 1
                 :handler  {:time 5}
                 :fx       {:time 8}
                 :effects  [{:time 10} {:time 14}]
                 :subs     [{:time 16} {:time 18}]
                 :renders  [{:time 20} {:time 26}]
                 :other    []}
        bd      (h/step-breakdown cascade)]
    (testing "handler = handler.time → fx.time"
      (is (= 3 (:handler bd))))
    (testing "fx = fx.time → max effects.time"
      (is (= 6 (:fx bd))))
    (testing "effects = bookend over effects"
      (is (= 4 (:effects bd))))
    (testing "subs / renders = bookend slices"
      (is (= 2 (:subs bd)))
      (is (= 6 (:renders bd))))))

(deftest step-breakdown-missing-slots-collapse-to-nil
  (let [cascade {:dispatch-id 1
                 :handler nil
                 :fx nil
                 :effects []
                 :subs []
                 :renders []
                 :other []}
        bd      (h/step-breakdown cascade)]
    (is (nil? (:handler bd)))
    (is (nil? (:fx bd)))
    (is (nil? (:effects bd)))
    (is (nil? (:subs bd)))
    (is (nil? (:renders bd)))))

;; ---- (5) project-cascade ------------------------------------------------

(deftest project-cascade-row-shape
  (let [stream  (cascade-events 1 [:counter/inc] 100)
        cascade (first (projection/group-cascades stream))
        row     (h/project-cascade 16 cascade)]
    (testing "every documented row slot is populated"
      (is (= 1 (:dispatch-id row)))
      (is (= [:counter/inc] (:event row)))
      ;; The cascade record's bookends come from the six-domino slots
      ;; (handler / fx / effects / subs / renders / other); the
      ;; :event/dispatched event itself only contributes the event
      ;; vector (per re-frame.trace.projection/absorb). The fixture's
      ;; handler lands at base+1, last render at base+12 → 11ms span.
      (is (= 11 (:duration-ms row)))
      (is (= :fast (:tier row)))         ; 11ms < 16ms
      (is (false? (:over-budget? row))) ; 11ms < 16ms budget
      (is (map? (:breakdown row)))
      (is (= 2 (:render-count row)))
      (is (= 2 (:effect-count row)))
      (is (= 1 (:sub-count row))))))

(deftest project-cascade-over-budget-flag
  (testing "duration >= budget marks over-budget"
    (let [;; Stretch the cascade so it crosses the 50ms boundary —
          ;; handler at +5, last render at +60 → 55ms span → :slow.
          stream  [(dispatched-ev 1 0   1 [:slow/op])
                   (handler-ev    2 5   1)
                   (fx-emit-ev    3 7   1)
                   (effect-ev     4 12  1 :db)
                   (render-ev     5 60  1 :app)]
          cascade (first (projection/group-cascades stream))
          row     (h/project-cascade 16 cascade)]
      (is (= 55 (:duration-ms row)))
      (is (= :slow (:tier row)))         ; 55ms → slow (50-100ms band)
      (is (true? (:over-budget? row))))))

(deftest project-cascade-event-fallback
  (testing "missing :event vector falls back to [:ungrouped]"
    (let [cascade {:dispatch-id 5
                   :event nil
                   :handler {:time 1}
                   :fx {:time 2}
                   :effects [] :subs [] :renders [] :other []}
          row     (h/project-cascade 16 cascade)]
      (is (= [:ungrouped] (:event row))))))

;; ---- (6) project-cascades ----------------------------------------------

(deftest project-cascades-drops-ungrouped
  (testing "free-floating events (cascade-id :ungrouped) don't surface"
    (let [;; Three cascades: 2 dispatched, 1 ungrouped (no :dispatch-id tag)
          stream (concat
                   (cascade-events 1 [:a] 0)
                   (cascade-events 2 [:b] 1000)
                   ;; A free-floating registry event without a dispatch-id
                   [{:id 999 :time 500 :op-type :warning
                     :operation :rf.warning/handler-replaced
                     :tags {}}])
          cascades (projection/group-cascades stream)
          rows     (h/project-cascades 16 cascades)]
      (is (= 2 (count rows)))
      (is (every? (fn [r] (not= :ungrouped (:dispatch-id r))) rows)))))

(deftest project-cascades-newest-first-not-required-here
  ;; project-cascades preserves group-cascades' oldest-first order;
  ;; project-feed flips to newest-first (the panel-facing slot).
  (let [stream (concat (cascade-events 1 [:a] 0)
                       (cascade-events 2 [:b] 1000))
        rows   (h/project-cascades 16 (projection/group-cascades stream))]
    (is (= [1 2] (mapv :dispatch-id rows)))))

;; ---- (7) tier-counts / over-budget-count -------------------------------

(deftest tier-counts-histogram
  (let [rows [{:tier :fast}      {:tier :fast}
              {:tier :medium}
              {:tier :slow}
              {:tier :blocking}  {:tier :blocking}]]
    (is (= {:fast 2 :medium 1 :slow 1 :blocking 2}
           (h/tier-counts rows)))))

(deftest tier-counts-zero-fill-missing
  (testing "missing tiers carry an explicit 0 so the chip-row is stable"
    (is (= {:fast 0 :medium 0 :slow 0 :blocking 0}
           (h/tier-counts [])))
    (is (= {:fast 1 :medium 0 :slow 0 :blocking 0}
           (h/tier-counts [{:tier :fast}])))))

(deftest over-budget-count-honours-flag
  (let [rows [{:over-budget? true}
              {:over-budget? false}
              {:over-budget? true}
              {:over-budget? nil}]]
    (is (= 2 (h/over-budget-count rows)))))

;; ---- (8) project-feed (composite the panel reads) ----------------------

(deftest project-feed-populates-every-slot
  (let [stream   (concat (cascade-events 1 [:a] 0)
                         (cascade-events 2 [:b] 1000))
        cascades (projection/group-cascades stream)
        feed     (h/project-feed cascades 16)]
    (testing "row-set"
      (is (= 2 (count (:rows feed))))
      (testing "rows are newest first per the bead's contract"
        (is (= [2 1] (mapv :dispatch-id (:rows feed))))))
    (testing "total / counts / budget"
      (is (= 2 (:total feed)))
      (is (= 4 (count (:tier-counts feed))))
      (is (= 0 (:over-budget-count feed)))
      (is (= 16 (:budget-ms feed)))
      (is (false? (:empty? feed))))))

(deftest project-feed-empty-state
  (let [feed (h/project-feed [] nil)]
    (is (true? (:empty? feed)))
    (is (= [] (:rows feed)))
    (is (= 0 (:total feed)))
    (is (= h/default-budget-ms (:budget-ms feed)))))

(deftest project-feed-budget-default
  (testing "nil :budget-ms falls back to default-budget-ms"
    (let [feed (h/project-feed [] nil)]
      (is (= h/default-budget-ms (:budget-ms feed))))
    (let [feed (h/project-feed [] 100)]
      (is (= 100 (:budget-ms feed))))))

(deftest project-feed-flags-over-budget
  (let [;; Cascade spans handler@10 → render@70 = 60ms → :slow
        stream   [(dispatched-ev 1 0  1 [:slow])
                  (handler-ev    2 10 1)
                  (fx-emit-ev    3 20 1)
                  (render-ev     4 70 1 :app)]
        cascades (projection/group-cascades stream)
        feed     (h/project-feed cascades 16)]
    (is (= 1 (:over-budget-count feed)))
    (is (true? (-> feed :rows first :over-budget?)))
    (is (= :slow (-> feed :rows first :tier)))))

;; ---- (9) find-row ------------------------------------------------------

(deftest find-row-lookup
  (let [rows [{:dispatch-id 1 :tier :fast}
              {:dispatch-id 2 :tier :medium}
              {:dispatch-id 3 :tier :blocking}]]
    (is (= :medium (:tier (h/find-row rows 2))))
    (is (nil? (h/find-row rows 99)))))

;; ---- (10) breakdown-segments (view-side) -------------------------------

(deftest breakdown-segments-shape
  (let [bd        {:handler 5 :fx 3 :effects 4 :subs 2 :renders 6}
        total     20
        segs      (h/breakdown-segments bd total)]
    (testing "every slice with a positive ms produces a segment"
      (is (= 5 (count segs)))
      (is (= [:handler :fx :effects :subs :renders]
             (mapv :key segs))))
    (testing "width-pct sums to 100 when slices span the full total"
      (is (= 100.0
             (reduce + (map :width-pct segs)))))))

(deftest breakdown-segments-drops-zero-and-nil
  (let [bd    {:handler nil :fx 0 :effects 5 :subs nil :renders 5}
        segs  (h/breakdown-segments bd 10)]
    (is (= 2 (count segs)))
    (is (= [:effects :renders] (mapv :key segs)))))

(deftest breakdown-segments-zero-or-nil-total
  (testing "non-positive / nil total → empty vector"
    (is (= [] (h/breakdown-segments {:handler 5} 0)))
    (is (= [] (h/breakdown-segments {:handler 5} nil)))
    (is (= [] (h/breakdown-segments {:handler 5} -1)))))

(deftest breakdown-colour-stable
  (is (= "#43C3D0" (h/breakdown-colour :handler)))
  (is (= "#43C3D0" (h/breakdown-colour :fx)))
  (is (= "#4ADE80" (h/breakdown-colour :effects)))
  (is (= "#43C3D0" (h/breakdown-colour :subs)))
  (is (= "#E879F9" (h/breakdown-colour :renders))))

;; ---- (11) formatting --------------------------------------------------

(deftest format-duration-stable
  (is (= "—"     (h/format-duration nil)))
  (is (= "—"     (h/format-duration "x")))
  (is (= "<1ms"  (h/format-duration 0)))
  (is (= "<1ms"  (h/format-duration 0.4)))
  (is (= "1ms"   (h/format-duration 1)))
  (is (= "16ms"  (h/format-duration 16)))
  (is (= "120ms" (h/format-duration 120.7))))

(deftest format-event-stable
  (is (= "[:counter/inc]"  (h/format-event [:counter/inc])))
  (is (= "[:ungrouped]"    (h/format-event [:ungrouped]))))

(deftest truncate-honours-cap
  (is (= "hello" (h/truncate "hello" 10)))
  (is (= "hell…" (h/truncate "hello world" 5)))
  ;; Truncating to 0 keeps just the ellipsis — the indicator that
  ;; *something* was elided rather than a silent empty string.
  (is (= "…"     (h/truncate "x" 0))))
