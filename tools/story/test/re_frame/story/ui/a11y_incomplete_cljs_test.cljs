(ns re-frame.story.ui.a11y-incomplete-cljs-test
  "rf2-0ae7o.2 — axe-core's INCOMPLETE results reach the a11y panel's state
  beside its violations, and are shown rather than failed.

  Before this, the scan's `.then` kept `(.-violations results)` and dropped
  `incomplete`, so a variant axe could not fully decide read '0 violation(s)
  found in variant' and 'no violations': a clean bill over checks nobody had
  looked at (the login-form testbed's `/idle` read 0 violations beside one
  incomplete `color-contrast` rule on 5 nodes).

  Same harness as `a11y-stale-settlement-cljs-test`: a fake axe on
  `js/window` makes `ensure-axe-loaded!` resolve at once, so the REAL
  `run-axe!` runs end to end with nothing routed around it.

  Pure `.cljs`: the panel is CLJS-only, and the `async` tests need cljs.test
  MAP fixtures, which a `.cljc` may not use
  (`re-frame.story.meta-fixtures-test`)."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.ui.a11y :as rf.story.ui.a11y]))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private saved-window (atom nil))

(defn- install-axe!
  "A fake axe-core whose `run` resolves to `results`."
  [results]
  (gobj/set (gobj/get js/globalThis "window") "axe"
            #js {"run" (fn [_] (js/Promise.resolve results))})
  nil)

(defn- setup! []
  (reset! saved-window (gobj/get js/globalThis "window"))
  (gobj/set js/globalThis "window" #js {})
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter) (catch :default _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf.story.ui.a11y/reset-state!)
  ;; `ensure-axe-loaded!` latches this once it has seen a global axe;
  ;; clear it so each test re-reads the fake installed for it.
  (reset! rf.story.ui.a11y/axe-loaded? false)
  (rf.story.ui.a11y/set-cdn-opt-in! true))

(defn- teardown! []
  (rf/unregister-listener! :trace ::a11y-warnings)
  (rf.story.ui.a11y/set-cdn-opt-in! false)
  (reset! rf.story.ui.a11y/axe-loaded? false)
  (rf.story.ui.a11y/reset-state!)
  (gobj/set js/globalThis "window" @saved-window))

(use-fixtures :each {:before setup! :after teardown!})

;; `nodeType`-bearing (so `run-axe!` treats it as the overlay scope) and
;; selector-inert (so the overlay decorator is a no-op).
(defn- ctx [] #js {:nodeType 1 "querySelector" (fn [_] nil)})

(defn- rule
  "An axe-core rule result for `id` on `n` nodes — the shape both
  `violations` and `incomplete` entries share."
  [id n]
  #js {:id     id
       :impact "serious"
       :help   (str "help for " id)
       :nodes  (apply array (repeat n #js {"target" #js ["#probe"]}))})

(defn- collect-a11y-warnings!
  "Count the `:rf.story.a11y/violation` warnings the panel emits — the
  trace events a play sequence's `:rf.assert/no-warnings` records."
  []
  (let [n (atom 0)]
    (rf/register-listener! :trace ::a11y-warnings
                           (fn [ev]
                             (when (= :rf.story.a11y/violation (:operation ev))
                               (swap! n inc))))
    n))

(def ^:private frame-id :story.a11y-incomplete/variant)

;; ---------------------------------------------------------------------------
;; The property
;; ---------------------------------------------------------------------------

(deftest an-incomplete-only-scan-is-not-a-clean-bill
  (testing "axe reports NO violations and one incomplete rule on 5 nodes —
            the login-form `/idle` shape. The panel's state must carry the
            incomplete rule and its line must name it, so zero violations
            is never reported alone"
    (async done
      (let [warnings (collect-a11y-warnings!)]
        (install-axe! #js {:violations #js []
                           :incomplete #js [(rule "color-contrast" 5)]})
        (-> (rf.story.ui.a11y/run-axe! frame-id (ctx))
            (.then
              (fn [_]
                (is (= :done (rf.story.ui.a11y/status-for frame-id))
                    "the scan finished")
                (is (= [] (get @rf.story.ui.a11y/violations-by-frame frame-id))
                    "the violations bag is unchanged in shape: an empty vector")
                (let [stored (get @rf.story.ui.a11y/incomplete-by-frame frame-id)]
                  (is (= ["color-contrast"] (mapv #(gobj/get % "id") stored))
                      "the incomplete rule reached the panel's state")
                  (is (= 5 (count (gobj/get (first stored) "nodes")))
                      "with its nodes, for a person to check"))
                (is (= "0 violation(s) found in variant, 1 incomplete"
                       (rf.story.ui.a11y/scan-summary frame-id))
                    "the panel's line names the incomplete count beside zero violations")
                (is (= 0 @warnings)
                    "an incomplete rule emits no warning, so :rf.assert/no-warnings stays green")
                nil))
            (.catch (fn [e] (is false (str "scan threw: " e)) nil))
            (.then (fn [_] (done))))))))

(deftest violations-still-warn-and-incomplete-rides-beside-them
  (testing "POSITIVE CONTROL for the warning count above, and the mixed
            shape: one violation and one incomplete rule. The violation
            still emits its warning — so the zero above is the incomplete
            rule's silence, not a listener that hears nothing"
    (async done
      (let [warnings (collect-a11y-warnings!)]
        (install-axe! #js {:violations #js [(rule "label" 1)]
                           :incomplete #js [(rule "color-contrast" 2)]})
        (-> (rf.story.ui.a11y/run-axe! frame-id (ctx))
            (.then
              (fn [_]
                (is (= ["label"] (mapv #(gobj/get % "id")
                                       (get @rf.story.ui.a11y/violations-by-frame frame-id))))
                (is (= ["color-contrast"] (mapv #(gobj/get % "id")
                                                (get @rf.story.ui.a11y/incomplete-by-frame frame-id))))
                (is (= "1 violation(s) found in variant, 1 incomplete"
                       (rf.story.ui.a11y/scan-summary frame-id)))
                (is (= 1 @warnings) "exactly one warning: the violation's")
                nil))
            (.catch (fn [e] (is false (str "scan threw: " e)) nil))
            (.then (fn [_] (done))))))))

(deftest a-result-without-an-incomplete-key-stores-an-empty-bag
  (testing "a results object carrying no `incomplete` array (older fakes,
            and any axe build that omits it) stores `[]` rather than
            throwing inside the settlement"
    (async done
      (install-axe! #js {:violations #js []})
      (-> (rf.story.ui.a11y/run-axe! frame-id (ctx))
          (.then
            (fn [_]
              (is (= :done (rf.story.ui.a11y/status-for frame-id)))
              (is (= [] (get @rf.story.ui.a11y/incomplete-by-frame frame-id)))
              (is (= "0 violation(s) found in variant, 0 incomplete"
                     (rf.story.ui.a11y/scan-summary frame-id)))
              nil))
          (.catch (fn [e] (is false (str "scan threw: " e)) nil))
          (.then (fn [_] (done)))))))

(deftest teardown-drops-the-incomplete-bag-too
  (testing "the incomplete bag holds raw axe objects that reference DOM
            nodes, exactly like the violations bag, so dropping a frame's
            state must drop both"
    (async done
      (install-axe! #js {:violations #js [] :incomplete #js [(rule "color-contrast" 1)]})
      (-> (rf.story.ui.a11y/run-axe! frame-id (ctx))
          (.then
            (fn [_]
              (is (contains? @rf.story.ui.a11y/incomplete-by-frame frame-id)
                  "precondition: the scan stored the incomplete bag")
              (rf.story.ui.a11y/drop-frame-state! frame-id)
              (is (not (contains? @rf.story.ui.a11y/incomplete-by-frame frame-id))
                  "drop-frame-state! evicts the incomplete bag")
              nil))
          (.catch (fn [e] (is false (str "scan threw: " e)) nil))
          (.then (fn [_] (done)))))))
