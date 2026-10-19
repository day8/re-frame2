(ns re-frame.plain-fn-frame-from-tree-dom-cljs-test
  "A Reagent view resolves its frame from the component tree. While a
  component renders, the frame boundary above it in the tree answers first —
  for a `reg-view` view, for a plain Reagent fn that never registered, and
  for a React hook beside them — and a `with-frame` scope around the render
  answers only where no boundary is above. Outside every render the dynamic
  scope alone answers, so a deferred callback still carries its frame
  explicitly (Spec 006 §One frame-resolution rule).

  Every probe here is a PLAIN fn unless its name says otherwise: no
  `reg-view`, so no `:contextType`. It records what its ambient reads
  resolved to, so a refusal is data the assertion reads rather than a
  render that throws. The server-render case needs no DOM and runs on the
  node lane too; the rest mount for real in the browser lane."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as react]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [reagent.dom.server :as rds]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.use-frame :as rf.adapter.use-frame]
            [re-frame.test-support :as rf.test-support]))

(rf/reg-event ::seed (fn [_ [_ label]] {:db {:label label}}))
(rf/reg-sub ::label (fn [db _] (:label db)))

;; `:ambient-frame nil`: no frame is in scope unless a test establishes one,
;; so every resolution below is the tree's or the test's own `with-frame`.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- frames! [& ids]
  (doseq [id ids]
    (rf/make-frame {:id id})
    (rf/dispatch-sync [::seed (name id)] {:frame id})))

(defn- read-ambient
  "What an ambient read resolves to here: `[:ok frame label]`, or
  `[:error <:rf.error/id>]` when no frame is in scope."
  []
  (try
    [:ok (rf/current-frame-id) @(rf/subscribe [::label])]
    (catch :default e
      [:error (:rf.error/id (ex-data e))])))

(defn- probe
  "A plain Reagent fn that logs each render's ambient read."
  [log]
  (fn plain-probe []
    (let [read (read-ambient)]
      (swap! log conj read)
      [:span (pr-str read)])))

(defn- with-act-environment [f]
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (try (f)
         (finally (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev)))))

