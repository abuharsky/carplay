//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <carplay/carplay_plugin.h>

void fl_register_plugins(FlPluginRegistry* registry) {
  g_autoptr(FlPluginRegistrar) carplay_registrar =
      fl_plugin_registry_get_registrar_for_plugin(registry, "CarplayPlugin");
  carplay_plugin_register_with_registrar(carplay_registrar);
}
