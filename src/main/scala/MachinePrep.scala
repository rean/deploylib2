package deploylib

import java.io.{File}
import scala.collection.mutable.{ArrayBuffer}

class MachinePrep(host: String, user: String, privateKeyPath: String = null) {
  val machine = new RemoteMachine(host, user, privateKeyPath)
  var workingDir: String = "~" // Assume home is the working dir

  val preReqPackages: ArrayBuffer[String] = new ArrayBuffer[String]()
  preReqPackages += "git"
  preReqPackages += "emacs24"
  preReqPackages += "g++"
  preReqPackages += "gcc"
  preReqPackages += "make"
  preReqPackages += "finger"

  private def buildCommand(rawCmd: String, logFile: String): String = {
    val cmd: StringBuffer = new StringBuffer()
    // make workingdir a param vs. an instance variable so we can
    // run commands in parallel in different working dirs
    //cmd.append("cd %s && ".format(workingDir))

    cmd.append("2>&1 nohup").append(" ")
      .append(rawCmd).append(" ")
      .append("| tee %s >> /dev/null".format(logFile))
    cmd.toString
  }

  def update_package_repos() = {
    val cmd: StringBuffer = new StringBuffer()
    val outputFname: String = "/tmp/apt-get-update.deploylib.out"

    //cmd.append("2>&1 nohup sudo apt-get update")
      //.append(" | tee %s >> /dev/null".format(outputFname))

    cmd.append(buildCommand("sudo apt-get update", outputFname))

    machine.executeCommand(cmd.toString)
  }

  def installPreReqPackages() = {
    val installPreReqCmd: StringBuffer = new StringBuffer()
     installPreReqCmd.append("2>&1 nohup sudo apt-get install ")

    preReqPackages.foreach{ pkg => { installPreReqCmd.append(pkg).append(" ") }}
    installPreReqCmd.append("-y")
    // Pipe to tee
    installPreReqCmd
      .append(" | tee /tmp/machine-prep-pre-req.deploylib.out >> /dev/null")
    println("Prep install pre-req command: %s"
      .format(installPreReqCmd.toString))

    //machine.executeCommand("sudo apt-get update")
    machine.executeCommand(installPreReqCmd.toString)
  }
}
