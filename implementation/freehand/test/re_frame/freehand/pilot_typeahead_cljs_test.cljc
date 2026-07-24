(ns re-frame.freehand.pilot-typeahead-cljs-test
  "CASE C, the HEADLESS half — every §C.2 requirement of the fitness
  harness enumerated one test per row, and the SIX async races the bead
  names, proven without a browser.

  The races are the point. A typeahead that works when the network is fast
  proves nothing; what silently corrupts one is a reply outrunning a
  keystroke, a cancelled request answering anyway, or a settle seeding
  state the user has since typed over. So the application's search
  performs no transport at all: it records the request and the reply
  prefix it was handed, and each row below dispatches the reply BY HAND,
  in the order the race requires. Latency becomes a variable rather than a
  wait, and every row is deterministic.

  It runs on the JVM and in Node from one `.cljc`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.pilot-typeahead :as ui]
            [re-frame.freehand.test :as t]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; Seams
;; ---------------------------------------------------------------------------

(def ^:private fid :rf/default)
(def ^:private doc-id :doc-1)
(def ^:private address [:doc doc-id :reviewer])
(def ^:private k [ui/typeahead-kind address])

(defn- seed! [db] (frame/replace-app-db! fid db))
(defn- app-db [] (frame/frame-app-db-value fid))
(defn- record [] (get-in (app-db) [ui/records-root k]))
(defn- send! [ev] (rf/dispatch-sync ev {:frame fid}))
(defn- requests [] (get (app-db) :re-frame.freehand.pilot-typeahead/requests []))

(defn- render!
  [form]
  (let [cand (cell/candidate (cell/cell :acme/probe) fid)]
    (cell/with-capture cand (fn [] (t/render form)))))

(defn- part?
  [p node]
  (and (map? node) (= p (get (t/attrs node) :data-part))))

(defn- node-with-part [tree p] (t/find tree (partial part? p)))
(defn- nodes-with-part [tree p] (t/find-all tree (partial part? p)))
(defn- attrs-of [tree p] (t/attrs (node-with-part tree p)))

(def ^:private results-a
  [{:value "amir"  :label "Amir Haddad"}
   {:value "anna"  :label "Anna Novak"}])

(def ^:private results-an
  [{:value "anna"  :label "Anna Novak"}])

(defn- init! []
  (ui/register!)
  (ui/register-app!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter
                                            :init-fn init!}))

;; ---------------------------------------------------------------------------
;; Driving the control the way a user and a network do
;; ---------------------------------------------------------------------------

(def ^:private never-fires-ms
  "The quiet period every row below types with.

  `:dispatch-later` is the REAL framework effect, so a keystroke really
  does arm a real host timer — and a test that let one fire would have the
  clock racing its own assertions, which is precisely the
  non-determinism these rows exist to remove. So the quiet period is set
  past the run, and each row delivers the delayed event by hand. The
  schedule itself is asserted as data in the R-C7 row, from the effects
  the handler returns."
  60000)

(defn- type!
  "One keystroke: the event the control's own `:on-input` site carries,
  with the live value filled in."
  ([text] (type! text 0))
  ([text revision]
   (send! [:acme.ui.typeahead/typed k revision never-fires-ms
           [:app/search-requested] text])))

(defn- fire-debounce!
  "The delayed dispatch the keystroke scheduled, delivered by hand. This is
  exactly the event `:dispatch-later` would deliver — the same handler,
  the same arguments — so driving it here tests the production path with
  the clock as a parameter rather than a wait."
  [token]
  (send! [:acme.ui.typeahead/due k token [:app/search-requested]]))

(defn- reply!
  "The caller answering a request it was handed, by conj-ing its outcome
  onto the reply prefix the library gave it. Nothing here reaches into the
  control."
  [request outcome]
  (send! (conj (vec (:reply-to request)) outcome)))

;; ===========================================================================
;; THE SIX RACES
;; ===========================================================================

