(ns re-frame.ui.compiler-digest-carrier-jvm-test
  "Pure carrier projection plus functional accepted-snapshot transaction tests."
  (:require [cljs.env :as cljs-env]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.build-hook :as build-hook]))

(def ^:private carrier-rid [:cljs "re_frame/ui/digest_carrier.cljs"])
(def ^:private app-rid [:cljs "app/view.cljs"])

(defn- shadow-state [build-id js]
  {:shadow.build/build-id build-id
   :shadow.build/stage :compile-prepare
   :compiler-env {}
   :executor (Object.)
   :build-options {:cache-blockers '#{re-frame.ui}}
   :build-sources [carrier-rid app-rid]
   :sources {carrier-rid {:ns 're-frame.ui.digest-carrier
                          :provides #{'re-frame.ui.digest-carrier}
                          :type :cljs}
             app-rid {:ns 'app.view
                      :provides #{'app.view}
                      :requires '#{re-frame.ui}
                      :type :cljs}}
   :output {carrier-rid {:resource-id carrier-rid :js js :cached true}
            app-rid {:resource-id app-rid :js "app.view = {};"}}
   :build-modules [{:module-id :base :sources [carrier-rid]}
                   {:module-id :lazy :sources [app-rid]
                    :depends-on #{:base}}]})

(defn- prepare [state]
  (build-hook/hook (assoc state :shadow.build/stage :compile-prepare)))

(defn- declare [state source id digest]
  (let [compiler (atom (assoc (:compiler-env state)
                              :shadow.build.cljs-bridge/state state))]
    (binding [cljs-env/*compiler* compiler]
      (build/contribute! build/views source id digest))
    (assoc state :compiler-env
           (dissoc @compiler :shadow.build.cljs-bridge/state))))

(defn- finish [state]
  (build-hook/hook (assoc state :shadow.build/stage :compile-finish)))

(deftest ui-build-requires-the-load-bearing-cache-blocker
  (doseq [configured [nil [] '#{other.library}]]
    (let [input (assoc-in (shadow-state :bad-config build-hook/digest-sentinel)
                          [:build-options :cache-blockers]
                          configured)
          ex (try (prepare input) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :re-frame.ui.compiler.build-hook/cache-blocker-missing
             (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
      (is (nil? (get-in input [:compiler-env build/scratch-key]))))))

(deftest version-zero-prepare-invalidates-retained-ui-consumer-output
  (let [input (shadow-state :warm-start build-hook/digest-sentinel)
        prepared (prepare input)]
    (is (nil? (get-in prepared [:output app-rid]))
        "retained output cannot bypass registry macro expansion on daemon start")
    (is (contains? (get-in prepared [:compiler-env build/scratch-key :touched])
                   'app.view))
    (is (= build-hook/digest-sentinel
           (get-in prepared [:output carrier-rid :js])))))

(deftest accepted-warm-prepare-preserves-output-present-ui-consumer
  (let [good (-> (shadow-state :warm-incremental build-hook/digest-sentinel)
                 prepare
                 (declare 'app.view :app/view ["tf1-good" "hs1-good"])
                 finish)
        warm-input (-> good
                       (assoc-in [:output carrier-rid :js]
                                 build-hook/digest-sentinel)
                       (assoc-in [:output app-rid]
                                 {:resource-id app-rid
                                  :js "app.view = {};"
                                  :cached true}))
        prepared (prepare warm-input)]
    (is (= "app.view = {};" (get-in prepared [:output app-rid :js])))
    (is (not (contains?
              (get-in prepared [:compiler-env build/scratch-key :touched])
              'app.view))
        "accepted output-present cache hits do not become whole-UI recompiles")))

(deftest compile-finish-patches-carrier-and-carries-one-snapshot
  (let [sentinel build-hook/digest-sentinel
        input (shadow-state :app (str "before:" sentinel ":after"))
        prepared (prepare input)
        compiled (declare prepared 'app.view :app/view ["tf1-a" "hs1-a"])
        out (finish compiled)
        digest (build/accepted-build-digest out)
        js (get-in out [:output carrier-rid :js])]
    (is (str/starts-with? digest "bd1-"))
    (is (= (count sentinel) (count digest)))
    (is (not (str/includes? js sentinel)))
    (is (= 1 (count (re-seq (re-pattern digest) js))))
    (is (nil? (get-in out [:output app-rid]))
        "version-zero prepare invalidates retained UI-consumer output before Shadow recompiles it")
    (is (= (:build-modules input) (:build-modules out)))
    (is (= {:app/view ["tf1-a" "hs1-a"]}
           (build/accepted-aggregate build/views out)))
    (is (= 1 (:version (build/accepted-snapshot out))))
    (is (nil? (get-in out [:compiler-env build/scratch-key])))))

(deftest malformed-carrier-never-produces-an-accepted-candidate
  (doseq [[label js]
          [[:missing "no marker"]
           [:duplicate (str build-hook/digest-sentinel
                            build-hook/digest-sentinel)]]]
    (testing (name label)
      (let [input (shadow-state (keyword "bad" (name label)) js)
            compiled (-> input prepare
                         (declare 'app.view :app/view ["tf1-new" "hs1-new"]))
            ex (try (finish compiled) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :re-frame.ui.compiler.build-hook/carrier-output-invalid
               (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
        (is (= {} (build/accepted-aggregate build/views input)))
        (is (= 0 (:version (build/accepted-snapshot input))))
        (is (some? (get-in compiled [:compiler-env build/scratch-key])))))))

(deftest duplicate-carrier-resource-is-loud-before-candidate-carriage
  (let [rid2 [:cljs "other/digest_carrier.cljs"]
        input (-> (shadow-state :duplicate-carrier build-hook/digest-sentinel)
                  (update :build-sources conj rid2)
                  (assoc-in [:sources rid2]
                            {:ns 'other.carrier
                             :provides #{'re-frame.ui.digest-carrier}})
                  (assoc-in [:output rid2]
                            {:resource-id rid2 :js build-hook/digest-sentinel}))
        compiled (-> input prepare
                     (declare 'app.view :app/view ["tf1-new" "hs1-new"]))]
    (is (thrown? clojure.lang.ExceptionInfo (finish compiled)))
    (is (= {} (build/accepted-aggregate build/views input)))))

(deftest interleaved-build-values-carry-isolated-digests
  (let [a (-> (shadow-state :a build-hook/digest-sentinel)
              prepare
              (declare 'app.view :app/view ["tf1-a" "hs1-a"])
              finish)
        b (-> (shadow-state :b build-hook/digest-sentinel)
              prepare
              (declare 'app.view :app/view ["tf1-b" "hs1-b"])
              finish)
        da (build/accepted-build-digest a)
        db (build/accepted-build-digest b)]
    (is (not= da db))
    (is (str/includes? (get-in a [:output carrier-rid :js]) da))
    (is (str/includes? (get-in b [:output carrier-rid :js]) db))))

(deftest downstream-failure-is-discard-not-rollback
  (let [seed (shadow-state :app build-hook/digest-sentinel)
        good (-> seed prepare
                 (declare 'app.view :app/view ["tf1-good" "hs1-good"])
                 finish)
        doomed-input (-> good
                         ;; A recompiled ^:dev/always carrier contains the
                         ;; sentinel again before compile-finish projection.
                         (assoc-in [:output carrier-rid :js]
                                   build-hook/digest-sentinel))
        doomed (-> doomed-input prepare
                   (declare 'app.view :app/view ["tf1-doomed" "hs1-doomed"])
                   finish)]
    (is (not= (build/accepted-build-digest good)
              (build/accepted-build-digest doomed)))
    ;; Model a failure after compile-finish: Shadow retains `good`, not
    ;; `doomed`. No external atom needs rollback and a retry seeds from good.
    (let [retry-input (assoc-in good [:output carrier-rid :js]
                                build-hook/digest-sentinel)
          retry (-> retry-input prepare
                    (declare 'app.view :app/view ["tf1-retry" "hs1-retry"])
                    finish)]
      (is (= 2 (:version (build/accepted-snapshot retry))))
      (is (= {:app/view ["tf1-retry" "hs1-retry"]}
             (build/accepted-aggregate build/views retry))))))
