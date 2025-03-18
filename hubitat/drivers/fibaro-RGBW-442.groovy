/*
 * FIBARO RGBW CONTROLLER 2
 *
 * This driver was created to support advanced features of the Fibaro RGBW controller and the ZEN31 RGBW Dimmer.
 *
 * The Zen31 RGBW dimmer is manufactured by Fibaro and both devices have the same hardware.
 * During testing and use of both devices, I found no difference using this driver.
 *
 * https://manuals.fibaro.com/content/manuals/en/FGRGBW-442/FGRGBW-442-T-EN-1.1.pdf
 *
 * This code contains portions orginally written by Jeff Page (@jtp10181) from his Zen31 driver. 
 * https://community.hubitat.com/t/zooz-zen31-rgbw-dimmer/115212
 *
 * TODO:
 *  - maximum white and color
 *  - gamma correction
 *  - association improvements
 *
 * CHANGELOG:
 *  [1.0] - 2025-02-12 Initial Release (@jbondc)
 *        - Added Preference to create "Child Devices": either for 'white' or 'color' (rgb) device.
 *        - Using the Configure command will re-create child devices based on 'Child Devices' preference (if changed).
 *        - When using an 'Set Effect' or 'Set Scene', the previous RGBW color and dimmer level will be restored once effects are disabled.
 *        - Added option to "Child Devices" preference as 'color channels' (1 child device for each color).
 *        - This is useful in more advanced scenarios where you can use each channel/color as an independant switch or dimmer.
 *        - Added flash capability.
 *        - Removed 'Quick Refresh' preference and enabled by default. 
 *        - Driver will immediately request an update from device even if setlevel or on/off isn't complete due to the 'ramp rate' / dimming duration. Very fast response time.
 *        - When Input Type (IN1...IN4) is set to 'Analog', this driver will create child devices allowing you to read voltage from inputs (using 'Configure' command)
 *
 *  Copyright 2025 Jonathan Bond-Caron
 *  Copyright 2023 Jeff Page
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
*/
import groovy.transform.Field
import groovy.json.JsonOutput
import hubitat.helper.ColorUtils

@Field static final String VERSION = "1.0.0"
@Field static final Map deviceModelNames = ["0902:4000":"FGRGBW442"]

metadata {
	definition (
		name: "FIBARO RGBW Controller 2",
		namespace: "gde",
		author: "Jonathan Bond-Caron",
		importUrl: "https://raw.githubusercontent.com/jbondc/monami/refs/heads/main/hubitat/drivers/fibaro-RGBW-442.groovy"
	) {
		capability "Initialize"
		capability "Configuration"
		capability "Actuator"
		capability "Sensor"
		capability "Switch"
		capability "SwitchLevel"
		capability "ChangeLevel"
		capability "Refresh"
		capability "Flash"
		capability "PowerMeter"
		capability "ColorMode"
		capability "ColorControl"
		capability "LightEffects"

		// Modified from default to add duration argument
		command "startLevelChange", [
			[name:"Direction*", description:"Direction for level change request", type: "ENUM", constraints: ["up","down"]],
			[name:"Duration", type:"NUMBER", description:"Transition duration in seconds"] ]

		command "setRGBW", [
			[name:"Channel*", description:"Select a channel to set", type: "ENUM", constraints: ["Red", "Green", "Blue", "White"]],
			[name:"Level*", type:"NUMBER", description:"Precise value (0-255)" ] ]

		command "setWhite", [
			[name:"Level*", type:"NUMBER", description:"White level to set (0-100)"] ]

		// TODO: 1-5 – user-defined sequence??
		// https://forum.fibaro.com/topic/55538-user-defined-programs-fgrgbw441m-with-hc3/
		// 
		command "setEffectByName", [ [name:"Select Effect*", type: "ENUM", constraints: EFFECT_NAMES] ]

		command "setParameter",[[name:"parameterNumber*",type:"NUMBER", description:"Parameter Number"],
			[name:"value*",type:"NUMBER", description:"Parameter Value"],
			[name:"size",type:"NUMBER", description:"Parameter Size"]]


		// DEBUGGING
		// command "reconfigure"
		// command "test"
		// command "debugShowVars"

		attribute "white", "string"
		attribute "syncStatus", "string"

		fingerprint mfr:"010f", prod:"0902", deviceId:"4000", inClusters:"0x5E,0x55,0x98,0x9F,0x56,0x22,0x6C", deviceJoinName:"FIBARO RGBW Controller 2"
	}

	preferences {
		input "hideParams", "bool",
			title: fmtTitle("Hide Parameter Settings"),
			description: fmtDesc("Turn on and Save to hide the Parameter Settings"),
			defaultValue: false

		configParams.each { param ->
			if (!param.hidden && !hideParams) {
				Integer paramVal = getParamValue(param)
				if (param.options) {
					input "configParam${param.num}", "enum",
						title: fmtTitle("${param.title}"),
						description: fmtDesc("• Parameter #${param.num}, Selected: ${paramVal}" + (param?.description ? "<br>• ${param?.description}" : '')),
						defaultValue: paramVal,
						options: param.options,
						required: false
				}
				else if (param.range) {
					input "configParam${param.num}", "number",
						title: fmtTitle("${param.title}"),
						description: fmtDesc("• Parameter #${param.num}, Range: ${(param.range).toString()}, DEFAULT: ${param.defaultVal}" + (param?.description ? "<br>• ${param?.description}" : '')),
						defaultValue: paramVal,
						range: param.range,
						required: false
				}
			}
		}

		input "childConfig", "enum",
			title: fmtTitle("Create child devices."),
			description: fmtDesc("This allows using child devices as dimmers. Most useful if 'RGBW' colors are not all used (e.g. RGB strip). The child device(s) can be used to turn on/off or dim 12v circuits for other purposes."),
			defaultValue: 0,
			options: [0:"None", 1: "White only", 2: "Color only (RGB)", 3: "White & Color", 29: "Red, Blue, Green & White Channels"] 
			required: true

		// input "maxWhiteLevel", "bool",
		// 	title: fmtTitle("Brightness Correction"),
		// 	description: fmtDesc("Brightness level set on dimmer is converted to fall within the min/max range but shown with the full range of 1-100%"),
		// 	defaultValue: false

		// TODO: remove and add compatibility with association app and use association lib for commands

		/*
               Simplify this.. kind of a pain can't auto-discover other devices with same driver. Need to use an app?
              2nd association group – “RGBW Sync” allows to synchronize state
             of other FIBARO RGBW Controller devices (FGRGBW-442 and FGRGBWM-441) - do not use with other devices.

             Add association to only FGRGBW442|FGRGBWM441 devices
*/

		input "assocEnabled", "bool",
			title: fmtTitle("Show Association Settings"),
			description: fmtDesc("Turn on and Save to show the Association Settings"),
			defaultValue: false
		
		if (assocEnabled) {
			input "assocDNI2", "string",
				title: fmtTitle("Device Associations - Group 2 (ZEN31 Sync)"),
				description: fmtDesc("Supports up to ${maxAssocNodes} Hex Device IDs separated by commas. Check device documentation for more info. Save as blank or 0 to clear."),
				required: false

			for(int i in 3..maxAssocGroups) {
				Integer inNum = Math.round((i-2)/2)
				Integer oe = i % 2
				input "assocDNI$i", "string",
					title: fmtTitle("Device Associations - Group $i (IN$inNum " + (oe ? "On/Off" : "Dimming") + ")"),
					description: fmtDesc("Supports up to ${maxAssocNodes} Hex Device IDs separated by commas. Check device documentation for more info. Save as blank or 0 to clear."),
					required: false
			}
		}

		// Logging options similar to other Hubitat drivers
		input "txtEnable", "bool", title: fmtTitle("Enable Description Text Logging?"), defaultValue: true
		input "debugEnable", "bool", title: fmtTitle("Enable Debug Logging?"), defaultValue: true
	}
}

//Preference Helpers
String fmtDesc(String str) {
	return "<div style='font-size: 85%; font-style: italic; padding: 1px 0px 4px 2px;'>${str}</div>"
}
String fmtTitle(String str) {
	return "<strong>${str}</strong>"
}

void debugShowVars() {
	log.warn "paramsList ${paramsList.hashCode()} ${paramsList}"
	log.warn "paramsMap ${paramsMap.hashCode()} ${paramsMap}"
	log.warn "settings ${settings.hashCode()} ${settings}"
}

//Association Settings
@Field static final int maxAssocGroups = 10
@Field static final int maxAssocNodes = 5

// Child devices to create
@Field static Map<String, Map> childDimmers = ["white": 1, "color": 2, "red": 4, "green": 8, "blue": 16]

@Field static boolean quickRefresh = true

