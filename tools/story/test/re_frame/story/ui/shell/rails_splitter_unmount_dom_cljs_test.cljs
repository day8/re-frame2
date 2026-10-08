(ns re-frame.story.ui.shell.rails-splitter-unmount-dom-cljs-test
  "DOM-mount test for the resizable rail splitter's drag lifecycle. Its
  `on-mouse-down` installs document-level mousemove/mouseup listeners. If
  the splitter unmounts mid-drag — e.g. a narrow-viewport flip drops
  `[rf.story.ui.shell.rails/splitter :left]` from `shell.cljs` — mouseup
  never fires, so listeners removed ONLY from inside the mouseup handler
  would keep writing rail widths from a component no longer on screen;
  `:component-will-unmount` removes them too.

  The listeners are real `document` side effects wired through React's
  synthetic `:on-mouse-down` and the unmount lifecycle, so only a real DOM
  mount observes them. Ns ends in `-dom-cljs-test` so shadow-cljs's
  `:browser-test` build drives real DOM events; `:node-test` also loads it,
  where the body self-gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.story.ui.shell.rails :as rf.story.ui.shell.rails]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

(def ^:private rail-storage-key "re-frame.story/rail-widths")

(defn- stored-widths []
  (some-> (.getItem js/localStorage rail-storage-key) js/JSON.parse (js->clj :keywordize-keys true)))

(deftest a-drag-release-persists-once-and-detaches-the-listeners
  (testing "a drag writes rail widths to shell state per mousemove but
            persists them to localStorage ONCE, on release — mouseup, or a
            mid-drag unmount that cuts the drag short — and after either
            release a further mousemove is a no-op"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (doseq [ending [:mouseup :unmount]]
        (.removeItem js/localStorage rail-storage-key)
        (rf.story.ui.state/reset-shell-state!)
        (let [mount-node (make-mount-node!)
              root       (rdc/create-root mount-node)]
          (try
            (react-dom/flushSync
              (fn [] (rdc/render root [rf.story.ui.shell.rails/splitter :left])))
            (let [splitter-el (.querySelector mount-node "[data-test=\"story-left-rail-splitter\"]")
                  move!       (fn [x]
                                (react-dom/flushSync
                                  (fn []
                                    (.dispatchEvent js/document
                                      (js/MouseEvent. "mousemove" #js {:bubbles true :clientX x})))))]
              (react-dom/flushSync
                (fn []
                  (.dispatchEvent splitter-el
                    (js/MouseEvent. "mousedown" #js {:bubbles true :clientX 100}))))
              (move! 130)
              (move! 160)
              (is (not= (:left rf.story.ui.shell.rails/default-widths)
                        (:left (rf.story.ui.shell.rails/current-widths)))
                  (str ending ": the drag moved the rail in shell state"))
              (is (nil? (stored-widths))
                  (str ending ": nothing persisted while the drag is live"))
              (if (= ending :mouseup)
                (react-dom/flushSync
                  (fn []
                    (.dispatchEvent js/document
                      (js/MouseEvent. "mouseup" #js {:bubbles true}))))
                (react-dom/flushSync (fn [] (.unmount root))))
              (is (= (rf.story.ui.shell.rails/current-widths) (stored-widths))
                  (str ending ": the widths the drag reached are persisted on release"))
              (move! 400)
              (is (= (stored-widths) (rf.story.ui.shell.rails/current-widths))
                  (str ending ": a mousemove after release does not move the rail — the listener is gone")))
            (finally
              (try (.unmount root) (catch :default _ nil)))))))))
