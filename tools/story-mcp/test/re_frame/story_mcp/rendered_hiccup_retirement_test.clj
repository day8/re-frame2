(ns re-frame.story-mcp.rendered-hiccup-retirement-test
  "The live Story/MCP boundary omits rendering output and options;
  render-variant remains the explicit rendering authority."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as rf.schemas]
            [re-frame.story :as rf.story]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.render]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.tools.registry :as rf.story-mcp.tools.registry]
            [re-frame.story-mcp.tools.wire-pipeline :as rf.story-mcp.tools.wire-pipeline]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ^:private retired-result-slot :rendered-hiccup)
(def ^:private retired-run-opt :render?)

(defn reset-story [t]
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (rf.story-mcp.config/set-allow-writes! false)
  (rf.story-mcp.config/set-allow-sensitive-reads! false)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.story.recorder/clear!)
  ;; Keep epoch evidence out of the wire budget on a wider test classpath.
  (rf/configure! {:epoch-history {:depth 0}})
  (rf.story/reg-story :story.cart
    {:doc "A cart." :component :app.ui/cart :tags #{:dev :test} :args {}})
  (rf.story/reg-variant :story.cart/full
    {:doc "A cart variant with a view." :tags #{:dev :test} :args {:label "Checkout"}})
  (try (t)
       (finally (rf/configure! {:epoch-history {:depth 50}}))))

(use-fixtures :each reset-story)

(defn- invoke [tool-name args]
  (rf.story-mcp.tools.wire-pipeline/invoke-tool tool-name (merge {:dedup false} args)))

(defn- run-real! [opts]
  (deref (rf.story/run-variant :story.cart/full opts) 15000 ::timed-out))

(deftest real-run-variant-emits-no-rendering-slot
  (let [outcome (run-real! nil)]
    (is (= [true false]
           [(rf.story/valid-run-result? outcome) (contains? outcome retired-result-slot)])
        (str (rf.story/explain-run-result outcome))))
  (let [outcome (run-real! {retired-run-opt true})]
    (is (= [true false] [(not= ::timed-out outcome) (contains? outcome retired-result-slot)]))))

(deftest real-mcp-run-and-preview-payloads-carry-no-rendering-slot
  (doseq [tool ["run-variant" "preview-variant"]]
    (let [r (invoke tool {:variant-id "story.cart/full"})
          s (:structuredContent r)]
      (is (= [true true true true false]
             [(not (true? (:isError r))) (map? s) (contains? s :app-db)
              (some? (:status s)) (contains? s retired-result-slot)])
          (str tool ": " (pr-str r))))))

(deftest no-tool-descriptor-advertises-rendered-output
  (doseq [{:keys [name description inputSchema]} rf.story-mcp.tools.registry/tool-registry]
    (is (= [false false]
           [(string/includes? (str description) "rendered-hiccup")
            (contains? (:properties inputSchema) :render?)])
        name)))

(deftest render-variant-remains-the-rendering-authority
  (is (some? (resolve 're-frame.story.render/render-variant))
      "render-variant is the explicit visual-rendering API"))
