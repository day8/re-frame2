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

(deftest coerce-opts-accepts-nil-as-empty-opts
  (testing "rf2-iszpyg — nil coerces to {} (the empty-opts shape), identical
            to the no-arg arity: it means 'no override, resolve the frame
            from scope'. A nil OPTS has no analogue to the nil-PATH hazard,
            so accepting it removes a footgun for a trusted in-process caller."
    (is (= {} (storage/coerce-opts nil)))))

(deftest coerce-opts-throws-on-bad-arg
  (testing "Per rf2-yv62u — a non-keyword, non-map argument throws
            :rf.error/bad-app-schemas-arg. numbers, strings, vectors all
            fail. (rf2-iszpyg — nil is now accepted as {}; see
            coerce-opts-accepts-nil-as-empty-opts.)"
    (doseq [bad-arg [42 "frame-a" [:a :b] :well/-actually-keyword-is-ok]]
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
            ;; rf2-vvixub — the human message leads with the :reason sentence
            ;; and trails the [:rf.error/<id>] token; branch on the canonical
            ;; :rf.error/id, never on the (non-normative) message bytes.
            (let [data (ex-data thrown)]
              (is (= :rf.error/bad-app-schemas-arg (:rf.error/id data))
                  "ex-data carries the canonical :rf.error/id discriminator")
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
    (is (= [:map [:id :int]] (schemas/app-schema-at [:user] :tenant/a))
        "schema landed in :tenant/a, matching the read sugar")
    (is (nil? (schemas/app-schema-at [:user]))
        "the DEFAULT frame is untouched — the silent-DEFAULT footgun is gone")
    (is (nil? (schemas/app-schema-at [:user] :rf/default))
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
          ;; rf2-vvixub — branch on the canonical :rf.error/id, not the message.
          (is (= :rf.error/bad-app-schemas-arg (:rf.error/id (ex-data thrown)))
              "ex-data names the same error category the read surface uses"))))))

(deftest reg-app-schema-valid-opts-map-registers-fine
  (testing "rf2-52dfy — a valid {:frame ...} opts map still registers
            against the named frame; the coercion is transparent for the
            common shape."
    (rf/reg-app-schema [:user] [:map] {:frame :tenant/b})
    (is (= [:map] (schemas/app-schema-at [:user] :tenant/b)))
    ;; And the zero-opts arity still defaults to the current frame.
    (rf/reg-app-schema [:auth] [:string])
    (is (= [:string] (schemas/app-schema-at [:auth]))
        "two-arg arity registers against the current (default) frame")))

(deftest reg-app-schemas-keyword-opts-matches-read-frame
  (testing "rf2-52dfy — the bulk form coerces opts identically: a bare
            keyword registers every entry against THAT frame, not the
            DEFAULT frame; a bad shape fails loud."
    (rf/reg-app-schemas {[:user] [:map] [:auth] [:string]} :tenant/c)
    (is (= [:map]    (schemas/app-schema-at [:user] :tenant/c)))
    (is (= [:string] (schemas/app-schema-at [:auth] :tenant/c)))
    (is (nil? (schemas/app-schema-at [:user]))
        "DEFAULT frame untouched for the bulk form too")
    (let [thrown (try (rf/reg-app-schemas {[:user] [:map]} "tenant-c")
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown))
      ;; rf2-vvixub — branch on the canonical :rf.error/id, not the message.
      (is (= :rf.error/bad-app-schemas-arg (:rf.error/id (ex-data thrown)))))))

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
          ;; rf2-vvixub — branch on the canonical :rf.error/id, not the message.
          (is (= :rf.error/bad-app-schema-path (:rf.error/id (ex-data thrown)))
              "ex-data names the path-shape error category")
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
    (is (= :int (schemas/app-schema-at [:n])) "vector key-path registers")
    (rf/reg-app-schema [] [:map [:n :int]])
    (is (= [:map [:n :int]] (schemas/app-schema-at [])) "root [] registers")
    ;; A non-vector seq is still a valid get-in path shape.
    (rf/reg-app-schema (list :a :b) :string)
    (is (= :string (schemas/app-schema-at (list :a :b)))
        "non-vector seq path registers (get-in accepts any sequential ks)")))

