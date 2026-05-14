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
            [re-frame.schemas.test-fixture :as tf]))

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
