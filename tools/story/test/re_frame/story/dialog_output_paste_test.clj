(ns re-frame.story.dialog-output-paste-test
  "The output of Story's copy-to-source emitters pastes and runs VERBATIM
  in a stories namespace that follows the require-alias dialect
  (spec/Conventions.md §Require-alias dialect: `re-frame.story` is aliased
  `rf.story`, as the login_form testbed's `stories.cljc` is). Each test
  reads the snippet, compiles it where `re-frame.story` is required under
  that alias and no other, and finds the variant it names registered, or
  runs it.

  The save-as-new-variant and `gen-play-snippet` emitters' exact text is
  pinned in `story_save_variant_test` and `story_recorder_test`. The Share
  dialog's Copy EDN emitter is CLJS-only and is pinned in
  `ui/share_egress_cljs_test.cljs`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Loaded for its late-bind hooks: `:rf.assert/dispatched?` reads
            ;; the epoch tape, which is empty without the epoch artefact.
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.artifact :as rf.story.artifact]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.recorder.play-export :as rf.story.recorder.play-export]
            [re-frame.story.recorder.play-export-events :as rf.story.recorder.play-export-events]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.author-expectations :as rf.story.ui.author-expectations]
            [re-frame.story.ui.promotion :as rf.story.ui.promotion]
            [re-frame.story.ui.schema-form :as rf.story.ui.schema-form]
            [re-frame.story.ui.view-state :as rf.story.ui.view-state]))

(defn- clean-registry [test-fn]
  (rf.story/clear-all!)
  ;; A fresh framework runtime, so a pasted variant can RUN as well as
  ;; register.
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
      (is (contains? body :run-artifact)))))

(deftest sub-override-value-entry-output-pastes-verbatim
  (let [snippet (rf.story.ui.schema-form/override-snippet
                  :story.paste/source [:paste/state] :error)]
    (is (str/starts-with? snippet "(rf.story/reg-variant :story.paste/source-pinned\n"))
    (paste! snippet)
    (let [body (registered :story.paste/source-pinned)]
      (is (= :story.paste/source (:extends body)))
      (is (= {[:paste/state] :error} (:sub-overrides body))))))

(defn- run-result
  "Run `variant-id` through the real runner (`story/run`, headless) and
  return the unified run result."
  [variant-id]
  (.get ^java.util.concurrent.CompletableFuture (rf.story/run variant-id)))

(deftest recorder-save-dialog-recording-runs-when-pasted
  (testing "the save dialog's snippet runs its recorded dispatches rather
            than registering a script nothing runs"
    (let [{:keys [snippet]} (rf.story.recorder.play-export/save-dialog-output
                              [{:kind :event/dispatch :event [:paste/submit] :t 0}]
                              {:variant-id :story.paste/recorded-flow
                               :extends    :story.paste/source})]
      (paste! snippet)
      (is (= 1 (get-in (run-result :story.paste/recorded-flow) [:app-db :submits]))))))

(defn- add-expectations-snippet
  "What the add-expectations dialog emits for `source-id` with one authored
  `:dispatched` expectation on `event`, built by the dialog's own
  `build-snippet`, which reads the source's registered body."
  [source-id new-id event]
  (rf.story.ui.author-expectations/build-snippet
    {:source-id source-id
     :draft     (-> (rf.story.ui.author-expectations/initial-draft)
                    (rf.story.ui.author-expectations/set-variant-id new-id)
                    (rf.story.ui.author-expectations/add-row :dispatched)
                    (rf.story.ui.author-expectations/set-operand 0 :event (pr-str event)))}))

(deftest add-expectations-to-a-scripted-story-replays-its-script
  (testing ":script and :plays are child-only through :extends, so the
            emitted variant re-declares the source's own and replays it"
    (rf.story/reg-variant :story.paste/scripted
      {:extends    :story.paste/source
       :script     [[:dispatch [:paste/submit]]]
       :assertions [[:rf.assert/path-equals [:submits] 1]]})
    (rf.story/reg-variant :story.paste/played
      {:extends    :story.paste/source
       :plays      [{:name "submit twice"
                     :script [[:dispatch [:paste/submit]]
                              [:dispatch [:paste/submit]]]}]
       :assertions [[:rf.assert/path-equals [:submits] 2]]})
    (doseq [[source-id new-id submits]
            [[:story.paste/scripted :story.paste/scripted-expects 1]
             [:story.paste/played   :story.paste/played-expects   2]]]
      (paste! (add-expectations-snippet source-id new-id :paste/submit))
      (let [result (run-result new-id)]
        (is (= :pass (:status result))
            (str new-id " — the source's assertion and the authored one both hold"))
        (is (= submits (get-in result [:app-db :submits]))
            (str new-id " — the source's interaction ran"))))))

