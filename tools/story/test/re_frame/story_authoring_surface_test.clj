(ns re-frame.story-authoring-surface-test
  "`reg-variant` authoring round-trips: `:modes` (spec/010), the
  `:rf.story/force-fx-stub` value form, and `:decorators` through `:extends`
  (spec/017, resolved by the plan compiler)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core             :as rf]
            ;; `:rf.assert/effect-emitted` reads the epoch tape.
            [re-frame.epoch]
            [re-frame.frame            :as rf.frame]
            [re-frame.machines         :as rf.machines]
            [re-frame.registrar        :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story            :as rf.story]
            [re-frame.story.async      :as rf.story.async]
            [re-frame.story.config     :as rf.story.config]
            [re-frame.story.decorators :as rf.story.decorators]
            [re-frame.story.frames     :as rf.story.frames]
            [re-frame.story.loaders    :as rf.story.loaders]
            [re-frame.story.play       :as rf.story.play]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-all [test-fn]
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (reset! rf.story.play/stepper-state            {})
  (reset! rf.story.frames/stub-call-log          {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (test-fn))

(use-fixtures :each reset-all)

(defn- reg-modes! []
  (rf.story/reg-mode :Mode.app/dark   {:args {:theme :dark}})
  (rf.story/reg-mode :Mode.app/mobile {:args {:viewport :mobile}}))

(deftest modes-declared-on-variant-survive-registration
  (reg-modes!)
  (rf.story/reg-variant :story.modedecl/v {:modes #{:Mode.app/dark :Mode.app/mobile} :setup []})
  (is (= #{:Mode.app/dark :Mode.app/mobile}
         (:modes (rf.story/handler-meta :variant :story.modedecl/v)))))

(deftest modes-active-merges-into-effective-args
  (testing "only active modes merge, between story and variant args"
    (rf.story/configure! {:rf.story/global-args {:theme :light :viewport :desktop}})
    (reg-modes!)
    (rf.story/reg-story :story.modemix {:args {:label "story-label"}})
    (rf.story/reg-variant :story.modemix/v
      {:modes #{:Mode.app/dark :Mode.app/mobile} :args {:label "variant-label"} :setup []})
    (doseq [[active expected] [[[:Mode.app/dark]
                                {:theme :dark :viewport :desktop :label "variant-label"}]
                               [[:Mode.app/dark :Mode.app/mobile]
                                {:theme :dark :viewport :mobile :label "variant-label"}]]]
      (is (= expected (rf.story/resolve-args :story.modemix/v {:active-modes active}))))))

(deftest modes-resolved-from-run-variant
  (testing "run-variant's :active-modes reach the result's :effective-args
            (mode beats story and global), and a reset without them does not"
    (rf.story/configure! {:rf.story/global-args {:viewport :desktop}})
    (reg-modes!)
    (rf.story/reg-story :story.modeprobe {:args {:theme :light}})
    (rf.story/reg-variant :story.modeprobe/v {:modes #{:Mode.app/dark :Mode.app/mobile} :setup []})
    (let [args-of #(select-keys (:effective-args (rf.story.async/deref-blocking % 5000))
                                [:theme :viewport])]
      (is (= {:theme :dark :viewport :mobile}
             (args-of (rf.story/run-variant :story.modeprobe/v
                                            {:active-modes [:Mode.app/dark :Mode.app/mobile]}))))
      (is (= {:theme :light :viewport :desktop}
             (args-of (rf.story/reset-variant :story.modeprobe/v {})))))
    (rf.story/destroy-variant! :story.modeprobe/v)))

(deftest force-fx-stub-declared-in-reg-variant-body
  (testing "the value form survives registration verbatim and materialises
            one stub per ref"
    (let [decorators [[:rf.story/force-fx-stub :http      {:status :ok}]
                      [:rf.story/force-fx-stub :analytics {:ack? true}]]]
      (rf.story/reg-variant :story.authfx/v {:decorators decorators :setup []})
      (is (= decorators (:decorators (rf.story/handler-meta :variant :story.authfx/v))))
      (let [r (rf.story/resolve-decorators :story.authfx/v)]
        (is (= #{[:http {:status :ok}] [:analytics {:ack? true}]}
               (set (map (comp (juxt :fx-id :response) :body) (:fx-override r)))))
        (is (= #{:http :analytics}
               (set (keys (:overrides (rf.story.decorators/fx-overrides-map (:fx-override r)))))))))))

(deftest force-fx-stub-runtime-intercepts-from-author-form
  (rf/reg-event :do/emit-http (fn [_ _] {:fx [[:http {:url "/probe"}]]}))
  (rf.story/reg-variant :story.authfx-rt/v
    {:decorators [[:rf.story/force-fx-stub :http {:status :ok}]]
     :setup      []
     :script     [[:dispatch-sync [:do/emit-http]]
                  [:dispatch-sync [:rf.assert/effect-emitted :http]]]})
  (let [r (rf.story.async/deref-blocking (rf.story/run-variant :story.authfx-rt/v) 5000)]
    (is (= [:ready true 1] [(:lifecycle r) (every? :passed? (:assertions r))
                            (count (rf.story.frames/stub-call-log-for :story.authfx-rt/v))])))
  (rf.story/destroy-variant! :story.authfx-rt/v))

(deftest extends-inherits-decorators-when-child-declares-none
  (rf.story/reg-decorator :inherited-deco {:kind :hiccup :wrap (fn [body _] [:div.inherited body])})
  (rf.story/reg-variant :story.inherit-bare/parent {:decorators [[:inherited-deco]] :setup []})
  (rf.story/reg-variant :story.inherit-bare/child {:extends :story.inherit-bare/parent :setup []})
  (is (= [:inherited-deco]
         (mapv :id (:hiccup (rf.story/resolve-decorators :story.inherit-bare/child))))))

(deftest extends-child-decorators-replace-parent
  (testing "a child's own :decorators replace the parent's (child-wins, no concat)"
    (rf.story/reg-decorator :parent-deco {:kind :hiccup :wrap (fn [body _] [:div.parent body])})
    (rf.story/reg-decorator :child-deco {:kind :hiccup :wrap (fn [body _] [:div.child body])})
    (rf.story/reg-variant :story.inherit-replace/parent {:decorators [[:parent-deco]] :setup []})
    (rf.story/reg-variant :story.inherit-replace/child
      {:extends :story.inherit-replace/parent :decorators [[:child-deco]] :setup []})
    (is (= [:child-deco]
           (mapv :id (:hiccup (rf.story/resolve-decorators :story.inherit-replace/child)))))))
