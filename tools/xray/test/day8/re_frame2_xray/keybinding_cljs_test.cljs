(ns day8.re-frame2-xray.keybinding-cljs-test
  "Tests for Xray's global keydown listener.

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
  level keydown-dispatch story lives in the Playwright lane on a real
  document."
  (:require [cljs.test :refer-macros [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.substrate.adapter :as rf.substrate.adapter]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [day8.re-frame2-xray.config :as config]
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
  "The Cmd/Ctrl+K command-palette predicate."
  [event]
  (#'keybinding/palette-toggle-key? event))

(defn- spine-key-id
  "The spine-binding predicate (Space / l / j / k / Shift+G /
  `,` / s). `keybinding/spine-key-id`'s `cond` arms are the roster; read
  them rather than any count restated here.
  Returns the spine event id or nil."
  [event]
  (#'keybinding/spine-key-id event))

(defn- mode-toggle-key?
  "The Cmd/Ctrl+Shift+M predicate that drives the
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

  Detect the host by writing-and-checking; if the
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
  ;; In `:browser-test` the host's `window.document` is
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
                 (mk-event {:code "KeyC" :ctrl? true :shift? true})))))
  (testing "C-shaped key on a different code — `key` alone is enough. This
            guards against the predicate being tightened to AND both fields"
    (is (true? (xray-toggle-key?
                 (mk-event {:key "C" :code "Digit3"
                            :ctrl? true :shift? true}))))))

(deftest xray-toggle-key-rejects-every-other-chord
  (are [event] (false? (xray-toggle-key? (mk-event event)))
    {:key "C"}                                ; no modifiers
    {:key "C" :ctrl? true}                    ; Shift missing
    {:key "C" :shift? true}                   ; Ctrl missing
    ;; meta blocks — avoids the macOS dev-tools Cmd+Shift+C collision the
    ;; source docstring calls out
    {:key "C" :ctrl? true :shift? true :meta? true}
    {:key "C" :ctrl? true :shift? true :alt? true}             ; alt blocks
    {:key "C" :ctrl? true :shift? true :meta? true :alt? true} ; both block
    {:key "D" :ctrl? true :shift? true}       ; wrong key, right modifiers
    {:code "KeyD" :ctrl? true :shift? true}   ; wrong code, right modifiers
    {:ctrl? true :shift? true}))              ; neither key nor code

;; ---- (3) palette-toggle-key? truth table ---------------------------------

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

(deftest palette-toggle-key-rejects-every-other-chord
  (are [event] (false? (palette-toggle-key? (mk-event event)))
    {:key "k" :ctrl? true :shift? true} ; Firefox dev-tools — must not be hijacked
    {:key "k" :meta? true :shift? true} ; Cmd+Shift+K likewise
    {:key "k" :ctrl? true :meta? true}  ; both modifiers held is ambiguous
    {:key "k"}                          ; plain k would hijack every k in the host
    {:key "k" :ctrl? true :alt? true}   ; an IME composition on some layouts
    {:key "j" :ctrl? true}              ; wrong key, right modifiers
    {:code "KeyJ" :ctrl? true}))        ; wrong code, right modifiers

;; ---- (3b) mode-toggle-key? truth table ----------------------------------

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

(deftest mode-toggle-key-rejects-every-other-chord
  (are [event] (false? (mode-toggle-key? (mk-event event)))
    {:key "m" :ctrl? true}  ; a Firefox 'bookmark this page' chord — Shift disambiguates
    {:key "m" :meta? true}  ; 'minimize window' on macOS — Shift disambiguates
    {:key "m" :ctrl? true :meta? true :shift? true} ; both primary modifiers: ambiguous
    {:key "m" :ctrl? true :shift? true :alt? true}  ; an IME composition on some layouts
    {:key "n" :ctrl? true :shift? true}             ; wrong key, right modifiers
    {:code "KeyN" :ctrl? true :shift? true}))       ; wrong code, right modifiers

;; ---- (4) attach! / detach! idempotency sentinel --------------------------

(deftest attach-is-idempotent
  ;; shadow-cljs :after-load re-runs attach!, which must not stack a second
  ;; listener; capture phase keeps host handlers from swallowing the toggle.
  (with-stub-document
    (fn [{:keys [listeners]}]
      (keybinding/attach!)
      (keybinding/attach!)
      (let [{:keys [type use-capture]} (first @listeners)]
        (is (= [true 1 "keydown" true]
               [(keybinding/attached?) (count @listeners) type use-capture]))))))

(deftest detach-removes-the-exact-attached-fn-hot-reload-safe
  (testing "detach! removes the SAME fn object attach!
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
        ;; handle-keydown var a bare-var detach! would pass) must NOT
        ;; remove the live listener. This reproduces the exact leak a
        ;; bare-var detach! would cause after :after-load.
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
            "detach! removed the exact attached fn — no leak")
        (is (false? (keybinding/attached?))
            "sentinel flipped back to false")
        ;; A subsequent attach!/detach! cycle still round-trips cleanly,
        ;; proving the stash is reset (no stale fn lingering).
        (keybinding/attach!)
        (is (= 1 (count @listeners)))
        (keybinding/detach!)
        (is (zero? (count @listeners))
            "second cycle round-trips — stash cleared on the prior detach!")))))

