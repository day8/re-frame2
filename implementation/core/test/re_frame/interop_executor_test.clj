(ns re-frame.interop-executor-test
  "The JVM runtime's executors — `next-tick`'s and the timer behind
  `set-timeout!` — run on daemon threads, so a JVM whose main thread has
  returned exits instead of waiting on an idle executor."
  (:require [clojure.test :refer [deftest is]]
            [re-frame.interop :as rf.interop]))

(defn- thread-of
  "Run `schedule!` with a task that reports the thread it ran on."
  [schedule!]
  (let [p (promise)]
    (schedule! #(deliver p (Thread/currentThread)))
    (deref p 5000 nil)))

(deftest executor-threads-are-daemon-threads
  (doseq [[label schedule!] [["next-tick" rf.interop/next-tick]
                             ["set-timeout!" #(rf.interop/set-timeout! % 0)]]]
    (let [^Thread t (thread-of schedule!)]
      (is (some? t) (str label " ran its task"))
      (is (true? (some-> t .isDaemon)) (str label " runs on a daemon thread")))))
