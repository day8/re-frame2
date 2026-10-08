(ns re-frame.recipes.async-nav-l0-cljs-test
  "THE THREE RECIPES' MODEL TIER, with no DOM: recipe 1's settle-merge and
  reply correlation, recipe 2's per-instance mutation status, and recipe
  3's guard sub. The navigation that guard holds — blocked, parked,
  continued, cancelled — and the address bar it puts back are
  `re-frame.recipes.async-nav-guard-dom-cljs-test`'s, in a real browser.

  Every reply is replayed by hand through the captured transport args, in
  the order the row chooses, inside `dispatch-sync`. Ordering a late
  arrival by hand is more precise than racing two timers, and an `async`
  row under the wrong fixture arrangement can abort the whole run."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            ;; The production managed-HTTP fx surface, so the recipes'
            ;; requests lower. The fetch never happens — `capture-transport!`
            ;; replaces the effect with a recorder and the rows replay the
            ;; transport's own reply shape.
            [re-frame.http.managed]
            [re-frame.recipes.async-nav :as rf.recipes.async-nav]
            ;; Side-effect require: the routing artefact's handlers.
            [re-frame.routing]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (rf.recipes.async-nav/register-routes!)
                      (rf.recipes.async-nav/register-resources!)
                      ;; No browser here, so routing's URL effects have
                      ;; nothing to drive. Registering no-ops keeps the
                      ;; navigation cascade whole without pretending the
                      ;; address bar moved.
                      (rf.fx/reg-fx :rf.nav/push-url
                                 {:platforms #{:server :client}} (fn [_ _] nil))
                      (rf.fx/reg-fx :rf.nav/replace-url
                                 {:platforms #{:server :client}} (fn [_ _] nil)))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(def ^:private !requests
  "Every `:rf.http/managed` argument map the recorder was handed, newest
  last. Each carries the reply targets the runtime built, which is what
  the rows replay."
  (atom []))

(defn- capture-transport!
  "Replace the managed-HTTP effect with a recorder. Everything above the
  transport still runs — request lowering, the mutation runtime's
  instance, its optimistic apply and its reply addressing; only the
  fetch is missing, and a row supplies the reply."
  []
  (reset! !requests [])
  (rf.fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! !requests conj args) nil)))

(defn- with-app [f]
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::rf.recipes.async-nav/seed]]})]
    (f frame)))

(defn- send! [frame event-v] (rf/dispatch-sync event-v {:frame frame}))
(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))
(defn- editor-of [frame] (read-sub frame [::rf.recipes.async-nav/editor]))

(defn- reply-ok! [frame args value]
  (send! frame (conj (:on-success args) {:status :ok :value value})))

(defn- reply-error! [frame args error]
  (send! frame (conj (:on-failure args) {:status :error :error error})))

(def ^:private welcome
  {:title "Welcome" :body "The server's copy of the body"})

;; ---------------------------------------------------------------------------
;; RECIPE 1 — settle-merge and reply correlation
;; ---------------------------------------------------------------------------

