(ns re-frame.story-mcp.login-form-testbed-test
  "The Story tutorial's flagship subject holds on the JVM (rf2-fmrgj).

  `tools/story/testbeds/login_form` is the testbed every `docs/story` chapter
  teaches. Its machine, subs, events and variants are `.cljc`; only its views
  and its browser mount are ClojureScript. So story-mcp over stdio loads the
  same stories a human sees at `#/stories`, and an agent can list them and read
  a variant with no browser in the loop.

  The `:test` alias puts `../story/testbeds` on this classpath for this test
  alone; the published jar carries no testbed.

  The testbed is required INSIDE the test rather than in the `ns` form. On a
  `.cljs`-only testbed that require throws `FileNotFoundException`, and here it
  is one failing assertion naming the missing file, not a namespace that never
  loads and takes the rest of the lane down with it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.story :as rf.story]
            [re-frame.story-mcp.config :as rf.story-mcp.config]
            [re-frame.story-mcp.tools.wire-pipeline :as rf.story-mcp.tools.wire-pipeline]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ^:private variant-ids
  "The testbed's five variants, the set `login-form.stories-cljs-test` pins."
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

(defn- load-testbed!
  "Require the testbed's stories namespace, then re-fire its registrations,
  which the fixture's `clear-all!` wiped. Returns nil, or what the require threw."
  []
  (try
    (require 'login-form.stories)
    ((requiring-resolve 'login-form.stories/register-all!))
    nil
    (catch Throwable t t)))

(deftest login-form-stories-hold-on-the-jvm
  (let [load-error (load-testbed!)]
    (is (nil? load-error)
        (str "login-form.stories loads on the JVM: " (some-> load-error ex-message)))
    (when-not load-error
      (testing "list-stories returns the login_form story and its five variants"
        (let [r       (invoke "list-stories" {})
              stories (-> r :structuredContent :stories)
              story   (first (filter #(= :story.login-form (:id %)) stories))]
          (is (not (:isError r)))
          (is (some? story) (str "listed: " (pr-str (mapv :id stories))))
          (is (= variant-ids (set (:variants story))))))
      (testing "get-variant resolves every variant"
        (doseq [vid variant-ids]
          (let [r (invoke "get-variant" {:variant-id (subs (str vid) 1)})]
            (is (not (:isError r)) (str vid ": " (-> r :content first :text)))
            (is (= vid (-> r :structuredContent :id))))))
      (testing "the resolved body is the testbed's own, :script spelling included"
        (let [r (invoke "get-variant" {:variant-id "story.login-form/submitting-retry"})]
          (is (= [[:assert [:rf.assert/state-is :login/flow :submitting-retry]]]
                 (-> r :structuredContent :body :script))))))))
