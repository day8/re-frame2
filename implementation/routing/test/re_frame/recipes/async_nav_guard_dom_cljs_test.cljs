(ns re-frame.recipes.async-nav-guard-dom-cljs-test
  "THE DIRTY-NAVIGATION GUARD, HELD AGAINST THE BROWSER'S OWN BACK BUTTON
  (rf2-hic-054, recipe 3).

  `re-frame.recipes.async-nav-l0-cljs-test` already proves the guard's
  whole model with zero DOM — blocked, parked, continued, cancelled,
  bypassed. This file exists for the one claim that model cannot make.

  ## The claim only a browser can carry

  A programmatic navigation is a FORWARD door: the address bar has not
  moved when the guard runs, so blocking it leaves nothing to undo. The
  Back button is not. By the time `popstate` reaches the application the
  browser has ALREADY changed the URL, so a guard that merely declines
  to commit leaves the user in the editor with the list's address in the
  bar — and the next reload, copy, or bookmark takes that address at its
  word and silently discards the draft.

  Routing answers that with `:rf.nav/replace-url` on the leave-block
  path (`re-frame.routing.decisions/decide`, the `url-driven?` arm), a
  REPLACE rather than a push so no history entry is added. Whether the
  address bar actually goes back is a fact about `window.history`, and
  the only instrument that can read it is a browser.

  ## Built on `re-frame.routing-conduct-dom-cljs-test`'s seam, not beside it

  That file (rf2-hic-042, PR #8031) established the arrangement this one
  reuses rather than reinvents: a `:url-bound? true` frame, so routing's
  registration installs its REAL `popstate` listener; a deep link that
  is a URL the browser is genuinely sitting on; `history.back()` rather
  than a hand-written `:rf.route/handle-url-change` carrying a synthetic
  cause; and the borrowing discipline below. Focus-on-route and scroll
  restoration are its subject and are not re-measured here.

  ## The URL is borrowed, and given back

  `js/location.href` is captured at NAMESPACE LOAD and `replaceState`d
  back in the trailing step both the success and failure paths reach.
  `pushState` does not reload, and **no row ever goes back past the
  entry it started on**, so the runner's execution context is never
  destroyed.

  One consequence is worth naming because it looks alarming and is not:
  the guard's own restore rewrites the entry the row went back TO. That
  is what a replace does, it is the correct behaviour, and teardown puts
  the runner's URL back over it regardless.

  ## Async rows, and the run that must be counted

  Two rows here are `async`, unavoidably — a real `popstate` and a real
  `.click()` both arrive on the browser's own task loop. An async row
  under the wrong fixture arrangement aborts the entire `test:browser`
  run silently, every later namespace included (rf2-u0j8, live at time
  of writing), so these use the same `make-reset-runtime-fixture`
  `:async? true` arrangement PR #8031 proved in this lane, and the PR
  body quotes this run's namespace and assertion counts against a
  control.

  ns ends in `-dom-cljs-test` so shadow-cljs's `:browser-test` discovers
  it; `:node-test` loads it too, where every engine-dependent row
  degrades to a STATED skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.recipes.async-nav :as app]
            [re-frame.routing :as routing]
            [re-frame.substrate.adapter :as substrate]
            [re-frame.test-support :as test-support]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a navigation-guard witness needs a real browser — " why)))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       reagent-adapter/adapter
     ;; This suite creates its own top-level url-bound frame, so it needs a
     ;; clear ambient scope: the frame's `:initial-events` and routing's
     ;; synchronous initial URL sync must drain as a top-level cascade.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (routing/reset-counters!)
                      ;; The scroll cache is a module-level `defonce` that
                      ;; survives the runtime reset, so a position saved by
                      ;; a neighbouring namespace would still be there.
                      (routing/reset-scroll-cache!)
                      (app/register-routes!)
                      (app/register-resources!)
                      ;; Recipe 1's load has no server here, and this file is
                      ;; not measuring it. A recorder keeps the editor's
                      ;; draft empty and untouched until a row types into
                      ;; it, so "dirty" means exactly what this row did.
                      (fx/reg-fx :rf.http/managed (fn [_ _] nil)))}))

;; ---------------------------------------------------------------------------
;; Borrowing the address bar
;; ---------------------------------------------------------------------------

(def ^:private runner-url
  "The URL the test page was served from, captured at NAMESPACE LOAD —
  before any row has touched the address bar."
  (when (browser?) (.-href js/location)))

(defn- at-url!
  "Put the browser on `url` without adding a history entry and without
  reloading. Called before the url-bound frame exists, so routing's own
  initial sync is what reads it."
  [url]
  (.replaceState js/window.history nil "" url))

(defn- restore-runner-url! []
  (when (and (browser?) runner-url)
    (.replaceState js/window.history nil "" runner-url)))

(defn- path [] (.-pathname js/location))

