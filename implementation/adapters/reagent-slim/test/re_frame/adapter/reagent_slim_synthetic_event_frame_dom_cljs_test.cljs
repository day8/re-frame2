(ns re-frame.adapter.reagent-slim-synthetic-event-frame-dom-cljs-test
  "reagent-slim half of the deferred-callback frame-law real-DOM matrix
  (rf2-leeqp; completes the stock-Reagent proof rf2-xzgs3j /
  `views-synthetic-event-frame-dom-cljs-test`).

  WHAT THIS PINS. A view's `:on-*` handler runs LATER — on the user's
  click, on a fresh JS stack, after the render that built it has
  committed. By then the dynamic `re-frame.frame/*current-frame*` scope
  has unwound and the `frame-provider`'s React context has been popped
  back to the no-provider sentinel. So a bare, fully-qualified
  `rf/dispatch` from an `:on-click` resolves NO frame and — EP-0002, no
  `:rf/default` floor — raises `:rf.error/no-frame-context`; NOTHING
  lands. What survives that boundary is the frame captured at RENDER
  time: the `reg-view` macro's injected `dispatch` is exactly a
  `(rf/capture-frame)` op bundle bound to the render frame, so the
  UNqualified injected `dispatch` dispatches correctly after the render
  boundary.

  The stock-Reagent twin (`re-frame.views-synthetic-event-frame-dom-cljs-test`,
  rf2-xzgs3j) proves this under `reagent.dom.client`. The existing
  reagent-slim client-runtime smoke
  (`adapters/reagent-slim/testbed/smoke.cjs`) proves only the
  captured/injected HAPPY path. This suite closes the gap the bead flags:
  the BARE-dispatch FAILURE under the day8/reagent-slim substrate
  (`reagent2.dom.client` + `re-frame.adapter.reagent-slim`), with a real
  synthetic click.

  RIGOUR (rf2-leeqp acceptance). The working half settles on a CAUSAL
  signal — `test-support/poll-until` waits for the frame's app-db to
  actually advance, NOT a fixed sleep. A SINGLE idempotent finalizer
  (`finalize!`) unmounts the root, removes the DOM node, destroys the
  frame, and completes the async test on EVERY path — success,
  poll timeout, or a throw from mount/setup.

  Browser-only — a genuine synthetic event needs a real React root the
  Node runner can't fake. The `-dom-cljs-test$` suffix opts this file
  into the `:browser-test` build; `:node-test` still loads it (matches
  `cljs-test$`) and the DOM branch self-gates on `(browser?)`, exiting
  early where `js/document` is absent."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent2.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

;; `:ambient-frame nil` OPTS OUT of the fixture's default ambient
;; `*current-frame*` :rf/default scope. It is load-bearing here: the whole
;; point of the proof is that a synthetic-event handler fires with NO frame
;; scope in effect. An ambient :rf/default binding would satisfy the bare
;; `rf/dispatch` at click time and mask the very failure under test —
;; exactly as it would mask nothing in production, where a real user click
;; never runs under an ambient binding. `:async? true` because the working
;; half awaits the async dispatch drain.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter :async? true :ambient-frame nil}))

;; ---- browser gate ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; Captures the error id a bare `rf/dispatch` raises from the click handler.
;; A namespace-level atom so the `reg-view` body (spliced verbatim) can close
;; over it; reset at the top of the test.
(defonce ^:private raised (atom nil))

