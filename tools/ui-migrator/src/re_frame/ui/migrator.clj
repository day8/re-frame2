(ns re-frame.ui.migrator
  "W1 - the Reagent -> re-frame.ui mechanical migrator (doc-10 rewriter).

  Implements the canonical MIG-01..35 rule table
  (ai/findings/new-substrate-synthesis/prep/w1-migrator-rule-table.md) as a
  scanner + conservative codemod over SOURCE TEXT via rewrite-clj. re-frame2 is
  never loaded; the tool runs against any Reagent corpus on a bare JVM.

  ---------------------------------------------------------------------------
  ARCHITECTURE (the rule table's Ordering, made operational)
  ---------------------------------------------------------------------------

  * The unit of migration is the WHOLE VIEW (Ordering 1). Each view candidate
    (a Form-1 `defn`/`defn-` whose tail is literal hiccup in every branch, or a
    `reg-view`/`reg-view*`) is GATE-scanned first: if any GATING rule hits
    (MIG-16/17/20/21/22/30/32/35 and the D cases of MIG-03/08/10/13/18/19/29/31,
    the residual net), the view is left unconverted on the compat tier and its
    findings reported. No half-migrated bodies.

  * A CLEAN view is rewritten: its header (`defn`->`ui/defview`, positional
    params -> one map destructure), its body (the mechanical M rules), and its
    call sites everywhere in the file (`[view a b]` / `(view a b)` -> `[view {..}]`)
    atomically (MIG-01).

  * Root?, call-site?, ns?, and dataflow-level rules (MIG-15/23/24/25/26/33)
    never gate a view body and run globally.

  * D-tier output is a FLAG carrying the prepared flag text (rules/registry),
    never an auto-rewrite - except the two rules the table designates non-gating
    rewrite+flag (MIG-28 `ui/spread`), which do rewrite.

  * Idempotent (Ordering 4): `ui/defview` bodies and already-pinned spellings
    are not candidates, so re-running over migrated code is a no-op.

  ---------------------------------------------------------------------------
  STAGED TARGETS (re-verified against implementation/ui/src/re_frame/ui.cljc)
  ---------------------------------------------------------------------------
  Every rewrite target is a shipped export EXCEPT the SSR serialisation path
  (`re-frame.ssr/emit-ui-tree`, S5 - MIG-23 stays a flag) and the outward
  `ui/->react` bridge (S6 - MIG-22 outward / Ordering 2). `ui/sub` is arity-1,
  so MIG-03 (explicit-frame ops) stays a flag; `(frame)`, `local`, `effect`,
  `event`, `handler`, `render-fn`, `dispatch-fn`, `raw-fn`, `spread`, `html`,
  `mount`, `frame-root`, `adapter` are all exported and used as targets.

  ---------------------------------------------------------------------------
  PROGRAMMATIC API
  ---------------------------------------------------------------------------
    (scan-string s opts)    -> [finding ...]
    (scan-file   path)      -> [finding ...]
    (scan-paths  paths)     -> [finding ...]
    (rewrite-string s opts) -> {:source out :findings [...]}
    (rewrite-file! path opts) -> {:path .. :changed? .. :findings [...]}
    (rewrite-paths! paths opts)

  A finding is a map:
    {:file str|nil :line int|nil :col int|nil
     :rule \"MIG-04\" :tier :M|:D|:R :action :rewrite|:flag|:reject
     :held? bool          ;; detected in a gated view; not applied
     :note \"prepared flag text (D/R) or short description (M)\"
     :suggest \"suggested rewrite text or nil\"}"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [re-frame.ui.migrator.resolve :as r]
            [re-frame.ui.migrator.rules :as rules]))

;; ---------------------------------------------------------------------------
;; node helpers
;; ---------------------------------------------------------------------------

(defn- ->sexpr [node] (try (n/sexpr node) (catch Exception _ ::err)))

(defn- sig-children
  "Non-whitespace, non-comment children of a node."
  [node]
  (filter (complement n/whitespace-or-comment?) (n/children node)))

(defn- tag= [node t] (and node (= t (n/tag node))))

(defn- token-sym
  "The symbol value of a :token node, or nil."
  [node]
  (when (tag= node :token)
    (let [v (->sexpr node)] (when (symbol? v) v))))

(defn- token-kw
  "The keyword value of a :token node, or nil."
  [node]
  (when (tag= node :token)
    (let [v (->sexpr node)] (when (keyword? v) v))))

(defn- list-head
  "Head child node of a :list node."
  [node]
  (when (tag= node :list) (first (sig-children node))))

(defn- list-head-sym [node] (some-> (list-head node) token-sym))

(defn- spaces1 [] (n/spaces 1))

;; ---------------------------------------------------------------------------
;; framework symbol recognition (through the ns resolver)
;; ---------------------------------------------------------------------------

(def ^:private rf-op-names #{"subscribe" "dispatch" "dispatch-sync"})

(defn- resolves [env sym targets]
  (r/resolves-to? env sym targets))

(defn- rf-op-kind
  "Return :subscribe / :dispatch / :dispatch-sync if `sym` is a re-frame.core op
  (resolved, or a bare injected/referred name inside a view body), else nil."
  [env sym]
  (when (symbol? sym)
    (let [q (r/resolve-sym env sym)
          nm (name sym)]
      (cond
        (= q "re-frame.core/subscribe") :subscribe
        (= q "re-frame.core/dispatch") :dispatch
        (= q "re-frame.core/dispatch-sync") :dispatch-sync
        ;; bare injected/referred op name (reg-view body, or :refer'd)
        (and (nil? (namespace sym)) (rf-op-names nm)) (keyword nm)
        :else nil))))

;; canonical target sets used by detectors
(defn- reagent-sym? [env sym names]
  (or (resolves env sym (into #{} (map #(str "reagent.core/" %)) names))
      (and (nil? (namespace sym)) (contains? names (name sym)))))

(defn- subtree-calls-op?
  "Does any nested form in `sexpr` call one of `op-names` (matched by simple
  name, so `rf/dispatch` and a bare `dispatch` both hit)-"
  [op-names sexpr]
  (boolean (some (fn [f] (and (seq? f) (symbol? (first f))
                              (contains? op-names (name (first f)))))
                 (tree-seq coll? seq sexpr))))

;; ---------------------------------------------------------------------------
;; finding construction
;; ---------------------------------------------------------------------------

(defn- mk
  "Build a finding for `rule-id`. `extra` may override :action/:note/:suggest and
  add :transform (a node->node fn) for M rewrites."
  ([rule-id] (mk rule-id {}))
  ([rule-id extra]
   (merge {:rule    rule-id
           :tier    (rules/tier rule-id)
           :gating? (rules/gating? rule-id)
           :action  (rules/action-for rule-id)
           :note    (or (rules/note rule-id) (rules/desc rule-id))
           :suggest (rules/suggest rule-id)
           :transform nil}
          extra)))

;; ---------------------------------------------------------------------------
;; MIG-11 - DOM prop name respelling (React published names -> pinned kebab)
;; ---------------------------------------------------------------------------

(def ^:private prop-name-table
  "camelCase / alias DOM prop spelling -> pinned kebab spelling. Table-driven
  from React's published event/attr names (pinned to react-dom 19.2.0, the
  004B S1b probe release; Open items 6). Unknown names pass verbatim."
  {"onClick" "on-click" "onChange" "on-change" "onInput" "on-input"
   "onSubmit" "on-submit" "onKeyDown" "on-key-down" "onKeyUp" "on-key-up"
   "onKeyPress" "on-key-press" "onFocus" "on-focus" "onBlur" "on-blur"
   "onMouseEnter" "on-mouse-enter" "onMouseLeave" "on-mouse-leave"
   "onMouseDown" "on-mouse-down" "onMouseUp" "on-mouse-up"
   "onDoubleClick" "on-double-click" "onScroll" "on-scroll"
   "className" "class" "htmlFor" "for" "tabIndex" "tab-index"
   "viewBox" "view-box" "readOnly" "read-only" "autoFocus" "auto-focus"
   "autoComplete" "auto-complete" "maxLength" "max-length"
   "minLength" "min-length" "colSpan" "col-span" "rowSpan" "row-span"
   ;; lowercase event forms respell through the same name table
   "onkeydown" "on-key-down" "onkeyup" "on-key-up"
   ;; :class-name / :html-for aliases are COMPILE ERRORS (one spelling per name)
   "class-name" "class" "html-for" "for"})

(defn- respell-key
  "If keyword `kw` names a respellable DOM prop, return the pinned keyword, else
  nil. `:dangerouslySetInnerHTML` is deliberately excluded (MIG-34)."
  [kw]
  (when (and (keyword? kw) (nil? (namespace kw)))
    (let [nm (name kw)]
      (when-not (= nm "dangerouslySetInnerHTML")
        (some-> (get prop-name-table nm) keyword)))))

;; ---------------------------------------------------------------------------
;; handler-fn shape analysis (MIG-04/05/06/18)
;; ---------------------------------------------------------------------------

(defn- fn-literal-shape
  "If `node` is a `#(...)` (:fn) or `(fn [..] ..)` handler, return
  {:kind :fn|:fn-form :body [body-nodes] :params param-vec-node|nil}, else nil."
  [node]
  (cond
    ;; `#(...)` - a :fn node holds its body content FLAT (the reader-implicit
    ;; call wrapper is not a node child), so reconstruct the single body form.
    (tag= node :fn)
    {:kind :fn :params nil :body [(n/list-node (vec (n/children node)))]}

    (and (tag= node :list) (= 'fn (list-head-sym node)))
    (let [kids (sig-children node)
          after (rest kids)
          named? (and (seq after) (token-sym (first after)))
          after (if named? (rest after) after)
          params (first after)
          body (rest after)]
      (when (tag= params :vector)
        {:kind :fn-form :params params :body (vec body)}))
    :else nil))

(def ^:private extraction-placeholders
  "Recognised %-extraction sexpr shape -> placeholder keyword (MIG-05)."
  {'value :rf.ui/value 'checked :rf.ui/checked 'key :rf.ui/key})

(defn- extraction->placeholder
  "If sexpr `form` is a recognised event-target extraction over param `p`, return
  the placeholder keyword, else nil. Covers `(-> p .-target .-value)`,
  `(.. p -target -value)`, `(.-value (.-target p))`, `(.-checked (.-target p))`,
  `(.-key p)`."
  [form p]
  (when (seq? form)
    (let [f (vec form)]
      (cond
        ;; (-> p .-target .-value) / (-> p .-target .-checked)
        (and (= '-> (first f)) (= p (second f))
             (= '.-target (nth f 2 nil)) (symbol? (nth f 3 nil)))
        (get extraction-placeholders (symbol (subs (name (nth f 3)) 2)))
        ;; (.. p -target -value)
        (and (= '.. (first f)) (= p (second f))
             (= '-target (nth f 2 nil)) (symbol? (nth f 3 nil)))
        (get extraction-placeholders (symbol (subs (name (nth f 3)) 1)))
        ;; (.-value (.-target p))
        (and (symbol? (first f)) (str/starts-with? (name (first f)) ".-")
             (seq? (second f)) (= '.-target (first (second f)))
             (= p (second (second f))))
        (get extraction-placeholders (symbol (subs (name (first f)) 2)))
        ;; (.-key p)
        (and (symbol? (first f)) (str/starts-with? (name (first f)) ".-")
             (= p (second f)) (nil? (nth f 2 nil)))
        (get extraction-placeholders (symbol (subs (name (first f)) 2)))
        :else nil))))

(defn- dispatch-call-sexpr?
  "Is sexpr `form` a `(dispatch [literal-vector])` with env-resolvable dispatch
  and a LITERAL vector arg (no explicit-frame opts map)- Returns the arg vector
  sexpr, or nil."
  [env form]
  (when (and (seq? form) (>= (count form) 2))
    (let [hd (first form)
          kind (rf-op-kind env hd)]
      (when (and (#{:dispatch :dispatch-sync} kind)
                 (= 2 (count form))          ;; no opts map => not MIG-03
                 (vector? (second form)))
        (second form)))))

(defn- classify-on-handler
  "Classify a fn-literal `node` used in an :on-* DOM position. Returns a finding
  (MIG-04/05/06/18) with a :transform that produces the replacement VALUE node."
  [env node]
  (let [shape (fn-literal-shape node)
        body0 (:body shape)
        ;; a single `(do stmt..)` body (typical of `#(do (.preventDefault %) ..)`)
        ;; unwraps to its statements so the MIG-06 multi-statement proof applies
        body  (if (and (= 1 (count body0))
                       (= 'do (list-head-sym (first body0))))
                (vec (rest (sig-children (first body0))))
                body0)
        one   (when (= 1 (count body)) (first body))
        one-s (when one (->sexpr one))
        param (cond
                (= :fn (:kind shape)) '%
                (:params shape) (some-> (first (sig-children (:params shape))) token-sym)
                :else nil)
        uses-param? (fn [form] (boolean (and param (some #{param} (tree-seq coll? seq form)))))]
    (cond
      (nil? shape)
      nil

      ;; MIG-06: (fn [e] (.preventDefault e) ... (dispatch [lit]))
      (let [ss (map ->sexpr body)
            wraps (filter #(and (seq? %) (#{'.preventDefault '.stopPropagation} (first %))) ss)
            disp  (filter #(dispatch-call-sexpr? env %) ss)]
        (and (seq wraps) (= 1 (count disp)) (= (count body) (+ (count wraps) (count disp)))))
      (let [ss   (map ->sexpr body)
            ev   (some #(dispatch-call-sexpr? env %) ss)
            prevent? (some #(and (seq? %) (= '.preventDefault (first %))) ss)
            stop?    (some #(and (seq? %) (= '.stopPropagation (first %))) ss)
            opts (cond-> [(n/keyword-node :event) (spaces1) (n/coerce ev)]
                   prevent? (into [(spaces1) (n/keyword-node :prevent-default) (spaces1) (n/token-node true)])
                   stop?    (into [(spaces1) (n/keyword-node :stop-propagation) (spaces1) (n/token-node true)]))]
        (mk "MIG-06" {:transform (fn [_] (n/map-node opts))}))

      ;; MIG-04: single form, (dispatch [lit]), params unused (capture-free)
      (and one (dispatch-call-sexpr? env one-s)
           (not (uses-param? (dispatch-call-sexpr? env one-s))))
      (let [ev (dispatch-call-sexpr? env one-s)]
        (mk "MIG-04" {:transform (fn [_] (n/coerce ev))}))

      ;; MIG-05: single (dispatch [id ... extraction ...]) using the param
      (and one (seq? one-s) (rf-op-kind env (first one-s))
           (= 2 (count one-s)) (vector? (second one-s)))
      (let [vecf  (vec (second one-s))
            ;; replace each top-level extraction over `param` with its placeholder
            replaced (mapv (fn [el]
                             (or (when param (extraction->placeholder el param)) el))
                           vecf)
            changed? (not= replaced vecf)
            ;; every non-first element that used the param must have been a
            ;; recognised top-level extraction; else it's nested => MIG-18
            leftover-uses? (some (fn [el]
                                   (and param (not (keyword? el))
                                        (some #{param} (tree-seq coll? seq el))))
                                 (rest replaced))]
        (if (and changed? (not leftover-uses?))
          (mk "MIG-05" {:transform (fn [_] (n/coerce (vec replaced)))})
          ;; param used outside the closed extraction vocabulary => guided
          (mk "MIG-18")))

      :else
      ;; mixed / guarded / non-literal / no-dispatch imperative => MIG-18
      (mk "MIG-18"))))

;; ---------------------------------------------------------------------------
;; head classification for hiccup vectors
;; ---------------------------------------------------------------------------

(def ^:private recom-namespaces
  "Known Reagent-wrapper libraries (MIG-22 roster; configurable)."
  #{"re-com.core" "re-com"})

(defn- head-kind
  "Classify a hiccup vector head node. Returns one of
  :dom :custom-element :foreign-interop :foreign-symbol :view-symbol
  :dynamic :route-link :recom, plus :sym / :kw for detail."
  [env head-node]
  (let [kw (token-kw head-node)
        sym (token-sym head-node)]
    (cond
      (= kw :>) :foreign-interop
      (contains? #{:f> :r>} kw) :foreign-interop-sibling
      (keyword? kw)
      (if (and (nil? (namespace kw)) (str/includes? (name kw) "-")
               (not (str/includes? (name kw) ".")))
        :custom-element :dom)      ;; a-b keyword head = web component
      sym
      (let [q  (r/resolve-sym env sym)
            qn (cond
                 q (first (str/split q #"/"))
                 (namespace sym) (name (get (:aliases env) (symbol (namespace sym)) (symbol (namespace sym))))
                 :else nil)]
        (cond
          (= q "re-frame.core/route-link") :route-link
          (= (name sym) "route-link") :route-link
          (and qn (contains? recom-namespaces qn)) :recom
          :else :view-symbol))
      ;; non-literal, non-symbol head (an (if ...) etc) => dynamic
      (nil? head-node) :dynamic
      :else :dynamic)))

;; ---------------------------------------------------------------------------
;; props-map processing (at the :map node whose parent is a hiccup vector)
;; ---------------------------------------------------------------------------

(defn- entry-pairs
  "Seq of [key-node value-node] for a :map node (significant children paired)."
  [map-node]
  (partition 2 (sig-children map-node)))

(defn- fn-valued?
  "Is `node` a fn literal / fn-valued expression (a `#(...)`, `(fn ...)`, or a
  `(partial ..)`/`(comp ..)`)-"
  [node]
  (or (tag= node :fn)
      (boolean (fn-literal-shape node))
      (contains? #{"partial" "comp"} (some-> (list-head-sym node) name))))

(defn- on-prop?
  "Is keyword `kw` an :on-* handler prop?"
  [kw]
  (and (keyword? kw) (nil? (namespace kw)) (str/starts-with? (name kw) "on-")))

(defn- on-prop-camel?
  "Is keyword `kw` a camelCase onX handler prop (:onClick ..)-"
  [kw]
  (and (keyword? kw) (nil? (namespace kw))
       (re-matches #"on[A-Z].*" (name kw))))

(defn- process-props-map
  "Process a hiccup props map. `head-kind` routes fn-valued props. Returns
  {:findings [...] :transform node->node}."
  [env map-node head]
  (let [findings (atom [])
        ;; per-key transforms: a map from identical? key-node OR value-node -> new node
        edits (atom {})]
    (doseq [[k v] (entry-pairs map-node)]
      (let [kw (token-kw k)]
        (cond
          ;; MIG-11: DOM prop name respelling (non-handler names; onX handled
          ;; by the camelCase-handler branch below so the fn is ALSO lifted)
          (and (#{:dom :custom-element} head) (respell-key kw) (not (on-prop-camel? kw)))
          (do (swap! findings conj (mk "MIG-11"))
              (swap! edits assoc k (n/keyword-node (respell-key kw))))

          ;; MIG-29: callback :ref on a DOM element
          (and (#{:dom :custom-element} head) (= kw :ref) (fn-valued? v))
          (do (swap! findings conj (mk "MIG-29"))
              (swap! edits assoc v (n/list-node
                                     [(n/token-node 'ui/raw-fn) (spaces1) v])))

          ;; :ref on an INTERNAL-view head => declared forwarding (S3) => D flag
          (and (= head :view-symbol) (= kw :ref))
          (swap! findings conj (mk "MIG-29" {:action :flag :transform nil
                                             :note "declared ref forwarding on an internal view lands S3 - flag; do not emit a bare :ref"}))

          ;; fn-valued :on-* prop - route by head
          (and (on-prop? kw) (fn-valued? v))
          (case head
            (:dom :custom-element)
            (let [f (classify-on-handler env v)]
              (swap! findings conj f)
              (when (:transform f)
                (swap! edits assoc v ((:transform f) v))))
            (:foreign-interop :foreign-interop-sibling)
            (swap! findings conj (mk "MIG-10"))
            ;; internal view head => MIG-27 (C-13a: legal, opaque, non-gating)
            (swap! findings conj (mk "MIG-27")))

          ;; camelCase onX handler on a DOM head => respell key THEN it's a
          ;; fn-valued handler; respell + route
          (and (#{:dom :custom-element} head) (on-prop-camel? kw))
          (let [kebab (keyword (str "on-" (str/lower-case (subs (name kw) 2))))]
            (swap! findings conj (mk "MIG-11"))
            (swap! edits assoc k (n/keyword-node kebab))
            (when (fn-valued? v)
              (let [f (classify-on-handler env v)]
                (swap! findings conj f)
                (when (:transform f)
                  (swap! edits assoc v ((:transform f) v))))))

          ;; any-other fn-valued prop on a foreign head => MIG-10
          (and (#{:foreign-interop :foreign-interop-sibling} head) (fn-valued? v))
          (swap! findings conj (mk "MIG-10"))

          ;; any-other fn-valued prop on an internal view head => MIG-27
          (and (= head :view-symbol) (fn-valued? v))
          (swap! findings conj (mk "MIG-27"))

          ;; fn value in a non-:on-*/non-:ref DOM attr => MIG-14 D sub-rule
          (and (#{:dom :custom-element} head) (fn-valued? v) (keyword? kw))
          (swap! findings conj (mk "MIG-14" {:action :flag :gating? true :transform nil
                                             :note "a fn value in a non-:on-*/non-:ref DOM attr has no legal spelling (:rf.ui.compile/unsupported) - no rule owns it"}))

          :else nil)))
    {:findings @findings
     :transform (fn [node]
                  (if (empty? @edits)
                    node
                    (n/replace-children
                      node
                      (map (fn [c] (get @edits c c)) (n/children node)))))}))

;; ---------------------------------------------------------------------------
;; hiccup-vector processing (call sites, foreign heads, MIG-34, sub-rules)
;; ---------------------------------------------------------------------------

(defn- hiccup-vector?
  "Is `node` a :vector whose head is a keyword or symbol (a plausible hiccup /
  component vector)-"
  [node]
  (and (tag= node :vector)
       (let [h (first (sig-children node))]
         (or (token-kw h) (token-sym h)))))

(defn- props-map-of
  "The literal props map node (child index 1) of a hiccup vector, or nil."
  [vec-node]
  (let [kids (sig-children vec-node)
        m (second kids)]
    (when (tag= m :map) m)))

(defn- id-sugar-segments
  "Count `#id` segments in a tag keyword's name (`:div#a#b` -> 2)."
  [kw]
  (when (keyword? kw)
    (dec (count (str/split (str "x" (name kw)) #"#")))))

(defn- convert-call-site
  "Rewrite a converted-view call site `[view a b]` -> `[view {..}]` using the
  view's param symbols. A zero-param view emits the explicit empty props map."
  [vec-node params]
  (let [kids (sig-children vec-node)
        args (rest kids)
        head (first kids)]
    (cond
      ;; already a single map arg - keep (idempotent / already-map)
      (and (= 1 (count args)) (tag= (first args) :map))
      vec-node
      :else
      (let [pairs (map vector params args)
            entries (->> pairs
                         (map (fn [[p a]] [(n/keyword-node (keyword (name p))) (spaces1) a]))
                         (interpose [(spaces1)])
                         (apply concat)
                         vec)
            props (n/map-node entries)]
        (n/vector-node [head (spaces1) props])))))

(defn- process-hiccup-vector
  "Vector-level detectors: MIG-01 call site, MIG-09 foreign head, MIG-21 dynamic
  head, MIG-32 route-link, MIG-22 re-com head, MIG-34 dangerouslySetInnerHTML,
  MIG-14 id-sugar / keyword-child sub-rules. Returns {:findings :transform}."
  [env vec-node]
  (let [kids (sig-children vec-node)
        head (first kids)
        kind (head-kind env head)
        converted (:converted env)
        findings (atom [])
        transform (atom identity)
        hkw (token-kw head)
        hsym (token-sym head)]
    ;; --- head-driven ---
    (cond
      (= kind :foreign-interop)                      ;; MIG-09  [:> C {..}] -> [C {..}]
      (let [rest-kids (rest kids)
            pm (some #(when (tag= % :map) %) rest-kids)]
        (swap! findings conj (mk "MIG-09"))
        ;; MIG-10: fn-valued props at the FOREIGN boundary must be detected HERE,
        ;; before the `:>` is stripped (afterwards the head reads as an internal
        ;; view and the foreignness is lost). MIG-10 gates, so the view holds.
        (when pm
          (doseq [[_ v] (entry-pairs pm)]
            (when (fn-valued? v) (swap! findings conj (mk "MIG-10")))))
        (reset! transform
                (fn [_] (n/vector-node (vec (interpose (spaces1) rest-kids))))))

      (= kind :foreign-interop-sibling)              ;; :f> / :r> => D flag
      (swap! findings conj (mk "MIG-09" {:action :flag :gating? true :transform nil
                                         :note "foreign interop head :f>/:r> - choose the foreign-head + callback/props form by hand (positional args / raw JS props); not a pass-through tag"}))

      (= kind :dynamic)                              ;; MIG-21 dynamic tag head
      (swap! findings conj (mk "MIG-21"))

      (= kind :route-link)                           ;; MIG-32 framework component
      (swap! findings conj (mk "MIG-32"))

      (= kind :recom)                                ;; MIG-22 third-party wrapper
      (swap! findings conj (mk "MIG-22"))

      ;; MIG-01 call site of a converted view
      (and (= kind :view-symbol) converted (contains? converted (name hsym)))
      (let [{:keys [params]} (get converted (name hsym))]
        (swap! findings conj (mk "MIG-01" {:note "call site: [view a b] -> [view {..}]"}))
        (reset! transform (fn [vn] (convert-call-site vn params))))

      :else nil)

    ;; --- MIG-14 tag-keyword sub-rules on a DOM head ---
    (when (and hkw (#{:dom :custom-element} kind))
      (let [segs (id-sugar-segments hkw)]
        (when (and segs (>= segs 2))                 ;; duplicate #id => M keep-first
          (swap! findings conj (mk "MIG-14" {:note "duplicate #id sugar (:rf.ui.compile/duplicate-id-sugar) - keep the first"}))
          (reset! transform
                  (fn [vn]
                    (let [nm (name hkw)
                          [before after] (str/split nm #"#" 3)
                          kept (str before "#" (second (str/split nm #"#")))]
                      (n/replace-children
                        vn (map (fn [c] (if (identical? c head)
                                          (n/keyword-node (keyword kept)) c))
                                (n/children vn)))))))))

    ;; --- MIG-34 dangerouslySetInnerHTML (sole prop) ---
    (when-let [pm (props-map-of vec-node)]
      (let [pairs (entry-pairs pm)]
        (when (and (= 1 (count pairs))
                   (= :dangerouslySetInnerHTML (token-kw (ffirst pairs))))
          (let [vval (second (first pairs))
                html (when (tag= vval :map)
                       (let [[hk hv] (first (entry-pairs vval))]
                         (when (= :__html (token-kw hk)) hv)))]
            (if html
              (do (swap! findings conj (mk "MIG-34"))
                  (reset! transform
                          (fn [_] (n/vector-node
                                    [head (spaces1)
                                     (n/list-node [(n/token-node 'ui/html) (spaces1) html])]))))
              (swap! findings conj (mk "MIG-34" {:action :flag :gating? false :transform nil
                                                 :note "non-literal {:__html ..} value - review by hand (D)"})))))))

    ;; --- MIG-28 non-literal props map on a DOM head (rewrite + flag) ---
    ;; Restricted to explicit map-builder call heads. A BARE symbol at index 1 is
    ;; syntactically ambiguous with a child (Reagent decides props-vs-child at
    ;; runtime by `map-`); auto-spreading it would mangle `[:li item]`, so the
    ;; migrator does not - the conservative choice (a deliberate deviation from
    ;; the rule table's "or a bound symbol", noted in the PR).
    (when (and (#{:dom :custom-element} kind) (>= (count kids) 2))
      (let [p (second kids)]
        (when (and (not (tag= p :map))
                   (contains? #{"merge" "assoc" "assoc-in" "update" "update-in" "into"}
                              (some-> (list-head-sym p) name)))
          (swap! findings conj (mk "MIG-28"))
          (reset! transform
                  (fn [vn]
                    (n/replace-children
                      vn (map (fn [c] (if (identical? c p)
                                        (n/list-node [(n/token-node 'ui/spread) (spaces1) p]) c))
                              (n/children vn))))))))

    {:findings @findings :transform @transform}))

;; ---------------------------------------------------------------------------
;; list / call processing (deref, doall, for, framework calls, mount, ..)
;; ---------------------------------------------------------------------------

(defn- with-let-cleanup-only? [_] false)  ;; reserved; all with-let => MIG-16

(defn- process-list
  "Detectors keyed on a :list head symbol. Returns {:findings :transform}."
  [env node]
  (let [hd (list-head-sym node)
        kids (sig-children node)
        none {:findings [] :transform identity}
        one (fn [f] {:findings [f] :transform identity})
        rew (fn [f xf] {:findings [f] :transform xf})]
    (cond
      (nil? hd) none

      ;; MIG-02: (deref (subscribe [:q]))
      (and (= 'deref hd) (= 2 (count kids))
           (rf-op-kind env (list-head-sym (second kids))))
      (let [inner (second kids)]
        (if (= :subscribe (rf-op-kind env (list-head-sym inner)))
          (rew (mk "MIG-02")
               (fn [_] (n/replace-children inner
                         (map (fn [c] (if (identical? c (list-head inner))
                                        (n/token-node 'sub) c))
                              (n/children inner)))))
          none))

      ;; MIG-09: (r/adapt-react-class X) -> X
      (resolves env hd #{"reagent.core/adapt-react-class"})
      (rew (mk "MIG-09" {:note "adapt-react-class wrapper deleted; component used as a direct head"})
           (fn [_] (second kids)))

      ;; MIG-12: (doall (for ..)) -> (for ..)
      (and (= 'doall hd) (= 2 (count kids))
           (= 'for (list-head-sym (second kids))))
      (rew (mk "MIG-12") (fn [_] (second kids)))

      ;; MIG-13: markup-returning (map (fn ..) ..) in child position
      (and (= 'map hd) (>= (count kids) 3)
           (let [f (second kids)] (fn-literal-shape f)))
      (one (mk "MIG-13"))

      ;; MIG-08: (for [bindings] body) - unkeyed body / sub|lease in loop
      (= 'for hd)
      (let [body (last kids)
            body-s (->sexpr body)
            keyed? (or (tag= body :meta)                         ;; ^{:key ..}
                       (and (hiccup-vector? body)
                            (let [pm (props-map-of body)]
                              (and pm (some #(= :key (token-kw (first %))) (entry-pairs pm))))))]
        (if (and (hiccup-vector? body) (not keyed?))
          (one (mk "MIG-08"))
          none))

      ;; MIG-15: mount - reagent.dom/render, reagent.core/render, dom.client render
      (or (resolves env hd #{"reagent.dom/render" "reagent.core/render"
                             "reagent.dom.client/render" "reagent.dom.client/create-root"})
          (and (nil? (namespace (or hd 'x))) (contains? #{"render"} (name (or hd 'x)))
               (r/required? env "reagent.dom")))
      (if (and (= 2 (count (rest kids))) (hiccup-vector? (second kids)))
        (rew (mk "MIG-15" {:note "mount rewrite: no inline frame-root config found; frame id + :initial-events must be supplied"})
             (fn [_]
               (let [app (second kids)
                     el  (nth kids 2)
                     ;; [app] (zero-arg root call) -> [app {}]; a root already
                     ;; carrying props/args is threaded through unchanged
                     root (if (and (tag= app :vector) (= 1 (count (sig-children app))))
                            (n/vector-node [(first (sig-children app)) (spaces1) (n/map-node [])])
                            app)]
                 (n/list-node
                   [(n/token-node 'ui/mount) (spaces1)
                    (n/vector-node
                      [(n/token-node 'ui/frame-root) (spaces1)
                       (n/coerce {:id :rf/frame :initial-events []}) (spaces1)
                       root])
                    (spaces1) el]))))
        (one (mk "MIG-15" {:action :flag :note "mount site lacks a literal root vector / frame config - supply frame id + :initial-events by hand"})))

      ;; MIG-23: SSR render-to-string (staged S5) -> flag
      (resolves env hd #{"reagent.dom.server/render-to-string"
                         "reagent.dom.server/render-to-static-markup"})
      (one (mk "MIG-23"))

      ;; MIG-33: (rf/init! adapter) -> (rf/init! ui/adapter)
      (resolves env hd #{"re-frame.core/init!"})
      (rew (mk "MIG-33")
           (fn [_]
             (n/list-node
               [(first kids) (spaces1) (n/token-node 'ui/adapter)])))

      ;; MIG-31: (capture-frame) render-body capture -> (frame)
      (resolves env hd #{"re-frame.core/capture-frame"})
      (if (= 1 (count kids))
        (rew (mk "MIG-31") (fn [_] (n/list-node [(n/token-node 'ui/frame)])))
        (one (mk "MIG-31" {:action :flag :note "explicit-frame (capture-frame frame-id) has no compiled pin spelling - flag per MIG-03"})))

      ;; MIG-16: (with-let ..) or (r/atom ..) Form-2 local state
      (resolves env hd #{"reagent.core/with-let"})
      (one (mk "MIG-16"))

      (reagent-sym? env hd #{"atom"})
      (one (mk "MIG-16" {:note "r/atom local state - decide: product meaning -> app-db (event+sub) vs ephemeral -> (local init)"}))

      ;; MIG-17: (r/create-class {..})
      (resolves env hd #{"reagent.core/create-class"})
      (one (mk "MIG-17"))

      ;; MIG-19: (r/track ..) / (r/cursor ..) / (reaction ..)
      (or (reagent-sym? env hd #{"track" "cursor"})
          (resolves env hd #{"reagent.ratom/reaction" "reagent.ratom/make-reaction"}))
      (one (mk "MIG-19"))

      ;; MIG-20: (add-watch a k f) / (r/track! ..) / (run! ..) reactive effects
      (or (and (= 'add-watch hd))
          (reagent-sym? env hd #{"track!"})
          (resolves env hd #{"reagent.ratom/run!"}))
      (one (mk "MIG-20"))

      ;; MIG-35: component introspection / scheduler pokes
      (reagent-sym? env hd #{"current-component" "props" "children"
                             "force-update" "next-tick" "after-render"})
      (one (mk "MIG-35"))

      ;; MIG-03: (subscribe [:q] {:frame f}) / (dispatch e {:frame f})
      (and (rf-op-kind env hd) (>= (count kids) 3)
           (tag= (last kids) :map)
           (some #(= :frame (token-kw (first %))) (entry-pairs (last kids))))
      (one (mk "MIG-03"))

      ;; MIG-25: reg-sub body with a side effect (dispatch/fx) - dataflow flag
      (and (or (contains? #{"reg-sub" "reg-sub-raw"} (name hd))
               (resolves env hd #{"re-frame.core/reg-sub" "re-frame.core/reg-sub-raw"}))
           (subtree-calls-op? #{"dispatch" "dispatch-sync"} (->sexpr node)))
      (one (mk "MIG-25"))

      ;; MIG-01: converted-view FN-CALL site (price a c) -> [price {..}]
      ;; (the todomvc (filter-link ..) idiom - a hiccup-returning fn call)
      (and (:converted env) (contains? (:converted env) (name hd)))
      (let [{:keys [params]} (get (:converted env) (name hd))
            args (rest kids)
            pairs (map vector params args)
            entries (->> pairs
                         (map (fn [[p a]] [(n/keyword-node (keyword (name p))) (spaces1) a]))
                         (interpose [(spaces1)]) (apply concat) vec)]
        (rew (mk "MIG-01" {:note "fn-call view site (view a c) -> [view {..}]"})
             (fn [_] (n/vector-node [(first kids) (spaces1) (n/map-node entries)]))))

      :else none)))

;; ---------------------------------------------------------------------------
;; view-candidate detection + gating
;; ---------------------------------------------------------------------------

(def ^:private tail-control-heads
  '#{let letfn if if-not when when-not cond case do})

(defn- hiccup-tail?
  "Does node's tail resolve to a literal hiccup vector in EVERY branch (through
  the grammar's branching control forms, minus `for`)- Conservative."
  [node]
  (cond
    (hiccup-vector? node) true
    ;; a vector with a dynamic (list) head is a hiccup-shaped tail -> the view
    ;; is a candidate that then gates on MIG-21
    (and (tag= node :vector) (tag= (first (sig-children node)) :list)) true
    (tag= node :list)
    (let [hd (list-head-sym node)]
      (when (contains? tail-control-heads hd)
        (let [kids (sig-children node)]
          (case hd
            (let letfn) (recur (last kids))
            do (recur (last kids))
            (if if-not) (let [b (drop 2 kids)] (and (seq b) (every? hiccup-tail? b)))
            (when when-not) (recur (last kids))
            cond (let [clauses (partition 2 (rest kids))]
                   (and (seq clauses) (every? #(hiccup-tail? (second %)) clauses)))
            case (let [clauses (rest (rest kids))
                       pairs (partition 2 clauses)
                       default (when (odd? (count clauses)) (last clauses))]
                   (and (every? #(hiccup-tail? (second %)) pairs)
                        (or (nil? default) (hiccup-tail? default))))
            false))))
    :else false))

(defn- view-candidate
  "If top-level `node` is a view candidate, return
  {:name str :params [param-syms] :body-node <node> :kind :defn|:reg-view},
  else nil."
  [env node]
  (when (tag= node :list)
    (let [hd (list-head-sym node)
          kids (sig-children node)]
      (cond
        ;; reg-view / reg-view* (possibly aliased/referred)
        (or (resolves env hd #{"re-frame.core/reg-view" "re-frame.core/reg-view*"})
            (contains? #{"reg-view" "reg-view*"} (some-> hd name)))
        (let [nm (second kids)
              params (some #(when (tag= % :vector) %) (drop 2 kids))]
          {:name (str (->sexpr nm))
           :params (when params (keep token-sym (sig-children params)))
           :params-node params
           :kind :reg-view :node node})

        ;; defn / defn- returning literal hiccup in every tail branch
        (contains? #{'defn 'defn-} hd)
        (let [nm (second kids)
              ;; first :vector child after the name is the arglist (single-arity
              ;; only; a multi-arity defn wraps arities in lists -> not matched)
              params (some #(when (tag= % :vector) %) (drop 2 kids))
              tail (last kids)]
          (when (and params tail (hiccup-tail? tail))
            {:name (str (->sexpr nm))
             :params (keep token-sym (sig-children params))
             :params-node params
             :kind :defn :node node}))
        :else nil))))

;; walk a node with a value zipper, calling (f zloc) for side effects/collection
(defn- edn-zip [node] (z/edn node))

;; forward decls used by detect-in-node (defined below)
(declare hiccup-props-map? props-head)

(def ^:private template-parent-heads
  "List heads under which a `[keyword ..]` child is TEMPLATE hiccup, not a data
  vector (a dispatch/subscribe arg). Distinguishing them is the hiccup-vs-data
  ambiguity: an event vector `[:buy amt]` is an argument to a call, never a
  template child, so it must not be walked as an element."
  '#{let letfn if if-not when when-not cond case do for
     defn defn- defview reg-view reg-view*})

(defn- adapt-react-head?
  "Is `vec-node`'s head a `(r/adapt-react-class X)` call (MIG-09, not a dynamic
  tag head)- The inner list rewrites to `X`, yielding a direct foreign head."
  [env vec-node]
  (let [h (first (sig-children vec-node))]
    (and (tag= h :list)
         (resolves env (list-head-sym h) #{"reagent.core/adapt-react-class"}))))

(defn- template-position?
  "Is the vector at `zloc` in template/child position? True when its parent is a
  hiccup vector, a `^{:key ..}` meta, or a control/view body form; false when it
  is a call argument, a prop value, etc."
  [zloc]
  (let [up (z/up zloc)]
    (cond
      (nil? up) true
      (= :vector (z/tag up)) (hiccup-vector? (z/node up))
      (= :meta (z/tag up)) true
      (= :list (z/tag up))
      (let [h (some-> (z/down up) z/node token-sym)]
        (boolean (and h (or (template-parent-heads h)
                            (template-parent-heads (symbol (name h)))))))
      :else false)))

(defn- detect-in-node
  "Collect all findings within `node` (a view body or any subtree), context-aware
  via a value zipper. Returns a seq of findings (no positions)."
  [env node]
  (loop [zloc (edn-zip node)
         acc  []]
    (if (z/end? zloc)
      acc
      (let [n* (z/node zloc)
            fs (cond
                 (tag= n* :deref)
                 (let [inner (first (sig-children n*))]
                   (if (and (tag= inner :list)
                            (= :subscribe (rf-op-kind env (list-head-sym inner))))
                     [(mk "MIG-02")]
                     []))
                 (tag= n* :meta) []            ;; MIG-07 handled in rewrite walk
                 (and (tag= n* :list) (template-position? zloc)
                      (contains? (:markup-helpers env) (some-> (list-head-sym n*) name)))
                 [(mk "MIG-30")]               ;; runtime-built markup helper call
                 (tag= n* :list) (:findings (process-list env n*))
                 (and (tag= n* :vector) (template-position? zloc)
                      (tag= (first (sig-children n*)) :list)
                      (not (adapt-react-head? env n*)))
                 [(mk "MIG-21")]               ;; dynamic tag head
                 (and (hiccup-vector? n*) (template-position? zloc))
                 (:findings (process-hiccup-vector env n*))
                 (and (tag= n* :map) (hiccup-props-map? zloc))
                 (:findings (process-props-map env n* (head-kind env (props-head zloc))))
                 :else [])]
        (recur (z/next zloc) (into acc fs))))))

(defn- view-gated?
  "Does a view body contain any GATING finding?"
  [env body-node]
  (boolean (some :gating? (detect-in-node env body-node))))

;; ---------------------------------------------------------------------------
;; props-map context helpers (position zipper AND edn zipper)
;; ---------------------------------------------------------------------------

(defn- hiccup-props-map?
  "Is the :map at `zloc` the props map (child index 1) of a hiccup vector - i.e.
  its LEFT sibling is the vector's head keyword/symbol (and nothing precedes it)-"
  [zloc]
  (and (= :map (z/tag zloc))
       (let [up (z/up zloc)]
         (and up (= :vector (z/tag up))
              (let [left (z/left zloc)]
                (and left (nil? (z/left left))
                     (or (token-kw (z/node left)) (token-sym (z/node left)))))))))

(defn- props-head
  "The head node of the hiccup vector whose props map is at `zloc` (its left
  sibling)."
  [zloc]
  (z/node (z/left zloc)))

;; ---------------------------------------------------------------------------
;; MIG-01 view header rewrite
;; ---------------------------------------------------------------------------

(defn- params->map-destructure
  "Build a `{:keys [a b]}` destructure node from param symbols."
  [param-syms]
  (if (seq param-syms)
    (n/map-node [(n/keyword-node :keys) (spaces1)
                 (n/vector-node (vec (interpose (spaces1) (map n/token-node param-syms))))])
    (n/map-node [])))

(defn- rewrite-view-header
  "Rewrite a converted view definition node into `(ui/defview name [{:keys [..]}]
  BODY...)`. For reg-view, unwrap; for defn, swap the head. Positional params
  collapse to one map destructure; a single already-map param is kept."
  [env {:keys [kind params-node params node]}]
  (let [kids (n/children node)
        sig  (sig-children node)
        hd   (first sig)
        newhd (n/token-node 'ui/defview)
        single-map? (and params-node
                         (= 1 (count (sig-children params-node)))
                         (tag= (first (sig-children params-node)) :map))
        newparams (if single-map?
                    params-node
                    (n/vector-node [(params->map-destructure params)]))]
    (n/replace-children
      node
      (map (fn [c]
             (cond
               (identical? c hd) newhd
               (identical? c params-node) newparams
               :else c))
           kids))))

;; ---------------------------------------------------------------------------
;; the single rewriting walk (position-tracking)
;; ---------------------------------------------------------------------------

(defn- pos-of [zloc]
  (let [p (try (z/position zloc) (catch Exception _ nil))]
    {:line (first p) :col (second p)}))

(defn- top-level? [zloc]
  (and (some? (z/up zloc)) (nil? (z/up (z/up zloc)))))

(defn- contains-hiccup?
  "Does `node` contain a literal hiccup vector (a [keyword ..]) anywhere?"
  [node]
  (loop [z (z/edn node)]
    (cond
      (z/end? z) false
      (and (= :vector (z/tag z))
           (token-kw (first (sig-children (z/node z))))) true
      :else (recur (z/next z)))))

(defn- defn-node? [node]
  (and (tag= node :list) (contains? #{'defn 'defn-} (list-head-sym node))))

(defn- scan-plan
  "Pre-scan (node-level): classify every top-level view candidate. Returns
    {:converted {name {:params [syms]}}     ;; clean views (convert + call sites)
     :markup-helpers #{name ..}}             ;; defns that BUILD hiccup at runtime
                                            ;; but are not clean views (MIG-30)."
  [env root-node]
  (reduce
    (fn [acc node]
      (if-let [vc (view-candidate env node)]
        (if (view-gated? env (:node vc))
          acc
          (assoc-in acc [:converted (:name vc)] {:params (:params vc)}))
        (if (and (defn-node? node) (contains-hiccup? node))
          (update acc :markup-helpers conj (str (->sexpr (second (sig-children node)))))
          acc)))
    {:converted {} :markup-helpers #{}}
    (sig-children root-node)))

(defn- stamp [finding file pos held?]
  (assoc finding :file file :line (:line pos) :col (:col pos)
         :held? (boolean held?)
         :action (if (and held? (= :rewrite (:action finding))) :flag (:action finding))))

(defn- detect-zloc
  "Detect findings at position-zipper `zloc`. Returns {:findings [...]
  :transform node->node}. Mirrors detect-in-node but context via the position
  zipper (so props-map head resolution works)."
  [env zloc]
  (let [node (z/node zloc)]
    (cond
      (tag= node :deref)
      (let [inner (first (sig-children node))]
        (if (and (tag= inner :list)
                 (= :subscribe (rf-op-kind env (list-head-sym inner))))
          {:findings [(mk "MIG-02")]
           :transform (fn [d]
                        (let [in (first (sig-children d))]
                          (n/replace-children in
                            (map (fn [c] (if (identical? c (list-head in))
                                           (n/token-node 'sub) c))
                                 (n/children in)))))}
          {:findings [] :transform identity}))

      (tag= node :meta)                          ;; MIG-07 ^{:key k} [item ..]
      (let [kids (sig-children node)
            mmap (first kids)
            val  (last kids)]
        (if (and (tag= mmap :map) (hiccup-vector? val)
                 (some #(= :key (token-kw (first %))) (entry-pairs mmap)))
          (let [keyv (some #(when (= :key (token-kw (first %))) (second %)) (entry-pairs mmap))]
            {:findings [(mk "MIG-07")]
             :transform (fn [_]
                          (let [pm (props-map-of val)
                                vkids (sig-children val)
                                head (first vkids)]
                            (if pm
                              ;; merge :key into existing props map
                              (n/replace-children val
                                (map (fn [c]
                                       (if (identical? c pm)
                                         (n/replace-children pm
                                           (concat [(n/keyword-node :key) (spaces1) keyv (spaces1)]
                                                   (n/children pm)))
                                         c))
                                     (n/children val)))
                              ;; insert a props map at position 1
                              (n/vector-node
                                (into [head (spaces1)
                                       (n/map-node [(n/keyword-node :key) (spaces1) keyv])]
                                      (mapcat (fn [c] [(spaces1) c]) (rest vkids)))))))})
          {:findings [] :transform identity}))

      (and (tag= node :list) (template-position? zloc)
           (contains? (:markup-helpers env) (some-> (list-head-sym node) name)))
      {:findings [(mk "MIG-30")] :transform identity}   ;; runtime-built markup
      (tag= node :list) (process-list env node)
      (and (tag= node :vector) (template-position? zloc)
           (tag= (first (sig-children node)) :list)
           (not (adapt-react-head? env node)))
      {:findings [(mk "MIG-21")] :transform identity}   ;; dynamic tag head
      (and (hiccup-vector? node) (template-position? zloc))
      (process-hiccup-vector env node)
      (and (tag= node :map) (hiccup-props-map? zloc))
      (process-props-map env node (head-kind env (props-head zloc)))
      :else {:findings [] :transform identity})))

(defn- walk
  "Single position-tracking walk. Collects findings and - when rewrite? -
  applies M/MIG-28 transforms to nodes NOT inside a gated view. View headers of
  converted views are rewritten at their top-level form."
  [zroot env {:keys [rewrite? file]}]
  (loop [zloc zroot
         held? false
         findings (transient [])]
    (let [node (z/node zloc)
          top? (top-level? zloc)
          vc   (when top? (view-candidate env node))
          gated? (when vc (view-gated? env (:node vc)))
          ;; MIG-26: a plain (non-view) top-level defn making ambient frame ops
          mig26? (and top? (nil? vc) (defn-node? node)
                      (subtree-calls-op? #{"subscribe" "dispatch" "dispatch-sync"}
                                         (->sexpr node)))
          held? (if top? (boolean (or gated? mig26?)) held?)
          pos (pos-of zloc)
          ;; view header rewrite for a converted (clean) view
          [zloc0 hdr-findings]
          (cond
            (and vc (not gated?))
            [(if rewrite? (z/replace zloc (rewrite-view-header env vc)) zloc)
             [(stamp (mk "MIG-01" {:note (str "view '" (:name vc) "' -> ui/defview; params -> map destructure")})
                     file pos false)]]
            mig26?
            [zloc [(stamp (mk "MIG-26") file pos false)]]
            :else [zloc []])
          {:keys [findings* zloc1]}
          (if vc
            {:findings* [] :zloc1 zloc0}          ;; header handled; body handled by descent
            (let [{:keys [findings transform]} (detect-zloc env zloc0)
                  stamped (map #(stamp % file pos held?) findings)
                  do-rw? (and rewrite? (not held?)
                              (some #(= :rewrite (:action %)) stamped))
                  z* (if do-rw? (z/replace zloc0 (transform (z/node zloc0))) zloc0)]
              {:findings* stamped :zloc1 z*}))
          all (reduce conj! findings (concat hdr-findings findings*))
          nxt (z/next zloc1)]
      (if (z/end? nxt)
        {:zip zloc1 :findings (persistent! all)}
        (recur nxt held? all)))))

;; ---------------------------------------------------------------------------
;; MIG-24 - ns requires fixup (runs last)
;; ---------------------------------------------------------------------------

(defn- ui-libspec-node []
  (n/vector-node
    [(n/token-node 're-frame.ui) (spaces1)
     (n/keyword-node :as) (spaces1) (n/token-node 'ui) (spaces1)
     (n/keyword-node :refer) (spaces1)
     (n/vector-node [(n/token-node 'defview) (spaces1) (n/token-node 'sub)])]))

(defn- require-clause-loc
  "Zipper location of the ns form's `(:require ..)` child list, or nil."
  [ns-zloc]
  (loop [c (z/down ns-zloc)]
    (cond
      (nil? c) nil
      (and (= :list (z/tag c))
           (= :require (token-kw (some-> (z/down c) z/node)))) c
      :else (recur (z/right c)))))

(defn- add-ui-require
  "Ensure the ns form requires `[re-frame.ui :as ui :refer [defview sub]]` (unless
  already present). Appends the libspec to the `(:require ..)` clause. Returns the
  (possibly modified) ROOT NODE."
  [zroot]
  (loop [zloc zroot]
    (cond
      (z/end? zloc) (z/root zloc)
      (and (= :list (z/tag zloc))
           (= 'ns (token-sym (some-> (z/down zloc) z/node))))
      (if (str/includes? (z/string zloc) "re-frame.ui")
        (z/root zloc)
        (if-let [req (require-clause-loc zloc)]
          (z/root (z/append-child req (ui-libspec-node)))
          (z/root zloc)))
      :else (recur (z/next zloc)))))

;; ---------------------------------------------------------------------------
;; orchestration
;; ---------------------------------------------------------------------------

(defn- process*
  [s {:keys [rewrite?] :as opts}]
  (let [zroot (z/of-string s {:track-position? true})
        env0  (r/env-from-zipper zroot)
        already-ui? (r/required? env0 "re-frame.ui")
        {:keys [converted markup-helpers]} (scan-plan env0 (z/root zroot))
        env   (assoc env0 :converted converted :markup-helpers markup-helpers)
        {:keys [zip findings]} (walk zroot env opts)
        walked (when rewrite? (z/root-string zip))
        ;; MIG-24: add the re-frame.ui require when the rewrite emitted any ui/*
        ;; target (converted views, mount, adapter, spread, html, raw-fn, frame)
        ;; and the file does not already require it.
        needs-ui? (and rewrite? walked (not already-ui?)
                       (boolean (re-find #"(?m)(^|[^\w./])ui/" walked)))
        src   (cond
                (not rewrite?) s
                needs-ui? (n/string (add-ui-require (z/of-string walked)))
                :else walked)
        findings (cond-> findings
                   (or needs-ui? (and (not rewrite?) (seq converted) (not already-ui?)))
                   (conj (mk "MIG-24" {:note "ns requires: add [re-frame.ui :as ui :refer [defview sub]]; drop reagent requires when zero uses remain"})))]
    {:source src :findings (vec findings)}))

(defn- process
  "Wraps process* to degrade gracefully on malformed source: an unparseable file
  is left unchanged and reported, never thrown."
  [s opts]
  (try
    (process* s opts)
    (catch Exception e
      {:source s
       :findings [{:rule "PARSE" :tier :error :action :flag :held? false
                   :file (:file opts) :line nil :col nil :suggest nil
                   :note (str "source could not be parsed; left unchanged: "
                              (.getMessage e))}]})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn scan-string
  "Scan a source string; return findings (no rewrite)."
  ([s] (scan-string s {}))
  ([s opts] (:findings (process s (assoc opts :rewrite? false)))))

(defn rewrite-string
  "Apply the migrator to a source string. Returns {:source out :findings [...]}.
  Source is unchanged for any site whose action is :flag / :reject, and for
  every site inside a gated view."
  ([s] (rewrite-string s {}))
  ([s opts] (process s (assoc opts :rewrite? true))))

(defn scan-file [path]
  (scan-string (slurp path) {:file (str path)}))

(defn- clj-source-file? [^java.io.File f]
  (and (.isFile f)
       (let [n (.getName f)]
         (or (str/ends-with? n ".clj") (str/ends-with? n ".cljc")
             (str/ends-with? n ".cljs")))))

(defn- expand-paths [paths]
  (->> paths
       (mapcat (fn [p]
                 (let [f (io/file p)]
                   (cond (.isDirectory f) (filter clj-source-file? (file-seq f))
                         (.isFile f) [f]
                         :else []))))
       distinct))

(defn scan-paths [paths]
  (vec (mapcat (fn [f] (scan-string (slurp f) {:file (str f)})) (expand-paths paths))))

(defn rewrite-file!
  ([path] (rewrite-file! path {}))
  ([path {:keys [write?]}]
   (let [orig (slurp path)
         {:keys [source findings]} (rewrite-string orig {:file (str path)})
         changed? (not= orig source)]
     (when (and write? changed?) (spit path source))
     {:path (str path) :changed? changed? :findings findings :source source})))

(defn rewrite-paths!
  ([paths] (rewrite-paths! paths {}))
  ([paths opts] (mapv #(rewrite-file! % opts) (expand-paths paths))))

;; ---------------------------------------------------------------------------
;; CLI
;; ---------------------------------------------------------------------------

(defn- print-findings [findings]
  (doseq [{:keys [file line col rule tier action held? note]} findings]
    (println (format "%s:%s:%s  %-7s %-6s %-7s %s%s"
                     (or file "-") (or line "-") (or col "-")
                     rule (name (or tier :-)) (name (or action :-))
                     (if held? "[held] " "") (or note "")))))

(defn- summary [findings]
  (let [by (group-by :action findings)]
    {:total (count findings)
     :rewrite (count (:rewrite by))
     :flag (count (:flag by))
     :reject (count (:reject by))}))

(defn -main
  "CLI: scan (default) or --rewrite a file set.

    clojure -M:run PATH ...                ;; scan + print findings
    clojure -M:run --rewrite PATH ...      ;; dry-run rewrite + print findings
    clojure -M:run --rewrite --write PATH  ;; rewrite IN PLACE"
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
