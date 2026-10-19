(ns re-frame.adapter-hydration-mismatch-record-dom-cljs-test
  "A hydrating root reports an adoption mismatch to production error sinks.
  Every hydrating `render!` through the Reagent adapter installs the spine's
  `onRecoverableError`, whatever `re-frame.interop/debug-enabled?` reads,
  and inside the adoption window it fans one structural-only
  `:rf.ssr/hydration-mismatch` record through the always-on error-emit
  registry. React's message is not in the record: in development it carries
  app text, and the record reaches sinks unprojected.

  Debug is off for the whole row, from the render through React's adoption,
  so every reading is taken in production posture. Real DOM, with act OFF,
  so React reports adoption errors on its own schedule."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/client" :as react-dom-client]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.interop :as rf.interop]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- host!
  "A fresh container attached to the document, pre-filled with `html`."
  [html]
  (let [el (.createElement js/document "div")]
    (set! (.-innerHTML el) html)
    (.appendChild (.-body js/document) el)
    el))

(defn- drop-host! [el]
  (when-let [p (.-parentNode el)] (.removeChild p el)))

(defn- poll-until
  "Poll `pred` every 5ms for up to ~2s, then call `k`, so an outcome that
  never arrives fails its assertions rather than hanging the lane."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 400))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

(defn- fiber-root
  "The FiberRoot behind a container — the object holding the
  `onRecoverableError` React was created with — read off the HostRoot fiber
  React records under the container's `__reactContainer$<id>` expando."
  [el]
  (some-> (some #(when (.startsWith % "__reactContainer$") (unchecked-get el %))
                (js/Object.keys el))
          .-stateNode))

(def ^:private effects
  "How many times `effect-probe`'s passive effect has run, across every root."
  (atom 0))

(defn- effect-probe
  "A passive effect in the same flush as the root's window closer, so once it
  has run the hydration commit and its effects, the closer's included, are
  done."
  []
  (react/useEffect (fn [] (swap! effects inc) js/undefined) #js [])
  [:span.probe "probe"])

(defn- page [label] [:p.page label])
(defn- layout [child] [:main [:h1 "title"] child])
(defn- app [label] [:div [layout [page label]] [:f> effect-probe]])

(defn- server-html [label]
  (str "<div><main><h1>title</h1><p class=\"page\">" label "</p></main>"
       "<span class=\"probe\">probe</span></div>"))

(def ^:private record-keys
  "Every key the adoption record may carry. The spine's door does not know
  the frame, so its record carries no `:frame`."
  #{:rf.error/id :where :recovery :time})

(deftest a-hydrating-root-fans-one-structural-record-with-debug-off
  (testing "a nested text mismatch delivers exactly one structural record to
            the error-emit registry with debug off; a later recoverable error
            on the same root delivers none; a clean root hydrated with no host
            callback still carries the framework's reporter"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [prev-act   (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
              debug?     rf.interop/debug-enabled?
              records    (atom [])
              host-calls (atom [])
              text-el    (host! (server-html "server"))
              clean-el   (host! (server-html "same"))
              stock-el   (host! "<p>stock</p>")
              stock      (atom nil)
              handles    [(rf.adapter.reagent/client-root)
                          (rf.adapter.reagent/client-root)]]
          (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
          (reset! effects 0)
          (rf.error-emit/register-error-listener!
            ::records
            (fn [r]
              (when (= :rf.ssr/hydration-mismatch (:rf.error/id r))
                (swap! records conj r)
                true)))
          (set! rf.interop/debug-enabled? false)
          (try
            (rf.adapter.reagent/render! (handles 0) [app "client"] text-el
                                        {:hydrate? true
                                         :on-recoverable-error (fn [e _] (swap! host-calls conj e))})
            (rf.adapter.reagent/render! (handles 1) [app "same"] clean-el {:hydrate? true})
            (reset! stock (react-dom-client/hydrateRoot stock-el (react/createElement "p" nil "stock")))
            (catch :default e
              (is false (str "hydrating render! threw: " (pr-str e)))))
          (poll-until
            #(and (seq @host-calls) (<= 2 @effects))
            (fn []
              (try
                (testing "inside the window"
                  (is (= 1 (count @host-calls))
                      "premise: React reported the nested text mismatch once, to the host")
                  (is (= 1 (count @records))
                      (str "the mismatch fanned exactly one always-on record, and the clean "
                           "root none. Saw: " (pr-str @records)))
                  (let [rec (first @records)]
                    (is (= record-keys (set (keys rec)))
                        (str "the record is structural only. Keys: " (pr-str (sort (keys rec)))))
                    (is (= [:rf.ssr/hydration-mismatch 're-frame.substrate.spine/make-render
                            :warned-and-replaced true]
                           [(:rf.error/id rec) (:where rec) (:recovery rec) (number? (:time rec))]))
                    (is (not-any? string? (vals rec))
                        "no slot carries text, so React's message cannot be in it")))
                (testing "the reporter is installed whatever debug reads"
                  (is (not (identical? (some-> (fiber-root stock-el) .-onRecoverableError)
                                       (some-> (fiber-root clean-el) .-onRecoverableError)))
                      (str "a clean root hydrated with no host callback and debug off carries "
                           "the framework's onRecoverableError, not React's default")))
                (testing "after the window"
                  (let [later (js/Error. "after the hydration commit")]
                    (when-some [on-recoverable (some-> (fiber-root text-el) .-onRecoverableError)]
                      (on-recoverable later #js {}))
                    (is (= 1 (count @records))
                        "a recoverable error after the hydration commit fans no record")
                    (is (identical? later (last @host-calls))
                        "and still reaches the host callback")))
                (catch :default e
                  (is false (str "assertions threw: " (pr-str e))))
                (finally
                  (set! rf.interop/debug-enabled? debug?)
                  (rf.error-emit/unregister-error-listener! ::records)
                  (doseq [h handles]
                    (try (react-dom/flushSync #(rf.adapter.reagent/unmount! h))
                         (catch :default _ nil)))
                  (when-some [r @stock]
                    (try (react-dom/flushSync #(.unmount r)) (catch :default _ nil)))
                  (run! drop-host! [text-el clean-el stock-el])
                  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev-act)
                  (done))))))))))
