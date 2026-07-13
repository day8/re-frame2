(ns re-frame.ui.compiler.analyze
  "Template-grammar analyzer: one normalized, closed-node-set template AST
  from the blessed hiccup grammar (Spec 004 rewrite §Template grammar).
  The AST is PRIVATE — the public contract is the JVM tree + the
  conversion table; both emitters consume ONLY these nodes:

    :text :nothing :expr :element :fragment :view :foreign
    :if :let :letfn :case :for :raw :html

  Control forms (let/letfn/if/if-not/when/when-not/cond/case/pure-do/for)
  normalize INTO the AST; every analyzer and both emitters see through
  branches. Rejected forms throw compile errors with
  {:rf.ui.compile/error <id>} ex-data (the S1e roster keys off the ids)."
  (:require [clojure.string :as str]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.fingerprint :as fingerprint]
            [re-frame.ui.rules :as rules]))

(def node-ops
  "The CLOSED op set — the AST-shape gate's vocabulary. `:frame-root` (S1c,
  rf2-vxgfnd.3) appears only inside the static top region of a ROOT form
  (`ui/mount` / `ui/render!` / `ui/hydrate-root`) — the analyzer rejects it
  everywhere else, so defview-template ASTs never carry it.
  `:frame-provider` (S2c, rf2-vxgfnd.9) is the SCOPE form — legal anywhere,
  scoping a subtree to an already-live frame."
  #{:text :nothing :expr :element :fragment :view :foreign
    :if :let :letfn :case :for :raw :html :frame-root :frame-provider})

(defn literal-scalar? [x]
  (or (string? x) (number? x) (keyword? x) (boolean? x) (nil? x)))

