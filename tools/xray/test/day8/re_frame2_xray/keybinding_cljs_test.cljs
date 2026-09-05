(ns day8.re-frame2-xray.keybinding-cljs-test
  "Tests for Xray's global keydown listener (rf2-jbhm5; source rf2-otcbz
  audit recommendation #5).

  Two contract surfaces under test:

  1. **Key predicates.** `xray-toggle-key?` (Ctrl+Shift+C) and
     `palette-toggle-key?` (Cmd/Ctrl+K) are pure functions over a
     KeyboardEvent's surface. They are private to the keybinding ns;
     tests reach in via `#'` var access. Both predicates check their
     key via both `.key` and `.code` (the latter is the IME-active
     fallback), and reject extra modifiers (meta, alt) — important
     so the macOS Cmd+Shift+C dev-tools shortcut never collides with
     Xray's toggle.

  2. **Idempotency sentinel.** `attach!` holds a private `defonce` atom
     (`attached?`) that survives shadow-cljs `:after-load` reloads. The
     contract: calling `attach!` twice attaches one listener; calling
     `detach!` flips the sentinel back so a subsequent `attach!`
     installs again. We assert the sentinel through the public
     `attached?` read-accessor and count listener attachments on a
     stubbed `js/document`.

  ## Why these tests run on node-test (not browser-test)

  The predicates are pure CLJS — synthetic `js-obj` events drive them
  with zero DOM dependency. The `attach!` / `detach!` flow needs
  *something* exposing `addEventListener` / `removeEventListener`; node-
  test has no `js/document` of its own, so we install a hand-rolled
  stub for the duration of the test and restore the absent binding in a
  `finally`. That keeps the suite fast and host-portable — the browser-
  level keydown-dispatch story lives in the Playwright lane (rf2-s2bhn)
  on a real document."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.defaults :as defaults]
            [day8.re-frame2-xray.keybinding :as keybinding]
            [day8.re-frame2-xray.mount :as mount]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- helpers -------------------------------------------------------------

(defn- mk-event
  "Build a synthetic KeyboardEvent-shaped JS object the predicates can
  read via `.key`, `.code`, `.ctrlKey`, `.shiftKey`, `.metaKey`,
  `.altKey`. Missing modifier keys default to `false` (matches the DOM
  default for KeyboardEventInit), missing key/code default to nil."
  ([opts]
   (let [{:keys [key code ctrl? shift? meta? alt?]
          :or   {ctrl? false shift? false meta? false alt? false}} opts]
     (js-obj "key"      key
             "code"     code
             "ctrlKey"  ctrl?
             "shiftKey" shift?
             "metaKey"  meta?
             "altKey"   alt?))))

(defn- xray-toggle-key?
  "Reach the private predicate via var access."
  [event]
  (#'keybinding/xray-toggle-key? event))

(defn- palette-toggle-key?
  "rf2-wm7z4 — the Cmd/Ctrl+K command-palette predicate."
  [event]
  (#'keybinding/palette-toggle-key? event))

(defn- spine-key-id
  "rf2-adve5 — the spine-binding predicate (Space / L / j / k / Shift+G /
  `,` / s). `keybinding/spine-key-id`'s `cond` arms are the roster; read
  them rather than any count restated here (rf2-v0rw).
  Returns the spine event id or nil."
  [event]
  (#'keybinding/spine-key-id event))

(defn- mode-toggle-key?
  "rf2-o5f5f.1 — the Cmd/Ctrl+Shift+M predicate that drives the
  Dynamic ↔ Static mode toggle."
  [event]
  (#'keybinding/mode-toggle-key? event))

;; ---- stub `js/document` --------------------------------------------------
;;
;; The `attach!` / `detach!` helpers are guarded by `(exists?
;; js/document)`, so under bare node-test they would silently no-op and
;; the sentinel would never flip. We install a minimal stub for the
;; duration of `with-stub-document`; counters expose the listener-
;; attach state so we can assert the idempotency contract directly
;; against `addEventListener` invocation counts (belt-and-braces beside
;; the `attached?` accessor).

(defn- mk-stub-document []
  (let [listeners (atom [])]
    {:doc       (js-obj "addEventListener"
                        (fn [type handler use-capture]
                          (swap! listeners conj {:type        type
                                                 :handler     handler
                                                 :use-capture use-capture}))
                        "removeEventListener"
                        (fn [type handler use-capture]
                          (swap! listeners
                                 (fn [xs]
                                   (vec (remove (fn [x]
                                                  (and (= type (:type x))
                                                       (identical? handler (:handler x))
                                                       (= use-capture (:use-capture x))))
                                                xs))))))
     :listeners listeners}))

(defn- can-stub-js-document?
  "True iff the running host lets us write `js/document` via `set!`.

  In node-test there is no real `document` global; `(set! js/document
  ...)` installs a fresh slot on `goog.global` and subsequent reads
  see the new value. In a real browser `window.document` is a non-
  configurable read-only WebIDL accessor — the JS engine silently
  drops the assignment (or throws in strict mode), so subsequent
  reads still return the genuine `HTMLDocument`. Test code that
  installs a stub via `set! js/document` and asserts against that
  stub silently fails in that case.

  Per rf2-higwg: detect the host by writing-and-checking; if the
  write didn't take effect we're inside a real browser
  (`:browser-test` build under Playwright) and the keybinding /
  mount tests' stub-driven contracts can't be exercised. The
  predicate gates `with-stub-document*` so the deftest bodies no-op
  on that host. The contracts are still proven on the node-test
  build where stubbing works."
  []
  (let [marker (js-obj "rf2-higwg-marker" true)
        prior  (when (exists? js/document) js/document)]
    (set! js/document marker)
    (let [installed? (identical? js/document marker)]
      (if prior
        (set! js/document prior)
        (when installed?
          (js-delete js/goog.global "document")))
      installed?)))

(defn- with-stub-document* [f]
  ;; `set!` on `js/document` installs at goog.global; restoring nil
  ;; afterwards puts the binding back to the node-test baseline (absent).
  ;;
  ;; Per rf2-higwg: in `:browser-test` the host's `window.document` is
  ;; non-configurable and the `set!` silently no-ops — the stub never
  ;; takes effect and `attach!`'s `addEventListener` lands on the real
  ;; document. Skip the body cleanly in that host; the same contracts
  ;; run on node-test where the stub does install.
  (when (can-stub-js-document?)
    (let [{:keys [doc listeners]} (mk-stub-document)
          had-doc?                (exists? js/document)
          prior                   (when had-doc? js/document)]
      (set! js/document doc)
      (try
        (f {:listeners listeners})
        (finally
          ;; Make sure no leftover listener / sentinel from a partial
          ;; test bleeds across runs. The sentinel is the contract we're
          ;; testing — but if a deftest threw mid-way the global state
          ;; would survive, so we hard-reset both here.
          (try (keybinding/detach!) (catch :default _))
          (if had-doc?
            (set! js/document prior)
            (js-delete js/goog.global "document")))))))

(defn- with-stub-document [f]
  (with-stub-document* f))

;; ---- fixtures -----------------------------------------------------------
;;
;; The `attached?` defonce survives across tests in the same JVM /
;; node session, so each test that touches it must end with the
;; sentinel back at `false`. The `:after` callback below provides the
;; safety net so a failing `attach!` test doesn't poison neighbours.

(defn- reset-sentinel! []
  ;; Belt-and-braces — `with-stub-document` already calls `detach!` in
  ;; its `finally`, but tests that don't go through the stub still
  ;; need a clean baseline.
  (when (exists? js/document)
    (keybinding/detach!)))

(use-fixtures :each {:before reset-sentinel!
                     :after  reset-sentinel!})

;; ---- (1) xray-toggle-key? truth table -----------------------------------

(deftest xray-toggle-key-matches-ctrl-shift-c
  (testing "Ctrl+Shift+C is the canonical positive case — uppercase `key`"
    (is (true? (xray-toggle-key?
                 (mk-event {:key "C" :ctrl? true :shift? true})))))
  (testing "lowercase `key` (most browsers report uppercase when Shift is
            held; the predicate accepts either to stay defensive against
            host quirks)"
    (is (true? (xray-toggle-key?
                 (mk-event {:key "c" :ctrl? true :shift? true})))))
  (testing "`code` fallback — IME-active contexts populate only .code"
    (is (true? (xray-toggle-key?
                 (mk-event {:code "KeyC" :ctrl? true :shift? true}))))))

(deftest xray-toggle-key-rejects-missing-modifiers
  (testing "no modifiers"
    (is (false? (xray-toggle-key? (mk-event {:key "C"})))))
  (testing "Ctrl only — Shift missing"
    (is (false? (xray-toggle-key?
                  (mk-event {:key "C" :ctrl? true})))))
  (testing "Shift only — Ctrl missing"
    (is (false? (xray-toggle-key?
                  (mk-event {:key "C" :shift? true}))))))

(deftest xray-toggle-key-rejects-extra-modifiers
  (testing "Ctrl+Shift+Cmd+C — meta blocks (avoids the macOS dev-tools
            Cmd+Shift+C collision the source docstring calls out)"
    (is (false? (xray-toggle-key?
                  (mk-event {:key "C" :ctrl? true :shift? true :meta? true})))))
  (testing "Ctrl+Shift+Alt+C — alt blocks"
    (is (false? (xray-toggle-key?
                  (mk-event {:key "C" :ctrl? true :shift? true :alt? true})))))
  (testing "all modifiers held — both meta + alt block"
    (is (false? (xray-toggle-key?
                  (mk-event {:key   "C"
                             :ctrl? true :shift? true
                             :meta? true :alt? true}))))))

(deftest xray-toggle-key-rejects-wrong-key
  (testing "wrong key letter, right modifiers"
    (is (false? (xray-toggle-key?
                  (mk-event {:key "D" :ctrl? true :shift? true})))))
  (testing "wrong `code`, right modifiers"
    (is (false? (xray-toggle-key?
                  (mk-event {:code "KeyD" :ctrl? true :shift? true})))))
  (testing "C-shaped key but on a different code — only `key` matches"
    ;; The predicate is permissive: matching on `key` is enough. This
    ;; guards against the predicate being tightened to AND both fields.
    (is (true? (xray-toggle-key?
                 (mk-event {:key "C" :code "Digit3"
                            :ctrl? true :shift? true}))))))

(deftest xray-toggle-key-defensive-nil-fields
  (testing "missing key + missing code → no match (defensive)"
    (is (false? (xray-toggle-key?
                  (mk-event {:ctrl? true :shift? true}))))))

;; ---- (2) toggles are mutually exclusive ----------------------------------

(deftest predicates-are-mutually-exclusive
  (testing "no synthetic event satisfies more than one predicate at once —
            mutual exclusivity matters because `handle-keydown` uses
            `cond` and a multi-match case would silently route to the
            first arm and drop the others"
    (doseq [event [(mk-event {:key "C" :ctrl? true :shift? true})
                   (mk-event {:code "KeyC" :ctrl? true :shift? true})
                   (mk-event {:key "k" :ctrl? true})
                   (mk-event {:key "k" :meta? true})
                   (mk-event {:code "KeyK" :ctrl? true})]]
      (let [matches (cond-> 0
                      (xray-toggle-key? event)   inc
                      (palette-toggle-key? event) inc)]
        (is (<= matches 1)
            (str "event " (js->clj event) " must match at most one predicate"))))))

;; ---- (3) palette-toggle-key? truth table (rf2-wm7z4) ---------------------

(deftest palette-toggle-key-matches-cmd-k-and-ctrl-k
  (testing "Ctrl+K — Windows / Linux convention"
    (is (true? (palette-toggle-key?
                 (mk-event {:key "k" :ctrl? true}))))
    (is (true? (palette-toggle-key?
                 (mk-event {:key "K" :ctrl? true})))))
  (testing "Cmd+K — macOS convention (Meta modifier)"
    (is (true? (palette-toggle-key?
                 (mk-event {:key "k" :meta? true})))))
  (testing "`code` fallback — KeyK"
    (is (true? (palette-toggle-key?
                 (mk-event {:code "KeyK" :ctrl? true}))))))

(deftest palette-toggle-key-rejects-shift
  (testing "Ctrl+Shift+K is Firefox dev-tools — must not be hijacked"
    (is (false? (palette-toggle-key?
                  (mk-event {:key "k" :ctrl? true :shift? true})))))
  (testing "Cmd+Shift+K likewise"
    (is (false? (palette-toggle-key?
                  (mk-event {:key "k" :meta? true :shift? true}))))))

(deftest palette-toggle-key-rejects-both-modifiers
  (testing "Ctrl+Cmd+K — both modifiers held is ambiguous; reject"
    (is (false? (palette-toggle-key?
                  (mk-event {:key "k" :ctrl? true :meta? true}))))))

(deftest palette-toggle-key-rejects-no-modifier
  (testing "plain k must NOT open the palette — that would hijack
            every k keystroke in the host app"
    (is (false? (palette-toggle-key? (mk-event {:key "k"}))))))

(deftest palette-toggle-key-rejects-alt
  (testing "Ctrl+Alt+K is an IME composition on some layouts — reject"
    (is (false? (palette-toggle-key?
                  (mk-event {:key "k" :ctrl? true :alt? true}))))))

(deftest palette-toggle-key-rejects-wrong-key
  (testing "wrong key letter, right modifiers"
    (is (false? (palette-toggle-key?
                  (mk-event {:key "j" :ctrl? true})))))
  (testing "wrong `code`, right modifiers"
    (is (false? (palette-toggle-key?
                  (mk-event {:code "KeyJ" :ctrl? true}))))))

;; ---- (3b) mode-toggle-key? truth table (rf2-o5f5f.1) --------------------

(deftest mode-toggle-key-matches-cmd-shift-m-and-ctrl-shift-m
  (testing "Ctrl+Shift+M — Windows / Linux convention"
    (is (true? (mode-toggle-key?
                 (mk-event {:key "M" :ctrl? true :shift? true}))))
    (is (true? (mode-toggle-key?
                 (mk-event {:key "m" :ctrl? true :shift? true})))))
  (testing "Cmd+Shift+M — macOS convention (Meta modifier)"
    (is (true? (mode-toggle-key?
                 (mk-event {:key "m" :meta? true :shift? true})))))
  (testing "`code` fallback — KeyM"
    (is (true? (mode-toggle-key?
                 (mk-event {:code "KeyM" :ctrl? true :shift? true}))))))

(deftest mode-toggle-key-requires-shift
  (testing "Ctrl+M alone is some Firefox 'bookmark this page' chord —
            require Shift to disambiguate"
    (is (false? (mode-toggle-key?
                  (mk-event {:key "m" :ctrl? true}))))
    (is (false? (mode-toggle-key?
                  (mk-event {:key "m" :meta? true})))
        "Cmd+M alone is 'minimize window' on macOS — require Shift")))

(deftest mode-toggle-key-rejects-both-primary-modifiers
  (testing "Ctrl+Cmd+Shift+M — both primary modifiers held is
            ambiguous, reject (mirror palette-toggle-key?'s posture)"
    (is (false? (mode-toggle-key?
                  (mk-event {:key "m" :ctrl? true :meta? true :shift? true}))))))

(deftest mode-toggle-key-rejects-alt
  (testing "Ctrl+Alt+Shift+M is an IME composition on some layouts — reject"
    (is (false? (mode-toggle-key?
                  (mk-event {:key "m" :ctrl? true :shift? true :alt? true}))))))

(deftest mode-toggle-key-rejects-wrong-key
  (testing "wrong key letter, right modifiers"
    (is (false? (mode-toggle-key?
                  (mk-event {:key "n" :ctrl? true :shift? true})))))
  (testing "wrong `code`, right modifiers"
    (is (false? (mode-toggle-key?
                  (mk-event {:code "KeyN" :ctrl? true :shift? true}))))))

(deftest mode-toggle-key-mutually-exclusive-with-other-predicates
  (testing "no synthetic event satisfies more than one Xray keybinding
            predicate at once — the cond in handle-keydown depends on
            mutual exclusivity"
    (doseq [event [(mk-event {:key "M" :ctrl? true :shift? true})
                   (mk-event {:key "m" :meta? true :shift? true})
                   (mk-event {:code "KeyM" :ctrl? true :shift? true})
                   (mk-event {:key "C" :ctrl? true :shift? true})
                   (mk-event {:key "k" :meta? true})]]
      (let [matches (cond-> 0
                      (mode-toggle-key? event)    inc
                      (xray-toggle-key? event)   inc
                      (palette-toggle-key? event) inc)]
        (is (<= matches 1)
            (str "event " (js->clj event) " must match at most one predicate"))))))

;; ---- (4) attach! / detach! idempotency sentinel --------------------------

(deftest attach-is-idempotent
  (testing "calling attach! twice installs the keydown listener exactly
            once — the contract preventing shadow-cljs :after-load from
            double-firing the toggle"
    (with-stub-document
      (fn [{:keys [listeners]}]
        (is (false? (keybinding/attached?))
            "baseline — sentinel starts at false (defonce reset by the fixture)")
        (keybinding/attach!)
        (is (true? (keybinding/attached?))
            "first attach! flips the sentinel")
        (is (= 1 (count @listeners))
            "first attach! installs exactly one listener")
        (keybinding/attach!)
        (is (true? (keybinding/attached?))
            "sentinel stays true on the second call")
        (is (= 1 (count @listeners))
            "second attach! is a no-op — listener count unchanged")
        (let [{:keys [type use-capture]} (first @listeners)]
          (is (= "keydown" type)
              "listener wired to the keydown event")
          (is (true? use-capture)
              "registered in the capture phase (so host handlers don't
              swallow the toggle)"))))))

(deftest detach-round-trips
  (testing "detach! flips the sentinel back and a subsequent attach!
            re-installs the listener — supports test isolation and any
            future runtime that wants to swap the binding"
    (with-stub-document
      (fn [{:keys [listeners]}]
        (keybinding/attach!)
        (is (= 1 (count @listeners)))
        (keybinding/detach!)
        (is (false? (keybinding/attached?))
            "detach! flips the sentinel back to false")
        (is (zero? (count @listeners))
            "detach! removes the listener")
        (keybinding/attach!)
        (is (true? (keybinding/attached?))
            "re-attach succeeds after detach")
        (is (= 1 (count @listeners))
            "exactly one listener installed after re-attach")))))

(deftest detach-removes-the-exact-attached-fn-hot-reload-safe
  (testing "rf2-t2o6o — detach! removes the SAME fn object attach!
            installed, NOT the (possibly hot-reloaded) handle-keydown
            var. addEventListener / removeEventListener compare by
            reference; a shadow-cljs :after-load recompiles
            handle-keydown to a fresh object, so a detach! that
            referenced the bare var would removeEventListener a fn that
            was never added and silently leak the original listener.
            We prove (a) removing a DIFFERENT fn object — standing in for
            the recompiled var the bare-var detach! would have passed —
            does NOT remove the live listener (the leak), and (b)
            detach! nonetheless drops the listener to zero, which is only
            possible if it passed the EXACT fn attach! installed."
    (with-stub-document
      (fn [{:keys [listeners]}]
        (keybinding/attach!)
        (is (= 1 (count @listeners))
            "attach! installed one listener")
        ;; Simulate the post-reload divergence: the stub's
        ;; removeEventListener matches by `identical?`, so removing a
        ;; DIFFERENT fn object (standing in for the recompiled
        ;; handle-keydown var the OLD code would have passed) must NOT
        ;; remove the live listener. This reproduces the exact leak the
        ;; bare-var detach! caused after :after-load.
        (let [recompiled-stand-in (fn [_] nil)]
          (.removeEventListener js/document "keydown" recompiled-stand-in true)
          (is (= 1 (count @listeners))
              "removing a fresh (recompiled-like) fn object leaves the
               real listener intact — the bare-var detach! leak"))
        ;; detach! removes the stashed object → the listener is gone.
        ;; This passes ONLY because detach! references the exact fn
        ;; attach! stored, not the (here-unchanged, but in production
        ;; recompiled) handle-keydown var.
        (keybinding/detach!)
        (is (zero? (count @listeners))
            "detach! removed the exact attached fn — no leak (rf2-t2o6o)")
        (is (false? (keybinding/attached?))
            "sentinel flipped back to false")
        ;; A subsequent attach!/detach! cycle still round-trips cleanly,
        ;; proving the stash is reset (no stale fn lingering).
        (keybinding/attach!)
        (is (= 1 (count @listeners)))
        (keybinding/detach!)
        (is (zero? (count @listeners))
            "second cycle round-trips — stash cleared on the prior detach!")))))

(deftest detach-on-clean-sentinel-is-safe
  (testing "calling detach! when nothing is attached is a no-op (does
            not throw, does not flip the sentinel below false)"
    (with-stub-document
      (fn [{:keys [listeners]}]
        (is (false? (keybinding/attached?)))
        (keybinding/detach!)
        (is (false? (keybinding/attached?))
            "sentinel remains false")
        (is (zero? (count @listeners))
            "no listener was added or removed")))))

(deftest detach-is-idempotent
  (testing "rf2-ycrt2 — detach! is the public embed-host escape hatch
            (Story calls it from ensure-xray-mounted! after flipping
            :rf.xray/keybinding-enabled? false); calling it twice in a
            row must be safe — the second call removes nothing (the
            sentinel is already false) and does not throw"
    (with-stub-document
      (fn [{:keys [listeners]}]
        (keybinding/attach!)
        (is (= 1 (count @listeners)))
        (keybinding/detach!)
        (is (false? (keybinding/attached?))
            "first detach! flips the sentinel back to false")
        (is (zero? (count @listeners))
            "first detach! removed the listener")
        (keybinding/detach!)
        (is (false? (keybinding/attached?))
            "second detach! keeps the sentinel at false (no underflow)")
        (is (zero? (count @listeners))
            "second detach! is a no-op on the listener set")))))

(deftest attach-without-document-is-safe
  (testing "absence of js/document — node-test baseline — must not
            throw and must not flip the sentinel"
    ;; This is the bare-node-test runtime; no stub installed. The
    ;; (exists? js/document) guard in attach! must short-circuit.
    (when-not (exists? js/document)
      (is (false? (keybinding/attached?)))
      (keybinding/attach!)
      (is (false? (keybinding/attached?))
          "without js/document the sentinel must NOT flip — otherwise
          a subsequent stub-driven attach! would falsely think it had
          already wired up"))))

;; ---- (5) spine-key-id (rf2-adve5) ---------------------------------------
;;
;; Per spec/018 §3 + §6. `keybinding/spine-key-id`'s `cond` arms are the
;; SOURCE OF TRUTH for the spine set — deliberately no count is stated
;; here, because the wording that named one undercounted from the moment
;; the `,` / s arms landed (rf2-v0rw). The predicate is *unmodified* —
;; modifier-held variants must not match (so Cmd+L → focus address bar
;; still works inside Xray). Today the arms are:
;;
;;     Space    →  :rf.xray/toggle-live-pause
;;     L        →  :rf.xray/follow-head      (snap-LIVE)
;;     G        →  :rf.xray/follow-head      (Shift+G; vim 'Go to head')
;;     j        →  :rf.xray/focus-event-prev
;;     k        →  :rf.xray/focus-event-next
;;     `,` / s  →  :rf.xray/settings-toggle  (toggle Settings popup)

(deftest spine-key-id-space-is-toggle-live-pause
  (is (= :rf.xray/toggle-live-pause
         (spine-key-id (mk-event {:key " "}))))
  (is (= :rf.xray/toggle-live-pause
         (spine-key-id (mk-event {:code "Space"})))))

(deftest spine-key-id-l-is-follow-head
  (is (= :rf.xray/follow-head
         (spine-key-id (mk-event {:key "l"}))))
  (is (= :rf.xray/follow-head
         (spine-key-id (mk-event {:code "KeyL"})))))

(deftest spine-key-id-shift-g-is-follow-head
  (is (= :rf.xray/follow-head
         (spine-key-id (mk-event {:key "G" :shift? true}))))
  (is (= :rf.xray/follow-head
         (spine-key-id (mk-event {:code "KeyG" :shift? true})))))

(deftest spine-key-id-j-is-prev
  (is (= :rf.xray/focus-event-prev
         (spine-key-id (mk-event {:key "j"}))))
  (is (= :rf.xray/focus-event-prev
         (spine-key-id (mk-event {:code "KeyJ"})))))

(deftest spine-key-id-k-is-next
  (is (= :rf.xray/focus-event-next
         (spine-key-id (mk-event {:key "k"}))))
  (is (= :rf.xray/focus-event-next
         (spine-key-id (mk-event {:code "KeyK"})))))

(deftest spine-key-id-comma-is-settings-toggle
  ;; rf2-v0rw — the `,` arm was unasserted, which is why the prose above
  ;; could undercount the roster without anything going red.
  (is (= :rf.xray/settings-toggle
         (spine-key-id (mk-event {:key ","}))))
  (is (= :rf.xray/settings-toggle
         (spine-key-id (mk-event {:code "Comma"})))))

(deftest spine-key-id-s-is-settings-toggle
  ;; rf2-v0rw — `s` is the second door onto the Settings popup; per
  ;; spec/007-UX-IA.md §Global shortcuts both `,` and `s` open the modal.
  (is (= :rf.xray/settings-toggle
         (spine-key-id (mk-event {:key "s"}))))
  (is (= :rf.xray/settings-toggle
         (spine-key-id (mk-event {:code "KeyS"})))))

(deftest spine-key-id-c-is-unbound
  ;; rf2-y0z5b — Causality surface dropped entirely; `c` is now free
  ;; (no spine handler attached). Future bead may rewire if needed.
  (is (nil? (spine-key-id (mk-event {:key "c"}))))
  (is (nil? (spine-key-id (mk-event {:code "KeyC"})))))

(deftest spine-key-id-rejects-modifiers
  (testing "Ctrl+L must not be hijacked (focus address bar)"
    (is (nil? (spine-key-id (mk-event {:key "l" :ctrl? true})))))
  (testing "Cmd+L likewise"
    (is (nil? (spine-key-id (mk-event {:key "l" :meta? true})))))
  (testing "Alt+j must not match"
    (is (nil? (spine-key-id (mk-event {:key "j" :alt? true})))))
  (testing "Shift+j must not match (capital J is not a spine key)"
    (is (nil? (spine-key-id (mk-event {:key "j" :shift? true})))))
  (testing "Lowercase g without Shift must not match (only Shift+G is)"
    (is (nil? (spine-key-id (mk-event {:key "g"}))))))

(deftest spine-key-id-rejects-unknown-keys
  (testing "unrelated keys return nil"
    (is (nil? (spine-key-id (mk-event {:key "x"}))))
    (is (nil? (spine-key-id (mk-event {:key "Enter"}))))
    (is (nil? (spine-key-id (mk-event {})))
        "empty event → nil")))

;; ---- (6) :rf.xray/keybinding-enabled? toggle (rf2-4eyik — rf2-q7who.A) ----
;;
;; Per Spec 015-Configuration §`:rf.xray/keybinding-enabled?` the slot
;; controls whether `attach!` installs the window-level capture-phase
;; listener. Default `true` (existing hosts unaffected); embed hosts —
;; Story mounts Xray as its RHS panel — flip it to `false` so their own
;; global keybindings (typically `Cmd/Ctrl+K`) aren't swallowed by the
;; capture-phase `stopPropagation()`.
;;
;; Each test sets the slot, exercises attach!, and ALWAYS resets it in
;; a `finally` so the default (`true`) survives into neighbouring
;; tests in the same suite run.

(deftest attach-disabled-by-config-is-noop
  (testing "rf2-4eyik (rf2-q7who.A) — with :rf.xray/keybinding-enabled?
            false, attach! does NOT register the global listener and
            does NOT flip the sentinel; the embed-host contract"
    (with-stub-document
      (fn [{:keys [listeners]}]
        (try
          (config/set-keybinding-enabled! false)
          (is (false? (config/keybinding-attach-enabled?))
              "config flag flipped false")
          (is (false? (keybinding/attached?))
              "baseline — sentinel starts at false")
          (keybinding/attach!)
          (is (false? (keybinding/attached?))
              "attach! short-circuited; sentinel stayed false")
          (is (zero? (count @listeners))
              "no listener registered on the stub document")
          (finally
            ;; Restore the default so neighbouring tests
            ;; (attach-is-idempotent, detach-round-trips) see the
            ;; baseline they were written against.
            (config/set-keybinding-enabled! true)))))))

(deftest attach-default-is-enabled
  (testing "rf2-4eyik (rf2-q7who.A) — default config is true; attach!
            registers as it did pre-rf2-4eyik. Defends against an
            accidental flip of the default."
    (with-stub-document
      (fn [{:keys [listeners]}]
        (is (true? (config/keybinding-attach-enabled?))
            "default state — slot is true")
        (keybinding/attach!)
        (is (true? (keybinding/attached?))
            "sentinel flipped true under default config")
        (is (= 1 (count @listeners))
            "one keydown listener registered as before")))))

(deftest config-set-keybinding-enabled-nil-resets-to-true
  (testing "rf2-4eyik — `nil` arg restores the default `true` per the
            convention shared with set-auto-open! / set-editor!"
    (try
      (config/set-keybinding-enabled! false)
      (is (false? (config/keybinding-attach-enabled?)))
      (config/set-keybinding-enabled! nil)
      (is (true? (config/keybinding-attach-enabled?))
          "nil restores the default")
      (finally
        (config/set-keybinding-enabled! true)))))

;; ---- (7) Esc dismisses the editor-hint toast (rf2-wpvy6f) ----------------
;;
;; The hint toast is a non-modal `role=status` surface that must NOT trap
;; focus (it would steal it from the host app), so its own in-DOM
;; `on-key-down` never receives Esc in the normal click flow. The
;; shell-level global `handle-keydown` is the reachable Esc path: it
;; dismisses the hint whenever it is open, and falls through (no consume)
;; whenever it is closed so other Esc consumers / the host are
;; undisturbed.
;;
;; Unlike the pure-predicate tests above, these need a live `:rf/xray`
;; frame with the editor-hint events registered, so they bootstrap the
;; re-frame runtime (mirrors editor_hint_cljs_test.cljs's fixture).

(defn- handle-keydown
  "Reach the private dispatcher via var access."
  [event]
  (#'keybinding/handle-keydown event))

(defn- mk-keydown-event
  "Synthetic KeyboardEvent with prevent/stop spies + a `key`. Records
  whether preventDefault / stopPropagation were called so a test can
  assert the listener consumed (or did NOT consume) the key."
  [k]
  (let [prevented (atom false)
        stopped   (atom false)]
    {:event   (js-obj "key"             k
                      "preventDefault"  (fn [] (reset! prevented true))
                      "stopPropagation" (fn [] (reset! stopped true)))
     :prevented prevented
     :stopped   stopped}))

(defn- setup-xray-runtime! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!)
  (reset! rf.frame/frames {})
  (rf.substrate.adapter/dispose-adapter!)
  (rf.substrate.adapter/install-adapter! rf.substrate.plain-atom/adapter)
  (rf.frame/ensure-default-frame!)
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(deftest esc-dismisses-open-editor-hint
  (testing "rf2-wpvy6f — when the editor-hint toast is OPEN, the global
            handle-keydown consumes Esc and dispatches
            :rf.xray/editor-hint-dismiss on :rf/xray, closing the toast"
    (setup-xray-runtime!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/editor-hint-show]))
    (is (true? (boolean (:editor-hint-open?
                         (rf.frame/frame-app-db-value defaults/default-frame-id))))
        "precondition: toast is open")
    (let [{:keys [event prevented stopped]} (mk-keydown-event "Escape")]
      (handle-keydown event)
      (is @prevented "Esc was consumed — preventDefault called")
      (is @stopped   "Esc was consumed — stopPropagation called"))
    ;; The dismiss dispatch is synchronous (`rf/dispatch` queues, but the
    ;; reg-event handler lands on the next router tick); drain via dispatch-sync
    ;; on a no-op to flush, then assert. Use dispatch-sync directly to be
    ;; deterministic.
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/editor-hint-dismiss]))
    (is (false? (boolean (:editor-hint-open?
                          (rf.frame/frame-app-db-value defaults/default-frame-id))))
        "toast is dismissed")))

(deftest esc-falls-through-when-hint-closed
  (testing "rf2-wpvy6f — when the toast is CLOSED, Esc is NOT consumed by
            the editor-hint branch (no preventDefault / stopPropagation),
            so it falls through to the host and other Esc consumers"
    (setup-xray-runtime!)
    (is (false? (boolean (:editor-hint-open?
                          (rf.frame/frame-app-db-value defaults/default-frame-id))))
        "precondition: toast is closed")
    (let [{:keys [event prevented stopped]} (mk-keydown-event "Escape")]
      (handle-keydown event)
      (is (false? @prevented)
          "closed toast → Esc not consumed (preventDefault not called)")
      (is (false? @stopped)
          "closed toast → Esc not consumed (stopPropagation not called)"))))

(deftest editor-hint-open-predicate-reads-frame-app-db
  (testing "rf2-wpvy6f — the private editor-hint-open? reader reflects the
            :rf/xray frame's :editor-hint-open? app-db slot, and is false
            when the frame is absent"
    (setup-xray-runtime!)
    (is (false? (#'keybinding/editor-hint-open?))
        "false when the slot is unset")
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/editor-hint-show]))
    (is (true? (#'keybinding/editor-hint-open?))
        "true once the toast is shown")
    (reset! rf.frame/frames {})
    (is (false? (#'keybinding/editor-hint-open?))
        "false when the :rf/xray frame is absent — Esc falls through")))

;; ---- (8) rf2-llecpa — held toggle chords must not flap (repeat guard) ----
;;
;; No `event.repeat` guard meant HOLDING a toggle chord fired it at the
;; OS key-repeat rate — the shell / palette / mode / live-pause flapped
;; open↔closed. `handle-keydown` now swallows `.repeat` keydowns for
;; every binding EXCEPT the j / k step keys (which WANT auto-repeat so a
;; held key walks the feed). `step-key?` is the exemption predicate.

(defn- step-key?
  "Reach the private step-exemption predicate via var access."
  [event]
  (#'keybinding/step-key? event))

(defn- mk-spy-event
  "KeyboardEvent-shaped object with preventDefault / stopPropagation
  spies plus arbitrary modifier + `.repeat` fields, so a test can assert
  whether `handle-keydown` consumed (acted on) a synthetic keydown."
  [opts]
  (let [{:keys [key code ctrl? shift? meta? alt? repeat?]
         :or   {ctrl? false shift? false meta? false alt? false repeat? false}} opts
        prevented (atom false)
        stopped   (atom false)]
    {:event     (js-obj "key"             key
                        "code"            code
                        "ctrlKey"         ctrl?
                        "shiftKey"        shift?
                        "metaKey"         meta?
                        "altKey"          alt?
                        "repeat"          repeat?
                        "preventDefault"  (fn [] (reset! prevented true))
                        "stopPropagation" (fn [] (reset! stopped true)))
     :prevented prevented
     :stopped   stopped}))

(deftest step-key-exempts-only-unmodified-j-and-k
  (testing "rf2-llecpa — step-key? is the SOLE repeat exemption: the
            feed-stepping keys j / k (which want held-key auto-repeat)"
    (is (true? (boolean (step-key? (mk-event {:key "j"})))))
    (is (true? (boolean (step-key? (mk-event {:key "k"})))))
    (is (true? (boolean (step-key? (mk-event {:code "KeyJ"})))))
    (is (true? (boolean (step-key? (mk-event {:code "KeyK"}))))))
  (testing "toggles + idempotent snaps are NOT step keys — they get
            repeat-guarded so a held press fires once per physical press"
    (is (false? (boolean (step-key? (mk-event {:key " "})))) "Space")
    (is (false? (boolean (step-key? (mk-event {:key "l"})))) "snap-LIVE")
    (is (false? (boolean (step-key? (mk-event {:key "G" :shift? true})))) "go-to-head")
    (is (false? (boolean (step-key? (mk-event {:key "s"})))) "settings")
    (is (false? (boolean (step-key? (mk-event {:key ","})))) "settings"))
  (testing "MODIFIED j / k are not the bare step binding (Ctrl+j etc.) —
            those never matched the spine anyway, so no exemption applies"
    (is (false? (boolean (step-key? (mk-event {:key "j" :ctrl? true})))))
    (is (false? (boolean (step-key? (mk-event {:key "k" :meta? true})))))
    (is (false? (boolean (step-key? (mk-event {:key "j" :shift? true})))))))

(deftest held-toggle-chords-are-ignored
  (testing "rf2-llecpa — a repeat keydown for a toggle chord is swallowed:
            handle-keydown bails at the first cond arm before the action,
            so it never preventDefaults / stopPropagations / toggles.
            Proves shell (Ctrl+Shift+C), palette (Cmd/Ctrl+K), and mode
            (Cmd/Ctrl+Shift+M) toggles fire once per PHYSICAL press, not
            once per OS repeat tick."
    (doseq [chord [{:key "C" :ctrl? true :shift? true :repeat? true}    ;; shell
                   {:key "k" :meta? true :repeat? true}                 ;; palette (mac)
                   {:key "k" :ctrl? true :repeat? true}                 ;; palette (win/linux)
                   {:key "M" :ctrl? true :shift? true :repeat? true}]]  ;; mode
      (let [{:keys [event prevented stopped]} (mk-spy-event chord)]
        (handle-keydown event)
        (is (false? @prevented)
            (str "repeat chord " chord " must be ignored — not consumed"))
        (is (false? @stopped)
            (str "repeat chord " chord " must not stopPropagation"))))))

(deftest held-space-does-not-toggle-live-pause
  (testing "rf2-llecpa — a HELD Space (auto-repeat) inside the shell does
            not re-fire the LIVE pause toggle. The runtime + shell-visible
            plumbing that a genuine Space press needs is the browser lane;
            here we assert the repeat is swallowed at the guard (no
            preventDefault) — the spine branch is never reached."
    (let [{:keys [event prevented stopped]} (mk-spy-event {:key " " :repeat? true})]
      (handle-keydown event)
      (is (false? @prevented) "repeat Space is ignored — not consumed")
      (is (false? @stopped)))))

(deftest held-escape-does-not-redismiss-hint
  (testing "rf2-llecpa — a HELD Escape (auto-repeat) does not re-fire the
            editor-hint dismiss; the first physical press already acted.
            The non-repeat Escape path stays live (see
            esc-dismisses-open-editor-hint), so this also proves a plain
            keydown is NOT over-blocked by the guard."
    (setup-xray-runtime!)
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/editor-hint-show]))
    (let [{:keys [event prevented stopped]} (mk-spy-event {:key "Escape" :repeat? true})]
      (handle-keydown event)
      (is (false? @prevented) "repeat Escape is ignored — not consumed")
      (is (false? @stopped)))))

;; ---- (9) rf2-d716o9 — Space not hijacked from focused button/summary -----
;;
;; `target-editable?` only exempted INPUT/TEXTAREA/SELECT/contenteditable,
;; so a focused shell `<button>` / `<summary>` / `[role=button]` fell into
;; the spine branch: Space dispatched `:rf.xray/toggle-live-pause` and
;; `.preventDefault`d, blocking the control's native Space activation. The
;; new `target-activatable?` predicate yields the spine to those controls.

(defn- mk-target-event
  "Synthetic keydown whose `.target` is a fake element with the given
  `tag` (tagName) and optional `role` attribute — enough to drive the
  target-* predicates without a real DOM."
  [{:keys [tag role]}]
  (js-obj "target"
          (js-obj "tagName"      tag
                  "getAttribute" (fn [attr] (when (= attr "role") role)))))

(defn- mk-shell-space-event
  "Synthetic bare Space keydown whose `.target` is a fake element INSIDE
  the Xray shell: `.closest` resolves the shell testid (so
  `target-inside-xray?` is true) but carries no modal marker. `tag` /
  `role` drive the activatable check; preventDefault / stopPropagation
  are spied so a test can see whether the spine consumed Space."
  [{:keys [tag role]}]
  (let [prevented  (atom false)
        stopped    (atom false)
        shell-node (js-obj "id" "fake-shell")
        target     (js-obj "tagName"      tag
                           "getAttribute" (fn [attr] (when (= attr "role") role))
                           "closest"      (fn [sel]
                                            (when (re-find #"rf-xray-shell" sel)
                                              shell-node)))
        event      (js-obj "key"             " "
                           "code"            "Space"
                           "target"          target
                           "ctrlKey"  false  "metaKey" false
                           "altKey"   false  "shiftKey" false
                           "repeat"          false
                           "preventDefault"  (fn [] (reset! prevented true))
                           "stopPropagation" (fn [] (reset! stopped true)))]
    {:event event :prevented prevented :stopped stopped}))

(deftest target-activatable-matches-buttons-summary-role
  (testing "rf2-d716o9 — a focused <button> / <summary> / [role=button]
            natively consumes Space; target-activatable? flags them so the
            spine yields"
    (is (true? (boolean (#'keybinding/target-activatable?
                          (mk-target-event {:tag "BUTTON"})))))
    (is (true? (boolean (#'keybinding/target-activatable?
                          (mk-target-event {:tag "SUMMARY"})))))
    (is (true? (boolean (#'keybinding/target-activatable?
                          (mk-target-event {:tag "DIV" :role "button"}))))
        "[role=button] is an ARIA button — also activatable")
    (is (true? (boolean (#'keybinding/target-activatable?
                          (mk-target-event {:tag "button"}))))
        "tagName compared case-insensitively (lower-case host quirk)")))

(deftest target-activatable-rejects-non-activatable
  (testing "rf2-d716o9 — ordinary shell nodes are NOT activatable, so the
            spine keys still fire on them"
    (is (false? (boolean (#'keybinding/target-activatable?
                           (mk-target-event {:tag "DIV"})))))
    (is (false? (boolean (#'keybinding/target-activatable?
                           (mk-target-event {:tag "SPAN"})))))
    (is (false? (boolean (#'keybinding/target-activatable?
                           (mk-target-event {:tag "A"}))))
        "<a href> activates on Enter (not a spine key) — not activatable")
    (is (false? (boolean (#'keybinding/target-activatable?
                           (mk-target-event {:tag "DIV" :role "listbox"}))))
        "a non-button role is not activatable")
    (is (nil? (#'keybinding/target-activatable? (js-obj)))
        "an event with no target must not throw")))

(deftest space-on-focused-button-is-not-hijacked
  (testing "rf2-d716o9 — a focused shell <button> / <summary> / [role=
            button] keeps Space for its native activation: the spine
            branch yields (no preventDefault, no live-pause dispatch)."
    (setup-xray-runtime!)
    (with-redefs [mount/visible? (constantly true)]
      (doseq [spec [{:tag "BUTTON"}
                    {:tag "SUMMARY"}
                    {:tag "DIV" :role "button"}]]
        (let [{:keys [event prevented stopped]} (mk-shell-space-event spec)]
          (handle-keydown event)
          (is (false? @prevented)
              (str "Space on a focused " spec " must NOT be hijacked"))
          (is (false? @stopped)
              (str "Space on a focused " spec " must not stopPropagation")))))))

(deftest space-on-non-activatable-shell-target-still-pauses
  (testing "rf2-d716o9 — the guard is surgical: Space on a NON-activatable
            focused shell node still fires the LIVE-pause binding
            (preventDefault called), so the spine keeps working normally
            when focus is not on an activatable control."
    (setup-xray-runtime!)
    (with-redefs [mount/visible? (constantly true)]
      (let [{:keys [event prevented]} (mk-shell-space-event {:tag "DIV"})]
        (handle-keydown event)
        (is (true? @prevented)
            "Space consumed — the spine live-pause binding fired on a plain node")))))

;; ---- (10) the pop-out document's own listener (rf2-61i5) -----------------
;;
;; `mount/popout!` renders a live shell into a SECOND document, and DOM key
;; events do not cross realms — so the opener-document listener could never
;; see a keypress made in the pop-out window, and the documented keyboard
;; workflow was inert whenever focus was there.
;;
;; Two things are under test, and they fail in different ways:
;;
;;   * ROUTING. `handle-keydown-on` is now surface-parameterised. Every
;;     assertion below pairs the pop-out surface with the OPENER surface on
;;     the identical event, so a test cannot pass by the handler having
;;     become inert — the control shares the shape of the target.
;;   * OWNERSHIP. `install-popout-keydown!` must add exactly one
;;     capture-phase listener to the document it is handed and return a
;;     disposer that removes THAT fn object.
;;
;; The browser-level counterpart (a real second window, real keypresses)
;; lives in the feature-matrix scenario added under the same bead.

(defn- handle-keydown-on [surface event]
  (#'keybinding/handle-keydown-on surface event))

(def ^:private popout-surface @#'keybinding/popout-surface)
(def ^:private opener-surface @#'keybinding/opener-surface)

(defn- mk-shell-key-event
  "Synthetic keydown whose `.target` resolves the Xray shell testid via
  `.closest` — i.e. a key pressed INSIDE a rendered shell, which is what
  both surfaces see. Modifiers and key are caller-supplied; prevent/stop
  are spied."
  [{:keys [key code ctrl? shift? meta? alt?]
    :or   {ctrl? false shift? false meta? false alt? false}}]
  (let [prevented  (atom false)
        stopped    (atom false)
        shell-node (js-obj "id" "fake-shell")
        target     (js-obj "tagName"      "DIV"
                           "getAttribute" (fn [_] nil)
                           "closest"      (fn [sel]
                                            (when (re-find #"rf-xray-shell" sel)
                                              shell-node)))]
    {:event     (js-obj "key"             key
                        "code"            code
                        "target"          target
                        "ctrlKey"         ctrl?
                        "shiftKey"        shift?
                        "metaKey"         meta?
                        "altKey"          alt?
                        "repeat"          false
                        "preventDefault"  (fn [] (reset! prevented true))
                        "stopPropagation" (fn [] (reset! stopped true)))
     :prevented prevented
     :stopped   stopped}))

(deftest popout-spine-keys-fire-with-no-opener-shell-visible
  (testing "rf2-61i5 — the bug's core. `mount/visible?` reports on the
            OPENER's in-app shell, so with no inline shell open every bare
            spine key was refused. In the pop-out the shell IS on screen,
            so the spine must fire; the opener surface on the SAME event
            must still refuse. Pairing them is the control: if the handler
            had merely gone inert, the pop-out half would fail too."
    (setup-xray-runtime!)
    (with-redefs [mount/visible? (constantly false)]
      (doseq [k [{:key " " :code "Space"}
                 {:key "j" :code "KeyJ"}
                 {:key "k" :code "KeyK"}
                 {:key "l" :code "KeyL"}]]
        (let [{:keys [event prevented]} (mk-shell-key-event k)]
          (handle-keydown-on popout-surface event)
          (is (true? @prevented)
              (str "pop-out spine key " k " must fire with no opener shell")))
        (let [{:keys [event prevented]} (mk-shell-key-event k)]
          (handle-keydown-on opener-surface event)
          (is (false? @prevented)
              (str "opener spine key " k " must still refuse when its own "
                   "shell is hidden — the pre-existing contract")))))))

(deftest popout-palette-does-not-touch-the-opener-shell
  (testing "rf2-61i5 — Cmd/Ctrl+K in the pop-out opens the palette WITHOUT
            mounting, showing or reopening the opener's inline shell. The
            opener surface on the identical event still calls `toggle!`
            when its shell is hidden, which is what makes the pop-out
            assertion mean something."
    (setup-xray-runtime!)
    (let [toggles (atom 0)]
      (with-redefs [mount/visible? (constantly false)
                    mount/toggle!  (fn [] (swap! toggles inc) nil)]
        (doseq [chord [{:key "k" :code "KeyK" :meta? true}
                       {:key "k" :code "KeyK" :ctrl? true}]]
          (reset! toggles 0)
          (let [{:keys [event prevented]} (mk-shell-key-event chord)]
            (handle-keydown-on popout-surface event)
            (is (true? @prevented)
                (str "pop-out " chord " is consumed — the palette opens here"))
            (is (zero? @toggles)
                (str "pop-out " chord " must NOT mount or reopen the opener's "
                     "shell — that is the accident this bead names")))
          (reset! toggles 0)
          (let [{:keys [event]} (mk-shell-key-event chord)]
            (handle-keydown-on opener-surface event)
            (is (= 1 @toggles)
                (str "control: the OPENER surface still shows its hidden "
                     "shell before opening the palette on " chord))))))))

(deftest popout-shell-toggle-chord-stays-opener-owned
  (testing "rf2-61i5 — Ctrl+Shift+C shows/hides the opener's IN-APP shell,
            a surface that does not exist in the pop-out document. Pressed
            in the pop-out it must not reach across and toggle the opener's
            shell, and must not be swallowed either (no preventDefault), so
            it falls through to the browser like any unbound key."
    (setup-xray-runtime!)
    (let [toggles (atom 0)]
      (with-redefs [mount/visible? (constantly true)
                    mount/toggle!  (fn [] (swap! toggles inc) nil)]
        (let [{:keys [event prevented stopped]}
              (mk-shell-key-event {:key "C" :code "KeyC" :ctrl? true :shift? true})]
          (handle-keydown-on popout-surface event)
          (is (zero? @toggles) "pop-out Ctrl+Shift+C must not toggle the opener")
          (is (false? @prevented) "and must not consume the key")
          (is (false? @stopped)   "and must not stop propagation"))
        (reset! toggles 0)
        (let [{:keys [event prevented]}
              (mk-shell-key-event {:key "C" :code "KeyC" :ctrl? true :shift? true})]
          (handle-keydown-on opener-surface event)
          (is (= 1 @toggles) "control: the opener surface still owns the chord")
          (is (true? @prevented) "and still consumes it"))))))

(deftest popout-mode-chord-routes-through-the-shared-map
  (testing "rf2-61i5 — Cmd/Ctrl+Shift+M and `,` / s are NOT surface-
            dependent: both surfaces route them identically through the one
            keyboard map. Proves the pop-out reuses the canonical roster
            rather than carrying a second table."
    (setup-xray-runtime!)
    (with-redefs [mount/visible? (constantly true)]
      (doseq [chord [{:key "M" :code "KeyM" :ctrl? true :shift? true}
                     {:key "," :code "Comma"}
                     {:key "s" :code "KeyS"}]]
        (let [popout (mk-shell-key-event chord)
              opener (mk-shell-key-event chord)]
          (handle-keydown-on popout-surface (:event popout))
          (handle-keydown-on opener-surface (:event opener))
          (is (= @(:prevented opener) @(:prevented popout))
              (str "surfaces must agree on " chord))
          (is (true? @(:prevented popout))
              (str chord " is a live binding on both surfaces")))))))

(deftest install-popout-keydown-owns-exactly-one-listener
  (testing "rf2-61i5 — installing on a pop-out document adds exactly ONE
            capture-phase keydown listener, and the returned disposer
            removes that exact fn object (add/removeEventListener compare
            by reference)."
    (let [{:keys [doc listeners]} (mk-stub-document)
          dispose (keybinding/install-popout-keydown! doc)]
      (is (fn? dispose) "an installer that ran returns its disposer")
      (is (= 1 (count @listeners)) "exactly one listener installed")
      (let [{:keys [type use-capture]} (first @listeners)]
        (is (= "keydown" type))
        (is (true? use-capture) "capture phase, as on the opener document"))
      (dispose)
      (is (zero? (count @listeners))
          "the disposer removed the exact listener it installed"))))

(deftest popout-listeners-do-not-accumulate-across-windows
  (testing "rf2-61i5 — each pop-out document gets its own listener and its
            own disposer; disposing one must not disturb the other. This is
            the reopen contract: `teardown-popout-state!` disposes, a later
            `popout!` installs one fresh listener rather than stacking
            handlers."
    (let [a (mk-stub-document)
          b (mk-stub-document)
          dispose-a (keybinding/install-popout-keydown! (:doc a))
          dispose-b (keybinding/install-popout-keydown! (:doc b))]
      (is (= 1 (count @(:listeners a))))
      (is (= 1 (count @(:listeners b))))
      (is (not (identical? (:handler (first @(:listeners a)))
                           (:handler (first @(:listeners b)))))
          "a fresh closure per document — not one shared process-wide fn")
      (dispose-a)
      (is (zero? (count @(:listeners a))) "a disposed")
      (is (= 1 (count @(:listeners b))) "b untouched by a's disposal")
      (dispose-b)
      (is (zero? (count @(:listeners b)))))))

(deftest install-popout-keydown-honours-the-config-slot
  (testing "rf2-61i5 — an embed host that cleared
            :rf.xray/keybinding-enabled? gets no pop-out listener either.
            The slot is read at INSTALL time, matching attach!'s posture."
    (let [{:keys [doc listeners]} (mk-stub-document)]
      (try
        (config/set-keybinding-enabled! false)
        (is (nil? (keybinding/install-popout-keydown! doc))
            "declined — nil rather than a disposer")
        (is (zero? (count @listeners)) "nothing installed")
        (finally
          (config/set-keybinding-enabled! true)))
      ;; Control, sharing the shape: with the slot restored the SAME call on
      ;; the SAME document installs — so the zero above is a refusal, not an
      ;; installer that never works.
      (let [dispose (keybinding/install-popout-keydown! doc)]
        (is (= 1 (count @listeners)) "installs once the slot is back on")
        (dispose)))))

(deftest install-popout-keydown-refuses-a-nil-document
  (testing "rf2-61i5 — a pop-out whose document is unreachable installs
            nothing and returns nil rather than throwing; `popout!` stores
            the nil and `teardown-popout-state!` skips disposal."
    (is (nil? (keybinding/install-popout-keydown! nil)))))

(deftest popout-installer-is-registered-with-mount
  (testing "rf2-61i5 — the injection that closes the mount <-> keybinding
            cycle. `mount/popout!` reaches keybinding ONLY through this
            slot, so an unregistered installer means a keyboard-less
            pop-out with nothing else failing."
    (is (identical? keybinding/install-popout-keydown!
                    @#'mount/popout-keydown-installer)
        "keybinding registered its installer into mount at load time")))
