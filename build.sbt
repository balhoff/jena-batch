enablePlugins(JavaAppPackaging)

organization := "org.renci"

name := "jena-batch"

version := "0.5.0"

licenses := Seq("MIT license" -> url("https://opensource.org/licenses/MIT"))

scalaVersion := "2.13.16"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javaOptions += "-Xmx8G"

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full)

val zioVersion     = "2.1.16"
val zioJsonVersion = "0.7.39"
val jenaVersion    = "6.0.0"

libraryDependencies ++= {
  Seq(
    "dev.zio"                    %% "zio"              % zioVersion,
    "dev.zio"                    %% "zio-streams"      % zioVersion,
    "dev.zio"                    %% "zio-json"         % zioJsonVersion,
    "com.outr"                   %% "scribe-slf4j2"    % "3.16.1",
    "com.github.alexarchambault" %% "case-app"         % "2.0.6",
    "org.apache.jena"             % "apache-jena-libs" % jenaVersion exclude ("org.slf4j", "slf4j-log4j12"),
    "org.apache.jena"             % "jena-shex"        % jenaVersion,
    "dev.zio"                    %% "zio-test"         % zioVersion % Test,
    "dev.zio"                    %% "zio-test-sbt"     % zioVersion % Test
  )
}

dockerBaseImage := "eclipse-temurin:21"
