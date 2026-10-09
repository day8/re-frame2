(ns re-frame.adapter.ssr-emitter-replay-atomic-cljs-test
  "`install-adapter!` replays the retained SSR hiccup emitter through the
  installed adapter's `:adapter/arm-hiccup-emitter-if-unarmed!` hook inside one
  failure-atomic transaction: a throwing re-arm rolls back exactly the
  generation it seated and rethrows, leaving a never-installed state. The hooks
  are injected directly, so this pins the lifecycle seam independently of any
  substrate's emitter wiring."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]))

;; The tests overwrite the durable emitter slot and both emitter hooks; restore
;; them so a real adapter's publications elsewhere in the bundle survive.
(def ^:private touched-hooks
  [:ssr/current-hiccup-emitter
   :adapter/arm-hiccup-emitter-if-unarmed!
   :reagent/set-hiccup-emitter!])

(defn- restore-hook! [k orig]
  (if (some? orig)
    (rf.late-bind/set-fn! k orig)
    (do (swap! rf.late-bind/hooks dissoc k)
        (rf.late-bind/invalidate-cache! k))))

(defn- isolate-emitter-hooks [test-fn]
  (let [saved (into {} (map (fn [k] [k (rf.late-bind/get-fn k)])) touched-hooks)]
    (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
    (try
      (test-fn)
      (finally
        (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
        (doseq [k touched-hooks]
          (restore-hook! k (get saved k)))))))

(use-fixtures :each isolate-emitter-hooks)

(def ^:private fake-adapter {:kind :rf.test/atomic-adapter})

(deftest a-throwing-replay-rolls-the-install-back-atomically
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter (fn [_ _] "<html/>"))
  (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed!
                        (fn [_] (throw (ex-info "replay boom" {:marker ::boom}))))
  (let [thrown (try (rf.substrate.adapter/install-adapter! fake-adapter) nil
                    (catch #?(:clj Throwable :cljs :default) e e))]
    (is (= ::boom (:marker (ex-data thrown)))
        "the re-arm exception is rethrown as the primary throw")
    (is (nil? (rf.substrate.adapter/current-adapter)) "no generation stays seated")
    (is (false? (rf.substrate.adapter/adapter-disposed?))
        "a failed install disposed nothing, so delegation still reports never-installed")
    (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed! (fn [_] nil))
    (is (= fake-adapter (rf.substrate.adapter/install-adapter! fake-adapter))
        "an immediate retry installs cleanly")))

(deftest exact-generation-rollback-does-not-erase-a-replacement
  ;; The throwing arm runs while the failing generation is seated and lands a
  ;; replacement generation first; the failing install's rollback must keep it.
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter (fn [_ _] "<html/>"))
  (let [replacement {:kind :rf.test/replacement-adapter}]
    (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed!
                          (fn [_]
                            (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
                            (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed! (fn [_] nil))
                            (rf.substrate.adapter/install-adapter! replacement)
                            (throw (ex-info "outer boom" {}))))
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (rf.substrate.adapter/install-adapter! fake-adapter)))
    (is (identical? replacement (rf.substrate.adapter/current-adapter)))))

(deftest inactive-throwing-broadcast-setter-cannot-break-the-active-boot
  ;; A loaded inactive adapter's `:reagent/set-hiccup-emitter!` broadcast setter
  ;; throws. The replay hands the retained emitter to the installed adapter's arm
  ;; hook alone, so the broadcast never runs and the boot succeeds.
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter ::retained-emitter)
  (let [broadcast-ran (atom false)
        armed-with    (atom nil)]
    (rf.late-bind/set-fn! :reagent/set-hiccup-emitter!
                          (fn [_]
                            (reset! broadcast-ran true)
                            (throw (ex-info "inactive setter boom" {}))))
    (rf.late-bind/set-fn! :adapter/arm-hiccup-emitter-if-unarmed!
                          (fn [f] (reset! armed-with f)))
    (is (= fake-adapter (rf.substrate.adapter/install-adapter! fake-adapter)))
    (is (= ::retained-emitter @armed-with) "the arm hook received the retained emitter")
    (is (false? @broadcast-ran) "the broadcast setter is not on the replay path")))
