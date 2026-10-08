(ns re-frame2-pair-mcp.raw-state-test
  "Launch-flag parsing and diagnostics, and the runtime raw-state signal.
  What the `--allow-sensitive-reads` gate does to each tool is pinned by
  the conformance corpus's `:raw-state/*` fixtures."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]))

;; Restore the stubbed eval here rather than in a per-test `.finally`, which
;; fires after `done` and can clobber a neighbour namespace's stub mid-eval.
(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn []
            (set! nrepl/cljs-eval-value pristine-eval)
            (raw-state/reset-runtime-signal-cache!)
            (raw-state/set-allow-raw-state! false))})

;; ---------------------------------------------------------------------------
;; Launch-flag parsing.
;; ---------------------------------------------------------------------------

(deftest parse-launch-flags-defaults
  ;; Sensitive reads and writes are opt-in; eval-cljs is opt-out.
  (is (= {:eval-allowed? true :allow-raw-state? false :allow-writes? false
          :port-file nil :http-port nil}
         (server/parse-launch-flags []))))

(deftest parse-launch-flags-old-name-rejected
  ;; The removed `--allow-raw-state` spelling must not open the gate.
  (is (false? (:allow-raw-state? (server/parse-launch-flags ["--allow-raw-state"])))))

(deftest parse-launch-flags-valued-flags-ride-with-other-flags
  (is (= {:eval-allowed? false :allow-raw-state? true :allow-writes? false
          :port-file "/p/nrepl.port" :http-port 9702}
         (server/parse-launch-flags
           ["--no-eval" "--http-port" "9702" "--port-file" "/p/nrepl.port"
            "--allow-sensitive-reads"]))))

(deftest parse-launch-flags-port-file-missing-value-is-nil
  ;; A following flag is not a value.
  (doseq [argv [["--port-file"] ["--port-file" "--no-eval"]]]
    (is (nil? (:port-file (server/parse-launch-flags argv))) (pr-str argv))))

(deftest parse-launch-flags-port-file-last-occurrence-wins
  (is (= "/second/nrepl.port"
         (:port-file (server/parse-launch-flags
                       ["--port-file" "/first/nrepl.port" "--port-file=/second/nrepl.port"])))))

(deftest parse-launch-flags-http-port-non-numeric-is-nil
  ;; Never a NaN port: discovery falls back to shadow's default.
  (is (nil? (:http-port (server/parse-launch-flags ["--http-port" "garbage"])))))

;; ---------------------------------------------------------------------------
;; Launch-config diagnostics: the parser stays permissive, and this layer
;; names each rejected input and the fallback the operator actually gets.
;; ---------------------------------------------------------------------------

(defn- without-effect [diagnostics] (mapv #(dissoc % :effect) diagnostics))

(deftest launch-diagnostics-clean-config-empty
  (is (= [] (server/launch-diagnostics
              ["--no-eval" "--allow-sensitive-reads" "--allow-writes"
               "--port-file" "/abs/nrepl.port" "--http-port=9700"]))))

(deftest launch-diagnostics-names-unknown-flag
  (is (= [{:severity :warn :input "--no-eavl" :issue :unknown-flag}]
         (without-effect (server/launch-diagnostics ["--no-eavl"])))))

(deftest launch-diagnostics-names-removed-flag
  ;; The effect names what to pass instead.
  (doseq [[flag replacement] [["--allow-raw-state" #"allow-sensitive-reads"]
                              ["--allow-eval" #"--no-eval"]]]
    (let [[d] (server/launch-diagnostics [flag])]
      (is (= :removed-flag (:issue d)) flag)
      (is (re-find replacement (:effect d)) flag))))

(deftest launch-diagnostics-names-missing-value
  (doseq [[argv flag] [[["--port-file"] "--port-file"]
                       [["--http-port" "--no-eval"] "--http-port"]]]
    (is (= {:issue :missing-value :input flag}
           (select-keys (first (server/launch-diagnostics argv)) [:issue :input])))))

(deftest launch-diagnostics-names-malformed-http-port
  (let [[d] (server/launch-diagnostics ["--http-port" "garbage"])]
    (is (= :malformed-value (:issue d)))
    (is (re-find #"9630" (:effect d)))))

(deftest launch-diagnostics-names-boolean-flag-with-inline-value
  ;; The parser takes only the bare token, so `--no-eval=true` leaves eval
  ;; ON; the diagnostic must say so rather than read the prefix as the flag.
  (let [argv ["--no-eval=true"]
        ds   (server/launch-diagnostics argv)]
    (is (= [{:severity :warn :input "--no-eval=true" :issue :malformed-value}]
           (without-effect ds)))
    (is (re-find #"eval-cljs stays ENABLED" (:effect (first ds))))
    (is (true? (:eval-allowed? (server/parse-launch-flags argv))))))

;; ---------------------------------------------------------------------------
;; signal-runtime!. The runtime's raw-state posture resets to permissive on
;; every page reload, so the signal is sent before EVERY state-emitting eval;
;; only a concurrent in-flight configure for the same build is shared.
;; ---------------------------------------------------------------------------

(deftest signal-runtime-reconfigures-each-call
  (async done
    (let [calls (atom 0)
          count! (fn [] (swap! calls inc) (js/Promise.resolve nil))]
      (raw-state/reset-runtime-signal-cache!)
      (set! nrepl/cljs-eval-value (fn ([_ _ _] (count!)) ([_ _ _ _] (count!))))
      (-> (raw-state/signal-runtime! nil :app)
          (.then (fn [_] (raw-state/signal-runtime! nil :app)))
          (.then (fn [_] (raw-state/signal-runtime! nil :app)))
          (.then (fn [_] (is (= 3 @calls) "no permanent per-build skip")))
          ;; Upstream of the single `done`, so a rejection reports rather than hangs.
          (.catch (fn [e] (is false (str "signal-runtime! must not reject: " (.-message e)))))
          (.then (fn [_] (done)))))))

(deftest signal-runtime-dedups-concurrent-in-flight
  ;; No caller may reach its state-emitting eval before the posture lands.
  (async done
    (let [calls    (atom 0)
          resolve! (atom nil)
          pending  (fn []
                     (swap! calls inc)
                     (js/Promise. (fn [res _] (reset! resolve! res))))]
      (raw-state/reset-runtime-signal-cache!)
      (set! nrepl/cljs-eval-value (fn ([_ _ _] (pending)) ([_ _ _ _] (pending))))
      (let [p1 (raw-state/signal-runtime! nil :app)
            p2 (raw-state/signal-runtime! nil :app)]
        (is (= 1 @calls) "two concurrent signals share one configure round-trip")
        (when-let [r @resolve!] (r nil))
        ;; Only waits for both to settle; the swallow sits upstream of `done`.
        (-> (js/Promise.all #js [p1 p2])
            (.catch (fn [_] nil))
            (.then (fn [_] (done))))))))