//Main Parameters Listing
@Field static Map<String, Map> paramsMap =
[
	powerFailure: [ num: 1,
		title: "Behavior After Power Failure",
		size: 1, defaultVal: 0,
		options: [1:"Restores Last Status", 0:"Forced to Off", 1:"Forced to On"],
	],
	//Input Types
	swIn1: [ num: 20,
		title: "Input Type (IN1)",
		size: 1, defaultVal: 2,
		options: [0:"Analog with no Pull-up", 1:"Analog with Pull-up", 2:"Momentary Switch", 3:"Toggle Switch", 4:"On/Off Switch"],
	],
	swIn2: [ num: 21,
		title: "Input Type (IN2)",
		size: 1, defaultVal: 2,
		options: [0:"Analog with no Pull-up", 1:"Analog with Pull-up", 2:"Momentary Switch", 3:"Toggle Switch", 4:"On/Off Switch"],
	],
	swIn3: [ num: 22,
		title: "Input Type (IN3)",
		size: 1, defaultVal: 2,
		options: [0:"Analog with no Pull-up", 1:"Analog with Pull-up", 2:"Momentary Switch", 3:"Toggle Switch", 4:"On/Off Switch"],
	],
	swIn4: [ num: 23,
		title: "Input Type (IN4)",
		size: 1, defaultVal: 2,
		options: [0:"Analog with no Pull-up", 1:"Analog with Pull-up", 2:"Momentary Switch", 3:"Toggle Switch", 4:"On/Off Switch"],
	],
	//Scene Control
	sceneIn1: [ num: 40,
		title: "Scene Events (IN1)",
		size: 1, defaultVal: 11,
		options: [0:"Disabled", 11:"Enable Supported"],
	],
	sceneIn2: [ num: 41,
		title: "Scene Events (IN2)",
		size: 1, defaultVal: 11,
		options: [0:"Disabled", 11:"Enable Supported"],
	],
	sceneIn3: [ num: 42,
		title: "Scene Events (IN3)",
		size: 1, defaultVal: 11,
		options: [0:"Disabled", 11:"Enable Supported"],
	],
	sceneIn4: [ num: 43,
		title: "Scene Events (IN4)",
		size: 1, defaultVal: 11,
		options: [0:"Disabled", 11:"Enable Supported"],
	],
	//Power Reporting
	powerFrequency: [ num: 62,
		title: "Power (Watts) Reporting Frequency",
		size: 2, defaultVal: 0,
		description: "[0 = Disabled]  Minimum number of seconds between reports",
		range: "0,30..32400",
	],
	voltageThreshold: [ num: 63,
		title: "Sensor Voltage (V) Reporting Threshold",
		size: 2, defaultVal: 0,
		description: "[1 = 0.1V, 100 = 10V]  Report when changed by this amount",
		range: 0..100
	],
	voltageFrequency: [ num: 64,
		title: "Sensor Voltage (V) Reporting Frequency",
		size: 2, defaultVal: 0,
		description: "[0 = Disabled]  Minimum number of seconds between reports",
		range: "0,30..32400"
	],
	energyThreshold: [ num: 65,
		title: "Energy (kWh) Reporting Threshold",
		size: 2, defaultVal: 0,
		description: "[1 = 0.01kWh, 100 = 1kWh]  Report when changed by this amount",
		range: 0..500
	],
	energyFrequency: [ num: 66,
		title: "Energy (kWh) Reporting Frequency",
		size: 2, defaultVal: 0,
		description: "[0 = Disabled]  Minimum number of seconds between reports",
		range: "0,30..32400"
	],
	//Other Settings
	switchMode: [ num: 150,
		title: "RGBW / HSB Wall Switch Mode",
		description: "See Zooz advanced settings docs for more info",
		size: 1, defaultVal: 0,
		options: [0:"RGBW Mode", 1:"HSB Mode"],
	],
	rampRate: [ num: 151,
		title: "Physical Ramp Rate to Full On/Off",
		size: 2, defaultVal: 3,
		options: [0:"Instant On/Off"] //rampRateOptions
	],
	zwaveRampRate: [ num: 152,
		title: "Z-Wave Ramp Rate to Full On/Off",
		size: 2, defaultVal: 3,
		options: [0:"Instant On/Off"] //rampRateOptions
	],

	// Use Command to Change
	// presetPrograms: [ num: 157,
	// 	title: "Preset Special Effects",
	// 	size: 1, defaultVal: 0,
	// 	options: [0:"Disabled", 6:"Fireplace", 7:"Storm", 8:"Rainbow", 9:"Polar Lights", 10:"Police"],
	// ],
]

/* ZEN31
CommandClassReport - class:0x22, version:1    (Application Status)
CommandClassReport - class:0x26, version:4    (Multilevel Switch)
CommandClassReport - class:0x31, version:11   (Multilevel Sensor)
CommandClassReport - class:0x32, version:3    (Meter)
CommandClassReport - class:0x33, version:3    (Color Switch)
CommandClassReport - class:0x55, version:2    (Transport Service)
CommandClassReport - class:0x56, version:1    (CRC-16 Encapsulation)
CommandClassReport - class:0x59, version:2    (Association Group Information (AGI))
CommandClassReport - class:0x5A, version:1    (Device Reset Locally)
CommandClassReport - class:0x5B, version:3    (Central Scene)
CommandClassReport - class:0x5E, version:2    (Z-Wave Plus Info)
CommandClassReport - class:0x60, version:4    (Multi Channel)
CommandClassReport - class:0x6C, version:1    (Supervision)
CommandClassReport - class:0x70, version:1    (Configuration)
CommandClassReport - class:0x71, version:8    (Alarm)
CommandClassReport - class:0x72, version:2    (Manufacturer Specific)
CommandClassReport - class:0x73, version:1    (Powerlevel)
CommandClassReport - class:0x75, version:2    (Protection)
CommandClassReport - class:0x7A, version:4    (Firmware Update Meta Data)
CommandClassReport - class:0x85, version:2    (Association)
CommandClassReport - class:0x86, version:2    (Version)
CommandClassReport - class:0x8E, version:3    (Multi Channel Association)
CommandClassReport - class:0x98, version:1    (Security 0)
CommandClassReport - class:0x9F, version:1    (Security 2)
*/
// Set Command Class Versions
@Field static final Map commandClassVersions = [
	0x26: 4,	// switchmultilevelv4
	0x31: 11,   // Multilevel Sensor
	0x32: 3,    // meterv3
	0x33: 3,    // switchColorV3
	0x6C: 1,	// supervisionv1
	0x60: 4,	// multichannelv4
	0x70: 1,	// configurationv1
	0x71: 8,    // notificationv8
	0x72: 2,	// manufacturerspecificv2
	0x85: 2,	// associationv2
	0x86: 2,	// versionv2
	0x8E: 3 	// multichannelassociationv3
]

/*** Static Lists and Settings ***/
@Field static final Map COLOR_CID_TO_NAME = [0:"white", 2: "red", 3: "green", 4: "blue"]
@Field static final Map COLOR_NAME_TO_CID = ["white": 0, "red": 2, "green": 3, "blue": 4]
@Field static final Map EFFECT_NAMES = [0:"Disabled", 6:"Fireplace", 7:"Storm", 8:"Rainbow", 9:"Polar Lights", 10:"Police"]

/*******************************************************************
 ***** Core Functions
********************************************************************/
void installed() {
	logDebug "installed..."
	initialize()
}

void initialize() {
	logDebug "initialize..."

	if(!state.on || !state.on.RGBW) {
		initializeState()
	}
	refresh()
}

void configure() {
	logDebug "configure..."

	sendEvent(name:"lightEffects", value: EFFECT_NAMES)
	updateSyncingStatus()
	configureChildDevices()

	List<String> cmds = getDeviceCmds()
	cmds += getConfigureCmds()
	cmds += getRefreshCmds()

	if (debugEnable) runIn(1800, debugLogsOff)

	if (cmds) sendCommands(cmds)
}

void configureChildDevices() {
	state.device = [:]

	Map configChildDevices = getConfigChildDevices()
	Map keepDevices = [:]

	getChildDevices().each{
		if(configChildDevices[it.deviceNetworkId]) {
			// keep child device 
			keepDevices[it.deviceNetworkId] = true
		} else {
			// remove child device not in config
			deleteChildDevice(it.deviceNetworkId)
		}
	}

	configChildDevices.each{dni, child ->
		if( keepDevices[dni] ) {
			state.device[child.code] = [dni: dni]
		} else {
			try {
				if(child.ns) {
					addChildDevice(child.ns, child.name, dni, child.data)
				} else {
					addChildDevice(child.name, dni, child.data)
				}
				state.device[child.code] = [dni: dni]

			} catch (e) {
				String msg = "Failed to create child device '${child.code}' (${child.name}): ${e.toString()}"
				log.warn msg
				sendEvent(
					descriptionText: msg,
					eventType: "ALERT",
					name: "childDeviceCreation",
					value: e.toString(),
					displayed: true,
				)
			}
		}
	}
}

void updated() {
	logDebug "updated..."
	logDebug "Debug logging is: ${debugEnable == true}"
	logDebug "Description logging is: ${txtEnable == true}"

	if(!state.on || !state.on.RGBW) {
		initializeState()
		configure()
	} else {
		configureChildDevices()
		// TODO: adjust maxColor(), maxWhite() according to preference
	}

	List<String> cmds = getDeviceCmds()
	cmds += getConfigureCmds()

	if (debugEnable) runIn(1800, debugLogsOff)

	if (cmds) sendCommands(cmds)
}

void refresh() {
	logDebug "refresh..."

	List<String> cmds = getDeviceCmds()
	cmds += getRefreshCmds()

	if (cmds) sendCommands(cmds, 100)
}

/*******************************************************************
 ***** Driver Commands
********************************************************************/
/*** Capabilities ***/

def on() {
	logDebug "on..."
	flashStop()

	List<String> cmds = getOnCmds()

	return delayBetween(cmds, 100)
}

private getOnCmds() {
	List<String> cmds = []

	updateColors = null

	["red", "green", "blue", "white"].each{
		if(state.on.RGBW[it] != state.RGBW[it]) {
			updateColors = state.on.RGBW
		}
	}

	if(updateColors) {
		cmds << colorUpdateCmd([red: updateColors.red, green: updateColors.green, blue: updateColors.blue, white: updateColors.white])
		if (quickRefresh) { cmds += getColorQuickRefreshCmds() }
	}

	cmds += getOnOffCmds(0XFF)

	return cmds
}


def off() {
	logDebug "off..."
	state.on.RGBW = state.RGBW
	if(state.switch.targetValue) {
		state.on.last.switchLevel = state.switch.targetValue
	}
	flashStop()
	return delayBetween(getOnOffCmds(0x00), 100)
}

def setLevel(level, duration=null) {
	logDebug "setLevel($level, $duration)..."

	level = getDimmerLevel(level)

	return delayBetween(getSetLevelCmds(level, duration), 100)
}

def startLevelChange(direction, duration=null) {
	Boolean upDown = (direction == "down") ? true : false
	Integer durationVal = validateRange(duration, getParamValue("zwaveRampRate"), 0, 127)
	logDebug "startLevelChange($direction) for ${durationVal}s"

	return switchMultilevelStartLvChCmd(upDown, durationVal)
}

def stopLevelChange() {
	logDebug "stopLevelChange()"
	return switchMultilevelStopLvChCmd()
}

