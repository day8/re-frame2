(ns re-frame.conformance-test
  "JVM leaf of the conformance corpus runner. All host-neutral logic lives in
  `re-frame.conformance-runner`, shared with the CLJS leaf
  `re-frame.conformance-corpus-cljs-test`; this leaf owns only the JVM seams
  handed to it as a host map: fixture loading from disk, the inter-fixture
  reset (`clear-all!` + `(require … :reload)`), and trace-listener access.

  The runner's self-tests live here: the runner is one `.cljc`, so a check
  that it bites on the JVM is the same check on CLJS. The classification-op
  guard keeps a CLJS mirror too, because the fixture grammar in
  spec/conformance/fixture-file.edn names both as its durable negative.

  The grammar is also what the runner's matcher tables answer to:
  `matcher-tables-cover-the-grammar` holds them to exactly the expectations
  it declares, `graded-expectations-run-on-every-fixture-carrying-them`
  proves no fixture carrying one escapes its matcher, and
  `every-matcher-bites` plants a wrong expected value, built from the law
  the grammar names, under each matcher and requires the fixture to fail.

  The corpus run and the self-tests that grade a fixture end to end carry
  `^:requires-debug`: the runner reads trace emissions, routed effects and
  the SSR public error off the dev trace and epoch records off the
  trace-fed epoch ring, and none of them exists under
  `-Dre-frame.debug=false`. The fixtures whose grading reads none of those
  surfaces (`rf.conformance-runner/prod-posture-fixture?`) run a second time
  in `run-conformance-corpus-prod-posture`, which is untagged, so the
  production-gate lane grades them under the gate."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.flows :as rf.flows]
            [re-frame.schemas :as rf.schemas]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.request :as rf.ssr.request]
            [re-frame.ssr.response :as rf.ssr.response]
            ;; Publishes the late-bind hook the default validator routes
            ;; through; without it the schema fixtures soft-pass on the JVM.
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.late-bind :as rf.late-bind]
            ;; Side-effect requires: registrations the fixtures reference,
            ;; re-seated by `reset-runtime!`'s `:reload`s.
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.routing.test-support]
            [re-frame.epoch]
            [re-frame.resources]
            [re-frame.resources.test-support]
            [re-frame.conformance-runner :as rf.conformance-runner]))

;; ---- fixture loader (JVM-specific: fs) ------------------------------------

(def fixtures-dir
  ;; Anchored to this namespace's own source on the classpath rather than the
  ;; cwd; five parents up is the repo root.
  (let [res (io/resource "re_frame/conformance_test.clj")]
    (assert res
            (str "conformance-test cannot locate its own source on the "
                 "classpath — the core test/ dir must be on the test "
                 "classpath for fixture discovery to anchor."))
    (-> (io/file res)        ; .../core/test/re_frame/conformance_test.clj
        .getParentFile       ; .../core/test/re_frame
        .getParentFile       ; .../core/test
        .getParentFile       ; .../core
        .getParentFile       ; .../implementation
        .getParentFile       ; repo root
        (io/file "spec" "conformance" "fixtures")
        .getCanonicalFile)))

(defn read-one-form
  "Read `text` as EXACTLY ONE top-level EDN form, or throw. Six sibling
  runners carry the same body — no artefact puts `core/test` on another's
  classpath, so there is no shared home for it below `src/` — and this
  docstring is where the reasoning lives.

  WHY NOT `edn/read-string` DIRECTLY. It returns the FIRST form and
  silently ignores everything after it. So a fixture whose expectation
  block closes one brace early still loads, still runs, and still reports
  as PASSING — with every assertion that fell outside the block discarded.

  WHY NOT WRAP THE TEXT AS `[<text>]` EITHER. Counting the elements of a
  SYNTHETIC vector is a guard the guarded text can walk straight out of: a
  fixture that closes the envelope itself with an early `]` yields a
  ONE-element vector, so the count check passes and everything after that
  `]` is discarded in silence — recreating the exact truncation class the
  check exists to remove. The text `{:fixture/id :first}`, newline,
  `] {:fixture/id :silently-hidden}` would return `#:fixture{:id :first}`
  without throwing.

  SO READ THE ORIGINAL TEXT — no envelope, hence nothing to escape from —
  and PROVE EOF behind the first form with a second read against a
  sentinel. `one-form-guard-rejects-early-close-bracket` below pins both
  directions.

  WHY IT THROWS rather than returning a load-error map. A runner handed a
  load-error map in place of a fixture can only skip it or drop it, so a
  caught parse error would be exactly as silent as the defect it is meant
  to catch — the fixture would stop passing falsely and start vanishing
  quietly instead. Corpus malformation is a repository defect, not a
  per-fixture runtime condition, so it fails the run. The `catch` in the
  body below is NOT such a catch: it RE-THROWS, and exists only so a
  reader error arrives naming the fixture it came out of.

  The corpus scanner (`scripts/check_conformance_fixture_edn.py`)
  is the complementary half: it sees fixtures whose capabilities are
  unclaimed, which this check never loads at all."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why
                                    " (see re-frame.conformance-test/read-one-form)")
                               (assoc data :fixture/file fixture-name))))
        rd   (fn []
               (try (edn/read {:eof eof} rdr)
                    (catch Exception e
                      (fail (str "is not readable EDN: " (.getMessage e))
                            {:fixture/reader-error (.getMessage e)}))))
        form (rd)]
    (when (identical? eof form)
      (fail "holds no top-level EDN form" {:fixture/forms 0}))
    (when-not (identical? eof (rd))
      (fail (str "must hold exactly ONE top-level EDN form — a plain read"
                 " returns the first and silently discards the rest")
            {:fixture/forms :more-than-one}))
    form))

