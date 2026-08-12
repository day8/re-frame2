(ns re-frame.views-synthetic-event-frame-dom-cljs-test
  "Real-DOM proof for the Core Views guide's synthetic-event frame advice
  (rf2-xzgs3j).

  The `docs/core/views.md` §\"imperative listeners lose the frame\" section
  previously claimed that Hiccup `:on-*` attrs are 'wrapped so a `dispatch`
  inside them carries the frame', and marked a bare
  `#(rf/dispatch [:tile/finished])` in an `:on-animation-end` prop as the
  RIGHT pattern. That is FALSE of the shipped adapters. Neither the Reagent
  adapter (`re-frame.adapter.reagent`) nor reagent-slim wraps `:on-*`
  callbacks to re-establish a frame binding: the handler is a plain closure
  React invokes LATER, on a fresh JS stack, with no render in progress. At
  that point:

    * the dynamic `re-frame.frame/*current-frame*` scope has unwound, and
    * the frame-provider's React context value has been popped back to the
      no-provider sentinel (context is a render-phase concept).

  So a bare, fully-qualified `rf/dispatch` from an `:on-*` handler resolves
  NO frame and — EP-0002, no `:rf/default` floor — raises
  `:rf.error/no-frame-context` (see `re-frame.core/current-frame-id`,
  implementation/core/src/re_frame/core.cljc:1118-1132, and
  `frame/require-current-frame!`).

  What actually carries the frame into a deferred callback is capturing it
  at RENDER time. The `reg-view` macro injects `dispatch` / `subscribe`
  locals that are exactly a `(rf/capture-frame)` op bundle
  (core_reg_view_macro.cljc:185-192), so the UNqualified injected `dispatch`
  closes over the render frame and dispatches correctly after the render
  boundary. The same is true of an explicit `(:dispatch (rf/capture-frame))`
  or an explicit `{:frame …}` on the dispatch.

  This suite fires a REAL synthetic click on a mounted `reg-view` and pins
  both halves of the corrected advice:

    1. bare `#(rf/dispatch [:evt])`     → raises :rf.error/no-frame-context
                                          and nothing lands.
    2. injected `#(dispatch [:evt])`    → dispatches successfully; the
                                          frame's app-db advances.

  Browser-only — a genuine synthetic event needs a real React root + real
  DOM the Node runner can't fake. The `-dom-cljs-test$` suffix (rf2-2hrj8)
  opts this file into the `:browser-test` build; `:node-test` still loads it
  (matches `cljs-test$`) and the DOM branch self-gates on `(browser?)`,
  exiting early under :node-test where `js/document` is absent."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling])
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
    {:adapter reagent-adapter/adapter :async? true :ambient-frame nil}))

;; ---- browser gate ----------------------------------------------------------

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- deferred-teardown await (rf2-7r78l) ----------------------------------
;;
;; Same leak class + fix as #6502 (rf2-vp3m9). `proof-view` is a Reagent
;; `reg-view`, so its `:rf.view/unmounted` teardown marker rides the per-
;; component render-reaction DISPOSAL, which Reagent defers to a macrotask PAST
;; the synchronous unmount commit. The finalizer's bare `(rdc/unmount root)`
;; therefore let the marker fire AFTER `done`, leaking into the process-global
;; trace listener the one `:browser-test` page shares across every
;; `-dom-cljs-test` namespace. The finalizer now awaits that disposal so the
;; marker fires WITHIN the test's window — the same local settle idiom #6502
;; proved, NOT a new runtime and NOT a shared framework.