(deftest detach-is-idempotent
  ;; Story calls detach! after clearing :rf.xray/keybinding-enabled?, so a
  ;; second call must be a safe no-op.
  (with-stub-document
    (fn [{:keys [listeners]}]
      (keybinding/attach!)
      (keybinding/detach!)
      (keybinding/detach!)
      (is (= [false 0] [(keybinding/attached?) (count @listeners)])))))

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

;; ---- (5) spine-key-id ---------------------------------------------------
;;
;; Per spec/018 §3 + §6. `keybinding/spine-key-id`'s `cond` arms are the
;; SOURCE OF TRUTH for the spine set — deliberately no count is stated
;; here, because a restated count drifts as arms are added. The predicate
;; is *unmodified* — modifier-held variants must not match (so Cmd+L →
;; focus address bar still works inside Xray). The arms are:
;;
;;     Space    →  :rf.xray/toggle-live-pause
;;     l        →  :rf.xray/follow-head      (snap-LIVE)
;;     G        →  :rf.xray/follow-head      (Shift+G; vim 'Go to head')
;;     j        →  :rf.xray/focus-event-prev
;;     k        →  :rf.xray/focus-event-next
;;     `,` / s  →  :rf.xray/settings-toggle  (toggle Settings popup)

(deftest spine-key-id-maps-every-arm
  ;; One row per arm and per spelling (`key`, then the `code` fallback), so
  ;; the roster is pinned by tests rather than by the prose above. `,` and
  ;; `s` are both doors onto the Settings popup, per spec/007-UX-IA.md
  ;; §Shell spine keys.
  (are [event id] (= id (spine-key-id (mk-event event)))
    {:key " "}                  :rf.xray/toggle-live-pause
    {:code "Space"}             :rf.xray/toggle-live-pause
    {:key "l"}                  :rf.xray/follow-head
    {:code "KeyL"}              :rf.xray/follow-head
    {:key "G" :shift? true}     :rf.xray/follow-head
    {:code "KeyG" :shift? true} :rf.xray/follow-head
    {:key "j"}                  :rf.xray/focus-event-prev
    {:code "KeyJ"}              :rf.xray/focus-event-prev
    {:key "k"}                  :rf.xray/focus-event-next
    {:code "KeyK"}              :rf.xray/focus-event-next
    {:key ","}                  :rf.xray/settings-toggle
    {:code "Comma"}             :rf.xray/settings-toggle
    {:key "s"}                  :rf.xray/settings-toggle
    {:code "KeyS"}              :rf.xray/settings-toggle))

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
  (is (nil? (spine-key-id (mk-event {:key "x"}))))
  (is (nil? (spine-key-id (mk-event {}))) "empty event → nil"))

