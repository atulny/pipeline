package org.allenai.pipeline

import org.allenai.common.Resource

import spray.json.DefaultJsonProtocol._
import spray.json.{JsString, JsValue, JsonFormat}

import scala.io.Source

import java.net.URI

/** DAG representation of the execution of a set of Producers.
  */
case class Workflow(nodes: Map[String, Node], links: Iterable[Link]) {
  def sourceNodes() = nodes.filter {
    case (nodeId, node) =>
      !links.exists(link => link.toId == nodeId)
  }

  def sinkNodes() = nodes.filter {
    case (nodeId, node) =>
      !links.exists(link => link.fromId == nodeId)
  }

  def errorNodes() = nodes.filter {
    case (nodeId, node) => node.outputMissing
  }
}

/** Represents a PipelineStep without its dependencies */
case class Node(className: String,
  classVersion: String = "",
  srcUrl: Option[URI] = None,
  binaryUrl: Option[URI] = None,
  parameters: Map[String, String] = Map(),
  description: Option[String] = None,
  outputLocation: Option[URI] = None,
  outputMissing: Boolean = false
)

object Node {
  def apply(step: PipelineStep): Node = {
    val stepInfo = step.stepInfo
    val outputMissing = step match {
      case persisted: PersistedProducer[_, _] =>
        !persisted.artifact.exists
      case _ => false
    }
    Node(stepInfo.className,
      stepInfo.classVersion,
      stepInfo.srcUrl,
      stepInfo.binaryUrl,
      stepInfo.parameters,
      stepInfo.description,
      stepInfo.outputLocation,
      outputMissing)
  }
}

/** Represents dependency between Producer instances */
case class Link(fromId: String, toId: String, name: String)

object Workflow {
  def forPipeline(steps: PipelineStep*): Workflow = {
    def findNodes(s: PipelineStep): Iterable[PipelineStep] =
      Seq(s) ++ s.stepInfo.dependencies.flatMap(t => findNodes(t._2))

    val nodeList = for {
      step <- steps
      childStep <- findNodes(step)
    } yield {
      (childStep.stepInfo.signature.id, Node.apply(childStep))
    }

    def findLinks(s: PipelineStepInfo): Iterable[(PipelineStepInfo, PipelineStepInfo, String)] =
      s.dependencies.map { case (name, dep) => (dep.stepInfo, s, name)} ++
          s.dependencies.flatMap(t => findLinks(t._2.stepInfo))

    val nodes = nodeList.toMap

    val links = (for {
      step <- steps
      (from, to, name) <- findLinks(step.stepInfo)
    } yield Link(from.signature.id, to.signature.id, name)).toSet
    Workflow(nodes, links)
  }

  implicit val jsFormat = {
    implicit val linkFormat = jsonFormat3(Link)
    implicit val nodeFormat = {
      implicit val uriFormat = new JsonFormat[URI] {
        override def write(uri: URI): JsValue = JsString(uri.toString)

        override def read(value: JsValue): URI = value match {
          case JsString(uri) => new URI(uri)
          case s => sys.error(s"Invalid URI: $s")
        }
      }
      jsonFormat8(Node.apply)
    }
    jsonFormat2(Workflow.apply)
  }

  private def link(uri: URI) = uri.getScheme match {
    case "s3" | "s3n" =>
      new java.net.URI("http", s"${uri.getHost}.s3.amazonaws.com", uri.getPath, null).toString
    case "file" =>
      new java.net.URI(null, null, uri.getPath, null)
    case _ => uri.toString
  }

  private val DEFAULT_MAX_SIZE = 40
  private val LHS_MAX_SIZE = 15

  private def limitLength(s: String, maxLength: Int = DEFAULT_MAX_SIZE) =
    if (s.size < maxLength) {
      s
    } else {
      val leftSize = math.min(LHS_MAX_SIZE, maxLength / 3)
      val rightSize = maxLength - leftSize
      s"${s.take(leftSize)}...${s.drop(s.size - rightSize)}"
    }

  def renderHtml(w: Workflow): String = {
    val sourceNodes = w.sourceNodes()
    val sinkNodes = w.sinkNodes()
    val errorNodes = w.errorNodes()
    // Collect nodes with output paths to be displayed in the upper-left.
    val outputNodeLinks = for {
      (id, info) <- w.nodes.toList
      path <- info.outputLocation
    } yield {
      s"""<a href="$path">${info.className}</a>"""
    }
    val addNodes =
      for ((id, info) <- w.nodes) yield {
        // Params show up as line items in the pipeline diagram node.
        val paramsText = info.parameters.toList.map {
          case (key, value) =>
            s""""$key=${limitLength(value)}""""
        }.mkString(",")
        // A link is like a param but it hyperlinks somewhere.
        val links =
        // An optional link to the source data.
          info.srcUrl.map(uri => s"""new Link("${link(uri)}","v${if (info.classVersion.nonEmpty) info.classVersion else "src"}")""") ++
              // An optional link to the output data.
              info.outputLocation.map(uri => s"""new Link("${link(uri)}","output")""")
        val clazz = sourceNodes match {
          case _ if sourceNodes contains id => "sourceNode"
          case _ if sinkNodes contains id => "sinkNode"
          case _ if errorNodes contains id => "errorNode"
          case _ => ""
        }
        val linksText = links.mkString(",")
        s"""        g.setNode("$id", {
           |          class: "$clazz",
           |          labelType: "html",
           |          label: generateStepContent("${info.className}",
           |            "${info.description.getOrElse("")}",
           |            [$paramsText],
           |            [$linksText])
           |        });""".stripMargin
      }
    val addEdges =
      for (Link(from, to, name) <- w.links) yield {
        s"""        g.setEdge("$from", "$to", {label: "$name"}); """
      }

    val resourceName = "pipelineSummary.html"
    val resourceUrl = this.getClass.getResource(resourceName)
    require(resourceUrl != null, s"Could not find resource: ${resourceName}")
    val template = Resource.using(Source.fromURL(resourceUrl)) { source =>
      source.mkString
    }
    val outputNodeHtml = outputNodeLinks.map("<li>" + _ + "</li>").mkString("<ul>", "\n", "</ul>")
    template.format(outputNodeHtml, addNodes.mkString("\n\n"), addEdges.mkString("\n\n"))
  }
}
