(ns re-frame.recipes.async-nav-guard-dom-cljs-test
  "THE DIRTY-NAVIGATION GUARD (recipe 3), HELD IN A REAL BROWSER.

  `re-frame.recipes.async-nav-l0-cljs-test` holds the guard's sub; this
  file holds the navigation it guards — a programmatic leave and the
  browser's own Back button, each blocked and parked, the prompt's two
  buttons continuing and cancelling, and the address bar the guard puts
  back.

  ## The claim only a browser can carry

  A programmatic navigation is a FORWARD door: the address bar has not
  moved when the guard runs, so blocking it leaves nothing to undo. The
  Back button is not. By the time `popstate` reaches the application the
  browser has ALREADY changed the URL, so a guard that merely declined to
  commit would leave the editor on screen under the list's address — and
  the next reload, copy or bookmark takes that address at its word and
  discards the draft. Routing answers with `:rf.nav/replace-url` on the
  leave-block path (`re-frame.routing.decisions/decide`, the `url-driven?`
  arm), and whether the address bar actually goes back is a fact about
  `window.history`.

  ## The URL is borrowed, and given back

  The arrangement is `re-frame.routing-conduct-dom-cljs-test`'s: a
  `:url-bound? true` frame, so routing installs its REAL `popstate`
  listener, and `history.back()` rather than a synthetic
  `:rf.route/handle-url-change`. `js/location.href` is captured at
  NAMESPACE LOAD and `replaceState`d back in the trailing step both the
  success and failure paths reach. `pushState` does not reload, and no row
  ever goes back past the entry it started on, so the runner's execution
  context is never destroyed. The guard's own restore rewrites the entry
  the row went back TO; that is what a replace does, and teardown puts the
  runner's URL back over it regardless.

  ## Async rows

  Both rows are `async` — a real `popstate` and a real `.click()` arrive
  on the browser's own task loop — under the `make-reset-runtime-fixture`
  `:async? true` arrangement `re-frame.routing-conduct-dom-cljs-test` uses,
  because an async row under the wrong arrangement can abort the entire
  `test:browser` run silently. `:node-test` loads this namespace too,
  where each row records a STATED skip."
  (:require [cljs.test :refer-macros [async deftest is use-fixtures]]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.recipes.async-nav :as rf.recipes.async-nav]
            [re-frame.routing :as rf.routing]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.test-support :as rf.test-support]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a navigation-guard witness needs a real browser — " why)))