;; ---- (7) Esc dismisses the editor-hint toast -----------------------------
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
;; re-frame runtime (mirrors settings/editor_hint_cljs_test.cljs's fixture).

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
  (setup-xray-runtime!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/editor-hint-show]))
  (let [{:keys [event prevented stopped]} (mk-keydown-event "Escape")]
    (handle-keydown event)
    (is (= [true true] [@prevented @stopped]) "an open toast consumes Esc")))

(deftest esc-falls-through-when-hint-closed
  (setup-xray-runtime!)
  (let [{:keys [event prevented stopped]} (mk-keydown-event "Escape")]
    (handle-keydown event)
    (is (= [false false] [@prevented @stopped])
        "a closed toast leaves Esc to the host and other consumers")))

(deftest editor-hint-open-predicate-reads-frame-app-db
  (setup-xray-runtime!)
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/editor-hint-show]))
  (reset! rf.frame/frames {})
  (is (false? (#'keybinding/editor-hint-open?))
      "with the :rf/xray frame gone, Esc falls through"))

;; ---- (8) held toggle chords must not flap (repeat guard) -----------------
;;
;; `handle-keydown` swallows `.repeat` keydowns for every binding EXCEPT
;; the j / k step keys (which WANT auto-repeat so a held key walks the
;; feed). Without that guard, HOLDING a toggle chord would fire it at the
;; OS key-repeat rate and the shell / palette / mode / live-pause would
;; flap open↔closed. `step-key?` is the exemption predicate.

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
  (is (= [true true true true]
         (mapv #(boolean (step-key? (mk-event %)))
               [{:key "j"} {:key "k"} {:code "KeyJ"} {:code "KeyK"}])))
  (is (= [false false false false]
         (mapv #(boolean (step-key? (mk-event %)))
               [{:key "l"} {:key "j" :ctrl? true} {:key "k" :meta? true} {:key "j" :shift? true}]))
      "every other key, and a modified j / k, is repeat-guarded"))

(deftest held-toggle-chords-are-ignored
  ;; A held toggle fires once per physical press, not per OS repeat tick.
  (doseq [chord [{:key "C" :ctrl? true :shift? true :repeat? true}
                 {:key "k" :ctrl? true :repeat? true}
                 {:key "M" :ctrl? true :shift? true :repeat? true}]]
    (let [{:keys [event prevented stopped]} (mk-spy-event chord)]
      (handle-keydown event)
      (is (= [false false] [@prevented @stopped])
          (str "repeat chord " chord " is ignored")))))

;; ---- (9) Space not hijacked from focused button/summary ------------------
;;
;; `target-editable?` exempts only INPUT/TEXTAREA/SELECT/contenteditable, so
;; on its own it would let a focused shell `<button>` / `<summary>` /
;; `[role=button]` fall into the spine branch: Space would dispatch
;; `:rf.xray/toggle-live-pause` and `.preventDefault`, blocking the
;; control's native Space activation. The `target-activatable?` predicate
;; yields the spine to those controls.

(defn- mk-target-event
  "Synthetic keydown whose `.target` is a fake element with the given
  `tag` (tagName) and optional `role` attribute — enough to drive the
  target-* predicates without a real DOM."
  [{:keys [tag role]}]
  (js-obj "target"
          (js-obj "tagName"      tag
                  "getAttribute" (fn [attr] (when (= attr "role") role)))))

