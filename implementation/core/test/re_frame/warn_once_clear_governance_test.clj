(ns re-frame.warn-once-clear-governance-test
  "JVM source-enumeration half of the warn-once-clear governance gate
  (rf2-z79p8). The CLJS half
  (`re-frame.warn-once-clear-governance-cljs-test`) proves, at runtime,
  that firing the canonical `:adapter/clear-warn-once-caches!` chain wipes
  every cache enrolled in the `warn-once-clear-registry`. This half proves,
  at the source level, that there is exactly ONE way to enrol a cache into
  that chain — the chokepoint `register-warn-once-clear-fn!` — so a future
  5th cache cannot quietly chain itself with a bare `chain-fn!` (which
  would chain it WITHOUT recording it in the registry, re-opening the
  rf2-4edk/9hoos/qy6cl/z79p8 defect class the CLJS gate can no longer see).

  Two assertions:

    1. SINGLE CHOKEPOINT — no source file other than `re-frame.late-bind`
       itself (which DEFINES the chokepoint) may call
       `(chain-fn! :adapter/clear-warn-once-caches! ...)` directly. Every
       contributor goes through `register-warn-once-clear-fn!` (core/views)
       or `install-clear-warn-once-step!` (the spine/adapter seam, itself a
       thin delegator). This guarantees enrolment-and-chaining are atomic:
       you cannot chain without registering.

    2. ENROLMENT COVERAGE — every standalone warn-once clear-fn the
       adapter/views family publishes as a `clear-*warned*!`-shaped
       late-bind hook (the historical straggler shape — a `defonce` cache
       with a hand-published clear-fn) is routed through the chokepoint
       somewhere in source.

  Walks the same source tree as `re-frame.late-bind-drift-test`.")

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing]])

(def ^:private repo-implementation-root
  (-> (io/file "..") .getCanonicalFile))

