;; Copyright © 2015, JUXT LTD.

(defproject aero "1.1.6"
  :description "A small library for explicit, intentful configuration."
  :url "http://github.com/juxt/aero"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/MIT"}

  :plugins [[lein-shell "0.5.0"]
            [lein-cloverage "1.0.13"]]

  :aliases {"test-all" ["do" ["test"] ["shell" "./lumo-test"]]}
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "patch"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag"]
                  ["deploy" "clojars"]
                  ["vcs" "push"]]
  :eastwood {:namespaces [aero.core aero.alpha.core]}
  :profiles
  {:provided {:dependencies [[org.clojure/clojure "1.8.0"]]}
   :dev {:plugins [[lein-cljfmt "0.5.7"]
                   [jonase/eastwood "0.3.4"]]}})
