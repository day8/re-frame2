(ns day8.re-frame2-xray.filters.edit-popup-cljs-test
  "View + wiring tests for the edit popup (rf2-ak4ms).

  Covers:
   - open-edit-popup hydrates the draft from the trigger payload
   - set-mode / set-pattern mutate the draft
   - save-edit-popup mutates :active-filters and closes the popup
   - delete-edit-popup drops the pill and closes
   - close-edit-popup discards the draft
   - hide-event-type (right-click row path) pre-populates OUT mode"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.filters :as filters]
            [day8.re-frame2-xray.filters.edit-popup :as edit-popup]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(defn- xray-init! []
  (xray-test-support/reset-all!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- xray-setup! []
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {}))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

;; -------------------------------------------------------------------------
;; (1) Pure helpers: draft<->pill round-trip
;; -------------------------------------------------------------------------

(deftest draft-to-pill-normalises-keyword-strings
  (testing "a string starting with `:` round-trips to a keyword"
    (is (= {:pattern :auth/*}
           (edit-popup/draft->pill {:pattern ":auth/*"})))
    (is (= {:pattern :order/submit}
           (edit-popup/draft->pill {:pattern ":order/submit"})))))

(deftest draft-to-pill-preserves-bare-string-patterns
  (testing "a bare substring stays a string"
    (is (= {:pattern "/login"}
           (edit-popup/draft->pill {:pattern "/login"})))))

(deftest draft-to-pill-blank-becomes-nil
  (is (= {:pattern nil}
         (edit-popup/draft->pill {:pattern ""})))
  (is (= {:pattern nil}
         (edit-popup/draft->pill {:pattern "   "}))))

(deftest draft-to-pill-is-event-id-only
  (testing "the pill shape carries the pattern only — event-id is the
            implicit, only scope; no :scope key is ever serialised"
    (is (= {:pattern :auth/login}
           (edit-popup/draft->pill {:pattern ":auth/login"}))
        "pattern-only shape")
    (is (= [:pattern]
           (keys (edit-popup/draft->pill {:pattern ":auth/login"})))
        "no :scope (or any other) key in the projected pill")))

(deftest pill-to-draft-stringifies-keyword
  (is (= {:pattern ":auth/*"}
         (edit-popup/pill->draft {:pattern :auth/*}))))

(deftest pill-to-draft-empty-pill
  (is (= {:pattern ""}
         (edit-popup/pill->draft nil))))

;; -------------------------------------------------------------------------
;; (2) Open popup — trigger payload hydrates the draft
;; -------------------------------------------------------------------------

(deftest open-edit-popup-from-add-source
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
  (is (true? (frame-sub [:rf.xray/edit-popup-open?])))
  (let [trig  (frame-sub [:rf.xray/edit-popup-trigger])
        draft (frame-sub [:rf.xray/edit-popup-draft])]
    (is (= :add (:source trig)))
    (is (= :in  (:mode trig)))
    (is (= ""   (:pattern draft))
        "add source ships an empty draft")))

(deftest open-edit-popup-from-pill-source-prepopulates
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :out :idx 2
                    :pill {:pattern :mouse-move}}])
  (let [draft (frame-sub [:rf.xray/edit-popup-draft])
        trig  (frame-sub [:rf.xray/edit-popup-trigger])]
    (is (= ":mouse-move" (:pattern draft))
        "pill source pre-populates the pattern input")
    (is (= :out (:mode draft)))
    (is (= 2 (:idx trig))
        "trigger remembers the pill index for in-place edit")))

;; -------------------------------------------------------------------------
;; (3) Draft mutation events
;; -------------------------------------------------------------------------

(deftest set-mode-mutates-draft
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
  (frame-dispatch [:rf.xray/edit-popup-set-mode :out])
  (is (= :out (:mode (frame-sub [:rf.xray/edit-popup-draft])))))

(deftest set-pattern-mutates-draft
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
  (frame-dispatch [:rf.xray/edit-popup-set-pattern ":auth/*"])
  (is (= ":auth/*" (:pattern (frame-sub [:rf.xray/edit-popup-draft])))))

;; -------------------------------------------------------------------------
;; (4) Save round-trip
;; -------------------------------------------------------------------------

(deftest save-add-appends-to-bucket-and-closes
  (xray-setup!)
  (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
  (frame-dispatch [:rf.xray/edit-popup-set-pattern ":auth/*"])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (let [filters (frame-sub [:rf.xray/active-filters])]
    (is (= [{:pattern :auth/*}] (:in filters))
        "new pill appended to IN bucket"))
  (is (false? (frame-sub [:rf.xray/edit-popup-open?]))
      "popup closed after save"))

(deftest save-edit-in-place-replaces-at-original-index
  (xray-setup!)
  ;; Seed two OUT pills.
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :mouse-move}])
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :anim-frame}])
  ;; Edit the first.
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :out :idx 0
                    :pill {:pattern :mouse-move}}])
  (frame-dispatch [:rf.xray/edit-popup-set-pattern ":pointermove"])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (let [out (:out (frame-sub [:rf.xray/active-filters]))]
    (is (= [{:pattern :pointermove}
            {:pattern :anim-frame}]
           out)
        "pill is replaced at idx 0; pill order preserved")))

(deftest save-flip-mode-moves-pill-between-buckets
  (xray-setup!)
  (frame-dispatch [:rf.xray/add-filter :in {:pattern :auth/login}])
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :in :idx 0
                    :pill {:pattern :auth/login}}])
  ;; Flip IN → OUT.
  (frame-dispatch [:rf.xray/edit-popup-set-mode :out])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (let [filters (frame-sub [:rf.xray/active-filters])]
    (is (= [] (:in filters)) "IN bucket emptied")
    (is (= [{:pattern :auth/login}] (:out filters))
        "pill landed in OUT bucket")))

(deftest save-blank-pattern-noops
  (testing "an empty pattern leaves the slot untouched (the Apply
            button is also disabled in this state)"
    (xray-setup!)
    (frame-dispatch [:rf.xray/add-filter :in {:pattern :auth/*}])
    (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
    (frame-dispatch [:rf.xray/edit-popup-set-pattern ""])
    (frame-dispatch [:rf.xray/save-edit-popup])
    (is (= [{:pattern :auth/*}]
           (:in (frame-sub [:rf.xray/active-filters])))
        "blank-pattern save did not corrupt the bucket")
    (is (true? (frame-sub [:rf.xray/edit-popup-open?]))
        "popup stays open so the user can fix the input")))

;; -------------------------------------------------------------------------
;; (5) Cancel — close discards draft, no filter mutation
;; -------------------------------------------------------------------------

(deftest cancel-discards-draft-and-leaves-filters-alone
  (xray-setup!)
  (frame-dispatch [:rf.xray/add-filter :in {:pattern :auth/*}])
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :in :idx 0
                    :pill {:pattern :auth/*}}])
  (frame-dispatch [:rf.xray/edit-popup-set-pattern ":wildly-different"])
  (frame-dispatch [:rf.xray/close-edit-popup])
  (is (false? (frame-sub [:rf.xray/edit-popup-open?])))
  (is (nil? (frame-sub [:rf.xray/edit-popup-draft]))
      "draft cleared after close")
  (is (= [{:pattern :auth/*}]
         (:in (frame-sub [:rf.xray/active-filters])))
      "the original pill survives a cancel"))

;; -------------------------------------------------------------------------
;; (6) Delete from popup
;; -------------------------------------------------------------------------

(deftest delete-drops-pill-at-trigger-idx
  (xray-setup!)
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :a}])
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :b}])
  (frame-dispatch [:rf.xray/add-filter :out {:pattern :c}])
  (frame-dispatch [:rf.xray/open-edit-popup
                   {:source :pill :mode :out :idx 1
                    :pill {:pattern :b}}])
  (frame-dispatch [:rf.xray/delete-edit-popup])
  (is (= [{:pattern :a} {:pattern :c}]
         (:out (frame-sub [:rf.xray/active-filters])))
      "pill at idx 1 deleted; siblings preserved"))

(deftest delete-from-add-trigger-just-closes
  (testing "delete is meaningful only when editing an existing pill;
            from the add path it degenerates to a close"
    (xray-setup!)
    (frame-dispatch [:rf.xray/add-filter :in {:pattern :auth/*}])
    (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
    (frame-dispatch [:rf.xray/delete-edit-popup])
    (is (false? (frame-sub [:rf.xray/edit-popup-open?])))
    (is (= [{:pattern :auth/*}]
           (:in (frame-sub [:rf.xray/active-filters])))
        "delete from :add source did not touch the IN bucket")))

;; -------------------------------------------------------------------------
;; (7) Right-click row → hide-event-type opens popup with OUT pre-fill
;; -------------------------------------------------------------------------

(deftest hide-event-type-opens-popup-with-out-default
  (xray-setup!)
  (frame-dispatch [:rf.xray/hide-event-type :user/mouse-move])
  (is (true? (frame-sub [:rf.xray/edit-popup-open?])))
  (let [trig  (frame-sub [:rf.xray/edit-popup-trigger])
        draft (frame-sub [:rf.xray/edit-popup-draft])]
    (is (= :context (:source trig)))
    (is (= :out (:mode trig)))
    (is (= :out (:mode draft)))
    (is (= ":user/mouse-move" (:pattern draft))
        "draft pre-populated with the row's event-id")))

(deftest hide-event-type-then-save-lands-in-out-bucket
  (xray-setup!)
  (frame-dispatch [:rf.xray/hide-event-type :mouse-move])
  (frame-dispatch [:rf.xray/save-edit-popup])
  (let [filters (frame-sub [:rf.xray/active-filters])]
    (is (= [{:pattern :mouse-move}] (:out filters)))
    (is (= [] (:in filters)))))

;; -------------------------------------------------------------------------
;; (8) Modal positioning (rf2-om6fa)
;; -------------------------------------------------------------------------

(declare expand-tree)

(defn- expand-tree
  [tree]
  (cond
    (and (vector? tree) (fn? (first tree)))
    (expand-tree (apply (first tree) (rest tree)))

    (vector? tree)
    (mapv expand-tree tree)

    (seq? tree)
    (map expand-tree tree)

    :else
    tree))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (tree-seq (some-fn vector? seq?) seq (expand-tree tree))))

(defn- testids-with-prefix [tree prefix]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-tree tree))
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (:data-testid (second node)))))
       (filter #(and (string? %) (str/starts-with? % prefix)))
       (into #{})))

(deftest backdrop-defaults-to-fixed-positioning
  (testing "with no :rf.xray/modal-positioning slot set, the edit
            popup backdrop renders position: fixed at the production
            z-index"
    (xray-setup!)
    (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
    (rf/with-frame :rf/xray
      (let [rendered (filters/Modal)
            backdrop (find-by-testid rendered "rf-xray-edit-popup-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "fixed" (:position style)))
        (is (= 2147483647 (:z-index style))
            "production z-index unchanged — one above the palette")
        (is (= "fixed"
               (:data-rf-xray-modal-positioning (second backdrop))))))))

(deftest backdrop-honours-absolute-positioning
  (testing "after `:rf.xray/set-modal-positioning :absolute` the
            backdrop switches to position: absolute"
    (xray-setup!)
    (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
    (frame-dispatch [:rf.xray/set-modal-positioning :absolute])
    (rf/with-frame :rf/xray
      (let [rendered (filters/Modal)
            backdrop (find-by-testid rendered "rf-xray-edit-popup-backdrop")
            style    (:style (second backdrop))]
        (is (some? backdrop))
        (is (= "absolute" (:position style)))
        (is (< (:z-index style) 1000)
            "z-index drops to a sane in-cell value")
        (is (= "absolute"
               (:data-rf-xray-modal-positioning (second backdrop))))))))

;; -------------------------------------------------------------------------
;; (9) Dialog is event-id-only (rf2-o8pjv)
;; -------------------------------------------------------------------------
;;
;; The Add-filter dialog reduces to exactly the Action radios (Show
;; only matching events / Hide matching events) + the Match-events
;; field + footer. The Match-scope section (event-id / event-args /
;; source-coord / tags checkboxes) and its
;; `:rf.xray/edit-popup-toggle-scope` plumbing are excised — event-id
;; is the implicit, only scope.

(deftest dialog-renders-mode-and-pattern-only
  (testing "the rendered dialog has Mode radios + the Pattern input +
            footer, and NO match-scope checkboxes"
    (xray-setup!)
    (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
    (rf/with-frame :rf/xray
      (let [rendered (filters/Modal)]
        ;; Kept surfaces.
        (is (some? (find-by-testid rendered "rf-xray-edit-popup-mode-in"))
            "Mode IN radio present")
        (is (some? (find-by-testid rendered "rf-xray-edit-popup-mode-out"))
            "Mode OUT radio present")
        (is (some? (find-by-testid rendered "rf-xray-edit-popup-pattern"))
            "Pattern input present")
        (is (some? (find-by-testid rendered "rf-xray-edit-popup-cancel"))
            "Cancel button present")
        (is (some? (find-by-testid rendered "rf-xray-edit-popup-save"))
            "Add filter / Apply button present")
        ;; Excised surface — no scope checkboxes of any key.
        (is (= #{} (testids-with-prefix rendered "rf-xray-edit-popup-scope-"))
            "no match-scope checkboxes render")))))

;; -------------------------------------------------------------------------
;; (10) Dialog copy is Mike's normative wording (rf2-ad7zx.19)
;; -------------------------------------------------------------------------
;;
;; The Add-filter dialog copy is normative in spec/018 §7. These tests
;; lock the exact strings — title, Action radios, field label, the
;; two-line helper, and the buttons — so a wording drift fails CI.

(defn- all-strings
  "Every string literal in the expanded hiccup tree."
  [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-tree tree))
       (filter string?)
       (into #{})))

(defn- placeholder-values
  "Every `:placeholder` attribute value in the expanded tree."
  [tree]
  (->> (tree-seq (some-fn vector? seq?) seq (expand-tree tree))
       (keep (fn [node]
               (when (and (vector? node) (map? (second node)))
                 (:placeholder (second node)))))
       (into #{})))

(deftest add-filter-dialog-copy-is-normative
  (testing "the Add-filter (trailing-+ / :add) dialog renders Mike's
            exact copy per spec/018 §7"
    (xray-setup!)
    (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
    (rf/with-frame :rf/xray
      (let [rendered     (filters/Modal)
            strings      (all-strings rendered)
            placeholders (placeholder-values rendered)]
        (is (contains? strings "Filter events")
            "title is 'Filter events' on the :add source")
        (is (contains? strings "Action")
            "section label is 'Action'")
        (is (contains? strings "Show only matching events")
            "IN radio reads 'Show only matching events'")
        (is (contains? strings "Hide matching events")
            "OUT radio reads 'Hide matching events'")
        (is (contains? strings "Match events containing")
            "field label is 'Match events containing'")
        (is (contains? strings "Matches keywords, namespaces, globs, or text.")
            "helper line 1")
        (is (contains? strings "Examples: :auth/*, :auth, :mouse-move, /login")
            "helper line 2 (examples)")
        (is (contains? placeholders ":auth/*, :mouse-move, /login")
            "input placeholder")
        (is (contains? strings "Cancel") "Cancel button")
        (is (contains? strings "Add filter")
            "primary button reads 'Add filter' on the :add source")))))

(deftest pill-edit-dialog-keeps-edit-title-and-apply
  (testing "editing an existing pill (:pill source) keeps 'Edit filter'
            + 'Apply' while sharing the same Action/field copy"
    (xray-setup!)
    (frame-dispatch [:rf.xray/open-edit-popup
                     {:source :pill :mode :in :idx 0
                      :pill {:pattern :auth/*}}])
    (rf/with-frame :rf/xray
      (let [strings (all-strings (filters/Modal))]
        (is (contains? strings "Edit filter")
            "title is 'Edit filter' on the :pill source")
        (is (contains? strings "Apply")
            "primary button is 'Apply' when editing")
        (is (contains? strings "Show only matching events")
            "Action radio copy shared with the add path")))))

(deftest context-dialog-keeps-add-for-this-event-title
  (testing "the right-click (:context) source titles 'Add filter for
            this event' and uses the 'Add filter' primary button"
    (xray-setup!)
    (frame-dispatch [:rf.xray/hide-event-type :user/mouse-move])
    (rf/with-frame :rf/xray
      (let [strings (all-strings (filters/Modal))]
        (is (contains? strings "Add filter for this event")
            "title is 'Add filter for this event' on the :context source")
        (is (contains? strings "Add filter")
            "primary button reads 'Add filter'")
        (is (contains? strings "Hide matching events")
            "Action radio copy shared with the add path")))))

(deftest toggle-scope-event-is-unregistered
  (testing "the `:rf.xray/edit-popup-toggle-scope` event is fully
            removed — dispatching it is a no-op (no scope slot appears)"
    (xray-setup!)
    (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
    ;; The handler is gone; an unhandled dispatch must not introduce a
    ;; :scope slot into the draft.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/edit-popup-toggle-scope :event-args]))
    (is (nil? (:scope (frame-sub [:rf.xray/edit-popup-draft])))
        "draft carries no :scope slot")))

(deftest saved-pill-is-event-id-only
  (testing "a saved pill carries :pattern only — no :scope (or any
            other) key. The matcher honours event-id exclusively."
    (xray-setup!)
    (frame-dispatch [:rf.xray/open-edit-popup {:source :add :mode :in}])
    (frame-dispatch [:rf.xray/edit-popup-set-pattern ":auth/*"])
    (frame-dispatch [:rf.xray/save-edit-popup])
    (let [pill (-> (frame-sub [:rf.xray/active-filters]) :in first)]
      (is (= {:pattern :auth/*} pill)
          "stored pill is the event-id pattern shape only")
      (is (= [:pattern] (keys pill))
          "no :scope key persisted"))))
