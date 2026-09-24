(ns re-frame.prod-gate-dispatch-jvm-test
  "Dispatch under the REAL documented production gate.

  SECURITY.md documents `-Dre-frame.debug=false` (and `RE_FRAME_DEBUG=false`) as
  the JVM/SSR production setting. Under it, a plain
  `make-frame` → `reg-event` → `dispatch-sync` sequence must run its handler and
  commit its `:db`, emitting no `:rf.error/no-such-handler` — agreeing with
  `registrar/lookup`, which returns the handler at that same moment.

  ## What could break, and why only a real gate catches it

  `make-frame` assembles an image generation UNCONDITIONALLY (EP-0026 §Default
  Image), and `registrar/lookup` resolves through that sealed generation for
  every `(kind, id)` inside `live-frame/call-with-frame-resolution`. The
  machinery that keeps the generation in step with the registration pool — the
  reprojection hook install, the read-time flush consults, the registrar's
  removal dirty-marks — is unconditional, like the PRODUCER of the sealed
  generation. Were the MAINTAINER gated on `interop/debug-enabled?` while the
  producer was not, the generation would freeze at construction time under the
  production gate and every later `reg-*` would be invisible to dispatch.

  Only a real gate catches that, because `interop/debug-enabled?` is read ONCE
  at namespace-load time and a suite that rebinds that Var with `with-redefs`
  does so AFTER the framework has loaded. A load-time `defonce` whose body is
  skipped is invisible to `with-redefs`, so a `with-redefs` \"production
  gate\" test can stay green while the documented production configuration
  does not dispatch.

  ## Therefore: a real gate, in a real JVM

  This suite relaunches a FRESH JVM with `-Dre-frame.debug=false` actually on
  the command line (the same `java.home` + `java.class.path` relaunch pattern
  `re-frame.test-quiet` uses) running `re-frame.prod-gate-dispatch-probe`, and
  asserts on what that child observed. `gate-was-really-off` pins the harness
  itself: if the property ever stops reaching the child, THAT assertion fails
  rather than the suite passing vacuously on a dev-mode run."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.prod-gate-dispatch-probe :as rf.prod-gate-dispatch-probe]))

;; ---------------------------------------------------------------------------
;; child-JVM harness
;; ---------------------------------------------------------------------------

(defn- java-binary []
  (let [win? (str/includes? (str/lower-case (System/getProperty "os.name")) "win")]
    (str (io/file (System/getProperty "java.home") "bin"
                  (if win? "java.exe" "java")))))

(defn- drain
  "Read `stream` to EOF on its own thread. Both pipes must be drained
  concurrently — slurping stdout fully and only THEN reading stderr deadlocks a
  child that fills the stderr pipe."
  [stream]
  (future (slurp (io/reader stream))))

(defn- run-probe
  "Relaunch a fresh JVM on THIS process's classpath running the probe's `-main`,
  with `jvm-opts` prepended. Returns `{:exit :out :err}`."
  [jvm-opts]
  (let [cmd  (into [(java-binary)]
                   (concat jvm-opts
                           ["-cp" (System/getProperty "java.class.path")
                            "clojure.main"
                            "-m" "re-frame.prod-gate-dispatch-probe"]))
        proc (-> (ProcessBuilder. ^java.util.List cmd)
                 (.redirectErrorStream false)
                 (.start))
        out  (drain (.getInputStream proc))
        err  (drain (.getErrorStream proc))]
    {:exit (.waitFor proc) :out @out :err @err}))

(defn- parse-result
  "Pull the probe's single marker line out of `out` and read it back as EDN."
  [out]
  (some->> (str/split-lines out)
           (filter #(str/starts-with? % rf.prod-gate-dispatch-probe/result-marker))
           first
           (drop (count rf.prod-gate-dispatch-probe/result-marker))
           (apply str)
           edn/read-string))

;; One child JVM serves every assertion below — the relaunch costs a JVM boot
;; plus a `re-frame.core` load, so it runs once and the deftests read the same
;; observation map.
(def ^:private observed
  (delay
    (let [{:keys [exit out err] :as raw} (run-probe ["-Dre-frame.debug=false"])]
      (assoc raw :result (parse-result out) :exit exit :err err))))

;; ---------------------------------------------------------------------------
;; the harness must be real before any assertion about it means anything
;; ---------------------------------------------------------------------------

(deftest probe-child-runs-cleanly
  (testing "the relaunched JVM completes and reports a result line"
    (let [{:keys [exit err result]} @observed]
      (is (zero? exit) (str "probe JVM exited " exit "; stderr:\n" err))
      (is (some? result)
          (str "no `" rf.prod-gate-dispatch-probe/result-marker "` line on the probe's stdout;"
               " stderr:\n" err))
      (is (nil? (:probe-threw result))
          (str "the probe threw: " (:probe-threw result))))))

(deftest gate-was-really-off
  (testing "the child really loaded under `-Dre-frame.debug=false`.
            Without this pin the rest of the suite would pass vacuously the
            moment the property stopped reaching the child — and a
            `with-redefs` stand-in cannot reproduce a load-time gate."
    (let [{:keys [result]} @observed]
      (is (false? (:debug-enabled? result))
          "interop/debug-enabled? must read false in the probe JVM"))))

;; ---------------------------------------------------------------------------
;; the contract itself
;; ---------------------------------------------------------------------------

(deftest dispatch-sync-runs-its-handler-under-the-production-gate
  (testing "under `-Dre-frame.debug=false`, a handler registered
            AFTER `make-frame` runs and commits `:db`. This is the
            documented production configuration; a gated generation maintainer
            would make the dispatch a silent no-op."
    (let [{:keys [result]} @observed]
      (is (= 1 (:handler-runs result))
          "dispatch-sync must run the handler exactly once under the prod gate")
      (is (= {:n 1} (:app-db result))
          "the handler's `:db` effect must be committed under the prod gate")
      (is (empty? (:errors result))
          (str "the dispatch must emit no error under the prod gate; got "
               (pr-str (:errors result)))))))

(deftest resolution-and-registration-agree-under-the-production-gate
  (testing "the whole contract in one assertion: a registry lookup
            that SUCCEEDS while the dispatch reports `:rf.error/no-such-handler`
            is a contradiction. The bare lookup reads the registrar atom; the
            cascade's lookup reads the frame's sealed generation. They must
            answer the same question the same way."
    (let [{:keys [result]} @observed]
      (is (true? (:bare-lookup-found? result))
          "sanity: the registrar atom holds the handler")
      (is (not (some #{:rf.error/no-such-handler} (:errors result)))
          "the frame's generation must resolve what the registrar atom holds"))))

(deftest cleared-registration-disappears-under-the-production-gate
  (testing "the removal twin. Were `unregister!`'s dirty-mark gated
            on the same flag, a cleared handler would keep resolving under the
            production gate out of a sealed generation the source store does
            not back. After the clear the event must genuinely have no handler."
    (let [{:keys [result]} @observed]
      (is (= 1 (:runs-after-unregister result))
          "the cleared handler must NOT run a second time")
      (is (some #{:rf.error/no-such-handler} (:errors-after-unregister result))
          (str "dispatching a cleared event must report no-such-handler; got "
               (pr-str (:errors-after-unregister result)))))))
