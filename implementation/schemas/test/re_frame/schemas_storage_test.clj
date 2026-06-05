(ns re-frame.schemas-storage-test
  "JVM tests for the slice-local storage / introspection surface
  (rf2-yv62u).

  The audit (rf2-yv62u) flagged a coverage gap: while the validation
  hot path is well-pinned, the per-frame storage / introspection
  surface had no direct slice-local tests for `app-schema-meta-at`,
  `frame-schema-entries`, or the `coerce-opts` bad-arg branch.
  Drift in these introspection helpers would silently break pair-
  tools, 10x panels, and the late-bind seam between schemas and
  elision / epoch.

  This file pins the storage / introspection contract."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.storage :as storage]
            [re-frame.schemas.test-fixture :as tf]
            [re-frame.schemas.validate :as validate]))

(use-fixtures :each tf/reset-runtime)

;; ---- app-schema-meta-at --------------------------------------------------

(deftest meta-at-returns-full-registration-map
  (testing "app-schema-meta-at returns the registration metadata map —
            including :schema, :path, :frame, and source-coords —
            distinct from app-schema-at which returns just :schema"
    (rf/reg-app-schema [:user] [:map [:id :int]])
    (let [m (schemas/app-schema-meta-at [:user])]
      (is (map? m))
      (is (= [:map [:id :int]] (:schema m))
          ":schema slot carries the registered form")
      (is (= [:user] (:path m))
          ":path slot mirrors the registration path")
      (is (some? (:frame m))
          ":frame slot is stamped — :rf/default outside (with-frame ...)"))))

(deftest meta-at-is-nil-for-unregistered-path
  (testing "an unregistered path yields nil — distinguishable from a
            registration that happened to carry a nil :schema"
    (is (nil? (schemas/app-schema-meta-at [:never-registered])))))

(deftest meta-at-keyword-sugar-resolves-frame
  (testing "the keyword-sugar arity targets the named frame —
            parallel to app-schema-at"
    (rf/reg-app-schema [:user] [:map] {:frame :tenant/a})
    (rf/reg-app-schema [:user] [:vector] {:frame :tenant/b})
    (is (= [:map] (:schema (schemas/app-schema-meta-at [:user] :tenant/a)))
        "frame :tenant/a's registration is isolated")
    (is (= [:vector] (:schema (schemas/app-schema-meta-at [:user] :tenant/b)))
        "frame :tenant/b's registration is isolated")
    (is (nil? (schemas/app-schema-meta-at [:user] :tenant/never))
        "unknown frame → nil")))

(deftest meta-at-opts-map-arity
  (testing "the opts-map arity {:frame ...} matches the keyword sugar"
    (rf/reg-app-schema [:user] [:map] {:frame :tenant/a})
    (is (= (schemas/app-schema-meta-at [:user] :tenant/a)
           (schemas/app-schema-meta-at [:user] {:frame :tenant/a}))
        "opts-map and keyword-sugar produce identical results")))

(deftest meta-at-reflects-hot-reload-replacement
  (testing "re-registering the same path with a new schema replaces the
            meta map — hot-reload semantics on the introspection
            surface"
    (rf/reg-app-schema [:user] [:map [:id :int]])
    (is (= [:map [:id :int]] (:schema (schemas/app-schema-meta-at [:user]))))
    (rf/reg-app-schema [:user] [:map [:id :uuid]])
    (is (= [:map [:id :uuid]] (:schema (schemas/app-schema-meta-at [:user])))
        "second registration replaces the first in the meta map")))

;; ---- frame-schema-entries ------------------------------------------------

(deftest frame-schema-entries-returns-empty-for-unknown-frame
  (testing "an unknown frame produces an empty map — the cross-artefact
            seam never blows up on consumers that ask before any
            registration has happened"
    (is (= {} (schemas/frame-schema-entries :rf/default)))
    (is (= {} (schemas/frame-schema-entries :never/created)))))

