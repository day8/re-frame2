(ns re-frame.hicasso.examples.forms.l0-cljs-test
  "L0 — THE RECIPES AS PURE CALCULATIONS AND REAL TRANSITIONS (rf2-hic-051).

  The kit's ladder names L0 as the tier it deliberately does not touch:
  a handler is a function of `db` and an event vector, a subscription is
  a function of its inputs, and a transition is the pair. So this file
  `:require`s neither `re-frame.hicasso` nor either half of the kit, and
  frame scope is the programmer's ordinary bracket.

  ## The five trap classes, and where each is decided

  The bead names five hand-rolled traps this application must be shown to
  avoid. Three of them are decided here and two are not, and saying which
  is which is the point of the list:

  | Trap | Decided |
  |---|---|
  | twin-atom stack (a draft in a component beside the model) | L2 — `forms.l2-cljs-test`, structurally: the bodies run under a resolver that refuses a hook |
  | same-value blindness (a refusal that leaves the draft on screen) | here for the MODEL half; `forms.flow-dom-cljs-test` for the SCREEN half |
  | commit flicker (a late reply overwriting newer work) | here |
  | arity-sniffed done-fn (completion as an undocumented callback) | here for the reply, L2 for the absence of function props |
  | re-minted ephemeral state (a draft destroyed by re-render or shared across instances) | here for addressing, `forms.flow-dom-cljs-test` for survival across a real remount |

  A trap whose witness could pass with the feature absent is worth
  nothing, so each row below is written to state what it would take to
  make it red."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.hicasso.examples.forms.db :as db]
            [re-frame.hicasso.examples.forms.events :as events]
            [re-frame.hicasso.examples.forms.subs :as subs]
            ;; The production managed-HTTP fx surface, so the mutation's
            ;; transport probe resolves. The fetch itself never happens —
            ;; `capture-transport!` below replaces the effect with a
            ;; recorder and the rows replay the transport's own reply shape.
            [re-frame.http.managed]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     ;; The reset restores the registrar to a baseline and the resources
     ;; artefact clears the mutation kind, so the ns-load registration may
     ;; not still be there when a row runs. It survived in this lane and
     ;; not in the browser one, which is exactly the kind of luck a
     ;; witness should not be standing on — see `events/register-save!`
     ;; and rf2-06lp for what its absence costs.
     :init-fn       (fn [] (events/register-save!))}))

(def ^:private ticket 7)

(defn- with-app
  "Run `f` against a fresh frame booted with the application's own seed
  event, and destroy it afterwards."
  [f]
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::events/seed]]})]
    (f frame)))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))
(defn- app-db-of [frame] (rf/app-db-value frame))
(defn- send! [frame event-v] (rf/dispatch-sync event-v {:frame frame}))

(defn- type-subject!
  "Type `text` into the buffered subject field — `h/reg-state`'s own
  setter event, which is what `:on-input` dispatches."
  [frame text]
  (send! frame [db/subject-draft ticket text]))

;; ---------------------------------------------------------------------------
;; Pure — the rules, with no runtime anywhere
;; ---------------------------------------------------------------------------

(deftest the-rules-are-a-function-of-the-draft-alone
  (is (= {} (db/problems {:assignee "ada" :notes ""})))
  (is (= {:assignee :problem/assignee-blank} (db/problems {:assignee "  " :notes ""})))
  (is (= {:notes :problem/notes-too-long}
         (db/problems {:assignee "ada" :notes (apply str (repeat (inc db/notes-limit) "x"))})))
  (testing "exactly at the limit is legal — the near-miss the guard must not catch"
    (is (= {} (db/problems {:assignee "ada" :notes (apply str (repeat db/notes-limit "x"))}))))
  (testing "both at once, because a submit attempt reveals every one of them"
    (is (= #{:assignee :notes}
           (set (keys (db/problems {:assignee "" :notes (apply str (repeat 200 "x"))})))))))

