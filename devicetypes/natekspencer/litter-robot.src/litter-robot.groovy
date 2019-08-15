/**
 *  Litter-Robot
 *
 *  Copyright 2020 Nathan Spencer
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *
 *  Known commands that can be called:
 *  C  - start cleaning cycle: unitStatus changes from RDY to CCP; upon completion, cycleCount += 1 and unitStatus briefly goes to CCC and panelLockActive = 1 then typically returns to unitStatus of RDY and panelLockActive of previous state
 *  D  - reset settings to defaults: sleepModeActive = 0, panelLockActive = 0, nightLightActive = 1, cleanCycleWaitTimeMinutes = 7
 *  L0 - panel lock off: panelLockActive = 0
 *  L1 - panel lock active: panelLockActive = 1
 *  N0 - night light off: nightLightActive = 0
 *  N1 - night light on: nightLightActive = 1
 *  P0 - turn power off: unitStatus changes = OFF; on the next report from the unit, powerStatus changes to NC and cleanCycleWaitTimeMinutes shows as 7; device is still wifi connected, but won't respond to any commands except P1 (power on), sleepModeActive and panelLockActive reset to 0
 *  P1 - turn power on: powerStatus goes from NC -> AC; cleanCycleWaitTimeMinutes returns to previous value; starts clean cycle (see details on "C" command above)
 *  R  - valid, not sure what it does though, reset or refresh maybe? weirdly a new parameter showed up called "cyclesUntilFull" after posting this, but still not sure how it is utilized
 *  S0 - turn off sleep mode: sleepModeActive = 0
 *  S1HH:MI:SS - turn on sleep mode: sleepModeActive = 1HH:MI:SS; HH:MI:SS is a 24 hour clock that enters sleep mode from 00:00:00-08:00:00, so if at midnight you set sleep mode to 122:30:00, then sleep mode will being in 1.5 hours or 1:30am; when coming out of sleep state, a clean cycle is performed (see details on "C" command above)
 *  W3 - set wait time to 3 minuts: cleanCycleWaitTimeMinutes = 3 (hexadecimal representation of minutes)
 *  W7 - set wait time to 7 minuts: cleanCycleWaitTimeMinutes = 7 (hexadecimal representation of minutes)
 *  WF - set wait time to 15 minuts: cleanCycleWaitTimeMinutes = F (hexadecimal representation of minutes)
 *
 *
 *  CHANGE HISTORY
 *  VERSION     DATE            NOTES
 *  1.0.0       2019-04-10      Initial release
 *  1.1.0       2019-04-23      Added generic actuator/sensor capabilities, moved power on/off away from switch
 *                              capability to custom powerOn/powerOff commands (similar to panel lock and night
 *                              light), reworked switch capability's "on" command to start a clean cycle so that the
 *                              new SmartThings app has some functionality outside of just reporting, added ability
 *                              to reset drawer gauge, added additional identified statuses reported by unit and
 *                              removed an incorrect one, fixed issue to prevent a forced clean cycle from running
 *                              during active sleep mode hours, added support for "battery" powerSource when unit
 *                              reports it is running off the backup battery, text tweaks and formatting
 *  1.2.0       2019-12-05      Update from @mbafford for robotCleanerMovement values to match SmartThings published
 *                              enum for the capability: https://docs.smartthings.com/en/latest/capabilities-reference.html
 *  1.2.1       2020-01-22      Exposed lastCleaned as an attribute
 *
 */

import groovy.time.TimeCategory

