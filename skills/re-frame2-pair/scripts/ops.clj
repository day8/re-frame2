#!/usr/bin/env bb
;;;; scripts/ops.clj — babashka entry point for the re-frame2-pair bash-shim
;;;; transport.
;;;;
;;;; Role
;;;; ----
;;;; This file is the dispatcher behind the six `scripts/*.sh` shims
;;;; (`discover-app.sh`, `eval-cljs.sh`, `dispatch.sh`, `trace-window.sh`,
;;;; `watch-epochs.sh`, `tail-build.sh`). Each shell wrapper is a one-liner
;;;; that exec's `bb ops.clj <subcommand> [args...]` and forwards the edn
;;;; printed on stdout.
;;;;
;;;; Transport status — the bash-shim is **retired from the skill's tool
;;;; surface** (the `allowed-tools:` frontmatter carries no shell tool, so
;;;; an agent cannot run it). It is NOT a skill-facing fallback transport.
;;;; The MCP server in `tools/re-frame2-pair-mcp/` is the only skill-facing
;;;; transport; it holds a single nREPL connection per session (~14× faster
;;;; than the per-call connect/disconnect this file performs). These shims
;;;; remain on disk only for the project's own test harness (`tests/shim/`,
;;;; `tests/e2e/`) and ad-hoc shell use OUTSIDE the skill. Op semantics are
;;;; identical between the shim and the MCP surface — see
;;;; `skills/re-frame2-pair/references/ops.md` for the full op catalogue
;;;; (MCP form in the Invocation column; the shim mapping in the harness
;;;; appendix) and `references/mcp-transport.md` for the MCP surface.
;;;;
;;;; Subcommands (each maps 1:1 to a shim under `scripts/`)
;;;; -----------------------------------------------------
;;;;   discover     — locate shadow-cljs nREPL, verify prerequisites,
;;;;                  probe for the preloaded re-frame2-pair.runtime
;;;;                  namespace, report {:ok? ...}
;;;;   eval         — cljs-eval a form, return edn result
;;;;   dispatch     — fire an event with :origin :pair; --sync / --trace
;;;;                  variants, --frame / --fx-override flags
;;;;   trace-recent — epochs added in the last N ms (operating frame)
;;;;   watch        — pull-mode live streaming of matching epochs
;;;;   tail-build   — wait for hot-reload to land; probe-form gated
;;;;
;;;; File layout (top→bottom)
;;;; ------------------------
;;;;   1. nREPL socket client (bencode codec shared from scripts/bencode.clj,
;;;;      no Maven dep)
;;;;   2. Config / env (build-id, port-file discovery)
;;;;   3. Output helpers (`emit`, `die`)
;;;;   4. nREPL conveniences (`jvm-eval`, `cljs-eval`, `cljs-eval-value`)
;;;;   5. One section per subcommand (`discover`, `eval`, `dispatch`,
;;;;      `trace-recent`, `watch`, `tail-build`)
;;;;   6. `-main` dispatcher
;;;;
;;;; When to add a new helper here vs. elsewhere
;;;; -------------------------------------------
;;;; - **New op shared across MCP + bash-shim**: add the underlying call
;;;;   to `re-frame2-pair.runtime` (in `preload/`), wire it into the MCP
;;;;   tool surface (`tools/re-frame2-pair-mcp/src/`), THEN add a thin subcommand
;;;;   here that calls it via `cljs-eval-value`. Document in
;;;;   `references/ops.md`. The runtime is the source of truth.
;;;; - **Bash-only flag tweak** (new `--foo` on an existing subcommand):
;;;;   inline it here, update the `references/ops.md` harness appendix.
;;;; - **Anything else** (new transport, new wire format, new shim):
;;;;   probably belongs in `tools/re-frame2-pair-mcp/` rather than another shim.
;;;;
;;;; Runtime preload requirement
;;;; ---------------------------
;;;; The runtime is no longer injected at first connect — it ships into
;;;; the consumer app via shadow-cljs's `:devtools :preloads` mechanism.
;;;; See `skills/re-frame2-pair/SKILL.md` (§Setup) for the one-line
;;;; preload entry. `discover` refuses with `:reason :runtime-not-preloaded`
;;;; when the namespace isn't present.
;;;;
;;;; All ops return edn on stdout. Shells capture and forward.

;; The minimal bencode codec is shared with the test stub
;; (tests/shim/stub_nrepl.clj); it lives in scripts/bencode.clj and is
;; loaded off this file's own location so cwd doesn't matter (rf2-qq7w2k).
(load-file (str (.getParent (java.io.File. *file*)) "/bencode.clj"))