// Button commands required with capabilities
void push(buttonId) { sendBasicButtonEvent(buttonId, "pushed") }
void hold(buttonId) { sendBasicButtonEvent(buttonId, "held") }
void release(buttonId) { sendBasicButtonEvent(buttonId, "released") }
void doubleTap(buttonId) { sendBasicButtonEvent(buttonId, "doubleTapped") }

void setSaturation(percent) {
	logDebug "setSaturation(${percent})"
	sendCommands(getColorCmds([saturation:percent]))
}

void setHue(value) {
	logDebug "setHue(${value})"
	sendCommands(getColorCmds([hue:value]))
}

void setColor(cMap) {
	logDebug "setColor(${cMap})"
	sendCommands(getColorCmds(cMap))
}

void setEffect(efNum) {
	logDebug "setEffect(${efNum})"
	efNum = safeToInt(efNum)
	String efName = EFFECT_NAMES[efNum]
	if (efName != null) {
		logDebug "Set Scene [${efNum} : ${efName}]"
		sendCommands(configSetGetCmd([num:157, size:1], efNum))
	}
	else {
		logWarn "setEffect(${efNum}): Invalid Effect Number"
	}
}

void setNextEffect() {
	List keys = EFFECT_NAMES.keySet().sort()
	Integer newEfNum = state.effectNumber + 1
	if (!keys.contains(newEfNum)) newEfNum = keys[1]
	sendCommands(configSetGetCmd([num:157, size:1], newEfNum))
}
void setPreviousEffect() {
	List keys = EFFECT_NAMES.keySet().sort()
	Integer newEfNum = state.effectNumber - 1
	if (!keys.contains(newEfNum) || newEfNum <= 0) newEfNum = keys.pop()
	sendCommands(configSetGetCmd([num:157, size:1], newEfNum))
}

// Flashing Capability
void flash(Number rate = 1500) {
	flashStop()
	if(rate <= 0)
		return

	logInfo "Flashing with rate of ${rate}ms"

	// Default: 1500ms, min rate of 1s, max of 30s
	rate = validateRange(rate, 1500, 1000, 30000)
	state.switch.flash = [rate: rate, status: device.currentValue("switch")]

	// Start the flashing
	flashFunction(state.switch.flash)
}

void flashStop() {
	if(state.switch.flash != null) {
		state.switch.flash = null
		logInfo "Flashing stopped..."
		unschedule("flashFunction")
	}
}

void flashFunction(Map fun) {
	if (fun.status == "on") {
		fun.status = "off"
		if(state.switch.flash != null) {
			state.switch.flash = fun
			sendCommands(getSetLevelCmds(0xFF, 0))
			runInMillis(fun.rate, flashFunction, [data:fun])
		}
	}
	else if (fun.status == "off") {
		fun.status = "on"
		if(state.switch.flash != null) {
			state.switch.flash = "on"
			sendCommands(getSetLevelCmds(0x00, 0))
			runInMillis(fun.rate, flashFunction, [data:fun])
		}
	}
}

/*** Custom Commands ***/
void reconfigure() {
	// Clears state & variables + recreate child devices
	clearVariables()
	initializeState()
	removeChildDevices(getChildDevices())

	// TODO: create child based on previous name + label + driverType ${cd.displayName} (${cd.deviceNetworkId})"
	// getZwaveHubNodeId() -- for association

	configure()
}

void initializeState() {
	maxColor = maxColorLevel(255)
	maxWhite = maxWhiteLevel(255)

	state.RGBW = [red: 0, green: 0, blue: 0, white: 0]
	state.on =
	 [
		color: false,
		white: false,
		red: false,
		green: false,
		blue: false,
		// child device levels (0-255) when turning back on switch
		RGBW: [red: maxColor, green: maxColor, blue: maxColor, white: maxWhite],
		// last non-zero value for restoring individual color channels/components 
		last: [red: maxColor, green: maxColor, blue: maxColor, white: maxWhite, color: null, switchLevel: 100] 
	]

	state.color = [level: 0]
	state.white = [level: 0]
	state.switch = [level: 0, on: false, flash: null]
	state.update = [:]
	state.device = [:]
}

void setRGBW(String componentName, value) {
	logTrace "setRGBW($componentName, $value)"

	componentName = componentName.toLowerCase();

	Map update = [:]
	update[componentName] = value

	List<String> cmds = []
	cmds << colorUpdateCmd(update)
	
	if(cmds) {
		cmds += getColorQuickRefreshCmds()
		sendCommands(cmds)
	}
}

void test() {
	List<String> cmds = []

	Map<String> info = [activatedDebug: false]
	if(!debugEnable) {
		info.activatedDebug = true
		debugEnable = true
	}

	logDebug "test() start..."

	// Voltage (V)
	cmds << secureCmd(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 0x0F, scale:0), 6)
	//cmds << secureCmd(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 0x0F, scale:1), 7)
	//cmds << secureCmd(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 0x0F, scale:1), 8)
	//cmds << secureCmd(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 0x0F, scale:1), 9)

	sendCommands(cmds, 200)

	// Run test done() after commands ahave been sent
	runInMillis(cmds.size()*200, testdone, [data: info])
}

void testdone(info)
{
	logDebug "test() end..."

	if(info.activatedDebug) {
		debugEnable = false
	}
}

void setWhite(level) {
	logDebug "setWhite($level)"
	sendCommands(getWhiteCmds(level))
}

void setScene(String efName) {
	Short paramVal = EFFECT_NAMES.find{ efName.equalsIgnoreCase(it.value) }.key
	logDebug "Set Scene [${paramVal} : ${efName}]"
	sendCommands(configSetGetCmd([num:157, size:1], paramVal))
}

void refreshParams() {
	List<String> cmds = []
	for (int i = 1; i <= maxAssocGroups; i++) {
		cmds << associationGetCmd(i)
	}

	configParams.each { param ->
		cmds << configGetCmd(param)
	}

	if (cmds) sendCommands(cmds)
}

String setParameter(paramNum, value, size = null) {
	Map param = getParam(paramNum)
	if (param && !size) { size = param.size	}

	if (paramNum == null || value == null || size == null) {
		logWarn "Incomplete parameter list supplied..."
		logWarn "Syntax: setParameter(paramNum, value, size)"
		return
	}
	logDebug "setParameter ( number: $paramNum, value: $value, size: $size )" + (param ? " [${param.name}]" : "")
	sendCommands(configSetGetCmd([num: paramNum, size: size], value as Integer))
}

// Where to store these? Configurable?
Integer maxColorLevel(base = null) {
	level = 100
	if(base)
		return Math.round((level * base) / 100)

	return level
}
Integer maxWhiteLevel(base = null) {
	level = 100
	if(base)
		return Math.round((level * base) / 100)

	return level
}

/*** Child functions for 'white' or 'color' dimmers ***/
void componentOn(cd) {
	logTrace "componentOn from ${cd.displayName} (${cd.deviceNetworkId}) (on state: ${state.on.RGBW})"

	name = getChildNameFromId(cd.deviceNetworkId)

	// If switch if off, make sure we turn on only the requested color(s)
	if(!state.switch.on) {
		if(name == 'color') {
			state.on.RGBW.white = 0;
		} else {
			['red', 'green', 'blue', 'white'].each{
				if(it != name) {
					state.on.RGBW[it] = 0;
				}
			}
		}

	}

	switch(name) {
		case 'white':
			value = state.on.RGBW.white
			break

		case 'color':
			hsv = getColorHSV(state.on.RGBW)
			value = hsv.level
			break

		case 'red':
		case 'green':
		case 'blue':
			value =  state.on.RGBW[name]
			break
	}

	componentOnSetValue(name, value)
}

void componentOnSetValue(name, value) {
	List<String> cmds = []

	switch(name) {
		case 'white':
			state.on.RGBW.white = value
			if(!state.on.RGBW.white) {
				state.on.RGBW.white = state.on.last.white ?: maxWhiteLevel(255)
			}
			cmds = getOnCmds()
			break

		case 'color':
			hsv = state.on.color ? getColorHSV(state.RGBW) : getColorHSV(state.on.RGBW)
			hsv.level = value

			if(!hsv.level) {
				// Get color from last level
				if(!state.on.last.color) {
					state.on.last.color = maxColorLevel(255) + "," + maxColorLevel(255) + "," + maxColorLevel(255)
				}
				rgb = state.on.last.color.split(',')
				hsv = getColorHSV([red: rgb[0].toInteger(), green: rgb[1].toInteger(), blue: rgb[2].toInteger()])
			}

			rbg = ColorUtils.hsvToRGB([hsv.hue, hsv.saturation,  hsv.level])

			state.on.RGBW.red = rgb[0]
			state.on.RGBW.green = rgb[1]
			state.on.RGBW.blue = rgb[2]
			cmds = getOnCmds()
			break

		case 'red':
		case 'green':
		case 'blue':
			 state.on.RGBW[name] =  value
			if(!state.on.RGBW[name]) {
				state.on.RGBW[name] = state.on.last[name] ?: maxColorLevel(255)
			}
			cmds = getOnCmds()
			break
	}

	if(cmds) sendCommands(cmds)
}


void componentSwitchOff() {
	sendHubCommand(new hubitat.device.HubMultiAction(off(), hubitat.device.Protocol.ZWAVE))
}

