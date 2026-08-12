(ns re-frame.hicasso.examples.typeahead.subs
  "THE DERIVATION GRAPH — and the two reads the flagship experiment is
  about (rf2-hic-044).

  Layers, each a pure function of the one below, in the `:<-` chain form
  the two earlier witness applications settled on.

  ## [[::suggestions]] and [[::detail]] are PARAMETRIC, and that is the point

  Everything else here is chrome. These two are the resource reads, and
  each takes the resource's parameter in its query vector:

      [::subs/suggestions \"cat\"]
      [::subs/detail      \"canid\"]

  A committed read of either is, by itself, the whole of what
  demand-driven resource ownership would need: the id says WHICH resource
  and the argument says WHICH ONE, and a boundary that stops rendering the
  panel stops holding the first. Today nothing connects that fact to the
  request, which is what
  [[re-frame.hicasso.examples.typeahead.events]]'s census rows are.

  It matters that the parameter is in the QUERY rather than read out of
  `app-db` inside the sub. A sub that reads the current term for itself is
  live under every term and expresses no parameter at all, so a mechanism
  riding read membership would have nothing to ride. The report's C4
  section turns on this, and the panel's body is written to make it true:
  the term is read once by the field's own boundary and handed down as a
  prop."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso.examples.typeahead.db :as db]))

;; ---------------------------------------------------------------------------
;; The field
;; ---------------------------------------------------------------------------

(rf/reg-sub ::search (fn [db _] (:search db)))

(rf/reg-sub ::term
  {:doc "The controlled field's value — the only place the text lives."}
  :<- [::search]
  (fn [search _] (:term search)))

(rf/reg-sub ::revision
  {:doc "The reset trigger. Bumped by `::events/clear`, so the field takes
         an empty value it may already have been handed."}
  :<- [::search]
  (fn [search _] (:revision search)))

(rf/reg-sub ::open?
  {:doc "Is the suggestion panel on screen? The one flag that decides
         whether the suggestion read exists at all."}
  :<- [::search]
  (fn [search _] (boolean (:open? search))))

(rf/reg-sub ::status
  :<- [::search]
  (fn [search _] (:status search)))

(rf/reg-sub ::problem
  :<- [::search]
  (fn [search _] (:problem search)))

;; ---------------------------------------------------------------------------
;; The resources
;; ---------------------------------------------------------------------------

(rf/reg-sub ::suggestions
  {:doc "The rows for `term`, or `nil` when what is held answers a
         different one. **Parametric on the resource's parameter**, so a
         committed read of it names the resource and the instance
         together."}
  :<- [::search]
  (fn [search [_ term]]
    (when (= term (:term (:shown search)))
      (:rows (:shown search)))))

(rf/reg-sub ::wanted
  {:doc "The term a live read wants, or `nil` — [[db/wanted]] as a
         subscription, so the shell can hand the panel its parameter."}
  (fn [db _] (db/wanted db)))

(rf/reg-sub ::held-rows
  {:doc "Whatever rows are held, whichever term they answer. Read ONLY by
         the panel's refresh-with-data decision; every other reader wants
         [[::suggestions]], which answers `nil` unless the rows are for
         the term asked about."}
  :<- [::search]
  (fn [search _] (:rows (:shown search))))

(rf/reg-sub ::detail
  {:doc "One row's detail, `:pending` while the service is being asked,
         `nil` when it has never been asked for. Parametric on the id, for
         the same reason [[::suggestions]] is parametric on the term."}
  (fn [db [_ id]] (get-in db [:details id])))

(rf/reg-sub ::chosen
  {:doc "The id the user picked, or nil. The detail pane's parameter, and
         the only thing that decides whether the detail read exists."}
  (fn [db _] (:chosen db)))

;; ---------------------------------------------------------------------------
;; Chrome
;; ---------------------------------------------------------------------------

(rf/reg-sub ::searchable?
  {:doc "Has the user typed enough to be worth a request? The panel shows
         a hint rather than an empty list when they have not."}
  :<- [::term]
  (fn [term _] (db/searchable? term)))
