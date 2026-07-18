(ns re-frame.ui.frame-destroyed-op-schema-jvm-test
  "rf2-vub3y — pin the canonical `FrameDestroyedTags` `:op` declaration in
  `spec/Spec-Schemas.md` against what the runtime ACTUALLY emits on the
  dev-trace `:tags` channel.

  `:op` is a ratified-PUBLIC, small closed-enum realm attribution on
  `:rf.error/frame-destroyed` (PR #6254 / rf2-a2x2w; see the Spec 009 error
  catalogue row). It rides BOTH the always-on record attrs (axis 1) and the
  dev-trace `:tags` (axis 2) — but only the record side was ever asserted
  (`frame-ops-cljs-test` checks `(:op r)` and merely COUNTS traces). The
  per-category `:tags` schema is the canonical CLJS-reference shape that ports
  translate mechanically, so an undeclared-but-emitted key is a real contract
  hole. This suite closes it from BOTH ends:

    - the schema is read from the MARKDOWN at run time (never hand-copied
      here), so deleting or narrowing the `:op` slot goes red;
    - every value in the declared enum is DRIVEN through a real emit and
      observed on `[:tags :op]`, so a declared-but-never-emitted value goes
      red too (the rf2-jxpf3 failure mode — 19 catalogue rows claimed tags
      that were never emitted);
    - the ordinary address-directed path is driven and asserted to OMIT
      `:op`, so promoting the slot to required goes red.

  JVM-only by construction: it slurps the spec markdown directly, so it needs
  no compile-time extraction macro. The `(frame)` bundle fences it drives are
  the ONLY surface that reaches all four enum values (`:capture` is ui-only)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.error-emit           :as error-emit]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as test-support]
            [re-frame.trace                :as trace]
            [re-frame.ui.frames            :as frames]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter       plain-atom/adapter
    :ambient-frame nil
    :init-fn       (fn [] (error-emit/clear-error-listeners!))})
  (fn [f]
    (frames/reset-frame-ops-cache!)
    (try (f) (finally (frames/reset-frame-ops-cache!)))))

;; ---------------------------------------------------------------------------
;; The canonical schema, read from spec/Spec-Schemas.md
;; ---------------------------------------------------------------------------

(def ^:private spec-schemas-candidates
  ;; Resolve relative to whichever directory the runner starts in
  ;; (`clojure -M:test` runs from implementation/ui).
  ["../../spec/Spec-Schemas.md" "../spec/Spec-Schemas.md" "spec/Spec-Schemas.md"])

(defn- frame-destroyed-tags-form
  "Return the `[:map …]` form of `(def FrameDestroyedTags …)` as data. The `;;`
  comments in the def are dropped by the EDN reader, so this is the pure
  canonical shape. Throws if the def is absent or is not a `[:map …]`."
  []
  (let [file (or (some (fn [p] (let [f (io/file p)] (when (.exists f) f)))
                       spec-schemas-candidates)
                 (throw (ex-info "rf2-vub3y: cannot locate spec/Spec-Schemas.md"
                                 {:candidates spec-schemas-candidates})))
        text  (slurp file)
        start (str/index-of text "(def FrameDestroyedTags")]
    (when (nil? start)
      (throw (ex-info "rf2-vub3y: (def FrameDestroyedTags …) not found in Spec-Schemas.md" {})))
    (let [schema (nth (edn/read-string (subs text start)) 2 nil)]
      (when-not (and (vector? schema) (= :map (first schema)))
        (throw (ex-info "rf2-vub3y: FrameDestroyedTags is not a [:map …] form" {:read schema})))
      schema)))

(defn- op-entry
  "The canonical FrameDestroyedTags `:op` slot as `{:props … :schema …}`, or nil
  when the slot is absent. Malli map entries are `[k schema]` OR `[k props
  schema]`; normalising both here means a slot that DROPS its props map (i.e. is
  promoted to required) fails the props assertion cleanly instead of throwing."
  []
  (when-let [entry (first (filter #(and (vector? %) (= :op (first %)))
                                  (rest (frame-destroyed-tags-form))))]
    (if (= 3 (count entry))
      {:props (nth entry 1) :schema (nth entry 2)}
      {:props nil :schema (nth entry 1)})))

;; ---------------------------------------------------------------------------
;; Driving the real emits
;; ---------------------------------------------------------------------------

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- reg! []
  (rf/reg-event :ops/set-n (fn [_ [_ n]] {:db {:n n}}))
  (rf/reg-sub :ops/n (fn [db _] (:n db))))