(deftest race-1-debounce-cancellation-a-superseded-keystroke-asks-nothing
  (testing "RACE 1 — DEBOUNCE CANCELLATION. Two keystrokes inside one
            quiet period arm two delayed dispatches. When the first fires
            it is no longer current, so it asks nothing and leaves nothing
            behind; only the second reaches the network. There is no timer
            handle, no channel and no cancel call — a superseded schedule
            is inert BY COMPARISON, which is the only kind of cancellation
            that cannot leak."
    (seed! {})
    (type! "a")
    (let [first-token (:token (record))]
      (type! "an")
      (let [second-token (:token (record))]
        (is (not= first-token second-token) "the second keystroke moved the token")

        (fire-debounce! first-token)
        (is (empty? (requests)) "the superseded schedule asked nothing")
        (is (nil? (:in-flight (record))) "and marked nothing in flight")

        (fire-debounce! second-token)
        (is (= 1 (count (requests))) "the current one asked, exactly once")
        (is (= "an" (:query (first (requests)))) "for what the user has actually typed")
        (is (= {:token second-token :query "an"} (:in-flight (record)))))

      (testing "and firing the superseded schedule AGAIN, after the real
                request is out, still does nothing"
        (let [before (app-db)]
          (fire-debounce! first-token)
          (is (= before (app-db))))))))

(deftest race-2-correlation-a-reply-names-the-request-it-answers
  (testing "RACE 2 — CORRELATION. The library hands the caller a REPLY
            PREFIX carrying the record key and the token, so the caller
            cannot lose the correlation — it never constructs one. A reply
            that names a different token is not this request's answer and
            is dropped, which is what makes the uncorrelated-reply defect
            (the corpus retrofitted it twice) unavailable here."
    (seed! {})
    (type! "an")
    (fire-debounce! (:token (record)))
    (let [req   (first (requests))
          token (:token (:in-flight (record)))]
      (is (= [:acme.ui.typeahead/replied k token] (:reply-to req))
          "the reply target names the control AND the request")

      (let [before (app-db)]
        (send! [:acme.ui.typeahead/replied k (+ token 99) {:results results-a}])
        (is (= before (app-db)) "a reply naming another token lands nowhere"))

      (reply! req {:results results-an})
      (is (= results-an (:results (record))) "and the correlated one lands")
      (is (= "an" (:results-for (record))) "tagged with the question it answers"))))

(deftest race-3-supersession-the-slower-earlier-request-cannot-win
  (testing "RACE 3 — SUPERSESSION. The classic corruption: request 1 goes
            out, the user types, request 2 goes out and answers FIRST, and
            then request 1's slower answer arrives and overwrites it. Here
            request 1's answer names a token nothing is waiting for, so it
            is inert — the list keeps the newer answer."
    (seed! {})
    (type! "a")
    (fire-debounce! (:token (record)))
    (let [req-1 (first (requests))]
      (type! "an")
      (fire-debounce! (:token (record)))
      (let [req-2 (second (requests))]
        (is (= 2 (count (requests))) "two requests really did go out")

        (reply! req-2 {:results results-an})
        (is (= results-an (:results (record))) "the newer answer landed")

        (reply! req-1 {:results results-a})
        (is (= results-an (:results (record)))
            "and the older, slower answer did NOT overwrite it")
        (is (= "an" (:results-for (record)))
            "the visible answer still answers the current question")))))

