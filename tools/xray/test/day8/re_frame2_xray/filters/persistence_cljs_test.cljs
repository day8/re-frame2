(ns day8.re-frame2-xray.filters.persistence-cljs-test
  "Host-free tests for the filter persistence layer (rf2-ak4ms): the
  `->edn` / `<-edn` algebra, the `configure!` plumbing and the
  seed-fallback hydration paths.

  THE REAL-STORAGE ROWS LIVE IN THE DOM SIBLING. `save!` / `load`
  round-trips, per-instance storage-key isolation and the
  write-through assertions moved to
  `day8.re-frame2-xray.filters.persistence-dom-cljs-test` under
  rf2-r51p, because only a namespace ending `-dom-cljs-test` is loaded
  by the `:browser-test` build.

  THIS DOCSTRING USED TO CLAIM localStorage EXISTED HERE, AND IT DID
  NOT. The previous wording said localStorage exists under `npm run
  test:cljs` \"via the `dom-storage` polyfill the test-support harness
  installs\". No such polyfill is installed and no such package is
  depended on — `implementation/package.json` lists no `dom-storage`,
  no `jsdom` and no `happy-dom` in any dependency list. The rows those
  words justified were guarded by `(and (exists? js/window)
  (.-localStorage js/window))`, which is FALSE on node, so they
  executed in neither lane until rf2-r51p moved them."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.filters :as filters]
            [day8.re-frame2-xray.filters.persistence :as persistence]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

(use-fixtures :each
  ;; `make-xray-runtime-fixture` (rf2-vj80u8) folds the reset (plain-atom +
  ;; `:all` tier) into one owner; `:post-reset` carries this suite's
  ;; persistence-slate tail.
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (persistence/clear!)
                   (config/set-filters-storage-key! nil)
                   (config/set-filter-seed! nil))}))

(defn- xray-setup!
  "Register Xray handlers + the :rf/xray frame, then re-run the
  filter hydration so the seed / localStorage value lifts into the
  slot. Production runs hydrate in `mount.cljs/ensure-xray-frame!`
  after the same make-frame; tests skip mount so we call hydrate
  directly."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray})
  (filters/hydrate!))

(defn- frame-sub [q]
  (rf/with-frame :rf/xray
    @(rf/subscribe q)))

;; `frame-dispatch` went with the write-through rows to the dom sibling —
;; every remaining test here reads, none dispatches.

;; -------------------------------------------------------------------------
;; (1) ->edn / <-edn round-trip
;; -------------------------------------------------------------------------