(deftest settle-merge-seeds-only-the-fields-nobody-touched
  (is (= welcome (rf.recipes.async-nav/settle-merge {:title "My own title"} welcome #{}))
      "with nothing touched the payload wins every key — the recipe IS the
       naive whole-slice write, which is why that defect survives every load
       that beats the user to the keyboard")
  (is (= {:title (:title welcome) :body "typed" :tags "kept"}
         (rf.recipes.async-nav/settle-merge {:body "typed" :tags "kept"} welcome #{:body}))
      "a touched field keeps what the user typed, an untouched one is seeded,
       and a draft key the payload does not carry is left alone"))

(deftest a-late-reply-cannot-clobber-a-field-the-user-touched
  ;; R-C1, on the paved path.
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::rf.recipes.async-nav/open-editor "welcome"])
      (let [args (last @!requests)]
        (send! frame [::rf.recipes.async-nav/edit :title "My own title"])
        (reply-ok! frame args welcome)
        (is (= {:slug     "welcome"
                :draft    {:title "My own title" :body (:body welcome)}
                :baseline welcome
                :touched  #{:title}}
               (editor-of frame))
            "the keystroke survived the settle, the untouched field was seeded,
             and the baseline took the payload WHOLE — a half-updated baseline
             would report saved work as dirty for the rest of the session")))))

(deftest a-reply-for-an-article-the-editor-has-left-is-dropped
  ;; The half the runtime does NOT own. Two articles are two `:request-id`s,
  ;; so nothing was superseded — the first request was abandoned, and an
  ;; abandoned request still replies.
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::rf.recipes.async-nav/open-editor "welcome"])
      (let [first-args     (last @!requests)
            second-article {:title "Second" :body "Second body"}]
        (send! frame [::rf.recipes.async-nav/open-editor "second"])
        (let [second-args (last @!requests)]
          (is (not= (:request-id first-args) (:request-id second-args))
              "precondition: two articles are two request ids, so the
               runtime's same-entry fence has nothing to suppress here")
          (reply-ok! frame second-args second-article)
          (reply-ok! frame first-args welcome)
          (is (= {:slug "second" :draft second-article :baseline second-article :touched #{}}
                 (editor-of frame))
              "the abandoned article's late reply changed nothing, the
               baseline included"))))))

(deftest a-failed-load-keeps-the-users-work
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::rf.recipes.async-nav/open-editor "welcome"])
      (let [args (last @!requests)]
        (send! frame [::rf.recipes.async-nav/edit :title "My own title"])
        (reply-error! frame args {:message "boom"})
        (is (= {:slug         "welcome"
                :draft        {:title "My own title"}
                :baseline     {}
                :touched      #{:title}
                :load-failed? true}
               (editor-of frame)))))))

;; ---------------------------------------------------------------------------
;; RECIPE 2 — per-instance mutation status, and the optimistic write
;; ---------------------------------------------------------------------------

(defn- status [frame slug ks]
  (select-keys (read-sub frame [:rf/mutation {:instance (rf.recipes.async-nav/favourite-instance slug)}])
               ks))

(deftest two-rows-in-flight-do-not-share-a-status
  ;; R-C5. A shared instance makes every row spin because any row is, and
  ;; paints one row's rejection on its neighbour.
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::rf.recipes.async-nav/toggle-favourite "welcome" true])
      (is (= {:pending? true :optimistic? true}
             (status frame "welcome" [:pending? :optimistic?]))
          "in flight, and already showing the user's change, from one read")
      (let [welcome-args (last @!requests)]
        (send! frame [::rf.recipes.async-nav/toggle-favourite "second" true])
        (reply-error! frame welcome-args {:message "rejected"})
        (is (= {:error? true :pending? false :optimistic? false}
               (status frame "welcome" [:error? :pending? :optimistic?]))
            "the rejection clears busy and the optimistic flag in the same read")
        (is (= {:error? false :pending? true}
               (status frame "second" [:error? :pending?]))
            "and the neighbour is untouched: still in flight, no error painted")
        (is (= :error (get-in (rf/app-db-value frame) [:last-settled "welcome"]))
            "and the reply was ADDRESSED — an unaddressed managed reply is
             silenced")))))

;; ---------------------------------------------------------------------------
;; RECIPE 3 — the dirty-navigation guard
;; ---------------------------------------------------------------------------

(deftest the-guard-and-the-badge-read-one-definition
  ;; R-A6: the guard and the badge read one `dirty?`, so they cannot drift.
  ;; Strict booleans both ways — a non-boolean guard fails closed.
  (with-app
    (fn [frame]
      (let [reads #(mapv (partial read-sub frame)
                         [[::rf.recipes.async-nav/can-leave?] [::rf.recipes.async-nav/dirty?]])]
        (is (= [true false] (reads)) "clean: leaving is fine, and no badge")
        (send! frame [::rf.recipes.async-nav/edit :title "My own title"])
        (is (= [false true] (reads)) "dirty: the guard holds, and the badge shows")))))

(deftest a-saved-draft-leaves-freely
  ;; R-C9: a guard written against "has the user ever typed?" traps a
  ;; just-saved draft in its own editor.
  (with-app
    (fn [frame]
      (send! frame [:rf.route/navigate {:to rf.recipes.async-nav/editor-route :params {:slug "welcome"}}])
      (send! frame [::rf.recipes.async-nav/edit :title "My own title"])
      (send! frame [::rf.recipes.async-nav/save])
      (send! frame [:rf.route/navigate {:to rf.recipes.async-nav/list-route}])
      (is (= rf.recipes.async-nav/list-route (read-sub frame [:rf.route/id]))))))