metadata {
    definition (name: "Litter-Robot", namespace: "natekspencer", author: "Nathan Spencer") {
        capability "Acceleration Sensor"
        capability "Contact Sensor"
        //capability "Filter Status" // maybe add this so as to remind to replace the filter after x days?
        capability "Motion Sensor"
        capability "Power Source"
        capability "Switch"
        capability "Tamper Alert"
        capability "Refresh"
        capability "Health Check"
        capability "Actuator"
        capability "Sensor"

        attribute "cycleCapacity", "number"
        attribute "cycleCount"   , "number"
		attribute "cyclesAfterDrawerFull", "number"
		attribute "litterRobotId", "string"
		attribute "lastCleaned", "string"	
		attribute "drawerLevel", "number"
		
        command "lightOn"
        command "lightOff"
        command "panelLockOn"
        command "panelLockOff"
        command "powerOn"
        command "powerOff"
        command "sleepOn"
        command "sleepOff"
        command "resetDrawerGauge"
    }
    
    preferences {
        input "waitTime"               , "enum"  , title: "Clean Cycle Wait Time (in Minutes)"              , options: ["3":"3", "7":"7", "F":"15"]
        input "sleepTime"              , "time"  , title: "Sleep Mode Start Time"                           , description: "Tap to set\nToggle sleep mode on main screen"
        input "forceCleanCycleInterval", "number", title: "Force Clean Cycle (in Hours)\n"                 +
                                                          "Forces a clean cycle to trigger if one hasn't " +
                                                          "occurred within the past specified number "     +
                                                          "of hours and the unit is not in sleep mode.\n"  +
                                                          "**Caution** Use of this feature is at you "     +
                                                          "and your pet's own risk. The developer is "     +
                                                          "not responsible for any injury that may "       +
                                                          "occur should your pet be in the Litter-Robot "  +
                                                          "when a forced clean cycle is started."           , range: "(1..*)"
    }
}

def installed() {
    runIn(5, getLastCleaned)
    runEvery30Minutes(getLastCleaned)
}

def updated() {
    // update wait time if it is different
    if (waitTime?.trim() && waitTime != device.currentValue("cleanCycleWaitTimeMinutes"))
        dispatchCommand("W${waitTime}")
    
    // update sleep mode if the time has changed
    def oldStartTime = device.currentValue("sleepModeStartTime")
    def newStartTime = sleepTime ? timeToday(sleepTime).format("hh:mm") : oldStartTime?:"22:00"
    if (device.currentValue("sleepModeActive") == "on" && newStartTime != oldStartTime) {
        sendEvent(name: "sleepModeTime", value: "${device.currentValue("sleepModeTime")}\n(updating)")
        sleepOn()
    }
    
    // monitor if we need to force clean or not
    if (forceCleanCycleInterval) runEvery5Minutes(monitorForceClean)
    else unschedule(monitorForceClean)
}

def setRobotCleanerMovement() {
    startCleanCycle()
}

def on() {
    if (device.currentValue("power") == "on") startCleanCycle()
    else powerOn() // powering on will start a clean cycle automatically
}

def off() {
    // pausing/canceling a clean cycle isn't supported, so we'll just silently do nothing and let it finish
}

def lightOn() {
    dispatchCommand("N1")
}

def lightOff() {
    dispatchCommand("N0")
}

def panelLockOn() {
    dispatchCommand("L1")
}

def panelLockOff() {
    dispatchCommand("L0")
}

def powerOn() {
    dispatchCommand("P1")
}

def powerOff() {
    dispatchCommand("P0")
}

def sleepOn() {
    def now = new Date()
    def defaultSleepTime = now.format("yyyy-MM-dd'T'22:00:00.000Z", getTimeZone())
    use(TimeCategory) {
        dispatchCommand("S1${((Date.parse("HH", "24") - (timeToday(sleepTime?:defaultSleepTime) - now)).format("HH:mm:ss"))}")
    }
}

def sleepOff() {
    dispatchCommand("S0")
}

def startCleanCycle() {
    dispatchCommand("C")
}

def resetDrawerGauge() {
    def lrId = device.currentValue("litterRobotId")
    def lrName = device.currentValue("litterRobotNickname")
    def cycleCapacity = device.currentValue("cycleCapacity")
    log.info "Sending command to reset drawer gauge for Litter-Robot: ${device}"
    parent.resetDrawerGauge(lrId, [name: lrName, capacity: cycleCapacity])
    refresh()
}

def refresh() {
    getLastCleaned()
    parent.pollChildren()
}

def dispatchCommand(command) {
    def lrId = device.currentValue("litterRobotId")
    log.info "Sending command: <${command} for Litter-Robot: ${device}"
    parent.dispatchCommand(lrId, "<${command}")
    runIn(15, refresh)
}

def monitorForceClean() {
    if (!forceCleanCycleInterval) unschedule(monitorForceClean) // clean up schedule just in case
    else {
        use(TimeCategory) {
            if ((parent.parseLrDate(device.currentValue("lastCleaned")) + forceCleanCycleInterval.hours) < new Date() && !isInSleepMode()) {
                log.info "Forcing clean cycle"
                setRobotCleanerMovement()
            }
        }
    }
}

