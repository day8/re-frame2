(ns re-frame.story.ui.chrome-a11y-force-colors-dom-cljs-test
  "The 'Use system colors' opt-in in the Chrome A11y panel:
  `set-force-colors-opt-in!` writes the ratom AND stamps / clears
  `data-rf-force-colors=\"active\"` on the live `<html>`, and `motion-css`
  carries the selector block that attribute activates. `:browser-test` runs
  the attribute assertions against a real `<html>`; the node lane has no
  `document`, so there only the ratom half runs."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.theme.motion :as rf.story.theme.motion]
            [re-frame.story.ui.chrome-a11y :as rf.story.ui.chrome-a11y]))

;; ---- fixture: clear storage + reset bootstrap sentinel + clear attr ----

(defn- clear-storage! []
  (when (and (exists? js/globalThis) (.-localStorage js/globalThis))
    (try
      (.removeItem (.-localStorage js/globalThis)
                   rf.story.ui.chrome-a11y/force-colors-opt-in-key)
      (catch :default _ nil))))

(defn- reset-attribute! []
  ;; Clear any attribute left behind by a prior test so each test
  ;; observes a clean baseline. `rf.story.ui.chrome-a11y/apply-force-colors-
  ;; attribute!` with `false` removes the attribute; safe when
  ;; absent.
  (rf.story.ui.chrome-a11y/apply-force-colors-attribute! false))

(defn- reset-in-memory-state! []
  ;; The bootstrap sentinel + ratom live in `defonce` so they survive
  ;; across tests. Force a clean state by writing false, which also
  ;; sets the bootstrap flag so subsequent reads don't re-read storage.
  (rf.story.ui.chrome-a11y/set-force-colors-opt-in! false))

(use-fixtures :each
  {:before (fn []
             (clear-storage!)
             (reset-in-memory-state!)
             (reset-attribute!))
   :after  (fn []
             (clear-storage!)
             (reset-in-memory-state!)
             (reset-attribute!))})

(defn- html-root []
  (try
    (when-let [doc (.-document js/globalThis)]
      (.-documentElement doc))
    (catch :default _ nil)))

;; ---- set / clear --------------------------------------------------------

(deftest set-force-colors-opt-in-stamps-and-clears-the-html-attribute
  (testing "on: the ratom reads true AND `<html>` carries the active
            attribute, so the sibling selectors in `theme/motion.cljc` fire"
    (rf.story.ui.chrome-a11y/set-force-colors-opt-in! true)
    (is (true? (rf.story.ui.chrome-a11y/force-colors-opt-in?)))
    (when-let [html (html-root)]
      (is (= "active"
             (.getAttribute html rf.story.ui.chrome-a11y/force-colors-attribute)))))
  (testing "off: the ratom reads false AND the attribute is cleared, so the
            chrome reverts to author-encoded colours"
    (rf.story.ui.chrome-a11y/set-force-colors-opt-in! false)
    (is (false? (rf.story.ui.chrome-a11y/force-colors-opt-in?)))
    (when-let [html (html-root)]
      (is (nil? (.getAttribute html rf.story.ui.chrome-a11y/force-colors-attribute))))))

;; ---- motion-css carries the attribute-selector arm ---------------------

(deftest motion-css-declares-attribute-selector-rules
  (testing "`theme/motion.cljc/motion-css` carries a
            sibling block keyed on `[data-rf-force-colors=\"active\"]`
            so the operator opt-in activates the same system-token
            chrome the OS HCM media query paints."
    (is (re-find #"\[data-rf-force-colors=\"active\"\]" rf.story.theme.motion/motion-css)
        "attribute selector is present in motion-css")))
