(ns re-frame.freehand.reactive-false-shell-free-dom-cljs-test
  "SPIKE rf2-3slzz — what a SHELL-FREE boundary actually does, in a real
  browser, to the two site families a ViewCell owns.

  `{:reactive false}` would declare a boundary shell-free: no `v/sub`
  read and no committed event site in its OWN render, so no ViewCell.
  Nothing proves the declaration; the runtime checks it. The JVM sibling
  (`reactive-false-totality-jvm-test`) pins the READ half. This file is
  the EVENT half, plus the two mounted acceptance shapes that only a real
  React commit can answer.

  ## Why `fr/element` is the faithful stand-in for the flag

  A shell-free interpreted boundary would run `(emit nil form)` — the
  interpreted walk with NO candidate threaded. That is exactly what the
  public `fr/element` does (\"There is no boundary above this call, so no
  candidate\"), so the arms below exercise the same fork in
  `handler-proxy` / `host-props` the flag would put a declared body
  through, without shipping the flag.

  ## What they find

  The reactive-READ door refuses. The EVENT door does not: with no
  candidate, a declarative `:on-click [:evt]` is DROPPED — the prop is
  never written, nothing throws, and only the DOM knows. A `v/defhost`
  declared callback degrades the other way: the authored function is
  handed to the foreign component raw, so a `v/event` carrier's returned
  intent is discarded by whoever called it. Both are chokepoints — one
  `(some? cand)` fork each — so a masking assertion context can make them
  RAISE. Until it does, the flag is not total on the event half.

  Rides the browser lane through the `-dom-cljs-test` suffix; under the
  node suites it has no DOM and says so."
  (:require ["react" :as react]
            ["react-dom/client" :as rdc]
            [cljs.test :refer [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.freehand :as v]
            [re-frame.freehand.cell :as cell]
            [re-frame.freehand.react :as fr]
            [re-frame.freehand.root :as root]
            [re-frame.live-frame :as live-frame]
            [re-frame.adapter.uix :as react-substrate]
            [re-frame.test-support :as test-support]))

(def ^:private fid :spike-shell-free/frame)

(def ^:private runtime-fixture
  (test-support/make-reset-runtime-fixture
    {:adapter       react-substrate/adapter
     :ambient-frame nil
     :async?        true}))

(use-fixtures :each
  {:before (fn []
             (root/reset-registry!)
             (fr/reset-boundaries!)
             ((:before runtime-fixture)))
   :after  (fn []
             ((:after runtime-fixture))
             (root/reset-registry!)
             (fr/reset-boundaries!))})

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why] (is true (str "a real React mount needs a DOM host — " why)))

(defn- act
  [thunk]
  (try
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
    (js/Promise.resolve (react/act (fn [] (js/Promise.resolve (thunk)))))
    (catch :default e
      (js/Promise.reject e))))

(defn- host-node! []
  (let [container (js/document.createElement "div")]
    (.appendChild js/document.body container)
    container))

(defn- register! []
  (rf/reg-event :spike/pressed (fn [{:keys [db]} _] {:db (assoc db :pressed true)}))
  (rf/reg-sub :spike/total (fn [db _] (:total db))))

(defn- seed! [db]
  (live-frame/make-frame {:id fid})
  (frame/replace-app-db! fid db)
  fid)

(defn- db [] (frame/frame-app-db-value fid))

(defn- text [container selector]
  (some-> (.querySelector container selector) .-textContent))

;; ---------------------------------------------------------------------------
;; The bodies
;; ---------------------------------------------------------------------------

(defn- helper-sub
  "An ORDINARY defn that performs the read. No analyzer sees through it,
  which is the whole point: this is the shape the flag's runtime check has
  to catch and a build-time proof cannot."
  []
  (str (v/sub [:spike/total])))

