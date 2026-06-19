;;;; scripts/bencode.clj — minimal bencode codec for the nREPL wire.
;;;;
;;;; bb ships no nREPL client and we don't want a Maven dep just for this:
;;;; bencode is a ~40-line protocol and nREPL speaks it directly over TCP,
;;;; so inlining the codec is simpler than bolting on a dependency.
;;;;
;;;; Shared by BOTH halves of the bash-shim transport (rf2-qq7w2k):
;;;;   - scripts/ops.clj           ENCODES requests + DECODES responses
;;;;   - tests/shim/stub_nrepl.clj DECODES requests + ENCODES canned replies
;;;; They are mirror halves of the same protocol, so the codec lives once.
;;;;
;;;; Dependency-free + bb-loadable: both consumers `load-file` this off
;;;; their own `*file*` (so cwd doesn't matter) BEFORE their `ns` form,
;;;; then `(:require [bencode :as bc])`. It pulls in nothing beyond JDK
;;;; classes, keeping the no-Maven-dep posture intact.

(ns bencode
  (:import (java.io PushbackInputStream)))

(defn encode ^String [v]
  (cond
    (integer? v)   (str "i" v "e")
    (string? v)    (let [bs (.getBytes ^String v "UTF-8")]
                     (str (alength bs) ":" v))
    (keyword? v)   (encode (name v))
    (map? v)       (str "d"
                        (apply str (mapcat (fn [[k v]] [(encode k) (encode v)])
                                           (sort-by (fn [[k _]] (if (keyword? k) (name k) (str k))) v)))
                        "e")
    (sequential? v) (str "l" (apply str (map encode v)) "e")
    (nil? v)       (encode "")
    :else          (encode (pr-str v))))

(defn read-char ^Character [^PushbackInputStream in]
  (let [b (.read in)]
    (when (neg? b) (throw (ex-info "unexpected EOF" {})))
    (char b)))

(defn decode [^PushbackInputStream in]
  (let [c (read-char in)]
    (case c
      \i (let [sb (StringBuilder.)]
           (loop [ch (read-char in)]
             (if (= ch \e)
               (Long/parseLong (.toString sb))
               (do (.append sb ch) (recur (read-char in))))))
      \l (loop [acc []]
           (let [b (.read in)]
             (cond (neg? b)          (throw (ex-info "unexpected EOF in list" {}))
                   (= b (int \e))    acc
                   :else             (do (.unread in b) (recur (conj acc (decode in)))))))
      \d (loop [acc {}]
           (let [b (.read in)]
             (cond (neg? b)          (throw (ex-info "unexpected EOF in dict" {}))
                   (= b (int \e))    acc
                   :else             (do (.unread in b)
                                         (let [k (decode in)
                                               v (decode in)]
                                           (recur (assoc acc k v)))))))
      ;; digit — byte string of length N
      (let [sb (StringBuilder.)]
        (.append sb c)
        (loop [ch (read-char in)]
          (if (= ch \:)
            (let [len (Long/parseLong (.toString sb))
                  buf (byte-array len)]
              (loop [read 0]
                (when (< read len)
                  (let [n (.read in buf read (- len read))]
                    (when-not (pos? n) (throw (ex-info "EOF in string body" {})))
                    (recur (+ read n)))))
              (String. buf "UTF-8"))
            (do (.append sb ch) (recur (read-char in)))))))))