(deftest a-problem-is-not-shown-until-it-is-earned
  (let [form {:draft {:assignee "" :notes ""} :touched #{} :attempted? false}]
    (testing "R-A5 — a blank form on first paint reports nothing"
      (is (nil? (db/shown-problem form :assignee))
          "the problem EXISTS; showing it before the user has been near the
           field is the defect this gate deletes"))
    (testing "touching the field it belongs to reveals it"
      (is (= :problem/assignee-blank
             (db/shown-problem (assoc form :touched #{:assignee}) :assignee))))
    (testing "and touching its NEIGHBOUR does not — the gate is per field"
      (is (nil? (db/shown-problem (assoc form :touched #{:notes}) :assignee))))
    (testing "a submit attempt reveals every field at once"
      (is (= :problem/assignee-blank
             (db/shown-problem (assoc form :attempted? true) :assignee))))
    (testing "and reveals nothing that is not a problem"
      (is (nil? (db/shown-problem (-> form
                                      (assoc :attempted? true)
                                      (assoc-in [:draft :assignee] "ada"))
                                  :assignee))
          "the gate decides WHEN a problem is shown, never whether it is one"))))

;; ---------------------------------------------------------------------------
;; Recipe 1 — the buffered draft's transitions
;; ---------------------------------------------------------------------------

(deftest the-field-is-populated-before-anybody-types
  (with-app
    (fn [frame]
      (is (= "Login page hangs on submit" (read-sub frame [::subs/subject-shown ticket]))
          "no draft, so the committed subject is what the field shows —
           there is no load-the-form step to forget")
      (is (false? (read-sub frame [::subs/editing? ticket]))
          "and reading is not editing"))))

(deftest a-keystroke-opens-the-session-and-nothing-else-moves
  (with-app
    (fn [frame]
      (let [before (app-db-of frame)]
        (type-subject! frame "Login page hangs")
        (let [after (app-db-of frame)]
          (is (= "Login page hangs" (read-sub frame [::subs/subject-shown ticket])))
          (is (true? (read-sub frame [::subs/editing? ticket])))
          (is (= (:ticket before) (:ticket after))
              "the committed subject has not moved — that is what BUFFERED means")
          (is (= (:subject-revision before) (:subject-revision after))
              "and the revision has not moved either: typing is not a reset"))))))

(deftest enter-commits-the-trimmed-draft-and-ends-the-session
  (with-app
    (fn [frame]
      (type-subject! frame "  Login times out  ")
      (send! frame [::events/commit-subject ticket])
      (is (= "Login times out" (get-in (app-db-of frame) [:ticket :subject])))
      (is (false? (read-sub frame [::subs/editing? ticket]))
          "the session is over — the draft is gone, not merely equal")
      (is (= 1 (read-sub frame [::subs/subject-revision ticket]))
          "and the revision moved, which is what re-baselines a field that
           is still mounted"))))

(deftest a-refused-commit-leaves-the-subject-standing-and-still-moves-the-revision
  ;; TRAP: same-value blindness. A refusal that changed nothing at all
  ;; would leave the blank text sitting in the field looking accepted,
  ;; because nothing the field reads would have moved.
  (with-app
    (fn [frame]
      (type-subject! frame "   ")
      (send! frame [::events/commit-subject ticket])
      (is (= "Login page hangs on submit" (get-in (app-db-of frame) [:ticket :subject]))
          "blank is refused — the committed subject does NOT move")
      (is (= 1 (read-sub frame [::subs/subject-revision ticket]))
          "and the revision moves ANYWAY. Delete the revision bump from the
           refusing branch of `events/end-session` and this line reds; it is
           the only signal a refusal has, because value equality is blind to
           it by construction")
      (is (false? (read-sub frame [::subs/editing? ticket]))
          "a refusal ends the session too — the user is shown the committed
           value back, not left holding text nothing will accept"))))

(deftest escape-abandons-the-session-and-the-late-blur-does-nothing
  ;; TRAP: cancel-unmounts-then-blur, from the model's side. The screen's
  ;; side is `forms.flow-dom-cljs-test`.
  (with-app
    (fn [frame]
      (type-subject! frame "half-typed nonsense")
      (send! frame [::events/cancel-subject ticket])
      (is (= "Login page hangs on submit" (read-sub frame [::subs/subject-shown ticket])))
      (is (= 1 (read-sub frame [::subs/subject-revision ticket])))
      (testing "the blur that follows finds no session and commits nothing"
        (let [before (app-db-of frame)]
          (send! frame [::events/commit-subject ticket])
          (is (= before (app-db-of frame))
              "not merely 'the subject is unchanged' — NOTHING is, including
               the revision. A commit that ran and happened to write the same
               value would pass a narrower assertion and be a different
               program"))))))

(deftest committing-twice-is-committing-once
  (with-app
    (fn [frame]
      (type-subject! frame "Login times out")
      (send! frame [::events/commit-subject ticket])
      (let [after-first (app-db-of frame)]
        (send! frame [::events/commit-subject ticket])
        (is (= after-first (app-db-of frame))
            "Enter then blur, or double Enter: the second finds no session")))))

(deftest two-tickets-hold-two-drafts
  ;; TRAP: re-minted / colliding ephemeral state. A hand-picked
  ;; `[:ui :subject-draft]` path would be ONE draft for the whole page.
  (with-app
    (fn [frame]
      (send! frame [db/subject-draft 7 "seven"])
      (send! frame [db/subject-draft 9 "nine"])
      (is (= "seven" (read-sub frame [::subs/subject-shown 7])))
      (is (= "nine" (read-sub frame [::subs/subject-shown 9])))
      (testing "and ending one session leaves the other's alone"
        (send! frame [::events/cancel-subject 7])
        (is (= "nine" (read-sub frame [::subs/subject-shown 9])))
        (is (= 0 (read-sub frame [::subs/subject-revision 9]))
            "including its revision — a reset is per instance")))))

;; ---------------------------------------------------------------------------
;; Recipe 2 — the gate
;; ---------------------------------------------------------------------------

(deftest a-refused-submission-reveals-every-problem-and-asks-for-nothing
  (with-app
    (fn [frame]
      (is (nil? (read-sub frame [::subs/shown-problem :assignee]))
          "before the attempt")
      (send! frame [::events/submit])
      (is (= :problem/assignee-blank (read-sub frame [::subs/shown-problem :assignee]))
          "after it")
      (is (false? (read-sub frame [::subs/can-submit?]))
          "and the gate the button reads still says no"))))

(deftest the-button-and-the-handler-read-one-definition
  ;; R-A6. The failure this deletes is two recomputations drifting apart,
  ;; so the assertion is that they answer the same thing at every step of
  ;; a repair — not that either is right at one moment.
  (with-app
    (fn [frame]
      (doseq [text ["" "  " "ada" ""]]
        (send! frame [::events/edit :assignee text])
        (is (= (db/can-submit? (app-db-of frame))
               (read-sub frame [::subs/can-submit?]))
            (str "gate agreement after typing " (pr-str text)))))))

(deftest a-problem-clears-as-soon-as-the-field-is-repaired
  (with-app
    (fn [frame]
      (send! frame [::events/touch {:field :assignee}])
      (is (some? (read-sub frame [::subs/shown-problem :assignee])))
      (send! frame [::events/edit :assignee "ada"])
      (is (nil? (read-sub frame [::subs/shown-problem :assignee]))
          "the problem is derived from the current draft, so repairing the
           field clears it without a second event to remember"))))

;; ---------------------------------------------------------------------------
;; Recipe 3 — the write, as a mutation instance
;; ---------------------------------------------------------------------------

(def ^:private !requests
  "Every `:rf.http/managed` argument map the transport stub was handed,
  newest last. Each carries the runtime's own `:on-success` / `:on-failure`
  reply targets, which is what the rows replay."
  (atom []))

(defn- capture-transport!
  "Replace the managed-HTTP effect with a recorder. The mutation runtime
  still mints the instance, the work-ledger record and the reply
  addressing; only the fetch is missing, and a row supplies the reply the
  transport would have produced."
  []
  (reset! !requests [])
  (fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! !requests conj args) nil)))

(defn- reply-ok! [frame args value]
  (send! frame (conj (:on-success args) {:status :ok :value value})))

(defn- reply-error! [frame args error]
  (send! frame (conj (:on-failure args) {:status :error :error error})))

(defn- fill-valid! [frame]
  (send! frame [::events/edit :assignee "ada"])
  (send! frame [::events/edit :notes "reproduced on staging"]))

(defn- status [frame]
  (read-sub frame [:rf/mutation {:instance events/save-instance}]))

(deftest a-refused-submission-asks-the-server-for-nothing
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::events/submit])
      (is (= [] @!requests)
          "the gate refused, so no write was executed at all")
      (is (false? (:pending? (status frame)))
          "and the form is not busy — there is nothing in flight to be busy
           about"))))

(deftest the-busy-flag-is-the-writes-own-and-not-a-flag-anybody-keeps
  ;; TRAP: arity-sniffed done-fn / a local `:saving?` boolean. Completion
  ;; arrives at a NAMED EVENT because `:reply-to` said so, and the busy
  ;; state is a projection of the instance rather than a copy of it.
  (capture-transport!)
  (with-app
    (fn [frame]
      (fill-valid! frame)
      (send! frame [::events/submit])
      (is (= 1 (count @!requests)))
      (is (true? (:pending? (status frame)))
          "pending comes from the instance; this application writes no
           `:saving?` key anywhere — grep it and there is none to clear")
      (reply-ok! frame (last @!requests) {:ok true})
      (is (false? (:pending? (status frame))))
      (is (true? (:success? (status frame))))
      (testing "and the reply landed at the named event, which reset the form"
        (is (= "ada" (get-in (app-db-of frame) [:ticket :assignee])))
        (is (= db/blank-form (:form (app-db-of frame))))))))

(deftest a-failed-write-leaves-the-draft-alone-and-says-so-once
  (capture-transport!)
  (with-app
    (fn [frame]
      (fill-valid! frame)
      (send! frame [::events/submit])
      (reply-error! frame (last @!requests) {:message "boom"})
      (is (true? (:error? (status frame))))
      (is (false? (:pending? (status frame)))
          "a failure clears busy — the trap a hand-kept boolean falls into
           is exactly the branch that forgets this one")
      (is (= "ada" (get-in (app-db-of frame) [:form :draft :assignee]))
          "and the user's work is still in the form"))))

(deftest a-superseded-reply-never-reaches-the-continuation
  ;; TRAP: commit flicker. The older request replies LAST; if its reply
  ;; were delivered the ticket would end up holding the older draft.
  (capture-transport!)
  (with-app
    (fn [frame]
      (fill-valid! frame)
      (send! frame [::events/submit])
      (let [first-args (last @!requests)]
        (send! frame [::events/edit :assignee "grace"])
        (send! frame [::events/submit])
        (let [second-args (last @!requests)]
          (is (= 2 (count @!requests)) "two writes under one instance")
          (is (not= (:on-success first-args) (:on-success second-args))
              "and the runtime addressed them apart — the two reply targets
               carry different correlation, which is what the fence reads.
               Without this line the row below could be green because the
               replays were indistinguishable rather than because one was
               refused")
          (reply-ok! frame second-args {:ok true})
          (is (= "grace" (get-in (app-db-of frame) [:ticket :assignee])))
          (testing "the older reply lands afterwards and changes nothing"
            (reply-ok! frame first-args {:ok true})
            (is (= "grace" (get-in (app-db-of frame) [:ticket :assignee]))
                "the runtime's stale fence, not a generation check this
                 application wrote — `events/saved` contains none")))))))