(ns ops
  (:require [bencode :as bc]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net Socket)
           (java.io PushbackInputStream)))

;; ---------------------------------------------------------------------------
;; nREPL socket client (bencode codec in scripts/bencode.clj)
;; ---------------------------------------------------------------------------

(defn- nrepl-eval-raw
  "Open a socket to nREPL at port, send an op eval of code-str, read
   responses until :status contains \"done\", close, return responses."
  [port code-str]
  (with-open [sock (Socket. "127.0.0.1" (int port))]
    (let [out (.getOutputStream sock)
          in  (PushbackInputStream. (.getInputStream sock))
          id  (str (random-uuid))
          msg (bc/encode {"op" "eval" "code" code-str "id" id})]
      (.write out (.getBytes ^String msg "UTF-8"))
      (.flush out)
      (loop [responses []]
        (let [resp (bc/decode in)
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

;; Relative port-file locations shadow-cljs may write to. We probe each
;; both at the shell CWD *and* under an `implementation/` subdir, because
;; the canonical re-frame2 dev build runs from `implementation/` while a
;; shell often sits at the repo root. A stale repo-root
;; `.shadow-cljs/nrepl.port` from an old run must not shadow the live
;; `implementation/.shadow-cljs/nrepl.port`.
(def port-file-rel-candidates
  ["target/shadow-cljs/nrepl.port"
   ".shadow-cljs/nrepl.port"
   ".nrepl-port"])

;; Subdir prefixes searched relative to CWD, "" meaning CWD itself.
;; `implementation/` is the re-frame2 reference-build root.
(def port-file-dir-prefixes
  ["" "implementation/"])

(defn- candidate-port-files
  "Every existing port file reachable from CWD across the rel-candidate ×
   dir-prefix matrix, as a seq of java.io.File."
  []
  (for [prefix port-file-dir-prefixes
        rel    port-file-rel-candidates
        :let   [f (io/file (str prefix rel))]
        :when  (.exists f)]
    f))

(defn- read-port
  "Resolve the live shadow-cljs nREPL port. Resolution order:

     1. $SHADOW_CLJS_NREPL_PORT  — explicit, CWD-independent override.
        Set this to bypass file discovery entirely (e.g. when running
        the shim from an unusual CWD, or to pin a specific build).
     2. Otherwise scan every candidate port file (the standard
        shadow-cljs locations, probed at both CWD and under
        `implementation/`) and pick the **most-recently-modified** one.

   Picking by mtime — rather than first-in-list — is what makes this
   robust: the live dev build rewrites its port file on every (re)start,
   so the freshest file is the live one. A stale repo-root
   `.shadow-cljs/nrepl.port` left over from an earlier run can no longer
   shadow the live `implementation/.shadow-cljs/nrepl.port`."
  []
  (or (when-let [p (System/getenv "SHADOW_CLJS_NREPL_PORT")]
        (Integer/parseInt p))
      (when-let [f (->> (candidate-port-files)
                        (sort-by (fn [^java.io.File f] (.lastModified f)) >)
                        first)]
        (Integer/parseInt (str/trim (slurp f))))))

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
          ;; rf2-n58jxo (mirrors the MCP-side rf2-mvdwv fix in nrepl.cljs) —
          ;; a populated `:err` on shadow's outer response is an analyzer
          ;; warning (unresolved symbol) or a compile error. It MUST surface
          ;; even though shadow ALSO pushes a "nil" into `:results`: peeking
          ;; `:results` FIRST (the pre-fix order) swallowed the compile
          ;; failure as a silent nil — the CLI eval reported success-shaped
          ;; while the form never produced a real value. The `:err` branch
          ;; now takes precedence so a compile error/warning is DISTINCT from
          ;; a runtime throw (`:eval-error`, the `:ex` path above).
          (and (map? outer) (not (str/blank? (str (:err outer)))))
          (throw (ex-info "cljs eval compile error/warning"
                          {:reason :cljs-eval-error
                           :err (str/trim (str (:err outer)))}))

          (and (map? outer) (vector? (:results outer)))
          (when-let [last-result (peek (:results outer))]
            (safe-edn last-result))

          :else outer)))))

;; ---------------------------------------------------------------------------
;; Subcommand: discover
;; ---------------------------------------------------------------------------

(defn- ensure-port! []
  (or (read-port)
      (die :nrepl-port-not-found
           :hint "Start your shadow-cljs dev build (`shadow-cljs watch <build>`).")))

(defn- build-id-from-args [args]
  ;; --build=:app -or- --build=app — both yield :app. We strip a leading
  ;; colon before `keyword` so the documented `:app` form doesn't produce
  ;; the malformed `::app` (a namespaced keyword in the `user` ns) and
  ;; probe a build that doesn't exist (false :runtime-not-preloaded).
  (or (some-> (some #(when (str/starts-with? % "--build=") %) args)
              (str/replace-first "--build=" "")
              (str/replace-first #"^:" "")
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

(def ^:private preload-missing-hint
  (str "re-frame2-pair.runtime is not loaded into this build. Add the "
       "preload entry to your shadow-cljs.edn: "
       ":builds {:app {:devtools {:preloads [re-frame2-pair.runtime]}}}, "
       "and make sure the directory containing re_frame2_pair/runtime.cljs "
       "is on :source-paths. See skills/re-frame2-pair/SKILL.md (§Setup)."))

(defn- runtime-preloaded?
  "Probe `js/globalThis.__re_frame2_pair_runtime` — the load-time
   sentinel set by the preloaded re-frame2-pair.runtime namespace.
   One round-trip, no CLJS compile."
  [build-id]
  (try
    (let [v (cljs-eval-value
              build-id
              "(some? (and (exists? js/globalThis) (.-__re_frame2_pair_runtime js/globalThis)))")]
      (true? v))
    (catch Exception _ false)))

(defn- runtime-health!
  "Call `(re-frame2-pair.runtime/health)`. Caller must have already
   confirmed the preload landed via `runtime-preloaded?` — this is the
   second round-trip, returning the structured health summary."
  [build-id]
  (cljs-eval-value build-id "(re-frame2-pair.runtime/health)"))

;; ---------------------------------------------------------------------------
;; Running-build enumeration + fail-loud build resolution (rf2-ivlb3).
;;
;; Mirrors `tools/re-frame2-pair-mcp/src/.../tools/probe.cljs`'s
;; `running-builds` / `resolve-build!` / `resolve-and-preflight!`. Both
;; transports MUST fail loud: eval'ing against a build with no live
;; re-frame2-pair runtime returns a blank cljs-eval value that
;; `cljs-eval-value` reads as nil — indistinguishable from a genuine
;; nil. The preflight + auto-detect removes the footgun on both paths.
;; ---------------------------------------------------------------------------

(defn- running-builds
  "Enumerate shadow-cljs build ids with a live watch worker, as a vector
   of keywords. JVM-side call — `active-builds` lives on the shadow API,
   not in the CLJS heap, so it works even when no build has the runtime
   preloaded (that's the point: it tells the operator which builds ARE
   running). Returns [] on any error (old shadow, socket hiccup)."
  []
  (try
    (let [res (jvm-eval "(try (vec (shadow.cljs.devtools.api/active-builds)) (catch Throwable _ []))")
          v   (some-> (:value res) safe-edn)]
      (if (vector? v) v []))
    (catch Exception _ [])))

(defn- auto-detect-hint [running]
  (cond
    (empty? running)
    (str "no shadow-cljs build is running. Start your dev build "
         "(`shadow-cljs watch <build>`) before eval'ing.")

    (= 1 (count running))
    (str "pass --build=" (first running) " or set SHADOW_CLJS_BUILD_ID.")

    :else
    (str "multiple shadow-cljs builds are running " (vec running)
         "; pass --build=<one-of-them> or set SHADOW_CLJS_BUILD_ID.")))

(defn- resolve-build!
  "Resolve the build to eval against, or throw a structured ex-info.

     - explicit `--build=` / SHADOW_CLJS_BUILD_ID  ⇒ honour it verbatim
       (preflight catches a typo / non-running build).
     - exactly one running build                   ⇒ auto-detect it.
     - zero or many running                        ⇒ throw
       `:no-runtime-for-build` enumerating `:running-builds`.

   `explicit?` is true when `build` came from a real `--build=` / env
   value rather than the bare `:app` fallback — we auto-detect ONLY
   when the build is the bare default (`explicit?` false)."
  [build explicit?]
  (if (and build explicit?)
    build
    (let [running (running-builds)]
      (if (= 1 (count running))
        (first running)
        (throw (ex-info "cannot auto-detect a single running build"
                        {:reason         :no-runtime-for-build
                         :build          (when explicit? build)
                         :running-builds running
                         :hint           (str (auto-detect-hint running)
                                              " The default 'app' build is not running.")}))))))

(defn- resolve-and-preflight!
  "Resolve the build then confirm a live re-frame2-pair runtime sentinel
   for it. Returns the resolved build-id; throws a structured ex-info
   (`:reason :no-runtime-for-build`, carrying `:running-builds`) when the
   build can't be eval'd. NEVER returns for a runtime-absent build, so
   the caller can't emit `:ok? true :value nil` for an eval that didn't
   run."
  [build explicit?]
  (let [build-id (resolve-build! build explicit?)]
    (if (runtime-preloaded? build-id)
      build-id
      (let [running (running-builds)]
        (throw (ex-info "no re-frame2-pair runtime for build"
                        {:reason         :no-runtime-for-build
                         :build          build-id
                         :running-builds running
                         :hint           (str "build " build-id " has no live "
                                              "re-frame2-pair runtime. "
                                              (auto-detect-hint running)
                                              " (Or add the :devtools :preloads "
                                              "[re-frame2-pair.runtime] entry — see "
                                              "skills/re-frame2-pair/SKILL.md §Setup.)")}))))))

(defn- discover [args]
  (ensure-port!)
  (let [build-id (build-id-from-args args)]
    (try
      (if-not (runtime-preloaded? build-id)
        (emit {:ok? false :reason :runtime-not-preloaded :hint preload-missing-hint})
        (let [health (runtime-health! build-id)]
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
                 :hint "No frame is registered yet. The app must reg-frame its app frame and establish it at the root (rf/init! creates no frame under EP-0002), or wait for app boot."})

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
                                         "DOM->source ops will degrade. re-frame2 "
                                         "stamps data-rf2-source-coord on registered-view "
                                         "roots automatically in debug builds (no "
                                         "configure! opt-in) — check registered-view "
                                         "coverage, a DOM-capable adapter, and the debug "
                                         "build (goog.DEBUG), or use re-com with :src (at).")))

          :else
          (emit (assoc health :ok? true :build-id build-id)))))
      (catch Exception e
        (emit {:ok? false
               :reason (or (:reason (ex-data e)) :unknown)
               :message (.getMessage e)})))))

