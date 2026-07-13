(ns re-frame.ui.compiler-build-hook-jvm-test
  "The thin shadow `:build-hooks` ADAPTER (rf2-phlhfd) driven through its
  real `hook` fn with synthetic shadow build-states.

  The authority + its pass-boundary primitives are proven in
  re-frame.ui.compiler-build-jvm-test; this suite proves the ADAPTER wires
  the shadow lifecycle stages to those primitives correctly, and that the
  ONE behaviour a hook adds — INCREMENTAL DELETED-SOURCE EVICTION in a live
  watch daemon — actually fires through the real begin -> finish path.

  The adversarial cruces:

    - DELETED-SOURCE EVICTION: a source that declared a view in pass 1 and
      whose FILE is deleted before pass 2 (absent from `:build-sources`)
      gets its registration EVICTED after pass 2's `:compile-finish` —
      isolation alone (df9873) can NOT detect this; the pass boundary can.
    - NO FALSE EVICTION: an incremental recompile of ONE source keeps every
      other LIVE member's registration — macro silence (a cache hit) is not
      a deletion; authoritative `:build-sources` membership drives eviction.
    - PRE-FINISH ID TRANSFER: a deleted/renamed namespace's committed view id
      does not block the new authoritative member that claims it before finish;
      if both namespaces remain members the duplicate still fails loud.
    - FAILED PASS keeps last-known-good: a compile that throws runs no
      `:compile-finish`; the doomed pass's partial staging is discarded at
      the next `:compile-prepare` (begin-build!'s discard-on-open), and the
      committed last-known-good is republished — no half-commit. The
      `abort-build!` primitive restores it explicitly too.
    - REPL DOES NOT TRIP A WIPE: a REPL contribution opens no pass (hooks
      never fire on REPL eval); a subsequent stray `:compile-finish` whose
      member set omits the REPL ns is a no-op — the `pass-open?` guard skips
      finish, so the REPL upsert survives."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler :as compiler]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.build-hook :as build-hook]))

;; Each test starts from a clean authority.
(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

;; ---------------------------------------------------------------------------
;; Harness — drive the REAL `build-hook/hook` fn with synthetic shadow
;; build-states. A shadow build-state carries `:shadow.build/build-id`,
;; `:shadow.build/stage`, `:build-sources`, and `:sources` at BOTH lifecycle
;; stages; Shadow resolves the graph before :compile-prepare, and the hook reads
;; exactly those keys. Using the declaring ns-sym as its own opaque resource-id
;; keeps the fixtures minimal.
;; ---------------------------------------------------------------------------

(defn- graph-state
  [build-id stage member-nss]
  {:shadow.build/build-id build-id
   :shadow.build/stage    stage
   :build-sources         (vec member-nss)
   :sources               (into {} (map (fn [n] [n {:ns n :provides #{n}}]))
                                member-nss)})

(defn- prepare!
  "Fire `:compile-prepare` with the already-resolved authoritative graph."
  [build-id member-nss]
  (build-hook/hook (graph-state build-id :compile-prepare member-nss)))

(defn- finish!
  "Fire the `:compile-finish` hook for `build-id` with `member-nss` as the
  authoritative build graph (`:build-sources`) — commit + evict-deleted."
  [build-id member-nss]
  (build-hook/hook (graph-state build-id :compile-finish member-nss)))

(defn- declare-view!
  "A source `ns-sym` contributing one view digest to the ambient build —
  the shape a `defview` macroexpansion produces (staged while a pass is
  open, upserted otherwise)."
  [build-id ns-sym view-id fp]
  (binding [build/*build-id* build-id]
    (build/contribute! build/views ns-sym view-id fp)))

(defn- declare-explicit-view!
  "Drive the real defview compiler contribution under the hook-opened pass."
  [build-id ns-sym vname view-id template]
  (binding [build/*build-id* build-id
            *ns* (create-ns ns-sym)]
    (compiler/defview*
     (with-meta (list 'defview vname {:id view-id} [] template) {:line 1})
     {} vname (list {:id view-id} [] template))))

(defn- views-of [build-id]
  (build/aggregate build/views build-id))

;; ---------------------------------------------------------------------------
;; The hook returns a valid build-state (shadow rejects a hook otherwise)
;; ---------------------------------------------------------------------------

(deftest hook-returns-the-build-state-unchanged
  (let [prep (graph-state :node-test-ui :compile-prepare '#{app.a})]
    (is (identical? prep (build-hook/hook prep))
        "the hook side-effects into the authority and returns the state it was given"))
  (let [fin {:shadow.build/build-id :node-test-ui
             :shadow.build/stage    :compile-finish
             :build-sources         []
             :sources               {}}]
    (is (identical? fin (build-hook/hook fin)))))

(deftest hook-declares-the-two-lifecycle-stages
  (is (= #{:compile-prepare :compile-finish}
         (:shadow.build/stages (meta #'build-hook/hook)))
      "shadow invokes the hook at exactly the pass-boundary stages"))

;; ---------------------------------------------------------------------------
;; THE HEADLINE — incremental deleted-source eviction in a watch daemon
;; ---------------------------------------------------------------------------

(deftest hook-evicts-a-deleted-source-on-compile-finish
  (let [bid :node-test-ui]
    ;; pass 1: app.a and app.b both declare a view; both are graph members.
    (prepare! bid '#{app.a app.b})
    (declare-view! bid 'app.a :app.a/view :fp-a)
    (declare-view! bid 'app.b :app.b/view :fp-b)
    (finish! bid '#{app.a app.b})
    (is (= {:app.a/view :fp-a :app.b/view :fp-b} (views-of bid))
        "both survive pass 1's finish")

    ;; pass 2: app.b's FILE is deleted → it is ABSENT from :build-sources and
    ;; never re-declares (macro silence). app.a recompiles unchanged.
    (prepare! bid '#{app.a})
    (declare-view! bid 'app.a :app.a/view :fp-a)
    (finish! bid '#{app.a})
    (is (= {:app.a/view :fp-a} (views-of bid))
        "the deleted app.b's registration is EVICTED after compile-finish — the ONE thing the hook adds")))

;; ---------------------------------------------------------------------------
;; NO FALSE EVICTION — an incremental recompile keeps surviving declarations
;; ---------------------------------------------------------------------------

(deftest hook-incremental-recompile-keeps-untouched-live-members
  (let [bid :node-test-ui]
    (prepare! bid '#{app.a app.b})
    (declare-view! bid 'app.a :app.a/view :fp-a)
    (declare-view! bid 'app.b :app.b/view :fp-b)
    (finish! bid '#{app.a app.b})

    ;; incremental: ONLY app.a recompiles (app.b is a cache hit → no
    ;; re-macroexpansion), but app.b is STILL a graph member.
    (prepare! bid '#{app.a app.b})
    (declare-view! bid 'app.a :app.a/view :fp-a2)
    (finish! bid '#{app.a app.b})
    (is (= {:app.a/view :fp-a2 :app.b/view :fp-b} (views-of bid))
        "app.b survives — macro silence is not deletion; only app.a's digest changed")))

;; ---------------------------------------------------------------------------
;; FAILED PASS keeps last-known-good (no :compile-finish runs on failure)
;; ---------------------------------------------------------------------------

(deftest hook-failed-pass-converges-to-last-known-good
  (let [bid :node-test-ui]
    ;; a good pass
    (prepare! bid '#{app.a})
    (declare-view! bid 'app.a :app.a/view :good)
    (finish! bid '#{app.a})
    (is (= {:app.a/view :good} (views-of bid)))

    ;; a DOOMED pass: prepare + a partial contribution, then compile-sources
    ;; throws so the hook's :compile-finish NEVER runs.
    (prepare! bid '#{app.a})
    (declare-view! bid 'app.a :app.a/view :bad)
    ;; ... exception; finish! deliberately NOT called ...

    ;; the NEXT pass's :compile-prepare discards the doomed staging.
    (prepare! bid '#{app.a})
    (declare-view! bid 'app.a :app.a/view :good)
    (finish! bid '#{app.a})
    (is (= {:app.a/view :good} (views-of bid))
        "the doomed pass's partial was discarded at the next prepare; the retry converges — no half-commit")))

(deftest explicit-abort-restores-last-known-good
  ;; The abort-build! primitive the out-of-band failure path would call:
  ;; discard staging, republish committed last-known-good, do not half-commit.
  (let [bid :node-test-ui]
    (prepare! bid '#{app.a})
    (declare-view! bid 'app.a :app.a/view :good)
    (finish! bid '#{app.a})

    (prepare! bid '#{app.a app.b})
    (declare-view! bid 'app.a :app.a/view :bad)
    (declare-view! bid 'app.b :app.b/view :partial)
    (is (= {:app.a/view :bad :app.b/view :partial} (views-of bid))
        "the doomed staging is visible mid-pass")
    (build/abort-build! bid)
    (is (= {:app.a/view :good} (views-of bid))
        "abort discards the partial + republishes last-known-good — the aborted pass does not half-commit")))

;; ---------------------------------------------------------------------------
;; REPL does not trip a wipe — hooks never fire on REPL eval, and even a
;; stray finish is skipped by the pass-open? guard
;; ---------------------------------------------------------------------------

(deftest repl-eval-without-a-pass-is-not-wiped-by-a-stray-finish
  (let [bid :node-test-ui]
    ;; a REPL form eval: shadow's REPL never runs the build-hook pipeline, so
    ;; NO prepare opens a pass — the contribution UPSERTS straight into
    ;; committed (the ruled REPL posture, build.cljc).
    (declare-view! bid 'app.repl :app.repl/view :r1)
    (is (false? (build/pass-open? bid)) "a REPL contribution opens no pass")
    (is (= {:app.repl/view :r1} (views-of bid)))

    ;; a stray :compile-finish whose member set OMITS app.repl must NOT evict
    ;; it: with no pass open, the guard skips finish-build! entirely.
    (finish! bid '#{app.other})
    (is (= {:app.repl/view :r1} (views-of bid))
        "the pass-open? guard skips the stray finish — the REPL upsert survives, no wipe")))

;; ---------------------------------------------------------------------------
;; MULTI-BUILD — the hook keys per build-id (df9873 isolation, through the hook)
;; ---------------------------------------------------------------------------

(deftest hook-keeps-concurrent-builds-isolated
  ;; two builds' passes are open at once (the daemon compiles both); each
  ;; hook keys its own slice, and a deletion in one build never touches the
  ;; other's registrations.
  (prepare! :node-test-ui '#{app.a})
  (prepare! :ui-bench '#{app.a})
  (declare-view! :node-test-ui 'app.a :app.a/view :fp-ui)
  (declare-view! :ui-bench     'app.a :app.a/view :fp-bench)
  (finish! :node-test-ui '#{app.a})
  (finish! :ui-bench     '#{app.a})
  (is (= {:app.a/view :fp-ui}    (views-of :node-test-ui)))
  (is (= {:app.a/view :fp-bench} (views-of :ui-bench)))

  ;; app.a's file is deleted from :node-test-ui only; :ui-bench keeps it.
  (prepare! :node-test-ui '#{})
  (finish! :node-test-ui '#{})
  (is (= {} (views-of :node-test-ui)) "the deletion evicted app.a in :node-test-ui")
  (is (= {:app.a/view :fp-bench} (views-of :ui-bench))
      "the :ui-bench build is untouched — per-build isolation holds through the hook"))

;; ---------------------------------------------------------------------------
;; A deleted/renamed namespace may hand its stable explicit id to a new member
;; BEFORE finish evicts the stale committed source. Prepare's resolved graph is
;; the authority that distinguishes this from a live duplicate.
;; ---------------------------------------------------------------------------

(deftest hook-admits-id-transfer-from-a-nonmember-source
  (let [bid :node-test-ui]
    (prepare! bid '#{app.old})
    (declare-explicit-view! bid 'app.old 'card :shared/card [:div "old"])
    (finish! bid '#{app.old})

    ;; The next resolved graph has replaced app.old with app.new. app.old is
    ;; still committed until finish, but it is no longer authoritative.
    (prepare! bid '#{app.new})
    (is (some? (declare-explicit-view! bid 'app.new 'card
                                       :shared/card [:div "new"])))
    (let [new-digest (get (views-of bid) :shared/card)]
      (finish! bid '#{app.new})
      (is (= {:shared/card new-digest} (views-of bid))
          "finish evicted app.old and committed only app.new's row"))))

(deftest hook-live-member-collision-rejects-and-retry-converges
  (let [bid :node-test-ui]
    (prepare! bid '#{app.old})
    (declare-explicit-view! bid 'app.old 'card :shared/card [:div "old"])
    (finish! bid '#{app.old})
    (let [last-known-good (build/committed-aggregate build/views bid)]
      ;; Both sources remain authoritative: this is a genuine collision.
      (prepare! bid '#{app.old app.new})
      (let [ex (try
                 (declare-explicit-view! bid 'app.new 'card
                                         :shared/card [:div "new"])
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :rf.ui.compile/bad-view-id
               (:rf.ui.compile/error (ex-data ex)))))
      (is (= last-known-good (build/committed-aggregate build/views bid))
          "the rejected pass preserves committed last-known-good")

      ;; No finish ran after rejection. The retry's prepare discards staging,
      ;; and the now-authoritative replacement graph converges without reset.
      (prepare! bid '#{app.new})
      (is (some? (declare-explicit-view! bid 'app.new 'card
                                         :shared/card [:div "new"])))
      (let [new-digest (get (views-of bid) :shared/card)]
        (finish! bid '#{app.new})
        (is (= {:shared/card new-digest} (views-of bid)))
        (is (not= last-known-good (build/committed-aggregate build/views bid))
            "the retry advances from old last-known-good to app.new")))))
