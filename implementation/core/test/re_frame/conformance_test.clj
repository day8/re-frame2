(ns re-frame.conformance-test
  "JVM LEAF of the conformance corpus runner (rf2-xurchk).

  All host-neutral logic — capability claims, fixture realisation, call
  execution, expectation matchers (including `:epoch-records`), result
  assembly, and reporting — lives in `re-frame.conformance-runner` (a
  test-only `.cljc` shared byte-for-byte with the CLJS leaf
  `re-frame.conformance-corpus-cljs-test`). This leaf owns only the
  genuinely host-specific seams:

    - FIXTURE LOADING — `slurp` + `clojure.edn/read-string` over the on-disk
      `spec/conformance/fixtures/*.edn` corpus (CLJS inlines them at
      compile time instead).
    - RESET / ISOLATION — `rf.registrar/clear-all!` + `(require … :reload)` to
      re-establish framework registrations between fixtures (CLJS has no
      `:reload`, so it snapshots/restores the registrar instead).
    - TRACE-LISTENER registry access — `re-frame.trace` on the JVM (CLJS
      uses `re-frame.trace.tooling` per the production-DCE split).

  These are handed to the shared runner as a HOST MAP. The requires below
  are the JVM classpath + ns-load side-effect surface the corpus exercises
  (Malli validator hook, canned HTTP stubs, test-support events); most
  handler calls now live in the runner, which pulls its own deps."
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
            ;; Per rf2-t0hq + rf2-qyfie — the Malli adapter ns must be
            ;; required at boot to publish the late-bind hook the default
            ;; validator routes through. Absent the require the validator
            ;; soft-passes (no failure traces) and the schema fixtures'
            ;; Malli-backed outcomes wouldn't surface. JVM-specific: the CLJS
            ;; leaf reaches the same outcomes without it.
            [re-frame.schemas.malli]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace :as rf.trace]
            [re-frame.late-bind :as rf.late-bind]
            ;; Side-effect requires — publish registrations the fixtures
            ;; reference. reset-runtime! `:reload`s these between fixtures.
            [re-frame.http.managed]
            [re-frame.http.test-support]
            [re-frame.routing.test-support]
            ;; rf2-v0jwt / rf2-xurchk — the epoch artefact publishes the
            ;; late-bind hooks (`:epoch/settle!`, `:epoch/clear-history!`,
            ;; `:epoch/clear-epoch-listeners!`, `:epoch/epoch-history`) the
            ;; router calls to commit drain-boundary records and the runner
            ;; reads for `:epoch-records`. The shared runner also requires it,
            ;; but keeping it explicit here documents the reset-runtime! epoch
            ;; clear below.
            [re-frame.epoch]
            [re-frame.resources]
            [re-frame.resources.test-support]
            ;; The shared, host-neutral runner (rf2-xurchk).
            [re-frame.conformance-runner :as rf.conformance-runner]))

;; ---- fixture loader (JVM-specific: fs) ------------------------------------