(defn- mk-shell-target-key-event
  "Synthetic keydown whose `.target` is a fake element INSIDE the Xray
  shell: `.closest` resolves the shell testid (so `target-inside-xray?`
  is true) but carries no modal marker. `tag` / `role` drive the
  activatable check; `key` / `code` / `shift?` choose the binding;
  preventDefault / stopPropagation are spied so a test can see whether
  the spine consumed the keystroke.

  The activatable-target exemption is scoped to Space, so the roster
  keys need the same shell-inside target shape as Space to assert they
  are NOT exempted. `key` defaults to Space."
  [{:keys [tag role key code shift?]
    :or   {key " " code "Space" shift? false}}]
  (let [prevented  (atom false)
        stopped    (atom false)
        shell-node (js-obj "id" "fake-shell")
        target     (js-obj "tagName"      tag
                           "getAttribute" (fn [attr] (when (= attr "role") role))
                           "closest"      (fn [sel]
                                            (when (re-find #"rf-xray-shell" sel)
                                              shell-node)))
        event      (js-obj "key"             key
                           "code"            code
                           "target"          target
                           "ctrlKey"  false  "metaKey" false
                           "altKey"   false  "shiftKey" shift?
                           "repeat"          false
                           "preventDefault"  (fn [] (reset! prevented true))
                           "stopPropagation" (fn [] (reset! stopped true)))]
    {:event event :prevented prevented :stopped stopped}))

(deftest target-activatable-compares-tag-names-case-insensitively
  (is (true? (boolean (#'keybinding/target-activatable?
                        (mk-target-event {:tag "button"}))))))

;; ---- the exemption is SPACE'S, not the roster's -------------------------
;;
;; `target-activatable?` exempts Space alone. Placed bare in front of the
;; whole `:else` guard it would exempt every spine key — and the most
;; natural gesture in the tool leaves DOM focus on an activatable control
;; (an L2 row is `role="button"` + `tab-index "0"` and the nav chevrons are
;; `<button>`s, and browsers focus both on mousedown), after which j / k /
;; l / Shift+G / `,` / s would all be dead until the user clicked somewhere
;; inert. The row's own `:on-key-down` handles Enter / Space / ContextMenu /
;; Shift+F10 and nothing else, so the step keys would reach no handler at
;; all. Space (and Enter, which is not a spine key) is the whole of what a
;; native control claims.

(def ^:private spine-roster-minus-space
  "The spine bindings a focused activatable control does NOT claim,
  paired with the id `spine-key-id` maps each to."
  [{:key "j" :code "KeyJ"                :expect :rf.xray/focus-event-prev}
   {:key "k" :code "KeyK"                :expect :rf.xray/focus-event-next}
   {:key "l" :code "KeyL"                :expect :rf.xray/follow-head}
   {:key "G" :code "KeyG" :shift? true   :expect :rf.xray/follow-head}
   {:key "," :code "Comma"               :expect :rf.xray/settings-toggle}
   {:key "s" :code "KeyS"                :expect :rf.xray/settings-toggle}])

(defn- xray-queued-events
  "The event vectors sitting UNDRAINED in `:rf/xray`'s router queue — i.e.
  what the keydown handler just dispatched.

  Observed at the queue rather than through a `with-redefs` on
  `rf/dispatch`: under `:node-test` that redef does not reach the
  compiled call site in `keybinding.cljs`, so a spy reads `[]` on a
  dispatch that demonstrably happened. The queue is the seam
  `epoch_pump_coalescing_cljs_test` also reads for its coalescing
  counts, and it observes the real envelope."
  []
  (mapv :event (:queue @(:router (rf.frame/frame :rf/xray)))))

(deftest spine-roster-survives-focus-on-an-activatable-target
  ;; With focus on a shell <button>, every spine key except Space fires.
  (setup-xray-runtime!)
  (with-redefs [mount/visible? (constantly true)]
    (doseq [{:keys [key code shift? expect]} spine-roster-minus-space]
      (let [before (count (xray-queued-events))
            {:keys [event prevented stopped]}
            (mk-shell-target-key-event {:tag "BUTTON" :key key :code code :shift? (boolean shift?)})]
        (handle-keydown event)
        (is (= [true true [[expect]]]
               [@prevented @stopped (vec (drop before (xray-queued-events)))])
            (str key " is consumed and dispatches " expect))))))

(deftest space-stays-exempt-on-an-activatable-target
  ;; The control for the row above: the Space-only guard is surgical, not absent.
  (setup-xray-runtime!)
  (with-redefs [mount/visible? (constantly true)]
    (doseq [target-spec [{:tag "BUTTON"}
                         {:tag "SUMMARY"}
                         {:tag "DIV" :role "button"}]]
      (let [before (count (xray-queued-events))
            {:keys [event prevented]} (mk-shell-target-key-event target-spec)]
        (handle-keydown event)
        (is (= [false []] [@prevented (vec (drop before (xray-queued-events)))])
            (str "Space on a focused " target-spec " is left to the control"))))))

;; ---- (10) the pop-out document's own listener ----------------------------
;;
;; `mount/popout!` renders a live shell into a SECOND document, and DOM key
;; events do not cross realms — so the opener-document listener never sees
;; a keypress made in the pop-out window, and without a listener of its own
;; the documented keyboard workflow would be inert whenever focus was there.
;;
;; Two things are under test, and they fail in different ways:
;;
;;   * ROUTING. `handle-keydown-on` is surface-parameterised. Every
;;     assertion below pairs the pop-out surface with the OPENER surface on
;;     the identical event, so a test cannot pass by the handler having
;;     become inert — the control shares the shape of the target.
;;   * OWNERSHIP. `install-popout-keydown!` must add exactly one
;;     capture-phase listener to the document it is handed and return a
;;     disposer that removes THAT fn object.
;;
;; The browser-level counterpart (a real second window, real keypresses)
;; lives in the feature-matrix gate (`assertPopoutKeyboard` in
;; `tools/xray/testbeds/feature_matrix/scenarios.cjs`).

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
  ;; `mount/visible?` reports on the opener's in-app shell. The opener
  ;; refusing the same event is the control that the handler is not inert.
  (setup-xray-runtime!)
  (with-redefs [mount/visible? (constantly false)]
    (doseq [k [{:key " " :code "Space"}
               {:key "j" :code "KeyJ"}
               {:key "k" :code "KeyK"}
               {:key "l" :code "KeyL"}]]
      (let [popout (mk-shell-key-event k)
            opener (mk-shell-key-event k)]
        (handle-keydown-on popout-surface (:event popout))
        (handle-keydown-on opener-surface (:event opener))
        (is (= [true false] [@(:prevented popout) @(:prevented opener)])
            (str k " fires in the pop-out and is refused by the hidden opener"))))))

