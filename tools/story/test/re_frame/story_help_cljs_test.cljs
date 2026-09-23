(ns re-frame.story-help-cljs-test
  "CLJS smoke tests for rf2-381i — first-time-user help overlay.

  Covers:

  - `seen?` degrades to false when localStorage is absent.
  - `help-content` renders as hiccup.
  - `open!` / `close!` toggle the local open atom.

  The localStorage round-trip is NOT here. It used to be, guarded by a
  `browser?` predicate — but this namespace ends `-cljs-test`, which
  `:browser-test` never loads, and the node lane has no
  `window.localStorage`, so those rows ran in neither lane. They now live
  in `re-frame.story-help-dom-cljs-test`, which BOTH lanes load
  (rf2-r51p)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.story.ui.help :as rf.story.ui.help]
            [re-frame.story.ui.keybindings :as rf.story.ui.keybindings]))

;; ---- fixtures ------------------------------------------------------------

(defn clear-flag! []
  (rf.story.ui.help/reset-seen!)
  (reset! @#'rf.story.ui.help/open? false))

(use-fixtures :each {:before clear-flag! :after clear-flag!})

;; ---- localStorage round-trip ---------------------------------------------

(deftest seen-defaults-to-false
  (testing "seen? is false when localStorage has never been touched"
    (is (false? (rf.story.ui.help/seen?)))))

;; `mark-seen-persists` MOVED to `re-frame.story-help-dom-cljs-test`
;; (rf2-r51p). It was guarded by `(when (browser?) ...)` here, and this
;; namespace ends `-cljs-test`, so `:browser-test` never loaded it while
;; `:node-test` — which has no `window.localStorage` — skipped the body:
;; it executed in neither lane. `seen-defaults-to-false` above STAYS,
;; because it asserts the no-storage degradation path and the node lane
;; is exactly where that belongs.

;; ---- hiccup shape --------------------------------------------------------

(defn- shortcuts-table
  "The `story-help-shortcuts-table` list inside `help-content`'s hiccup."
  []
  (->> (tree-seq sequential? seq (rf.story.ui.help/help-content))
       (filter #(and (vector? %)
                     (= :ul (first %))
                     (= "story-help-shortcuts-table" (:data-test (second %)))))
       first))

(defn- table-strings
  "Every string anywhere inside the shortcuts table."
  []
  (set (filter string? (tree-seq sequential? seq (shortcuts-table)))))

;; rf2-nxbdw — spec 014 §Keyboard shortcuts, API.md and `shortcut-keys`'
;; own docstring all say the overlay's table is READ from the hotkey
;; registry. It used to hard-code the four letters, so a fifth binding
;; would never have reached the cheat-sheet.

(deftest shortcuts-table-renders-every-registered-key
  (testing "each key the registry binds appears in the help table"
    (is (some? (shortcuts-table)) "control: the table is found")
    (doseq [k (rf.story.ui.keybindings/shortcut-keys)]
      (is (contains? (table-strings) k)
          (str "registered key " (pr-str k) " is in the help table")))))

(deftest shortcuts-table-follows-the-registry
  (testing "a key added to the registry appears in the table with no help.cljs edit"
    (is (not (contains? (table-strings) "z")) "control: no z row today")
    (with-redefs [rf.story.ui.keybindings/shortcut-keys
                  (fn [] ["a" "f" "s" "t" "z"])]
      (is (contains? (table-strings) "z")
          "the table was built from shortcut-keys"))))

(deftest help-content-is-hiccup
  (testing "help-content returns a hiccup vector rooted at :div"
    (let [out (rf.story.ui.help/help-content)]
      (is (vector? out))
      (is (= :div (first out))))))

;; ---- open / close behaviour ----------------------------------------------

(deftest open-then-close-toggles-atom
  (testing "open! flips the atom to true; close! flips it back"
    (rf.story.ui.help/open!)
    (is (true? @@#'rf.story.ui.help/open?))
    (rf.story.ui.help/close!)
    (is (false? @@#'rf.story.ui.help/open?))))

;; This row was SPLIT rather than moved (rf2-r51p). Its two `open?` ratom
;; assertions above genuinely run on node; only a trailing
;; `(when (browser?) (is (true? (seen?))))` was dead. Moving the row whole
;; would have taken LIVE assertions off the node lane — this bug in
;; reverse — so the persistence half now lives in
;; `re-frame.story-help-dom-cljs-test` as `close!-marks-the-overlay-seen`.
