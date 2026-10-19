(ns re-frame.fresco.hydration-mismatch-record-dom-cljs-test
  "Fresco's hydrating door reports an adoption mismatch to production error
  sinks. `impl.mount/hydrate-root!` installs `hydration-reporter` on every
  root, whatever `re-frame.interop/debug-enabled?` reads, and inside that
  root's adoption window it fans one structural-only
  `:rf.ssr/hydration-mismatch` record, owned by the door's frame, through
  the always-on error-emit registry. React's message is not in the record:
  in development it carries app text, and the record reaches sinks
  unprojected.

  Debug is off for the whole row, from the server render through React's
  adoption, so every reading is taken in production posture."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react-dom/client" :as react-dom-client]
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

(defn- react-default-on-recoverable-error
  "The `onRecoverableError` React gives a root created with no options."
  []
  (let [container (rf.fresco.impl.mount/fresh-container!)
        root      (react-dom-client/createRoot container)
        f         (.-onRecoverableError (.-_internalRoot root))]
    (.unmount root)
    (.remove container)
    f))

(deftest a-hydrating-root-fans-one-structural-record-with-debug-off
  (async done
    (if-not (rf.fresco.impl.mount/browser?)
      (do (rf.fresco.roots-frames-support/skip! ":node-test has no DOM") (done))
      (let [debug?  rf.interop/debug-enabled?
            records (atom [])
            capture (atom nil)
            handle  (atom nil)]
        (rf.fresco.roots-frames-support/leave-act-environment!)
        (rf.error-emit/register-error-listener!
          ::records
          (fn [r]
            (when (= :rf.ssr/hydration-mismatch (:rf.error/id r))
              (swap! records conj r)
              true)))
        (rf.fresco.roots-frames-support/settle-row!
          (-> (js/Promise.
                (fn [resolve]
                  (set! rf.interop/debug-enabled? false)
                  (rf/make-frame {:id frame-id})
                  (let [html      (rf.fresco.roots-frames-support/server-html!
                                    frame-id [screen {:title "server"}])
                        container (rf.fresco.roots-frames-support/server-dom! html)]
                    (rf.fresco.impl.collector/reset-runtime!)
                    ;; MANUFACTURED here and asserted on here: the reporter
                    ;; delegates to React's default report, which raises the
                    ;; recoverable error on the window.
                    (reset! capture (rf.fresco.roots-frames-support/open-console-capture!
                                      {:swallow-uncaught? true}))
                    (reset! handle (rf.fresco.impl.mount/hydrate-root!
                                     container frame-id [screen {:title "client"}]))
                    (resolve (rf.fresco.roots-frames-support/adopted! @handle)))))
              (.then
                (fn [adopted?]
                  (is (true? adopted?) "premise: the root finished adopting")
                  (testing "inside the window"
                    (is (= 1 (count @records))
                        (str "the nested text mismatch fanned exactly one always-on record. Saw: "
                             (pr-str @records)))
                    (let [rec (first @records)]
                      (is (= #{:rf.error/id :where :recovery :time :frame} (set (keys rec)))
                          (str "the record is structural only. Keys: " (pr-str (sort (keys rec)))))
                      (is (= [:rf.ssr/hydration-mismatch 're-frame.fresco.impl.mount/hydrate-root!
                              :warned-and-replaced frame-id true]
                             [(:rf.error/id rec) (:where rec) (:recovery rec) (:frame rec)
                              (number? (:time rec))]))
                      (is (not-any? string? (vals rec))
                          "no slot carries text, so React's message cannot be in it")))
                  (let [on-recoverable (.-onRecoverableError (.-_internalRoot (:root @handle)))]
                    (testing "the reporter is installed whatever debug reads"
                      (is (not (identical? (react-default-on-recoverable-error) on-recoverable))
                          (str "a root hydrated with debug off carries the door's "
                               "onRecoverableError, not React's default")))
                    (testing "after the window"
                      (on-recoverable (js/Error. "after the hydration commit") #js {})
                      (is (= 1 (count @records))
                          "a recoverable error after the hydration commit fans no record")
                      (is (some #(re-find #"after the hydration commit" %)
                                @(:captured @capture))
                          "and still reaches React's default report"))))))
          {:row      "a hydrating root fans one structural record with debug off"
           :done     done
           :release! (fn []
                       (set! rf.interop/debug-enabled? debug?)
                       (rf.error-emit/unregister-error-listener! ::records)
                       (when-some [{:keys [close!]} @capture] (close!))
                       (when-some [h @handle] (rf.fresco.impl.mount/release! h)))})))))
