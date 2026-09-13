(ns re-frame.story.resolved-scenario-test
  "rf2-fzbj.3 (rf2-gwye.5 / .6 / .7) — a REGISTERED run executes and reports
  the scenario its compiled plan resolves, and a run's loader cleanup belongs
  to that run.

  The three defects share one root. The plan compiler resolves `:extends` and
  `:compose` into `[:world …]` (loaders, loader teardown, completion policy,
  effective args), while registered execution and the save / share / canvas
  readers re-read the RAW registration:

  1. Loaders (rf2-gwye.5) — an extends-only or compose-only variant skipped
     its inherited / composed loaders AND their teardown, and still reported
     `:pass` / `:ready`.
  2. Cleanup ownership (rf2-gwye.6) — teardown read the CURRENT registration,
     so a hot reload between a run and its destroy (or re-run) ran a cleanup
     whose setup never ran and skipped the one that did.
  3. Effective args (rf2-gwye.7) — the facade resolver and the save snapshot
     read the variant's own `:args`, so an inherited value was replaced by the
     story default, and a saved variant made that replacement permanent.

  Every test drives `run-variant` / `destroy-variant!` and reads what the
  handlers actually saw (the `calls` atom) or the frame's app-db."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.async :as rf.story.async]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.frames :as rf.story.frames]
            [re-frame.story.loaders :as rf.story.loaders]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.play.runner-events :as rf.story.play.runner-events]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.runtime :as rf.story.runtime]
            [re-frame.story.save-variant :as rf.story.save-variant]
            [re-frame.story.ui.state :as rf.story.ui.state]))

;; ---- fixtures -------------------------------------------------------------

(def ^:private calls (atom []))

(defn- recording-event! [id]
  (rf/reg-event (keyword "rs" (name id))
    (fn [_ _] (swap! calls conj id) {})))

(defn- reset-all [t]
  (rf.story/clear-all!)
  (rf.story.ui.state/reset-shell-state!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch clojure.lang.ExceptionInfo _ nil))
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf.story.loaders/clear-watchers!)
  (rf.story.config/set-global-args! {})
  (reset! rf.story.frames/stub-call-log {})
  (reset! rf.story.frames/allocated-decorator-stacks {})
  (rf.story.play.runner-events/clear-all-runs!)
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!)
  (reset! calls [])
  (doseq [id [:close :frag :frag-close :own :own-close :setup :script
              :opened-a :closed-a :closed-b]]
    (recording-event! id))
  (rf/reg-event :rs/load
    (fn [{:keys [db]} _] (swap! calls conj :load) {:db (assoc db :loaded true)}))
  (rf/reg-event :rs/never-ready
    (fn [{:keys [db]} _] {:db (assoc db :rf.story/loaders-complete? false)}))
  (t))

(use-fixtures :each reset-all)

(defn- run-it!
  ([vid] (run-it! vid nil))
  ([vid opts] (rf.story.async/deref-blocking (rf.story/run-variant vid opts) 5000)))

