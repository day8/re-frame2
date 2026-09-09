(ns re-frame.hicasso.examples.typeahead.l0-cljs-test
  "L0 — THE MODEL TIER AND THE DEFECT REACHABILITY DEMONSTRATIONS.

  `re-frame.hicasso.test`'s ladder names L0 as the tier the kit
  deliberately does not touch: an event handler is a function of `db` and
  an event vector, a subscription is a function of its inputs, a state
  transition is the pair. None of that needs a view substrate, so none of
  it is written with one, and this file `:require`s neither half of the
  test kit. Frame scope is the programmer's ordinary bracket,
  `rf/with-new-frame`.

  Two kinds of row live here, answering two criteria from
  `docs/design/hicasso/product/resource-demand-criteria.md` at its
  effective revision `afbb58febc`. C1's ceremony census is the `;; CENSUS`
  markers in `events.cljs` and `views.cljs`, published in
  `resource-demand-witness.md` with the marker ids as its citations;
  nothing here counts them.

  ## 1. The model — the application actually works

  Debounce, supersession, stale-reply suppression, refresh-with-data,
  cancellation and both resources' acquire and release paths, driven
  through a real frame so a registration that never happened cannot pass.

  ## 2. The reachability demonstrations — C2

  C2 admits a defect class only with *a mutation that makes the
  hand-written answer actually exhibit the defect*, so that an
  accidentally unreachable class cannot pass. The mutations are registered
  HERE rather than made by editing the application, and each is built from
  the application's own function with exactly one thing removed:

  - [[::unguarded-suggestions]] is `db/take-rows` with no correlation
    check — the P3 region gone;
  - [[::dismiss-without-release]] is `events/dismiss-fx` with its `:fx`
    dropped — the O5 region gone;
  - [[::typed-without-release]] is `events/typed-fx` with its one abandon
    entry filtered out — the O2 region gone, and the debounce it also
    emits untouched.

  No model logic is copied, so no arm can drift from another and the
  witness on disk stays the honest answer.

  ## Cancellation is best-effort; suppression is what makes it correct

  Worth reading before the stale-reply rows, because they look at first
  like they contradict the release rows. `::events/typed` abandons the
  request for the term the user typed past, so in the ordinary case the
  reply never arrives at all. That is not a guarantee: a request already
  on the wire cannot be un-sent, and an abandon is a `clearTimeout` here
  and an `AbortController` in a real client — both of which lose a race
  the network can win. The rows below therefore deliver the superseded
  reply BY HAND, which is exactly the case the abandon missed, and the
  guard is what decides it. The two mechanisms are independent and the
  census classifies them differently for that reason: the release is
  OWNERSHIP (work nobody reads should stop) and the guard is POLICY (an
  answer nobody awaits must not land).

  ## Requests are counted at the SERVICE, and the count is synchronous

  Every figure about work — how many requests a burst made, what is still
  armed after a release — is read from
  [[re-frame.hicasso.examples.typeahead.service]], which is the network.
  Both readings are taken on the line after the dispatch that moved them,
  because `rf/dispatch-sync` runs the effects too. Nothing here waits on a
  duration and nothing here reports one."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.typeahead.db :as rf.hicasso.examples.typeahead.db]
            [re-frame.hicasso.examples.typeahead.events :as rf.hicasso.examples.typeahead.events]
            [re-frame.hicasso.examples.typeahead.service :as rf.hicasso.examples.typeahead.service]
            [re-frame.hicasso.examples.typeahead.subs :as rf.hicasso.examples.typeahead.subs]
            [re-frame.test-support :as rf.test-support]))

;; ---------------------------------------------------------------------------
;; The mutations — the application's own functions, one thing removed
;;
;; REGISTERED ABOVE `use-fixtures`, and it is load-bearing:
;; `make-reset-runtime-fixture` restores the registrar to the baseline it
;; captured when the `use-fixtures` FORM was evaluated, so a handler
;; registered after that form is wiped before the first row runs and every
;; dispatch of it is a silent no-op. That is the slice authoring report's
;; third finding, met here for a third time.
;; ---------------------------------------------------------------------------

