(ns re-frame.fresco.examples.typeahead.l0-cljs-test
  "L0 — THE MODEL TIER.

  `re-frame.fresco.test`'s ladder names L0 as the tier the kit
  deliberately does not touch: an event handler is a function of `db` and
  an event vector, a subscription is a function of its inputs, a state
  transition is the pair. None of that needs a view substrate, so none of
  it is written with one, and this file `:require`s neither half of the
  test kit. Frame scope is the programmer's ordinary bracket,
  `rf/with-new-frame`.

  C1 of `docs/design/fresco/product/resource-demand-criteria.md`, the
  ceremony census, is the `;; CENSUS` markers in `events.cljs` and
  `views.cljs`, published in `resource-demand-witness.md` with the marker
  ids as its citations; nothing here counts them.

  ## Stale-reply suppression, through a real frame

  [[a-late-reply-cannot-clobber-a-newer-term]] drives supersession and
  stale-reply suppression through a real frame, so a registration that
  never happened cannot pass.

  ## Cancellation is best-effort; suppression is what makes it correct

  `::events/typed` abandons the request for the term the user typed
  past, so in the ordinary case the reply never arrives at all. That is
  not a guarantee: a request already on the wire cannot be un-sent, and
  an abandon is a `clearTimeout` here and an `AbortController` in a real
  client — both of which lose a race the network can win. The row below
  therefore delivers the superseded reply BY HAND, which is exactly the
  case the abandon missed, and the guard is what decides it. The two
  mechanisms are independent and the census classifies them differently
  for that reason: the release is OWNERSHIP (work nobody reads should
  stop) and the guard is POLICY (an answer nobody awaits must not land).

  ## Requests are counted at the SERVICE, and the count is synchronous

  Every figure about work is read from
  [[re-frame.fresco.examples.typeahead.service]], which is the network.
  Each reading is taken on the line after the dispatch that moved it,
  because `rf/dispatch-sync` runs the effects too. Nothing here waits on a
  duration and nothing here reports one."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco.examples.typeahead.events :as rf.fresco.examples.typeahead.events]
            [re-frame.fresco.examples.typeahead.service :as rf.fresco.examples.typeahead.service]
            [re-frame.fresco.examples.typeahead.subs :as rf.fresco.examples.typeahead.subs]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       rf.fresco.examples.typeahead.service/reset-log!}))

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
  (rf.fresco.examples.typeahead.service/reset-log!)
  (try
    (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::rf.fresco.examples.typeahead.events/seed]]})]
      (f frame))
    (finally (rf.fresco.examples.typeahead.service/reset-log!))))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))

(defn- app-db [frame] (rf/app-db-value frame))

(defn- searches
  "The terms the application asked the service for, in order."
  []
  (mapv :param (filterv #(= :search (:kind %)) (rf.fresco.examples.typeahead.service/requests))))

(defn- typed! [term] (rf/dispatch-sync [::rf.fresco.examples.typeahead.events/typed term]))

(defn- tick! [token] (rf/dispatch-sync [::rf.fresco.examples.typeahead.events/search-due {:token token}]))

(defn- reply!
  [token term]
  (rf/dispatch-sync [::rf.fresco.examples.typeahead.events/suggestions
                     {:token token :term term
                      :rows  [{:id "row" :name "Row"}]}]))

;; ---------------------------------------------------------------------------
;; Stale-reply suppression
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
          (is (some? (read-sub frame [::rf.fresco.examples.typeahead.subs/suggestions "cat"]))
              "and the live read still answers")
          (is (nil? (read-sub frame [::rf.fresco.examples.typeahead.subs/suggestions "ca"]))
              "while the superseded term answers nothing"))))))
