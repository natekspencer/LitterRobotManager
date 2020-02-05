/**
 *  Litter-Robot Manager
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
 *  CHANGE HISTORY
 *  VERSION     DATE            NOTES
 *  1.0.0       2019-04-10      Initial release
 *  1.0.1       2019-04-23      Attempt to re-auth twice if first re-auth fails. Also adds support for resetting gauge
 *  1.0.2       2020-02-05      Adjustments to reauthorization fail logic. Prevent hitting API (except for login) when
 *                              not logged in. Update robots with [Disconnected] status when no longer logged in.
 *
 */

definition(
    name: "Litter-Robot Manager",
    namespace: "natekspencer",
    author: "Nathan Spencer",
    description: "Access and control your Litter-Robot robots.",
    category: "Pets",
    iconUrl: "https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot.png",
    iconX2Url: "https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@2x.png",
    iconX3Url: "https://raw.githubusercontent.com/natekspencer/LitterRobotManager/master/images/litter-robot@3x.png",
    singleInstance: true
) {
    appSetting "apiKey"
}

preferences {
    page(name: "mainPage")
    page(name: "authPage")
    page(name: "authResultPage")
}

def mainPage() {
    // Check for API key
    if (!appSettings.apiKey?.trim()) {
        dynamicPage(name: "mainPage", install: false) {
            section("API Key Missing") {
                paragraph("No API Key was found. Please go to the App Settings and enter your API Key.")
            }
        }
    } else {
        def robots = [:]
        // Get robots if we don't have them already
        if ((state.robots?.size()?:0) == 0 && state.token?.trim()) {
            getLitterRobots()
        }
        if (state.robots) {
            robots = state.robots
            robots.sort { it.value }
        }
            
        dynamicPage(name: "mainPage", install: true, uninstall: true) {
            if (robots) {
                section("Select which Litter-Robots to use:") {
                    input(name: "robots", type: "enum", title: "Litter-Robots", required: false, multiple: true, metadata: [values: robots])
                }
                section("How frequently do you want to poll the Litter-Robot cloud for changes? (Use a lower number if you care about trying to capture and respond to \"cleaning\" events as they happen)") {
                    input(name: "pollingInterval", title: "Polling Interval (in Minutes)", type: "enum", required: false, multiple: false, defaultValue: 5, description: "5", options: ["1", "5", "10", "15", "30"])
                }
            }
            section("Litter-Robot Authentication") {
                href("authPage", title: "Litter-Robot API Authorization", description: "${state.credentialStatus ? "${state.credentialStatus}${state.loggedIn ? "" : " - ${state.loginResponse}"}\n" : ""}Click to enter Litter-Robot credentials")
            }
            section ("Name this instance of ${app.name}") {
                label name: "name", title: "Assign a name", required: false, defaultValue: app.name, description: app.name, submitOnChange: true
            }
        }
    }
}

def authPage() {
    dynamicPage(name: "authPage", nextPage: "authResultPage", uninstall: false, install: false) {
        section("Litter-Robot Credentials") {
            input("email", "email", title: "Email", description: "Litter-Robot Email", required: true)
            input("password", "password", title: "Password", description: "Litter-Robot password", required: true)
        }
    }
}

def authResultPage() {
    log.info "Attempting login with specified credentials..."
    
    doLogin()
    log.info state.loginResponse
    
    // Check if login was successful
    if (state.token == null) {
        dynamicPage(name: "authResultPage", nextPage: "authPage", uninstall: false, install: false) {
            section("${state.loginResponse}") {
                paragraph ("Please check your credentials and try again.")
            }
        }
    } else {
        dynamicPage(name: "authResultPage", nextPage: "mainPage", uninstall: false, install: false) {
            section("${state.loginResponse}") {
                paragraph ("Please click next to continue setting up your Litter-Robot.")
            }
        }
    }
}

boolean doLogin(){
    def loggedIn = false
    def resp = doCallout("POST", "/login", [email: email, password: password, oneSignalPlayerId: "0"])
    state.loginAttempt = (state.loginAttempt ?: 0) + 1
    
    switch (resp.status) {
        case 403:
            state.loggedIn = false
            state.loginResponse = resp.data.message == "Forbidden" ? "Access forbidden: invalid API key" : resp.data.message
            state.uri = null
            state.token = null
            state.robots = null
            break
        case 401:
            state.loggedIn = false
            state.loginResponse = resp.data.userMessage
            state.uri = null
            state.token = null
            state.robots = null
            break
        case 200:
            state.loggedIn = true
            state.loginResponse = resp.data._developerMessage
            state.uri = resp.data._uri
            state.token = resp.data.token
            state.loginDate = toStDateString(parseLrDate(resp.data._created))
            state.remove("loginAttempt")
            break
        default:
            log.debug resp.data
            state.loggedIn = false
            state.loginResponse = "Login unsuccessful"
            state.uri = null
            state.token = null
            state.robots = null
            break
    }

    loggedIn = state.loggedIn
    state.credentialStatus = loggedIn ? "[Connected]" : "[Disconnected]"
    loggedIn
}

