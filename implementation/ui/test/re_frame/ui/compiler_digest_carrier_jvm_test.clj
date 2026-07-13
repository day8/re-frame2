(ns re-frame.ui.compiler-digest-carrier-jvm-test
  "Compiler-owned whole-build digest publication.  These fixtures exercise the
  actual build-hook boundary over Shadow 3.4.10-shaped build-state data: output
  projection must succeed before compiler authority commits."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.build-hook :as build-hook]))

(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

(def ^:private carrier-rid [:cljs "re_frame/ui/digest_carrier.cljs"])
(def ^:private app-rid [:cljs "app/view.cljs"])

(defn- shadow-state
  ([build-id stage js]
   (shadow-state build-id stage js {}))
  ([build-id stage js extra]
   (merge
    {:shadow.build/build-id build-id
     :shadow.build/stage stage
     :build-sources [carrier-rid app-rid]
     :sources {carrier-rid {:ns 're-frame.ui.digest-carrier
                            :provides #{'re-frame.ui.digest-carrier}}
               app-rid {:ns 'app.view :provides #{'app.view}}}
     :output {carrier-rid {:resource-id carrier-rid :js js :cached true}
              app-rid {:resource-id app-rid :js "app.view = {};"}}}
    extra)))

(defn- prepare! [bid state]
  (build-hook/hook (assoc state
                          :shadow.build/build-id bid
                          :shadow.build/stage :compile-prepare)))

(defn- declare! [bid source id digest]
  (binding [build/*build-id* bid]
    (build/contribute! build/views source id digest)))

(deftest compile-finish-patches-exactly-one-carrier-before-commit
  (let [bid :app
        sentinel build-hook/digest-sentinel
        input (shadow-state bid :compile-finish
                            (str "before:" sentinel ":after")
                            {:build-modules
                             [{:module-id :base :sources [carrier-rid]}
                              {:module-id :lazy :sources [app-rid]
                               :depends-on #{:base}}]})]
    (prepare! bid input)
    (declare! bid 'app.view :app/view ["tf1-a" "hs1-a"])
    (let [out (build-hook/hook input)
          digest (build/finalized-build-digest bid)
          js (get-in out [:output carrier-rid :js])]
      (is (str/starts-with? digest "bd1-"))
      (is (= (count sentinel) (count digest))
          "equal-length replacement preserves source-map offsets")
      (is (not (str/includes? js sentinel)))
      (is (= 1 (count (re-seq (re-pattern digest) js))))
      (is (= "app.view = {};" (get-in out [:output app-rid :js]))
          "the output projection changes only the carrier source")
      (is (= (:build-modules input) (:build-modules out))
          "multi-entry/lazy module assignment is untouched")
      (is (= {:app/view ["tf1-a" "hs1-a"]}
             (build/committed-aggregate build/views bid))))))

(deftest malformed-carrier-output-never-commits-the-candidate
  (doseq [[label js]
          [[:missing "no marker"]
           [:duplicate (str build-hook/digest-sentinel
                            build-hook/digest-sentinel)]]]
    (testing (name label)
      (let [bid (keyword "bad" (name label))
            state (shadow-state bid :compile-finish js)]
        (prepare! bid state)
        (declare! bid 'app.view :app/view ["tf1-new" "hs1-new"])
        (let [ex (try (build-hook/hook state) nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :re-frame.ui.compiler.build-hook/carrier-output-invalid
                 (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
          (is (= {} (build/committed-aggregate build/views bid))
              "projection failure leaves last-known-good compiler state untouched")
          (is (build/pass-open? bid)
              "the uncommitted pass remains staged for normal abort/retry recovery"))))))

(deftest duplicate-carrier-resource-never-commits
  (let [bid :duplicate-carrier
        rid2 [:cljs "other/digest_carrier.cljs"]
        state (-> (shadow-state bid :compile-finish build-hook/digest-sentinel)
                  (update :build-sources conj rid2)
                  (assoc-in [:sources rid2]
                            {:ns 'other.carrier
                             :provides #{'re-frame.ui.digest-carrier}})
                  (assoc-in [:output rid2]
                            {:resource-id rid2 :js build-hook/digest-sentinel}))]
    (prepare! bid state)
    (declare! bid 'app.view :app/view ["tf1-new" "hs1-new"])
    (is (thrown? clojure.lang.ExceptionInfo (build-hook/hook state)))
    (is (= {} (build/committed-aggregate build/views bid)))))

(deftest interleaved-build-ids-publish-their-own-digest
  (let [a (shadow-state :a :compile-finish build-hook/digest-sentinel)
        b (shadow-state :b :compile-finish build-hook/digest-sentinel)]
    (prepare! :a a)
    (prepare! :b b)
    (declare! :a 'app.view :app/view ["tf1-a" "hs1-a"])
    (declare! :b 'app.view :app/view ["tf1-b" "hs1-b"])
    (let [out-b (build-hook/hook b)
          out-a (build-hook/hook a)
          da (build/finalized-build-digest :a)
          db (build/finalized-build-digest :b)]
      (is (not= da db))
      (is (str/includes? (get-in out-a [:output carrier-rid :js]) da))
      (is (str/includes? (get-in out-b [:output carrier-rid :js]) db)))))
