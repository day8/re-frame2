(ns re-frame.epoch-silence-contract-test
  "rf2-4go8s — the epoch silencing SCHEMA and the state.cljc source authority
  agree with the shipped comparable-ID / opaque-generation / outside-lock design.

  Three teeth, no runtime change:

    * AC1 / AC3 (JVM) — a NON-KEYWORD comparable listener id round-trips through
      registration → the emitted `:cb-id` VERBATIM (raw-ID preservation) → the
      supported receiver decision's self-filter, AND the emitted tags validate
      against the CANONICAL `EpochCbSilencedOnFrameDestroyTags` schema EXTRACTED
      from `spec/Spec-Schemas.md`. Before this bead the schema narrowed `:cb-id`
      to keyword|string, so a valid non-keyword id the runtime accepts (and emits
      raw) FAILED the canonical schema — the drift this proves is closed.

    * AC6 — a focused SOURCE-CONTRACT tooth over the exact `epoch/state.cljc`
      docstrings + module comment that used to carry the stale authority. It
      catches a regression to EMIT-UNDER-LOCK language (a claim that a ledger lock
      spans the external emit, or that a replacement/drop blocks until publication)
      or to RESERVE-ONLY-EMITS language (describing the reserve-only primitive as
      emitting a signal though it emits nothing). It reads only these named
      surfaces — it is NOT a general comment linter.

  The runtime is UNCHANGED: reservation happens under both ledger locks and the
  generation-qualified emit runs after they are released (rf2-8b9twg). These teeth
  pin the doc/schema surfaces to that shipped protocol."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.state :as state]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- cb-generation
  "The live generation token under `cb-id`. Artefact-internal: rf2-uhouu retired
  the public generation query (the generation alone can only recompose the torn
  two-read receiver decision). A consumer asks `rf/epoch-silence-current?`
  instead; this test names the generation only to pin the EMITTED qualifier."
  [cb-id]
  (get-in (state/listeners-snapshot) [cb-id :generation]))

;; ---- canonical schema extraction (no drift: read the markdown) --------------

(def ^:private spec-schemas-candidates
  ;; `clojure -M:test` runs from implementation/epoch; keep a couple of fallbacks
  ;; so the extraction is CWD-robust (mirrors observation-schema-extract).
  ["../../spec/Spec-Schemas.md" "../spec/Spec-Schemas.md" "spec/Spec-Schemas.md"])

(defn- canonical-silencing-schema
  "Extract the `EpochCbSilencedOnFrameDestroyTags` `[:map …]` form from
  `spec/Spec-Schemas.md`. The `;;` comments in the def are dropped by the EDN
  reader, so the result is the pure canonical schema — validating the runtime's
  emitted tags against THIS proves the markdown schema (not a hand-copy) accepts
  the id domain the runtime emits."
  []
  (let [f    (or (some (fn [p] (let [f (io/file p)] (when (.exists f) f)))
                       spec-schemas-candidates)
                 (throw (ex-info "rf2-4go8s: cannot locate spec/Spec-Schemas.md"
                                 {:candidates spec-schemas-candidates})))
        text (slurp f)
        start (str/index-of text "(def EpochCbSilencedOnFrameDestroyTags")
        _    (when (nil? start)
               (throw (ex-info "rf2-4go8s: EpochCbSilencedOnFrameDestroyTags not found"
                               {:file (str f)})))
        form (edn/read-string (subs text start))
        schema (nth form 2 nil)]
    (when-not (and (vector? schema) (= :map (first schema)))
      (throw (ex-info "rf2-4go8s: schema is not a [:map …] form" {:read schema})))
    schema))

;; ---- AC1 / AC3 — a non-keyword comparable id round-trips + schema-validates -

