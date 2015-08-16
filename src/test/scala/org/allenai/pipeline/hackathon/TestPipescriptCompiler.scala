package org.allenai.pipeline.hackathon

import org.allenai.common.testkit.UnitSpec
import org.allenai.pipeline.Pipeline

import scala.io.Source

import java.io.File

class TestPipescriptCompiler extends UnitSpec {
  "pipescript compiler" should "successfully parse and resolve a variable command" in {
    val program =
      """set {x: "http://www.foo.com"}
        |package {id: "pkg1", source: s"${x}"}
        |package {id: "pkg2", source: s"$x"}
      """.stripMargin
    val parser = new PipescriptCompiler
    val parsed = parser.compileScript(program)
  }

  it should "successfully parse the sample vision workflow" in {
    val resourceUrl = {
      val url = this.getClass.getResource("/pipeline/vision-workflow.pipe")
      require(url != null, "Could not find resource.")
      url
    }
    val visionWorkflow = Source.fromURL(resourceUrl).mkString

    val parser = new PipescriptCompiler
    val workflow = parser.compileScript(visionWorkflow)

    assert(workflow.packages.size === 1)
    assert(workflow.stepCommands.size === 4)
  }

  it should "build a pipeline from a script" in {
    val resourceUrl = {
      val url = this.getClass.getResource("/pipeline/vision-workflow.pipe")
      require(url != null, "Could not find resource.")
      url
    }
    val scriptText = Source.fromURL(resourceUrl).mkString

    val parser = new PipescriptCompiler()
    val script = parser.compileScript(scriptText)
  }

  it should "run a pipeline from a script" in {
    val resourceUrl = {
      val url = this.getClass.getResource("/pipeline/vision-workflow.pipe")
      require(url != null, "Could not find resource.")
      url
    }
    val scriptText = Source.fromURL(resourceUrl).mkString

    val dir = new File(new File("pipeline-output"), "RunScript")
    val pipeline = new PipescriptPipeline(Pipeline(dir)).buildPipeline(scriptText)
    pipeline.run("RunFromScript", None)
  }
}