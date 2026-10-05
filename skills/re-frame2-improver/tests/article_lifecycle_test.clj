;;;; tests/article_lifecycle_test.clj — the canonical HTTP fix in
;;;; references/schemaless-events.md must SETTLE its lifecycle.
;;;;
;;;; The leaf's "After — schema-validated boundary with a production gate"
;;;; block is copyable canonical code: a reader pastes it into a status-driven
;;;; page. A block that starts `[:article :status]` at `:loading` and never
;;;; leaves it — `:article/loaded` writing only `[:article :data]` and
;;;; `:article/load-failed` writing only `[:article :error]` — would leave the
;;;; spinner up for ever after both a successful load and a classified failure.
;;;; The payload and the error arrive correctly; the lifecycle simply never
;;;; closes, which is the missing-terminator class the sibling leaf
;;;; `manual-loading-flags.md` exists to diagnose. A schema-only reading of the
;;;; leaf (and of eval 20) could satisfy the stated fix while keeping that
;;;; bug, which is why it is pinned here rather than left to prose.
;;;;
;;;; This suite EVALUATES the shipped block rather than grepping it: it
;;;; extracts the fenced source, binds `rf/reg-event` + `rf/reg-app-schema` to
;;;; process-local collectors, and runs the three pure `:db` transitions. No
;;;; framework runtime, no HTTP, no schema execution — it validates exactly the
;;;; handler bodies a reader would copy, and nothing it does not.
;;;;
;;;; Discriminating in both directions: a completion expression that writes
;;;; only the payload or only the error (`{:db (assoc-in db [:article :data] …)}` /
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

;; A missing or duplicated After block, or an id it does not register, makes
;; every test that calls the handler error.
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
;; The always-on gate is the point of the block
;; ---------------------------------------------------------------------------

(deftest request-keeps-its-always-on-decode-gate-and-both-reply-targets
  (let [{:keys [fx]} ((handler :article/load) {:db {}} [:article/load {:slug "alpha"}])
        opts         (->> fx
                          (filter #(= :rf.http/managed (first %)))
                          first
                          second)]
    (testing "the managed-HTTP effect is present"
      (is (map? opts) "the After block does not issue an :rf.http/managed request."))
    (testing ":decode is the always-on production gate the leaf is about"
      (is (vector? (:decode opts))
          (str "the :decode gate is missing or does not carry the Article schema "
               "value — a missing gate is the defect this whole leaf teaches against.")))
    (testing "both reply branches are addressed"
      (is (= [:article/loaded] (:on-success opts)))
      (is (= [:article/load-failed] (:on-failure opts))))))

;; ---------------------------------------------------------------------------
;; The lifecycle settles on BOTH branches
;; ---------------------------------------------------------------------------

(deftest load-starts-the-lifecycle
  (testing ":article/load puts the slice in flight"
    (is (= :loading (get-in @loading-db [:article :status]))
        ":article/load does not start the lifecycle at :loading.")))

(deftest success-settles-the-lifecycle-and-stores-the-validated-payload
  (let [db (:db ((handler :article/loaded) {:db @loading-db} [:article/loaded success-reply]))]
    (testing "status leaves :loading for :loaded"
      (is (= :loaded (get-in db [:article :status]))
          (str "a SUCCESSFUL reply must settle [:article :status] at :loaded, matching "
               "the RemoteData slice. Left at :loading, a reader pasting this canonical "
               "fix into a status-driven page gets a permanent spinner after a perfectly "
               "good load.")))
    (testing "the validated payload is stored unchanged at the schema'd path, with no lifecycle key"
      (is (= article (get-in db [:article :data]))
          (str ":value must land verbatim under [:article :data] — reg-app-schema sees it, "
               "and a lifecycle key leaked there, registered against Article, fails dev "
               "app-schema validation.")))))

(deftest failure-settles-the-lifecycle-and-stores-the-classified-error
  (let [db (:db ((handler :article/load-failed) {:db @loading-db} [:article/load-failed failure-reply]))]
    (testing "status leaves :loading for :error"
      (is (= :error (get-in db [:article :status]))
          (str "a FAILED reply must settle [:article :status] at :error. Left at "
               ":loading, the classified error arrived correctly but the lifecycle "
               "never closed.")))
    (testing "the classified error is stored unchanged, beside the payload path"
      (is (= (:error failure-reply) (get-in db [:article :error]))
          "the classified :rf.http/* map must land verbatim under [:article :error]."))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'article-lifecycle-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
