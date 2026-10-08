(ns re-frame.story.xray-preset-test
  "The pure `.cljc` surface of the per-story Xray preset: story/variant
  resolution, `lower-filters` and the closed preset map.

  JVM only: this ns name does not match `:node-test`'s `cljs-test$`, so a
  `#?(:cljs …)` test here would run on no host. CLJS behaviour lives in
  `re-frame.story.xray-preset-cljs-test`."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.xray-preset :as rf.story.xray-preset]))

(defn reset-all! []
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

(deftest resolve-preset-merges-story-and-variant
  (doseq [[story-id story-xray variant-xray resolved]
          [[:story.no-preset nil nil nil]
           [:story.story-preset {:open? true :panel :trace} nil {:open? true :panel :trace}]
           ;; the variant wins a slot and a :filters axis; the story's other axis survives
           [:story.both
            {:open? true :panel :epoch :filters {:in [:keep/x] :out [:story/out]}}
            {:panel :trace :filters {:out [:drop/y]}}
            {:open? true :panel :trace :filters {:in [:keep/x] :out [:drop/y]}}]]
          :let [variant-id (keyword (name story-id) "v")]]
    (rf.story/reg-story* story-id (cond-> {:doc "s" :component :Some.view}
                                    story-xray (assoc :xray story-xray)))
    (rf.story/reg-variant* variant-id (cond-> {:doc "v"}
                                        variant-xray (assoc :xray variant-xray)))
    (is (= resolved (rf.story.xray-preset/resolve-preset variant-id)) (str story-id))))

;; Xray reads a bare keyword as a pill that matches nothing, and expects
;; both axes present.
(deftest lower-filters-lowers-story-keywords-to-xray-pills
  (are [filters lowered] (= lowered (rf.story.xray-preset/lower-filters filters))
    {:out [:app/noise]}                 {:in [] :out [{:pattern :app/noise}]}
    {}                                  {:in [] :out []}
    {:in [:a/one :a/two] :out [:b/one]} {:in  [{:pattern :a/one} {:pattern :a/two}]
                                         :out [{:pattern :b/one}]}))

;; `:open?`, `:panel` and `:filters` are the whole preset: an undeclared
;; slot rejects at registration instead of registering and doing nothing.
(deftest xray-preset-rejects-undeclared-slots
  (let [data (try (rf.story/reg-variant* :story.closed-preset/v
                                         {:xray {:panel :trace :focus {:event-pos 5}}})
                  nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (ex-data e)))]
    (is (= :rf.error/variant-shape (:rf.error/id data)))
    (is (not (rf.story/registered? :variant :story.closed-preset/v)))))
