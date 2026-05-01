# Aero — Findings vs ratified spec

Findings produced by reading `src/aero/**` through the lens of `specs/aero.allium`.

## Likely bugs

### 1. `expand-keys` shadows its `ks` path argument (high)
`src/aero/alpha/core.cljc:151-171`. The function takes a path `ks` parameter, then immediately rebinds it via `(loop [ks (keys m) ...])`. The recursive `(expand (first ks) opts env ks)` therefore passes *the list of remaining map keys* as the path vector. Both `:aero.core/env` entries (used by `#ref`) and `::incomplete::path` diagnostics reported by `resolve-tagged-literals` end up keyed under bogus paths. Suspected to break key-side `#ref`/`#profile`/`#user` chains in maps. Fix: rename the loop binding (e.g. `loop [remaining (keys m) ...]`).
Spec: `resolve_ref`, `resolve_profile`, `resolve_user`, `resolve_hostname`.

### 2. `#or` returns first **truthy**, not first **non-nil** (high)
`src/aero/core.cljc:264-286`. The `value` truthy check skips `false`, contradicting `rule resolve_or` which says "first non-nil element". `#or [false x]` resolves to `x`. Fix: `(some? value)` instead of relying on truthiness.
Spec: `resolve_or`.

### 3. Nested `#include` from a non-file source silently produces a missing-include marker (medium)
`src/aero/core.cljc:84-90, 104-116`. When a child config's source is a `StringReader` (e.g. an earlier missing-include placeholder, or a user-supplied reader), `relative-resolver` catches `IllegalArgumentException` from `(io/file source)`, sets `fl` to nil, and returns the `{:aero/missing-include include}` marker. Failures cascade silently across `#include` boundaries. Fix: surface a structured error or at minimum log; consider preserving an explicit base path through opts.
Spec: `resolve_include`, `no_unresolved_tags`.

### 4. `#envf` silently substitutes `""` for unset env vars (medium)
`src/aero/core.cljc:52-56`. `(map #(str (get-env (str %))) args)` turns missing variables into empty strings before formatting, producing malformed config without warning. Fix: detect nils and either throw or render as `nil`/`"<unset>"` per the policy chosen for finding 5.
Spec: `resolve_envf`, open question on error semantics.

## Logic ambiguity

### 5. Inconsistent error semantics for failed tag resolution (high)
`src/aero/core.cljc:48,52,63-82`. `#env` returns nil on miss; `#long`/`#double` throw raw `NumberFormatException`; `#boolean` silently coerces any non-`"true"` string to false; `#envf` silently formats with `""`. The spec leaves this open (`open question` line 124). Pick one contract (recommend `ex-info` with `{:tag :value :reason}`) and apply uniformly.
Spec: `resolve_env`, `resolve_envf`, `resolve_coercion`, open question.

## Brittle code

### 6. `resolve-tagged-literals` fixed-point loop is opaque (medium)
`src/aero/core.cljc:360-412`. Uses an attempts counter and a `(not (::incomplete? x))` gate that is never true on iteration 0 (initial map sets it to `true`). Failures throw `"Max attempts exhausted"` carrying only `:progress` — no path or tag for the offending node. Hard to diagnose real spec violations of `no_unresolved_tags`. Suggest carrying `::incomplete` into the thrown ex-info and naming the path explicitly.
Spec: `no_unresolved_tags`.

### 7. `#include` re-uses parent opts; map resolver branch drops `source` (medium)
`src/aero/core.cljc:84-90`. The recursive `(read-config ... opts)` keeps the parent's `:resolver` and stale `:source`; `read-config` then overrides `:source` with the new value. The `(map? resolver)` branch ignores `source` entirely, so source-relative resolution silently degrades when callers supply a map resolver. At minimum, document; ideally pass an explicit relative-base through opts.
Spec: `resolve_include`.

## Inefficiency

### 8. Unconditional `realize-deferreds` postwalk on every read (low)
`src/aero/core.cljc:158-160, 414-430`. Every successful `read-config` performs a full postwalk to look for `Deferred` instances even when none exist. For large trees this is a wasted traversal; track whether any Deferred was emitted during resolution and skip the walk when none.
Spec: `Aero.read_config` (performance is unspecified, so a low-priority efficiency note).

## Business questions

### 9. Ratify error contract for failed resolutions
Spec line 124. Code today is a grab-bag (nil / throw / empty-string / silent false). Pick one and amend the spec so callers can rely on it.

### 10. Ratify the warn-and-nil behaviour for unresolved `#ref`
`src/aero/core.cljc:380-398`. The implementation prints `WARNING: Unable to resolve ...` to stderr and replaces the unresolved value with `nil` rather than throwing. This is observable behaviour callers may already depend on. Either ratify in the spec (extend `resolve_ref` and `no_unresolved_tags`) or change the code to throw.
