(ns re-frame.ssr.streaming-install-frame-value-dom-cljs-test
  "`streaming-install!` takes its `:frame` as a frame id OR as the frame value
  `rf/make-frame` returns, and a chunk's delta merges into that frame either
  way. The delta merge keys the frame registry by id, so the target is
  normalised before it is used.

  Browser-only: the streaming runtime is a `MutationObserver` + DOM consumer.
  `:node-test` loads this file too and the body exits early where there is no
  DOM."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr :as rf.ssr]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter :ambient-frame nil}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- root-with-resolved-chunk!
  "An attached host holding one boundary's fallback and its resolved chunk,
  built through the server facade so the client reads the real wire bytes."
  [boundary-id delta]
  (let [host   (.createElement js/document "div")
        parser (.createElement js/document "template")]
    (set! (.-innerHTML host)
          (str "<div id=\"app\">"
               (rf.ssr/streaming-fallback-template
                 boundary-id "<div class=\"card skeleton\">loading</div>")
               "</div>"))
    (.appendChild (.-body js/document) host)
    (set! (.-innerHTML parser)
          (str (rf.ssr/streaming-resolved-template
                 boundary-id "<div class=\"card resolved\">done</div>")
               (rf.ssr/streaming-hydrate-delta-script boundary-id (pr-str delta))))
    (.appendChild host (.-content parser))
    host))

(deftest streaming-install-merges-deltas-for-either-spelling
  (testing "a resolved chunk's delta lands in the frame named by an id or
            by a frame value"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test build runs the assertions")
      (doseq [spelling [:id :value]]
        (let [fid         (keyword "rf.frame" (str (gensym "stream-frame-value-")))
              frame-value (rf/make-frame {:id fid :platform :client})
              target      (case spelling :id fid :value frame-value)
              host        (root-with-resolved-chunk! :card.revenue
                                                     {:cards {:revenue 42375}})]
          (try
            (let [stop! (rf.ssr/streaming-install! {:frame target :root host})]
              (try
                (is (= {:cards {:revenue 42375}} (rf.frame/frame-app-db-value fid))
                    (str spelling " target: the delta merged into the frame"))
                (finally (stop!))))
            (finally
              (when-let [p (.-parentNode host)]
                (.removeChild p host)))))))))