void componentOff(cd) {
	logTrace "componentOff from ${cd.displayName} (${cd.deviceNetworkId}) (on state: ${state.on.RGBW})"

	List<String> cmds = []
	name = getChildNameFromId(cd.deviceNetworkId)
	switch(name) {
		case 'white':
			state.on.RGBW.white = state.RGBW.white
			if(state.RGBW.white > 0) 
				state.on.last.white = state.RGBW.white

			// Color is off, turn off switch 
			if(!state.on.color) {
				componentSwitchOff()
				return
			}
			cmds += getWhiteCmds(0)
			break

		case 'color':
			hsv = getColorHSV(state.RGBW)
			['red', 'green', 'blue'].each{
				state.on.RGBW[it] = state.RGBW[it]
				if(hsv.level > 0) {
					state.on.last[it] = state.RGBW[it]
				}
			}

			if(!state.on.white) {
				componentSwitchOff()
				return
			}

			hsv.level = 0
			cmds += getColorCmds(hsv)
			break

		case 'red':
		case 'green':
		case 'blue':
			state.on.RGBW[name] = state.RGBW[name]
			if(state.RGBW[name] > 0)
				state.on.last[name] = state.RGBW[name]

			update = [:]
			update[name] = 0
			// When turning off a single color, if color is 'off' and white, then turn off switch
			colorLevel = state.RGBW.red + state.RGBW.green + state.RGBW.blue - state.RGBW[name]
			if(colorLevel == 0 && !state.on.white) {
				componentSwitchOff()
				return
			}

			cmds << colorUpdateCmd(update)
			if (quickRefresh) { cmds += getColorQuickRefreshCmds() }
			break
	}

	logTrace "component OFF White(${state.on.white}) Color(${state.on.color}) (on state: ${state.on.RGBW})"

	if(cmds) {
		sendCommands(cmds)
	}
}

void componentRefresh(cd) {
	logTrace "Refresh from ${cd.displayName} (${cd.deviceNetworkId})"

	List<String> cmds = []

	name = getChildNameFromId(cd.deviceNetworkId)
	switch(name) {
		case 'white':
			cmds += getRefreshWhiteCmds()
			break

		case 'color': 
			cmds += getRefreshColorCmds()
			break
		case 'ep6' :
		case 'ep7' :
		case 'ep8' :
		case 'ep9' :
			ep = name.substring(2).toInteger()
			cmds << secureCmd(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 0x0F, scale:1), ep)
	}

	if(cmds) sendCommands(cmds)
}

// TODO: max levels
void componentSetLevel(cd, level, duration=null) {
	logTrace "componentSetLevel from ${cd.displayName} (${cd.deviceNetworkId})"

	name = getChildNameFromId(cd.deviceNetworkId)

	// No level, turn off color or switch
	if(!level) {
		componentOff(cd)
		return
	}

	// If switch is off, turn on color at a given level 
	if(!state.switch.on) {
		if(name == 'color') {
			value = level
		} else {
			value = Math.round((level * 255) / 100)
		}
		componentOnSetValue(name, value)
		return
	}

	List<String> cmds = []

	switch(name) {
		case 'white':
			color = Math.round((level * 255) / 100)
			cmds += getWhiteCmds(level, duration)
			break
		case 'color':
			hsv = getColorHSV(state.RGBW)
			hsv.level = level
			cmds += getColorCmds(hsv, duration)
			break;
		case 'red':
		case 'green':
		case 'blue':
			color = Math.round((level * 255) / 100)
			update = [:]
			update[name] = color
			cmds << colorUpdateCmd(update, duration)
			if (quickRefresh) { cmds += getColorQuickRefreshCmds() }
			break

	}
	if(cmds.size()) sendCommands(cmds)
}

/*******************************************************************
 ***** Z-Wave Reports
********************************************************************/
void parse(String description) {
	hubitat.zwave.Command cmd = zwave.parse(description, commandClassVersions)

	if (cmd) {
		logTrace "parse: ${description} --PARSED-- ${cmd}"
		zwaveEvent(cmd)
	} else {
		logWarn "Unable to parse: ${description}"
	}

	// Update Last Activity
	updateLastCheckIn()
}

// Decodes Supervision Encapsulated Commands (and replies to device)
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, ep=0) {
	def encapsulatedCmd = cmd.encapsulatedCommand(commandClassVersions)
	logTrace "SupervisionGet ${cmd} --ENCAP-- ${encapsulatedCmd}"

	if (encapsulatedCmd) {
		zwaveEvent(encapsulatedCmd, ep)
	} else {
		logWarn "Unable to extract encapsulated cmd from $cmd"
	}

	sendCommands(secureCmd(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0), ep))
}

void zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd) {
	logTrace "VersionReport ${cmd}"

	String fullVersion = String.format("%d.%02d",cmd.firmware0Version,cmd.firmware0SubVersion)
	device.updateDataValue("firmwareVersion", fullVersion)

	logDebug "Received Version Report - Firmware: ${fullVersion}"
	setDevModel(new BigDecimal(fullVersion))
}

void zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd) {
	logTrace "ConfigurationReport ${cmd}"
	updateSyncingStatus()

	Map param = getParam(cmd.parameterNumber)
	Integer val = cmd.scaledConfigurationValue

	if (param) {
		//Convert scaled signed integer to unsigned
		Long sizeFactor = Math.pow(256,param.size).round()
		if (val < 0) { val += sizeFactor }

		logDebug "${param.name} (#${param.num}) = ${val.toString()}"
		setParamStoredValue(param.num, val)
	}
	else if (cmd.parameterNumber == 157) { //Effects Parameter
		sendEffectEvents(val)
	}
	else {
		logDebug "Parameter #${cmd.parameterNumber} = ${val.toString()}"
	}
}

void zwaveEvent(hubitat.zwave.commands.associationv2.AssociationReport cmd) {
	logTrace "AssociationReport ${cmd}"
	updateSyncingStatus()

	Integer grp = cmd.groupingIdentifier

	if (grp == 1) {
		logDebug "Lifeline Association: ${cmd.nodeId}"
		state.group1Assoc = (cmd.nodeId == [zwaveHubNodeId]) ? true : false
	}
	else if (grp > 1 && grp <= maxAssocGroups) {
		logDebug "Group $grp Association: ${cmd.nodeId}"

		if (cmd.nodeId.size() > 0) {
			state["assocNodes$grp"] = cmd.nodeId
		} else {
			state.remove("assocNodes$grp".toString())
		}

		String dnis = convertIntListToHexList(cmd.nodeId)?.join(", ")
		device.updateSetting("assocDNI$grp", [value:"${dnis}", type:"string"])
	}
	else {
		logDebug "Unhandled Group: $cmd"
	}
}

// Untested, backwards compat.. zwave 500 hub?
void zwaveEvent(hubitat.zwave.commands.basicv1.BasicReport cmd, ep=0) {
	logTrace "BasicReport v1 ${cmd} (ep ${ep})"

	onSwitchReport(cmd, ep)
}

void zwaveEvent(hubitat.zwave.commands.basicv2.BasicReport cmd, ep=0) {
	logTrace "BasicReport v2 ${cmd} (ep ${ep})"

	onSwitchReport(cmd, ep)
}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd, ep=0) {
	logTrace "SwitchMultilevelReport ${cmd} (ep ${ep})"

	onSwitchReport(cmd, ep)
}

void onSwitchReport(cmd, ep) {
	state.switch.targetValue = cmd.targetValue

	if(cmd.hasProperty('duration')) {
		if(cmd.targetValue != cmd.value) {
			Map evt = [name: "levelUpdate", value: "${cmd.value}->${cmd.targetValue}  ${cmd.duration}s", desc: "switch level update, completes in ${cmd.duration}s", isStateChange:true]
			sendEventLog(evt, ep)
		}

		onSwitchEvent(cmd.targetValue, null, ep)
		return
	}

	if(cmd.targetValue == cmd.value && cmd.value == state.switch.level)
		return

	onSwitchEvent(cmd.targetValue, null, ep)
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv11.SensorMultilevelReport cmd, ep=0) {
	logTrace "SensorMultilevelReport (v11) Sensor Type:  ${cmd} ${cmd.sensorType} @ endpoint ${ep} has value ${cmd.scaledSensorValue} "

	endpoint = ep.toInteger() 
	switch(endpoint) {
		case 6..9:
			onVoltageInputEvent(cmd.scaledSensorValue, endpoint)
			break;
	}
}

void zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd, ep=0) {
	logTrace "SensorMultilevelReport (v5) Sensor Type: ${cmd.sensorType} @ endpoint ${ep} has value ${cmd.scaledSensorValue} "

	endpoint = ep.toInteger()
	switch(endpoint) {
		case 6..9:
			onVoltageInputEvent(cmd.scaledSensorValue, endpoint)
			break;
	}
}

void zwaveEvent(hubitat.zwave.commands.multichannelv4.MultiChannelCmdEncap cmd) {
	logTrace "MultiChannelCmdEncap V4: $cmd: src: {$cmd.sourceEndPoint} dst: {$cmd.destinationEndPoint}"

	def encapsulatedCommand = cmd.encapsulatedCommand(commandClassVersions)
	logTrace "${cmd} --ENCAP-- ${encapsulatedCmd}"

	if (encapsulatedCommand) {
		if(cmd.destinationEndPoint != 0) {
			zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint, cmd.destinationEndPoint)
 		}
 		else {
			zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint)
		}
	}
	else {
		log.warn "Ignored encapsulated command: ${cmd}"
	}
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd,ep) {
	logTrace "NotificationReport V8: ep=$ep $cmd"

	if(ep == 1) {
		switch(cmd.notificationType) {
			case 0x08: 
				log.error "${device.displayName}: Over-current detected: ${ep}"
				return
      
			case 0x09: 
				log.error "${device.displayName}: System hardware failure: ${ep}"
				return
		}
	}

	log.warn "Unexpected NotificationReport for ep=${ep} with notificationType: ${cmd.notificationType}"
}

void zwaveEvent(hubitat.zwave.commands.switchcolorv3.SwitchColorReport cmd, ep=0) {
	logTrace "SwitchColorReport (v3) ${cmd} (ep ${ep})"
	String colorName = COLOR_CID_TO_NAME[cmd.colorComponentId.toInteger()]
	state.RGBW[colorName] = cmd.targetValue

	if(isColorUpdateEvent(colorName)) {
		onRGBWUpdate()
	} else {
		onSwitchLevelUpdate()
	}
}