(deftest race-3b-an-obsolete-request-is-inert-before-any-newer-debounce-fires
  (testing "RACE 3, the window RACE 3 does not close. RACE 3 lets the
            newer keystroke's debounce FIRE — so the newer request claims
            `:in-flight` and the older reply names a token nothing is
            waiting for. But typing supersedes the older request the
            instant the keystroke lands, not when the next debounce fires;
            between them the older request must ALREADY be inert. A reply
            that lands in that window, for a query the user has since typed
            over and RETURNED to, must not be believed just because the
            text matches again.

            Drive it exactly: type `a` (token 1), fire its debounce so
            token 1 is the claimed request, type `an` then `a` again
            WITHOUT firing either newer debounce, then deliver token 1's
            reply. It answers a question three keystrokes stale; it is
            dropped, and nothing of its becomes visible merely because the
            current text is `a` once more."
    (seed! {})
    (type! "a")
    (fire-debounce! (:token (record)))
    (let [req-1 (first (requests))]
      (is (= 1 (:token (:in-flight (record)))) "token 1 is the claimed request")

      (type! "an")
      (type! "a")
      (is (= 3 (:token (record))) "two more keystrokes moved the record past token 1")
      (is (= "a" (:query (record))) "and the current query is `a` once more")

      ;; The obsolete reply lands in the window before either newer
      ;; debounce has fired. It names token 1 — which the record has left.
      (reply! req-1 {:results results-a})
      (is (nil? (:results-for (record)))
          "the obsolete reply tagged NO answer — it was inert, not stored")
      (is (empty? (:results (record)))
          "and left no results behind")
      (let [st (rf/subscribe-once [:acme.ui.typeahead/status k 0] {:frame fid})]
        (is (empty? (:results st))
            "nothing of token 1's answer is visible, though the text is `a` again")
        (is (not (some #{"Amir Haddad"} (map :label (:results st))))
            "specifically the token-1 results (results-a) are NOT shown"))

      ;; Non-vacuity, same row: the LATEST token's due and reply land and
      ;; become visible normally, so the drop above is selective, not a
      ;; blanket refusal.
      (fire-debounce! (:token (record)))
      (let [req-2 (last (requests))]
        (is (= 3 (:token (:in-flight (record)))) "the latest keystroke's request is now in flight")
        (reply! req-2 {:results results-an})
        (is (= results-an (:results (record))) "the current token's answer lands")
        (is (= "a" (:results-for (record))) "tagged with the question it answers")
        (let [st (rf/subscribe-once [:acme.ui.typeahead/status k 0] {:frame fid})]
          (is (= results-an (:results st)) "and becomes visible normally"))))))

(deftest race-4-stale-completion-cannot-clobber-what-the-user-typed
  (testing "RACE 4 — STALE COMPLETION, which is R-C1 exactly. A settle
            arrives while the user is mid-word. The baseline FAILS this:
            the accepted reply replaces the whole slice and the keystrokes
            typed since are discarded. Here a settle has nowhere to write
            the typed text — it writes a result set tagged with the query
            it answers — so the draft is untouched, and the list is NOT
            shown, because it answers a question the user has moved past."
    (seed! {})
    (type! "a")
    (fire-debounce! (:token (record)))
    (let [req (first (requests))]
      ;; The user types on while the request is out.
      (type! "ann")
      (reply! req {:results results-a})

      (is (= "ann" (:query (record)))
          "the settle did not touch a single character the user typed")
      (let [st (rf/subscribe-once [:acme.ui.typeahead/status k 0] {:frame fid})]
        (is (empty? (:results st))
            "and its results are not shown — they answer 'a', not 'ann'")
        (is (true? (:stale? st))
            "the control says so: there is a question with no answer yet")))))

(deftest race-5-retry-takes-a-new-token-so-the-failure-cannot-resurrect
  (testing "RACE 5 — RETRY. A failed request is retried, and the retry is
            a NEW request with a NEW token. That is what makes the failed
            one's late answer inert: cancellation is best-effort
            everywhere, so the design has to survive its failure rather
            than assume it."
    (seed! {})
    (type! "an")
    (fire-debounce! (:token (record)))
    (let [req-1 (first (requests))]
      (reply! req-1 {:results [] :error "timeout"})
      (is (= "timeout" (:error (record))) "the failure is the control's own state")

      (send! [:acme.ui.typeahead/retried k [:app/search-requested]])
      (is (nil? (:error (record))) "the retry cleared the error")
      (is (= 2 (count (requests))) "and asked again, immediately — no second quiet period")

      (let [req-2 (second (requests))]
        (is (not= (:reply-to req-1) (:reply-to req-2))
            "the retry carries a different reply target")

        ;; The FIRST request finally answers, long after everyone moved on.
        (let [before (app-db)]
          (reply! req-1 {:results results-a})
          (is (= before (app-db)) "the abandoned request's answer is inert"))

        (reply! req-2 {:results results-an})
        (is (= results-an (:results (record))) "and the retry's answer lands")
        (is (nil? (:error (record))) "with the error gone")))))

(deftest race-6-release-ends-the-lifetime-on-every-path
  (testing "RACE 6 — UNMOUNT / RELEASE. Whoever mints a lifetime needs an
            end event that covers EVERY exit path, and after it a
            scheduled search, an in-flight reply and a stray keypress must
            all find nothing. One event does all of it, and the row drives
            each survivor separately rather than asserting a single
            absence."
    (seed! {})
    (type! "an")
    (let [token (:token (record))]
      (fire-debounce! token)
      (let [req (first (requests))]
        (is (some? (record)) "live")

        (send! [:acme.ui.typeahead/released k])
        (is (nil? (record)) "released — the record is gone, not blanked")
        (is (empty? (get (app-db) ui/records-root))
            "and the library's root holds nothing")

        (let [before (app-db)]
          (reply! req {:results results-a})
          (is (= before (app-db)) "the in-flight reply landed nowhere"))

        (let [before (app-db)]
          (fire-debounce! token)
          (is (= before (app-db)) "the scheduled search asked nothing"))

        (let [before (app-db)]
          (send! [:acme.ui.typeahead/key-pressed k 0 [:app/reviewer-chosen doc-id] "Enter"])
          (is (= before (app-db)) "and a late keypress committed nothing"))))))

(deftest race-6b-every-exit-path-reaches-the-same-end-event
  (testing "RACE 6, the other half. `released` only helps if every exit
            dispatches it. The application's route-leave does — which is
            the corpus's own doctrine (the causal end event is the leave,
            not a lifecycle hook) — and the row proves the leave really
            releases rather than merely being named as the place it could."
    (seed! {})
    (type! "an")
    (is (some? (record)))
    (send! [:app/left doc-id k])
    (is (nil? (record)) "leaving the route released the control")))

;; ===========================================================================
;; §C.2 — one row per requirement
;; ===========================================================================

(deftest r-c1-a-late-arrival-cannot-clobber-user-input
  (testing "R-C1. Covered mechanically by RACE 4; stated here as its own
            row because the harness enumerates it, and asserted from the
            other direction: the settle path has NO write to the query at
            all, whatever order things arrive in."
    (seed! {})
    (type! "a")
    (fire-debounce! (:token (record)))
    (let [req (first (requests))]
      (doseq [more ["an" "ann" "anna"]]
        (type! more))
      (reply! req {:results results-a})
      (is (= "anna" (:query (record)))
          "three keystrokes after the request went out, and all three survive"))))

(deftest r-c2-reply-correlation-is-the-paved-path-not-a-lesson
  (testing "R-C2. The correlation is not something the caller remembers to
            do: the reply prefix arrives WITH the request, carrying the
            control and the token, so an uncorrelated reply is not a
            mistake a caller can make."
    (seed! {})
    (type! "an")
    (fire-debounce! (:token (record)))
    (let [[_ target token] (:reply-to (first (requests)))]
      (is (= k target) "the control is in the reply target")
      (is (int? token) "and so is the request's own token"))))

(deftest r-c3-the-lifetime-has-one-causal-owner-with-one-end-event
  (testing "R-C3. The failure this guards is a lifetime whose end was
            removed and not re-homed. There is exactly one end event, it is
            registered, and both exit paths the application has reach it."
    (seed! {})
    (type! "an")
    (send! [:acme.ui.typeahead/released k])
    (is (nil? (record)))
    (seed! {})
    (type! "an")
    (send! [:app/left doc-id k])
    (is (nil? (record)) "the route leave is the same end event, not a second one")))

(deftest r-c4-cancellation-is-best-effort-and-the-design-survives-its-failure
  (testing "R-C4. Nothing here cancels anything: a superseded schedule
            fires, a supersedeed request answers, a released control's
            reply arrives. Every one of them is guarded rather than
            assumed away — which is the only posture that survives a
            transport that ignores an abort."
    (seed! {})
    (type! "a")
    (fire-debounce! (:token (record)))
    (let [req (first (requests))]
      (send! [:acme.ui.typeahead/released k])
      (let [before (app-db)]
        (reply! req {:results results-a})
        (is (= before (app-db)) "the answer to a cancelled request is inert")))))

(deftest r-c5-per-instance-async-status-is-collision-free-at-n-instances
  (testing "R-C5. Two controls on one page, each with its own in-flight
            request, its own error and its own results — addressed by the
            domain identity that owns them, with no registry ceremony and
            nothing to coordinate."
    (seed! {})
    (let [k1 [ui/typeahead-kind [:doc :doc-1 :reviewer]]
          k2 [ui/typeahead-kind [:doc :doc-2 :reviewer]]]
      (doseq [kk [k1 k2]]
        (send! [:acme.ui.typeahead/typed kk 0 never-fires-ms [:app/search-requested] "an"]))
      (doseq [kk [k1 k2]]
        (send! [:acme.ui.typeahead/due kk (:token (get-in (app-db) [ui/records-root kk]))
                [:app/search-requested]]))
      (is (= 2 (count (requests))) "two independent requests")
      (let [[r1 r2] (requests)]
        (reply! r1 {:results results-an})
        (reply! r2 {:results [] :error "timeout"})
        (is (= results-an (get-in (app-db) [ui/records-root k1 :results]))
            "the first has results")
        (is (nil? (get-in (app-db) [ui/records-root k1 :error]))
            "and no error")
        (is (= "timeout" (get-in (app-db) [ui/records-root k2 :error]))
            "the second has an error")
        (is (empty? (get-in (app-db) [ui/records-root k2 :results]))
            "and no results")))))

(deftest r-c6-an-optimistic-commit-rolls-back-without-disabling-the-control
  (testing "R-C6. The chosen value shows AT ONCE, marked unconfirmed, and
            the control stays usable. A refusal rolls back — and the
            rollback may reassert exactly the value that was there before,
            which is the case value-equality is blind to and the advanced
            revision is not."
    (seed! {:doc {doc-id {:reviewer "amir" :reviewer-revision 3 :reviewer-confirmed? true}}})
    (send! [:app/reviewer-chosen doc-id "anna"])
    (is (= "anna" (get-in (app-db) [:doc doc-id :reviewer])) "shown at once")
    (is (false? (get-in (app-db) [:doc doc-id :reviewer-confirmed?])) "marked unconfirmed")
    (is (= 4 (get-in (app-db) [:doc doc-id :reviewer-revision])) "a new baseline decision")

    (send! [:app/reviewer-refused doc-id "amir"])
    (is (= "amir" (get-in (app-db) [:doc doc-id :reviewer])) "rolled back")
    (is (= 5 (get-in (app-db) [:doc doc-id :reviewer-revision]))
        "and the revision advanced again, so a control holding a draft sees it")

    (testing "the same-value case: a refusal that reasserts what was
              already displayed is still VISIBLE to the control"
      (let [before (get-in (app-db) [:doc doc-id :reviewer])]
        (send! [:app/reviewer-refused doc-id before])
        (is (= before (get-in (app-db) [:doc doc-id :reviewer]))
            "non-vacuous: the value really did not move")
        (is (= 6 (get-in (app-db) [:doc doc-id :reviewer-revision]))
            "and the generation did, which is the whole signal")))))

(deftest r-c7-the-debounce-has-a-visible-home-and-a-stated-cost
  (testing "R-C7. The baseline hides debounce in core.async closures inside
            the component, invisible to tooling. Here it is an ordinary
            `:dispatch-later` effect the keystroke handler RETURNS — so a
            structural test reads the schedule as data, a trace shows it,
            and the cost (one armed timer per keystroke, N-1 of them
            no-ops) is countable rather than hidden."
    (seed! {})
    (let [handler  (registrar/handler :event :acme.ui.typeahead/typed)
          typed    (fn [text]
                     (handler {:db (app-db)}
                              [:acme.ui.typeahead/typed k 0 20 [:app/search-requested] text]))
          effects  (typed "an")
          [fx-id args] (first (:fx effects))]
      (is (= :dispatch-later fx-id)
          "the schedule is an ordinary framework effect the handler RETURNED")
      (is (= 20 (:ms args)) "carrying the quiet period as data")
      (is (= :acme.ui.typeahead/due (first (:event args)))
          "and the event it will deliver")
      (is (empty? (:fx (typed "")))
          "an empty query arms nothing — there is no question to ask"))))

(deftest r-c8-one-instance-identity-across-input-list-and-status
  (testing "R-C8. re-com's baseline coordinates eleven ratoms per
            instance. Here the input, the suggestion list and the status
            region are three parts of ONE view reading ONE record, and
            every intent any of them carries names the same record key —
            so there is nothing for the parts to disagree about."
    (seed! {:doc {doc-id {:reviewer "" :reviewer-revision 0}}})
    (send! [:acme.ui.typeahead/typed k 0 never-fires-ms [:app/search-requested] "an"])
    (fire-debounce! (:token (record)))
    (reply! (first (requests)) {:results results-an})
    (let [tree  (render! [ui/reviewer-form {:id doc-id}])
          input (attrs-of tree "input")
          opt   (t/attrs (first (nodes-with-part tree "option")))]
      (is (= "an" (:value input)) "the input shows the draft")
      (is (= 1 (count (nodes-with-part tree "option"))) "the list shows the answer")
      (is (= k (second (:on-input input))) "the input's intent names the record")
      (is (= k (second (:on-click opt))) "and so does the option's")
      (is (= k (second (:on-key-down input))) "and the keyboard's"))))

(deftest r-c9-a-dirty-control-blocks-the-leave-and-a-clean-one-does-not
  (testing "R-C9. Leaving with an unconfirmed write blocks, the
            confirmation is ordinary state-driven view code, and a
            just-confirmed value leaves freely. The gate is DERIVED and
            read by both the view and the guard handler — neither
            recomputes it."
    (seed! {:doc {doc-id {:reviewer "amir" :reviewer-confirmed? true}}})
    (send! [:app/leave-attempted doc-id k])
    (is (contains? (app-db) :re-frame.freehand.pilot-typeahead/left)
        "a clean form leaves")

    (seed! {:doc {doc-id {:reviewer "amir" :reviewer-confirmed? true}}})
    (send! [:app/reviewer-chosen doc-id "anna"])
    (send! [:app/leave-attempted doc-id k])
    (is (not (contains? (app-db) :re-frame.freehand.pilot-typeahead/left))
        "a dirty one does not")
    (is (true? (get (app-db) :re-frame.freehand.pilot-typeahead/leaving?))
        "and the confirm is ordinary state a view renders off")

    (send! [:app/reviewer-confirmed doc-id])
    (send! [:app/leave-attempted doc-id k])
    (is (contains? (app-db) :re-frame.freehand.pilot-typeahead/left)
        "and once the write confirms, the leave goes through")))

(deftest r-c10-the-whole-loop-is-provable-with-no-browser
  (testing "R-C10. Every step — keystroke, debounce, request, reply,
            freshness, selection, release — is an event or a subscription,
            so the whole loop is drivable and assertable here. That is the
            bar the corpus already set, and the row exists to prove the
            view-layer machinery did not lower it."
    (seed! {:doc {doc-id {:reviewer "" :reviewer-revision 0}}})
    (type! "an")
    (fire-debounce! (:token (record)))
    (reply! (first (requests)) {:results results-an})
    (send! [:acme.ui.typeahead/chosen k 0 0 [:app/reviewer-chosen doc-id]])
    (is (= "anna" (get-in (app-db) [:doc doc-id :reviewer]))
        "the caller's own state moved, through the caller's own event")
    (is (nil? (record)) "and the control retired itself with the commit")))

;; ===========================================================================
;; The composed control's view surface
;; ===========================================================================

(deftest the-controlled-input-site-is-a-literal-vector-so-the-door-stays-open
  (testing "The input is a controlled native carrying a LITERAL event
            vector at `:on-input`, which is what puts the site inside the
            substrate's synchronous door — the keystroke's state change
            lands before the listener returns, so React's end-of-event
            value restore finds what it just rendered. The library owns the
            site and carries the caller's intent as an ARGUMENT inside it,
            so a caller cannot spell its way out of the door."
    (seed! {:doc {doc-id {:reviewer "" :reviewer-revision 0}}})
    (let [tree  (render! [ui/reviewer-form {:id doc-id}])
          input (attrs-of tree "input")]
      (is (= :input (:tag (node-with-part tree "input"))) "a controlled native")
      (is (contains? input :value) "carrying a value")
      (is (vector? (:on-input input)) "and a literal event vector at the door slot")
      (is (= :acme.ui.typeahead/typed (first (:on-input input)))
          "the LIBRARY's own event, not the caller's")
      (is (= [:app/search-requested] (nth (:on-input input) 4))
          "with the caller's intent riding as an argument inside it")
      (is (= ::v/value (last (:on-input input)))
          "and the live value filled by the reserved projection"))))

(deftest the-status-region-is-derived-and-never-a-local-flag
  (testing "Busy discipline (R-A10's sibling in CASE C): the control shows
            `Searching…` off the in-flight request's OWN state and off the
            gap between what is typed and what is answered — never off a
            flag something has to remember to clear."
    (seed! {:doc {doc-id {:reviewer "" :reviewer-revision 0}}})
    (let [status #(t/text (node-with-part (render! [ui/reviewer-form {:id doc-id}]) "status"))]
      (is (= "" (status)) "idle")
      (type! "an")
      (is (= "Searching…" (status)) "typed, nothing answered yet")
      (fire-debounce! (:token (record)))
      (is (= "Searching…" (status)) "and in flight")
      (reply! (first (requests)) {:results results-an})
      (is (= "" (status)) "answered")
      (type! "ann")
      (is (= "Searching…" (status)) "typed again — the old answer is not this question's"))))