(deftest edn-round-trip-preserves-shape
  (let [filters {:in  [{:pattern :auth/*}]
                 :out [{:pattern :mouse-move}
                       {:pattern "/login"}]}]
    (is (= filters
           (persistence/<-edn (persistence/->edn filters))))))

(deftest edn-round-trip-handles-empty
  (let [filters {:in [] :out []}]
    (is (= filters
           (persistence/<-edn (persistence/->edn filters))))))

(deftest from-edn-malformed-falls-back-to-empty
  (is (= {:in [] :out []}
         (persistence/<-edn "this is not edn")))
  (is (= {:in [] :out []}
         (persistence/<-edn "[1 2 3]"))
      "non-map parsed value collapses to default"))

(deftest from-edn-scalar-in-out-degrades-not-throws
  ;; rf2-oa1va6 — a parseable, map-shaped, but corrupt / hand-edited
  ;; payload whose :in / :out value is a NON-seqable scalar must NOT
  ;; throw `(vec 5)` ("5 is not ISeqable") out of `<-edn`, escaping the
  ;; load path into init. The prior guard only rejected non-map / non-
  ;; parseable payloads; a scalar :in / :out slipped past the `map?`
  ;; check and blew up on the `(vec …)` coercion. Each field now
  ;; degrades fail-soft to [] — the documented never-throws-into-init
  ;; contract.
  (is (= {:in [] :out []}
         (persistence/<-edn "{:in 5 :out []}"))
      "scalar :in (a number) degrades to [] instead of throwing ISeqable")
  (is (= {:in [] :out []}
         (persistence/<-edn "{:in :oops :out []}"))
      "scalar :in (a keyword) degrades to []")
  (is (= {:in [] :out []}
         (persistence/<-edn "{:in [] :out true}"))
      "scalar :out (a boolean) degrades to []")
  (is (= {:in [{:pattern :auth/*}] :out []}
         (persistence/<-edn "{:in [{:pattern :auth/*}] :out 9}"))
      "per-field: a good :in survives while a scalar :out degrades to []"))

;; -------------------------------------------------------------------------
;; (2) save! / load round-trip (depends on localStorage being available)
;; -------------------------------------------------------------------------

;; The real-storage rows that lived here — `save-and-load-round-trip`,
;; `custom-storage-key-isolates-per-instance`,
;; `hydration-prefers-localstorage-over-seed`,
;; `add-filter-persists-to-localstorage` and
;; `remove-filter-persists-to-localstorage` — moved to
;; `day8.re-frame2-xray.filters.persistence-dom-cljs-test` under
;; rf2-r51p. Each was wrapped in `(when (and (exists? js/window)
;; (.-localStorage js/window)) ...)`, which is FALSE under `:node-test`,
;; while `:browser-test`'s `.*-dom-cljs-test$` `:ns-regexp` never loaded
;; this file at all — so they executed in NEITHER lane. Their new home
;; ends `-dom-cljs-test`, which BOTH builds select, so the rows now run
;; for real in the browser and stay inert on node behind `ls/available?`.

(deftest load-when-slot-is-empty-returns-defaults
  (persistence/clear!)
  (is (= {:in [] :out []} (persistence/load))))

;; -------------------------------------------------------------------------
;; (3) Storage-key override via config (per-instance isolation)
;; -------------------------------------------------------------------------

;; -------------------------------------------------------------------------
;; (4) configure! plumbs :rf.xray/filters and :rf.xray/filters-storage-key
;; -------------------------------------------------------------------------

(deftest configure-bang-passes-filters-through
  (config/configure! {:rf.xray/filters {:in  [{:pattern :auth/*}]
                                         :out [{:pattern :mouse-move}]}})
  (is (= {:in  [{:pattern :auth/*}]
          :out [{:pattern :mouse-move}]}
         (config/get-filter-seed))))

(deftest configure-bang-passes-storage-key-through
  (config/configure! {:rf.xray/filters-storage-key "myhost.filters"})
  (is (= "myhost.filters" (config/get-filters-storage-key)))
  (is (= "myhost.filters" (persistence/get-storage-key))))

(deftest configure-bang-without-filters-keys-leaves-them-alone
  (config/set-filter-seed! {:in [{:pattern :seeded}] :out []})
  (config/set-filters-storage-key! "myhost.filters")
  (config/configure! {:rf.xray/editor :cursor})
  (is (= {:in [{:pattern :seeded}] :out []}
         (config/get-filter-seed))
      "absent :rf.xray/filters key leaves seed untouched")
  (is (= "myhost.filters" (config/get-filters-storage-key))
      "absent :rf.xray/filters-storage-key leaves key untouched"))

;; -------------------------------------------------------------------------
;; (5) Hydration on install — localStorage wins, seed fills the gap
;; -------------------------------------------------------------------------

(deftest hydration-falls-back-to-seed-when-localstorage-empty
  (persistence/clear!)
  (config/set-filter-seed! {:in  []
                            :out [{:pattern :mouse-move}]})
  (registry/reset-for-test!)
  (xray-setup!)
  (is (= [{:pattern :mouse-move}]
         (:out (frame-sub [:rf.xray/active-filters])))
      "seed fills the empty-slot gap"))

(deftest hydration-falls-back-to-empty-when-no-source
  (persistence/clear!)
  (config/set-filter-seed! nil)
  (registry/reset-for-test!)
  (xray-setup!)
  (is (= {:in [] :out []}
         (frame-sub [:rf.xray/active-filters]))
      "no localStorage + no seed → empty (first-session honesty)"))

;; -------------------------------------------------------------------------
;; (6) add-filter / remove-filter persist (round-trip via the fx)
;; -------------------------------------------------------------------------

