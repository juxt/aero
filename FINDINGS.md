# Aero — Spec/Code Findings

Reviewed `src/aero/core.cljc` and `src/aero/alpha/core.cljc` against `specs/aero.allium`.

## 1. Unknown tag throws instead of warning (likely bug, high)

**Spec** (`AeroAPI.read-config` ensures): *"Unknown tags are dispatched through the reader multimethod; if no method is registered the literal is returned unchanged with a stderr warning."*

**Code** (`src/aero/core.cljc:42`):
```clojure
:else
(throw (ex-info (... "No reader for tag %s" ...) {:tag tag :value value}))
```
The default reader throws `ex-info`. This is a hard divergence from the ratified spec — applications relying on graceful unknown-tag handling will instead crash at read time.

**Suggested fix:** emit a `*err*` warning and return the original `(tagged-literal tag value)` unchanged; or update the spec if the throw is the intended behaviour.

---

## 2. `#or` uses truthy, not non-nil (likely bug, high)

**Spec** (`or_tag`): *"Resolves to the first non-nil candidate after expansion; resolves to nil only if all are nil."*

**Code** (`src/aero/core.cljc:278`):
```clojure
value
expansion
```
The loop skips any falsey value, so a candidate of literal `false` is treated as nil and discarded. This is observable: `#or [false :fallback]` resolves to `:fallback` rather than `false`.

**Suggested fix:** test `(some? value)` instead of bare `value`, or amend the spec to say "first truthy".

---

## 3. `expand-keys` shadows the outer `ks` path argument (likely bug, high)

**Code** (`src/aero/alpha/core.cljc:151–160`):
```clojure
(defn- expand-keys
  [m opts env ks]
  (loop [ks (keys m)
         m m]
    ...
      (let [... (expand (first ks) opts env ks)]
```
The `loop` rebinding `ks` shadows the outer parameter, so the `ks` passed to `expand` is the **remaining seq of map keys**, not the path vector the rest of the engine expects. Anything that uses `ks` to index into `env` (via `(conj ks k)`) downstream silently corrupts the env map and makes `::incomplete ::path` reporting wrong.

**Suggested fix:** rename the loop binding (e.g. `remaining`) and pass the original `ks` argument to `expand`.

---

## 4. Unresolved `#ref`s are silently dropped from the config (brittle, medium)

**Code** (`src/aero/core.cljc:378–398`): after one failed retry, an unresolved ref logs a stderr WARNING and uses `dissoc-in-kv-seq` / `assoc-in-kv-seq … nil` to remove or null the offending entry, then continues.

**Spec** (`ref_tag`): *"References are recursive; cycles surface as an Incomplete resolution error."* The dropping behaviour is not described. The result is configs that *appear* to read cleanly while quietly missing structure — high blast radius for production misconfiguration.

**Suggested fix:** either throw on unresolved refs (consistent with the cycle promise), or document the prune-and-warn behaviour in the spec and make it opt-in.

---

## 5. Default `:resolver` is `adaptive-resolver`, not listed in spec (logic ambiguity, medium)

**Code** (`src/aero/core.cljc:148`): `default-opts` uses `adaptive-resolver`.

**Spec** (`Resolver`): enumerates `Relative, Resource, Root, Custom, Map` and the README excerpt names *relative* as default. Adaptive (resource → fall back to relative) is observable behaviour callers depend on, but the spec does not mention it.

**Suggested fix:** add an `Adaptive` variant to the `Resolver` enum and update the description.

---

## 6. cljs `#long` / `#double` parsing is lax (inefficiency / correctness, low)

**Code** (`src/aero/core.cljc:66,71`): `js/parseInt (str value)` (no radix) and `js/parseFloat`. Both accept trailing garbage (`"12abc" → 12`) where the JVM `Long/parseLong` throws. Two runtimes silently disagree on the same config.

**Suggested fix:** use `Number(...)` with NaN check, or explicit regex validation, to mirror JVM strictness.

---

## 7. Deferred / realize-deferreds is undocumented in the public surface (business question, informational)

**Code** (`src/aero/core.cljc:25,158,434`): `Deferred` record and `deferred` macro are part of `aero.core`, run unconditionally on every `read-config`.

**Spec**: only `MacroTag` and the alpha pathway describe deferred-style behaviour, and one open question already flags this. Confirm whether `Deferred` is public API (and add an entity), or move it under the alpha boundary.

---

## 8. `#merge` semantics for non-maps / nils unspecified (logic ambiguity, low)

**Code** (`src/aero/core.cljc:101`): `(apply merge values)` silently skips `nil` parts and will return whatever `merge` does with non-map inputs.

**Spec** (`merge_tag`): "left-to-right merge of the given maps after each is expanded" — does not say what happens for `nil`/non-map members.

**Suggested fix:** spec the nil/non-map behaviour, or validate at the reader.
