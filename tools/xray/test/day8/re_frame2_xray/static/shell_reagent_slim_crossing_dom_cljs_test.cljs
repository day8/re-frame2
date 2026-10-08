(ns day8.re-frame2-xray.static.shell-reagent-slim-crossing-dom-cljs-test
  "Xray's Fresco boundaries cross their Reagent islands through the
  INSTALLED ratom build, measured under **reagent-slim**, the adapter on
  which a crossing through any other build fails.

  The boundaries take the hiccup->React crossing as an `as-child`
  parameter and pass `substrate/as-element`, which reads the walk off the
  installed adapter. One passing `reagent.core/as-element` — stock
  Reagent's walk, named statically — would render the island under a
  build `:adapter/current-component` cannot see into, so every ambient
  `subscribe` / `dispatch` beneath raises `:rf.error/no-frame-context` in
  React's render phase and React takes the subtree down: a blank Xray,
  not a diagnostic.

  W1 reads the door itself and needs no DOM, so it runs in both lanes. W2
  is the paint witness, from a real commit under reagent-slim's own root,
  with `rf.fresco/error-boundary` above the ribbon so a render-phase raise
  reddens this row on its own message instead of failing the whole run as
  an uncaught `pageerror`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent2.core :as slim]
            [reagent2.dom.client :as slim-dom]
            [reagent.core :as stock]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent-slim/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture does not reset.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ===========================================================================
;; W1 — the crossing door is the INSTALLED build's walk
;; ===========================================================================

(deftest w1-as-element-door-is-the-installed-builds-walk
  (testing "with reagent-slim installed, `:adapter/as-element` walks hiccup
            the way reagent2 does and NOT the way stock Reagent does.

            Measured by EFFECT, not identity: the door is a routed-hook
            object, not the raw var. The discriminator is the component
            type React receives for a fn head, read through ONE `probe` fn
            for all three walks — each build caches its class on the fn
            under its own property, so the answers stay independent, where
            a fresh fn per build would make the negative trivially true.
            Both halves compare to literal vars, so they cannot degrade
            together."
    (let [door      (rf.late-bind/get-fn-cached :adapter/as-element)
          probe     (fn probe-view [] [:div {:data-testid "rf-slim-crossing-probe"}])
          ;; Stock runs FIRST on the same fn, so a collision on one cache
          ;; property would surface in the negative below.
          via-stock (stock/as-element [probe])
          via-door  (door [probe])
          via-slim  (slim/as-element [probe])]
      (is (identical? (.-type via-door) (.-type via-slim))
          "the door mints the component type REAGENT2 mints")
      (is (not (identical? (.-type via-door) (.-type via-stock)))
          "and not stock Reagent's — the defect itself"))))

;; ===========================================================================
;; W2 — the Static ribbon actually PAINTS under slim
;; ===========================================================================

(def ^:private caught
  "The error `rf.fresco/error-boundary` caught below it, or nil."
  (atom nil))

(rf.fresco/defview guarded-ribbon
  "The Static L1 ribbon under an error boundary, so a render-phase raise is
  HANDLED and reddens this row rather than aborting the lane."
  [_props]
  [rf.fresco/error-boundary
   {:on-error (fn [error] (reset! caught error))
    :fallback [:div {:data-testid "rf-slim-crossing-fallback"}]}
   [static-shell/ribbon {}]])

(def ^:private guarded-component
  "The React component [[guarded-ribbon]] presents as. Declared once at top
  level, as `rf.fresco/as-component` requires."
  (rf.fresco/as-component guarded-ribbon))

(defn- mount-ribbon!
  "Mount the guarded ribbon through REAGENT-SLIM's own client root, under a
  `frame-provider` scoping `:rf/xray`, committed synchronously."
  []
  (let [container (.createElement js/document "div")
        root      (slim-dom/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (slim-dom/render root [rf/frame-provider {:frame :rf/xray}
                               [:> guarded-component {}]])))
    {:container container :root root}))

(deftest w2-ribbon-reagent-island-paints-under-reagent-slim
  (testing "under reagent-slim the Static ribbon commits its frame picker.
            `frame-switcher-view` READS, so its picker exists only when the
            frame resolved beneath the ribbon boundary, and only if the
            error boundary above did not swap in its fallback"
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (reset! caught nil)
        (registry/register-xray-handlers!)
        (rf/make-frame {:id :rf/xray})
        (let [{:keys [container root]} (mount-ribbon!)]
          (is (some? (.querySelector container
                                     "[data-testid=\"rf-xray-ribbon-frame-picker\"]"))
              (str "the frame picker committed under reagent-slim. Caught: "
                   (pr-str @caught)))
          (react-dom/flushSync (fn [] (.unmount root)))
          (.remove container)
          (done))))))