(rf/reg-event ::unguarded-suggestions
  {:doc "`::events/suggestions` WITHOUT its correlation check — the P3
         region deleted and nothing else. `db/take-rows` is the
         application's own fold, so this arm and the real one share every
         line but the `if`."}
  (fn [{:keys [db]} [_ reply]] {:db (rf.hicasso.examples.typeahead.db/take-rows db reply)}))

(rf/reg-event ::dismiss-without-release
  {:doc "`::events/dismiss` WITHOUT its release — the O5 region deleted
         and nothing else. The model half is the application's own
         function, called here and not copied."}
  (fn [{:keys [db]} _] (dissoc (rf.hicasso.examples.typeahead.events/dismiss-fx db) :fx)))

(rf/reg-event ::typed-without-release
  {:doc "`::events/typed` WITHOUT its release — the O2 region deleted and
         nothing else. The keystroke's own effect map, with the one
         `::service/abandon` entry filtered out, so the debounce it also
         emits is untouched and the mutation is exactly the release."}
  (fn [{:keys [db]} [_ typed]]
    (update (rf.hicasso.examples.typeahead.events/typed-fx db typed) :fx
            (fn [fx] (filterv #(not= ::rf.hicasso.examples.typeahead.service/abandon (first %)) fx)))))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       rf.hicasso.examples.typeahead.service/reset-log!}))

;; ---------------------------------------------------------------------------
;; Driving
;; ---------------------------------------------------------------------------

(defn- with-app
  "Run `f` against a fresh frame seeded the way the application boots, and
  clear the service afterwards.

  The service reset is what keeps a row's armed replies out of the next
  row: `rf/with-new-frame` cancels the frame's own `:dispatch-later`
  timers on destroy, but the stand-in service's timers are the service's."
  [f]
  (rf.hicasso.examples.typeahead.service/reset-log!)
  (try
    (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::rf.hicasso.examples.typeahead.events/seed]]})]
      (f frame))
    (finally (rf.hicasso.examples.typeahead.service/reset-log!))))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))

(defn- app-db [frame] (rf/app-db-value frame))

(defn- searches
  "The terms the application asked the service for, in order."
  []
  (mapv :param (filterv #(= :search (:kind %)) (rf.hicasso.examples.typeahead.service/requests))))

(defn- detail-asks
  "The ids the application asked the service for, in order."
  []
  (mapv :param (filterv #(= :detail (:kind %)) (rf.hicasso.examples.typeahead.service/requests))))

(defn- typed! [term] (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/typed term]))

(defn- burst!
  "Type `term` one character at a time, all in this turn. The debounce
  ticks are not fired here; [[tick!]] does that, because
  `:dispatch-later`'s host timer is a real one and this tier does not
  wait."
  [term]
  (doseq [n (range 1 (inc (count term)))]
    (typed! (apply str (take n term)))))

(defn- tick! [token] (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/search-due {:token token}]))

(defn- reply!
  [token term]
  (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/suggestions
                     {:token token :term term
                      :rows  [{:id "row" :name "Row"}]}]))

(defn- generation [frame] (get-in (app-db frame) [:search :generation]))

;; ---------------------------------------------------------------------------
;; The debounce figure, and the control that moves it
;; ---------------------------------------------------------------------------

(deftest a-burst-of-keystrokes-makes-exactly-one-request
  ;; Five keystrokes in one turn arm five ticks; the P2 guard drops the
  ;; four a later keystroke superseded.
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (burst! "cavil")
        (is (= [] (searches)) "no keystroke issues a request by itself")
        (doseq [token (range 1 6)] (tick! token))
        (is (= ["cavil"] (searches))
            "one request, for the term the user stopped on")))))

(deftest without-the-debounce-the-same-burst-makes-four
  ;; THE CONTROL. The P1 region is the `:dispatch-later` wrapper, so the
  ;; application with that site removed is the one whose tick fires
  ;; immediately — which is what this row drives. The count moves 1 -> 4
  ;; (four of the five prefixes are long enough to be worth asking about),
  ;; so the figure above is a measurement rather than a coincidence.
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (doseq [n (range 1 6)]
          (typed! (apply str (take n "cavil")))
          (tick! (generation frame)))
        (is (= ["ca" "cav" "cavi" "cavil"] (searches))
            "every keystroke past the threshold issues its own request")))))

