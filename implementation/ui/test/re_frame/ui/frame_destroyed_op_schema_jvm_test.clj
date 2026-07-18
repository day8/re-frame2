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
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core                   :as rf]
            [re-frame.error-emit             :as error-emit]
            [re-frame.frame                  :as frame]
            [re-frame.live-frame             :as live-frame]
            [re-frame.substrate.observation  :as obs]
            [re-frame.substrate.plain-atom   :as plain-atom]
            [re-frame.test-support           :as test-support]
            [re-frame.trace                  :as trace]
            [re-frame.ui.frames              :as frames]))

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

(defn- slot-entry
  "The canonical FrameDestroyedTags slot `k` as `{:props … :schema …}`, or nil
  when the slot is absent. Malli map entries are `[k schema]` OR `[k props
  schema]`; normalising both here means a slot that DROPS its props map (i.e. is
  promoted to required) fails the props assertion cleanly instead of throwing."
  [k]
  (when-let [entry (first (filter #(and (vector? %) (= k (first %)))
                                  (rest (frame-destroyed-tags-form))))]
    (if (= 3 (count entry))
      {:props (nth entry 1) :schema (nth entry 2)}
      {:props nil :schema (nth entry 1)})))

(defn- op-entry [] (slot-entry :op))

(defn- declared-slots
  "The set of slot KEYS the canonical schema declares."
  []
  (set (map first (filter vector? (rest (frame-destroyed-tags-form))))))

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

;; ---------------------------------------------------------------------------
;; rf2-g8ict — the declared slot roster vs the FOUR live emitters
;; ---------------------------------------------------------------------------
;;
;; The `:op` legs above pin ONE slot. This section pins the whole roster in both
;; directions, because the same failure mode had struck the payload pair:
;; `FrameDestroyedTags` declared `:rf.event/v` + `:rf.sub/query-v` as THE
;; payload slots, but no emitter stamps `:rf.event/v` for this category, while
;; the bare `:event` / `:query-v` / `:reason` / `:where` / `:rf.sub/id` that
;; three of the four emitters DO stamp were undeclared.
;;
;; The bare `:event` spelling is deliberate, not drift:
;; `re-frame.classification/project-trace-event` walks `:event` and
;; `:rf.event/v` through the SAME `project-event-tags` redaction chokepoint.
;; So the document was wrong and the runtime was right — no emitter was renamed.

