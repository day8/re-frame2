#!/usr/bin/env bb
;;;; scripts/ops.clj — babashka entry point for all re-frame-pair2 ops.
;;;;
;;;; Each `scripts/*.sh` is a thin wrapper that exec's:
;;;;     bb ops.clj <subcommand> [args...]
;;;;
;;;; Subcommands:
;;;;   discover     — locate shadow-cljs nREPL, verify prerequisites,
;;;;                  inject runtime, report {:ok? ...}
;;;;   eval         — cljs-eval a form, return edn result
;;;;   inject       — (re-)inject re-frame-pair2.runtime
;;;;   dispatch     — fire an event with :origin :pair; --sync / --trace
;;;;                  variants, --frame / --fx-override flags
;;;;   trace-recent — epochs added in the last N ms (operating frame)
;;;;   watch        — pull-mode live streaming of matching epochs
;;;;   tail-build   — wait for hot-reload to land; probe-form gated
;;;;
;;;; All ops return edn on stdout. Shells capture and forward.

(ns ops
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net Socket)
           (java.io PushbackInputStream)))

;; ---------------------------------------------------------------------------
;; Minimal bencode + nREPL socket client
;; ---------------------------------------------------------------------------
;;
;; bb doesn't ship a built-in nREPL client and we don't want a classpath
;; dep just for this. Bencode is a 40-line protocol and nREPL speaks it
;; directly over TCP; inline is simpler than bolting on Maven deps.

(defn- bencode ^String [v]
  (cond
    (integer? v)   (str "i" v "e")
    (string? v)    (let [bs (.getBytes ^String v "UTF-8")]
                     (str (alength bs) ":" v))
    (keyword? v)   (bencode (name v))
    (map? v)       (str "d"
                        (apply str (mapcat (fn [[k v]] [(bencode k) (bencode v)])
                                           (sort-by (fn [[k _]] (if (keyword? k) (name k) (str k))) v)))
                        "e")
    (sequential? v) (str "l" (apply str (map bencode v)) "e")
    (nil? v)       (bencode "")
    :else          (bencode (pr-str v))))

(defn- read-char [^PushbackInputStream in]
  (let [b (.read in)]
    (when (neg? b) (throw (ex-info "unexpected EOF" {})))
    (char b)))

