(ns re-frame.adapter.uix-hydrate-root-options-dom-cljs-test
  "Hydrating roots preserve useId, bound mismatch reporting to adoption, and reject frame-root."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react" :as react]
            ["react-dom" :as react-dom]
            ["react-dom/server" :as react-dom-server]
            [uix.core :refer-macros [defui $]]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter :async? true :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- host!
  "Attach a fresh container holding the server markup."
  [server-html]
  (let [el (.createElement js/document "div")]
    (set! (.-innerHTML el) server-html)
    (.appendChild (.-body js/document) el)
    el))

(defn- drop-host! [el]
  (when-let [p (.-parentNode el)] (.removeChild p el)))

(defn- poll-until
  "Call k when pred holds or after the bounded wait expires."
  [pred k]
  (let [tries (atom 0)]
    (letfn [(step []
              (if (or (pred) (>= @tries 400))
                (k)
                (do (swap! tries inc) (js/setTimeout step 5))))]
      (step))))

(defn- fiber-root
  "Read the root callback to simulate a recoverable error after adoption."
  [el]
  (some-> (some #(when (.startsWith % "__reactContainer$") (unchecked-get el %))
                (js/Object.keys el))
          .-stateNode))

(defn- listen-for-mismatches!
  "Return a fresh mismatch collector and its listener ID."
  []
  (let [seen (atom [])
        lk   (keyword (gensym "rf.uix-hydrate-root-options-"))]
    (rf.trace.tooling/register-listener!
      lk (fn [ev] (when (= :rf.ssr/hydration-mismatch (:operation ev))
                    (swap! seen conj ev))))
    [seen lk]))

(defn- act-off!
  "Let hydration run on React scheduling; return the restore thunk."
  []
  (let [prev (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)]
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
    (fn [] (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prev))))

(def ^:private probe-ids
  "IDs observed during server and client rendering."
  (atom []))

(def ^:private probe-effects (atom 0))

(defui id-probe []
  (let [id (react/useId)]
    (swap! probe-ids conj id)
    (react/useEffect (fn [] (swap! probe-effects inc) js/undefined) #js [])
    ($ :span {:data-id id} "probe")))

;; A sibling gives useId a tree-fork position, exposing a wrapper that shifts it.
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
          ;; The probe effect marks the hydration commit and its adoption-window closer as settled.
          (poll-until
            #(pos? @probe-effects)
            (fn []
              (try
                (testing "identifier prefix"
                  (is (= server-id (last @probe-ids))
                      (str "the client's useId equals the server's prefixed one: the prefix reached"
                           " React, and the root's own wrapping moved no tree position. Ids: "
                           (pr-str @probe-ids))))
                (testing "after the hydration commit the reporter delegates without emitting"
                  (let [on-recoverable (some-> (fiber-root el) .-onRecoverableError)
                        later          (js/Error. "after the hydration commit")]
                    (when on-recoverable (on-recoverable later #js {}))
                    (is (= [[later] []] [@host-calls @mismatches])
                        "after adoption the host callback receives the error without a hydration-mismatch emit")))
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
                (is (= [[:rf.error/fresco-frame-root-adopting] frame-id false]
                       [(mapv #(:rf.error/id (ex-data %)) @refusals)
                        (:frame (ex-data (first @refusals)))
                        (contains? (set (rf/frame-ids)) frame-id)])
                    (str "one frame-specific refusal before ENSURE creates it: " (pr-str @refusals)))
                (catch :default e
                  (is false (str "assertions threw: " (pr-str e))))
                (finally
                  (.removeEventListener js/window "error" on-error)
                  (try (react-dom/flushSync #(rf.adapter.uix/unmount! h))
                       (catch :default _ nil))
                  (drop-host! el)
                  (restore-act)
                  (done))))))))))
