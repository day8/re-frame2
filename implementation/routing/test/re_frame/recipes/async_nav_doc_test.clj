(ns re-frame.recipes.async-nav-doc-test
  "The one witness that reads the PROSE beside this application, and the
  only reason it exists is that a code block is the one part of a page a
  reader actually runs, and it was the one part under no gate at all.

  ## What went wrong, and why prose could not notice

  `re-frame.recipes.async-nav` registers an `:optimistic` plan whose
  target is a MAP — `{:resource … :params … :scope …}`. The page that
  reports this application,
  `docs/design/hicasso/product/async-routing-recipes.md`, printed the
  `[id params]` VECTOR spelling instead. That is not a near-miss.
  Optimistic arms run BEFORE the request lowers, so a target that could
  write the cache under a wrong identity is rejected outright rather
  than dropped-and-warned, and it takes the request with it: a reader
  who copied the published recipe got no instance, no request, and
  `:idle` afterwards.

  ## What the runtime does about it — corrected

  An earlier telling here called that refusal SILENT. It is not, and
  rf2-e4y9 measured the difference. `validate-target-key!` throws the
  catalogued `:rf.error/mutation-invalid-target` under the strict
  pre-write policy, carrying `:recovery :fix-mutation-target`, the
  offending `:arm`, and the target the caller actually typed; the router
  captures it and fans it on the always-on `:errors` axis as
  `:rf.error/handler-exception`, legible in a dev build AND in a
  production one. The row that pins this is
  `vector-shaped-optimistic-target-refuses-readably` in
  `re-frame.resources-optimistic-validation-cljs-test`. The absent
  instance and the `:idle` read are the RULED answer rather than a hole,
  for rf2-06lp's reason one registrar up: an instance minted and left
  `:pending` with no request behind it would report itself in flight
  forever.

  What no channel carries is a CONSOLE byte. re-frame2 ships no default
  console sink and the router captures the throw rather than re-throwing
  it to `dispatch-sync`'s caller, so a reader with no `:errors` listener
  attached still meets a bare no-op. That residual is framework-wide —
  it swallows an unregistered event id on the same terms — and it is
  exactly rf2-fu75's; this gate neither touches it nor needs it fixed to
  be worth having.

  Both suites beside this one were green throughout, and correctly so:
  they exercise the APPLICATION, which was right. Nothing anywhere
  exercised the SNIPPET, which was wrong.

  ## The rule

  **Every `:optimistic` target printed in a fenced `clojure` block of
  that page must be a target `async_nav.cljs` itself registers, and
  every target on either side must be the `{:resource :params :scope}`
  map the runtime accepts.**

  Both halves are load-bearing and neither implies the other:

  - **The match** catches DRIFT. The page and the application are read
    as data and compared as data, so the published target is the
    application's target down to the resource symbol and the scope
    keyword. Change either side alone and this reds naming both.
  - **The shape** catches BORN-WRONG. The defect above was not drift —
    the snippet was never right, so a rule that only compared the two
    sides would have been satisfied by making the application wrong
    too. The shape row is what refuses that direction.

  This is deliberately NOT the digest-roster mechanism that
  `re-frame.freehand.guide.samples-coverage-jvm-test` runs over
  `docs/core/freehand/`. That gate's question is *did this block change
  since someone last checked it*, and a pin over a block that was wrong
  when it was pinned certifies the wrongness. Here the question is
  answerable outright — the target either is the one on the classpath
  or it is not — so it is asked directly, and no roster has to be kept
  in step. The idiom borrowed from that namespace is the useful half:
  locate the repo by a marker rather than a relative path, split fences
  with a parser that understands nothing, keep the comparison PURE so
  the gate's own teeth can be exercised, and prove those teeth in a
  test.

  ## The population, and why the readability row is not scope creep

  [[every-block-that-teaches-an-optimistic-target-reads]] looks like a
  second concern and is actually this gate's own fail-open guard: a
  block that does not read contributes no targets, and a rule about
  targets is vacuously true over none of them. Without it, mangling the
  snippet past the reader would turn this suite GREEN — the failure one
  level up from the one it was built to catch.

  It is asked of the blocks that TEACH an optimistic plan, not of every
  block on the page, and that boundary was measured rather than
  guessed. Asked page-wide it reds on
  `{:can-leave [::can-leave?] …}` — a map literal with an odd number of
  forms, and a perfectly good thing for a page to print, because `…`
  there means *and the rest of the route*. Elliptical prose-code is how
  this corpus writes a fragment, and a gate that outlawed it would be
  litigating style. What may not be elliptical past reading is the one
  spelling a reader COPIES, which is why the population is that class
  and no wider.

  ## Bounds

  One page, one class, one application. The plan is read in its LITERAL
  shape — `(fn [params] {target …})` — and a plan refactored behind a
  `let` is reported rather than followed, because the report forces a
  look and a clever walker would eventually follow something into a
  false green. Nothing here evaluates a line of the page or of the
  application; this namespace reads text and compares data.

  Filed under rf2-hic-054, from the merged-PR audit of #8045; its
  narrative corrected under the same bead from the audit of #8056, after
  rf2-e4y9 refuted the silence."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; ---------------------------------------------------------------------------
;; Locating the two files
;; ---------------------------------------------------------------------------

(defn- repo-root
  "The repository root, found by walking up for the `AGENTS.md` marker
  rather than by a hardcoded relative path — this suite runs from
  `implementation/routing` locally and from the repo root in CI."
  []
  (or (some (fn [candidate]
              (let [root (.getCanonicalFile (io/file candidate))]
                (when (.isFile (io/file root "AGENTS.md"))
                  root)))
            (take 6 (iterate #(io/file % "..") (io/file "."))))
      (throw (ex-info "Could not locate repository root" {}))))

(def ^:private page-path
  "docs/design/hicasso/product/async-routing-recipes.md")

(def ^:private app-path
  "implementation/routing/test/re_frame/recipes/async_nav.cljs")

(defn- slurp-repo-file
  "Read one repo-relative path, failing with the path rather than with a
  `FileNotFoundException` if the file has moved. A rename SHOULD red
  here: the page and the application are a pair, and half a pair is the
  staleness this gate exists to end."
  [rel]
  (let [f (io/file (repo-root) rel)]
    (when-not (.isFile f)
      (throw (ex-info (str "missing: " rel " — the page and the application are a pair; "
                           "if one moved, move this witness's path with it")
                      {:path rel})))
    (slurp f)))

;; ---------------------------------------------------------------------------
;; Splitting a page into fenced blocks
;; ---------------------------------------------------------------------------

(defn clojure-blocks
  "Every fenced `clojure` block in `text`, in document order, as
  `[{:n page-ordinal :body \"…\"}]`.

  A fence is a line opening with three backticks; the block runs to the
  next such line. That is the whole parser — it does not interpret
  markdown, and `:n` counts ALL fenced blocks so the ordinal a failure
  prints is the one a reader counts down the page."
  [text]
  (let [lines  (vec (str/split (str/replace text "\r\n" "\n") #"\n" -1))
        fence? #(str/starts-with? ^String % "```")]
    (loop [i 0, n 0, acc []]
      (cond
        (>= i (count lines)) acc

        (not (fence? (nth lines i))) (recur (inc i) n acc)

        :else
        (let [lang  (str/trim (subs (nth lines i) 3))
              close (or (first (keep-indexed #(when (fence? %2) (+ i 1 %1))
                                             (subvec lines (inc i))))
                        (count lines))
              body  (subvec lines (inc i) close)]
          (recur (inc close)
                 (inc n)
                 (cond-> acc
                   (= "clojure" lang)
                   (conj {:n (inc n) :body (str/join "\n" body)}))))))))

;; ---------------------------------------------------------------------------
;; Reading, without evaluating
;; ---------------------------------------------------------------------------

(defn read-forms
  "Every top-level form in `s`, as data.

  Read under this namespace so `::foo` resolves to something rather than
  throwing, with the `:cljs` branch of any reader conditional taken (the
  application is ClojureScript) and unknown tagged literals passed
  through. NOTHING is evaluated, and nothing here needs the application
  to be loadable on the JVM — which it is not.

  One consequence to know when reading a failure: an auto-resolved
  keyword prints qualified by THIS namespace, not by the page's or the
  application's. `::articles` comes back as
  `:re-frame.recipes.async-nav-doc-test/articles`. Nothing compares
  across the two files by such a keyword — the targets that matter are
  written with plain symbols and fully-qualified keywords — so the
  qualification is cosmetic, but it is startling if unannounced."
  [^String s]
  (with-open [rdr (java.io.PushbackReader. (java.io.StringReader. s))]
    (binding [*ns*                     (the-ns 're-frame.recipes.async-nav-doc-test)
              *default-data-reader-fn* (fn [_tag value] value)]
      (into [] (take-while #(not= ::eof %))
            (repeatedly #(read {:eof ::eof :read-cond :allow :features #{:cljs}} rdr))))))

;; ---------------------------------------------------------------------------
;; Extracting the optimistic targets — the pure half
;; ---------------------------------------------------------------------------

(def ^:private required-target-keys
  "The keys an optimistic target map carries. Exactly these: a target is
  an IDENTITY, and a partial identity would write the cache somewhere
  nobody asked for."
  #{:resource :params :scope})

(defn- plan-targets
  "The targets one `:optimistic` plan declares, each classified.

  `{:kind :map :target …}` is the accepted shape; `{:kind :rejected
  :target …}` is anything else, which is where the `[id params]` vector
  lands; the two `:not-a-literal-*` kinds report a plan this reader
  cannot see into rather than passing it."
  [plan]
  (let [ret (when (and (seq? plan) (contains? '#{fn fn*} (first plan)))
              (last plan))]
    (cond
      (nil? ret) [{:kind :not-a-literal-fn :form plan}]

      (map? ret) (mapv (fn [target]
                         {:kind   (if (and (map? target)
                                           (= required-target-keys (set (keys target))))
                                    :map
                                    :rejected)
                          :target target})
                       (keys ret))

      :else [{:kind :not-a-literal-map :form ret}])))

(defn optimistic-targets
  "Every optimistic target declared anywhere in `forms`, classified.

  The rule is one line: in any sequential node, an element `:optimistic`
  is followed by its plan. That covers a map's entry (how the
  application spells it, inside `reg-mutation`) and a bare key/value
  fragment (how the page prints it) with no special case for either,
  because a map entry IS a sequential node.

  Pure — [[the-witness-has-teeth]] feeds it synthetic input."
  [forms]
  (into []
        (for [node   (tree-seq coll? seq forms)
              :when  (sequential? node)
              [k v]  (partition 2 1 node)
              :when  (= :optimistic k)
              target (plan-targets v)]
          target)))

(defn- describe
  [{:keys [kind target form]}]
  (case kind
    :map              (str "ok  " (pr-str target))
    :rejected         (str "REJECTED SHAPE — " (pr-str target)
                           " is not a " (pr-str (vec (sort required-target-keys)))
                           " map. An optimistic target is an identity, and a wrong-identity"
                           " target is refused BEFORE the request lowers, so this spelling"
                           " deletes the whole mutation: no instance, no request, :idle"
                           " afterwards. The runtime says why —"
                           " :rf.error/mutation-invalid-target on the always-on :errors"
                           " axis — but only to a listener, never to a console (rf2-fu75),"
                           " so a reader who copies this meets a bare no-op")
    :not-a-literal-fn (str "UNREADABLE PLAN — " (pr-str form)
                           " is not a literal `(fn …)`; this witness reads the literal shape")
    :not-a-literal-map (str "UNREADABLE PLAN — the plan's last form is " (pr-str form)
                            ", not a literal target map")))

;; ---------------------------------------------------------------------------
;; The checks
;; ---------------------------------------------------------------------------

(def ^:private optimistic-token
  "The registration key, spelled so `:optimistic?` — a different keyword,
  the one the READ side uses — cannot be mistaken for it."
  #":optimistic(?![?\w-])")

(defn optimistic-blocks
  "The fenced `clojure` blocks of `text` that teach an optimistic plan.

  This witness's whole population. See the namespace docstring for why
  it is this class and not every block on the page."
  [text]
  (filterv #(re-find optimistic-token (:body %)) (clojure-blocks text)))

(defn- page-blocks [] (optimistic-blocks (slurp-repo-file page-path)))
(defn- page-forms [] (into [] (mapcat (comp read-forms :body)) (page-blocks)))
(defn- app-forms  [] (read-forms (slurp-repo-file app-path)))

(deftest every-block-that-teaches-an-optimistic-target-reads
  (testing "this gate's own fail-open guard. A block that does not read
            contributes no targets, and a rule about targets is vacuously
            true over none of them — so a mangled snippet would turn this
            suite GREEN, one level up from the defect it exists to catch."
    (let [blocks (page-blocks)
          bad    (for [b blocks
                       :let [err (try (read-forms (:body b)) nil
                                      (catch Exception e (ex-message e)))]
                       :when err]
                   (format "%s block %d does not read — %s. A reader COPIES this block; it may
be elliptical, but not past the reader" page-path (:n b) err))]
      (is (pos? (count blocks))
          (str page-path " publishes no `:optimistic` plan at all — either the page "
               "stopped teaching one, or the fence language changed and this "
               "witness is now grading nothing"))
      (is (= [] (vec bad)) (str "\n" (str/join "\n" bad) "\n")))))

(deftest every-optimistic-target-is-the-map-the-runtime-accepts
  (testing "the BORN-WRONG half. The page's snippet was never right, so a
            rule that only compared the two sides would have been satisfied
            by making the application wrong too."
    (doseq [[what targets] [[page-path (optimistic-targets (page-forms))]
                            [app-path  (optimistic-targets (app-forms))]]]
      (is (pos? (count targets))
          (str what " declares no `:optimistic` target this reader can see. That is not a "
               "pass: it is this witness grading nothing"))
      (is (= [] (vec (for [t targets :when (not= :map (:kind t))]
                       (str what " — " (describe t)))))
          (str "\n" (str/join "\n" (for [t targets :when (not= :map (:kind t))]
                                     (str what " — " (describe t))))
               "\n")))))

(deftest the-page-publishes-only-targets-the-application-registers
  (testing "the DRIFT half. Both sides are read as data and compared as
            data, so the published target is the application's own down to
            the resource symbol and the scope keyword. The direction is a
            subset — the page may report fewer targets than the application
            registers, but never one the application does not have."
    (let [published (into #{} (map :target) (optimistic-targets (page-forms)))
          registered (into #{} (map :target) (optimistic-targets (app-forms)))
          orphans   (set/difference published registered)]
      (is (= #{} orphans)
          (str "\n" page-path " publishes " (count orphans)
               " optimistic target(s) that " app-path " does not register.\n"
               "  published, unmatched: " (pr-str (vec orphans)) "\n"
               "  registered:           " (pr-str (vec registered)) "\n"
               "A reader copies the page, not the test. Make the snippet mirror the "
               "application, or change the application first.\n")))))

(deftest the-witness-has-teeth
  (testing "a gate that cannot fail is the silence it was built to end, so
            every way this one is supposed to red is exercised against
            synthetic input rather than trusted."
    (let [kinds #(mapv :kind (optimistic-targets (read-forms %)))]

      (testing "the required map spelling passes, as a map entry and as a
                bare page fragment — one rule, no special case"
        (is (= [:map] (kinds (str "(rf/reg-mutation m {:scope :rf.scope/global"
                                  " :optimistic (fn [p] {{:resource r :params {}"
                                  " :scope :rf.scope/global} (fn [xs] xs)})})"))))
        (is (= [:map] (kinds (str ":optimistic (fn [p] {{:resource r :params {}"
                                  " :scope :rf.scope/global} (fn [xs] …)})")))))

      (testing "the rejected [id params] vector — the exact published defect — reds"
        (is (= [:rejected] (kinds ":optimistic (fn [p] {[r {}] (fn [xs] …)})"))))

      (testing "so does a PARTIAL identity, which is the near-miss a shape
                check written as `map?` would wave through"
        (is (= [:rejected] (kinds ":optimistic (fn [p] {{:resource r :params {}} (fn [xs] …)})"))))

      (testing "a plan this reader cannot see into is reported, not passed"
        (is (= [:not-a-literal-fn] (kinds ":optimistic (my-plan-builder r)")))
        (is (= [:not-a-literal-map] (kinds ":optimistic (fn [p] (build-targets p))"))))

      (testing "`:optimistic?` is a different keyword and mints no target —
                the page and the application both use it for the READ"
        (is (= [] (kinds "{:optimistic? true}"))))

      (testing "no `:optimistic` at all yields nothing, which is exactly why
                both counts are asserted positive above"
        (is (= [] (kinds "(rf/reg-mutation m {:scope :rf.scope/global})")))))

    (testing "and the match is on the target's CONTENT, not merely its shape"
      (let [targets #(into #{} (map :target) (optimistic-targets (read-forms %)))
            global  ":optimistic (fn [p] {{:resource r :params {} :scope :rf.scope/global} f})"
            framed  ":optimistic (fn [p] {{:resource r :params {} :scope :rf.scope/frame} f})"
            other   ":optimistic (fn [p] {{:resource q :params {} :scope :rf.scope/global} f})"]
        (is (= (targets global) (targets global)) "the same target matches itself")
        (is (not= (targets global) (targets framed)) "a drifted scope is caught")
        (is (not= (targets global) (targets other)) "so is a drifted resource")))

    (testing "and the fence parser finds only `clojure` blocks, by ordinal"
      (let [blocks (clojure-blocks "prose\n```text\nnot clojure\n```\nmore\n```clojure\n(inc 1)\n```\n")]
        (is (= [2] (mapv :n blocks)) "the ordinal counts every fence, not just the clojure ones")
        (is (= ["(inc 1)"] (mapv :body blocks)))))

    (testing "and the population is the blocks that TEACH a plan — the page's
              other fragments stay elliptical, which is the boundary that was
              measured rather than guessed"
      (let [page (str "```clojure\n(routing/reg-route ::editor {:can-leave [::c] …} \"/x\")\n```\n"
                      "```clojure\n:optimistic (fn [p] {{:resource r :params {} :scope :s} f})\n```\n"
                      "```clojure\n[:rf/mutation {:instance i :optimistic? true}]\n```\n")]
        (is (= [2] (mapv :n (optimistic-blocks page)))
            "the elliptical route fragment is out of the class, and `:optimistic?` is
             a different keyword")
        (is (thrown? Exception (read-forms (:body (first (clojure-blocks page)))))
            "and that fragment genuinely does not read — the boundary is doing work")))))
