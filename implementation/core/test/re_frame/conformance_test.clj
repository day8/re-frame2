(ns re-frame.conformance-test
  "JVM leaf of the conformance corpus runner. All host-neutral logic lives in
  `re-frame.conformance-runner`, shared with the CLJS leaf
  `re-frame.conformance-corpus-cljs-test`; this leaf owns only the JVM seams
  handed to it as a host map: fixture loading from disk, the inter-fixture
  reset (`clear-all!` + `(require … :reload)`), and trace-listener access.

  The runner's self-tests live here only: the runner is one `.cljc`, so a
  check that it bites on the JVM is the same check on CLJS."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.flows :as rf.flows]
            [re-frame.schemas :as rf.schemas]
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

  WHY IT THROWS rather than returning a load-error map. A `try`/`catch`
  that turned any load failure into a `{:fixture/load-error ...}` map
  would be silent: `conformance-runner/run-corpus` classifies that map as
  `:skipped? true` while `machines-conformance-test` filters those
  fixtures out altogether. A caught parse error would therefore be
  exactly as silent as the defect it is meant to catch — the fixture
  would stop passing falsely and start vanishing quietly instead. Corpus
  malformation is a repository defect, not a per-fixture runtime
  condition, so it fails the run. The `catch` in the body below is NOT
  such a catch: it RE-THROWS, and exists only so a reader error arrives
  naming the fixture it came out of.

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

(defn- load-fixture [file]
  ;; pure clojure.edn cannot read an auto-resolved `::name`, so rewrite only
  ;; one that begins a token (the lookbehind spares a `::` inside a string
  ;; such as the CEDN-1 token `"k::answer"`).
  (let [raw   (slurp file)
        fixed (string/replace raw #"(?<=[\s(\[{])::([a-zA-Z][a-zA-Z0-9_-]*)"
                              ":rf.machine.timer/$1")]
    (read-one-form fixed (.getName file))))

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

(deftest run-conformance-corpus
  (rf.conformance-runner/run-corpus (all-fixtures) host "JVM"))

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

(deftest epoch-records-checked-on-jvm
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
    (is (seq (:epoch-failures result)))))

(deftest unknown-expect-key-fails-loud
  (is (seq (rf.conformance-runner/unknown-expect-keys
             {:fixture/expect {:rf.test/no-such-expectation 1}}))))

;; ---- neuter probe for routing/door-parity ---------------------------------
;;
;; The runner's matchers are order-preserving SUBSET matchers, so a door
;; asserted only by the absence of a further effect would pass with that door
;; deleted. Each door has a distinct destination; this probe holds that.

(defn- door-parity-fixture []
  (or (some (fn [[n f]] (when (= n "routing-door-parity.edn") f)) (all-fixtures))
      (throw (ex-info "routing-door-parity.edn is missing from the corpus" {}))))

(defn- without-door
  "The fixture with the 0-based `:fixture/dispatches` index `idx` removed."
  [fixture idx]
  (update fixture :fixture/dispatches
          #(vec (keep-indexed (fn [i d] (when-not (= idx i) d)) %))))

(deftest door-parity-fixture-bites
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
