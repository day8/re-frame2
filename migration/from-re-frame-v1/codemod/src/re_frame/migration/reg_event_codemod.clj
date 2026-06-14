(ns re-frame.migration.reg-event-codemod
  "EP-0018 Slice E — v1 -> v2 `reg-event` scanner + conservative codemod.

  EP-0018 collapses re-frame2's three public event-registration forms to one:

    reg-event-fx   ->  reg-event   (pure rename; same semantics)
    reg-event-db   ->  reg-event   with the handler body wrapped `{:db BODY}`
                                   and the `db` arg destructured `{:keys [db]}`
    reg-event-ctx  ->  (removed from the public surface; never auto-rewritten)

  This namespace is the SCANNER + the CONSERVATIVE CODEMOD. It operates purely
  on source TEXT via rewrite-clj (a zipper over the node tree), so formatting
  and comments survive the rewrite and re-frame2 itself is never loaded.

  ---------------------------------------------------------------------------
  WHAT IT REWRITES (and what it deliberately leaves for a human)
  ---------------------------------------------------------------------------

  * reg-event-fx  -> reg-event
      A pure symbol rename. `reg-event` IS today's `reg-event-fx`, so the body
      is byte-for-byte unchanged. Always safe; always applied.

  * reg-event-db  -> reg-event, when the form is the SIMPLE, FAITHFUL shape:
      (reg-event-db ID (fn [DB EV] BODY))            ;; 2-arg handler fn
        =>
      (reg-event    ID (fn [{:keys [db]} EV] {:db BODY}))

      BODY always evaluates to the new app-db (that IS the reg-event-db
      contract), so wrapping it in `{:db BODY}` is mechanical regardless of how
      complex BODY is. The first handler param (`DB`) is rebound to a
      `{:keys [db]}` destructure so the renamed handler reads the db out of the
      coeffects map. Path-interceptor metadata in the optional middle slot
      (`{:interceptors [(rf/path ...)]}`) is PRESERVED untouched.

      D7 NIL-FLAG: a reg-event-db handler whose BODY can evaluate to `nil` is
      NOT silently rewritten. Under the new model a bare `nil` return is a
      no-op and `{:db nil}` coerces to `{:db {}}` (rf2-ekq28v), so the author
      may now prefer the no-op reading. The codemod FLAGS these (`:flag/nil`)
      for the author to choose the intended reading; it leaves the source
      unchanged.

  * reg-event-db  -> FLAGGED for manual review (not rewritten) when the form is
      COMPLEX: a handler that is not a literal `(fn ...)` (a var, a
      higher-order construction, `comp`, etc.), a multi-arity fn, an `fn` with a
      handler arg that is itself destructured (so the `db` rebinding is unsafe),
      or anything the conservative analysis cannot prove is the simple shape.

  * reg-event-ctx -> ALWAYS FLAGGED, never rewritten. The full-context form is
      removed from the public surface; the replacement is an interceptor
      (`->interceptor`), which is a human-judgment rewrite.

  ---------------------------------------------------------------------------
  PROGRAMMATIC API (the atomic-flip slice calls these corpus-wide)
  ---------------------------------------------------------------------------

    (scan-string s {:keys [file]})  -> [finding ...]
    (scan-file   path)              -> [finding ...]
    (scan-paths  paths)             -> [finding ...]    ;; files + dirs (recursive)
    (rewrite-string s)              -> {:source out :findings [...]}
    (rewrite-file!  path opts)      -> {:path .. :changed? .. :findings [...]}
    (rewrite-paths! paths opts)     -> [{...} ...]

  A `finding` is a map:
    {:file       \"path or nil\"
     :line       42
     :col        3
     :form       :reg-event-db | :reg-event-fx | :reg-event-ctx
     :action     :rewrite | :rename | :flag
     :flag       nil | :nil-capable | :complex | :ctx          ;; when :action :flag
     :target     :reg-event | nil                              ;; suggested target form
     :note       \"human-readable explanation\"}"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; Symbol recognition
;; ---------------------------------------------------------------------------

(def ^:private registrar-forms
  "Maps a recognised registrar (by its *simple* name) to the v1 form keyword."
  {"reg-event-db"  :reg-event-db
   "reg-event-fx"  :reg-event-fx
   "reg-event-ctx" :reg-event-ctx})

(defn- registrar-of
  "If `sym` (a clojure symbol like `rf/reg-event-db`, `re-frame.core/reg-event-fx`,
  or bare `reg-event-db`) names one of the three retired registrars, return its
  form keyword; else nil. Matches on the symbol's *name* (post-`/`) so any
  namespace alias is recognised."
  [sym]
  (when (symbol? sym)
    (get registrar-forms (name sym))))

(defn- replace-registrar-name
  "Given a registrar symbol like `rf/reg-event-db`, return the same symbol with
  its name swapped to `reg-event` (alias/namespace preserved)."
  [sym]
  (if-let [ns* (namespace sym)]
    (symbol ns* "reg-event")
    (symbol "reg-event")))

;; ---------------------------------------------------------------------------
;; nil-capability analysis (D7)
;; ---------------------------------------------------------------------------
;;
;; Conservative: we ask "can this BODY (the final form of the handler) evaluate
;; to nil under SOME input?". We answer YES (=> flag) for the shapes a v1
;; reg-event-db body commonly takes that can yield nil, and NO only when the
;; body is provably non-nil (a literal map/vector/etc, or a thread/assoc/update
;; whose head is a guaranteed-non-nil db-builder). When unsure, we say YES
;; (flag) — the safe direction for D7.

(defn- sig-children
  "Non-whitespace, non-comment child nodes of a node."
  [node]
  (->> (n/children node)
       (filter (complement n/whitespace-or-comment?))))

(def ^:private nil-safe-heads
  "Head symbols whose return is a guaranteed-non-nil value when given a
  guaranteed-non-nil first arg (the db). These are the pervasive db-builders; a
  body headed by one of these cannot return nil for a map db."
  #{"assoc" "assoc-in" "update" "update-in" "dissoc" "merge" "merge-with"
    "conj" "into" "concat" "select-keys" "rename-keys" "vary-meta"
    "with-meta" "assoc!" "update-keys" "update-vals"})

