(ns re-frame.prod-gate-dispatch-jvm-test
  "rf2-9c2jf — dispatch under the REAL documented production gate.

  SECURITY.md documents `-Dre-frame.debug=false` (and `RE_FRAME_DEBUG=false`) as
  the JVM/SSR production setting. Under it, a plain
  `make-frame` → `reg-event` → `dispatch-sync` sequence silently ran NOTHING:
  handler-runs 0, app-db untouched, `:rf.error/no-such-handler` emitted — while
  `registrar/lookup` returned the handler at that same moment.

  ## What was actually broken, and why nothing caught it

  `make-frame` assembles an image generation UNCONDITIONALLY (EP-0026 §Default
  Image), and `registrar/lookup` resolves through that sealed generation for
  every `(kind, id)` inside `live-frame/call-with-frame-resolution`. The
  machinery that keeps the generation in step with the registration pool — the
  reprojection hook install, the read-time flush consults, the registrar's
  removal dirty-marks — was gated on `interop/debug-enabled?`. The PRODUCER of
  the sealed generation was unconditional; only its MAINTAINER was gated, so
  under the production gate the generation froze at construction time and every
  later `reg-*` became invisible to dispatch.

  Nothing caught it because `interop/debug-enabled?` is read ONCE at
  namespace-load time and every existing prod-gate suite rebinds that Var with
  `with-redefs` AFTER the framework has loaded. A load-time `defonce` whose body
  was skipped is invisible to `with-redefs`, so the whole family of \"production
  gate\" tests could stay green while the documented production configuration
  did not dispatch.

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
            [re-frame.prod-gate-dispatch-probe :as probe]))

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
           (filter #(str/starts-with? % probe/result-marker))
           first
           (drop (count probe/result-marker))
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
          (str "no `" probe/result-marker "` line on the probe's stdout;"
               " stderr:\n" err))
      (is (nil? (:probe-threw result))
          (str "the probe threw: " (:probe-threw result))))))

(deftest gate-was-really-off
  (testing "rf2-9c2jf — the child really loaded under `-Dre-frame.debug=false`.
            Without this pin the rest of the suite would pass vacuously the
            moment the property stopped reaching the child, which is precisely
            how the defect survived: every other prod-gate suite asserts against
            a `with-redefs` stand-in that cannot reproduce a load-time gate."
    (let [{:keys [result]} @observed]
      (is (false? (:debug-enabled? result))
          "interop/debug-enabled? must read false in the probe JVM"))))

;; ---------------------------------------------------------------------------
;; the defect itself
;; ---------------------------------------------------------------------------

(deftest dispatch-sync-runs-its-handler-under-the-production-gate
  (testing "rf2-9c2jf — under `-Dre-frame.debug=false`, a handler registered
            AFTER `make-frame` still runs and still commits `:db`. This is the
            documented production configuration; before the fix the dispatch was
            a silent no-op."
    (let [{:keys [result]} @observed]
      (is (= 1 (:handler-runs result))
          "dispatch-sync must run the handler exactly once under the prod gate")
      (is (= {:n 1} (:app-db result))
          "the handler's `:db` effect must be committed under the prod gate")
      (is (empty? (:errors result))
          (str "the dispatch must emit no error under the prod gate; got "
               (pr-str (:errors result)))))))

(deftest resolution-and-registration-agree-under-the-production-gate
  (testing "rf2-9c2jf — the whole finding in one assertion: a registry lookup
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
  (testing "rf2-9c2jf — the removal twin. `unregister!`'s dirty-mark was gated
            on the same flag, so under the production gate a cleared handler
            kept resolving out of a sealed generation the source store no longer
            backs. After the clear the event must genuinely have no handler."
    (let [{:keys [result]} @observed]
      (is (= 1 (:runs-after-unregister result))
          "the cleared handler must NOT run a second time")
      (is (some #{:rf.error/no-such-handler} (:errors-after-unregister result))
          (str "dispatching a cleared event must report no-such-handler; got "
               (pr-str (:errors-after-unregister result)))))))
