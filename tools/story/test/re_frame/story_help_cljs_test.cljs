(ns re-frame.story-help-cljs-test
  "CLJS tests for Story's first-time-user help overlay on the node lane:
  `seen?` degrades to false without localStorage, the help content's
  shortcuts table and inspectors list, and `open!` / `close!` on the open
  atom. The localStorage round trip, including the seen flag `close!` writes,
  needs `window.localStorage`, so it lives in
  `re-frame.story-help-dom-cljs-test`, which the browser lane loads."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.story.ui.help :as rf.story.ui.help]
            [re-frame.story.ui.keybindings :as rf.story.ui.keybindings]
            [re-frame.story.ui.xray-embed :as rf.story.ui.xray-embed]))

;; ---- fixtures ------------------------------------------------------------

(defn clear-flag! []
  (rf.story.ui.help/reset-seen!)
  (reset! @#'rf.story.ui.help/open? false))

(use-fixtures :each {:before clear-flag! :after clear-flag!})

;; ---- seen? without localStorage ------------------------------------------

(deftest seen-defaults-to-false
  (testing "seen? is false when localStorage has never been touched"
    (is (false? (rf.story.ui.help/seen?)))))

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

;; Spec 014 §Chrome-visibility hotkeys, API.md and `shortcut-keys`' own
;; docstring all say the overlay's table is READ from the hotkey
;; registry. A table that hard-coded the four letters would never show a
;; fifth binding on the cheat-sheet.

(deftest shortcuts-table-renders-every-registered-key
  (testing "each key the registry binds appears in the help table"
    (is (some? (shortcuts-table)) "control: the table is found")
    (doseq [k (rf.story.ui.keybindings/shortcut-keys)]
      (is (contains? (table-strings) k)
          (str "registered key " (pr-str k) " is in the help table")))))

(deftest shortcuts-table-follows-the-registry
  (testing "a key added to the registry appears in the table with no help.cljs edit"
    (is (not (contains? (table-strings) "z")) "control: no z row before the redef")
    (with-redefs [rf.story.ui.keybindings/shortcut-keys
                  (fn [] ["a" "f" "s" "t" "z"])]
      (is (contains? (table-strings) "z")
          "the table was built from shortcut-keys"))))

;; ---- the inspectors list -------------------------------------------------

(defn- inspectors-text
  "Every string in the list that follows the `Inspectors (right)` heading,
  joined with spaces, or nil when no such heading is found."
  []
  (let [children (vec (rest (rf.story.ui.help/help-content)))
        heading? (fn [x] (and (vector? x) (= "Inspectors (right)" (last x))))
        idx      (first (keep-indexed (fn [i x] (when (heading? x) i)) children))]
    (when idx
      (->> (tree-seq sequential? seq (get children (inc idx)))
           (filter string?)
           (str/join " ")))))

(deftest inspectors-list-names-every-xray-lens
  (testing "each Xray panel the RHS chip row offers is named in the help"
    (is (some? (inspectors-text)) "control: the inspectors list is found")
    (is (seq rf.story.ui.xray-embed/panel-catalog) "control: the chip row offers panels")
    (doseq [{:keys [label]} rf.story.ui.xray-embed/panel-catalog]
      (is (str/includes? (str (inspectors-text)) label)
          (str "chip-row panel " (pr-str label) " is in the inspectors list")))))

(deftest inspectors-list-names-no-panel-that-does-not-ship
  (testing "Story's RHS has no time-travel or notes panel, so the help names neither"
    (is (some? (inspectors-text)) "control: the inspectors list is found")
    (doseq [absent ["time-travel" "notes"]]
      (is (not (str/includes? (str/lower-case (str (inspectors-text))) absent))
          (str (pr-str absent) " is not in the inspectors list")))))

;; ---- open / close behaviour ----------------------------------------------

(deftest open-then-close-toggles-atom
  (testing "open! flips the atom to true; close! flips it back"
    (rf.story.ui.help/open!)
    (is (true? @@#'rf.story.ui.help/open?))
    (rf.story.ui.help/close!)
    (is (false? @@#'rf.story.ui.help/open?))))
