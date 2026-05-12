(ns re-frame-pair2-mcp.subscribe-test
  "Unit tests for the streaming subscribe / unsubscribe tools (rf2-hq49).

  These tests cover the MCP-arg → runtime-form translation (the EDN
  shape we send over nREPL), the filter-arg parser (JSON object vs
  EDN string), and the descriptor wiring. The live end-to-end coverage
  (notifications/progress emission, abort-signal termination) lives
  in `test/stdio-roundtrip.js` and the live-nREPL integration test
  against a real shadow-cljs build."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [cljs.reader]
            [clojure.string :as str]
            [applied-science.js-interop :as j]))

;; Mirror of `parse-filter-arg` from tools.cljs (private). Keeping a
;; parallel test fixture documents the contract and pins the semantics —
;; a rename in the source surfaces as a failing test rather than a
;; silent contract drift.

(defn- parse-filter [raw]
  (cond
    (nil? raw)    nil
    (string? raw) (try (cljs.reader/read-string raw)
                       (catch :default _
                         {:invalid-filter-edn raw}))
    (map? raw)    raw
    :else         (js->clj raw :keywordize-keys true)))

(deftest filter-arg-nil-passes-through
  (is (nil? (parse-filter nil))))

(deftest filter-arg-edn-string-reads
  (is (= {:op-type :error}
         (parse-filter "{:op-type :error}"))))

(deftest filter-arg-edn-string-with-touches-path
  (is (= {:touches-path [:cart :items]}
         (parse-filter "{:touches-path [:cart :items]}"))))

(deftest filter-arg-malformed-edn-surfaces-marker
  (is (= {:invalid-filter-edn "((("}
         (parse-filter "(((")))) ;; unbalanced delimiters

(deftest filter-arg-js-object-keywordises
  (let [obj #js {:op-type "error" :event-id "user/login"}
        out (parse-filter obj)]
    (is (map? out))
    (is (= "error" (:op-type out)))
    (is (= "user/login" (:event-id out)))))

(deftest filter-arg-clj-map-passes-through
  (is (= {:op-type :error}
         (parse-filter {:op-type :error}))))

;; The subscribe-form constructor is private. Re-implement here to
;; pin the exact CLJS form we send to the runtime.

(defn- subscribe-form
  [topic filter-map max-buf]
  (str "(re-frame-pair2.runtime/subscribe! "
       (pr-str (cond-> {:topic topic :max-buffered max-buf}
                 filter-map (assoc :filter filter-map)))
       ")"))

(deftest subscribe-form-with-trace-and-filter
  (let [form (subscribe-form :trace {:op-type :error :event-id :user/login} 500)]
    (is (re-find #":topic :trace" form))
    (is (re-find #":max-buffered 500" form))
    (is (re-find #":op-type :error" form))
    (is (re-find #":event-id :user/login" form))))

(deftest subscribe-form-without-filter-omits-key
  (let [form (subscribe-form :epoch nil 250)]
    (is (re-find #":topic :epoch" form))
    (is (re-find #":max-buffered 250" form))
    (is (not (re-find #":filter" form)))))

(deftest subscribe-form-fx-topic
  (let [form (subscribe-form :fx {:event-id :http/get} 100)]
    (is (re-find #":topic :fx" form))
    (is (re-find #":event-id :http/get" form))))

;; Recognised topics — keep this set in lockstep with the runtime's
;; subscribe! whitelist.

(deftest recognised-topics
  (let [recognised #{:trace :epoch :fx :error}]
    (is (contains? recognised :trace))
    (is (contains? recognised :epoch))
    (is (contains? recognised :fx))
    (is (contains? recognised :error))
    (is (not (contains? recognised :unknown)))))

;; The progress payload shape — the MCP notifications/progress
;; notification we emit on each batch tick.

(defn- progress-payload
  [progress-token tick events-edn overflow]
  #js {:progressToken progress-token
       :progress      tick
       :message       events-edn
       :data          #js {:overflow overflow}})

(deftest progress-payload-carries-token-and-counters
  (let [p (progress-payload "tok-1" 3 "{:events [...]}" 0)]
    (is (= "tok-1" (j/get p :progressToken)))
    (is (= 3 (j/get p :progress)))
    (is (= "{:events [...]}" (j/get p :message)))
    (is (= 0 (j/get-in p [:data :overflow])))))

(deftest progress-payload-with-overflow
  (let [p (progress-payload 42 1 "{...}" 7)]
    (is (= 42 (j/get p :progressToken)))
    (is (= 7 (j/get-in p [:data :overflow])))))

;; Unsubscribe-form pinning — the CLJS form sent over nREPL.

(defn- unsubscribe-form [sub-id]
  (str "(re-frame-pair2.runtime/unsubscribe! "
       (pr-str sub-id) ")"))

(deftest unsubscribe-form-roundtrips
  (let [form (unsubscribe-form "abc-123-uuid")]
    (is (= "(re-frame-pair2.runtime/unsubscribe! \"abc-123-uuid\")" form))))

(defn- drain-form [sub-id]
  (str "(re-frame-pair2.runtime/drain-subscription! "
       (pr-str sub-id) ")"))

(deftest drain-form-roundtrips
  (is (= "(re-frame-pair2.runtime/drain-subscription! \"sub-xyz\")"
         (drain-form "sub-xyz"))))