;; ---------------------------------------------------------------------------
;; Stale-reply suppression — the bead's named acceptance, and its control
;; ---------------------------------------------------------------------------

(deftest a-late-reply-cannot-clobber-a-newer-term
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (is (= ["ca"] (searches)))

        (typed! "cat")
        (tick! 2)
        (reply! 2 "cat")
        (is (= "cat" (:term (:shown (:search (app-db frame))))))

        (testing "the superseded reply arrives anyway and is dropped"
          (reply! 1 "ca")
          (is (= "cat" (:term (:shown (:search (app-db frame)))))
              "the model still holds the rows for the term on screen")
          (is (some? (read-sub frame [::rf.hicasso.examples.typeahead.subs/suggestions "cat"]))
              "and the live read still answers")
          (is (nil? (read-sub frame [::rf.hicasso.examples.typeahead.subs/suggestions "ca"]))
              "while the superseded term answers nothing"))))))

(deftest without-the-guard-the-late-reply-clobbers
  ;; THE CONTROL for the row above: the P3 region deleted, nothing else.
  ;; The class is reachable, so it is admissible under C2.
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (typed! "cat")
        (tick! 2)
        (reply! 2 "cat")
        (rf/dispatch-sync [::unguarded-suggestions
                           {:token 1 :term "ca" :rows [{:id "row" :name "Row"}]}])
        (is (= "ca" (:term (:shown (:search (app-db frame)))))
            "the stale rows landed")
        (is (nil? (read-sub frame [::rf.hicasso.examples.typeahead.subs/suggestions "cat"]))
            "and the live read now answers NOTHING for the term the field
             holds, so the panel paints rows for a term the user has
             already typed past")))))

;; ---------------------------------------------------------------------------
;; Release — the three written sites, and the one deleted
;; ---------------------------------------------------------------------------

(deftest dismissing-releases-the-request
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (is (= #{1} (rf.hicasso.examples.typeahead.service/outstanding)) "the request is out")
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/dismiss])
        (is (= #{} (rf.hicasso.examples.typeahead.service/outstanding))
            "and the panel closing took it back down")
        (is (false? (read-sub frame [::rf.hicasso.examples.typeahead.subs/open?])))))))

