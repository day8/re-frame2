(ns re-frame.story.xray-preset-dom-cljs-test
  "The listener half of `wire-cross-host!`: it must REMOVE the keydown
  listener Xray's preload installed under the default-true posture, since
  flipping `:rf.xray/keybinding-enabled?` alone never detaches one.

  `keybinding/attach!` and `detach!` both return early without a
  `js/document`, so on `:node-test` nothing can attach and an
  `(false? (attached?))` reading would pass over a listener never
  installed. Only `:browser-test` (`-dom-cljs-test$`) has a real document.
  `:node-test`'s `cljs-test$` also loads this namespace, so the row is
  guarded by `(browser?)` and reports a STATED skip there rather than
  passing with zero assertions."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.keybinding :as xray-keybinding]
            [re-frame.story.xray-preset :as rf.story.xray-preset]))

(defn- browser?
  "True when a real `js/document` with an event-listener API is present."
  []
  (and (exists? js/document)
       (some? (.-addEventListener js/document))))

(def ^:private skip-msg
  "skipped: no DOM (node lane — see ns docstring)")

(deftest wire-cross-host-clears-attached-listener
  (testing "after a preload-style attach! under the default-true posture,
            wire-cross-host! leaves the slot false AND the listener gone"
    (if-not (browser?)
      (is true skip-msg)
      (do
        ;; `attach!` is a `compare-and-set!` from false, so an already-
        ;; attached listener would make it a silent no-op and the
        ;; precondition would pass on somebody else's listener.
        (xray-keybinding/detach!)
        (xray-config/set-keybinding-enabled! true)
        (try
          (xray-keybinding/attach!)
          (is (true? (xray-keybinding/attached?))
              "precondition: preload-style attach! installed the listener")
          (rf.story.xray-preset/wire-cross-host!)
          (is (false? (xray-config/keybinding-attach-enabled?))
              "wire-cross-host! flipped the slot to false")
          (is (false? (xray-keybinding/attached?))
              "wire-cross-host! removed the listener")
          (finally
            (xray-config/set-keybinding-enabled! true)
            (xray-keybinding/detach!)))))))
