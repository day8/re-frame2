(ns re-frame.routing-path-census-test
  "The in-bundle route-path census (rf2-wqnl).

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
  COLUMN-1 (namespace-load-time) `reg-route` form under the app trees, with
  its path canonicalised by the framework's own `canonical-route-pattern`.

  Source reading buys three properties the runtime walk cannot have: it is
  complete, it is independent of load and run order, and it perturbs nothing
  (adding requires to a test namespace to widen a runtime walk would reorder
  registrations, and registration order is what decides which duplicate wins).

  Column 1 is the whole filter, and it is exactly the right one: only
  namespace-load-time registrations are process-global. The `reg-route` calls
  inside `deftest` bodies are indented, fixture-scoped, and rolled back — they
  never co-reside with another app's, so they are not censused and cannot
  false-flag.

  ## What this census is NOT

  It is not a widening of `:rf.warning/route-shadowed-by-equal-score`, and it
  could not be: that warning asks whether two co-matchable routes tie on
  structural rank, a different question from whether two applications silently
  overwrite each other, and even when it fires it goes to the instrumentation
  ring buffer that nothing in the node lane reads. It is not a registration
  refusal either — `reg-route` still accepts a duplicate path, because a
  duplicate path is not always a bug (an emission-only registration that only
  ever synthesises hrefs by id is coherent and harmless). This is a repo-side
  gate on the one composition where the class bites: many apps, one process.
  Consumer apps are unaffected; there is zero framework and zero spec surface
  here. Per the rf2-wqnl ruling — option (a), narrowly scoped."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.routing.match :as match])
  (:import [java.io PushbackReader StringReader]))

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

;; ---- reading the claims out of the app trees -------------------------------

(def ^:private app-roots
  "Repo-relative trees holding applications that load into the shared node
  test bundle. Directory-level, not app-level, so a NEW app is censused the
  moment it exists — there is no per-app roster to forget to update.

  Framework and tool testbeds are deliberately outside this list: they compile
  into their own bundles (`:node-test-freehand`, the per-tool testbed builds),
  so a path they share with an example is not a collision."
  ["examples" "testbeds"])

(def ^:private top-level-reg-route
  ;; A reg-route call in COLUMN 1 — i.e. at namespace-load time — under
  ;; whatever alias the file requires re-frame.core as.
  #"(?m)^\((?:[^\s()\[\]{};]+/)?reg-route\s")

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
       (filter #(.isDirectory ^java.io.File %))
       (mapcat file-seq)
       (filter #(and (.isFile ^java.io.File %)
                     (re-find #"\.clj[sc]$" (.getName ^java.io.File %))))
       (sort-by #(.getPath ^java.io.File %))))

(defn- line-of [text idx]
  (inc (count (filter #(= \newline %) (subs text 0 idx)))))

(defn- read-form-at
  "Read exactly one form starting at `idx`. `edn/read` is the balanced-form
  scanner here — it handles strings, chars and `;` comments correctly, and
  never evaluates anything."
  [text idx label]
  (try
    (edn/read {:default (fn [_tag v] v)}
              (PushbackReader. (StringReader. (subs text idx))))
    (catch Exception e
      (throw (ex-info (str "route-path census: could not read the reg-route form at " label)
                      {:at label} e)))))

(defn claims-in
  "Every top-level route claim in `text`, paths canonicalised the way
  `reg-route` canonicalises them. `label` is the display path used in failure
  messages and in the read-error report."
  [text label]
  (let [m (re-matcher top-level-reg-route text)]
    (loop [acc []]
      (if-not (.find m)
        acc
        (let [start (.start m)
              line  (line-of text start)
              form  (read-form-at text start (str label ":" line))
              id    (second form)
              path  (last form)]
          (recur (if (and (keyword? id) (string? path))
                   (conj acc {:id   id
                              :path (match/canonical-route-pattern path)
                              :file label
                              :line line})
                   acc)))))))

(defn- bundle-claims []
  (let [root (repo-root)
        base (str (.getPath root) java.io.File/separator)]
    (mapcat (fn [^java.io.File f]
              (claims-in (slurp f)
                         (-> (.getPath f)
                             (str/replace base "")
                             (str/replace "\\" "/"))))
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

(deftest claims-in-reads-top-level-registrations-only
  (testing "column-1 forms are claims; indented (fixture-scoped) ones are not"
    (let [text (str "(ns example.core)\n"
                    "(rf/reg-route :app/home {:doc \"Home.\"} \"/\")\n"
                    "(reg-route :app/thing {} \"/thing/:id\")\n"
                    "(deftest t\n"
                    "  (rf/reg-route :fixture/local {} \"/\"))\n")]
      (is (= [{:id :app/home  :path "/"          :file "f.cljs" :line 2}
              {:id :app/thing :path "/thing/:id" :file "f.cljs" :line 3}]
             (claims-in text "f.cljs"))))))

;; ---- 2. the census ---------------------------------------------------------

(deftest app-tree-route-path-census
  (testing "no two applications in the shared node test bundle claim one URL path"
    (let [claims (bundle-claims)
          groups (duplicate-path-groups claims allowed-duplicate-paths)]
      (is (seq claims) "the census must not pass vacuously — it found no routes at all")
      (is (empty? groups) (census-failure-message groups)))))