(deftest popout-palette-does-not-touch-the-opener-shell
  (setup-xray-runtime!)
  (let [toggles (atom 0)
        chord   {:key "k" :code "KeyK" :ctrl? true}]
    (with-redefs [mount/visible? (constantly false)
                  mount/toggle!  (fn [] (swap! toggles inc) nil)]
      (let [{:keys [event prevented]} (mk-shell-key-event chord)]
        (handle-keydown-on popout-surface event)
        (is (= [true 0] [@prevented @toggles])
            "the pop-out opens its palette without reopening the opener's shell"))
      (handle-keydown-on opener-surface (:event (mk-shell-key-event chord)))
      (is (= 1 @toggles) "control: the opener shows its hidden shell first"))))

(deftest popout-shell-toggle-chord-stays-opener-owned
  ;; Ctrl+Shift+C toggles the opener's in-app shell, which the pop-out does
  ;; not have, so there it falls through like any unbound key.
  (setup-xray-runtime!)
  (let [toggles (atom 0)
        chord   {:key "C" :code "KeyC" :ctrl? true :shift? true}]
    (with-redefs [mount/visible? (constantly true)
                  mount/toggle!  (fn [] (swap! toggles inc) nil)]
      (let [{:keys [event prevented stopped]} (mk-shell-key-event chord)]
        (handle-keydown-on popout-surface event)
        (is (= [0 false false] [@toggles @prevented @stopped])))
      (let [{:keys [event prevented]} (mk-shell-key-event chord)]
        (handle-keydown-on opener-surface event)
        (is (= [1 true] [@toggles @prevented]) "control: the opener owns the chord")))))

