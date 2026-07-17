(ns re-frame.ui.slot-render-fn-arity-cljs-test
  "rf2-ckviw — the RUNTIME half of the host-exact ui/slot ↔ ui/render-fn arity
  contract (the INLINE compile-time half is the `:rf.ui.compile/slot-arity`
  roster rows in error_roster_cljs_test). A prop-carried render-fn cannot be
  arity-checked at the slot call site (the value arrives at a library seam), so
  the compiled carrier RECORDS its fixed arity and the slot seam's
  `check-slot-arity!` enforces it BEFORE invocation — identically on both hosts,
  leaning on neither native call quirk (JS silently drops surplus args; the JVM
  throws a raw ArityException). One `.cljc` source runs the carrier seam on both
  hosts; the JVM half additionally proves the emitter wires the check ahead of
  the invocation end to end."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            ;; the render-slot CARRIER vocabulary — the CLJS runtime twin and the
            ;; JVM tree twin expose the same render-fn / check-slot-arity! surface.
            #?(:clj  [re-frame.ui.tree :as carrier]
               :cljs [re-frame.ui.runtime :as carrier])
            #?(:clj [re-frame.ui.parity-fixtures :as fx])))

(defn- caught
  "Run `thunk`; -> the thrown ExceptionInfo, or nil when it returns."
  [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) ex
         ex)))

;; ---------------------------------------------------------------------------
;; The carrier seam — host-identical arity enforcement (both hosts)
;; ---------------------------------------------------------------------------

(deftest exact-arity-passes-the-seam
  ;; a render-fn compiled with arity N, invoked with N slot args, never fires.
  (let [rf0 (carrier/render-fn (fn [] :zero) 0)
        rf2 (carrier/render-fn (fn [a b] [a b]) 2)]
    (is (nil? (caught #(carrier/check-slot-arity! rf0 0)))
        "a zero-arg slot over a zero-param render-fn is legal")
    (is (nil? (caught #(carrier/check-slot-arity! rf2 2)))
        "a two-arg slot over a two-param render-fn is legal")))

(deftest too-few-and-too-many-fail-didactically-with-identical-shape
  ;; the SAME code path on each host: the carrier records arity, the seam throws
  ;; the canonical :rf.error/ui-tree-malformed BEFORE any invocation. This is the
  ;; parity proof — identical category + message data on CLJS and JVM.
  (let [rf2 (carrier/render-fn (fn [a b] [a b]) 2)]
    (doseq [[argc dir] [[1 :too-few] [3 :too-many]]]
      (testing (str dir " (" argc " args, arity 2)")
        (let [ex (caught #(carrier/check-slot-arity! rf2 argc))]
          (is (some? ex) "a mismatched arity throws before invocation")
          (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
              "the arity twin reuses the slot-malformed runtime id")
          (is (= 're-frame.ui/slot (:where (ex-data ex))) "thrown as ui/slot")
          (is (= 2 (:expected (ex-data ex))) "carries the render-fn's declared arity")
          (is (= argc (:actual (ex-data ex))) "carries the passed argument count")
          (let [m (ex-message ex)]
            (is (str/includes? m "fixed-arity callback")
                "names the fixed-arity contract")
            (is (str/includes? m "declares 2 parameter")
                "names the declared parameter count")
            (is (str/includes? m (str "passed " argc " argument"))
                "names the passed argument count")))))))

;; ---------------------------------------------------------------------------
;; End-to-end (JVM): the emitter wires check-slot-arity! AHEAD of invocation
;; ---------------------------------------------------------------------------

#?(:clj
   (deftest prop-carried-arity-mismatch-throws-on-render-before-invocation
     ;; slot-table invokes `(ui/slot render i r)` (2 args); the bad consumer
     ;; supplies a ONE-param render-fn. The mismatch is invisible at each call
     ;; site (a prop-carried value crossing the library seam), so ONLY the
     ;; carrier-recorded arity + the emitted check can catch it — and it must,
     ;; before the render-fn body runs (a raw ArityException would be host-only).
     (let [rows [{:id 1 :name "alpha"} {:id 2 :name "beta"}]
           ex   (caught #(re-frame.ui.tree/render fx/slot-bad-arity-consumer
                                                  {:rows rows}))]
       (is (some? ex) "rendering a wrong-arity prop-carried slot throws")
       (is (= :rf.error/ui-tree-malformed (:rf.error/id (ex-data ex)))
           "the didactic slot-malformed id, NOT a raw ArityException")
       (is (str/includes? (ex-message ex) "fixed-arity callback")
           "the message names the arity contract")
       (is (= 1 (:expected (ex-data ex))) "the render-fn declared arity 1")
       (is (= 2 (:actual (ex-data ex))) "the slot passed 2 args"))
     (testing "mutation control — the correct-arity consumer renders cleanly"
       ;; slot-consumer supplies a two-param render-fn and slot-table passes two
       ;; args: the fence must NOT fire on a matching arity.
       (let [rows [{:id 1 :name "alpha"}]
             out  (caught #(re-frame.ui.tree/render fx/slot-consumer {:rows rows}))]
         (is (nil? out)
             "a matched-arity prop-carried slot renders without throwing")))))
