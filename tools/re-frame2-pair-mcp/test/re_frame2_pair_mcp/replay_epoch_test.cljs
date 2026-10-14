(ns re-frame2-pair-mcp.replay-epoch-test
  "Unit tests for the replay-epoch tool: the tool sends only the id, and the
  preload runtime's `replay-epoch` resolves the record and replays it under
  `:rf.cofx/mint-policy :strict`. Not behind `--allow-writes`; the corpus
  fixture `:replay-epoch/happy` runs with writes off."
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.replay-epoch :as replay-epoch]))

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-all!
  "Record every eval form into `forms*`; answer `configure-raw-state!` with
  nil and everything else with `canned`."
  [forms* canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        run  (fn [form-str]
               (swap! forms* conj form-str)
               (js/Promise.resolve
                 (if (str/includes? form-str "configure-raw-state!")
                   nil
                   canned-value)))
        stub (fn
               ([_conn _build-id form-str] (run form-str))
               ([_conn _build-id form-str _opts] (run form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(deftest replay-form-carries-the-quoted-id-and-the-optional-frame
  ;; The id is parsed as EDN (the runtime's ids are integers) and rides
  ;; quoted as caller data; the frame is the runtime fn's second arg.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-all! forms {:ok? true}
            #(replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "12" :frame ":stories"}))
          (.then (fn [_]
                   (is (= '(re-frame2-pair.runtime/replay-epoch (quote 12) :stories)
                          (cljs.reader/read-string
                            (some #(when (str/includes? % "replay-epoch") %) @forms))))
                   (done)))))))

(deftest rejects-unreadable-epoch-id
  (async done
    (-> (replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "#("})
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= :invalid-epoch-id (:reason (tu/extract-edn r))))
                 (done))))))

(deftest non-envelope-runtime-value-is-not-a-success
  ;; Only an out-of-date preload (no `replay-epoch` fn) yields a non-map.
  (async done
    (-> (with-captured-all! (atom []) false
          #(replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "7"}))
        (.then (fn [r]
                 (is (tu/error? r))
                 (is (= {:ok? false :reason :replay-unavailable :epoch-id 7 :frame nil}
                        (dissoc (tu/extract-edn r) :hint)))
                 (done))))))

(deftest signals-raw-state-posture-before-the-replay-eval
  ;; The new epoch's cascade-summary copies its raw :trigger-event, so the
  ;; gate-OFF posture must reach the runtime before the replay runs.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-all! forms {:ok? true}
            #(replay-epoch/replay-epoch-tool (fresh-conn) #js {:epoch-id "7"}))
          (.then (fn [_]
                   (let [index-of (fn [s] (first (keep-indexed #(when (str/includes? %2 s) %1) @forms)))
                         cfg-idx  (index-of "configure-raw-state!")
                         rpl-idx  (index-of "re-frame2-pair.runtime/replay-epoch")]
                     (is (and (some? cfg-idx) (some? rpl-idx) (< cfg-idx rpl-idx))
                         "configure-raw-state! lands BEFORE the replay eval"))
                   (done)))))))