void zwaveEvent(hubitat.zwave.commands.centralscenev3.CentralSceneNotification cmd, ep=0){
	logTrace "CentralSceneNotification (V3) ${cmd} (ep ${ep})"

	if (state.csnSequenceNumber == cmd.sequenceNumber) return
	state.csnSequenceNumber = cmd.sequenceNumber

	Map scene = [name: null, value: cmd.sceneNumber, desc: null, type:"physical", isStateChange:true]

	switch (cmd.keyAttributes){
		case 0:
			scene.name = "pushed"
			break
		case 1:
			scene.name = "released"
			break
		case 2:
			scene.name = "held"
			break
		case 3:
			scene.name = "doubleTapped"
			break
		default:
			logDebug "Unhandled keyAttributes: ${cmd}"
	}

	if (scene.name) {
		scene.desc = "button ${scene.value} ${scene.name}"
		sendEventLog(scene, ep)
	}
}

void zwaveEvent(hubitat.zwave.commands.meterv3.MeterReport cmd, ep=0) {
	logTrace "MeterReport (v3) ${cmd} (meterValue: ${cmd.scaledMeterValue}, previousMeter: ${cmd.scaledPreviousMeterValue}) (ep ${ep})"
	if (cmd.meterType != 1) {
		logDebug "Unhandled Meter Type: $cmd"
		return
	}

	BigDecimal val = safeToDec(cmd.scaledMeterValue, 0, Math.min(cmd.precision,3))
	switch (cmd.scale) {
		 case 0: // Energy
		 	sendEventLog(name:"energy", value:val, unit:"kWh")
		 	break
		 case 1: // Energy
		 	sendEventLog(name:"energy", value:val, unit:"kVAh")
		 	break
		case 2: // Power
			sendEventLog(name:"power", value:val, unit:"W")
			break
		default:
			logDebug "Unhandled Meter Scale: $cmd"
	}
}

void zwaveEvent(hubitat.zwave.Command cmd, ep=0) {
	logDebug "Unhandled zwaveEvent: $cmd (ep ${ep})"
}


/*******************************************************************
 ***** Z-Wave Command Shortcuts
********************************************************************/
//These send commands to the device either a list or a single command
void sendCommands(List<String> cmds, Long delay=200) {
	logTrace "sendCommands() count: {$cmds.size()}"
	sendHubCommand(new hubitat.device.HubMultiAction(delayBetween(cmds, delay), hubitat.device.Protocol.ZWAVE))
}

//Single Command
void sendCommands(String cmd) {
    sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZWAVE))
}

//Consolidated zwave command functions so other code is easier to read
String associationSetCmd(Integer group, List<Integer> nodes) {
	return secureCmd(zwave.associationV2.associationSet(groupingIdentifier: group, nodeId: nodes))
}

String associationRemoveCmd(Integer group, List<Integer> nodes) {
	return secureCmd(zwave.associationV2.associationRemove(groupingIdentifier: group, nodeId: nodes))
}

String associationGetCmd(Integer group) {
	return secureCmd(zwave.associationV2.associationGet(groupingIdentifier: group))
}

String versionGetCmd() {
	return secureCmd(zwave.versionV2.versionGet())
}

String basicGetCmd(Integer ep=0) {
	return secureCmd(zwave.basicV1.basicGet(), ep)
}

String switchBinarySetCmd(Integer value, Integer ep=0) {
	return secureCmd(zwave.switchBinaryV1.switchBinarySet(switchValue: value), ep)
}

String switchBinaryGetCmd(Integer ep=0) {
	return secureCmd(zwave.switchBinaryV1.switchBinaryGet(), ep)
}

String switchMultilevelSetCmd(Integer value, Integer duration, Integer ep=0) {
	return secureCmd(zwave.switchMultilevelV4.switchMultilevelSet(dimmingDuration: duration, value: value), ep)
}

String switchMultilevelGetCmd(Integer ep=0) {
	return secureCmd(zwave.switchMultilevelV4.switchMultilevelGet(), ep)
}

Map colorUpdateRGB(List rgb) {
	Map update = [red: rgb[0], green: rgb[1], blue: rgb[2]]
	return update
}

Map colorUpdateWhite(Integer value) {
	Map update = [white: value]
	return update
}

String colorUpdateCmd(Map update, Integer duration = null) {
	state.update = update;
	duration = getDimmerDuration(duration)

	Short r = (update.red == null) ? state.RGBW.red : update.red
	Short g = (update.green == null) ? state.RGBW.green : update.green
	Short b = (update.blue == null) ? state.RGBW.blue : update.blue
	Short w = (update.white == null) ? state.RGBW.white : update.white

	if((update.red == state.RGBW.red) && update.size() > 1) {
		update.remove('red')
	}
	if((update.green == state.RGBW.green) && update.size() > 1) {
		update.remove('green')
	}
	if((update.blue == state.RGBW.blue) && update.size() > 1) {
		update.remove('blue')
	}
	if((update.white == state.RGBW.white) && update.size() > 1) {
		update.remove('white')
	}

logTrace "colorUpdateCmd(${update}) ${state.RGBW}"

	/*
	Map colors = [:]
	update.each{
		//colors[COLOR_NAME_TO_CID[it.key]] = it.value
	}*/

	return secureCmd(zwave.switchColorV3.switchColorSet(red: r, green: g, blue: b, warmWhite: w, dimmingDuration: duration))

	//return secureCmd(zwave.switchColorV3.switchColorSet(colorComponents: colors, dimmingDuration: duration))
}

String colorGetComponentCmd(componentId) {	
	return secureCmd(zwave.switchColorV3.switchColorGet(colorComponentId: componentId))
}

String switchMultilevelStartLvChCmd(Boolean upDown, Integer duration, Integer ep=0) {
	//upDown: false=up, true=down
	return secureCmd(zwave.switchMultilevelV4.switchMultilevelStartLevelChange(upDown: upDown, ignoreStartLevel:1, dimmingDuration: duration), ep)
}

String switchMultilevelStopLvChCmd(Integer ep=0) {
	return secureCmd(zwave.switchMultilevelV4.switchMultilevelStopLevelChange(), ep)
}

String configSetCmd(Map param, Integer value) {
	//Convert from unsigned to signed for scaledConfigurationValue
	Long sizeFactor = Math.pow(256,param.size).round()
	if (value >= sizeFactor/2) { value -= sizeFactor }

	return secureCmd(zwave.configurationV1.configurationSet(parameterNumber: param.num, size: param.size, scaledConfigurationValue: value))
}

String configGetCmd(Map param) {
	return secureCmd(zwave.configurationV1.configurationGet(parameterNumber: param.num))
}

List configSetGetCmd(Map param, Integer value) {
	List<String> cmds = []
	cmds << configSetCmd(param, value)
	cmds << configGetCmd(param)
	return cmds
}


/*******************************************************************
 ***** Z-Wave Encapsulation
********************************************************************/
//Secure and MultiChannel Encapsulate
String secureCmd(String cmd) {
	return zwaveSecureEncap(cmd)
}
String secureCmd(hubitat.zwave.Command cmd, ep=0) {
	return zwaveSecureEncap(multiChannelEncap(cmd, ep))
}

//MultiChannel Encapsulate if needed
//This is called from secureCmd or supervisionEncap, do not call directly
String multiChannelEncap(hubitat.zwave.Command cmd, ep) {
	logTrace "multiChannelEncap (V3): ${cmd} (ep ${ep})"
	if (ep > 0) {
		cmd = zwave.multiChannelV3.multiChannelCmdEncap(destinationEndPoint:ep).encapsulate(cmd)
	}
	return cmd.format()
}

/*******************************************************************
 ***** Execute / Build Commands
********************************************************************/
List getDeviceCmds() {
	List<String> cmds = []

	if (!firmwareVersion || !state.deviceModel) {
		cmds << versionGetCmd()
	}

	return cmds
}

List getConfigureCmds() {
	List<String> cmds = []

	if (!firmwareVersion || !state.deviceModel) {
		cmds << versionGetCmd()
	}

	cmds += getConfigureAssocsCmds()

	configParams.each { param ->
		Integer paramVal = getParamValue(param, true)
		Integer storedVal = getParamStoredValue(param.num)

		if (paramVal != null && storedVal != paramVal) {
			logDebug "Changing ${param.name} (#${param.num}) from ${storedVal} to ${paramVal}"
			cmds += configSetGetCmd(param, paramVal)
		}
	}

	return cmds
}

List getRefreshCmds() {
	List<String> cmds = []

	// This needs to be first to check effects mode???
	cmds << secureCmd(zwave.configurationV2.configurationGet(parameterNumber: 157))

	// Level, Colors, and Power
	cmds << switchMultilevelGetCmd()
	cmds += getRefreshColorCmds()
	cmds += getRefreshWhiteCmds()
	cmds << secureCmd(zwave.meterV3.meterGet(scale: 2)) // Power Meter


	// https://developer.smartthings.com/docs/edge-device-drivers/zwave/generated/Notification/constants.html#st.zwave.CommandClass.Notification.notification_type
	cmds << secureCmd(zwave.notificationV8.notificationGet(notificationType: 0x08), 1) 
	cmds << secureCmd(zwave.notificationV8.notificationGet(notificationType: 0x09), 1) 

	//cmds << secureCmd(zwave.notificationV8.notificationGet(notificationType: 0x08, v1AlarmType: 0, event: 0)) 
	//cmds << secureCmd(zwave.notificationV8.notificationGet(notificationType: 0x09, v1AlarmType: 0, event: 0)) 

	getChildAnalogInputs().each{ep, itm -> 
		cmds << secureCmd(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 0x0F, scale:1), ep.toInteger())
	}


	return cmds
}

List getRefreshColorCmds() {
	state.update.red = null
	state.update.green = null
	state.update.blue = null

	List<String> cmds = []

	cmds << colorGetComponentCmd(2)
	cmds << colorGetComponentCmd(3)
	cmds << colorGetComponentCmd(4)
	return cmds
}

List getRefreshWhiteCmds() {
	state.update.white = null

	List<String> cmds = []
	cmds << colorGetComponentCmd(0) 
	return cmds
}

