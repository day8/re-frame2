(ns day8.re-frame2-xray.panels.shared.coord-affordances-cljs-test
  "Behavioural hiccup tests for the two shared click-to-source
  affordances — `coord-link` (label-as-link) and `coord-chip`
  (icon-only) — per rf2-3t7rs8 finding 3.

  ## What was unpinned

  `click_to_source_consolidation_test.clj` is a SOURCE-TEXT guard (it
  greps for bespoke dispatches). `open_in_editor_cljs_test.cljs` covers
  the reg-event-fx RECEIVER and the static `open-chip` anchor — NOT the
  rendering / click branches of these two shared helpers. So the
  load-bearing render contract was never exercised:

    - valid coord renders a clickable `<button>`
    - missing / `:file`-less coord degrades (link → plain `<span>`,
      chip → nil)
    - aria / title / testid shape on both branches
    - glyph / no-glyph ordering (`coord-link`'s `:glyph?` /
      `:glyph-leading?` knobs)
    - the `:on-click` stops event propagation
    - the `:on-click` dispatches `[:rf.xray/open-in-editor
      {:source-coord coord}]` through the captured `:dispatch-fn`

  ## Test seam

  Pure hiccup — these helpers return data, so we inspect the returned
  vector directly (no DOM mount). The click branch is driven by pulling
  `:on-click` off the rendered attrs and invoking it with a stub event
  (a JS object whose `stopPropagation` flips an atom) and a captured
  `:dispatch-fn` (an atom-collector) supplied via opts — both helpers
  expose `:dispatch-fn` as the rf2-r0o63 frame-aware override seam, so
  no real frame / dispatch bus is touched."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.panels.shared.coord-chip :as coord-chip]
            [day8.re-frame2-xray.panels.shared.coord-link :as coord-link]))

;; ---- stubs --------------------------------------------------------------

