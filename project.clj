(defproject liveops/thread-test "0.1.0-SNAPSHOT"
  :description "The thread-test is a service which coordinates loading data
     from S3 into Redshift tables"
  :url "https://github.com/liveops/thread-test"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [prismatic/schema "0.4.3"]
                 [liveops/hal9002 "0.3.2-SNAPSHOT"]
                 [liveops/themis "0.1.0-SNAPSHOT"]]
  :manifest {"Git-Commit" ~(System/getenv "GIT_COMMIT")
             "Build-Tag" ~(System/getenv "BUILD_TAG")}
  :profiles {:uberjar {:aot :all
                       :uberjar-name "thread-test.jar"}
             :test {:dependencies [[org.clojars.runa/conjure "2.1.3"]]}}
  :main thread-test.core
  :repositories [["releases" {:url "http://nexus.liveopslabs.com/content/repositories/releases/"
                              :snapshots false}]
                 ["snapshots" {:url "http://nexus.liveopslabs.com/content/repositories/snapshots/"
                               :update :always}]
                 ["thirdparty" {:url "http://nexus.liveopslabs.com/content/repositories/thirdparty/"
                                :snapshots false}]])