(def ^:private ui-raw-fqns    #{'re-frame.ui/raw})
(def ^:private ui-html-fqns   #{'re-frame.ui/html})
(def ^:private ui-raw-fn-fqns #{'re-frame.ui/raw-fn})
(def ^:private ui-spread-fqns #{'re-frame.ui/spread})
(def ^:private sub-fqns       #{'re-frame.ui/sub})
(def ^:private lease-fqns     #{'re-frame.ui/lease})
(def ^:private frame-root-fqns #{'re-frame.ui/frame-root})
(def ^:private frame-provider-fqns #{'re-frame.ui/frame-provider})

(def markup-map-fqns
  "The map family — heads whose (f render-fn coll) idiom generates markup
  rows lazily. Rejected in child position (:rf.ui.compile/markup-returning-map)
  with the keyed-(for ...) escape. CLOSED vocabulary: additions are S1e
  roster changes."
  #{'clojure.core/map          'cljs.core/map
    'clojure.core/map-indexed  'cljs.core/map-indexed
    'clojure.core/mapcat       'cljs.core/mapcat
    'clojure.core/keep         'cljs.core/keep
    'clojure.core/keep-indexed 'cljs.core/keep-indexed})

(def lazy-seq-fqns
  "Core seq producers whose value in child position is a RAW SEQ — the
  grammar rejects raw lazy seqs (Spec 004 rewrite §Template grammar:
  they hide keys and laziness). Rejected in child position
  (:rf.ui.compile/lazy-seq-child). CLOSED vocabulary: additions are S1e
  roster changes. Note: these heads stay legal everywhere expressions
  are opaque — prop values, for-collections, if-tests — only RENDERED
  CONTENT positions reject them."
  (into #{}
        (mapcat (fn [s] [(symbol "clojure.core" (name s))
                         (symbol "cljs.core" (name s))]))
        '[filter remove take take-while take-nth take-last drop drop-while
          drop-last concat interpose interleave sequence repeat repeatedly
          iterate cycle range distinct dedupe flatten partition
          partition-all partition-by sort sort-by reverse rest next butlast
          keys vals seq re-seq]))

(def ^:private control-heads
  #{'if 'if-not 'when 'when-not 'cond 'case 'let 'letfn 'do 'for})

(defn- fn-form? [f]
  (and (seq? f) (contains? #{'fn 'fn* 'clojure.core/fn 'cljs.core/fn} (first f))))

(defn- raw-form? [e f]  (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-raw-fqns)))
(defn- html-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-html-fqns)))
(defn- raw-fn-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-raw-fn-fqns)))
(defn- spread-form? [e f] (and (seq? f) (symbol? (first f)) (env/resolves-to? e (first f) ui-spread-fqns)))

;; ---------------------------------------------------------------------------
;; Expression rewriting — lexical site indexing + loop finiteness
;; ---------------------------------------------------------------------------

(def ^:private runtime-sub-fqn 're-frame.ui.reactive/sub-read)
(def ^:private deferred-scope ::deferred-scope)

(defn- deferred-expr-root?
  "Expression roots whose value is evaluated by a later host callback, not by
  the view's render capture. `sub`/`lease` below these roots would be
  phase-divergent between the JVM data host and React."
  [expr-root]
  (or (= :handler (first expr-root))
      (some #{:raw-fn} expr-root)))

(defn runtime-sub-form?
  "True for the compiler's serialized internal lowering of a lexical sub.
  Public only within the compiler family (fingerprint/emitter tests use it)."
  [x]
  (and (seq? x) (= runtime-sub-fqn (first x)) (= 3 (count x))))

(defn template-fingerprint-projection
  "Remove compiler-owned lexical site ids from an analyzed AST while retaining
  the semantic sub query. This makes source-anchor/path movement irrelevant to
  template/build identity without making the query invisible."
  [ast]
  (letfn [(project [x]
            (cond
              (runtime-sub-form? x)
              (with-meta (list 're-frame.ui/sub (project (nth x 2))) (meta x))

              (map? x)
              (with-meta (into (empty x) (map (fn [[k v]] [(project k) (project v)])) x)
                         (meta x))

              (vector? x) (with-meta (mapv project x) (meta x))
              (set? x)    (with-meta (into (empty x) (map project) x) (meta x))
              (seq? x)    (with-meta (apply list (map project x)) (meta x))
              :else x))]
    (project ast)))

(defn- relative-source-anchor
  [e form]
  (let [m    (meta form)
        base (:source e)
        line (:line m)
        col  (:column m)]
    (if (and (integer? line) (integer? (:line base)))
      ;; Reader end coordinates are not host-common (and have changed across
      ;; reader versions). Start coordinates are the portable lexical anchor.
      [:relative (- line (:line base)) (or col 0)]
      ;; No reader anchor means no attempt to history-match sites. The whole
      ;; template anchor changes all such ids on an edit: safe reacquisition,
      ;; never ordinal transfer to a different lexical site.
      [:template (or (:template-anchor e)
                     (fingerprint/digest "sta1-" form))])))

(defn- lexical-site-id
  [e kind form expr-path]
  (fingerprint/digest
   "sid1-"
   [1 (:self-id e) kind (relative-source-anchor e form)
    (:path e) (vec expr-path)]))

(defn- map-path-token [x]
  (fingerprint/digest "ep1-" x))

(defn rewrite-expr
  "Rewrite an opaque expression, lowering resolved unshadowed `(sub q)` calls
  to the serialized two-argument runtime shape and recording stable lexical
  sub/lease ids. The returned form MUST be threaded into the AST.

  `expr-root` names the containing AST slot; recursive paths then distinguish
  every nested expression without a fragile global/preorder ordinal. Binding
  scopes follow evaluation order for fn/let/loop/letfn and all metadata and
  collection kinds are preserved."
  ([e form] (rewrite-expr e [:expr] form))
  ([e expr-root form]
   (letfn [(with-same-meta [old x]
              (with-meta x (meta old)))
           (rw-fn-arity [arity locals p]
             (let [[argv & body] arity
                   body-locals (-> locals
                                   (into (env/binding-syms argv))
                                   (conj deferred-scope))]
               (with-same-meta
                 arity
                 (apply list argv
                        (map-indexed #(rw %2 body-locals (conj p :body %1)) body)))))
           (rw-fn [f locals p]
             (let [head (first f)
                   tail (rest f)
                   named? (symbol? (first tail))
                   fname (when named? (first tail))
                   tail (if named? (rest tail) tail)
                   locals* (cond-> locals fname (conj fname))
                   rewritten
                   (if (vector? (first tail))
                     (let [[argv & body] tail
                            body-locals (-> locals*
                                            (into (env/binding-syms argv))
                                            (conj deferred-scope))]
                       (concat [head] (when fname [fname]) [argv]
                               (map-indexed #(rw %2 body-locals (conj p :body %1)) body)))
                     (concat [head] (when fname [fname])
                             (map-indexed #(rw-fn-arity %2 locals* (conj p :arity %1))
                                          tail)))]
               (with-same-meta f (apply list rewritten))))
           (rw-let [f locals p]
             (let [head (first f)
                   bindings (second f)]
               (if-not (vector? bindings)
                 (with-same-meta f
                   (apply list (map-indexed #(rw %2 locals (conj p %1)) f)))
                 (let [[bindings* locals*]
                       (loop [pairs (partition 2 bindings)
                              i 0
                              out []
                              scope locals]
                         (if-let [[pat init] (first pairs)]
                           (recur (rest pairs) (inc i)
                                  (conj out pat (rw init scope (conj p :binding i)))
                                  (into scope (env/binding-syms pat)))
                           [(with-same-meta bindings (vec out)) scope]))]
                   (with-same-meta
                     f
                     (apply list head bindings*
                             (map-indexed #(rw %2 (cond-> locals*
                                                   (contains? #{'loop 'loop*} head)
                                                   (conj deferred-scope))
                                               (conj p :body %1))
                                          (drop 2 f))))))))
           (rw-letfn [f locals p]
             (let [specs (second f)]
               (if-not (vector? specs)
                 (with-same-meta f
                   (apply list (map-indexed #(rw %2 locals (conj p %1)) f)))
                 (let [names (into #{} (keep #(when (seq? %) (first %))) specs)
                       scope (into locals names)
                       specs* (with-same-meta
                                specs
                                (mapv (fn [i spec]
                                        (if (and (seq? spec) (symbol? (first spec)))
                                          (let [[name & arities] spec
                                                fake (with-meta (apply list 'fn name arities)
                                                                (meta spec))
                                                [_ _ & rewritten] (rw-fn fake scope
                                                                          (conj p :spec i))]
                                            (with-same-meta spec (apply list name rewritten)))
                                          (rw spec scope (conj p :spec i))))
                                      (range) specs))]
                   (with-same-meta
                     f
                     (apply list (first f) specs*
                            (map-indexed #(rw %2 scope (conj p :body %1))
                                         (drop 2 f))))))))
           (rw-try [f locals p]
             ;; `catch` introduces a local, so it cannot go through generic
             ;; traversal. `finally` does not. Preserve the marker/type slots
             ;; verbatim and rewrite only evaluated bodies.
             (with-same-meta
               f
               (apply list
                      (first f)
                      (map-indexed
                       (fn [i clause]
                         (cond
                           (and (seq? clause) (= 'catch (first clause))
                                (symbol? (nth clause 2 nil)))
                           (let [[marker type binding & body] clause]
                             (with-same-meta
                               clause
                               (apply list marker type binding
                                      (map-indexed
                                       #(rw %2 (conj locals binding)
                                            (conj p :catch i :body %1))
                                       body))))

                           (and (seq? clause) (= 'finally (first clause)))
                           (with-same-meta
                             clause
                             (apply list 'finally
                                    (map-indexed
                                     #(rw %2 locals (conj p :finally :body %1))
                                     (rest clause))))

                           :else (rw clause locals (conj p :body i))))
                       (rest f)))))
           (rw-map [m locals p]
             (with-same-meta
               m
               (reduce-kv (fn [out k v]
                            (let [t (map-path-token k)]
                              (assoc out
                                     (rw k locals (conj p :map-key t))
                                     (rw v locals (conj p :map-value t)))))
                          (empty m)
                          m)))
           (contains-reactive-call? [x locals]
             ;; An unresolved/unknown binder macro cannot be traversed safely.
             ;; This conservative scan deliberately treats a macro-local name
             ;; that shadows `sub` as reactive: only macro expansion could
             ;; prove the shadow, so failing is preferable to mis-lowering it.
             (let [e* (update e :locals into locals)]
               (cond
                 (and (seq? x) (= 'quote (first x))) false
                 (seq? x)
                 (or (let [h (first x)]
                       (and (symbol? h)
                            (not (contains? locals h))
                            (or (env/resolves-to? e* h sub-fqns)
                                (env/resolves-to? e* h lease-fqns))))
                     (some #(contains-reactive-call? % locals) (rest x)))
                 (map? x) (or (some #(contains-reactive-call? % locals) (keys x))
                              (some #(contains-reactive-call? % locals) (vals x)))
                 (coll? x) (some #(contains-reactive-call? % locals) x)
                 :else false)))
           (macro-call? [e* head locals]
             (and (symbol? head)
                  (not (contains? locals head))
                  (true? (-> (env/resolve-sym e* head) :meta :macro))))
           (rw [f locals p]
             (cond
               (and (seq? f) (= 'quote (first f))) f

               (seq? f)
               (let [head (first f)
                     e*   (update e :locals into locals)]
                 (cond
                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head sub-fqns))
                    (do
                      (when-not (= 2 (count f))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   "(sub query) takes exactly one query argument"
                                   {:form f}))
                      (when (or (nil? (:self-id e))
                                (:in-loop? e)
                                (contains? locals deferred-scope))
                        (env/fail! e :rf.ui.compile/sub-in-loop
                                   (str "(sub ...) must be a finite render-time site in a "
                                        "defview — it cannot run in a loop, deferred "
                                        "callback, raw-fn/ref body, or root expression. "
                                        "Hoist the read into the view body and let the "
                                        "callback capture its committed value; for rows, "
                                        "extract a keyed child view")
                                   {:form f}))
                     (let [sid   (lexical-site-id e :sub f p)
                           query (rw (second f) locals (conj p :query))]
                       (env/add-site! e :subs {:sid sid :query (second f)
                                               :path (:path e) :expr-path (vec p)})
                       (with-same-meta f (list runtime-sub-fqn sid query))))

                    (and (symbol? head) (not (contains? locals head))
                         (env/resolves-to? e* head lease-fqns))
                    (do
                      (when-not (= 2 (count f))
                        (env/fail! e :rf.ui.compile/unsupported-form
                                   "(lease descriptor) takes exactly one descriptor argument"
                                   {:form f}))
                      (when (or (nil? (:self-id e))
                                (:in-loop? e)
                                (contains? locals deferred-scope))
                        (env/fail! e :rf.ui.compile/lease-in-loop
                                   (str "(lease ...) must be a finite render-time site in "
                                        "a defview — it cannot run in a loop, deferred "
                                        "callback, raw-fn/ref body, or root expression. "
                                        "Hoist the lease into the view body; for rows, "
                                        "extract a keyed child view")
                                   {:form f}))
                     (let [sid (lexical-site-id e :lease f p)]
                       (env/add-site! e :leases {:sid sid :descriptor (second f)
                                                 :path (:path e) :expr-path (vec p)})
                       ;; Runtime lease lowering belongs to .106. Rebuild now so
                       ;; sub sites nested in its descriptor still lower.
                       (with-same-meta
                         f
                          (apply list head
                                 (map-indexed #(rw %2 locals (conj p (inc %1)))
                                             (rest f))))))

                   (fn-form? f) (rw-fn f locals p)
                   (contains? #{'let 'let* 'loop 'loop*} head) (rw-let f locals p)
                   (contains? #{'letfn 'letfn*} head) (rw-letfn f locals p)
                   (= 'try head) (rw-try f locals p)

                   (macro-call? e* head locals)
                   (if (contains-reactive-call? (rest f) locals)
                     (env/fail! e :rf.ui.compile/unsupported-form
                                (str "(sub ...)/(lease ...) below macro " head
                                     " cannot be assigned a sound lexical site "
                                     "before macro expansion. Hoist the read into "
                                     "a surrounding let, then pass the value to "
                                     "the macro expression")
                                {:form f :macro head})
                     f)

                   :else
                   (with-same-meta
                     f
                     ;; The call head is not an evaluated argument and cannot
                     ;; itself contain a reactive site. Preserve it verbatim.
                     (apply list head
                            (map-indexed #(rw %2 locals (conj p (inc %1)))
                                         (rest f))))))

               (map? f) (rw-map f locals p)
               (vector? f)
               (with-same-meta f (mapv #(rw %2 locals (conj p %1)) (range) f))
               (set? f)
               (with-same-meta
                 f
                 (into (empty f)
                       (map #(rw % locals (conj p :set (map-path-token %))))
                       f))
               :else f))]
     (rw form (cond-> #{}
                (deferred-expr-root? expr-root) (conj deferred-scope))
         (vec expr-root)))))

;; Transitional internal name used throughout the analyzer. Unlike its former
;; side-effect-only implementation it returns the rewritten expression.
(def walk-expr rewrite-expr)

;; ---------------------------------------------------------------------------
;; Tag sugar — :div.cls#id / :div#id.cls (both orders)
;; ---------------------------------------------------------------------------

(defn parse-tag
  "Parse a keyword element head into {:tag kw :id str|nil :classes [str]}.
  `.class`/`#id` segments compose in any order; two `#id`s are an error."
  [e head]
  (let [s (name head)]
    (when (namespace head)
      (env/fail! e :rf.ui.compile/bad-tag
                 (str "element head " head " must be an unqualified keyword — "
                      "tag keywords carry no namespace (write :" s ")")
                 {:head head}))
    (when (or (str/blank? s) (str/starts-with? s ".") (str/starts-with? s "#"))
      (env/fail! e :rf.ui.compile/bad-tag
                 (str "element head " head " needs a tag name before sugar "
                      "(e.g. :div.card, :div#main)")
                 {:head head}))
    (let [segs (re-seq #"[.#][^.#]+|^[^.#]+" s)
          tag  (first segs)
          sugar (rest segs)]
      (reduce (fn [acc seg]
                (cond
                  (str/starts-with? seg "#")
                  (if (:id acc)
                    (env/fail! e :rf.ui.compile/duplicate-id-sugar
                               (str "two #id segments on " head " — an element "
                                    "has one id. Keep one #id segment (or move "
                                    "the id to an :id prop)")
                               {:head head})
                    (assoc acc :id (subs seg 1)))
                  (str/starts-with? seg ".")
                  (update acc :classes conj (subs seg 1))
                  :else acc))
              {:tag (keyword tag) :id nil :classes []}
              sugar))))

;; ---------------------------------------------------------------------------
;; Handlers (DOM/custom-element :on-* positions)
;; ---------------------------------------------------------------------------

(defn- check-loop-capture! [e kind form]
  (when (:in-loop? e)
    (let [captured (env/captured-loop-syms e form)]
      (when (seq captured)
        (env/fail! e :rf.ui.compile/loop-capturing-handler
                   (str kind " captures loop binding" (when (next captured) "s")
                        " " (str/join ", " (sort (map str captured)))
                        " — per-row committed slots need per-row instances. "
                        "Extract a keyed child view and pass "
                        (first (sort (map str captured))) " as a prop")
                   {:form form :captured captured})))))

(defn- check-placeholders! [e vec-form]
  ;; top-level placeholders splice; NESTED placeholder keywords are almost
  ;; certainly a bug -> warning (they dispatch as ordinary keywords)
  (doseq [[i x] (map-indexed vector vec-form)]
    (when (and (coll? x) (seq (filter rules/placeholders (env/kws-in-form x))))
      (env/warn! e {:id :rf.ui.compile/placeholder-not-top-level
                    :msg (str "placeholder keyword nested inside event-vector "
                              "argument " i " — placeholders splice only at "
                              "top-level positions; nested ones dispatch as "
                              "ordinary keywords")
                    :form vec-form}))))

(defn- analyze-event-vector! [e k form]
  (when-not (keyword? (first form))
    (env/fail! e :rf.ui.compile/bad-event-vector
               (str k " event vector must start with a literal event-id "
                    "keyword: [:domain/event args...]")
               {:prop k :form form}))
  (check-loop-capture! e (str "event vector " (pr-str form) " at " k) form)
  (check-placeholders! e form)
  (with-meta
    (into [(first form)]
          (map-indexed (fn [i x]
                         (walk-expr e [:handler k :event-arg i] x)))
          (rest form))
    (meta form)))

(defn analyze-handler
  "Classify one :on-* entry. -> {:k kw :name str :classification
  :vector|:options|:fn|:dynamic :form form :capture? bool
  :hoistable? bool :serializable? bool}"
  [e k form]
  (let [nm (name k)]
    (cond
      (vector? form)
      (let [form* (analyze-event-vector! e k form)]
          (env/add-site! e :events {:prop k :handler form* :path (:path e)
                                    :classification :vector :serializable? true})
          {:k k :name nm :classification :vector :form form* :capture? false
           :hoistable? (every? #(or (literal-scalar? %) (contains? rules/placeholders %)) form*)
           :serializable? true})

      (map? form)
      (let [unknown (remove rules/handler-option-keys (keys form))]
        (when (seq unknown)
          (env/fail! e :rf.ui.compile/bad-handler-options
                     (str "unknown handler option" (when (next unknown) "s") " "
                          (str/join ", " (map pr-str unknown)) " at " k
                          " — the closed listener vocabulary is "
                          "{:event :prevent-default :stop-propagation :capture "
                          ":passive :once}")
                     {:prop k :form form}))
        (when (and (:passive form) (:prevent-default form))
          (env/fail! e :rf.ui.compile/contradictory-handler-options
                     (str ":passive true with :prevent-default true at " k
                          " — a passive listener promises the browser it will "
                          "NEVER call preventDefault, so the combination is a "
                          "contradiction (in any stage). Drop one of them")
                     {:prop k :form form}))
        (when-not (vector? (:event form))
          (env/fail! e :rf.ui.compile/bad-handler-options
                     (str "handler options map at " k " needs a literal "
                          ":event vector — {:event [:domain/event args...] "
                          ":prevent-default true ...}")
                     {:prop k :form form}))
        (when (or (:passive form) (:once form))
          (env/fail! e :rf.ui.compile/handler-option-unavailable-s1
                     (str ":passive/:once at " k " land with the committed-"
                          "handler slice (S3) — the React host cannot express "
                          "them as element props; S1 rejects rather than "
                          "silently dropping them. Remove the option for now")
                     {:prop k :form form}))
        (let [event* (analyze-event-vector! e k (:event form))
              form*  (assoc form :event event*)]
          (env/add-site! e :events {:prop k :handler form* :path (:path e)
                                    :classification :options :serializable? true})
          {:k k :name nm :classification :options :form form*
           :capture? (boolean (:capture form))
           :hoistable? (every? #(or (literal-scalar? %)
                                     (contains? rules/placeholders %))
                                event*)
           :serializable? true}))

      (fn-form? form)
      (do (when (:in-loop? e)
            (env/warn! e {:id :rf.ui.compile/bare-fn-in-loop
                          :msg (str "bare fn handler at " k " inside a loop — "
                                    "works, at per-row closure cost, and defeats "
                                    "the data idiom; prefer an event vector or a "
                                    "keyed child view")
                          :form form}))
          (let [form* (walk-expr e [:handler k :fn] form)]
            (env/add-site! e :events {:prop k :handler :opaque :path (:path e)
                                      :classification :fn :serializable? false})
            {:k k :name nm :classification :fn :form form* :capture? false
             :hoistable? false :serializable? false}))

      :else
      (let [form* (walk-expr e [:handler k :dynamic] form)]
        (check-loop-capture! e (str "dynamic handler at " k) form)
        (env/add-site! e :events {:prop k :handler :opaque :path (:path e)
                                  :classification :dynamic :serializable? false})
        {:k k :name nm :classification :dynamic :form form* :capture? false
         :hoistable? false :serializable? false}))))

;; ---------------------------------------------------------------------------
;; :class / :style
;; ---------------------------------------------------------------------------

(defn analyze-class
  "-> {:base-str str :flags [[name expr]...] :dyn form|nil :static? bool}
  Sugar classes first (source order), then the explicit :class form;
  flag-map entries in lexicographic name order; no de-duplication."
  [e sugar form]
  (let [base (vec sugar)]
    (cond
      (nil? form)
      {:base-str (str/join " " base) :flags [] :dyn nil
       :static? true}

      (or (string? form) (keyword? form))
      {:base-str (str/join " " (conj base (if (keyword? form) (name form) form)))
       :flags [] :dyn nil :static? true}

      (and (vector? form) (every? #(or (string? %) (keyword? %) (nil? %)) form))
      {:base-str (str/join " " (into base (keep #(when % (if (keyword? %) (name %) %))) form))
       :flags [] :dyn nil :static? true}

      (map? form)
      (do
        (when-not (every? #(or (keyword? %) (string? %)) (keys form))
          (env/fail! e :rf.ui.compile/bad-class
                     ":class flag-map keys must be literal names (string/keyword)"
                     {:form form}))
        (let [{consts true exprs false}
              (group-by (fn [[_ v]] (literal-scalar? v)) form)
              const-names (into base
                                (->> consts
                                     (keep (fn [[k v]] (when v (if (keyword? k) (name k) k))))
                                     sort))
               flags (->> exprs
                          (map (fn [[k v]]
                                 [(if (keyword? k) (name k) k)
                                  (walk-expr e [:class :flag k] v)]))
                          (sort-by first)
                          vec)]
          {:base-str (str/join " " const-names) :flags flags :dyn nil
           :static? (empty? flags)}))

      (vector? form) ; mixed literal/expr vector — runtime join in vector order
      (let [form* (with-meta
                    (mapv (fn [i x]
                            (if (literal-scalar? x)
                              x
                              (walk-expr e [:class :vector i] x)))
                          (range) form)
                    (meta form))]
        {:base-str (str/join " " base) :flags [] :dyn form* :static? false})

      :else
      {:base-str (str/join " " base) :flags []
       :dyn (walk-expr e [:class :dynamic] form) :static? false})))

(defn analyze-style
  "-> {:entries [{:css-name str :value form :literal? bool}] :static? bool}
  or {:dyn form} for a wholly-dynamic :style expression."
  [e form]
  (cond
    (map? form)
    (let [entries (mapv (fn [[k v]]
                          (when-not (keyword? k)
                            (env/fail! e :rf.ui.compile/bad-style
                                       (str ":style keys must be literal "
                                            "keywords — for computed names, "
                                            "pass the whole :style value as "
                                            "one dynamic expression")
                                       {:key k :form form}))
                           {:css-name (name k)
                            :value (if (literal-scalar? v)
                                     v
                                     (walk-expr e [:style k] v))
                            :literal? (literal-scalar? v)})
                         form)]
      {:entries entries :static? (every? :literal? entries)})

    :else
    {:dyn (walk-expr e [:style :dynamic] form) :static? false}))

;; ---------------------------------------------------------------------------
;; Element props
;; ---------------------------------------------------------------------------

(defn- check-rejected-spelling! [e k]
  (when-let [replacement (get rules/rejected-prop-spellings k)]
    (env/fail! e :rf.ui.compile/rejected-prop-spelling
               (str k " is not a prop — one spelling per name, ambiguities "
                    "removed. Use " replacement)
               {:prop k})))

(defn- analyze-ref [e form context]
  (case context
    :element
    (cond
      (fn-form? form)
      (env/fail! e :rf.ui.compile/bare-fn-ref
                 (str "bare fn in :ref — the bare-fn shorthand applies only to "
                      "native event properties, never refs. A callback ref must "
                      "be explicit: (ui/raw-fn f); object refs are preferred")
                 {:form form})
      (raw-fn-form? e form)
      {:form (walk-expr e [:ref :raw-fn] (second form)) :raw-fn? true}
      :else
      {:form (walk-expr e [:ref :element] form) :raw-fn? false})
    :view
    (env/fail! e :rf.ui.compile/ref-on-view-s1
               (str ":ref at an internal-view call site — internal views "
                    "forward :ref only by declaring it, and declared ref "
                    "forwarding lands S3. (Conservative S1 pin.)")
               {:form form})
    :foreign
    {:form (walk-expr e [:ref :foreign] form) :raw-fn? false}))

(defn analyze-element-props
  "Analyze a DOM/custom element's props position (literal map or
  (ui/spread base overrides)). -> {:key {..} :class {..} :style {..}|nil
  :attrs [..] :events [..] :spread form|nil :ref {..}|nil
  :property-props #{kw} :static? bool}"
  [e tag-info custom? props-form]
  (let [tag        (:tag tag-info)
        ;; Per-build compile-time property classification, read from the
        ;; ambient build's `elements` slice (rf2-vxgfnd.91). This analysis
        ;; runs under `cljs.env/*compiler*`, so `build/element-properties`
        ;; resolves the CURRENT build's declarations — never a process-global
        ;; last-writer-wins mirror that a sibling build could clobber between
        ;; this tag's declaration and this classification read.
        properties (when custom? (build/element-properties tag))
        spread?    (spread-form? e props-form)]
    (when (and (some? props-form) (not (map? props-form)) (not spread?))
      (env/fail! e :rf.ui.compile/dynamic-props-map
                 (str "props of " tag " must be a literal map or "
                      "(ui/spread base overrides) — the one generic runtime "
                      "prop-map conversion")
                 {:form props-form}))
    (if spread?
      (let [[_ base overrides & extra] props-form]
        (when (or (nil? base) (seq extra))
          (env/fail! e :rf.ui.compile/bad-spread
                     "(ui/spread base) or (ui/spread base overrides)"
                     {:form props-form}))
        (let [base*      (walk-expr e [:spread :base] base)
              overrides* (when overrides
                           (walk-expr e [:spread :overrides] overrides))]
        {:key {:present? false} :class (analyze-class e (:classes tag-info) nil)
         :style nil :attrs [] :events []
         :spread {:base base* :overrides overrides*}
         :ref nil :property-props #{} :static? false}))
      (let [m (or props-form {})]
        (doseq [k (keys m)]
          (when-not (keyword? k)
            (env/fail! e :rf.ui.compile/non-keyword-prop
                       (str "prop keys must be literal keywords; got " (pr-str k))
                       {:key k}))
          (check-rejected-spelling! e k))
        (when (and (:id tag-info) (contains? m :id))
          (env/fail! e :rf.ui.compile/id-sugar-conflict
                     (str "#" (:id tag-info) " sugar AND an :id prop on " tag
                          " — two id spellings on one element is an ambiguity, "
                          "and this grammar removes ambiguities. Keep one")
                     {:tag tag}))
        (let [key-form   (get m :key)
              m*         (dissoc m :key :class :style :ref)
              ref-form   (get m :ref)
              on?        (fn [k] (str/starts-with? (name k) "on-"))
              handler-ks (filter on? (keys m*))
              attr-ks    (remove on? (keys m*))
              events     (mapv #(analyze-handler e % (get m* %)) handler-ks)
              attrs      (mapv (fn [k]
                                  (let [v (get m* k)
                                        n (name k)
                                        property? (boolean (and properties (properties k)))
                                        v* (if (literal-scalar? v)
                                             v
                                             (walk-expr e [:prop k] v))]
                                   ;; literal collection VALUES only — seq forms
                                   ;; are dynamic expressions (runtime-normalized)
                                   (when (and (or (vector? v) (map? v) (set? v))
                                              (not property?))
                                     (env/fail! e :rf.ui.compile/collection-attr-value
                                                (str "collection value for attribute " k
                                                     " — collections are only meaningful "
                                                     "for :class/:style (React renders "
                                                     "\"[object Object]\" garbage). Pass "
                                                     "a string, e.g. (str/join \" \" xs)")
                                                {:prop k :value v}))
                                   (when (fn-form? v)
                                     (env/fail! e :rf.ui.compile/bare-fn-prop
                                                (str "bare fn at non-event prop " k " — "
                                                     "bare fns are legal only in known "
                                                     "native event properties (:on-* on "
                                                     "DOM/custom elements). Use ui/raw-fn "
                                                     "for identity-as-protocol callbacks")
                                                {:prop k}))
                                   {:k k
                                    :react-name (if property?
                                                  (rules/custom-element-property-name n)
                                                  (rules/react-prop-name n))
                                    :kind (if property? :property :attr)
                                     :value v*
                                     :literal? (literal-scalar? v)}))
                               attr-ks)
              sugar-id   (:id tag-info)
              attrs      (into (if sugar-id
                                 [{:k :id :react-name "id" :kind :attr
                                   :value sugar-id :literal? true}]
                                 [])
                               attrs)
              class-a    (when (or (seq (:classes tag-info)) (contains? m :class))
                           (analyze-class e (:classes tag-info) (get m :class)))
              style-a    (when (contains? m :style)
                           (analyze-style e (get m :style)))
               ref-a      (when (contains? m :ref) (analyze-ref e ref-form :element))
               key-form*  (if (and (contains? m :key)
                                    (not (literal-scalar? key-form)))
                            (walk-expr e [:key] key-form)
                            key-form)]
          {:key   {:present? (contains? m :key) :expr key-form*
                   :literal? (literal-scalar? key-form)}
           :class class-a
           :style style-a
           :attrs attrs
           :events events
           :spread nil
           :ref ref-a
           :property-props (into #{} (comp (filter #(= :property (:kind %))) (map :k)) attrs)
           :static? (and (not (contains? m :key))
                         (nil? ref-a)
                         (every? :literal? attrs)
                         (or (nil? class-a) (:static? class-a))
                         (or (nil? style-a) (:static? style-a))
                         (every? :hoistable? events))})))))

;; ---------------------------------------------------------------------------
;; Nodes
;; ---------------------------------------------------------------------------

(declare analyze)

(defn- node-static? [n] (boolean (:static? n)))

(defn- analyze-children [e forms]
  (into []
        (map-indexed (fn [i f] (analyze (update e :path conj i) f)))
        forms))

(defn- analyze-element [e form]
  (let [head      (nth form 0)
        tag-info  (parse-tag e head)
        tag       (:tag tag-info)
        custom?   (str/includes? (name tag) "-")
        second*   (nth form 1 nil)
        has-props (or (map? second*) (spread-form? e second*))
        props     (analyze-element-props e tag-info custom? (when has-props second*))
        child-fs  (vec (if has-props (drop 2 form) (drop 1 form)))
        html-kid? (and (= 1 (count child-fs)) (html-form? e (first child-fs)))]
    (when (and (contains? rules/children-rejected-tags tag) (seq child-fs))
      (env/fail! e :rf.ui.compile/void-children
                 (str "<" (name tag) "> cannot have children (React throws at "
                      "render; this grammar rejects earlier)")
                 {:tag tag :form form}))
    (doseq [f child-fs]
      (when (and (html-form? e f) (not html-kid?))
        (env/fail! e :rf.ui.compile/html-not-sole-child
                   (str "(ui/html ...) must be the SOLE child of a DOM element "
                        "— the React host owns trusted markup through the "
                        "parent element. Wrap it: [:div (ui/html s)] "
                        "(conservative S1 pin)")
                   {:form form})))
    (let [children (if html-kid? [] (analyze-children e child-fs))
          html-ast (when html-kid?
                     (let [[_ s & extra] (first child-fs)]
                       (when (or (nil? s) (seq extra))
                         (env/fail! e :rf.ui.compile/bad-html
                                    "(ui/html string) takes exactly one argument"
                                    {:form (first child-fs)}))
                       (when-not (or (string? s) (not (literal-scalar? s)))
                         (env/fail! e :rf.ui.compile/bad-html
                                    "(ui/html x) requires a string"
                                    {:form (first child-fs)}))
                       (let [s* (if (string? s)
                                  s
                                  (walk-expr e [:html] s))]
                       ;; Record the trusted-markup site in the compiler
                       ;; manifest (profile row `ui/html` — "manifest site
                       ;; recording"): the visible bypass carries source/
                       ;; template path so tools can list every place escaping
                       ;; is bypassed. `:serializable?` is false for a dynamic
                       ;; string expression, true for a literal.
                       (env/add-site! e :htmls {:form s*
                                                :static? (string? s)
                                                :serializable? (string? s)
                                                :path (:path e)})
                       {:op :html :form s* :static? (string? s)})))]
      {:op :element
       :tag tag
       :custom? custom?
       :void? (contains? rules/void-tags tag)
       :props props
       :html html-ast
       :children children
       :static? (and (:static? props)
                     (nil? (:spread props))
                     (if html-kid? (:static? html-ast) (every? node-static? children))
                     true)
       :path (:path e)})))

(defn- analyze-component-props
  "View/foreign call-site props (Q2/Q3/Q4): literal map required; :key
  extracted (never a prop); :children as an explicit key rejected
  (children are positional); literal fn values rejected (the narrow
  bare-fn law: not arbitrary fn-valued props)."
  [e head-info props-form]
  (when (and (some? props-form) (not (map? props-form)))
    (env/fail! e :rf.ui.compile/dynamic-props-map
               (str "component call sites take a LITERAL props map — a wholly-"
                    "dynamic props expression is not v1 grammar (conservative "
                    "S1 pin; ui/spread converts dynamic maps for DOM elements "
                    "only)")
               {:head (:sym head-info) :form props-form}))
  (let [m (or props-form {})]
    (doseq [k (keys m)]
      (when-not (keyword? k)
        (env/fail! e :rf.ui.compile/non-keyword-prop
                   (str "prop keys must be literal keywords; got " (pr-str k))
                   {:key k}))
      (when (= k :children)
        (env/fail! e :rf.ui.compile/children-prop
                   (str ":children as an explicit prop at a call site — "
                        "children are positional ([view {...} child1 child2]) "
                        "and arrive as :children on the definition side. One "
                        "spelling per concept")
                   {:head (:sym head-info)})))
    (let [key-form (get m :key)
          ref-a    (when (contains? m :ref)
                     (analyze-ref e (get m :ref)
                                  (if (= :view (:kind head-info)) :view :foreign)))
          m*       (dissoc m :key :ref)
          entries  (mapv (fn [[k v]]
                           (when (fn-form? v)
                             (env/fail! e :rf.ui.compile/bare-fn-prop
                                        (str "bare fn prop " k " at a "
                                             (if (= :view (:kind head-info))
                                               "view" "foreign-component")
                                             " boundary — invoker and phase are "
                                             "unknown there. Choose ui/raw-fn "
                                             "(identity-as-protocol) now; "
                                             "ui/event / ui/handler / ui/render-fn "
                                             "land S3")
                                        {:prop k :head (:sym head-info)}))
                           ;; NOTE: vector/data props MAY capture loop bindings —
                           ;; passing row data into a keyed child view is exactly
                           ;; the extract-a-keyed-child-view fix; only HANDLER
                           ;; sites are capture-checked.
                            (let [raw?    (raw-form? e v)
                                  raw-fn? (raw-fn-form? e v)
                                  v*      (cond
                                            raw? (walk-expr e [:component-prop k :raw]
                                                            (second v))
                                            raw-fn? (walk-expr e [:component-prop k :raw-fn]
                                                               (second v))
                                            (literal-scalar? v) v
                                            :else (walk-expr e [:component-prop k] v))]
                              {:k k
                               :slot (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k))
                               :value v*
                               :marker (cond raw? :foreign raw-fn? :ui/raw-fn :else nil)
                               :literal? (literal-scalar? v)}))
                          m*)]
      (let [key-form* (if (and (contains? m :key) (not (literal-scalar? key-form)))
                        (walk-expr e [:component-key] key-form)
                        key-form)]
      {:key {:present? (contains? m :key) :expr key-form*
             :literal? (literal-scalar? key-form)}
       :entries entries
       :ref ref-a}))))

(defn- analyze-component [e form]
  (let [head      (nth form 0)
        info      (env/classify-head e head)
        second*   (nth form 1 nil)
        has-props (map? second*)
        props     (analyze-component-props e info (when has-props second*))
        child-fs  (vec (if has-props (drop 2 form) (drop 1 form)))
        ;; a view/foreign boundary ENDS the static top region (S1c):
        ;; frame-root below it is a compile error
        children  (analyze-children (dissoc e :top-region?) child-fs)]
    (when (and (= :view (:kind info)) (seq children))
      (let [meta*      (:meta (env/resolve-sym e head))
            self?      (and (:self e) (= head (:self e)))
            children-ok? (if self?
                           (:self-children? e)
                           (:rf.ui/children? meta*))]
        (when-not children-ok?
          (env/fail! e :rf.ui.compile/children-not-accepted
                     (str "view " (:view-id info) " does not accept children — "
                          "it declares none (:children in the header "
                          "destructuring, an :as binding, or a [:children ...] "
                          "schema entry would declare them). Q4 pin")
                     {:head head}))))
    (when (= :view (:kind info))
      ;; Q2 closed-map: :props present => closed — literal call-site keys
      ;; must be declared
      (let [meta*  (:meta (env/resolve-sym e head))
            self?  (and (:self e) (= head (:self e)))
            closed (if self? (:self-closed-keys e) (:rf.ui/closed-prop-keys meta*))]
        (when closed
          (let [allowed (set closed)
                bad     (remove #(or (allowed %) (= :key %)) (map :k (:entries props)))]
            (when (seq bad)
              (env/fail! e :rf.ui.compile/undeclared-prop
                         (str "undeclared prop" (when (next bad) "s") " "
                              (str/join ", " (map pr-str bad)) " passed to "
                              (:view-id info) " — its :props schema closes the "
                              "map (declared: " (str/join ", " (map pr-str closed))
                              "). Q2 pin: absent :props = open, present :props "
                              "= closed")
                         {:head head :undeclared (vec bad)}))))))
    {:op (if (= :view (:kind info)) :view :foreign)
     :sym head
     :fqn (:fqn info)
     :view-id (:view-id info)
     :props props
     :children children
     :static? false
     :path (:path e)}))

;; ---------------------------------------------------------------------------
;; frame-root (S1c, rf2-vxgfnd.3) — the static ENSURE-plan position
;; ---------------------------------------------------------------------------
;;
;; The STATIC TOP REGION of a root form (root-identity contract §6) is every
;; node reachable from the root without crossing a control form, a dynamic
;; expression, an internal-view boundary, or a foreign component. The walk
;; carries `:top-region? true` (set only by the root-form entry points —
;; `ui/mount` / `ui/render!` / `ui/hydrate-root`); DOM elements, fragments
;; and frame-root itself PRESERVE the flag for their children, and every
;; other position clears it. `frame-root` is legal exactly while the flag
;; holds — plans are static identity, extracted at compile time.

(defn- frame-root-head? [e head]
  (and (symbol? head)
       (not (contains? (:locals e) head))
       (env/resolves-to? e head frame-root-fqns)))

(defn- analyze-frame-root [e form]
  (when-not (:top-region? e)
    (env/fail! e :rf.ui.compile/frame-root-misplaced
               (str "frame-root sits outside the static top region of a root "
                    "form — ENSURE plans are static identity, extracted at "
                    "compile time. Legal positions: the root form handed to "
                    "ui/mount / ui/render! / ui/hydrate-root, nested only "
                    "under unconditional wrappers (DOM elements, fragments, "
                    "frame-root) — never under a control form, inside a "
                    "view, or in a defview template (frames are ambient "
                    "inside views)")
               {:form form}))
  (let [props (nth form 1 nil)]
    (when-not (map? props)
      (env/fail! e :rf.ui.compile/bad-frame-root
                 (str "frame-root takes a literal props map: "
                      "[frame-root {:id :frame-id ...} children...]")
                 {:form form}))
    (when (contains? props :frame)
      (env/fail! e :rf.ui.compile/bad-frame-root
                 (str "frame-root ENSURES a frame by :id — :frame is "
                      "frame-provider's scope key (providers scope, roots "
                      "ensure; the rf2-nyea0r split)")
                 {:form form}))
    (let [id (:id props)]
      (when-not (keyword? id)
        (env/fail! e :rf.ui.compile/bad-frame-root
                   (str "frame-root :id must be a compile-time literal "
                        "keyword — plans are static identity; got "
                        (pr-str id))
                   {:form form :id id}))
      (let [config (not-empty (dissoc props :id))
            config* (when config
                      (with-meta
                        (into (empty config)
                              (map (fn [[k v]]
                                     [k (walk-expr e [:frame-root :config k] v)]))
                              config)
                        (meta config)))]
        ;; config values are opaque runtime expressions (evaluated at
        ;; preflight) — walk them for sub/lease site indexing only
        {:op :frame-root
         :frame-id id
         :config config*
         :children (analyze-children e (vec (drop 2 form)))
         :static? false
         :path (:path e)}))))

;; ---------------------------------------------------------------------------
;; frame-provider (S2c, rf2-vxgfnd.9) — the SCOPE form
;; ---------------------------------------------------------------------------
;;
;; frame-provider scopes a subtree to an ALREADY-LIVE frame through the
;; shared React context — it is legal ANYWHERE a template form is (defview
;; templates and root forms), unlike the static-top-region-only `frame-root`.
;; Its `:frame` is a RUNTIME frame TARGET (a frame-id keyword OR a live frame
;; value), so provider-scoped frames are not statically extractable and
;; contribute NO plan to the Root Descriptor (root-identity contract §6). In
;; a root form's top region the walk descends THROUGH a frame-provider (it
;; preserves `:top-region?` for its children), so a nested `frame-root` plan
;; is still extracted; the provider itself just scopes.

(defn- frame-provider-head? [e head]
  (and (symbol? head)
       (not (contains? (:locals e) head))
       (env/resolves-to? e head frame-provider-fqns)))

(defn- analyze-frame-provider [e form]
  (let [props (nth form 1 nil)]
    (when-not (map? props)
      (env/fail! e :rf.ui.compile/bad-frame-provider
                 (str "frame-provider takes a literal props map: "
                      "[frame-provider {:frame f} children...]")
                 {:form form}))
    (when (contains? props :id)
      (env/fail! e :rf.ui.compile/bad-frame-provider
                 (str "frame-provider SCOPES an already-live frame by :frame — "
                      ":id is frame-root's ENSURE key (roots ensure, providers "
                      "scope; the rf2-nyea0r split). Use frame-root {:id ...} to "
                      "create-if-absent")
                 {:form form}))
    (when-not (contains? props :frame)
      (env/fail! e :rf.ui.compile/bad-frame-provider
                 (str "frame-provider requires :frame — the already-live frame "
                      "target (a frame-id keyword or a live frame value) to "
                      "scope the subtree to")
                 {:form form}))
    (let [extra (dissoc props :frame)]
      (when (seq extra)
        (env/fail! e :rf.ui.compile/bad-frame-provider
                   (str "frame-provider takes only {:frame f}; got extra key"
                        (when (next extra) "s") " "
                        (str/join ", " (map pr-str (keys extra)))
                        " — providers only scope (roots ensure)")
                   {:form form})))
    (let [target (:frame props)
          target* (if (literal-scalar? target)
                    target
                    (walk-expr e [:frame-provider :frame] target))]
      ;; the :frame target is a runtime expression — walk it for sub/lease
      ;; site indexing (a sub in the target expression is still a site)
      {:op :frame-provider
       :frame target*
       ;; children keep whatever region flag `e` carries: preserved in a
       ;; root form's top region (nested frame-root plans still extract),
       ;; absent in a defview template
       :children (analyze-children e (vec (drop 2 form)))
       :static? false
       :path (:path e)})))

(defn- analyze-fragment [e form]
  (let [second*   (nth form 1 nil)
        has-props (map? second*)
        _         (when has-props
                    (let [extra (dissoc second* :key)]
                      (when (seq extra)
                        (env/fail! e :rf.ui.compile/bad-fragment-props
                                   (str "fragments take only {:key ...}; got "
                                        (str/join ", " (map pr-str (keys extra))))
                                   {:form form}))))
        key-form  (when has-props (get second* :key))
        key-form* (if (and has-props (not (literal-scalar? key-form)))
                    (walk-expr e [:fragment :key] key-form)
                    key-form)
        children  (analyze-children e (vec (if has-props (drop 2 form) (drop 1 form))))]
    {:op :fragment
     :key {:present? has-props :expr key-form* :literal? (literal-scalar? key-form)}
     :children children
     :static? (and (not has-props) (every? node-static? children))
     :path (:path e)}))

;; ---------------------------------------------------------------------------
;; Control forms
;; ---------------------------------------------------------------------------

(defn- single-body! [e head body form]
  (when (not= 1 (count body))
    (env/fail! e :rf.ui.compile/multi-form-body
               (str head " in template position takes exactly ONE body form — "
                    "side effects don't belong in templates (statically-pure "
                    "rule); siblings wrap in [:<> ...]")
               {:form form}))
  (first body))

(defn- analyze-if [e test then else form]
  {:op :if :test (walk-expr e [:if :test] test)
   :then (analyze (update e :path conj :then) then)
   :else (if (some? else)
           (analyze (update e :path conj :else) else)
           {:op :nothing :static? true})
   :static? false :path (:path e)})

(defn- analyze-cond [e clauses form]
  (cond
    (empty? clauses) {:op :nothing :static? true}
    (odd? (count clauses))
    (env/fail! e :rf.ui.compile/bad-cond
               "cond needs test/branch pairs: (cond test1 branch1 ... :else fallback)"
               {:form form})
    :else
    (let [[t b & more] clauses]
      (if (= :else t)
        (analyze e b)
        (analyze-if e t b (when (seq more) (cons 'cond more)) form)))))
  ;; note: the recursive else is re-entered through analyze-list as (cond ...)

(defn- analyze-case [e form]
  (let [[_ expr & clauses] form]
    (let [default? (odd? (count clauses))
          pairs    (partition 2 (if default? (butlast clauses) clauses))
          default  (when default? (last clauses))]
      {:op :case
       :expr (walk-expr e [:case :expr] expr)
       :clauses (into []
                      (map-indexed (fn [i [test branch]]
                                     [test (analyze (update e :path conj [:case i]) branch)]))
                      pairs)
       :default (if default?
                  (analyze (update e :path conj :case-default) default)
                  ::none)
       :static? false
       :path (:path e)})))

(defn- analyze-let [e form]
  (let [[head bindings & body] form]
    (when-not (and (vector? bindings) (even? (count bindings)))
      (env/fail! e :rf.ui.compile/bad-let
                 (str head " needs an even bindings vector") {:form form}))
    (let [pairs (partition 2 bindings)
          [e* rewritten]
          (reduce (fn [[acc out] [i [pat init]]]
                    [(env/with-locals acc (env/binding-syms pat))
                     (conj out pat
                           (walk-expr acc [:let :binding i] init))])
                  [e []]
                  (map-indexed vector pairs))
          bindings* (with-meta (vec rewritten) (meta bindings))
          body-form (single-body! e (str head) body form)]
      {:op :let :bindings bindings*
       :body (analyze (update e* :path conj :body) body-form)
       :static? false :path (:path e)})))

(defn- analyze-letfn [e form]
  (let [[_ fnspecs & body] form]
    (when-not (vector? fnspecs)
      (env/fail! e :rf.ui.compile/bad-let "letfn needs a fnspecs vector" {:form form}))
    (let [names (map first fnspecs)
          e*    (env/with-locals e names)
          ;; Reuse the opaque rewriter's exact letfn scoping (all names in
          ;; scope for every body; each argv shadows vars within its arity).
          fnspecs* (second
                     (walk-expr e [:letfn :bindings]
                                (list 'letfn fnspecs nil)))]
      (let [body-form (single-body! e "letfn" body form)]
        {:op :letfn :fnspecs fnspecs*
         :body (analyze (update e* :path conj :body) body-form)
         :static? false :path (:path e)}))))

(defn- analyze-for [e form]
  (let [[_ seq-exprs & body] form]
    (when-not (and (vector? seq-exprs) (even? (count seq-exprs)) (seq seq-exprs))
      (env/fail! e :rf.ui.compile/bad-for
                 "(for [pattern coll ...modifiers] body) needs a non-empty, even seq-exprs vector"
                 {:form form}))
    (let [pairs (partition 2 seq-exprs)]
      (when (keyword? (ffirst pairs))
        (env/fail! e :rf.ui.compile/bad-for
                   "for needs a binding pair before modifiers" {:form form}))
      ;; Rewrite in evaluation order. The first collection evaluates once per
      ;; view render; after its binding every later collection/modifier is
      ;; row-scoped and therefore rejects sub/lease sites.
      (let [[e-final rewritten]
            (loop [scope e
                   first-coll? true
                   ps (seq (map-indexed vector pairs))
                   out []]
              (if-let [[i [l r]] (first ps)]
                (cond
                  (= :let l)
                  (do
                    (when-not (and (vector? r) (even? (count r)))
                      (env/fail! e :rf.ui.compile/bad-for
                                 ":let modifier needs an even bindings vector"
                                 {:form form}))
                    (let [[scope* binds*]
                          (reduce (fn [[s xs] [j [pat init]]]
                                    [(env/with-loop s (env/binding-syms pat))
                                     (conj xs pat
                                           (walk-expr (assoc s :in-loop? true)
                                                      [:for i :let j] init))])
                                  [scope []]
                                  (map-indexed vector (partition 2 r)))]
                      (recur scope* false (next ps)
                             (conj out l (with-meta (vec binds*) (meta r))))))

                  (contains? #{:when :while} l)
                  (recur scope false (next ps)
                         (conj out l
                               (walk-expr (assoc scope :in-loop? true)
                                          [:for i l] r)))

                  (keyword? l)
                  (env/fail! e :rf.ui.compile/bad-for
                             (str "unknown for modifier " l " — the subgrammar is "
                                  ":let / :when / :while (Q6 pin)")
                             {:form form})

                  :else
                  (let [r* (walk-expr (if first-coll?
                                        scope
                                        (assoc scope :in-loop? true))
                                      [:for i :collection] r)]
                    (recur (env/with-loop scope (env/binding-syms l))
                           false (next ps) (conj out l r*))))
                [scope out]))
            seq-exprs* (with-meta (vec rewritten) (meta seq-exprs))
            e-body    (update e-final :path conj :for)
            body-form (single-body! e "for" (vec body) form)
            _         (when (and (seq? body-form) (= 'for (first body-form)))
                        (env/fail! e :rf.ui.compile/nested-for-body
                                   (str "a for directly inside a for body — express "
                                        "nested iteration as multiple binding pairs in "
                                        "ONE for: (for [x xs, y (f x)] ...) — one keyed "
                                        "list site (Q6 pin)")
                                   {:form form}))
            body-ast  (analyze e-body body-form)]
        (when-not (contains? #{:element :view :foreign :fragment} (:op body-ast))
          (env/fail! e :rf.ui.compile/unkeyed-list-item
                     (str "for body must be a keyed element/view/fragment — got "
                          (name (:op body-ast)) ". Keyed lists compile to direct "
                          "JS arrays; missing key = build failure")
                     {:form form}))
        (let [k (get-in body-ast [:props :key] (get body-ast :key))]
          (when-not (:present? k)
            (env/fail! e :rf.ui.compile/unkeyed-list-item
                       (str "missing :key on the for body — every list row needs "
                            "a literal :key prop (unkeyed list items are a build "
                            "failure)")
                       {:form form}))
          (when (:literal? k)
            (env/fail! e :rf.ui.compile/constant-list-key
                       (str "constant :key " (pr-str (:expr k)) " in a list — a "
                            "key must vary per row (a constant key guarantees "
                            "duplicates). Key on row data: {:key (:id x)}")
                       {:form form})))
        {:op :for
         :seq-exprs seq-exprs*
         :body body-ast
         :static? false
         :path (:path e)}))))

;; ---------------------------------------------------------------------------
;; Dispatch
;; ---------------------------------------------------------------------------

(defn- analyze-list [e form]
  (let [head (first form)]
    (if (and (symbol? head)
             (contains? control-heads head)
             (not (contains? (:locals e) head)))
      (case head
        if       (let [[_ t a b & extra] form]
                   (when (seq extra)
                     (env/fail! e :rf.ui.compile/bad-if "if takes test then else?" {:form form}))
                   (analyze-if e t a b form))
        if-not   (let [[_ t a b & extra] form]
                   (when (seq extra)
                     (env/fail! e :rf.ui.compile/bad-if "if-not takes test then else?" {:form form}))
                   (analyze-if e (list 'not t) a b form))
        when     (let [[_ t & body] form]
                   (analyze-if e t (single-body! e "when" (vec body) form) nil form))
        when-not (let [[_ t & body] form]
                   (analyze-if e (list 'not t) (single-body! e "when-not" (vec body) form) nil form))
        cond     (analyze-cond e (rest form) form)
        case     (analyze-case e form)
        let      (analyze-let e form)
        letfn    (analyze-letfn e form)
        do       (analyze e (single-body! e "do" (vec (rest form)) form))
        for      (analyze-for e form))
      (cond
        (raw-form? e form)
        (let [[_ x & extra] form]
          (when (or (nil? x) (seq extra))
            (env/fail! e :rf.ui.compile/bad-raw "(ui/raw react-element) takes one argument"
                       {:form form}))
          {:op :raw :form (walk-expr e [:raw] x)
           :static? false :path (:path e)})

        (html-form? e form)
        (env/fail! e :rf.ui.compile/html-not-sole-child
                   (str "(ui/html ...) must be the sole child of a DOM element "
                        "— here it has no host element to own the markup. Wrap "
                        "it: [:div (ui/html s)] (conservative S1 pin)")
                   {:form form})

        (raw-fn-form? e form)
        (env/fail! e :rf.ui.compile/raw-fn-child
                   "(ui/raw-fn f) is a callback marker for prop positions, not renderable content"
                   {:form form})

        (spread-form? e form)
        (env/fail! e :rf.ui.compile/bad-spread
                   "(ui/spread ...) belongs in a DOM element's props position: [:div (ui/spread base overrides)]"
                   {:form form})

        (and (symbol? head) (not (contains? (:locals e) head))
             (env/resolves-to? e head markup-map-fqns))
        (env/fail! e :rf.ui.compile/markup-returning-map
                   (str "(" head " f coll) in child position — markup-returning "
                        "map is rejected: it hides keys and laziness. Use "
                        "(for [x coll] [row {:key (:id x)}])")
                   {:form form})

        (and (symbol? head) (not (contains? (:locals e) head))
             (env/resolves-to? e head lazy-seq-fqns))
        (env/fail! e :rf.ui.compile/lazy-seq-child
                   (str "(" head " ...) in child position produces a raw seq — "
                        "raw lazy seqs are rejected: they hide keys and "
                        "laziness. Render rows with (for [x coll] "
                        "[row {:key (:id x)}]); render text with "
                        "(str/join ...)")
                   {:form form})

        :else
        {:op :expr :form (walk-expr e [:expr] form)
         :static? false :path (:path e)}))))

(defn analyze
  "Template form -> AST node."
  [e form]
  (cond
    (string? form)  {:op :text :value form :static? true}
    (number? form)  {:op :text :value (rules/js-number-str form) :static? true}
    (nil? form)     {:op :nothing :static? true}
    (boolean? form) {:op :nothing :static? true}
    (keyword? form) (env/fail! e :rf.ui.compile/keyword-child
                               (str "keyword " form " in child position — keywords are "
                                    "element heads, not content. String content wants "
                                    (pr-str (name form)))
                               {:form form})
    (vector? form)
    (let [head (nth form 0 nil)]
      (cond
        (= :<> head)     (analyze-fragment e form)
        (keyword? head)  (analyze-element e form)
        (frame-root-head? e head) (analyze-frame-root e form)
        (frame-provider-head? e head) (analyze-frame-provider e form)
        (symbol? head)   (analyze-component e form)
        :else
        (env/fail! e :rf.ui.compile/dynamic-head
                   (str "dynamic element head " (pr-str head) " — heads must be "
                        "literal (a keyword or a component var). Runtime-chosen "
                        "components are ui/view / ui/element [WAVE-2]; ui/raw "
                        "covers a runtime React element meanwhile")
                   {:form form})))
    ;; a control form / opaque expression ends the static top region (S1c)
    (seq? form)     (analyze-list (dissoc e :top-region?) form)
    (symbol? form)  {:op :expr :form (walk-expr e [:expr] form)
                     :static? false :path (:path e)}
    :else
    (env/fail! e :rf.ui.compile/unsupported-form
               (str "unsupported template form " (pr-str form) " of type "
                    (type form) " — template content is strings/numbers/"
                    "nil/false, [:tag ...] vectors, control forms, and "
                    "expressions. Render a value as text with (str ...)")
               {:form form})))
