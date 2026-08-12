(ns re-frame.recipes.async-nav-l0-cljs-test
  "THE THREE RECIPES' MODEL TIER (rf2-hic-054).

  Every rule in `re-frame.recipes.async-nav` — first as pure functions,
  then through a real frame — with no DOM anywhere. The one claim that
  genuinely needs a browser (a real Back button, and the address bar the
  guard puts back) is
  `re-frame.recipes.async-nav-guard-dom-cljs-test`'s.

  ## Every row states what would make it red

  A witness that would pass with the feature deleted is worth nothing,
  and the async ones fail that way most easily: a reply that never
  arrives and a reply that arrives and is correctly ignored are the same
  observation from the outside. So recipe 1's two clobber rows come as a
  pair — the guarded one, and a CONTROL that performs the whole-slice
  write the recipe replaces and asserts the keystrokes are gone. The
  class is reachable on demand, which is what makes the green row mean
  something.

  ## Why no async row is used

  Every reply here is replayed by hand through the captured transport
  args, in the order the row chooses, inside `dispatch-sync`. That is
  not a convenience: an `async` row aborts the whole `test:browser` run
  on this tree if the fixture arrangement is wrong (rf2-u0j8, live), and
  ordering a late arrival by hand is also strictly more precise than
  racing two timers and hoping."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            ;; The production managed-HTTP fx surface, so the recipes'
            ;; requests lower. The fetch never happens — `capture-transport!`
            ;; replaces the effect with a recorder and the rows replay the
            ;; transport's own reply shape.
            [re-frame.http.managed]
            [re-frame.recipes.async-nav :as app]
            [re-frame.routing :as routing]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (app/register-routes!)
                      (app/register-resources!)
                      ;; No browser here, so routing's URL effects have
                      ;; nothing to drive. Registering no-ops keeps the
                      ;; navigation cascade whole without pretending the
                      ;; address bar moved.
                      (fx/reg-fx :rf.nav/push-url
                                 {:platforms #{:server :client}} (fn [_ _] nil))
                      (fx/reg-fx :rf.nav/replace-url
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
  (fx/reg-fx :rf.http/managed (fn [_ctx args] (swap! !requests conj args) nil)))

(defn- with-app [f]
  (rf/with-new-frame [frame (rf/make-frame {:initial-events [[::app/seed]]})]
    (f frame)))

(defn- send! [frame event-v] (rf/dispatch-sync event-v {:frame frame}))
(defn- read-sub [frame query-v] (rf/subscribe-once query-v {:frame frame}))
(defn- editor-of [frame] (read-sub frame [::app/editor]))

(defn- reply-ok! [frame args value]
  (send! frame (conj (:on-success args) {:status :ok :value value})))

(defn- reply-error! [frame args error]
  (send! frame (conj (:on-failure args) {:status :error :error error})))

(def ^:private welcome
  {:title "Welcome" :body "The server's copy of the body"})

;; ---------------------------------------------------------------------------
;; RECIPE 1 — pure
;; ---------------------------------------------------------------------------

(deftest settle-merge-seeds-only-the-fields-nobody-touched
  (testing "an untouched draft takes the whole payload"
    (is (= welcome (app/settle-merge {} welcome #{}))))

  (testing "a touched field keeps what the user typed"
    (is (= {:title "My own title" :body (:body welcome)}
           (app/settle-merge {:title "My own title"} welcome #{:title}))))

  (testing "every field touched — the payload seeds nothing at all"
    (is (= {:title "mine" :body "also mine"}
           (app/settle-merge {:title "mine" :body "also mine"}
                             welcome
                             #{:title :body}))))

  (testing "a field the payload does not carry is left exactly as it is"
    (is (= {:title (:title welcome) :body "typed" :tags "kept"}
           (app/settle-merge {:body "typed" :tags "kept"} welcome #{:body}))
        "the merge walks the PAYLOAD, so a draft key the server said
         nothing about is neither seeded nor cleared")))

(deftest the-whole-slice-write-is-what-the-merge-replaces
  ;; THE CONTROL, as a pure comparison: the two spellings agree exactly
  ;; where the typist lost the race, and disagree exactly where they did
  ;; not. Without this row, `settle-merge-seeds-only-the-fields-nobody-touched`
  ;; could be green against a defect that never had a chance to fire.
  (let [typed {:title "My own title"}]
    (is (= (app/settle-merge typed welcome #{})
           (merge typed welcome))
        "with nothing touched the recipe IS the naive write — which is why
         the defect survives every load that beats the user to the keyboard")
    (is (not= (app/settle-merge typed welcome #{:title})
              (merge typed welcome))
        "and with the field touched they part company")
    (is (= (:title welcome) (:title (merge typed welcome)))
        "the naive write hands the server the field the user was typing
         into — the discarded keystroke, spelled out")))

;; ---------------------------------------------------------------------------
;; RECIPE 1 — through a real frame
;; ---------------------------------------------------------------------------

(deftest the-reply-target-names-the-article-it-answers
  ;; R-C2, structurally. Without the slug in the reply target there is
  ;; nothing for the correlation gate below to compare, and the drop row
  ;; would be green because every reply looked current.
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/open-editor "welcome"])
      (is (= 1 (count @!requests)))
      (let [args (last @!requests)]
        (is (= [::app/article-arrived "welcome"] (:on-success args)))
        (is (= [::app/article-failed "welcome"] (:on-failure args)))
        (is (= [::app/article "welcome"] (:request-id args))
            "and the request id is per-slug, so re-opening the same article
             supersedes its own earlier request inside the runtime")))))

(deftest a-load-that-beats-the-typist-seeds-the-whole-form
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/open-editor "welcome"])
      (reply-ok! frame (last @!requests) welcome)
      (is (= welcome (:draft (editor-of frame))))
      (is (= welcome (:baseline (editor-of frame))))
      (is (false? (read-sub frame [::app/dirty?]))
          "and it arrives clean — a freshly-seeded editor the user has not
           touched must not hold the navigation guard shut"))))

(deftest a-late-reply-cannot-clobber-a-field-the-user-touched
  ;; R-C1, on the paved path.
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/open-editor "welcome"])
      (let [args (last @!requests)]
        (send! frame [::app/edit :title "My own title"])
        (reply-ok! frame args welcome)
        (is (= "My own title" (get-in (editor-of frame) [:draft :title]))
            "the keystrokes survived the settle — the whole recipe")
        (is (= (:body welcome) (get-in (editor-of frame) [:draft :body]))
            "and the field they did NOT touch was seeded, so the merge is
             leaf-wise rather than a refusal to seed at all")
        (is (= welcome (:baseline (editor-of frame)))
            "the baseline took the payload WHOLE — it is what the server
             said, and a half-updated baseline would report saved work as
             dirty for the rest of the session")
        (is (true? (read-sub frame [::app/dirty?]))
            "so the editor is now legitimately dirty: there is one field of
             unsaved work in it, and the guard should hold")))))

(deftest without-the-merge-the-late-reply-clobbers
  ;; THE CONTROL for the row above, through the same frame: the accepted
  ;; payload written as a whole slice, which is the corpus shape
  ;; (`examples/real-apps/realworld_resources/article_editor.cljs`
  ;; :304-314, the live rf2-y4mgw defect). The class is reachable, so
  ;; the guarded row is measuring something.
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/open-editor "welcome"])
      (send! frame [::app/edit :title "My own title"])
      (let [typed  (:draft (editor-of frame))
            naive  (merge typed welcome)]
        (is (= "My own title" (:title typed))
            "precondition: the typed value really was in the draft")
        (is (= (:title welcome) (:title naive))
            "and a whole-slice write of the very same payload replaces it
             — no error, no warning, the field simply changes under the
             cursor")))))

(deftest a-reply-for-an-article-the-editor-has-left-is-dropped
  ;; The half the runtime does NOT own. Two different articles are two
  ;; different `:request-id`s, so nothing was superseded — the first
  ;; request was abandoned, and an abandoned request still replies.
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/open-editor "welcome"])
      (let [first-args (last @!requests)]
        (send! frame [::app/open-editor "second"])
        (let [second-args (last @!requests)]
          (is (not= (:request-id first-args) (:request-id second-args))
              "precondition: two articles are two request ids, so the
               runtime's same-entry fence has nothing to suppress here")
          (reply-ok! frame second-args {:title "Second" :body "Second body"})
          (is (= "Second" (get-in (editor-of frame) [:draft :title])))
          (testing "the abandoned article's reply lands afterwards"
            (reply-ok! frame first-args welcome)
            (is (= "Second" (get-in (editor-of frame) [:draft :title]))
                "and changes nothing — the receiver compared the slug the
                 reply carries with the slug the editor holds")
            (is (= {:title "Second" :body "Second body"}
                   (:baseline (editor-of frame)))
                "including the baseline, which a drop that only guarded the
                 draft would have moved to the wrong article")))))))

(deftest a-failed-load-keeps-the-users-work
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/open-editor "welcome"])
      (let [args (last @!requests)]
        (send! frame [::app/edit :title "My own title"])
        (reply-error! frame args {:message "boom"})
        (is (= "My own title" (get-in (editor-of frame) [:draft :title]))
            "losing a draft to a failed GET is the clobber defect wearing a
             different hat")
        (is (true? (:load-failed? (editor-of frame))))))))

;; ---------------------------------------------------------------------------
;; RECIPE 2 — per-instance mutation status, and the optimistic write
;; ---------------------------------------------------------------------------

(defn- status [frame slug]
  (read-sub frame [:rf/mutation {:instance (app/favourite-instance slug)}]))

(deftest two-rows-in-flight-do-not-share-a-status
  ;; R-C5. A shared instance makes every row spin because any row is, and
  ;; paints one row's rejection on its neighbour.
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/toggle-favourite "welcome" true])
      (let [welcome-args (last @!requests)]
        (send! frame [::app/toggle-favourite "second" true])
        (is (= 2 (count @!requests)) "two writes, two requests")
        (is (true? (:pending? (status frame "welcome"))))
        (is (true? (:pending? (status frame "second"))))
        (reply-error! frame welcome-args {:message "rejected"})
        (is (true? (:error? (status frame "welcome"))))
        (is (false? (:pending? (status frame "welcome")))
            "a failure clears busy — the branch a hand-kept `:saving?`
             boolean is famous for forgetting")
        (is (false? (:error? (status frame "second")))
            "and the neighbour is untouched: no error painted on a row that
             did not fail")
        (is (true? (:pending? (status frame "second")))
            "and still in flight, because it is")))))

(deftest the-write-says-it-is-already-showing-your-change
  ;; The consumer-visible half of the optimistic plan: the view can tell
  ;; the difference between "in flight" and "in flight, and the value on
  ;; screen is yours rather than the server's".
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/toggle-favourite "welcome" true])
      (let [s (status frame "welcome")]
        (is (true? (:pending? s)))
        (is (true? (:optimistic? s))
            "so a row can render \"pending, but already showing your
             change\" from the read it already had, with no second flag")))))

(deftest a-rejected-optimistic-write-settles-back-in-the-same-read
  (capture-transport!)
  (with-app
    (fn [frame]
      (send! frame [::app/toggle-favourite "welcome" true])
      (reply-error! frame (last @!requests) {:message "rejected"})
      (let [s (status frame "welcome")]
        (is (true? (:error? s)))
        (is (false? (:pending? s)))
        (is (false? (:optimistic? s))
            "the optimistic value is no longer on screen — the runtime
             settled it, and the flag the view reads went with it. This
             application writes no rollback code at all; the apply /
             rollback / reconcile contract and its `:on-conflict` enum
             are the resources artefact's, pinned by its own
             `resources-optimistic-settle-cljs-test`"))
      (is (= :error (get-in (rf/app-db-value frame) [:last-settled "welcome"]))
          "and the reply was ADDRESSED — an unaddressed managed reply is
           silenced, so a recipe that omitted `:reply-to` would teach a
           write nobody can observe finishing"))))

;; ---------------------------------------------------------------------------
;; RECIPE 3 — the dirty-navigation guard, with zero DOM
;; ---------------------------------------------------------------------------

(defn- pending [frame] (read-sub frame [:rf/pending-navigation]))
(defn- route [frame] (read-sub frame [:rf.route/id]))

(defn- open-dirty-editor!
  "Land on the editor and leave one field of unsaved work in it."
  [frame]
  (send! frame [:rf.route/navigate {:to app/editor-route :params {:slug "welcome"}}])
  (send! frame [::app/edit :title "My own title"])
  frame)

(deftest the-guard-and-the-badge-read-one-definition
  ;; R-A6 in its navigation form: two recomputations of "is this dirty?"
  ;; drifting apart. The fix is one DEFINITION, not one cached value.
  (with-app
    (fn [frame]
      (send! frame [:rf.route/navigate {:to app/editor-route :params {:slug "welcome"}}])
      (is (true? (read-sub frame [::app/can-leave?])))
      (is (false? (read-sub frame [::app/dirty?])))
      (send! frame [::app/edit :title "My own title"])
      (is (false? (read-sub frame [::app/can-leave?])))
      (is (true? (read-sub frame [::app/dirty?])))
      (testing "the guard is STRICTLY boolean in both positions"
        ;; A non-boolean fails closed and raises
        ;; `:rf.error/can-leave-non-boolean`, so a guard answering `nil`
        ;; for "no editor open" would deny every navigation in the app.
        (is (boolean? (read-sub frame [::app/can-leave?])))
        (send! frame [::app/save])
        (is (boolean? (read-sub frame [::app/can-leave?])))))))

(deftest a-dirty-editor-blocks-the-leave-and-parks-it
  (with-app
    (fn [frame]
      (open-dirty-editor! frame)
      (send! frame [:rf.route/navigate {:to app/list-route}])
      (is (= app/editor-route (route frame))
          "the navigation did not commit — the user is still in the editor")
      (let [p (pending frame)]
        (is (some? p) "and the attempt was PARKED rather than dropped")
        (is (= app/editor-route (:rejecting-route p)))
        (is (= ::app/can-leave? (:rejecting-guard p))
            "the pending value names the guard that rejected, so a confirm
             dialog can say which one and a tool can attribute it")
        (is (some? (:id p))
            "and carries an id — both resolution events are keyed by it")))))

(deftest continue-completes-the-parked-navigation
  (with-app
    (fn [frame]
      (open-dirty-editor! frame)
      (send! frame [:rf.route/navigate {:to app/list-route}])
      (send! frame [:rf.route/continue (:id (pending frame))])
      (is (= app/list-route (route frame)) "the reader confirmed, so it went")
      (is (nil? (pending frame)) "and the slot cleared"))))

(deftest cancel-stays-put-and-clears-the-slot
  (with-app
    (fn [frame]
      (open-dirty-editor! frame)
      (send! frame [:rf.route/navigate {:to app/list-route}])
      (send! frame [:rf.route/cancel (:id (pending frame))])
      (is (= app/editor-route (route frame)))
      (is (nil? (pending frame)))
      (is (= "My own title" (get-in (editor-of frame) [:draft :title]))
          "and the work is still there — cancelling the leave must not
           also cancel the edits it was protecting"))))

(deftest a-saved-draft-leaves-freely
  ;; R-C9's second half, and the one a guard written against "has the user
  ;; ever typed?" fails: a just-saved draft is trapped in its own editor.
  (with-app
    (fn [frame]
      (open-dirty-editor! frame)
      (send! frame [::app/save])
      (send! frame [:rf.route/navigate {:to app/list-route}])
      (is (= app/list-route (route frame)))
      (is (nil? (pending frame)) "no prompt was raised at all"))))

(deftest save-and-close-bypasses-the-prompt
  (with-app
    (fn [frame]
      (open-dirty-editor! frame)
      (send! frame [::app/save-and-close])
      (is (= app/list-route (route frame)))
      (is (nil? (pending frame))
          "`:bypass-leave? true` states the intent explicitly; saving would
           have released the guard anyway, and a reader of the handler can
           see which of the two is being relied on"))))

(deftest the-guard-is-one-key-on-the-route
  ;; Structural, and the reason it is worth a row: the whole recipe is a
  ;; sub plus this key. If the key were dropped the four rows above would
  ;; all go red, but they would go red saying "the navigation committed",
  ;; which reads like a routing bug rather than a missing declaration.
  (let [meta* (routing/route-meta app/editor-route)]
    (is (= [::app/can-leave?] (:can-leave meta*))
        "the editor route declares the guard, naming the sub")
    (is (nil? (:can-leave (routing/route-meta app/list-route)))
        "and the list does not — a guard on every route is a guard nobody
         reads")))
