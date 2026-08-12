(ns re-frame.routing-path-census-test
  "The in-bundle route-path census (rf2-wqnl; widened to reach the app trees
  under `implementation/` and the routes they register from a FUNCTION by
  rf2-p5og).

  Route IDS are namespaced keywords, so two applications can never collide on
  one. Route PATHS are plain strings in the process-global registrar, and the
  shared node test bundle (`npm run test:cljs`) loads a dozen applications
  into ONE process — so the match table `match-url` walks is written by every
  app in the bundle at once. Two apps that both claim `/` share one entry:
  ties go to the earlier registration (rank carries `(- reg-index)`), so the
  first one loaded wins every URL forever and the later is unreachable for URL
  ingress. The breakage then lands in a suite that has never heard of your
  app, naming YOUR routes — which is how rf2-hic-025 (PR #7920) cost twelve
  RealWorld assertions.

  The remedy is the in-bundle path-prefix convention written down in
  TESTING.md — every app or witness loaded into the shared bundle namespaces
  its URL paths under a distinct leading segment — and this census is what
  makes that convention impossible to violate by accident. Distinct leading
  literals can never co-match, so the prefix also forecloses the non-identical
  overlap class (one app's literal under another's capture) that exact-
  duplicate grouping cannot see.

  ## What this census reads, and why it is not a runtime walk

  The rf2-wqnl ruling directed a node-lane test walking
  `registrar/registrations :route` under the standard fixture. That was
  built first and MEASURED: it sees 20 of the 39 top-level route
  registrations in the app trees, and WHICH 20 depends on namespace require
  order and on sibling test namespaces calling `registrar/clear-kind!` /
  `clear-all!` mid-run. The half it could not see contained `:todo/all` and
  `realworld-http`'s `:realworld/home` — the exact pair the ruling's own
  allowlist instruction names. A census blind to half the bundle, including
  the collision the ruling wrote down, would repeat this bead's own
  phantom-warning failure mode, so the census reads the SOURCE instead: every
  NAMESPACE-LOAD-TIME `reg-route` form under the app trees, with its path
  canonicalised by the framework's own `canonical-route-pattern`.

  Source reading buys three properties the runtime walk cannot have: it is
  complete, it is independent of load and run order, and it perturbs nothing
  (adding requires to a test namespace to widen a runtime walk would reorder
  registrations, and registration order is what decides which duplicate wins).

  ## Why it reads FORMS and not lines (rf2-p5og)

  The first cut of this census anchored on a column-1 regex, and that filter
  was described here as \"exactly the right one\". It was the right QUESTION —
  only namespace-load-time registrations are process-global — asked by an
  instrument that could not answer it. Three applications register their
  routes through a `register!` FUNCTION called at column 1, because
  `re-frame.test-support`'s reset fixture rolls back a registration made
  before the fixture snapshot was taken; their `reg-route` forms are indented
  inside a `defn` and the regex could not see one. Those three are the slice,
  the Todo witness and the navigation witness — the applications whose
  collision motivated rf2-wqnl in the first place. Nor could the regex have
  been widened into place: the same three write the route id as a `def`'d
  symbol (`(routing/reg-route feed …)`), and the old reader dropped any claim
  whose id was not a keyword literal without a word. Measured before this
  bead: adding `implementation/hicasso/test` to `app-roots` moved the files
  scanned from 101 to 231 and the claims found from 39 to 39.

  So the reader is now `clojure.tools.reader` at `:features #{:cljs}` over the
  whole file — the same instrument `re-frame.freehand.compiler.harvest` reads
  ClojureScript source with, and on the classpath for the same reason (it
  ships with `org.clojure/clojurescript`, a hard dep of core). Reading forms
  rather than lines answers the load-time question STRUCTURALLY:

    - The walk descends the whole file but never into a function body — `fn`,
      and the `fn*` that `#(…)` reads as. Code inside one runs when something
      calls it, not when the namespace loads.
    - A `defn` is followed only when a load-time expression, or the body of an
      already-followed `defn`, mentions its name — to a fixpoint, so one
      indirection cannot hide a registration.
    - A `def`'d symbol id is resolved against the file's own `def`s, and an
      `::auto-resolved` keyword against the file's own `ns`.

  The `reg-route` calls inside `deftest` bodies, and inside the `defn`s only a
  test calls, are therefore still not censused and still cannot false-flag —
  by construction rather than by indentation.

  ## Nothing is dropped quietly

  Under-reporting is the failure mode a source-reading census has, and it is
  worse than having no census: a green light over a path nobody checked. Two
  rules keep it out. A `reg-route` this walk REACHES whose id or path is not a
  literal it can resolve is REPORTED as unresolved rather than skipped. And a
  `reg-route` sitting inside a top-level `def…` form this census does not
  know the evaluation semantics of is reported the same way, asking for the
  form to be classified — the known deferred-body heads (`defn`, `deftest`,
  `defmethod`, …) are enumerated below and stay silent.

  ## What this census is NOT

  It is not a widening of `:rf.warning/route-shadowed-by-equal-score`, and it
  could not be: that warning asks whether two co-matchable routes tie on
  structural rank, a different question from whether two applications silently
  overwrite each other, and even when it fires it goes to the instrumentation
  ring buffer that nothing in the node lane reads. It is not a registration
  refusal either — `reg-route` still accepts a duplicate path, because a
  duplicate path is not always a bug (an emission-only registration that only
  ever synthesises hrefs by id is coherent and harmless). It is not a check on
  duplicate route IDS: one id registered from two files is an id replacement,
  the registrar is keyed by id so only one entry ever exists, and nothing is
  shadowed. This is a repo-side gate on the one composition where the path
  class bites: many apps, one process. Consumer apps are unaffected; there is
  zero framework and zero spec surface here. Per the rf2-wqnl ruling —
  option (a), narrowly scoped."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.reader :as rdr]
            [clojure.tools.reader.reader-types :as rt]
            [re-frame.routing.match :as match]))

