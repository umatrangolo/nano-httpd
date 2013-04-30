nano-httpd
==========

Very simple web server.

This is no more than a minimal http server implementation that
supports only the GET operation.

Lifecycle
---------

Written in Scala and using SBT.

To compile:

        sbt compile

To assembly:

        sbt assembly

To run:

Running needs one to specify two parameters:

- port number where the server will start to listen
- root folder from where the content will be served

E.g. To start serving content on port 8080 fetching from the ./content folder:

        scala target/scala-2.10/nano-httpd-assembly-0.0.1.jar com.umatrangolo.http.HttpdServer --port 8080 --root content

---
ugo.matrangolo@gmail.com
1-May-2013