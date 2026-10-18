(ns re-frame.adapter.ssr-emitter-replay-atomic-cljs-test
  "`install-adapter!` publishes the installed map's capabilities and replays
  the retained SSR hiccup emitter through the map's own
  `:arm-hiccup-emitter-if-unarmed!` inside one failure-atomic transaction: a
  throwing re-arm rolls back exactly the generation it seated — its seat and
  its capabilities — and rethrows, leaving a never-installed state. Neither
  that rollback nor a stale generation's teardown finalization touches a
  replacement generation's seat or capabilities. The maps are inert, so this
  pins the lifecycle seam independently of any substrate's emitter wiring."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.substrate.adapter :as rf.substrate.adapter]))

;; The tests overwrite the durable emitter slot and the broadcast emitter hook;
;; restore them so a real adapter's publications elsewhere in the bundle
;; survive.
(def ^:private touched-hooks
  [:ssr/current-hiccup-emitter
   :reagent/set-hiccup-emitter!])

(defn- restore-hook! [k orig]
  (if (some? orig)
    (rf.late-bind/set-fn! k orig)
    (swap! rf.late-bind/hooks dissoc k)))

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

(defn- arm-that-throws [_]
  (throw (ex-info "replay boom" {:marker ::boom})))

(def ^:private fake-adapter
  {:kind                           :rf.test/atomic-adapter
   :after-render                   (fn fake-after-render [_] nil)
   :arm-hiccup-emitter-if-unarmed! (fn [_] nil)})

(deftest a-throwing-replay-rolls-the-install-back-atomically
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter (fn [_ _] "<html/>"))
  (let [failing (assoc fake-adapter :arm-hiccup-emitter-if-unarmed! arm-that-throws)
        thrown  (try (rf.substrate.adapter/install-adapter! failing) nil
                     (catch #?(:clj Throwable :cljs :default) e e))]
    (is (= ::boom (:marker (ex-data thrown)))
        "the re-arm exception is rethrown as the primary throw")
    (is (nil? (rf.substrate.adapter/current-adapter)) "no generation stays seated")
    (is (nil? (rf.late-bind/get-fn :adapter/after-render))
        "the rolled-back generation's capabilities are not left published")
    (is (false? (rf.substrate.adapter/adapter-disposed?))
        "a failed install disposed nothing, so delegation still reports never-installed")
    (is (= fake-adapter (rf.substrate.adapter/install-adapter! fake-adapter))
        "an immediate retry installs cleanly")))

(deftest exact-generation-rollback-does-not-erase-a-replacement
  ;; The throwing arm runs while the failing generation is seated: it destroys
  ;; that generation and installs a replacement first. The failing install's
  ;; rollback must keep both the replacement's seat and its capabilities.
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter (fn [_ _] "<html/>"))
  (let [replacement-after-render (fn replacement-after-render [_] nil)
        replacement {:kind         :rf.test/replacement-adapter
                     :after-render replacement-after-render}
        failing     (assoc fake-adapter
                           :arm-hiccup-emitter-if-unarmed!
                           (fn [_]
                             (rf.substrate.adapter/dispose-adapter!)
                             (rf.substrate.adapter/install-adapter! replacement)
                             (throw (ex-info "outer boom" {}))))]
    (is (thrown? #?(:clj Throwable :cljs :default)
                 (rf.substrate.adapter/install-adapter! failing)))
    (is (identical? replacement (rf.substrate.adapter/current-adapter)))
    (is (identical? replacement-after-render (rf.late-bind/get-fn :adapter/after-render))
        "the rollback left the replacement's capability published")))

(deftest stale-finalization-does-not-erase-a-replacement
  ;; A teardown whose generation lost the seat while its cleanup ran must not
  ;; clear the generation seated since: neither its seat nor its capabilities.
  (let [replacement-after-render (fn replacement-after-render [_] nil)
        replacement {:kind         :rf.test/replacement-adapter
                     :after-render replacement-after-render}
        departing   (assoc fake-adapter
                           :dispose-adapter!
                           (fn []
                             ;; Something else releases the seat mid-cleanup
                             ;; and a replacement installs over it.
                             (rf.substrate.adapter/reset-lifecycle-state-for-tests!)
                             (rf.substrate.adapter/install-adapter! replacement)))]
    (rf.substrate.adapter/install-adapter! departing)
    (rf.substrate.adapter/dispose-adapter!)
    (is (identical? replacement (rf.substrate.adapter/current-adapter)))
    (is (identical? replacement-after-render (rf.late-bind/get-fn :adapter/after-render))
        "the stale finalization left the replacement's capability published")))

(deftest inactive-throwing-broadcast-setter-cannot-break-the-active-boot
  ;; A loaded inactive adapter's `:reagent/set-hiccup-emitter!` broadcast setter
  ;; throws. The replay hands the retained emitter to the installed map's arm
  ;; alone, so the broadcast never runs and the boot succeeds.
  (rf.late-bind/set-fn! :ssr/current-hiccup-emitter ::retained-emitter)
  (let [broadcast-ran (atom false)
        armed-with    (atom nil)
        adapter       (assoc fake-adapter
                             :arm-hiccup-emitter-if-unarmed! (fn [f] (reset! armed-with f)))]
    (rf.late-bind/set-fn! :reagent/set-hiccup-emitter!
                          (fn [_]
                            (reset! broadcast-ran true)
                            (throw (ex-info "inactive setter boom" {}))))
    (is (= adapter (rf.substrate.adapter/install-adapter! adapter)))
    (is (= ::retained-emitter @armed-with) "the installed map's arm received the retained emitter")
    (is (false? @broadcast-ran) "the broadcast setter is not on the replay path")))