(defn- stub-event
  "A minimal DOM-event stand-in. `stopPropagation` flips the supplied
  atom so the test can assert the click guard fired."
  [stopped?]
  #js {:stopPropagation (fn [] (reset! stopped? true))})

(defn- capturing-dispatch
  "Returns `[dispatch-fn calls-atom]`. The fn captures every event
  vector it's handed (the rf2-r0o63 `:dispatch-fn` override seam)."
  []
  (let [calls (atom [])]
    [(fn [ev] (swap! calls conj ev)) calls]))

(def ^:private coord {:file "src/app/events.cljs" :line 17})

;; =========================================================================
;; coord-link — label-as-link
;; =========================================================================

(deftest coord-link-renders-clickable-button-for-valid-coord
  (testing "rf2-3t7rs8 — a valid coord renders a `<button>` carrying the
            testid, aria-label, and title (the `open <file>:<line> in
            editor` shape)"
    (let [[tree] [(coord-link/coord-link coord "open" "tid-link")]
          [tag attrs & _] tree]
      (is (= :button tag) "valid coord → button (clickable)")
      (is (= "tid-link" (:data-testid attrs)))
      (is (= "open src/app/events.cljs:17 in editor" (:aria-label attrs))
          "aria-label carries the file:line")
      (is (= "open src/app/events.cljs:17 in editor" (:title attrs))
          "title mirrors aria-label")
      (is (fn? (:on-click attrs)) "click handler present"))))

(deftest coord-link-degrades-to-span-when-coord-missing
  (testing "rf2-3t7rs8 — nil coord (or one without :file) degrades to a
            plain `<span>` carrying the SAME testid, with no click
            affordance — a missing coord is non-clickable label text,
            not a dead button"
    (doseq [bad [nil {} {:line 5} {:file ""}]]
      (let [[tag attrs & _] (coord-link/coord-link bad "open" "tid-link")]
        (is (= :span tag) (str "no usable :file → span for " (pr-str bad)))
        (is (= "tid-link" (:data-testid attrs))
            "fallback span keeps the testid so a test pins either branch")
        (is (nil? (:on-click attrs))
            "fallback span has no click handler")))))

(deftest coord-link-glyph-ordering
  (testing "rf2-3t7rs8 — the glyph knobs: default appends the glyph
            AFTER the label (`label ↗`); :glyph-leading? leads with it
            (`↗ label`); :glyph? false renders the label ALONE"
    ;; default: [label glyph]
    (let [tree (coord-link/coord-link coord "L" "tid")
          body (vec (drop 2 tree))]
      (is (= "L" (first body)) "default: label first")
      (is (vector? (second body)) "default: glyph (an svg hiccup) after label")
      (is (= 2 (count body)) "default body is exactly [label glyph]"))
    ;; glyph-leading?: [glyph label]
    (let [tree (coord-link/coord-link coord "L" "tid" {:glyph-leading? true})
          body (vec (drop 2 tree))]
      (is (vector? (first body)) "glyph-leading?: glyph first")
      (is (= "L" (second body)) "glyph-leading?: label after glyph"))
    ;; glyph? false: [label] only
    (let [tree (coord-link/coord-link coord "L" "tid" {:glyph? false})
          body (vec (drop 2 tree))]
      (is (= ["L"] body) "glyph? false: label alone, no glyph"))))

(deftest coord-link-click-stops-propagation-and-dispatches
  (testing "rf2-3t7rs8 — clicking the link stops event propagation (so an
            enclosing row handler doesn't also fire) and dispatches
            `[:rf.xray/open-in-editor {:source-coord coord}]` through the
            captured `:dispatch-fn`"
    (let [stopped?     (atom false)
          [disp calls] (capturing-dispatch)
          tree         (coord-link/coord-link coord "open" "tid"
                                              {:dispatch-fn disp})
          on-click     (:on-click (second tree))]
      (on-click (stub-event stopped?))
      (is (true? @stopped?) "stopPropagation fired on the click")
      (is (= [[:rf.xray/open-in-editor {:source-coord coord}]] @calls)
          "exactly one open-in-editor dispatch with the coord payload"))))

;; =========================================================================
;; coord-chip — icon-only
;; =========================================================================

(deftest coord-chip-renders-button-for-valid-coord
  (testing "rf2-3t7rs8 — a valid coord renders a focusable `<button>`
            with the canonical aria-label / title (`open in editor`) and
            the supplied testid"
    (let [tree (coord-chip/coord-chip coord "tid-chip")
          [tag attrs & _] tree]
      (is (= :button tag) "valid coord → button")
      (is (= "tid-chip" (:data-testid attrs)))
      (is (= "open in editor" (:aria-label attrs)))
      (is (= "open in editor" (:title attrs)))
      (is (fn? (:on-click attrs)) "click handler present"))))

(deftest coord-chip-returns-nil-when-coord-missing
  (testing "rf2-3t7rs8 — nil coord (or one without a usable :file)
            returns nil so call-sites can drop the chip cleanly (the
            chip has no plain-text fallback — that's the link's job)"
    (doseq [bad [nil {} {:line 5} {:file ""}]]
      (is (nil? (coord-chip/coord-chip bad "tid-chip"))
          (str "no usable :file → nil for " (pr-str bad))))))

(deftest coord-chip-applies-pixel-knob-overrides
  (testing "rf2-3t7rs8 — :color and :margin-left opts overlay the hoisted
            base style; defaults are inherit / 4px"
    (let [default-style (-> (coord-chip/coord-chip coord "tid") second :style)
          override-style (-> (coord-chip/coord-chip coord "tid"
                                                    {:color "red"
                                                     :margin-left "6px"})
                             second :style)]
      (is (= "inherit" (:color default-style)) "default colour inherits")
      (is (= "4px" (:margin-left default-style)) "default margin 4px")
      (is (= "red" (:color override-style)) "override colour applied")
      (is (= "6px" (:margin-left override-style)) "override margin applied"))))

(deftest coord-chip-click-stops-propagation-and-dispatches
  (testing "rf2-3t7rs8 — clicking the chip stops propagation and
            dispatches the open-in-editor event through the captured
            `:dispatch-fn`"
    (let [stopped?     (atom false)
          [disp calls] (capturing-dispatch)
          tree         (coord-chip/coord-chip coord "tid"
                                             {:dispatch-fn disp})
          on-click     (:on-click (second tree))]
      (on-click (stub-event stopped?))
      (is (true? @stopped?) "stopPropagation fired on the click")
      (is (= [[:rf.xray/open-in-editor {:source-coord coord}]] @calls)
          "exactly one open-in-editor dispatch with the coord payload"))))

;; =========================================================================
;; shared open-in-editor! action (coord-link's exported dispatch)
;; =========================================================================

(deftest open-in-editor!-stops-propagation-and-routes-through-dispatch-fn
  (testing "rf2-3t7rs8 — the shared `open-in-editor!` action (the ONE
            dispatch every affordance funnels through) stops propagation
            and routes the event through the supplied dispatch-fn"
    (let [stopped?     (atom false)
          [disp calls] (capturing-dispatch)]
      (coord-link/open-in-editor! coord (stub-event stopped?) disp)
      (is (true? @stopped?))
      (is (= [[:rf.xray/open-in-editor {:source-coord coord}]] @calls)))))

(deftest open-in-editor!-tolerates-nil-event
  (testing "rf2-3t7rs8 — `open-in-editor!` guards the `.stopPropagation`
            on event presence, so a nil event (a programmatic invoke
            with no DOM event) does not throw; the dispatch still fires"
    (let [[disp calls] (capturing-dispatch)]
      (coord-link/open-in-editor! coord nil disp)
      (is (= [[:rf.xray/open-in-editor {:source-coord coord}]] @calls)
          "dispatch still routes with a nil event"))))
