(ns day8.re-frame2-xray.static.shell-fresco-boundary-dom-cljs-test
  "Xray's Static SHELL read off a real React commit: the L3 tab-button
  React key reaches React.

  The key rides in each button's own ATTRIBUTE MAP rather than in
  `^{:key …}` reader metadata, which Fresco's codec reads nowhere. A lost
  key does not fail — it degrades into index-based reconciliation, which
  paints identically and corrupts identity only once the list changes
  shape — so only a head-removal identity row can see it.

  The shell's first display, liveness, frame targeting, evidence and
  teardown are the acceptance harness's subject
  (`acceptance.test-helpers.criteria`, run on three substrates).

  The mount crosses into a Reagent root through `rf.fresco/as-component`,
  under `rf/frame-provider`, which writes the same React context the
  shell's own `rf.fresco/frame-provider` does. `:ambient-frame nil` keeps
  an ambient frame from shadowing that context.

  The ns ends `-dom-cljs-test`, so the `:browser-test` build runs it; the
  `:node-test` build loads it too, where the row reports a skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panel-registry :as panel-registry]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.static.shell :as static-shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
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

(def ^:private surface-component
  "The React component `static-shell/surface` presents as. Declared once at
  top level, as `rf.fresco/as-component` requires — deriving it per render
  would mint a new component type and remount the surface."
  (rf.fresco/as-component static-shell/surface))

(defn- mount-shell!
  "Mount the Static surface under a `frame-provider` scoping `:rf/xray`,
  committed synchronously so the first assertions see it."
  []
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame :rf/xray}
                          [:> surface-component {}]])))
    {:container container :root root}))

(defn- teardown! [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- tab-node [container tab-id]
  (.querySelector container
                  (str "[data-testid=\"rf-xray-static-tab-" (name tab-id) "\"]")))

(defn- tab-nodes [container]
  (vec (.from js/Array
              (.querySelectorAll
                container
                "[data-testid^=\"rf-xray-static-tab-\"][role=\"tab\"]"))))

(defn- click!
  "Click a committed node, or fail the row — a raw `.click` on nil throws
  out of the `async` block, which `cljs.test/run-block` does not catch, and
  would end the whole browser lane with no summary."
  [node label]
  (if (some? node)
    (do (.click node) true)
    (do (is false (str "cannot click " label ": it is not in the committed DOM"))
        false)))

(deftest w5-tab-identity-survives-a-head-removal
  (testing "removing the HEAD of the tab list leaves the survivor as the SAME
            DOM node; under index-based reconciliation React would hand the
            survivor the head's node instead. The registry removal alone
            invalidates nothing, so a real tab selection drives the
            re-render."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (registry/register-xray-handlers!)
        (rf/make-frame {:id :rf/xray})
        (let [head-entry (panel-registry/tab-by-id :static :machines)
              {:keys [container root]} (mount-shell!)
              head       (tab-node container :machines)
              survivor   (tab-node container :routes)]
          (is (= 5 (count (tab-nodes container)))
              "PRECONDITION: the five Static tabs are on screen")
          (when (and (some? head) (some? survivor))
            ;; DOCUMENT_POSITION_FOLLOWING = 4: a removal from the tail would
            ;; leave the survivor untouched under either key spelling.
            (is (pos? (bit-and (.compareDocumentPosition head survivor) 4))
                "NON-VACUITY: the removed tab precedes the survivor"))
          (panel-registry/unreg-l4-tab! :machines)
          (click! survivor "the survivor tab button")
          (-> (rf.test-support/poll-until
                (fn [] (= 4 (count (tab-nodes container))))
                {:label "the head tab left the committed DOM"})
              (.then
                (fn [_]
                  (is (identical? survivor (tab-node container :routes))
                      "the survivor is the IDENTICAL DOM node React already had,
                       which holds only if the key reached React")))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       " — DOM: " (.-textContent container)))
                        nil))
              (.then (fn [_]
                       (when head-entry
                         (panel-registry/reg-l4-tab! head-entry))
                       (teardown! root container)
                       (done)))))))))
