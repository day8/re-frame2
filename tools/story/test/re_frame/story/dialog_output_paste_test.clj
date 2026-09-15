(ns re-frame.story.dialog-output-paste-test
  "rf2-yemtm, rf2-0ae7o.6 — the output of each of Story's copy-to-source
  emitters pastes and runs VERBATIM in a stories namespace that follows the
  require-alias dialect (spec/Conventions.md §Require-alias dialect:
  `re-frame.story` is aliased `rf.story`, as the login_form testbed's
  `stories.cljc` is).

  Journey 1 of the 2026-09 parity measurement (rf2-a1v8a) had to rewrite
  `story/` to `rf.story/` once per dialog before any of them compiled. Each
  test pins the emitted head, then takes the snippet down the path an author
  takes: read it, compile it where `re-frame.story` is required under its
  canonical alias and no other, and find the variant it names registered.

  rf2-yemtm fixed the three authoring dialogs (save as new variant, real-setup
  upgrade, add expectations); rf2-0ae7o.6 the remaining four JVM-reachable
  emitters (the recorder's save and export dialogs, promote run to regression
  variant, and the sub-override value entry). The Share dialog's Copy EDN
  emitter is CLJS-only and is pinned in `ui/share_egress_cljs_test.cljs`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.artifact :as rf.story.artifact]
            [re-frame.story.author-expectations :as rf.story.author-expectations]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.recorder.play-export :as rf.story.recorder.play-export]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.save-variant :as rf.story.save-variant]
            [re-frame.story.ui.promotion :as rf.story.ui.promotion]
            [re-frame.story.ui.schema-form :as rf.story.ui.schema-form]
            [re-frame.story.ui.view-state :as rf.story.ui.view-state]))

(defn- clean-registry [test-fn]
  (rf.story/clear-all!)
  ;; A fresh framework runtime, so a pasted variant can RUN as well as
  ;; register (the setup `view-state-upgrade-test` uses).
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-event :paste/submit
    (fn [{:keys [db]} _] {:db (update db :submits (fnil inc 0))}))
  (rf.story/reg-story :story.paste {:component :paste/card})
  (rf.story/reg-variant :story.paste/source {:args {:heading "Sign in"}})
  (rf.story/reg-variant :story.paste/pinned
    {:extends       :story.paste/source
     :sub-overrides {[:paste/state] :authenticated}})
  (try (test-fn)
       (finally (rf.story/clear-all!))))

(use-fixtures :each clean-registry)