;; ---- the allowlist ---------------------------------------------------------

(def allowed-duplicate-paths
  "Canonical path → the EXACT set of route ids permitted to share it. Any id
  joining or leaving the set re-reds this census, which is the review point we
  want: a NEW claimant on a shared path is precisely the event this gate
  exists to catch.

  Two entries, both the state the app trees were already in when this census
  was written. `/` is a URL-fidelity claim on every side of it:

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
  the landing page nothing else navigates to. That is a property of TODAY'S
  suites, not a guarantee — which is why a new claimant must come here and be
  argued for rather than land silently. A witness app added to the bundle
  should prefix its paths (`/slice`, `/hicasso-todo`) and never appear here.
  Widening the census's reach to the Hicasso witness applications (rf2-p5og)
  added no entry and was never going to: all three were already prefixed.

  `/articles` is the weaker entry and the honest label for it is INHERITED,
  not blessed: the resources capability demo and the routing capability
  walkthrough both grew an articles list before the convention existed, and
  neither URL is a fidelity claim — either could take a prefix. It is
  allowlisted rather than fixed here because renaming another app's routes is
  outside this bead (rf2-wqnl explicitly forbids it), and asymptomatic today
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
       "/slice, /hicasso-todo. Prefix your paths. Only if the shared claim is a\n"
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
  over an unchecked path, which is the exact defect rf2-p5og found in its
  predecessor."
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

  `implementation/hicasso/test` is here (rf2-p5og) because `hicasso/test` is a
  `:source-paths` entry of the top-level shadow build, so the witness
  applications under `re_frame/hicasso/examples/` compile into the SAME node
  bundle as everything under `examples/` and their paths are exactly as
  process-global. The tree also holds ordinary `*_cljs_test` namespaces, and
  they are safe to walk: a `reg-route` a test registers sits inside a
  `deftest`, or inside a `defn` only a test calls, and neither is reached at
  namespace-load time.

  Framework and tool testbeds are deliberately outside this list: they compile
  into their own bundles (`:node-test-freehand`, the per-tool testbed builds),
  so a path they share with an example is not a collision."
  ["examples"
   "testbeds"
   "implementation/hicasso/test"])

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
           :path (match/canonical-route-pattern path)
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
                                   (when (and n (>= (count f) 3) (keyword? (last f)))
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
    {:claims     (vec (filter :id results))
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
      (is (= 1 (count groups)))
      (is (= "/" (:path (first groups))))
      (is (= [:app-a/home :app-b/home] (mapv :id (:claims (first groups))))
          "both claimants named, ordered stably")
      (let [msg (census-failure-message groups)]
        (is (str/includes? msg ":app-a/home"))
        (is (str/includes? msg ":app-b/home"))
        (is (str/includes? msg "examples/a/core.cljs:12") "coords make it jumpable")
        (is (str/includes? msg "examples/b/core.cljs:7"))
        (is (str/includes? msg "rf2-wqnl")))))

  (testing "a guard can be wrong by being too eager — the legal cases stay green"
    (let [distinct-paths [{:id :app-a/home :path "/a"} {:id :app-b/home :path "/b"}]
          allowlisted    [{:id :app-a/home :path "/"}  {:id :app-b/home :path "/"}]]
      (is (= [] (duplicate-path-groups distinct-paths {}))
          "distinct paths never collide, however many apps hold them")
      (is (= [] (duplicate-path-groups allowlisted {"/" #{:app-a/home :app-b/home}}))
          "the exact blessed set passes")
      (is (= [] (duplicate-path-groups [{:id :app-a/home :path "/"}] {}))
          "a path with one claimant is not a duplicate")
      (is (= [] (duplicate-path-groups
                  [{:id :realworld/home :path "/" :file "examples/real-apps/realworld_http/routing.cljs"}
                   {:id :realworld/home :path "/" :file "examples/real-apps/realworld_resources/routing.cljs"}]
                  {}))
          "ONE id registered from two files is an id replacement, not a path
           collision — the registrar is keyed by id, so there is only ever one
           entry and nothing is shadowed")
      (is (seq (duplicate-path-groups
                 (conj allowlisted {:id :app-c/home :path "/"})
                 {"/" #{:app-a/home :app-b/home}}))
          "a THIRD claimant is not covered by the pair's allowlist entry")
      (is (seq (duplicate-path-groups allowlisted {"/other" #{:app-a/home :app-b/home}}))
          "an allowlist entry is pinned to its path"))))

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
            the shape rf2-p5og's three witness applications are written in,
            and the shape the column-1 regex could not see"
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

  (testing "fixture-scoped registrations are NOT claims"
    (let [text (str "(ns example.core-test (:require [re-frame.routing :as routing]))\n"
                    "(deftest t\n"
                    "  (routing/reg-route :fixture/local {} \"/\"))\n"
                    "(defn- fresh! []\n"
                    "  (routing/reg-route :helper/only-a-test-calls-me {} \"/\"))\n"
                    "(defn- unreached [] (fresh!))\n")
          out  (claims-in text "core_test.cljs")]
      (is (= [] (:claims out))
          "a deftest body, and a defn nothing calls at load, register nothing global")
      (is (= [] (:unresolved out)) "and neither is a complaint")))

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
      (is (= [:app.a/feed :app.b/feed] (mapv :id claims)))
      (is (= 1 (count (duplicate-path-groups claims {})))
          "and the census sees the collision between them"))))

(deftest claims-in-never-drops-what-it-cannot-read
  (testing "a computed path is reported, not skipped"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(def base \"/app\")\n"
                    "(routing/reg-route :app/home {} (str base \"/home\"))\n")
          out  (claims-in text "core.cljs")]
      (is (= [] (:claims out)))
      (is (= 1 (count (:unresolved out))))
      (is (= 3 (:line (first (:unresolved out)))))
      (is (str/includes? (unresolved-failure-message (:unresolved out)) "core.cljs:3"))))

  (testing "an id that is neither a literal nor a file-local def is reported"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(routing/reg-route (route-id) {} \"/home\")\n")]
      (is (= 1 (count (:unresolved (claims-in text "core.cljs")))))))

  (testing "a wrong arity is reported rather than mis-read"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(routing/reg-route :app/home \"/home\")\n")
          out  (claims-in text "core.cljs")]
      (is (= [] (:claims out)))
      (is (str/includes? (:why (first (:unresolved out))) "exactly three arguments"))))

  (testing "a reg-route inside an unclassified def… form asks to be classified"
    (let [text (str "(ns example.core (:require [re-frame.routing :as routing]))\n"
                    "(defsomething thing\n"
                    "  (routing/reg-route :app/home {} \"/home\"))\n")
          out  (claims-in text "core.cljs")]
      (is (= [] (:claims out)))
      (is (str/includes? (:why (first (:unresolved out))) "defsomething"))))

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
