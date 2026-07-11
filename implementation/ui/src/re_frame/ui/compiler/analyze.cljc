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
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.rules :as rules]))

(def node-ops
  "The CLOSED op set — the AST-shape gate's vocabulary. `:frame-root` (S1c,
  rf2-vxgfnd.3) appears only inside the static top region of a ROOT form
  (`ui/mount` / `ui/render!` / `ui/hydrate-root`) — the analyzer rejects it
  everywhere else, so defview-template ASTs never carry it."
  #{:text :nothing :expr :element :fragment :view :foreign
    :if :let :letfn :case :for :raw :html :frame-root})

(defn literal-scalar? [x]
  (or (string? x) (number? x) (keyword? x) (boolean? x) (nil? x)))

(def ^:private ui-raw-fqns    #{'re-frame.ui/raw})
(def ^:private ui-html-fqns   #{'re-frame.ui/html})
(def ^:private ui-raw-fn-fqns #{'re-frame.ui/raw-fn})
(def ^:private ui-spread-fqns #{'re-frame.ui/spread})
(def ^:private sub-fqns       #{'re-frame.ui/sub})
(def ^:private lease-fqns     #{'re-frame.ui/lease})
(def ^:private frame-root-fqns #{'re-frame.ui/frame-root})

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
;; Expression walking — sub/lease site indexing + loop finiteness
;; ---------------------------------------------------------------------------

(defn walk-expr
  "Walk an opaque expression: index (sub ...)/(lease ...) sites and
  enforce the finite-sites law — a sub/lease site anywhere it may
  evaluate more than once per render (`:in-loop?`) is a compile error."
  [e form]
  (letfn [(walk [f locals]
            (cond
              (and (seq? f) (= 'quote (first f))) nil

              (seq? f)
              (let [head (first f)]
                (when (and (symbol? head) (not (contains? locals head)))
                  (let [e* (update e :locals into locals)]
                    (cond
                      (env/resolves-to? e* head sub-fqns)
                      (do (when (:in-loop? e)
                            (env/fail! e :rf.ui.compile/sub-in-loop
                                       (str "(sub ...) inside a loop — subscription sites "
                                            "must be finite. Extract a keyed child view and "
                                            "read the sub there")
                                       {:form f}))
                          (env/add-site! e :subs {:query (second f) :path (:path e)}))

                      (env/resolves-to? e* head lease-fqns)
                      (do (when (:in-loop? e)
                            (env/fail! e :rf.ui.compile/lease-in-loop
                                       (str "(lease ...) inside a loop — lease sites must "
                                            "be finite. Extract a keyed child view")
                                       {:form f}))
                          (env/add-site! e :leases {:descriptor (second f) :path (:path e)}))
                      :else nil)))
                ;; collect locals introduced by fn*/let* inside opaque exprs
                ;; (best-effort shadow tracking)
                (let [locals' (cond
                                (fn-form? f)
                                (into locals (mapcat env/binding-syms
                                                     (filter vector? (flatten1-argvs f))))
                                (and (contains? #{'let 'let* 'loop 'loop*} head)
                                     (vector? (second f)))
                                (into locals (mapcat env/binding-syms
                                                     (take-nth 2 (second f))))
                                :else locals)]
                  (doseq [x (rest f)] (walk x locals'))))

              (coll? f)
              (doseq [x f] (walk x locals))

              :else nil))
          (flatten1-argvs [f]
            ;; fn forms: (fn name? [args] ...) or (fn ([a] ..) ([a b] ..))
            (let [tail (drop 1 f)
                  tail (if (symbol? (first tail)) (rest tail) tail)]
              (if (vector? (first tail))
                [(first tail)]
                (map first (filter seq? tail)))))]
    (walk form #{})
    form))

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
  (doseq [x (rest form)] (walk-expr e x)))

(defn analyze-handler
  "Classify one :on-* entry. -> {:k kw :name str :classification
  :vector|:options|:fn|:dynamic :form form :capture? bool
  :hoistable? bool :serializable? bool}"
  [e k form]
  (let [nm (name k)]
    (cond
      (vector? form)
      (do (analyze-event-vector! e k form)
          (env/add-site! e :events {:prop k :handler form :path (:path e)
                                    :classification :vector :serializable? true})
          {:k k :name nm :classification :vector :form form :capture? false
           :hoistable? (every? #(or (literal-scalar? %) (contains? rules/placeholders %)) form)
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
        (analyze-event-vector! e k (:event form))
        (env/add-site! e :events {:prop k :handler form :path (:path e)
                                  :classification :options :serializable? true})
        {:k k :name nm :classification :options :form form
         :capture? (boolean (:capture form))
         :hoistable? (every? #(or (literal-scalar? %) (contains? rules/placeholders %))
                             (:event form))
         :serializable? true})

      (fn-form? form)
      (do (when (:in-loop? e)
            (env/warn! e {:id :rf.ui.compile/bare-fn-in-loop
                          :msg (str "bare fn handler at " k " inside a loop — "
                                    "works, at per-row closure cost, and defeats "
                                    "the data idiom; prefer an event vector or a "
                                    "keyed child view")
                          :form form}))
          (walk-expr e form)
          (env/add-site! e :events {:prop k :handler :opaque :path (:path e)
                                    :classification :fn :serializable? false})
          {:k k :name nm :classification :fn :form form :capture? false
           :hoistable? false :serializable? false})

      :else
      (do (walk-expr e form)
          (check-loop-capture! e (str "dynamic handler at " k) form)
          (env/add-site! e :events {:prop k :handler :opaque :path (:path e)
                                    :classification :dynamic :serializable? false})
          {:k k :name nm :classification :dynamic :form form :capture? false
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
                         (map (fn [[k v]] [(if (keyword? k) (name k) k) v]))
                         (sort-by first)
                         vec)]
          (doseq [[_ v] flags] (walk-expr e v))
          {:base-str (str/join " " const-names) :flags flags :dyn nil
           :static? (empty? flags)}))

      (vector? form) ; mixed literal/expr vector — runtime join in vector order
      (do (doseq [x form] (when-not (literal-scalar? x) (walk-expr e x)))
          {:base-str (str/join " " base) :flags [] :dyn form :static? false})

      :else
      (do (walk-expr e form)
          {:base-str (str/join " " base) :flags [] :dyn form :static? false}))))

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
                          (when-not (literal-scalar? v) (walk-expr e v))
                          {:css-name (name k) :value v :literal? (literal-scalar? v)})
                        form)]
      {:entries entries :static? (every? :literal? entries)})

    :else
    (do (walk-expr e form)
        {:dyn form :static? false})))

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
      {:form (second form) :raw-fn? true}
      :else
      (do (walk-expr e form) {:form form :raw-fn? false}))
    :view
    (env/fail! e :rf.ui.compile/ref-on-view-s1
               (str ":ref at an internal-view call site — internal views "
                    "forward :ref only by declaring it, and declared ref "
                    "forwarding lands S3. (Conservative S1 pin.)")
               {:form form})
    :foreign
    (do (walk-expr e form) {:form form :raw-fn? false})))

(defn analyze-element-props
  "Analyze a DOM/custom element's props position (literal map or
  (ui/spread base overrides)). -> {:key {..} :class {..} :style {..}|nil
  :attrs [..] :events [..] :spread form|nil :ref {..}|nil
  :property-props #{kw} :static? bool}"
  [e tag-info custom? props-form]
  (let [tag        (:tag tag-info)
        properties (when custom? (rules/custom-element-properties tag))
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
        (walk-expr e base)
        (when overrides (walk-expr e overrides))
        {:key {:present? false} :class (analyze-class e (:classes tag-info) nil)
         :style nil :attrs [] :events []
         :spread {:base base :overrides overrides}
         :ref nil :property-props #{} :static? false})
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
                                       property? (boolean (and properties (properties k)))]
                                   (when-not (literal-scalar? v)
                                     (walk-expr e v))
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
                                    :value v
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
              ref-a      (when (contains? m :ref) (analyze-ref e ref-form :element))]
          (when-not (literal-scalar? key-form) (when (contains? m :key) (walk-expr e key-form)))
          {:key   {:present? (contains? m :key) :expr key-form
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
                       (when-not (string? s) (walk-expr e s))
                       {:op :html :form s :static? (string? s)}))]
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
                                 raw-fn? (raw-fn-form? e v)]
                             (when-not (literal-scalar? v) (walk-expr e v))
                             {:k k
                              :slot (if-let [ns* (namespace k)] (str ns* "/" (name k)) (name k))
                              :value (cond raw? (second v) raw-fn? (second v) :else v)
                              :marker (cond raw? :foreign raw-fn? :ui/raw-fn :else nil)
                              :literal? (literal-scalar? v)}))
                         m*)]
      (when (and (contains? m :key) (not (literal-scalar? key-form)))
        (walk-expr e key-form))
      {:key {:present? (contains? m :key) :expr key-form
             :literal? (literal-scalar? key-form)}
       :entries entries
       :ref ref-a})))

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
      (let [config (not-empty (dissoc props :id))]
        ;; config values are opaque runtime expressions (evaluated at
        ;; preflight) — walk them for sub/lease site indexing only
        (doseq [[_k v] config] (walk-expr e v))
        {:op :frame-root
         :frame-id id
         :config config
         :children (analyze-children e (vec (drop 2 form)))
         :static? false
         :path (:path e)}))))

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
        children  (analyze-children e (vec (if has-props (drop 2 form) (drop 1 form))))]
    (when (and has-props (not (literal-scalar? key-form))) (walk-expr e key-form))
    {:op :fragment
     :key {:present? has-props :expr key-form :literal? (literal-scalar? key-form)}
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
  (walk-expr e test)
  {:op :if :test test
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
    (walk-expr e expr)
    (let [default? (odd? (count clauses))
          pairs    (partition 2 (if default? (butlast clauses) clauses))
          default  (when default? (last clauses))]
      {:op :case
       :expr expr
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
          e*    (reduce (fn [acc [pat init]]
                          (walk-expr acc init)
                          (env/with-locals acc (env/binding-syms pat)))
                        e pairs)
          body-form (single-body! e (str head) body form)]
      {:op :let :bindings bindings
       :body (analyze (update e* :path conj :body) body-form)
       :static? false :path (:path e)})))

