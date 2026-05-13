(ns day8.re-frame2-causa.keybinding-cljs-test
  "Tests for Causa's global keydown listener (rf2-jbhm5; source rf2-otcbz
  audit recommendation #5).

  Two contract surfaces under test:

  1. **Key predicates.** `causa-toggle-key?` (Ctrl+Shift+C) and
     `copilot-toggle-key?` (Ctrl+Shift+/) are pure functions over a
     KeyboardEvent's surface. They are private to the keybinding ns;
     tests reach in via `#'` var access. Both predicates check the C / /
     key via both `.key` and `.code` (the latter is the IME-active
     fallback), and both reject extra modifiers (meta, alt) — important
     so the macOS Cmd+Shift+C dev-tools shortcut never collides with
     Causa's toggle.

  2. **Idempotency sentinel.** `attach!` holds a private `defonce` atom
     (`attached?`) that survives shadow-cljs `:after-load` reloads. The
     contract: calling `attach!` twice attaches one listener; calling
     `detach!` flips the sentinel back so a subsequent `attach!`
     installs again. We assert the sentinel through the public
     `attached?*` read-accessor and count listener attachments on a
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
            [day8.re-frame2-causa.keybinding :as keybinding]))

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

(defn- causa-toggle-key?
  "Reach the private predicate via var access."
  [event]
  (#'keybinding/causa-toggle-key? event))

(defn- copilot-toggle-key?
  [event]
  (#'keybinding/copilot-toggle-key? event))

;; ---- stub `js/document` --------------------------------------------------
;;
;; The `attach!` / `detach!` helpers are guarded by `(exists?
;; js/document)`, so under bare node-test they would silently no-op and
;; the sentinel would never flip. We install a minimal stub for the
;; duration of `with-stub-document`; counters expose the listener-
;; attach state so we can assert the idempotency contract directly
;; against `addEventListener` invocation counts (belt-and-braces beside
;; the `attached?*` accessor).

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

;; ---- (1) causa-toggle-key? truth table -----------------------------------

(deftest causa-toggle-key-matches-ctrl-shift-c
  (testing "Ctrl+Shift+C is the canonical positive case — uppercase `key`"
    (is (true? (causa-toggle-key?
                 (mk-event {:key "C" :ctrl? true :shift? true})))))
  (testing "lowercase `key` (most browsers report uppercase when Shift is
            held; the predicate accepts either to stay defensive against
            host quirks)"
    (is (true? (causa-toggle-key?
                 (mk-event {:key "c" :ctrl? true :shift? true})))))
  (testing "`code` fallback — IME-active contexts populate only .code"
    (is (true? (causa-toggle-key?
                 (mk-event {:code "KeyC" :ctrl? true :shift? true}))))))

(deftest causa-toggle-key-rejects-missing-modifiers
  (testing "no modifiers"
    (is (false? (causa-toggle-key? (mk-event {:key "C"})))))
  (testing "Ctrl only — Shift missing"
    (is (false? (causa-toggle-key?
                  (mk-event {:key "C" :ctrl? true})))))
  (testing "Shift only — Ctrl missing"
    (is (false? (causa-toggle-key?
                  (mk-event {:key "C" :shift? true}))))))

(deftest causa-toggle-key-rejects-extra-modifiers
  (testing "Ctrl+Shift+Cmd+C — meta blocks (avoids the macOS dev-tools
            Cmd+Shift+C collision the source docstring calls out)"
    (is (false? (causa-toggle-key?
                  (mk-event {:key "C" :ctrl? true :shift? true :meta? true})))))
  (testing "Ctrl+Shift+Alt+C — alt blocks"
    (is (false? (causa-toggle-key?
                  (mk-event {:key "C" :ctrl? true :shift? true :alt? true})))))
  (testing "all modifiers held — both meta + alt block"
    (is (false? (causa-toggle-key?
                  (mk-event {:key   "C"
                             :ctrl? true :shift? true
                             :meta? true :alt? true}))))))

(deftest causa-toggle-key-rejects-wrong-key
  (testing "wrong key letter, right modifiers"
    (is (false? (causa-toggle-key?
                  (mk-event {:key "D" :ctrl? true :shift? true})))))
  (testing "wrong `code`, right modifiers"
    (is (false? (causa-toggle-key?
                  (mk-event {:code "KeyD" :ctrl? true :shift? true})))))
  (testing "C-shaped key but on a different code — only `key` matches"
    ;; The predicate is permissive: matching on `key` is enough. This
    ;; guards against the predicate being tightened to AND both fields.
    (is (true? (causa-toggle-key?
                 (mk-event {:key "C" :code "Digit3"
                            :ctrl? true :shift? true}))))))

(deftest causa-toggle-key-defensive-nil-fields
  (testing "missing key + missing code → no match (defensive)"
    (is (false? (causa-toggle-key?
                  (mk-event {:ctrl? true :shift? true}))))))

;; ---- (2) copilot-toggle-key? truth table ---------------------------------

(deftest copilot-toggle-key-matches-ctrl-shift-slash
  (testing "Ctrl+Shift+/ — most layouts: Shift+/ produces `?`, the
            predicate accepts both raw `/` and shifted `?` per source
            docstring"
    (is (true? (copilot-toggle-key?
                 (mk-event {:key "/" :ctrl? true :shift? true}))))
    (is (true? (copilot-toggle-key?
                 (mk-event {:key "?" :ctrl? true :shift? true})))))
  (testing "`code` fallback — `Slash`"
    (is (true? (copilot-toggle-key?
                 (mk-event {:code "Slash" :ctrl? true :shift? true}))))))

(deftest copilot-toggle-key-rejects-missing-modifiers
  (testing "no modifiers"
    (is (false? (copilot-toggle-key? (mk-event {:key "?"})))))
  (testing "Shift only — Ctrl missing (this is the plain `?` keypress,
            which must not toggle the rail)"
    (is (false? (copilot-toggle-key?
                  (mk-event {:key "?" :shift? true}))))))

(deftest copilot-toggle-key-rejects-extra-modifiers
  (testing "meta blocks"
    (is (false? (copilot-toggle-key?
                  (mk-event {:key "/" :ctrl? true :shift? true :meta? true})))))
  (testing "alt blocks"
    (is (false? (copilot-toggle-key?
                  (mk-event {:key "/" :ctrl? true :shift? true :alt? true}))))))

(deftest copilot-toggle-key-rejects-wrong-key
  (testing "Ctrl+Shift+C must not also trigger the co-pilot toggle"
    (is (false? (copilot-toggle-key?
                  (mk-event {:key "C" :code "KeyC"
                             :ctrl? true :shift? true})))))
  (testing "wrong `code`"
    (is (false? (copilot-toggle-key?
                  (mk-event {:code "KeyA" :ctrl? true :shift? true}))))))

;; ---- (3) toggles are mutually exclusive ----------------------------------

(deftest predicates-are-mutually-exclusive
  (testing "no synthetic event satisfies both predicates simultaneously —
            mutual exclusivity matters because `handle-keydown` uses
            `cond` and a both-match case would silently route to causa
            and drop the copilot path"
    (doseq [event [(mk-event {:key "C" :ctrl? true :shift? true})
                   (mk-event {:key "/" :ctrl? true :shift? true})
                   (mk-event {:key "?" :ctrl? true :shift? true})
                   (mk-event {:code "KeyC" :ctrl? true :shift? true})
                   (mk-event {:code "Slash" :ctrl? true :shift? true})]]
      (is (not (and (causa-toggle-key? event)
                    (copilot-toggle-key? event)))
          (str "event " (js->clj event) " must match at most one predicate")))))

;; ---- (4) attach! / detach! idempotency sentinel --------------------------

(deftest attach-is-idempotent
  (testing "calling attach! twice installs the keydown listener exactly
            once — the contract preventing shadow-cljs :after-load from
            double-firing the toggle"
    (with-stub-document
      (fn [{:keys [listeners]}]
        (is (false? (keybinding/attached?*))
            "baseline — sentinel starts at false (defonce reset by the fixture)")
        (keybinding/attach!)
        (is (true? (keybinding/attached?*))
            "first attach! flips the sentinel")
        (is (= 1 (count @listeners))
            "first attach! installs exactly one listener")
        (keybinding/attach!)
        (is (true? (keybinding/attached?*))
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
        (is (false? (keybinding/attached?*))
            "detach! flips the sentinel back to false")
        (is (zero? (count @listeners))
            "detach! removes the listener")
        (keybinding/attach!)
        (is (true? (keybinding/attached?*))
            "re-attach succeeds after detach")
        (is (= 1 (count @listeners))
            "exactly one listener installed after re-attach")))))

(deftest detach-on-clean-sentinel-is-safe
  (testing "calling detach! when nothing is attached is a no-op (does
            not throw, does not flip the sentinel below false)"
    (with-stub-document
      (fn [{:keys [listeners]}]
        (is (false? (keybinding/attached?*)))
        (keybinding/detach!)
        (is (false? (keybinding/attached?*))
            "sentinel remains false")
        (is (zero? (count @listeners))
            "no listener was added or removed")))))

(deftest attach-without-document-is-safe
  (testing "absence of js/document — node-test baseline — must not
            throw and must not flip the sentinel"
    ;; This is the bare-node-test runtime; no stub installed. The
    ;; (exists? js/document) guard in attach! must short-circuit.
    (when-not (exists? js/document)
      (is (false? (keybinding/attached?*)))
      (keybinding/attach!)
      (is (false? (keybinding/attached?*))
          "without js/document the sentinel must NOT flip — otherwise
          a subsequent stub-driven attach! would falsely think it had
          already wired up"))))
