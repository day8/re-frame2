(ns re-frame.ui.g8.dev
  "G-8 — the real-browser controlled-input correctness matrix (07 §5; EP-0035
  widened G-8). S-5's evidence was jsdom-only; this fixture is driven by the
  run-ui-g8.cjs runner in BOTH real Chromium AND real WebKit, so the engine's
  own DOM event pipeline, controlled-input reconciliation, caret management, and
  paint scheduling — none of which jsdom fakes — are under test.

  The matrix runs through a REUSABLE EVENT-PREFIX COMPONENT (the readiness P0-2
  library arm): a library-shaped control that receives its application event
  vector through props and dispatches `(conj prefix payload)` via the `ui/event`
  vector-outcome door at a compiler-proven controlled site. Toy literal fixtures
  do NOT close G-8 (EP-0035); the matrix passes through the library control.

  Arms (each observed on the REAL engine):
    - pre-paint synchronous commit — the door writes app-db INSIDE the native
      `input` dispatch, before any microtask, await, or paint (rAF).
    - single synchronous revision advance — the dirty ViewCell advances exactly
      once per keystroke, so React never re-renders mid-keystroke (no tearing).
    - event ordering — queued native inputs deliver in order; app-db reflects
      the last; the controlled value round-trips.
    - caret restoration — a mid-string insertion keeps the caret at the
      insertion point through the controlled re-render (the door commits the
      matching value, so React resets nothing).
    - IME composition — a compositionstart/update/end sequence commits the
      composed value once at the real engine's composition boundary, caret kept.

  The TOOTH: a second control — the deliberate ASYNC-DOOR REGRESSION — is the
  SAME controlled input whose write is deferred to a `setTimeout(0)` (a
  `ui/handler` scheduling a `dispatch-fn`, forfeiting the sync door). Under it
  the pre-paint + revision arms MUST FAIL. The runner asserts the sync door
  passes AND the async door fails; an async door that accidentally passes turns
  the gate red (the matrix is not vacuous)."
  (:require [re-frame.core :as rf]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

;; ---------------------------------------------------------------------------
;; Registry — the app grammar the reusable control cannot know
;; ---------------------------------------------------------------------------

(defonce ^:private delivered (atom []))

(defn- register! []
  (reset! delivered [])
  (rf/reg-sub ::text (fn [db _] (:text db)))
  (rf/reg-sub ::async-text (fn [db _] (:async-text db)))
  (rf/reg-event
   ::set-text
   (fn [{:keys [db]} [_ value]]
     (swap! delivered conj [::set-text value])
     {:db (assoc db :text value)}))
  (rf/reg-event
   ::set-async
   (fn [{:keys [db]} [_ value]]
     (swap! delivered conj [::set-async value])
     {:db (assoc db :async-text value)})))

;; ---------------------------------------------------------------------------
;; The reusable event-prefix control (sync door) + the async-door regression
;; ---------------------------------------------------------------------------

(defview reusable-prefix-input [{:keys [on-change]}]
  ;; The library control: it receives an application event PREFIX through props
  ;; and appends the live payload at dispatch time, through the ui/event
  ;; vector-outcome door. The SITE proof is static (literal :value co-present on
  ;; :on-input); prefix + payload are runtime values.
  [:input {:data-role "sync"
           :value (ui/sub [::text])
           :on-input (ui/event [e] (conj on-change (.. e -target -value)))}])

(defview async-door-input []
  ;; The DELIBERATE async-door regression: a controlled input whose write is
  ;; deferred off the synchronous stack. `ui/handler` is imperative (its result
  ;; is ignored — it does NOT ride the vector-outcome sync door), and it
  ;; schedules the dispatch through a committed-frame dispatcher on a
  ;; setTimeout(0). So the app-db write lands AFTER the native dispatch returns,
  ;; and the pre-paint + revision arms observe a stale, un-advanced control.
  ;;
  ;; The compiler EXPECTEDLY warns `:rf.ui.compile/controlled-input-async-handler`
  ;; here — that warning is the compile-time confirmation that this control
  ;; genuinely forfeits the sync door, which is exactly the regression the tooth
  ;; needs. It is an intentional, evidence-bearing warning, not a defect.
  (let [d (ui/dispatch-fn)]
    [:input {:data-role "async"
             :value (ui/sub [::async-text])
             :on-input (ui/handler [e]
                         (let [v (.. e -target -value)]
                           (js/setTimeout #(d [::set-async v]) 0)))}]))

;; ---------------------------------------------------------------------------
;; In-browser observation helpers
;; ---------------------------------------------------------------------------

(defn- app-db [frame] (rf/app-db-value frame))

(defn- cell-observing [frame-id query-v]
  (let [target-key [:sub frame-id query-v]]
    (some #(when (contains? (reactive/committed-target-keys %) target-key) %)
          (reactive/current-live-cells))))

(defn- fire-input!
  "Set the DOM value (what a keystroke leaves behind) then dispatch a native
  `input` event through the real engine — exactly the committed-event spine's
  own delivery, no gesture abstraction."
  [el value]
  (set! (.-value el) value)
  (.dispatchEvent el (js/Event. "input" #js {:bubbles true :cancelable true})))

(defn- host-turn! []
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) 0))))

(defn- next-paint!
  "Resolve on the next real animation frame — the browser's paint boundary."
  []
  (js/Promise. (fn [resolve _] (js/requestAnimationFrame (fn [_] (resolve nil))))))

;; ---------------------------------------------------------------------------
;; The matrix — driven against the sync-door control
;; ---------------------------------------------------------------------------

(defn- run-sync-matrix! [frame frame-id root]
  (let [el   (uit/query root "[data-role='sync']")
        cell (cell-observing frame-id [::text])
        out  (atom {})]
    ;; Arm 1+2+5 — pre-paint synchronous commit, single revision advance, and
    ;; ordering, all observed SYNCHRONOUSLY inside the native dispatch stack.
    (let [before (reactive/revision cell)]
      (fire-input! el "abc")
      ;; SYNCHRONOUS reads — no await, no microtask, strictly before any paint.
      (swap! out assoc
             :pre-paint-committed (= "abc" (:text (app-db frame)))
             :revision-delta (- (reactive/revision cell) before)
             :pending-drained (zero? (reactive/pending-cell-count))))
    (-> (next-paint!)
        (.then
         (fn []
           ;; Arm 5 — ordering: three queued keystrokes deliver in order and the
           ;; committed value round-trips into the controlled DOM node.
           (reset! delivered [])
           (-> (uit/flush!
                (fn []
                  (fire-input! el "o1")
                  (fire-input! el "o2")
                  (fire-input! el "o3")
                  (host-turn!)))
               (.then
                (fn []
                  (swap! out assoc
                         :ordering (mapv second @delivered)
                         :committed-value (:text (app-db frame))
                         :dom-value (.-value el)))))))
        (.then
         (fn []
           ;; Arm 4 — caret restoration: insert 'X' at index 2 of "abXc"; the
           ;; door commits the matching value, so React resets nothing and the
           ;; real engine keeps the caret at the insertion point.
           (reset! delivered [])
           (set! (.-value el) "abXc")
           (.setSelectionRange el 3 3)
           (.dispatchEvent el (js/Event. "input" #js {:bubbles true}))
           (-> (uit/flush! host-turn!)
               (.then
                (fn []
                  (swap! out assoc
                         :caret-value (.-value el)
                         :caret-pos (.-selectionStart el)))))))
        (.then
         (fn []
           ;; Arm 3 — IME composition through the real engine: start, a
           ;; composing input carrying the composed value, then end. The door
           ;; commits the composed value once at the composition boundary and
           ;; the caret is preserved.
           (reset! delivered [])
           (let [before (reactive/revision cell)]
             (.dispatchEvent el (js/CompositionEvent. "compositionstart"
                                                      #js {:bubbles true :data ""}))
             (set! (.-value el) "が")
             (.dispatchEvent el (js/InputEvent. "input"
                                                #js {:bubbles true
                                                     :inputType "insertCompositionText"
                                                     :data "が"
                                                     :isComposing true}))
             (.dispatchEvent el (js/CompositionEvent. "compositionend"
                                                      #js {:bubbles true :data "が"}))
             (.setSelectionRange el 1 1)
             (-> (uit/flush! host-turn!)
                 (.then
                  (fn []
                    (swap! out assoc
                           :ime-committed (:text (app-db frame))
                           :ime-revision-delta (- (reactive/revision cell) before)
                           :ime-caret (.-selectionStart el)
                           :ime-dom (.-value el))))))))
        (.then (fn [] @out)))))

;; ---------------------------------------------------------------------------
;; The async-door tooth — the SAME keystroke against the deferred-write control
;; ---------------------------------------------------------------------------

(defn- run-async-tooth! [frame frame-id root]
  (let [el   (uit/query root "[data-role='async']")
        cell (cell-observing frame-id [::async-text])
        out  (atom {})]
    (let [before (reactive/revision cell)]
      (fire-input! el "abc")
      ;; SYNCHRONOUS reads — the async door has not written yet.
      (swap! out assoc
             :pre-paint-committed (= "abc" (:async-text (app-db frame)))
             :revision-delta (- (reactive/revision cell) before)))
    ;; Let the deferred write land so teardown is clean; the tooth is the
    ;; SYNCHRONOUS observation above, not the eventual value.
    (-> (uit/flush! host-turn!)
        (.then (fn [] (swap! out assoc :eventual (:async-text (app-db frame))) @out)))))

;; ---------------------------------------------------------------------------
;; Driver — mount both controls, run the matrix + tooth, publish the result
;; ---------------------------------------------------------------------------

(defn- execute! []
  (register!)
  (rf/init! ui/adapter)
  (let [f-sync  (rf/make-frame {:id ::sync
                                :initial-events [[:rf/set-db {:text "seed"}]]})
        f-async (rf/make-frame {:id ::async
                                :initial-events [[:rf/set-db {:async-text "seed"}]]})]
    (-> (uit/with-root [root [:div
                              [ui/frame-provider {:frame f-sync}
                               [reusable-prefix-input {:on-change [::set-text]}]]
                              [ui/frame-provider {:frame f-async}
                               [async-door-input]]]]
          (-> (host-turn!)
              (.then (fn [] (run-sync-matrix! f-sync ::sync root)))
              (.then (fn [sync-result]
                       (-> (run-async-tooth! f-async ::async root)
                           (.then (fn [async-result]
                                    {:gate "G-8"
                                     :status "pass"
                                     :user-agent (.-userAgent js/navigator)
                                     :sync sync-result
                                     :async async-result})))))))
        (.then (fn [result]
                 (rf/destroy-frame! f-sync)
                 (rf/destroy-frame! f-async)
                 result)
               (fn [e]
                 (rf/destroy-frame! f-sync)
                 (rf/destroy-frame! f-async)
                 (throw e))))))

(defn -main []
  (-> (execute!)
      (.then (fn [result]
               (unchecked-set js/globalThis "__RF2_G8_RESULT__" (clj->js result))))
      (.catch (fn [e]
                (unchecked-set js/globalThis "__RF2_G8_ERROR__"
                               (or (.-stack e) (str e)))))))
