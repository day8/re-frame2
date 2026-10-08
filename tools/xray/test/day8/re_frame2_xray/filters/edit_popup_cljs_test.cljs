(ns day8.re-frame2-xray.filters.edit-popup-cljs-test
  "View + wiring tests for the edit popup.

  `filters/Modal` is a Fresco boundary behind an `as-component` bridge,
  so calling it answers the `[:>]` interop head rather than a tree to
  walk. The view rows drive `test-helpers.modal-trees/edit-popup-tree`,
  which reproduces `filters/ModalView`'s gate and its three reads in the
  same order — so what they assert on is the SHIPPED hiccup."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-helpers :as rf.test-helpers]
            [day8.re-frame2-xray.filters.edit-popup :as edit-popup]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.modal-trees :as modal-trees]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each (xray-test-support/make-xray-runtime-fixture))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

(defn- popup-tree []
  (rf/with-frame :rf/xray
    (modal-trees/edit-popup-tree rf/dispatch)))

;; ---- draft -> pill -------------------------------------------------------

(deftest draft-to-pill-keeps-bare-strings-and-nils-blanks
  (is (= [{:pattern "/login"} {:pattern nil}]
         (mapv edit-popup/draft->pill [{:pattern "/login"} {:pattern "   "}]))))

;; ---- open / save / cancel / delete ---------------------------------------

(deftest open-edit-popup-from-pill-source-prepopulates
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :out :idx 2
                    :pill {:pattern :mouse-move}}])
  (is (= {:pattern ":mouse-move" :mode :out}
         (frame-sub [:rf.xray/edit-popup-draft]))))

(deftest save-add-appends-to-bucket-and-closes
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
  (frame-dispatch [:rf.xray/edit-popup-set-pattern ":auth/*"])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (is (= [{:pattern :auth/*}] (:in (frame-sub [:rf.xray/active-filters]))))
  (is (false? (frame-sub [:rf.xray/edit-popup-open?]))))

(deftest save-edit-in-place-replaces-at-original-index
  (xray-setup!)
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :mouse-move}])
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :anim-frame}])
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :out :idx 0
                    :pill {:pattern :mouse-move}}])
  (frame-dispatch [:rf.xray/edit-popup-set-pattern ":pointermove"])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (is (= [{:pattern :pointermove} {:pattern :anim-frame}]
         (:out (frame-sub [:rf.xray/active-filters])))))

(deftest save-flip-mode-moves-pill-between-buckets
  (xray-setup!)
  (frame-dispatch [:rf.xray/add-filter :in {:pattern :auth/login}])
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :in :idx 0
                    :pill {:pattern :auth/login}}])
  (frame-dispatch [:rf.xray/edit-popup-set-mode :out])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (is (= {:in [] :out [{:pattern :auth/login}]}
         (frame-sub [:rf.xray/active-filters]))))

(deftest save-blank-pattern-noops
  ;; Apply is disabled on a blank pattern; a programmatic save leaves the
  ;; bucket alone and keeps the popup open so the user can fix the input.
  (xray-setup!)
  (frame-dispatch [:rf.xray/add-filter :in {:pattern :auth/*}])
  (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
  (frame-dispatch [:rf.xray/edit-popup-set-pattern ""])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (is (= [{:pattern :auth/*}] (:in (frame-sub [:rf.xray/active-filters]))))
  (is (true? (frame-sub [:rf.xray/edit-popup-open?]))))

(deftest cancel-discards-draft-and-leaves-filters-alone
  (xray-setup!)
  (frame-dispatch [:rf.xray/add-filter :in {:pattern :auth/*}])
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :in :idx 0
                    :pill {:pattern :auth/*}}])
  (frame-dispatch [:rf.xray/edit-popup-set-pattern ":wildly-different"])
  (frame-dispatch [:rf.xray/close-edit-popup])
  (is (false? (frame-sub [:rf.xray/edit-popup-open?])))
  (is (= [{:pattern :auth/*}] (:in (frame-sub [:rf.xray/active-filters])))))

(deftest delete-drops-pill-at-trigger-idx
  (xray-setup!)
  (doseq [p [:a :b :c]]
    (frame-dispatch [:rf.xray/add-filter :out {:pattern p}]))
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :out :idx 1
                    :pill {:pattern :b}}])
  (frame-dispatch [:rf.xray/delete-edit-popup])
  (is (= [{:pattern :a} {:pattern :c}]
         (:out (frame-sub [:rf.xray/active-filters])))))

(deftest hide-event-type-then-save-lands-in-out-bucket
  (xray-setup!)
  (frame-dispatch [:rf.xray/hide-event-type :mouse-move])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (is (= {:in [] :out [{:pattern :mouse-move}]}
         (frame-sub [:rf.xray/active-filters]))))

;; ---- view ----------------------------------------------------------------

(defn- backdrop-attrs []
  (second (rf.test-helpers/find-by-testid (popup-tree) "rf-xray-edit-popup-backdrop")))

(deftest backdrop-follows-modal-positioning
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
  (let [{:keys [style] :as attrs} (backdrop-attrs)]
    (is (= ["fixed" 2147483647 "fixed"]
           [(:position style) (:z-index style) (:data-rf-xray-modal-positioning attrs)])
        "the default sits one z above the palette (2147483646)"))
  (frame-dispatch [:rf.xray/set-modal-positioning :absolute])
  (let [{:keys [style] :as attrs} (backdrop-attrs)]
    (is (= ["absolute" "absolute"]
           [(:position style) (:data-rf-xray-modal-positioning attrs)]))
    (is (< (:z-index style) 1000) "z-index drops to a sane in-cell value")))

(defn- all-strings
  "Every string literal in the expanded hiccup tree."
  [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (rf.test-helpers/expand-tree tree))
       (filter string?)
       (into #{})))

(deftest dialog-copy-is-normative
  ;; spec/018 §7 fixes the dialog copy; the title and the primary button
  ;; follow the trigger source.
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
  (let [tree (popup-tree)]
    (is (= [] (remove (all-strings tree)
                      ["Filter events" "Action" "Show only matching events"
                       "Hide matching events" "Match events containing"
                       "Matches keywords, namespaces, globs, or text."
                       "Examples: :auth/*, :auth, :mouse-move, /login"
                       "Cancel" "Add filter"])))
    (is (= ":auth/*, :mouse-move, /login"
           (:placeholder (second (rf.test-helpers/find-by-testid tree "rf-xray-edit-popup-pattern")))))
    (is (= [] (remove #(rf.test-helpers/find-by-testid tree %)
                      ["rf-xray-edit-popup-mode-in" "rf-xray-edit-popup-mode-out"
                       "rf-xray-edit-popup-cancel" "rf-xray-edit-popup-save"]))
        "the testids the tutorial screenshot script drives"))
  (doseq [[open-ev expected]
          [[[:rf.xray/open-edit-popup {:source :pill :mode :in :idx 0
                                       :pill {:pattern :auth/*}}]
            ["Edit filter" "Apply"]]
           [[:rf.xray/hide-event-type :user/mouse-move]
            ["Add filter for this event" "Add filter"]]]]
    (frame-dispatch open-ev)
    (is (= [] (remove (all-strings (popup-tree)) expected)) (pr-str open-ev))))
