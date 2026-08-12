(ns re-frame.hicasso.examples.typeahead.l0-cljs-test
  "L0 — THE MODEL TIER, THE CENSUS, AND THE DEFECT REACHABILITY
  DEMONSTRATIONS (rf2-hic-044).

  `re-frame.hicasso.test`'s ladder names L0 as the tier the kit
  deliberately does not touch: an event handler is a function of `db` and
  an event vector, a subscription is a function of its inputs, a state
  transition is the pair. None of that needs a view substrate, so none of
  it is written with one, and this file `:require`s neither half of the
  test kit. Frame scope is the programmer's ordinary bracket,
  `rf/with-new-frame`.

  Three kinds of row live here, answering three different criteria from
  `docs/design/hicasso/product/resource-demand-criteria.md` at its
  effective revision `afbb58febc`.

  ## 1. The census — C1

  [[census]] is read off the witness's own source at macro-expansion time
  by [[re-frame.hicasso.examples.typeahead.census]]. The rows below pin
  its shape and its counts, so a ceremony site deleted, added or
  re-classified reds this file by name rather than quietly moving a
  published number.

  ## 2. The model — the application actually works

  Debounce, supersession, stale-reply suppression, refresh-with-data,
  cancellation and both resources' acquire and release paths, driven
  through a real frame so a registration that never happened cannot pass.

  ## 3. The reachability demonstrations — C2

  C2 admits a defect class only with *a mutation that makes the
  hand-written answer actually exhibit the defect*, so that an
  accidentally unreachable class cannot pass. The mutations are registered
  HERE rather than made by editing the application, and each is built from
  the application's own function with exactly one thing removed —
  [[::unguarded-suggestions]] is `db/take-rows` with no correlation check,
  [[::dismiss-without-release]] is `events/dismiss-fx` with its `:fx`
  dropped. No model logic is copied, so neither arm can drift from the
  other and the witness on disk stays the honest answer.

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
            [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.typeahead.db :as db]
            [re-frame.hicasso.examples.typeahead.events :as events]
            [re-frame.hicasso.examples.typeahead.service :as service]
            [re-frame.hicasso.examples.typeahead.subs :as subs]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.hicasso.examples.typeahead.census :as c]))

;; ---------------------------------------------------------------------------
;; The mutations — the application's own functions, one thing removed
;;
;; REGISTERED ABOVE `use-fixtures`, and it is load-bearing:
;; `make-reset-runtime-fixture` restores the registrar to the baseline it
;; captured when the `use-fixtures` FORM was evaluated, so a handler
;; registered after that form is wiped before the first row runs and every
;; dispatch of it is a silent no-op. That is rf2-hic-025's third finding,
;; met here for a third time.
;; ---------------------------------------------------------------------------

(rf/reg-event ::unguarded-suggestions
  {:doc "`::events/suggestions` WITHOUT its correlation check — the P3
         region deleted and nothing else. `db/take-rows` is the
         application's own fold, so this arm and the real one share every
         line but the `if`."}
  (fn [{:keys [db]} [_ reply]] {:db (db/take-rows db reply)}))

(rf/reg-event ::dismiss-without-release
  {:doc "`::events/dismiss` WITHOUT its release — the O5 region deleted
         and nothing else. The model half is the application's own
         function, called here and not copied."}
  (fn [{:keys [db]} _] (dissoc (events/dismiss-fx db) :fx)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       service/reset-log!}))

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
  (service/reset-log!)
  (try
    (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::events/seed]]})]
      (f frame))
    (finally (service/reset-log!))))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))

(defn- app-db [frame] (rf/app-db-value frame))