def reAuth() {
    if (!doLogin()) {
        runIn(60 * state.loginAttempt * state.loginAttempt, reAuth) // timeout or other login issue occurred, attempt again with exponential backoff
        getChildDevices().each {
            it.parseUnitStatus(state.credentialStatus, null)
            it.sendEvent(name: "robotStatusText", value: state.loginResponse)
        }
    }
}

// Get the list of Litter-Robots
def getLitterRobots() {
    def data = doCallout("GET", "/litter-robots", null).data
    // save in state so we can re-use in settings
    def robots = [:]
    data.each {
        robots[[app.id, it.litterRobotId].join('.')] = it.litterRobotNickname
    }
    state.robots = robots
    data
}

def doCallout(calloutMethod, urlPath, calloutBody) {
    doCallout(calloutMethod, urlPath, calloutBody, null)
}

def doCallout(calloutMethod, urlPath, calloutBody, queryParams){
    def isLoginRequest = urlPath == "/login"
   
    if (state.loggedIn || isLoginRequest) { // prevent unauthorized calls
        log.info "\"${calloutMethod}\"-ing to \"${urlPath}\""
    
        def params = [
            uri: "https://muvnkjeut7.execute-api.us-east-1.amazonaws.com",
            path: "/staging/${isLoginRequest ? "" : (state.uri?.trim()?:"")}${urlPath}",
            query: queryParams,
            headers: [
                "Content-Type": "application/json",
                "x-api-key": appSettings.apiKey,
                Authorization: state.token?.trim() ? "Bearer ${state.token as String}" : null
            ],
            body: calloutBody
        ]
        
        try {
            switch (calloutMethod) {
                case "GET":
                    httpGet(params) {resp->
                        return resp
                    }
                    break
                case "PATCH":
                    params.headers["x-http-method-override"] = "PATCH"
                    // NOTE: break is purposefully missing so that it falls into the next case and "POST"s
                case "POST":
                    httpPostJson(params) {resp->
                        return resp
                    }
                    break
                default:
                    log.error "unhandled method"
                    return [error: "unhandled method"]
                    break
            }
        } catch (groovyx.net.http.HttpResponseException e) {
            log.info e
            return e.response
        } catch (e) {
            log.error "Something went wrong: ${e}"
            return [error: e.message]
        }
    } else {
        log.info "skipping request since the user is not currently logged in"
        return []
    }
}

def installed() {
    initialize()
}

def updated() {
    unsubscribe()
    initialize()
}

def initialize() {
    // Tokens expire every 24 hours. Schedule to reauthorize every day
    if(state.loginDate?.trim()) schedule(parseStDate(state.loginDate), reAuth)

    def delete = getChildDevices().findAll { !settings.robots?.contains(it.deviceNetworkId) }
    delete.each {
        deleteChildDevice(it.deviceNetworkId)
    }
    
    def childDevices = []
    settings.robots.each {deviceId ->
        try {
            def childDevice = getChildDevice(deviceId)
            if(!childDevice) {
                log.info "Adding device: ${state.robots[deviceId]} [${deviceId}]"
                childDevice = addChildDevice(app.namespace, "Litter-Robot", deviceId, location.hubs[0]?.id, [label: state.robots[deviceId], completedSetup: true])
            }
            childDevices.add(childDevice)
        } catch (e) {
            log.error "Error creating device: ${e}"
        }
    }
    
    // set up polling only if we have child devices
    if(childDevices.size() > 0) {
        pollChildren()
        "runEvery${pollingInterval}Minute${pollingInterval != "1" ? 's' : ''}"("pollChildren")
    } else unschedule(pollChildren)
}

def pollChildren() {
    log.info "polling..."
    def devices = getChildDevices()
    if (devices.size() == 0) {
        log.info "no children to update: skipping polling"
    } else {
        def robots = getLitterRobots()
        devices.each {
            def dni = it.deviceNetworkId
            def deviceData = robots.find { [app.id, it.litterRobotId].join('.') == dni }
            if (deviceData) {
                it.parseEventData(deviceData)
            }
        }
    }
}

def dispatchCommand(litterRobotId, command) {
    log.info "dispatch command ${command} for ${litterRobotId}"
    def resp = doCallout("POST", "/litter-robots/${litterRobotId}/dispatch-commands", [command: command as String])
    resp.data
}

def getActivity(litterRobotId, limit=10) {
    def resp = doCallout("GET", "/litter-robots/${litterRobotId}/activity", null, [limit: limit])
    resp.data.activities
}

def resetDrawerGauge(litterRobotId, params) {
    def resp = doCallout("PATCH", "/litter-robots/${litterRobotId}", [litterRobotNickname: params.name, cycleCapacity: params.capacity, cycleCount: 0, cyclesAfterDrawerFull: 0])
    resp.data
}

def isoFormat() {
    "yyyy-MM-dd'T'HH:mm:ss.SSSZ"
}

def toStDateString(date) {
    date.format(isoFormat())
}

def parseStDate(dateStr) {
    dateStr?.trim() ? timeToday(dateStr) : null
}

def parseLrDate(dateStr) {
    dateStr?.trim() ? Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS", dateStr?.substring(0,23)) : null
}