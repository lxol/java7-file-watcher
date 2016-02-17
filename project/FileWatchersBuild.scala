import sbt._
import Keys._

import scalariform.formatter.preferences._
import com.typesafe.sbt.SbtScalariform
import SbtScalariform._
import SonatypeSupport._

object FileWatchersBuild extends Build {
  override val settings = super.settings ++ Seq(
    organization := "org.ensime",
    version := "1.0.0-SNAPSHOT",
    ivyLoggingLevel := UpdateLogging.Quiet,
    scalaVersion := "2.11.7",
    scalacOptions in Compile ++= Seq(
      "-encoding", "UTF-8",
      "-target:jvm-1.6",
      "-feature",
      "-deprecation",
      "-Xlint",
      "-Yinline-warnings",
      "-Yno-adapted-args",
      "-Ywarn-dead-code",
      "-Xfuture"
    ) ++ {
      if (scalaVersion.value.startsWith("2.11")) Seq("-Ywarn-unused-import")
      else Nil
    } ++ {
      // fatal warnings can get in the way during the DEV cycle
      if (sys.env.contains("CI")) Seq("-Xfatal-warnings")
      else Nil
    },

    // we must output JRE6 classfiles for safe ensime-server compiles
    // (even though it'll fail at runtime)
    javacOptions in (Compile, compile) ++= Seq(
      "-source", "1.7", "-target", "1.7", "-Xlint:all", "-Werror",
      "-Xlint:-options", "-Xlint:-path", "-Xlint:-processing"
    ),
    javacOptions in doc ++= Seq("-source", "1.7")
  ) ++ sonatype("ensime", "java7-file-watcher", Apache2)

  lazy val root = (project in file(".")).
    enablePlugins(SbtScalariform).
    settings(
      name := "java7-file-watchers",
      ScalariformKeys.preferences := FormattingPreferences().setPreference(AlignSingleLineCaseStatements, true),
      libraryDependencies ++= Seq(
        "com.google.code.findbugs" % "jsr305" % "3.0.1" % "provided",
        "com.googlecode.java-diff-utils" % "diffutils" % "1.3.0",
        "com.google.guava" % "guava" % "18.0",
        "com.h2database" % "h2" % "1.4.190",
        "commons-lang" % "commons-lang" % "2.6",
        "com.typesafe.akka" %% "akka-actor" % "2.3.14",
        "com.typesafe.akka" %% "akka-slf4j" % "2.3.14",
        "com.typesafe.akka" %% "akka-testkit" % "2.3.14",
        "com.typesafe.slick" %% "slick" % "3.1.1",
        "com.zaxxer" % "HikariCP-java6" % "2.3.12",
        "org.apache.commons" % "commons-vfs2" % "2.0",
        "org.apache.lucene" % "lucene-analyzers-common" % "4.7.2",
        "org.apache.lucene" % "lucene-core" % "4.7.2",
        "org.ow2.asm" % "asm-commons" % "5.0.4",
        "org.ow2.asm" % "asm-util" % "5.0.4",
        "org.scala-lang" % "scala-compiler" % scalaVersion.value,
        "org.scala-lang" % "scalap" % scalaVersion.value,
        "org.scala-refactoring" %% "org.scala-refactoring.library" % "0.8.0",
        "org.scalatest" %% "scalatest" % "2.2.5" % "test",
        "org.slf4j" % "jul-to-slf4j" % "1.7.13",
        "org.slf4j" % "slf4j-api" % "1.7.13",
        "org.slf4j" % "slf4j-simple" % "1.7.13"
      )
    )

}
