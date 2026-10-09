(ns re-frame.test-quiet-stderr-ring-concurrency-test
  "Regression for the JVM runner's stderr ring. `-main` funnels `*err*` (a
  PrintWriter) and raw `System.err` (a UTF-8 PrintStream over an OutputStream
  bridge) into one StringBuilder through wrappers holding DISTINCT locks;
  without the ring's own monitor a concurrent append and front-trim tear it
  (`ArrayIndexOutOfBoundsException`), reddening a passing test. The race
  needs the bridge's chunked writes: two PrintWriters do not trip it."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.test-quiet.runner]))

(def ^:private stderr-buffer-cap
  @#'re-frame.test-quiet.runner/stderr-buffer-cap)

(defn- make-stderr-wiring
  "`-main`'s two stderr channels over a fresh ring."
  []
  (let [stderr-ring (StringBuilder.)
        writer      (#'re-frame.test-quiet.runner/buffering-stderr-writer stderr-ring)
        bridge      (proxy [java.io.OutputStream] []
                      (write
                        ([byte-value]
                         (.write writer (String. (byte-array [(unchecked-byte byte-value)]))))
                        ([byte-buffer offset length]
                         (.write writer (String. ^bytes byte-buffer (int offset) (int length))))))]
    {:stderr-ring        stderr-ring
     :dynamic-err-writer (java.io.PrintWriter. writer)
     :system-err-stream  (java.io.PrintStream. bridge true "UTF-8")}))

(def ^:private big-line
  "Larger than the cap, so every write drives the front-trim."
  (apply str (repeat 400000 \x)))

(deftest concurrent-dual-channel-writes-do-not-corrupt-the-ring
  (let [trials (vec (for [_ (range 30)]
                      (let [{:keys [stderr-ring dynamic-err-writer system-err-stream]}
                            (make-stderr-wiring)
                            gate   (promise)
                            writes [(future @gate (dotimes [_ 3] (.println dynamic-err-writer big-line)))
                                    (future @gate (dotimes [_ 3] (.println system-err-stream big-line)))]]
                        (deliver gate :go)
                        {:thrown (try (run! deref writes) nil
                                      (catch Throwable e (.getMessage e)))
                         :length (.length stderr-ring)})))]
    (is (= [] (keep :thrown trials)))
    (is (every? #(<= (:length %) stderr-buffer-cap) trials))))