(deftest reg-app-schema-normalizes-seq-path-to-canonical-vector
  (testing "rf2-94o54l.2 / EP-0012 §Path shape — a list path and the
            equivalent vector path are ONE canonical-vector identity:
            the entry is STORED under the canonical vector key, looked up
            by EITHER spelling, and re-registering via the other spelling
            overwrites the SAME slot (not a parallel list-keyed entry)"
    ;; Register via a non-vector seq; the stored key is the canonical vector.
    (rf/reg-app-schema (list :a :b) :string)
    (let [frame-keys (keys (get (schemas/snapshot-schemas-by-frame) :rf/default))]
      (is (every? vector? frame-keys)
          "every stored schemas-by-frame key is a canonical vector")
      (is (contains? (set frame-keys) [:a :b])
          "the stored key is the canonical VECTOR, not the supplied list"))
    ;; The vector-spelling lookup finds the list-registered entry.
    (is (= :string (schemas/app-schema-at [:a :b]))
        "vector lookup resolves the list-registered entry — one identity")
    ;; The stored meta :path is itself the canonical vector.
    (is (= [:a :b] (:path (schemas/app-schema-meta-at (list :a :b))))
        "schema-meta :path is the canonical vector, regardless of read spelling")
    ;; reg-app-schema returns the normalized canonical-vector path.
    (is (= [:c :d] (rf/reg-app-schema (list :c :d) :int))
        "reg-app-schema returns the canonical vector form of a seq path")
    ;; Re-registering via the vector spelling overwrites the SAME slot
    ;; (no parallel list-keyed entry survives).
    (rf/reg-app-schema [:a :b] :int)
    (is (= :int (schemas/app-schema-at (list :a :b)))
        "re-register via the vector spelling hits the same canonical slot")
    (is (= 1 (count (filter #(= [:a :b] %)
                            (keys (get (schemas/snapshot-schemas-by-frame) :rf/default)))))
        "exactly ONE entry for the path — no list-vs-vector key split")))

(deftest app-schemas-digest-list-path-equals-vector-path
  (testing "rf2-94o54l.2 / EP-0012 §Digest path keys — a frame populated
            via a list path digests identically to one populated via the
            equivalent vector path (digest keys derive from the canonical
            vector, not the raw container shape)"
    (rf/reg-app-schema (list :a :b) :string {:frame :seq-frame})
    (rf/reg-app-schema [:a :b] :string {:frame :vec-frame})
    (is (= (schemas/app-schemas-digest :vec-frame)
           (schemas/app-schemas-digest :seq-frame))
        "list-keyed and vector-keyed frames produce byte-identical digests")))

;; ===========================================================================
;; rf2-ujmc3u — schema paths are full :rf/path citizens (concrete segments)
;; ===========================================================================
;;
;; EP-0012 §The :rf/path algebra: schema paths inherit the shared path algebra
;; and concrete segments are portable EDN identity values. Pre-fix
;; `reg-app-schema` validated path SHAPE only (`sequential?`), so a sequential
;; path carrying a COMPOSITE / function / host / float / unsafe-integer
;; segment registered fine — outside the one `:rf/path` contract EP-0012
;; centralizes (and a non-portable segment would later poison the canonical
;; digest key). Registration + lookup now route through the SHARED concrete
;; boundary (`re-frame.path/normalize-concrete`).