(defn- frame-destroyed-trace-tags
  "Run `thunk` and return the `:tags` maps of every `:rf.error/frame-destroyed`
  dev-trace event it emitted. Swallows the typed throw the fail-loud `(frame)`
  surfaces raise — this suite is about the emitted envelope, not the throw."
  [thunk]
  (let [traces (atom [])
        tkey   (keyword "test" (name (gensym "fd-op-trace")))]
    (trace/register-listener! tkey
                              (fn [ev] (when (= :rf.error/frame-destroyed (:operation ev))
                                         (swap! traces conj (:tags ev)))))
    (try
      (try (thunk) (catch clojure.lang.ExceptionInfo _ nil))
      @traces
      (finally (trace/unregister-listener! tkey)))))

(defn- emitted-op-for
  "Drive one `(frame)`-bundle arm and return the single emitted `:tags` map."
  [thunk]
  (let [tags (frame-destroyed-trace-tags thunk)]
    (is (= 1 (count tags)) "exactly one :rf.error/frame-destroyed dev trace")
    (first tags)))

;; ---------------------------------------------------------------------------
;; Legs
;; ---------------------------------------------------------------------------

(deftest canonical-schema-declares-the-public-op-slot
  (testing "spec/Spec-Schemas.md FrameDestroyedTags declares `:op` as an
            OPTIONAL closed enum — the ratified-public realm attribution"
    (let [{:keys [props schema]} (op-entry)]
      (is (some? (op-entry)) "FrameDestroyedTags declares an :op slot")
      (is (= {:optional true} props)
          ":op is OPTIONAL — presence tracks the emit site's knowledge of the realm")
      (is (= [:enum :dispatch :dispatch-sync :subscribe :capture] schema)
          ":op is the exact four-value closed enum ratified by Spec 009"))))

(deftest every-declared-op-value-is-actually-emitted-on-the-tags-channel
  (testing "each value in the canonical enum is reachable through a REAL emit
            and lands on [:tags :op] — no declared-but-phantom value (rf2-jxpf3)"
    (reg!)
    (let [declared (set (rest (:schema (op-entry))))
          observed (atom #{})]
      ;; The three stale-bundle-op arms.
      (make-frame! :ops/doomed {:n 1})
      (let [b (rf/with-frame :ops/doomed (frames/frame-ops))]
        (frame/destroy-frame! :ops/doomed)
        (doseq [[op thunk] [[:dispatch      #((:dispatch b) [:ops/set-n 2])]
                            [:dispatch-sync #((:dispatch-sync b) [:ops/set-n 2])]
                            [:subscribe     #((:subscribe b) [:ops/n])]]]
          (testing (str "stale bundle " op)
            (let [tags (emitted-op-for thunk)]
              (is (contains? tags :op) ":op is PRESENT on the dev-trace tags")
              (is (= op (:op tags)) ":op names the failing operation realm")
              (is (contains? declared (:op tags))
                  ":op is a member of the canonical closed enum")
              (swap! observed conj (:op tags))))))
      ;; The ui-only `:capture` arm — a `(frame)` read resolving a dead
      ;; incarnation before any op ran.
      (testing "capture-time read of a dead incarnation"
        (let [tags (emitted-op-for
                    #(binding [frame/*current-frame* :ops/ghost] (frames/frame-ops)))]
          (is (contains? tags :op) ":op is PRESENT on the dev-trace tags")
          (is (= :capture (:op tags)) ":op marks the capture-time failure")
          (is (contains? declared (:op tags))
              ":op is a member of the canonical closed enum")
          (swap! observed conj (:op tags))))
      (is (= declared @observed)
          "the declared enum is exactly the set of values the runtime emits —
           neither a phantom declared value nor an undeclared emitted one"))))

(deftest ordinary-address-directed-emit-omits-op
  (testing "an ordinary address-directed dispatch into a destroyed frame carries
            NO captured incarnation, so it omits `:op` entirely — which is why
            the slot is `{:optional true}` and not required"
    (reg!)
    (make-frame! :ops/plain {:n 1})
    (frame/destroy-frame! :ops/plain)
    (let [tags (frame-destroyed-trace-tags
                #(rf/dispatch-sync [:ops/set-n 5] {:frame :ops/plain}))]
      (is (seq tags) "the ordinary path still emits :rf.error/frame-destroyed")
      (doseq [t tags]
        (is (not (contains? t :op))
            "no :op key at all — the tight legacy record shape is unchanged")
        (is (= :rf.error/frame-destroyed (:category t))
            ":category IS present — frame-destroyed is an :error envelope
             (build-event merges {:category operation} on the error branch only)")))))