(defn- frame-destroyed-tag-keys
  "The tag-key set of every `:rf.error/frame-destroyed` dev trace `thunk` emits."
  [thunk]
  (into #{} (mapcat keys) (frame-destroyed-trace-tags thunk)))

(deftest declared-slots-are-exactly-the-slots-the-live-emitters-stamp
  (testing "rf2-g8ict: the canonical schema declares NO phantom slot and OMITS
            no slot a live emitter stamps. Both directions, across all four
            frame-destroyed surfaces: router (ordinary dispatch), subs
            (ordinary subscribe), the internal observation port, and the ui
            `(frame)` bundle (stale op + capture)"
    (reg!)
    (let [observed (atom #{})
          note!    (fn [ks] (swap! observed into ks))]
      ;; 1 — router: ordinary address-directed dispatch into a destroyed frame.
      (make-frame! :ops/router-arm {:n 1})
      (frame/destroy-frame! :ops/router-arm)
      (note! (frame-destroyed-tag-keys
              #(rf/dispatch-sync [:ops/set-n 5] {:frame :ops/router-arm})))

      ;; 2 — subs: ordinary address-directed subscribe into a destroyed frame.
      (make-frame! :ops/subs-arm {:n 1})
      (frame/destroy-frame! :ops/subs-arm)
      (note! (frame-destroyed-tag-keys
              #(rf/subscribe [:ops/n] {:frame :ops/subs-arm})))

      ;; 3 — the internal observation port (throwing surface, namespaced trio).
      (make-frame! :ops/obs-arm {:n 1})
      (let [target (obs/resolve-target {:frame :ops/obs-arm :query-v [:ops/n]})]
        (frame/destroy-frame! :ops/obs-arm)
        (note! (frame-destroyed-tag-keys #(obs/probe target))))

      ;; 4 — the ui `(frame)` bundle: a stale op AND the capture arm.
      (make-frame! :ops/ui-arm {:n 1})
      (let [b (rf/with-frame :ops/ui-arm (frames/frame-ops))]
        (frame/destroy-frame! :ops/ui-arm)
        (note! (frame-destroyed-tag-keys #((:dispatch b) [:ops/set-n 2]))))
      (note! (frame-destroyed-tag-keys
              #(binding [frame/*current-frame* :ops/ghost] (frames/frame-ops))))

      (let [declared (declared-slots)
            emitted  @observed]
        (is (seq emitted)
            "the four surfaces actually emitted — a vacuous set would make the
             set-equality below pass for the wrong reason")
        (is (empty? (clojure.set/difference declared emitted))
            (str "no PHANTOM slot: every declared slot is stamped by some live "
                 "emitter. Declared-but-never-emitted: "
                 (clojure.set/difference declared emitted)))
        (is (empty? (clojure.set/difference emitted declared))
            (str "no UNDECLARED slot: every stamped slot is declared. "
                 "Emitted-but-undeclared: "
                 (clojure.set/difference emitted declared)))))))

(deftest the-payload-slot-per-surface-is-the-one-the-schema-names
  (testing "rf2-g8ict: the per-surface presence/absence rules — the bare
            `:event` on the router + ui arms, `:query-v` on the subs arm, and
            the namespaced `:rf.sub/query-v` (never `:rf.event/v`) on the
            observation port"
    (reg!)
    (make-frame! :ops/p-router {:n 1})
    (frame/destroy-frame! :ops/p-router)
    (let [tags (first (frame-destroyed-trace-tags
                       #(rf/dispatch-sync [:ops/set-n 5] {:frame :ops/p-router})))]
      (is (= [:ops/set-n 5] (:event tags))
          "router stamps the bare `:event` — the classification-aware error-tag
           spelling, NOT the dispatch-pipeline `:rf.event/v`")
      (is (not (contains? tags :rf.event/v))
          "`:rf.event/v` is phantom for this category")
      (is (= :frame-destroyed (:reason tags)))
      (is (not (contains? tags :recovery))
          "`:recovery` is hoisted out of :tags by build-event on every branch"))

    (make-frame! :ops/p-subs {:n 1})
    (frame/destroy-frame! :ops/p-subs)
    (let [tags (first (frame-destroyed-trace-tags
                       #(rf/subscribe [:ops/n] {:frame :ops/p-subs})))]
      (is (= [:ops/n] (:query-v tags))
          "subs stamps the bare `:query-v`")
      (is (not (contains? tags :rf.sub/query-v))
          "…not the namespaced spelling — that one belongs to the port"))

    (make-frame! :ops/p-obs {:n 1})
    (let [target (obs/resolve-target {:frame :ops/p-obs :query-v [:ops/n]})]
      (frame/destroy-frame! :ops/p-obs)
      (let [tags (first (frame-destroyed-trace-tags #(obs/probe target)))]
        (is (= [:ops/n] (:rf.sub/query-v tags))
            "the observation port uses the NAMESPACED sub spellings, matching
             its `:rf.error/observation-retry-exhausted` sibling row")
        (is (= :ops/n (:rf.sub/id tags)))
        (is (some? (:where tags)) "`:where` names the port call site")
        (is (not (contains? tags :query-v)))))))

(deftest ui-arm-redacts-the-payload-body-so-event-is-not-always-a-vector
  (testing "rf2-g8ict: the ui `(frame)` surface redacts the attempted payload
            AT SOURCE, and its capture arm has no payload at all. So the
            schema types `:event` as `:any` — declaring `[:vector :any]` would
            be a claim the runtime does not honour"
    (reg!)
    (make-frame! :ops/redact-arm {:n 1})
    (let [b (rf/with-frame :ops/redact-arm (frames/frame-ops))]
      (frame/destroy-frame! :ops/redact-arm)
      (let [tags (first (frame-destroyed-trace-tags
                         #((:dispatch b) [:ops/set-n 2])))]
        (is (contains? tags :event) "the slot is present…")
        (is (not (vector? (:event tags)))
            "…but NOT a vector — the ui arm redacts the body at source")))
    (let [tags (first (frame-destroyed-trace-tags
                       #(binding [frame/*current-frame* :ops/ghost]
                          (frames/frame-ops))))]
      (is (contains? tags :event) "the capture arm still carries the slot")
      (is (nil? (:event tags))
          "…with nil — no op ran, so there is no attempted payload"))
    (is (= :any (:schema (slot-entry :event)))
        "and the canonical schema says so")))

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