(deftest add-expectations-to-a-composing-story-carries-its-compose
  (testing ":compose is child-only through :extends (spec/017 §`:compose`),
            so the emitted variant re-declares the source's own"
    (rf.story/reg-fragment :fragment.paste/submitted
      {:setup [[:dispatch [:paste/submit]]]})
    (rf.story/reg-variant :story.paste/composed
      {:extends    :story.paste/source
       :compose    [:fragment.paste/submitted]
       :assertions [[:rf.assert/path-equals [:submits] 1]]})
    (paste! (add-expectations-snippet :story.paste/composed
                                      :story.paste/composed-expects
                                      :paste/submit))
    (let [result (run-result :story.paste/composed-expects)]
      (is (= :pass (:status result)))
      (is (= 1 (get-in result [:app-db :submits]))
          "the composed fragment's setup ran"))))

(defn- no-such-handler-record [result]
  (first (filter #(and (= :rf.error/exception (:assertion %))
                       (= :rf.error/no-such-handler (:operation %)))
                 (:assertions result))))

(deftest real-setup-upgrade-output-unfilled-does-not-pass
  (testing "pasted with its :your/setup-event placeholder left in, the
            real-setup upgrade scaffold is refused, never a vacuous :pass"
    (paste! (rf.story.ui.view-state/upgrade-snippet :story.paste/pinned :real-setup))
    (let [result (run-result :story.paste/pinned-upgraded)]
      (is (not= :pass (:status result)))
      (is (= {:event [:your/setup-event {}] :phase :phase-2-events}
             (select-keys (no-such-handler-record result) [:event :phase])))))
  (testing "CONTROL: once the placeholder names a registered handler the
            same scaffold passes and its setup runs"
    (rf/reg-event :your/setup-event (fn [{:keys [db]} _] {:db (assoc db :setup-ran? true)}))
    (paste! (rf.story.ui.view-state/upgrade-snippet :story.paste/pinned :real-setup))
    (let [result (run-result :story.paste/pinned-upgraded)]
      (is (= :pass (:status result)))
      (is (true? (get-in result [:app-db :setup-ran?]))))))

(deftest recorder-save-dialog-click-recording-refuses-headless
  (testing "a pasted click recording is :cannot-run headless (spec/017
            §Requirement inference), never a :pass over a script that never ran"
    (let [{:keys [snippet]} (rf.story.recorder.play-export/save-dialog-output
                              [{:kind :dom/click :selector "[data-test=\"paste-submit\"]" :t 0}]
                              {:variant-id :story.paste/recorded-click
                               :extends    :story.paste/source})]
      (paste! snippet)
      (is (= :cannot-run (:status (run-result :story.paste/recorded-click)))))))

(deftest recorder-export-auto-assert-asserts-what-the-recording-changed
  (testing "against a db carrying static keys and Story's own
            :rf.story/assertions records, the export dialog's default
            auto-assert pins only the path the recording changed, and the
            pasted form runs green"
    (rf/reg-event :paste/seed-static
      (fn [{:keys [db]} _]
        {:db (merge db {:static-a 1 :static-b 2 :static-c 3
                        :static-d 4 :static-e 5 :static-f 6})}))
    (rf.story/reg-variant :story.paste/asserted
      {:extends    :story.paste/source
       :setup      [[:dispatch [:paste/seed-static]]]
       :assertions [[:rf.assert/path-equals [:submits] nil]]})
    (rf.story.async/deref-blocking (rf.story/run-variant :story.paste/asserted) 5000)
    (rf.story.recorder/install-trace-listener!)
    (try
      (rf.story.recorder/start-recording! :story.paste/asserted)
      (rf/dispatch-sync [:paste/submit] {:frame :story.paste/asserted})
      (rf/dispatch-sync [:paste/submit] {:frame :story.paste/asserted})
      (rf.story.recorder/stop-recording!)
      (let [final-db  (rf.story.recorder.play-export-events/snapshot-frame-db
                        :story.paste/asserted)
            recording (rf.story.recorder/current-state)
            ;; What the save dialog's :on-export hands the export dialog, built
            ;; with the export dialog's defaults.
            {:keys [spec rendered]}
            (rf.story.recorder.play-export-events/build-export
              (:entries recording)
              {:variant-id   :story.paste/asserted-script
               :extends      :story.paste/asserted
               :auto-run?    true
               :auto-assert? true
               :final-db     final-db
               :seed-db      (:seed-db recording)})]
        (is (contains? final-db :rf.story/assertions)
            "control: the recorded frame's db carries Story's run records")
        (is (= [[:assert-db [:submits] 2]]
               (filterv #(= :assert-db (first %)) (:script spec)))
            "the one path the recording changed, and nothing else")
        (paste! rendered)
        (is (= :pass (:status (run-result :story.paste/asserted-script)))))
      (finally
        (rf.story.recorder/remove-trace-listener!)
        (rf.story.recorder/clear!)))))