;; ---------------------------------------------------------------------------
;; Mounting, reading, waiting
;; ---------------------------------------------------------------------------

(defn- mount!
  "Create the application's root, append it, and commit the first render
  synchronously so the pane is on the page in the same task the frame
  was created in."
  [frame-id]
  (let [container (.createElement js/document "div")]
    (.setAttribute container "id" app/root-id)
    (.appendChild js/document.body container)
    (let [root (rdc/create-root container)]
      (react-dom/flushSync
        (fn []
          (rdc/render root [rf/frame-provider {:frame frame-id}
                            [(rf/view ::app/app)]])))
      {:container container :root root})))

(defn- teardown!
  "Unmount, detach, give the address bar back, and drop the frame. Runs on
  the success and failure paths alike."
  [{:keys [container root]} frame-id]
  (try (.unmount root) (catch :default _ nil))
  (try (.remove container) (catch :default _ nil))
  (restore-runner-url!)
  (try (.scrollTo js/window 0 0) (catch :default _ nil))
  (try (rf/destroy-frame! frame-id) (catch :default _ nil))
  nil)

(defn- finish
  "The single trailing step both paths reach. A rejection is reported as
  this row's failure rather than left to hang the run."
  [p m frame-id done]
  (-> p
      (.catch (fn [e]
                (is false (str "the flow never settled: "
                               (or (ex-message e) (str e)) " "
                               (pr-str (ex-data e))))
                nil))
      (.then (fn [_] (teardown! m frame-id) (done)))))

(defn- read-sub [frame-id query-v] (rf/subscribe-once query-v {:frame frame-id}))
(defn- route-id [frame-id] (read-sub frame-id [:rf.route/id]))
(defn- pending [frame-id] (read-sub frame-id [:rf/pending-navigation]))

(defn- node [{:keys [container]} selector] (.querySelector container selector))

