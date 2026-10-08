(ns re-frame2-pair-mcp.freshness-test
  "The freshness / liveness token: the cross-check verdict, the merge of
  the browser and JVM halves, the JVM read with its one retry, and the
  actionable hint on every non-fresh verdict."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.freshness :as fresh]))

(deftest liveness-verdict-table
  ;; The precedence rows the end-to-end tests below do not reach.
  (doseq [[expected input why]
          [[:fresh
            {:jvm-read? true :runtime-count 1 :heartbeat-age-ms 0
             :runtime-loaded-at 1000 :build-flushed-at 1000}
            "flush == load ⇒ :fresh: the staleness comparison is a strict >"]
           [:no-runtime
            {:jvm-read? true :runtime-count 0
             :runtime-loaded-at 1000 :build-flushed-at 5000}
            ":no-runtime wins over :stale-build"]
           [:stale-build
            {:jvm-read? true :runtime-count 1
             :runtime-loaded-at 1000 :build-flushed-at 5000}
            "A missing heartbeat cannot hide a stale build: the flush/load comparison needs no heartbeat"]]]
    (is (= expected (fresh/liveness-verdict input)) why)))

;; ---------------------------------------------------------------------------
;; assemble / token-from-health, with the JVM half stubbed.
;; ---------------------------------------------------------------------------

(def ^:private browser-half
  {:runtime-instance-id "uuid-abc"
   :runtime-loaded-at   1000
   :read-at             9999})

(defn- recommends-process-termination?
  "True when `hint` tells the operator to stop, kill or restart shadow-cljs
  processes wholesale. `npx shadow-cljs stop` stops EVERY shadow-cljs
  server on the machine, and an unreadable build state is no evidence that
  any of them is a zombie."
  [hint]
  (boolean (or (re-find #"shadow-cljs stop" hint)
               (re-find #"(?i)\bkill\b" hint)
               (re-find #"(?i)zombie" hint)
               (re-find #"(?i)\ball shadow" hint))))

(deftest unknown-hint-is-bounded-and-never-stops-shadow
  ;; An unreadable JVM half degrades to :unknown, keeping the browser half
  ;; and the port; the hint names one bounded next step about the build.
  (async done
    (-> (tu/with-stubbed-freshness! nil
          (fn [] (fresh/assemble nil :examples/machine-epochs browser-half {:port 8033})))
        (.then
          (fn [token]
            (is (= (assoc browser-half :build-id :examples/machine-epochs :port 8033
                          :liveness :unknown :unknown-reason :jvm-unreadable)
                   (dissoc token :hint)))
            (is (re-find #"discover-app" (:hint token)) "the next step is a bounded re-check")
            (is (re-find #":examples/machine-epochs" (:hint token)) "names the build")
            (is (not (recommends-process-termination? (:hint token)))
                (str "an unreadable build state never prescribes stopping shadow-cljs: "
                     (:hint token)))
            (done))))))

(deftest no-runtime-hint-is-actionable-with-the-port
  ;; A stale heartbeat ⇒ :no-runtime: reload the exact URL, then re-run
  ;; discover-app.
  (async done
    (-> (tu/with-stubbed-freshness! {:compile-cycle 3 :build-flushed-at 500
                                     :runtime-count 1 :heartbeat-age-ms 60000}
          (fn [] (fresh/assemble nil :examples/machine-epochs browser-half {:port 8033})))
        (.then
          (fn [token]
            (is (= :no-runtime (:liveness token)))
            (is (re-find #"http://localhost:8033" (:hint token)))
            (is (re-find #"discover-app" (:hint token)))
            (done))))))

(deftest token-from-health-threads-the-port
  ;; discover-app's entry point: the browser half comes from `health`, the
  ;; JVM half merges in, and the port reaches both the token and the hint.
  (async done
    (-> (tu/with-stubbed-freshness! {:compile-cycle 1 :build-flushed-at 9000
                                     :runtime-count 1 :heartbeat-age-ms 50}
          (fn [] (fresh/token-from-health nil :app
                                          {:ok? true :runtime-instance-id "uuid-xyz"
                                           :runtime-loaded-at 2000 :read-at 3000
                                           :frames [:rf/default]}
                                          {:port 8033})))
        (.then
          (fn [token]
            (is (= {:runtime-instance-id "uuid-xyz" :runtime-loaded-at 2000 :read-at 3000
                    :build-id :app :port 8033 :compile-cycle 1 :build-flushed-at 9000
                    :runtime-count 1 :heartbeat-age-ms 50 :liveness :stale-build}
                   (dissoc token :hint)))
            (is (re-find #"http://localhost:8033" (:hint token)))
            (done))))))

(deftest retry-once-on-nil-retries-only-a-blank-read
  ;; A blank first read is most often a transient socket hiccup, so one
  ;; retry recovers it; a map-valued first read pays no second round-trip.
  (async done
    (let [run (fn [reads]
                (let [calls (atom 0)]
                  (-> (fresh/retry-once-on-nil
                        (fn [] (js/Promise.resolve (nth reads (dec (swap! calls inc))))))
                      (.then (fn [half] [@calls half])))))]
      (-> (js/Promise.all #js [(run [nil {:compile-cycle 5}])
                               (run [{:compile-cycle 7}])])
          (.then (fn [results]
                   (is (= [[2 {:compile-cycle 5}] [1 {:compile-cycle 7}]] (vec results))
                       "[calls half] per row")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; The real JVM read: `jvm-build-freshness` emits its form and parses the
;; reply, with only `nrepl/jvm-eval` stubbed.
;; ---------------------------------------------------------------------------

(defn- socket-conn
  "A conn that passes the live-socket check, so the real round-trip runs."
  []
  (atom {:socket :fake-live-socket :closed? false}))

(defn- with-jvm-value!
  "Stub `nrepl/jvm-eval` to answer `{:value value}`, recording each form
  into `seen` when given; restores once `body-fn`'s Promise settles."
  [value seen body-fn]
  (let [orig   nrepl/jvm-eval
        answer (fn [form]
                 (when seen (reset! seen form))
                 (js/Promise.resolve {:value value}))
        stub   (fn
                 ([_c form] (answer form))
                 ([_c form _o] (answer form)))]
    (set! nrepl/jvm-eval stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-jvm-eval! stub orig))))))

(deftest jvm-build-freshness-emits-a-parseable-well-formed-form
  ;; Only this test reads the JVM form itself: it must be one `(try ...)`
  ;; s-expression carrying the build id as its keyword literal. It must also
  ;; reduce nothing: `apply max` over an empty heartbeat sample throws there,
  ;; and the catch would erase the worker, build and runtime facts with it.
  (async done
    (let [seen (atom nil)]
      (-> (with-jvm-value! "nil" seen
            (fn [] (fresh/jvm-build-freshness (socket-conn) :examples/machine-epochs)))
          (.then
            (fn [_]
              (let [form @seen]
                (is (= 'try (first (cljs.reader/read-string form))))
                (is (re-find #"get-worker sup :examples/machine-epochs" form))
                (is (not (re-find #"\(apply max" form))))
              (done)))))))

(deftest jvm-build-freshness-parses-a-success-payload-into-the-documented-shape
  ;; The freshest of the build's own heartbeats wins.
  (async done
    (let [payload {:worker? true :compile-cycle 12 :build-flushed-at 1699999999999
                   :runtimes {4 {:last-pong nil} 5 {:last-pong nil}}
                   :relay-clients {4 {:last-pong 1700000000958} 5 {:last-pong 1700000000900}}
                   :now 1700000001000}]
      (-> (with-jvm-value! (pr-str payload) nil
            (fn [] (fresh/jvm-build-freshness (socket-conn) :app)))
          (.then
            (fn [half]
              (is (= {:worker? true :compile-cycle 12 :build-flushed-at 1699999999999
                      :runtime-count 2 :heartbeat-age-ms 42}
                     half))
              (done)))))))

(deftest jvm-build-freshness-blank-value-degrades-to-nil
  ;; Read and retried for real, a blank value degrades to nil (the caller's
  ;; :unknown), never a throw.
  (async done
    (-> (with-jvm-value! "" nil
          (fn [] (fresh/jvm-build-freshness (socket-conn) :app)))
        (.then (fn [half]
                 (is (nil? half))
                 (done))))))

;; ---------------------------------------------------------------------------
;; The heartbeat belongs to the SELECTED build's own runtimes.
;;
;; The worker's `:runtimes` and the relay's `:clients` are both keyed by
;; relay client id. The relay serves every client (other builds' tabs,
;; tools, the CLJ runtime) and stamps `:last-pong` on every message a client
;; sends; the worker runtime's own `:last-pong` is written only by a reply
;; current shadow-cljs never solicits. `jvm-reply` is what the JVM form
;; returns, so each row drives the real decode, match and verdict.
;; ---------------------------------------------------------------------------

(def ^:private now-ms 1791177296969)

(def ^:private flushed-at 1791176095823)

(defn- jvm-reply
  "The JVM form's reply for a worker holding `runtimes` (nil when the
  reached JVM runs no worker for the build) and a relay serving `clients`;
  both map client id to `{:last-pong ms}`."
  [runtimes clients]
  {:worker?          (some? runtimes)
   :compile-cycle    (when runtimes 1)
   :build-flushed-at (when runtimes flushed-at)
   :runtimes         (or runtimes {})
   :relay-clients    clients
   :now              now-ms})

(def ^:private unrelated-client
  "A relay client that is NOT one of this build's runtimes, answering
  moments ago."
  {9 {:last-pong (- now-ms 10)}})

(def ^:private heartbeat-rows
  [["no build worker in the reached JVM"
    (jvm-reply nil unrelated-client)
    {:liveness :unknown :unknown-reason :no-build-worker :runtime-count 0}]

   ["a worker with zero runtimes"
    (jvm-reply {} unrelated-client)
    {:liveness :no-runtime :runtime-count 0 :compile-cycle 1 :build-flushed-at flushed-at}]

   ["one connected runtime with no heartbeat anywhere"
    (jvm-reply {7 {}} unrelated-client)
    {:liveness :unknown :unknown-reason :heartbeat-unavailable
     :runtime-count 1 :compile-cycle 1 :build-flushed-at flushed-at}]

   ["one connected runtime without a worker :last-pong, matched to a recent relay heartbeat"
    (jvm-reply {7 {}} (assoc unrelated-client 7 {:last-pong (- now-ms 2533)}))
    {:liveness :fresh :heartbeat-age-ms 2533 :runtime-count 1 :compile-cycle 1}]

   ["a matched relay heartbeat older than the stale threshold"
    (jvm-reply {7 {}} (assoc unrelated-client 7 {:last-pong (- now-ms 45000)}))
    {:liveness :no-runtime :heartbeat-age-ms 45000 :runtime-count 1}]

   ["a fresh heartbeat on unrelated relay clients only"
    (jvm-reply {7 {}} (assoc unrelated-client 1 {:last-pong (- now-ms 2)}))
    {:liveness :unknown :unknown-reason :heartbeat-unavailable :runtime-count 1}]

   ["future and non-numeric timestamps on the build's own runtime"
    (jvm-reply {7 {:last-pong "soon"}} {7 {:last-pong (+ now-ms 60000)}})
    {:liveness :unknown :unknown-reason :heartbeat-unavailable :runtime-count 1}]

   ["a worker :last-pong from a shadow-cljs that still writes one"
    (jvm-reply {7 {:last-pong (- now-ms 800)}} {})
    {:liveness :fresh :heartbeat-age-ms 800 :runtime-count 1}]])

(def ^:private live-browser
  "A tab that loaded AFTER the last flush, so only the heartbeat decides."
  {:runtime-instance-id "uuid-live"
   :runtime-loaded-at   (+ flushed-at 600000)
   :read-at             (- now-ms 5)})

(deftest heartbeat-comes-only-from-the-selected-builds-runtimes
  ;; Each row keeps the facts it has, reports an age only from a usable
  ;; sample (the extra `:heartbeat-age-ms` key below), and no non-fresh hint
  ;; prescribes stopping shadow-cljs.
  (async done
    (-> (reduce
          (fn [p [why reply expected]]
            (.then p
                   (fn [_]
                     (.then (with-jvm-value! (pr-str reply) nil
                              (fn [] (fresh/assemble (socket-conn) :app live-browser {:port 8280})))
                            (fn [token]
                              (is (= expected
                                     (select-keys token (conj (keys expected) :heartbeat-age-ms)))
                                  why)
                              (if (= :fresh (:liveness token))
                                (is (nil? (:hint token))
                                    (str why " — a fresh verdict carries no hint"))
                                (is (not (recommends-process-termination? (str (:hint token))))
                                    (str why " — " (:hint token)))))))))
          (js/Promise.resolve nil)
          heartbeat-rows)
        (.catch (fn [e] (is false (str "assembling a token rejected: " e))))
        (.then (fn [_] (done))))))
