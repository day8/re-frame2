(ns re-frame.story.xray-preset-dom-cljs-test
  "Browser-lane half of the Xray-preset cross-host keybinding bridge;
  the node-lane half is `re-frame.story.xray-preset-cljs-test`.

  ## What only a real host can answer

  `wire-cross-host!` declares intent by flipping Xray's
  `:rf.xray/keybinding-enabled?` slot, and closes the runtime gap by
  driving `keybinding/detach!` so the listener Xray's preload installed
  under the default-true posture is actually removed. The slot flip is
  plain atom arithmetic and is on the node lane, where the sibling
  namespace asserts it. The listener removal is not: both
  `keybinding/attach!` and `keybinding/detach!` open with
  `(exists? js/document)` and return without touching their sentinel
  when there is no document, so on `:node-test` nothing can ever attach.

  ## Why the listener row runs on a real document

  On the node lane, a row with its `attach!` PRECONDITION — and only the
  precondition — wrapped in `(when (exists? js/document) ...)`:

      (when (exists? js/document)
        (xray-keybinding/attach!)
        (is (true? (xray-keybinding/attached?))
            \"precondition: preload-style attach! installed the listener\"))
      ...
      (is (false? (xray-keybinding/attached?))
          \"wire-cross-host! removed the listener\")

  would skip the guarded precondition, so nothing would ever attach —
  and the UNGUARDED assertion two lines below it would read
  `(false? false)` and PASS, reporting that the bridge had removed a
  listener that had never been installed. A dead row hollowing a live
  one is worse than a dead row: the live one is the part that lies.

  ## Lane mechanics

  `:browser-test`'s `:ns-regexp` is `.*-dom-cljs-test$`, so a namespace
  must carry that suffix to reach a real document at all. It does NOT
  follow that this file runs only there: `:node-test`'s `cljs-test$` is
  a suffix match that ALSO matches `-dom-cljs-test`, so both targets
  load this namespace. The `(browser?)` guard is what makes the row
  inert under node instead of red; without it a browser-only row fails
  in node.

  The guard is spelled `(if-not (browser?) (is true skip-msg) ...)`
  rather than as a bare `(when (browser?) ...)`: under `:node-test` the
  marker assertion fires and the row reports a STATED skip, where a bare
  `when` would leave it passing with zero assertions — on the console
  indistinguishable from a row that ran."
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

(def ^:private skip-msg
  "skipped: no DOM (node lane — see ns docstring)")

(deftest wire-cross-host-clears-attached-listener
  (testing "simulate Xray's preload-time attach! under the
            default-true posture, then drive wire-cross-host!: the slot
            reads false (intent) AND the keydown listener is gone
            (runtime). The slot flip alone never detaches a listener
            that attach! already installed."
    (if-not (browser?)
      (is true skip-msg)
      (do
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
          ;; namespaces through declared `:require`s. No shell
          ;; mounts — `wire-cross-host!` never calls `apply-open!`.
          (rf.story.xray-preset/wire-cross-host!)
          (is (false? (xray-config/keybinding-attach-enabled?))
              "wire-cross-host! flipped the slot to false")
          (is (false? (xray-keybinding/attached?))
              "wire-cross-host! removed the listener")
          (finally
            ;; Restore the baseline so neighbouring namespaces on this
            ;; page see the default posture and no stray listener.
            (xray-config/set-keybinding-enabled! true)
            (xray-keybinding/detach!)))))))