(deftest frame-schema-entries-returns-path-to-meta-map
  (testing "frame-schema-entries returns the full {path → schema-meta}
            map for a frame — the shape the validation hot path walks"
    (rf/reg-app-schema [:user] [:map [:id :int]])
    (rf/reg-app-schema [:auth] [:map [:token :string]])
    (let [entries (schemas/frame-schema-entries :rf/default)]
      (is (= #{[:user] [:auth]} (set (keys entries)))
          "both registered paths present")
      (is (= [:map [:id :int]] (-> entries (get [:user]) :schema)))
      (is (= [:map [:token :string]] (-> entries (get [:auth]) :schema))))))

(deftest frame-schema-entries-per-frame-isolation
  (testing "entries from one frame do not bleed into another — the
            cross-artefact seam respects frame scoping"
    (rf/reg-app-schema [:user] [:map] {:frame :tenant/a})
    (rf/reg-app-schema [:user] [:vector] {:frame :tenant/b})
    (is (= [:map]    (-> (schemas/frame-schema-entries :tenant/a)
                         (get [:user]) :schema)))
    (is (= [:vector] (-> (schemas/frame-schema-entries :tenant/b)
                         (get [:user]) :schema)))
    (is (not (contains? (schemas/frame-schema-entries :tenant/a)
                        [:other-path-in-tenant-b]))
        "tenant/a's view does not include tenant/b's entries")))

;; ---- coerce-opts ---------------------------------------------------------

(deftest coerce-opts-accepts-keyword
  (testing "keyword sugar coerces to {:frame keyword}"
    (is (= {:frame :tenant/a} (storage/coerce-opts :tenant/a)))))

(deftest coerce-opts-accepts-opts-map
  (testing "opts-map passes through verbatim"
    (is (= {:frame :tenant/a :other :thing}
           (storage/coerce-opts {:frame :tenant/a :other :thing})))
    (is (= {} (storage/coerce-opts {})))))

(deftest coerce-opts-throws-on-bad-arg
  (testing "Per rf2-yv62u — a non-keyword, non-map argument throws
            :rf.error/bad-app-schemas-arg. nil, numbers, strings,
            vectors all fail."
    (doseq [bad-arg [nil 42 "frame-a" [:a :b] :well/-actually-keyword-is-ok]]
      (cond
        ;; keywords pass — exclude from the throw-loop sentinel above.
        (keyword? bad-arg)
        (is (map? (storage/coerce-opts bad-arg))
            (str "keyword " bad-arg " is accepted"))

        :else
        (let [thrown (try (storage/coerce-opts bad-arg)
                          (catch clojure.lang.ExceptionInfo e e)
                          (catch Exception e e))]
          (is (instance? clojure.lang.ExceptionInfo thrown)
              (str "bad-arg " (pr-str bad-arg) " throws ex-info"))
          (when (instance? clojure.lang.ExceptionInfo thrown)
            (is (= ":rf.error/bad-app-schemas-arg" (.getMessage ^Exception thrown))
                "ex-info message names the error category")
            (let [data (ex-data thrown)]
              (is (= bad-arg (:received data))
                  ":received slot carries the bad input verbatim"))))))))

;; ---- reg-app-schema opts contract symmetry (rf2-52dfy) -------------------
;;
;; Pre-rf2-52dfy `reg-app-schema` plucked `(:frame opts)` directly off
;; whatever was passed as `opts`. A bare keyword (`(reg-app-schema path
;; schema :tenant/a)`) returned nil for `(:frame …)` and SILENTLY fell
;; back to the DEFAULT frame — yet every read entry point (`app-schema-at`
;; / `app-schema-meta-at` / `app-schemas`) routed its opts through
;; `coerce-opts`, which treats a bare keyword as the `{:frame kw}` sugar.
;; Write and read disagreed on the same shape: read targeted `:tenant/a`,
;; write targeted `:rf/default`. rf2-52dfy routes write through the SAME
;; `coerce-opts` contract so the two agree, and a genuinely bad shape
;; (string / number / vector) fails loud instead of silently mis-routing.

(deftest reg-app-schema-keyword-opts-matches-read-frame
  (testing "rf2-52dfy — a bare-keyword opts registers against THAT frame
            (the read API's `{:frame kw}` sugar), NOT silently against
            the DEFAULT frame. Write and read agree."
    (rf/reg-app-schema [:user] [:map [:id :int]] :tenant/a)
    ;; Read the same way — both target :tenant/a.
    (is (= [:map [:id :int]] (rf/app-schema-at [:user] :tenant/a))
        "schema landed in :tenant/a, matching the read sugar")
    (is (nil? (rf/app-schema-at [:user]))
        "the DEFAULT frame is untouched — the silent-DEFAULT footgun is gone")
    (is (nil? (rf/app-schema-at [:user] :rf/default))
        "explicit DEFAULT-frame read also empty")))

(deftest reg-app-schema-bad-opts-shape-fails-loud
  (testing "rf2-52dfy — a non-keyword, non-map opts shape throws
            :rf.error/bad-app-schemas-arg, matching coerce-opts (the read
            surface's contract). No silent mis-registration."
    (doseq [bad-arg ["tenant-a" 42 [:tenant :a]]]
      (let [thrown (try (rf/reg-app-schema [:user] [:map] bad-arg)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown)
            (str "bad opts " (pr-str bad-arg) " throws ex-info"))
        (when (instance? clojure.lang.ExceptionInfo thrown)
          (is (= ":rf.error/bad-app-schemas-arg" (.getMessage ^Exception thrown))
              "ex-info names the same error category the read surface uses"))))))

(deftest reg-app-schema-valid-opts-map-registers-fine
  (testing "rf2-52dfy — a valid {:frame ...} opts map still registers
            against the named frame; the coercion is transparent for the
            common shape."
    (rf/reg-app-schema [:user] [:map] {:frame :tenant/b})
    (is (= [:map] (rf/app-schema-at [:user] :tenant/b)))
    ;; And the zero-opts arity still defaults to the current frame.
    (rf/reg-app-schema [:auth] [:string])
    (is (= [:string] (rf/app-schema-at [:auth]))
        "two-arg arity registers against the current (default) frame")))

(deftest reg-app-schemas-keyword-opts-matches-read-frame
  (testing "rf2-52dfy — the bulk form coerces opts identically: a bare
            keyword registers every entry against THAT frame, not the
            DEFAULT frame; a bad shape fails loud."
    (rf/reg-app-schemas {[:user] [:map] [:auth] [:string]} :tenant/c)
    (is (= [:map]    (rf/app-schema-at [:user] :tenant/c)))
    (is (= [:string] (rf/app-schema-at [:auth] :tenant/c)))
    (is (nil? (rf/app-schema-at [:user]))
        "DEFAULT frame untouched for the bulk form too")
    (let [thrown (try (rf/reg-app-schemas {[:user] [:map]} "tenant-c")
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      (is (= ":rf.error/bad-app-schemas-arg" (.getMessage ^Exception thrown))))))

;; ---- path-shape validation (rf2-sk0ql) -----------------------------------
;;
;; A `reg-app-schema` `path` is a `get-in`/`assoc-in`-shaped path: a
;; sequential collection of keys, or `[]` for the whole-app-db root
;; (Spec 010 §`app-db` schemas — path-based / §A schema for the whole
;; `app-db` / §Digest "path is a vector of keywords (or the empty vector
;; for the root)").
;;
;; Before rf2-sk0ql a non-sequential scalar path (a bare keyword `:n`,
;; string, number, nil) registered successfully but made
;; `validate-app-schema!`'s `(get-in db path)` throw
;; IllegalArgumentException ("Don't know how to create ISeq from: …").
;; The live router's `run-post-commit-validation!` wraps that call in a
;; defensive try/catch and treats the throw as a validation PASS — so an
;; invalid `:db` commit was installed permanently with no
;; `:rf.error/schema-validation-failure` and no rollback (a correctness-
;; and privacy-relevant bypass — the redaction-bearing failure traces
;; never fired). Worse, the poisoned entry persisted and made EVERY
;; subsequent commit's validation throw, silently disabling post-commit
;; validation for the whole frame.
;;
;; Fix: fail loud at registration time with `:rf.error/bad-app-schema-path`
;; BEFORE the store mutation, so the malformed shape can never land.

(deftest reg-app-schema-rejects-non-sequential-path-shapes
  (testing "rf2-sk0ql — a non-sequential scalar `path` (bare keyword,
            string, number, nil) throws :rf.error/bad-app-schema-path and
            does NOT mutate schemas-by-frame"
    (doseq [bad-path [:n "user" 42 nil {:not :a-path} #{:n}]]
      (let [before (schemas/snapshot-schemas-by-frame)
            thrown (try (rf/reg-app-schema bad-path :int)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown)
            (str "bad path " (pr-str bad-path) " throws ex-info"))
        (when (instance? clojure.lang.ExceptionInfo thrown)
          (is (= ":rf.error/bad-app-schema-path" (.getMessage ^Exception thrown))
              "ex-info message names the path-shape error category")
          (let [data (ex-data thrown)]
            (is (= bad-path (:received data))
                ":received slot carries the bad path verbatim")
            (is (= :rf.error/bad-app-schema-path (:rf.error/id data))
                ":rf.error/id slot carries the framework error id")))
        (is (= before (schemas/snapshot-schemas-by-frame))
            (str "store is unchanged after rejecting " (pr-str bad-path)
                 " — the malformed entry never lands"))))))

(deftest reg-app-schema-accepts-vector-and-root-and-seq-paths
  (testing "rf2-sk0ql — valid sequential paths register fine: a vector
            key-path, the root `[]`, and a non-vector seq path"
    (rf/reg-app-schema [:n] :int)
    (is (= :int (rf/app-schema-at [:n])) "vector key-path registers")
    (rf/reg-app-schema [] [:map [:n :int]])
    (is (= [:map [:n :int]] (rf/app-schema-at [])) "root [] registers")
    ;; A non-vector seq is still a valid get-in path shape.
    (rf/reg-app-schema (list :a :b) :string)
    (is (= :string (rf/app-schema-at (list :a :b)))
        "non-vector seq path registers (get-in accepts any sequential ks)")))

(deftest reg-app-schemas-rejects-batch-with-bad-path-atomically
  (testing "rf2-sk0ql — a bulk batch containing a non-sequential path key
            is rejected ATOMICALLY: the whole call throws and NO entry
            lands (not even the well-formed siblings iterated first)"
    (let [before (schemas/snapshot-schemas-by-frame)
          thrown (try (rf/reg-app-schemas {[:good] :int
                                           :bad-key :string
                                           [:also-good] :boolean})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown)
          "a batch with a bad path key throws")
      (when (instance? clojure.lang.ExceptionInfo thrown)
        (is (= ":rf.error/bad-app-schema-path" (.getMessage ^Exception thrown))
            "names the path-shape error category"))
      (is (= before (schemas/snapshot-schemas-by-frame))
          "NO entry from the batch landed — all-or-nothing")
      (is (nil? (rf/app-schema-at [:good]))
          "the well-formed sibling did not half-register"))))

(deftest reg-app-schemas-accepts-all-valid-paths
  (testing "rf2-sk0ql — a batch of valid sequential paths (including the
            root `[]`) registers every entry"
    (rf/reg-app-schemas {[:good]      :int
                         []           [:map]
                         [:cart :qty] :int})
    (is (= :int    (rf/app-schema-at [:good])))
    (is (= [:map]  (rf/app-schema-at [])))
    (is (= :int    (rf/app-schema-at [:cart :qty])))))

;; ---- end-to-end bypass invariant (rf2-sk0ql) -----------------------------
;;
;; The load-bearing regression: BEFORE the fix, registering `:n` then
;; calling validate-app-schema! against a non-conforming app-db threw
;; IllegalArgumentException (which the router swallowed as a silent pass).
;; AFTER the fix, the registration is rejected up front, so the toxic
;; entry never reaches the validation hot path — and a VALID `[:n]`
;; registration validates and signals a mismatch (false → router rolls
;; back) exactly as the contract requires.

(deftest validate-app-schema-no-longer-poisoned-by-bad-path
  (testing "rf2-sk0ql — the previously-bypassing bare-keyword path can no
            longer poison validate-app-schema!. Rejected at registration;
            never reaches the hot path. validate-app-schema! returns true
            (clean) rather than THROWING the IllegalArgumentException the
            router silently treated as a pass."
    ;; The bypass input: a bare-keyword path. Registration now throws.
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf.error/bad-app-schema-path"
                          (rf/reg-app-schema :n :int)))
    ;; Nothing landed, so validate-app-schema! is a clean pass — it does
    ;; NOT throw the ISeq IllegalArgumentException any more.
    (is (true? (validate/validate-app-schema! {:n "bad"} :test))
        "no registered schema → clean pass, no throw to be swallowed")))

(deftest valid-path-validates-and-signals-mismatch
  (testing "rf2-sk0ql — the VALID counterpart of the bypass case: the
            vector path [:n] registers, and validate-app-schema! returns
            false on a non-conforming value (the signal the router uses to
            roll the commit back) — proving the validation path the bug
            had silently disabled is live."
    (rf/reg-app-schema [:n] :int)
    (is (false? (validate/validate-app-schema! {:n "bad"} :test))
        "a non-conforming value at the valid path fails validation")
    (is (true? (validate/validate-app-schema! {:n 0} :test))
        "a conforming value at the valid path passes")))

;; ---- malformed-schema fail-closed invariant (rf2-ss06u.3) ----------------
;;
;; Same fail-OPEN class as the sk0ql PATH bypass, but via a malformed SCHEMA
;; value. Malli validates schema FORMS lazily (at validate-time), so a
;; childless `[:vector]` / unknown op registers fine and then makes the
;; validator THROW on the first post-commit validation. Before the fix the
;; throw aborted the whole validate-app-schema! loop and the router's
;; `(catch ... true)` swallowed it as a validation PASS — installing an
;; unvalidated commit with no trace and no rollback, AND disabling validation
;; (incl. the privacy redaction traces) frame-wide for the bad schema's
;; lifetime. The fix isolates the throw per-entry: a distinct
;; :rf.error/malformed-schema trace fires, validate-app-schema! returns
;; FALSE (fail-closed → router rolls back — does NOT install blind), and the
;; frame's sibling schemas are still validated.

(defn- malformed-trace
  "Validate `db` against the named frame, capturing the schema-validation /
  malformed-schema traces. Returns {:result <bool> :traces [...]}. Does NOT
  swallow throws — a regression that re-introduces the throw fails the
  `:result` assertion loudly."
  [db failing-id frame]
  (let [traces (atom [])
        kw     (keyword "ss06u.3" (name (gensym "l")))]
    (rf/register-listener! kw (fn [ev] (swap! traces conj ev)))
    (let [result (validate/validate-app-schema! db failing-id frame)]
      (rf/unregister-listener! kw)
      {:result result :traces @traces})))

(deftest malformed-schema-no-silent-pass-childless-vector
  (testing "rf2-ss06u.3 — a childless [:vector] registered schema does NOT
            yield a silent commit-pass: validate-app-schema! returns false
            (fail-closed → rollback) without THROWING, and a distinct
            :rf.error/malformed-schema trace fires."
    (rf/reg-app-schema [:x] [:vector] {:frame :ss06u.3/a})
    (let [{:keys [result traces]} (malformed-trace {:x [1 2 3]} :bad/ev :ss06u.3/a)
          mal (filter #(= :rf.error/malformed-schema (:operation %)) traces)]
      (is (false? result)
          "fail-closed: false (rollback), NOT the swallowed silent true")
      (is (= 1 (count mal)) "exactly one malformed-schema trace fired")
      (let [m (first mal)]
        (is (= :app-db (-> m :tags :where)))
        (is (= [:x] (-> m :tags :path)) ":path is the structural registration root")
        (is (= [:vector] (-> m :tags :schema)) ":schema carries the malformed form to fix")
        (is (string? (-> m :tags :reason)))))))

(deftest malformed-schema-no-silent-pass-unknown-op
  (testing "rf2-ss06u.3 — an unknown-op registered schema also fails closed
            with a distinct trace, no throw."
    (rf/reg-app-schema [:y] [:not-a-real-op :int] {:frame :ss06u.3/b})
    (let [{:keys [result traces]} (malformed-trace {:y 1} :bad/ev :ss06u.3/b)
          mal (filter #(= :rf.error/malformed-schema (:operation %)) traces)]
      (is (false? result) "fail-closed: false, no throw")
      (is (= 1 (count mal)) "one malformed-schema trace fired"))))

(deftest malformed-schema-does-not-poison-sibling-validation
  (testing "rf2-ss06u.3 — a malformed schema at one path does NOT silently
            disable validation for OTHER paths in the same frame. Register a
            good schema + a malformed schema; commit a value that violates
            the GOOD schema; the good schema's rollback/trace STILL fires."
    (rf/reg-app-schema [:good]   [:int]    {:frame :ss06u.3/c})
    (rf/reg-app-schema [:broken] [:vector] {:frame :ss06u.3/c}) ; childless — malformed
    (let [{:keys [result traces]} (malformed-trace
                                    {:good "not-an-int" :broken [1]}
                                    :bad/ev :ss06u.3/c)
          good-fail (filter #(and (= :rf.error/schema-validation-failure (:operation %))
                                  (= [:good] (-> % :tags :path)))
                            traces)
          mal       (filter #(= :rf.error/malformed-schema (:operation %)) traces)]
      (is (false? result) "fail-closed: false (rollback)")
      (is (= 1 (count good-fail))
          "the GOOD schema's validation-failure STILL fires — not poisoned by the malformed sibling")
      (is (= 1 (count mal)) "the malformed schema surfaces its own distinct trace"))))

;; ---- snapshot / restore / clear (test-support seam) ----------------------

(deftest snapshot-and-restore-roundtrip
  (testing "snapshot captures the current state; restore replays it"
    (rf/reg-app-schema [:user] [:map [:id :int]])
    (rf/reg-app-schema [:auth] [:string])
    (let [snap (schemas/snapshot-schemas-by-frame)]
      ;; Mutate.
      (rf/reg-app-schema [:user] [:vector])
      (is (= [:vector] (rf/app-schema-at [:user]))
          "mutation visible before restore")
      ;; Restore.
      (schemas/restore-schemas-by-frame! snap)
      (is (= [:map [:id :int]] (rf/app-schema-at [:user]))
          "restore replays the captured state")
      (is (= [:string] (rf/app-schema-at [:auth]))
          "sibling entries restored too"))))

(deftest clear-removes-all-registrations
  (testing "clear-schemas-by-frame! drops every frame's registrations"
    (rf/reg-app-schema [:user] [:map])
    (rf/reg-app-schema [:user] [:vector] {:frame :tenant/a})
    (schemas/clear-schemas-by-frame!)
    (is (nil? (rf/app-schema-at [:user]))
        "default-frame registration cleared")
    (is (nil? (rf/app-schema-at [:user] :tenant/a))
        "tenant-frame registration cleared")
    (is (= {} (schemas/frame-schema-entries :rf/default)))
    (is (= {} (schemas/frame-schema-entries :tenant/a)))))

;; ---- on-frame-destroyed! (Spec 002 §Destroy / rf2-rbbmt) -----------------
;;
;; storage.cljc:361 drops a destroyed frame's schemas so a subsequent
;; reg-frame of the same id starts with a clean slate (prevents spurious
;; rollbacks against orphan paths — rf2-wkxng / rf2-6m0se). Previously
;; covered ONLY indirectly via the conformance runner's destroy cycle;
;; these are the direct slice-local pins for the dissoc + idempotent
;; no-op the docstring promises.

(deftest on-frame-destroyed-dissocs-the-frame
  (testing "rf2-rbbmt — on-frame-destroyed! drops every schema registered
            against the destroyed frame; sibling frames are untouched"
    (rf/reg-app-schema [:user] [:map [:id :int]] {:frame :tenant/doomed})
    (rf/reg-app-schema [:auth] [:string]         {:frame :tenant/doomed})
    (rf/reg-app-schema [:user] [:map]            {:frame :tenant/survivor})
    (is (= 2 (count (schemas/frame-schema-entries :tenant/doomed)))
        "doomed frame has both registrations before destroy")
    (schemas/on-frame-destroyed! :tenant/doomed)
    (is (= {} (schemas/frame-schema-entries :tenant/doomed))
        "destroyed frame's entries are gone")
    (is (nil? (rf/app-schema-at [:user] :tenant/doomed))
        "looking up a destroyed frame's schema yields nil")
    (is (= [:map] (rf/app-schema-at [:user] :tenant/survivor))
        "sibling frame's registration survives — destroy is frame-scoped")))

(deftest on-frame-destroyed-is-idempotent-no-op-for-missing-frame
  (testing "rf2-rbbmt — destroying a frame with no registrations (or one
            already destroyed) is a no-op dissoc — never throws, never
            disturbs other frames"
    (rf/reg-app-schema [:user] [:map] {:frame :tenant/keep})
    ;; Never-registered frame — the swap! dissoc returns the (unchanged)
    ;; registry value and never throws. The destroyed frame is simply
    ;; absent from the result.
    (let [after (schemas/on-frame-destroyed! :tenant/never)]
      (is (map? after)
          "swap! dissoc returns the registry value; no throw")
      (is (not (contains? after :tenant/never))
          "the never-registered frame is absent from the registry"))
    ;; Double-destroy is safe.
    (schemas/on-frame-destroyed! :tenant/keep)
    (schemas/on-frame-destroyed! :tenant/keep)
    (is (= {} (schemas/frame-schema-entries :tenant/keep))
        "second destroy of an already-empty frame is a clean no-op")
    (is (= {} (schemas/frame-schema-entries :tenant/never)))))

(deftest on-frame-destroyed-allows-clean-re-registration
  (testing "rf2-rbbmt — after destroy, re-registering the same frame-id
            starts from a clean slate (the rf2-wkxng rationale: no orphan
            schemas re-fire against the re-created frame)"
    (rf/reg-app-schema [:user]   [:map [:id :int]] {:frame :tenant/reuse})
    (rf/reg-app-schema [:orphan] [:string]         {:frame :tenant/reuse})
    (schemas/on-frame-destroyed! :tenant/reuse)
    ;; Re-create with only ONE of the prior schemas.
    (rf/reg-app-schema [:user] [:vector] {:frame :tenant/reuse})
    (let [entries (schemas/frame-schema-entries :tenant/reuse)]
      (is (= #{[:user]} (set (keys entries)))
          "only the freshly-registered path is present — :orphan did not
           survive the destroy")
      (is (= [:vector] (-> entries (get [:user]) :schema))
          "the re-registration's schema, not the pre-destroy one"))))

;; ---- registration roundtrip via reg-app-schemas --------------------------

(deftest bulk-registration-visible-from-meta-at
  (testing "reg-app-schemas — every entry is visible through
            app-schema-meta-at with the same source-coord stamping
            singular reg-app-schema would produce"
    (rf/reg-app-schemas {[:user] [:map [:id :int]]
                         [:auth] [:string]})
    (let [m1 (schemas/app-schema-meta-at [:user])
          m2 (schemas/app-schema-meta-at [:auth])]
      (is (= [:map [:id :int]] (:schema m1)))
      (is (= [:string] (:schema m2)))
      (is (= [:user] (:path m1)))
      (is (= [:auth] (:path m2))))))
