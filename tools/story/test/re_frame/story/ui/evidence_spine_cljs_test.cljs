(ns re-frame.story.ui.evidence-spine-cljs-test
  "CLJS coverage of the evidence spine (spec/020 §3 + spec/021 §2): the
  panel's render states, beat selection and its `data-selected` highlight,
  the focus links and `focus-beat!` → the real
  `day8.re-frame2-xray.core/focus!`, and the static-export focus boundary.
  The panel render is exercised by calling the form-2 component's inner
  render fn and walking the hiccup with `re-frame.test-helpers`; the pure
  projection is covered by `evidence_spine_test.cljc`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            [re-frame.frame            :as rf.frame]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.test-helpers     :as rf.test-helpers]
            [day8.re-frame2-xray.core :as xray-core]
            [day8.re-frame2-xray.preload :as xray-preload]
            [day8.re-frame2-xray.registry :as xray-registry]
            [day8.re-frame2-xray.trace-collector :as xray-trace-collector]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.ui.evidence-spine :as rf.story.ui.evidence-spine]
            [re-frame.story.ui.state   :as rf.story.ui.state]
            [re-frame.story.ui.test-mode.state :as rf.story.ui.test-mode.state]))

;; ---- fixtures ------------------------------------------------------------

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch :default _ nil))
  (rf.story.ui.state/reset-shell-state!)
  (reset! rf.story.ui.test-mode.state/results-atom {})
  (reset! rf.story.ui.evidence-spine/selection-atom {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

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
  (swap! rf.story.ui.test-mode.state/results-atom assoc variant-id
         {:result {:status :pass :narrative narrative}}))

(defn- reg-counter! []
  (rf.story/reg-story :story.evidence {:doc "Evidence probe"})
  (rf.story/reg-variant :story.evidence/basic {:tags #{:test}}))

(defn- render-panel
  "Invoke the form-2 component's inner render fn. The panel reads
  `rf.story.ui.state/shell-state-atom` + the Test-mode result slot, so seed selection
  + the result first."
  []
  (let [render-fn (rf.story.ui.evidence-spine/evidence-spine-panel)]
    (render-fn)))

(defn- stand-up-xray!
  "The Xray shell frame + handlers, so `focus!` has a live target."
  []
  (xray-preload/reset-for-test!)
  (xray-registry/reset-for-test!)
  (xray-trace-collector/reset-for-test!)
  (xray-registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ===========================================================================
;; render states
;; ===========================================================================

(deftest render-no-variant-shows-empty-state
  (testing "with no focused variant the spine renders the quiet empty state"
    (let [tree (render-panel)]
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-no-variant")))
      (is (nil? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-spine"))))))

(deftest render-no-evidence-shows-honest-empty
  (testing "a focused variant with NO retained run renders the honest
            'no evidence yet' line, not a fabricated spine"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (let [tree (render-panel)]
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-spine")))
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-empty")))
      (is (nil? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-spans"))))))

(deftest render-spine-shows-spans-beats-and-strength
  (testing "for a variant with a retained run the spine renders the spans,
            the decorated beats, evidence-strength tags, and summary chips"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    (let [tree   (render-panel)
          spans  (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-span")
          beats  (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-beat")
          str-tags (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-strength")]
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-spans")))
      (is (= 2 (count spans)) "one span per script step")
      (is (= 1 (count beats)) "one committed beat")
      ;; the settled beat carries both direct + attributed (db + sub-run)
      (is (some #(= "direct" (get (second %) :data-strength)) str-tags))
      (is (some #(= "attributed" (get (second %) :data-strength)) str-tags))
      ;; the beatless non-dispatch assert span renders its empty-span note
      (is (= (rf.story.ui.evidence-spine/empty-span-note :non-dispatch)
             (last (rf.test-helpers/find-by-attr tree :data-test "story-evidence-span-empty")))))))

;; ===========================================================================
;; selection (spec/021 §2 — result row drives the selected span)
;; ===========================================================================

;; ---- per-row data-selected highlight (multi-beat) -----------------------
;;
;; The evidence-spine `beat-row` is Story's selectable-row surface for
;; 015-Test-Coverage.md's cascade-row `data-selected` highlight: the
;; selected row reads "true", every sibling "false".

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
  (swap! rf.story.ui.test-mode.state/results-atom assoc variant-id
         {:result {:status :pass :narrative two-beat-narrative}}))

(deftest data-selected-true-on-producing-row-false-on-others
  (testing "scrubbing to a beat sets data-selected=true on that beat's row
            and data-selected=false on every other beat row (the
            producing-row highlight contract)"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-two-beat! :story.evidence/basic)
    ;; Select the FIRST beat (idx 0).
    (rf.story.ui.evidence-spine/select-beat! :story.evidence/basic 0)
    (let [tree  (render-panel)
          beats (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-beat")
          sel   (mapv #(get (second %) :data-selected) beats)]
      (is (= ["true" "false"] sel)
          (str "the selected producing row is data-selected=true and the "
               "sibling is false; got " (pr-str sel))))))

(deftest data-selected-round-trip-moves-the-highlight
  (testing "re-selecting a different beat moves the data-selected=true marker
            to the newly-selected row (the scrub round-trip)"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-two-beat! :story.evidence/basic)
    ;; Now select the SECOND beat (idx 1) — the highlight must move.
    (rf.story.ui.evidence-spine/select-beat! :story.evidence/basic 1)
    (let [tree  (render-panel)
          beats (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-beat")
          sel   (mapv #(get (second %) :data-selected) beats)]
      (is (= ["false" "true"] sel)
          (str "the highlight moved to the second row; got " (pr-str sel))))))

(deftest clicking-a-beat-row-selects-it
  (testing "a beat row's :on-click selects that beat"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-two-beat! :story.evidence/basic)
    (let [beats (rf.test-helpers/find-all-by-attr (render-panel) :data-test "story-evidence-beat")]
      ((:on-click (second (second beats))) nil)
      (is (= 1 (rf.story.ui.evidence-spine/selected-beat-idx :story.evidence/basic))))))

(deftest no-selection-leaves-every-row-unselected
  (testing "with no beat selected every row carries data-selected=false —
            the scrub-on-load default (no producing row highlighted)"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-two-beat! :story.evidence/basic)
    ;; selection-atom is reset by the fixture — no select-beat! call.
    (let [tree  (render-panel)
          beats (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-beat")
          sel   (mapv #(get (second %) :data-selected) beats)]
      (is (= ["false" "false"] sel)
          (str "no row is highlighted before a scrub; got " (pr-str sel))))))

(deftest open-flips-visibility-and-selects-beat
  (testing "open! flips the :evidence panel-visibility slot on and
            pre-selects the supplied beat (the Test-mode evidence-row +
            command-palette entry point)"
    (rf.story.ui.state/swap-state! assoc-in [:panel-visibility rf.story.ui.evidence-spine/panel-key] false)
    (rf.story.ui.evidence-spine/open! :story.evidence/basic 0)
    (is (true? (get-in (rf.story.ui.state/get-state) [:panel-visibility rf.story.ui.evidence-spine/panel-key])))
    (is (= 0 (rf.story.ui.evidence-spine/selected-beat-idx :story.evidence/basic)))))

;; ===========================================================================
;; focus wiring (spec/020 §2.1 — links call the Xray focus API)
;; ===========================================================================

(deftest clicking-a-focus-link-focuses-its-panel
  (testing "a focus link's :on-click stops the click reaching its beat row and
            fires the focus command at the variant for the link's panel"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    (let [link     (->> (rf.test-helpers/find-all-by-attr (render-panel) :data-test "story-evidence-focus-link")
                        (filter #(= "trace" (:data-panel (second %))))
                        first)
          stopped? (atom false)
          focused  (atom nil)]
      (with-redefs [xray-core/focus! (fn [& args] (reset! focused (first args)) {:ok? true})]
        ((:on-click (second link)) #js {:stopPropagation #(reset! stopped? true)}))
      (is (true? @stopped?) "the click does not also select the row")
      (is (= :story.evidence/basic @focused) "focus! was reached for the variant")
      (is (= :trace (:xray-panel (rf.story.ui.state/get-state)))
          "the embed follows the link's panel"))))

(deftest precise-beat-focus-row-is-marked-precise
  (testing "a beat with an epoch-id marks its focus row data-precise=true and
            shows NO 'unavailable' note"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    (let [tree (render-panel)
          row  (rf.test-helpers/find-by-attr tree :data-test "story-evidence-focus-row")]
      (is (= "true" (get (second row) :data-precise)))
      (is (nil? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-focus-unavailable"))))))

(deftest no-coords-beat-shows-graceful-unavailable-note
  (testing "a beat with NO coordinates still renders the focus links (panel
            only) AND a note saying why precise focus is unavailable
            (spec/020 §3 graceful path)"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    ;; A run whose single beat carries no :epoch-id / :dispatch-id.
    (swap! rf.story.ui.test-mode.state/results-atom assoc :story.evidence/basic
           {:result {:status :pass
                     :narrative [{:step [:dispatch [:x]]
                                  :epochs [{:db-after {:a 1} :trigger-event [:x]}]}]}})
    (let [tree (render-panel)
          row  (rf.test-helpers/find-by-attr tree :data-test "story-evidence-focus-row")
          links (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-focus-link")]
      (is (= "false" (get (second row) :data-precise)))
      (is (seq links) "panel-only links still offered")
      (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-focus-unavailable"))
          "the why-unavailable note renders"))))

(deftest focus-beat-drives-the-real-xray-focus-api
  (testing "focus-beat! builds a focus command and drives the real
            day8.re-frame2-xray.core/focus! host-facing entry,
            returning the {:ok? true …} result with the applied dispatches +
            echoed Story provenance"
    (stand-up-xray!)
    (let [beat   {:epoch-id 100 :dispatch-id 100 :beat-idx 3 :span-idx 1}
          result (rf.story.ui.evidence-spine/focus-beat! :story.evidence/basic beat :app-db)]
      (is (true? (:ok? result)) "the focus command applied")
      (is (vector? (:applied result)))
      (is (seq (:applied result)) "at least one :rf.xray/* event fired")
      ;; the opaque Story provenance round-trips back untouched
      (is (= :story/evidence-beat (:kind (:source result))))
      (is (= :story.evidence/basic (:variant/id (:source result))))
      (is (= 3 (:beat-idx (:source result)))))))

(deftest focus-beat-scrolls-the-rail-to-the-xray-band
  (testing "an 'Xray: …' link scrolls the rail to the Xray band, which sits
            above the Evidence section the link lives in, so the switched
            embed comes into view rather than changing out of sight"
    (stand-up-xray!)
    (let [scrolls (atom 0)
          beat    {:epoch-id 100 :dispatch-id 100 :beat-idx 0 :span-idx 0}]
      (with-redefs [rf.story.ui.evidence-spine/scroll-rail-to-xray! #(swap! scrolls inc)]
        (rf.story.ui.evidence-spine/focus-beat! :story.evidence/basic beat :app-db)
        (is (= 1 @scrolls) "one link press, one scroll to the Xray band")
        (with-redefs [rf.story.config/static-mode? true]
          (rf.story.ui.evidence-spine/focus-beat! :story.evidence/basic beat :app-db)
          (is (= 1 @scrolls) "static export: no Xray band, so no scroll"))))))

;; ===========================================================================
;; STATIC EXPORT — the evidence focus boundary
;; ===========================================================================
;;
;; A published Story static export ships NO Xray, by design:
;; `:devtools/preloads` is a `watch`/`compile` slot that `release` ignores,
;; so nothing registers Xray's instruction set, and its `reg-view` symbols
;; are undefined under `:advanced`.
;;
;; The evidence NARRATIVE is the publishable half and must survive intact.
;; The focus affordances and the focus CALLBACK must not, because both
;; target a surface that is not in the bundle. Rendering three live Xray
;; buttons per beat in a static export, or gating `focus-beat!` on
;; `enabled?` alone, would send the callback into Xray's dispatch path
;; with no mounted destination.
;;
;; EVERY test below carries its DEV control in the same block, and the
;; control runs FIRST. The characteristic failure of a boundary like this
;; is not a missing guard but an over-broad one that silently disables the
;; feature in dev too — a static-only assertion cannot see that, and would
;; pass just as happily against a `focus-available?` hard-wired to false.

(deftest static-export-evidence-keeps-its-narrative
  (testing "a RETAINED Test result still renders its spans,
            beats, strength tags and summary chips under static-mode?. Only
            the active affordances that target an unavailable surface go,
            NOT the evidence section."
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    (with-redefs [rf.story.config/static-mode? true]
      (let [tree (render-panel)]
        (is (some? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-spans"))
            "the spine still renders")
        (is (= 2 (count (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-span")))
            "one span per script step, exactly as in dev")
        (is (= 1 (count (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-beat")))
            "the committed beat survives")
        (is (seq (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-strength"))
            "evidence-strength tags survive")
        (is (seq (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-summary-chip"))
            "summary chips survive")))))

(deftest static-export-evidence-offers-no-active-xray-action
  (testing "the per-beat focus row is OMITTED under static-mode?
            and present in dev"
    (reg-counter!)
    (rf.story.ui.state/swap-state! rf.story.ui.state/select-variant :story.evidence/basic)
    (seed-result! :story.evidence/basic)
    ;; DEV CONTROL FIRST — a guard that broke dev would pass a
    ;; static-only assertion without a murmur.
    (let [dev-tree (render-panel)]
      (is (some? (rf.test-helpers/find-by-attr dev-tree :data-test "story-evidence-focus-row"))
          "dev control: the focus row renders")
      (is (= 3 (count (rf.test-helpers/find-all-by-attr dev-tree :data-test "story-evidence-focus-link")))
          "dev control: three focus links (Epoch / App-db / Trace) on the beat"))
    (with-redefs [rf.story.config/static-mode? true]
      (let [tree (render-panel)]
        (is (nil? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-focus-row"))
            "static: the whole focus row is omitted")
        (is (empty? (rf.test-helpers/find-all-by-attr tree :data-test "story-evidence-focus-link"))
            "static: NO active action targeting the absent Xray")
        (is (nil? (rf.test-helpers/find-by-attr tree :data-test "story-evidence-focus-unavailable"))
            "static: and no orphan 'why focus is unavailable' note left behind")))))

(deftest static-export-focus-callback-cannot-dispatch-into-xray
  (testing "focus-beat! refuses to dispatch under static-mode?. The
            callback is guarded as well as the affordance because
            `re-frame.story.ui.docs/excerpt-beat-row` reaches focus-beat!
            directly — an affordance-only guard would leave that path live."
    (let [beat {:epoch-id 100 :dispatch-id 100 :beat-idx 3 :span-idx 1}]
      ;; A BOUNDARY SPY, both ways — `nil` alone would not distinguish a
      ;; guard that fired from a focus! that returned nothing.
      (let [called? (atom false)]
        (with-redefs [xray-core/focus! (fn [& _] (reset! called? true) {:ok? true})]
          (rf.story.ui.evidence-spine/focus-beat! :story.evidence/basic beat :app-db)
          (is (true? @called?)
              "dev control: the spy is live — focus! IS reached in dev")))
      (let [called? (atom false)]
        (with-redefs [rf.story.config/static-mode? true
                      xray-core/focus! (fn [& _] (reset! called? true) {:ok? true})]
          (is (nil? (rf.story.ui.evidence-spine/focus-beat! :story.evidence/basic beat :app-db))
              "static: focus-beat! returns nil")
          (is (false? @called?)
              "static: day8.re-frame2-xray.core/focus! was never reached"))))))
