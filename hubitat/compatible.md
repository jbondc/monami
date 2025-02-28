
# Community Compatible

## Drivers

The JSON file (**compatible.json)** lists the drivers that can replace native Hubitat drivers and child components.
The drivers have been tested for compatibility and in most cases can be more reliable then Hubitat drivers. 
More importantly, community compatible drivers are easier to improve, debug and extend.

## Components

The JSON file (**compatible.json)** lists also child components that are compatible and that can be changed using the [Compatible app](#app).

## <a name="lib">Shared library</a>

Explain shared library usage once completed + tested.

### Examples

## <a name="app">App</a>

TODO: Link to Hubitat app (github+hpm)

The Community Compatible app allows to change the driver of a particular device. While Hubitat allows changing a driver, there's no way to know if a particular community driver is compatible and well tested with a given device (zwave, zigbee etc...). Using the app to change a driver limits the choices to those known to be compatible and significantly improves the perceived reliability of a community driver.
 
 Furthermore, many drivers developed by Hubitat and the community use **isComponent: true** when creating child components in a driver. This prevents selecting a different component and makes it hard to improve, debug and extend an existing driver. The goal is to prevent writing from scratch a different driver and making collaboration easier. When drivers use the [shared library](#app) to create child components, it registers the device with the compatible app and allows to change the components of a driver to known compatible ones. 

### Examples

TODO