(defn- settled-at
  "Wait until the route slice reads `expected`, then flush the render so
  the pane the assertions read is the pane the route names."
  [frame-id expected label]
  (-> (test-support/poll-until #(= expected (route-id frame-id)) {:label label})
      (.then (fn [_] (substrate/flush-render!) true))))

(defn- parked
  "Wait until a blocked navigation has been PARKED, then flush the render
  so the prompt the assertions read is on the page."
  [frame-id label]
  (-> (test-support/poll-until #(some? (pending frame-id)) {:label label})
      (.then (fn [_] (substrate/flush-render!) (pending frame-id)))))

(defn- open-dirty-editor!
  "Navigate to the editor and leave one field of unsaved work in it, then
  flush so the badge is on the page."
  [frame-id]
  (rf/dispatch-sync [:rf.route/navigate {:to     app/editor-route
                                         :params {:slug "welcome"}}]
                    {:frame frame-id})
  (rf/dispatch-sync [::app/edit :title "My own title"] {:frame frame-id})
  (substrate/flush-render!)
  nil)

;; ---------------------------------------------------------------------------
;; The negative control — no prompt until something is pending
;; ---------------------------------------------------------------------------

(deftest the-prompt-renders-nothing-until-a-leave-is-blocked
  (if-not (browser?)
    (skip! ":node-test has no document to mount into")
    (let [frame-id ::quiet
          _        (at-url! app/list-url)
          _        (rf/make-frame {:id             frame-id
                                   :url-bound?     true
                                   :initial-events [[::app/seed]]})
          m        (mount! frame-id)]
      (try
        (is (= app/list-route (route-id frame-id))
            "precondition: the frame's initial sync read the address bar")
        (is (nil? (pending frame-id)))
        (is (nil? (node m app/prompt-selector))
            "the confirm UI is ordinary view code over `:rf/pending-navigation`,
             so with nothing pending it puts NO node on the page — which is
             what makes the rows below able to assert its presence")
        (open-dirty-editor! frame-id)
        (is (some? (node m app/dirty-badge-selector))
            "the editor is dirty and says so")
        (is (nil? (node m app/prompt-selector))
            "and being dirty is not being blocked — nothing has tried to
             leave yet, so there is still no prompt")
        (finally (teardown! m frame-id))))))

;; ---------------------------------------------------------------------------
;; The browser's own Back button
;; ---------------------------------------------------------------------------

(deftest the-real-back-button-is-held-and-the-address-bar-is-put-back
  (if-not (browser?)
    (skip! ":node-test has no history model")
    (async done
      (let [frame-id ::back
            ;; Entry 0 is the list. The editor is a REAL pushState on top of
            ;; it, so the Back button below never goes past where this row
            ;; started.
            _        (at-url! app/list-url)
            _        (rf/make-frame {:id             frame-id
                                     :url-bound?     true
                                     :initial-events [[::app/seed]]})
            m        (mount! frame-id)]
        (-> (js/Promise.resolve
              (testing "forward into the editor, and dirty it"
                (open-dirty-editor! frame-id)
                (is (= app/editor-route (route-id frame-id)))
                (is (= (app/editor-url "welcome") (path))
                    (str "precondition: `:rf.nav/push-url` is a real"
                         " `history.pushState`, so the address bar reads the"
                         " editor; it reads " (pr-str (path))))
                (is (some? (node m app/dirty-badge-selector))
                    "precondition: there is unsaved work to protect")))
            (.then (fn [_]
                     ;; THE BACK BUTTON. Not a dispatch standing in for one:
                     ;; a real `history.back()`, which fires a real `popstate`
                     ;; on the browser's own task loop, reaching the listener
                     ;; the url-bound frame's lifecycle installed.
                     (.back js/window.history)
                     (parked frame-id "the real Back button's blocked attempt")))
            (.then
              (fn [p]
                (testing "the leave was refused, and the URL came back with it"
                  (is (= app/editor-route (route-id frame-id))
                      "the navigation did not commit — the user is still in
                       the editor with their draft")
                  (is (= (app/editor-url "welcome") (path))
                      (str "AND THE ADDRESS BAR WAS PUT BACK. It reads "
                           (pr-str (path)) ". `popstate` had already moved it"
                           " before the application heard about it, so a guard"
                           " that only declined to commit would leave the"
                           " editor on screen under the list's address — and"
                           " a reload, a copy or a bookmark takes the address"
                           " at its word and discards the draft. This is the"
                           " one claim in the recipe that no zero-DOM row can"
                           " make"))
                  (is (true? (:url-restored? p))
                      "and the pending value records that the restore happened,
                       so a confirm dialog reading `current-url` sees the
                       restored value rather than the one the browser moved to")
                  (is (= app/editor-route (:rejecting-route p)))
                  (is (= ::app/can-leave? (:rejecting-guard p))))

                (testing "and the prompt is on the page — ordinary view code"
                  (is (some? (node m app/prompt-selector))
                      "no `window.confirm` and no `beforeunload`: a blocked
                       attempt is a value, and the dialog is a view over it")
                  (is (some? (node m app/leave-selector))))

                ;; A REAL CLICK on the reader's own choice.
                (.click (node m app/leave-selector))
                (settled-at frame-id app/list-route
                            "the reader's `Discard and leave` completing the parked navigation")))
            (.then
              (fn [_]
                (is (nil? (pending frame-id))
                    "the slot cleared, so the prompt cannot come back")
                (is (nil? (node m app/prompt-selector)))
                (is (= app/list-url (path))
                    (str "and the address bar followed the completed"
                         " navigation to " (pr-str app/list-url) "; it reads "
                         (pr-str (path)) ". A continue that landed the route"
                         " without landing the URL would leave the two"
                         " disagreeing in exactly the state the guard was"
                         " protecting"))))
            (finish m frame-id done))))))

;; ---------------------------------------------------------------------------
;; Staying — the other button, and the work it protects
;; ---------------------------------------------------------------------------

(deftest staying-keeps-the-route-the-prompt-and-the-work
  (if-not (browser?)
    (skip! ":node-test has no click model")
    (async done
      (let [frame-id ::stay
            _        (at-url! app/list-url)
            _        (rf/make-frame {:id             frame-id
                                     :url-bound?     true
                                     :initial-events [[::app/seed]]})
            m        (mount! frame-id)]
        (-> (js/Promise.resolve (open-dirty-editor! frame-id))
            (.then (fn [_]
                     ;; A PROGRAMMATIC leave this time — a forward door, so
                     ;; the address bar never moved and there is nothing to
                     ;; restore. The row is here for the OTHER button, and
                     ;; deliberately touches no history: it must not go back
                     ;; past the entry it started on.
                     (rf/dispatch-sync [:rf.route/navigate {:to app/list-route}]
                                       {:frame frame-id})
                     (parked frame-id "the programmatic leave's blocked attempt")))
            (.then
              (fn [_]
                (is (= app/editor-route (route-id frame-id)))
                (is (= (app/editor-url "welcome") (path))
                    "a forward door never moved the address bar, so there was
                     nothing to put back — the asymmetry the Back-button row
                     exists for")
                (is (some? (node m app/stay-selector)))
                (.click (node m app/stay-selector))
                (test-support/poll-until #(nil? (pending frame-id))
                                         {:label "the reader's `Stay` clearing the parked attempt"})))
            (.then
              (fn [_]
                (substrate/flush-render!)
                (is (= app/editor-route (route-id frame-id))
                    "still in the editor")
                (is (nil? (node m app/prompt-selector))
                    "the prompt went with the pending value")
                (is (some? (node m app/dirty-badge-selector))
                    "and the work is still there — cancelling the leave must
                     not also cancel the edits it was protecting")
                (is (= "My own title"
                       (.-value (node m "[data-editor-title]")))
                    "in the field itself, not merely in app-db")))
            (finish m frame-id done))))))
