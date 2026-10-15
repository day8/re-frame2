(ns re-frame.routing-path-census-test
  "Load-time routes from bundled app trees must use distinct paths (TESTING.md). The source walk follows called functions and resolves local ids without loading apps or counting test fixtures. Unreadable claims fail closed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.routing.match :as rf.routing.match]))

;; ---- the allowlist ---------------------------------------------------------

(def allowed-duplicate-paths
  "Canonical path → the EXACT set of route ids permitted to share it. Any id
  joining or leaving the set re-reds this census, which is the review point we
  want: a NEW claimant on a shared path is precisely the event this gate
  exists to catch.

  Two entries. `/` is a URL-fidelity claim on every side of it:

    :todo/all             canonical TodoMVC — `/`, `/active`, `/completed`
                          are the spec's own URLs, and the guide's code blocks
                          are digest-pinned to them.
    :realworld/home       the Conduit contract followed to the letter (both
                          the http and the resources ports register this id).
    :resources.app/home   the resources / infinite-feed / linearlite
    :infinite-feed.app/home   capability demos, whose landing page is `/`
    :linearlite.app/board     because that is what a landing page is.
    :routing.app/home     the routing capability walkthrough itself.

  They are asymptomatic together because no suite here feeds another app's
  URL in: each app's tests ingress only its own URLs, and the shared claim is
  the landing page nothing else navigates to. That is a property of the
  current suites, not a guarantee — which is why a new claimant must come here and be
  argued for rather than land silently. A witness app added to the bundle
  should prefix its paths (`/slice`, `/fresco-todo`) and never appear here.
  The Fresco witness applications need no entry: all three are prefixed.

  `/articles` is the weaker entry: neither the resources capability demo's
  nor the routing capability walkthrough's articles list is a fidelity
  claim — either could take a prefix. It is
  allowlisted rather than fixed here because renaming another app's routes is
  outside this census's remit, and it is asymptomatic
  because each app's suite ingresses only its own URLs. It is the entry to
  retire first if anyone is prefixing capability-demo paths."
  {"/"         #{:todo/all
                 :realworld/home
                 :resources.app/home
                 :infinite-feed.app/home
                 :linearlite.app/board
                 :routing.app/home}
   "/articles" #{:resources.app/articles
                 :routing.app/articles}})

;; ---- the census core (pure over claims) ------------------------------------

(defn duplicate-path-groups
  "Pure census. Over a seq of route CLAIMS — `{:id :path :file :line}`, paths
  already canonical — and an allowlist of `{path #{id …}}`, return the
  violating groups: every path claimed by more than one distinct id whose
  claimant set is not exactly the set the allowlist blesses for that path.

  Shape: `[{:path p :claims [{:id :file :line} …]} …]`, ordered by path then
  id then file so a failure message is stable run to run."
  [claims allowlist]
  (->> claims
       (group-by :path)
       (keep (fn [[path cs]]
               (let [ids (set (map :id cs))]
                 (when (and (< 1 (count ids))
                            (not= ids (get allowlist path)))
                   {:path   path
                    :claims (vec (sort-by (juxt :id :file :line) cs))}))))
       (sort-by :path)
       vec))

(defn census-failure-message
  "Render violating groups as the failure text: every claimant id with the
  file and line that claims it, the convention, and the bead."
  [groups]
  (str "Route PATH collision in the shared node test bundle (rf2-wqnl).\n\n"
       "`npm run test:cljs` loads every application into ONE node process, and\n"
       "route paths are plain strings in the process-global registrar. Two apps\n"
       "claiming one path share one match-table entry: the earlier registration\n"
       "wins every URL and the later is unreachable for URL ingress, so the\n"
       "breakage lands in a suite that has never heard of your app.\n\n"
       "CONVENTION (TESTING.md): every app or witness loaded into the shared node\n"
       "test bundle namespaces its URL paths under a distinct leading segment:\n"
       "/slice, /fresco-todo. Prefix your paths. Only if the shared claim is a\n"
       "URL-fidelity requirement AND no suite can ingress another app's URLs, add\n"
       "it to `allowed-duplicate-paths` in this file with the justification.\n\n"
       (str/join "\n\n"
                 (for [{:keys [path claims]} groups]
                   (str "  " (pr-str path) " claimed by "
                        (count (distinct (map :id claims))) " route ids:\n"
                        (str/join "\n"
                                  (for [c claims]
                                    (str "    " (:id c) "\n"
                                         "      " (:file c) ":" (:line c)))))))))

