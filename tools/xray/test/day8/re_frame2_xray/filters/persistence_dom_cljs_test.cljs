(ns day8.re-frame2-xray.filters.persistence-dom-cljs-test
  "Browser-lane half of the filter-persistence tests (rf2-ak4ms),
  promoted out of `day8.re-frame2-xray.filters.persistence-cljs-test`
  under rf2-r51p.

  WHY A SEPARATE NAMESPACE. The sibling keeps the pure `->edn` /
  `<-edn` algebra and the seed/config plumbing, none of which touches
  a host. The `save!` / `load` round-trip, the per-instance storage-key
  isolation and the write-through assertions need a real
  `window.localStorage`, and only a namespace ending `-dom-cljs-test`
  is ever loaded by the `:browser-test` build, whose `:ns-regexp` is
  `.*-dom-cljs-test$`. Sitting in the sibling file these rows executed
  in NEITHER lane: skipped under `:node-test` for want of storage, and
  never loaded by `:browser-test` at all. The file's LOCATION was the
  defect. The guard was not.

  THE SIBLING'S DOCSTRING USED TO CLAIM OTHERWISE, AND IT WAS WRONG.
  It said localStorage exists under `npm run test:cljs` \"via the
  `dom-storage` polyfill the test-support harness installs\". No such
  polyfill is installed and no such package is depended on —
  `implementation/package.json` lists no `dom-storage`, no `jsdom` and
  no `happy-dom` in any dependency list, and the only occurrence of
  the string `dom-storage` anywhere in the tree was that sentence.
  The guard therefore never opened on node. That corrected claim is
  why these rows are here rather than there.

  THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES. `:node-test`'s
  `:ns-regexp` is `cljs-test$` — a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does, so the node
  build loads this namespace too and the overlap is deliberate (see
  the comment above `:browser-test` in
  `implementation/shadow-cljs.edn`). Moving a row here ADDS the browser
  lane; it does not take the row off the node one. `ls/available?` is
  what keeps the node run inert.

  THE SKIP BRANCH ASSERTS RATHER THAN VANISHING, so the node lane never
  holds a deftest with zero assertions — the hollow shape rf2-r51p
  exists to remove.

  These assertions had never executed in ANY lane before this
  namespace existed. A failure here is evidence arriving for the first
  time, not a regression introduced by the move."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.filters :as filters]
            [day8.re-frame2-xray.filters.persistence :as persistence]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; Mirrors the sibling's fixture. The slate matters more here than on
  ;; node: the browser lane runs every namespace on ONE page, so a
  ;; leftover slot would be in storage when the next namespace hydrates.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (persistence/clear!)
                   (config/set-filters-storage-key! nil)
                   (config/set-filter-seed! nil))}))

(defn- xray-setup!
  "Register Xray handlers + the :rf/xray frame, then re-run the filter
  hydration so the seed / localStorage value lifts into the slot.
  Mirrors the sibling's `xray-setup!`."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (filters/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

(defn- frame-dispatch [ev]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync ev)))

;; -------------------------------------------------------------------------
;; save! / load round-trip
;; -------------------------------------------------------------------------

(deftest save-and-load-round-trip
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (let [flt {:in  [{:pattern :auth/*}]
               :out [{:pattern :mouse-move}]}]
      (persistence/clear!)
      (persistence/save! flt)
      (is (= flt (persistence/load))
          "browser-backed round-trip preserves both filter directions"))))

;; -------------------------------------------------------------------------
;; Storage-key override via config (per-instance isolation)
;; -------------------------------------------------------------------------

(deftest custom-storage-key-isolates-per-instance
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (testing "story testbeds set distinct keys so two Xray instances
              do not stomp on each other's pill state"
      ;; Instance A
      (config/set-filters-storage-key! "story.testbed.a.filters")
      (persistence/save! {:in [{:pattern :a}] :out []})
      ;; Precondition, not decoration: without it the `{:in [] :out []}`
      ;; read below passes on a silently no-op storage, where NOTHING
      ;; was ever written and every slot reads empty. Proving A's write
      ;; landed is what makes B's empty read discriminating.
      (is (= {:in [{:pattern :a}] :out []} (persistence/load))
          "precondition: instance A's write really did persist")
      ;; Switch to instance B and confirm an empty load
      (config/set-filters-storage-key! "story.testbed.b.filters")
      (is (= {:in [] :out []} (persistence/load))
          "instance B's slot starts empty even though A wrote")
      (persistence/save! {:in [] :out [{:pattern :b}]})
      ;; Switch back to A and confirm A's value is intact
      (config/set-filters-storage-key! "story.testbed.a.filters")
      (is (= {:in [{:pattern :a}] :out []}
             (persistence/load))
          "instance A's slot survived the B writes")
      ;; Cleanup
      (config/set-filters-storage-key! "story.testbed.a.filters")
      (persistence/clear!)
      (config/set-filters-storage-key! "story.testbed.b.filters")
      (persistence/clear!)
      (config/set-filters-storage-key! nil))))

;; -------------------------------------------------------------------------
;; Hydration on install — localStorage wins over the seed
;; -------------------------------------------------------------------------

(deftest hydration-prefers-localstorage-over-seed
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (persistence/save! {:in  [{:pattern :persisted}]
                          :out []})
      (config/set-filter-seed! {:in [{:pattern :seed}] :out []})
      ;; A fresh registry install rehydrates.
      (registry/reset-for-test!)
      (xray-setup!)
      (is (= [{:pattern :persisted}]
             (:in (frame-sub [:rf.xray/active-filters])))
          "localStorage value wins over the configured seed")
      (persistence/clear!))))

;; -------------------------------------------------------------------------
;; add-filter / remove-filter write through to storage
;; -------------------------------------------------------------------------

(deftest add-filter-persists-to-localstorage
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (persistence/clear!)
      (xray-setup!)
      (frame-dispatch [:rf.xray/add-filter :in {:pattern :auth/*}])
      (is (= {:in [{:pattern :auth/*}] :out []}
             (persistence/load))
          "add-filter writes through to localStorage"))))

(deftest remove-filter-persists-to-localstorage
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (persistence/clear!)
      (xray-setup!)
      (frame-dispatch [:rf.xray/add-filter :out {:pattern :a}])
      (frame-dispatch [:rf.xray/add-filter :out {:pattern :b}])
      (frame-dispatch [:rf.xray/remove-filter :out 0])
      (is (= {:in [] :out [{:pattern :b}]}
             (persistence/load))
          "remove-filter writes through to localStorage"))))