(def fixtures-dir
  ;; The conformance corpus lives under spec/conformance/fixtures at the repo
  ;; root. Anchored to a CLASSPATH RESOURCE, not the working directory
  ;; (rf2-ywrwkl): this namespace's own source file is on the test classpath,
  ;; so resolving it via io/resource pins the anchor to the on-disk source
  ;; location regardless of cwd or which alias loaded the namespace. Walking
  ;; five parents (conformance_test.clj → re_frame → test → core →
  ;; implementation → repo root) reaches the repo root.
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
  classpath, so there is no shared home for it below `src/` — and each
  cites this docstring rather than restating it (rf2-98ni).

  WHY NOT `edn/read-string` DIRECTLY. It returns the FIRST form and
  silently ignores everything after it. So a fixture whose expectation
  block closes one brace early still loads, still runs, and still reports
  as PASSING — with every assertion that fell outside the block discarded.
  That is exactly what `routing-not-found.edn` did (rf2-5mr6).

  WHY NOT WRAP THE TEXT AS `[<text>]` EITHER, which is what this fn did
  when the check first landed. Counting the elements of a SYNTHETIC vector
  is a guard the guarded text can walk straight out of: a fixture that
  closes the envelope itself with an early `]` yields a ONE-element vector,
  so the count check passes and everything after that `]` is discarded in
  silence — recreating the exact truncation class the check exists to
  remove. Measured on the merged code: the text `{:fixture/id :first}`,
  newline, `] {:fixture/id :silently-hidden}` returned `#:fixture{:id
  :first}` without throwing.

  SO READ THE ORIGINAL TEXT — no envelope, hence nothing to escape from —
  and PROVE EOF behind the first form with a second read against a
  sentinel. `one-form-guard-rejects-early-close-bracket` below pins both
  directions.

  WHY IT THROWS, and why the `try`/`catch` that used to wrap this is gone.
  The catch turned any load failure into a `{:fixture/load-error ...}` map,
  and `conformance-runner/run-corpus` classifies that map as `:skipped?
  true` while `machines-conformance-test` filters those fixtures out
  altogether. A caught parse error would therefore be exactly as silent as
  the defect it is meant to catch — the fixture would stop passing
  falsely and start vanishing quietly instead. Corpus malformation is a
  repository defect, not a per-fixture runtime condition, so it fails the
  run. The `catch` in the body below is NOT that catch and must not be
  read as its return: it RE-THROWS, and exists only so a reader error
  arrives naming the fixture it came out of.

  The corpus scanner (`scripts/check_conformance_fixture_edn.py`, rf2-x91a)
  is the complementary half: it sees fixtures whose capabilities are
  unclaimed, which this check never loads at all."
  [text fixture-name]
  (let [eof  (Object.)
        rdr  (java.io.PushbackReader. (java.io.StringReader. text))
        fail (fn [why data]
               (throw (ex-info (str "conformance fixture " fixture-name " " why
                                    " (rf2-98ni, rf2-5mr6)")
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
  ;; A handful of fixtures use `::name` (auto-resolved keyword) which pure
  ;; clojure.edn cannot read without a *reader-resolver*. Rewrite ONLY a
  ;; standalone auto-resolved keyword `::name` (one that begins a token) to
  ;; a stable namespace so the fixture loads (rf2-lu3f). The lookbehind keeps
  ;; the rewrite from corrupting a `::` INSIDE a value (e.g. a CEDN-1 token
  ;; string `"k::answer"`).
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
  ;; rf2-wxe9t — drop every corpus-wide error-emit listener so a recorder
  ;; installed for one fixture can't fire against the next fixture's drains.
  (rf.error-emit/clear-error-listeners!)
  ;; rf2-v0jwt — drop the per-frame epoch ring buffer (and the in-flight
  ;; capture buffer) between fixtures so `:epoch-records` assertions observe
  ;; THIS fixture's recorded epochs only.
  (when-let [f (rf.late-bind/get-fn :epoch/clear-history!)]
    (f))
  (when-let [f (rf.late-bind/get-fn :epoch/clear-epoch-listeners!)]
    (f))
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; The framework's provided recordable coeffect `:rf/time-ms` is registered
  ;; at `re-frame.cofx` ns-load; clear-all! wiped it. Re-seat it.
  (require 're-frame.cofx :reload)
  ;; Framework events / fx are registered at ns-load in routing.cljc /
  ;; ssr.cljc; clear-all! wiped them. Re-eval those registrations.
  ;; rf2-kuky.36 deleted a hand-registration of `:rf/route` that sat here: it
  ;; went through `re-frame.subs/reg-sub`, which makes an APP-DB sub, while
  ;; `:rf/route` is a RUNTIME sub over `[:rf.runtime/routing :current]`. The
  ;; reload below re-registers it correctly one line later and had been
  ;; overwriting the wrong registration all along, so the only thing the
  ;; hand-registration bought was a consumer for the `route-sub-fn` alias.
  (require 're-frame.routing :reload)
  ;; rf2-dbiv8 — re-seat the test-only `:rf.test/simulate-http-resolution`
  ;; fixture event after clear-all!.
  (require 're-frame.routing.test-support :reload)
  (require 're-frame.ssr :reload)
  ;; Spec 014 — re-register :rf.http/managed and friends after clear-all!.
  (require 're-frame.http.managed :reload)
  ;; rf2-cdmle — re-fire re-frame.http.test-support's load body so its
  ;; canned-stub fx registrations re-seat.
  (require 're-frame.http.test-support :reload)
  ;; Spec 005 — re-register :rf.machine/spawn / :rf.machine/destroy fx + the
  ;; :rf/machine sub after clear-all!.
  (require 're-frame.machines :reload)
  ;; Spec 016 §Resources (rf2-rul3ov) — re-register the resource events / fx /
  ;; subs and reset host-side caches so each fixture's first load mints
  ;; generation 1 deterministically.
  (require 're-frame.resources :reload)
  (require 're-frame.resources.test-support :reload)
  ((requiring-resolve 're-frame.resources.test-support/reset-resources!))
  ;; Reset id-allocators so nav-token / pending-nav / rank-reg / spawn ids are
  ;; stable across runs.
  ((requiring-resolve 're-frame.routing/reset-counters!))
  ;; rf2-oosjmh — the nav-token / pending-nav counters are host-side transient
  ;; state now, so the `frames` reset above no longer clears them.
  ((requiring-resolve 're-frame.routing/reset-nav-counters!))
  ((requiring-resolve 're-frame.machines/reset-timers!))
  ;; Spec 014 — drop the in-flight request registry between fixtures.
  ((requiring-resolve 're-frame.http.managed/clear-all-in-flight!))
  ;; Spec 014 §Middleware (rf2-yhfgf) — clear the per-frame interceptor chain
  ;; (a `defonce` atom that persists across `:reload`).
  ((requiring-resolve 're-frame.http.managed/clear-all-http-interceptors!)))

;; ---- host map --------------------------------------------------------------

(def ^:private host
  "The genuinely host-specific seams handed to the shared runner. Trace
  registry access uses `re-frame.trace` (JVM); the CLJS leaf uses
  `re-frame.trace.tooling` per the production-DCE split. The fixture-end
  cleanup clears ALL listeners (the framework SSR error-projection listener
  is re-registered by the next reset-runtime!'s `(require 're-frame.ssr
  :reload)`), matching the pre-consolidation JVM behaviour."
  {:reset-runtime!             reset-runtime!
   :register-trace-listener!   (fn [fixture-id listener]
                                 (rf.trace/register-listener! [fixture-id] listener))
   :unregister-trace-listener! (fn [_fixture-id]
                                 (rf.trace/clear-listeners!))})

;; ---- the test entrypoint --------------------------------------------------

(deftest run-conformance-corpus
  (rf.conformance-runner/run-corpus (all-fixtures) host "JVM"))

;; ---- rf2-98ni acceptance: the one-form guard cannot be escaped ------------
;;
;; The FIRST case is the discriminating one. Its trailing text opens with `]`,
;; which is what the previous `[<text>]` implementation could not survive: the
;; fixture closed the synthetic vector itself, `read-string` returned a
;; one-element vector, the count check passed, and the second map vanished. A
;; regression whose trailing text were an ordinary form would have passed
;; against that implementation too and pinned nothing.

(deftest one-form-guard-rejects-early-close-bracket
  (is (thrown? clojure.lang.ExceptionInfo
               (read-one-form "{:fixture/id :first}\n] {:fixture/id :hidden}"
                              "early-close.edn"))
      (str "a fixture that closes the reader's envelope itself with an early ] "
           "MUST fail to load — under the [<text>] implementation this returned "
           "the first form and discarded the second in silence (rf2-98ni)"))

  (is (thrown? clojure.lang.ExceptionInfo
               (read-one-form "{:fixture/id :first}\n{:fixture/id :second}"
                              "two-forms.edn"))
      "ordinary trailing text must still be refused (rf2-5mr6)")

  (is (thrown? clojure.lang.ExceptionInfo
               (read-one-form "\n;; only a comment\n" "empty.edn"))
      "a fixture holding no top-level form must fail rather than load as nil")

  ;; The other direction: a well-formed fixture, and one with the trailing
  ;; whitespace and comments real corpus files carry, must still load.
  (is (= {:fixture/id :ok}
         (read-one-form "{:fixture/id :ok}" "clean.edn"))
      "a single well-formed form must load unchanged")

  (is (= {:fixture/id :ok}
         (read-one-form "\n;; leading comment\n{:fixture/id :ok}\n;; trailing\n"
                        "commented.edn"))
      "comments and surrounding whitespace are not trailing FORMS"))

;; ---- rf2-xurchk acceptance self-tests -------------------------------------
;;
;; These pin the correctness fix directly on the JVM host (the CLJS leaf pins
;; the same on CLJS). Both prove the shared runner BITES rather than silently
;; ignoring an expectation.

;; A single-drain counter fixture (mirror of epoch-record-shape.edn) whose
;; `:epoch-records` expectation is DELIBERATELY WRONG. If the epoch matcher
;; were absent (the rf2-xurchk bug) this would pass; with it, it MUST fail.
(def ^:private epoch-mismatch-fixture
  {:fixture/id           :rf.test/epoch-records-deliberate-mismatch
   :fixture/spec-version "1.0"
   :fixture/capabilities #{:core/event-handler :core/trace}
   :fixture/handlers     {:event {:counter/inc [[:update [:count] [:fn :inc]]]}}
   :fixture/frame-config {}
   :fixture/dispatches   [[:counter/inc]]
   :fixture/expect
   {:epoch-records
    ;; The real settled drain records :outcome :ok; assert a wrong outcome so
    ;; a WORKING epoch matcher fails the fixture.
    [{:frame  :rf/default
      :record {:event-id :counter/inc
               :outcome  :rf.test/DELIBERATELY-WRONG}}]}})

(deftest epoch-records-checked-on-jvm
  (let [result (rf.conformance-runner/run-fixture epoch-mismatch-fixture host)]
    (is (not (:passed? result))
        "a deliberately-mismatched :epoch-records expectation MUST fail the runner on JVM")
    (is (seq (:epoch-failures result))
        "the failure MUST be attributed to the epoch-records matcher (not silently ignored)")))

(deftest unknown-expect-key-fails-loud
  ;; A runnable fixture whose :fixture/expect names a key the runner neither
  ;; checks nor delegates MUST be reported as unknown — not silently ignored.
  (is (seq (rf.conformance-runner/unknown-expect-keys
             {:fixture/expect {:rf.test/no-such-expectation 1}}))
      "an unrecognised :fixture/expect key must be flagged unknown")
  (is (empty? (rf.conformance-runner/unknown-expect-keys
                {:fixture/expect {:final-app-db {} :epoch-records []}}))
      "corpus-checked expectation keys must NOT be flagged unknown"))

;; ---- rf2-kqxe6.2 NEUTER PROBE for routing/door-parity ---------------------
;;
;; The door-parity fixture claims all three navigation doors lower to ONE
;; resolver. It used to assert doors 2 and 3 purely by the ABSENCE of a further
;; history push — and both `check-effects-routed` and `check-trace-emissions`
;; are ORDER-PRESERVING SUBSET matchers ("extras are tolerated"), so no
;; `:fixture/expect` key in this runner can grade an absence. The fixture
;; therefore passed with doors 2 and 3 DELETED, proving nothing it advertised.
;; The repair gives every door a distinct destination and thus a positive
;; footprint; this probe is what holds the repair in place.

(defn- door-parity-fixture []
  (or (some (fn [[n f]] (when (= n "routing-door-parity.edn") f)) (all-fixtures))
      (throw (ex-info "routing-door-parity.edn is missing from the corpus" {}))))

(defn- without-doors
  "The fixture with the 0-based `:fixture/dispatches` indices in `idxs` removed."
  [fixture idxs]
  (let [drop? (set idxs)]
    (update fixture :fixture/dispatches
            #(vec (keep-indexed (fn [i d] (when-not (drop? i) d)) %)))))

(deftest door-parity-fixture-bites
  (let [fixture (door-parity-fixture)
        passes? (fn [f] (:passed? (rf.conformance-runner/run-fixture f host)))]
    (is (passes? fixture)
        "the door-parity fixture must pass as shipped")
    (doseq [[door idx] [["named-address" 0] ["raw-URL" 1] ["URL-driven" 2]]]
      (is (not (passes? (without-doors fixture [idx])))
          (str "deleting the " door " door MUST red the door-parity fixture")))
    (is (not (passes? (without-doors fixture [1 2])))
        "the named-address door alone MUST NOT satisfy the door-parity fixture")
    ;; The `:fixture/calls` leg cannot be probed by DELETION — removing an
    ;; expectation can never fail a subset-matching runner. Perturbing one
    ;; proves the pure-resolver leg is graded rather than silently skipped.
    (is (not (passes? (assoc fixture :fixture/calls
                            [{:call     :route-url
                              :route-id :route/article
                              :params   {:slug "raw-url"}
                              :expect   "/articles/DELIBERATELY-WRONG"}])))
        "a wrong :fixture/calls expectation MUST red the door-parity fixture")))

;; ---- rf2-ska8zk NEGATIVE self-test for the :expect-graph guard ------------
;;
;; The broad derivation-graph fixture pins the live graph's {:mode :live
;; :frame :rf/default} shape via :expect-graph. This proves the GUARD bites:
;; a :derivation-graph call whose :expect-graph misreports :mode/:frame must
;; FAIL the runner, otherwise the fixture's live-mode assertion is a no-op.
(deftest derivation-graph-expect-graph-guard
  (reset-runtime!)
  (let [run (fn [call] (rf.conformance-runner/run-call call))]
    (is (:passed? (run {:call :derivation-graph :mode :live
                        :expect-graph {:mode :live :frame :rf/default}}))
        "the true live graph shape must pass")
    (is (not (:passed? (run {:call :derivation-graph :mode :live
                             :expect-graph {:mode :static}})))
        "a wrong live graph :mode must fail the runner")
    (is (not (:passed? (run {:call :derivation-graph :mode :live
                             :expect-graph {:mode :live :frame :rf/other}})))
        "a wrong live graph :frame must fail the runner")
    (is (not (:passed? (run {:call :derivation-graph :mode :static
                             :expect-graph {:mode :static :frame :rf/default}})))
        "asserting a :frame on the frame-agnostic static graph must fail")
    (is (:passed? (run {:call :derivation-graph :mode :static
                        :expect-graph {:mode :static}}))
        "the true static graph shape must pass")))

;; ---- rf2-7yth0 NEGATIVE self-test for the classification-op guard ---------
;;
;; `realise-classification-effects!` refuses a `:fixture/classification-effects`
;; op-map that does not carry EXACTLY ONE of the four commit-plane axes, and
;; the FixtureFile schema in `spec/Spec-Schemas.md` states the same contract.
;; Nothing in the corpus exercises the refusal: every live op is a valid
;; single-axis map, so deleting the guard leaves the corpus green. Without it
;; the runner's priority-ordered `cond` silently applies a multi-axis op's
;; FIRST arm only, and applies nothing at all for an empty or unknown-key op
;; -- a fixture author writing two axes gets one, with no error. This is the
;; durable negative; the CLJS leaf carries the mirror.

(defn- classification-op-fixture
  "A minimal runnable fixture whose only variable is its
  `:fixture/classification-effects` vector."
  [ops]
  {:fixture/id           :rf.test/classification-op-guard
   :fixture/spec-version "1.0"
   :fixture/capabilities #{:core/event-handler}
   :fixture/handlers     {:event {:dc/store [[:set [:secret] "s"]]}}
   :fixture/frame-config {}
   :fixture/classification-effects ops
   :fixture/dispatches   [[:dc/store]]
   :fixture/expect       {:final-app-db {:secret "s"}}})

(def ^:private refused-classification-ops
  "The three shapes the closed four-way schema refuses, plus the mixed case."
  [["an empty op-map"        [{}]]
   ["a multi-axis op-map"    [{:sensitive [[:secret]] :large [[:secret]]}]]
   ["an unknown-axis op-map" [{:rf.test/bogus [[:secret]]}]]
   ["a known axis beside an unknown key"
    [{:sensitive [[:secret]] :rf.test/bogus [[:secret]]}]]])

(deftest classification-op-map-guard
  (doseq [[label ops] refused-classification-ops]
    (let [result (rf.conformance-runner/run-fixture (classification-op-fixture ops) host)]
      (is (not (:passed? result))
          (str label " in :fixture/classification-effects MUST fail the fixture, not silently no-op"))
      (is (some? (re-find #"unrecognised :fixture/classification-effects op-map"
                          (str (:error result))))
          (str label " must be refused BY THE CLASSIFICATION-OP GUARD, not by some later check"))))
  ;; Control: the valid single-axis shape the whole live corpus uses still runs
  ;; the fixture to a pass, so the guard is not simply refusing everything.
  (let [result (rf.conformance-runner/run-fixture
                 (classification-op-fixture [{:sensitive [[:secret]]}]) host)]
    (is (:passed? result)
        (str "a valid single-axis op-map must still run the fixture to a pass; got "
             (pr-str (select-keys result [:error :final-db :expected-db]))))))