void clearVariables() {
	logWarn "Clearing state variables and data..."

	//Backup
	String devModel = state.deviceModel

	//Clears State Variables
	state.clear()

	//Clear Config Data
	configsList["${device.id}"] = [:]
	device.removeDataValue("configVals")

	//Clear Data from other Drivers
	device.removeDataValue("protocolVersion")
	device.removeDataValue("hardwareVersion")
	device.removeDataValue("zwaveAssociationG1")
	device.removeDataValue("zwaveAssociationG2")
	device.removeDataValue("zwaveAssociationG3")

	//Restore
	if (devModel) state.deviceModel = devModel
}

List getConfigureAssocsCmds() {
	List<String> cmds = []

	if (!state.group1Assoc) {
		cmds << associationSetCmd(1, [zwaveHubNodeId])
		cmds << associationGetCmd(1)
		if (state.group1Assoc == false) {
			logDebug "Adding missing lifeline association..."
		}
	}

	for (int i = 2; i <= maxAssocGroups; i++) {
		List<String> cmdsEach = []
		List settingNodeIds = getAssocDNIsSettingNodeIds(i)

		//Need to remove first then add in case we are at limit
		List oldNodeIds = state."assocNodes$i"?.findAll { !(it in settingNodeIds) }
		if (oldNodeIds) {
			logDebug "Removing Nodes: Group $i - $oldNodeIds"
			cmdsEach << associationRemoveCmd(i, oldNodeIds)
		}

		List newNodeIds = settingNodeIds.findAll { !(it in state."assocNodes$i") }
		if (newNodeIds) {
			logDebug "Adding Nodes: Group $i - $newNodeIds"
			cmdsEach << associationSetCmd(i, newNodeIds)
		}

		if (cmdsEach) {
			cmdsEach << associationGetCmd(i)
			cmds += cmdsEach
		}
	}

	return cmds
}

List getAssocDNIsSettingNodeIds(grp) {
	def dni = getAssocDNIsSetting(grp)
	def nodeIds = convertHexListToIntList(dni.split(","))

	if (dni && !nodeIds) {
		logWarn "'${dni}' is not a valid value for the 'Device Associations - Group ${grp}' setting.  All z-wave devices have a 2 character Device Network ID and if you're entering more than 1, use commas to separate them."
	}
	else if (nodeIds.size() > maxAssocNodes) {
		logWarn "The 'Device Associations - Group ${grp}' setting contains more than ${maxAssocNodes} IDs so some (or all) may not get associated."
	}

	return nodeIds
}

Integer getPendingChanges() {
	Integer configChanges = configParams.count { param ->
		Integer paramVal = getParamValue(param, true)
		((paramVal != null) && (paramVal != getParamStoredValue(param.num)))
	}
	Integer pendingAssocs = Math.ceil(getConfigureAssocsCmds()?.size()/2) ?: 0
	return configChanges + pendingAssocs
}

List getOnOffCmds(val, Number duration=null, Integer endPoint=0) {
	Short onVal = state.on.last.switchLevel ?: 0xFF

	return getSetLevelCmds(val ? onVal : 0x00, duration, endPoint)
}

List getSetLevelCmds(Number level, Number duration=null, Integer endPoint=0) {
	Short levelVal = getDimmerLevel(level)
	Short durationVal = getDimmerDuration(duration)

	logDebug "getSetLevelCmds output [level:${levelVal}, duration:${durationVal}, endPoint:${endPoint}]"

	List<String> cmds = []
	Integer delay = (safeToInt(zwaveRampRate) * 1000)
	cmds << switchMultilevelSetCmd(levelVal, durationVal, endPoint)
	cmds << switchMultilevelGetCmd()

	return cmds
}

Short getDimmerLevel(level) {
	Short levelVal = safeToInt(level, 99)

	// level 0xFF tells device to use last level, 0x00 is off
	if (levelVal != 0xFF && levelVal != 0x00) {
		//Convert level in range of min/max
		levelVal = convertLevel(levelVal, true)
		levelVal = validateRange(levelVal, 99, 1, 99)
	}
	return  levelVal
}

Short getDimmerDuration(duration)  {
	// Duration Encoding:
	// 0x01..0x7F 1 second (0x01) to 127 seconds (0x7F) in 1 second resolution.
	// 0x80..0xFE 1 minute (0x80) to 127 minutes (0xFE) in 1 minute resolution.
	// 0xFF Factory default duration.

	//Convert seconds to minutes above 120s
	if (duration > 120) {
		logDebug "getSetLevelCmds converting ${duration}s to ${Math.round(duration/60)}min"
		duration = (duration / 60) + 127
	}

	Short durationVal = validateRange(duration, -1, -1, 254)
	if (duration == null || durationVal == -1) {
		durationVal = 0xFF
	}

	return durationVal
}


Map getColorHSV(Map rgbw) {
	rgb = [rgbw.red, rgbw.green, rgbw.blue]
	hsv = ColorUtils.rgbToHSV(rgb)
	Map hsvMap = [hue: Math.round(hsv[0]), saturation:  Math.round(hsv[1]), level: Math.round(hsv[2])]

	return hsvMap
}

List<String> getColorCmds(Map hsv, Integer duration = null) {
	logTrace "getColorCmds(${hsv})"

	current = getColorHSV(state.RGBW)

	// Figure out desired state from data provided
	def hue = (hsv.hue != null ? hsv.hue : current.hue)
	def sat = (hsv.saturation != null ? hsv.saturation : current.saturation)
	def lvl = (hsv.level != null ? hsv.level : current.level)

	// Convert back to RGB
	rgb = ColorUtils.hsvToRGB([hue, sat, lvl])
	logTrace "getColorCmds HSV/RGB: ${[hue,sat,lvl]} / ${rgb}"

	List<String> cmds = []

	cmds << colorUpdateCmd(colorUpdateRGB(rgb), duration)
	if (quickRefresh) { cmds += getColorQuickRefreshCmds() }

	return cmds
}

List<String> getWhiteCmds(level, Integer duration = null) {
	logDebug "getWhiteCmds(${level})"

	// Scale the level from 0-100 to 0-255
	Integer scaledLevel = Math.round((level * 255) / 100)

	List<String> cmds = []
	cmds << colorUpdateCmd(colorUpdateWhite(scaledLevel), duration)
	if (quickRefresh) { cmds += getColorQuickRefreshCmds() }

	return cmds
}

List<String> getColorQuickRefreshCmds() {
	List<String> cmds = []

	update = state.update
	update.each{
		if (it.value == null || (update[it.key] != state.RGBW[it.key])) {
			cmds << colorGetComponentCmd(COLOR_NAME_TO_CID[it.key])
		}
	}

	return cmds
}

/*******************************************************************
 ***** Event Senders
********************************************************************/
//evt = [name, value, type, unit, desc, isStateChange]
void sendEventLog(Map evt, ep=null) {
	//Set description if not passed in
	evt.descriptionText = evt.desc ?: "${evt.name} set to ${evt.value}${evt.unit ?: ''}"

	logTrace "sendEventLog: ${evt.descriptionText} (ep ${ep})"

	// Endpoint Event
	if (ep) {
		def childName = ep.isNumber() ? childNetworkNameEndpoint(ep) : ep;
		def childDev = getChildByName(childName)
		if (childDev) {
			if (childDev.currentValue(evt.name).toString() != evt.value.toString() || evt.isStateChange) {
				evt.descriptionText = "${childDev}: ${evt.descriptionText}"
				childDev.sendEvent(evt)
			} else {
				logDebug "${childDev}: ${evt.descriptionText} [NOT CHANGED]"
				childDev.sendEvent(evt)
			}
		}
		else {
			log.error "No device for endpoint (${ep}). Press Configure to create child devices."
		}
		return
	}

	// Main Device Events
	if (device.currentValue(evt.name).toString() != evt.value.toString() || evt.isStateChange) {
		logInfo "${evt.descriptionText}"
	} else {
		logDebug "${evt.descriptionText} [NOT CHANGED]"
	}
	// Always send event to update last activity
	sendEvent(evt)
}

void onSwitchEvent(rawVal, String type, ep = null) {
	String value = (rawVal ? "on" : "off")
	String desc = "switch is turned ${value}" + (type ? " (${type})" : "")
	sendEventLog(name:"switch", value: value, type: type, desc: desc, ep)

	// Level update
	Integer level = (rawVal == 99 ? 100 : rawVal)
	level = convertLevel(level, false)

	desc = "level is set to ${level}%"
	if (type) desc += " (${type})"
	if (levelCorrection) desc += " [actual: ${rawVal}]"
	sendEventLog(name:"level", value: level, type: type, unit:"%", desc: desc, ep)

	state.switch.level = rawVal
	if(rawVal > 0)
		state.on.last.switchLevel = rawVal

	onSwitchUpdate()
	onSwitchChildEvent(type)
}

void onSwitchChildEvent(type = null) {
	// Send switch event to child devices (if they exist)
	["white", "color", "red", "green", "blue"].each{ childName ->

		if(!state.device[childName])
			return

		if(childName == "color") {
			Map hsv = getColorHSV(state.RGBW)
			level = hsv.level
		} else {
			level = Math.round((state.RGBW[childName]*100)/255)
		}

		// TODO: check if value has changed before sending events?
		List<Map> events = []

		String value = (level && state.switch.on) ? "on" : "off"
		String desc = "switch is turned ${value}" + (type ? " (${type})" : "")

		events.add([name:"switch", value: value, type: type, descriptionText:desc])

		desc = "level is set to ${level}%"
		if (type) desc += " (${type})"
		//if (levelCorrection) desc += " [actual: ${rawVal}]"

		events.add([name: "level", value: level, type: type, unit:"%", descriptionText: desc])

		// TODO: store color value in child device?
		// use 'Generic Variable Component Dimmer' that also has   capability "Variable" ??
		desc = "color value set to ${state.RGBW[childName]}"
		if (type) desc += " (${type})"
		//events.add([name: "variable", value: state.RGBW[childName], type: type, descriptionText: desc])

		def childDev = getChildByName(childName)
		if (childDev) {
			childDev.parse(events)
		}
	}
}

