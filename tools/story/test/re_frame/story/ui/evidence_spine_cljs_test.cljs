(ns re-frame.story.ui.evidence-spine-cljs-test
  "CLJS-side regression net for the evidence spine (rf2-ba86n.10,
  spec/020 §3 + spec/021 §2).

  Pairs with the host-free projection coverage in
  `re_frame/story/ui/evidence_spine_test.cljc`. This namespace pins the
  reachability + render wiring + focus side effect that need a CLJS runtime:

  - **render-no-variant / no-evidence / spine states** — the panel reads
    shell selection + the Test-mode result slot and renders the right
    surface for each;
  - **selection** — clicking a beat selects it; `row->beat-index` + the
    `select-beat!` / `open!` linkage drive the selected span (spec/021 §2);
  - **focus wiring** — the per-beat focus links render with the focus-panel
    vocabulary, fire `focus-beat!` → the real `day8.re-frame2-xray.core/focus!`
    (the rf2-crtmq host-facing entry), and the no-coords graceful path
    renders the 'why focus is unavailable' note while STILL offering the
    panel-only links (spec/020 §3).

  Per the Story testing posture (CLJS unit tests, not Playwright) the panel
  render is exercised by calling the form-2 component's inner render fn
  directly and walking the hiccup with `re-frame.test-helpers`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as frame]
            [re-frame.registrar        :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.story            :as story]
            [re-frame.test-helpers     :as th]
            [day8.re-frame2-xray.preload :as xray-preload]
            [day8.re-frame2-xray.registry :as xray-registry]
            [day8.re-frame2-xray.trace-collector :as xray-trace-collector]
            [re-frame.story.ui.evidence-spine :as spine]
            [re-frame.story.ui.state   :as state]
            [re-frame.story.ui.test-mode.state :as test-state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (story/clear-all!)
  (registrar/clear-all!)
  (reset! frame/frames {})
  (try (rf/init! plain-atom/adapter)
       (catch :default _ nil))
  (state/reset-shell-state!)
  (reset! test-state/results-atom {})
  (reset! spine/selection-atom {})
  (story/install-canonical-vocabulary!)
  (frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all! :after reset-all!})

;; ---- a representative run-result seeded into the Test-mode slot ----------

(def ^:private narrative
  ;; A two-level narrative: one settled dispatch beat (epoch 100, with a
  ;; db transition so focus is precise) plus a non-dispatch assert span
  ;; with no beats (the graceful no-coords case is exercised via a
  ;; coordinate-less synthetic beat below).
  [{:step [:dispatch [:counter/inc]]
    :epochs [{:epoch-id 100 :dispatch-id 100 :trigger-event [:counter/inc]
              :db-before {:count 0} :db-after {:count 1}
              :effects [{:fx-id :db}] :sub-runs [{:sub-id :count}] :renders []
              :trace-events []}]}
   {:step [:assert [:rf.assert/path-equals [:count] 1]]
    :epochs []}])

(defn- seed-result! [variant-id]
  (swap! test-state/results-atom assoc variant-id
         {:result {:status :pass :narrative narrative}}))