Boolean isInSleepMode() {
    if (device.currentValue("sleepModeTime") ==~ "\\d{2}:\\d{2} [AP]M - \\d{2}:\\d{2} [AP]M") {
        use(TimeCategory) {
            def now = new Date()
            def hhmm = device.currentValue("sleepModeStartTime").split(":").collect{it as int}
            def start = now.plus(0).clearTime() + hhmm[0].hours + hhmm[1].minutes
            hhmm = device.currentValue("sleepModeEndTime").split(":").collect{it as int}
            def stop = now.plus(0).clearTime() + hhmm[0].hours + hhmm[1].minutes
            if(stop < start) {
                if(now < start) start -= 24.hours
                else stop += 24.hours
            }
            if(now > stop) {
                start += 24.hours
                stop+=24.hours
            }
            return (start <= now && now < stop)
        }
    } else return false
}

def getActivities() {
    def lrId = device.currentValue("litterRobotId")
    def activities = parent.getActivity(lrId)
    activities
}

def getLastCleaned() {
    def lastCleaned = getActivities().find { it.unitStatus == "CCC" || it.unitStatus == "CCP" }?.timestamp
    if(lastCleaned) { // let's only update this if we get a value back
        sendEvent(name: "lastCleaned", value: lastCleaned)
        setRobotStatusText()
    }
}

def parseEventData(Map results) {
    results.each {name, value ->
        switch (name) {
            case "lastSeen": // store this in the state as we don't necessarily care to display this to the end user
                state.lastSeen = value
                break
            case "nightLightActive":
            case "panelLockActive":
                value = (value == "1" ? "on" : "off")
                sendEvent(name: name, value: value)
                break
            case "powerStatus":
                switch (value) {
                    case "AC":
                        value = "mains"
                        break
                    case "DC": // LR is using the backup battery
                        value = "battery"
                        break
                    default:
                        value = "unknown"
                        break
                }
                sendEvent(name: "powerSource", value: value)
                break
            case "sleepModeActive":
                setSleepModeStatuses(value, results.lastSeen)
                break
            case "sleepModeStartTime": // always returned as 0; ignore and use sleepModeActive to set this
            case "sleepModeEndTime": // always returned as 0; ignore and use sleepModeActive to set this
                break
            case "unitStatus":
                parseUnitStatus(value, results.lastSeen)
                break
            default:
                sendEvent(name: name, value: value)
                break
        }
    }
    setRobotStatusText()
}

def setRobotStatusText() {
    def lastCleaned = parent.parseLrDate(device.currentValue("lastCleaned"))
    use(TimeCategory){
        def format = "hh:mm aa"
        if (lastCleaned && (new Date() - lastCleaned) >= (23.hours+45.minutes)) format = "MMM dd @ hh:mm aa"
        sendEvent(name: "robotStatusText", value: "drawer: ${Math.round(device.currentValue("cycleCount")/device.currentValue("cycleCapacity")*100)}% full\nlast cleaned: ${lastCleaned?.format(format, getTimeZone())?:"unknown"}")
		sendEvent(name: "drawerLevel", value: Math.round(device.currentValue("cycleCount")/device.currentValue("cycleCapacity")*100))
    }
}