(defn- load-fixture
  "Read one fixture. The corpus is plain EDN, so an auto-resolved `::name`
  fails here as not readable EDN, as the corpus scanner refuses it."
  [file]
  (read-one-form (slurp file) (.getName file)))

(defn all-fixtures []
  (->> (file-seq fixtures-dir)
       (filter #(.isFile %))
       (filter #(string/ends-with? (.getName %) ".edn"))
       (map (fn [f] [(.getName f) (load-fixture f)]))))

;; ---- runtime reset (JVM-specific: clear-all! + require :reload) ------------

(defn- reset-runtime! []
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  ;; The per-frame SSR side channel outlives the `frames` reset, and
  ;; `:ssr/request-result` reads its response slot.
  (reset! rf.ssr.request/request-slots {})
  (reset! rf.ssr.response/response-slots {})
  (reset! rf.ssr.error-listener/pending-error-traces {})
  (rf.error-emit/clear-error-listeners!)
  ;; `:epoch-records` must observe only this fixture's epochs.
  (when-let [f (rf.late-bind/get-fn :epoch/clear-history!)]
    (f))
  (when-let [f (rf.late-bind/get-fn :epoch/clear-epoch-listeners!)]
    (f))
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; clear-all! wiped every ns-load registration; re-evaluate them. The
  ;; routing reload keeps `:rf/route` a RUNTIME sub (a hand `reg-sub` would
  ;; make it an app-db sub).
  (require 're-frame.cofx :reload)
  (require 're-frame.routing :reload)
  (require 're-frame.routing.test-support :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.http.managed :reload)
  (require 're-frame.http.test-support :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.resources :reload)
  (require 're-frame.resources.test-support :reload)
  ;; Host-side counters and caches outlive the `frames` reset; zero them so
  ;; ids and resource generations are stable across fixtures.
  ((requiring-resolve 're-frame.resources.test-support/reset-resources!))
  ((requiring-resolve 're-frame.routing/reset-counters!))
  ((requiring-resolve 're-frame.routing/reset-nav-counters!))
  ((requiring-resolve 're-frame.machines/reset-timers!))
  ((requiring-resolve 're-frame.http.managed/clear-all-in-flight!))
  ;; a `defonce` atom that survives `:reload`
  ((requiring-resolve 're-frame.http.managed/clear-all-http-interceptors!)))

;; ---- host map --------------------------------------------------------------

(def ^:private host
  ;; Fixture-end cleanup clears ALL listeners; the SSR error-projection
  ;; listener is re-registered by the next reset's `re-frame.ssr` reload.
  {:reset-runtime!             reset-runtime!
   :register-trace-listener!   (fn [fixture-id listener]
                                 (rf.trace.tooling/register-listener! [fixture-id] listener))
   :unregister-trace-listener! (fn [_fixture-id]
                                 (rf.trace.tooling/clear-listeners!))})

;; ---- the test entrypoint --------------------------------------------------

(deftest ^:requires-debug run-conformance-corpus
  (rf.conformance-runner/run-corpus (all-fixtures) host "JVM"))

;; The production-posture subset, in both lanes. Its floor sits four fixtures
;; under the subset's size, so a fixture leaving the subset — by gaining a
;; dev-only expectation — goes unnoticed only until a handful have.
(deftest run-conformance-corpus-prod-posture
  (rf.conformance-runner/run-corpus
    (filter (comp rf.conformance-runner/prod-posture-fixture? second) (all-fixtures))
    host "JVM production-posture" 115))

;; ---- runner self-tests: each proves a check bites rather than no-ops -------

;; The early-close row is the discriminating one: a `[<text>]` implementation
;; reads it as a one-element vector and drops the second map in silence.
(deftest one-form-guard-rejects-early-close-bracket
  (doseq [text ["{:fixture/id :first}\n] {:fixture/id :hidden}"
                "{:fixture/id :first}\n{:fixture/id :second}"
                "\n;; only a comment\n"]]
    (is (thrown? clojure.lang.ExceptionInfo (read-one-form text "f.edn"))
        (pr-str text)))
  (is (= {:fixture/id :ok}
         (read-one-form "\n;; leading comment\n{:fixture/id :ok}\n;; trailing\n"
                        "commented.edn"))
      "comments and surrounding whitespace are not trailing forms"))

(deftest ^:requires-debug epoch-records-checked-on-jvm
  ;; The real drain records `:outcome :ok`, so this expectation must fail the
  ;; fixture, attributed to the epoch matcher.
  (let [result (rf.conformance-runner/run-fixture
                 {:fixture/id           :rf.test/epoch-records-deliberate-mismatch
                  :fixture/spec-version "1.0"
                  :fixture/capabilities #{:core/event-handler :core/trace}
                  :fixture/handlers     {:event {:counter/inc [[:update [:count] [:fn :inc]]]}}
                  :fixture/frame-config {}
                  :fixture/dispatches   [[:counter/inc]]
                  :fixture/expect       {:epoch-records
                                         [{:frame  :rf/default
                                           :record {:event-id :counter/inc
                                                    :outcome  :rf.test/DELIBERATELY-WRONG}}]}}
                 host)]
    (is (not (:passed? result)))
    (is (seq (get-in result [:expect-failures :epoch-records])))))

;; ---- unknown keys -----------------------------------------------------------

(def ^:private grammar
  (edn/read-string (slurp (io/file (.getParentFile fixtures-dir) "fixture-file.edn"))))

(def ^:private counter-fixture
  {:fixture/id           :rf.test/unknown-key-guard
   :fixture/spec-version "1.0"
   :fixture/doc          "A counter set to 1."
   :fixture/capabilities #{:core/event-handler}
   :fixture/handlers     {:event {:counter/set [[:set [:count] 1]]}}
   :fixture/frame-config {}
   :fixture/dispatches   [[:counter/set]]
   :fixture/expect       {:final-app-db {:count 1}}})

;; An expectation key with no matcher fails the fixture on both hosts, so
;; the CLJS lane needs no grammar validation to refuse one.
(deftest unknown-expect-key-fails-the-fixture
  (is (:passed? (rf.conformance-runner/run-fixture counter-fixture host))
      "control: the fixture passes as written")
  (let [result (rf.conformance-runner/run-fixture
                 (assoc-in counter-fixture [:fixture/expect :rf.test/no-such-expectation] 1)
                 host)]
    (is (not (:passed? result)))
    (is (contains? (:expect-failures result) :rf.test/no-such-expectation)
        "the failure names the key")))

;; A misspelt setup key silently changes the scenario. The JVM grammar
;; validation (`re-frame.conformance-grammar-test`) refuses it over the whole
;; corpus; this is its negative.
(deftest unknown-top-level-key-fails-the-grammar
  (let [schema (m/schema [:schema {:registry (:registry grammar)} (:root grammar)])]
    (is (m/validate schema counter-fixture) "control: the fixture conforms as written")
    (is (= [[:fixture/dispatchess]]
           (map :in (:errors (m/explain schema (assoc counter-fixture
                                                      :fixture/dispatchess [[:counter/set]])))))
        "the misspelt key is refused, by its path")))

;; ---- the matcher tables against the fixture grammar -------------------------

(defn- map-entries
  "The `[key props schema]` entries of a grammar `[:map props? & entries]`."
  [[_ & children]]
  (filter vector? children))

(defn- expectation-props
  "The props of a grammar map entry the grammar annotates as an expectation."
  [[_ props]]
  (when (and (map? props) (contains? props :observe)) props))

(def ^:private grammar-expectations
  "Every expectation the grammar declares, `{path {:law … :element-law …}}`:
  `[key]` for a `:fixture/expect` key, `[op key]` for a call-record key, and
  `[op]` for a call record whose whole claim is its observation."
  (let [registry           (:registry grammar)
        [_ _ & call-arms]  (:conformance/call registry)]
    (into {}
          (concat
            (for [entry (map-entries (:conformance/expect registry))
                  :let  [props (expectation-props entry)] :when props]
              [[(first entry)] props])
            (for [[op [_ record-props :as record]] call-arms
                  path+props (cons (when (:observe record-props) [[op] record-props])
                                   (for [entry (map-entries record)
                                         :let  [props (expectation-props entry)] :when props]
                                     [[op (first entry)] props]))
                  :when path+props]
              path+props)))))

(def ^:private table-expectations
  "Every expectation the runner's tables grade, as `grammar-expectations`
  paths."
  (set (concat (for [[k _] rf.conformance-runner/expect-matchers] [k])
               (for [[op {:keys [holds matchers]}] rf.conformance-runner/call-matchers
                     path (concat (when holds [[op]]) (for [k (keys matchers)] [op k]))]
                 path)
               (keys rf.conformance-runner/sibling-graded))))

(deftest matcher-tables-cover-the-grammar
  (let [declared (set (keys grammar-expectations))]
    (is (< 50 (count declared)) "the grammar walk reads the expectations")
    (is (empty? (remove table-expectations declared))
        "expectations the grammar declares with no matcher")
    (is (empty? (remove declared table-expectations))
        "matchers for an expectation the grammar does not declare")
    (is (apply distinct? (map first rf.conformance-runner/expect-matchers))
        "one matcher per :fixture/expect key")
    (is (not-any? (comp rf.conformance-runner/call-matchers first)
                  (keys rf.conformance-runner/sibling-graded))
        "a sibling-graded expectation has no matcher here")))

(defn- fixture-expectations
  "The grammar expectation paths `fixture` carries."
  [fixture]
  (filter grammar-expectations
          (concat (for [k (keys (:fixture/expect fixture))] [k])
                  (for [record (:fixture/calls fixture)
                        path   (cons [(:call record)]
                                     (for [k (keys record)] [(:call record) k]))]
                    path))))

(deftest graded-expectations-run-on-every-fixture-carrying-them
  (let [escapes
        (for [[fname fixture] (sort-by first (all-fixtures))
              :let [{:keys [allowed unknown]} (rf.conformance-runner/classify-capabilities fixture)
                    runnable? (and (empty? allowed) (empty? unknown))]
              path (fixture-expectations fixture)
              :let [{:keys [runner prefix]} (get rf.conformance-runner/sibling-graded path)]
              :when (if runner
                      (not (string/starts-with? fname prefix))
                      (not runnable?))]
          (str fname " carries " path
               (if runner
                 (str ", graded by " runner ", which loads only " prefix "*.edn")
                 ", but this runner does not claim the fixture's capabilities")))]
    (is (empty? escapes) (string/join "\n" escapes))))

;; ---- every matcher bites ----------------------------------------------------
;;
;; A matcher that passes whatever it is handed reads green over the whole
;; corpus, so each one is run against a wrong expected value: the first
;; runnable corpus fixture carrying the key, with that key's value replaced
;; by one the law the grammar names must refuse.

(defn- plant-submap [v]
  (cond (vector? v) (update v 0 plant-submap)
        (map? v)    (if-let [[k inner] (first (filter (comp map? val) v))]
                      (assoc v k (plant-submap inner))
                      (assoc v ::planted ::wrong))
        :else       ::planted))

(defn- plant
  "A wrong expected value for `expected`, by the law the grammar names."
  [{:keys [law element-law]} expected]
  (case law
    :submap          (plant-submap expected)
    :equal           (cond (and (map? expected) (seq expected))
                           (assoc expected (key (first expected)) ::wrong)
                           (or (vector? expected) (set? expected)) (conj expected ::planted)
                           :else ::planted)
    :ordered-subset  (conj expected (if (= :equal element-law) [::planted nil] {::planted ::wrong}))
    :positional      (conj expected {:record {::planted ::wrong}})
    ;; The empty shape matches every trace.
    :none-match      (conj expected {})
    ;; The empty path reads the whole db, which is always present.
    :absent-paths    (conj expected [])
    :substring       (conj expected "rf.test/planted")
    :thrown-error-id ::planted
    :member          (conj expected (if (vector? (first expected)) [::planted ::planted] {::planted ::wrong}))))

(def ^:private targeted-plants
  "Plants the law alone cannot build. A `:non-member` key needs an element
  the observation does hold, and a `:holds` record has no expected value, so
  its inputs are perturbed instead."
  {[:assemble-image :expect-absent]
   (fn [record] (update record :expect-absent conj ((juxt :kind :id) (first (:expect-resolves record)))))
   [:assemble-image :expect-gen-absent]
   (fn [record] (update record :expect-gen-absent conj :rf.gen/resolver))
   [:derivation-graph :expect-absent-edges]
   (fn [record] (update record :expect-absent-edges conj (first (:expect-edges record))))
   [:round-trip]          (fn [record] (assoc record :url "rf.test/planted"))
   [:assert-rank-greater] (fn [record] (assoc record :winner (:loser record) :loser (:winner record)))
   [:canonical-identical] (fn [record] (assoc record :b [::planted]))
   [:canonical-distinct]  (fn [record] (assoc record :b (:a record)))})

(defn- planted-run
  "Run the first runnable corpus fixture carrying `path` with a wrong value
  planted under it; nil when no runnable fixture carries it."
  [fixtures [k-or-op call-key :as path]]
  (let [law     (grammar-expectations path)
        planter (or (targeted-plants path)
                    (fn [record] (update record call-key #(plant law %))))]
    (some (fn [[_ fixture]]
            (if (= 1 (count path))
              (cond
                (contains? (:fixture/expect fixture) k-or-op)
                (rf.conformance-runner/run-fixture
                  (update-in fixture [:fixture/expect k-or-op] #(plant law %)) host)

                (contains? targeted-plants path)
                (when-let [record (first (filter #(= k-or-op (:call %)) (:fixture/calls fixture)))]
                  (rf.conformance-runner/run-fixture
                    (assoc fixture :fixture/calls [(planter record)]) host)))
              (when-let [record (first (filter #(and (= k-or-op (:call %)) (contains? % call-key))
                                               (:fixture/calls fixture)))]
                (rf.conformance-runner/run-fixture
                  (assoc fixture :fixture/calls [(planter record)]) host))))
          fixtures)))

(deftest ^:requires-debug every-matcher-bites
  (let [fixtures (->> (all-fixtures)
                      (sort-by first)
                      (filter (fn [[_ fixture]]
                                (let [{:keys [allowed unknown]}
                                      (rf.conformance-runner/classify-capabilities fixture)]
                                  (and (empty? allowed) (empty? unknown))))))
        paths    (remove rf.conformance-runner/sibling-graded (keys grammar-expectations))
        bit?     (fn [[k-or-op call-key :as path] result]
                   (if (and (= 1 (count path)) (not (rf.conformance-runner/call-matchers k-or-op)))
                     (seq (get-in result [:expect-failures k-or-op]))
                     (and (not (:passed? result))
                          (string/includes? (str (:error result)) (str (name k-or-op) " "))
                          (or (nil? call-key)
                              (string/includes? (str (:error result)) (str call-key))))))
        survivors (for [path  paths
                        :let  [result (planted-run fixtures path)]
                        :when (not (and result (bit? path result)))]
                    (str (pr-str path) (if result
                                         (str " stayed green: " (pr-str (select-keys result [:passed? :error :expect-failures])))
                                         " is carried by no runnable fixture")))]
    (is (< 50 (count paths)))
    (is (empty? survivors) (string/join "\n" survivors))))

;; ---- neuter probe for routing/door-parity ---------------------------------
;;
;; The runner's matchers are order-preserving SUBSET matchers, so a door
;; asserted only by the absence of a further effect would pass with that door
;; deleted. Each door has a distinct destination; this probe holds that.

(defn- corpus-fixture [fname]
  (or (some (fn [[n f]] (when (= n fname) f)) (all-fixtures))
      (throw (ex-info (str fname " is missing from the corpus") {}))))

(defn- door-parity-fixture []
  (corpus-fixture "routing-door-parity.edn"))

(defn- without-door
  "The fixture with the 0-based `:fixture/dispatches` index `idx` removed."
  [fixture idx]
  (update fixture :fixture/dispatches
          #(vec (keep-indexed (fn [i d] (when-not (= idx i) d)) %))))

(deftest ^:requires-debug door-parity-fixture-bites
  (let [fixture (door-parity-fixture)
        passes? (fn [f] (:passed? (rf.conformance-runner/run-fixture f host)))]
    (is (passes? fixture) "the door-parity fixture must pass as shipped")
    (doseq [[door idx] [["named-address" 0] ["raw-URL" 1] ["URL-driven" 2]]]
      (is (not (passes? (without-door fixture idx)))
          (str "deleting the " door " door must red the door-parity fixture")))
    ;; Deleting an expectation can never fail a subset matcher, so the
    ;; `:fixture/calls` leg is probed by perturbing one instead.
    (is (not (passes? (assoc fixture :fixture/calls
                            [{:call     :route-url
                              :route-id :route/article
                              :params   {:slug "raw-url"}
                              :expect   "/articles/DELIBERATELY-WRONG"}])))
        "a wrong :fixture/calls expectation must red the door-parity fixture")))

;; ---- :fixture/compute-subs ---------------------------------------------------
;;
;; The one corpus fixture carrying the key also computes the same subs under
;; `:sub-values`, so the corpus alone cannot tell whether the runner invokes
;; the queries.

(deftest ^:requires-debug compute-subs-invokes-each-query
  (let [fixture (corpus-fixture "error-sub-exception.edn")
        passes? (fn [f] (:passed? (rf.conformance-runner/run-fixture f host)))
        unknown (rf.conformance-runner/run-fixture
                  (assoc fixture :fixture/compute-subs [[:no/such-sub]]) host)]
    (is (passes? fixture) "the fixture passes as shipped")
    (is (passes? (update fixture :fixture/expect dissoc :sub-values))
        "computing [:cart/count] alone emits the sub-exception trace the fixture expects")
    (is (not (:passed? unknown)) "a query naming no registered sub fails the fixture")
    (is (some #(string/includes? % "[:no/such-sub]") (:compute-sub-failures unknown))
        "the failure names the query")))

;; ---- the :expect-graph guard ----------------------------------------------

(deftest derivation-graph-expect-graph-guard
  (reset-runtime!)
  (doseq [[call pass?] [[{:mode :live   :expect-graph {:mode :live :frame :rf/default}}   true]
                        [{:mode :live   :expect-graph {:mode :live :frame :rf/other}}     false]
                        ;; the static graph is frame-agnostic
                        [{:mode :static :expect-graph {:mode :static :frame :rf/default}} false]
                        [{:mode :static :expect-graph {:mode :static}}                    true]]]
    (is (= pass? (:passed? (rf.conformance-runner/run-call
                             (assoc call :call :derivation-graph))))
        (pr-str call))))

;; ---- the classification-op guard ------------------------------------------
;;
;; Every live corpus op is a valid single-axis map, so nothing in the corpus
;; reds if `realise-classification-effects!`'s one-axis check is deleted;
;; without it the runner's priority-ordered `cond` applies a multi-axis op's
;; first arm only and an empty or unknown-key op not at all.

(defn- classification-op-fixture [ops]
  {:fixture/id           :rf.test/classification-op-guard
   :fixture/spec-version "1.0"
   :fixture/capabilities #{:core/event-handler}
   :fixture/handlers     {:event {:dc/store [[:set [:secret] "s"]]}}
   :fixture/frame-config {}
   :fixture/classification-effects ops
   :fixture/dispatches   [[:dc/store]]
   :fixture/expect       {:final-app-db {:secret "s"}}})

(deftest classification-op-map-guard
  (doseq [[label ops] [["an empty op-map"        [{}]]
                       ["a multi-axis op-map"    [{:sensitive [[:secret]] :large [[:secret]]}]]
                       ["an unknown-axis op-map" [{:rf.test/bogus [[:secret]]}]]
                       ["a known axis beside an unknown key"
                        [{:sensitive [[:secret]] :rf.test/bogus [[:secret]]}]]]]
    ;; `:error` is set only on the failing (catch) path
    (is (re-find #"unrecognised :fixture/classification-effects op-map"
                 (str (:error (rf.conformance-runner/run-fixture
                                (classification-op-fixture ops) host))))
        (str label " must be refused by the classification-op guard")))
  ;; Control: the valid single-axis shape still runs the fixture to a pass.
  (let [result (rf.conformance-runner/run-fixture
                 (classification-op-fixture [{:sensitive [[:secret]]}]) host)]
    (is (:passed? result)
        (pr-str (select-keys result [:error :final-db :expected-db])))))
