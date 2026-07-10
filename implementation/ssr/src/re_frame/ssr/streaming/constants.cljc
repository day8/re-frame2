(ns re-frame.ssr.streaming.constants
  "Reserved string constants for the STREAMING SSR wire protocol — the
  `data-rf2-suspense-*` attribute names the server emitter
  (`re-frame.ssr.streaming`) stamps and the client runtime
  (`re-frame.ssr.streaming.client`) reads, plus the client-owned live-mount
  element name.

  Pinning these in one ns (rather than scattered literal strings on each
  side of the wire) makes the protocol discoverable and renameable: a
  rename of an attribute name is a one-edit change here, not a grep-driven
  sweep across server, client, and tests. The same single-source convention
  `re-frame.ssr.constants` already established for the non-streaming
  hydration-payload script id (per Spec 011 §Hydration payload script id),
  applied to the streaming surface.

  This is a sibling of `re-frame.ssr.constants` rather than an extension:
  these strings are streaming-specific machinery (the `:rf/suspense-boundary`
  protocol, Spec 011 §Streaming SSR), so they co-locate with the streaming
  namespaces and stay off the lean `constants` ns that the non-streaming
  boot helper (`re-frame.ssr.boot`) already depends on. Renaming a constant here
  changes the on-wire token — see the `attr-*` docstrings for the
  server↔client matching contract before changing a value.")

;; ---- server-emitted suspense-boundary wire attributes ---------------------
;;
;; The server (`re-frame.ssr.streaming/{suspense-template,fallback-template,
;; resolved-template,failed-template,hydrate-delta-script}`) stamps these
;; onto the `<template>` / `<script>` chunk nodes; the client
;; (`re-frame.ssr.streaming.client`) queries for them. The two sides MUST
;; agree on every value — these constants are that agreement.

(def ^:const attr-suspense-id
  "The boundary-id attribute on every suspense `<template>` chunk (fallback
  / resolved / failed) and the matching anchor for the client's swap.
  Carries the hiccup author's `:id` printed via `(str id)` then escaped."
  "data-rf2-suspense-id")

(def ^:const attr-suspense-fallback
  "Marker on the inline fallback `<template>` the shell walk emits, so the
  client knows which templates to materialise into live, visible mounts.
  The `<template>` content is inert until the
  client runtime runs; the marked template is the wire-carrier, the painted
  fallback is the client-materialised `<rf-suspense>` mount, NOT first-byte
  content."
  "data-rf2-suspense-fallback")

(def ^:const attr-suspense-resolved
  "Marker on a resolved-subtree `<template>` flushed when a continuation
  drains. The client swaps the matching fallback mount for this content."
  "data-rf2-suspense-resolved")

(def ^:const attr-suspense-failed
  "Marker added (alongside `attr-suspense-resolved`) when the server's
  continuation render threw — the chunk carries the FALLBACK html and no
  delta. The client still swaps the content but applies no delta and emits
  a `:rf.ssr/suspense-boundary-failed` trace. Per Spec 011 §Failure
  semantics — inline fallback."
  "data-rf2-suspense-failed")

(def ^:const attr-suspense-hydrate
  "The boundary-id attribute on the per-subtree hydration-delta `<script>`
  (`type=\"application/edn\"`). The client reads the bare delta-map EDN body
  and merges it into the target frame's app-db. Per Spec 011 §Hydration
  interleaving."
  "data-rf2-suspense-hydrate")

;; ---- client-owned live-mount element --------------------------------------
;;
;; NOT emitted by the server: the server's fallback `<template>` content is
;; inert (a detached DocumentFragment, never painted). The CLIENT runtime
;; materialises each fallback into a LIVE `<rf-suspense
;; data-rf2-suspense-mount="<id>">…fallback…</rf-suspense>` wrapper on
;; install — the user sees the skeleton, and the wrapper is the stable swap
;; target the resolved chunk replaces in-place. Pinned here so the
;; client-owned half of the protocol shares the streaming wire-constants
;; home rather than scattering its own literals.

(def ^:const attr-suspense-mount
  "The boundary-id attribute on the client-materialised live-mount wrapper.
  Client-owned (the server never emits it) — it is the swap target the
  resolved chunk's content replaces in-place."
  "data-rf2-suspense-mount")

(def ^:const mount-tag
  "The custom-element tag name of the client-materialised live mount.
  Client-owned. A non-standard element name keeps the wrapper out of the
  author's CSS/selector surface."
  "rf-suspense")
