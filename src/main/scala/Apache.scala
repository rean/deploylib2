package deploylib

import java.io.{File}
import scala.collection.mutable.{ArrayBuffer}

class Apache(baseUrl: String = "http://mirror.reverse.net/pub/apache/httpd/",
  host: String = "localhost",
  user: String = "rtgbim") {
  val machine = new RemoteMachine(host, user)
  val preReqPackages: ArrayBuffer[String] = new ArrayBuffer[String]()
  preReqPackages += "g++"
  preReqPackages += "make"
  preReqPackages += "zlib1g-dev"
  preReqPackages += "libapr1-dev"
  preReqPackages += "libaprutil1-dev"
  preReqPackages += "libpcre3-dev"

  val configureFlags: ArrayBuffer[String] = new ArrayBuffer[String]()
  // --with-threads --enable-shared --enable-proxy --enable-proxy-http --with-mpm=worker --enable-ssl --enable-env --prefix=/var/www/apache2
  configureFlags.append("--with-threads")
  configureFlags.append("--enable-shared")
  configureFlags.append("--enable-proxy")
  configureFlags.append("--enable-proxy-http")
  configureFlags.append("--with-mpm=worker")
  configureFlags.append("--enable-ssl")
  configureFlags.append("--enable-env")

  def fetch(version: String = "2.4.23",
    scratchDir: String = "/tmp"): String = {

    val fetchUrl = "%s/httpd-%s.tar.gz".format(baseUrl, version)
    println(fetchUrl)
    val targetFile = fetchUrl.split("/").last
    val fullQFile = "%s/%s".format(scratchDir, targetFile)
    val fetchFlags = "-q"

    if (!machine.fileExists(new File(fullQFile))) {
      val fetchCmd = "wget %s %s -O %s".format(fetchFlags, fetchUrl, fullQFile)
      println(fetchCmd)
      machine.executeCommand(fetchCmd)
    } else {
      println("File %s exists - no download".format(fullQFile))
    }

    fullQFile
  }

  def untar(tarball: String, where: String): String = {
    // sudo tar -C /opt -xzvf /tmp/httpd-2.4.23.tar.gz
    val dirname = tarball.split("/").last.stripSuffix(".tar.gz")

    val untarCmd: StringBuffer = new StringBuffer
    untarCmd.append("2>&1 nohup sudo tar -C %s -xzvf %s".format(where, tarball))
      .append(" | tee /tmp/apache-untar.deploylib.out >> /dev/null")

    println(untarCmd.toString)

    machine.executeCommand(untarCmd.toString) match {
      case ExecutionResponse(Some(0), data, "") => "%s/%s".format(where,
        dirname)
      case res: ExecutionResponse => ""
    }
  }

  private def installBuildPreReqs() = {
    val installPreReqCmd: StringBuffer = new StringBuffer()
    installPreReqCmd.append("2>&1 nohup sudo apt-get install ")
    preReqPackages.foreach{pkg => { installPreReqCmd.append(pkg).append(" ") }}
    installPreReqCmd.append("-y")
    // Pipe output to tee
    installPreReqCmd
      .append(" | tee /tmp/apache-install-prereqs.deploylib.out >> /dev/null")
    println("Install pre-req command: %s".format(installPreReqCmd.toString))
    machine.executeCommand(installPreReqCmd.toString)
  }


  def build(sourceDir: String, installDir: String): Boolean = {
    val configureCmd: StringBuffer = new StringBuffer()
    configureCmd.append("cd %s && 2>&1 nohup ./configure ".format(sourceDir))
    configureFlags.foreach{flag => { configureCmd.append(flag).append(" ") }}
    // Add in prefix
    configureCmd.append("--prefix=%s".format(installDir))
    // Pipe output to tee
    configureCmd
      .append(" | tee /tmp/apache-configure.deploylib.out >> /dev/null")
    println("Configure command: %s".format(configureCmd.toString))
    val configureRes = machine.executeCommand(configureCmd.toString)

    // make clean
    val makeCleanCmd: StringBuffer = new StringBuffer
    makeCleanCmd.append("cd %s && 2>&1 nohup make clean".format(sourceDir))
      .append(" | tee /tmp/apache-make-clean.deploylib.out >> /dev/null")
    val makeCleanRes = machine.executeCommand(makeCleanCmd.toString)
    if (makeCleanRes.status != Some(0)) {
      println("Make clean command failed")
      return false
    }

    // make
    val makeCmd: StringBuffer = new StringBuffer
    makeCmd.append("cd %s && 2>&1 nohup make".format(sourceDir))
      .append(" | tee /tmp/apache-make.deploylib.out >> /dev/null")
    val makeRes = machine.executeCommand(makeCmd.toString)
    if (makeRes.status != Some(0)) {
      println("Make command failed")
      return false
    }

    // make install
    val makeInstallCmd: StringBuffer = new StringBuffer
    makeInstallCmd.append("cd %s && 2>&1 nohup make install".format(sourceDir))
      .append(" | tee /tmp/apache-make-install.deploylib.out >> /dev/null")
    val makeInstallRes = machine.executeCommand(makeInstallCmd.toString)
    if (makeInstallRes.status != Some(0)) {
      println("Make install failed")
      return false
    }

    /*
    machine.executeCommand(configureCmd.toString) match {
      case ExecutionResponse(Some(0), _, _) => {
        val makeCmd: StringBuffer = new StringBuffer

        makeCmd.append("cd %s && make clean && make && make install"
          .format(sourceDir))
        // Pipe output to tee
        //makeCmd.append(" | tee /tmp/apache-make.deploylib.out >> /dev/null")
        machine.executeCommand(makeCmd.toString)
      }
      case _ => false
    }
     */
    true
  }

  def install(version: String = "2.4.23",
    installDir: String = "/opt/apache2",
    scratchDir: String = "/tmp"): Boolean = {

    val targetFile = fetch(version, scratchDir)

    if (!machine.fileExists(new File(targetFile))) {
      return false // Fetch failed
    }

    val untarDir = untar(targetFile, scratchDir)
    if (untarDir.length == 0) {
      return false // Untar failed
    }

    // Change ownership and group
    machine.chown(new File(untarDir), user)
    machine.chgrp(new File(untarDir), user)

    //machine.ls(new File(scratchDir))

    installBuildPreReqs()
    machine.mkdir(new File(installDir), true)
    machine.chown(new File(installDir), user)
    machine.chgrp(new File(installDir), user)

    build(untarDir, installDir)

    true
  }
}
