(ns re-frame.mcp-conformance.trace-catalogue-lint-test
  "Trace-catalogue one-name-per-fact lint (rf2-o6c2jr).

  ## Why this gate

  `spec/Conventions.md` §The naming rules (one name per fact) forbids a
  subsystem carrying the same fact under two keys. Managed-Effects property 9
  requires managed async families to emit trace rows FROM reply-envelope facts,
  and the async-completion / stale-suppression trace rows historically stamped
  the SAME fact twice — the work identity under BOTH bare `:work/id` AND the
  namespaced `:rf.reply/work-id`, and the completion time under BOTH bare
  `:completed-at` AND `:rf.reply/completed-at`. rf2-o6c2jr dropped the bare
  duplicates and made the namespaced spelling canonical.

  This lint keeps the drop honest. It reads the production emitter sources,
  reads each file as data (`:read-cond :allow` so a `.cljc` reader-conditional
  parses), walks EVERY map literal, and asserts no single map carries a fact
  under both its namespaced reply-envelope spelling AND its bare duplicate:

    - a map carrying `:rf.reply/work-id`      MUST NOT also carry `:work/id`
    - a map carrying `:rf.reply/completed-at` MUST NOT also carry `:completed-at`

  ## Scope — reply-envelope rows, not the durable identity

  The bare `:work/id` is still legitimate on its own: it is the durable
  work-ledger identity key ON THE REPLY MAP and on non-reply resource-lifecycle
  trace rows (`:rf.resource/succeeded`, issuance rows, …), which carry it as a
  LONE spelling with no `:rf.reply/work-id` twin. Routing's stale row carries a
  lone bare `:work/id` too. The lint targets only the DUPLICATE: a map that
  carries the namespaced reply-envelope key AND the bare key for the same fact.
  A lone `:work/id` (no `:rf.reply/work-id` in the same map) is fine; a nested
  correlation payload like `:rf.reply/carried {:work/id … :generation …}` is
  fine (that inner map carries no `:rf.reply/work-id`).

  ## Teeth

  `sanity-a-planted-duplicate-is-caught` plants a map carrying both spellings
  and asserts the detector flags it — so a future emitter re-introducing the
  bare duplicate FAILS here, not silently."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [re-frame.mcp-conformance.fixtures :as fx]))

;; ---------------------------------------------------------------------------
;; The one-name-per-fact key pairs. Each pair is
;; [canonical-namespaced-key bare-duplicate-key]: a map carrying the FIRST
;; MUST NOT also carry the SECOND (rf2-o6c2jr).
;; ---------------------------------------------------------------------------

(def ^:private duplicate-key-pairs
  [[:rf.reply/work-id      :work/id]
   [:rf.reply/completed-at :completed-at]])

;; ---------------------------------------------------------------------------
;; The production emitter source files that stamp the additive `:rf.reply/*`
;; reply-envelope vocabulary onto trace rows. Any file that emits a
;; reply-envelope trace row belongs here so a re-introduced bare duplicate is
;; swept. (Resource / mutation stale-suppression route through the shared
;; `reply_handlers.cljc`; its callers pass the bespoke facts, so the caller
;; files are covered too.)
;; ---------------------------------------------------------------------------

(def ^:private emitter-source-files
  ["implementation/http/src/re_frame/http/registry.cljc"
   "implementation/http/src/re_frame/http/transport.cljc"
   "implementation/machines/src/re_frame/machines/lifecycle_fx/finalize.cljc"
   "implementation/machines/src/re_frame/machines/lifecycle_fx/join.cljc"
   "implementation/machines/src/re_frame/machines/lifecycle_fx/traces.cljc"
   "implementation/machines/src/re_frame/machines/timer.cljc"
   "implementation/machines/src/re_frame/machines/transition.cljc"
   "implementation/resources/src/re_frame/resources/events.cljc"
   "implementation/resources/src/re_frame/resources/mutation_events.cljc"
   "implementation/resources/src/re_frame/resources/reply_handlers.cljc"])

;; ---------------------------------------------------------------------------
;; Read a `.cljc` / `.clj` source file as a sequence of top-level forms.
;; `:read-cond :allow` keeps reader-conditionals as data rather than choosing
;; a platform branch; `:eof ::eof` terminates the read loop cleanly. We swallow
;; NOTHING — a read error means a malformed source file and MUST surface.
;; ---------------------------------------------------------------------------

(defn- read-all-forms [source-text]
  (let [rdr (java.io.PushbackReader. (java.io.StringReader. source-text))
        eof ::eof
        opts {:eof eof :read-cond :allow}]
    (loop [forms []]
      (let [form (read opts rdr)]
        (if (= form eof)
          forms
          (recur (conj forms form)))))))

;; ---------------------------------------------------------------------------
;; Walk every sub-form of the parsed source; collect the map literals that
;; carry BOTH a canonical namespaced reply-envelope key AND its bare duplicate.
;; A hit is [canonical-key bare-key the-offending-map].
;; ---------------------------------------------------------------------------

(defn- duplicate-hits [forms]
  (let [hits (volatile! [])]
    (walk/postwalk
      (fn [node]
        (when (map? node)
          (doseq [[canonical bare] duplicate-key-pairs]
            (when (and (contains? node canonical)
                       (contains? node bare))
              (vswap! hits conj [canonical bare node]))))
        node)
      forms)
    @hits))

;; ---------------------------------------------------------------------------
;; (1) THE gate — no production emitter map carries a fact under two keys.
;; ---------------------------------------------------------------------------

(deftest no-trace-row-carries-one-fact-under-two-keys
  (testing "no production reply-envelope emitter map carries a fact under both
            its canonical :rf.reply/* spelling AND its bare duplicate
            (rf2-o6c2jr — Conventions §one name per fact)"
    (doseq [rel-path emitter-source-files]
      (let [forms (read-all-forms (fx/read-source rel-path))
            hits  (duplicate-hits forms)]
        (is (empty? hits)
            (str rel-path " contains " (count hits) " map(s) carrying a fact "
                 "under two keys — the bare duplicate MUST be dropped "
                 "(rf2-o6c2jr, Conventions §one name per fact):\n"
                 (str/join
                   "\n"
                   (for [[canonical bare _m] hits]
                     (str "  - a map carries BOTH " canonical " and its bare "
                          "duplicate " bare)))))))))

;; ---------------------------------------------------------------------------
;; (2) Teeth — the detector flags a PLANTED duplicate. Without this a broken
;;     detector (e.g. a walk that never descends) would pass (1) vacuously.
;; ---------------------------------------------------------------------------

(deftest sanity-a-planted-duplicate-is-caught
  (testing "the duplicate detector flags a map that carries both spellings —
            proving (1) has teeth"
    (let [planted '[(trace/emit! :rf.event :rf.some/completed
                                 {:work/kind :http
                                  :rf.reply/work-id     work-id
                                  :work/id              work-id
                                  :rf.reply/completed-at t
                                  :completed-at          t})]
          hits    (duplicate-hits planted)]
      (is (= 2 (count hits))
          "both the work-id AND completed-at duplicates are detected")
      (is (= #{[:rf.reply/work-id :work/id]
               [:rf.reply/completed-at :completed-at]}
             (into #{} (map (fn [[c b _]] [c b])) hits))
          "the detector names both offending key pairs")))
  (testing "a LONE bare :work/id (no :rf.reply/work-id twin) is NOT flagged —
            the durable identity / resource-lifecycle spelling is legitimate"
    (let [lone '[(trace/emit! :rf.event :rf.resource/succeeded
                              {:work/id work-id :generation 1 :status :completed})]]
      (is (empty? (duplicate-hits lone))
          "a lone bare :work/id is not a one-name-per-fact violation")))
  (testing "a nested correlation payload carrying :work/id under
            :rf.reply/carried is NOT flagged (the inner map carries no
            :rf.reply/work-id twin)"
    (let [nested '[(trace/emit! :rf.event :rf.some/stale-suppressed
                               {:rf.reply/work-id work-id
                                :rf.reply/carried {:work/id work-id :generation 1}
                                :rf.reply/current {:work/id other :generation 2}})]]
      (is (empty? (duplicate-hits nested))
          "the carried/current correlation gate legitimately nests :work/id"))))