(defn- settle-macrotasks
  "Resolve after `n` macrotask turns so Reagent's deferred render-reaction
  disposal (which fires `:rf.view/unmounted` on a real unmount) has settled
  before the test completes."
  [n]
  (js/Promise.
    (fn [resolve _]
      (letfn [(step [remaining]
                (if (zero? remaining)
                  (resolve nil)
                  (js/setTimeout #(step (dec remaining)) 4)))]
        (step n)))))

(defn- await-teardown!
  "Unmount `root` through the real host teardown path under `flushSync` (so the
  unmount commits synchronously), then await Reagent's deferred reaction
  disposal so the `:rf.view/unmounted` marker fires WITHIN the caller's window
  rather than leaking into the shared runner after the test ends. Returns a
  Promise; settle-count 3 matches the proven #6502 idiom. A nil `root` (setup
  threw before create-root) is tolerated — the flushSync throw is swallowed and
  the settle still runs."
  [root]
  (try (react-dom/flushSync (fn [] (rdc/unmount root))) (catch :default _ nil))
  (settle-macrotasks 3))

;; Captures the error id a bare `rf/dispatch` raises from the click handler.
;; A namespace-level atom so the `reg-view` body (spliced verbatim) can close
;; over it; reset at the top of the test.
(defonce ^:private raised (atom nil))

;; The proof view. Defined with the `reg-view` MACRO (not `reg-view*`) so the
;; UNqualified `dispatch` / `subscribe` below are the macro's render-time
;; frame-captured injections — the exact surface the guide documents.
;;
;;   * `syn-bare`     uses fully-qualified `rf/dispatch` — the ambient form.
;;                    It bypasses the injection and resolves the frame at
;;                    CLICK time, when there is none. Wrapped in try/catch to
;;                    capture the raised error id (mirrors the setTimeout
;;                    regression in dispatch_frame_capture_cljs_test).
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

(deftest synthetic-event-frame-advice-real-dom-proof
  "rf2-xzgs3j — real synthetic click proves the corrected guide advice.

   A `reg-view` mounted under a `frame-provider` carries two buttons. A real
   DOM click on the bare `#(rf/dispatch …)` button raises
   :rf.error/no-frame-context and lands nothing; a real DOM click on the
   injected `#(dispatch …)` button dispatches successfully and the frame's
   app-db advances after the render boundary."
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [target     :syn/frame
            done?      (atom false)
            ;; Resources are held in atoms so THE single finalizer can clean up
            ;; whatever was allocated, even if setup (createElement / mount /
            ;; render) throws before the happy path is reached.
            node-atom  (atom nil)
            root-atom  (atom nil)
            ;; rf2-7r78l teeth: record proof-view's OWN :rf.view/unmounted marker,
            ;; scoped by :rf.view/id, so the finalizer can assert it fired WITHIN
            ;; the awaited window rather than leaking past `done` into the shared
            ;; runner. `reg-view`'s macro id is `<ns>/<sym>` (core-reg-view-macro).
            proof-view-id :re-frame.views-synthetic-event-frame-dom-cljs-test/proof-view
            unmounts   (atom [])
            ;; THE single guaranteed finalizer: idempotent, runs on EVERY path —
            ;; success, poll-until timeout, or a throw anywhere in setup/mount.
            ;; AWAITS proof-view's deferred reaction disposal (so its
            ;; :rf.view/unmounted fires within the test window, rf2-7r78l), then
            ;; removes the DOM node, destroys the frame, asserts the teardown
            ;; marker landed, and completes the async test. No fixed sleeps.
            finalize!  (fn []
                         (when (compare-and-set! done? false true)
                           (-> (await-teardown! @root-atom)
                               (.then (fn [_]
                                        (when-let [n @node-atom] (try (.remove n) (catch :default _ nil)))
                                        (try (rf/destroy-frame! target) (catch :default _ nil))
                                        (trace-tooling/unregister-listener! ::proof-view-unmounts)
                                        (is (= 1 (count @unmounts))
                                            (str "rf2-7r78l: exactly one :rf.view/unmounted fired for "
                                                 "proof-view WITHIN the awaited window — teardown awaited, "
                                                 "not leaked into the shared runner; got " (count @unmounts)))
                                        (done))))))]
        (try
          (reset! raised nil)
          (trace-tooling/register-listener! ::proof-view-unmounts
            (fn [ev]
              (when (and (= :rf.view/unmounted (:operation ev))
                         (= proof-view-id (-> ev :tags :rf.view/id)))
                (swap! unmounts conj ev))))
          (let [mount-node (.createElement js/document "div")]
            (reset! node-atom mount-node)
            (.appendChild (.-body js/document) mount-node)
            (rf/make-frame {:id target :doc "rf2-xzgs3j synthetic-event proof frame"})
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
                      {:label      "injected dispatch advances the render frame to {:n 1}"
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
            (is false (str "synthetic-event proof threw: " (pr-str e)))
            (finalize!)))))))
