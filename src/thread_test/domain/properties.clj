(ns thread-test.domain.properties
  (:require
    [dilepis.core :as d]))

;; Look up the URI for the S3 bucket. Files are written here by the
;; HistoricalDataSink Service.
;;
;; The bucket will be manually created and named according to convention.
;; Once this is established for an environment it should rarely be changed,
;; but this parameter enables different buckets for different environments to
;; prevent cross-contamination. Bucket config: initial buckets are in
;; "US Standard" region for (foolish?) consistency with other titan S3 buckets.
(d/defprop s3-bucket-name "themis.s3BucketName"
  "titan-b3dev-reporting-events")

(d/defprop s3-archival-bucket-name "themis.s3BucketName"
  "titan-b3dev-reporting-events")

(d/defprop s3-archival-folder "hsitoricalloader.archivalFolder"
  "archive/")
