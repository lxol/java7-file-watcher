import sbt._
import Keys._

import scalariform.formatter.preferences._
import com.typesafe.sbt.SbtScalariform
import SbtScalariform._
import SonatypeSupport._

object FileWatcherBuild extends Build {
  override val settings = super.settings ++ Seq(
    organization := "org.ensime",
    version := "1.0.0-SNAPSHOT",
    scalaVersion := "2.11.7"
  ) ++ sonatype("ensime", "java7-file-watcher", Apache2)

  lazy val root = (project in file(".")).
    settings(Sensible.settings).settings(
      name := "java7-file-watcher",
      javaOptions in Test ++= Seq("-Dlogback.configurationFile=logback-test.xml"),
      libraryDependencies ++= Sensible.testLibs() ++
        Sensible.logback ++ Sensible.guava // ++ Seq(
        //   "org.apache.commons" % "commons-vfs2" % "2.0"
        // )
    )

}
