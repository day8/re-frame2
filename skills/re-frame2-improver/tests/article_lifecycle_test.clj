;;;; tests/article_lifecycle_test.clj — the canonical HTTP fix in
;;;; references/schemaless-events.md must SETTLE its lifecycle (rf2-fzbj.40).
;;;;
;;;; The leaf's "After — schema-validated boundary with a production gate"
;;;; block is copyable canonical code: a reader pastes it into a status-driven
;;;; page. It used to start `[:article :status]` at `:loading` and then never
;;;; leave it — `:article/loaded` wrote only `[:article :data]` and
;;;; `:article/load-failed` wrote only `[:article :error]` — so both a
;;;; successful load and a classified failure left the spinner up for ever.
;;;; The payload and the error arrived correctly; the lifecycle simply never
;;;; closed, which is the missing-terminator class the sibling leaf
;;;; `manual-loading-flags.md` exists to diagnose. A schema-only reading of the
;;;; leaf (and of eval 20) could satisfy the stated fix while preserving that
;;;; bug, which is why it is pinned here rather than left to prose.
;;;;
;;;; This suite EVALUATES the shipped block rather than grepping it: it
;;;; extracts the fenced source, binds `rf/reg-event` + `rf/reg-app-schema` to
;;;; process-local collectors, and runs the three pure `:db` transitions. No
;;;; framework runtime, no HTTP, no schema execution — it validates exactly the
;;;; handler bodies a reader would copy, and nothing it does not.
;;;;
;;;; Discriminating in both directions: restoring either original
;;;; completion expression (`{:db (assoc-in db [:article :data] …)}` /
;;;; `{:db (assoc-in db [:article :error] …)}`) fails the corresponding
;;;; settled-status assertion while the payload/error assertions stay green.
;;;;
;;;; Run locally:  bb tests/article_lifecycle_test.clj   (from the skill root)
;;;; Exit:         0 = pass, non-zero = fail.
;;;;
;;;; CI: gated by the `skills-structural` job in .github/workflows/test.yml,
;;;; which loops `skills/re-frame2-improver/tests/*_test.clj`.
;;;;
;;;; NOT published — package.json `files` excludes `tests/`.

