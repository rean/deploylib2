package deploylib

import java.io.{File}
import scala.collection.mutable.{ArrayBuffer}

class Rain(host: String = "localhost", user: String = "exo") {
  val machine = new RemoteMachine(host, user)
  val rainGitRepoUrl = "https://github.com/rean/rain-workload-toolkit.git"

  def checkoutLatest(parentDirName: String, sourceDir: String,
    branchName: String = "master"): Boolean = {
    val parentDir: File = new File(parentDirName)
    // Check whether the parent dir exists, if not then create it
    if (!machine.dirExists(parentDir)) {
      if (!machine.mkdir(parentDir)) {
        return false
      }
    }

    val buf: StringBuffer = new StringBuffer()
    buf.append(parentDirName).append("/").append(sourceDir)

    if (machine.dirExists(new File(buf.toString))) {
      // cd into dir and do a git pull --rebase origin <branchname>
      val cmd: StringBuffer = new StringBuffer()
      cmd.append("cd %s && git pull --rebase origin %s"
        .format(buf.toString, branchName))
      machine.executeCommand(cmd.toString)
    } else {
      // cd into dir and do a git clone
      val cmd: StringBuffer = new StringBuffer()
      cmd.append("cd %s && git clone %s %s"
        .format(parentDirName, rainGitRepoUrl, sourceDir))
      machine.executeCommand(cmd.toString)
    }

    true
  }
}

object Rain {
  def main(args: Array[String]) {
    val host: String = "localhost"
    val user: String = "rtgbim"

    val loadGen: Rain = new Rain(host, user)
    val parentDir = "/tmp"
    val srcDir = "rain"
    loadGen.checkoutLatest(parentDir, srcDir)
  }
}
