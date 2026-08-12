(ns re-frame.hicasso.examples.forms.subs
  "WHAT THE FORM READS (rf2-hic-051).

  Every read the views make, and nothing else. The `db` namespace holds
  the rules; this file only addresses them, which is why each handler
  below is one line.

  ## The reads are NARROW on purpose

  A controlled field re-renders its boundary whenever a subscription it
  read notifies, so the shape of these queries is the shape of the
  per-keystroke cost. `::field` takes the field as a parameter and
  returns one string, so typing into the assignee notifies the assignee's
  boundary and no other; a single `::form` sub returning the whole map
  would notify the notes field, the submit button and the error region on
  every character. rf2-hic-045 owns the published per-keystroke numbers
  for the four-field editor and the grid; this application inherits the
  discipline they measure rather than re-measuring it.

  `::shown-problem` is the one read that deliberately spans more than its
  own field: R-A5's gate is *touched OR attempted*, and `:attempted?` is
  a fact about the form. Attempting a submission is therefore allowed to
  notify every field's error region at once — which is exactly what it is
  for."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso.examples.forms.db :as db]))

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered field
;; ---------------------------------------------------------------------------

(rf/reg-sub ::subject-shown
  {:doc "What the subject field shows — the draft while a session is open,
         the committed subject otherwise."}
  (fn [db [_ ikey]] (db/subject-shown db ikey)))

(rf/reg-sub ::subject-revision
  {:doc "The subject field's reset trigger. A COUNTER, not a value: the
         field re-baselines when this CHANGES, never on value equality."}
  (fn [db [_ ikey]] (get-in db [:subject-revision ikey] 0)))

(rf/reg-sub ::subject
  {:doc "The committed subject — what a reader who is not editing sees."}
  (fn [db _] (get-in db [:ticket :subject])))

(rf/reg-sub ::editing?
  {:doc "Is a subject session open for `ikey`? Read by the region that
         explains Enter and Escape, so the hint is absent until it
         applies."}
  (fn [db [_ ikey]] (some? (get-in db (db/draft-path ikey)))))

;; ---------------------------------------------------------------------------
;; Recipe 2 — the ordinary fields and the gate
;; ---------------------------------------------------------------------------

(rf/reg-sub ::field
  {:doc "One form field's draft text."}
  (fn [db [_ field]] (get-in db [:form :draft field])))

(rf/reg-sub ::shown-problem
  {:doc "The problem to DISPLAY for `field`, gated by touch and submit
         attempt — nil until the user has earned it."}
  (fn [db [_ field]] (db/shown-problem (:form db) field)))

(rf/reg-sub ::can-submit?
  {:doc "The submit gate. Calls the same `db/can-submit?` the submit
         handler calls, so button and handler cannot disagree."}
  (fn [db _] (db/can-submit? db)))
