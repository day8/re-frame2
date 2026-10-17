(ns re-frame.fresco.hydration-on-recoverable-error-dom-cljs-test
  "A Fresco root hydrated through the public door with the host's
  `:on-recoverable-error` composes the framework's report over it, as
  Spec 011 §Hydration-mismatch detection promises of every React-family
  root: inside the adoption window a recovery fans the always-on record and
  emits the dev trace first, then reaches the host callback; after the
  window it reaches the host callback alone, with no hydration label. The
  host callback replaces React's default report rather than running beside
  it, so the recovery is not also raised on the window."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.mount :as rf.fresco.impl.mount]
            [re-frame.fresco.roots-frames-support :as rf.fresco.roots-frames-support]
            [re-frame.interop :as rf.interop]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(def ^:private frame-id ::frame)

(rf.fresco/defview screen
  "One boundary whose title is a prop, so the server and the client
  diverge in nested text by one argument."
  [{:keys [title]}]
  [:div.screen
   [:h1.title title]])

(defn- root-on-recoverable-error
  "The `onRecoverableError` React holds for the root on `container`. A
  public handle is opaque, so it is read off the container's own host-root
  fiber, whose `stateNode` is the same fiber root a `ReactDOMRoot` exposes
  as `_internalRoot`."
  [container]
  (let [k (some #(when (str/starts-with? % "__reactContainer$") %)
                (js-keys container))]
    (some-> (when k (unchecked-get container k)) .-stateNode .-onRecoverableError)))

(deftest a-hydrating-root-composes-the-report-over-the-host-callback
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (rf.fresco.roots-frames-support/skip! ":node-test has no DOM") (done))
      (let [records   (atom [])
            host      (atom [])
            watch     (rf.fresco.roots-frames-support/watch-mismatches!)
            capture   (atom nil)
            handle    (rf.fresco/client-root)
            container (atom nil)]
        (rf.fresco.roots-frames-support/leave-act-environment!)
        (rf.error-emit/register-error-listener!
          ::records
          (fn [r]
            (when (= :rf.ssr/hydration-mismatch (:error r))
              (swap! records conj r)
              true)))
        (rf.fresco.roots-frames-support/settle-row!
          (-> (js/Promise.
                (fn [resolve]
                  (rf/make-frame {:id frame-id})
                  (let [html (rf.fresco.roots-frames-support/server-html!
                               frame-id [screen {:title "server"}])]
                    (reset! container (rf.fresco.roots-frames-support/server-dom! html))
                    (rf.fresco.impl.collector/reset-runtime!)
                    ;; Not swallowing: with a host callback React's default
                    ;; report must not run, so an uncaught window error here
                    ;; is a failure the runner should see as well.
                    (reset! capture (rf.fresco.roots-frames-support/open-console-capture!))
                    (rf.fresco/render! handle
                                       [rf.fresco/frame-provider {:frame frame-id}
                                        [screen {:title "client"}]]
                                       @container
                                       {:hydrate?             true
                                        :on-recoverable-error (fn [error info]
                                                                (swap! host conj [error info]))})
                    (resolve (rf.fresco.roots-frames-support/adopted! handle)))))
              (.then
                (fn [adopted?]
                  (is (true? adopted?) "premise: the root finished adopting")
                  (is (true? rf.interop/debug-enabled?)
                      "premise: a development build, where the trace fires")
                  (testing "inside the window"
                    (is (= 1 (count @host))
                        (str "the host callback saw the nested text mismatch once. Saw: "
                             (pr-str (mapv (comp str first) @host))))
                    (is (some? (second (first @host)))
                        "and was handed React's error info as well as the error")
                    (is (= 1 (count @records))
                        (str "the always-on record is still fanned. Saw: " (pr-str @records)))
                    (let [seen @(:seen watch)]
                      (is (= 1 (count seen))
                          (str "the dev trace still fires. Saw: " (pr-str seen)))
                      (is (= 're-frame.fresco.impl.mount/hydrate-root!
                             (:where (rf.fresco.roots-frames-support/tags-of (first seen)))))))
                  (let [on-recoverable (root-on-recoverable-error @container)
                        later          (js/Error. "after the hydration commit")]
                    (is (fn? on-recoverable) "premise: the root's callback is readable")
                    (testing "after the window"
                      (on-recoverable later #js {})
                      (is (= [later] (mapv first (rest @host)))
                          "a later recovery still reaches the host callback")
                      (is (= 1 (count @records))
                          "and fans no record")
                      (is (= 1 (count @(:seen watch)))
                          "and emits no hydration trace")))
                  (is (not-any? #(str/starts-with? % "window.error") @(:captured @capture))
                      (str "the host callback replaces React's default report. Captured: "
                           (pr-str @(:captured @capture)))))))
          {:row      "a hydrating root composes the report over the host callback"
           :done     done
           :release! (fn []
                       (rf.error-emit/unregister-error-listener! ::records)
                       ((:stop! watch))
                       (when-some [{:keys [close!]} @capture] (close!))
                       (rf.fresco/unmount! handle)
                       (when-some [c @container] (.remove c)))})))))