(deftest reg-app-schema-rejects-non-concrete-segments
  (testing "a sequential path carrying a composite / function / host / float /
            unsafe-integer SEGMENT is rejected (not just non-sequential
            shapes) — schema paths are full :rf/path citizens (rf2-ujmc3u)"
    (doseq [bad-path [[:a [:nested] :b]          ;; composite (vector) segment
                      [:a {:k 1}]                ;; composite (map) segment
                      [:a #{:s}]                 ;; composite (set) segment
                      [:a 1.5]                   ;; float segment
                      [:a (fn [_])]              ;; function segment
                      [:a (Object.)]             ;; host-object segment
                      [:a 9007199254740992]      ;; unsafe-high integer segment
                      [:a -9007199254740992]]]   ;; unsafe-low integer segment
      (let [before (schemas/snapshot-schemas-by-frame)
            thrown (try (rf/reg-app-schema bad-path :int)
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown)
            (str "bad-segment path " (pr-str bad-path) " throws"))
        (is (= :rf.error/bad-app-schema-path (:rf.error/id (ex-data thrown)))
            (str "structured :rf.error/id for " (pr-str bad-path)))
        (is (= before (schemas/snapshot-schemas-by-frame))
            (str "store unchanged after rejecting " (pr-str bad-path)))))))

(deftest reg-app-schema-accepts-concrete-non-keyword-segments
  (testing "the full concrete-segment domain (keyword / string / safe-integer
            / boolean / UUID / instant / nil) is admitted — the narrowing is
            to the SHARED concrete domain, NOT keyword-only (which would break
            integer-indexed app-db paths like [:cart :items 42])"
    (rf/reg-app-schema [:cart :items 42 :qty] :int)
    (is (= :int (schemas/app-schema-at [:cart :items 42 :qty]))
        "an integer-indexed app-db path registers + looks up")
    (rf/reg-app-schema [:by-name "alice"] :string)
    (is (= :string (schemas/app-schema-at [:by-name "alice"])) "a string segment")
    (rf/reg-app-schema [:flag true] :boolean)
    (is (= :boolean (schemas/app-schema-at [:flag true])) "a boolean segment")
    ;; both safe-integer boundaries register (the canonical-identity range)
    (rf/reg-app-schema [:n 9007199254740991] :int)
    (is (= :int (schemas/app-schema-at [:n 9007199254740991]))
        "the max safe integer is an admitted segment")))

(deftest app-schema-lookup-rejects-non-concrete-segment
  (testing "a LOOKUP with a non-concrete segment fails closed loudly rather
            than silently missing (registration AND lookup route through the
            concrete boundary — rf2-ujmc3u acceptance)"
    (is (= :rf.error/bad-path
           (try (schemas/app-schema-at [:a [:nested]]) nil
                (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))
        "app-schema-at with a composite segment fails closed")
    (is (= :rf.error/bad-path
           (try (schemas/app-schema-meta-at [:a 1.5]) nil
                (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))
        "app-schema-meta-at with a float segment fails closed")))

(deftest reg-app-schemas-bulk-rejects-bad-segment-atomically
  (testing "bulk registration fails ATOMICALLY on an invalid-segment path —
            no entry in the batch lands when ANY key carries a non-concrete
            segment (the up-front sweep validates every segment before any
            store mutation — rf2-ujmc3u acceptance)"
    (let [before (schemas/snapshot-schemas-by-frame)
          thrown (try (rf/reg-app-schemas {[:good]            :int
                                           [:also :good]      :string
                                           [:bad [:composite]] :int}   ;; bad segment
                                          {:frame :bulk-frame})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown)
          "the batch with one bad-segment key throws")
      (is (= :rf.error/bad-app-schema-path (:rf.error/id (ex-data thrown))))
      (is (= before (schemas/snapshot-schemas-by-frame))
          "NOTHING in the batch landed — atomic all-or-nothing")
      (is (nil? (schemas/app-schema-at [:good] :bulk-frame))
          "the earlier-iterated good key did NOT register before the bad one threw"))))

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
        ;; rf2-vvixub — branch on the canonical :rf.error/id, not the message.
        (is (= :rf.error/bad-app-schema-path (:rf.error/id (ex-data thrown)))
            "names the path-shape error category"))
      (is (= before (schemas/snapshot-schemas-by-frame))
          "NO entry from the batch landed — all-or-nothing")
      (is (nil? (schemas/app-schema-at [:good]))
          "the well-formed sibling did not half-register"))))

(deftest reg-app-schemas-accepts-all-valid-paths
  (testing "rf2-sk0ql — a batch of valid sequential paths (including the
            root `[]`) registers every entry"
    (rf/reg-app-schemas {[:good]      :int
                         []           [:map]
                         [:cart :qty] :int})
    (is (= :int    (schemas/app-schema-at [:good])))
    (is (= [:map]  (schemas/app-schema-at [])))
    (is (= :int    (schemas/app-schema-at [:cart :qty])))))