void onVoltageInputEvent(voltageValue, Integer ep) {
	// TODO: error handling?
	def child = childDevices.find { it.deviceNetworkId == childNetworkIdEndpoint(ep) }
	child?.parse([[name:"voltage", value: voltageValue, descriptionText:"${child} voltage is ${voltageValue}v", unit: "v"]])
}

void sendBasicButtonEvent(buttonId, String name) {
	String desc = "button ${buttonId} ${name} (digital)"
	sendEventLog(name:name, value:buttonId, type:"digital", desc:desc, isStateChange:true)
}


// May to update switch level if color or white level changes
// Disabled for now
List getSwitchUpdateCmds() {
	List<String> cmds = []

	oldState = state.switch.on
	onSwitchUpdate()

	if(oldState != state.switch.on) {
		cmds += getOnOffCmds(state.switch.on ? 0xFF : 0x00)
	}

	return cmds
}

void onSwitchUpdate() {
	if(state.switch.level == 0) {
		state.switch.on = false
		["red", "green", "blue", "white", "color"].each { 
			state.on[it] = false
		}
		return
	}

	state.on.color = state.color.level > 0 ? true : false;
	["red", "green", "blue", "white"].each { 
		state.on[it] = state.RGBW[it] > 0
	}

	state.switch.on = (state.on.color || state.on.white)
}

Boolean isColorUpdateEvent(colorName) {
	// No information about color update so don't trigger
	if(state.update.size() == 0)
		return false

logTrace "isColorUpdateEvent: ${colorName} in ${state.update}"

	if(state.update.containsKey(colorName)) {
		if(state.update.size() == 1) {
			state.update.remove(colorName)
			return true // Update only 1 color
		}

		state.update.remove(colorName)

		// If update contains other values with null, we need to wait for those reports / refresh()
		hasNull = state.update.find{it.value == null}
		if(hasNull)
			return false

		// Assume all colors were updated, don't wait for other color reports
		state.update.each{cName, cVal -> state.RGBW[cName] = cVal}
		state.update = [:]
		return true
	}

	return false;
}

void onRGBWUpdate() {
	logTrace "onRGBWUpdate(${state.RGBW})"

	onWhiteUpdate()
	onColorUpdate()
	onSwitchLevelUpdate() // Color changes affect switch level status

	state.on.RGBW = state.RGBW
	state.on.RGBW.each{name, val ->
		if(val > 0) {
			state.on.last[name] = val
		}
	}
}

// Update color 
void onColorUpdate() {
	logDebug "onColorUpdate(${state.RGBW})"

	List<String> cmds = []
	Map hsv = getColorHSV(state.RGBW)
	if(hsv.level > 0) {
		state.on.last.color = state.RGBW.red +","+ state.RGBW.green +","+ state.RGBW.blue;
	}

	logDebug "onColorUpdate(${hsv})"
	state.color.level = hsv.level
	state.on.color = hsv.level > 0

	// Send events
	sendEventLog(name:"hue", value: hsv.hue)
	sendEventLog(name:"saturation", value: hsv.saturation, unit:"%")
	sendEventLog(name:"RGB", value: [state.RGBW.red, state.RGBW.green, state.RGBW.blue])
	sendEventLog(name:"color", value: hsv)

	// Set colorMode and colorName
	if(state.effectNumber == 0) {
		if (state.on.white && state.on.color) {
			sendEventLog(name:"colorMode", value:"RGBW")
			sendEventLog(name:"colorName", value: getGenericColor(hsv) + "/White")
		}
		else if (state.on.color) {
			sendEventLog(name:"colorMode", value: "RGB")
			sendEventLog(name:"colorName", value: getGenericColor(hsv))
		}
		else if (state.on.white) {
			sendEventLog(name:"colorMode", value:"WHITE")
			sendEventLog(name:"colorName", value:"White")
		}
	}
}

void onWhiteUpdate() {
	Integer whValue = state.RGBW.white
	Integer whLevel = Math.round((whValue*100)/255)
	Map whMap = [level: whLevel, W: whValue]

	state.white.level = whLevel
	state.on.white = whLevel > 0

	// Send events
	sendEventLog(name:"white", value: whMap)
}

void onSwitchLevelUpdate() {
 	if(state.on.color == false && state.on.white  == false) {
		sendEventLog(name:"switch", value: "off")
		state.switch.on = false
	} else {
		sendEventLog(name:"switch", value: "on")
		state.switch.on = true
	}
	onSwitchChildEvent()
}

void sendEffectEvents(effect) {
	
	logDebug "sendEffectEvents(${effect})"
	String efName = EFFECT_NAMES[effect] ?: "Unknown"
	sendEventLog(name:"effectName", value: efName)
	
	if (effect > 0) {
		// Stop flashing for effect but restore when effect disabled  
		if(state.switch.flash != null) {
			unschedule("flashFunction")
		}

		sendEventLog(name:"colorMode", value:"EFFECTS", desc:"colorMode set to EFFECTS (${efName})")
		sendEventLog(name:"switch", value:"on")

		// should we set the 'color' device as 'on'?

	} else if (effect == 0 && state.effectNumber > 0) {
		// If now disabled and there was a previous effect, restore color + switch level
		List<String> cmds = []

		// Will use RGBW state to restore color
		cmds << colorUpdateCmd([red: null])
		cmds += getSetLevelCmds(state.switch.level, 0)

		if(state.switch.flash) {
			flashFunction(state.switch.flash)
		} else if(state.switch.off) {
			// Turn off again if switch was off prior to effect 
			cmds += getOnOffCmds(0x00)
		}
		sendCommands(cmds)
	}
	state.effectNumber = effect
}

/*******************************************************************
 ***** Child management
********************************************************************/

void removeChildDevices(delete) {
	delete.each {deleteChildDevice(it.deviceNetworkId)}
}

private childNetworkNameEndpoint(ep) {
	return "ep${ep}"
}

private childNetworkIdEndpoint(ep) {
	return childNetworkIdName(childNetworkNameEndpoint(ep))
}

private childNetworkIdName(name) {
	return "${device.deviceNetworkId}-${name}"
}

private getConfigChildDevices() {

	Map configChildDevices = [:]
	String dni

	childSetup = safeToInt(childConfig)

	names = ['white', 'color', 'red' , 'green', 'blue']
	names.each {
		code = it
		name = it.capitalize()

		// Add child devices if setup in parameters (uses bit flags)
		if(childSetup != null && (childSetup & childDimmers[code]) == childDimmers[code]) {
			dni = childNetworkIdName(code)
			configChildDevices[dni] = [
				"code": code,
				"ns": "hubitat", 
				"name": "Generic Component Dimmer", 
				"data": [componentLabel: name, label: "${device.displayName} - ${name} Dimmer", isComponent: true]
			]
		}
	}

	def analogInputParamValues = [0,1]
	def ep = 0;
	for(int i = 1; i<=4 ;i++) {
		if(getParamValue("swIn${i}") in analogInputParamValues) {
			ep = i + 5; // endpoints 6-9
			dni = childNetworkIdEndpoint(ep)
			configChildDevices[dni] = [
				"code": "${ep}", 
				"name": "Zooz ZEN31 Analog Input",
				 "data": [componentLabel: "ep${ep}", completedSetup: true, label: "${device.displayName} - Analog Input ${i}", isComponent: false]
			]
		}
	}

	return configChildDevices
}




private getChildAnalogInputs() {
	Map analogDeviceIds = [:]
	for(ep in 6..9) {
		analogDeviceIds[childNetworkIdEndpoint(ep)] = ep.toString()
	}
	String ep
	Map analogDevices = [:]
	for(itm in getChildDevices()) {
		if( analogDeviceIds.containsKey(itm.deviceNetworkId)) {
			ep = analogDeviceIds[itm.deviceNetworkId]
			analogDevices[ep] = itm
		}
	}
	return analogDevices
}

private getChildNameFromId(deviceNetworkId) {
	parts = deviceNetworkId.split('-')
	return parts[1]
}

private getChildByName(name) {
	return getChildByNetworkId("${device.deviceNetworkId}-${name}")
}

private getChildByNetworkId(dni) {
	return childDevices?.find { dni.equalsIgnoreCase(it.deviceNetworkId) }
}

/*******************************************************************
 ***** Common Functions
********************************************************************/
/*** Parameter Store Map Functions ***/
@Field static Map<String, Map> configsList = new java.util.concurrent.ConcurrentHashMap()
Integer getParamStoredValue(Integer paramNum) {
	//Using Data (Map) instead of State Variables
	TreeMap configsMap = getParamStoredMap()
	return safeToInt(configsMap[paramNum], null)
}

void setParamStoredValue(Integer paramNum, Integer value) {
	//Using Data (Map) instead of State Variables
	TreeMap configsMap = getParamStoredMap()
	configsMap[paramNum] = value
	configsList[device.id][paramNum] = value
	device.updateDataValue("configVals", configsMap.inspect())
}

Map getParamStoredMap() {
	Map configsMap = configsList[device.id]
	if (configsMap == null) {
		configsMap = [:]
		if (device.getDataValue("configVals")) {
			try {
				configsMap = evaluate(device.getDataValue("configVals"))
			}
			catch(Exception e) {
				logWarn("Clearing Invalid configVals: ${e}")
				device.removeDataValue("configVals")
			}
		}
		configsList[device.id] = configsMap
	}
	return configsMap
}

