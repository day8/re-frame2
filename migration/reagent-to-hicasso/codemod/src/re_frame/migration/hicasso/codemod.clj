(ns re-frame.migration.hicasso.codemod
  "The Reagent `[:>]` → Hicasso migration codemod — the FIXER.

  A source-text tool that reads a consumer's Reagent `.cljs` namespaces and
  repairs the props dialect at every foreign crossing, so that a codebase
  whose `[:> …]` sites are about to be interpreted by Hicasso keeps
  behaving the way Reagent made it behave. It mints no declaration, hoists
  nothing, and edits no `ns` form. It writes whole files through
  rewrite-clj, skips any file it cannot parse, and emits a report.

  Designed at
  `docs/design/hicasso/studio/reagent-codemod-against-the-landed-escape.md`
  and built as amended by rf2-2rtt6.143. The HOIST — the
  declare-what-you-use-twice `defhost` hoist with dedupe — is DEMAND-GATED
  by the same ratification and is deliberately absent.

  ## Two passes over one plan

  The report addresses the INPUT file, so its line and column numbers are
  the ones a migrator greps for whether they ran a scan or a rewrite. That
  rules out reading positions off a tree being edited underneath us, so
  there are two passes:

  * [[analyse]] walks a POSITION-TRACKED zipper over the pristine tree and
    never edits, so every coordinate it reports is exact.
  * [[transform]] walks the same pristine tree as pure NODES, bottom-up
    inside each site, and never asks for a position.

  Both call [[rewrite/plan-site]] on the same nodes with the same context.
  There is one implementation of every decision and two consumers of it,
  which is the same discipline `front/slot.cljc` applies to the slot rule
  one level down."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [re-frame.migration.hicasso.report :as report]
            [re-frame.migration.hicasso.rewrite :as rw]
            [rewrite-clj.node :as n]
            [rewrite-clj.parser :as p]
            [rewrite-clj.zip :as z])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; `:adapt-def-site` (§4.5) — the one class that is about a whole file
;; ---------------------------------------------------------------------------

