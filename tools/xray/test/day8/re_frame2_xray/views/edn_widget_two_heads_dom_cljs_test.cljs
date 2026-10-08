(ns day8.re-frame2-xray.views.edn-widget-two-heads-dom-cljs-test
  "The facade's REAGENT head, read off a real React commit.

  `views/edn_widget.cljs`'s `inspect` emits `[ei/edn-inspector …]`, a
  Reagent component. Its one live caller, `static/routes/row_expand.cljs`'s
  `value-block`, CALLS it inside the `as-child` Reagent island
  `static/routes/panel.cljs` stands up, and the routes panel's own browser
  witness seeds no schema meta, so it never drags `edn-inspector` into that
  island. This row is that crossing's live evidence: were `inspect` moved
  onto the Fresco head, the mount would raise React's invalid-hook-call
  refusal and the widget would never commit.

  The ns ends in `-dom-cljs-test`, so it runs under `:browser-test` (real
  DOM + React via Chromium). The `:node-test` build loads it too, where the
  row reports a skip: the node lane INVOKES a fn head, so head legality is
  invisible to it.

  Every DOM accessor is `some->`-guarded: the browser lane runs in one
  `cljs.test/run-block` with no try/catch, so a bare accessor on an absent
  element aborts every later namespace."
  (:require [clojure.string :as str]
            [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.views.edn-widget :as edn]))

(def ^:private xray-frame
  "The frame the enclosing `frame-provider` names."
  :rf/xray)

(def ^:private node-key
  "The facade's per-mount qualifier, which `inspect` turns into the
  widget's `:panel-id` and so into its testid."
  "two-heads")

(def ^:private sentinel
  "A leaf string distinctive enough that finding it in `.textContent` is
  evidence the RENDERER ran, not merely that a container div committed."
  "two-heads-sentinel-value")

(def ^:private subject-value
  "Wide on purpose: the widget inlines any value whose `pr-str` fits the
  measured column, so a small value changes shape between the mount and the
  first settle. This one renders as a container at every width."
  {:sentinel sentinel
   :padding  (into {} (for [i (range 12)]
                        [(keyword (str "key-" i))
                         (str "a deliberately long value string number " i)]))})

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `cljs.test` refuses a FUNCTION fixture in a namespace carrying an
     ;; `async` row.
     :async?        true
     :init-fn       xray-test-support/reset-all!}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns but has no `js/document` to mount React into."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- settle
  "A promise resolving once the render pipeline has had a real chance to
  commit — two animation frames and a macrotask."
  []
  (js/Promise.
    (fn [resolve]
      (js/requestAnimationFrame
        (fn [_]
          (js/requestAnimationFrame
            (fn [_] (js/setTimeout resolve 30))))))))

(defn- setup!
  "Register Xray's handlers — which is what installs the widget's
  expansion / zoom / width subs and events — and make the frame the
  provider names."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id xray-frame})
  nil)

(defn- ReagentHost
  "`row_expand.cljs`'s shape, in miniature: an ordinary Reagent fn whose
  body CALLS the facade and splices the returned vector in as a child."
  []
  [:div (edn/inspect subject-value node-key)])

(defn- mount!
  "Commit `tree` under a `frame-provider` naming [[xray-frame]], as
  `mount.cljs` wraps the real shell. Inside `flushSync`, because
  `root.render` is otherwise async under React 19."
  [tree]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame xray-frame} tree])))
    {:container container :root root}))

(defn- teardown!
  "Unmount inside `flushSync` so React's cleanup effects have RUN before
  the next row mounts. A bare `.unmount` merely schedules them."
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- widget-text
  "The text of the widget's own root element. Its testid is
  `rf-xray-edn-inspector-<node-key>-<mount-id>`, and the Reagent head mints
  the mount-id per mount, so the match is on the prefix."
  [container]
  (some-> container
          (.querySelector (str "[data-testid^=\"rf-xray-edn-inspector-" node-key "-\"]"))
          (.-textContent)))

(deftest w1-reagent-head-paints-under-a-reagent-parent
  (testing "`edn-widget/inspect`, CALLED from an ordinary Reagent fn the
            way `static/routes/row_expand.cljs` calls it, commits the
            widget and renders the value"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane)")
      (async done
        (setup!)
        (let [{:keys [container root]} (mount! [ReagentHost])]
          (-> (settle)
              (.then
                (fn [_]
                  (is (some-> (widget-text container) (str/includes? sentinel))
                      "the widget committed and its RENDERER ran — the leaf value is in its text")
                  (teardown! root container)
                  (done)))))))))
