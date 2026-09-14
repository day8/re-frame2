(ns re-frame.story.ui.view-state-upgrade-test
  "End-to-end acceptance for the fidelity-upgrade handoff (rf2-mw9th). The
  snippet `upgrade-snippet` emits is taken down the path an author takes —
  read, completed, registered, compiled, run — and graded on what the
  UPGRADED variant can prove, never on the snippet's shape:

  1. the emitted snippet reads as EDN;
  2. the completed child compiles to a plan whose `:fidelity` lacks
     `:sub-overrides`;
  3. a handler defect the pinned parent CANNOT detect IS detected by the
     upgraded child.

  Clause 3 grades the RENDER-PATH read — what the view would show for the
  subscription — because that is exactly what a pin masks. It deliberately
  does not grade `:rf.assert/sub-equals`: that assertion reads `compute-sub`
  against app-db and ignores overrides, so a child still carrying the pin
  would pass it too, and the test would prove nothing about the upgrade."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.render :as rf.story.render]
            [re-frame.story.sub-overrides :as rf.story.sub-overrides]
            [re-frame.story.ui.view-state :as rf.story.ui.view-state]))

(def ^:private address "ada@example.com")

(defn- reg-set-email!
  "Register `:login/set-email`. `defective?` installs the defect under test:
  the handler writes the address to a misspelt path, so the app never holds
  it and `[:login/email]` computes nil."
  [defective?]
  (rf/reg-event :login/set-email
    (fn [{:keys [db]} [_ email]]
      {:db (assoc-in db (if defective? [:login :emial] [:login :email]) email)})))

(defn- reset-rf! [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (rf/reg-sub :login/email (fn [db _] (get-in db [:login :email])))
  (rf.story/reg-story :story.upgrade {:component :upgrade/login-card})
  ;; The low-fidelity picture: the address is PINNED and no event runs.
  ;; Registered through the macro, so the side-table body carries the
  ;; registrar's `:source` coords stamp exactly as an authored one does.
  (rf.story/reg-variant :story.upgrade/pinned {:sub-overrides {[:login/email] address}})
  (test-fn))

(use-fixtures :each reset-rf!)

(defn- run-variant [variant-id]
  (.get ^java.util.concurrent.CompletableFuture (rf.story/run variant-id)))

(defn- rendered-email
  "The address `variant-id`'s view would render: run the variant, then read
  `[:login/email]` through the render path — the variant's resolved render
  `:sub-overrides` on a hit, the real subscription against the run's app-db
  on a miss."
  [variant-id]
  (let [app-db (:app-db (run-variant variant-id))
        pins   (get-in (rf.story.render/prepare-render variant-id)
                       [:render-inputs :sub-overrides])]
    (rf.story.sub-overrides/with-overrides* pins
      #(rf.story.sub-overrides/read [:login/email]
                                    (fn [] (rf/compute-sub [:login/email] app-db))))))

(defn- upgrade!
  "Take the upgrade the View-State section offers for `:story.upgrade/pinned`
  → `:real-setup` the way an author does: read the emitted snippet, fill its
  setup slot with the real event, register what it names. Returns the
  upgraded variant id."
  []
  (let [snippet      (rf.story.ui.view-state/upgrade-snippet :story.upgrade/pinned :real-setup)
        [op id body] (edn/read-string snippet)]
    (is (= 'story/reg-variant op) "the snippet reads back as a reg-variant form")
    (rf.story.registrar/reg-variant* id (assoc body :setup [[:dispatch [:login/set-email address]]]))
    id))

(deftest completed-upgrade-compiles-without-the-pin
  (reg-set-email! false)
  (let [id (upgrade!)]
    (testing "control — the source really is a pinned picture"
      (is (= #{:sub-overrides}
             (get-in (rf.story.plan/variant-plan :story.upgrade/pinned) [:world :fidelity]))))
    (testing "the completed child rests on real setup alone"
      (let [plan (rf.story.plan/variant-plan id)]
        (is (= #{:real-setup} (get-in plan [:world :fidelity])))
        (is (empty? (get-in plan [:world :render :sub-overrides]))
            "no pinned subscription reaches the child's render path")))))

(deftest upgraded-child-detects-a-handler-defect-the-pinned-parent-cannot
  (let [id (upgrade!)]
    (testing "with a CORRECT handler both show the address"
      (reg-set-email! false)
      (is (= address (rendered-email :story.upgrade/pinned)))
      (is (= address (rendered-email id))))
    (testing "with a DEFECTIVE handler"
      (reg-set-email! true)
      (is (= address (rendered-email :story.upgrade/pinned))
          "the pinned parent still shows the address — it cannot see the defect")
      (is (not= address (rendered-email id))
          "the upgraded child shows what the handler really produced — the defect is detected"))))