(defn unresolved-failure-message
  "Render the registrations the census REACHED but could not read as claims.
  Never a skip: a census that drops what it cannot resolve is a green light
  over an unchecked path."
  [unresolved]
  (str "Route registration the census could not READ (rf2-p5og).\n\n"
       "These `reg-route` calls run at namespace load — so their paths are\n"
       "process-global and the census must account for them — but the id or the\n"
       "path is not a literal it can resolve, and dropping one silently is how a\n"
       "source-reading census turns into a green light over an unchecked path.\n\n"
       "Write the id as a keyword literal or as a file-local `(def name ::kw)`,\n"
       "and the path as a string literal. If the registration is genuinely\n"
       "computed, take it out of namespace-load time: a route registered inside a\n"
       "function nothing calls at load is fixture-scoped and is not censused.\n\n"
       (str/join "\n"
                 (for [u (sort-by (juxt :file :line) unresolved)]
                   (str "  " (:file u) ":" (:line u) "\n"
                        "    " (:why u))))))

;; ---- reading the claims out of the app trees -------------------------------

(def app-roots
  "Repo-relative trees holding applications that load into the shared node
  test bundle. Directory-level, not app-level, so a NEW app is censused the
  moment it exists — there is no per-app roster to forget to update.

  `implementation/fresco/test` is here because `fresco/test` is a
  `:source-paths` entry of the top-level shadow build, so the witness
  applications under `re_frame/fresco/examples/` compile into the SAME node
  bundle as everything under `examples/` and their paths are exactly as
  process-global. The tree also holds ordinary `*_cljs_test` namespaces, and
  they are safe to walk: a `reg-route` a test registers sits inside a
  `deftest`, or inside a `defn` only a test calls, and neither is reached at
  namespace-load time.

  Framework and tool testbeds are deliberately outside this list: they compile
  into their own bundles (`:node-test-testbed-support`, the per-tool testbed
  builds), so a path they share with an example is not a collision."
  ["examples"
   "testbeds"
   "implementation/fresco/test"])

(defn- repo-root
  "Walk up from the test's working directory to the checkout root (the
  directory holding `examples/`)."
  []
  (loop [d (.getCanonicalFile (io/file (System/getProperty "user.dir")))]
    (when (nil? d)
      (throw (ex-info "route-path census: repo root not found above the working directory"
                      {:cwd (System/getProperty "user.dir")})))
    (if (.isDirectory (io/file d "examples"))
      d
      (recur (.getParentFile d)))))