;; ---- runtime-db path hard-reject (rf2-k0ew8n) ----------------------------
;;
;; RULING (b): `reg-app-schema` / `reg-app-schemas` HARD-REJECT any path
;; whose FIRST segment reaches into the runtime-db partition — a
;; `:rf.runtime/*` keyword, the `:rf.db/runtime` container root, or the
;; retired legacy app-db `:rf/runtime` root — with the DISTINCT id
;; `:rf.error/app-schema-runtime-path` (not `:rf.error/bad-app-schema-path`).
;; App schemas validate ONLY app-db: `validate-app-schema!` reads
;; `(get-in app-db path)`, so a runtime path either detonates every dev
;; commit (a normal `[:map …]` schema over a `nil` slot) or silently
;; installs a validator the author falsely believes guards runtime-db.
;; Nothing to soft-land — unlike the `:rf.db/runtime` EFFECT seam, no
;; legitimate caller exists, so this is a hard reject at the existing
;; pre-mutation gate. The runtime-db partition is framework-owned (the
;; framework validates it; machine `:snapshots` are refined per-machine from
;; each machine's `:data-schema`) and is NOT a user schema-registration
;; surface, so the honest remedy is to drop the runtime path.
;;
;; rf2-sklyam: the prior `:reason` told users to "Use reg-runtime-schema to
;; validate runtime-db state" — but `reg-runtime-schema` has NO public export
;; (it is framework-internal boot vocabulary; `git grep` finds only prose),
;; and runtime-db is framework-owned (Conventions §Reserved runtime-db keys —
;; "user code MUST NOT register against it"). The error must not direct a user
;; at a non-callable, framework-owned API. The tests below now FAIL if the
;; `:reason` names `reg-runtime-schema`, pinning the corrected contract.

(def runtime-paths-rejected
  "Every shape `runtime-app-schema-path?` must reject as a first segment."
  [[:rf.runtime/machines :snapshots :m/x]
   [:rf.runtime/routing :current]
   [:rf.runtime/elision :decls]
   [:rf.db/runtime :rf.runtime/machines]   ; runtime-db container root
   [:rf/runtime :legacy :slice]])          ; retired legacy app-db root

(deftest reg-app-schema-rejects-runtime-path-with-distinct-error
  (testing "rf2-k0ew8n / rf2-sklyam — a singular reg-app-schema against a
            runtime-db path throws :rf.error/app-schema-runtime-path (distinct
            from the shape error), carries :received + :frame + a :reason that
            states the honest remedy (drop the runtime path; runtime-db is
            framework-owned) and does NOT direct the user at the non-public,
            framework-owned `reg-runtime-schema` API, and stores NOTHING"
    (doseq [bad-path runtime-paths-rejected]
      (let [before (schemas/snapshot-schemas-by-frame)
            thrown (try (rf/reg-app-schema bad-path [:map] {:frame :tenant/rt})
                        (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo thrown)
            (str "runtime path " (pr-str bad-path) " throws ex-info"))
        (when (instance? clojure.lang.ExceptionInfo thrown)
          (let [data   (ex-data thrown)
                reason (:reason data)]
            ;; rf2-vvixub — branch on the canonical :rf.error/id, not the
            ;; (non-normative) message bytes.
            (is (= :rf.error/app-schema-runtime-path (:rf.error/id data))
                ":rf.error/id is the distinct runtime-path id")
            (is (= bad-path (:received data))
                ":received carries the offending path verbatim")
            (is (= :tenant/rt (:frame data))
                ":frame carries the resolved registration frame")
            ;; rf2-sklyam — ADVERSARIAL: the prior bug pointed users at a
            ;; phantom, framework-owned API. The reason MUST NOT name it.
            (is (not (re-find #"reg-runtime-schema" (str reason)))
                (str ":reason must NOT direct the user at the non-public, "
                     "framework-owned reg-runtime-schema API (rf2-sklyam)"))
            ;; ...and MUST state the honest contract: runtime-db is
            ;; framework-owned, so the remedy is to stop using the runtime path.
            (is (re-find #"(?i)framework-owned" (str reason))
                ":reason states the runtime-db partition is framework-owned")
            (is (re-find #"(?i)app-?db" (str reason))
                ":reason reminds the user app schemas validate app-db only")))
        (is (= before (schemas/snapshot-schemas-by-frame))
            (str "store unchanged after rejecting " (pr-str bad-path)))))))

(deftest reg-app-schema-runtime-path-distinct-from-shape-error
  (testing "rf2-k0ew8n — the runtime-path id is NOT the shape-error id; a
            runtime path is well-SHAPED (sequential) so it passes the shape
            gate and is rejected by the SEPARATE namespace gate"
    (is (storage/valid-app-schema-path? [:rf.runtime/machines])
        "a runtime path is a valid SHAPE — sequential")
    (is (storage/runtime-app-schema-path? [:rf.runtime/machines])
        "but the first-segment runtime check catches it")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf.error/app-schema-runtime-path"
                          (rf/reg-app-schema [:rf.runtime/machines] [:map]
                                             {:frame :tenant/rt})))))

(deftest reg-app-schema-accepts-non-runtime-rf-paths
  (testing "rf2-k0ew8n — the gate keys on the runtime-db FIRST segment only;
            an ordinary app-db path with an `:rf*`-ish key that is NOT a
            runtime root registers fine, and a runtime keyword NOT in head
            position is allowed"
    (rf/reg-app-schema [:user] [:map [:id :int]] {:frame :tenant/rt})
    (is (= [:map [:id :int]] (schemas/app-schema-at [:user] :tenant/rt)))
    ;; A runtime keyword that is NOT the first segment is fine — only the
    ;; head matters (app-db could legitimately hold such a nested key).
    (rf/reg-app-schema [:user :rf.runtime/note] :string {:frame :tenant/rt})
    (is (= :string (schemas/app-schema-at [:user :rf.runtime/note] :tenant/rt)))
    ;; The empty root [] is the whole-app-db schema — never runtime.
    (is (not (storage/runtime-app-schema-path? [])))
    (is (not (storage/runtime-app-schema-path? [:rf/something-else])))))

(deftest reg-app-schemas-rejects-batch-with-runtime-path-atomically
  (testing "rf2-k0ew8n — a bulk batch containing one runtime-db path key is
            rejected ATOMICALLY with :rf.error/app-schema-runtime-path before
            any mutation: NO entry lands, not even well-formed app-db siblings"
    (let [before (schemas/snapshot-schemas-by-frame)
          thrown (try (rf/reg-app-schemas {[:good]                 :int
                                           [:rf.runtime/machines]  [:map]
                                           [:also-good]            :boolean}
                                          {:frame :tenant/rt})
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo thrown)
          "a batch with a runtime path key throws")
      (when (instance? clojure.lang.ExceptionInfo thrown)
        ;; rf2-vvixub — branch on the canonical :rf.error/id, not the message.
        (is (= :rf.error/app-schema-runtime-path
               (:rf.error/id (ex-data thrown)))
            "names the distinct runtime-path error category"))
      (is (= before (schemas/snapshot-schemas-by-frame))
          "NO entry from the batch landed — all-or-nothing")
      (is (nil? (schemas/app-schema-at [:good] :tenant/rt))
          "the well-formed app-db sibling did not half-register"))))

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
    (rf/register-listener! :trace kw (fn [ev] (swap! traces conj ev)))
    (let [result (validate/validate-app-schema! db failing-id frame)]
      (rf/unregister-listener! :trace kw)
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
      (is (= [:vector] (schemas/app-schema-at [:user]))
          "mutation visible before restore")
      ;; Restore.
      (schemas/restore-schemas-by-frame! snap)
      (is (= [:map [:id :int]] (schemas/app-schema-at [:user]))
          "restore replays the captured state")
      (is (= [:string] (schemas/app-schema-at [:auth]))
          "sibling entries restored too"))))

(deftest clear-removes-all-registrations
  (testing "clear-schemas-by-frame! drops every frame's registrations"
    (rf/reg-app-schema [:user] [:map])
    (rf/reg-app-schema [:user] [:vector] {:frame :tenant/a})
    (schemas/clear-schemas-by-frame!)
    (is (nil? (schemas/app-schema-at [:user]))
        "default-frame registration cleared")
    (is (nil? (schemas/app-schema-at [:user] :tenant/a))
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
    (is (nil? (schemas/app-schema-at [:user] :tenant/doomed))
        "looking up a destroyed frame's schema yields nil")
    (is (= [:map] (schemas/app-schema-at [:user] :tenant/survivor))
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
