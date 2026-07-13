(ns re-frame.ui.adapter-cljs-test
  "S2 first-party adapter conformance.

  The CLJC init assertions pin the frozen `ui/adapter` row on both hosts. The
  CLJS arms exercise every function in the closed ten-function contract, the
  copied-adapter routing token, and dispose/re-init watch re-arming. Focused
  artifact isolation is a BUILD property, checked after `:node-test-ui`
  compilation by `scripts/check-ui-adapter-isolation.cjs`; it deliberately
  does not live in this `*-cljs-test` namespace because this namespace also
  runs in the consolidated bundle that loads the retiring adapters' tests."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.ui :as ui]))

(use-fixtures
  :each
  (fn [f]
    (substrate-adapter/reset-lifecycle-state-for-tests!)
    (try
      (f)
      (finally
        (rf/destroy-adapter!)
        (substrate-adapter/reset-lifecycle-state-for-tests!)))))

(def ^:private required-cross-host-entries
  #{:make-state-container
    :read-container
    :replace-container!
    :subscribe-container
    :make-derived-value
    :render
    :render-to-string
    :register-context-provider
    :dispose-adapter!})

#?(:cljs
   (def ^:private cljs-contract-entries
     (conj required-cross-host-entries :flush-render!)))

(deftest public-adapter-installs-with-the-canonical-identity
  (testing "the frozen public var is a complete installable adapter map"
    (is (= :rf.adapter/ui (:kind ui/adapter)))
    (is (every? #(fn? (get ui/adapter %)) required-cross-host-entries)))
  (testing "rf/init! installs the exact public value and introspection names it"
    (is (nil? (rf/init! ui/adapter)))
    (is (identical? ui/adapter (rf/current-adapter-spec)))
    (is (= :rf.adapter/ui (rf/current-adapter)))))

#?(:cljs
   (deftest cljs-adapter-realises-the-closed-ten-function-contract
     (is (= (conj cljs-contract-entries :kind) (set (keys ui/adapter)))
         "the first-party CLJS adapter is exactly :kind plus the closed ten-function contract")
     (doseq [entry cljs-contract-entries]
       (is (fn? (get ui/adapter entry))
           (str entry " is present and function-shaped")))

     (let [make-state (:make-state-container ui/adapter)
           read        (:read-container ui/adapter)
           replace!   (:replace-container! ui/adapter)
           subscribe  (:subscribe-container ui/adapter)
           derive     (:make-derived-value ui/adapter)
           source     (make-state 1)
           value      (derive [source] identity)
           source-seen (atom [])
           value-seen  (atom [])
           unsub-source (subscribe source #(swap! source-seen conj [%1 %2]))
           unsub-value  (subscribe value #(swap! value-seen conj [%1 %2]))]
       (is (= 1 (read source)) "read-container reads the writable source")
       (is (= 1 (read value)) "read-container establishes the lazy derived baseline")
       (is (nil? (replace! source 2)) "replace-container! returns nil")
       (is (= 2 (read source)))
       (is (= 2 (read value)))
       (is (= [[1 2]] @source-seen)
           "subscribe-container observes base-container movement")
       (is (= [[1 2]] @value-seen)
           "subscribe-container observes derived-value movement")
       (unsub-source)
       (unsub-value)
       (replace! source 3)
       (is (= [[1 2]] @source-seen) "base unsubscribe releases its watch")
       (is (= [[1 2]] @value-seen) "derived unsubscribe releases its watch"))

     (testing "render-to-string is wired through the shared emitter chain"
       (let [install-emitter! (late-bind/get-fn :reagent/set-hiccup-emitter!)
             emit             (fn [tree opts] (str "ui:" (pr-str [tree opts])))]
         (is (fn? install-emitter!))
         (try
           (install-emitter! emit)
           (is (= "ui:[[:div \"ok\"] {:mode :probe}]"
                  ((:render-to-string ui/adapter) [:div "ok"] {:mode :probe})))
           (finally
             (install-emitter! nil)))))

     (testing "register-context-provider returns one stable native component"
       (let [provider (:register-context-provider ui/adapter)]
         (is (fn? (provider :rf.ui-test/a)))
         (is (identical? (provider :rf.ui-test/a)
                         (provider :rf.ui-test/b))
             "frame identity is a runtime prop; the provider component slot is stable")))

     (testing "flush-render! executes its callback synchronously and returns nil"
       (let [ran? (atom false)]
         (is (nil? ((:flush-render! ui/adapter) #(reset! ran? true))))
         (is (true? @ran?))))))

#?(:cljs
   (deftest copied-ui-adapter-routes-disposal-hooks-by-canonical-kind
     (let [copied      (assoc ui/adapter :instrumentation-wrapper true)
           source      ((:make-state-container copied) 1)
           derived     ((:make-derived-value copied) [source] identity)
           disposed?   (atom false)
           add-dispose (late-bind/get-fn :adapter/add-on-dispose!)
           dispose     (late-bind/get-fn :adapter/dispose!)]
       (is (false? (identical? ui/adapter copied)) "precondition: copied map has distinct identity")
       (is (nil? (rf/init! copied)))
       (is (identical? copied (rf/current-adapter-spec)))
       (is (= :rf.adapter/ui (rf/current-adapter)))
       (add-dispose derived #(reset! disposed? true))
       (dispose derived)
       (is (true? @disposed?)
           "routed hooks select a copied :rf.adapter/ui map by its stable canonical token"))))

#?(:cljs
   (deftest destroy-then-reinit-rearms-watchable-derived-values
     (letfn [(exercise-watch! [initial next-value]
               (let [source    ((:make-state-container ui/adapter) initial)
                     derived   ((:make-derived-value ui/adapter) [source] identity)
                     seen      (atom [])
                     unsubscribe ((:subscribe-container ui/adapter)
                                  derived
                                  #(swap! seen conj [%1 %2]))]
                 ((:read-container ui/adapter) derived)
                 ((:replace-container! ui/adapter) source next-value)
                 (is (= [[initial next-value]] @seen))
                 (unsubscribe)))]
       (is (nil? (rf/init! ui/adapter)))
       (exercise-watch! 1 2)
       (is (nil? (rf/destroy-adapter!)))
       (is (nil? (rf/current-adapter-spec)))
       (is (true? (substrate-adapter/adapter-disposed?)))
       (is (nil? (rf/init! ui/adapter))
           "a fresh init is legal after adapter disposal")
       (is (false? (substrate-adapter/adapter-disposed?)))
       (exercise-watch! 10 11))))
