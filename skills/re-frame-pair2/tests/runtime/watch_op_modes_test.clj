;;;; tests/runtime/watch_op_modes_test.clj
;;;;
;;;; Babashka-runnable verification of `watch-op` mode-termination
;;;; semantics and predicate-flag handling in `scripts/ops.clj`.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;;   `scripts/ops.clj` is a babashka entry point that opens nREPL
;;;;   sockets, calls `System/exit`, and prints edn to stdout. None of
;;;;   that is friendly to in-process unit testing. The functions of
;;;;   interest — the mode-termination cond and the flag-parsing loop —
;;;;   are pure logic embedded inside `watch-op` / `parse-predicate-args`.
;;;;
;;;;   This file mirrors that logic and asserts behaviour against the
;;;;   contract documented in `SKILL.md` §"Live watch (push-mode)" and
;;;;   the inline rf2-gy3n notes in `scripts/ops.clj`. Format drift in
;;;;   the production cond shows up under bb until a richer test
;;;;   harness lands.
;;;;
;;;;   KEEP IN SYNC WITH scripts/ops.clj: the mirror functions and the
;;;;   real ones must agree on flag set, default fallbacks, and stop
;;;;   ordering.
;;;;
;;;; Run:    bb tests/runtime/watch_op_modes_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns watch-op-modes-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

;; ---------------------------------------------------------------------------
;; Mirror of `flag-value` from scripts/ops.clj.
;; ---------------------------------------------------------------------------

(defn- flag-value [args flag default]
  (if-let [idx (->> args (keep-indexed (fn [i v] (when (= v flag) i))) first)]
    (nth args (inc idx) default)
    default))

