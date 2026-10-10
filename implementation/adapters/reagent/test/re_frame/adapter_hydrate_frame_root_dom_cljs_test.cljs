(ns re-frame.adapter-hydrate-frame-root-dom-cljs-test
  "A hydrating `render!` through the Reagent adapter whose tree is topped by
  `rf/frame-root` refuses rather than mounting the page beside the server's
  copy. `frame-root`'s first render is empty and React keeps an unclaimed
  server node directly under a root, so without the refusal the server's
  markup stays and the second pass appends the client tree: the page twice,
  with no mismatch reported. The spine's window closer provides the root's
  adoption flag, and the boundary refuses in its first render.

  Real DOM, with act OFF, so React hydrates on its own schedule. The UIx twin
  is the `hydrating-root-topped-by-frame-root-refuses` row of
  `re-frame.adapter.uix-hydrate-root-options-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]))

;; Async rows need the map-form fixture. The tree names its own frame, so no
;; ambient frame.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- host!
  "A fresh container attached to the document (hydration needs a connected
  node), pre-filled with `server-html`."
  [server-html]
  (let [el (.createElement js/document "div")]
    (set! (.-innerHTML el) server-html)
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

(defn- act-off!
  "Leave the act environment so hydration runs on React's own schedule.
  Returns the restore thunk."
  []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (fn [] (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))))

(defn- page [] [:main [:h1 "title"] [:p "same"]])

(def ^:private page-html "<main><h1>title</h1><p>same</p></main>")

(deftest hydrating-root-topped-by-frame-root-refuses
  (testing "frame-root's first render is empty, so a hydrating root topped by
            it would keep the server's markup and mount the page beside it;
            the boundary refuses instead, before any frame is made"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [restore-act (act-off!)
              frame-id    ::adopting-root-frame
              refusals    (atom [])
              ;; React reports a render error no boundary catches at the
              ;; window. The row asserts on it, so it marks the event handled.
              on-error    (fn [^js e]
                            (swap! refusals conj (.-error e))
                            (.preventDefault e))
              _           (.addEventListener js/window "error" on-error)
              el          (host! page-html)
              copies      #(.-length (.querySelectorAll el "main"))
              h           (rf.adapter.reagent/client-root)]
          (try
            (rf.adapter.reagent/render! h [rf/frame-root {:id frame-id} [page]]
                                        el {:hydrate? true})
            (catch :default e
              (swap! refusals conj e)))
          (poll-until
            #(or (seq @refusals) (<= 2 (copies)))
            (fn []
              (try
                (is (< (copies) 2)
                    (str "the page is not mounted beside the server's copy. DOM: " (.-innerHTML el)))
                (is (= [:rf.error/fresco-frame-root-adopting]
                       (mapv #(:rf.error/id (ex-data %)) @refusals))
                    (str "one refusal, naming the adopting frame-root. Saw: " (pr-str @refusals)))
                (is (= frame-id (:frame (ex-data (first @refusals))))
                    "the refusal names the frame-root's :id")
                (is (not (contains? (set (rf/frame-ids)) frame-id))
                    "the refusal came before the commit that would have made the frame")
                (catch :default e
                  (is false (str "assertions threw: " (pr-str e))))
                (finally
                  (.removeEventListener js/window "error" on-error)
                  (try (react-dom/flushSync #(rf.adapter.reagent/unmount! h))
                       (catch :default _ nil))
                  (drop-host! el)
                  (restore-act)
                  (done))))))))))
