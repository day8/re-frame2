(ns re-frame.hicasso.examples.todo.l0-cljs-test
  "L0 — THE HANDLERS, THE SUBSCRIPTIONS AND THE TRANSITIONS (rf2-hic-086).

  `re-frame.hicasso.test`'s ladder names L0 as the tier the kit
  deliberately does not touch: *an event handler is a function of `db`
  and an event vector; a subscription is a function of its inputs; a
  state transition is the pair.* None of that needs a view substrate, so
  none of it is written with one, and this file `:require`s neither half
  of the test kit.

  Frame scope is the programmer's ordinary bracket, `rf/with-new-frame`,
  exactly as the ladder says.

  ## Routes, at L0

  The filter is derived from the URL, so half the rows below navigate.
  They reach routing through its own public event, `[:rf.route/navigate
  {:to …}]`, dispatched into the frame — the same door the application's
  own `make-frame!` uses — which is why the fixture has to re-register
  the routes: `make-reset-runtime-fixture` restores the registrar to a
  baseline captured when the `use-fixtures` FORM was evaluated, and
  routes registered at namespace load are behind it. `reg-sub` and
  `reg-event` survive; routes do not. That is rf2-hic-025's third
  finding, met here on this file's first row and confirmed unchanged."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso.examples.todo.db :as db]
            [re-frame.hicasso.examples.todo.events :as events]
            [re-frame.hicasso.examples.todo.routes :as routes]
            [re-frame.hicasso.examples.todo.subs :as subs]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       routes/register!}))

(def ^:private titles ["milk" "bread" "jam"])

(defn- with-app
  "Run `f` against a fresh frame seeded with three to-dos, and destroy it
  afterwards. `f` takes the frame.

  Seeded through `[::events/seed …]` rather than `[:rf/set-db …]` so the
  rows exercise the handler the application actually boots with."
  [f]
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::events/seed titles]]})]
    (f frame)))

(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))

(defn- titles-of [frame query-v] (mapv :title (read-sub frame query-v)))

(defn- todos-of [frame] (:todos (rf/app-db-value frame)))

;; ---------------------------------------------------------------------------
;; Pure — the shape, with no runtime anywhere
;; ---------------------------------------------------------------------------

(deftest seeding-allocates-ids-that-are-not-positions
  (let [seeded (db/seed titles)]
    (is (= [1 2 3] (keys (:todos seeded)))
        "ids come from the allocator, so they survive a delete — which is
         what makes them a legal React key and an index illegal")
    (is (= 4 (:next-id seeded))
        "and the allocator does not reuse one")
    (is (= [false false false] (mapv :done? (vals (:todos seeded))))
        "nothing arrives done")
    (is (= "" (:new-todo seeded)))))

(deftest the-empty-shape-is-the-seed-of-nothing
  (is (= db/empty-db (db/seed [])))
  (is (= (sorted-map) (:todos db/empty-db))
      "a sorted map, so iteration order is insertion order without a
       second :order vector to keep in step with it"))

;; ---------------------------------------------------------------------------
;; Transitions — through a real frame, so a registration that never
;; happened cannot pass
;; ---------------------------------------------------------------------------

(deftest adding-trims-ignores-a-blank-and-empties-the-box
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (testing "a blank submission is ignored rather than refused"
          (rf/dispatch-sync [::events/typed "   "])
          (rf/dispatch-sync [::events/add])
          (is (= 3 (count (todos-of frame))) "nothing was added")
          (is (= "   " (read-sub frame [::subs/new-todo]))
              "and the box is left exactly as the user left it"))

        (testing "a real one is trimmed, appended and clears the box"
          (rf/dispatch-sync [::events/typed "  eggs  "])
          (rf/dispatch-sync [::events/add])
          (is (= ["milk" "bread" "jam" "eggs"] (titles-of frame [::subs/todos])))
          (is (= "" (read-sub frame [::subs/new-todo]))))))))

(deftest toggling-takes-the-value-rather-than-negating
  (with-app
    (fn [frame]
      (rf/with-frame frame
        ;; `::h/checked` hands the handler what the checkbox IS. Dispatching
        ;; the same value twice is therefore idempotent, where a `not` would
        ;; have flipped it back — which is the whole reason the marker beats
        ;; a negation in the handler.
        (rf/dispatch-sync [::events/toggle 2 true])
        (rf/dispatch-sync [::events/toggle 2 true])
        (is (= [false true false] (mapv :done? (read-sub frame [::subs/todos]))))
        (is (= 2 (read-sub frame [::subs/active-count])))
        (is (= 1 (read-sub frame [::subs/completed-count])))
        (is (false? (read-sub frame [::subs/all-done?])))

        (testing "toggle-all takes a value too"
          (rf/dispatch-sync [::events/toggle-all true])
          (is (true? (read-sub frame [::subs/all-done?])))
          (rf/dispatch-sync [::events/toggle-all false])
          (is (= 3 (read-sub frame [::subs/active-count]))))))))

(deftest an-empty-list-is-not-all-done
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::events/seed []]]})]
    (is (false? (read-sub frame [::subs/all-done?]))
        "otherwise the toggle-all box sits checked over nothing")
    (is (false? (read-sub frame [::subs/any?])))))

(deftest clearing-completed-keeps-the-rest-in-order
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (rf/dispatch-sync [::events/toggle 1 true])
        (rf/dispatch-sync [::events/toggle 3 true])
        (rf/dispatch-sync [::events/clear-completed])
        (is (= ["bread"] (titles-of frame [::subs/todos])))
        (is (= [2] (keys (todos-of frame)))
            "the surviving row keeps its id, so its React key does not move")))))