(v/defview elided-but-reads
  "DECLARED `{:compiled true}` and PROVED inert — its body carries no
  lexical `sub` site, so the analyzer elides the ViewCell — yet it reads
  through an ordinary helper. This is the compiler's OWN proof being
  defeated, and what stops it becoming a silently stale view is the
  runtime candidate check, the very check `{:reactive false}` would
  rely on."
  {:compiled true}
  [_]
  [:p#leak (helper-sub)])

(v/defview reactive-child
  "An ordinary reactive interpreted boundary. It owns its own shell
  wherever it is mounted."
  [_]
  [:span#child (str (v/sub [:spike/total]))])

(v/defview shell-free-parent
  "Elided by proof, and it mounts a reactive child. The child's
  reactivity must be untouched by the parent having no shell."
  {:compiled true}
  [_]
  [:div#parent [reactive-child {}]])

(v/defview elided-snapshot-read
  "Elided by proof, and it performs the NON-reactive read the flag's
  contract names as staying legal: the ambient 1-arity
  `rf/subscribe-once`. The analyzer records no site for it — it is an
  ordinary function call, not a `sub` — so the ViewCell is elided, and
  the ambient form then has no frame scope to resolve against."
  {:compiled true}
  [_]
  [:p#snapshot (str (rf/subscribe-once [:spike/total]))])

(v/defview owning-button
  "The CONTROL for the event arms: the identical authored markup inside a
  boundary that DOES own a shell, so the site is committed and the click
  dispatches."
  [_]
  [:button#owned {:on-click [:spike/pressed]} "go"])

;; ===========================================================================
;; 1 — the read door, in the real shell-free mount
;; ===========================================================================

(deftest a-shell-free-boundary-refuses-a-helper-mediated-read-at-first-render
  (testing "The compiled analyzer proved this body inert — no lexical
            `sub` site, `:view-cell :elided` — and it was WRONG, because
            the read reaches the shell through an ordinary helper the
            analysis cannot see through. The mount does not produce a
            silently stale page: the read finds no candidate and raises
            `:rf.error/view-read-outside-render` at the first offending
            render. The check `{:reactive false}` would depend on is
            therefore already load-bearing for the compiled tier's own
            elision."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (register!)
        (seed! {:total 41})
        (is (= :elided (:view-cell (v/manifest elided-but-reads)))
            "non-vacuous: the analysis really did elide the ViewCell for this body")
        (let [container (host-node!)]
          (-> (act #(v/mount [elided-but-reads {}] container {:frame fid}))
              ;; The REJECTION is this row's success path — the mount must be
              ;; refused — so the two handlers are SIBLINGS of one two-arg
              ;; `.then`, which states "exactly one of these runs" directly. A
              ;; `.catch` downstream of a `done` would instead claim a later
              ;; namespace's throw as this row's and fire `done` twice
              ;; (rf2-fyba). The shared container detach rides the single
              ;; trailing step; the unmount is fulfilment-only and stays.
              (.then (fn [mounted]
                       (is false
                           (str "the shell-free mount SUCCEEDED and rendered "
                                (pr-str (text container "#leak"))
                                " — a read with no owner was not refused"))
                       (when mounted (.unmount (.-react-root ^root/Root mounted))))
                     (fn [e]
                       (is (= :rf.error/view-read-outside-render
                              (:rf.error/id (ex-data e)))
                           (str "refused loudly at the read; got " (pr-str e)))))
              (.then (fn [_] (.remove container) (done)))))))))

(deftest a-shell-free-boundary-still-resolves-a-frame-for-a-snapshot-read
  (testing "The ruling keeps `rf/subscribe-once` legal under the flag — a
            snapshot the programmer asked for — and this checks that the
            promise survives losing the shell, because it was not obvious
            that it would. The shell's `cell/with-capture` binds
            `frame/*current-frame*`, and a shell-free boundary gets no
            such binding; the reason the ambient 1-arity still resolves
            is the OTHER scope tier. `frame/resolve-current-frame` reads
            the React frame CONTEXT through the `:adapter/current-frame`
            hook, and a context value is available to any component
            rendering under the provider `v/mount` installs — shell or no
            shell. So the snapshot read resolves the mounting frame and
            returns its value.

            What the boundary does NOT acquire is a `useContext`
            SUBSCRIPTION to that context, because it calls no hook. It
            performs no reactive read either, so there is nothing for a
            provider retarget to invalidate — and its reactive children
            each own their own `useContext`. See
            `a-reactive-child-of-a-shell-free-parent-keeps-its-own-shell`."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (register!)
        (seed! {:total 41})
        (is (= :elided (:view-cell (v/manifest elided-snapshot-read)))
            "non-vacuous: a subscribe-once is not a site, so the cell is elided")
        (let [container (host-node!)]
          (-> (act #(v/mount [elided-snapshot-read {}] container {:frame fid}))
              (.then (fn [mounted]
                       (is (= "41" (text container "#snapshot"))
                           "the ambient one-shot read resolved the mounting frame
                            through the React frame context, with no shell above it")
                       ;; Fulfilment-only: there is no root to unmount on the
                       ;; rejection arm. The container detach is shared.
                       (when mounted (.unmount (.-react-root ^root/Root mounted)))))
              (.catch (fn [e]
                        (is false
                            (str "the snapshot read failed in a shell-free boundary: "
                                 (pr-str e)))
                        nil))
              (.then (fn [_] (.remove container) (done)))))))))

;; ===========================================================================
;; 2 — a reactive child of a shell-free parent stays reactive
;; ===========================================================================

(deftest a-reactive-child-of-a-shell-free-parent-keeps-its-own-shell
  (testing "Acceptance 4. The parent's analysis elided its ViewCell; the
            child declares a subscription and therefore owns one. React
            renders the child in its own component, so the parent having
            no shell cannot reach it: the child observes the current
            value and repaints when it moves."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (register!)
        (seed! {:total 41})
        (let [container (host-node!)]
          (-> (act #(v/mount [shell-free-parent {}] container {:frame fid}))
              (.then (fn [mounted]
                       (is (= :elided (:view-cell (v/manifest shell-free-parent)))
                           "non-vacuous: the parent really is shell-free")
                       (is (= "41" (text container "#child"))
                           "the child observed the current value under a shell-free parent")
                       (-> (act (fn []
                                  (frame/replace-app-db! fid {:total 42})
                                  (cell/flush!)))
                           (.then (fn [_]
                                    (is (= "42" (text container "#child"))
                                        "and it repainted when the value moved")
                                    ;; Fulfilment-only. The nested chain is
                                    ;; RETURNED into the outer one, which finishes.
                                    (when mounted
                                      (.unmount (.-react-root ^root/Root mounted))))))))
              (.catch (fn [e] (is false (str "shell-free parent mount rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

;; ===========================================================================
;; 3 — the event door, with no candidate: SILENT LOSS
;; ===========================================================================

(defn- click! [container selector]
  (some-> (.querySelector container selector) .click))

(deftest a-declarative-intent-with-no-candidate-is-silently-dropped
  (testing "THE HOLE ON THE EVENT HALF. `(emit nil form)` is the walk a
            shell-free boundary performs. A declarative `:on-click
            [:spike/pressed]` reached there is classified, finds no
            candidate to own it, and `handler-proxy` answers nil — so the
            prop is never written. Nothing throws, nothing warns, and the
            page renders a button that does nothing. Under the flag this
            position must RAISE; it is a single `(some? cand)` fork, so it
            can."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (register!)
        (seed! {})
        (let [container (host-node!)
              react-root (rdc/createRoot container)]
          (-> (act #(.render react-root
                             (fr/element [:button#orphan {:on-click [:spike/pressed]} "go"])))
              (.then (fn [_]
                       (is (some? (.querySelector container "#orphan"))
                           "the element mounted — the loss is the HANDLER, not the markup")
                       (act #(click! container "#orphan"))))
              (.then (fn [_]
                       (is (nil? (:pressed (db)))
                           "the click dispatched NOTHING — the authored intent vanished")
                       (act #(.unmount react-root))))
              ;; UPSTREAM of the step that finishes: reports and releases, never
              ;; finishes. Downstream it would claim a later namespace's throw as
              ;; this row's and fire `done` a second time (rf2-fyba).
              (.catch (fn [e] (is false (str "orphan mount rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

(deftest the-same-markup-inside-a-shell-owning-boundary-dispatches
  (testing "The CONTROL for the arm above, and the reason it is a LOSS
            rather than a documented no-op: the identical authored
            markup, inside a boundary that owns a shell, commits its site
            and dispatches into the committed frame."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (register!)
        (seed! {})
        (let [container (host-node!)]
          (-> (act #(v/mount [owning-button {}] container {:frame fid}))
              (.then (fn [mounted]
                       ;; The HOT-RELOAD AXIS, pinned where it currently
                       ;; stands. `shell-signature` is
                       ;; `[lowering (:view-cell (manifest view))]`, and an
                       ;; interpreted declaration has no manifest — so today
                       ;; every interpreted view signs `[:interpreted nil]`.
                       ;; `{:reactive false}` must move that second element
                       ;; (the compiled tier already signs `[:compiled
                       ;; :elided]` vs `[:compiled :present]`), which is what
                       ;; mints a new component type and buys the ONE clean
                       ;; remount acceptance 5 asks for. Nothing else has to
                       ;; be built for it.
                       (is (= [:interpreted nil]
                              (:signature (get (fr/boundary-cache)
                                               (:view-id (v/describe owning-button)))))
                           "the interpreted shell signature is the axis the flag must move")
                       (-> (act #(click! container "#owned"))
                           (.then (fn [_]
                                    (is (true? (:pressed (db)))
                                        "the committed site dispatched into the frame")
                                    ;; Fulfilment-only. The nested chain is RETURNED
                                    ;; into the outer one, which finishes.
                                    (when mounted
                                      (.unmount (.-react-root ^root/Root mounted))))))))
              (.catch (fn [e] (is false (str "owned mount rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

(deftest a-v-event-carrier-with-no-candidate-loses-its-conversion
  (testing "The `v/event` spelling degrades on the same fork: its whole
            point is a committed site to dispatch through, there is none,
            so `handler-proxy`'s no-candidate arm writes no prop at all
            (`events/unsited` classifies it as needing the site it cannot
            have). The conversion the author declared never runs against a
            dispatcher. The assertion pins the OBSERVABLE — no dispatch —
            because that is what an application sees."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (register!)
        (seed! {})
        (let [container (host-node!)
              react-root (rdc/createRoot container)]
          (-> (act #(.render react-root
                             (fr/element
                               [:button#carrier
                                {:on-click (v/event [_] [:spike/pressed])}
                                "go"])))
              (.then (fn [_]
                       (is (some? (.querySelector container "#carrier"))
                           "the element mounted")
                       (act #(click! container "#carrier"))))
              (.then (fn [_]
                       (is (nil? (:pressed (db)))
                           "the declared conversion never reached a dispatcher")
                       (act #(.unmount react-root))))
              (.catch (fn [e]
                        ;; A THROW here is a perfectly good outcome for the
                        ;; spike's purposes — it would mean the position
                        ;; already fails loud. Record which happened, and
                        ;; RELEASE: the trailing step below finishes.
                        (is (some? (:rf.error/id (ex-data e)))
                            (str "the carrier position raised rather than degraded: "
                                 (pr-str (ex-data e))))
                        nil))
              (.then (fn [_] (.remove container) (done)))))))))

(deftest a-bare-function-with-no-candidate-keeps-working-and-loses-its-site
  (testing "The THIRD degradation, and the most dangerous of the three
            for an OPT-IN, because it looks like success. A bare function
            at a native `:on-*` is one of the three values
            `handler-proxy`'s no-candidate arm hands back (a `v/raw-fn`
            and a `v/render-fn` are the others, per rf2-nzmuy) — so it is
            attached as an ordinary DOM handler and it FIRES. What a bare
            function silently loses is everything
            a committed site is: the frame the commit bound, `:once`,
            retirement at unmount, the controlled-input door verdict, and
            a stable identity across re-renders. An author who wrongly
            declared a boundary shell-free would get no signal at all
            here — which is exactly why this position has to raise rather
            than pass the function through."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (register!)
        (seed! {})
        (let [fired      (atom 0)
              container  (host-node!)
              react-root (rdc/createRoot container)]
          (-> (act #(.render react-root
                             (fr/element
                               [:button#bare {:on-click (fn [_] (swap! fired inc))} "go"])))
              (.then (fn [_] (act #(click! container "#bare"))))
              (.then (fn [_]
                       (is (= 1 @fired)
                           "the bare function fired — nothing signalled that the
                            boundary above it owns no commit")
                       (is (nil? (:pressed (db)))
                           "non-vacuous: no re-frame site was involved; this is a raw
                            DOM callback with none of D008's identity laws")
                       (act #(.unmount react-root))))
              (.catch (fn [e] (is false (str "bare-fn mount rejected: " e)) nil))
              (.then (fn [_] (.remove container) (done)))))))))

;; ===========================================================================
;; 4 — the declared-host callback door, with no candidate
;; ===========================================================================

(defn- pick-panel
  "A foreign React component that CALLS the callback prop it was handed and
  ignores whatever it answers — the ordinary foreign contract, and the
  reason a returned intent vector is not a dispatch."
  [js-props]
  (let [on-select (aget js-props "onSelect")]
    (react/createElement
      "button"
      #js {:id "host" :onClick (fn [_] (when (fn? on-select) (on-select "x")))}
      "pick")))

(v/defhost pick-host
  "A declared crossing with one `:event` callback position."
  pick-panel
  {:callbacks {:onSelect :event}
   :children  :none
   :ssr       :client-only})

(deftest a-declared-host-callback-with-no-candidate-is-handed-over-raw
  (testing "The SECOND no-candidate fallback: `host-props` answers
            `events/callback-fn` — the authored function with EXACTLY its
            supplied identity and none of the site's guarantees. A
            `v/event` carrier's body then answers an intent vector to a
            foreign caller that discards return values, so the declared
            conversion is silently inert. Under the flag this position
            must raise too; like the element fork it is one
            `(some? cand)` test."
    (if-not (browser?)
      (skip! "the browser job runs the mount assertions")
      (async done
        (register!)
        (seed! {})
        (let [container (host-node!)
              react-root (rdc/createRoot container)]
          (-> (act #(.render react-root
                              (fr/element
                                [pick-host {:onSelect (v/event [_] [:spike/pressed])}])))
              (.then (fn [_]
                       (is (some? (.querySelector container "#host"))
                           "the host crossed and mounted")
                       (act #(click! container "#host"))))
              (.then (fn [_]
                       (is (nil? (:pressed (db)))
                           "the host called the raw carrier fn; the intent it answered
                            was discarded and nothing dispatched")
                       (act #(.unmount react-root))))
              (.catch (fn [e]
                        ;; As above: a raise is an acceptable outcome here, so this
                        ;; records which happened and RELEASES — the trailing step
                        ;; finishes.
                        (is (some? (:rf.error/id (ex-data e)))
                            (str "the host callback position raised rather than degraded: "
                                 (pr-str (ex-data e))))
                        nil))
              (.then (fn [_] (.remove container) (done)))))))))