(defn- source-files [root]
  (->> app-roots
       (map #(io/file root %))
       (mapcat file-seq)
       (filter #(and (.isFile ^java.io.File %)
                     (re-find #"\.clj[sc]$" (.getName ^java.io.File %))))
       (sort-by #(.getPath ^java.io.File %))))

;; ---- reading one file ------------------------------------------------------

(def ^:private unresolvable-alias-ns
  "Namespace prefix stamped on an `::alias/kw` whose alias the file's `ns` form
  did not declare in a shape this census parses. Reading must not THROW on one
  — an exotic libspec would then red the census over a file with no route in
  it — but it must not be quietly believed either, so the marker rides along
  on the keyword and any claim id wearing one is reported as unresolved."
  "route-census.unresolvable-alias")

(def ^:private reading-scratch-ns
  "The namespace `*ns*` is bound to for the first read of a file, before its
  own `ns` form has been seen. Only `::auto-resolved` keywords are affected,
  and that pass keeps none of them."
  'route-census.reading-scratch)

(defn- read-all
  "Every top-level form in `text`, in order, with `:line` / `:column`
  metadata, read by `clojure.tools.reader` at `:features #{:cljs}`.

  `*ns*` is bound to `ns-sym` so an `::auto-resolved` keyword carries the
  FILE'S namespace — two files' `::feed` must be two ids, or a genuine
  collision between them would read as one id registered twice. `create-ns`
  interns an empty namespace to bind; that is inert (nothing is interned into
  it, and `require` consults `*loaded-libs*` rather than `find-ns`).

  `*read-eval*` is off, so `#=(…)` is refused rather than evaluated."
  [text label ns-sym aliases]
  (let [eof (Object.)
        rdr (rt/indexing-push-back-reader text)]
    (binding [*ns*                         (create-ns ns-sym)
              rdr/*alias-map*              (fn [sym]
                                             (or (get aliases sym)
                                                 (symbol (str unresolvable-alias-ns "." sym))))
              rdr/*default-data-reader-fn* (fn [_tag v] v)
              rdr/*read-eval*              false]
      (try
        (loop [acc []]
          (let [form (rdr/read {:eof eof :read-cond :allow :features #{:cljs}} rdr)]
            (if (identical? form eof)
              acc
              (recur (conj acc form)))))
        (catch Exception e
          (throw (ex-info (str "route-path census: could not read " label)
                          {:file label} e)))))))

(defn- ns-form-of
  "The file's `ns` form, or nil."
  [forms]
  (first (filter #(and (seq? %) (= 'ns (first %))) forms)))

(defn- ns-alias-map
  "alias symbol → namespace symbol, over every `:require` / `:require-macros`
  libspec of an `ns` form. Flat libspecs only (`[a.b :as x]`, `:as-alias`);
  anything else falls through to the `unresolvable-alias-ns` marker, which is
  reported rather than believed."
  [ns-form]
  (into {}
        (for [clause (rest ns-form)
              :when  (and (seq? clause)
                          (#{:require :require-macros} (first clause)))
              spec   (rest clause)
              :when  (vector? spec)
              :let   [opts (->> (rest spec)
                                (partition-all 2)
                                (filter #(= 2 (count %)))
                                (map vec)
                                (into {}))]
              k      [:as :as-alias]
              :let   [a (get opts k)]
              :when  (and (symbol? a) (symbol? (first spec)))]
          [a (first spec)])))

;; ---- deciding what runs at namespace-load time -----------------------------

(defn- reg-route-call? [form]
  (and (seq? form)
       (symbol? (first form))
       (= "reg-route" (name (first form)))))

(defn- fn-literal? [form]
  (and (seq? form) (contains? '#{fn fn*} (first form))))

(defn- load-time-forms
  "`form` and every subform of it that is evaluated when `form` is — the whole
  tree MINUS the bodies of function literals (`fn`, and the `fn*` that `#(…)`
  reads as), which run when something calls them."
  [form]
  (tree-seq #(and (coll? %) (not (fn-literal? %))) seq form))

(def ^:private inert-heads
  "Top-level heads that neither run nor define anything this census follows."
  '#{ns comment declare})

(def ^:private defn-heads
  "Top-level heads whose body is deferred but whose NAME this census follows:
  call one at load time and its `reg-route`s are load-time registrations."
  '#{defn defn-})

(def ^:private evaluated-def-heads
  "`def`-family heads whose value expression IS evaluated at namespace load."
  '#{def defonce})

(def ^:private deferred-body-heads
  "`def`-family heads whose body is a function body — it runs when the thing is
  called, never at namespace load — so a `reg-route` inside one is
  fixture-scoped by construction and is deliberately not censused, silently.
  Any OTHER `def…` head holding a `reg-route` is reported instead of assumed:
  see `unresolved-failure-message`."
  '#{defn defn- defmacro defmethod defmulti deftest deftest- defprotocol
     defrecord deftype definterface})

(defn- head-of [form]
  (when (and (seq? form) (symbol? (first form)))
    (first form)))

(defn- definition-name [form]
  (first (filter symbol? (rest form))))

(defn- classify
  "What kind of top-level form this is, for the load-time walk."
  [form]
  (let [h (head-of form)]
    (cond
      (nil? h)                             :load-time
      (contains? inert-heads h)            :inert
      (contains? defn-heads h)             :defn
      (contains? evaluated-def-heads h)    :load-time
      (str/starts-with? (name h) "def")    :deferred
      :else                                :load-time)))

(defn- mentioned-symbols [form]
  (into #{} (filter symbol?) (load-time-forms form)))

(defn- reachable-defns
  "The names of the file-local `defn`s reached from `seed-forms`, to a
  fixpoint: a `defn` is reached when a load-time expression, or the body of an
  already-reached `defn`, mentions its name. Fixpoint rather than one hop so a
  single indirection cannot hide a registration."
  [defns seed-forms]
  (loop [reached #{}
         pending (into #{} (mapcat mentioned-symbols) seed-forms)]
    (let [fresh (into #{} (filter #(and (contains? defns %) (not (contains? reached %)))) pending)]
      (if (empty? fresh)
        reached
        (recur (into reached fresh)
               (into #{} (mapcat #(mentioned-symbols (get defns %))) fresh))))))

;; ---- turning a reached reg-route form into a claim -------------------------

(defn- unresolvable-alias? [kw]
  (and (keyword? kw)
       (some-> (namespace kw) (str/starts-with? unresolvable-alias-ns))))

(defn- claim-of
  "One reached `reg-route` form as either a claim or an unresolved report.
  `defs` maps the file's own `def`'d symbols to their keyword values."
  [form label defs]
  (let [line (:line (meta form))
        bad  (fn [why] {:file label :line line :why why})]
    (if (not= 4 (count form))
      (bad (str "`reg-route` takes exactly three arguments — id, metadata, path — "
                "and this call has " (dec (count form)) "."))
      (let [[_ id _metadata path] form
            id' (cond
                  (keyword? id)                            id
                  (and (symbol? id) (nil? (namespace id)))  (get defs id)
                  :else                                     nil)]
        (cond
          (nil? id')
          (bad (str "the route id " (pr-str id) " is neither a keyword literal nor a "
                    "symbol this file `def`s to one."))

          (unresolvable-alias? id')
          (bad (str "the route id " (pr-str id) " uses a namespace alias this census "
                    "could not resolve from the `ns` form."))

          (not (string? path))
          (bad (str "the route path " (pr-str path) " is not a string literal."))

          :else
          {:id   id'
           :path (rf.routing.match/canonical-route-pattern path)
           :file label
           :line line})))))

(defn claims-in
  "Every NAMESPACE-LOAD-TIME route claim in `text`, paths canonicalised the way
  `reg-route` canonicalises them. `label` is the display path used in failure
  messages.

  Returns `{:claims [{:id :path :file :line} …] :unresolved [{:file :line :why}
  …]}`, both ordered by line. A registration the walk reaches but cannot read
  lands in `:unresolved`; it is never dropped."
  [text label]
  (let [pass1   (read-all text label reading-scratch-ns {})
        ns-form (ns-form-of pass1)
        ns-sym  (or (some->> ns-form rest (filter symbol?) first) reading-scratch-ns)
        forms   (if ns-form
                  (read-all text label ns-sym (ns-alias-map ns-form))
                  pass1)
        by-kind (group-by classify forms)
        defns   (into {} (keep (fn [f] (when-let [n (definition-name f)] [n f]))) (:defn by-kind))
        entries (:load-time by-kind)
        reached (reachable-defns defns entries)
        calls   (concat (mapcat #(filter reg-route-call? (load-time-forms %)) entries)
                        (mapcat #(filter reg-route-call? (load-time-forms (get defns %))) reached))
        defs    (into {} (keep (fn [f]
                                 (let [n (definition-name f)]
                                   (when (and n
                                              (contains? evaluated-def-heads (head-of f))
                                              (>= (count f) 3)
                                              (keyword? (last f)))
                                     [n (last f)]))))
                      (:load-time by-kind))
        results (map #(claim-of % label defs) (sort-by #(:line (meta %)) calls))
        opaque  (for [f    (:deferred by-kind)
                      :when (not (contains? deferred-body-heads (head-of f)))
                      call (filter reg-route-call? (tree-seq coll? seq f))]
                  {:file label
                   :line (:line (meta call))
                   :why  (str "this `reg-route` sits inside a `(" (head-of f) " …)` form, and "
                              "the census does not know whether that body runs at namespace "
                              "load. Classify the head in `deferred-body-heads` if it does not.")})]
    {:claims     (filterv :id results)
     :unresolved (vec (sort-by :line (concat (remove :id results) opaque)))}))

(defn- bundle-census []
  (let [root (repo-root)
        base (str (.getPath root) java.io.File/separator)]
    (reduce (fn [acc ^java.io.File f]
              (merge-with into acc
                          (claims-in (slurp f)
                                     (-> (.getPath f)
                                         (str/replace base "")
                                         (str/replace "\\" "/")))))
            {:claims [] :unresolved []}
            (source-files root))))

;; ---- 1. the census core goes red on demand (self-test) ---------------------

(deftest duplicate-path-groups-self-test
  (testing "an unallowlisted duplicate is REPORTED, with every id and its coords"
    (let [claims [{:id :app-a/home  :path "/"      :file "examples/a/core.cljs" :line 12}
                  {:id :app-b/home  :path "/"      :file "examples/b/core.cljs" :line 7}
                  {:id :app-a/about :path "/about" :file "examples/a/core.cljs" :line 13}]
          groups (duplicate-path-groups claims {})]
      (is (= [{:path "/" :claims (subvec claims 0 2)}] groups)
          "only the shared path, both claimants ordered stably")))

  (testing "a guard can be wrong by being too eager — the legal cases stay green"
    (let [pair [{:id :app-a/home :path "/"} {:id :app-b/home :path "/"}]]
      (doseq [[label claims allowlist violation?]
              [["the exact blessed set passes"
                pair {"/" #{:app-a/home :app-b/home}} false]
               ["ONE id registered from two files is an id replacement, not a path
                 collision — the registrar is keyed by id, so nothing is shadowed"
                [{:id :realworld/home :path "/" :file "examples/real-apps/realworld_http/routing.cljs"}
                 {:id :realworld/home :path "/" :file "examples/real-apps/realworld_resources/routing.cljs"}]
                {} false]
               ["a THIRD claimant is not covered by the pair's allowlist entry"
                (conj pair {:id :app-c/home :path "/"}) {"/" #{:app-a/home :app-b/home}} true]
               ["an allowlist entry is pinned to its path"
                pair {"/other" #{:app-a/home :app-b/home}} true]]]
        (is (= violation? (boolean (seq (duplicate-path-groups claims allowlist)))) label)))))

;; ---- 2. the reader sees load-time registrations, and only those ------------

(deftest claims-in-sees-every-load-time-registration
  (testing "a top-level call, in either grammar the app trees use"
    (let [text (str "(ns example.core (:require [re-frame.core :as rf]))\n"
                    "(rf/reg-route :app/home {:doc \"Home.\"} \"/\")\n"
                    "(reg-route :app/thing {} \"/thing/:id\")\n")]
      (is (= [{:id :app/home  :path "/"          :file "f.cljs" :line 2}
              {:id :app/thing :path "/thing/:id" :file "f.cljs" :line 3}]
             (:claims (claims-in text "f.cljs"))))))

  (testing "a reg-route inside a defn CALLED at the top level is load-time —
            the shape three witness applications are written in,
            and a shape a column-1 regex cannot see"
    (let [text (str "(ns example.routes (:require [re-frame.routing :as routing]))\n"
                    "(def feed \"The list page.\" ::feed)\n"
                    "(defn register! []\n"
                    "  (routing/reg-route feed\n"
                    "    {:doc \"The article list.\"}\n"
                    "    \"/slice\"))\n"
                    "(register!)\n")]
      (is (= [{:id :example.routes/feed :path "/slice" :file "routes.cljs" :line 4}]
             (:claims (claims-in text "routes.cljs")))
          "the `def`'d symbol id resolves, and `::feed` carries the FILE's namespace")))

  (testing "one indirection does not hide a registration"
    (let [text (str "(ns example.routes (:require [re-frame.routing :as routing]))\n"
                    "(defn- inner [] (routing/reg-route :app/deep {} \"/deep\"))\n"
                    "(defn register! [] (inner))\n"
                    "(register!)\n")]
      (is (= [:app/deep] (mapv :id (:claims (claims-in text "routes.cljs")))))))

  (testing "a deftest body, and a defn nothing calls at load, register nothing
            global — and neither is a complaint"
    (let [text (str "(ns example.core-test (:require [re-frame.routing :as routing]))\n"
                    "(deftest t\n"
                    "  (routing/reg-route :fixture/local {} \"/\"))\n"
                    "(defn- fresh! []\n"
                    "  (routing/reg-route :helper/only-a-test-calls-me {} \"/\"))\n"
                    "(defn- unreached [] (fresh!))\n")]
      (is (= {:claims [] :unresolved []} (claims-in text "core_test.cljs")))))

  (testing "a function literal's body is not load-time either"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(def make-fixture (fn [] (routing/reg-route :fx/one {} \"/\")))\n"
                    "(def make-other #(routing/reg-route :fx/two {} \"/\"))\n")]
      (is (= [] (:claims (claims-in text "core.cljs"))))))

  (testing "two files' ::feed are two ids, or a real collision would read as one"
    (let [a (str "(ns app.a (:require [re-frame.routing :as routing]))\n"
                 "(def feed ::feed)\n"
                 "(defn r! [] (routing/reg-route feed {} \"/x\"))\n(r!)\n")
          b (str "(ns app.b (:require [re-frame.routing :as routing]))\n"
                 "(def feed ::feed)\n"
                 "(defn r! [] (routing/reg-route feed {} \"/x\"))\n(r!)\n")
          claims (concat (:claims (claims-in a "a.cljs")) (:claims (claims-in b "b.cljs")))]
      (is (= 1 (count (duplicate-path-groups claims {})))
          "the census sees the collision between them"))))

(deftest claims-in-never-drops-what-it-cannot-read
  (testing "a computed path is reported, not skipped"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(def base \"/app\")\n"
                    "(routing/reg-route :app/home {} (str base \"/home\"))\n")]
      (is (= [["core.cljs" 3]] (map (juxt :file :line) (:unresolved (claims-in text "core.cljs")))))))

  (testing "an id that is neither a literal nor a file-local def is reported"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(routing/reg-route (route-id) {} \"/home\")\n")]
      (is (= 1 (count (:unresolved (claims-in text "core.cljs")))))))

  (testing "a reg-route inside an unclassified def… form asks to be classified"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(defsomething thing\n"
                    "  (routing/reg-route :app/home {} \"/home\"))\n")]
      (is (str/includes? (:why (first (:unresolved (claims-in text "core.cljs"))))
                         "defsomething"))))

  (testing "the enumerated deferred-body heads stay silent"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(defmethod render :x [_] (routing/reg-route :app/home {} \"/home\"))\n")]
      (is (= {:claims [] :unresolved []} (claims-in text "core.cljs"))))))

;; ---- 3. the census ---------------------------------------------------------

(deftest app-tree-route-path-census
  (let [root (repo-root)]
    (testing "every app root exists — a tree that has MOVED must go red, not quiet"
      (is (= [] (remove #(.isDirectory ^java.io.File (io/file root %)) app-roots)))))

  (let [{:keys [claims unresolved]} (bundle-census)
        groups                      (duplicate-path-groups claims allowed-duplicate-paths)]
    (testing "every load-time registration in the app trees was READ"
      (is (empty? unresolved) (unresolved-failure-message unresolved)))
    (testing "no two applications in the shared node test bundle claim one URL path"
      (is (seq claims) "the census must not pass vacuously — it found no routes at all")
      (is (empty? groups) (census-failure-message groups)))))