;; ---------------------------------------------------------------------------
;; The lane
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     ;; This suite creates its own top-level url-bound frame, so it needs a
     ;; clear ambient scope: the frame's `:initial-events` and routing's
     ;; synchronous initial URL sync must drain as a top-level cascade.
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.routing/reset-counters!)
                      ;; The scroll cache is a module-level `defonce` that
                      ;; survives the runtime reset, so a position saved by
                      ;; a neighbouring namespace would still be there.
                      (rf.routing/reset-scroll-cache!)
                      (rf.recipes.async-nav/register-routes!)
                      (rf.recipes.async-nav/register-resources!)
                      ;; Recipe 1's load has no server here, and this file is
                      ;; not measuring it. A recorder keeps the editor's
                      ;; draft empty and untouched until a row types into
                      ;; it, so "dirty" means exactly what this row did.
                      (rf.fx/reg-fx :rf.http/managed (fn [_ _] nil)))}))

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
    (.setAttribute container "id" rf.recipes.async-nav/root-id)
    (.appendChild js/document.body container)
    (let [root (rdc/create-root container)]
      (react-dom/flushSync
        (fn []
          (rdc/render root [rf/frame-provider {:frame frame-id}
                            [rf.recipes.async-nav/app]])))
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
  (-> (rf.test-support/poll-until #(= expected (route-id frame-id)) {:label label})
      (.then (fn [_] (rf.substrate.adapter/flush-render!) true))))

(defn- parked
  "Wait until a blocked navigation has been PARKED, then flush the render
  so the prompt the assertions read is on the page."
  [frame-id label]
  (-> (rf.test-support/poll-until #(some? (pending frame-id)) {:label label})
      (.then (fn [_] (rf.substrate.adapter/flush-render!) (pending frame-id)))))

(defn- open-dirty-editor!
  "Navigate to the editor and leave one field of unsaved work in it, then
  flush so the badge is on the page."
  [frame-id]
  (rf/dispatch-sync [:rf.route/navigate {:to     rf.recipes.async-nav/editor-route
                                         :params {:slug "welcome"}}]
                    {:frame frame-id})
  (rf/dispatch-sync [::rf.recipes.async-nav/edit :title "My own title"] {:frame frame-id})
  (rf.substrate.adapter/flush-render!)
  nil)

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
            _        (at-url! rf.recipes.async-nav/list-url)
            _        (rf/make-frame {:id             frame-id
                                     :url-bound?     true
                                     :initial-events [[::rf.recipes.async-nav/seed]]})
            m        (mount! frame-id)]
        (-> (js/Promise.resolve
              (do (open-dirty-editor! frame-id)
                  (is (= (rf.recipes.async-nav/editor-url "welcome") (path))
                      (str "precondition: `:rf.nav/push-url` is a real"
                           " `history.pushState`; the address bar reads " (pr-str (path))))))
            (.then (fn [_]
                     ;; A real `history.back()`, firing a real `popstate` on
                     ;; the browser's own task loop.
                     (.back js/window.history)
                     (parked frame-id "the real Back button's blocked attempt")))
            (.then
              (fn [p]
                (is (= rf.recipes.async-nav/editor-route (route-id frame-id))
                    "the navigation did not commit")
                (is (= (rf.recipes.async-nav/editor-url "welcome") (path))
                    (str "the address bar was put back; it reads " (pr-str (path))))
                (is (= {:url-restored?   true
                        :rejecting-route rf.recipes.async-nav/editor-route
                        :rejecting-guard ::rf.recipes.async-nav/can-leave?}
                       (select-keys p [:url-restored? :rejecting-route :rejecting-guard])))
                (is (some? (node m rf.recipes.async-nav/prompt-selector))
                    "the prompt is ordinary view code over the pending value")
                (.click (node m rf.recipes.async-nav/leave-selector))
                (settled-at frame-id rf.recipes.async-nav/list-route
                            "the reader's `Discard and leave` completing the parked navigation")))
            (.then
              (fn [_]
                (is (nil? (node m rf.recipes.async-nav/prompt-selector))
                    "the slot cleared, and the prompt went with it")
                (is (= rf.recipes.async-nav/list-url (path))
                    (str "the address bar followed the completed navigation; it reads "
                         (pr-str (path))))))
            (finish m frame-id done))))))

;; ---------------------------------------------------------------------------
;; Staying — the other button, and the work it protects
;; ---------------------------------------------------------------------------

(deftest staying-keeps-the-route-the-prompt-and-the-work
  (if-not (browser?)
    (skip! ":node-test has no click model")
    (async done
      (let [frame-id ::stay
            _        (at-url! rf.recipes.async-nav/list-url)
            _        (rf/make-frame {:id             frame-id
                                     :url-bound?     true
                                     :initial-events [[::rf.recipes.async-nav/seed]]})
            m        (mount! frame-id)]
        (-> (js/Promise.resolve (open-dirty-editor! frame-id))
            (.then (fn [_]
                     ;; A PROGRAMMATIC leave — a forward door, which touches
                     ;; no history, so this row never goes back past the
                     ;; entry it started on.
                     (rf/dispatch-sync [:rf.route/navigate {:to rf.recipes.async-nav/list-route}]
                                       {:frame frame-id})
                     (parked frame-id "the programmatic leave's blocked attempt")))
            (.then
              (fn [_]
                (is (= (rf.recipes.async-nav/editor-url "welcome") (path))
                    "a forward door never moved the address bar, so there was
                     nothing to put back")
                (.click (node m rf.recipes.async-nav/stay-selector))
                (rf.test-support/poll-until #(nil? (pending frame-id))
                                            {:label "the reader's `Stay` clearing the parked attempt"})))
            (.then
              (fn [_]
                (rf.substrate.adapter/flush-render!)
                (is (= rf.recipes.async-nav/editor-route (route-id frame-id))
                    "still in the editor")
                (is (nil? (node m rf.recipes.async-nav/prompt-selector))
                    "dirty is not blocked: with nothing pending there is no prompt")
                (is (some? (node m rf.recipes.async-nav/dirty-badge-selector))
                    "and the work is still there — cancelling the leave must not
                     also cancel the edits it was protecting")
                (is (= "My own title" (.-value (node m "[data-editor-title]")))
                    "in the field itself, not merely in app-db")))
            (finish m frame-id done))))))