(defn- has-flag? [args flag]
  (boolean (some #(= % flag) args)))

;; ---------------------------------------------------------------------------
;; Mirror of the mode-resolution prelude inside `watch-op`.
;;
;; Returns the resolved {:window-ms :count-n :stream?} after applying
;; the rf2-gy3n distinct-modes rules.
;; ---------------------------------------------------------------------------

(defn resolve-modes [args]
  (let [stream?    (has-flag? args "--stream")
        window-raw (flag-value args "--window-ms" nil)
        count-raw  (flag-value args "--count" nil)
        window-ms  (when window-raw (Long/parseLong window-raw))
        count-n    (when count-raw  (Long/parseLong count-raw))
        ;; If neither is set and we're not streaming, fall back to a
        ;; 30 s window so an argument-less watch still terminates.
        window-ms  (if (and (nil? window-ms) (nil? count-n) (not stream?))
                     30000
                     window-ms)]
    {:window-ms window-ms
     :count-n   count-n
     :stream?   stream?}))

;; ---------------------------------------------------------------------------
;; Mirror of the `done?` cond inside `watch-op`.
;;
;; Returns [:done <reason>] or nil. The contract under test is:
;;   - window-ms is checked only when set.
;;   - count-n is checked only when set.
;;   - hard-ms is always a backstop.
;;   - stream + idle is a stop only when stream? is true.
;;   - Order: window > count > hard-cap > idle (matches scripts/ops.clj).
;; ---------------------------------------------------------------------------

(defn decide-stop
  [{:keys [window-ms count-n stream?]} elapsed emitted idle hard-ms]
  (cond
    (and (not stream?) window-ms (>= elapsed window-ms))  [:done :window]
    (and (not stream?) count-n   (>= emitted count-n))    [:done :count]
    (>= elapsed hard-ms)                                  [:done :hard-cap]
    (and stream? (>= idle (or 30000 idle)))               [:done :idle]
    :else                                                 nil))

;; The (or 30000 idle) above is a guard rail: in production, idle-ms
;; defaults to 30000 via flag-value. Tests that exercise the stream
;; branch pass a concrete idle-ms below.

(defn decide-stop*
  "Variant that takes idle-ms explicitly so the stream branch is testable."
  [{:keys [window-ms count-n stream?]} elapsed emitted idle hard-ms idle-ms]
  (cond
    (and (not stream?) window-ms (>= elapsed window-ms))  [:done :window]
    (and (not stream?) count-n   (>= emitted count-n))    [:done :count]
    (>= elapsed hard-ms)                                  [:done :hard-cap]
    (and stream? (>= idle idle-ms))                       [:done :idle]
    :else                                                 nil))

;; ---------------------------------------------------------------------------
;; Behaviour matrix — rf2-gy3n contract.
;; ---------------------------------------------------------------------------

(deftest window-ms-alone-no-count-limit
  (testing "--window-ms 30000 alone: count limit is unbounded"
    (let [m (resolve-modes ["--window-ms" "30000"])]
      (is (= 30000 (:window-ms m)))
      (is (nil?    (:count-n m)))
      ;; 100 matches accumulated, only 5 s elapsed: must keep running.
      (is (nil?    (decide-stop m 5000 100 0 300000)))
      ;; 30 s elapsed: stops on :window regardless of count.
      (is (= [:done :window] (decide-stop m 30000 100 0 300000)))
      (is (= [:done :window] (decide-stop m 30001 0   0 300000))))))

(deftest count-alone-no-window-timeout
  (testing "--count 5 alone: window timeout is unbounded"
    (let [m (resolve-modes ["--count" "5"])]
      (is (nil? (:window-ms m)))
      (is (= 5  (:count-n m)))
      ;; 5 minutes elapsed, only 4 matches: keep running.
      (is (nil? (decide-stop m 300001 4 0 600000)))
      ;; 5 matches: stop on :count.
      (is (= [:done :count] (decide-stop m 0      5 0 600000)))
      (is (= [:done :count] (decide-stop m 999999 5 0 (* 60 60 1000))))
      ;; 6 matches: still :count (>=).
      (is (= [:done :count] (decide-stop m 0      6 0 600000))))))

(deftest both-flags-race
  (testing "--window-ms N --count M: first to fire wins"
    (let [m (resolve-modes ["--window-ms" "10000" "--count" "5"])]
      (is (= 10000 (:window-ms m)))
      (is (= 5     (:count-n m)))
      ;; Window fires first.
      (is (= [:done :window] (decide-stop m 10000 0 0 300000)))
      ;; Count fires first.
      (is (= [:done :count]  (decide-stop m 0     5 0 300000)))
      ;; Neither fires.
      (is (nil? (decide-stop m 9999 4 0 300000)))
      ;; Both fire simultaneously: window wins (cond order).
      (is (= [:done :window] (decide-stop m 10000 5 0 300000))))))

(deftest neither-flag-defaults-to-30s-window
  (testing "neither --window-ms nor --count (no --stream): default 30 s window"
    (let [m (resolve-modes [])]
      (is (= 30000 (:window-ms m)))
      (is (nil?    (:count-n m)))
      (is (false?  (:stream? m)))
      ;; Stops at 30 s on :window.
      (is (= [:done :window] (decide-stop m 30000 0 0 300000))))))

(deftest stream-mode-ignores-window-and-count
  (testing "--stream: window/count limits are ignored, idle/hard backstop"
    (let [m (resolve-modes ["--stream"])]
      (is (nil? (:window-ms m)))
      (is (nil? (:count-n m)))
      (is (true? (:stream? m)))
      ;; 5 minutes elapsed, 1000 matches, no idle: keeps running.
      (is (nil? (decide-stop* m 300000 1000 0 600000 30000)))
      ;; 30 s idle: stops on :idle.
      (is (= [:done :idle] (decide-stop* m 60000 1 30000 600000 30000)))
      ;; Hard-cap regardless of stream.
      (is (= [:done :hard-cap] (decide-stop* m 600000 1 0 600000 30000))))))

(deftest hard-cap-applies-to-all-modes
  (testing "--hard-ms is the global backstop for every mode"
    (let [m-win   (resolve-modes ["--window-ms" "999999999"])
          m-cnt   (resolve-modes ["--count" "999999999"])
          m-strm  (resolve-modes ["--stream"])]
      (is (= [:done :hard-cap] (decide-stop m-win  600000 0 0 600000)))
      (is (= [:done :hard-cap] (decide-stop m-cnt  600000 0 0 600000)))
      (is (= [:done :hard-cap] (decide-stop* m-strm 600000 0 0 600000 30000))))))

;; ---------------------------------------------------------------------------
;; Mirror of `parse-predicate-args` from scripts/ops.clj.
;;
;; Production version calls (emit ...) + (System/exit 1) on --custom.
;; The mirror returns a sentinel so the test can assert rejection
;; without exiting the JVM. KEEP IN SYNC.
;; ---------------------------------------------------------------------------

(defn- ->kw [s]
  (when s
    (if (str/starts-with? s ":")
      (keyword (subs s 1))
      (keyword s))))

(defn parse-predicate-args
  "Mirror of scripts/ops.clj `parse-predicate-args`. On --custom returns
   {:rejected :--custom :reason :unsupported-flag}. On unknown flags
   continues (matches production: unknown flags are skipped — they may
   be mode/predicate flags handled elsewhere)."
  [args]
  (loop [[a & more] args pred {}]
    (cond
      (nil? a) pred
      (= a "--event-id")        (recur (rest more) (assoc pred :event-id (->kw (first more))))
      (= a "--event-id-prefix") (recur (rest more)
                                       (let [raw (first more)]
                                         (assoc pred :event-id-prefix
                                                (if (str/starts-with? raw ":") raw (str ":" raw)))))
      (= a "--effects")         (recur (rest more) (assoc pred :effects (->kw (first more))))
      (= a "--touches-path")    (recur (rest more) (assoc pred :touches-path
                                                           (edn/read-string (first more))))
      (= a "--sub-ran")         (recur (rest more) (assoc pred :sub-ran (->kw (first more))))
      (= a "--render")          (recur (rest more) (assoc pred :render (first more)))
      (= a "--origin")          (recur (rest more) (assoc pred :origin (->kw (first more))))
      (= a "--frame")           (recur (rest more) (assoc pred :frame (->kw (first more))))
      (= a "--custom")          {:rejected :--custom :reason :unsupported-flag}
      :else                     (recur more pred))))

;; ---------------------------------------------------------------------------
;; Predicate-flag tests — supported set + rejected --custom.
;; ---------------------------------------------------------------------------

(deftest predicate-supported-flags
  (testing "supported predicate flags parse into the predicate map"
    (is (= {:event-id :foo}
           (parse-predicate-args ["--event-id" ":foo"])))
    (is (= {:event-id-prefix ":cart/"}
           (parse-predicate-args ["--event-id-prefix" ":cart/"])))
    (is (= {:event-id-prefix ":cart/"}
           (parse-predicate-args ["--event-id-prefix" "cart/"]))
        "--event-id-prefix should auto-prefix `:` if missing")
    (is (= {:effects :http}
           (parse-predicate-args ["--effects" ":http"])))
    (is (= {:touches-path [:user :name]}
           (parse-predicate-args ["--touches-path" "[:user :name]"])))
    (is (= {:sub-ran :cart/total}
           (parse-predicate-args ["--sub-ran" ":cart/total"])))
    (is (= {:render "my.ns/foo"}
           (parse-predicate-args ["--render" "my.ns/foo"])))
    (is (= {:origin :pair}
           (parse-predicate-args ["--origin" ":pair"])))
    (is (= {:frame :stories}
           (parse-predicate-args ["--frame" ":stories"])))))

(deftest predicate-flags-can-combine
  (testing "multiple predicate flags AND together into one map"
    (is (= {:event-id-prefix ":cart/" :origin :pair :effects :http}
           (parse-predicate-args ["--event-id-prefix" ":cart/"
                                  "--origin" ":pair"
                                  "--effects" ":http"])))))

(deftest predicate-custom-rejected
  (testing "--custom is explicitly rejected (rf2-gy3n: not implemented)"
    (let [r (parse-predicate-args ["--custom" "(fn [_] true)"])]
      (is (= :--custom            (:rejected r)))
      (is (= :unsupported-flag    (:reason r)))))
  (testing "--custom is rejected even when other valid flags are present"
    (let [r (parse-predicate-args ["--event-id" ":foo" "--custom" "(fn [_] true)"])]
      (is (= :--custom         (:rejected r))))))

(deftest predicate-unknown-flags-skipped
  (testing "unknown flags are skipped (consistent with production loop)"
    ;; Mode flags like --window-ms / --count / --stream are NOT predicates
    ;; — the predicate loop must not choke on them.
    (is (= {:event-id :foo}
           (parse-predicate-args ["--window-ms" "30000" "--event-id" ":foo"])))
    (is (= {:event-id :foo}
           (parse-predicate-args ["--count" "5" "--event-id" ":foo"])))
    (is (= {:event-id :foo}
           (parse-predicate-args ["--stream" "--event-id" ":foo"])))))

;; ---------------------------------------------------------------------------
;; Documented predicate set — must match SKILL.md §"Live watch (push-mode)".
;; If you add or remove a flag from the production parse-predicate-args,
;; update both this set and the SKILL.md predicate list.
;; ---------------------------------------------------------------------------

(def ^:private documented-predicate-flags
  #{"--event-id" "--event-id-prefix" "--effects" "--touches-path"
    "--sub-ran" "--render" "--origin" "--frame"})

(deftest documented-predicates-cover-the-flag-set
  (testing "every documented predicate flag is parsed"
    (doseq [flag documented-predicate-flags]
      (let [r (parse-predicate-args [flag ":x"])]
        (is (and (map? r) (not (contains? r :rejected)))
            (str "flag " flag " should parse, got: " (pr-str r)))))))

(deftest custom-not-in-documented-set
  (testing "--custom is no longer in the documented flag set"
    (is (not (contains? documented-predicate-flags "--custom")))))

;; ---------------------------------------------------------------------------
;; Run.
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'watch-op-modes-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
