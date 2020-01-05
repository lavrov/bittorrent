package component

import frp.Observable
import logic.{Dispatcher, RootModel}
import material_ui.core._
import material_ui.styles.makeStyles
import slinky.core.FunctionalComponent
import slinky.core.annotations.react
import slinky.web.html._

import scala.scalajs.js.Dynamic

@react
object App {
  case class Props(router: Router, model: Observable[RootModel], dispatcher: Dispatcher)

  private val useStyles = makeStyles(
    theme =>
      Dynamic.literal(
        appBarSpacer = theme.mixins.toolbar,
        container = Dynamic.literal(
          paddingTop = theme.spacing(4),
          paddingBottom = theme.spacing(4)
        ),
        centered = Dynamic.literal(
          textAlign = "center"
        )
      )
  )

  val component = FunctionalComponent[Props] { props =>
    val classes = useStyles()
    div(
      div(className := classes.appBarSpacer.toString),
      main(
        Container(maxWidth = "md", className = classes.container.toString)(
          Connect(props.model.zoomTo(_.connected), props.dispatcher) {
            case (true, _) =>
              props.router.when {
                case Router.Route.Root =>
                  DownloadPanel(props.router)
                case Router.Route.Torrent(_) =>
                  Connect(props.model.zoomTo(_.torrent), props.dispatcher) {
                    case (Some(torrent), dispatcher) =>
                      Torrent(torrent, dispatcher)
                    case _ =>
                      div()
                  }
              }
            case _ =>
              p(className := classes.centered.toString)("Connecting...")
          }
        )
      )
    )
  }
}
