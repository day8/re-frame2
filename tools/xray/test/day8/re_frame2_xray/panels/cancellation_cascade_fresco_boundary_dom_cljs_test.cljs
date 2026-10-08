(ns day8.re-frame2-xray.panels.cancellation-cascade-fresco-boundary-dom-cljs-test
  "The cancellation-cascade popover's Fresco boundary, read off a real
  React commit.

  `cancellation-cascade/PopoverView` is an `rf.fresco/defview`, mounted the
  way `shell.cljs` mounts it: the `cc/Popover` bridge as a hiccup head
  inside a `frame-provider`. W1 is first display, with the boundary's read
  landing in the frame the provider named. W5 is a real browser click
  inside the boundary: the dispatch carries the frame captured at render
  (`rf/current-frame-id`), because inside a Fresco body an AMBIENT dispatch
  refuses — so the event must land in the named frame and nowhere else.

  The ns ends in `-dom-cljs-test`, so it runs under `:browser-test`; the
  `:node-test` build also loads it, where every row reports the skip
  through [[browser?]]."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.cancellation-cascade :as cc]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(def ^:private app-frame
  "An ordinary application frame: W1's negative half of the frame-targeting
  claim, and the frame W5's click must NOT write into."
  ::app)

(def ^:private open?-q
  "The popover's gate read, which is also its sub-cache key."
  [:rf.xray/cancellation-cascade-popover-open?])

(def ^:private expanded?-q
  [:rf.xray/cancellation-cascade-expanded?])

(def ^:private focus-dispatch-id 7)

(defn- abort-event
  [n]
  {:id        (+ 10 n)
   :operation :rf.http/aborted-on-actor-destroy
   :op-type   :rf.http
   :severity  :info
   :time      (+ 1020 n)
   :tags      {:request-id           (keyword (str "r" n))
               :url                  (str "/api/x" n)
               :actor-id             :user-session
               :rf.trace/dispatch-id focus-dispatch-id
               :frame                :rf/default}})

(def ^:private cascade-buffer
  "One decision + one cancellation-anchor + TWELVE aborts — over the
  collapse threshold, so W5 has an expander to press."
  (into [{:id 1 :operation :rf.event/dispatched :op-type :rf.event
          :time 1000
          :tags {:rf.event/v           [:auth/logout]
                 :rf.trace/dispatch-id focus-dispatch-id
                 :frame                :rf/default}}
         {:id 2 :operation :rf.machine/destroyed :op-type :rf.machine
          :time 1010
          :tags {:machine-id           :user-session
                 :reason               :explicit
                 :rf.trace/dispatch-id focus-dispatch-id
                 :frame                :rf/default}}]
        (map abort-event (range 1 13))))

