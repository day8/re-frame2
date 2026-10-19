(ns day8.re-frame2-xray.palette.empty-row-frame-context-dom-cljs-test
  "Real-DOM witness that the palette's EMPTY-RESULTS row commits under
  the production provider shape.

  `view/palette-view` calls `(empty-row query)` with the already-bound
  query. Headed instead (`[empty-row]`), the row would be a plain `defn`
  in head position under the `palette/ModalView` boundary, which Fresco
  refuses with `:rf.error/fresco-bad-head`. Only a committed React render
  shows this: node rows drive `palette-view` as a pure fn and read its
  hiccup as data. The browser runner fails on any uncaught page error,
  and the refused subtree never commits, so the row below goes red
  either way.

  `:node-test` also loads `-dom-cljs-test` namespaces; there the row
  reports a skip."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.palette :as palette]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.shell :as shell]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; Not `:rf/xray`, the production singleton other suites share.
(def ^:private palette-frame ::palette)

;; `:ambient-frame nil` leaves no `with-frame` scope around the mount, so the
;; only frame the palette can resolve is the one `frame-provider` writes.
;; `make-xray-runtime-fixture` does not thread the key.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (xray-test-support/reset-all!))}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle
  "Resolves once the page's render pipelines have had a real chance to
  commit."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 20))))))))

(defn- mount!
  "Mount `body` under an outer `frame-provider`, as `mount.cljs` does: a
  boundary at a bare root resolves no frame and would fail for a
  different reason. Committed synchronously."
  [frame body]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame} body])))
    {:container container :root root}))

(defn- teardown! [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(deftest w1-empty-row-commits-under-a-provider
  (if-not (browser?)
    (is true "skipped: no DOM (node lane)")
    (async done
      (registry/register-xray-handlers!)
      ;; Several Xray registrations reach the production singleton by name.
      (rf/make-frame {:id shell/default-frame-id})
      (rf/make-frame {:id palette-frame})
      (rf/with-frame palette-frame
        (rf/dispatch-sync [:rf.xray/palette-open])
        ;; An empty query keeps every item, so type one that matches nothing.
        (rf/dispatch-sync [:rf.xray/palette-set-query "zzqqxxjjvvww"]))
      (let [{:keys [container root]} (mount! palette-frame [palette/Modal])]
        (-> (settle)
            (.then
              (fn [_]
                (is (some? (.querySelector container "[data-testid=\"rf-xray-palette-empty\"]"))
                    "the empty-results row committed")
                (teardown! root container)
                (done))))))))
