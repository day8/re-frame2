(ns re-frame.adapter.uix-hydrate-root-options-dom-cljs-test
  "A hydrating `render!` through the UIx adapter creates its React root with
  the options every React-family hydrating root gets: `:identifier-prefix` as
  React's `identifierPrefix`, and the composed `onRecoverableError` reporter
  bounded to the adoption window. The spine's after-render sentinel and window
  closer each render the tree as their only child, so a client `useId` equals
  the one the server rendered for the bare element. A hydrating root topped by
  `frame-root` refuses rather than mounting the page beside the server's copy.

  Real DOM, with act OFF, so React hydrates on its own schedule. The Reagent
  twins are `re-frame.adapter-hydrate-root-options-dom-cljs-test` and
  `re-frame.adapter-hydrate-frame-root-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/server" :as react-dom-server]
            [uix.core :as uix :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; Async rows need the map-form fixture. The trees carry no ambient frame.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true :ambient-frame nil}))

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

(defn- fiber-root
  "The FiberRoot behind a container — the object holding the root options
  React was created with — read off the HostRoot fiber React records under
  the container's `__reactContainer$<id>` expando."
  [el]
  (some-> (some #(when (.startsWith % "__reactContainer$") (unchecked-get el %))
                (js/Object.keys el))
          .-stateNode))

(defn- listen-for-mismatches!
  "Collect every `:rf.ssr/hydration-mismatch` trace into a fresh atom.
  Returns `[atom listener-id]`."
  []
  (let [seen (atom [])
        lk   (keyword (gensym "rf.uix-hydrate-root-options-"))]
    (rf.trace.tooling/register-listener!
      lk (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                    (swap! seen conj ev))))
    [seen lk]))

(defn- act-off!
  "Leave the act environment so hydration runs on React's own schedule.
  Returns the restore thunk."
  []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (fn [] (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))))

;; ---- prefix, the window after commit, and later updates -------------------

(def ^:private probe-ids
  "Every id `id-probe` has read, server render and client render alike."
  (atom []))

(def ^:private probe-effects (atom 0))

(defui id-probe []
  (let [id (react/useId)]
    (swap! probe-ids conj id)
    (react/useEffect (fn [] (swap! probe-effects inc) js/undefined) #js [])
    ($ :span {:data-id id} "probe")))

;; A sibling ahead of the probe, so the probe's useId carries a tree-fork
;; position: a wrapper that added a sibling at the root would shift it.
(defui id-tree []
  ($ :section ($ :h2 "ids") ($ id-probe)))

(deftest hydrated-root-carries-prefix-closes-its-window-and-stays-live
  (testing "the :identifier-prefix reaches React (a client useId carries it and
            matches the server's), a recoverable error after the hydration
            commit reaches the host without the framework emit, and the
            hydrated root still updates"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (async done
        (let [restore-act     (act-off!)
              [mismatches lk] (listen-for-mismatches!)
              host-calls      (atom [])
              prefix          "rf-pfx-"
              server-html     (.renderToString react-dom-server ($ id-tree)
                                               #js {:identifierPrefix prefix})
              server-id       (last @probe-ids)
              _               (reset! probe-ids [])
              _               (reset! probe-effects 0)
              el              (host! server-html)
              h               (rf.adapter.uix/client-root)]
          (try
            (rf.adapter.uix/render! h ($ id-tree) el
                                    {:hydrate? true
                                     :identifier-prefix prefix
                                     :on-recoverable-error (fn [e _] (swap! host-calls conj e))})
            (catch :default e
              (is false (str "hydrating render! threw: " (pr-str e)))))
          ;; The probe's passive effect runs in the same flush as the window
          ;; closer's, so once it has run the hydration commit and its effects
          ;; are done.
          (poll-until
            #(pos? @probe-effects)
            (fn []
              (try
                (testing "identifier prefix"
                  (is (= prefix (some-> (fiber-root el) .-identifierPrefix))
                      "the root was created with the prefix as identifierPrefix")
                  (is (some-> (last @probe-ids) (.includes prefix))
                      (str "a client useId carries the prefix. Ids: " (pr-str @probe-ids)))
                  (is (= server-id (last @probe-ids))
                      "the client's useId equals the server's: the root's own wrapping moved no tree position"))
                (testing "a clean hydration reports nothing"
                  (is (empty? @mismatches) (str "no trace for a clean root. Saw: " (pr-str @mismatches)))
                  (is (empty? @host-calls) "no host call for a clean root"))
                (testing "after the hydration commit the reporter delegates without emitting"
                  (let [on-recoverable (some-> (fiber-root el) .-onRecoverableError)
                        later          (js/Error. "after the hydration commit")]
                    (when on-recoverable (on-recoverable later #js {}))
                    (is (= [later] @host-calls)
                        "the root's onRecoverableError still reaches the host callback")
                    (is (empty? @mismatches)
                        "a recoverable error after the hydration commit is not a hydration mismatch")))
                (testing "the hydrated root still updates"
                  (react-dom/flushSync #(rf.adapter.uix/render! h ($ :p {:class "later"} "v2") el))
                  (is (= "v2" (some-> (.querySelector el "p.later") .-textContent))
                      "a later render! committed through the hydrated root"))
                (catch :default e
                  (is false (str "assertions threw: " (pr-str e))))
                (finally
                  (rf.trace.tooling/unregister-listener! lk)
                  (try (react-dom/flushSync #(rf.adapter.uix/unmount! h))
                       (catch :default _ nil))
                  (drop-host! el)
                  (restore-act)
                  (done))))))))))

;; ---- a hydrating root topped by frame-root refuses ------------------------

(defui page []
  ($ :main ($ :h1 "title") ($ :p "same")))

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
              h           (rf.adapter.uix/client-root)]
          (try
            (rf.adapter.uix/render! h ($ rf.adapter.uix/frame-root {:id frame-id} ($ page))
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
                  (try (react-dom/flushSync #(rf.adapter.uix/unmount! h))
                       (catch :default _ nil))
                  (drop-host! el)
                  (restore-act)
                  (done))))))))))