(rf/reg-sub ::n (fn [db _] (::n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     ;; `:async? true` because W5 is an `async` row, and `cljs.test`
     ;; refuses a FUNCTION fixture in any namespace that carries one.
     :async?        true
     :init-fn       (fn []
                      (xray-test-support/reset-all!)
                      ;; Fresco's collector tables are process-global
                      ;; `defonce`s the core fixture does not reset.
                      (rf.fresco.impl.collector/reset-runtime!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- setup!
  "Register Xray's handlers and make the two frames."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (rf/make-frame {:id app-frame})
  nil)

(defn- seed-cascade!
  "Put a real cancellation cascade in Xray's trace buffer and focus it."
  []
  (rf/dispatch-sync [:rf.xray/sync-trace-buffer cascade-buffer]
                    {:frame :rf/xray})
  (rf/dispatch-sync [:rf.xray/cancellation-cascade-open
                     {:kind :dispatch-id :id focus-dispatch-id}]
                    {:frame :rf/xray}))

(defn- mount-popover!
  "Mount the popover the way `shell.cljs` mounts it, inside a
  `frame-provider` scoping `frame`. Committed synchronously: React 19's
  `root.render` is async."
  [frame]
  (let [container (.createElement js/document "div")
        root      (rdc/create-root container)]
    (.appendChild (.-body js/document) container)
    (react-dom/flushSync
      (fn []
        (rdc/render root [rf/frame-provider {:frame frame}
                          [cc/Popover]])))
    {:container container :root root}))

(defn- teardown!
  [root container]
  (react-dom/flushSync (fn [] (.unmount root)))
  (.remove container))

(defn- q [container sel] (.querySelector container sel))

(defn- cache-of
  "The frame's live sub-cache map; throws if the frame is not live."
  [frame-id]
  @(:sub-cache (rf.frame/frame frame-id)))

(defn- ref-count-of
  "The sub-cache ref-count the frame holds for `query-v`, or 0."
  [frame-id query-v]
  (or (:ref-count (get (cache-of frame-id) query-v)) 0))

(defn- aborts-shown
  "How many abort rows the committed body says it shows
  (`data-aborts-shown` on its own root)."
  [container]
  (some-> (q container "[data-testid=\"rf-xray-cancellation-cascade-body\"]")
          (.getAttribute "data-aborts-shown")
          js/parseInt))

(deftest w1-popover-paints-and-its-reads-land-in-the-named-frame
  (testing "the popover commits real DOM through the head `shell.cljs`
            mounts, and its `rf.fresco/sub` reads resolve against the frame
            the enclosing `frame-provider` named"
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (let [_ (setup!)
            _ (seed-cascade!)
            ;; A live read in the OTHER frame, so the zero below is measured
            ;; by an instrument shown able to see that frame's cache.
            _probe (rf/subscribe [::n] {:frame app-frame})
            {:keys [container root]} (mount-popover! :rf/xray)]
        (try
          (is (some? (q container "[data-testid=\"rf-xray-cancellation-cascade-decision-row\"]"))
              "the dialog's waterfall rendered against a real cascade")
          (is (pos? (ref-count-of :rf/xray open?-q))
              (str "the gate read holds a reference in :rf/xray's sub-cache. "
                   "Cache keys: " (pr-str (keys (cache-of :rf/xray)))))
          (is (zero? (ref-count-of app-frame open?-q))
              "and NOT in the application frame's")
          (is (pos? (ref-count-of app-frame [::n]))
              "NON-VACUITY: the same instrument does see the probe's entry there")
          (finally
            (rf/unsubscribe [::n] {:frame app-frame})
            (teardown! root container)))))))

(deftest w5-a-click-inside-the-boundary-dispatches-into-the-named-frame
  (testing "clicking the collapse expander inside the
            popover dispatches `:rf.xray/cancellation-cascade-toggle-expand`,
            the DOM follows, and the event lands in the frame the enclosing
            `frame-provider` NAMED rather than in the ambient one or in a
            neighbouring application frame. Criterion 3."
    (if-not (browser?)
      (is true ":node — the :browser-test runner drives the real React mount")
      (async done
        (setup!)
        (seed-cascade!)
        (let [{:keys [container root]} (mount-popover! :rf/xray)
              toggle (q container "[data-testid=\"rf-xray-cancellation-cascade-expand-toggle\"]")
              before (aborts-shown container)]
          (is (some? toggle)
              "PRECONDITION: the collapse expander rendered — the fixture
               carries more aborts than the collapse threshold, so there is
               a real control to press")
          (is (= 5 before)
              (str "PRECONDITION: the body opens COLLAPSED, showing the "
                   "default five of twelve abort rows. Got: " before))
          (is (false? @(rf/subscribe expanded?-q {:frame :rf/xray}))
              "PRECONDITION: and the expand flag is off in :rf/xray")
          (is (false? @(rf/subscribe expanded?-q {:frame app-frame}))
              "PRECONDITION: and off in the application frame, so the
               cross-frame control below starts from a real zero")

          ;; ---- the act: a REAL browser click on a REAL button -------------
          (when toggle (.click toggle))

          (-> (rf.test-support/poll-until
                #(= 12 (aborts-shown container))
                {:label "the popover committed the expanded abort list"})
              (.then
                (fn [_]
                  (is (= 12 (aborts-shown container))
                      "the click reached the handler, the dispatch landed, the
                       read was invalidated and the boundary committed the
                       expanded list — the whole round trip through a Fresco
                       boundary, in a browser")
                  (is (true? @(rf/subscribe expanded?-q {:frame :rf/xray}))
                      "and the flag flipped in :rf/xray — the frame the
                       enclosing frame-provider named")
                  (is (false? @(rf/subscribe expanded?-q {:frame app-frame}))
                      "CROSS-FRAME CONTROL: and NOT in the application frame.
                       A handler that had lost its captured frame and fallen
                       back to an ambient or default one would write here")))
              (.catch (fn [e]
                        (is false (str "W5 never settled: " (.-message e)
                                       " — aborts-shown: "
                                       (pr-str (aborts-shown container))))
                        nil))
              (.then (fn [_]
                       (rf/unsubscribe expanded?-q {:frame :rf/xray})
                       (rf/unsubscribe expanded?-q {:frame app-frame})
                       (teardown! root container)
                       (done)))))))))
