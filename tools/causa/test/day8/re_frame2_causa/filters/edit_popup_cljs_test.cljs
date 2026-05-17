(ns day8.re-frame2-causa.filters.edit-popup-cljs-test
  "View + wiring tests for the edit popup (rf2-ak4ms).

  Covers:
   - open-edit-popup hydrates the draft from the trigger payload
   - set-mode / set-pattern / toggle-scope mutate the draft
   - save-edit-popup mutates :active-filters and closes the popup
   - delete-edit-popup drops the pill and closes
   - close-edit-popup discards the draft
   - hide-event-type (right-click row path) pre-populates OUT mode"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.filters :as filters]
            [day8.re-frame2-causa.filters.edit-popup :as edit-popup]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]))

(defn- causa-init! []
  (causa-test-support/reset-all!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- causa-setup! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

(defn- frame-sub [q]
  (rf/with-frame :rf/causa
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/causa
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

(deftest draft-to-pill-attaches-non-default-scope
  (testing "the default scope (#{:event-id}) is implicit and not
            serialised; widened scopes are preserved verbatim"
    (is (= {:pattern :auth/login}
           (edit-popup/draft->pill {:pattern ":auth/login"
                                    :scope   #{:event-id}}))
        "default scope omitted")
    (is (= {:pattern :auth/login
            :scope   #{:event-id :event-args}}
           (edit-popup/draft->pill {:pattern ":auth/login"
                                    :scope   #{:event-id :event-args}}))
        "widened scope preserved")))

(deftest pill-to-draft-stringifies-keyword
  (is (= {:pattern ":auth/*"
          :scope   #{:event-id}}
         (edit-popup/pill->draft {:pattern :auth/*}))))

(deftest pill-to-draft-empty-pill
  (is (= {:pattern ""
          :scope   #{:event-id}}
         (edit-popup/pill->draft nil))))

;; -------------------------------------------------------------------------
;; (2) Open popup — trigger payload hydrates the draft
;; -------------------------------------------------------------------------

(deftest open-edit-popup-from-add-source
  (causa-setup!)
  (frame-dispatch [:rf.causa/open-edit-popup {:source :add :mode :in}])
  (is (true? (frame-sub [:rf.causa/edit-popup-open?])))
  (let [trig  (frame-sub [:rf.causa/edit-popup-trigger])
        draft (frame-sub [:rf.causa/edit-popup-draft])]
    (is (= :add (:source trig)))
    (is (= :in  (:mode trig)))
    (is (= ""   (:pattern draft))
        "add source ships an empty draft")))

(deftest open-edit-popup-from-pill-source-prepopulates
  (causa-setup!)
  (frame-dispatch [:rf.causa/open-edit-popup
                   {:source :pill :mode :out :idx 2
                    :pill {:pattern :mouse-move}}])
  (let [draft (frame-sub [:rf.causa/edit-popup-draft])
        trig  (frame-sub [:rf.causa/edit-popup-trigger])]
    (is (= ":mouse-move" (:pattern draft))
        "pill source pre-populates the pattern input")
    (is (= :out (:mode draft)))
    (is (= 2 (:idx trig))
        "trigger remembers the pill index for in-place edit")))

;; -------------------------------------------------------------------------
;; (3) Draft mutation events
;; -------------------------------------------------------------------------

(deftest set-mode-mutates-draft
  (causa-setup!)
  (frame-dispatch [:rf.causa/open-edit-popup {:source :add :mode :in}])
  (frame-dispatch [:rf.causa/edit-popup-set-mode :out])
  (is (= :out (:mode (frame-sub [:rf.causa/edit-popup-draft])))))

(deftest set-pattern-mutates-draft
  (causa-setup!)
  (frame-dispatch [:rf.causa/open-edit-popup {:source :add :mode :in}])
  (frame-dispatch [:rf.causa/edit-popup-set-pattern ":auth/*"])
  (is (= ":auth/*" (:pattern (frame-sub [:rf.causa/edit-popup-draft])))))

(deftest toggle-scope-flips-membership
  (causa-setup!)
  (frame-dispatch [:rf.causa/open-edit-popup {:source :add :mode :in}])
  (is (= #{:event-id} (:scope (frame-sub [:rf.causa/edit-popup-draft]))))
  (frame-dispatch [:rf.causa/edit-popup-toggle-scope :event-args])
  (is (= #{:event-id :event-args}
         (:scope (frame-sub [:rf.causa/edit-popup-draft]))))
  (frame-dispatch [:rf.causa/edit-popup-toggle-scope :event-args])
  (is (= #{:event-id}
         (:scope (frame-sub [:rf.causa/edit-popup-draft])))))

;; -------------------------------------------------------------------------
;; (4) Save round-trip
;; -------------------------------------------------------------------------

(deftest save-add-appends-to-bucket-and-closes
  (causa-setup!)
  (frame-dispatch [:rf.causa/open-edit-popup {:source :add :mode :in}])
  (frame-dispatch [:rf.causa/edit-popup-set-pattern ":auth/*"])
  (frame-dispatch [:rf.causa/save-edit-popup])
  (let [filters (frame-sub [:rf.causa/active-filters])]
    (is (= [{:pattern :auth/*}] (:in filters))
        "new pill appended to IN bucket"))
  (is (false? (frame-sub [:rf.causa/edit-popup-open?]))
      "popup closed after save"))

(deftest save-edit-in-place-replaces-at-original-index
  (causa-setup!)
  ;; Seed two OUT pills.
  (frame-dispatch [:rf.causa/add-filter :out {:pattern :mouse-move}])
  (frame-dispatch [:rf.causa/add-filter :out {:pattern :anim-frame}])
  ;; Edit the first.
  (frame-dispatch [:rf.causa/open-edit-popup
                   {:source :pill :mode :out :idx 0
                    :pill {:pattern :mouse-move}}])
  (frame-dispatch [:rf.causa/edit-popup-set-pattern ":pointermove"])
  (frame-dispatch [:rf.causa/save-edit-popup])
  (let [out (:out (frame-sub [:rf.causa/active-filters]))]
    (is (= [{:pattern :pointermove}
            {:pattern :anim-frame}]
           out)
        "pill is replaced at idx 0; pill order preserved")))

(deftest save-flip-mode-moves-pill-between-buckets
  (causa-setup!)
  (frame-dispatch [:rf.causa/add-filter :in {:pattern :auth/login}])
  (frame-dispatch [:rf.causa/open-edit-popup
                   {:source :pill :mode :in :idx 0
                    :pill {:pattern :auth/login}}])
  ;; Flip IN → OUT.
  (frame-dispatch [:rf.causa/edit-popup-set-mode :out])
  (frame-dispatch [:rf.causa/save-edit-popup])
  (let [filters (frame-sub [:rf.causa/active-filters])]
    (is (= [] (:in filters)) "IN bucket emptied")
    (is (= [{:pattern :auth/login}] (:out filters))
        "pill landed in OUT bucket")))

(deftest save-blank-pattern-noops
  (testing "an empty pattern leaves the slot untouched (the Apply
            button is also disabled in this state)"
    (causa-setup!)
    (frame-dispatch [:rf.causa/add-filter :in {:pattern :auth/*}])
    (frame-dispatch [:rf.causa/open-edit-popup {:source :add :mode :in}])
    (frame-dispatch [:rf.causa/edit-popup-set-pattern ""])
    (frame-dispatch [:rf.causa/save-edit-popup])
    (is (= [{:pattern :auth/*}]
           (:in (frame-sub [:rf.causa/active-filters])))
        "blank-pattern save did not corrupt the bucket")
    (is (true? (frame-sub [:rf.causa/edit-popup-open?]))
        "popup stays open so the user can fix the input")))

;; -------------------------------------------------------------------------
;; (5) Cancel — close discards draft, no filter mutation
;; -------------------------------------------------------------------------

(deftest cancel-discards-draft-and-leaves-filters-alone
  (causa-setup!)
  (frame-dispatch [:rf.causa/add-filter :in {:pattern :auth/*}])
  (frame-dispatch [:rf.causa/open-edit-popup
                   {:source :pill :mode :in :idx 0
                    :pill {:pattern :auth/*}}])
  (frame-dispatch [:rf.causa/edit-popup-set-pattern ":wildly-different"])
  (frame-dispatch [:rf.causa/close-edit-popup])
  (is (false? (frame-sub [:rf.causa/edit-popup-open?])))
  (is (nil? (frame-sub [:rf.causa/edit-popup-draft]))
      "draft cleared after close")
  (is (= [{:pattern :auth/*}]
         (:in (frame-sub [:rf.causa/active-filters])))
      "the original pill survives a cancel"))

;; -------------------------------------------------------------------------
;; (6) Delete from popup
;; -------------------------------------------------------------------------

(deftest delete-drops-pill-at-trigger-idx
  (causa-setup!)
  (frame-dispatch [:rf.causa/add-filter :out {:pattern :a}])
  (frame-dispatch [:rf.causa/add-filter :out {:pattern :b}])
  (frame-dispatch [:rf.causa/add-filter :out {:pattern :c}])
  (frame-dispatch [:rf.causa/open-edit-popup
                   {:source :pill :mode :out :idx 1
                    :pill {:pattern :b}}])
  (frame-dispatch [:rf.causa/delete-edit-popup])
  (is (= [{:pattern :a} {:pattern :c}]
         (:out (frame-sub [:rf.causa/active-filters])))
      "pill at idx 1 deleted; siblings preserved"))

(deftest delete-from-add-trigger-just-closes
  (testing "delete is meaningful only when editing an existing pill;
            from the add path it degenerates to a close"
    (causa-setup!)
    (frame-dispatch [:rf.causa/add-filter :in {:pattern :auth/*}])
    (frame-dispatch [:rf.causa/open-edit-popup {:source :add :mode :in}])
    (frame-dispatch [:rf.causa/delete-edit-popup])
    (is (false? (frame-sub [:rf.causa/edit-popup-open?])))
    (is (= [{:pattern :auth/*}]
           (:in (frame-sub [:rf.causa/active-filters])))
        "delete from :add source did not touch the IN bucket")))

;; -------------------------------------------------------------------------
;; (7) Right-click row → hide-event-type opens popup with OUT pre-fill
;; -------------------------------------------------------------------------

(deftest hide-event-type-opens-popup-with-out-default
  (causa-setup!)
  (frame-dispatch [:rf.causa/hide-event-type :user/mouse-move])
  (is (true? (frame-sub [:rf.causa/edit-popup-open?])))
  (let [trig  (frame-sub [:rf.causa/edit-popup-trigger])
        draft (frame-sub [:rf.causa/edit-popup-draft])]
    (is (= :context (:source trig)))
    (is (= :out (:mode trig)))
    (is (= :out (:mode draft)))
    (is (= ":user/mouse-move" (:pattern draft))
        "draft pre-populated with the row's event-id")))

(deftest hide-event-type-then-save-lands-in-out-bucket
  (causa-setup!)
  (frame-dispatch [:rf.causa/hide-event-type :mouse-move])
  (frame-dispatch [:rf.causa/save-edit-popup])
  (let [filters (frame-sub [:rf.causa/active-filters])]
    (is (= [{:pattern :mouse-move}] (:out filters)))
    (is (= [] (:in filters)))))