(defn- loader-incomplete? [result]
  (boolean (some #(= :rf.error/loader-incomplete (:assertion %)) (:assertions result))))

;; ===========================================================================
;; 1 · rf2-gwye.5 — registered runs execute the RESOLVED loader world
;; ===========================================================================

(deftest extends-only-child-runs-inherited-loaders-and-cleanup
  (rf.story/reg-variant :story.rs/base
    {:loaders [[:rs/load]] :loaders-teardown [[:rs/close]]})
  (rf.story/reg-variant :story.rs/child {:extends :story.rs/base})
  (let [result (run-it! :story.rs/child)]
    (is (= [:load] @calls) "the inherited loader ran")
    (is (true? (:loaded (rf/app-db-value :story.rs/child)))
        "and its app-db effect landed before the run settled")
    (is (= :pass (:status result))))
  (rf.story/destroy-variant! :story.rs/child)
  (is (= [:load :close] @calls) "the inherited cleanup ran once on destroy"))

(deftest compose-only-variant-runs-fragment-loaders-and-cleanup
  (rf.story/reg-fragment :fragment.rs/loader
    {:loaders [[:rs/load]] :loaders-teardown [[:rs/close]]})
  (rf.story/reg-variant :story.rs/composed {:compose [:fragment.rs/loader]})
  (run-it! :story.rs/composed)
  (is (= [:load] @calls) "the composed fragment's loader ran")
  (rf.story/destroy-variant! :story.rs/composed)
  (is (= [:load :close] @calls) "the composed fragment's cleanup ran once"))

(deftest composed-loaders-run-before-the-variants-own
  (rf.story/reg-fragment :fragment.rs/pre
    {:loaders [[:rs/frag]] :loaders-teardown [[:rs/frag-close]]})
  (rf.story/reg-variant :story.rs/both
    {:compose [:fragment.rs/pre]
     :loaders [[:rs/own]] :loaders-teardown [[:rs/own-close]]})
  (run-it! :story.rs/both)
  (is (= [:frag :own] @calls)
      "spec/017 §Total merge order — composed fragments, then the variant chain")
  (rf.story/destroy-variant! :story.rs/both)
  (is (= [:frag :own :frag-close :own-close] @calls)))

(deftest inherited-false-completion-predicate-stops-setup-and-play
  (rf.story/reg-variant :story.rs/gated
    {:loaders [[:rs/load]] :loaders-complete-when :rs/never-ready})
  (rf.story/reg-variant :story.rs/gated-child
    {:extends :story.rs/gated
     :setup   [[:rs/setup]]
     :script  [[:dispatch [:rs/script]]]})
  (let [result (run-it! :story.rs/gated-child)]
    (is (= [:load] @calls) "the loader ran; setup and play did not")
    (is (loader-incomplete? result)
        "the inherited predicate's verdict is reported as loader-incomplete")
    (is (not= :pass (:status result)))))

(deftest rerun-runs-the-resolved-cleanup-once
  (rf.story/reg-variant :story.rs/base
    {:loaders [[:rs/load]] :loaders-teardown [[:rs/close]]})
  (rf.story/reg-variant :story.rs/child {:extends :story.rs/base})
  (run-it! :story.rs/child)
  (run-it! :story.rs/child)
  (is (= [:load :close :load] @calls)
      "the in-place reset closes the first run's resource before re-opening")
  (rf.story/destroy-variant! :story.rs/child)
  (is (= [:load :close :load :close] @calls)))

(deftest loader-controls-direct-no-loader-and-inline
  (testing "direct variant — unchanged"
    (rf.story/reg-variant :story.rs/direct
      {:loaders [[:rs/load]] :loaders-teardown [[:rs/close]]})
    (run-it! :story.rs/direct)
    (rf.story/destroy-variant! :story.rs/direct)
    (is (= [:load :close] @calls)))
  (testing "no-loader variant still takes the events-only fast path"
    (reset! calls [])
    (rf.story/reg-variant :story.rs/plain {:setup [[:rs/setup]]})
    (let [result (run-it! :story.rs/plain)]
      (is (= [:setup] @calls))
      (is (= :ready (:lifecycle result)))
      (is (not (loader-incomplete? result))))
    (rf.story/destroy-variant! :story.rs/plain))
  (testing "inline plan — unchanged"
    (reset! calls [])
    (rf.story.async/deref-blocking
      (rf.story.runtime/run-inline-plan
        {:loaders [[:rs/load]] :loaders-teardown [[:rs/close]]})
      5000)
    (is (= [:load :close] @calls))))

;; ===========================================================================
;; 2 · rf2-gwye.6 — a run's loader cleanup survives a hot reload
;; ===========================================================================

(defn- reg-reload! [teardown]
  (rf.story/reg-variant :story.rs/reload
    (cond-> {:loaders [[:rs/opened-a]]}
      teardown (assoc :loaders-teardown teardown))))

(deftest hot-reload-then-destroy-runs-the-cleanup-the-run-owns
  (reg-reload! [[:rs/closed-a]])
  (run-it! :story.rs/reload)
  (reg-reload! [[:rs/closed-b]])
  (rf.story/destroy-variant! :story.rs/reload)
  (is (= [:opened-a :closed-a] @calls)
      "A closed what A's run opened; B, whose setup never ran, did not run"))

(deftest hot-reload-then-rerun-closes-the-prior-run-then-adopts-the-new-cleanup
  (reg-reload! [[:rs/closed-a]])
  (run-it! :story.rs/reload)
  (reg-reload! [[:rs/closed-b]])
  (run-it! :story.rs/reload)
  (is (= [:opened-a :closed-a :opened-a] @calls)
      "the reset closed the previous run's resource with ITS cleanup")
  (rf.story/destroy-variant! :story.rs/reload)
  (is (= [:opened-a :closed-a :opened-a :closed-b] @calls)
      "the new run owns the new cleanup"))

(deftest hot-reload-removing-or-adding-cleanup-follows-the-run
  (testing "cleanup removed after the run opened its resource — A still runs"
    (reg-reload! [[:rs/closed-a]])
    (run-it! :story.rs/reload)
    (reg-reload! nil)
    (rf.story/destroy-variant! :story.rs/reload)
    (is (= [:opened-a :closed-a] @calls)))
  (testing "cleanup added after a run that had none — nothing runs"
    (reset! calls [])
    (reg-reload! nil)
    (run-it! :story.rs/reload)
    (reg-reload! [[:rs/closed-b]])
    (rf.story/destroy-variant! :story.rs/reload)
    (is (= [:opened-a] @calls))))

(deftest parent-edit-does-not-rewrite-an-inherited-runs-cleanup
  (rf.story/reg-variant :story.rs/parent
    {:loaders [[:rs/opened-a]] :loaders-teardown [[:rs/closed-a]]})
  (rf.story/reg-variant :story.rs/heir {:extends :story.rs/parent})
  (run-it! :story.rs/heir)
  (rf.story/reg-variant :story.rs/parent
    {:loaders [[:rs/opened-a]] :loaders-teardown [[:rs/closed-b]]})
  (rf.story/destroy-variant! :story.rs/heir)
  (is (= [:opened-a :closed-a] @calls)))

(deftest destroy-twice-does-not-repeat-the-cleanup
  (reg-reload! [[:rs/closed-a]])
  (run-it! :story.rs/reload)
  (rf.story/destroy-variant! :story.rs/reload)
  (rf.story/destroy-variant! :story.rs/reload)
  (is (= [:opened-a :closed-a] @calls)))

;; ===========================================================================
;; 3 · rf2-gwye.7 — effective args agree across run, facade, save, snippet
;; ===========================================================================

(def ^:private inherited {:count 42 :nested {:v 7 :keep 1}})

(defn- reg-args-scenario! []
  (rf.story/reg-story :story.rsargs {:args {:count 0 :nested {:v 0 :keep 1}}})
  (rf.story/reg-mode :Mode.rsargs/loud {:args {:count 5 :theme :loud}})
  (rf.story/reg-variant :story.rsargs/parent {:args {:count 42 :nested {:v 7}}})
  (rf.story/reg-variant :story.rsargs/child {:extends :story.rsargs/parent})
  (rf.story/reg-fragment :fragment.rsargs/args {:args {:count 42 :nested {:v 7}}})
  (rf.story/reg-variant :story.rsargs/composed {:compose [:fragment.rsargs/args]})
  (rf.story/reg-variant :story.rsargs/deep
    {:extends :story.rsargs/parent :args {:nested {:v 9}}})
  (rf.story/reg-variant :story.rsargs/direct {:args {:count 3}}))

(defn- surfaces
  "The effective args each surface reports for `vid` under run `opts`."
  [vid opts]
  (let [run (run-it! vid opts)]
    (rf.story/destroy-variant! vid)
    {:run      (:effective-args run)
     :facade   (rf.story/resolve-args vid opts)
     :snapshot (rf.story.save-variant/snapshot-args vid opts)}))

(deftest inherited-and-composed-args-agree-on-every-surface
  (reg-args-scenario!)
  (doseq [vid [:story.rsargs/child :story.rsargs/composed]]
    (is (= {:run inherited :facade inherited :snapshot inherited} (surfaces vid nil))
        (str vid " — the resolved variant layer beats the story default"))))

(deftest saved-variant-round-trip-keeps-the-inherited-args
  (reg-args-scenario!)
  (doseq [vid [:story.rsargs/child :story.rsargs/composed]]
    (let [saved-id (keyword "story.rsargs" (str "saved-" (name vid)))
          snippet  (rf.story.save-variant/gen-variant-snippet
                     {:variant-id saved-id
                      :extends    vid
                      :args       (rf.story.save-variant/snapshot-args vid)})
          [_ id body] (edn/read-string snippet)]
      (rf.story.registrar/reg-variant* id body)
      (is (= inherited (get-in (rf.story.plan/variant-plan saved-id)
                               [:world :effective-args]))
          (str vid " — the saved form re-registers the args the user saw")))))

(deftest nested-args-deep-merge-through-the-chain
  (reg-args-scenario!)
  (is (= {:run      {:count 42 :nested {:v 9 :keep 1}}
          :facade   {:count 42 :nested {:v 9 :keep 1}}
          :snapshot {:count 42 :nested {:v 9 :keep 1}}}
         (surfaces :story.rsargs/deep nil))))

(deftest run-layers-fold-around-the-resolved-variant-layer
  (reg-args-scenario!)
  (testing "an active mode sits BELOW the inherited variant args"
    (let [expected (assoc inherited :theme :loud)]
      (is (= {:run expected :facade expected :snapshot expected}
             (surfaces :story.rsargs/child {:active-modes [:Mode.rsargs/loud]})))))
  (testing "a cell override sits above everything"
    (let [expected (assoc inherited :count 99)]
      (is (= {:run expected :facade expected :snapshot expected}
             (surfaces :story.rsargs/child {:cell-overrides {:count 99}}))))))

(deftest direct-variant-args-control
  (reg-args-scenario!)
  (let [expected {:count 3 :nested {:v 0 :keep 1}}]
    (is (= {:run expected :facade expected :snapshot expected}
           (surfaces :story.rsargs/direct nil)))))
