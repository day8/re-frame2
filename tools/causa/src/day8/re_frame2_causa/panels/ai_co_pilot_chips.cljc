(ns day8.re-frame2-causa.panels.ai-co-pilot-chips
  "Structured chip parsing and resolution for streamed AI answers."
  #?(:clj  (:require [clojure.edn :as edn])
     :cljs (:require [cljs.reader :as edn])))

(def chip-key-set
  "Supported structured citation chip keys."
  #{:dispatch-id :path :epoch-number :handler-id})

(def chip-glyphs
  "Glyph per structured citation chip key."
  {:dispatch-id  "◆"
   :path         "▥"
   :epoch-number "⏵"
   :handler-id   "⚙"})

(def chip-targets
  "Allowlisted click target per chip key."
  {:dispatch-id  :rf.causa/select-dispatch-id
   :path         :rf.causa.copilot/open-path
   :epoch-number :rf.causa/select-epoch
   :handler-id   :rf.causa.copilot/open-handler})

(def ^:private chip-prefix "{:rf.copilot/chip")

(defn- balanced-brace-length
  "Return the length of the smallest balanced brace prefix in `s`."
  [s]
  (let [n (count s)]
    (loop [i 0
           depth 0
           in-str? false
           escaped? false]
      (cond
        (>= i n)
        nil

        in-str?
        (let [c (.charAt ^String s i)]
          (cond
            escaped? (recur (inc i) depth true false)
            (= c \\) (recur (inc i) depth true true)
            (= c \") (recur (inc i) depth false false)
            :else    (recur (inc i) depth true false)))

        :else
        (let [c (.charAt ^String s i)]
          (cond
            (= c \") (recur (inc i) depth true false)
            (or (= c \{) (= c \[) (= c \())
            (recur (inc i) (inc depth) false false)
            (or (= c \}) (= c \]) (= c \)))
            (let [depth' (dec depth)]
              (if (zero? depth')
                (inc i)
                (recur (inc i) depth' false false)))
            :else
            (recur (inc i) depth false false)))))))

(defn- read-chip-fragment
  "Read `{:rf.copilot/chip <key> <value>}` from the head of `s`."
  [s]
  (when-let [len (balanced-brace-length s)]
    (let [prefix  (subs s 0 len)
          rest-s  (subs s len)
          inner   (subs prefix 1 (dec (count prefix)))
          wrapped (str "[" inner "]")]
      (try
        (let [tokens #?(:clj  (edn/read-string wrapped)
                        :cljs (edn/read-string wrapped))]
          (when (and (vector? tokens)
                     (= 3 (count tokens))
                     (= :rf.copilot/chip (first tokens)))
            {:value {:rf.copilot/chip :rf.copilot/chip
                     (nth tokens 1)    (nth tokens 2)}
             :rest  rest-s}))
        (catch #?(:clj Exception :cljs :default) _
          nil)))))

(defn- looks-like-chip-prefix?
  [s idx]
  (when (< idx (count s))
    (let [head-len (count chip-prefix)
          tail     (subs s idx (min (+ idx head-len) (count s)))]
      (= chip-prefix tail))))

(defn parse-streamed-answer
  "Split streamed answer text into plain text and structured chip
  segments. Malformed or unsupported chips remain literal text."
  [text]
  (if-not (string? text)
    []
    (loop [idx 0
           buf []
           out []]
      (cond
        (>= idx (count text))
        (let [tail (apply str buf)]
          (cond-> out
            (seq tail) (conj {:kind :text :text tail})))

        (looks-like-chip-prefix? text idx)
        (let [fragment (subs text idx)
              parsed   (read-chip-fragment fragment)]
          (if parsed
            (let [chip-map (:value parsed)
                  entries (seq (dissoc chip-map :rf.copilot/chip))
                  [chip-key chip-value] (when entries (first entries))
                  consumed-len (- (count fragment) (count (:rest parsed)))
                  raw (subs fragment 0 consumed-len)
                  pre (apply str buf)
                  out' (cond-> out
                         (seq pre) (conj {:kind :text :text pre}))]
              (if (contains? chip-key-set chip-key)
                (recur (+ idx consumed-len)
                       []
                       (conj out'
                             {:kind     :chip
                              :chip-key chip-key
                              :value    chip-value
                              :raw      raw}))
                (recur (+ idx consumed-len)
                       []
                       (conj out' {:kind :text :text raw}))))
            (recur (inc idx)
                   (conj buf (subs text idx (inc idx)))
                   out)))

        :else
        (recur (inc idx)
               (conj buf (subs text idx (inc idx)))
               out)))))

(defn resolve-chip
  "Resolve a parsed chip into render metadata, or nil for unknown keys."
  [{:keys [chip-key value]}]
  (when (contains? chip-key-set chip-key)
    {:chip-key chip-key
     :value    value
     :glyph    (get chip-glyphs chip-key)
     :target   (get chip-targets chip-key)}))
