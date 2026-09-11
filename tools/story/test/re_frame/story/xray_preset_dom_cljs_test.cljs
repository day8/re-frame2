(ns re-frame.story.xray-preset-dom-cljs-test
  "Browser-lane half of the Xray-preset cross-host keybinding bridge
  (rf2-ycrt2), promoted out of `re-frame.story.xray-preset-cljs-test`
  under rf2-r51p.

  ## What only a real host can answer

  `wire-cross-host!` declares intent by flipping Xray's
  `:rf.xray/keybinding-enabled?` slot, and closes the runtime gap by
  driving `keybinding/detach!` so the listener Xray's preload installed
  under the default-true posture is actually removed. The slot flip is
  plain atom arithmetic and stays on the node lane, where the sibling
  namespace asserts it. The listener removal is not: both
  `keybinding/attach!` and `keybinding/detach!` open with
  `(exists? js/document)` and return without touching their sentinel
  when there is no document, so on `:node-test` nothing can ever attach.

  ## The false green this namespace exists to remove

  The row below used to live in the sibling `-cljs-test` namespace with
  its `attach!` PRECONDITION — and only the precondition — wrapped in
  `(when (exists? js/document) ...)`:

      (when (exists? js/document)
        (xray-keybinding/attach!)
        (is (true? (xray-keybinding/attached?))
            \"precondition: preload-style attach! installed the listener\"))
      ...
      (is (false? (xray-keybinding/attached?))
          \"wire-cross-host! removed the listener\")

  On node the guarded precondition was skipped, so nothing ever
  attached — and the UNGUARDED assertion two lines below it then read
  `(false? false)` and PASSED, reporting to CI on every PR that the
  bridge had removed a listener that had never been installed. A dead
  row hollowing a live one is worse than a dead row: the live one is
  the part that lies.

  ## Lane mechanics (rf2-r51p)

  `:browser-test`'s `:ns-regexp` is `.*-dom-cljs-test$`, so a namespace
  must carry that suffix to reach a real document at all. It does NOT
  follow that this file runs only there: `:node-test`'s `cljs-test$` is
  a suffix match that ALSO matches `-dom-cljs-test`, so both targets
  load this namespace. The `(browser?)` guard is therefore kept rather
  than dropped — it is what makes the row inert under node instead of
  red. Dropping the guard on the way across is how a browser-only row
  ends up failing in node.

  These assertions had never executed anywhere. A failure here is first
  evidence about `keybinding/attach!` / `keybinding/detach!` and the
  bridge that drives them, not a regression introduced by the move."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-xray.config :as xray-config]
            [day8.re-frame2-xray.keybinding :as xray-keybinding]
            [re-frame.story.xray-preset :as rf.story.xray-preset]))

(defn- browser?
  "True when a real `js/document` with an event-listener API is present.
  False under `:node-test`, which loads this namespace too (see the ns
  docstring) and must skip the body rather than fail in it."
  []
  (and (exists? js/document)
       (some? (.-addEventListener js/document))))

(deftest wire-cross-host-clears-attached-listener
  (testing "rf2-ycrt2 — simulate Xray's preload-time attach! under the
            default-true posture, then drive wire-cross-host!: the slot
            reads false (intent) AND the keydown listener is gone
            (runtime). rf2-q7who.1 declared the contract and did not
            close it — the slot flip alone never detaches a listener
            that attach! already installed."
    (when (browser?)
      ;; Normalise the sentinel before the precondition asserts on it.
      ;; `attach!` is a `compare-and-set!` from false, so an already-
      ;; attached listener would make it a silent no-op and the
      ;; precondition would pass on somebody else's listener.
      (xray-keybinding/detach!)
      (xray-config/set-keybinding-enabled! true)
      (try
        (xray-keybinding/attach!)
        (is (true? (xray-keybinding/attached?))
            "precondition: preload-style attach! installed the listener")
        ;; Drive the real bridge. No shims: `disable-keybinding!` and
        ;; `detach-keybinding!` reach Xray's live config / keybinding
        ;; namespaces through declared `:require`s (rf2-r8trk). No shell
        ;; mounts — `wire-cross-host!` never calls `apply-open!`.
        (rf.story.xray-preset/wire-cross-host!)
        (is (false? (xray-config/keybinding-attach-enabled?))
            "wire-cross-host! flipped the slot to false")
        (is (false? (xray-keybinding/attached?))
            "wire-cross-host! removed the listener (rf2-ycrt2 runtime gap closed)")
        (finally
          ;; Restore the baseline so neighbouring namespaces on this
          ;; page see the default posture and no stray listener.
          (xray-config/set-keybinding-enabled! true)
          (xray-keybinding/detach!))))))
