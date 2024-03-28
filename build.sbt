val scalaVersion = "2.12.18"

// Extends the Test configuration
lazy val Benchmark = config("bench").extend(Test)

lazy val root = project
  .in(file("."))
  .settings(
    name := "frankenpaxos",
    scalacOptions ++= Seq(
      // This option is needed to get nice Java flame graphs. See [1] for more
      // information.
      //
      // [1]: https://medium.com/netflix-techblog/java-in-flames-e763b3d32166
      "-J-XX:+PreserveFramePointer",
      // These flags enable all warnings and make them fatal.
      "-unchecked",
      "-deprecation",
      "-feature",
      "-Xfatal-warnings"
    ),
    libraryDependencies ++= Seq(
      "com.github.scopt" %% "scopt" % "3.7.0",
      "com.github.tototoshi" %% "scala-csv" % "1.3.5",
      "com.storm-enroute" %% "scalameter" % "0.18",
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion,
      "io.netty" % "netty-all" % "4.1.34.Final",
      "io.prometheus" % "simpleclient" % "0.6.0",
      "io.prometheus" % "simpleclient_hotspot" % "0.6.0",
      "io.prometheus" % "simpleclient_httpserver" % "0.6.0",
      "org.jgrapht" % "jgrapht-core" % "1.1.0",
      "org.scala-graph" %% "graph-core" % "1.12.5",
      "org.scala-graph" %% "graph-core" % "1.12.5",
      "org.scalacheck" %% "scalacheck" % "1.14.0",
      "org.scalactic" %% "scalactic" % "3.0.5",
      "org.scalatest" %% "scalatest" % "3.0.5"
    ),
    Compile / PB.targets := Seq(
      scalapb.gen() -> (sourceManaged in Compile).value
    ),
    Compile / PB.protoSources := Seq(
      file("shared/src/main/scala"),
      file("jvm/src/main/scala")
    ),
    // These settings enable scalameter. See [1].
    //
    // [1]: https://github.com/scalameter/scalameter-examples/blob/master/basic-with-separate-config/build.sbt
    testFrameworks += new TestFramework("org.scalameter.ScalaMeterFramework"),
    Benchmark / parallelExecution := false,
    Benchmark / logBuffered := false
  )
  // These settings enable scalameter. See [1].
  //
  // [1]: https://github.com/scalameter/scalameter-examples/blob/master/basic-with-separate-config/build.sbt
  .configs(Benchmark)
  .settings(
    inConfig(Benchmark)(Defaults.testSettings): _*
  )

