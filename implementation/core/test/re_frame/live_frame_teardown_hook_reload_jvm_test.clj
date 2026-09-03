(ns re-frame.live-frame-teardown-hook-reload-jvm-test
  "rf2-cq0yi (audit of PR #8887) — `:live-frame/on-frame-destroyed!` RE-ARMS
  when `re-frame.live-frame` is hot-reloaded in a process that has already
  constructed a frame.

  ## The defect this pins

  PR #8887 added the provenance release and published it — correctly, as far as
  a FRESH process is concerned — from `install-reprojection!`, the reprojection
  once-body. That body is guarded by `reprojection-installed?`, a `defonce`
  atom, so it survives a namespace reload holding `true` and the body is skipped
  on every reload after the first `make-frame`. A publication sited inside it is
  therefore published exactly once per PROCESS, never once per LOAD.

  In a fresh process that is invisible: the first `make-frame` runs the
  once-body, the key is published, and every existing case in
  `frame_destroy_generation_provenance_cljs_test` passes. It bites on the
  UPGRADE path — a live dev process running pre-#8887 code has the once-flag
  already `true` and the key ABSENT, so reloading `re-frame.live-frame` skipped
  the once-body, never published the key, and `destroy-frame!`'s step-6
  `safe-call-hook!` found nothing to call. Provenance rows then leaked for the
  rest of the process, which is the very leak #8887 set out to fix. It also
  contradicted `re-frame.late-bind`'s stated contract — `hooks` is \"populated by
  the producing namespace at LOAD TIME\", and `set-fn!` invalidates the sticky
  cache precisely so \"hot-reload of an artefact swaps the resolved fn on the
  very next dispatch\".

  The fix publishes the key from a top-level form at ns load instead. The
  registrar registration hook stays in the once-body, because
  `add-registration-hook!` APPENDS and re-running it per reload would accumulate
  duplicates; only the idempotent keyed `set-fn!` moved.

  ## Why the existing cases cannot see this

  Every case in `frame_destroy_generation_provenance_cljs_test` starts from a
  complete registry — the state a fresh process reaches on its first
  `make-frame` — so all of them are green in both the broken and the repaired
  arrangement. The discriminating state is *once-flag already true* AND *this
  one key missing*, which no other case constructs. This file constructs it
  explicitly.

  ## Why this file is JVM-only

  The reproduction needs the producing namespace re-loaded, and
  `(require 'ns :reload)` has no ClojureScript runtime analogue — a fact this
  test tree already relies on in `cofx_cljs_test`, `conformance_corpus_cljs_test`
  and `test-support`'s own docstring. The FIX is a single top-level form in a
  `.cljc` file, so both hosts re-run it identically: shadow-cljs hot reload
  re-evaluates a reloaded namespace's top-level forms exactly as
  `(require … :reload)` does. The mechanism is shared; only the harness that can
  drive it is host-specific."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core       :as rf]
            [re-frame.late-bind  :as rf.late-bind]
            [re-frame.live-frame :as rf.live-frame]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private hook-key :live-frame/on-frame-destroyed!)

(defn- provenance
  "The private generation-provenance table, as a plain map."
  []
  (deref @#'rf.live-frame/frame-generation-pool))

(defn- row?
  "Does the table carry a row for `id`? Key MEMBERSHIP, never `get`: an ordinary
  1-arity frame's recorded pool IS `nil`, so `get` cannot separate a live
  ordinary row from no row at all."
  [id]
  (contains? (provenance) id))

(defn- once-flag []
  (deref @#'rf.live-frame/reprojection-installed?))

(defn- restore-hook!
  "Put `hook-key` back exactly as `before` had it — present with that fn, or
  ABSENT. Surgical rather than a whole-map `reset!` so nothing published by
  anything else during this test is clobbered (the same discipline
  `reprojection_install_race_jvm_test`'s fixture keeps)."
  [before]
  (if-let [f (get before hook-key)]
    (rf.late-bind/set-fn! hook-key f)
    (do (swap! rf.late-bind/hooks dissoc hook-key)
        (rf.late-bind/invalidate-cache! hook-key)))
  nil)

(deftest teardown-hook-re-arms-on-live-frame-reload-rf2-cq0yi
  (testing "rf2-cq0yi: with the reprojection once-flag ALREADY set and only the
            teardown key missing — the exact state of a dev process upgraded
            from pre-#8887 code — reloading `re-frame.live-frame` re-publishes
            `:live-frame/on-frame-destroyed!`, and `destroy-frame!` returns the
            provenance table to its captured baseline"
    (let [hooks-before @rf.late-bind/hooks]
      (try
        ;; ---- a LIVE process: something has already constructed a frame, so
        ;; the reprojection once-body has run and its flag is latched.
        (let [warm (rf/make-frame {:id :cq0yi-reload/warm})]
          (rf/destroy-frame! warm))
        (is (true? (once-flag))
            "PRECONDITION: the `defonce` once-flag is already true — this is
             what any process that has constructed a frame looks like, and it
             is what makes the once-body unreachable from here on")

        ;; ---- the PRE-UPGRADE registry: only the new key is missing.
        ;; Everything else the once-body owns (the registrar hook, the two
        ;; reprojection keys) stays exactly as a pre-#8887 process left it.
        (swap! rf.late-bind/hooks dissoc hook-key)
        (rf.late-bind/invalidate-cache! hook-key)
        (is (nil? (rf.late-bind/get-fn hook-key))
            "control: the teardown key is absent, as it is in a process that
             was running code from before the hook existed")

        ;; ---- the once-body CANNOT repair this. It is skipped behind the
        ;; latched flag, which is the whole defect: a publication sited there
        ;; gets exactly one chance per process and it has already been spent.
        (rf.live-frame/ensure-reprojection-installed!)
        (is (nil? (rf.late-bind/get-fn hook-key))
            "the once-body is a no-op behind the latched flag, so it cannot be
             the thing that re-arms the hook")

        ;; ---- the upgrade itself.
        (require 're-frame.live-frame :reload)
        (is (some? (rf.late-bind/get-fn hook-key))
            "rf2-cq0yi: ns LOAD re-publishes the teardown hook. Pre-fix this
             read nil — the reload skipped the once-body and published
             nothing, so destroy had no release to call")

        ;; ---- and the re-armed hook actually releases.
        (let [id       :cq0yi-reload/subject
              baseline (count (provenance))
              f        (rf/make-frame {:id id})]
          (is (row? id)
              "NON-VACUITY control: `make-frame` recorded a provenance row while
               the frame was live, so the absence asserted below is a RELEASE
               and not a row that was never written")
          (is (= (inc baseline) (count (provenance)))
              "and the table grew by exactly that one row")
          (rf/destroy-frame! f)
          (is (not (row? id))
              "the row was released after the reload — pre-fix it survived for
               the remainder of the process")
          (is (= baseline (count (provenance)))
              "the provenance table is back to its captured baseline"))
        (finally
          (restore-hook! hooks-before))))))