(deftest destroying-removes-one-row
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (rf/dispatch-sync [::events/destroy 2])
        (is (= ["milk" "jam"] (titles-of frame [::subs/todos])))))))

;; ---------------------------------------------------------------------------
;; Edit in place — the reg-state pair, and the late-blur rule
;; ---------------------------------------------------------------------------

(deftest editing-is-the-presence-of-a-draft
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (is (nil? (read-sub frame [db/draft 2]))
            "not editing — the concern's default, read because the entry
             is ABSENT rather than because a nil was written")

        (testing "opening the editor and filling it are ONE write"
          (rf/dispatch-sync [db/draft 2 "bread"])
          (is (= "bread" (read-sub frame [db/draft 2])))
          (is (nil? (read-sub frame [db/draft 1]))
              "and it opened exactly one row — the instance key is the
               to-do's id, which is the whole of what h/reg-state buys"))

        (testing "committing writes the trimmed draft and closes the editor"
          (rf/dispatch-sync [db/draft 2 "  sourdough  "])
          (rf/dispatch-sync [::events/commit-edit 2])
          (is (= ["milk" "sourdough" "jam"] (titles-of frame [::subs/todos])))
          (is (nil? (read-sub frame [db/draft 2]))))))))

(deftest committing-a-blank-draft-destroys-the-row
  (with-app
    (fn [frame]
      (rf/with-frame frame
        (rf/dispatch-sync [db/draft 2 "   "])
        (rf/dispatch-sync [::events/commit-edit 2])
        (is (= ["milk" "jam"] (titles-of frame [::subs/todos]))
            "TodoMVC's own rule: editing a title away deletes the to-do")))))

(deftest a-commit-with-no-draft-does-nothing
  (with-app
    (fn [frame]
      (rf/with-frame frame
        ;; THE CANCEL-BEATS-LATE-BLUR ROW. Escape clears the draft; the
        ;; blur that follows dispatches the same commit. Reading the draft
        ;; from `app-db` rather than off the DOM event is what makes the
        ;; second one a no-op — the ordering problem is solved by the
        ;; model rather than by ordering.
        (rf/dispatch-sync [db/draft 2 "typed but cancelled"])
        (rf/dispatch-sync [:re-frame.hicasso/clear db/draft 2])
        (rf/dispatch-sync [::events/commit-edit 2])
        (is (= ["milk" "bread" "jam"] (titles-of frame [::subs/todos]))
            "the cancelled text did not reach the model")
        (is (= 3 (count (todos-of frame)))
            "and the row was not destroyed by a blank commit either")))))

;; ---------------------------------------------------------------------------
;; The filter is a URL
;; ---------------------------------------------------------------------------

(defn- with-app-at
  "Seeded, with one done to-do, and navigated to `nav-event`."
  [nav-event f]
  (rf/with-new-frame [frame (rf/make-frame
                              {:initial-events [[::events/seed titles]
                                                [::events/toggle 2 true]
                                                nav-event]})]
    (f frame)))

(deftest the-showing-filter-is-read-off-the-url
  (testing "the unparameterised route"
    (with-app-at [:rf.route/navigate {:to routes/all}]
      (fn [frame]
        (is (= :all (read-sub frame [::subs/showing])))
        (is (= ["milk" "bread" "jam"] (titles-of frame [::subs/visible]))))))

  (testing "active"
    (with-app-at [:rf.route/navigate {:to routes/filtered :params {:filter "active"}}]
      (fn [frame]
        (is (= :active (read-sub frame [::subs/showing])))
        (is (= ["milk" "jam"] (titles-of frame [::subs/visible]))))))

  (testing "completed"
    (with-app-at [:rf.route/navigate {:to routes/filtered :params {:filter "completed"}}]
      (fn [frame]
        (is (= :completed (read-sub frame [::subs/showing])))
        (is (= ["bread"] (titles-of frame [::subs/visible])))))))

(deftest a-filter-the-url-invented-shows-everything
  ;; A URL is user input. `/hicasso-todo/banana` matches the parameterised
  ;; route, so the coercion has to happen in the subscription that reads
  ;; the parameter rather than in the router that matched it.
  (with-app-at [:rf.route/navigate {:to routes/filtered :params {:filter "banana"}}]
    (fn [frame]
      (is (= :all (read-sub frame [::subs/showing])))
      (is (= 3 (count (read-sub frame [::subs/visible])))))))

(deftest no-route-at-all-shows-everything
  ;; This application registers no `:rf.route/not-found` — the id is
  ;; process-global and another application in this bundle holds it. An
  ;; unmatched URL therefore leaves the route id nil, which is the same
  ;; answer as an unrecognised filter and needs no separate branch.
  (with-app
    (fn [frame]
      (is (nil? (read-sub frame [:rf.route/id])))
      (is (= :all (read-sub frame [::subs/showing]))))))

(deftest the-application-never-stores-the-filter
  (with-app-at [:rf.route/navigate {:to routes/filtered :params {:filter "completed"}}]
    (fn [frame]
      (let [db (rf/app-db-value frame)]
        (is (= #{:todos :next-id :new-todo} (set (keys db)))
            "one fact, one home: the filter is on the URL, and a copy in
             app-db is a second place for it to be wrong")))))
