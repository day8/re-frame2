(ns re-frame2-pair-mcp.discover-app-reserved-frame-test
  "discover-app's reserved-frame-aware operating-frame resolution.

  An Xray-instrumented app carries a `:rf/xray` tool frame beside its app
  frame. The runtime's `health` excludes `:rf/*` tool frames from the
  ambiguity count and reports `:app-frames`; discover-app shapes the
  result. `:rf/default` is an app frame and is never excluded."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.discover-app :as discover-app]
            [re-frame2-pair-mcp.test-utils :as tu]))

(defn- discover!
  "discover-app on a probed build against `health`, with a fresh JVM half;
  resolves to the payload's EDN."
  [health]
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:examples/my-app} :resolved-build-id nil)
    (-> (tu/with-stubbed-freshness! {:compile-cycle 1 :build-flushed-at 100
                                     :runtime-count 1 :heartbeat-age-ms 50}
          (fn []
            (tu/with-stubbed-eval! health
              (fn [] (discover-app/discover-app conn (tu/args->js {:build "examples/my-app"}))))))
        (.then tu/extract-edn))))

(deftest single-app-plus-xray-is-not-ambiguous
  ;; No warning, the operating frame echoed, and a note naming the
  ;; auto-resolved app frame and the excluded tool frame.
  (async done
    (-> (discover! {:ok?                        true
                    :debug-enabled?             true
                    :coord-annotation-enabled?  true
                    :frames                     [:rf/default :rf/xray]
                    :app-frames                 [:rf/default]
                    :operating-frame            :rf/default
                    :ambiguous-frame?           false})
        (.then
          (fn [edn]
            (is (= {:ok? true :operating-frame :rf/default}
                   (select-keys edn [:ok? :operating-frame :warning])))
            (is (every? #(str/includes? (:note edn) %) [":rf/default" ":rf/xray"]))
            (done))))))

(deftest two-app-frames-plus-xray-stays-ambiguous
  ;; Two app frames are a real choice; the note offers the APP frames only.
  (async done
    (-> (discover! {:ok?                        true
                    :debug-enabled?             true
                    :coord-annotation-enabled?  true
                    :frames                     [:rf/default :stories :rf/xray]
                    :app-frames                 [:rf/default :stories]
                    :operating-frame            nil
                    :ambiguous-frame?           true})
        (.then
          (fn [edn]
            (is (= :ambiguous-frame (:warning edn)))
            (is (str/includes? (:note edn) "[:rf/default :stories]")
                "the note lists the app frames, not the excluded tool frame")
            (done))))))