;; The proof view. Defined with the `reg-view` MACRO (not `reg-view*`) so the
;; UNqualified `dispatch` / `subscribe` below are the macro's render-time
;; frame-captured injections — the exact surface the guide documents — under
;; the reagent-slim substrate (the `reg-view` carries the `:contextType`
;; wiring slim needs to read the frame-provider's frame from React context).
;;
;;   * `syn-bare`     uses fully-qualified `rf/dispatch` — the ambient form.
;;                    It bypasses the injection and resolves the frame at
;;                    CLICK time, when there is none. Wrapped in try/catch to
;;                    capture the raised error id.
;;   * `syn-captured` uses the injected `dispatch` — captured at render.
(reg-view proof-view []
  (let [n @(subscribe [:syn/n])]
    [:div
     [:span {:data-testid "syn-count"} n]
     [:button {:data-testid "syn-bare"
               :on-click (fn [_]
                           (try
                             (rf/dispatch [:syn/inc])
                             (catch :default e
                               (reset! raised (:rf.error/id (ex-data e))))))}
      "bare rf/dispatch"]
     [:button {:data-testid "syn-captured"
               :on-click (fn [_] (dispatch [:syn/inc]))}
      "injected dispatch"]]))

(defn- query [mount-node testid]
  (.querySelector mount-node (str "[data-testid='" testid "']")))

(deftest synthetic-event-frame-advice-real-dom-proof-reagent-slim
  "rf2-leeqp — real synthetic click proves the deferred-callback frame law
   under reagent-slim.

   A `reg-view` mounted under a `frame-provider` on a real `reagent2`
   root carries two buttons. A real DOM click on the bare
   `#(rf/dispatch …)` button raises :rf.error/no-frame-context and lands
   nothing; a real DOM click on the injected `#(dispatch …)` button
   dispatches successfully and the frame's app-db advances after the
   render boundary. Settles on the causal app-db signal via poll-until."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [target     :syn.slim/frame
            done?      (atom false)
            ;; Resources are held in atoms so THE single finalizer can clean up
            ;; whatever was allocated, even if setup (createElement / mount /
            ;; render) throws before the happy path is reached.
            node-atom  (atom nil)
            root-atom  (atom nil)
            ;; THE single guaranteed finalizer: idempotent, runs on EVERY path —
            ;; success, poll-until timeout, or a throw anywhere in setup/mount.
            ;; Unmounts the root, removes the DOM node, destroys the frame,
            ;; completes the async test. No fixed sleeps anywhere.
            finalize!  (fn []
                         (when (compare-and-set! done? false true)
                           (when-let [r @root-atom] (try (.unmount r)  (catch :default _ nil)))
                           (when-let [n @node-atom] (try (.remove n)   (catch :default _ nil)))
                           (try (rf/destroy-frame! target) (catch :default _ nil))
                           (done)))]
        (try
          (reset! raised nil)
          (let [mount-node (.createElement js/document "div")]
            (reset! node-atom mount-node)
            (.appendChild (.-body js/document) mount-node)
            (rf/make-frame {:id target :doc "rf2-leeqp reagent-slim synthetic-event proof frame"})
            (rf/reg-event :syn/init (fn [{:keys [db]} _] {:db (assoc db :n 0)}))
            (rf/reg-event :syn/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
            (rf/reg-sub :syn/n (fn [db _] (:n db)))
            (rf/dispatch-sync [:syn/init] {:frame target})
            (let [root (rdc/create-root mount-node)]
              (reset! root-atom root)
              (react-dom/flushSync
                (fn []
                  (rdc/render root [rf/frame-provider {:frame target}
                                    [proof-view]])))
              (let [bare     (query mount-node "syn-bare")
                    captured (query mount-node "syn-captured")]
                (is (some? bare) "the bare-dispatch button mounted")
                (is (some? captured) "the injected-dispatch button mounted")
                (is (= 0 (:n (rf/app-db-value target)))
                    "sanity: app-db seeded to {:n 0} before any click")

                ;; ---- (1) bare rf/dispatch: fires later, no frame → raises ----
                (.click bare)
                (is (= :rf.error/no-frame-context @raised)
                    (str "a real synthetic click on the bare `#(rf/dispatch …)` button "
                         "raised :rf.error/no-frame-context (the render frame is gone "
                         "by the time the event fires); got " (pr-str @raised)))
                (is (= 0 (:n (rf/app-db-value target)))
                    "the bare dispatch landed NOTHING — app-db is still {:n 0}")

                ;; ---- (2) injected dispatch: captured at render → dispatches ----
                ;; Causal settle: poll the frame's app-db until the async
                ;; router drain lands the increment — no fixed sleep.
                (.click captured)
                (-> (test-support/poll-until
                      #(= 1 (:n (rf/app-db-value target)))
                      {:label      "reagent-slim injected dispatch advances the render frame to {:n 1}"
                       :timeout-ms 1000})
                    (.then (fn [_]
                             (is (= 1 (:n (rf/app-db-value target)))
                                 (str "a real synthetic click on the injected `#(dispatch …)` "
                                      "button dispatched successfully AFTER the render boundary "
                                      "— app-db advanced to {:n 1}"))
                             nil))
                    ;; Reports; it does NOT finalize. `done` hands
                    ;; `cljs.test/run-block` a continuation that runs the WHOLE
                    ;; remainder of the run synchronously, so a rejection
                    ;; handler downstream of the step that finished the row
                    ;; claims whatever a LATER namespace throws and prints it
                    ;; against this row's label (rf2-e8kc). The CAS inside
                    ;; `finalize!` swallowed the second `done`; it could not
                    ;; swallow the misattributed failure.
                    (.catch (fn [e]
                              (is false
                                  (str "injected dispatch never advanced the render frame: "
                                       (pr-str (ex-message e))))
                              nil))
                    ;; THE single finalizer, on the single trailing step —
                    ;; both arms reach it, and nothing follows it.
                    (.then (fn [_] (finalize!)))))))
          (catch :default e
            (is false (str "reagent-slim synthetic-event proof threw: " (pr-str e)))
            (finalize!)))))))