(defn- analyze-letfn [e form]
  (let [[_ fnspecs & body] form]
    (when-not (vector? fnspecs)
      (env/fail! e :rf.ui.compile/bad-let "letfn needs a fnspecs vector" {:form form}))
    (let [names (map first fnspecs)
          e*    (env/with-locals e names)]
      (doseq [spec fnspecs] (walk-expr e* spec))
      (let [body-form (single-body! e "letfn" body form)]
        {:op :letfn :fnspecs fnspecs
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
      ;; walk seq-exprs: first coll evaluates once per render (sub legal
      ;; there); every later coll/modifier expr is per-row (in-loop)
      (loop [e* e, first-coll? true, ps pairs, bound []]
        (when-let [[l r] (first ps)]
          (cond
            (= :let l)
            (do (when-not (vector? r)
                  (env/fail! e :rf.ui.compile/bad-for ":let modifier needs a bindings vector"
                             {:form form}))
                (doseq [[_pat init] (partition 2 r)]
                  (walk-expr (assoc e* :in-loop? true) init))
                (recur (env/with-loop e* (mapcat env/binding-syms (take-nth 2 r)))
                       false (rest ps) bound))

            (contains? #{:when :while} l)
            (do (walk-expr (assoc e* :in-loop? true) r)
                (recur e* false (rest ps) bound))

            (keyword? l)
            (env/fail! e :rf.ui.compile/bad-for
                       (str "unknown for modifier " l " — the subgrammar is "
                            ":let / :when / :while (Q6 pin)")
                       {:form form})

            :else
            (do (walk-expr (if first-coll? e* (assoc e* :in-loop? true)) r)
                (recur (env/with-loop e* (env/binding-syms l))
                       false (rest ps) bound)))))
      (let [all-bound (into []
                            (comp (partition-all 2)
                                  (mapcat (fn [[l r]]
                                            (cond
                                              (= :let l) (mapcat env/binding-syms (take-nth 2 r))
                                              (keyword? l) []
                                              :else (env/binding-syms l)))))
                            seq-exprs)
            e-body    (update (env/with-loop e all-bound) :path conj :for)
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
         :seq-exprs seq-exprs
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
          (walk-expr e x)
          {:op :raw :form x :static? false :path (:path e)})

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
        (do (walk-expr e form)
            {:op :expr :form form :static? false :path (:path e)})))))

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
    (symbol? form)  (do (walk-expr e form)
                        {:op :expr :form form :static? false :path (:path e)})
    :else
    (env/fail! e :rf.ui.compile/unsupported-form
               (str "unsupported template form " (pr-str form) " of type "
                    (type form) " — template content is strings/numbers/"
                    "nil/false, [:tag ...] vectors, control forms, and "
                    "expressions. Render a value as text with (str ...)")
               {:form form})))