def parseUnitStatus(status, lastSeen) {
    // Create default events
    def events = [:]
    events["robotCleanerMovement"]     = [value: ""]
    events["lastStatusCode"]           = [value: status]
    events["acceleration"]             = [value: "inactive"]
    events["contact"]                  = [value: "closed"]
    events["DeviceWatch-DeviceStatus"] = [value: "online"]
    events["healthStatus"]             = [value: "online"]
    events["motion"]                   = [value: "inactive"]
    events["power"]                    = [value: "on"]
    events["switch"]                   = [value: "off"]
    events["tamper"]                   = [value: "clear"]
    
    def robotCleanerMovement = "idle";
    
    // All known statuses listed for sake of advancing this code even though some of them do not currently change default events above
    switch (status) {
        case "BR": // Bonnet Removed - The Litter-Robot can't function until the top is secured
            events["contact"].value = "open"
            events["tamper"].value = "detected"
			events["robotCleanerMovement"].value = "alarm";
            break
        case "CCC": // Clean Cycle Complete - Clean litter is now ready for next use
            events["lastCleaned"] = [value: lastSeen]
			events["robotCleanerMovement"].value = "idle";
            break
        case "CCP": // Clean Cycle In Progress - Litter-Robot is running a Clean Cycle
            events["acceleration"].value = "active"
            events["contact"].value = "open"
            events["lastCleaned"] = [value: lastSeen]
            events["switch"].value = "on"
			events["robotCleanerMovement"].value = "cleaning";
            break
        case "CSF": // Cat Sensor Fault - Weight in the Litter-Robot is too heavy
        case "SCF": // Cat Sensor Fault Startup - Weight in the Litter-Robot is too heavy
			events["robotCleanerMovement"].value = "alarm";
            break
        case "CSI": // Cat Sensor Interrupted - Cat re-entered the Litter-Robot while cycling
            events["contact"].value = "open"
            events["motion"].value = "active"
			events["robotCleanerMovement"].value = "alarm";
            break
        case "CST": // Cat Sensor Timing - Cat was in Litter-Robot, timer waiting to start Clean Cycle
            events["motion"].value = "active"
			events["robotCleanerMovement"].value = "idle";
            break
        case "DF1": // Drawer Is Full - Empty the Waste Drawer soon (2 cycles left)
        case "DF2": // Drawer Is Full - Empty the Waste Drawer soon (1 cycle left)
        case "DFS": // Drawer Full - Your Litter-Robot is full. It won't auto cycle anymore
        case "SDF": // Started with Drawer Full? - Your Litter-Robot is full. It won't auto cycle anymore
			events["robotCleanerMovement"].value = "alarm";
            break
        case "DHF": // Dump + Home Position Fault - Litter-Robot unable to find the dump and home position
        case "DPF": // Dump Position Fault - Litter-Robot unable to find the dump position
        case "HPF": // Home Position Fault - Litter-Robot unable to find the home position
			events["robotCleanerMovement"].value = "alarm";
            break
        case "EC": // Empty Cycle - Emptying all of the litter into the waste drawer
			events["robotCleanerMovement"].value = "homing";
            break
        case "OFF": // Power Off - The Litter-Robot is turned off and will not run cycles
            events["power"].value = "off"
			events["robotCleanerMovement"].value = "powerOff";
            break
        case "OFFLINE": // Offline - The Litter-Robot is not connected to the internet
            events["DeviceWatch-DeviceStatus"].value = "offline"
            events["healthStatus"].value = "offline"
			events["robotCleanerMovement"].value = "powerOff";
            break
        case "OTF": // Over Torque Fault - The globe rotation was over torqued
			events["robotCleanerMovement"].value = "alarm";
            break
        case "P": // Clean Cycle Paused - Press Cycle to resume or press Empty or Reset to abort Clean Cycle.
            events["contact"].value = "open"
			events["robotCleanerMovement"].value = "alarm";
            break
        case "PD": // Pinch Detect - Anti-Pinch safety feature was triggered
        case "SPF": // Pinch Detect Startup - Anti-Pinch safety feature was triggered
			events["robotCleanerMovement"].value = "alarm";
            break
        case "RDY": // Ready - The Litter-Robot is ready for your cat
			events["robotCleanerMovement"].value = "idle";
            break
        case "UNKNOWN": // Unknown
        default:
			events["robotCleanerMovement"].value = "alarm";
            break
    }

    events.each {k, v ->
        sendEvent(name: k, value: v.value)
    }
}

def setSleepModeStatuses(value, robotLastSeen) {
    def sleepModeActive = "off"
    Date sleepModeStartTime
    Date sleepModeEndTime
    def sleepModeTime = "off"
    if (value.startsWith("1")) {
        sleepModeActive = "on"
        def lastSeen = parent.parseLrDate(robotLastSeen)
        use(TimeCategory) {
            def dur = Date.parse("HH", "24") - Date.parse("HH:mm:ss", value.substring(1))
            sleepModeStartTime = lastSeen + dur
            sleepModeEndTime = sleepModeStartTime + 8.hours
            sleepModeTime = "${sleepModeStartTime.format("hh:mm aa", getTimeZone())} - ${sleepModeEndTime.format("hh:mm aa", getTimeZone())}"
        }
    } else if (value != "0") {
        sleepModeActive = "unknown"
        sleepModeTime = "unknown"
    }
    sendEvent(name: "sleepModeActive", value: sleepModeActive)
    sendEvent(name: "sleepModeStartTime", value: sleepModeStartTime?.format("HH:mm"))
    sendEvent(name: "sleepModeEndTime", value: sleepModeEndTime?.format("HH:mm"))
    sendEvent(name: "sleepModeTime", value: sleepModeTime)
}

def getTimeZone() {
    location.timeZone?:TimeZone.getDefault()
}