(ns re-frame.story.promotion-dom-cljs-test
  "DOM-driven acceptance for rf2-5vmog: a variant whose `:script` types,
  clicks, waits and asserts, and which declares a terminal assertion,
  promotes through the Test-mode dialog's own capture into a regression
  that fails for the same reason, passes once the app is fixed, and fails
  again when the fault returns.

  WHY A REAL DOM. Headless, `:type` / `:click` / `:assert-dom` refuse
  (`:cannot-run`), so a JVM run cannot tell a retained interaction from a
  dropped one. The pure twin in `re-frame.story.promotion-cljs-test`
  (`promoted-dom-driven-variant-retains-its-script`) pins the promoted
  BODY; this suite RUNS it under the `:dom` runner.

  WHAT IT DISCRIMINATES. Test mode captures a run as the dispatch-only
  projection of its program. Without the retained program the promoted
  variant never types or clicks, so the fixed app cannot pass it; without
  the carried terminal assertion its assertion count falls below its
  source's. Either regression reds the single test below.

  The app is a plain DOM form, not a React view: the runner's DOM steps act
  on whatever `document` holds, and a Story run's frame id IS its variant
  id (`re-frame.story.frames/allocate!`), so the form dispatches into the
  frame that is currently running.

  `-dom-cljs-test$` opts the file into the `:browser-test` build (Playwright
  + Chromium). `:node-test` also loads it (its `cljs-test$` regex matches);
  there the test self-gates on `(browser?)` and does nothing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.story :as rf.story]
            [re-frame.story.play :as rf.story.play]
            [re-frame.story.promotion :as rf.story.promotion]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.ui.promotion :as rf.story.ui.promotion]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (try (rf/init! rf.adapter.reagent/adapter)
       (catch :default _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each {:before reset-all!})

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the app under test ---------------------------------------------------

(def ^:private source-id :story.promo-dom/save)
(def ^:private promoted-id :story.promo-dom/save-promoted)

(def ^:private running
  "The frame the form dispatches into — set to the variant about to run."
  (atom nil))

(defn- reg-app!
  "`fixed?` false is the FAULT — save records an empty name; true records
  the typed one."
  [fixed?]
  (rf/reg-event :promo-dom/open
    (fn [{:keys [db]} _] {:db (dissoc db :saved)}))
  (rf/reg-event :promo-dom/save
    (fn [{:keys [db]} [_ typed]]
      {:db (assoc db :saved (if fixed? typed ""))})))

(defn- mount-form!
  "A name input and a save button. Clicking save dispatches the input's
  current value into the running variant's frame."
  []
  (let [root  (js/document.createElement "div")
        input (js/document.createElement "input")
        save  (js/document.createElement "button")]
    (.setAttribute input "data-test" "promo-dom-name")
    (.setAttribute save "data-test" "promo-dom-save")
    (set! (.-textContent save) "save")
    (.addEventListener save "click"
      (fn [_] (rf/dispatch [:promo-dom/save (.-value input)] {:frame @running})))
    (.appendChild root input)
    (.appendChild root save)
    (js/document.body.appendChild root)
    root))

(def ^:private source-body
  {:tags       #{:test}
   :script     [[:dispatch [:promo-dom/open]]
                [:type "[data-test=promo-dom-name]" "Ada"]
                [:click "[data-test=promo-dom-save]"]
                [:wait 50]
                [:assert-dom "[data-test=promo-dom-name]" :visible]
                [:assert [:rf.assert/path-equals [:saved] "Ada"]]]
   :assertions [[:rf.assert/path-equals [:saved] "Ada"]]})

(defn- run-variant!
  "Run variant `id` under the `:dom` runner, pointing the form at its frame."
  [id]
  (reset! running id)
  (rf.story/run id {:runner :dom}))

(defn- verdict [result]
  {:status (:status result) :assertions (count (:assertions result))})

;; ---- the acceptance -------------------------------------------------------

(deftest dom-driven-promotion-runs-fail-pass-fail
  (testing "a DOM-driven source promoted through the Test-mode dialog's capture
            fails under the fault with its source's verdict, passes by all
            three of its assertions once the app is fixed, and fails again
            when the fault returns (rf2-5vmog)"
    (when (browser?)
      (async done
        (let [root   (mount-form!)
              finish (fn [] (.remove root) (done))
              source (atom nil)]
          (reg-app! false)
          (rf.story.registrar/reg-variant* source-id source-body)
          (-> (run-variant! source-id)
              (.then (fn [result]
                       (reset! source (verdict result))
                       (is (= :fail (:status @source))
                           "the source fails under the fault")
                       (rf.story.promotion/promote-run-artifact!
                         (rf.story.ui.promotion/result->artifact
                           result (rf.story.play/variant-play-events source-id))
                         (rf.story.ui.promotion/draft->promote-opts
                           {:variant-id  promoted-id
                            :tags        #{:test}
                            :setup-count 0
                            :extends     source-id}))
                       (run-variant! promoted-id)))
              (.then (fn [result]
                       (is (= @source (verdict result))
                           "promoted, under the fault: the source's status AND
                            assertion count")
                       (reg-app! true)
                       (run-variant! promoted-id)))
              (.then (fn [result]
                       (is (= {:status :pass :assertions 3} (verdict result))
                           "fixed: passes BY its DOM checkpoint, its in-script
                            checkpoint and its terminal assertion")
                       (reg-app! false)
                       (run-variant! promoted-id)))
              (.then (fn [result]
                       (is (= @source (verdict result))
                           "fault restored: fails again, as its source did")
                       (finish)))
              (.catch (fn [e]
                        (is false (str "a run rejected: " e))
                        (finish)))))))))
