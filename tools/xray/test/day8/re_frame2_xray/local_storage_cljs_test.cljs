(ns day8.re-frame2-xray.local-storage-cljs-test
  "Fail-soft contract for the shared `local-storage` seam (rf2-dwn5yn).

  `local_storage.cljs`'s :33 posture promises a quota error or a
  cross-origin `SecurityError` NEVER poisons the dispatch chain that
  drove a write, and that reads degrade to `nil`. Seven Xray
  namespaces persist a slot through this one seam, so a single
  unguarded throw here propagates into every persistence call-site —
  and (since several fire from init / dispatch hooks) into dispatch.

  The existing `init_filter_reset_cljs_test` covers the HAPPY round-trip
  (an in-memory stub whose methods never throw) and the absent-window
  branch. It does NOT cover the two error edges this file pins:

    (a) the `window.localStorage` PROPERTY access itself throwing — the
        sandboxed-iframe / cross-origin / cookie-blocked `SecurityError`
        case. The browser raises it on the bare `js/window.localStorage`
        read, BEFORE any get/set/remove method is called. `available?`
        reads that property, so an unguarded read there propagates the
        throw out of `available?` — past every primitive's own
        method-level try — and into dispatch.

    (b) the METHOD-level throw — `available?` returns true but
        `getItem` / `setItem` / `removeItem` themselves throw (quota
        exceeded on write; a hostile getItem). Each primitive's own
        `(catch :default _ …)` must swallow these.

  Both edges assert the SAME invariant: `available?`, `get-item`,
  `set-item!`, and `remove-item!` never throw; reads return nil; writes
  / removes no-op and return nil."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.local-storage :as ls]))

;; -------------------------------------------------------------------------
;; window stub install / restore
;; -------------------------------------------------------------------------
;;
;; Node-test has no jsdom, so each scenario installs its own
;; `js/globalThis.window` for the test's duration and tears the whole
;; stub down afterwards (the same teardown shape as
;; `init_filter_reset_cljs_test`), so a leaked window can't bleed the
;; throwing-localStorage into sibling namespaces.

(def ^:private had-window?
  "Whether a `js/globalThis.window` existed BEFORE this ns ran. Node-test
  normally has none; we restore to the original absence on teardown."
  (exists? js/globalThis.window))

(defn- uninstall-window! []
  (when-not had-window?
    (js-delete js/globalThis "window")))

(use-fixtures :each
  {:after uninstall-window!})

(defn- install-window-with-throwing-localStorage-getter!
  "Install a `js/globalThis.window` whose `localStorage` PROPERTY read
  throws a `SecurityError`-shaped error — the sandboxed-iframe /
  cross-origin case. The throw fires on the bare property access, not
  on any method, so it reproduces the seam (b)-vs-(a) distinction
  precisely."
  []
  (let [win #js {}]
    (js/Object.defineProperty
      win "localStorage"
      (js-obj "get" (fn []
                      (throw (js/Error. "SecurityError: localStorage access denied")))
              "configurable" true))
    (set! (.-window js/globalThis) win)))

(defn- install-window-with-throwing-methods!
  "Install a `js/globalThis.window` whose `localStorage` PROPERTY reads
  fine (so `available?` sees a non-nil object) but whose getItem /
  setItem / removeItem METHODS each throw — the quota-exceeded /
  hostile-method case the per-primitive try must swallow."
  []
  (let [throwing #js {:getItem    (fn [_k] (throw (js/Error. "getItem boom")))
                      :setItem    (fn [_k _v] (throw (js/Error. "QuotaExceededError")))
                      :removeItem (fn [_k] (throw (js/Error. "removeItem boom")))}]
    (set! (.-window js/globalThis) #js {:localStorage throwing})))

;; -------------------------------------------------------------------------
;; (a) localStorage PROPERTY access throws (SecurityError / cross-origin)
;; -------------------------------------------------------------------------

(deftest available?-fails-soft-when-property-access-throws
  (testing "a throwing `window.localStorage` getter degrades `available?`
            to false rather than propagating the SecurityError"
    (install-window-with-throwing-localStorage-getter!)
    (is (false? (ls/available?))
        "available? swallows the property-access throw and returns false")))

(deftest get-item-fails-soft-when-property-access-throws
  (testing "get-item never propagates a property-access SecurityError"
    (install-window-with-throwing-localStorage-getter!)
    (is (nil? (ls/get-item "k"))
        "get-item returns nil instead of throwing into the caller")))

(deftest set-item!-fails-soft-when-property-access-throws
  (testing "set-item! never propagates a property-access SecurityError
            into the dispatch chain that drove the write"
    (install-window-with-throwing-localStorage-getter!)
    (is (nil? (ls/set-item! "k" "v"))
        "set-item! no-ops and returns nil")))

(deftest remove-item!-fails-soft-when-property-access-throws
  (testing "remove-item! never propagates a property-access SecurityError"
    (install-window-with-throwing-localStorage-getter!)
    (is (nil? (ls/remove-item! "k"))
        "remove-item! no-ops and returns nil")))

;; -------------------------------------------------------------------------
;; (b) localStorage methods throw (quota / hostile method)
;; -------------------------------------------------------------------------

(deftest available?-is-true-when-only-methods-throw
  (testing "available? sees a non-nil localStorage object even though its
            methods will throw — the (a)/(b) distinction"
    (install-window-with-throwing-methods!)
    (is (true? (ls/available?))
        "the property read succeeds; method throws are a separate concern")))

(deftest get-item-fails-soft-when-getItem-throws
  (testing "a throwing getItem degrades the read to nil"
    (install-window-with-throwing-methods!)
    (is (nil? (ls/get-item "k"))
        "get-item swallows the method throw and returns nil")))

(deftest set-item!-fails-soft-when-setItem-throws
  (testing "a quota-exceeded setItem must not poison the dispatch chain"
    (install-window-with-throwing-methods!)
    (is (nil? (ls/set-item! "k" "v"))
        "set-item! swallows the QuotaExceededError and returns nil")))

(deftest remove-item!-fails-soft-when-removeItem-throws
  (testing "a throwing removeItem no-ops cleanly"
    (install-window-with-throwing-methods!)
    (is (nil? (ls/remove-item! "k"))
        "remove-item! swallows the method throw and returns nil")))
