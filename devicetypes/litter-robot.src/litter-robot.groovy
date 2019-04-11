/**
 *  Litter-Robot
 *
 *  Copyright 2019 Nathan Spencer
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
 *  VERSION HISTORY
 *  DATE           VERSION        NOTES
 *  2019-04-10     1.0.0          Initial release
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
        capability "Robot Cleaner Movement"
        capability "Switch"
        capability "Tamper Alert"
        capability "Refresh"
        capability "Health Check"
        
        command "lightOn"
        command "lightOff"
        command "sleepOn"
        command "sleepOff"
        command "panelLockOn"
        command "panelLockOff"
    }
    
    preferences {
        input "waitTime"               , "enum"  , title:"Clean Cycle Wait Time (in Minutes)", options: ["3":"3","7":"7","F":"15"]
        input "sleepTime"              , "time"  , title:"Sleep Mode Start Time"
        input "forceCleanCycleInterval", "number", title:"Force Clean Cycle (in Hours)\n"                 +
                                                         "Forces a clean cycle to trigger if one hasn't " +
                                                         "occurred within the past specified number "     +
                                                         "of hours.\n"                                    +
                                                         "**Caution** Use of this feature is at you "     +
                                                         "and your pet's own risk. The developer is "     +
                                                         "not responsible for any injury that may "       +
                                                         "occur should your pet be in the Litter-Robot "  +
                                                         "when a forced clean cycle is started."           , range:"(1..*)"
    }

    tiles(scale: 2) {
        multiAttributeTile(name:"robotCleanerMovement", type:"generic", width:6, height:4) {
            tileAttribute("device.robotCleanerMovement", key:"PRIMARY_CONTROL") {
                attributeState "val"    , label:'${currentValue}'                                                               , backgroundColor:"#ffffff", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png", defaultState:true
                attributeState "BR"     , label:'Bonnet Removed'                                                                , backgroundColor:"#e86d13", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "CCC"    , label:'Clean Cycle Complete'                                                          , backgroundColor:"#ffffff", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "CCP"    , label:'Clean Cycle In Progress'                                                       , backgroundColor:"#00a0dc", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "CSF"    , label:'Cat Sensor Fault'                                                              , backgroundColor:"#e86d13", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "CSI"    , label:'Cat Sensor Interrupt'                                                          , backgroundColor:"#e86d13", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "CST"    , label:'Cat Sensor Timing'                                                             , backgroundColor:"#ffffff", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "DF1"    , label:'Drawer Is Full'                                                                , backgroundColor:"#e86d13", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "DF2"    , label:'Drawer Is Full'                                                                , backgroundColor:"#e86d13", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "DFS"    , label:'Drawer Is Full'                                                                , backgroundColor:"#e86d13", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "EC"     , label:'Empty Cycle'                                                                   , backgroundColor:"#00a0dc", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "OFF"    , label:'Power Off'                                                                     , backgroundColor:"#ffffff", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "OFFLINE", label:'Device Is Offline'                                                             , backgroundColor:"#cccccc", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "P"      , label:'Clean Cycle Paused'                                                            , backgroundColor:"#00a0dc", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "RDY"    , label:'Ready\nPress To Clean'  , action:"setRobotCleanerMovement", nextState:"startCC", backgroundColor:"#ffffff", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "SDF"    , label:'Drawer Is Full'                                                                , backgroundColor:"#bc2323", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
                attributeState "startCC", label:'Preparing To Clean'                                                            , backgroundColor:"#00a0dc", icon:"https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png"
            }
            tileAttribute("device.robotStatusText", key:"SECONDARY_CONTROL") {
                attributeState "val", label:'${currentValue}', defaultState:true
            }
        }
        
        standardTile("nightLightActive", "device.nightLightActive", width:2, height:2, decoration:"flat") {
            state "on"        , label:'night light:\non'         , action:"lightOff", nextState:"turningOff", backgroundColor:"#00a0dc", icon:"st.switches.light.on" , defaultState:true
            state "turningOff", label:'night light:\nturning off', action:"lightOff", nextState:"turningOff", backgroundColor:"#ffffff", icon:"st.switches.light.on"
            state "off"       , label:'night light:\noff'        , action:"lightOn" , nextState:"turningOn" , backgroundColor:"#ffffff", icon:"st.switches.light.off"
            state "turningOn" , label:'night light:\nturning on' , action:"lightOn" , nextState:"turningOn" , backgroundColor:"#00a0dc", icon:"st.switches.light.off"
        }
        
        standardTile("panelLockActive", "device.panelLockActive", width:2, height:2, decoration:"flat") {
            state "on"        , label:'panel:\nlocked'   , action:"panelLockOff", nextState:"turningOff", backgroundColor:"#00a0dc", icon:"st.presence.house.secured" 
            state "turningOff", label:'panel:\nunlocking', action:"panelLockOff", nextState:"turningOff", backgroundColor:"#ffffff", icon:"st.presence.house.secured"
            state "off"       , label:'panel:\nunlocked' , action:"panelLockOn" , nextState:"turningOn" , backgroundColor:"#ffffff", icon:"st.presence.house.unlocked", defaultState:true
            state "turningOn" , label:'panel:\nlocking'  , action:"panelLockOn" , nextState:"turningOn" , backgroundColor:"#00a0dc", icon:"st.presence.house.unlocked"
        }
        
        standardTile("switch", "device.switch", width:2, height:2, decoration:"flat") {
            state "on"        , label:'power:\non'         , action:"off", nextState:"turningOff", backgroundColor:"#00a0dc", icon:"st.samsung.da.RC_ic_power", defaultState: true
            state "turningOff", label:'power:\nturning off', action:"off", nextState:"turningOff", backgroundColor:"#ffffff", icon:"st.samsung.da.RC_ic_power"
            state "off"       , label:'power:\n$off'       , action:"on" , nextState:"turningOn" , backgroundColor:"#ffffff", icon:"st.samsung.da.RC_ic_power"
            state "turningOn" , label:'power:\nturning on' , action:"on" , nextState:"turningOn" , backgroundColor:"#00a0dc", icon:"st.samsung.da.RC_ic_power"
        }
        
        standardTile("sleepModeActive", "device.sleepModeActive", width:2, height:2, decoration:"flat") {
            state "on"        , label:'Sleep Mode:\n${currentValue}', action:"sleepOff", nextState:"turningOff", backgroundColor:"#00a0dc", icon:"st.Weather.weather4", defaultState:true
            state "turningOff", label:'Sleep Mode:\nturning off'    , action:"sleepOff", nextState:"turningOff", backgroundColor:"#ffffff", icon:"st.Weather.weather4"
            state "off"       , label:'Sleep Mode:\n${currentValue}', action:"sleepOn" , nextState:"turningOn" , backgroundColor:"#ffffff", icon:"st.Weather.weather4"
            state "turningOn" , label:'Sleep Mode:\nturning on'     , action:"sleepOn" , nextState:"turningOn" , backgroundColor:"#00a0dc", icon:"st.Weather.weather4"
        }
        
        valueTile("sleepModeTime", "device.sleepModeTime", width:2, height:1, decoration:"flat") {
            state "on" , label:'Sleep Mode:\n${currentValue}'              , defaultState:true
            state "off", label:'Sleep Mode: off\n(adjust time in settings)'
        }
        
        standardTile("refresh", "device.refresh", width:2, height:2, decoration:"flat") {
            state "refresh", label:"Refresh", action:"refresh", icon:"st.secondary.refresh"
        }
        
        standardTile("contact", "device.contact", width:1, height:1, decoration:"flat") {
            state "closed", label:'${currentValue}', icon:"st.contact.contact.closed", default:true
            state "open"  , label:'${currentValue}', icon:"st.contact.contact.open"
        }
        
        standardTile("motion", "device.motion", width:1, height:1, decoration:"flat") {
            state "inactive", label:'${currentValue}', icon:"st.motion.motion.inactive", default:true
            state "active"  , label:'${currentValue}', icon:"st.motion.motion.active"
        }
        
        standardTile("acceleration", "device.acceleration", width:1, height:1, decoration:"flat") {
            state "inactive", label:'${currentValue}', icon:"st.motion.acceleration.inactive", default:true
            state "active"  , label:'${currentValue}', icon:"st.motion.acceleration.active"
        }
        
        standardTile("tamper", "device.tamper", width: 1, height: 1, decoration: "flat") {
            state "clear"   , label:'${currentValue}', icon:"st.alarm.alarm.alarm", default:true
            state "detected", label:'${currentValue}', icon:"st.alarm.alarm.alarm"
        }

        main("robotCleanerMovement")
        details(["robotCleanerMovement", "nightLightActive", "panelLockActive", "switch", "sleepModeActive", "sleepModeTime", "refresh", "contact", "motion"])
    }
}

def installed() {
    runIn(5,getLastCleaned)
    runEvery30Minutes(getLastCleaned)
}

def updated() {
    // update wait time if it is different
    if (waitTime?.trim() && waitTime != device.currentValue("cleanCycleWaitTimeMinutes"))
        dispatchCommand("W${waitTime}")
    
    // update sleep mode if the time has changed or been removed
    def sleepMode = (device.currentValue("sleepModeActive") == "off" || device.currentValue("sleepModeActive") == "unknown") ? null : Date.parse("hh:mm aa",device.currentValue("sleepModeTime")).format("HH:mm")
    if (!sleepTime?.trim() && sleepMode) {
        sleepOff()
        sendEvent(name: "sleepModeActive", value: "turningOff", displayed: false)
    } else if (sleepTime?.trim()?.substring(11,16) != sleepMode) {
        sleepOn()
        sendEvent(name: "sleepModeActive", value: sleepMode ? "updating" : "turningOn", displayed: false)
    }
    
    // monitor if we need to force clean or not
    if (forceCleanCycleInterval) runEvery5Minutes(monitorForceClean)
    else unschedule(monitorForceClean)
}

def setRobotCleanerMovement() {
    dispatchCommand("C")
}

def on() {
    dispatchCommand("P1")
}

def off() {
    dispatchCommand("P0")
}

def lightOn() {
    dispatchCommand("N1")
}

def lightOff() {
    dispatchCommand("N0")
}

def sleepOn() {
    def now = new Date()
    def defaultSleepTime = now.format("yyyy-MM-dd'T'22:00:00.000Z",getTimeZone())
    use(TimeCategory) {
        dispatchCommand("S1${((Date.parse("HH","24") - (timeToday(sleepTime?:defaultSleepTime) - now)).format("HH:mm:ss"))}")
    }
}

def sleepOff() {
    dispatchCommand("S0")
}

def panelLockOn() {
    dispatchCommand("L1")
}

def panelLockOff() {
    dispatchCommand("L0")
}

def refresh() {
    getLastCleaned()
    parent.pollChildren()
}

def dispatchCommand(command) {
    def lrId = device.currentValue("litterRobotId")
    log.info "Sending command: <${command} for Litter-Robot: ${device}"
    parent.dispatchCommand(lrId,"<${command}")
    runIn(15,refresh)
}

def monitorForceClean() {
    if (!forceCleanCycleInterval) unschedule(monitorForceClean) // clean up schedule just in case
    else {
        use(TimeCategory) {
            if ((parent.parseLrDate(device.currentValue("lastCleaned")) + forceCleanCycleInterval.hours) < new Date()) {
                log.info "Forcing clean cycle"
                setRobotCleanerMovement()
            }
        }
    }
}

def getActivities() {
    def lrId = device.currentValue("litterRobotId")
    def activities = parent.getActivity(lrId)
    activities
}

def getLastCleaned() {
    def lastCleaned = getActivities().find { it.unitStatus == "CCC" || it.unitStatus == "CCP" }?.timestamp
    sendEvent(name:"lastCleaned", value:lastCleaned, displayed:false)
    setRobotStatusText()
}

def parseEventData(Map results) {
    results.each {name, value ->
        switch (name) {
            case "lastSeen": // store this in the state as we don't necessarily care to display this to the end user
                state.lastSeen = value
                break
            case "nightLightActive":
            case "panelLockActive":
                value = (value=="1"?"on":"off")
                sendEvent(name:name, value:value)
                break;
            case "powerStatus":
                value = value == "AC" ? "mains" : "unknown"
                sendEvent(name:"powerSource", value:value, displayed:false)
                break
            case "sleepModeActive":
                setSleepModeStatuses(value, results.lastSeen)
                break
            case "unitStatus":
                parseUnitStatus(value, results.lastSeen)
                break
            default:
                sendEvent(name:name, value:value, displayed:false)
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
        sendEvent(name:"robotStatusText", value:"Drawer: ${Math.round(device.currentValue("cycleCount")/device.currentValue("cycleCapacity")*100)}% Full\nLast Cleaned: ${lastCleaned?.format(format,getTimeZone())?:"unknown"}", displayed:false)
    }
}

def parseUnitStatus(status, lastSeen) {
    // Create default events
    def events = [:]
    events["robotCleanerMovement"]     = [value:status]
    events["acceleration"]             = [value:"inactive"]
    events["contact"]                  = [value:"closed"]
    events["DeviceWatch-DeviceStatus"] = [value:"online",display:false]
    events["healthStatus"]             = [value:"online",display:false]
    events["motion"]                   = [value:"inactive"]
    events["switch"]                   = [value:"on"]
    events["tamper"]                   = [value:"clear"]
    
    // All known statuses listed for sake of advancing this code even though some of them do not currently change default events above
    switch (status) {
        case "BR": // Bonnet Removed
            events["contact"].value = "open"
            events["tamper"].value = "detected"
            break
        case "CCC": // Clean Cycle Complete - Clean litter is now ready for next use
            events["lastCleaned"] = [value:lastSeen]
            break
        case "CCP": // Clean Cycle In Progress - Litter-Robot is running a Clean Cycle
            events["acceleration"].value = "active"
            events["contact"].value = "open"
            events["lastCleaned"] = [value:lastSeen]
            break
        case "CSF": // Cat Sensor Fault - Weight in the Litter-Robot is too heavy
            break
        case "CSI": // Cat Sensor Interrupt - Cat re-entered the Litter-Robot while cycling
            events["contact"].value = "open"
            events["motion"].value = "active"
            break
        case "CST": // Cat Sensor Timing - Cat was in Litter-Robot, timer waiting to start Clean Cycle
            events["motion"].value = "active"
            break
        case "DF1": // Drawer Is Full - Empty the Waste Drawer soon - can still cycle a few more times
            break
        case "DF2": // Drawer Is Full - Empty the Waste Drawer soon - very limited cycles left
            break
        case "DFS": // Drawer Is Full - Empty the Waste Drawer soon - final warning
            break
        case "EC": // Empty Cycle - Emptying all of the litter into the waste drawer
            break
        case "OFF": // Power Off - The Litter-Robot is turned off and will not run cycles
            events["switch"].value = "off"
            break
        case "OFFLINE": // Offline - The Litter-Robot is not connected to the internet
            events["DeviceWatch-DeviceStatus"].value = "offline"
            events["healthStatus"].value = "offline"
            break
        case "P": // Clean Cycle Paused - Press Cycle to resume or press Empty or Reset to abort Clean Cycle.
            events["contact"].value = "open"
            break
        case "RDY": // Ready - The Litter-Robot is ready for your cat
            break
        case "SDF": // Drawer Is Completely Full - Empty the Waste Drawer now
            break
    }
    events.each {k,v->
        sendEvent(name:k, value:v.value, displayed:v.display)
    }
}

def setSleepModeStatuses(value,robotLastSeen) {
    def sleepModeActive = "off"
    def sleepModeTime = "off"
    if (value.startsWith("1")) {
        sleepModeActive = "on"
        def lastSeen = parent.parseLrDate(robotLastSeen)
        use(TimeCategory) {
            def dur = Date.parse("HH","24") - Date.parse("HH:mm:ss",value.substring(1))
            def sleepModeStartTime = lastSeen + dur
            def sleepModeEndTime = sleepModeStartTime + 8.hours
            sleepModeTime = "${sleepModeStartTime.format("hh:mm aa",getTimeZone())} - ${sleepModeEndTime.format("hh:mm aa",getTimeZone())}"
        }
    } else if (value != "0") {
        sleepModeActive = "unknown"
        sleepModeTime = "unknown"
    }
    sendEvent(name:"sleepModeActive", value:sleepModeActive)
    sendEvent(name:"sleepModeTime", value:sleepModeTime, displayed:false)
}

def getTimeZone() {
    location.timeZone?:TimeZone.getDefault()
}