(defn- mounted
  "Mount `tree` into a fresh node, run `(f root)`, and unmount."
  [tree f]
  (with-act-environment
    (fn []
      (let [root (rdc/create-root (.createElement js/document "div"))]
        (try
          (rf.test-support/flush-views! #(rdc/render root tree))
          (f root)
          (finally
            (rf.test-support/flush-views! #(rdc/unmount root))))))))

(deftest a-plain-fn-resolves-the-nearest-provider
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [outer (atom [])
          inner (atom [])]
      (frames! :tenant/outer :tenant/inner :tenant/distractor)
      ;; The distractor scope surrounds the whole synchronous render: the tree
      ;; must win over it, at both depths.
      (rf/with-frame :tenant/distractor
        (mounted [rf/frame-provider {:frame :tenant/outer}
                  [(probe outer)]
                  [rf/frame-provider {:frame :tenant/inner}
                   [(probe inner)]]]
                 (fn [_])))
      (is (= [:ok :tenant/outer "outer"] (first @outer))
          "the plain fn read the outer provider's frame, namespace intact")
      (is (= [:ok :tenant/inner "inner"] (first @inner))
          "the nested plain fn read the nearer provider's frame"))))

(deftest an-independent-ratom-re-render-still-resolves-the-provider
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [log  (atom [])
          tick (r/atom 0)
          view (fn ticking-plain-fn []
                 @tick
                 (let [read (read-ambient)]
                   (swap! log conj read)
                   [:span (pr-str read)]))]
      (frames! :tenant/outer)
      (mounted [rf/frame-provider {:frame :tenant/outer} [view]]
               (fn [_]
                 ;; Only this component re-renders: a local ratom, nothing
                 ;; above it moves.
                 (rf.test-support/flush-views! #(swap! tick inc))))
      (is (= [[:ok :tenant/outer "outer"] [:ok :tenant/outer "outer"]] @log)
          "both the mount and the ratom-triggered re-render resolved the provider"))))

(deftest strict-mode-double-render-resolves-the-provider
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [log (atom [])]
      (frames! :tenant/outer)
      (mounted [:> react/StrictMode
                [rf/frame-provider {:frame :tenant/outer} [(probe log)]]]
               (fn [_]))
      (is (seq @log) "the probe rendered")
      (is (every? #{[:ok :tenant/outer "outer"]} @log)
          (str "every StrictMode render resolved the provider; got " (pr-str @log))))))

(deftest a-server-render-resolves-the-provider
  (let [log (atom [])]
    (frames! :tenant/ssr)
    (let [html (rds/render-to-string
                 [rf/frame-provider {:frame :tenant/ssr} [(probe log)]])]
      (is (= [:ok :tenant/ssr "ssr"] (first @log))
          "the plain fn read the provider's frame under react-dom/server")
      (is (re-find #"tenant/ssr" html) "the markup carries the resolved frame"))))

(deftest a-flush-inside-with-frame-resolves-the-tree-for-a-view-and-a-hook-alike
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [plain-log (atom [])
          view-log  (atom [])
          hook-log  (atom [])
          hook-fn   (fn hook-probe []
                      (swap! hook-log conj (:frame (rf.adapter.use-frame/use-frame)))
                      (react/createElement "span" nil "hook"))]
      (frames! :tenant/a :tenant/b)
      (rf/reg-view* ::registered
                    (fn registered-probe []
                      (let [read (read-ambient)]
                        (swap! view-log conj read)
                        [:span (pr-str read)])))
      (rf/with-frame :tenant/a
        (mounted [rf/frame-provider {:frame :tenant/b}
                  [(probe plain-log)]
                  [(rf/view ::registered)]
                  [:f> hook-fn]]
                 (fn [_])))
      (is (= [:ok :tenant/b "b"] (first @plain-log)) "the plain fn resolved :b")
      (is (= [:ok :tenant/b "b"] (first @view-log)) "the reg-view view resolved :b")
      (is (= :tenant/b (first @hook-log)) "the hook resolved :b"))))

(deftest with-no-provider-the-dynamic-scope-answers-or-the-read-refuses
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (let [scoped   (atom [])
          unscoped (atom [])]
      (frames! :tenant/scoped)
      (rf/with-frame :tenant/scoped
        (mounted [(probe scoped)] (fn [_])))
      (mounted [(probe unscoped)] (fn [_]))
      (testing "control: no boundary above"
        (is (= [:ok :tenant/scoped "scoped"] (first @scoped))
            "a with-frame around the synchronous render answers")
        (is (= [:error :rf.error/no-frame-context] (first @unscoped))
            "with no scope at all the read refuses rather than inventing a frame")))))

(deftest a-deferred-callback-still-needs-captured-ops
  (if-not (browser?)
    (is true ":node-test: no DOM — the :browser-test runner exercises the assertions")
    (async done
      (let [bare     (atom nil)
            captured (atom nil)
            view     (fn deferring-plain-fn []
                       ;; A refused capture is data too, so a regression fails
                       ;; the assertion instead of throwing out of the render.
                       (let [ops (try (rf/capture-frame)
                                      (catch :default e
                                        {:frame [:error (:rf.error/id (ex-data e))]}))]
                         (js/setTimeout
                           (fn []
                             (reset! bare (try (rf/current-frame-id)
                                               (catch :default e
                                                 (:rf.error/id (ex-data e)))))
                             (reset! captured (:frame ops)))
                           0))
                       [:span "deferring"])]
        (frames! :tenant/outer)
        (mounted [rf/frame-provider {:frame :tenant/outer} [view]] (fn [_]))
        (-> (rf.test-support/poll-until #(some? @captured) {:label "the deferred callback ran"})
            (.then (fn [_]
                     (is (= :rf.error/no-frame-context @bare)
                         "control: outside every render an ambient read in the callback refuses")
                     (is (= :tenant/outer @captured)
                         "ops captured during the render carry the provider's frame")))
            (.catch (fn [e] (is false (str "the deferred callback never ran: " e)) nil))
            (.then (fn [_] (done))))))))
