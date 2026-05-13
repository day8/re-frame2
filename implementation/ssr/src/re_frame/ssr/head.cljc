(ns re-frame.ssr.head
  "Head/meta contract façade — `reg-head`, `render-head`, `active-head`,
  `head-model->html`, `head-snapshot`. Per Spec 011 §Head/meta contract
  (rf2-4dra9).

  The server-rendered HTML must carry head metadata — `<title>`,
  `<meta>`, `<link>`, JSON-LD — on first byte; crawlers and link-
  unfurlers don't run JS. The pattern's commitment: **the head model
  is data derived from app-db**, not an imperative DOM API.

  ---- file split (rf2-x7g10) ----

  Pre-split this ns was 415 LoC carrying three concerns: the HTML
  escape helpers (duplicated with `re-frame.ssr.emit`), the canonical-
  order head-model emitter, and the registry + render + active +
  per-frame snapshot + late-bind wiring. Per the rf2-x7g10 split
  (audit rf2-asmj1 §H1/Q2):

    - `re-frame.ssr.html-helpers`   — shared HTML escape helpers
                                      (`escape-html` / `escape-attr` /
                                      `attr-string`); consumed by the
                                      hiccup emitter AND the head emitter.
    - `re-frame.ssr.head.emit`      — `head-model->html` and its
                                      per-element emitters.
    - `re-frame.ssr.head.registry`  — `reg-head`, `render-head`,
                                      `active-head`, `default-head`,
                                      `head-snapshots`,
                                      `on-frame-destroyed!`.
    - `re-frame.ssr.head`           — this façade. Re-exports the public
                                      surface and publishes the late-bind
                                      hooks so `(require 're-frame.ssr.head
                                      :reload)` resurrects every
                                      registration after a `clear-all!`.

  Per the optional-artefact wrapper convention (Conventions.md
  §Optional-artefact wrapper convention), each public surface is
  reachable via `re-frame.core` through a late-bind hook so core never
  statically `:require`s `re-frame.ssr.head`."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.ssr.head.emit :as head-emit]
            [re-frame.ssr.head.registry :as registry]))

;; ---- public-surface re-exports --------------------------------------------

(def head-model->html    head-emit/head-model->html)

(def reg-head            registry/reg-head)
(def render-head         registry/render-head)
(def active-head         registry/active-head)
(def default-head        registry/default-head)
(def head-snapshot       registry/head-snapshot)
(def on-frame-destroyed! registry/on-frame-destroyed!)
;; framework-private: the test-fixture reset reaches into the atom by
;; name. Kept as a façade re-export so external test code that grabs
;; the var via `(resolve 're-frame.ssr.head/head-snapshots)` keeps
;; working unchanged.
(def head-snapshots      registry/head-snapshots)

;; ---- late-bind hook registration ------------------------------------------
;;
;; Late-bind hooks fire on ns load. Keeping them in the façade (rather
;; than in the producing sub-ns) means that `(require 're-frame.ssr.head
;; :reload)` — the canonical test-fixture reset shape — re-publishes
;; every hook, regardless of which sub-ns happened to define the
;; underlying fn.

(late-bind/set-fn! :ssr/reg-head          reg-head)
(late-bind/set-fn! :ssr/render-head       render-head)
(late-bind/set-fn! :ssr/active-head       active-head)
(late-bind/set-fn! :ssr/head-snapshot     head-snapshot)
;; NB: late-bind keys conventionally use `-` only (the drift-detector
;; regex limits its grammar to alpha-numeric + standard symbol chars);
;; the user-facing fn is `head-model->html` but the hook key drops the
;; `->` decoration.
(late-bind/set-fn! :ssr/head-model-html   head-model->html)

;; ---- per-request frame teardown -------------------------------------------
;;
;; `re-frame.ssr/on-frame-destroyed!` (under the `:ssr/on-frame-destroyed`
;; late-bind hook) clears `pending-error-traces`, `request-slots`, and
;; `response-slots` per rf2-fcj33 + rf2-jbcmt. The head ns adds per-frame
;; head-snapshot cleanup; we surface our cleanup via a separate
;; `:ssr/head-on-frame-destroyed` hook that `re-frame.ssr`'s teardown
;; fn looks up and invokes.
;;
;; Why a separate hook rather than wrapping `:ssr/on-frame-destroyed`?
;; Load order is unpredictable under the optional-artefact wrapper
;; convention — `re-frame.ssr` `:require`s `re-frame.ssr.head` so the
;; head ns loads BEFORE ssr's body publishes its hook. Any chain we
;; install here would be overwritten when ssr runs its own
;; `(late-bind/set-fn! :ssr/on-frame-destroyed ...)`. The keyed hook
;; sidesteps the ordering issue: ssr's fn invokes ours by key,
;; regardless of which ns loaded first.

(late-bind/set-fn! :ssr/head-on-frame-destroyed on-frame-destroyed!)
