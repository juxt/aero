(ns aero.impl.macro)

;; Code from Christophe Grand's macrovich.
;; Licensed under EPL-1.0
;; Copyright © 2016-2017 Christophe Grand

(defmacro deftime
  [& body]
  (when #?(:clj (not (:ns &env)) :cljs (re-matches #".*\$macros" (name (ns-name *ns*))))
    `(do ~@body)))

(defmacro usetime
  [& body]
  (when #?(:clj true :cljs (not (re-matches #".*\$macros" (name (ns-name *ns*)))))
    `(do ~@body)))
