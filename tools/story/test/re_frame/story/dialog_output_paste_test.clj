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
            ;; Loaded for its late-bind hooks: `:rf.assert/dispatched?` reads
            ;; the epoch tape, which is empty without the epoch artefact.
            [re-frame.epoch]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.artifact :as rf.story.artifact]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.author-expectations :as rf.story.author-expectations]
            [re-frame.story.recorder :as rf.story.recorder]
            [re-frame.story.recorder.play-export :as rf.story.recorder.play-export]
            [re-frame.story.recorder.play-export-events :as rf.story.recorder.play-export-events]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.save-variant :as rf.story.save-variant]
            [re-frame.story.ui.author-expectations :as rf.story.ui.author-expectations]
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

;; ---- rf2-3x7nj.29.1 — an expectation added to a SCRIPTED story replays it --

(defn- add-expectations-snippet
  "What the add-expectations dialog emits for `source-id` with one authored
  `:dispatched` expectation on `event` — built by the dialog's own
  `build-snippet`, which reads the source's registered body."
  [source-id new-id event]
  (rf.story.ui.author-expectations/build-snippet
    {:source-id source-id
     :draft     (-> (rf.story.ui.author-expectations/initial-draft)
                    (rf.story.ui.author-expectations/set-variant-id new-id)
                    (rf.story.ui.author-expectations/add-row :dispatched)
                    (rf.story.ui.author-expectations/set-operand 0 :event (pr-str event)))}))

(deftest add-expectations-to-a-scripted-story-replays-its-script
  (testing "rf2-3x7nj.29.1: :script and :plays are child-only through
            :extends, so the regression test the dialog emits re-declares
            the source's own — pasted as-is it replays the story and its
            expectations pass on a correct app"
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
    (testing "CONTROL: each source passes on its own"
      (is (= :pass (:status (run-result :story.paste/scripted))))
      (is (= :pass (:status (run-result :story.paste/played)))))
    (doseq [[source-id new-id submits]
            [[:story.paste/scripted :story.paste/scripted-expects 1]
             [:story.paste/played   :story.paste/played-expects   2]]]
      (paste! (add-expectations-snippet source-id new-id :paste/submit))
      (let [result (run-result new-id)]
        (is (= :pass (:status result))
            (str new-id " — the source's assertion and the authored one both hold"))
        (is (= submits (get-in result [:app-db :submits]))
            (str new-id " — the source's interaction ran"))))))

;; ---- rf2-0ae7o.13 — pasted UNFILLED, the upgrade scaffold does not pass ----

(defn- no-such-handler-record [result]
  (first (filter #(and (= :rf.error/exception (:assertion %))
                       (= :rf.error/no-such-handler (:operation %)))
                 (:assertions result))))

(deftest real-setup-upgrade-output-unfilled-does-not-pass
  (testing "rf2-0ae7o.13: the real-setup upgrade scaffold, pasted with its
            :your/setup-event placeholder left in, is refused by the runner —
            never a vacuous :pass over a setup that dispatched nothing"
    (paste! (rf.story.ui.view-state/upgrade-snippet :story.paste/pinned :real-setup))
    (let [result (run-result :story.paste/pinned-upgraded)
          record (no-such-handler-record result)]
      (is (not= :pass (:status result))
          "the unfilled scaffold must not read green")
      (is (some? record)
          "the refused placeholder dispatch is a failed :rf.error/exception record")
      (is (= [:your/setup-event {}] (:event record))
          "the record names the placeholder event the author was told to replace")
      (is (= :phase-2-events (:phase record))
          "the refusal is attributed to the :setup phase")))
  (testing "CONTROL: the same scaffold passes once the placeholder names a
            registered handler — the refusal is about the missing handler,
            not about the scaffold"
    (rf/reg-event :your/setup-event (fn [{:keys [db]} _] {:db (assoc db :setup-ran? true)}))
    (paste! (rf.story.ui.view-state/upgrade-snippet :story.paste/pinned :real-setup))
    (let [result (run-result :story.paste/pinned-upgraded)]
      (is (= :pass (:status result)))
      (is (nil? (no-such-handler-record result)))
      (is (true? (get-in result [:app-db :setup-ran?]))
          "the filled-in setup event actually ran"))))

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

;; ---- rf2-3x7nj.29.2 — auto-assert asserts what the RECORDING changed --------

(deftest recorder-export-auto-assert-asserts-what-the-recording-changed
  (testing "rf2-3x7nj.29.2: recorded against a variant whose db carries static
            keys and Story's own :rf.story/assertions records, the export
            dialog's default auto-assert pins only the path the recording
            changed; the pasted form compiles and its run passes"
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