(defn- source-files
  "Every `.clj{,c,s}` under `implementation/**/src/` (skips `test/`)."
  []
  (->> (file-seq repo-implementation-root)
       (filter #(.isFile ^java.io.File %))
       (filter (fn [^java.io.File f]
                 (let [n (.getName f)]
                   (or (str/ends-with? n ".clj")
                       (str/ends-with? n ".cljc")
                       (str/ends-with? n ".cljs")))))
       (filter (fn [^java.io.File f]
                 (let [norm (str/replace (.getPath f) "\\" "/")]
                   (and (str/includes? norm "/src/")
                        (not (str/includes? norm "/test/"))))))))

(defn- ns-name-of
  "Best-effort `(ns ...)` symbol of a source file (first ns form)."
  [content]
  (when-let [m (re-find #"\(ns\s+([a-zA-Z][a-zA-Z0-9.\-]*)" content)]
    (second m)))

;; ---------------------------------------------------------------------------
;; 1. Single chokepoint — only re-frame.late-bind may chain the key directly
;; ---------------------------------------------------------------------------

(def ^:private raw-chain-re
  "Match a direct `chain-fn!` (qualified or not) on the warn-once-clear
  key. The chokepoint `register-warn-once-clear-fn!` (in re-frame.late-bind)
  is the ONLY legitimate such call site."
  #"\((?:late-bind/)?chain-fn!\s+:adapter/clear-warn-once-caches!")

(deftest only-the-chokepoint-chains-the-warn-once-clear-key
  (testing "no source file other than re-frame.late-bind calls
            (chain-fn! :adapter/clear-warn-once-caches! ...) directly —
            every contributor enrols through register-warn-once-clear-fn!
            so chaining and registry-enrolment are atomic (rf2-z79p8)"
    (let [offenders
          (for [^java.io.File f (source-files)
                :let [content (slurp f)]
                :when (re-find raw-chain-re content)
                :let [ns-sym (ns-name-of content)]
                :when (not= "re-frame.late-bind" ns-sym)]
            (str ns-sym " (" (.getPath f) ")"))]
      (is (empty? offenders)
          (str "These source files chain :adapter/clear-warn-once-caches! "
               "with a RAW chain-fn! instead of the canonical chokepoint "
               "re-frame.late-bind/register-warn-once-clear-fn! (or the "
               "spine seam install-clear-warn-once-step!). A raw chain-fn! "
               "wires the cache into the fixture chain WITHOUT recording it "
               "in the warn-once-clear-registry, so the CLJS governance "
               "assertion can no longer see it — re-opening the "
               "rf2-4edk/9hoos/qy6cl/z79p8 defect class. Route through the "
               "chokepoint:\n  "
               (str/join "\n  " (sort offenders)))))))

;; ---------------------------------------------------------------------------
;; 2. Enrolment coverage — every published clear-*warned* hook is chokepointed
;; ---------------------------------------------------------------------------

(def ^:private clear-warned-hook-re
  "Match a late-bind publication of a `clear-…warned…`-shaped clear-fn —
  the historical straggler shape (a standalone hand-published clear-fn for
  a warn-once cache). e.g. `(late-bind/set-fn! :views/clear-plain-fn-warned-pairs! …`."
  #"\(late-bind/set-fn!\s+(:[a-zA-Z][a-zA-Z0-9.!?*+\-]*/clear-[a-zA-Z0-9!?*+\-]*warned[a-zA-Z0-9!?*+\-]*)")

(defn- published-clear-warned-hooks
  "Map of `clear-*warned*` hook-key → producing-ns-sym across all source."
  []
  (into {}
        (for [^java.io.File f (source-files)
              :let [content (slurp f)
                    ns-sym  (ns-name-of content)]
              k (->> (re-seq clear-warned-hook-re content)
                     (map (comp keyword #(subs % 1) second)))]
          [k ns-sym])))

(defn- files-routing-through-chokepoint
  "Source whose content references the chokepoint or its spine seam by the
  name of a clear-fn — used as a coarse 'this cache is wired in' signal."
  []
  (->> (source-files)
       (map (fn [^java.io.File f] [(.getPath f) (slurp f)]))
       (into {})))

(deftest every-clear-warned-hook-is-routed-through-the-chokepoint
  (testing "every standalone clear-*warned* warn-once clear-fn the
            adapter/views family publishes is also wired into the chain via
            the chokepoint (rf2-z79p8) — so it is enrolled in the registry
            and the CLJS gate covers it"
    (let [hooks      (published-clear-warned-hooks)
          all-source (vals (files-routing-through-chokepoint))
          ;; A clear-fn is 'wired' if SOME source file enrols it through
          ;; the chokepoint or the spine seam. We match on the unqualified
          ;; fn name (the symbol after the last `/` in the hook key, minus
          ;; the `clear-`/`!` decoration is too lossy — instead match the
          ;; fn-name token the clear-fn is defined under, which by
          ;; convention is the hook key's local name).
          enrol-tokens ["register-warn-once-clear-fn!"
                        "install-clear-warn-once-step!"]
          chokepoint-source (filter (fn [s]
                                      (some #(str/includes? s %) enrol-tokens))
                                    all-source)]
      (is (seq hooks)
          "sanity: at least one clear-*warned* hook is published in source")
      (is (seq chokepoint-source)
          "sanity: the chokepoint / spine seam is referenced in source")
      ;; For each published clear-*warned* hook, its local fn name should
      ;; appear in a chokepoint-routing source file. The hook key's local
      ;; name mirrors the clear-fn symbol (e.g.
      ;; :views/clear-plain-fn-warned-pairs! ↔ clear-plain-fn-warned-pairs!).
      (let [unrouted
            (for [[hook-key _producer] hooks
                  :let [fn-name (name hook-key)]
                  :when (not (some #(str/includes? % fn-name) chokepoint-source))]
              hook-key)]
        (is (empty? unrouted)
            (str "These clear-*warned* warn-once clear-fns are published as "
                 "standalone late-bind hooks but their fn-name is NOT "
                 "referenced at any chokepoint (register-warn-once-clear-fn! "
                 "/ install-clear-warn-once-step!) call site — they may be "
                 "stragglers NOT wired into the fixture chain (the "
                 "rf2-z79p8 defect class):\n  "
                 (str/join "\n  " (sort (map str unrouted)))))))))