;; ---------------------------------------------------------------------------
;; Subcommand: eval
;; ---------------------------------------------------------------------------

(defn- explicit-build?
  "True iff the caller passed an explicit `--build=` flag or set
   $SHADOW_CLJS_BUILD_ID — vs. relying on the bare `:app` fallback in
   `default-build-id`. The eval-path resolver (rf2-ivlb3) auto-detects
   the running build ONLY when the build is the bare default."
  [args]
  (boolean (or (some #(str/starts-with? % "--build=") args)
               (System/getenv "SHADOW_CLJS_BUILD_ID"))))

(defn- eval-op [args]
  (ensure-port!)
  (when (empty? args) (die :missing-form :hint "usage: eval '<form>' [--build=app]"))
  (let [form      (first args)
        rest-args (rest args)
        build-id  (build-id-from-args rest-args)
        explicit? (explicit-build? rest-args)]
    (try
      ;; rf2-ivlb3: resolve the build (auto-detect the single running one
      ;; when no explicit --build was passed) and confirm a live runtime
      ;; BEFORE eval'ing. Never emit `:ok? true :value nil` for a build
      ;; with no runtime — that's indistinguishable from a genuine nil.
      (let [resolved (resolve-and-preflight! build-id explicit?)]
        (emit {:ok? true :value (cljs-eval-value resolved form) :build resolved}))
      (catch Exception e
        (let [data (ex-data e)]
          (if (= :no-runtime-for-build (:reason data))
            (emit (merge {:ok? false} data))
            (emit {:ok? false
                   :reason (or (:reason data) :eval-error)
                   :message (.getMessage e)
                   :data (dissoc (or data {}) :reason)})))))))

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
                       (format "(re-frame2-pair.runtime/dispatch-and-collect %s %s)"
                               event-str (pr-str opts-form)))))

        sync?
        (emit (merge {:mode :sync}
                     (cljs-eval-value
                       build-id
                       (format "(re-frame2-pair.runtime/pair-dispatch-sync! %s %s)"
                               event-str (pr-str opts-form)))))

        :else
        (emit (merge {:mode :queued}
                     (cljs-eval-value
                       build-id
                       (format "(re-frame2-pair.runtime/pair-dispatch! %s %s)"
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
                   (format "(re-frame2-pair.runtime/epochs-in-last-ms %d %s)" ms (pr-str frame))
                   (format "(re-frame2-pair.runtime/epochs-in-last-ms %d)" ms))
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

      ;; --custom (arbitrary CLJS predicate form) was advertised
      ;; in earlier docs but never implemented. Implementing it requires
      ;; designing the predicate-fn surface (security implications: the
      ;; transport would eval untrusted CLJS), so it is removed from the
      ;; documented surface for now. The flag is rejected with an
      ;; explanatory error rather than silently skipped, so any stale
      ;; caller surfaces clearly.
      (= a "--custom")
      (do
        (emit {:ok? false
               :reason :unsupported-flag
               :flag :--custom
               :hint (str "--custom (arbitrary CLJS predicate form) is "
                          "not supported. Use --event-id, --event-id-prefix, "
                          "--effects, --touches-path, --sub-ran, --render, "
                          "--origin, or --frame.")})
        (System/exit 1))

      :else                     (recur more pred))))