(defn- reg-counter! []
  (story/reg-story :story.evidence {:doc "Evidence probe"})
  (story/reg-variant :story.evidence/basic {:tags #{:test}}))

(defn- render-panel
  "Invoke the form-2 component's inner render fn. The panel reads
  `state/shell-state-atom` + the Test-mode result slot, so seed selection
  + the result first."
  []
  (let [render-fn (spine/evidence-spine-panel)]
    (render-fn)))

;; ===========================================================================
;; render states
;; ===========================================================================

(deftest render-no-variant-shows-empty-state
  (testing "with no focused variant the spine renders the quiet empty state"
    (let [tree (render-panel)]
      (is (some? (th/find-by-attr tree :data-test "story-evidence-no-variant")))
      (is (nil? (th/find-by-attr tree :data-test "story-evidence-spine"))))))

(deftest render-no-evidence-shows-honest-empty
  (testing "a focused variant with NO retained run renders the honest
            'no evidence yet' line, not a fabricated spine"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    (let [tree (render-panel)]
      (is (some? (th/find-by-attr tree :data-test "story-evidence-spine")))
      (is (some? (th/find-by-attr tree :data-test "story-evidence-empty")))
      (is (nil? (th/find-by-attr tree :data-test "story-evidence-spans"))))))

(deftest render-spine-shows-spans-beats-and-strength
  (testing "for a variant with a retained run the spine renders the spans,
            the decorated beats, evidence-strength tags, and summary chips"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    (let [tree   (render-panel)
          spans  (th/find-all-by-attr tree :data-test "story-evidence-span")
          beats  (th/find-all-by-attr tree :data-test "story-evidence-beat")
          str-tags (th/find-all-by-attr tree :data-test "story-evidence-strength")]
      (is (some? (th/find-by-attr tree :data-test "story-evidence-spans")))
      (is (= 2 (count spans)) "one span per script step")
      (is (= 1 (count beats)) "one committed beat")
      ;; the settled beat carries both direct + attributed (db + sub-run)
      (is (some #(= "direct" (get (second %) :data-strength)) str-tags))
      (is (some #(= "attributed" (get (second %) :data-strength)) str-tags))
      ;; the non-dispatch assert span renders the 'committed no epoch' marker
      (is (some? (th/find-by-attr tree :data-test "story-evidence-span-empty"))))))

;; ===========================================================================
;; selection (spec/021 §2 — result row drives the selected span)
;; ===========================================================================

(deftest select-beat-highlights-the-beat
  (testing "select-beat! marks the matching beat row data-selected=true"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    (spine/select-beat! :story.evidence/basic 0)
    (let [tree (render-panel)
          beat (th/find-by-attr tree :data-test "story-evidence-beat")]
      (is (= "true" (get (second beat) :data-selected))))))

;; ---- rf2-2b3ael: per-row data-selected highlight (multi-beat) ----------
;;
;; 015-Test-Coverage.md:186 owes the fuller `data-selected` DOM assertion
;; the retired trace-scrubber cascade rows carried: scrubbing to an epoch
;; sets `data-selected="true"` on the producing row while NON-selected rows
;; carry `data-selected="false"`. The scrubber panel is retired (Xray owns
;; the ribbon), but the evidence-spine `beat-row` is Story's live
;; selectable-row surface and carries exactly that `data-selected` attr,
;; driven by the pure `select-beat!` / `selected-beat-idx` state. The
;; single-beat `select-beat-highlights-the-beat` above proves the positive
;; half; a two-beat narrative is needed to prove the NEGATIVE half (siblings
;; are "false") and the round-trip (re-select moves the highlight).

(def ^:private two-beat-narrative
  ;; Two committed dispatch beats → flattened beat-idx 0 and 1 in tape
  ;; order (narrative-beats assigns a flat 0-based index across spans).
  [{:step [:dispatch [:counter/inc]]
    :epochs [{:epoch-id 100 :dispatch-id 100 :trigger-event [:counter/inc]
              :db-before {:count 0} :db-after {:count 1}
              :effects [{:fx-id :db}] :sub-runs [] :renders []
              :trace-events []}]}
   {:step [:dispatch [:counter/inc]]
    :epochs [{:epoch-id 101 :dispatch-id 101 :trigger-event [:counter/inc]
              :db-before {:count 1} :db-after {:count 2}
              :effects [{:fx-id :db}] :sub-runs [] :renders []
              :trace-events []}]}])

(defn- seed-two-beat! [variant-id]
  (swap! test-state/results-atom assoc variant-id
         {:result {:status :pass :narrative two-beat-narrative}}))

(deftest data-selected-true-on-producing-row-false-on-others
  (testing "scrubbing to a beat sets data-selected=true on that beat's row
            and data-selected=false on every other beat row (rf2-2b3ael —
            the producing-row highlight contract)"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    (seed-two-beat! :story.evidence/basic)
    ;; Select the FIRST beat (idx 0).
    (spine/select-beat! :story.evidence/basic 0)
    (let [tree  (render-panel)
          beats (th/find-all-by-attr tree :data-test "story-evidence-beat")
          sel   (mapv #(get (second %) :data-selected) beats)]
      (is (= 2 (count beats)) "both committed beats render as rows")
      (is (= ["true" "false"] sel)
          (str "the selected producing row is data-selected=true and the "
               "sibling is false; got " (pr-str sel))))))

(deftest data-selected-round-trip-moves-the-highlight
  (testing "re-selecting a different beat moves the data-selected=true marker
            to the newly-selected row (rf2-2b3ael — the scrub round-trip)"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    (seed-two-beat! :story.evidence/basic)
    ;; Now select the SECOND beat (idx 1) — the highlight must move.
    (spine/select-beat! :story.evidence/basic 1)
    (let [tree  (render-panel)
          beats (th/find-all-by-attr tree :data-test "story-evidence-beat")
          sel   (mapv #(get (second %) :data-selected) beats)]
      (is (= ["false" "true"] sel)
          (str "the highlight moved to the second row; got " (pr-str sel))))))

(deftest no-selection-leaves-every-row-unselected
  (testing "with no beat selected every row carries data-selected=false —
            the scrub-on-load default (no producing row highlighted)"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    (seed-two-beat! :story.evidence/basic)
    ;; selection-atom is reset by the fixture — no select-beat! call.
    (let [tree  (render-panel)
          beats (th/find-all-by-attr tree :data-test "story-evidence-beat")
          sel   (mapv #(get (second %) :data-selected) beats)]
      (is (= ["false" "false"] sel)
          (str "no row is highlighted before a scrub; got " (pr-str sel))))))

(deftest row-to-beat-index-then-select
  (testing "a result row resolved to a beat index drives the spine selection
            (the spec/021 §2 linkage end to end)"
    (let [idx (spine/row->beat-index narrative {:epoch-id 100})]
      (is (= 0 idx))
      (spine/select-beat! :story.evidence/basic idx)
      (is (= 0 (spine/selected-beat-idx :story.evidence/basic))))))

(deftest open-flips-visibility-and-selects-beat
  (testing "open! flips the :evidence panel-visibility slot on and
            pre-selects the supplied beat (the Test-mode evidence-row +
            command-palette entry point)"
    (state/swap-state! assoc-in [:panel-visibility spine/panel-key] false)
    (spine/open! :story.evidence/basic 0)
    (is (true? (get-in (state/get-state) [:panel-visibility spine/panel-key])))
    (is (= 0 (spine/selected-beat-idx :story.evidence/basic)))))

;; ===========================================================================
;; focus wiring (spec/020 §2.1 — links call the rf2-crtmq focus API)
;; ===========================================================================

(deftest focus-links-render-with-focus-vocabulary
  (testing "each beat renders 'open in Xray' focus links keyed to the
            host-facing focus-panel vocabulary"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    (let [tree  (render-panel)
          links (th/find-all-by-attr tree :data-test "story-evidence-focus-link")
          panels (into #{} (map #(get (second %) :data-panel)) links)]
      (is (seq links) "focus links present on the settled beat")
      (is (contains? panels "epoch"))
      (is (contains? panels "app-db"))
      (is (contains? panels "trace")))))

(deftest precise-beat-focus-row-is-marked-precise
  (testing "a beat with an epoch-id marks its focus row data-precise=true and
            shows NO 'unavailable' note"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    (let [tree (render-panel)
          row  (th/find-by-attr tree :data-test "story-evidence-focus-row")]
      (is (= "true" (get (second row) :data-precise)))
      (is (nil? (th/find-by-attr tree :data-test "story-evidence-focus-unavailable"))))))

(deftest no-coords-beat-shows-graceful-unavailable-note
  (testing "a beat with NO coordinates still renders the focus links (panel
            only) AND a note saying why precise focus is unavailable
            (spec/020 §3 graceful path)"
    (reg-counter!)
    (state/swap-state! state/select-variant :story.evidence/basic)
    ;; A run whose single beat carries no :epoch-id / :dispatch-id.
    (swap! test-state/results-atom assoc :story.evidence/basic
           {:result {:status :pass
                     :narrative [{:step [:dispatch [:x]]
                                  :epochs [{:db-after {:a 1} :trigger-event [:x]}]}]}})
    (let [tree (render-panel)
          row  (th/find-by-attr tree :data-test "story-evidence-focus-row")
          links (th/find-all-by-attr tree :data-test "story-evidence-focus-link")]
      (is (= "false" (get (second row) :data-precise)))
      (is (seq links) "panel-only links still offered")
      (is (some? (th/find-by-attr tree :data-test "story-evidence-focus-unavailable"))
          "the why-unavailable note renders"))))

(deftest focus-beat-drives-the-real-xray-focus-api
  (testing "focus-beat! builds a focus command and drives the real
            day8.re-frame2-xray.core/focus! host-facing entry (rf2-crtmq),
            returning the {:ok? true …} result with the applied dispatches +
            echoed Story provenance"
    ;; Stand up the Xray shell frame + handlers so focus! has a live target.
    (xray-preload/reset-for-test!)
    (xray-registry/reset-for-test!)
    (xray-trace-collector/reset-for-test!)
    (xray-registry/register-xray-handlers!)
    (frame/reg-frame :rf/xray {})
    (let [beat   {:epoch-id 100 :dispatch-id 100 :beat-idx 3 :span-idx 1}
          result (spine/focus-beat! :story.evidence/basic beat :app-db)]
      (is (map? result))
      (is (true? (:ok? result)) "the focus command applied")
      (is (vector? (:applied result)))
      (is (seq (:applied result)) "at least one :rf.xray/* event fired")
      ;; the opaque Story provenance round-trips back untouched
      (is (= :story/evidence-beat (:kind (:source result))))
      (is (= :story.evidence/basic (:variant/id (:source result))))
      (is (= 3 (:beat-idx (:source result)))))))
