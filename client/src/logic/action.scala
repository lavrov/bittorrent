package logic
import component.Router

sealed trait Action

object Action {
  case class ServerEvent(payload: String) extends Action
  case class UpdateConnectionStatus(connected: Boolean) extends Action
  case class Navigate(route: Router.Route) extends Action
}