(defn- bdecode [^PushbackInputStream in]
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
                   :else             (do (.unread in b) (recur (conj acc (bdecode in)))))))
      \d (loop [acc {}]
           (let [b (.read in)]
             (cond (neg? b)          (throw (ex-info "unexpected EOF in dict" {}))
                   (= b (int \e))    acc
                   :else             (do (.unread in b)
                                         (let [k (bdecode in)
                                               v (bdecode in)]
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

(defn- nrepl-eval-raw
  "Open a socket to nREPL at port, send an op eval of code-str, read
   responses until :status contains \"done\", close, return responses."
  [port code-str]
  (with-open [sock (Socket. "127.0.0.1" (int port))]
    (let [out (.getOutputStream sock)
          in  (PushbackInputStream. (.getInputStream sock))
          id  (str (random-uuid))
          msg (bencode {"op" "eval" "code" code-str "id" id})]
      (.write out (.getBytes ^String msg "UTF-8"))
      (.flush out)
      (loop [responses []]
        (let [resp (bdecode in)
              responses' (conj responses resp)
              done? (and (= id (get resp "id"))
                         (some #{"done"} (get resp "status" [])))]
          (if done?
            responses'
            (recur responses')))))))

(defn- combine-responses
  "Collapse a sequence of nREPL responses into a single result map."
  [responses]
  (reduce (fn [acc r]
            (cond-> acc
              (contains? r "value") (assoc :value (get r "value"))
              (contains? r "out")   (update :out str (get r "out"))
              (contains? r "err")   (update :err str (get r "err"))
              (contains? r "ex")    (assoc :ex (get r "ex"))
              (contains? r "status") (update :status (fnil into #{}) (get r "status"))))
          {:out "" :err ""}
          responses))

;; ---------------------------------------------------------------------------
;; Config / env
;; ---------------------------------------------------------------------------

(def default-build-id
  (keyword (or (System/getenv "SHADOW_CLJS_BUILD_ID") "app")))

(def port-file-candidates
  ["target/shadow-cljs/nrepl.port"
   ".shadow-cljs/nrepl.port"
   ".nrepl-port"])

(defn- read-port []
  (or (when-let [p (System/getenv "SHADOW_CLJS_NREPL_PORT")]
        (Integer/parseInt p))
      (some (fn [path]
              (let [f (io/file path)]
                (when (.exists f)
                  (Integer/parseInt (str/trim (slurp f))))))
            port-file-candidates)))

(defn- runtime-cljs-path []
  (.getPath (io/file (.getParent (io/file *file*)) "runtime.cljs")))

;; ---------------------------------------------------------------------------
;; Output helpers
;; ---------------------------------------------------------------------------

(defn- emit [m]
  (binding [*out* *out*]
    (pr m)
    (println))
  (flush))

(defn- die [reason & {:as extra}]
  (emit (merge {:ok? false :reason reason} extra))
  (System/exit 1))

;; ---------------------------------------------------------------------------
;; nREPL conveniences
;; ---------------------------------------------------------------------------

(defn- jvm-eval
  "Evaluate a Clojure (JVM-side) form over nREPL and return the combined
   response map."
  [form-str]
  (let [port (or (read-port)
                 (throw (ex-info "nREPL port not found" {:reason :nrepl-port-not-found})))]
    (combine-responses (nrepl-eval-raw port form-str))))

(defn- cljs-eval
  "Evaluate a ClojureScript form in the connected browser runtime via
   shadow-cljs's `cljs-eval` API. Returns the raw nREPL response."
  [build-id form-str]
  (let [wrapped (format "(shadow.cljs.devtools.api/cljs-eval %s %s {})"
                        (pr-str build-id)
                        (pr-str form-str))]
    (jvm-eval wrapped)))

(defn- safe-edn [s]
  (try (edn/read-string s) (catch Exception _ s)))

(defn- cljs-eval-value
  "Like cljs-eval but unwraps to the actual CLJS value."
  [build-id form-str]
  (let [res (cljs-eval build-id form-str)]
    (cond
      (some? (:ex res))
      (throw (ex-info "nREPL eval error" {:reason :eval-error
                                          :ex (:ex res)
                                          :err (:err res)}))

      (str/blank? (str (:value res)))
      nil

      :else
      (let [outer (safe-edn (str (:value res)))]
        (cond
          (and (map? outer) (vector? (:results outer)))
          (when-let [last-result (peek (:results outer))]
            (safe-edn last-result))

          (and (map? outer) (:err outer))
          (throw (ex-info "cljs eval error"
                          {:reason :cljs-eval-error
                           :err (:err outer)}))

          :else outer)))))

;; ---------------------------------------------------------------------------
;; Subcommand: discover
;; ---------------------------------------------------------------------------

(defn- ensure-port! []
  (or (read-port)
      (die :nrepl-port-not-found
           :hint "Start your shadow-cljs dev build (`shadow-cljs watch <build>`).")))

(defn- build-id-from-args [args]
  (or (some-> (some #(when (str/starts-with? % "--build=") %) args)
              (str/replace-first "--build=" "")
              keyword)
      default-build-id))

(defn- frame-from-args [args]
  ;; --frame :foo  -or-  --frame=:foo
  (or (some-> (some #(when (str/starts-with? % "--frame=") %) args)
              (str/replace-first "--frame=" "")
              edn/read-string)
      (let [idx (->> args (keep-indexed (fn [i v] (when (= v "--frame") i))) first)]
        (when idx
          (some-> (nth args (inc idx) nil) edn/read-string)))))

(defn- runtime-already-injected?
  "Fast-path check: if the session sentinel var exists, the runtime is
   already injected in this browser runtime."
  [build-id]
  (try
    (let [v (cljs-eval-value build-id "re-frame-pair2.runtime/session-id")]
      (and (string? v) (seq v)))
    (catch Exception _ false)))

(defn- inject-runtime!
  "Ensure scripts/runtime.cljs is loaded in the connected runtime."
  [build-id]
  (when-not (runtime-already-injected? build-id)
    (let [source (slurp (runtime-cljs-path))]
      (cljs-eval build-id source)))
  (cljs-eval-value build-id "(re-frame-pair2.runtime/health)"))

(defn- discover [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)]
    (try
      (let [health (inject-runtime! build-id)]
        (cond
          (not (:ok? health))
          (emit health)

          (not (:debug-enabled? health))
          (emit {:ok? false :reason :debug-disabled
                 :hint (str "re-frame.interop/debug-enabled? is false. "
                            "This is a production build (or goog.DEBUG was forced "
                            "off). Trace and epoch surfaces are elided.")})

          (empty? (:frames health))
          (emit {:ok? false :reason :no-frames-registered
                 :hint "Call (rf/init!) to register :rf/default, or wait for app boot."})

          (:ambiguous-frame? health)
          (emit (assoc health :ok? true
                              :warning :ambiguous-frame
                              :note (str "Multiple frames registered: "
                                         (vec (:frames health))
                                         ". Mutating ops require --frame :foo "
                                         "or run `frames/select` first.")))

          (not (:coord-annotation-enabled? health))
          (emit (assoc health :ok? true
                              :warning :no-source-coord-annotation
                              :note (str "Neither data-rf2-source-coord nor "
                                         "data-rc-src is on any element. The "
                                         "DOM->source ops will degrade. Enable "
                                         "(rf/configure :source-coords {:annotate-dom? true}) "
                                         "or use re-com with :src (at).")))

          :else
          (emit (assoc health :ok? true :build-id build-id))))
      (catch Exception e
        (emit {:ok? false
               :reason (or (:reason (ex-data e)) :unknown)
               :message (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: eval
;; ---------------------------------------------------------------------------

(defn- eval-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-form :hint "usage: eval '<form>' [--build :app]"))
  (let [form     (first args)
        build-id (build-id-from-args (rest args))]
    (try
      (emit {:ok? true :value (cljs-eval-value build-id form)})
      (catch Exception e
        (emit {:ok? false
               :reason (or (:reason (ex-data e)) :eval-error)
               :message (.getMessage e)
               :data (dissoc (ex-data e) :reason)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: inject
;; ---------------------------------------------------------------------------

(defn- inject-runtime-force!
  "Like inject-runtime! but *always* re-ships scripts/runtime.cljs."
  [build-id]
  (let [source (slurp (runtime-cljs-path))]
    (cljs-eval build-id source))
  (cljs-eval-value build-id "(re-frame-pair2.runtime/health)"))

(defn- inject-op [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)]
    (try
      (emit (assoc (inject-runtime-force! build-id)
                   :build-id build-id
                   :forced? true
                   :note "Source re-shipped regardless of sentinel. Use this after editing scripts/runtime.cljs."))
      (catch Exception e
        (emit {:ok? false :reason :inject-failed :message (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: dispatch
;; ---------------------------------------------------------------------------

(defn- has-flag? [args flag]
  (boolean (some #(= % flag) args)))

(defn- fx-override-from-args
  "--fx-override :http=:stub-http  (repeatable)
   Returns a map {:http :stub-http ...} or nil."
  [args]
  (let [pairs (->> args
                   (keep-indexed (fn [i v] (when (= v "--fx-override") (nth args (inc i) nil))))
                   (keep (fn [s]
                           (when (string? s)
                             (let [[k v] (str/split s #"=" 2)]
                               (when (and k v)
                                 [(edn/read-string k) (edn/read-string v)]))))))]
    (when (seq pairs)
      (into {} pairs))))

(defn- dispatch-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-event :hint "usage: dispatch '[:ev/id args...]' [--sync] [--trace] [--frame :foo] [--fx-override :http=:stub-http]"))
  (let [event-str    (first args)
        rest-args    (rest args)
        build-id     (build-id-from-args rest-args)
        sync?        (has-flag? rest-args "--sync")
        trace?       (has-flag? rest-args "--trace")
        frame        (frame-from-args rest-args)
        fx-overrides (fx-override-from-args rest-args)
        opts-form    (cond-> {}
                       frame        (assoc :frame frame)
                       fx-overrides (assoc :fx-overrides fx-overrides))]
    (try
      (cond
        ;; --trace: dispatch-and-collect returns the epoch synchronously
        ;; once drain settles (run-to-completion guarantees this).
        trace?
        (emit (merge {:mode :trace}
                     (cljs-eval-value
                       build-id
                       (format "(re-frame-pair2.runtime/dispatch-and-collect %s %s)"
                               event-str (pr-str opts-form)))))

        sync?
        (emit (merge {:mode :sync}
                     (cljs-eval-value
                       build-id
                       (format "(re-frame-pair2.runtime/pair-dispatch-sync! %s %s)"
                               event-str (pr-str opts-form)))))

        :else
        (emit (merge {:mode :queued}
                     (cljs-eval-value
                       build-id
                       (format "(re-frame-pair2.runtime/pair-dispatch! %s %s)"
                               event-str (pr-str opts-form))))))
      (catch Exception e
        (emit {:ok? false :reason :dispatch-failed :message (.getMessage e)
               :ex-data (ex-data e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: trace-recent
;; ---------------------------------------------------------------------------

(defn- trace-recent-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-window :hint "usage: trace-recent <ms> [--frame :foo]"))
  (let [ms       (Integer/parseInt (first args))
        rest-args (rest args)
        build-id (build-id-from-args rest-args)
        frame    (frame-from-args rest-args)]
    (try
      (let [form (if frame
                   (format "(re-frame-pair2.runtime/epochs-in-last-ms %d %s)" ms (pr-str frame))
                   (format "(re-frame-pair2.runtime/epochs-in-last-ms %d)" ms))
            epochs (cljs-eval-value build-id form)]
        (emit {:ok? true :window-ms ms :count (count epochs) :epochs epochs}))
      (catch Exception e
        (emit {:ok? false :reason :trace-failed :message (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: watch (pull-mode)
;; ---------------------------------------------------------------------------

(defn- ->kw [s]
  (when s
    (if (str/starts-with? s ":")
      (keyword (subs s 1))
      (keyword s))))

(defn- parse-predicate-args [args]
  (loop [[a & more] args pred {}]
    (cond
      (nil? a) pred
      (= a "--event-id")        (recur (rest more) (assoc pred :event-id (->kw (first more))))
      (= a "--event-id-prefix") (recur (rest more)
                                       (let [raw (first more)]
                                         (assoc pred :event-id-prefix
                                                (if (str/starts-with? raw ":") raw (str ":" raw)))))
      (= a "--effects")         (recur (rest more) (assoc pred :effects (->kw (first more))))
      (= a "--touches-path")    (recur (rest more) (assoc pred :touches-path
                                                           (edn/read-string (first more))))
      (= a "--sub-ran")         (recur (rest more) (assoc pred :sub-ran (->kw (first more))))
      (= a "--render")          (recur (rest more) (assoc pred :render (first more)))
      (= a "--origin")          (recur (rest more) (assoc pred :origin (->kw (first more))))
      (= a "--frame")           (recur (rest more) (assoc pred :frame (->kw (first more))))

      (= a "--custom")
      (do
        (emit {:ok? false
               :reason :not-yet-supported
               :flag :--custom
               :hint (str "Arbitrary CLJS predicate filters are reserved "
                          "but not yet implemented. Use --event-id-prefix, "
                          "--effects, --touches-path, --sub-ran, --render, "
                          "--origin, or --frame instead.")})
        (System/exit 1))

      :else                     (recur more pred))))

(defn- flag-value [args flag default]
  (if-let [idx (->> args (keep-indexed (fn [i v] (when (= v flag) i))) first)]
    (nth args (inc idx) default)
    default))

(defn- watch-op [args]
  (ensure-port!)
  (let [build-id   (build-id-from-args args)
        stream?    (has-flag? args "--stream")
        stop?      (has-flag? args "--stop")
        window-ms  (Long/parseLong (flag-value args "--window-ms" "30000"))
        count-n    (Long/parseLong (flag-value args "--count" "5"))
        frame      (frame-from-args args)
        pred       (parse-predicate-args args)
        idle-ms    (Long/parseLong (flag-value args "--idle-ms" "30000"))
        hard-ms    (Long/parseLong (flag-value args "--hard-ms" "300000"))
        poll-ms    (Long/parseLong (flag-value args "--poll-ms" "100"))
        frame-arg  (if frame (str " " (pr-str frame)) "")]
    (cond
      stop?
      (emit {:ok? true :stopped? true
             :note "watch/stop is a no-op in pull-mode; simply terminate the running watch shell."})

      :else
      (try
        (let [start     (System/currentTimeMillis)
              last-id   (atom (cljs-eval-value
                                build-id
                                (format "(some-> (re-frame-pair2.runtime/last-epoch%s) :epoch-id)"
                                        frame-arg)))
              emitted   (atom 0)
              last-hit  (atom (System/currentTimeMillis))
              done?     (fn []
                          (let [now      (System/currentTimeMillis)
                                elapsed  (- now start)
                                idle     (- now @last-hit)]
                            (cond
                              (and (not stream?) (>= elapsed window-ms))     [:done :window]
                              (and (not stream?) (>= @emitted count-n))      [:done :count]
                              (>= elapsed hard-ms)                           [:done :hard-cap]
                              (and stream? (>= idle idle-ms))                [:done :idle]
                              :else nil)))
              fetch-form (fn [since-id]
                           (format
                            "(let [r (re-frame-pair2.runtime/epochs-since %s%s)
                                   matches (filterv #(re-frame-pair2.runtime/epoch-matches? %s %%) (:epochs r))]
                               {:matches matches
                                :id-aged-out? (:id-aged-out? r)
                                :head-id (:head-id r)})"
                            (pr-str since-id)
                            frame-arg
                            (pr-str pred)))
              aged-warned? (atom false)]
          (loop []
            (Thread/sleep poll-ms)
            (let [result    (try (cljs-eval-value build-id (fetch-form @last-id))
                                 (catch Exception _ nil))
                  matches   (or (:matches result) [])
                  head-id   (:head-id result)
                  aged-out? (:id-aged-out? result)]
              (when head-id (reset! last-id head-id))
              (when (and aged-out? (not @aged-warned?))
                (reset! aged-warned? true)
                (emit {:ok? true :warning :id-aged-out
                       :note (str "The id we were tracking fell off the frame's "
                                  "epoch-history ring between polls — some matching "
                                  "epochs may have been missed. Bump depth via "
                                  "(rf/configure :epoch-history {:depth N}).")}))
              (doseq [m matches]
                (swap! emitted inc)
                (reset! last-hit (System/currentTimeMillis))
                (emit {:ok? true :epoch m}))
              (if-let [[_ why] (done?)]
                (emit {:ok? true :finished? true :reason why :emitted @emitted})
                (recur)))))
        (catch Exception e
          (emit {:ok? false :reason :watch-failed :message (.getMessage e)}))))))

;; ---------------------------------------------------------------------------
;; Subcommand: tail-build (hot-reload/wait)
;; ---------------------------------------------------------------------------

(defn- tail-build-op [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)
        wait-ms  (Long/parseLong (flag-value args "--wait-ms" "5000"))
        probe    (flag-value args "--probe" nil)
        poll-ms  100]
    (cond
      (nil? probe)
      (do (Thread/sleep 300)
          (emit {:ok? true :t (System/currentTimeMillis) :soft? true
                 :note "No probe supplied; waited a 300ms fixed delay."}))

      :else
      (let [before (try (cljs-eval-value build-id probe) (catch Exception _ ::error))
            start  (System/currentTimeMillis)]
        (loop []
          (Thread/sleep poll-ms)
          (let [elapsed (- (System/currentTimeMillis) start)
                now     (try (cljs-eval-value build-id probe) (catch Exception _ ::error))]
            (cond
              (and (not= now ::error) (not= now before))
              (emit {:ok? true :t (System/currentTimeMillis) :soft? false})

              (>= elapsed wait-ms)
              (emit {:ok? false :reason :timed-out :timed-out? true
                     :note "Probe did not change within --wait-ms. Likely a compile error; check your dev build output."})

              :else
              (recur))))))))

;; ---------------------------------------------------------------------------
;; Dispatcher
;; ---------------------------------------------------------------------------

(defn -main [& args]
  (case (first args)
    "discover"     (discover (rest args))
    "eval"         (eval-op (rest args))
    "inject"       (inject-op (rest args))
    "dispatch"     (dispatch-op (rest args))
    "trace-recent" (trace-recent-op (rest args))
    "watch"        (watch-op (rest args))
    "tail-build"   (tail-build-op (rest args))
    (die :unknown-subcommand :arg (first args)
         :valid #{"discover" "eval" "inject" "dispatch" "trace-recent" "watch" "tail-build"})))

(apply -main *command-line-args*)
