package dev.polygate;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class DashboardController {
  @GetMapping({
    "/",
    "/login",
    "/dashboard",
    "/guide",
    "/api-keys",
    "/users",
    "/quotas",
    "/models",
    "/logs",
    "/enterprise",
    "/governance",
    "/operations",
    "/settings"
  })
  String index() {
    return "forward:/index.html";
  }
}