(defn- adapt-def?
  "Is this node `(def Foo (r/adapt-react-class X))`?"
  [nd ctx]
  (and (rw/list-node? nd)
       (= 'def (rw/sexpr-safe (rw/element-at nd 0)))
       (symbol? (rw/sexpr-safe (rw/element-at nd 1)))
       (some-> (rw/element-at nd 2) (rw/reagent-call? 'adapt-react-class ctx))))

(defn- hiccup-head-symbol
  "The head of `[Foo …]` when it is a bare symbol — a call site of a
  `def`ed adapted class, if that symbol names one."
  [nd]
  (when (= :vector (n/tag nd))
    (let [h (rw/element-at nd 0)]
      (when (and h (= :token (n/tag h)))
        (let [s (rw/sexpr-safe h)] (when (symbol? s) s))))))

(defn- adapt-def-entry
  "`(def Foo (r/adapt-react-class X))` cannot be rewritten by a fixer:
  rewriting the def changes every call site's conversion regime at once,
  including call sites in other namespaces this tool may never have been
  pointed at. The report names the def and the same-file call sites it
  found, and says plainly that the cross-namespace ones are invisible."
  [{:keys [sym line col form file]} call-sites]
  (let [here (sort (get call-sites sym))]
    {:class  :adapt-def-site
     :action :refused
     :file   file
     :line   line
     :col    col
     :form   form
     :head   (str sym)
     :detail {:def (str sym) :call-sites-in-this-file (vec here)}
     :note   (str "`(def " sym " (r/adapt-react-class …))` is left alone, and W5 cannot take it. "
                  "Rewriting the def would change the conversion regime of EVERY call site at "
                  "once, and this run found " (count here) " in this file"
                  (when (seq here) (str " (line" (when (> (count here) 1) "s") " "
                                        (str/join ", " here) ")"))
                  ". CALL SITES IN OTHER NAMESPACES ARE INVISIBLE TO THIS TOOL — it only saw the "
                  "files it was pointed at. Either convert it by hand, or inline the "
                  "`adapt-react-class` at each call site and re-run so W5 can take them "
                  "individually.")}))

;; ---------------------------------------------------------------------------
;; Pass 1 — analyse
;; ---------------------------------------------------------------------------

(defn- meta-keys-above
  "The enclosing `^{:key …}` chain, read upward. [[transform]] reads the
  same chain downward; a chain is order-free for the one thing the plan
  asks of it (how many keys, and which node)."
  [loc]
  (loop [ks [], l loc]
    (let [p (z/up l)]
      (if (and p (= :meta (z/tag p)))
        (recur (if-let [k (rw/meta-key-node (z/node p))] (conj ks k) ks) p)
        ks))))

(defn analyse
  "Walk the pristine tree, planning every site and stamping each entry
  with the file, line, column and source form. Returns
  `{:entries [...] :suggestions [...] :sites n :left-alone n}`."
  [source {:keys [file] :as ctx}]
  (loop [loc      (z/of-string source {:track-position? true})
         entries  []
         suggests []
         defs     []          ; (def Foo (r/adapt-react-class …)) sites
         calls    {}          ; symbol -> [line …], the same-file call sites
         sites    0
         quiet    0]
    (if (z/end? loc)
      {:entries    (into entries (map #(adapt-def-entry % calls)) defs)
       :suggestions suggests
       :sites      sites
       :left-alone quiet}
      (let [nd         (z/node loc)
            [line col] (z/position loc)
            form       (fn [] (let [s (z/string loc)]
                                (if (> (count s) 200) (str (subs s 0 200) " …") s)))
            kind       (rw/site-kind nd ctx)
            calls      (if-let [s (hiccup-head-symbol nd)]
                         (update calls s (fnil conj []) line)
                         calls)
            defs       (if (adapt-def? nd ctx)
                         (conj defs {:sym  (rw/sexpr-safe (rw/element-at nd 1))
                                     :line line :col col :form (form) :file file})
                         defs)]
        (if-not kind
          (recur (z/next loc) entries suggests defs calls sites quiet)
          (let [plan (rw/plan-site nd (assoc ctx :meta-keys (meta-keys-above loc)))
                es   (mapv #(assoc % :file file :line line :col col
                                   :form (form) :head (:head plan))
                           (:entries plan))]
            (recur (z/next loc)
                   (into entries es)
                   (cond-> suggests (:suggest plan) (conj (:suggest plan)))
                   defs calls
                   (inc sites)
                   (cond-> quiet (empty? es) inc))))))))

;; ---------------------------------------------------------------------------
;; Pass 2 — transform
;; ---------------------------------------------------------------------------

(declare rw-node)

(defn- rw-children [nd ctx]
  (if (n/inner? nd)
    (n/replace-children nd (mapv #(rw-node % ctx) (n/children nd)))
    nd))

(defn- rw-node
  "Rewrite one node. A site's own plan is applied AFTER its children have
  been rewritten — so a nested `[:>]` inside a prop value or a child slot
  is repaired too — but the plan itself is computed from the pristine
  node, so the report pass and this pass cannot reach different decisions."
  [nd ctx]
  (cond
    (rw/meta-node? nd)
    (let [[metas inner] (rw/unwrap-metas nd)
          plan   (rw/plan-site inner (assoc ctx :meta-keys (rw/meta-key-nodes metas)))
          inner' ((:edit plan) (rw-children inner ctx))]
      (rw/rebuild-meta-chain nd inner' (boolean (:w1? plan))))

    (rw/site-kind nd ctx)
    (let [plan (rw/plan-site nd (assoc ctx :meta-keys []))]
      ((:edit plan) (rw-children nd ctx)))

    :else (rw-children nd ctx)))

;; ---------------------------------------------------------------------------
;; Line endings
;; ---------------------------------------------------------------------------
;;
;; rewrite-clj's parser normalizes every newline it reads to `\n`, so a tool
;; that hands `n/string` straight back rewrites EVERY LINE of a CRLF file —
;; the whole-file diff that the formatting-preservation posture exists to
;; prevent, and a real hazard for the Windows consumers this codemod is aimed
;; at. The convention is read off the input and re-applied to the output.

(defn- crlf-source?
  "Does this source use CRLF line endings?

  Whichever of the two conventions the file uses MORE OFTEN wins, and a tie
  — including a file with no newline at all — goes to LF, which is what
  rewrite-clj already emits. A genuinely mixed file cannot be reproduced
  exactly whatever we do, because the parser has already discarded the
  distinction; the majority rule keeps the diff as small as that loss
  allows."
  [^String source]
  (let [crlf (count (re-seq #"\r\n" source))
        all  (count (re-seq #"\n" source))]
    (> crlf (- all crlf))))

(defn- with-newlines-of
  "Give `out` the line-ending convention of `source`.

  Normalize-then-apply rather than a bare `\\n` → `\\r\\n`, so a `\\r\\n`
  that survived inside a multi-line string token is not doubled into
  `\\r\\r\\n`. Idempotent by construction."
  [^String out ^String source]
  (if (crlf-source? source)
    (-> out (str/replace "\r\n" "\n") (str/replace "\n" "\r\n"))
    out))

(defn transform
  "Apply the fixer to a source string. Returns the rewritten text, in the
  line-ending convention the input was written in."
  [source ctx]
  (-> (n/string (rw-node (p/parse-string-all source) ctx))
      (with-newlines-of source)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- file-context
  "The per-file context: the Reagent aliases this file's `ns` form binds,
  and whether the file is `.cljc` (a `[:>]` there is `.cljs`-only at that
  node, which is `:cljc-site`)."
  [source file]
  (let [root (p/parse-string-all source)]
    (assoc (rw/ns-context root)
           :file  (some-> file str)
           :cljc? (boolean (some-> file str (str/ends-with? ".cljc"))))))

(defn scan-string
  "Analyse a source string. Never edits. Returns the analysis map."
  ([s] (scan-string s nil))
  ([s file] (analyse s (file-context s file))))

(defn rewrite-string
  "Apply the fixer to a source string. Returns
  `{:source <new-source> :entries [...] :suggestions [...] :sites n :left-alone n}`.
  The source is unchanged for every site the tool refused."
  ([s] (rewrite-string s nil))
  ([s file]
   (let [ctx (file-context s file)]
     (assoc (scan-string s file) :source (transform s ctx)))))

(defn- source-file? [^java.io.File f]
  (and (.isFile f)
       (let [nm (.getName f)]
         (or (str/ends-with? nm ".cljs") (str/ends-with? nm ".cljc")))))

(defn- expand-paths [paths]
  (->> paths
       (mapcat (fn [p]
                 (let [f (io/file p)]
                   (cond (.isDirectory f) (filter source-file? (file-seq f))
                         (.isFile f)      [f]
                         :else            []))))
       distinct
       (sort-by str)))

(defn- run-file
  "Analyse (and optionally rewrite) one file, converting any reader failure
  into a `:parse-error` entry rather than an exception — the design's
  rule is that the tool skips a file it cannot parse and says so."
  [f {:keys [rewrite? write?]}]
  (let [path (str f)]
    (try
      (let [orig (slurp f)
            r    (if rewrite? (rewrite-string orig path) (scan-string orig path))
            changed? (and rewrite? (not= orig (:source r)))]
        (when (and write? changed?) (spit f (:source r)))
        (assoc r :path path :changed? (boolean changed?)))
      (catch Exception e
        {:path path :changed? false :sites 0 :left-alone 0 :suggestions []
         :entries [{:file path :line 0 :col 0 :form "" :head nil
                    :class :parse-error :action :refused
                    :detail {:message (.getMessage e)}
                    :note (str "rewrite-clj could not read this file, so it was left completely "
                               "untouched. The reader said: " (.getMessage e))}]}))))

(defn run
  "Scan or rewrite a path set and build the report artefact."
  [paths {:keys [rewrite? write?] :as opts}]
  (let [files   (expand-paths paths)
        results (mapv #(run-file % opts) files)]
    (report/build
     {:entries          (vec (mapcat :entries results))
      :suggestions      (vec (mapcat :suggestions results))
      :files-scanned    (count files)
      :files-changed    (count (filter :changed? results))
      :dry-run?         (and rewrite? (not write?))
      :sites-total      (reduce + 0 (map #(or (:sites %) 0) results))
      :sites-left-alone (reduce + 0 (map #(or (:left-alone %) 0) results))})))

(defn scan-paths
  "Programmatic entry point: every entry across a path set."
  [paths]
  (:entries (run paths {})))

(defn rewrite-file!
  "Apply the fixer to one file. With `{:write? true}` the file is
  overwritten in place when it changes; otherwise it is a dry run."
  ([path] (rewrite-file! path {}))
  ([path opts] (run-file (io/file path) (assoc opts :rewrite? true))))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(def ^:private usage
  (str "usage: clojure -M:run [--rewrite] [--write] [--report PATH] PATH ...\n"
       "\n"
       "  (no flag)        scan: report only, touch nothing\n"
       "  --rewrite        dry run: show what would change\n"
       "  --rewrite --write  apply the fixer in place\n"
       "  --report PATH    where the EDN report goes"
       " (default: reagent-to-hicasso-report.edn)\n"))

(defn -main
  [& args]
  (let [flags    (set (filter #(str/starts-with? % "--") args))
        report-p (or (second (drop-while #(not= "--report" %) args))
                     "reagent-to-hicasso-report.edn")
        paths    (->> args
                      (remove #(str/starts-with? % "--"))
                      (remove #(= % report-p)))]
    (if (empty? paths)
      (do (print usage) (flush) (System/exit 2))
      (let [rep (run paths {:rewrite? (contains? flags "--rewrite")
                            :write?   (contains? flags "--write")})]
        (report/print-lines (:entries rep))
        (report/write! rep report-p)
        (println)
        (println (pr-str (:summary rep)))
        (println (str "report: " report-p))
        ;; Exit 0 for any run that COMPLETED. A migration tool that fails a
        ;; build because a consumer's code needs a human decision is a tool
        ;; that gets run once.
        (System/exit (if (some #(= :parse-error (:class %)) (:entries rep)) 1 0))))))
