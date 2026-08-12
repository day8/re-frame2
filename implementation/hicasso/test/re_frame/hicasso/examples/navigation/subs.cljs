(ns re-frame.hicasso.examples.navigation.subs
  "THE NAVIGATION WITNESS'S SUBSCRIPTIONS — including the one routing
  itself reads (rf2-hic-042).

  Ordinary `re-frame.core` reads over `app-db`, and one of them is not
  read by a view at all: [[::may-leave?]] is the article route's
  `:can-leave` guard, and its only consumer is routing's own leave
  decision. It is registered here rather than beside the route because it
  is a READ over the application's state, and this is where a reader
  looks for those.

  ## The guard's contract is closed, and its polarity is the trap

  `re-frame.routing.decisions` accepts literal `true` and `false` and
  nothing else: a guard answering a truthy dirty-flag rather than a
  boolean verdict is refused with `:rf.error/can-leave-non-boolean`
  rather than quietly permitted. That refusal exists because the classic
  version of this bug — a guard wired to the dirty flag instead of to its
  negation — lets the user walk away from unsaved work while every test
  that only checks *the guard is installed* stays green. So the sub below
  answers `(empty? drafts)`: **may I leave**, not *am I dirty*.

  The guard sub is called with the resolved target appended
  (`[::may-leave? <target>]`), which this one ignores — a guard that
  wanted to permit leaving toward a particular destination would read it."
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Layer 1 — the raw reads
;; ---------------------------------------------------------------------------

(rf/reg-sub ::articles (fn [db _] (:articles db)))
(rf/reg-sub ::drafts   (fn [db _] (:drafts db)))

;; ---------------------------------------------------------------------------
;; Layer 2 — what the two panes read
;; ---------------------------------------------------------------------------

(rf/reg-sub ::feed
  {:doc "The list rows — slug and title, which is all a row renders."}
  :<- [::articles]
  (fn [articles _] (mapv #(select-keys % [:slug :title]) articles)))

(rf/reg-sub ::article
  {:doc "One article by slug, or nil for a slug the URL invented. A URL is
  user input, so an unknown slug is a page rather than an error."}
  :<- [::articles]
  (fn [articles [_ slug]] (first (filter #(= slug (:slug %)) articles))))

(rf/reg-sub ::title
  {:doc "The editable title for one article — the draft when there is one,
  the saved title otherwise. What the controlled field is handed."}
  :<- [::articles]
  :<- [::drafts]
  (fn [[articles drafts] [_ slug]]
    (or (get drafts slug)
        (:title (first (filter #(= slug (:slug %)) articles))))))

(rf/reg-sub ::dirty?
  {:doc "Has anything been typed and not saved? What the Save button reads."}
  :<- [::drafts]
  (fn [drafts _] (boolean (seq drafts))))

;; ---------------------------------------------------------------------------
;; The guard routing reads
;; ---------------------------------------------------------------------------

(rf/reg-sub ::may-leave?
  {:doc "The article route's `:can-leave` verdict. TRUE means the
  navigation may proceed — see the namespace docstring on why this is not
  the dirty flag."}
  :<- [::drafts]
  (fn [drafts _] (empty? drafts)))
