(ns re-frame.story.ui.chrome-a11y-force-colors-cljs-test
  "CLJS smoke tests for the 'Use system colors' opt-in surface in the
  Chrome A11y panel.

  Coverage:

  - `set-force-colors-opt-in!` writes through to the in-memory ratom
    AND stamps / clears the `data-rf-force-colors=\"active\"`
    attribute on the live `<html>` (the chrome root is optional —
    when absent the cascade still reaches descendants via `<html>`).
    This namespace runs on the node lane, which has no `document`, so
    the attribute assertions sit behind `(when-let [html ...])` and the
    ratom half is what executes there.
  - `motion-css` carries the attribute-selector block the opt-in
    activates."
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

(deftest set-true-stamps-attribute-on-html-root
  (testing "`set-force-colors-opt-in!` writes through to
            the in-memory ratom AND stamps the attribute on `<html>`
            so the sibling selectors in `theme/motion.cljc` fire on
            the next paint."
    (rf.story.ui.chrome-a11y/set-force-colors-opt-in! true)
    (is (true? (rf.story.ui.chrome-a11y/force-colors-opt-in?))
        "ratom reflects the new value")
    (when-let [html (html-root)]
      (is (= "active"
             (.getAttribute html rf.story.ui.chrome-a11y/force-colors-attribute))
          "<html> carries the active attribute"))))

(deftest set-false-clears-attribute-on-html-root
  (testing "flipping the toggle off clears the attribute
            so the chrome reverts to author-encoded colours."
    (rf.story.ui.chrome-a11y/set-force-colors-opt-in! true)
    (rf.story.ui.chrome-a11y/set-force-colors-opt-in! false)
    (is (false? (rf.story.ui.chrome-a11y/force-colors-opt-in?))
        "ratom reflects the flipped value")
    (when-let [html (html-root)]
      (is (nil? (.getAttribute html rf.story.ui.chrome-a11y/force-colors-attribute))
          "<html> attribute cleared"))))

;; ---- motion-css carries the attribute-selector arm ---------------------

(deftest motion-css-declares-attribute-selector-rules
  (testing "`theme/motion.cljc/motion-css` carries a
            sibling block keyed on `[data-rf-force-colors=\"active\"]`
            so the operator opt-in activates the same system-token
            chrome the OS HCM media query paints."
    (let [css rf.story.theme.motion/motion-css]
      (is (string? css))
      (is (re-find #"\[data-rf-force-colors=\"active\"\]" css)
          "attribute selector is present in motion-css"))))
