(ns re-frame.story.viewport-storage-dom-cljs-test
  "Viewport-selection persistence against a real `window.localStorage`:
  `save-to-storage!` writes through `.setItem`, `load-from-storage` reads
  back through `.getItem`, and `hydrate!` seeds the shell slot from what
  survived. The node runtime has no localStorage, so the rows run on
  `:browser-test` (`.*-dom-cljs-test$`).

  `:node-test`'s `cljs-test$` also selects this namespace, so each row
  answers the node lane with a visible marker assertion rather than a
  silent `when`, which would leave a hollow deftest there."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.story.viewport :as rf.story.viewport]
            [re-frame.story.ui.viewport-switcher
             :as rf.story.ui.viewport-switcher]
            [re-frame.story.ui.state :as rf.story.ui.state]))

(defn- browser?
  "True when a working `js/window.localStorage` is present: false under
  `:node-test`, true under `:browser-test`."
  []
  (and (exists? js/window) (.-localStorage js/window)))

(defn- clear-storage! []
  (when (browser?)
    (try (.removeItem (.-localStorage js/window) rf.story.viewport/ls-key)
         (catch :default _ nil))))

(def ^:private skip-msg
  "skipped: no localStorage (node lane — see ns docstring)")

(deftest storage-roundtrip-custom-and-save-nil-clears
  (if-not (browser?)
    (is true skip-msg)
    (do
      (clear-storage!)
      (rf.story.viewport/save-to-storage! {:width 800 :height 600})
      (is (= {:width 800 :height 600} (rf.story.viewport/load-from-storage)))
      (rf.story.viewport/save-to-storage! nil)
      (is (nil? (rf.story.viewport/load-from-storage)))
      (clear-storage!))))

(deftest hydrate-seeds-an-empty-slot-and-skips-a-populated-one
  (if-not (browser?)
    (is true skip-msg)
    (do
      (rf.story.viewport/save-to-storage! :tablet)
      (rf.story.ui.state/reset-shell-state!)
      (is (nil? (:viewport (rf.story.ui.state/get-state))))
      (rf.story.ui.viewport-switcher/hydrate!)
      (is (= :tablet (:viewport (rf.story.ui.state/get-state))))
      (rf.story.ui.state/swap-state! assoc :viewport :mobile-portrait)
      (rf.story.ui.viewport-switcher/hydrate!)
      (is (= :mobile-portrait (:viewport (rf.story.ui.state/get-state)))
          "a populated slot is left alone")
      (clear-storage!))))