//Parameter List Functions
//This will rebuild the list for the current model and firmware only as needed
//paramsList Structure: MODEL:[FIRMWARE:PARAM_MAPS]
//PARAM_MAPS [num, name, title, description, size, defaultVal, options, firmVer]
@Field static Map<String, Map<String, List>> paramsList = new java.util.concurrent.ConcurrentHashMap()
void updateParamsList() {
	logDebug "Update Params List"
	String devModel = state.deviceModel
	Short modelNum = deviceModelShort
	Short modelSeries = Math.floor(modelNum/10)
	BigDecimal firmware = firmwareVersion

	List<Map> tmpList = []
	paramsMap.each { name, pMap ->
		Map tmpMap = pMap.clone()
		tmpMap.options = tmpMap.options?.clone()

		//Save the name
		tmpMap.name = name

		//Apply custom adjustments
		tmpMap.changes.each { m, changes ->
			if (m == devModel || m == modelNum || m ==~ /${modelSeries}X/) {
				tmpMap.putAll(changes)
				if (changes.options) { tmpMap.options = changes.options.clone() }
			}
		}
		//Don't need this anymore
		tmpMap.remove("changes")

		//Set DEFAULT tag on the default
		tmpMap.options.each { k, val ->
			if (k == tmpMap.defaultVal) {
				tmpMap.options[(k)] = "${val} [DEFAULT]"
			}
		}

		//Save to the temp list
		tmpList << tmpMap
	}

	//Remove invalid or not supported by firmware
	tmpList.removeAll { it.num == null }
	tmpList.removeAll { firmware < (it.firmVer ?: 0) }
	tmpList.removeAll {
		if (it.firmVerM) {
			(firmware-(int)firmware)*100 < it.firmVerM[(int)firmware]
		}
	}

	//Save it to the static list
	if (paramsList[devModel] == null) paramsList[devModel] = [:]
	paramsList[devModel][firmware] = tmpList
}

//Verify the list and build if its not populated
void verifyParamsList() {
	String devModel = state.deviceModel
	BigDecimal firmware = firmwareVersion
	if (!paramsMap.settings?.fixed) fixParamsMap()
	if (paramsList[devModel] == null) updateParamsList()
	if (paramsList[devModel][firmware] == null) updateParamsList()
}
//These have to be added in after the fact or groovy complains
void fixParamsMap() {
	paramsMap.rampRate.options << rampRateOptions
	paramsMap.zwaveRampRate.options << rampRateOptions
	paramsMap['settings'] = [fixed: true]
}

//Gets full list of params
List<Map> getConfigParams() {
	//logDebug "Get Config Params"
	if (!device) return []
	String devModel = state.deviceModel
	BigDecimal firmware = firmwareVersion

	//Try to get device model if not set
	if (devModel) { verifyParamsList() }
	else          { runInMillis(200, setDevModel) }
	//Bail out if unknown device
	if (!devModel || devModel == "UNK00") return []

	return paramsList[devModel][firmware]
}

//Get a single param by name or number
Map getParam(def search) {
	//logDebug "Get Param (${search} | ${search.class})"
	Map param = [:]

	verifyParamsList()
	if (search instanceof String) {
		param = configParams.find{ it.name == search }
	} else {
		param = configParams.find{ it.num == search }
	}

	return param
}

//Convert Param Value if Needed
Integer getParamValue(String paramName) {
	return getParamValue(getParam(paramName))
}
Number getParamValue(Map param, Boolean adjust=false) {
	if (param == null) return
	Number paramVal = safeToInt(settings."configParam${param.num}", param.defaultVal)
	if (!adjust) return paramVal

	//Reset hidden parameters to default
	if (param.hidden && settings."configParam${param.num}" != null) {
		logWarn "Resetting hidden parameter ${param.name} (${param.num}) to default ${param.defaultVal}"
		device.removeSetting("configParam${param.num}")
		paramVal = param.defaultVal
	}

	return paramVal
}

/*** Parameter Helper Functions ***/
private getRampRateOptions() {
	return getTimeOptionsRange("Second", 1, [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,20,25,30,45,60,75,90])
}

private getTimeOptionsRange(String name, Integer multiplier, List range) {
	return range.collectEntries{ [(it*multiplier): "${it} ${name}${it == 1 ? '' : 's'}"] }
}

/*** Other Helper Functions ***/
void updateSyncingStatus(Integer delay=2) {
	runIn(delay, refreshSyncStatus)
	sendEvent(name:"syncStatus", value:"Syncing...")
}

void refreshSyncStatus() {
	Integer changes = pendingChanges
	sendEvent(name:"syncStatus", value:(changes ? "${changes} Pending Changes" : "Synced"))
}

void updateLastCheckIn() {
	if (!isDuplicateCommand(state.lastCheckInTime, 60000)) {
		state.lastCheckInTime = new Date().time
		state.lastCheckInDate = convertToLocalTimeString(new Date())
	}
}

// iOS app has no way of clearing string input so workaround is to have users enter 0.
String getAssocDNIsSetting(grp) {
	def val = settings."assocDNI$grp"
	return ((val && (val.trim() != "0")) ? val : "")
}

//Stash the model in a state variable
String setDevModel(BigDecimal firmware) {
	if (!device) return
	def devTypeId = convertIntListToHexList([safeToInt(device.getDataValue("deviceType")),safeToInt(device.getDataValue("deviceId"))],4)
	String devModel = deviceModelNames[devTypeId.join(":")] ?: "UNK00"
	if (!firmware) { firmware = firmwareVersion }

	state.deviceModel = devModel
	device.updateDataValue("deviceModel", devModel)
	logDebug "Set Device Info - Model: ${devModel} | Firmware: ${firmware}"

	if (devModel == "UNK00") {
		logWarn "Unsupported Device USE AT YOUR OWN RISK: ${devTypeId}"
		state.WARNING = "Unsupported Device Model - USE AT YOUR OWN RISK!"
	}
	else state.remove("WARNING")

	//Setup parameters if not set
	verifyParamsList()

	return devModel
}

Integer getDeviceModelShort() {
	return safeToInt(state.deviceModel?.drop(3))
}

BigDecimal getFirmwareVersion() {
	String version = device?.getDataValue("firmwareVersion")
	return ((version != null) && version.isNumber()) ? version.toBigDecimal() : 0.0
}

String convertToLocalTimeString(dt) {
	def timeZoneId = location?.timeZone?.ID
	if (timeZoneId) {
		return dt.format("MM/dd/yyyy hh:mm:ss a", TimeZone.getTimeZone(timeZoneId))
	} else {
		return "$dt"
	}
}

List convertIntListToHexList(intList, pad=2) {
	def hexList = []
	intList?.each {
		hexList.add(Integer.toHexString(it).padLeft(pad, "0").toUpperCase())
	}
	return hexList
}

List convertHexListToIntList(String[] hexList) {
	def intList = []

	hexList?.each {
		try {
			it = it.trim()
			intList.add(Integer.parseInt(it, 16))
		}
		catch (e) { }
	}
	return intList
}

Integer convertLevel(level, userLevel=false) {
	if (levelCorrection) {
		Integer brightmax = getParamValue("maximumBrightness")
		Integer brightmin = getParamValue("minimumBrightness")
		brightmax = (brightmax == 99) ? 100 : brightmax
		brightmin = (brightmin == 1) ? 0 : brightmin

		if (userLevel) {
			//This converts what the user selected into a physical level within the min/max range
			level = ((brightmax-brightmin) * (level/100)) + brightmin
			state.levelActual = level
			level = validateRange(Math.round(level), brightmax, brightmin, brightmax)
		}
		else {
			//This takes the true physical level and converts to what we want to show to the user
			if (Math.round(state.levelActual ?: 0) == level) level = state.levelActual
			else state.levelActual = level

			level = ((level - brightmin) / (brightmax - brightmin)) * 100
			level = validateRange(Math.round(level), 100, 1, 100)
		}
	}
	else if (state.levelActual) {
		state.remove("levelActual")
	}

	return level
}

String getGenericColor(Map hsv) {
    String colorName
    Integer hue = Math.round(hsv.hue * 3.6)
    switch (hue) {
        case 0..15:  colorName = "Red"; break
        case 16..45: colorName = "Orange"; break
        case 46..75: colorName = "Yellow"; break
        case 76..105: colorName = "Chartreuse"; break
        case 106..135: colorName = "Green"; break
        case 136..165: colorName = "Spring"; break
        case 166..195: colorName = "Cyan"; break
        case 196..225: colorName = "Azure"; break
        case 226..255: colorName = "Blue"; break
        case 256..285: colorName = "Violet"; break
        case 286..315: colorName = "Magenta"; break
        case 316..345: colorName = "Rose"; break
        case 346..360: colorName = "Red"; break
    }
    //Check for Low Saturation
    if (hsv.saturation <= 10) colorName = "White"

    return colorName
}

Integer validateRange(val, Integer defaultVal, Integer lowVal, Integer highVal) {
	Integer intVal = safeToInt(val, defaultVal)
	if (intVal > highVal) {
		return highVal
	} else if (intVal < lowVal) {
		return lowVal
	} else {
		return intVal
	}
}

Integer safeToInt(val, defaultVal=0) {
	if ("${val}"?.isInteger())		{ return "${val}".toInteger() }
	else if ("${val}"?.isNumber())	{ return "${val}".toDouble()?.round() }
	else { return defaultVal }
}

BigDecimal safeToDec(val, defaultVal=0, roundTo=-1) {
	BigDecimal decVal = "${val}"?.isNumber() ? "${val}".toBigDecimal() : defaultVal
	if (roundTo == 0)		{ decVal = Math.round(decVal) }
	else if (roundTo > 0)	{ decVal = decVal.setScale(roundTo, BigDecimal.ROUND_HALF_UP).stripTrailingZeros() }
	if (decVal.scale()<0)	{ decVal = decVal.setScale(0) }
	return decVal
}

boolean isDuplicateCommand(lastExecuted, allowedMil) {
	!lastExecuted ? false : (lastExecuted + allowedMil > new Date().time)
}


/*******************************************************************
 ***** Logging Functions
********************************************************************/
void logsOff() {}
void debugLogsOff() {
	logWarn "Debug logging disabled..."
	device.updateSetting("debugEnable",[value:"false",type:"bool"])
}

void logWarn(String msg) {
	log.warn "${device.displayName}: ${msg}"
}

void logInfo(String msg) {
	if (txtEnable) log.info "${device.displayName}: ${msg}"
}

void logDebug(String msg) {
	if (debugEnable) log.debug "${device.displayName}: ${msg}"
}

//For Extreme Code Debugging - tracing commands
void logTrace(String msg) {
	//Uncomment to Enable
	//log.trace "${device.displayName}: ${msg}"
}
