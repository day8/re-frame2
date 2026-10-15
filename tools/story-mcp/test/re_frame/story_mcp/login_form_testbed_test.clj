(ns re-frame.story-mcp.login-form-testbed-test
  "The tutorial's login-form stories are readable through Story-MCP on the JVM."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.tools.wire-pipeline :as rf.story-mcp.tools.wire-pipeline]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ^:private variant-ids
  #{:story.login-form/idle
    :story.login-form/submitting
    :story.login-form/error
    :story.login-form/submitting-retry
    :story.login-form/authenticated})

(defn- reset-story [t]
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (rf.story-mcp.config/set-allow-writes! false)
  (rf.story-mcp.config/set-allow-sensitive-reads! false)
  (t))

(use-fixtures :each reset-story)

(defn- invoke [tool-name args]
  (rf.story-mcp.tools.wire-pipeline/invoke-tool tool-name (merge {:dedup false} args)))

(defn- load-testbed! []
  ;; Keep a missing JVM testbed as a named failure rather than a namespace load error.
  (try
    (require 'login-form.stories)
    ((requiring-resolve 'login-form.stories/register-all!))
    nil
    (catch Throwable t t)))

(deftest login-form-stories-hold-on-the-jvm
  (let [load-error (load-testbed!)]
    (is (nil? load-error) (some-> load-error ex-message))
    (when-not load-error
      (testing "list-stories returns the story and all five variants"
        (let [r (invoke "list-stories" {})
              story (first (filter #(= :story.login-form (:id %)) (-> r :structuredContent :stories)))]
          (is (= [true variant-ids] [(not (:isError r)) (set (:variants story))]))))
      (testing "get-variant resolves every variant"
        (doseq [vid variant-ids]
          (let [r (invoke "get-variant" {:variant-id (subs (str vid) 1)})]
            (is (= [true vid] [(not (:isError r)) (-> r :structuredContent :id)])
                (str vid ": " (-> r :content first :text))))))
      (testing "the resolved body retains the authored :script"
        (is (= [[:assert [:rf.assert/state-is :login/flow :submitting-retry]]]
               (-> (invoke "get-variant" {:variant-id "story.login-form/submitting-retry"})
                   :structuredContent :body :script)))))))
