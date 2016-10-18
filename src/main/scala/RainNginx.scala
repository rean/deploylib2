package deploylib

import java.io.{File}
import scala.collection.mutable.{ArrayBuffer}

class RainNginx(host: String = "localhost", user: String = "rtgbim") {
  val machine = new RemoteMachine(host, user)
  def runExpt(repeats: Integer = 5, outputFile: String): Boolean = {
    val rainCmdBase: StringBuffer = new StringBuffer

    rainCmdBase.append("cd %s && java -cp .:./rain.jar:workloads/nginx.jar "
      .format("/home/rtgbim/work/rain.git"))
      .append("radlab.rain.Benchmark config/rain.config.nginx.json")

    for(i <- 0 until repeats) {
      val outputFname = outputFile.format(i)
      val cmd: StringBuffer = new StringBuffer
      cmd.append(rainCmdBase.toString)
        .append(" | tee %s >> /dev/null".format(outputFname))
      println("Repeat %s - output file %s".format(i, outputFname))
      println("Cmd: %s".format(cmd.toString))

      machine.executeCommand(cmd.toString)
    }

    true
  }
}