(ns article-lifecycle-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

;; ---------------------------------------------------------------------------
;; Sources
;; ---------------------------------------------------------------------------

(def ^:private skill-root
  (-> *file*
      (io/file)
      (.getAbsoluteFile)
      (.getParentFile)   ;; tests/
      (.getParentFile))) ;; skills/re-frame2-improver/

(def ^:private leaf-md
  (delay (slurp (io/file skill-root "references" "schemaless-events.md"))))

(defn- clojure-blocks
  "Every ```clojure fenced block in `md`, fence lines excluded."
  [md]
  (->> (re-seq #"(?s)```clojure\r?\n(.*?)```" md)
       (map second)))

(defn- block-containing
  "The single fenced block containing `needle`. Nil when absent, and
  deliberately nil when ambiguous — a silently-picked first match would let a
  duplicated block pass a check the other copy fails."
  [needle]
  (let [hits (filter #(str/includes? % needle) (clojure-blocks @leaf-md))]
    (when (= 1 (count hits)) (first hits))))

;; `:article/load-failed` is registered only by the After block: the Before
;; block has no failure branch at all, and the Regression example registers
;; `:session/rehydrate`.
(def ^:private after-block
  (delay (block-containing "reg-event :article/load-failed")))

;; ---------------------------------------------------------------------------
;; Evaluating the shipped block
;; ---------------------------------------------------------------------------
;;
;; The block's only framework calls are `rf/reg-event` and `rf/reg-app-schema`.
;; Dropping the `rf/` prefix routes them at the two collectors below, which live
;; in this namespace; everything else in the block is ordinary data and
;; `assoc-in`, so the handler bodies run unmodified.

(def ^:private registry (atom {}))

(defn reg-event
  ([id f]        (swap! registry assoc id f))
  ([id _opts f]  (swap! registry assoc id f)))

(defn reg-app-schema [_path _schema] nil)

(def ^:private handlers
  (delay
    (reset! registry {})
    (load-string (str/replace @after-block "rf/" ""))
    @registry))

(defn- handler [id] (get @handlers id))

;; The canonical envelopes EP-0011 delivers to the two reply targets.
(def ^:private article
  {:slug "alpha" :title "Alpha" :body "Synthetic" :authors []})

(def ^:private success-reply {:status :ok :value article})

(def ^:private failure-reply
  {:status :error
   :error  {:category :rf.http/http-5xx :message "Synthetic failure"}})

(def ^:private loading-db
  (delay (:db ((handler :article/load) {:db {}} [:article/load {:slug "alpha"}]))))

;; ---------------------------------------------------------------------------
;; Premise — the block is present and registers all three lifecycle events
;; ---------------------------------------------------------------------------

(deftest after-block-is-found-and-complete
  (testing "exactly one fenced block registers :article/load-failed"
    (is (some? @after-block)
        "the canonical After block is missing, renamed, or duplicated"))
  (testing "it registers the load and both reply handlers"
    (doseq [id [:article/load :article/loaded :article/load-failed]]
      (is (some? (handler id))
          (str "the After block no longer registers " id
               ". A canonical fix without both reply branches cannot settle its "
               "lifecycle at all.")))))

;; ---------------------------------------------------------------------------
;; The always-on gate is still the point of the block
;; ---------------------------------------------------------------------------

(deftest request-keeps-its-always-on-decode-gate-and-both-reply-targets
  (let [{:keys [fx]} ((handler :article/load) {:db {}} [:article/load {:slug "alpha"}])
        opts         (->> fx
                          (filter #(= :rf.http/managed (first %)))
                          first
                          second)]
    (testing "the managed-HTTP effect is still there"
      (is (map? opts) "the After block no longer issues an :rf.http/managed request."))
    (testing ":decode is the always-on production gate the leaf is about"
      (is (contains? opts :decode)
          "the :decode gate is gone — that is the defect this whole leaf teaches against.")
      (is (vector? (:decode opts))
          ":decode no longer carries the Article schema value."))
    (testing "both reply branches are addressed"
      (is (= [:article/loaded] (:on-success opts)))
      (is (= [:article/load-failed] (:on-failure opts))))))

;; ---------------------------------------------------------------------------
;; The lifecycle settles on BOTH branches (the finding)
;; ---------------------------------------------------------------------------

(deftest load-starts-the-lifecycle
  (testing ":article/load puts the slice in flight"
    (is (= :loading (get-in @loading-db [:article :status]))
        ":article/load no longer starts the lifecycle at :loading.")))

(deftest success-settles-the-lifecycle-and-stores-the-validated-payload
  (let [db (:db ((handler :article/loaded) {:db @loading-db} [:article/loaded success-reply]))]
    (testing "status leaves :loading"
      (is (not= :loading (get-in db [:article :status]))
          (str "a SUCCESSFUL reply leaves [:article :status] at :loading. A reader "
               "pasting this canonical fix into a status-driven page gets a permanent "
               "spinner after a perfectly good load (rf2-fzbj.40 F1)."))
      (is (= :loaded (get-in db [:article :status]))
          "the settled success status must be :loaded, matching the RemoteData slice."))
    (testing "the validated payload is stored unchanged at the schema'd path"
      (is (= article (get-in db [:article :data]))
          ":value must land verbatim under [:article :data] — reg-app-schema sees it."))
    (testing "lifecycle keys stay OFF the schema-registered payload path"
      (is (= #{:slug :title :body :authors} (set (keys (get-in db [:article :data]))))
          (str "a lifecycle key leaked under [:article :data], which is registered "
               "against Article — that fails dev app-schema validation.")))))

(deftest failure-settles-the-lifecycle-and-stores-the-classified-error
  (let [db (:db ((handler :article/load-failed) {:db @loading-db} [:article/load-failed failure-reply]))]
    (testing "status leaves :loading"
      (is (not= :loading (get-in db [:article :status]))
          (str "a FAILED reply leaves [:article :status] at :loading. The classified "
               "error arrived correctly; the lifecycle never closed (rf2-fzbj.40 F1)."))
      (is (= :error (get-in db [:article :status]))
          "the settled failure status must be :error."))
    (testing "the classified error is stored unchanged, beside the payload path"
      (is (= (:error failure-reply) (get-in db [:article :error]))
          "the classified :rf.http/* map must land verbatim under [:article :error]."))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'article-lifecycle-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