(defn- searches
  "The terms the application asked the service for, in order."
  []
  (mapv :param (filterv #(= :search (:kind %)) (service/requests))))

(defn- detail-asks
  "The ids the application asked the service for, in order."
  []
  (mapv :param (filterv #(= :detail (:kind %)) (service/requests))))

(defn- typed! [term] (rf/dispatch-sync [::events/typed term]))

(defn- burst!
  "Type `term` one character at a time, all in this turn. The debounce
  ticks are not fired here; [[tick!]] does that, because
  `:dispatch-later`'s host timer is a real one and this tier does not
  wait."
  [term]
  (doseq [n (range 1 (inc (count term)))]
    (typed! (apply str (take n term)))))

(defn- tick! [token] (rf/dispatch-sync [::events/search-due {:token token}]))

(defn- reply!
  [token term]
  (rf/dispatch-sync [::events/suggestions
                     {:token token :term term
                      :rows  [{:id "row" :name "Row"}]}]))

(defn- generation [frame] (get-in (app-db frame) [:search :generation]))

;; ---------------------------------------------------------------------------
;; 1. The census — C1's instrument
;; ---------------------------------------------------------------------------

(def census
  "Every ceremony region in the witness, read off its source. **The whole
  application is scanned**, not the two files that happen to hold rows
  today: a site can only escape the count by being in a file this vector
  does not name, so the vector names them all."
  (c/emit-census
    '["re_frame/hicasso/examples/typeahead/db.cljs"
      "re_frame/hicasso/examples/typeahead/events.cljs"
      "re_frame/hicasso/examples/typeahead/service.cljs"
      "re_frame/hicasso/examples/typeahead/subs.cljs"
      "re_frame/hicasso/examples/typeahead/views.cljs"
      "re_frame/hicasso/examples/typeahead/app.cljs"]))

(def ^:private classes
  "C1's three, restated here because the emitting macro is a `.clj` and a
  ClojureScript namespace can call its macros but not read its vars. The
  macro already refuses an unknown class at expansion time; this is the
  runtime half of the same statement."
  #{"OWNERSHIP" "POLICY" "DOMAIN"})

(defn- ids-of [klass]
  (into (sorted-set) (comp (filter #(= klass (:class %))) (map :id)) census))

(deftest the-census-instrument-answered
  ;; Asserted BEFORE anything is asserted with it. An empty census passes
  ;; every classification check below vacuously, and a census that had
  ;; scanned one file would report a complete-looking count over a
  ;; fraction of the application.
  (testing "it is populated"
    (is (pos? (count census)) "the census is empty — the scan found nothing"))

  (testing "every row carries what a report needs to cite it"
    (doseq [{:keys [id class role label file from to lines]} census]
      (is (contains? classes class) (str id " is classified " class))
      (is (not (str/blank? role)) (str id " has no role"))
      (is (not (str/blank? label)) (str id " has no label"))
      (is (string? file) (str id " names no file"))
      (is (< 0 from to) (str id "'s marker lines are not in order"))
      (is (pos? lines)
          (str id " delimits an EMPTY region — a marker pair around
               nothing counts a site that is not there"))))

  (testing "more than one file contributed"
    ;; The positive control on the scan itself. Both the model tier and
    ;; the view tier hold ceremony, and a scan that had silently failed on
    ;; one file would still look like a healthy census.
    (is (= #{"re_frame/hicasso/examples/typeahead/events.cljs"
             "re_frame/hicasso/examples/typeahead/views.cljs"}
           (into #{} (map :file) census))
        "the set of files holding ceremony moved. That is a real finding
         either way — a new file grew a correlation region, or one lost
         its last — and the report's C1 table names the files it read")))

(deftest the-census-counts-are-pinned
  ;; THE PUBLISHED FIGURE. `docs/design/hicasso/product/resource-demand-witness.md`
  ;; publishes these ids and counts; this row is what stops the document
  ;; and the code disagreeing. Sets rather than totals, so a failure names
  ;; the row that moved instead of printing two integers.
  (testing "OWNERSHIP — the class demand could claim"
    (is (= #{"O1" "O2" "O3" "O4" "O5" "O6" "O7" "O8" "O9"} (ids-of "OWNERSHIP"))))

  (testing "POLICY — explicit under demand as well, so never claimable"
    (is (= #{"P1" "P2" "P3" "P4" "P5"} (ids-of "POLICY"))))

  (testing "DOMAIN — would exist under any mechanism"
    (is (= #{"X1"} (ids-of "DOMAIN"))))

  (testing "and nothing is classified twice or not at all"
    (is (= (count census) (count (distinct (map :id census)))))
    (is (= (set (map :id census))
           (into #{} (concat (ids-of "OWNERSHIP") (ids-of "POLICY") (ids-of "DOMAIN")))))))

(deftest ownership-has-both-an-acquire-and-a-release
  ;; C1 stops on an OWNERSHIP census that is empty or acquire-only: a
  ;; mechanism with nothing to release has nothing to buy.
  (let [ownership (filter #(= "OWNERSHIP" (:class %)) census)]
    (is (= {"release" 6 "acquire" 3} (frequencies (map :role ownership)))
        "the OWNERSHIP roles moved. Both halves must be non-empty for C1
         to be answerable at all, and the split is a published figure")))

;; ---------------------------------------------------------------------------
;; 2. The debounce figure, and the control that moves it
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
          (is (some? (read-sub frame [::subs/suggestions "cat"]))
              "and the live read still answers")
          (is (nil? (read-sub frame [::subs/suggestions "ca"]))
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
        (is (nil? (read-sub frame [::subs/suggestions "cat"]))
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
        (is (= #{1} (service/outstanding)) "the request is out")
        (rf/dispatch-sync [::events/dismiss])
        (is (= #{} (service/outstanding))
            "and the panel closing took it back down")
        (is (false? (read-sub frame [::subs/open?])))))))

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
        (is (false? (read-sub frame [::subs/open?])) "the read is gone")
        (is (= #{1} (service/outstanding))
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
        (is (= #{1} (service/outstanding)))
        (typed! "cat")
        (is (= #{} (service/outstanding))
            "the request for the term the user typed past is abandoned at
             the keystroke")))))

(deftest clearing-releases-the-request-and-rebaselines-the-field
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (let [revision (read-sub frame [::subs/revision])]
          (rf/dispatch-sync [::events/clear])
          (is (= #{} (service/outstanding)))
          (is (= "" (read-sub frame [::subs/term])))
          (is (= (inc revision) (read-sub frame [::subs/revision]))
              "HD-019's reset: the field is handed an empty string it may
               already have been showing, so the revision is what makes it
               take it")
          (is (= :idle (read-sub frame [::subs/status]))))))))

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
        (is (= :ready (read-sub frame [::subs/status])))

        (typed! "cav")
        (tick! 2)
        (is (= :refreshing (read-sub frame [::subs/status]))
            "a request is out and rows are already on screen")
        (is (nil? (read-sub frame [::subs/suggestions "cav"]))
            "the live read is honest: nothing answers the NEW term yet")
        (is (some? (read-sub frame [::subs/held-rows]))
            "and the held rows are what the panel keeps painting — the P5
             region in `views.cljs` is the decision between the two")))))

(deftest re-opening-over-an-answered-term-asks-nothing
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (reply! 1 "ca")
        (rf/dispatch-sync [::events/dismiss])
        (rf/dispatch-sync [::events/focus])
        (is (true? (read-sub frame [::subs/open?])) "the read is live again")
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
        (rf/dispatch-sync [::events/dismiss])
        (is (= #{} (service/outstanding)) "the dismissal released it")
        (rf/dispatch-sync [::events/focus])
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
        (is (= #{1} (service/outstanding)))
        (rf/dispatch-sync [::events/choose {:id "canid"}])
        (is (false? (read-sub frame [::subs/open?])))
        (is (= ["canid"] (detail-asks)) "the detail read's resource was asked for")
        (is (= #{(events/detail-token "canid")} (service/outstanding))
            "and the suggestion request is gone while the detail's is out")
        (is (= :pending (read-sub frame [::subs/detail "canid"])))))))

(deftest choosing-again-releases-the-detail-the-parameter-left-behind
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (typed! "ca")
        (tick! 1)
        (rf/dispatch-sync [::events/choose {:id "canid"}])
        (rf/dispatch-sync [::events/choose {:id "cavil"}])
        (is (= #{(events/detail-token "cavil")} (service/outstanding))
            "the request for the id nobody reads any more was abandoned")
        (is (nil? (read-sub frame [::subs/detail "canid"]))
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
        (rf/dispatch-sync [::events/hover {:id "cavil"}])
        (is (= ["cavil"] (detail-asks)))
        (is (nil? (read-sub frame [::subs/chosen]))
            "nothing is chosen, so no boundary reads [::subs/detail
             \"cavil\"] — the demand has no reader at all")

        (testing "hovering again asks nothing"
          (rf/dispatch-sync [::events/hover {:id "cavil"}])
          (is (= ["cavil"] (detail-asks))))

        (testing "and choosing the warmed row asks nothing either"
          (rf/dispatch-sync [::events/choose {:id "cavil"}])
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
        (rf/dispatch-sync [::events/search-failed
                           {:token 1 :term "zzz" :problem :problem/service-down}])
        (is (= :failed (read-sub frame [::subs/status])))
        (is (= :problem/service-down (read-sub frame [::subs/problem])))

        (testing "a refusal for a request nobody awaits is dropped"
          (typed! "ca")
          (tick! 2)
          (rf/dispatch-sync [::events/search-failed
                             {:token 1 :term "zzz" :problem :problem/service-down}])
          (is (not= :failed (read-sub frame [::subs/status]))
              "the stale refusal did not put the panel back into error"))))))

;; ---------------------------------------------------------------------------
;; The pure model, with no runtime anywhere
;; ---------------------------------------------------------------------------

(deftest wanted-is-the-parameter-of-the-live-read
  ;; `db/wanted` is the one expression the whole experiment is about: the
  ;; term a live read wants. Under demand it would be read off the
  ;; committed read set instead of computed here.
  (is (nil? (db/wanted db/seed)) "a closed panel wants nothing")
  (is (nil? (db/wanted (assoc-in db/seed [:search :open?] true)))
      "nor does an open one over an empty field")
  (is (nil? (db/wanted (-> db/seed
                           (assoc-in [:search :open?] true)
                           (assoc-in [:search :term] "c"))))
      "nor over a term below the threshold")
  (is (= "ca" (db/wanted (-> db/seed
                             (assoc-in [:search :open?] true)
                             (assoc-in [:search :term] "  ca "))))
      "and it is trimmed, because a field holding two spaces holds
       nothing"))