(defn- paste!
  "Compile `snippet` the way pasting it into a stories namespace does: read
  its first form and evaluate it in a namespace that requires
  `re-frame.story` as `rf.story` and under no other alias."
  [snippet]
  (binding [*ns* (create-ns 're-frame.story.dialog-output-paste-test.stories)]
    (refer-clojure)
    (alias 'rf.story 're-frame.story)
    (eval (read-string snippet))))

(defn- registered [variant-id]
  (rf.story.registrar/handler-meta :variant variant-id))

(deftest save-as-new-variant-output-pastes-verbatim
  (let [snippet (rf.story.save-variant/gen-variant-snippet
                  {:variant-id :story.paste/saved
                   :extends    :story.paste/source
                   :args       {:heading "Welcome back"}})]
    (is (str/starts-with? snippet "(rf.story/reg-variant :story.paste/saved\n"))
    (paste! snippet)
    (is (= {:extends :story.paste/source :args {:heading "Welcome back"}}
           (select-keys (registered :story.paste/saved) [:extends :args])))
    (testing "control — the spelling J1 had to translate does not compile there"
      (is (thrown? Exception (paste! (str/replace snippet "(rf.story/" "(story/")))))))

(deftest real-setup-upgrade-output-pastes-verbatim
  (let [snippet (rf.story.ui.view-state/upgrade-snippet :story.paste/pinned :real-setup)]
    (is (str/starts-with? snippet "(rf.story/reg-variant :story.paste/pinned-upgraded\n"))
    (paste! snippet)
    (let [body (registered :story.paste/pinned-upgraded)]
      (is (= :story.paste/source (:extends body)))
      (is (= [[:dispatch [:your/setup-event {}]]] (:setup body)))
      (is (not (contains? body :sub-overrides))))))

(deftest add-expectations-output-pastes-verbatim
  (let [snippet (rf.story.author-expectations/gen-expectations-snippet
                  {:variant-id :story.paste/expects
                   :extends    :story.paste/source
                   :authored   [[:rf.assert/path-equals [:paste :value] 5]]})]
    (is (str/starts-with? snippet "(rf.story/reg-variant :story.paste/expects\n"))
    (paste! snippet)
    (let [body (registered :story.paste/expects)]
      (is (= :story.paste/source (:extends body)))
      (is (= [[:rf.assert/path-equals [:paste :value] 5]] (:assertions body)))
      (is (contains? (:tags body) :test)))))

(defn- story-alias-fails-to-paste?
  "Control — the spelling J1 had to translate does not compile in a namespace
  whose only Story alias is `rf.story`."
  [snippet]
  (try (paste! (str/replace snippet "(rf.story/" "(story/"))
       false
       (catch Exception _ true)))

(deftest recorder-save-dialog-output-pastes-verbatim
  (let [snippet (rf.story.recorder/gen-play-snippet
                  [[:paste/inc]]
                  {:variant-id :story.paste/recorded
                   :extends    :story.paste/source})]
    (is (str/starts-with? snippet "(rf.story/reg-variant :story.paste/recorded\n"))
    (paste! snippet)
    (let [body (registered :story.paste/recorded)]
      (is (= :story.paste/source (:extends body)))
      (is (str/includes? (pr-str (:script body)) "[:dispatch-sync [:paste/inc]]")))
    (is (story-alias-fails-to-paste? snippet))))

(deftest recorder-export-dialog-output-pastes-verbatim
  (let [spec    (rf.story.recorder.play-export/recording->script-body
                  [[:paste/inc]] {:name "happy path"})
        snippet (rf.story.recorder.play-export/render-variant-form
                  spec {:variant-id :story.paste/exported
                        :extends    :story.paste/source})]
    (is (str/starts-with? snippet "(rf.story/reg-variant :story.paste/exported\n"))
    (paste! snippet)
    (let [body (registered :story.paste/exported)]
      (is (= :story.paste/source (:extends body)))
      (is (str/includes? (pr-str (:script body)) "[:dispatch [:paste/inc]]")))
    (is (story-alias-fails-to-paste? snippet))))

(deftest promote-run-output-pastes-verbatim
  (let [artifact (rf.story.artifact/make-run-artifact
                   {:event-program [[:dispatch [:paste/inc]]]
                    :result        {:status :fail :variant/id :story.paste/source}})
        snippet  (rf.story.ui.promotion/promotion-snippet
                   artifact {:variant-id :story.paste/regression
                             :extends    :story.paste/source
                             :tags       #{:test}})]
    (is (str/starts-with? snippet "(rf.story/reg-variant :story.paste/regression\n"))
    (paste! snippet)
    (let [body (registered :story.paste/regression)]
      (is (= :story.paste/source (:extends body)))
      (is (contains? body :run-artifact)))
    (is (story-alias-fails-to-paste? snippet))))

(deftest sub-override-value-entry-output-pastes-verbatim
  (let [snippet (rf.story.ui.schema-form/override-snippet
                  :story.paste/source [:paste/state] :error)]
    (is (str/starts-with? snippet "(rf.story/reg-variant :story.paste/source-pinned\n"))
    (paste! snippet)
    (let [body (registered :story.paste/source-pinned)]
      (is (= :story.paste/source (:extends body)))
      (is (= {[:paste/state] :error} (:sub-overrides body))))
    (is (story-alias-fails-to-paste? snippet))))

;; ---- rf2-0ae7o.11 — pasted as-is, a recording RUNS ------------------------

(defn- run-result
  "Run `variant-id` through the real runner (`story/run`, headless) and
  return the unified run result."
  [variant-id]
  (.get ^java.util.concurrent.CompletableFuture (rf.story/run variant-id)))

(deftest recorder-save-dialog-recording-runs-when-pasted
  (testing "rf2-0ae7o.11 (b): the save dialog's snippet, pasted as-is, runs its
            recorded dispatches instead of registering a script nothing runs"
    (let [{:keys [snippet]} (rf.story.recorder.play-export/save-dialog-output
                              [{:kind :event/dispatch :event [:paste/submit] :t 0}]
                              {:variant-id :story.paste/recorded-flow
                               :extends    :story.paste/source})]
      (paste! snippet)
      (is (= 1 (get-in (run-result :story.paste/recorded-flow) [:app-db :submits]))
          "the recorded dispatch executed"))))

(deftest recorder-save-dialog-click-recording-refuses-headless
  (testing "rf2-0ae7o.11 (b): a pasted recording of a click is not proved by a
            headless run — :cannot-run (spec/017 §Requirement inference), never a
            :pass over a script that never ran"
    (let [{:keys [snippet]} (rf.story.recorder.play-export/save-dialog-output
                              [{:kind :dom/click :selector "[data-test=\"paste-submit\"]" :t 0}]
                              {:variant-id :story.paste/recorded-click
                               :extends    :story.paste/source})]
      (paste! snippet)
      (is (= :cannot-run (:status (run-result :story.paste/recorded-click)))))))
