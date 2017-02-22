;; Copyright © 2015, JUXT LTD.

(defproject aero "1.1.2"
  :description "A small library for explicit, intentful configuration."
  :url "http://github.com/juxt/aero"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}
  :dependencies []
  :plugins [[lein-shell "0.5.0"]]

  :aliases {"test" ["do" ["test"] ["shell" "./lumo-test"]]}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]]}})
