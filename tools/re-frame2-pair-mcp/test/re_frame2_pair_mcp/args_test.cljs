(ns re-frame2-pair-mcp.args-test
  "Unit tests for the shared MCP arg parsers. Boolean accept-shapes are
  `re-frame.mcp-base.args-test`'s; this suite pins the table lookup on top."
  (:require [cljs.test :refer-macros [deftest is]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools.args :as args]))

(defn- args-js
  "A JS args object from a CLJS map — the MCP wire ships string keys."
  [m]
  (let [o #js {}]
    (doseq [[k v] m]
      (j/assoc! o (name k) v))
    o))

;; ---------------------------------------------------------------------------
;; parse-bool-arg
;; ---------------------------------------------------------------------------

(deftest parse-bool-arg-absent-uses-table-default
  ;; An absent slot, and a nil or undefined args object, all read the
  ;; documented default. The privacy knobs default closed.
  (let [ks [:dedup :elision :cache :include-sensitive :include-fx-args
            :include-values :drain :stop]]
    (doseq [a [(args-js {}) nil js/undefined]]
      (is (= {:dedup true :elision true :cache false :include-sensitive false
              :include-fx-args false :include-values false :drain false :stop false}
             (zipmap ks (map #(args/parse-bool-arg a %) ks)))
          (pr-str a)))))

(deftest parse-bool-arg-explicit-value-overrides-default
  (let [ks [:dedup :elision :cache :include-sensitive]]
    (doseq [a [(args-js {:dedup false :elision false :cache true :include-sensitive true})
               (args-js {:dedup "no" :elision "off" :cache "yes" :include-sensitive "1"})]]
      (is (= {:dedup false :elision false :cache true :include-sensitive true}
             (zipmap ks (map #(args/parse-bool-arg a %) ks)))))))

;; ---------------------------------------------------------------------------
;; read-edn-arg — each caller passes its own reason keywords.
;; ---------------------------------------------------------------------------

(deftest read-edn-arg-err-arms-forward-the-callers-reasons
  (doseq [[raw missing invalid expected]
          [["   " :missing-db :invalid-db [:err :missing-db]]
           ["{:k" :missing-db :invalid-db [:err :invalid-db]]
           [nil :missing-epoch-id :invalid-epoch-id-edn [:err :missing-epoch-id]]
           ["{:unterminated" :missing-epoch-id :invalid-epoch-id-edn [:err :invalid-epoch-id-edn]]]]
    (is (= expected (args/read-edn-arg raw missing invalid)) (pr-str raw))))

(deftest read-edn-arg-parses-valid-edn
  (is (= [:ok {:counter 0}] (args/read-edn-arg "{:counter 0}" :missing :invalid))))

;; ---------------------------------------------------------------------------
;; parse-event-arg — the one event-parse seam dispatch and dispatch-dry-run
;; share, and its security gate: host-form source reads as a list and is
;; refused, never spliced into the runtime eval.
;; ---------------------------------------------------------------------------

(def ^:private dispatch-hint
  "usage: dispatch {event '[:ev/id ...]' [sync true] [trace true] [frame :foo] [fx-overrides {...}] [interceptor-overrides {...}]}")

(deftest parse-event-arg-refuses-anything-but-a-vector
  ;; Only the missing arm carries the caller's hint.
  (doseq [[in expected]
          [[nil   {:ok? false :reason :missing-event :hint dispatch-hint}]
           ["   " {:ok? false :reason :missing-event :hint dispatch-hint}]
           ["[:foo" {:reason :invalid-event-edn :event "[:foo"}]
           ["{:id :foo}" {:reason :not-an-event-vector :parsed-type :map :event "{:id :foo}"}]
           [":cart/checkout" {:reason :not-an-event-vector :parsed-type :keyword :event ":cart/checkout"}]
           ["(println :pwn)" {:reason :not-an-event-vector :parsed-type :list :event "(println :pwn)"}]
           ["some-symbol" {:reason :not-an-event-vector :parsed-type :symbol :event "some-symbol"}]
           ["42" {:reason :not-an-event-vector :parsed-type :scalar :event "42"}]]]
    (let [[tag env] (args/parse-event-arg in dispatch-hint)]
      (is (= [:err expected] [tag (select-keys env (keys expected))]) (pr-str in)))))

(deftest parse-event-arg-accepts-vectors
  (is (= [:ok [:cart/add {:sku "abc"}]]
         (args/parse-event-arg "[:cart/add {:sku \"abc\"}]" dispatch-hint))))

;; ---------------------------------------------------------------------------
;; parse-timeout-arg — a non-numeric deadline would make `(>= elapsed NaN)`
;; never true, polling forever; zero or negative times out at once.
;; ---------------------------------------------------------------------------

(deftest parse-timeout-arg-accepts-only-positive-integers
  (doseq [[v expected] [[nil [:ok nil]] [1 [:ok 1]] ["250" [:ok 250]]]]
    (is (= expected (args/parse-timeout-arg "wait-ms" v)) (pr-str v)))
  (doseq [v ["never" 0 12.5]]
    (let [[tag env] (args/parse-timeout-arg "wait-ms" v)]
      (is (= [:err :invalid-numeric-arg "wait-ms" v] [tag (:reason env) (:arg env) (:given env)])
          (pr-str v)))))

;; ---------------------------------------------------------------------------
;; parse-fx-overrides — a non-keyword target would silently fall through to
;; the real fx, so only colon-prefixed ids and null are accepted.
;; ---------------------------------------------------------------------------

(deftest parse-fx-overrides-coerces-colon-string-targets-to-keywords
  (is (= [:ok {:http :stub-http :navigate :noop-nav :persist nil}]
         (args/parse-fx-overrides #js {":http" ":stub-http" ":navigate" ":noop-nav" ":persist" nil}))))

(deftest parse-fx-overrides-rejects-non-colon-and-non-string-targets
  (let [[tag m] (args/parse-fx-overrides #js {":http" "stub-http"})]
    (is (= [:err :rf.error/invalid-fx-overrides] [tag (:reason m)]))))

(deftest parse-fx-overrides-fn-override-sentinel-fails-loud
  ;; The recorded stand-in for an unserializable fn override (Tool-Pair
  ;; §Replay) is never a redirect target, even beside a valid entry.
  (let [[tag m] (args/parse-fx-overrides #js {":http" ":stub-http" ":navigate" ":rf/fn-override"})]
    (is (= [:err :rf.error/unreplayable-fx-override :navigate] [tag (:reason m) (:target m)]))))

;; ---------------------------------------------------------------------------
;; parse-interceptor-overrides — keys and values are colon-tolerant keyword
;; ids or bracket-shaped "[id arg]" refs; null removes.
;; ---------------------------------------------------------------------------

(deftest parse-interceptor-overrides-coerces-ref-shaped-keys-and-values
  (is (= [:ok {:auth/required :story/skip-auth
               :audit/record-event nil
               [:rf.interceptor/path [:cart]] [:rf.interceptor/path [:cart :items]]
               :auth/other :story/other}]
         (args/parse-interceptor-overrides
           #js {":auth/required" ":story/skip-auth"
                ":audit/record-event" nil
                "[:rf.interceptor/path [:cart]]" "[:rf.interceptor/path [:cart :items]]"
                "auth/other" "story/other"}))))

(deftest parse-interceptor-overrides-rejects-non-ref-input
  ;; Rejections carry the runtime's own chain-assembly reason.
  (doseq [[label o] [["blank replacement"                             #js {":auth/required" "   "}]
                     ["number replacement"                            #js {":auth/required" 42}]
                     ["unreadable bracket EDN"                        #js {"[:bad" ":story/skip-auth"}]
                     ["a 3-vector isn't a valid [id arg] ref"         #js {"[:a :b :c]" ":story/skip-auth"}]
                     ["a non-keyword-headed vector isn't a valid ref" #js {"[1 2]" ":story/skip-auth"}]
                     ["not an object"                                 "not-an-object"]]]
    (let [[tag m] (args/parse-interceptor-overrides o)]
      (is (= [:err :rf.error/interceptor-override-invalid] [tag (:reason m)]) label))))
