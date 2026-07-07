(ns re-frame.story.ui.shell.rails-splitter-unmount-dom-cljs-test
  "DOM-mount regression for rf2-cmjly3 finding 6: the resizable rail
  splitter's `on-mouse-down` installs document-level mousemove/mouseup
  listeners that were previously removed ONLY from inside the mouseup
  handler itself. If the splitter unmounted mid-drag — e.g. a
  narrow-viewport flip drops `[rails/splitter :left]` from `shell.cljs`
  (`rails/narrow-viewport?`) — mouseup never fires, so the document
  listeners were never torn down and kept calling `set-width!` against the
  (by-then stale) component's closure forever: a permanent per-drag
  listener leak plus phantom rail-width writes driven by a component no
  longer on screen.

  ## The fix

  The drag's `move-fn`/`up-fn` closures are tracked in a component-level
  atom (`drag-handlers`); `:component-will-unmount` removes them from
  `js/document` if a drag is still in flight when the component goes away,
  in addition to `up-fn`'s own (unchanged) removal on a normal mouseup.

  ## Why this needs a REAL DOM mount

  The bug is a real `document.addEventListener` / `removeEventListener`
  side effect wired through Reagent's `:on-mouse-down` (React's synthetic
  event system) — a hiccup-level test never invokes real DOM event
  dispatch or the `:component-will-unmount` lifecycle hook. This test
  proves the teardown behaviourally: a `mousemove` dispatched WHILE a drag
  is live moves the rail; a `mousemove` dispatched AFTER a mid-drag
  unmount must be a no-op.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` build
  discovers it and drives real DOM events; `:node-test` also loads it
  (regex matches the suffix too) where the body self-gates on `(browser?)`
  and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.story.ui.shell.rails :as rails]
            [re-frame.story.ui.state :as state]))

;; ---- browser gate -----------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- make-mount-node! []
  (let [node (js/document.createElement "div")]
    (js/document.body.appendChild node)
    node))

(use-fixtures :each
  {:before (fn [] (when (browser?) (state/reset-shell-state!)))})

;; ---- the regression ---------------------------------------------------

(deftest splitter-unmount-mid-drag-tears-down-document-listeners
  (testing "rf2-cmjly3 finding 6: a mousemove dispatched AFTER the
            splitter unmounts mid-drag (no mouseup ever fired) is a no-op
            — :component-will-unmount removed the document-level listener
            the pre-fix code left dangling"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (react-dom/flushSync
            (fn [] (rdc/render root [rails/splitter :left])))
          (let [splitter-el (.querySelector mount-node "[data-test=\"story-left-rail-splitter\"]")
                width-0     (:left (rails/current-widths))]
            (is (some? splitter-el) "splitter element mounted")
            ;; Start the drag — installs the document mousemove/mouseup
            ;; listeners.
            (react-dom/flushSync
              (fn []
                (.dispatchEvent splitter-el
                  (js/MouseEvent. "mousedown" #js {:bubbles true :clientX 100}))))
            ;; A mousemove WHILE the drag is live moves the rail — proves
            ;; the listener is actually attached and driving `set-width!`.
            (react-dom/flushSync
              (fn []
                (.dispatchEvent js/document
                  (js/MouseEvent. "mousemove" #js {:bubbles true :clientX 150}))))
            (let [width-1 (:left (rails/current-widths))]
              (is (not= width-0 width-1)
                  "a live drag's mousemove moves the rail width")
              ;; Unmount MID-DRAG — no mouseup ever fired.
              (react-dom/flushSync (fn [] (.unmount root)))
              ;; A further mousemove after the mid-drag unmount must be a
              ;; no-op — the pre-fix bug kept the listener attached against
              ;; the (now stale) component closure and would have moved
              ;; the rail again here.
              (react-dom/flushSync
                (fn []
                  (.dispatchEvent js/document
                    (js/MouseEvent. "mousemove" #js {:bubbles true :clientX 400}))))
              (let [width-2 (:left (rails/current-widths))]
                (is (= width-1 width-2)
                    "rf2-cmjly3 finding 6: a mousemove AFTER a mid-drag
                     unmount does not move the rail — the document
                     listener was torn down by :component-will-unmount"))))
          (finally
            (try (.unmount root) (catch :default _ nil))))))))

(deftest splitter-normal-mouseup-still-tears-down-listeners
  (testing "rf2-cmjly3 finding 6 — no regression: the ORIGINAL teardown
            path (mouseup firing while the component is still mounted)
            still removes the document listeners — a further mousemove
            after mouseup is a no-op, same as before the fix"
    (if-not (browser?)
      (is true ":node-test — no DOM; :browser-test runs the real assertion")
      (let [mount-node (make-mount-node!)
            root       (rdc/create-root mount-node)]
        (try
          (react-dom/flushSync
            (fn [] (rdc/render root [rails/splitter :left])))
          (let [splitter-el (.querySelector mount-node "[data-test=\"story-left-rail-splitter\"]")]
            (react-dom/flushSync
              (fn []
                (.dispatchEvent splitter-el
                  (js/MouseEvent. "mousedown" #js {:bubbles true :clientX 100}))))
            (react-dom/flushSync
              (fn []
                (.dispatchEvent js/document
                  (js/MouseEvent. "mousemove" #js {:bubbles true :clientX 150}))))
            (let [width-after-move (:left (rails/current-widths))]
              ;; Normal end-of-drag.
              (react-dom/flushSync
                (fn []
                  (.dispatchEvent js/document
                    (js/MouseEvent. "mouseup" #js {:bubbles true}))))
              (react-dom/flushSync
                (fn []
                  (.dispatchEvent js/document
                    (js/MouseEvent. "mousemove" #js {:bubbles true :clientX 400}))))
              (is (= width-after-move (:left (rails/current-widths)))
                  "a mousemove after a normal mouseup is still a no-op")))
          (finally
            (try (.unmount root) (catch :default _ nil))))))))