(deftest popout-mode-chord-routes-through-the-shared-map
  ;; Surface-independent bindings answer identically on both surfaces.
  (setup-xray-runtime!)
  (with-redefs [mount/visible? (constantly true)]
    (doseq [chord [{:key "M" :code "KeyM" :ctrl? true :shift? true}
                   {:key "," :code "Comma"}
                   {:key "s" :code "KeyS"}]]
      (let [popout (mk-shell-key-event chord)
            opener (mk-shell-key-event chord)]
        (handle-keydown-on popout-surface (:event popout))
        (handle-keydown-on opener-surface (:event opener))
        (is (= [true true] [@(:prevented popout) @(:prevented opener)])
            (str chord " is live on both surfaces"))))))

(deftest install-popout-keydown-owns-exactly-one-listener
  (let [{:keys [doc listeners]} (mk-stub-document)
        dispose (keybinding/install-popout-keydown! doc)]
    (let [{:keys [type use-capture]} (first @listeners)]
      (is (= [1 "keydown" true] [(count @listeners) type use-capture])
          "one capture-phase keydown listener, as on the opener document"))
    (dispose)
    (is (zero? (count @listeners))
        "the disposer removed the exact listener it installed")))

(deftest popout-listeners-do-not-accumulate-across-windows
  ;; Each pop-out document owns its listener, so a reopen installs one fresh
  ;; listener rather than stacking handlers.
  (let [a (mk-stub-document)
        b (mk-stub-document)
        dispose-a (keybinding/install-popout-keydown! (:doc a))
        _         (keybinding/install-popout-keydown! (:doc b))]
    (is (= [1 1] [(count @(:listeners a)) (count @(:listeners b))]))
    (is (not (identical? (:handler (first @(:listeners a)))
                         (:handler (first @(:listeners b)))))
        "a fresh closure per document")
    (dispose-a)
    (is (= [0 1] [(count @(:listeners a)) (count @(:listeners b))])
        "disposing one leaves the other")))

(deftest install-popout-keydown-refuses-a-nil-document
  (testing "a pop-out whose document is unreachable installs
            nothing and returns nil rather than throwing; `popout!` stores
            the nil and `teardown-popout-state!` skips disposal."
    (is (nil? (keybinding/install-popout-keydown! nil)))))

