(ns day8.re-frame2-causa-mcp.nrepl-test
  "Unit tests for the F-3 nREPL transport + bencode framing
  (rf2-8xzoe.3) — Causa-side mirror of
  `tools/pair2-mcp/test/re_frame_pair2_mcp/nrepl_test.cljs`.

  The hard bug pinned during the pair2 pilot — bencode@2 storing the
  post-decode cursor on `bencode.decode.position` rather than the
  module-level export — is the kind of regression that's easy to
  reintroduce, so the multi-frame walker gets a thorough test.

  Tests pin `decode-all-frames` directly from
  `day8.re-frame2-causa-mcp.nrepl` — the source ns is the contract.

  Also pins the F-3 lift: `read-port-from-fs` now lives on the
  transport ns (was inlined in `server.cljs` at F-2), and the
  conn-record shape that `make-conn` constructs."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            ["bencode" :as bencode]
            [day8.re-frame2-causa-mcp.nrepl :as nrepl]))

(defn- decode-all
  "Wrap `nrepl/decode-all-frames` returning `[clj-vec rest-buf]`.
  Source returns `[js-array rest-buf]` for hot-path perf; tests want a
  CLJS vector to walk."
  [^js buf]
  (let [[js-frames rest] (nrepl/decode-all-frames buf)]
    [(vec (array-seq js-frames)) rest]))

(deftest single-frame-decodes
  (let [buf      (js/Buffer.from "d3:foo3:bare" "utf8")
        [fs rst] (decode-all buf)]
    (is (= 1 (count fs)))
    (is (= "bar" (j/get (first fs) "foo")))
    (is (zero? (.-length rst)))))

(deftest two-concatenated-frames-decode
  ;; This is the case the bash-shim chain never had to handle (each
  ;; call opened a fresh socket), and the one that bit the pair2 pilot.
  (let [buf      (js/Buffer.from "d3:foo3:bared3:baz3:quxe" "utf8")
        [fs rst] (decode-all buf)]
    (is (= 2 (count fs)))
    (is (= "bar" (j/get (nth fs 0) "foo")))
    (is (= "qux" (j/get (nth fs 1) "baz")))
    (is (zero? (.-length rst)))))

(deftest nrepl-status-response-decodes
  ;; A representative two-frame nREPL response: value then status.
  (let [buf      (js/Buffer.from
                   "d2:id1:15:value1:3ed2:id1:16:statusl4:doneee"
                   "utf8")
        [fs rst] (decode-all buf)]
    (is (= 2 (count fs)))
    (is (= "3" (j/get (nth fs 0) "value")))
    (let [status (j/get (nth fs 1) "status")]
      (is (some? status))
      (is (= "done" (aget status 0))))
    (is (zero? (.-length rst)))))

(deftest three-frames-decode
  (let [buf      (js/Buffer.from
                   "d1:ai1ee" "utf8")
        twice    (js/Buffer.concat #js [buf buf buf])
        [fs rst] (decode-all twice)]
    (is (= 3 (count fs)))
    (is (zero? (.-length rst)))))

;; ---------------------------------------------------------------------------
;; Public surface — vars exist and are callable.
;; ---------------------------------------------------------------------------

(deftest public-vars-exist
  (testing "F-3 lands the structural surface — every public var the
            tool-dispatcher F-tranches will lean on is resolvable"
    (is (fn? nrepl/read-port-from-fs))
    (is (fn? nrepl/decode-all-frames))
    (is (fn? nrepl/make-conn))
    (is (fn? nrepl/connect!))
    (is (fn? nrepl/close!))
    (is (fn? nrepl/send-op!))
    (is (fn? nrepl/jvm-eval))
    (is (fn? nrepl/cljs-eval))
    (is (fn? nrepl/cljs-eval-value))))

;; ---------------------------------------------------------------------------
;; Port discovery — lifted from server.cljs at F-3.
;; ---------------------------------------------------------------------------

(deftest read-port-from-fs-returns-nil-or-int
  (testing "read-port-from-fs is total — returns nil when no port
            source is present, or a positive integer when one is.
            The degraded boot path keys on the nil branch."
    ;; The node-test harness runs from `tools/causa-mcp/` (no port
    ;; files at any of the three candidate paths). We only assert the
    ;; nil-or-int contract; depending on dev-state the env-var might
    ;; be set, so we accept either.
    (let [result (nrepl/read-port-from-fs)]
      (is (or (nil? result)
              (and (number? result) (pos? result)))))))

;; ---------------------------------------------------------------------------
;; make-conn — initial record shape.
;; ---------------------------------------------------------------------------

(deftest make-conn-initial-shape
  (testing "make-conn builds an atom carrying the fresh-connection
            record: port + host + closed?=true + no socket + empty
            pending map. connect! flips closed? and fills :socket;
            close! resets back to the same closed shape."
    (let [conn @(nrepl/make-conn 12345 nil)]
      (is (= 12345 (:port conn)))
      (is (= "127.0.0.1" (:host conn))
          "nil host defaults to loopback — the shadow-cljs nREPL only
           ever binds to localhost in dev")
      (is (nil? (:socket conn)))
      (is (true? (:closed? conn)))
      (is (= {} (:pending conn)))
      (is (nil? (:session conn))))))

(deftest make-conn-honours-explicit-host
  (testing "an explicit host overrides the loopback default — the
            transport doesn't assume localhost, only defaults to it"
    (let [conn @(nrepl/make-conn 12345 "10.0.0.42")]
      (is (= "10.0.0.42" (:host conn))))))
