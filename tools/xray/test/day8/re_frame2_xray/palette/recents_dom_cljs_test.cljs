(ns day8.re-frame2-xray.palette.recents-dom-cljs-test
  "Browser-lane half of the palette recents persistence tests.

  WHY A SEPARATE NAMESPACE. The sibling
  `day8.re-frame2-xray.palette.recents-cljs-test` holds the pure `record`
  / `sanitise` algebra, which needs no host storage. The `save!` / `load`
  round-trip needs a real `window.localStorage`, and only a namespace
  ending `-dom-cljs-test` is ever loaded by the `:browser-test` build,
  whose `:ns-regexp` is `.*-dom-cljs-test$`. In the sibling file these
  rows would execute in NEITHER lane: skipped under `:node-test` for want
  of storage, and never loaded by `:browser-test` at all.

  THE ROWS ARE GUARDED, BECAUSE THIS FILE RUNS ON BOTH LANES. `:node-test`'s
  `:ns-regexp` is `cljs-test$` — a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does. So the node
  build loads this namespace too, and the overlap is deliberate:
  `implementation/shadow-cljs.edn` records above `:browser-test` that the
  DOM-tagged files run on BOTH targets so their cross-runtime asserts
  keep firing in Node. A row here therefore runs on the browser lane AND
  the node one. `ls/available?` is what keeps the node run inert —
  without it, node would execute an unguarded round-trip against storage
  it does not have and `load` would return `[]`.

  THE SKIP BRANCH ASSERTS RATHER THAN VANISHING. A bare `(when ...)` body
  would leave the node lane holding a deftest with ZERO assertions — a
  hollow row that reports a pass while checking nothing. The marker row
  keeps the skip visible in the node summary. Same shape as the
  `browser?` branches in
  `day8.re-frame2-xray.palette.empty-row-frame-context-dom-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [day8.re-frame2-xray.local-storage :as ls]
            [day8.re-frame2-xray.palette.recents :as recents]))

(use-fixtures :each
  {:before (fn [] (recents/clear!))
   :after  (fn [] (recents/clear!))})

(deftest save-load-round-trip
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (recents/save! [:foo :bar])
      (is (= [:foo :bar] (recents/load))
          "browser-backed round-trip preserves the recents vector
           (most-recent-first, capped at max-recents)"))))

(deftest save-caps-at-max
  (if-not (ls/available?)
    (is true "skipped: no localStorage (node lane — see ns docstring)")
    (do
      (recents/save! [:a :b :c :d :e])
      (let [loaded (recents/load)]
        ;; `=` rather than `<=`: a silently no-op write (swallowed quota
        ;; or SecurityError — see the `local-storage` seam) leaves `load`
        ;; returning `[]`, and `<=` passes on that. The cap is exact —
        ;; five saved, `max-recents` kept.
        (is (= recents/max-recents (count loaded))
            "save! caps the persisted list at max-recents")))))