(deftest non-keyword-comparable-cb-id-round-trips-and-schema-validates
  (testing "a non-keyword comparable listener id (a vector) registers, answers the
            generation query, is emitted VERBATIM as :cb-id, self-filters by
            generation, and validates against the canonical markdown schema"
    (let [cb-id   [:my-app/epoch-log 7]         ; a vector — NOT keyword|string
          schema  (canonical-silencing-schema)
          entries (into {} (map (fn [e] [(first e) (second e)])) (rest schema))]
      ;; The canonical schema must ACCEPT the non-keyword id (widened from the
      ;; stale keyword|string narrowing) and treat :observed-gen as opaque.
      (is (= :any (:cb-id entries))
          "the canonical :cb-id domain is :any (the open comparable-ID domain)")
      (is (= :any (:observed-gen entries))
          "the canonical :observed-gen is an opaque :any token, not :int")

      (is (nil? (cb-generation cb-id))
          "an unregistered non-keyword id has no generation")

      (rf/make-frame {:id :test/non-kw})
      (rf/reg-event :seed-nonkw (fn [{:keys [db]} _] {:db {:n 0}}))

      (let [recorded (atom [])]
        (rf/register-listener! :trace ::recorder-nonkw (fn [ev] (swap! recorded conj ev)))
        (rf/register-listener! :epoch cb-id (fn [_] nil))
        (is (some? (cb-generation cb-id))
            "the non-keyword id mints a generation once registered")

        ;; Observe the frame under the live generation, then destroy it.
        (rf/dispatch-sync [:seed-nonkw] {:frame :test/non-kw})
        (let [g-observed (cb-generation cb-id)]
          (rf/destroy-frame! :test/non-kw)
          (let [tags (->> @recorded
                          (filter #(= :rf.epoch.cb/silenced-on-frame-destroy
                                      (:operation %)))
                          first
                          :tags)]
            (is (some? tags) "a silence fired for the destroyed frame")

            ;; RAW-ID PRESERVATION — the emitted :cb-id is the vector VERBATIM.
            (is (= cb-id (:cb-id tags))
                "the emitted :cb-id is the raw non-keyword id, not narrowed/coerced")
            (is (vector? (:cb-id tags))
                "and it is genuinely a non-keyword comparable value")
            (is (= g-observed (:observed-gen tags))
                "the silence names the generation that observed the frame")

            ;; SCHEMA VALIDATION — the emitted tags satisfy the CANONICAL schema
            ;; from the markdown (would FAIL the old keyword|string :cb-id).
            (is (m/validate schema tags)
                (str "emitted tags must validate against the canonical "
                     "EpochCbSilencedOnFrameDestroyTags schema: "
                     (pr-str (m/explain schema tags))))

            ;; GENERATION-QUALIFIED SELF-FILTER on the non-keyword id, through
            ;; the ONE supported receiver decision (rf2-uhouu).
            (is (true? (rf/epoch-silence-current? tags))
                "live signal names the current registration — APPLY")
            (rf/register-listener! :epoch cb-id (fn [_] nil)) ; supersede G→H
            (is (false? (rf/epoch-silence-current? tags))
                "a superseded signal is no longer current — DISCARD")))

        (rf/unregister-listener! :trace ::recorder-nonkw)
        (rf/unregister-listener! :epoch cb-id)))))

;; ---- AC6 — source-contract tooth over the state.cljc silencing authority ----

(defn- squish
  "Collapse runs of whitespace to single spaces so a multi-line docstring/comment
  is searchable as one line."
  [s]
  (some-> s (str/replace #"\s+" " ") str/trim))

(defn- var-doc [v] (squish (:doc (meta v))))

(defn- state-source
  "The `re-frame.epoch.state` source text, read off the classpath (its `src` dir
  is a classpath root), squished — the module comment is a top-level `;;` block,
  not reachable through var metadata."
  []
  (squish (slurp (io/resource "re_frame/epoch/state.cljc"))))

(deftest source-authority-states-the-outside-lock-generation-qualified-protocol
  (testing "AC6 — the state.cljc silencing docstrings + module comment describe the
            shipped protocol (reserve/recheck under both locks, RELEASE, then emit
            a generation-qualified signal OUTSIDE the locks) and carry NO stale
            emit-under-lock or reserve-only-emits authority. A regression to that
            language flips these assertions RED."
    (let [src        (state-source)
          put-doc    (var-doc #'state/put-listener!)
          drop-doc   (var-doc #'state/drop-listener!)
          rec-doc    (var-doc #'state/record-observation!)
          publish    (var-doc #'state/claim-and-publish-delayed-silence!)]

      ;; --- NO emit-under-lock authority (a lock spanning the external emit) ---
      (is (not (str/includes? src "across its eligibility recheck AND the external emit"))
          "module comment must NOT claim a lock spans the external emit")
      (is (not (str/includes? put-doc "blocks on the registry lock until publication completes"))
          "put-listener! must NOT claim a replacement blocks until publication")
      (is (not (str/includes? drop-doc "blocks until publication completes"))
          "drop-listener! must NOT claim a drop blocks until publication")
      (is (not (str/includes? rec-doc "between its reservation and emit"))
          "record-observation! must NOT exclude a re-arm from the reservation→emit window")

      ;; --- NO reserve-only API at all (rf2-6r9j.78) ---
      ;; The reserve-only pair was retired: it left a claim→emit window it could
      ;; not qualify, so its own docstring conceded it was for staged tests and
      ;; for callers that provably cannot race a registry mutation. Only the
      ;; integrated generation-qualified operation ships now, and re-introducing
      ;; a reserve-only seam here flips this RED. (Neither name is a substring of
      ;; `claim-and-publish-delayed-silence!`, so the correct helper is unaffected.)
      (is (not (str/includes? src "claim-delayed-silence!"))
          "the retired reserve-only claim helper must not return")
      (is (not (str/includes? src "rollback-delayed-silence!"))
          "the retired reserve-only rollback helper must not return")

      ;; --- the CORRECT protocol is stated positively ---
      (is (str/includes? src "runs OUTSIDE both locks")
          "module comment states the emit runs OUTSIDE both locks")
      (is (str/includes? publish "OUTSIDE")
          "claim-and-publish-delayed-silence! states the emit runs outside the locks")
      (is (str/includes? put-doc "OUTSIDE the ledger locks")
          "put-listener! states the emit runs outside the ledger locks"))))