(deftest popout-installer-is-registered-with-mount
  (testing "the injection that closes the mount <-> keybinding
            cycle. `mount/popout!` reaches keybinding ONLY through this
            slot, so an unregistered installer means a keyboard-less
            pop-out with nothing else failing."
    ;; Two derefs, not one: the var holds an ATOM, so `@#'` yields the atom
    ;; and the second `@` reads the installer out of it.
    (is (identical? keybinding/install-popout-keydown!
                    @@#'mount/popout-keydown-installer)
        "keybinding registered its installer into mount at load time")))

;; ---- (11) the pop-out listener answers the LIVE slot ---------------------
;;
;; `:rf.xray/keybinding-enabled?` is reactive for the OPENER: the watch at the
;; foot of `keybinding.cljs` calls `attach!` / `detach!` on every flip, so the
;; listener's PRESENCE is the switch there. That watch cannot reach a pop-out
;; listener — its lifetime belongs to `mount/popout!` and
;; `teardown-popout-state!` — so the pop-out handler reads the slot per
;; keystroke. Were it to read the slot only at INSTALL time the switch would be
;; one-way and one-shot in that window: one opened while the slot was true
;; would go on consuming `Cmd/Ctrl+K` and the spine after the host cleared it,
;; and one opened while it was false would stay inert for the life of the
;; window after the host restored it.
;;
;; The rows below drive the ACTUAL INSTALLED HANDLER — the fn object the stub
;; document captured — and not `handle-keydown-on`. That siting is the whole
;; point: this failure lives in the seam between a listener's lifetime and the
;; flag, and a direct call to the shared handler cannot see it — section 10's
;; surface rows would all pass against an install-time read.
;;
;; Each row pairs its disabled reading with an enabled reading on the IDENTICAL
;; event through the IDENTICAL listener, so a handler that had merely gone
;; inert fails the enabled half rather than passing the disabled one.

(defn- installed-popout-handler
  "The fn `install-popout-keydown!` actually handed to `addEventListener` on
  the stub document — the seam under test. nil when nothing was installed."
  [listeners]
  (:handler (first @listeners)))

(defn- press-in-popout!
  "Send `key-spec` through the installed pop-out `handler` and report what the
  keystroke did: whether it was consumed, and what it left on `:rf/xray`'s
  router queue. Reads the queue the way `xray-queued-events`' own docstring
  prescribes — a `with-redefs` spy on `rf/dispatch` does not reach the
  compiled call site under `:node-test`."
  [handler key-spec]
  (let [before                            (count (xray-queued-events))
        {:keys [event prevented stopped]} (mk-shell-key-event key-spec)]
    (handler event)
    {:prevented @prevented
     :stopped   @stopped
     :queued    (vec (drop before (xray-queued-events)))}))

(deftest popout-handler-goes-quiet-when-the-slot-is-cleared
  ;; One listener, never reinstalled, read three times: the handler reads
  ;; the slot per keystroke because the opener's attach!/detach! watch
  ;; cannot reach a pop-out listener.
  (setup-xray-runtime!)
  (let [{:keys [doc listeners]} (mk-stub-document)
        dispose                 (keybinding/install-popout-keydown! doc)
        handler                 (installed-popout-handler listeners)
        chord                   {:key "k" :code "KeyK" :ctrl? true}
        live                    {:prevented true :queued [[:rf.xray/palette-toggle]]}]
    (try
      (is (= live (select-keys (press-in-popout! handler chord) [:prevented :queued])))
      (config/set-keybinding-enabled! false)
      (is (= 1 (count @listeners))
          "the slot does not remove the listener — the disposer owns that")
      (is (= {:prevented false :stopped false :queued []} (press-in-popout! handler chord))
          "disabled: the key is left alone")
      (config/set-keybinding-enabled! true)
      (is (= live (select-keys (press-in-popout! handler chord) [:prevented :queued]))
          "re-enabled: the very same listener is live again")
      (finally
        (config/set-keybinding-enabled! true)
        (when (fn? dispose) (dispose))))))

(deftest popout-opened-while-disabled-goes-live-when-the-slot-returns
  ;; A pop-out opened while the slot was cleared still installs, so it
  ;; answers once the slot returns instead of staying keyboard-less.
  (setup-xray-runtime!)
  (let [{:keys [doc listeners]} (mk-stub-document)
        step                    {:key "j" :code "KeyJ"}]
    (try
      (config/set-keybinding-enabled! false)
      (let [dispose (keybinding/install-popout-keydown! doc)
            handler (installed-popout-handler listeners)]
        (is (fn? dispose)
            "a disposer even with the slot false — mount stores one per pop-out")
        (is (fn? handler) "CONTROL — a real listener for the rows below to drive")
        (when (fn? handler)
          (is (= {:prevented false :queued []}
                 (select-keys (press-in-popout! handler step) [:prevented :queued]))
              "still disabled: j is left alone")
          (config/set-keybinding-enabled! true)
          (is (= {:prevented true :queued [[:rf.xray/focus-event-prev]]}
                 (select-keys (press-in-popout! handler step) [:prevented :queued]))
              "enabled: the listener installed under a false slot drives the spine"))
        (when (fn? dispose)
          (dispose)
          (is (zero? (count @listeners))
              "the disposer removes the listener it installed under a false slot")))
      (finally
        (config/set-keybinding-enabled! true)))))
