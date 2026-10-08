(ns re-frame.reg-view-injection-test
  "`reg-view` injects `dispatch` / `subscribe` nouns built on one render-time
  `make-capture-frame`, passing the view's definition-site coord so a UI
  dispatch carries `:source :ui` and a `:rf.trace/call-site` (Xray's 'go to
  code'), and a subscribe error carries the same call-site.

  On the JVM `reg-view*` registers the raw render fn, so calling the view yields
  hiccup whose `:on-click` fires a real dispatch. Call-sites are dev-only (the
  expansion test pins the production branch), so they are read in
  `(when rf.interop/debug-enabled? ...)` arms; the dispatch landing in the
  render-time frame and the always-on `:rf.error/no-such-sub` record hold in
  both postures."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

(defn- clicked?
  "The injected `dispatch` enqueues, so its write lands on the drain: poll."
  [frame-id]
  (try (rf.test-support/poll-until
         #(true? (:clicked? (rf/app-db-value frame-id)))
         {:timeout-ms 2000 :label (str "clicked? " frame-id)})
       (catch Exception _ false)))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- on-click-of [view-fn]
  (:on-click (second (view-fn 0))))

(deftest injected-dispatch-stamps-the-view-coord-and-keeps-the-render-time-frame
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try
      (rf/make-frame {:id :rf2-cry25/render-frame :doc "the render-time frame"})
      (rf/make-frame {:id :rf2-cry25/other-frame :doc "must stay untouched"})
      (rf/reg-event :rf2-cry25/clicked
        (fn [{:keys [db]} _] {:db (assoc db :clicked? true)}))
      (rf/reg-view click-view [_n]
        [:button {:on-click #(dispatch [:rf2-cry25/clicked])} "go"])
      ;; render under the frame, click after the scope has unwound
      (let [click (rf/with-frame :rf2-cry25/render-frame
                    (on-click-of (rf/view :re-frame.reg-view-injection-test/click-view)))]
        (click))
      (is (true? (clicked? :rf2-cry25/render-frame))
          "the click dispatched into the render-time frame")
      (is (nil? (:clicked? (rf/app-db-value :rf2-cry25/other-frame))))
      (when rf.interop/debug-enabled?
        (let [ev (->> @seen
                      (filter #(= :rf.event/dispatched (:operation %)))
                      (filter #(= [:rf2-cry25/clicked] (get-in % [:tags :rf.event/v])))
                      first)
              cs (:rf.trace/call-site ev)]
          (is (= :ui (:source ev)))
          (is (= 're-frame.reg-view-injection-test (:ns cs)) "the view's definition site")
          (is (integer? (:line cs)))))
      (finally (rf/unregister-listener! :trace ::rec)))))

(deftest injected-subscribe-stamps-view-call-site-on-error
  (let [seen    (atom [])
        records (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (rf.error-emit/register-error-listener! ::err (fn [r] (swap! records conj r)))
    (try
      (rf/make-frame {:id :rf2-cry25/sub-frame :doc "the render-time frame"})
      (rf/reg-view sub-view [_n]
        [:span @(subscribe [:rf2-cry25/missing])])
      (rf/with-frame :rf2-cry25/sub-frame
        ((rf/view :re-frame.reg-view-injection-test/sub-view) 0))
      (is (= [:rf2-cry25/sub-frame]
             (mapv :frame (filterv #(= :rf.error/no-such-sub (:error %)) @records)))
          "one always-on record, attributed to the render-time frame")
      (when rf.interop/debug-enabled?
        (let [err (->> @seen
                       (filter #(= :rf.error/no-such-sub (get-in % [:tags :category])))
                       first)]
          (is (= 're-frame.reg-view-injection-test (:ns (:rf.trace/call-site err))))))
      (finally
        (rf.error-emit/unregister-error-listener! ::err)
        (rf/unregister-listener! :trace ::rec)))))

;; Each injected arg rides its own (if rf.interop/debug-enabled? <dev> <prod>)
;; gate so Closure DCEs the dev coord under goog.DEBUG=false; the CLJS bundle
;; probe (scripts/check-elision.cjs) pins the absence, this pins the branches.

(defn- find-form [pred form]
  (cond
    (pred form) form
    (coll? form) (some #(find-form pred %) form)
    :else nil))

(deftest injected-args-ride-debug-gate-with-dev-and-prod-branches
  (let [exp       (rf/expand-reg-view {:line 7 :column 4 :file "v.cljc"}
                                      'my.ns "v.cljc" 'cv
                                      '([] [:button {:on-click #(dispatch [:x])}]))
        mfh-call  (find-form #(and (seq? %)
                                   (= 're-frame.capture-frame/make-capture-frame (first %)))
                             exp)
        opts-map  (nth mfh-call 2)
        disp-gate (:dispatch-opts opts-map)
        sub-gate  (:subscribe-call-site opts-map)
        gated?    #(= ['if 're-frame.interop/debug-enabled?] (take 2 %))]
    (is (gated? disp-gate))
    (is (= {:source :ui} (nth disp-gate 3))
        "production keeps :source :ui and drops the call-site")
    (is (gated? sub-gate))
    (is (nil? (nth sub-gate 3)))))
