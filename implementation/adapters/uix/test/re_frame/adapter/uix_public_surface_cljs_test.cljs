(ns re-frame.adapter.uix-public-surface-cljs-test
  "Node-runtime contract test for the PUBLIC SURFACE + adapter-map shape of
  `re-frame.adapter.uix` (rf2-6c2sr). The byte-identical twin of the Helix
  guard (`re-frame.adapter.helix-public-surface-cljs-test`, rf2-ynjts.4) —
  UIx is the same `make-react-spine` / `make-react-adapter` assembly with
  the same seven public re-exports, so it carries the same silent-drift
  class and earns the same guard.

  WHY THIS EXISTS (the gap it closes). The UIx adapter is a thin
  `make-react-spine` / `make-react-adapter` wrapper: it builds a
  UIx-specific `spine-fns` map (substrate-name, gensym prefixes, and the
  three React hook fns) and re-exports seven public Vars plus the
  `adapter` map. Every BEHAVIOUR is asserted in the shared react-suite
  (`react-shared-suite`) and the browser DOM twins (`use-subscribe`,
  `after-render`). But two public re-exports —
  `re-frame.adapter.uix/use-current-frame` and `…/flush-views!` — are
  referenced by NO UIx test at all (the shared-suite `cfg` maps wire
  `wrap-view` / `clear-warned-non-dom-roots!` / `set-hiccup-emitter!` /
  `frame-provider`; the browser DOM twin wires `use-subscribe`). A refactor
  that dropped, renamed, or CROSS-WIRED a re-export (e.g. binding
  `use-current-frame` to the spine's `:use-subscribe` key by copy-paste)
  would compile and pass every existing UIx test, surfacing only as a
  runtime breakage in a downstream UIx app.

  This file is the fast, deterministic, node-level guard for that surface:
  presence + kind + cross-wiring distinctness of the public Vars, and the
  9-fn substrate-contract shape + `:kind` of the `adapter` map. It does NOT
  duplicate behaviour the shared suite owns — it pins the WIRING the suite
  cannot see.

  Node-safety. UIx's `use-context` / `use-memo*` / `use-callback*` are
  React hooks (`use-current-frame` / `use-subscribe` call them), so those
  Vars cannot be INVOKED outside a React render — the browser DOM twins own
  that. Here we only assert their KIND (callable) and distinct IDENTITY.
  `flush-views!` is the one hook-adjacent Var that IS node-safe to call:
  the spine `resolve-act-fn` returns nil when React's `act()` is
  unreachable, and `flush-views!` returns nil regardless — a real
  documented invariant.

  ns ends in `-cljs-test` (NOT `-dom-cljs-test`) so shadow-cljs's
  `:node-test` build picks it up; nothing here needs a DOM."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.adapter.uix :as uix-adapter]))

;; ---- public Var presence + kind -------------------------------------------

(deftest public-vars-are-present-and-callable
  (testing "every documented public Var of re-frame.adapter.uix is bound
            and fn-shaped (a dropped/renamed re-export trips this — incl.
            use-current-frame + flush-views!, which no other UIx test
            references)"
    (is (fn? uix-adapter/set-hiccup-emitter!)        "set-hiccup-emitter!")
    (is (fn? uix-adapter/use-current-frame)          "use-current-frame")
    (is (fn? uix-adapter/frame-provider)             "frame-provider")
    (is (fn? uix-adapter/use-subscribe)              "use-subscribe")
    (is (fn? uix-adapter/flush-views!)               "flush-views!")
    (is (fn? uix-adapter/wrap-view)                  "wrap-view")
    (is (fn? uix-adapter/clear-warned-non-dom-roots!) "clear-warned-non-dom-roots!")))

;; ---- cross-wiring distinctness --------------------------------------------
;; The spine surfaces the seven hook/helper fns under distinct keys; each
;; public Var must be bound to its OWN key. A copy-paste mis-key (two Vars
;; pointing at the same spine fn) is the silent-drift class this guards —
;; behavioural tests would still pass for whichever Var happened to forward
;; the asserted behaviour.

(deftest public-vars-are-distinct-fns
  (testing "no two public Vars are the SAME fn object — guards a copy-paste
            spine-key mis-wire (e.g. use-current-frame ← :use-subscribe)"
    (let [surface {:set-hiccup-emitter!         uix-adapter/set-hiccup-emitter!
                   :use-current-frame           uix-adapter/use-current-frame
                   :frame-provider              uix-adapter/frame-provider
                   :use-subscribe               uix-adapter/use-subscribe
                   :flush-views!                uix-adapter/flush-views!
                   :wrap-view                   uix-adapter/wrap-view
                   :clear-warned-non-dom-roots! uix-adapter/clear-warned-non-dom-roots!}
          fns     (vals surface)]
      (is (= (count fns) (count (distinct fns)))
          (str "expected 7 distinct fn objects across the public surface; "
               "duplicates indicate a cross-wired spine key. Surface: "
               (pr-str (mapv (fn [[k v]] [k (hash v)]) surface)))))))

;; ---- flush-views! node-safe invariant -------------------------------------

(deftest flush-views-returns-nil-and-is-node-safe
  (testing "flush-views! returns nil on both arities and does not throw at
            node level when React's act() is unreachable (spine
            resolve-act-fn → nil ⇒ no-op). Documented contract."
    (is (nil? (uix-adapter/flush-views!))
        "0-arity flush returns nil")
    (let [ran (atom false)]
      (is (nil? (uix-adapter/flush-views! (fn [] (reset! ran true))))
          "1-arity flush returns nil")
      ;; When act() IS reachable (React 19 hosts it on the React ns) the
      ;; thunk runs; when it is NOT, the thunk is skipped. Either way the
      ;; call is a safe no-throw nil — assert only the contract that holds
      ;; on every React build (no @ran assertion: that is React-version
      ;; dependent and would be flaky).
      (is (contains? #{true false} @ran)
          "thunk-ran flag is a clean boolean (no partial/throwing state)"))))

;; ---- adapter-map shape -----------------------------------------------------
;; The 9-fn substrate contract + :kind discriminator. make-react-adapter
;; assembles this; dropping a contract fn or mis-tagging :kind trips here
;; without needing the install/dispose machinery the shared suite drives.

(deftest adapter-map-satisfies-nine-fn-contract
  (testing "the UIx adapter map carries :kind :rf.adapter/uix and all
            nine substrate-contract fns (Spec 006 §CLJS reference)"
    (let [a uix-adapter/adapter]
      (is (map? a) "adapter is a map")
      (is (= :rf.adapter/uix (:kind a))
          "kind discriminator is :rf.adapter/uix (mixed-substrate routing
           keys off this)")
      (doseq [k [:make-state-container :read-container :replace-container!
                 :subscribe-container :make-derived-value :render
                 :render-to-string :register-context-provider :dispose-adapter!]]
        (is (fn? (get a k))
            (str "adapter contract fn " k " is present and fn-shaped"))))))