(def ^:private nil-capable-heads
  "Head symbols that branch and may yield a falsey/absent value: the classic
  nil-capable reg-event-db shapes."
  #{"if" "if-let" "if-some" "if-not" "when" "when-let" "when-some" "when-not"
    "when-first" "cond" "condp" "case" "and" "or"
    "get" "get-in" "find" "some" "seek" "first" "peek" "next"
    "filter" "remove" "keep"})

(defn- classify-head
  "Classify a head-name string for nil-capability. Returns one of
  :safe / :nil-capable / :unknown."
  [head-name]
  (cond
    (contains? nil-safe-heads head-name)     :safe
    (contains? nil-capable-heads head-name)  :nil-capable
    :else                                    :unknown))

(declare node-nil-capable?)

(defn- list-nil-capable?
  "nil-capability of a list/seq node — its head symbol decides."
  [node]
  (let [kids (sig-children node)
        hd   (first kids)]
    (if (and hd (= :token (n/tag hd)))
      (let [hv (try (n/sexpr hd) (catch Exception _ nil))]
        (if (symbol? hv)
          (let [head-name (name hv)]
            (cond
              ;; some-> / some->> short-circuit to nil by construction.
              (#{"some->" "some->>"} head-name) true

              ;; -> / ->> thread: the value flows to the LAST stage's head.
              (#{"->" "->>"} head-name)
              (let [last-stage (last (rest kids))]
                (cond
                  (nil? last-stage) true
                  ;; (-> db (assoc ...)) — last stage is a list; its head decides
                  (= :list (n/tag last-stage))
                  (list-nil-capable? last-stage)
                  ;; (-> db some-fn) — bare-symbol stage; classify by its name
                  (= :token (n/tag last-stage))
                  (let [v (try (n/sexpr last-stage) (catch Exception _ nil))]
                    (if (symbol? v)
                      (not= :safe (classify-head (name v)))
                      true))
                  :else true))

              ;; when-family: a falsey test makes the form evaluate to nil.
              (#{"when" "when-let" "when-some" "when-not" "when-first"} head-name)
              true

              ;; do / let / letfn / binding: the value is the LAST body form.
              (#{"do" "let" "letfn" "binding"} head-name)
              (let [forms (rest kids)
                    tail  (if (#{"let" "letfn" "binding"} head-name)
                            (rest forms)  ;; skip the binding vector
                            forms)]
                (boolean (node-nil-capable? (last tail))))

              :else
              (case (classify-head head-name)
                :safe        false
                :nil-capable true
                :unknown     true)))
          true))
      true)))

(defn- node-nil-capable?
  "Conservative nil-capability of a single value node (the handler BODY).
  Returns true if the node can evaluate to nil under some input. When unsure we
  answer YES (flag) — the safe direction for D7."
  [node]
  (cond
    (nil? node) false
    (n/whitespace-or-comment? node) false
    :else
    (case (n/tag node)
      ;; Literal collections are never nil.
      (:map :vector :set) false
      :token (let [v (try (n/sexpr node) (catch Exception _ ::err))]
               (cond
                 (= v ::err) true        ;; unreadable -> be safe, flag
                 (nil? v)    true         ;; literal nil
                 (symbol? v) true         ;; a bare local/var — unknown value, flag
                 :else       false))      ;; literal number/string/kw/bool/char — non-nil
      :list  (list-nil-capable? node)
      ;; Anything else (reader macros, quotes, syntax-quote, ...) -> be safe.
      true)))

(defn- body-nil-capable? [node] (node-nil-capable? node))

;; ---------------------------------------------------------------------------
;; reg-event-db form shape analysis
;; ---------------------------------------------------------------------------

(defn- simple-handler-fn?
  "Is `node` a literal single-arity `(fn [DB EV] BODY...)` or `(fn name [DB EV] BODY...)`?
  Returns a map describing it, or nil if it is not the simple shape:
    {:fn-node node
     :name?   bool       ;; carries an optional fn name
     :params  param-vec-node
     :body    [body-node ...]}"
  [node]
  (when (and node (= :list (n/tag node)))
    (let [kids (sig-children node)
          hd   (first kids)]
      (when (and hd (= :token (n/tag hd))
                 (= 'fn (try (n/sexpr hd) (catch Exception _ nil))))
        (let [after-fn (rest kids)
              named?   (and (seq after-fn) (= :token (n/tag (first after-fn))))
              after    (if named? (rest after-fn) after-fn)
              params   (first after)
              body     (rest after)]
          (when (and params (= :vector (n/tag params)) (seq body))
            {:fn-node node :name? named? :params params :body (vec body)}))))))

(defn- params-simple?
  "Is the param vector the simple `[DB EV]` / `[DB _]` / `[DB]` shape with the
  first param a plain symbol (so we can safely rebind it to `{:keys [db]}`)?
  Returns the first-param node when simple, nil otherwise. A first param that is
  itself a destructure (`{:keys [...]}` / `[...]`) is NOT simple — flag it."
  [params-node]
  (let [ps (sig-children params-node)
        p0 (first ps)]
    (when (and p0 (= :token (n/tag p0))
               (symbol? (try (n/sexpr p0) (catch Exception _ nil))))
      p0)))

;; ---------------------------------------------------------------------------
;; Finding construction
;; ---------------------------------------------------------------------------

(defn- pos-of [zloc]
  (let [p (z/position zloc)]
    {:line (first p) :col (second p)}))

(defn- finding
  [base extra]
  (merge {:file nil :line nil :col nil :form nil :action nil
          :flag nil :target nil :note nil}
         base extra))

;; ---------------------------------------------------------------------------
;; Per-call-site analysis + rewrite
;; ---------------------------------------------------------------------------

(defn- analyse-call
  "Analyse a registrar call-site list zipper `zloc` (head already known to be a
  retired registrar `form-kw`). Returns {:finding f :rewrite-fn (fn [zloc] zloc')}.
  `rewrite-fn` mutates the zipper at this node when the action is a structural
  rewrite; for :flag / :rename-symbol-only it may be nil (handled by caller)."
  [zloc form-kw {:keys [file]}]
  (let [{:keys [line col]} (pos-of zloc)
        base {:file file :line line :col col :form form-kw}]
    (case form-kw
      :reg-event-fx
      {:finding (finding base {:action :rename :target :reg-event
                               :note "reg-event-fx -> reg-event (pure rename; same semantics)"})
       :kind :rename}

      :reg-event-ctx
      {:finding (finding base {:action :flag :flag :ctx :target nil
                               :note "reg-event-ctx removed from the public surface; rewrite to an interceptor (->interceptor) by hand"})
       :kind :flag}

      :reg-event-db
      (let [kids        (sig-children (z/node zloc))
            ;; kids: (reg-event-db ID ?meta HANDLER)
            handler-node (last kids)
            simple-fn    (simple-handler-fn? handler-node)]
        (cond
          (nil? simple-fn)
          {:finding (finding base {:action :flag :flag :complex :target nil
                                   :note "reg-event-db handler is not a literal single-arity (fn [db ev] ...) — review by hand (var/higher-order/multi-arity/anon-shorthand)"})
           :kind :flag}

          ;; first param not a plain symbol => unsafe to rebind to {:keys [db]}
          (nil? (params-simple? (:params simple-fn)))
          {:finding (finding base {:action :flag :flag :complex :target nil
                                   :note "reg-event-db handler's first param is destructured (or absent) — db-rebinding is unsafe; review by hand"})
           :kind :flag}

          ;; D7: body can evaluate to nil => flag, do not rewrite
          (body-nil-capable? (last (:body simple-fn)))
          {:finding (finding base {:action :flag :flag :nil-capable :target :reg-event
                                   :note "reg-event-db body can evaluate to nil; under v2 a bare nil is a no-op and {:db nil} coerces to {:db {}} -- choose the intended reading by hand (D7)"})
           :kind :flag}

          :else
          {:finding (finding base {:action :rewrite :target :reg-event
                                   :note "reg-event-db -> reg-event: db -> {:keys [db]}, body wrapped {:db BODY}"})
           :kind :rewrite-db
           :simple-fn simple-fn})))))

(defn- rewrite-db-handler
  "Given the handler `(fn [DB EV] BODY)` node and its analysis, return the new
  handler node `(fn [{:keys [db]} EV] {:db BODY})`, preserving fn name, the
  rest of the params, and any intervening whitespace in the params/body as much
  as rewrite-clj allows."
  [handler-node {:keys [params body]}]
  (let [p0        (params-simple? params)
        ;; Replace the first param token with a {:keys [db]} destructure node.
        keys-node (n/coerce '{:keys [db]})
        new-params (n/replace-children
                     params
                     (map (fn [c] (if (identical? c p0) keys-node c))
                          (n/children params)))
        ;; Wrap only the LAST body form in {:db ...}; earlier body forms (side
        ;; effects / lets at the top of an implicit do) are preserved verbatim.
        ;; The inner body node is reused unchanged, so its formatting/comments
        ;; survive the wrap.
        last-form  (last body)
        db-map     (n/map-node
                     [(n/keyword-node :db) (n/spaces 1) last-form])]
    (n/replace-children
      handler-node
      (map (fn [c]
             (cond
               (identical? c params)    new-params
               (identical? c last-form) db-map
               :else                    c))
           (n/children handler-node)))))

(defn- apply-rewrite-at
  "At the registrar call-site zipper `zloc`, apply the structural rewrite for
  `analysis`. Returns the (possibly mutated) zipper positioned at the call-site."
  [zloc form-kw analysis]
  (case (:kind analysis)
    :flag zloc                          ;; leave source unchanged
    :rename
    ;; rename the head symbol reg-event-fx -> reg-event
    (let [head-z (z/down zloc)
          sym    (z/sexpr head-z)]
      (z/up (z/replace head-z (n/token-node (replace-registrar-name sym)))))
    :rewrite-db
    (let [head-z   (z/down zloc)
          sym      (z/sexpr head-z)
          ;; rename head
          zloc1    (z/up (z/replace head-z (n/token-node (replace-registrar-name sym))))
          ;; rewrite handler node (the last child)
          node     (z/node zloc1)
          kids     (n/children node)
          simple   (:simple-fn analysis)
          old-h    (:fn-node simple)
          new-h    (rewrite-db-handler old-h simple)
          new-kids (map (fn [c] (if (identical? c old-h) new-h c)) kids)]
      (z/replace zloc1 (n/replace-children node new-kids)))
    zloc))

;; ---------------------------------------------------------------------------
;; Traversal
;; ---------------------------------------------------------------------------

(defn- registrar-call?
  "If zloc is positioned at a list whose head token names a retired registrar,
  return its form keyword; else nil."
  [zloc]
  (when (and (= :list (z/tag zloc)))
    (let [head (z/down zloc)]
      (when (and head (= :token (z/tag head)))
        (registrar-of (try (z/sexpr head) (catch Exception _ nil)))))))

(defn- walk
  "Walk every node of the zipper rooted at `zroot` (the value-stripping zipper
  positioned at its first form), collecting findings and — when `rewrite?` —
  applying the codemod in place. Returns {:zip <last-zloc> :findings v}."
  [zroot {:keys [rewrite?] :as opts}]
  (loop [zloc     zroot
         findings (transient [])]
    (let [form-kw (registrar-call? zloc)
          [zloc' fs] (if form-kw
                       (let [{:keys [finding] :as analysis} (analyse-call zloc form-kw opts)
                             z* (if rewrite? (apply-rewrite-at zloc form-kw analysis) zloc)]
                         [z* (conj! findings finding)])
                       [zloc findings])
          nxt (z/next zloc')]
      (if (z/end? nxt)
        {:zip nxt :findings (persistent! fs)}
        (recur nxt fs)))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn scan-string
  "Scan a source string; return a vector of findings (no rewrite)."
  ([s] (scan-string s {}))
  ([s opts]
   (let [zroot (z/of-string s {:track-position? true})]
     (:findings (walk zroot (assoc opts :rewrite? false))))))

(defn rewrite-string
  "Apply the conservative codemod to a source string. Returns
  {:source <new-source> :findings [...]}. The source is unchanged for any site
  whose action is :flag."
  ([s] (rewrite-string s {}))
  ([s opts]
   (let [zroot  (z/of-string s {:track-position? true})
         {:keys [zip findings]} (walk zroot (assoc opts :rewrite? true))]
     {:source   (z/root-string (or zip zroot))
      :findings findings})))

(defn scan-file
  "Scan a single file path; return findings stamped with :file."
  [path]
  (let [s (slurp path)]
    (scan-string s {:file (str path)})))

(defn- clj-source-file? [^java.io.File f]
  (and (.isFile f)
       (let [n (.getName f)]
         (or (str/ends-with? n ".clj")
             (str/ends-with? n ".cljc")
             (str/ends-with? n ".cljs")))))

(defn- expand-paths
  "Expand a seq of paths (files or dirs) into a seq of source files."
  [paths]
  (->> paths
       (mapcat (fn [p]
                 (let [f (io/file p)]
                   (cond
                     (.isDirectory f) (filter clj-source-file? (file-seq f))
                     (.isFile f)      [f]
                     :else            []))))
       distinct))

(defn scan-paths
  "Scan a seq of file/dir paths (dirs walked recursively for .clj/.cljc/.cljs);
  return all findings."
  [paths]
  (vec (mapcat (fn [f] (scan-string (slurp f) {:file (str f)}))
               (expand-paths paths))))

(defn rewrite-file!
  "Apply the codemod to a file. With `{:write? true}` the file is overwritten
  in place when it changes; otherwise it is a dry run. Returns
  {:path .. :changed? .. :findings [...]}."
  ([path] (rewrite-file! path {}))
  ([path {:keys [write?] :as opts}]
   (let [orig (slurp path)
         {:keys [source findings]} (rewrite-string orig {:file (str path)})
         changed? (not= orig source)]
     (when (and write? changed?)
       (spit path source))
     {:path (str path) :changed? changed? :findings findings :source source})))

(defn rewrite-paths!
  "Apply the codemod across a seq of file/dir paths. See `rewrite-file!`."
  ([paths] (rewrite-paths! paths {}))
  ([paths opts]
   (mapv #(rewrite-file! % opts) (expand-paths paths))))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- print-findings [findings]
  (doseq [{:keys [file line col form action flag note]} findings]
    (println (format "%s:%s:%s  %-14s %-8s %s"
                     (or file "-") (or line "-") (or col "-")
                     (name form) (name action)
                     (str (when flag (str "[" (name flag) "] ")) note)))))

(defn- summary [findings]
  (let [by (group-by :action findings)]
    {:total   (count findings)
     :rewrite (count (:rewrite by))
     :rename  (count (:rename by))
     :flag    (count (:flag by))}))

(defn -main
  "CLI: scan (default) or --rewrite a file set.

    clojure -M:run PATH ...               ;; scan + print findings
    clojure -M:run --rewrite PATH ...     ;; dry-run rewrite + print findings
    clojure -M:run --rewrite --write PATH ;; rewrite IN PLACE"
  [& args]
  (let [rewrite? (some #{"--rewrite"} args)
        write?   (some #{"--write"} args)
        paths    (remove #{"--rewrite" "--write"} args)]
    (when (empty? paths)
      (println "usage: clojure -M:run [--rewrite] [--write] PATH ...")
      (System/exit 2))
    (if rewrite?
      (let [results (rewrite-paths! paths {:write? write?})
            findings (mapcat :findings results)
            changed (filter :changed? results)]
        (print-findings findings)
        (println)
        (println (format "%d file(s) %s; summary: %s"
                         (count changed)
                         (if write? "rewritten" "would change (dry run)")
                         (summary findings))))
      (let [findings (scan-paths paths)]
        (print-findings findings)
        (println)
        (println (format "summary: %s" (summary findings)))))))
