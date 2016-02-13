// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import akka.event.slf4j.SLF4JLogging
import org.apache.commons.vfs2._
import org.apache.commons.vfs2.impl.DefaultFileMonitor

import org.ensime.vfs._

import scala.collection.Set
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try
import org.ensime.util.file._
import java.io.File
import java.util.zip.ZipFile

trait FileChangeListener {
  def fileAdded(f: FileObject): Unit
  def fileRemoved(f: FileObject): Unit
  def fileChanged(f: FileObject): Unit
  def baseReCreated(f: FileObject): Unit = {}
  def baseRemoved(f: FileObject): Unit = {}
}

trait Watcher {
  def shutdown(): Unit
}

/**
 * One watcher per directory because we need to restart the watcher if
 * the directory is deleted.
 */
/**
 * One watcher per directory because we need to restart the watcher if
 * the directory is deleted.
 */
private class Java7Watcher(
  watched: File,
  selector: ExtSelector,
  recursive: Boolean,
  listeners: Seq[FileChangeListener]
)(
  implicit
  vfs: EnsimeVFS
) extends Watcher {
  private val base = vfs.vfile(watched).getName.getURI
  import org.ensime.filewatcher.FileMonitor
  @volatile private var fm = create()

  def create(): FileMonitor = {
    @volatile var fm1 = new FileMonitor
    selector.include foreach (fm1.addSelector(_))

    val fileListener = new org.ensime.filewatcher.FileListener {
      def fileAdded(f: File): Unit = {
        listeners foreach (_.fileAdded(vfs.vfile(f)))
      }
      def fileChanged(f: File): Unit = {
        listeners foreach (_.fileChanged(vfs.vfile(f)))
      }
      def fileRemoved(f: File): Unit = {
        if (base == vfs.vfile(f).getName.getURI) {
          //log.info(s"$base (a watched base) was deleted")
          listeners foreach (_.baseRemoved(vfs.vfile(f)))
          // this is a best efforts thing, subject to race conditions
          fm.stop() // the delete stack is a liability
          fm = create()
          init(restarted = true)
        } else
          listeners foreach (_.fileRemoved(vfs.vfile(f)))
      }
      def onOverflow(f: File): Unit = {
        //        log.warn("WatchSevice overflow event")
      }
    }
    fm1.addListener(fileListener)
    fm1
  }

  private def init(restarted: Boolean): Unit = {
    fm.setRecursive(recursive)
    val base = vfs.vfile(watched)

    // we don't send baseReCreated if we create it on startup
    if (watched.mkdirs() && restarted)
      listeners.foreach(_.baseReCreated(base))
    fm.addWatchedDir(watched)
    for {
      file <- if (recursive) watched.tree else watched.children
      fo = vfs.vfile(file)
    } {
      // VFS doesn't send "file created" messages when it first starts
      // up, but since we're reacting to a directory deletion, we
      // should send signals for everything we see. This could result
      // in dupes, but we figure that's better than dropping the
      // message.
      if (restarted && selector.includeFile(fo)) {
        listeners foreach (_.fileAdded(fo))
      }
    }
    fm.start()
  }

  init(restarted = false)

  override def shutdown(): Unit = {
    fm.stop()
  }
}