(defn- flag-value [args flag default]
  (if-let [idx (->> args (keep-indexed (fn [i v] (when (= v flag) i))) first)]
    (nth args (inc idx) default)
    default))

(defn- watch-op [args]
  (ensure-port!)
  (let [build-id     (build-id-from-args args)
        stream?      (has-flag? args "--stream")
        stop?        (has-flag? args "--stop")
        ;; --window-ms and --count are independent modes.
        ;;   --window-ms alone: run for N ms, no count limit.
        ;;   --count alone:     run until N matches, no window timeout.
        ;;   both set:          first to fire wins (race).
        ;;   neither set:       default to a 30s window (back-compat).
        window-raw   (flag-value args "--window-ms" nil)
        count-raw    (flag-value args "--count" nil)
        window-ms    (when window-raw (Long/parseLong window-raw))
        count-n      (when count-raw  (Long/parseLong count-raw))
        ;; If neither is set, fall back to the documented 30s window so
        ;; an argument-less `watch-epochs.sh` still terminates.
        window-ms    (if (and (nil? window-ms) (nil? count-n) (not stream?))
                       30000
                       window-ms)
        frame        (frame-from-args args)
        pred         (parse-predicate-args args)
        idle-ms      (Long/parseLong (flag-value args "--idle-ms" "30000"))
        hard-ms      (Long/parseLong (flag-value args "--hard-ms" "300000"))
        poll-ms      (Long/parseLong (flag-value args "--poll-ms" "100"))
        frame-arg    (if frame (str " " (pr-str frame)) "")]
    (cond
      stop?
      (emit {:ok? true :stopped? true
             :note "watch/stop is a no-op in pull-mode; simply terminate the running watch shell."})

      :else
      (try
        (let [start     (System/currentTimeMillis)
              last-id   (atom (cljs-eval-value
                                build-id
                                (format "(some-> (re-frame2-pair.runtime/last-epoch%s) :epoch-id)"
                                        frame-arg)))
              emitted   (atom 0)
              last-hit  (atom (System/currentTimeMillis))
              done?     (fn []
                          (let [now      (System/currentTimeMillis)
                                elapsed  (- now start)
                                idle     (- now @last-hit)]
                            (cond
                              ;; window-ms is only checked when the user
                              ;; (or the default fallback) actually set it.
                              (and (not stream?) window-ms
                                   (>= elapsed window-ms))                 [:done :window]
                              ;; count-n is only checked when the user
                              ;; actually set --count.
                              (and (not stream?) count-n
                                   (>= @emitted count-n))                  [:done :count]
                              (>= elapsed hard-ms)                         [:done :hard-cap]
                              (and stream? (>= idle idle-ms))              [:done :idle]
                              :else nil)))
              fetch-form (fn [since-id]
                           (format
                            "(let [r (re-frame2-pair.runtime/epochs-since %s%s)
                                   matches (filterv #(re-frame2-pair.runtime/epoch-matches? %s %%) (:epochs r))]
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
                                  "(rf/configure! {:epoch-history {:depth N}}).")}))
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
    "dispatch"     (dispatch-op (rest args))
    "trace-recent" (trace-recent-op (rest args))
    "watch"        (watch-op (rest args))
    "tail-build"   (tail-build-op (rest args))
    (die :unknown-subcommand :arg (first args)
         :valid #{"discover" "eval" "dispatch" "trace-recent" "watch" "tail-build"})))

(apply -main *command-line-args*)
