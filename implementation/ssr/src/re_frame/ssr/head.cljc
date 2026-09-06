(ns re-frame.ssr.head
  "Head/meta contract façade — `reg-head`, `render-head`, `active-head`
  and `head-model->html`.

  The server-rendered HTML must carry head metadata — `<title>`,
  `<meta>`, `<link>`, JSON-LD — on first byte; crawlers and link-
  unfurlers don't run JS. The pattern's commitment: **the head model
  is data derived from app-db**, not an imperative DOM API.

  The head module decomposes into three concern-per-file siblings + a
  shared HTML-helpers ns:

    - `re-frame.ssr.html-helpers`   — shared HTML escape helpers
                                      (`escape-html` / `escape-attr` /
                                      `attr-string`); consumed by the
                                      hiccup emitter AND the head emitter.
    - `re-frame.ssr.head.emit`      — `head-model->html` and its
                                      per-element emitters.
    - `re-frame.ssr.head.registry`  — `reg-head`, `render-head`,
                                      `active-head`, `default-head`.
    - `re-frame.ssr.head`           — this façade. Re-exports the public
                                      surface and publishes the late-bind
                                      hooks so `(require 're-frame.ssr.head
                                      :reload)` resurrects every
                                      registration after a `clear-all!`.

  Per the optional-artefact wrapper convention (Conventions.md
  §Optional-artefact wrapper convention), each public surface is
  reachable via `re-frame.core` through a late-bind hook so core never
  statically `:require`s `re-frame.ssr.head`."
  (:require [re-frame.late-bind :as rf.late-bind]
            [re-frame.ssr.head.emit :as rf.ssr.head.emit]
            [re-frame.ssr.head.registry :as rf.ssr.head.registry]))

;; ---- public-surface re-exports --------------------------------------------

(def head-model->html    rf.ssr.head.emit/head-model->html)

(def reg-head            rf.ssr.head.registry/reg-head)
(def render-head         rf.ssr.head.registry/render-head)
(def active-head         rf.ssr.head.registry/active-head)
(def default-head        rf.ssr.head.registry/default-head)

;; ---- late-bind hook registration ------------------------------------------
;;
;; Late-bind hooks fire on ns load. Keeping them in the façade (rather
;; than in the producing sub-ns) means that `(require 're-frame.ssr.head
;; :reload)` — the canonical test-fixture reset shape — re-publishes
;; every hook, regardless of which sub-ns happened to define the
;; underlying fn.

(rf.late-bind/set-fn! :ssr/reg-head          reg-head)
(rf.late-bind/set-fn! :ssr/render-head       render-head)
(rf.late-bind/set-fn! :ssr/active-head       active-head)
;; NB: late-bind keys conventionally use `-` only (the drift-detector
;; regex limits its grammar to alpha-numeric + standard symbol chars);
;; the user-facing fn is `head-model->html` but the hook key drops the
;; `->` decoration.
(rf.late-bind/set-fn! :ssr/head-model-html   head-model->html)