(deftest without-the-release-site-the-request-survives-the-read
  ;; THE CONTROL, and C2's *missed release on a conditional-false read*.
  ;; The O5 region deleted, nothing else: the panel closes, the read is
  ;; gone, and the work continues.
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (rf/dispatch-sync [::dismiss-without-release])
        (is (false? (read-sub frame [::rf.hicasso.examples.typeahead.subs/open?])) "the read is gone")
        (is (= #{1} (rf.hicasso.examples.typeahead.service/outstanding))
            "and its request is still armed — residue that outlives the
             read, produced by deleting one line from one of three
             intents that each have to remember it")))))

(deftest typing-on-releases-the-superseded-request
  ;; OWNERSHIP release on parameter change, and it is a different fact
  ;; from the POLICY guard above: this row is about the WORK stopping, and
  ;; that one about the ANSWER being refused.
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (is (= #{1} (rf.hicasso.examples.typeahead.service/outstanding)))
        (typed! "cat")
        (is (= #{} (rf.hicasso.examples.typeahead.service/outstanding))
            "the request for the term the user typed past is abandoned at
             the keystroke")))))

(deftest without-the-release-the-superseded-request-runs-on
  ;; THE CONTROL, and C2's *orphaned in-flight request after a parameter
  ;; change*. The O2 region deleted, nothing else: the term moves, the old
  ;; request keeps running, and the page ends up paying for two round
  ;; trips where one read exists.
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (is (= #{1} (rf.hicasso.examples.typeahead.service/outstanding)))
        (rf/dispatch-sync [::typed-without-release "cat"])
        (is (= #{1} (rf.hicasso.examples.typeahead.service/outstanding))
            "the request for `ca` is still running, and nothing on screen
             will ever read its answer")
        (tick! 2)
        (is (= ["ca" "cat"] (searches)))
        (is (= #{1 2} (rf.hicasso.examples.typeahead.service/outstanding))
            "two requests in flight for one read — the waste this class
             names, produced by deleting one line")))))

(deftest clearing-releases-the-request-and-rebaselines-the-field
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (let [revision (read-sub frame [::rf.hicasso.examples.typeahead.subs/revision])]
          (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/clear])
          (is (= #{} (rf.hicasso.examples.typeahead.service/outstanding)))
          (is (= "" (read-sub frame [::rf.hicasso.examples.typeahead.subs/term])))
          (is (= (inc revision) (read-sub frame [::rf.hicasso.examples.typeahead.subs/revision]))
              "HD-019's reset: the field is handed an empty string it may
               already have been showing, so the revision is what makes it
               take it")
          (is (= :idle (read-sub frame [::rf.hicasso.examples.typeahead.subs/status]))))))))

;; ---------------------------------------------------------------------------
;; Refresh-with-data, and the acquire site's two answers
;; ---------------------------------------------------------------------------

(deftest refresh-with-data-keeps-the-rows-on-screen
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (reply! 1 "ca")
        (is (= :ready (read-sub frame [::rf.hicasso.examples.typeahead.subs/status])))

        (typed! "cav")
        (tick! 2)
        (is (= :refreshing (read-sub frame [::rf.hicasso.examples.typeahead.subs/status]))
            "a request is out and rows are already on screen")
        (is (nil? (read-sub frame [::rf.hicasso.examples.typeahead.subs/suggestions "cav"]))
            "the live read is honest: nothing answers the NEW term yet")
        (is (some? (read-sub frame [::rf.hicasso.examples.typeahead.subs/held-rows]))
            "and the held rows are what the panel keeps painting — the P5
             region in `views.cljs` is the decision between the two")))))

(deftest re-opening-over-an-answered-term-asks-nothing
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (reply! 1 "ca")
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/dismiss])
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/focus])
        (is (true? (read-sub frame [::rf.hicasso.examples.typeahead.subs/open?])) "the read is live again")
        (is (= ["ca"] (searches))
            "and the acquire site found the resource already answered, so
             it asked for nothing. THAT DECISION IS THE CEREMONY: the
             application has to reconstruct from `app-db` whether the read
             that just appeared is already satisfied")))))

(deftest re-opening-over-an-unanswered-term-asks-again
  ;; The other half of O4, so the row above cannot pass by the acquire
  ;; site being dead.
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/dismiss])
        (is (= #{} (rf.hicasso.examples.typeahead.service/outstanding)) "the dismissal released it")
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/focus])
        (is (= ["ca" "ca"] (searches))
            "so re-opening has to ask again — the release and the
             re-acquire are two hand-written sites and the round trip is
             paid twice")))))

;; ---------------------------------------------------------------------------
;; The second resource
;; ---------------------------------------------------------------------------

(deftest choosing-releases-the-suggestion-and-acquires-the-detail
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (is (= #{1} (rf.hicasso.examples.typeahead.service/outstanding)))
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/choose {:id "canid"}])
        (is (false? (read-sub frame [::rf.hicasso.examples.typeahead.subs/open?])))
        (is (= ["canid"] (detail-asks)) "the detail read's resource was asked for")
        (is (= #{(rf.hicasso.examples.typeahead.events/detail-token "canid")} (rf.hicasso.examples.typeahead.service/outstanding))
            "and the suggestion request is gone while the detail's is out")
        (is (= :pending (read-sub frame [::rf.hicasso.examples.typeahead.subs/detail "canid"])))))))

(deftest choosing-again-releases-the-detail-the-parameter-left-behind
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/choose {:id "canid"}])
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/choose {:id "cavil"}])
        (is (= #{(rf.hicasso.examples.typeahead.events/detail-token "cavil")} (rf.hicasso.examples.typeahead.service/outstanding))
            "the request for the id nobody reads any more was abandoned")
        (is (nil? (read-sub frame [::rf.hicasso.examples.typeahead.subs/detail "canid"]))
            "and its pending entry went with it, so a later choose asks
             again rather than waiting on an answer that was cancelled")))))

(deftest hover-prefetch-is-a-demand-no-read-expresses
  ;; C4's known out-of-scope case, exhibited rather than asserted: this
  ;; request exists while NOTHING reads the resource, so no read
  ;; membership could have implied it.
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/hover {:id "cavil"}])
        (is (= ["cavil"] (detail-asks)))
        (is (nil? (read-sub frame [::rf.hicasso.examples.typeahead.subs/chosen]))
            "nothing is chosen, so no boundary reads [::subs/detail
             \"cavil\"] — the demand has no reader at all")

        (testing "hovering again asks nothing"
          (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/hover {:id "cavil"}])
          (is (= ["cavil"] (detail-asks))))

        (testing "and choosing the warmed row asks nothing either"
          (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/choose {:id "cavil"}])
          (is (= ["cavil"] (detail-asks))
              "which is what a prefetch buys, and the reason it cannot be
               given up to a mechanism that only knows about reads"))))))

;; ---------------------------------------------------------------------------
;; The failure path
;; ---------------------------------------------------------------------------

(deftest a-refused-search-shows-a-problem-and-drops-a-stale-refusal
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "zzz")
        (tick! 1)
        (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/search-failed
                           {:token 1 :term "zzz" :problem :problem/service-down}])
        (is (= :failed (read-sub frame [::rf.hicasso.examples.typeahead.subs/status])))
        (is (= :problem/service-down (read-sub frame [::rf.hicasso.examples.typeahead.subs/problem])))

        (testing "a refusal for a request nobody awaits is dropped"
          (typed! "ca")
          (tick! 2)
          (rf/dispatch-sync [::rf.hicasso.examples.typeahead.events/search-failed
                             {:token 1 :term "zzz" :problem :problem/service-down}])
          (is (not= :failed (read-sub frame [::rf.hicasso.examples.typeahead.subs/status]))
              "the stale refusal did not put the panel back into error"))))))

;; ---------------------------------------------------------------------------
;; The pure model, with no runtime anywhere
;; ---------------------------------------------------------------------------

(deftest wanted-is-the-parameter-of-the-live-read
  ;; `db/wanted` is the one expression the whole experiment is about: the
  ;; term a live read wants. Under demand it would be read off the
  ;; committed read set instead of computed here.
  (is (nil? (rf.hicasso.examples.typeahead.db/wanted rf.hicasso.examples.typeahead.db/seed)) "a closed panel wants nothing")
  (is (nil? (rf.hicasso.examples.typeahead.db/wanted (assoc-in rf.hicasso.examples.typeahead.db/seed [:search :open?] true)))
      "nor does an open one over an empty field")
  (is (nil? (rf.hicasso.examples.typeahead.db/wanted (-> rf.hicasso.examples.typeahead.db/seed
                           (assoc-in [:search :open?] true)
                           (assoc-in [:search :term] "c"))))
      "nor over a term below the threshold")
  (is (= "ca" (rf.hicasso.examples.typeahead.db/wanted (-> rf.hicasso.examples.typeahead.db/seed
                             (assoc-in [:search :open?] true)
                             (assoc-in [:search :term] "  ca "))))
      "and it is trimmed, because a field holding two spaces holds
       nothing"))
